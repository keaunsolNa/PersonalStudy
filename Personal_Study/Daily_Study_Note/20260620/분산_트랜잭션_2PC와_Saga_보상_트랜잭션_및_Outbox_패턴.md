Notion 원본: https://app.notion.com/p/3855a06fd6d3817b9356d2477ec58945

# 분산 트랜잭션 2PC와 Saga 보상 트랜잭션 및 Outbox 패턴

> 2026-06-20 신규 주제 · 확장 대상: 면접을_위한_CS_전공지식_노트

## 학습 목표

- 2PC 의 prepare·commit 단계와 코디네이터 장애 시 블로킹 메커니즘을 설명한다
- Saga 의 오케스트레이션과 코레오그래피 방식을 비교하고 보상 트랜잭션을 설계한다
- 메시지 발행과 DB 커밋의 원자성을 Transactional Outbox 로 보장한다
- 멱등성·이중처리·격리 부재 같은 Saga 의 약점을 완화하는 기법을 적용한다

## 1. 분산 트랜잭션이 어려운 이유

단일 DB 트랜잭션은 ACID 를 공짜로 준다. 그러나 주문 서비스·결제 서비스·재고 서비스가 각자 DB 를 가진 마이크로서비스 환경에서는 하나의 비즈니스 작업이 여러 DB 에 걸친다. 이때 "전부 성공하거나 전부 실패" 를 보장하려면 분산 트랜잭션이 필요하다. CAP 정리가 말하듯 네트워크 분단 상황에서 강한 일관성과 가용성을 동시에 가질 수 없으므로, 분산 트랜잭션은 본질적으로 **일관성과 가용성·성능 사이의 타협**이다. 크게 두 갈래가 있다. 즉시 일관성을 강제하는 2PC 계열과, 최종 일관성을 받아들이는 Saga 계열이다.

## 2. 2PC 프로토콜의 동작

Two-Phase Commit 은 코디네이터(트랜잭션 매니저)가 모든 참여자(리소스 매니저)를 두 단계로 조율한다.

```
Phase 1 (Prepare):
  Coordinator → 모든 참여자: "준비됐나?(prepare)"
  각 참여자: 로컬에 변경을 redo/undo 로그로 영속화 + 락 유지, "YES/NO" 응답
Phase 2 (Commit/Abort):
  모두 YES → Coordinator: "commit" 브로드캐스트 → 각 참여자 커밋, 락 해제
  하나라도 NO → Coordinator: "abort" 브로드캐스트 → 각 참여자 롤백
```

prepare 단계에서 YES 를 응답한 참여자는 commit/abort 결정이 올 때까지 **자원 락을 잡은 채 대기** 해야 한다. 여기서 2PC 의 치명적 약점이 나온다. prepare 후 코디네이터가 죽으면, 참여자는 스스로 결정할 수 없어 무기한 블로킹된다(이른바 "in-doubt" 상태). 이 블로킹 동안 잠긴 행은 다른 트랜잭션을 막아 가용성을 떨어뜨린다. 3PC 는 prepare 와 commit 사이에 pre-commit 단계를 넣어 블로킹을 줄이지만, 네트워크 분단에서는 여전히 안전하지 않고 라운드트립이 늘어 실무 채택률이 낮다.

XA 표준은 2PC 를 구현한 인터페이스다. JTA(`UserTransaction`) 로 여러 XA 리소스를 묶을 수 있지만, 분산 락과 코디네이터 단일 장애점 때문에 마이크로서비스에서는 점점 기피된다.

## 3. Saga: 로컬 트랜잭션의 연쇄

Saga 는 전역 트랜잭션을 버리고, 각 서비스의 **로컬 트랜잭션들의 순차 실행** 으로 비즈니스 작업을 구성한다. 단계 T1, T2, ..., Tn 이 차례로 커밋되고, 중간에 Ti 가 실패하면 이미 커밋된 T1..Ti-1 을 되돌리는 **보상 트랜잭션** C(i-1)..C1 을 역순으로 실행한다.

```
정상:   T1(주문생성) → T2(결제승인) → T3(재고차감) → T4(배송예약)
보상:   T3 실패 → C2(결제취소) → C1(주문취소)
```

보상은 물리적 롤백이 아니라 **의미적 역연산** 이다. 결제를 "취소" 하고 재고를 "복원" 하는 새 트랜잭션을 만든다. 따라서 보상 가능한 작업으로 설계해야 한다. 환불 불가능한 외부 호출처럼 되돌릴 수 없는 단계는 Saga 후반에 배치하거나(pivot transaction 이후), 사전 예약-확정 패턴으로 분해한다.

## 4. 오케스트레이션 대 코레오그래피

Saga 조율에는 두 방식이 있다.

```java
// 오케스트레이션: 중앙 오케스트레이터가 상태 기계로 단계를 지시
public class OrderSaga {

    public void on(OrderCreated event) {
        commandGateway.send(new ApprovePaymentCommand(event.orderId(), event.amount()));
    }

    public void on(PaymentApproved event) {
        commandGateway.send(new ReserveStockCommand(event.orderId(), event.items()));
    }

    public void on(StockReservationFailed event) {
        // 보상 시작
        commandGateway.send(new CancelPaymentCommand(event.orderId()));
    }
}
```

오케스트레이션은 흐름이 한곳에 모여 가시성과 디버깅이 좋지만, 오케스트레이터가 새로운 결합점이 된다. 반대로 **코레오그래피** 는 각 서비스가 이벤트를 구독·발행하며 분산적으로 진행한다. 결합은 낮지만 흐름이 이벤트에 흩어져 "지금 어디까지 진행됐는가" 를 추적하기 어렵고 순환 의존이 생기기 쉬우다. 경험칙으로 단계가 4개를 넘고 분기·보상이 복잡하면 오케스트레이션을, 단순 선형 흐름이면 코레오그래피를 택한다.

## 5. 핵심 난제 1: 메시지와 DB 의 원자성

Saga 의 각 단계는 "DB 를 바꾸고 다음 단계로 이벤트/명령을 발행" 한다. 그런데 DB 커밋과 메시지 브로커 발행은 서로 다른 시스템이라 원자적이지 않다. DB 커밋 후 브로커 발행 직전에 프로세스가 죽으면 이벤트가 유실되어 Saga 가 멈춘다. 이 "dual write" 문제의 표준 해법이 **Transactional Outbox** 다.

```sql
-- 비즈니스 변경과 아웃박스 삽입을 같은 로컬 트랜잭션으로 커밋
BEGIN;
  UPDATE orders SET status = 'PAID' WHERE id = 42;
  INSERT INTO outbox(id, aggregate_id, type, payload, created_at)
  VALUES (gen_random_uuid(), 42, 'PaymentApproved', '{"orderId":42}', now());
COMMIT;
```

```java
// 별도 릴레이가 아웃박스를 폴링해 브로커로 발행 후 표시
@Scheduled(fixedDelay = 500)
public void relay() {
    List<OutboxRecord> batch = outboxRepository.findUnpublished(100);
    for (OutboxRecord record : batch) {
        brokerPublisher.publish(record.getType(), record.getPayload());
        outboxRepository.markPublished(record.getId()); // at-least-once
    }
}
```

아웃박스 덕에 메시지 발행은 DB 트랜잭션과 운명을 같이한다. 릴레이는 최소 한 번(at-least-once) 발행을 보장하지만, 발행 후 markPublished 전에 죽으면 **같은 이벤트가 중복 발행** 될 수 있다. 그래서 소비자 멱등성이 필수다. 폴링 대신 Debezium 같은 CDC 로 트랜잭션 로그를 읽어 아웃박스 변경을 발행하면 폴링 지연과 부하를 줄일 수 있다.

## 6. 핵심 난제 2: 멱등성과 중복 처리

at-least-once 환경에서 소비자는 같은 메시지를 두 번 받을 수 있다. 멱등성은 "같은 메시지를 여러 번 처리해도 결과가 한 번 처리한 것과 같다" 는 성질로, 처리한 메시지 ID 를 기록해 보장한다.

```java
@Transactional
public void handle(PaymentApproved event) {
    if (processedRepository.existsById(event.messageId())) {
        return; // 이미 처리됨 → 무시
    }
    inventoryService.reserve(event.orderId(), event.items());
    processedRepository.save(new ProcessedMessage(event.messageId())); // 같은 트랜잭션에 기록
}
```

중복 체크와 비즈니스 변경을 **같은 로컬 트랜잭션** 에 두는 것이 관건이다. 별도 트랜잭션으로 분리하면 체크 후 기록 전 장애 시 중복이 새어 나간다.

## 7. 핵심 난제 3: 격리 부재

Saga 는 단계마다 커밋하므로, Saga 진행 중간 상태가 다른 트랜잭션에 **노출** 된다. 2PC 라면 격리됐을 중간값이 보인다(이른바 "dirty read" 유사 현상). 완화 기법으로는 의미적 락(상태를 PENDING 으로 표시해 다른 작업이 손대지 못하게 함), 교환 가능 업데이트(순서 무관한 가감 연산 설계), 재시도-가능 보상(by value 가 아니라 증분으로 보상) 등이 있다.

```java
// 의미적 락: PENDING 상태로 외부 노출을 막고 보상/확정 시 전이
order.setStatus(OrderStatus.PENDING_PAYMENT); // 다른 작업은 PENDING 을 보고 대기/거부
// ... 결제 성공 시
order.setStatus(OrderStatus.CONFIRMED);
// ... 실패 시 보상
order.setStatus(OrderStatus.CANCELLED);
```

## 8. 선택 기준과 트레이드오프

| 기준 | 2PC(XA) | Saga |
|---|---|---|
| 일관성 | 강한 즉시 일관성 | 최종 일관성 |
| 가용성 | 코디네이터·락으로 저하 | 높음(서비스 독립 커밋) |
| 격리 | 보장 | 부재(애플리케이션이 보완) |
| 복잡도 | 인프라 의존, 코드 단순 | 보상·멱등 설계로 코드 복잡 |
| 확장성 | 낮음 | 높음 |

실무 지침은 명확하다. 같은 조직이 통제하는 소수의 트랜잭셔널 리소스이고 강한 일관성이 필수라면 2PC 가 단순하다. 그러나 서비스 경계를 넘고 가용성·확장성이 중요한 마이크로서비스에서는 Saga + Outbox + 멱등 소비자가 사실상 표준이다. 둘 중 무엇을 쓰든, "롤백" 이 아니라 "보상" 과 "재시도" 라는 사고방식으로 실패 경로를 1급 시민으로 설계하는 것이 분산 트랜잭션 성패를 가른다.

## 9. 상태 기계로서의 Saga 구현

오케스트레이션 Saga 는 본질적으로 **영속 상태 기계** 다. 각 단계의 진행·보상 여부를 DB 에 기록해, 프로세스가 중간에 죽어도 재기동 후 이어서 진행해야 한다. 상태를 메모리에만 두면 장애 시 Saga 가 미아가 된다.

```java
@Entity
public class OrderSagaState {
    @Id private String sagaId;
    @Enumerated(EnumType.STRING)
    private Step currentStep;     // CREATED, PAYMENT_PENDING, STOCK_PENDING, DONE
    @Enumerated(EnumType.STRING)
    private Status status;        // RUNNING, COMPENSATING, COMPLETED, FAILED
    private String lastError;

    enum Step { CREATED, PAYMENT_PENDING, STOCK_PENDING, SHIPPING_PENDING, DONE }
    enum Status { RUNNING, COMPENSATING, COMPLETED, FAILED }
}

@Transactional
public void advance(SagaEvent event) {
    OrderSagaState saga = repository.findById(event.sagaId()).orElseThrow();
    switch (saga.getStatus()) {
        case RUNNING -> handleForward(saga, event);     // 다음 단계 명령 발행
        case COMPENSATING -> handleCompensation(saga, event); // 역순 보상 진행
        default -> { /* 종료 상태 → 무시(멱등) */ }
    }
    repository.save(saga); // 상태 전이를 같은 트랜잭션에 영속화 + 아웃박스 발행
}
```

상태 전이와 다음 명령 발행(아웃박스 삽입)을 같은 로컬 트랜잭션에 묶는 것이 핵심이다. 타임아웃 처리도 필요하다. 외부 서비스가 응답하지 않으면 Saga 가 영원히 PENDING 에 머무르므로, 스케줄러가 일정 시간 지난 미완료 Saga 를 찾아 보상을 트리거하거나 재시도한다.

## 10. 장애 시나리오와 정확도 보장

분산 트랜잭션 설계의 진짜 시험은 "정확히 어떤 순간에 죽으면 어떻게 되는가" 를 모두 따져보는 것이다.

| 장애 지점 | 결과 | 복구 메커니즘 |
|---|---|---|
| 단계 커밋 후, 이벤트 발행 전 | 아웃박스에 이벤트 남음 | 릴레이가 재발행(at-least-once) |
| 이벤트 발행 후, markPublished 전 | 중복 발행 | 소비자 멱등성으로 흡수 |
| 소비자 처리 후, ack 전 | 메시지 재전달 | processed_message 로 중복 차단 |
| 보상 중 프로세스 사망 | 보상 미완료 | 상태 기계 재기동 후 COMPENSATING 이어감 |

이 표가 말하는 핵심 원칙: 분산 트랜잭션은 **at-least-once 전달 + 멱등 처리 = 실질적 exactly-once** 라는 등식 위에 선다. 진짜 exactly-once 전달은 분산 환경에서 불가능에 가깝지만, 중복을 허용하되 멱등으로 흡수하면 결과적으로 한 번 처리한 것과 같아진다. 보상 역시 멱등이어야 한다. "결제 취소" 를 두 번 호출해도 한 번 취소한 것과 같도록, 이미 취소된 상태면 무시하게 설계한다.

## 11. 패턴 선택 의사결정

실무에서 어떤 패턴을 언제 쓰는지 정리하면 이렇다. 단일 서비스·단일 DB 안의 작업이면 그냥 로컬 ACID 트랜잭션을 쓴다. 분산 트랜잭션을 끌어들이는 것 자체가 비용이므로, 경계를 합쳐 분산을 피할 수 있으면 그게 최선이다. 둘째, 같은 팀이 통제하고 즉시 일관성이 법적·금전적으로 필수(예: 회계 원장)이며 참여자가 소수면 2PC/XA 가 단순하다. 셋째, 서비스가 독립 배포·독립 DB 이고 최종 일관성이 허용되면 Saga + Outbox 가 표준이다. 넷째, 이벤트 흐름이 단순 선형이면 코레오그래피, 분기·보상이 복잡하면 오케스트레이션을 택한다.

가장 흔한 안티패턴은 "마이크로서비스라서 무조건 분산 트랜잭션"이다. 서비스 경계를 잘못 그어 하나의 일관성 단위가 여러 서비스에 흩어지면, Saga 의 복잡도를 떠안고도 격리 부재로 버그가 샔다. 분산 트랜잭션이 자주 필요하다는 것은 서비스 경계 설계가 잘못됐다는 신호일 때가 많다. 트랜잭션 경계와 서비스 경계를 일치시키는 것이 분산 트랜잭션을 가장 적게 쓰는 길이다.

## 참고

- Chris Richardson, "Microservices Patterns" — Saga / Transactional Outbox 챕터
- Pat Helland, "Life beyond Distributed Transactions"
- Hector Garcia-Molina & Kenneth Salem, "Sagas" (1987)
- Debezium Documentation — Outbox Event Router
