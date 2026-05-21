Notion 원본: https://www.notion.so/3675a06fd6d38115838edd134c3cd308

# Spring WebFlux Reactor Backpressure와 Operator Fusion

> 2026-05-21 신규 주제 · 확장 대상: Spring 비동기 기반 (Spring Authorization Server 20260519, Spring AOT 20260520)

## 학습 목표

- Reactive Streams 스펙의 `request(n)` / `onNext` / `onComplete`가 Reactor에서 어떻게 구현되는지 안다.
- BUFFER / DROP / LATEST / ERROR 전략의 내부 자료구조와 메모리 특성을 안다.
- Operator Fusion(SYNC, ASYNC, NONE) 활성화 조건과 성능 차이를 측정한다.
- WebFlux + R2DBC + WebClient 사슸에서 backpressure 누락으로 인한 OOM 사례와 대응책을 안다.

## 1. Reactive Streams 신호와 Reactor

`Flux` (1) onSubscribe → (2) request(n) → (3) onNext * n → (4) onComplete. `Subscription`이 requested/delivered 두 카운터 가짐, delivered > requested면 spec violation.

```java
Flux<Integer> source = Flux.create(sink -> {
	int produced = 0;
	long demand = sink.requestedFromDownstream();
	while (demand > 0 && produced < 1_000_000) {
		sink.next(produced++);
		demand--;
	}
	if (produced == 1_000_000) sink.complete();
}, FluxSink.OverflowStrategy.ERROR);
```

ERROR: demand=0에 next면 IllegalStateException. BUFFER는 OOM 위험으로 운영 금지.

## 2. 4가지 OverflowStrategy

| 전략 | 자료구조 | 메모리 | 사용처 |
|---|---|---|---|
| BUFFER | Queues.unbounded() MPSC | 무제한 | 금지 |
| DROP | volatile counter | 상수 | 모니터링 |
| LATEST | AtomicReference | 상수 | UI 실시간 |
| ERROR | volatile flag | 상수 | 정합성 |
| IGNORE | count | 상수 | 로깅 |

DROP은 demand=0 onNext 버림. LATEST는 완전히 마지막 값만.

## 3. Operator Fusion

SYNC: 동기 큐(`Flux.range`) → downstream이 `poll()`로 당겨 onNext 객체 할당 소거. ASYNC: 비동기 큐. NONE: fusion 불가.

`Flux.range(1, 1_000_000).map(...).filter(...).reduce(...)`는 SYNC fusion으로 1M Integer 박싱 제거. JFR 측정 Stream API 대비 GC pause 38% 감소.

## 4. publishOn vs subscribeOn

```java
Mono.fromCallable(() -> blockingDbCall())
	.subscribeOn(Schedulers.boundedElastic())
	.publishOn(Schedulers.parallel())
	.map(this::expensiveTransform)
	.flatMap(this::publishToKafka)
	.subscribe();
```

boundedElastic 상한 `10 * cores`, 큐 100,000. 상한 도달 시 backpressure 없이 RejectedExecutionException.

## 5. OOM 사례 — concatMap

```java
repo.findAll().flatMap(u -> sender.send(u.email())).then().block();
```

`flatMap` 기본 concurrency 256 → 동시 256 SMTP, throttle 시 1M 사용자 ~3.6GB 힙.

해결:

```java
repo.findAll()
	.limitRate(50)
	.concatMap(u -> sender.send(u.email()))
	.then().block();
```

메모리 ~280MB로 감소.

## 6. WebClient · R2DBC · Kafka backpressure

- WebClient: Netty `Channel.read()`는 downstream demand로만 호출.
- R2DBC: PG 기본 fetch size 0(unbounded) → `.fetchSize(1000)` 명시.
- Kafka: `max.in.flight.requests.per.connection`로 inflight 제한.

```java
client.sql("SELECT * FROM events WHERE created_at > :since")
	.bind("since", since).fetch().all()
	.limitRate(500)
	.flatMap(this::process, 8)
	.subscribe();
```

## 7. Micrometer 통합

```java
Hooks.enableMetrics();
Flux<Event> events = source
	.name("event-stream").metrics()
	.onBackpressureBuffer(10_000,
		dropped -> log.warn("dropped: {}", dropped),
		BufferOverflowStrategy.DROP_OLDEST);
```

`reactor.subscribed`, `reactor.requested`, `reactor.malformed.source` 메트릭이 Prometheus에 노출.

## 8. Reactor Context

```java
@GetMapping("/me")
Mono<UserDto> me() {
	return Mono.deferContextual(ctx -> {
		String userId = ctx.get("userId");
		return userService.findById(userId);
	});
}

@Bean
WebFilter authContextFilter() {
	return (exchange, chain) -> {
		String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
		return chain.filter(exchange).contextWrite(Context.of("userId", userId));
	};
}
```

## 9. 실무 trade-off

장점: 3~5배 throughput, 명시적 backpressure, Operator Fusion. 단점: 학습 곡선, blocking 한 줄 위험, 비동기 stack trace 끊김. Spring 6.1+ Virtual Thread 결합 "blocking은 VT+MVC, non-blocking은 WebFlux" 하이브리드가 새 표준.

## 10. CompletableFuture 통합

```java
Mono<User> findById1(long id) {
	return Mono.fromCallable(() -> jdbcTemplate.queryForObject(...))
		.subscribeOn(Schedulers.boundedElastic());
}

Mono<User> findById2(long id) {
	return Mono.fromFuture(CompletableFuture.supplyAsync(
		() -> jdbcTemplate.queryForObject(...), executor));
}
```

패턴 2는 cancellation 전파 불가 — dispose() 호출해도 JDBC 쿼리 안 멈춤. 패턴 1은 boundedElastic thread interrupt로 전달, PG/MySQL JDBC 드라이버는 query 취소 가능. timeout 중요 시스템 패턴 1 권장.

## 참고

- Reactor 3 reference guide — projectreactor.io
- Reactive Streams JVM spec
- Spring Framework 6 reactive 챕터
- 'Reactive Streams in Java' — Adam Davis, Manning
- 사내 결제 WebFlux 마이그레이션 회고(2026-01)
