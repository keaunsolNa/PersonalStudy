Notion 원본: https://www.notion.so/37a5a06fd6d381e1a66ade79b0884666

# Spring Kafka Exactly-Once Semantics와 Transactional Producer / Consumer Rebalance 내부

> 2026-06-09 신규 주제 · 확장 대상: Spring

## 학습 목표

- Kafka의 idempotent producer와 transactional producer가 EOS를 보장하는 메커니즘을 설명한다
- Spring Kafka `KafkaTransactionManager`와 `read_committed`로 read-process-write EOS를 구성한다
- Consumer Group Rebalance의 Eager vs Cooperative 프로토콜 차이를 이해한다
- 트랜잭션 타임아웃, fencing, 좌비 인스턴스 격리 같은 운영 함정을 다룬다

## 1. 메시지 전달 의미론과 중복의 근원

분산 메시징에서 전달 보장은 at-most-once, at-least-once, exactly-once 셋으로 나눠진다. 기본 Kafka 프로듀서는 ack 유실 시 재전송하므로 at-least-once이고, 이때 같은 메시지가 두 번 기록될 수 있다. 컨슈머 측에서도 처리 후 오프셋 커밋 전에 죽으면 재처리가 발생한다. EOS(Exactly-Once Semantics)는 이 두 경로의 중복을 모두 제거하는 것을 목표로 한다.

## 2. Idempotent Producer

EOS의 1단계는 멱등 프로듀서다. `enable.idempotence=true`이면 브로커는 각 프로듀서에 PID(Producer ID)를 부여하고, 파티션별로 단조 증가하는 시퀀스 번호를 추적한다. 재전송으로 동일 시퀀스가 도착하면 브로커가 중복으로 인식해 한 번만 기록한다.

```properties
# 멱등 프로듀서 (Kafka 3.x 부터 기본 활성)
enable.idempotence=true
acks=all
max.in.flight.requests.per.connection=5
retries=2147483647
```

멱등성은 "단일 프로듀서 세션, 단일 파티션" 범위에서만 중복을 막는다. 프로듀서가 재시작해 PID가 바뀌거나 여러 파티션·여러 토픽에 걸친 원자성이 필요하면 트랜잭션이 필요하다.

## 3. Transactional Producer와 원자적 쓰기

`transactional.id`를 설정하면 프로듀서는 여러 파티션·토픽에 대한 쓰기와 컨슈머 오프셋 커밋을 하나의 원자 단위로 묶는다. 브로커의 Transaction Coordinator가 2PC 유사 프로토콜로 트랜잭션 상태(Ongoing → PrepareCommit → CompleteCommit)를 트랜잭션 로그에 기록한다.

```java
@Configuration
public class KafkaTxConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> pf =
            new DefaultKafkaProducerFactory<>(props);
        pf.setTransactionIdPrefix("tx-order-"); // transactional.id 접두사
        return pf;
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTxManager(
            ProducerFactory<String, String> pf) {
        return new KafkaTransactionManager<>(pf);
    }
}
```

`setTransactionIdPrefix`를 설정하면 Spring이 인스턴스·리스너별로 `tx-order-<group>.<topic>.<partition>` 형태의 고유 `transactional.id`를 생성한다. 이 ID가 동일해야 재시작 후에도 같은 트랜잭션 컨텍스트로 fencing이 동작한다.

## 4. Read-Process-Write EOS 구성

가장 흔한 EOS 시나리오는 토픽을 소비해 가공한 뒤 다른 토픽으로 발행하는 파이프라인이다. 컨슈머 오프셋 커밋을 프로듀서 트랜잭션에 포함시켜, "처리 결과 발행"과 "입력 오프셋 전진"이 함께 커밋되거나 함께 롤백되게 한다.

```java
@Component
public class OrderProcessor {

    private final KafkaTemplate<String, String> template;

    public OrderProcessor(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @KafkaListener(topics = "orders-in", groupId = "order-eos")
    @Transactional("kafkaTxManager")
    public void process(ConsumerRecord<String, String> record) {
        String result = transform(record.value());
        // 발행 + 입력 오프셋 커밋이 하나의 Kafka 트랜잭션으로 원자 처리
        template.send("orders-out", record.key(), result);
    }

    private String transform(String raw) {
        return raw.toUpperCase();
    }
}
```

`@Transactional("kafkaTxManager")`로 리스너를 감싸면 Spring Kafka가 컨테이너의 오프셋 커밋을 `producer.sendOffsetsToTransaction()`으로 트랜잭션에 합류시킨다. 컨슈머 측은 반드시 `isolation.level=read_committed`여야 커밋되지 않은(aborted) 메시지를 읽지 않는다.

```properties
# 컨슈머: 커밋된 메시지만 읽기
isolation.level=read_committed
enable.auto.commit=false
```

## 5. read_committed와 LSO

`read_committed` 컨슈머는 LSO(Last Stable Offset)까지만 읽는다. LSO는 아직 진행 중인(open) 트랜잭션의 첫 메시지 직전 오프셋이다. 즉 진행 중 트랜잭션이 있으면 그 뒤 메시지는 커밋 마커가 도착할 때까지 가려진다. 이 때문에 장시간 열린 트랜잭션은 후속 컨슈머의 지연(latency)을 유발한다. 트랜잭션은 짧게 유지해야 하며 `transaction.timeout.ms`(기본 60초)를 넘기면 코디네이터가 강제로 abort 한다.

## 6. Consumer Rebalance: Eager vs Cooperative

컨슈머 그룹 멤버십이 바뀌면(가입/이탈/구독 변경) 파티션을 재분배하는 리밸런스가 일어난다. 전통적 Eager 프로토콜은 "stop-the-world" 방식으로 모든 컨슈머가 파티션을 전부 반납한 뒤 재할당받는다. 그동안 전체 처리가 멈춘다.

```properties
# Cooperative Sticky: 점진적 재분배, 처리 중단 최소화
partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

Cooperative Rebalancing(KIP-429)은 옮길 파티션만 반납하고 나머지는 계속 처리한다. 리밸런스를 2단계(revoke가 필요한 파티션만 회수 → 재참여)로 나눠 정지 시간을 크게 줄인다.

| 항목 | Eager(Range/RoundRobin) | Cooperative Sticky |
|---|---|---|
| 재분배 방식 | 전체 반납 후 재할당 | 변경 파티션만 점진 이동 |
| 처리 중단 | 그룹 전체 정지 | 영향 파티션만 일시 정지 |
| 리밸런스 횟수 | 1회(무거움) | 2회(가벼움) |
| 권장 환경 | 소규모/단순 | 대규모/EOS 파이프라인 |

## 7. Fencing과 좌비 인스턴스 격리

`transactional.id`의 핵심 역할은 좌비 fencing이다. 같은 `transactional.id`로 새 프로듀서가 `initTransactions()`를 호출하면 코디네이터는 epoch를 증가시키고, 이전 epoch를 가진 옆 프로듀서(네트워크 분리됐다 돌아온 "좌비")의 쓰기를 `ProducerFencedException`으로 거부한다. 이로써 GC 멈춤이나 일시적 단절 후 부활한 인스턴스가 중복 메시지를 쓰는 것을 막는다.

```java
// 좌비가 살아 돌아와 send 시도 → 브로커가 epoch 불일치로 거부
// org.apache.kafka.common.errors.ProducerFencedException
// Spring Kafka 컨테이너는 이를 받아 컨슈머를 멈추고 안전하게 재기동
```

따라서 `transactional.id`는 논리적 작업 단위(파티션·리스너)에 안정적으로 고정되어야 한다. Spring의 `transactionIdPrefix`는 이 균형을 자동으로 맞춘다.

## 8. 외부 시스템과의 정합성: EOS의 경계

Kafka EOS는 "Kafka 안에서의" read-process-write에만 원자성을 보장한다. 처리 도중 외부 DB에 쓰기를 하면, 그 DB 쓰기와 Kafka 트랜잭션은 별개라서 한쪽만 성공하는 부분 실패가 가능하다. 이 간극을 메우는 표준 패턴이 Transactional Outbox다. DB 트랜잭션 안에서 비즈니스 변경과 함께 outbox 테이블에 이벤트를 적재하고, 별도 릴레이(Debezium CDC 등)가 outbox를 읽어 Kafka로 발행한다.

```java
@Transactional // DB 트랜잭션: 비즈니스 변경 + outbox insert 가 원자적
public void placeOrder(Order order) {
    orderRepository.save(order);
    outboxRepository.save(OutboxEvent.of("OrderCreated", order)); // 같은 트랜잭션
    // 커밋 후 CDC 릴레이가 outbox → Kafka 로 at-least-once 발행
    // 컨슈머는 멱등 처리(이벤트 ID 중복 제거)로 최종 EOS 효과
}
```

여기서 Kafka 트랜잭션과 DB 트랜잭션을 하나의 분산 2PC로 묶으려는 시도(JTA/XA)는 성능·복잡도 때문에 거의 권장되지 않는다. 대신 outbox + 컨슈머 멱등이라는 "at-least-once + 멱등 = 실질적 exactly-once" 조합이 사실상 표준이다. 즉 Kafka 내부는 EOS로, Kafka와 외부 DB 경계는 outbox로 정합성을 나눠 책임지는 설계가 현실적이다.

## 9. 운영 trade-off

EOS는 강력하지만 공짜가 아니다. 트랜잭션 마커 기록과 코디네이터 왕복으로 처리량이 멱등 전용 대비 통상 수십 퍼센트 감소하고, `read_committed`로 인해 종단 지연이 LSO 진행에 묶인다. 따라서 (1) 금전·재고처럼 중복이 치명적인 도메인에만 EOS를 적용하고, (2) 단순 로그·메트릭은 멱등 프로듀서+at-least-once+컨슈머 멱등 처리로 충분하며, (3) 트랜잭션을 짧게 유지하고 `transaction.timeout.ms`와 `max.poll.interval.ms`를 처리 시간에 맞게 조정하는 것이 핵심이다. 정확성과 처리량·지연은 명확한 trade-off이며, "모든 토픽을 EOS로" 하는 접근은 비용만 키운다.

## 참고

- Apache Kafka 공식 문서 — Transactions / Exactly-Once Semantics (https://kafka.apache.org/documentation/#semantics)
- KIP-98 — Exactly Once Delivery and Transactional Messaging
- KIP-429 — Incremental Cooperative Rebalancing
- Spring for Apache Kafka Reference — Transactions (https://docs.spring.io/spring-kafka/reference/kafka/transactions.html)
