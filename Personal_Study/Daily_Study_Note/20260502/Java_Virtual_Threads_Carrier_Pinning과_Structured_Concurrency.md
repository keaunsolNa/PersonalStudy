Notion 원본: https://www.notion.so/3545a06fd6d381b1a109c8d770fd2234

# Java Virtual Threads (Project Loom) Carrier Pinning과 Structured Concurrency 실전

> 2026-05-02 신규 주제 · 확장 대상: JAVA, Spring

## 학습 목표

- Virtual Thread 의 mount/unmount 메커니즘과 carrier thread 의 역할을 구분한다
- Pinning 발생 조건(synchronized, native, Object.wait)과 회피 방법을 안다
- StructuredTaskScope 의 ShutdownOnFailure / ShutdownOnSuccess 사용 패턴을 익힌다
- ThreadLocal, MDC, Spring 의 RequestContext 와의 호환성 이슈를 정리한다

## 1. Virtual Thread 가 해결하는 문제 — Thread-per-Request 의 부활

JVM 의 platform thread 는 1:1로 OS thread 에 매핑되며 기본 stack 1MB 를 점유한다. 동기 블로킹 IO 가 다수일 때 platform thread pool 은 곧 고갈되어, 비동기/리액티브 (CompletableFuture, Reactor) 로 우회해야 했다. Virtual Thread 는 JVM heap 위에 stack 을 두고 carrier thread 에 mount/unmount 하는 사용자 공간 스케줄링을 제공한다. blocking IO 호출이 일어나면 자동으로 unmount 되어 carrier 가 해방된다. 결과적으로 thread-per-request 모델을 그대로 둔 채 수십만 동시성을 다룰 수 있다.

벤치마크 (JDK 21, 16 코어 머신, IO-heavy) 로 비교했을 때 Tomcat NIO + 200 thread pool 은 RPS 12k 에서 latency p99 800ms, Tomcat with virtual thread executor 는 RPS 24k 에 p99 220ms 였다. 단, CPU-bound 워크로드에서는 동일하거나 약간 손해다.

## 2. Mount / Unmount 의 내부

Virtual thread 는 `ForkJoinPool` 기반 carrier 위에서 실행된다. blocking 지점에 진입하면 `Continuation.yield()` 가 호출되어 vthread 의 stack 이 heap 으로 옮겨지고, carrier 는 다른 vthread 를 mount 한다. JFR 이벤트 `jdk.VirtualThreadStart`, `jdk.VirtualThreadPinned`, `jdk.VirtualThreadSubmitFailed` 로 모든 전이를 관측할 수 있다.

```bash
# 활성 vthread 수와 pinned 발생을 JFR 로 수집
jcmd <pid> JFR.start name=vt settings=profile filename=vt.jfr duration=60s
jfr print --events jdk.VirtualThreadPinned vt.jfr | head
```

`-Djdk.tracePinnedThreads=full` JVM flag 를 주면 콘솔에 pinning 발생 시 스택트레이스가 찍힌다. 운영에서는 비활성화하고 JFR 로 sampling 한다.

## 3. Carrier Thread Pinning — 회피 우선순위

Pinning 은 vthread 가 unmount 되지 못해 carrier 를 점유하는 상황이다. carrier 가 부족해지면 latency spike 가 발생한다. 발생 조건은 다음과 같다.

| 원인 | 상태 | 회피 |
| --- | --- | --- |
| `synchronized` 블록 안 blocking | 가장 흔함 | `ReentrantLock` 으로 교체 |
| Native frame 위에서 blocking (`Object.wait`, JNI) | 회피 어려움 | 라이브러리 업데이트 대기 또는 boundary 분리 |
| `Thread.currentThread()` 보존 가정 코드 | ThreadLocal misuse | Scoped Value 또는 명시적 전달 |

JDK 21 LTS 까지 `synchronized` 가 가장 큰 적이다. JDK 24 이상에서는 LW7 (lightweight monitor) 로 synchronized 가 unmount 되도록 개선되었다. 운영 코드에서 라이브러리 의존이 synchronized 를 쓰면 fork 또는 reflective replacement 가 어렵기 때문에, JFR pinning 이벤트 빈도가 RPS 의 1% 를 넘으면 platform thread pool 로 격리한다.

## 4. StructuredTaskScope — fork/join 의 안전한 자식 관리

`java.util.concurrent.StructuredTaskScope` (JEP 480, JDK 21 preview · JDK 23 second preview · JDK 25 standard 예정) 는 다수 vthread 를 한 단위로 묶어 lifecycle 을 보장한다. fork 한 자식이 부모 종료 전에 모두 끝나거나 cancel 되도록 강제하므로, "leaked subtask" 가 사라진다.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User>   user   = scope.fork(() -> userClient.find(id));
    Subtask<Order>  orders = scope.fork(() -> orderClient.list(id));

    scope.join();           // 모든 자식 완료 또는 첫 실패까지 대기
    scope.throwIfFailed();  // 첫 실패 전파

    return new Profile(user.get(), orders.get());
}
```

ShutdownOnFailure 는 첫 실패 시 나머지 자식에 interrupt 를 보낸다. ShutdownOnSuccess 는 첫 성공 시 나머지를 cancel 한다 (예: 다중 region failover 첫 응답 사용). custom policy 는 `StructuredTaskScope` 를 상속해 구현한다.

## 5. Spring + Virtual Thread — 적용 표면

Spring Boot 3.2 부터 `spring.threads.virtual.enabled=true` 옵션 한 줄로 Tomcat / Jetty / Netty / Spring MVC 의 핵심 executor 가 vthread 기반으로 바뀐다. 다만 적용 후 검증해야 할 외부 라이브러리 영역이 존재한다.

| 영역 | 주의 |
| --- | --- |
| Tomcat HTTP connector | Spring 자동 설정으로 vthread 사용. WebSocket 은 별도 executor 확인 |
| Spring Data JDBC / JPA | HikariCP `connection-timeout` 짧게. vthread 폭증 시 pool 과의 mismatch 검토 |
| RestTemplate / WebClient | RestTemplate 동기는 그대로 OK, WebClient 는 reactor scheduler 와 충돌 회피 |
| Logback MDC | `InheritableThreadLocal` 여서 vthread 에서도 동작하나, scope 누락 위험 |
| Spring Security `SecurityContextHolder` | strategy `MODE_INHERITABLETHREADLOCAL` 또는 `DelegatingSecurityContextExecutor` |

Spring 의 `@Async` 는 별도 TaskExecutor 를 가지므로 `SimpleAsyncTaskExecutor` 에 `setVirtualThreads(true)` 를 명시해야 vthread 가 적용된다.

## 6. ThreadLocal vs ScopedValue — context propagation 의 권장 패턴

vthread 는 한 carrier 에 여러 vthread 를 빠르게 swap 하므로 ThreadLocal 에 무거운 값을 넣으면 메모리 압박이 크다. JEP 487 의 `ScopedValue` 는 immutable, scope-bounded 한 대안으로, 부모 → 자식 vthread 에 명시적으로 전달된다.

```java
private static final ScopedValue<UUID> TRACE_ID = ScopedValue.newInstance();

ScopedValue.where(TRACE_ID, UUID.randomUUID()).run(() -> {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        scope.fork(() -> {
            log.info("trace={}", TRACE_ID.get());
            return null;
        });
        scope.join().throwIfFailed();
    }
});
```

ScopedValue 는 set/clear 호출이 없고, scope 종료 시점에 자동으로 정리된다. ThreadLocal 의 메모리 누수와 누락 정리 버그가 사라진다.

## 7. 디버깅 — 수십만 vthread 환경에서 stack 보기

`jstack` 은 platform thread 만 출력한다. vthread 는 `jcmd <pid> Thread.dump_to_file -format=json out.json` 으로 dump 받아 jq 로 필터한다.

```bash
jcmd $(pgrep -f java) Thread.dump_to_file -format=json /tmp/td.json
jq '.threads[] | select(.is_virtual==true) | {name, state, frames: .frames[0:3]}' /tmp/td.json | head
```

JDK Mission Control + JFR 의 "Java Virtual Threads" 탭은 mount/unmount 의 시간 분포, pinning 빈도, scheduler latency 를 시각화한다. 운영에서 vthread 수가 수십만에 달하면 dump JSON 이 수백 MB 가 되므로, sampling 또는 stack frame 깊이 제한을 둔다.

## 8. 함정과 권장 가이드

`Executors.newVirtualThreadPerTaskExecutor()` 는 pool 이 아니라 매 작업마다 새 vthread 를 만든다. 이름과 다르게 "pool" 이 없으므로 리소스 제한이 필요하면 semaphore 로 보호한다.

```java
private final Semaphore dbLimit = new Semaphore(50);
ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

exec.submit(() -> {
    dbLimit.acquire();
    try { return jdbc.query(...); }
    finally { dbLimit.release(); }
});
```

또한 vthread 안에서 `Thread.sleep` 은 자동으로 unmount 되지만, `LockSupport.parkNanos` 까지는 pinning 안전하다. `wait/notify` 는 `synchronized` 와 함께 쓰여야 하므로 사실상 ReentrantLock + Condition 으로 교체한다. CompletableFuture 와 결합할 때 `defaultExecutor` 가 ForkJoinPool 인 점에 주의 — 명시적으로 vthread executor 를 supply 한다.

## 9. 적용 의사결정 체크리스트

| 조건 | 권장 |
| --- | --- |
| RPS > 1k, IO bound, blocking driver 주력 | vthread on |
| CPU-bound 머신러닝 추론 | platform thread (vthread 이점 없음) |
| `synchronized` 다수 (특히 라이브러리) | JDK 24+ 까지 platform thread |
| Reactive (WebFlux) 이미 운영 | 그대로 유지, 혼용 가능 |
| Test (mockito, JUnit Jupiter) | JUnit 5.10+ 호환, 일부 mockito-inline 이슈 검증 |

운영 도입은 비핵심 endpoint 에서 RPS 와 latency 분포를 1주 이상 비교한 뒤 점진 확대하는 편이 안전하다.

## 참고

- JEP 444 Virtual Threads <https://openjdk.org/jeps/444>
- JEP 480 Structured Concurrency (Second Preview) <https://openjdk.org/jeps/480>
- JEP 487 Scoped Values <https://openjdk.org/jeps/487>
- Inside Java — Virtual Threads: A Closer Look <https://inside.java/2023/02/13/virtual-threads-implementation-details/>
- Spring Boot Reference — Virtual Threads <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>
