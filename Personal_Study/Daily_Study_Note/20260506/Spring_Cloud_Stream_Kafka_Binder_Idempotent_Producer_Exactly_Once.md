Notion 원본: https://www.notion.so/3585a06fd6d381f58e67c16574b1c857

# Spring Cloud Stream Kafka Binder Idempotent Producer와 Exactly Once 트랜잭션

> 2026-05-06 신규 주제 · 확장 대상: Backend

## 학습 목표

- Spring Cloud Stream 의 Functional Programming Model 위에서 Kafka Binder 가 동작하는 흐름을 추적한다
- Idempotent Producer 의 PID, Sequence Number, ProducerEpoch 관리 메커니즘을 정리한다
- Transactional Producer 와 read_committed isolation 으로 Exactly Once Semantics(EOS) 를 만드는 조건을 분석한다
- Consumer 측 DLQ, retry, error handling 정책을 stream binder 설정으로 표현한다

## 1. Spring Cloud Stream — 메시지 추상의 의미

Spring Cloud Stream(SCS)은 Kafka, RabbitMQ, Pulsar 같은 메시지 브로커를 추상화한 framework 이다. 핵심 아이디어는 **binder** — 브로커별 어댑터가 channel 추상을 implement 한다. 애플리케이션 코드는 `Function`, `Consumer`, `Supplier` 빈만 노출하면 SCS 가 binding 을 릺는다.

```java
@SpringBootApplication
public class OrderApp {
    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }

    @Bean
    public Function<OrderCreated, OrderShipped> processOrder() {
        return event -> {
            return new OrderShipped(event.orderId(), Instant.now());
        };
    }
}
```

```yaml
spring:
  cloud:
    stream:
      bindings:
        processOrder-in-0:
          destination: orders.created
          group: order-processor
        processOrder-out-0:
          destination: orders.shipped
      kafka:
        binder:
          brokers: kafka-1:9092,kafka-2:9092,kafka-3:9092
```

`Function<I, O>` 의 입력은 `processOrder-in-0` 으로, 출력은 `processOrder-out-0` 으로 자동 binding 된다. `KStream<K,V>`, `Flux<T>`, `Message<T>` 도 함수 시그니처로 받을 수 있다.

이 함수형 모델의 효용은 코드와 라우팅의 분리다. yaml 만 바꾸면 같은 함수를 다른 토픽으로 다시 binding 할 수 있고, 테스트는 함수만 단위 호출하면 된다.

## 2. Kafka 의 Idempotent Producer 동작 원리

Kafka 0.11 부터 들어온 `enable.idempotence=true` 는 producer 측 재전송에서 발생하는 **중복 메시지를 브로커가 자동으로 제거**하는 기능이다. 동작 원리는 세 식별자에 있다.

| 식별자 | 의미 | 관리 주체 |
|---|---|---|
| Producer ID (PID) | producer 인스턴스의 고유 ID | 브로커가 init 시 발급 |
| Producer Epoch | 같은 transactional.id 의 세대 번호 | 브로커가 fencing 용 발급 |
| Sequence Number | 파티션별 메시지 순번 | producer 가 0부터 증가 |

브로커는 `(PID, partition)` 별로 마지막에 받은 sequence number 를 기억한다. producer 가 같은 메시지를 재전송하면 브로커는 sequence 를 비교하고 이미 처리된 메시지면 ack 만 다시 보낸다. 메시지가 중복으로 로그에 들어가지 않는다.

```properties
enable.idempotence=true
acks=all
retries=Integer.MAX_VALUE
max.in.flight.requests.per.connection=5
```

`acks=all` 은 ISR(In-Sync Replica) 전부에 commit 되어야 ack 가 돌아온다는 뜻. Idempotence 는 `acks=all` 이 강제다. `max.in.flight.requests.per.connection=5` 까지는 Kafka 2.0+ 에서 ordering 보장이 유지된다.

## 3. Transactional Producer — Exactly Once 의 진짜 의미

Idempotence 단독으로는 **at-least-once + 동일 메시지 dedup**까지다. consume → process → produce 사이클에서 producer 가 produce 하기 전에 죽으면 consumer offset commit 과 produce 가 어깄난다. 이걸 EOS 로 묶으려면 `transactional.id` 를 부여한 transactional producer 가 필요하다.

```java
@Bean
public Function<OrderCreated, OrderShipped> processOrder(
        KafkaTransactionManager<String, Object> txManager) {
    return event -> {
        return new OrderShipped(event.orderId(), Instant.now());
    };
}
```

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          transaction:
            transaction-id-prefix: order-tx-
            producer:
              configuration:
                acks: all
                enable.idempotence: true
        bindings:
          processOrder-out-0:
            producer:
              configuration:
                transactional.id: ${spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix}${random.uuid}
```

브로커에는 `__transaction_state` 라는 internal topic 이 있다. transactional.id 별로 begin/commit/abort 가 기록된다. consumer 는 `isolation.level=read_committed` 로 설정하면 abort 된 트랜잭션의 메시지를 건너뛰다.

```yaml
spring:
  cloud:
    stream:
      kafka:
        bindings:
          processOrder-in-0:
            consumer:
              configuration:
                isolation.level: read_committed
```

EOS 구성의 4가지 필수 조건:

1. producer 측 `enable.idempotence=true` + `transactional.id` 지정
2. producer 측 `sendOffsetsToTransaction(offsets, groupMetadata)` 로 source offset 을 트랜잭션에 포함
3. consumer 측 `isolation.level=read_committed`
4. broker `min.insync.replicas ≥ 2` (RF=3 권장) — leader 장애 시 데이터 손실 방지

Spring Cloud Stream + Kafka binder 는 (2)를 자동 처리한다.

## 4. ProducerEpoch 와 Zombie Fencing

같은 `transactional.id` 의 producer 인스턴스가 두 개 살아 있으면 데이터 정합성이 깨진다. Kubernetes 에서 pod 가 graceful shutdown 없이 crash 하고 새 pod 가 같은 transactional.id 로 올라온 시나리오다.

브로커는 `initTransactions` 호출때마다 epoch 를 +1 시킨다. 이전 epoch 의 producer 가 produce 하면 `ProducerFencedException` 으로 거부한다. 살아남은 좍비 producer 는 자살한다.

```java
try {
    producer.initTransactions();
    producer.beginTransaction();
    producer.send(record);
    producer.commitTransaction();
} catch (ProducerFencedException e) {
    producer.close();
    System.exit(1);
}
```

운영 팁: transactional.id 에 `${HOSTNAME}` 또는 deployment 별 고유값을 포함시켜 동일 ID 충돌을 방지한다. 그러나 hostname 기반은 stateful pod 에 유리하고 stateless deployment 라면 random UUID 를 부팅 시 발급해 fence 가 그대로 동작하게 둔다.

## 5. Consumer 측 Error Handling — DLQ 와 Backoff

처리 실패 메시지의 무한 retry 는 partition lag 폭증을 부른다. binder 는 retry 정책과 DLQ(Dead Letter Queue)를 yaml 로 지정한다.

```yaml
spring:
  cloud:
    stream:
      bindings:
        processOrder-in-0:
          consumer:
            max-attempts: 3
            back-off-initial-interval: 1000
            back-off-multiplier: 2.0
            back-off-max-interval: 10000
      kafka:
        bindings:
          processOrder-in-0:
            consumer:
              enable-dlq: true
              dlq-name: orders.created.DLQ
              dlq-producer-properties:
                acks: all
```

3회 retry 가 모두 실패하면 메시지가 `orders.created.DLQ` 토픽으로 이동하고 원본 partition 의 offset 은 commit 된다. DLQ 메시지의 헤더에는 `x-exception-message`, `x-exception-stacktrace`, `x-original-topic`, `x-original-partition`, `x-original-offset` 이 박힌다.

DLQ 메시지를 reprocess 하려면 별도 binding 을 만들고 수동 트리거 또는 admin API 로 다시 흘려보낸다.

```java
@Bean
public Function<Message<OrderCreated>, OrderCreated> reprocessDlq() {
    return msg -> {
        OrderCreated payload = msg.getPayload();
        log.warn("Reprocessing DLQ msg: orig-topic={} offset={}",
                msg.getHeaders().get("x-original-topic"),
                msg.getHeaders().get("x-original-offset"));
        return payload;
    };
}
```

## 6. Partition Strategy — Key 기반 라우팅

EOS 와 별개로 동일 key 의 메시지가 같은 partition 으로 가야 ordering 이 보장된다. SCS 의 `partition-key-expression` 으로 SpEL 표현식을 지정한다.

```yaml
spring:
  cloud:
    stream:
      bindings:
        processOrder-out-0:
          producer:
            partition-key-expression: payload.userId
            partition-count: 12
```

`payload.userId` 가 같은 메시지는 항상 같은 partition 으로 간다. consumer side 에서 partition 단위로 single-thread 처리하면 user 단위 ordering 이 보장된다. 단, 신규 partition 추가 시 hash 가 재계산되어 user → partition 매핑이 바뀌는 점은 주의한다.

## 7. EOS 성능 비용 — 실측 수치

EOS 는 공짜가 아니다. 트랜잭션 마커 기록, log flush 강화, isolation.level 필터링이 latency 를 늘린다.

벤치 환경: Kafka 3.7, RF=3, ISR=3, 3-broker cluster, payload 1KB, batch.size=16KB, linger.ms=5

| 설정 | p50 latency | p99 latency | 처리량 |
|---|---|---|---|
| acks=1, no idempotence | 4 ms | 18 ms | 320k msg/s |
| acks=all + idempotence | 9 ms | 35 ms | 240k msg/s |
| transactional EOS | 18 ms | 80 ms | 130k msg/s |

처리량은 약 60% 로 줄고 p99 가 4배 가까이 늘어난다. 결제, 잔액 변동, 주문 상태 전이 같은 진짜 EOS 가 필요한 stream 에만 적용하고, 로그/메트릭 stream 은 idempotent + at-least-once 로 두는 것이 합리적이다.

## 8. 테스트 — Embedded Broker 와 OutputDestination

스프링 클라우드 스트림은 `spring-cloud-stream-test-binder` 라는 in-memory binder 를 제공한다. Kafka 없이 함수 단위 통합 테스트가 가능하다.

```java
@SpringBootTest
@Import(TestChannelBinderConfiguration.class)
class OrderProcessTest {

    @Autowired InputDestination input;
    @Autowired OutputDestination output;

    @Test
    void processOrder_emitsShippedEvent() {
        var event = new OrderCreated("o-1", "u-1", 12000L);
        input.send(MessageBuilder.withPayload(event).build(), "processOrder-in-0");

        Message<byte[]> received = output.receive(1000, "processOrder-out-0");
        assertThat(received).isNotNull();
        var shipped = objectMapper.readValue(received.getPayload(), OrderShipped.class);
        assertThat(shipped.orderId()).isEqualTo("o-1");
    }
}
```

실제 Kafka 와의 통합 검증은 `@EmbeddedKafka` 또는 Testcontainers 의 `KafkaContainer` 로 한다. EOS 시나리오는 Testcontainers 강력 추천 — embedded broker 는 transactional.id 와 `__transaction_state` 동작이 실제 broker 와 완전히 일치하지 않는 케이스가 있다.

## 9. 운영 체크리스트

EOS 구성 시 점검할 사항을 정리한다.

`min.insync.replicas` 가 `replication.factor - 1` 이상인지. RF=3 이면 `min.insync.replicas=2`. 이래야 한 broker 가 죽어도 produce 가 가능하다. RF=3 + ISR=3 강제는 ISR 한 명만 떨어져도 produce 가 멈추므로 운영성이 떨어진다.

`transactional.id` 가 deployment 별 고유한지. fence 가 작동하려면 같은 논리 인스턴스에 같은 ID 가 부여돼야 한다. Kubernetes Deployment 라면 pod ordinal 이 없으므로 instance UUID 를 부팅 시 발급하고 producer close 시 정리한다.

consumer side 가 `isolation.level=read_committed` 인지. 한쪽만 EOS 면 의미가 없다. 다운스트림 consumer 모두 read_committed 가 default 가 되도록 ConfigMap 으로 관리한다.

`__transaction_state` 토픽의 retention 모니터링. transactional.id 가 폭증하면 이 토픽 사이즈가 커진다. `transactional.id.expiration.ms` (기본 7일) 를 줄이거나 ID 재사용 전략을 만든다.

DLQ 토픽의 retention 과 alert. DLQ 가 실시간으로 처리되는 게 아니라면 retention 을 길게(예 30일) 잡고, 메시지 누적량을 alert 한다. DLQ 메시지가 지속적으로 들어온다는 것은 root cause 가 아직 살아 있다는 뜻이다.

## 참고

- Confluent — Idempotent Producer 와 EOS: https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/
- Apache Kafka 공식 docs — Transactions: https://kafka.apache.org/documentation/#semantics
- Spring Cloud Stream Reference: https://docs.spring.io/spring-cloud-stream/reference/index.html
- "Kafka — The Definitive Guide" 2nd ed., Ch. 8 (Exactly-Once Semantics)
- KIP-98 (Exactly Once Delivery and Transactional Messaging) 설계 문서
