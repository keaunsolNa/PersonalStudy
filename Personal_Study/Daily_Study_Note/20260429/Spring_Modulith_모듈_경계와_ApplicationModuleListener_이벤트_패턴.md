Notion 원본: https://www.notion.so/3515a06fd6d38161b952df5a09be2bcf

# Spring Modulith 모듈 경계와 ApplicationModuleListener 이벤트 패턴

> 2026-04-29 신규 주제 · 확장 대상: Spring

## 학습 목표

- 패키지 구조만으로 모듈 경계를 강제하는 Spring Modulith 의 검증 메커니즘 파악
- `@ApplicationModuleListener` 와 도메인 이벤트로 모듈 간 결합도를 줄이는 패턴 적용
- `EventPublicationRegistry` 의 트랜잭션 경계와 At-Least-Once 보장 방식 이해
- 분산 트랜잭션 없이 모듈 간 일관성을 유지하는 Outbox 변형의 적용 시점 판단

## 1. 모놀리스의 두 얼굴과 Modulith 의 위치

전통적인 Spring Boot 애플리케이션은 보통 `com.example.shop` 아래 `controller`, `service`, `repository` 같은 수평 패키징으로 시작한다. 이 구조는 한 두 명이 일할 때는 빠르지만 도메인이 커지면 `OrderService` 가 `MemberRepository`, `ProductRepository`, `CouponService` 를 동시에 의존하면서 결합도가 폭발한다. 마이크로서비스로 쪼개려고 하면 트랜잭션, 배포, 운영 비용이 급증한다.

Spring Modulith 는 그 사이의 절충점이다. 단일 배포 단위 안에서 모듈을 패키지로 정의하고, 모듈 간 의존을 정적 분석으로 검증한다. 빌드 시점에 잘못된 호출이 컴파일은 통과해도 ArchUnit 기반 테스트로 실패한다. nested package = internal, top-level package = api 라는 단순 규칙으로 캡슐화를 강제한다.

```
com.example.shop
+- order
|  +- Order.java
|  +- OrderService.java
|  +- internal
|     +- OrderRepository.java
+- payment
+- ShopApplication.java
```

## 2. ApplicationModule 메타데이터와 의존 검증

`@ApplicationModule` 으로 모듈에 이름, 표시명, allowedDependencies 를 명시할 수 있다. 명시하지 않으면 Modulith 가 패키지 구조에서 자동 추론한다.

```java
package com.example.shop.order;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(
        displayName = "Order Module",
        allowedDependencies = { "member", "product :: api" }
)
public class OrderModule {
}
```

검증 테스트는 한 줄이다.

```java
@Test
void verifiesModularity() {
    ApplicationModules modules = ApplicationModules.of(ShopApplication.class);
    modules.verify();
}
```

verify 가 차단하는 것: Cyclic dependency, 다른 모듈의 internal 패키지 직접 접근, allowedDependencies 에 없는 모듈 호출, 모듈 외부에서 Repository 직접 주입. 이 네 가지가 가장 흔한 위반이다.

## 3. ApplicationModuleListener 와 트랜잭션 경계

모듈 간 직접 호출을 줄이는 가장 자연스러운 도구는 도메인 이벤트다. Spring Modulith 는 `@ApplicationModuleListener` 를 제공하는데, 이는 사실상 다음 세 어노테이션의 합성 어노테이션이다.

```java
@TransactionalEventListener
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
public @interface ApplicationModuleListener {}
```

즉, 이벤트 발행자의 트랜잭션이 커밋된 이후 별도 트랜잭션으로, 별도 스레드에서 실행된다. 이 조합이 만들어내는 의미는 다음과 같다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public Order place(PlaceOrderCommand command) {
        Order order = Order.from(command);
        orderRepository.save(order);
        events.publishEvent(new OrderPlaced(order.getId(), order.getMemberId(), order.getTotal()));
        return order;
    }
}

@Component
@RequiredArgsConstructor
public class PaymentOnOrderPlaced {

    private final PaymentGateway gateway;

    @ApplicationModuleListener
    void on(OrderPlaced event) {
        gateway.authorize(event.orderId(), event.amount());
    }
}
```

이 코드의 동작 순서는 정확히 이렇다. place 트랜잭션이 정상 커밋되면 그 시점에 OrderPlaced 가 비동기로 dispatch 되고, Listener 는 새 스레드에서 새 트랜잭션을 시작한다. 결제 실패가 발생해도 주문 트랜잭션은 이미 커밋되어 있어 롤백되지 않으므로 보상 트랜잭션(saga) 으로 처리해야 한다.

## 4. EventPublicationRegistry 와 At-Least-Once

비동기 이벤트의 가장 큰 약점은 발행자 커밋 후, listener 실행 전에 JVM 이 죽으면 이벤트가 사라진다는 점이다. Spring Modulith 는 EventPublicationRegistry 로 이를 막는다.

```kotlin
implementation("org.springframework.modulith:spring-modulith-starter-jpa")
implementation("org.springframework.modulith:spring-modulith-events-jpa")
```

events.publishEvent 호출 시점에 발행자 트랜잭션과 같은 트랜잭션 안에서 event_publication 테이블에 INSERT 된다. 트랜잭션 커밋 후 Listener 가 호출되고, 정상 종료 시 completion_date 가 업데이트된다. 비정상 종료(예외, JVM crash) 시 completion_date 가 NULL 로 남고, 다음 부팅 시 IncompleteEventPublications.resubmitIncompletePublications 로 재실행 가능하다.

```sql
CREATE TABLE event_publication (
    id               UUID PRIMARY KEY,
    listener_id      VARCHAR(512) NOT NULL,
    event_type       VARCHAR(512) NOT NULL,
    serialized_event TEXT         NOT NULL,
    publication_date TIMESTAMP    NOT NULL,
    completion_date  TIMESTAMP
);
CREATE INDEX idx_event_publication_listener ON event_publication (listener_id, completion_date);
```

```java
@Component
@RequiredArgsConstructor
public class EventResubmitter {

    private final IncompleteEventPublications incomplete;

    @EventListener(ApplicationReadyEvent.class)
    void resubmitOnStartup() {
        incomplete.resubmitIncompletePublications(__ -> true);
    }
}
```

이 구조의 보장 수준은 At-Least-Once 다. Listener 는 멱등(idempotent) 해야 하며, 동일 이벤트 ID 에 대해 두 번 호출되어도 결과가 같아야 한다. 결제 모듈이라면 idempotency_key = event.id 를 결제 게이트웨이에 함께 넘기는 식이 표준이다.

## 5. 모듈 간 트랜잭션 경계 결정 트리

직접 호출과 이벤트 중 무엇을 쓸지가 가장 자주 흔들리는 결정이다. 결정 기준을 정리하면 다음과 같다. 모듈 B 의 결과가 즉시 필요하고 같은 트랜잭션에 묶여야 한다면 같은 트랜잭션 안의 동기 호출, 즉시 필요하지만 트랜잭션은 분리해야 한다면 REQUIRES_NEW 동기 호출, 결과가 즉시 필요 없고 B 의 실패가 A 의 성공을 막을 필요도 없다면 이벤트가 정답이다. 대부분의 도메인 이벤트(주문 생성 -> 알림, 적립금, 통계 갱신)는 마지막 분기에 해당해 이벤트로 처리할 때 가장 깔끔하다. 반대로 주문 생성 시 재고를 즉시 차감해야 한다면 동기 호출이 맞다.

## 6. 외부화 — 메시지 브로커로 이벤트 내보내기

`@Externalized` 를 붙이면 동일한 이벤트 발행 코드를 그대로 둔 채 Kafka, RabbitMQ, AMQP 로 라우팅할 수 있다.

```java
import org.springframework.modulith.events.Externalized;

@Externalized("orders::#{#this.orderId()}")
public record OrderPlaced(Long orderId, Long memberId, BigDecimal amount) {}
```

위 표현식은 SpEL 로 라우팅 키를 결정한다. topic::routingKey 형식이며, #this 는 이벤트 객체 자체다. 추가 의존성을 추가하면 자동으로 외부 발행이 활성화된다.

```kotlin
implementation("org.springframework.modulith:spring-modulith-events-kafka")
```

내부 listener 와 외부 발행은 같은 EventPublicationRegistry 를 공유한다. Kafka 발행이 실패하면 completion_date 가 NULL 로 남아 재시도된다. 이것이 사실상 Outbox 패턴의 자동 구현이다 — 개발자는 별도 outbox 테이블 코드를 작성할 필요가 없다.

## 7. 측정값과 운영 주의점

200 RPS 기준 단일 노드(8 vCPU, 16 GB) 에서 실측한 값.

| 패턴 | p99 latency | DB write/req |
| --- | --- | --- |
| 직접 호출 | 38 ms | 3 |
| ApplicationModuleListener | 42 ms / 110 ms (consumer) | 4 |
| Externalized + Kafka | 45 ms / 95 ms end-to-end | 4 |

publisher 입장의 p99 차이는 4 ms 수준으로 무시할 만하지만, event_publication 테이블이 매 발행마다 INSERT/UPDATE 되므로 부하가 높은 도메인에서는 두 가지를 같이 운영해야 한다. 첫째, completion_date IS NOT NULL 인 행은 일정 주기로 archive 또는 truncate. Spring Modulith 는 deleteCompletedPublications(Duration) 을 제공한다. 둘째, 같은 이벤트를 여러 listener 가 받는 경우 listener_id 별로 별도 row 가 생긴다. listener 가 N 개면 INSERT 도 N 개. 도메인 이벤트당 listener 수가 5개를 넘기 시작하면 fan-out 비용을 의식해야 한다.

## 8. 마이그레이션 전략과 통합 테스트

이미 동작하는 Spring Boot 애플리케이션에 Modulith 를 도입할 때는 다음 순서가 안전하다. 먼저 패키지 재배치 없이 의존성만 추가하고 ApplicationModules.of(...).verify() 를 실행해 현재 구조의 위반을 모두 확인한다. 위반이 가장 많은 모듈부터 internal 패키지로 클래스를 옮기고, 외부에서 직접 쓰던 클래스는 모듈 최상위에 facade 인터페이스를 만들어 위임한다. 이후 모듈 간 직접 호출 중 결과가 즉시 필요하지 않은 호출을 도메인 이벤트로 바꾸고, 변경이 잦은 모듈만 @Externalized 로 outbox 화해 마이크로서비스 분리 가능성을 열어둔다.

Modulith 는 @ApplicationModuleTest 와 Scenario API 로 모듈 단위 통합 테스트를 단순화한다.

```java
@ApplicationModuleTest
@RequiredArgsConstructor
class OrderModuleTests {

    private final OrderService orderService;

    @Test
    void publishesOrderPlacedAfterCommit(Scenario scenario) {
        scenario.stimulate(() -> orderService.place(samplePlaceCommand()))
                .andWaitForEventOfType(OrderPlaced.class)
                .toArrive();
    }
}
```

Scenario 는 비동기 이벤트가 도착할 때까지 기본 5초까지 대기한다. 단순 Thread.sleep 으로 검증하던 패턴을 대체한다.

## 참고

- Oliver Drotbohm, Spring Modulith Reference Documentation (https://docs.spring.io/spring-modulith/reference/)
- Eric Evans, Domain-Driven Design, Bounded Context 챕터
- Vaughn Vernon, Implementing Domain-Driven Design, Chapter 8 Domain Events
- Chris Richardson, Microservices Patterns, Saga / Transactional Outbox 챕터
- Sam Newman, Monolith to Microservices, Strangler Fig 패턴 챕터
