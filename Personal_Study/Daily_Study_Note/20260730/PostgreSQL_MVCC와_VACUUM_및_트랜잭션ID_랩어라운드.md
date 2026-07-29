Notion 원본: https://app.notion.com/p/3ac5a06fd6d381a8872ef8d3986f1cdf

# PostgreSQL MVCC와 VACUUM 및 트랜잭션 ID 랩어라운드

> 2026-07-30 신규 주제 · 확장 대상: Oracle / RDBMS(다중버전 동시성의 내부 구현)

## 학습 목표

- PostgreSQL 이 UNDO 세그먼트 없이 튜플 다중버전으로 MVCC 를 구현하는 방식을 정리한다.
- `xmin`·`xmax`·힌트 비트가 가시성 판단에 쓰이는 규칙을 파악한다.
- dead tuple 이 왜 쌓이며 VACUUM·autovacuum 이 이를 어떻게 회수하는지 구분한다.
- 32비트 트랜잭션 ID 랩어라운드의 위험과 freeze 방어 메커니즘을 직접 쿼리로 관찰해 검증한다.

## 1. Oracle 과 다른 길 — 버전을 테이블 안에 둔다

Oracle 은 변경 전 이미지를 UNDO 테이블스페이스에 따로 보관하고, 읽기 일관성이 필요하면 UNDO 로 과거 상태를 재구성한다. PostgreSQL 은 정반대다. 행을 UPDATE 하면 기존 행을 덮어쓰지 않고 **새 버전 튜플을 테이블(heap)에 추가** 하고, 옛 튜플은 그대로 둔 채 "죽은 것으로 표시"한다. DELETE 도 물리 삭제가 아니라 죽음 표시다.

이 설계의 장점은 롤백이 즉시라는 점이다. 커밋된 것만 보이도록 가시성 규칙이 걸러내므로, 롤백은 "그 트랜잭션이 만든 튜플을 아무도 못 보게" 두면 끝이다. UNDO 를 되감을 필요가 없다. 대가는 죽은 튜플(dead tuple)이 물리적으로 쌓인다는 것 — 이걸 청소하는 게 VACUUM 이고, PostgreSQL 운영의 핵심 난제다.

## 2. xmin, xmax, 그리고 가시성

모든 튜플에는 시스템 컬럼 `xmin`(이 튜플을 만든 트랜잭션 ID)과 `xmax`(이 튜플을 죽인 트랜잭션 ID, 살아있으면 0)가 붙는다. 트랜잭션은 자신의 스냅샷 기준으로 "보이는" 튜플만 읽는다.

```sql
-- 시스템 컬럼을 직접 조회해 버전을 관찰
CREATE TABLE account (id int primary key, balance int);
INSERT INTO account VALUES (1, 1000);
SELECT ctid, xmin, xmax, id, balance FROM account;
--  ctid  | xmin | xmax | id | balance
-- (0,1)  |  742 |    0 |  1 |    1000

UPDATE account SET balance = 900 WHERE id = 1;
SELECT ctid, xmin, xmax, id, balance FROM account;
--  ctid  | xmin | xmax | id | balance
-- (0,2)  |  743 |    0 |  1 |     900   ← 새 버전 (0,2)
-- 옛 튜플 (0,1) 은 xmax=743 로 죽음 표시되어 스냅샷에서 걸러짐
```

가시성 판단은 대략 이렇다. 튜플의 `xmin` 이 커밋되었고 내 스냅샷보다 과거이며, `xmax` 가 없거나(살아있음) 커밋되지 않았거나 내 스냅샷보다 미래이면 → 보인다. 이 판단을 매번 CLOG(커밋 로그)에서 조회하면 비싸므로, 한 번 확정된 커밋/롤백 상태는 튜플의 **힌트 비트(hint bit)** 에 캐싱한다. 그래서 대량 INSERT 직후 첫 SELECT 가 유독 느린 현상(힌트 비트를 쓰며 페이지를 dirty 로 만듦)이 나타난다.

## 3. 격리 수준과 스냅샷

PostgreSQL 의 기본 격리 수준은 READ COMMITTED 다. 이 수준에서는 **문장마다** 새 스냅샷을 뜬다. 그래서 한 트랜잭션 안에서도 두 SELECT 가 다른 결과를 볼 수 있다(non-repeatable read). REPEATABLE READ 는 트랜잭션 시작 시점의 스냅샷을 끝까지 유지한다. PostgreSQL 의 REPEATABLE READ 는 스냅샷 격리라서 팬텀 리드까지 사실상 막는다.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM account WHERE id = 1;  -- 900
-- 이 사이 다른 세션이 balance=800 커밋해도
SELECT balance FROM account WHERE id = 1;  -- 여전히 900 (스냅샷 고정)
COMMIT;
```

SERIALIZABLE 은 여기에 SSI(Serializable Snapshot Isolation)를 더해 읽기-쓰기 의존성 그래프에 위험한 사이클이 생기면 한 트랜잭션을 `could not serialize access` 로 중단시킨다. 락 대신 충돌 감지로 직렬성을 얻으므로 읽기 성능이 좋지만, 재시도 로직이 애플리케이션에 필요하다.

## 4. Dead tuple 과 VACUUM

UPDATE/DELETE 가 반복되면 죽은 튜플이 heap 에 누적된다. 이를 방치하면 테이블이 실제 데이터보다 훨씬 커지는 **테이블 팽창(bloat)** 이 생기고, 인덱스도 죽은 튜플을 가리키는 항목으로 부푼다. VACUUM 은 죽은 튜플이 차지한 공간을 회수해 같은 테이블 내 재사용 가능한 free space 로 되돌린다.

```sql
-- 팽창 관찰
SELECT relname, n_live_tup, n_dead_tup,
       last_autovacuum, autovacuum_count
FROM pg_stat_user_tables WHERE relname = 'account';

VACUUM (VERBOSE, ANALYZE) account;
-- INFO: "account": removed 12000 dead item identifiers
```

주의할 점은 일반 `VACUUM` 은 파일 크기를 OS 로 반환하지 않는다는 것이다. 회수된 공간은 테이블 내부에서 재사용될 뿐 디스크는 줄지 않는다. 디스크를 실제로 돌려받으려면 `VACUUM FULL` 이 필요한데, 이건 테이블 전체를 다시 쓰며 **ACCESS EXCLUSIVE 락** 을 잡아 그동안 읽기·쓰기가 모두 막힌다. 운영 중에는 `pg_repack` 처럼 락 최소화 재작성 도구를 쓴다.

## 5. autovacuum 튜닝

수동 VACUUM 에 의존하면 반드시 놓친다. autovacuum 데몬이 배경에서 임계치를 넘긴 테이블을 자동 청소한다. 트리거 공식은 `죽은 튜플 수 > threshold + scale_factor × 살아있는 튜플 수` 다. 기본 `scale_factor` 0.2 는 대형 테이블에서 너무 관대하다 — 1억 행 테이블은 2천만 개 죽은 튜플이 쌓여야 발동하니, 그 전에 이미 팽창이 심각하다.

```sql
-- 대형·고빈도 갱신 테이블은 테이블별로 공격적으로 조정
ALTER TABLE orders SET (
  autovacuum_vacuum_scale_factor = 0.02,   -- 2% 만 죽어도 발동
  autovacuum_vacuum_threshold = 1000,
  autovacuum_vacuum_cost_limit = 2000      -- 청소 속도 상향 (기본 200)
);
```

`cost_limit` 은 autovacuum 이 I/O 를 얼마나 공격적으로 쓸지 결정한다. 기본값은 운영 트래픽을 방해하지 않으려 보수적이라, 갱신량을 못 따라잡으면 죽은 튜플이 계속 밀린다. 모니터링에서 `n_dead_tup` 이 우상향하면 threshold 나 cost_limit 을 조정해야 한다.

## 6. 트랜잭션 ID 랩어라운드 — 가장 무서운 실패

트랜잭션 ID(XID)는 32비트라 약 42억 개에서 소진된 뒤 순환한다. XID 는 원형으로 비교되어 "각 XID 는 과거 약 21억 개를 자신보다 과거로, 미래 약 21억 개를 미래로" 본다. 만약 어떤 튜플의 `xmin` 이 현재로부터 21억을 넘게 과거가 되면, 순환 비교상 갑자기 **미래로 보여** 사라진다. 데이터가 조용히 안 보이게 되는 재앙이다.

이를 막는 장치가 **freeze** 다. 충분히 오래되고 모두에게 보이는 튜플의 `xmin` 을 특수 값(FrozenTransactionId)으로 바꿔 "영원히 과거"로 고정한다. freeze 된 튜플은 XID 비교에서 제외되어 랩어라운드의 영향을 받지 않는다. autovacuum 은 `autovacuum_freeze_max_age`(기본 2억)에 도달하면 freeze 를 강제 수행한다.

```sql
-- 각 테이블이 랩어라운드까지 남긴 여유 관찰
SELECT relname,
       age(relfrozenxid) AS xid_age,
       2^31 - age(relfrozenxid) AS remaining
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE relkind = 'r' AND n.nspname = 'public'
ORDER BY xid_age DESC;
```

`age` 가 20억(약 2^31)에 근접하면 PostgreSQL 은 경고를 로그에 쏟고, 임계에 도달하면 **데이터 손상 방지를 위해 새 쓰기를 거부** 하며 단일 사용자 모드에서 VACUUM 을 요구한다. 실제 대형 서비스가 랩어라운드로 수 시간 다운된 사례가 여럿 있다. 원인은 대개 오래 열린 트랜잭션, 방치된 replication slot, autovacuum 이 못 따라가는 대량 쓰기다.

## 7. 오래 열린 트랜잭션이 모든 걸 막는다

VACUUM 은 "현재 살아있는 가장 오래된 스냅샷보다 과거의 죽은 튜플"만 회수할 수 있다. 어떤 세션이 트랜잭션을 몇 시간째 열어두면(예: `BEGIN` 후 커밋을 잊은 배치, idle-in-transaction 커넥션), 그 시점 이후의 죽은 튜플은 아무리 청소해도 회수되지 않는다. 팽창과 XID age 가 동시에 치솟는다.

```sql
-- 오래 열린 트랜잭션과 idle-in-transaction 탐지
SELECT pid, state, now() - xact_start AS duration, query
FROM pg_stat_activity
WHERE state IN ('active','idle in transaction')
  AND xact_start IS NOT NULL
ORDER BY xact_start;

-- 방어: 세션이 트랜잭션을 붙든 채 방치되지 않게
ALTER SYSTEM SET idle_in_transaction_session_timeout = '5min';
```

정리하면 PostgreSQL 의 MVCC 는 "버전을 테이블에 쌓는" 단순함으로 즉시 롤백과 높은 읽기 동시성을 얻지만, dead tuple·bloat·XID 랩어라운드라는 청구서를 남긴다. autovacuum 이 부하를 못 따라가지 않도록 테이블별 튜닝, 오래 열린 트랜잭션 차단, XID age 모니터링을 상시 운영에 넣어야 한다.

## 8. HOT 업데이트 — 인덱스 팽창을 줄이는 최적화

UPDATE 가 새 튜플 버전을 만들면 원칙적으로 그 행을 가리키는 **모든 인덱스**에 새 항목이 추가돼야 한다. 인덱스가 많은 테이블에서 이는 쓰기 증폭을 크게 만든다. PostgreSQL 은 이를 완화하는 HOT(Heap-Only Tuple) 최적화를 갖는다. 조건은 두 가지다 — 변경된 컬럼이 **어떤 인덱스에도 포함되지 않고**, 새 버전이 **같은 페이지 안**에 들어갈 여유가 있을 때다. 이 경우 인덱스는 갱신하지 않고, 옛 튜플에서 새 튜플로 향하는 페이지 내부 포인터(HOT chain)만 만든다.

```sql
-- HOT 업데이트 비율 관찰
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / nullif(n_tup_upd,0), 1) AS hot_pct
FROM pg_stat_user_tables WHERE relname = 'account';
--  relname | n_tup_upd | n_tup_hot_upd | hot_pct
--  account |    50000  |        47000  |   94.0   ← 높을수록 좋음
```

HOT 비율을 높이려면 자주 갱신되는 컬럼을 인덱스에서 빼고, 페이지에 여유 공간을 남기는 `fillfactor`(예: 90)를 테이블에 설정한다. 여유가 있으면 새 버전이 같은 페이지에 들어가 HOT 조건을 만족할 확률이 오른다. HOT chain 은 페이지 내 미니 VACUUM(page pruning)으로 즉시 정리될 수 있어 인덱스 팽창과 VACUUM 부담을 동시에 줄인다.

## 9. Visibility Map 과 인덱스 온리 스캔

VACUUM 은 부수적으로 **Visibility Map(VM)** 을 갱신한다. VM 은 각 heap 페이지에 대해 "이 페이지의 모든 튜플이 모든 트랜잭션에게 보이는가(all-visible)"를 비트로 기록한다. 이 정보가 두 가지를 가능케 한다. 첫째, 다음 VACUUM 은 all-visible 페이지를 건너뛰어 스캔량을 줄인다. 둘째, **인덱스 온리 스캔(index-only scan)** — 인덱스에 필요한 컬럼이 다 있고 대상 페이지가 all-visible 이면, heap 을 방문하지 않고 인덱스만으로 결과를 낸다.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT code FROM country WHERE code LIKE 'K%';
-- Index Only Scan using country_pkey ...
--   Heap Fetches: 0     ← VM 이 all-visible 이라 heap 방문 0회 (이상적)
```

`Heap Fetches` 가 크면 VM 이 갱신되지 않아(최근 VACUUM 부재) 인덱스 온리 스캔의 이점을 못 살리는 것이다. 읽기 성능이 중요한 테이블에서 VACUUM 을 게을리하면, 팽창뿐 아니라 이 최적화까지 잃는다. 즉 VACUUM 은 "청소"만이 아니라 읽기 경로 성능의 일부라는 점이 Oracle 과 구별되는 PostgreSQL 운영의 감각이다.

## 참고

- PostgreSQL Documentation — Concurrency Control (MVCC), Routine Vacuuming
- PostgreSQL Documentation — Preventing Transaction ID Wraparound Failures
- *PostgreSQL 14 Internals* (Egor Rogov) — MVCC, VACUUM 챕터
- pg_repack Documentation — online table reorganization
