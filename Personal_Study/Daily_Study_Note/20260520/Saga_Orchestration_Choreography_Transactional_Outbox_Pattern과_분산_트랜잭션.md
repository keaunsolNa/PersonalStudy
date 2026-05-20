Notion 원본: https://www.notion.so/3665a06fd6d381e6816cd16ee61ac6cc

# Saga Orchestration vs Choreography — Transactional Outbox Pattern과 보상 트랜잭션

> 2026-05-20 신규 주제 · 확장 대상: 분산 시스템(CS)

## 학습 목표

- Saga 패턴이 2PC를 대체하는 원리적 이유와 long-lived 트랜잭션 모델을 식별한다
- Orchestration·Choreography 스타일의 운영 비용과 가시성 차이를 비교한다
- Transactional Outbox·Inbox·Idempotency Key 세트가 exactly-once 효과를 어떻게 합성하는지 분석한다
- 보상 트랜잭션 설계에서 격리 부재로 생기는 anomaly를 인지하고 회피 전략을 작성한다

## 1. 왜 2PC가 아닌 Saga인가

2-Phase Commit은 모든 참여자가 prepare 단계에서 잠금을 잡고 commit/abort 결정을 기다린다. 마이크로서비스 환경에서는 (1) 참여 서비스가 자율적이며 (2) 트랜잭션이 수 초~수 분까지 길어질 수 있어 2PC의 락 비용이 받아들이기 어렵다.

Saga는 각 서비스의 *로컬 트랜잭션*들을 sequence로 엮고, 중간에 실패하면 *보상 트랜잭션*을 역순으로 실행해 "관찰 가능한" 일관성을 회복한다.

## 2. Orchestration

```
[OrderService(orchestrator)]
   |--CreateOrder(local txn)
   |--cmd--> PaymentService: ChargeCard
   |   <-- PaymentCharged
   |--cmd--> InventoryService: ReserveItems
   |   <-- InventoryReserved
   |--cmd--> ShippingService: ScheduleDelivery
   |   <-- DeliveryScheduled
   |--complete order(local txn)
```

```java
@Saga(stateClass = OrderState.class)
public class OrderSaga {
    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderPlacedEvent e) {
        commandGateway.send(new ChargeCardCommand(e.paymentId(), e.amount()));
    }
    @SagaEventHandler(associationProperty = "orderId")
    public void on(InventoryFailed e) {
        commandGateway.send(new RefundCardCommand(e.paymentId()));
        SagaLifecycle.end();
    }
}
```

장점은 *흐름 가시성*이다. 단점은 orchestrator가 결합점이 된다는 것이다.

## 3. Choreography

```
OrderService    --OrderPlaced-->     PaymentService
PaymentService  --PaymentCharged-->  InventoryService
InventoryService--InventoryReserved->ShippingService
```

운영 경험상 *step 수 3 이하*면 Choreography가 가볍고, *5 이상*이면 Orchestration이 유지보수에 우위다.

## 4. Transactional Outbox

```sql
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP    NULL
);
```

```java
@Transactional
public void placeOrder(OrderCmd cmd) {
    Order o = repository.save(Order.from(cmd));
    outboxRepository.save(new OutboxRow("order.placed",
        new OrderPlaced(o.id(), o.amount())));
}
```

별도 publisher 프로세스(예: Debezium CDC)가 outbox를 읽어 Kafka로 발행한다.

## 5. Inbox·Idempotency Key

```sql
CREATE TABLE inbox (
    message_id  VARCHAR(64),
    consumer    VARCHAR(64),
    processed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, consumer)
);
```

```java
@Transactional
public void handle(OrderPlaced ev) {
    if (!inboxRepository.tryInsert(ev.messageId(), "payment-consumer")) {
        return;
    }
    payments.charge(ev.orderId(), ev.amount());
}
```

핵심은 inbox INSERT와 비즈니스 로직이 같은 DB 트랜잭션 안이라는 점이다.

## 6. 보상 트랜잭션의 이상 현상

- **Dirty Read**: A 서비스가 commit한 중간 상태를 B 서비스가 읽고 의사결정
- **Lost Update**: 보상 트랜잭션 실행 도중 다른 트랜잭션이 같은 row를 갱신
- **Fuzzy Read**: 같은 saga 안에서 두 번 읽은 값이 외부 트랜잭션 때문에 달라짐

```
1) Semantic Lock: 진행 중인 도메인 객체에 'pending' 상태 플래그
2) Commutative Update: 보상이 역연산이 되도록 설계
3) Pessimistic View: 결과를 미리 확정하지 않고 'reserved' 상태
```

가장 자주 쓰이는 패턴은 *reserved + TTL*이다.

## 7. 운영 가시성

```java
public record OrderPlacedEvent(UUID orderId, UUID sagaId, BigDecimal amount,
                               String traceParent) {}
```

OpenTelemetry의 `trace_id`를 `saga_id`로 매핑해 Jaeger UI에서 전체 흐름을 시각화한다.

## 8. 실패·재처리 시나리오

| 시나리오 | 원인 | 대응 |
|---|---|---|
| 보상 트랜잭션도 실패 | 외부 시스템 일시 장애 | DLQ + 운영자 수동 |
| retry timeout | 네트워크 분리 | step별 retry counter |
| 동일 saga가 두 번 시작 | producer 재시도 | Saga ID idempotency |
| Out-of-order 이벤트 | 파티션 | 이벤트 sequence number |
| 보상 후 원본 재발행 | 발행 retry | 이벤트 버전 또는 단조증가 seq |

## 9. 어느 패턴을 언제 쓸 것인가

| 상황 | 권장 |
|---|---|
| 5+ step의 복잡한 흐름 | Orchestration |
| 1-3 step, 자율적 서비스 | Choreography |
| 외부 API 결합 | Orchestration + Outbox |
| 강한 격리 요구 | Saga 대신 단일 트랜잭션 또는 2PC |
| 이벤트 소싱과 결합 | Orchestration with explicit state |
| 마이크로서비스 간 데이터 동기화 | Outbox + Inbox |

Temporal·Camunda 같은 workflow engine은 saga 상태·재시도·visibility를 인프라가 책임지게 만든다. 도입 임계점은 *동시 진행 중 saga 수가 10만+ 또는 step이 평균 7+*에 도달할 때 정량적 이득이 명확해진다.

## 참고

- Chris Richardson, "Microservices Patterns" — Chapter 4
- Microsoft Cloud Design Patterns — Saga, Transactional Outbox
- Hector Garcia-Molina & Kenneth Salem, "Sagas" (1987 ACM SIGMOD)
- Debezium Documentation — Outbox Event Router
- Temporal Documentation — Workflow Patterns
