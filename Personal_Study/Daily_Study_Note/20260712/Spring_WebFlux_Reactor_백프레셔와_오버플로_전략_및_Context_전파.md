Notion 원본: https://www.notion.so/39b5a06fd6d381e084fbc42ef64074b7

# Spring WebFlux Reactor 백프레셔와 오버플로 전략 및 Context 전파

> 2026-07-12 신규 주제 · 확장 대상: Spring

## 학습 목표

- Reactive Streams 의 `request(n)` 기반 백프레셔가 Reactor 연산자 체인에서 실제로 어떻게 전파되는지 추적한다.
- `onBackpressureBuffer` / `Drop` / `Latest` / `Error` 전략의 큐 동작과 메모리 위험을 비교해 상황별로 선택한다.
- `publishOn` 과 `subscribeOn` 이 스레드 경계와 큐 크기(`prefetch`)에 미치는 영향을 구분한다.
- ThreadLocal 이 없는 리액티브 환경에서 `Context` / `ContextView` 로 인증·트레이스 정보를 전파한다.

## 1. Reactive Streams 백프레셔의 본질

블로킹 MVC 에서는 서블릿 스레드 하나가 요청을 끝까지 붙잡으므로 "느린 소비자" 문제가 커넥션 풀 고갈로만 나타난다. WebFlux 는 이벤트 루프(Netty) 위에서 소수의 스레드가 수천 개의 요청을 처리하므로, 빠른 생산자(예: DB 커서, Kafka 컨슈머)가 느린 소비자(예: 원격 API, 클라이언트 소켓)를 압도하면 중간 버퍼가 무한히 쌓여 OOM 이 난다. 백프레셔는 이 문제를 소비자가 생산자에게 "지금 n개만 보내라"고 요청하는 `Subscription.request(n)` 신호로 해결한다.

Reactive Streams 표준의 네 인터페이스는 `Publisher`, `Subscriber`, `Subscription`, `Processor` 다. 핵심은 데이터가 push 되기 전에 소비자가 먼저 demand 를 pull 한다는 점이다. 이 "pull 기반 push" 구조 덕분에 생산자는 소비자가 감당할 수 있는 만큼만 방출한다.

```java
Flux.range(1, 1_000_000)
    .doOnRequest(n -> log.info("upstream requested: {}", n))
    .subscribe(new BaseSubscriber<Integer>() {
        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            request(1); // 최초 1개만 요청
        }

        @Override
        protected void hookOnNext(Integer value) {
            process(value);
            request(1); // 처리 끝난 뒤 다음 1개 요청 — 명시적 백프레셔
        }
    });
```

위 코드에서 소비자는 한 건을 처리한 뒤에야 다음 건을 요청한다. 생산자 `range` 는 demand 를 초과해 방출하지 않으므로 버퍼가 쌓이지 않는다. 실무에서는 이렇게 수동으로 `request(1)` 하는 대신 연산자가 대신 demand 를 관리한다.

## 2. prefetch 와 연산자의 내부 큐

`flatMap`, `concatMap`, `publishOn` 같은 연산자는 내부적으로 상류에 `request(prefetch)` 를 걸어 두고, 큐가 75%(기본 `limitRate` 비율)까지 소진되면 다시 채운다. 기본 prefetch 는 `Queues.SMALL_BUFFER_SIZE`(기본 256, 시스템 프로퍼티 `reactor.bufferSize.small` 로 조정)이다.

```java
Flux.range(1, 10_000)
    .publishOn(Schedulers.parallel(), 32) // prefetch=32 로 내부 큐 상한 지정
    .map(this::heavyCompute)
    .subscribe();
```

prefetch 를 낮추면 메모리 사용은 줄지만 상류-하류 간 왕복 신호가 잦아져 처리량이 떨어진다. 높이면 처리량은 오르지만 큐가 커진다. 실측상 CPU 바운드 파이프라인은 prefetch 를 코어 수의 배수(예: 16~64)로, 네트워크 바운드는 더 크게(256) 두는 편이 무난하다. `limitRate(highTide, lowTide)` 로 재요청 임계치까지 세밀 제어할 수 있다.

| 연산자 | 기본 prefetch | 순서 보장 | 동시성 |
|---|---|---|---|
| flatMap | 256 | 없음(인터리빙) | concurrency 파라미터 |
| concatMap | 1(사실상 순차) | 보장 | 1 |
| flatMapSequential | 256 | 결과 순서 보장 | concurrency 파라미터 |

## 3. 백프레셔가 불가능한 소스 — 오버플로 전략

`request(n)` 를 존중하지 않는 소스가 있다. 마우스 이벤트, 센서 스트림, `Flux.interval`, 외부 push 기반 웹소켓 등은 소비자 demand 와 무관하게 데이터를 뿜는다. 이때는 `onBackpressure*` 연산자로 초과분을 어떻게 처리할지 정해야 한다.

```java
Flux<Tick> ticks = sensorStream(); // demand 무시하고 방출하는 hot source

ticks.onBackpressureBuffer(
        1024,                                   // 큐 상한
        dropped -> log.warn("dropped tick: {}", dropped), // 오버플로 시 콜백
        BufferOverflowStrategy.DROP_OLDEST)     // 가장 오래된 것부터 폐기
     .publishOn(Schedulers.boundedElastic())
     .subscribe(this::persist);
```

- `onBackpressureBuffer()`(무한): 가장 위험. 소비자가 느리면 큐가 무한 성장해 OOM. 상한 있는 오버로드만 쓰는 것이 안전하다.
- `onBackpressureDrop()`: 소비자가 준비 안 됐으면 새 데이터를 즉시 버린다. 실시간성이 중요하고 데이터 유실이 허용되는 텔레메트리에 적합.
- `onBackpressureLatest()`: 항상 "가장 최신" 한 건만 유지. 주가·환율 틱처럼 최신값만 의미 있을 때.
- `onBackpressureError()`: 오버플로 즉시 `IllegalStateException`(reactor.core.Exceptions.failWithOverflow) 로 스트림 종료. fail-fast 가 필요한 파이프라인.

선택 기준은 "유실을 허용하는가"와 "메모리 상한을 어디에 둘 것인가"다. 금융 주문처럼 유실 불가면 버퍼+상한+백프레셔 소스 자체를 재설계하고, 모니터링 지표처럼 유실 허용이면 Drop/Latest 가 낫다.

## 4. publishOn vs subscribeOn — 스레드 경계

두 연산자는 이름이 비슷하지만 완전히 다르다. `subscribeOn` 은 **구독(subscribe) 시점의 실행 컨텍스트**, 즉 소스가 방출을 시작하는 스레드를 바꾼다. 체인 어디에 두든 소스까지 거슬러 올라가 영향을 준다(단, 첫 번째 `subscribeOn` 만 유효). `publishOn` 은 **그 지점 이후 하류 연산자의 실행 스레드**를 바꾸며, 체인에 여러 번 두면 각 구간이 다른 스케줄러에서 돈다.

```java
Flux.fromIterable(loadFromDbBlocking())   // 블로킹 소스
    .subscribeOn(Schedulers.boundedElastic()) // 소스 방출을 elastic 스레드로
    .map(this::transform)                      // 여전히 elastic
    .publishOn(Schedulers.parallel())          // 여기서부터 parallel 스레드
    .map(this::cpuBoundWork)                    // parallel 에서 실행
    .subscribe();
```

블로킹 I/O(JDBC, 파일)는 반드시 `boundedElastic()` 에 격리해야 이벤트 루프 스레드가 막히지 않는다. Netty 의 이벤트 루프 스레드(`reactor-http-nio-*`)를 블로킹하면 그 스레드에 매핑된 수백 개 커넥션이 동시에 멈춘다. `BlockHound` 를 테스트에 붙이면 논블로킹 스레드에서의 블로킹 호출을 런타임에 잡아낼 수 있다.

## 5. 리액티브 Context 전파

MVC 는 `ThreadLocal`(SecurityContextHolder, MDC)로 요청 스코프 데이터를 나른다. 그러나 리액티브 체인은 연산자마다 스레드가 바뀔 수 있어 ThreadLocal 이 깨진다. Reactor 는 대신 구독 시점에 하류→상류로 전파되는 불변 `Context` 를 제공한다. 데이터는 소비자 쪽에서 심고, 연산자는 `ContextView` 로 읽는다.

```java
Mono<String> handler = Mono.deferContextual(ctx -> {
        String traceId = ctx.get("traceId");
        return callDownstream(traceId);
    });

handler
    .contextWrite(Context.of("traceId", UUID.randomUUID().toString()))
    .subscribe();
```

전파 방향이 하류→상류라는 점이 헷갈리기 쉽다. `contextWrite` 는 자기보다 **위(상류)** 에 있는 연산자들이 보는 Context 를 채운다. 따라서 Context 를 심는 `contextWrite` 는 체인의 아래쪽(구독 근처)에 두고, 읽는 `deferContextual` 은 위쪽에 둔다.

Spring Security 리액티브(`ReactiveSecurityContextHolder.getContext()`)와 Micrometer 트레이싱이 이 메커니즘 위에 구축돼 있다. Reactor 3.5+ 의 `Micrometer.contextCapture()` 와 `ContextRegistry` 를 쓰면 ThreadLocal 기반 라이브러리(MDC 로깅 등)와 리액티브 Context 를 자동 브리징할 수 있다.

```java
// ThreadLocal(MDC) <-> Reactor Context 자동 브리징
Hooks.enableAutomaticContextPropagation(); // Reactor 3.5+
```

## 6. WebClient 에서의 백프레셔와 타임아웃

`WebClient` 는 응답 바디를 `Flux<DataBuffer>` 로 스트리밍하며 다운스트림 demand 를 존중한다. 대용량 응답을 처리할 때 `bodyToFlux` 로 스트리밍하면 전체를 메모리에 올리지 않는다. 반드시 타임아웃과 재시도 백프레셔를 함께 건다.

```java
webClient.get()
    .uri("/large-stream")
    .retrieve()
    .bodyToFlux(Item.class)
    .timeout(Duration.ofSeconds(5))
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
                    .filter(this::isTransient))
    .onBackpressureBuffer(512)
    .subscribe(this::handle);
```

`retryWhen(Retry.backoff)` 는 지수 백오프+지터로 재시도해 thundering herd 를 완화한다. `filter` 로 5xx·타임아웃 등 일시적 오류만 재시도하고 4xx 는 즉시 실패시키는 것이 정석이다.

## 7. Sinks 와 hot/cold 스트림

Reactor 3.4+ 의 `Sinks` API 는 명령형 코드에서 리액티브 스트림으로 데이터를 밀어 넣는 표준 창구다. 옛 `Processor` 를 대체하며, 멀티스레드 방출 시 발생하던 사양 위반을 방어한다. 서버-센트 이벤트(SSE) 브로드캐스트나 이벤트 버스 구현에 자주 쓴다.

```java
Sinks.Many<Notification> sink =
        Sinks.many().multicast().onBackpressureBuffer(256);

Flux<Notification> stream = sink.asFlux(); // 구독자에게 노출

// 명령형 코드에서 방출 (반환값으로 실패를 반드시 처리)
Sinks.EmitResult result = sink.tryEmitNext(notification);
if (result.isFailure()) {
    log.warn("emit failed: {}", result); // 백프레셔·구독자 없음 등
}
```

`multicast()` 는 여러 구독자가 공유하는 hot 스트림을, `unicast()` 는 단일 구독자를, `replay()` 는 늦게 구독한 소비자에게 과거 이벤트를 재생한다. hot 스트림은 구독 여부와 무관하게 흐르므로 구독 전 방출된 데이터는 놓칠 수 있다. 반면 `Flux.range` 같은 cold 스트림은 구독할 때마다 처음부터 다시 실행된다. 이 구분을 모르면 "왜 어떤 구독자는 데이터를 못 받는가"라는 버그를 만든다.

`tryEmitNext` 의 반환 `EmitResult` 를 무시하는 것이 흔한 실수다. 백프레셔로 버퍼가 찼거나 종료된 sink 에 방출하면 조용히 실패하므로, 실패를 로깅·재시도·드롭 중 무엇으로 처리할지 명시해야 한다.

## 8. 흔한 함정과 실측 감각

가장 흔한 사고는 리액티브 체인 안에서 무심코 블로킹 호출(JPA repository, `RestTemplate`, `Thread.sleep`)을 하는 것이다. 이벤트 루프가 막히면 처리량이 순간적으로 0 에 수렴한다. 두 번째는 `onBackpressureBuffer()` 를 상한 없이 써서 트래픽 급증 시 힙이 터지는 경우다. 세 번째는 `subscribeOn` 을 여러 번 걸고 왜 안 먹히는지 의아해하는 경우인데, 소스에 가장 가까운 첫 번째만 유효하다.

성능 감각 차원에서, 동일 하드웨어에서 순수 논블로킹 프록시 시나리오는 WebFlux 가 MVC 대비 커넥션당 메모리를 크게 줄이고 높은 동시성에서 꼬리 지연(tail latency)이 안정적이다. 반면 파이프라인 중간에 블로킹이 하나라도 섞이면 MVC 보다 나빠질 수 있으므로 "완전 논블로킹"이 전제될 때만 이득이다.

## 참고

- Reactor 3 Reference Guide — Backpressure and Handling Overflow (projectreactor.io)
- Reactive Streams Specification 1.0.4 (reactive-streams.org)
- Spring Framework Reference — Web on Reactive Stack
- Micrometer Context Propagation 문서
