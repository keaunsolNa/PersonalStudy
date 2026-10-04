Notion 원본: https://www.notion.so/3ef5a06fd6d381868736c0b5b20ce6eb

# MySQL InnoDB Redo Undo 로그와 MVCC ReadView 및 Purge 지연 분석

> 2026-10-03 신규 주제 · 확장 대상: MySQL InnoDB Next Key Lock과 Gap Lock 및 데드락 분석, B Tree 인덱스 내부구조

## 학습 목표

- Redo(내구성)와 Undo(롤백·MVCC)의 역할 분담과 WAL 쓰기 순서를 설명한다.
- 레코드 숨은 컬럼과 ReadView 규칙으로 특정 트랜잭션이 보는 버전을 직접 판정한다.
- `innodb_flush_log_at_trx_commit`, `innodb_redo_log_capacity` 설정이 내구성과 처리량에 미치는 영향을 구분한다.
- History list length 증가의 원인(장기 트랜잭션)을 쿼리로 추적하고 해소한다.

## 1. 두 로그, 두 가지 약속

InnoDB는 한 트랜잭션의 변경을 두 종류의 로그로 기록한다. **Redo log**는 "커밋된 변경은 장애 후에도 반드시 남는다"는 내구성(Durability)을 위한 물리적·논리적 변경 기록이다. **Undo log**는 "변경 전 값"을 저장해 롤백과 다중 버전 읽기(MVCC)를 가능하게 한다. 둘은 목적이 다르므로 저장 위치와 수명도 다르다.

데이터 페이지는 버퍼 풀에서 먼저 수정되고, 디스크에는 나중에 비동기로 플러시된다(더티 페이지). 커밋 시점에 데이터 파일을 매번 동기 쓰기하면 랜덤 I/O 때문에 느리므로, InnoDB는 **Write-Ahead Logging**을 쓴다. 데이터 페이지보다 로그가 먼저 디스크에 안전하게 기록되어야 한다는 규칙이다. 로그는 순차 쓰기이므로 훨씬 빠르다. 장애가 나면 재시작 시 redo를 재생해 플러시되지 않은 변경을 복구하고, 커밋되지 않은 트랜잭션은 undo로 되돌린다.

## 2. Redo log 구조

Redo 레코드는 mini-transaction(mtr) 단위로 생성된다. mtr은 B-tree 페이지 분할처럼 "여러 페이지를 원자적으로 바꾸는 작은 작업 묶음"이다. 생성된 redo는 먼저 메모리의 **log buffer**(`innodb_log_buffer_size`)에 쌓이고, 커밋 또는 버퍼 포화 시 redo 파일로 기록된다. 각 레코드에는 단조 증가하는 **LSN**(Log Sequence Number)이 부여된다. 더티 페이지가 플러시된 위치까지를 **checkpoint LSN**이라 하며, checkpoint 이전의 redo는 재사용될 수 있다.

MySQL 8.0.30부터 redo 설정이 바뀌었다. 기존의 `innodb_log_file_size`와 `innodb_log_files_in_group` 대신 **`innodb_redo_log_capacity`** 하나로 총 용량을 지정하며, 값은 동적으로 변경할 수 있다. redo 파일은 데이터 디렉터리의 `#innodb_redo` 하위에 여러 개로 나뉘어 관리된다. 용량이 작으면 checkpoint가 따라가지 못해 쓰기가 막히는 구간이 생기고, 너무 크면 장애 복구 시간이 늘어난다. 쓰기 부하가 큰 시스템에서는 피크 시간 동안 생성되는 redo 양을 기준으로 잡는다. 대략의 측정은 `SHOW ENGINE INNODB STATUS`의 LSN 값을 일정 간격으로 두 번 읽어 차이를 시간으로 나누는 방식이다.

```sql
-- 현재 설정 확인
SHOW VARIABLES LIKE 'innodb_redo_log_capacity';
SHOW VARIABLES LIKE 'innodb_flush_log_at_trx_commit';
SHOW VARIABLES LIKE 'sync_binlog';

-- 용량 조정 (8.0.30+, 재시작 불필요)
SET GLOBAL innodb_redo_log_capacity = 4 * 1024 * 1024 * 1024;

-- LSN, checkpoint 확인
SHOW ENGINE INNODB STATUS\G   -- LOG 섹션: Log sequence number / Last checkpoint at
```

### 커밋 시점의 내구성 선택

`innodb_flush_log_at_trx_commit`은 커밋 때 redo를 어떻게 처리할지 정한다.

값 1(기본값)은 커밋마다 redo를 파일에 쓰고 `fsync`까지 수행한다. OS 크래시나 정전에도 커밋된 트랜잭션이 보존된다. 값 2는 커밋마다 OS 캐시에 쓰지만 `fsync`는 약 1초 간격으로 한다. MySQL 프로세스가 죽어도 OS가 살아있으면 안전하지만, OS 크래시 시 최근 약 1초의 커밋을 잃을 수 있다. 값 0은 약 1초마다 쓰고 플러시하며, mysqld 프로세스 크래시만으로도 최근 변경을 잃을 수 있다. 복제나 바이너리 로그와 함께 쓸 때는 `sync_binlog=1`과 `innodb_flush_log_at_trx_commit=1` 조합이 가장 안전한 "double 1" 구성이다. 처리량이 중요한 분석성 워크로드나 재생성 가능한 데이터에서만 완화를 검토한다.

**그룹 커밋**도 알아둘 가치가 있다. 여러 트랜잭션의 커밋이 동시에 들어오면 하나의 `fsync`로 묶어서 처리해 `fsync` 비용을 분산한다. 동시성이 높을수록 커밋당 `fsync` 부담이 줄어드는 이유다.

### 더블라이트 버퍼와의 관계

페이지는 16KB인데 디스크 쓰기는 더 작은 단위로 나뉠 수 있어, 쓰기 도중 장애가 나면 페이지가 반쯤만 기록(torn page)될 수 있다. redo는 "페이지에 대한 델타"이므로 손상된 페이지에는 적용할 수 없다. 그래서 InnoDB는 페이지를 데이터 파일에 쓰기 전에 **doublewrite** 영역에 먼저 쓴다. 8.0.20부터 doublewrite는 시스템 테이블스페이스가 아닌 별도 파일로 분리되었다. redo가 내구성의 전부가 아님을 보여주는 지점이다.

## 3. Undo log와 레코드 버전

클러스터드 인덱스의 각 레코드에는 숨은 컬럼이 있다. `DB_TRX_ID`(이 레코드를 마지막으로 수정한 트랜잭션 ID, 6바이트), `DB_ROLL_PTR`(undo 레코드를 가리키는 7바이트 포인터), 그리고 PK가 없으면 InnoDB가 만드는 `DB_ROW_ID`(6바이트)다. UPDATE는 레코드를 제자리에서 바꾸되, 변경 전 값을 undo 로그에 넣고 `DB_ROLL_PTR`로 연결한다. 이렇게 이전 버전들이 **연결 리스트(버전 체인)** 를 이룬다.

Undo 레코드는 크게 insert undo와 update undo로 나뉜다. INSERT로 생긴 undo는 해당 트랜잭션이 커밋되면 다른 트랜잭션이 읽을 필요가 없으므로 곧바로 정리 대상이 된다. UPDATE/DELETE의 undo는 이전 버전을 읽어야 하는 다른 트랜잭션이 남아있을 수 있어 **purge**가 안전하다고 판단할 때까지 보존된다.

Undo는 undo 테이블스페이스에 저장된다. 8.0에서는 기본으로 `undo_001`, `undo_002` 두 개의 독립 undo 테이블스페이스가 생성되며, 각각 최대 128개의 롤백 세그먼트를 가진다. `innodb_undo_log_truncate`를 켜면 임계 크기(`innodb_max_undo_log_size`)를 넘은 undo 테이블스페이스를 자동으로 축소할 수 있다. 장기 트랜잭션 때문에 부풀어 오른 undo를 되돌릴 수 있게 해주지만, 트랜잭션이 끝나기 전에는 줄지 않는다.

## 4. MVCC와 ReadView

일관된 읽기(consistent read)는 잠금 없이 수행된다. 읽기 트랜잭션은 **ReadView**라는 스냅샷 정보를 만들고, 레코드마다 "이 버전이 내게 보이는가"를 판정한다. ReadView의 핵심 필드는 다음과 같다.

`m_ids`는 ReadView 생성 시점에 활성 상태(미커밋)인 트랜잭션 ID 목록이다. `m_up_limit_id`는 `m_ids` 중 최솟값이며, 이보다 작은 ID는 이미 커밋된 것이다. `m_low_limit_id`는 ReadView 생성 시점에 아직 할당되지 않은 다음 트랜잭션 ID이며, 이 값 이상은 이후에 시작된 트랜잭션이다. `m_creator_trx_id`는 ReadView를 만든 트랜잭션 자신이다.

가시성 판정은 레코드의 `DB_TRX_ID`(= T)를 다음 순서로 검사한다.

1. T가 `m_creator_trx_id`와 같으면 자기 변경이므로 **보인다**.
2. T < `m_up_limit_id`이면 ReadView 이전에 커밋되었으므로 **보인다**.
3. T ≥ `m_low_limit_id`이면 ReadView 이후 시작된 트랜잭션이므로 **보이지 않는다**.
4. 그 사이라면 T가 `m_ids`에 있는지 본다. 있으면 아직 미커밋이므로 **보이지 않고**, 없으면 커밋되었으므로 **보인다**.

보이지 않으면 `DB_ROLL_PTR`을 따라 undo의 이전 버전으로 가서 같은 검사를 반복한다. 체인 끝까지 보이는 버전이 없으면 해당 행은 이 트랜잭션에게 존재하지 않는 것이다.

격리 수준의 차이는 **ReadView를 언제 만드는가**에서 나온다. REPEATABLE READ(MySQL 기본값)는 트랜잭션의 **첫 번째 일관된 읽기** 시점에 만들어 끝까지 재사용한다. READ COMMITTED는 **각 SELECT 문마다** 새로 만든다. 그래서 RR에서는 같은 쿼리가 항상 같은 결과를 내고, RC에서는 다른 트랜잭션의 커밋이 다음 쿼리에 보인다.

다음 시나리오로 확인할 수 있다. 세션 A와 B를 열고 순서대로 실행한다.

```sql
-- 준비
CREATE TABLE acct (id INT PRIMARY KEY, bal INT) ENGINE=InnoDB;
INSERT INTO acct VALUES (1, 100);

-- 세션 A (REPEATABLE READ)
START TRANSACTION;
SELECT bal FROM acct WHERE id = 1;          -- 100 (이때 ReadView 생성)

-- 세션 B
UPDATE acct SET bal = 200 WHERE id = 1;     -- autocommit

-- 세션 A
SELECT bal FROM acct WHERE id = 1;          -- 100 (RR: 기존 ReadView 재사용)
UPDATE acct SET bal = bal + 1 WHERE id = 1; -- 현재 읽기(current read): 최신 값 200을 기준으로 201
SELECT bal FROM acct WHERE id = 1;          -- 201 (자신의 변경은 보인다)
COMMIT;
```

마지막 쓰기가 눈에 띈다. `UPDATE`는 스냅샷이 아니라 **최신 커밋 값**(현재 읽기)을 기준으로 동작하므로 100이 아니라 200에서 증가한다. RR에서도 "스냅샷으로 읽고 최신 값으로 쓴다"는 비대칭이 있으며, 이것이 팬텀과 갱신 손실 논의의 출발점이다.

## 5. Purge와 History List Length

커밋된 트랜잭션의 update undo는 **history list**에 올라가고, 백그라운드 **purge 스레드**(`innodb_purge_threads`)가 "더는 어떤 ReadView도 이 버전을 필요로 하지 않을 때" undo를 삭제하고 delete-marked 레코드를 물리 삭제한다. 가장 오래된 활성 ReadView보다 이전에 커밋된 변경만 정리할 수 있다.

따라서 **오래 열려 있는 트랜잭션 하나가 purge 전체를 막는다.** 그 트랜잭션의 ReadView가 필요로 할 수 있는 모든 버전이 보존되기 때문이다. 증상은 다음과 같이 나타난다. History list length가 계속 증가하고, undo 테이블스페이스가 커지며, 버전 체인이 길어져 같은 행을 읽는 쿼리가 점점 느려진다. 인덱스 스캔에서 삭제 표시된 레코드를 건너뛰는 비용도 늘어난다.

```sql
-- History list length (두 가지 방법)
SELECT name, count FROM information_schema.innodb_metrics WHERE name = 'trx_rseg_history_len';
SHOW ENGINE INNODB STATUS\G   -- TRANSACTIONS 섹션: History list length N

-- 오래 열린 트랜잭션 찾기
SELECT trx_id, trx_state, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_sec,
       trx_mysql_thread_id, trx_rows_modified, trx_query
FROM information_schema.innodb_trx
ORDER BY trx_started
LIMIT 10;
```

`trx_rseg_history_len`이 `innodb_metrics`에서 바로 조회되지 않으면 `SET GLOBAL innodb_monitor_enable = 'trx_rseg_history_len';`로 카운터를 활성화한다. 장기 트랜잭션은 `trx_mysql_thread_id`로 `performance_schema.threads`나 `SHOW PROCESSLIST`와 매핑해 어떤 커넥션인지 특정한다. 흔한 원인은 애플리케이션이 트랜잭션을 연 채로 외부 API를 호출하거나, 커넥션 풀이 `autocommit=0` 상태의 커넥션을 반환하며 커밋하지 않는 경우, 그리고 대용량 배치와 덤프(`mysqldump --single-transaction`)다.

해소 방법은 우선 원인 트랜잭션을 종료하는 것(`KILL <thread_id>`)이다. 근본 대책은 트랜잭션 경계를 짧게 하고, 읽기 전용 작업은 필요 시 `START TRANSACTION READ ONLY`를 쓰며, 배치는 청크 단위로 커밋하는 것이다. 모니터링은 history list length에 경보 임계치를 두되, 절대값은 워크로드에 따라 다르므로 평상시 기준선을 먼저 관측해 정한다.

## 6. 설정 선택 요약과 trade-off

내구성과 지연의 균형은 `innodb_flush_log_at_trx_commit`이 결정한다. 처리량을 얻으려고 2로 낮출 수는 있지만, OS 크래시 시 최근 약 1초 분량의 커밋 유실 가능성을 허용하는 선택이다. redo 용량은 크게 잡을수록 checkpoint 압박이 줄어 쓰기 지연이 안정되지만 복구 시간이 늘어난다. undo는 RR의 일관된 읽기를 지탱하는 비용이다. RC로 낮추면 ReadView 수명이 짧아져 purge가 수월해지고 gap lock 사용이 줄어드는 대신, 한 트랜잭션 안에서 같은 쿼리의 결과가 달라질 수 있다. 격리 수준 변경은 애플리케이션 정합성 검토 후에 적용한다.

## 7. 크래시 복구 개요

재시작 시 InnoDB는 마지막 checkpoint LSN부터 redo를 순차 재생(roll-forward)해 버퍼 풀에 있던 변경을 데이터 페이지에 복원한다. 이어 커밋되지 않았던 트랜잭션은 undo로 롤백한다. 이때 XA·바이너리 로그와의 일관성은 2PC로 맞춘다. 커밋은 redo에 prepare 기록 → 바이너리 로그 기록 → redo에 commit 표시 순서로 진행되며, 복구 시 바이너리 로그에 있는 트랜잭션은 commit으로, 없는 것은 롤백으로 결정한다. `sync_binlog=1`이 중요한 이유다.

## 8. 검증 체크리스트

운영 DB에 적용하기 전에 개발 환경에서 다음을 확인한다. 위 SQL 시나리오는 MySQL 8.0 이상의 InnoDB 테이블에서 재현할 수 있다(본 노트 작성 환경에서는 MySQL을 실행하지 않았으므로 직접 실행해 결과를 확인해야 한다).

첫째, RR과 RC 각각에서 4절 시나리오를 돌려 두 번째 SELECT 결과가 100과 200으로 갈리는지 확인한다. 둘째, 세션 A에서 `START TRANSACTION WITH CONSISTENT SNAPSHOT;`을 열어 둔 채 세션 B에서 10만 건 UPDATE를 수행하고, 5절 쿼리로 `trx_rseg_history_len`이 증가하는지 관찰한 뒤 A를 종료해 감소하는지 본다. 셋째, `innodb_flush_log_at_trx_commit` 값을 바꿔 가며 단건 INSERT 루프의 초당 커밋 수를 비교하되, 스토리지 종류와 캐시 설정에 따라 결과가 크게 다르므로 절대 수치를 일반화하지 않는다.

## 참고

- MySQL 8.0 Reference Manual: The InnoDB Storage Engine — Redo Log, Undo Logs, InnoDB Multi-Versioning
- MySQL 8.0 Reference Manual: `innodb_redo_log_capacity`, `innodb_flush_log_at_trx_commit`
- MySQL 8.0 Reference Manual: InnoDB Purge Configuration, InnoDB INFORMATION_SCHEMA Metrics Table
- Jeremy Cole, "The basics of the InnoDB undo logging and history system"
