Notion 원본: https://www.notion.so/37f5a06fd6d38142a6a2e7529f6eb6c5

# Hibernate 영속성 컨텍스트 Flush 전략과 JDBC Batch Insert 및 Optimistic Lock

> 2026-06-14 신규 주제 · 확장 대상: Spring / ORM

## 학습 목표

- 영속성 컨텍스트의 dirty checking 과 flush 시점을 SQL 발생 순서까지 추적한다
- `hibernate.jdbc.batch_size` 와 `order_inserts/order_updates` 로 배치 INSERT 를 실제로 묶는다
- `@Version` 기반 낙관적 락의 UPDATE WHERE 조건과 `OptimisticLockException` 흐름을 설명한다
- flush 모드와 배치, 락이 함께 동작할 때의 성능·정합성 trade-off 를 판단한다

## 1. 영속성 컨텍스트와 dirty checking 의 실체

영속성 컨텍스트는 엔티티의 식별자를 키로 하는 1차 캐시와, 엔티티를 처음 로딩하거나 영속화할 때 찍어 둔 스냅샷(snapshot)을 함께 보관한다. dirty checking 은 flush 시점에 현재 엔티티 필드 값과 스냅샷을 필드 단위로 비교해 변경된 컬럼을 찾아 UPDATE 문을 만드는 메커니즘이다. 별도의 `save()` 호출 없이 트랜잭션 안에서 값만 바꿔도 UPDATE 가 나가는 이유가 바로 이것이다.

```java
@Transactional
public void changeNickname(Long memberId, String nickname) {
    Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new MemberNotFoundException(memberId));
    member.changeNickname(nickname); // setter 가 아닌 도메인 메서드
    // memberRepository.save(member) 가 없어도 flush 시 UPDATE 발생
}
```

기본 설정에서 Hibernate 는 변경 여부와 무관하게 매핑된 모든 컬럼을 UPDATE 에 포함한다. 변경 컬럼만 동적으로 포함하려면 `@DynamicUpdate` 를 붙이지만, 이는 매 flush 마다 SQL 문자열을 새로 생성하므로 PreparedStatement 캐시 적중률을 떨어뜨린다. 컬럼이 매우 많고 일부만 자주 바뀌는 와이드 테이블에서만 선택적으로 쓰는 것이 정석이다.

## 2. flush 가 일어나는 정확한 시점

flush 는 영속성 컨텍스트의 변경 내용을 DB 에 SQL 로 내보내는 동작이며, 트랜잭션 커밋과는 구분된다. 기본 `FlushModeType.AUTO` 에서 flush 가 트리거되는 지점은 세 가지다. 첫째 트랜잭션 커밋 직전, 둘째 JPQL/Criteria 쿼리 실행 직전(쿼리 결과 정합성을 위해), 셋째 명시적 `EntityManager.flush()` 호출이다. 중요한 점은 `find()` 같은 식별자 기반 조회는 flush 를 유발하지 않는다는 것이다. 1차 캐시에서 바로 반환하거나 단순 SELECT 이므로 보류 중인 변경과 충돌하지 않기 때문이다.

```java
@Transactional
public void demo(EntityManager em) {
    Member m = new Member("kim");
    em.persist(m);                 // INSERT 는 아직 보류(쓰기 지연 SQL 저장소에 적재)
    em.find(Member.class, m.getId()); // flush 안 함 → 1차 캐시 hit
    Long count = em.createQuery("select count(m) from Member m", Long.class)
        .getSingleResult();        // JPQL 실행 직전 flush → 여기서 INSERT 발생
}
```

`FlushModeType.COMMIT` 으로 바꾸면 쿼리 실행 직전 flush 를 생략한다. 같은 트랜잭션에서 INSERT 한 데이터를 곧바로 JPQL 로 다시 읽지 않는 배치성 작업이라면 불필요한 중간 flush 를 줄여 성능에 유리하지만, 읽기 정합성이 깨질 수 있으니 의도를 분명히 하고 적용해야 한다.

## 3. 쓰기 지연(write-behind)과 INSERT 배치

`persist()` 시점에 INSERT 가 즉시 나가지 않고 쓰기 지연 SQL 저장소(ActionQueue)에 쌓였다가 flush 때 한꺼번에 나가는 것을 쓰기 지연이라 한다. 이 지연 덕분에 여러 INSERT 를 JDBC batch 로 묶을 여지가 생긴다. 다만 식별자 생성 전략이 `IDENTITY` 이면 INSERT 를 실행해야 PK 를 알 수 있으므로 쓰기 지연 자체가 무력화되고 배치도 불가능하다. 배치 INSERT 를 쓰려면 `SEQUENCE` 또는 `TABLE` 전략을 선택해야 한다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50          # 50개씩 PreparedStatement.addBatch()
        order_inserts: true        # 같은 테이블 INSERT 끼리 정렬해 배치 단절 방지
        order_updates: true
        batch_versioned_data: true # @Version 엔티티도 배치 UPDATE 허용
```

```java
@Entity
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 50)
    private Long id;
}
```

`allocationSize` 를 `batch_size` 와 맞추면 시퀀스 호출 라운드트립도 50건당 1회로 줄어든다. `order_inserts` 가 핵심인 이유는, A·B·A·B 순서로 persist 하면 JDBC 배치가 테이블이 바뀔 때마다 끊겨 4개의 단건 실행이 되지만, 정렬하면 A·A / B·B 두 배치로 묶이기 때문이다.

다음 표는 1만 건 INSERT 의 대략적 비교다(로컬 MySQL, 단일 트랜잭션 기준 경향값).

| 설정 | 실행 SQL 라운드트립 | 상대 소요 |
|------|------------------|----------|
| IDENTITY, batch 미적용 | 약 10,000회 | 1.0x (기준, 가장 느림) |
| SEQUENCE, batch_size 미설정 | 약 10,000회 | 0.95x |
| SEQUENCE, batch_size=50 | 약 200회 | 0.15~0.25x |
| 위 + rewriteBatchedStatements(MySQL) | multi-row INSERT | 0.08~0.15x |

MySQL JDBC 드라이버는 `rewriteBatchedStatements=true` 를 줘야 `addBatch()` 한 INSERT 를 `INSERT ... VALUES (...),(...),(...)` 형태의 멀티 로우로 재작성한다. 이 옵션 없이 batch 만 켜면 네트워크 라운드트립은 줄지만 서버 측 파싱·실행은 여전히 건당 발생한다.

## 4. @Version 낙관적 락의 UPDATE 메커니즘

낙관적 락은 DB 락을 잡지 않고, 버전 컬럼을 UPDATE 의 WHERE 조건에 넣어 "내가 읽은 버전과 현재 버전이 같을 때만" 갱신하는 방식이다. `@Version` 컬럼은 매 UPDATE 마다 1 증가하며, 영향받은 행 수가 0이면 그 사이 다른 트랜잭션이 먼저 갱신한 것으로 보고 예외를 던진다.

```java
@Entity
public class Product {
    @Id private Long id;
    private int stock;
    @Version private long version; // long/int/short/Timestamp 가능
}
```

```sql
-- member 가 version=3 으로 읽은 뒤 재고를 변경하면 flush 시
UPDATE product SET stock = ?, version = 4
WHERE id = ? AND version = 3;
-- affected rows = 0 이면 Hibernate 가 StaleObjectStateException 발생
--   → JPA 경계에서 OptimisticLockException 으로 변환
```

동시 차감 시나리오에서 두 트랜잭션이 같은 version=3 을 읽으면 먼저 커밋한 쪽만 성공하고 나머지는 충돌로 실패한다. 충돌은 정상 흐름의 일부이므로 재시도 전략이 필요하다.

```java
@Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
           maxAttempts = 3, backoff = @Backoff(delay = 50))
@Transactional
public void decreaseStock(Long productId, int quantity) {
    Product product = productRepository.findById(productId).orElseThrow();
    product.decrease(quantity); // 재고 부족이면 도메인 예외
}
```

재시도는 반드시 트랜잭션 밖에서 다시 시작해야 한다. 같은 영속성 컨텍스트 안에서 다시 시도하면 충돌난 더티 상태가 그대로 남아 의미가 없다. `@Retryable` 이 붙은 public 메서드를 프록시 경계 바깥에서 호출하거나, 별도 facade 에서 트랜잭션 메서드를 재호출하는 구조로 가야 한다.

낙관적 락은 충돌이 드문 읽기 위주 워크로드에 적합하다. 충돌이 빈번한 인기 상품 재고 같은 핫스팟에서는 재시도 폭주로 오히려 처리량이 떨어지므로, 이 경우 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 로 `SELECT ... FOR UPDATE` 비관적 락을 쓰거나 Redis 등 외부 카운터로 분리하는 편이 낫다.

## 5. flush·batch·락이 함께 동작할 때의 주의점

`batch_versioned_data=true` 를 켜지 않으면 `@Version` 엔티티의 UPDATE 는 배치로 묶이지 않는다. Hibernate 가 affected rows 를 건별로 확인해 낙관적 락 충돌을 판정해야 하는데, 일부 JDBC 드라이버가 배치 실행 시 정확한 건별 카운트를 돌려주지 못했던 역사적 이유 때문이다. 최신 MySQL/PostgreSQL 드라이버는 안정적이므로 이 옵션을 켜고 배치 UPDATE 와 낙관적 락을 함께 쓸 수 있다.

또한 대량 작업에서 영속성 컨텍스트에 엔티티가 무한정 쌓이면 dirty checking 비용과 메모리가 함께 증가한다. `batch_size` 단위로 `flush()` 후 `clear()` 를 호출해 1차 캐시를 비워 줘야 한다.

```java
@Transactional
public void bulkInsert(List<Order> orders) {
    for (int i = 0; i < orders.size(); i++) {
        entityManager.persist(orders.get(i));
        if (i % 50 == 0) {       // batch_size 와 동일 주기
            entityManager.flush();
            entityManager.clear(); // 누적 엔티티 detach → 메모리·비교비용 절감
        }
    }
}
```

`clear()` 이후에는 기존 엔티티가 준영속 상태가 되므로, clear 전에 참조하던 객체를 이후에 다시 변경해도 dirty checking 대상이 아니다. 배치 루프 밖에서 같은 엔티티를 만지지 않도록 설계해야 한다. 정리하면 Yes/No 관점에서, 배치 INSERT 에 IDENTITY 전략을 쓸 수 있는가는 No 이고, 낙관적 락과 배치 UPDATE 를 함께 쓸 수 있는가는 `batch_versioned_data` 를 켜면 Yes 다.

## 6. 1차 캐시·쓰기 지연과 N+1 의 관계

영속성 컨텍스트는 같은 식별자 조회를 1차 캐시로 처리하지만, 연관 엔티티를 지연 로딩(LAZY)할 때는 프록시 초기화가 매번 별도 SELECT 를 유발해 N+1 문제로 이어진다. 이는 flush·batch 와는 다른 축의 비용이지만, 같은 트랜잭션 안에서 함께 작동하므로 통합적으로 봐야 한다. 부모 N건을 조회한 뒤 각 부모의 자식 컬렉션을 건드리면 1(부모) + N(자식) 쿼리가 나간다.

```java
// N+1 발생: orders 1번 + 각 order 의 member 프록시 초기화 N번
List<Order> orders = em.createQuery("select o from Order o", Order.class).getResultList();
for (Order o : orders) {
    o.getMember().getName(); // 매 반복마다 SELECT member ...
}
```

해결책은 세 가지다. 첫째 fetch join 으로 한 번에 가져온다. 둘째 `@BatchSize` 또는 `hibernate.default_batch_fetch_size` 로 프록시 초기화를 IN 절로 묶는다. 셋째 `@EntityGraph` 로 조회 시점에 로딩 범위를 선언한다.

```java
@Query("select o from Order o join fetch o.member")
List<Order> findAllWithMember(); // 단일 조인 쿼리로 N+1 제거
```

`default_batch_fetch_size` 는 컬렉션·프록시 로딩을 자동으로 IN 절 배치로 바꿔 N+1 을 N/100+1 수준으로 낮추는 가장 손쉬운 전역 처방이다. 컬렉션은 batch fetch, 단일 연관은 fetch join 으로 조합하는 것이 실무 정석이다.

## 7. flush 모드와 읽기 일관성 trade-off 정리

한 트랜잭션 타임라인에서 종합하면, persist 는 쓰기 지연 큐에 쌓이고, JPQL 실행이나 commit 이 flush 를 트리거하며, flush 시 dirty checking 으로 UPDATE 가, ActionQueue 정렬로 INSERT 배치가 만들어지고, `@Version` 엔티티는 affected rows 로 낙관적 락을 판정한다.

| 상황 | 권장 조합 | 이유 |
|------|----------|------|
| 대량 INSERT 배치 | SEQUENCE + batch_size + order_inserts + FlushMode.COMMIT | 중간 flush 제거, 배치 단절 방지 |
| 읽기-쓰기 혼합 트랜잭션 | FlushMode.AUTO(기본) | 쿼리 전 flush 로 읽기 정합성 보장 |
| 충돌 드문 갱신 | @Version 낙관적 락 + 재시도 | 락 대기 없이 처리량 확보 |
| 핫스팟 갱신 | PESSIMISTIC_WRITE 또는 외부 카운터 | 재시도 폭주 회피 |

핵심 판단은 "이 트랜잭션이 쓴 데이터를 같은 트랜잭션에서 JPQL 로 다시 읽는가"이다. 답이 Yes 면 AUTO 를 유지해야 하고, No(순수 적재)면 COMMIT 으로 낮춰 중간 flush 비용을 없앤다. 모든 선택이 정합성과 처리량의 교환이라는 한 가지 축 위에 있다.

## 참고

- Hibernate ORM User Guide — Flushing, Batching, Locking, Fetching 장 (hibernate.org/orm/documentation)
- Jakarta Persistence 3.1 Specification — Optimistic Locking and Version Attributes
- MySQL Connector/J Reference — rewriteBatchedStatements 동작
- Vlad Mihalcea, "High-Performance Java Persistence" — Batching, Concurrency Control 챕터
