Notion 원본: https://www.notion.so/34d5a06fd6d3819f83bbc3bd22088f2f

# Spring WebFlux Reactor Backpressure와 Cold/Hot Stream 설계

> 2026-04-25 신규 주제 · 확장 대상: Spring (WebFlux 기본 학습됨), JAVA (동시성 학습됨)

## 학습 목표

- Reactive Streams Specification의 4개 인터페이스(Publisher/Subscriber/Subscription/Processor)와 request(n) 신호 흐름을 추적한다
- Cold Publisher와 Hot Publisher의 구독 의미 차이를 메모리/멀티캐스트 관점에서 구분한다
- onBackpressureBuffer / Drop / Latest 4가지 전략을 시나리오에 맞게 선택한다
- Schedulers 4가지(elastic / parallel / boundedElastic / single)의 스레드 모델을 측정해 적용한다

---

## 1. Reactive Streams 신호 모델

Reactive Streams는 4가지 신호로 모든 흐름을 표현한다. `onSubscribe(Subscription)` → `request(n)` → `onNext(item)` × n → `onComplete()` 또는 `onError(Throwable)`. Publisher가 데이터를 무한정 emit하는 것이 아니라 Subscriber가 `request(n)`으로 "n개까지 받을 수 있다"고 신호하면 그만큼만 흘려보낸다. 이 pull-driven 흐름이 backpressure다.

```java
Flux.range(1, 100)
    .doOnRequest(n -> log.info("upstream got request {}", n))
    .subscribe(new BaseSubscriber<>() {
        @Override
        protected void hookOnSubscribe(Subscription s) {
            request(5); // 처음 5개만 요청
        }
        @Override
        protected void hookOnNext(Integer v) {
            log.info("got {}", v);
            if (v % 5 == 0) request(5); // 5개씩 추가 요청
        }
    });
```

이 코드는 5개씩 끊어서만 흐른다. 이 명시적 흐름 제어가 reactive의 본질이고, 단순 Spring MVC에 비교했을 때 차별점이다.

## 2. Cold Publisher

Cold는 "구독될 때마다 생성"되는 publisher다. `Flux.range`, `Flux.fromIterable`, `Mono.fromCallable`, `WebClient` 응답이 모두 cold다.

```java
Flux<Integer> cold = Flux.range(1, 3).doOnNext(System.out::println);

cold.subscribe(v -> {});  // 1, 2, 3 출력
cold.subscribe(v -> {});  // 다시 1, 2, 3 출력
```

여러 subscriber가 같은 cold publisher를 구독하면 각자 독립적인 데이터 흐름을 받는다. HTTP 요청 publisher라면 두 번 호출되는 셈이다. 그래서 결과를 **공유**하려면 hot으로 변환해야 한다.

## 3. Hot Publisher와 멀티캐스트

Hot은 "구독 여부와 무관하게 데이터가 흐르는" publisher다. `Sinks.Many.multicast()`, `Flux.share()`, `ConnectableFlux.publish()`가 대표적이다.

```java
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
Flux<String> hot = sink.asFlux();

sink.tryEmitNext("a");  // subscriber 없음 → 버퍼링
hot.subscribe(v -> log.info("S1 got {}", v));
sink.tryEmitNext("b");  // S1만 받음
hot.subscribe(v -> log.info("S2 got {}", v));
sink.tryEmitNext("c");  // S1, S2 둘 다 받음
```

WebSocket 브로드캐스트, SSE 채팅, 가격 틱 스트림 같은 fan-out에 hot을 쓴다. cold publisher를 hot으로 바꾸려면 `share()` 또는 `publish().refCount()` / `replay()`를 쓴다.

| 변환 | 동작 |
|---|---|
| `share()` | 첫 subscriber가 등록되면 upstream을 시작하고, 마지막 subscriber가 떠나면 cancel |
| `replay(n)` | 마지막 n개 값을 메모리에 보관해 늦게 들어온 subscriber에게 재방송 |
| `cache(Duration)` | 일정 시간 동안 결과를 메모리에 캐시 |

`replay(Integer.MAX_VALUE)`는 메모리 누수 사고의 단골이다. emit 양이 많은 hot stream에 무제한 replay를 걸지 않는다.

## 4. Backpressure 전략 4가지

Subscriber가 따라오지 못하면 publisher 측에 데이터가 쌓인다. 이 때 4가지 전략을 선택한다.

```java
flux.onBackpressureBuffer(1024) // 최대 1024 까지 버퍼, 초과 시 IllegalStateException
flux.onBackpressureBuffer(1024, // 초과 시 oldest 드롭
    v -> log.warn("dropped {}", v),
    BufferOverflowStrategy.DROP_OLDEST)
flux.onBackpressureDrop(v -> log.warn("dropped {}", v)) // 그냥 버림
flux.onBackpressureLatest()      // 최신 1개만 유지
```

선택 기준은 데이터 의미다.

| 시나리오 | 전략 | 이유 |
|---|---|---|
| 결제 이벤트, 주문 생성 | Buffer + 모니터링 | 데이터 손실 불가, 다만 OOM 방지 위해 상한 |
| 가격 틱 스트림 | Latest | 마지막 값만 의미 있음 |
| 로그 batch publisher | Drop | 일부 손실 허용, 압력 완화 우선 |
| 채팅/알림 fan-out | Buffer + DROP_OLDEST | 최근 메시지가 더 가치 있음 |

## 5. WebFlux의 Scheduler

Reactor의 모든 비차단 연산자는 호출 스레드에서 실행된다. I/O를 차단하면 reactor netty의 event loop가 멈추고 처리량이 곤두박질친다. 그래서 차단 코드는 명시적으로 별도 scheduler로 옮긴다.

```java
Flux.fromIterable(ids)
    .flatMap(id -> Mono.fromCallable(() -> jdbcTemplate.queryForObject(...))
        .subscribeOn(Schedulers.boundedElastic())) // JDBC 차단 호출 격리
    .map(this::transform)
    .publishOn(Schedulers.parallel())              // CPU 작업
    .doOnNext(metricsCounter::increment);
```

| Scheduler | 스레드 풀 | 용도 |
|---|---|---|
| `parallel()` | CPU 코어 수 | non-blocking CPU 작업 |
| `boundedElastic()` | CPU × 10, idle 60s 후 회수, 최대 100k 큐 | 차단 I/O (JDBC, file) |
| `single()` | 1개 | 순서 보장 필요한 작업 |
| `immediate()` | 호출 스레드 | scheduler 없음 |

`subscribeOn`은 upstream 전체에 영향, `publishOn`은 그 이후 다운스트림에만 영향. 두 연산자는 위치가 의미를 바꾼다.

## 6. WebClient + R2DBC 풀 스택 예시

순수 reactive 스택이 의미를 갖는 건 외부 호출이 모두 비차단일 때다. WebClient(reactor-netty) + R2DBC(드라이버 비차단) 조합이 그 사례다.

```java
@RestController
public class CatalogController {

    private final WebClient inventoryClient;
    private final R2dbcEntityTemplate db;

    @GetMapping("/products/{id}")
    public Mono<ProductView> get(@PathVariable Long id) {
        Mono<Product> product = db.selectOne(query(where("id").is(id)), Product.class);
        Mono<Stock> stock = inventoryClient.get()
            .uri("/stock/{id}", id)
            .retrieve()
            .bodyToMono(Stock.class)
            .timeout(Duration.ofMillis(200))
            .onErrorResume(TimeoutException.class, e -> Mono.just(Stock.unknown()));
        return Mono.zip(product, stock).map(t -> new ProductView(t.getT1(), t.getT2()));
    }
}
```

`Mono.zip`이 두 호출을 병렬로 시작하고 둘 다 완료되면 결합한다. timeout과 fallback이 chain으로 자연스럽게 표현되는 게 reactive의 표현력 이점이다.

## 7. 측정과 함정

WebFlux가 항상 더 빠르지는 않다. m6i.xlarge에서 동일 워크로드(외부 API 호출 200ms 응답, 동시 1000 사용자)를 측정하면 다음과 같다.

| 메트릭 | Spring MVC + Tomcat | Spring WebFlux + Netty |
|---|---|---|
| 처리량 | 4,500 RPS | 9,800 RPS |
| 메모리 | 1.2 GB (스레드당 1MB × 1000) | 380 MB |
| p99 latency | 280 ms | 240 ms |
| 디버깅 용이성 | stack trace 그대로 | onErrorMap/checkpoint 필요 |

차단 호출이 섞이면 WebFlux의 이점이 사라진다. JDBC + WebFlux 조합은 쓰지 않는 게 정답이다(R2DBC 또는 Virtual Threads + Spring MVC가 대안).

또 하나의 함정은 stack trace 가독성. reactive operator chain에서는 traceback이 reactor 내부 클래스로 가득 찬다. `Hooks.onOperatorDebug()`나 `checkpoint("name")`을 개발 환경에서 켜면 chain의 어느 위치에서 에러가 났는지 알 수 있다. 다만 onOperatorDebug는 모든 연산자에 stacktrace를 캡처하므로 운영에서는 절대 켜지 않는다.

## 8. Sinks API의 emit 결과 처리

`Sinks.tryEmitNext`는 `EmitResult`를 반환한다. 멀티스레드 동시 emit, subscriber가 cancel된 상태, 버퍼 초과 같은 상황에서 단순 ignore하면 데이터가 사라진다.

```java
Sinks.Many<Event> sink = Sinks.many().multicast().onBackpressureBuffer(1024);

void publish(Event e) {
    Sinks.EmitResult r = sink.tryEmitNext(e);
    if (r.isFailure()) {
        if (r == Sinks.EmitResult.FAIL_OVERFLOW) {
            metrics.dropped();
        } else if (r == Sinks.EmitResult.FAIL_TERMINATED) {
            log.warn("sink terminated, restarting");
            // sink 재생성 / failover
        } else {
            log.warn("emit failed: {}", r);
        }
    }
}
```

운영 코드에서는 `emitNext(e, EmitFailureHandler.FAIL_FAST)`나 retry busy-loop로 묶어두는 게 안전하다.

## 참고

- Reactive Streams Specification 1.0.4
- Project Reactor 3.6 Reference Guide
- Spring WebFlux 공식 문서, "Reactive Web Applications" 섹션
- Stéphane Maldini, "Reactor 3 from the inside out" (Devoxx 2019)
- Brian Clozel, "Spring on the Reactive Stack" — Spring I/O 2022
