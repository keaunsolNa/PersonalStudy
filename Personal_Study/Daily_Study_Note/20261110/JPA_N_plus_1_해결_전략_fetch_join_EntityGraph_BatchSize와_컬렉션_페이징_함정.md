Notion 원본: https://www.notion.so/4145a06fd6d381b39aa6e82f91ca9b90

# JPA N+1 해결 전략: fetch join·EntityGraph·@BatchSize 비교와 컬렉션 페이징 함정

> 2026-11-10 신규 주제 · 확장 대상: ORM / Spring Data JPA (조회 성능)

## 학습 목표

- N+1 이 발생하는 지점(지연 로딩 프록시 초기화, EAGER 의 JPQL 재조회)을 쿼리 로그로 식별한다
- fetch join, `@EntityGraph`, `@BatchSize` 의 SQL 형태와 적용 한계를 비교한다
- 컬렉션 fetch join 과 페이징을 함께 쓸 때의 메모리 페이징 경고와 `MultipleBagFetchException` 을 회피한다
- 쿼리 수를 테스트로 고정해 회귀를 막는다

## 1. N+1 이 생기는 구조

`Order` 100건을 조회하는 쿼리 1번 이후, 각 `Order.items`(`@OneToMany`, 기본 LAZY)를 순회할 때마다 컬렉션 초기화 쿼리가 1번씩 나가면 총 1+100번이다. 연관이 `@ManyToOne` 이고 EAGER(JPA 기본값)인 경우에는 더 눈에 띄지 않게 발생한다. `em.find` 는 JOIN 으로 한 번에 가져오지만, **JPQL/Spring Data 메서드로 조회하면 JPQL 이 먼저 실행된 뒤 EAGER 연관을 별도 SELECT 로 채운다.** 그래서 `@ManyToOne` 은 명시적으로 `fetch = FetchType.LAZY` 를 두는 것이 기본 원칙이다.

```java
@Entity
class Orders {
    @Id @GeneratedValue Long id;
    @ManyToOne(fetch = FetchType.LAZY) Member member;
    @OneToMany(mappedBy = "orders") List<OrderItem> items = new ArrayList<>();
}
```

문제 확인은 `spring.jpa.properties.hibernate.generate_statistics=true` 와 `logging.level.org.hibernate.SQL=DEBUG` 로 한다. 통계에는 요청당 prepared statement 수가 찍힌다.

## 2. fetch join

JPQL 에서 연관을 한 번의 JOIN 쿼리로 함께 로딩한다.

```java
@Query("select o from Orders o join fetch o.member where o.status = :s")
List<Orders> findWithMember(@Param("s") Status s);
```

`@ManyToOne`/`@OneToOne` 에는 행 수가 늘지 않으므로 부작용이 거의 없다. 컬렉션(`@OneToMany`) fetch join 은 부모 행이 자식 수만큼 **중복**되므로 `distinct` 가 필요하다(Hibernate 6 에서는 부모 엔티티 중복이 자동으로 제거되지만, 5 이하에서는 `select distinct` 가 필요하다). 더 큰 문제는 두 가지다.

첫째, **컬렉션 fetch join + 페이징**. `setFirstResult/setMaxResults` 와 함께 쓰면 Hibernate 는 DB 에서 LIMIT 을 걸 수 없다고 판단해 전체를 읽고 메모리에서 잘라 내며 경고(`HHH000104: firstResult/maxResults specified with collection fetch; applying in memory`)를 남긴다. 데이터가 많으면 OOM 위험이다.

둘째, **둘 이상의 List(bag) 컬렉션 동시 fetch join** 은 `MultipleBagFetchException` 이다. 한 컬렉션은 fetch join, 나머지는 BatchSize 로 푸는 식으로 분리한다.

## 3. @EntityGraph

메서드 선언만으로 fetch join 과 유사한 로딩을 지정한다.

```java
@EntityGraph(attributePaths = {"member"})
@Query("select o from Orders o where o.status = :s")
List<Orders> findGraph(@Param("s") Status s);
```

JPQL 을 수정하지 않고 재사용하기 쉽지만, 내부적으로 LEFT OUTER JOIN 을 쓰므로 컬렉션을 지정하면 fetch join 과 같은 중복·페이징 문제가 그대로 발생한다. 정적 그래프(`@NamedEntityGraph`)는 도메인에 로딩 정책이 침투하므로 조회 전용 리포지토리 메서드에 `attributePaths` 로 두는 쪽이 결합도가 낮다.

## 4. @BatchSize 와 default_batch_fetch_size

지연 로딩 초기화를 **IN 절 묶음 쿼리**로 바꾼다. 쿼리 수를 N+1 에서 1+ceil(N/size) 로 줄인다.

```yaml
spring:
  jpa:
    properties:
      hibernate.default_batch_fetch_size: 100
```

```java
@BatchSize(size = 100)
@OneToMany(mappedBy = "orders")
List<OrderItem> items = new ArrayList<>();
```

100 건의 `Orders` 중 `items` 를 처음 접근하면 `where order_id in (?, ?, ... )` 한 번으로 묶어서 로딩한다. 장점은 **컬렉션이 있어도 페이징이 DB LIMIT 으로 유지**되고, 여러 컬렉션을 동시에 풀 수 있다는 점이다. 단점은 쿼리가 여전히 1+k 번이라는 것과, 바인딩 파라미터가 많아 DB 의 IN 목록 한계(Oracle 은 IN 리스트 1000개 제한)에 주의해야 한다는 점이다. 보통 100~1000 사이에서 조정한다. 전역 설정이 가장 부담이 적고, 특정 연관만 조정할 땐 `@BatchSize` 를 쓴다.

## 5. 선택 기준

| 상황 | 권장 |
|---|---|
| `@ManyToOne`/`@OneToOne` 연관 | fetch join 또는 EntityGraph |
| 컬렉션 1개 + 페이징 없음 | 컬렉션 fetch join(+distinct) |
| 컬렉션 + 페이징 | 부모만 페이징 조회 후 `@BatchSize` 로 컬렉션 |
| 컬렉션 2개 이상 | 하나만 fetch join, 나머지 `@BatchSize` |
| 화면 전용 읽기 | DTO 프로젝션(`select new ...`)으로 필요한 컬럼만 |

DTO 프로젝션은 영속성 컨텍스트 관리와 dirty checking 비용이 없고 SELECT 컬럼도 줄어 읽기 전용 목록 API 에 가장 가볍다. 대신 엔티티 재사용성이 낮고 쿼리별 DTO 가 늘어난다.

## 6. 쿼리 수 테스트로 고정하기

회귀 방지에는 Hibernate `Statistics` 로 쿼리 수를 단언한다.

```java
@DataJpaTest
class OrderQueryTest {
    @Autowired EntityManager em;
    @Autowired OrderRepository repo;

    @Test
    void 주문목록_조회는_쿼리_2회_이하() {
        Statistics stats = em.getEntityManagerFactory()
            .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<Orders> list = repo.findWithMember(Status.PAID);
        list.forEach(o -> o.getMember().getName());   // 프록시 초기화 시도

        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }
}
```

fetch join 이 빠지면 `getName()` 호출마다 쿼리가 나가 카운트가 늘어 테스트가 실패한다. 라이브러리(`datasource-proxy` 등)로 SQL 수를 세는 방법도 있다.

## 7. 주의: Open-in-View 와 트랜잭션 경계

`spring.jpa.open-in-view`(기본 true)가 켜져 있으면 컨트롤러·뷰 렌더링까지 영속성 컨텍스트가 열려 있어 직렬화 중 지연 로딩이 조용히 N+1 을 만든다. 운영 API 서버는 보통 `false` 로 끄고, 서비스 계층 트랜잭션 안에서 필요한 연관을 모두 로딩해 DTO 로 변환한 뒤 반환하는 구조를 택한다. 이 경우 누락 시 `LazyInitializationException` 으로 **빨리 실패**하므로 문제가 개발 단계에서 드러난다.

## 참고

- Hibernate ORM User Guide: Fetching (Batch fetching, Fetch joins)
- Spring Data JPA Reference: Configuring Fetch- and LoadGraphs
- Vlad Mihalcea, "The best way to fix the Hibernate MultipleBagFetchException"
