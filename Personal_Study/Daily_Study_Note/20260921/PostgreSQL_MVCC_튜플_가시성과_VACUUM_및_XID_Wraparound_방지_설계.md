Notion 원본: https://www.notion.so/3e25a06fd6d381959978d4c2f3145a34

# PostgreSQL MVCC 튜플 가시성과 VACUUM 및 XID Wraparound 방지 설계

> 2026-09-21 신규 주제 · 확장 대상: Oracle, SQLD

## 학습 목표

- 힙 튜플 헤더의 xmin/xmax/t_ctid 로 가시성 판정을 재구성한다
- 데드 튜플·블로트 지표를 조회해 autovacuum 파라미터를 테이블 단위로 조정한다
- XID wraparound 경고에서 freeze 까지의 대응 절차를 순서대로 실행한다
- HOT 업데이트가 성립하는 조건을 fillfactor·인덱스 설계로 유도한다

## 1. Oracle 과 다른 MVCC 구현 — 되돌리지 않고 남겨 둔다

Oracle 은 블록을 제자리에서 갱신하고 이전 이미지를 UNDO 세그먼트에 따로 보관한다. PostgreSQL 은 반대다. **UPDATE 를 새 버전 튜플의 INSERT + 구 버전의 논리적 삭제 표시로 구현**하고, 두 버전이 모두 같은 힙 안에 존재한다. 별도 UNDO 가 없다.

이 선택의 결과가 PostgreSQL 운영의 거의 모든 특징을 만든다.

| 특성 | Oracle(UNDO) | PostgreSQL(힙 내 버전) |
| --- | --- | --- |
| 롤백 비용 | 높음(UNDO 적용) | 거의 0(커밋 안 함) |
| 읽기 일관성 | UNDO 재구성 | 힙 직접 조회 |
| `ORA-01555` 류 | 발생 | 없음 |
| 공간 회수 | 자동(UNDO 순환) | VACUUM 필요 |
| 테이블 블로트 | 없음 | 관리 대상 |
| 인덱스 갱신 | 변경 컬럼만 | 원칙적으로 전부(HOT 예외) |

"롤백이 공짜인 대신 청소가 필요하다"는 교환이다. 그래서 PostgreSQL DBA 의 업무 중 큰 비중이 VACUUM 튜닝에 있다.

## 2. 튜플 헤더와 가시성 판정

모든 힙 튜플은 23바이트 헤더를 갖는다. 가시성에 직접 쓰이는 필드는 넷이다.

```
t_xmin   이 버전을 만든 트랜잭션 ID
t_xmax   이 버전을 삭제/갱신한 트랜잭션 ID (0이면 살아 있음)
t_cid    같은 트랜잭션 내 커맨드 순서
t_ctid   (block, offset) — 갱신된 경우 다음 버전을 가리킴
t_infomask 커밋/중단 힌트 비트, freeze 표시 등
```

스냅샷 `S` 를 가진 트랜잭션이 튜플을 볼 수 있는 조건은 대략 이렇다.

```
visible(t, S) :=
      committed(t.xmin) ∧ t.xmin ∉ S.in_progress ∧ t.xmin < S.xmax
  ∧ ( t.xmax == 0
      ∨ aborted(t.xmax)
      ∨ t.xmax ∈ S.in_progress
      ∨ t.xmax ≥ S.xmax )
```

트랜잭션 상태는 `pg_xact`(구 clog)에서 2비트로 읽고, 한 번 확인한 결과는 `t_infomask` 의 힌트 비트에 기록해 다음 조회를 빠르게 한다. 힌트 비트 갱신은 **읽기 쿼리가 페이지를 더티로 만든다**는 뜻이라, SELECT 만 하는데 WAL·체크포인트 부하가 오르는 현상의 원인이 된다.

실제 값을 눈으로 확인하려면 `pageinspect` 를 쓴다.

```sql
CREATE EXTENSION IF NOT EXISTS pageinspect;

SELECT lp, t_xmin, t_xmax, t_ctid,
       (t_infomask & 256) > 0 AS xmin_committed,
       (t_infomask & 2048) > 0 AS is_frozen
FROM heap_page_items(get_raw_page('orders', 0));
```

`t_ctid` 가 자기 자신이 아닌 다른 위치를 가리키면 그 튜플은 갱신되어 체인의 중간 노드가 된 것이다. 이 체인을 따라가는 비용이 UPDATE 가 잦은 테이블의 숨은 지연 요인이다.

## 3. 데드 튜플과 블로트 측정

커밋된 트랜잭션이 남긴 구 버전은 **어떤 활성 스냅샷에도 보이지 않게 된 시점**부터 데드 튜플이다. 그 경계를 정하는 것이 가장 오래된 수평선(horizon)이며, `pg_stat_activity` 의 `backend_xmin` 과 복제 슬롯·prepared transaction 이 이를 잡아 둔다.

```sql
-- 청소를 막고 있는 주체 찾기
SELECT 'backend' AS src, pid::text AS id, backend_xmin AS xmin,
       now() - xact_start AS age
  FROM pg_stat_activity WHERE backend_xmin IS NOT NULL
UNION ALL
SELECT 'slot', slot_name, xmin, NULL FROM pg_replication_slots WHERE xmin IS NOT NULL
UNION ALL
SELECT 'prepared', gid, transaction::text, now() - prepared FROM pg_prepared_xacts
ORDER BY xmin;
```

이 셋 중 하나가 오래 남아 있으면 VACUUM 이 돌아도 아무것도 회수하지 못한다. **"VACUUM 을 돌렸는데 블로트가 줄지 않는다"의 90% 는 여기에 원인이 있다.** 특히 물리 복제 대기 서버의 `hot_standby_feedback = on` 과 버려진 논리 복제 슬롯이 대표적이다.

블로트 자체는 통계 뷰와 `pgstattuple` 로 본다.

```sql
SELECT relname,
       n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       last_autovacuum, autovacuum_count
  FROM pg_stat_user_tables
 WHERE n_dead_tup > 10000
 ORDER BY n_dead_tup DESC;

-- 정확하지만 전체 스캔 비용이 큼
SELECT * FROM pgstattuple('orders');
SELECT * FROM pgstatindex('orders_pkey');
```

실전 임계는 대략 이렇다. dead_pct 20% 미만은 정상, 30~50% 는 autovacuum 설정 재검토, 그 이상이면 청소를 막는 요인이 따로 있다고 보고 위 쿼리를 먼저 돌린다.

## 4. autovacuum 튜닝 — 전역 기본값은 대부분 부족하다

autovacuum 이 테이블을 고르는 조건은 단순하다.

```
dead_tuples > autovacuum_vacuum_threshold
            + autovacuum_vacuum_scale_factor × reltuples
```

기본값은 threshold 50, scale_factor 0.2 다. 1억 행 테이블이라면 **2천만 행이 죽어야** 비로소 청소가 시작된다. 대형 테이블에서 이 기본값은 사실상 "청소하지 않음"에 가깝다.

```sql
-- 큰 테이블은 scale_factor 를 낮추고 threshold 로 제어
ALTER TABLE orders SET (
  autovacuum_vacuum_scale_factor = 0.01,
  autovacuum_vacuum_threshold    = 10000,
  autovacuum_analyze_scale_factor= 0.005,
  autovacuum_vacuum_cost_delay   = 2,     -- ms, 기본 2 (v12+)
  autovacuum_vacuum_cost_limit   = 2000,  -- 기본 200 → 처리량 10배
  fillfactor                     = 85
);
```

`cost_limit`/`cost_delay` 는 VACUUM 의 I/O 속도 제어다. 기본 설정은 2010년대 회전 디스크를 전제하므로, NVMe 환경에서는 cost_limit 을 2000~5000 까지 올려도 무해한 경우가 많다. 올리지 않으면 autovacuum 이 "항상 돌고 있는데 항상 뒤처진" 상태가 된다.

워커 수도 확인한다. `autovacuum_max_workers` 기본 3 은 테이블이 수백 개인 스키마에서 부족하다. 다만 **cost_limit 은 워커 전체가 나눠 쓴다**는 점을 기억해야 한다. 워커를 6으로 올리면 워커당 처리량은 절반이 되므로 cost_limit 도 함께 올려야 의미가 있다.

## 5. HOT 업데이트와 fillfactor

인덱스 엔트리는 힙 튜플의 물리 위치(ctid)를 가리킨다. 따라서 UPDATE 로 새 튜플이 다른 페이지에 생기면 **그 테이블의 모든 인덱스를 갱신**해야 한다. 이것이 PostgreSQL 에서 인덱스가 많을수록 UPDATE 가 급격히 느려지는 이유다.

**HOT(Heap-Only Tuple)** 은 이 비용을 없애는 최적화다. 두 조건이 모두 맞으면 인덱스를 건드리지 않는다.

1. 갱신된 컬럼이 **어떤 인덱스에도 포함되지 않음**
2. 새 버전이 **같은 페이지 안에** 들어감

이때 새 튜플은 인덱스 엔트리 없이 구 튜플의 `t_ctid` 체인으로만 접근되고, 청소도 VACUUM 전체 스캔 없이 페이지 단위 pruning 으로 처리된다.

조건 2를 만들려면 페이지에 여유가 있어야 하고, 그것이 `fillfactor` 다. 기본 100 은 INSERT 전용 테이블에 맞고, UPDATE 가 잦으면 85~90 으로 낮춰 페이지당 여유 공간을 남긴다.

```sql
-- HOT 비율 확인
SELECT relname,
       n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd, 0), 1) AS hot_pct
  FROM pg_stat_user_tables
 WHERE n_tup_upd > 0
 ORDER BY n_tup_upd DESC;
```

hot_pct 가 낮으면 두 가지를 점검한다. 자주 갱신되는 컬럼(상태 플래그, `updated_at`, 카운터)에 인덱스가 걸려 있지 않은지, 그리고 fillfactor 가 100 인지. `updated_at` 에 걸린 인덱스 하나 때문에 HOT 이 전부 무산되는 사례가 흔하다. 정렬용 인덱스가 정말 필요한지 재검토할 가치가 있다.

v16 이후 BRIN·요약 인덱스 개선으로 대안이 늘었지만, 원칙은 변하지 않는다. **자주 바뀌는 컬럼에는 인덱스를 걸지 않는다.**

## 6. XID Wraparound — 32비트의 한계

트랜잭션 ID 는 32비트 순환 값이다. 약 21억(2^31) 범위의 "과거"와 "미래"를 모듈러 비교로 판정하므로, 40억 개가 지나면 옛 트랜잭션이 미래로 보이기 시작한다. 그러면 살아 있던 행이 갑자기 사라진다.

방지 장치가 **freeze** 다. 충분히 오래된 튜플의 xmin 을 특별 표시(`HEAP_XMIN_FROZEN`)로 바꿔 "항상 보임"으로 고정한다. 한 번 freeze 된 튜플은 XID 비교 대상에서 빠진다.

```sql
-- 데이터베이스별 XID 소진율
SELECT datname,
       age(datfrozenxid) AS xid_age,
       round(100.0 * age(datfrozenxid) / 2000000000, 1) AS pct_to_wraparound
  FROM pg_database ORDER BY xid_age DESC;

-- 테이블별
SELECT relname, age(relfrozenxid) AS xid_age, pg_size_pretty(pg_relation_size(oid))
  FROM pg_class WHERE relkind IN ('r','m','t')
 ORDER BY age(relfrozenxid) DESC LIMIT 20;
```

단계별 임계값과 대응은 다음과 같다.

| age(datfrozenxid) | 상태 | 대응 |
| --- | --- | --- |
| < 200M | 정상 | 모니터링만 |
| ≥ `autovacuum_freeze_max_age`(기본 200M) | 강제 autovacuum 시작 | 정상 동작, 부하 확인 |
| ≥ 10.5억 | 경고 로그 | 원인 제거 + 수동 VACUUM FREEZE |
| ≥ 20억 | **읽기 전용 전환** | 단일 사용자 모드 복구 |

주의할 점은 wraparound 방지 autovacuum 이 **`vacuum_cost_delay` 를 무시하지 않는다**는 것이다(버전에 따라 다름). 즉 임계에 도달해도 청소 속도는 여전히 설정에 묶여 있어, 대형 테이블이 제때 끝나지 않을 수 있다. 경고가 뜼 시점에 cost_limit 을 임시로 크게 올리는 것이 실전 대응이다.

또한 **긴 트랜잭션과 유휴 슬롯이 freeze 도 막는다**. wraparound 경고의 실제 원인은 거의 항상 3절의 수평선 문제다. `idle_in_transaction_session_timeout` 과 `max_slot_wal_keep_size` 를 설정해 두면 사고 자체가 크게 줄어든다.

```sql
ALTER SYSTEM SET idle_in_transaction_session_timeout = '10min';
ALTER SYSTEM SET max_slot_wal_keep_size = '100GB';
ALTER SYSTEM SET log_autovacuum_min_duration = '1s';
SELECT pg_reload_conf();
```

v14 이후 `vacuum_failsafe_age`(기본 16억)에 도달하면 VACUUM 이 비용 지연과 인덱스 청소를 건너뛰고 freeze 에만 집중하는 안전 모드로 전환된다. 이 로그가 보이면 이미 상당히 늦은 상태이므로 즉시 원인 조사를 시작해야 한다.

## 7. 블로트 회수 — VACUUM FULL 없이

일반 VACUUM 은 데드 튜플 공간을 **재사용 가능하게** 만들 뿐 파일을 줄이지 않는다. 파일 축소가 필요하면 선택지는 셋이다.

| 방법 | 락 | 추가 공간 | 비고 |
| --- | --- | --- | --- |
| `VACUUM FULL` | ACCESS EXCLUSIVE | 테이블 크기만큼 | 운영 중 사실상 불가 |
| `CLUSTER` | ACCESS EXCLUSIVE | 동일 | 물리 정렬 부수 효과 |
| `pg_repack` | 짧은 EXCLUSIVE 2회 | 동일 | 온라인, 트리거 기반 |
| `REINDEX CONCURRENTLY` | 약한 락 | 인덱스 크기 | 인덱스 블로트 전용 |

인덱스 블로트만의 문제라면 `REINDEX INDEX CONCURRENTLY` 가 가장 안전하다. B-tree 는 삭제가 반복되면 페이지가 반쯤 빈 채 남는데, v12 이후 중복 제거(deduplication)와 bottom-up 삭제가 이를 크게 완화했으므로 최신 버전으로 올리는 것 자체가 유효한 대응이다.

테이블 블로트가 구조적으로 반복된다면 **파티셔닝**을 검토한다. 시계열 데이터에서 오래된 파티션을 DROP 하는 것은 VACUUM 이 필요 없는 O(1) 삭제이고, 대량 DELETE 로 생기는 블로트를 원천 제거한다.

```sql
CREATE TABLE events (id bigserial, occurred_at timestamptz NOT NULL, payload jsonb)
  PARTITION BY RANGE (occurred_at);

CREATE TABLE events_2026_09 PARTITION OF events
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

DROP TABLE events_2026_03;   -- DELETE 대신
```

## 8. 운영 체크리스트

점검 주기와 항목을 정리한다.

| 주기 | 항목 | 쿼리/설정 |
| --- | --- | --- |
| 상시 알람 | `age(datfrozenxid) > 5억` | `pg_database` |
| 상시 알람 | 5분 초과 idle in transaction | `pg_stat_activity` |
| 상시 알람 | 비활성 복제 슬롯 | `pg_replication_slots.active` |
| 일간 | dead_pct 상위 테이블 | `pg_stat_user_tables` |
| 일간 | autovacuum 미수행 24h+ | `last_autovacuum` |
| 주간 | HOT 비율 하락 테이블 | `n_tup_hot_upd` 비율 |
| 월간 | 인덱스 블로트 | `pgstatindex` |
| 변경 시 | 대형 테이블 개별 설정 | `ALTER TABLE ... SET` |

마지막으로 강조할 것은 **모든 VACUUM 문제는 "무엇이 수평선을 붙잡고 있는가"로 환원된다**는 점이다. 설정을 아무리 공격적으로 잡아도 1시간짜리 리포팅 트랜잭션이 하나 돌고 있으면 그 시간 동안 생긴 데드 튜플은 전부 회수 불가다. 튜닝보다 먼저 볼 것은 긴 트랜잭션, 유휴 슬롯, `hot_standby_feedback` 세 가지다.

## 참고

- PostgreSQL Documentation — Chapter 13. Concurrency Control (MVCC)
- PostgreSQL Documentation — Routine Vacuuming / Preventing Transaction ID Wraparound
- PostgreSQL Source — `src/backend/access/heap/heapam_visibility.c`
- PostgreSQL Documentation — Heap-Only Tuples (`src/backend/access/heap/README.HOT`)
- pgstattuple / pageinspect 확장 모듈 문서
- reorg/pg_repack — Online table reorganization
