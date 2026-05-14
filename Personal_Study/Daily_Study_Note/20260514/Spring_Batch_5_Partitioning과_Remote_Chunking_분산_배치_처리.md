Notion 원본: https://www.notion.so/3605a06fd6d381f48113cfb3a6be02ef

# Spring Batch 5 Partitioning과 Remote Chunking — 대용량 배치를 노드 단위로 쪼개 처리량을 올리는 두 갈래

> 2026-05-14 신규 주제 · 확장 대상: Backend

## 학습 목표

- 단일 JVM `chunk` 모델의 처리량 한계 지점과 *수평 확장* 이 필요한 임계 인식
- Partitioning 과 Remote Chunking 이 *각각 무엇을 분산하는지* 와 운영 트레이드오프 비교
- `PartitionHandler` · `ItemWriter` 원격화 구현을 메시지 브로커(Spring Integration Kafka/RabbitMQ) 와 결합
- 실패/재시작 시 Job Repository 와 ExecutionContext 가 어떻게 보존되는지 확인

## 1. 단일 JVM Chunk 모델의 한계

Spring Batch 의 기본 step 모델은 `ItemReader → ItemProcessor → ItemWriter` 를 *chunk 단위* 로 트랜잭션 경계를 묶는 구조다. 한 노드에서 thread pool 을 키우면 처리량은 늘지만, 다음 지점에서 막힌다.

- DB 트랜잭션 lock 경합: chunk 마다 commit, 같은 row 를 동시 갱신하면 deadlock 폭증
- ItemReader 의 cursor / page 단일성: `JpaCursorItemReader` 는 본질적으로 단일 connection
- GC 압력: chunk 가 메모리에 머무는 시간 × 동시 thread 수

목표 throughput 이 *분당 100만 건* 이상이거나 *시간 SLA 가 빡빡* 한 EOD(End-Of-Day) 잡이면 단일 JVM 한계에 닿는다. 이때 두 가지 분산 전략을 선택한다 — Partitioning 과 Remote Chunking.

## 2. 두 모델의 본질적 차이

| 항목 | Partitioning | Remote Chunking |
|---|---|---|
| 분산 대상 | Step 단위 (read+process+write 전부) | Chunk 의 process+write 만 |
| Master 역할 | 입력 범위 분할만 | Reader 로 직접 읽고 워커에 chunk 전송 |
| Worker 역할 | 자신만의 reader/writer 가짐 | reader 없음, master 가 보낸 chunk 처리 |
| 메시지 브로커 | 선택 (Spring Integration 으로 가능) | 필수 (Kafka/Rabbit/JMS) |
| Reader 병렬화 | 가능 — 각 워커가 다른 range | 불가능 — master 단일 reader 가 병목 |
| 코드 침습성 | reader 변경 (range 인자 지원 필요) | reader 그대로, 통신 코드만 추가 |
| 부분 실패 복구 | partition 단위 재실행 | message redelivery 로 자동 |

핵심 직관: **읽기가 병목이면 Partitioning, 처리가 병목이면 Remote Chunking.** OCR/이미지 변환/외부 API 호출 같은 *건당 무거운 처리* 는 Remote Chunking 이 적합하다. DB 의 큰 테이블을 ID range 로 잘라 읽어야 한다면 Partitioning 이 자연스럽다.

## 3. Partitioning 구현 골격

```java
@Configuration
public class PartitionedJobConfig {

	@Bean
	public Job dailySalesJob(JobRepository jobRepository, Step partitionMasterStep) {
		return new JobBuilder("dailySalesJob", jobRepository)
				.start(partitionMasterStep)
				.build();
	}

	@Bean
	public Step partitionMasterStep(
			JobRepository jobRepository,
			Step workerStep,
			Partitioner partitioner,
			PartitionHandler partitionHandler
	) {
		return new StepBuilder("partitionMasterStep", jobRepository)
				.partitioner(workerStep.getName(), partitioner)
				.partitionHandler(partitionHandler)
				.build();
	}

	@Bean
	public Partitioner partitioner() {
		return gridSize -> {
			Map<String, ExecutionContext> result = new HashMap<>(gridSize);
			long min = 1L;
			long max = 100_000_000L;
			long stride = (max - min) / gridSize + 1;
			for (int i = 0; i < gridSize; i++) {
				ExecutionContext ctx = new ExecutionContext();
				ctx.putLong("minId", min + i * stride);
				ctx.putLong("maxId", Math.min(max, min + (i + 1) * stride - 1));
				result.put("partition-" + i, ctx);
			}
			return result;
		};
	}

	@Bean
	@StepScope
	public JdbcCursorItemReader<Sale> reader(
			@Value("#{stepExecutionContext['minId']}") Long minId,
			@Value("#{stepExecutionContext['maxId']}") Long maxId,
			DataSource dataSource
	) {
		return new JdbcCursorItemReaderBuilder<Sale>()
				.name("saleReader")
				.dataSource(dataSource)
				.sql("SELECT id, amount FROM sale WHERE id BETWEEN ? AND ? ORDER BY id")
				.preparedStatementSetter((ps) -> {
					ps.setLong(1, minId);
					ps.setLong(2, maxId);
				})
				.rowMapper((rs, rowNum) -> new Sale(rs.getLong("id"), rs.getBigDecimal("amount")))
				.build();
	}
}
```

여기서 핵심은 `@StepScope` 와 `stepExecutionContext` 다. 각 워커 step 은 별도 stepExecutionContext 를 받아 `minId/maxId` 가 주입된다. ID range 가 *조밀* 하지 않으면(삭제 등으로 sparse) round-robin 분포가 깨지므로 chunk skew 가 생긴다. 이 경우 `id % gridSize == n` 같은 modulo 분할 또는 timestamp 분할로 변경.

## 4. PartitionHandler — local vs remote

`TaskExecutorPartitionHandler` 는 *같은 JVM 의 thread pool* 로 워커를 띄운다. Kubernetes 의 *수직* 스케일링이라 가장 단순하지만 한 노드 자원에 묶인다.

```java
@Bean
public PartitionHandler partitionHandler(Step workerStep, TaskExecutor taskExecutor) {
	TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
	handler.setStep(workerStep);
	handler.setTaskExecutor(taskExecutor);
	handler.setGridSize(8);
	return handler;
}
```

진짜 수평 확장은 `MessageChannelPartitionHandler` 다. master 가 partition message 를 채널에 publish 하고, 별도 JVM/Pod 의 워커들이 받아 자신만의 step 을 실행한다. 결과는 reply channel 로 응답한다. Spring Integration 의 inbound/outbound gateway 가 메시지 ↔ StepExecution 직렬화를 담당한다.

```java
@Bean
public MessageChannelPartitionHandler messageChannelPartitionHandler(
		MessagingTemplate messagingTemplate,
		Step workerStep
) {
	MessageChannelPartitionHandler handler = new MessageChannelPartitionHandler();
	handler.setStepName(workerStep.getName());
	handler.setGridSize(16);
	handler.setMessagingOperations(messagingTemplate);
	handler.setPollInterval(2_000);
	handler.setReplyChannel(replyChannel());
	return handler;
}
```

워커 쪽에는 `StepExecutionRequestHandler` 를 두어 `StepExecutionRequest` 메시지를 받아 step 을 실행시키고 `StepExecution` 결과를 회신한다. 모든 워커가 *같은 JobRepository* (보통 공유 DB)를 보아야 ExecutionContext 가 일관되게 저장된다.

## 5. Remote Chunking 구현 골격

Remote Chunking 은 master 가 reader 를 들고 있고, chunk 만 메시지로 던진다. `ChunkMessageChannelItemWriter` 가 master 측 writer 자리에 대신 들어가 chunk 를 송신 채널로 보낸다.

```java
@Configuration
@EnableBatchIntegration
public class RemoteChunkingMasterConfig {

	@Bean
	public IntegrationFlow outboundFlow(KafkaTemplate<String, ChunkRequest<?>> kafkaTemplate) {
		return IntegrationFlow.from("requests")
				.handle(Kafka.outboundChannelAdapter(kafkaTemplate).topic("chunk.requests"))
				.get();
	}

	@Bean
	public ChunkMessageChannelItemWriter<Sale> chunkWriter(
			MessagingTemplate messagingTemplate
	) {
		ChunkMessageChannelItemWriter<Sale> writer = new ChunkMessageChannelItemWriter<>();
		writer.setMessagingOperations(messagingTemplate);
		writer.setReplyChannel(replies());
		writer.setMaxWaitTimeouts(10);
		return writer;
	}

	@Bean
	public Step masterStep(JobRepository jobRepository, JdbcCursorItemReader<Sale> reader, ChunkMessageChannelItemWriter<Sale> chunkWriter, PlatformTransactionManager tm) {
		return new StepBuilder("masterStep", jobRepository)
				.<Sale, Sale>chunk(1_000, tm)
				.reader(reader)
				.writer(chunkWriter)
				.build();
	}
}
```

워커 측은 `ChunkProcessorChunkHandler` 를 만들고 inbound flow 를 거꾸로 깐다.

```java
@Bean
public ChunkProcessorChunkHandler<Sale> chunkProcessorChunkHandler(
		ItemProcessor<Sale, Sale> processor,
		ItemWriter<Sale> realWriter
) {
	SimpleChunkProcessor<Sale, Sale> p = new SimpleChunkProcessor<>(processor, realWriter);
	ChunkProcessorChunkHandler<Sale> h = new ChunkProcessorChunkHandler<>();
	h.setChunkProcessor(p);
	return h;
}

@Bean
public IntegrationFlow inboundFlow(ConsumerFactory<String, ChunkRequest<Sale>> cf) {
	return IntegrationFlow.from(Kafka.inboundChannelAdapter(cf, new ConsumerProperties("chunk.requests")))
			.handle("chunkProcessorChunkHandler", "handleChunk")
			.channel("replies")
			.get();
}
```

여기서 chunk 크기, partition (Kafka topic partition 수), concurrent consumer 수가 처리량을 결정한다. 토픽 파티션이 8개, 워커 인스턴스 3개 × concurrency 4 = 12 인 경우 active consumer 는 8 (파티션 수 한계) 로 묶이는 함정이 흔하다.

## 6. JobRepository · ExecutionContext 와 재시작

Spring Batch 5 의 JobRepository 스키마는 다음 핵심 테이블을 갖는다.

| 테이블 | 역할 |
|---|---|
| BATCH_JOB_INSTANCE | 동일 파라미터로 식별되는 잡 인스턴스 |
| BATCH_JOB_EXECUTION | 한 번의 실행 시도 |
| BATCH_STEP_EXECUTION | 각 step 의 실행 통계 |
| BATCH_STEP_EXECUTION_CONTEXT | step 에 저장한 임의 키/값 |
| BATCH_JOB_EXECUTION_CONTEXT | job 레벨 컨텍스트 |

Partitioning 의 핵심은 *각 워커 step 의 ExecutionContext 가 따로 영속화* 된다는 점이다. 잡이 중간에 죽었다 다시 시작되면 master 는 *완료된 partition 을 건너뛰고* 실패한 partition 만 재시도한다. 이 동작은 `Partitioner` 가 멱등한 키 (`partition-0`, `partition-1`, ...)를 반환해야 보장된다.

Remote Chunking 에서는 *master 의 reader 위치* 가 ExecutionContext 에 저장된다. master 가 죽으면 마지막 commit 된 chunk 직후부터 reader 가 재시작한다. 워커는 stateless 하므로 어느 인스턴스가 죽든 message broker 가 재배달한다 (Kafka 의 `enable.auto.commit=false` + manual ack 필수).

## 7. 트랜잭션 경계와 멱등성

chunk 트랜잭션은 *해당 chunk 의 write 만* 감싼다. read 는 cursor 이므로 트랜잭션 밖에서 진행되는 경우가 많다 (`JdbcCursorItemReader` 는 별도 connection). 따라서 다음을 반드시 챙긴다.

writer 멱등성: 동일 chunk 가 재처리되어도 결과가 동일해야 한다. `INSERT ... ON CONFLICT DO NOTHING` (PostgreSQL) 또는 `MERGE` (Oracle) 패턴, 또는 *외부 시스템 호출* 의 경우 idempotency key 헤더.

skip / retry policy: `faultTolerant().skipLimit(100).skip(DataIntegrityViolationException.class)` 같은 정책으로 *데이터 한 건의 오류* 가 잡 전체를 죽이지 않게 한다. retry 는 *멱등 실패* 에만 사용.

listener 의 트랜잭션 인지: `ItemWriteListener#beforeWrite` 는 chunk 트랜잭션 *안* 에서 실행된다. 외부 API 호출은 트랜잭션 외부로 빼야 long-running 트랜잭션을 피할 수 있다.

## 8. 운영 관점 — 메트릭과 SLA

Spring Batch 5 + Micrometer 는 다음 메트릭을 자동 노출한다.

| 메트릭 | 의미 |
|---|---|
| `spring.batch.job.active` | 실행 중 잡 수 |
| `spring.batch.job` | 잡 실행 시간 (timer) |
| `spring.batch.step` | step 실행 시간 |
| `spring.batch.item.read` | read 카운터 |
| `spring.batch.item.process` | process 카운터 |
| `spring.batch.chunk.write` | chunk write timer |

분산 환경에서는 *각 워커의 step 메트릭이 따로* 수집되므로 Prometheus 라벨로 partition name 까지 분리해 alert 를 걸면 skew 가 즉시 보인다.

실측 예시 (사내 데이터 기준): 1억 건 daily aggregation 잡, 단일 JVM 8 thread chunk(1000) → 약 220분. ID range partitioning gridSize=8, 8 Pod → 약 35분. Remote Chunking, 1 master + 4 worker × 4 concurrency = 16 consumer → 약 50분 (reader 가 master 단일이라 더 이상 떨어지지 않음). 처리(외부 API 호출) 가 무겁다면 Remote Chunking 이 역전한다.

## 9. 흔한 함정

partition skew: ID 가 sparse 하면 빈 range 가 생긴다. `count(*) GROUP BY mod(id, 8)` 로 사전 검증.

JobRepository contention: 모든 워커가 같은 DB 를 사용하므로 `BATCH_STEP_EXECUTION_CONTEXT` 의 update 가 핫스팟이 된다. 메시지 채널 polling 간격(`setPollInterval`)을 너무 짧게 두면 polling 자체가 DB 부하가 된다. 1~2초가 일반적.

ExecutionContext 직렬화 한계: 기본 직렬화는 1MB 안팎까지 안전하다. *partition 당 큰 데이터* 를 ExecutionContext 에 넣지 않고 *DB 키 + 외부 storage* 로 우회.

worker 인스턴스 cold start: Pod 스케일 아웃이 master 의 message dispatch 보다 느리면 첫 chunk batch 가 timeout. `MessageChannelPartitionHandler.setPollInterval` 과 `setStepLocator` 로 healthcheck 가 끝난 뒤 dispatch 하도록 조정.

## 참고

- Spring Batch Reference — Scaling and Parallel Processing: https://docs.spring.io/spring-batch/reference/scalability.html
- Spring Batch Integration 모듈: https://docs.spring.io/spring-batch/reference/spring-batch-integration.html
- Michael T. Minella, *Pro Spring Batch* (Apress)
- SpringOne talk — Partitioning vs Remote Chunking
- Kafka 컨슈머 그룹과 파티션 수 관계: https://kafka.apache.org/documentation/#consumerconfigs
