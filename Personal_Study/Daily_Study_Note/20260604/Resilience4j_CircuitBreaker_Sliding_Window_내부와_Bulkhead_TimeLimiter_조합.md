Notion 원본: https://www.notion.so/3755a06fd6d381a6892ef173e340d303

# Resilience4j CircuitBreaker Sliding Window 내부와 Bulkhead TimeLimiter 조합

> 2026-06-04 신규 주제 · 확장 대상: Spring Boot 마이크로서비스 호출 안정화 (REST API / Spring 학습됨)

## 학습 목표

- CircuitBreaker 의 상태 머신과 COUNT/TIME 기반 Sliding Window 집계 구조를 소스 수준에서 분석한다
- slowCallRate 가 failureRate 와 독립적으로 차단을 트리거하는 조건을 실험으로 확인한다
- Bulkhead(세마포어/스레드풀) · TimeLimiter · Retry 를 CircuitBreaker 와 조합할 때의 데코레이터 순서 규칙을 적용한다
- Spring Boot 3 + Resilience4j 환경에서 Actuator 메트릭으로 차단 이벤트를 관측하고 임계값을 튜닝한다

## 1. 상태 머신 — CLOSED / OPEN / HALF_OPEN과 두 개의 특수 상태

CircuitBreaker 는 5개 상태를 가진다. 핵심 3개(CLOSED → OPEN → HALF_OPEN)와 운영 개입용 2개(DISABLED, FORCED_OPEN)다.

```
CLOSED ──(failureRate ≥ threshold)──▶ OPEN
   ▲                                    │ waitDurationInOpenState 경과
   │                                    ▼
   └──(시험 호출 성공률 회복)── HALF_OPEN ──(여전히 실패)──▶ OPEN
```

상태 전이는 호출 결과가 기록될 때마다 평가된다. 중요한 세부: OPEN → HALF_OPEN 전이는 기본적으로 **다음 호출이 도착했을 때 lazy 하게** 일어난다. `automaticTransitionFromOpenToHalfOpenEnabled: true` 를 켜면 스케줄러 스레드가 시간 경과 시점에 즉시 전이시키는데, 인스턴스가 많으면 스레드 비용이 있으므로 트래픽이 항상 흐르는 서비스에서는 기본값(lazy)이 낫다.

HALF_OPEN 에서는 `permittedNumberOfCallsInHalfOpenState`(기본 10) 개의 시험 호출만 통과시키고 나머지는 `CallNotPermittedException` 으로 즉시 거부한다. 시험 호출의 실패율이 임계값 미만이면 CLOSED, 이상이면 다시 OPEN 이다.

## 2. Sliding Window — 고정 배열 기반 O(1) 집계

실패율 계산의 핵심 자료구조는 두 종류다.

**COUNT_BASED**: 크기 N 의 원형 배열에 최근 N 개 호출 결과를 기록한다. 각 슬롯은 (성공/실패/느림) 을 담고, 새 기록이 가장 오래된 슬롯을 덮어쓸 때 전체 카운터에서 그 슬롯 값을 빼고 새 값을 더한다. 합산을 매번 다시 하지 않으므로 기록·집계 모두 O(1)이다.

**TIME_BASED**: N 초를 1초 단위 버킷(partial aggregation) N 개로 나눈 원형 배열이다. 각 버킷은 해당 초에 발생한 호출의 (호출 수, 실패 수, 느린 호출 수, 총 소요 시간)을 누적하고, 에포크 초가 넘어가면 가장 오래된 버킷을 전체 집계에서 차감 후 재사용한다. 호출 단위가 아니라 초 단위 버킷이므로 메모리가 호출량과 무관하게 고정된다 — 고QPS 서비스에서 TIME_BASED 를 권하는 이유다.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        slidingWindowType: TIME_BASED
        slidingWindowSize: 60              # 60초 윈도우 = 버킷 60개
        minimumNumberOfCalls: 50           # 이 수 미만이면 실패율 평가 안 함
        failureRateThreshold: 50           # %
        slowCallDurationThreshold: 2s
        slowCallRateThreshold: 80          # 느린 호출 80% 이상도 OPEN 트리거
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 10
```

`minimumNumberOfCalls` 는 콜드 스타트 보호 장치다. 윈도우에 50건이 쌓이기 전에는 100% 실패해도 차단하지 않는다. 트래픽이 적은 내부 API 라면 이 값을 낮추지 않으면 차단기가 사실상 동작하지 않는 함정이 있다.

## 3. slowCallRate — 타임아웃 없이도 열리는 차단기

failureRate 는 예외 기준이지만, slowCallRate 는 **성공했지만 느린** 호출의 비율이다. 하류 서비스가 에러 없이 응답 시간만 늘어나는 회색 장애(gray failure)에서 failureRate 는 0% 로 유지되므로 slowCallRate 가 유일한 방어선이 된다.

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
	.slowCallDurationThreshold(Duration.ofSeconds(2))
	.slowCallRateThreshold(80)
	.failureRateThreshold(50)
	.recordExceptions(IOException.class, TimeoutException.class)
	.ignoreExceptions(BusinessValidationException.class)
	.build();
```

`ignoreExceptions` 는 실패로도 성공으로도 집계하지 않는다(윈도우에서 제외). 4xx 성격의 비즈니스 예외를 실패로 집계하면 클라이언트 입력 오류 폭주가 차단기를 열어 정상 사용자까지 차단하는 사고가 난다 — 반드시 분리한다.

## 4. Bulkhead — 세마포어 vs 스레드풀 격리

Bulkhead 는 동시 실행 수를 제한해 한 하류의 지연이 전체 스레드를 잠식하는 것을 막는다. 두 구현의 선택 기준이 명확히 다르다.

| 구분 | SemaphoreBulkhead | FixedThreadPoolBulkhead |
| --- | --- | --- |
| 실행 위치 | 호출자 스레드 | 전용 풀 스레드 |
| 대기 | maxWaitDuration 동안 블로킹 | 큐(capacity) 적재 |
| 타임아웃 강제 | 불가(실행 중 중단 못 함) | TimeLimiter와 조합 가능 |
| 적합 환경 | Virtual Threads, 동기 호출 | CompletableFuture 비동기 |

```java
// 세마포어 — 동시 25건 초과 시 5ms만 기다리고 BulkheadFullException
BulkheadConfig semConfig = BulkheadConfig.custom()
	.maxConcurrentCalls(25)
	.maxWaitDuration(Duration.ofMillis(5))
	.build();

// 스레드풀 — 코어 8, 큐 16
ThreadPoolBulkheadConfig tpConfig = ThreadPoolBulkheadConfig.custom()
	.coreThreadPoolSize(8)
	.maxThreadPoolSize(8)
	.queueCapacity(16)
	.build();
```

Java 21 Virtual Threads 환경이라면 스레드풀 Bulkhead 의 존재 이유(플랫폼 스레드 고갈 방지)가 약해진다. 가상 스레드는 수만 개 생성이 가능하므로 **동시성 상한 자체가 목적**인 세마포어 방식이 자연스럽고, 하류 보호용 상한(connection pool 크기와 정렬)으로 maxConcurrentCalls 를 설정하는 패턴이 표준이 된다.

## 5. 데코레이터 순서 — 무엇이 무엇을 감싸는가

Resilience4j 는 함수형 데코레이터 체인이다. 순서가 의미를 바꾼다. Spring 어노테이션 방식의 기본 적용 순서는 바깥부터 `Bulkhead → TimeLimiter → RateLimiter → CircuitBreaker → Retry` 다.

```
Bulkhead( TimeLimiter( CircuitBreaker( Retry( 원본 호출 ) ) ) )
```

이 순서의 함의를 짚으면:

- **Retry 가 가장 안쪽**: Retry 가 CircuitBreaker 안쪽이면 재시도마다 차단기를 통과하므로 각 시도가 집계된다. 바깥이면 N 회 재시도가 1건으로 집계된다. 기본 순서(Retry 안쪽)는 "각 시도를 집계"하는 쪽이고, 이는 실패율을 실제 시도 기준으로 반영한다.
- **TimeLimiter 가 CircuitBreaker 바깥**: 타임아웃으로 끊긴 호출이 `TimeoutException` 으로 차단기에 실패 기록된다. 반대로 안쪽이면 차단기는 타임아웃을 모른다.
- **Bulkhead 최외곽**: 가득 차면 차단기 집계에 아예 잡히지 않고 즉시 거부된다. Bulkhead 거부를 차단기 실패로 집계하고 싶다면 순서를 바꿔야 한다.

프로그래밍 방식으로 순서를 명시할 수 있다.

```java
Supplier<PaymentResult> decorated = Decorators
	.ofSupplier(() -> paymentClient.charge(req))
	.withRetry(retry)              // 안쪽
	.withCircuitBreaker(cb)
	.withBulkhead(bulkhead)        // 바깥쪽
	.decorate();
```

Spring 에서는 어노테이션 aspect 순서를 프로퍼티로 조정한다.

```yaml
resilience4j:
  retry.retryAspectOrder: 1
  circuitbreaker.circuitBreakerAspectOrder: 2   # 숫자 클수록 바깥
```

## 6. Spring Boot 3 통합 — 어노테이션과 함정

```java
@Service
public class PaymentService {

	@CircuitBreaker(name = "paymentApi", fallbackMethod = "fallback")
	@Retry(name = "paymentApi")
	@TimeLimiter(name = "paymentApi")
	public CompletableFuture<PaymentResult> charge(PaymentRequest req) {
		return CompletableFuture.supplyAsync(() -> client.charge(req));
	}

	private CompletableFuture<PaymentResult> fallback(PaymentRequest req, CallNotPermittedException ex) {
		return CompletableFuture.completedFuture(PaymentResult.queued(req));
	}
}
```

함정 목록:

- `@TimeLimiter` 는 반환 타입이 `CompletionStage`/`Future` 여야 한다. 동기 메서드에 붙이면 기동 시 예외가 난다. 동기 코드 타임아웃은 RestClient 의 connect/read timeout 으로 거는 것이 맞다.
- fallbackMethod 는 **같은 클래스**의 메서드를 시그니처(원본 인자 + 예외)로 찾는다. 예외 타입별 오버로드가 가능하며, `CallNotPermittedException` 전용 fallback 으로 "차단 중" 응답과 "실제 실패" 응답을 구분하는 것이 운영상 중요하다.
- 어노테이션은 Spring AOP 프록시 기반이므로 self-invocation(같은 빈 내부 호출)에는 적용되지 않는다.

## 7. 관측 — 메트릭으로 임계값 검증

Actuator + Micrometer 연동 시 노출되는 핵심 메트릭:

```
resilience4j_circuitbreaker_state{name="paymentApi",state="open"} 0|1
resilience4j_circuitbreaker_calls_seconds_count{kind="successful|failed|ignored"}
resilience4j_circuitbreaker_failure_rate{name="paymentApi"}
resilience4j_circuitbreaker_slow_call_rate{name="paymentApi"}
resilience4j_bulkhead_available_concurrent_calls{name="paymentApi"}
```

튜닝 절차는 데이터 기반으로 한다. (1) 운영 p99 지연을 측정해 `slowCallDurationThreshold` 를 p99 의 1.5~2배로 잡는다 — p50 근처로 잡으면 정상 트래픽이 느린 호출로 집계돼 오탐 차단이 난다. (2) `waitDurationInOpenState` 는 하류 복구 시간(재기동 30초, 오토스케일 1~2분)에 맞춘다. (3) 차단 이벤트는 EventConsumer 로 구조화 로그를 남겨 사후 분석한다.

```java
circuitBreakerRegistry.circuitBreaker("paymentApi")
	.getEventPublisher()
	.onStateTransition(e -> log.warn("CB transition: {} {} -> {}",
		e.getCircuitBreakerName(),
		e.getStateTransition().getFromState(),
		e.getStateTransition().getToState()));
```

## 8. 부하 실험 — 차단과 회복 시나리오 재현

차단기 설정은 반드시 장애 주입으로 검증한다. WireMock 으로 하류 지연을 주입하는 통합 테스트:

```java
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class CircuitBreakerIT {

	@Test
	void opensOnSlowCalls_thenRecovers() throws Exception {
		// given: 하류가 3초 지연 (slowCallDurationThreshold=2s 초과)
		stubFor(post("/charge").willReturn(ok().withFixedDelay(3000)));

		// when: minimumNumberOfCalls 이상 호출
		IntStream.range(0, 50).parallel()
			.forEach(i -> safeCall(() -> paymentService.charge(req(i))));

		// then: OPEN 전이
		CircuitBreaker cb = registry.circuitBreaker("paymentApi");
		assertThat(cb.getState()).isEqualTo(State.OPEN);
		assertThat(cb.getMetrics().getSlowCallRate()).isGreaterThan(80f);

		// 하류 정상화 + waitDuration 경과 후 HALF_OPEN 시험 통과 확인
		stubFor(post("/charge").willReturn(ok()));
		await().atMost(Duration.ofSeconds(40))
			.untilAsserted(() -> {
				safeCall(() -> paymentService.charge(req(99)));
				assertThat(cb.getState()).isIn(State.HALF_OPEN, State.CLOSED);
			});
	}
}
```

운영 직전에는 카오스 도구(istio fault injection, Toxiproxy)로 스테이징에서 동일 시나리오를 돌려 fallback 경로의 부하(큐 적재, 대체 API 비용)까지 확인한다. fallback 이 DB 폴백이면 차단 순간 DB 로 부하가 전이되는 2차 장애를 설계 단계에서 막아야 한다.

## 9. Resilience4j vs 대안 — 선택 기준

| 항목 | Resilience4j | Spring Cloud CircuitBreaker | Istio/Envoy Outlier Detection |
| --- | --- | --- | --- |
| 적용 위치 | 애플리케이션 코드 | 추상화 레이어(구현은 R4j) | 사이드카/프록시 |
| 세밀도 | 메서드 단위, 예외 타입별 | 메서드 단위 | 호스트/엔드포인트 단위 |
| fallback | 코드로 표현 | 코드로 표현 | 불가(503 반환) |
| 다국어 서비스 | JVM 한정 | JVM 한정 | 언어 무관 |

메시 차단(Envoy)은 인프라 일관성이 장점이지만 비즈니스 fallback 을 표현할 수 없다. 실무 정답은 양층 방어다 — 메시에서 호스트 수준 outlier ejection, 애플리케이션에서 비즈니스 fallback 과 예외 분류. 단 양쪽 임계값이 충돌하지 않게 메시 쪽을 더 보수적으로(높은 임계값) 설정해 애플리케이션 차단기가 먼저 동작하게 한다.

## 참고

- Resilience4j 공식 문서 — CircuitBreaker, Bulkhead, TimeLimiter (resilience4j.readme.io)
- resilience4j GitHub — SlidingWindowMetrics 구현 (github.com/resilience4j/resilience4j)
- Spring Boot 3 Resilience4j Starter 문서
- Michael Nygard, *Release It!* 2nd Edition — Circuit Breaker / Bulkhead 패턴
- Envoy Outlier Detection 문서 (envoyproxy.io)
