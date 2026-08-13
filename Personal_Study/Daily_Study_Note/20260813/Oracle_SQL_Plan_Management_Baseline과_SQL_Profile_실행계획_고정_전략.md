Notion 원본: https://app.notion.com/p/3bb5a06fd6d38127a944c21b5c700edf?pvs=204

# Oracle SQL Plan Management Baseline과 SQL Profile 실행계획 고정 전략

> 2026-08-13 신규 주제 · 확장 대상: Oracle, SQLD

## 학습 목표

- SQL Plan Baseline 의 ACCEPTED / ENABLED / FIXED 조합으로 계획 선택 순서를 추적한다.
- 자동 캡처와 커서 캐시·STS 수동 로드를 상황별로 나눠 베이스라인을 구축한다.
- SQL Profile, SQL Patch, Stored Outline 과 베이스라인의 역할·라이선스 경계를 구분한다.
- SYSAUX SMB 용량·보관 정책을 점검하고 업그레이드 회귀 방지 절차를 설계한다.

## 1. 계획 회귀 문제와 SPM 의 위치

옵티마이저는 통계, 바인드 값, 파라미터, 인덱스 구성이 조금만 달라져도 다른 계획을 만든다. 대부분은 개선이지만 극소수의 계획 회귀(plan regression)가 야간 배치를 몇 시간짜리로 만든다. SQL Plan Management 는 새 계획을 막는 장치가 아니라, 계획은 계속 만들되 검증 전에는 쓰지 않는 보수적 진화 프레임워크다. 베이스라인에 없는 계획은 SMB(SQL Management Base)에 UNACCEPTED 로 기록만 되고, 실행은 검증된 ACCEPTED 계획 중 비용 최저인 것으로 이뤄진다.

반드시 짚을 포인트는 라이선스다. SQL Plan Management(베이스라인 캡처·로드·표시)는 Enterprise Edition 기본 기능으로 관리 팩 없이 쓸 수 있다. 반면 SQL Profile 을 만드는 SQL Tuning Advisor 는 Diagnostics + Tuning Pack 이 필요하고, `DBMS_SPM.EVOLVE_SQL_PLAN_BASELINE` 도 내부적으로 이를 호출하므로 evolve 를 돌리는 순간 Tuning Pack 사용이 된다. 팩이 없다면 좋은 계획을 커서 캐시에서 직접 로드해 ACCEPTED 로 넣는 수동 경로만 쓰는 편이 안전하다.

```sql
-- SPM 관련 파라미터 상태: 진단의 출발점
SELECT name, value, isdefault
  FROM v$parameter
 WHERE name IN ('optimizer_capture_sql_plan_baselines',
                'optimizer_use_sql_plan_baselines',
                'cursor_sharing');
```

`optimizer_use_sql_plan_baselines` 는 기본이 TRUE 라 베이스라인이 하나라도 있으면 즉시 영향을 받고, `optimizer_capture_sql_plan_baselines` 는 기본이 FALSE 다. 기본 DB 는 "SPM 은 켜져 있으나 캡처는 꺼진" 상태다.

## 2. ACCEPTED · ENABLED · FIXED 상태 모델

베이스라인 하나(plan_name 단위)는 독립적인 플래그를 갖는다. `ENABLED` 는 후보에 넣을지, `ACCEPTED` 는 검증을 통과했는지, `FIXED` 는 우선권을 줄지, `AUTOPURGE` 는 미사용 시 자동 삭제 여부, `REPRODUCED` 는 마지막 검증 때 그 계획을 재현할 수 있었는지를 뜻한다.

| 플래그 | YES 의 의미 | NO 의 의미 | 운영 활용 |
| --- | --- | --- | --- |
| ENABLED | 옵티마이저 후보로 포함 | 존재하되 무시됨 | 롤백 없는 임시 비활성화 |
| ACCEPTED | 검증되어 사용 가능 | 기록만 된 후보 | evolve·수동 승인 대상 |
| FIXED | 우선 선택 | 비용 기반 경쟁 | 긴급 계획 고정 |
| AUTOPURGE | 미사용 시 삭제 | 영구 보관 | 장주기 SQL 보호 |
| REPRODUCED | 재현 가능 | 재현 불가 | 스키마 변경 후 점검 |

선택 규칙은 단순하다. FIXED 이면서 ACCEPTED·ENABLED 인 계획이 있으면 그 그룹 안에서만 비용을 비교하고, 없으면 ACCEPTED·ENABLED 전체에서 비용 최저를 고른다. FIXED 는 강력한 만큼 위험하다. 분포가 바뀌어 계획이 나빠져도 계속 쓰이고 evolve 로 더 좋은 계획을 승인해도 선택되지 않으니, 지혈용으로 쓰고 원인 해소 후 되돌리는 절차를 표준에 넣는다.

```sql
-- 특정 SQL 의 베이스라인 상태 전수 확인
SELECT sql_handle, plan_name, origin, enabled, accepted, fixed,
       reproduced, autopurge, optimizer_cost, last_executed
  FROM dba_sql_plan_baselines
 WHERE sql_text LIKE '%ORDER_SUMMARY%'
 ORDER BY sql_handle, accepted DESC, optimizer_cost;

-- 지혈용 FIXED 지정 (원복 시 attribute_value => 'NO')
DECLARE
    v_cnt PLS_INTEGER;
BEGIN
    v_cnt := DBMS_SPM.ALTER_SQL_PLAN_BASELINE(
                 sql_handle     => 'SQL_a1b2c3d4e5f60718',
                 plan_name      => 'SQL_PLAN_a1b2c3d4e5f6g_1a2b3c4d',
                 attribute_name => 'FIXED', attribute_value => 'YES');
    DBMS_OUTPUT.PUT_LINE('fixed count = ' || v_cnt);
END;
/
```

## 3. 자동 캡처와 수동 로드

자동 캡처는 `optimizer_capture_sql_plan_baselines = TRUE` 로 켠다. 같은 SQL 이 두 번 이상 실행되어 반복 가능한 SQL 로 판정되면 첫 계획이 등록되고 자동으로 ACCEPTED 를 받는다. 문제는 첫 계획이 늘 좋은 계획은 아니라는 점으로, 통계가 stale 한 시점에 켜면 나쁜 계획을 정답으로 굳힌다. 그래서 상시 ON 보다 잘 도는 구간에만 짧게 켜고 끄며, 캡처가 대상을 가리지 않아 애드혹 리터럴이 많으면 SMB 가 불어난다는 점도 함께 본다.

수동 로드는 훨씬 정밀하다. 커서 캐시에 좋은 계획이 살아 있으면 `LOAD_PLANS_FROM_CURSOR_CACHE` 로 `sql_id` 와 `plan_hash_value` 를 지정해 원하는 계획만 집고, 과거 AWR 이나 다른 환경의 계획이면 STS 를 거쳐 `LOAD_PLANS_FROM_SQLSET` 을 쓴다.

```sql
-- 커서 캐시의 특정 plan_hash_value 만 로드
DECLARE
    v_plans PLS_INTEGER;
BEGIN
    v_plans := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(
                   sql_id => '9xz1kq3vh8dxc', plan_hash_value => 2938471056,
                   fixed  => 'NO', enabled => 'YES');
    DBMS_OUTPUT.PUT_LINE('loaded = ' || v_plans);
END;
/
```

```sql
-- AWR 구간을 STS 로 옮긴 뒤 베이스라인으로 로드 (업그레이드 전 계획 보존)
DECLARE
    v_plans PLS_INTEGER;
BEGIN
    DBMS_SQLTUNE.CREATE_SQLSET(sqlset_name => 'STS_PRE_UPGRADE');
    DBMS_SQLTUNE.LOAD_SQLSET(
        sqlset_name     => 'STS_PRE_UPGRADE',
        populate_cursor => DBMS_SQLTUNE.SELECT_WORKLOAD_REPOSITORY(
                               begin_snap   => 41200,
                               end_snap     => 41260,
                               basic_filter => 'parsing_schema_name = ''APP_OWNER'''));

    v_plans := DBMS_SPM.LOAD_PLANS_FROM_SQLSET(
                   sqlset_name => 'STS_PRE_UPGRADE', fixed => 'NO', enabled => 'YES');
    DBMS_OUTPUT.PUT_LINE('baseline plans = ' || v_plans);
END;
/
```

로드 API 는 SPM 기능이지만 재료를 AWR·STS 로 만들면 Diagnostics/Tuning Pack 사용이 발생하므로, 팩이 없다면 커서 캐시 로드로 범위를 좁힌다.

## 4. 계획 진화(evolution)와 승인 절차

UNACCEPTED 계획이 쌓이면 기존보다 나은지 판정해야 한다. `DBMS_SPM.EVOLVE_SQL_PLAN_BASELINE` 은 후보를 실제 실행하거나 비용을 비교해 기존 ACCEPTED 보다 우수할 때만 승격시킨다. `verify => 'YES'` 는 검증 실행을 뜻하므로 DML 이 섞였거나 비용이 큰 SQL 은 검증 자체가 부담이다. `commit => 'NO'` 로 리포트를 먼저 읽고 승격하는 2단계 접근이 안전하다.

```sql
-- 1단계: 승격 없이 검증 리포트만 확인 (2단계는 commit => 'YES')
SET LONG 200000 LONGCHUNKSIZE 200000 PAGESIZE 0
SELECT DBMS_SPM.EVOLVE_SQL_PLAN_BASELINE(
           sql_handle => 'SQL_a1b2c3d4e5f60718',
           plan_name  => NULL,
           verify     => 'YES',
           commit     => 'NO') AS evolve_report
  FROM dual;
```

12c 이후에는 `SYS_AUTO_SPM_EVOLVE_TASK` 가 유지관리 윈도우에 돌며 UNACCEPTED 계획을 자동 검증·승격한다. 편리하지만 운영자가 모르는 사이 계획이 바뀌므로, 변경 통제가 엄격하면 `ACCEPT_PLANS` 를 FALSE 로 두고 승격은 사람이 결정한다.

```sql
-- 자동 evolve 태스크를 리포트 전용으로 전환하고 이력 점검
BEGIN
    DBMS_SPM.SET_EVOLVE_TASK_PARAMETER('SYS_AUTO_SPM_EVOLVE_TASK',
                                       'ACCEPT_PLANS', 'FALSE');
END;
/

SELECT task_name, execution_start, status
  FROM dba_advisor_executions
 WHERE task_name = 'SYS_AUTO_SPM_EVOLVE_TASK'
 ORDER BY execution_start DESC FETCH FIRST 10 ROWS ONLY;
```

## 5. SMB 공간 관리와 보관 정책

베이스라인, SQL Profile, SQL Patch 는 모두 SYSAUX 안의 SQL Management Base 에 저장된다. SMB 는 SYSAUX 크기의 일정 비율을 상한으로 두고 초과 시 경고를 남기며, 상한과 보관 기간은 `DBMS_SPM.CONFIGURE` 로 조정하고 `DBA_SQL_MANAGEMENT_CONFIG` 로 읽는다. 보관 기간 내내 쓰이지 않은 계획은 주간 퍼지로 삭제되는데, 분기 마감 배치처럼 주기가 긴 SQL 의 계획이 이에 걸려 사라지는 사고가 난다. 이런 계획은 `AUTOPURGE` 를 NO 로 바꿔 보호한다.

```sql
-- SMB 설정 확인·조정 후, 장주기 SQL 계획을 자동 퍼지에서 제외
SELECT parameter_name, parameter_value FROM dba_sql_management_config;

DECLARE
    v_cnt PLS_INTEGER;
BEGIN
    DBMS_SPM.CONFIGURE('space_budget_percent', 20);
    DBMS_SPM.CONFIGURE('plan_retention_weeks', 105);

    FOR r IN (SELECT sql_handle, plan_name
                FROM dba_sql_plan_baselines
               WHERE accepted = 'YES' AND autopurge = 'YES'
                 AND sql_text LIKE '%QUARTER_CLOSE%')
    LOOP
        v_cnt := DBMS_SPM.ALTER_SQL_PLAN_BASELINE(
                     sql_handle => r.sql_handle, plan_name => r.plan_name,
                     attribute_name => 'AUTOPURGE', attribute_value => 'NO');
    END LOOP;
END;
/
```

SMB 의 SYSAUX 잠식 여부는 `V$SYSAUX_OCCUPANTS` 의 `SQL_MANAGEMENT_BASE` 점유로 확인한다. 리터럴 SQL 이 많은 곳에서 캡처를 켜 두면 며칠 만에 수 GB 로 뛰므로 여유 공간 모니터링을 함께 건다.

## 6. SQL Profile · SQL Patch · Stored Outline 과의 경계

세 기능 모두 실행계획에 개입하지만 지점이 다르다. SQL Profile 은 계획을 고정하지 않는다. 잘못 추정된 카디널리티에 보정 계수를 주는 통계 보조 정보라, 프로파일이 붙어도 통계나 인덱스가 바뀌면 계획은 달라진다. 베이스라인은 반대로 계획 자체(outline hints 집합)를 저장해 재현을 시도한다. SQL Patch 는 힌트를 주입하는 경량 도구로 소스를 고칠 수 없는 패키지 애플리케이션에 유용하다. Stored Outline 은 deprecated 되었고 `DBMS_SPM.MIGRATE_STORED_OUTLINE` 을 통한 SPM 이관이 공식 권고다.

| 기능 | 개입 방식 | 계획 고정력 | 라이선스 |
| --- | --- | --- | --- |
| SQL Plan Baseline | 검증된 계획 목록 유지 | 강함(ACCEPTED 범위 내) | EE 기본 |
| SQL Profile | 카디널리티 보정 | 없음(유도만) | Tuning Pack 필요 |
| SQL Patch | 힌트 주입 | 힌트 강도에 의존 | EE 기본 |
| Stored Outline | 힌트 집합 저장 | 강함 | 퇴역, 신규 사용 금지 |

둘은 배타적이지 않다. 프로파일이 카디널리티를 바로잡아 더 나은 계획이 나오면 그 계획은 UNACCEPTED 로 들어오고 evolve 를 거쳐 승격된다. 프로파일은 좋은 계획이 나올 확률을 높이고, 베이스라인은 나온 계획을 지킨다.

```sql
-- SQL Patch 로 애플리케이션 수정 없이 힌트 주입
BEGIN
    DBMS_SQLDIAG.CREATE_SQL_PATCH(
        sql_id    => '9xz1kq3vh8dxc',
        hint_text => 'INDEX(o IX_ORDERS_CUST_DT)',
        name      => 'PATCH_ORDERS_IDX');
END;
/

-- 적용 여부는 실행계획 Note 섹션에서 확인
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST +NOTE'));
```

## 7. 업그레이드 회귀 방지 워크플로와 진단 쿼리

DB 업그레이드는 옵티마이저 코드가 통째로 바뀌므로 회귀 위험이 가장 크다. 업그레이드 전에 잘 도는 계획을 커서 캐시나 STS 에서 베이스라인으로 확보하고, 직후에는 캡처를 끈 채 기존 베이스라인만 쓰게 해 현행 성능을 유지하며, 안정화 후 evolve 로 새 계획을 하나씩 승격한다. 핵심은 업그레이드와 계획 변경을 같은 날에 겪지 않는 것이다.

계획이 베이스라인을 타는지는 실행계획 Note 의 `SQL plan baseline ... used for this statement` 와 `V$SQL.SQL_PLAN_BASELINE` 값으로 본다.

```sql
-- 베이스라인이 실제로 적용된 커서 식별
SELECT sql_id, child_number, plan_hash_value, sql_plan_baseline, executions,
       ROUND(elapsed_time / GREATEST(executions, 1) / 1000, 1) AS avg_ms
  FROM v$sql
 WHERE sql_plan_baseline IS NOT NULL
 ORDER BY elapsed_time DESC FETCH FIRST 20 ROWS ONLY;

-- 저장된 계획 원문 출력과 승격 대기 후보 조회
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_SQL_PLAN_BASELINE(
                        sql_handle => 'SQL_a1b2c3d4e5f60718',
                        plan_name  => NULL, format => 'TYPICAL +OUTLINE'));

SELECT sql_handle, plan_name, origin, optimizer_cost, created, reproduced
  FROM dba_sql_plan_baselines
 WHERE accepted = 'NO' AND enabled = 'YES' AND created > SYSDATE - 14
 ORDER BY sql_handle, optimizer_cost;
```

`REPRODUCED = 'NO'` 인 ACCEPTED 계획은 경고 신호다. 인덱스 삭제나 파티션 변경으로 그 계획을 더 이상 만들 수 없다는 뜻이며, 옵티마이저는 다른 계획으로 조용히 넘어간다. 스키마 변경 릴리스 직후 점검 항목에 넣는다.

## 8. 바인드 피킹 · ACS · force_matching_signature

계획 고정은 바인드 피킹 및 Adaptive Cursor Sharing 과 충돌한다. ACS 는 바인드 값에 따라 다른 계획이 유리하면 bind-aware 커서를 여러 개 만드는데, 베이스라인은 이를 막지 않는다. ACS 가 만든 계획도 후보로 들어오고 ACCEPTED 가 여러 개면 실행 시점 바인드 값 기준 비용으로 고른다. 문제는 FIXED 다. 단일 계획을 못박으면 편향된 값에서 최악의 계획이 강제되어 회귀를 만드니, skew 가 큰 컬럼을 쓰는 SQL 은 좋은 계획 여러 개를 ACCEPTED 로 두는 편이 낫다.

또 하나의 난제는 리터럴 SQL 이다. 베이스라인은 정규화된 `signature` 로 매칭되므로 `cust_id = 1001` 과 `cust_id = 1002` 는 다른 SQL 로 취급되어 각각 별도 베이스라인이 필요하다. 리터럴이 쏟아지는 시스템에서 캡처가 SMB 를 폭증시키는 이유다. `force_matching_signature` 는 리터럴만 다른 SQL 을 하나로 묶어 주지만 베이스라인 자체는 force matching 으로 동작하지 않는다. 이를 지원하는 쪽은 SQL Profile(`force_match => TRUE`)이므로, 리터럴 문제는 바인드 변수 전환으로 풀고 임시 대응에만 프로파일을 쓴다.

```sql
-- 리터럴만 다른 SQL 군집 탐지: 베이스라인 폭증 위험 진단
SELECT force_matching_signature, COUNT(*) AS literal_variants,
       SUM(executions) AS total_execs, MIN(sql_id) AS sample_sql_id
  FROM v$sql
 WHERE force_matching_signature <> 0
 GROUP BY force_matching_signature
HAVING COUNT(*) > 20
 ORDER BY literal_variants DESC FETCH FIRST 15 ROWS ONLY;

-- 바인드 인식 커서와 베이스라인의 상호작용 점검
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware,
       plan_hash_value, sql_plan_baseline
  FROM v$sql
 WHERE sql_id = '9xz1kq3vh8dxc' ORDER BY child_number;
```

베이스라인은 변화를 금지하는 자물쇠가 아니라 변화를 승인 절차 뒤로 미루는 게이트다. 게이트만 세우고 evolve 를 돌보지 않으면 몇 년 전 계획이 화석처럼 남아 새 인덱스와 통계의 이점을 놓친다. 캡처 범위, evolve 주기와 승인 주체, SMB 임계와 보관 기간, FIXED 해제 조건을 문서로 정하는 것이 SPM 운영의 절반이다.

## 참고

- [Oracle Database SQL Tuning Guide - Managing SQL Plan Baselines](https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/managing-sql-plan-baselines.html)
- [Oracle Database SQL Tuning Guide - Overview of SQL Plan Management](https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/overview-of-sql-plan-management.html)
- [Oracle Database PL/SQL Packages and Types Reference - DBMS_SPM](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_SPM.html)
- [Oracle Database PL/SQL Packages and Types Reference - DBMS_SQLDIAG](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_SQLDIAG.html)
- [Oracle Optimizer Blog](https://blogs.oracle.com/optimizer/)
- [Oracle Database Licensing Information User Manual](https://docs.oracle.com/en/database/oracle/oracle-database/19/dblic/)
