Notion 원본: https://www.notion.so/3575a06fd6d3815283fee0784699b296

# Spring Batch 5 — Chunk Processing 과 Partitioning 기반 분산 처리

> 2026-05-05 신규 주제 · 확장 대상: Spring Boot Auto Configuration, Spring Modulith

## 학습 목표

- Spring Batch 5 의 chunk-oriented step 이 어떻게 트랜잭션과 retry/skip 정책을 묶는지 흐름으로 정리한다
- Local Partitioning 과 Remote Partitioning 의 메시지 경계·실패 회복 차이를 코드와 운영 지표로 비교한다
- ItemReader/Processor/Writer 가 분산 환경에서 *상태를 가지지 않게* 하는 설계 원칙과 함정을 코드로 검증한다
- JobRepository 의 동시성 제어와 idempotency 처리 방식을 운영 관점에서 정리한다

## 1. Spring Batch 5 의 위치 — 5.0 / 5.1 / 5.2 의 변화

Spring Batch 는 5.0 에서 Java 17 baseline 으로 올라갔고 `JobBuilderFactory` 등 deprecated API 가 *제거* 됐다. 5.1 은 Virtual Threads 지원을 step executor 에 추가했고, 5.2 는 `Observability` 자동 등록과 Native Image 호환성을 정리했다. Spring Boot 3.4 와 함께 출시된 5.2 가 *2026 년 신규 도입의 baseline* 이다.

```java
// 5.0 이전 (deprecated)
@Bean
Job legacyJob(JobBuilderFactory jobs, Step step) {
    return jobs.get("legacyJob").start(step).build();
}

// 5.0+
@Bean
Job job(JobRepository repository, Step step) {
    return new JobBuilder("dailyAggregateJob", repository)
        .start(step)
        .build();
}
```

builder factory 제거의 실제 영향은 *컴파일 에러보다 마이그레이션 주의 사항* 이다. 자동 빌더 팩토리에서 자동 주입되던 `PlatformTransactionManager` 가 5.0+ 에서는 step builder 호출 시 명시적으로 넘겨야 한다. 잘못 잡으면 transaction 이 silently 비활성화되어 chunk 가 한 건씩 commit 되는 사고가 발생한다.

## 2. Chunk-Oriented Step — 트랜잭션 / retry / skip 의 경계

`StepBuilder.<I, O>chunk(size, txManager)` 는 다음 4가지 책임을 *하나의 트랜잭션 경계* 안에 묶는다.

1. ItemReader 가 chunk size 만큼 read 호출 (read 자체는 트랜잭션 외부일 수 있음).
2. ItemProcessor 가 read 된 각 item 을 변환.
3. ItemWriter 가 chunk 단위로 한 번에 write.
4. JobRepository 에 step execution 의 read-count / write-count / commit-count 를 update.

```java
@Bean
Step aggregateStep(JobRepository repository,
                   PlatformTransactionManager txManager,
                   ItemReader<RawEvent> reader,
                   ItemProcessor<RawEvent, AggregatedRow> processor,
                   ItemWriter<AggregatedRow> writer) {
    return new StepBuilder("aggregateStep", repository)
        .<RawEvent, AggregatedRow>chunk(500, txManager)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .faultTolerant()
        .retry(TransientDataAccessException.class).retryLimit(3)
        .skip(MalformedRowException.class).skipLimit(50)
        .listener(new ChunkAuditListener())
        .build();
}
```

retry 와 skip 은 `faultTolerant()` 활성화 후에만 동작한다. retry 가 발생하면 chunk 전체가 *처음부터* 재실행되며, 트랜잭션이 rollback 된다. 따라서 *processor / writer 가 멱등하지 않으면 retry 가 외부 시스템을 손상* 시킨다. retry 설계의 핵심은 *리더의 cursor 가 chunk 시작점으로 복귀해야 한다는 점* 이다. JDBC cursor reader 는 자동으로 처리하지만 custom reader 는 직접 챙겨야 한다.

skip 은 chunk 안의 *해당 1건만* 건너뛰는 듯 보이지만 실제로는 chunk 전체를 *fan-out re-run* 한다. chunk size = 500 이고 한 건이 skip 되면, 499 건을 1-by-1 트랜잭션으로 다시 실행한다. 따라서 skip 이 빈번한 데이터셋에서 chunk size 가 크면 *처리량이 급락* 한다. 원본 데이터의 오류율을 측정해 chunk size 를 조정하는 것이 운영의 핵심이다.

## 3. Local Partitioning — 단일 JVM 멀티 스레드

partitioning 은 step 을 *master + worker* 쌍으로 분리하고, master 가 worker 에게 *partition 메타데이터(예: `id BETWEEN 1 AND 1000`)* 를 흘려보낸다. local partitioning 은 master 와 worker 가 동일 JVM 에 있고 `TaskExecutor` 로 worker step 을 동시 실행한다.

```java
@Bean
Step masterStep(JobRepository repository,
                Partitioner partitioner,
                Step workerStep,
                TaskExecutor executor) {
    return new StepBuilder("masterStep", repository)
        .partitioner("workerStep", partitioner)
        .step(workerStep)
        .gridSize(8)
        .taskExecutor(executor)
        .build();
}

@Bean
Partitioner partitioner(JdbcTemplate jdbc) {
    return gridSize -> {
        long max = jdbc.queryForObject("SELECT MAX(id) FROM raw_events", Long.class);
        long span = max / gridSize + 1;
        Map<String, ExecutionContext> ctx = new HashMap<>();
        for (int i = 0; i < gridSize; i++) {
            ExecutionContext c = new ExecutionContext();
            c.putLong("minId", i * span);
            c.putLong("maxId", (i + 1) * span - 1);
            ctx.put("partition-" + i, c);
        }
        return ctx;
    };
}
```

worker step 의 `ItemReader` 는 `@StepScope` 빈으로 만들어 `#{stepExecutionContext['minId']}` 같은 SpEL 로 partition 범위를 주입받는다. 이 패턴이 가장 흔한 함정의 진원지다. 실수로 `@StepScope` 를 빠뜨리면 reader 가 싱글톤이 되어 모든 worker 가 *같은 cursor* 를 공유한다. 결과는 *데이터 중복 처리 + race condition*. 빌드 타임 검증 도구가 없어 운영 사고 사례가 잦다.

| 측정 항목 | gridSize=1 | gridSize=8 (8 코어) |
|---|---|---|
| 100M rows aggregate 처리 | 4시간 12분 | 38분 |
| commit 횟수 | 200,000 | 200,000 |
| Connection pool 점유 | 1 | 8 |
| GC pause (P99) | 120ms | 240ms (heap 압박) |

local partitioning 은 *코어 수까지 선형 가속* 하다가 그 이상에서는 connection pool 과 heap 이 병목이 된다. JDBC pool size = gridSize × chunkSize 의 *피크 점유* 를 가정하고 capacity 를 늘려야 한다.

## 4. Remote Partitioning — Kafka / RabbitMQ 기반 분산

worker 가 다른 JVM(또는 K8s pod) 에 있는 경우 master 는 partition 메시지를 message broker 에 publish 하고, worker 는 subscribe 해서 step 을 실행한다. spring-batch-integration 의 `RemotePartitioningMasterStepBuilder` / `RemotePartitioningWorkerStepBuilder` 가 표준 구현을 제공한다.

```java
@Bean
Step masterStep(JobRepository repo,
                Partitioner partitioner,
                MessageChannel partitionRequests,
                PollableChannel partitionReplies) {
    return new RemotePartitioningMasterStepBuilder("masterStep", repo)
        .partitioner("workerStep", partitioner)
        .gridSize(16)
        .outputChannel(partitionRequests)
        .inputChannel(partitionReplies)
        .build();
}

@Bean
Step workerStep(JobRepository repo,
                MessageChannel partitionRequests,
                MessageChannel partitionReplies,
                Step actualWorkStep) {
    return new RemotePartitioningWorkerStepBuilder("workerStep", repo)
        .inputChannel(partitionRequests)
        .outputChannel(partitionReplies)
        .step(actualWorkStep)
        .build();
}
```

remote 의 핵심 차이는 *실패 회복 모델* 이다. master 가 partition 메시지를 보내고 N 분 안에 reply 가 안 오면, *job 전체* 가 timeout 으로 실패한다. 이때 어떤 partition 이 어디까지 처리됐는지를 JobRepository 의 `BATCH_STEP_EXECUTION` 테이블에서 확인할 수 있다. 단, broker 가 at-most-once 라면 partition 메시지 자체가 유실될 수 있고, master 의 retry 정책이 없으면 *job 이 hang* 된다.

운영 관점에서는 다음 4가지를 표준화해야 한다.

첫째, partition 메시지의 *idempotency key* 를 partition 이름 + jobInstanceId 조합으로 만든다. worker 가 같은 partition 을 두 번 받으면 두 번째는 no-op 처리한다.

둘째, JobRepository 를 *worker 와 master 가 공유* 하게 한다. 별도 DB 를 쓰면 master 가 worker 의 진행을 못 본다.

셋째, broker 의 dead-letter queue 에 들어간 partition 메시지를 *주기적으로 inspect* 한다. step execution 의 status 가 `STARTED` 인 채로 멈춘 경우가 많다.

넷째, K8s pod 의 *graceful shutdown* 과 step 의 commit interval 을 일치시킨다. `terminationGracePeriodSeconds` 가 chunk commit 보다 짧으면 *부분 커밋된 chunk* 가 남는다.

## 5. ItemReader 의 stateless 설계

partition 환경에서 reader 가 상태(예: 마지막 cursor) 를 인스턴스 변수로 가지고 있으면 *멀티 스레드 충돌* 이 발생한다. `JdbcCursorItemReader` 는 cursor 가 connection 단위 상태이므로 `@StepScope` 가 필수다. `JdbcPagingItemReader` 는 페이지 단위 SQL 을 다시 실행하므로 stateless 에 가깝다. partition 키를 `WHERE` 절에 넣어 두면 안전하다.

```java
@Bean
@StepScope
JdbcPagingItemReader<RawEvent> reader(
        DataSource ds,
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) {

    return new JdbcPagingItemReaderBuilder<RawEvent>()
        .name("rawEventReader")
        .dataSource(ds)
        .pageSize(1000)
        .selectClause("SELECT id, user_id, payload, occurred_at")
        .fromClause("FROM raw_events")
        .whereClause("id BETWEEN :min AND :max")
        .parameterValues(Map.of("min", minId, "max", maxId))
        .sortKeys(Map.of("id", Order.ASCENDING))
        .rowMapper(new BeanPropertyRowMapper<>(RawEvent.class))
        .build();
}
```

`sortKeys` 는 *고유한 정렬 키* 여야 한다. 같은 값의 페이징 키가 여러 건이면 페이지 경계에서 row 가 누락되거나 중복된다. `pageSize` 와 chunk size 는 별개이며, page = 1000, chunk = 500 이면 한 page 가 두 chunk 에 걸친다. page 경계와 chunk 경계가 어긋나도 정합성에는 문제가 없지만, retry 시 *로그 분석을 어렵게* 만든다. 같은 값으로 두는 것이 운영상 유리하다.

## 6. JobRepository 의 동시성과 idempotency

JobRepository 는 6개 핵심 테이블 (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`) 로 동작한다. 같은 *job parameters* 로 두 번 실행하면 동일 `JOB_INSTANCE` 가 재사용된다. 이미 `COMPLETED` 인 instance 는 재실행이 거부된다.

따라서 일별 배치를 매일 실행하려면 `runDate` 같은 파라미터를 명시적으로 다르게 줘야 한다. JobLauncher 에 `JobParametersIncrementer` 를 등록해 `RunIdIncrementer` 를 쓰는 패턴이 표준이지만, 이 ID 만으로는 *수동 재실행* 시 멱등성이 깨지기 쉽다. 대신 **`runDate=YYYY-MM-DD`** 를 explicit 파라미터로 넘겨서 같은 날짜는 같은 instance 로 모이게 하는 것이 권장된다.

| 정책 | 멱등성 | 재실행 가능 | 운영 비용 |
|---|---|---|---|
| RunIdIncrementer (자동 증가) | X | 항상 새 instance | 로그 추적 어려움 |
| explicit runDate 파라미터 | O | restart 로 재실행 | 추적 쉬움 |
| explicit runDate + force flag | O | 강제 재실행 | force flag 오용 위험 |

JobRepository 의 동시성 제어는 *DB 의 isolation level* 에 의존한다. MySQL InnoDB 의 `READ_COMMITTED` 가 기본값으로 권장된다. `REPEATABLE_READ` 에서는 step execution status 의 update 가 phantom read 를 일으켜 step 이 두 번 실행되는 사고가 보고된 적 있다 (Spring Batch GitHub #4283 참조).

## 7. 운영에서 본 trade-off

| 상황 | 권장 |
|---|---|
| 단일 노드, 100M rows 이하 / 매일 실행 | local partitioning, gridSize=코어수 |
| 1B rows 이상 / SLA 1 시간 이내 | remote partitioning, K8s Job + Kafka |
| 외부 API 호출이 처리량 병목 | chunk 작게(50~100), retry 보수적, fault-tolerant 필수 |
| 처리 단위가 매우 작은 다건(메시지) | Spring Batch 가 아니라 Spring Cloud Data Flow / Kafka Streams 검토 |

Virtual Threads(Loom) 를 step executor 로 쓰는 5.1+ 패턴은 IO 위주 step 에서 chunk 처리량을 30~40% 끌어올린다. 하지만 *DB connection 이 carrier thread 를 pin* 하면 이득이 사라진다. JDBC driver 가 PostgreSQL 42.7+ / MySQL 8.4+ 처럼 *pinning 없는* 버전을 쓰는지 먼저 확인한다.

## 참고

- Spring Batch 5.2 Reference — Chapter 4 (Chunk Processing), Chapter 11 (Partitioning)
- "Spring Batch in Action" 2nd edition — Chapter 13 Scaling
- Spring Batch GitHub Issues — #4283, #4451 (JobRepository concurrency)
- Spring Boot 3.4 Release Notes — Batch auto-configuration changes