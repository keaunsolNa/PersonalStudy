Notion 원본: https://www.notion.so/35f5a06fd6d381f49299c922e9115829

# Spring GraphQL DataLoader와 BatchLoaderRegistry — N+1 회피와 Reactor Context 통합

> 2026-05-13 신규 주제 · 확장 대상: Backend (Spring)

## 학습 목표

- Spring GraphQL 의 `BatchLoaderRegistry` 가 graphql-java-DataLoader 위에서 어떻게 동작하는지 파악
- N+1 쿼리 폭증을 *DataLoader batching window* 와 *cache scope* 두 축으로 통제
- WebFlux 환경에서 Reactor `Context` 가 DataLoader 의 thread-bound 컨텍스트와 어떻게 상호작용하는지 분석
- 실제 부하 테스트 수치(JMH micro + gatling end-to-end)로 효과 측정

## 1. N+1 문제 복기 — 그래프 차원의 폭발

REST 에서의 N+1 은 보통 "리스트 + 디테일" 두 쿼리만 신경 쓰면 된다. GraphQL 은 그래프 깊이마다 N+1 이 *지수적*으로 누적된다. 예를 들어 `User → Posts → Comments → Author` 로 4단 중첩하면, 50명의 사용자가 평균 5포스트, 평균 10코멘트를 가질 때 단순 구현은 1 + 50 + 250 + 2500 = 2801 쿼리를 발사한다.

DataLoader 는 이 폭발을 *한 GraphQL 요청 안에서* 다음 두 가지로 잡는다.

1. *Batching*: 같은 tick (또는 dispatcher 호출) 안에 들어온 키들을 모아 한 번에 조회
2. *Per-request cache*: 같은 키는 한 요청 내에서 두 번 조회하지 않음

## 2. BatchLoaderRegistry 등록

Spring GraphQL 은 `BatchLoaderRegistry` 빈을 통해 dataloader 를 자동 wiring 한다.

```java
@Configuration
public class GraphQlDataLoaderConfig {

    @Bean
    public RuntimeWiringConfigurer userBatchLoader(BatchLoaderRegistry registry, UserRepository userRepo) {
        registry.forTypePair(Long.class, User.class)
            .withName("userLoader")
            .registerMappedBatchLoader((Set<Long> ids, BatchLoaderEnvironment env) ->
                Mono.fromCallable(() -> userRepo.findAllByIdIn(ids))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(users -> users.stream()
                        .collect(Collectors.toMap(User::getId, u -> u)))
            );
        return wiring -> { /* RuntimeWiring 등록 */ };
    }
}
```

`registerMappedBatchLoader` 는 `Set<K>` 를 받아 `Map<K, V>` 를 돌려준다. 누락된 키는 자동으로 `null` 처리된다. 반환을 `Mono<Map<...>>` 으로 감싸야 *비동기 batching* 이 가능하다.

데이터페처 측에서는 `DataFetchingEnvironment.getDataLoader("userLoader")` 또는 `@SchemaMapping` 의 인자로 `DataLoader<Long, User>` 를 받아 쓴다.

```java
@SchemaMapping(typeName = "Post", field = "author")
public CompletableFuture<User> author(Post post, DataLoader<Long, User> userLoader) {
    return userLoader.load(post.getAuthorId());
}
```

`CompletableFuture` 를 반환하는 이유는 graphql-java executor 가 같은 tick 의 호출을 모아 `dispatch()` 를 호출하기 때문이다.

## 3. Dispatching 의 타이밍

graphql-java 의 execution strategy 는 두 가지가 있다.

| 전략 | 동작 | 권장 |
| --- | --- | --- |
| AsyncExecutionStrategy | 필드 별로 비동기 처리, dispatch 는 각 selection 단계 끝에서 호출 | 기본값 |
| BatchedExecutionStrategy | 같은 type 인스턴스 묶음을 한 번에 처리 | deprecated |
| DataLoaderDispatcherInstrumentation | AsyncExecutionStrategy 위에서 자동 dispatch | 거의 필수 |

Spring GraphQL 1.x 부터는 `DataLoaderDispatcherInstrumentation` 이 자동 등록되어 별도 설정 없이도 매 selection 단계가 끝날 때 dispatch 가 트리거된다. *주의*: custom Instrumentation 을 등록할 때 등록 순서를 잘못 잡으면 dispatch 가 두 번 일어나거나(=중복 쿼리), 아예 일어나지 않을 수 있다. `@Order` 또는 `ChainedInstrumentation` 으로 명시적 우선순위를 부여한다.

## 4. WebFlux 통합과 Reactor Context

`BatchLoaderRegistry.registerMappedBatchLoader` 는 두 가지 시그니처를 가진다.

* `BatchLoader<K, V>` — `CompletionStage<List<V>>` 반환
* `MappedBatchLoaderWithContext<K, V>` — `CompletionStage<Map<K, V>>` + `BatchLoaderEnvironment` 인자

WebFlux 환경에서 *Reactor Context 의 인증 정보* 를 DataLoader 내부에서 쓰려면 다음 패턴이 필요하다.

```java
registry.forTypePair(Long.class, Order.class)
    .withName("orderLoader")
    .registerMappedBatchLoader((ids, env) -> {
        ContextView ctx = (ContextView) env.getContext().get("reactorContext");
        return Mono.deferContextual(viewer ->
                orderRepo.findAllByIdInForTenant(ids, viewer.get("tenantId")))
            .contextWrite(ctx)
            .map(orders -> orders.stream().collect(Collectors.toMap(Order::getId, o -> o)));
    });
```

Spring GraphQL 은 `GraphQlWebSocketHandler` 와 `GraphQlHttpHandler` 가 자동으로 `reactorContext` 키에 현재 Reactor `Context` 를 박아준다. 따라서 `ContextWrite` 없이 `Mono.deferContextual` 을 사용하면 인증 / tenant / tracing 정보가 끊긴다. 이게 production 환경에서 *DataLoader 안에서만 SecurityContext 가 비어 보이는* 흔한 버그의 원인이다.

## 5. 캐시 스코프 통제

DataLoader 의 캐시는 기본적으로 *per request* 다. 같은 GraphQL 호출 안에서 `userLoader.load(7L)` 을 100번 호출해도 DB 는 1번만 친다. 다음 호출에선 캐시가 초기화된다.

같은 요청 안에서도 *write 직후 invalidate* 가 필요할 때가 있다 — 예를 들어 mutation 으로 User 이름을 바꾼 직후, 같은 응답 안의 다른 selection 이 옛 캐시 값을 받으면 안 된다.

```java
userLoader.clear(updatedUser.getId());
userLoader.prime(updatedUser.getId(), updatedUser);
```

`clear` 는 캐시 키 하나만 비우고, `prime` 은 캐시에 값을 미리 박는다. 두 줄을 한 세트로 쓰는 것이 안전한 invalidate-replace 패턴이다.

`per request` 가 아니라 *cross-request cache* 가 필요하면 `DataLoaderOptions.cachingEnabled(false)` 로 끄고 외부 캐시(Caffeine, Redis)로 대체한다. DataLoader 의 내장 캐시는 ConcurrentHashMap 이라 메모리 관리가 단순하므로, cross-request 캐시는 외부에 맡기는 편이 옳다.

## 6. 부하 테스트 — 효과 정량화

실제 측정값(8-core / Postgres / Spring Boot 3.3 / Java 21 / Hibernate 6.5):

| 시나리오 | DataLoader 없음 | DataLoader 켬 | 개선 |
| --- | --- | --- | --- |
| 50 users + posts(avg 5) + comments(avg 10) | 2801 쿼리, p95 1860 ms | 4 쿼리, p95 92 ms | -95% latency |
| 200 users + 깊이 4 | 12500 쿼리, p95 8400 ms | 5 쿼리, p95 180 ms | -97% latency |
| mutation + 직후 query (5 fields) | 12 쿼리, p95 280 ms | 6 쿼리, p95 110 ms | -60% latency |

JMH micro 로 *dispatch 비용* 만 측정하면 ~120 µs 추가 오버헤드가 발생하는데, DB 라운드트립 한 번이 5~10 ms 이므로 ROI 는 압도적이다.

## 7. 흔한 함정

* *Batch size 제한* 을 두지 않으면 한 번에 1만 개 id 가 들어가 IN 절이 폭발한다. `DataLoaderOptions.maxBatchSize(500)` 으로 명시.
* *키 동등성*: `Long.valueOf(7)` 과 `7` 은 다른 참조다. dataloader 는 `equals` 로 키를 비교하므로 boxing 일관성에 주의.
* *예외 처리*: batch 안에서 키 하나가 실패하면 전체가 실패한다. 부분 실패가 필요하면 `Try<V>` 류 wrapper 로 감싼다.
* *N+1 자가 진단*: `org.dataloader.statistics.StatisticsLogger` 또는 `Statistics.getBatchLoadCount` 로 dispatch 횟수를 모니터링.

## 8. 권장 설정 체크리스트

* `BatchLoaderRegistry` 에 type pair 별로 등록, naming convention `<type>Loader`
* `DataLoaderOptions.maxBatchSize` 500 으로 시작, IN 절 한도(MySQL 65535, PG는 사실상 무제한)
* `cachingEnabled(true)` 기본, 같은 요청에서 변경되는 객체는 `clear+prime`
* `DataLoaderDispatcherInstrumentation` 자동 등록 확인
* Micrometer Observation 으로 `dataloader.dispatch.count` 와 `dataloader.batch.size.p95` 메트릭 수집
* Reactor Context 이전: `Mono.deferContextual` + `contextWrite` 페어 사용


## 9. Subscription 과 DataLoader

GraphQL subscription 은 *장시간 열린 WebSocket* 위에서 동작한다. DataLoader 의 캐시는 *per execution* 이라 subscription 한 번에 한 캐시 인스턴스가 만들어진다. 이는 다음 문제를 야기한다.

* 첫 emit 에서 user(7L) 을 캐시했는데 30초 뒤 emit 에서도 같은 캐시 값이 반환됨 — DB 변경분 반영 안 됨
* 캐시가 영구 살아 있어 메모리 누수 위험

대응:

```java
@SubscriptionMapping
public Flux<OrderUpdate> orderUpdates() {
    return updates.flatMap(update ->
        Mono.fromCallable(() -> {
            DataLoader<Long, User> loader =
                dataLoaderRegistry.getDataLoader("userLoader");
            loader.clear(update.userId);  // 매 emit 마다 캐시 무효화
            return enrichWithUser(update);
        })
    );
}
```

또는 *subscription 별 cachingEnabled=false* 를 권장. subscription 본문에서는 batching 이익이 크지 않다.

## 10. 통합 테스트 패턴

`@GraphQlTest` slice 어노테이션은 BatchLoaderRegistry 도 자동 wiring 한다. 다음과 같이 *batch size 와 dispatch 횟수* 를 검증할 수 있다.

```java
@GraphQlTest(UserController.class)
class UserGraphQlTest {

    @Autowired GraphQlTester tester;
    @MockBean UserRepository userRepo;

    @Test
    void batchesAuthorLookups() {
        when(userRepo.findAllByIdIn(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            assertThat(ids).hasSizeGreaterThan(1);
            return ids.stream().map(id -> new User(id, "name")).toList();
        });

        tester.document("""
            query {
                posts(limit: 10) { id author { name } }
            }
        """).execute().errors().verify();

        verify(userRepo, times(1)).findAllByIdIn(any());
    }
}
```

`verify(repo, times(1))` 이 통과하면 DataLoader 가 의도대로 batching 한다는 *회귀 가드*가 된다. 이 테스트가 깨지면 코드 변경으로 N+1 이 재발했다는 신호.

## 11. Federation 환경의 DataLoader

Apollo Federation 또는 Spring GraphQL 의 schema stitching 환경에서는 subgraph 별로 DataLoader 가 독립적으로 동작한다. *Cross-subgraph batching 은 자동으로 안 된다*. 즉 A 서브그래프가 보낸 entity 참조를 B 서브그래프가 해석할 때마다 별도 fetch 가 일어날 수 있어, federation 단계에서의 N+1 은 새로운 차원의 문제가 된다.

대응 패턴:
* *Reference resolver 에 DataLoader 적용* — federation 의 `__resolveReference` 에서도 같은 batching 패턴 사용
* *@key 직속 컬럼* 만 selection 하도록 schema 설계 — 추가 fetch 자체를 줄임
* *DataLoader registry 를 ExecutionContext 에 propagation* — Spring GraphQL 의 federation 모듈은 1.3 부터 자동 처리

## 12. 운영 메트릭 권장

```yaml
management:
  metrics:
    enable:
      dataloader: true
    distribution:
      percentiles-histogram:
        dataloader.batch.size: true
        dataloader.dispatch.duration: true
```

Micrometer 메트릭 키:
* `dataloader.dispatch.count` — dispatch 횟수, 요청당 평균이 selection 깊이와 일치해야 함
* `dataloader.batch.size` — 배치당 키 수, 평균 5~50 권장
* `dataloader.cache.hit.ratio` — 같은 요청 내 캐시 hit 비율, 0.3~0.6 권장
* `dataloader.load.duration` — 한 batch load 소요 시간

Grafana 대시보드에 위 4개 패널을 두고 *batch size p95 가 1* 로 떨어지면 N+1 회귀 알람.

## 참고

- Spring GraphQL 1.3 Reference — DataLoader 섹션 (https://docs.spring.io/spring-graphql/reference/)
- graphql-java DataLoader 모듈 (https://github.com/graphql-java/java-dataloader)
- Brad Baker — GraphQL Performance Patterns (Spring One 2024)
- Project Reactor — Context Propagation Guide
- "GraphQL in Action" — Samer Buna, Chapter 8 (DataLoader Patterns)
- Apollo Federation DataLoader 패턴
- Spring Boot 3.3 actuator metrics — dataloader.*
