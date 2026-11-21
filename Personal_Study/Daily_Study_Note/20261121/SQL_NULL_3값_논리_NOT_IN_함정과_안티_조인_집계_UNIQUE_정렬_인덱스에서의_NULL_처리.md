Notion 원본: https://www.notion.so/41f5a06fd6d38182add9eafb44d97008

# SQL NULL 과 3값 논리: NOT IN 함정과 안티 조인, 집계·UNIQUE·정렬·인덱스에서의 NULL 처리와 DBMS 별 차이

> 2026-11-21 신규 주제 · 확장 대상: SQLD(NULL 관련 함수·집계), Oracle(옵티마이저·인덱스)

## 학습 목표

- NULL 이 포함된 비교와 논리 연산의 결과를 진리표로 출력해 3값 논리를 확인한다
- `NOT IN` 서브쿼리에 NULL 이 섞였을 때 결과가 0 건이 되는 과정을 재현하고 `NOT EXISTS` 와 실행 계획을 비교한다
- 집계, GROUP BY, 조인, UNIQUE 제약이 NULL 을 각각 다르게 다루는 규칙을 실행 결과로 구분한다
- PostgreSQL 실행 결과를 기준으로 Oracle·MySQL 의 차이(빈 문자열, 정렬 위치, 인덱스 저장)를 문서 근거와 함께 정리한다

## 1. NULL 은 값이 아니라 "모름" 이고, 비교 결과도 "모름" 이 된다

SQL 의 논리값은 TRUE, FALSE, UNKNOWN 세 가지다. NULL 과의 비교는 UNKNOWN 을 돌려주고, UNKNOWN 은 불리언 컬럼으로 출력하면 NULL 로 보인다.

```sql
SELECT (NULL = NULL) AS eq, (NULL <> 1) AS ne, (NULL AND FALSE) AS and_f, (NULL AND TRUE) AS and_t,
       (NULL OR TRUE) AS or_t, (NULL OR FALSE) AS or_f, (NOT NULL::boolean) AS not_n,
       (NULL IS NOT DISTINCT FROM NULL) AS isnd;
```

```text
   eq   |   ne   | and_f | and_t  | or_t |  or_f  | not_n  | isnd 
--------+--------+-------+--------+------+--------+--------+------
 (null) | (null) | f     | (null) | t    | (null) | (null) | t
```

`NULL = NULL` 은 참이 아니다. 모르는 값 두 개가 같은지는 알 수 없다. AND 와 OR 는 "모르는 쪽이 무엇이든 결과가 정해지는가" 로 생각하면 맞는다. `NULL AND FALSE` 는 다른 쪽이 무엇이든 FALSE 이므로 FALSE, `NULL OR TRUE` 는 TRUE 다. 그 외에는 UNKNOWN 이 전파된다. `NOT UNKNOWN` 도 UNKNOWN 이라는 점이 뒤에 나오는 `NOT IN` 문제의 뿌리다.

이후 예제는 다음 테이블을 쓴다. 부서가 정해지지 않은 사원(`park`)과 부서 코드가 비어 있는 부서 행(`TBD`)이 하나씩 있다.

```sql
CREATE TABLE emp (emp_id int PRIMARY KEY, name text NOT NULL, dept_id int, bonus numeric, retire_dt date);
CREATE TABLE dept (dept_id int, name text);
INSERT INTO emp VALUES (1,'kim',10,100,NULL),(2,'lee',20,NULL,NULL),(3,'park',NULL,300,DATE '2026-06-30'),
                       (4,'choi',30,NULL,NULL),(5,'jung',10,0,NULL);
INSERT INTO dept VALUES (10,'HR'),(20,'DEV'),(NULL,'TBD');
```

## 2. WHERE 는 TRUE 만 통과시키고 CHECK 는 FALSE 만 막는다

```sql
SELECT count(*) FILTER (WHERE bonus > 50) AS gt, count(*) FILTER (WHERE NOT (bonus > 50)) AS not_gt,
       count(*) FILTER (WHERE bonus > 50 OR NOT (bonus > 50)) AS either, count(*) AS total FROM emp;
```

```text
 gt | not_gt | either | total 
----+--------+--------+-------
  2 |      1 |      3 |     5
```

`bonus > 50` 인 행이 2 건, 그 부정이 1 건이다. 합쳐도 3 건이고 전체는 5 건이다. `조건 OR NOT 조건` 이 항상 참이라는 2값 논리의 상식이 깨진다. `bonus` 가 NULL 인 두 행은 조건도 UNKNOWN, 부정도 UNKNOWN 이라 어느 쪽에도 들지 못한다. "조건에 맞는 건수 + 맞지 않는 건수 = 전체" 로 검산하는 리포트는 NULL 이 있으면 맞지 않는다.

제약 조건은 반대 방향이다.

```sql
CREATE TABLE pay (amt int CHECK (amt > 0));
INSERT INTO pay VALUES (NULL);
INSERT INTO pay VALUES (-1);
```

```text
INSERT 0 1
ERROR:  new row for relation "pay" violates check constraint "pay_amt_check"
DETAIL:  Failing row contains (-1).
```

`CHECK (amt > 0)` 은 NULL 을 막지 않는다. CHECK 는 결과가 FALSE 일 때만 거부하고 UNKNOWN 은 통과시킨다. "양수만 허용" 을 의도했다면 `NOT NULL` 을 함께 걸어야 한다.

## 3. NOT IN 서브쿼리에 NULL 이 하나라도 있으면 0 건이 된다

부서 테이블에 없는 부서 코드를 가진 사원을 찾는 쿼리를 세 가지로 썼다.

```sql
SELECT name FROM emp WHERE dept_id NOT IN (SELECT dept_id FROM dept);
SELECT name FROM emp e WHERE NOT EXISTS (SELECT 1 FROM dept d WHERE d.dept_id = e.dept_id) ORDER BY emp_id;
SELECT name FROM emp WHERE dept_id NOT IN (SELECT dept_id FROM dept WHERE dept_id IS NOT NULL) ORDER BY emp_id;
```

```text
 name 
------
(0 rows)

 name 
------
 park
 choi
(2 rows)

 name 
------
 choi
(1 row)
```

첫 쿼리는 0 건이다. `dept_id NOT IN (10, 20, NULL)` 은 `dept_id <> 10 AND dept_id <> 20 AND dept_id <> NULL` 로 풀리고, 마지막 항이 항상 UNKNOWN 이므로 전체가 TRUE 가 될 수 없다. 부서 30 인 `choi` 조차 나오지 않는다. 오류도 경고도 없이 빈 결과가 나오기 때문에, 서브쿼리 대상 컬럼에 NULL 이 한 건 들어온 날부터 화면이 비는 형태로 발견된다.

두 번째와 세 번째의 차이도 중요하다. `NOT EXISTS` 는 "일치하는 행이 없다" 를 묻기 때문에 `dept_id` 가 NULL 인 `park` 도 결과에 포함한다(일치하는 부서가 없으므로). 서브쿼리에서 NULL 을 걸러 낸 `NOT IN` 은 `park` 의 `NULL NOT IN (10, 20)` 이 UNKNOWN 이라 제외한다. 두 표현은 바깥쪽 컬럼이 NULL 인 행에서 결과가 갈린다. 어느 쪽이 맞는지는 요구사항이 정한다. "부서가 없는 사원도 미배정으로 본다" 면 `NOT EXISTS` 가 맞다.

## 4. 실행 계획도 달라진다

사원 20 만 건(10% 는 부서 NULL), 부서 4,000 건으로 같은 두 쿼리의 계획을 봤다.

```sql
EXPLAIN (COSTS OFF) SELECT count(*) FROM big_e e WHERE e.dept_id NOT IN (SELECT dept_id FROM big_d);
EXPLAIN (COSTS OFF) SELECT count(*) FROM big_e e WHERE NOT EXISTS (SELECT 1 FROM big_d d WHERE d.dept_id = e.dept_id);
```

```text
 Aggregate
   ->  Seq Scan on big_e e
         Filter: (NOT (hashed SubPlan 1))
         SubPlan 1
           ->  Seq Scan on big_d

 Aggregate
   ->  Hash Anti Join
         Hash Cond: (e.dept_id = d.dept_id)
         ->  Seq Scan on big_e e
         ->  Hash
               ->  Seq Scan on big_d d
```

PostgreSQL 16 은 `NOT EXISTS` 를 안티 조인으로 바꾸지만 `NOT IN` 은 바꾸지 않고 서브플랜 필터로 실행한다. 3절의 의미 차이 때문에 두 형태가 동치가 아니어서다. 서브쿼리 결과가 작으면 해시 서브플랜으로 처리되지만, 조인 순서 변경이나 다른 조인 방식 선택 같은 최적화 대상에서는 빠진다. 건수도 달랐다. `NOT IN` 은 36,000 건, `NOT EXISTS` 는 56,000 건이다. 차이 20,000 건이 부서가 NULL 인 사원이다. Oracle 은 11g 부터 NULL 을 고려한 안티 조인(실행 계획의 `HASH JOIN ANTI NA`)을 지원해 `NOT IN` 도 조인으로 처리하지만, 결과가 0 건이 되는 의미 자체는 같다.

## 5. 집계 함수는 NULL 을 건너뛴다

```sql
SELECT count(*) AS cnt_all, count(bonus) AS cnt_bonus, sum(bonus) AS sum_b, avg(bonus) AS avg_b,
       avg(coalesce(bonus, 0)) AS avg_b0, sum(bonus) / count(*) AS manual FROM emp;
SELECT sum(bonus) AS sum_empty, count(bonus) AS cnt_empty, max(bonus) AS max_empty FROM emp WHERE emp_id < 0;
SELECT 100 + NULL AS plus, 'a' || NULL AS concat, concat('a', NULL) AS concat_fn, coalesce(NULL, NULL, 3) AS coal,
       nullif(0, 0) AS nullif0, 10 / nullif(0, 0) AS safe_div;
```

```text
 cnt_all | cnt_bonus | sum_b |        avg_b         |       avg_b0        |       manual        
---------+-----------+-------+----------------------+---------------------+---------------------
       5 |         3 |   400 | 133.3333333333333333 | 80.0000000000000000 | 80.0000000000000000

 sum_empty | cnt_empty | max_empty 
-----------+-----------+-----------
    (null) |         0 |    (null)

  plus  | concat | concat_fn | coal | nullif0 | safe_div 
--------+--------+-----------+------+---------+----------
 (null) | (null) | a         |    3 |  (null) |   (null)
```

`count(*)` 는 행을 세고 `count(bonus)` 는 NULL 이 아닌 값을 센다. `avg(bonus)` 는 `sum / count(bonus)` 이므로 133.33 이고, "상여가 없는 사람은 0 원" 으로 보는 평균은 80 이다. 둘 다 틀린 값이 아니라 다른 질문의 답이다. 평균을 내는 쿼리에서는 NULL 을 "대상 아님" 으로 볼지 "0" 으로 볼지를 먼저 정해야 한다.

대상 행이 0 건일 때 `sum` 은 0 이 아니라 NULL 이다. 합계를 받아 산술에 쓰는 애플리케이션 코드에서 원시 타입으로 언박싱하다 예외가 나는 지점이 여기다. `coalesce(sum(bonus), 0)` 으로 받는다. 스칼라 연산은 집계와 달리 NULL 을 전파한다. `100 + NULL` 과 `'a' || NULL` 이 모두 NULL 이다. PostgreSQL 의 `concat()` 함수는 NULL 을 무시한다. `nullif(분모, 0)` 은 0 으로 나누기 오류를 NULL 결과로 바꾸는 관용구다.

## 6. GROUP BY 와 DISTINCT 는 NULL 끼리 묶고, 조인은 묶지 않는다

```sql
SELECT dept_id, count(*) FROM emp GROUP BY dept_id ORDER BY dept_id;
SELECT count(DISTINCT bonus) AS distinct_cnt, (SELECT count(*) FROM (SELECT DISTINCT bonus FROM emp) t) AS distinct_rows FROM emp;
SELECT e.name, d.name AS dept FROM emp e JOIN dept d ON d.dept_id = e.dept_id ORDER BY e.emp_id;
SELECT e.name, d.name AS dept FROM emp e JOIN dept d ON d.dept_id IS NOT DISTINCT FROM e.dept_id ORDER BY e.emp_id;
```

```text
 dept_id | count 
---------+-------
      10 |     2
      20 |     1
      30 |     1
  (null) |     1

 distinct_cnt | distinct_rows 
--------------+---------------
            3 |             4

 name | dept          name | dept 
------+------        ------+------
 kim  | HR            kim  | HR
 lee  | DEV           lee  | DEV
 jung | HR            park | TBD
                      jung | HR
```

비교 연산에서는 NULL 끼리 같지 않지만, 그룹핑·`DISTINCT`·`UNION` 에서는 NULL 끼리 같은 것으로 취급해 한 그룹에 넣는다. SQL 표준은 이를 "distinct 하지 않다" 는 별도 개념으로 정의한다. `count(DISTINCT bonus)` 는 3 인데 `SELECT DISTINCT bonus` 의 행 수는 4 다. 앞쪽은 집계 함수라 NULL 을 건너뛰고, 뒤쪽은 NULL 을 하나의 값처럼 한 행으로 남긴다. 조인의 `=` 는 비교 연산이므로 `park` 과 `TBD` 는 맺어지지 않는다(왼쪽 결과). NULL 끼리도 맺어야 하면 `IS NOT DISTINCT FROM` 을 쓴다(오른쪽 결과). 변경 감지 쿼리에서 `old.col <> new.col` 로 비교하면 NULL 에서 값으로, 값에서 NULL 로 바뀐 행을 놓치는데, 이것도 `IS DISTINCT FROM` 으로 해결한다.

## 7. UNIQUE 제약과 NULL

```sql
CREATE TABLE u1 (code text UNIQUE);
INSERT INTO u1 VALUES (NULL), (NULL), ('A');
CREATE TABLE u2 (code text, UNIQUE NULLS NOT DISTINCT (code));
INSERT INTO u2 VALUES (NULL);
INSERT INTO u2 VALUES (NULL);
CREATE TABLE u3 (a int, b int, UNIQUE (a, b));
INSERT INTO u3 VALUES (1, NULL), (1, NULL);
```

```text
INSERT 0 3
INSERT 0 1
ERROR:  duplicate key value violates unique constraint "u2_code_key"
DETAIL:  Key (code)=(null) already exists.
INSERT 0 2
```

UNIQUE 는 비교 기반이라 NULL 이 여러 건 들어간다(`u1`). PostgreSQL 15 부터 `NULLS NOT DISTINCT` 를 지정하면 NULL 도 한 건만 허용한다(`u2`). 복합 키에서 일부만 NULL 인 `(1, NULL)` 두 건도 PostgreSQL 에서는 통과한다(`u3`). "사번 + 종료일(NULL 이면 현재 유효)" 같은 이력 테이블에서 유효 행을 한 건으로 제한하려고 복합 UNIQUE 를 걸면 PostgreSQL 에서는 막히지 않는다. Oracle 은 이 지점이 다르다. Oracle Database Concepts 문서는 일부 컬럼만 NULL 인 복합 유일 키에서 NULL 이 아닌 컬럼 값이 같은 행을 중복으로 본다고 설명한다. 같은 DDL 이 DBMS 에 따라 다르게 동작하므로 이관 시 확인 대상이다.

## 8. 정렬 위치와 인덱스

```sql
SELECT string_agg(coalesce(bonus::text, 'NULL'), ' ' ORDER BY bonus) AS asc_default,
       string_agg(coalesce(bonus::text, 'NULL'), ' ' ORDER BY bonus DESC) AS desc_default,
       string_agg(coalesce(bonus::text, 'NULL'), ' ' ORDER BY bonus DESC NULLS LAST) AS desc_nulls_last FROM emp;
```

```text
     asc_default     |    desc_default     |   desc_nulls_last   
---------------------+---------------------+---------------------
 0 100 300 NULL NULL | NULL NULL 300 100 0 | 300 100 0 NULL NULL
```

PostgreSQL 은 NULL 을 가장 큰 값처럼 정렬한다. 오름차순에서 마지막, 내림차순에서 처음이다. "상여 높은 순" 목록의 맨 위에 NULL 이 올라오므로 `NULLS LAST` 를 붙여야 한다. 인덱스는 NULL 을 저장하고 `IS NULL` 조건에도 쓰인다.

```text
 Bitmap Heap Scan on big_e
   Recheck Cond: (dept_id IS NULL)
   ->  Bitmap Index Scan on big_e_dept
         Index Cond: (dept_id IS NULL)

  relname   | pages 
------------+-------
 big_e_dept |   174
 big_e_null |    57
```

`big_e_dept` 는 `dept_id` 전체 인덱스, `big_e_null` 은 `WHERE dept_id IS NULL` 조건의 부분 인덱스다. "미처리 건만 찾는" 조회라면 부분 인덱스가 174 페이지 대신 57 페이지로 끝난다.

| 항목 | PostgreSQL 16 (실행) | Oracle (문서) | MySQL (문서) |
|---|---|---|---|
| 빈 문자열 `''` | NULL 과 다른 값 | NULL 로 취급 | NULL 과 다른 값 |
| `'a' \|\| NULL` | NULL | `'a'` | `CONCAT('a', NULL)` 은 NULL |
| 오름차순 정렬 시 NULL | 마지막 | 마지막 | 처음 |
| `NULLS FIRST/LAST` 구문 | 지원 | 지원 | 미지원 |
| 단일 컬럼 B-tree 의 NULL | 저장, `IS NULL` 에 사용 | 모든 키가 NULL 인 행은 저장 안 함 | 저장, `IS NULL` 에 사용 |
| 복합 UNIQUE 의 `(1, NULL)` 중복 | 허용 | 거부 | 허용 |

Oracle 에서 빈 문자열이 NULL 이라는 점은 파급이 크다. `WHERE col = ''` 는 어떤 행도 찾지 못하고, `NOT NULL` 컬럼에 빈 문자열을 넣을 수 없으며, 화면에서 빈 값으로 저장한 것과 저장하지 않은 것을 구분할 수 없다. PostgreSQL 에서 `'' IS NULL` 은 `f`, `length('')` 는 `0` 으로 확인했다. Oracle 의 단일 컬럼 B-tree 인덱스는 NULL 인 행의 항목을 만들지 않으므로 `WHERE col IS NULL` 은 그 인덱스를 쓸 수 없다. `(col, 0)` 처럼 상수를 덧붙인 복합 인덱스로 NULL 행도 인덱스에 들어가게 하는 방법이 쓰인다.

## 9. 실무 판단

- 서브쿼리 부정은 `NOT EXISTS` 를 기본으로 쓴다. `NOT IN` 은 양쪽 컬럼이 모두 `NOT NULL` 일 때만 안전하다.
- 건수 검산은 `조건 / NOT 조건 / 조건 IS NULL` 세 갈래로 나눈다.
- `CHECK` 로 값 범위를 강제할 때는 `NOT NULL` 여부를 따로 정한다.
- 0 건일 수 있는 `sum` 은 `coalesce` 로 감싼다. 평균은 NULL 을 분모에 넣을지 먼저 정한다.
- NULL 을 포함한 동등 비교는 `IS NOT DISTINCT FROM`(Oracle 은 `DECODE(a, b, 1, 0) = 1` 이 같은 의미로 쓰인다)을 쓴다.
- Oracle 과 다른 DBMS 사이를 오가는 스키마는 빈 문자열, 복합 UNIQUE, `IS NULL` 인덱스 세 가지를 점검한다.

검증 환경: PostgreSQL 16.15 에서 `psql -f` 로 실행한 출력이다. `\pset null '(null)'` 로 NULL 표시를 바꿨고, 출력에서 `CREATE TABLE` 같은 상태 줄과 행 수 표시는 일부 생략했다. 6절의 조인 결과 두 개는 지면을 줄이려고 나란히 배치한 것이며 값은 그대로다. 4절의 `big_e` 는 `generate_series(1, 200000)` 에서 10 의 배수를 NULL 로, 나머지를 `g % 5000` 으로 채웠고 `big_d` 는 1 부터 4,000 이다. Oracle 과 MySQL 열의 내용은 아래 문서에 근거하며 이 환경에서 실행하지 않았다.

## 참고

- PostgreSQL 16 문서, Comparison Functions and Operators (IS DISTINCT FROM, NULL 비교): https://www.postgresql.org/docs/16/functions-comparison.html
- PostgreSQL 16 문서, Subquery Expressions (NOT IN 과 NULL): https://www.postgresql.org/docs/16/functions-subquery.html
- PostgreSQL 16 문서, Constraints (CHECK 와 NULL, UNIQUE NULLS NOT DISTINCT): https://www.postgresql.org/docs/16/ddl-constraints.html
- PostgreSQL 16 문서, Indexes and ORDER BY (NULLS FIRST/LAST), Partial Indexes: https://www.postgresql.org/docs/16/indexes-ordering.html
- Oracle Database 19c SQL Language Reference, Nulls (빈 문자열, 연결 연산자, 조건에서의 NULL): https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Nulls.html
- Oracle Database 19c Concepts, Data Integrity (Unique Constraints 와 부분 NULL 복합 키): https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-integrity.html
- Oracle Database 19c Concepts, Indexes and Index-Organized Tables (B-tree 인덱스와 NULL): https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/indexes-and-index-organized-tables.html
- MySQL 8.4 Reference Manual, Working with NULL Values: https://dev.mysql.com/doc/refman/8.4/en/working-with-null.html
- C. J. Date, SQL and Relational Theory, 3rd ed., O'Reilly (3값 논리 비판)
