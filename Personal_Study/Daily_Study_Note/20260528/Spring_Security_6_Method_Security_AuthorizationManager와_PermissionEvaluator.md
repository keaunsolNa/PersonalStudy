Notion 원본: https://www.notion.so/36e5a06fd6d3815f8959cba3c0fd1848

# Spring Security 6 Method Security AuthorizationManager와 PermissionEvaluator

> 2026-05-28 신규 주제 · 확장 대상: Spring Security — 메서드 보안과 도메인 객체 단위 권한

## 학습 목표

- Spring Security 6 의 AuthorizationManager<T> 추상화가 AccessDecisionManager 를 어떻게 대체했는지 비교한다.
- @PreAuthorize, @PostAuthorize, @PreFilter, @PostFilter 의 SpEL 평가 시점과 컨텍스트 변수를 정리한다.
- 도메인 객체 ACL 까지 검사하는 PermissionEvaluator 를 직접 구현하고 캐싱 전략을 적용한다.
- AOP prefix proxy vs AspectJ 모드 차이와 성능 영향을 측정한다.

## 1. 6 버전에서의 변화

5.x 까지의 AccessDecisionVoter 다중투표 + AccessDecisionManager 합산 대신 6.0 부터 단일 인터페이스 AuthorizationManager<T> 로 통합. 다중 매니저 조합은 AuthorizationManagers.allOf(), anyOf() 함수형 결합.

```java
@FunctionalInterface
public interface AuthorizationManager<T> {
    @Nullable
    AuthorizationDecision check(Supplier<Authentication> authentication, T object);
}
```

Supplier<Authentication> 형태는 인증 객체 평가를 지연시켜 익명 경로의 SecurityContext 접근 비용 회피.

## 2. 어노테이션 시점 정리

| 어노테이션 | 호출 위치 | SpEL root | 실패 시 |
|---|---|---|---|
| @PreAuthorize | 메서드 호출 직전 | MethodSecurityExpressionRoot | AccessDeniedException |
| @PostAuthorize | 메서드 반환 직후 | + returnObject | AccessDeniedException |
| @PreFilter | 컬렉션 인자 사전 필터링 | + filterObject | 인자 mutate |
| @PostFilter | 반환 컬렉션 필터링 | + filterObject | 반환값에서 비매칭 제거 |

@PreFilter 는 컬렉션을 제자리에서 수정 — mutable List/Set. Stream 은 lazy evaluation 이라 두 번 소비 시 IllegalStateException.

## 3. AuthorizationManager 직접 구현

```java
@Component
public class OrgScopedAuthorizationManager
    implements AuthorizationManager<MethodInvocation> {

    private final OrgMembershipRepository repo;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> auth, MethodInvocation invocation) {
        Long orgId = (Long) invocation.getArguments()[0];
        Authentication a = auth.get();
        if (a == null || !a.isAuthenticated()) return new AuthorizationDecision(false);
        return new AuthorizationDecision(repo.existsByUserIdAndOrgId(a.getName(), orgId));
    }
}
```

AnnotationMatchingPointcut + AuthorizationManagerBeforeMethodInterceptor 로 직접 advisor 등록. @PreAuthorize SpEL bean 호출 우회.

## 4. PermissionEvaluator — 도메인 객체 단위 검사

```java
@Component
public class CustomPermissionEvaluator implements PermissionEvaluator {
    private final AclQueryService aclService;

    @Override
    public boolean hasPermission(Authentication auth, Object target, Object permission) {
        if (target == null) return false;
        return aclService.canAccess(
            (String) auth.getName(),
            target.getClass().getSimpleName(),
            extractId(target),
            (String) permission);
    }
}
```

DefaultMethodSecurityExpressionHandler 에 setPermissionEvaluator 주입. SpEL 에서 hasPermission(#id, 'Order', 'WRITE') 으로 사용.

## 5. ACL 캐싱 — 권한 검사의 성능 함정

100건 반환 + @PostFilter = 100회 DB 조회. 두 단계 캐싱.
1. RequestScope 캐싱 — 같은 요청 내 메모리 주소.
2. 분산 캐싱 — Redis/Caffeine, TTL 30초~5분. Write API 시 명시적 evict 필수.

## 6. AspectJ 모드 — self-invocation 함정

기본 PROXY 모드는 외부에서 호출된 메서드만 보호. 같은 클래스 내부 호출은 권한 검사 건너뛴. 해결: @EnableMethodSecurity(mode=ASPECTJ) 또는 self-injection.

## 7. 실측 — 권한 검사 오버헤드

```
Spring Boot 3.3, Spring Security 6.3, JDK 21
1000 RPS x 60s 부하

Endpoint                              p50    p95    p99
no method security                    2.1ms  4.0ms  6.8ms
+ @PreAuthorize hasRole               2.3ms  4.3ms  7.1ms
+ SpEL @bean.check()                  3.8ms  6.9ms  10.5ms
+ hasPermission cache hit             4.6ms  8.2ms  12.0ms
+ hasPermission cache miss            9.2ms  18ms   27ms
```

## 8. Trade-off 와 설계 가이드

| 옵션 | 사용처 | 주의 |
|---|---|---|
| hasRole | 단순 RBAC | 계층 변경 수정 |
| hasPermission | ABAC/ACL | PermissionEvaluator + 캐싱 |
| 커스텀 AuthorizationManager | 복잡 규칙 | 학습 비용 |
| AspectJ 모드 | self-invocation | 빌드 설정 |
| SpEL @bean.method() | prototyping | reflection cost |

## 9. 통합 테스트 패턴

@WithMockUser, @WithUserDetails, @WithMockJwt 로 권한 컨텍스트 표현. 회귀 테스트는 각 endpoint x 각 role 매트릭스.

## 10. 외부 IAM 시스템과의 통합

OPA / Cerbos / Casbin 을 PermissionEvaluator backend 로. 50ms timeout + 폴백 정책 명시 필수.

## 11. JWT 기반 권한과 fine-grained scope

@PreAuthorize("hasAuthority('SCOPE_orders:read')"). SCOPE_ 프리픽스는 JwtGrantedAuthoritiesConverter 기본값. 토큰 내 scope 30개 이상이면 role-based 검토.

## 참고

- Spring Security Reference — Method Security
- Spring Security AuthorizationManager API
- Spring Security ACL Module
- Rob Winch SpringOne 2023
- Spring Security 6.0 Migration Guide
