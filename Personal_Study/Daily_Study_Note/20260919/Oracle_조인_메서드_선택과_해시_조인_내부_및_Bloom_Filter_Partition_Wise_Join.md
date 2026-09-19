Notion 원본: https://www.notion.so/3e05a06fd6d381339bacf36f00dbe8a7

# Oracle 조인 메서드 선택과 해시 조인 내부 및 Bloom Filter Partition Wise Join

> 2026-09-19 신규 주제 · 확장 대상: Oracle, SQLD

## 학습 목표

- Nested Loop / Sort Merge / Hash Join의 비용 모델을 카디널리티와 PGA 관점에서 비교한다
- 해시 조인의 build/probe 단계와 Optimal / One-pass / Multi-pass 워크에어리어 등급을 실행 통계로 확인한다
- Bloom Filter 프루닝(`:BF0000`)이 파티션 프루닝 및 PX 조인과 결합하는 지점을 읽는다
- `V$SQL_WORKAREA`, `V$PQ_TQSTAT`, `GATHER_PLAN_STATISTICS`로 조인 병목을 수치로 확정한다

## 1. 조인 메서드 세 가지의 비용 구조

Oracle CBO는 두 집합을 붙일 때 세 알고리즘 중 하나를 고른다. 선택 기준은 취향이 아니라 추정 카디널리티와 접근 경로 비용의 산술이다.

**Nested Loop.** 외부(driving) 행 하나마다 내부 테이블을 탐색한다. 비용은 대략 `cost(outer) + card(outer) × cost(inner 1회 probe)`. 내부 탐색이 유니크 인덱스 한 번이면 probe 비용이 2~4 블록이므로, 외부 카디널리티가 작을 때(수백~수천) 압도적으로 싸다. OLTP 쿼리의 기본형이다. 11g 이후 NLJ는 벡터화된 형태(`NESTED LOOPS` 두 줄 + `TABLE ACCESS BY INDEX ROWID BATCHED`)로 나타나며, 내부 rowid를 모아 배치로 테이블 블록을 읽어 단일 블록 I/O 횟수를 줄인다.

**Sort Merge.** 양쪽을 조인 키로 정렬한 뒤 병합한다. 비용은 `cost(A) + cost(B) + sort(A) + sort(B)`. 등가 조인에서는 해시 조인에 거의 항상 밀리지만, **비등가 조인**(`a.dt BETWEEN b.st AND b.en`)에서는 해시 조인이 불가능하므로 유일한 대안이다. 한쪽이 이미 인덱스 순서로 정렬되어 나온다면 그쪽 정렬이 생략되어 경쟁력이 생긴다.

**Hash Join.** 작은 쪽으로 해시 테이블을 만들고(build) 큰 쪽을 흘려보내며 조회한다(probe). 등가 조인에서만 가능하고, 비용은 이상적으로 `cost(A) + cost(B)` — 즉 양쪽 풀 스캔 한 번씩이다. 대용량 집계·배치의 기본 선택이다.

| 상황 | 유리한 메서드 | 결정적 요인 |
|---|---|---|
| 외부 수백 행, 내부 유니크 인덱스 | Nested Loop | probe 1회 비용이 상수 |
| 양쪽 수백만 행, 등가 조인 | Hash Join | 각 1회 스캔, PGA 안에 build 수용 |
| 범위·비등가 조인 | Sort Merge | HJ 불가, 정렬 후 1회 병합 |
| 한쪽만 거대, 다른 쪽 필터로 극소 | Hash Join + Bloom | 프루닝으로 큰 쪽 스캔량 감소 |

실전에서 실행계획이 "잘못된" 메서드를 고른 경우, 원인의 9할은 메서드 선택 로직이 아니라 **카디널리티 추정 오류**다. 조인 순서를 힌트로 고정하기 전에 `E-Rows`와 `A-Rows`의 괴리부터 봐야 한다.

## 2. 실측의 출발점 — GATHER_PLAN_STATISTICS

추정치와 실측치를 나란히 보는 것이 모든 조인 튜닝의 1단계다.

```sql
SELECT /*+ GATHER_PLAN_STATISTICS */
       o.order_id, c.customer_name, SUM(l.amount)
  FROM orders      o
  JOIN customers   c ON c.customer_id = o.customer_id
  JOIN order_lines l ON l.order_id    = o.order_id
 WHERE o.order_date >= DATE '2026-09-01'
   AND c.region_code = 'KR'
 GROUP BY o.order_id, c.customer_name;

SELECT * FROM TABLE(
  DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST +OUTLINE +PREDICATE'));
```

출력에서 볼 열은 넷이다.

- `E-Rows` : 옵티마이저 추정 행 수
- `A-Rows` : 실제 반환 행 수
- `A-Time` : 해당 단계 누적 경과 시간
- `Buffers` / `Reads` : 논리·물리 읽기

`Starts × E-Rows`와 `A-Rows`를 비교한다. Nested Loop 내부 단계는 `Starts`가 외부 행 수만큼 찍히므로 곱해서 봐야 한다. 두 값이 한 자릿수 배 차이면 무시, 100배 이상 벌어지는 첫 단계가 근본 원인이다.

추가로 `OMem`, `1Mem`, `Used-Mem` 열이 나타나면 그 단계가 워크에어리어를 쓴다는 뜻이다. `Used-Mem`에 `(1)`이 붙으면 Optimal, `(0)`이면 one-pass 이상으로 디스크를 썼다는 표시다.

```
|  3 |  HASH JOIN  | ... | 1 |  2M |  4M | 98M (0)| 33M | 2 | 41M |
                                            ^^^^^^^^  → one-pass, 41MB 임시 세그먼트 사용
```

## 3. 해시 조인 내부 — build, probe, 그리고 파티셔닝

Oracle의 해시 조인은 Grace Hash Join 계열이다. 동작은 세 국면으로 나뉜다.

**(1) Build 입력 선택.** 옵티마이저는 추정 크기가 작은 쪽을 build로 삼는다. 실행계획에서 `HASH JOIN` 바로 아래 **첫 번째** 자식이 build 입력이다. 이 선택이 틀어지면(통계 부재로 큰 쪽을 build로 잡으면) 즉시 디스크로 내려간다. `SWAP_JOIN_INPUTS` 힌트로 강제 교체할 수 있다.

```sql
SELECT /*+ LEADING(c o) USE_HASH(o) SWAP_JOIN_INPUTS(c) */ ...
```

**(2) 인메모리 build.** build 입력을 읽으며 조인 키로 해시하여 해시 테이블을 만든다. 전체가 워크에어리어에 들어가면 Optimal이다. `V$SQL_WORKAREA_ACTIVE`로 실행 중 상태를 볼 수 있다.

```sql
SELECT sql_id, operation_type, policy,
       ROUND(actual_mem_used/1048576) used_mb,
       ROUND(max_mem_used/1048576)    max_mb,
       ROUND(tempseg_size/1048576)    temp_mb,
       number_passes
  FROM v$sql_workarea_active
 WHERE operation_type LIKE 'HASH%';
```

**(3) 디스크 파티셔닝(one-pass / multi-pass).** build가 메모리를 넘치면 양쪽 입력을 조인 키 해시로 N개 파티션에 나눠 임시 테이블스페이스에 쓴다. 이후 파티션 쌍을 하나씩 다시 읽어 조인한다. 파티션 하나가 여전히 메모리를 넘으면 재귀적으로 다시 쪼갠다 — 이것이 multi-pass이고, `number_passes > 1`이면 I/O가 기하급수로 늘어난다. multi-pass는 사실상 항상 "고쳐야 하는 상태"다.

누적 통계는 `V$SQL_WORKAREA`에서 본다.

```sql
SELECT sql_id, operation_type,
       optimal_executions, onepass_executions, multipasses_executions,
       ROUND(estimated_optimal_size/1048576)  opt_mb,
       ROUND(estimated_onepass_size/1048576)  onepass_mb,
       ROUND(last_memory_used/1048576)        last_mb
  FROM v$sql_workarea
 WHERE sql_id = :sql_id
 ORDER BY operation_id;
```

`estimated_optimal_size`가 "Optimal로 돌리려면 필요한 PGA"다. 이 값과 현재 세션이 받을 수 있는 최대 워크에어리어를 비교해야 한다.

## 4. 워크에어리어 크기 — PGA_AGGREGATE_TARGET의 실제 배분

자동 PGA 관리(`WORKAREA_SIZE_POLICY=AUTO`)에서 세션 하나가 받는 워크에어리어는 `PGA_AGGREGATE_TARGET` 전체가 아니다. 내부 한도가 걸린다.

- 직렬 실행: 대략 `PGA_AGGREGATE_TARGET`의 5% 또는 `_smm_max_size`(KB), 둘 중 작은 값
- 병렬 실행: 전체 PX 집합에 대해 대략 30%까지, 이를 DOP로 나눠 슬레이브가 나눠 가짐

즉 PGA_AGGREGATE_TARGET이 10GB라도 직렬 해시 조인 하나가 쓸 수 있는 메모리는 수백 MB 수준이다. 현재 한도는 다음으로 확인한다.

```sql
SELECT name, value/1024/1024 mb
  FROM v$pgastat
 WHERE name IN ('aggregate PGA target parameter',
                'global memory bound',
                'total PGA allocated',
                'over allocation count');
```

`global memory bound`가 직렬 워크에어리어의 실효 상한이다. `over allocation count`가 증가 중이면 타깃 자체가 부족하다는 뜻이다.

19c 이후 `PGA_AGGREGATE_LIMIT`은 하드 리밋으로 동작해 초과 세션을 ORA-04036으로 종료시킨다. 타깃만 올리고 리밋을 방치하면 야간 배치가 죽는다.

## 5. Bloom Filter — 조인 프루닝이 파티션 프루닝이 되는 지점

해시 조인에서 build 쪽이 강한 필터로 극소수 행만 남는다면, probe 쪽 거대 테이블을 전부 읽는 것은 낭비다. Oracle은 build 단계에서 조인 키의 **블룸 필터**를 만들어 probe 스캔 시점에 미리 대부분의 행을 버린다. 실행계획에 다음 두 줄로 나타난다.

```
|   4 |   PART JOIN FILTER CREATE      | :BF0000  |
|   8 |    TABLE ACCESS FULL           | SALES    |  Pstart=:BF0000  Pstop=:BF0000
```

또는 병렬 실행에서는 이렇게 보인다.

```
|   6 |   JOIN FILTER CREATE           | :BF0000  |
|  10 |    JOIN FILTER USE             | :BF0000  |
```

두 형태의 의미가 다르다.

**PART JOIN FILTER CREATE + Pstart=:BF0000** 은 블룸 필터가 **파티션 프루닝**으로 승격된 경우다. probe 테이블이 조인 키로 파티셔닝되어 있을 때, build 쪽에서 나온 키 집합으로 읽을 파티션 자체를 골라낸다. 스캔량이 파티션 단위로 줄어들므로 효과가 가장 크다.

**JOIN FILTER CREATE / USE** 는 행 단위 필터다. 블룸 필터는 확률적 자료구조라 false positive를 허용하므로, 통과한 행은 이후 해시 테이블 조회에서 다시 검증된다. false negative는 없으므로 결과 정확성은 보장된다.

효과는 실행 통계로 확인한다.

```sql
SELECT name, value
  FROM v$sesstat s JOIN v$statname n USING (statistic#)
 WHERE s.sid = SYS_CONTEXT('USERENV','SID')
   AND n.name LIKE 'Bloom filter%';
-- Bloom filter rows rejected / rows accepted / filtered on partitions
```

`rows rejected` 비율이 90% 이상이면 필터가 제 역할을 한 것이고, 0에 가까우면 필터 생성 비용만 쓴 셈이다. 후자라면 `NO_PX_JOIN_FILTER(alias)` 힌트로 끌 수 있다.

블룸 필터가 안 생기는 전형적 원인 세 가지: build 쪽 추정 카디널리티가 너무 크게 나옴(통계 문제), 조인 키에 함수·암시적 형변환이 끼어 있음, 직렬 실행이면서 probe 쪽이 파티션 테이블이 아님.

## 6. Partition Wise Join — 조인 자체를 파티션 단위로 쪼개기

양쪽 테이블이 **같은 키·같은 방식·같은 파티션 수**로 파티셔닝되어 있으면, 조인을 파티션 쌍별로 독립 수행할 수 있다. 데이터 재분배(PX SEND HASH)가 통째로 사라진다.

```sql
CREATE TABLE orders (
  order_id    NUMBER,
  customer_id NUMBER,
  order_date  DATE,
  amount      NUMBER
) PARTITION BY HASH (customer_id) PARTITIONS 32;

CREATE TABLE order_lines (
  line_id     NUMBER,
  order_id    NUMBER,
  customer_id NUMBER,
  amount      NUMBER
) PARTITION BY HASH (customer_id) PARTITIONS 32;   -- 동일 키, 동일 파티션 수
```

`customer_id`로 조인하면 실행계획에 `PX PARTITION HASH ALL`이 나타나고 `PX SEND HASH`가 사라진다 — full partition-wise join이다. 한쪽만 파티셔닝되어 있으면 partial partition-wise join이 되어 한쪽만 재분배한다.

**Reference Partitioning**은 이 정렬을 FK로 자동 유지해 준다. 자식 테이블에 파티션 키를 중복 저장할 필요가 없다.

```sql
CREATE TABLE order_lines (
  line_id  NUMBER,
  order_id NUMBER NOT NULL,
  amount   NUMBER,
  CONSTRAINT fk_ol_o FOREIGN KEY (order_id) REFERENCES orders(order_id)
) PARTITION BY REFERENCE (fk_ol_o);
```

부모의 파티션 구조 변경이 자식에 전파되고, 조인은 항상 partition-wise가 된다. 대신 FK가 `NOT NULL`이어야 하고 파티션 유지보수 작업이 부모에 묶인다.

병렬 조인에서 재분배가 균형을 잃었는지는 `V$PQ_TQSTAT`으로 본다.

```sql
SELECT dfo_number, tq_id, server_type, process, num_rows, bytes
  FROM v$pq_tqstat
 ORDER BY dfo_number, tq_id, server_type DESC, process;
```

같은 `server_type='Producer'` 안에서 `num_rows`가 슬레이브별로 10배 이상 벌어지면 데이터 스큐다. 이 경우 12c 이후의 hybrid hash 분배나 `PQ_DISTRIBUTE` 힌트로 방식을 바꾼다.

```sql
SELECT /*+ PARALLEL(8) PQ_DISTRIBUTE(l HASH HASH) */ ...
-- 한쪽이 아주 작으면 BROADCAST NONE 이 유리
SELECT /*+ PARALLEL(8) PQ_DISTRIBUTE(c BROADCAST NONE) */ ...
```

주의: `V$PQ_TQSTAT`은 **같은 세션에서 병렬 쿼리를 실행한 직후에만** 내용이 있다. 다른 세션에서 조회하면 비어 있다.

## 7. 카디널리티 추정을 고치는 편이 먼저다

조인 메서드를 힌트로 못 박는 것은 마지막 수단이다. 추정이 틀린 원인을 먼저 제거한다.

**다중 컬럼 상관관계.** `WHERE region='KR' AND country='KR'` 처럼 상관된 두 조건은 독립 가정 때문에 선택도가 과소 추정된다. 확장 통계로 고친다.

```sql
SELECT DBMS_STATS.CREATE_EXTENDED_STATS(USER, 'CUSTOMERS', '(region_code, country_code)')
  FROM dual;

BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMERS',
      method_opt => 'FOR ALL COLUMNS SIZE AUTO FOR COLUMNS (region_code, country_code) SIZE AUTO');
END;
/
```

**함수 적용 컬럼.** `WHERE TRUNC(order_date) = :d`는 컬럼 통계를 못 쓴다. 표현식 통계나 함수 기반 인덱스, 또는 조건을 범위로 바꾸는 편이 낫다.

```sql
WHERE order_date >= :d AND order_date < :d + 1
```

**바인드 변수 스큐.** 히스토그램이 있는 컬럼에 바인드를 쓰면 첫 실행의 bind peeking 값이 계획을 결정한다. Adaptive Cursor Sharing이 보완하지만 첫 실행은 어쩔 수 없다. 계획이 널뛰면 SQL Plan Baseline으로 고정한다.

```sql
DECLARE
  n PLS_INTEGER;
BEGIN
  n := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(sql_id => '&sql_id', plan_hash_value => &phv);
END;
/
```

**동적 샘플링.** 통계가 오래됐거나 복잡한 조건일 때 `/*+ DYNAMIC_SAMPLING(4) */`로 임시 보완할 수 있지만, 파싱 시간이 늘어나므로 OLTP에는 쓰지 않는다.

## 8. 진단에서 조치까지 — 체크리스트

실행계획 하나를 앞에 두고 순서대로 묻는다.

1. `A-Rows`와 `E-Rows`가 처음 크게 벌어지는 라인은 어디인가 → 그 단계의 통계·조건을 본다
2. 해시 조인의 `Used-Mem`에 `(0)`이 있는가 → one-pass 이상. `estimated_optimal_size`와 `global memory bound` 비교
3. build 입력이 작은 쪽인가 → `HASH JOIN` 첫 자식 확인, 필요하면 `SWAP_JOIN_INPUTS`
4. probe 쪽 거대 테이블에 `:BF0000`이 붙었는가 → 없다면 왜 안 붙었는지(형변환·통계) 확인
5. 병렬이라면 `PX SEND HASH`가 있는가 → 파티션 정렬로 제거 가능한지 검토
6. `V$PQ_TQSTAT`의 슬레이브별 행 수가 고른가 → 스큐면 분배 방식 변경
7. 계획이 실행마다 바뀌는가 → Baseline 고정

임시 테이블스페이스 압박이 실제 병목인지는 대기 이벤트로 교차 확인한다. `direct path write temp` / `direct path read temp`가 Top 대기에 올라오면 해시 조인이나 정렬의 디스크 폴백이 원인일 가능성이 높다.

```sql
SELECT event, total_waits, time_waited_micro/1e6 sec
  FROM v$system_event
 WHERE event LIKE 'direct path%temp'
 ORDER BY time_waited_micro DESC;
```

마지막으로 트레이드오프를 분명히 해 둔다. PGA를 키워 Optimal 해시 조인을 만드는 것은 동시 세션 수와 정면으로 충돌한다. 배치 전용 서비스라면 세션당 `WORKAREA_SIZE_POLICY=MANUAL` + `HASH_AREA_SIZE`를 수동 지정해 특정 잡에만 큰 메모리를 주는 편이, 인스턴스 전체 타깃을 올리는 것보다 안전하다. 반대로 OLTP 혼재 환경에서는 조인 메서드를 Nested Loop로 유지하며 인덱스로 푸는 쪽이 예측 가능성 면에서 낫다.

## 참고

- Oracle Database SQL Tuning Guide 19c/23ai — "Joins" 및 "Optimizer Access Paths"
- Oracle Database VLDB and Partitioning Guide — "Partition-Wise Joins"
- Oracle Database Reference — `V$SQL_WORKAREA`, `V$SQL_WORKAREA_ACTIVE`, `V$PGASTAT`, `V$PQ_TQSTAT`
- Oracle Database PL/SQL Packages and Types Reference — `DBMS_XPLAN`, `DBMS_STATS`, `DBMS_SPM`
- Jonathan Lewis, *Cost-Based Oracle Fundamentals* (Apress)
- Christian Antognini, *Troubleshooting Oracle Performance*, 2nd ed. (Apress)
