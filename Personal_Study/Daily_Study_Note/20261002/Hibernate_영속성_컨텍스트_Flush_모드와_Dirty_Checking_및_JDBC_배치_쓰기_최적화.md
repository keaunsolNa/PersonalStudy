Notion 원본: https://www.notion.so/3ed5a06fd6d3816faf1fe99ae119ce0f

# Hibernate 영속성 컨텍스트 Flush 모드와 Dirty Checking 및 JDBC 배치 쓰기 최적화

> 2026-10-02 신규 주제 · 확장 대상: ORM

## 학습 목표

- FlushMode별 flush 발생 시점을 구분하고 AUTO가 쿼리 직전에 flush를 끼워 넣는 조건을 추적한다.
- 스냅샷 기반 dirty checking의 비용 구조를 분석하고 read-only, `@Immutable`, bytecode enhancement로 줄인다.
- ID 전략, `batch_size`, 정렬 옵션, 드라이버 옵션을 조합해 JDBC 배치가 실제로 동작하게 구성한다.
- 통계와 프록시 로그로 배치 적용 여부를 직접 검증하고 대량 쓰기 패턴을 상황에 맞게 고른다.

## 1. 쓰기 지연과 영속성 컨텍스트의 구조

Hibernate의 쓰기 성능은 flush, dirty checking, JDBC 배치라는 세 메커니즘이 맞물려 결정된다. `persist`, 필드 변경, `remove`는 호출 즉시 SQL이 되지 않고 영속성 컨텍스트의 ActionQueue에 쌓인다. flush가 일어나면 큐가 정해진 순서로 실행되고, 이때 JDBC 배치가 켜져 있으면 같은 모양의 문장이 `addBatch`로 모였다가 `executeBatch` 한 번으로 DB에 전달된다. 즉 flush는 "언제 어떤 순서로" 나가는지를, dirty checking은 "무엇이" 나가는지를, 배치는 "얼마나 적은 왕복으로" 나가는지를 정한다.

영속성 컨텍스트는 식별자별 엔티티 인스턴스를 보관하는 1차 캐시이면서, 엔티티를 로딩하거나 저장한 시점의 속성 값을 복사해 둔 loadedState(스냅샷)를 함께 들고 있다. 이 스냅샷이 dirty checking의 비교 기준이다. 따라서 관리 중인 엔티티가 늘어날수록 메모리는 스냅샷만큼 더 쓰고, flush마다 비교할 대상도 늘어난다. 쓰기 지연의 예외는 IDENTITY 전략으로, PK를 알아야 영속 상태가 되므로 `persist` 시점에 insert가 즉시 실행된다. 이 예외가 6장에서 다룰 배치 불가 문제의 원인이다.

```text
persist / setter / remove
        -> 영속성 컨텍스트 (1차 캐시 + 스냅샷)
        -> flush: dirty checking, ActionQueue 구성
        -> ActionQueue 실행: addBatch ... executeBatch
        -> commit
```

트레이드오프는 명확하다. 쓰기 지연 덕분에 같은 행에 대한 여러 변경이 하나의 update로 합쳐지고 배치가 가능해지지만, 그 대가로 컨텍스트가 커질수록 메모리와 flush 비용이 선형으로 증가한다.

## 2. Flush 시점과 FlushMode

flush는 세 경우에 일어난다. `flush()`를 명시적으로 호출할 때, 트랜잭션이 commit되기 직전, 그리고 FlushMode가 AUTO일 때 쿼리 실행 직전이다. JPA 표준 `FlushModeType`은 AUTO와 COMMIT 두 가지이고, Hibernate 고유 `FlushMode`는 여기에 ALWAYS와 MANUAL을 더해 네 가지다. AUTO는 기본값으로 commit 시점과 "변경 중인 테이블과 겹치는 쿼리" 직전에 flush한다. COMMIT은 쿼리 직전 flush를 생략하므로 메모리의 변경분을 쿼리가 보지 못할 수 있다. ALWAYS는 모든 쿼리 직전에 무조건 flush하므로 거의 쓰지 않는다. MANUAL은 명시 호출 때만 flush하며 읽기 전용 작업에 어울린다.

AUTO의 세부 동작이 실무에서 가장 자주 문제가 된다. HQL과 JPQL은 쿼리가 참조하는 엔티티 테이블(query space)을 계산해 보류 중인 액션이 그 테이블과 겹칠 때만 flush한다. 겹치지 않으면 flush를 생략하므로 불필요한 SQL이 나가지 않는다. 네이티브 SQL은 Hibernate가 어떤 테이블을 읽는지 알 수 없다. JPA 방식(`EntityManager`)으로 부트스트랩한 경우 보수적으로 전체를 flush하는 것이 일반적이고, 네이티브 Hibernate API에서는 synchronized query space를 직접 지정하지 않으면 flush가 생략될 수 있다. 버전별 차이가 있으므로 사용 중인 버전에서 SQL 로그로 확인해야 한다. `find`와 `getReference` 같은 식별자 조회는 flush를 유발하지 않는다.

```java
// 쿼리 단위로 flush 모드 지정
List<Order> orders = entityManager.createQuery("select o from Order o", Order.class)
	.setFlushMode(FlushModeType.COMMIT)
	.getResultList();

// 세션 단위로 Hibernate 고유 모드 지정
Session session = entityManager.unwrap(Session.class);
session.setHibernateFlushMode(FlushMode.MANUAL);
```

트레이드오프는 일관성과 비용의 교환이다. AUTO는 "방금 저장한 데이터가 쿼리에 보인다"는 직관을 지켜 주지만, 배치 루프 안에 조회 쿼리가 끼면 매 반복마다 flush가 발생해 배치가 쪼개진다. 배치 구간에서는 조회를 루프 밖으로 빼거나, 해당 구간만 COMMIT 모드를 쓰되 쿼리 결과가 변경분을 반영하지 않는다는 점을 감수해야 한다.

## 3. Flush 실행 순서와 Spring readOnly

ActionQueue는 코드를 작성한 순서가 아니라 고정된 순서로 실행된다. 엔티티 insert, 엔티티 update, 컬렉션 삭제, 컬렉션 요소의 삭제와 수정과 추가, 마지막으로 엔티티 delete 순이다. 이 순서 때문에 "기존 행을 삭제하고 같은 유니크 키로 다시 insert"하는 코드는 insert가 delete보다 먼저 실행되어 유니크 제약 위반으로 실패한다. 이런 경우 삭제 직후 `flush()`를 명시해 순서를 강제한다.

```java
orderRepository.delete(existing);
entityManager.flush();            // delete를 먼저 DB에 반영
orderRepository.save(replacement); // 같은 유니크 키로 insert
```

Spring에서 `@Transactional(readOnly = true)`를 쓰면 Hibernate 세션의 FlushMode를 MANUAL로 바꾸고 JDBC 연결에도 read-only 힌트를 전달한다. 그 결과 flush와 dirty checking이 모두 생략되어 CPU와 메모리가 절약된다. 반대로 readOnly 트랜잭션 안에서 엔티티를 수정하면 예외 없이 조용히 DB에 반영되지 않으므로, 조회 메서드에 변경 로직이 섞이지 않도록 주의해야 한다.

```java
@Transactional(readOnly = true)
public List<OrderSummary> findRecentOrders() {
	return orderRepository.findRecent();
}
```

## 4. Dirty Checking 동작과 비용

기본 dirty checking은 flush 시점에 영속 상태의 모든 엔티티를 순회하며 현재 속성 값을 loadedState와 필드별로 비교한다. 다른 값이 하나라도 있으면 update 액션을 만든다. 엔티티 수를 N, 속성 수를 M이라 하면 flush마다 N과 M의 곱에 비례하는 비교가 일어난다. 수만 건을 읽어 가공만 하는 배치에서 이 비용이 눈에 띄게 커지고, 같은 컨텍스트에서 flush가 반복되면 매번 전체를 다시 비교하므로 누적 비용이 이차로 늘어난다.

비교의 정확성도 신경 써야 한다. `@Embedded` 값 객체나 `Date`, 배열, JSON 매핑 같은 가변 타입은 동등성 판정 방식에 따라 값이 같아도 매번 dirty로 판정될 수 있다. 이 경우 변경한 적 없는 엔티티에 update가 나간다. `equals`와 `hashCode` 구현, 불변 타입 사용, 타입 매핑의 `MutabilityPlan`을 점검해야 한다. `@Version` 필드는 update마다 자동 증가하고, 배치 update에서는 영향 행 수 검증 방식에 영향을 주므로 `hibernate.jdbc.batch_versioned_data` 설정과 함께 고려한다.

## 5. 갱신 SQL 형태와 비용 절감

기본 update SQL은 변경 여부와 무관하게 모든 컬럼을 set하는 정적 문장이며 시작 시 한 번 생성되어 재사용된다. 문장이 항상 같기 때문에 PreparedStatement 캐시와 배치 묶음에 유리하다. `@DynamicUpdate`를 붙이면 변경된 컬럼만 set하는 SQL을 매번 만든다. 컬럼이 매우 많거나 큰 LOB 또는 JSON 컬럼이 있어 불필요한 전송을 줄이고 싶을 때, 혹은 컬럼 단위로 락 경합을 낮추고 싶을 때 유리하다. 그러나 변경된 컬럼 조합이 다르면 문장 모양이 달라져 배치가 쪼개지고 SQL 생성 비용이 매번 든다. 컬럼이 적고 변경 패턴이 일정하다면 기본값이 대체로 낫다.

dirty checking 자체를 줄이는 방법은 네 가지다. 첫째, 조회에 읽기 전용 힌트를 주면 스냅샷을 만들지 않아 비교 대상에서 빠진다. 둘째, 변하지 않는 엔티티에 `@Immutable`을 선언한다. 셋째, 엔티티 대신 DTO 프로젝션으로 읽어 영속성 컨텍스트에 올리지 않는다. 넷째, 빌드 시점 bytecode enhancement를 적용하고 `hibernate.enhancer.enableDirtyTracking=true`로 설정하면 setter 호출 때 변경 필드를 기록하므로 flush에서 전체 비교가 필요 없다. 엔티티가 많고 변경 비율이 낮을수록 효과가 크지만, 빌드 설정과 디버깅 복잡도가 늘어나는 대가가 있다.

```java
List<Product> products = entityManager.createQuery("select p from Product p", Product.class)
	.setHint("org.hibernate.readOnly", true) // 스냅샷 생성과 dirty checking 생략
	.getResultList();
```

## 6. JDBC 배치 설정과 ID 전략

배치의 기본 설정은 세 가지 속성으로 이루어진다. `hibernate.jdbc.batch_size`는 한 배치에 묶을 문장 수로 0이면 배치가 꺼지며, 보통 20에서 100 사이에서 측정해 정한다. `hibernate.order_inserts`는 부모와 자식 엔티티의 insert가 섞여 있어도 엔티티별로 정렬해 배치가 끊기지 않게 한다. `hibernate.order_updates`는 update를 엔티티와 PK 순으로 정렬해 배치 효율을 높이고 데드락 가능성도 낮춘다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          batch_versioned_data: true
        order_inserts: true
        order_updates: true
```

가장 흔한 함정은 ID 전략이다. IDENTITY는 `persist` 즉시 insert를 실행해야 PK를 얻으므로 insert 배치가 사실상 비활성화된다. SEQUENCE는 시퀀스 값을 미리 받아 두는 pooled 최적화 덕분에 insert를 flush까지 미룰 수 있어 배치가 가능하다. 이때 `allocationSize`와 DB 시퀀스의 `INCREMENT BY`가 반드시 같아야 한다. 다르면 ID 충돌이 나거나 시퀀스 호출이 매번 일어난다. TABLE 전략은 배치는 되지만 별도 테이블 락 경합 때문에 느리고, UUID나 애플리케이션 할당 ID는 DB 왕복 없이 배치가 가능하다.

```java
@Entity
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_line_seq")
	@SequenceGenerator(name = "order_line_seq", sequenceName = "order_line_seq", allocationSize = 50)
	private Long id;
}
```

```sql
CREATE SEQUENCE order_line_seq START WITH 1 INCREMENT BY 50;
```

Hibernate가 `executeBatch`를 호출해도 드라이버가 문장을 하나로 다시 쓰지 않으면 서버로는 개별 문장이 전송되는 경우가 있다. MySQL과 MariaDB는 JDBC URL에 `rewriteBatchedStatements=true`, PostgreSQL은 `reWriteBatchedInserts=true`를 지정해야 멀티 로우 문장으로 합쳐진다. 배치는 연속된 동일 SQL, 동일 테이블일 때만 묶이므로 정렬 옵션이 꺼져 다른 테이블 insert가 끼거나, insert 사이에 update나 delete가 끼거나, 쿼리 실행으로 AUTO flush가 유발되면 현재 배치가 먼저 실행되어 효과가 줄어든다. MySQL처럼 시퀀스가 없는 DB에서 IDENTITY가 필수라면 대량 insert는 JdbcTemplate의 `batchUpdate`나 네이티브 멀티 로우 insert 같은 JPA 밖의 경로를 고려한다.

## 7. 대량 쓰기 패턴

수만 건 이상을 한 트랜잭션에서 저장할 때의 표준 패턴은 `batch_size` 주기로 `flush()`와 `clear()`를 반복하는 것이다. flush로 배치를 실행하고 clear로 컨텍스트를 비워 메모리 증가와 dirty checking 누적 비용을 막는다. flush 주기를 `batch_size`와 맞추면 배치가 균일하게 실행된다. clear 이후 이전 엔티티는 준영속이 되므로 이후에 다시 쓰지 않도록 주의하고, 트랜잭션이 너무 길어지면 undo 로그와 락 유지 시간이 커지므로 청크 단위로 트랜잭션을 나누는 것도 고려한다.

```java
@Transactional
public void saveAll(List<OrderLine> lines) {
	int batchSize = 50;
	for (int index = 0; index < lines.size(); index++) {
		entityManager.persist(lines.get(index));
		if ((index + 1) % batchSize == 0) {
			entityManager.flush();
			entityManager.clear();
		}
	}
	entityManager.flush();
	entityManager.clear();
}
```

영속성 컨텍스트가 필요 없는 단순 대량 작업에는 `StatelessSession`이 가볍다. 1차 캐시, dirty checking, cascade, 지연 로딩이 없어 insert와 update가 곧바로 SQL로 변환된다. 대신 이벤트 리스너와 2차 캐시 연동이 적용되지 않고 연관 엔티티 처리를 직접 해야 한다. 조건 기반 일괄 변경은 벌크 JPQL이 가장 빠르다. 단일 SQL로 처리되지만 영속성 컨텍스트를 우회하므로, 실행 전에 보류 변경을 flush하고 실행 후 `clear()`로 오래된 엔티티가 남지 않게 해야 한다.

```java
try (StatelessSession statelessSession = sessionFactory.openStatelessSession()) {
	Transaction transaction = statelessSession.beginTransaction();
	for (OrderLine line : lines) {
		statelessSession.insert(line);
	}
	transaction.commit();
}
```

<table header-row="true">
<tr><td>상황</td><td>권장 방법</td><td>대가</td></tr>
<tr><td>소량 변경</td><td>기본 dirty checking</td><td>컨텍스트 크기에 비례한 flush 비용</td></tr>
<tr><td>대량 insert, SEQUENCE 가능</td><td>batch_size, order_inserts, flush와 clear</td><td>준영속 엔티티 관리 필요</td></tr>
<tr><td>IDENTITY 고정</td><td>JdbcTemplate, StatelessSession</td><td>cascade와 리스너 미적용</td></tr>
<tr><td>조건 기반 일괄 변경</td><td>벌크 JPQL 또는 네이티브 SQL</td><td>컨텍스트 불일치, 버전 증가 수동 처리</td></tr>
<tr><td>대량 조회 후 가공</td><td>read-only 힌트, DTO 프로젝션</td><td>변경 감지 불가</td></tr>
</table>

## 8. 검증과 운영 트레이드오프

설정을 넣었다고 배치가 적용된 것은 아니므로 반드시 실측으로 확인한다. `hibernate.generate_statistics=true`를 켜면 `Statistics`의 `getPrepareStatementCount()`와 `getEntityInsertCount()`를 비교해 문장 수가 줄었는지 볼 수 있다. datasource-proxy나 p6spy 같은 프록시 데이터소스는 배치 크기를 로그로 보여 주며, `org.hibernate.SQL` 로거는 문장 단위로만 찍혀 배치 여부를 알려 주지 못한다는 점에 주의한다. `org.hibernate.engine.jdbc.batch.internal.BatchingBatch`를 DEBUG로 켜거나 DB의 general log, pg_stat_statements로 서버가 받은 문장 수를 확인하면 가장 확실하다.

운영 관점의 판단 기준은 다음과 같다. `batch_size`를 키우면 왕복은 줄지만 드라이버 메모리, 단일 문장 크기, 실패 시 롤백 범위가 커지므로 부하 테스트로 정한다. `order_inserts`와 `order_updates`는 정렬 비용이 들지만 대부분 이득이 더 크다. 읽기 경로에는 readOnly와 DTO를 기본으로 하고, 쓰기 경로에서는 컨텍스트를 작게 유지하며, 대량 처리는 ID 전략부터 점검하는 순서가 시행착오를 줄인다.

## 참고

- Hibernate ORM User Guide, Flushing: https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#flushing
- Hibernate ORM User Guide, Batching: https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch
- Hibernate ORM User Guide, Bytecode Enhancement: https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement
- Spring Framework Reference, Transaction Management: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
