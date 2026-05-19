Notion 원본: https://www.notion.so/3655a06fd6d3815c920ac2ac330ce9bd

# PostgreSQL MVCC와 VACUUM — Bloat·XID Wraparound 운영

> 2026-05-19 신규 주제 · 확장 대상: DB

## 학습 목표

- PostgreSQL MVCC 가 `xmin`·`xmax`·`ctid`·`t_infomask` 로 가시성을 표현하는 방식을 모델 단에서 이해한다
- `VACUUM`·`VACUUM FREEZE`·`autovacuum` 의 책임 분담과 트리거 조건을 식별한다
- bloat 의 정의·관측·복구 방법을 `pg_stat_user_tables`·`pg_freespacemap`·`pgstattuple` 로 측정한다
- XID wraparound 의 위험과 32-bit XID → 64-bit XID(MultiXact 분리)·emergency vacuum 동작을 추적한다

## 1. MVCC 모델 — 행은 *unique row* 가 아니라 *불변 버전*

PostgreSQL 은 *append-only* 변형 방식으로 MVCC 를 구현한다. UPDATE 는 *제자리 갱신* 이 아니라 새 row version 을 같은 페이지(또는 새 페이지)에 append 하고, 기존 version 의 `xmax` 에 갱신 트랜잭션 ID 를 적는다. DELETE 는 `xmax` 만 채운다. 모든 row 는 `xmin`, `xmax`, `t_ctid`, `t_infomask` 헤더를 가진다.

쿼리는 *snapshot* 을 잡는다. snapshot 은 `(xmin, xmax, [xip*])` 로 표현되며, "내가 시작했을 때 xmin 이하의 트랜잭션은 commit/abort 가 결정됐고, xmax 이상은 아직 시작 안 됐다"를 의미한다. 행 가시성 판정은 자기 트랜잭션이 만든 행은 항상 보이고, `xmin` 의 트랜잭션이 *snapshot 전에 commit* 됐고 `xmax` 가 0 이거나 *snapshot 후에야 commit/active* 면 보인다.

이 규칙 덕에 *읽기는 잠금 없이* 동작하고, write skew·lost update 같은 이상은 SSI(Serializable Snapshot Isolation) 또는 명시적 `SELECT ... FOR UPDATE` 로 막는다. 부작용은 *데드 튜플(dead tuple)* 이다. 이 자원 회수가 VACUUM 의 첫 번째 책임이다.

## 2. VACUUM 의 책임 분해 — 회수·동결·인덱스 정리

VACUUM 은 네 단계를 수행한다. 첫째, 데드 튜플의 *공간을 free space map(FSM) 에 등록* 한다. 페이지 자체를 OS 로 반환하지 않고 이후 INSERT/UPDATE 가 새 버전을 쓸 때 *재사용* 한다. 둘째, *visibility map(VM)* 을 갱신한다. 페이지의 *모든* 튜플이 모든 트랜잭션에서 보일 때 해당 페이지에 `all-visible` 비트를 세운다. 이 비트가 켜져 있으면 *index-only scan* 이 가능해진다. 셋째, *FROZEN 표시* 를 한다. `xmin` 이 충분히 오래되어 wraparound 안전선 안쪽이면 그 비트를 끄고 `HEAP_XMIN_FROZEN` 비트를 켜다. 넷째, *인덱스 항목 제거*. dead heap tuple 을 가리키는 인덱스 entry 를 같이 지운다.

`VACUUM FULL` 은 전혀 다른 동작이다. 새 빈 relation 을 만든 뒤 살아있는 튜플만 복사하고 원본을 교체한다. 디스크가 실제로 줄고 인덱스도 재생성되지만 *AccessExclusiveLock* 을 잡기 때문에 운영 중 테이블에서는 금기. 대신 `pg_repack` 이나 `pg_squeeze` 로 락 시간을 짧게 만든다.

## 3. autovacuum 트리거와 튜닝 파라미터

autovacuum launcher 가 60초(`autovacuum_naptime`)마다 데이터베이스를 검사한다. 다음 임계를 넘은 테이블이 vacuum 대상이 된다: `threshold = autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * reltuples`. 기본값 50 + 0.2 × reltuples. 100만 행 테이블이면 약 20만 행이 dead 가 되어야 한다.

대형 OLTP 에서 이 기본값은 너무 느슨하다. *write 가 많은 테이블* 에 대해서는 테이블별로 설정한다.

```sql
ALTER TABLE orders SET (
  autovacuum_vacuum_scale_factor = 0.02,
  autovacuum_vacuum_threshold = 10000,
  autovacuum_analyze_scale_factor = 0.01
);
```

`autovacuum_vacuum_cost_limit`·`autovacuum_vacuum_cost_delay`(13+ 기본 2ms) 는 vacuum 의 *I/O 속도 제한* 이다. cost 200 만 누적되면 delay 만큼 잠시 쉰다. 13+ 부터는 *parallel vacuum* 이 도입되어 인덱스 단계가 멀티 워커로 분산된다.

## 4. Bloat — 측정과 의미

bloat 는 *living tuple 외에 점유된 페이지 비율* 로 정의된다. 측정은 세 방법이 있다.

`pgstattuple` (contrib 확장)은 정확하지만 *전체 페이지* 를 스캔하므로 대형 테이블에서는 비싸다. `pgstattuple_approx` 가 샘플링 버전이다. `pg_stat_user_tables` 는 추정치이지만 비용이 0 에 가까워 모니터링에 적합하다. dead_ratio 0.2 이상은 vacuum 부족 신호. bloat estimation 쿼리(check_postgres `bloat_check.sql`)는 페이지 헤더·라인 포인터 크기를 모델링해 *예상 vs 실제* row 크기 차이를 계산한다.

bloat 가 심한 테이블의 증상은 index-only scan 이 사라지고, 같은 쿼리의 plan 이 `Seq Scan` 으로 떨어지고, buffer hit ratio 가 낮아지고, vacuum 자체가 점점 오래 걸리는 것이다. 복구는 autovacuum 임계 강화 후 `pg_repack -t orders` 로 *온라인 재구성* 을 수행한다.

## 5. HOT UPDATE — 인덱스 갱신을 줄이는 핵심 최적화

UPDATE 가 *어떤 인덱스 키도 변경하지 않고* *같은 페이지에 새 버전이 들어갈 공간이 있을 때* HOT(Heap-Only Tuple) update 가 발동된다. HOT 의 효과는 인덱스에 *새 entry 를 추가하지 않는다* 는 것이다.

```sql
ALTER TABLE orders SET (fillfactor = 80);
```

fillfactor 를 낮추면 *같은 페이지에 들어갈 확률* 이 올라가 HOT update 비율이 증가한다. 빈번한 UPDATE 가 있는 OLTP 테이블은 fillfactor 70~85 사이가 일반적. hot_ratio 가 0.7 이하이면 *fillfactor 부족* 또는 *인덱스 키 컬럼이 자주 변경됨* 의 증거다.

## 6. XID Wraparound — 32-bit ID 가 다 차면 어떻게 되나

트랜잭션 ID 는 32-bit 정수다. 약 21억 개를 발급한 뒤 0 으로 wrap 한다. PostgreSQL 의 가시성 판정은 "내 xmin 이 상대 xmin 보다 크다" 같은 *modulo 2^32 비교* 를 한다. wrap 이 일어나면 *살아있는 행이 미래의 행처럼 보이는* 사고가 발생한다.

이를 막기 위해 PostgreSQL 은 *모든 행의 xmin 이 200만(`vacuum_freeze_min_age`) 이상 오래되면 FROZEN 으로 표시* 한다. `autovacuum_freeze_max_age`(기본 2억) 를 넘은 테이블은 *vacuum freeze 가 강제* 된다. `pg_class.relfrozenxid` 가 가장 오래된 xmin 이며, 데이터베이스 차원의 가장 오래된 값이 `pg_database.datfrozenxid` 다.

```sql
SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY age(datfrozenxid) DESC;
```

`age(datfrozenxid)` 가 *15억* 를 넘으면 위험 영역, *19억* 를 넘으면 *읽기 전용 모드*(`ERROR: database is not accepting commands to avoid wraparound data loss`)로 진입한다. MultiXact ID(`pg_multixact`) 도 동일한 32-bit 한계를 가지며 별도의 `autovacuum_multixact_freeze_max_age` 로 관리된다.

## 7. emergency vacuum — wraparound 직전의 동작 모델

`autovacuum_freeze_max_age` 를 넘은 테이블은 *anti-wraparound autovacuum* 으로 마킹된다. 이 vacuum 은 세 특이점을 가진다. 자동 시작이 즉시(`autovacuum=off` 라도 실행). 기간이 길다(대형 테이블이면 수 시간 ~ 수일, *page 단위 progressive freeze* 가 14+ 에서 도입). ACCESS EXCLUSIVE 가 아닌 *SHARE UPDATE EXCLUSIVE* 락만 잡으므로 SELECT/UPDATE 는 가능하다. 회피 전략은 운영 부하가 낮은 시간에 *수동* `VACUUM FREEZE` 를 주기적으로 실행해 freeze 작업을 분산하는 것이다.

## 8. 64-bit XID 의 의미 — 17+ 의 변화

PostgreSQL 17(2024) 의 *opportunistic freezing* 과 인-페이지 *64-bit XID 확장 슬롯* 은 anti-wraparound 의 빈도를 크게 떨어뜨린다. 페이지에 32-bit xmin/xmax 가 부족할 때만 *epoch 슬롯* 을 추가해 wraparound 영향을 페이지 단위로 회피한다. 또한 17+ 에서 *streaming I/O for vacuum* 이 도입되어 vacuum 의 디스크 처리율이 1.5~2.5배 증가했다. write-heavy OLTP 환경은 17+ 이주를 우선순위 상위에 둔다.

## 9. 운영 체크리스트와 모니터링 쿼리

매일 실행할 세 쿼리. 가장 오래된 xmin/MultiXact:

```sql
SELECT 'xid' AS kind, datname, age(datfrozenxid) AS age FROM pg_database
UNION ALL
SELECT 'mxid', datname, mxid_age(datminmxid) FROM pg_database
ORDER BY age DESC;
```

vacuum/analyze 마지막 수행 시각 — `pg_stat_user_tables` 에서 `last_vacuum`, `last_autovacuum` 조회. 진행 중인 vacuum 은 `pg_stat_progress_vacuum`. 알람 임계 권장값: `age(datfrozenxid) > 1_500_000_000` → P2, `> 1_800_000_000` → P1, `dead_ratio > 0.3 AND last_autovacuum < now() - interval '1 day'` → P2.

운영 4대 원칙으로 마무리한다. autovacuum 을 *끄지 않는다*. vacuum 임계를 *테이블별로* 조정한다. *fillfactor 와 HOT update 비율을 같이* 본다. wraparound age 를 *매일* 그래프로 본다.

## 참고

- PostgreSQL Documentation — Routine Vacuuming <https://www.postgresql.org/docs/current/routine-vacuuming.html>
- "Concurrency Control" 챕터 — 가시성 모델 상세 <https://www.postgresql.org/docs/current/mvcc.html>
- Peter Geoghegan, "How PostgreSQL deletes data" — PGCon 2023
- check_postgres bloat estimation 쿼리 <https://bucardo.org/check_postgres/>
- PostgreSQL 17 Release Notes — opportunistic freezing, streaming I/O
