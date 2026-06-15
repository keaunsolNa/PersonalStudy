Notion 원본: https://www.notion.so/3805a06fd6d381a98660fd605dbe3093

# Spring Security 6 SecurityFilterChain과 인증/인가 아키텍처

> 2026-06-15 신규 주제 · 확장 대상: Spring

## 학습 목표

- DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain으로 이어지는 요청 위임 구조를 설명한다
- 컴포넌트 기반 람다 DSL 설정을 작성하고 `WebSecurityConfigurerAdapter` 제거 이후의 차이를 적용한다
- AuthenticationManager·AuthenticationProvider·SecurityContext의 협력으로 인증 흐름을 추적한다
- 메서드 보안과 AuthorizationManager 기반 인가의 동작 시점을 구분한다

## 1. 전체 요청 흐름: 서블릿 필터에서 시작한다

Spring Security는 서블릿 필터 위에 구축된다. 모든 HTTP 요청은 서블릿 컨테이너의 필터 체인을 통과하며, Spring Security는 여기에 `DelegatingFilterProxy` 하나를 등록한다. 이 프록시는 자체 로직이 없고 스프링 ApplicationContext에서 `springSecurityFilterChain`(`FilterChainProxy`)을 찾아 위임한다. 이 간접 계층 덕분에 서블릿 컨테이너와 스프링 빈의 생명주기가 분리된다. `FilterChainProxy`는 여러 `SecurityFilterChain` 중 요청 URL에 매칭되는 첫 번째 체인을 선택한다. 더 구체적인 매처를 가진 체인을 먼저 선언해야 한다.

## 2. SecurityFilterChain 내부의 필터 순서

앞쪽부터 `SecurityContextHolderFilter`(이전 컨텍스트 로드), `HeaderWriterFilter`, `CorsFilter`, `CsrfFilter`, `LogoutFilter`, 인증 필터들, `ExceptionTranslationFilter`, 마지막에 `AuthorizationFilter`다. `ExceptionTranslationFilter`가 인가 필터 바로 앞에 있어, 인증 안 됨 → 401/로그인, 권한 없음 → 403으로 분기해 예외를 HTTP 응답으로 번역한다.

## 3. 람다 DSL 설정 (Spring Security 6)

Spring Security 6는 `WebSecurityConfigurerAdapter`를 완전히 제거했다. 이제 `SecurityFilterChain`을 빈으로 직접 등록하고 모든 설정이 람다 DSL을 강제한다.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/public/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("SCOPE_write")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
```

`authorizeHttpRequests`는 구버전 `authorizeRequests`(deprecated)를 대체하며 내부적으로 `AuthorizationFilter` + `AuthorizationManager` 기반으로 재구현했다. 넓은 `anyRequest()`를 반드시 마지막에 둔다.

## 4. 다중 SecurityFilterChain 분리

```java
@Bean @Order(1)
public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
}
```

`securityMatcher`는 "이 체인이 어떤 요청을 책임하는가", `requestMatchers`는 "그 안에서 어떤 권한이 필요한가"를 정한다. 둘을 혼동하면 의도와 다른 체인이 선택된다.

## 5. 인증 아키텍처: Authentication과 Provider

인증 전에는 제출된 자격증명을, 후에는 인증된 주체와 권한을 담는다. `ProviderManager`가 여러 `AuthenticationProvider`를 순회하며 폼 로그인은 `DaoAuthenticationProvider`가 `UserDetailsService`로 사용자를 로드하고 `PasswordEncoder`로 검증한다.

```java
@Service
public class JpaUserDetailsService implements UserDetailsService {
    private final MemberRepository memberRepository;
    public JpaUserDetailsService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }
    @Override
    public UserDetails loadUserByUsername(String username) {
        Member member = memberRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.builder()
            .username(member.getUsername())
            .password(member.getEncodedPassword())
            .authorities(member.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())
            .build();
    }
}
```

`hasRole("ADMIN")`은 내부적으로 `ROLE_ADMIN` 권한을 확인하므로 권한에 `ROLE_` 접두사를 붙여 저장해야 한다.

## 6. SecurityContext와 컨텍스트 전파

`SecurityContextHolder`는 기본적으로 `ThreadLocal`에 컨텍스트를 저장한다. `@Async`나 리액티브 환경에서는 전파되지 않아 `DelegatingSecurityContextExecutor`로 명시 위임한다. 6에서는 `SecurityContextPersistenceFilter`가 `SecurityContextHolderFilter`로 대체되면서 세션 저장이 명시적(`requireExplicitSave=true`)으로 바뀜어, 커스텀 인증 구현 시 `securityContextRepository.saveContext(...)`를 직접 호출하지 않으면 인증이 유지되지 않는 함정이 생긴다.

## 7. 메서드 보안과 AuthorizationManager

`@EnableMethodSecurity`(6.x)로 활성화한다.

```java
@Service
public class OrderService {
    @PreAuthorize("hasRole('ADMIN') or #order.ownerId == authentication.name")
    public void cancel(Order order) { }
    @PostAuthorize("returnObject.ownerId == authentication.name")
    public Order findById(Long id) { return orderRepository.findById(id).orElseThrow(); }
}
```

`@PreAuthorize`는 실행 전, `@PostAuthorize`는 반환 객체 기준으로 평가된다. AOP 프록시 기반이므로 같은 클래스 내 self-invocation은 보안이 적용되지 않는다. 6은 인가 전반을 `AuthorizationManager<T>`로 통일해 URL 인가와 메서드 인가가 같은 추상 위에서 동작한다.

## 8. 테스트와 trade-off

```java
@WebMvcTest(AdminController.class)
class AdminControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @Test @WithMockUser(roles = "USER")
    void userCannotAccessAdmin() throws Exception {
        mockMvc.perform(get("/admin/dashboard")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles = "ADMIN")
    void adminCanAccessAdmin() throws Exception {
        mockMvc.perform(get("/admin/dashboard")).andExpect(status().isOk());
    }
}
```

다중 체인은 관심사를 분리하지만 매처 우선순위 실수가 보안 구멍으로 직결된다. STATELESS 세션은 수평 확장에 유리하나 즉시 로그아웃을 포기해 토큰 블랙리스트 같은 별도 메커니즘이 필요하다. 메서드 보안은 세밀한 규칙을 표현하나 AOP 한계와 SpEL 평가 비용을 감수한다. 가장 흔한 실수는 `permitAll`을 너무 넓게 잡거나 `anyRequest()`를 위에 두는 것이며, 위와 같은 인가 테스트가 이를 잡아낸다.

## 참고

- Spring Security Reference, "Architecture": https://docs.spring.io/spring-security/reference/servlet/architecture.html
- Spring Security Reference, "Authorize HttpServletRequests": https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
- Spring Security Reference, "Method Security": https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Spring Security Migration: https://docs.spring.io/spring-security/reference/migration-7/index.html
