Notion 원본: https://app.notion.com/p/3ec5a06fd6d381a8984ef6734f78f645?pvs=204

# Spring 트랜잭션 전파와 TransactionSynchronization 및 Transactional Outbox 패턴 기반 이벤트 발행 일관성

> 2026-10-01 신규 주제 · 확장 대상: Spring 트랜잭션

## 학습 목표
- 전파 속성별 물리 트랜잭션과 논리 트랜잭션의 경계를 구분하고 rollback-only 전파 경로를 추적한다
- TransactionSynchronization 콜백 순서와 afterCommit 내부의 트랜잭션 제약을 코드로 검증한다
- 이중 쓰기(dual write) 불일치가 생기는 지점을 찾아 Outbox 테이블 설계로 제거한다
- 폴링 릴레이와 CDC 릴레이의 처리량·순서·중복 특성을 비교해 운영 방식을 선택한다

## 1. 문제 정의: DB 커밋과 메시지 발행은 원자적이지 않다

주문 서비스가 주문 행을 INSERT하고 "OrderCreated" 이벤트를 Kafka로 보내는 가장 단순한 구현은 하나의 @Transactional 메서드 안에서 repository.save()와 kafkaTemplate.send()를 연달아 호출하는 것이다. 이 코드는 정상 경로에서만 올바르다. send()가 먼저 성공하고 이후 커밋이 실패하면 존재하지 않는 주문에 대한 이벤트가 이미 소비자에게 전달된다. 반대로 커밋이 성공한 뒤 발행 직전에 프로세스가 죽으면 주문은 있으나 이벤트는 영원히 없다. Kafka는 XA 분산 트랜잭션에 참여하지 않으므로 애플리케이션 수준의 설계가 필요하다.

```java
@Transactional
public Long placeOrder(PlaceOrderCommand cmd) {
    Order order = orderRepository.save(Order.create(cmd));
    // 안티패턴: 커밋 전에 외부 시스템으로 나간다. 이후 롤백되면 되돌릴 수 없다.
    kafkaTemplate.send("order-events", order.getId().toString(), toJson(order));
    return order.getId();
}
```

해결 방향은 두 갈래다. 첫째는 "커밋이 확정된 뒤에만 발행"하는 방식으로, TransactionSynchronization.afterCommit 혹은 @TransactionalEventListener(AFTER_COMMIT)를 쓴다. 롤백된 건에 대한 유령 이벤트는 막지만 커밋 후 발행 실패(프로세스 크래시 포함)는 막지 못한다. 둘째는 이벤트를 비즈니스 데이터와 같은 트랜잭션으로 같은 DB의 outbox 테이블에 저장하고, 별도 릴레이가 이를 브로커로 옮기는 Transactional Outbox 패턴이다.

## 2. 전파 속성과 논리/물리 트랜잭션

Spring 레퍼런스 문서는 트랜잭션을 물리 트랜잭션(실제 DB 커넥션 위의 트랜잭션)과 논리 트랜잭션(@Transactional 메서드 진입마다 생기는 스코프)으로 나눈다. PROPAGATION_REQUIRED(기본값)에서는 바깥 메서드가 물리 트랜잭션을 열고, 안쪽 메서드는 같은 물리 트랜잭션에 참여하는 별개의 논리 트랜잭션이 된다. 이때 안쪽 스코프가 롤백을 결정해도 실제 롤백은 바깥 경계에서 일어나며, 그 사이 물리 트랜잭션은 rollback-only로 표시된다.

| 속성 | 기존 트랜잭션 있을 때 | 없을 때 | 비고 |
|---|---|---|---|
| REQUIRED | 참여 | 새로 생성 | 기본값, 안쪽 롤백은 rollback-only 마킹 |
| REQUIRES_NEW | 기존 것을 일시 중단하고 새 물리 트랜잭션 | 새로 생성 | JDBC에서는 별도 커넥션을 추가로 점유 |
| NESTED | 같은 물리 트랜잭션 안에서 savepoint 생성 | 새로 생성 | JDBC 3.0 savepoint 기반, JPA 기본 구성에서는 제약 있음 |
| SUPPORTS | 참여 | 트랜잭션 없이 실행 | 동기화 자체는 활성화될 수 있음 |
| MANDATORY | 참여 | 예외 발생 | 호출 계약 강제용 |
| NOT_SUPPORTED | 일시 중단 후 비트랜잭션 실행 | 비트랜잭션 실행 | |
| NEVER | 예외 발생 | 비트랜잭션 실행 | |

흔한 함정은 rollback-only 전파다. 안쪽 REQUIRED 메서드에서 런타임 예외가 나가고 바깥에서 이를 catch해서 삼키면, 바깥 메서드가 정상 종료해 커밋을 시도하는 순간 UnexpectedRollbackException이 발생한다. 아래 코드는 이를 재현한다.

```java
@Transactional
public void run() {                       // OuterService
    try { inner.fail(); }                 // REQUIRED 참여, RuntimeException -> rollback-only 마킹
    catch (RuntimeException ignored) { }  // 삼켜도 물리 트랜잭션은 이미 rollback-only
}                                         // 종료 시 커밋 시도 -> UnexpectedRollbackException
// inner.fail(): @Transactional 메서드, IllegalStateException을 던진다
```

실패해도 남겨야 하는 감사 로그 같은 작업은 REQUIRES_NEW로 분리한다. 다만 REQUIRES_NEW는 바깥 트랜잭션이 커넥션을 쥔 채 두 번째 커넥션을 요청하므로 풀 크기가 작은 환경에서 풀 고갈 데드락이 생길 수 있다. HikariCP 기본 maximumPoolSize는 10이며, 동시 요청 수가 풀 크기에 이르면 모든 스레드가 첫 커넥션을 쥔 채 두 번째를 기다릴 수 있다. 실제 임계치는 환경에 따라 다름이다. @Transactional은 프록시 기반이라 같은 클래스 내부 호출에는 적용되지 않으므로 전파 속성을 바꾸려면 다른 빈으로 분리한다.

## 3. TransactionSynchronization 콜백 수명주기

TransactionSynchronizationManager는 현재 스레드(ThreadLocal)에 동기화 목록을 묶어 두고, 트랜잭션 매니저가 커밋/롤백 과정에서 각 콜백을 호출한다. 동기화를 등록하려면 isSynchronizationActive()가 true여야 하며, 아니면 IllegalStateException이 발생한다. 콜백 순서는 Javadoc에 명시된 대로 beforeCommit(readOnly), beforeCompletion, 실제 커밋, afterCommit, afterCompletion(status)이다. 롤백이면 beforeCompletion과 afterCompletion(STATUS_ROLLED_BACK)만 호출된다. Spring 5.3부터 인터페이스 메서드는 default 구현을 가져 필요한 것만 오버라이드하면 된다.

```java
public void publishAfterCommit(String topic, String key, String payload) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        kafka.send(topic, key, payload);          // 트랜잭션이 없으면 즉시 발행
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { kafka.send(topic, key, payload); }
    });
}
```

afterCommit에는 공식 Javadoc이 경고하는 제약이 있다. 이 시점에 트랜잭션은 이미 커밋됐지만 트랜잭션 자원은 아직 활성 상태일 수 있어서, 여기서 실행한 데이터 접근 코드는 이미 커밋된 원래 트랜잭션에 "참여"하며 이후 커밋이 없다. 즉 afterCommit 안에서 save()를 호출하면 INSERT가 플러시되지 않거나 커밋되지 않은 채 사라질 수 있다. Javadoc의 지침은 이 안에서 트랜잭션 작업이 필요하면 PROPAGATION_REQUIRES_NEW를 쓰라는 것이다. 또한 afterCommit에서 던진 예외는 호출자에게 전파되지만 커밋은 이미 끝난 뒤라 롤백되지 않는다. 클라이언트가 실패로 오해해 재시도하면 중복 주문이 생긴다. 따라서 afterCommit 내부에서는 예외를 잡아 로깅/재시도 큐로 넘기는 것이 안전하다.

## 4. @TransactionalEventListener와 그 한계

@TransactionalEventListener는 위 동기화 등록을 선언적으로 감싼 것이다. phase 기본값은 AFTER_COMMIT이고 BEFORE_COMMIT, AFTER_ROLLBACK, AFTER_COMPLETION도 선택할 수 있다. 활성 트랜잭션이 없을 때 이벤트는 기본적으로 폐기되며(fallbackExecution=false), 트랜잭션 없는 경로에서는 리스너가 실행되지 않는다.

```java
// 서비스 메서드 안에서 events.publishEvent(new OrderCreated(id, json)) 호출
public record OrderCreated(Long orderId, String payload) {}

@Component
class OrderEventRelay {
    // kafka: KafkaTemplate 주입
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderCreated e) {
        kafka.send("order-events", e.orderId().toString(), e.payload());
    }
}
```

이 방식은 롤백된 트랜잭션의 이벤트를 걸러 주지만 보장 범위가 at-most-once에 가깝다. 커밋 직후 JVM이 종료되거나 브로커가 불가하면 이벤트는 메모리에서 사라지고 복구 수단이 없다. AFTER_COMMIT 리스너에서 DB 쓰기를 하려면 @Transactional(propagation = REQUIRES_NEW)를 붙여야 하는데, 이 조합은 Spring Framework 6.1 이후 리스너 메서드에서 명시적으로 지원하는 형태다(버전별 동작은 Javadoc으로 확인). afterCommit 계열은 유령 이벤트 방지까지만 해결한다.

## 5. Transactional Outbox: 스키마와 같은 트랜잭션 기록

Outbox 패턴(microservices.io, Chris Richardson)은 이벤트를 비즈니스 변경과 같은 로컬 ACID 트랜잭션으로 outbox 테이블에 INSERT한다. 커밋되면 이벤트도 함께 영속화되고, 롤백되면 함께 사라진다. 발행은 별도 릴레이가 담당하므로 커밋과 전송 사이의 틈이 재시도 가능한 영속 상태로 바뀐다. 핵심은 outbox INSERT가 반드시 비즈니스 쓰기와 같은 물리 트랜잭션에 있어야 한다는 점이다. 2절의 규칙에 따라 outbox 기록 메서드는 MANDATORY로 선언해 트랜잭션 밖 호출을 컴파일이 아닌 런타임에 차단하는 것이 좋다. REQUIRES_NEW로 기록하면 비즈니스 롤백 후에도 이벤트가 남는다.

```sql
-- PostgreSQL 예시
CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;
```

```java
@Component
public class OutboxWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, String aggregateId, String eventType, String jsonPayload) {
        jdbc.update("""
            INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?::jsonb)""", aggregateType, aggregateId, eventType, jsonPayload);
    }
}
```

호출 측은 `repo.save(order)` 직후 `outbox.write("Order", id, "OrderCreated", json)`를 같은 @Transactional 메서드에서 부르면 된다.

부분 인덱스(WHERE published_at IS NULL)는 미발행 행만 인덱싱해 폴링 쿼리가 테이블 크기에 덜 영향받게 하며, 이 문법은 PostgreSQL 기준이고 MySQL에는 없다. 발행 후 행은 주기적으로 삭제하거나 파티션 단위로 드롭해야 한다. 보존 기간은 재처리 요구에 따라 정한다.

## 6. 릴레이 구현 1: 폴링 퍼블리셔

단순한 릴레이는 스케줄러가 미발행 행을 읽어 브로커로 보내고 published_at을 갱신하는 폴링이다. 여러 인스턴스가 동시에 돌아도 같은 행을 중복 집어 가지 않게 하려면 `FOR UPDATE SKIP LOCKED`를 쓴다. 이 절은 PostgreSQL 9.5 이상, MySQL 8.0 이상에서 지원된다. 인스턴스를 여러 개 두면 aggregate_id별 순서가 깨질 수 있다. 다음 코드는 한 배치를 한 트랜잭션에서 처리하고 전송 성공을 확인한 뒤에만 표시한다.

```java
@Scheduled(fixedDelay = 500)
@Transactional
public void relay() throws Exception {            // OutboxPoller 빈의 메서드
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT id, aggregate_id, event_type, payload::text AS payload
        FROM outbox_event WHERE published_at IS NULL
        ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED""");
    for (Map<String, Object> r : rows) {
        kafka.send("order-events", (String) r.get("aggregate_id"), (String) r.get("payload"))
             .get(5, TimeUnit.SECONDS);           // 실패 시 예외 -> 롤백 -> 다음 주기에 재시도
        jdbc.update("UPDATE outbox_event SET published_at = now() WHERE id = ?", r.get("id"));
    }
}
```

전송 성공 후 UPDATE 커밋 전에 프로세스가 죽으면 같은 행이 다시 발행된다. 즉 보장 수준은 at-least-once이고, 중복은 설계상 정상 상태다. 지연은 폴링 간격(위 예시는 500ms)이 하한이 되고, 처리량은 배치 크기와 브로커 왕복 시간에 의해 정해진다. 구체적 TPS는 환경에 따라 다름이므로 직접 측정해야 한다. 동기 대기 중에는 행 락과 커넥션을 쥐므로 배치를 작게, 타임아웃을 짧게 둔다.

## 7. 릴레이 구현 2: CDC(Debezium Outbox Event Router)

폴링의 부하와 지연이 문제라면 DB 트랜잭션 로그를 읽는 CDC가 대안이다. Debezium은 outbox 테이블 변경을 Kafka Connect로 읽어 토픽으로 보내는 SMT인 `io.debezium.transforms.outbox.EventRouter`를 제공한다. 애플리케이션은 5절처럼 INSERT만 하면 되고 published_at 갱신이나 삭제 부담이 없다. 아래는 PostgreSQL 커넥터 설정 예시이며, 속성 이름은 사용 버전의 Debezium 문서로 확인해야 한다.

```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.dbname": "orders", "topic.prefix": "orders",
    "table.include.list": "public.outbox_event",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "${routedByValue}.events"
  }
}
```

CDC도 exactly-once가 아니다. Debezium 문서는 장애 복구 시 이벤트가 중복될 수 있다고 안내한다. PostgreSQL에서는 복제 슬롯이 소비되지 못하면 WAL이 쌓이므로 슬롯 지연 모니터링이 필요하다.

## 8. 소비자 멱등성과 일관성 검증

Outbox는 유실 없음을 얻는 대가로 중복 가능성을 받아들인다. 따라서 소비자는 이벤트 id로 멱등 처리를 해야 한다. 가장 견고한 방법은 처리 이력 테이블에 이벤트 id를 PK로 두고 비즈니스 처리와 같은 트랜잭션에서 INSERT하는 것으로, 중복 시 PK 위반으로 해당 트랜잭션이 롤백되어 부작용이 한 번만 반영된다.

PostgreSQL에서는 PK 위반이 난 트랜잭션이 중단 상태가 되므로, `INSERT INTO processed_event (event_id) VALUES (?) ON CONFLICT DO NOTHING`의 갱신 행 수가 0이면 즉시 반환하는 형태가 안전하다. 검증은 장애 주입으로 한다. 통합 테스트로 (1) 강제 롤백 시 outbox 행이 없는지, (2) 릴레이가 전송 직후 UPDATE 전에 죽어도 소비자 반영이 한 번뿐인지 확인한다. 결론적으로 afterCommit 방식은 구현이 가볍지만 유실을 허용하는 경우(알림, 캐시 무효화 등)에, Outbox는 돈·재고처럼 유실이 곧 장애인 경우에 선택한다. Kafka 프로듀서의 `enable.idempotence=true`는 프로듀서 재시도 중복만 줄일 뿐 릴레이 크래시로 생기는 중복은 막지 못한다.

## 참고
- Spring Framework Reference: Transaction Management, Transaction Propagation (docs.spring.io)
- Spring Framework API Javadoc: TransactionSynchronization, TransactionSynchronizationManager, TransactionalEventListener
- Chris Richardson, Microservices Patterns (Manning, 2018), Transactional Outbox / Polling Publisher / Transaction Log Tailing
- Debezium Documentation: Outbox Event Router
- PostgreSQL Documentation: SELECT, The Locking Clause (SKIP LOCKED)
- Apache Kafka Documentation: Producer Configs (enable.idempotence)
