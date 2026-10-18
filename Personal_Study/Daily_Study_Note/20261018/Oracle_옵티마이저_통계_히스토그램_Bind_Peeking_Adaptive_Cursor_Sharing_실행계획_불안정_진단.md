Notion 원본: https://app.notion.com/p/3fd5a06fd6d38176b0c7d6b5249ac3f1?pvs=204

# Oracle 옵티마이저 통계와 히스토그램, Bind Peeking 및 Adaptive Cursor Sharing으로 보는 실행계획 불안정 진단

> 2026-10-18 신규 주제 · 확장 대상: Oracle

## 학습 목표

- 편향된 컬럼에서 히스토그램 유무가 카디널리티 추정을 어떻게 갈라놓는지 재현한다.
- Bind Peeking이 첫 하드 파싱 한 번으로 플랜을 고정하는 지점을 V$ 뷰로 추적한다.
- Adaptive Cursor Sharing의 bind-sensitive/bind-aware 전이 조건을 확인하고 자식 커서 분기를 읽는다.
- "어제까지 빨랐던 쿼리"의 원인을 통계 이력, 플랜 해시, 바인드 캡처로 좁히는 진단 순서를 세운다.

## 1. 실행계획이 흔들리는 세 개의 축

운영에서 "같은 SQL인데 갑자기 느려졌다"는 증상은 대부분 세 축 중 하나로 수렴한다. 첫째는 통계가 바뀐 경우(재수집, 데이터 급증, 통계 미갱신)이고, 둘째는 같은 통계 아래에서 바인드 값이 달라진 경우(Bind Peeking), 셋째는 커서가 무효화되어 재파싱되면서 다른 바인드가 peek된 경우다. 세 축은 독립이 아니다. 통계 재수집은 커서를 무효화하고, 무효화는 재파싱을 부르며, 재파싱은 그 순간 들어온 바인드로 플랜을 다시 고정한다. 그래서 "통계를 건드린 적이 없다"는 말은 "플랜이 안 바뀌었다"의 근거가 되지 못한다. 공유 풀 플러시나 메모리 압박만으로도 같은 효과가 난다.

Spring 환경에서는 JDBC `PreparedStatement`와 JPA가 항상 바인드 변수를 쓰므로 리터럴 SQL에 없던 "첫 값 의존성"이 구조적으로 생긴다. 커서 공유의 이득을 얻는 대가로 값별 최적화를 잃고, ACS는 그 손실을 부분적으로 되돌린다.

## 2. 통계가 추정에 쓰이는 방식

옵티마이저는 조건절의 선택도(selectivity)에 테이블 행 수를 곱해 카디널리티를 구한다. 히스토그램이 없으면 등치 조건의 선택도는 `1/NDV`로 단순화되는데, 이는 값이 균등 분포한다는 가정이다. 상태 컬럼처럼 `DONE 99%`, `ERROR 0.9%`, `HOLD 0.1%` 식으로 쏠린 컬럼에서는 이 가정이 곧바로 틀린다.

```sql
SELECT column_name, num_distinct, density, histogram, num_buckets, last_analyzed
FROM   user_tab_col_statistics
WHERE  table_name = 'ORDERS' AND column_name = 'STATUS';
```

위 질의에서 `HISTOGRAM`이 `NONE`이면 균등 가정이 쓰인다. 값은 `FREQUENCY`, `TOP-FREQUENCY`, `HYBRID`, `HEIGHT BALANCED`(11g 이전 방식, 12c 이후 신규 수집에서는 보통 나오지 않음)로 나오며, 어떤 종류가 선택되는지는 NDV와 버킷 수(기본 254) 관계, 그리고 수집 방식에 따라 다르다. 정확한 선택 규칙은 버전에 따라 다르므로 사용 중인 버전의 Database SQL Tuning Guide로 확인한다.

이 추정 차이가 플랜 선택을 어떻게 가르는지 감을 잡기 위해, 100만 행·3개 값 분포를 가정한 장난감 모델을 Python으로 돌렸다. 인덱스 비용은 행당 약 0.9블록(클러스터링 팩터가 나쁜 경우), 풀스캔 비용은 블록 수/멀티블록 읽기 단위로 단순화했으며 Oracle의 실제 CBO 비용식이 아니다.

```text
DONE   real= 990000 no-hist   est=   333333 idx= 300003.0 full=1250.0 -> FULL
DONE   real= 990000 freq-hist est=   990000 idx= 891003.0 full=1250.0 -> FULL
ERROR  real=   9000 no-hist   est=   333333 idx= 300003.0 full=1250.0 -> FULL
ERROR  real=   9000 freq-hist est=     9000 idx=   8103.0 full=1250.0 -> FULL
HOLD   real=   1000 no-hist   est=   333333 idx= 300003.0 full=1250.0 -> FULL
HOLD   real=   1000 freq-hist est=     1000 idx=    903.0 full=1250.0 -> INDEX
```

히스토그램이 없으면 어떤 값이든 33만 건으로 추정되어 전부 풀스캔이 되고, `HOLD`처럼 천 건뿐인 값도 인덱스를 못 탄다. 반대로 히스토그램이 있으면 `HOLD`는 인덱스를 선택한다. `ERROR`가 풀스캔으로 남는 것은 이 모델의 클러스터링 가정 때문이며, 실제로는 인덱스 클러스터링 팩터가 좋으면 뒤집힌다. 요점은 히스토그램이 "인덱스를 타게 만드는 도구"가 아니라 "값별 추정을 현실에 가깝게 만드는 도구"라는 것이다.

## 3. 히스토그램 수집과 유지의 함정

히스토그램은 `METHOD_OPT`로 제어한다. 기본값 `FOR ALL COLUMNS SIZE AUTO`는 `SYS.COL_USAGE$`에 기록된 컬럼 사용 이력(조건절에 등치/범위로 쓰인 컬럼)과 데이터 편향을 보고 히스토그램 필요 여부를 정한다. 따라서 신규 환경이나 워크로드가 아직 안 돌아간 환경에서는 히스토그램이 안 생기고, 운영에서 한동안 돈 뒤에야 생긴다. 스테이징에서 재현이 안 되는 이유가 이것인 경우가 많다.

```sql
-- 컬럼 사용 이력 확인: AUTO가 왜 히스토그램을 만들었는지/안 만들었는지
SELECT DBMS_STATS.REPORT_COL_USAGE(USER, 'ORDERS') FROM dual;

-- 특정 컬럼만 명시적으로 히스토그램 수집 (예시)
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(
    ownname          => USER,
    tabname          => 'ORDERS',
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
    method_opt       => 'FOR ALL COLUMNS SIZE 1 FOR COLUMNS STATUS SIZE 254',
    no_invalidate    => FALSE);
END;
/
```

실무 함정은 두 가지다. 첫째, 히스토그램이 있는 컬럼에 대한 바인드 조건은 ACS의 대상이 되고 Bind Peeking의 영향도 커진다. 즉 히스토그램을 추가하는 순간 플랜 불안정 위험이 함께 늘 수 있다. 둘째, 샘플링 비율이 낮으면 희귀 값이 샘플에서 누락되어 히스토그램에서 사라지고, 사라진 값은 평균 밀도로 추정되어 실제보다 과대 추정되는 경우가 있다. `AUTO_SAMPLE_SIZE`의 히스토그램 처리는 버전별로 다르므로 문서를 확인한다.

통계 수집 직후의 커서 무효화도 알아야 한다. `NO_INVALIDATE`를 기본값(`AUTO_INVALIDATE`)으로 두면 기존 커서가 즉시 무효화되지 않고 일정 시간 창 안에서 무작위 시점에 재파싱된다. 이 창의 기본 길이는 숨은 파라미터로 제어되며 버전에 따라 확인이 필요하다. 이 지연 때문에 "통계 수집 후 몇 시간 뒤에 갑자기 느려졌다"는 시차 증상이 생긴다.

## 4. Bind Peeking: 첫 하드 파싱이 플랜을 고정한다

하드 파싱 시 옵티마이저는 바인드 변수의 실제 값을 들여다보고(peek) 그 값을 리터럴처럼 취급해 플랜을 만든다. 이후 같은 커서를 재사용하는 모든 실행은, 바인드 값이 달라도 그 플랜을 그대로 쓴다. 값이 균등하면 문제없지만 편향 컬럼이면 아래처럼 갈린다.

```sql
VAR st VARCHAR2(10)
EXEC :st := 'HOLD'
SELECT /* peek_demo */ COUNT(*), MAX(amount) FROM orders WHERE status = :st;
-- 이 첫 실행에서 peek된 값 'HOLD'(천 건)로 INDEX RANGE SCAN 플랜 고정

EXEC :st := 'DONE'
SELECT /* peek_demo */ COUNT(*), MAX(amount) FROM orders WHERE status = :st;
-- 99% 행을 인덱스로 읽는 최악의 플랜이 그대로 재사용될 수 있다
```

peek된 값은 플랜 출력에 직접 나온다.

```sql
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(
  sql_id => '&sql_id', cursor_child_no => 0,
  format => 'TYPICAL +PEEKED_BINDS'));
```

출력의 `Peeked Binds (identified by position)` 블록에 `1 - :1 (VARCHAR2(30), CSID=873): 'HOLD'` 형태로 나오는 것이 기대 구조다(실제 숫자와 문자셋 ID는 환경별로 다름, 이 포맷은 예시 구조다). 이 값이 "느린 실행의 바인드"와 다르면 Bind Peeking 의심이 거의 확정된다. 느린 실행 쪽 바인드는 `V$SQL_BIND_CAPTURE`에서 본다. 다만 캡처는 주기적 샘플링(기본 약 15분 간격)이라 모든 실행이 기록되지는 않고, 장기 이력은 `DBA_HIST_SQLBIND`에 일부만 남는다.

```sql
SELECT child_number, name, position, datatype_string, value_string, last_captured
FROM   v$sql_bind_capture
WHERE  sql_id = '&sql_id'
ORDER  BY child_number, position;
```

여기서 `PEEKED_BINDS`와 `V$SQL_BIND_CAPTURE`의 비교가 핵심이다. 둘이 같은 값을 보이면 Bind Peeking이 아니라 통계 문제일 가능성이 높고, 다르면 첫 값 의존성을 의심한다.

## 5. Adaptive Cursor Sharing: 커서가 값 범위별로 갈라지는 과정

11g에서 도입된 ACS는 "한 커서 = 한 플랜" 제약을 느슨하게 만든다. 조건은 두 단계다. 먼저 바인드를 쓰는 조건절 컬럼에 히스토그램이 있거나 범위 조건이 있으면 커서가 `IS_BIND_SENSITIVE = 'Y'`로 표시된다. 이후 실행에서 행 수 통계가 크게 어긋나는 실행이 관측되면 다음 실행부터 `IS_BIND_AWARE = 'Y'`로 승격되어 값 범위(선택도 구간)별로 별도 자식 커서를 만든다. 승격 직전에는 한 번 더 하드 파싱이 필요하므로, 처음 한두 번은 나쁜 플랜이 그대로 실행되는 학습 구간이 있다.

```sql
SELECT child_number, plan_hash_value, executions, buffer_gets,
       is_bind_sensitive bs, is_bind_aware ba, is_shareable sh
FROM   v$sql
WHERE  sql_id = '&sql_id'
ORDER  BY child_number;
```

기대되는 전이 구조는 다음과 같다(실제 수치가 아니라 예시 구조).

```text
CHILD PLAN_HASH   BS BA SH
0     1111111111  Y  N  N     <- 최초 커서, bind-aware 승격 후 공유 불가
1     1111111111  Y  Y  Y     <- 선택도 구간 A용 (인덱스)
2     2222222222  Y  Y  Y     <- 선택도 구간 B용 (풀스캔)
```

`V$SQL_CS_SELECTIVITY`는 각 자식 커서가 담당하는 선택도 범위(`LOW`, `HIGH`)를 보여주고, `V$SQL_CS_HISTOGRAM`은 실행당 행 처리량의 버킷별 실행 횟수를 보여준다. 이 두 뷰로 "왜 커서가 갈라졌는가"와 "갈라진 뒤 각 구간이 어떻게 쓰이는가"를 읽는다.

ACS의 trade-off는 분명하다. 자식 커서가 늘면 파싱 비용과 공유 풀 사용량이 늘고, 같은 SQL이 값 범위마다 별도 플랜을 가지니 플랜 해시 기준 모니터링이 복잡해진다. 또한 선택도 구간이 겹치는 경계값에서는 커서가 과도하게 분기해 `V$SQL`에 수십 개 자식이 쌓이는 사례가 알려져 있다. 힌트 `/*+ BIND_AWARE */`로 처음부터 bind-aware로 시작하게 하거나 `NO_BIND_AWARE`로 끌 수 있는데, 전자는 학습 구간을 건너뛰는 용도로 쓸 만하다.

```sql
SELECT /*+ BIND_AWARE */ COUNT(*), MAX(amount)
FROM   orders WHERE status = :st;
```

ACS 외에도 12c의 Adaptive Plans와 Automatic Reoptimization이 플랜을 실행 사이에 바꿀 수 있으므로, 플랜 출력의 `Note` 섹션(`this is an adaptive plan`, `statistics feedback used for this statement`)을 확인한다.

## 6. 진단 순서: 증상에서 원인으로

권장 순서는 비용이 싼 것부터 좁히는 것이다. 1단계는 플랜이 정말 바뀌었는지 이력으로 확인한다. `DBA_HIST_SQLSTAT`에서 `plan_hash_value`별 실행당 지표를 비교하면 언제부터 어떤 플랜이 쓰였는지 보인다.

```sql
SELECT s.snap_id, TO_CHAR(sn.begin_interval_time,'MM-DD HH24:MI') t,
       s.plan_hash_value, s.executions_delta ex,
       ROUND(s.elapsed_time_delta/NULLIF(s.executions_delta,0)/1000) ms_per_exec,
       ROUND(s.buffer_gets_delta/NULLIF(s.executions_delta,0))        gets_per_exec
FROM   dba_hist_sqlstat s JOIN dba_hist_snapshot sn USING (snap_id, instance_number)
WHERE  s.sql_id = '&sql_id'
ORDER  BY s.snap_id;
```

2단계는 변화 시점 전후에 통계가 바뀌었는지 `DBA_TAB_STATS_HISTORY`(`stats_update_time`)와 `DBA_TAB_STATISTICS`로 확인한다. 같은 시점이면 통계가 원인 후보이고, 아니면 3단계로 가서 커서 무효화(DDL, 인덱스 변경, 공유 풀 플러시)와 Bind Peeking을 본다.

3단계에서는 느린 실행과 빠른 실행의 플랜을 `DBMS_XPLAN.DISPLAY_AWR`와 `DISPLAY_CURSOR`로 나란히 보고, `ALLSTATS LAST` 형식으로 `E-Rows`(추정)와 `A-Rows`(실제)를 비교한다. 이 비교는 `STATISTICS_LEVEL=ALL` 또는 `/*+ GATHER_PLAN_STATISTICS */` 힌트가 있어야 `A-Rows`가 채워진다.

```sql
SELECT /*+ GATHER_PLAN_STATISTICS */ COUNT(*) FROM orders WHERE status = :st;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST +PEEKED_BINDS'));
```

`E-Rows`와 `A-Rows`가 어느 단계에서 처음 크게 벌어지는지가 추정 오류의 진원지다. 가장 안쪽 테이블 접근 단계에서 벌어지면 단일 컬럼 통계/히스토그램 문제, 조인 단계에서 벌어지면 컬럼 간 상관(확장 통계 `DBMS_STATS.CREATE_EXTENDED_STATS`)이나 조인 선택도 문제로 분기한다.

## 7. 안정화 수단과 각각의 대가

수단은 "원인 제거"와 "결과 고정"으로 나뉜다. 원인 제거 쪽은 히스토그램 수집·보정, 확장 통계, 조건절 분리(편향 값 전용 쿼리)다. 특히 `status = 'HOLD'`처럼 업무적으로 희귀 값을 찾는 용도라면 애플리케이션에서 별도 SQL로 분리하거나 리터럴을 쓰는 편이 ACS보다 예측 가능하다. 리터럴은 값이 닫힌 열거형일 때만 쓴다.

결과 고정 쪽에서는 SQL Plan Management(SPM)가 표준이다. 검증된 플랜을 baseline으로 묶으면 통계가 바뀌어도 그 플랜만 허용되고, 옵티마이저가 새 플랜을 찾으면 `ENABLED=YES, ACCEPTED=NO`로 보관했다가 검증 후 승격한다. 대가는 baseline 자체가 노후화될 수 있다는 점이다. 데이터가 크게 변해도 오래된 플랜이 선택될 수 있으므로 정기 evolve가 필요하다.

통계 쪽 방어선은 `DBMS_STATS.LOCK_TABLE_STATS`(특정 테이블 통계 고정), 보류 통계(`PUBLISH=FALSE` 후 `GATHER_TABLE_STATS`, 세션에서 `OPTIMIZER_USE_PENDING_STATISTICS=TRUE`로 검증 후 `PUBLISH_PENDING_STATS`), 그리고 문제 발생 시 `RESTORE_TABLE_STATS(as_of_timestamp => ...)`로의 롤백이다.

## 8. 재현 환경과 운영 체크리스트

위 시나리오는 로컬 Oracle(예: 컨테이너 XE/Free)에서 재현하는 것이 안전하다. 편향 데이터 생성 후 통계 수집 방식에 따라 `V$SQL`의 BS/BA 변화를 관찰한다. 이 노트에서는 Oracle 인스턴스가 없어 SQL 출력은 실행해 검증하지 못했고, 이름이 붙은 "예시 구조"만 기재했다. 실측값은 직접 위 절차로 얻어야 한다. 반면 2절의 카디널리티 시뮬레이션은 실제로 실행한 결과다.

```sql
-- 재현용 데이터 (예시, 직접 실행해 검증할 것)
CREATE TABLE orders AS
SELECT level id,
       CASE WHEN level <= 1000 THEN 'HOLD'
            WHEN level <= 10000 THEN 'ERROR' ELSE 'DONE' END status,
       DBMS_RANDOM.VALUE(1,1000) amount
FROM   dual CONNECT BY level <= 1000000;
CREATE INDEX ix_orders_status ON orders(status);
```

운영 체크리스트로는 편향 컬럼의 히스토그램 유무를 정기 감사하고, 핵심 `sql_id`의 `plan_hash_value` 변화를 AWR에서 경보로 걸며, 통계 잡 전에 롤백 경로(`RESTORE_TABLE_STATS`, 보존 기간 내 이력)를 확보한다. SQL 주석에 호출 위치(`/* svc.OrderRepo.findByStatus */`)를 남겨 `sql_id`와 코드를 즉시 매핑하고, 배포·통계 잡·재기동 직후를 고위험 시점으로 본다. 플랜을 고정하면 데이터 변화에 적응할 능력도 함께 고정된다는 대가를 문서화하고 재검토 주기를 정한다.

## 참고

- Oracle Database SQL Tuning Guide: Histograms, Optimizer Statistics Concepts, Adaptive Query Optimization (해당 버전 문서)
- Oracle Database PL/SQL Packages and Types Reference: DBMS_STATS, DBMS_XPLAN, DBMS_SPM
- Oracle Database Reference: V$SQL, V$SQL_BIND_CAPTURE, V$SQL_CS_SELECTIVITY, V$SQL_CS_HISTOGRAM
- Jonathan Lewis, "Cost-Based Oracle Fundamentals" (Apress)
- Christian Antognini, "Troubleshooting Oracle Performance" (Apress)
