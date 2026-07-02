Notion 원본: https://www.notion.so/3915a06fd6d381dd848cd4c802801e2b

# Spring @Transactional 전파 속성과 격리 수준 및 REQUIRES_NEW 물리 커넥션 경계

> 2026-07-02 신규 주제 · 확장 대상: Spring(AOP 프록시·선언적 트랜잭션 학습됨)

## 학습 목표

- 7가지 전파 속성이 물리 커넥션과 논리 트랜잭션에 각각 어떻게 대응되는지 구분한다
- REQUIRES_NEW가 커넥션을 하나 더 점유하는 메커니즘과 그로 인한 풀 고갈·데드락을 추적한다
- 격리 수준별 이상 현상(dirty/non-repeatable/phantom)을 실제 DB 동작과 연결한다
- rollback-only 마킹과 중첩 트랜잭션 예외 처리의 함정을 진단한다

## 1. 논리 트랜잭션과 물리 트랜잭션의 구분

물리 트랜잭션은 실제 DB 커넥션에 걸린 하나의 BEGIN...COMMIT 범위다. 논리 트랜잭션은 `@Transactional`이 붙은 각 메서드 실행 단위다. 전파 속성(propagation)은 새 논리 트랜잭션을 만날 때 기존 물리 트랜잭션을 어떻게 처리할지를 정하는 규칙이다. 기본값 REQUIRED에서는 여러 논리 트랜잭션이 하나의 물리 트랜잭션을 공유한다. 안쪽 메서드는 새 커넥션을 만들지 않고 같은 커넥션에 참여하며, 커밋은 가장 바깥 트랜잭션이 끝날 때 한 번 일어난다.

## 2. 7가지 전파 속성

| 전파 속성 | 기존 트랜잭션 있을 때 | 없을 때 | 물리 커넥션 |
|---|---|---|---|
| REQUIRED | 참여 | 새로 생성 | 공유(기본) |
| REQUIRES_NEW | 기존 보류, 새 트랜잭션 | 새로 생성 | 별도 커넥션 |
| NESTED | savepoint 생성 | 새로 생성 | 공유(savepoint) |
| SUPPORTS | 참여 | 트랜잭션 없이 실행 | 조건부 |
| NOT_SUPPORTED | 기존 보류, 논트랜잭션 | 논트랜잭션 | 보류 |
| MANDATORY | 참여 | 예외 | 공유 |
| NEVER | 예외 | 논트랜잭션 | 없음 |

NESTED는 같은 물리 커넥션 위에 savepoint를 만들어 부분 롤백만 가능하게 하고 바깥이 롤백되면 안쪽도 함께 사라진다. 반면 REQUIRES_NEW는 별개 커넥션을 잡아 독립 커밋하므로 바깥이 롤백돼도 안쪽 커밋은 남는다.

## 3. REQUIRES_NEW의 커넥션 점유와 풀 고갈

REQUIRES_NEW는 바깥 트랜잭션의 커넥션을 반납하지 않고 보류한 채 새 커넥션을 하나 더 획득한다. 즉 한 스레드가 커넥션을 2개 점유한다.

```java
@Service
public class OrderService {

    @Transactional // 바깥: 커넥션 A 점유
    public void placeOrder(final Order order) {
        orderRepository.save(order);
        auditService.writeLog(order.getId()); // 커넥션 B 추가 점유
    }
}

@Service
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLog(final Long orderId) {
        auditRepository.save(new AuditLog(orderId));
    }
}
```

풀 크기가 10인데 동시 요청 10건이 모두 placeOrder 안에서 writeLog를 호출하면, 10개 스레드가 각각 A를 쉠 채 B를 요청해 서로를 기다리는 커넥션 풀 데드락이 발생한다. 해결 원칙은 풀 크기를 동시성×2로 감당하거나, 독립 커밋이 필요한 로직을 커밋 이후 이벤트로 분리하는 것이다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPlaced(final OrderPlacedEvent event) {
    auditRepository.save(new AuditLog(event.orderId()));
}
```

## 4. 격리 수준과 이상 현상

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read |
|---|---|---|---|
| READ_UNCOMMITTED | 발생 | 발생 | 발생 |
| READ_COMMITTED | 방지 | 발생 | 발생 |
| REPEATABLE_READ | 방지 | 방지 | 발생(InnoDB는 갑락으로 방지) |
| SERIALIZABLE | 방지 | 방지 | 방지 |

격리 수준의 실제 동작은 DBMS마다 다르다. Oracle은 REPEATABLE_READ를 지원하지 않고 MVCC로 non-repeatable read를 스냅샷으로 방지하며, MySQL InnoDB의 기본은 REPEATABLE_READ이고 next-key lock으로 팬텀까지 상당 부분 막는다. 따라서 대상 DB의 구현을 함께 확인해야 한다.

## 5. rollback-only 전파와 UnexpectedRollbackException

REQUIRED로 참여한 안쪽 트랜잭션에서 예외가 나 롤백이 표시되면, 그 표시는 공유 물리 트랜잭션 전체에 rollback-only로 각인된다. 바깥에서 그 예외를 catch해도 커밋 시점에 Spring은 rollback-only 마크를 발견하고 UnexpectedRollbackException을 던진다.

```java
@Transactional
public void outer() {
    try {
        inner(); // 내부 런타임 예외 → rollback-only 마킹
    } catch (final RuntimeException e) {
        log.warn("무시 시도", e); // 삼켜도 커밋 때 예외
    }
}
```

이 상황을 격리하려면 inner()를 REQUIRES_NEW나 NESTED로 만들어 롤백 경계를 분리해야 한다.

## 6. 프록시 자기호출 함정 재확인

전파 속성은 AOP 프록시를 통과할 때만 적용된다. 같은 클래스 안에서 `this.writeLog()`처럼 직접 호출하면 프록시를 거치지 않으므로 REQUIRES_NEW가 무시되고 바깥 트랜잭션에 묻힌다. 해결책은 자기 주입(self-injection)이나 별도 빈 분리다.

## 7. 실무 점검 체크리스트

트랜잭션 장애를 진단할 때는 먼저 메서드가 public이며 프록시 경유 호출인지, 전파 속성이 의도한 커넥션 경계와 맞는지, REQUIRES_NEW가 풀 크기 대비 안전한지, 격리 수준이 대상 DB에서 어떤 잠금·스냅샷으로 구현되는지, 예외 catch 지점이 rollback-only와 충돌하지 않는지를 순서대로 확인한다.

## 8. 읽기 전용 트랜잭션과 flush 모드

`@Transactional(readOnly = true)`는 JPA/Hibernate 레벨에서 영속성 컨텍스트의 flush를 낮춰 dirty checking 스냅샷 비교와 flush를 생략해 대량 조회에서 메모리와 CPU를 아낌다. 일부 드라이버·DB에서는 커넥션을 read-only로 표시해 read replica 라우팅의 근거가 된다.

```java
@Transactional(readOnly = true)
public List<OrderView> findRecentOrders(final Long userId) {
    return orderRepository.findRecentByUser(userId);
}
```

다만 readOnly = true가 물리적 쓰기 방지를 보장하지는 않는다. 또 AUTO 모드에서 Hibernate는 커밋 직전과 관련 엔티티 쿼리 실행 직전에 flush하므로 "저장 후 같은 트랜잭션에서 조회하면 반영된" 동작이 나온다. 이 순서를 모르면 native 쿼리가 미반영 변경을 못 보는 버그를 만난다.

## 9. 결론

전파 속성과 격리 수준은 애노테이션 한 줄로 선언되지만 그 뒤에는 물리 커넥션 점유·보류, savepoint, DB별 잠금 구현이라는 물리 계층이 있다. 애노테이션의 의미를 커넥션 수준으로 번역해 사고하는 습관이 이 주제의 핵심 역량이다.

## 참고

- Spring Framework Reference — Transaction Management, Propagation
- HikariCP — About Pool Sizing (https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- Oracle Database Concepts — Data Concurrency and Consistency
- MySQL Reference Manual — InnoDB Transaction Isolation Levels
