Notion 원본: https://app.notion.com/p/3e65a06fd6d3813a8752f2a503121301

# Spring Batch 청크 지향 처리와 트랜잭션 경계 및 재시작 가능한 Job 설계

> 2026-09-25 신규 주제 · 확장 대상: Spring

## 학습 목표

- 청크 지향(Chunk-Oriented) 처리 모델에서 Reader/Processor/Writer의 트랜잭션 경계를 구분한다
- `JobRepository`의 메타데이터 테이블 구조를 통해 재시작(restart) 메커니즘을 설명한다
- `ItemReader`의 상태 저장 방식에 따라 재시작 시 중복 처리를 방지하는 설계를 적용한다
- Skip/Retry 정책을 조합해 부분 실패를 허용하는 Job을 구성한다

## 1. 청크 지향 처리 모델의 트랜잭션 경계

Spring Batch의 핵심 처리 모델인 Chunk-Oriented Processing은 `Item` 단위가 아니라 **청크(chunk) 단위**로 트랜잭션을 커밋한다. `chunk(N)`으로 설정한 크기만큼 `ItemReader`가 읽고 `ItemProcessor`가 가공한 뒤, `ItemWriter`가 리스트 단위로 일괄 기록하면 그 시점에 트랜잭션이 커밋된다.

```java
@Bean
public Step processOrdersStep(JobRepository jobRepository,
                               PlatformTransactionManager txManager,
                               ItemReader<Order> reader,
                               ItemProcessor<Order, ProcessedOrder> processor,
                               ItemWriter<ProcessedOrder> writer) {
    return new StepBuilder("processOrdersStep", jobRepository)
            .<Order, ProcessedOrder>chunk(100, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
}
```

이 구조에서 트랜잭션의 시작과 종료는 `RepeatTemplate`이 청크 경계마다 관리한다. 100건을 읽고 처리하는 동안은 트랜잭션이 열려 있지 않고(Reader는 커서 기반이든 페이징 기반이든 자체적으로 독립 트랜잭션이나 논트랜잭션으로 동작), Writer가 호출되는 순간에만 트랜잭션이 시작되어 100건 전체를 하나의 트랜잭션으로 묶어 커밋한다. 이 방식의 장점은 명확하다. 전체 100만 건을 하나의 트랜잭션으로 처리하면 언두 로그가 비대해지고 장시간 락이 유지되지만, 청크 단위 커밋은 실패 시 롤백 범위를 해당 청크로 국한시키고 커밋 시점마다 언두 로그를 비운다.

주의할 점은 `ItemReader`가 기본적으로 **논트랜잭션 상태**에서 읽기를 수행한다는 것이다. `JdbcCursorItemReader`처럼 커서를 여는 경우 청크 트랜잭션과 커서 수명주기가 어긋나면 "트랜잭션 커밋 후 커서 무효화" 문제가 생길 수 있어, `driverSupportsAbsolute`나 `saveState` 옵션을 명확히 설정해야 한다.

## 2. JobRepository 메타데이터 스키마

Spring Batch는 Job 실행 이력을 `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_EXECUTION_CONTEXT` 6개 테이블에 영속화한다. 이 메타데이터가 재시작의 근간이다.

`BATCH_JOB_INSTANCE`는 Job 이름과 `JobParameters`의 조합(식별 파라미터만 해시)으로 유일하게 식별된다. 즉 동일한 식별 파라미터로 같은 Job을 다시 실행하면 새 Instance가 아니라 기존 Instance에 연결된 새 Execution이 생성된다. 이것이 "동일 파라미터로 재실행하면 이미 완료된 Job은 재실행되지 않는다"(`JobInstanceAlreadyCompleteException`)는 Spring Batch의 기본 방어 동작의 근거다.

```sql
-- BATCH_STEP_EXECUTION_CONTEXT 는 각 Step의 진행 상태(ExecutionContext)를
-- 직렬화된 형태로 저장한다. 재시작 시 이 값을 복원해 ItemReader의 상태를 되살린다.
SELECT step_execution_id, short_context
FROM BATCH_STEP_EXECUTION_CONTEXT
WHERE step_execution_id = ?;
```

`ExecutionContext`는 Step 안에서 `key-value` 쌍으로 임의 상태를 저장할 수 있는 컨테이너다. `ItemStreamReader`를 구현하는 대부분의 Reader(`FlatFileItemReader`, `JdbcPagingItemReader`)는 `update(ExecutionContext)` 메서드에서 현재까지 읽은 라인 수나 페이지 오프셋을 이 컨텍스트에 기록하고, `open(ExecutionContext)`에서 이를 읽어 재개 지점을 복원한다.

## 3. 재시작 가능한 ItemReader 설계

재시작을 안전하게 만들려면 `ItemReader`가 **멱등하게 재개 가능**해야 한다. 대표적인 두 가지 패턴을 비교한다.

**(1) FlatFileItemReader (파일 기반)**: `saveState(true)`(기본값)로 설정하면 현재까지 읽은 라인 번호를 `ExecutionContext`에 저장한다. Job이 중단 후 재시작되면 파일을 처음부터 열되 저장된 라인 수만큼 건너뛰고 이어서 읽는다. 파일 자체가 변경되지 않는다는 전제가 필요하다.

```java
@Bean
public FlatFileItemReader<OrderCsv> orderReader() {
    return new FlatFileItemReaderBuilder<OrderCsv>()
            .name("orderReader")           // saveState 키의 접두사로 사용됨
            .resource(new FileSystemResource("orders.csv"))
            .delimited().delimiter(",")
            .names("orderId", "amount", "status")
            .targetType(OrderCsv.class)
            .saveState(true)
            .build();
}
```

**(2) JdbcPagingItemReader (DB 기반)**: 페이지 단위로 `ORDER BY` 조건이 있는 쿼리를 실행하며, 마지막으로 읽은 정렬 키 값을 `ExecutionContext`에 저장한다. 재시작 시 `WHERE id > :lastId` 형태로 이어서 조회한다. 이때 정렬 기준 컬럼에 **유일하고 안정적인 값**(PK 등)을 사용해야 한다. 그렇지 않으면 페이지 경계에서 레코드가 누락되거나 중복 처리될 수 있다.

```java
@Bean
public JdbcPagingItemReader<Order> orderPagingReader(DataSource dataSource) {
    Map<String, Order.Sort> sortKeys = new HashMap<>();
    sortKeys.put("id", Order.Sort.ASCENDING);

    SqlPagingQueryProviderFactoryBean provider = new SqlPagingQueryProviderFactoryBean();
    provider.setDataSource(dataSource);
    provider.setSelectClause("SELECT id, amount, status");
    provider.setFromClause("FROM orders");
    provider.setWhereClause("WHERE status = 'PENDING'");
    provider.setSortKeys(sortKeys);

    return new JdbcPagingItemReaderBuilder<Order>()
            .name("orderPagingReader")
            .dataSource(dataSource)
            .queryProvider(getObject(provider))
            .pageSize(500)
            .rowMapper(new OrderRowMapper())
            .saveState(true)
            .build();
}
```

여기서 실무적으로 흔한 함정은 `WHERE status = 'PENDING'`처럼 **Writer가 상태를 바꾸는 컬럼을 Reader의 필터 조건으로 쓰는 것**이다. 청크가 커밋되어 일부 레코드의 status가 'DONE'으로 바뀌면, 페이징 커서 위치가 어긋나 다음 페이지 조회 시 일부 레코드를 건너뛰는 일이 발생할 수 있다. 이를 피하려면 필터 조건에 "이번 Job Instance에 배정된 스냅샷"(예: 배치 실행 시점에 별도 컬럼으로 마킹)을 사용하거나, ID 범위를 사전에 고정하는 방식이 안전하다.

## 4. Skip과 Retry 정책

운영 데이터는 항상 깨끗하지 않다. 일부 레코드의 파싱 실패나 일시적 네트워크 오류로 전체 Job을 실패시키는 대신, Spring Batch는 Step 단위로 Skip/Retry 정책을 선언적으로 설정할 수 있다.

```java
@Bean
public Step step(JobRepository jobRepository, PlatformTransactionManager txManager,
                  ItemReader<Order> reader, ItemProcessor<Order, ProcessedOrder> processor,
                  ItemWriter<ProcessedOrder> writer) {
    return new StepBuilder("step", jobRepository)
            .<Order, ProcessedOrder>chunk(100, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .faultTolerant()
            .skip(ParseException.class)
            .skipLimit(50)                       // 최대 50건까지 스킵 허용, 초과 시 Job 실패
            .retry(DeadlockLoserDataAccessException.class)
            .retryLimit(3)                        // 데드락 시 최대 3회 재시도
            .backOffPolicy(new ExponentialBackOffPolicy())
            .listener(new SkipListener<Order, ProcessedOrder>() {
                @Override
                public void onSkipInProcess(Order item, Throwable t) {
                    log.warn("스킵된 레코드: id={}, reason={}", item.getId(), t.getMessage());
                }
            })
            .build();
}
```

`faultTolerant()`를 선언하면 청크 처리 방식이 내부적으로 바뀐다. 청크 커밋이 실패하면 Spring Batch는 해당 청크를 **item 단위로 재실행(scan)**하여 어떤 item이 실패의 원인인지 격리한다. 이는 청크 크기가 클수록 재시도 비용이 커진다는 트레이드오프를 의미하므로, Skip/Retry를 많이 쓰는 Step은 청크 크기를 상대적으로 작게(예: 10~50) 잡는 편이 재시도 오버헤드를 줄인다.

## 5. Step 간 데이터 전달과 JobExecutionContext

여러 Step으로 구성된 Job에서 이전 Step의 결과를 다음 Step에 전달해야 할 때가 있다. `ExecutionContext`를 Step 범위가 아니라 Job 범위로 승격시키려면 `ExecutionContextPromotionListener`를 사용한다.

```java
@Bean
public Step extractStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
    listener.setKeys(new String[]{"processedCount"});

    return new StepBuilder("extractStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                StepContext stepContext = chunkContext.getStepContext();
                stepContext.getStepExecution().getExecutionContext()
                        .putLong("processedCount", 12345L);
                return RepeatStatus.FINISHED;
            }, txManager)
            .listener(listener)
            .build();
}
```

`processedCount`는 이제 `JobExecutionContext`로 승격되어, 이후 Step에서 `stepExecution.getJobExecution().getExecutionContext().getLong("processedCount")`로 조회할 수 있다.

## 6. 재시작 시나리오별 동작 비교

| 시나리오 | 동작 |
|---|---|
| 같은 JobParameters로 정상 완료된 Job 재실행 | `JobInstanceAlreadyCompleteException` 발생, 재실행 거부 |
| 실패한 Job을 같은 JobParameters로 재실행 | 마지막 실패 Step부터 재개, 이전 성공 Step은 재실행 안 함(기본) |
| Step에 `allowStartIfComplete(true)` 설정 | 이전에 성공한 Step도 재시작 시 다시 실행 |
| JobParameters에 타임스탬프 등 매번 다른 값 포함 | 항상 새 JobInstance로 취급되어 처음부터 실행 |

운영에서는 "매번 새로 실행되어야 하는 배치"(예: 일별 정산)와 "실패 시 이어서 재개해야 하는 배치"(예: 대용량 마이그레이션)를 JobParameters 설계로 구분해야 한다. 전자는 실행 시각을 파라미터에 포함시키고, 후자는 논리적 식별자(예: 배치 대상 날짜)만 파라미터로 사용해 재시작 시 동일 Instance로 인식되게 한다.

## 7. Tasklet 기반 Step과의 비교

모든 Step이 청크 지향일 필요는 없다. 파일 이동, 사전/사후 검증처럼 단일 트랜잭션으로 처리해도 무방한 작업은 `Tasklet` 기반 Step으로 구현한다.

```java
@Bean
public Step validateStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("validateStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                if (!preconditionMet()) {
                    throw new IllegalStateException("사전 조건 미충족");
                }
                return RepeatStatus.FINISHED;
            }, txManager)
            .build();
}
```

Tasklet은 `execute()`가 `RepeatStatus.FINISHED`를 반환할 때까지 반복 호출되는 구조라, 폴링이 필요한 작업(예: 외부 시스템 응답 대기)에도 활용된다. 청크 지향과 Tasklet 중 어느 쪽을 쓸지는 "처리 대상이 대량의 동질적 레코드인가"(청크) 대 "처리 단위가 하나의 원자적 작업인가"(Tasklet)로 구분하면 대체로 명확해진다.

## 8. 운영 관점의 모니터링 포인트

`BATCH_STEP_EXECUTION` 테이블의 `commit_count`, `read_count`, `write_count`, `skip_count`, `rollback_count` 컬럼은 Job의 건강 상태를 파악하는 1차 지표다. 특히 `rollback_count`가 `commit_count`에 비해 비정상적으로 높다면 청크 크기가 지나치게 커서 데드락이나 락 경합이 빈번하다는 신호로 해석할 수 있다. 대용량 배치를 신규 도입할 때는 청크 크기를 100~1000 범위에서 여러 값으로 실측 비교하여, 처리량(`write_count`/실행 시간)과 롤백 빈도의 트레이드오프를 확인한 뒤 값을 고정하는 것이 안전하다. 또한 `JobExecutionListener.afterJob()`에서 최종 `ExitStatus`를 확인해 부분 성공(Skip으로 인한 `COMPLETED WITH SKIPS`)과 완전 성공을 구분해 알림을 분기하는 것이 실무적으로 유용하다.

## 참고

- Spring Batch Reference Documentation, "Chunk-oriented Processing"
- Spring Batch Reference Documentation, "Configuring a Step for Restart"
- Spring Batch Reference Documentation, "Skip and Retry"
- Spring Batch Reference Documentation, "Passing Data to Future Steps"
