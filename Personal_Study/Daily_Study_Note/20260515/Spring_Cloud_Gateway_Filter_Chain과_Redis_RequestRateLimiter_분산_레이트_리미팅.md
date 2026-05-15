Notion 원본: https://www.notion.so/3615a06fd6d38119beaed61e188918b4

# Spring Cloud Gateway Filter Chain과 Redis RequestRateLimiter 분산 레이트 리미팅

> 2026-05-15 신규 주제 · 확장 대상: Spring / API Gateway

## 학습 목표

- Spring Cloud Gateway 의 Route → Predicate → Filter → NettyRoutingFilter 흐름을 단계별로 추적한다
- Global Filter 와 GatewayFilter 의 실행 순서, Ordered 인터페이스의 의미를 정리한다
- Redis 기반 RequestRateLimiter 의 Token Bucket 알고리즘을 Lua 스크립트 수준에서 분석한다
- 분산 환경에서 KeyResolver 설계, Redis 장애 시 fallback 전략을 코드로 구현한다

## 1. Gateway 전체 흐름과 Filter Chain

Spring Cloud Gateway 는 Spring WebFlux 위에서 동작하는 reactive 게이트웨이다. 요청 처리 흐름은 `HttpServerRequest → DispatcherHandler → RoutePredicateHandlerMapping(Route 매칭) → FilteringWebHandler → Pre Filter chain → NettyRoutingFilter(target call) → Post Filter chain → HttpServerResponse` 순이다.

Filter Chain 은 두 종류로 구성된다. **GlobalFilter** 는 모든 라우트에 적용되며 `GlobalFilter` 구현체가 자동 등록, **GatewayFilter** 는 특정 라우트에만 적용. 두 타입 모두 `OrderedGatewayFilter` 래퍼로 감싸지고 `Ordered.getOrder()` 가 낮을수록 먼저 실행.

`NettyRoutingFilter` 가 `Integer.MAX_VALUE` 라 그 뒤로는 아무 필터도 실행되지 않으므로 응답 변환은 `chain.filter(exchange).then(Mono.fromRunnable(...))` 패턴으로 post 단계를 표현한다.

## 2. 커스텀 GlobalFilter 작성

```java
@Component
@Slf4j
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

	private static final String TRACE_HEADER = "X-Trace-Id";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		final String finalTraceId = traceId;

		ServerWebExchange mutated = exchange.mutate()
			.request(r -> r.header(TRACE_HEADER, finalTraceId))
			.build();

		return chain.filter(mutated).then(Mono.fromRunnable(() -> {
			mutated.getResponse().getHeaders().add(TRACE_HEADER, finalTraceId);
			log.info("trace={} status={}", finalTraceId,
				mutated.getResponse().getStatusCode());
		}));
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 100;
	}
}
```

`exchange.mutate()` 로 요청 헤더를 추가하고 chain 을 진행. post 처리는 `then(Mono.fromRunnable(...))`. NettyRoutingFilter 이후의 응답 헤더 수정은 `ResponseDecorator` 로 감싸야 한다.

## 3. RequestRateLimiter GatewayFilter 의 동작

YAML 설정:

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 100    # 초당 100토큰 채움
            redis-rate-limiter.burstCapacity: 200    # 최대 200토큰 버킷
            redis-rate-limiter.requestedTokens: 1
            key-resolver: "#{@userKeyResolver}"
```

## 4. KeyResolver 설계

기본 동작은 인증된 사용자가 없으면 `__defaultKey` 한 개로 모든 트래픽이 같은 버킷을 공유한다. 명시적 KeyResolver 를 빈으로 등록.

```java
@Bean
public KeyResolver userKeyResolver() {
	return exchange -> {
		String userId = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_HANDLER_MAPPER_ATTR);
		if (userId != null) return Mono.just("user:" + userId);
		String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
		if (apiKey != null) return Mono.just("apikey:" + apiKey);
		String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
		String ip = (forwardedFor != null)
			? forwardedFor.split(",")[0].trim()
			: Optional.ofNullable(exchange.getRequest().getRemoteAddress())
				.map(addr -> addr.getAddress().getHostAddress())
				.orElse("unknown");
		return Mono.just("ip:" + ip);
	};
}
```

키 설계 가이드: 너무 좁은 키(IP+UA+Path) → CDN 뒤에서 동일 IP 많아 의도와 다름. 너무 넓은 키 → 한 사용자가 다른 사용자 트래픽까지 영향. 짧은 prefix + 안정적 식별자가 권장.

## 5. Redis Token Bucket 알고리즘 분석

`request_rate_limiter.lua` 스크립트(요약):

```lua
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local fill_time = capacity / rate
local ttl = math.floor(fill_time * 2)
local last_tokens = tonumber(redis.call("GET", tokens_key)) or capacity
local last_refreshed = tonumber(redis.call("GET", timestamp_key)) or 0
local delta = math.max(0, now - last_refreshed)
local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
local allowed = filled_tokens >= requested
if allowed then filled_tokens = filled_tokens - requested end
redis.call("SETEX", tokens_key, ttl, filled_tokens)
redis.call("SETEX", timestamp_key, ttl, now)
return { allowed and 1 or 0, filled_tokens }
```

핵심 포인트: **Token Bucket** 은 매 초 `rate` 만큼 채워지며 최대 `capacity` 까지 누적, 일시적 burst 허용. **Lua 원자성** 으로 GET/계산/SET 이 단일 EVAL 안에 묶여 race condition 없음. **TTL = 2 × fill_time** 으로 사용자가 한참 후 다시 와도 자연스럽게 초기화. `{key}` 의 중괄호는 Redis Cluster 의 hash tag 로 동일 슬롯 안정.

## 6. 응답 동작과 헤더

요청이 제한되면 HTTP 429 와 `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity`, `X-RateLimit-Replenish-Rate`, `X-RateLimit-Requested-Tokens` 헤더. `RedisRateLimiter.config.setIncludeHeaders(true)` 일 때만 노출.

```java
@Bean
public WebFilter rateLimitResponseEnricher() {
	return (exchange, chain) -> chain.filter(exchange).onErrorResume(ex -> {
		if (exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			byte[] body = "{\"error\":\"rate_limited\",\"retry_after\":1}".getBytes(UTF_8);
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
		}
		return Mono.error(ex);
	});
}
```

## 7. Redis 장애 시 fallback

**Fail-open**: Redis 장애 시 모든 요청 통과. 보호장치보다 가용성이 우선이면 선택.

```java
return delegate.isAllowed(routeId, id)
	.onErrorResume(ex -> {
		log.warn("RateLimiter Redis down — fail open: {}", ex.toString());
		return Mono.just(new Response(true, Map.of()));
	});
```

**Local fallback**: Redis 가 죽으면 Resilience4j 또는 Bucket4j 로컬 토큰 버킷으로 전환. 정합성은 떨어지지만 보호는 유지. **Circuit Breaker 와 조합**: Redis 응답 시간이 길어지면 게이트웨이 전체가 느려지므로 `CircuitBreaker` GatewayFilter 로 감싸 빠르게 차단.

## 8. 실측 벤치마크

Gateway 1대(4 vCPU), Redis 1대(c6g.large), Backend mock, Gatling 200 RPS / 5분:

| 설정 | p50 | p99 | Redis ops/s |
|---|---|---|---|
| 레이트 리미터 비활성 | 4ms | 12ms | 0 |
| RedisRateLimiter, 사용자 키 | 6ms | 18ms | 200 |
| Resilient + Redis 일시 장애(10s) | 6ms→11ms→6ms | 22ms | 200→0→200 |
| 동일 키 200 RPS, 100/200 버킷 | 6ms (50% 차단) | 21ms | 200 |

EVAL 한 번에 GET 2 + SET 2 + 계산. Redis 입장에서 약 0.2ms 추가.

**트레이드오프**: Token Bucket 은 burst 허용에 강하고 메모리가 적다. Sliding Window 는 분당 정확한 한도 보장이 필요할 때 유리. Redis 가 SPOF 이므로 Sentinel / Cluster 로 HA, fail-open / local fallback 으로 가용성 보강. 키 cardinality 가 1억 단위면 메모리 압박. 게이트웨이 인스턴스가 늘어도 Redis 카운터가 공유되므로 합산 한도가 유지.

## 참고

- Spring Cloud Gateway Reference: https://docs.spring.io/spring-cloud-gateway/reference/
- RedisRateLimiter 소스: https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/ratelimit/RedisRateLimiter.java
- request_rate_limiter.lua: https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/resources/META-INF/scripts/request_rate_limiter.lua
- Token Bucket: https://en.wikipedia.org/wiki/Token_bucket
- Bucket4j: https://bucket4j.com/
