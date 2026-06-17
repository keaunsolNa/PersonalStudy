Notion 원본: https://www.notion.so/3825a06fd6d381e68481ff869e291c74

# Spring Data JPA N+1과 Fetch Join · EntityGraph · BatchSize 및 Cartesian 곱

> 2026-06-17 신규 주제 · 확장 대상: Spring, ORM

## 학습 목표

- N+1 문제가 지연 로딩 연관에서 발생하는 정확한 메커니즘을 쿼리 로그로 본다
- Fetch Join, EntityGraph, @BatchSize, default_batch_fetch_size의 동작과 한계를 구분한다
- 컴렉션 다중 Fetch Join이 일으키는 Cartesian 곱과 페이징 불가 문제를 해결한다
- DTO Projection으로 조회 전용 경로를 분리하는 전략을 정리한다

## 1. N+1 — 가장 흔한 JPA 성능 함정

연관을 `LAZY`로 두면 연관 엔티티는 실제 접근 시점에 별도 쿼리로 로딩된다. 문제는 컴렉션을 순회하며 연관에 접근할 때다. `Order` 10건을 조회한 뒤 각 주문의 `member`에 접근하면, 주문 조회 1번 + 회원 조회 10번 = 11번 쿼리가 나간다. 이것이 **N+1**이다.

```java
@Entity
public class Order {
	@Id @GeneratedValue
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	private Member member;

	@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
	private List<OrderItem> items = new ArrayList<>();
}
```

```java
List<Order> orders = orderRepository.findAll();   // SELECT * FROM orders  (1번)
for (Order order : orders) {
	order.getMember().getName();   // 주문마다 SELECT * FROM member  (N번)
}
```

`EAGER`로 바꾸면 더 나빰진다 — 항상 즉시 로딩되어 모든 조회 경로에서 불필요한 조인/쿼리가 붙고, N+1이 코드 어디서든 터진다. **연관은 항상 LAZY로 두고, 필요한 곳에서 명시적으로 함께 조회**하는 것이 원칙이다.

## 2. Fetch Join — 한 방 쿼리로 연관 로딩

JPQL의 `join fetch`는 연관 엔티티를 SQL 조인으로 한 번에 가져와 영속성 컨텍스트에 채운다. `LAZY`라도 fetch join 대상은 즉시 초기화된다.

```java
@Query("select o from Order o join fetch o.member")
List<Order> findAllWithMember();
// SELECT o.*, m.* FROM orders o INNER JOIN member m ON o.member_id = m.id  (1번)
```

`ManyToOne`/`OneToOne` 단일 연관은 fetch join이 거의 항상 정답이다. 결과 행 수가 늘지 않기 때문이다. 문제는 컴렉션(`OneToMany`) fetch join이다 — 여기서 Cartesian 곱이 등장한다.

## 3. 컴렉션 Fetch Join과 Cartesian 곱

`Order`(2건) 각각이 `OrderItem`을 3개씩 가질 때 `join fetch o.items`를 하면 조인 결과는 2×3 = 6행이 된다. JPA는 같은 Order 식별자를 묶어 중복을 제거하지만, **DB에서 6행을 전송**하므로 데이터가 클수록 낭비가 크다. 또 부모 기준 중복 행 때문에 `distinct`가 필요하다(Hibernate 6+에서는 엔티티 중복 제거가 기본).

```java
@Query("select distinct o from Order o join fetch o.items")
List<Order> findAllWithItems();
```

치명적 한계: **컴렉션 fetch join에는 페이징(`setFirstResult`/`setMaxResults`)을 적용할 수 없다.** 조인으로 행이 붥튀기된 상태라 DB 레벨 LIMIT이 부모 기준으로 동작하지 않는다. Hibernate는 이 경우 경고 로그(`HHH000104`)와 함께 **전체를 메모리로 읽어** 페이징한다 — OOM 위험이 있는 안티패턴이다. 또 하나: **둘 이상의 컴렉션을 동시에 fetch join하면 MultipleBagFetchException**이 난다.

## 4. @BatchSize / default_batch_fetch_size — 페이징과 양립하는 해법

컴렉션을 페이징과 함께 조회해야 한다면 fetch join 대신 **batch fetching**을 쓴다. 부모를 먼저 페이징으로 조회하고, 자식 컴렉션은 `IN` 절로 한 번에 모아 가져온다. N+1이 N/배치크기 + 1로 줄어든다.

```java
@OneToMany(mappedBy = "order")
@BatchSize(size = 100)
private List<OrderItem> items = new ArrayList<>();
```

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

권장 패턴: 단일 연관은 fetch join, 컴렉션은 batch fetch.

```java
@Query("select o from Order o join fetch o.member")
Page<Order> findOrders(Pageable pageable);   // items는 default_batch_fetch_size로 로딩
```

## 5. EntityGraph — 어노테이션 기반 fetch 지정

`@EntityGraph`는 JPQL을 바꾸지 않고 어떤 연관을 함께 로딩할지 선언한다. fetch join과 유사하게 동작(LEFT OUTER JOIN)하지만 메서드 단위로 fetch 전략을 다르게 줄 수 있어 재사용성이 좋다.

```java
@EntityGraph(attributePaths = {"member"})
@Query("select o from Order o")
List<Order> findAllWithMemberGraph();

@EntityGraph(attributePaths = {"member", "delivery"})
List<Order> findByStatus(OrderStatus status);
```

EntityGraph는 OUTER JOIN을 쓰므로 fetch join(기본 INNER)과 결과 집합이 다를 수 있다. 컴렉션을 EntityGraph로 묶으면 fetch join과 똑같이 페이징·Cartesian 문제가 재현되므로, 컴렉션은 여기서도 batch fetch에 맡긴다.

## 6. DTO Projection — 조회 전용 경로 분리

복잡한 조회는 엔티티를 거치지 않고 **필요한 컴럼만 DTO로 직접 조회**하는 것이 가장 빠르다. 영속성 컨텍스트·지연 로딩 오버헤드가 없고 SELECT 컴럼을 최소화한다.

```java
public record OrderSummary(Long orderId, String memberName, int itemCount) {}

@Query("""
		select new com.example.OrderSummary(o.id, m.name, size(o.items))
		from Order o join o.member m
		""")
List<OrderSummary> findOrderSummaries();
```

명령(쓰기)은 엔티티로, 조회(읽기)는 DTO로 분리하는 CQRS-lite 접근이 대규모 서비스의 일반적 결론이다.

## 7. 기법 비교

| 기법 | 페이징 | Cartesian | 다중 컴렉션 | 적합 대상 |
|---|---|---|---|---|
| Fetch Join (ToOne) | O | 없음 | - | 단일 연관 |
| Fetch Join (컴렉션) | X(메모리) | 발생 | MultipleBag 예외 | 단건/소량 |
| @BatchSize / batch_fetch | O | 없음 | O | 컴렉션 + 페이징 |
| EntityGraph | ToOne만 O | 컴렉션 시 발생 | 제한 | 선언적 fetch |
| DTO Projection | O | 직접 제어 | 직접 제어 | 조회 전용 |

실무 결론: ToOne은 fetch join 또는 EntityGraph, OneToMany 컴렉션은 `default_batch_fetch_size`(100~1000)로 전역 처리, 통계·목록성 조회는 DTO Projection. 이 세 가지 조합으로 대부분의 N+1과 페이징 문제가 해결된다.

## 8. 진단 — 쿼리 로그와 검증

```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        generate_statistics: true
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

`generate_statistics`가 출력하는 `X queries`가 기대치(보통 1~3)를 크게 넘으면 N+1이다. 단위 테스트에서 `Statistics.getPrepareStatementCount()`를 단언하면 회귀를 자동 차단할 수 있다.

```java
@Test
void 주문_목록_조회는_쿼리_2개_이하() {
	Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	stats.clear();
	orderService.findOrders(PageRequest.of(0, 100));
	assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2L);
}
```

## 9. 컴렉션 조회 최적화 — 부모 페이징 + 자식 batch 조합 실전

가장 흔한 실무 요구는 "주문 목록을 페이징하되 각 주문의 항목들도 함께 보여달라"이다. 이를 fetch join 하나로 풀려다 §3의 함정에 빠진다. 정석은 **ToOne은 fetch join으로, 컴렉션은 batch fetch로** 분리하는 것이다.

```java
@Query("""
		select o from Order o
		join fetch o.member
		join fetch o.delivery
		""")
Page<Order> findOrderPage(Pageable pageable);
```

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 1000
```

이 조합의 결과 쿼리는 (1) 주문+회원+배송 조인 1번(페이징 적용), (2) 조회된 주문들의 items를 `WHERE order_id IN (...)`로 1번, (3) items가 또 다른 컴렉션을 가지면 그것도 IN으로 1번 — 총 컴렉션 깊이 + 1번으로 수렴한다. N+1이 "컴렉션 종류 수 + 1"로 상수화되는 것이 핵심이다.

`default_batch_fetch_size` 값은 보통 100~1000을 쓴다. 너무 작으면 IN 쿼리가 여러 번 쪼개져 나가고, 너무 크면 IN 절 파라미터가 많아져 일부 DB의 파라미터 한도(예: Oracle의 IN 절 1000개 제한)에 걸린다. Hibernate는 이 한도를 알아서 청크로 나누지만, 적정선이 SQL 파싱·실행계획 캐시 측면에서 유리하다.

## 10. 주의 — 영속성 컨텍스트와 OSIV, 그리고 테스트

N+1을 잡았다고 끝이 아니다. **OSIV(Open Session In View)** 설정이 켜져 있으면(Spring Boot 기본 `spring.jpa.open-in-view=true`) 영속성 컨텍스트가 뷰 렌더링까지 열려 있어, 컨트롤러/뷰에서 지연 로딩이 트리거되며 예기치 않은 추가 쿼리가 나갈 수 있다. 이는 DB 커넥션을 요청 끝까지 점유하는 부작용도 있다. 트래픽이 큰 서비스는 OSIV를 끄고(`open-in-view=false`) 서비스 계층 트랜잭션 안에서 필요한 모든 데이터를 fetch join/batch로 미리 로딩하는 패턴을 권장한다. 단, OSIV를 끄면 트랜잭션 밖에서 지연 로딩 접근 시 `LazyInitializationException`이 나므로, 조회 결과를 DTO로 변환해 트랜잭션 경계 밖으로 내보내는 규율이 함께 필요하다.

마지막으로 회귀 방지를 위해 쿼리 수를 단언하는 테스트를 둔다. 코드 변경으로 fetch 전략이 깨져 N+1이 부활하는 것을 CI에서 막는다.

```java
@Test
void 주문_페이지_조회는_쿼리_3개_이하() {
	SessionFactory sf = entityManagerFactory.unwrap(SessionFactory.class);
	Statistics stats = sf.getStatistics();
	stats.setStatisticsEnabled(true);
	stats.clear();

	Page<Order> page = orderService.findOrderPage(PageRequest.of(0, 50));
	page.getContent().forEach(o -> o.getItems().size());

	assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3L);
}
```

이 단언은 "주문+회원+배송 1 + items batch 1 + (필요시) 추가 1" 구조를 고정한다. 누군가 fetch join을 제거하면 쿼리 수가 50+로 튀어 테스트가 즉시 실패한다 — 성능 회귀를 기능 회귀처럼 다루는 안전장치다.

## 참고

- Hibernate ORM 6 User Guide — "Fetching" / "Batch fetching"
- Spring Data JPA Reference — "Entity Graphs"
- Vlad Mihalcea, "The best way to fix the Hibernate MultipleBagFetchException"
- "자바 ORM 표준 JPA 프로그래밍" (김영한) — 프록시와 연관관계 관리, 페치 조인
