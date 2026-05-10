Notion 원본: https://www.notion.so/35c5a06fd6d381829dfeee88097fefcd

# Spring WebFlux Backpressure와 Sinks Many OverflowStrategy 비교

> 2026-05-10 신규 주제 · 확장 대상: Spring

## 학습 목표

- Reactive Streams 명세의 4개 시그널(onSubscribe, onNext, onError, onComplete) 위에서 backpressure 가 request(n) 으로 어떻게 흐르는지 호출 시퀀스를 따라간다
- Reactor 의 `Sinks.Many` 가 제공하는 multicast / unicast / replay 변형이 각각 어떤 use case 를 해결하는지 분류한다
- `Sinks.EmitFailureHandler` 가 lock-free 큐의 동시 emit 충돌을 어떻게 해석하는지, retry/serialize/fail 중 무엇을 골라야 하는지 결정한다
- onBackpressureBuffer/Drop/Latest 의 OverflowStrategy 가 프로듀서가 빠르고 컨슈머가 느릴 때 어떤 손실을 만드는지 실측 패턴으로 본다

## 1. backpressure 의 wire-level 모델

```java
public interface Subscriber<T> {
    void onSubscribe(Subscription s);
    void onNext(T t);
    void onError(Throwable t);
    void onComplete();
}
public interface Subscription {
    void request(long n);
    void cancel();
}
```

핵심: 데이터는 publisher 가 push 하는 것이 아니라, subscriber 가 pull demand 를 표현한다. `request(n)` 이 publisher 에게 "지금 n 개까지 받을 수 있다" 를 알린다.

| operator | inner concurrency | order 보존 | demand 흘려보내는 방식 |
| --- | --- | --- | --- |
| concatMap | 1 | yes | 한 inner 가 끝날 때마다 다음에 request |
| flatMap | 256 (default) | no | 모든 active inner 에 동시 demand |
| flatMapSequential | 256 | yes | 동시에 trigger, 결과는 buffered 후 in-order emit |

## 2. Sinks 도입 배경

Reactor 3.4 부터 FluxProcessor 류는 deprecate. Sinks 가 대체. emit 시 EmitResult 를 반환해 race condition 을 분명히 한다.

```java
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

Sinks.EmitResult result = sink.tryEmitNext("hello");
if (result.isFailure()) {
    // FAIL_NON_SERIALIZED, FAIL_OVERFLOW, FAIL_TERMINATED, FAIL_CANCELLED, FAIL_ZERO_SUBSCRIBER
}

sink.emitNext("hello", Sinks.EmitFailureHandler.FAIL_FAST);
```

## 3. Sinks.Many 변형

```java
Sinks.Many<String> u = Sinks.many().unicast().onBackpressureBuffer();
Sinks.Many<String> m = Sinks.many().multicast().onBackpressureBuffer();
Sinks.Many<String> mb = Sinks.many().multicast().directBestEffort();
Sinks.Many<String> r1 = Sinks.many().replay().limit(100);
Sinks.Many<String> r2 = Sinks.many().replay().latest();
```

선택 기준:
- unicast: 1:1 channel. SSE 응답을 한 클라이언트에게만.
- multicast onBackpressureBuffer: 여러 subscriber, 각자 demand 따로. 가장 보편적.
- multicast directBestEffort: dashboard 처럼 최신 데이터가 중요한 경우.
- replay limit: chat 룸 입장 시 최근 N 개 메시지 같이 받기.

multicast 모드 함정: `multicast().onBackpressureBuffer(N)` 의 buffer 는 sink 전체에 1개. 가장 느린 subscriber 가 buffer 비움 속도를 결정. 막기 위해 각 subscriber chain 에 다시 onBackpressureBuffer / onBackpressureDrop 을 두 번째 layer 로 둔다.

## 4. EmitFailureHandler — 동시 emit 처리 전략

| 상황 | 권장 핸들러 | 이유 |
| --- | --- | --- |
| 단일 producer 보장됨 | FAIL_FAST | 동시성 없음, retry 의미 없음 |
| 여러 producer, 손실 허용 안됨 | busyLooping(Duration.ofMillis(100)) | spin 으로 직렬화 |
| 여러 producer, 짧은 burst | 외부 ConcurrentLinkedQueue + 단일 drain thread | spin 으로 못 풀 만큼 |

busyLooping 을 무한 루프로 두면 producer 스레드가 영원히 풀리지 않을 수 있다. 동시 producer 수가 4개를 넘어가는 순간 busyLoop 의 spin 비용이 급증.

## 5. OverflowStrategy

| 전략 | 동작 | 손실 | use case |
| --- | --- | --- | --- |
| BUFFER | 무제한 buffer | OOM 위험 | 짧은 burst |
| DROP | 새 데이터 폐기 | 새것을 잃음 | 메트릭 수집 |
| LATEST | 1개 슬롯, 매번 덮어씀 | 중간 모두 잃음 | UI 업데이트 |
| ERROR | onError 발생 | terminate | 명세 위반은 즉시 |
| IGNORE | demand 무시하고 push | spec violation | 거의 사용 안함 |

```java
flux.onBackpressureBuffer(
    1024,
    dropped -> log.warn("dropped: {}", dropped),
    BufferOverflowStrategy.DROP_OLDEST
);
```

## 6. 실측 — producer 1MB/s, consumer 200KB/s

| 전략 | 처리된 byte | 누적 backlog 최대 | 동작 요약 |
| --- | --- | --- | --- |
| BUFFER (무제한) | 1.0MB | 4.0MB | 5초 후에도 4MB 가 메모리에 |
| BUFFER(1024) DROP_OLDEST | 1.0MB | ~1MB | 새 데이터 우선 |
| DROP | 1.0MB | 0 (zero-buffer) | drop 한 데이터가 80% |
| LATEST | 1.0MB | 1 item | 최신 1개만 보존 |
| ERROR | 0.2MB | 0 | 1024개 초과 즉시 onError |

throttling 은 `Flux.limitRate(n)` 또는 `Flux.delayElements(Duration)` 으로 구현. `Flux.window(Duration)` 으로 시간 단위로 묶고 flatMap 으로 batch 처리도 자주 쓴다.

## 7. WebFlux 컨트롤러에서 SSE 실전 예제

```java
@RestController
@RequestMapping("/events")
public class EventController {

    private final Sinks.Many<EventDto> sink =
        Sinks.many().multicast().onBackpressureBuffer(1024, false);

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<EventDto>> stream() {
        return sink.asFlux()
            .onBackpressureBuffer(256, BufferOverflowStrategy.DROP_OLDEST)
            .map(e -> ServerSentEvent.<EventDto>builder()
                .id(String.valueOf(e.id())).event("update").data(e).build())
            .timeout(Duration.ofSeconds(30))
            .onErrorResume(TimeoutException.class, ex -> Flux.empty());
    }

    @PostMapping("/publish")
    public Mono<Void> publish(@RequestBody EventDto e) {
        Sinks.EmitResult r = sink.tryEmitNext(e);
        if (r.isFailure()) log.warn("emit failed: {}", r);
        return Mono.empty();
    }
}
```

운영 패턴:
- `Flux.merge(sink.asFlux(), Flux.interval(Duration.ofSeconds(15)).map(t -> heartbeat()))` 로 idle connection NLB 타임아웃 회피.
- subscribe 시 `Hooks.onErrorDropped` 로 client cancel 시 발생하는 ChannelClosedException silently 처리.
- SSE `Last-Event-ID` 헤더를 받아 sink 의 replay buffer 에서 그 이후만 재생.

## 8. 디버깅 도구 — Hooks 와 checkpoint

```java
Hooks.onOperatorDebug();
ReactorDebugAgent.init();

flux.flatMap(x -> remote(x))
    .checkpoint("after remote")
    .onBackpressureBuffer(1024)
    .checkpoint("after buffer")
    .subscribe();
```

- BlockHound: reactive scheduler thread 안에서 blocking call 감지.
- Micrometer Reactor metric: Flux.metrics() 로 onNext rate, onError count 추출.
- Reactor 3.5+ 의 contextCapture(): MDC, OpenTelemetry trace context 자동 전파.

## 9. trade-off 정리

| 상황 | 선택 | 거부할 대안 |
| --- | --- | --- |
| 외부 webhook 처럼 자유 push | onBackpressureBuffer + maxSize + DROP_OLDEST | 무한 BUFFER (OOM 위험) |
| 가격 tick stream, 최신만 의미 | onBackpressureLatest | 무한 BUFFER, DROP |
| 결제 이벤트, 누락 절대 불가 | producer throttle + ERROR overflow | DROP / LATEST 류 |
| 여러 SSE client 의 fan-out | multicast + per-subscriber buffer | unicast |
| event sourcing replay | replay().limit(N) 또는 외부 storage 의 catch-up flux | replay().all (메모리 폭발) |

규칙: 첫 번째로 producer rate 와 consumer rate 의 비를 추정하고, 그 비가 1.0 미만이면 throttle 을 우선 검토하라. 비가 영구적으로 1.0 이상이면 어떤 buffer 도 결국 손실이나 OOM 으로 귀결된다.

## 참고

- Reactive Streams Specification: https://github.com/reactive-streams/reactive-streams-jvm/blob/master/README.md
- Project Reactor — Sinks API: https://projectreactor.io/docs/core/release/reference/#sinks
- Project Reactor — Backpressure: https://projectreactor.io/docs/core/release/reference/#_backpressure
- Reactor 3.4 release notes: https://github.com/reactor/reactor-core/releases/tag/v3.4.0
- BlockHound: https://github.com/reactor/BlockHound
- Spring WebFlux Reference: https://docs.spring.io/spring-framework/reference/web/webflux-functional.html
