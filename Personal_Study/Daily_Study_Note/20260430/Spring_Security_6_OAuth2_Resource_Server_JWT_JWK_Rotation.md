Notion 원본: https://app.notion.com/p/3525a06fd6d3815d80f5cab49cf208b6

# Spring Security 6 OAuth2 Resource Server JWT 검증과 JWK Rotation

> 2026-04-30 신규 주제 · 확장 대상: Spring Security · OAuth2

## 학습 목표

- `OAuth2ResourceServerConfigurer` 의 JWT 디코더 체인 동작 추적
- JWK Set 캐시 만료와 키 회전(rotation) 시 401 폭주를 막는 설계
- 멀티 테넌트 환경에서 issuer 별 디코더 분기 구성
- 서명 검증·claim 검증·권한 매핑의 책임 분리

## 1. Resource Server 와 Authorization Server 분리

OAuth2 표준은 두 역할을 분리한다.

- **Authorization Server (AS)**: 토큰 발급 + 키 관리. Keycloak / Auth0 / 자체 구현.
- **Resource Server (RS)**: AS 가 발급한 access token 을 검증해 보호된 자원을 제공. 보통 백엔드 API.

Spring Security 6 의 `spring-boot-starter-oauth2-resource-server` 는 RS 역할만 담당한다. AS는 외부 인프라를 쓰는 게 권장이며, Spring Authorization Server 는 별도 프로젝트로 분리됐다.

## 2. 최소 구성 — issuer-uri 한 줄

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/app
```

이 한 줄이 부팅 시 다음을 자동 수행한다.

1. `https://auth.example.com/realms/app/.well-known/openid-configuration` 호출 → metadata 획득.
2. metadata 에서 `jwks_uri` 추출.
3. 그 URL 의 JWKS(JSON Web Key Set)를 가져와 `JwtDecoder` 로 등록.

내부적으로 `NimbusJwtDecoder` + `JWKSourceBuilder` 가 구성되며, JWK 는 5분 TTL 의 메모리 캐시를 갖는다.

## 3. SecurityFilterChain 명시 구성

자동 구성에 의존하지 않고 명시적으로 풀어 쓰는 게 운영에 유리하다.

```java
@Configuration
@EnableWebSecurity
public class ResourceServerSecurityConfig {

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.security.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(jwkSetUri)
            .cache(Duration.ofMinutes(10))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://auth.example.com/realms/app"));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        scopes.setAuthorityPrefix("SCOPE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
```

`SessionCreationPolicy.STATELESS` 는 RS 의 정석이다. 토큰 검증만 하고 서버 세션은 만들지 않는다.

## 4. JWK Set 캐시와 회전(rotation)

JWT 의 헤더 `kid`(Key ID)는 어떤 공개키로 서명을 검증해야 하는지 알려준다. AS는 보안을 위해 정기적으로 키를 회전한다(예: 매 90일 새 키 추가, 30일 후 옛 키 폐기).

캐시가 회전을 못 따라잡으면 모든 RS가 동시에 401 을 던지는 사고가 난다. 흔한 시나리오:

1. AS 가 새 키 `kid=k2` 를 생성, JWKS endpoint 가 `[k1, k2]` 반환.
2. AS 가 `k2` 로 새 토큰 발급 시작.
3. RS 의 캐시는 아직 `[k1]` 만 보유.
4. RS 가 새 토큰을 받아 → `kid=k2` 가 캐시에 없음 → 검증 실패 → 401.

Nimbus 라이브러리는 이런 상황에 대응해 **"unknown kid 발견 시 캐시 강제 갱신"** 동작을 제공한다. `JWKSourceBuilder.refreshAheadCache()` 를 켜면 만료 직전에 비동기로 미리 갱신한다.

```java
JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
    .create(new URL(jwkSetUri))
    .cache(Duration.ofMinutes(10).toMillis(), Duration.ofSeconds(15).toMillis())
    .refreshAheadCache(true)
    .rateLimited(false)
    .retrying(true)
    .build();

DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));

NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
```

운영 가이드:

- **TTL 너무 길게(1시간+)**: 회전 직후 401 가능성 ↑
- **TTL 너무 짧게(30초 이하)**: AS 의 JWKS endpoint 에 부하 집중
- 권장: 5~15분 + refreshAhead

## 5. Custom JWT Validator — claim 검증

서명만 검증하면 끝이 아니다. 만료(`exp`), 발급자(`iss`), 사용처(`aud`), 발급 시점(`iat`) 등 claim 검증이 필요하다.

```java
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
        OAuth2ErrorCodes.INVALID_TOKEN, "Required audience missing", null);

    private final String requiredAudience;

    public AudienceValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        if (audiences != null && audiences.contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}

OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
    JwtValidators.createDefaultWithIssuer(issuer),
    new AudienceValidator("api://orders")
);
decoder.setJwtValidator(validator);
```

`createDefaultWithIssuer` 는 기본적으로 `JwtTimestampValidator(60s clock skew)` + `JwtIssuerValidator` 를 조합한다.

## 6. 멀티 테넌트 — issuer 별 디코더 분기

대형 SaaS 는 테넌트마다 다른 AS 를 갖는다. issuer 가 다르면 다른 디코더를 써야 한다.

```java
@Bean
public AuthenticationManagerResolver<HttpServletRequest> tenantResolver(
        TenantRepository tenants) {

    JwtIssuerAuthenticationManagerResolver delegate =
        new JwtIssuerAuthenticationManagerResolver(issuer -> {
            Tenant tenant = tenants.findByIssuer(issuer)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown issuer: " + issuer));
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(tenant.jwkSetUri()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            return new ProviderManager(new JwtAuthenticationProvider(decoder));
        });
    return delegate;
}
```

`HttpSecurity.oauth2ResourceServer(rs -> rs.authenticationManagerResolver(tenantResolver(tenants)))` 로 연결한다. 토큰의 `iss` 를 보고 동적으로 디코더가 골라진다.

## 7. Opaque Token 과 Introspection

JWT 가 아니라 무작위 문자열인 **opaque token** 을 쓰는 AS도 있다. 이때는 RS 가 매 요청마다 AS 의 `/oauth2/introspect` endpoint 에 캐시 적용 검증을 한다.

```java
@Bean
public OpaqueTokenIntrospector introspector(OAuth2ResourceServerProperties props) {
    return new SpringOpaqueTokenIntrospector(
        props.getOpaquetoken().getIntrospectionUri(),
        props.getOpaquetoken().getClientId(),
        props.getOpaquetoken().getClientSecret()
    );
}
```

장단점:

- 장: 토큰을 즉시 무효화 가능(JWT 는 만료 전엔 못 막음).
- 단: 매 요청 네트워크 호출. 캐시 + circuit breaker 필수.

## 8. 검증 결과 캐시 vs 검증 자체 캐시

JWT 검증이 CPU 시간을 잡으면 다음을 검토한다.

| 캐시 대상 | 위험 | 권장 |
|---|---|---|
| 검증 결과 자체 (token → Authentication) | 만료된 토큰을 살릴 수 있음 | exp 포함한 짧은 TTL |
| JWK 만 캐시(서명용 공개키) | 회전 지연 401 가능 | refreshAhead + 5~15분 |
| JWS 검증 결과 (signature only) | claim 검증은 매번 해야 함 | guava Cache 1분 |

## 9. 통합 테스트 — `@WithMockJwt` 와 토큰 발급

```java
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    @Autowired MockMvc mvc;

    @Test
    @WithMockJwt(authorities = {"SCOPE_orders.read"}, claims = {
        "sub=alice", "preferred_username=alice"
    })
    void getOrders_returns_200() throws Exception {
        mvc.perform(get("/api/orders"))
            .andExpect(status().isOk());
    }
}
```

`spring-security-test` 의 `JwtRequestPostProcessor` 가 mock JWT 를 만들어 SecurityContext 에 주입한다. 실제 토큰 발급/검증을 거치지 않으므로 단위 테스트 범위에 적합하다.

End-to-end 테스트는 testcontainers 의 Keycloak 컨테이너를 띄워 실 토큰을 발급받아 호출한다.

## 참고

- Spring Security Reference, "OAuth 2.0 Resource Server" (docs.spring.io/spring-security/reference)
- RFC 7519 JWT, RFC 7517 JWK, RFC 7662 OAuth Token Introspection
- Connect2id Nimbus JOSE+JWT 라이브러리 문서 — JWKSource builder 옵션
- Auth0 Engineering, "How JWKS works and key rotation patterns"
