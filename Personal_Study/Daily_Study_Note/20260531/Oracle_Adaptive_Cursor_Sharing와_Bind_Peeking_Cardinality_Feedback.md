Notion 원본: https://www.notion.so/3715a06fd6d381aea687cd6c1c87ff81

# Oracle Adaptive Cursor Sharing, Bind Peeking, Cardinality Feedback의 실행계획 적응 메커니즘

> 2026-05-31 신규 주제 · 확장 대상: Oracle 기본 (Personal_Study/Oracle) / PostgreSQL Partitioning (20260528)

## 학습 목표

- Bind Peeking 이 11g 부터 default 동작이 된 배경과, 동일 SQL 의 첫 실행 시점이 plan 을 어떻게 결정짓는지 설명한다
- Adaptive Cursor Sharing(ACS) 의 bind sensitive → bind aware 전이 과정과 V$SQL 의 IS_BIND_SENSITIVE / IS_BIND_AWARE 열 의미를 식별한다
- Cardinality Feedback(11gR2+) 과 12c 이후의 Statistics Feedback 이 실행 후 실측 row 수를 어떻게 다음 실행에 반영하는지 추적한다
- ACS 가 의도와 다르게 plan 폭주(plan explosion) 를 일으키는 운영 사례를 진단하고 SQL_PLAN_BASELINE 으로 안정화한다

## 1. Bind Peeking

bind 변수(`WHERE col = :x`) 를 쓰면 같은 SQL 텍스트가 여러 값으로 호출되어도 shared pool 의 cursor 가 재사용된다. 10g 부터 bind peeking 도입. 11g 부터 default 이며 hidden parameter `_optim_peek_user_binds` 로만 끌 수 있다.

```sql
SELECT id FROM orders WHERE status = :status;
-- :status = 'RARE' (10건)  → INDEX RANGE SCAN
-- :status = 'COMMON' (5,000만건) → 같은 plan 재사용 → 재앙
```

bind peeking 은 첫 실행 값이 운명을 결정한다. cursor invalidate 전까지 plan 고정.

## 2. Adaptive Cursor Sharing — bind sensitive → bind aware

1. Bind sensitive — bind 값에 따라 plan 이 달라질 수 있다고 표시
2. Bind aware — 실측 row 수 편차 임계 초과 시 selectivity range 별 child cursor
3. Child cursor 전환 — bind 값이 어느 range 에 속하는지 보고 적합한 child cursor 선택

```sql
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware,
       executions, buffer_gets, rows_processed
FROM   v$sql WHERE sql_id = '&sql_id';

SELECT child_number, predicate, low, high
FROM   v$sql_cs_selectivity WHERE sql_id = '&sql_id';

SELECT child_number, bucket_id, count
FROM   v$sql_cs_histogram WHERE sql_id = '&sql_id';
```

`V$SQL_CS_HISTOGRAM` 은 (0, 1, 2+ rows) 3 버킷 분류. 한쪽 치우치면 bind-aware 전이 트리거.

## 3. selectivity range 사례

```
child_number | plan                                     | range
-------------|------------------------------------------|------
0 (legacy)   | INDEX RANGE SCAN (잘못된 plan)            | 첫 peek 잔재
1            | TABLE FULL + HASH GROUP BY                | 'A' (~1억)
2            | INDEX RANGE SCAN + TABLE ACCESS BY ROWID  | 'B' (~90만)
3            | INDEX UNIQUE SCAN                         | 'C' (~10만)
```

`BIND_EQUIV_FAILURE = 'Y'` 가 보이면 ACS 가 새 child 를 만든 표식.

## 4. Cardinality Feedback

11gR2 도입. 실행 직후 실측 row 수와 optimizer 예측 비교 → 다음 hard parse 시 보정.

```sql
SET AUTOTRACE ON
SELECT /* fb_test */ COUNT(*) FROM orders WHERE created > SYSDATE - 7;
-- Note: "cardinality feedback used for this statement"
```

12c 부터 Statistics Feedback 으로 명명 변경, SQL Plan Directive 와 결합돼 column group 단위 보정.

## 5. 부작용

- **plan 폭주** — child cursor 수십~수백 개, shared pool 압박
- **첫 실행 plan 잔존** — legacy child 0 잔재
- **batch job 첫 호출 위험** — 새벽 outlier 값으로 plan 고정

## 6. SQL Plan Baseline

```sql
EXEC DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(sql_id => 'abc123');

SELECT sql_handle, plan_name, enabled, accepted, fixed
FROM   dba_sql_plan_baselines WHERE sql_handle = '&sql_handle';

EXEC DBMS_SPM.ALTER_SQL_PLAN_BASELINE(
       sql_handle => '&h', plan_name => '&p',
       attribute_name => 'FIXED', attribute_value => 'YES');
```

baseline 이 있는 SQL 은 ACS 의 새 child cursor 도 baseline 외 plan 미사용. SLA SQL 의 가장 안전한 선택.

## 7. 진단 흐름

| 증상 | view | 컬럼 |
|---|---|---|
| plan 흔들림 | V$SQL | IS_BIND_SENSITIVE/AWARE |
| child cursor 많음 | V$SQL_SHARED_CURSOR | BIND_EQUIV_FAILURE |
| selectivity range | V$SQL_CS_SELECTIVITY | LOW/HIGH |
| 실행 분포 | V$SQL_CS_HISTOGRAM | BUCKET_ID, COUNT |
| feedback | DBMS_XPLAN | Note 섹션 |
| directive | DBA_SQL_PLAN_DIRECTIVES | TYPE, STATE |

```sql
SELECT * FROM TABLE(
  DBMS_XPLAN.DISPLAY_CURSOR('&sql_id', &child, 'ALLSTATS LAST +ADAPTIVE')
);
```

E-Rows vs A-Rows 비율 10배 이상이면 cardinality 추정 실패 — extended statistics 검토.

## 8. 19c+ Real-Time Statistics

```sql
ALTER SESSION SET "_optimizer_adaptive_cursor_sharing" = FALSE;
ALTER SESSION SET "_optimizer_use_feedback" = FALSE;
ALTER SESSION SET "_optimizer_gather_stats_on_load" = FALSE;
```

production 전역 변경 비권장. 특정 SQL 의 `/*+ OPT_PARAM(...) */` 힌트가 더 안전.

## 9. 운영 권고

| 목표 | 권고 |
|---|---|
| skew column cardinality | HEIGHT BALANCED / TOP FREQUENCY histogram |
| 핵심 SQL plan 고정 | SQL Plan Baseline + fixed=YES |
| 새벽 batch 안전 | 사전 hard parse + baseline capture |
| 진단 가시화 | V$SQL_CS_* + DBMS_XPLAN ALLSTATS LAST |
| 통계 stale 방지 | AUTO_TASKS, sampling level 5+ |

ACS 와 Cardinality Feedback 은 11g 이후 Oracle optimizer 가 정적 비용 기반에서 실측 적응형으로 옮겨가는 가장 큰 변화. 운영의 첫 단계는 bind-sensitive SQL 가시화, SLA 가 걸린 것만 baseline 으로 잠그기.

## 참고

- Oracle Database SQL Tuning Guide 19c — Adaptive Cursor Sharing
- Maria Colgan, "Adaptive Features in Oracle 12c" Oracle Optimizer Blog
- Jonathan Lewis, "Cost-Based Oracle Fundamentals"
- Oracle MOS Note 740052.1 / 1344670.1
