Notion 원본: https://www.notion.so/3ae5a06fd6d381cca54ef6ad7c83bbfa

# Spring 트랜잭셔널 이벤트와 @TransactionalEventListener 및 커밋 이후 처리 경계

> 2026-07-31 신규 주제 · 확장 대상: Spring

## 학습 목표

- ApplicationEventPublisher 기반 동기 이벤트가 트랜잭션 경계와 어떻게 얽히는지 파악한다
- @TransactionalEventListener 의 네 가지 phase 별 실행 시점과 실무 선택 기준을 구분한다
- AFTER_COMMIT 리스너에서 새 트랜잭션이 필요한 경우와 그 함정을 코드로 재현한다
- 이벤트 유실·중복 문제를 outbox 패턴으로 보완하는 경계를 설계한다

## 1. 스프링 이벤트의 실행 모델과 트랜잭션의 관계

`ApplicationEventPublisher.publishEvent()` 는 기본적으로 **동기·블로킹**이다. 이벤트를 발행하는 스레드가 그대로 모든 리스너를 순차 호출하고, 리스너가 던진 예외는 발행 지점까지 전파된다. 많은 개발자가 "이벤트니까 비동기이고 트랜잭션과 분리되겠지"라고 오해하지만, 아무 설정이 없으면 리스너는 **발행자와 동일한 스레드, 동일한 트랜잭션** 안에서 실행된다. 즉 리스너에서 예외가 나면 발행 측 트랜잭션이 롤백된다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ApplicationEventPublisher publisher;
    private final OrderRepository orderRepository;

    @Transactional
    public void placeOrder(OrderCommand command) {
        Order order = orderRepository.save(Order.from(command));
        // 아래 발행은 같은 스레드/같은 트랜잭션에서 리스너를 즉시 실행한다
        publisher.publishEvent(new OrderPlacedEvent(order.getId()));
        // 리스너가 예외를 던지면 이 지점 이후로 전파되어 order 저장도 롤백된다
    }
}
```

이 특성은 양날의 검이다. 도메인 정합성을 하나의 트랜잭션으로 묶고 싶을 때는 유용하지만, "주문은 반드시 저장되고 알림 실패는 무시" 같은 요구에서는 오히려 방해가 된다. 그래서 **리스너 실행 시점을 트랜잭션 커밋 전후로 제어**하는 `@TransactionalEventListener` 가 필요해진다.

## 2. @TransactionalEventListener 의 네 가지 phase

`@TransactionalEventListener(phase = ...)` 는 현재 스레드에 **활성 트랜잭션이 있을 때** 리스너 실행을 트랜잭션 동기화 콜백에 등록한다. phase 는 `TransactionPhase` enum 으로 네 가지다.

| phase | 실행 시점 | 대표 용도 |
|---|---|---|
| BEFORE_COMMIT | 커밋 직전(flush 이후) | 커밋 전 최종 검증, 감사 로그 적재 |
| AFTER_COMMIT (기본값) | 커밋 성공 직후 | 알림 발송, 캐시 무효화, 외부 시스템 통지 |
| AFTER_ROLLBACK | 롤백 직후 | 실패 보상, 실패 메트릭 기록 |
| AFTER_COMPLETION | 커밋/롤백 무관 완료 후 | 리소스 정리, 공통 후처리 |

가장 많이 쓰는 것은 `AFTER_COMMIT` 이다. 데이터가 실제로 영속화된 뒤에만 외부 부수효과를 일으키므로, "DB 는 롤백됐는데 고객에게 결제 완료 문자만 나가는" 사고를 원천 차단한다.

```java
@Component
public class OrderEventHandler {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAfterCommit(OrderPlacedEvent event) {
        // 이 지점에서는 order 가 확실히 커밋된 상태다
        notificationClient.sendOrderConfirmed(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handleRollback(OrderPlacedEvent event) {
        meterRegistry.counter("order.rollback").increment();
    }
}
```

## 3. 활성 트랜잭션이 없을 때의 함정

`@TransactionalEventListener` 는 **트랜잭션 동기화가 활성 상태일 때만** 콜백을 등록한다. 트랜잭션 밖에서 이벤트가 발행되면 리스너는 **아예 호출되지 않는다**(기본 동작). 테스트 코드나 배치 초기화에서 트랜잭션 없이 발행했다가 "리스너가 안 탄다"고 헤매는 전형적인 원인이다.

이를 완화하려면 `fallbackExecution = true` 를 준다. 그러면 활성 트랜잭션이 없을 때 리스너를 **즉시 실행**한다.

```java
@TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true) // 트랜잭션 없으면 즉시 실행
public void handle(OrderPlacedEvent event) {
    notificationClient.sendOrderConfirmed(event.orderId());
}
```

단 `fallbackExecution` 을 켜면 "커밋 후"라는 보장이 사라지므로, 트랜잭션 유무에 따라 실행 의미가 달라진다는 점을 문서화해야 한다.

## 4. AFTER_COMMIT 에서의 DB 쓰기와 REQUIRES_NEW

AFTER_COMMIT 리스너 안에서 다시 DB 에 뭔가를 쓰고 싶은 경우가 많다(예: 발송 이력 저장). 그런데 이 시점은 **원본 트랜잭션이 이미 커밋된 이후**라, 리스너가 참여할 활성 트랜잭션이 없다. 리스너 안에서 그냥 repository 를 호출하면 트랜잭션 없이 실행되거나, 영속성 컨텍스트가 닫혀 `LazyInitializationException` 이 터진다.

해결책은 리스너 메서드에 **새 트랜잭션을 명시적으로 시작**하는 것이다.

```java
@Component
@RequiredArgsConstructor
public class OrderNotificationHandler {

    private final NotificationHistoryRepository historyRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 새 물리 트랜잭션
    public void recordAndSend(OrderPlacedEvent event) {
        historyRepository.save(NotificationHistory.of(event.orderId()));
        notificationClient.sendOrderConfirmed(event.orderId());
    }
}
```

주의할 함정이 있다. `@Transactional` 은 프록시 기반이므로 **같은 빈 내부에서 self-invocation** 으로 호출하면 프록시를 우회해 새 트랜잭션이 열리지 않는다. 리스너는 별도 빈으로 분리하거나 프록시를 거쳐 호출해야 한다. 또한 REQUIRES_NEW 는 커넥션 풀에서 커넥션을 하나 더 점유하므로, 원본 트랜잭션이 아직 커넥션을 반납하지 않은 극단적 상황에서는 **풀 고갈 데드락**이 날 수 있다. AFTER_COMMIT 은 원본 커밋 직후이므로 대개 안전하지만, BEFORE_COMMIT 단계에서 REQUIRES_NEW 를 남발하면 커넥션 2개를 동시에 물게 되어 위험하다.

## 5. 비동기 처리와의 결합

알림·통지처럼 응답 지연을 원치 않는 후처리는 `@Async` 로 스레드를 분리한다. `@TransactionalEventListener` 와 `@Async` 를 함께 붙이면 "커밋 후, 별도 스레드"라는 조합이 된다.

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor eventTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("evt-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

@Component
public class AsyncOrderHandler {

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderPlacedEvent event) {
        // 커밋 후 별도 스레드에서 실행. 여기서 던진 예외는 발행자에게 전파되지 않는다
        notificationClient.sendOrderConfirmed(event.orderId());
    }
}
```

여기서 핵심 trade-off 는 **에러 가시성**이다. `@Async` 스레드에서 던진 예외는 호출자에게 전파되지 않고 조용히 사라지므로, `AsyncUncaughtExceptionHandler` 를 등록하거나 리스너 내부에서 try-catch 후 실패 메트릭·재시도 큐에 적재해야 한다. 또한 별도 스레드에는 원본 트랜잭션의 영속성 컨텍스트가 넘어가지 않으므로, 이벤트에는 엔티티가 아니라 **ID 나 값 스냅샷**만 담아야 한다. 엔티티를 통째로 담아 지연 로딩을 시도하면 스레드 경계에서 예외가 난다.

## 6. 이벤트 유실 문제와 outbox 로의 경계 확장

`@TransactionalEventListener(AFTER_COMMIT)` 은 "커밋됐지만 리스너 실행 직전에 애플리케이션이 죽으면" 이벤트가 유실된다. DB 커밋과 외부 발송이 **원자적이지 않기** 때문이다. 결제 완료를 DB 에 기록하고 커밋한 직후 인스턴스가 재시작되면, 알림은 영영 나가지 않는다.

강한 보장이 필요하면 **Transactional Outbox 패턴**으로 경계를 넓힌다. 이벤트를 외부로 바로 보내는 대신, 원본 트랜잭션 안에서 outbox 테이블에 메시지를 **같이 커밋**하고, 별도 릴레이(폴러 또는 CDC)가 outbox 를 읽어 실제로 발송한다.

```java
@Transactional
public void placeOrder(OrderCommand command) {
    Order order = orderRepository.save(Order.from(command));
    // 외부 발송이 아니라, 같은 트랜잭션에서 outbox row 를 저장한다
    outboxRepository.save(OutboxMessage.of(
            "OrderPlaced", order.getId(), toJson(command)));
    // order 와 outbox 가 한 커밋으로 원자적으로 묶인다
}
```

이렇게 하면 "DB 에는 저장됐는데 메시지는 안 나감" 상태가 사라진다. 대신 릴레이가 최소 한 번(at-least-once) 발송하므로 **컨슈머 측 멱등성**이 필수가 된다. 즉 `@TransactionalEventListener` 는 단일 애플리케이션 내의 부수효과 순서 제어에 적합하고, 프로세스·네트워크 장애까지 견뎌야 하는 통합 지점에서는 outbox 로 경계를 옮기는 것이 정석이다. 두 기법은 배타적이지 않다. 애플리케이션 내부 후처리는 트랜잭셔널 이벤트로, 시스템 간 통합은 outbox 로 나누어 쓰는 계층화가 실무에서 가장 견고하다.

## 7. 리스너 순서 제어와 조건부 처리

한 이벤트에 여러 리스너가 걸리면 실행 순서가 문제 된다. 기본 순서는 보장되지 않으므로, 순서가 의미 있으면 `@Order` 로 명시한다. 값이 작을수록 먼저 실행된다.

```java
@Component
public class OrderListeners {

    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateCache(OrderPlacedEvent event) {
        cacheManager.getCache("orders").evict(event.orderId());
    }

    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notify(OrderPlacedEvent event) {
        // 캐시 무효화 이후 알림. 순서 의존성을 @Order 로 명시
        notificationClient.sendOrderConfirmed(event.orderId());
    }
}
```

또한 `@TransactionalEventListener` 는 SpEL `condition` 으로 특정 이벤트만 골라 처리할 수 있다. 이벤트 타입은 같지만 속성값에 따라 분기할 때 유용하다.

```java
@TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        condition = "#event.amount > 100000")   // 고액 주문만 별도 처리
public void auditHighValue(OrderPlacedEvent event) {
    auditService.recordHighValueOrder(event);
}
```

주의점은 `@Order` 가 **동일 phase 내부의 상대 순서**만 제어한다는 것이다. phase 자체가 다르면(BEFORE_COMMIT vs AFTER_COMMIT) phase 규칙이 우선한다. 그리고 여러 리스너 중 하나가 AFTER_COMMIT 에서 예외를 던지면, 그 예외는 이미 커밋된 트랜잭션을 되돌리지 못한다(커밋은 끝났다). 대신 이후 리스너 실행이 중단될 수 있으므로, 각 AFTER_COMMIT 리스너는 자신의 실패를 스스로 격리(try-catch + 실패 큐)해 다른 리스너에 영향을 주지 않도록 설계하는 것이 안전하다. 이 격리 원칙은 "커밋 후 부수효과는 서로 독립"이라는 사고에서 나온다.

## 참고

- Spring Framework Reference: Transaction-bound Events (docs.spring.io)
- Spring `@TransactionalEventListener` / `TransactionPhase` Javadoc
- Chris Richardson, "Microservices Patterns" — Transactional Outbox / Polling Publisher
- Spring Blog: Better application events in Spring Framework 4.2
