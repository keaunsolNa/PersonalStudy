Notion 원본: https://www.notion.so/3a25a06fd6d3810e8f61e1c72e750cda

# Spring 이벤트 기반 아키텍처와 ApplicationEventPublisher 및 트랜잭셔널 이벤트 리스너

> 2026-07-19 신규 주제 · 확장 대상: Spring

## 학습 목표

- `ApplicationEventPublisher` 의 발행-구독 흐름과 기본 동기 실행 모델을 설명한다.
- `@TransactionalEventListener` 의 phase 별 실행 시점을 트랜잭션 커밋과 정렬한다.
- 동기·비동기 전환 시의 예외 전파와 트랜잭션 경계 변화를 추적한다.
- 이벤트 도입이 결합도를 낮추는 지점과 추적성을 해치는 지점을 구분한다.

## 1. 왜 이벤트를 쓰는가

주문 생성 후 재고·메일·포인트·색인을 `placeOrder()` 안에서 직접 호출하면 주문 서비스가 네 협력자를 전부 의존한다. 이벤트는 "주문이 생성됐다"는 사실만 발행하고 구독자가 각자 반응하게 해 의존을 뒤집는다.

```java
public record OrderPlacedEvent(Long orderId, Long userId, long amount) {
}

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Long placeOrder(OrderCommand command) {
        Order order = orderRepository.save(Order.from(command));
        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId(), order.getUserId(), order.getAmount()));
        return order.getId();
    }
}
```

Spring 4.2 부터 이벤트는 특정 클래스 상속 없이 `record` 로 충분하다.

## 2. 기본 실행 모델은 동기다

기본값에서 `publishEvent` 는 리스너를 같은 스레드, 같은 트랜잭션 안에서 순차 호출한다. 리스너가 예외를 던지면 발행부로 전파되고 같은 트랜잭션이라 전체가 롤백된다. 즉 기본 이벤트는 트랜잭션 관점에서 강하게 결합된 메서드 호출과 같다.

## 3. @TransactionalEventListener — 커밋 이후로 미루기

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void on(OrderPlacedEvent event) {
    mailService.sendOrderConfirmation(event.userId(), event.orderId());
}
```

| phase | 실행 시점 | 용도 |
|---|---|---|
| AFTER_COMMIT(기본) | 커밋 성공 직후 | 메일, 색인 |
| AFTER_ROLLBACK | 롤백 직후 | 보상, 로그 |
| AFTER_COMPLETION | 완료 후 | 리소스 정리 |
| BEFORE_COMMIT | 커밋 직전 | 커밋 전 검증 |

`AFTER_COMMIT` 리스너는 원 트랜잭션이 커밋을 확정한 뒤 돌아 메일 실패가 주문을 롤백시키지 않는다. 발행이 트랜잭션 안에서 일어났을 때만 동작한다.

## 4. AFTER_COMMIT 의 함정 — 리스너 안의 DB 쓰기

`AFTER_COMMIT` 리스너 안에서 DB 를 쓰면 커밋되지 않는다. 이미 커밋을 마친 트랜잭션이a 새 SQL 을 커밋할 주체가 없다. 해결은 `REQUIRES_NEW` 로 새 트랜잭션을 여는 것이다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void on(OrderPlacedEvent event) {
    auditRepository.save(new AuditLog(event.orderId()));
}
```

Trade-off 는 원자성이 깨진다는 점이다. 후속 실패는 재시도·보상으로 다뤄야 한다.

## 5. 비동기로 전환 — @Async 와 스레드 경계

```java
@Async("eventExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void on(OrderPlacedEvent event) { searchIndexer.index(event.orderId()); }
```

비동기 전환은 예외가 발행부로 전파되지 않고(AsyncUncaughtExceptionHandler 필요), 트랜잭션·SecurityContext·MDC 같은 ThreadLocal 컨텍스트가 새 스레드로 전파되지 않는다(TaskDecorator 필요).

## 6. @Async + AFTER_COMMIT 실행 순서

커밋 완료 → AFTER_COMMIT 발화 → executor 스레드로 즉시 반환. executor 큐(queueCapacity)가 차면 AbortPolicy 가 RejectedExecutionException 을 던져 후속이 유실된다. CallerRunsPolicy 로 백프레셔를 걸거나 아웃박스로 옮긴다.

## 7. 인메모리 이벤트의 한계와 아웃박스

Spring 이벤트는 프로세스 인메모리다. 비동기 리스너 실행 전 앱이 죽으면 이벤트가 사라진다. 유실이 치명적이면 원 트랜잭션 안에서 outbox 테이블에 INSERT 하는 트랜잭셔널 아웃박스를 쓴다.

| 방식 | 유실 위험 | 순서 | 복잡도 |
|---|---|---|---|
| 동기 이벤트 | 없음 | 강함 | 낮음 |
| AFTER_COMMIT 인메모리 | 크래시 시 유실 | 약함 | 낮음 |
| AFTER_COMMIT + @Async | 큐 거부 시 유실 | 없음 | 중간 |
| 아웃박스 | 없음 | 조정 가능 | 높음 |

## 8. 언제 이벤트, 언제 직접 호출

이벤트의 대가는 추적성이다. 구독자가 여러이고 발행부가 그들을 알 필요가 없을 때, 후속 실패가 원 작업을 롤백시키면 안 될 때, 모듈 경계를 넘는 결합을 끊을 때 정당하다. 단일 구독자에 강한 원자성이 필요하면 직접 호출이 읽기 쉽다.

## 참고

- Spring Framework Reference — "Standard and Custom Events", "Transaction-bound Events"
- Spring Framework Javadoc — `@TransactionalEventListener`, `TransactionPhase`
- Spring Framework Reference — "Using @Async"
- microservices.io — "Pattern: Transactional outbox"
- Vlad Mihalcea 블로그 — Outbox pattern
