Notion 원본: https://app.notion.com/p/3db5a06fd6d381c49be9d46e1e1f0685?pvs=204

# Kafka Exactly-Once Semantics와 멱등 프로듀서 및 트랜잭션 코디네이터

> 2026-09-14 신규 주제 · 확장 대상: Kafka 프로듀서·컨슈머 기본

## 학습 목표

- 세 가지 전달 보장을 메시지 라이프사이클 구간별로 구분하고 Kafka EOS 의 보장 경계를 특정한다
- 멱등 프로듀서의 PID·에포크·시퀀스 검증 규칙과 강제 설정 제약을 브로커 동작으로 추적한다
- 트랜잭션 코디네이터의 2PC 유사 프로토콜과 컨트롤 레코드·LSO 를 연결해 read_committed 의 지연 원인을 계산한다
- consume-transform-produce 파이프라인을 Java 와 Spring Kafka 로 구현하고 DB 이중 쓰기 문제를 Outbox 와 비교한다

---

## 1. 세 가지 전달 보장과 Kafka EOS 의 경계

전달 보장은 "메시지가 몇 번 처리되는가"가 아니라 "어느 구간에서 재시도와 커밋이 일어나는가"로 정의된다. At-most-once 는 프로듀서가 재시도하지 않고 컨슈머가 처리 전에 오프셋을 커밋하는 구성이다. 장애가 나면 메시지는 사라지지만 중복은 없다. At-least-once 는 프로듀서가 ack 를 못 받으면 재전송하고 컨슈머는 처리 완료 후 오프셋을 커밋한다. 손실은 없지만 ack 유실과 커밋 직전 크래시 두 지점에서 중복이 생긴다. Exactly-once 는 이 두 지점을 각각 멱등 프로듀서와 트랜잭션으로 막는다.

여기서 가장 흔한 오해는 Kafka EOS 가 "메시지가 세상에 단 한 번만 영향을 준다"는 뜻이라고 믿는 것이다. Kafka 가 원자적으로 보장하는 대상은 **Kafka 토픽 파티션에 대한 쓰기와 `__consumer_offsets` 에 대한 오프셋 커밋의 묶음**뿐이다. 컨슈머가 메시지를 받아 결제 API 를 호출하거나 SMTP 로 메일을 보냈다면, 그 부수효과는 트랜잭션의 참여자가 아니므로 abort 되어도 되돌아가지 않는다. 즉 Kafka 의 EOS 는 "Kafka 로 읽고 Kafka 로 쓰는 스트림 처리 경계 안에서의 원자성"이며, 경계를 벗어나는 순간 애플리케이션이 별도의 멱등성을 책임져야 한다.

| 보장 | 프로듀서 재시도 | 오프셋 커밋 시점 | 결과 | 대표 설정 |
|---|---|---|---|---|
| At-most-once | 없음 (`retries=0`) | 처리 전 커밋 | 손실 가능, 중복 없음 | `acks=0` 또는 `acks=1` |
| At-least-once | 있음 | 처리 후 커밋 | 손실 없음, 중복 가능 | `acks=all`, `enable.idempotence=false` |
| Exactly-once (Kafka 경계 내) | 멱등 + 트랜잭션 | 트랜잭션 내부 커밋 | 손실·중복 없음 | `transactional.id` + `read_committed` |

실무 판단 기준은 단순하다. 다운스트림 싱크가 upsert 가능한 키를 가지고 있다면 at-least-once + 애플리케이션 멱등 키가 훨씬 싸다. 싱크가 append-only 집계(카운터 증가, 잔액 가감)라서 중복이 곳 오답이고, 그 싱크가 Kafka 토픽이라면 EOS 가 정답이다.

## 2. 멱등 프로듀서: PID·에포크·시퀀스 번호

`enable.idempotence=true` 로 켜면 프로듀서는 기동 시 `InitProducerId` 요청을 보내 브로커로부터 **PID(Producer ID)** 와 **에포크(epoch)** 를 발급받는다. 이후 모든 produce 요청의 레코드 배치 헤더에는 `(PID, epoch, 파티션별 시퀀스 번호)` 가 실린다. 브로커는 파티션 단위로 각 PID 의 마지막 시퀀스 5개를 로그의 프로듀서 상태 스냅샷에 유지하면서 다음 규칙으로 검증한다.

- `seq == lastSeq + 1` → 정상 append
- `seq <= lastSeq` (최근 5개 윈도우 내) → 중복으로 판정, append 하지 않고 원래 오프셋을 담아 성공 응답. 재전송이 투명하게 흡수된다
- `seq > lastSeq + 1` → 중간 배치 유실. `OutOfOrderSequenceException` 을 던진다
- `epoch` 가 브로커가 아는 값보다 작음 → `ProducerFencedException` 으로 거부

KIP-679 에 따라 Kafka 3.0 부터 `enable.idempotence` 의 기본값이 `true` 이고, 이때 다음 값들이 사실상 강제된다. 충돌하는 값을 명시하면 `ConfigException` 으로 기동 자체가 실패한다.

```properties
enable.idempotence=true
acks=all
retries=2147483647
max.in.flight.requests.per.connection=5
delivery.timeout.ms=120000
```

`max.in.flight.requests.per.connection` 이 5 이하여야 하는 이유는 브로커가 유지하는 시퀀스 검증 윈도우 크기가 5이기 때문이다. 6개 이상의 요청이 동시에 비행 중이면 앞선 배치가 재시도로 뒤로 밀렸을 때 브로커가 그 배치를 윈도우 안에서 찾지 못해 `OutOfOrderSequenceException` 을 내고, 결과적으로 순서가 뒤집힌 로그가 만들어질 수 있다. 5는 처리량과 순서 보장의 타협점이며, 1로 낮추면 파티션당 왕복 지연이 그대로 처리량 상한이 되어 보통 20~40% 수준의 throughput 손실을 본다.

```java
public class IdempotentProducerFactory {

    public Producer<String, String> create(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        return new KafkaProducer<>(props);
    }

    public void sendWithFatalHandling(Producer<String, String> producer, ProducerRecord<String, String> record) {
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                return;
            }
            if (exception instanceof OutOfOrderSequenceException || exception instanceof ProducerFencedException) {
                // 복구 불가능한 상태다. 재시도하지 말고 프로듀서를 닫은 뒤 새로 만들어야 한다.
                producer.close(Duration.ZERO);
                throw new IllegalStateException("producer state corrupted", exception);
            }
            log.warn("retriable send failure, client will retry: {}", exception.getMessage());
        });
    }
}
```

멱등성은 **하나의 프로듀서 세션 안에서만** 유효하다는 점이 핵심 한계다. JVM 이 죽고 다시 뜨면 새 PID 를 받으므로, 크래시 직전 브로커에 도달했지만 ack 를 못 받은 배치는 새 PID 로 다시 전송되어 중복이 된다. 세션을 넘는 식별자가 필요하고, 그것이 `transactional.id` 다.

## 3. 트랜잭션 API 와 transactional.id

`transactional.id` 는 애플리케이션이 직접 정하는 논리적 프로듀서 이름이다. 재시작해도 같은 값을 쓰면 코디네이터가 "같은 프로듀서가 돌아왔다"고 인식해 이전 세션의 미완료 트랜잭션을 정리한다. 따라서 이 값은 인스턴스마다 안정적이고 유일해야 한다. 오토스케일되는 Pod 에서 UUID 를 쓰면 매 기동마다 새 ID 가 생겨 좀비 펜싱이 동작하지 않고 `__transaction_state` 에 쓰레기 항목만 쌓인다. StatefulSet 서수나 담당 파티션 번호를 접미사로 쓰는 것이 안전하다.

```java
public class TransactionalProducerFactory {

    public Producer<String, String> create(String bootstrapServers, String transactionalId) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        props.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 60_000);
        // transactional.id 를 지정하면 enable.idempotence 는 자동으로 true 가 된다.
        Producer<String, String> producer = new KafkaProducer<>(props);
        producer.initTransactions();
        return producer;
    }
}
```

API 는 다섯 개뿐이다. `initTransactions()` 는 코디네이터를 찾아 PID 를 발급받고 에포크를 1 증가시키며, 같은 `transactional.id` 의 이전 트랜잭션이 완료(커밋 또는 어보트)될 때까지 블록한다. 애플리케이션 수명주기에서 단 한 번만 호출해야 한다. `beginTransaction()` 은 클라이언트 로컬 상태만 바꾸는 값싼 호출이고, 실제 코디네이터 등록은 첫 `send()` 때 `AddPartitionsToTxn` 요청으로 일어난다. `sendOffsetsToTransaction()` 은 소비한 오프셋을 같은 트랜잭션에 참여시키며, `commitTransaction()` / `abortTransaction()` 이 종료를 지시한다.

`commitTransaction()` 은 블로킹이며, 반환되었다는 것은 코디네이터가 커밋을 확정했다는 뜻이다. 여기서 `KafkaException` 계열 중 `ProducerFencedException`, `OutOfOrderSequenceException`, `AuthorizationException` 은 복구 불가능이라 프로듀서를 닫아야 하고, `TimeoutException` 같은 나머지는 `abortTransaction()` 후 재시도할 수 있다.

## 4. 트랜잭션 코디네이터와 `__transaction_state`

트랜잭션 코디네이터는 별도 프로세스가 아니라 브로커 안에 있는 모듈이다. 어떤 브로커가 담당하는지는 내부 토픽 `__transaction_state` 의 파티션 소유권으로 결정된다. 즉 `partition = abs(hash(transactional.id)) % transaction.state.log.num.partitions` 로 파티션을 고른 뒤, 그 파티션의 리더 브로커가 해당 `transactional.id` 의 코디네이터가 된다. 이 토픽은 압축(compacted) 토픽이며 기본값은 파티션 50개, 복제 계수 3, `min.insync.replicas=2` 다. 코디네이터의 모든 상태 전이는 이 토픽에 먼저 기록되므로, 코디네이터 브로커가 죽어도 새 리더가 로그를 재생해 상태를 복원한다.

```properties
# broker 측 설정 (server.properties)
transaction.state.log.num.partitions=50
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
transaction.max.timeout.ms=900000
transactional.id.expiration.ms=604800000
```

프로토콜은 2PC 를 닮았지만 참여자가 투표하지 않는다는 결정적 차이가 있다. 코디네이터가 단독으로 결정하고, 데이터 파티션의 리더는 마커를 받아 기록할 뿐 거부권이 없다. 순서는 다음과 같다.

1. 첫 `send()` 시 프로듀서가 `AddPartitionsToTxn` 을 보내면 코디네이터는 트랜잭션 상태를 `Ongoing` 으로 바꾸고 참여 파티션 목록을 `__transaction_state` 에 기록한다
2. 프로듀서는 데이터 파티션에 레코드를 직접 쓴다. 이 레코드들은 커밋 전이라도 로그에 실제로 적재된다
3. `sendOffsetsToTransaction()` 은 `AddOffsetsToTxn` 으로 `__consumer_offsets` 의 해당 파티션을 참여자로 추가한 뒤, 그룹 코디네이터에게 `TxnOffsetCommit` 을 보낸다
4. `commitTransaction()` 시 코디네이터는 `PrepareCommit` 을 `__transaction_state` 에 기록한다. 이 기록이 복제되는 순간 결과가 확정되며, 이후 코디네이터가 죽어도 새 코디네이터가 이어서 완료시킨다
5. 코디네이터가 참여한 모든 데이터 파티션과 `__consumer_offsets` 파티션에 `WriteTxnMarkers` 요청을 보내 **컨트롤 레코드**(COMMIT 또는 ABORT 마커)를 append 한다
6. 모든 마커가 기록되면 코디네이터가 `CompleteCommit` 을 기록하고 트랜잭션이 끝난다

컨트롤 레코드는 사용자 데이터와 같은 로그에 같은 오프셋 공간을 쓰면서 기록된다. 애플리케이션 컨슈머에게는 노출되지 않지만 오프셋 번호는 소비하므로, 트랜잭션을 쓰면 "마지막 오프셋 - 처음 오프셋"이 실제 메시지 수보다 크다. 커밋 주기가 짧을수록 이 오버헤드가 커지는데, 참여 파티션이 N개면 트랜잭션 하나당 N+1 개의 마커가 추가된다.

## 5. 컨트롤 레코드와 컨슈머의 `read_committed`·LSO

컨슈머는 `isolation.level` 로 커밋되지 않은 데이터를 볼지 결정한다. 기본값 `read_uncommitted` 는 HW(High Watermark)까지 모든 레코드를 반환하므로, abort 될 레코드까지 읽힌다. `read_committed` 로 바꾸면 브로커는 **LSO(Last Stable Offset)** 까지만 반환한다. LSO 는 "아직 완료되지 않은 가장 오래된 트랜잭션의 첫 오프셋"이며, 진행 중 트랜잭션이 없으면 HW 와 같다.

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
props.put(ConsumerConfig.GROUP_ID_CONFIG, "settlement-eos");
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
```

abort 된 레코드는 로그에서 물리적으로 삭제되지 않는다. 대신 브로커는 세그먼트마다 `.txnindex` 파일(aborted transaction index)에 어보트된 트랜잭션의 `(PID, firstOffset, lastOffset, lastStableOffset)` 을 기록해 둔다. `read_committed` fetch 응답에는 이 구간 목록이 함께 실리고, **필터링은 클라이언트가 수행한다.** 즉 abort 된 데이터도 네트워크로 전송된 뒤 버려지므로, 어보트 비율이 높으면 유효 처리량이 그만큼 떨어진다.

운영상 가장 중요한 함의는 지연이다. `read_committed` 컨슈머는 트랜잭션이 커밋될 때까지 그 파티션에서 **아무것도** 못 읽는다. 같은 파티션의 다른 프로듀서가 쓴 나중 메시지도 LSO 에 막힌다. 따라서 종단 지연의 하한이 "트랜잭션 커밋 주기 + 마커 복제 시간"이 된다. 커밋 주기 100ms 면 p50 지연에 50ms 정도가 얹히고, 배치 지향으로 5초 주기를 쓰면 지연이 초 단위로 뛰다. 더 위험한 것은 행(hang)이다. 프로듀서가 멈춘 채 트랜잭션을 열어두면 `transaction.timeout.ms` 가 만료될 때까지 LSO 가 고정되어 컨슈머 랙이 무한정 쌓인다. `kafka-transactions.sh --list` 로 장기 실행 트랜잭션을 감시하고, 필요하면 `--force-terminate` 로 강제 종료해야 한다.

```bash
# 진행 중 트랜잭션 조회 (Kafka 3.0+)
kafka-transactions.sh --bootstrap-server broker:9092 --list

# 2분 이상 열려 있는 트랜잭션만 탐지
kafka-transactions.sh --bootstrap-server broker:9092 \
  find-hanging --broker-id 1 --max-transaction-timeout 120000

# 특정 transactional.id 강제 종료 (LSO 를 풀어 컨슈머 랙을 해소)
kafka-transactions.sh --bootstrap-server broker:9092 \
  --force-terminate --transactional-id settlement-tx-3
```

## 6. 좀비 펜싱과 타임아웃 설정

좀비(zombie)는 GC 스톱이나 네트워크 단절로 그룹에서 쪼겨났지만 자신은 살아 있다고 믿고 계속 쓰려는 인스턴스다. 이를 막는 장치가 에포크다. 같은 `transactional.id` 로 새 인스턴스가 `initTransactions()` 를 호출하면 코디네이터는 에포크를 1 증가시키고, 이전 에포크로 들어오는 모든 요청을 `ProducerFencedException` 으로 거부한다. 좀비는 예외를 받고 죽어야 하며, 이 예외는 **잡아서 재시도하면 안 되는** 신호다.

KIP-447 이전(EOSv1)에는 컨슈머 리밸런스가 일어나도 코디네이터가 그 사실을 몰랐기 때문에, 파티션 재배치 후 새 소유자가 옷 소유자를 펜싱하려면 `transactional.id` 를 입력 파티션에 1:1 로 묶어야 했다. 그래서 입력 파티션 수만큼 프로듀서가 필요했다. KIP-447 은 `sendOffsetsToTransaction()` 의 인자를 `String groupId` 에서 `ConsumerGroupMetadata` 로 바꿔 제너레이션 ID 를 함께 전달한다. 그룹 코디네이터가 오래된 제너레이션의 오프셋 커밋을 직접 거부할 수 있으므로 파티션 단위 `transactional.id` 가 불필요해졌고, 스레드당 프로듀서 하나로 충분해졌다.

| 설정 | 기본값 | 소유 | 역할과 주의점 |
|---|---|---|---|
| `transaction.timeout.ms` | 60000 | 프로듀서 | 초과 시 코디네이터가 자동 abort. 이 값만큼 LSO 가 막힐 수 있다 |
| `transaction.max.timeout.ms` | 900000 | 브로커 | 프로듀서가 더 큰 값을 요청하면 기동 실패 |
| `transactional.id.expiration.ms` | 604800000 (7일) | 브로커 | 미사용 ID 의 PID 매핑 만료. 만료 후 재사용하면 펜싱이 풀린다 |
| `max.poll.interval.ms` | 300000 | 컨슈머 | `transaction.timeout.ms` 보다 크면 리밸런스 전에 트랜잭션이 먼저 터진다 |

권장 관계는 `transaction.timeout.ms` > (한 배치 처리 최대 시간) 이면서 `max.poll.interval.ms` 보다는 작게 두는 것이다. 처리 시간이 30초까지 튀는 파이프라인이라면 `transaction.timeout.ms=60000`, `max.poll.interval.ms=120000` 정도가 무난하다.

## 7. consume-transform-produce 구현과 Kafka Streams EOSv2

플레인 클라이언트로 구현할 때의 정석 루프다. 오프셋은 `record.offset() + 1` 을 커밋해야 하고, 컨슈머의 `enable.auto.commit` 은 반드시 꺼야 한다.

```java
public class ExactlyOnceProcessor implements Runnable {

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final String outputTopic;
    private volatile boolean running = true;

    @Override
    public void run() {
        producer.initTransactions();
        while (running) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
            if (records.isEmpty()) {
                continue;
            }
            try {
                producer.beginTransaction();
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (ConsumerRecord<String, String> record : records) {
                    String transformed = transform(record.value());
                    producer.send(new ProducerRecord<>(outputTopic, record.key(), transformed));
                    offsets.put(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1));
                }
                producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                producer.commitTransaction();
            } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                // 복구 불가. 프로듀서를 버리고 인스턴스를 종료한다.
                running = false;
                producer.close(Duration.ZERO);
                throw new IllegalStateException("fatal transactional error", e);
            } catch (KafkaException e) {
                // 재시도 가능. abort 후 컨슈머를 마지막 커밋 지점으로 되감는다.
                producer.abortTransaction();
                rewindToLastCommitted();
            }
        }
    }

    private void rewindToLastCommitted() {
        for (TopicPartition partition : consumer.assignment()) {
            OffsetAndMetadata committed = consumer.committed(Set.of(partition)).get(partition);
            if (committed == null) {
                consumer.seekToBeginning(List.of(partition));
            } else {
                consumer.seek(partition, committed.offset());
            }
        }
    }
}
```

`abortTransaction()` 후 되감기를 빠뜨리는 것이 가장 흔한 버그다. 컨슈머의 내부 위치는 이미 앞으로 나가 있으므로 되감지 않으면 어보트된 배치가 영구 유실된다.

Kafka Streams 를 쓰면 이 전부가 설정 한 줄로 대체된다. `processing.guarantee=exactly_once_v2` 를 켜면 상태 저장소 변경로그(changelog)까지 같은 트랜잭션에 묶인다.

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "settlement-streams");
props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 4);
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
```

EOSv1 은 태스크(= 입력 파티션)마다 프로듀서를 하나씩 만들었다. 입력 파티션 96개를 인스턴스 4대가 나눠 처리하면 클러스터 전체에 96개의 프로듀서와 96개의 `transactional.id` 가 생기고, 그만큼 `__transaction_state` 쓰기와 마커 트래픽이 발생한다. EOSv2 는 스트림 스레드당 프로듀서 하나이므로 같은 조건에서 4 × 4 = 16개로 줄어든다. 프로듀서 수는 커밋당 브로커 왕복 횟수와 메모리 버퍼(`buffer.memory` 기본 32MB × 프로듀서 수)에 직결되므로, 파티션이 많은 토폴로지일수록 차이가 크다. 참고로 `exactly_once` 와 `exactly_once_beta` 값은 Kafka 3.0 에서 폐기 예고된 뒤 4.0 에서 제거되었으므로 신규 개발은 `exactly_once_v2` 만 쓰면 된다.

## 8. Spring Kafka 연동과 DB-Kafka 이중 쓰기

Spring 에서는 `spring.kafka.producer.transaction-id-prefix` 를 지정하는 것만으로 `KafkaTransactionManager` 가 자동 등록되고 `KafkaTemplate` 이 트랜잭션 모드로 전환된다. 리스너 컨테이너는 이 트랜잭션 매니저를 발견하면 레코드 처리 전후를 트랜잭션으로 감싸고 오프셋 커밋도 트랜잭션 안에서 수행한다.

```java
@Configuration
@EnableKafka
public class KafkaTransactionConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties properties) {
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null));
        factory.setTransactionIdPrefix("settlement-tx-");
        return factory;
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTransactionManager(ProducerFactory<String, String> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTransactionManager<String, String> transactionManager) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setKafkaAwareTransactionManager(transactionManager);
        factory.getContainerProperties().setEosMode(ContainerProperties.EOSMode.V2);
        return factory;
    }
}
```

문제는 리스너 안에서 DB 도 함께 쓸 때다. `@Transactional` 을 붙이면 JPA 트랜잭션과 Kafka 트랜잭션이라는 서로 다른 두 리소스가 생기는데, 둘 사이에는 XA 같은 공통 커밋 프로토콜이 없다. 과거 Spring for Apache Kafka 가 제공하던 `ChainedKafkaTransactionManager` 는 두 매니저를 중첩해 순서대로 커밋할 뿐, 원자성을 주지 못했다. DB 커밋은 성공하고 Kafka 커밋 직전에 프로세스가 죽으면 DB 에는 반영됐는데 이벤트는 발행되지 않는 상태가 남는다. 이 위험을 오해하게 만든다는 이유로 해당 클래스는 2.7 에서 폐기되고 3.0 에서 제거되었다. 현재 권장은 **Kafka 트랜잭션을 바깥, DB 트랜잭션을 안쪽**에 두어 실패 창을 좁히고, 소비 측에서 멱등 처리를 병행하는 것이다.

근본 해법은 이중 쓰기 자체를 없애는 **Transactional Outbox** 다. 이벤트를 별도 토픽이 아니라 같은 DB 트랜잭션 안의 `outbox` 테이블에 INSERT 하고, CDC(Debezium)나 폴링 릴레이가 그 테이블을 읽어 Kafka 로 옮긴다. 쓰기 지점이 DB 하나뿐이므로 원자성이 DB 트랜잭션으로 보장된다.

```sql
CREATE TABLE outbox_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_outbox_created_at (created_at)
);
```

```java
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void settle(SettlementCommand command) {
        Settlement settlement = settlementRepository.save(Settlement.from(command));
        // 같은 DB 트랜잭션에 포함되므로 상태 변경과 이벤트 기록이 원자적이다.
        outboxEventRepository.save(OutboxEvent.of(settlement.getId(), "SettlementCompleted", command.toPayload()));
    }
}
```

Outbox 는 릴레이가 at-least-once 로 동작하므로 Kafka 에는 중복이 갈 수 있다. 대신 `aggregate_id` 를 메시지 키로 쓰면 파티션 내 순서가 보장되고, 소비 측이 이벤트 ID 로 멱등 처리하면 실질적 EOS 가 된다. 정리하면 **Kafka→Kafka 파이프라인은 트랜잭션 API, DB→Kafka 는 Outbox** 가 기본 선택이다.

## 9. 성능 트레이드오프와 운영 수치

EOS 의 비용은 세 갈래다. 첫째, 트랜잭션 하나당 `__transaction_state` 에 최소 3회(Ongoing, PrepareCommit, CompleteCommit) 쓰기가 발생하고 각각 복제 계수만큼 복제된다. 둘째, 참여 파티션 N개마다 컨트롤 레코드 N+1 개가 데이터 로그에 추가된다. 셋째, `read_committed` 컨슈머는 LSO 에 묶여 커밋 전까지 대기한다.

따라서 조절 손잡이는 결국 **커밋 주기**다. 주기를 늘리면 트랜잭션당 메시지 수가 늘어 고정 비용이 분산되지만 종단 지연이 늘고, 줄이면 반대가 된다. Kafka Streams 는 이 관계를 반영해 `commit.interval.ms` 기본값을 at-least-once 에서 30000ms, EOS 에서 100ms 로 다르게 잡는다. 100ms 주기면 마커 오버헤드가 작지 않으므로, 지연 요구가 느슨한 배치성 토폴로지라면 1000~5000ms 로 올려 브로커 부하를 크게 줄일 수 있다.

| 조정 대상 | 늘렸을 때 | 줄였을 때 | 판단 기준 |
|---|---|---|---|
| 커밋 주기 | 처리량 증가, 마커 감소 | 종단 지연 감소 | SLA 가 초 단위면 1000ms 이상 |
| 트랜잭션 참여 파티션 수 | 한 번에 더 많은 출력 | 마커 수 감소 | 출력 토픽 파티션을 키로 좁힌다 |
| `transaction.state.log.num.partitions` | 코디네이터 분산 | 메타데이터 감소 | `transactional.id` 수가 수천 개면 기본 50 유지 |
| `linger.ms` | 배치 효율 증가 | 지연 감소 | 트랜잭션 커밋 주기보다 작게 |

경험적으로 멱등 프로듀서만 켜을 때의 오버헤드는 시퀀스 필드 몇 바이트 수준이라 무시할 만하고(일반적으로 한 자릿수 % 이내), 비용의 대부분은 트랜잭션과 `read_committed` 에서 온다. 그러므로 설계 순서는 명확하다. 먼저 `enable.idempotence=true` 만으로 프로듀서 중복을 없애고, 소비 측 멱등 키로 해결되지 않는 경우에만 트랜잭션을 도입한다. 그리고 트랜잭션을 도입했다면 반드시 `kafka-transactions.sh find-hanging` 을 정기 점검에 넣어, 열린 채 방치된 트랜잭션이 컨슈머 랙을 폭발시키는 사고를 예방해야 한다.

## 참고

- [Apache Kafka Documentation — Producer Configs](https://kafka.apache.org/documentation/#producerconfigs)
- [Apache Kafka Documentation — Consumer Configs](https://kafka.apache.org/documentation/#consumerconfigs)
- [Apache Kafka Documentation — Message Delivery Semantics](https://kafka.apache.org/documentation/#semantics)
- [Kafka Streams — Processing Guarantees](https://kafka.apache.org/documentation/streams/core-concepts#streams_processing_guarantee)
- [KIP-98: Exactly Once Delivery and Transactional Messaging](https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging)
- [KIP-447: Producer scalability for exactly once semantics](https://cwiki.apache.org/confluence/display/KAFKA/KIP-447%3A+Producer+scalability+for+exactly+once+semantics)
- [KIP-679: Producer will enable the strongest delivery guarantee by default](https://cwiki.apache.org/confluence/display/KAFKA/KIP-679%3A+Producer+will+enable+the+strongest+delivery+guarantee+by+default)
- [KafkaProducer Javadoc — Transactional API](https://kafka.apache.org/documentation/#producerapi)
- [Spring for Apache Kafka — Transactions](https://docs.spring.io/spring-kafka/reference/kafka/transactions.html)
- [Confluent — Transactions in Apache Kafka](https://www.confluent.io/blog/transactions-apache-kafka/)
- [Microservices.io — Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
