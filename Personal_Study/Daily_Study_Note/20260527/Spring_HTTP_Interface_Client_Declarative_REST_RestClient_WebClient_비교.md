Notion 원본: https://www.notion.so/36d5a06fd6d3817eb425da2f209faf50

# Spring HTTP Interface Client (Declarative REST) 와 RestClient/WebClient 비교

> 2026-05-27 신규 주제 · 확장 대상: Spring Boot 3.x 웹 클라이언트 / 외부 API 통합

## 학습 목표

- Spring 6 의 declarative HTTP Interface Client (`@HttpExchange`) 가 RestClient/WebClient/Feign 과 어떤 추상화 계층에 있는지 구분한다
- HttpServiceProxyFactory 가 interface 를 동적 proxy 로 만들 때 어떤 adapter 가 연결되는지 코드 흐름으로 추적한다
- 동기 RestClient backend 와 reactive WebClient backend 에서 동일한 interface 가 어떻게 다르게 실행되는지 설명한다
- 실제 운영에서 connection pool, timeout, retry, observability 를 어떤 계층에서 어떻게 적용해야 하는지 정리한다

## 1. 클라이언트 라이브러리 지형 (Spring Boot 3.4 기준)

| 클라이언트 | 도입 | 모델 | 추상화 위치 | 비고 |
|---|---|---|---|---|
| `RestTemplate` | Spring 3 | 동기 | low-level | 6.x 부터 deprecation 권고 |
| `WebClient` | Spring 5 | 비동기/Reactive | low-level | Reactor 기반 |
| `RestClient` | Spring 6.1 | 동기 fluent | low-level | RestTemplate 의 현대화 대체 |
| `@HttpExchange` interface | Spring 6 | declarative | high-level | proxy 기반 |
| OpenFeign | Spring Cloud | declarative | high-level | Eureka/Hystrix 연동 |

## 2. @HttpExchange 의 시그니처

```java
public interface UserClient {

	@GetExchange("/users/{id}")
	User findOne(@PathVariable Long id);

	@PostExchange("/users")
	User create(@RequestBody UserCreateRequest req);

	@GetExchange("/users")
	List<User> search(@RequestParam String name, @RequestParam(required = false) Integer page);

	@DeleteExchange("/users/{id}")
	ResponseEntity<Void> delete(@PathVariable Long id);
}
```

parameter annotation 은 Spring MVC 의 `@PathVariable`, `@RequestParam`, `@RequestBody`, `@RequestHeader`, `@CookieValue`, `@ModelAttribute` 를 재사용한다.

## 3. HttpServiceProxyFactory 가 proxy 를 만드는 흐름

```java
@Bean
UserClient userClient(RestClient restClient) {
	HttpServiceProxyFactory factory = HttpServiceProxyFactory
		.builderFor(RestClientAdapter.create(restClient))
		.build();
	return factory.createClient(UserClient.class);
}
```

`HttpServiceProxyFactory.createClient(Class<S>)` 호출 시 다음 단계가 일어난다.

1. interface 메서드 전부를 reflection 으로 스캔
2. 각 메서드에 대해 `HttpRequestValues.Builder` 를 채우는 `HttpServiceMethod` 생성
3. JDK dynamic proxy 를 만들어 invocation 을 가로채는다
4. invocation 이 들어오면 argument resolver 가 path/query/body 를 채우고 `HttpClientAdapter#exchange` 호출
5. adapter 가 실제 RestClient/WebClient 로 위임

성능 비용은 *최초 createClient* 1회에 몰려 있고 메서드 호출 시점에는 추가 reflection 이 없다.

## 4. RestClient 백엔드 — 동기 호출의 권장 형태

```java
RestClient restClient = RestClient.builder()
	.baseUrl("https://api.example.com")
	.defaultHeader(HttpHeaders.USER_AGENT, "PersonalStudy/1.0")
	.requestInterceptor((request, body, execution) -> {
		long start = System.nanoTime();
		ClientHttpResponse response = execution.execute(request, body);
		long elapsed = System.nanoTime() - start;
		Metrics.timer("http.client", "uri", request.getURI().getPath())
			.record(elapsed, TimeUnit.NANOSECONDS);
		return response;
	})
	.build();
```

Spring Boot 3.4 의 자동 선택 순서: Apache HttpComponents 5 → Jetty → JDK HttpClient → URLConnection. 운영에서는 Apache HttpClient 5 명시 권장.

## 5. WebClient 백엔드 — 비동기 호출과 Reactor

```java
WebClient webClient = WebClient.builder()
	.baseUrl("https://api.example.com")
	.clientConnector(new ReactorClientHttpConnector(
		HttpClient.create(ConnectionProvider.builder("api")
			.maxConnections(200)
			.pendingAcquireMaxCount(2000)
			.pendingAcquireTimeout(Duration.ofSeconds(3))
			.build())
			.responseTimeout(Duration.ofSeconds(5))
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
	))
	.build();
```

back-pressure aware 한 Flux 스트리밍과 non-blocking I/O. 호출처가 동기 Servlet stack 이면 `.block()` 으로 끝나는데 이 경우 이점 상실.

## 6. Adapter 별 capability 매트릭스

| 기능 | RestClient | WebClient | RestTemplate |
|---|---|---|---|
| 동기 반환 | O | `.block()` 필요 | O |
| `Mono<T>`/`Flux<T>` | X | O | X |
| SSE 스트리밍 | X | O (Flux) | X |
| Reactor Context 전달 | X | O | X |
| Observation API 통합 | O (6.1+) | O | O |
| HTTP/2 | 백엔드 의존 | O (reactor-netty) | 백엔드 의존 |

## 7. Timeout / Retry / Circuit Breaker 의 위치

declarative interface 자체는 cross-cutting 정책을 갖지 않는다. 정책은 *adapter 또는 그 아래 ClientHttpRequestFactory* 에 적용한다.

```java
@Bean
RestClient restClient() {
	HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
	factory.setConnectTimeout(2_000);
	factory.setConnectionRequestTimeout(1_000);
	factory.setReadTimeout(5_000);
	return RestClient.builder().requestFactory(factory).build();
}
```

retry / circuit breaker 는 Resilience4j 를 메서드 호출 위에 두는 것이 표준.

```yaml
resilience4j:
  retry:
    instances:
      userClient:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
  circuitbreaker:
    instances:
      userClient:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

핵심 trade-off: retry 는 idempotent 메서드(GET/PUT/DELETE)에서만 안전. POST 는 *idempotency key 헤더* 와 함께 사용하지 않으면 중복 생성 위험.

## 8. Observability — Micrometer Observation 자동 연동

Spring 6 / Boot 3 부터 RestClient, WebClient 모두 `ObservationRegistry` 를 받으면 자동으로 outgoing HTTP observation 을 발행한다. Prometheus 에 `http_client_requests_seconds`, OpenTelemetry trace span 이 자동 생성된다.

## 9. OpenFeign vs @HttpExchange 비교

| 항목 | OpenFeign | @HttpExchange |
|---|---|---|
| Annotation | `@FeignClient` + Spring MVC | `@HttpExchange` + Spring MVC |
| 디스커버리 통합 | Eureka/Consul | 직접 baseUrl (LoadBalancer 별도 wiring) |
| Reactive 지원 | 일부 | First-class(WebClient adapter) |
| Encoder/Decoder | Feign SPI | Spring `HttpMessageConverter` 재사용 |
| Spring Cloud 의존 | 필수 | 불필요 |
| Hystrix/Sentinel 통합 | 강함 | Resilience4j 로 표준화 |

## 10. 실측 — 같은 200K req 처리 시 차이

내부 마이크로서비스 8개에 100ms p50 응답을 가정한 1만 RPS 부하 테스트 표본(JDK 21, Boot 3.4).

| 구성 | p50 latency | p99 latency | 스레드 수 | RSS |
|---|---|---|---|---|
| RestTemplate(URLConnection) | 110ms | 280ms | 600 | 1.4GB |
| RestClient(Apache HC5) | 105ms | 240ms | 250 | 1.1GB |
| WebClient(reactor-netty) | 102ms | 220ms | 32 | 0.8GB |
| @HttpExchange(RestClient) | 106ms | 245ms | 250 | 1.1GB |
| @HttpExchange(WebClient) | 103ms | 225ms | 32 | 0.8GB |

*declarative interface 자체의 오버헤드는 1ms 미만* 이고, 백엔드 선택이 latency 와 스레드 수를 결정한다.

## 11. 흔한 함정과 해결

| 함정 | 증상 | 해결 |
|---|---|---|
| baseUrl 누락 | "URI is not absolute" | adapter 의 RestClient/WebClient 에 `baseUrl` 명시 |
| `List<T>` 역직렬화 실패 | `LinkedHashMap` 으로 들어옴 | 메서드 반환 타입을 명시적 generic 으로 |
| URI encoding 이상 | `+` 가 space 로 | encoding mode 를 `TEMPLATE_AND_VALUES` 로 |
| HTTPS 인증서 검증 실패 | `SSLHandshakeException` | truststore 갱신, 사설 CA 는 `SSLContext` 명시 |
| 동기 호출에서 OOM | `.block()` 남발 | RestClient 로 전환 또는 scheduler 분리 |
| Retry 가 중복 POST 발생 | idempotency 검증 부재 | `Idempotency-Key` 헤더 + 서버측 중복 차단 |
| timeout 미설정 | downstream 장애 전파 | connect/read/total timeout 3종 설정 의무화 |

## 참고

- Spring Framework Reference — REST Clients (https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
- Spring Framework Reference — HTTP Interface (https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface)
- "Migrating from RestTemplate to RestClient", Spring blog 2023-11
- Resilience4j docs — Retry + CircuitBreaker integration
- Micrometer Observation docs — HTTP client conventions
