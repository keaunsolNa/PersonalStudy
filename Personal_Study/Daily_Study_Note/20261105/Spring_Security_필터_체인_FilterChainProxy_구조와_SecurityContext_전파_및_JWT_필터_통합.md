Notion 원본: https://app.notion.com/p/40f5a06fd6d3813d82efea22b7646a37

# Spring Security 필터 체인 FilterChainProxy 구조와 SecurityContext 전파 및 JWT 필터 통합

> 2026-11-05 신규 주제 · 확장 대상: Spring

## 학습 목표

- DelegatingFilterProxy에서 FilterChainProxy, SecurityFilterChain으로 이어지는 요청 경로를 추적한다
- Spring Security 6의 SecurityContextHolderFilter와 명시적 컨텍스트 저장 규칙을 적용한다
- JWT 인증 필터를 올바른 위치에 삽입하고 예외 처리 경로를 구성한다
- 비동기 · 멀티스레드 환경에서 SecurityContext 전파 방법을 선택한다

## 1. 서블릿 컨테이너와 Spring Security의 접점

서블릿 컨테이너는 Spring Bean을 모른다. 그래서 web.xml이나 Boot의 FilterRegistrationBean으로 등록된 `DelegatingFilterProxy`가 이름이 `springSecurityFilterChain`인 Bean에 요청을 위임한다. 이 Bean의 실체가 `FilterChainProxy`이며, 하나 이상의 `SecurityFilterChain`을 보관한다. FilterChainProxy는 요청마다 등록된 체인을 순서대로 보고 `RequestMatcher`가 처음으로 일치하는 체인 하나만 선택해 그 체인의 필터들을 실행한다. 따라서 체인이 여러 개일 때 순서(`@Order`)와 매처 범위가 곧 정책이다. 범위가 넓은 체인이 앞서면 뒤 체인은 호출되지 않는다.

```java
@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean @Order(1)
  SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    return http
      .securityMatcher("/api/**")
      .csrf(csrf -> csrf.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a.anyRequest().authenticated())
      .build();
  }

  @Bean @Order(2)
  SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    return http
      .authorizeHttpRequests(a -> a
        .requestMatchers("/login", "/assets/**").permitAll()
        .anyRequest().authenticated())
      .formLogin(Customizer.withDefaults())
      .build();
  }
}
```

## 2. 기본 필터 순서

체인 안에서 필터 순서는 `FilterOrderRegistration`에 정의되어 있다. 주요 필터를 요청 흐름 순으로 보면 다음과 같다. `DisableEncodeUrlFilter`, `WebAsyncManagerIntegrationFilter`, `SecurityContextHolderFilter`, `HeaderWriterFilter`, `CorsFilter`, `CsrfFilter`, `LogoutFilter`, `UsernamePasswordAuthenticationFilter`, `BasicAuthenticationFilter`, `RequestCacheAwareFilter`, `SecurityContextHolderAwareRequestFilter`, `AnonymousAuthenticationFilter`, `SessionManagementFilter`, `ExceptionTranslationFilter`, `AuthorizationFilter`. 인증을 시도하는 필터들이 앞에 있고, 마지막의 `AuthorizationFilter`가 `authorizeHttpRequests` 규칙을 평가한다. Spring Security 6 이전의 `FilterSecurityInterceptor`는 AuthorizationFilter로 대체되었다.

`ExceptionTranslationFilter`는 자신보다 뒤에서 던져진 `AuthenticationException`과 `AccessDeniedException`을 잡아 각각 `AuthenticationEntryPoint`와 `AccessDeniedHandler`로 넘긴다. 이 필터보다 앞에 놓인 사용자 정의 필터에서 던진 예외는 이 변환을 받지 못하므로, JWT 필터를 앞에 두면 직접 응답을 써야 한다.

## 3. SecurityContext와 SecurityContextHolderFilter

인증 결과는 `SecurityContext` 안의 `Authentication`에 담기고 `SecurityContextHolder`가 이를 보관한다. 기본 전략은 `MODE_THREADLOCAL`이라 요청 처리 스레드에만 보인다. Spring Security 5까지의 `SecurityContextPersistenceFilter`는 요청 종료 시 컨텍스트를 자동으로 저장했지만, 6에서 도입된 `SecurityContextHolderFilter`는 `SecurityContextRepository`에서 컨텍스트를 로드하기만 하고 자동 저장하지 않는다. 따라서 직접 인증을 구현한 필터나 컨트롤러가 세션에 로그인 상태를 남기려면 저장을 명시해야 한다. 무상태 API에서는 저장하지 않는 편이 맞다.

```java
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(authentication);
SecurityContextHolder.setContext(context);
// 세션 기반 로그인을 유지해야 할 때만 명시적으로 저장
new HttpSessionSecurityContextRepository().saveContext(context, request, response);
```

전역 `SecurityContextHolder.getContext().setAuthentication(...)`만 호출하고 요청이 끝나면 다음 요청에서 인증이 사라지는 증상이 6 이상으로 올린 뒤 흔히 나타나는 이유가 이것이다. 스레드 풀 스레드를 재사용하므로 컨텍스트를 `clearContext()`로 정리하지 않으면 다른 요청에 인증이 새어 나갈 위험이 있고, 표준 필터들은 이를 처리하지만 직접 만든 스레드 코드에서는 개발자의 책임이다.

## 4. JWT 인증 필터 삽입

JWT는 형식 검증과 서명 검증이 끝나면 `Authentication`을 만들어 컨텍스트에 넣는 필터로 구현한다. `OncePerRequestFilter`를 상속하면 forward 등으로 인한 중복 실행을 막을 수 있고, 위치는 보통 `UsernamePasswordAuthenticationFilter` 앞(`addFilterBefore`)이다. 주의점은 필터 Bean이 `@Component`이면 서블릿 컨테이너에도 자동 등록되어 두 번 실행되므로 `FilterRegistrationBean`으로 비활성화하거나 Bean으로 노출하지 않는 것이다.

```java
class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtVerifier verifier;
  JwtAuthFilter(JwtVerifier verifier) { this.verifier = verifier; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                  FilterChain chain) throws ServletException, IOException {
    String header = req.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) {
      try {
        Authentication auth = verifier.verify(header.substring(7));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
      } catch (JwtException e) {
        SecurityContextHolder.clearContext();
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }
    chain.doFilter(req, res);
  }
}

// 체인 구성
http.addFilterBefore(new JwtAuthFilter(verifier), UsernamePasswordAuthenticationFilter.class);
```

서명 검증 로직을 직접 쓰기보다 `spring-security-oauth2-resource-server`의 `oauth2ResourceServer(o -> o.jwt(...))`를 쓰면 `BearerTokenAuthenticationFilter`와 `JwtDecoder`를 표준 방식으로 얻는다. 직접 구현은 커스텀 토큰 포맷이 필요할 때만 정당화된다.

## 5. 인가: URL 규칙과 메서드 보안

`authorizeHttpRequests`는 선언 순서대로 첫 일치 규칙이 적용되므로 구체적인 패턴을 앞에 둔다. `requestMatchers("/admin/**").hasRole("ADMIN")`에서 `hasRole`은 내부적으로 `ROLE_` 접두사를 붙이고, `hasAuthority`는 문자열을 그대로 비교한다. 메서드 수준 규칙은 `@EnableMethodSecurity`를 켜고 `@PreAuthorize("hasRole('ADMIN') or #id == authentication.name")`처럼 SpEL로 쓴다. 이는 AOP 프록시 기반이므로 같은 클래스 내부 호출(self-invocation)에는 적용되지 않는다. 트랜잭션 `@Transactional`과 동일한 함정이다.

## 6. 스레드 경계에서의 전파

ThreadLocal 전략은 `@Async`, `CompletableFuture`, 직접 만든 ExecutorService로 넘어가면 컨텍스트가 사라진다. 해결 방법은 세 가지다. `DelegatingSecurityContextExecutor`나 `DelegatingSecurityContextRunnable`로 작업을 감싸 제출 시점의 컨텍스트를 복사한다. Spring의 `@Async`는 `DelegatingSecurityContextAsyncTaskExecutor`로 TaskExecutor를 감싼다. 또는 인증 정보가 필요한 값만 파라미터로 넘겨 컨텍스트 의존을 끊는다. `MODE_INHERITABLETHREADLOCAL`은 풀 스레드 재사용과 맞지 않아 권장되지 않는다.

```java
ExecutorService delegate = Executors.newFixedThreadPool(4);
ExecutorService secured = new DelegatingSecurityContextExecutorService(delegate);
secured.submit(() -> {
  Authentication a = SecurityContextHolder.getContext().getAuthentication();
  // 제출 스레드의 인증이 보인다
});
```

## 7. 디버깅

`logging.level.org.springframework.security=DEBUG`로 요청마다 실행된 필터 목록과 선택된 체인을 볼 수 있다. 특정 URL이 어느 체인에 속하는지 헷갈릴 때 가장 빠른 방법이다. 테스트에서는 `spring-security-test`의 `@WithMockUser`, `SecurityMockMvcRequestPostProcessors.jwt()`를 사용해 MockMvc 요청에 인증을 주입하고, 인증 실패(401)와 인가 실패(403)를 각각 검증한다.

## 참고

- Spring Security Reference — Servlet Architecture, Authorization, OAuth2 Resource Server
- Spring Security 6.0 Migration Guide — Session Management, Save SecurityContext Explicitly
- Spring Security API — FilterOrderRegistration
