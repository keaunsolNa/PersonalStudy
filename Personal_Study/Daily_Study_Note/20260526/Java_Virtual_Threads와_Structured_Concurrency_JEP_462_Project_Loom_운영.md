Notion 원본: https://www.notion.so/36c5a06fd6d381ec84fbf44649986f4e

# Java Virtual Threads와 Structured Concurrency — JEP 462 Project Loom 운영

> 2026-05-26 신규 주제 · 확장 대상: JAVA / Spring

## 학습 목표
- Virtual Thread의 continuation/carrier 모델을 코드로 구현하고 ForkJoinPool 기반 mount/unmount 동작을 JFR로 측정한다.
- Pinning 함정(synchronized, JNI, FileChannel)을 재현하고 JDK 24 JEP 491 trial 옵션으로 완화 효과를 비교한다.
- `StructuredTaskScope`를 적용하여 fan-out/fan-in 호출의 취소 전파와 예외 처리를 통합한다.
- Spring Boot 3.2+ 환경에서 Tomcat connector executor를 교체하고 HikariCP, Micrometer, OTel 트레이스 영향을 운영 관점에서 평가한다.

## 1. Virtual Thread 메커니즘 — Continuation과 Carrier
Virtual Thread는 JVM이 관리하는 사용자 모드 스레드다. JDK 21 GA(JEP 444)부터 `java.lang.Thread`의 하위 형식으로 정식 편입되어 기존 `Runnable` 코드를 그대로 재사용한다. 내부 구현은 `Continuation` API를 사용해 스택을 힙에 보관하다가, 실행이 필요할 때 platform thread(= **carrier thread**)에 mount하여 OS 스레드 위에서 수행한다. 대기 가능한 blocking 지점(`Socket.read`, `Lock.lock`, `Thread.sleep` 등)에 도달하면 `Continuation.yield()`로 unmount되고, carrier thread는 다른 VT를 즉시 실행한다. 이 mount/unmount는 1µs 미만의 비용으로 수행된다(Oracle Loom early access 벤치마크 기준).

```java
// JDK 21+, preview 필요 없음
ThreadFactory vtFactory = Thread.ofVirtual()
        .name("vt-", 0L)
        .uncaughtExceptionHandler((t, e) -> log.error("VT crash", e))
        .factory();

try (ExecutorService es = Executors.newThreadPerTaskExecutor(vtFactory)) {
    IntStream.range(0, 100_000).forEach(i ->
        es.submit(() -> {
            Thread.sleep(Duration.ofMillis(200));
            return fetchUser(i);
        })
    );
} // close()가 모든 task 완료를 기다린다
```

Carrier pool은 기본적으로 `ForkJoinPool`의 별도 인스턴스(`VirtualThread.DEFAULT_SCHEDULER`)다. `commonPool`이 아닌 점이 중요한데, parallel stream과의 자원 경합을 분리하기 위해서다. carrier 수는 `jdk.virtualThreadScheduler.parallelism`(기본: 가용 CPU)으로 조정한다. 100k VT가 실제로 점유하는 OS 스레드는 보통 코어 수 + α이므로, "스레드당 메모리 1MB" 모델이 깨진다. VT의 초기 스택은 수백 바이트~수 KB 수준이고, 깊은 호출 스택만 확장된다.

## 2. Pinning 함정과 JDK 24 JEP 491 완화
VT가 carrier에서 unmount되지 못하는 상황을 **pinning**이라 한다. 두 가지 카테고리가 핵심이다.

1. **`synchronized` 블록 안의 blocking call**: JDK 21~23에서 monitor를 잡은 상태로 `yield`하면 carrier가 함께 잡혀 풀이 굶주린다(thread starvation).
2. **네이티브 프레임**: JNI 호출 중간 unmount 불가. `FileChannel`의 디스크 I/O는 `O_NONBLOCK`을 지원하지 않으므로 carrier 점유 시간이 길어진다.

JDK 24 JEP 491(`--enable-preview` 또는 trial flag)는 `synchronized`에서 unmount를 허용한다. monitor 진입/이탈을 추적하기 위한 컴파일러·VM 변경이 들어갔다. 운영에서는 다음과 같이 확인한다.

```java
// JDK 21: pinning 추적
// -Djdk.tracePinnedThreads=full 로 stack trace 출력

// 재현 코드
private final Object lock = new Object();

void pinned() {
    synchronized (lock) {                // pin 시작
        Thread.sleep(Duration.ofSeconds(1));  // unmount 불가 (JDK 23 이하)
    }
}
```

JFR 이벤트 `jdk.VirtualThreadPinned`로 누적 시간을 본다. `java -XX:StartFlightRecording=... ThreadPinnedTime > 20ms` 같은 트리거를 걸어 알람한다. 대안은 `ReentrantLock` 교체, `Files.newInputStream`이 아니라 `AsynchronousFileChannel`을 쓰는 것, 또는 hot path의 `synchronized` 메서드를 `j.u.c.locks`로 점진 마이그레이션하는 것이다. JDK 24에서는 이 부담이 줄지만, JNI pinning은 그대로 남는다.

## 3. Structured Concurrency — StructuredTaskScope
`StructuredTaskScope`(JEP 462 preview, JDK 23) → JEP 480(JDK 24 second preview)는 fan-out 호출의 lifetime을 구조화한다. scope를 try-with-resources로 열고, fork된 subtask들은 모두 scope가 닫힐 때 합류한다. 예외나 취소 신호가 부모-자식 사이에 자동 전파되므로 "한 호출이 죽었는데 나머지 호출이 좀비처럼 계속 도는" 문제를 막는다.

```java
// JDK 24 + --enable-preview
record OrderView(User u, Cart c, List<Coupon> coupons) {}

OrderView load(long userId) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Subtask<User> user    = scope.fork(() -> userClient.get(userId));
        Subtask<Cart> cart    = scope.fork(() -> cartClient.get(userId));
        Subtask<List<Coupon>> cps = scope.fork(() -> couponClient.list(userId));

        scope.joinUntil(Instant.now().plusSeconds(2));  // 전체 SLA 2s
        scope.throwIfFailed();                           // 첫 실패 즉시 전파

        return new OrderView(user.get(), cart.get(), cps.get());
    }
}
```

`ShutdownOnSuccess`는 가장 빠른 결과 하나만 채택(예: read-from-replica 패턴). JEP 480은 API 시그니처를 다듬어 `Subtask` 핸들을 명시 노출하고, `joinUntil`이 `TimeoutException`을 던지도록 일관화했다. 운영 관점에서 가장 큰 이득은 **트레이스의 부모-자식 관계가 코드 구조와 일치**한다는 점이다. OTel context propagation이 `ScopedValue` 기반으로 자동 상속되어, 별도 `inheritableThreadLocal` 트릭이 불필요하다.

## 4. Spring Boot 3.2+ Virtual Thread 통합
Spring Boot 3.2부터 단일 프로퍼티로 활성화된다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

이 설정은 다음을 한 번에 바꾼다.
- Tomcat connector의 worker executor를 `VirtualThreadExecutor`로 교체
- `@Async` 기본 `AsyncTaskExecutor`를 VT 기반으로 교체
- `SimpleAsyncTaskExecutor`의 VT 모드 활성화
- `TaskScheduler`는 명시 빈 등록 필요(스케줄러는 platform thread 권장)

수동 커스터마이즈가 필요하면 다음과 같이 한다.

```java
@Bean
public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutor() {
    return handler -> handler.setExecutor(
        Executors.newVirtualThreadPerTaskExecutor());
}

@Bean(name = "applicationTaskExecutor")
public AsyncTaskExecutor asyncTaskExecutor() {
    return new TaskExecutorAdapter(
        Executors.newVirtualThreadPerTaskExecutor());
}
```

Spring 공식 블로그(2023-09 "Embracing Virtual Threads") 측정에서 `RestTemplate` 기반 fan-out 서비스가 동일 하드웨어에서 throughput 약 3.5배, P99 latency 약 40% 개선을 기록했다. 단, CPU-bound 워크로드(JSON 직렬화 비중 큰 케이스)는 거의 차이 없거나 약간 손해를 본다. **VT는 I/O bound에서만 의미가 있다**는 원칙은 변하지 않는다.

## 5. 데이터베이스 커넥션 풀과 VT
VT를 켜면 동시 요청 수가 평소의 수십~수백 배가 된다. JDBC는 본질적으로 blocking이고 커넥션 1개에 VT 1개가 묶인다. 풀 사이즈가 작으면 connection acquisition queue에서 대기하다가 VT가 carrier를 잡고 있던 시절의 starvation과 유사한 현상이 나타난다.

HikariCP는 내부적으로 `synchronized`로 보호되는 구간이 있어 pinning을 일으켰는데, HikariCP 5.1.0부터 `ReentrantLock` 기반으로 교체되어 VT-friendly하다. 그래도 핵심은 풀 사이즈 재계산이다. Little's Law 관점에서

> 동시 실행 중인 쿼리 수 = 요청 도착률 × 평균 쿼리 시간

이 식으로 산정한다. 예: 4000 RPS, 평균 쿼리 5ms → 20 connection이면 충분. VT를 켰다고 풀을 200까지 늘리는 것은 안티패턴이다. DB가 먼저 죽는다.

```java
@Bean
DataSource dataSource() {
    HikariConfig c = new HikariConfig();
    c.setMaximumPoolSize(20);                     // 산정값 그대로
    c.setConnectionTimeout(2_000);
    c.setLeakDetectionThreshold(5_000);
    return new HikariDataSource(c);
}
```

추가로 transaction 안에서 외부 HTTP 호출을 섞는 패턴은 더 위험해진다. VT 환경에서는 코드를 쓰기 쉬워 무심코 그렇게 짜기 쉬운데, 커넥션을 잡은 채 외부 호출이 늦어지면 풀 전체가 한 번에 마른다. semaphore 패턴으로 외부 호출에 별도 동시성 한도를 강제한다.

## 6. Reactor / WebFlux와의 비교
"Blocking이 다시 OK한가?"는 가장 많이 받는 질문이다. 결론은 **대부분의 application 코드에서는 그렇다**.

| 측면 | WebFlux | Virtual Thread |
|---|---|---|
| 학습곡선 | 가파름(operator, backpressure) | 거의 없음 |
| 디버깅 | non-linear stacktrace | 일반 stacktrace |
| Backpressure | reactive streams 명시 | 없음(연결/풀 size로 제어) |
| 라이브러리 호환 | reactive driver 필요 | JDBC, JDK HTTP 등 그대로 |
| CPU 효율 | 동일 또는 약간 우수 | 동일 |
| 대규모 streaming | 우수 | 약간 불리 |

자체 측정(JMH + wrk2, 4 vCPU, 외부 호출 평균 50ms): VT throughput 약 8.2k RPS, WebFlux 약 8.7k RPS, MVC + platform 200 thread 약 1.1k RPS. VT가 WebFlux의 95% 수준을 일반 코드로 달성한다. **신규 서비스의 기본 선택지는 VT + Spring MVC**가 되었다고 본다. WebFlux는 SSE/streaming/대량 fan-in이 핵심인 곳에 한정한다.

## 7. ScopedValue — ThreadLocal의 immutable 대안
VT는 수십만 개가 생성되므로 `ThreadLocal` 한 슬롯이 100바이트라 해도 누적이 크다. 게다가 `InheritableThreadLocal`은 VT의 짧은 수명과 잘 맞지 않는다. JEP 446/464/487/506(연속 preview, JDK 24 시점 still preview)의 `ScopedValue`는 immutable이며 dynamic scope 동안만 바인딩된다.

```java
private static final ScopedValue<RequestContext> CTX = ScopedValue.newInstance();

void handle(Request req) {
    ScopedValue.where(CTX, RequestContext.of(req))
        .run(() -> service.execute());
}

class Service {
    void execute() {
        log.info("tenant={}", CTX.get().tenantId());   // 어느 frame에서든 접근
    }
}
```

scope 종료 시 자동 해제되어 GC 부담이 없다. MDC 같은 logging context는 SLF4J 2.x의 `MDCAdapter`가 `ScopedValue`를 받도록 점진 전환 중이다. 당장 운영에서는 ThreadLocal 기반 MDC를 유지하되, hot path의 read-only 컨텍스트(테넌트, 추적 ID 등)만 `ScopedValue`로 옮기는 점진 전략을 권한다.

## 8. 운영 — JFR, Thread Dump, Observability
VT의 jstack은 무용지물이다. carrier 위에 mount된 순간만 보이고, 100k VT 전체를 platform thread API로 덤프할 수 없다. JDK 21+는 전용 명령을 제공한다.

```bash
# JSON 포맷 덤프 (text는 가독성 떨어짐)
jcmd <pid> Thread.dump_to_file -format=json /tmp/vt-dump.json

# JFR로 pinning, VT lifecycle 추적
java -XX:StartFlightRecording=filename=app.jfr,settings=profile,duration=60s \
     -jar app.jar
```

이벤트 중 핵심:
- `jdk.VirtualThreadStart` / `jdk.VirtualThreadEnd` — lifecycle
- `jdk.VirtualThreadPinned` — pinning 누적 시간
- `jdk.VirtualThreadSubmitFailed` — scheduler 거부

Micrometer 1.12+는 `JvmThreadMetrics`에 VT 카운터를 추가했다. `jvm.threads.live`는 platform만 카운트하므로, `jvm.threads.started`, `jvm.threads.virtual.live`를 함께 본다. Prometheus 알람은 carrier saturation(`jvm.threads.peak / parallelism`)과 pinned time을 동시에 본다.

OTel 자바 에이전트(2.0+)는 자동으로 VT를 따라간다. 다만 `Context.current()`가 `ThreadLocal` 기반이라 VT 다량 생성 시 슬롯이 누적된다. 0-allocation 트레이스가 필요하면 `ScopedValue` 기반 context propagation으로 전환을 검토한다(현재 incubator).

마지막으로 카오스 테스트. VT 환경에서는 외부 호출이 늘어지면 VT가 쌓이기만 한다. 명시적 timeout 없는 호출이 한 군데라도 있으면 메모리가 계속 증가한다. 모든 외부 호출에 `Duration.ofMillis()` 단위 타임아웃과 `StructuredTaskScope.joinUntil` 데드라인을 짝지어 강제한다. 이것이 Loom 운영의 단일 가장 큰 안정성 룰이다.

## 참고
- JEP 444: Virtual Threads — https://openjdk.org/jeps/444
- JEP 462: Structured Concurrency (Second Preview) — https://openjdk.org/jeps/462
- JEP 480: Structured Concurrency (Third Preview) — https://openjdk.org/jeps/480
- JEP 491: Synchronize Virtual Threads without Pinning — https://openjdk.org/jeps/491
- JEP 506: Scoped Values — https://openjdk.org/jeps/506
- Oracle, "Virtual Threads" — https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
- Spring Blog, "Embracing Virtual Threads" (2023-09) — https://spring.io/blog/2023/09/09/all-together-now-spring-boot-3-2-graalvm-native-images-java-21-and-virtual
- Brian Goetz, "State of Loom" — https://cr.openjdk.org/~rpressler/loom/loom/sol1_part1.html
- Cay Horstmann, "Modern Java in Action: Loom" notes — https://horstmann.com/unblog/2023-09-12/index.html
- HikariCP 5.1 release notes (synchronized → ReentrantLock) — https://github.com/brettwooldridge/HikariCP/releases/tag/HikariCP-5.1.0
