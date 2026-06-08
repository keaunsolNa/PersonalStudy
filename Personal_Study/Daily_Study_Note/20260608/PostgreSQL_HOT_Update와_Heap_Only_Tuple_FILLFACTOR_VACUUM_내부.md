Notion 원본: https://www.notion.so/3795a06fd6d381c58610d5ba62a3463c

# PostgreSQL HOT Update와 Heap-Only Tuple, FILLFACTOR, VACUUM 내부

> 2026-06-08 신규 주제 · 확장 대상: Oracle

## 학습 목표

- PostgreSQL 의 MVCC 가 UPDATE 를 "새 튜플 삽입"으로 처리하는 구조와 그 부작용(인덱스 write amplification)을 설명한다
- HOT(Heap-Only Tuple) 업데이트가 성립하는 조건과 그 이득을 페이지 레벨에서 추적한다
- FILLFACTOR 가 HOT 성립률에 어떻게 영향을 주는지 수치로 판단한다
- VACUUM·HOT pruning·autovacuum 튜닝으로 테이블 bloat 를 통제한다

## 1. PostgreSQL UPDATE 의 진실: in-place 가 아니다

Oracle 은 UNDO 세그먼트에 이전 이미지를 저장하고 데이터 블록을 제자리 갱신(in-place update)한다. PostgreSQL 은 정반대다. UPDATE 는 기존 튜플을 죽었다고 표시(`xmax` 설정)하고 **새로운 버전의 튜플을 새 위치에 삽입**한다. 이 append-only 방식이 MVCC 를 단순하게 만들지만 두 비용을 낳는다. 첫째, 죽은 튜플이 쌓여 테이블이 부푼다(bloat). 둘째, 새 튜플은 새 물리 위치(ctid)를 가지므로 그 행을 가리키는 모든 인덱스가 새 엔트리를 추가해야 한다. 변경하지 않은 컬럼의 인덱스까지 갱신되는 이 현상이 write amplification 이다.

## 2. HOT: Heap-Only Tuple 업데이트

HOT 은 두 조건이 모두 성립할 때 발동한다. 첫째, 변경된 컬럼이 어떤 인덱스에도 포함되지 않을 것. 둘째, 새 튜플이 기존 튜플과 같은 페이지(8KB)에 들어갈 공간이 있을 것. 두 조건이 만족되면 PostgreSQL 은 인덱스를 전혀 건드리지 않고 페이지 내부 포인터 체인(HOT chain)을 만든다. 인덱스는 체인 head 를 가리키고 조회 시 체인을 따라가 살아있는 버전을 찾는다.

```sql
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd,0), 1) AS hot_ratio
FROM pg_stat_user_tables WHERE relname = 'orders';
```

`hot_ratio` 90% 이상이면 양호, 50% 이하면 인덱스 설계나 FILLFACTOR 를 의심한다.

## 3. FILLFACTOR: HOT 을 위한 빈 공간 확보

기본 FILLFACTOR 는 100이라 페이지를 꽉 채워 INSERT 한다. 꽉 찬 페이지에서는 UPDATE 시 새 튜플이 다른 페이지로 가야 해 HOT 이 깨진다.

```sql
ALTER TABLE orders SET (fillfactor = 80);
VACUUM FULL orders;   -- 또는 pg_repack 으로 무중단 재구성
```

| FILLFACTOR | 페이지 여유 | HOT 성립률 | 테이블 크기 |
|---|---|---|---|
| 100(기본) | 없음 | 낮음 | 최소 |
| 90 | 10% | 중간 | +약 11% |
| 80 | 20% | 높음 | +약 25% |
| 70 | 30% | 매우 높음 | +약 43% |

읽기 위주 테이블은 100 유지, 핫한 UPDATE 테이블만 선택적으로 70~85 로 낮추는 것이 정석이다.

## 4. HOT Pruning: 죽은 튜플 즉시 회수

페이지에 접근할 때(SELECT 포함) PostgreSQL 은 죽은 HOT 튜플을 line pointer 만 남기고 즉시 회수할 수 있다. VACUUM 을 기다리지 않고 페이지 내부에서 공간이 재활용되므로 bloat 가 자연 억제된다. 단 pruning 은 페이지 라이트락을 잠깐 잡으므로 SELECT 가 페이지를 dirty 로 만든다.

## 5. VACUUM 과 autovacuum

HOT 으로 안 되는 dead tuple 은 VACUUM 이 정리해 FSM 에 등록하고 visibility map 을 갱신한다. VACUUM 은 공간을 OS 에 반환하지 않고 재사용 가능으로만 표시하며, OS 반환은 VACUUM FULL 또는 pg_repack 이 필요하다.

```sql
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_cost_limit = 2000,
    autovacuum_vacuum_insert_scale_factor = 0.05
);
```

기본 scale_factor 0.2 는 테이블의 20% 가 dead 가 되어야 청소라 대형 테이블엔 너무 늦다. 테이블별로 낮춘다.

## 6. Bloat 진단과 Transaction ID Wraparound

```sql
CREATE EXTENSION IF NOT EXISTS pgstattuple;
SELECT * FROM pgstattuple('orders');
SELECT datname, age(datfrozenxid) AS xid_age FROM pg_database ORDER BY xid_age DESC;
```

VACUUM 은 오래된 튜플의 xmin 을 frozen 으로 표시해 32비트 트랜잭션 ID 순환 사고를 막는다. autovacuum 이 장시간 막히면 `age(datfrozenxid)` 가 `autovacuum_freeze_max_age`(기본 2억)에 근접해 강제 셧다운 위험에 빠진다. 긴 트랜잭션과 방치된 logical replication slot 이 양대 원인이다.

## 7. Oracle 과의 대비 정리

Oracle 은 UNDO + in-place 라 write amplification 이 구조적으로 없지만 UNDO 보존 정책과 ORA-01555(snapshot too old)라는 다른 문제를 진다. PostgreSQL 은 HOT/FILLFACTOR/VACUUM 손잡이를 운영자에게 노출하는 대신 그걸 안 돌리면 bloat 와 wraparound 로 벌받는 모델이다.

## 8. 페이지 내부 구조로 본 HOT 체인

8KB 힙 페이지는 헤더, line pointer(ItemId) 배열, 페이지 끝에서부터 채워지는 튜플 데이터로 구성된다. 인덱스 엔트리는 ctid(블록번호, line pointer 인덱스)를 가리킨다. HOT 업데이트 시 새 튜플이 같은 페이지에 추가되고 기존 튜플 헤더에 `HEAP_HOT_UPDATED` 플래그와 `t_ctid` 가 설정되어 체인이 형성된다. pruning 이 일어나면 죽은 튜플의 line pointer 는 `LP_REDIRECT` 로 바뀌어 살아있는 튜플로 점프한다. 이 간접화가 인덱스를 안 건드리면서 버전을 교체하는 트릭의 핵심이다.

## 9. 인덱스 bloat 와 B-tree Deduplication

PostgreSQL 13+ 의 B-tree deduplication 은 같은 키 값의 중복 엔트리를 posting list 하나로 묶어 인덱스 크기를 줄인다. 인덱스 bloat 가 심하면 `REINDEX INDEX CONCURRENTLY` 로 회수한다. 더 근본적으로는 자주 갱신되는 컬럼의 불필요한 인덱스를 제거해야 HOT 이 살아난다 — 인덱스 설계가 곧 UPDATE 성능 설계다.

## 10. autovacuum 모니터링 운영 루틴

`pg_stat_user_tables` 의 `n_dead_tup` 추세, `last_autovacuum`, `pg_stat_progress_vacuum`, `age(datfrozenxid)` 를 주기적으로 본다. 대량 DELETE/UPDATE 배치 직후엔 수동 `VACUUM (ANALYZE)` 를 트리거한다. VACUUM 은 어떤 트랜잭션도 더 볼 수 없는 튜플만 회수하므로 장기 트랜잭션이 옛 스냅샷을 붙잡으면 dead tuple 을 회수 못 한다(xmin horizon 정체). bloat 해결이 autovacuum 튜닝이 아니라 장기 트랜잭션을 끊는 것으로 귀결되는 경우가 많다.

## 참고

- PostgreSQL Documentation: "Heap-Only Tuples (HOT)" (src/backend/access/heap/README.HOT)
- PostgreSQL Documentation: Routine Vacuuming, Preventing Transaction ID Wraparound Failures
- `pgstattuple`, `pg_repack` 확장 문서
- "The Internals of PostgreSQL" — Hironobu Suzuki, Ch.6 VACUUM Processing
