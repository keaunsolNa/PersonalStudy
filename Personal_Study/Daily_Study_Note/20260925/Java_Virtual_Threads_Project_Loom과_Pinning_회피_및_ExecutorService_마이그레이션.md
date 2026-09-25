Notion 원본: https://app.notion.com/p/3e65a06fd6d38139b1d0ee95fd761db1

# Java Virtual Threads(Project Loom)와 Pinning 회피 및 ExecutorService 마이그레이션

> 2026-09-25 신규 주제 · 확장 대상: JAVA

## 학습 목표

- Virtual Thread의 Carrier Thread 스케줄링 모델과 Platform Thread의 구조적 차이를 구분한다
- synchronized 블록과 native frame에서 발생하는 Pinning 조건을 식별하고 JFR로 탐지한다
- 기존 `ExecutorService` 기반 코드를 `newVirtualThreadPerTaskExecutor`로 전환할 때의 스레드 풀 사이징 관행을 재검토한다
- HikariCP 등 커넥션 풀과 Virtual Thread를 함께 쓸 때 생기는 병목을 실측 기반으로 판단한다

## 1. Virtual Thread의 실행 모델

JDK 21에서 정식화된 Virtual Thread는 OS 스레드를 1:1로 점유하지 않고, 소수의 Platform Thread(Carrier Thread) 위에서 M:N으로 스케줄링된다. 이 스케줄러는 `ForkJoinPool`의 work-stealing 구현을 재사용하며, 기본적으로 `Runtime.getRuntime().availableProcessors()` 개수만큼의 Carrier Thread를 생성한다. Virtual Thread가 블로킹 I/O(예: `Socket.read`, `Files.readAllBytes`)를 만나면 JVM 내부적으로 해당 Virtual Thread를 Carrier Thread에서 **언마운트(unmount)**하고, Carrier Thread는 즉시 다른 Virtual Thread를 실행한다. I/O가 완료되면 유휴 Carrier Thread에 다시 **마운트(mount)**되어 실행을 재개한다.

이 모델의 핵심은 "블로킹 코드를 논블로킹처럼 실행 가능하게" 만든다는 점이다. 즉 `CompletableFuture` 체이닝이나 리액티브 스트림 없이도 동기 스타일 코드로 수만 개의 동시 요청을 처리할 수 있다.

```java
// 기존 Platform Thread 풀: 스레드 수만큼만 동시 처리
ExecutorService fixed = Executors.newFixedThreadPool(200);

// Virtual Thread: 요청마다 새 Virtual Thread, 블로킹 시 자동 언마운트
ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();

for (int i = 0; i < 100_000; i++) {
    virtual.submit(() -> {
        String result = callBlockingHttpApi(); // 블로킹 호출이지만 Carrier를 막지 않음
        process(result);
    });
}
```

Virtual Thread는 스택 크기가 초기 수백 바이트로 시작해 필요 시 힙에 동적으로 확장되는 연속(continuation) 구조를 사용하므로, Platform Thread(기본 1MB 스택)보다 생성 비용이 수백 배 저렴하다. OpenJDK 벤치마크 기준 Virtual Thread 100만 개 생성이 수 초 내에 끝나는 반면, 동일 수의 Platform Thread는 OutOfMemoryError로 이어지는 것이 일반적이다.

## 2. Pinning: Virtual Thread가 언마운트되지 못하는 조건

Virtual Thread의 장점은 "블로킹 시 Carrier를 놓아준다"는 전제에 의존한다. 그런데 특정 상황에서는 Virtual Thread가 Carrier Thread에 **고정(pinned)**되어 언마운트가 불가능해진다. 대표적인 두 가지 조건은 다음과 같다.

**(1) synchronized 블록/메서드 내부에서 블로킹 호출**: JDK 21/22까지는 `synchronized`로 보호된 임계 구역 안에서 블로킹 I/O를 수행하면 Virtual Thread가 Pinning된다. 이는 `synchronized`가 모니터를 OS 레벨 뮤텍스로 구현하기 때문에, 언마운트 시 모니터 소유권을 안전하게 이전할 방법이 없기 때문이다. (JDK 24부터는 `synchronized`의 Pinning 문제가 상당 부분 해소되었으나, 사내 운영 환경이 21 LTS인 경우 여전히 유효한 제약이다.)

```java
// Pinning 발생 패턴
private final Object lock = new Object();

void handle() {
    synchronized (lock) {
        String data = jdbcCall(); // 블로킹 JDBC 호출 -> Carrier Thread 고정
    }
}
```

**(2) native 프레임 실행 중**: JNI 호출 스택 안에서 블로킹이 발생해도 언마운트할 수 없다. 레거시 네이티브 라이브러리를 감싼 JDBC 드라이버나 압축 라이브러리가 여기 해당할 수 있다.

Pinning이 발생해도 프로그램이 죽지는 않는다. 다만 해당 Carrier Thread가 블로킹 기간 내내 점유되므로, Carrier Thread 풀(기본 CPU 코어 수)이 모두 Pinning된 Virtual Thread로 채워지면 나머지 Virtual Thread는 실행 기회를 얻지 못해 사실상 데드락처럼 보이는 처리량 급락이 발생한다.

**해결책**: `synchronized`를 `java.util.concurrent.locks.ReentrantLock`으로 교체한다. `ReentrantLock`은 AQS(AbstractQueuedSynchronizer) 기반이라 언마운트가 가능하다.

```java
private final ReentrantLock lock = new ReentrantLock();

void handle() {
    lock.lock();
    try {
        String data = jdbcCall(); // Pinning 없이 언마운트 가능
    } finally {
        lock.unlock();
    }
}
```

## 3. JFR을 통한 Pinning 탐지

Pinning은 코드 리뷰만으로 전수 발견하기 어렵다. JDK Flight Recorder(JFR)의 `jdk.VirtualThreadPinned` 이벤트를 활성화하면 실제 운영 트래픽에서 Pinning이 발생하는 지점을 스택 트레이스와 함께 확인할 수 있다.

```bash
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=filename=pinning.jfr,settings=profile \
     -Djdk.tracePinnedThreads=full \
     -jar app.jar
```

`-Djdk.tracePinnedThreads=full` 옵션을 켜면 Pinning이 100ms(기본 임계값) 이상 지속될 때마다 표준 출력에 스택 트레이스를 즉시 출력한다. `short`로 설정하면 한 줄 요약만 출력된다. 운영 환경에서는 출력 폭주를 피하기 위해 JFR 이벤트 스트리밍(`jdk.jfr.consumer.RecordingStream`)으로 `jdk.VirtualThreadPinned`만 선택적으로 구독하는 편이 안전하다.

```java
try (var rs = new RecordingStream()) {
    rs.enable("jdk.VirtualThreadPinned").withStackTrace();
    rs.onEvent("jdk.VirtualThreadPinned", event -> {
        log.warn("Pinning 감지: duration={}ms, stack={}",
                event.getDuration().toMillis(), event.getStackTrace());
    });
    rs.startAsync();
}
```

또 다른 실패 신호로 `jdk.VirtualThreadSubmitFailed` 이벤트가 있는데, 이는 Carrier Thread 풀이 고갈되어 새 Virtual Thread를 스케줄링하지 못할 때 발생한다. 이 이벤트가 잦다면 `jdk.virtualThreadScheduler.parallelism` 시스템 프로퍼티로 Carrier 수를 늘리는 임시 완화책을 검토하되, 근본 원인은 대개 Pinning 누적이다.

## 4. 기존 스레드 풀 코드의 마이그레이션 전략

가장 흔한 실수는 "Virtual Thread는 저렴하니 기존 `newFixedThreadPool(N)`을 `newVirtualThreadPerTaskExecutor()`로 그대로 치환하면 된다"고 단순화하는 것이다. 이는 두 가지 이유로 위험하다.

첫째, `newVirtualThreadPerTaskExecutor()`는 **동시성 제한이 없다**. Platform Thread 풀은 큐잉을 통해 다운스트림(DB, 외부 API)으로 나가는 동시 요청 수를 암묵적으로 제한했지만, Virtual Thread는 요청마다 생성되므로 다운스트림에 순간적으로 수만 건의 동시 호출이 몰릴 수 있다. DB 커넥션 풀이나 외부 API의 Rate Limit이 병목이 되어 오히려 전체 지연시간이 악화될 수 있다.

```java
// 위험: 다운스트림 보호 장치 없음
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

// 개선: Semaphore로 다운스트림 동시성 상한을 명시적으로 관리
private final Semaphore downstreamLimiter = new Semaphore(50);

void callDownstream() throws InterruptedException {
    downstreamLimiter.acquire();
    try {
        externalApiCall();
    } finally {
        downstreamLimiter.release();
    }
}
```

둘째, `ThreadLocal` 사용 패턴이 깨질 수 있다. Platform Thread 풀에서는 스레드 수가 적어 `ThreadLocal` 캐시(예: `SimpleDateFormat` 재사용)가 메모리 효율적이었지만, Virtual Thread는 요청마다 새로 생성되므로 `ThreadLocal`을 캐시 용도로 쓰면 매 요청마다 새 인스턴스가 만들어져 이점이 사라진다. JDK 21은 이런 상황을 위해 **Scoped Values**(`ScopedValue`, JEP 429/446)를 대안으로 제공한다. `ScopedValue`는 불변이며 구조적 동시성 범위 안에서만 유효해 Virtual Thread 대량 생성 환경에 더 적합하다.

```java
private static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

void handleRequest(String id) {
    ScopedValue.where(REQUEST_ID, id).run(() -> {
        process(); // process() 내부에서 REQUEST_ID.get() 호출 가능
    });
}
```

## 5. Structured Concurrency로 하위 작업 관리

JDK 21+ 프리뷰(JDK 25 기준 `StructuredTaskScope`로 정식화 진행 중)의 Structured Concurrency는 여러 Virtual Thread로 분기한 하위 작업을 하나의 스코프로 묶어, 스코프 종료 시 모든 하위 작업이 함께 취소되거나 완료되도록 보장한다. 이는 "한쪽 하위 작업이 실패했는데 다른 하위 작업은 좀비처럼 계속 도는" Fire-and-forget 패턴의 리소스 누수를 원천 차단한다.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> userTask = scope.fork(() -> userService.fetch(id));
    Subtask<List<Order>> orderTask = scope.fork(() -> orderService.fetchByUser(id));

    scope.join();           // 두 하위 작업이 끝날 때까지 대기
    scope.throwIfFailed();  // 하나라도 실패하면 예외 전파, 나머지는 자동 취소

    return new UserDetail(userTask.get(), orderTask.get());
}
```

기존 `CompletableFuture.allOf()` 조합 방식과 비교하면, 예외 발생 시 나머지 Future를 명시적으로 `cancel()`해야 했던 번거로움과 스택 트레이스 단절 문제가 Structured Concurrency에서는 스코프 단위로 해결된다.

## 6. HikariCP와 Virtual Thread의 상호작용

Virtual Thread 환경에서 가장 많이 오해하는 지점이 커넥션 풀 사이징이다. Platform Thread 시절의 경험칙(HikariCP 공식 권장: `connections = ((core_count * 2) + effective_spindle_count)`, 통상 10~20)은 "동시에 실행 가능한 스레드 수가 제한적"이라는 전제 위에 있었다. Virtual Thread는 수만 개가 동시에 존재할 수 있으므로, 풀 크기를 그대로 두면 대부분의 Virtual Thread가 커넥션 획득 대기(`connectionTimeout`)에서 블로킹된다.

이때 중요한 사실은, HikariCP의 커넥션 대기 큐잉 자체가 `synchronized` 기반이 아니라 `ConcurrentBag`(락-프리에 가까운 구조)을 사용하므로 Pinning을 유발하지 않는다는 점이다. 즉 대기가 발생해도 Virtual Thread는 정상적으로 언마운트된다. 따라서 실질적인 튜닝 포인트는 "DB가 실제로 감당 가능한 동시 커넥션 수"이지, Virtual Thread 수가 아니다. DB 서버의 `max_connections`(PostgreSQL/MySQL) 한도와 커넥션당 자원 소비를 기준으로 풀 크기를 여전히 보수적으로(수십 단위) 유지하고, 그 앞단에서 Semaphore나 Rate Limiter로 Virtual Thread의 동시 요청을 조절하는 2단 방어가 실무적으로 안전하다.

| 구성 요소 | Platform Thread 시절 | Virtual Thread 도입 후 |
|---|---|---|
| 스레드 풀 크기 | 요청 동시성의 상한 역할 | 상한 없음(요청당 1개) |
| DB 커넥션 풀 크기 | 스레드 풀 크기와 근사 | DB 용량 기준 그대로 유지 |
| 동시성 제어 지점 | 스레드 풀 큐 | 별도 Semaphore/RateLimiter 필요 |
| synchronized 블록 | 성능 영향 적음 | Pinning 위험, ReentrantLock 권장 |

## 7. 실측 기반 비교

간단한 벤치마크로 "블로킹 HTTP 호출을 흉내낸 100ms sleep 작업 10,000건"을 처리한 결과(4코어 환경 기준, 상대적 경향 파악용):

- `newFixedThreadPool(200)`: 10,000건 처리에 약 5초(동시성 200에 막혀 라운드 반복), 스레드 생성 비용은 무시할 수준이나 처리량이 풀 크기에 종속
- `newVirtualThreadPerTaskExecutor()`: 10,000건 거의 동시에 제출, 처리 시간이 이론상 100ms에 근접(약 0.3~0.5초, 스케줄링 오버헤드 포함), 단 다운스트림이 이를 감당할 때만 유효
- `newVirtualThreadPerTaskExecutor()` + Pinning 유발(synchronized 안에서 sleep): 처리 시간이 CPU 코어 수에 다시 종속되어 Platform Thread 풀과 유사하거나 더 나빠짐(컨텍스트 스위칭 오버헤드 추가)

이 결과는 "Virtual Thread가 항상 빠르다"가 아니라 "Pinning 없이, 그리고 다운스트림 동시성을 통제했을 때만 이점이 실현된다"는 점을 보여준다.

## 8. 실무 마이그레이션 체크리스트

Virtual Thread로 전환할 때는 다음 순서로 점검하는 것이 안전하다. 먼저 `-Djdk.tracePinnedThreads=full`을 스테이징 환경에서 활성화해 기존 코드베이스의 `synchronized` 사용 지점 중 실제로 블로킹 I/O와 결합된 곳을 전수 조사한다. 다음으로 서드파티 라이브러리(로깅 프레임워크의 내부 락, 커넥션 풀 구현) 중 `synchronized`를 사용하는 것이 있는지 확인한다 — 일부 구버전 JDBC 드라이버나 Log4j 2의 특정 Appender가 여기 해당할 수 있다. 그 다음 다운스트림(DB, 외부 API, 메시지 브로커) 각각에 대해 명시적 동시성 상한(Semaphore, Resilience4j Bulkhead 등)을 도입한다. 마지막으로 `ThreadLocal` 캐시 패턴을 `ScopedValue`나 요청 스코프 빈으로 재설계한다. 이 네 단계를 건너뛰고 Executor만 교체하면, 겉보기엔 동작하지만 부하 상황에서만 드러나는 Pinning 병목이나 다운스트림 과부하로 이어지기 쉽다.

## 참고

- JEP 444: Virtual Threads (JDK 21)
- JEP 453/462/480: Structured Concurrency (Preview 진행 이력)
- JEP 429/446: Scoped Values
- Oracle, "Virtual Threads" — Java Platform, Standard Edition Core Libraries Developer Guide
- HikariCP Wiki, "About Pool Sizing"
