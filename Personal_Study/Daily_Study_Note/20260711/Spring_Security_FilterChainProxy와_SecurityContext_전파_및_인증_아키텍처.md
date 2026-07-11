Notion 원본: https://app.notion.com/p/39a5a06fd6d381e6a76ee8e486db1d45

# Spring Security FilterChainProxy와 SecurityContext 전파 및 인증 아키텍처

> 2026-07-11 신규 주제 · 확장 대상: Spring (트랜잭션·WebFlux 학습됨)

## 학습 목표

- FilterChainProxy와 SecurityFilterChain의 위임 구조를 설명한다
- 인증 처리 흐름을 AuthenticationManager·Provider·Filter 단위로 분해한다
- SecurityContextHolder의 전략과 스레드 전파 문제를 판단한다
- 스테이트리스(JWT) 구성에서 세션·컨텍스트 저장 정책을 설계한다

## 1. 서블릿 필터 위임 구조

Spring Security의 진입점은 서블릿 컨테이너에 등록된 단일 필터 `DelegatingFilterProxy`다. 이 프록시는 이름이 `springSecurityFilterChain`인 스프링 빈, 즉 `FilterChainProxy`에게 요청을 위임한다. 컨테이너의 생명주기와 스프링 컨텍스트의 생명주기를 분리하기 위한 다리 역할이다.

`FilterChainProxy`는 다시 여러 개의 `SecurityFilterChain`을 들고 있다. 각 체인은 `RequestMatcher`와 `List<Filter>`의 쌍이다. 요청이 들어오면 등록된 체인을 순서대로 확인해 첫 번째로 매칭되는 체인 하나만 실행한다. 여러 체인이 매칭되어도 최초 매칭 체인만 적용되므로 체인 등록 순서가 곧 우선순위다.

```java
@Bean
@Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
  http.securityMatcher("/api/**")
      .authorizeHttpRequests(a -> a.anyRequest().authenticated())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
  return http.build();
}

@Bean
@Order(2)
SecurityFilterChain webChain(HttpSecurity http) throws Exception {
  http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
      .formLogin(Customizer.withDefaults());
  return http.build();
}
```

`@Order`로 체인 우선순위를 명시한다. `/api/**`가 먼저 매칭되면 그 체인만 돌고, 그렇지 않으면 두 번째 체인이 처리한다. 특정 경로를 보안에서 완전히 제외하려면 그 경로만 매칭하는 필터가 비어 있는 체인을 앞순위로 두거나 `web.ignoring()`을 쓴다.

## 2. 핵심 필터의 역할 분해

한 `SecurityFilterChain` 안에는 10여 개의 필터가 정해진 순서로 배치된다. 순서는 `FilterOrderRegistration`이 관리한다. 주요 필터의 책임은 다음과 같다.

| 필터 | 책임 |
|------|------|
| SecurityContextHolderFilter | 저장소에서 SecurityContext 로드·정리 |
| CsrfFilter | CSRF 토큰 검증 |
| UsernamePasswordAuthenticationFilter | 폼 로그인 자격증명 처리 |
| BearerTokenAuthenticationFilter | Authorization 헤더의 토큰 처리 |
| ExceptionTranslationFilter | 인증/인가 예외를 HTTP 응답으로 변환 |
| AuthorizationFilter | 최종 접근 결정(authorizeHttpRequests) |

필터 순서가 의미를 갖는다. `ExceptionTranslationFilter`는 뒤쪽 `AuthorizationFilter`가 던지는 `AccessDeniedException`·`AuthenticationException`을 잡아 로그인 리다이렉트나 401/403 응답으로 바꾼다. 그래서 예외 변환 필터는 인가 필터보다 앞에 있어야 감쌀 수 있다. 커스텀 필터를 넣을 때 `addFilterBefore`/`addFilterAfter`로 기준 필터를 정확히 지정해야 하는 이유다.

## 3. 인증 처리 파이프라인

인증은 세 계층으로 나뉜다. 필터가 요청에서 자격증명을 뽑아 `Authentication`(미인증) 객체를 만들고, `AuthenticationManager`에게 넘긴다. 기본 구현 `ProviderManager`는 여러 `AuthenticationProvider`를 순회하며 해당 토큰 타입을 지원하는 provider에게 위임한다. Provider가 실제 검증을 수행해 인증된 `Authentication`을 돌려준다.

```java
UsernamePasswordAuthenticationToken token =
    UsernamePasswordAuthenticationToken.unauthenticated(username, password);
Authentication result = authenticationManager.authenticate(token);
SecurityContextHolder.getContext().setAuthentication(result);
```

`ProviderManager`는 `supports(tokenClass)`가 true인 첫 provider의 결과를 채택한다. 대표 provider인 `DaoAuthenticationProvider`는 `UserDetailsService`로 사용자를 조회하고 `PasswordEncoder`로 비밀번호를 대조한다. 검증 성공 시 권한(`GrantedAuthority`)이 채워진 인증 객체를 반환하고, 실패 시 `BadCredentialsException` 등을 던진다.

Provider 체인은 한 provider가 인증을 성공시키면 종료되고, 모두 실패하면 마지막 예외가 전파된다. `ProviderManager`는 부모 `AuthenticationManager`를 가질 수 있어 계층적 인증 구성을 지원한다.

## 4. SecurityContext와 저장 전략

인증 결과는 `SecurityContext`에 담기고, `SecurityContextHolder`가 이를 보관한다. 보관 전략은 세 가지다. 기본은 `MODE_THREADLOCAL`로 스레드 로컬에 저장한다. 자식 스레드에 상속시키려면 `MODE_INHERITABLETHREADLOCAL`, 전역 단일 컨텍스트가 필요한 데스크톱형 앱이면 `MODE_GLOBAL`을 쓴다.

```java
// 애플리케이션 시작 시 전략 지정
SecurityContextHolder.setStrategyName(
    SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
```

요청 사이의 컨텍스트 유지는 `SecurityContextRepository`가 담당한다. 세션 기반이면 `HttpSessionSecurityContextRepository`가 세션에 컨텍스트를 저장·복원한다. Spring Security 6부터 컨텍스트 로드는 `SecurityContextHolderFilter`가 지연 로딩으로 처리하고, 저장은 명시적으로 이뤄진다. 과거의 `SecurityContextPersistenceFilter`가 자동 저장하던 것과 달라진 부분으로, 커스텀 인증 후에는 `securityContextRepository.saveContext(context, request, response)`를 직접 호출해야 세션에 반영된다.

## 5. 스레드 전파 문제

`ThreadLocal` 저장이라 비동기·병렬 처리에서 컨텍스트가 유실된다. `@Async` 메서드나 병렬 스트림, 별도 스레드풀에서 실행되는 작업은 부모 스레드의 `SecurityContext`를 보지 못한다. 해결책은 컨텍스트를 명시적으로 전파하는 것이다.

```java
// Executor에 SecurityContext 전파 래핑
Executor delegate = Executors.newFixedThreadPool(4);
Executor secured = new DelegatingSecurityContextExecutor(delegate);

// 또는 개별 작업 래핑
Runnable task = new DelegatingSecurityContextRunnable(() -> {
  Authentication a = SecurityContextHolder.getContext().getAuthentication();
  // a는 원본 스레드의 인증을 유지
});
```

`DelegatingSecurityContext*` 계열은 작업 제출 시점의 컨텍스트를 캡처해 실행 스레드에 심고, 종료 후 정리한다. WebFlux(리액티브)에서는 `ThreadLocal`이 무의미하므로 `ReactiveSecurityContextHolder`가 Reactor Context를 통해 컨텍스트를 전파한다. 서블릿과 리액티브의 전파 메커니즘이 근본적으로 다르다는 점이 이전에 학습한 WebFlux Context 전파와 연결된다.

## 6. 스테이트리스 JWT 구성

REST API는 세션 대신 토큰을 쓴다. `SessionCreationPolicy.STATELESS`로 세션 생성을 막고, 요청마다 토큰에서 인증을 복원한다. 이 경우 `SecurityContext`는 요청 스코프로만 존재하고 세션에 저장되지 않는다.

```java
public class JwtAuthFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest req,
      HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      Authentication auth = jwtProvider.validateAndBuild(token);
      SecurityContext ctx = SecurityContextHolder.createEmptyContext();
      ctx.setAuthentication(auth);
      SecurityContextHolder.setContext(ctx);
    }
    chain.doFilter(req, res);
  }
}
```

`OncePerRequestFilter`를 상속해 요청당 한 번만 실행되게 하고, 인증 성공 시 새 컨텍스트를 만들어 홀더에 넣는다. 스테이트리스에서는 저장소에 쓸 필요가 없으므로 `saveContext`를 호출하지 않는다. 다만 `SecurityContextHolder.createEmptyContext()`로 새 인스턴스를 만들어야 스레드 간 오염을 막는다. 공유 정적 컨텍스트를 재사용하면 동시 요청에서 인증이 뒤섞일 수 있다.

## 7. 트레이드오프와 성능

세션 기반은 서버가 상태를 갖는 대신 토큰 무효화·권한 변경 반영이 즉시 가능하다. 스테이트리스 JWT는 확장성이 좋지만 토큰 폐기가 어렵다. 만료 전 강제 로그아웃을 지원하려면 블랙리스트 저장소(Redis 등)가 필요해 결국 부분적 상태를 갖게 된다. 이 지점에서 순수 스테이트리스의 이점이 상쇄된다.

| 방식 | 상태 | 확장성 | 즉시 무효화 |
|------|------|--------|-------------|
| 세션 | 서버 보관 | 세션 공유 필요 | 가능 |
| JWT 스테이트리스 | 무상태 | 우수 | 어려움 |
| JWT + 블랙리스트 | 부분 상태 | 중간 | 가능 |

성능 측면에서 `DaoAuthenticationProvider`의 `PasswordEncoder`는 의도적으로 느리다. BCrypt·Argon2는 계산 비용이 커 무차별 대입을 방어하는데, 로그인 처리량이 높으면 이 비용이 병목이 된다. 반대로 JWT 검증은 서명 확인만 하므로 요청당 비용이 훨씬 작아, 로그인은 무겁고 이후 API 호출은 가벼운 비대칭 구조가 자연스럽다.

## 8. 실전 설계 지침

인증 필터를 커스텀할 때는 기존 필터와의 순서를 명확히 한다. JWT 필터는 보통 `UsernamePasswordAuthenticationFilter` 앞에 두어 폼 로그인 경로와 분리한다. 인증 실패 응답 형식을 통일하려면 `AuthenticationEntryPoint`와 `AccessDeniedHandler`를 구현해 `ExceptionTranslationFilter`에 연결한다. 이렇게 하면 401(미인증)과 403(권한 없음)을 일관된 JSON 오류 바디로 반환할 수 있다.

메서드 수준 보안(`@PreAuthorize`)은 필터가 아니라 AOP 프록시로 동작한다. 필터 체인을 통과한 뒤 서비스 계층에서 다시 권한을 검사하므로, 필터의 URL 기반 인가와 메서드 기반 인가를 이중으로 설계하면 방어가 깊어진다. 단 `@PreAuthorize`는 `SecurityContextHolder`에 의존하므로, 앞서 설명한 비동기 전파 문제가 그대로 적용된다. 비동기 서비스에 메서드 보안을 걸려면 컨텍스트 전파 Executor를 반드시 함께 구성해야 한다.

## 참고

- Spring Security Reference, "Architecture" (docs.spring.io/spring-security/reference/servlet/architecture.html)
- Spring Security Reference, "Authentication" 및 "SecurityContextHolder"
- spring-projects/spring-security `FilterChainProxy`, `ProviderManager` 소스
- Spring Security 6 마이그레이션 가이드 (SecurityContext 변경 사항)
- "Spring Security in Action", Laurentiu Spilca
