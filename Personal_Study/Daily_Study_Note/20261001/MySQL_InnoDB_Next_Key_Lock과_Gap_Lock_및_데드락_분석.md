Notion 원본: https://app.notion.com/p/3ec5a06fd6d381e2aa44f163c883af1e?pvs=204

# MySQL InnoDB Next-Key Lock과 Gap Lock 동작 및 REPEATABLE READ 데드락 분석 기법

> 2026-10-01 신규 주제 · 확장 대상: MySQL 트랜잭션·격리 수준

## 학습 목표

- REPEATABLE READ에서 조건별로 잠기는 인덱스 레코드와 갭 범위를 예측한다
- performance_schema.data_locks로 실제 보유 잠금과 대기 관계를 조회한다
- SHOW ENGINE INNODB STATUS의 데드락 로그에서 순환 대기 원인을 역추적한다
- Gap Lock 기반 데드락을 격리 수준, 쿼리 패턴, 인덱스 설계로 줄이는 방법을 비교한다

## 1. 잠금은 행이 아니라 인덱스 레코드에 걸린다

InnoDB의 행 수준 잠금은 테이블의 "행"이 아니라 인덱스 레코드에 걸린다. 이 사실을 놓치면 이후의 모든 분석이 어긋난다. 클러스터드 인덱스(PK)에 걸리는 잠금이 가장 흔하지만, 보조 인덱스를 타고 들어간 쿼리는 보조 인덱스 레코드에 먼저 잠금을 걸고, 이어서 대응하는 PK 레코드에도 잠금을 건다. 인덱스가 전혀 쓸 수 없는 조건이면 InnoDB는 클러스터드 인덱스를 전부 훑으면서 지나간 모든 레코드에 잠금을 건다. 그래서 `UPDATE ... WHERE 인덱스 없는 컬럼 = ?` 한 문장이 사실상 테이블 전체 쓰기 잠금처럼 동작하고, 데드락과 대기 폭증의 가장 흔한 출발점이 된다.

MySQL 공식 문서(InnoDB Locking)가 정의하는 잠금 종류를 갭 관련 위주로 정리하면 다음과 같다.

| 잠금 | 보호 대상 | 핵심 성질 |
|---|---|---|
| Record Lock | 인덱스 레코드 하나 | 해당 레코드의 수정, 삭제, 잠금 읽기를 막는다 |
| Gap Lock | 인덱스 레코드 사이의 빈 구간(레코드 자체는 제외) | 구간 안으로의 INSERT를 막는다. 순수하게 "금지" 목적이다 |
| Next-Key Lock | 레코드 + 그 레코드 직전의 갭 | Record Lock과 Gap Lock의 결합. 반열림 구간 (이전 레코드, 현재 레코드]를 보호한다 |
| Insert Intention Lock | INSERT 직전에 거는 갭 잠금의 특수형 | 같은 갭에 서로 다른 위치로 INSERT하는 트랜잭션끼리는 충돌하지 않는다 |

Gap Lock의 가장 중요한 특징은 서로 충돌하지 않는다는 점이다. 공식 문서에 따르면 갭 잠금은 다른 트랜잭션이 그 갭에 INSERT하는 것만 막을 뿐이므로, 트랜잭션 A가 가진 갭 잠금과 트랜잭션 B가 가진 같은 갭의 갭 잠금은 공존한다. S 갭 잠금과 X 갭 잠금의 구분도 사실상 의미가 없다. 이 공존 성질이 뒤에서 볼 데드락의 핵심 재료다. 인덱스의 마지막 레코드보다 큰 영역은 supremum 의사 레코드(pseudo-record)로 표현되며, 마지막 레코드 뒤의 갭을 막으려면 supremum에 next-key lock을 건다.

테이블에 갭이 존재하는 이유를 이해하려면 Gap Lock이 왜 필요한지부터 알아야 한다. REPEATABLE READ에서 같은 범위를 두 번 잠금 읽기(FOR UPDATE, FOR SHARE)했을 때 두 번째에 새 행이 보이는 현상이 팬텀 읽기다. InnoDB는 읽은 범위의 갭까지 잠가서 다른 트랜잭션의 INSERT를 막아 이를 방지한다. 또한 statement 기반 복제에서 바이너리 로그의 문장 순서를 재생해도 소스와 같은 결과가 나오도록 하는 데에도 갭 잠금이 쓰인다. 반대로 말하면, 이 보호가 필요 없는 환경(READ COMMITTED + ROW 포맷)에서는 갭 잠금을 끌 수 있고 그것이 데드락 완화 수단이 된다.

## 2. REPEATABLE READ에서 잠금 범위가 정해지는 규칙

REPEATABLE READ는 InnoDB의 기본 격리 수준이다. 이 수준에서 UPDATE, DELETE, SELECT ... FOR UPDATE/FOR SHARE는 인덱스를 탐색하는 동안 지나가는 인덱스 레코드에 기본적으로 next-key lock을 건다. 예외는 고유 인덱스(PK 포함)의 모든 컬럼에 등호 조건을 줘서 레코드 하나를 정확히 찾는 경우로, 이때는 Record Lock만 걸리고 갭은 잠기지 않는다. 이 규칙으로 아래 실험 테이블에서 각 쿼리의 잠금 범위를 예측해 보자.

```sql
CREATE TABLE t_order (
  id      INT NOT NULL PRIMARY KEY,
  user_id INT NOT NULL,
  amount  INT NOT NULL,
  KEY idx_user (user_id)
) ENGINE=InnoDB;

INSERT INTO t_order VALUES
  (10, 100, 5000),
  (20, 200, 7000),
  (30, 200, 9000),
  (40, 300, 1000);
```

| 쿼리(REPEATABLE READ, FOR UPDATE) | 잠기는 범위 | 이유 |
|---|---|---|
| `WHERE id = 20` | PK 20의 Record Lock만 | 고유 인덱스 등호 조건으로 레코드 하나가 확정된다 |
| `WHERE id = 25` (없는 값) | PK 갭 (20, 30) | 값이 없으므로 그 값이 들어갈 갭이 잠긴다 |
| `WHERE id BETWEEN 15 AND 25` | (10, 20], (20, 30] | 범위 스캔이 20에서 시작해 25를 넘는 첫 레코드 30에서 멈추므로 30까지의 next-key lock |
| `WHERE user_id = 200` | idx_user의 (100,200], (200,200], (200,300) 갭, 그리고 PK 20, 30의 Record Lock | 비고유 인덱스는 일치 구간 전체와 다음 레코드 직전 갭까지 잠근다 |
| `WHERE amount = 7000` (인덱스 없음) | PK 전체 레코드의 next-key lock과 supremum | 풀 스캔이므로 지나간 모든 레코드가 잠긴다 |

네 번째 행이 특히 중요하다. 비고유 인덱스에서 `user_id = 200`을 찾으면 일치하는 레코드가 둘이라도 잠금은 그 둘에서 끝나지 않고, 다음 레코드(300) 앞의 갭까지 이어진다. 그래서 다른 트랜잭션이 `user_id = 250`인 주문을 INSERT하려 하면 대기한다. 실무에서 "내 쿼리는 200번 사용자 행만 건드리는데 왜 250번 사용자 INSERT가 막히는가"라는 질문의 정답이 이것이다. 마지막 행도 놓치기 쉽다. REPEATABLE READ에서는 WHERE 조건에 맞지 않아 결과에서 제외되는 행도 스캔 과정에서 지나갔다면 트랜잭션이 끝날 때까지 잠금이 유지된다. READ COMMITTED에서는 MySQL이 WHERE를 평가한 뒤 조건에 맞지 않는 행의 잠금을 해제한다(UPDATE는 semi-consistent read도 사용한다). 이 차이가 두 격리 수준의 잠금 경합 규모를 가르는 가장 큰 요인이다.

일반 SELECT(잠금 없는 일관된 읽기)는 이 모든 논의와 무관하다. 일관된 읽기는 MVCC 스냅샷에서 읽으므로 잠금을 걸지도 대기하지도 않는다.

## 3. performance_schema.data_locks로 잠금 관찰하기

예측한 잠금이 실제와 맞는지 확인하려면 MySQL 8.0의 performance_schema.data_locks와 data_lock_waits를 쓴다. 5.7까지 쓰던 information_schema.INNODB_LOCKS, INNODB_LOCK_WAITS는 8.0에서 제거되었고, sys 스키마의 innodb_lock_waits 뷰가 이 두 테이블을 조인해 대기자와 차단자를 한 줄로 보여준다. 세션 하나에서 트랜잭션을 열어 잠금을 잡은 채로 두고, 다른 세션에서 조회하는 방식이 가장 단순하다.

```sql
-- 세션 1: 잠금을 잡고 커밋하지 않는다
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT * FROM t_order WHERE id BETWEEN 15 AND 25 FOR UPDATE;

-- 세션 2: 보유 잠금 확인
SELECT object_name, index_name, lock_type, lock_mode, lock_status, lock_data
FROM performance_schema.data_locks
WHERE object_name = 't_order'
ORDER BY index_name, lock_data;
```

LOCK_MODE 컬럼의 표기가 해석의 열쇠다. 접미사가 없는 `X`는 next-key lock, `X,REC_NOT_GAP`은 Record Lock, `X,GAP`은 순수 Gap Lock, `X,GAP,INSERT_INTENTION`은 삽입 의도 잠금을 뜻한다. 테이블 수준의 의도 잠금은 LOCK_TYPE이 TABLE이고 `IX`, `IS`로 나타난다. 위 쿼리를 PRIMARY 인덱스 기준으로 보면 `lock_data`가 20과 30인 행이 `X`로 보이는 것이 정상이며, 이것이 (10,20]과 (20,30]의 next-key lock에 해당한다. 대기 관계는 아래 쿼리로 본다.

```sql
-- 누가 누구를 막고 있는가 (8.0)
SELECT r.trx_id          AS waiting_trx,
       r.trx_mysql_thread_id AS waiting_thread,
       r.trx_query       AS waiting_query,
       b.trx_id          AS blocking_trx,
       b.trx_mysql_thread_id AS blocking_thread,
       b.trx_query       AS blocking_query
FROM performance_schema.data_lock_waits w
JOIN information_schema.innodb_trx r ON r.trx_id = w.requesting_engine_transaction_id
JOIN information_schema.innodb_trx b ON b.trx_id = w.blocking_engine_transaction_id;

-- 같은 정보를 요약해 주는 sys 뷰
SELECT * FROM sys.innodb_lock_waits\G
```

주의할 점은 data_locks가 현재 시점의 스냅샷이라, InnoDB가 즉시 해소해 버린 데드락은 여기서 복원할 수 없다는 것이다. 그 용도로는 다음 장의 데드락 로그가 필요하다. 잠금이 많을 때는 object_name, index_name으로 범위를 좁혀 조회해야 하며, 조회 비용은 환경에 따라 다름이다.

## 4. 데드락 시나리오 A: 갭 잠금 공존 후 INSERT

REPEATABLE READ에서 가장 자주 보이는 데드락은 "존재 확인 후 없으면 INSERT" 패턴에서 나온다. 코드는 직관적이다. 같은 키를 FOR UPDATE로 조회해서 없으면 INSERT한다. 단일 스레드라면 문제가 없지만, 두 트랜잭션이 같은 비어 있는 키에 동시에 들어오면 다음과 같이 순환 대기가 만들어진다.

```sql
-- t_order에 id=25는 없다 (갭 (20,30))
-- 세션 1
START TRANSACTION;
SELECT * FROM t_order WHERE id = 25 FOR UPDATE;   -- 빈 결과, 갭 (20,30)에 X Gap Lock

-- 세션 2
START TRANSACTION;
SELECT * FROM t_order WHERE id = 25 FOR UPDATE;   -- 빈 결과, 같은 갭에 X Gap Lock. 공존하므로 대기 없음

-- 세션 1
INSERT INTO t_order VALUES (25, 100, 1000);       -- 삽입 의도 잠금 요청. 세션 2의 갭 잠금 때문에 대기

-- 세션 2
INSERT INTO t_order VALUES (25, 100, 1000);       -- 세션 1의 갭 잠금과 충돌, 순환 대기
-- ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction
```

원리를 풀어 쓰면 이렇다. 두 세션의 Gap Lock은 서로 호환되므로 SELECT 단계에서는 아무도 기다리지 않는다. 그러나 INSERT는 삽입 의도 잠금을 요구하고, 이 잠금은 다른 트랜잭션이 가진 갭 잠금과 충돌한다. 세션 1은 세션 2의 갭 잠금이 풀리길 기다리고 세션 2는 세션 1의 갭 잠금이 풀리길 기다리니 InnoDB의 데드락 검출기가 순환을 발견해 한쪽을 롤백한다. 이 데드락은 로직상 "버그"라기보다 REPEATABLE READ의 정상 동작이다. 동일한 문제가 `DELETE FROM t WHERE id = 25` 후 INSERT 패턴에서도 재현되는데, 지울 행이 없어도 갭 잠금은 걸리기 때문이다.

피해 가는 방법은 의도에 따라 달라진다. 단순히 "있으면 갱신, 없으면 삽입"이라면 선조회 없이 `INSERT ... ON DUPLICATE KEY UPDATE`를 쓰는 것이 낫다. 이 문장은 갭 잠금 공존 단계를 거치지 않고 고유 인덱스 충돌을 엔진이 원자적으로 판정한다. 다만 이 문장도 고유 인덱스가 둘 이상이면 어느 인덱스에서 충돌하느냐에 따라 의도하지 않은 행을 갱신할 수 있고, 충돌 시 AUTO_INCREMENT 값이 소모되는 부작용이 있으니 키 설계를 함께 점검해야 한다.

## 5. 데드락 시나리오 B: 중복 키 검사의 S 잠금과 인덱스 접근 순서

INSERT는 삽입 전에 중복 키 검사를 하고, 이때 해당 인덱스 레코드에 공유(S) 잠금을 건다. 공식 문서의 데드락 예제가 이 상황을 다룬다. 세션 1이 PK=1을 INSERT한 뒤 아직 커밋하지 않은 상태에서 세션 2와 세션 3이 같은 PK=1을 INSERT하면, 둘 다 세션 1의 X 잠금 뒤에서 S 잠금을 기다리는 큐에 선다. 이때 세션 1이 ROLLBACK하면 세션 2와 3이 동시에 S 잠금을 얻는데, 둘 다 이어서 X 잠금이 필요하므로 서로의 S 잠금에 막혀 데드락이 발생하고 하나가 롤백된다. 해결은 중복 가능성이 있는 INSERT를 한 경로로 직렬화하거나, 중복 시 예외를 정상 흐름으로 받아들이고 재시도하는 것이다.

또 하나의 전형은 같은 행 집합을 서로 다른 인덱스 순서(보조 인덱스 경유와 PK 직접 지정 등)로 잠가 교차 대기가 생기는 경우다. 이는 갭 잠금과 무관해 READ COMMITTED에서도 발생하므로, 갭 잠금 기인 데드락과 잠금 순서 기인 데드락을 먼저 구분하는 것이 분석의 출발점이다.

## 6. 데드락 로그 읽는 법

이미 끝난 데드락은 `SHOW ENGINE INNODB STATUS`의 LATEST DETECTED DEADLOCK 절에 가장 최근 1건만 남는다. 전부 남기려면 `SET GLOBAL innodb_print_all_deadlocks = ON;`으로 에러 로그에 기록한다. 로그에서 볼 것은 세 가지다. 각 트랜잭션이 실행 중이던 SQL, HOLDS THE LOCK(S) 절의 보유 잠금, WAITING FOR THIS LOCK TO BE GRANTED 절의 대기 잠금이다.

```
*** (1) TRANSACTION: ... INSERT INTO t_order VALUES (25, 100, 1000)
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `test`.`t_order` trx id 1001 lock_mode X locks gap before rec
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index PRIMARY of table `test`.`t_order` trx id 1001 lock_mode X locks gap before rec insert intention waiting
*** (2) TRANSACTION: ... (동일 구조, 보유와 대기가 서로 반대)
*** WE ROLL BACK TRANSACTION (2)
```

(공간 ID, 트랜잭션 ID는 예시값이다.) 대기 잠금에 `insert intention waiting`이 있고 상대의 보유 잠금이 `gap before rec`이면 4장의 갭 공존 패턴이다. 양쪽이 모두 `rec but not gap`이면 잠금 순서 문제다. 희생자는 갱신한 행이 적은 쪽을 InnoDB가 선택해 롤백한다. 분석 절차는 (1) 두 SQL의 인덱스 경로를 EXPLAIN으로 확인하고 (2) 보유/대기 잠금 모드로 유형을 분류하고 (3) 단독 실행으로 재현해 data_locks로 검증하는 순서가 안전하다.

## 7. 설계 차원의 대응

대응은 비용이 작은 순서로 적용한다. 첫째, 앱은 데드락(에러 1213, SQLState 40001)을 정상 경로로 보고 트랜잭션 전체를 재시도한다. 이것만은 어떤 설계에서도 필수다. 둘째, 여러 행을 잠글 때 PK 오름차순 같은 일정한 순서를 강제하고, 트랜잭션을 짧게 유지하며, 잠금 조건에 맞는 인덱스를 둬서 스캔 범위를 줄인다. 셋째, 갭 잠금이 불필요하면 격리 수준을 READ COMMITTED로 낮춘다.

```java
// 데드락 재시도 (JDBC). 트랜잭션 전체를 다시 실행해야 한다
for (int attempt = 1; attempt <= 3; attempt++) {
    try (Connection c = ds.getConnection()) {
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        // ... SQL 실행 ...
        c.commit();
        break;
    } catch (SQLException e) {
        if (e.getErrorCode() != 1213 || attempt == 3) throw e;
        Thread.sleep(20L * attempt);
    }
}
```

## 8. Trade-off와 설정 선택

READ COMMITTED는 검색과 인덱스 스캔에서 갭 잠금을 쓰지 않아(외래 키, 중복 키 검사는 예외) 4장 유형을 크게 줄이지만, 팬텀 읽기가 허용되고 binlog_format은 ROW여야 한다(STATEMENT는 지원되지 않는다). 그리고 5장의 순서 기인 데드락은 줄지 않는다. 8.0의 `innodb_deadlock_detect`(기본 ON)는 높은 동시성에서 같은 행에 요청이 몰리면 검출 비용이 커질 수 있어 OFF로 두기도 하지만, 그 경우 `innodb_lock_wait_timeout`(기본 50초)이 유일한 탈출구이므로 값을 낮춰야 한다. 검출 방식 변경의 효과는 워크로드에 따라 다름이며 반드시 부하 테스트로 확인한다.

## 참고

- MySQL 8.0 Reference Manual, 17.7.1 InnoDB Locking (Record, Gap, Next-Key, Insert Intention)
- MySQL 8.0 Reference Manual, 17.7.3 Locks Set by Different SQL Statements in InnoDB
- MySQL 8.0 Reference Manual, 17.7.5 Deadlocks in InnoDB (Deadlock Detection, Minimizing and Handling Deadlocks)
- MySQL 8.0 Reference Manual, 28.12.13 Performance Schema Lock Tables (data_locks, data_lock_waits)
- MySQL 8.0 Reference Manual, 17.7.2.1 Transaction Isolation Levels
