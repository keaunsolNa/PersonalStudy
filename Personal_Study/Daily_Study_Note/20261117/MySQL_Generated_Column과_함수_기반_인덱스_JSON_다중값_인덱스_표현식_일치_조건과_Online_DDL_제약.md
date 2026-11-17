Notion 원본: https://www.notion.so/41b5a06fd6d381939975e4b93e508a73

# MySQL Generated Column 과 함수 기반 인덱스, JSON 다중값 인덱스: 표현식 일치 조건과 Online DDL 제약

> 2026-11-17 신규 주제 · 확장 대상: Oracle(함수 기반 인덱스·가상 컬럼), SQLD(인덱스와 조건절 가공), ORM(JPA 컬럼 매핑)

## 학습 목표

- 컬럼을 가공한 조건절이 인덱스를 쓰지 못하는 상황을 EXPLAIN 으로 재현하고 세 가지 해법을 비교한다
- VIRTUAL·STORED 생성 컬럼의 DDL 알고리즘과 쓰기 제약을 실행 결과로 구분한다
- 옵티마이저가 조건절 표현식을 생성 컬럼·함수 인덱스로 치환하는 조건과 실패하는 경우를 확인한다
- JSON 스칼라 경로와 배열에 각각 함수 인덱스와 다중값 인덱스를 만들고 인덱스를 타는 함수를 가려낸다

## 1. 기준선: 가공된 컬럼은 인덱스 범위 탐색을 못 한다

실습 테이블은 주문 20만 건이다. `email` 은 대소문자가 섞여 있고, `attrs` 는 `channel`(4종), `coupon`(50건 중 1건만 값이 있음), `tags`(문자열 3개 배열)를 담은 JSON 이다.

```sql
CREATE TABLE orders (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  email       VARCHAR(100) NOT NULL,
  ordered_at  DATETIME NOT NULL,
  amount      INT NOT NULL,
  attrs       JSON NOT NULL,
  KEY idx_email (email),
  KEY idx_ordered_at (ordered_at)
) ENGINE=InnoDB;

SET SESSION cte_max_recursion_depth = 300000;
INSERT INTO orders (email, ordered_at, amount, attrs)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200000)
SELECT CONCAT(IF(n % 2 = 0, 'User', 'user'), n, '@Example.com'),
       TIMESTAMPADD(MINUTE, n * 7, '2025-01-01 00:00:00'),
       (n * 37) % 100000,
       JSON_OBJECT('channel', ELT(1 + n % 4, 'web', 'app', 'kiosk', 'partner'),
                   'coupon', IF(n % 50 = 0, CONCAT('C', n % 1000), NULL),
                   'tags', JSON_ARRAY(CONCAT('t', n % 500), CONCAT('t', (n * 7) % 500), IF(n % 1000 = 0, 'vip', 'normal')))
FROM seq;
ANALYZE TABLE orders;
```

이후 EXPLAIN 결과는 `type`, `key`, `rows`, `Extra` 네 컬럼만 옮긴다.

```text
EXPLAIN SELECT id FROM orders WHERE YEAR(ordered_at) = 2026 AND MONTH(ordered_at) = 3
  type=index key=idx_ordered_at rows=198175 Extra=Using where; Using index
EXPLAIN SELECT id FROM orders WHERE ordered_at >= '2026-03-01' AND ordered_at < '2026-04-01'
  type=range key=idx_ordered_at rows=6377 Extra=Using where; Using index
EXPLAIN SELECT id FROM orders WHERE amount DIV 10000 = 3
  type=ALL key=NULL rows=198175 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE attrs->>'$.channel' = 'kiosk'
  type=ALL key=NULL rows=198175 Extra=Using where
```

첫 줄의 `type=index` 는 인덱스를 쓴 것처럼 보이지만 인덱스 전체를 처음부터 끝까지 읽는 풀 스캔이다. B-Tree 는 저장된 값의 순서로 정렬되어 있으므로 `YEAR(ordered_at)` 의 결과로는 탐색 시작점을 정할 수 없다. 가장 먼저 검토할 해법은 두 번째 줄처럼 조건을 컬럼 원본에 대한 범위로 다시 쓰는 것이며, 추가 인덱스가 필요 없다. 다시 쓸 수 없는 표현식(`amount DIV 10000`, JSON 경로 추출)에 대해서만 생성 컬럼이나 함수 인덱스를 쓴다.

## 2. 생성 컬럼: VIRTUAL 과 STORED

생성 컬럼은 다른 컬럼으로부터 계산되는 컬럼이다. VIRTUAL 은 값을 행에 저장하지 않고 읽을 때 계산하며, STORED 는 쓰기 시점에 계산해 행에 저장한다. 이 차이가 DDL 비용을 가른다.

```text
mysql> ALTER TABLE orders ADD COLUMN amount_band INT
       GENERATED ALWAYS AS (amount DIV 10000) VIRTUAL, ALGORITHM=INSTANT;
Query OK, 0 rows affected (0.04 sec)

mysql> ALTER TABLE orders ADD COLUMN channel VARCHAR(10)
       GENERATED ALWAYS AS (attrs->>'$.channel') STORED, ALGORITHM=INPLACE;
ERROR 1845 (0A000): ALGORITHM=INPLACE is not supported for this operation. Try ALGORITHM=COPY.

mysql> ALTER TABLE orders ADD COLUMN channel VARCHAR(10)
       GENERATED ALWAYS AS (attrs->>'$.channel') STORED, ALGORITHM=COPY;
Query OK, 200000 rows affected (1.92 sec)

mysql> ALTER TABLE orders ADD INDEX idx_band (amount_band), ADD INDEX idx_channel (channel),
       ALGORITHM=INPLACE, LOCK=NONE;
Query OK, 0 rows affected (0.66 sec)
```

VIRTUAL 컬럼 추가는 메타데이터만 바꾸는 INSTANT 로 끝났다. STORED 컬럼 추가는 모든 행에 값을 써야 하므로 테이블 복사(COPY)만 가능하고, COPY 는 수행 중 쓰기를 막는다. VIRTUAL 컬럼에 건 보조 인덱스는 인덱스 엔트리에 계산 값이 물리적으로 저장되므로 조회 성능은 STORED 컬럼의 인덱스와 같다. 따라서 인덱스가 목적이면 VIRTUAL 로 충분하고, STORED 는 계산 비용이 큰 값을 인덱스 없이 자주 읽을 때만 쓴다.

## 3. 옵티마이저의 표현식 치환

생성 컬럼에 인덱스가 있으면, 애플리케이션 쿼리가 생성 컬럼 이름을 쓰지 않고 원래 표현식을 그대로 써도 옵티마이저가 치환한다. EXPLAIN 뒤에 `SHOW WARNINGS` 를 실행하면 재작성된 쿼리가 보인다.

```text
EXPLAIN SELECT id FROM orders WHERE amount DIV 10000 = 3
  type=ref key=idx_band rows=37774 Extra=NULL
SHOW WARNINGS: ... where (`study`.`orders`.`amount_band` = 3)

EXPLAIN SELECT id FROM orders WHERE amount DIV 10000 IN (3, 4)
  type=ALL key=NULL rows=198217 Extra=Using where
SHOW WARNINGS: ... where (`study`.`orders`.`amount_band` in (3,4))

EXPLAIN SELECT id FROM orders WHERE amount_band IN (3, 4)
  type=range key=idx_band rows=71032 Extra=Using where; Using index

EXPLAIN SELECT id FROM orders WHERE amount DIV 10000 + 0 = 3
  type=ALL key=NULL rows=198217 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE amount DIV 1000 = 30
  type=ALL key=NULL rows=198217 Extra=Using where
EXPLAIN SELECT COUNT(*) FROM orders WHERE attrs->>'$.channel' = 'kiosk'
  type=ref key=idx_channel rows=91110 Extra=NULL
```

두 번째 결과는 해석에 주의가 필요하다. `IN (3, 4)` 는 `amount_band` 로 치환되었는데도 풀 스캔이 선택되었다. 치환 실패가 아니라 비용 판단이다. 첫 번째 결과의 `Extra` 가 `Using index` 가 아닌 `NULL` 인 점이 단서다. 원래 쿼리가 `amount` 를 참조하므로 치환된 계획은 커버링 인덱스로 처리되지 않고 행을 읽으러 간다. 구간 두 개는 전체의 약 20% 라서 그만큼의 행 조회가 풀 스캔보다 비싸다고 본 것이다. 세 번째 결과처럼 생성 컬럼 이름을 직접 쓰면 같은 조건이 커버링 범위 스캔이 된다. 네 번째와 다섯 번째는 치환 자체가 일어나지 않았다. 표현식이 생성 컬럼 정의와 구문 수준에서 일치해야 하고, `+ 0` 을 붙이거나 상수를 바꾸면 다른 표현식이다. `rows=37774` 는 통계 추정치이고 구간 하나의 실제 건수는 20000 이다.

치환은 편리하지만 조건이 까다롭고 커버링 여부도 달라지므로, 새로 작성하는 쿼리는 생성 컬럼 이름을 직접 쓰는 쪽이 실행 계획이 안정적이다. 치환은 ORM 이 생성한 쿼리처럼 고칠 수 없는 SQL 을 인덱스에 태울 때 유용하다.

## 4. 쓰기 제약

```text
mysql> INSERT INTO orders (email, ordered_at, amount, attrs, amount_band)
       VALUES ('w@test', '2030-01-01', 1, '{}', 0);
ERROR 3105 (HY000): The value specified for generated column 'amount_band' in table 'orders' is not allowed.

mysql> INSERT INTO orders (email, ordered_at, amount, attrs, amount_band)
       VALUES ('w@test', '2030-01-01', 12345, '{"channel":"web"}', DEFAULT);
Query OK, 1 row affected (0.00 sec)

mysql> UPDATE orders SET amount = 98765, attrs = JSON_SET(attrs, '$.channel', 'app') WHERE email = 'w@test';
mysql> SELECT amount, amount_band, channel FROM orders WHERE email = 'w@test';
+--------+-------------+---------+
| amount | amount_band | channel |
+--------+-------------+---------+
|  98765 |           9 | app     |
+--------+-------------+---------+

mysql> INSERT INTO orders (email, ordered_at, amount, attrs)
       VALUES ('w@test', '2030-01-01', 1, '{"channel":"a-very-long-channel-name"}');
ERROR 1406 (22001): Data too long for column 'channel' at row 1

mysql> ALTER TABLE orders ADD COLUMN age_days INT GENERATED ALWAYS AS (DATEDIFF(NOW(), ordered_at)) VIRTUAL;
ERROR 3763 (HY000): Expression of generated column 'age_days' contains a disallowed function: now.
```

생성 컬럼에는 `DEFAULT` 외의 값을 쓸 수 없다. JPA 엔티티에 매핑할 때는 해당 필드를 `insertable = false, updatable = false` 로 선언해야 INSERT·UPDATE 문에 컬럼이 포함되지 않는다. 네 번째 결과는 운영에서 사고가 나는 지점이다. 생성 컬럼의 타입이 표현식 결과를 담지 못하면 원본 컬럼에 대한 정상적인 INSERT 가 실패한다. JSON 처럼 형식이 열려 있는 원본에서 값을 뽑는 생성 컬럼은 길이를 넉넉히 잡아야 한다. 마지막으로 `NOW()`·`RAND()` 같은 비결정적 함수는 쓸 수 없으므로 "경과 일수" 같은 값은 생성 컬럼으로 만들 수 없다.

## 5. 함수 기반 인덱스

MySQL 8.0.13 부터 인덱스 정의에 표현식을 직접 쓸 수 있다. 표현식 전체를 괄호로 한 번 더 감싸는 것이 문법이다.

```text
mysql> ALTER TABLE orders ADD INDEX idx_email_lower ((LOWER(email))), ALGORITHM=INPLACE, LOCK=NONE;
ERROR 1846 (0A000): LOCK=NONE is not supported. Reason: ADD COLUMN col...VIRTUAL, ADD INDEX(col). Try LOCK=SHARED.

mysql> ALTER TABLE orders ADD INDEX idx_email_lower ((LOWER(email))), ALGORITHM=INPLACE, LOCK=SHARED;
Query OK, 0 rows affected (0.71 sec)

mysql> SHOW EXTENDED COLUMNS FROM orders LIKE '!hidden%';
| !hidden!idx_email_lower!0!0 | varchar(100) | YES | MUL | NULL | VIRTUAL GENERATED |

EXPLAIN SELECT id FROM orders WHERE LOWER(email) = 'user1000@example.com'
  type=ref key=idx_email_lower rows=1 Extra=NULL
EXPLAIN SELECT id FROM orders WHERE LOWER(email) IN ('user1000@example.com', 'user7@example.com')
  type=range key=idx_email_lower rows=2 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE LOWER(TRIM(email)) = 'user1000@example.com'
  type=index key=idx_email rows=193290 Extra=Using where; Using index
EXPLAIN SELECT id FROM orders WHERE LOWER(email) LIKE 'user1000@%'
  type=index key=idx_email rows=193290 Extra=Using where; Using index
```

오류 메시지와 `SHOW EXTENDED COLUMNS` 가 구현을 드러낸다. 함수 인덱스는 숨겨진 VIRTUAL 생성 컬럼을 만들고 거기에 인덱스를 거는 것과 같다. 그래서 3절의 치환 규칙이 그대로 적용되어 `TRIM` 을 한 겹 더 씌우면 쓰이지 않고, 이 환경에서는 `LIKE` 전방 일치 조건도 인덱스를 타지 못했다. 또한 일반 보조 인덱스와 달리 `LOCK=NONE` 이 거부되어, 인덱스 생성 중 읽기는 가능하지만 쓰기는 대기한다. 쓰기가 많은 큰 테이블에서는 gh-ost 같은 외부 도구나 점검 시간대 작업을 검토해야 한다.

Oracle 에서 넘어온 경우 대소문자 무시 검색을 위해 `LOWER(email)` 인덱스부터 떠올리기 쉽지만, MySQL 8.0 의 기본 콜레이션 `utf8mb4_0900_ai_ci` 는 비교 자체가 대소문자를 구분하지 않는다.

```text
EXPLAIN SELECT id FROM orders WHERE email = 'USER1000@example.COM'
  type=ref key=idx_email rows=1 Extra=Using index
mysql> SELECT email FROM orders WHERE email = 'USER1000@example.COM';
| User1000@Example.com |
```

기존 `idx_email` 만으로 대소문자가 다른 값이 조회된다. `LOWER` 함수 인덱스가 실제로 필요한 경우는 컬럼이 `utf8mb4_bin` 같은 대소문자 구분 콜레이션일 때다. 이때는 유니크 함수 인덱스로 대소문자 무시 중복 방지까지 걸 수 있다.

```text
mysql> CREATE TABLE members (id INT PRIMARY KEY, email VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
       UNIQUE KEY uq_email_ci ((LOWER(email))));
mysql> INSERT INTO members VALUES (1, 'Kim@Example.com');
mysql> INSERT INTO members VALUES (2, 'kim@example.COM');
ERROR 1062 (23000): Duplicate entry 'kim@example.com' for key 'members.uq_email_ci'
```

## 6. JSON 스칼라 경로의 함수 인덱스

`->>` 연산자는 `JSON_UNQUOTE(JSON_EXTRACT(...))` 의 축약이고 반환 타입이 LONGTEXT 다. 그대로는 인덱스를 만들 수 없어 `CAST` 로 길이를 정해야 하는데, 여기에 콜레이션 함정이 있다.

```text
mysql> ALTER TABLE orders ADD INDEX idx_coupon ((attrs->>'$.coupon'));
ERROR 3757 (HY000): Cannot create a functional index on an expression that returns a BLOB or TEXT. Please consider using CAST.

mysql> SELECT COLLATION(attrs->>'$.coupon') c1, COLLATION(CAST(attrs->>'$.coupon' AS CHAR(20))) c2 FROM orders LIMIT 1;
| utf8mb4_bin | utf8mb4_0900_ai_ci |

mysql> ALTER TABLE orders ADD INDEX idx_coupon
       ((CAST(attrs->>'$.coupon' AS CHAR(20)) COLLATE utf8mb4_bin)), ALGORITHM=INPLACE, LOCK=SHARED;
ERROR 1846 (0A000): ALGORITHM=INPLACE is not supported. Reason: INPLACE ADD or DROP of virtual columns cannot be combined with other ALTER TABLE actions. Try ALGORITHM=COPY.

mysql> ALTER TABLE orders ADD INDEX idx_coupon ((CAST(attrs->>'$.coupon' AS CHAR(20)) COLLATE utf8mb4_bin));
Query OK, 200000 rows affected (3.73 sec)

EXPLAIN SELECT id FROM orders WHERE attrs->>'$.coupon' = 'C50'
  type=ref key=idx_coupon rows=200 Extra=NULL
EXPLAIN SELECT id FROM orders WHERE CAST(attrs->>'$.coupon' AS CHAR(20)) = 'C50'
  type=ALL key=NULL rows=198122 Extra=Using where
```

`->>` 의 결과는 `utf8mb4_bin` 이고 `CAST(... AS CHAR)` 의 결과는 연결 콜레이션을 따른다. 인덱스 정의에 `COLLATE utf8mb4_bin` 을 붙여 두면 애플리케이션이 평범하게 쓰는 `attrs->>'$.coupon' = 'C50'` 조건이 인덱스를 탄다. 반대로 `CAST` 만 쓴 조건은 콜레이션이 달라 인덱스와 일치하지 않는다. 인덱스 유무에 따른 실행 시간은 0.00초와 0.10초였다.

DDL 결과도 기록해 둔다. 이 테이블 상태(가상 컬럼과 함수 인덱스가 이미 있는 상태)에서는 `ALGORITHM=INPLACE` 를 명시한 두 번째 함수 인덱스 추가가 ERROR 1846 으로 거부되었고, 같은 상태에서 VIRTUAL 컬럼 추가도 같은 오류로 거부되었다. 알고리즘을 생략하자 서버가 COPY 로 전환해 20만 행을 복사했다. 반면 바로 뒤 7절의 다중값 인덱스는 INPLACE 로 생성되었다. 어떤 조합에서 INPLACE 가 불가능한지 일반 규칙으로 정리하지는 못했으므로 결론은 절차로 남긴다. 운영 DDL 에는 `ALGORITHM` 과 `LOCK` 을 항상 명시해 의도하지 않은 테이블 복사가 조용히 시작되지 않게 하고, 운영과 같은 스키마 상태의 사본에서 먼저 실행해 본다.

## 7. JSON 배열의 다중값 인덱스

배열의 원소로 검색하려면 한 행이 인덱스 엔트리 여러 개를 가져야 한다. 8.0.17 부터 `CAST(... AS 타입 ARRAY)` 로 다중값 인덱스를 만들 수 있다.

```text
mysql> ALTER TABLE orders ADD INDEX idx_tags
       ((CAST(attrs->'$.tags' AS CHAR(20) ARRAY))), ALGORITHM=INPLACE, LOCK=SHARED;
Query OK, 0 rows affected (1.47 sec)

EXPLAIN SELECT id FROM orders WHERE 'vip' MEMBER OF (attrs->'$.tags')
  type=ref key=idx_tags rows=200 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE JSON_CONTAINS(attrs->'$.tags', '["t7", "t49"]')
  type=range key=idx_tags rows=1600 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE JSON_OVERLAPS(attrs->'$.tags', '["vip", "t499"]')
  type=range key=idx_tags rows=1000 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE JSON_CONTAINS(attrs, '"vip"', '$.tags')
  type=ALL key=NULL rows=198027 Extra=Using where
EXPLAIN SELECT id FROM orders WHERE JSON_SEARCH(attrs->'$.tags', 'one', 'vip') IS NOT NULL
  type=ALL key=NULL rows=198027 Extra=Using where
```

인덱스를 타는 함수는 `MEMBER OF`, `JSON_CONTAINS`, `JSON_OVERLAPS` 셋이고, 인자 모양이 인덱스 정의의 `attrs->'$.tags'` 와 일치해야 한다. 같은 의미라도 경로를 세 번째 인자로 넘긴 `JSON_CONTAINS(attrs, '"vip"', '$.tags')` 는 풀 스캔이다. `JSON_SEARCH` 도 대상이 아니다. `JSON_OVERLAPS` 조회는 인덱스 사용 시 0.01초, `IGNORE INDEX` 로 막았을 때 0.27초였다.

```text
mysql> INSERT ... '{"channel":"web","tags":["this-tag-is-longer-than-twenty-chars"]}';
ERROR 3907 (22001): Data too long for functional index 'idx_tags'.
mysql> INSERT ... '{"channel":"web","tags":"scalar"}';
Query OK, 1 row affected (0.00 sec)
mysql> SELECT email FROM orders WHERE 'scalar' MEMBER OF (attrs->'$.tags');
| mv@test |
```

원소가 인덱스의 `CHAR(20)` 을 넘으면 INSERT 가 실패한다. 4절의 STORED 컬럼과 같은 종류의 위험이다. 배열 자리에 스칼라가 오면 원소 하나짜리 배열처럼 색인된다. 인덱스 크기도 확인했다. `mysql.innodb_index_stats` 의 리프 페이지 수는 `idx_band` 239, `idx_coupon` 244, `idx_tags` 739 였다. 행마다 태그 수만큼 엔트리가 생기므로 일반 보조 인덱스의 약 3배이고, 쓰기 시에도 그만큼의 인덱스 변경이 발생한다.

## 8. 선택 기준

조건을 원본 컬럼의 범위로 다시 쓸 수 있으면 그것이 첫 번째 선택이다. 인덱스가 늘지 않고 치환 규칙에 기대지 않는다. 가공 값을 SELECT 목록이나 여러 쿼리에서 이름으로 쓰고 싶으면 VIRTUAL 생성 컬럼에 인덱스를 건다. 컬럼을 노출하고 싶지 않고 조건절 하나만 태우면 되면 함수 인덱스를 쓰되, 쿼리의 표현식이 인덱스 정의와 구문 단위로 같은지 EXPLAIN 으로 확인한다. Oracle 의 함수 기반 인덱스와 개념은 같지만, MySQL 은 5절에서 본 것처럼 구문 일치에 가깝게 동작하고 온라인 DDL 제약이 다르므로 Oracle 에서의 경험을 그대로 옮기지 말고 실행 계획으로 확인한다.

JSON 은 자주 검색하는 경로가 고정되면 정규 컬럼으로 승격하는 편이 낫다. 타입과 길이를 스키마가 검증하고 통계도 정확해진다. 경로가 유동적이거나 승격이 어려울 때 스칼라는 `CAST ... COLLATE utf8mb4_bin` 함수 인덱스, 배열은 다중값 인덱스를 쓴다. 어느 방식이든 인덱스가 원본 쓰기의 실패 원인이 될 수 있다는 점(1406, 3907)을 배포 전 데이터 길이 조사로 걸러 낸다.

검증 환경: MySQL 8.0.46(Ubuntu 24.04 패키지), InnoDB, 기본 설정, 서버·연결 문자셋 utf8mb4. 실행 시간은 단일 실행 값이고 `rows` 는 통계 추정치라 실행마다 조금씩 달라진다.

## 참고

- MySQL 8.0 Reference Manual, CREATE TABLE and Generated Columns: https://dev.mysql.com/doc/refman/8.0/en/create-table-generated-columns.html
- MySQL 8.0 Reference Manual, Optimizer Use of Generated Column Indexes: https://dev.mysql.com/doc/refman/8.0/en/generated-column-index-optimizations.html
- MySQL 8.0 Reference Manual, CREATE INDEX (Functional Key Parts, Multi-Valued Indexes): https://dev.mysql.com/doc/refman/8.0/en/create-index.html
- MySQL 8.0 Reference Manual, Online DDL Operations: https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html
- MySQL 8.0 Reference Manual, Secondary Indexes and Generated Columns: https://dev.mysql.com/doc/refman/8.0/en/create-table-secondary-indexes.html
- Oracle Database Concepts, Function-Based Indexes: https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/indexes-and-index-organized-tables.html
