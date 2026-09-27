Notion 원본: https://www.notion.so/3e85a06fd6d38110b726f803a5d36370

# Oracle CBO 히스토그램과 바인드 변수 Peeking 및 Adaptive Cursor Sharing, SQL Plan Baseline

> 2026-09-27 신규 주제 · 확장 대상: Oracle, 최적화 기본

## 학습 목표

- 왜곡된 컬럼에 히스토그램을 직접 생성해보고 CBO의 카디널리티 추정치가 실제 분포와 어떻게 달라지는지 재현한다
- 바인드 변수 Peeking으로 최초 실행 시점의 리터럴 값에 실행계획이 고정되는 부작용을 10046/10053 트레이스로 확인한다
- Adaptive Cursor Sharing의 bind-sensitive → bind-aware 전이 조건을 V$SQL로 관찰하고 한계를 파악한다
- SQL Plan Baseline을 생성·검증·진화(evolve)시켜 특정 실행계획을 운영계에서 강제 고정하는 절차를 수행한다

## 1. CBO의 통계 기반 비용 계산 원리와 DBMS_STATS

Oracle CBO는 SQL 실행 전에 후보 계획(access path, join method, join order)별 비용을 계산해 가장 낮은 것을 선택한다. 이 비용은 predicate의 selectivity/cardinality 추정에서 출발해 I/O·CPU 비용 모델을 더한 값이다. 히스토그램이 없으면 CBO는 컬럼 값이 균등 분포한다고 가정해 selectivity를 `1/NUM_DISTINCT`로 계산하는데, 실무 데이터는 특정 값에 쏠린(skewed) 경우가 많아 이 괴리가 실행계획 오판의 근본 원인이 된다.

DBMS_STATS는 통계 수집 표준 패키지로, `METHOD_OPT => 'FOR ALL COLUMNS SIZE AUTO'`를 쓰면 Oracle이 컬럼 사용 이력(`SYS.COL_USAGE$`)과 분포 왜곡 정도를 참고해 히스토그램 필요 여부를 자동 판단한다.

```sql
CREATE TABLE orders_hist (
  order_id NUMBER PRIMARY KEY, cust_id NUMBER NOT NULL,
  status VARCHAR2(10) NOT NULL, order_date DATE NOT NULL, amount NUMBER(12,2));

-- status: 'DONE' 이 95%를 차지하는 왜곡 컬럼
INSERT INTO orders_hist
SELECT LEVEL, MOD(LEVEL,5000)+1,
       CASE WHEN MOD(LEVEL,100) < 95 THEN 'DONE'
            WHEN MOD(LEVEL,100) < 98 THEN 'CANCEL' ELSE 'PENDING' END,
       SYSDATE - MOD(LEVEL,365), ROUND(DBMS_RANDOM.VALUE(1000,50000),2)
FROM DUAL CONNECT BY LEVEL <= 1000000;
COMMIT;
EXEC DBMS_STATS.GATHER_TABLE_STATS(USER,'ORDERS_HIST', -
  method_opt=>'FOR ALL COLUMNS SIZE AUTO', cascade=>TRUE);
```

`AUTO_SAMPLE_SIZE`는 근사 NDV(해시 기반 sketch) 알고리즘으로 전체 스캔에 가까운 정확도를 낮은 비용에 얻으므로, 임의의 샘플링 비율(예: 10%)을 고정 지정하는 관행은 지양한다.

## 2. 히스토그램 종류와 데이터 왜곡 탐지

11g까지 Height-Balanced/Frequency 두 종류였고, 12c부터 Hybrid·Top-Frequency가 추가돼 총 4종이다. 생성 종류는 버킷 수, distinct value 개수, popular value 비중으로 자동 결정된다.

- **Frequency**: distinct value ≤ 254(기본 버킷 수)일 때 각 값의 정확한 빈도를 저장. 가장 정확하나 distinct value 많으면 못 쓴다.
- **Top-Frequency(12c+)**: distinct value가 많아도 상위 N개 popular value가 대부분(예: 99%↑)이면 그 값만 정확 기록, 롱테일은 폐기.
- **Hybrid(12c+)**: 버킷 경계값마다 반복 횟수(`ENDPOINT_REPEAT_COUNT`)를 저장해 popular value와 넓은 분포를 동시 표현. Height-Balanced의 사실상 대체제.
- **Height-Balanced(레거시)**: 동일 행 수 기준 버킷 분할로 대략적 쏠림만 표현. 12c 이후 Hybrid로 대체 권장.

| 히스토그램 종류 | 도입 버전 | 저장 방식 | 적합한 데이터 특성 | 정확도 |
| --- | --- | --- | --- | --- |
| Frequency | 이전부터 | 모든 distinct value의 정확한 빈도 | distinct value ≤ 254 | 매우 높음 |
| Top-Frequency | 12c | 상위 popular value만 정확 기록 | distinct value 많고 극단적 쏠림 | 높음(popular 한정) |
| Hybrid | 12c | 버킷 경계 + endpoint repeat count | distinct value 많고 부분 쏠림 | 중간~높음 |
| Height-Balanced | 레거시 | 동일 행 수 기준 버킷 분할 | (Hybrid로 대체 권장) | 낮음~중간 |

```sql
EXEC DBMS_STATS.GATHER_TABLE_STATS(USER,'ORDERS_HIST', -
  method_opt=>'FOR COLUMNS status SIZE 254', cascade=>TRUE);

SELECT column_name, num_distinct, histogram, num_buckets
FROM user_tab_col_statistics
WHERE table_name='ORDERS_HIST' AND column_name='STATUS';
-- HISTOGRAM=FREQUENCY, NUM_BUCKETS=3 (버킷 내용은 USER_TAB_HISTOGRAMS 에서 확인)
```

히스토그램이 없으면 `PENDING`(2%)과 `DONE`(95%) 모두 `1,000,000/3≈333,333`건으로 동일하게 추정되지만, 히스토그램이 있으면 각각 20,000건, 950,000건에 가깝게 추정되어 access path 선택이 달라진다. 이 지점이 다음 절의 바인드 Peeking 문제로 직결된다.

## 3. 바인드 변수 Peeking 메커니즘과 최초 실행계획 고정 문제

바인드 변수는 리터럴 변경마다 hard parse가 반복되는 것을 막지만, CBO는 파싱 시점에 실제 값을 알 수 없다는 딜레마가 생긴다. Bind Peeking(9i~)은 "최초 1회 hard parse 시 바인딩된 실제 값을 CBO가 훔쳐본다"는 방식으로 이를 해결하고, 결정된 계획을 커서에 캐싱해 이후 재사용한다.

문제는 컬럼이 왜곡됐을 때다. `status = :b1`에서 최초 `'PENDING'`(2%, 인덱스 유리)이 바인딩되면 인덱스 스캔으로 고정되고, 이후 `'DONE'`(95%, 풀스캔 유리)이 반복돼도 인덱스 스캔이 재사용되어 성능이 크게 저하된다. "어제까지 잘 돌던 쿼리가 오늘 갑자기 느려졌다"는 장애의 상당수가 이 bind peeking side effect에서 비롯된다.

```sql
VARIABLE b_status VARCHAR2(10);
EXEC :b_status := 'PENDING';
SELECT /*+ GATHER_PLAN_STATISTICS */ COUNT(*), SUM(amount)
FROM orders_hist WHERE status = :b_status;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL,NULL,'ALLSTATS LAST'));
-- 인덱스 레인지 스캔으로 계획 고정 (Peeked Binds에 'PENDING' 확인 가능)

EXEC :b_status := 'DONE';
SELECT /*+ GATHER_PLAN_STATISTICS */ COUNT(*), SUM(amount)
FROM orders_hist WHERE status = :b_status;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL,NULL,'ALLSTATS LAST'));
-- 동일 인덱스 스캔 재사용 -> E-Rows/A-Rows 괴리 급증
```

`ALLSTATS LAST`의 `E-Rows`(추정)와 `A-Rows`(실제)가 수십~수백 배 벌어지는 것이 peeking 부작용의 1차 진단 신호다. 근본 원인은 `ALTER SESSION SET EVENTS '10053 trace name context forever, level 1'`로 트레이스를 켠 뒤 확인한다. `Peeked values` 섹션에 실제 바인딩 값이, `SINGLE TABLE ACCESS PATH`에 히스토그램 반영 selectivity 계산이 남는다. 오버헤드가 크므로 운영계에서는 문제 세션·SQL_ID에 한해 `DBMS_MONITOR`나 `oradebug`로 targeted하게 켜는 것이 실무적이다.

## 4. Adaptive Cursor Sharing의 bind-sensitive/bind-aware 상태 전이

Bind Peeking의 결함(SQL_ID당 계획 1개 고정)을 완화하려 11g에서 ACS가 도입됐다. ACS는 selectivity가 바인드 값에 민감한 predicate를 `bind-sensitive`로 표시하고, 실제 실행에서 성능 차이가 관측되면 `bind-aware`로 전환한다. bind-aware 커서는 하나의 SQL_ID 아래 여러 자식 커서를 유지하며 selectivity 군집(cluster)별로 적절한 자식 커서를 재사용한다.

전이는 이렇게 진행된다. 최초 파싱에서 `IS_BIND_SENSITIVE=YES`로 표시하되 계획은 아직 1개뿐이고, 실행마다 `V$SQL_CS_STATISTICS`에 selectivity가 누적 추적된다. 새 바인드 값의 selectivity가 기존 군집과 다르면 reparse가 일어나 자식 커서가 추가되고 `IS_BIND_AWARE=YES`로 바뀌며, 이후에는 값이 기존 군집과 맞으면 재사용, 다르면 다시 reparse된다.

| 상태 | V$SQL 표시 | 동작 | 자식 커서 수 |
| --- | --- | --- | --- |
| Peeking만(구버전 동작) | `IS_BIND_SENSITIVE=NO`,`IS_BIND_AWARE=NO` | 최초 계획 무조건 재사용 | 1개 고정 |
| bind-sensitive | `SENSITIVE=YES`,`AWARE=NO` | 계획 재사용 + 통계 관측 중 | 1개(증가 가능) |
| bind-aware | `SENSITIVE=YES`,`AWARE=YES` | 군집별 자식 커서 분기 | 군집 수만큼 다수 |

```sql
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware, executions, plan_hash_value
FROM v$sql WHERE sql_text LIKE 'SELECT COUNT(*), SUM(amount) FROM orders_hist%'
ORDER BY child_number;
```

각 자식 커서가 관측한 selectivity 구간은 `V$SQL_CS_SELECTIVITY(sql_id, child_number, predicate, low, high)`로 확인한다.

ACS의 실무 한계: 자식 커서 증가로 shared pool 압박과 mutex 경합이 생겨 "cursor explosion"이 발생할 수 있고, RAC에서는 인스턴스별 상태가 독립적이라 노드 간 불일치가 생길 수 있다. 그래서 운영계에서는 ACS만 믿기보다 Baseline으로 계획을 명시 고정하는 전략을 병행한다.

## 5. 실전 재현: Peeking 성능 저하 시나리오와 트레이스 분석 워크플로

시나리오: 새벽 배치가 `status='PENDING'`으로 최초 호출돼 인덱스 계획이 캐싱된 뒤, 낮 시간 대시보드가 같은 SQL_ID로 `status='DONE'`을 반복 조회하며 풀스캔이 필요한데도 인덱스 스캔을 강제당해 지연된다. 재현은 3절 `orders_hist`에 `CREATE INDEX ix_orders_hist_status ON orders_hist(status)`를 추가하고, `ALTER SYSTEM FLUSH SHARED_POOL`로 커서를 비운 뒤 `'PENDING'` → `'DONE'` 순으로 재실행하면 된다.

```sql
SELECT sql_id, child_number, plan_hash_value, executions, buffer_gets
FROM v$sql WHERE sql_text LIKE 'SELECT /*+ GATHER_PLAN_STATISTICS */ order_id%'
ORDER BY child_number;
```

`buffer_gets`가 DONE 실행에서 폭증했다면 8절의 AWR/ASH 쿼리로 시점을 좁힌다. 동일 `plan_hash_value`인데 `buffer_gets`만 급증했다면 bind peeking 부작용이다. `_optim_peek_user_binds=FALSE`는 전역 영향이 커 비권장이고, 히스토그램 재조정·컬럼 재설계는 근본적이지만 시간이 걸리므로, SQL Plan Baseline으로 안전한 계획을 즉시 고정하는 것이 가장 실용적이다(다음 절).

## 6. SQL Plan Management와 SQL Plan Baseline 생성·고정·진화

SPM(11g~)은 검증된(accepted) 실행계획 집합을 SQL Plan Baseline에 유지하고, 옵티마이저가 새 계획을 찾아도 baseline에 없으면 채택 못하게 한다. 여러 계획 중 최저 비용을 고르는 유연성과 급변(plan regression)을 막는 안전성을 동시에 제공한다.

```sql
-- (1) 힌트로 원하는(풀스캔) 계획을 강제 실행 후 그 계획을 baseline으로 로드
SELECT /*+ FULL(orders_hist) */ order_id FROM orders_hist WHERE status=:b_status;
SELECT DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(
  sql_id=>'&full_scan_sql_id', plan_hash_value=>&full_scan_plan_hash) FROM DUAL;

SELECT sql_handle, plan_name, enabled, accepted, fixed, origin
FROM dba_sql_plan_baselines WHERE sql_text LIKE '%orders_hist%';

-- (2) 특정 계획을 fixed로 승격(우선 채택 강제), evolve로 신규 계획 검증 후 승격
EXEC DBMS_SPM.ALTER_SQL_PLAN_BASELINE(sql_handle=>'&sql_handle', -
  plan_name=>'&plan_name', attribute_name=>'FIXED', attribute_value=>'YES');
SELECT DBMS_SPM.EVOLVE_SQL_PLAN_BASELINE(sql_handle=>'&sql_handle') FROM DUAL;
```

`EVOLVE_SQL_PLAN_BASELINE`은 unaccepted 계획을 재현 실행해 기존보다 개선됐을 때만 승격한다. 자동 검증 덕에 수동 판단 부담이 줄지만, 검증 기준이 단순 buffer_gets/elapsed_time 비교라 일회성 값이 전체 트래픽을 대표 못할 수 있다.

바인드 값별로 서로 다른 계획이 모두 타당하면(4절 시나리오) 하나의 SQL_HANDLE 아래 여러 accepted plan을 유지할 수 있어 SPM·ACS가 함께 작동한다. SPM은 "이 계획들 중에서만 고르라"는 울타리, ACS는 그 안에서 값에 맞게 고르는 역할이다.

## 7. SQL Profile vs SQL Plan Baseline vs Hint 비교와 적용 시점

- **Hint**: `/*+ ... */`로 access path·join order를 직접 지시. 즉각적이지만 코드 배포가 필요하고, 데이터 변화에 적응 못해 "박제된 최적화"가 될 위험이 있다.
- **SQL Profile**: `DBMS_SQLTUNE.ACCEPT_SQL_PROFILE`로 생성. SQL 텍스트는 그대로 두고 카디널리티 추정치를 보정한다. 히스토그램 부재·컬럼 상관관계 문제에 효과적이며, 리터럴이 바뀌는 SQL엔 `FORCE_MATCH` 옵션이 필요하다.
- **SQL Plan Baseline**: 허용된 계획 집합을 유지해 그 밖의 계획을 배제한다. 코드 변경이 불필요하고, 새 계획은 unaccepted로만 추가돼 검증 후 evolve할 수 있다는 점이 최대 장점이다.

| 구분 | Hint | SQL Profile | SQL Plan Baseline |
| --- | --- | --- | --- |
| 적용 대상 | SQL 텍스트(코드 변경 필요) | SQL_ID/FORCE_MATCHING SIGNATURE | SQL_HANDLE(텍스트 정규화 매칭) |
| 개입 방식 | access path/join 직접 지시 | cardinality 보정 계수 주입 | 허용 계획 집합으로 제한 |
| 코드 배포 필요 | 필요 | 불필요 | 불필요 |
| 통계 변화 적응성 | 낮음(고정) | 중간(보정치 재계산 필요) | 높음(evolve 재검증) |
| 신규 계획 자동 검증 | 없음 | 없음 | 있음(EVOLVE) |
| 주 사용 시점 | 긴급 핫픽스 | 카디널리티 왜곡 교정 | 계획 장기 안정화 |

실무 기준: 급한 장애면 힌트가 빠르지만 임시 조치로만 쓰고 원인 해결 후 제거해야 한다. 조인 컬럼 상관관계나 subquery cardinality 오류가 원인이면 SQL Profile이 근본적이다. 잘 도는 계획을 통계·버전 변화로부터 보호하려면 Baseline이 적합하며, 업그레이드 시 `OPTIMIZER_FEATURES_ENABLE`을 낮추는 대신 baseline으로 계획을 유지하며 점진 evolve하는 것이 공식 권장 패턴이다.

## 8. 운영 환경에서의 모니터링과 실행계획 변경 알림 체계

계획 고정만큼 중요한 것이 "언제, 왜 바뀌었는지" 추적하는 체계다. AWR은 `dba_hist_sqlstat`에 SQL별 실행 통계와 계획 해시를 스냅샷 단위로 남긴다.

```sql
-- 최근 스냅샷 구간 내 동일 SQL_ID에서 plan_hash_value가 바뀐 이력 탐지
SELECT sql_id, COUNT(DISTINCT plan_hash_value) AS distinct_plans
FROM dba_hist_sqlstat
WHERE snap_id > (SELECT MAX(snap_id)-96 FROM dba_hist_snapshot)
GROUP BY sql_id HAVING COUNT(DISTINCT plan_hash_value) > 1
ORDER BY distinct_plans DESC;
```

ASH는 초 단위 샘플링으로 대기 이벤트를 남겨, 응답 지연 시점의 `sql_plan_hash_value`와 대기 이벤트(`db file sequential read` 폭증 → 인덱스 쏠림 의심)를 교차 확인하는 데 쓴다. Diagnostics Pack 라이선스가 없다면 `V$SQL`/`V$SQLSTATS`를 배치로 적재해 자체 이력을 구축한다.

알림 체계는 세 축이다. `DBA_SQL_PLAN_BASELINES`의 신규 unaccepted plan을 매일 점검해 evolve 여부를 판단하고, `dba_hist_sqlstat`로 plan_hash_value가 갑자기 바뀐 이벤트를 탐지하되 baseline 없는 고빈도 SQL을 우선하며, `V$SQL`의 bind 플래그와 자식 커서 수로 비정상 SQL_ID(예: 50개↑)를 cursor explosion 후보로 식별한다. 이를 대시보드로 엮으면 "히스토그램 왜곡 → peeking 부작용 → ACS 미전이/cursor 폭증 → 계획 불안정"의 사슬을 baseline 적용 전후로 비교하며 운영할 수 있다. 모니터링 없이 baseline만 걸어두면 분포 변화 시 옛 계획이 강제돼 오히려 성능 저하를 유발할 수 있다.

## 참고

- Oracle Database Performance Tuning Guide ("The Query Optimizer", "Managing Optimizer Statistics", "Using Plan Stability" 챕터)
- Oracle Database SQL Tuning Guide ("Optimizer Statistics Concepts", "SQL Plan Management" 챕터)
- Oracle Database Reference (`DBA_SQL_PLAN_BASELINES`, `V$SQL`, `V$SQL_CS_STATISTICS` 뷰 정의)
- Oracle Database PL/SQL Packages and Types Reference (`DBMS_STATS`, `DBMS_SPM`, `DBMS_SQLTUNE` 패키지)
- Jonathan Lewis, *Cost-Based Oracle Fundamentals* (Apress)
- Christian Antognini, *Troubleshooting Oracle Performance* (Apress)