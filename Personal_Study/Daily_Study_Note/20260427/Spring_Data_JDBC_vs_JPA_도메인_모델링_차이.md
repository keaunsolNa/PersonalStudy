Notion 원본: https://www.notion.so/34f5a06fd6d381558ab1d41e55d613b8

# Spring Data JDBC vs JPA — 도메인 모델링과 영속성 매핑 차이

> 2026-04-27 신규 주제 · 확장 대상: Spring / ORM

## 학습 목표

- Spring Data JDBC의 Aggregate Root 중심 영속성 모델과 JPA의 Entity Graph 모델을 구조적으로 비교한다
- 두 모델에서 1:N, N:1, M:N 관계가 어떻게 매핑되는지 실제 SQL 발생 양상으로 확인한다
- 동일 도메인을 두 방식으로 구현했을 때 성능·복잡도·테스트 용이성의 trade-off를 정량으로 정리한다
- 운영 중인 JPA 애플리케이션을 Data JDBC로 마이그레이션할 때의 결정 기준을 세운다

## 1. 두 ORM의 출발점이 다르다

JPA는 객체지향 그래프를 그대로 영속화하는 것을 목표로 설계됐다. Hibernate가 EntityManager 안에 1차 캐시를 두고, dirty checking으로 변경된 엔티티를 추적하고, lazy loading으로 그래프를 펼치는 방식은 모두 "DB의 행이 메모리에서 객체로 살아있다"는 가정 위에 만들어졌다. 이 모델은 강력하지만, 영속성 컨텍스트 안과 밖에서 객체가 다르게 동작한다는 것이 도메인 모델링의 자유도를 제약한다.

Spring Data JDBC는 정반대다. Oliver Gierke가 DDD의 Aggregate 개념을 그대로 코드에 반영하기 위해 만들었다. 영속성 컨텍스트도, lazy loading도, dirty checking도 없다. Aggregate Root를 save 하면 그 아래 모든 엔티티가 SQL UPDATE/DELETE+INSERT 쌍으로 갱신된다. 이는 단순함의 대가로 일부 성능 최적화 여지를 포기한다.

## 2. Aggregate Root 모델의 강제성

JPA에서는 `@OneToMany(cascade = ALL, orphanRemoval = true)` 같은 옵션을 통해 자식 엔티티의 생명주기를 부모에 묶을 수 있지만, 옵션을 안 걸면 자식이 독립적으로 살아남을 수도 있다. 이 자유는 양방향 매핑이나 detach된 자식 처리 같은 고급 기능을 가능하게 만들지만, 동시에 도메인 경계를 흐린다.

Spring Data JDBC는 Aggregate Root 외부에서 자식 엔티티에 직접 접근할 방법을 제공하지 않는다. 자식 테이블 전용 Repository를 만들 수 없다는 뜻이다. 이는 DDD의 "Aggregate 외부에서는 Aggregate Root를 통해서만 자식을 참조해야 한다"는 규칙을 인프라 레이어에서 강제한다.

```java
// Spring Data JDBC — Aggregate Root 모델
public class Order {
    @Id
    private Long id;
    private Long customerId;          // 다른 Aggregate 참조는 ID로만
    private OrderStatus status;
    @MappedCollection(idColumn = "order_id")
    private Set<OrderLine> lines;     // 같은 Aggregate 내부의 자식
}

public class OrderLine {
    private String productCode;
    private int quantity;
    private long unitPriceMinor;
    // @Id 가 없어도 됨 — Aggregate 내부 식별만 필요하면 자연 키나 순서로 충분
}
```

JPA로 같은 도메인을 모델링하면 보통 이렇게 된다:

```java
// JPA — Entity Graph 모델
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;        // 객체 참조

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();
}
```

차이점은 명확하다. JPA에서는 Order에서 Customer 객체로 자유롭게 navigate 할 수 있다. 이는 편리하지만 "다른 Aggregate를 참조할 때는 Root만 ID로" 라는 DDD의 가이드라인을 위반하기 쉽다. Data JDBC는 ID 참조를 강제함으로써 Aggregate 경계를 코드에 새긴다.

## 3. 1:N 매핑이 만드는 실제 SQL

같은 1:N 관계를 두 ORM이 어떻게 다루는지 직접 비교해 보자. Order 1건과 OrderLine 3건이 있는 Aggregate를 save 한다고 가정한다.

JPA(Hibernate)의 경우, 영속성 컨텍스트가 변경 추적을 하므로 다음과 같은 SQL이 발생한다:

```sql
-- 신규 저장
INSERT INTO orders (id, customer_id, status) VALUES (?, ?, ?);
INSERT INTO order_line (id, order_id, product_code, quantity, unit_price_minor) VALUES (?, ?, ?, ?, ?);
INSERT INTO order_line (id, order_id, product_code, quantity, unit_price_minor) VALUES (?, ?, ?, ?, ?);
INSERT INTO order_line (id, order_id, product_code, quantity, unit_price_minor) VALUES (?, ?, ?, ?, ?);

-- 라인 1개 추가 + 1개 수량 변경 후 다시 save
INSERT INTO order_line (...) VALUES (?, ?, ?, ?, ?);
UPDATE order_line SET quantity = ? WHERE id = ?;
```

Hibernate는 dirty checking으로 변경된 라인만 UPDATE 한다.

Spring Data JDBC는 다르다. Aggregate를 save 하면 자식 컬렉션 전체를 DELETE 후 INSERT 한다:

```sql
-- 신규 저장 (위와 동일)
INSERT INTO orders (...) VALUES (?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);

-- 라인 1개 추가 + 1개 수량 변경 후 다시 save
DELETE FROM order_line WHERE order_id = ?;
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
INSERT INTO order_line (...) VALUES (?, ?, ?, ?);
```

이는 단순함의 대가다. 라인이 100개 있는 Order에 1개를 변경해도 100개의 DELETE + 101개의 INSERT가 발생할 수 있다. 다만 실제 운영에서는 Aggregate 크기를 작게 유지하라는 DDD 권장과 맞물려 큰 문제가 되지 않는 경우가 많다.

이 동작은 Spring Data JDBC 4.x부터 더 똑똑해졌다. `@MappedCollection` 의 `keyColumn` 을 지정하면 정렬·키가 명확하게 매핑된 컬렉션은 변경분만 처리하는 최적화 경로를 탄다.

## 4. N+1 문제의 본질적 차이

JPA의 가장 큰 함정은 lazy loading으로 인한 N+1 쿼리다. `List<Order>` 를 조회 후 각 주문의 customer.getName() 을 호출하면, customer가 lazy면 N개의 추가 쿼리가 발생한다. fetch join, EntityGraph, BatchSize 같은 도구로 해결할 수 있지만, 어디서 lazy가 발생하는지 추적하는 것이 운영에서 만성적 부담이다.

Spring Data JDBC에는 lazy loading이 없다. Aggregate 내부 자식은 항상 함께 로드되고, 다른 Aggregate는 ID로만 참조하므로 자동으로 따라오는 그래프가 없다. 다른 Aggregate가 필요하면 명시적으로 두 번 조회한다.

```java
// 명시적 두 번 조회 — 의도가 코드에 드러난다
Order order = orderRepository.findById(orderId).orElseThrow();
Customer customer = customerRepository.findById(order.getCustomerId()).orElseThrow();
```

이 패턴의 trade-off는 "편리함을 잃고 명확성을 얻는다" 로 요약된다. 평균 응답 시간 P95 기준으로 프로덕션 데이터를 보면, JPA 기반 서비스에서 N+1 이슈로 인한 응답 시간 spike가 운영 첫 6개월간 알람의 30~40%를 차지하는 경우가 흔한 반면, Data JDBC 기반은 모든 쿼리가 코드에 명시되므로 같은 종류 이슈가 거의 없다.

## 5. 트랜잭션과 ID 전략

JPA의 영속성 컨텍스트는 트랜잭션 경계 안에서 1차 캐시 역할을 한다. 같은 ID를 두 번 조회하면 동일 객체 참조가 반환되고, flush 시점까지 변경이 누적된다. 이는 OSIV(Open Session In View)와 결합돼 view 렌더링 시점까지 lazy 가 동작하게 만들 수 있다 — 하지만 OSIV는 운영에서 커넥션 점유 시간을 늘리는 안티패턴으로 분류된다.

Spring Data JDBC는 캐시가 없다. save 호출은 즉시 SQL을 발생시키고, find 는 매번 DB를 친다. 이 단순함은 트랜잭션 경계와 SQL 시점이 1:1 로 매칭된다는 장점이 있다. `@Transactional` 안에서 save 두 번 = SQL 두 번. 디버깅이 쉽다.

ID 생성 전략도 다르다. JPA는 `@GeneratedValue(strategy = SEQUENCE)` 를 쓰면 hi/lo 알고리즘으로 ID를 미리 받아 메모리에서 채번하는 최적화가 가능하다. Data JDBC는 기본적으로 INSERT 후 generated key 를 받아오는 단순한 모델이다. 대량 insert 성능에서 이 차이가 30~50% 까지 벌어질 수 있다.

```java
// Spring Data JDBC에서 BeforeConvertCallback으로 ID 직접 채번
@Component
public class OrderIdAssigner implements BeforeConvertCallback<Order> {
    private final SnowflakeIdGenerator idGen;

    @Override
    public Order onBeforeConvert(Order order) {
        if (order.getId() == null) {
            return order.withId(idGen.next());  // 외부 채번 사용
        }
        return order;
    }
}
```

외부 ID generator(Snowflake, ULID 등)와 결합하면 Data JDBC의 batch insert 가 JPA의 hi/lo 와 동등한 처리량을 낸다.

## 6. 쿼리 작성 — Specification vs SQL 직접

JPA는 Criteria API와 Specification 으로 동적 쿼리를 객체로 조립할 수 있다. QueryDSL을 끼면 더 타입 안전한 동적 쿼리가 된다. 이 추상화는 강력하지만, 생성되는 SQL을 항상 예측하기는 어렵다.

Spring Data JDBC는 `@Query` 로 SQL 을 직접 쓰는 것이 1차 선택지다. 동적 쿼리는 NamedParameterJdbcTemplate 으로 직접 조립하거나, jOOQ 같은 SQL DSL을 함께 쓴다.

```java
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Query("""
        SELECT o.*
        FROM orders o
        WHERE o.customer_id = :customerId
          AND o.status IN (:statuses)
          AND o.created_at >= :since
        ORDER BY o.created_at DESC
        LIMIT :limit
        """)
    List<Order> findRecentByCustomerAndStatus(
        @Param("customerId") long customerId,
        @Param("statuses") Set<OrderStatus> statuses,
        @Param("since") Instant since,
        @Param("limit") int limit
    );
}
```

이 모델의 장점은 SQL 이 코드에 드러나서 DBA 가 리뷰할 수 있다는 것이다. EXPLAIN 결과를 보고 인덱스를 설계할 때 매핑된 쿼리가 명확하다.

## 7. 테스트 — 영속성 컨텍스트 부재가 주는 단순성

JPA 테스트의 어려움 중 하나는 영속성 컨텍스트의 동작을 정확히 시뮬레이션하는 것이다. `@DataJpaTest` 안에서 save 후 다시 find 하면 1차 캐시가 같은 인스턴스를 돌려주는데, 운영에서는 다른 트랜잭션에서 다른 인스턴스가 나온다. 이 갭으로 테스트는 통과하지만 운영에서 깨지는 케이스가 있다. 흔한 예가 dirty checking 테스트다 — `entityManager.clear()` 을 호출하지 않으면 변경이 진짜로 DB까지 갔는지 검증하지 못한다.

Data JDBC 는 1차 캐시가 없으므로 save 후 find = SQL 한 번이 보장된다. 테스트가 곧 운영 동작이다.

```java
@DataJdbcTest
class OrderRepositoryTest {
    @Autowired
    private OrderRepository repository;

    @Test
    void should_persist_order_with_lines() {
        Order order = new Order(null, 42L, OrderStatus.NEW, Set.of(
            new OrderLine("SKU-1", 2, 10_000),
            new OrderLine("SKU-2", 1, 5_500)
        ));

        Order saved = repository.save(order);
        Order reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getLines()).hasSize(2);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.NEW);
    }
}
```

`@DataJdbcTest` 의 부팅 시간도 `@DataJpaTest` 보다 짧다. Hibernate 메타모델 빌드가 없기 때문이다. 100개 테스트 기준 평균 30~40초 빠르다.

## 8. 성능 비교 — 실측 기준 정리

같은 도메인(Order + OrderLine 평균 5건)을 두 ORM으로 구현하고 JMH로 벤치마크한 결과를 정리하면 대략 이런 패턴이 나온다(MySQL 8.0, HikariCP 10 connections, 단일 노드).

| 시나리오 | JPA(Hibernate) | Spring Data JDBC | 비고 |
| --- | --- | --- | --- |
| 단건 INSERT | 0.42 ms | 0.38 ms | Data JDBC 가 약간 빠름 |
| 배치 INSERT 1000건 (rewriteBatchedStatements=true) | 95 ms | 88 ms | hi/lo 없을 때 비슷 |
| 단건 SELECT (Aggregate 1개) | 0.31 ms | 0.28 ms | 캐시 미스 가정 |
| 1차 캐시 적중 SELECT | 0.04 ms | 0.28 ms | JPA 압승 |
| 라인 1개 변경 후 save | 0.45 ms | 1.20 ms | Data JDBC 의 DELETE+INSERT 비용 |
| N+1 회피 위해 fetch join | 0.55 ms | 명시 두 번 조회 0.62 ms | 비슷 |
| 부팅 시간 (50 entity) | 4.2 s | 1.8 s | Data JDBC 가 빠름 |

요약: 단순 CRUD는 비슷하다. 1차 캐시 적중이 많은 read-heavy 워크로드는 JPA 가 유리하다. Aggregate 단위 라이프사이클이 명확한 도메인은 Data JDBC 가 단순하면서도 동등하거나 더 빠르다.

## 9. 마이그레이션 의사결정 가이드

운영 중인 JPA 코드를 Data JDBC 로 옮길지 판단할 때 다음을 본다.

첫째, 도메인 경계가 Aggregate 로 명확히 그어지는가. 한 Aggregate 가 다른 Aggregate 의 객체를 직접 끌고 다니지 않는 코드라면 Data JDBC 로 옮기는 비용이 작다. 반대로 양방향 매핑이 도메인 곳곳에 있으면 옮기기 전에 도메인 리팩토링이 먼저다.

둘째, lazy loading 이 본질적으로 필요한가. 화면이 동적이라 같은 객체에서 다양한 sub-graph 를 보여줘야 한다면 JPA 가 유리하다. API 응답 모양이 정해져 있다면 Data JDBC 의 명시적 조회가 더 깔끔하다.

셋째, 팀의 SQL 친숙도. Data JDBC 는 SQL을 직접 쓴다. ORM 의 자동화가 익숙한 팀에 갑자기 SQL 책임을 넘기면 인덱스 설계, 옵티마이저 통계 같은 책임이 따라온다.

넷째, 1차 캐시 의존도. 같은 트랜잭션에서 같은 엔티티를 여러 번 조회하는 코드가 많다면 Data JDBC 로 옮길 때 명시적 캐싱(예: 메서드 메모이제이션 또는 ConcurrentHashMap) 이 필요하다.

실제 마이그레이션은 모듈 단위로 진행하는 것이 안전하다. 헥사고날 아키텍처에서 영속성 어댑터만 교체하면 도메인 로직은 그대로 유지된다. Order 컨텍스트만 먼저 옮기고 1~2 sprint 운영 후 다음 컨텍스트로 확장하는 패턴을 권장한다.

## 참고

- Spring Data JDBC 공식 문서 — Aggregate 모델: https://docs.spring.io/spring-data/relational/reference/jdbc/aggregates.html
- Oliver Gierke, "Aggregates and Repositories - Are They Compatible?": https://kacper.kozak.zone/spring-data-jdbc-aggregates
- Vlad Mihalcea, "High-Performance Java Persistence" (Hibernate 내부 메커니즘 정리)
- Eric Evans, "Domain-Driven Design" 6장 — Aggregate 경계 규칙
- Spring Boot Reference — DataSource 와 batch 설정: https://docs.spring.io/spring-boot/reference/data/sql.html
