Notion 원본: https://www.notion.so/3dd5a06fd6d3811cbebdd80892895737

# Resilience4j 서킷브레이커 상태머신과 벌크헤드 및 TimeLimiter 조합 설계

> 2026-09-16 신규 주제 · 확장 대상: Spring(Boot) 심화 — Micrometer Observation, Problem Details 예외 계층

## 학습 목표

- 서킷브레이커의 5개 상태와 슬라이딩 윈도 두 종류의 동작 차이를 구분한다
- 세마포어 벌크헤드와 스레드풀 벌크헤드의 적용 조건을 판단한다
- Retry·CircuitBreaker·TimeLimiter 데코레이터의 중첩 순서가 만드는 부작용을 안다
- 상태 전이를 Micrometer 지표로 관측하고 임계값을 근거 있게 정한다

## 1. 왜 타임아웃만으로는 부족한가

하류 서비스가 느려졌을 때 타임아웃만 걸어두면 무슨 일이 생기는지 계산해 보자. 톰캐톷 워커 200개, 하류 호출 타임아웃 3초, 평소 응답 20ms 인 API 가 있다. 하류가 3초로 느려지면 처리량은 초당 200/3 ≈ 66 요청으로 떨어진다. 유입이 초당 500 이면 큐가 무한히 쌓이고, 결국 이 서비스 전체가 죽는다. 하류 하나가 느려졌을 뿐인데 무관한 엔드포인트까지 응답하지 못한다.

서킷브레이커는 "실패가 확실한 호출에 시간을 쓰지 않는다"로 이 문제를 다룬다. 벌크헤드는 "느린 하류가 쓸 수 있는 동시성 총량을 제한한다"로 다룬다. 둘은 대체재가 아니라 보완재다. 서킷이 열리기 전까지의 구간을 벌크헤드가 막고, 벌크헤드가 포화된 상태를 서킷이 끊는다.

Resilience4j 는 Hystrix 의 후속으로 자주 언급되지만 설계가 다르다. Hystrix 는 모든 호출을 별도 스레드풀에 격리하는 것을 기본으로 삼았고, Resilience4j 는 함수형 데코레이터 조합을 기본으로 삼아 스레드 격리를 **선택 사항**으로 뚜다. 가상 스레드 환경에서는 이 차이가 더 벌어진다.

## 2. 서킷브레이커 상태 머신

상태는 5개다. 흔히 3개로 소개되지만 강제 상태 두 개가 더 있다.

| 상태 | 호출 허용 | 전이 조건 |
|---|---|---|
| CLOSED | 전부 허용 | 실패율/느린호출율이 임계 초과 → OPEN |
| OPEN | 전부 차단 (`CallNotPermittedException`) | 대기 시간 경과 → HALF_OPEN |
| HALF_OPEN | 제한된 수만 허용 | 결과 집계 후 CLOSED 또는 OPEN |
| DISABLED | 전부 허용, 집계 안 함 | 수동 전이만 |
| FORCED_OPEN | 전부 차단, 집계 안 함 | 수동 전이만 |

DISABLED / FORCED_OPEN 은 운영 중 수동 개입용이다. 장애 대응 시 특정 하류를 즉시 끊거나, 반대로 서킷 판단을 잠시 무시하고 흘려보낼 때 쓴다.

```java
CircuitBreaker breaker = registry.circuitBreaker("paymentApi");
breaker.transitionToForcedOpenState();   // 즉시 차단
breaker.transitionToClosedState();       // 정상 복귀
```

전이를 판단하는 집계 창은 두 가지다.

**COUNT_BASED**: 최근 N 회 호출을 링버퍼에 담는다. 호출 빈도가 낮고 균일할 때 적합하다. 트래픽이 뛸하면 오래된 실패가 계속 남아 판단이 늘어진다.

**TIME_BASED**: 최근 N 초를 1초 단위 버킷으로 나눠 집계한다. 각 버킷은 성공 수·실패 수·느린 호출 수·총 소요 시간을 누적한 부분 집계이며, 개별 호출을 저장하지 않는다. 트래픽 변동이 큰 API 에 맞다.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        slidingWindowType: TIME_BASED
        slidingWindowSize: 60            # 60초
        minimumNumberOfCalls: 20
        failureRateThreshold: 50         # %
        slowCallRateThreshold: 80        # %
        slowCallDurationThreshold: 2s
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.example.BusinessRuleViolationException
```

여기서 실무적으로 가장 자주 틀리는 항목이 셋 있다.

첫째, `minimumNumberOfCalls`. 이 값보다 호출 수가 적으면 실패율이 100% 여도 서킷이 열리지 않는다. 배포 직후 트래픽이 적은 구간에서 "서킷이 왜 안 열리지"의 원인이 대개 이것이다.

둘째, `ignoreExceptions`. 400 계열 비즈니스 예외를 여기에 넣지 않으면, 사용자 입력 오류가 하류 장애로 집계되어 멀준한 서킷이 열린다. Feign/RestClient 를 쓸 때 4xx 를 예외로 던지도록 설정했다면 반드시 분리해야 한다.

셋째, `slowCallRateThreshold`. 실패는 아니지만 느린 호출을 별도 축으로 집계한다. 하류가 타임아웃 직전까지 버티며 응답하는 "좀비 상태"는 실패율로는 잡힐 수 없다. 느린 호출 기준을 타임아웃의 절반 정도로 잡아 두면 조기 감지가 된다.

`automaticTransitionFromOpenToHalfOpenEnabled` 는 기본값 `false` 다. 끄면 OPEN 상태에서 **다음 호출이 들어올 때** 비로소 시간 경과를 확인해 HALF_OPEN 으로 넘어간다. 호출이 없으면 영원히 OPEN 으로 보인다. 지표만 보고 "복구가 안 된다"고 오판하기 쉽으므로, 대시보드를 신뢰하려면 켜는 편이 낫다. 대신 인스턴스마다 스레드가 하나 더 붙는다.

## 3. 벌크헤드 두 종류

**SemaphoreBulkhead** 는 호출자 스레드를 그대로 쓰면서 동시 진입 수만 제한한다. 오버헤드가 거의 없다.

```yaml
resilience4j:
  bulkhead:
    instances:
      paymentApi:
        maxConcurrentCalls: 25
        maxWaitDuration: 0               # 즉시 거부
```

**ThreadPoolBulkhead** 는 별도 스레드풀로 호출을 넘긴다. 호출자 스레드가 즉시 반환되므로 진짜 격리가 된다. 대신 컨텍스트 전파(SecurityContext, MDC, 트랜잭션)를 직접 챙겨야 한다.

```yaml
resilience4j:
  thread-pool-bulkhead:
    instances:
      paymentApi:
        maxThreadPoolSize: 20
        coreThreadPoolSize: 10
        queueCapacity: 50
        keepAliveDuration: 20ms
```

선택 기준은 단순하다. 호출이 블로킹이고 **호출자 스레드가 톰캐톷 워커처럼 희소 자원**이면 ThreadPoolBulkhead 가 값을 한다. 반대로 리액티브 스택이거나 가상 스레드 위에서 돌고 있으면 세마포어로 충분하다. 가상 스레드는 이미 값싸므로 별도 풀로 옮기는 것이 순손실이다.

```java
// Java 21+ 가상 스레드 환경
@Bean
TomcatProtocolHandlerCustomizer<?> virtualThreadExecutor() {
	return handler -> handler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
}
```

이 설정에서는 워커 고갈 문제가 사라지므로 벌크헤드의 목적이 "내 서비스 보호"에서 "하류 서비스 보호"로 바뀜다. 즉 동시 호출 상한은 여전히 필요하고, 값은 하류가 감당 가능한 수치로 잡는다. 리틀의 법칙으로 역산하면 된다. 하류 목표 처리량 200 rps, 평균 응답 50ms 라면 필요한 동시성은 200 × 0.05 = 10 이다. 여기에 여유 2배를 두어 20 정도가 출발점이 된다.

## 4. 데코레이터 중첩 순서

Resilience4j 는 데코레이터를 함수 합성으로 쌓는다. 순서가 곳 의미다. Spring Boot 스타터의 기본 순서는 바깥쪽부터 이렇다.

```
Retry( CircuitBreaker( RateLimiter( TimeLimiter( Bulkhead( 실제호출 ) ) ) ) )
```

이 순서의 의미를 하나씨 보면 이렇다. Retry 가 가장 바깥이므로 재시도는 서킷브레이커 판정 **이후**에 일어난다. 즉 서킷이 OPEN 이면 `CallNotPermittedException` 이 Retry 로 올라가고, 이것을 재시도 대상에서 제외하지 않으면 열린 서킷을 향해 계속 두드리게 된다.

```yaml
resilience4j:
  retry:
    instances:
      paymentApi:
        maxAttempts: 3
        waitDuration: 200ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        ignoreExceptions:
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
```

반대 순서, 즉 CircuitBreaker 가 Retry 를 감싸면 재시도 3회가 **한 번의 호출 결과**로 집계된다. 실패 통계가 1/3 로 희석되어 서킷이 늦게 열린다. 어느 쪽이 맞는지는 목적에 달렸다. 일시적 네트워크 흔들림을 재시도로 흡수하고 싶으면 기본 순서가 맞고, 재시도 자체를 하류 부하로 간주하고 싶으면 반대 순서가 맞다. 명시적으로 바꾸려면 `@CircuitBreaker` 와 `@Retry` 를 다른 빈 계층에 나눴 배치해야 한다. 같은 메서드에 붙인 애노테이션의 순서는 바꿀 수 없다.

TimeLimiter 는 `CompletionStage` 반환에만 적용된다. 동기 메서드에 `@TimeLimiter` 를 붙이면 아무 일도 하지 않는다. 동기 호출의 시간 제한은 HTTP 클라이언트의 `connectTimeout`/`readTimeout` 으로 거는 것이 정석이다.

```java
@Service
public class PaymentClient {

	@CircuitBreaker(name = "paymentApi", fallbackMethod = "fallback")
	@Bulkhead(name = "paymentApi")
	@Retry(name = "paymentApi")
	public PaymentResult charge(ChargeCommand command) {
		return restClient.post()
				.uri("/charges")
				.body(command)
				.retrieve()
				.body(PaymentResult.class);
	}

	private PaymentResult fallback(ChargeCommand command, CallNotPermittedException e) {
		return PaymentResult.deferred(command.orderId());
	}

	private PaymentResult fallback(ChargeCommand command, Exception e) {
		throw new PaymentUnavailableException(e);
	}
}
```

폴백 메서드는 **예외 타입별로 오버로드**할 수 있고, 가장 구체적인 시그니처가 선택된다. 서킷이 열려 차단된 경우와 실제 호출이 실패한 경우를 다르게 처리해야 하는 경우가 대부분이므로 이 분리는 거의 항상 필요하다. 폴백 시그니처는 원본 메서드의 파라미터 + 마지막에 예외 타입이어야 하며, 어긋나면 런타임에 `NoSuchMethodException` 계열로 터진다. 컴파일 타임에 잡힐 수 없으므로 테스트가 필수다.

## 5. 관측: 무엇을 봐야 하는가

`resilience4j-micrometer` 가 붙으면 다음 지표가 나온다.

| 지표 | 의미 |
|---|---|
| `resilience4j_circuitbreaker_state` | 상태별 게이지 (해당 상태면 1) |
| `resilience4j_circuitbreaker_calls_seconds_count` | `kind` 태그로 successful/failed/ignored 구분 |
| `resilience4j_circuitbreaker_failure_rate` | 현재 창의 실패율(%) |
| `resilience4j_circuitbreaker_slow_call_rate` | 현재 창의 느린 호출 비율(%) |
| `resilience4j_bulkhead_available_concurrent_calls` | 남은 허가 수 |
| `resilience4j_retry_calls_total` | `kind` 태그로 successful_with_retry 등 구분 |

경보를 걸 대상은 상태 게이지가 아니라 **전이 빈도**다. OPEN 이 잠깐 발생하는 것은 설계된 동작이고, 5분 안에 OPEN↔HALF_OPEN 을 반복하는 플래핑이 실제 문제다.

```promql
# 10분 내 OPEN 진입 횟수
increase(resilience4j_circuitbreaker_state{state="open"}[10m]) > 3
```

게이지에 `increase` 를 직접 쓰는 것은 부정확하므로, 실무에서는 이벤트 리스너로 카운터를 따로 만드는 편이 낫다.

```java
@Component
public class CircuitBreakerEventPublisher {

	private final MeterRegistry meterRegistry;

	public CircuitBreakerEventPublisher(CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		registry.getEventPublisher()
				.onEntryAdded(event -> subscribe(event.getAddedEntry()));
		registry.getAllCircuitBreakers().forEach(this::subscribe);
	}

	private void subscribe(CircuitBreaker breaker) {
		breaker.getEventPublisher().onStateTransition(event -> {
			meterRegistry.counter("circuitbreaker.transition",
					"name", breaker.getName(),
					"from", event.getStateTransition().getFromState().name(),
					"to", event.getStateTransition().getToState().name()
			).increment();
		});
	}
}
```

`onEntryAdded` 구독이 필요한 이유는 서킷브레이커 인스턴스가 **첫 호출 시점에 지연 생성**되기 때문이다. 애플리케이션 시작 시점에 `getAllCircuitBreakers()` 만 돌면 아직 만들어지지 않은 인스턴스를 놓친다.

## 6. 임계값을 정하는 절차

숫자를 감으로 정하면 플래핑하거나 아예 열리지 않는다. 순서는 이렇다.

1. **하류의 정상 실패율을 측정한다.** 평소 0.1% 라면 임계 50% 는 너무 느슨하지 않다. 평소 5% 인 하류라면 50% 는 사실상 완전 장애만 잡는다.
2. **느린 호출 기준을 p99 보다 약간 위로 잡는다.** p99 가 800ms 라면 `slowCallDurationThreshold: 1s` 정도. 이보다 낮으면 정상 트래픽이 느린 호출로 분류된다.
3. **`waitDurationInOpenState` 를 하류 복구 시간의 하한으로 잡는다.** 하류가 롤링 재시작에 60초 걸린다면 5초 대기는 무의미한 재시도만 만든다.
4. **`permittedNumberOfCallsInHalfOpenState` 를 작게 잡는다.** HALF_OPEN 은 탐침이다. 5회면 충분하고, 50회를 허용하면 아직 아픈 하류에 다시 부하를 준다.
5. **부하 테스트로 검증한다.** 하류를 인위적으로 느리게 만들고(예: Toxiproxy 로 지연 주입) 서킷이 몇 초 만에 열리는지 측정한다.

```bash
# Toxiproxy 로 하류에 2초 지연 주입
toxiproxy-cli toxic add payment_api -t latency -a latency=2000
```

검증 시 확인할 것은 "열렸는가"가 아니라 **"열리기까지 상류가 몇 개의 스레드를 잡아먹었는가"** 다. 이 값이 워커 풀 크기에 근접하면 임계가 늦은 것이다.

## 7. 흔한 실패 모드

| 증상 | 원인 | 대응 |
|---|---|---|
| 서킷이 절대 안 열림 | `minimumNumberOfCalls` 미달, 또는 예외가 `ignoreExceptions` 에 포함 | 실제 던져지는 예외 타입 로깅 후 재설정 |
| 정상인데 자꾸 열림 | 4xx 비즈니스 예외가 실패로 집계 | `ignoreExceptions` 에 추가, 또는 `recordFailurePredicate` 로 상태코드 판별 |
| OPEN 에서 안 돌아옴 | `automaticTransition...` 꺼짐 + 호출 없음 | 옵션 활성화 |
| 폴백이 호출 안 됨 | 같은 클래스 내부 호출(self-invocation)로 프록시 우회 | 별도 빈으로 분리 |
| 5~10분 주기 플래핑 | HALF_OPEN 허용 수가 큼, 하류 복구 미완 | 허용 수 축소, 대기 시간 증가 |
| 벌크헤드 거부가 서킷을 열음 | `BulkheadFullException` 이 실패로 집계 | `ignoreExceptions` 에 추가 |

마지막 항목은 특히 흔하다. 벌크헤드가 과부하를 막았을 뿐인데 그 거부가 서킷 실패로 잡힐면, 부하 스파이크마다 서킷이 열려 복구가 지연된다. 벌크헤드와 서킷을 함께 쓸 때 반드시 확인해야 한다.

## 8. 무엇을 하지 말아야 하는가

서킷브레이커를 모든 호출에 기계적으로 붙이는 것은 좋지 않다. 판단 기준은 **폴백이 의미 있는가**다. 폴백이 "예외를 다시 던진다"뿐이라면 서킷은 실패를 빠르게 만드는 것 외에 이득이 없고, 그 정도는 타임아웃 축소로도 얻을 수 있다. 캐시된 값, 축소된 응답, 지연 처리 큐 적재처럼 진짜 대안이 있을 때 값이 난다.

또한 데이터베이스 호출에 서킷을 다는 것은 신중해야 한다. DB 가 느려지면 서킷이 열리고, 열린 동안 쓰기가 유실되거나 일관성이 깨질 수 있다. DB 는 커넥션 풀 크기와 쿼리 타임아웃으로 제어하는 것이 정석이고, 서킷은 멱등하거나 재구성 가능한 외부 호출에 쓴다.

마지막으로 서킷브레이커 상태는 **인스턴스 로컬**이다. 10대가 떠 있으면 서킷도 10개이고, 각자 독립적으로 학습한다. 이는 대체로 바람직하다(한 인스턴스의 네트워크 문제가 전체를 끊지 않는다). 다만 지표를 볼 때 "서킷이 열렸다"가 전체가 아니라 일부라는 점을 감안해야 하고, 대시보드는 인스턴스별로 쪼개 보는 편이 진단에 유리하다.

## 참고

- Resilience4j 공식 문서: https://resilience4j.readme.io/docs/getting-started
- Resilience4j — CircuitBreaker 내부 동작: https://resilience4j.readme.io/docs/circuitbreaker
- Spring Cloud Circuit Breaker Reference: https://docs.spring.io/spring-cloud-circuitbreaker/reference/
- Michael T. Nygard, *Release It!* 2nd Edition — Stability Patterns
- Micrometer Documentation — Concepts: https://docs.micrometer.io/micrometer/reference/concepts.html
