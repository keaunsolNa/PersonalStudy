Notion 원본: https://www.notion.so/34a5a06fd6d381f39de8de4cc9ec6400

# OAuth2 Authorization Code + PKCE와 Spring Security 6 실전

> 2026-04-22 신규 주제 · 확장 대상: Spring, REST_API (인증/보안 심화)

## 학습 목표

- OAuth2의 네 Grant Type을 비교하고, Authorization Code + PKCE가 **기본이자 유일한 권장**이 된 역사적 이유를 설명한다
- JWT 구조·서명 알고리즘(HS256/RS256/ES256) 선택 기준과 JWK Rotation 실전 처리를 이해한다
- Spring Security 6 `SecurityFilterChain` DSL로 Resource Server(API)와 Client(로그인) 양쪽을 한 애플리케이션에 구성한다
- Refresh Token Rotation, Token Introspection, Revocation 엔드포인트를 실무에서 안전하게 쓰는 법을 알고, 흔한 보안 실수(client_secret 노출, open redirect, CSRF 조합)를 방지한다

---

## 1. OAuth2/OIDC 용어 재정리

용어 혼동이 보안 실수의 절반을 차지한다. 네 주체를 명확히 구분한다.

- **Resource Owner**: 사용자 본인. "내 구글 드라이브 파일에 접근할 권리가 있는 사람".
- **Client**: 자원에 접근하려는 앱(우리 서비스). 모바일 앱, 웹 프론트, 백엔드 서비스.
- **Authorization Server(AS)**: 토큰을 발급하는 서버. Keycloak, Auth0, Cognito, 자체 구축.
- **Resource Server(RS)**: 실제 보호 자원을 제공하는 서버. 우리가 만드는 API 서버.

OIDC(OpenID Connect)는 OAuth2 **위에 올린 얇은 인증 계층**이다. OAuth2가 "권한 위임"만 한다면 OIDC는 "**누가** 로그인했는지"까지 알려준다. 구분하는 가장 간단한 기준은 스코프에 `openid`가 포함되는지 여부와, 응답에 **ID Token**이 포함되는지 여부다.

- Access Token: **API 호출 권한** (Resource Server가 소비)
- ID Token: **누구인지 증명** (Client가 소비, 사용자 UI에 표시용)

Access Token을 Client가 직접 파싱해서 `sub`을 읽는 건 명백한 안티패턴이다. **Access Token은 Resource Server만 검증하고, Client는 ID Token/UserInfo만 본다**.

Client 분류도 중요하다. 백엔드처럼 `client_secret`을 안전하게 저장할 수 있으면 **Confidential**, SPA/모바일처럼 소스가 클라이언트에 노출되면 **Public**. Public Client에서는 **client_secret을 쓸 수 없다**(어차피 앱을 디컴파일하면 털리니까). 이게 PKCE가 등장한 배경이다.

## 2. Authorization Code + PKCE 플로우

예전 SPA용 권장이던 Implicit Flow는 **Access Token이 브라우저 URL 프래그먼트에 그대로 노출**되는 구조였다. 브라우저 히스토리, 리퍼러, XSS를 통해 토큰이 유출됐다. 2019년 RFC BCP 차원에서 Implicit Flow는 **공식 폐기**됐다.

현재 공식 권장은 **Authorization Code + PKCE**이고, Confidential/Public Client 관계없이 동일 플로우를 쓴다.

```
1. Client: code_verifier = random(43~128 chars)
           code_challenge = BASE64URL(SHA256(code_verifier))
2. Client → Browser redirect:
   GET /authorize?
     response_type=code&
     client_id=myapp&
     redirect_uri=https://myapp.com/callback&
     scope=openid profile email&
     state=<CSRF 방지 random>&
     code_challenge=<challenge>&
     code_challenge_method=S256
3. User 로그인 & 동의
4. AS → Browser redirect:
   https://myapp.com/callback?code=ABC&state=...
5. Client → AS (Back channel):
   POST /token
     grant_type=authorization_code&
     code=ABC&
     redirect_uri=...&
     code_verifier=<원본 verifier>
6. AS: code_challenge == BASE64URL(SHA256(code_verifier)) 검증
7. AS → Client: { access_token, id_token, refresh_token }
```

PKCE의 핵심은 **code를 가로챈 공격자가 verifier 없이 토큰을 얻을 수 없다**는 것. 공격자는 challenge만 알 수 있고, verifier는 원본 클라이언트 메모리에만 있다. `state` 파라미터는 **CSRF 방지**용으로 따로 필요하다(PKCE와 별개 목적).

`code_challenge_method=S256`만 쓴다. `plain`은 verifier 자체를 URL에 노출시키는 구식 방식이라 쓰면 안 된다.

## 3. JWT 구조와 알고리즘 선택

JWT는 `Header.Payload.Signature` 형태의 base64url 세 부분이다.

```
eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYyJ9.
eyJpc3MiOiJodHRwczovL2F1dGguZXhhbXBsZS5jb20iLCJzdWIiOiIxMjM0IiwiZXhwIjoxNzQwMDAwMDAwfQ.
<signature>
```

Header의 `alg`는 서명 알고리즘, `kid`는 Key ID(JWK Set에서 어떤 키로 서명됐는지). Payload의 필수 클레임:

- `iss` (Issuer): 토큰 발급자 URL. Resource Server가 이 값으로 AS를 식별
- `sub` (Subject): 사용자 고유 식별자
- `aud` (Audience): 이 토큰을 받아야 할 Resource Server 식별자
- `exp`, `nbf`, `iat`: 만료/유효 시작/발급 시각
- `jti`: JWT ID. Revocation 리스트 관리 시 사용

**알고리즘 선택 기준**:

- `HS256`(HMAC SHA-256): 대칭키. AS와 RS가 같은 조직이고 같은 secret을 공유할 때만. 여러 RS에 secret이 퍼지면 유출 위험 증가.
- `RS256`(RSA SHA-256): 비대칭키. AS는 private key로 서명, RS는 public key(JWK Set)로 검증. 거의 모든 실제 서비스가 이것.
- `ES256`(ECDSA P-256): RS256보다 키가 짧고 검증이 빠르지만, 서명 비결정적이라 테스트가 까다롭다. 모바일 친화적.

실전에서는 **RS256이 기본**이다. JWK Set URI(`https://auth.example.com/.well-known/jwks.json`)를 제공하면 RS들이 자동으로 키를 캐시/갱신한다.

**Key Rotation**. AS는 주기적으로 새 키 쌍을 만들고 JWK Set에 추가하되 **이전 키도 한동안 유지**해서 기존 토큰이 유효하도록 한다. RS 쪽 JWK 캐시는 5~15분 TTL로 두되, 모르는 `kid`가 오면 즉시 refetch하는 전략이 표준이다.

## 4. Spring Security 6 Resource Server 설정

API 서버를 Resource Server로 구성하는 최소 설정:

```java
@Configuration
@EnableMethodSecurity  // @PreAuthorize 활성화
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthConverter())
                )
            )
            .csrf(csrf -> csrf.disable())  // API는 토큰 기반, CSRF 불필요
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
            .withJwkSetUri("https://auth.example.com/.well-known/jwks.json")
            .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
        gac.setAuthorityPrefix("SCOPE_");
        gac.setAuthoritiesClaimName("scope");  // 또는 "permissions", "roles"

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(gac);
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
```

`application.yml`에 `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`만 주면 자동 구성되지만, 권한 매핑을 커스터마이즈하려면 위처럼 명시적으로 Bean을 만드는 게 깔끔하다.

**주의**: `@PreAuthorize("hasAuthority('SCOPE_admin')")`와 `@PreAuthorize("hasRole('admin')")`은 다르다. `hasRole`은 내부적으로 `ROLE_` prefix를 붙인다. scope와 role을 섞어 쓰지 말고 하나의 컨벤션을 고수한다.

## 5. Client 설정 — 로그인 플로우

백엔드가 Client로 동작해 사용자를 로그인시키는 시나리오(SSR 애플리케이션, BFF 패턴):

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid, profile, email
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          google:
            issuer-uri: https://accounts.google.com
```

```java
@Bean
SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/**")
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/login", "/error").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2Login(oauth2 -> oauth2
            .loginPage("/login")
            .defaultSuccessUrl("/home", true)
        )
        .logout(logout -> logout.logoutSuccessUrl("/"))
        .build();
}
```

서버 간 API 호출이 필요하면 `OAuth2AuthorizedClientManager`를 써서 토큰을 자동 주입한다.

```java
@RequiredArgsConstructor
public class DownstreamApiClient {
    private final OAuth2AuthorizedClientManager clientManager;
    private final RestClient restClient = RestClient.create();

    public String callApi(Authentication auth) {
        OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
            .withClientRegistrationId("google")
            .principal(auth)
            .build();
        OAuth2AuthorizedClient client = clientManager.authorize(req);
        String token = client.getAccessToken().getTokenValue();
        return restClient.get()
            .uri("https://api.example.com/me")
            .header("Authorization", "Bearer " + token)
            .retrieve().body(String.class);
    }
}
```

## 6. Refresh Token Rotation과 재사용 감지

Access Token은 15~60분의 짧은 수명을 갖고, 만료되면 Refresh Token으로 새 Access Token을 받는다. 문제는 **Refresh Token이 탈취되면** 공격자가 계속 토큰을 갱신할 수 있다는 것.

**Rotation 전략**: Refresh Token을 쓸 때마다 **새 Refresh Token을 함께 발급**하고, 기존 Refresh Token은 **즉시 무효화**한다. 만약 같은 Refresh Token이 두 번 쓰이면 → **탈취 의심, 해당 사용자의 전체 Refresh Token family 무효화 + 재로그인 강제**.

```
1. 로그인 → AT(1), RT(1) 발급
2. AT 만료 → RT(1)으로 갱신 → AT(2), RT(2) 발급, RT(1) 무효화
3. (공격자가 가로챈 RT(1)로 갱신 시도) → AS: "RT(1)은 사용됨" 감지
4. RT 전체 family 무효화 → 합법 사용자도 재로그인 필요
```

이 설계는 Auth0, Okta, Keycloak 모두 지원한다. 자체 구축이라면 Refresh Token을 DB에 family_id와 함께 저장하고 재사용 감지 로직을 추가한다.

**Revocation 엔드포인트**(RFC 7009)는 사용자가 "로그아웃" 또는 관리자가 "세션 강제 종료"를 할 때 호출한다. Revocation 후에도 이미 발급된 Access Token은 **expiry까지는 유효**하므로, 강한 즉시성이 필요하면 **Token Introspection**(RFC 7662)으로 RS가 매 요청마다 AS에 유효성을 물어야 한다. Introspection은 RS의 latency를 크게 키우니 **고위험 API만 Introspection, 일반 API는 JWT 로컬 검증 + 짧은 expiry** 조합이 현실적이다.

## 7. 권한 모델 — Role vs Scope vs Permission

세 단어가 혼용되지만 실무에서는 다음처럼 구분하면 편하다.

- **Scope**: 토큰이 **요청**한 권한 범위. 사용자가 동의 화면에서 "이 앱에게 email 읽기 권한을 허용하시겠습니까?" 하는 그 단위. 거친 단위.
- **Role**: 사용자의 **직무**. admin, editor, viewer. 조직 내에서 의미가 있음.
- **Permission**: 리소스 단위의 **세밀한 허용**. `order:create`, `order:read:own`, `user:delete`. Fine-grained Authorization (FGA).

단순 서비스는 Role만으로 충분하다. 멀티 테넌트 SaaS처럼 "이 사용자는 A 프로젝트의 Admin이지만 B 프로젝트에서는 Viewer" 같은 요구가 생기면 **ReBAC**(Relationship-based Access Control)로 진화한다. Google Zanzibar 논문을 구현한 오픈소스 [OpenFGA](https://openfga.dev/), [SpiceDB](https://authzed.com/spicedb)가 대표적이다.

```java
// 거친 권한 체크(scope/role)는 Spring Security로
@PreAuthorize("hasAuthority('SCOPE_order.write')")
public void createOrder(Order o) { ... }

// 세밀한 권한 체크(FGA)는 런타임 질의로
if (!fgaClient.check("user:" + userId, "edit", "order:" + orderId)) {
    throw new AccessDeniedException();
}
```

## 8. 보안 헤더와 CORS의 함정

쿠키 기반 인증을 섞어 쓸 때 가장 많이 실수하는 부분이다.

**`SameSite` 속성**. 기본 `Lax`는 대부분의 CSRF를 막지만, 크로스 사이트 POST가 필요한 SSO 플로우에서는 `None; Secure` 조합이 필요하다. 그런데 `None`은 명시적으로 안전하지 않은 요청까지 허용하므로 반드시 **HttpOnly + Secure + SameSite=None + CSRF 토큰**의 4중 보호를 건다.

**`HttpOnly`**. JS에서 쿠키에 접근 못 하게 막는다. XSS로 토큰이 털리는 가장 큰 사고는 대부분 `HttpOnly`가 빠진 것이다.

**CSRF와 JWT의 관계**. JWT를 **Authorization 헤더로만** 전송하면 CSRF가 원천 불가능하다(브라우저는 수동으로 헤더를 붙여야 하므로 크로스 사이트에서 자동 전송되지 않음). JWT를 **쿠키에 저장**하면 CSRF 가능성이 돌아오므로 **CSRF 토큰**을 별도로 추가해야 한다. "Stateless JWT인데 CSRF 토큰 왜 쓰냐"는 흔한 오해.

**CORS preflight with credentials**. `credentials: 'include'`로 쿠키를 보내려면 서버의 `Access-Control-Allow-Origin`이 **와일드카드(`*`) 가 아닌 특정 오리진**이어야 하고 `Access-Control-Allow-Credentials: true`가 필요하다. Spring Security에서는:

```java
.cors(cors -> cors.configurationSource(req -> {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of("https://myapp.example.com"));
    c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
    c.setAllowCredentials(true);
    c.setAllowedHeaders(List.of("*"));
    c.setMaxAge(3600L);
    return c;
}))
```

## 9. 운영 체크리스트

프로덕션에서 반드시 확인하는 항목들.

- **client_secret을 프론트/앱에 하드코딩하지 않는다**. Public Client는 PKCE만 쓴다.
- **redirect_uri는 AS에 사전 등록된 값과 완전 일치해야 한다**. 와일드카드 허용 금지. Open Redirect 취약점으로 직결.
- **Access Token 만료는 짧게(15~60분), Refresh Token은 중간(1~14일), Refresh Token Rotation 필수**.
- **Token Introspection은 고위험 작업에만 사용**하고, 그렇지 않으면 JWT 로컬 검증 + JWK 캐시로 끝낸다.
- **감사 로그(audit log)**: 로그인 성공/실패, Refresh 토큰 사용, 권한 변경, Revocation을 별도 저장소에 분리 기록. PII(이메일 전체)는 해시해서 저장.
- **Rate Limiting**: `/token`, `/authorize` 엔드포인트는 IP당/유저당 별도 RL. Credential Stuffing 방어.
- **AS 선택**: 자체 구축은 고난이도. Keycloak(오픈소스, 자가 호스팅), Auth0(SaaS, 비싸지만 편함), AWS Cognito(AWS 생태계 통합)를 먼저 고려. 자체 구축은 보안 컴플라이언스 요구(금융/의료) 때만.

---

## 참고

- 기학습 연계: [Spring](./Spring.md), [REST_API](./REST_API.md), [Validator](./Validator.md)
- [RFC 6749 — OAuth 2.0](https://www.rfc-editor.org/rfc/rfc6749), [RFC 7636 — PKCE](https://www.rfc-editor.org/rfc/rfc7636)
- [RFC 8252 — OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252)
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)
- [OAuth 2.0 Security Best Current Practice (draft)](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics)
- [Spring Security 6 Reference](https://docs.spring.io/spring-security/reference/index.html)
- [OpenFGA](https://openfga.dev/), [SpiceDB](https://authzed.com/spicedb) — FGA 오픈소스
