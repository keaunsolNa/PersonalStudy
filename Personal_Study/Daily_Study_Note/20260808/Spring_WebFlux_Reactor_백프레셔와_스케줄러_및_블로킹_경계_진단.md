Notion 원본: https://www.notion.so/3b65a06fd6d381e9a312f7e996bd3fbe

# Spring WebFlux Reactor 백프레셔와 스케줄러 및 블로킹 경계 진단

> 2026-08-08 신규 주제 · 확장 대상: Spring (MVC·트랜잭션·AOP·Security 기학습) + 리액티브 스트림 확장

## 학습 목표

- Reactive Streams 4개 인터페이스와 request(n) 기반 백프레셔 전파 경로를 추적한다
- onBackpressureBuffer/Drop/Latest와 limitRate의 동작 차이를 오버플로우 시나리오로 비교한다
- publishOn/subscribeOn의 스레드 경계와 Schedulers 4종의 용도를 구분해 적용한다
- BlockHound·체크포인트·컨텍스트 전파로 블로킹 유출과 조립/구독 시점 문제를 진단한다

## 1. Reactive Streams 계약 — 백프레셔는 풀 기반이다

Reactive Streams 명세는 인터페이스 4개(`Publisher`, `Subscriber`, `Subscription`, `Processor`)와 43개 규칙으로 구성되며, 핵심 계약은 하나다. **Publisher는 Subscriber가 `request(n)`으로 요청한 개수를 초과해 `onNext`를 호출할 수 없다.** 즉 데이터는 푸시되지만 유량은 소비자가 풀 방식으로 통제한다. `request(Long.MAX_VALUE)`는 "무제한 푸시" 선언으로 백프레셔를 사실상 끄는 것이다.

```java
Flux.range(1, 1000)
	.log() // request/onNext 신호 관측
	.subscribe(new BaseSubscriber<Integer>() {
		@Override
		protected void hookOnSubscribe(Subscription s) {
			request(10); // 최초 10개만
		}

		@Override
		protected void hookOnNext(Integer value) {
			if (value % 10 == 0) {
				request(10); // 10개 소비마다 다음 10개
			}
		}
	});
// log 출력: request(10) → onNext×10 → request(10) → ...
```

중요한 것은 백프레셔가 **연산자 체인을 거슬러 전파**된다는 점이다. `map`은 요청을 그대로 상류에 전달하지만, `flatMap(fn, concurrency)`은 내부 동시성만큼(기본 256) 미리 요청하고, `buffer(n)`은 배수로 증폭한다. 소스가 요청량을 조절할 수 없는 종류(WebSocket 수신, Kafka 컨슈머 폴링 결과)라면 요청 계약을 지킬 수 없으므로 §2의 오버플로우 전략이 필요해진다.

## 2. 오버플로우 전략 — Buffer, Drop, Latest, Error

생산 속도가 소비 요청을 초과할 때의 처리 전략이 `onBackpressure*` 연산자군이다. 선택은 데이터의 의미론이 결정한다.

| 연산자 | 오버플로우 시 동작 | 적합 도메인 |
|---|---|---|
| `onBackpressureBuffer()` | 무제한 버퍼 적재 | 유실 불가 · 단기 폭주 (메모리 리스크 감수) |
| `onBackpressureBuffer(n, 콜백, 전략)` | n 초과 시 DROP_OLDEST/DROP_LATEST/ERROR | 유실 불가 + 상한 필요 |
| `onBackpressureDrop(콜백)` | 초과분 즉시 폐기 | 손실 허용 이벤트(메트릭, 로그) |
| `onBackpressureLatest()` | 최신 1건만 유지 | 최종 상태만 의미 있는 값(시세, 좌표) |
| 기본 (전략 없음) | `Exceptions.failWithOverflow()` → onError | 명시적 실패가 나은 경우 |

`limitRate(n)`은 성격이 다르다. 오버플로우 대응이 아니라 **상류로 가는 요청을 n 단위로 쪼개고, 75%(기본 lowTide) 소비 시점에 보충 요청하는 프리페치 제어**다. 하류가 `request(MAX_VALUE)`를 걸어도 상류에는 n씩만 전달되므로, DB 커서·원격 API처럼 일괄 인출 크기를 제한하고 싶을 때 쓴다. 실무 사고 패턴은 무한 버퍼다 — `onBackpressureBuffer()` 기본형을 무심코 쓴 파이프라인이 다운스트림 지연 시 힙을 다 먹고 OOM으로 죽는다. 상한 없는 버퍼는 코드 리뷰에서 기본 반려 대상으로 두는 것이 안전하다.

## 3. publishOn과 subscribeOn — 스레드 경계의 규칙

두 연산자의 규칙은 짧지만 혼동이 잦다. **`publishOn`은 자신 아래(다운스트림) 연산자의 실행 스레드를 바꾸고, `subscribeOn`은 구독 신호가 올라가는 경로 즉 소스의 실행 스레드를 바꾼다.** subscribeOn은 체인 내 위치와 무관하게 소스에 작용하며 여러 개면 가장 소스에 가까운 것만 유효하다. publishOn은 위치마다 유효해 체인을 구간별로 다른 스레드에 배정할 수 있다.

```java
Flux.fromIterable(loadIds())          // subscribeOn 지정 스레드에서 실행
	.map(this::parse)                  // 동일 (boundedElastic)
	.subscribeOn(Schedulers.boundedElastic())
	.publishOn(Schedulers.parallel())  // ↓ 이후는 parallel 스레드
	.map(this::compute)
	.publishOn(Schedulers.single())    // ↓ 이후는 single 스레드
	.doOnNext(this::writeMetric)
	.subscribe();
```

Schedulers 4종의 용도: `parallel()`은 CPU 코어 수 워커로 계산 작업 전용(블로킹 금지), `boundedElastic()`은 블로킹 작업 격리용(기본 상한: 코어×10 스레드, 큐 100k — 상한 도달 시 `RejectedExecutionException`), `single()`은 순서 보장이 필요한 저빈도 작업, `immediate()`는 현재 스레드 유지(전환 억제 명시)다. WebFlux 요청 처리는 기본적으로 Netty 이벤트 루프(코어 수만큼)에서 실행되므로, **이벤트 루프에서의 블로킹 1건은 해당 루프에 배정된 모든 커넥션을 멈춘다.** 이것이 다음 절의 블로킹 진단이 리액티브 스택 운영의 최우선 과제인 이유다.

## 4. 블로킹 유출 진단 — BlockHound와 격리 패턴

블로킹 유출의 전형은 세 가지다: 리액티브 체인 안의 JDBC/RestTemplate 호출, `Mono.block()`을 숨긴 유틸 메서드, 그리고 의외로 흔한 `Thread.sleep`·파일 IO·DNS 조회다. 탐지 도구는 **BlockHound** — ByteBuddy 에이전트로 JDK 블로킹 메서드에 훅을 심어, parallel/이벤트 루프 스레드에서 호출되면 `BlockingOperationError`를 던진다.

```java
// 테스트 수트 전역 설치 (프로덕션 오버헤드 회피)
class BlockHoundSetup {
	static {
		BlockHound.install(builder -> builder
			// 검증된 예외 허용 목록은 명시적으로만 추가
			.allowBlockingCallsInside("io.netty.resolver.HostsFileParser", "parse"));
	}
}

@Test
void detectBlocking() {
	StepVerifier.create(
			Mono.delay(Duration.ofMillis(1))
				.map(t -> {
					legacyJdbcCall(); // → BlockingOperationError
					return t;
				}))
		.expectError(Error.class)
		.verify();
}
```

블로킹이 불가피한 레거시 의존(JDBC, JNI 라이브러리)은 제거가 아니라 **격리**한다: `Mono.fromCallable(::blockingCall).subscribeOn(Schedulers.boundedElastic())`이 표준형이다. 단 boundedElastic은 격리 수단이지 성능 수단이 아니다 — 처리량이 스레드 상한에 묶이므로, 사실상 전 요청이 boundedElastic을 통과한다면 그 시스템은 "스레드풀 기반 MVC를 복잡하게 재구현"한 것에 불과하다. 이 경우 R2DBC 등 논블로킹 드라이버 전환 또는 애초에 MVC + 가상 스레드 채택이 정직한 선택지다.

## 5. 조립 시점과 구독 시점 — cold 소스의 함정

Reactor 파이프라인은 선언(조립) 시점에는 아무것도 실행하지 않고 구독 시점에 소스부터 실행된다. 이 구분을 놓친 버그 두 가지가 반복적으로 나타난다. 첫째, **조립 시점 값 고정**: `Mono.just(computeNow())`는 조립 순간 `computeNow()`를 실행해 값을 박제한다. 구독마다 재평가하려면 `Mono.fromSupplier(this::computeNow)` 또는 `Mono.defer(() -> loadMono())`가 필요하다. 재시도(`retry`)와 조합될 때 특히 치명적인데, `defer` 없이 조립된 Mono를 retry해도 같은 값/같은 실패가 반복된다. 둘째, **다중 구독 부작용**: cold Publisher는 구독마다 소스가 재실행되므로, 하나의 `Mono<Result>`를 두 곳에서 subscribe하면 원격 호출이 두 번 나간다. 공유가 의도라면 `cache()`(값 리플레이) 또는 `share()`/`publish().refCount()`(hot 변환)를 명시한다.

```java
// retry가 의미 있으려면 defer로 구독마다 새 호출 생성
Mono<Response> resilient = Mono.defer(() -> webClient.get()
		.uri("/api/data").retrieve().bodyToMono(Response.class))
	.timeout(Duration.ofSeconds(2))
	.retryWhen(Retry.backoff(3, Duration.ofMillis(200)).jitter(0.5));
```

## 6. 오류 처리와 재시도 전략

리액티브 체인에서 onError는 **종결 신호**다 — 스트림은 그 지점에서 끝나며, catch처럼 지나가는 것이 아니다. 무한 스트림(이벤트 구독 등)에서 요소 1건의 처리 실패가 스트림 전체를 죽이지 않게 하려면 오류를 요소 단위로 가둬야 한다.

```java
kafkaFlux
	.flatMap(record -> process(record)
		.onErrorResume(e -> {
			log.warn("skip record {}", record.key(), e);
			return sendToDlq(record).then(Mono.empty()); // 요소 단위 격리
		}))
	.subscribe();
```

`onErrorResume`(대체 Publisher), `onErrorReturn`(대체 값), `onErrorContinue`(요소 건너뛰기)를 구분해야 하는데, `onErrorContinue`는 연산자 지원 여부에 의존하는 특수 훅으로 동작이 직관과 다른 경우가 많아(상류 연산자에 소급 적용) 공식 문서도 onErrorResume 조합을 권한다. 재시도는 `Retry.backoff`가 표준 — 지수 백오프에 jitter를 더해 동시 재시도 줍림(thundering herd)을 완화하고, `filter`로 재시도 가치가 있는 예외(타임아웃, 5xx)만 걸러낸다. 멱등하지 않은 쓰기 요청의 무조건 재시도는 중복 부작용을 만드므로 금물이다.

## 7. Context 전파 — ThreadLocal의 대체물

스레드가 고정되지 않으므로 ThreadLocal 기반 관례(MDC 로깅, SecurityContextHolder)는 그대로 작동하지 않는다. Reactor의 대안은 구독 경로를 따라 아래→위로 전파되는 불변 `Context`다. WebFlux Security가 인증 정보를 `ReactiveSecurityContextHolder.getContext()`로 제공하는 것도 이 메커니즘이다.

```java
Mono.deferContextual(ctx -> {
		String traceId = ctx.get("traceId");
		return callDownstream(traceId);
	})
	.contextWrite(Context.of("traceId", generateTraceId()));
// contextWrite는 자신보다 "위(상류)" 연산자에게 보이는 값을 쓴다
// — 읽기(deferContextual)가 쓰기(contextWrite)보다 상류에 있어야 한다
```

MDC 연동은 수동 브리지가 번거로웠으나, Reactor 3.5+의 **`Hooks.enableAutomaticContextPropagation()`과 Micrometer context-propagation 라이브러리** 조합이 표준이 됐다. `ObservationThreadLocalAccessor`를 등록하면 traceId가 스레드 전환을 넘어 MDC에 자동 복원되어, 로그 상관관계가 MVC 수준으로 회복된다. 도입 전 부하 테스트로 오버헤드(수 % 수준)를 확인하는 것이 권장 절차다.

## 8. 테스트 — StepVerifier와 가상 시간

리액티브 코드 테스트의 표준 도구는 reactor-test의 `StepVerifier`다. 신호 시퀀스(onNext/onComplete/onError)를 단계적으로 단언하고, 백프레셔 시나리오는 `thenRequest`로 요청량을 직접 제어해 검증한다.

```java
@Test
void backpressureContract() {
	StepVerifier.create(Flux.range(1, 100), 0) // 초기 요청 0
		.expectSubscription()
		.thenRequest(2)
		.expectNext(1, 2)
		.expectNoEvent(Duration.ofMillis(100)) // 요청 없이는 방출 없음
		.thenRequest(98)
		.expectNextCount(98)
		.verifyComplete();
}

@Test
void timeoutWithVirtualTime() {
	StepVerifier.withVirtualTime(() ->
			Mono.delay(Duration.ofHours(3)).then(Mono.just("done")))
		.expectSubscription()
		.thenAwait(Duration.ofHours(3)) // 실제 대기 없이 시계 전진
		.expectNext("done")
		.verifyComplete();
}
```

`withVirtualTime`은 내부에서 Schedulers를 `VirtualTimeScheduler`로 바꿔치기하므로 **Publisher 생성을 반드시 Supplier 안에서** 해야 한다(밖에서 만들면 실제 스케줄러에 이미 바인딩). 통합 수준에서는 `WebTestClient`로 SSE/스트리밍 응답을 검증하고, §4의 BlockHound를 테스트 프로파일에 상시 켜서 블로킹 회귀를 CI에서 잡는 구성이 실무 표준이다.

## 9. 아키텍처 판단 — WebFlux를 쓸 곳과 쓰지 않을 곳

마지막으로 기술 선택 기준을 정리한다. WebFlux의 이득은 **동시 연결 수가 스레드 수를 압도하는 워크로드**에서 나온다: 스트리밍(SSE/WebSocket) 다중 팬아웃, 느린 다운스트림을 기다리는 고동시성 API 게이트웨이, 백프레셔가 필수인 파이프라인(Kafka → 가공 → 저장)이 전형이다. 반면 전형적 CRUD + JPA 스택은 드라이버가 블로킹이라 이득이 소멸하고, 디버깅 난도(스택트레이스 단절 — `Hooks.onOperatorDebug()`는 프로덕션 금지 수준의 오버헤드, checkpoint()로 국소 보완)와 팀 학습 비용이 상수로 붙는다. **Java 21+ 가상 스레드는 이 판단을 바꿨다** — "스레드 고갈 회피"만이 목적이면 명령형 코드 그대로 가상 스레드(Spring Boot 3.2+ `spring.threads.virtual.enabled=true`)로 충분한 경우가 많다. 그럼에도 리액티브가 남는 자리는 백프레셔 계약 자체가 필요한 곳, 그리고 R2DBC·Reactor Kafka 등 논블로킹 생태계와 스트림 합성(zip/merge/window)이 도메인 로직인 곳이다. 요약하면 "동시성 문제는 가상 스레드, 유량 제어 문제는 Reactor"가 2020년대 중반의 실용적 분업이다.

## 참고

- Project Reactor Reference Guide (projectreactor.io/docs/core/release/reference) — Backpressure·Schedulers·Context 장
- Reactive Streams Specification 1.0.4 (reactive-streams.org) — 43개 규칙 원문
- reactor/BlockHound GitHub — Detecting blocking calls 문서
- Spring Framework Reference — Web on Reactive Stack
- Oleh Dokuka & Igor Lozynskyi, 『Hands-On Reactive Programming in Spring 5』
