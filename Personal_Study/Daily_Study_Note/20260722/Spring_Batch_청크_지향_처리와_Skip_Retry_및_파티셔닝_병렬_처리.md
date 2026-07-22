Notion 원본: https://app.notion.com/p/3a55a06fd6d381dc91b3fb34b3af6408

# Spring Batch 청크 지향 처리와 Skip·Retry 및 파티셔닝 병렬 처리

> 2026-07-22 신규 주제 · 확장 대상: Spring(배치·대용량 처리)

## 학습 목표

- 청크 지향 처리에서 read-process-write 루프와 트랜잭션 커밋 경계의 관계를 추적한다.
- Skip·Retry 정책이 예외 발생 시 청크를 어떻게 롤백하고 재구동하는지 분석한다.
- Fault-tolerant 스텝의 트랜잭션 재시도 스캔 동작과 성능 함정을 파악한다.
- 파티셔닝·멀티스레드 스텝·원격 청킹의 병렬화 모델을 비교하고 선택 기준을 세운다.

## 1. 청크 지향 처리 — 트랜잭션 단위로서의 청크

Spring Batch의 스텝은 크게 tasklet 방식과 청크 지향(chunk-oriented) 방식으로 나뉜다. 대용량 데이터는 거의 청크 지향으로 처리한다. 청크 지향의 핵심은 "N개의 아이템을 하나의 트랜잭션으로 묶어 커밋한다"이다. `ItemReader`가 한 건씩 읽어 청크 크기만큼 모으고, 각 아이템을 `ItemProcessor`로 변환한 뒤, 청크 단위로 `ItemWriter`가 일괄 기록하고 트랜잭션을 커밋한다.

```java
@Bean
public Step chunkStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("chunkStep", jobRepository)
        .<InputRecord, OutputRecord>chunk(1000, txManager)
        .reader(itemReader())
        .processor(itemProcessor())
        .writer(itemWriter())
        .build();
}
```

청크 크기 1000은 "1000건을 읽고 처리한 뒤 한 번에 write 하고 커밋"을 뜻한다. read는 개별 호출이지만 write는 List로 한꺼번에 온다. 이 구조가 JDBC batch insert나 JPA `flush` 최적화의 전제다. 청크 크기는 성능의 핵심 파라미터다 — 너무 작으면 커밋 오버헤드가 커지고, 너무 크면 트랜잭션이 길어지고 메모리·롤백 세그먼트 압박이 커진다. 실측 기준 대개 500~2000 사이에서 튜닝한다.

## 2. read-process-write 루프의 정확한 순서

청크 처리 루프의 내부 순서를 정확히 알아야 예외 동작을 이해할 수 있다. 한 청크 사이클은 다음과 같이 진행된다. 트랜잭션을 시작하고, `read()`를 청크 크기만큼 반복 호출해 아이템을 버퍼에 모은다(read가 null을 반환하면 데이터 소진). 버퍼의 각 아이템에 `process()`를 적용한다(processor가 null을 반환하면 그 아이템은 필터링되어 write에서 제외). 처리된 아이템 List를 `write()`에 한 번 전달한다. 마지막으로 트랜잭션을 커밋하고 `ChunkContext`를 정리한다.

이 순서에서 중요한 점은 read는 트랜잭션 안에서 이뤄지지만 커서 기반 reader의 경우 커밋 시 커서 상태가 유지돼야 한다는 것이다. 그래서 `JdbcCursorItemReader`는 재시작·재시도에 취약하고, 페이지 기반 `JdbcPagingItemReader`가 병렬·재시작에 더 안전하다. 페이징 reader는 매 청크마다 `SELECT ... OFFSET/키 조건`으로 상태 없는 조회를 하기 때문이다.

## 3. Skip 정책 — 불량 데이터 건너뛰기

배치는 수백만 건 중 몇 건의 불량 데이터 때문에 전체가 실패하면 곤란하다. Skip 정책은 특정 예외가 발생한 아이템을 건너뛰고 계속 진행하게 한다. `faultTolerant()`를 켜고 `skip()`으로 대상 예외와 `skipLimit()`으로 허용 한도를 지정한다.

```java
return new StepBuilder("chunkStep", jobRepository)
    .<InputRecord, OutputRecord>chunk(1000, txManager)
    .reader(itemReader())
    .processor(itemProcessor())
    .writer(itemWriter())
    .faultTolerant()
    .skip(FlatFileParseException.class)
    .skip(ConstraintViolationException.class)
    .skipLimit(50)
    .listener(skipListener())   // 스킵된 아이템 로깅
    .build();
```

여기서 반드시 알아야 할 동작이 있다. write 단계에서 예외가 나면 어느 아이템이 문제인지 특정할 수 없으므로, Spring Batch는 **청크 전체를 롤백한 뒤 아이템을 한 건씩 재처리**한다. 즉 청크 크기 1000에서 write 중 한 건이 제약 위반이면, 그 청크의 1000건을 롤백하고 커밋 간격을 1로 줄여 하나씩 다시 write 하면서 범인을 찾아 스킵한다. 이 "스캔" 동작 때문에 write 스킵은 성능을 크게 떨어뜨린다. read나 process 단계의 스킵은 개별 아이템 단위라 이런 스캔이 없다.

## 4. Retry 정책 — 일시적 오류 재시도

Skip이 "포기하고 넘어감"이라면 Retry는 "다시 시도"다. 데드락, 낙관적 락 충돌, 일시적 네트워크 오류처럼 재시도하면 성공할 수 있는 예외에 적용한다. `retry()`와 `retryLimit()`으로 지정한다.

```java
return new StepBuilder("chunkStep", jobRepository)
    .<InputRecord, OutputRecord>chunk(1000, txManager)
    .reader(itemReader())
    .processor(itemProcessor())
    .writer(itemWriter())
    .faultTolerant()
    .retry(OptimisticLockingFailureException.class)
    .retry(DeadlockLoserDataAccessException.class)
    .retryLimit(3)
    .backOffPolicy(exponentialBackOff())
    .skip(OptimisticLockingFailureException.class)  // 재시도 소진 후 스킵
    .skipLimit(20)
    .build();
```

Retry도 청크 롤백과 재구동을 동반한다. write에서 재시도 대상 예외가 나면 청크를 롤백하고, 재시도 한도까지 청크를 다시 실행한다. Retry와 Skip을 함께 걸면 "재시도를 다 소진해도 실패하면 스킵"하는 방어적 구성이 된다. Backoff 정책을 붙이면 재시도 사이 지연을 두어 데드락 상대가 먼저 커밋하도록 양보할 수 있다. 지수 백오프가 데드락·락 경합에 효과적이다.

## 5. Fault-tolerant 스텝의 숨은 비용

`faultTolerant()`를 켜는 순간 스텝의 처리 모델이 바뀐다. 정상 경로에서는 청크 단위 커밋으로 빠르지만, 예외가 나면 위에서 본 "롤백 후 단건 스캔"이 발동한다. 이 스캔은 실질적으로 청크 크기를 1로 줄이는 것과 같아 처리량이 급락한다. 불량 데이터가 산발적으로 섞인 대용량 파일에서는 스캔이 반복돼 전체 배치 시간이 몇 배로 늘 수 있다.

완화책은 세 가지다. 첫째, 가능하면 **process 단계에서 검증**해 불량 데이터를 write 이전에 걸러라 — process 스킵은 스캔이 없다. 둘째, write 스킵이 불가피하면 청크 크기를 적당히 작게 잡아 스캔 비용의 최댓값(청크 크기)을 제한하라. 셋째, `ItemWriteListener`나 `SkipListener`로 스킵된 레코드를 별도 테이블·파일에 적재해 사후 재처리하는 "dead letter" 패턴을 쓰라. 배치 본류는 빠르게 통과시키고 예외 건은 격리하는 것이 대용량 처리의 정석이다.

## 6. 병렬화 모델 — 멀티스레드 스텝

단일 스레드로 부족하면 병렬화한다. 가장 간단한 방식은 멀티스레드 스텝으로, `taskExecutor()`를 지정하면 청크 처리가 여러 스레드에서 동시에 돈다.

```java
return new StepBuilder("parallelStep", jobRepository)
    .<InputRecord, OutputRecord>chunk(500, txManager)
    .reader(threadSafeReader())   // reader 가 반드시 스레드 안전해야 함
    .writer(itemWriter())
    .taskExecutor(new SimpleAsyncTaskExecutor("batch-"))
    .throttleLimit(8)
    .build();
```

멀티스레드 스텝의 함정은 reader의 스레드 안전성이다. `JdbcCursorItemReader`는 커서를 공유하므로 스레드 안전하지 않다. `JdbcPagingItemReader`나 `SynchronizedItemStreamReader`로 감싸야 한다. 또 재시작 시 어느 스레드가 어디까지 읽었는지 상태가 뒤섞여 정확한 재시작이 어렵다. 그래서 멀티스레드 스텝은 "재시작 정확성보다 처리량"이 중요한 멱등 작업에 적합하다.

## 7. 파티셔닝 — 데이터 분할 병렬 처리

파티셔닝은 데이터를 논리적으로 나눠(예: ID 범위 0~99999, 100000~199999 …) 각 파티션을 독립 스텝 인스턴스로 병렬 실행한다. 마스터 스텝이 `Partitioner`로 파티션 메타데이터를 만들고, 각 파티션은 별도 `StepExecution`으로 실행돼 독립적인 실행 컨텍스트와 트랜잭션을 가진다.

```java
@Bean
public Step masterStep(JobRepository jobRepository, Step workerStep) {
    return new StepBuilder("masterStep", jobRepository)
        .partitioner("workerStep", rangePartitioner())
        .step(workerStep)
        .gridSize(10)
        .taskExecutor(new ThreadPoolTaskExecutor())
        .build();
}

public class RangePartitioner implements Partitioner {
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> result = new HashMap<>();
        long targetSize = (max - min) / gridSize + 1;
        for (int i = 0; i < gridSize; i++) {
            ExecutionContext context = new ExecutionContext();
            context.putLong("minId", min + i * targetSize);
            context.putLong("maxId", Math.min(min + (i + 1) * targetSize - 1, max));
            result.put("partition" + i, context);
        }
        return result;
    }
}
```

파티셔닝의 장점은 각 워커가 완전히 독립적이라는 것이다. 파티션마다 별도 `StepExecution`이 있어 재시작 시 실패한 파티션만 다시 돌릴 수 있고, reader가 파티션별 범위만 읽으므로 스레드 안전 문제가 없다. 각 워커의 reader는 자신의 `minId~maxId`만 조회하므로 커서 공유가 없다. 이것이 멀티스레드 스텝보다 파티셔닝이 재시작·정확성 면에서 우수한 이유다.

| 병렬화 방식 | 재시작 정확성 | reader 제약 | 확장 범위 |
|---|---|---|---|
| 멀티스레드 스텝 | 낮음 | 스레드 안전 필요 | 단일 JVM |
| 파티셔닝(로컬) | 높음 | 파티션별 독립 | 단일 JVM 멀티코어 |
| 파티셔닝(원격) | 높음 | 파티션별 독립 | 다중 노드 |
| 원격 청킹 | 중간 | 마스터가 read | 다중 노드 |

## 8. 원격 병렬화와 선택 기준

단일 JVM을 넘어 확장하려면 원격 파티셔닝이나 원격 청킹을 쓴다. **원격 파티셔닝**은 마스터가 파티션 메타데이터를 메시지 큐(예: Kafka, RabbitMQ)로 워커 노드에 뿌리고, 각 워커가 자기 파티션 범위를 스스로 read-process-write 한다. read 부하까지 분산되므로 read가 병목일 때 효과적이다. **원격 청킹**은 마스터가 read만 하고 process-write를 워커에 분산한다. read는 가볍지만 process가 CPU 집약적일 때 적합하다.

선택 기준은 병목 지점이다. 데이터 조회 자체가 무겁고 파티션 경계를 깔끔히 나눌 수 있으면 원격 파티셔닝, 조회는 가볍지만 변환·계산이 무거우면 원격 청킹이다. 단일 서버 멀티코어로 충분하면 로컬 파티셔닝이 인프라 복잡도 없이 가장 실용적이다. 실무에서는 대부분 로컬 파티셔닝으로 시작해 처리량이 물리 코어를 초과할 때만 원격 방식으로 넘어간다. 분산은 메시지 브로커·직렬화·장애 복구 복잡도를 더하므로, 필요가 증명되기 전에는 단일 노드 파티셔닝을 유지하는 것이 정석이다.

## 9. 재시작과 멱등성 — JobRepository의 역할

Spring Batch가 tasklet 스크립트와 다른 결정적 이유는 **재시작(restart)** 능력이다. `JobRepository`가 모든 `JobExecution`·`StepExecution`의 상태와 진척을 메타데이터 테이블(`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` 등)에 기록한다. 잡이 중간에 실패하면 같은 파라미터로 재실행 시 이미 완료된 스텝은 건너뛰고 실패 지점부터 이어간다.

청크 스텝의 재시작 정확성은 reader의 상태 저장에 달려 있다. `ItemStream`을 구현한 reader는 매 청크 커밋 시 자신의 위치(읽은 라인 수, 페이지 번호 등)를 `ExecutionContext`에 저장한다. 재시작하면 이 컨텍스트에서 위치를 복원해 이미 처리한 데이터를 건너뛴다.

```java
@Bean
public JdbcPagingItemReader<InputRecord> reader(DataSource ds) {
    return new JdbcPagingItemReaderBuilder<InputRecord>()
        .name("recordReader")   // ExecutionContext 키 접두사 — 반드시 지정
        .dataSource(ds)
        .pageSize(1000)
        .saveState(true)
        .build();
}
```

`name`을 반드시 지정해야 한다. 이 이름이 `ExecutionContext`에서 reader 상태를 저장하는 키가 되며, 누락하면 상태 저장이 충돌하거나 재시작이 부정확해진다. 주의할 점은 재시작이 완벽한 exactly-once를 보장하지 않는다는 것이다. write 커밋 후 프로세스가 죽으면 그 청크는 커밋됐지만 스텝은 미완료로 남아, 재시작 시 다음 청크부터 재개돼 대개 문제없다. 그러나 write와 외부 시스템 호출이 한 트랜잭션에 안 묶이면(예: DB 커밋 후 이메일 발송) 재시작 시 중복 발송이 생길 수 있다. 그래서 배치 로직은 가능한 한 **멱등**하게 설계하고, 외부 부수효과는 트랜잭션 경계 안으로 넣거나 별도 멱등 키로 중복을 막아야 한다. `JobParameters`에 타임스탬프를 넣어 매 실행을 고유하게 만드는 관행도 이 메타데이터 추적과 맞물린다 — 동일 파라미터 잡은 완료 시 재실행이 거부되기 때문이다.

## 참고

- Spring Batch Reference — Chunk-Oriented Processing, Fault Tolerance
- Spring Batch Reference — Scaling and Parallel Processing (Partitioning, Remote Chunking)
- Michael Minella, "Pro Spring Batch" — 재시작·병렬화 장
- Spring Batch 소스 — `FaultTolerantChunkProcessor` retry/skip scan 구현
