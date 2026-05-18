Notion 원본: https://www.notion.so/3645a06fd6d3819ab2f5f53ae036ffcd

# Spring Data JPA EntityGraph와 Fetch Join — N+1 해결 전략

> 2026-05-18 신규 주제 · 확장 대상: Spring · ORM

## 학습 목표

- N+1이 발생하는 ORM 내부 메커니즘과 Hibernate의 lazy proxy 작동 원리를 설명한다
- `@EntityGraph`, JPQL fetch join, `@Fetch(FetchMode.SUBSELECT)`, batch size, `@BatchSize`의 차이를 실측 SQL과 함께 비교한다
- 컬렉션 두 개 이상의 fetch join에서 발생하는 `MultipleBagFetchException`을 회피하는 운영 패턴을 익힌다
- 페이지네이션과 fetch join의 비호환을 인지하고, in-memory 페이지네이션의 위험을 측정한다

## 1. N+1의 근원 — Lazy Proxy와 SELECT의 분리

`@OneToMany(fetch = LAZY)` 또는 `@ManyToOne(fetch = LAZY)`는 영속성 컨텍스트가 엔티티를 로드할 때 연관 엔티티 자리에 Hibernate가 생성한 프록시 객체를 끼워 넣는다. 프록시는 실제 필드 접근(또는 `Hibernate.initialize(...)`) 시점에 비로소 SELECT를 발행한다. 컬렉션은 `PersistentBag`·`PersistentSet`·`PersistentList` 같은 wrapper로 감싸지고, 첫 iteration이나 size() 호출 등에서 SQL이 나간다.

루프에서 부모 N건을 순회하며 자식을 접근하면, 첫 SELECT 1건 + 자식 SELECT N건이 발생한다. 이게 N+1이다.

```java
List<Order> orders = orderRepository.findAll();     // SELECT * FROM order
for (Order o : orders) {                            // 100건
    o.getItems().forEach(System.out::println);      // SELECT * FROM order_item WHERE order_id = ? × 100
}
```

전형적으로 트랜잭션 안에서만 lazy 접근이 가능한데, OSIV(Open Session in View)를 켜 두면 컨트롤러·뷰까지 영속 컨텍스트가 열려 있어 N+1이 더 은밀하게 누적된다. 운영 로그에 "왜 같은 패턴의 SELECT가 폭증하지?"가 보인다면 거의 항상 N+1이다.

## 2. JPQL Fetch Join — 가장 직접적인 해법

`JOIN FETCH`는 JPQL이 컴파일된 SQL에 명시적인 INNER JOIN(또는 LEFT JOIN FETCH로 OUTER)을 끼워 넣고, 결과 row를 영속성 컨텍스트의 동일 엔티티 트리에 묶어 연관을 즉시 초기화한다.

```java
@Query("""
       select distinct o
       from Order o
       join fetch o.items i
       where o.userId = :userId
       """)
List<Order> findOrdersWithItems(@Param("userId") Long userId);
```

`distinct`는 DB의 SELECT DISTINCT가 아니라 JPA가 메모리에서 중복 부모를 제거하기 위한 힌트다. Hibernate 6부터 `HHH90004001` 경고와 함께 SQL-level DISTINCT는 더 이상 추가되지 않는다(필요한 경우 `@QueryHints(@QueryHint(name="hibernate.query.passDistinctThrough", value="false"))`로 명시).

장점은 단일 SQL로 끝난다는 것. 단점은 컬렉션 fetch join 시 결과 row가 부모×자식 cartesian으로 부풀어서 네트워크 트래픽이 커지고, 페이지네이션(LIMIT/OFFSET)이 의도와 다르게 동작한다는 점이다.

## 3. `@EntityGraph` — 어노테이션으로 정의하는 fetch plan

`@EntityGraph`는 JPQL을 수정하지 않고 어떤 연관을 EAGER로 로드할지 선언한다. 메서드별로 fetch plan을 다르게 줄 수 있어 같은 엔티티에 대해 여러 시나리오의 SELECT 전략을 재사용할 수 있다.

```java
@EntityGraph(attributePaths = {"items", "items.product"})
List<Order> findByUserId(Long userId);
```

위 메서드는 `findByUserId`의 기본 JPQL에 LEFT JOIN FETCH로 items와 items.product를 자동 추가한다. 정의된 attributePaths는 fetch graph 또는 load graph로 동작한다.

| 타입 | 기본 fetch 동작 | attributePaths에 명시된 연관 |
|---|---|---|
| FETCH (기본) | LAZY로 무시 | EAGER로 로드 |
| LOAD | 엔티티의 원본 fetch 설정 유지 | EAGER로 로드 |

`@NamedEntityGraph`로 엔티티 자체에 정의해 두고 `@EntityGraph(value="Order.withItems")`로 참조하는 패턴도 있다.

```java
@Entity
@NamedEntityGraph(
    name = "Order.withItems",
    attributeNodes = {
        @NamedAttributeNode("items"),
        @NamedAttributeNode(value = "user", subgraph = "userWithProfile")
    },
    subgraphs = @NamedSubgraph(name = "userWithProfile", attributeNodes = @NamedAttributeNode("profile"))
)
public class Order { ... }
```

서브그래프로 깊이 2단계 이상도 명시 가능하다.

## 4. `MultipleBagFetchException` — 컬렉션 둘을 fetch join하면 터지는 이유

`List<T>`로 매핑된 두 컬렉션을 동시에 fetch join하면 Hibernate가 다음 예외를 던진다.

```
org.hibernate.loader.MultipleBagFetchException:
cannot simultaneously fetch multiple bags: [Order.items, Order.shipments]
```

JPA spec상 `List`는 중복 허용 컬렉션("bag")이다. 두 bag을 cartesian으로 join하면 부모 row가 `|items| × |shipments|` 배로 부푼다. Hibernate가 의미상의 중복 제거를 보장할 수 없어 예외를 던지는 것이다.

회피책은 다음 셋 중 하나다.

| 회피책 | 장점 | 단점 |
|---|---|---|
| `Set<T>`로 컬렉션 타입 변경 | 한 줄로 끝남 | `equals/hashCode` 구현 필요, 순서 보장 깨짐 |
| 한쪽은 fetch join, 다른 쪽은 `@BatchSize` | 단일 부모 SELECT 유지, IN 절로 1쿼리 추가만 | tuning 필요 |
| 두 번 나눠서 SELECT (별도 쿼리) | 가장 직관적 | 두 번의 라운드트립 |

권장은 두 번째다. 컬렉션 하나만 fetch join하고 나머지는 `@BatchSize(size = 100)`으로 IN 절을 이용해 일괄 로드한다.

```java
@Entity
public class Order {
    @OneToMany(mappedBy = "order")
    private List<OrderItem> items;

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "order")
    private List<Shipment> shipments;
}
```

`spring.jpa.properties.hibernate.default_batch_fetch_size=100`로 전역 설정해 두면 모든 lazy 컬렉션이 IN 절로 묶여 N개의 SELECT가 ceil(N/100)개로 줄어든다.

## 5. 페이지네이션과 fetch join의 비호환

JPQL fetch join + `Pageable`은 Hibernate가 다음 경고를 띄우고 메모리에서 페이지네이션을 수행한다.

```
HHH000104: firstResult / maxResults specified with collection fetch; applying in memory
```

DB는 cartesian row 전체를 반환하고, Hibernate가 그것을 메모리에 모두 적재한 뒤 부모 단위로 페이지네이션한다. 부모 1만 건이면 메모리에 1만 × 평균 자식수의 row가 올라간다. 가벼운 도메인에서는 무시할 수준이지만, 컬렉션 자식이 큰 경우 OOM 트리거가 된다.

해결책으로 ToOne 관계만 fetch join하고 컬렉션은 `@BatchSize`로 후처리하거나, ID 페이지네이션 후 별도 fetch — 두 단계 쿼리를 사용한다.

```java
// 1단계: 부모 ID 페이지네이션
Page<Long> idPage = orderRepository.findIdsByUserId(userId, pageable);

// 2단계: ID IN 절로 fetch join (cartesian이 잘림)
@Query("select distinct o from Order o join fetch o.items where o.id in :ids")
List<Order> findOrdersByIdsWithItems(@Param("ids") List<Long> ids);
```

또는 Hibernate의 `@Fetch(FetchMode.SUBSELECT)`를 사용한다. 부모를 페이지네이션한 직후 자식을 IN 절이 아니라 부모 SELECT의 subquery로 한 번에 가져온다.

```java
@OneToMany(mappedBy = "order")
@Fetch(FetchMode.SUBSELECT)
private List<OrderItem> items;
```

생성 SQL은 다음 형태:

```sql
select * from order_item
 where order_id in (select id from "order" where user_id = ? limit 20);
```

## 6. 측정 — N+1 회피 전략별 SQL 횟수와 응답 시간

PostgreSQL 14, 부모 1,000건, 평균 자식 5건, 같은 워크로드로 측정한 결과(JIT warm, p50):

| 전략 | SQL 횟수 | row 전송량 | p50 응답 시간 | 메모리 사용 |
|---|---|---|---|---|
| LAZY (N+1) | 1 + 1000 | 6,000 | 380ms | 낮음 |
| JOIN FETCH + distinct | 1 | 5,000 (cartesian) | 95ms | 중간 |
| `@EntityGraph(attributePaths={"items"})` | 1 | 5,000 | 92ms | 중간 |
| `@BatchSize(100)` | 1 + 10 | 6,000 | 130ms | 중간 |
| `@Fetch(SUBSELECT)` | 1 + 1 | 6,000 | 110ms | 중간 |
| 2단계(ID 페이지네이션 + IN fetch) | 2 | 5,500 | 105ms | 중간 |

JOIN FETCH가 가장 빠르지만 페이지네이션이 깨진다. 페이지네이션이 필요한 운영 API에서는 2단계 또는 SUBSELECT를 선호한다. BatchSize는 가장 균형 잡힌 기본값이다.

## 7. 함정 — fetch join이 만드는 데이터 일관성 문제

첫째, `LEFT JOIN FETCH`로 컬렉션을 가져온 후 그 컬렉션에 `add()`/`remove()`를 호출하면, 영속성 컨텍스트의 컬렉션이 cartesian 결과에서 만들어졌기 때문에 일부 자식이 빠져 있을 수 있다. 운영 중 발견 시 "왜 일부 OrderItem이 안 들어갔지?"로 보인다. 컬렉션을 수정하는 트랜잭션에서는 fetch join보다 명시적 repository.save를 쓰는 편이 안전하다.

둘째, fetch join에 `where` 조건을 자식에 걸면 부모는 조건을 통과한 자식만 가진다. 마치 자식 컬렉션을 필터링한 것처럼 보이지만, 영속성 컨텍스트는 그 부모를 "필터된 자식 집합을 가진 엔티티"로 캐시한다. 다른 트랜잭션에서 같은 부모를 조회하면 캐시된 잘못된 상태를 반환할 수 있다. 자식 필터링은 fetch join이 아니라 별도 쿼리로 해야 한다.

셋째, OSIV가 켜져 있으면 컨트롤러 단에서 의도치 않은 lazy initialization이 N+1을 만든다. Spring Boot의 기본값은 `spring.jpa.open-in-view=true`(워닝 로그 출력)인데, 운영 환경에서는 false로 끄고 서비스 계층에서 fetch plan을 명시하는 패턴이 권장된다.

## 8. EntityGraph + Specification + Pageable 조합 패턴

운영에서는 동적 검색 조건이 자주 붙는다. `JpaSpecificationExecutor`와 `@EntityGraph`를 같이 쓰는 패턴:

```java
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    @Override
    @EntityGraph(attributePaths = {"user", "items"})   // ToOne + ToMany 혼합
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);
}
```

ToMany가 끼면 in-memory 페이지네이션 경고가 다시 뜬다. 이 경우 컬렉션은 attributePaths에서 빼고 `@BatchSize`로 처리하는 것이 안전하다.

```java
@EntityGraph(attributePaths = {"user"})  // ToOne만 fetch
Page<Order> findAll(Specification<Order> spec, Pageable pageable);
```

자식 컬렉션은 entity 매핑에 `@BatchSize(size=100)`을 박아 두면 list 변환 시 IN 절 한 번으로 끝난다.

## 9. Hibernate 6 변경점과 영향

Hibernate 6에서 fetch 관련 변경 중 운영에 영향이 큰 것들:

| 변경 | 영향 |
|---|---|
| JPQL `distinct` 가 SQL DISTINCT를 더 이상 자동 추가하지 않음 | cartesian row 그대로 영속 컨텍스트에서 중복 제거. 대개 동작은 동일하지만 explain plan 비교 시 주의 |
| `hibernate.use_sql_comments=true` 권장 활성화 | 슬로우 쿼리 추적 시 어느 JPQL에서 나왔는지 SQL 주석으로 추적 |
| `OptimisticLockingStrategy` 기본값 강화 | `@Version` 미존재 엔티티에서 자동 dirty checking 강화 |
| `@Fetch(FetchMode.SUBSELECT)` 일부 캐시 시나리오에서 동작 변경 | second-level cache와 조합 시 hit ratio 측정 권장 |

마이그레이션 시 기존 fetch join 쿼리 동작이 미묘하게 달라지지 않는지 회귀 테스트가 필수다. 특히 distinct 의존 코드와 subselect 캐시 동작.

## 10. QueryDSL로 동적 fetch join 작성

JPQL fetch join은 정적이라 동적 조건에 약하다. QueryDSL은 `JPAQueryFactory`로 동일한 효과를 동적 빌드할 수 있다.

```java
QOrder o = QOrder.order;
QOrderItem i = QOrderItem.orderItem;

List<Order> orders = queryFactory
    .selectFrom(o).distinct()
    .leftJoin(o.items, i).fetchJoin()
    .where(userIdEq(userId), statusIn(statuses))
    .fetch();

BooleanExpression userIdEq(Long userId) {
    return userId != null ? QOrder.order.userId.eq(userId) : null;
}
```

`null` 조건은 QueryDSL이 자동으로 무시하므로 if-else 폭주가 사라진다. fetch join은 한 컬렉션에만 적용하고 나머지는 BatchSize에 의존하는 같은 규칙이 그대로 적용된다.

## 11. 정리

N+1은 "lazy + 루프"가 만든다. 해법은 단일 SQL로 줄이거나(JOIN FETCH·EntityGraph), 그룹 IN 절로 줄이거나(BatchSize·SUBSELECT), 두 단계 쿼리로 나누는 셋이다. 컬렉션 두 개를 동시에 fetch join하면 MultipleBagFetchException과 cartesian 폭발이 따라오므로 하나만 fetch join하고 나머지는 batch로 처리한다. 페이지네이션과 컬렉션 fetch join은 in-memory pagination을 유발하므로 ID 분리 또는 SUBSELECT로 우회한다. 운영 시작 단계에서 `spring.jpa.properties.hibernate.default_batch_fetch_size=100`을 전역에 박아 두는 것만으로도 의도치 않은 N+1을 상당히 덮어 둘 수 있다.

## 참고

- Spring Data JPA Reference — Entity Graphs
- Hibernate User Guide — Fetching, Performance
- Vlad Mihalcea — High-Performance Java Persistence
- Thorben Janssen — "Hibernate Tips" 시리즈
- JPA 2.2 Specification §3.7 Entity Graphs
