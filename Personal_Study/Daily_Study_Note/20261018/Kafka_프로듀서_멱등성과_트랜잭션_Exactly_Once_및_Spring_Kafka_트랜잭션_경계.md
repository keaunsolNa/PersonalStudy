Notion 원본: https://app.notion.com/p/3fd5a06fd6d3812f95dbe7d85d5739ed?pvs=204

# Kafka 프로듀서 멱등성과 트랜잭션 API 기반 Exactly-Once 처리 및 Spring Kafka 트랜잭션 경계 설계

> 2026-10-18 신규 주제 · 확장 대상: Spring

## 학습 목표

- 멱등 프로듀서의 (PID, epoch, sequence) 검증 흐름을 코드로 재현한다.
- 트랜잭션 마커와 LSO가 만드는 가시성 규칙을 consume-transform-produce 루프에 적용한다.
- Spring Kafka의 KafkaTransactionManager와 리스너 컨테이너 트랜잭션 경계를 DB 트랜잭션과 분리해 설계한다.
- Exactly-Once가 깨지는 지점을 식별하고 outbox, 멱등 소비자로 보완한다.

## 1. 멱등 프로듀서: 재시도가 중복이 되는 지점

acks=all에 retries를 켜 두면 브로커가 append를 끝낸 뒤 응답만 유실되는 경우 프로듀서는 같은 배치를 다시 보내고, 파티션 로그에는 레코드가 두 번 남는다. 멱등 프로듀서는 이 문제를 브로커 쪽 중복 제거로 푼다. 프로듀서는 시작 시 브로커에서 PID와 epoch를 받고, 파티션별로 배치마다 단조 증가하는 sequence를 붙인다. 브로커는 (PID, 파티션)별로 마지막 sequence를 기억하며 기대값보다 작으면 중복으로 보고 append 없이 성공 응답을 주고, 기대값보다 크면 OUT_OF_ORDER_SEQUENCE_NUMBER로 거절한다.

이 판정 로직만 Java로 시뮬레이션했다. 실제 브로커 코드가 아닌 설명용 모델이며, 아래는 javac로 컴파일해 실행한 결과다.

```java
Broker b = new Broker();
b.append(7, (short) 0, 0, "A");   // PID=7, epoch=0, seq=0
b.append(7, (short) 0, 0, "A");   // 응답 유실 후 재전송
b.append(7, (short) 0, 1, "B");
b.append(7, (short) 0, 3, "D");   // seq 2 누락
b.append(7, (short) 1, 0, "X");   // epoch 상승 -> seq 리셋
b.append(7, (short) 0, 2, "Z");   // 이전 epoch(좀비)
```

```text
seq0 OK offset=0
seq0 retry DUPLICATE_SEQUENCE(ack, not appended)
seq1 OK offset=1
seq3 OUT_OF_ORDER_SEQUENCE_NUMBER
new epoch seq0 OK offset=2
zombie epoch0 INVALID_PRODUCER_EPOCH(fenced)
log=[A, B, X]
```

첫째, 보장 범위는 단일 프로듀서 세션 안의 단일 파티션이다. PID는 프로듀서 인스턴스가 재시작하면 새로 발급되므로, 앱이 재시작된 뒤 같은 비즈니스 이벤트를 다시 보내면 멱등성은 이를 막지 못한다. 또한 브로커가 sequence 이력을 파티션당 최근 5개 배치까지만 유지하기 때문에 max.in.flight.requests.per.connection이 5를 넘으면 멱등성을 켤 수 없다. 5 이하에서는 재시도가 있어도 순서가 보존된다.

Kafka 3.0부터 클라이언트 기본값이 enable.idempotence=true, acks=all이다. 이전 버전이나 다른 언어 클라이언트는 기본값이 다를 수 있으므로 명시하는 편이 안전하다.

## 2. 트랜잭션 API: transactional.id, epoch, 코디네이터

멱등성이 파티션 단위 중복 제거라면 트랜잭션은 여러 파티션과 컨슈머 오프셋 커밋을 원자적으로 묶는다. 프로듀서에 transactional.id를 주면 initTransactions()에서 트랜잭션 코디네이터(내부 토픽 __transaction_state의 파티션 리더)를 찾아 PID와 epoch를 받는다. 같은 transactional.id로 새 프로듀서가 initTransactions()를 호출하면 epoch가 올라가고, 이전 인스턴스는 이후 요청에서 ProducerFencedException을 받는다. 위 시뮬레이션의 마지막 줄이 이 좀비 펜싱이다. transactional.id는 인스턴스를 식별하는 값이면서 재시작 시 이전 미완료 트랜잭션을 중단시키는 열쇠다.

```java
Properties p = new Properties();
p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-processor-1");
// key/value serializer 설정 생략

try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
    producer.initTransactions();
    producer.beginTransaction();
    try {
        producer.send(new ProducerRecord<>("orders-validated", "k1", "v1"));
        producer.send(new ProducerRecord<>("audit", "k1", "validated"));
        producer.commitTransaction();
    } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
        // 복구 불가: 이 인스턴스는 닫는다
        throw e;
    } catch (KafkaException e) {
        producer.abortTransaction(); // 복구 가능: 중단 후 재시도
    }
}
```

예외 분류가 핵심이다. ProducerFencedException 등은 프로듀서를 닫아야 하고, 그 외 KafkaException은 abortTransaction() 후 다시 시작할 수 있다. 브로커가 없어 실행하지 못한 구조 예시다.

내부 흐름은 다음과 같다. 새 파티션에 처음 쓸 때 AddPartitionsToTxn으로 코디네이터에 파티션을 등록하고, 데이터는 평소처럼 파티션 리더에 쓴다. commitTransaction()은 EndTxn 요청을 보내고, 코디네이터가 PREPARE_COMMIT을 트랜잭션 로그에 기록한 시점이 커밋 확정이다. 이후 코디네이터가 참여한 각 파티션에 commit 마커(control record)를 쓰고 COMPLETE_COMMIT으로 마무리한다. 마커는 데이터 파티션에 실제 오프셋 하나를 차지하므로, 트랜잭션을 쓰는 토픽에서는 컨슈머가 오프셋 불연속을 보게 된다. 오프셋 연속성에 의존하는 lag 계산이나 "offset+1이 다음 레코드" 가정은 깨진다.

## 3. 컨슈머 가시성: read_committed와 LSO

트랜잭션 레코드는 커밋 전에도 로그에 존재하고, 가시성은 컨슈머의 isolation.level이 정한다. read_uncommitted(기본값)는 중단된 트랜잭션의 레코드까지 읽는다. read_committed는 LSO(Last Stable Offset), 즉 가장 오래된 미완료 트랜잭션의 첫 오프셋 이전까지만 반환하고, 중단된 트랜잭션의 레코드는 브로커가 abort 목록을 내려주면 클라이언트가 걸러낸다.

여기서 운영상 가장 아픈 trade-off가 나온다. LSO는 미완료 트랜잭션 하나가 파티션 전체의 read_committed 소비를 막는 head-of-line blocking을 만든다. 프로듀서가 멈추면 transaction.timeout.ms(기본 60초, 브로커 상한 기본 15분)가 지나 코디네이터가 중단시킬 때까지 다운스트림 지연이 계속된다. commit 주기를 timeout보다 충분히 짧게 잡는다.

레코드마다 트랜잭션을 여는 설계는 지연과 처리량 모두에서 불리하다. 구체 수치는 브로커 버전과 배치 크기에 따라 크게 달라지므로 단정하지 않고, 도입 전 자기 워크로드로 측정하길 권한다.

## 4. Consume-Transform-Produce와 EOS v2

Kafka가 말하는 Exactly-Once의 정확한 범위는 "Kafka 토픽에서 읽고, 처리하고, Kafka 토픽에 쓰는" 루프다. 입력 오프셋 커밋과 출력 쓰기를 한 트랜잭션에 넣어 둘이 함께 보이거나 함께 사라지게 만든다.

```java
consumer.subscribe(List.of("orders"));
producer.initTransactions();
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    if (records.isEmpty()) continue;
    producer.beginTransaction();
    try {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, String> r : records) {
            producer.send(new ProducerRecord<>("orders-validated", r.key(), transform(r.value())));
            offsets.put(new TopicPartition(r.topic(), r.partition()),
                        new OffsetAndMetadata(r.offset() + 1));
        }
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();
    } catch (KafkaException e) {
        producer.abortTransaction();
        // 구독 위치를 마지막 커밋 오프셋으로 되돌려야 한다 (seek)
    }
}
```

sendOffsetsToTransaction의 두 번째 인자로 ConsumerGroupMetadata를 넘기는 형태가 KIP-447(EOS v2, 브로커 2.5 이상)이다. 컨슈머 그룹의 generation 정보를 함께 보내므로, 리밸런스로 파티션이 다른 인스턴스로 넘어간 뒤 옛 인스턴스가 오프셋을 커밋하는 것을 브로커가 거절한다. v1에서는 입력 파티션마다 transactional.id를 따로 둬야 했지만, v2에서는 인스턴스당 하나로 충분해 프로듀서 수와 코디네이터 부하가 줄어든다. Spring Kafka 3.0부터는 v1 모드(ALPHA, BETA)가 제거되고 V2만 남았다.

abort 후에는 컨슈머 포지션을 마지막 커밋 오프셋으로 seek해야 한다. 그렇지 않으면 이미 poll로 앞서간 레코드를 건너뛴다. 이 부분을 Spring이 컨테이너 수준에서 대신 처리한다.

## 5. Spring Kafka 구성: 트랜잭션 프로듀서 팩토리

Spring Boot에서는 transaction-id-prefix만 지정하면 프로듀서 팩토리가 트랜잭션 모드가 되고 KafkaTransactionManager가 자동 구성된다.

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      transaction-id-prefix: order-tx-
      acks: all
    consumer:
      group-id: order-processor
      isolation-level: read_committed
      enable-auto-commit: false
```

수동 구성은 다음과 같다. 팩토리는 prefix에 접미 번호를 붙인 transactional.id의 프로듀서를 캐시해 쓴다.

```java
@Configuration
class KafkaTxConfig {
    @Bean
    ProducerFactory<String, String> producerFactory(KafkaProperties props) {
        var pf = new DefaultKafkaProducerFactory<String, String>(props.buildProducerProperties(null));
        pf.setTransactionIdPrefix("order-tx-");
        return pf;
    }

    @Bean
    KafkaTransactionManager<String, String> kafkaTransactionManager(ProducerFactory<String, String> pf) {
        return new KafkaTransactionManager<>(pf);
    }
}
```

v2의 컨슈머 기반 흐름은 모든 인스턴스가 같은 prefix를 써도 되지만(펜싱은 그룹 메타데이터가 담당), 컨슈머 없이 프로듀서 트랜잭션만 쓰는 서비스는 인스턴스끼리 같은 transactional.id를 쓰면 서로를 펜싱하므로 인스턴스별 고유 접미사가 필요하다. 트랜잭션이 켜진 KafkaTemplate은 트랜잭션 밖에서 send를 호출하면 예외가 나므로, 트랜잭션 밖 전송에는 executeInTransaction을 쓴다.

## 6. 트랜잭션 경계 설계: 리스너 컨테이너와 @Transactional

경계를 누가 여느냐에 따라 의미가 갈린다. 가장 단순하고 안전한 형태는 리스너 컨테이너가 트랜잭션을 여는 것이다. 컨테이너에 KafkaTransactionManager를 주면 처리 전에 트랜잭션을 시작하고, 정상 종료 시 오프셋을 트랜잭션으로 전송해 커밋한다. 예외가 나면 abort하고 해당 오프셋으로 seek해 재시도한다. 리스너 안에서 같은 KafkaTemplate으로 보낸 메시지는 이 트랜잭션에 자동 참여한다.

```java
@Bean
ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> cf, KafkaTransactionManager<String, String> tm) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
    f.setConsumerFactory(cf);
    f.getContainerProperties().setKafkaAwareTransactionManager(tm);
    return f;
}

@Service
class OrderListener {
    private final KafkaTemplate<String, String> template;
    OrderListener(KafkaTemplate<String, String> template) { this.template = template; }

    @KafkaListener(topics = "orders")
    void on(String payload) {
        template.send("orders-validated", payload.toUpperCase()); // 같은 Kafka 트랜잭션
    }
}
```

반대로 컨슈머 없이 서비스 메서드에서 시작하는 경우는 @Transactional을 쓰되 매니저를 명시한다. DB와 Kafka 매니저가 함께 있을 때 이름을 안 주면 Kafka 전송이 트랜잭션 밖으로 빠질 수 있다.

```java
@Transactional("kafkaTransactionManager")
public void publish(OrderCreated e) {
    kafkaTemplate.send("orders", e.id(), json(e));
    kafkaTemplate.send("audit", e.id(), "created");
}
```

DB 변경과 Kafka 전송을 한 메서드에 넣으면 하나의 원자적 트랜잭션이라 착각하기 쉽다. 실제로는 서로 독립된 두 트랜잭션이 중첩되어 순서대로 커밋되는 best-effort 1PC다. 흐름을 보면 바깥이 Kafka(컨테이너) 트랜잭션, 안쪽이 DB @Transactional일 때 안쪽 DB가 먼저 커밋되고 바깥 Kafka가 나중에 커밋된다. DB 커밋 직후 Kafka 커밋 전에 프로세스가 죽으면 DB에는 반영되었으나 Kafka는 abort되어 같은 레코드가 다시 소비된다. 즉 DB 쓰기는 at-least-once이고, 그 효과는 소비 측 멱등성으로 흡수해야 한다. ChainedKafkaTransactionManager는 이 한계를 가려 deprecated되었으므로 신규 설계에서는 쓰지 않는다.

| 경계 구성 | 원자성 범위 | 장애 시 결과 | 보완 수단 |
|---|---|---|---|
| 컨테이너 Kafka 트랜잭션만 | 입력 오프셋 + 출력 토픽 | 진정한 EOS | 없음 |
| Kafka 바깥, DB @Transactional 안쪽 | 각각 독립 | DB 반영 후 재처리 가능 | 소비 측 멱등 키 |
| DB 단독 + Outbox | DB 한 트랜잭션 | 발행은 at-least-once | 멱등 소비자 |

## 7. Exactly-Once가 깨지는 지점과 보완 설계

Kafka 트랜잭션은 외부 HTTP 호출, 메일 발송, DB 쓰기 같은 부수 효과를 되돌리지 못한다. 따라서 "EOS를 켰으니 안전하다"가 아니라 "Kafka 안에서만 정확히 한 번이고, 바깥은 멱등하게 만든다"로 읽어야 한다. 설계 원칙은 세 가지다.

첫째, DB 변경과 이벤트 발행의 이중 쓰기는 outbox 패턴으로 푼다. 서비스는 비즈니스 테이블과 outbox 테이블을 같은 DB 트랜잭션에 쓰고, 별도의 릴레이(폴링 또는 CDC)가 outbox를 읽어 Kafka로 발행한다. 릴레이의 발행은 at-least-once이므로 이벤트 ID를 키나 헤더에 실어 중복을 허용한다.

둘째, 소비 측은 이벤트 ID 기반 멱등 처리를 한다. 처리 이력 테이블의 유니크 제약을 비즈니스 쓰기와 같은 DB 트랜잭션에 넣으면 재전달되어도 한 번만 반영된다(event_id 기본키의 INSERT가 중복으로 실패하면 비즈니스 쓰기를 건너뛴다).

셋째, 운영에서는 ProducerFencedException 빈도(리밸런스 폭주 신호)와 read_committed 컨슈머의 lag 급증(LSO 정체 신호)을 감시한다. 임계치는 자기 시스템에서 기준선을 잡는다.

## 8. 테스트와 도입 판단

트랜잭션 동작은 mock 단위 테스트로는 검증되지 않는다. 마커와 LSO는 브로커가 만들기 때문이다. 단일 브로커 테스트에서는 transaction.state.log.replication.factor와 min.isr을 1로 낮춰야 하고, 운영과 가까운 검증에는 Testcontainers의 Kafka 이미지가 낫다. 검증 시나리오는 리스너에서 예외를 던져 abort시킨 뒤 read_committed 컨슈머가 출력 토픽에서 아무것도 읽지 못하는지, 정상 처리 후에는 정확히 1건이 읽히는지 두 가지로 충분하다. 이 환경에는 브로커가 없어 실행하지 못했다.

도입 판단은 출력 위치로 갈린다. 입력과 출력이 모두 Kafka면 컨테이너 트랜잭션과 read_committed의 비용을 감수할 가치가 크다. 출력이 DB나 외부 시스템이면 멱등 소비자와 outbox가 더 싸고 정확하다. 중복 허용에 순서만 중요하면 멱등 프로듀서만으로 충분하다. 트랜잭션은 지연과 LSO 정체 비용을 동반하므로 필요한 경로에만 적용한다.

## 참고

- Apache Kafka 공식 문서, Design 장의 "Message Delivery Semantics"와 Producer Configs (enable.idempotence, transactional.id, transaction.timeout.ms)
- KIP-98: Exactly Once Delivery and Transactional Messaging
- KIP-447: Producer scalability for exactly once semantics
- Spring for Apache Kafka Reference, "Transactions"와 "Exactly Once Semantics" 절
