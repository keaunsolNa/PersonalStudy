Notion 원본: https://app.notion.com/p/39e5a06fd6d38122b8e6c33ec25d726e

# Oracle 옵티마이저 통계와 Cardinality 추정 및 Adaptive Cursor Sharing

> 2026-07-15 신규 주제 · 확장 대상: Oracle(SQL 기본 / 인덱스)

## 학습 목표

- CBO 의 비용 산식과 Cardinality 추정이 실행계획을 결정하는 경로를 추적한다
- 히스토그램 4종의 선택 기준과 도수 분포 편향에서의 오차를 계산한다
- Bind Peeking 의 부작용을 Adaptive Cursor Sharing 이 보정하는 절차를 확인한다
- `DBMS_XPLAN` 의 E-Rows / A-Rows 괴리로 오추정 지점을 특정한다

## 1. CBO 의 판단 근거는 Cardinality 하나로 수렴한다

Oracle 옵티마이저(CBO)는 여러 실행계획 후보를 만들고 각각의 비용을 계산해 최소를 고른다. 비용 모델(System Statistics 기반)은 대략 다음과 같다.

```
Cost ≈ (#SRds × sreadtim + #MRds × mreadtim + #CPUCycles / cpuspeed) / sreadtim
```

`#SRds` 는 단일 블록 읽기 횟수, `#MRds` 는 다중 블록 읽기 횟수다. 인덱스 스캔은 SRd 가 많고 풀 스캔은 MRd 가 많다. 그런데 이 횟수를 계산하려면 **몇 건이 나오는지** 를 먼저 알아야 한다. 인덱스로 1,000만 건 중 5건을 찾는다면 SRd 몇 번이면 끝나지만, 500만 건이 나온다면 인덱스 경유 랜덤 액세스가 풀 스캔보다 훨씬 비싸다.

즉 **Cardinality(예상 행 수) 추정이 틀리면 비용도 계획도 전부 틀린다.** 실무에서 만나는 실행계획 사고의 대부분은 비용 모델의 문제가 아니라 카디널리티 오추정이다.

기본 추정식은 선택도(Selectivity)에 기반한다.

```
Cardinality = NUM_ROWS × Selectivity
```

| 조건 | 기본 Selectivity |
|---|---|
| `col = :b` (히스토그램 없음, 균등 가정) | `1 / NUM_DISTINCT` |
| `col > :b` | `(high_value - :b) / (high_value - low_value)` |
| `col BETWEEN :a AND :b` | 범위 비율 |
| `col LIKE '%x%'` | 5% (하드코딩된 추측) |
| `col IS NULL` | `NUM_NULLS / NUM_ROWS` |
| `func(col) = :b` | 1% (함수가 씨워지면 통계 무력) |
| 조건 2개 AND (독립 가정) | `S1 × S2` |

마지막 행이 두 번째로 큰 오추정 원인이다. CBO 는 컬럼 간 독립을 가정하는데, 현실 데이터는 상관관계가 있다.

```sql
-- 도시와 우편번호는 강한 상관관계
SELECT * FROM customers WHERE city = 'Seoul' AND zip_code = '06234';
-- CBO: (1/50) × (1/3000) = 6.7e-6 → 1,000만 건 × 6.7e-6 = 67건 예상
-- 실제: 우편번호가 이미 서울을 함의 → 3,300건
```

50배 과소추정이면 CBO 는 인덱스 + 네스티드 루프를 고르고, 실제로는 해시 조인이 맞는 상황이 된다.

## 2. 통계의 구성 요소

`DBA_TAB_STATISTICS` / `DBA_TAB_COL_STATISTICS` 가 원천이다.

```sql
SELECT column_name, num_distinct, density, num_nulls, num_buckets, histogram,
       last_analyzed
  FROM user_tab_col_statistics
 WHERE table_name = 'ORDERS';
```

| 컬럼 | 의미 | 오추정 시 증상 |
|---|---|---|
| `NUM_ROWS` | 테이블 행 수 | 전체 카디널리티가 스케일링 오류 |
| `BLOCKS` | 할당 블록 수 | 풀 스캔 비용 오산 |
| `NUM_DISTINCT` | 고유 값 수(NDV) | 등치 조건 선택도 직결 |
| `DENSITY` | 히스토그램 없으면 `1/NDV`, 있으면 보정값 | 등치 선택도 |
| `LOW_VALUE`/`HIGH_VALUE` | 최소/최대 | 범위 조건, out-of-range 문제 |
| `NUM_NULLS` | NULL 개수 | IS NULL 선택도 |
| `AVG_COL_LEN` | 평균 길이 | 메모리·정렬 비용 |

NDV 계산은 12c 부터 **HyperLogLog 기반 근사 알고리즘**(Approximate NDV)을 쓴다. `AUTO_SAMPLE_SIZE` 사용 시 전체 스캔 1회로 정확도 99% 수준의 NDV 를 얻는다. 그래서 `ESTIMATE_PERCENT` 를 수동으로 낮게 잡는 옛날 관행은 12c 이후 오히려 정확도를 떨어뜨린다.

```sql
-- ❌ 구식. 10% 샘플링은 NDV 를 심하게 왜곡
EXEC DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS', estimate_percent => 10);

-- ✅ 권장. Approximate NDV 알고리즘 활성
EXEC DBMS_STATS.GATHER_TABLE_STATS(
       USER, 'ORDERS',
       estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
       method_opt       => 'FOR ALL COLUMNS SIZE AUTO',
       cascade          => TRUE);
```

## 3. 히스토그램 4종

균등 분포 가정이 깨지는 컬럼(상태 코드, 국가, 등급)에는 히스토그램이 필요하다. 12c 부터 4종이 있다.

| 종류 | 조건 | 저장 내용 | 정확도 |
|---|---|---|---|
| **Frequency** | NDV ≤ 버킷 수(기본 254) | 값마다 정확한 빈도 | 완전 정확 |
| **Top-Frequency** | 상위 N 개 값이 전체의 99%+ 차지 | 상위 값만 정확, 나머지는 무시 | 상위 값 정확 |
| **Height-Balanced** | 11g 방식, NDV > 254 | 버킷마다 동일 행 수, 경계값 저장 | 부정확. 12c 이후 거의 안 씩 |
| **Hybrid** | NDV > 254 (12c 기본) | 높이 균형 + 각 버킷 끔값의 반복 횟수 | 상당히 정확 |

Hybrid 가 12c 의 개선점이다. Height-Balanced 는 인기 값(popular value)이 버킷 경계에 걸리는지 여부에 따라 선택도가 요동쳤는데, Hybrid 는 각 버킷 끔값이 몇 번 반복되는지를 함께 저장해 이 문제를 줄인다.

```sql
SELECT endpoint_value, endpoint_number, endpoint_repeat_count
  FROM user_tab_histograms
 WHERE table_name = 'ORDERS' AND column_name = 'STATUS'
 ORDER BY endpoint_number;
```

`method_opt => 'FOR ALL COLUMNS SIZE AUTO'` 는 "컬럼 사용 이력(`SYS.COL_USAGE$`)이 있고 데이터가 편향된 컬럼에만 히스토그램을 만든다" 는 뜻이다. 이력은 실제 쿼리가 그 컬럼을 조건절에 쓸 때 누적되므로, **새로 배포한 쿼리는 첫 통계 수집 시점에 히스토그램을 못 얻는다.** 배포 직후 계획이 나쁘다가 며칠 뒤 좋아지는 현상의 원인이 이것이다.

특정 컬럼을 강제하려면:

```sql
EXEC DBMS_STATS.GATHER_TABLE_STATS(
       USER, 'ORDERS',
       method_opt => 'FOR COLUMNS SIZE 254 STATUS, SIZE 1 CREATED_AT');
```

`SIZE 1` 은 히스토그램을 만들지 말라는 뜻이다. 균등 분포 컬럼에 히스토그램을 만들면 이득 없이 통계 수집 시간과 하드파싱 비용만 늘어난다.

## 4. 확장 통계 — 상관관계와 함수

§1 의 독립 가정 문제는 **Extended Statistics** 로 푸다.

```sql
-- 컬럼 그룹 통계
SELECT DBMS_STATS.CREATE_EXTENDED_STATS(USER, 'CUSTOMERS', '(CITY, ZIP_CODE)')
  FROM dual;

EXEC DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMERS',
       method_opt => 'FOR ALL COLUMNS SIZE AUTO FOR COLUMNS (CITY, ZIP_CODE)');
```

이렇게 하면 CBO 는 `(CITY, ZIP_CODE)` 조합의 실제 NDV 를 알고, `S1 × S2` 대신 조합 NDV 로 선택도를 계산한다. 위 예에서 67건 → 3,300건으로 추정이 교정된다.

어떤 컬럼 그룹이 필요한지 모르겠다면 워크로드에서 자동 탐지시킬 수 있다.

```sql
EXEC DBMS_STATS.SEED_COL_USAGE(NULL, NULL, 300);   -- 300초간 조건절 사용 관찰
-- 그동안 애플리케이션 워크로드 실행
SELECT DBMS_STATS.REPORT_COL_USAGE(USER, 'CUSTOMERS') FROM dual;
SELECT DBMS_STATS.CREATE_EXTENDED_STATS(USER, 'CUSTOMERS') FROM dual;  -- 자동 생성
```

함수 기반 조건도 같은 메커니즘으로 해결한다.

```sql
-- UPPER(last_name) 에는 통계가 없어 1% 고정 추정
SELECT DBMS_STATS.CREATE_EXTENDED_STATS(USER, 'EMPLOYEES', '(UPPER(LAST_NAME))')
  FROM dual;
```

내부적으로는 가상 컬럼(`SYS_STU...`)이 생성되고 거기에 통계·히스토그램이 붙는다. 비용은 통계 수집 시간 증가와 DML 시 가상 컬럼 평가 오버헤드다.

## 5. Bind Peeking 과 그 부작용

리터럴 SQL 은 값마다 하드파싱이 발생해 라이브러리 캐시를 오염시킨다. 그래서 바인드 변수를 쓴다. 그런데 바인드 변수를 쓰면 파싱 시점에 값을 모르므로 히스토그램을 활용할 수 없다.

9i 부터 **Bind Peeking** 이 도입되었다. 하드파싱 시점에 딱 한 번 바인드 값을 들여다보고, 그 값 기준으로 계획을 만든 뒤 캐시한다. 이후 모든 실행이 그 계획을 재사용한다.

문제는 편향 데이터다.

```sql
-- STATUS 분포: 'DONE' 9,990,000건 / 'ERROR' 100건
SELECT * FROM orders WHERE status = :s;
```

첫 실행이 `:s = 'ERROR'` 였다면 CBO 는 100건을 예상하고 인덱스 스캔 계획을 캐시한다. 이후 `:s = 'DONE'` 이 들어오면 999만 건을 인덱스 + 랜덤 액세스로 긁는다. 풀 스캔이었으면 수 초일 작업이 수십 분이 된다. 반대 순서면 100건 찾자고 999만 건을 풀 스캔한다.

**어떤 계획이 캐시되느냐가 인스턴스 재시작 순서에 좌우된다.** 어제는 빠르고 오늘은 느린, 재현되지 않는 성능 문제의 대표 원인이다.

## 6. Adaptive Cursor Sharing (11g+)

ACS 는 이 문제를 "한 SQL 에 여러 계획을 두고 바인드 값에 따라 고른다" 로 해결한다. 절차는 이렇다.

**1단계 — Bind Sensitive 판정.** 히스토그램이 있거나 범위 조건에 바인드가 쓰이면, 커서를 `IS_BIND_SENSITIVE = Y` 로 표시한다. 옵티마이저는 이 커서의 실행마다 A-Rows 를 관찰한다.

**2단계 — Bind Aware 승격.** 관찰된 실제 행 수가 예상과 크게 다른 실행이 나오면 `IS_BIND_AWARE = Y` 로 승격하고, 다음 실행부터 바인드 값을 보고 **재최적화**한다. 승격은 보통 3번째 실행에서 일어난다(첫 실행 관찰 → 두 번째 실행 오차 감지 → 세 번째부터 bind aware).

**3단계 — Selectivity Cube 로 계획 선택.** 각 자식 커서에 선택도 범위(cube)를 부여하고, 새 바인드 값의 선택도가 어느 cube 에 들어가는지로 계획을 고른다. 어디에도 안 들어가면 새 자식 커서를 만들고, 계획이 기존과 같으면 cube 를 병합한다.

```sql
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware, is_shareable,
       executions, buffer_gets/GREATEST(executions,1) AS gets_per_exec
  FROM v$sql
 WHERE sql_id = '&sql_id';
```

```
SQL_ID        CHILD IS_BIND_SENSITIVE IS_BIND_AWARE EXECUTIONS GETS_PER_EXEC
------------- ----- ----------------- ------------- ---------- -------------
7xyz9abc1def0     0 Y                 N                      2        180000
7xyz9abc1def0     1 Y                 Y                     45            12
7xyz9abc1def0     2 Y                 Y                     18        152000
```

child 0 은 초기 커서(shareable=N 이 되어 폐기 대기), child 1/2 가 각각 'ERROR'/'DONE' 계획이다.

선택도 cube 는 다음 뷰로 본다.

```sql
SELECT child_number, predicate, range_id, low, high
  FROM v$sql_cs_selectivity WHERE sql_id = '&sql_id';

SELECT child_number, bucket_id, count
  FROM v$sql_cs_histogram WHERE sql_id = '&sql_id';
```

### ACS 의 한계

세 가지가 실무에서 걸린다.

첫째, **학습 비용.** 승격 전 최초 몇 번의 실행은 여전히 나쁜 계획으로 돌다. 야간 배치가 하루 1회만 도는 SQL 이라면 ACS 는 영원히 학습만 하다 끝난다.

둘째, **커서 캐시 휘발.** 인스턴스 재시작, `SHARED_POOL` 플러시, 통계 재수집(커서 무효화)이 일어나면 학습 결과가 전부 날아간다. 재시작 직후 성능이 나쁜 이유가 여기 있다.

셋째, **커서 폭증.** 바인드 조합이 다양하면 자식 커서가 늘어 라이브러리 캐시 경합(`cursor: mutex S`)을 일으킨다. Oracle 은 자식 수가 과도하면 ACS 를 스스로 비활성화한다.

12c 의 **Adaptive Plans / SQL Plan Directives** 가 보완재다. Adaptive Plan 은 실행 중에 실제 행 수를 보고 네스티드 루프 ↔ 해시 조인을 전환하고, SPD 는 오추정 사실을 디스크에 영구 기록해 다음 통계 수집 시 확장 통계를 자동 생성한다.

```sql
SELECT directive_id, type, state, reason FROM dba_sql_plan_directives;
SELECT owner, object_name, subobject_name, object_type
  FROM dba_sql_plan_dir_objects WHERE directive_id = &id;
```

```
TYPE               STATE       REASON
------------------ ----------- ------------------------------------
DYNAMIC_SAMPLING   USABLE      SINGLE TABLE CARDINALITY MISESTIMATE
```

단 12.1 의 SPD 는 동적 샘플링을 과도하게 유발해 하드파싱 시간을 크게 늘리는 문제로 악명이 높았고, 12.2 에서 `PERMANENT` 상태 도입과 함께 완화되었다. 12.1 운영 중이라면 `OPTIMIZER_ADAPTIVE_FEATURES=FALSE` 로 끄는 것이 정석이었다. 12.2+ 는 `OPTIMIZER_ADAPTIVE_PLANS`(기본 TRUE 유지)와 `OPTIMIZER_ADAPTIVE_STATISTICS`(기본 FALSE)로 분리되었으므로 기본값을 그대로 두면 된다.

## 7. 진단 — E-Rows vs A-Rows

오추정 지점을 특정하는 표준 절차는 실제 실행 통계를 뽑는 것이다.

```sql
ALTER SESSION SET STATISTICS_LEVEL = ALL;
-- 또는 힌트: /*+ GATHER_PLAN_STATISTICS */

SELECT /*+ GATHER_PLAN_STATISTICS */ ...;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST +PEEKED_BINDS'));
```

```
-----------------------------------------------------------------------------------
| Id | Operation                    | Name      | Starts | E-Rows | A-Rows | Buffers |
-----------------------------------------------------------------------------------
|  0 | SELECT STATEMENT             |           |      1 |        |   3300 |  152341 |
|* 1 |  TABLE ACCESS BY INDEX ROWID | CUSTOMERS |      1 |     67 |   3300 |  152341 |
|* 2 |   INDEX RANGE SCAN           | IX_CITY   |      1 |     67 |   3300 |    1204 |
-----------------------------------------------------------------------------------

Peeked Binds (identified by position):
   1 - :S (VARCHAR2(30)): 'Seoul'
```

읽는 법은 단순하다. **E-Rows(예상) 와 A-Rows(실제) 를 비교하고, 괴리가 처음 발생하는 가장 안쪽 라인이 근본 원인이다.** 위 예는 Id 2 에서 67 vs 3300 — 50배 차이. §1 의 상관관계 문제이며 §4 의 확장 통계가 해법이다.

`Starts` 도 함께 본다. 네스티드 루프의 안쪽 라인에서 `Starts` 가 크면 그만큼 반복 실행된 것이고, `Buffers` 가 폭증한다. `Starts × E-Rows ≈ A-Rows` 여야 정상이다.

주의: `STATISTICS_LEVEL = ALL` 은 행마다 타이밍을 수집하므로 오버헤드가 크다(실측 10~30%). 운영에서 상시로 켜지 않고, 문제 SQL 에만 `GATHER_PLAN_STATISTICS` 힌트를 붙이는 방식을 쓴다.

## 8. 통계 관리 전략과 트레이드오프

| 전략 | 장점 | 단점 |
|---|---|---|
| 자동 통계 작업(기본) | 무관리. 10% 변경 시 자동 갱신 | 야간 윈도우에만 실행. 배치 후 즉시 반영 안 됨 |
| 배치 종료 시 수동 수집 | 대량 적재 직후 정확 | 수집 시간이 배치 시간에 포함 |
| `PENDING` 통계 검증 후 반영 | 회귀 위험 차단 | 절차 복잡 |
| 통계 LOCK + 대표값 고정 | 계획 안정성 | 데이터 변화 추종 불가 |
| SQL Plan Baseline | 계획 회귀 원천 차단 | 더 좋은 계획도 막힘. 진화 관리 필요 |

`PENDING` 통계는 안전한 반영 절차다.

```sql
-- 1. 통계를 pending 으로만 수집
EXEC DBMS_STATS.SET_TABLE_PREFS(USER, 'ORDERS', 'PUBLISH', 'FALSE');
EXEC DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS');

-- 2. 세션에서만 pending 통계로 계획 검증
ALTER SESSION SET OPTIMIZER_USE_PENDING_STATISTICS = TRUE;
EXPLAIN PLAN FOR SELECT ...;

-- 3. 문제없으면 공개
EXEC DBMS_STATS.PUBLISH_PENDING_STATS(USER, 'ORDERS');
EXEC DBMS_STATS.SET_TABLE_PREFS(USER, 'ORDERS', 'PUBLISH', 'TRUE');
```

**Out-of-range** 문제도 통계 전략과 얖힌다. `created_at` 같은 증가 컬럼은 통계 수집 이후 삽입된 최신 데이터가 `HIGH_VALUE` 를 넘어선다. `WHERE created_at >= SYSDATE - 1` 은 통계상 범위 밖이므로 CBO 가 선택도를 급격히 낮게 잡고(선형 감쇠), 카디널리티 1건을 예상해 네스티드 루프를 고른다. 실제로는 수십만 건이다.

해법은 두 가지다. 파티션 테이블이면 `INCREMENTAL` 통계로 파티션 단위 갱신을 싸게 만들고, 아니면 `DBMS_STATS.SET_COLUMN_STATS` 로 `HIGH_VALUE` 를 미래 값으로 수동 설정한다.

```sql
EXEC DBMS_STATS.SET_TABLE_PREFS(USER, 'ORDERS', 'INCREMENTAL', 'TRUE');
EXEC DBMS_STATS.SET_TABLE_PREFS(USER, 'ORDERS', 'GRANULARITY', 'AUTO');
```

`INCREMENTAL` 은 파티션별 시놀시스를 저장해 두고 글로벌 NDV 를 시놀시스 병합으로 계산한다. 전체 스캔 없이 글로벌 통계를 갱신하므로, 일별 파티션 테이블에서 통계 수집 시간이 수십 분 → 수십 초로 줄어든다. 대가는 `SYSAUX` 시놀시스 저장 공간이다.

## 참고

- Oracle Database SQL Tuning Guide — Optimizer Statistics Concepts: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/optimizer-statistics-concepts.html
- Oracle SQL Tuning Guide — Adaptive Query Optimization: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/adaptive-query-optimization.html
- Oracle Optimizer Blog — Understanding Adaptive Cursor Sharing: https://blogs.oracle.com/optimizer/
- Jonathan Lewis, *Cost-Based Oracle Fundamentals*, Apress
- Christian Antognini, *Troubleshooting Oracle Performance*, 2nd Ed., Apress
- Oracle White Paper — Best Practices for Gathering Optimizer Statistics: https://www.oracle.com/technetwork/database/bi-datawarehousing/twp-bp-for-stats-gather-19c-5324205.pdf
