Notion 원본: https://www.notion.so/3635a06fd6d3811f9a63c688b9114b60

# Spring Modulith ApplicationModules와 ApplicationEventPublisher 이벤트 기반 모듈 통신

> 2026-05-17 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring Modulith가 패키지 단위 module 경계를 정적으로 검증하는 방식과 ArchUnit 기반 검증의 의미를 이해한다
- `@ApplicationModuleListener` 로 이벤트 기반 비동기 모듈 통신을 트랜잭션 경계와 함께 설계한다
- Event Publication Registry(JDBC/JPA) 가 at-least-once 전달과 재시도를 보장하는 메커니즘을 분석한다
- Modulith 의 통합 테스트(`@ApplicationModuleTest`) 로 모듈 단위 격리 테스트를 작성한다

## 1. Modulith 가 해결하려는 문제

마이크로서비스 vs 모놈리식 사이에는 *모듈러 모놈리식(modular monolith)* 이 있다. 단일 배포 단위지만 내부적으로는 명확한 모듈 경계를 가지며, 각 모듈은 자기 패키지 외부로 노출하는 API 만 다른 모듈에서 호출 가능해야 한다. Spring Modulith는 이 경계를 `package-info.java` 와 `@ApplicationModule` 어노테이션으로 선언하고, 빌드/테스트 시 정적 검증한다. Modulith 는 모듈 사이 통신을 *이벤트* 로 강제한다는 점에서 단순한 패키지 규칙 검증기 이상이다.

## 2. 모듈 경계 선언

```java
// com/acme/order/package-info.java
@org.springframework.modulith.ApplicationModule(
    displayName = "Order Management",
    allowedDependencies = {"shared"}
)
package com.acme.order;
```

`allowedDependencies` 에 명시되지 않은 모듈은 import 할 수 없다. 같은 모듈의 *internal* 패키지(`com.acme.order.internal.*`)는 외부 모듈이 import 할 수 없다. `ApplicationModules.of(Application.class).verify()` 호출이 정적 검증을 수행한다.

## 3. 검증 테스트

```java
class ModularityTests {
    ApplicationModules modules = ApplicationModules.of(Application.class);
    @Test void verifiesModularStructure() { modules.verify(); }
    @Test void writeDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
```

`verify()` 는 ArchUnit 위에서 선언되지 않은 모듈 의존성, internal 패키지 외부 import, 순환 의존성, 같은 type 이 두 모듈에서 동시 노출되는 충돌을 점검한다. `Documenter` 는 PlantUML 다이어그램을 자동 생성한다.

## 4. 모듈 간 통신은 ApplicationEvent

```java
@Service
public class OrderService {
    private final ApplicationEventPublisher events;
    @Transactional
    public OrderId placeOrder(OrderRequest req) {
        Order order = Order.create(req);
        orderRepo.save(order);
        events.publishEvent(new OrderPlaced(order.getId(), order.getTotal()));
        return order.getId();
    }
}
```

Payment 모듈은 다음과 같이 구독한다.

```java
@Component
class PaymentEventHandler {
    @ApplicationModuleListener
    public void on(OrderPlaced event) { payments.charge(event.orderId(), event.total()); }
}
```

`@ApplicationModuleListener` 는 `@Async` + `@Transactional(propagation = REQUIRES_NEW)` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 의 메타 조합이다. 발행자가 commit 실패하면 listener 는 호출되지 않고, listener 의 트랜잭션이 실패해도 발행자에는 영향 없으며, listener 는 별도 스레드에서 비동기 실행된다.

## 5. Event Publication Registry: at-least-once 보장

순수 `@TransactionalEventListener(AFTER_COMMIT)` 의 한계는 *발행자 commit 후 listener 실행 직전 JVM 이 죽으면 이벤트가 영구 손실* 된다는 점이다. Modulith 는 Event Publication Registry 로 이를 해결한다.

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>
</dependency>
```

스타터 추가 후 동작은: (1) 발행자 트랜잭션 안에서 이벤트가 발행되면 *같은 트랜잭션* 으로 `event_publication` 테이블에 row 를 insert. (2) 발행자 commit 직후 listener 가 실행되면 성공 시 `completion_date` 를 update. (3) JVM 이 죽거나 listener 가 실패해 row 가 남아 있으면 *재기동 시* 또는 *scheduled republish* 시 다시 listener 호출.

```sql
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event VARCHAR(4000) NOT NULL,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completion_date TIMESTAMP(6) WITH TIME ZONE
);
```

incomplete event 재처리는 `IncompleteEventPublications.resubmitIncompletePublications(predicate)` 로. at-least-once 이므로 listener 는 *idempotent* 해야 한다.

## 6. Externalization: 사내 이벤트를 외부 Kafka 로 노출

Modulith 1.1 부터 `@Externalized` 어노테이션으로 application event 를 외부 broker 에 자동 publish.

```java
@DomainEvent
@Externalized("orders::#{orderId}")
public record OrderPlaced(OrderId orderId, Money total) {}
```

`spring-modulith-events-kafka` 의존성을 추가하면 Modulith 가 listener 처럼 등록되어 Kafka 로 publish. 같은 Event Publication Registry 가 사용되므로, Kafka 가 일시 장애여도 publication 이 incomplete 로 남아 재시도된다.

| 방식 | at-least-once | 순서 보장 | 외부 노출 |
|---|---|---|---|
| 단순 @ApplicationEventPublisher | X | 발행 순서 | X |
| Modulith + Registry | O (재시도) | 발행 순서 | X |
| Modulith + Externalized | O | partition key 기준 | O |
| Transactional Outbox (수동) | O | 직접 구현 | O |

Modulith Externalized 는 사실상 *Transactional Outbox Pattern* 의 매니지드 구현이다.

## 7. 모듈 격리 테스트: @ApplicationModuleTest

```java
@ApplicationModuleTest
class OrderModuleTests {
    @Autowired OrderService service;
    @Autowired PublishedEvents events;
    @Test
    void publishesOrderPlacedEvent() {
        service.placeOrder(req);
        assertThat(events.ofType(OrderPlaced.class)).hasSize(1)
            .extracting(OrderPlaced::orderId).isNotNull();
    }
}
```

`PublishedEvents` 는 Modulith 가 제공하는 검증 도구로, 발행된 이벤트를 in-memory 로 캐프처해 검증. 다른 모듈을 실제로 띄우지 않고도 이벤트 발행 사실만 단정할 수 있어, 단위/통합 테스트 사이를 메운다.

## 8. 마이그레이션 전략

1단계 — 의존성 추가, `ApplicationModules.of(...).verify()` 를 *실패 무시* 모드로 돌려 현재 의존성 그래프를 `Documenter` 로 시각화. 2단계 — 큰 도메인 단위에 `@ApplicationModule` 을 선언하되 `allowedDependencies` 는 처음에 *현재 의존 그대로* 둔다. 3단계 — 이벤트 발행 + listener 패턴으로 점진적 대체. 4단계 — `allowedDependencies` 에서 옮긴 의존성을 제거 + verify 재통과 확인. 5단계 — Event Publication Registry 의존성 추가, idempotency 확보. 6단계 — 일부 모듈을 외부 서비스로 추출 시 `@Externalized` 로 Kafka 제한 처리.

## 9. 운영 시 주의점

`@ApplicationModuleListener` 의 비동기 스레드 풀은 기본 SimpleAsyncTaskExecutor 다. 이는 *매 호출마다 새 스레드 생성* 이므로 production 에서는 반드시 `ThreadPoolTaskExecutor` 를 빈으로 제공해 풀 크기를 제어해야 한다.

```java
@Bean(name = "applicationTaskExecutor")
public ThreadPoolTaskExecutor taskExecutor() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(8); ex.setMaxPoolSize(32); ex.setQueueCapacity(500);
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return ex;
}
```

`event_publication` 테이블은 시간이 지나면 completion_date 가 채워진 row 가 쌓인다. `CompletedEventPublications.deletePublicationsOlderThan(Duration)` 으로 주기 정리. at-least-once 의 함정은 *중복 처리*. 모든 listener 는 idempotent 해야 한다. Modulith 의 모듈 경계 verify 는 *컴파일* 시점이 아닌 *테스트 실행* 시점에 실행된다. CI 가 테스트를 건너뛰면 위반이 빠져나간다.

## 참고

- Spring Modulith 공식 레퍼런스: https://docs.spring.io/spring-modulith/reference/
- Event Externalization 문서: https://docs.spring.io/spring-modulith/reference/events.html#externalization
- Oliver Drotbohm — "Modulithic Applications with Spring Boot" (Spring I/O 2023)
- ArchUnit: https://www.archunit.org/
- Microservices.io — Transactional Outbox Pattern: https://microservices.io/patterns/data/transactional-outbox.html
