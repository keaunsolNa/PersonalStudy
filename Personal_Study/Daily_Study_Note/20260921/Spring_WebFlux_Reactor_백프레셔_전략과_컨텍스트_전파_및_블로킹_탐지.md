Notion 원본: https://www.notion.so/3e25a06fd6d3811d9283fcc82fb36e19

# Spring WebFlux Reactor 백프레셔 전략과 컨텍스트 전파 및 블로킹 탐지

> 2026-09-21 신규 주제 · 확장 대상: Spring, JAVA

## 학습 목표

- Reactive Streams 의 request(n) 협상이 실제로 어디서 끊기는지 연산자 단위로 추적한다
- onBackpressureBuffer/Drop/Latest 를 처리량·지연·손실 허용치 기준으로 배치한다
- Reactor Context 와 ThreadLocal 을 `ContextPropagation` 으로 연결해 MDC·보안 컨텍스트를 유지한다
- BlockHound 와 스케줄러 메트릭으로 이벤트 루프 블로킹을 탐지하고 격리한다

## 1. 백프레셔는 옵션이 아니라 프로토콜이다

Reactive Streams 의 핵심은 `Subscription.request(n)` 이다. 소비자가 "n 개 받을 준비가 됐다"고 선언하고, 생산자는 그 수만큼만 `onNext` 를 보낸다. 이 협상이 유지되는 한 큐는 무한히 자라지 않는다.

문제는 **실제 파이프라인 대부분에서 이 협상이 중간에 끊긴다**는 점이다. 끊기는 지점은 세 가지다.

첫째, **비동기 경계 연산자**. `publishOn`, `subscribeOn`, `flatMap`, `buffer` 등은 내부 큐를 두고 상류에 `request(prefetch)` 를 미리 보낸다. 기본 prefetch 는 `Queues.SMALL_BUFFER_SIZE`(기본 256)다. 하류가 1개만 요청해도 상류는 이미 256 개를 만들고 있다.

둘째, **Cold 가 아닌 소스**. `Flux.create(sink, OverflowStrategy)`, Kafka 컨슈머, 웹소켓 수신처럼 외부가 밀어 넣는(push) 소스는 request 를 존중할 수 없다. 여기서는 `OverflowStrategy` 가 실질적 정책이 된다.

셋째, **`onBackpressureXxx` 연산자 자체**. 이 연산자들은 하류 요청과 무관하게 상류에 `request(unbounded)` 를 보낸다. 즉 "백프레셔를 처리하는" 연산자가 아니라 **"백프레셔를 포기하고 다른 전략으로 대체하는"** 연산자다. 이름이 오해를 부른다.

```java
Flux.range(1, 1_000_000)
    .onBackpressureBuffer(1000)   // 상류에 request(Long.MAX_VALUE) 발행
    .publishOn(Schedulers.parallel(), 16)
    .subscribe(this::handle);
```

이 파이프라인에서 `range` 는 전속력으로 생성하고, 버퍼 1000 을 넘으면 `onErrorDropped` 가 아니라 `OverflowException` 으로 스트림이 종료된다.

## 2. 네 가지 오버플로 전략의 선택 기준

| 전략 | 동작 | 손실 | 메모리 | 적합 |
| --- | --- | --- | --- | --- |
| `BUFFER`(무제한) | 계속 쌓음 | 없음 | 위험 | 유한·짧은 버스트 |
| `BUFFER(n, consumer)` | n 초과 시 에러 + 콜백 | 에러로 종료 | 상한 있음 | 백프레셔 위반을 버그로 취급 |
| `DROP` | 새 값 버림 | 새 값 | 상한 | 센서·텔레메트리 |
| `LATEST` | 오래된 값 버림 | 오래된 값 | 1 | 시세·현재 상태 |
| `ERROR` | 즉시 실패 | 전체 | 없음 | fail-fast 계약 |

판단 기준은 "**어느 데이터가 더 가치 있는가**" 하나다. 주가 스트림은 최신값만 의미 있으므로 `LATEST`, 감사 로그는 하나도 버릴 수 없으므로 `BUFFER` + 영속 큐, 메트릭 샘플은 `DROP` 이 자연스럽다.

```java
Flux<Quote> quotes = Flux.create(sink -> {
    feed.onQuote(sink::next);
    feed.onClose(sink::complete);
}, FluxSink.OverflowStrategy.LATEST);
```

버퍼 상한을 두면서 손실도 피하고 싶다면 **상류 속도 자체를 줄여야 한다**. `limitRate(n)` 가 그 도구다. 하류 요청을 n 단위로 쪼개 상류에 전달하므로, prefetch 폭주를 억제한다.

```java
source.limitRate(64, 16)   // highTide=64, lowTide=16 → 48개 소비 후 재요청
      .flatMap(this::callDownstream, 8)   // 동시성 8로 제한
      .subscribe();
```

`flatMap` 의 두 번째 인자(concurrency)는 실무에서 가장 중요한 튜닝 손잡이다. 기본값이 256 이라 외부 API 를 호출하는 파이프라인이 순간적으로 256 개 커넥션을 요구해 커넥션 풀을 고갈시키는 사고가 흔하다. **외부 호출이 있는 flatMap 은 항상 concurrency 를 명시한다.**

## 3. 순서 보존과 동시성의 교환

`flatMap` 은 완료 순서대로 방출하므로 순서가 뒤섞인다. 순서가 필요하면 `concatMap`(동시성 1) 또는 `flatMapSequential`(동시 실행 + 순서 재정렬)을 쓴다.

| 연산자 | 동시성 | 순서 | 내부 버퍼 |
| --- | --- | --- | --- |
| `concatMap` | 1 | 보존 | 최소 |
| `flatMap(f, n)` | n | 비보존 | n개 내부 큐 |
| `flatMapSequential(f, n)` | n | 보존 | n + 재정렬 큐 |
| `switchMap` | 1(취소) | 최신만 | 최소 |

`flatMapSequential` 은 먼저 끝난 결과를 순서가 올 때까지 들고 있어야 하므로, 느린 원소 하나가 뒤의 n-1 개 결과를 메모리에 붙잡아 둔다. 응답 크기가 큰 호출에서는 이 지연 비용이 `concatMap` 의 직렬화 비용보다 클 수 있다.

`switchMap` 은 새 값이 오면 진행 중이던 내부 스트림을 **취소**한다. 검색어 자동완성처럼 "마지막 요청만 유효" 한 경우에 정확히 맞지만, 취소가 곳 HTTP 커넥션 중단이므로 서버 측에 부분 처리 흔적이 남을 수 있다. 멱등하지 않은 작업에는 쓰지 않는다.

## 4. 스케줄러 배치: 어디서 무엇을 실행할 것인가

WebFlux 의 실행 모델은 **소수의 이벤트 루프 스레드**(Netty, 기본 CPU 코어 수)가 모든 요청을 처리한다. 이 스레드가 막히면 해당 루프에 바인딩된 모든 커넥션이 동시에 멈춘다. 전통적인 스레드 풀 모델에서는 한 요청이 느려도 다른 요청이 영향받지 않지만, 여기서는 전파된다.

```java
// subscribeOn: 구독 시점(소스 실행)의 스레드 결정 — 파이프라인 위치와 무관, 최초 1회
// publishOn: 그 아래 연산자들의 실행 스레드 변경 — 여러 번 가능
Mono.fromCallable(() -> jdbcTemplate.queryForObject(sql, Long.class))
    .subscribeOn(Schedulers.boundedElastic())   // 블로킹 호출 격리
    .publishOn(Schedulers.parallel())           // 이후 CPU 작업
    .map(this::transform);
```

| 스케줄러 | 스레드 | 용도 | 주의 |
| --- | --- | --- | --- |
| `parallel()` | CPU 코어 수 고정 | CPU 바운드 | 블로킹 금지 |
| `boundedElastic()` | 상한 10×코어, 유휴 60s 회수 | 블로킹 I/O 격리 | 큐 대기 발생 |
| `single()` | 1 | 순차 보장 작업 | 전역 병목 |
| `immediate()` | 호출 스레드 | 테스트 | — |
| `fromExecutor()` | 사용자 정의 | 전용 격리 | 메트릭 직접 노출 필요 |

Java 21 이후 `Schedulers.boundedElastic()` 대신 가상 스레드 기반 실행기를 쓸 수 있다. `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` 로 전환하면 블로킹 호출당 플랫폼 스레드를 점유하지 않는다. 다만 `synchronized` 블록 안의 블로킹은 여전히 캐리어 스레드를 핀(pin)하므로, 격리 효과가 기대만큼 나오지 않을 수 있다. 전환 전에 `-Djdk.tracePinnedThreads=full` 로 핀닝 지점을 먼저 확인한다.

## 5. 블로킹 탐지 — BlockHound

이벤트 루프 블로킹은 부하가 걸리기 전까지 증상이 없다가, 임계점에서 지연이 급격히 무너진다. 테스트 단계에서 잡아야 한다.

```java
// build.gradle: testImplementation("io.projectreactor.tools:blockhound-junit-platform:1.0.x")

static {
    BlockHound.install(builder -> builder
        // 의도적으로 허용하는 지점만 화이트리스트
        .allowBlockingCallsInside("java.util.UUID", "randomUUID")
        .allowBlockingCallsInside("org.springframework.boot.SpringApplication", "run")
        .blockingMethodCallback(m -> {
            log.error("Blocking call detected: {}", m);
            throw new BlockingOperationError(m);
        })
    );
}
```

BlockHound 는 JVM 인스트루멘테이션으로 `Thread.sleep`, `Socket.read`, `File.read` 등 네이티브 블로킹 지점에 훅을 걸고, 호출 스레드가 논블로킹으로 표시된 스레드(Netty 이벤트 루프, `parallel()` 스케줄러)면 에러를 던진다.

운영에서는 BlockHound 대신 **스케줄러·이벤트 루프 메트릭**을 본다.

```java
@Bean
MeterBinder reactorMetrics() {
    return registry -> {
        Schedulers.enableMetrics();   // reactor.scheduler.* 게이지 노출
        Metrics.globalRegistry.add(registry);
    };
}
```

관찰할 지표는 세 가지다. `reactor.netty.eventloop.pending.tasks` 가 지속적으로 0 이 아니면 루프가 밀리고 있다는 뜻이다. `executor.queued` (boundedElastic) 가 증가하면 블로킹 격리 풀이 포화된 것이다. 그리고 `reactor.netty.connection.provider.pending.connections` 는 커넥션 풀 고갈의 직접 신호다.

## 6. 컨텍스트 전파 — ThreadLocal 이 없는 세계

WebFlux 에서는 한 요청이 여러 스레드를 오가므로 `ThreadLocal` 기반 MDC·`SecurityContextHolder` 가 그대로는 동작하지 않는다. Reactor 는 대신 **구독 시점에 아래에서 위로 전파되는 불변 맵**인 `Context` 를 제공한다.

```java
Mono<String> handler(ServerRequest req) {
    return service.process(req)
        .contextWrite(ctx -> ctx.put("traceId", req.headers().firstHeader("X-Trace-Id")));
}

// 하류에서 읽기
Mono<String> process(Input in) {
    return Mono.deferContextual(ctx ->
        Mono.just(transform(in, ctx.getOrDefault("traceId", "none"))));
}
```

방향이 중요하다. `contextWrite` 는 **그 위치보다 상류(업스트림)에만** 보인다. 파이프라인 끝에 붙이면 전체에 적용되고, 중간에 붙이면 그 아래 연산자는 못 본다. 직관과 반대이므로 실수가 잦다.

로깅 MDC 처럼 기존 ThreadLocal API 를 유지해야 하는 경우, `micrometer-context-propagation` 이 다리를 놓는다.

```java
// 1) ThreadLocal 접근자 등록
ContextRegistry.getInstance().registerThreadLocalAccessor(
    "traceId", () -> MDC.get("traceId"), v -> MDC.put("traceId", v), () -> MDC.remove("traceId"));

// 2) Reactor 자동 전파 활성화
Hooks.enableAutomaticContextPropagation();
```

활성화하면 Reactor 가 연산자 경계마다 Context 값을 ThreadLocal 로 복원하고 실행 후 정리한다. 비용은 연산자 호출당 맵 조회 + ThreadLocal 설정 두 번이므로, 초고처리량 경로에서는 측정 후 결정한다. 실측 기준으로 단순 `map` 체인에서 20~30% 오버헤드가 관측되는 사례가 있어, 전역 활성화보다 `.tap()` 으로 필요한 구간만 감싸는 선택지도 있다.

Spring Security 는 별도로 `ReactiveSecurityContextHolder.getContext()` 를 제공하며, 내부적으로 같은 Reactor Context 를 쓴다. `@PreAuthorize` 는 WebFlux 에서도 동작하지만 반환 타입이 `Mono`/`Flux` 여야 한다.

## 7. 에러 처리와 취소 시맨틱

리액티브 파이프라인에서 에러는 **종료 신호**다. `onError` 가 흐르면 스트림이 끝나고, 이후 원소는 나오지 않는다. 일부 원소의 실패를 견려야 하면 실패를 값의 일부로 바꿔야 한다.

```java
flux.flatMap(item -> process(item)
        .map(Result::ok)
        .onErrorResume(e -> Mono.just(Result.fail(item, e))),  // 내부에서 흡수
     8)
    .subscribe(this::record);
```

`onErrorContinue` 라는 연산자도 있지만 권장하지 않는다. 상류 연산자의 협조가 필요한 특수 신호라 `flatMap` 내부 등 일부 지점에서만 동작하고, 어느 연산자가 지원하는지 시그니처로 드러나지 않는다. 디버깅 난이도가 높아 대부분의 팀이 금지 목록에 올려 둔다.

취소(cancel)는 클라이언트 연결 종료, `timeout`, `take(n)`, `switchMap` 에서 발생한다. 취소는 `onError`/`onComplete` 와 다른 경로이므로 `doFinally` 로 통합해서 정리한다.

```java
resource.acquire()
    .flatMap(this::use)
    .doFinally(sig -> {           // ON_COMPLETE, ON_ERROR, CANCEL 모두 포착
        if (sig == SignalType.CANCEL) meter.increment("cancelled");
        resource.release();
    });
```

트랜잭션 경계에서 취소는 특히 위험하다. R2DBC 트랜잭션 도중 취소되면 커밋도 롤백도 명시적으로 일어나지 않고 커넥션이 반환될 수 있다. `TransactionalOperator` 를 쓰고, 취소가 잦은 경로(`timeout` 이 걸린 경로)에서는 트랜잭션 범위를 최소화한다.

## 8. 디버깅과 운영 체크리스트

리액티브 스택 트레이스는 실행 스레드의 스택일 뿐이라 원인 지점을 가리키지 못한다. 두 가지 보완책이 있다.

`Hooks.onOperatorDebug()` 는 모든 연산자 조립 지점의 스택을 캐처한다. 정보는 완전하지만 조립마다 스택 캐처가 일어나 **운영에서는 쓸 수 없다**. 대안이 `reactor-tools` 의 `ReactorDebugAgent` 로, 바이트코드 조작으로 같은 정보를 훨씬 낮은 비용에 얻는다.

```java
public static void main(String[] args) {
    ReactorDebugAgent.init();        // 애플리케이션 시작 최상단
    SpringApplication.run(App.class, args);
}
```

운영 투입 전 점검 항목을 정리하면 다음과 같다.

| 항목 | 확인 방법 | 기준 |
| --- | --- | --- |
| flatMap concurrency 명시 | 코드 검색 `flatMap(` | 외부 호출은 전부 명시 |
| 커넥션 풀 크기 vs 동시성 | WebClient 설정 | 풀 ≥ 총 concurrency |
| 블로킹 호출 격리 | BlockHound 테스트 | 위반 0 |
| 이벤트 루프 대기 큐 | `eventloop.pending.tasks` | 정상 시 0 근접 |
| boundedElastic 큐 | `executor.queued` | 증가 추세 없음 |
| 컨텍스트 전파 | 로그 traceId 연속성 | 요청 전 구간 유지 |
| 취소 시 자원 해제 | `doFinally` 존재 | 자원 획득 경로 전부 |

마지막으로 기억할 것은 **WebFlux 가 처리량을 자동으로 올려 주지 않는다**는 점이다. 같은 하드웨어에서 블로킹 스택 대비 이득이 나는 구간은 "I/O 대기가 길고 동시 연결 수가 많은" 경우로 한정된다. CPU 바운드 작업이나 동시성이 낮은 내부 API 에서는 MVC + 가상 스레드 조합이 코드 복잡도 대비 더 나은 선택인 경우가 많다. 아키텍처 선택은 측정 후에 한다.

## 참고

- Reactive Streams Specification 1.0.4
- Project Reactor Reference Guide — Backpressure, Schedulers, Context, Debugging
- Spring Framework Documentation — Web on Reactive Stack
- reactor/BlockHound — Custom integrations & allowlist
- Micrometer Context Propagation Documentation
- JEP 444: Virtual Threads (핀닝 관련 절)
