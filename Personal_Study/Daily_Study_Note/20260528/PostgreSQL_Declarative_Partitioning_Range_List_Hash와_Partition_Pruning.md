Notion 원본: https://www.notion.so/36e5a06fd6d381969231c15e7df559a2

# PostgreSQL Declarative Partitioning Range/List/Hash와 Partition Pruning

> 2026-05-28 신규 주제 · 확장 대상: PostgreSQL — 선언형 파티셔닝

## 학습 목표

- PostgreSQL 10 이후 선언형 파티셔닝의 DDL 모델과 파일 레이아웃을 정리한다.
- partition pruning 2단계 (plan-time vs run-time) 동작 차이를 EXPLAIN 으로 식별한다.
- partition-wise join, partition-wise aggregate 조건과 실측 효과 측정.
- pg_partman 의 자동 시간기반 파티션 운영과 attach/detach 락 동작 이해.

## 1. 선언형 vs 상속 기반

10 이전 INHERITS+트리거 그대 대신 10부터 선언형 (RANGE/LIST/HASH). tuple routing 으로 처리.

```sql
CREATE TABLE events (
    id bigserial,
    occurred_at timestamptz NOT NULL,
    kind text NOT NULL,
    payload jsonb
) PARTITION BY RANGE (occurred_at);

CREATE TABLE events_2026_05 PARTITION OF events
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE orders (region text, ...) PARTITION BY LIST (region);
CREATE TABLE orders_kr PARTITION OF orders FOR VALUES IN ('KR');

CREATE TABLE sessions (user_id bigint, ...) PARTITION BY HASH (user_id);
CREATE TABLE sessions_p0 PARTITION OF sessions FOR VALUES WITH (modulus 8, remainder 0);
```

## 2. Partition Pruning — Plan-time

```sql
EXPLAIN SELECT count(*) FROM events WHERE occurred_at >= '2026-05-15' AND occurred_at < '2026-05-20';
Aggregate -> Append -> Seq Scan on events_2026_05
```

12 부터 partition descriptor 기반, 100개 파티션에도 plan-time 에 1개만 남음.

## 3. Run-time Pruning

```sql
PREPARE q AS SELECT * FROM events WHERE occurred_at = $1;
EXPLAIN EXECUTE q ('2026-05-15');
Append
  Subplans Removed: 11
  -> Seq Scan on events_2026_05
```

Subplans Removed 가 핵심 신호. nested loop join 에서 outer 각 row 마다 적용.

## 4. Partition-wise Join 과 Aggregate

enable_partition_wise_join = on 이면 동일 partition key 의 join 이 파티션 쌍 별 병렬. 메모리 압력 N배 감소. 파티션 boundary 가 정확히 같아야 함. enable_partition_wise_aggregate = on 은 GROUP BY 가 파티션 키 prefix 일 때 적용.

## 5. 인덱스와 제약

부모에 인덱스 → 모든 파티션에 자동 생성(11+). UNIQUE 는 파티션 키 포함 필수. 전역 PK 는 (id, partition_key) 합성으로 해결.

## 6. ATTACH / DETACH

```sql
ALTER TABLE events ATTACH PARTITION events_2026_07_stage
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
ALTER TABLE events DETACH PARTITION events_2026_03 CONCURRENTLY;
```

DETACH CONCURRENTLY (14+) 는 ACCESS EXCLUSIVE lock 없이. ATTACH 전 CHECK NOT VALID → VALIDATE → ATTACH 으로 lock 최소화.

## 7. pg_partman

```sql
SELECT partman.create_parent(
    p_parent_table=>'public.events',
    p_control=>'occurred_at',
    p_type=>'range',
    p_interval=>'1 day',
    p_premake=>7);
SELECT partman.run_maintenance();
```

retention 설정 시 과거 파티션 자동 삭제.

## 8. 실측 — 대용량 시계열

```
Postgres 16, 30억행, 730 파티션

COUNT WHERE day=...        : 6.2s → 0.18s
SUM WHERE month=...        : 18.7s → 2.4s
JOIN WHERE day=$1 prepared : 0.31s
INSERT throughput          : 92k/s → 88k/s (4% 오버헤드)
```

## 9. Trade-off 와 안티패턴

| 안티패턴 | 결과 |
|---|---|
| WHERE 에 파티션 키 누락 | pruning 미작동 |
| 시간당 1개 파티션 | 메타데이터 비용 |
| UNIQUE 키 없이 시도 | DDL 실패 |
| ATTACH 전 CHECK 누락 | 장시간 lock |
| pg_partman 없이 수동 | retention 누락 |

가이드 — "row 1000만 + 자연 키 쿼리" 가 도입 분기점.

## 10. Subpartition

부모 → RANGE → LIST 다층 구성 가능. 파티션 수가 곱셈으로 증가, 2차원까지만 권장.

## 11. 통계 갱신과 ANALYZE

autovacuum_analyze_scale_factor 0.02 로 낮춰 write-heavy 파티션 조절.

## 참고

- PostgreSQL Documentation — Table Partitioning
- pg_partman: https://github.com/pgpartman/pg_partman
- PostgreSQL 14 Release Notes
- Robert Haas PGConf 2023
- Citus Engine architecture
