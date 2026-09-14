Notion 원본: https://app.notion.com/p/3db5a06fd6d3816da7a8c484c2e317de?pvs=204

# MySQL InnoDB 언두 로그와 퍼지 스레드 및 History List Length 팽창 진단

> 2026-09-14 신규 주제 · 확장 대상: MySQL InnoDB 트랜잭션·MVCC

## 학습 목표

- 언두 레코드 체인과 ReadView 가 과거 버전을 재구성하는 경로를 단계별로 추적한다
- `SHOW ENGINE INNODB STATUS` 와 `INNODB_METRICS` 로 History List Length 를 관측하고 위험 수준을 판정한다
- 퍼지 관련 파라미터를 워크로드에 맞게 조정하고 그 부작용을 수치로 계산한다
- 장기 트랜잭션을 만들어내는 배치·ORM 패턴을 찾아내어 설계를 교정한다

## 1. 히든 컴럼과 ReadView 가 과거 버전을 만드는 경로

InnoDB 의 클러스터형 인덱스 레코드에는 사용자가 정의하지 않은 세 개의 히든 컴럼이 붙는다. `DB_TRX_ID`(6바이트)는 그 레코드를 마지막으로 INSERT/UPDATE 한 트랜잭션의 ID 이고, `DB_ROLL_PTR`(7바이트)는 그 변경이 만들어낸 언두 레코드를 가리키는 롤 포인터다. 기본 키도 유니크 NOT NULL 인덱스도 없는 테이블에만 `DB_ROW_ID`(6바이트)가 추가된다. 즉 행 하나가 수정될 때마다 "수정 전 모습을 복원할 수 있는 정보"가 언두 로그에 기록되고, 현재 행은 그 언두 레코드를 링크로 들고 있게 된다. 언두 레코드 자체도 자신이 덮어쓴 이전 버전의 롤 포인터를 담고 있어서, 결과적으로 하나의 행은 최신 버전 → 직전 버전 → 그 이전 버전으로 이어지는 단방향 체인을 형성한다.

REPEATABLE READ 에서 일관된 읽기가 성립하는 이유가 여기 있다. 트랜잭션이 첫 일관된 읽기를 수행하는 순간 InnoDB 는 ReadView 를 만든다. ReadView 는 그 시점에 활성 상태였던 트랜잭션 ID 집합과, 그 집합의 최솟값(이보다 작으면 무조건 보임)·아직 할당되지 않은 다음 ID(이보다 크거나 같으면 무조건 안 보임)를 들고 있다. 레코드를 읽을 때 `DB_TRX_ID` 가 "보이지 않는" 판정을 받으면 `DB_ROLL_PTR` 를 따라 언두 레코드로 내려가 이전 버전을 복원하고, 그 버전의 `DB_TRX_ID` 로 다시 판정한다. 보이는 버전을 만날 때까지 이 과정이 반복된다. 체인이 길수록 한 행을 읽는 데 필요한 언두 페이지 접근 횟수가 늘어난다는 점이 이 글 전체의 핵심 전제다.

```sql
-- 세션 A: 스냅샷을 먼저 고정한다
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT;  -- 이 시점에 ReadView 생성
SELECT balance FROM account WHERE id = 1;    -- 1000

-- 세션 B: 같은 행을 20회 갱신하고 각각 커밋
UPDATE account SET balance = balance + 1 WHERE id = 1;  -- 반복 커밋

-- 세션 A: 여전히 1000 을 본다. 단, 내부적으로는 언두 체인을 20단계 되짚는다
SELECT balance FROM account WHERE id = 1;
COMMIT;  -- 여기서야 ReadView 가 닫히고 해당 언두 버전들이 퍼지 대상이 된다
```

READ COMMITTED 는 문장마다 ReadView 를 새로 만들기 때문에 같은 트랜잭션이 길게 열려 있어도 오래된 버전을 붙잡는 기간이 문장 단위로 짧아진다. 리포팅 트랜잭션을 READ COMMITTED 로 낮추는 것이 HLL 대응책으로 자주 거론되는 이유다.

## 2. insert undo 와 update undo, 그리고 롤백 세그먼트

언두 로그는 성격이 전혀 다른 두 종류로 나뉘다. insert undo 는 INSERT 가 만든 레코드를 되돌리기 위한 정보다. 아직 커밋되지 않은 INSERT 는 다른 트랜잭션에게 애초에 보이지 않아야 하므로, 커밋되는 순간 이 언두 로그는 MVCC 용도로 쓸모가 없어져 즉시 폐기 가능해진다. 반면 update undo 는 UPDATE 와 DELETE 가 남기는 것으로, 커밋된 뒤에도 그 이전 버전을 봐야 하는 오래된 ReadView 가 하나라도 남아 있을 수 있으므로 즉시 버릴 수 없다. 이 update undo 를 실제로 회수하는 주체가 퍼지이며, 회수되지 않고 쌓인 update undo 로그의 개수가 바로 History List Length 다. DELETE 도 물리 삭제가 아니라 delete-mark 만 찍고 지나가므로 update undo 를 남긴다는 점을 기억해야 한다.

언두 로그는 롤백 세그먼트에 배치되고, 롤백 세그먼트는 고정 개수의 undo slot 을 가진다. 기본 페이지 크기 16KB 기준으로 롤백 세그먼트 하나당 1,024개의 슬롯이 있고, 한 트랜잭션은 필요한 언두 종류에 따라 최대 두 개(insert 용, update 용)를 쓸 수 있다. 임시 테이블에 대한 변경은 별도의 임시 테이블스페이스 롤백 세그먼트를 사용해 리두를 생성하지 않는다.

```sql
SELECT @@innodb_page_size,          -- 16384
       @@innodb_rollback_segments,  -- 기본 128
       @@innodb_undo_tablespaces,
       @@innodb_undo_log_truncate,
       @@innodb_max_undo_log_size;  -- 기본 1073741824 (1GB)
```

`innodb_rollback_segments` 는 언두 테이블스페이스마다 할당되는 롤백 세그먼트 수를 정한다. 값이 작으면 동시 트랜잭션이 같은 롤백 세그먼트를 두고 경합하고, 값이 크면 퍼지가 순회해야 할 세그먼트가 늘어난다. 대부분의 OLTP 환경에서는 기본값 128 을 유지하고, 롤백 세그먼트 뮤텍스 경합이 실제로 관측될 때만 손대는 편이 안전하다.

## 3. MySQL 8.0 의 독립 언두 테이블스페이스와 트렁케이트

MySQL 5.7 까지 언두는 기본적으로 시스템 테이블스페이스(`ibdata1`) 안에 있었고, 한 번 부풀어 오른 `ibdata1` 은 서버를 재구축하지 않는 한 줄어들지 않았다. MySQL 8.0 은 초기화 시점에 `innodb_undo_001`, `innodb_undo_002` 두 개의 독립 언두 테이블스페이스를 항상 만든다. 최소 2개가 보장되는 이유는 트렁케이트 때문이다. 하나를 비활성화해 잘라내는 동안 나머지 하나가 언두를 받아야 하므로, 언두 테이블스페이스가 하나뿐이면 온라인 트렁케이트가 성립하지 않는다.

MySQL 8.0.14 부터는 SQL 로 언두 테이블스페이스를 직접 추가·삭제할 수 있어 `innodb_undo_tablespaces` 변수의 역할은 초기화 시점 개수 지정으로 축소됐다. 언두 I/O 를 별도 디스크로 분리하고 싶을 때 다음과 같이 파일 경로를 지정해 추가한다.

```sql
-- 언두 전용 볼륨으로 분산 (경로는 innodb_directories 에 등록돼 있어야 한다)
CREATE UNDO TABLESPACE undo_003 ADD DATAFILE '/data/undo/undo_003.ibu';

SELECT NAME, STATE, ROUND(FILE_SIZE/1024/1024) AS file_mb
  FROM information_schema.INNODB_TABLESPACES
 WHERE NAME LIKE '%undo%';

-- 제거는 비활성화 → empty 확인 → DROP 순서
ALTER UNDO TABLESPACE undo_003 SET INACTIVE;
DROP UNDO TABLESPACE undo_003;
```

`innodb_undo_log_truncate` 는 MySQL 8.0 에서 기본 ON 이다. 어떤 언두 테이블스페이스의 크기가 `innodb_max_undo_log_size`(기본 1GB)를 넘으면 트렁케이트 대상으로 표시되고, 퍼지가 그 테이블스페이스의 언두를 모두 회수한 뒤 파일을 초기 크기로 잘라낸다. 여기서 중요한 함정은 **트렁케이트가 퍼지에 종속된다**는 점이다. 오래된 ReadView 때문에 퍼지가 진행되지 못하면 파일은 1GB 를 한참 넘겨 수십 GB 까지 자라고, 트렁케이트는 영원히 실행되지 않는다. 즉 언두 테이블스페이스의 디스크 폭증은 "설정 문제"가 아니라 거의 항상 "장기 트랜잭션 문제"의 증상이다. `innodb_purge_rseg_truncate_frequency`(기본 128, 최대 128)는 퍼지가 몇 번 호출될 때마다 롤백 세그먼트를 실제로 해제할지를 정한다. 트렁케이트를 더 공격적으로 돌리려면 이 값을 낮추지만, 그만큼 퍼지 사이클마다 추가 작업이 붙는다.

## 4. 퍼지 스레드의 동작과 파라미터

퍼지는 update undo 를 제거하면서 동시에 delete-mark 된 레코드를 클러스터형 인덱스와 세컨더리 인덱스에서 물리적으로 제거한다. 즉 DELETE 의 실제 비용 상당 부분이 커밋 이후 백그라운드로 이연된 것이며, 퍼지가 밀리면 이 부채가 그대로 누적된다.

```ini
[mysqld]
# 코디네이터 1개를 포함한 퍼지 스레드 수. 기본 4, 범위 1~32, 재시작 필요
innodb_purge_threads = 8
# 한 배치에서 파싱·처리할 언두 로그 페이지 수. 기본 300, 범위 1~5000, 동적
innodb_purge_batch_size = 1000
# HLL 이 이 값을 넘으면 DML 에 지연을 주입. 기본 0(무제한)
innodb_max_purge_lag = 1000000
# 주입 지연의 상한 (마이크로초). 기본 0(상한 없음)
innodb_max_purge_lag_delay = 100000
# 롤백 세그먼트 해제 빈도. 기본 128
innodb_purge_rseg_truncate_frequency = 128
```

`innodb_purge_threads` 는 동적 변수가 아니므로 재시작이 필요하다. 테이블 수가 적고 DML 이 한두 테이블에 집중되는 워크로드에서는 스레드를 늘려도 같은 인덱스에 경합만 늘어 효과가 적고, 테이블이 많고 DML 이 넓게 퍼진 워크로드에서는 8~16 으로 올렸을 때 퍼지 처리량이 유의미하게 오른다. `innodb_purge_batch_size` 를 300 에서 1000 으로 올리면 한 번에 더 많은 언두 페이지를 정리하지만, 그만큼 퍼지가 버퍼 풀과 I/O 대역폭을 더 가져가 포그라운드 쿼리의 지연 변동성이 커진다. 운영 중 즉시 대응이 필요할 때는 동적인 `innodb_purge_batch_size` 를 먼저 올리고, 구조적 부족이 확인되면 다음 정비 창에서 `innodb_purge_threads` 를 조정하는 순서가 현실적이다.

`innodb_max_purge_lag` 는 성격이 다르다. 이 값은 퍼지를 빠르게 만들지 않고, **DML 을 느리게 만들어** 언두 생성 속도를 퍼지 속도에 맞춘다. 매뉴얼의 지연 계산식은 `delay = ((length(history_list) - innodb_max_purge_lag) * 10) - 5` 이고, 지연은 퍼지 배치 시작 시점에 계산된다. `innodb_max_purge_lag_delay` 는 이 지연의 상한을 마이크로초 단위로 정한다. 상한을 두지 않고 `innodb_max_purge_lag` 만 켜면 HLL 이 크게 벌어졌을 때 모든 쓰기 트래픽이 급격히 느려져 장애처럼 보일 수 있으므로, 두 값은 반드시 함께 설정한다. 기본값은 둘 다 0 이라 아무 제동도 걸리지 않는다.

## 5. History List Length 를 관측하는 두 가지 경로

가장 흔한 관측 경로는 표준 모니터 출력의 TRANSACTIONS 섹션이다.

```sql
SHOW ENGINE INNODB STATUS\G
-- ------------
-- TRANSACTIONS
-- ------------
-- Trx id counter 84213991
-- Purge done for trx's n:o < 84109233 undo n:o < 0 state: running but idle
-- History list length 1843726
```

여기서 `Trx id counter` 와 `Purge done for trx's n:o` 의 격차가 곳 퍼지가 따라잡지 못한 트랜잭션 수다. `History list length` 만 보지 말고 이 두 값의 차이를 함께 보면 "정체가 시작된 지점"을 가늘할 수 있다.

모니터링 시스템에 넣기 좋은 형태는 `INNODB_METRICS` 다.

```sql
SELECT NAME, COUNT, STATUS
  FROM information_schema.INNODB_METRICS
 WHERE NAME = 'trx_rseg_history_len';

-- STATUS 가 disabled 로 나오면 카운터를 켜다 (동적)
SET GLOBAL innodb_monitor_enable = 'trx_rseg_history_len';
```

정상 범위는 워크로드에 따라 다르지만 기준선은 잡을 수 있다. 대부분의 OLTP 서버는 평상시 수백~수천 수준에서 진동하고, 배치 시간대에 수만까지 올랐다가 배치 종료 후 분 단위로 원위치한다. 위험 신호는 절대값보다 **형태**다. 값이 단조 증가하며 내려오지 않는다면 퍼지가 특정 시점 이후 완전히 멈춘 것이고, 이는 거의 예외 없이 그 시점에 열려서 아직 닫히지 않은 트랜잭션이 있다는 뜻이다. 실무 임계치로는 100만을 경고, 1,000만을 심각으로 두고 함께 언두 테이블스페이스 파일 크기를 같이 알람하는 구성이 무난하다. 단순 임계치보다 "60분 이동 최솟값이 계속 상승하는가"를 보는 편이 오탐이 적다.

## 6. 팽창의 원인 분류

첫째, 오래 열린 ReadView 다. 몇 시간짜리 집계 쿼리, `START TRANSACTION WITH CONSISTENT SNAPSHOT` 으로 스냅샷을 잡아둔 덤프 세션, 트랜잭션을 시작해놓고 애플리케이션 예외로 커밋도 롤백도 하지 못한 채 커넥션 풀에 반납된 세션, 그리고 `mysqldump --single-transaction` 이 대표적이다. 특히 `autocommit=0` 인 접속에서는 단순 SELECT 하나만 실행해도 트랜잭션이 열리고 REPEATABLE READ ReadView 가 COMMIT 까지 유지된다. 커넥션 풀이 그 커넥션을 재사용하지 않고 오래 붙잡고 있으면 아무도 "쿼리를 실행 중"이 아닌데 HLL 이 오른다.

```sql
-- 재현: 이 세션은 SELECT 한 번만 하고 아무것도 안 하지만 퍼지를 막는다
SET autocommit = 0;
SELECT COUNT(*) FROM orders;   -- ReadView 생성
-- (이후 수 시간 idle)          -- trx_state = RUNNING, 퍼지 정지
```

둘째, 대량 UPDATE/DELETE 다. 1,000만 건을 한 문장으로 DELETE 하면 1,000만 개의 update undo 레코드가 한 번에 생기고, 커밋 직후 HLL 이 그 규모만큼 치솟는다. 이 자체는 일시적이지만, 같은 시간대에 장기 조회가 겹치면 회수되지 못한 채 눌러앜는다.

셋째, 퍼지 처리 능력 부족이다. 초당 언두 생성량이 퍼지 처리량을 항상 웃도는 구조라면 장기 트랜잭션이 없어도 HLL 이 완만하게 우상향한다. 이 경우에만 `innodb_purge_threads` 상향이 진짜 해법이다.

넷째, 레플리카다. 레플리카에서 도는 긴 분석 쿼리는 그 레플리카의 퍼지를 막는다. 소스는 멀쥱한데 레플리카만 언두가 부풀고 복제 지연이 함께 늘어나는 패턴이 여기 해당한다.

## 7. 팽창이 성능으로 드러나는 방식

디스크 사용량 증가는 눈에 띄니 그나마 낫다. 더 고약한 것은 **같은 쿼리가 같은 데이터에 대해 점점 느려지는 현상**이다. 세컨더리 인덱스 레코드에는 행별 `DB_TRX_ID` 가 없고, 대신 페이지 헤더의 `PAGE_MAX_TRX_ID` 로 "이 페이지에 내 ReadView 보다 최신인 변경이 없음"을 빠르게 판정한다. 이 판정에 실패하면 세컨더리 인덱스 스캔은 각 엔트리마다 클러스터형 인덱스를 조회하고, 필요하면 언두 체인을 되짚어야 한다. 커버링 인덱스로 끝나던 쿼리가 갑자기 클러스터형 인덱스 랜덤 접근 + 언두 페이지 접근으로 바뀌면 논리적 읽기 수가 한 자릿수 배로 늘어난다. 실행 계획은 그대로인데 시간만 늘어나므로 옵티마이저를 의심하다 시간을 버리기 쉽다.

delete-mark 된 레코드가 퍼지되지 않고 남아 있는 것도 같은 방향으로 작용한다. 100만 건을 지웠지만 퍼지가 밀려 있다면 범위 스캔은 여전히 그 100만 건을 읽고 버린다. `SHOW ENGINE INNODB STATUS` 의 `Number of rows read` 는 늘어나는데 `Rows sent` 는 그대로인 괴리로 나타난다.

버퍼 풀 오염은 세 번째 비용이다. 언두 페이지와 곷 사라질 delete-mark 페이지가 버퍼 풀을 차지하면 실제 워킹셋이 밀려난다.

```sql
-- 버퍼 풀에서 언두 로그 페이지가 차지하는 비중 (부하가 큰 조회이므로 운영에졌 신중히)
SELECT PAGE_TYPE, COUNT(*) AS pages,
       ROUND(COUNT(*) * @@innodb_page_size / 1024 / 1024, 1) AS mb
  FROM information_schema.INNODB_BUFFER_PAGE
 GROUP BY PAGE_TYPE
 ORDER BY pages DESC;
```

언두 페이지 비중이 수 퍼센트를 넘어가면 버퍼 풀 히트율 하락과 HLL 상승이 같은 원인에서 나온 것이라고 봐도 좁다.

## 8. 진단 쿼리와 대응 절차

가장 먼저 볼 것은 가장 오래된 트랜잭션이다. `trx_started` 만 보면 "지금 무슨 쿼리를 실행 중인가"와 무관하게 열린 시간을 알 수 있다.

```sql
SELECT t.trx_id,
       t.trx_state,
       t.trx_started,
       TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS open_sec,
       t.trx_rows_modified,
       t.trx_isolation_level,
       p.ID AS conn_id, p.USER, p.HOST, p.DB, p.TIME AS thread_idle_sec,
       LEFT(COALESCE(t.trx_query, '(idle)'), 80) AS cur_query
  FROM information_schema.INNODB_TRX t
  JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id
 WHERE TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) > 60
 ORDER BY t.trx_started;
```

`trx_query` 가 `(idle)` 인데 `open_sec` 이 수천 초라면 커밋을 잊은 세션이다. 이런 세션은 `KILL <conn_id>` 로 끊으면 롤백되면서 ReadView 가 해제되고 퍼지가 즉시 재개된다. 다만 대량 변경을 한 트랜잭션이라면 롤백 자체가 오래 걸리므로, 끊기 전 `trx_rows_modified` 를 확인해야 한다.

트랜잭션이 열린 이유를 애플리케이션 레벨에서 추적하려면 performance_schema 의 트랜잭션 계측을 켜다. 기본적으로 꺼져 있다.

```sql
UPDATE performance_schema.setup_instruments
   SET ENABLED = 'YES', TIMED = 'YES'
 WHERE NAME = 'transaction';

SELECT t.THREAD_ID, t.STATE,
       ROUND(t.TIMER_WAIT/1e12, 1) AS elapsed_sec,
       s.PROCESSLIST_USER, s.PROCESSLIST_HOST,
       (SELECT SQL_TEXT FROM performance_schema.events_statements_current c
         WHERE c.THREAD_ID = t.THREAD_ID) AS last_stmt
  FROM performance_schema.events_transactions_current t
  JOIN performance_schema.threads s ON s.THREAD_ID = t.THREAD_ID
 WHERE t.STATE = 'ACTIVE'
 ORDER BY t.TIMER_WAIT DESC
 LIMIT 10;
```

잠금 대기가 함께 얘혀 있으면 sys 스키마가 가장 빠르다. `sys.innodb_lock_waits` 는 `performance_schema.data_locks` 와 `data_lock_waits` 를 조인해 블로커와 대기자를 한 줄로 보여주고, `sql_kill_blocking_connection` 컴럼에 바로 실행 가능한 KILL 문을 만들어 준다.

```sql
SELECT wait_age, locked_table, waiting_query,
       blocking_trx_id, blocking_query, sql_kill_blocking_connection
  FROM sys.innodb_lock_waits
 ORDER BY wait_age DESC;
```

대응은 원인별로 다르다. 대량 DELETE 는 청크로 쪼개 각 청크를 별도 트랜잭션으로 커밋한다. 이렇게 하면 한 트랜잭션이 만드는 언두 양이 제한되고 매 커밋 직후 퍼지가 회수를 시작할 수 있다.

```sql
-- 기준 컴럼에 인덱스가 있어야 한다. 5,000건씩, 퍼지가 따라올 시간을 준다
DELETE FROM audit_log
 WHERE created_at < '2026-01-01'
 ORDER BY id
 LIMIT 5000;
-- 애플리케이션 루프: affected_rows 가 0 이 될 때까지 반복, 사이에 0.1~0.5초 sleep
-- 주기적으로 trx_rseg_history_len 을 읽어 임계치 초과 시 루프를 일시 정지
```

리포팅·집계 쿼리는 레플리카로 분리하되, 그 레플리카에서도 퍼지가 막히므로 분석 전용 레플리카를 따로 두거나 해당 세션의 격리 수준을 READ COMMITTED 로 낮춰 ReadView 유지 구간을 문장 단위로 줄인다. 커넥션 풀 쪽에서는 `autocommit` 설정과 유휴 커넥션 정책을 함께 점검한다.

ORM 은 별도로 봐야 한다. Spring Boot 의 `spring.jpa.open-in-view` 는 기본값이 true 라서 영속성 컨텍스트와 DB 커넥션이 HTTP 요청 전 구간 동안 유지된다. 뷰 렌더링이나 외부 API 호출이 요청 처리에 끼어 있으면 그 대기 시간만큼 커넥션이 붙잡히고, `autocommit=0` 조합에서는 ReadView 까지 함께 살아남는다. 여기에 `@Transactional` 을 서비스 최상단에 넓게 걸고 그 안에서 외부 HTTP 호출을 하는 코드가 겹치면, 네트워크 지연이 그대로 트랜잭션 길이가 된다.

```properties
# 요청 전체가 아니라 서비스 계층 트랜잭션 구간에서만 커넥션을 잡는다
spring.jpa.open-in-view=false
# 누수된 커넥션을 조기에 드러낸다 (ms)
spring.datasource.hikari.leak-detection-threshold=20000
spring.datasource.hikari.max-lifetime=600000
```

트랜잭션 경계 안에서는 외부 호출·파일 IO 를 금지하고, 대량 배치는 `@Transactional` 을 청크 단위 메서드에 거는 식으로 경계를 잘게 나눈다.

## 9. Oracle UNDO 와의 구조적 비교

Oracle 과 InnoDB 는 "이전 이미지를 별도 영역에 두고 일관된 읽기를 제공한다"는 설계 철학을 공유하지만, 공간이 부족하거나 이전 이미지가 필요할 때의 행동이 정반대다.

| 항목 | Oracle Database | MySQL InnoDB |
| --- | --- | --- |
| 저장 영역 | UNDO 테이블스페이스(자동 관리) | 독립 언두 테이블스페이스(8.0 기본 2개) |
| 읽기 일관성 단위 | SCN 기반 CR 블록 재구성 | ReadView + 행별 언두 체인 재구성 |
| 보존 정책 | `UNDO_RETENTION`(기본 900초) + 자동 튜닝 | 시간 기반 보존 정책 없음. 오래된 ReadView 가 있으면 무기한 보존 |
| 회수 주체 | 만료(expired) 익스텐트를 SMON/자동 관리가 재사용 | 퍼지 스레드가 update undo 회수 및 delete-mark 제거 |
| 공간 부족 시 | 만료 안 된 언두를 덮어써 `ORA-01555 snapshot too old` 로 조회 실패 | 쿼리는 실패하지 않고 언두 테이블스페이스가 계속 증가 |
| 강제 보존 | `ALTER TABLESPACE undotbs1 RETENTION GUARANTEE` | 동등 기능 없음(사실상 항상 보장) |
| 주요 관측 뷰 | `V$UNDOSTAT`, `V$TRANSACTION`, `DBA_HIST_UNDOSTAT` | `INNODB_TRX`, `INNODB_METRICS`, `SHOW ENGINE INNODB STATUS` |
| 전형적 장애 증상 | 긴 조회가 ORA-01555 로 중단 | 디스크 고갈 또는 전체 쿼리의 점진적 성능 저하 |

Oracle 경험자가 InnoDB 로 넘어올 때 가장 자주 하는 오해가 "InnoDB 에는 ORA-01555 가 없으니 이 영역은 신경 안 써도 된다"는 판단이다. 실제로는 실패로 즉시 드러나던 문제가 디스크 증가와 완만한 성능 저하라는 형태로 바뀜을 뿐이고, 감지가 늦는 만큼 복구도 어렵다. Oracle 의 `V$UNDOSTAT.TUNED_UNDORETENTION` 을 보던 습관을 `trx_rseg_history_len` 추세 감시로 옮겨오는 것이, 두 엔진을 오가며 운영할 때 가장 실용적인 대응이다.

## 참고

- [MySQL 8.0 Reference Manual: InnoDB Multi-Versioning](https://dev.mysql.com/doc/refman/8.0/en/innodb-multi-versioning.html)
- [MySQL 8.0 Reference Manual: Undo Logs](https://dev.mysql.com/doc/refman/8.0/en/innodb-undo-logs.html)
- [MySQL 8.0 Reference Manual: Undo Tablespaces](https://dev.mysql.com/doc/refman/8.0/en/innodb-undo-tablespaces.html)
- [MySQL 8.0 Reference Manual: Purge Configuration](https://dev.mysql.com/doc/refman/8.0/en/innodb-purge-configuration.html)
- [MySQL 8.0 Reference Manual: InnoDB Startup Options and System Variables](https://dev.mysql.com/doc/refman/8.0/en/innodb-parameters.html)
- [MySQL 8.0 Reference Manual: InnoDB Standard Monitor Output](https://dev.mysql.com/doc/refman/8.0/en/innodb-standard-monitor.html)
- [MySQL 8.0 Reference Manual: The INFORMATION_SCHEMA INNODB_TRX Table](https://dev.mysql.com/doc/refman/8.0/en/information-schema-innodb-trx-table.html)
- [MySQL 8.0 Reference Manual: The INFORMATION_SCHEMA INNODB_METRICS Table](https://dev.mysql.com/doc/refman/8.0/en/information-schema-innodb-metrics-table.html)
- [MySQL 8.0 Reference Manual: The innodb_lock_waits and x$innodb_lock_waits Views](https://dev.mysql.com/doc/refman/8.0/en/sys-innodb-lock-waits.html)
- [Oracle Database Administrator's Guide: Managing Undo](https://docs.oracle.com/en/database/oracle/oracle-database/19/admin/managing-undo.html)
