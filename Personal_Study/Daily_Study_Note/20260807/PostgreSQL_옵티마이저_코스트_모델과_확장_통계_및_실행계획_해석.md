Notion 원본: https://www.notion.so/3b55a06fd6d3812da9dac1c1be215092

# PostgreSQL 옵티마이저 코스트 모델과 확장 통계 및 실행계획 해석

> 2026-08-07 신규 주제 · 확장 대상: PostgreSQL (MVCC·인덱스 심화 기학습)

## 학습 목표

- 플래너 코스트 파라미터(seq_page_cost, random_page_cost, cpu_*_cost, effective_cache_size)가 실행계획 선택에 미치는 영향을 계산식 수준에서 설명한다.
- pg_stats 의 n_distinct, MCV, 히스토그램, correlation 을 근거로 선택도 추정 오차의 원인을 진단한다.
- CREATE STATISTICS 확장 통계(dependencies, ndistinct, mcv)로 상관 컬럼의 row 추정을 개선하고 before/after 를 검증한다.
- EXPLAIN (ANALYZE, BUFFERS) 출력에서 actual/estimated 괴리, loops 곱셈, 버퍼 지표를 읽고 플랜 회귀를 탐지하는 운영 루틴을 구성한다.

## 1. 플래너 코스트 모델의 구조

PostgreSQL 옵티마이저는 비용 기반(cost-based)이다. 각 후보 플랜 노드에 대해 디스크 I/O 와 CPU 연산을 추상 단위로 환산한 코스트를 계산하고, 총 코스트가 가장 낮은 플랜을 선택한다. 기준 단위는 `seq_page_cost = 1.0`, 즉 순차 읽기로 8KB 페이지 하나를 가져오는 비용이다. 나머지 파라미터는 모두 이 기준에 대한 상대값이다.

| 파라미터 | 기본값 | 의미 |
|---|---|---|
| seq_page_cost | 1.0 | 순차 페이지 읽기 비용 (기준 단위) |
| random_page_cost | 4.0 | 랜덤 페이지 읽기 비용 |
| cpu_tuple_cost | 0.01 | 튜플 1건 처리 비용 |
| cpu_index_tuple_cost | 0.005 | 인덱스 엔트리 1건 처리 비용 |
| cpu_operator_cost | 0.0025 | 연산자/함수 1회 평가 비용 |
| effective_cache_size | 4GB | 커널 페이지 캐시 + shared_buffers 로 캐시될 것으로 기대하는 크기 추정치 |

예를 들어 100만 행, 10,000 페이지 테이블의 순차 스캔 코스트는 대략 `10000 * 1.0 + 1000000 * 0.01 = 20000` 으로 계산된다. WHERE 절 연산자가 하나 있으면 행당 `cpu_operator_cost` 가 추가된다.

`effective_cache_size` 는 자주 오해되는 파라미터다. 이 값은 **메모리를 할당하지 않는다**. 플래너가 인덱스 스캔 중 반복 접근 페이지가 캐시에 남아 있을 확률을 추정하는 힌트일 뿐이며, 클수록 인덱스 스캔의 기대 I/O 비용이 낮아진다. 관례적으로 시스템 메모리의 50~75% 로 설정한다.

`random_page_cost = 4.0` 은 seek 비용이 큰 회전식 디스크를 가정한 값이다. SSD/NVMe 는 랜덤·순차 읽기 차이가 작으므로 1.1~1.5 로 낮추는 것이 일반적이다. 낮출수록 인덱스 스캔이 공격적으로 선택되지만, 콜드 캐시에서 느린 인덱스 스캔이 과선택되는 trade-off 가 있다.

```sql
-- 세션 단위 실험: SSD 환경 가정
SET random_page_cost = 1.1;
SET effective_cache_size = '24GB';
EXPLAIN SELECT * FROM orders WHERE customer_id = 42;
-- 테이블스페이스 단위로도 지정 가능
ALTER TABLESPACE fast_ssd SET (random_page_cost = 1.1);
```

주의할 점은 코스트는 절대 시간이 아니라는 것이다. 코스트 20000 이 코스트 10000 보다 2배 느리다는 보장은 없다. 코스트 모델은 플랜 간 상대 비교를 위한 순서화 장치다.

## 2. pg_statistic 과 pg_stats: 통계의 원천

플래너의 모든 row 추정은 `ANALYZE` 가 수집해 `pg_statistic` 카탈로그에 저장한 표본 통계에서 나온다. 사람이 읽기 좋은 뷰가 `pg_stats` 다.

```sql
SELECT attname, n_distinct, most_common_vals, most_common_freqs,
       histogram_bounds, correlation
FROM pg_stats
WHERE tablename = 'orders' AND attname IN ('status', 'created_at');
```

- **n_distinct**: 고유값 개수 추정. 양수면 절대 개수, 음수면 행 수 대비 비율(-1 은 전부 고유, -0.5 는 행의 50%). 표본 기반 고유값 추정은 통계적으로 어려운 문제라 대량 테이블에서 크게 빗나갈 수 있으며, 그 경우 `ALTER TABLE ... ALTER COLUMN ... SET (n_distinct = -0.9)` 처럼 수동 고정이 가능하다.
- **MCV (most_common_vals / most_common_freqs)**: 가장 빈번한 값 목록과 각 빈도. 등호 조건이 MCV 에 있으면 그 빈도를 그대로 선택도로 쓰므로 추정이 정확하다. 심하게 치우친(skewed) 분포에서 핵심적 역할을 한다.
- **histogram_bounds**: MCV 를 제외한 나머지 값들을 등빈도(equi-depth) 구간으로 나눈 경계. 범위 조건의 선택도 계산에 쓰인다.
- **correlation**: 컬럼 값 순서와 물리적 저장 순서의 상관계수(-1~1). 1에 가까우면(순차 증가 PK 등) 인덱스 순회 시 힙 접근이 거의 순차 I/O 가 되어 코스트가 `seq_page_cost` 쪽으로 보간되고, 0에 가까우면 `random_page_cost` 기준의 비싼 스캔으로 계산된다. 같은 선택도라도 correlation 이 낮으면 Bitmap Heap Scan 이나 Seq Scan 이 선택되는 이유다.

표본 크기와 MCV/히스토그램 슬롯 수는 `default_statistics_target`(기본 100)이 결정한다. 표본은 대략 `300 × target` 행이다. 값을 1000으로 올리면 skew 가 심한 컬럼의 추정 정확도가 올라가지만 ANALYZE 시간과 플래닝 시간(통계 목록 순회 비용)이 늘어나는 trade-off 가 있다. 전역 상향보다는 문제 컬럼만 올리는 것이 정석이다.

```sql
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 1000;
ANALYZE orders;
```

## 3. 선택도 추정: 연산자별 동작과 독립 가정의 함정

선택도(selectivity)는 조건을 통과하는 행의 비율 추정치다. `추정 행 수 = reltuples × selectivity` 다.

- **등호(=)**: 상수가 MCV 목록에 있으면 해당 빈도를 사용. 없으면 `(1 - MCV 빈도 합) / (n_distinct - MCV 개수)` 로 "나머지 값은 균등 분포" 가정을 적용한다.
- **범위(<, >, BETWEEN)**: 히스토그램 경계 안에서 상수 위치를 선형 보간한다. 함정은 **히스토그램 범위 밖의 값**이다. `WHERE created_at > now() - interval '1 hour'` 같은 최신 구간은 마지막 ANALYZE 이후 삽입분이라 히스토그램에 없어 과소 추정되기 쉽다. 시계열 append-only 테이블에서 autoanalyze 주기를 짧게 가져가야 하는 이유다.
- **LIKE / 정규식**: `LIKE 'abc%'` 처럼 고정 접두사가 있으면 범위 조건(`>= 'abc' AND < 'abd'`)으로 변환해 히스토그램을 쓴다. `LIKE '%abc%'` 는 히스토그램을 쓸 수 없어 패턴 길이 기반 휴리스틱으로 추정하므로 오차가 크다.

가장 큰 함정은 **컬럼 간 독립 가정**이다. `WHERE a = 1 AND b = 2` 의 결합 선택도는 기본적으로 `sel(a) × sel(b)` 로 곱해지므로, 두 컬럼이 강하게 상관되어 있으면(`city = 'Seoul' AND country = 'KR'` 등) 수십~수백 배 과소 추정된다. 과소 추정은 Nested Loop 선택 → 상위 조인 loop 폭발이라는 전형적 장애 패턴으로 이어진다.

## 4. CREATE STATISTICS: 확장(다변량) 통계

PostgreSQL 10부터 컬럼 조합에 대한 확장 통계를 만들 수 있다. 세 종류가 있다.

```sql
-- 함수적 종속: zip 이 정해지면 city 가 사실상 결정되는 관계
CREATE STATISTICS st_addr_dep (dependencies) ON city, zip FROM addresses;

-- 다변량 n_distinct: GROUP BY city, zip 의 그룹 수 추정 개선
CREATE STATISTICS st_addr_nd (ndistinct) ON city, zip FROM addresses;

-- 다변량 MCV: 자주 나오는 (city, zip) 조합의 빈도 직접 저장 (PG 12+)
CREATE STATISTICS st_addr_mcv (mcv) ON city, zip FROM addresses;

ANALYZE addresses;
```

before/after 를 실측하면 효과가 명확하다. `city` 와 `zip` 이 1:다 로 종속된 100만 행 테이블에서:

```sql
EXPLAIN ANALYZE
SELECT * FROM addresses WHERE city = 'Seoul' AND zip = '04524';
-- 확장 통계 전:
--   Seq Scan on addresses (cost=... rows=6 width=...)
--     (actual time=... rows=4870 loops=1)
--   → sel(city)=0.05 × sel(zip)=0.0001 곱셈으로 6행 과소 추정
-- dependencies 통계 후:
--   Seq Scan on addresses (cost=... rows=4680 width=...)
--     (actual time=... rows=4870 loops=1)
--   → zip 이 city 를 함수적으로 결정함을 인지, zip 선택도만 적용
```

rows=6 vs rows=4870 의 800배 오차가 사라지면, 이를 입력으로 받는 상위 조인의 알고리즘 선택(Nested Loop → Hash Join 등)도 함께 교정된다. dependencies 는 등호 결합에만 작동하고, mcv 통계는 특정 조합의 skew 까지 잡는다. 확장 통계는 ANALYZE·플래닝 비용을 늘리므로 괴리가 실측된 컬럼 조합에만 선별 적용하며, 수집 결과는 `pg_stats_ext` 뷰로 확인한다.

## 5. 조인 플래닝: 알고리즘 선택과 탐색 공간 제어

세 가지 조인 알고리즘의 비용 특성은 다음과 같다.

| 알고리즘 | 유리한 조건 | 비용 특성 |
|---|---|---|
| Nested Loop | outer 가 소수 행 + inner 에 인덱스 | outer 행 수 × inner 1회 접근 비용. outer 추정이 빗나가면 비용 폭발 |
| Hash Join | 대량 등가 조인, 한쪽이 work_mem 에 수용 | build 측 해시 생성 후 probe. work_mem 초과 시 배치(디스크 spill) 분할 |
| Merge Join | 양쪽이 이미 정렬(인덱스/이전 노드) | 정렬 비용이 관건. 정렬돼 있으면 대량 조인에서 최저 비용 |

Nested Loop 는 시작 비용(startup cost)이 0에 가까워 `LIMIT` 쿼리에 유리하고, Hash Join 은 전체 처리량에 유리하다. 플래너는 row 추정에 따라 이를 저울질하므로, 3절의 추정 오차가 조인 알고리즘 오선택의 근본 원인이 되는 경우가 많다.

조인 순서 탐색 공간은 테이블 수에 대해 지수적으로 커진다. `join_collapse_limit`(기본 8)은 명시적 JOIN 구문을, `from_collapse_limit`(기본 8)은 FROM 절 항목을 하나의 탐색 단위로 병합하는 상한이다. 이 값을 넘는 조인은 작성된 순서가 부분적으로 고정되어 최적 순서를 놓칠 수 있고, 값을 올리면 플래닝 시간이 급증한다. `join_collapse_limit = 1` 로 두면 SQL 에 쓴 조인 순서를 그대로 강제하는 수동 튜닝 수단이 된다.

테이블 수가 `geqo_threshold`(기본 12) 이상이면 전수 탐색 대신 GEQO(유전 알고리즘)로 전환한다. GEQO 는 플래닝 시간을 억제하는 대신 준최적 플랜을 낼 수 있고, `geqo_seed` 를 고정하지 않으면 실행마다 플랜이 달라져 재현성이 떨어진다. 다중 조인 뷰가 중첩된 쿼리에서 플랜이 널뛰면 GEQO 진입을 먼저 의심한다.

## 6. EXPLAIN (ANALYZE, BUFFERS, ...) 읽기

```sql
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT o.*, c.name
FROM orders o JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'PENDING' AND o.created_at > now() - interval '1 day';
```

- `ANALYZE` 는 쿼리를 **실제 실행**한다. DML 검증 시에는 `BEGIN; EXPLAIN ANALYZE ...; ROLLBACK;` 으로 감싼다.
- `SETTINGS` 는 기본값과 다른 플래너 관련 파라미터를 함께 출력해, "이 플랜이 어떤 설정에서 나왔는가" 재현성을 보장한다.
- `WAL` 은 생성된 WAL 레코드 수/바이트를 보여 DML 의 쓰기 부하를 정량화한다.

핵심 판독 포인트 세 가지.

**첫째, estimated vs actual rows 괴리.** `rows=10`(estimated) 인데 `actual rows=52000` 이면 그 노드의 통계·선택도가 문제라는 신호다. 오차는 상위로 전파·증폭되므로 10배 이상 괴리 나는 최하위 노드부터 찾는다.

**둘째, loops 곱셈 함정.** actual 수치(rows, time)는 **loop 1회당 평균**이다. `rows=1 loops=48000` 은 실제 4만 8천 행이고, `actual time=0.050..0.120 loops=48000` 은 총 5.76초다. Nested Loop 하위 노드의 진짜 비용은 loops 를 곱해 읽는다.

**셋째, Rows Removed by Filter.** `rows=100, Rows Removed by Filter: 990000` 이면 99% 를 읽고 버린 것으로, 복합 인덱스나 부분 인덱스 후보를 시사한다. `Rows Removed by Index Recheck` 는 lossy bitmap(work_mem 부족) 신호다.

`BUFFERS` 의 `shared hit`(캐시 적중)/`read`(캐시 밖 읽기)/`dirtied`/`written` 은 I/O 실측치다. hit/read 비율로 플랜이 나쁜지 캐시가 식었는지를 분리 진단하고, `temp read/written` 은 정렬·해시의 디스크 spill 로서 work_mem 조정 근거가 된다. `FORMAT JSON` 출력은 시각화 도구나 자동 회귀 비교 스크립트의 입력으로 쓰기 좋다.

## 7. 플랜 안정성: prepared statement, plan_cache_mode, pg_hint_plan

Prepared statement 는 파라미터 값마다 통계를 반영한 **custom plan** 을 만들다가, 규칙에 따라 파라미터 무관 **generic plan** 으로 전환될 수 있다. 기본 동작(`plan_cache_mode = auto`)은 처음 5회는 custom plan 을 만들고, 이후 generic plan 의 추정 코스트가 지금까지의 custom plan 평균 코스트보다 싸다고 판단되면 generic plan 을 고정한다.

문제는 파라미터 민감(skew) 쿼리다. `WHERE status = $1` 에서 'FAILED'(0.1%) 는 인덱스 스캔이, 'DONE'(90%) 는 Seq Scan 이 최적이라면, generic plan 으로 고정되는 순간 한쪽 파라미터에서 성능이 급락한다. "5회까지는 빠르다가 6번째부터 느려졌다" 는 전형적 증상이 이 전환 시점이다.

```sql
-- 파라미터마다 통계 기반 플랜을 강제
SET plan_cache_mode = force_custom_plan;   -- 매번 플래닝 비용을 지불하는 trade-off
-- 반대로 플래닝 비용 절감이 목적이면
SET plan_cache_mode = force_generic_plan;
-- prepared statement 의 현재 플랜 확인
PREPARE q(text) AS SELECT count(*) FROM orders WHERE status = $1;
EXPLAIN (ANALYZE) EXECUTE q('FAILED');
```

PostgreSQL 코어는 옵티마이저 힌트를 제공하지 않지만, 서드파티 확장 `pg_hint_plan` 으로 `/*+ IndexScan(o idx_orders_status) Leading((o c)) HashJoin(o c) */` 형태의 힌트를 줄 수 있다. 힌트는 통계 문제를 덮는 응급 처치이며 데이터 분포가 변하면 힌트 자체가 회귀 원인이 되므로, 통계·확장 통계·파라미터라는 근본 원인 교정이 우선이다. `enable_seqscan = off` 류는 해당 노드에 페널티 코스트를 얹는 방식으로, 프로덕션 고정용이 아니라 플래너가 왜 그 플랜을 안 골랐는지 실험하는 진단 도구다.

## 8. 회귀 탐지 운영 루틴: auto_explain + pg_stat_statements

플랜 회귀는 배포가 아니라 **데이터 분포 변화·ANALYZE 타이밍·plan cache 전환**으로도 발생하므로, 상시 관측 체계가 필요하다.

```sql
-- postgresql.conf
shared_preload_libraries = 'pg_stat_statements,auto_explain'
auto_explain.log_min_duration = '500ms'   -- 임계 초과 쿼리만 플랜 로깅
auto_explain.log_analyze = on             -- actual 수치 포함
auto_explain.log_buffers = on
auto_explain.log_timing = off             -- 타이머 오버헤드 절감 옵션
auto_explain.log_nested_statements = on   -- 함수 내부 SQL 포함
auto_explain.log_format = 'json'
```

`log_analyze = on` 은 조건에 걸리지 않은 쿼리에도 계측 오버헤드를 부과할 수 있으므로, 고빈도 OLTP 에서는 `log_timing = off` 나 `sample_rate`(예: 0.1)로 비용을 통제한다.

운영 루틴은 다음과 같이 구성한다.

1. **베이스라인**: `pg_stat_statements` 의 queryid 별 `mean_exec_time`, `calls`, `shared_blks_read`, `temp_blks_written` 을 주기 수집한다.
2. **탐지**: 베이스라인 대비 급증한 queryid 를 알림으로 잡는다. 실행시간보다 `shared_blks_read` 급증이 플랜 변화를 더 일찍 드러내는 경우가 많다.
3. **확보**: 해당 시각의 auto_explain JSON 로그에서 실제 플랜을 회수한다. 사후 EXPLAIN 은 통계가 이미 바뀌어 당시 플랜이 재현되지 않을 수 있다.
4. **원인 분류**: (a) rows 괴리 → ANALYZE 주기·statistics target·확장 통계 (b) generic plan 전환 → plan_cache_mode (c) GEQO 비결정성 → geqo_seed (d) 캐시 콜드 → BUFFERS read 비중.
5. **재발 방지**: 교정 후 지표의 베이스라인 복귀를 확인하고, 대형 배치 적재 직후 명시적 `ANALYZE` 를 파이프라인에 넣는다.

pg_stat_statements 는 쿼리를 정규화(상수 제거)해 집계하므로 파라미터별 편차는 보이지 않는다는 한계가 있다. 파라미터 민감 쿼리는 7절의 plan_cache_mode 진단과 auto_explain 의 개별 실행 로그로 보완한다.

## 참고

- PostgreSQL 공식 문서 — Planner Cost Constants: https://www.postgresql.org/docs/current/runtime-config-query.html
- PostgreSQL 공식 문서 — Statistics Used by the Planner / Extended Statistics: https://www.postgresql.org/docs/current/planner-stats.html
- PostgreSQL 공식 문서 — Row Estimation Examples: https://www.postgresql.org/docs/current/row-estimation-examples.html
- PostgreSQL 공식 문서 — Using EXPLAIN: https://www.postgresql.org/docs/current/using-explain.html
- PostgreSQL 공식 문서 — CREATE STATISTICS: https://www.postgresql.org/docs/current/sql-createstatistics.html
- PostgreSQL 공식 문서 — Genetic Query Optimizer: https://www.postgresql.org/docs/current/geqo.html
- PostgreSQL 공식 문서 — auto_explain: https://www.postgresql.org/docs/current/auto-explain.html
- PostgreSQL 공식 문서 — pg_stat_statements: https://www.postgresql.org/docs/current/pgstatstatements.html
- pg_hint_plan 공식 문서: https://pg-hint-plan.readthedocs.io/
