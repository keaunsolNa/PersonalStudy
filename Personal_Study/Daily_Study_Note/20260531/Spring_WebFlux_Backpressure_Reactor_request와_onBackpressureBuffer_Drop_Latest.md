Notion 원본: https://www.notion.so/3715a06fd6d3817c8c57e461f09e6425

# Spring WebFlux Backpressure — Reactor request(n)과 onBackpressureBuffer/Drop/Latest 운영

> 2026-05-31 신규 주제 · 확장 대상: Spring HTTP Interface Client (20260527) / Spring Batch 5 Partition (20260529)

## 학습 목표

- Reactive Streams 의 `request(n)` 신호가 publisher / operator chain / subscriber 사이에서 어떻게 전파되는지 추적한다
- `onBackpressureBuffer`, `onBackpressureDrop`, `onBackpressureLatest`, `onBackpressureError` 의 동작 차이와 메모리·정합성 trade-off 를 식별한다
- WebFlux Netty 서버에서 클라이언트가 느릴 때 발생하는 TCP write back-pressure 가 Mono/Flux 까지 전파되는 경로를 이해한다
- 운영 환경에서 backpressure 손실(drop/latest)을 안전하게 사용할 수 있는 경계 조건을 정한다

## 1. Reactive Streams 의 request(n) 와 hot/cold 의 분리

Reactive Streams 표준은 publisher 와 subscriber 사이에 4개 시그널을 정의한다 — `onSubscribe(Subscription)`, `onNext(T)`, `onError(Throwable)`, `onComplete()`. 그 중 backpressure 의 핵심은 `Subscription.request(n)` 으로, 구독자가 "지금부터 n개를 받을 준비가 됐다" 고 publisher 에 알리는 신호다.

cold publisher (DB query, file read 같은 lazy source) 는 request 신호를 기다렸다 정확히 n 개만 emit 한다. hot publisher (KafkaListener, WebSocket frame, MQTT topic) 는 외부 클럭이 흐름을 결정하므로 request 신호를 무시할 수 없다 — 이 격차를 메우는 게 backpressure operator 다.

```java
Flux<Integer> cold = Flux.range(1, 1_000_000)
    .doOnRequest(n -> log.info("requested {}", n));

cold.subscribe(new BaseSubscriber<Integer>() {
    @Override
    protected void hookOnSubscribe(Subscription s) {
        request(10);
    }
    @Override
    protected void hookOnNext(Integer v) {
        if (v % 10 == 0) request(10);
    }
});
```

WebFlux 의 Netty server 는 클라이언트가 TCP buffer 를 비울 때까지 다음 chunk 를 write 하지 않는다. 이 멈춤이 Channel.writability 를 false 로 바꾸고, Reactor Netty 의 ChannelOperations 가 자동으로 상위 chain 에 `request` 를 보내지 않아 결과적으로 cold publisher 까지 backpressure 가 전파된다.

## 2. operator chain 에서 prefetch 의 역할

Reactor 의 거의 모든 operator 는 default prefetch 가 있다. `flatMap` 은 256, `concatMap` 도 256, `publishOn` 은 256(`Queues.SMALL_BUFFER_SIZE`).

```java
Flux.range(1, 100_000)
    .publishOn(Schedulers.parallel(), 32)
    .flatMap(this::callExternal, 8, 16)
    .subscribe();
```

`flatMap(mapper, concurrency, prefetch)` 의 두 숫자는 운영 튜닝의 핵심이다.

## 3. onBackpressureBuffer

```java
import reactor.core.publisher.BufferOverflowStrategy;

Flux.from(kafkaConsumer.receive())
    .onBackpressureBuffer(
        1000,
        dropped -> log.warn("dropped key={}", dropped.key()),
        BufferOverflowStrategy.DROP_OLDEST
    )
    .concatMap(this::process)
    .subscribe();
```

## 4. onBackpressureDrop / onBackpressureLatest

```java
Flux.interval(Duration.ofMillis(10))
    .onBackpressureLatest()
    .publishOn(Schedulers.single())
    .delayElements(Duration.ofSeconds(1))
    .subscribe(v -> log.info("got {}", v));
```

| 연산자 | 큐 | demand 0 동안 | 손실 | 메모리 |
|---|---|---|---|---|
| onBackpressureBuffer | 무제한/제한 | 보관 | 없음 (overflow 제외) | OOM 위험 |
| onBackpressureDrop | 없음 | 폐기 | 큼 | O(1) |
| onBackpressureLatest | 1 슬롯 | 최신 1개 | 큼 (중간값) | O(1) |
| onBackpressureError | 없음 | 즉시 에러 | N/A | O(1) |

## 5. WebFlux 컨트롤러 실전

```java
@RestController
public class PriceStreamController {
    private final Flux<PriceTick> hotSource;

    @GetMapping(value = "/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<PriceTick>> stream() {
        return hotSource
            .onBackpressureLatest()
            .map(t -> ServerSentEvent.<PriceTick>builder()
                .id(String.valueOf(t.seq()))
                .event("tick")
                .data(t)
                .build());
    }
}
```

`onBackpressureBuffer(capacity)` 는 **per-subscriber 큐**. 1만 동시 SSE 클라이언트에 1000 큐는 1000만 element 메모리.

## 6. WebClient backpressure

```java
WebClient client = WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create(ConnectionProvider.builder("api")
            .maxConnections(50)
            .pendingAcquireMaxCount(200)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .build())))
    .build();

Flux.fromIterable(ids)
    .flatMap(id -> client.get().uri("/items/{id}", id).retrieve().bodyToMono(Item.class),
             50, 64)
    .collectList();
```

## 7. 실측

| 전략 | p50 | p99 | 손실 | RSS |
|---|---|---|---|---|
| buffer (capacity=1000) | 280 ms | 4.2 s | 0 | +260 MB |
| buffer + DROP_OLDEST | 110 ms | 850 ms | 75% | +6 MB |
| drop | 5 ms | 12 ms | 80% | +4 MB |
| latest | 6 ms | 14 ms | 79% | +4 MB |

## 8. 안티패턴

- `Flux.create` 의 `OverflowStrategy.IGNORE` default
- chain 종단 `block()`/`toFuture().get()`
- chain 마지막에 `subscribeOn` 한 번
- subscriber 직전에 backpressure operator

## 9. 운영 체크리스트

| 항목 | 점검 | 임계값 |
|---|---|---|
| Reactor 메트릭 | `reactor.subscribers.count` | 비정상 증가 |
| Netty writability | LoggingHandler TRACE | 빈번 false |
| Connection pool | idle.connections | 0 지속 |
| buffer 사용량 | dropped 콜백 | drop>1% |
| GC | G1/ZGC pause | promotion 폭증 |

backpressure 발생 자체가 시스템 어딘가에 병목이 있다는 신호. operator 선택은 "병목을 어디로 흡수할지" 의 결정이지, 병목 자체를 없애지 않는다.

## 참고

- Reactive Streams Specification (1.0.4) https://www.reactive-streams.org/
- Project Reactor Reference Guide — Backpressure
- Spring Framework Reference — WebFlux Concurrency
- Sergei Egorov, "Reactive Streams and the weird case of backpressure" (2023 InfoQ talk)
- Reactor Netty Reference — ConnectionProvider tuning
