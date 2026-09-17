Notion 원본: https://www.notion.so/3de5a06fd6d381859da7c01ce5c82fd8

# Java 가상 스레드 핀닝 진단과 구조적 동시성 및 스케줄러 내부

> 2026-09-17 신규 주제 · 확장 대상: JAVA 문법·동시성 기초 → Loom 런타임 내부

## 학습 목표

- 가상 스레드의 마운트·언마운트 경로와 캐리어 스레드 고갈이 생기는 조건을 구분한다
- 핀닝(pinning)을 JFR·시스템 속성으로 재현하고 원인 코드를 지목한다
- `StructuredTaskScope` 의 취소 전파 규칙을 이해하고 기존 `ExecutorService` 패턴과 대비한다
- 커넥션 풀·세마포어 크기를 가상 스레드 환경 기준으로 다시 산정한다

## 1. 마운트와 언마운트가 실제로 하는 일

가상 스레드는 JVM 힙에 `Continuation` 객체로 존재한다. 실행할 때만 플랫폼 스레드(캐리어)에 올라타고, 블로킹이 발생하면 스택을 힙으로 복사한 뒤 캐리어를 놓아준다. 이 "스택 복사"가 핵심 비용이자 핵심 제약이다.

`Thread.startVirtualThread(...)` 로 만든 스레드는 기본적으로 `ForkJoinPool` 기반 스케줄러에 제출된다. 이 풀의 병렬도는 기본값이 `Runtime.getRuntime().availableProcessors()` 이고 `jdk.virtualThreadScheduler.parallelism` 으로 조정한다. 중요한 것은 이 풀이 **FIFO 모드**로 동작한다는 점이다. 일반 `ForkJoinPool` 의 LIFO work-stealing 은 분할정복 작업에 유리하지만, 서로 독립적인 요청 처리에는 먼저 들어온 작업을 먼저 끝내는 편이 지연 분포가 낫다.

언마운트가 가능한 지점은 JDK 내부가 `Continuation.yield()` 를 호출하도록 고쳐놓은 곳뿐이다. 구체적으로는 `java.util.concurrent.locks.LockSupport.park()`, NIO 소켓 read/write, `BlockingQueue` 대기, `Thread.sleep` 등이다. 반대로 언마운트가 **불가능한** 지점이 핀닝이다.

```java
// 캐리어를 붙잡는 코드 — 언마운트 불가
synchronized (lock) {
    jdbcTemplate.queryForObject(sql, Long.class);   // 소켓 대기 중에도 캐리어 점유
}

// 언마운트 가능한 코드
lock.lock();                                        // ReentrantLock
try {
    jdbcTemplate.queryForObject(sql, Long.class);
} finally {
    lock.unlock();
}
```

JDK 21~23 에서는 `synchronized` 블록 안의 블로킹이 곧바로 핀닝이었다. JDK 24 에 들어간 JEP 491 이 모니터 진입 자체를 가상 스레드 친화적으로 바꿔 이 문제 대부분을 없앤지만, **네이티브 프레임이 스택에 있는 경우**(JNI 호출 안에서의 블로킹, 클래스 초기화자 실행 중 블로킹)는 여전히 핀닝이다. 운영 JDK 버전에 따라 진단 결과 해석이 달라지므로 먼저 `java -version` 을 확인하고 시작해야 한다.

## 2. 핀닝을 재현하고 증거를 남기는 방법

핀닝은 "느려졌다"는 증상으로만 보면 커넥션 풀 고갈과 구분되지 않는다. 다음 세 가지 증거를 순서대로 모은다.

**1) JFR 이벤트.** `jdk.VirtualThreadPinned` 이벤트가 표준이다. 기본 임계값은 20ms 이고 설정으로 낮출 수 있다.

```bash
java -XX:StartFlightRecording=duration=60s,filename=pin.jfr,settings=profile \
     -jar app.jar

jfr summary pin.jfr | grep -i pinned
jfr print --events jdk.VirtualThreadPinned pin.jfr | head -60
```

출력의 스택 트레이스 최상단이 원인 지점이다. `jdk.VirtualThreadSubmitFailed` 가 함께 뜨면 스케줄러 큐 제출 실패까지 간 상태로, 이미 심각하다.

**2) 시스템 속성 트레이스.** JDK 21~23 에서는 `-Djdk.tracePinnedThreads=full` 이 표준 출력으로 스택을 찍어준다. 이 플래그는 JDK 24 에서 제거됐으므로 신규 환경에서는 JFR 을 써야 한다.

**3) 캐리어 스레드 수 관측.** 핀닝이 실제 피해를 주는지는 캐리어가 모자라야 확인된다. 병렬도를 1 로 낮춰 실험하면 핀닝이 있는 코드는 즉시 직렬화되어 드러난다.

```bash
java -Djdk.virtualThreadScheduler.parallelism=1 \
     -Djdk.virtualThreadScheduler.maxPoolSize=1 -jar app.jar
```

이 상태에서 동시 요청 100개를 넣었을 때 처리량이 1/100 로 떨어지면 핀닝이 있다는 뜻이고, 그대로 유지되면 언마운트가 정상 동작하는 것이다. 운영에 적용할 설정이 아니라 진단용 실험이다.

| 증상 | 핀닝 | 커넥션 풀 고갈 | 스케줄러 병렬도 부족 |
|---|---|---|---|
| JFR `VirtualThreadPinned` | 발생 | 없음 | 없음 |
| HikariCP `pending` 지표 | 낮음 | 높음 | 낮음 |
| 캐리어 스레드 CPU | 낮음(대기) | 낮음 | 높음(포화) |
| 병렬도 1 실험 | 극단적 악화 | 변화 미미 | 변화 미미 |
| 해결 방향 | 락 교체·JDK 상향 | 풀 크기·쿼리 튜닝 | parallelism 상향 |

## 3. 캐리어 고갈과 처리량 계산

가상 스레드를 도입하면서 "스레드가 공짜니 제한을 없애자"고 판단하면 병목이 아래로 밀려 내려갈 뿐이다. 실제로 제한되는 자원은 세 층이다.

- 캐리어 스레드 수 = CPU 코어 수 (CPU 바운드 구간의 상한)
- DB 커넥션 수 = HikariCP `maximumPoolSize`
- 하류 서비스가 받아줄 수 있는 동시 요청 수

8코어 장비에서 요청당 CPU 2ms, DB 대기 30ms 인 API 를 생각하자. 플랫폼 스레드 200개 모델에서는 200/0.032 ≈ 6,250 rps 가 이론 상한이지만 스레드 스택 200 × 1MB 메모리와 컨텍스트 스위칭 비용이 붙는다. 가상 스레드로 바꾸면 스레드 수 제약은 사라지지만 CPU 상한이 8/0.002 = 4,000 rps 로 남고, 커넥션 풀이 20개라면 20/0.030 ≈ 666 rps 가 진짜 상한이다. 즉 이 시스템에서 가상 스레드 전환의 이득은 처리량이 아니라 **메모리와 꼬리 지연**이다.

이 계산이 주는 실무 결론은 명확하다. 가상 스레드 전환 시 커넥션 풀을 그대로 두면 대기가 풀 획득 지점으로 몰린다. HikariCP 는 커넥션을 기다리는 동안 `LockSupport.park` 를 쓰므로 언마운트는 되지만, `connectionTimeout` 초과 예외가 대량 발생한다. 풀 크기를 늘릴지, 세마포어로 유입을 앞단에서 제한할지 정해야 한다.

```java
// 유입 제한을 앞단에 두는 편이 장애 반경이 작다
private final Semaphore downstreamLimit = new Semaphore(50);

public Report fetchReport(long id) throws InterruptedException {
    downstreamLimit.acquire();   // 가상 스레드 친화적 — park 기반
    try {
        return downstreamClient.get(id);
    } finally {
        downstreamLimit.release();
    }
}
```

`Semaphore` 는 AQS 기반이라 `LockSupport.park` 로 내려가고 언마운트가 정상 동작한다. 반면 `synchronized` + `wait()` 조합으로 직접 구현한 제한기는 JDK 버전에 따라 핀닝을 만든다.

## 4. ThreadLocal 과 ScopedValue

가상 스레드는 수백만 개가 만들어질 수 있으므로 `ThreadLocal` 의 비용 구조가 달라진다. 스레드당 `ThreadLocalMap` 이 붙는데, 스레드가 100만 개면 맵도 100만 개다. MDC 처럼 요청 컨텍스트를 `ThreadLocal` 에 담는 관행이 그대로면 힙이 빠르게 찬다.

`ScopedValue`(JDK 21 프리뷰 → 25 정식)는 불변 바인딩을 호출 스택 범위로 한정해 이 문제를 푼다.

```java
private static final ScopedValue<RequestContext> CTX = ScopedValue.newInstance();

void handle(HttpExchange exchange) {
    RequestContext ctx = RequestContext.from(exchange);
    ScopedValue.where(CTX, ctx).run(() -> {
        service.process();       // 하위 호출 어디서나 CTX.get() 가능
    });
    // 블록을 벗어나면 바인딩은 자동 소멸 — remove() 누락으로 인한 누수가 없다
}
```

핵심 차이는 세 가지다. 값이 불변이라 방어적 복사가 필요 없고, 바인딩 수명이 스택 범위로 고정돼 `remove()` 호출 누락 사고가 원천 차단되며, 자식 스레드 상속이 `StructuredTaskScope` 와 결합될 때만 일어나 의도치 않은 전파가 없다.

## 5. 구조적 동시성 — 취소가 새지 않는 구조

`ExecutorService` 로 병렬 호출을 짤 때 가장 흔한 버그는 하나가 실패해도 나머지가 계속 도는 것이다. `invokeAll` 은 모든 작업이 끝날 때까지 기다리고, 수동 `Future.cancel` 은 예외 경로에서 빠뜨리기 쉽다.

`StructuredTaskScope`(JDK 25 에서 정식화, API 가 프리뷰 기간 동안 여러 번 바뀌었으므로 버전 확인 필수)는 스코프 종료 시 미완 작업을 반드시 취소한다.

```java
Order loadOrder(long orderId) throws Exception {
    try (var scope = StructuredTaskScope.open(
             StructuredTaskScope.Joiner.<Object>allSuccessfulOrThrow())) {

        var user    = scope.fork(() -> userClient.find(orderId));
        var payment = scope.fork(() -> paymentClient.find(orderId));
        var items   = scope.fork(() -> itemClient.findAll(orderId));

        scope.join();                       // 하나라도 실패하면 나머지 자동 취소
        return new Order(user.get(), payment.get(), items.get());
    }
}
```

`try-with-resources` 블록을 벗어나는 모든 경로 — 정상 반환, 예외, 인터럽트 — 에서 `close()` 가 미완 자식을 인터럽트하고 회수한다. 이 규칙 덕분에 "요청이 취소됐는데 하류 호출은 계속 돌아가는" 유령 작업이 사라진다.

정책은 `Joiner` 로 갈아 끼운다. `allSuccessfulOrThrow` 는 전부 성공해야 하는 팬아웃, `anySuccessfulResultOrThrow` 는 여러 후보 중 먼저 성공한 하나만 쓰는 헤지 요청에 맞는다. 타임아웃은 `scope.join()` 대신 `Joiner` 설정이나 외부 `Timeout` 조합으로 건다.

다만 스코프는 **동일 스레드에서 fork 하고 join 해야 한다**. 스코프 객체를 필드에 저장해 다른 스레드가 fork 하면 `WrongThreadException` 이 난다. 이 제약이 곧 "동시성 구조가 코드 블록 구조와 일치한다"는 보장의 근거다.

## 6. 스케줄러 튜닝이 필요한 경우와 아닌 경우

`jdk.virtualThreadScheduler.parallelism` 을 코어 수보다 크게 잡는 것이 도움이 되는 경우는 사실상 핀닝이 남아 있을 때뿐이다. 핀닝된 캐리어가 늘어날 때 여유분으로 버티려는 임시방편이다. 근본 해결은 핀닝 제거다.

`maxPoolSize`(기본 256)는 핀닝된 캐리어가 늘어날 때 스케줄러가 임시로 만들 수 있는 캐리어의 상한이다. 이 값에 도달하면 새 가상 스레드가 실행 기회를 얻지 못하고 사실상 교착한다. JFR 에서 `VirtualThreadPinned` 가 다수인데 `maxPoolSize` 근처까지 캐리어가 늘었다면 교착 직전 신호다.

`jdk.virtualThreadScheduler.maxPoolSize` 를 무작정 올리면 플랫폼 스레드가 그만큼 생겨 가상 스레드를 쓰는 의미가 사라진다. 순서는 항상 (1) 핀닝 제거, (2) 커넥션·세마포어 재산정, (3) 그래도 부족하면 병렬도 조정이다.

## 7. 마이그레이션 체크리스트

Spring Boot 는 `spring.threads.virtual.enabled=true` 한 줄로 톰캣 요청 처리와 `@Async`, `@Scheduled` 실행기를 가상 스레드로 바꾼다. 그러나 다음을 함께 점검하지 않으면 전환 후 오히려 나빠진다.

- **풀링 제거**: 가상 스레드는 풀링하지 않는다. `Executors.newVirtualThreadPerTaskExecutor()` 는 이름과 달리 풀이 아니라 작업마다 새 스레드를 만드는 팩토리다. 기존 `newFixedThreadPool` 을 그대로 두면 가상 스레드 이점이 없다.
- **라이브러리 감사**: 오래된 JDBC 드라이버, 일부 커넥터가 내부적으로 `synchronized` + 네이티브 호출을 쓴다. 드라이버 버전을 최신으로 올리는 것이 가장 효과가 크다.
- **ThreadLocal 캐시 제거**: `SimpleDateFormat` 을 `ThreadLocal` 로 캐싱하던 코드는 스레드 수가 폭증하면 캐시 적중이 사라져 오히려 손해다. `DateTimeFormatter` 같은 불변 객체로 교체한다.
- **스레드 이름 기반 로깅**: 가상 스레드는 기본 이름이 없다. 로그 패턴이 `%thread` 에 의존하면 식별 불가능한 로그가 쌓인다. MDC 나 `ScopedValue` 로 요청 ID 를 심는다.
- **모니터링 지표 교체**: 스레드 수·풀 사용률 대시보드는 의미를 잃는다. 대신 진행 중 요청 수, 커넥션 대기 시간, `VirtualThreadPinned` 카운트를 본다.

## 8. 성능이 개선되지 않는 경우의 판정

전환 후 처리량이 그대로라면 다음 순서로 원인을 좁힌다. CPU 사용률이 100% 근처면 애초에 CPU 바운드라 가상 스레드로 얻을 것이 없다 — 이 경우 플랫폼 스레드가 오히려 스택 복사 비용이 없어 유리하다. CPU 가 놀고 처리량이 낮으면 어딘가에서 직렬화되고 있다는 뜻이고, 핀닝·커넥션 풀·하류 rate limit 셋 중 하나다. JFR 의 `VirtualThreadPinned` 가 0 이고 커넥션 대기도 짧다면 하류가 상한이며, 이때는 아키텍처 문제라 스레드 모델로 풀 수 없다.

가상 스레드의 실질 이득은 대개 두 가지로 요약된다. 첫째, 수만 개의 동시 연결을 스레드당 1MB 스택 없이 유지할 수 있어 메모리가 크게 준다. 둘째, 큐 대기가 사라져 P99 지연의 분산이 줄어든다. 평균 처리량 개선을 기대하고 들어가면 대부분 실망한다.

## 참고

- JEP 444: Virtual Threads — https://openjdk.org/jeps/444
- JEP 491: Synchronize Virtual Threads without Pinning — https://openjdk.org/jeps/491
- JEP 505: Structured Concurrency (Fifth Preview) — https://openjdk.org/jeps/505
- JEP 506: Scoped Values — https://openjdk.org/jeps/506
- Oracle, "Virtual Threads" — https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html
- JDK Mission Control 사용자 가이드 — https://docs.oracle.com/en/java/java-components/jdk-mission-control/
