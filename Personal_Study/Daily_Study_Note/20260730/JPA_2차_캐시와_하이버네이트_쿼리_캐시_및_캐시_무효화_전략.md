Notion 원본: https://app.notion.com/p/3ac5a06fd6d38152beeceb9e7c3685b5

# JPA 2차 캐시와 하이버네이트 쿼리 캐시 및 캐시 무효화 전략

> 2026-07-30 신규 주제 · 확장 대상: ORM / Spring Data JPA(영속성 컨텍스트 이후의 캐시 계층)

## 학습 목표

- 1차 캐시(영속성 컨텍스트)와 2차 캐시(SessionFactory 공유)의 생명주기·범위 차이를 구분한다.
- `@Cache` 전략(READ_ONLY / NONSTRICT_READ_WRITE / READ_WRITE / TRANSACTIONAL)이 정합성과 성능에 미치는 영향을 정리한다.
- 쿼리 캐시가 왜 엔티티 캐시와 별도로 동작하며 어떤 함정(타임스탬프 무효화, 컬렉션 캐시 의존)이 있는지 파악한다.
- 분산 환경에서 캐시 무효화가 stale read 를 어떻게 만드는지, TTL·무효화 이벤트로 어떻게 방어하는지 직접 구성해 검증한다.

## 1. 1차 캐시로는 부족한 지점

JPA 의 1차 캐시는 `EntityManager`(하이버네이트 `Session`) 하나에 묶인다. 트랜잭션이 끝나면 영속성 컨텍스트가 닫히고 캐시도 사라진다. 즉 웹 요청 A 가 로드한 엔티티는 요청 B 가 재사용할 수 없다. 매 요청마다 같은 코드 테이블, 같은 설정 엔티티를 다시 SELECT 하는 낭비가 여기서 발생한다.

2차 캐시는 `SessionFactory`(= `EntityManagerFactory`) 수준에서 공유되므로 트랜잭션·요청을 넘어 살아남는다. 읽기 비중이 압도적이고 변경이 드문 엔티티(국가 코드, 카테고리, 권한 정의)에서 DB 왕복을 제거한다. 다만 공유 자원이므로 정합성 관리 책임이 애플리케이션으로 넘어온다는 대가가 따른다.

```java
// 활성화: persistence.xml 또는 Spring 설정
// spring.jpa.properties.hibernate.cache.use_second_level_cache=true
// spring.jpa.properties.hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory

@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "country")
public class Country {
    @Id
    private String code;      // "KR", "US"
    private String name;
    private String currency;
}
```

`shared-cache-mode` 를 `ENABLE_SELECTIVE` 로 두면 `@Cacheable` 이 붙은 엔티티만 캐싱된다. 무분별한 전체 캐싱은 메모리를 잠식하므로 선택적 활성화가 기본값으로 안전하다.

## 2. 2차 캐시는 엔티티를 그대로 담지 않는다

흔한 오해가 "2차 캐시에 엔티티 객체가 들어간다"는 것이다. 실제로는 **분해된 상태(hydrated state)** 를 담는다. 연관 엔티티는 객체 참조가 아니라 식별자(FK)만 저장된다. 그래서 캐시 히트 시에도 하이버네이트는 상태 배열로부터 엔티티를 재조립(assemble)한다.

이 설계의 함의는 두 가지다. 첫째, 연관 엔티티를 함께 캐시에서 꺼내려면 그 연관 엔티티도 각각 캐시 대상이어야 한다. `Order` 만 캐싱하고 `Order.customer` 를 캐싱하지 않으면 customer 는 다시 DB 를 친다. 둘째, 컬렉션은 별도의 **컬렉션 캐시** 로 관리되며 기본적으로 비활성이다.

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Order {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;   // Customer 도 @Cache 여야 캐시에서 함께 해결

    @OneToMany(mappedBy = "order")
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)  // 컬렉션 캐시는 명시 필요
    private List<OrderLine> lines = new ArrayList<>();
}
```

컬렉션 캐시는 자식의 **식별자 목록** 만 담는다. 실제 `OrderLine` 본문은 `OrderLine` 엔티티 캐시에서 각각 조회된다. 따라서 컬렉션 캐시를 켜면서 자식 엔티티 캐시를 끄면, 식별자 N개에 대해 N번의 개별 조회가 발생해 오히려 느려질 수 있다.

## 3. 동시성 전략 4가지의 트레이드오프

`CacheConcurrencyStrategy` 는 캐시가 쓰기와 어떻게 상호작용할지 결정한다. 정합성이 강할수록 처리량은 낮아진다.

| 전략 | 정합성 | 쓰기 허용 | 적합한 데이터 |
|---|---|---|---|
| READ_ONLY | 최상 (변경 불가) | 불가 | 국가·통화 등 불변 참조 |
| NONSTRICT_READ_WRITE | 약함 (stale 가능) | 가능 | 드물게 변경, 짧은 불일치 허용 |
| READ_WRITE | 강함 (soft lock) | 가능 | 자주 읽고 가끔 쓰는 핵심 엔티티 |
| TRANSACTIONAL | 최상 (XA 연동) | 가능 | JTA 트랜잭션 캐시 필요 시 |

`READ_ONLY` 엔티티를 수정하면 `UnsupportedOperationException` 이 발생한다. `NONSTRICT_READ_WRITE` 는 커밋 시점에 캐시 엔트리를 **무효화만** 하고 갱신하지 않는다. 그래서 커밋 직후 다른 트랜잭션이 잠깐 stale 값을 읽을 수 있다. `READ_WRITE` 는 쓰기 시 해당 엔트리에 soft lock 을 걸어 다른 트랜잭션이 캐시 대신 DB 를 읽게 유도하고, 커밋 후 새 값으로 교체한다. 락 관리 오버헤드가 있지만 단일 노드에서 실용적인 강한 정합성을 제공한다.

```java
// READ_WRITE 의 동작을 로그로 관찰
// hibernate.cache.use_structured_entries=true 로 캐시 내용을 사람이 읽게
// 아래 통계로 히트/미스/put/무효화 카운트를 확인
Statistics stats = sessionFactory.getStatistics();
stats.setStatisticsEnabled(true);
SecondLevelCacheStatistics c = stats.getSecondLevelCacheStatistics("country");
System.out.println("hit=" + c.getHitCount() + " miss=" + c.getMissCount()
        + " put=" + c.getPutCount());
```

## 4. 쿼리 캐시는 별개의 세계다

엔티티 캐시가 "ID 로 엔티티를 찾는" 캐시라면, 쿼리 캐시는 "이 JPQL + 파라미터 조합이 반환한 **식별자 목록**"을 캐싱한다. 결과 엔티티 본문은 여전히 엔티티 캐시(또는 DB)에서 해결된다. 그래서 쿼리 캐시만 켜고 대상 엔티티의 2차 캐시를 끄면, 식별자 목록은 캐시되지만 본문 로딩에서 N번 DB 를 쳐서 이득이 거의 없다.

```java
// 활성화: hibernate.cache.use_query_cache=true
List<Country> result = em.createQuery(
        "select c from Country c where c.currency = :cur", Country.class)
    .setParameter("cur", "KRW")
    .setHint("org.hibernate.cacheable", true)   // 이 쿼리를 캐시 대상으로
    .setHint("org.hibernate.cacheRegion", "query.country")
    .getResultList();
```

쿼리 캐시의 무효화는 **테이블 단위 타임스탬프** 로 이뤄진다. 하이버네이트는 `UpdateTimestampsCache` 에 각 테이블의 마지막 변경 시각을 기록한다. 쿼리 캐시 엔트리에는 저장 시각이 함께 들어가고, 조회 시 해당 쿼리가 건드리는 테이블들의 타임스탬프와 비교해 하나라도 더 최신이면 캐시를 버린다. 결과적으로 대상 테이블에 INSERT/UPDATE/DELETE 가 한 번이라도 발생하면 그 테이블을 참조하는 **모든** 쿼리 캐시가 무효화된다. 쓰기가 잦은 테이블에 쿼리 캐시를 걸면 히트율이 바닥을 친다.

## 5. 네이티브 쿼리와 벌크 연산의 함정

`@Modifying` 벌크 업데이트나 네이티브 쿼리는 영속성 컨텍스트를 우회한다. 하이버네이트는 네이티브 쿼리가 어떤 테이블을 건드리는지 정확히 모르기 때문에, 기본적으로 **모든 쿼리 캐시 영역을 무효화** 하려 시도하거나, 반대로 무효화를 놓칠 수 있다. 네이티브 쿼리에는 영향받는 공간을 명시해야 한다.

```java
Query q = em.createNativeQuery("UPDATE country SET name = :n WHERE code = :c");
q.setParameter("n", "대한민국");
q.setParameter("c", "KR");
org.hibernate.query.NativeQuery<?> hq = q.unwrap(org.hibernate.query.NativeQuery.class);
hq.addSynchronizedEntityClass(Country.class);  // 이 엔티티/쿼리 캐시를 무효화 대상으로 등록
q.executeUpdate();
```

벌크 JPQL(`update Country c set c.name = ...`)은 하이버네이트가 대상 엔티티를 알기 때문에 해당 엔티티의 2차 캐시와 관련 쿼리 캐시를 자동 무효화한다. 그러나 1차 캐시(현재 영속성 컨텍스트)의 이미 로드된 인스턴스는 갱신하지 않으므로, 벌크 연산 후 `em.clear()` 로 컨텍스트를 비워 stale 인스턴스를 제거하는 것이 안전하다.

## 6. 분산 환경 — 캐시가 노드마다 따로 논다

가장 위험한 지점이다. 애플리케이션을 2대 이상으로 스케일 아웃하면 각 JVM 의 2차 캐시가 독립적이다. 노드 A 에서 `Country` 를 수정하면 A 의 캐시는 무효화되지만 B 의 캐시는 여전히 옛 값을 들고 있다. `READ_WRITE` 조차 단일 노드 정합성만 보장하므로 다중 노드 stale read 를 막지 못한다.

해결은 크게 두 방향이다. 첫째, 캐시 제공자를 **분산 캐시**(Hazelcast, Infinispan)로 두어 무효화 이벤트를 클러스터에 전파한다. 둘째, Redis 같은 외부 공유 캐시를 2차 캐시 백엔드로 붙여 모든 노드가 같은 저장소를 본다. JCache(JSR-107) 표준 위에서 region factory 만 교체하면 코드 변경 없이 전환된다.

```yaml
# Infinispan 분산 모드 예시 — 무효화(invalidation) 클러스터
# 노드에서 put/remove 가 일어나면 다른 노드에 무효화 메시지 브로드캐스트
infinispan:
  cacheContainer:
    transport: { cluster: "jpa-cache" }
    invalidationCache:
      name: "country"
      mode: SYNC          # 동기 무효화 — 정합성 우선
      # ASYNC 로 두면 처리량은 오르나 짧은 stale 창이 생김
```

동기(SYNC) 무효화는 쓰기 지연을 늘리는 대신 stale 창을 최소화한다. 실무에서는 "TTL 을 짧게(예: 30~60초) 두고 무효화는 best-effort" 조합이 흔하다. TTL 이 최종 방어선이 되어, 무효화 메시지가 유실돼도 최대 TTL 만큼 뒤엔 정합해진다.

## 7. 언제 2차 캐시를 쓰지 말아야 하는가

2차 캐시는 만능이 아니다. 쓰기가 읽기와 비슷하거나 많은 테이블은 무효화 폭풍으로 순손해가 난다. 강한 정합성이 필수인 잔액·재고 같은 데이터는 stale 위험 때문에 부적합하다. 또한 캐시 통계 없이 "일단 켜두는" 운영은 메모리 누수와 GC 압박을 부른다. `hit/miss/put` 카운트를 모니터링해 히트율이 낮은 영역은 과감히 꺼야 한다.

실무 판단 기준은 명확하다. 읽기:쓰기 비율이 최소 10:1 이상이고, 짧은 불일치를 허용할 수 있으며, 엔티티 수가 메모리에 감당 가능한 규모일 때만 켠다. 그 외에는 애플리케이션 레벨 캐시(`@Cacheable` + Redis, 명시적 키·TTL 관리)가 오히려 제어하기 쉽다. 2차 캐시의 매력은 "투명성"이지만, 그 투명성이 곧 무효화 시점을 놓치게 만드는 위험이기도 하다.

## 8. Spring @Cacheable 과 2차 캐시의 계층 구분

혼동하기 쉬운 지점이 Spring 의 `@Cacheable` 과 JPA 2차 캐시가 **서로 다른 계층**이라는 것이다. `@Cacheable` 은 메서드 반환값을 캐싱하는 AOP 프록시 기반이라, 캐시 히트 시 메서드 자체가 실행되지 않는다. 반면 2차 캐시는 하이버네이트 내부에서 엔티티 로딩을 가로챈다. 둘을 겹쳐 쓰면 무효화 지점이 이원화되어 정합성 추적이 어려워진다.

```java
@Service
public class CountryService {
    @Cacheable(value = "countryByCurrency", key = "#currency")
    public List<Country> findByCurrency(String currency) {
        // 이 메서드가 통째로 캐시됨 — JPA 2차 캐시보다 상위 계층
        return repository.findByCurrency(currency);
    }

    @CacheEvict(value = "countryByCurrency", key = "#country.currency")
    public void update(Country country) {  // 변경 시 명시적으로 무효화
        repository.save(country);
    }
}
```

실무 권장은 계층을 하나로 정하는 것이다. 명시적 제어와 TTL·키 설계가 필요한 조회는 `@Cacheable`(+ Redis)로 상위에서 다루고, 엔티티 로딩 자체의 반복 왕복 제거가 목적이면 2차 캐시를 쓰되 둘을 같은 데이터에 중첩하지 않는다. `@CacheEvict` 의 누락이 stale 의 주원인이므로, 쓰기 경로마다 무효화 대상 키를 코드 리뷰에서 반드시 확인한다.

## 9. 캐시 스탬피드 방지

인기 있는 캐시 키의 엔트리가 만료되는 순간, 동시에 수백 요청이 캐시 미스를 겪고 한꺼번에 DB 로 몰리는 현상이 캐시 스탬피드(thundering herd)다. 방어는 세 가지 축이다. 첫째, 만료 시각에 지터를 더해 동시 만료를 분산한다. 둘째, 미스 시 하나의 요청만 DB 를 치고 나머지는 대기하도록 뮤텍스(single-flight)를 건다. 셋째, 만료 직전 백그라운드로 미리 갱신하는 refresh-ahead 를 쓴다.

```java
// single-flight 근사 — 키별 락으로 한 요청만 재계산
public List<Country> findByCurrency(String cur) {
    List<Country> hit = cache.get(cur);
    if (hit != null) return hit;
    synchronized (lockFor(cur)) {              // 같은 키에 대해 직렬화
        List<Country> again = cache.get(cur);  // 락 진입 후 재확인 (double-check)
        if (again != null) return again;
        List<Country> loaded = repository.findByCurrency(cur);
        cache.put(cur, loaded);
        return loaded;
    }
}
```

분산 환경에서는 JVM 락 대신 Redis 분산 락으로 single-flight 를 구현한다. 스탬피드는 캐시 히트율이 높을수록 오히려 파괴적이므로(만료 순간의 부하 집중이 커짐), 히트율만 보고 안심하면 안 되고 만료 정책과 재계산 동시성 제어까지 함께 설계해야 한다.

## 참고

- Hibernate ORM User Guide — Caching (https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching)
- Jakarta Persistence Specification — Second-level Cache, `shared-cache-mode`
- Infinispan Documentation — Second Level Cache for Hibernate
- Vlad Mihalcea, *High-Performance Java Persistence* — 2nd level & query cache chapters
