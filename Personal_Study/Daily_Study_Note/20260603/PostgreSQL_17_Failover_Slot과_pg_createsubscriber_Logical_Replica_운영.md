Notion 원본: https://www.notion.so/3745a06fd6d381e3a89dd87d17348aeb

# PostgreSQL 17 Failover Slot과 pg_createsubscriber Logical Replica 운영

> 2026-06-03 신규 주제 · 확장 대상: Oracle

## 학습 목표

- PostgreSQL 17 의 *failover slot* 이 standby 승격 후 logical subscriber 의 데이터 손실을 어떻게 막는지 따라간다
- `pg_createsubscriber` 가 physical standby 를 *zero-dump logical replica* 로 변환하는 절차를 단계별로 분해한다
- WAL retention 과 `max_slot_wal_keep_size` 의 상호작용을 운영 관점에서 정리한다
- DDL 전파와 v17 의 column list / row filter 한계를 점검한다

## 1. logical replication 의 약점 — failover

PostgreSQL 의 logical replication 은 *replication slot* 을 통해 subscriber 가 어디까지 받았는지 (`confirmed_flush_lsn`) 를 추적한다. slot 은 publisher 측에만 존재한다. publisher 의 primary 가 failover 되면 — slot 정보가 standby 에 *없으므로* — subscriber 는 *복제를 처음부터 다시* 해야 했다. v16 까지 이게 logical replication 운영의 가장 큰 함정이었다.

## 2. Failover Slot 의 동작

```sql
SELECT pg_create_logical_replication_slot('orders_slot', 'pgoutput', false, true);
```

v17 의 신규 GUC `sync_replication_slots = on` 이 켜지면 publisher 의 모든 slot 의 `confirmed_flush_lsn`, `restart_lsn`, `catalog_xmin` 이 *physical standby* 로 정기 동기화된다. failover 시 새 primary 는 마지막으로 동기화된 slot 상태를 그대로 가진다.

## 3. pg_createsubscriber — physical → logical 전환

```bash
pg_createsubscriber \
  --pgdata=/var/lib/postgresql/17/standby \
  --publisher-server="host=pub.db port=5432 dbname=prod user=repl" \
  --database=prod \
  --publication=orders_pub \
  --subscription=orders_sub
```

전체가 ~수 분. 100 GB 이상 데이터셋도 promotion 시간 + slot 생성 시간 뿐이다.

## 4. WAL retention 의 함정 — slot 이 쌓이는 경우

slot 의 `restart_lsn` 보다 오래된 WAL 은 *재활용 불가*. subscriber 가 느려지면 publisher 의 `pg_wal` 이 무한정 커진다. v13 의 `max_slot_wal_keep_size` 가 안전장치. slot 의 WAL 이 한계 넘으면 invalidate. subscriber 는 *완전 재초기화* 필요.

## 5. publication 의 column list / row filter

```sql
CREATE PUBLICATION orders_pub
  FOR TABLE orders (id, total, status) WHERE (status IN ('PAID', 'REFUNDED'))
  WITH (publish_via_partition_root = true);
```

v17 제약: generated column 은 v17 부터 가능. sequence 는 여전히 전파 안 됨. DDL 도 자동 전파 안 됨.

## 6. v17 의 신규 wait events 와 모니터링

`LogicalLauncherMain`, `LogicalApplyMain`, `LogicalParallelApplyMain` 이 새 항목. v17 은 *parallel apply* 지원. `streaming = parallel` 이 켜지면 large transaction 을 chunk 단위로 stream, 여러 apply worker 가 commit 순서 유지하여 병렬 적용. 큰 batch 의 replication lag 가 5–10x 줄어든다.

## 7. Oracle GoldenGate / DMS 와의 비교

| 항목 | PG 17 logical + failover slot | Oracle GoldenGate | AWS DMS |
|---|---|---|---|
| 라이선스 | open source | 상용 | AWS 청구 |
| Failover-aware | v17 native | Extract integrated | task 재구성 |
| DDL 전파 | X | O | 제한적 |
| Sequence 전파 | X | O | O |
| Parallel apply | v17 | O | 부분적 |
| Heterogeneous | X | O | O |

요약: PG ↔ PG migration 이면 v17 native 로 충분. 이종 / DDL 전파가 필요하면 GoldenGate / Debezium + Kafka.

## 8. Failover 시나리오 시뮬레이션

사전 조건: `sync_replication_slots = on`, standby 동기화 완료, subscriber conninfo 추상화. 총 무중단 1–5 초. v16 은 수 분 이상 수동 작업이 필요했다.

## 9. Trade-off

장점: PG 17 failover slot + pg_createsubscriber 조합은 외부 도구 없이 HA logical replication 제공. dump-restore 가 사라지고, parallel apply 로 lag 단축, failover 자동 회복. 단점: DDL 전파 부재, sequence 전파 부재. 해결책은 UUID v7 / ULID, failover script 에 setval 자동화.

## 참고

- PostgreSQL 17 Release Notes — Replication
- pg_createsubscriber docs: https://www.postgresql.org/docs/17/app-pgcreatesubscriber.html
- Robert Haas — Failover slots design notes
- Debezium PostgreSQL connector
- Patroni docs — failover and synchronous replication
