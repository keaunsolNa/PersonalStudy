Notion 원본: https://www.notion.so/37e5a06fd6d381c093def076817498f1

# Spring WebFlux Reactor 백프레셔와 Context Propagation 및 Scheduler 내부

> 2026-06-13 신규 주제 · 확장 대상: Backend

## 학습 목표

- Reactive Streams의 request(n) 기반 백프레셔가 Publisher–Subscriber 사이에서 동작하는 흐름을 설명한다
- `onBackpressureBuffer`/`Drop`/`Latest` 전략을 상황별로 선택해 과부하를 제어한다
- Reactor `Context`와 `Scheduler`로 ThreadLocal 부재 문제와 블로킹 격리를 해결한다
- WebFlux 핸들러에서 블로킹 호출이 이벤트 루프를 굶기는 함정을 진단·회피한다

## 1. Reactive Streams와 백프레셔의 본질

WebFlux는 Reactive Streams 표준 위에 Reactor(Flux/Mono)를 얹은 것이다. 백프레셔의 핵심은 데이터가 push로 밀려오는 게 아니라 Subscriber가 `Subscription.request(n)`으로 수요를 표명하고 Publisher는 그 한도 안에서만 emit한다는 점이다. 소비자가 느릴수록 생산도 느려진다 — 흐름 제어가 프로토콜에 내장된 셌이다.

```java
Flux.range(1, 100)
    .doOnRequest(n -> System.out.println("requested: " + n))
    .subscribe(new BaseSubscriber<Integer>() {
        @Override protected void hookOnSubscribe(Subscription s) { request(1); }
        @Override protected void hookOnNext(Integer v) {
            System.out.println("got " + v);
            request(1); // 처리 후 1개씩 → 수요 기반 흐름
        }
    });
```

`subscribe()`만 호출하면 기본적으로 `Long.MAX_VALUE`를 request해 무제한 수요를 표명한다. 따라서 백프레셔가 의미를 가지려면 `limitRate` 같은 연산자나 커스텀 Subscriber로 수요를 제한해야 한다.

## 2. 백프레셔가 깨지는 지점

소스가 본질적으로 push형이고 수요를 모를 때(센서 이벤트, 브로커 콜백, `Flux.create` 핫 스트림) 중간 버퍼가 무한히 커지거나 `MissingBackpressureException`이 발생한다.

```java
Flux<Integer> source = Flux.create(sink -> {
    for (int i = 0; i < 1_000_000; i++) sink.next(i); // 수요 무시 push
    sink.complete();
}, FluxSink.OverflowStrategy.ERROR);
```

## 3. 백프레셔 전략 선택

| 전략 | 동작 | 적합한 상황 |
|---|---|---|
| `onBackpressureBuffer(n)` | 초과분을 버퍼에 보관, 초과 시 에러/드롭 콜백 | 일시적 버스트, 유실 불가 데이터 |
| `onBackpressureDrop()` | 수요 없을 때 도착한 항목 폐기 | 부하 안정이 중요(메트릭) |
| `onBackpressureLatest()` | 가장 최근 1건만 유지 | UI 상태·시세처럼 최신값만 유의미 |

선택 기준은 데이터 유실 허용 여부와 메모리 한도다. 유실이 절대 안 되면 buffer + 상류 흐름 제어를 병행하고, 허용되면 Drop/Latest로 메모리 폭증을 원차 차단한다. Buffer만 키우는 것은 OOM을 미루는 것일 뿐이다.

## 4. Scheduler — 어디서 실행될 것인가

Reactor 연산은 기본적으로 구독한 스레드(Netty 이벤트 루프)에서 실행된다. `publishOn`은 이후 연산의 스레드를, `subscribeOn`은 구독 시점의 스레드를 바꿄다.

```java
Mono.fromCallable(() -> blockingJdbcCall())
    .subscribeOn(Schedulers.boundedElastic())  // 블로킹 격리
    .map(this::transform)
    .publishOn(Schedulers.parallel())
    .subscribe();
```

`parallel()`은 CPU 논블로킹 연산, `boundedElastic()`은 블로킹 I/O 격리, `single()`은 단일 스레드, `immediate()`는 현재 스레드다. 블로킹 호출은 반드시 boundedElastic으로 격리한다.

## 5. 이벤트 루프를 굶기는 함정

WebFlux의 성능은 소수 이벤트 루프 스레드가 절대 블로킹되지 않는다는 전제에서 나온다. 핸들러에서 JDBC, RestTemplate, Thread.sleep 같은 블로킹 호출을 직접 하면 그 스레드가 묶여 수천 요청 처리가 멈춘다.

```java
@GetMapping("/bad")
public Mono<String> bad() {
    String r = restTemplate.getForObject("...", String.class); // 블로킹!
    return Mono.just(r);
}
@GetMapping("/good")
public Mono<String> good() {
    return webClient.get().uri("...").retrieve().bodyToMono(String.class);
}
```

진단은 BlockHound로 이벤트 루프의 블로킹 호출을 런타임 탐지하는 것이 가장 확실하다.

## 6. Reactor Context — ThreadLocal의 대체

리액티브 파이프라인은 연산마다 스레드가 바뀌 수 있어 ThreadLocal(MDC, 보안, 트랜잭션)이 정상 동작하지 않는다. Reactor는 구독 시점에 아래에서 위로 전파되는 불변 Context를 제공한다.

```java
Mono.deferContextual(ctx -> Mono.just("user=" + ctx.get("userId")))
    .contextWrite(Context.of("userId", "alice"))
    .subscribe(System.out::println); // user=alice
```

쓰기는 `contextWrite`, 읽기는 `deferContextual`로 하며 쓰기는 구독 방향 역순으로 전파된다. Micrometer context-propagation으로 ThreadLocal ↔ Context 브리징을 설정하면 스레드 전환 경계에서 MDC가 복원된다.

## 7. 성능·운영 trade-off

WebFlux는 적은 스레드로 높은 동시성(I/O 바운드·많은 느린 커넥션)을 처리하는 데 강하다. 스레드당 메모리와 컨텍스트 스위칭이 절약된다. 반대 비용은 디버깅 난도다 — 스택 트레이스 파편화, 블로킹 한 줄의 위험, 가파른 학습 곡선. CPU 바운드나 단순 CRUD는 MVC + Virtual Threads 조합이 동등 동시성을 더 단순한 코드로 달성한다.

## 8. Hot vs Cold Publisher와 멀티캐스트

```java
Flux<Long> cold = Flux.interval(Duration.ofMillis(100)); // 구독마다 0부터
Flux<Long> hot = cold.publish().refCount(1);            // 공유
Flux<String> shared = expensiveCall().share();          // 멀티캐스트
```

cold는 구독마다 처음부터 생성, hot은 공유된다. `share()`/`cache()`로 cold를 hot으로 전환하며, 외부 호출 결과를 여러 구독자가 공유할 때 cache로 중복을 막는다. 다만 hot 전환 시 백프레셔 의미가 복잡해져 §3 전략과 함께 설계해야 한다.

## 9. 에러 처리와 재시도

```java
webClient.get().uri("/api").retrieve().bodyToMono(String.class)
    .timeout(Duration.ofSeconds(2))
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
        .filter(ex -> ex instanceof TimeoutException))
    .onErrorResume(ex -> Mono.just("fallback"));
```

`retryWhen(Retry.backoff)`는 지터 포함 지수 백오프로 thundering herd를 완화한다. 주의할 점은 재시도가 업스트림 전체를 재구독한다는 것이다 — 부수효과가 있는 결제 등은 멱등 키·보상 트랜잭션과 함께 써야 한다.

## 10. 도입 판단

전 구간(컨트롤러–서비스–R2DBC–WebClient)이 논블로킹으로 일관될 때 WebFlux의 가치가 극대화된다. 중간에 블로킹 JDBC가 한 곳이라도 있으면 그 지점이 병목이자 위험원이 된다. 스트리밍(SSE), 게이트웨이/프록시, 외부 API 팬아웃이 1순위 후보다. 그 외 일반 업무 시스템은 Virtual Threads 기반 MVC가 운영·디버깅 비용 면에서 더 합리적일 수 있다.

## 참고

- Reactor Reference — Backpressure & Schedulers: https://projectreactor.io/docs/core/release/reference/
- Spring WebFlux Reference: https://docs.spring.io/spring-framework/reference/web/webflux.html
- Reactive Streams Specification: https://www.reactive-streams.org/
- Micrometer Context Propagation: https://docs.micrometer.io/context-propagation/reference/
