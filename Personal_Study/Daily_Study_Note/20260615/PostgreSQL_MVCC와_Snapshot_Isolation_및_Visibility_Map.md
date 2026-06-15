Notion 원본: https://www.notion.so/3805a06fd6d381ec9a2fc36952976bfc

# PostgreSQL MVCC와 Snapshot Isolation 및 Visibility Map

> 2026-06-15 신규 주제 · 확장 대상: Oracle / DB

## 학습 목표

- 튜플 헤더의 xmin·xmax·ctid가 버전 가시성을 결정하는 방식을 추적한다
- 스냅샷(xmin, xmax, xip)으로 트랜쟭션이 어떤 버전을 보는지 판정한다
- Read Committed와 Repeatable Read의 스냅샷 획득 시점 차이를 구분한다
- Visibility Map과 VACUUM이 dead tuple 회수 및 Index-Only Scan에 기여하는 원리를 설명한다

## 1. PostgreSQL MVCC는 "추가 전용" 모델이다

Oracle은 변경 시 이전 이미지를 UNDO에 저장하고 데이터 블록은 제자리에서 갱신한다. PostgreSQL은 반대로 UPDATE를 "기존 행을 죽은 것으로 표시하고 새 행 버전을 테이블에 추가"하는 방식이다. 한 논리적 행이 물리적으로 여러 튜플 버전으로 공존한다. 이 차이로 PostgreSQL은 dead tuple이 쌓여 bloat가 생기고, VACUUM이 이를 정리해야 한다.

## 2. 튜플 헤더: xmin, xmax, ctid

`t_xmin`은 이 버전을 생성한 트랜쟭션 ID, `t_xmax`는 이 버전을 무효화한 트랜쟭션 ID(살아있으면 0), `t_ctid`는 (블록 번호, 오프셋)으로 UPDATE 시 새 버전을 가리켜 버전 체인을 형성한다.

```sql
CREATE EXTENSION IF NOT EXISTS pageinspect;
INSERT INTO accounts VALUES (1, 100);
SELECT xmin, xmax, ctid, id, balance FROM accounts;
--  745 |    0 | (0,1) |  1 |     100
UPDATE accounts SET balance = 150 WHERE id = 1;
SELECT xmin, xmax, ctid, id, balance FROM accounts;
--  746 |    0 | (0,2) |  1 |     150
```

UPDATE는 `(0,1)`에 `xmax=746`을 기록하고 새 튜플 `(0,2)`를 `xmin=746`으로 추가한다.

## 3. 스냅샷: 누가 무엇을 보는가

스냅샷은 `xmin`(하한), `xmax`(상한), `xip_list`(진행 중 트랜쟭션 목록)으로 구성된다. 튜플의 xmin이 커밋되고 내 스냅샷에서 "과거"이며, xmax가 없거나 미커밋/"미래"이면 그 튜플이 보인다.

```sql
SELECT txid_current(), pg_current_snapshot();
--           750 | 748:752:748,749
```

`748:752:748,749`는 "748 미만 완료, 752 이상 미시작, 748·749 진행 중"을 뜻한다. 이 판정으로 잠금 없이 일관된 읽기가 가능하다 — 읽기는 쓰기를 막지 않고 쓰기는 읽기를 막지 않는다.

## 4. Read Committed vs Repeatable Read

Read Committed(기본값)는 각 SQL 문마다 새 스냅샷을, Repeatable Read는 트랜쟭션 첫 문에서 한 번 스냅샷을 잡아 끝까지 유지한다.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = 1;  -- 100, 스냅샷 고정
-- 세션 B가 999로 UPDATE 후 COMMIT
SELECT balance FROM accounts WHERE id = 1;  -- 여전히 100
COMMIT;
```

PostgreSQL의 Repeatable Read는 스냅샷 격리를 구현해 phantom read까지 방지하며, 쓰기-쓰기 충돌 시 직렬화 실패(`could not serialize access`)가 나 애플리케이션이 재시도해야 한다.

```java
@Retryable(retryFor = CannotSerializeTransactionException.class, maxAttempts = 3,
           backoff = @Backoff(delay = 50))
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void transfer(long from, long to, int amount) { }
```

## 5. HOT Update와 인덱스 부담

갱신된 컴럼이 어느 인덱스에도 없고 새 버전이 같은 힙 페이지에 들어갈 공간이 있으면, 인덱스를 건드리지 않고 힙 안에서만 버전 체인을 잇는다(HOT). `FILLFACTOR`를 낮춰 갱신 여지를 남긴다.

```sql
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd, 0), 1) AS hot_ratio
FROM pg_stat_user_tables WHERE relname = 'accounts';
--  accounts | 50000 | 47200 | 94.4
```

## 6. Visibility Map과 Index-Only Scan

VM은 힙의 각 페이지마다 2비트(all-visible, frozen)를 갖는다. all-visible 비트가 설정된 페이지는 가시성 확인을 위한 힙 방문을 건너뛰어 Index-Only Scan이 효율적이 된다.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM accounts WHERE id BETWEEN 1 AND 1000;
-- Index Only Scan ... Heap Fetches: 0  ← VM 덕분에 힙 방문 0회
```

`Heap Fetches`가 크면 VM 갱신이 필요하다.

## 7. VACUUM과 트랜쟭션 ID Wraparound

VACUUM은 dead tuple 공간을 회수하고(OS 반환은 VACUUM FULL), 오래된 xmin을 동결해 트랜쟭션 ID wraparound를 방지한다. ID는 32비트라 약 42억 개를 쓰면 도는데, autovacuum이 `autovacuum_freeze_max_age`(약 2억) 이상 테이블을 강제 동결한다.

```sql
SELECT relname, age(relfrozenxid) AS xid_age FROM pg_class
WHERE relkind = 'r' ORDER BY age(relfrozenxid) DESC LIMIT 5;
```

## 8. 운영 함정과 trade-off

첫째, 장기 실행 트랜쟭션은 오래된 스냅샷을 잡아 VACUUM의 dead tuple 회수를 막는다. `idle_in_transaction_session_timeout`으로 방치된 트랜쟭션을 종료한다. 둘째, bloat는 I/O를 늘려 성능을 잠식하므로 `pg_repack`으로 온라인 재구성한다. 셋째, 직렬화 실패는 정상 동작이므로 반드시 재시도로 대응한다.

## 9. Serializable Snapshot Isolation(SSI)과 Oracle 대비

PostgreSQL의 `SERIALIZABLE`은 SSI로 구현되어 write skew를 탐지한다. 두 트랜쟭션이 서로 겹치지 않는 행을 갱신하지만 각자 상대가 읽은 데이터를 바꿔 불변식이 깨지는 경우를 막는다. SSI는 SIRead 잠금으로 근사하므로 읽은 범위가 넓으면 false positive가 늘 수 있다. Oracle의 `SERIALIZABLE`은 사실상 스냅샷 격리라 write skew를 막지 못하고, UNDO 기반이라 bloat는 없지만 UNDO 고갈 시 `ORA-01555`가 난다. 동시성 모델의 철학 차이가 운영 실패 양상까지 다르게 만든다.

## 참고

- PostgreSQL Documentation, "Transaction Isolation": https://www.postgresql.org/docs/current/transaction-iso.html
- PostgreSQL Documentation, "Routine Vacuuming": https://www.postgresql.org/docs/current/routine-vacuuming.html
- The Internals of PostgreSQL, "Concurrency Control": https://www.interdb.jp/pg/pgsql05.html
- PostgreSQL Documentation, "Database Page Layout": https://www.postgresql.org/docs/current/storage-page-layout.html
