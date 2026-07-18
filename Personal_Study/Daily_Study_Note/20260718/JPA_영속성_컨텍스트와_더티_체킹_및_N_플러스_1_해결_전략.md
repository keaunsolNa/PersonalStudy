Notion 원본: https://www.notion.so/3a15a06fd6d3815db452dcff363a8d48

# JPA 영속성 컨텍스트와 더티 체킹 및 N+1 해결 전략

> 2026-07-18 신규 주제 · 확장 대상: ORM

## 학습 목표

- 영속성 컨텍스트의 1차 캐시와 스냅샷 구조를 Hibernate 내부 자료구조 수준에서 파악한다.
- flush 시점의 더티 체킹 알고리즘과 ActionQueue 실행 순서를 추적한다.
- N+1 의 발생 원인을 JPQL 번역 과정에서 규명하고 전략별 트레이드오프를 비교한다.
- 컴렉션 fetch join 과 페이징의 충돌을 batch fetch 로 해소하고 쿼리 수를 테스트로 검증한다.

## 1. 영속성 컨텍스트의 내부 구조

스펙상 `EntityManager` 는 영속성 컨텍스트를 다루는 창구지만, Hibernate 에서 실제 상태를 들고 있는 객체는 `StatefulPersistenceContext` 다. `SessionImpl` 이 `EntityManager` 와 `Session` 을 동시에 구현하며 그 내부에 이 컨텍스트를 하나 보유한다. Spring 에서 `@PersistenceContext` 로 주입받는 것은 프록시이고, 호출 시점에 현재 스레드에 바인딩된 `SessionImpl` 로 위임한다.

1차 캐시의 실체는 `Map<EntityKey, Object> entitiesByKey` 다. `EntityKey` 는 식별자 값과 `EntityPersister` 의 조합, 즉 캐시 키가 "타입 + PK"다. 같은 트랜잭션에서 같은 PK 로 두 번 조회하면 두 번째는 SQL 없이 동일 인스턴스가 반환되어 `==` 가 성립한다. JPA 가 보장하는 애플리케이션 수준 리피터블 리드이며 DB 격리 수준과는 별개의 층이다. 보조 구조인 `entityEntryContext` 는 엔티티마다 `EntityEntry` 를 매달아 상태·스냅샷(`loadedState`)·버전·락 모드를 기록한다. 더티 체킹의 원자재가 이 `loadedState` 다.

```java
@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)  // 기본값이 EAGER 이므로 반드시 명시한다
	@JoinColumn(name = "member_id")
	private Member member;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
	private List<OrderItem> orderItems = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	private OrderStatus status;

	public void changeStatus(OrderStatus status) {
		this.status = status;
	}
}
```

`new Order()` 직후는 transient 로 컨텍스트가 인스턴스를 모른다. `persist()` 하면 managed 가 되어 `entitiesByKey` 에 등록되고 `EntityEntry` 가 생성된다. detached 가 되면 인스턴스는 살아 있지만 추적되지 않아 필드를 바꿔도 UPDATE 가 나가지 않는다. `remove()` 는 removed 로 표시만 하고 실제 DELETE 는 flush 때 나간다.

| 전이 | 트리거 | 1차 캐시 | flush 시 SQL |
|---|---|---|---|
| transient → managed | `persist()` | 등록 | INSERT |
| detached → managed | `merge()` | 복사본 등록 | SELECT 후 UPDATE |
| managed → detached | `detach()`, `clear()`, 트랜잭션 종료 | 제거 | 없음 |
| managed → removed | `remove()` | 유지(상태만 변경) | DELETE |
| removed → managed | `persist()` | 유지 | 없음(취소) |

## 2. 스냅샷 기반 더티 체킹의 실제 비용

DB 에서 엔티티를 읽으면 Hibernate 는 `TwoPhaseLoad` 단계에서 각 필드 값을 `Object[]` 로 복사해 `EntityEntry.loadedState` 에 보관한다. 이것이 스냅샷이다. flush 때 `DefaultFlushEntityEventListener.dirtyCheck()` 가 현재 값 배열과 스냅샷을 인덱스별로 비교하는데, 단순 `equals` 가 아니라 매핑 타입(`Type.isDirty()`)에 위임되므로 `@Lob`, `@Convert`, `@Embeddable` 마다 방식이 다르다.

핵심은 **비교가 O(필드 수)이고 컨텍스트의 모든 엔티티에 대해 매 flush 마다 수행된다**는 점이다. 엔티티 100개에 필드 30개면 flush 한 번에 3,000회 비교다. 배치에서 수만 건을 올려 둔 채 JPQL 을 날리면 flush 가 자동 트리거되며 비용이 누적된다. 완화책은 바이트코드 강화(`hibernate-enhance-maven-plugin` 의 `enableDirtyTracking`)다. setter 호출 시점에 변경 필드를 자기 기록해 flush 때 그 필드만 확인한다.

기본 설정의 UPDATE 는 변경 여부와 무관하게 **모든 컬럼**을 SET 절에 넣는다.

```sql
-- status 하나만 바꿔도 네 컬럼을 전부 쓴다
update orders set member_id=?, ordered_at=?, status=?, total_amount=? where id=?;
```

UPDATE 모양이 항상 동일해야 Hibernate 가 부트스트랩 시점에 SQL 을 미리 만들어 캐싱할 수 있고, JDBC 드라이버와 DB 서버의 PreparedStatement 캐시 히트율이 100%에 가까워지기 때문이다. 엔티티에 `@DynamicUpdate` 를 붙이면 변경 컬럼만 SET 하는 SQL(`update orders set status=? where id=?`)을 매 flush 마다 동적 생성한다.

이득은 바인딩 파라미터가 줄고, 인덱스 컬럼을 건드리지 않으면 인덱스 갱신도 피하며, lost update 표면적도 줄어든다는 것이다. 반대급부는 SQL 문자열이 변경 조합마다 달라진다는 점이다. 컬럼 n 개면 이론상 2^n 가지 모양이 생겨 PreparedStatement 캐시가 파편화되고, Oracle 의 shared pool 이나 MySQL 의 statement 캐시가 재사용에 실패하면 매번 파싱 비용을 낸다. 따라서 "컬럼이 많고 변경 패턴이 소수로 수렴하는 테이블"에만 선별 적용한다. 일괄 적용은 거의 항상 손해다.

## 3. 쓰기 지연과 ActionQueue 실행 순서

`persist()` 호출 순간 INSERT 가 나가지 않는다(IDENTITY 는 식별자를 얻으려 즉시 INSERT 하므로 예외다). Hibernate 는 `ActionQueue` 에 `EntityInsertAction` 을 쌓고 flush 때 몰아 실행한다. JDBC 배치로 라운드트립을 줄이고 참조 무결성 순서로 재정렬하기 위해서다. 실행은 고정 순서다: orphan removal → insert → update → collection remove/update/recreate → **delete**. 자주 사고가 나는 지점이 **DELETE 가 항상 맨 뒤**라는 사실이다.

```java
@Transactional
public void replaceEmail(Long memberId, String newEmail) {
	Member member = memberRepository.findById(memberId).orElseThrow();
	emailRepository.delete(member.getPrimaryEmail());  // unique(email)
	entityManager.flush();  // 없으면 INSERT 가 먼저 나가 유니크 인덱스가 터진다
	emailRepository.save(new Email(member, newEmail));
}
```

flush 자동 트리거 조건은 셋이다. 트랜잭션 커밋 직전, JPQL/Criteria/네이티브 쿼리 실행 직전, 명시적 `flush()`. 두 번째가 중요한 이유는 결과 정합성이다. `persist()` 한 엔티티가 아직 DB 에 없는데 JPQL 로 조회하면 누락되므로, Hibernate 는 쿼리가 건드리는 테이블과 겹치는 pending action 이 있으면 flush 한다. `find()` 는 1차 캐시로 해결되므로 flush 를 유발하지 않는다.

```java
@Transactional
public long registerAndCount(Member member) {
	entityManager.setFlushMode(FlushModeType.COMMIT);  // 위험
	entityManager.persist(member);
	// persist 한 member 가 DB 에 없어 카운트에서 빠진다 — read-your-writes 위반
	return entityManager.createQuery("select count(m) from Member m", Long.class)
			.getSingleResult();
}
```

`FlushModeType.COMMIT` 은 두 번째 조건을 꺼서 flush 횟수를 줄이지만 대가가 이것이다. 게다가 Hibernate 는 네이티브 쿼리가 어떤 테이블을 건드리는지 파싱하지 않으므로 네이티브 쿼리는 AUTO 모드에서도 같은 문제를 랼 수 있다. `@Modifying(flushAutomatically = true)` 로 명시하는 편이 안전하다. 결국 이 모드는 읽기/쓰기 분리가 자명한 곳에만 국소 적용한다.

쓰기 지연의 이득인 JDBC 배치는 `hibernate.jdbc.batch_size` 로 켜다. 배치는 동일 SQL 이 연속될 때만 묶이므로 A-B-A-B 로 섞이면 크기가 1로 떨어지고, `order_inserts: true` 가 같은 테이블 INSERT 를 인접하게 재정렬해 이를 막는다. 다만 `IDENTITY` 는 INSERT 를 즉시 실행해 키를 받아야 하므로 **JDBC 배치가 원천 비활성화**된다. 대량 INSERT 라면 `SEQUENCE` + `pooled` 옵티마이저를 쓴다.

## 4. 지연 로딩 프록시의 실체와 경계

`@ManyToOne(fetch = LAZY)` 필드에 채워지는 것은 실제 엔티티가 아니라 그 엔티티를 상속한 런타임 서브클래스다. 과거 CGLIB 로 만들었으나 5.3 이후 ByteBuddy 로 대체되었다. 프록시는 원본의 모든 메서드를 오버라이드하고, 호출되면 `LazyInitializer.getImplementation()` 으로 실체를 로딩한 뒤 위임한다. 여기서 제약이 따라 나온다. 엔티티가 `final` 이면 상속 불가라 즉시 로딩으로 퇴화하고, `final` 메서드는 오버라이드되지 않아 초기화 없이 빈 필드를 읽으며, 기본 생성자가 `private` 이면 프록시 생성이 실패한다. Kotlin 에서 `kotlin("plugin.jpa")` 가 필수인 이유다.

```java
// getReference 는 SELECT 없이 프록시만 반환한다 — INSERT 만 나간다
Order order = entityManager.getReference(Order.class, orderId);
Product product = entityManager.getReference(Product.class, productId);
entityManager.persist(new OrderItem(order, product, quantity));
```

단 존재하지 않는 PK 는 접근 시점에 `EntityNotFoundException` 으로 터져 존재 검증이 뒤로 미뤄진다. 프록시는 원본 클래스가 아니므로 `equals()` 는 `getClass()` 가 아니라 `instanceof` 로 짜야 한다.

`LazyInitializationException` 은 미초기화 프록시를 세션이 닫힐 뒤 접근할 때 터진다. `@Transactional` 서비스가 반환한 엔티티를 컨트롤러에서 lazy 필드로 파고들면 그 시점에 세션은 닫혀 있다. Spring Boot 는 이를 무마하려 `spring.jpa.open-in-view` 를 기본 `true` 로 두어, 인터셉터가 요청 시작 시점에 `EntityManager` 를 만들어 응답이 끝날 때까지 유지한다. 대가는 커넥션이다. 뷰 렌더링 중 lazy 로딩이 발생하면 트랜잭션이 끝난 뒤에도 다시 커넥션을 잡고, 외부 API 호출이나 무거운 직렬화가 섞이면 커넥션 풀이 잠식되어 대기와 타임아웃이 연쇄된다. 권장은 `open-in-view: false` 로 끄고 서비스 계층에서 fetch join 이나 DTO 프로젝션으로 데이터를 완결해 반환하는 것이다. 끄는 순간 가려져 있던 lazy 접근이 예외로 드러나므로 신규 프로젝트라면 처음부터 끄고 시작한다.

## 5. N+1 의 발생 원리 — EAGER 도 답이 아니다

N+1 은 "목록 1건 조회 후 각 행마다 연관 조회 N 건"이다. LAZY 에서 나는 것은 직관적이다. 문제는 **EAGER 로 바꿔도 JPQL 에서는 N+1 이 그대로 난다**는 사실이다. `em.find()` 는 Hibernate 가 메타데이터를 보고 SQL 을 직접 조립하므로 EAGER 연관을 JOIN 으로 합친다. 반면 JPQL 은 개발자가 쓴 문자열을 **그대로 SQL 로 번역**해 member 조인이 없는 SQL 을 만든다. 그 SQL 로 Order 목록을 만든 뒤 Hibernate 는 "member 가 EAGER 인데 안 채워졌네"를 발견하고 **뒤능게 행마다 추가 SELECT** 를 날린다. 결과는 1 + N 이다.

```sql
-- JPQL: select o from Order o  →  EAGER 여부와 무관하게 조인 없이 번역된다
select o.id, o.member_id, o.status, o.total_amount from orders o;
-- 이후 Hibernate 가 EAGER 를 채우려 N 번 반복
select m.id, m.name from member m where m.id=?;
```

EAGER 는 상황을 악화시킨다. LAZY 라면 실제로 `getMember()` 를 호출하는 경로에서만 추가 쿼리가 나가고 안 쓰면 안 나간다. EAGER 는 **쓰든 안 쓰든 무조건** N 번을 날리고, 매핑에 박히므로 특정 유스케이스만 예외로 두지도 못한다. 원칙은 **모든 연관을 LAZY 로 두고 fetch 전략을 쿼리 단위로 지정**하는 것이다. `@ManyToOne`, `@OneToOne` 의 기본값이 EAGER 라는 점을 기억하고 반드시 LAZY 를 명시한다.

## 6. 해결 전략 비교

| 전략 | 쿼리 수 | 페이징 | 컴렉션 다중 | 적용 상황 |
|---|---|---|---|---|
| fetch join (ToOne) | 1 | 가능 | - | ToOne 연관, 항상 함께 쓰는 경우 |
| fetch join (컴렉션) | 1 | **불가(메모리 페이징)** | 불가(Bag 다중 시 예외) | 결과가 작고 페이징 없을 때 |
| `@EntityGraph` | 1 | fetch join 과 동일 | 동일 | Spring Data 메서드에 선언적 적용 |
| `@BatchSize` | 1 + ⌈N/size⌉ | **가능** | 가능 | 컴렉션/프록시 다수 초기화 |
| `default_batch_fetch_size` | 1 + ⌈N/size⌉ | **가능** | 가능 | 전역 기본값, 사실상 필수 |
| 서브쿼리/IN 분리 조회 | 2 | 가능 | 가능 | 배치 페치가 안 먹는 복잡 조건 |
| DTO 프로젝션 | 1 | 가능 | 수동 가공 | 조회 전용 API |

`@EntityGraph` 는 fetch join 과 같은 일을 어노테이션으로 하며 파생 쿼리에도 적용된다. 내부적으로 left outer join 으로 번역되는데, fetch join 은 기본 inner join 이라 연관이 null 인 행이 사라진다. 이 차이로 결과 건수가 달라지는 버그가 흔하다. DTO 프로젝션은 엔티티를 컨텍스트에 올리지 않아 더티 체킹도 스냅샷도 없으므로 조회 전용 API 의 정답에 가깝다.

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

	@EntityGraph(attributePaths = {"member", "delivery"})
	List<Order> findByStatus(OrderStatus status);

	// DTO 프로젝션 — 엔티티를 컨텍스트에 올리지 않는다
	@Query("""
			select new com.example.order.dto.OrderSummary(o.id, m.name, o.status, o.totalAmount)
			from Order o join o.member m where o.orderedAt >= :from
			""")
	List<OrderSummary> findSummaries(@Param("from") LocalDateTime from);
}
```

`@BatchSize` 는 접근이 다르다. 프록시 초기화 때 하나만 로딩하는 대신, 컨텍스트에 쌓인 **같은 타입의 미초기화 프록시를 최대 size 개까지 모아 IN 절로** 가져온다. Order 100건의 orderItems 를 전부 순회해도 쿼리는 2번이다. 클래스 레벨에 붙이면 ToOne 프록시에도 적용된다.

```java
@BatchSize(size = 100)
@OneToMany(mappedBy = "order")
private List<OrderItem> orderItems = new ArrayList<>();
// select oi.* from order_item oi where oi.order_id in (?, ?, ..., ?)
```

매번 붙이는 대신 전역 설정을 켜는 것이 실무 표준이며, 효과 대비 비용이 좋아 사실상 기본값으로 취급된다. 보통 100~1000 을 쓰고, 너무 크면 파싱 비용과 DB 의 IN 절 한계(Oracle 1000개)에 부딪힌다. `in_clause_parameter_padding` 은 파라미터 수를 2의 거듭제곱으로 패딩해 SQL 모양을 정규화하므로 캐시 히트율을 올린다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
        in_clause_parameter_padding: true
```

## 7. fetch join 과 페이징의 함정

컴렉션 fetch join 과 페이징을 함께 쓰면 `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!` 경고가 뜼다. Order 1건에 OrderItem 3건이면 조인 결과는 3행이라, `limit 10` 은 Order 10건이 아니라 조인 후 10행(Order 3~4건)을 잘라 결과가 틀린다. Hibernate 는 이를 알기에 **`limit` 을 SQL 에서 빼고 전체 결과를 다 읽은 뒤 메모리에서 자른다**. 결과는 맞지만 100만 건 테이블이면 100만 행을 힙에 올린 뒤 10건만 남긴다. OOM 으로 직행하는 경로이며, Hibernate 6.x 는 이를 예외로 승격하는 방향으로 정책을 강화했다.

두 번째 함정은 `MultipleBagFetchException: cannot simultaneously fetch multiple bags` 다. Bag 은 "중복 허용, 순서 없는 컴렉션", 즉 `@OrderColumn` 없는 `List` 매핑이다. Bag 둘을 동시에 fetch join 하면 카테시안 곱이 생기고, 어느 행이 어느 컴렉션 소속인지 복원할 키가 없어 예외를 던진다. `Set` 은 중복을 자체 제거해 다중 fetch 가 허용되지만 **진짜 해결이 아니다**. SQL 레벨에서는 여전히 카테시안 곱이 만들어져 A×B 행이 애플리케이션으로 전송되고 중복 제거는 그 뒤 메모리에서 일어난다. 각각 100건이면 10,000행이 네트워크를 탄다.

정석 해법은 **ToOne 은 fetch join, 컴렉션은 batch fetch** 로 역할을 나누는 것이다.

```java
@Query(value = """
		select o from Order o
		join fetch o.member m
		join fetch o.delivery d
		where o.status = :status
		""",
		countQuery = "select count(o) from Order o where o.status = :status")
Page<Order> findPageWithToOne(@Param("status") OrderStatus status, Pageable pageable);
```

ToOne fetch join 은 행 수를 늘리지 않으므로 `limit` 이 DB 에서 정상 동작한다. 여기에 `default_batch_fetch_size` 가 켜져 있으면 페이지에 담긴 Order 들의 `orderItems` 를 처음 건드리는 순간 IN 절 한 방으로 채워진다. 페이지 크기 10, 컴렉션 2개면 총 3회(루트 1 + 컴렉션 2)로 수렴하고, 컴렉션이 중첩도도 단계마다 IN 절 1회씩이라 깊이에 선형이다.

## 8. 캐시 경계와 대량 배치의 메모리 관리

1차 캐시는 영속성 컨텍스트 생명주기에 묶여 스레드 간 공유되지 않는다. 2차 캐시는 `SessionFactory` 범위로 전체가 공유하며 조회 순서는 1차 → 2차 → DB 다. 2차 캐시는 **엔티티가 아니라 분해된 상태 배열을 저장**하고 꿼낼 때 재조립하므로, 히트해도 반환 인스턴스는 매번 다르고 가변 필드가 공유돼 오염되는 사고는 없다.

쿼리 캐시는 함정이 많다. **결과 엔티티가 아니라 식별자 목록만** 저장하기 때문이다. 히트해도 각 ID 로 엔티티를 다시 찾아야 하고, 2차 캐시에 그 엔티티가 없으면 ID 마다 SELECT 가 나간다. 즉 **쿼리 캐시만 켜고 엔티티 2차 캐시를 안 켜면 N+1 을 새로 만들어낸다.** 또한 대상 테이블의 갱신 타임스탬프로 유효성을 판단하므로 쓰기가 잦으면 거의 항상 무효화되어 오버헤드만 남는다.

대량 배치에서는 영속성 컨텍스트 자체가 메모리 누수원이다. 10만 건을 `persist()` 하면 10만 개 인스턴스 + `EntityEntry` + 스냅샷 배열이 힙에 남고 flush 마다 전수 더티 체킹이 돌다.

```java
@Transactional
public void bulkInsert(List<OrderRequest> requests) {
	final int batchSize = 50;
	for (int i = 0; i < requests.size(); i++) {
		entityManager.persist(toEntity(requests.get(i)));
		if (i % batchSize == 0 && i > 0) {
			entityManager.flush();
			entityManager.clear();  // 없으면 힙이 계속 부푼다
		}
	}
	entityManager.flush();
	entityManager.clear();
}
```

`clear()` 는 전체를, `detach(entity)` 는 특정 엔티티만 둜다. `clear()` 이후 기존 참조는 모두 detached 가 되어 lazy 접근이 예외를 던지고, `flush()` 없이 `clear()` 하면 변경사항이 **조용히 사라진다**. 순서는 항상 flush → clear 다.

## 9. 진단 — 쿼리 수를 보고 테스트로 고정하기

N+1 은 코드 리뷰로 잡히지 않는다. 로컬 데이터 3건이면 4번이라 안 보이다가 운영에서 1만 건이면 1만 1번이 된다. 계측이 먼저다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

`generate_statistics` 는 세션 종료마다 실행 쿼리 수, 컴렉션 페치 수, 2차 캐시 히트/미스를 남긴다. 오버헤드가 있으니 운영에는 지표 수집 목적으로만 쓴다. `org.hibernate.orm.jdbc.bind`(5.x 는 `org.hibernate.type.descriptor.sql`)는 `?` 에 바인딩된 값을 보여 준다. 완성된 SQL 을 보려면 JDBC 드라이버를 감싸는 p6spy 가 편하다. 가장 중요한 것은 **쿼리 수를 테스트로 고정**하는 것이다. 한 번 고쳌도 누군가 lazy 필드를 하나 더 건드리면 조용히 N+1 이 부활한다.

```java
@SpringBootTest
@Transactional
class OrderQueryCountTest {

	@Autowired
	private EntityManagerFactory emf;

	@Autowired
	private OrderRepository orderRepository;

	private Statistics statistics;

	@BeforeEach
	void setUp() {
		statistics = emf.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();  // 전역 누적치이므로 매 테스트 초기화한다
	}

	@Test
	void 주문_목록_조회는_쿼리_2회여야_한다() {
		List<Order> orders = orderRepository
				.findPageWithToOne(OrderStatus.PAID, PageRequest.of(0, 10))
				.getContent();

		// 컴렉션을 실제로 순회해 lazy 초기화를 유발한다
		long itemCount = orders.stream()
				.flatMap(order -> order.getOrderItems().stream())
				.count();

		assertThat(itemCount).isPositive();
		assertThat(statistics.getPrepareStatementCount())
				.as("루트 1회 + orderItems 배치 페치 1회")
				.isEqualTo(2L);
	}
}
```

`getPrepareStatementCount()` 는 실제 준비된 JDBC statement 수를 센다. `getQueryExecutionCount()` 는 JPQL/HQL 실행만 세고 lazy 로딩 SELECT 는 빠지므로 N+1 탐지에는 전자가 정확하다. 테스트 데이터는 최소 3건 이상 넣어야 한다. 1건이면 1+1=2 라 정상과 구분되지 않는다. 다만 이 테스트의 지연 로딩은 트랜잭션 안에서 항상 성공하므로, OSIV 를 끔 운영에서 터질 `LazyInitializationException` 은 컨트롤러 레벨 통합 테스트로 따로 막아야 한다.

## 참고

- Hibernate ORM 6.x User Guide — Persistence Contexts, Flushing, Fetching 챕터
- Jakarta Persistence 3.1 Specification — Chapter 3. Entity Operations
- 김영한, 『자바 ORM 표준 JPA 프로그래밍』, 에이콘출판
- Vlad Mihalcea, 『High-Performance Java Persistence』, 2nd Edition
- Hibernate ORM Javadoc — `org.hibernate.stat.Statistics`, `org.hibernate.engine.spi.ActionQueue`
- Spring Data JPA Reference Documentation — Entity Graphs, Query Methods
