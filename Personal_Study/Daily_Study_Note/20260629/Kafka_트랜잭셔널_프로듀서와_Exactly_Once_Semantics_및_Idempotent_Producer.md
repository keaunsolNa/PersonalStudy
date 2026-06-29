Notion 원본: https://app.notion.com/p/38e5a06fd6d381cba010ffc8f448d9a3

# Kafka 트랜잭셔널 프로듀서와 Exactly-Once Semantics 및 Idempotent Producer

> 2026-06-29 신규 주제 · 확장 대상: Backend

## 학습 목표

- 멱등 프로듀서가 PID 와 시퀀스 번호로 중복·재정렬을 막는 메커니즘을 설명한다
- 트랜잭셔널 프로듀서의 `transactional.id` 와 2PC 기반 커밋 흐름을 추적한다
- consume-process-produce 패턴에서 EOS 를 구성하고 read-committed 격리를 적용한다
- EOS 의 처리량 비용을 측정해 at-least-once 와의 선택 기준을 세운다

## 1. 전달 보장의 세 단계

Kafka 의 전달 의미는 at-most-once(유실 허용), at-least-once(중복 허용), exactly-once(정확히 한 번)로 나뀄다. 기본 프로듀서는 ack 후 응답이 유실되면 재전송하므로 at-least-once 이며, 이 재전송이 브로커에 중복 레코드를 남긴다. Exactly-Once Semantics(EOS)는 멱등 프로듀서와 트랜잭션 두 축으로 이 중복을 제거한다.

```properties
# at-least-once (기본) — 응답 유실 시 재전송하며 중복 발생 가능
acks=all
retries=2147483647
enable.idempotence=false
```

`acks=all` 은 ISR(In-Sync Replica) 전체가 기록을 확인해야 ack 하므로 유실은 막지만, 중복은 별도 메커니즘 없이는 막지 못한다.

## 2. 멱등 프로듀서 — PID 와 시퀀스 번호

`enable.idempotence=true` 를 켜면 프로듀서는 브로커로부터 Producer ID(PID)를 발급받고, 각 파티션마다 단조 증가하는 시퀀스 번호를 레코드에 부여한다. 브로커는 `(PID, partition)` 별로 마지막 시퀀스 번호를 기억해 중복과 순서 역전을 판단한다.

```properties
enable.idempotence=true
# 멱등성 활성화 시 아래는 자동으로 강제된다
acks=all
max.in.flight.requests.per.connection=5  # 5 이하여야 순서 보장
retries > 0
```

브로커의 판정 규칙은 단순하다. 도착한 시퀀스가 기대값과 같으면 정상 기록, 기대값보다 작으면 중복으로 간주해 *조용히 폐기*(이미 기록됨), 기대값보다 크면 `OutOfOrderSequenceException` 으로 거부한다. 이 덕분에 재전송이 일어나도 같은 시퀀스 레코드는 한 번만 로그에 남는다. 단, 멱등성은 *단일 프로듀서 세션, 단일 파티션* 범위에서만 보장된다. 프로듀서가 재시작하면 새 PID 를 받으므로 세션 경계를 넘는 중복은 막지 못한다 — 이 공백을 메우는 것이 트랜잭션이다.

## 3. 트랜잭셔널 프로듀서와 transactional.id

`transactional.id` 를 설정하면 프로듀서 재시작을 넘어 PID 가 안정적으로 유지된다. 트랜잭션 코디네이터(브로커)가 `transactional.id → PID + epoch` 매핑을 유지하며, 같은 id 로 새 프로듀서가 등록되면 epoch 를 올려 이전 좀비 프로듀서를 펜싱(fencing)한다.

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-processor-1");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
producer.initTransactions(); // epoch 증가, 이전 좀비 펜싱

try {
	producer.beginTransaction();
	producer.send(new ProducerRecord<>("orders", orderId, payload));
	producer.send(new ProducerRecord<>("audit", orderId, log));
	producer.commitTransaction(); // 두 토픽에 원자적 커밋
} catch (ProducerFencedException e) {
	producer.close(); // 펜싱당함 — 좀비, 즉시 종료
} catch (KafkaException e) {
	producer.abortTransaction();
}
```

`commitTransaction()` 은 2PC 로 동작한다. 코디네이터가 트랜잭션 로그에 PREPARE_COMMIT 을 쓰고, 관련 파티션마다 *트랜잭션 마커(control batch)* 를 기록한 뒤, COMPLETE_COMMIT 으로 마무리한다. 이 마커가 컨슈머에게 "여기까지가 커밋된 트랜잭션"임을 알리는 신호다.

## 4. consume-process-produce 와 오프셋의 원자적 커밋

EOS 가 가장 빛나는 곳은 "토픽에서 읽어 처리하고 다른 토픽에 쓰는" 스트림 파이프라인이다. 여기서 핵심은 *소비 오프셋 커밋도 같은 트랜잭션에 포함* 하는 것이다. 그래야 출력 기록과 입력 진행이 함께 커밋되거나 함께 롤백된다.

```java
producer.beginTransaction();
for (ConsumerRecord<String, String> record : records) {
	String result = process(record.value());
	producer.send(new ProducerRecord<>("output", record.key(), result));
}
// 컨슈머 오프셋을 프로듀서 트랜잭션에 포함시켜 원자적 커밋
Map<TopicPartition, OffsetAndMetadata> offsets = currentOffsets(records);
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction();
```

`sendOffsetsToTransaction` 은 오프셋 커밋을 `__consumer_offsets` 토픽 기록으로 만들어 트랜잭션에 합류시킨다. 따라서 출력 메시지가 커밋되지 않으면 오프셋도 전진하지 않아, 재시작 시 같은 입력을 다시 처리하지만 출력은 한 번만 보이는 EOS 가 성립한다.

## 5. read-committed — 컨슈머 측 격리

프로듀서가 아무리 트랜잭션을 써도 컨슈머가 `read_uncommitted`(기본값)면 abort 된 메시지까지 읽어버린다. EOS 를 완성하려면 컨슈머를 `read_committed` 로 설정해야 한다.

```properties
isolation.level=read_committed
```

`read_committed` 컨슈머는 LSO(Last Stable Offset)까지만 읽는다. LSO 는 아직 미결(in-flight)인 트랜잭션의 시작 지점 직전이다. abort 마커가 붙은 메시지는 컨슈머가 건너뛴다. 부작용으로, 장시간 미완료 트랜잭션이 있으면 LSO 가 전진하지 못해 컨슈머 지연(consumer lag)이 늘 수 있다. 그래서 `transaction.timeout.ms`(기본 60초)로 트랜잭션 최대 수명을 제한한다.

## 6. 처리량 비용 측정

EOS 는 공짜가 아니다. 추가 RPC(트랜잭션 시작/커밋), 마커 기록, 코디네이터 왕복이 처리량을 깍는다. 대략적인 비교는 다음과 같다.

| 구성 | 상대 처리량 | 추가 지연 요인 |
|---|---|---|
| at-least-once (`acks=all`) | 100% (기준) | ISR 복제 대기 |
| 멱등 프로듀서만 | 약 95~98% | PID/시퀀스 오버헤드 미미 |
| 트랜잭션 (배치 큼, 1000+ msg/tx) | 약 90~95% | 커밋당 코디네이터 왕복 분산 |
| 트랜잭션 (배치 작음, 1 msg/tx) | 약 30~50% | 커밋 RPC 가 메시지마다 발생 |

가장 중요한 실측 교훈은 *트랜잭션당 메시지 수* 가 처리량을 좌우한다는 점이다. 메시지 하나마다 커밋하면 커밋 RPC 가 병목이 되어 처리량이 절반 이하로 떨어진다. 따라서 EOS 파이프라인은 적절한 배치 크기(예: poll 한 번에 읽은 수백~수천 건을 한 트랜잭션으로)를 잡아 커밋 비용을 분산해야 한다.

## 7. 선택 기준과 한계

EOS 는 결제·재고처럼 중복이 곧 비용인 도메인에서 가치가 크다. 반대로 메트릭 집계나 로그 적재처럼 다운스트림이 자체 멱등성을 갖추거나 약간의 중복이 무해한 경우, at-least-once 에 *컨슈머 측 멱등 처리*(예: 처리 키 dedup 테이블)를 더하는 편이 운영이 단순하고 처리량 손실이 없다. 또한 Kafka EOS 는 Kafka 토픽 사이에서만 원자성을 보장한다 — DB 쓰기와 Kafka 쓰기를 함께 묶으려면 별도 트랜잭셔널 아웃박스 패턴이 필요하며, Kafka 트랜잭션만으로는 외부 시스템과의 원자성을 얻을 수 없다. 이 경계를 혼동하면 "EOS 를 켰는데 왜 DB 와 메시지가 어긋나나"라는 흔한 오해에 빠진다.

## 8. Kafka Streams 와 processing.guarantee

직접 트랜잭션 API 를 다루는 대신 Kafka Streams 를 쓰면 EOS 가 설정 한 줄로 추상화된다. Streams 는 내부적으로 상태 저장소(state store)의 변경, 출력 토픽 쓰기, 소비 오프셋을 하나의 트랜잭션으로 묶는다.

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "word-count");
// EOS v2 — transactional.id 를 인스턴스가 아닌 파티션 단위로 공유해 확장성 개선
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

StreamsBuilder builder = new StreamsBuilder();
builder.<String, String>stream("input")
	.flatMapValues(v -> Arrays.asList(v.toLowerCase().split(" ")))
	.groupBy((k, word) -> word)
	.count()                       // 상태 저장소 갱신
	.toStream()
	.to("output");                 // 출력 — 모두 같은 트랜잭션
```

`exactly_once_v2`(KIP-447)는 구버전 EOS 의 핵심 한계를 해결했다. 구버전은 입력 파티션마다 별도 프로듀서/`transactional.id` 가 필요해 파티션이 수천 개면 프로듀서도 수천 개가 됐다. v2 는 하나의 프로듀서가 여러 입력 파티션을 다루도록 펜싱 모델을 개선해, 메모리·커넥션 비용을 크게 낮춘다. 따라서 신규 프로젝트는 v2 를 기본으로 쓴다.

## 9. 운영 — 좀비 펜싱과 타임아웃 튜닝

트랜잭션의 정확성을 떠받치는 것은 좀비 펜싱이다. 네트워크 분할로 옷 인스턴스가 살아 있다고 착각하며 계속 쓰려 할 때, 같은 `transactional.id` 로 새로 `initTransactions()` 한 인스턴스가 epoch 를 올려 옷 인스턴스를 `ProducerFencedException` 으로 차단한다. 이로써 중복 쓰기를 막는다. 운영에서 조정해야 하는 타임아웃은 다음과 같다.

| 설정 | 기본값 | 영향 |
|---|---|---|
| `transaction.timeout.ms` | 60,000 | 초과 시 트랜잭션 강제 abort, LSO 전진 |
| `transaction.max.timeout.ms` (브로커) | 900,000 | 프로듀서가 요청 가능한 상한 |
| `transactional.id.expiration.ms` | 604,800,000 | 미사용 id 메타데이터 만료 |

가장 흔한 운영 사고는 컨슈머 측 `read_committed` 인데 한 프로듀서가 트랜잭션을 열어둔 채 멈춰, LSO 가 전진하지 못해 *모든* read_committed 컨슈머가 그 파티션에서 멈추는 것이다. 증상은 "프로듀서는 멀썰한데 컨슈머 lag 만 무한히 쌓임"이다. 진단은 `kafka-transactions.sh --list` 로 장기 미완료 트랜잭션을 찾는 것이고, 처방은 `transaction.timeout.ms` 를 처리 SLA 에 맞게 낮춰 멈춘 트랜잭션이 자동 abort 되게 하는 것이다. 다만 너무 낮추면 정상적인 긴 배치가 도중에 abort 되므로, "최대 정상 트랜잭션 소요 시간 + 여유"로 잡는 균형이 필요하다.

## 참고

- Apache Kafka Documentation — Idempotent and Transactional Producer
- Confluent — Exactly-Once Semantics Are Possible: Here's How Kafka Does It
- KIP-98: Exactly Once Delivery and Transactional Messaging
- KIP-447: Producer Scalability for Exactly Once Semantics
