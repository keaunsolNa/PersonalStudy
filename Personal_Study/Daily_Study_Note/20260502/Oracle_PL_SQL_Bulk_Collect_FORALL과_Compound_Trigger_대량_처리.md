Notion 원본: https://www.notion.so/3545a06fd6d3816f9b57cf037930d626

# Oracle PL/SQL Bulk Collect, FORALL과 Compound Trigger 대량 처리 최적화

> 2026-05-02 신규 주제 · 확장 대상: Oracle, SQLD

## 학습 목표

- Bulk Collect 의 LIMIT 사용으로 PGA 폭주를 막는 패턴을 익힌다
- FORALL 과 SAVE EXCEPTIONS 로 부분 실패를 복구한다
- Compound Trigger 를 활용해 mutating table 오류를 우회한다
- bulk binding context switch 비용 절감 효과를 실측 단위로 안다

## 1. 행 단위 처리의 컨텍스트 스위칭 비용

PL/SQL 엔진과 SQL 엔진은 별개의 실행 컨텍스트다. row-by-row LOOP INSERT 1만 건은 매 row 마다 두 엔진 사이를 왕복하며 매 호출당 ~50µs 의 오버헤드를 만든다. AWR 의 "PL/SQL execution elapsed time" 과 "DB CPU" 비율, "context switches" wait event 로 관측된다. Bulk binding 은 이 왕복을 한 번으로 압축해 동일 작업을 5~25 배 빠르게 만든다.

| 방식 | 1M rows INSERT 시간(예시) | redo bytes |
| --- | --- | --- |
| FOR LOOP INSERT | 280s | 1.1GB |
| BULK COLLECT + FORALL (LIMIT 1000) | 14s | 880MB |
| INSERT INTO ... SELECT (SQL only) | 6s | 760MB |

가능하면 SQL-only 가 가장 빠르지만, 행 단위 변환 로직이 필요하면 FORALL 이 차선책이다.

## 2. BULK COLLECT — LIMIT 의 의무성

`BULK COLLECT INTO` 는 cursor 결과를 collection 에 한 번에 채우는 절이다. 백만 건을 한 번에 수집하면 PGA 가 바로 GB 단위로 폭증한다. 운영에서는 항상 `LIMIT` 으로 chunk 단위 fetch 를 한다.

```sql
DECLARE
  CURSOR c IS SELECT order_id, total FROM orders WHERE status = 'NEW';
  TYPE t_rows IS TABLE OF c%ROWTYPE;
  v_rows t_rows;
  c_chunk CONSTANT PLS_INTEGER := 1000;
BEGIN
  OPEN c;
  LOOP
    FETCH c BULK COLLECT INTO v_rows LIMIT c_chunk;
    EXIT WHEN v_rows.COUNT = 0;

    FORALL i IN 1 .. v_rows.COUNT
      INSERT INTO orders_archive VALUES v_rows(i);
  END LOOP;
  CLOSE c;
  COMMIT;
END;
```

LIMIT 의 적정값은 row 폭에 반비례한다. 행 평균 200B 일 때 1000~5000, 평균 2KB 가 넘으면 200~500 이 안전선이다. PGA target 의 5 % 를 넘지 않도록 sizing 하는 것이 일반적이다.

## 3. FORALL 과 SAVE EXCEPTIONS — 부분 실패 복구

FORALL 은 한 collection 을 한 SQL 로 일괄 처리한다. 일부 row 가 unique constraint 위반 등으로 실패해도 전체 batch 가 abort 되는 동작을 `SAVE EXCEPTIONS` 절로 변경할 수 있다.

```sql
DECLARE
  TYPE t_ids IS TABLE OF orders.order_id%TYPE;
  v_ids t_ids := t_ids(101, 102, 103, 104);
  v_errs PLS_INTEGER;
BEGIN
  FORALL i IN 1 .. v_ids.COUNT SAVE EXCEPTIONS
    UPDATE orders SET status = 'PROCESSED' WHERE order_id = v_ids(i);

EXCEPTION
  WHEN OTHERS THEN
    v_errs := SQL%BULK_EXCEPTIONS.COUNT;
    FOR i IN 1 .. v_errs LOOP
      DBMS_OUTPUT.PUT_LINE(
        'idx='   || SQL%BULK_EXCEPTIONS(i).ERROR_INDEX ||
        ' code=' || SQL%BULK_EXCEPTIONS(i).ERROR_CODE
      );
    END LOOP;
    RAISE;
END;
```

`SQL%BULK_EXCEPTIONS` 는 PL/SQL 의 의사컬렉션으로, `ERROR_INDEX` (실패한 i) 와 `ERROR_CODE` (ORA-XXXXX, 양수) 를 보관한다. 실패 로그를 별도 테이블에 적재하고 성공분만 commit 하는 dead-letter 패턴에 흔히 사용된다.

## 4. RETURNING + BULK COLLECT — INSERT/UPDATE 결과 한꺼번에 받기

DML 후 affected row 의 컬럼을 그대로 받아오는 경우 row-by-row trigger 보다 RETURNING 절이 빠르다.

```sql
DECLARE
  TYPE t_ids IS TABLE OF orders.order_id%TYPE;
  v_ids t_ids;
BEGIN
  UPDATE orders SET status = 'CLOSED'
    WHERE created_at < TRUNC(SYSDATE) - 30
  RETURNING order_id BULK COLLECT INTO v_ids;

  FORALL i IN 1 .. v_ids.COUNT
    INSERT INTO closed_orders_audit (order_id, closed_at)
    VALUES (v_ids(i), SYSTIMESTAMP);
END;
```

`UPDATE ... RETURNING ... BULK COLLECT` 는 단일 SQL 호출로 affected row 의 컬럼들을 PL/SQL 컬렉션에 채운다. 관련 audit, denormalized table 동기화에 적합하다.

## 5. Compound Trigger — Mutating Table 오류 우회

`ORA-04091: table is mutating` 은 row trigger 안에서 그 trigger 의 base table 을 다시 SELECT 할 때 발생한다. 11g 이전에는 관계없는 패키지 변수와 statement-level after trigger 를 짜야 했다. Compound trigger 는 한 trigger 객체 안에 statement-level / row-level 섹션을 함께 두어 단일 PL/SQL 단위로 처리할 수 있게 한다.

```sql
CREATE OR REPLACE TRIGGER trg_emp_salary_audit
FOR INSERT OR UPDATE OF salary ON employees
COMPOUND TRIGGER

  TYPE t_payload IS TABLE OF employees%ROWTYPE INDEX BY PLS_INTEGER;
  g_buffer t_payload;
  g_idx    PLS_INTEGER := 0;

  AFTER EACH ROW IS
  BEGIN
    g_idx := g_idx + 1;
    g_buffer(g_idx).employee_id := :NEW.employee_id;
    g_buffer(g_idx).salary      := :NEW.salary;
  END AFTER EACH ROW;

  AFTER STATEMENT IS
  BEGIN
    FORALL i IN 1 .. g_buffer.COUNT
      INSERT INTO salary_audit (emp_id, new_salary, recorded_at)
      VALUES (g_buffer(i).employee_id, g_buffer(i).salary, SYSTIMESTAMP);
    g_buffer.DELETE;
  END AFTER STATEMENT;

END;
```

각 row event 에서는 buffering 만 하고, statement 종료 후 한 번에 INSERT 하므로 mutating 오류와 row-by-row 오버헤드를 동시에 해결한다. 단, autonomous transaction 과 결합하면 commit boundary 가 어긋나 트랜잭션 일관성을 깨뜨릴 수 있다.

## 6. INSERT ALL / Multi-Table Insert 대안

복잡한 split 적재가 필요하면 PL/SQL 보다 `INSERT ALL` 이 단순하고 빠르다.

```sql
INSERT ALL
  WHEN amount >= 10000 THEN INTO orders_high
  WHEN amount <  10000 THEN INTO orders_low
SELECT order_id, customer_id, amount FROM staging_orders;
```

batch 적재 후 결과 분기는 이 방식이 row-by-row 분기 트리거보다 redo 가 적고, parallel DML 이 자동으로 적용된다. PL/SQL 로직이 정말 필요한지 한 번 더 의심하는 편이 좋다.

## 7. Parallel DML 과 ENABLE PARALLEL DML

대용량 변환은 SQL 엔진의 병렬화를 함께 쓴다. ENABLE PARALLEL DML 후 hint 또는 alter session 으로 degree 를 조절한다.

```sql
ALTER SESSION ENABLE PARALLEL DML;
INSERT /*+ parallel(orders_archive 4) APPEND */ INTO orders_archive
SELECT /*+ parallel(orders 4) */ * FROM orders WHERE status = 'CLOSED';
COMMIT;
```

`APPEND` hint 는 direct-path insert 로 redo 를 줄인다. 단, archive 테이블이 다른 세션에서 동시에 읽히고 있다면 ORA-12838 또는 row lock 경합이 일어날 수 있어, 적재 시간대를 batch 시간으로 분리한다.

## 8. 운영 모니터링 포인트

운영에서는 다음 view 와 wait event 를 주기 관찰한다.

| 항목 | view / event | 의미 |
| --- | --- | --- |
| Bulk binding 비효율 | V$SESSTAT `bytes received via SQL*Net from client` | client 쪽 round-trip 잔존 |
| PGA 폭주 | V$PGASTAT `total PGA inuse` | LIMIT 누락 의심 |
| Redo 폭주 | V$SESSTAT `redo size` | APPEND 미사용 / 인덱스 과다 |
| Mutating | DBA_ERRORS / log table | trigger 설계 오류 |
| Parallel skew | V$PX_SESSION | partitioning 불균형 |

PL/SQL profiler (DBMS_HPROF) 를 함께 켜두면 어느 helper procedure 가 시간을 쓰는지 정확히 잡을 수 있다.

## 9. 함정과 모범 사례

`FORALL` 안에서는 SQL 외 PL/SQL 문 (IF, CALL) 을 쓸 수 없다. 분기가 필요하면 미리 collection 을 분리해 두 번 FORALL 하거나 `INDICES OF` / `VALUES OF` 절을 사용한다. 또한 implicit COMMIT 이 일어나는 DDL 을 batch 중간에 두면 redo flush 가 발생해 성능이 떨어진다 — 일괄 적재는 한 트랜잭션 안에서 끝낸다.

PL/SQL collection 은 type 을 SCHEMA 레벨에서 선언해 패키지 간 공유할 때 캐스팅 비용이 든다. dedicated package 안의 nested table 타입을 쓰는 편이 안정적이다. 마지막으로, `BULK COLLECT INTO collection.LIMIT 1` 은 cursor 가 단일 row 라도 컬렉션 0 인덱스를 만들지 않으니 NO_DATA_FOUND 를 직접 검사할 필요가 없다.

## 참고

- Oracle Database PL/SQL Language Reference — Bulk SQL <https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/plsql-collections-and-records.html>
- Steven Feuerstein — Oracle PL/SQL Programming (O'Reilly)
- AskTOM — Bulk processing best practices <https://asktom.oracle.com>
- Oracle Performance Tuning Guide — Database Resident Connection Pool 19c
- Oracle Doc — Compound Triggers <https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/plsql-triggers.html#GUID-D162D31D-7C48-4A24-B05A-A7FBA20C8A5C>
