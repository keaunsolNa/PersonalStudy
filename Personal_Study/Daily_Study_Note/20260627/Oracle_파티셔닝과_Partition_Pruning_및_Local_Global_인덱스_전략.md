Notion 원본: https://app.notion.com/p/38c5a06fd6d3819e8258c1dc824cded0

# Oracle 파티셔닝과 Partition Pruning 및 Local/Global 인덱스 전략

> 2026-06-27 신규 주제 · 확장 대상: Oracle DB

## 학습 목표

- Range/List/Hash/Composite 파티셔닝의 분할 기준과 사용처를 구분한다.
- 파티션 프루닝(static/dynamic)이 스캔 범위를 줄이는 원리를 읽는다.
- Local·Global 인덱스의 유지보수·가용성 트레이드오프를 판단한다.
- Interval 파티셔닝과 파티션 교환(EXCHANGE)으로 운영 부담을 줄인다.

## 1. 파티셔닝의 목적

하나의 논리 테이블을 여러 물리 세그먼트로 나눠 저장한다. 목적은 성능(프루닝으로 I/O 감소), 관리성(오래된 파티션 DROP/TRUNCATE), 가용성(파티션별 독립성)이다.

## 2. 파티셔닝 유형

```sql
CREATE TABLE sales (sale_id NUMBER, sale_date DATE, amount NUMBER)
PARTITION BY RANGE (sale_date) (
  PARTITION p2024 VALUES LESS THAN (DATE '2025-01-01'),
  PARTITION p2025 VALUES LESS THAN (DATE '2026-01-01'),
  PARTITION pmax  VALUES LESS THAN (MAXVALUE));
```

List는 이산 값 집합, Hash는 해시로 균등 분산, Composite은 Range→Hash 두 단계다.

## 3. Interval 파티셔닝

```sql
CREATE TABLE events (event_id NUMBER, event_ts DATE, payload VARCHAR2(200))
PARTITION BY RANGE (event_ts) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
(PARTITION p_first VALUES LESS THAN (DATE '2026-01-01'));
```

경계를 넘는 행이 처음 들어올 때 파티션을 자동 생성한다.

## 4. 파티션 프루닝

리터럴은 static, 바인드·서브쿼리는 dynamic 프루닝이다. 실행계획의 PSTART/PSTOP이 핵심이다. **프루닝이 깨지는 가장 흔한 원인은 파티션 키에 함수를 씨우는 것**이다. `WHERE TRUNC(sale_date) = ...`처럼 키를 가공하면 전체 스캔으로 떨어진다.

## 5. Local vs Global 인덱스

| 항목 | Local | Global |
| --- | --- | --- |
| 파티션 정렬 | 키 기준 동일 | 무관 |
| DDL 영향 | 해당 파티션만 | 전체 무효화 |
| 프루닝 친화 | 키 쿼리 강함 | 비키 단건 강함 |
| 운영 부담 | 낮음 | 높음(UPDATE INDEXES) |

```sql
CREATE INDEX idx_sales_amount ON sales(amount) LOCAL;
ALTER TABLE sales DROP PARTITION p2024 UPDATE INDEXES;
```

## 6. 파티션 교환(EXCHANGE)

```sql
ALTER TABLE sales EXCHANGE PARTITION p2025 WITH TABLE sales_stage_2025
  INCLUDING INDEXES WITHOUT VALIDATION;
```

메타데이터만 맞바꾸므로 거의 즉시 완료되는 ETL 표준 패턴이다.

## 7. 운영 체크리스트

파티션 키는 가장 흔한 쿼리의 WHERE 절과 정렬해야 한다. 오래된 데이터는 DELETE 대신 파티션 DROP/TRUNCATE로 처리해 UNDO·REDO 부담을 없앨다.

## 8. Partition-Wise Join

조인 키로 동일하게 파티셔닝되면 같은 파티션끼리만 조인(full partition-wise)해 데이터 재분배를 없앨다.

```sql
SELECT /*+ PARALLEL(4) */ o.order_id, c.name
FROM orders o JOIN customers c ON o.cust_id = c.cust_id;
```

## 9. 통계 — Incremental Statistics

```sql
BEGIN
  DBMS_STATS.SET_TABLE_PREFS('SALES_OWNER', 'SALES', 'INCREMENTAL', 'TRUE');
  DBMS_STATS.GATHER_TABLE_STATS('SALES_OWNER', 'SALES', GRANULARITY => 'AUTO');
END;
/
```

변경된 파티션 통계만 새로 모으고 글로벌 통계는 시녈시스를 병합해 전체 스캔 없이 갱신한다.

## 10. 안티패턴

작은 테이블·수천 파티션 폭증은 오버헤드만 늘린다. 키는 불변에 가깝고 가장 흔한 필터·조인 컴럼을 택한다.

## 참고

- Oracle Database VLDB and Partitioning Guide(19c/23ai)
- Oracle SQL Language Reference — CREATE TABLE PARTITION BY, EXCHANGE PARTITION
- Oracle Database SQL Tuning Guide — Reading Execution Plans, PSTART/PSTOP
- DBMS_STATS — Incremental Statistics
