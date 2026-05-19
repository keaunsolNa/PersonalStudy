Notion 원본: https://www.notion.so/3655a06fd6d38113af4cdab04b20333a

# Spring Authorization Server OAuth2.1 — Token Customizer·JWK Rotation 운영

> 2026-05-19 신규 주제 · 확장 대상: Backend

## 학습 목표

- Spring Authorization Server(SAS)가 OAuth2.1 / OIDC 1.0 표준을 어떻게 분해해 빈으로 노출하는지 구조를 파악한다
- `OAuth2TokenCustomizer<JwtEncodingContext>`로 access·id token 의 클레임을 변형하고, 미세한 변형이 RFC 호환성을 깨지 않게 가드한다
- `JWKSource` + `JWKSet` rotation 전략을 설계해 무중단 키 교체와 `kid` 폴백을 보장한다
- DCR(Dynamic Client Registration)·PAR·PKCE 가 강제되는 환경에서 다중 클라이언트 라우팅·시크릿 폐기를 안정적으로 운영한다

## 1. SAS의 빈 구성 — 어떤 빈이 어떤 RFC를 책임지는가

Spring Authorization Server(1.3+)는 `SecurityFilterChain` 두 개를 만든다. 첫 번째는 protocol endpoint(`/oauth2/*`, `/.well-known/*`, `/userinfo`, `/connect/register`)를 보호하고, 두 번째는 일반 form-login 페이지를 처리한다. 분리의 의도는 protocol 트래픽에 *기본 인증·CSRF 비활성·CORS 별도* 같은 정책을 적용하기 위함이다.

핵심 빈은 `RegisteredClientRepository`(클라이언트 메타데이터), `OAuth2AuthorizationService`(인가 상태), `OAuth2AuthorizationConsentService`, `AuthorizationServerSettings`, `SecurityFilterChain` 다섯이다. `RegisteredClientRepository.JdbcRegisteredClientRepository`는 기본 스키마(`oauth2_registered_client`)를 제공하지만 `client_secret` 컬럼은 *암호화되지 않은 상태*로 들어가니 운영에서는 `PasswordEncoder.encode`된 값으로 직접 INSERT 한다.

`AuthorizationServerSettings.issuer` 는 *모든* JWT의 `iss` 클레임으로 들어가며 OIDC discovery(`/.well-known/openid-configuration`)에 그대로 노출된다. issuer 값에 path prefix 가 붙는 경우 Spring 은 `/oauth2/token` 같은 경로를 그 prefix 아래로 자동 매핑하지 않는다는 점을 유의한다.

## 2. OAuth2.1 핵심 변경 — implicit·password 폐기와 PKCE 강제

OAuth2.1(draft 11 기준)은 OAuth2.0 위에 다음을 강제한다. 첫째, `implicit` 와 `password` 그랜트가 삭제되었다. 모바일/SPA 는 모두 `authorization_code + PKCE`로 통일한다. 둘째, public 클라이언트(시크릿 없는 SPA·모바일)는 PKCE 가 *필수*다. SAS 는 `ClientSettings.builder().requireProofKey(true)` 가 기본값. 셋째, refresh token rotation 이 기본이다. `TokenSettings.builder().reuseRefreshTokens(false)`가 기본값이며, 이전 토큰을 재사용하려는 시도가 감지되면 SAS 는 동일 grant 의 모든 refresh token 을 invalidate 한다. 넷째, redirect_uri 정확 매칭. wildcard·정규식·suffix 매칭은 모두 거부되고 *문자열 동등성*만 허용된다. localhost 만 예외로 port 변동을 허용한다.

이 네 가지는 정책 레벨이 아닌 *프로토콜 핸들러* 레벨에서 강제되므로 우회가 어렵다.

## 3. Token Customizer — 클레임 가공의 정석

`OAuth2TokenCustomizer<JwtEncodingContext>` 는 access·id token 의 클레임을 발급 직전에 변형할 수 있는 단일 진입점이다.

```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(RoleService roleService) {
    return context -> {
        if (context.getTokenType().equals(OAuth2TokenType.ACCESS_TOKEN)) {
            Authentication principal = context.getPrincipal();
            Collection<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
            context.getClaims()
                .claim("authorities", authorities)
                .claim("tenant_id", resolveTenantId(principal))
                .claim("client_id", context.getRegisteredClient().getClientId());
        }
    };
}
```

세 가지 함정을 피한다. 표준 클레임 이름을 변형하지 않는다(`aud`·`iss`·`exp`·`iat`·`nbf`·`sub`·`jti`). `Authentication` 이 항상 사용자라고 가정하지 않는다(client_credentials 그랜트에서는 principal 이 *클라이언트*). 페이로드 크기는 8KB Cookie/Header 제한에 걸리지 않게 한다.

## 4. JWKSource 와 키 회전 — 무중단 교체의 표준 절차

SAS 는 서명 키를 `JWKSource<SecurityContext>` 빈으로 받는다. 운영에서는 *세 개 슬롯*을 두는 *rolling rotation* 이 표준이다. `previous`·`current`·`next` 의 세 키를 동시에 노출하되, 새 토큰의 서명은 `current` 로만 한다.

JWKS 엔드포인트(`/oauth2/jwks`)는 세 키를 모두 반환한다. RS(Resource Server)는 토큰의 `kid` 헤더를 보고 JWKS 에서 같은 `kid` 의 키로 검증한다. 따라서 `previous` 키로 서명된 토큰은 만료까지 그대로 검증된다.

회전 트리거가 발생하면 `next → current`, `current → previous`, `previous` 폐기 순서로 한 단계씩 민다. 옮긴 직후 24시간(혹은 access token 의 최대 수명 + 마진)동안은 `previous` 가 살아 있어야 한다.

```java
@Component
public class RotatingJwkSource implements JWKSource<SecurityContext> {
    private final AtomicReference<JWKSet> ref = new AtomicReference<>(initial());
    public void rotate(JWKSet next) { ref.set(next); }
    @Override public List<JWK> get(JWKSelector selector, SecurityContext ctx) {
        return selector.select(ref.get());
    }
}
```

복수 SAS 인스턴스(스케일 아웃) 환경에서는 키 자체를 외부 저장소(KMS·HashiCorp Vault transit·DB)에 두고 각 인스턴스가 동일한 키셋을 조회하도록 만든다.

## 5. 회전 트리거 — 시간·이벤트·강제 폐기

시간 기반 — 정기 회전. 90일 또는 180일 주기로 자동 회전한다. 이벤트 기반 — 키 컴프로마이즈 의심 시 CI/CD pipeline 의 `rotate-now` 잡으로 수동 트리거한다. 강제 폐기 — 클라이언트 시크릿 유출 시 해당 client 의 모든 *기존 발급 토큰* 을 무효화하려면 RS 의 캐시·denylist 까지 동기화해야 한다. introspection 호출을 강제하려면 access token 의 수명을 짧게(5~15분) 두거나, `JwtDecoder` 의 `OAuth2TokenValidator` 에 외부 denylist 체크를 추가한다.

## 6. DCR·PAR·Token Exchange — 부가 RFC의 활성화

SAS 1.3 부터 다음 부가 RFC 가 빈 한 줄로 활성화된다. DCR(`/connect/register`, RFC 7591) 은 클라이언트가 동적으로 자신을 등록하게 한다. *공개 등록*은 거의 항상 위험하므로 초기 등록은 *initial access token* 발급 후에만 허용한다. PAR(`/oauth2/par`, RFC 9126) 은 authorization request 를 *POST body* 로 미리 전송해 url 길이 한계와 *referrer leak* 을 회피한다. Token Exchange(`/oauth2/token` + grant `urn:ietf:params:oauth:grant-type:token-exchange`)는 *backend-for-frontend* 또는 *microservice 간 위임* 시나리오에서 사용된다.

## 7. 데이터베이스 스키마와 운영 컬럼

JDBC 백엔드 사용 시 `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` 세 테이블이 필요하다. `oauth2_authorization` 의 `attributes` 컬럼은 직렬화된 `Map<String, Object>`로 클라이언트마다 가변이고, MySQL 기본 row size 65535 바이트를 넘어 `Row size too large` 에러를 자주 만난다. `attributes` 를 LONGTEXT 로 두고 `ROW_FORMAT=DYNAMIC` 으로 만든다. PostgreSQL 은 `TOAST` 가 자동 처리한다. 비활성 인가(`access_token_expires_at < now() - interval '7 days'`)는 정기 purge 한다.

## 8. 관찰 — Micrometer·OpenTelemetry 통합

SAS 는 `OAuth2AuthorizationEvent`, `OAuth2TokenIssuedEvent` 등을 `ApplicationEventPublisher` 로 발생시킨다. Micrometer 메트릭은 별도 빈으로 직접 만든다. OpenTelemetry tracing 은 `/oauth2/token` 핸들러를 *제로 자체 호출* 패스로 처리하므로 span 이 매우 짧다. p99 가 50ms 를 넘으면 DB 잠금 또는 `BCryptPasswordEncoder` 의 cost factor 가 높아진 경우다.

## 9. 실전 체크리스트

운영 투입 전 다음 8개를 확인한다. `issuer` 가 외부 도메인이고 reverse proxy 가 호스트 헤더를 보존하는가. `tokenSettings.accessTokenTimeToLive` 가 5~15분 사이인가. `tokenSettings.refreshTokenTimeToLive` 가 30일 이내인가. `clientSettings.requireAuthorizationConsent` 정책이 의도와 맞는가. JWKS 가 *세 개 슬롯* 으로 회전 가능한 구조인가. DB 의 `oauth2_authorization.attributes` 가 LONGTEXT/JSONB 인가. introspection 엔드포인트(`/oauth2/introspect`)가 외부에 노출되지 않는가. `/oauth2/revoke` 가 활성이고, 로그아웃 시 클라이언트가 자기 토큰을 폐기하는가.

이 8개를 통과한 SAS 는 OAuth2.1 / OIDC 1.0 의 표면을 안정적으로 다 막은 상태다.

## 참고

- Spring Authorization Server 공식 문서 <https://docs.spring.io/spring-authorization-server/reference/index.html>
- OAuth 2.1 draft <https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/>
- RFC 7591 — OAuth 2.0 Dynamic Client Registration
- RFC 9126 — OAuth 2.0 Pushed Authorization Requests
- Joe Grandja, "Spring Authorization Server — Architecture and Customization" (SpringOne 2024)
