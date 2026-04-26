Notion 원본: https://www.notion.so/34e5a06fd6d38170b65ecea8a05500f8

# Kafka Exactly-Once Semantics와 Idempotent Producer

> 2026-04-26 신규 주제 · 확장 대상: Backend (이벤트 처리), Spring (Spring Kafka 학습됨)

## 학습 목표

- At-most-once / At-least-once / Exactly-once의 정확한 정의를 메시지 라이프사이클로 구분한다
- Idempotent Producer의 PID와 sequence number 메커니즘을 broker 동작과 함께 설명한다
- Transactional Producer + `read_committed` Consumer로 Read-Process-Write EOS 파이프라인을 구성한다
- Spring Kafka `KafkaTransactionManager`와 chained transaction 구성 시 주의점을 검증한다

---

## 1. 세 가지 전달 보장의 정의

흔히 혼용되는 "exactly-once"라는 용어는 정확한 경계가 있다. 메시지가 producer → broker → consumer로 흐르는 전 구간에서 어디에 보장이 걸리느냐가 분류 기준이다.

| 보장 | producer→broker | consumer 처리 | 결과 |
|---|---|---|---|
| At-most-once | retry 없음 | offset 즉시 commit | 손실 가능, 중복 없음 |
| At-least-once | retry 있음 | 처리 후 offset commit | 손실 없음, 중복 가능 |
| Exactly-once | 멱등 producer + transaction | transaction 안에서 offset commit | 손실 없음, 중복 없음 |

Kafka의 기본 producer는 `acks=all, retries=Integer.MAX_VALUE` 설정만으로는 at-least-once다. 네트워크 partition으로 producer가 ack를 못 받고 재전송하면 같은 메시지가 broker에 두 번 적재될 수 있기 때문이다. Idempotent Producer는 이 producer→broker 구간의 중복을 제거한다. 그러나 consumer 측 중복(처리 후 offset commit 직전 crash)은 남아있다. 진정한 EOS는 transaction까지 더해야 한다.

## 2. Idempotent Producer 메커니즘

`enable.idempotence=true`로 켜면 producer는 broker로부터 unique PID(Producer ID)를 발급받는다. 모든 produce 요청은 `(PID, partition, sequence number)` 튜플을 포함하고, broker는 partition별로 마지막 sequence를 기억한다. sequence가 `last + 1`이 아니면 `OutOfOrderSequenceException` 또는 중복(`DuplicateSequenceException`)으로 거부한다.

```
Producer P (PID=1234)
  → topic-A partition-0
    seq=0 msg-A   →  broker accepts, last_seq=0
    seq=1 msg-B   →  broker accepts, last_seq=1
    seq=2 msg-C   →  network timeout, retry seq=2
    seq=2 msg-C'  →  broker rejects (duplicate), client treats as success
    seq=3 msg-D   →  broker accepts, last_seq=3
```

이 메커니즘이 동작하려면 다음 설정이 강제된다.

```properties
enable.idempotence=true
acks=all
retries=Integer.MAX_VALUE
max.in.flight.requests.per.connection=5  # 5 이하만 허용
```

`max.in.flight`가 5 이하인 이유는 broker가 sliding window로 sequence를 검증하기 때문이다. 6 이상이면 retry 시 순서가 뒤바뀌어 검증 실패한다. 5는 throughput과 ordering의 균형이다. 1로 줄이면 strict ordering이지만 throughput이 떨어진다.

PID는 producer 인스턴스 lifetime 동안 유지된다. producer를 재시작하면 새 PID를 받으므로, 재시작 직전 inflight 메시지 중 broker에 도달했지만 ack를 받지 못한 메시지는 재시작 후 새 PID로 다시 보내져 중복이 된다. 이 갭을 Transactional Producer가 메운다.

## 3. Transactional Producer

`transactional.id`를 설정하면 producer는 cross-partition atomic write를 할 수 있다.

```java
Properties props = new Properties();
props.put("bootstrap.servers", "kafka1:9092,kafka2:9092");
props.put("transactional.id", "order-service-1");
props.put("enable.idempotence", "true");
props.put("acks", "all");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();

try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("orders", orderId, orderJson));
    producer.send(new ProducerRecord<>("audit", orderId, auditJson));
    producer.commitTransaction();
} catch (KafkaException e) {
    producer.abortTransaction();
    throw e;
}
```

`initTransactions()`는 broker의 Transaction Coordinator로부터 `producerEpoch`를 받는다. 같은 `transactional.id`를 가진 새 producer 인스턴스가 등장하면 이전 producer의 epoch는 fence 되어 더 이상 commit 할 수 없다(zombie fencing). 이로써 producer crash 후 재시작 시 inflight transaction이 자동으로 abort 된다.

`commitTransaction()`은 broker가 transaction marker(commit/abort marker)를 모든 관련 partition에 기록해야 완료된다. consumer는 이 marker를 보고 메시지를 가시화 여부를 결정한다.

## 4. Consumer isolation.level

EOS를 완성하려면 consumer가 abort된 transaction의 메시지를 읽어선 안 된다.

```properties
isolation.level=read_committed
```

`read_committed`는 commit marker가 찍힌 메시지만 consumer에 노출한다. abort marker가 찍히면 그 transaction에 속한 메시지는 보이지 않는다. 단, `committed offset` 계산은 LSO(Last Stable Offset) 기준이라 active transaction 메시지는 일시적으로 가려진다. 이는 consumer lag 모니터링에서 "lag이 0인데 메시지가 안 들어온다"는 현상으로 나타날 수 있다.

`read_uncommitted`(기본값)은 commit/abort 여부를 무시하고 모든 메시지를 읽는다. EOS 파이프라인에서는 절대 사용하면 안 된다.

## 5. Read-Process-Write 패턴

Kafka EOS의 정수는 Read-Process-Write 패턴이다. 입력 topic을 읽어 변환 후 출력 topic에 쓰는 stream processing을 atomic하게 수행한다.

```java
public class StreamProcessor {

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;

    public void run() {
        producer.initTransactions();
        consumer.subscribe(List.of("input-topic"));

        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            if (records.isEmpty()) continue;

            producer.beginTransaction();
            try {
                for (ConsumerRecord<String, String> record : records) {
                    String transformed = process(record.value());
                    producer.send(new ProducerRecord<>("output-topic", record.key(), transformed));
                }

                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (TopicPartition tp : records.partitions()) {
                    long lastOffset = records.records(tp)
                            .get(records.records(tp).size() - 1)
                            .offset();
                    offsets.put(tp, new OffsetAndMetadata(lastOffset + 1));
                }
                producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

                producer.commitTransaction();
            } catch (Exception e) {
                producer.abortTransaction();
            }
        }
    }
}
```

핵심은 `sendOffsetsToTransaction`이다. consumer offset commit이 producer transaction에 포함되어, output 메시지 쓰기와 input offset 진행이 atomic 하게 되거나 atomic 하게 abort 된다. 별도로 `consumer.commitSync()`를 호출하면 안 된다. transaction 외부에서 commit 되어 EOS가 깨진다.

`enable.auto.commit=false`로 반드시 끈다. auto commit은 백그라운드 thread가 transaction과 무관하게 offset을 진행시킨다.

## 6. Spring Kafka에서의 EOS 구성

Spring Kafka는 `ProducerFactory`에 `transactionIdPrefix`를 지정하고 `KafkaTransactionManager`를 등록하면 EOS가 활성화된다.

```java
@Configuration
@EnableTransactionManagement
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setTransactionIdPrefix("order-svc-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTxManager(ProducerFactory<String, String> pf) {
        return new KafkaTransactionManager<>(pf);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory(
            ConsumerFactory<String, String> cf, KafkaTransactionManager<String, String> txManager) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setTransactionManager(txManager);
        factory.getContainerProperties().setEosMode(ContainerProperties.EOSMode.V2);
        return factory;
    }
}
```

`transactionIdPrefix`는 producer 인스턴스마다 unique 해야 한다. Spring Kafka는 `prefix + groupId + topic + partition` 조합으로 자동 생성하므로(EOS V2) 보통은 prefix만 설정하면 된다. EOS V1은 partition마다 producer를 생성해 자원 부담이 컸지만 V2(Kafka 2.5+)는 producer를 재사용해 효율이 높다.

`@KafkaListener` 메서드 안에서 `kafkaTemplate.send()`를 호출하면 자동으로 같은 transaction에 묶인다.

```java
@KafkaListener(topics = "input-topic")
public void listen(ConsumerRecord<String, String> record) {
    String result = transform(record.value());
    kafkaTemplate.send("output-topic", record.key(), result);
    // commit은 listener 메서드 종료 시점에 KafkaTransactionManager가 처리
}
```

DB INSERT까지 같은 transaction에 묶고 싶다면 `ChainedKafkaTransactionManager`를 쓴다. 단, 이는 진정한 distributed transaction이 아닌 best-effort 1PC이므로 DB commit 성공 + Kafka commit 실패 같은 중간 실패 가능성이 남는다. 진정한 atomic을 원하면 transactional outbox 패턴을 쓴다(Saga 노트 참조).

## 7. 성능 측정과 trade-off

Confluent의 공개 벤치마크와 자체 측정 기준으로 EOS 활성화의 비용은 다음과 같다.

| 모드 | 처리량 (msg/s) | latency p99 | CPU |
|---|---|---|---|
| acks=1 (at-most-once) | 1.2M | 8ms | 베이스라인 |
| acks=all + idempotence | 950K | 12ms | +5% |
| transactional | 720K | 25ms | +15% |

EOS 비용은 broker fsync(`acks=all`)와 transaction marker 추가 쓰기, isolation.level=read_committed의 LSO 계산이다. throughput은 약 40% 감소한다. 이게 받아들일 만한가는 비즈니스 요구에 따른다. 결제 / 주문 정산 / 회계 데이터는 EOS가 필수, 단순 이벤트 stream(클릭 로그, 메트릭)은 at-least-once + dedup이 비용 효율적이다.

`commit.interval.ms`(consumer)나 `linger.ms`(producer)를 키우면 batch 효율이 올라가지만 latency가 늘어난다. 운영 SLA를 보고 결정한다.

## 8. 흔한 함정

`transactional.id`를 환경별로 같게 두면 prod와 staging이 서로의 producer를 fence 하는 사고가 난다. 환경 prefix를 반드시 분리한다.

`isolation.level`을 producer에 설정하지 않도록 주의한다. 이 설정은 consumer 전용이다. producer에 잘못 넣어도 에러는 안 나지만 무시될 뿐이다.

`max.in.flight=1`로 강제하는 케이스는 EOS와 무관한 순서 강제용이다. idempotent producer에서는 5까지 안전하므로 throughput을 위해 5로 둔다.

Kafka Streams는 `processing.guarantee=exactly_once_v2`만 설정하면 위 메커니즘을 자동 구성한다. 직접 producer/consumer를 다루지 않는 stream 워크로드라면 Streams를 쓰는 편이 유지보수가 압도적으로 쉽다.

## 참고

- Apache Kafka Documentation - Idempotent and Transactional Producer
- Confluent, "Exactly-Once Semantics in Apache Kafka" (Jun Rao)
- Spring for Apache Kafka Reference - Transactions
- KIP-447, KIP-360 (transactional.id 개선 KIP)
