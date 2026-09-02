Notion 원본: https://www.notion.so/3cf5a06fd6d381a7871ed3227af8a7e2

# Debezium CDC 커넥터와 MySQL binlog 스냅샷 및 Outbox 토픽 라우팅

> 2026-09-02 신규 주제 · 확장 대상: MySQL InnoDB 버퍼풀·리두로그, Transactional Outbox 패턴

## 학습 목표

- MySQL row 기반 binlog 이벤트가 Debezium change event 로 변환되는 경로를 추적한다
- 초기 스냅샷 모드별 락 획득 범위와 무중단 조건을 구분한다
- Outbox Event Router SMT 로 애플리케이션 코드 변경 없이 토픽을 분리한다
- GTID·오프셋·스키마 히스토리 토픽의 복구 시나리오를 설계한다

## 1. Outbox 폴러를 왜 버리는가

Transactional Outbox 는 비즈니스 테이블과 `outbox` 테이블을 같은 로컬 트랜잭션으로 커밋한 뒤, 별도 폴러가 `outbox` 를 읽어 Kafka 로 보낸다. 정합성은 확보되지만 폴러 자체가 문제를 만든다.

폴링 주기가 짧으면 `SELECT * FROM outbox WHERE published = false ORDER BY id LIMIT 100` 이 초당 수십 회 실행되어 인덱스 스캔과 갱신 부하가 지속된다. 주기가 길면 지연이 늘어난다. 멀티 인스턴스에서는 `FOR UPDATE SKIP LOCKED` 로 경합을 막아야 하고, 발행 후 `UPDATE published = true` 가 또 하나의 쓰기다. 무엇보다 `id` 기준 정렬은 순서를 보장하지 않는다 — AUTO_INCREMENT 값은 트랜잭션 시작 시점에 할당되지만 커밋 순서는 다를 수 있어, 폴러가 커밋 순서를 앞질러 읽으면 갭이 생긴다.

Debezium 은 폴링 대신 MySQL 의 복제 프로토콜에 직접 붙는다. 서버 입장에서는 슬레이브가 하나 더 붙은 것이고, binlog 는 이미 커밋 순서대로 직렬화되어 있으므로 순서 문제가 원천적으로 사라진다.

## 2. binlog row 이벤트에서 change event 까지

Debezium MySQL 커넥터가 동작하려면 서버 설정이 다음을 만족해야 한다.

```ini
[mysqld]
server-id                = 184054
log_bin                  = mysql-bin
binlog_format            = ROW
binlog_row_image         = FULL
gtid_mode                = ON
enforce_gtid_consistency = ON
binlog_expire_logs_seconds = 604800
```

`binlog_format = ROW` 는 필수다. `STATEMENT` 는 실행된 SQL 문만 남기므로 변경 전후 값을 복원할 수 없다. `binlog_row_image = FULL` 은 UPDATE 이벤트에 변경되지 않은 컬럼까지 before/after 양쪽에 담게 한다. `MINIMAL` 로 두면 before 에는 PK 만, after 에는 변경된 컬럼만 들어가 다운스트림에서 전체 행을 재구성할 수 없다. FULL 은 binlog 용량을 늘리지만 CDC 를 쓸 거면 선택지가 없다.

커넥터 사용자에게 필요한 권한은 다음과 같다.

```sql
CREATE USER 'debezium'@'%' IDENTIFIED BY '...';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
	ON *.* TO 'debezium'@'%';
```

`REPLICATION SLAVE` 로 binlog 스트림을 읽고, `RELOAD` 로 스냅샷 시 글로벌 읽기 락을 걸고, `SELECT` 로 스냅샷 데이터를 읽는다. MySQL 8.0.22+ 에서는 `RELOAD` 대신 `FLUSH_TABLES` 권한으로 대체할 수 있다.

커넥터가 만들어내는 change event 의 value 구조는 다음과 같다.

```json
{
  "before": { "id": 8842, "status": "PENDING", "amount": "150.00" },
  "after":  { "id": 8842, "status": "PAID",    "amount": "150.00" },
  "source": {
    "version": "2.7.0.Final",
    "connector": "mysql",
    "name": "shopdb",
    "ts_ms": 1788400000000,
    "snapshot": "false",
    "db": "shop",
    "table": "orders",
    "server_id": 184054,
    "gtid": "3e11fa47-71ca-11e1-9e33-c80aa9429562:23",
    "file": "mysql-bin.000042",
    "pos": 1093820,
    "row": 0
  },
  "op": "u",
  "ts_ms": 1788400000123
}
```

`op` 는 `c`(create) / `u`(update) / `d`(delete) / `r`(read, 스냅샷) 네 가지다. DELETE 는 `after: null` 인 이벤트 하나와, 그 뒤 같은 키에 `value: null` 인 **툼스톤 레코드** 하나가 연속으로 나간다. 툼스톤은 Kafka 로그 컴팩션이 해당 키를 물리적으로 제거하게 만드는 마커다. 컨슈머는 `value == null` 을 반드시 처리해야 하며, 역직렬화기가 null 에서 NPE 를 내지 않는지 확인해야 한다. 컴팩션을 쓰지 않는다면 `tombstones.on.delete: false` 로 끌 수 있다.

`decimal` 타입은 기본적으로 `io.debezium.data.VariableScaleDecimal` 또는 base64 인코딩된 바이트로 나간다. 사람이 읽을 값이 필요하면 `decimal.handling.mode: string` 을, 정밀도 손실을 감수하고 숫자로 받으려면 `double` 을 쓴다. 금액 컬럼은 `string` 이 안전하다.

## 3. 초기 스냅샷 — 락 범위와 무중단 조건

커넥터가 처음 뜨면 binlog 의 현재 위치를 잡기 전에 기존 데이터를 전부 읽어야 한다. 이 과정에서 "스냅샷 시점" 과 "binlog 시작 위치" 가 정확히 일치해야 중복도 누락도 없다.

기본 모드(`snapshot.mode: initial`)의 순서는 다음과 같다.

1. `FLUSH TABLES WITH READ LOCK` — 전역 읽기 락 획득 (모든 쓰기 차단)
2. `START TRANSACTION WITH CONSISTENT SNAPSHOT` — REPEATABLE READ 스냅샷 고정
3. `SHOW MASTER STATUS` — binlog 파일명/위치/GTID 세트 기록
4. `UNLOCK TABLES` — 전역 락 해제 (여기까지가 수 ms~수백 ms)
5. 각 테이블을 `SELECT` 로 읽으며 `op: r` 이벤트 발행 (오래 걸림, 락 없음)
6. 3번에서 기록한 위치부터 binlog 스트리밍 시작

핵심은 전역 락이 3번까지만 유지된다는 점이다. 실제 데이터 읽기는 트랜잭션 스냅샷 격리로 처리되므로 락이 없다. 다만 1번에서 장기 실행 쿼리가 있으면 `FLUSH TABLES WITH READ LOCK` 이 그 쿼리를 기다리며 그동안 모든 쓰기가 대기한다. 배치 작업이 도는 시간대는 피해야 한다.

모드별 비교는 다음과 같다.

| snapshot.mode | 동작 | 용도 |
|---|---|---|
| `initial` | 스냅샷 후 스트리밍 | 기본. 최초 구축 |
| `initial_only` | 스냅샷만 하고 종료 | 일회성 벌크 적재 |
| `no_data` (구 `schema_only`) | 스키마만 읽고 현재 binlog 위치부터 스트리밍 | 기존 데이터 불필요 |
| `when_needed` | 오프셋이 유효하지 않으면 자동 재스냅샷 | binlog 만료 복구 자동화 |
| `never` | 스냅샷 없이 저장된 오프셋부터 | 오프셋이 반드시 유효할 때만 |

전역 락조차 허용되지 않는 환경(RDS 등에서 `RELOAD` 권한이 없는 경우)에서는 `snapshot.locking.mode: none` 을 쓴다. 이 경우 스냅샷 도중 발생한 변경이 스냅샷 결과와 binlog 양쪽에 나타날 수 있는데, 다운스트림이 키 기준 upsert 라면 최종 상태는 수렴하므로 실무에서 대체로 허용된다. 반대로 이벤트를 append-only 로 소비하는 파이프라인이라면 중복이 그대로 노출된다.

### 증분 스냅샷

운영 중 테이블을 추가하거나 특정 테이블만 다시 읽어야 할 때, 커넥터를 재시작해 전체 스냅샷을 다시 도는 것은 비현실적이다. Debezium 의 증분 스냅샷(DDD-3 알고리즘)은 시그널 테이블에 행을 넣어 트리거한다.

```sql
CREATE TABLE shop.debezium_signal (
	id   VARCHAR(42) PRIMARY KEY,
	type VARCHAR(32) NOT NULL,
	data VARCHAR(2048) NULL
);

INSERT INTO shop.debezium_signal (id, type, data) VALUES (
	'ad-hoc-1',
	'execute-snapshot',
	'{"data-collections": ["shop.customers"], "type": "INCREMENTAL"}'
);
```

커넥터는 테이블을 PK 순으로 청크(기본 1024행) 단위로 읽으면서, 동시에 들어오는 binlog 이벤트와 청크 결과를 창(window) 안에서 중복 제거한다. 스트리밍이 멈추지 않고 락도 걸지 않는다. `incremental.snapshot.chunk.size` 를 키우면 처리량이 늘지만 창 관리 메모리가 늘어난다.

## 4. Outbox Event Router SMT

CDC 를 테이블에 그대로 걸면 토픽이 `shopdb.shop.orders` 처럼 내부 스키마에 종속된다. 컬럼 하나 이름만 바꿔도 컨슈머가 깨진다. Outbox 패턴을 유지하되 폴러 대신 CDC 를 쓰면 이 결합을 끊을 수 있다.

```sql
CREATE TABLE shop.outbox (
	id            BINARY(16)   NOT NULL PRIMARY KEY,
	aggregatetype VARCHAR(64)  NOT NULL,
	aggregateid   VARCHAR(64)  NOT NULL,
	type          VARCHAR(64)  NOT NULL,
	payload       JSON         NOT NULL,
	tracingspancontext VARCHAR(256) NULL,
	created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
```

애플리케이션은 도메인 트랜잭션 안에서 이 테이블에 INSERT 만 한다.

```java
@Transactional
public void markPaid(Long orderId, String receiptId) {
	Order order = orderRepository.findById(orderId).orElseThrow();
	order.markPaid(receiptId);

	OutboxEvent event = OutboxEvent.builder()
			.id(UUID.randomUUID())
			.aggregateType("Order")
			.aggregateId(String.valueOf(orderId))
			.type("OrderPaid")
			.payload(objectMapper.valueToTree(OrderPaidPayload.from(order)))
			.build();
	outboxRepository.save(event);
	outboxRepository.delete(event);   // 즉시 삭제해도 binlog 에는 INSERT 가 남는다
}
```

`save` 직후 `delete` 하는 관용구가 핵심이다. 두 문 모두 같은 트랜잭션에서 binlog 에 기록되므로 Debezium 은 INSERT 이벤트를 정상적으로 발행하지만, 테이블 자체는 비어 있는 상태를 유지해 별도 정리 배치가 필요 없다. Hibernate 가 두 문을 실제로 발행하도록 `@Transactional` 안에서 `flush()` 순서를 확인해야 한다 — 같은 영속성 컨텍스트에서 저장 후 즉시 삭제하면 Hibernate 가 두 문을 생략할 수 있으므로, 안전하게는 네이티브 쿼리로 INSERT 후 DELETE 를 명시한다.

커넥터 설정에 SMT 를 건다.

```json
{
  "name": "shop-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql-primary",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "${file:/secrets/db.properties:password}",
    "database.server.id": "184054",
    "topic.prefix": "shopdb",
    "database.include.list": "shop",
    "table.include.list": "shop.outbox",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-history.shopdb",
    "tombstones.on.delete": "false",

    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "aggregatetype",
    "transforms.outbox.route.topic.replacement": "domain.${routedByValue}",
    "transforms.outbox.table.field.event.key": "aggregateid",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.fields.additional.placement": "type:header:eventType,created_at:header:eventTime",
    "transforms.outbox.table.expand.json.payload": "true",

    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

결과적으로 `aggregatetype = "Order"` 인 행은 `domain.Order` 토픽으로, 키는 `aggregateid` 로, 값은 `payload` JSON 그 자체로 나간다. `type` 은 Kafka 헤더 `eventType` 에 들어가 컨슈머가 역직렬화 대상을 고를 수 있다. 컨슈머는 `outbox` 테이블 스키마를 전혀 모르고, DB 컬럼을 바꿔도 계약이 유지된다.

`table.expand.json.payload: true` 는 MySQL JSON 컬럼을 문자열이 아니라 구조화된 Connect 스키마로 펼친다. 이걸 끄면 payload 가 이스케이프된 문자열로 전달되어 컨슈머가 이중 파싱해야 한다.

## 5. 오프셋·스키마 히스토리와 복구

Debezium 이 관리하는 상태는 세 곳에 흩어져 있다.

| 저장소 | 내용 | Kafka Connect 기준 위치 |
|---|---|---|
| 오프셋 토픽 | 마지막으로 처리한 binlog 파일/위치/GTID 세트 | `connect-offsets` |
| 스키마 히스토리 토픽 | 지금까지 관측한 모든 DDL 문 | `schema-history.<prefix>` |
| 컨피그 토픽 | 커넥터 설정 | `connect-configs` |

스키마 히스토리가 별도로 필요한 이유는 binlog row 이벤트에 컬럼 이름이 없기 때문이다. binlog 는 컬럼 순서와 값만 담으므로, 그 시점의 테이블 정의를 알아야 이름을 붙일 수 있다. 커넥터는 재시작 시 스키마 히스토리 토픽을 처음부터 재생해 각 시점의 테이블 구조를 복원한 뒤 스트리밍을 이어간다. 따라서 **스키마 히스토리 토픽은 절대 컴팩션하거나 삭제하면 안 된다**. `cleanup.policy: delete` + 짧은 retention 으로 잘못 만들면 커넥터가 영구 복구 불가 상태에 빠진다. 생성 시 `cleanup.policy=delete`, `retention.ms=-1`, `partitions=1`, `replication.factor=3` 이 권장 값이다.

장애 시나리오별 대응은 다음과 같다.

- **커넥터가 며칠 멈춰 binlog 가 만료됨**: 저장된 GTID 가 서버의 `gtid_purged` 에 포함되어 스트리밍이 불가능하다. `snapshot.mode: when_needed` 였다면 자동으로 재스냅샷한다. 아니면 오프셋을 지우고 `initial` 로 재시작하거나, 다운스트림이 upsert 라면 증분 스냅샷으로 테이블별 재동기화를 건다.
- **스키마 히스토리 토픽 유실**: 커넥터가 `Encountered change event ... whose schema isn't known` 로 죽는다. 복구는 재스냅샷뿐이다. 이 토픽 백업이 사실상 CDC 파이프라인의 백업이다.
- **DB 페일오버**: GTID 를 켜뒀다면 새 프라이머리에서 같은 GTID 세트 이후부터 이어갈 수 있다. GTID 없이 파일/포지션만 쓰면 페일오버 후 위치가 무의미해져 재스냅샷이 필요하다. 이것이 `gtid_mode: ON` 을 강하게 권하는 이유다.

## 6. 전달 보장과 순서

Debezium 은 **at-least-once** 를 보장한다. 커넥터가 오프셋을 커밋하기 전에 죽으면 마지막 몇 이벤트가 재발행된다. 컨슈머는 멱등해야 하며, `source.gtid` + `source.pos` + `source.row` 조합이나 outbox 의 `id` 를 중복 제거 키로 쓸 수 있다.

순서 보장은 파티션 단위다. Outbox Router 가 `aggregateid` 를 키로 잡으므로 같은 주문의 이벤트는 같은 파티션에 들어가 순서가 유지된다. 서로 다른 애그리거트 간 전역 순서는 보장되지 않으며, 필요하다면 파티션을 1개로 두는 대가를 치러야 한다. 대부분의 도메인에서 애그리거트 단위 순서면 충분하다.

`exactly.once.support: required` (Kafka Connect 3.3+ 소스 커넥터 EOS)를 켜면 Connect 가 트랜잭셔널 프로듀서로 오프셋과 레코드를 원자적으로 커밋한다. 처리량이 20~30% 정도 떨어지고 컨슈머도 `read_committed` 여야 하므로, 중복 제거가 가능한 파이프라인이면 굳이 켜지 않는 편이 낫다.

## 7. 처리량 튜닝과 관측

주요 파라미터는 다음과 같다.

```json
"max.batch.size": 4096,
"max.queue.size": 16384,
"max.queue.size.in.bytes": 268435456,
"poll.interval.ms": 500,
"producer.override.compression.type": "zstd",
"producer.override.linger.ms": 20,
"producer.override.batch.size": 262144
```

`max.queue.size` 는 binlog 리더 스레드와 Connect 폴링 스레드 사이의 큐 크기다. 큐가 가득 차면 binlog 리더가 블록되고 지연이 쌓인다. `max.queue.size.in.bytes` 를 함께 걸어 큰 행(BLOB 등)이 힙을 터뜨리지 않게 한다.

모니터링은 JMX MBean 으로 노출된다.

| 지표 | MBean 속성 | 의미 |
|---|---|---|
| `MilliSecondsBehindSource` | streaming | DB 커밋 시각 대비 지연 |
| `QueueRemainingCapacity` | streaming | 큐 여유. 0 근처면 다운스트림 병목 |
| `NumberOfCommittedTransactions` | streaming | 처리한 트랜잭션 수 |
| `SnapshotCompleted` / `RowsScanned` | snapshot | 스냅샷 진행률 |
| `NumberOfEventsFiltered` | streaming | include.list 로 걸러진 수 |

`MilliSecondsBehindSource` 가 지속 상승하면 원인이 소스(binlog 생성 속도)인지 싱크(Kafka 프로듀서)인지 `QueueRemainingCapacity` 로 구분한다. 큐가 가득 차 있으면 싱크 병목, 여유로우면 소스에서 못 읽고 있는 것이다.

Prometheus 로 뽑을 때는 커넥터별 라벨을 붙이고, 지연 알람은 절대값(예: 30초)보다 "5분 이동평균이 계속 증가" 조건이 오탐이 적다. 배치 작업 중에는 순간 지연이 자연스럽게 튀기 때문이다.

## 8. 대안과 선택 기준

| 방식 | 지연 | DB 부하 | 순서 보장 | 운영 복잡도 |
|---|---|---|---|---|
| Outbox 폴링 | 폴링 주기 | 지속 SELECT/UPDATE | 약함(id 갭) | 낮음 |
| Debezium + Kafka Connect | 수십 ms~수 s | binlog 읽기만 | 강함(커밋 순서) | 높음(Connect 클러스터) |
| Debezium Server | 동일 | 동일 | 동일 | 중간(단일 프로세스) |
| Debezium Engine 임베디드 | 동일 | 동일 | 동일 | 중간(HA 직접 구현) |
| 트리거 기반 CDC | 즉시 | 쓰기마다 추가 INSERT | 강함 | 중간(DB 로직 증가) |

Kafka 를 이미 운영 중이라면 Debezium + Connect 가 표준 선택이다. Kafka 가 없고 목적지가 하나라면 Debezium Server 가 Connect 클러스터 운영 부담을 없애준다. 애플리케이션 수가 적고 이벤트 볼륨이 낮으면(초당 수십 건 이하) Outbox 폴링이 여전히 합리적이다 — CDC 는 인프라 구성요소가 3개(Connect, 스키마 히스토리 토픽, 오프셋 토픽) 늘어나고 각각 장애 모드가 있다.

한 가지 흔한 실수는 CDC 를 "서비스 간 통신 수단" 으로 쓰는 것이다. 내부 테이블 변경을 그대로 토픽으로 흘리면 다른 서비스가 남의 스키마에 결합된다. Outbox Router 를 거쳐 *도메인 이벤트* 만 내보내는 것이 CDC 를 안전하게 쓰는 유일한 방법이다. CDC 자체는 전송 메커니즘이지 계약이 아니다.

## 참고

- Debezium Documentation — MySQL Connector (https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- Debezium Documentation — Outbox Event Router (https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- Debezium Blog — Incremental Snapshots (DDD-3 design document)
- MySQL 8.0 Reference Manual — Replication with Global Transaction Identifiers
- Gunnar Morling, *Practical Change Data Capture with Debezium*
