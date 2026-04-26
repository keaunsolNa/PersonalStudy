Notion 원본: https://www.notion.so/34e5a06fd6d381a08e45e19413800502

# Spring Batch 5 Partitioning과 JobRepository 튜닝

> 2026-04-26 신규 주제 · 확장 대상: Spring (Spring Boot 학습됨), JAVA (Concurrency 학습됨)

## 학습 목표

- Spring Batch 5의 Job/Step/Chunk 모델을 Partitioning 관점에서 재해석한다
- `PartitionHandler`, `Partitioner`, `StepExecutionSplitter`의 책임 분리를 코드로 구현한다
- `JobRepository` 메타데이터 테이블 인덱스와 격리 수준을 튜닝해 동시 Job 실행 시 락 경합을 제거한다
- 1,000만 건 처리 잡에서 LocalPartitioning과 RemotePartitioning을 비교해 적정 partition 수를 결정한다

---

## 1. Chunk 모델의 한계와 Partitioning이 등장하는 지점

Spring Batch의 기본 처리 단위는 chunk-oriented Step이다. `ItemReader → ItemProcessor → ItemWriter`가 하나의 트랜잭션 경계 안에서 chunk-size 만큼 묶여 실행되고, 트랜잭션 commit 시점에 `BATCH_STEP_EXECUTION` 메타데이터가 갱신된다. 이 모델은 단일 스레드에서는 단순하지만, 처리량을 늘리는 두 가지 방법이 존재한다.

첫째, `TaskExecutorRepeatTemplate` 기반의 multi-threaded Step. `taskExecutor`를 지정해 chunk 단위를 병렬 처리한다. 구현은 간단하지만 Reader가 stateful이면 thread-unsafe 문제가 발생한다. JdbcPagingItemReader처럼 lock-free한 Reader만 안전하다.

둘째, Partitioning. Master Step이 입력 공간을 `n`개의 ExecutionContext로 분할하고, 각 partition을 독립적인 Slave StepExecution으로 실행한다. 각 Slave는 자체 트랜잭션 경계를 가지므로 Reader/Writer가 thread-safe할 필요가 없다. 또한 분할 키를 ID 범위로 잡으면 데이터 충돌도 본질적으로 발생하지 않는다.

| 방식 | 병렬 단위 | 트랜잭션 경계 | Reader 요구 |
|---|---|---|---|
| Multi-threaded Step | chunk | chunk별 1 트랜잭션, 공유 StepExecution | thread-safe |
| Local Partitioning | partition | partition별 독립 StepExecution | partition 내 단일 스레드라 자유 |
| Remote Partitioning | partition (다른 JVM) | partition별 독립 StepExecution | 분산 메시징 필요 |

## 2. Partitioner: 입력 공간 분할 전략

`Partitioner`는 master Step에서 호출되어 `Map<String, ExecutionContext>`를 반환한다. 키는 partition 이름, 값은 slave StepExecution의 초기 ExecutionContext다.

```java
public class IdRangePartitioner implements Partitioner {

    private final long minId;
    private final long maxId;

    public IdRangePartitioner(long minId, long maxId) {
        this.minId = minId;
        this.maxId = maxId;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        long total = maxId - minId + 1;
        long range = (total + gridSize - 1) / gridSize; // ceil
        Map<String, ExecutionContext> result = new HashMap<>();
        for (int i = 0; i < gridSize; i++) {
            long from = minId + i * range;
            long to = Math.min(from + range - 1, maxId);
            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong("minId", from);
            ctx.putLong("maxId", to);
            result.put("partition-" + i, ctx);
        }
        return result;
    }
}
```

`gridSize`는 PartitionHandler가 넘겨주는 hint다. Partitioner는 hint와 실제 분할 전략을 다르게 잡을 수 있다. 예를 들어 데이터 분포가 비대칭이면 quartile 기반 분할이 균등 분할보다 낫다. ID가 자연 증가하지 않고 채번 정책이 중간에 바뀐 시스템에서 `min(id)`, `max(id)`로 범위를 잡으면 hot partition이 생긴다. 이 경우 `NTILE` 윈도우 함수로 사전 통계를 만들고 분위수 경계를 ExecutionContext에 박는 편이 안전하다.

## 3. PartitionHandler: 실행 전략의 분기점

`PartitionHandler`는 Partitioner가 만든 ExecutionContext들을 어떻게 실행할지를 결정한다. Spring Batch 5는 두 종류를 제공한다.

`TaskExecutorPartitionHandler`는 같은 JVM 안에서 `TaskExecutor`로 Slave StepExecution을 실행한다. ThreadPoolTaskExecutor의 corePoolSize와 grid size를 일치시켜야 의미가 있다. corePoolSize가 grid size보다 작으면 큐잉되어 직렬화된다.

`MessageChannelPartitionHandler`는 Spring Integration MessageChannel로 Slave 실행 요청을 보낸다. 다른 JVM의 Worker가 메시지를 소비해 StepExecution을 실행하고, 완료 후 결과 메시지를 반환한다. 메시지 브로커로 RabbitMQ나 Kafka를 쓴다.

```java
@Bean
public Step masterStep(JobRepository jobRepository,
                       PartitionHandler partitionHandler,
                       Step workerStep,
                       Partitioner partitioner) {
    return new StepBuilder("masterStep", jobRepository)
            .partitioner("workerStep", partitioner)
            .partitionHandler(partitionHandler)
            .build();
}

@Bean
public TaskExecutorPartitionHandler partitionHandler(Step workerStep,
                                                     TaskExecutor batchTaskExecutor) {
    TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
    handler.setStep(workerStep);
    handler.setTaskExecutor(batchTaskExecutor);
    handler.setGridSize(8);
    return handler;
}

@Bean
public TaskExecutor batchTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("batch-");
    executor.initialize();
    return executor;
}
```

`gridSize`는 partition 개수다. 위 코드에서는 corePoolSize와 같은 8이다. 만약 `gridSize=16, corePoolSize=8`이면 8개가 동시 실행, 8개는 큐에서 대기한다. 이건 단점이라기보다 메모리 사용을 제한하는 안전장치로 의도적으로 쓰는 패턴이다.

## 4. JobRepository: 메타데이터 테이블 구조

Spring Batch 5의 JobRepository는 6개 핵심 테이블을 사용한다.

```
BATCH_JOB_INSTANCE       - JobParameters로 식별되는 unique Job 실행 단위
BATCH_JOB_EXECUTION      - JobInstance의 실제 실행 시도 (재시도 시 행 추가)
BATCH_JOB_EXECUTION_PARAMS - JobParameters 직렬화
BATCH_JOB_EXECUTION_CONTEXT - Job 레벨 ExecutionContext 직렬화
BATCH_STEP_EXECUTION     - 각 Step 실행 (Partitioning 시 master + slave 모두)
BATCH_STEP_EXECUTION_CONTEXT - Step 레벨 ExecutionContext (partition 정보 포함)
```

Partitioning Job 1회는 `BATCH_STEP_EXECUTION`에 `1 (master) + N (slaves)` 행을 만든다. gridSize=64면 65 행이 한 번에 들어간다. 그리고 chunk 처리 중 매 commit 시점에 `read_count`, `write_count`, `commit_count`를 갱신하므로, 큰 batch에서는 이 테이블이 update hotspot이 된다.

운영 튜닝 포인트는 다음과 같다.

`BATCH_JOB_EXECUTION` 조회는 `(JOB_INSTANCE_ID, JOB_EXECUTION_ID DESC)` 인덱스로 최근 시도를 빠르게 가져와야 한다. 기본 schema에는 PK뿐이므로 운영 환경에서 보조 인덱스를 추가한다.

```sql
CREATE INDEX idx_job_exec_instance ON BATCH_JOB_EXECUTION (JOB_INSTANCE_ID, JOB_EXECUTION_ID DESC);
CREATE INDEX idx_step_exec_job ON BATCH_STEP_EXECUTION (JOB_EXECUTION_ID, STEP_EXECUTION_ID);
```

`isolationLevelForCreate`를 기본값 `SERIALIZABLE`에서 `READ_COMMITTED`로 낮추면 동시 Job 실행 충돌이 줄어든다. 단, JobInstance 중복 생성 방어가 약해지므로 application-side에서 JobParameters에 timestamp를 넣어 unique 성을 보장해야 한다.

```java
@Bean
public JobRepository jobRepository(DataSource dataSource, PlatformTransactionManager txManager)
        throws Exception {
    JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
    factory.setDataSource(dataSource);
    factory.setTransactionManager(txManager);
    factory.setIsolationLevelForCreate("ISOLATION_READ_COMMITTED");
    factory.setTablePrefix("BATCH_");
    factory.afterPropertiesSet();
    return factory.getObject();
}
```

대량 partition Job을 자주 돌리면 메타데이터 테이블이 빠르게 커진다. 90일 이상 된 데이터는 별도 archive 테이블로 옮기는 batch를 추가하거나, MySQL의 partitioned table로 `START_TIME` 기준 range partition을 잡는 방법이 있다. Oracle이라면 동일한 concept이 partitioned table + interval partition으로 자연스럽게 구현된다.

## 5. Restart 시 ExecutionContext 복원

Partitioning Job이 6번째 partition에서 실패했다고 하자. 재시작 시 동일 JobParameters로 Job을 호출하면 `BATCH_JOB_EXECUTION`에 새 row가 추가되지만 같은 JobInstance를 공유한다. 각 Slave StepExecution은 ExecutionContext에 last processed key를 보관해 두어야 정확한 지점부터 재시작된다.

```java
@Bean
@StepScope
public JdbcPagingItemReader<Order> orderReader(
        DataSource dataSource,
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) {

    JdbcPagingItemReader<Order> reader = new JdbcPagingItemReaderBuilder<Order>()
            .name("orderReader")
            .dataSource(dataSource)
            .selectClause("SELECT id, user_id, total_amount, status")
            .fromClause("FROM orders")
            .whereClause("id BETWEEN :minId AND :maxId AND status = 'PENDING'")
            .parameterValues(Map.of("minId", minId, "maxId", maxId))
            .sortKeys(Map.of("id", Order.ASCENDING))
            .pageSize(1000)
            .rowMapper(new OrderRowMapper())
            .saveState(true)
            .build();
    return reader;
}
```

`saveState=true`이면 Reader가 chunk commit마다 현재 page와 row 위치를 ExecutionContext에 저장한다. Restart 시 같은 partition의 Slave StepExecution이 그 위치부터 읽기 시작한다. `@StepScope`가 필수다. JobScope나 Singleton이면 partition마다 다른 minId/maxId를 받지 못한다.

## 6. 실측: 1,000만 건 처리

PostgreSQL `orders` 테이블 1,000만 건, ItemProcessor에서 외부 API 호출 1회 (평균 50ms latency)인 워크로드를 가정한다. ItemWriter는 같은 DB 다른 테이블에 INSERT 한다.

| 구성 | gridSize | chunkSize | 처리 시간 | 비고 |
|---|---|---|---|---|
| 단일 스레드 | 1 | 1000 | 약 8.3시간 | I/O 대기로 CPU idle |
| Multi-threaded Step | 1 (8 thread) | 1000 | 약 1.1시간 | Reader thread-safety 검증 필요 |
| Local Partitioning | 8 | 1000 | 약 1.0시간 | DB connection 8 + master 1 |
| Local Partitioning | 16 | 1000 | 약 0.7시간 | DB pool 20으로 늘림 |
| Local Partitioning | 32 | 1000 | 약 0.65시간 | 외부 API rate limit 도달 |

핵심은 partition 수를 무작정 늘리는 게 아니다. 외부 의존성(DB connection pool, HTTP client pool, downstream API rate limit)이 병목이 되는 지점에서 throughput이 plateau에 도달한다. 측정 후 그 직전 값을 운영 partition 수로 잡는다.

또한 chunk size는 트랜잭션 size와 commit 빈도의 trade-off다. 너무 크면 실패 시 rollback 비용이 크고 lock hold 시간이 길어진다. 너무 작으면 commit이 빈번해 처리 시간이 늘어난다. 1000은 일반적으로 합리적인 출발점이고, 외부 API 호출이 무거우면 100~500으로 줄인다.

## 7. Skip / Retry 정책과 멱등성

Partitioning 환경에서 Skip/Retry는 partition 단위로 독립이다. partition-3이 retry를 시도해도 partition-7에는 영향이 없다.

```java
@Bean
public Step workerStep(JobRepository jobRepository,
                       PlatformTransactionManager txManager,
                       ItemReader<Order> reader,
                       ItemProcessor<Order, ProcessedOrder> processor,
                       ItemWriter<ProcessedOrder> writer) {
    return new StepBuilder("workerStep", jobRepository)
            .<Order, ProcessedOrder>chunk(1000, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .faultTolerant()
            .retry(TransientApiException.class)
            .retryLimit(3)
            .skip(InvalidOrderException.class)
            .skipLimit(100)
            .listener(new SkipLoggingListener())
            .build();
}
```

Retry는 ItemProcessor / ItemWriter에서 발생한 transient 오류에 대해 chunk를 다시 실행한다. 여기서 멱등성이 중요하다. Writer가 INSERT로 동작하면 retry 시 중복 row가 생긴다. UPSERT (`ON CONFLICT DO UPDATE`) 또는 처리 결과 기록 테이블에 자연 키 unique 인덱스를 두어 중복 commit을 막는다. 외부 API 호출이라면 idempotency key를 처리 키와 1:1 매핑해 같은 키 재호출은 같은 결과가 반환되도록 한다.

## 8. Trade-off 요약

`Local Partitioning`은 단일 JVM 내에서 추가 메시징 없이 즉시 도입 가능하다. CPU와 DB connection이 단일 머신에 집중되므로 수직 확장의 한계에 빨리 부딪친다. `Remote Partitioning`은 Slave를 다른 머신으로 분산해 수평 확장이 되지만, 메시징 인프라(RabbitMQ/Kafka)와 직렬화 프로토콜이 추가 복잡도를 만든다. 1억 건 미만이면 거의 항상 Local로 충분하다.

`gridSize`를 정적으로 박는 대신 `JobParameters`로 받으면 데이터 양에 따라 운영자가 조절할 수 있다. 동일 Job 인스턴스 재실행 시에는 같은 gridSize를 유지해야 ExecutionContext 복원이 정확하다.

JobRepository 격리 수준을 낮추는 결정은 신중해야 한다. 일정상 충돌이 빈번하지 않으면 기본 SERIALIZABLE을 유지하고, 진짜로 batch가 동시에 수십개씩 도는 운영 환경에서만 READ_COMMITTED를 고려한다.

## 참고

- Spring Batch 5 Reference Documentation - Partitioning
- Michael Minella, "Pro Spring Batch", Apress
- Spring Batch GitHub `spring-projects/spring-batch` - `PartitionHandler` JavaDoc
- PostgreSQL Documentation - Index types and concurrent index creation
