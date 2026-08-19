Notion 원본: https://app.notion.com/p/3c15a06fd6d381ef95d2f9c5562970ed?pvs=204

# Hibernate 배치 INSERT와 Fetch 전략 및 N+1 진단

> 2026-08-19 신규 주제 · 확장 대상: ORM

## 학습 목표

- `hibernate.jdbc.batch_size` 가 실제로 배치를 만들지 못하는 조건을 SQL 로그로 판별한다
- `JOIN FETCH` / `@BatchSize` / `@EntityGraph` / subselect 네 가지 페치 전략의 카디널리티 비용을 계산해 선택한다
- 페이징과 컬렉션 조인이 겹칠 때 발생하는 메모리 페이징을 탐지하고 제거한다
- 통계 지표(`Statistics`)와 쿼리 카운터로 N+1 을 회귀 테스트에 고정한다

## 1. 배치가 만들어지지 않는 진짜 이유

`spring.jpa.properties.hibernate.jdbc.batch_size=50` 을 넣었는데 INSERT 가 여전히 한 건씩 나가는 상황은 대부분 설정 문제가 아니라 **식별자 생성 전략** 문제다. Hibernate 의 배치는 "flush 시점에 같은 테이블·같은 SQL 문자열을 가진 연속된 액션을 하나의 `PreparedStatement.addBatch()` 묶음으로 보낸다"는 규칙으로 동작한다. 그런데 `GenerationType.IDENTITY` 는 INSERT 를 실행해야만 PK 를 알 수 있고, Hibernate 는 `persist()` 호출 즉시 영속성 컨텍스트의 1차 캐시 키를 확정해야 하므로 INSERT 를 **즉시 실행**한다. 그 결과 액션 큐에 쌓일 것이 없어 배치가 원천적으로 불가능하다.

MySQL 은 시퀀스가 없으니 IDENTITY 를 쓰기 쉬운데, 이때는 테이블 기반 시퀀스 에뮬레이션인 `SEQUENCE` + pooled-lo 최적화를 쓴다.

```java
@Entity
@Table(name = "order_line")
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_line_seq")
	@GenericGenerator(
			name = "order_line_seq",
			strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
			parameters = {
					@Parameter(name = "sequence_name", value = "seq_order_line"),
					@Parameter(name = "increment_size", value = "50"),
					@Parameter(name = "optimizer", value = "pooled-lo")
			})
	private Long id;
}
```

`pooled-lo` 는 시퀀스에서 값 하나를 당겨오면 그 값부터 `increment_size` 개를 애플리케이션 메모리에서 소진한다. `increment_size=50` 이면 5만 건 INSERT 에 시퀀스 왕복이 1,000 번으로 줄고, 무엇보다 PK 를 미리 알 수 있으니 INSERT 를 flush 까지 미룰 수 있어 배치가 성립한다. 주의할 점은 시퀀스의 DB 측 `INCREMENT BY` 와 `increment_size` 가 반드시 일치해야 한다는 것이다. DB 는 1씩 증가하는데 애플리케이션이 50씩 예약하면 다른 세션과 PK 가 충돌한다.

배치가 켜졌는지 확인하는 가장 확실한 방법은 SQL 로그가 아니라 JDBC 드라이버 레벨 통계다. `datasource-proxy` 나 `p6spy` 를 물리면 `batch=true, batchSize=50` 형태로 실제 묶음 크기가 찍힌다. Hibernate 의 `show_sql` 은 배치 여부와 무관하게 SQL 문자열을 그대로 뿌리므로 착시를 준다.

```java
DataSource proxy = ProxyDataSourceBuilder.create(realDataSource)
		.name("trace")
		.logQueryBySlf4j(SLF4JLogLevel.INFO)
		.multiline()
		.countQuery()
		.build();
```

## 2. 배치를 깨뜨리는 액션 재정렬 문제

배치 묶음은 "연속된 동일 SQL" 이어야 한다. 부모 A → 자식 A1 → 부모 B → 자식 B1 순서로 `persist()` 하면 액션 큐가 `INSERT parent, INSERT child, INSERT parent, INSERT child` 가 되어 배치 크기가 각각 1이 된다. Hibernate 는 이 문제를 위해 정렬 옵션을 제공한다.

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.batch_versioned_data=true
```

`order_inserts=true` 는 flush 직전에 액션 큐를 엔티티 타입별로 정렬해 `INSERT parent x N, INSERT child x N` 으로 재배치한다. FK 제약이 있어도 부모가 먼저 정렬되므로 안전하다. `batch_versioned_data` 는 `@Version` 이 붙은 엔티티의 UPDATE 를 배치에 포함시킬지 여부인데, JDBC 드라이버가 `executeBatch()` 의 반환 배열로 행별 영향 개수를 정확히 돌려줘야 낙관적 락 검증이 가능하다. PostgreSQL·MySQL 최신 드라이버는 지원하지만, 일부 드라이버는 `Statement.SUCCESS_NO_INFO(-2)` 를 반환해 버전 충돌을 놓친다. 그런 환경에서는 이 옵션을 켜면 안 된다.

MySQL 은 추가로 JDBC URL 에 `rewriteBatchedStatements=true` 가 필요하다. 이게 없으면 드라이버가 `addBatch()` 를 받아도 네트워크로는 INSERT 를 한 줄씩 보낸다. 켜면 `INSERT INTO t VALUES (...),(...),(...)` 멀티로우 형태로 재작성한다. 실측하면 1만 건 INSERT 가 로컬 네트워크 기준 약 8초에서 0.4초 수준으로 떨어진다. 대신 재작성된 문은 서버 사이드 prepared statement 캐시를 못 쓰므로, 배치가 아닌 단건 경로가 많은 애플리케이션에서는 `useServerPrepStmts=true` 와의 상호작용을 측정해야 한다.

## 3. 대량 처리에서 영속성 컨텍스트를 비우는 규칙

배치 크기를 아무리 키워도 영속성 컨텍스트가 계속 커지면 flush 마다 전체 엔티티를 dirty checking 하므로 O(n²) 에 가까운 CPU 를 태운다. 정석은 배치 크기 단위로 `flush()` + `clear()` 다.

```java
@Transactional
public void bulkInsert(List<OrderLineDto> rows) {
	final int batchSize = 50;
	for (int i = 0; i < rows.size(); i++) {
		entityManager.persist(toEntity(rows.get(i)));
		if ((i + 1) % batchSize == 0) {
			entityManager.flush();
			entityManager.clear();
		}
	}
	entityManager.flush();
	entityManager.clear();
}
```

`clear()` 후에는 이전에 반환한 엔티티 참조가 전부 detached 가 되므로, 호출자에게 엔티티를 그대로 넘기는 설계는 깨진다. 반환이 필요하면 DTO 로 변환해 리스트에 담아두고 넘긴다. 또한 `clear()` 는 2차 캐시를 건드리지 않으므로, 2차 캐시가 켜진 엔티티를 대량 삽입하면 캐시 무효화 트래픽이 별도로 발생한다. 대량 적재 경로에서는 `CacheStoreMode.BYPASS` 를 지정해 캐시 갱신을 끄는 편이 낫다.

수십만 건 이상이면 JPA 를 쓰지 않는 판단도 정당하다. `JdbcTemplate.batchUpdate` 나 PostgreSQL `COPY`, MySQL `LOAD DATA LOCAL INFILE` 이 한 자릿수 배 이상 빠르다. ORM 은 "도메인 규칙이 붙는 쓰기"에 쓰고, 순수 적재는 JDBC 로 내리는 분리가 실무 기본형이다.

## 4. N+1 이 발생하는 지점의 정확한 정의

N+1 은 "연관을 지연 로딩으로 선언했는데 컬렉션/프록시를 실제로 순회했다"는 단일 원인에서 나온다. 문제는 이 순회가 코드상 눈에 안 띄는 곳에서 일어난다는 점이다.

- 컨트롤러가 엔티티를 그대로 반환해 Jackson 이 getter 를 호출할 때
- `toString()` 에 연관 필드를 포함시켜 로그를 찍을 때
- `equals`/`hashCode` 가 연관 엔티티를 참조할 때
- Kotlin data class 의 자동 생성 `toString()`

가장 먼저 할 일은 엔티티가 프레젠테이션 계층으로 새어나가지 않게 막는 것이다. 그 다음이 페치 전략 선택이다.

## 5. 네 가지 페치 전략의 비용 비교

| 전략 | 발생 쿼리 수 | 결과 카디널리티 | 페이징 | 적합 상황 |
|---|---|---|---|---|
| `JOIN FETCH` | 1 | 부모 x 자식 (곱) | 컬렉션이면 불가(메모리 페이징) | 자식 수가 적고 단건 조회 |
| `@BatchSize(size=n)` | 1 + ceil(N/n) | 부모 N, 자식 별도 | 가능 | 목록 조회 기본값 |
| `@EntityGraph` | 1 (LEFT JOIN) | JOIN FETCH 와 동일 | 동일 제약 | 리포지토리 메서드 단위 제어 |
| `subselect` | 2 | 부모 N, 자식 1회 IN 서브쿼리 | 가능 | 동일 트랜잭션에서 전체 순회 확정 시 |

`JOIN FETCH` 는 쿼리 하나로 끝나지만 일대다 조인이므로 부모 행이 자식 수만큼 복제되어 돌아온다. 부모 100건 × 자식 20건이면 2,000 행이 네트워크를 타고, Hibernate 가 중복 부모를 제거한다. 자식이 평균 3~5건이면 이득이지만 20건을 넘어가면 배치 페치가 유리해진다.

컬렉션 `JOIN FETCH` + `setFirstResult/setMaxResults` 조합은 Hibernate 6 에서 여전히 경고를 남기고 **전체 결과를 메모리로 읽은 뒤 자바에서 자른다**. 이게 `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory` 로그다. 프로덕션에서 이 로그가 보이면 OOM 후보로 취급해야 한다. 해결책은 두 단계 조회다.

```java
// 1단계: ID 만 페이징으로 확보 (컬렉션 조인 없음)
List<Long> ids = em.createQuery(
		"select o.id from Order o where o.status = :st order by o.createdAt desc", Long.class)
		.setParameter("st", OrderStatus.PAID)
		.setFirstResult(page * size)
		.setMaxResults(size)
		.getResultList();

// 2단계: 확보한 ID 집합에 대해서만 컬렉션 페치
List<Order> orders = em.createQuery(
		"select distinct o from Order o join fetch o.lines where o.id in :ids", Order.class)
		.setParameter("ids", ids)
		.getResultList();
```

2단계 쿼리에는 `order by` 가 없으므로 애플리케이션에서 1단계 ID 순서대로 재정렬해야 한다. 이 패턴은 페이징 정확성과 쿼리 수(항상 2회)를 동시에 만족한다.

## 6. @BatchSize 의 실제 동작과 크기 선택

`@BatchSize(size = 100)` 을 컬렉션이나 `@ManyToOne` 에 붙이면, 프록시 초기화 시점에 영속성 컨텍스트가 보유한 **아직 초기화되지 않은 같은 타입의 프록시들을 최대 100개까지 모아** `IN (?, ?, ...)` 한 방으로 조회한다.

```java
@Entity
public class Order {

	@BatchSize(size = 100)
	@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
	private List<OrderLine> lines = new ArrayList<>();
}
```

전역 기본값은 `hibernate.default_batch_fetch_size` 로 준다. 실무에서는 이 전역값을 100~500 사이로 깔고 시작하는 것이 가장 비용 대비 효과가 크다. 코드 변경 없이 대부분의 N+1 을 "N/100 + 1" 로 낮춘다.

크기 선택에는 상한이 있다. Oracle 은 `IN` 절 요소가 1,000 개를 넘으면 ORA-01795 로 실패한다. Hibernate 6 는 배치 크기가 크면 `IN` 을 여러 개로 쪼개거나 padding 을 적용하는데, padding 이 켜져 있으면 배치 크기를 2의 거듭제곱(1,2,4,8,...)으로 올림해 SQL 문자열 종류를 줄여 준비된 문 캐시 적중률을 높인다.

```properties
spring.jpa.properties.hibernate.default_batch_fetch_size=100
spring.jpa.properties.hibernate.query.in_clause_parameter_padding=true
```

padding 이 없으면 IN 요소가 97개, 98개, 99개일 때마다 서로 다른 SQL 문자열이 생성되어 DB 의 실행계획 캐시(Oracle library cache, PostgreSQL prepared statement)를 오염시킨다. padding 을 켜면 97/98/99 가 모두 128 로 반올림되어 같은 문자열이 된다. 남는 자리는 마지막 값으로 채워지므로 결과는 동일하다.

## 7. Hibernate Statistics 로 회귀 테스트 고정하기

N+1 은 리팩터링 중 조용히 재발한다. 쿼리 개수를 테스트에서 단언하면 회귀를 막을 수 있다.

```java
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderQueryRegressionTest {

	@Autowired
	private EntityManagerFactory emf;

	@Autowired
	private OrderQueryService orderQueryService;

	private Statistics statistics;

	@BeforeEach
	void setUp() {
		statistics = emf.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
	}

	@Test
	void 주문_목록_조회는_쿼리_두_번만_실행한다() {
		orderQueryService.findPaidOrders(0, 50);

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2L);
		assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(50L * 6);
	}
}
```

`getPrepareStatementCount()` 는 실제 JDBC 문 준비 횟수라 배치 페치까지 포함해 정확히 잡힌다. 다만 `generate_statistics=true` 는 동기화된 카운터를 유지하므로 프로덕션에서는 오버헤드가 있다. 테스트 프로파일에만 켠다.

더 강한 방법은 커스텀 `StatementInspector` 로 트랜잭션 경계마다 임계치를 검사해 초과 시 테스트를 실패시키는 것이다. 이렇게 하면 "새로 추가한 서비스 메서드가 쿼리 300번을 유발" 같은 사고를 PR 단계에서 잡는다.

```java
public class QueryCountInspector implements StatementInspector {

	private static final ThreadLocal<AtomicInteger> COUNTER =
			ThreadLocal.withInitial(AtomicInteger::new);

	@Override
	public String inspect(String sql) {
		COUNTER.get().incrementAndGet();
		return sql;
	}

	public static int currentCount() {
		return COUNTER.get().get();
	}

	public static void reset() {
		COUNTER.get().set(0);
	}
}
```

## 8. 읽기 전용 경로의 최적화

조회 전용 트랜잭션에서는 dirty checking 을 위한 스냅샷 자체가 낭비다. 엔티티 하나당 필드 값 복사본을 하나 더 들고 있으므로 힙 사용량이 대략 두 배가 된다.

```java
@Transactional(readOnly = true)
public List<OrderSummary> summaries(OrderStatus status) {
	return queryFactory
			.select(Projections.constructor(OrderSummary.class,
					order.id, order.orderNo, order.totalAmount, order.createdAt))
			.from(order)
			.where(order.status.eq(status))
			.fetch();
}
```

`@Transactional(readOnly = true)` 는 Hibernate 세션의 flush mode 를 `MANUAL` 로 바꿔 flush 자체를 막고, 커넥션 레벨에서도 읽기 전용 힌트를 전달한다. 여기에 DTO 프로젝션까지 쓰면 스냅샷을 아예 만들지 않는다. 실측 기준, 5만 행 조회에서 엔티티 로딩 대비 DTO 프로젝션이 힙 할당량 60~70% 감소, 응답 시간 30~40% 감소를 보이는 것이 일반적이다.

주의할 점은 DTO 프로젝션이 1차 캐시를 우회한다는 것이다. 같은 트랜잭션에서 같은 데이터를 엔티티로도 읽으면 쿼리가 두 번 나가고, 두 결과가 서로 다른 시점의 스냅샷일 수 있다. 조회 경로와 변경 경로를 서비스 단위로 분리하는 CQRS 성격의 구조가 이 혼선을 없앤다.

## 9. 진단 절차 정리

문제가 터졌을 때 순서는 다음과 같다. 첫째, `generate_statistics` 를 켜고 요청 한 건의 `PrepareStatementCount` 를 잰다. 둘째, 100을 넘으면 `default_batch_fetch_size` 를 전역으로 넣고 다시 잰다. 대개 여기서 90% 가 해결된다. 셋째, 그래도 남는 것은 단건 상세 조회이므로 해당 메서드에만 `@EntityGraph` 를 붙인다. 넷째, 페이징 로그에 `applying in memory` 가 있으면 2단계 조회로 바꾼다. 다섯째, 쓰기 경로는 `order_inserts` + 시퀀스 pooled-lo + `rewriteBatchedStatements` 세 개를 동시에 확인한다. 하나라도 빠지면 배치는 성립하지 않는다.

이 순서를 지키면 대부분의 ORM 성능 문제는 코드 구조를 뒤집지 않고 설정과 국소 수정으로 끝난다. 반대로 순서를 건너뛰고 곧바로 네이티브 쿼리로 도피하면, 도메인 규칙이 SQL 문자열 안으로 흩어져 유지보수 비용이 장기적으로 더 커진다.

## 참고

- Hibernate ORM 6.x User Guide — Batching, Fetching, Statistics 챕터
- Jakarta Persistence 3.1 Specification — §3.2 Entity Instance Lifecycle
- Vlad Mihalcea, *High-Performance Java Persistence* (2판)
- MySQL Connector/J 8.x Reference — Performance Extensions (`rewriteBatchedStatements`)
- PostgreSQL Documentation — `COPY`, Prepared Statements
