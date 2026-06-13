Notion 원본: https://www.notion.so/37c5a06fd6d381e398add7cde6586533

# Spring AOP 프록시 내부와 @Transactional propagation 및 self-invocation 한계

> 2026-06-11 신규 주제 · 확장 대상: Spring

## 학습 목표

- JDK 동적 프록시와 CGLIB 프록시의 생성 조건과 동작 차이를 구분한다
- `@Transactional`의 7가지 propagation 동작을 시나리오별로 선택한다
- self-invocation이 트랜잭션·AOP를 무력화하는 원인을 프록시 구조로 설명한다
- 자기호출 한계를 우회하는 방법(자기주입, AspectJ, 구조 분리)을 비교한다

## 1. Spring AOP는 프록시 기반이다

Spring AOP는 바이트코드 위빙이 아니라 런타임 프록시로 동작한다. `@Transactional`, `@Async`, `@Cacheable` 같은 애너테이션은 대상 빈을 감싸는 프록시 객체가 부가 기능(advice)을 실행하고 실제 메서드로 위임하는 방식으로 구현된다. 컨테이너는 빈을 등록할 때 해당 빈이 어드바이스 대상이면 원본 대신 프록시를 빈으로 등록한다.

프록시 종류는 두 가지다. 대상이 인터페이스를 구현하면 기본적으로 JDK 동적 프록시를, 그렇지 않으면 CGLIB 프록시를 만든다. 단, Spring Boot는 2.x부터 `proxyTargetClass=true`가 기본값이라 인터페이스 유무와 무관하게 CGLIB 프록시를 사용한다.

```java
// 트랜잭션 프록시가 의사코드로 하는 일
public class OrderServiceProxy extends OrderService {
    private final OrderService target;
    private final PlatformTransactionManager txManager;

    @Override
    public void placeOrder(Order order) {
        TransactionStatus status = txManager.getTransaction(definition);
        try {
            target.placeOrder(order); // 실제 객체로 위임
            txManager.commit(status);
        } catch (RuntimeException e) {
            txManager.rollback(status);
            throw e;
        }
    }
}
```

## 2. JDK 동적 프록시 vs CGLIB

두 방식의 메커니즘과 제약이 다르다.

| 항목 | JDK 동적 프록시 | CGLIB |
|---|---|---|
| 구현 원리 | `java.lang.reflect.Proxy`로 인터페이스 구현체 생성 | 대상 클래스를 상속한 서브클래스 생성 |
| 전제 조건 | 대상이 인터페이스 구현 | 인터페이스 불필요 |
| 한계 | 인터페이스에 선언된 메서드만 프록시 | `final` 클래스·`final` 메서드 프록시 불가 |
| 생성자 호출 | 대상 생성자 미호출 | 서브클래스라 부모 생성자 1회 호출 |
| 캐스팅 | 인터페이스 타입으로만 | 구체 클래스 타입으로 가능 |

CGLIB는 상속 기반이므로 `final` 메서드나 `private` 메서드는 오버라이드할 수 없어 어드바이스가 적용되지 않는다. 또한 서브클래스가 부모 생성자를 호출하므로, 생성자에 부작용이 있으면 두 번 실행되는 것처럼 보일 수 있다. JDK 프록시는 인터페이스에 없는 public 메서드를 프록시하지 못한다. 어느 쪽이든 "프록시가 가로챌 수 있는 호출"만 어드바이스가 동작한다는 점이 핵심 제약이다.

## 3. @Transactional propagation 7종

propagation은 호출 시점에 이미 트랜잭션이 있을 때와 없을 때 어떻게 동작할지를 정한다.

```java
@Service
public class PaymentService {
    @Transactional(propagation = Propagation.REQUIRED) // 기본값
    public void pay() { /* ... */ }
}
```

- `REQUIRED`(기본): 기존 트랜잭션이 있으면 참여, 없으면 새로 시작. 가장 흔함.
- `REQUIRES_NEW`: 항상 새 물리 트랜잭션 시작. 기존 트랜잭션은 일시 정지(suspend). 로그 기록처럼 외부 실패와 독립적으로 커밋해야 할 때.
- `NESTED`: 기존 트랜잭션 안에서 savepoint를 만든다. 내부만 롤백 가능. JDBC savepoint 지원 시에만 동작(JPA는 제약 있음).
- `SUPPORTS`: 있으면 참여, 없으면 트랜잭션 없이 실행.
- `NOT_SUPPORTED`: 트랜잭션을 정지하고 논트랜잭션으로 실행.
- `MANDATORY`: 반드시 기존 트랜잭션이 있어야 하며, 없으면 예외.
- `NEVER`: 트랜잭션이 있으면 예외.

`REQUIRES_NEW`와 `NESTED`의 차이가 실무에서 중요하다. `REQUIRES_NEW`는 완전히 독립된 트랜잭션이라 바깥이 롤백돼도 안쪽 커밋은 유지된다. `NESTED`는 savepoint라 바깥이 롤백되면 안쪽도 함께 롤백된다. "주문 실패와 무관하게 감사 로그는 남겨야 한다"면 `REQUIRES_NEW`, "부분 작업만 되돌리고 전체 트랜잭션은 살린다"면 `NESTED`다.

## 4. 롤백 규칙과 흔한 함정

기본 롤백 규칙은 unchecked 예외(`RuntimeException`, `Error`)에서만 롤백하고, checked 예외에서는 커밋한다는 점이다. 이는 직관과 어긋나 자주 버그를 만든다.

```java
@Transactional(rollbackFor = Exception.class) // checked 예외도 롤백
public void transfer() throws IOException {
    // ...
}
```

또 흔한 실수는 트랜잭션 메서드 안에서 예외를 try-catch로 삼키는 것이다. 예외를 잡아 로그만 찍고 정상 반환하면 Spring은 예외를 못 보므로 커밋한다. 반대로 `REQUIRES_NEW` 내부 트랜잭션에서 예외가 났는데 호출 측이 그것을 잡아도, 내부 트랜잭션은 이미 롤백 표시(`rollback-only`)가 되어 바깥 커밋 시 `UnexpectedRollbackException`이 날 수 있다.

## 5. self-invocation 문제의 정체

같은 클래스 내 메서드가 다른 메서드를 `this.method()`로 직접 호출하면 트랜잭션/AOP가 적용되지 않는다. 이것이 self-invocation 한계다.

```java
@Service
public class ReportService {
    public void generateAll() {
        for (Report r : reports) {
            this.generateOne(r); // 프록시를 거치지 않음!
        }
    }

    @Transactional
    public void generateOne(Report r) {
        // 이 @Transactional 은 generateAll 경유 호출 시 동작하지 않는다
    }
}
```

원인은 프록시 구조다. 외부 코드가 `reportService.generateAll()`을 호출하면 프록시가 가로채지만, `generateAll` 내부의 `this.generateOne()`은 프록시가 아니라 실제 대상 객체(`this`)를 직접 호출한다. 프록시를 우회하므로 `generateOne`의 `@Transactional`을 처리할 인터셉터가 끼어들 자리가 없다. 같은 이유로 self-invocation으로 호출된 `@Async`, `@Cacheable`도 무력화된다.

## 6. 우회 방법 비교

self-invocation을 피하는 방법은 여러 가지이며 트레이드오프가 다르다.

첫째, **구조 분리**: 트랜잭션 경계가 필요한 메서드를 별도 빈으로 분리한다. 가장 권장되는 방법으로, 책임도 명확해진다.

```java
@Service
public class ReportService {
    private final ReportItemService itemService;

    public void generateAll(List<Report> reports) {
        for (Report r : reports) {
            itemService.generateOne(r); // 다른 빈 → 프록시 경유
        }
    }
}

@Service
public class ReportItemService {
    @Transactional
    public void generateOne(Report r) { /* ... */ }
}
```

둘째, **자기 주입(self-injection)**: 자기 자신의 프록시를 주입받아 호출한다. 동작하지만 순환 의존이라 가독성이 떨어진다.

```java
@Service
public class ReportService {
    @Autowired @Lazy
    private ReportService self; // 프록시 주입

    public void generateAll(List<Report> reports) {
        reports.forEach(self::generateOne); // 프록시 경유
    }

    @Transactional
    public void generateOne(Report r) { /* ... */ }
}
```

셋째, **`AopContext.currentProxy()`**: `@EnableAspectJAutoProxy(exposeProxy = true)` 설정 후 현재 프록시를 얻는다. 코드가 AOP에 강하게 결합되어 권장도는 낮다.

넷째, **AspectJ 컴파일/로드타임 위빙**: 프록시가 아니라 바이트코드를 직접 위빙하므로 self-invocation도 가로챈다. 가장 강력하지만 빌드/에이전트 설정이 복잡하다.

| 방법 | 동작 | 권장도 | 비고 |
|---|---|---|---|
| 구조 분리 | O | 높음 | 책임 분리, 가장 깔끔 |
| 자기 주입 | O | 중간 | 순환 의존 느낌 |
| AopContext | O | 낮음 | AOP 결합 노출 |
| AspectJ 위빙 | O | 상황별 | 프록시 한계 자체를 제거 |

## 7. 진단과 검증

self-invocation 의심 시 빠른 진단법은 트랜잭션 로그를 켜는 것이다.

```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
```

이 로그는 `Getting transaction for [...]`, `Completing transaction for [...]`를 출력한다. 의도한 메서드에서 이 로그가 없으면 프록시를 우회했다는 뜻이다. 또 `TransactionSynchronizationManager.isActualTransactionActive()`로 런타임에 트랜잭션 활성 여부를 점검할 수 있다.

테스트로 회귀를 막을 수도 있다. 통합 테스트에서 내부 트랜잭션이 실제로 롤백되는지 검증한다.

```java
@SpringBootTest
class TransactionPropagationTest {
    @Autowired ReportService reportService;
    @Autowired ReportRepository repo;

    @Test
    void self_invocation_은_롤백되지_않음을_재현() {
        // generateAll → this.generateOne 경로에서 예외 시
        // 트랜잭션 미적용으로 데이터가 남는지 확인
        assertThatThrownBy(() -> reportService.generateAll(badInput))
            .isInstanceOf(RuntimeException.class);
        // 프록시 우회였다면 부분 저장이 남아 있을 수 있다
        assertThat(repo.count()).isGreaterThan(0);
    }
}
```

## 8. 정리와 설계 지침

Spring의 선언적 트랜잭션은 프록시라는 단일 메커니즘 위에 서 있고, propagation·롤백 규칙·self-invocation 한계는 모두 이 구조의 직접적인 결과다. 프록시는 "외부에서 빈으로 들어오는 호출"만 가로챌 수 있으므로, 트랜잭션 경계는 항상 빈 경계와 일치시키는 것이 가장 안전한 설계 원칙이다.

실무 지침을 요약하면, 트랜잭션이 필요한 단위 작업은 독립된 서비스 메서드(가능하면 독립 빈)로 두고 내부 반복 호출을 피한다. checked 예외 롤백이 필요하면 `rollbackFor`를 명시한다. 독립 커밋이 필요하면 `REQUIRES_NEW`, 부분 롤백이 필요하면 `NESTED`를 쓰되 JPA의 savepoint 제약을 확인한다. 그리고 트랜잭션 로그를 켜서 실제 경계가 의도대로 생성되는지 반드시 검증한다. 프록시 구조를 이해하면 "왜 내 `@Transactional`이 안 먹지?"라는 질문의 답이 대부분 self-invocation이라는 것을 곧장 알 수 있다.

## 9. 실전 사례: REQUIRES_NEW 감사 로그 패턴

self-invocation 한계와 propagation을 함께 보여주는 대표 사례가 "비즈니스 트랜잭션은 실패해도 감사 로그는 남겨야 한다"는 요구다. 이를 잘못 구현하면 로그가 함께 롤백되거나 아예 동작하지 않는다.

잘못된 구현은 같은 클래스 안에서 `@Transactional(REQUIRES_NEW)` 메서드를 self-invocation으로 호출하는 것이다.

```java
@Service
public class OrderService {
    @Transactional
    public void placeOrder(Order order) {
        try {
            process(order);
        } catch (Exception e) {
            this.writeAuditLog(order, e); // 프록시 우회 → REQUIRES_NEW 무시!
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAuditLog(Order order, Exception e) {
        auditRepo.save(new AuditLog(order, e));
    }
}
```

`this.writeAuditLog()`는 프록시를 거치지 않아 `REQUIRES_NEW`가 적용되지 않고, 바깥 트랜잭션 안에서 실행된 뒤 `throw e`로 함께 롤백된다. 결국 감사 로그가 사라진다.

올바른 구현은 감사 로깅을 별도 빈으로 분리하는 것이다.

```java
@Service
public class OrderService {
    private final AuditService auditService;

    @Transactional
    public void placeOrder(Order order) {
        try {
            process(order);
        } catch (Exception e) {
            auditService.writeAuditLog(order, e); // 다른 빈 → 프록시 경유, 독립 커밋
            throw e;
        }
    }
}

@Service
public class AuditService {
    private final AuditRepository auditRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAuditLog(Order order, Exception e) {
        auditRepo.save(new AuditLog(order, e));
    }
}
```

이제 `auditService.writeAuditLog()`는 프록시를 거쳐 새 물리 트랜잭션을 열고, 바깥 트랜잭션과 독립적으로 커밋된다. 주문이 롤백돼도 감사 로그는 남는다. 이 패턴은 propagation의 독립성과 self-invocation 회피(구조 분리)를 한 번에 보여주는 정석이다.

검증 관점에서, 통합 테스트로 "바깥 롤백 + 감사 로그 보존"을 단언하면 회귀를 막을 수 있다. `@Transactional` 테스트는 롤백 동작을 가리므로, 트랜잭션 경계 검증 테스트에는 `@Commit`이나 실제 DB 상태 확인을 함께 둔다. 운영에서는 `org.springframework.transaction.interceptor` 로그로 `placeOrder`와 `writeAuditLog`가 서로 다른 트랜잭션 경계를 여는지 확인하면 의도대로 동작하는지 즉시 알 수 있다.

## 참고

- Spring Framework Reference — Transaction Management / Declarative: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html
- Spring Framework Reference — Understanding the Spring AOP Proxies: https://docs.spring.io/spring-framework/reference/core/aop/proxying.html
- Spring Framework Reference — Transaction Propagation: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
- Baeldung — Spring Self-Invocation and AOP Proxies: https://www.baeldung.com/spring-aop-proxy
