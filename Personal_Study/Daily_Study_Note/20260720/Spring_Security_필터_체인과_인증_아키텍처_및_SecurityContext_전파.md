Notion 원본: https://www.notion.so/3a35a06fd6d381d88c0ae77153d8f01f

# Spring Security 필터 체인과 인증 아키텍처 및 SecurityContext 전파

> 2026-07-20 신규 주제 · 확장 대상: Spring

## 학습 목표

- `FilterChainProxy` 와 `SecurityFilterChain` 이 서블릿 필터 계층에서 어떻게 위치하는지 파악한다
- 인증 흐름을 `AuthenticationManager` → `AuthenticationProvider` → `UserDetailsService` 순으로 추적한다
- `SecurityContextHolder` 의 저장 전략과 스레드/비동기 경계에서의 전파 문제를 진단한다
- 인가(authorization)가 `AuthorizationManager` 로 통합된 최신 구조를 이해한다

## 1. 서블릿 필터와 Spring Security 의 진입점

Spring Security 는 서블릿 필터 메커니즘 위에 세워져 있다. 요청이 `DispatcherServlet` 에 닿기 전, 서블릿 컨테이너의 필터 체인을 통과하는데, Spring Security 는 여기에 `DelegatingFilterProxy` 라는 단 하나의 필터를 등록한다. 이 프록시는 실제 작업을 하지 않고 Spring 컨텍스트에 등록된 빈 `springSecurityFilterChain`(즉 `FilterChainProxy`)에게 위임한다. 컨테이너의 필터 생명주기와 Spring 의 빈 생명주기를 잇는 어댑터 역할이다.

```java
public class DelegatingFilterProxy extends GenericFilterBean {
    private Filter delegate; // = FilterChainProxy 빈

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        this.delegate.doFilter(req, res, chain);
    }
}
```

이 구조 덕분에 Spring Security 의 모든 필터는 Spring 빈으로 관리되어 의존성 주입과 프로퍼티 바인딩을 온전히 누릴 수 있다. 서블릿 컨테이너는 Spring Security 내부를 전혀 모른 채 하나의 필터로만 인식한다.

## 2. FilterChainProxy 와 다중 SecurityFilterChain

`FilterChainProxy` 는 여러 개의 `SecurityFilterChain` 을 보유하고, 들어온 요청의 URL 을 각 체인의 `RequestMatcher` 에 대조해 **가장 먼저 매칭되는 하나**의 체인만 실행한다. 이 "첫 매칭 승리" 규칙이 실무 설정에서 가장 흔한 혼란의 원인이다. 순서가 중요하며, 넓은 매처를 위에 두면 아래의 구체적 체인이 영영 실행되지 않는다.

```java
@Bean
@Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
}

@Bean
@Order(2)
SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/login", "/css/**").permitAll()
            .anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```

여기서 `/api/**` 요청은 1번 체인만 실행되고 2번은 건너뛴다. `@Order` 로 우선순위를 명시하고, 좁은 경로일수록 앞에 두는 것이 원칙이다. 각 체인 내부는 다시 순서가 정해진 필터들의 목록이며, `SecurityContextHolderFilter`, `UsernamePasswordAuthenticationFilter`, `ExceptionTranslationFilter`, `AuthorizationFilter` 등이 정해진 순서로 배치된다.

## 3. 인증 흐름: AuthenticationManager 를 중심으로

인증은 "제출된 자격증명이 유효한가"를 판정하는 과정이다. 흐름의 중심에는 `AuthenticationManager` 인터페이스가 있고, 표준 구현은 `ProviderManager` 다. `ProviderManager` 는 여러 `AuthenticationProvider` 를 보유하고, 각 provider 가 특정 `Authentication` 타입을 지원하는지(`supports`) 물어 처리를 위임한다.

```java
public interface AuthenticationProvider {
    Authentication authenticate(Authentication authentication) throws AuthenticationException;
    boolean supports(Class<?> authentication);
}
```

폼 로그인의 경우 `UsernamePasswordAuthenticationFilter` 가 요청에서 아이디/비밀번호를 뽑아 미인증 `UsernamePasswordAuthenticationToken` 을 만들고 `AuthenticationManager.authenticate()` 를 호출한다. `DaoAuthenticationProvider` 가 `UserDetailsService.loadUserByUsername()` 으로 사용자 정보를 조회한 뒤 `PasswordEncoder.matches()` 로 비밀번호를 검증한다.

```java
@Bean
PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // {bcrypt} 등 접두사 기반
}
```

`DelegatingPasswordEncoder` 는 해시 앞에 `{bcrypt}`, `{argon2}` 같은 알고리즘 식별자를 붙여 저장하므로, 나중에 알고리즘을 교체해도 기존 해시와 공존한다.

## 4. SecurityContextHolder 와 저장 전략

인증에 성공하면 결과 `Authentication` 은 `SecurityContext` 에 담겨 `SecurityContextHolder` 에 보관된다. 기본 전략은 `MODE_THREADLOCAL` 로, 현재 스레드에 인증 정보를 묶는다.

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();
```

`ThreadLocal` 저장의 함정은 스레드가 바뀌면 정보가 사라진다는 것이다. `@Async` 로 다른 스레드에 작업을 넘기거나, WebFlux 처럼 요청이 여러 스레드를 오가면 컨텍스트가 유실된다. 자식 스레드에 전파하려면 `MODE_INHERITABLETHREADLOCAL` 을 쓰거나, `DelegatingSecurityContextExecutor` 로 실행자를 감싸야 한다.

```java
@Bean
Executor securityAwareExecutor() {
    return new DelegatingSecurityContextExecutor(Executors.newFixedThreadPool(4));
}
```

Spring Security 6 부터는 명시적 저장(`SecurityContextRepository.saveContext`)이 기본이 되어, 인증 필터가 성공 후 저장소에 직접 써야 한다. 이 변경 때문에 커스텀 인증 필터를 6 으로 올릴 때 "인증은 됐는데 다음 요청에서 로그인이 풀리는" 회귀가 자주 발생한다.

## 5. 인가: AuthorizationManager 로의 통합

인증이 "누구인가"라면 인가는 "이 자원에 접근할 권한이 있는가"다. Spring Security 6 는 과거의 `AccessDecisionManager`/`Voter` 구조를 폐기하고 `AuthorizationManager` 인터페이스로 단일화했다. `AuthorizationFilter` 가 요청마다 `AuthorizationManager.check()` 를 호출해 접근 결정을 내린다.

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/admin/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("SCOPE_write")
    .requestMatchers("/public/**").permitAll()
    .anyRequest().authenticated());
```

`hasRole('ADMIN')` 은 실제로 `ROLE_ADMIN` 권한을 요구하며, `hasAuthority` 는 접두사 없이 정확한 문자열을 본다. 이 `ROLE_` 접두사 처리 차이가 초심자의 흔한 실수다.

| 표현식 | 검사 대상 권한 | 접두사 처리 |
|---|---|---|
| `hasRole('ADMIN')` | `ROLE_ADMIN` | 자동으로 `ROLE_` 부착 |
| `hasAuthority('ROLE_ADMIN')` | `ROLE_ADMIN` | 문자열 그대로 |
| `hasAuthority('SCOPE_read')` | `SCOPE_read` | OAuth2 스코프 등 |

## 6. 예외 처리와 인증 진입점

인가 실패나 미인증 접근은 예외로 표현되며 `ExceptionTranslationFilter` 가 이를 잡아 처리한다. 미인증 사용자가 보호된 자원에 접근하면 `AuthenticationException` 이 던져지고, 필터는 `AuthenticationEntryPoint` 를 호출해 로그인 페이지로 보내거나(폼 로그인) 401 을 반환한다(REST API). 이미 인증됐지만 권한이 부족하면 `AccessDeniedException` 이 발생하고 `AccessDeniedHandler` 가 403 을 응답한다.

```java
http.exceptionHandling(ex -> ex
    .authenticationEntryPoint((req, res, e) -> res.sendError(401))
    .accessDeniedHandler((req, res, e) -> res.sendError(403)));
```

401 은 재인증을, 403 은 권한 상승을 의미하도록 명확히 나눠야 클라이언트 재시도 로직이 꼬이지 않는다.

## 7. 무상태(stateless) JWT 인증의 위치

토큰 기반 API 는 `SessionCreationPolicy.STATELESS` 로 설정하고, 매 요청 `Authorization: Bearer <token>` 헤더를 검증하는 커스텀 필터를 `OncePerRequestFilter` 로 추가한다. 이 필터는 토큰을 파싱·검증해 인증 객체를 만들고 홀더에 직접 넣는다.

```java
public class JwtAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Authentication auth = jwtParser.toAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }
}
```

이 필터는 `UsernamePasswordAuthenticationFilter` 앞에 두어(`http.addFilterBefore(...)`) 인증이 인가 판정 전에 완료되도록 한다. 무상태 구조에서는 서버가 토큰을 능동적으로 무효화할 수 없다는 trade-off 가 있어, 짧은 만료 시간과 리프레시 토큰, 또는 블랙리스트(Redis)를 병행한다.

## 8. 필터 순서와 디버깅 실전

Security 필터 체인의 순서를 확인하려면 `logging.level.org.springframework.security=DEBUG` 를 켜면 요청이 어느 체인의 어떤 필터를 거쳤는지 전체 경로가 출력된다. 문제가 "필터가 실행되지 않는" 유형이면 대개 `RequestMatcher` 우선순위나 `permitAll` 범위가 원인이고, "인증은 되는데 다음 요청에 풀리는" 유형이면 Security 6 의 명시적 `SecurityContextRepository` 저장 누락이 원인이다. 진입점은 하나의 `DelegatingFilterProxy`, 그 뒤로 `FilterChainProxy` 가 다중 체인을 라우팅하고, 각 체인 안에서 인증 필터가 `AuthenticationManager` 로 자격을 검증한 뒤 `AuthorizationFilter` 가 `AuthorizationManager` 로 접근을 판정하는 계층이 Spring Security 아키텍처의 골격이다.

## 참고

- Spring Security Reference — Architecture / Servlet Security (https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- Spring Security Reference — Authentication / AuthenticationManager, ProviderManager
- Spring Security Reference — Authorization / AuthorizationManager
- Spring Security 6 Migration Guide — SecurityContextRepository 명시적 저장
