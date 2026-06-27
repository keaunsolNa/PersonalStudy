Notion 원본: https://app.notion.com/p/38c5a06fd6d3814c8c56f41b85842f68

# Spring WebFlux Reactor 백프레셔와 Scheduler 및 Context 전파

> 2026-06-27 신규 주제 · 확장 대상: Spring(WebFlux), Reactor

## 학습 목표

- Reactive Streams의 request(n) 기반 백프레셔가 생산자 속도를 제어하는 원리를 설명한다.
- onBackpressureBuffer/Drop/Latest 전략의 메모리·유실 트레이드오프를 구분한다.
- publishOn과 subscribeOn의 스레드 경계와 Scheduler 종류를 선택한다.
- Reactor Context와 MDC를 전파한다.

## 1. 백프레셔 — pull 기반 수요 신호

Reactive Streams는 소비자가 `Subscription.request(n)`으로 처리 가능한 만큼만 요청하는 pull-push 혼합이다. 빠른 생산자가 느린 소비자의 큐를 채워 OOM을 일으키는 사고를 구조적으로 막는다. 연산자 체인의 기본 prefetch는 256이다.

## 2. 백프레셔 전략

| 전략 | 동작 | 메모리 | 유실 |
| --- | --- | --- | --- |
| Buffer | 큐잉 후 정책 | 큼 | 초과분 |
| Drop | 미수요 시 폐기 | 작음 | 많을 수 있음 |
| Latest | 최신 1개 보존 | 최소 | 중간값 |
| Error | 즉시 실패 | 최소 | — |

## 3. publishOn vs subscribeOn

`subscribeOn`은 구독 시점(소스 방출 시작)의 실행 컨텍스트를 지정하며 체인 전체에 영향한다. `publishOn`은 이후 다운스트림 스레드를 바꾸며 여러 번 두면 그 지점마다 경계가 생긴다. 이벤트 루프(nio) 스레드에서 블로킹은 금지이고 `Schedulers.boundedElastic()`로 격리한다.

## 4. ThreadLocal의 붕괴와 Reactor Context

```java
Mono<String> handler = Mono.deferContextual(ctx -> Mono.just("trace=" + ctx.get("traceId")))
  .contextWrite(Context.of("traceId", "abc-123"));
```

Context는 구독 신호와 함께 다운스트림에서 업스트림으로 흐른다. 값을 읽는 연산자보다 더 아래에 `contextWrite`를 둔다.

## 5. MDC 로깅 전파

```java
Hooks.enableAutomaticContextPropagation();
chain.filter(exchange).contextWrite(ctx -> ctx.put("traceId", traceId));
```

## 6. 운영 체크리스트

WebFlux 이점은 체인 전체가 논블로킹일 때만 성립한다. 외부 호출에 timeout과 retryWhen(지수 백오프+지터)을 건다.

```java
webClient.get().uri("/external").retrieve().bodyToMono(Dto.class)
  .timeout(Duration.ofSeconds(2))
  .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).jitter(0.5))
  .onErrorResume(ex -> Mono.just(Dto.fallback()));
```

## 7. flatMap 동시성 제어와 순서 보장

`flatMap`은 기본 동시성(256)만큼 동시 구독하고 순서를 보장하지 않는다. 동시성 제한+순서가 필요하면 `flatMapSequential`을 쓴다.

```java
Flux.fromIterable(ids).flatMap(id -> callExternal(id), 8).subscribe();
Flux.fromIterable(ids).flatMapSequential(id -> callExternal(id), 8).subscribe();
```

## 8. 핫·콜드 시퀀스와 멀티캐스트

콜드 시퀀스는 구독할 때마다 처음부터 재실행된다. `cache()`는 결과를 캐싱해 재생하고 `share()`는 진행 중 발행을 공유한다.

```java
Mono<Config> config = loadConfig().cache(Duration.ofMinutes(5));
```

## 9. 에러 처리 연산자

각 내부 스트림에 `onErrorResume`을 두면 한 건 실패가 전체를 죽이지 않는다.

```java
Flux.fromIterable(ids)
  .flatMap(id -> callExternal(id).onErrorResume(ex -> Mono.just(Result.failed(id, ex))), 8)
  .collectList().subscribe(this::report);
```

## 참고

- Project Reactor Reference — Backpressure, Schedulers, Context
- Reactive Streams Specification 1.0.4 — Subscription.request(n)
- Spring Framework Reference — Web on Reactive Stack(WebFlux)
- Micrometer Context Propagation, BlockHound
