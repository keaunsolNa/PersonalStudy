Notion 원본: https://www.notion.so/3555a06fd6d381feabf8e0e4e027b4ec

# Spring Reactive Transactional과 R2DBC Connection Pool — 비동기 트랜잭션 경계 설계

> 2026-05-03 신규 주제 · 확장 대상: Spring WebFlux Reactor Backpressure, Virtual Threads Spring Boot 3.2

## 학습 목표

- 블로킹 JDBC 와 R2DBC 의 트랜잭션 모델 차이를 ConnectionFactory 레벨에서 비교한다
- `@Transactional` 이 Reactor 컨텍스트에서 어떻게 전파되는지 ReactiveTransactionManager 를 통해 추적한다
- R2DBC Pool 의 핵심 옵션 (`maxSize`, `maxIdleTime`, `maxAcquireTime`, `maxLifeTime`, `validationQuery`) 의 의미와 기본값을 정리하고 실측 부하에 맞춰 결정한다
- 트랜잭션 격리 수준과 `READ ONLY` 힌트가 R2DBC에서 어떻게 적용되는지, 그리고 잘못 쓰면 발생하는 connection 누수를 진단한다

## 1. JDBC 와 R2DBC 의 트랜잭션 모델 차이

JDBC 는 `Connection` 이 ThreadLocal 에 묶여 있고 `Connection.setAutoCommit(false)` 로 명시적 트랜잭션을 시작한다. Spring 의 `DataSourceTransactionManager` 는 `TransactionSynchronizationManager.bindResource(dataSource, connectionHolder)` 를 통해 동일 스레드 내에서 같은 connection 을 재사용한다. 이 모델의 핵심 가정은 "스레드가 트랜잭션의 경계" 라는 점이다.

R2DBC 는 비동기 모델이라 스레드가 자유롭게 옮겨다닌다. 트랜잭션 컨텍스트는 ThreadLocal 이 아닌 **Reactor Context** (`reactor.util.context.Context`) 에 저장된다. Spring 은 `R2dbcTransactionManager` 와 `TransactionalOperator` 를 제공하며, 내부적으로 `Connection` 을 `Mono<Connection>` 로 감싸 컨텍스트를 통해 전파한다.

```java
@Configuration
@EnableTransactionManagement
public class R2dbcConfig {
    @Bean
    public ConnectionFactory connectionFactory() {
        return ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(DRIVER, "pool")
                .option(PROTOCOL, "postgresql")
                .option(HOST, "db.example.com")
                .option(PORT, 5432)
                .option(USER, "app")
                .option(PASSWORD, System.getenv("DB_PASSWORD"))
                .option(DATABASE, "app")
                .option(ConnectionFactoryOptions.LOCK_WAIT_TIMEOUT, Duration.ofSeconds(3))
                .build()
        );
    }

    @Bean
    public ReactiveTransactionManager transactionManager(ConnectionFactory cf) {
        return new R2dbcTransactionManager(cf);
    }
}
```

`ConnectionFactories.get` 의 driver `"pool"` 은 ConnectionPool 래퍼를 명시한 형태로, 실제 backing driver 는 `PROTOCOL` 로 지정한다.

## 2. @Transactional 의 전파 메커니즘

`@Transactional` 어노테이션을 단 메서드의 반환 타입이 `Mono` 또는 `Flux` 일 때 Spring 은 `ReactiveTransactionAspectSupport` 를 거치며, 핵심 로직은 다음과 같다.

```java
public Mono<Order> createOrder(OrderRequest req) {
    return orderRepository.save(toEntity(req))
        .flatMap(o -> stockRepository.decrease(req.itemId(), req.qty())
                       .thenReturn(o));
}
```

위 메서드에 `@Transactional` 을 달면 다음 처리가 일어난다.

1. 메서드 호출 시 `TransactionalOperator.execute(...)` 가 적용
2. 새로운 `R2dbcTransactionManager` 트랜잭션이 `Mono.deferContextual` 로 시작되어 `Connection` 을 ConnectionPool 에서 획득
3. `BEGIN` 발행 후 Reactor Context 에 `ConnectionHolder` 를 push
4. 내부 `flatMap` 체인이 동일 connection 을 사용해 SQL 발행
5. 체인이 정상 완료되면 `COMMIT`, 에러나 cancel 이면 `ROLLBACK` 후 connection 반환

**주의**: `Mono.fromCallable(() -> blockingJdbcCall())` 처럼 블로킹 호출을 섞으면 Reactor Context 가 단절되어 트랜잭션이 전파되지 않는다. R2DBC 트랜잭션은 R2DBC 호출에만 적용된다.

## 3. ConnectionPool 옵션과 기본값

`io.r2dbc:r2dbc-pool` 의 주요 옵션. 기본값은 라이브러리 1.0 기준이다.

| 옵션 | 기본값 | 의미 | 권장 가이드 |
|---|---|---|---|
| `initialSize` | 10 | 시작 시 미리 채워둘 connection 수 | maxSize 의 25~50% |
| `maxSize` | 10 | 최대 connection 수 | DB 서버의 max_connections / 인스턴스 수 |
| `acquireRetry` | 1 | 획득 실패 시 재시도 횟수 | 1 그대로 |
| `maxAcquireTime` | -1 (무한) | acquire 대기 최대 시간 | 3~5초 권장 — 무한 대기는 장애 시 자살행위 |
| `maxIdleTime` | 30분 | idle connection 회수 시점 | 10~30분 |
| `maxCreateConnectionTime` | -1 | create 대기 최대 시간 | 5~10초 |
| `maxLifeTime` | -1 | connection 의 절대 수명 | 30분 — DB cluster failover 대비 |
| `validationQuery` | (없음) | 획득 시 검증 쿼리 | `SELECT 1` 권장 |
| `validationDepth` | LOCAL | 검증 깊이 (LOCAL/REMOTE) | 부하 환경은 REMOTE |

`maxAcquireTime` 을 무한으로 두면, DB 가 포화 상태일 때 모든 요청이 무한 대기에 빠지고 그 결과 Reactor 의 보유 메모리가 폭발한다. **반드시 유한 값으로 설정**한다.

## 4. PostgreSQL 기준 maxSize 계산식

Tomas Vondra 의 PostgreSQL 튜닝 가이드는 동시 활성 connection 수 기준 다음 식을 권장한다.

```
max_connections ≈ ((CPU 코어 수 × 2) + 디스크 스핀들 수) × 인스턴스 수
```

서버 측 `max_connections=200`, 동일 DB 를 4개의 애플리케이션 인스턴스가 쓴다고 가정하면 인스턴스당 50 이 한도다. 여기에 admin 세션과 PgBouncer 풀을 위한 여유 20% 를 빼면 인스턴스당 `maxSize` 는 약 40 이 합리적이다. 더 늘려도 디스크/락 경합으로 처리량이 오히려 떨어진다.

R2DBC 도 본질적으로 하나의 connection 위에서 한 번에 한 트랜잭션만 돌리므로 connection 수가 곧 동시 트랜잭션 수의 상한이다. 비동기라고 무한히 늘릴 수 있는 것이 아니다.

## 5. TransactionalOperator 직접 사용

`@Transactional` 은 AOP 프록시에 의존하므로 함수형 라우팅이나 빌더 체인 안에서는 동작하지 않는다. 그럴 때는 `TransactionalOperator` 를 명시적으로 쓴다.

```java
@Component
@RequiredArgsConstructor
public class OrderHandler {
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final TransactionalOperator txOperator;

    public Mono<Order> placeOrder(OrderRequest req) {
        return orderRepository.save(toEntity(req))
            .flatMap(o -> stockRepository.decrease(req.itemId(), req.qty())
                           .thenReturn(o))
            .as(txOperator::transactional);
    }
}
```

`as(txOperator::transactional)` 한 줄로 reactive 트랜잭션 경계를 묶는다. 격리 수준이나 readOnly 힌트가 필요하면 `TransactionalOperator.create(txManager, definition)` 으로 정의를 직접 만든다.

```java
TransactionDefinition def = new TransactionDefinitionBuilder()
    .name("placeOrder")
    .isolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ)
    .timeout(5)
    .build();
TransactionalOperator op = TransactionalOperator.create(txManager, def);
```

## 6. readOnly 힌트와 라우팅

`@Transactional(readOnly = true)` 는 R2DBC 트랜잭션 매니저에서 `Connection.setAutoCommit(false)` 후 `Connection.setTransactionIsolationLevel(...)` 와 함께 `BEGIN READ ONLY` SQL 을 발행하는 효과를 낸다. PostgreSQL 의 경우 `SET TRANSACTION READ ONLY` 가 같이 발행되어 옵티마이저가 read-only 가정을 사용한다.

또한 `AbstractRoutingConnectionFactory` 와 결합하면 readOnly 트랜잭션을 readonly replica 로 라우팅할 수 있다.

```java
public class ReplicaRoutingConnectionFactory extends AbstractRoutingConnectionFactory {
    @Override
    protected Mono<Object> determineCurrentLookupKey() {
        return Mono.deferContextual(ctx -> Mono.just(
            ctx.getOrEmpty(TransactionDefinition.class)
               .map(d -> d.isReadOnly() ? "replica" : "primary")
               .orElse("primary")
        ));
    }
}
```

핵심: 라우팅 결정은 *트랜잭션 시작 시점에* 일어난다. 이미 시작된 트랜잭션 내부에서 readOnly 를 바꿔도 connection 은 이미 잡혀 있으므로 라우팅이 바뀌지 않는다.

## 7. 격리 수준과 R2DBC 드라이버 차이

| DB | R2DBC 드라이버 | 기본 격리 수준 | 특이점 |
|---|---|---|---|
| PostgreSQL | `r2dbc-postgresql` | READ COMMITTED | SET TRANSACTION 으로 변경 가능 |
| MySQL | `r2dbc-mysql` (asyncer) | REPEATABLE READ | gap lock 동작 동일 |
| MSSQL | `r2dbc-mssql` | READ COMMITTED | snapshot isolation 별도 옵션 |
| H2 | `r2dbc-h2` | READ COMMITTED | 테스트 전용 |
| Oracle | `oracle.r2dbc:oracle-r2dbc` | READ COMMITTED | SERIALIZABLE 만 추가 지원 |

격리 수준 변경은 트랜잭션 단위로만 가능하고, connection 단위 default 변경은 권장되지 않는다 (다른 트랜잭션과 충돌). `TransactionDefinition.ISOLATION_DEFAULT` 를 그대로 두고 `@Transactional(isolation = ...)` 로 트랜잭션 진입 시 설정하는 것이 안전하다.

## 8. 누수 디버깅과 메트릭

R2DBC Pool 은 Micrometer 메트릭을 그대로 발행한다. 핵심 메트릭:

| 메트릭 | 의미 | 임계 |
|---|---|---|
| `r2dbc.pool.acquired` | 현재 사용 중인 connection 수 | maxSize 의 80% 이상 지속 시 위험 |
| `r2dbc.pool.idle` | 유휴 connection 수 | 항상 0 이면 풀 부족 |
| `r2dbc.pool.pending` | 대기 중 요청 수 | > 0 지속 = 부하 초과 |
| `r2dbc.pool.allocated` | 풀 내 총 connection 수 | maxSize 와 일치해야 정상 |
| `r2dbc.pool.maxAllocatedSize` | 피크 시 사용량 | maxSize 결정 근거 |

가장 흔한 누수 패턴은 다음 두 가지다.

```java
// 1) Mono 를 구독하지 않은 경우 — connection 은 lazy 라 누수 자체는 없으나 트랜잭션도 안 시작
orderRepository.save(o);            // 구독 누락 — Mono 가 그냥 버려짐

// 2) flatMap 안에서 외부 connection 까지 끌어들임
return orderRepository.findById(id)
    .flatMap(o -> someBlockingCall())   // 트랜잭션 안에서 블로킹 → connection 점유 시간 폭증
    .as(tx::transactional);
```

해결: 구독 검증은 SonarQube `S2629` 류 규칙 / `reactor-tools` 의 `Hooks.onOperatorDebug()` 로 chain-trace 활성화. 블로킹 호출은 반드시 `.publishOn(Schedulers.boundedElastic())` 으로 트랜잭션 밖으로 내보내거나 비동기 API 로 대체한다.

## 9. Spring Boot 자동 설정 키

`application.yml` 에 풀 옵션 풀세트 (Spring Boot 3.x):

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:postgresql://db.example.com:5432/app
    username: app
    password: ${DB_PASSWORD}
    pool:
      enabled: true
      initial-size: 10
      max-size: 40
      max-idle-time: 10m
      max-acquire-time: 3s
      max-life-time: 30m
      validation-query: SELECT 1
      validation-depth: REMOTE
  transaction:
    default-timeout: 5s
```

`spring.transaction.default-timeout` 은 `@Transactional(timeout=...)` 를 명시하지 않은 트랜잭션의 기본 timeout 이다. 5초 정도가 무난하다. 무한정 도는 트랜잭션은 connection 을 끝없이 잡고 있어 풀이 빠르게 고갈된다.

## 10. 결론과 가이드

R2DBC 트랜잭션은 ThreadLocal 이 아닌 Reactor Context 로 전파되며, 그 결과 비동기 체인 내내 동일 connection 이 사용된다. 풀 옵션은 *DB 서버의 한도, 인스턴스 수, 평균 트랜잭션 시간* 세 변수로 결정한다. 비동기라고 connection 을 무한히 늘릴 수 있는 것이 아니다 — DB 의 락/디스크 경합 한계를 그대로 따른다.

운영 체크리스트:

1. `maxAcquireTime` 을 5초 이하로 명시 (무한 대기 금지)
2. `maxLifeTime` 을 30분 이하로 두어 cluster failover 대비
3. Micrometer 메트릭으로 `pending` 증가를 알람화
4. 블로킹 호출은 `boundedElastic` 으로 분리, 트랜잭션 안에서 호출 금지
5. readOnly 트랜잭션은 가능한 분리하고 routing factory 로 replica 사용

## 참고

- Spring Framework Reference — Reactive Transaction Management (`R2dbcTransactionManager`)
- Spring Boot Reference — Data > R2DBC
- r2dbc-pool 1.0 documentation — io.r2dbc.pool README
- PostgreSQL Wiki — Number of database connections (Tomas Vondra)
- Mark Paluch, "Reactive Transactions with R2DBC" SpringOne 2023
- Project Reactor — Context propagation in Reactor 3.5+
