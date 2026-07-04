Notion 원본: https://app.notion.com/p/3935a06fd6d381b9a818f410c8c13433

# Spring Data JPA N+1과 Fetch Join 및 EntityGraph 배치사이즈

> 2026-07-05 신규 주제 · 확장 대상: ORM

## 학습 목표

- 지연 로딩이 N+1 쿼리를 유발하는 구조적 원인을 설명한다
- Fetch Join, @EntityGraph, batch size 각 해법의 적용 조건과 한계를 구분한다
- 컬렉션 Fetch Join 의 페이징 불가·카테시안 곱 문제를 파악한다
- DTO 프로젝션으로 조회 전용 경로를 분리하는 판단 기준을 세운다

## 1. N+1 은 왜 생기는가

지연 로딩 프록시는 접근 시점에 SELECT 를 날린다. 팀 10개를 조회 후 각 팀 members 를 순회하면 1+10=11 쿼리가 나간다. 이는 실수가 아니라 지연 로딩의 정상 동작이며, 문제는 그래프 전체가 필요함을 미리 알리지 않은 데 있다.

```java
List<Team> teams = teamRepository.findAll();   // (1)
for (Team team : teams) {
    team.getMembers().size();   // 팀마다 SELECT (N)
}
```

## 2. Fetch Join

`join fetch` 는 연관을 같은 SELECT 로 즉시 로딩한다.

```java
@Query("select distinct t from Team t join fetch t.members")
List<Team> findAllWithMembers();
```

일대다 조인은 멤버 수만큼 팀 행이 중복되므로 distinct 로 엔티티 중복을 제거한다(Hibernate 6 는 기본 제거).

## 3. 컬렉션 Fetch Join 의 한계 — 페이징 불가

일대다 Fetch Join 에는 페이징을 못 건다. 조인으로 뻥튀기된 행에 LIMIT 을 걸면 팀 단위가 아니라 조인 행 단위로 잘린다. Hibernate 는 이 경우 전 데이터를 메모리로 읽어 페이징(HHH000104)하므로 OOM 위험이 있다. 컬렉션 두 개 동시 Fetch Join 은 카테시안 곱 폭발로 MultipleBagFetchException 이 난다.

| 상황 | Fetch Join | 대안 |
|---|---|---|
| ToOne | 가능, 페이징 OK | 그대로 |
| ToMany 1개+페이징 없음 | 가능 | distinct |
| ToMany 1개+페이징 필요 | 위험(메모리) | batch size |
| ToMany 2개+ | 불가(예외) | batch size |

## 4. batch size — ToMany 페이징 정석

ToOne 만 Fetch Join 하고 ToMany 는 지연 로딩하되 `default_batch_fetch_size` 로 IN 조회로 묶는다. N+1 이 N/batch+1 로 준다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

페이징·카테시안 곱과 무관하며 실무 기본 해법이다. Oracle IN 상한 1000 이하로 맞춘다.

## 5. @EntityGraph

JPQL 없이 페치할 연관을 선언한다. 내부적으로 LEFT OUTER JOIN 즉시 로딩이며 파생 쿼리 메서드에도 붙는다. 다만 컬렉션 EntityGraph 도 조인이라 페이징 한계는 동일하다.

```java
@EntityGraph(attributePaths = {"members", "leader"})
List<Team> findByNameContaining(String keyword);
```

## 6. DTO 프로젝션

조회 전용은 엔티티 대신 필요한 컬럼만 DTO 로 직접 조회한다.

```java
public record TeamSummary(Long teamId, String teamName, long memberCount) {}

@Query("select new com.example.TeamSummary(t.id, t.name, count(m)) from Team t left join t.members m group by t.id, t.name")
List<TeamSummary> findTeamSummaries();
```

## 6.5. 읽기 전용 최적화와 지연 로딩 예외

`@Transactional(readOnly = true)` 는 스냅샷을 안 만들어 더티 체킹·flush 비용을 아낀다. 트랜잭션 밖 지연 로딩 접근은 LazyInitializationException 을 낸다. 서비스 계층에서 그래프를 미리 로딩하거나 DTO 로 변환해 반환하고, `open-in-view=false` 로 커넥션 풀 고갈을 막는다.

## 7. 진단과 선택 기준

`hibernate.generate_statistics=true` 로 실제 쿼리 수를 세어 회귀를 막는다.

```java
assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
```

| 해법 | 페이징 | 다중 컬렉션 |
|---|---|---|
| Fetch Join | ToOne만 | 불가 |
| @EntityGraph | ToOne만 | 제한적 |
| batch size | 가능 | 가능 |
| DTO 프로젝션 | 가능 | 무관 |

## 참고

- Hibernate ORM User Guide — Fetching, Batch fetching
- Spring Data JPA Reference — EntityGraph
- Vlad Mihalcea, "The best way to fix Hibernate N+1 query problem"
- 김영한, "실전! 스프링 부트와 JPA 활용"
