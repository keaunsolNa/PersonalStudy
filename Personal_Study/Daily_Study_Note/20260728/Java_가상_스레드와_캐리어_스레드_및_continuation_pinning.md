Notion 원본: https://app.notion.com/p/3ab5a06fd6d3816bb9fcf2d33e2ae553

# Java 가상 스레드와 캐리어 스레드 및 continuation pinning

> 2026-07-28 신규 주제 · 확장 대상: JAVA(스레드 모델·동시성)

## 학습 목표

- 플랫폼 스레드와 가상 스레드의 스케줄링 계층 차이를 캐리어 스레드·ForkJoinPool 관점에서 구분한다.
- unmount/mount가 continuation 캡처로 구현되는 원리와 blocking 호출이 스케줄링 지점이 되는 조건을 정리한다.
- synchronized·네이티브 호출로 인한 pinning 이 캐리어 스레드를 묶는 메커니즘과 진단 방법을 파악한다.
- 처리량 관점에서 스레드 풀 대비 가상 스레드가 유리한 워크로드와 오히려 손해인 워크로드를 실측 기준으로 판단한다.

## 1. 두 계층 스케줄링 모델

플랫폼 스레드(platform thread)는 OS 커널 스레드 1:1 래퍼다. 생성 비용이 크고(스택 기본 1MB 예약), 컨텍스트 스위칭이 커널을 경유한다. 수만 개를 만들면 메모리와 스케줄링 오버헤드로 무너진다. 가상 스레드(virtual thread, JDK 21 정식)는 JVM이 관리하는 사용자 모드 스레드로, 실제 실행은 소수의 캐리어 스레드(carrier thread) 위에서 M:N으로 다중화된다.

캐리어 스레드는 기본적으로 ForkJoinPool(전용 인스턴스, parallelism = CPU 코어 수)의 워커다. 가상 스레드가 blocking 지점에 도달하면 캐리어에서 unmount 되고, 캐리어는 다른 가상 스레드를 실행한다. blocking 이 끝나면 임의의 캐리어에 다시 mount 된다.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<String>> futures = IntStream.range(0, 10_000)
        .mapToObj(i -> executor.submit(() -> fetch("https://api/" + i)))
        .toList();
    for (var f : futures) process(f.get());
}
```

## 2. continuation 으로 구현되는 unmount/mount

가상 스레드의 실행 상태는 Continuation 객체로 캡처된다. unmount 시점에 JVM은 현재 스택 프레임을 힙 위의 continuation 스택에 복사(freeze)하고 캐리어 스택을 비운다. mount 시점에 힙의 스택을 다시 캐리어 스택으로 복원(thaw)한다. blocking 지점이 곧 스케줄링 지점이며, JDK 21에서 대부분의 blocking I/O는 내부적으로 continuation yield 를 호출해 캐리어를 반환한다. 반대로 CPU 바운드 tight loop 는 yield 지점이 없어 캐리어를 계속 점유한다.

## 3. pinning — 캐리어가 묶이는 조건

특정 상황에서 가상 스레드는 blocking 을 만나도 unmount 하지 못하고 캐리어를 붙든 채 대기한다. 이를 pinning 이라 한다. 두 원인은 synchronized 블록 안에서 blocking 하는 경우(모니터 락이 스택에 고정되어 continuation freeze 불가)와 네이티브(JNI) 프레임 안에서 blocking 하는 경우다. 해법은 핫패스의 synchronized 를 ReentrantLock 으로 교체하는 것이다.

```java
private final ReentrantLock lock = new ReentrantLock();
public void goodWrite(Data d) {
    lock.lock();
    try { db.blockingWrite(d); }   // unmount 됨, 캐리어 해방
    finally { lock.unlock(); }
}
```

> 참고: JDK 24(JEP 491)에서 synchronized pinning 이 대부분 제거됐으나, 21 LTS 운영 환경에서는 여전히 ReentrantLock 을 기본 선택으로 둔다.

## 4. pinning 진단

JFR 에 jdk.VirtualThreadPinned 이벤트가 있어 pinning 지속 시간과 스택을 기록한다.

```bash
java -XX:StartFlightRecording=filename=app.jfr,settings=profile -jar app.jar
jfr print --events jdk.VirtualThreadPinned app.jfr | head -40
```

과거의 -Djdk.tracePinnedThreads 프로퍼티는 JDK 24에서 제거 예정이므로 신규 진단은 JFR 이벤트 기반으로 표준화한다.

## 5. 언제 이득이고 언제 손해인가

| 워크로드 | 플랫폼 스레드 풀 | 가상 스레드 | 판단 |
|---|---|---|---|
| I/O 바운드 다중 요청 | 풀 크기에 처리량 상한 | 요청당 1스레드로 선형 확장 | 가상 스레드 유리 |
| DB 커넥션 대기 위주 | 스레드 = 커넥션 상한 | 커넥션 풀이 실제 병목 | 가상 스레드 유리(커넥션 별도 상한) |
| CPU 바운드 계산 | 코어 수 풀이 최적 | yield 없어 이득 없음 | 플랫폼 스레드 유리 |
| synchronized 레거시 | 영향 없음 | pinning 위험 | 리팩터링 전엔 신중 |

가상 스레드는 처리량을 높이지 지연을 낮추지 않으며, 커넥션 풀 같은 유한 자원의 상한을 대체하지 않는다. 요청당 스레드를 만들어도 DB 커넥션은 세마포어로 제한해야 한다.

```java
private final Semaphore dbPermits = new Semaphore(50);
public Result query(String sql) throws InterruptedException {
    dbPermits.acquire();
    try { return jdbc.query(sql); }
    finally { dbPermits.release(); }
}
```

## 6. 풀링 금지 — 생성이 곧 자원

가상 스레드는 생성이 저렴하므로 풀링하지 않는다. newVirtualThreadPerTaskExecutor() 는 태스크마다 새 가상 스레드를 만들고 끝나면 버린다. ThreadLocal 이 많으면 가상 스레드마다 힙 사본이 생기므로 ScopedValue 로 대체하는 방향이 권장된다.

## 7. 구조적 동시성과의 결합

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> user  = scope.fork(() -> fetchUser(id));
    Subtask<Order> order = scope.fork(() -> fetchOrder(id));
    scope.join().throwIfFailed();   // 하나라도 실패하면 나머지 자동 취소
    return new Dashboard(user.get(), order.get());
}
```

## 8. 웹 프레임워크 통합 — Spring Boot 3.2+

Spring Boot 3.2부터 프로퍼티 하나로 웹 요청 처리 스레드를 가상 스레드로 전환할 수 있다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

켜기 전 두 가지를 점검한다. synchronized blocking 구간이 있으면 pinning 이 재현되어 오히려 느려질 수 있고, DB 커넥션 풀(HikariCP)은 여전히 유한해 응답 지연은 커넥션 풀 크기가 결정한다. 즉 가상 스레드는 스레드 병목만 없애고 커넥션·CPU 병목을 드러낸다.

## 9. 실측 관점

이득은 동시에 blocking 대기하는 태스크 수에 비례한다. 외부 API 팬아웃 게이트웨이에서 플랫폼 스레드 풀 200개는 201번째를 큐에서 대기시키지만 가상 스레드는 수만 개가 동시 대기해도 캐리어가 놀지 않는다. 메모리도 1만 개 기준 수 GB(플랫폼) 대 수십~수백 MB(가상)로 다르다. 리액티브가 콜백으로 얻던 동시성을 가상 스레드는 읽기 쉬운 순차 코드로 얻으며 스택 트레이스가 온전한 것도 장점이다.

## 참고

- JEP 444: Virtual Threads (JDK 21) — https://openjdk.org/jeps/444
- JEP 491: Synchronize Virtual Threads without Pinning (JDK 24) — https://openjdk.org/jeps/491
- JEP 453: Structured Concurrency (Preview) — https://openjdk.org/jeps/453
- Oracle Core Libraries — Virtual Threads — https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
