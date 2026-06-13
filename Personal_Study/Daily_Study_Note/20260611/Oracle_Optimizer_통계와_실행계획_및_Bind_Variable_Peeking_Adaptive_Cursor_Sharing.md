Notion 원본: https://www.notion.so/37c5a06fd6d3816bad4dc01612361180

# Oracle Optimizer 통계와 실행계획 및 Bind Variable Peeking · Adaptive Cursor Sharing

> 2026-06-11 신규 주제 · 확장 대상: Oracle

## 학습 목표

- CBO가 통계(cardinality, selectivity)를 이용해 실행계획 비용을 계산하는 흐름을 설명한다
- 히스토그램이 데이터 편향(skew)을 어떻게 표현하고 계획 선택을 바꾸는지 구분한다
- bind variable peeking과 그로 인한 plan 불안정 문제를 진단한다
- Adaptive Cursor Sharing이 bind-aware 커서로 이 문제를 완화하는 원리를 파악한다

## 1. Cost-Based Optimizer의 기본

Oracle의 옵티마이저(CBO)는 SQL을 받으면 여러 실행계획 후보를 만들고 각 계획의 "비용(cost)"을 추정해 가장 싼 것을 고른다. 비용은 디스크 I/O, CPU, 메모리를 종합한 추정치이며, 그 토대가 통계(statistics)다. 통계는 테이블 행 수, 블록 수, 컬럼별 distinct 값 수(NDV), 최소·최대값, null 비율, 인덱스 클러스터링 팩터 등을 담는다.

핵심 개념은 cardinality와 selectivity다. selectivity는 조건이 걸렀을 때 남는 행의 비율(0~1)이고, cardinality는 `selectivity × 테이블 행 수`로 추정한 결과 행 수다. 예를 들어 `WHERE status = 'A'`에서 status의 distinct 값이 4개이고 균등 분포를 가정하면 selectivity는 1/4 = 0.25, 100만 행 테이블이면 cardinality는 25만으로 추정된다.

```sql
-- 통계 수집
EXEC DBMS_STATS.GATHER_TABLE_STATS(
  ownname => 'APP',
  tabname => 'ORDERS',
  method_opt => 'FOR ALL COLUMNS SIZE AUTO',
  estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
  cascade => TRUE
);
```

`AUTO_SAMPLE_SIZE`는 Oracle이 정확도와 비용의 균형을 맞춰 표본 크기를 정하는 모드로, 11g 이후 NDV를 거의 전수 수준 정확도로 계산하는 해시 기반 알고리즘을 사용한다.

## 2. 실행계획 읽기

실행계획은 `EXPLAIN PLAN` 또는 실제 실행 후 `DBMS_XPLAN`으로 확인한다. 추정 계획과 실제 실행 통계를 비교하는 것이 진단의 출발점이다.

```sql
SELECT /*+ GATHER_PLAN_STATISTICS */ *
FROM orders WHERE status = 'A' AND region = 'KR';

SELECT * FROM TABLE(
  DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST')
);
```

출력에서 핵심 컬럼은 `E-Rows`(추정 행 수)와 `A-Rows`(실제 행 수)다. 둘의 차이가 크면 통계가 현실과 어긋났다는 신호이며, 옵티마이저가 잘못된 조인 순서나 접근 경로를 골랐을 가능성이 높다. 예컨대 `E-Rows=1`인데 `A-Rows=500000`이면 nested loop를 골랐다가 대량 행에 대해 인덱스를 반복 탐색해 성능이 무너진다.

| 접근 경로 | 적합 상황 | cardinality 오추정 시 위험 |
|---|---|---|
| Index Range Scan + Nested Loop | 소량 결과 | 대량이면 반복 탐색으로 폭발 |
| Full Table Scan + Hash Join | 대량 결과 | 소량이면 불필요한 전체 스캔 |
| Index Fast Full Scan | 인덱스만으로 충족 | 커버링 안 되면 테이블 액세스 추가 |

## 3. 히스토그램과 데이터 편향

균등 분포 가정은 컬럼 값이 한쪽으로 쏠려 있으면 빗나간다. 예를 들어 `status` 컬럼이 `'A'`가 99%, `'B' / 'C' / 'D'`가 합쳐서 1%라면, `status = 'D'`는 매우 선택적인데도 균등 가정은 selectivity를 1/4로 잡아 cardinality를 과대평가한다. 히스토그램이 이 편향을 표현한다.

Oracle은 두 종류의 히스토그램을 쓴다. distinct 값이 적으면 frequency 히스토그램(값별 정확한 빈도), 많으면 height-balanced 또는 12c 이후 hybrid/top-frequency 히스토그램을 만든다.

```sql
-- 특정 컬럼에 히스토그램 강제 수집
EXEC DBMS_STATS.GATHER_TABLE_STATS(
  'APP', 'ORDERS',
  method_opt => 'FOR COLUMNS SIZE 254 status'
);
```

히스토그램이 있으면 옵티마이저는 `status = 'D'`가 희소함을 알고 인덱스 접근을, `status = 'A'`가 대다수임을 알고 full scan을 선택할 수 있다. 즉 같은 SQL 텍스트라도 리터럴 값에 따라 최적 계획이 달라진다. 바로 이 지점에서 bind variable과의 긴장이 발생한다.

## 4. Bind Variable과 커서 공유

리터럴을 직접 쓰면(`WHERE status = 'A'`) 값마다 SQL 텍스트가 달라져 매번 hard parse가 일어나고 shared pool이 같은 모양의 커서로 가득 찬다. 이를 막으려고 bind variable(`WHERE status = :1`)을 쓰면 SQL 텍스트가 동일해져 커서를 공유하고 parse 비용이 준다.

```sql
-- bind 사용: 하나의 공유 커서로 재사용
SELECT * FROM orders WHERE status = :1;
```

그런데 커서를 공유한다는 것은 "하나의 실행계획"을 공유한다는 뜻이다. `status` 값이 `'A'`(대다수)든 `'D'`(희소)든 같은 계획을 쓰게 되는데, 앞서 봤듯 두 값의 최적 계획은 정반대다. 여기서 trade-off가 생긴다. parse 절약(bind) vs 값별 최적 계획(리터럴).

## 5. Bind Variable Peeking

이 긴장을 줄이려고 Oracle은 bind variable peeking을 도입했다. 커서를 **처음 hard parse 할 때** 바인드 변수의 실제 값을 "엿보고(peek)" 그 값에 맞는 계획을 만든 뒤, 이후 같은 커서를 재사용하는 모든 실행이 그 계획을 그대로 쓴다.

문제는 첫 실행의 값이 대표적이지 않을 때다. 첫 호출이 `status = 'D'`(희소)였다면 옵티마이저는 인덱스 기반 계획을 만들고 캐시한다. 이후 `status = 'A'`(99%)로 호출돼도 같은 인덱스 계획을 재사용해, 99만 행을 인덱스로 한 건씩 접근하는 재앙이 벌어진다. 반대 순서면 full scan을 희소 값에도 적용해 비효율이 생긴다.

```sql
-- peeking 영향 진단: 같은 sql_id 인데 실행마다 성능이 들쭉날쭉
SELECT sql_id, child_number, plan_hash_value, executions, buffer_gets
FROM v$sql
WHERE sql_id = '&target_sql_id';
```

이 "계획 불안정(plan instability)"은 운영에서 가장 골치 아픈 성능 이슈 중 하나다. 같은 쿼리가 어떤 날은 빠르고 어떤 날은 느린데, 코드도 데이터도 안 바뀐 것처럼 보이기 때문이다. 실제 원인은 커서가 에이징되어 사라진 뒤 첫 재파싱 때 우연히 들어온 바인드 값이다.

## 6. Adaptive Cursor Sharing

11g부터 도입된 Adaptive Cursor Sharing(ACS)이 peeking의 한계를 완화한다. ACS는 selectivity가 바인드 값에 따라 크게 달라지는 SQL을 "bind-sensitive"로 표시하고, 실제 실행에서 행 수가 예상과 크게 다르면 "bind-aware"로 승격시켜 **바인드 값 구간별로 서로 다른 child cursor(계획)를 생성·유지**한다.

동작 흐름은 다음과 같다. 처음에는 peeking으로 한 계획을 만들되 bind-sensitive 플래그를 켠다. 실행을 모니터링하다가 같은 커서에서 처리 행 수 편차가 임계를 넘으면 bind-aware로 전환한다. 이후부터는 바인드 값의 selectivity 범위를 프로파일로 묶어, `'A'`류 호출과 `'D'`류 호출에 각각 알맞은 계획을 따로 캐시한다.

```sql
-- ACS 상태 확인
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware, is_shareable
FROM v$sql
WHERE sql_id = '&target_sql_id';
```

`IS_BIND_SENSITIVE = Y`는 ACS 감시 대상이라는 뜻, `IS_BIND_AWARE = Y`는 이미 값 구간별 계획 분기가 활성화됐다는 뜻이다. 추가로 `V$SQL_CS_HISTOGRAM`, `V$SQL_CS_SELECTIVITY`에서 selectivity 버킷과 분기 기준을 볼 수 있다.

| 메커니즘 | 효과 | 한계 |
|---|---|---|
| Bind peeking | 첫 값에 맞춘 계획 1개 | 비대표 첫 값 시 계획 불안정 |
| Adaptive Cursor Sharing | 값 구간별 계획 N개 | 초기 학습 비용, child cursor 증가 |
| SQL Plan Baseline | 검증된 계획만 사용하도록 고정 | 운영 관리 필요 |

## 7. 안정화 전략

ACS가 만능은 아니다. bind-aware로 승격되기 전 몇 번의 실행은 여전히 나쁜 계획을 겪을 수 있고, child cursor가 늘면 shared pool 압박과 versioning 문제가 생긴다. 그래서 운영에서는 보완 전략을 병행한다.

첫째, **SQL Plan Management(SPM) 베이스라인**으로 검증된 계획만 쓰게 고정한다. 새 계획이 나와도 베이스라인에 등록·검증되기 전에는 채택되지 않아 회귀를 막는다.

```sql
-- 현재 커서의 계획을 베이스라인으로 고정
DECLARE
  n PLS_INTEGER;
BEGIN
  n := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(sql_id => '&sql_id');
END;
/
```

둘째, **통계 최신성 유지**다. 편향이 큰 컬럼에 히스토그램을 확보하고, 데이터가 빠르게 변하는 테이블은 통계 갱신 주기를 조정한다. 통계가 낡으면 ACS의 판단 근거 자체가 틀어진다.

셋째, **극단적 편향 쿼리는 분기**한다. `'A'`류와 `'D'`류를 애플리케이션에서 다른 SQL로 분리하거나, 매우 희소한 조회에는 리터럴/힌트를 의도적으로 사용해 계획을 고정한다. 모든 것을 옵티마이저에 맡기기보다, 데이터 분포를 아는 개발자가 경계를 그어주는 편이 안정적일 때가 많다.

## 8. 정리

Oracle 성능 튜닝의 많은 부분이 "옵티마이저가 cardinality를 정확히 추정하게 만드는 일"로 귀결된다. 통계와 히스토그램이 추정의 입력이고, bind variable은 parse 비용을 줄이는 대신 값별 최적 계획과 충돌하며, peeking과 ACS는 그 충돌을 다루는 장치다.

실무 점검 순서를 정리하면, 느린 SQL이 있으면 먼저 `DBMS_XPLAN`으로 `E-Rows`와 `A-Rows`를 비교해 추정 오차를 확인하고, 편향 컬럼에 히스토그램이 있는지 본다. 그다음 `V$SQL`에서 `is_bind_sensitive`·`is_bind_aware`로 ACS 상태를, child cursor 수와 plan_hash_value 분산으로 계획 불안정을 점검한다. 회귀가 반복되면 SPM 베이스라인으로 검증된 계획을 고정한다. 이 흐름을 따르면 "데이터도 코드도 그대로인데 갑자기 느려진" 전형적 사건의 원인을 대부분 bind peeking과 통계에서 찾을 수 있다.

## 9. 실전 진단 시나리오: 갑자기 느려진 SQL

운영에서 가장 흔한 사건은 "어제까지 멀쩡하던 쿼리가 오늘 갑자기 느려짐"이다. 코드도 데이터도 그대로인데 응답이 수십 배 느려졌다면, 단계적으로 원인을 좁힌다.

```sql
-- 1) 같은 sql_id 에 child cursor 가 여럿이고 plan_hash 가 다른가?
SELECT child_number, plan_hash_value, executions,
       buffer_gets, buffer_gets/GREATEST(executions,1) AS gets_per_exec
FROM v$sql
WHERE sql_id = '&sql_id'
ORDER BY child_number;
```

`plan_hash_value`가 child별로 다르고 `gets_per_exec`가 들쭉날쭉하면 bind peeking에 의한 계획 불안정이 강하게 의심된다. 처음 hard parse 때 들어온 바인드 값이 비대표적이어서 나쁜 계획이 캐시된 전형적 사례다.

```sql
-- 2) bind-sensitive / bind-aware 여부
SELECT child_number, is_bind_sensitive, is_bind_aware, is_shareable
FROM v$sql WHERE sql_id = '&sql_id';

-- 3) 캐시된 계획에서 실제로 peek 된 바인드 값 확인
SELECT name, position, datatype_string, value_string
FROM v$sql_bind_capture
WHERE sql_id = '&sql_id';
```

`is_bind_sensitive = Y`인데 `is_bind_aware = N`이면, ACS가 감시는 하지만 아직 값 구간별 분기를 만들지 않은 상태다. 이 구간에서는 첫 계획을 계속 쓰므로 비대표 값에 묶이면 느릴 수 있다. `v$sql_bind_capture`로 어떤 값이 peek 됐는지 보면, 예컨대 희소 값 `'D'`가 peek 되어 인덱스 계획이 굳었음을 확인할 수 있다.

대응은 상황에 따라 나뉜다. 임시 완화로는 `DBMS_SHARED_POOL.PURGE`로 해당 커서를 제거해 재파싱을 유도하거나, 통계를 갱신해 옵티마이저가 다시 판단하게 한다. 근본 대응은 좋은 계획을 SPM 베이스라인으로 고정하거나, 편향이 큰 컬럼에 히스토그램을 확보해 ACS가 빠르게 bind-aware로 전환되게 하는 것이다.

```sql
-- 4) 임시: 문제 커서 퍼지(재파싱 유도)
-- address, hash_value 는 v$sqlarea 에서 조회
EXEC DBMS_SHARED_POOL.PURGE('&address, &hash_value', 'C');
```

마지막으로, 자동 통계 수집 작업(`auto optimizer stats collection`)이 야간에 돌면서 통계가 바뀌어 계획이 흔들리는 경우도 있다. 통계 변경 시점과 성능 저하 시점을 `DBA_OPTSTAT_OPERATIONS` / `DBA_TAB_STATS_HISTORY`로 대조하면 "통계 갱신이 원인인지"를 가릴 수 있고, 필요하면 `DBMS_STATS.RESTORE_TABLE_STATS`로 직전 통계로 되돌려 즉시 안정화할 수 있다. 이처럼 Oracle의 계획 불안정은 대부분 bind peeking·통계 변경 두 축으로 환원되며, 진단 쿼리 몇 개로 원인을 좁히는 절차를 몸에 익히는 것이 핵심이다.

## 참고

- Oracle Database SQL Tuning Guide — Optimizer Statistics Concepts: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/optimizer-statistics-concepts.html
- Oracle Database SQL Tuning Guide — Adaptive Cursor Sharing: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/cursor-sharing.html
- Oracle Database SQL Tuning Guide — Histograms: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/histograms.html
- Oracle Database SQL Tuning Guide — Managing SQL Plan Baselines: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/managing-sql-plan-baselines.html
