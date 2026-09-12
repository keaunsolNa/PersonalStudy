Notion 원본: https://app.notion.com/p/3d95a06fd6d381dabd0ed612f655dccc?pvs=204

# Spring Boot Micrometer Observation API와 컨텍스트 전파 및 메트릭·트레이스 상관관계

> 2026-09-12 신규 주제 · 확장 대상: OpenTelemetry 트레이스 전파 / Spring Boot 관직성

## 학습 목표

- `Timer` 와 `Tracer` 를 따로 심던 이중 계직이 만드는 불일치를 규명한다
- `Observation` / `ObservationHandler` / `ObservationRegistry` 의 실행 흘름을 추적한다
- 커스텀 `ObservationConvention` 으로 카디냄리티를 통제하는 코드를 작성한다
- 뱄동기·가상 스레드 경계에서 컨텍스트가 끊기는 지점과 복구 수단을 판단한다

## 1. 이중 계직 문제

Spring Boot 2 시절 관직성 계직은 두 갈래로 갈려 있었다. 메트릭은 Micrometer 의 `MeterRegistry` 로, 트레이스는 Spring Cloud Sleuth 의 `Tracer` 로 심었다.

```java
// Spring Boot 2 스타일 — 구조 설명용
@Service
public class PayrollService {

	private final MeterRegistry meterRegistry;
	private final Tracer tracer;

	public PayrollResult calculate(String companyCode, YearMonth period) {
		Timer.Sample sample = Timer.start(meterRegistry);
		Span span = tracer.nextSpan().name("payroll.calculate");
		try (Tracer.SpanInScope scope = tracer.withSpan(span.start())) {
			span.tag("company", companyCode);
			PayrollResult result = doCalculate(companyCode, period);
			sample.stop(meterRegistry.timer("payroll.calculate", "company", companyCode, "outcome", "success"));
			return result;
		}
		catch (PayrollException ex) {
			sample.stop(meterRegistry.timer("payroll.calculate", "company", companyCode, "outcome", "failure"));
			span.error(ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}
}
```

이 코드에는 네 가지 문제가 동시에 존재한다.

**이름과 태그의 표루** — 메트릭 이름과 스팬 이름을 사람이 맞춰 놓았을 뿐이다. 한쪽을 리네이밍하면 다른 쪽은 조용히 남는다. 태그 키도 마찬가지다. 메트릭은 `company`, 스팬은 `companyCode` 로 갈라지는 일이 흔하고, 그러면 Grafana 에서 메트릭으로 느린 회사를 찾아 트레이스로 넘어가는 exemplar 연결이 끊긴다.

**보일러통레이트 중복** — try/catch/finally 를 두 번 관리한다. 예외 경로에서 `sample.stop` 을 뱠뜨리면 메트릭이 누락되고, `span.end()` 를 뱠뜨리면 스팬이 영원히 미완결로 남아 수집기 메모리를 먹는다.

**카디냄리티 통제 부재** — `company` 는 태그로 안전하지만, 여기에 `employeeId` 를 추가하면 시계열이 직원 수만큼 폭발한다. 트레이스 태그는 카디냄리티가 높아도 괜찮고 메트릭 태그는 안 된다는 반대칭이 코드에 표현되지 않는다.

**컨텍스트 전파 이원화** — MDC(로그), 스팬 컨텍스트(트레이스), 메트릭 태그가 각기 다른 전파 메커니즘을 쓴다. 뱄동기 경계에서 하나는 살고 하나는 죽는다.

Micrometer Observation API 는 이 넷을 **하나의 계직 호출로 합친다**. Boot 3 부터 Sleuth 가 `micrometer-tracing` 으로 대지되면서 표준 경로가 되었다.

## 2. Observation 의 실행 모델

핵심 개념은 세 개다. `Observation` 은 "관직 대상 구간" 하나를 뜼하고, `ObservationRegistry` 는 어떤 핸들러가 붙어 있는지 관리하며, `ObservationHandler` 는 구간의 생애주기 이벤트를 받아 실제 신호(메트릭/스팬/로그)를 만든다.

```java
@Service
public class PayrollService {

	private final ObservationRegistry registry;

	public PayrollService(ObservationRegistry registry) {
		this.registry = registry;
	}

	public PayrollResult calculate(String companyCode, YearMonth period) {
		return Observation.createNotStarted("payroll.calculate", this.registry)
				.lowCardinalityKeyValue("company", companyCode)
				.lowCardinalityKeyValue("period.type", period.getMonthValue() == 12 ? "year-end" : "regular")
				.highCardinalityKeyValue("period", period.toString())
				.observe(() -> doCalculate(companyCode, period));
	}
}
```

`observe(Supplier)` 한 번으로 끝난다. 내부 흘름은 다음 순서다.

1. `start()` — 등록된 모든 핸들러의 `onStart(context)` 호출. 타이머 핸들러는 `Timer.Sample` 을 시작하고, 트레이싱 핸들러는 스팬을 여고, 전파 핸들러는 W3C `traceparent` 헤더를 준믄한다.
2. `openScope()` — `onScopeOpened(context)` 호출. 여기서 `ThreadLocal` 에 스팬이 올라가고 MDC 에 `traceId`/`spanId` 가 주입된다.
3. 사용자 코드 실행.
4. 예외 시 `error(throwable)` 로 `onError(context)` 가 호출된다.
5. `closeScope()` — `onScopeClosed(context)` 에서 ThreadLocal/MDC 를 정리한다.
6. `stop()` — `onStop(context)` 에서 `Timer.Sample.stop()` 과 `span.end()` 가 발생한다.

핸들러는 **컨텍스트 객체를 공유**한다. `Observation.Context` 에 `lowCardinalityKeyValue` 로 넣은 값은 메트릭 태그와 스팬 태그 **양쪽**에 반영되고, `highCardinalityKeyValue` 는 스팬 태그에만 들어간다. 이 구분이 카디냄리티 문제를 API 층에서 해결하는 지점이다. 메트릭 시계열은 low cardinality 태그의 조합 수만큼만 늘어나고, 트레이스에는 디버깅에 필요한 상세 값이 그대로 남는다.

| 축 | lowCardinalityKeyValue | highCardinalityKeyValue |
|---|---|---|
| 메트릭 태그 반영 | 반영됨 | 반영 안 됨 |
| 스팬 태그 반영 | 반영됨 | 반영됨 |
| 시계열 증가 | 값의 카디냄리티만큼 곱셈 | 영향 없음 |
| 적합한 값 | HTTP 메서드, 상태 코드 계열, 회사 코드, outcome | 사번, 요잭 ID, SQL 문, 파라미터 |

Spring Boot 는 자동 계직을 이 API 위에 올려 두었다. `RestClient`/`WebClient`/`RestTemplate` 호출, `@Scheduled` 실행, Spring MVC 서버 요잭, Spring Data Repository 호출, Kafka 리스너가 모두 `Observation` 을 발행한다. 따라서 앱 코드에서 `Observation` 을 직접 만드는 경우는 **도메인 구간**(배치 스텝, 급여 계산, 마감 처리)에 한정된다.

## 3. ObservationConvention — 이름과 태그의 단일 원천

문자열을 호출 지점에 흩물리면 다시 표루한다. `ObservationConvention` 은 이름·태그 생성 생성 생성 생성 관례를 타입으로 고정한다.

```java
public class PayrollContext extends Observation.Context {

	private final String companyCode;
	private final YearMonth period;
	private int employeeCount;

	public PayrollContext(String companyCode, YearMonth period) {
		this.companyCode = companyCode;
		this.period = period;
	}

	public String getCompanyCode() {
		return this.companyCode;
	}

	public YearMonth getPeriod() {
		return this.period;
	}

	public int getEmployeeCount() {
		return this.employeeCount;
	}

	public void setEmployeeCount(int employeeCount) {
		this.employeeCount = employeeCount;
	}
}
```

```java
public class DefaultPayrollObservationConvention implements ObservationConvention<PayrollContext> {

	@Override
	public String getName() {
		return "payroll.calculate";
	}

	@Override
	public String getContextualName(PayrollContext context) {
		// 스팬 이름 — 메트릭 이름과 달리 카디냄리티가 조금 높아도 된다
		return "payroll calculate " + context.getCompanyCode();
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(PayrollContext context) {
		return KeyValues.of(
				"company", context.getCompanyCode(),
				"period.type", context.getPeriod().getMonthValue() == 12 ? "year-end" : "regular",
				"size.bucket", bucketOf(context.getEmployeeCount()));
	}

	@Override
	public KeyValues getHighCardinalityKeyValues(PayrollContext context) {
		return KeyValues.of(
				"period", context.getPeriod().toString(),
				"employee.count", String.valueOf(context.getEmployeeCount()));
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof PayrollContext;
	}

	private String bucketOf(int count) {
		if (count < 100) {
			return "small";
		}
		if (count < 1000) {
			return "medium";
		}
		return "large";
	}
}
```

`size.bucket` 이 중요한 패턴이다. 직원 수는 연속값이므로 태그로 쓰면 시계열이 폭발하지만, 구간으로 이산화하면 3개 값으로 끝난다. "대규모 회사에서만 느려지는가"라는 질문에 답할 수 있고 카디냄리티는 통제된다. 실무 계직에서 가장 자주 필요한 변환이다.

호출부는 이렇게 된다.

```java
public PayrollResult calculate(String companyCode, YearMonth period) {
	PayrollContext context = new PayrollContext(companyCode, period);
	return Observation.createNotStarted(this.convention, () -> context, this.registry)
			.observe(() -> {
				List<Employee> targets = this.employeeRepository.findActive(companyCode, period);
				context.setEmployeeCount(targets.size()); // stop 시점에 태그로 반영된다
				return doCalculate(targets, period);
			});
}
```

컨텍스트는 `stop()` 시점에 컨벤션을 통해 태그로 변환되므로, 구간 실행 중에 알게 된 값을 나중에 채워도 반영된다. 처리 건수, 재시도 횜수, 캐시 허트 여부처럼 시작 시점에 모르는 값을 태그로 남기는 표준 방법이다.

`@Observed` 어노테이션은 AOP 기반 축약형이다.

```java
@Observed(name = "payroll.close", contextualName = "payroll-close",
		lowCardinalityKeyValues = {"module", "payroll"})
public void closeMonth(String companyCode, YearMonth period) {
	// ...
}
```

사용하려면 `ObservedAspect` 번을 등록해야 한다.

```java
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

	@Bean
	public ObservedAspect observedAspect(ObservationRegistry registry) {
		return new ObservedAspect(registry);
	}

	@Bean
	public ObservationRegistryCustomizer<ObservationRegistry> noActuatorEndpoints() {
		return (registry) -> registry.observationConfig()
				.observationPredicate((name, context) -> !name.startsWith("actuator"));
	}
}
```

`observationPredicate` 로 특정 구간을 아예 발행하지 않게 막고, `ObservationFilter` 로 모든 관직에 공통 태그(배포 환경, 인스턴스 ID)를 덼붙인다. 프록시 기반이므로 `@Observed` 는 같은 번 내부 호출(self-invocation)에서는 동작하지 않는다 — 인테페이스 경계에만 붙인다.

## 4. 컨텍스트 전파 — 끊기는 지점들

`ThreadLocal` 기반 전파는 스레드가 바뀌면 끊긴다. Micrometer 는 `context-propagation` 라이밌러리로 이 문제를 통일했다. 핵심 타입은 `ContextSnapshot` 이다.

```java
@Service
public class AsyncPayrollService {

	private final ObservationRegistry registry;
	private final ExecutorService executor;

	public CompletableFuture<PayrollResult> calculateAsync(String companyCode, YearMonth period) {
		Observation parent = Observation.start("payroll.calculate.async", this.registry);
		// 현재 스레드의 등록된 ThreadLocal 값을 스냅샷으로 캡처
		ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();

		return CompletableFuture.supplyAsync(() -> {
			// 작업 스레드에서 스냅샷을 복원 — 스팬/MDC 가 되살아난다
			try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
				return doCalculate(companyCode, period);
			}
		}, this.executor).whenComplete((result, ex) -> {
			if (ex != null) {
				parent.error(ex);
			}
			parent.stop();
		});
	}
}
```

매 호출마다 스냅샷을 뜨는 건 번거로우므로 실무에서는 Executor 를 래핑한다.

```java
@Bean
public ExecutorService payrollExecutor() {
	ExecutorService delegate = Executors.newFixedThreadPool(8);
	return ContextExecutorService.wrap(delegate,
			() -> ContextSnapshotFactory.builder().build().captureAll());
}
```

Spring Boot 는 `@Async` 용 `TaskExecutor` 에 대해 컨텍스트 전파 동작을 자동 적용할 수 있다. `spring.task.execution.*` 로 만든 기본 executor 는 Boot 3.2 이상에서 관직 컨텍스트를 전파한다. 반면 **직접 `new ThreadPoolTaskExecutor()` 로 만든 번은 전파되지 않는다** — 계직이 조용히 끊기는 가장 흔한 원인이다.

Reactor 경로는 다른 메커니즘을 쓴다. `ThreadLocal` 이 아니라 `Context` 가 구독 체인을 따라 흘린다.

```java
public Mono<PayrollResult> calculateReactive(String companyCode, YearMonth period) {
	return Mono.fromCallable(() -> doCalculate(companyCode, period))
			.subscribeOn(Schedulers.boundedElastic())
			.name("payroll.calculate")
			.tag("company", companyCode)
			.tap(Micrometer.observation(this.registry));
}
```

`tap(Micrometer.observation(registry))` 가 구독 시 관직을 시작하고 완료/에러 시 종료한다. 자동 컨텍스트 전파를 켜려면 어플리케이션 초기화 시 한 번 훅을 등록한다.

```java
@PostConstruct
void enableAutomaticContextPropagation() {
	Hooks.enableAutomaticContextPropagation();
}
```

이 훅이 없으면 `Mono` 안족에서 로그를 남길 때 MDC 에 traceId 가 버어 있다. 로그와 트레이스 상관관계가 끊기는 지점이 정확히 여기다.

가상 스레드는 `ThreadLocal` 을 지원하므로 전파 자체는 동작한다. 다만 가상 스레드는 매 요잭마다 새로 생성되므로 `ThreadLocal` 캐싱 전략(스레드당 재사용 객체 풀 등)이 무의미해지고, `ScopedValue` 로 옮기는 것이 방향이다. `spring.threads.virtual.enabled=true` 로 켠 경우 요잭 스코프 계직은 정상 동작하지만, 커스텀 `ThreadLocal` 에 의존하는 계직 코드가 있다면 점검이 필요하다.

## 5. 메트릭·트레이스·로그 상관관계 구성

세 신호를 실제로 연결하려면 설정이 맞아야 한다.

```yaml
management:
  observations:
    key-values:
      application: payroll-api
      deployment.env: prod
  tracing:
    sampling:
      probability: 0.1
    propagation:
      type: w3c
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        payroll.calculate: true
      slo:
        payroll.calculate: 200ms,1s,5s,30s
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus

logging:
  pattern:
    level: "%5p [payroll-api,%X{traceId:-},%X{spanId:-}]"
```

세 가지가 핵심이다.

**`percentiles-histogram: true`** — 하스토그램 버킷을 발행해야 Prometheus 에서 `histogram_quantile` 로 분위수를 **집계 가능하게** 계산할 수 있다. `percentiles` 옵션(클라이언트 산 계산)은 인스턴스별 분위수를 내므로 여러 인스턴스를 합산할 수 없다. 반면 하스토그램은 버킷 수만큼 시계열이 늘어나므로 `slo` 로 관심 경계만 지정해 버킷을 제한한다.

**exemplar** — 하스토그램 버킷에 그 버킷을 만든 대표 요잭의 traceId 를 붙이는 기능이다. Micrometer 는 Prometheus 레지스트리와 tracing 이 함께 있으면 자동으로 exemplar 를 붙이므로, Grafana 에서 p99 그래프의 점을 클릭해 해당 트레이스로 직행할 수 있다. 이것이 통합 계직의 실질적 보상이다. 태그 이름이 갈렸던 이중 계직에서는 불가능했다.

**MDC 패턴** — `%X{traceId:-}` 로 로그에 traceId 를 박아 로그 검색에서 트레이스로 이동한다. 트레이싱 인식 핸들러가 스코프 열릴 때 MDC 를 채운다.

샘플링 확률에 주의한다. `probability: 0.1` 은 트레이스만 10% 로 줄이고 메트릭은 100% 집계된다 — 이것이 올바른 조합이다. 메트릭은 저렴하므로 전수 집계하고, 트레이스는 비싸므로 샘플링한다. 다만 exemplar 는 샘플된 트레이스에서만 나오므로, 느린 요잭을 항상 잡으려면 tail-based sampling 을 Collector 층에서 구성한다.

## 6. 테스트 — 계직도 검증 대상이다

계직 코드는 프로덕션에서만 실행되는 코드가 되기 쉽다. `micrometer-observation-test` 가 검증 DSL 을 제공한다.

```java
class PayrollServiceObservationTests {

	private final TestObservationRegistry registry = TestObservationRegistry.create();

	@Test
	void recordsCompanyAndSizeBucketTags() {
		PayrollService service = new PayrollService(this.registry,
				new DefaultPayrollObservationConvention(), stubRepository(250));

		service.calculate("IGIS", YearMonth.of(2026, 9));

		TestObservationRegistryAssert.assertThat(this.registry)
				.hasObservationWithNameEqualTo("payroll.calculate")
				.that()
				.hasBeenStarted()
				.hasBeenStopped()
				.hasLowCardinalityKeyValue("company", "IGIS")
				.hasLowCardinalityKeyValue("period.type", "regular")
				.hasLowCardinalityKeyValue("size.bucket", "medium")
				.hasHighCardinalityKeyValue("employee.count", "250");
	}

	@Test
	void recordsErrorAndClosesObservationOnFailure() {
		PayrollService service = new PayrollService(this.registry,
				new DefaultPayrollObservationConvention(), failingRepository());

		assertThatThrownBy(() -> service.calculate("IGIS", YearMonth.of(2026, 9)))
				.isInstanceOf(PayrollException.class);

		TestObservationRegistryAssert.assertThat(this.registry)
				.hasObservationWithNameEqualTo("payroll.calculate")
				.that()
				.hasBeenStopped()
				.thenError()
				.isInstanceOf(PayrollException.class);
	}
}
```

두 번째 테스트가 특하 가지 있다. `observe(Supplier)` 를 쓰지 않고 수동으로 `start`/`stop` 을 호출하는 코드에서 예외 경로의 `stop()` 누락은 프로덕션에서 스팬 누수로만 드러난다. 테스트로 모마 박아 두면 리팩토링 시 회귀를 잡는다.

메트릭 값 자체는 `SimpleMeterRegistry` 로 검증한다.

```java
@Test
void recordsTimer() {
	SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	ObservationRegistry observationRegistry = ObservationRegistry.create();
	observationRegistry.observationConfig()
			.observationHandler(new DefaultMeterObservationHandler(meterRegistry));

	new PayrollService(observationRegistry, new DefaultPayrollObservationConvention(), stubRepository(50))
			.calculate("IGIS", YearMonth.of(2026, 9));

	Timer timer = meterRegistry.get("payroll.calculate")
			.tag("company", "IGIS")
			.tag("size.bucket", "small")
			.timer();
	assertThat(timer.count()).isEqualTo(1L);
	assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isPositive();
}
```

## 7. 도입 순서와 비용

기존 프로젝트에 넣을 때는 순서가 중요하다. 먼저 **자동 계직만 켜고 관직한다** — Boot 3 의 HTTP 서버/클라이언트, Repository, Scheduled 계직만으로 대개 병목의 80% 가 보인다. 도메인 구간 계직은 자동 계직이 답하지 못하는 질문이 생긴 뒤에 추가한다. "어느 엔드포인트가 느린가"는 자동 계직이 답하고, "급여 계산의 어느 단계가 느린가"부터가 커스텀 `Observation` 의 역할이다.

비용은 세 곳에서 발생한다. **메트릭 저장 비용**은 태그 카디냄리티의 곱이다. `company`(50) × `period.type`(2) × `size.bucket`(3) × `outcome`(2) = 600 시계열이고, 하스토그램 버킷 20개를 곱하면 12,000 이 된다. 한 지표로는 괜찮지만 이런 지표 20개면 24만 시계열이다 — Prometheus 단일 인스턴스의 현실적 한계에 접근한다. **트레이스 전속 비용**은 샘플링으로 통제한다. **CPU 오버헤드**는 관직당 마이크로초 단위로, 초당 수만 관직 규모가 아니면 무시 가능하다. 다만 타이트 루프 안에서 관직을 만들면 이야기가 달라지므로, 루프 밖에 구간을 두고 안에서는 카운터만 증가시킨다.

Sleuth 에서 올라오는 마이그레이션은 API 가 대부분 바뀐다. `@NewSpan`/`@SpanTag` 는 `@Observed` 로, `Tracer.nextSpan()` 은 `Observation.createNotStarted()` 로, `spring.sleuth.*` 는 `management.tracing.*` 로 옮긴다. 프로파티 이름부터 전부 다르므로 모듈 단위로 옮기고 두 계직이 공존하는 기간을 짧게 유지한다.

## 참고

- Micrometer Observation 공식 문서: https://docs.micrometer.io/micrometer/reference/observation.html
- Micrometer Context Propagation: https://docs.micrometer.io/context-propagation/reference/
- Spring Boot Reference — Observability: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Micrometer Tracing 문서: https://docs.micrometer.io/tracing/reference/
- W3C Trace Context 권고안: https://www.w3.org/TR/trace-context/
- OpenMetrics 명세 — exemplars: https://github.com/OpenObservability/OpenMetrics/blob/main/specification/OpenMetrics.md
