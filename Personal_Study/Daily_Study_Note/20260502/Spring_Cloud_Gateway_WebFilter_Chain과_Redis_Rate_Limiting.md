Notion 원본: https://www.notion.so/3545a06fd6d381d6b423fb3f803039d8

# Spring Cloud Gateway WebFilter Chain과 Redis 기반 Rate Limiting

> 2026-05-02 신규 주제 · 확장 대상: Spring, Redis

## 학습 목표

- Spring Cloud Gateway 의 GlobalFilter / GatewayFilter / WebFilter 차이를 안다
- Filter Chain 의 Order, Pre/Post 단계 동작과 backpressure 흐름을 익힌다
- RedisRateLimiter 의 Token Bucket 알고리즘과 Lua script 동작을 분해한다
- Reactive Streams 위에서 보안 / 인증 / Rate Limit 를 결합하는 실전 구성을 작성한다

## 1. Gateway 의 위치 — Edge 와 Service Mesh 의 차이

Spring Cloud Gateway (이하 SCG) 는 Reactor Netty 위에서 동작하는 reactive API gateway 다. 동일 영역의 비교 대상은 NGINX, Kong, Envoy, AWS API Gateway 다. 서비스 메시(Istio, Linkerd) 가 east-west 트래픽 (서비스 간) 를 다루는 동안, SCG 는 north-south (외부 ↔ 내부) 의 인증·rate limit·routing 를 다룬다. JVM 위에서 동작하기 때문에 인증 로직이나 비즈니스 컨텍스트가 필요한 변환은 SCG 에서, latency-critical L7 라우팅은 Envoy 에서 분담하는 hybrid 구조가 흔하다.

## 2. Filter 종류 3 가지

| 타입 | 적용 범위 | 등록 방법 |
| --- | --- | --- |
| `GlobalFilter` | 모든 라우트에 자동 적용 | `@Bean` 으로 GlobalFilter 구현 |
| `GatewayFilter` (factory) | 특정 라우트에만 적용 | `routes.filters.AddRequestHeader=...` |
| `WebFilter` | Spring WebFlux 의 일반 필터 | gateway 외 reactive 앱에서도 동일 |

WebFilter 는 SCG 라우팅보다 앞서 실행된다. CORS, CSRF, 로깅처럼 라우팅과 무관한 처리는 WebFilter 가 적합하다. 인증 (JWT 검증 후 user attr 주입), API 키 검사처럼 라우팅 결과에 의존하는 처리는 GatewayFilter 가 자연스럽다.

## 3. Filter Chain 의 Order 와 Pre/Post 단계

Filter 는 `Ordered.getOrder()` 또는 `@Order` 로 우선순위를 부여한다. 숫자가 작을수록 먼저 pre 단계가 실행되고, post 단계는 LIFO 로 풀린다.

```java
@Component
@Order(-1)   // 보안 검증을 다른 필터보다 먼저
public class AuthFilter implements GlobalFilter {
  @Override
  public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
    String token = ex.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (token == null) {
      ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return ex.getResponse().setComplete();
    }
    return verifier.verify(token)
      .doOnNext(p -> ex.getAttributes().put("principal", p))
      .then(chain.filter(ex))                          // pre
      .doOnSuccess(v -> log.info("after route"));      // post
  }
}
```

`chain.filter(...)` 호출 전이 pre, 그 뒤가 post 다. `then`, `doOnNext` 같은 reactor operator 의 위치가 곧 단계의 경계이므로, blocking 코드가 들어가면 backpressure 가 깨진다. `boundedElastic` scheduler 로 확실히 격리해야 한다.

## 4. RedisRateLimiter — Token Bucket 의 표준 구현

SCG 의 `RedisRateLimiter` 는 Redis 위에서 token bucket 을 운영한다. routes 정의에 KeyResolver 와 함께 등록한다.

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: orders
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 50   # tokens/sec
                redis-rate-limiter.burstCapacity: 200  # bucket size
                redis-rate-limiter.requestedTokens: 1
                key-resolver: '#{@userKeyResolver}'
```

`KeyResolver` 는 사용자/IP/팀 ID 등 식별자를 결정하는 함수다.

```java
@Bean
public KeyResolver userKeyResolver() {
  return ex -> Mono.justOrEmpty(ex.getAttribute("principal"))
    .map(p -> ((Principal) p).getName())
    .switchIfEmpty(Mono.just(ex.getRequest().getRemoteAddress().getAddress().getHostAddress()));
}
```

Resolver 가 다른 키를 반환하면 bucket 도 분리된다. 운영에서는 user → team → IP 순서로 fallback 하는 패턴이 안정적이다.

## 5. Lua Script — 원자적 token 차감

RedisRateLimiter 는 내부적으로 다음 형태의 Lua script 를 EVAL 한다 (단순화).

```lua
local key  = KEYS[1] .. '.tokens'
local ts   = KEYS[1] .. '.timestamp'

local rate     = tonumber(ARGV[1])    -- replenishRate
local capacity = tonumber(ARGV[2])    -- burstCapacity
local now      = tonumber(ARGV[3])
local req      = tonumber(ARGV[4])    -- requestedTokens

local last_tokens = tonumber(redis.call('GET', key))
if last_tokens == nil then last_tokens = capacity end

local last_refresh = tonumber(redis.call('GET', ts))
if last_refresh == nil then last_refresh = 0 end

local delta = math.max(0, now - last_refresh)
local filled = math.min(capacity, last_tokens + delta * rate)
local allowed = filled >= req

local new_tokens = allowed and (filled - req) or filled

redis.call('SETEX', key,  math.ceil(capacity / rate) * 2, new_tokens)
redis.call('SETEX', ts,   math.ceil(capacity / rate) * 2, now)

return { allowed and 1 or 0, new_tokens }
```

EVAL 은 Redis 가 단일 thread 로 실행하므로 race condition 이 없다. 응답으로 `X-RateLimit-Remaining`, `X-RateLimit-Reset` 헤더가 클라이언트에 전달된다. Redis Cluster 에서는 두 키 (`tokens`, `timestamp`) 가 같은 슬롯에 떨어지도록 hash tag `{user:42}` 를 KeyResolver 에서 사용한다.

## 6. 다층 Rate Limiting 전략

요건에 따라 여러 layer 를 둔다.

| Layer | 도구 | 목적 |
| --- | --- | --- |
| L4 / IP | NGINX `limit_req_zone`, AWS WAF | DoS 1차 방어, 의도적 공격 차단 |
| L7 / Edge | SCG RedisRateLimiter | 사용자/팀 단위 quota |
| Service 내부 | Resilience4j RateLimiter | 단일 endpoint 의 thread/timeout 보호 |
| DB | connection-pool (HikariCP) | 백엔드 자원 보호 |

각 layer 의 한계는 그 위 layer 의 burst 를 부드럽게 흡수하는 정도로 잡는다. SCG 만 높게 두고 service 내부 보호가 없으면 burst 가 그대로 backend 에 도달한다.

## 7. KeyResolver 의 보안 설계

`X-Forwarded-For` 만 신뢰하고 IP 를 KeyResolver 의 키로 쓰면 헤더 위조로 limit 회피가 가능하다. ALB / Cloudflare 뒤에서는 신뢰 가능한 trusted-proxy 로부터 받은 헤더만 사용해야 한다. SCG 는 `ForwardedHeaderTransformer` 와 `XForwardedHeaderFilter` 두 옵션이 있다.

```yaml
spring:
  cloud:
    gateway:
      forwarded:
        enabled: true
      x-forwarded:
        enabled: true
        forEnabled: true
        prefixEnabled: false
        protoEnabled: true
```

Cloudflare 의 `CF-Connecting-IP` 같은 비표준 헤더를 KeyResolver 에서 우선 사용하려면 trusted-proxy CIDR 검사 후 fallback 으로 두는 패턴이 권장된다.

## 8. 운영 모니터링 메트릭

SCG 는 Micrometer 와 통합되어 다음 메트릭을 제공한다.

| Metric | 의미 |
| --- | --- |
| `spring.cloud.gateway.requests` | endpoint 별 RPS, status |
| `spring.cloud.gateway.routes` | 라우팅 적용 횟수 |
| `gateway.requests.rate.limited` | rate limit 차단 수 |
| `redis.command.duration` | Redis EVAL latency |
| `reactor.netty.bytebuf.allocated.size` | 버퍼 누수 의심 |

차단율이 정상값(보통 0.1~0.5%) 보다 크게 튀면 KeyResolver 의 키가 너무 좁게 정의돼 정상 사용자도 차단되는 경우가 많다. user-id 만으로 quota 를 잡으면, 모바일 앱이 한 사용자당 동시 다수 요청을 보낼 때 의도치 않게 막힌다 — 이때는 user × endpoint group 단위로 키를 분리한다.

## 9. 함정과 모범 사례

1. **Blocking I/O 금지**: Filter 안에서 `restTemplate` / JDBC 호출은 Reactor 스레드를 점유한다. 인증을 외부 호출로 처리해야 한다면 WebClient 또는 Reactor R2DBC 로 전부 reactive 로 작성하거나, `boundedElastic` 으로 명시적으로 schedule on 해야 한다.

2. **Body 변환 시점**: `ModifyRequestBodyGatewayFilterFactory` 는 body 를 메모리로 buffering 한다. 큰 multipart upload 라우트에는 적용하지 않는다.

3. **Redis cluster 와 hash tag**: KeyResolver 결과에 `{}` 로 hash tag 를 명시해 두 보조 키가 동일 slot 에 떨어지게 한다.

4. **Local rate limit 대안**: Resilience4j `RateLimiter` 또는 Bucket4j 는 단일 노드 내 메모리 기반 limiter 로, latency 가 1µs 이하다. SCG 인스턴스가 1대뿐이면 Redis 보다 단순하고 빠르다.

5. **Test**: `@AutoConfigureWebTestClient` + `WebTestClient` 로 통합 테스트하고, RedisRateLimiter 의 시간 의존성은 `MutableClock` 으로 가짜 시간을 주입해 검증한다.

```java
@SpringBootTest
@AutoConfigureWebTestClient
class GatewayRateLimitTest {
  @Autowired WebTestClient client;
  @Test void blockExcessTraffic() {
    for (int i = 0; i < 60; i++) {
      client.get().uri("/api/orders").exchange();
    }
    client.get().uri("/api/orders").exchange()
      .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }
}
```

## 참고

- Spring Cloud Gateway Reference <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway/>
- RedisRateLimiter Source <https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/ratelimit/RedisRateLimiter.java>
- Resilience4j RateLimiter <https://resilience4j.readme.io/docs/ratelimiter>
- Reactor Netty 가이드 <https://projectreactor.io/docs/netty/release/reference/index.html>
- Bucket4j 문서 <https://bucket4j.com/>
