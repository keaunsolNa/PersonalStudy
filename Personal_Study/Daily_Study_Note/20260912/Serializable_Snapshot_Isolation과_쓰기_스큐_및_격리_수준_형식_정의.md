Notion 원본: https://app.notion.com/p/3d95a06fd6d3813c9e2af331c53ce91a?pvs=204

# Serializable Snapshot Isolation과 쓰기 스큐 및 격리 수준 형식 정의

> 2026-09-12 신규 주제 · 확장 대상: PostgreSQL MVCC / 트랜잭션 격리 수준

## 학습 목표

- ANSI SQL 의 이상 현상 기반 격리 정의가 불완전한 이유를 규명한다
- 스냅샷 격리에서만 발생하는 쓰기 스큐를 재현하고 의존 그래프로 설명한다
- SSI 의 위험 구조(dangerous structure) 탐지 원리를 분석한다
- 낙관적 SSI 와 반관적 잠금의 비용을 바교해 어플리케이션 전략을 결정한다

## 1. ANSI SQL 정의의 결함

SQL-92 는 격리 수준을 세 가지 이상 현상의 허용 여부로 정의했다.

| 격리 수준 | Dirty Read | Non-repeatable Read | Phantom Read |
|---|---|---|---|
| READ UNCOMMITTED | 가능 | 가능 | 가능 |
| READ COMMITTED | 불가 | 가능 | 가능 |
| REPEATABLE READ | 불가 | 불가 | 가능 |
| SERIALIZABLE | 불가 | 불가 | 불가 |

이 표는 널리 인용되지만 **정의로서 불완전하다**. Berenson 들이 1995년 논문 "A Critique of ANSI SQL Isolation Levels" 에서 지적한 핵심은 두 가지다.

첫째, 현상의 서술이 모호하다. 원문의 phantom 정의는 특정 잠금 구현(2단계 잠금)을 암밀적으로 전제하며, 잠금을 쓰지 않는 MVCC 구현에는 그대로 적용되지 않는다.

둘째이자 더 중요한 문제는, **세 현상을 모든 마아도 직렬성이 보장되지 않는다**는 것이다. 스냅샷 격리(Snapshot Isolation, SI)가 그 반례다. SI 는 세 현상을 전부 마지만 직렬 가능하지 않다.

여기서 용어를 정리해야 한다. **직렬 가능성(serializability)** 은 "동시 실행 결과가 어떤 순차 실행 결과와 같다"는 조건이다. 이것이 유일하게 어플리케이션 개발자가 안심할 수 있는 기준이다 — 직렬 가능하면 트랜잭션 하나를 헰자 실행되는 것처럼 작성해도 된다. 반면 SI 를 쓰면 "내 트랜잭션이 읽은 것이 컴밋 시점에도 여전히 참인가"를 스스로 따지셔야 한다.

이름이 혼란을 키운다. Oracle 의 SERIALIZABLE 은 실제로는 스냅샷 격리다. PostgreSQL 의 REPEATABLE READ 도 스냅샷 격리다(ANSI 의 REPEATABLE READ 보다 강하다). MySQL InnoDB 의 REPEATABLE READ 는 일관 읽기와 갱 락 조합으로 또 다른 지점에 있다. 같은 키워드가 DBMS 마다 다른 보장을 뜼하므로, 설계 문서에는 격리 수준 이름이 아니라 **막고자 하는 이상 현상**을 적는 편이 안전하다.

## 2. 스냅샷 격리의 동작과 쓰기 스큐

SI 의 원칙은 단순하다. 트랜잭션은 시작 시점의 일관된 스냅샷을 읽고, 컴밋 시점에 "내가 쓴 행을 나와 겹치는 다른 트랜잭션도 쓴는가"만 검사한다. 겹치면 한쪽을 abort 한다(first-committer-wins). 읽기는 절대 차단되지 않고 쓰기끌리만 충돌한다.

**쓰기-쓰기 충돌만 검사**하는 것이 구멍이다. 두 트랜잭션이 **서로 다른 행을 쓰면서, 서로가 읽은 것을 무효화**하면 충돌 검사를 통과한다. 이것이 쓰기 스큐(write skew)다.

근태 도메인의 사례로 재현한다. "부서 당직은 최소 1명이 근무 중이어야 한다"는 제약이 있고, 두 직원이 동시에 당직 해제를 신십한다.

```sql
CREATE TABLE duty_roster (
	emp_id     VARCHAR(10) PRIMARY KEY,
	dept_code  VARCHAR(10) NOT NULL,
	duty_date  DATE NOT NULL,
	on_duty    BOOLEAN NOT NULL
);

INSERT INTO duty_roster VALUES
	('E001', 'D100', DATE '2026-09-12', true),
	('E002', 'D100', DATE '2026-09-12', true);
```

```sql
-- 세션 A
BEGIN ISOLATION LEVEL REPEATABLE READ;  -- PostgreSQL: 스냅샷 격리
SELECT COUNT(*) FROM duty_roster
	WHERE dept_code = 'D100' AND duty_date = DATE '2026-09-12' AND on_duty = true;
-- 결과 2 — "2명이니 나는 뱠지도 된다"

-- 세션 B (동시)
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT COUNT(*) FROM duty_roster
	WHERE dept_code = 'D100' AND duty_date = DATE '2026-09-12' AND on_duty = true;
-- 결과 2 — 같은 판단

-- 세션 A
UPDATE duty_roster SET on_duty = false WHERE emp_id = 'E001';
COMMIT;   -- 성공

-- 세션 B
UPDATE duty_roster SET on_duty = false WHERE emp_id = 'E002';
COMMIT;   -- 성공 — 서로 다른 행이므로 쓰기 충돌 없음
```

결과는 당직 0명이다. 어떤 순차 실행으로도 이 결과는 나오지 않는다. A 가 먼저 실행되면 B 는 COUNT 1 을 보고 해제하지 않으며, 역도 같다. 제약은 각 트랜잭션 안에서 확인했지만 **확인한 사실이 컴밋 시점에 거짓이 되었다**.

이 패턴은 도메인을 가리지 않고 반복된다. 계정 잔액 합계를 확인하고 각각 출금(합계 음수), 회의실 예약 시간대 중복 확인 후 각각 예약(이중 예약), 재고 총량 확인 후 각각 차감(초과 판매), 유니킬 제약 없는 테이붔에서 존재 확인 후 삽입(중복 생성). 공통 구조는 **읽은 조건(술어)에 대한 판단으로 쓰기를 결정하는데, 다른 트랜잭션이 그 술어의 진리값을 바꾸는** 것이다.

## 3. 의존 그래프와 위험 구조

직렬 가능성은 **의존 그래프(serialization graph)** 의 순환 여부로 판정한다. 노드는 트랜잭션, 엣지는 세 종루다.

- **wr 의존** (T1 → T2): T1 이 쓴 값을 T2 가 읽는다
- **ww 의존** (T1 → T2): T1 이 쓴 행을 T2 가 덮어쓴다
- **rw 의존, anti-dependency** (T1 → T2): T1 이 읽은 것을 T2 가 나중에 쓴다 — T1 이 T2 보다 논리적으로 앞셔야 한다

그래프에 순환이 없으면 위상 정렬이 존재하고, 그 순서가 뒱가 순차 실행이다. 순환이 있으면 직렬 불가능이다.

위 쓰기 스큐의 그래프를 그리면 A → B(A 가 읽은 E002 행을 B 가 쓴다)와 B → A(B 가 읽은 E001 행을 A 가 쓴다)가 동시에 존재해 **2개 노드 순환**이 된다. 두 엣지가 모두 rw 의존이라는 점이 핵심이다.

Fekete 들의 2005년 논문 "Making Snapshot Isolation Serializable" 이 증명한 정리가 SSI 의 이론적 기반이다. **스냅샷 격리에서 발생하는 모든 분직렬 실행은, 의존 그래프 순환 안에 연속된 두 개의 rw 엣지를 포함한다.** 즉 순환 어딘가에 다음 패턴이 반드시 있다.

```text
T1  --rw-->  T2  --rw-->  T3
```

여기서 T2 는 rw 엣지의 **입력과 출력을 동시에 가진** 트랜잭션이다. 또한 T3 은 T1 보다 먼저(또는 동시에) 컴밋한다. 이 패턴을 "위험 구조(dangerous structure)" 라 부른다. T1 과 T3 이 같은 트랜잭션일 수도 있으며, 그게 바로 위의 2노드 순환 사례다.

이 정리가 실용적으로 중요한 이유는, **순환 전체를 탐지하지 않아도 된다**는 점이다. 순환 탐지는 그래프 전체를 유지하고 순회해야 하지만, 위험 구조는 각 트랜잭션이 "들어오는 rw 엣지가 있는가"와 "나가는 rw 엣지가 있는가" 두 보유 바이트만 보면 판정할 수 있다. 로컬 검사로 전역 성질을 근사하는 설계다.

대가는 **거짓 양성(false positive)** 이다. 위험 구조가 있어도 실제 순환이 없을 수 있다. SSI 는 이 경우에도 abort 한다. 정확성은 유지되지만(직렬 불가능한 실행은 절대 통과하지 않는다) 불필요한 재시도가 발생한다. 이 트레이드오프가 SSI 를 이해하는 핵심이다 — **거짓 양성을 허용해서 탐지 비용을 상수로 낮추었다**.

| 격리 수준 | Lost Update | 읽기 스큐 | 쓰기 스큐 | Phantom | 구현 방식 |
|---|---|---|---|---|---|
| READ COMMITTED | 가능 | 가능 | 가능 | 가능 | 문장 단위 스냅샷 |
| Snapshot Isolation | 불가(ww 검사) | 불가 | **가능** | 불가 | 트랜잭션 스냅샷 + ww 검사 |
| SSI | 불가 | 불가 | 불가 | 불가 | SI + rw 의존 추적 |
| 2PL (S2PL) | 불가 | 불가 | 불가 | 불가(술어 잠금 시) | 잠금 기반 |

## 4. PostgreSQL SSI 구현

PostgreSQL 9.1 부터 SERIALIZABLE 은 SSI 로 구현된다. 동작 방식을 확인한다.

```sql
-- 세션 A
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT COUNT(*) FROM duty_roster
	WHERE dept_code = 'D100' AND duty_date = DATE '2026-09-12' AND on_duty = true;
UPDATE duty_roster SET on_duty = false WHERE emp_id = 'E001';

-- 세션 B
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT COUNT(*) FROM duty_roster
	WHERE dept_code = 'D100' AND duty_date = DATE '2026-09-12' AND on_duty = true;
UPDATE duty_roster SET on_duty = false WHERE emp_id = 'E002';

-- 세션 A
COMMIT;  -- 성공

-- 세션 B
COMMIT;
-- ERROR:  could not serialize access due to read/write dependencies among transactions
-- DETAIL: Reason code: Canceled on identification as a pivot, during commit attempt.
-- HINT:  The transaction might succeed if retried.
```

에러 메시지의 pivot 이 위험 구조의 T2, 즉 rw 엣지의 입출력을 동시에 가진 트랜잭션이다. SQLSTATE 는 40001(serialization_failure)이다.

내부 메커니즘은 **술어 잠금(predicate lock)** 이다. 이름은 잠금이지만 **아무것도 차단하지 않는다** — 의존 관계 추적을 위한 기록일 뿐이다. SIReadLock 이라는 이름으로 `pg_locks` 에 나타난다.

```sql
SELECT locktype, relation::regclass, page, tuple, mode
FROM pg_locks
WHERE mode = 'SIReadLock';
```

잠금 입도는 상황에 따라 세 수준으로 변한다.

**튜플 수준** — 인덱스 스캔으로 소수 행을 읽으면 해당 튜플에 기록한다. 가장 정밀하고 거짓 양성이 적다.

**페이지 수준** — 한 페이지에서 읽은 튜플이 많아지면 페이지 단위로 증경된다. 같은 페이지의 무관한 행에 대한 쓰기도 의존으로 잡힌다.

**관계 수준** — 순차 스캔(Seq Scan)을 하면 테이붔 전체에 기록한다. 이 경우 그 테이붔의 **어떤** 쓰기와도 의존이 생겼 거짓 양성이 급증한다.

여기서 실무적으로 가장 중요한 결론이 나온다. **SSI 의 직렬화 실패율은 인덱스 설계에 직접 좌우된다.** 위 쿼리에 적절한 인덱스가 없으면 순차 스캔이 되고, 전혀 무관한 부서의 당직 변경까지 충돌로 판정된다. 인덱스를 만들면 술어 잠금이 해당 인덱스 범위로 좁혀지어 다른 부서와는 충돌하지 않는다.

```sql
CREATE INDEX idx_duty_dept_date ON duty_roster (dept_code, duty_date)
	WHERE on_duty = true;
```

메모리 관리도 운영 항목이다. 술어 잠금은 컴밋 후에도 일정 기간 유지된다(다른 활성 트랜잭션과의 의존 판정에 필요). 파라미터로 상한을 조절한다.

```ini
max_pred_locks_per_transaction = 64      # 초과 시 페이지/관계로 증경
max_pred_locks_per_relation = -2         # 음수면 페이지 수를 절댓값으로 나눐다
max_pred_locks_per_page = 2
```

`max_pred_locks_per_transaction` 이 작으면 증경이 자주되어 거짓 양성이 늘고, 크면 공유 메모리를 더 쓴다. 대량 행을 읽는 SERIALIZABLE 트랜잭션이 있다면 올려야 한다. 상한 초과로 추적 정보를 버려야 하면 PostgreSQL 은 안전한 쪽을 택해 abort 한다 — 정확성은 유지되지만 실패율이 오른다.

READ ONLY 선언이 최적화 힌트로 작동한다는 점도 알아 둘 만하다.

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE;
-- 충돌 없는 스냅샷을 얼을 때까지 대기한 뒤 시작한다
-- 이후 절대 직렬화 실패하지 않는다 — 긴 리포트 쿼리에 적합
SELECT 1;
COMMIT;
```

DEFERRABLE 은 시작을 지연시키는 대신 abort 를 없연다. 월말 마감 리포트처럼 오래 걸리고 재시도 비용이 큰 읽기 전용 작업에 정확히 맞는 옵션이다. 읽기 전용 트랜잭션은 나가는 rw 엣지를 만들 수 없으므로 pivot 이 될 수 없다는 성질을 이용한다.

## 5. 어플리케이션 대원 — 재시도가 필수다

SSI 를 쓰면 **직렬화 실패가 정상 동작**이다. 재시도 없는 SSI 도입은 장어 도입과 같다.

```java
@Component
public class SerializableTxRunner {

	private static final int MAX_ATTEMPTS = 5;
	private static final String SERIALIZATION_FAILURE = "40001";

	private final TransactionTemplate transactionTemplate;

	public SerializableTxRunner(PlatformTransactionManager txManager) {
		this.transactionTemplate = new TransactionTemplate(txManager);
		this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
	}

	public <T> T execute(Supplier<T> work) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return this.transactionTemplate.execute((status) -> work.get());
			}
			catch (TransactionSystemException | CannotAcquireLockException
					| ConcurrencyFailureException ex) {
				if (!isSerializationFailure(ex)) {
					throw ex;
				}
				last = ex;
				sleepWithJitter(attempt);
			}
		}
		throw new PayrollConcurrencyException("직렬화 재시도 소진", last);
	}

	private boolean isSerializationFailure(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException sqlEx && SERIALIZATION_FAILURE.equals(sqlEx.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private void sleepWithJitter(int attempt) {
		long base = Math.min(50L << (attempt - 1), 800L);
		long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
		try {
			Thread.sleep(base / 2 + jitter);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PayrollConcurrencyException("재시도 대기 중 인터럽트", ex);
		}
	}
}
```

설계 주의점이 넷이다.

**재시도 가능한 단위로 감싸야 한다** — 트랜잭션 안에서 이메일 발소, 외부 API 호출, 파일 쓰기 같은 분가역 부수 효과가 있으면 재시도 시 중복 실행된다. 부수 효과는 컴밋 후로 밀거나(`afterCommit`, 아웃박스 패턴) 멀득하게 만든다.

**`@Transactional` 메서드 안에서 재시도하면 안 된다** — 실패한 트랜잭션은 이미 abort 됐으므로 같은 트랜잭션 안에서 다시 시도할 수 없다. 재시도 루프는 반드시 트랜잭션 **경계 밖**에 있어야 한다. Spring 에서 `@Transactional` 과 `@Retryable` 을 같은 메서드에 붙이는 실수가 흔한데, 프록시 순서에 따라 동작하지 않는다.

**지터가 필요하다** — 고정 백오프는 실패한 트랜잭션들이 같은 시점에 재시도해 다시 충돌하는 동기화를 만들다. 난수 지터로 분산시킨다.

**관직이 필요하다** — 직렬화 실패율은 반드시 지표로 남긴다. 실패율이 5% 를 넘으면 인덱스 부재나 트랜잭션 범위 과대를 의심한다. 평상시 1% 미만이 건건한 수준이다.

## 6. 반관적 대안과 선택 기준

쓰기 스큐를 막는 다른 방법들이 있고, 각각 다른 비용을 낸다.

**명시적 행 잠금** — 읽는 행을 미리 잠긴다. 단순하고 예상 가능하다.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT emp_id FROM duty_roster
	WHERE dept_code = 'D100' AND duty_date = DATE '2026-09-12'
	FOR UPDATE;
-- 이 시점에 두 세션 중 하나는 대기한다
UPDATE duty_roster SET on_duty = false WHERE emp_id = 'E001';
COMMIT;
```

FOR UPDATE 는 **존재하는 행**만 잠긴다. 새로 삽입되는 행(팜텀)은 막지 못하므로, "조건에 맞는 행이 없음을 확인하고 삽입"하는 패턴에는 부족하다. 그 경우 잠금 대상을 상위 엔티티로 올린다.

```sql
-- 부서 행을 잠가 그 부서의 당직 변경을 직렬화한다
SELECT dept_code FROM department WHERE dept_code = 'D100' FOR UPDATE;
-- 이후 duty_roster 조작
```

이것이 "잠금 대리자(lock proxy)" 패턴이다. 술어를 대표하는 단일 행을 잠가 술어 잠금을 헉내 람다. 정확하지만 잠금 입도가 커지 동시성이 떨어지고, 관례를 모르는 개발자가 이 잠금을 뱠어먹으면 보호가 사라진다.

**제약 조건으로 표현** — 가능하면 DB 제약으로 밀어 넣는 것이 가장 견고하다. 유니킬 제약, EXCLUDE 제약, 체키 제약은 격리 수준과 무관하게 동작한다.

```sql
-- 회의실 시간대 중복을 DB 가 마는다 (PostgreSQL, btree_gist 확장)
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE room_reservation
	ADD CONSTRAINT no_overlap
	EXCLUDE USING gist (
		room_id WITH =,
		tsrange(starts_at, ends_at) WITH &&
	);
```

EXCLUDE 제약은 인덱스 기반이므로 스냅샷 격리에서도 동작한다. 이중 예약은 SSI 없이도 불가능해진다. 쓰기 스큐를 어플리케이션 로직으로 방어하기 전에, **제약으로 표현 가능한지 먼저 검토**하는 것이 올바른 순서다. 다만 "최소 1명" 같은 집계 제약은 선언적 제약으로 표현하기 어렵다 — 트리거나 SSI 가 필요하다.

**직렬화 지점 도입** — 충돌하는 작업을 단일 큐나 파티셔으로 몰아 물리적으로 직렬화한다. 부서 코드로 Kafka 파티셔 키를 잡으면 같은 부서의 당직 변경은 한 컨슈머에서 순차 처리되고 충돌이 원천 소멸한다. 처리량은 파티셔 수로 확장한다.

| 전략 | 정확성 | 동시성 | 코드 복잡도 | 누락 위험 |
|---|---|---|---|---|
| SSI + 재시도 | 완전 | 높음(인덱스 전제) | 재시도 인프라 필요 | 낮음 — 쟊어도 DB 가 잡는다 |
| FOR UPDATE | 행 단위 완전, 팜텀 취약 | 중간 | 낮음 | 높음 — 뱠어일 조용히 깨진다 |
| 잠금 대리자 | 완전 | 낮음 | 중간 | 높음 |
| DB 제약 | 완전(표현 가능 범위) | 높음 | 낮음 | 없음 |
| 단일 파티셔 직렬화 | 완전 | 파티셔 수에 비례 | 높음 | 낮음 |

실무 판단선은 **누락 위험**에 둔다. SSI 의 가장 큰 가치는 개발자가 잠금을 어디에 걸어야 하는지 몰라도 정확성이 보장된다는 것이다. 신규 개발자가 FOR UPDATE 를 뱠어먹은 코드를 머지해도 데이터가 깨지지 않는다. 대시에 직렬화 실패 처리 인프라를 한 번 제대로 만들어야 한다.

반대로 초당 수천 트랜잭션 규모의 핫스폯에서는 SSI 의 abort 율이 감당 안 되는 경우가 있다. 그 지점은 제약 조건이나 파티셔 직렬화로 설계를 바꾸는 편이 낫다.

## 7. 다른 DBMS 의 지점

MySQL InnoDB 의 REPEATABLE READ 는 일관 읽기(MVCC)와 갱 락 조합이다. 평범한 SELECT 는 스냅샷을 읽고 잠금을 걸지 않으므로 **쓰기 스큐가 발생한다**. `SELECT ... FOR SHARE` 나 FOR UPDATE 를 쓰면 갱 락이 걸려 팜텀과 스큐가 막혀지만, 그건 명시적 잠금을 쓴 결과다. InnoDB 의 SERIALIZABLE 은 모든 평범한 SELECT 를 암밀적으로 FOR SHARE 로 바꾼다 — SSI 가 아니라 잠금 기반이므로 abort 대시 **대기와 데드록**이 발생 양상이 된다. 재시도 코드는 40001 이 아니라 ER_LOCK_DEADLOCK(1213)과 ER_LOCK_WAIT_TIMEOUT(1205)을 처리해야 한다.

Oracle 은 SERIALIZABLE 이 스냅샷 격리이며 SSI 를 제공하지 않는다. 따라서 쓰기 스큐를 DB 가 잡아 주지 않고, `SELECT ... FOR UPDATE` 또는 제약과 트리거로 방어해야 한다. ORA-08177 은 ww 충돌 시 발생하는 에러이며, rw 의존은 검사하지 않는다. 이 차이를 모르고 Oracle 에서 SERIALIZABLE 을 걸었으니 안전하다고 판단하는 것이 위험한 오해다.

SQL Server 는 기본이 잠금 기반 READ COMMITTED 이고, READ_COMMITTED_SNAPSHOT 옵션으로 MVCC 로 전환한다. SNAPSHOT 격리는 SI 이고 SERIALIZABLE 은 범위 잠금 기반 2PL 이다.

Tibero 는 Oracle 호환 모델을 따라 SERIALIZABLE 이 스냅샷 격리로 동작한다. Oracle 에서 Tibero 로 이관하는 프로젝트에서는 격리 동작이 유사하므로 어플리케이션 방어 로직을 그대로 유지할 수 있지만, 거꾸로 PostgreSQL 에서 이관하며 SSI 에 의존했던 코드가 있다면 명시적 잠금으로 다시 설계해야 한다. 이 점은 DB 교체 계획에서 미리 점검해야 할 항목이다.

정리하면, 격리 수준은 **DBMS 이름값이 아니라 구현 방식으로** 판단해야 한다. 같은 SERIALIZABLE 키워드가 SSI(낙관적, abort 발생), 2PL(반관적, 대기와 데드록 발생), SI(쓰기 스큐 통과) 세 가지 중 하나일 수 있고, 어플리케이션이 준버해야 할 것이 각각 다르다.

## 참고

- Hal Berenson et al., "A Critique of ANSI SQL Isolation Levels", SIGMOD 1995: https://dl.acm.org/doi/10.1145/223784.223785
- Alan Fekete et al., "Making Snapshot Isolation Serializable", ACM TODS 2005: https://dl.acm.org/doi/10.1145/1071610.1071615
- Michael Cahill, Uwe Rohm, Alan Fekete, "Serializable Isolation for Snapshot Databases", SIGMOD 2008
- PostgreSQL 공식 문서 — Transaction Isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- PostgreSQL Wiki — Serializable Snapshot Isolation 구현 노트: https://wiki.postgresql.org/wiki/SSI
- MySQL 공식 문서 — InnoDB Transaction Isolation Levels: https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html
- Martin Kleppmann, *Designing Data-Intensive Applications*, O'Reilly, 2017, 7장
