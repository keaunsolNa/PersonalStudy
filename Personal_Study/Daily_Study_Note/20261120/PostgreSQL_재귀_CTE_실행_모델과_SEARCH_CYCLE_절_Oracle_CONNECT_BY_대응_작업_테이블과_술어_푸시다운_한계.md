Notion 원본: https://www.notion.so/41e5a06fd6d381688c9ed40059d917a2

# PostgreSQL 재귀 CTE 실행 모델과 SEARCH·CYCLE 절: Oracle CONNECT BY 대응, 작업 테이블과 술어 푸시다운 한계

> 2026-11-20 신규 주제 · 확장 대상: Oracle(계층 쿼리 CONNECT BY), SQLD(셀프 조인·계층형 질의)

## 학습 목표

- `WITH RECURSIVE` 가 작업 테이블을 교체하며 반복하는 과정을 실행계획의 `loops` 값으로 확인한다
- 깊이 우선·너비 우선 순서를 경로 배열과 `SEARCH` 절 두 방식으로 만든다
- 순환이 있는 그래프에서 `UNION`, 경로 배열, `CYCLE` 절이 각각 어떻게 종료를 보장하는지 비교한다
- Oracle `CONNECT BY` 의 의사 컬럼과 절을 재귀 CTE 표현으로 대응시킨다

## 1. 실행 모델: 반복이지 재귀가 아니다

이름과 달리 재귀 CTE 는 반복으로 평가된다. PostgreSQL 문서가 기술하는 절차는 이렇다. 비재귀 항(앵커)을 실행해 결과와 작업 테이블(working table)에 넣는다. 작업 테이블이 비어 있지 않은 동안, 재귀 항을 실행하되 자기 참조를 작업 테이블의 현재 내용으로 대체한다. 그 결과를 전체 결과에 더하고 중간 테이블에 모은 뒤, 작업 테이블을 중간 테이블로 교체한다. 재귀 항은 "직전 반복에서 새로 나온 행" 만 본다. 지금까지 누적된 전체 결과를 보는 것이 아니다.

```sql
CREATE TABLE emp (id int PRIMARY KEY, name text NOT NULL, mgr_id int REFERENCES emp(id));
INSERT INTO emp VALUES (1,'ceo',NULL),(2,'cto',1),(3,'cfo',1),(4,'dev_lead',2),(5,'dev_a',4),(6,'dev_b',4),(7,'acct',3);
CREATE INDEX emp_mgr_idx ON emp(mgr_id);

WITH RECURSIVE org AS (
  SELECT id, name, mgr_id, 1 AS lvl, name AS path
  FROM emp WHERE mgr_id IS NULL
  UNION ALL
  SELECT e.id, e.name, e.mgr_id, o.lvl + 1, o.path || '/' || e.name
  FROM emp e JOIN org o ON e.mgr_id = o.id
)
SELECT lvl, repeat('  ', lvl - 1) || name AS name, path FROM org;
```

```text
 lvl |     name     |          path          
-----+--------------+------------------------
   1 | ceo          | ceo
   2 |   cfo        | ceo/cfo
   2 |   cto        | ceo/cto
   3 |     acct     | ceo/cfo/acct
   3 |     dev_lead | ceo/cto/dev_lead
   4 |       dev_b  | ceo/cto/dev_lead/dev_b
   4 |       dev_a  | ceo/cto/dev_lead/dev_a
(7 rows)
```

`lvl` 과 `path` 는 Oracle 의 `LEVEL`, `SYS_CONNECT_BY_PATH` 에 해당하는 값을 직접 누적한 열이다. 출력이 레벨 순으로 나온 것은 반복 단위로 결과가 쌓이는 구현의 부산물이다. `ORDER BY` 가 없는 한 순서는 보장되지 않으며, 같은 레벨 안의 순서(`cfo` 가 `cto` 보다 먼저)는 조인 방식에 따라 달라진다.

재귀 항에는 제약이 있다. 아래 네 쿼리는 모두 오류다.

```sql
WITH RECURSIVE t AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM t WHERE n < (SELECT max(n) FROM t)) SELECT * FROM t;
WITH RECURSIVE t AS (SELECT 1 AS n UNION ALL SELECT max(n) + 1 FROM t HAVING max(n) < 3) SELECT * FROM t;
WITH RECURSIVE t AS (SELECT 1 AS n UNION ALL SELECT a.n + 1 FROM t a JOIN t b ON a.n = b.n WHERE a.n < 3) SELECT * FROM t;
WITH RECURSIVE t AS (SELECT 1 AS n UNION ALL SELECT n + 1.5 FROM t WHERE n < 3) SELECT * FROM t;
```

```text
ERROR:  recursive reference to query "t" must not appear within a subquery
ERROR:  aggregate functions are not allowed in a recursive query's recursive term
ERROR:  recursive reference to query "t" must not appear more than once
ERROR:  recursive query "t" column 1 has type integer in non-recursive term but type numeric overall
HINT:  Cast the output of the non-recursive term to the correct type.
```

자기 참조는 재귀 항에 한 번만, 서브쿼리 밖에서 나와야 하고, 재귀 항에 집계 함수를 쓸 수 없다. 작업 테이블은 한 반복의 행만 담고 있으므로 "전체에 대한 집계" 는 정의할 수 없기 때문이다. 열 타입은 앵커가 결정한다. 네 번째 오류처럼 재귀 항이 더 넓은 타입을 만들면 앵커 쪽에 캐스트를 넣어야 한다. 문자열 경로를 누적할 때 앵커가 `varchar(10)` 열이면 같은 오류가 나므로 `name::text` 로 시작한다.

## 2. 순서 제어: 경로 배열과 SEARCH 절

조직도를 트리 모양으로 출력하려면 깊이 우선 순서가 필요하다. Oracle 은 `CONNECT BY` 가 깊이 우선으로 결과를 내고 `ORDER SIBLINGS BY` 로 형제 순서를 정한다. 재귀 CTE 에서는 루트부터의 경로를 배열로 누적해 그 배열로 정렬한다.

```sql
WITH RECURSIVE org AS (
  SELECT id, name, 1 AS lvl, ARRAY[id] AS ord FROM emp WHERE mgr_id IS NULL
  UNION ALL
  SELECT e.id, e.name, o.lvl + 1, o.ord || e.id FROM emp e JOIN org o ON e.mgr_id = o.id
)
SELECT repeat('  ', lvl - 1) || name AS name, ord FROM org ORDER BY ord;
```

```text
     name     |    ord    
--------------+-----------
 ceo          | {1}
   cto        | {1,2}
     dev_lead | {1,2,4}
       dev_a  | {1,2,4,5}
       dev_b  | {1,2,4,6}
   cfo        | {1,3}
     acct     | {1,3,7}
(7 rows)
```

배열은 원소 단위로 사전식 비교되므로 부모가 자식보다 앞에, 형제는 누적한 키 순으로 온다. PostgreSQL 14 부터는 SQL 표준의 `SEARCH` 절이 같은 열을 자동으로 만든다.

```sql
WITH RECURSIVE org AS (
  SELECT id, name, 1 AS lvl FROM emp WHERE mgr_id IS NULL
  UNION ALL
  SELECT e.id, e.name, o.lvl + 1 FROM emp e JOIN org o ON e.mgr_id = o.id
) SEARCH DEPTH FIRST BY name SET ord
SELECT repeat('  ', lvl - 1) || name AS name, ord FROM org ORDER BY ord;
```

```text
     name     |               ord                
--------------+----------------------------------
 ceo          | {(ceo)}
   cfo        | {(ceo),(cfo)}
     acct     | {(ceo),(cfo),(acct)}
   cto        | {(ceo),(cto)}
     dev_lead | {(ceo),(cto),(dev_lead)}
       dev_a  | {(ceo),(cto),(dev_lead),(dev_a)}
       dev_b  | {(ceo),(cto),(dev_lead),(dev_b)}
(7 rows)
```

`SEARCH DEPTH FIRST BY name SET ord` 는 `name` 의 행 값 배열을 `ord` 열로 추가한다. `SEARCH BREADTH FIRST BY name SET ord` 로 바꾸면 `ord` 가 `(0,ceo)`, `(1,cfo)`, `(1,cto)`, `(2,acct)` 처럼 (깊이, 키) 레코드가 되어 레벨 순 정렬이 된다. 어느 쪽이든 `SEARCH` 절은 정렬용 열을 만들 뿐이고 실제 정렬은 바깥 쿼리의 `ORDER BY ord` 가 한다. 절만 쓰고 `ORDER BY` 를 빠뜨리면 순서는 여전히 보장되지 않는다. 경로 배열은 깊이에 비례해 행이 커지므로 깊이가 수백 단계인 계층에서는 정렬 메모리를 확인한다.

## 3. 상향 탐색과 서브트리 집계

앵커와 조인 방향을 바꾸면 같은 구조로 조상 체인을 구한다.

```sql
WITH RECURSIVE up AS (
  SELECT id, name, mgr_id, 0 AS dist FROM emp WHERE name = 'dev_a'
  UNION ALL
  SELECT e.id, e.name, e.mgr_id, u.dist + 1 FROM emp e JOIN up u ON e.id = u.mgr_id
)
SELECT string_agg(name, ' > ' ORDER BY dist DESC) AS chain FROM up;

WITH RECURSIVE sub AS (
  SELECT id AS root, id FROM emp
  UNION ALL
  SELECT s.root, e.id FROM emp e JOIN sub s ON e.mgr_id = s.id
)
SELECT r.name, count(*) - 1 AS reports FROM sub s JOIN emp r ON r.id = s.root GROUP BY r.id, r.name ORDER BY r.id;
```

```text
            chain             
------------------------------
 ceo > cto > dev_lead > dev_a
(1 row)

   name   | reports 
----------+---------
 ceo      |       6
 cto      |       3
 cfo      |       1
 dev_lead |       2
 dev_a    |       0
 dev_b    |       0
 acct     |       0
(7 rows)
```

두 번째 쿼리는 모든 사원을 각자의 루트로 삼아 서브트리를 펼친 뒤 바깥에서 집계한다. 재귀 항 안에서는 집계할 수 없으므로 "펼치고 나서 집계" 가 기본 형태다. 이 방식은 (조상, 자손) 쌍을 전부 만들기 때문에 행 수가 노드 수 × 평균 깊이가 된다. 계층이 깊고 조회가 잦으면 이 쌍을 미리 저장해 두는 closure table 이나 `ltree` 확장이 대안이다.

## 4. 순환: UNION, 경로 검사, CYCLE 절

트리가 아닌 그래프에는 순환이 있을 수 있다. `UNION ALL` 은 종료 조건이 "새 행이 없음" 뿐이라 순환을 만나면 끝나지 않는다.

```sql
CREATE TABLE edge (src text, dst text);
INSERT INTO edge VALUES ('a','b'),('b','c'),('c','a'),('c','d');
SET statement_timeout = '300ms';
WITH RECURSIVE walk AS (
  SELECT src, dst FROM edge WHERE src = 'a'
  UNION ALL
  SELECT e.src, e.dst FROM edge e JOIN walk w ON e.src = w.dst
)
SELECT count(*) FROM walk;
-- ERROR:  canceling statement due to statement timeout
```

`UNION` 으로 바꾸면 이미 결과에 있는 행과 중복되는 행이 버려져 종료된다. 다만 행 전체가 같아야 중복이다. 깊이 열을 하나 추가하면 같은 간선이 다른 깊이로 계속 새 행이 되어 다시 무한 반복한다.

```sql
WITH RECURSIVE walk AS (
  SELECT src, dst, 1 AS depth FROM edge WHERE src = 'a'
  UNION
  SELECT e.src, e.dst, w.depth + 1 FROM edge e JOIN walk w ON e.src = w.dst
)
SELECT * FROM walk LIMIT 6;
```

```text
 src | dst | depth 
-----+-----+-------
 a   | b   |     1
 b   | c   |     2
 c   | a   |     3
 c   | d   |     3
 a   | b   |     4
 b   | c   |     5
(6 rows)
```

`LIMIT 6` 이 있어서 멈췄을 뿐이다. PostgreSQL 은 바깥 쿼리가 요구한 만큼만 재귀 CTE 를 평가하므로 `LIMIT` 로 끊을 수 있지만, 문서는 이것에 의존하지 말라고 경고한다. 바깥에 정렬이나 조인이 붙으면 전체를 평가하려 들기 때문이다. 일반적인 해법은 방문 경로를 누적하고 다음 노드가 경로에 이미 있는지 검사하는 것이고, 14 부터는 `CYCLE` 절이 그 열들을 만들어 준다.

```sql
WITH RECURSIVE walk AS (
  SELECT src, dst, 1 AS depth FROM edge WHERE src = 'a'
  UNION ALL
  SELECT e.src, e.dst, w.depth + 1 FROM edge e JOIN walk w ON e.src = w.dst
) CYCLE dst SET is_cycle USING path
SELECT * FROM walk;
```

```text
 src | dst | depth | is_cycle |       path        
-----+-----+-------+----------+-------------------
 a   | b   |     1 | f        | {(b)}
 b   | c   |     2 | f        | {(b),(c)}
 c   | a   |     3 | f        | {(b),(c),(a)}
 c   | d   |     3 | f        | {(b),(c),(d)}
 a   | b   |     4 | t        | {(b),(c),(a),(b)}
(5 rows)
```

`CYCLE dst SET is_cycle USING path` 는 `dst` 값이 자기 경로에 다시 나타난 행에 `is_cycle = true` 를 표시하고 그 행에서는 더 확장하지 않는다. 순환을 닫는 행 자체는 결과에 포함되므로 필요 없으면 바깥에서 `WHERE NOT is_cycle` 로 걸러 낸다. 수동 구현은 `ARRAY[src, dst]` 로 시작해 `e.dst = ANY(w.path)` 를 플래그로 두고 `WHERE NOT w.is_cycle` 로 확장을 막는 형태이며, 같은 데이터에서 `a→b`, `b→c`, `c→d` 세 행을 반환했다. 순환 가능성이 있는 데이터에는 `CYCLE` 절과 함께 깊이 상한(`WHERE w.depth < 50`)을 방어선으로 같이 둔다. 경로 수가 폭발하는 조밀한 그래프에서는 순환이 없어도 결과가 지수적으로 커진다.

## 5. 실행계획과 술어 푸시다운 한계

같은 테이블에 99,993 행을 추가해 5 단계 계층(레벨별 1, 102, 1002, 10002, 88893 행)을 만들고 계획을 확인했다.

```sql
INSERT INTO emp SELECT g, 'e' || g, CASE WHEN g <= 107 THEN 1 ELSE (g - 8) / 10 - 2 END FROM generate_series(8, 100000) g;
ANALYZE emp;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
WITH RECURSIVE org AS (
  SELECT id, mgr_id, 1 AS lvl FROM emp WHERE mgr_id IS NULL
  UNION ALL
  SELECT e.id, e.mgr_id, o.lvl + 1 FROM emp e JOIN org o ON e.mgr_id = o.id
)
SELECT * FROM org WHERE id = 5;
```

```text
 CTE Scan on org (actual rows=1 loops=1)
   Filter: (id = 5)
   Rows Removed by Filter: 99999
   CTE org
     ->  Recursive Union (actual rows=100000 loops=1)
           ->  Index Scan using emp_mgr_idx on emp (actual rows=1 loops=1)
                 Index Cond: (mgr_id IS NULL)
           ->  Nested Loop (actual rows=20000 loops=5)
                 ->  WorkTable Scan on org o (actual rows=20000 loops=5)
                 ->  Index Scan using emp_mgr_idx on emp e (actual rows=1 loops=100000)
                       Index Cond: (mgr_id = o.id)
```

`Recursive Union` 아래 첫 자식이 앵커, 둘째 자식이 재귀 항이다. `WorkTable Scan` 의 `loops=5` 가 반복 횟수이고, 마지막 반복은 빈 결과를 내고 종료한다. `rows=20000` 은 반복당 평균이다. `emp_mgr_idx` 인덱스 탐색이 작업 테이블의 행마다 한 번씩, 합계 10만 번 실행되었다. `mgr_id` 인덱스가 없으면 반복마다 `emp` 전체를 읽는 해시 조인이나 순차 스캔이 되므로 부모 키 열의 인덱스는 재귀 CTE 의 전제 조건이다.

더 중요한 것은 `Rows Removed by Filter: 99999` 다. 바깥의 `WHERE id = 5` 는 CTE 안으로 들어가지 않았다. 재귀 CTE 는 항상 구체화되며, 재귀 결과에 대한 조건을 앵커로 옮기는 것은 일반적으로 의미가 달라지는 변환이라 옵티마이저가 하지 않는다. 계층 전체를 펼친 뒤 한 행을 고르는 뷰를 만들어 두고 `WHERE` 로 조회하는 구성이 느린 이유다. 조건은 앵커에 직접 써야 한다. `cto` 의 서브트리만 필요할 때 앵커를 `WHERE id = 2` 로 두면 다음 계획이 된다.

```text
     ->  Recursive Union (actual rows=4 loops=1)
           ->  Index Scan using emp_pkey on emp (actual rows=1 loops=1)
                 Index Cond: (id = 2)
           ->  Nested Loop (actual rows=1 loops=3)
                 ->  WorkTable Scan on org o (actual rows=1 loops=3)
                 ->  Index Scan using emp_mgr_idx on emp e (actual rows=1 loops=4)
                       Index Cond: (mgr_id = o.id)
```

읽는 행이 10만에서 4 로 줄었다. 시작점이 매개변수인 계층 조회는 뷰가 아니라 시작 키를 인자로 받는 SQL 함수나 애플리케이션 쿼리로 만든다. 옵티마이저의 행 수 추정도 약점이다. 재귀 항이 몇 번 반복될지 알 수 없어 고정된 가정으로 추정하므로, 재귀 CTE 결과를 큰 테이블과 다시 조인하면 조인 방식 선택이 어긋날 수 있다.

## 6. Oracle CONNECT BY 와의 대응

이 절은 Oracle Database SQL Language Reference 에 근거한 대응 정리이며 Oracle 에서 실행해 검증하지는 않았다.

- `START WITH 조건` 은 앵커의 `WHERE`, `CONNECT BY PRIOR id = mgr_id` 는 재귀 항의 조인 조건 `e.mgr_id = o.id` 에 해당한다. `PRIOR` 가 붙은 쪽이 직전 단계(작업 테이블)의 열이다.
- `LEVEL` 은 `1` 에서 시작해 `+ 1` 하는 열, `SYS_CONNECT_BY_PATH(name, '/')` 는 문자열 누적 열, `CONNECT_BY_ROOT name` 은 앵커 값을 그대로 전달하는 열(3절의 `root`)로 만든다.
- `CONNECT_BY_ISLEAF` 는 누적으로 만들 수 없다. 바깥에서 `NOT EXISTS (SELECT 1 FROM emp c WHERE c.mgr_id = org.id)` 로 계산한다.
- `ORDER SIBLINGS BY name` 은 `SEARCH DEPTH FIRST BY name SET ord` 와 `ORDER BY ord` 의 조합이다.
- `NOCYCLE` 과 `CONNECT_BY_ISCYCLE` 은 `CYCLE` 절에 해당한다. `NOCYCLE` 없이 순환을 만나면 Oracle 은 ORA-01436 을 낸다.
- `CONNECT BY` 절의 조건은 탐색을 가지치기하고 `WHERE` 절은 탐색이 끝난 뒤 행을 걸러 낸다. 재귀 CTE 에서는 재귀 항의 `WHERE` 가 가지치기, 바깥 쿼리의 `WHERE` 가 사후 필터다. 5절의 결과와 같은 구분이다.

Oracle 도 11g Release 2 부터 재귀 서브쿼리 팩토링을 지원한다. `RECURSIVE` 키워드 없이 `WITH org (id, name, lvl) AS (...)` 처럼 열 목록을 적는 형태이고 `SEARCH`·`CYCLE` 절 문법은 표준과 같다. `CYCLE` 절 없이 순환을 만나면 ORA-32044 로 중단된다. MySQL 은 8.0 부터 `WITH RECURSIVE` 를 지원하고 `cte_max_recursion_depth`(기본 1000)로 반복 횟수를 제한하며, `SEARCH`·`CYCLE` 절은 없어 2절과 4절의 수동 방식을 쓴다. 세 DBMS 에서 모두 동작해야 하는 쿼리는 재귀 CTE 에 경로 누적을 직접 쓰는 형태가 공통분모다.

## 7. 적용 판단

재귀 CTE 는 인접 목록(부모 키 열 하나)이라는 가장 단순한 스키마를 그대로 두고 계층을 조회하게 해 준다. 쓰기가 단순하고 노드 이동이 `UPDATE` 한 번이라는 장점이 유지된다. 비용은 조회 때 깊이만큼 인덱스 탐색을 반복한다는 점이다. 댓글 트리나 조직도처럼 깊이가 수십 단계 이하이고 시작점이 정해진 조회에는 충분하다.

조회가 압도적으로 많고 "어떤 노드의 모든 자손" 을 자주 묻는 카테고리 트리 같은 경우에는 경로를 열에 저장하는 방식(materialized path, PostgreSQL 의 `ltree`)이나 closure table 이 조회를 단일 인덱스 범위 스캔으로 바꾼다. 대신 노드를 옮길 때 서브트리 전체의 경로를 갱신해야 한다. 최단 경로나 다대다 관계의 다단계 탐색처럼 경로 수가 폭발하는 그래프 문제는 SQL 재귀로 풀 수는 있어도 깊이 상한 없이는 운영에 올리기 어렵다. 재귀 CTE 를 쓸 때의 점검 항목은 네 가지다. 부모 키 열에 인덱스가 있는가, 시작 조건이 앵커에 있는가, 순환 가능성에 대한 종료 보장이 있는가, 결과 순서가 필요하면 바깥에 `ORDER BY` 가 있는가.

검증 환경: PostgreSQL 16.15 에서 `psql -f` 로 실행한 출력이다. 실행계획은 `EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)` 결과이고, 5절의 두 번째 계획은 앵커를 `WHERE id = 2` 로 바꾼 쿼리의 계획에서 `Recursive Union` 부분만 옮겼다. 1절의 오류 쿼리 네 개는 원래 여러 줄이던 것을 한 줄로 붙여 적었고 오류 메시지의 `LINE` 표시는 생략했다.

## 참고

- PostgreSQL 16 문서, WITH Queries (Recursive Queries, Search Order, Cycle Detection): https://www.postgresql.org/docs/16/queries-with.html
- PostgreSQL 16 문서, SELECT (WITH Clause): https://www.postgresql.org/docs/16/sql-select.html
- PostgreSQL 14 릴리스 노트 (SEARCH·CYCLE 절 추가): https://www.postgresql.org/docs/release/14.0/
- PostgreSQL 16 문서, ltree: https://www.postgresql.org/docs/16/ltree.html
- Oracle Database SQL Language Reference, Hierarchical Queries: https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Hierarchical-Queries.html
- Oracle Database SQL Language Reference, SELECT (subquery_factoring_clause, search_clause, cycle_clause): https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/SELECT.html
- MySQL 8.0 Reference Manual, WITH (Common Table Expressions): https://dev.mysql.com/doc/refman/8.0/en/with.html
