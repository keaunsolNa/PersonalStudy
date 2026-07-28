Notion 원본: https://app.notion.com/p/3ab5a06fd6d381ea8402fd2796b8408b

# Spring Data JPA N+1 문제와 페치 조인 및 EntityGraph 배치 페칭

> 2026-07-28 신규 주제 · 확장 대상: ORM(JPA 연관관계·페치 전략)

## 학습 목표

- 지연 로딩이 N+1 쿼리를 유발하는 정확한 지점과 프록시 초기화 타이밍을 구분한다.
- 페치 조인·@EntityGraph·@BatchSize·서브셀렉트의 생성 SQL 차이와 카티션 곱 위험을 정리한다.
- 컬렉션 페치 조인에서 페이징이 메모리 페이징으로 퇴화하는 원리와 회피 전략을 파악한다.
- DTO 프로젝션과 페치 전략을 언제 선택할지 실측 쿼리 수 기준으로 판단한다.

## 1. N+1이 발생하는 정확한 지점

N+1은 부모 N건을 1번 쿼리로 조회한 뒤 각 부모의 지연 로딩 연관을 접근할 때마다 추가 쿼리가 N번 나가는 현상이다. 발생 지점은 SELECT 시점이 아니라 프록시가 실제로 초기화되는 시점이다. getMember() 자체는 프록시를 반환할 뿐 쿼리를 내지 않고, getName() 처럼 실제 필드에 접근하는 순간 프록시가 초기화되며 SQL이 나간다.

```java
List<Order> orders = orderRepository.findAll();  // SELECT * FROM orders (1번)
for (Order o : orders) {
    o.getMember().getName();   // 각 주문마다 SELECT ... member (N번)
}
```

가장 위험한 오해는 EAGER로 바꾸면 해결된다는 생각이다. EAGER는 findAll 같은 JPQL에서 여전히 각 연관을 개별 쿼리로 초기화한다(JPQL은 EAGER를 조인으로 자동 변환하지 않는다). 연관은 전부 LAZY로 두고 조회 시점에 명시적으로 페치하는 것이 원칙이다.

## 2. 페치 조인 — 한 방 쿼리

```java
@Query("select o from Order o join fetch o.member")
List<Order> findAllWithMember();
```

@ManyToOne·@OneToOne 단일 연관 페치 조인은 결과 행 수가 늘지 않아 안전하다. @OneToMany 컬렉션 페치 조인은 부모 1건에 자식 M건이면 M행으로 뻥튀기(카티션 곱)되어 부모가 중복된다. distinct 를 붙이면 하이버네이트가 애플리케이션 레벨에서 중복 부모를 제거한다.

## 3. 컬렉션 페치 조인 + 페이징의 함정

컬렉션을 페치 조인하면서 Pageable 로 페이징하면 하이버네이트는 경고(HHH000104)를 남기고 전체를 메모리로 읽은 뒤 메모리에서 페이징한다. 카티션 곱으로 부모가 M행으로 늘어난 상태에서 LIMIT을 걸면 데이터가 깨지기 때문이다. 수십만 행이면 OOM 직행이다. 정석 해법은 두 단계 조회다.

```java
@Query("select o.id from Order o where o.status = :s")
Page<Long> findOrderIds(@Param("s") Status s, Pageable pageable);

@Query("select distinct o from Order o join fetch o.items where o.id in :ids")
List<Order> findWithItems(@Param("ids") List<Long> ids);
```

## 4. @BatchSize / default_batch_fetch_size — IN 절 묶음

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

주문 1000건이면 1(부모) + 10(자식 배치) = 11쿼리로 수렴하고 카티션 곱이 없다. 컬렉션이 2개 이상이면 페치 조인은 곱집합 폭발을 일으키므로 배치 페칭이 사실상 유일한 안전 선택이다.

## 5. 두 컬렉션 동시 페치 조인 금지

@OneToMany 두 개를 동시에 페치 조인하면 카티션 곱(M×K행)이 되어 데이터가 폭증하고 MultipleBagFetchException 을 던지기도 한다. 실무 규칙은 컬렉션 페치 조인은 최대 하나, 나머지는 배치 페칭이다.

## 6. @EntityGraph — 애너테이션 기반 페치 명세

```java
@EntityGraph(attributePaths = {"member", "items"})
@Query("select o from Order o where o.status = :s")
List<Order> findByStatus(@Param("s") Status s);
```

JPQL을 수정하지 않고 어떤 연관을 함께 로딩할지 선언하며 페치 조인과 동일한 조인 SQL을 만든다. 컬렉션을 포함하면 §3의 페이징 함정이 동일 적용된다.

## 7. DTO 프로젝션 — 엔티티가 필요 없을 때

```java
public record OrderSummary(Long orderId, String memberName, long itemCount) {}

@Query("select new com.example.OrderSummary(o.id, o.member.name, count(i)) from Order o join o.items i group by o.id, o.member.name")
List<OrderSummary> findSummaries();
```

조회 전용 화면은 프록시도 영속성 컨텍스트 부담도 없이 필요한 데이터만 가져오는 DTO 프로젝션이 가장 빠르다.

## 8. 선택 가이드 요약

| 상황 | 권장 전략 | 이유 |
|---|---|---|
| 단일 연관 다수 로딩 | 페치 조인 / @EntityGraph | 행 증가 없음 |
| 컬렉션 1개 + 페이징 없음 | 페치 조인 + distinct | 단순, 한 방 |
| 컬렉션 1개 + 페이징 필요 | ID 2단계 조회 / 배치 페칭 | 메모리 페이징 회피 |
| 컬렉션 2개 이상 | default_batch_fetch_size | 카티션 곱 방지 |
| 조회 전용 화면 | DTO 프로젝션 | 그래프 로딩 불필요 |

## 9. OSIV — 지연 로딩 경계의 숨은 함정

Spring Boot는 기본적으로 OSIV(open-in-view: true)를 켠다. 영속성 컨텍스트를 요청 전 구간 열어 지연 로딩 예외를 막지만 DB 커넥션을 요청 내내 점유해 트래픽이 높으면 풀이 고갈된다.

```yaml
spring:
  jpa:
    open-in-view: false
```

끄면 커넥션이 서비스 트랜잭션 종료 시점에 반납되어 풀 효율이 오른다. 대신 트랜잭션 안에서 필요한 연관을 모두 페치해야 한다.

## 10. 쿼리 수 실측 — 테스트로 회귀 방어

```java
@Test
void 목록조회는_N플러스1이_없다() {
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
    stats.clear();
    List<Order> orders = orderRepository.findAllWithMemberAndItems();
    assertThat(orders).hasSize(100);
    assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
}
```

쿼리 수를 테스트로 못 박으면 리팩터링으로 N+1이 재발했을 때 CI에서 즉시 잡힌다.

## 참고

- Hibernate ORM User Guide — Fetching — https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#fetching
- Spring Data JPA Reference — Entity Graphs — https://docs.spring.io/spring-data/jpa/reference/jpa/entity-graph.html
- Vlad Mihalcea — MultipleBagFetchException — https://vladmihalcea.com/hibernate-multiplebagfetchexception/
- Jakarta Persistence 3.1 Specification — Fetch Joins
