Notion 원본: https://app.notion.com/p/4135a06fd6d381e9ae01d2e2737a325c

# Spring 트랜잭션 전파 REQUIRES_NEW 함정과 TransactionSynchronization afterCommit 이벤트 발행

> 2026-11-09 신규 주제 · 확장 대상: Spring (트랜잭션 심화)

## 학습 목표

- 프록시 기반 `@Transactional` 의 자기 호출(self-invocation) 무효화를 재현하고 우회한다
- `REQUIRED` 와 `REQUIRES_NEW` 의 커넥션 사용과 롤백 마킹 차이를 분석한다
- `rollback-only` 마킹으로 발생하는 `UnexpectedRollbackException` 원인을 추적한다
- `TransactionSynchronization.afterCommit` 과 `@TransactionalEventListener` 로 커밋 후 부수효과를 안전하게 실행한다

## 1. 프록시 기반 트랜잭션의 한계

Spring 의 `@Transactional` 은 AOP 프록시가 메서드 호출을 가로채 `TransactionInterceptor` 를 실행하는 방식이다. 따라서 **같은 클래스 내부에서 `this.method()` 로 호출하면 프록시를 거치지 않아 어노테이션이 무시된다.**

```java
@Service
public class OrderService {
    public void placeOrder(Order o) {
        save(o);            // 프록시 우회: REQUIRES_NEW 가 적용되지 않음
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Order o) { /* ... */ }
}
```

해결책은 (1) 별도 빈으로 분리, (2) `TransactionTemplate` 으로 프로그램적 경계 지정, (3) 자기 자신을 주입(권장 안 함)이다. 또한 `private`/`final` 메서드와 `public` 이 아닌 메서드에는 기본 설정에서 적용되지 않는다.

## 2. REQUIRED vs REQUIRES_NEW

`REQUIRED`(기본)는 기존 트랜잭션에 참여하고 없으면 새로 만든다. 내부 메서드에서 런타임 예외가 발생해 프록시를 통과하면 **참여 중인 트랜잭션 전체가 rollback-only 로 마킹**된다. 외부에서 예외를 catch 해 정상 반환해도 커밋 시점에 `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` 가 발생한다.

```java
@Transactional
public void outer() {
    try {
        inner.fail();           // REQUIRED, RuntimeException → rollback-only 마킹
    } catch (RuntimeException ignore) { }
    // 여기서 커밋 시도 → UnexpectedRollbackException
}
```

`REQUIRES_NEW` 는 기존 트랜잭션을 일시 중단(suspend)하고 **새 물리 커넥션**을 열어 독립 트랜잭션을 실행한다. 감사 로그처럼 본 작업 실패와 무관하게 남겨야 하는 기록에 적합하다.

## 3. REQUIRES_NEW 의 커넥션 풀 데드락

`REQUIRES_NEW` 는 호출 중 커넥션을 2개 쓴다. HikariCP 최대 풀 크기가 10 이고 동시 요청 10개가 각각 외부 트랜잭션으로 커넥션을 잡은 채 내부에서 두 번째 커넥션을 요청하면, 모두 대기하다 `connectionTimeout`(기본 30초) 후 실패한다. 단일 풀에서 중첩 사용하는 REQUIRES_NEW 는 풀 크기를 `동시요청수 × 2` 이상으로 두거나, 내부 작업을 비동기 큐로 분리해야 한다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000   # 대기를 짧게 해 데드락을 빨리 드러냄
```

## 4. 커밋 후 부수효과: afterCommit

외부 시스템 호출(메시지 발행, 이메일)을 트랜잭션 안에서 실행하면 DB 롤백이 되어도 이미 발송이 끝나 불일치가 생긴다. 커밋이 성공한 뒤에만 실행하려면 `TransactionSynchronizationManager` 를 쓴다.

```java
@Transactional
public void pay(Long orderId) {
    orderRepository.markPaid(orderId);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifier.send(orderId);   // 커밋 성공 후에만 실행
            }
        });
}
```

`afterCommit` 시점에는 트랜잭션이 이미 끝났지만 리소스(커넥션)는 아직 바인딩돼 있으므로, 여기서 DB 쓰기를 하려면 `REQUIRES_NEW` 가 필요하다.

## 5. @TransactionalEventListener

더 선언적인 방법이다. 기본 phase 는 `AFTER_COMMIT` 이며, 트랜잭션이 없으면 이벤트가 버려진다(`fallbackExecution = true` 로 변경 가능).

```java
public record OrderPaid(Long orderId) {}

@Service
@RequiredArgsConstructor
public class PayService {
    private final ApplicationEventPublisher publisher;

    @Transactional
    public void pay(Long id) {
        // ... DB 변경
        publisher.publishEvent(new OrderPaid(id));
    }
}

@Component
public class OrderPaidListener {
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderPaid e) { /* 알림 발송 */ }
}
```

`@Async` 를 함께 쓰면 커밋 후 별도 스레드에서 실행돼 응답 지연이 없다. 단 이 경우 실패해도 원 트랜잭션은 이미 커밋되었으므로 재시도/아웃박스 패턴으로 보완해야 한다.

## 6. 테스트 전략

전파 동작은 통합 테스트로만 검증된다. `@Transactional` 테스트 클래스는 테스트 전체를 감싸 외부 트랜잭션을 만들기 때문에 REQUIRES_NEW 의 독립 커밋 결과를 다른 커넥션에서 조회해야 확인된다. `@SpringBootTest` 에서 `TransactionTemplate` 으로 경계를 직접 만들어 `outer → inner(REQUIRES_NEW)` 케이스에 대해 외부 롤백 후 내부 결과가 남는지 단언한다.

## 7. 선택 가이드

기본은 `REQUIRED`, 감사/로그 등 독립 보존은 `REQUIRES_NEW`(풀 크기 확인 필수), 외부 호출은 `AFTER_COMMIT` + 아웃박스. 이 세 가지로 대부분의 정합성 문제가 정리된다.

## 참고

- Spring Framework Reference: Transaction Management – Declarative, Propagation
- Spring Framework Reference: Transaction-bound Events
- HikariCP Wiki: About Pool Sizing
