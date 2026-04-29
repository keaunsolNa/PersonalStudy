Notion 원본: https://www.notion.so/3515a06fd6d3819ebbf9c880c513f8ac

# MySQL Binlog GTID 복제와 Replica Crash Recovery

> 2026-04-29 신규 주제 · 확장 대상: MySQL

## 학습 목표

- MySQL binlog 의 세 포맷(STATEMENT/ROW/MIXED) 차이와 복제 안전성 비교
- GTID(Global Transaction Identifier) 가 해결하는 file/position 기반 복제의 한계 분석
- semi-sync replication 과 ACK 타이밍의 데이터 손실 시나리오 파악
- replica crash 이후 `relay_log_recovery=ON` 의 동작과 일관성 보장 메커니즘 이해

## 1. Binlog 의 정체 — Statement, Row, Mixed

MySQL 의 binary log 는 데이터 변경 이벤트를 직렬화한 append-only 파일이다. 복제, point-in-time recovery, CDC(예: Debezium) 가 모두 이 파일을 읽는다. 포맷은 세 가지다.

| 포맷 | 기록 단위 | 비결정적 함수 | 사이즈 |
| --- | --- | --- | --- |
| STATEMENT | SQL 그 자체 | NOW(), UUID() 등은 위험 | 작음 |
| ROW | 변경된 행 이미지 | 항상 안전 | 큼 (UPDATE 1행도 before/after 기록) |
| MIXED | 안전하면 STATEMENT, 위험하면 ROW | 케이스별 자동 | 중간 |

기본값은 8.0 부터 ROW 다. 운영에서 STATEMENT 가 위험한 대표 케이스는 다음과 같다.

```sql
-- master 와 replica 가 다른 시각에 실행되면 다른 결과를 만든다
INSERT INTO audit (id, created_at) VALUES (?, NOW());

-- AUTO_INCREMENT + INSERT ... SELECT 동시 실행 시 row 순서가 달라질 수 있음
INSERT INTO target (val) SELECT val FROM source ORDER BY id LIMIT 1000;

-- 트리거 안에서 비결정적 함수
CREATE TRIGGER ... INSERT INTO log VALUES (RAND() * 100);
```

ROW 포맷은 binlog 사이즈가 커지지만 (대용량 UPDATE 의 경우 BLOB 컬럼까지 before/after 모두 기록) 데이터 일관성을 깨지 않는다. 디스크 비용보다 일관성을 우선해야 한다.

`binlog_row_image` 를 `MINIMAL` 로 설정하면 PK + 변경 컬럼만 기록해 사이즈를 줄일 수 있지만, CDC 사용 시에는 `FULL` 이 필요하다.

## 2. File/Position 복제의 한계와 GTID 의 등장

MySQL 5.5 까지 표준이었던 비-GTID 복제는 replica 가 master 의 binlog 파일명과 offset 을 추적했다.

```sql
CHANGE MASTER TO
  MASTER_HOST='primary.db',
  MASTER_LOG_FILE='mysql-bin.000123',
  MASTER_LOG_POS=4567890;
```

이 모델의 결정적 약점은 **master 장애 시 새 master 의 file/position 을 다른 replica 들이 모른다는 점** 이다. 복제 토폴로지가 A(primary) → B, C 였는데 A 가 죽어 B 를 새 master 로 승격하면, C 는 B 의 binlog 안에서 자기가 마지막으로 적용한 트랜잭션이 어디인지 직접 찾아야 한다.

GTID 는 이 문제를 해결한다. 각 트랜잭션은 클러스터 전역에서 유일한 ID 를 가진다.

```
GTID = server_uuid : transaction_id
예: 3E11FA47-71CA-11E1-9E33-C80AA9429562:23
```

replica 는 자기가 적용한 GTID 집합(`gtid_executed`) 을 안다. 새 master 로 붙을 때 "내가 적용 안 한 GTID 만 보내줘" 라고 요청하면 끝이다. 운영 명령은 한 줄로 줄어든다.

```sql
CHANGE MASTER TO
  MASTER_HOST='new-primary.db',
  MASTER_AUTO_POSITION=1;
```

활성화 옵션:

```ini
[mysqld]
gtid_mode = ON
enforce_gtid_consistency = ON
log_bin = ON
log_replica_updates = ON
binlog_format = ROW
```

`enforce_gtid_consistency` 는 GTID 와 호환되지 않는 SQL(예: `CREATE TABLE ... SELECT`, 트랜잭션 내 비-트랜잭션 테이블 변경) 을 거부한다. 이 옵션 없이 GTID 를 켜면 일부 트랜잭션이 깨질 수 있다.

## 3. 비동기/Semi-sync/Sync — 데이터 손실 모델

MySQL 의 복제는 기본 비동기다. master 는 commit 시 binlog 만 자신의 디스크에 fsync 하고 응답한다. replica 가 그 변경을 받기 전에 master 디스크가 손상되면 그 트랜잭션은 영구 손실된다.

semi-sync 는 master 가 commit 응답을 보내기 전에 **최소 N 개의 replica 가 binlog 를 받았다고 ACK** 하기를 기다린다.

```sql
INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
INSTALL PLUGIN rpl_semi_sync_replica SONAME 'semisync_replica.so';
SET GLOBAL rpl_semi_sync_source_enabled = 1;
SET GLOBAL rpl_semi_sync_source_wait_for_replica_count = 1;
SET GLOBAL rpl_semi_sync_source_timeout = 1000;  -- ms
```

여기서 핵심은 ACK 시점이다. MySQL 8.0 부터 `rpl_semi_sync_source_wait_point = AFTER_SYNC` 가 기본이다. 즉, master 가 binlog 를 디스크에 fsync 한 후, storage engine commit 전에 replica ACK 를 기다린다. 이 때문에 master 가 storage engine 커밋 직전 죽으면 client 는 commit 실패를 보지만 replica 는 이미 그 트랜잭션을 가지고 있을 수 있다 — fail-over 후 "phantom commit" 이 보일 수 있다는 뜻이다.

`AFTER_COMMIT` 옵션은 storage engine commit 이후 ACK 를 기다리는데, 이 경우 같은 row 를 동시에 읽는 다른 client 가 commit 된 데이터를 보지만 client 본인은 아직 응답을 못 받는 read-after-commit 일관성 문제가 생긴다. 트레이드오프를 정리하면 다음과 같다.

| 모드 | master 디스크 손상 시 손실 | fail-over 후 phantom commit |
| --- | --- | --- |
| 비동기 | 가능 | 없음 |
| semi-sync AFTER_SYNC | 거의 없음 | 가능 |
| semi-sync AFTER_COMMIT | 거의 없음 | 없음, 단 read-after-commit 비일관 |
| Group Replication (사실상 sync) | 없음 | 없음 |

대부분의 운영 환경에서는 `AFTER_SYNC` + 자동 페일오버가 합리적이다. ACK timeout 이 발생하면 master 는 자동으로 비동기 모드로 강등(degrade) 되며, 이 때 모든 손실 가능성이 다시 열린다는 점을 모니터링해야 한다.

## 4. Replica 의 두 스레드와 Relay Log

replica 는 두 스레드로 구성된다.

- **IO thread**: master 와 TCP 연결을 유지하며 binlog 를 읽어 로컬 `relay-bin.NNNNNN` 파일에 쓴다.
- **SQL thread**(또는 `replica_parallel_workers > 0` 시 coordinator + worker N 개): relay log 를 읽어 자기 storage engine 에 적용한다.

```
[master.binlog] -- TCP --> [replica.IO_thread] --> [replica.relay_log] --> [replica.SQL_thread] --> [InnoDB]
```

병렬 적용은 `replica_parallel_type = LOGICAL_CLOCK` + `binlog_transaction_dependency_tracking = WRITESET` 조합이 8.0 의 표준이다. master 가 트랜잭션 간 row 충돌이 없는지 미리 분석해 binlog 에 dependency tag 를 박고, replica 는 충돌 없는 그룹을 동시에 적용한다. 단일 스레드 적용 대비 5~10배 처리량 향상이 일반적이다.

```ini
[mysqld]
replica_parallel_workers = 8
replica_parallel_type = LOGICAL_CLOCK
binlog_transaction_dependency_tracking = WRITESET
replica_preserve_commit_order = ON
```

`replica_preserve_commit_order = ON` 은 master 의 commit 순서를 replica 에서도 유지한다. 이 옵션이 꺼져 있으면 같은 시점에 replica 를 읽는 client 가 master 와 다른 순서의 데이터를 볼 수 있어, 외부 cache invalidation 이나 write-then-read 패턴이 깨진다.

## 5. Relay Log Recovery — Crash-Safe Replica

replica 가 crash 했을 때 무엇이 일관적으로 남아 있어야 할까? 8.0 이전에는 다음 두 위치가 별도 파일에 기록되어 sync 되지 않았다.

- IO thread 가 master 로부터 받은 마지막 위치 (`master.info`)
- SQL thread 가 relay log 에서 적용한 마지막 위치 (`relay-log.info`)

만약 replica 가 IO thread 위치 까지만 fsync 하고 SQL thread 위치는 fsync 하지 않은 채 crash 하면, 부팅 후 replica 는 "내가 적용한 곳" 을 정확히 모른다. 같은 트랜잭션을 두 번 적용하거나(중복 PK 에러), 일부를 건너뛸 수 있다.

해법은 두 가지를 동시에 켜는 것이다.

```ini
[mysqld]
relay_log_recovery = ON
relay_log_info_repository = TABLE
master_info_repository = TABLE
sync_relay_log_info = 1
```

`relay_log_info_repository = TABLE` 은 적용 위치를 `mysql.slave_relay_log_info` InnoDB 테이블에 기록한다. SQL thread 가 트랜잭션을 commit 하는 같은 단위 안에서 위치도 갱신되므로 InnoDB 의 crash recovery 가 알아서 둘을 함께 복구한다.

`relay_log_recovery = ON` 은 부팅 시 crash 직전의 relay log 를 모두 버리고, 마지막 적용 위치(InnoDB 가 알려준 정확한 GTID) 부터 master 에게 다시 binlog 를 요청한다. 즉 IO thread 위치는 신뢰하지 않는다. 결과적으로 SQL thread 위치만 정확하면 일관성이 보장된다.

## 6. GTID 환경에서의 Failover 시나리오

3-노드 토폴로지(A=primary, B/C=replica) 에서 A 장애 후 B 를 승격하는 실제 절차를 GTID 기준으로 정리.

```sql
-- 1. 모든 replica 에서 IO/SQL thread 정지
STOP REPLICA;

-- 2. 어느 replica 가 가장 앞선지 확인 (gtid_executed 로 비교)
SHOW REPLICA STATUS\G
-- Executed_Gtid_Set 을 보고 가장 큰 GTID 를 가진 노드를 새 primary 로 선택

-- 3. 부족한 GTID 를 가진 다른 replica 에게 새 primary 의 누락 분을 적용시키지 않으면 안 됨.
--    이 단계는 "gtid_subset" 함수로 검증
SELECT GTID_SUBSET(@b_executed, @c_executed);  -- 1 이면 C 가 B 를 포함

-- 4. 새 primary 로 승격
RESET REPLICA ALL;          -- B 노드에서 replication 메타 제거
SET GLOBAL read_only = OFF;

-- 5. 다른 replica 들을 새 primary 에 붙임
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST = 'B-host',
  SOURCE_AUTO_POSITION = 1;
START REPLICA;
```

여기서 중요한 함정은 `GTID_SUBSET` 으로 검증해 replica 가 새 primary 를 포함하는 경우다. 이 때 replica 를 단순히 새 primary 에 붙이면 "이미 가지고 있는 GTID 는 보내지 않음" 으로 동작해 일관성이 유지되지만, 그 차이만큼의 트랜잭션은 영구 손실된다. 따라서 fail-over 전 모든 replica 가 같은 GTID 집합을 가지도록 catch-up 시키거나, semi-sync 로 가능성을 사전에 차단하는 것이 안전하다.

## 7. Errant Transaction — GTID 의 가장 흔한 함정

운영자가 replica 에 직접 INSERT 를 해버리면 그 트랜잭션은 새 GTID 를 가진 채 replica 의 binlog 에 기록된다. 이 replica 가 나중에 primary 로 승격되면 다른 replica 는 그 GTID 를 모르기 때문에 충돌이 생긴다. 이를 errant transaction 이라 한다.

```sql
-- replica B 에서 직접 INSERT
USE shop;
INSERT INTO promo VALUES (...);  -- 새 GTID 생성: B-uuid:42

-- B 를 primary 로 승격 후 C 를 붙이면 C 의 SQL_thread 가 B-uuid:42 를 적용할 수 없음
-- (이미 binlog 에 있는 GTID 이므로 conflict 가 아니라 적용 자체는 가능하지만,
-- 만약 같은 데이터가 다른 시점에 들어왔다면 PK 충돌 등으로 실패)
```

errant transaction 을 사전에 막는 두 가지 방법:

1. 모든 replica 를 `super_read_only = ON` 으로 운영. 관리자라도 INSERT 가 막힌다.
2. orchestrator, MHA 같은 fail-over 도구는 승격 직전 `GTID_SUBSET(slave_gtid, master_gtid)` 로 errant 를 검증한다.

이미 발생한 errant 를 처리하려면 새 primary 에서 해당 GTID 를 "executed" 로 주입하거나(주의: 데이터는 사라짐), 다른 replica 를 from-scratch 재구성하는 수밖에 없다.

## 참고

- MySQL 8.0 Reference Manual, Chapter 17 (Replication)
- Jeremy Cole, MySQL at Scale 시리즈 (https://blog.jcole.us)
- Henrik Ingo, "MySQL High Availability" (O'Reilly)
- Vitess 문서, GTID 기반 reparent 알고리즘 (https://vitess.io/docs/)
- Percona Blog, Semi-Sync Replication After-Sync vs After-Commit
