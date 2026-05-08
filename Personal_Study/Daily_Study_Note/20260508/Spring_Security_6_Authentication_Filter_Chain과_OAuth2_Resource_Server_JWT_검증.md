Notion 원본: https://www.notion.so/35a5a06fd6d38187925fcbb9286e0242

# Spring Security 6 Authentication Filter Chain과 OAuth2 Resource Server JWT 검증

> 2026-05-08 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring Security 6의 SecurityFilterChain 구성 방식과 lambda DSL 마이그레이션 결과를 이해한다
- OAuth2 Resource Server를 JWT bearer token 검증용으로 구성하고 JWK Set 캐싱 동작을 제어한다
- `JwtAuthenticationConverter` 와 `OpaqueTokenIntrospector` 의 사용 시점을 구분하고 RBAC 매핑을 작성한다
- 토큰 만료, 시계 편차, 검증 실패 시의 응답 카테고리(`invalid_token`, `insufficient_scope`)를 정확히 다룬다

## 1. Spring Security 6 의 구조 변화

Spring Security 6.0은 SecurityFilterChain을 `@Bean` 메서드로 직접 정의하는 방식을 사실상의 표준으로 정착시켰다. 5.x의 `WebSecurityConfigurerAdapter`는 6.0에서 완전히 제거되었고, 모든 설정은 lambda DSL로 작성된다.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/public/**").permitAll()
				.requestMatchers("/admin/**").hasAuthority("SCOPE_admin")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
		return http.build();
	}
}
```

## 2. AuthenticationManager 구조와 BearerTokenAuthenticationFilter

1. `Authorization: Bearer ...` 헤더 또는 `access_token` 쿼리에서 raw token 추출
2. `BearerTokenAuthenticationToken` 객체로 감싼 뒤 `AuthenticationManager.authenticate()` 호출
3. JWT 모드면 `JwtAuthenticationProvider` 가 동작하며 JWT 디코딩과 서명 검증, claim 검증을 수행
4. 통과하면 `JwtAuthenticationToken` 을 SecurityContext 에 저장하고 다음 필터로 진행

```java
@Bean
@Order(1)
public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
	http.securityMatcher("/api/**")
		.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
	return http.build();
}
```

## 3. JWT 검증 단계와 실패 분기

| 단계 | 검증 항목 | 실패 코드 |
|---|---|---|
| 1 | header 형식, alg 가 JWS 알고리즘인지 | `invalid_token` |
| 2 | JWK Set 에서 kid 매칭, 서명 검증 | `invalid_token` |
| 3 | iss claim == issuer-uri 일치 | `invalid_token` |
| 4 | exp/nbf 검증, clock-skew 적용 | `invalid_token` |
| 5 | 사용자 정의 OAuth2TokenValidator | 사용자 정의 |

clock-skew는 default 60초다. 더 엄격한 검증이 필요하면 `JwtTimestampValidator(Duration.ofSeconds(5))` 를 직접 등록한다.

```java
@Bean
public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
	NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
	decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
		new JwtTimestampValidator(Duration.ofSeconds(10)),
		new JwtIssuerValidator(issuer),
		new AudienceValidator("api://my-service")));
	return decoder;
}
```

`aud` 검증은 default 동작에 포함되지 않으므로 multi-tenant·multi-service 환경에서 반드시 추가해야 한다.

## 4. JWK Set 캐싱과 키 로테이션

- 디폴트 캐시 TTL: 5분 (Cache-Control 헤더가 있으면 그 값을 우선)
- HTTP 304(Not Modified) 응답이면 만료 시점만 갱신, body는 재사용
- 키 매칭 실패(unknown kid) 시 즉시 한 번 강제 갱신

## 5. JwtAuthenticationConverter 로 RBAC 매핑

```java
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
	JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
	authoritiesConverter.setAuthoritiesClaimName("roles");
	authoritiesConverter.setAuthorityPrefix("ROLE_");

	JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
	converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
	converter.setPrincipalClaimName("sub");
	return converter;
}
```

## 6. JWT 모드 vs Opaque Token Introspection

| 항목 | JWT | Opaque (RFC 7662 Introspection) |
|---|---|---|
| 검증 방식 | 자체 서명 검증 | IdP의 `/introspect` 호출 |
| 네트워크 비용 | 거의 없음 | 매 요청마다 IdP 호출 |
| 폐기(Revocation) | 짧은 TTL + refresh | 즉시 폐기 가능 |
| 추천 시나리오 | 무상태 API | 즉시 폐기 중요 토큰 |

## 7. 인증 실패 응답 표준화

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="api", error="invalid_token", error_description="..."

HTTP/1.1 403 Forbidden
WWW-Authenticate: Bearer realm="api", error="insufficient_scope", scope="admin"
```

## 8. 운영 체크리스트

- `issuer-uri` 가 IdP 의 well-known/openid-configuration 을 반환하는지 startup 시점 검증
- audience claim 검증을 반드시 추가
- 토큰 TTL 은 가능하면 5~15분, refresh token 으로 갱신
- 시스템 시계는 NTP 로 200ms 이내 동기화, clock-skew 는 30초 이하로
- access log 에 토큰 자체를 남기지 않도록 마스킹 필터 적용

## 참고

- Spring Security 6.x Reference — OAuth 2.0 Resource Server
- RFC 6750 — Bearer Token Usage
- RFC 7519 — JSON Web Token (JWT)
- RFC 7662 — OAuth 2.0 Token Introspection
- Nimbus JOSE+JWT 라이브러리 공식 문서 — JWK Set Caching
