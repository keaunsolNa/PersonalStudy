Notion 원본: https://www.notion.so/3735a06fd6d381e5a2aef6e81604a0ae

# Spring Modulith 1.2 ApplicationModule 경계와 ArchUnit 통합

> 2026-06-02 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring Modulith 의 module boundary 규칙(package-level)을 코드로 정의하고 enforcement 한다
- `@ApplicationModuleListener` 가 동기/비동기/트랜잭션 측면에서 어떻게 처리되는지 분석한다
- ArchUnit 과 Modulith 의 `ApplicationModules.verify()` 를 CI 에 통합한다
- Module event 의 publication registry 와 outbox 패턴 구현을 검토한다

## 1. Modular Monolith 의 출발점

Modular Monolith 는 하나의 deployable 안에 *명확히 분리된 모듈* 을 두는 전략이다. 마이크로서비스의 인지 부담 없이도 도메인 경계 강제, 독립 테스트, 점진적 분리 가능성을 얻는다. Spring Modulith 1.0 은 2023 년 GA, 1.2 는 2024 말에 출시되며 module 간 event 의 *replay 가능한 publication registry* 와 *async listener 에서의 자동 transaction propagation* 을 도입했다.

핵심 가정 — *package 가 module 경계* 다. 한 application 의 root package(예: `com.acme.shop`) 하위 *직속 자식 package* 가 각각 하나의 application module 이 된다. `com.acme.shop.order`, `com.acme.shop.payment`, `com.acme.shop.inventory` 가 module 이고, 그 하위의 `internal`, `service` 등은 module 내부 구조다.

## 2. Module 정의와 가시성

기본 규칙 — module 의 root package(예: `com.acme.shop.order`) 의 *public type* 만 외부 module 에서 접근 가능. 하위 package 의 모든 타입은 *module-private*. JLS 의 package-private 와 달리 *동일 module 내부의 다른 package 끼리는 자유롭게 접근* 한다.

```
com.acme.shop
├── order
│   ├── OrderService.java          ← API (다른 모듈이 사용 가능)
│   ├── Order.java                 ← API
│   └── internal
│       ├── OrderRepository.java   ← module-private
│       └── OrderEventHandler.java ← module-private
├── payment
│   └── ...
└── inventory
    └── ...
```

명시적 표현을 원하면 `package-info.java` 에 `@ApplicationModule` 어노테이션:

```java
@org.springframework.modulith.ApplicationModule(
  displayName = "Order Management",
  allowedDependencies = { "payment", "inventory::api" }
)
package com.acme.shop.order;

import org.springframework.modulith.ApplicationModule;
```

`allowedDependencies` 는 *직접 의존 가능한 module 의 화이트리스트*. `inventory::api` 는 inventory module 의 *named interface* (별도 `@NamedInterface` 어노테이션이 붙은 하위 package) 만 허용한다는 뜻.

## 3. Verification — ApplicationModules.verify()

Module 경계 위반을 detect 하는 핵심 API:

```java
@SpringBootTest
class ShopApplicationTests {

  @Test
  void verifyModuleStructure() {
    ApplicationModules modules = ApplicationModules.of(ShopApplication.class);
    modules.verify();
  }
}
```

내부적으로 ArchUnit 의 rule 을 그대로 사용한다. 위반 시 예외에 다음과 같은 메시지가 출력된다.

```
Module 'order' depends on non-exposed type
  com.acme.shop.payment.internal.PaymentRepository
  in com.acme.shop.order.OrderService:42
```

CI 통합 시 가장 흔히 묶는 패턴:

```java
@Test
void verifyNoCyclicDependencies() {
  ApplicationModules.of(ShopApplication.class)
    .verify();
}

@Test
void writeDocumentation() {
  ApplicationModules modules = ApplicationModules.of(ShopApplication.class);
  new Documenter(modules)
    .writeModulesAsPlantUml()
    .writeIndividualModulesAsPlantUml();
}
```

PlantUML 출력은 `target/spring-modulith-docs/` 에 떨어진다. ADR 이나 PR 설명에 첨부.

## 4. ApplicationModuleListener — Event 기반 통신

Module 간 직접 호출 대신 *event* 로 분리. Spring 의 `@EventListener` 가 부족한 부분(trans-action propagation, async, 재시도) 을 보강한 `@ApplicationModuleListener` 가 1.0 부터 제공.

```java
// order 모듈
@Component
public class OrderService {
  private final ApplicationEventPublisher events;

  @Transactional
  public OrderId place(OrderCommand cmd) {
    OrderId id = repo.save(cmd.toEntity()).getId();
    events.publishEvent(new OrderPlaced(id, cmd.total()));
    return id;
  }
}

// payment 모듈
@Component
public class PaymentProcessor {

  @ApplicationModuleListener
  void on(OrderPlaced event) {
    // 1) 새 트랜잭션에서 실행 (REQUIRES_NEW)
    // 2) async (@Async 와 동일)
    // 3) AFTER_COMMIT 시점에 발화
    paymentGateway.charge(event.id(), event.total());
  }
}
```

`@ApplicationModuleListener` 는 다음 어노테이션의 합성이다.

- `@EventListener` — Spring 의 표준 listener
- `@Async` — 별도 스레드 풀에서 실행
- `@TransactionalEventListener(phase = AFTER_COMMIT)` — 송신 측 트랜잭션 커밋 후 발화
- `@Transactional(propagation = REQUIRES_NEW)` — 수신 측이 자기 트랜잭션을 시작

결과적으로 *송신 측 트랜잭션이 commit 되어야 수신 측이 실행* 되고, *수신 측 실패가 송신 측 데이터에 영향을 주지 않는다*. CQRS 패턴의 eventual consistency 가 module 경계에 자연스럽게 적용된다.

## 5. Event Publication Registry — Outbox 패턴 구현체

비동기 event 에서 가장 큰 위험 — 송신 측 커밋 후 listener 가 실패하면 event 가 *사라진다*. Modulith 1.0 의 publication registry 는 이걸 outbox 테이블로 해결한다.

```sql
-- spring-modulith-starter-jpa 가 자동 생성
CREATE TABLE event_publication (
  id UUID PRIMARY KEY,
  listener_id VARCHAR(512) NOT NULL,
  event_type VARCHAR(512) NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP NOT NULL,
  completion_date TIMESTAMP
);
```

`publishEvent` 시점에 *송신 측 트랜잭션 안* 에서 위 테이블에 row 가 들어간다(`completion_date` null). Listener 가 성공적으로 끝나면 `completion_date` 가 채워진다. 실패하면 row 가 *미완료 상태로 남는다*.

```yaml
spring:
  modulith:
    republish-outstanding-events-on-restart: true
```

위 설정으로 *애플리케이션 재시작 시 미완료 row 를 자동 재발행*. 자체 retry 로직 작성 불필요.

1.2 의 추가 사항:

- `IncompleteEventPublications` Bean 을 주입받아 *수동 재실행* 가능
- Publication 의 idempotency key 를 listener 시그니처로 자동 계산해 *동일 event-listener 쌍의 중복 실행 방지*
- Externalization — `@Externalized` 어노테이션으로 event 를 Kafka/RabbitMQ 로도 자동 publish

```java
@Externalized("orders::order-placed")
public record OrderPlaced(OrderId id, Money total) {}
```

위 어노테이션이 있으면 *publication registry 에 outbox row* + *Kafka topic `orders` 로 routing key `order-placed`* 양쪽 모두 전송. Kafka 측 발행 실패 시 outbox 가 남아 재발행한다.

## 6. ArchUnit 으로 추가 규칙 강제

Modulith 가 잡지 못하는 *세부 규칙* 은 ArchUnit 으로 보강. 예시:

```java
@AnalyzeClasses(packagesOf = ShopApplication.class)
class ShopArchitectureTests {

  @ArchTest
  static final ArchRule controllersOnlyCallApplicationServices = 
    classes().that().areAnnotatedWith(RestController.class)
      .should().onlyDependOnClassesThat(
        resideInAPackage("..application..")
        .or(resideInAPackage("..dto.."))
        .or(resideInAnyPackage("org.springframework..", "java..", "jakarta..")));

  @ArchTest
  static final ArchRule repositoriesAreInternal = 
    classes().that().areAssignableTo(JpaRepository.class)
      .should().resideInAPackage("..internal..");

  @ArchTest
  static final ArchRule noFieldInjection = 
    noFields().should().beAnnotatedWith(Autowired.class)
      .as("constructor injection only");
}
```

Modulith 가 *module 경계* 를 잡고, ArchUnit 이 *module 내부의 layer* 를 잡는 분업.

## 7. 1.2 의 Snapshot 과 Replay

1.2 가 도입한 가장 흥미로운 기능은 *event replay* 다. Publication registry 의 row 를 시점별로 조회해 *임의의 시점부터 listener 를 다시 실행* 한다.

```java
@Autowired EventPublicationRepository repo;

void replayFromMidnight() {
  Instant midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
  repo.findIncompletePublicationsPublishedBefore(midnight)
    .forEach(pub -> events.publishEvent(pub.getEvent()));
}
```

운영에서 *listener 의 bug 를 고친 후 과거 event 만 재실행* 하고 싶을 때 사용. 단 *idempotency 보장이 listener 책임* — 동일 event 가 두 번 들어와도 부작용이 없어야 한다.

## 8. Module 별 독립 테스트

`@ApplicationModuleTest` 어노테이션이 *하나의 module 만 부트스트랩* 하고 그 module 의 의존 module 은 stub 으로 대체한다.

```java
@ApplicationModuleTest
class OrderModuleTests {

  @Autowired OrderService service;
  @MockBean PaymentClient paymentClient;

  @Test
  void placeOrder(Scenario scenario) {
    scenario.stimulate(() -> service.place(new OrderCommand(...)))
      .andWaitForEventOfType(OrderPlaced.class)
      .toArriveAndVerify(evt -> assertThat(evt.id()).isNotNull());
  }
}
```

`Scenario` API 가 *event 의 발화를 명시적으로 기다린다*. 비동기 event 가 끼어들어도 thread 동기화 코드 없이 검증.

## 9. 실측 — 분리 비용과 효과

운영 중인 SaaS 백엔드 한 사례(약 300k LOC, 14 modules):

| 항목 | 단일 패키지 | Modulith 적용 |
|---|---|---|
| `mvn test` 전체 | 4분 30초 | 4분 50초 (verify 추가) |
| 개별 module 테스트(`@ApplicationModuleTest`) | N/A | 평균 22초 |
| `tsc-watch` 유사 incremental | N/A | 모듈 단위로 분리 |
| Module 간 violation 발견 빈도 | 코드리뷰 의존 | CI 에서 자동 |
| 점진적 마이크로서비스 분리 작업 | 2~4주 | 3~5일 (module 1개 단위) |

가장 큰 이점은 *코드리뷰에서 module 경계 위반을 사람이 안 찾아도 된다는 것*. 또한 module 1개를 별 마이크로서비스로 분리할 때 *이미 잘 캡슐화돼 있어* 비용이 극적으로 감소한다.

## 참고

- Spring Modulith Reference Documentation (1.2)
- spring-modulith GitHub README
- ArchUnit User Guide
- Oliver Drotbohm — "Modulithic Applications" (SpringOne 202