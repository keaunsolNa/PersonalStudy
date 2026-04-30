Notion 원본: https://app.notion.com/p/3525a06fd6d38194be7dc8156edfcc61

# PostgreSQL Autovacuum과 Bloat 측정·튜닝

> 2026-04-30 신규 주제 · 확장 대상: PostgreSQL · MVCC

## 학습 목표

- MVCC 가 만든 dead tuple 이 autovacuum 트리거 임계값에 닿는 과정 추적
- `pgstattuple` / `pg_stat_user_tables` 로 bloat 비율을 정량 측정
- `autovacuum_vacuum_scale_factor` / `_threshold` / `_cost_limit` 의 상호작용 실험
- 대용량 테이블에 `VACUUM FREEZE` 와 `pg_repack` 를 안전하게 적용

## 1. Dead Tuple 이 쌓이는 이유

PostgreSQL 의 MVCC 는 UPDATE 를 "기존 튜플의 xmax 를 기록 → 새 튜플 INSERT" 로 처리한다. 옛 버전은 즉시 사라지지 않는다. 이 옛 버전이 **모든 활성 트랜잭션의 snapshot 보다 과거** 가 되어야 dead tuple 로 분류되어 회수 대상이 된다. 회수의 책임자가 `VACUUM` 이고, 자동으로 도는 게 `autovacuum` 이다.

dead tuple 이 회수되지 않은 채 쌓이면 두 문제를 만든다.

- **공간 bloat**: 디스크가 실제 데이터 + 버려진 행으로 부풀어 SSD 비용·캐시 효율을 깎는다.
- **인덱스 bloat**: B-Tree 가 dead 키를 가리키는 페이지를 그대로 들고 있어 page split 횟수가 늘어난다.

## 2. Autovacuum 트리거 공식

기본 공식은 다음과 같다.

```
threshold = autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * n_live_tup
```

- `autovacuum_vacuum_threshold` 기본 50
- `autovacuum_vacuum_scale_factor` 기본 0.2 (즉 20%)

천만 행 테이블이라면 `50 + 0.2 * 10_000_000 = 2_000_050` 행이 dead 가 되어야 autovacuum 이 시작된다. 실시간 트래픽이 많은 테이블에서 이 공식이 너무 느슨해 bloat 가 누적된다. 운영에서는 `ALTER TABLE` 단위로 줄이는 게 정석이다.

```sql
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 1000,
    autovacuum_analyze_scale_factor = 0.01
);
```

이렇게 하면 천만 행에서도 약 20만 행 dead 시점에 회수가 시작된다.

## 3. Bloat 측정 — pgstattuple 과 추정 쿼리

정확값은 `pgstattuple` 확장이 본다(전 페이지 스캔이라 큰 테이블에선 무겁다).

```sql
CREATE EXTENSION IF NOT EXISTS pgstattuple;
SELECT * FROM pgstattuple('public.orders');
-- table_len | tuple_count | dead_tuple_count | dead_tuple_percent | free_percent
```

운영에서는 시스템 카탈로그로 추정값을 본다.

```sql
SELECT
    schemaname,
    relname,
    n_live_tup,
    n_dead_tup,
    round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_pct,
    last_vacuum,
    last_autovacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY dead_pct DESC NULLS LAST
LIMIT 20;
```

`dead_pct` 가 20% 를 넘는 테이블은 즉시 점검 대상이다.

## 4. Autovacuum 이 제때 못 따라가는 신호

`pg_stat_progress_vacuum` 에 진행 중인 vacuum 의 phase 와 처리한 페이지가 보인다.

```sql
SELECT pid, datname, relid::regclass, phase, heap_blks_total, heap_blks_scanned, num_dead_tuples
FROM pg_stat_progress_vacuum;
```

phase 가 `vacuuming heap` 에 오래 머무르면 IO bound, `cleaning up indexes` 에 머무르면 인덱스 페이지가 너무 많은 상태다. `last_autovacuum` 이 며칠 째 동일하면 `autovacuum_max_workers` 가 부족하거나 `cost_limit` 이 너무 빡빡해서 대기 중인 것이다.

## 5. Cost-based Vacuum Delay

vacuum 이 디스크 IO 를 너무 잡지 않도록 PostgreSQL 은 cost 기반으로 자가 페이스 조절한다.

| 파라미터 | 기본 | 의미 |
|---|---|---|
| autovacuum_vacuum_cost_limit | 200 | 한 cycle 누적 cost 임계 |
| autovacuum_vacuum_cost_delay | 2ms | 임계 도달 후 sleep |
| vacuum_cost_page_hit | 1 | 캐시 페이지 |
| vacuum_cost_page_miss | 10 | 디스크 fetch |
| vacuum_cost_page_dirty | 20 | 더티 페이지 write |

기본값으로는 autovacuum 이 분당 약 2~3MB 만 처리한다. NVMe SSD 시대에는 `cost_limit` 을 1000~2000 으로 올려야 따라온다. `delay` 를 0으로 두는 운영도 흔하다(throughput 우선).

```sql
ALTER SYSTEM SET autovacuum_vacuum_cost_limit = 2000;
ALTER SYSTEM SET autovacuum_vacuum_cost_delay = 2;
SELECT pg_reload_conf();
```

## 6. XID Wraparound 와 VACUUM FREEZE

PostgreSQL 의 트랜잭션 ID 는 32bit 다. 약 21억 트랜잭션이 지나면 wrap around 가 일어나 과거 행이 미래 행으로 보이는 재앙이 발생한다. 이를 막기 위해 `vacuum_freeze_min_age` (기본 5천만), `autovacuum_freeze_max_age` (기본 2억) 가 있다. 이 임계 도달 시 autovacuum 은 **거부할 수 없는 강제 모드** 로 들어가 모든 페이지를 스캔하고 frozen 마킹을 찍는다.

이 강제 freeze 는 IO를 폭주시킨다. 운영에서는 다음 두 가지로 완화한다.

- `vacuum_freeze_min_age` 를 1억 이상으로 올려 freeze 빈도를 줄인다.
- 비활성 시간대에 수동 `VACUUM (FREEZE, VERBOSE) huge_table;` 을 도는 야간 잡을 둔다.

`SELECT datname, age(datfrozenxid) FROM pg_database;` 로 wraparound 까지 남은 여유를 본다. 1.5억을 넘으면 위험 신호.

## 7. pg_repack — 무중단 bloat 제거

`VACUUM FULL` 은 테이블에 ACCESS EXCLUSIVE LOCK 을 잡는다. 운영 트래픽이 있는 테이블에는 못 쓴다. `pg_repack` 은 동일 결과(완전한 공간 회수)를 트리거 기반 incremental copy 로 달성한다.

흐름:
1. 새 빈 테이블 생성, 인덱스 복제.
2. 원본 테이블에 트리거 추가 — INSERT/UPDATE/DELETE 를 변경 로그 테이블에 기록.
3. 원본을 새 테이블로 COPY (이때는 lock 없음).
4. 변경 로그를 적용하면서 따라잡는다.
5. 짧은 ACCESS EXCLUSIVE 시간(수 초) 동안 swap.

```bash
pg_repack -h db.internal -U app -d production -t public.orders --no-superuser-check
```

주의: 디스크 여유가 원본 테이블 크기 + 인덱스 크기만큼 필요하다. 수십 GB 테이블이면 호스트 디스크 50% 이상이 비어 있어야 안전하다.

## 8. HOT Update 활용

UPDATE 가 인덱스 컬럼을 건드리지 않으면 PostgreSQL 은 **HOT(Heap-Only Tuple) update** 를 시도한다. 같은 페이지 내에 새 버전을 두고 인덱스를 건드리지 않는다 → 인덱스 bloat 0. `fillfactor` 를 90 → 70 으로 낮추면 페이지에 여유가 생겨 HOT 비율이 올라간다.

```sql
ALTER TABLE orders SET (fillfactor = 80);
VACUUM FULL orders;  -- 새 fillfactor 로 재배치
```

`pg_stat_user_tables.n_tup_hot_upd / n_tup_upd` 가 80% 이상이면 잘 사용 중. 30% 이하면 인덱스 컬럼이 너무 자주 갱신되거나 fillfactor 조정 여지가 있다.

## 9. 모니터링 대시보드 권장 메트릭

- `dead_tuple_ratio` per table — 5분 주기, 임계 20%
- `last_autovacuum_age` — 12시간 이상이면 alert
- `pg_stat_progress_vacuum.heap_blks_scanned` 변화율 — 0으로 정체되면 hung 의심
- `xid_age(datfrozenxid)` — 1.5억 초과 알림
- `pg_stat_bgwriter.checkpoints_req` — 너무 자주 (분당 1회 이상)면 WAL 설정과 함께 vacuum 도 의심

## 참고

- PostgreSQL 공식 문서, "Routine Vacuuming" (postgresql.org/docs/current/routine-vacuuming.html)
- pgstattuple, pg_repack 확장 문서
- Robert Haas, "Vacuum Internals" 발표 — PGCon 시리즈
- "PostgreSQL Internals" — Hironobu Suzuki, Chapter 6 Vacuum Processing
