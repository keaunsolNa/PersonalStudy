Notion 원본: https://www.notion.so/37e5a06fd6d381c093def076817498f1

# Spring WebFlux Reactor 백프레셔와 Context Propagation 및 Scheduler 내부

> 2026-06-13 신규 주제 · 확장 대상: Backend

## 학습 목표

- Reactive Streams의 request(n) 기반 백프레셔가 Publisher–Subscriber 사이에서 동작하는 흐름을 설명한다
- `onBackpressureBuffer`/`Drop`/`Latest` 전략을 상황별로 선택해 과부하를 제어한다
- Reactor `Context`와 `Scheduler`로 ThreadLocal 부재 문제와 블로킹 격리를 해결한다
- WebFlux 핸들러에서 블로킹 호출이 이벤트 루프를 굶주는 함정을 진단하고 회피한다

## 1. Reactive Streams와 백프레셔의 본질

WebFlux는 Reactive Streams 표준(Publisher, Subscriber, Subscription, Processor) 위에 Reactor(`Flux`/`Mono`)를 얹은 것이다. 백프레셔의 핵심은 데이터가 push로 무한정 밀려오는 것이 아니라, Subscriber가 `Subscription.request(n)`으로 "지금 n개까지 받을 수 있다"고 수요를 표명하고 Publisher는 그 한도 안에서만 emit한다는 점이다. 소비자가 느리면 request가 느려지고, 그만큼 생산도 느려진다 — 흐름 제어가 프로토콜에 내장된 셌이다.

```java
Flux.range(1, 100)
    .doOnRequest(n -> System.out.println("requested: " + n))
    .subscribe(new BaseSubscriber<Integer>() {
        @Override
        protected void hookOnSubscribe(Subscription s) {
            request(1); // 처음 1개만 요청
        }
        @Override
        protected void hookOnNext(Integer v) {
            System.out.println("got " + v);
            request(1); // 처리 후 1개씩 추가 요청 → 수요 기반 흐름
        }
    });
```

`subscribe()`만 호출하면 Reactor는 기본적으로 `Long.MAX_VALUE`를 request해 무제한 수요를 표명한다. 따라서 백프레셔가 의미를 가지려면 연산자(예: `limitRate`)나 커스텀 Subscriber로 수요를 제한해야 한다.

## 2. 백프레셔가 깨지는 지점 — 소스가 수요를 무시할 때

문제는 소스가 본질적으로 push형이고 수요를 모를 때다. 센서 이벤트, 메시지 브로커 콜백, `Flux.create`로 만든 핿 스트림 등은 소비자 수요와 무관하게 신호를 만든다. 이때 중간 버퍼가 무한히 커지거나 `MissingBackpressureException`이 발생한다. Reactor는 이를 제어할 `onBackpressureXxx` 연산자를 제공한다.

```java
Flux<Integer> source = Flux.create(sink -> {
    for (int i = 0; i < 1_000_000; i++) sink.next(i); // 수요 무시 push
    sink.complete();
}, FluxSink.OverflowStrategy.ERROR);
```

`Flux.create`의 `OverflowStrategy`로 1차 방어를 하거나, 다운스트림에서 `onBackpressureBuffer(size)` 등으로 명시적으로 처리한다.

## 3. 백프레셔 전략 선택

| 전략 | 동작 | 적합한 상황 |
|---|---|---|
| `onBackpressureBuffer(n)` | 초과분을 버퍼에 보관, 초과 시 에러/드롭 콜백 | 일시적 버스트, 유실 불가 데이터 |
| `onBackpressureDrop()` | 수요 없을 때 도착한 항목 폐기 | 최신성보다 부하 안정이 중요(메트릭) |
| `onBackpressureLatest()` | 가장 최근 1건만 유지 | UI 상태·시세처럼 최신값만 유의미 |

```java
hotStream
    .onBackpressureBuffer(1024,
        dropped -> log.warn("dropped: {}", dropped),
        BufferOverflowStrategy.DROP_OLDEST)
    .subscribe(this::handle);
```

선택 기준은 "데이터 유실 허용 여부"와 "메모리 한도"다. 유실이 절대 안 되면 buffer + 상류 흐름 제어를 병행하고, 유실이 허용되면 Drop/Latest로 메모리 폭증을 원천 차단한다. Buffer만 키우는 것은 OOM을 뒤로 미룰 뽐 근본 해결이 아니다.

## 4. Scheduler — 어디서 실행될 것인가

Reactor 연산은 기본적으로 구독한 스레드(WebFlux에서는 Netty 이벤트 루프)에서 실행된다. `publishOn`은 그 이후 연산의 실행 스레드를 바꾸고, `subscribeOn`은 구독 시점(소스 emit)의 스레드를 바꿜다.

```java
Mono.fromCallable(() -> blockingJdbcCall())   // 블로킹 작업
    .subscribeOn(Schedulers.boundedElastic())  // 별도 풀에서 실행
    .map(this::transform)                       // 여전히 boundedElastic
    .publishOn(Schedulers.parallel())           // 이후 연산은 parallel로 이동
    .subscribe();
```

주요 Scheduler: `parallel()`은 CPU 코어 수만큼의 고정 스레드로 논블로킹 연산용, `boundedElastic()`은 블로킹 I/O 격리용(상한 있는 가변 풀), `single()`은 단일 스레드, `immediate()`는 현재 스레드다. 블로킹 호출은 반드시 `boundedElastic`으로 격리해야 이벤트 루프를 보호한다.

## 5. 이벤트 루프를 굶기는 함정

WebFlux의 성능은 소수의 이벤트 루프 스레드(코어 수 × 2 정도)가 절대 블로킹되지 않는다는 전제에서 나온다. 핸들러 안에서 JDBC, `RestTemplate`, `Thread.sleep`, 동기 파일 I/O 같은 블로킹 호출을 직접 하면 그 스레드가 묶여 수천 개 요청 처리가 동시에 멈춘다. 증상은 "낮은 CPU 사용률인데 처리량이 바닥"이다.

```java
// 안티패턴: 이벤트 루프에서 블로킹
@GetMapping("/bad")
public Mono<String> bad() {
    String r = restTemplate.getForObject("...", String.class); // 블로킹!
    return Mono.just(r);
}

// 개선: WebClient(논블로킹) 또는 boundedElastic 격리
@GetMapping("/good")
public Mono<String> good() {
    return webClient.get().uri("...").retrieve().bodyToMono(String.class);
}
```

진단은 BlockHound를 테스트 환경에 붙여 이벤트 루프에서의 블로킹 호출을 런타임에 탐지하는 방식이 가장 확실하다. 불가피한 레거시 블로킹은 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`로 감싼다.

## 6. Reactor Context — ThreadLocal의 대체

리액티브 파이프라인은 연산마다 스레드가 바뀔 수 있어 `ThreadLocal`(MDC 로깅, 보안 컨텍스트, 트랜잭션)이 정상 동작하지 않는다. Reactor는 구독 시점에 아래에서 위로 전파되는 불변 `Context`를 제공한다.

```java
Mono.deferContextual(ctx ->
        Mono.just("user=" + ctx.get("userId")))
    .contextWrite(Context.of("userId", "alice"))
    .subscribe(System.out::println); // user=alice
```

Context는 다운스트림에서 `contextWrite`로 쓰고 업스트림에서 `deferContextual`/`transformDeferredContextual`로 읽는다(쓰기는 구독 방향 역순으로 전파됨에 유의). Spring Security의 `ReactiveSecurityContextHolder`도 내부적으로 Reactor Context를 쓴다. 로깅 MDC 연동은 `context-propagation` 라이브러리(Micrometer)로 ThreadLocal ↔ Context 자동 브리지을 설정하면, 스레드 전환 경계에서 MDC가 복원된다.

## 7. 성능·운영 trade-off

WebFlux는 적은 스레드로 높은 동시성(특히 I/O 바운드·많은 느린 커넥션)을 처리하는 데 강하다. 스레드당 메모리(~1MB 스택)와 컨텍스트 스위칭이 절약되어 C10K급 연결 유지에 유리하다. 반대 비용은 디버깅 난도다 — 스택 트레이스가 파편화되고, 블로킹 한 줄이 전체를 마비시키며, 학습 곡선이 가파르다. CPU 바운드 작업이나 단순 CRUD 트래픽에서는 MVC + (Java 21) Virtual Threads 조합이 동등한 동시성을 훨씬 단순한 명령형 코드로 달성할 수 있어, WebFlux의 이점이 줄어든다.

## 8. Hot vs Cold Publisher와 멀티캐스트

Reactor 시퀀스는 cold가 기본이다 — 구독할 때마다 데이터가 처음부터 새로 생성된다(HTTP 요청을 구독마다 다시 보냄). 반면 hot 시퀀스는 구독 여부와 무관하게 흐르며, 늦게 구독한 자는 그 이후 데이터만 본다.

```java
Flux<Long> cold = Flux.interval(Duration.ofMillis(100)); // 구독마다 0부터
Flux<Long> hot = cold.publish().refCount(1);             // 공유, 첫 구독부터 흐름

// share()는 refCount(1)의 축약 — 여러 구독자가 같은 스트림을 공유(멀티캐스트)
Flux<String> shared = expensiveCall().share();
```

`publish().refCount()`, `share()`, `cache()`로 cold를 hot/멀티캐스트로 전환한다. 외부 호출 결과를 여러 구독자가 공유해야 할 때 `cache()`로 결과를 메모이즈하면 중복 호출을 막는다. 다만 hot 전환 시 백프레셔 의미가 복잡해진다 — 느린 구독자 하나가 전체를 늦추거나, 반대로 데이터를 놓칠 수 있어 §3의 전략과 함께 설계해야 한다.

## 9. 에러 처리와 재시도

리액티브 파이프라인의 에러는 종료 신호다. `onErrorResume`(대체 시퀀스), `onErrorReturn`(기본값), `retryWhen`(조건부 재시도)으로 복구한다.

```java
webClient.get().uri("/api").retrieve().bodyToMono(String.class)
    .timeout(Duration.ofSeconds(2))
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200)) // 지수 백오프 3회
        .filter(ex -> ex instanceof TimeoutException))
    .onErrorResume(ex -> Mono.just("fallback"));         // 최종 실패 시 대체
```

`retryWhen(Retry.backoff(...))`는 지터를 포함한 지수 백오프를 제공해 thundering herd를 완화한다. 주의할 점은 재시도가 **업스트림 전체를 재구독**한다는 것이다 — 부수효과가 있는 연산(결제 등)을 재시도하면 멱등성이 없을 때 중복 실행되므로, 멱등 키나 보상 트랜잭션과 함께 써야 한다.

## 10. 도입 판단

전 구간(컨트롤러–서비스–DB 드라이버 R2DBC–외부 호출 WebClient)이 논블로킹으로 일관될 때 WebFlux의 가치가 극대화된다. 중간에 블로킹 JDBC가 한 곳이라도 있으면 그 지점이 병목이자 위험원이 되므로, 전체 스택의 논블로킹 가용성을 먼저 점검해야 한다. 스트리밍(SSE, 대용량 응답), 게이트웨이/프록시, 외부 API 팬아웃처럼 I/O 대기가 지배적인 워크로드가 1순위 후보다. 그 외 일반 업무 시스템이라면 Virtual Threads 기반 MVC가 운영·디버깅 비용 면에서 더 합리적인 선택일 수 있다.

## 참고

- Reactor Reference — Backpressure & Schedulers: https://projectreactor.io/docs/core/release/reference/
- Spring WebFlux Reference: https://docs.spring.io/spring-framework/reference/web/webflux.html
- Reactive Streams Specification: https://www.reactive-streams.org/
- Micrometer Context Propagation: https://docs.micrometer.io/context-propagation/reference/
