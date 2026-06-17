Notion 원본: https://www.notion.so/3825a06fd6d381d0b7e9e2cdae8b82b3

# MySQL InnoDB Locking — Record · Gap · Next-Key Lock과 팬텀 방지 및 데드락 탐지

> 2026-06-17 신규 주제 · 확장 대상: SQLD, Oracle

## 학습 목표

- InnoDB의 Record / Gap / Next-Key Lock 세 종류를 인덱스 구조와 함께 구분한다
- REPEATABLE READ에서 Next-Key Lock이 팬텀 리드를 막는 메커니즘을 실험으로 확인한다
- 락 호환성, intention lock, insert intention lock의 역할을 설명한다
- 데드락 발생 패턴을 재현하고 `SHOW ENGINE INNODB STATUS`로 진단한다

## 1. 잠금은 행이 아니라 인덱스 레코드에 걸린다

InnoDB의 가장 중요한 사실: 행 잠금은 테이블의 데이터가 아니라 **인덱스 레코드**에 건다. 모든 InnoDB 테이블은 클러스터형 인덱스(PK)로 저장되며, 보조 인덱스 조회 시 보조 인덱스 레코드와 해당 PK 레코드 양쪽에 락이 걸릴 수 있다. WHERE 절이 인덱스를 타지 못하면 스캔하는 **모든 레코드**에 락이 걸려 사실상 테이블 락처럼 동작한다. 이것이 "인덱스 없는 UPDATE가 동시성을 죽이는" 근본 원인이다.

```sql
-- 인덱스가 없는 컴럼으로 UPDATE → 전체 행 스캔, 전부 락
UPDATE orders SET status = 'X' WHERE memo = 'urgent';  -- memo 인덱스 없음 → 위험

-- PK/인덱스로 좌히면 해당 레코드만 락
UPDATE orders SET status = 'X' WHERE id = 100;
```

## 2. 세 가지 락 — Record, Gap, Next-Key

InnoDB의 행 수준 락은 세 형태로 나뉘다.

**Record Lock**은 인덱스 레코드 자체에 거는 락이다. `WHERE id = 10`처럼 유니크 인덱스로 한 행을 정확히 찾으면 그 레코드만 잠근다.

**Gap Lock**은 인덱스 레코드 *사이의 간격*에 거는 락이다. 실제 존재하는 행이 아니라 "그 범위에 새 행이 들어오지 못하게" 막는다. 예를 들어 id 값이 (10, 20)인 두 행 사이의 갑을 잠그면 id=15 INSERT가 차단된다.

**Next-Key Lock**은 Record Lock + 그 레코드 앞쪽 Gap Lock의 결합이다. InnoDB의 기본 락 단위로, `(이전 레코드, 현재 레코드]` 형태의 반열린 구간을 잠그다. REPEATABLE READ에서 범위 검색의 기본 동작이다.

```
인덱스 값:   ...  10        20        30  ...
             갑   |갑|      |갑|       |갑|
Next-Key Lock (...,10] = 레코드10 + 그 왼쪽 갑(이전값,10)
```

## 3. 팬텀 리드 방지 — Next-Key Lock의 진짜 목적

SQL 표준에서 REPEATABLE READ는 팬텀 리드(같은 범위 쿼리가 두 번째 실행에서 새 행을 보는 현상)를 허용하지만, InnoDB는 Next-Key Lock으로 잠금 읽기에서 팬텀을 막는다. 갑을 잠가 범위 안으로의 INSERT를 차단하기 때문이다.

```sql
-- 세션 A (REPEATABLE READ)
START TRANSACTION;
SELECT * FROM orders WHERE id BETWEEN 10 AND 30 FOR UPDATE;
-- (10,30] 범위에 Next-Key Lock → 갑이 잠김

-- 세션 B
INSERT INTO orders (id, status) VALUES (25, 'NEW');
-- ❌ 블록됨: 세션 A가 커밋/롤백할 때까지 대기 (팬텀 방지)
```

주의: 이 보호는 **잠금 읽기(`FOR UPDATE`, `FOR SHARE`)** 와 쓰기에만 적용된다. 일반 `SELECT`는 MVCC 스냅샷(consistent read)으로 동작해 락 없이 일관된 뷰를 보므로, 잠금 읽기와 일반 읽기가 같은 트랜잭션에서 다른 결과를 볼 수 있다.

## 4. 유니크 검색에서는 Gap Lock이 사라진다

성능을 위해 InnoDB는 가능하면 락 범위를 좁힌다. **유니크 인덱스로 정확히 한 행을 찾는 동등 조건**에서는 Next-Key Lock이 Record Lock으로 강등되어 갑을 잠그지 않는다. 갑을 잠글 이유가 없기 때문이다(유니크하므로 그 값에 중복 INSERT 불가).

```sql
-- id가 PK(유니크), 값 15는 존재 → Record Lock만 (갑 없음)
SELECT * FROM orders WHERE id = 15 FOR UPDATE;

-- 존재하지 않는 값 → 그 갑에 Gap Lock (INSERT 방지)
SELECT * FROM orders WHERE id = 16 FOR UPDATE;  -- (15,20) 갑 잠금
```

반면 보조 인덱스(non-unique)나 범위 조건에서는 Next-Key Lock이 유지된다. 이 차이를 모르면 "왜 같은 쿼리인데 어떤 건 INSERT를 막고 어떤 건 안 막지?"에서 혼란이 온다.

## 5. Intention Lock과 Insert Intention Lock

테이블 락과 행 락을 함께 쓰려면 호환성 판단이 필요하다. InnoDB는 **Intention Lock**(IS/IX)이라는 테이블 수준 의도 표시로 이를 효율화한다. 행에 공유 락을 걸기 전 테이블에 IS, 배타 락 전엔 IX를 먼저 건다. 다른 트랜잭션이 테이블 전체 락(LOCK TABLES)을 요청하면 intention lock만 보고 충돌 여부를 빠르게 판단한다. Intention lock끼리는 항상 호환된다.

**Insert Intention Lock**은 INSERT가 갑에 진입하기 전 거는 특수한 갑 락이다. 같은 갑이라도 서로 다른 위치에 INSERT하는 트랜잭션끼리는 충돌하지 않게 해 동시 삽입 성능을 높인다. 하지만 다른 트랜잭션이 그 갑에 Gap Lock을 들고 있으면 Insert Intention Lock은 대기한다 — 이것이 §3에서 INSERT가 막힌 이유다.

```
락 호환성 (요약): 
        | Gap | Insert Intention | Record(X)
Gap     |  O  |        O         |    O
II      |  X  |        O         |    O   (Gap이 있으면 II 대기)
```

## 6. 데드락 재현 — 락 획득 순서의 교차

데드락은 두 트랜잭션이 서로가 가진 락을 기다릴 때 발생한다. 가장 흔한 패턴은 같은 행들을 **다른 순서**로 잠그는 것이다.

```sql
-- 세션 A                          -- 세션 B
START TRANSACTION;                 START TRANSACTION;
UPDATE acct SET bal=bal-100        UPDATE acct SET bal=bal-50
  WHERE id=1;  -- A: row1 X락         WHERE id=2;  -- B: row2 X락
UPDATE acct SET bal=bal+100        UPDATE acct SET bal=bal+50
  WHERE id=2;  -- A: row2 대기        WHERE id=1;  -- B: row1 대기 → 데드락!
```

InnoDB는 데드락을 자동 탐지(wait-for graph의 사이클 검출)하고 비용이 더 작은 트랜잭션을 victim으로 롤백해 `ERROR 1213 (40001): Deadlock found`를 던진다. 애플리케이션은 이 에러를 잡아 **재시도**해야 한다. 회피 전략은 (1) 모든 트랜잭션이 행을 동일한 순서(예: PK 오름차)로 잠그기, (2) 트랜잭션을 짧게 유지, (3) 적절한 인덱스로 락 범위 최소화다.

```java
// 데드락 재시도 — Spring 의사코드, 멱등성 보장 전제
int maxRetry = 3;
for (int attempt = 0; attempt < maxRetry; attempt++) {
	try {
		transferService.transfer(fromId, toId, amount);
		break;
	} catch (DeadlockLoserDataAccessException e) {
		if (attempt == maxRetry - 1) {
			throw e;
		}
		Thread.sleep(50L * (attempt + 1)); // backoff
	}
}
```

## 7. 진단 — SHOW ENGINE INNODB STATUS와 performance_schema

데드락 직후 `SHOW ENGINE INNODB STATUS`의 `LATEST DETECTED DEADLOCK` 섹션에 두 트랜잭션이 어떤 락을 들고 무엇을 기다렸는지, 어느 쪽이 롤백됐는지가 찍힌다. 운영에서는 `innodb_print_all_deadlocks=ON`으로 모든 데드락을 에러 로그에 남긴다.

진행 중인 락 대기는 performance_schema로 본다.

```sql
-- 현재 잠금 보유/대기 현황 (MySQL 8.0+)
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;

-- 어떤 트랜잭션이 누구를 막는지
SELECT r.trx_id AS waiting, b.trx_id AS blocking
FROM sys.innodb_lock_waits w
JOIN information_schema.innodb_trx r ON r.trx_id = w.waiting_trx_id
JOIN information_schema.innodb_trx b ON b.trx_id = w.blocking_trx_id;
```

`innodb_lock_wait_timeout`(기본 50초)은 데드락이 아닌 단순 대기의 한계 시간이다. OLTP에서는 보통 짧게(5~10초) 줄여 빠른 실패-재시도 패턴을 만든다.

## 8. 격리 수준에 따른 락 동작 차이

| 격리 수준 | Gap Lock | 팬텀 | 비고 |
|---|---|---|---|
| READ COMMITTED | 거의 없음(반-일관 읽기) | 발생 가능 | 락 범위 작아 동시성↑, 복제는 RBR 필요 |
| REPEATABLE READ (기본) | 있음(Next-Key) | 잠금 읽기에서 방지 | InnoDB 기본 |
| SERIALIZABLE | 강함 | 방지 | 일반 SELECT도 공유 락으로 승격 |

운영 팁: READ COMMITTED는 Gap Lock을 거의 쓰지 않아 데드락 빈도가 낮고 동시성이 높다. 팬텀을 애플리케이션이 감내할 수 있고 로우 기반 복제(binlog_format=ROW)를 쓴다면 RC로 낮추는 것이 흔한 튜닝이다. 반대로 정합성이 핵심인 금융 트랜잭션은 RR을 유지하고 락 순서를 정렬해 데드락을 구조적으로 차단한다.

## 9. 외래 키 락과 자식-부모 경합

자주 간과되는 데드락 원인이 외래 키(FK) 검사에 따른 락이다. 자식 테이블에 INSERT/UPDATE가 일어나면 InnoDB는 참조하는 부모 행이 존재하고 변경되지 않음을 보장하기 위해 **부모 행에 공유 락(S Lock)** 을 건다. 부모를 UPDATE하는 트랜잭션과 자식을 INSERT하는 트랜잭션이 서로 다른 순서로 진행되면 데드락이 난다.

```sql
-- orders.member_id → member.id 외래 키
-- 세션 A: 회원 정보 수정 (부모 X락)
UPDATE member SET grade = 'VIP' WHERE id = 7;

-- 세션 B: 그 회원의 주문 추가 (부모 member.id=7 에 S락 요청)
INSERT INTO orders (member_id, status) VALUES (7, 'NEW'); -- A의 X락과 충돌 가능
```

대량 배치에서 같은 부모를 참조하는 자식 INSERT가 몰리면 부모 행 S락 경합이 병목이 된다. 완화책은 (1) 배치를 부모 키 순으로 정렬해 락 순서를 일정하게, (2) 트랜잭션을 잘게 쪼개 락 보유 시간 단축, (3) 정합성을 애플리케이션에서 보장할 수 있다면 FK 제약을 논리적으로만 두고 물리 제약을 제거하는 것(트레이드오프 — 무결성 보증이 약해짐)이다.

## 10. 락 모니터링과 운영 지표

운영에서 락 경합을 상시 관찰하려면 몇 가지 지표를 본다. `SHOW ENGINE INNODB STATUS`의 `TRANSACTIONS` 섹션에서 `LOCK WAIT` 상태로 오래 머무르는 트랜잭션, `Trx read view`가 오래 열려 있어 purge를 막는 long-running 트랜잭션을 찾는다. `information_schema.innodb_trx`의 `trx_started`로 오래된 트랜잭션을 잡고, `trx_rows_locked`로 한 트랜잭션이 잠근 행 수를 본다.

```sql
-- 60초 이상 진행 중인 트랜잭션 (락 점유·purge 지연 의심)
SELECT trx_id, trx_started, trx_rows_locked, trx_query
FROM information_schema.innodb_trx
WHERE trx_started < NOW() - INTERVAL 60 SECOND
ORDER BY trx_started;
```

장기 트랜잭션은 락뿐 아니라 MVCC undo 누적(History List Length 증가)을 유발해 전체 성능을 끌어내린다. `SHOW ENGINE INNODB STATUS`의 `History list length`가 수십만~수백만으로 치솔으면 어딘가에 커밋되지 않은 오래된 read view가 있다는 신호다. 흔한 원인은 애플리케이션의 트랜잭션 누수(커넥션을 빌려놓고 커밋/롤백을 안 함)나 사람이 콘솔에서 열어둔 `START TRANSACTION`이다. 자동 커밋 비활성화(`autocommit=0`) 환경에서는 단순 SELECT도 트랜잭션을 열어 read view를 유지하므로 주의한다.

운영 권장 설정 요약: OLTP에서는 `innodb_lock_wait_timeout`을 짧게(5~10초) 두어 빠른 실패-재시도를 유도하고, `innodb_deadlock_detect=ON`(기본)으로 즉시 탐지하되 초고동시성 환경에서 데드락 탐지 비용이 부담되면 탐지를 끄고 timeout에만 의존하는 선택지도 있다(트레이드오프 — 데드락 해소가 timeout만큼 느려짐). 어느 경우든 애플리케이션의 재시도 로직과 멱등성 설계가 전제다.

## 참고

- MySQL Reference Manual 8.0 — "InnoDB Locking" (15.7.1)
- MySQL Reference Manual — "Phantom Rows", "Deadlocks in InnoDB"
- High Performance MySQL, 4th Edition (O'Reilly) — Locking chapter
- Jeremy Cole, "InnoDB locking" blog series (blog.jcole.us)
