Notion 원본: https://www.notion.so/34c5a06fd6d381c787e4cc409da523e7

# MySQL InnoDB Gap Lock과 Next-Key Lock 내부

> 2026-04-24 신규 주제 · 확장 대상: DataBase (Oracle / 최적화 기본 / PG MVCC 학습됨)

## 학습 목표

- InnoDB의 Record/Gap/Next-Key/Insert Intention Lock 구조를 구분한다
- REPEATABLE READ(RR)에서만 Gap Lock이 활성화되는 이유를 팬텀 반실에 관점으로 설명한다
- `SELECT ... FOR UPDATE`가 익덕함수 과도 잠금(over-locking)을 유발하는 케이스를 실측한다
- SHOW ENGINE INNODB STATUS로 데드락 lock graph를 읽어낸다

---

## 1. Lock 종류 개괄

InnoDB의 록 Lock은 세 가지 축으로 구분된다. (1) 모드: Shared(S) / Exclusive(X), (2) 범위: 테이블 / 레코드 / 갭, (3) 의도(Intention). 영객 별로 기믹히 어렵기로 유명한 게 Gap 계열이다. Oracle에서 넘어온 사람은 "엄의 락만 있지 갭 락은 없다"는 개념에 익숙해 측정하기 어렵다.

## 2. Record Lock

인덱스 레코드 자체에 걸리는 락. PK 기반 equality 조회에 `FOR UPDATE`를 붙이면 해당 하나의 레코드에만 걸린다.

```sql
-- tx1
BEGIN;
SELECT * FROM orders WHERE id = 10 FOR UPDATE;  -- record lock on id=10
-- tx2 (지금 대기)
SELECT * FROM orders WHERE id = 10 FOR UPDATE;
```

다른 레코드(id=11, 12 ...)는 영향 없음.

## 3. Gap Lock

인덱스 레코드 사이의 **빈 공간**에 걸리는 락. 목적은 "팬텀 리드"의 방지다.

```sql
CREATE TABLE orders (id INT PK, status VARCHAR(16), KEY idx_status(status));
INSERT INTO orders VALUES (1,'NEW'), (5,'NEW'), (10,'PAID');

-- tx1
BEGIN;
SELECT * FROM orders WHERE status = 'NEW' FOR UPDATE;
-- tx2
INSERT INTO orders VALUES (7, 'NEW');  -- 대기! (gap lock (5, 10))
```

외관상 "INSERT 할 row"가 기존에 없으니 경쟁이 없는 것 같았지만, tx1이 WHERE로 효과를 보아야 할 범위에 새 row가 끼어들 가능성이 있으므로 InnoDB는 "그 범위 전체"를 잠근다.

## 4. Next-Key Lock

Next-Key Lock = Record Lock + 그 레코드 **앞** Gap Lock. 즉 구간 `(previous_record, this_record]`에 걸린다. InnoDB RR에서 범위 조회 + FOR UPDATE의 기본동작이 이거다.

```sql
SELECT * FROM orders WHERE id >= 5 AND id <= 10 FOR UPDATE;
-- 걸리는 lock: next-key (-inf..1] 아님, (5..10] 위주로 간주되었더라도
-- 실제는 (1, 5], (5, 10], 그리고 10 미만 근처 인덱스 상태에 따라 더 넓을 수 있음.
```

실전 함정은 WHERE가 인덱스가 아닌 컬럼을 거칠 때. 그럴 때는 **전체 테이블을 스캔**하면서 거치는 모든 인덱스 레코드에 next-key lock을 건다. 즉, 말 그대로 전체 테이블 잠금.

## 5. Insert Intention Lock

Gap에 새 row를 넣으려고 할 때 획득하는 특수 lock. 같은 gap에 서로 다른 key를 넣는 두 트랜잭션은 서로 충돌하지 않는다. 에러로 `Lock wait timeout`이 뜨는 문제의 원인을 파악할 때 이 식별이 중요하다.

## 6. 격리 수준과 Lock 관계

| 격리수준 | Gap Lock | 팬텀 리드 | Non-repeatable Read |
|---|---|---|---|
| READ UNCOMMITTED | 안 걸림 | 발생 | 발생 |
| READ COMMITTED | 안 걸림 | 발생 | 발생 |
| REPEATABLE READ (MySQL 기본) | 걸림 | 차단 | 차단 |
| SERIALIZABLE | 걸림 | 차단 | 차단 |

활용 판단: 운영환경에서 Gap Lock으로 인한 데드락/대기가 익덕함수로 터진다면 격리 수준을 `READ COMMITTED`로 낮추는 것이 현실적인 해법이 되기도 한다. 이경우 애플리케이션 레벨에서 Ghost read를 허용할 수 있는지 파악해야 한다.

## 7. Deadlock Detection

```sql
SET GLOBAL innodb_print_all_deadlocks = ON;
SHOW ENGINE INNODB STATUS\G
```

출력 예:

```
LATEST DETECTED DEADLOCK
------------------------
*** (1) TRANSACTION: ...
LOCK WAIT holds lock(s):
  RECORD LOCKS space id N page no X n bits 72 index PRIMARY of table `orders`
  trx id 4242 lock_mode X locks rec but not gap waiting
*** (2) TRANSACTION: ...
  HOLDS THE LOCK(S) ...
  WAITING FOR THIS LOCK TO BE GRANTED ...
```

`lock_mode X locks rec but not gap` → Record Lock만. `lock_mode X` (suffix 없음) → next-key. `lock_mode X locks gap before rec` → Gap only. Waits-for graph를 그리면 순환 경로가 드러난다.

## 8. 사례: 계좌 잔고 데드락

```sql
-- tx1
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
-- tx2
UPDATE accounts SET balance = balance - 50  WHERE id = 2;
UPDATE accounts SET balance = balance + 50  WHERE id = 1;
```

대각선상의 예제. 해법: (a) 액세스 순서를 **id 오름차순으로 고정**, (b) `FOR UPDATE`로 미리 두 행을 한 번에 잠금, (c) 애플리케이션 레벨에 글로벌 lock 무리하지 않고 DB 레벨로 위임.

## 9. 성능 관찰 포인트

- `innodb_lock_wait_timeout` 기본 50초. 웹 트랜픽 경로는 짧게(2–5초) 잡는 게 권장
- `SHOW ENGINE INNODB STATUS`와 `information_schema.innodb_trx`, `innodb_locks`, `innodb_lock_waits`
- Performance Schema `data_locks` 테이블(8.0+)에서 lock 상세 내용
- DataDog/New Relic 플러그인은 근본 추적에 부족—slow log + binlog 기반 분석 병행

## 참고

- MySQL 공식 문서 — InnoDB Locking: https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html
- Jeremy Cole — "InnoDB Locking Explained": https://blog.jcole.us/
- Percona Blog — "MySQL Deadlock Troubleshooting"
- "High Performance MySQL" 4th ed. (O'Reilly)
