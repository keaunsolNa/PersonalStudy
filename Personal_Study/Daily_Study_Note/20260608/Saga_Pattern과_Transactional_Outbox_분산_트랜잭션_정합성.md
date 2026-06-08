Notion 원본: https://www.notion.so/3795a06fd6d3817da5e8d76a21ab234e

# Saga Pattern과 Transactional Outbox 분산 트랜잭션 정합성

> 2026-06-08 신규 주제 · 확장 대상: Spring

## 학습 목표

- 마이크로서비스에서 2PC 가 회피되는 이유와 Saga 가 대안이 되는 맥락을 정리한다
- Choreography 와 Orchestration Saga 의 제어 흐름·결합도 차이를 구분한다
- dual-write 문제를 Transactional Outbox + CDC 로 해소하는 메커니즘을 구현 수준으로 설명한다
- 보상 트랜잭션·멱등성·메시지 중복(at-least-once) 처리의 실무 규칙을 적용한다

## 1. 2PC 를 피하는 이유

여러 서비스가 각자의 DB 를 가지는 마이크로서비스에서 분산 트랜잭션의 전통적 해법은 2PC(Two-Phase Commit, XA)다. 그러나 2PC 는 가용성과 결합도 때문에 마이크로서비스에서 거의 쓰이지 않는다. prepare 단계에서 참여자는 락을 잡고 commit 명령을 기다리는데, 코디네이터가 그 사이 죽으면 참여자는 **불확정(in-doubt) 상태로 락을 무한정 붙잡는다**(blocking protocol). 모든 참여 DB 가 XA 를 지원해야 하고, 한 서비스의 지연이 전체 트랜잭션을 멈춘다. CAP 관점에서 2PC 는 일관성을 위해 가용성을 희생한다. 마이크로서비스는 대신 결과적 일관성을 받아들이고 비즈니스 트랜잭션을 일련의 로컬 트랜잭션으로 쪼개는 Saga 를 택한다.

## 2. Saga: 로컬 트랜잭션 + 보상

Saga 는 분산 트랜잭션을 각 서비스의 로컬 트랜잭션 T1..Tn 의 시퀀스로 정의한다. 어느 단계 Ti 가 실패하면, 이미 커밋된 T1..Ti-1 을 되돌리는 **보상 트랜잭션(compensating transaction) Ci-1..C1** 을 역순으로 실행한다. 핵심은 보상이 롤백이 아니라 "의미적 취소"라는 점이다. 이미 커밋되어 다른 트랜잭션에 노출된 데이터를 물리적으로 되돌릴 수 없으므로 "결제 취소", "재고 복원" 같은 새 트랜잭션으로 효과를 상쇄한다. 이 때문에 Saga 는 **격리성(Isolation)을 포기**한다. 중간 상태가 외부에 보일 수 있으므로 semantic lock, commutative update, pessimistic view 같은 countermeasure 를 설계에 넣는다.

## 3. Choreography vs Orchestration

| 구분 | Choreography | Orchestration |
|---|---|---|
| 제어 주체 | 각 서비스가 이벤트에 반응 | 중앙 오케스트레이터 |
| 결합도 | 느슨하나 흐름이 분산·암묵적 | 중앙 집중, 흐름 가시적 |
| 디버깅 | 흐름 추적 어려움 | 한 곳에서 상태 추적 |
| 적합 규모 | 2~3 단계 단순 흐름 | 다단계·복잡 분기 |

단계가 3개를 넘거나 보상 분기가 복잡하면 Orchestration 이 유지보수에 유리하다.

```java
public class OrderSagaOrchestrator {
    public void handle(SagaEvent event) {
        switch (state) {
            case PAYMENT_PENDING -> {
                if (event instanceof PaymentReserved) {
                    send(new DeductStockCommand(orderId, items));
                    state = STOCK_PENDING;
                } else if (event instanceof PaymentFailed) {
                    send(new CancelOrderCommand(orderId));
                    state = FAILED;
                }
            }
        }
    }
}
```

## 4. Dual-Write 문제

Saga 의 각 단계는 "로컬 DB 커밋 + 다음 단계를 알리는 메시지 발행"을 한다. DB 커밋과 메시지 브로커 발행은 서로 다른 시스템이라 하나의 원자적 트랜잭션으로 묶이지 않는다. 이것이 **dual-write 문제**다.

```java
@Transactional
public void createOrder(Order order) {
    orderRepository.save(order);        // (1) DB 커밋
    kafkaTemplate.send("orders", event); // (2) 브로커 발행
}
```

(1)과 (2) 사이에 프로세스가 죽으면 DB 에는 주문이 있는데 이벤트는 안 나간다. `@Transactional` 은 DB 트랜잭션만 관할하므로 Kafka 발행을 보호하지 못한다.

## 5. Transactional Outbox

해법은 **메시지 발행을 DB 트랜잭션 안으로 끌어들이는 것**이다. 비즈니스 데이터와 함께 발행할 이벤트를 같은 DB 의 `outbox` 테이블에 같은 트랜잭션으로 INSERT 한다.

```java
@Transactional
public void createOrder(Order order) {
    orderRepository.save(order);
    outboxRepository.save(new OutboxEvent(
        UUID.randomUUID(), "Order", order.getId(),
        "OrderCreated", toJson(order)));
}
```

별도 프로세스가 outbox 를 읽어 발행한다. **Polling Publisher** 는 주기적으로 미발행 행을 SELECT 한다(단순, 폴링 지연). **CDC** 는 DB 트랜잭션 로그(WAL/binlog)를 tailing 해 outbox INSERT 를 감지한다. Debezium 이 대표적이며 거의 실시간이고 DB 부하가 낮다.

## 6. At-Least-Once 와 멱등성

Outbox + 브로커는 거의 항상 **at-least-once** 다. 발행 후 ack 유실, CDC 재시작, 컨슈머 재처리로 같은 메시지가 두 번 이상 도착할 수 있다. 모든 컨슈머는 **멱등**해야 한다. 가장 견고한 방법은 처리한 `event_id` 를 기록하는 **inbox(processed-messages) 테이블**이다.

```java
@Transactional
public void onStockDeducted(StockDeductedEvent e) {
    if (processedRepository.existsById(e.getEventId())) return;
    processedRepository.save(new ProcessedEvent(e.getEventId()));
    // ... 실제 비즈니스 처리 (같은 트랜잭션)
}
```

`event_id` 중복 체크와 비즈니스 처리를 같은 트랜잭션에 묶어야 체크 통과 후 처리 전 크래시 틈을 막는다.

## 7. 설계 체크리스트

각 단계의 보상이 의미적으로 정의되는가, 보상 불가능한 단계는 흐름 마지막에 배치했는가(pivot transaction), semantic lock 으로 중간 상태 노출을 통제하는가, 모든 메시지 핸들러가 멱등한가, Saga 상태를 영속화해 재시작 후 복구되는가. 이 다섯이 충족되지 않으면 Saga 는 결과적 일관성이 아니라 결과적 비일관성을 만든다.

## 8. Saga 상태 영속화와 복구

오케스트레이터가 메모리에만 상태를 들면 재시작 시 진행 중 Saga 가 유실된다. 상태(현재 단계, 누적 데이터, 보상 대상)를 DB 에 영속화하고 각 전이를 같은 트랜잭션으로 저장하면, 크래시 후 미완료 Saga 를 로드해 이어가거나 타임아웃된 것을 보상 처리한다.

```java
@Entity
public class SagaInstance {
    @Id private String sagaId;
    @Enumerated(EnumType.STRING) private SagaState state;
    private String payload;
    private Instant lastTransition;
    @Version private long version;   // 낙관적 락
}
```

`@Version` 낙관적 락은 같은 Saga 에 두 이벤트가 동시 도착할 때 이중 전이를 막는다. 별도 스케줄러가 `lastTransition` 이 임계를 넘긴 PENDING Saga 를 스캔해 타임아웃 보상을 트리거하는 것도 필수다.

## 9. 보상 트랜잭션 설계의 함정

첫째, 보상 자체가 실패할 수 있으므로 보상도 재시도 큐에 넣고 멱등하게 만들어 성공할 때까지 백오프 재시도한다. 둘째, 되돌릴 수 없는 작업(이메일·배송)은 pivot transaction 이후 retriable 구간에 배치한다. pivot 이전은 모두 compensatable, 이후는 모두 retriable 이어야 한다. 셋째, 이미 PENDING 으로 노출된 상태의 가시성을 UX 차원에서 정의한다.

| 단계 분류 | 위치 | 실패 시 동작 |
|---|---|---|
| Compensatable | pivot 이전 | 역순 보상 실행 |
| Pivot | 경계 지점 | 이후 성공 보장의 분기점 |
| Retriable | pivot 이후 | 성공할 때까지 재시도 |

## 10. Outbox 운영: 정리와 순서 보장

발행 완료 행이 쌓이면 배치로 파티션 단위 정리한다. CDC 방식에서는 INSERT 후 같은 트랜잭션에서 DELETE 해도 WAL 에 INSERT 가 남아 발행되므로 테이블에 데이터를 남기지 않는 패턴이 가능하다. 순서는 같은 애그리거트(orderId)를 Kafka 파티션 키로 써서 파티션 내 순서로 인과성을 유지한다. 서로 다른 애그리거트 간 전역 순서를 강제하면 단일 파티션 병목이 생기므로 보장하지 않는 것이 처리량 측면에서 합리적이다.

## 참고

- Chris Richardson, "Microservices Patterns" — Saga, Transactional Outbox 챕터
- microservices.io: Pattern: Saga / Transactional Outbox / Transaction Log Tailing
- Debezium Documentation: Outbox Event Router
- Pat Helland, "Life Beyond Distributed Transactions"
