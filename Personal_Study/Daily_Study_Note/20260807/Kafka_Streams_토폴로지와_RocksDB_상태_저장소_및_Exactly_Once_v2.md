Notion 원본: https://www.notion.so/3b55a06fd6d38120a26dc5966c9f8b54

# Kafka Streams 토폴로지와 RocksDB 상태 저장소 및 Exactly-Once v2
> 2026-08-07 신규 주제 · 확장 대상: Kafka (복제·Exactly-Once 시맨틱 기학습)

## 학습 목표
- Streams DSL과 Processor API로 토폴로지를 구성하고 sub-topology 분할 기준과 태스크 병렬성 모델을 설명한다.
- RocksDB 기반 상태 저장소의 내부 구조와 changelog·standby replica를 통한 내결함성 메커니즘을 분석한다.
- Exactly-Once v2(KIP-447)의 트랜잭션 구조를 at_least_once와 비교하고 처리량 비용을 정량적으로 판단한다.
- Interactive Queries와 Spring Boot 통합, RocksDB 튜닝·모니터링 등 운영 관점의 설계 기준을 도출한다.

## 1. 토폴로지 구성: DSL, Processor API, sub-topology 분할

Kafka Streams 애플리케이션은 소스 토픽에서 레코드를 읽어 프로세서 노드의 방향 그래프(topology)를 통과시키고 싱크 토픽으로 내보낸다. Streams DSL은 `map`, `filter`, `groupByKey`, `join`, `windowedBy` 같은 함수형 연산자를 제공하며 내부적으로 Processor API로 변환된다. Processor API는 `Processor` 인터페이스를 직접 구현해 레코드 단위 로직, 상태 저장소 접근, `punctuate()` 기반 주기 실행을 세밀하게 제어한다. DSL로 표현하기 어려운 로직(복수 저장소 조합 중복 제거, 지연 플러시 등)은 `process()`로 DSL 파이프라인 중간에 끼워 넣는 하이브리드 방식이 실무 표준이다.

빌드된 토폴로지는 **sub-topology** 단위로 분할되며 분할 기준은 토픽을 통한 데이터 이동이다. `groupBy(newKeySelector)`처럼 키를 변경하는 연산 뒤에 집계나 조인이 오면 Streams가 자동으로 `-repartition` 토픽을 만들어 키 기준으로 재분배하는데, 이 경계가 곧 sub-topology 경계다. 반대로 `groupByKey`는 키가 바뀌지 않았다면 repartition을 생략하므로, 키를 재작성하는 `map` 대신 `mapValues`를 쓰는 것이 repartition 토픽 생성을 억제하는 대표적 최적화다.

병렬성은 파티션에 정확히 정렬된다. sub-topology마다 입력 파티션 수만큼 **StreamTask**가 생성되고(파티션 P, sub-topology S면 최대 P×S 태스크), 태스크는 `num.stream.threads`개의 **StreamThread**에 분배된다. 하나의 태스크는 항상 하나의 스레드에서만 실행되므로 파티션 내 순서가 보장되고 락이 필요 없다. 최대 병렬도는 입력 파티션 수가 상한이므로, 파티션 12개에 인스턴스 4대 × 스레드 4개 = 16 스레드를 투입하면 4개는 유휴가 된다. 스케일 설계는 "파티션 수 ≥ 총 스레드 수"가 기준이다.

```java
StreamsBuilder builder = new StreamsBuilder();
KStream<String, Order> orders = builder.stream("orders",
		Consumed.with(Serdes.String(), orderSerde));

KTable<String, Long> orderCountByUser = orders
		.selectKey((k, v) -> v.getUserId())        // 키 변경 → repartition 경계
		.groupByKey(Grouped.with(Serdes.String(), orderSerde))
		.count(Materialized.as("order-count-store"));

orderCountByUser.toStream().to("order-counts",
		Produced.with(Serdes.String(), Serdes.Long()));

Topology topology = builder.build();
System.out.println(topology.describe());           // sub-topology 구조 확인
```

`topology.describe()` 출력에서 `Sub-topology: 0`, `Sub-topology: 1`과 repartition 토픽 이름을 확인하는 습관이 토폴로지 비용을 통제하는 첫걸음이다.

## 2. KStream, KTable, GlobalKTable과 스트림-테이블 이중성

세 추상화의 의미론 차이가 조인·집계 설계를 결정한다.

| 추상화 | 의미론 | 파티셔닝 | 대표 용도 |
|---|---|---|---|
| KStream | 각 레코드가 독립 이벤트(append) | 입력 토픽 파티션 그대로 | 이벤트 처리, 윈도우 집계 |
| KTable | 키별 최신 값(update, upsert) | 파티션별 부분 뷰 | 최신 상태 조회, 스트림-테이블 조인 |
| GlobalKTable | 키별 최신 값, 전 파티션 복제 | 각 인스턴스가 전체 복사본 보유 | 소규모 참조 데이터 조인(키 불일치 허용) |

**스트림-테이블 이중성**은 설계의 핵심 관점이다. 테이블은 변경 이벤트 스트림을 키별로 누적한 스냅샷이고, 스트림은 테이블 갱신을 시간순으로 나열한 changelog다. 이 이중성 덕분에 KTable 상태를 compacted **changelog 토픽**으로 브로커에 백업하고 장애 시 재생해 복원할 수 있다. `aggregate`, `count`, `reduce` 등 상태 연산은 `<application.id>-<store-name>-changelog` 토픽을 자동 생성한다.

KTable 조인은 양쪽이 co-partitioned(파티션 수·파티셔너 동일)여야 한다. GlobalKTable은 전체 데이터를 각 인스턴스에 복제하므로 이 제약이 없고 조인 키를 임의 필드로 지정할 수 있지만, 전체 데이터가 인스턴스마다 올라가므로 수 GB 이하 참조 데이터(코드 테이블, 상품 마스터)에만 적합하다. 갱신은 별도 GlobalStreamThread가 처리해 조인 시점에 약간의 시차(eventual consistency)가 있다는 점도 트레이드오프다.

## 3. 상태 저장소: RocksDB 구조와 내결함성

기본 persistent store는 임베디드 **RocksDB**다. LSM-tree 구조라 쓰기는 memtable(write buffer)에 먼저 들어가고, 가득 차면 L0 SST 파일로 플러시되며, 백그라운드 컴팩션이 레벨 간 SST를 병합한다. Streams 워크로드 관점의 영향은 다음과 같다.

- **쓰기 우세 워크로드에 유리**: 집계 갱신은 대부분 최신 키 재기록이라 memtable에서 흡수되고 순차 I/O 중심이라 쓰기 처리량이 높다.
- **읽기 증폭**: 조회는 memtable → L0 다수 파일 → 하위 레벨 순으로 탐색하므로 컴팩션이 지연되면 조회 지연이 늘어난다. 윈도우 조인처럼 조회가 많은 토폴로지에서 두드러진다.
- **플러시·컴팩션 지연 스파이크**: 커밋 주기마다 다수 store가 동시에 플러시되면 디스크 경합으로 commit latency가 튄다. 블록 캐시 공유(8절)로 완화한다.
- **공간 증폭**: 컴팩션 전까지 구버전 레코드가 SST에 남으므로 `state.dir` 용량은 논리 데이터의 2~3배 여유를 둔다.

in-memory store(`Stores.inMemoryKeyValueStore`)는 조회 지연이 마이크로초 수준으로 안정적이지만 크기가 힙 한도에 묶이고 재시작 시 항상 changelog 전체 재생이 필요하다. "상태가 수백 MB 이하 + 조회 지연 민감 + 복구 시간 허용"일 때만 선택하고, 그 외에는 RocksDB가 기본값이다.

내결함성은 changelog 토픽이 담당한다. 로컬 store의 모든 쓰기는 changelog에도 기록되고(EOS면 같은 트랜잭션 포함), 태스크가 다른 인스턴스로 이동하면 changelog를 재생해 재구축한다. 문제는 재생 시간인데, 100GB 상태를 초당 100MB로 재생해도 약 17분간 해당 파티션 처리가 정지한다. **standby replica**(`num.standby.replicas=1` 이상)는 다른 인스턴스가 changelog를 상시 추적해 핫 카피를 유지하므로 페일오버가 수 초 수준으로 줄어든다. 비용은 추가 디스크·네트워크와 changelog 소비 부하이며, 상태가 큰 프로덕션에서는 1이 통상적 권장값이다.

## 4. 윈도우 연산: 종류, grace period, suppress

| 윈도우 | 정의 | 특징 |
|---|---|---|
| Tumbling | 고정 크기, 겹침 없음 (`ofSizeWithNoGrace(5m)`) | 레코드가 정확히 1개 윈도우에 속함 |
| Hopping | 고정 크기 + advance 간격 | 겹침 발생, 레코드가 size/advance개 윈도우에 중복 집계 |
| Sliding | 레코드 시간 기준 상대 윈도우 (`ofTimeDifference`) | 조인·집계에서 실제 이벤트 간격 기준, 불필요한 빈 윈도우 없음 |
| Session | 활동 간격(inactivity gap) 기반 가변 크기 | 세션 병합 발생, 사용자 행동 분석에 적합 |

이벤트 타임 기반 처리에서 지연 레코드(out-of-order)는 필연이다. **grace period**는 윈도우 종료 후에도 지연 레코드를 받아 결과를 갱신하는 유예 시간이다. grace 안에 도착한 지연 레코드는 기존 윈도우 집계를 갱신해 다시 방출하고, grace를 넘긴 레코드는 폐기되며 `dropped-records-total` 메트릭으로 집계된다. 3.x부터 DSL이 `ofSizeWithNoGrace` / `ofSizeAndGrace`로 명시를 강제하는 이유는, 과거 기본값 24시간이 의도치 않은 상태 보존과 늦은 결과 방출을 일으켰기 때문이다.

윈도우 집계는 기본적으로 갱신될 때마다 중간 결과를 내보낸다(연속 갱신 스트림). 다운스트림이 "윈도우당 최종 결과 1건"을 원하면 `suppress(Suppressed.untilWindowCloses(...))`를 쓴다.

```java
KTable<Windowed<String>, Long> counts = orders
		.groupByKey()
		.windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofMinutes(1)))
		.count()
		.suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()));
```

suppress는 윈도우가 닫힐 때까지(윈도우 끝 + grace) 결과를 버퍼에 잡아두므로, 버퍼 메모리와 방출 지연이 비용이다. 또한 방출 트리거가 stream time 전진에 의존하므로 유입이 멈춘 파티션에서는 마지막 윈도우가 방출되지 않는 특성을 운영에서 반드시 인지해야 한다.

## 5. Exactly-Once v2: 트랜잭션 구조와 KIP-447

`processing.guarantee=exactly_once_v2`(3.0+ 기본 명칭, KIP-732로 구 `exactly_once`·`exactly_once_beta`는 제거 수순)는 "읽기-처리-쓰기"를 원자적으로 만든다. 핵심은 **출력 레코드 전송, 상태 changelog 기록, 입력 consumer offset 커밋을 하나의 프로듀서 트랜잭션으로 묶는 것**이다. 커밋 주기(`commit.interval.ms`, EOS 기본 100ms)마다 다음이 반복된다.

1. 트랜잭셔널 프로듀서가 출력·changelog 레코드를 전송(트랜잭션에 포함).
2. `sendOffsetsToTransaction()`으로 입력 오프셋을 `__consumer_offsets`에 트랜잭션의 일부로 기록.
3. `commitTransaction()` — 트랜잭션 코디네이터가 2단계 커밋으로 마커를 기록.

장애 시 트랜잭션이 abort되면 출력·changelog·오프셋이 모두 무효화되고, 다운스트림 컨슈머는 `isolation.level=read_committed`로 커밋된 레코드만 읽으므로 결과가 "정확히 한 번 반영"된다. 상태 저장소도 changelog가 트랜잭션에 포함되므로 재생 시 일관성이 유지된다(비정상 종료 시 로컬 RocksDB는 오염 가능성이 있어 체크포인트 없이 changelog에서 재구축한다).

**KIP-447 이전(EOS v1)**: 좀비 프로듀서 펜싱을 위해 `transactional.id`가 태스크에 1:1로 묶여야 했고, 결과적으로 **태스크당 프로듀서 1개**가 필요했다. 태스크 수백 개면 프로듀서 수백 개 → 브로커 커넥션·메모리 부담이 커서 대규모 배포의 실질적 제약이었다. **KIP-447 이후(EOS v2)**: 컨슈머 그룹 메타데이터(generation)를 `sendOffsetsToTransaction(offsets, groupMetadata)`에 함께 보내 브로커가 구세대 멤버의 커밋을 거부하는 방식으로 펜싱을 옮겼다. 덕분에 **StreamThread당 프로듀서 1개**로 축소되어 리소스가 태스크 수가 아닌 스레드 수에 비례한다. 이것이 v1 대비 v2의 본질적 개선이다.

비용은 처리량과 지연이다. at_least_once(기본 커밋 30초)는 배치를 크게 묶지만, EOS는 100ms마다 트랜잭션 커밋(마커 기록, 코디네이터 왕복)이 발생한다. 공개 벤치마크와 실무 보고에서 EOS 오버헤드는 워크로드에 따라 대략 처리량 10~30% 감소, 종단 지연은 커밋 주기+트랜잭션 완료만큼 증가로 나타난다. `commit.interval.ms`를 1000ms 정도로 올리면 처리량 손실을 한 자릿수 %대로 줄일 수 있으나 재처리 윈도우와 종단 지연이 그만큼 늘어난다. 멱등 소비가 가능한 파이프라인(예: 키 기반 upsert 싱크)은 at_least_once + 멱등 싱크가 더 경제적일 수 있다.

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "order-aggregator");
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 1);
props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);   // 내부 토픽 복제 계수
```

EOS는 내부 토픽 복제 계수 3, `min.insync.replicas=2` 등 브로커 측 내구성 설정과 함께 써야 의미가 완성된다.

## 6. 리밸런싱: cooperative sticky, warmup replica, probing rebalance

Streams는 자체 파티션 할당자(StreamsPartitionAssignor)를 쓰며 2.4부터 **cooperative 프로토콜**을 채택했다. eager 방식은 리밸런스마다 전 멤버가 모든 파티션을 반납해 stop-the-world가 발생했지만, cooperative 방식은 이동이 필요한 파티션만 두 번의 리밸런스에 걸쳐 점진 이동하므로 나머지 태스크는 처리를 계속한다.

상태ful 태스크 이동의 진짜 비용은 파티션 소유권이 아니라 **상태 복원**이다. KIP-441이 도입한 해법이 **warmup replica**와 **probing rebalance**다. 새 인스턴스가 합류하면 즉시 태스크를 넘기지 않고, 목표 인스턴스에 warmup replica(standby와 유사하게 changelog를 따라잡는 임시 복제본)를 만든다. 이후 `probing.rebalance.interval.ms`(기본 10분)마다 재할당을 시도해, warmup의 랙이 `acceptable.recovery.lag`(기본 10,000 레코드) 이하로 따라잡힌 시점에 활성 태스크를 이동한다. 동시 warmup 수는 `max.warmup.replicas`(기본 2)로 제한한다. 결과적으로 스케일 아웃 시 "복원이 끝난 뒤에만 이동"이 보장되어 가용성 저하가 없다. 트레이드오프는 이동 완료까지의 시간(수십 분 가능)과 warmup 유지 비용이며, 급한 이전이 필요하면 `max.warmup.replicas`를 올리고 `probing.rebalance.interval.ms`를 줄인다.

`group.instance.id`(static membership)를 함께 설정하면 롤링 재시작 시 `session.timeout.ms` 내 재합류에 대해 리밸런스 자체를 생략하므로, 쿠버네티스 환경의 재배포에서 불필요한 상태 이동을 크게 줄인다.

## 7. Interactive Queries와 Spring Boot 통합

Interactive Queries(IQ)는 로컬 상태 저장소를 읽기 전용으로 조회해, 별도 DB 없이 최신 집계 상태를 REST API로 노출하는 기능이다. 상태는 인스턴스별로 분산되어 있으므로, 다중 인스턴스 배포에서는 `application.server`(host:port)를 설정하고 `queryMetadataForKey()`로 키를 가진 인스턴스를 찾아 필요 시 원격 인스턴스로 포워딩하는 라우팅 계층을 직접 구현해야 한다.

spring-kafka는 `@EnableKafkaStreams`로 `StreamsBuilderFactoryBean`을 등록해 `KafkaStreams` 라이프사이클(시작·종료·상태 리스너)을 컨테이너와 통합한다.

```java
@Configuration
@EnableKafkaStreams
public class StreamsTopologyConfig {

	@Bean
	public KTable<String, Long> orderCountTable(StreamsBuilder builder) {
		return builder.stream("orders", Consumed.with(Serdes.String(), Serdes.String()))
				.groupByKey()
				.count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-count-store")
						.withKeySerde(Serdes.String())
						.withValueSerde(Serdes.Long()));
	}
}

@RestController
@RequiredArgsConstructor
public class OrderCountController {

	private final StreamsBuilderFactoryBean factoryBean;

	@GetMapping("/counts/{userId}")
	public ResponseEntity<Long> getCount(@PathVariable String userId) {
		KafkaStreams kafkaStreams = factoryBean.getKafkaStreams();
		if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		ReadOnlyKeyValueStore<String, Long> store = kafkaStreams.store(
				StoreQueryParameters.fromNameAndType("order-count-store",
						QueryableStoreTypes.keyValueStore()));
		Long count = store.get(userId);
		return count == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(count);
	}
}
```

리밸런스 중에는 store가 일시적으로 조회 불가(`InvalidStateStoreException`)가 될 수 있으므로 재시도 또는 `StoreQueryParameters.enableStaleStores()`(standby 조회 허용, 최신성 완화)로 대응한다. IQ는 "조회가 단순 키 기반이고 최신성 요구가 초 단위"일 때 외부 DB 싱크를 대체하는 선택지다.

## 8. 운영: state.dir, RocksDB 튜닝, 메트릭, Flink와의 선택

**state.dir**: 기본 `/tmp/kafka-streams`는 재부팅 시 삭제되어 매번 전체 복원을 유발하므로 반드시 영속 볼륨으로 변경한다. 쿠버네티스에서는 StatefulSet + PVC로 인스턴스-볼륨을 고정해 재스케줄 후에도 로컬 상태를 재사용한다. 디렉터리 구조는 `<state.dir>/<application.id>/<task_id>/rocksdb/<store>`이며, `.checkpoint` 파일이 changelog 오프셋과 로컬 상태의 동기 지점을 기록한다.

**RocksDBConfigSetter**: 기본값은 store마다 독립 블록 캐시(50MB)·write buffer를 잡아 store가 많으면 오프힙 메모리가 폭증한다. 프로덕션에서는 전역 공유 캐시와 write buffer manager로 총량을 캡핑한다.

```java
public class BoundedMemoryRocksDbConfig implements RocksDBConfigSetter {

	private static final Cache SHARED_CACHE = new LRUCache(512 * 1024 * 1024L);
	private static final WriteBufferManager WRITE_BUFFER_MANAGER =
			new WriteBufferManager(128 * 1024 * 1024L, SHARED_CACHE);

	@Override
	public void setConfig(String storeName, Options options, Map<String, Object> configs) {
		BlockBasedTableConfig tableConfig = (BlockBasedTableConfig) options.tableFormatConfig();
		tableConfig.setBlockCache(SHARED_CACHE);
		tableConfig.setCacheIndexAndFilterBlocks(true);
		options.setWriteBufferManager(WRITE_BUFFER_MANAGER);
		options.setMaxWriteBufferNumber(3);
		options.setWriteBufferSize(32 * 1024 * 1024L);
		options.setCompactionStyle(CompactionStyle.LEVEL);
		options.setTableFormatConfig(tableConfig);
	}

	@Override
	public void close(String storeName, Options options) {
		// 공유 캐시는 close 하지 않는다
	}
}
```

**모니터링**: 최소 감시 대상은 다음과 같다. `kafka-consumer` 레벨의 records-lag(파티션 랙), Streams 레벨의 `commit-latency-avg/max`(트랜잭션·플러시 병목 감지), `process-rate`·`process-latency-avg`, `punctuate-latency-avg`(punctuator 지연이 스레드를 점유하면 전체 파티션 처리가 밀린다), `task-level dropped-records-total`(grace 초과 폐기), state store 레벨 `restore` 관련 메트릭과 RocksDB 메트릭(`block-cache-hit-ratio` 등, `metrics.recording.level=DEBUG` 필요). 리밸런스 빈도와 `failed-stream-threads`도 알람 대상이다.

**Kafka Streams vs Flink**: Streams는 라이브러리라서 별도 클러스터 없이 애플리케이션에 내장되고 배포·운영이 단순하며 Kafka에 강결합된 파이프라인에 최적이다. Flink는 전용 클러스터(JobManager/TaskManager)와 체크포인트 기반 스냅샷을 갖고, Kafka 외 소스·싱크, 대규모 상태(수 TB), 정교한 이벤트 타임 워터마크, 배치-스트림 통합이 필요할 때 우위다. 판단 기준을 요약하면 다음과 같다.

| 기준 | Kafka Streams | Flink |
|---|---|---|
| 배포 형태 | 앱 내장 라이브러리 | 전용 클러스터(또는 K8s Operator) |
| 소스/싱크 | Kafka 중심 | Kafka 외 다수 커넥터 |
| 상태 규모 | 인스턴스 로컬 디스크 한도 내 | RocksDB + 분산 체크포인트로 초대형 |
| 정합성 | EOS v2(Kafka 트랜잭션) | 체크포인트 기반 exactly-once |
| 운영 부담 | 낮음(앱 운영과 동일) | 높음(클러스터 운영 필요) |

"입력도 출력도 Kafka이고 팀이 별도 스트리밍 클러스터를 운영할 여력이 없다"면 Streams, "다양한 소스, 초대형 상태, 복잡한 시간 의미론"이면 Flink가 출발점이다.

## 참고
- Kafka Streams Architecture (Apache Kafka 공식 문서): https://kafka.apache.org/documentation/streams/architecture
- Kafka Streams Developer Guide - Processor API / DSL: https://kafka.apache.org/documentation/streams/developer-guide/
- KIP-129: Streams Exactly-Once Semantics: https://cwiki.apache.org/confluence/display/KAFKA/KIP-129%3A+Streams+Exactly-Once+Semantics
- KIP-447: Producer scalability for exactly once semantics: https://cwiki.apache.org/confluence/display/KAFKA/KIP-447%3A+Producer+scalability+for+exactly+once+semantics
- KIP-441: Smooth Scaling Out for Kafka Streams: https://cwiki.apache.org/confluence/display/KAFKA/KIP-441%3A+Smooth+Scaling+Out+for+Kafka+Streams
- Kafka Streams Memory Management (RocksDB 튜닝): https://kafka.apache.org/documentation/streams/developer-guide/memory-mgmt.html
- Kafka Streams Interactive Queries: https://kafka.apache.org/documentation/streams/developer-guide/interactive-queries.html
- RocksDB Wiki - Leveled Compaction: https://github.com/facebook/rocksdb/wiki/Leveled-Compaction
