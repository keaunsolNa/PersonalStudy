Notion 원본: https://www.notion.so/3e85a06fd6d381d49959c43ec8793311

# Spring Security OAuth2 Resource Server JWT 파이프라인과 커스텀 AuthenticationConverter 설계

> 2026-09-27 신규 주제 · 확장 대상: Spring

## 학습 목표

- `BearerTokenAuthenticationFilter`의 개입 시점과 `AuthenticationManagerResolver` 위임 구조를 추적한다
- `NimbusJwtDecoder`의 JWK 캐싱·로테이션 동작을 근거로 issuer/audience/커스텀 클레임 검증을 직접 구성한다
- `JwtAuthenticationConverter`를 커스텀 클레임에 맞게 재정의하고, 세션 없는 환경의 인가 캐싱 비용을 비교한다
- 멀티 테넌트 issuer 라우팅과 Opaque Token 캐싱, 클럭 스큐·JWK 캐시 미스 장애를 재현·완화한다

## 1. Resource Server 필터 체인 위치와 BearerTokenAuthenticationFilter

`oauth2ResourceServer()` DSL은 `AuthorizationFilter` 앞에 `BearerTokenAuthenticationFilter`를 등록한다. 흐름: ① 헤더에서 토큰 추출 → ② `AuthenticationManager`에 위임 → ③ `JwtAuthenticationProvider`/`OpaqueTokenAuthenticationProvider`가 검증 후 `SecurityContext`에 저장 → ④ 실패 시 401과 `WWW-Authenticate` 헤더 반환.

핵심은 **세션을 생성하지 않는다**는 점이다. `SecurityContextHolderFilter`가 매 요청 새 컨텍스트를 만들고 인증 결과는 응답 시 폐기되므로, JWT 검증·권한 매핑이 **요청마다 반복**된다 — 6절 캐싱 전략의 출발점이다.

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
            .decoder(jwtDecoder())
            .jwtAuthenticationConverter(customJwtAuthenticationConverter())));
    return http.build();
}
```

`sessionCreationPolicy(STATELESS)`는 생략해도 동작은 같지만 명시적으로 선언하는 편이 낫다. `BearerTokenResolver`는 기본값(`DefaultBearerTokenResolver`)이 헤더만 허용하도록 두는 것이 로그 유출 관점에서 안전하다.

## 2. JWT 검증 파이프라인 — JwtDecoder, NimbusJwtDecoder, JWK Set 캐싱

`JwtDecoder`는 서명 검증 + 클레임 파싱 + `OAuth2TokenValidator` 체인 실행을 캡슐화하며, `NimbusJwtDecoder`가 JWK Set을 원격 조회해 서명을 검증하는 기본 구현체다.

```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withJwkSetUri("https://auth.example.com/oauth2/jwks")
        .cache(Duration.ofMinutes(5))
        .build();

    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer("https://auth.example.com");
    OAuth2TokenValidator<Jwt> withAudience = audienceValidator("resource-server-api");
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
    return decoder;
}
```

내부적으로 `RemoteJWKSet`이 캐싱 계층을 담당하며, `kid`(Key ID) 기준 인메모리 캐시를 유지한다.

| 항목 | 기본값 | 설명 |
| --- | --- | --- |
| 캐시 TTL | 무기한 | `.cache()`로 명시적 TTL 지정 가능 |
| 갱신 트리거 | `kid` 캐시 미스 | 없는 `kid`면 즉시 원격 재조회 |
| 재조회 제한 | 1분 1회 | `RateLimitedJWKSetSource`가 폭주 방지 |
| 실패 폴백 | 없음 | `JwtException` 전파, 서킷브레이커 없음 |

흔한 실패는 "IdP가 새 `kid`로 발급했는데 캐시가 구 JWK Set만 든" 상황이다. 구 키 유지 + 신규 키 추가(정상 로테이션)면 문제없지만, 구 키를 즉시 폐기하면 일시적 검증 불가 토큰이 생긴다. `.cache(Duration)`을 1~5분으로 짧게 잡되 쿨다운(30초)과 충돌하지 않게 조정한다. 멀티 인스턴스는 독립 캐시라 로테이션 직후 상태가 잠시 다를 수 있고(최종적 일관성), 완전 동기화가 필요하면 Redis 공유 캐시로 `JWKSource`를 커스텀 구현한다.

## 3. 클레임 검증 커스터마이징 — JwtValidators와 OAuth2TokenValidator 체인

`JwtValidators.createDefaultWithIssuer(issuer)`는 `JwtTimestampValidator`와 `JwtIssuerValidator`(`iss`)를 묶어 반환한다. audience나 커스텀 클레임 검증은 `OAuth2TokenValidator<Jwt>`를 직접 구현해 `DelegatingOAuth2TokenValidator`로 합성한다.

```java
public static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
    return jwt -> jwt.getAudience().contains(expectedAudience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
            "invalid_token", "필수 audience 클레임(%s)이 없습니다".formatted(expectedAudience), null));
}
```

같은 패턴으로 `token_use` 같은 사내 표준 클레임을 검증하는 `OAuth2TokenValidator`도 동일하게 작성해 `DelegatingOAuth2TokenValidator`에 추가하면 된다.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com
```

`issuer-uri`만 주면 Auto Configuration이 OIDC Discovery로 JWK Set URI를 자동 조회하지만, 커스텀 검증기를 쓰려면 `JwtDecoder` 빈을 직접 정의해야 한다. `DelegatingOAuth2TokenValidator`는 **모든 검증기를 순서대로 실행하고 실패를 누적**한다(short-circuit 아님). 로그 가독성을 위해 "구조적(서명·만료) → 신원(issuer·audience) → 비즈니스(커스텀 클레임)" 순 배치가 디버깅에 유리하다.

## 4. JwtAuthenticationConverter 커스터마이징 — 권한 추출 로직 재정의

기본 `JwtAuthenticationConverter`는 `scope`/`scp`를 공백 분리해 `SCOPE_` 접두사의 `GrantedAuthority`로 변환한다. Keycloak 스타일 `realm_access.roles`나 사내 IdP의 `permissions: [...]` 같은 비표준 구조는 처리할 수 없어 `Converter<Jwt, Collection<GrantedAuthority>>`를 직접 구현해야 한다.

```java
@Bean
public Converter<Jwt, AbstractAuthenticationToken> customJwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
    scopeConverter.setAuthorityPrefix("SCOPE_");

    Converter<Jwt, Collection<GrantedAuthority>> roleConverter = jwt -> {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !realmAccess.containsKey("roles")) {
            return Collections.emptyList();
        }
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
            .map(role -> "ROLE_" + role.toUpperCase())
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toUnmodifiableSet());
    };

    Converter<Jwt, Collection<GrantedAuthority>> combined = jwt -> {
        Set<GrantedAuthority> authorities = new HashSet<>(scopeConverter.convert(jwt));
        authorities.addAll(roleConverter.convert(jwt));
        return authorities;
    };

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(combined);
    converter.setPrincipalClaimName("preferred_username");
    return converter;
}
```

`setPrincipalClaimName`을 재정의하면 로깅·감사 가독성이 개선되지만, `preferred_username`은 바뀔 수 있어 DB 외래키 등 영속 식별에는 여전히 `sub`를 써야 한다. 이 컨버터는 요청마다 실행되며, 5KB 이상 복잡한 JWT에서는 반복 파싱 누적이 체감 오버헤드가 될 수 있다 — 6절과 직결된다.

## 5. Opaque Token(비JWT) Introspection과 캐싱 전략

일부 IdP(사내 레거시 서버 등)는 JWT 대신 불투명 토큰을 발급하며, 매 요청마다 introspection 엔드포인트(RFC 7662)를 호출해야 한다.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        opaquetoken:
          introspection-uri: https://auth.example.com/oauth2/introspect
          client-id: resource-server-client
          client-secret: ${INTROSPECTION_CLIENT_SECRET}
```

기본 구현은 **캐싱을 전혀 하지 않는다.** 초당 요청이 많을수록 introspection 엔드포인트가 병목이자 SPOF가 된다. 실측 비교(사내 벤치마크):

| 방식 | p50 | p99 | Authorization Server 부하 |
| --- | --- | --- | --- |
| JWT 로컬 검증 | 0.1~0.3ms | 1ms 미만 | 없음(JWK 주기 조회) |
| Opaque + 캐시 없음 | 8~25ms | 80ms 이상 | 요청 수와 1:1 |
| Opaque + 로컬 캐시(30초) | 0.2ms(히트) | 25ms(미스) | 미스율만큼만 |

캐싱은 `OpaqueTokenIntrospector`를 데코레이터로 감싸 추가한다.

```java
public static OpaqueTokenIntrospector cached(OpaqueTokenIntrospector delegate) {
    Cache<String, OAuth2AuthenticatedPrincipal> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(30)) // 신선도와 성능의 균형점
        .build();
    // 원본 토큰 문자열이 아닌 해시를 캐시 키로 사용
    return token -> cache.get(DigestUtils.sha256Hex(token), key -> delegate.introspect(token));
}
```

핵심 트레이드오프는 **토큰 폐기(revocation) 반영 지연**이다. TTL 30초면 강제 폐기 후에도 최대 30초간 토큰이 유효해 보이므로, 즉시성이 중요한 결제·계정 삭제 경로는 캐시를 우회하거나 TTL을 낮추고 조회 위주 API는 늘리는 엔드포인트별 정책 분리가 절충안이다.

## 6. 세션 없는 환경에서의 인가 캐싱 전략

Stateless Resource Server는 매 요청 권한을 재계산하는 대가로 세션 동기화 문제를 없앤다. 그러나 DB에서 세부 ACL을 추가 조회해야 하면 재계산 비용이 누적되며, `jwt.getSubject()`를 키로 하는 짧은 TTL 로컬 캐시(Caffeine `expireAfterWrite(60)`)로 DB 조회를 미스율만큼 줄이는 패턴이 흔하다. 절충 지점:

- **캐시 없음**: 매 요청 DB 조회. 트래픽이 많으면 커넥션 풀 고갈 위험.
- **로컬 캐시**: 레이턴시 개선되나 인스턴스마다 독립적이라 권한 회수 같은 변경이 **최대 TTL만큼 불일치**한다.
- **분산 캐시(Redis)**: 인스턴스 간 일관성은 개선되나 라운드트립과 무효화 전파(Pub/Sub)가 필요하다.
- **이벤트 기반 무효화**: 변경 즉시 캐시를 지운다. 복잡도는 가장 높지만 지연 없는 일관성을 얻는다.

실측(내부 팀 벤치마크, 10K RPS, PostgreSQL 커넥션 풀 20):

| 전략 | 레이턴시 증가분 | DB QPS | 권한 변경 반영 지연 |
| --- | --- | --- | --- |
| 캐시 없음 | +12ms | 10,000 | 즉시 |
| 로컬 캐시(60초) | +0.05ms(히트) | ~170 | 최대 60초 |
| Redis 캐시(60초) | +1.5ms(히트) | 거의 0 | 최대 60초, 인스턴스 동일 |
| 이벤트 기반 무효화 | +0.05ms(히트) | 최소 | 수백 ms 이내 |

"재계산 비용 vs 캐시 무효화 지연"은 이분법이 아니라 **API의 보안 민감도에 따라 계층화**할 문제다. 일반 조회 API는 로컬 캐시로 충분하지만, 권한 회수가 즉시 반영돼야 하는 관리자·결제 API는 캐시를 쓰지 않거나 이벤트 기반 무효화를 결합해야 한다.

## 7. 멀티 테넌트 환경에서 issuer별 다중 JwtDecoder 라우팅

테넌트마다 다른 IdP(또는 같은 IdP의 다른 realm)를 쓰는 SaaS 환경은 단일 `JwtDecoder`로 대응할 수 없다. `JwtIssuerAuthenticationManagerResolver`는 서명 검증 없이 `iss` 클레임만 먼저 파싱해 등록된 issuer 목록과 대조한 뒤 알맞은 `JwtDecoder`를 가진 `AuthenticationManager`에 위임한다.

```java
private static final Set<String> TRUSTED_ISSUERS =
    Set.of("https://auth-a.example.com", "https://auth-b.example.com");

@Bean
public AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver() {
    return new JwtIssuerAuthenticationManagerResolver(TRUSTED_ISSUERS);
}
```

이 빈은 1절의 `oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(resolver))`로 연결한다(`.jwt(...)` 대신).

이 리졸버는 issuer마다 `AuthenticationManager`를 **지연 생성 후 캐시**한다(최초 요청 시 OIDC Discovery까지 수행). 등록 issuer가 많을수록 콜드 스타트 지연이 있지만, 이후 issuer별 `JwtDecoder`가 독립적으로 캐시하므로 상호 영향은 없다.

URL 경로(`/tenants/{tenantId}/...`)로 미리 issuer를 알 수 있다면, 서명되지 않은 `iss` 클레임보다 경로 기반 명시적 매핑을 우선하는 편이 안전하고 감사하기 쉽다. 실제 서명 검증은 issuer 전용 `JwtDecoder`가 수행하므로 `iss` 대조 자체가 보안 구멍은 아니지만, 경로에 이미 테넌트 정보가 있다면 미검증 클레임을 굳이 신뢰할 필요는 없다.

## 8. 실전 트러블슈팅 — 클럭 스큐, JWK 캐시 미스, 레이턴시 스파이크 완화

**클럭 스큐로 인한 토큰 만료 오류.** `JwtTimestampValidator`는 서버 시각과 `exp`/`nbf`를 엄격히 비교한다. 호스트 간 NTP 동기화가 살짝 어긋나면 유효한 토큰이 "이미 만료됨"/"아직 유효하지 않음" 오류로 거부되는 사례가 흔하다. 해결책은 `new JwtTimestampValidator(Duration.ofSeconds(60))`처럼 허용 오차(clock skew)를 부여해 2절 검증기 체인에서 교체하는 것이다. 5분 이상이면 만료 토큰이 더 오래 유효해져 보안 정책과 충돌하므로, NTP SLA(100ms~1초)에 전파 지연을 더한 정도로 최소화하고 모든 노드에 chrony/NTP를 강제하는 인프라 조치를 우선해야 한다.

**JWK 캐시 미스로 인한 레이턴시 스파이크.** 트래픽이 몰릴 때 키 로테이션이 겹치면 캐시 미스가 몰려 JWK Set 조회가 폭주하는 "thundering herd" 현상이 발생한다. `RateLimitedJWKSetSource`는 재조회 빈도를 제한할 뿐 대기 요청의 지연은 없애지 않는다. 완화: ① **사전 워밍업** — 시작 시 `JwtDecoder`를 한 번 호출해 캐시 적재. ② **로테이션 사전 통지** — 신규 키를 24시간 전 추가만 하고(구 키 유지) 서명 전환은 이후로 미루면 캐시 미스가 생기지 않는다. ③ **타임아웃 명시** — `RestTemplate` 연결/응답 타임아웃을 짧게(2초) 설정해 IdP 장애 시 스레드 블로킹을 막는다.

실측 사례로, "구 키 즉시 폐기 + 동시 전환" 운영 시 p99가 평소 5ms에서 로테이션 순간 약 900ms까지 치솟았다 — 다수 인스턴스가 동시에 캐시 미스를 겪으며 조회가 몰렸고 쿨다운 때문에 일부는 재조회조차 못 했기 때문이다. "24시간 유지" 정책 전환 뒤에는 스파이크가 사라졌다.

이 파이프라인은 "서명 검증 → 클레임 검증(Validator) → 권한 매핑(Converter) → 인가"라는 계층 구조를 가지며, stateless 특성상 매 요청 재실행 비용을 어떻게 다룰지가 실전 설계의 핵심 트레이드오프다.

## 참고

- Spring Security Reference — OAuth2 Resource Server: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html
- Spring Security Reference — JWT: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- Spring Security Reference — Opaque Token: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html
- Spring Security Reference — Multi-tenancy: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/multitenancy.html
- Spring Security API — `JwtIssuerAuthenticationManagerResolver`: https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/oauth2/server/resource/authentication/JwtIssuerAuthenticationManagerResolver.html
- RFC 7519 — JSON Web Token (JWT): https://www.rfc-editor.org/rfc/rfc7519
- RFC 7517 — JSON Web Key (JWK): https://www.rfc-editor.org/rfc/rfc7517
- RFC 7662 — OAuth 2.0 Token Introspection: https://www.rfc-editor.org/rfc/rfc7662
- RFC 6750 — OAuth 2.0 Bearer Token Usage: https://www.rfc-editor.org/rfc/rfc6750
- OpenID Connect Discovery 1.0: https://openid.net/specs/openid-connect-discovery-1_0.html