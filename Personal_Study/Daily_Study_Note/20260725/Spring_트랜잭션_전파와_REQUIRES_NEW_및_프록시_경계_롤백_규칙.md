Notion 원본: https://app.notion.com/p/3a85a06fd6d38185b1eff542ab13507d

# Spring 트랜잭션 전파와 REQUIRES_NEW 및 프록시 경계 롤백 규칙

> 2026-07-25 신규 주제 · 확장 대상: Spring(선언적 트랜잭션·AOP 프록시)

## 학습 목표

- 일곱 가지 전파 속성(propagation)이 물리 트랜잭션과 논리 트랜잭션을 어떻게 매핑하는지 구분한다.
- `REQUIRES_NEW`가 별도 커넥션·savepoint 없이 완전히 독립된 트랜잭션을 여는 조건과 커넥션 풀 고갈 위험을 파악한다.
- `NESTED`가 JDBC savepoint로 부분 롤백을 구현하는 방식과 `REQUIRES_NEW`와의 차이를 정리한다.
- 프록시 경계·자기 호출·롤백 규칙(checked vs unchecked)이 트랜잭션 경계에 미치는 영향을 설명한다.

## 1. 물리 트랜잭션 vs 논리 트랜잭션

Spring의 선언적 트랜잭션은 `@Transactional` 메서드 호출마다 "논리 트랜잭션"을 하나 연다. 그러나 실제 DB 커넥션의 커밋/롤백 단위인 "물리 트랜잭션"은 전파 속성에 따라 여러 논리 트랜잭션이 공유하거나 분리한다. 이 두 층위의 구분이 전파를 이해하는 출발점이다.

기본값 `REQUIRED`는 진행 중인 물리 트랜잭션이 있으면 참여하고 없으면 새로 연다. 바깥 서비스가 트랜잭션을 열고 내부 서비스도 `REQUIRED`이면, 둘은 **하나의 물리 트랜잭션**을 공유한다. 이때 내부에서 예외가 나면 전체가 롤백된다 — 논리 트랜잭션은 둘이지만 물리 트랜잭션은 하나이므로 부분 커밋이 불가능하다.

```java
@Service
public class OrderService {
    @Transactional  // REQUIRED
    public void placeOrder(Order order) {
        orderRepository.save(order);
        pointService.deductPoints(order.getUserId(), order.getAmount());
        // deductPoints 가 REQUIRED 이면 같은 물리 트랜잭션 참여
    }
}
```

## 2. 전파 속성 일곱 가지

전파 속성은 "기존 트랜잭션이 있을 때"와 "없을 때" 각각의 동작을 정의한다.

| 속성 | 기존 트랜잭션 있음 | 기존 트랜잭션 없음 |
|---|---|---|
| REQUIRED | 참여 | 새로 생성 |
| REQUIRES_NEW | 기존 보류·새 물리 트랜잭션 | 새로 생성 |
| NESTED | savepoint 생성 | 새로 생성 |
| SUPPORTS | 참여 | 트랜잭션 없이 실행 |
| NOT_SUPPORTED | 기존 보류·비트랜잭션 실행 | 비트랜잭션 실행 |
| MANDATORY | 참여 | 예외 |
| NEVER | 예외 | 비트랜잭션 실행 |

`MANDATORY`는 반드시 상위 트랜잭션 안에서만 호출돼야 하는 메서드(예: 트랜잭션 경계를 스스로 관리하지 않는 도메인 로직)에 방어적으로 쓴다. `NEVER`는 트랜잭션 안에서 절대 실행되면 안 되는 작업(예: 오래 걸리는 외부 API 호출)에 쓴다. `SUPPORTS`/`NOT_SUPPORTED`는 읽기 유틸리티에 가끔 쓰이지만 대부분의 실무 코드는 `REQUIRED`, `REQUIRES_NEW`, `NESTED` 세 가지 안에서 결정된다.

## 3. REQUIRES_NEW — 완전 독립 트랜잭션

`REQUIRES_NEW`는 기존 트랜잭션을 **보류(suspend)** 하고 완전히 새로운 물리 트랜잭션을 연다. 새 트랜잭션은 별도의 DB 커넥션을 사용하며, 독립적으로 커밋/롤백된다. 바깥 트랜잭션이 나중에 롤백돼도 이미 커밋된 `REQUIRES_NEW` 트랜잭션은 되돌아가지 않는다.

전형적 용도는 감사 로그·이력 기록이다. 본 비즈니스 로직이 실패해 롤백되더라도 "시도했다"는 기록은 남겨야 하는 경우다.

```java
@Service
public class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditEntry entry) {
        auditRepository.save(entry);  // 바깥이 롤백돼도 이 기록은 커밋됨
    }
}
```

핵심 위험은 **커넥션 풀 고갈**이다. `REQUIRES_NEW`는 바깥 트랜잭션의 커넥션을 반납하지 않은 채 새 커넥션을 요청한다. 즉 한 스레드가 동시에 두 개 이상의 커넥션을 점유한다. 바깥 트랜잭션이 루프 안에서 `REQUIRES_NEW` 메서드를 호출하면 매 반복마다 보류/재개가 일어나고, 중첩이 깊어지면 한 요청이 여러 커넥션을 물게 되어 HikariCP에서 데드락에 준하는 대기가 발생한다. 실측상 풀 크기 10에서 `REQUIRES_NEW`를 2단계 중첩하며 동시 요청 6건만 들어와도 커넥션 대기 타임아웃(기본 30초)이 관측될 수 있다. 따라서 `REQUIRES_NEW`는 짧고 얕게, 루프 밖에서 사용해야 한다.

## 4. NESTED — savepoint 기반 부분 롤백

`NESTED`는 `REQUIRES_NEW`와 달리 **같은 물리 트랜잭션·같은 커넥션**을 쓰되, JDBC savepoint를 설정한다. 내부에서 예외가 나면 savepoint까지만 롤백하고 바깥 트랜잭션은 계속 진행할 수 있다. 반대로 바깥이 롤백하면 savepoint 이후를 포함해 전체가 롤백된다 — `REQUIRES_NEW`의 독립성과 결정적으로 다른 지점이다.

```java
@Transactional  // 바깥
public void importBatch(List<Row> rows) {
    for (Row row : rows) {
        try {
            processOne(row);  // NESTED
        } catch (Exception e) {
            failedRows.add(row);  // 이 행만 savepoint 롤백, 나머지 배치는 계속
        }
    }
}

@Transactional(propagation = Propagation.NESTED)
public void processOne(Row row) { /* ... */ }
```

`NESTED`는 커넥션을 추가로 점유하지 않으므로 풀 고갈 위험이 없고, 대량 처리 중 "실패한 항목만 건너뛰기"에 적합하다. 단 제약이 있다. savepoint는 JDBC 드라이버가 지원해야 하며, JPA/Hibernate 조합에서는 영속성 컨텍스트 플러시 타이밍과 savepoint 롤백이 어깃나 예상과 다르게 동작할 수 있다. 그래서 JPA 환경에서는 `NESTED`보다 각 항목을 `REQUIRES_NEW`로 분리하거나 배치 프레임워크의 skip 정책을 쓰는 편이 안전한 경우가 많다.

## 5. 프록시 경계와 자기 호출 함정

Spring의 `@Transactional`은 AOP 프록시로 구현된다. 스프링 컨테이너가 대상 빈을 프록시로 감싸고, **프록시를 통한 외부 호출**에서만 트랜잭션 어드바이스가 개입한다. 같은 클래스 안에서 `this.otherMethod()`로 호출하면 프록시를 우회하므로 그 메서드의 `@Transactional`이 무시된다. 이것이 전파 속성이 "안 먹힌는" 가장 흔한 원인이다.

```java
@Service
public class ReportService {
    @Transactional
    public void generate() {
        saveHistory();  // this.saveHistory() — 프록시 우회!
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveHistory() { /* REQUIRES_NEW 가 적용되지 않음 */ }
}
```

위 코드에서 `saveHistory()`는 새 트랜잭션을 열지 못하고 `generate()`의 트랜잭션에 그대로 묶인다. 해결책은 세 가지다. 첫째, 자기 호출 대상을 **별도 빈으로 분리**해 프록시를 통해 호출한다(가장 권장). 둘째, `AopContext.currentProxy()`로 프록시를 명시적으로 얻어 호출한다. 셋째, 자기 주입(self-injection)으로 자신의 프록시를 주입받아 호출한다. 대부분은 첫 번째가 설계상 깔끔하다.

## 6. 롤백 규칙 — 무엇이 롤백을 유발하는가

Spring의 기본 롤백 규칙은 직관과 다를 수 있다. `@Transactional`은 **unchecked 예외(RuntimeException 및 Error)** 에서만 자동 롤백하고, **checked 예외**에서는 커밋한다. 자바 checked 예외를 던지며 롤백을 기대했다가 데이터가 커밋되는 사고가 자주 발생한다.

```java
@Transactional(rollbackFor = Exception.class)  // checked 도 롤백
public void transfer() throws BusinessException { /* ... */ }
```

`rollbackFor`로 롤백 대상 예외를, `noRollbackFor`로 롤백 제외 예외를 지정한다. 또 하나 미묘한 지점은 **rollback-only 마킹의 전파**다. `REQUIRED`로 참여한 내부 트랜잭션에서 예외가 나면 물리 트랜잭션이 rollback-only로 마킹된다. 바깥에서 그 예외를 catch해 계속 진행하려 해도, 커밋 시점에 Spring이 `UnexpectedRollbackException`을 던진다. 이 상황을 피하려면 롤백돼도 무방한 내부 작업은 `REQUIRES_NEW`나 `NESTED`로 물리 트랜잭션을 분리하거나, 예외를 삼키지 말고 전파시켜야 한다.

## 7. 트랜잭션 보류와 스레드 바인딩

전파를 깊이 이해하려면 Spring이 트랜잭션을 **스레드 로컬**에 바인딩한다는 점을 알아야 한다. `TransactionSynchronizationManager`가 현재 스레드에 활성 트랜잭션의 커넥션·동기화 자원을 매단다. `REQUIRES_NEW`가 기존 트랜잭션을 "보류"한다는 것은, 기존 스레드 바인딩 자원을 잠시 스택에 밀어두고 새 자원으로 교체한 뒤, 내부 트랜잭션이 끝나면 원래 자원을 복원한다는 의미다.

이 스레드 바인딩 때문에 생기는 중요한 제약이 있다. `@Async`나 별도 스레드에서 실행되는 작업은 부모의 트랜잭션을 **상속하지 않는다**. 새 스레드는 스레드 로컬이 비어 있으므로 `REQUIRED`라도 완전히 새 트랜잭션을 열기 때문이다. 마찬가지로 리액티브 스택(WebFlux)에서는 스레드가 아니라 Reactor Context로 트랜잭션을 전파하므로 `@Transactional`의 동작 모델 자체가 다르다.

## 8. 트랜잭션 동기화 콜백과 커밋 후 처리

트랜잭션 경계와 관련해 자주 놓치는 지점이 "커밋이 실제로 끝난 뒤"에 후속 작업을 실행하는 것이다. 예를 들어 주문 저장이 커밋된 뒤에만 알림 이벤트를 발행해야 한다. 트랜잭션 안에서 바로 발행하면, 이후 롤백 시 "저장 안 됐는데 알림은 나간" 불일치가 생긴다.

Spring은 `TransactionSynchronization` 콜백 또는 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 이를 해결한다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPlaced(OrderPlacedEvent e) {
    notificationService.send(e);  // 커밋이 성공한 뒤에만 실행
}
```

`AFTER_COMMIT` 리스너는 물리 트랜잭션이 성공적으로 커밋된 후에만 호출되므로, 롤백 시에는 아예 실행되지 않아 불일치를 원천 차단한다. 단 이 리스너 안에서 다시 DB를 쓰려면 이미 트랜잭션이 종료된 상태이므로 새 트랜잭션(`REQUIRES_NEW`)이 필요하다.

이 모든 규칙을 종합하면, 트랜잭션 경계 설계의 실무 체크리스트는 다음과 같다. 전파 속성이 물리 트랜잭션을 공유하는가 분리하는가를 먼저 정하고, 프록시 경계(자기 호출 여부)를 확인하며, 롤백 규칙(checked 예외 포함 여부)을 명시하고, 커밋 후 부수효과는 `AFTER_COMMIT`으로 분리하며, 비동기·리액티브 경계에서는 트랜잭션이 전파되지 않음을 전제한다. 이 다섯 가지를 지키면 선언적 트랜잭션이 "적용될 줄 알았는데 안 됐다"는 부류의 버그 대부분을 예방할 수 있다.

## 참고

- Spring Framework Reference — Transaction Propagation (https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- Spring Framework Reference — Understanding the Spring Framework Transaction Abstraction (https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- HikariCP — About Pool Sizing (https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- Vlad Mihalcea — A beginner's guide to Spring transaction propagation (https://vladmihalcea.com/)
