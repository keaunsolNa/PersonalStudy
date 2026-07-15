Notion 원본: https://app.notion.com/p/39e5a06fd6d38190ac00e6d695f73694

# Java Virtual Threads와 Continuation 및 Pinning 진단

> 2026-07-15 신규 주제 · 확장 대상: JAVA(NIO Selector / 이벤트 루프)

## 학습 목표

- 플랫폼 스레드와 가상 스레드의 스케줄링 주체와 스택 저장 위치를 구분한다
- `Continuation` 의 yield/mount 메커니즘으로 블로킹이 어떻게 논블로킹으로 변환되는지 추적한다
- Pinning 이 발생하는 조건과 JFR·시스템 프로퍼티로 진단하는 절차를 익힌다
- 가상 스레드가 이득인 워크로드와 오히려 손해인 워크로드를 수치로 구분한다

## 1. NIO 이벤트 루프가 남긴 문제

`Selector` 기반 이벤트 루프는 C10K 를 해결했지만 대가가 있었다. 코드가 콜백과 상태 머신으로 조각난다. 요청 하나의 처리 흐름이 `accept → read → parse → DB 호출 → write` 라면, 각 단계가 별개의 콜백으로 흩어지고 지역 변수는 명시적 컨텍스트 객체에 담아야 한다. 스택 트레이스는 이벤트 루프에서 끊기고, `try/catch` 는 단계를 넘지 못하며, 디버거의 step over 는 의미를 잃는다. Reactor/WebFlux 가 `Context` 라는 별도 전파 메커니즘을 만들어야 했던 이유가 이것이다.

가상 스레드(JEP 444, Java 21 정식)는 반대 방향의 해법이다. **코드는 블로킹 스타일 그대로 두고, 블로킹의 비용을 없앤다.** `thread-per-request` 모델을 유지하면서 스레드를 싸게 만든다.

```java
// 블로킹 코드. 그대로 유지된다.
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 100_000; i++) {
        executor.submit(() -> {
            var res = httpClient.send(req, ofString());   // 블로킹처럼 보임
            return db.query(res.body());                  // 블로킹처럼 보임
        });
    }
}
```

10만 개 태스크에 플랫폼 스레드 10만 개가 필요하지 않다. 캐리어 스레드는 CPU 코어 수만큼만 있으면 된다.

## 2. 스레드의 실체 — 스택은 어디에 있는가

플랫폼 스레드는 OS 스레드와 1:1 이다. 생성 시 커널이 태스크 구조체를 만들고, 고정 크기 스택(리눅스 x64 기본 1MB, `-Xss` 로 조정)을 가상 메모리에 예약한다. 실제 물리 메모리는 터치한 페이지만 잡히므로 보통 수십 KB 지만, 가상 주소 공간과 커널 자료구조는 확정 비용이다. 컨텍스트 스위칭은 커널이 수행하며 대략 1~10μs 가 든다.

가상 스레드의 스택은 **힙에 있다.** JVM 은 실행 중인 가상 스레드의 스택을 캐리어 스레드의 네이티브 스택에 올려두고(mount), 블로킹이 발생하면 그 스택 프레임을 힙의 `StackChunk` 객체로 복사해 내리고(unmount) 캐리어를 놓아준다. 재개 시 힙에서 다시 복사해 올린다.

| 항목 | 플랫폼 스레드 | 가상 스레드 |
|---|---|---|
| 스케줄러 | OS 커널 | JVM(`ForkJoinPool`, FIFO 모드) |
| 스택 위치 | 네이티브 스택, 고정 예약 | 힙(`StackChunk`), 가변 |
| 생성 비용 | ~수십 μs, 커널 진입 | ~수백 ns, 객체 할당 |
| 초기 메모리 | 예약 1MB, 실제 수십 KB | 수백 B ~ 수 KB |
| 스위칭 비용 | 1~10μs, 커널 | ~수백 ns, 복사만 |
| 실용 상한 | 수천 개 | 수백만 개 |

가상 스레드의 캐리어 풀은 기본적으로 `Runtime.availableProcessors()` 크기의 `ForkJoinPool` 이며, 워크스틸링이 아니라 **FIFO 모드**로 동작한다(공정성 우선). 다음 프로퍼티로 조정한다.

```bash
-Djdk.virtualThreadScheduler.parallelism=8
-Djdk.virtualThreadScheduler.maxPoolSize=256
```

## 3. Continuation — 마운트/언마운트의 실체

가상 스레드의 심장은 `jdk.internal.vm.Continuation` 이다. 이것은 "실행을 중단했다가 나중에 이어서 재개할 수 있는 스택" 을 1급 객체로 만든 것으로, 학술 용어로는 one-shot delimited continuation 이다.

```java
// 개념 스케치 (internal API — 직접 사용 대상 아님)
ContinuationScope scope = new ContinuationScope("demo");
Continuation cont = new Continuation(scope, () -> {
    System.out.println("A");
    Continuation.yield(scope);   // 여기서 스택을 힙으로 복사하고 반환
    System.out.println("B");
});

cont.run();   // "A" 출력 후 yield 지점에서 리턴
cont.run();   // yield 지점부터 재개 → "B" 출력
System.out.println(cont.isDone());  // true
```

`yield` 는 현재 `ContinuationScope` 진입점부터 지금까지의 프레임들을 힙의 `StackChunk` 로 **복사**하고 네이티브 스택을 되감는다. `run` 은 반대로 `StackChunk` 를 네이티브 스택에 복사해 붙이고 yield 지점의 PC 로 점프한다.

가상 스레드는 이 위에 얇게 얹혀 있다. `VirtualThread.park()` 가 `Continuation.yield()` 를 호출하고, `unpark()` 가 캐리어 풀에 `Continuation.run()` 을 제출하는 구조다.

JDK 라이브러리 내부의 블로킹 지점 — `SocketChannel.read`, `LockSupport.park`, `ReentrantLock.lock`, `BlockingQueue.take`, `Thread.sleep` — 이 전부 이 park 로 재작성되었다. 그래서 **같은 소스 코드**가 플랫폼 스레드에서는 커널 블로킹으로, 가상 스레드에서는 continuation yield 로 동작한다. 내부적으로 소켓은 여전히 논블로킹 모드 + `Poller`(epoll) 로 등록되며, 준비되면 해당 가상 스레드를 unpark 한다. 즉 **NIO 이벤트 루프가 사라진 게 아니라 런타임 밑으로 숨은 것**이다.

스택 복사 비용은 프레임 깊이에 비례한다. JDK 는 lazy copy 최적화를 적용해 매번 전체를 복사하지 않지만, 깊은 스택(재귀, 두꺼운 프레임워크 필터 체인)에서 빈번히 yield 하면 복사 비용이 드러난다. 이것이 가상 스레드가 "무료" 가 아닌 첫 번째 이유다.

## 4. Pinning — 언마운트가 막힐는 경우

Pinning 은 가상 스레드가 블로킹해야 하는데 **언마운트하지 못해 캐리어 스레드를 붙잡고 있는** 상태다. 캐리어가 코어 수만큼밖에 없으므로, 핀 된 가상 스레드가 늘면 처리량이 급락한다. 최악의 경우 모든 캐리어가 핀 되어 데드락에 준하는 정지가 온다.

원인은 두 가지다.

**(1) 네이티브 프레임 위의 블로킹.** JNI 를 호출한 상태에서 블로킹하면 언마운트할 수 없다. 네이티브 스택 프레임은 힙으로 복사할 방법이 없기 때문이다. JDBC 드라이버가 네이티브 라이브러리를 쓰거나(Oracle OCI 등), JNI 로 암호화를 수행하는 경로가 여기 해당한다.

**(2) `synchronized` 블록 안의 블로킹 (Java 21~23).** `synchronized` 의 모니터는 스레드 정체성이 아니라 **캐리어 스레드에 귀속**되도록 구현되어 있었다. 언마운트 후 다른 캐리어에서 재개하면 모니터 소유권이 깨지므로, JVM 은 언마운트를 포기하고 핀 상태로 블로킹한다.

```java
private final Object lock = new Object();

// ❌ Java 21~23 에서 pinning
synchronized (lock) {
    var res = httpClient.send(req, ofString());   // 캐리어를 붙잡은 채 블로킹
}

// ✅ ReentrantLock 은 언마운트 가능
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    var res = httpClient.send(req, ofString());   // 정상 언마운트
} finally {
    lock.unlock();
}
```

`ReentrantLock` 은 `AbstractQueuedSynchronizer` 기반이고, AQS 의 대기는 `LockSupport.park` 로 구현되어 있어 continuation yield 를 탄다. 그래서 가상 스레드 친화적이다.

**JDK 24 의 JEP 491** 이 (2) 를 해소했다. 모니터를 캐리어가 아니라 가상 스레드에 귀속시키도록 재구현해, `synchronized` 안에서도 언마운트가 가능해졌다. Java 24 이상에서는 `synchronized` 로 인한 pinning 을 더 이상 걱정하지 않아도 된다. 다만 (1) 네이티브 프레임 pinning 은 여전히 남는다. 운영 JDK 버전이 21 LTS 인지 24+ 인지에 따라 대응이 완전히 달라지므로 먼저 확인해야 한다.

## 5. Pinning 진단 절차

### 5.1 시스템 프로퍼티 (Java 21~23)

```bash
java -Djdk.tracePinnedThreads=full -jar app.jar
```

`short` 는 핀을 유발한 프레임만, `full` 은 전체 스택을 출력한다.

```
Thread[#42,ForkJoinPool-1-worker-3,5,CarrierThreads]
    java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(...)
    java.base/jdk.internal.misc.InnocuousThread...
    app.OrderService.charge(OrderService.java:88) <== monitors:1
    app.OrderController.post(OrderController.java:31)
```

`<== monitors:1` 이 붙은 프레임이 범인이다. 다만 이 플래그는 **stdout 에 직접 찍고 성능 오버헤드가 크며**, JDK 24 에서 제거되었다. 운영 상시 사용은 부적절하고 부하 테스트 환경 전용이다.

### 5.2 JFR 이벤트 (권장)

`jdk.VirtualThreadPinned` 이벤트가 표준 경로다. 기본 임계값은 20ms 이며, 그보다 짧은 핀은 기록되지 않는다.

```bash
java -XX:StartFlightRecording=filename=rec.jfr,settings=profile \
     -jar app.jar

jfr summary rec.jfr
jfr print --events jdk.VirtualThreadPinned rec.jfr
```

임계값을 낮추려면 커스텀 설정을 쓴다.

```xml
<!-- pinned.jfc -->
<event name="jdk.VirtualThreadPinned">
  <setting name="enabled">true</setting>
  <setting name="threshold">1 ms</setting>
  <setting name="stackTrace">true</setting>
</event>
```

```bash
java -XX:StartFlightRecording=filename=rec.jfr,settings=pinned.jfc -jar app.jar
```

함께 볼 이벤트가 두 개 더 있다. `jdk.VirtualThreadSubmitFailed` 는 캐리어 풀 제출 실패를, `jdk.VirtualThreadStart`/`End` 는 생성/종료 빈도를 알려준다. 후자는 기본 비활성이며 대량 발생하므로 필요할 때만 켠다.

### 5.3 임시 완화

핀의 근본 원인을 못 고치는 상황(서드파티 드라이버 등)이라면 캐리어 풀을 키워 완화할 수 있다. 근본 해결이 아니라 시간 벌기다.

```bash
-Djdk.virtualThreadScheduler.maxPoolSize=512
```

## 6. 스레드 로컬과 ScopedValue

가상 스레드는 수백만 개가 될 수 있으므로 `ThreadLocal` 이 위험해진다. 가상 스레드마다 별도 맵이 생기고, 값이 크면 힙을 그대로 잡아먹는다. `SecurityContextHolder`, MDC, 트랜잭션 컨텍스트가 전부 `ThreadLocal` 기반이라는 점이 마이그레이션의 실질적 장애물이다.

```java
// 가상 스레드 100만 개 × ThreadLocal 값 1KB = 1GB
static final ThreadLocal<Context> CTX = new ThreadLocal<>();
```

`ScopedValue`(JEP 481, 프리뷰)가 대안이다. 불변이고 스코프가 명확해 자식 스레드로의 상속이 구조적으로 안전하며, 값을 복사하지 않고 프레임 체인을 타고 조회한다.

```java
static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

ScopedValue.where(TRACE_ID, "abc-123").run(() -> {
    service.handle();          // 내부 어디서든 TRACE_ID.get() 가능
});                            // 스코프 종료 시 자동 해제. remove() 누수 없음
```

`StructuredTaskScope`(JEP 480/499) 와 결합하면 자식 가상 스레드로 자동 상속되고, 부모의 스코프 종료가 자식의 취소를 보장한다.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> user = scope.fork(() -> fetchUser(id));
    Subtask<Order[]> orders = scope.fork(() -> fetchOrders(id));
    scope.join().throwIfFailed();
    return new Dashboard(user.get(), orders.get());
}   // 하나라도 실패하면 나머지 자동 취소
```

주의: 가상 스레드는 **풀링하지 않는다.** `Executors.newVirtualThreadPerTaskExecutor()` 는 태스크마다 새 가상 스레드를 만든다. 가상 스레드를 풀에 넣는 것은 `ThreadLocal` 오염과 생성 비용 절감 실패를 동시에 부르는 안티패턴이다. 동시성 제한이 필요하면 스레드 수가 아니라 `Semaphore` 로 제한한다.

```java
private final Semaphore dbLimit = new Semaphore(20);   // 커넥션 풀 크기에 맞춤

dbLimit.acquire();
try { return db.query(sql); } finally { dbLimit.release(); }
```

## 7. 언제 이득이고 언제 손해인가

| 워크로드 | 가상 스레드 | 근거 |
|---|---|---|
| I/O 바운드, 동시성 1만+ (API 게이트웨이, BFF) | 큰 이득 | 스레드 수가 병목이었음 |
| I/O 바운드, 동시성 수백 (일반 CRUD) | 미미 | 플랫폼 스레드 200개로 충분. DB 커넥션이 진짜 병목 |
| CPU 바운드 (인코딩, 계산) | 손해 | 캐리어 = 코어 수. 컨텍스트 스위칭만 추가 |
| 깊은 스택 + 빈번한 yield | 손해 가능 | StackChunk 복사 비용 |
| 네이티브 라이브러리 의존 | 위험 | JNI pinning |
| Java 21~23 + synchronized 많은 레거시 | 위험 | 모니터 pinning. JDK 24 로 올리거나 ReentrantLock 전환 |

두 번째 행이 가장 자주 오해된다. 가상 스레드는 **스레드 수 상한을 없앨 뿐, 다운스트림 용량을 늘리지 않는다.** DB 커넥션 풀이 20이면 가상 스레드 10만 개가 커넥션 20개를 놓고 경쟁할 뿐이고, 응답 시간은 큐잉 지연만큼 늘어난다. 오히려 플랫폼 스레드 200개일 때는 스레드 풀이 자연스러운 백프레셔로 작동해 초과 요청을 빨리 거절했는데, 가상 스레드는 그 방어막을 제거해 **큐가 무한히 자라는 실패 모드**를 만든다. 그래서 `Semaphore` 나 별도 rate limiter 로 명시적 백프레셔를 다시 넣어야 한다.

Spring Boot 3.2+ 에서는 한 줄로 활성화된다.

```properties
spring.threads.virtual.enabled=true
```

활성화 전에 확인할 것은 세 가지다. JDK 버전(24+ 면 `synchronized` 걱정 없음), JDBC 드라이버가 순수 Java 인지, 그리고 `ThreadLocal` 을 대량 사용하는 필터가 있는지. 이 세 가지가 정리되지 않은 상태의 전환은 처리량 개선 없이 pinning 만 얻는 결과가 되기 쉽다.

## 참고

- JEP 444: Virtual Threads: https://openjdk.org/jeps/444
- JEP 491: Synchronize Virtual Threads without Pinning: https://openjdk.org/jeps/491
- JEP 480: Structured Concurrency: https://openjdk.org/jeps/480
- JEP 481: Scoped Values: https://openjdk.org/jeps/481
- Ron Pressler, "State of Loom": https://cr.openjdk.org/~rpressler/loom/loom-mar2021.html
- Oracle, Core Libraries — Virtual Threads Troubleshooting: https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
