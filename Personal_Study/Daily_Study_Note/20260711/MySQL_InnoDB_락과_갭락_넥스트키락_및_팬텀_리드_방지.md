Notion 원본: https://app.notion.com/p/39a5a06fd6d3816082d2d1937e06ce93

# MySQL InnoDB 락과 갭락 넥스트키락 및 팬텀 리드 방지

> 2026-07-11 신규 주제 · 확장 대상: MySQL/InnoDB (버퍼풀·Change Buffer 학습됨)

## 학습 목표

- 레코드 락·갭 락·넥스트키 락의 잠금 대상과 범위를 구분한다
- 넥스트키 락이 REPEATABLE READ에서 팬텀 리드를 막는 원리를 설명한다
- 인덱스 유무에 따른 잠금 확대 현상을 진단한다
- 격리 수준별 잠금 동작 차이를 판단해 데드락을 회피한다

## 1. InnoDB 잠금의 기본 단위

InnoDB의 행 수준 잠금은 실제로는 "행"이 아니라 "인덱스 레코드"에 걸린다. 이 사실이 모든 잠금 동작을 이해하는 출발점이다. 테이블에 적절한 인덱스가 없으면 InnoDB는 조건에 맞는 행을 찾기 위해 더 많은 인덱스 레코드를 스캔하고, 스캔한 레코드마다 잠금을 걸어 잠금 범위가 예상보다 크게 확대된다.

잠금에는 공유 락(S)과 배타 락(X)이 있다. `SELECT ... FOR SHARE`는 S 락을, `SELECT ... FOR UPDATE`와 `UPDATE`/`DELETE`는 X 락을 건다. 여기에 테이블 수준의 의도 락(IS/IX)이 더해져, 행 잠금 전에 테이블에 의도를 표시함으로써 테이블 락과의 충돌을 빠르게 판정한다.

```sql
-- 인덱스가 걸린 컬럼으로 조회 → 해당 레코드만 잠금
SELECT * FROM orders WHERE id = 100 FOR UPDATE;

-- 인덱스 없는 컬럼으로 조회 → 스캔한 모든 레코드에 잠금 확대
SELECT * FROM orders WHERE memo = 'x' FOR UPDATE;
```

두 번째 쿼리에서 `memo`에 인덱스가 없으면 InnoDB는 전체 클러스터드 인덱스를 스캔하며 사실상 모든 행에 X 락을 걸고, 조건에 맞지 않는 행은 나중에 잠금을 해제한다. 하지만 스캔 도중에는 잠겨 있으므로 동시성이 급락한다.

## 2. 레코드 락과 갭 락

레코드 락(record lock)은 존재하는 인덱스 레코드 자체를 잠근다. 갭 락(gap lock)은 인덱스 레코드 "사이의 빈 구간"을 잠가 그 구간에 새 행이 삽입되는 것을 막는다. 갭 락은 값이 아니라 범위를 대상으로 하므로, 여러 트랜잭션이 같은 갭에 갭 락을 동시에 가질 수 있다(갭 S와 갭 X가 서로 충돌하지 않는다). 갭 락의 목적은 오직 삽입 방지이기 때문이다.

예를 들어 `id`가 10, 20, 30인 레코드가 있을 때, `WHERE id > 15 AND id < 25 FOR UPDATE`는 20 레코드에 레코드 락을 걸고, 그 앞뒤 갭 (10,20)과 (20,30)의 일부에 갭 락을 걸어 16~24 사이의 삽입을 차단한다. 이 삽입 차단이 팬텀 리드 방지의 핵심 메커니즘이다.

## 3. 넥스트키 락

넥스트키 락(next-key lock)은 레코드 락과 그 레코드 "앞쪽" 갭 락을 합친 것이다. InnoDB의 기본 잠금 형태로, REPEATABLE READ에서 범위 스캔 시 각 레코드에 넥스트키 락을 건다. 잠금 구간은 반개구간 `(직전값, 현재값]`이다. 즉 이전 레코드는 배타적, 현재 레코드는 포함이다.

```
레코드: 10, 20, 30
넥스트키 락 구간: (-∞,10], (10,20], (20,30], (30,+∞)
```

`WHERE id > 15 FOR UPDATE`를 실행하면 20에 대한 넥스트키 락 (10,20], 30에 대한 (20,30], 그리고 최상단 의사 레코드까지 (30,+∞) 구간이 잠긴다. 결과적으로 15 초과 영역 전체가 삽입·수정으로부터 보호된다. 이렇게 "레코드 + 선행 갭"을 함께 잠그기 때문에 범위 질의의 결과 집합이 트랜잭션 내내 불변으로 유지된다.

## 4. 팬텀 리드가 막히는 원리

팬텀 리드는 같은 범위 질의를 두 번 실행했을 때 첫 번째엔 없던 행이 두 번째에 나타나는 현상이다. 표준 SQL의 REPEATABLE READ는 팬텀을 허용하지만, InnoDB는 넥스트키 락으로 잠금 읽기(`FOR UPDATE`/`FOR SHARE`)에서 팬텀을 막는다. 범위에 갭 락이 걸려 다른 트랜잭션이 그 범위에 새 행을 넣지 못하기 때문이다.

주의할 점은 일반 `SELECT`(잠금 없는 읽기)와 잠금 읽기의 팬텀 방지 메커니즘이 다르다는 것이다. 일반 읽기는 MVCC 스냅숏으로 일관성을 유지해 애초에 새 행을 보지 않는다. 잠금 읽기는 스냅숏이 아니라 현재 커밋된 데이터를 읽으므로(current read), 넥스트키 락이 없으면 팬텀이 발생할 수 있다. 그래서 InnoDB는 잠금 읽기에 넥스트키 락을 적용해 두 경로 모두에서 REPEATABLE READ를 보장한다.

```sql
-- 트랜잭션 A
START TRANSACTION;
SELECT * FROM orders WHERE amount > 1000 FOR UPDATE; -- 넥스트키 락 걸림
-- 트랜잭션 B: INSERT INTO orders(amount) VALUES (1500); → A의 갭 락에 막혀 대기
```

## 5. 인덱스와 잠금 확대

넥스트키 락은 스캔하는 인덱스를 기준으로 걸린다. 세컨더리 인덱스로 조회하면 세컨더리 인덱스 레코드와 그 갭, 그리고 대응하는 클러스터드 인덱스 레코드까지 잠근다. 인덱스가 유니크하고 조건이 등치(=)로 단일 행을 확정하면 갭 락은 생략되고 레코드 락만 걸린다. 유니크 조건은 새 삽입이 그 값과 충돌해 어차피 불가능하므로 갭을 잠글 필요가 없기 때문이다.

| 조건 형태 | 인덱스 | 잠금 |
|-----------|--------|------|
| `id = 5` (유니크, 존재) | PK | 레코드 락만 |
| `id = 5` (유니크, 미존재) | PK | 갭 락 |
| `age = 30` (논유니크) | 세컨더리 | 넥스트키 락 |
| 인덱스 없음 | 없음 | 스캔 전체 확대 |

유니크 인덱스라도 값이 존재하지 않으면 갭 락이 걸린다는 점이 중요하다. 없는 값을 `FOR UPDATE`로 조회하면 그 값이 들어갈 갭을 잠가 삽입을 막는다. 이는 "존재하지 않는 것을 잠그는" 동작으로, 유니크 제약을 이용한 락 기반 upsert 패턴에서 데드락의 흔한 원인이 된다.

## 6. 격리 수준별 차이

READ COMMITTED에서는 갭 락이 대부분 비활성화된다. 조건에 맞는 레코드에만 레코드 락을 걸고, 매 문장마다 새 스냅숏을 읽는다. 그 결과 팬텀이 발생할 수 있지만 잠금 범위가 좁아 동시성이 높아진다. 또 조건에 맞지 않아 잠갔던 레코드의 락을 문장 종료 시 즉시 푼다(semi-consistent read). REPEATABLE READ는 넥스트키 락을 유지해 팬텀을 막는 대신 잠금 경합이 커진다.

```sql
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- 갭 락 없음, 팬텀 가능, 동시성 우선

SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
-- 넥스트키 락, 팬텀 차단, 일관성 우선 (InnoDB 기본)
```

실무에서 대량 배치나 삽입 경합이 심한 워크로드는 READ COMMITTED로 낮춰 갭 락 데드락을 줄이기도 한다. 단 이 경우 애플리케이션이 팬텀을 견디도록 설계되어야 한다. 바이너리 로그를 STATEMENT 포맷으로 쓰던 시절엔 READ COMMITTED가 복제 안전성 문제가 있었으나, ROW 포맷 복제에서는 문제가 없다.

## 7. 데드락 회피

갭 락과 넥스트키 락은 데드락을 유발하기 쉽다. 대표 시나리오는 두 트랜잭션이 같은 갭에 갭 락(서로 호환)을 가진 뒤, 각자 그 갭에 삽입 의도 락(insert intention lock)을 요청하며 서로를 기다리는 경우다. 삽입 의도 락은 갭 락과 충돌하므로, 상대의 갭 락이 풀리길 기다리다 순환 대기가 형성된다.

회피 전략은 다음과 같다. 첫째, 잠금 순서를 일관되게 한다. 여러 행을 갱신할 때 항상 같은 순서(예: PK 오름차순)로 접근하면 순환 대기가 깨진다. 둘째, 트랜잭션을 짧게 유지해 잠금 보유 시간을 줄인다. 셋째, 없는 값에 `FOR UPDATE`를 남발하지 않는다. upsert가 필요하면 `INSERT ... ON DUPLICATE KEY UPDATE`나 `INSERT IGNORE`로 갭 락 경합을 줄인다.

```sql
-- 데드락 유발형: 두 세션이 없는 값을 잠그고 삽입
SELECT * FROM t WHERE id = 50 FOR UPDATE;  -- 갭 락
INSERT INTO t(id) VALUES (50);              -- 삽입 의도 락 → 상호 대기

-- 개선형
INSERT INTO t(id, v) VALUES (50, 1)
  ON DUPLICATE KEY UPDATE v = VALUES(v);
```

InnoDB는 데드락을 감지하면 비용이 적은 트랜잭션 하나를 롤백(victim)한다. `SHOW ENGINE INNODB STATUS`의 LATEST DETECTED DEADLOCK 섹션으로 원인을 분석하고, `innodb_deadlock_detect`를 끄면 감지 대신 타임아웃(`innodb_lock_wait_timeout`)에 의존하게 되어 고빈도 워크로드의 감지 오버헤드를 줄일 수 있다.

## 8. 진단 도구와 실전 지침

잠금 현황은 `performance_schema.data_locks`와 `data_lock_waits`로 관찰한다. 어떤 트랜잭션이 어떤 레코드·갭에 어떤 락을 들고 있는지, 누가 무엇을 기다리는지 조인해 파악할 수 있다.

```sql
SELECT r.trx_id waiting, b.trx_id blocking, l.lock_mode, l.lock_type
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks l ON w.requesting_engine_lock_id = l.engine_lock_id
JOIN information_schema.innodb_trx r ON w.requesting_engine_transaction_id = r.trx_id
JOIN information_schema.innodb_trx b ON w.blocking_engine_transaction_id = b.trx_id;
```

설계 지침을 정리하면 세 가지다. 첫째, 잠금 읽기 조건에는 반드시 인덱스를 태워 잠금 확대를 막는다. 둘째, 범위 갱신은 격리 수준과 갭 락의 상호작용을 이해하고, 삽입 경합이 크면 READ COMMITTED를 고려한다. 셋째, 애플리케이션 레벨에서 잠금 획득 순서를 표준화해 데드락 가능성을 구조적으로 낮춘다. 앞서 학습한 버퍼풀·Change Buffer가 물리 I/O를 줄이는 계층이라면, 잠금은 논리적 동시성을 조율하는 계층으로 둘 다 이해해야 고동시성 트랜잭션 시스템을 튜닝할 수 있다.

## 참고

- MySQL Reference Manual, "InnoDB Locking" (dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- MySQL Reference Manual, "Phantom Rows" 및 "Locks Set by Different SQL Statements"
- "High Performance MySQL", Silvia Botros & Jeremy Tinley
- performance_schema data_locks 문서 (dev.mysql.com)
- InnoDB 소스 `lock0lock.cc` 주석
