Notion 원본: https://www.notion.so/3925a06fd6d3817883a2da73656ad55c

# PostgreSQL MVCC와 VACUUM 및 HOT 업데이트 Bloat 관리

> 2026-07-03 신규 주제 · 확장 대상: Oracle(Redo/Undo·MVCC Consistent Read 학습됨)

## 학습 목표

- PostgreSQL의 튜플 버전 저장 방식(추가형 MVCC)이 Oracle의 undo 방식과 어떻게 다른지 구분한다
- `xmin`/`xmax`와 가시성 판정, 그리고 dead tuple이 쌓이는 과정을 설명한다
- VACUUM·autovacuum·freeze가 하는 일과 트랜잭션 ID wraparound 위험을 정리한다
- HOT 업데이트와 fillfactor로 bloat과 인덱스 증폭을 줄이는 전략을 코드로 적용한다

## 1. 추가형 MVCC: Oracle과의 근본 차이

Oracle은 데이터 블록을 제자리에서 수정하고, 이전 이미지를 undo 세그먼트에 따로 보관해 일관된 읽기(consistent read)를 재구성한다. PostgreSQL은 정반대다. 행(튜플)을 수정하면 기존 튜플을 그 자리에서 고치지 않고, **새 버전 튜플을 힙에 추가**하고 옛 버전은 "죽은 튜플(dead tuple)"로 남겨 둔다. UPDATE는 사실상 "옛 버전 만료 표시 + 새 버전 삽입"이다.

이 설계의 장점은 롤백이 값싸다는 것이다. 실패한 트랜잭션이 만든 새 튜플은 그냥 죽은 튜플이 되어 버려지고, undo를 되감을 필요가 없다. 단점은 죽은 튜플이 물리적으로 계속 쌓여 공간을 먹는다는 것이고, 이를 청소하는 것이 VACUUM의 존재 이유다.

## 2. xmin, xmax, 가시성 판정

각 힙 튜플은 시스템 컴럼 `xmin`(그 튜플을 만든 트랜잭션 ID)과 `xmax`(그 튜플을 만료시킨 트랜잭션 ID)를 헤더에 갖는다. 스냅샷 격리에서 "이 튜플이 내 트랜잭션에 보이는가"는 대략 이렇게 판정된다: `xmin`이 커밋되었고 내 스냅샷보다 과거이며, `xmax`가 비었거나(아직 만료 안 됨) 내 스냅샷에 보이지 않는 트랜잭션이 만료시킨 경우 보인다.

```sql
-- 숨은 시스템 컴럼을 직접 조회해 버전 흔적을 관찰
SELECT ctid, xmin, xmax, * FROM accounts WHERE id = 1;
-- UPDATE 한 번 후 다시 조회하면 ctid(물리 위치)와 xmin 이 바뀜어 있다
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
SELECT ctid, xmin, xmax, * FROM accounts WHERE id = 1;
```

`ctid`는 `(블록번호, 블록내 오프셋)` 형태의 물리 주소다. UPDATE로 새 버전이 다른 위치에 생기면 `ctid`가 바뀜다. 이 물리 위치 변화가 인덱스와 얽혀 뒤에서 다룰 HOT 문제를 만든다.

## 3. 죽은 튜플과 bloat

죽은 튜플은 어떤 활성 트랜잭션에도 더는 보이지 않게 되면 회수 대상이 된다. 하지만 자동으로 즉시 회수되지 않고, VACUUM이 돌 때 공간이 재사용 가능 상태로 표시된다. 회수가 늘으면 테이블과 인덱스가 실제 살아 있는 데이터보다 훨씬 커지는 bloat이 발생한다. Bloat은 디스크 낭비를 넘어, 스캔이 죽은 공간까지 읽게 만들어 캐시 효율과 쿼리 성능을 떨어뜨린다.

```sql
-- 테이블별 죽은 튜플 비율과 마지막 autovacuum 시각
SELECT relname,
       n_live_tup,
       n_dead_tup,
       round(n_dead_tup::numeric / nullif(n_live_tup + n_dead_tup, 0), 3) AS dead_ratio,
       last_autovacuum
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 10;
```

`dead_ratio`가 지속적으로 높은(예: 0.2 이상) 테이블은 autovacuum이 워크로드를 못 따라가고 있다는 신호다.

## 4. VACUUM, autovacuum, FREEZE

`VACUUM`은 죽은 튜플이 점유한 공간을 재사용 가능 목록에 등록한다. 다만 일반 VACUUM은 파일 크기를 OS로 반환하지 않는다(테이블은 그대로 크고, 내부 빈 공간만 재사용). 파일 자체를 줄이려면 `VACUUM FULL`이 필요하지만, 이는 테이블 전체를 다시 쓰며 `ACCESS EXCLUSIVE` 락을 잡아 서비스 중 실행은 위험하다. 온라인으로 물리 축소가 필요하면 `pg_repack` 같은 도구를 쓴다.

autovacuum은 이 VACUUM을 백그라운드에서 자동 실행하는 데몬이다. 트리거는 대략 `n_dead_tup > autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * reltuples`다. 기본 scale factor 0.2는 "죽은 튜플이 살아 있는 행의 20%를 넘으면 vacuum"을 의미한다. 대형·고빈도 갱신 테이블에서는 이 20%가 너무 커서 bloat이 커지므로, 테이블 단위로 낮춘다.

```sql
-- 갱신이 잦은 큰 테이블은 임계치를 공격적으로 낮춘다
ALTER TABLE orders SET (
  autovacuum_vacuum_scale_factor = 0.02,   -- 2% 마다 vacuum
  autovacuum_vacuum_threshold   = 1000,
  autovacuum_vacuum_cost_delay  = 2        -- 부하 조절
);
```

FREEZE는 별개의 중대한 이유로 필요하다. 트랜잭션 ID(XID)는 32비트 순환값이라, 아주 오래된 튜플의 `xmin`이 "미래"로 보이는 wraparound가 발생하면 데이터가 갑자기 안 보이는 재앙이 된다. 이를 막기 위해 VACUUM은 충분히 오래된 튜플의 XID를 "영구 과거"를 뜻하는 frozen 상태로 표시한다. `autovacuum_freeze_max_age`에 도달하면 강제 anti-wraparound autovacuum이 돌는다. 이 vacuum은 막을 수 없으므로, `pg_stat_user_tables`와 `age(relfrozenxid)`를 모니터링해 미리 대비해야 한다.

| 명령/기능 | 락 수준 | 공간 OS 반환 | 주 용도 |
|---|---|---|---|
| `VACUUM` | SHARE UPDATE EXCLUSIVE(온라인) | 아니오 | 죽은 튜플 회수·freeze |
| `VACUUM FULL` | ACCESS EXCLUSIVE(차단) | 예(전체 재작성) | 강한 bloat 물리 축소 |
| autovacuum | 온라인 | 아니오 | 자동 유지보수 |
| `pg_repack` | 대부분 온라인 | 예 | 무중단 물리 축소 |

## 5. HOT 업데이트와 fillfactor

PostgreSQL의 아픈 지점은 인덱스다. UPDATE로 새 튜플 버전이 생기면 원칙적으로 모든 인덱스에 새 물리 위치(ctid)를 가리키는 엔트리를 추가해야 한다. 갱신 컴럼이 인덱스와 무관해도 그렇다면 인덱스 쓰기 증폭이 심하다.

HOT(Heap-Only Tuple) 업데이트가 이를 완화한다. 조건은 두 가지다. 첫째, 갱신되는 컴럼이 어떤 인덱스에도 포함되지 않을 것. 둘째, 새 버전 튜플이 **같은 힙 페이지 안**에 들어갈 공간이 있을 것. 이 조건이 맞으면 새 버전을 같은 페이지에 넣고 옛 튜플에서 새 튜플로 페이지 내부 포인터 체인만 연결한다. 인덱스는 여전히 옛 위치를 가리키지만 체인을 따라가 최신 버전을 찾는다. 결과적으로 인덱스에 새 엔트리를 만들지 않는다.

```sql
-- 갱신이 잦은 테이블은 페이지에 여유를 두어 HOT 성사율을 높인다
ALTER TABLE sessions SET (fillfactor = 80);
-- HOT 성사율 관찰
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(n_tup_hot_upd::numeric / nullif(n_tup_upd, 0), 3) AS hot_ratio
FROM pg_stat_user_tables
WHERE n_tup_upd > 0
ORDER BY n_tup_upd DESC;
```

`hot_ratio`가 높을수록 인덱스 증폭과 bloat이 적다. 낮다면 갱신 컴럼에 걸린 불필요한 인덱스를 제거하거나 fillfactor를 낮춰 개선한다.

## 6. 트레이드오프와 운영 지침

첫째, fillfactor를 낮추면 HOT 성사율은 오르지만 테이블이 커지고 순수 읽기 스캔의 페이지 수가 늘어 캐시 효율이 약간 떨어진다. 갱신 빈도가 높은 테이블에만 선택적으로 적용한다.

둘째, 인덱스를 무분별하게 많이 걸면 HOT 조건이 깨져 UPDATE마다 여러 인덱스를 갱신하게 된다. "이 인덱스가 정말 쿼리에 쓰이는가"를 `pg_stat_user_indexes`의 `idx_scan`으로 검증해 미사용 인덱스를 정리하면 쓰기 비용과 bloat이 함께 준다.

셋째, autovacuum을 끄는 것은 거의 항상 잘못된 선택이다. 부하가 걱정이면 끄지 말고 `cost_delay`/`cost_limit`로 속도를 조절한다. anti-wraparound vacuum까지 미루면 최악의 순간에 강제로 몰려 오히려 큰 정지를 부른다.

## 7. 가시성 맵과 인덱스 온리 스캔

VACUUM은 죽은 튜플 회수 외에 또 하나의 중요한 부산물을 갱신한다. 가시성 맵(visibility map)은 각 힙 페이지당 비트를 두어 "이 페이지의 모든 튜플이 모든 트랜잭션에 보이는가(all-visible)"를 표시한다. 이 비트가 인덱스 온리 스캔(index-only scan)의 성능을 좌우한다.

인덱스 온리 스캔은 필요한 컴럼이 모두 인덱스에 있으면 힙을 읽지 않고 인덱스만으로 결과를 만든다. 그런데 PostgreSQL 인덱스에는 가시성 정보가 없어서(인덱스 엔트리는 죽은 버전도 가리킬 수 있음), 원칙적으로 각 행의 가시성을 힙에서 확인해야 한다. 여기서 가시성 맵이 개입한다. 해당 페이지가 all-visible로 표시돼 있으면 힙 방문을 건너뛴다. 즉 VACUUM이 최근에 돌아 가시성 맵이 최신일수록 인덱스 온리 스캔이 진짜로 힙을 안 읽어 빨라진다.

```sql
-- 인덱스 온리 스캔 여부와 Heap Fetches(힙 방문 횟수) 확인
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM orders WHERE id BETWEEN 1 AND 1000;
-- 출력의 "Heap Fetches: 0" 이면 가시성 맵 덕에 힙을 전혀 안 읽음.
-- Heap Fetches 가 크면 vacuum 이 밀려 가시성 맵이 낡았다는 신호
```

`Heap Fetches`가 크다면 해당 테이블에 VACUUM을 돌려 가시성 맵을 갱신하면 인덱스 온리 스캔이 실제로 효과를 난다. vacuum은 읽기 성능 최적화의 일부다.

## 8. 긴 트랜잭션이 vacuum을 마비시키는 이유

운영에서 가장 흔한 bloat 폭증 원인은 오래 열려 있는 트랜잭션이다. VACUUM은 "어떤 활성 트랜잭션에도 보이지 않는" 죽은 튜플만 회수할 수 있다. 아주 오래된 스냅샷을 든 트랜잭션(몇 시간짜리 분석 쿼리, 커밋을 잊은 세션, 방치된 `idle in transaction`)이 하나라도 있으면, 그 스냅샷 시점 이후 죽은 모든 튜플이 "누군가에게 보일 수 있음"으로 판정되어 회수가 막힌다. 데이터베이스 전체의 vacuum 지평선(xmin horizon)이 그 오래된 트랜잭션에 고정되기 때문이다.

```sql
-- 가장 오래된 트랜잭션과 idle in transaction 세션을 찾아낸다
SELECT pid, state, now() - xact_start AS xact_age, query
FROM pg_stat_activity
WHERE state <> 'idle' AND xact_start IS NOT NULL
ORDER BY xact_start
LIMIT 5;
```

대응은 애플리케이션 레벨에서 트랜잭션을 짧게 유지하고, `idle_in_transaction_session_timeout`을 설정해 방치 세션을 강제 종료하는 것이다. 분석용 장기 쿼리는 별도 리드 레플리카로 분리해 주 서버의 vacuum 지평선을 막지 않게 한다.

이 메커니즘은 XID wraparound 위험과도 직결된다. 오래된 트랜잭션이 freeze를 막으면 `relfrozenxid`가 전진하지 못해 wraparound 임계에 가까워지고, 결국 강제 anti-wraparound autovacuum이 몰린다. 따라서 "긴 트랜잭션을 없애는 것"이 PostgreSQL 운영에서 bloat과 wraparound를 동시에 예방하는 가장 효과적인 규율이다.

## 참고

- PostgreSQL Documentation — Concurrency Control / MVCC (Ch. 13)
- PostgreSQL Documentation — Routine Vacuuming (Ch. 25)
- PostgreSQL Documentation — Heap-Only Tuples (HOT) README
- pg_repack 프로젝트 문서
