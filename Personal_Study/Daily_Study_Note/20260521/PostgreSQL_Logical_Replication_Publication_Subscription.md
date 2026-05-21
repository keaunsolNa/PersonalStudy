Notion 원본: https://www.notion.so/3675a06fd6d381f19c85dc7e0b62209e

# PostgreSQL Logical Replication과 Publication/Subscription 운영

> 2026-05-21 신규 주제 · 확장 대상: PostgreSQL MVCC·VACUUM(20260519), pg_stat_statements(20260514)

## 학습 목표

- WAL 레벨에서 physical과 logical replication의 stream 차이를 구분한다.
- Publication / Subscription / Replication Slot의 책임 분담과 lag 누적의 영향을 안다.
- DDL 미전파, sequence 동기화, conflict 시 대응을 설계한다.
- 메이저 버전 무중단 업그레이드에 logical replication을 활용한다.

## 1. Physical vs Logical

Physical: WAL byte stream 그대로, 메이저·OS·arch 동일 강제. Logical: decoder가 row 변경으로 변환 → (1) 메이저 cross, (2) 부분 복제, (3) 양방향.

| 항목 | Physical | Logical |
|---|---|---|
| 단위 | WAL byte | row 변경 |
| DDL 전파 | 자동 | 미지원 |
| 메이저 cross | 불가 | 가능(>=10) |
| 부분 복제 | 불가 | publication 단위 |
| 시퀀스 | 자동 | 미지원 |

## 2. 기본 setup

```sql
-- Publisher
ALTER SYSTEM SET wal_level = 'logical';
ALTER SYSTEM SET max_replication_slots = 10;
ALTER SYSTEM SET max_wal_senders = 10;

CREATE PUBLICATION pub_orders FOR TABLE orders, order_items
WITH (publish = 'insert, update, delete');

CREATE ROLE rep_user WITH REPLICATION LOGIN PASSWORD 'xxx';
GRANT SELECT ON orders, order_items TO rep_user;

-- Subscriber
CREATE SUBSCRIPTION sub_orders
CONNECTION 'host=publisher.internal port=5432 dbname=ordersdb user=rep_user password=xxx'
PUBLICATION pub_orders
WITH (copy_data = true, create_slot = true, slot_name = 'sub_orders_slot');
```

## 3. Replication Slot

```sql
SELECT slot_name,
	pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag_bytes,
	active
FROM pg_replication_slots WHERE slot_type = 'logical';
```

lag 누적 시 pg_wal 디스크 가득 → publisher read-only. `max_slot_wal_keep_size = '50GB'` 안전선, 초과 시 slot lost 상태로 전환 → initial copy 재시작.

## 4. DDL 미전파

`ALTER TABLE orders ADD COLUMN coupon_code TEXT` 시 subscriber에 컬럼 없으므로 INSERT 도착 → 에러로 stream 중단.

절차: (1) subscriber 먼저 컬럼 추가, (2) publisher 그다음, (3) 애플리케이션 배포, (4) 제약 추가. PG 16 `pglogical`이 DDL 일부 지원하나 부분적.

## 5. Sequence 동기화

Sequence는 logical stream에 포함 안 됨. failover 시 PK 충돌.

```sql
SELECT setval('users_id_seq',
	(SELECT last_value FROM remote.users_id_seq) + 1000,
	false);
```

더 안전: PK를 UUID v7로 전환.

## 6. Conflict 처리

PG 17 `disable_on_error = true` 옵션:

```sql
ALTER SUBSCRIPTION sub_orders SET (disable_on_error = true);
SELECT subname, last_msg_send_time, last_msg_receipt_time, sub_state
FROM pg_stat_subscription;
```

`sub_state = 'd'`면 사람 개입.

## 7. 메이저 버전 무중단 업그레이드

1. 새 메이저 cluster (14 → 16)
2. 14 → 16 subscription, initial copy
3. lag < 5s 대기
4. 14를 read-only
5. 마지막 LSN 확인
6. connection 16으로
7. 16 → 14 reverse subscription (롤백)
8. 24h 후 14 폐기

자체 700GB DB에서 4~6단계 11초. PG 17 `pg_createsubscriber`로 standby 자리에서 변환 — initial copy 사실상 이해 0.

## 8. Row Filter와 Column List

```sql
CREATE PUBLICATION pub_tenant_a
FOR TABLE orders (id, user_id, amount, created_at)
	WHERE (tenant_id = 'tenant_a');
```

filter 벗어나는 row UPDATE 시 subscriber에 DELETE처럼 전달.

## 9. 운영 trade-off

logical은 (1) 메이저 cross, (2) 부분 복제, (3) 무중단 마이그레이션 핵심 도구. 단 WAL decode CPU 5~15% 추가, DDL·sequence 수동, slot 누적이 publisher 죽일 위험, 충돌 자동화 부족. 순수 read replica는 physical이 단순·안전.

## 10. 모니터링 baseline

```sql
SELECT slot_name,
	pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS lag_bytes,
	now() - active_since AS slot_age, active
FROM pg_replication_slots WHERE slot_type = 'logical';

SELECT subname, pid, received_lsn, latest_end_lsn,
	now() - last_msg_receipt_time AS heartbeat_age
FROM pg_stat_subscription;

SELECT srsubid::regclass AS subscription,
	srrelid::regclass AS table, srsubstate
FROM pg_subscription_rel WHERE srsubstate <> 'r';
```

임계값: lag > 10GB(WARN)/50GB(CRIT), heartbeat > 60s/300s, pg_wal > 500GB. `srsubstate`가 가장 놓치는 지표.

## 참고

- PostgreSQL 17 docs — Logical Replication
- pg_createsubscriber — postgresql.org/docs/17
- 'PostgreSQL Replication' — Hans-Jürgen Schönig, 4판
- pgconf.eu 2025
- 사내 14→16 무중단 업그레이드 회고(2026-03)
