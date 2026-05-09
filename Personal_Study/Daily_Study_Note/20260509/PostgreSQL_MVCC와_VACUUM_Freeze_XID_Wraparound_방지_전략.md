Notion 원본: https://www.notion.so/35b5a06fd6d38180b20dc79fa272bbb6

# PostgreSQL MVCC와 VACUUM Freeze XID Wraparound 방지 전략

> 2026-05-09 신규 주제 · 확장 대상: Oracle / SQLD (DB)

## 학습 목표

- PostgreSQL 의 row-level MVCC 가 `xmin`/`xmax`/`cmin`/`cmax`/`ctid` 헤더로 어떻게 가시성을 결정하는지 분해한다
- 32-bit transaction id 의 wraparound 문제와 Frozen XID 메커니즘이 어떻게 그것을 막는지 단계별로 추적한다
- `VACUUM`, `VACUUM FREEZE`, autovacuum, `vacuumdb --freeze` 의 차이와 운영 임계값(`autovacuum_freeze_max_age`, `vacuum_freeze_table_age`) 설정 가이드를 정리한다
- 실제 장애 시나리오 ("database must be vacuumed within X transactions") 가 발생했을 때 복구 절차를 행위 단위로 익힌다

## 1. PostgreSQL MVCC 의 헤더 구조

각 튜플은 `HeapTupleHeaderData` 라는 23~27 바이트 헤더를 갖는다. 가시성 판단에 쓰이는 핵심 필드:

| 필드 | 의미 |
|---|---|
| `xmin` | 이 튜플을 *생성한* 트랜잭션 ID |
| `xmax` | 이 튜플을 *삭제/변경한* 트랜잭션 ID (없으면 0) |
| `cmin`/`cmax` | 같은 트랜잭션 내 명령 번호 (가시성 미세 결정) |
| `ctid` | 튜플의 물리 위치 (block, offset). UPDATE 시 새 행으로 가는 포인터 |
| `t_infomask`/`t_infomask2` | 플래그: HEAP_XMIN_FROZEN, HEAP_XMIN_COMMITTED, HEAP_XMAX_INVALID 등 |

쿼리는 *현재 스냅샷 (xmin, xmax, xip_list)* 을 갖고 있다. 튜플 가시성 판단은 다음을 본다.

1. `xmin` 의 트랜잭션이 commit 되었고 스냅샷 시점에 *이미 끝나 있었다* 면 INSERT 가 보인다
2. `xmax` 가 0 이거나 *아직 commit 안 되었거나* *내 스냅샷 이후에 끝났다* 면 행은 살아 있다
3. 그 외에는 dead tuple

이 매커니즘이 *낙관적 동시성* 을 제공하는 대신, 삭제된 행이 즉시 사라지지 않고 dead tuple 로 남는다. dead tuple 정리가 `VACUUM` 의 첫 번째 책임이다.

## 2. Transaction ID 의 32-bit 한계

XID 는 32-bit 정수다. 약 42억(2^32)을 한 번 다 쓰면 *wraparound* 가 일어나 옛날 값과 새 값이 충돌한다. PostgreSQL 은 *modulo-2^32 비교* 를 쓰는데, 두 XID 의 거리는 항상 *2^31 이내* 여야 의미 있는 비교가 된다. 거리가 2^31 을 넘으면 *과거의 commit 이 미래로 보이는* 가시성 깨짐이 일어난다.

따라서 시스템은 *특정 임계 이상으로 오래된 행을 영구적으로 가시* 처리해야 한다. 이게 **Freeze** 다. `xmin` 을 특수값 `FrozenTransactionId` (또는 infomask 플래그 `HEAP_XMIN_FROZEN`)로 마킹하면, 어떤 미래 스냅샷에서도 *항상 보임* 이 보장된다.

## 3. Freeze 의 임계값

`pg_class.relfrozenxid` 는 *해당 릴레이션에서 가장 오래된 frozen 안 된 xmin*. 데이터베이스 차원의 `pg_database.datfrozenxid` 는 그 DB 안 모든 테이블 중 최솟값. 시스템은 다음 임계로 freeze 를 강제한다.

| 파라미터 | 기본값 | 의미 |
|---|---|---|
| `vacuum_freeze_min_age` | 50,000,000 | 이보다 오래된 xmin 은 VACUUM 시 freeze |
| `vacuum_freeze_table_age` | 150,000,000 | 이보다 오래된 relfrozenxid 의 테이블은 *aggressive* VACUUM |
| `autovacuum_freeze_max_age` | 200,000,000 | 자동으로 anti-wraparound VACUUM 트리거 |
| `vacuum_failsafe_age` | 1,600,000,000 | failsafe 모드 (cost-based 지연 무시하고 강제 freeze) |

DB 가 wraparound 직전까지 가면 PostgreSQL 은 *single-user 모드* 로 들어가야만 살아나는 상태에 진입한다. 운영 시 `pg_stat_activity` 와 `pg_class.relfrozenxid` 모니터링이 필수.

## 4. VACUUM vs VACUUM FREEZE vs Autovacuum

- `VACUUM` (lazy): dead tuple 의 슬롯을 free space map 에 등록, 가능하면 visibility map 갱신. 모든 페이지를 재방문하지 않고 visibility map 비트가 0인 페이지만 본다.
- `VACUUM (FREEZE)`: `vacuum_freeze_min_age` 를 0 처럼 다뤄 *오래된 xmin 모두* freeze. 모든 페이지를 다 봐야 함.
- `VACUUM FULL`: 테이블을 새 파일로 다시 쓴다. ACCESS EXCLUSIVE LOCK 필요. 운영 중 회피.
- Autovacuum 워커: `autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * reltuples` 보다 dead tuple 이 많으면 트리거. anti-wraparound 가 필요한 테이블이 발견되면 *유저 락 무시하고* 강제 시작.

```sql
-- 진단 쿼리: 임박한 wraparound 위험 테이블
SELECT relname,
       age(relfrozenxid) AS xid_age,
       pg_size_pretty(pg_total_relation_size(oid)) AS size
FROM pg_class
WHERE relkind IN ('r','m','t')
ORDER BY xid_age DESC
LIMIT 20;
```

`age` 가 200,000,000 을 넘기 시작하면 anti-wraparound autovacuum 이 도는 중이라는 뜻이고, 1,600,000,000 에 가까우면 *failsafe* 가 발동한다.

## 5. Visibility Map 과 Index-Only Scan

Visibility map(VM)은 페이지마다 2비트를 둔다: *all-visible*, *all-frozen*. all-frozen 비트가 켜진 페이지는 *그 페이지의 어떤 튜플도 freeze 가 끝나 있다* 는 의미라 다음 VACUUM 이 그 페이지를 건너뛸 수 있다.

Index-Only Scan 도 VM 의 all-visible 비트를 본다. 비트가 켜진 페이지에 대해서는 *heap fetch 없이 인덱스만 보고* 결과를 낸다. 즉 VACUUM 이 잘 돌아 VM 비트가 잘 채워지면 read 성능까지 올라간다.

## 6. 실제 장애 — "database must be vacuumed"

증상: 신규 트랜잭션이 거부되며 다음 메시지가 뜸.

```
ERROR:  database is not accepting commands to avoid wraparound data loss in database "..."
HINT:   Stop the postmaster and vacuum that database in single-user mode.
```

복구 절차:

1. 클라이언트 트래픽 차단 (LB out)
2. `pg_stat_activity` 에서 *오래 멈춰 있는 트랜잭션* 을 식별. 보통 죽은 idle-in-transaction 이 원인. `pg_terminate_backend(pid)` 로 정리
3. `VACUUM (FREEZE, VERBOSE) <table>` 또는 single-user 모드 진입 (`postgres --single -D ...`)
4. 가장 오래된 테이블부터 `VACUUM FREEZE` 수행
5. `pg_class` / `pg_database` 의 frozenxid 모니터링하면서 정상화
6. 재발 방지: long-running transaction 모니터링, `idle_in_transaction_session_timeout` 설정, autovacuum cost limit 완화

## 7. 운영 튜닝 가이드

```sql
-- 큰 테이블에 대한 per-table 설정 예
ALTER TABLE big_event SET (
  autovacuum_vacuum_scale_factor = 0.05,
  autovacuum_vacuum_cost_limit   = 2000,
  autovacuum_freeze_min_age      = 10000000,
  autovacuum_freeze_table_age    = 100000000
);
```

핵심 휴리스틱:

- 쓰기 빈도가 매우 높은 테이블은 `scale_factor` 를 낮춰 자주 돌게 한다
- IO 가 충분히 빠르면 `vacuum_cost_limit` 을 올려 한 번에 많이 처리하게 한다
- 큰 분석용 append-only 테이블은 `freeze_min_age` 를 낮춰 *처음 VACUUM 에서 곧바로 freeze* 시켜 두 번 일하지 않게 한다
- `pg_stat_progress_vacuum` 으로 진행상황 모니터링

## 8. HOT Update 와 dead tuple 발생 패턴

Heap Only Tuple update 는 *인덱스가 가리키는 컬럼이 변경되지 않았고 페이지 내 free space 가 충분* 할 때 발생한다. 같은 페이지에 새 버전이 들어가고 인덱스는 변경되지 않는다. dead tuple 은 늘지만 *인덱스 bloat* 은 발생하지 않는다.

```text
[heap page]
  [tuple v1: xmin=T1, xmax=T2] -- HOT chain head
    └── ctid →
  [tuple v2: xmin=T2, xmax=T3]
    └── ctid →
  [tuple v3: xmin=T3, xmax=0 ]
[index]
  key=42 → (page, line=v1)
```

VACUUM 은 HOT chain 을 *prune* 해 v1, v2 를 회수하면서 인덱스는 그대로 둔다. *fillfactor* 를 낮춰 페이지에 여유를 만들면 HOT update 비율이 올라가 vacuum 비용이 감소한다(예: `WITH (fillfactor=80)`).

## 9. 실측 시나리오 — 1억 행 테이블

| 항목 | 측정값 (예시) |
|---|---|
| 테이블 크기 | 80 GB |
| dead tuple 비율 (VACUUM 직전) | 12% |
| autovacuum 1회 소요 | 35분 |
| anti-wraparound aggressive freeze 1회 소요 | 4시간 |
| `vacuum_cost_limit` 1000 → 4000 변경 후 | 35분 → 12분 |

운영 머신 IOPS 와 maintenance_work_mem(VACUUM 의 dead tuple 배열 크기 결정) 에 따라 결과는 달라진다. PG 13 부터 VACUUM 이 *parallel index vacuum* 을 지원해 `max_parallel_maintenance_workers` 를 활용할 수 있다.

## 참고

- PostgreSQL Documentation — Routine Vacuuming / Preventing Transaction ID Wraparound
- PostgreSQL Documentation — Visibility Map / HOT Updates
- 2ndQuadrant Blog — "Routine Vacuuming Best Practices"
- Citus Engineering — "Postgres Vacuum: A Survival Guide"
- Bruce Momjian, "Inside PostgreSQL Shared Memory"
