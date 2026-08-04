Notion 원본: https://app.notion.com/p/3b25a06fd6d381ccb8b1d3c852b7b192

# Spring Security 필터 체인과 인증 아키텍처 및 SecurityContext 전파

> 2026-08-04 신규 주제 · 확장 대상: Spring

## 학습 목표

- 서블릿 필터와 Spring Security 의 DelegatingFilterProxy·FilterChainProxy 연결 구조를 파악한다
- SecurityFilterChain 안에서 인증·인가 필터가 실행되는 순서와 각 필터의 책임을 구분한다
- SecurityContext 가 스레드 로컬로 저장·전파되는 메커니즘과 비동기 경계의 유실 문제를 다룬다
- 인증 실패·예외가 ExceptionTranslationFilter 를 통해 진입점으로 위임되는 흐름을 추적한다

## 1. 서블릿 컨테이너와 스프링 컨텍스트의 접점

Spring Security 는 스프링 MVC 위에 얹힌 별도의 계층이 아니라, 서블릿 스펙의 **필터**에 뿌리를 둔다. 요청이 `DispatcherServlet` 에 도달하기 전, 서블릿 컨테이너의 필터 체인을 먼저 통과하는데 Security 는 이 지점을 장악한다. 문제는 서블릿 필터가 컨테이너에 의해 생성되어 스프링 빈 라이프사이클 밖에 있다는 점이다. 의존성 주입이나 AOP 를 쓸 수 없다.

이 간극을 메우는 것이 `DelegatingFilterProxy` 다. 이것은 컨테이너에 등록된 얇은 서블릿 필터이지만, 실제 처리는 스프링 컨텍스트에서 이름으로 찾은 빈에게 위임한다. 위임 대상이 `springSecurityFilterChain` 이라는 이름의 `FilterChainProxy` 다.

```java
// Boot 자동 구성이 내부적으로 하는 등록 (개념적 표현)
DelegatingFilterProxy proxy =
    new DelegatingFilterProxy("springSecurityFilterChain");
// 요청 시점에 ApplicationContext 에서 빈을 lazy 하게 조회해 위임
```

`FilterChainProxy` 는 여러 개의 `SecurityFilterChain` 을 보유하고, 각 체인은 `RequestMatcher` 로 자신이 처리할 요청을 판별한다. 요청 하나당 **매칭되는 첫 번째 체인만** 실행된다. 이 때문에 체인 등록 순서가 중요하다. 더 구체적인 matcher 를 가진 체인을 먼저 두어야 한다.

```java
@Bean
@Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("SERVICE"))
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
}

@Bean
@Order(2)
SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/css/**").permitAll()
            .anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```

`/api/**` 요청은 1번 체인만 타고 폼 로그인 필터를 거치지 않는다. 반대로 `@Order` 를 뒤집으면 `/api` 요청도 webChain 에 먼저 매칭되어 의도치 않게 폼 로그인 리다이렉트를 받는다. 실무에서 "API 가 302 로 로그인 페이지를 준다"는 증상은 대부분 이 순서 문제다.

## 2. 필터 체인의 실행 순서 — 누가 먼저 도는가

하나의 `SecurityFilterChain` 안에는 십수 개의 필터가 정해진 순서로 배열된다. 순서는 `HttpSecurity` 설정과 무관하게 `FilterOrderRegistration` 이 강제한다. 핵심 필터의 실행 순서와 책임은 다음과 같다.

| 순서 | 필터 | 책임 |
| --- | --- | --- |
| 1 | DisableEncodeUrlFilter | 세션 ID URL 인코딩 차단 |
| 2 | SecurityContextHolderFilter | 저장소에서 SecurityContext 로드·복원 |
| 3 | HeaderWriterFilter | 보안 응답 헤더 부여 |
| 4 | CsrfFilter | CSRF 토큰 검증 |
| 5 | LogoutFilter | 로그아웃 요청 처리 |
| 6 | UsernamePasswordAuthenticationFilter | 폼 로그인 자격 증명 처리 |
| 7 | BearerTokenAuthenticationFilter | JWT/Bearer 토큰 인증 |
| 8 | RequestCacheAwareFilter | 인증 후 원래 요청 복원 |
| 9 | AnonymousAuthenticationFilter | 익명 사용자 토큰 부여 |
| 10 | ExceptionTranslationFilter | 인증/인가 예외를 진입점으로 위임 |
| 11 | AuthorizationFilter | 최종 인가 결정 |

가장 자주 오해하는 지점은 **인가(AuthorizationFilter)가 거의 마지막**이라는 사실이다. 인증 필터들이 앞에서 `SecurityContext` 를 채우고, 맨 끝의 인가 필터가 그 컨텍스트를 읽어 접근을 허용/거부한다. `ExceptionTranslationFilter` 가 인가 필터 바로 앞에 있는 이유도 여기 있다. 인가 필터가 던지는 `AccessDeniedException` 을 감싸는 try/catch 역할을 하기 때문이다.

## 3. 인증의 핵심 삼각형 — AuthenticationManager

인증 처리 필터(폼 로그인 등)는 자격 증명을 직접 검증하지 않는다. `Authentication` 객체(미인증 상태)를 만들어 `AuthenticationManager` 에게 넘긴다. 표준 구현 `ProviderManager` 는 등록된 `AuthenticationProvider` 목록을 순회하며, 해당 토큰 타입을 지원하는 provider 에게 실제 검증을 위임한다.

```java
public Authentication authenticate(Authentication authentication) {
    for (AuthenticationProvider provider : getProviders()) {
        if (!provider.supports(authentication.getClass())) {
            continue;                       // 이 토큰을 못 다루면 다음으로
        }
        Authentication result = provider.authenticate(authentication);
        if (result != null) {
            return result;                  // 인증 성공 → 인증된 토큰 반환
        }
    }
    throw new ProviderNotFoundException(...);
}
```

`DaoAuthenticationProvider` 가 가장 흔한 provider 다. `UserDetailsService` 로 사용자를 조회하고 `PasswordEncoder` 로 비밀번호를 대조한다. 인증에 성공하면 권한(`GrantedAuthority`)이 채워진 **새로운 인증된 Authentication** 을 반환한다. 원본 토큰의 raw 비밀번호는 이 시점에 지워진다(`eraseCredentials`).

```java
@Bean
UserDetailsService users(DataSource ds) {
    return username -> userRepository.findByName(username)
        .map(u -> User.withUsername(u.getName())
            .password(u.getEncodedPassword())   // 이미 인코딩된 값
            .authorities(u.getRoles().toArray(String[]::new))
            .build())
        .orElseThrow(() -> new UsernameNotFoundException(username));
}

@Bean
PasswordEncoder passwordEncoder() {
    // {bcrypt}, {noop} 등 접두사로 인코더를 위임 — 마이그레이션 안전
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

`DelegatingPasswordEncoder` 는 저장된 해시 앞의 `{bcrypt}` 같은 식별자로 인코더를 고른다. 덕분에 과거 MD5 해시와 신규 bcrypt 해시를 한 컬럼에 공존시키면서 점진 마이그레이션이 가능하다. 로그인 성공 시 구식 해시를 재인코딩해 갱신하는 것도 이 구조 덕분이다.

## 4. SecurityContext 와 스레드 로컬 전파

인증 결과는 `SecurityContextHolder` 에 담긴다. 기본 전략은 `MODE_THREADLOCAL` 로, 현재 스레드에만 컨텍스트가 묶인다. 요청을 처리하는 서블릿 스레드 안에서는 어디서든 `SecurityContextHolder.getContext().getAuthentication()` 으로 인증 정보를 꺼낼 수 있다.

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();
boolean isAdmin = auth.getAuthorities().stream()
    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
```

문제는 **스레드 경계를 넘을 때** 발생한다. `@Async` 로 다른 스레드에 작업을 넘기거나, `WebFlux`, 병렬 스트림, 수동 생성 스레드로 넘어가면 스레드 로컬이 복사되지 않아 컨텍스트가 `null` 이 된다.

```java
@Async
public void audit() {
    // ✗ NPE 위험: 워커 스레드에는 SecurityContext 가 없다
    var user = SecurityContextHolder.getContext().getAuthentication().getName();
}
```

해결책은 컨텍스트 전파 전략을 바꾸는 것이다. `@Async` 계열은 `DelegatingSecurityContextExecutor` 나 전략 모드 변경으로 해결한다.

```java
// 자식 스레드로 컨텍스트를 상속시키는 전략
SecurityContextHolder.setStrategyName(
    SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);

// 또는 개별 작업을 래핑
Runnable wrapped = new DelegatingSecurityContextRunnable(task);
executor.execute(wrapped);
```

`MODE_INHERITABLETHREADLOCAL` 은 스레드 생성 시점에 부모의 컨텍스트를 복사하지만, **스레드 풀에서 재사용되는 스레드**에는 위험하다. 이전 요청의 컨텍스트가 남아 다른 사용자의 권한으로 실행될 수 있다. 그래서 풀 기반에서는 작업 단위로 명시적으로 감싸는 `DelegatingSecurityContext*` 방식이 안전하다.

Spring Security 6 에서는 `SecurityContextHolderFilter` 가 컨텍스트를 **명시적으로 저장**하도록 바뀌었다. 과거의 `SecurityContextPersistenceFilter` 가 요청 끝에 자동 저장하던 것과 달리, 이제는 `SecurityContextRepository.saveContext()` 를 명시 호출해야 세션에 반영된다. 인증 필터가 이 저장을 대행하지만, 수동으로 `SecurityContextHolder` 만 세팅하고 저장을 빠뜨리면 다음 요청에서 인증이 유실된다.

## 5. 인가 결정 — AuthorizationManager 로의 통합

Spring Security 6 는 과거의 `AccessDecisionManager`·`Voter` 구조를 `AuthorizationManager` 하나로 통합했다. 인가 필터는 요청마다 `AuthorizationManager.check()` 를 호출해 허용 여부를 판단한다.

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/admin/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.POST, "/orders").hasAuthority("SCOPE_write")
    .requestMatchers("/public/**").permitAll()
    .anyRequest().authenticated());
```

메서드 수준 인가는 `@EnableMethodSecurity` 로 활성화하며, `@PreAuthorize` 가 AOP 프록시로 동작한다. 여기서 3번의 self-invocation 문제가 재등장한다. 같은 클래스 내부에서 `@PreAuthorize` 메서드를 직접 호출하면 프록시를 거치지 않아 검사가 생략된다.

```java
@PreAuthorize("hasRole('ADMIN') and #userId == authentication.name")
public void deleteUser(String userId) { ... }

@PreAuthorize("hasAuthority('SCOPE_read') and returnObject.owner == authentication.name")
@PostAuthorize("...")   // 반환값 기반 사후 검사
public Document load(Long id) { ... }
```

SpEL 표현식에서 `authentication`, `principal`, 메서드 인자(`#userId`), 반환값(`returnObject`)에 접근할 수 있다. 복잡한 규칙은 커스텀 `PermissionEvaluator` 로 분리하는 편이 테스트하기 쉽다.

## 6. 예외 흐름과 진입점 — 401 vs 403

`ExceptionTranslationFilter` 는 뒤쪽 필터들이 던지는 두 예외를 구분해 처리한다. `AuthenticationException`(인증 자체 실패)은 `AuthenticationEntryPoint` 로, `AccessDeniedException`(인증은 됐으나 권한 부족)은 `AccessDeniedHandler` 로 위임한다.

```java
http.exceptionHandling(e -> e
    // 미인증 API 요청 → 리다이렉트 대신 401 JSON
    .authenticationEntryPoint((req, res, ex) -> {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthenticated\"}");
    })
    // 인증됐지만 권한 부족 → 403
    .accessDeniedHandler((req, res, ex) -> res.sendError(403)));
```

REST API 라면 미인증에 302 리다이렉트를 주면 안 된다. 위처럼 진입점을 401 응답으로 교체해야 한다. `oauth2ResourceServer` 나 `httpBasic` 를 쓰면 적절한 진입점이 자동 설정되지만, 폼 로그인만 켜면 기본 진입점이 로그인 페이지 리다이렉트라는 점을 기억해야 한다.

## 7. 테스트 — 필터 체인을 타지 않고 검증

인가 규칙은 `spring-security-test` 로 모킹된 인증을 주입해 검증한다. 슬라이스 테스트에서 필터 체인 전체를 재현하지 않고도 권한 분기를 확인할 수 있다.

```java
@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles = "USER")
    void user_forbidden_on_admin() throws Exception {
        mvc.perform(get("/admin/dashboard"))
           .andExpect(status().isForbidden());        // 403
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_allowed() throws Exception {
        mvc.perform(get("/admin/dashboard"))
           .andExpect(status().isOk());
    }

    @Test
    void anonymous_unauthenticated() throws Exception {
        mvc.perform(get("/admin/dashboard"))
           .andExpect(status().isUnauthorized());     // 401
    }
}
```

`@WithMockUser` 는 `SecurityContext` 를 직접 채워 인증 필터를 건너뛴다. 인증된 사용자와 미인증 사용자, 권한 부족 사용자의 세 경우를 각각 401/403/200 으로 구분해 검증하는 것이 인가 테스트의 기본 골격이다.

## 8. trade-off 정리

세션 기반 인증은 서버가 상태를 쥐고 있어 즉시 무효화(로그아웃, 강제 만료)가 쉽지만, 수평 확장 시 세션 공유(sticky session, 분산 세션 스토어)가 필요하다. JWT 기반 무상태 인증은 확장이 자유롭지만 발급된 토큰을 즉시 폐기하기 어렵다. 블랙리스트나 짧은 만료 + 리프레시 조합으로 절충한다.

필터 체인을 여러 개로 쪼개면 `/api` 와 `/web` 를 독립적으로 설정할 수 있어 명확하지만, 체인 수가 늘면 `RequestMatcher` 중첩으로 어떤 요청이 어느 체인을 타는지 추적이 어려워진다. `securityMatcher` 를 구체적으로 유지하고 순서를 `@Order` 로 못 박는 규율이 필요하다. 메서드 보안은 세밀하지만 SpEL 이 런타임 문자열이라 리팩터링 안전성이 낮으므로, 도메인 규칙이 복잡하면 코드로 표현되는 `AuthorizationManager` 구현을 선호한다.

## 참고

- Spring Security Reference — Architecture, Servlet Authentication (docs.spring.io)
- Spring Security 6.x Migration Guide — SecurityContextHolderFilter
- Servlet Specification 4.0 — Filter and FilterChain
- 『Spring Security in Action』 (Laurentiu Spilca, Manning)
