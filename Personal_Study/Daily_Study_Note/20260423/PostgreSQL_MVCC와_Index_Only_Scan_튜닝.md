Notion 원본: https://www.notion.so/34b5a06fd6d381b1b77df8708a79a120

# PostgreSQL MVCC와 Index-Only Scan 튜닝

> 2026-04-23 신규 주제 · 확장 대상: DataBase (Oracle / 최적화 기본 학습됨)

## 학습 목표

- MVCC의 튜플 헤더(xmin/xmax)와 visibility map의 역할을 실제 `pg_visibility`로 확인한다
- Index-Only Scan이 실제로 인덱스만으로 끝나는지 `EXPLAIN (ANALYZE, BUFFERS)`로 판정한다
- VACUUM / autovacuum과 visibility map의 의존 관계를 수치로 관찰한다
- Oracle의 UNDO 기반 MVCC와 다른 점을 아카이브/장기 트랜잭션 관점에서 비교한다

---

## 1. PostgreSQL MVCC의 기본 구조

Postgres의 MVCC는 "UPDATE하면 새 row를 append하고, 기존 row의 xmax를 채운다"는 append-only 전략이다. 각 튜플에는 `xmin`(생성 TXID), `xmax`(삭제/업데이트 TXID), `ctid`(물리 위치), `infomask` 비트 플래그가 붙는다. 이 모델의 대가는 튜플이 물리적으로 남아 있다는 점이고, VACUUM이 이걸 회수한다. Oracle은 UNDO 세그먼트에 옛 버전을 따로 두고 데이터 파일은 제자리 수정이라 VACUUM 같은 개념이 없는 대신 UNDO가 꽉 차면 "snapshot too old"가 난다.

```sql
CREATE EXTENSION IF NOT EXISTS pageinspect;
SELECT t_xmin, t_xmax, t_ctid, t_infomask::bit(16)
  FROM heap_page_items(get_raw_page('orders', 0))
 LIMIT 10;
```

이 결과에서 `t_xmax`가 0이면 현재 버전, 0이 아니면 이미 대체되었거나 삭제된 튜플이다. `infomask`의 `HEAP_XMIN_COMMITTED`, `HEAP_XMAX_COMMITTED` 비트가 켜져 있으면 커밋 확정 상태다.

## 2. Visibility Map 과 Index-Only Scan

Index-Only Scan은 쿼리가 요구하는 모든 컬럼이 인덱스에 포함되고 해당 heap 페이지가 visibility map에서 "all-visible"로 마킹되어 있을 때 heap fetch를 건너뛰어 동작한다. VACUUM이 all-visible 비트를 세운다. 즉 대량 INSERT 직후에는 Index-Only Scan이 안 나올 수 있다 — heap fetch가 필요해진다.

```sql
CREATE EXTENSION IF NOT EXISTS pg_visibility;
SELECT all_visible, all_frozen
  FROM pg_visibility_map('orders')
 LIMIT 5;
```

`all_visible`이 false인 페이지 비율이 높다면 Index-Only Scan 효과가 떨어진다. `VACUUM (VERBOSE, ANALYZE) orders`로 강제 실행 후 비율 변화를 확인한다.

## 3. EXPLAIN 으로 판정하기

```sql
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT user_id, created_at FROM orders
 WHERE user_id = 1001 ORDER BY created_at DESC LIMIT 20;
```

결정적 지표는 `Heap Fetches: 0`. 0이 아니면 해당 페이지가 아직 all-visible이 아니라서 heap을 건드렸다는 뜻이다. `BUFFERS` 절로 shared hit/read 수치도 함께 봐야 "인덱스만 읽었는데 왜 I/O가 크지" 같은 오해를 막을 수 있다.

## 4. Covering Index (INCLUDE) 로 Index-Only 유도

```sql
CREATE INDEX idx_orders_user_covering
    ON orders (user_id, created_at DESC)
    INCLUDE (item_id, qty);
```

`INCLUDE`는 non-key 컬럼을 leaf 페이지에 붙여 인덱스 검색에는 참여시키지 않으면서 Index-Only Scan 커버리지를 확장한다. 단점은 인덱스 크기가 커진다는 것 — 인덱스 scan 비용이 커져 범위 질의 성능이 떨어질 수 있으니 hot query에만 붙인다.

## 5. HOT Update 와 인덱스 뚱뚱해짐

Postgres의 HOT(Heap-Only Tuple) update는 업데이트된 컬럼이 어떤 인덱스에도 포함되지 않고 같은 페이지에 여유 공간이 있을 때 적용된다. HOT이 성립하면 새 튜플 버전은 같은 페이지에 삽입되고 인덱스 엔트리가 그대로 유효하다.

HOT이 깨지는 흔한 실수: 큰 JSON 컬럼 중 expression index를 걸었는데 업데이트는 그 JSON을 건드린다 → HOT 불가 → 인덱스가 매 업데이트마다 새 엔트리 쓰기. `pg_stat_user_tables.n_tup_hot_upd / n_tup_upd` 비율이 0.7 이하이면 의심.

## 6. autovacuum 파라미터 튜닝

```sql
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold    = 10000,
    autovacuum_analyze_scale_factor = 0.01,
    autovacuum_vacuum_cost_limit    = 2000
);
```

`pg_stat_user_tables`로 n_dead_tup 및 last_autovacuum 관찰. 대용량 테이블은 scale_factor를 기본값(0.2)으로 두면 수천만 dead tuple이 쌓이고 나서야 돌아서 I/O 스파이크가 크다. 2~5% 수준으로 낮추는 것이 권장.

## 7. TXID wraparound 와 freeze

Postgres TXID는 32비트라 ~20억번 트랜잭션 후 wraparound가 발생하고, `autovacuum_freeze_max_age`(기본 2억)을 넘어서면 anti-wraparound VACUUM이 강제로 실행되고 대형 테이블에서는 이게 긴 시간동안 I/O를 점유해 장애의 원인이 된다. 14+에서는 64비트 XID 논의가 있지만 아직 프로덕션 기본은 32비트다. `SELECT datname, age(datfrozenxid) FROM pg_database`로 남은 여유를 주기적으로 모니터링.

## 8. 장기 트랜잭션의 비용

긴 트랜잭션이 열려 있으면 그 snapshot이 "필요"로 보는 옛 튜플을 VACUUM이 회수하지 못한다. `pg_stat_activity`에서 `state='idle in transaction'`을 모니터링하고, `idle_in_transaction_session_timeout`을 설정해 강제 종료한다. 리포팅 쿼리가 애매하게 30분씩 열려 있으면 hot 테이블의 dead tuple이 빠르게 누적되어 Index-Only Scan 효과가 사라지는 부작용이 난다.

## 9. Oracle MVCC 와의 대조

| 항목 | PostgreSQL | Oracle |
|---|---|---|
| 옛 버전 저장 | 테이블 내 append (dead tuple) | UNDO tablespace |
| 회수 메커니즘 | VACUUM | UNDO retention |
| 긴 조회 실패 | bloat 증가 | ORA-01555 |
| wraparound | TXID 32bit freeze 필요 | SCN 64bit 사실상 무한 |

Oracle에서 오던 엔지니어는 "VACUUM이라는 별도 유지보수 작업이 정말 있구나"에서 놀라고, Postgres에서 오던 엔지니어는 "UNDO 용량 계산이 생각보다 까다롭구나"에서 놀란다. 두 모델 모두 MVCC라는 같은 목표에 다른 트레이드오프를 했을 뿐이다.

## 참고

- PostgreSQL 공식 문서 — Routine Vacuuming: https://www.postgresql.org/docs/current/routine-vacuuming.html
- PostgreSQL 공식 문서 — Index-Only Scans: https://www.postgresql.org/docs/current/indexes-index-only-scans.html
- Peter Geoghegan — "VACUUM internals" (PGCon 2021)
- Bruce Momjian — "MVCC Unmasked"
