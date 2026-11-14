Notion 원본: https://www.notion.so/4185a06fd6d381bca2fde1c15b06219a

# MySQL Index Condition Pushdown 과 커버링 인덱스, MRR: EXPLAIN Extra 해석과 복합 인덱스 컬럼 순서 설계

> 2026-11-14 신규 주제 · 확장 대상: MySQL(InnoDB 인덱스와 실행계획), Oracle(인덱스 스캔과 테이블 액세스)

## 학습 목표

- EXPLAIN Extra 값으로 조건이 평가되는 계층(스토리지 엔진 / 서버)을 판별한다
- ICP 적용 여부에 따른 행 읽기 횟수 차이를 `EXPLAIN ANALYZE` 와 `Handler_read_*` 로 계측한다
- 커버링 인덱스, ICP, MRR 이 각각 줄이는 비용을 구분한다
- 동등·범위·필터 조건의 역할에 맞춰 복합 인덱스 컬럼 순서를 정한다

## 1. InnoDB 클러스터드 인덱스와 세컨더리 인덱스

InnoDB 테이블은 PK 를 키로 하는 클러스터드 인덱스 그 자체이며, 리프 페이지에 행 전체가 들어 있다. 세컨더리 인덱스의 레코드는 인덱스 컬럼과 함께 그 행의 PK 컬럼 값을 가진다. 그래서 인덱스에 없는 컬럼이 필요하면 그 PK 값으로 클러스터드 인덱스를 다시 탐색해야 한다. 커버링은 이 두 번째 탐색을 없애고, ICP 는 줄이고, MRR 은 순서를 정돈한다.

Oracle 의 힙 테이블은 인덱스 엔트리가 ROWID 를 갖고, 실행계획의 `TABLE ACCESS BY INDEX ROWID` 단계가 그 주소의 블록을 직접 읽는다. B-tree 를 한 번 더 타지 않는 대신 인덱스에 PK 값이 자동으로 실리지 않는다. InnoDB 는 PK 가 길수록 모든 세컨더리 인덱스가 커지므로 PK 를 짧게 잡는 편이 유리하다.

실습 테이블은 30만 행, 고객 300명(고객당 1,000행), 쿠폰 코드 1,000종이며 `CRC32` 로 값을 만들어 재실행해도 같은 데이터가 나온다.

```sql
CREATE DATABASE icp_lab; USE icp_lab;
CREATE TABLE orders (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  customer_id INT          NOT NULL,
  order_date  DATE         NOT NULL,
  coupon_code VARCHAR(10)  NOT NULL,
  amount      INT          NOT NULL,
  memo        VARCHAR(64)  NOT NULL,
  pad         CHAR(200)    NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cust_date_coupon (customer_id, order_date, coupon_code)
) ENGINE=InnoDB;

SET SESSION cte_max_recursion_depth = 300000;
INSERT INTO orders (customer_id, order_date, coupon_code, amount, memo, pad)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300000)
SELECT (n % 300) + 1,
       DATE_ADD('2025-01-01', INTERVAL (CRC32(n) % 680) DAY),
       CONCAT('C', LPAD(CRC32(CONCAT('c', n)) % 1000, 4, '0')),
       CRC32(CONCAT('a', n)) % 100000,
       CONCAT('memo-', MD5(n)),
       REPEAT('x', 200)
FROM seq;
ANALYZE TABLE orders;
```

## 2. 서버 계층과 스토리지 엔진 계층의 조건 평가 분담

MySQL 은 옵티마이저·실행기가 있는 서버 계층과 InnoDB 같은 스토리지 엔진 계층으로 나뉘고, 서버는 핸들러 API 로 엔진에 행을 요청한다. WHERE 조건은 세 부류로 갈린다. 첫째는 인덱스 탐색 범위를 정하는 조건으로 EXPLAIN 의 `type`, `key_len`, `ref` 에 드러난다. 둘째는 범위를 좁히지 못하지만 인덱스 컬럼만으로 판정할 수 있는 조건, 셋째는 테이블 행을 읽어야 판정할 수 있는 조건이다.

ICP 가 없으면 둘째와 셋째를 모두 서버가 평가하므로, 엔진은 범위 안의 모든 인덱스 엔트리에 대해 행을 읽어 넘긴다. ICP 는 둘째 부류의 평가를 엔진으로 내려보내 행을 읽기 전에 탈락시킨다. Oracle 실행계획이 인덱스 단계의 `access` 와 `filter` 술어를 나눠 보여 주는 것과 같은 구분이다.

## 3. ICP 가 없을 때와 있을 때의 흐름

매뉴얼이 적은 흐름은 이렇다. ICP 가 없으면 (1) 인덱스 튜플을 읽고 그것으로 테이블 행 전체를 읽은 뒤 (2) 그 행에 WHERE 조건을 검사한다. ICP 가 있으면 (1) 인덱스 튜플만 읽고 (2) WHERE 중 인덱스 컬럼만으로 검사할 수 있는 부분을 검사해 불만족이면 다음 튜플로 넘어가며 (3) 만족할 때만 테이블 행을 읽고 (4) 나머지 조건을 검사한다.

다음 쿼리에서 `customer_id` 조건은 탐색 범위를 정하고, `coupon_code LIKE '%77%'` 는 인덱스 컬럼이지만 범위를 좁히지 못하며, `memo` 는 인덱스에 없다. 고객 1,000행 중 쿠폰 조건 만족은 19건, 최종 결과는 17건이다.

```sql
EXPLAIN ANALYZE
SELECT * FROM orders
WHERE customer_id = 42 AND coupon_code LIKE '%77%' AND memo LIKE '%a%';

-- EXPLAIN: type=ref, key_len=4, rows=1000, Extra=Using index condition; Using where
-- -> Filter: (orders.memo like '%a%')  (actual rows=17)
--     -> Index lookup on orders using idx_cust_date_coupon (customer_id=42),
--        with index condition: (orders.coupon_code like '%77%')  (actual rows=19)
SET optimizer_switch = 'index_condition_pushdown=off';
-- EXPLAIN: Extra 만 Using where 로 바뀜
-- -> Filter: ((orders.coupon_code like '%77%') and (orders.memo like '%a%'))  (actual rows=17)
--     -> Index lookup on orders using idx_cust_date_coupon (customer_id=42)  (actual rows=1000)
SET optimizer_switch = 'index_condition_pushdown=on';
```

엔진이 서버로 올려 보낸 행이 1,000건에서 19건으로 줄었고, 그만큼 클러스터드 인덱스 탐색이 생략된다. `rows=1000` 은 두 경우 모두 같아서 ICP 효과는 EXPLAIN 의 `rows` 로는 보이지 않는다. ICP 가 켜져 있어도 인덱스 엔트리 1,000개를 훑는 일은 그대로다. 줄어드는 것은 테이블 행 읽기다.

## 4. ICP 적용 조건과 제한

매뉴얼이 명시한 조건은 다음과 같다. ICP 는 테이블 행 전체를 읽어야 하는 `range`, `ref`, `eq_ref`, `ref_or_null` 액세스에 쓰인다. InnoDB 와 MyISAM(파티션 테이블 포함)에서 동작한다. InnoDB 에서는 세컨더리 인덱스에만 적용되는데, 클러스터드 인덱스는 이미 행 전체가 버퍼에 읽힌 상태라 I/O 가 줄지 않기 때문이다. 가상 생성 컬럼의 세컨더리 인덱스에는 지원되지 않는다. 서브쿼리를 참조하는 조건, 스토어드 함수 조건(엔진이 호출할 수 없다), triggered condition 은 내려보낼 수 없다.

일부를 실행해 보았다.

```sql
EXPLAIN SELECT * FROM orders WHERE id BETWEEN 1000 AND 2000 AND amount > 90000;
-- (a) type=range, key=PRIMARY, Extra=Using where

CREATE FUNCTION f_has77(s VARCHAR(10)) RETURNS INT DETERMINISTIC NO SQL RETURN s LIKE '%77%';
EXPLAIN SELECT * FROM orders WHERE customer_id = 42 AND f_has77(coupon_code) = 1;
-- (b) type=ref, key=idx_cust_date_coupon, Extra=Using where
```

(a)는 클러스터드 인덱스 접근이고, (b)는 같은 판정을 내장 `LIKE` 로 써서 ICP 가 걸린 3장의 쿼리와 대비된다. 스칼라 서브쿼리와 비교하는 조건도 `Using where` 로만 나왔다. ICP 는 기본으로 켜져 있고 `optimizer_switch` 의 `index_condition_pushdown` 플래그로 끄고 켠다.

## 5. EXPLAIN Extra 값의 정확한 의미

매뉴얼 "EXPLAIN Output Format" 의 정의를 기준으로 정리한다.

| Extra 값 | 의미 | 읽는 법 |
| --- | --- | --- |
| `Using index` | 인덱스 트리의 정보만으로 컬럼 값을 얻고 행을 읽는 추가 탐색을 하지 않는다 | 커버링이다. 테이블 행 접근이 없다 |
| `Using index condition` | 인덱스 튜플을 먼저 검사해 테이블 행을 읽을지 결정한다 | ICP 다. 통과한 엔트리만 행을 읽는다 |
| `Using where` | WHERE 조건으로 다음 테이블에 넘기거나 클라이언트에 보낼 행을 제한한다 | 서버 계층이 받은 행을 다시 거른다 |
| `Using MRR` | Multi-Range Read 전략으로 테이블을 읽는다 | 모은 키를 정렬해 행을 읽는다 |
| `Using filesort` | 정렬된 순서로 행을 얻기 위해 추가 패스를 수행한다 | 인덱스 순서로 정렬하지 못했다 |

`Using where` 는 인덱스를 못 탔다는 뜻이 아니다. 3장의 `Using index condition; Using where` 는 쿠폰 조건은 엔진이, memo 조건은 서버가 평가했다는 뜻이다.

```sql
EXPLAIN SELECT * FROM orders WHERE customer_id = 42 ORDER BY order_date LIMIT 10;
-- type=ref, Extra=NULL
EXPLAIN SELECT * FROM orders WHERE customer_id = 42 ORDER BY amount LIMIT 10;
-- type=ref, Extra=Using filesort
EXPLAIN SELECT * FROM orders
WHERE customer_id = 42 AND order_date >= '2026-01-01' ORDER BY coupon_code LIMIT 10;
-- type=range, key_len=7, Extra=Using index condition; Using filesort
```

첫 번째는 인덱스 순서로 정렬을 해결했다. 세 번째는 앞 컬럼이 범위 조건이면 뒤 컬럼의 순서를 정렬에 쓸 수 없음을 보여 준다.

## 6. 커버링 인덱스와 ICP 의 관계

세컨더리 인덱스 레코드에는 PK 값이 들어 있으므로 인덱스 정의에 `id` 를 적지 않아도 `id` 까지 커버된다.

```sql
EXPLAIN SELECT id FROM orders WHERE customer_id = 42;          -- Extra=Using index
EXPLAIN SELECT id, amount FROM orders WHERE customer_id = 42;  -- Extra=NULL

EXPLAIN ANALYZE
SELECT id, order_date, coupon_code FROM orders
WHERE customer_id = 42 AND coupon_code LIKE '%77%';
-- EXPLAIN: type=ref, key_len=4, Extra=Using where; Using index
-- -> Filter: (orders.coupon_code like '%77%')  (actual rows=19)
--     -> Covering index lookup on orders ... (customer_id=42)  (actual rows=1000)
```

커버링이 되면 `Using index condition` 대신 `Using where; Using index` 가 나온다. ICP 는 테이블 행 읽기를 미루는 최적화인데 커버링에서는 미룰 행 읽기가 없기 때문이다. 이때 쿠폰 조건은 서버 계층이 인덱스 엔트리 1,000건을 받아 평가한다.

trade-off 로, 커버링을 위해 컬럼을 추가할수록 인덱스가 커지고 쓰기 부담이 늘며 조회 컬럼이 하나만 늘어도 효과가 사라진다. 조회 컬럼이 적고 고정된 쿼리만 커버링으로 풀고, 나머지는 필터 컬럼만 인덱스에 두어 ICP 가 걸리게 한다.

## 7. 복합 인덱스 컬럼 순서: 동등 → 범위, 그리고 범위 뒤 컬럼

B-tree 는 선행 컬럼부터 정렬되어 있어, 범위 조건 컬럼 뒤의 컬럼은 스캔 구간을 사실상 좁히지 못한다. 기존 인덱스에 날짜 범위와 쿠폰 동등 조건을 함께 건다. 날짜 조건 만족은 478건, 최종 결과는 3건이다.

```sql
EXPLAIN ANALYZE
SELECT * FROM orders
WHERE customer_id = 42 AND order_date >= '2026-01-01' AND coupon_code = 'C0990';
-- EXPLAIN: type=range, key_len=49, rows=477, Extra=Using index condition
-- -> Index range scan ... over (customer_id = 42 AND '2026-01-01' <= order_date
--    AND 'C0990' <= coupon_code), with index condition: (세 조건 전부)  (actual rows=3)
-- ICP off: Extra=Using where, Index range scan (actual rows=477) 위에 Filter (actual rows=3)
```

첫째, `key_len=49`(4+3+42)로 세 컬럼이 모두 쓰인 것처럼 보이지만 `>=` 의 시작 지점 키에 쿠폰 값이 덧붙었을 뿐이고, 스캔은 날짜 범위 끝까지 가서 477개 엔트리를 본다. 조건을 `order_date > '2025-12-31'` 로 바꾸면(`FORCE INDEX` 로 같은 인덱스 지정) `key_len=7` 이 되었다. `key_len` 만으로 스캔 구간을 판단하면 틀린다. 둘째, 범위 뒤의 `coupon_code` 조건은 ICP 로 평가되어 테이블 행 읽기가 3건으로 줄었다.

동등 → 범위 순서의 인덱스를 추가하면 스캔 구간 자체가 줄어든다.

```sql
ALTER TABLE orders ADD KEY idx_cust_coupon_date (customer_id, coupon_code, order_date);
ANALYZE TABLE orders;
-- 같은 쿼리: type=range, key=idx_cust_coupon_date, key_len=49, rows=3, Extra=Using index condition
-- over (customer_id = 42 AND coupon_code = 'C0990' AND '2026-01-01' <= order_date)
```

`rows` 추정이 477 에서 3 으로 내려갔다. 동등 조건 컬럼을 앞에, 범위 조건 컬럼을 그 뒤에 두고, 범위를 좁히지 못하는 조건(`LIKE '%x%'` 등)의 컬럼은 맨 뒤에 붙여 ICP 대상이 되게 한다. 인덱스를 둘 다 유지하면 쓰기 비용이 늘므로 어느 쿼리가 더 잦은지를 놓고 고른다.

## 8. MRR(Multi-Range Read)과 옵티마이저 스위치·힌트

세컨더리 인덱스 range 스캔은 테이블 행을 PK 기준으로 무작위 순서로 읽는다. MRR 은 먼저 인덱스만 스캔해 키를 모으고, 정렬한 뒤 PK 순서로 행을 읽어, 테이블이 크고 캐시에 없을 때의 랜덤 디스크 접근을 줄인다. 커버링으로 끝나는 쿼리에는 쓰이지 않는다.

`optimizer_switch` 의 `mrr` 은 사용 여부를, `mrr_cost_based` 는 비용 기반으로 선택할지(on) 가능하면 항상 쓸지(off)를 정하며 기본값은 둘 다 on 이다. 힌트는 `MRR`, `NO_MRR`, `NO_ICP` 가 있고(테이블 또는 인덱스 단위) 문장 안의 힌트가 `optimizer_switch` 보다 우선한다. 3장 쿼리에 `/*+ NO_ICP(orders idx_cust_date_coupon) */` 를 주면 Extra 가 `Using where` 로 바뀌었다.

```sql
-- 기본 설정(mrr=on, mrr_cost_based=on): MRR 선택 안 됨
EXPLAIN SELECT * FROM orders
WHERE customer_id = 42 AND order_date BETWEEN '2026-01-01' AND '2026-03-31';
-- type=range, key_len=7, rows=129, Extra=Using index condition

-- 같은 쿼리에 SELECT /*+ MRR(orders idx_cust_date_coupon) */ 또는 mrr_cost_based=off:
-- Extra=Using index condition; Using MRR
```

같은 쿼리에 `LIMIT 5` 를 붙였을 때 MRR 없이는 `order_date` 순(id 98441, 193841, ...)으로, MRR 을 쓰면 PK 순(id 6641, 10841, ...)으로 행이 나왔다. `ORDER BY order_date` 를 붙이면 힌트를 줘도 `Using MRR` 이 사라졌다. `customer_id = 42` 만 있는 `ref` 액세스에도 힌트는 적용되지 않았고, `customer_id IN (42, 43)` 의 `range` 에는 적용되었다. 이 테이블은 버퍼 풀에 다 들어가므로 MRR 의 시간 이득은 측정하지 않았다.

## 9. ICP 효과 계측과 인덱스 설계 체크리스트

`EXPLAIN FORMAT=TREE` 는 `with index condition` 과 `Filter` 로 조건이 평가되는 계층을 보여 주고, `EXPLAIN ANALYZE` 는 실제 실행해 노드별 `actual rows` 를 붙인다. 세션 상태 변수 `Handler_read_*` 로도 확인한다.

```sql
FLUSH STATUS;
SELECT SUM(amount) FROM orders WHERE customer_id = 42 AND coupon_code LIKE '%77%' AND memo LIKE '%a%';
SHOW SESSION STATUS LIKE 'Handler_read%';
```

| 실행 조건 | Handler_read_key | Handler_read_next | Handler_read_rnd |
| --- | --- | --- | --- |
| 3장 쿼리, ICP on | 1 | 19 | 0 |
| 3장 쿼리, ICP off | 1 | 1000 | 0 |
| 6장 커버링 쿼리 | 1 | 1000 | 0 |
| 7장 쿼리(기존 인덱스), ICP on | 1 | 3 | 0 |
| 7장 쿼리(기존 인덱스), ICP off | 1 | 477 | 0 |
| 8장 쿼리, MRR 없음 | 1 | 129 | 0 |
| 8장 쿼리, MRR 힌트 | 130 | 129 | 129 |

ICP 가 켜지면 엔진 안에서 탈락한 인덱스 엔트리는 `Handler_read_next` 에 잡히지 않았다. 커버링 쿼리의 1000 은 인덱스 엔트리 읽기인데 숫자만으로는 구분되지 않으므로 Extra 와 함께 읽는다. 이 버전에서 MRR 사용 여부는 `FORMAT=TREE` 출력에는 표시되지 않았다.

설계할 때는 다음 순서로 점검한다.

1. WHERE 조건을 동등, 범위, 범위를 좁히지 못하는 필터로 분류해 인덱스 컬럼을 그 순서로 배치한다.
2. TREE 의 `over (...)` 로 실제 스캔 구간을 확인한다.
3. 필터 조건이 `with index condition` 에 들어갔는지 보고, `Filter` 에 남았다면 4장의 제한을 확인한다.
4. 조회 컬럼이 적은 쿼리는 PK 암묵 포함을 감안해 커버링 가능성을 본다.
5. ORDER BY 가 있으면 `Using filesort` 유무를 본다.
6. 판단은 `EXPLAIN ANALYZE` 의 `actual rows` 와 `Handler_read_*` 로 검증한다.

검증 환경: Ubuntu 24.04 컨테이너에 apt 로 설치한 MySQL 8.0.46-0ubuntu0.24.04.5(버퍼 풀 128MB, `optimizer_switch` 기본값)에서 이 문서의 SQL 을 실행했고, EXPLAIN 값과 `actual rows`, `Handler_read_*` 수치는 그 출력에서 옮겼다. ICP·MRR 적용 조건과 Extra 정의는 8.0 레퍼런스 매뉴얼과 대조했다. Oracle 관련 서술과 MySQL 8.4 이상의 동작은 실행하지 않았다.

## 참고

- MySQL 8.0 Reference Manual: Index Condition Pushdown Optimization: https://dev.mysql.com/doc/refman/8.0/en/index-condition-pushdown-optimization.html
- Multi-Range Read Optimization: https://dev.mysql.com/doc/refman/8.0/en/mrr-optimization.html
- EXPLAIN Output Format: https://dev.mysql.com/doc/refman/8.0/en/explain-output.html
- Optimizer Hints: https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html
- Clustered and Secondary Indexes: https://dev.mysql.com/doc/refman/8.0/en/innodb-index-types.html
- Oracle SQL Tuning Guide, Optimizer Access Paths: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/optimizer-access-paths.html
