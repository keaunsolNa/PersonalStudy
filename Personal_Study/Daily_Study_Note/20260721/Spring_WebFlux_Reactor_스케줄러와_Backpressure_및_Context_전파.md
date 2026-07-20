Notion 원본: https://app.notion.com/p/3a35a06fd6d38198b35efe8549018c4b

# Spring WebFlux Reactor 스케줄러와 Backpressure 및 Context 전파

> 2026-07-21 신규 주제 · 확장 대상: Spring

## 학습 목표

- Reactor 의 스케줄러 종류별 스레드 모델과 `publishOn`/`subscribeOn` 의 실행 경계를 구분한다
- Reactive Streams 의 request(n) 프로토콜로 backpressure 가 어떻게 전파되는지 추적한다
- `ThreadLocal` 이 깨지는 논블로킹 환경에서 Reactor Context 로 요청 스코프 값을 전파한다
- WebFlux 에서 블로킹 코드를 격리하고 이벤트 루프 고갈을 진단하는 기준을 세운다

## 1. 이벤트 루프 모델

WebFlux 는 Netty 위에서 동작하며 기본적으로 CPU 코어 수만큼의 이벤트 루프 스레드만으로 모든 요청을 처리한다. 서블릿 기반 MVC 가 요청당 스레드를 점유하는 것과 근본적으로 다르다. 이 모델의 이점은 이벤트 루프에서 절대 블로킹하지 않는다는 계약을 지킬 때만 성립한다. JDBC 처럼 블로킹 I/O 를 이벤트 루프에서 호출하면 WebFlux 의 모든 장점이 사라진다.

```java
@GetMapping("/good")
public Mono<User> good(Long id) {
	return Mono.fromCallable(() -> jdbcUserRepository.findById(id))
			.subscribeOn(Schedulers.boundedElastic());
}
```

## 2. 스케줄러 종류와 스레드 모델

| 스케줄러 | 스레드 수 | 용도 |
|---|---|---|
| parallel() | CPU 코어 수 고정 | CPU 바운드 연산 |
| boundedElastic() | 코어 × 10 상한 | 블로킹 I/O 격리 |
| single() | 1 | 순차 저비용 작업 |
| immediate() | 호출 스레드 | 스케줄링 없음 |

`boundedElastic` 은 기본 상한이 `10 × 코어 수`이고 유휴 스레드는 60초 후 회수된다. 과거 `elastic()` 은 무한정 스레드를 만들어 OOM 을 유발했기 때문에 deprecated 되었다. CPU 바운드 작업은 워커가 코어 수로 고정된 `parallel()` 에 올린다.

## 3. subscribeOn 과 publishOn 의 실행 경계

`subscribeOn` 은 구독 신호가 위로 올라가는 방향 전체에 영향을 주고 소스의 실행 스레드를 결정한다. `publishOn` 은 데이터가 아래로 흐르는 방향에서 하위 연산자들의 실행 스레드를 바꾸며, 여러 번 쓰면 그 지점부터 스레드가 전환된다.

```java
Flux.range(1, 5)
		.map(i -> i * 2)
		.publishOn(Schedulers.parallel())
		.map(i -> i + 1)
		.subscribeOn(Schedulers.boundedElastic())
		.subscribe();
```

실무에서는 소스 근처에서 한 번만 `subscribeOn`, 중간에 스레드 전환이 필요할 때 `publishOn` 을 쓰는 패턴이 명확하다.

## 4. Backpressure — request(n) 프로토콜

Reactive Streams 의 핵심은 소비자가 생산자에게 처리할 수 있는 만큼만 보내라고 역방향으로 신호를 보내는 것이다. Reactor 연산자는 대부분 프리페치(기본 256)만큼 request 하고 75%를 소비하면 다시 채운다.

```java
Flux.create(sink -> registerFastProducer(sink), FluxSink.OverflowStrategy.LATEST)
		.onBackpressureBuffer(1000, dropped -> log.warn("dropped: {}", dropped),
				BufferOverflowStrategy.DROP_OLDEST)
		.publishOn(Schedulers.boundedElastic(), 32)
		.subscribe(this::slowConsume);
```

`BUFFER` 는 데이터를 잃지 않지만 메모리를 소비하고, `DROP`/`LATEST` 는 최신성을 지키되 데이터를 버리며, `ERROR` 는 즉시 실패해 문제를 조기에 드러낸다.

## 5. Reactor Context — ThreadLocal 대체

논블로킹 파이프라인에서는 하나의 요청이 여러 스레드를 옮겨 다니므로 `ThreadLocal` 이 깨진다. Reactor Context 는 구독 시점에 아래에서 위로 전파되는 불변 키-값 저장소다.

```java
Mono.deferContextual(ctx -> callDownstream(ctx.get("traceId")))
		.contextWrite(Context.of("traceId", UUID.randomUUID().toString()))
		.subscribe();
```

`contextWrite` 는 그것을 읽는 연산자보다 아래(하류)에 있어야 한다. Spring Security 리액티브 버전은 `ReactiveSecurityContextHolder` 로 인증 정보를 Context 에 실어 나른다.

## 6. MDC 로깅 브리징

`ThreadLocal` 기반 SLF4J MDC 는 WebFlux 에서 그냥은 동작하지 않는다. `Hooks.enableAutomaticContextPropagation()` 을 켜면 마이크로미터 context-propagation 의 `ThreadLocalAccessor` 를 통해 각 스레드 경계에서 ThreadLocal 을 복원한다.

## 7. 블로킹 검출과 이벤트 루프 보호

개발 단계에서 BlockHound 를 붙이면 이벤트 루프나 `parallel` 스레드에서 블로킹 호출이 일어날 때 `BlockingOperationError` 를 던져 즉시 드러낸다. 운영에서는 p99 지연이 튀는데 CPU 는 낮다면 이벤트 루프가 블로킹되고 있다는 신호다. 큐가 지속적으로 쌓이면 JDBC 를 R2DBC 로 교체하는 게 근본 해법이다.

## 8. WebFlux 를 선택할 때와 아닐 때

WebFlux 는 I/O 대기가 지배적이고 동시 커넥션이 매우 많은 게이트웨이·스트리밍·프록시 워크로드에서 진가를 발휘한다. 반대로 순수 CPU 바운드이거나 팀이 JDBC/JPA 에 강하게 묶여 있으면 MVC + 가상 스레드(Java 21 Loom)가 더 나은 선택일 수 있다. 이 트레이드오프를 팀 역량과 워크로드 특성에 맞춰 판단하는 것이 핵심이다.

## 참고

- Reactor 공식 레퍼런스: Schedulers, Context, Backpressure 챕터
- Reactive Streams 명세 1.0.4 (reactive-streams.org)
- BlockHound 프로젝트 문서 (github.com/reactor/BlockHound)
- Micrometer Context Propagation 문서 (micrometer.io)
