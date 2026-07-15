Notion 원본: https://app.notion.com/p/39e5a06fd6d381d0aa74dfdc217f64f9

# Spring 트랜잭션 전파와 TransactionManager 내부 및 Savepoint

> 2026-07-15 신규 주제 · 확장 대상: Spring(AOP 프록시 / Bean 생명주기)

## 학습 목표

- `AbstractPlatformTransactionManager` 의 `getTransaction` 분기로 7개 전파 속성을 해석한다
- `TransactionSynchronizationManager` 의 ThreadLocal 리소스 바인딩 경로를 추적한다
- `REQUIRES_NEW` 와 `NESTED` 의 커넥션·Savepoint 차이를 실측 기준으로 구분한다
- 롤백 마킹(`rollback-only`)이 전파되어 `UnexpectedRollbackException` 이 되는 과정을 재현한다

## 1. @Transactional 의 실체는 프록시 + 상태 기계

`@Transactional` 은 AOP 프록시가 `TransactionInterceptor` 를 끼워 넣는 것이고, 그 인터셉터는 `PlatformTransactionManager` 를 호출하는 얇은 껍데기다.

```java
// TransactionAspectSupport#invokeWithinTransaction 의 골자
TransactionInfo txInfo = createTransactionIfNecessary(tm, attr, joinpointIdentification);
Object result;
try {
    result = invocation.proceedWithInvocation();   // 실제 비즈니스 메서드
} catch (Throwable ex) {
    completeTransactionAfterThrowing(txInfo, ex);  // 롤백 판정
    throw ex;
} finally {
    cleanupTransactionInfo(txInfo);
}
commitTransactionAfterReturning(txInfo);
return result;
```

프록시 기반이므로 self-invocation 이 무력하다는 점(같은 클래스 내부 호출은 프록시를 거치지 않음)은 이미 AOP 에서 다뢬 내용이고, 여기서 볼 것은 `createTransactionIfNecessary` 안쪽 — 즉 `AbstractPlatformTransactionManager.getTransaction()` 의 분기다. 전파 속성 7개의 의미는 전부 이 메서드 한 곳에서 결정된다.

## 2. 리소스 바인딩 — TransactionSynchronizationManager

Spring 트랜잭션의 기반은 "현재 스레드에 어떤 커넥션이 묶여 있는가" 다.

```java
public abstract class TransactionSynchronizationManager {
    private static final ThreadLocal<Map<Object, Object>> resources =
        new NamedThreadLocal<>("Transactional resources");
    private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations = ...;
    private static final ThreadLocal<String> currentTransactionName = ...;
    private static final ThreadLocal<Boolean> actualTransactionActive = ...;
}
```

`resources` 의 키는 `DataSource` 인스턴스, 값은 `ConnectionHolder`(JDBC) 또는 `EntityManagerHolder`(JPA)다. `DataSourceUtils.getConnection(dataSource)` 는 이 맵을 먼저 조회하고, 있으면 그 커넥션을 재사용한다. 이것이 "같은 트랜잭션 안의 모든 DAO 가 같은 커넥션을 쓴다" 는 보장의 전부다.

```java
// DataSourceUtils#doGetConnection 요약
ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(ds);
if (holder != null && (holder.hasConnection() || holder.isSynchronizedWithTransaction())) {
    holder.requested();
    if (!holder.hasConnection()) {
        holder.setConnection(fetchConnection(ds));
    }
    return holder.getConnection();   // 트랜잭션 커넥션 재사용
}
Connection con = fetchConnection(ds);   // 트랜잭션 없음 → 풀에서 새로 꺼냄
```

여기서 중요한 파생 사실 세 가지가 나온다.

첫째, **트랜잭션은 스레드에 묶인다.** `@Async` 나 새 스레드에서 실행되는 코드는 부모 트랜잭션을 상속받지 않는다. `@Transactional` 메서드 안에서 `CompletableFuture.supplyAsync(() -> repo.save(x))` 를 하면 그 저장은 별개의 트랜잭션(또는 auto-commit)으로 나간다.

둘째, **JdbcTemplate 도 같은 경로를 탄다.** JPA 와 JdbcTemplate 을 섞어도 `DataSource` 가 같고 `JpaTransactionManager` 가 커넥션을 바인딩해 두었다면 한 트랜잭션으로 묶인다. 단 JPA 의 1차 캐시에 있는 변경분이 아직 flush 되지 않았을 수 있으므로 순서 문제는 별개다.

셋째, **가상 스레드에서도 동작한다.** ThreadLocal 이므로 가상 스레드마다 독립적이다. 다만 가상 스레드 수백만 개 × ConnectionHolder 는 의미가 없다 — 커넥션 풀이 상한이므로 결국 `Semaphore` 수준의 경합이 된다.

## 3. getTransaction 의 분기 구조

```java
// AbstractPlatformTransactionManager#getTransaction 요약
Object transaction = doGetTransaction();   // 구현체가 현재 스레드의 리소스 조회

if (isExistingTransaction(transaction)) {
    return handleExistingTransaction(def, transaction, debugEnabled);   // ── (B)
}

// 여기 도달 = 기존 트랜잭션 없음                                        ── (A)
if (def.getPropagationBehavior() == PROPAGATION_MANDATORY) {
    throw new IllegalTransactionStateException("No existing transaction found");
}
if (def.getPropagationBehavior() == PROPAGATION_REQUIRED
        || def.getPropagationBehavior() == PROPAGATION_REQUIRES_NEW
        || def.getPropagationBehavior() == PROPAGATION_NESTED) {
    SuspendedResourcesHolder suspended = suspend(null);
    DefaultTransactionStatus status = newTransactionStatus(def, transaction, true, ...);
    doBegin(transaction, def);   // 실제 커넥션 획득 + setAutoCommit(false)
    prepareSynchronization(status, def);
    return status;
}
// SUPPORTS / NOT_SUPPORTED / NEVER → 트랜잭션 없이 진행
return prepareTransactionStatus(def, null, true, ...);
```

(A) 경로에서 `REQUIRED` / `REQUIRES_NEW` / `NESTED` 가 **완전히 동일하게 동작한다**는 점이 첫 번째 포인트다. 기존 트랜잭션이 없으면 셋 다 그냥 새 트랜잭션을 시작한다. 세 속성의 차이는 오직 (B) — 기존 트랜잭션이 있을 때만 드러난다.

```java
// handleExistingTransaction 요약
switch (def.getPropagationBehavior()) {
    case PROPAGATION_NEVER:
        throw new IllegalTransactionStateException("Existing transaction found");

    case PROPAGATION_NOT_SUPPORTED:
        Object suspended = suspend(transaction);        // 언바인딩 후 보관
        return prepareTransactionStatus(def, null, false, ..., suspended);

    case PROPAGATION_REQUIRES_NEW:
        SuspendedResourcesHolder held = suspend(transaction);
        try {
            return startTransaction(def, transaction, ..., held);  // 새 커넥션 + doBegin
        } catch (RuntimeException | Error ex) {
            resumeAfterBeginException(transaction, held, ex);
            throw ex;
        }

    case PROPAGATION_NESTED:
        if (useSavepointForNestedTransaction()) {
            DefaultTransactionStatus status =
                prepareTransactionStatus(def, transaction, false, false, ...);
            status.createAndHoldSavepoint();            // 같은 커넥션에 SAVEPOINT
            return status;
        }
        return startTransaction(def, transaction, ...); // JTA 경로

    default:   // REQUIRED, SUPPORTS, MANDATORY
        return prepareTransactionStatus(def, transaction, false, ...);  // 그냥 참여
}
```

## 4. REQUIRES_NEW vs NESTED — 실측 차이

두 속성은 "안쪽이 실패해도 바깥은 살아남는다" 는 점이 같아 보이지만 구현이 근본적으로 다르다.

| 항목 | REQUIRES_NEW | NESTED |
|---|---|---|
| 커넥션 | **새로 획득**. 풀에서 하나 더 | 기존 커넥션 재사용 |
| 격리 | 완전 독립 트랜잭션 | 같은 트랜잭션 내부의 Savepoint |
| 바깥 롤백 시 | 안쪽 커밋은 **살아남음** | 안쪽도 함께 롤백 |
| 안쪽 롤백 시 | 바깥 무영향 | Savepoint 까지만 되감김 |
| 안쪽에서 바깥 데이터 조회 | **안 보임**(미커밋) → 락 대기 가능 | 보임(같은 트랜잭션) |
| 지원 | JDBC / JPA / JTA 전부 | JDBC 계열만. **JPA 는 미지원** |
| 커넥션 풀 소모 | 중첩 깊이만큼 배수 | 1개 |

가장 위험한 항목이 마지막에서 두 번째다. `REQUIRES_NEW` 는 커넥션을 하나 더 잡는다. 풀 크기가 10인데 `REQUIRES_NEW` 가 중첩되면 바깥 트랜잭션 10개가 각각 커넥션 1개씩 쥐 채 안쪽 커넥션을 기다리는 **자기 데드락**이 발생한다.

```java
@Transactional                                  // 커넥션 #1 점유
public void placeOrder(Order o) {
    orderRepo.save(o);
    auditService.log("order", o.getId());       // ← 커넥션 #2 요구
}

@Service
class AuditService {
    @Transactional(propagation = REQUIRES_NEW)  // 새 커넥션 필요
    public void log(String type, Long id) { auditRepo.save(new Audit(type, id)); }
}
```

풀 크기 10, 동시 요청 10 → 전부 커넥션 #1 을 잡고 #2 를 기다림 → 타임아웃까지 정지. HikariCP 로그에 다음이 뜼다.

```
HikariPool-1 - Connection is not available, request timed out after 30000ms
(total=10, active=10, idle=0, waiting=10)
```

교과서적 해법은 풀 크기 ≥ (동시 요청 수 × 중첩 깊이) 지만 현실적이지 않고, 실무 해법은 `REQUIRES_NEW` 를 쓰지 않는 것이다. 감사 로그처럼 "메인 트랜잭션과 독립적으로 남아야 하는" 요구는 대개 `@TransactionalEventListener(phase = AFTER_COMPLETION)` 으로 커밋 이후에 처리하는 편이 옳다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
public void onOrderPlaced(OrderPlacedEvent e) {
    auditRepo.save(new Audit("order", e.orderId()));   // 메인 커넥션 반납 후 실행
}
```

`NESTED` 의 제약도 분명하다. `JpaTransactionManager` 는 `useSavepointForNestedTransaction()` 이 true 지만, JPA 구현체가 Savepoint 롤백 시 **1차 캐시(영속성 컨텍스트)를 되감지 못한다.** DB 는 Savepoint 로 되돌아가는데 엔티티 매니저는 롤백된 변경을 여전히 관리 상태로 들고 있어, 이후 flush 에서 다시 나갈 수 있다. 그래서 JPA 환경에서 `NESTED` 는 사실상 금지에 가깝고, `JpaDialect` 가 Savepoint 를 지원하지 않으면 아예 예외가 난다.

```
NestedTransactionNotSupportedException:
JpaDialect does not support savepoints - check your JPA provider's capabilities
```

`NESTED` 가 유효한 곳은 순수 `DataSourceTransactionManager` + JdbcTemplate 조합, 특히 배치 처리에서 "레코드 단위 실패는 건너뛰고 나머지는 커밋" 하는 패턴이다.

```java
@Transactional
public void importBatch(List<Row> rows) {
    for (Row row : rows) {
        try {
            self.importOne(row);      // NESTED — 실패해도 Savepoint 까지만 되감김
        } catch (DataIntegrityViolationException e) {
            failures.add(row);        // 계속 진행
        }
    }
}

@Transactional(propagation = NESTED)
public void importOne(Row row) { jdbc.update(INSERT_SQL, row.args()); }
```

## 5. rollback-only 전파와 UnexpectedRollbackException

가장 자주 만나는 함정이다. `REQUIRED` 로 참여한 안쪽 메서드에서 예외가 나면, 안쪽은 물리적 롤백을 하지 않는다 — 물리 트랜잭션의 주인이 아니기 때문이다. 대신 **rollback-only 플래그를 세운다.**

```java
// AbstractPlatformTransactionManager#processRollback 요약
if (status.hasSavepoint()) {
    status.rollbackToHeldSavepoint();            // NESTED
} else if (status.isNewTransaction()) {
    doRollback(status);                          // 물리 롤백
} else if (status.hasTransaction()) {
    if (status.isLocalRollbackOnly() || isGlobalRollbackOnParticipationFailure()) {
        doSetRollbackOnly(status);               // ← 플래그만 세움
    }
}
```

바깥이 커밋하려 하면 플래그를 발견하고 롤백한 뒤 예외를 던진다.

```java
// processCommit 요약
if (status.isNewTransaction() && unexpectedRollback) {
    throw new UnexpectedRollbackException(
        "Transaction rolled back because it has been marked as rollback-only");
}
```

재현되는 전형적 코드는 이렇다.

```java
@Transactional
public void outer() {
    try {
        inner();                     // 안에서 예외 → rollback-only 마킹
    } catch (Exception e) {
        log.warn("무시하고 진행", e); // ← 삼켰지만 플래그는 남음
    }
    repo.save(somethingElse);
}   // 커밋 시도 → UnexpectedRollbackException
```

예외를 잡아 처리했는데도 전체가 롤백되고, 스택 트레이스는 커밋 시점을 가리켜 원인 파악이 어렵다. 대응은 세 가지다.

1. **안쪽을 `REQUIRES_NEW` 로** — 물리적으로 분리. 단 §4 의 커넥션 비용
2. **`noRollbackFor` 지정** — 해당 예외로는 마킹하지 않음
3. **`globalRollbackOnParticipationFailure = false`** — 참여 실패가 전역 마킹을 못 하게 함(권장하지 않음. 데이터 일관성 위험)

```java
@Transactional(noRollbackFor = OptionalStepException.class)
public void inner() { ... }
```

```java
// 3번 — 전역 설정. 신중히
@Bean
public PlatformTransactionManager txManager(DataSource ds) {
    var tm = new DataSourceTransactionManager(ds);
    tm.setGlobalRollbackOnParticipationFailure(false);
    return tm;
}
```

현재 상태를 코드에서 확인하려면:

```java
boolean marked = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
```

## 6. 롤백 규칙 — checked vs unchecked

기본 규칙은 `RuntimeException` 과 `Error` 만 롤백, checked exception 은 **커밋**이다. EJB 관례를 이어받은 것으로, 직관과 어깋나 사고를 만든다.

```java
@Transactional
public void transfer() throws InsufficientFundsException {
    accountRepo.debit(from, amount);
    if (balance < 0) {
        throw new InsufficientFundsException();   // checked → 커밋됨!
    }
}
```

`rollbackFor` 를 명시해야 한다.

```java
@Transactional(rollbackFor = Exception.class)
```

판정은 `RuleBasedTransactionAttribute` 가 수행하며, 예외 클래스 이름의 **부분 문자열 매칭 깊이**로 가장 가까운 규칙을 고른다. `rollbackFor` 와 `noRollbackFor` 가 충돌하면 상속 거리가 짧은 쪽이 이긴다.

## 7. 트랜잭션 동기화 콜백

커밋 전후에 훅을 걸려면 `TransactionSynchronization` 을 등록한다. 캐시 무효화, 메시지 발행, 파일 정리 같은 "DB 커밋이 확정된 뒤에만 해야 하는" 작업의 정석이다.

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
        @Override public void beforeCommit(boolean readOnly) { /* flush 전 */ }
        @Override public void afterCommit() { cache.evict(key); }
        @Override public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) { compensate(); }
        }
    });
```

`afterCommit` 에서 던진 예외는 **트랜잭션을 되돌리지 못한다.** 이미 커밋됐기 때문이다. 그래서 여기서는 예외를 삼키고 별도 재시도 큐로 보내는 것이 안전하다. 또한 `afterCommit` 시점에는 트랜잭션 리소스가 아직 바인딩되어 있지만 커밋된 상태이므로, 여기서 DB 쓰기를 하면 그 쓰기는 트랜잭션 밖에서 auto-commit 으로 나간다.

Spring 은 이 메커니즘을 `@TransactionalEventListener` 로 감싸 제공한다.

| phase | 시점 | 용도 |
|---|---|---|
| `BEFORE_COMMIT` | flush 후, 커밋 전 | 검증. 여기 예외는 롤백시킴 |
| `AFTER_COMMIT` (기본) | 커밋 직후 | 알림, 캐시 무효화, 메시지 발행 |
| `AFTER_ROLLBACK` | 롤백 직후 | 보상 로직 |
| `AFTER_COMPLETION` | 커밋/롤백 무관 | 정리 |

`AFTER_COMMIT` 리스너 안에서 `@Transactional` 을 쓰려면 `REQUIRES_NEW` 가 필요하다. 기존 트랜잭션은 이미 완료되어 참여할 대상이 없기 때문이다.

## 8. 진단 체크리스트

문제 상황별 확인 순서를 고정해 두면 시간이 절약된다.

```properties
# 어떤 전파로 무슨 결정이 났는지 전부 로그로 나옴
logging.level.org.springframework.transaction=TRACE
logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG
logging.level.org.springframework.jdbc.datasource.DataSourceTransactionManager=DEBUG
```

```
Creating new transaction with name [app.OrderService.placeOrder]:
  PROPAGATION_REQUIRED,ISOLATION_DEFAULT
Acquired Connection [HikariProxyConnection@123 wrapping ...] for JDBC transaction
Participating in existing transaction            ← REQUIRED 참여
Suspending current transaction, creating new transaction with name [...]  ← REQUIRES_NEW
Creating nested transaction with name [...]      ← NESTED
Participating transaction failed - marking existing transaction as rollback-only  ← 범인
```

마지막 줄이 보이면 §5 의 시나리오다. `Suspending current transaction` 이 예상보다 자주 보이면 `REQUIRES_NEW` 남용이고, 커넥션 풀 고갈을 의심할 근거가 된다. 트랜잭션 경계가 예상과 다르면 대개 self-invocation 이거나 `private`/`final` 메서드라 프록시가 적용되지 않은 경우다.

## 참고

- Spring Framework Reference — Data Access / Transaction Management: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- `AbstractPlatformTransactionManager` 소스: https://github.com/spring-projects/spring-framework/blob/main/spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java
- Spring Reference — Transaction-bound Events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- Vlad Mihalcea, "Spring transaction best practices": https://vladmihalcea.com/spring-transaction-best-practices/
- HikariCP — About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
