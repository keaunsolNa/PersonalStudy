Notion 원본: https://www.notion.so/3845a06fd6d3813580acc208764c6504

# MySQL 복제 Semi-Sync와 GTID 및 Binlog 포맷 Row Statement Mixed

> 2026-06-19 신규 주제 · 확장 대상: MySQL

## 학습 목표

- 비동기·반동기·그룹 복제의 내구성과 지연 트레이드오프를 구분한다
- binlog 포맷 STATEMENT / ROW / MIXED의 안전성과 크기 차이를 분석한다
- GTID 기반 복제로 페일오버 시 위치 추적과 자동 포지셔닝을 구현한다
- 복제 지연(replica lag) 원인을 진단하고 병렬 복제로 완화한다

## 1. 복제의 토대: Binary Log

MySQL 복제의 모든 것은 binlog에서 출발한다. 소스는 데이터 변경 트랜잭션을 binlog에 순서대로 기록하고, 레플리카는 이를 받아 재생한다. 소스의 binlog dump 스레드가 이벤트를 보내고, 레플리카 I/O 스레드가 relay log에 쓰며, SQL 스레드(applier)가 적용한다.

```ini
[mysqld]
server_id = 1
log_bin = mysql-bin
binlog_format = ROW
gtid_mode = ON
enforce_gtid_consistency = ON
```

binlog는 복제 외에 시점 복구(PITR)에도 쓰인다. 전체 백업 + 이후 binlog 재생으로 특정 시점 상태를 복원한다.

## 2. Binlog 포맷: STATEMENT vs ROW vs MIXED

| 포맷 | 기록 내용 | 크기 | 안전성 |
|---|---|---|---|
| STATEMENT | 실행된 SQL 문 | 작음 | 비결정 함수에 취약 |
| ROW | 변경된 행의 before/after | 큼 | 가장 안전(권장) |
| MIXED | 기본 STATEMENT, 위험 시 ROW | 중간 | 절충 |

STATEMENT는 SQL 문 자체를 기록해 작지만 NOW(), UUID(), RAND() 같은 비결정적 요소가 있으면 소스와 레플리카가 갈라진다. ROW는 실제 바뀜 행의 이미지를 기록해 가장 안전하며 현대 MySQL 8.0의 기본이다. binlog_row_image=minimal로 크기를 줄일 수 있다. MIXED는 비결정 구문만 ROW로 자동 전환한다.

## 3. 비동기 vs 반동기(Semi-Sync) 복제

기본 복제는 비동기다. 소스는 binlog에 쓰고 커밋을 즉시 응답한다. 커밋 직후 소스가 죽으면 미전달 트랜잭션이 유실될 수 있다. 반동기는 커밋 응답 전 적어도 한 레플리카가 relay log에 기록했다는 ACK를 기다린다.

```sql
INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
SET GLOBAL rpl_semi_sync_source_enabled = 1;
SET GLOBAL rpl_semi_sync_source_timeout = 1000;
```

주의: 반동기는 수신(relay log 기록)까지만 보장하고 적용은 아니다. timeout 안에 ACK를 못 받으면 비동기로 폴백한다. 내구성 순서: 비동기 < 반동기 < 그룹 복제.

## 4. GTID: 트랜잭션 단위 전역 식별자

전통 복제는 binlog 파일명+오프셋으로 위치를 추적해 페일오버 시 악몽이다. GTID는 각 트랜잭션에 소스UUID:트랜잭션번호 형태의 전역 고유 ID를 부여한다.

```sql
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST = '10.0.0.1', SOURCE_USER = 'repl',
  SOURCE_AUTO_POSITION = 1;
START REPLICA;
SELECT @@GLOBAL.gtid_executed;
```

레플리카는 이미 적용한 GTID 집합(gtid_executed)을 알아 아직 적용 안 한 GTID만 골라 받는다. 파일·오프셋을 몰라도 자동 재개된다. 이미 실행한 GTID는 재적용되지 않아 멱등성도 좋다. enforce_gtid_consistency=ON은 호환 안 되는 구문을 막는다.

## 5. 복제 지연(Replica Lag) 진단

```sql
SHOW REPLICA STATUS\G
-- Seconds_Behind_Source, Replica_IO_Running/SQL_Running,
-- Retrieved_Gtid_Set vs Executed_Gtid_Set
```

Seconds_Behind_Source는 SQL 스레드 기준이라 부정확할 수 있어, 받은 것과 적용한 것의 GTID set 차이로 구분하는 것이 정확하다. 근본 원인은 보통 단일 SQL 스레드 병목, 큰 트랜잭션, 인덱스 없는 대량 UPDATE다.

## 6. 병렬 복제(Multi-Threaded Replica)

MySQL 8.0은 멀티스레드 applier로 지연을 완화한다. 소스에서 동시 커밋된(서로 의존하지 않는) 트랜잭션을 병렬 적용한다(LOGICAL_CLOCK).

```sql
SET GLOBAL replica_parallel_workers = 8;
SET GLOBAL replica_parallel_type = 'LOGICAL_CLOCK';
SET GLOBAL replica_preserve_commit_order = ON;
```

원리는 binlog group commit의 commit timestamp(논리 시계)다. 같은 그룹으로 동시 커밋된 트랜잭션은 충돌하지 않음이 보장되어 병렬 적용해도 안전하다. preserve_commit_order=ON은 최종 커밋 순서를 소스와 동일하게 유지한다.

## 7. 운영 체크리스트와 일관성 읽기

쓰기 직후 최신 읽기가 필요한 요청은 소스로 라우팅(read-your-writes)하거나 GTID로 적용 완료를 기다린 뒤 읽는다.

```sql
SELECT WAIT_FOR_EXECUTED_GTID_SET('3E11FA47-...:23', 1);
```

binlog 보존 기간이 가장 느린 레플리카 따라잡기보다 길어야 하고, 반동기 timeout 빈도·GTID gap 연속성을 감시한다. 페일오버 리허설로 GTID 자동 포지셔닝이 실제 동작하는지 검증한다.

## 8. Group Replication과 InnoDB Cluster: 합의 기반 복제

반동기도 완전 무손실을 보장하진 못한다. MySQL Group Replication(MGR)은 Paxos 변형 합의로 트랜잭션을 다수(quorum)가 합의해야 커밋한다. single-primary는 한 노드만 쓰기를 받고 장애 시 자동 선출, multi-primary는 모두 쓰기를 받되 인증 단계에서 충돌을 감지한다.

| 방식 | 커밋 합의 | 자동 페일오버 | 무손실 |
|---|---|---|---|
| 비동기 | 없음 | 외부 도구 | 아니오 |
| 반동기 | 레플리카 1대 ACK | 외부 도구 | 조건부 |
| Group Replication | 다수 합의 | 내장 | 예(quorum 유지) |

MGR 위에 운영 자동화를 얹은 것이 InnoDB Cluster다. MySQL Router가 읽기/쓰기를 현재 프라이머리와 레플리카로 자동 라우팅한다. 트레이드오프: MGR은 합의 라운드로 커밋 지연이 늘고 네트워크에 민감하며, quorum을 잃으면 멈춰 split-brain을 방지한다(CAP에서 일관성 선택).

## 참고

- MySQL 8.0 Reference Manual — Replication (Binary Log Formats, Semisynchronous Replication)
- MySQL 8.0 Reference Manual — Replication with Global Transaction Identifiers
- MySQL 8.0 Reference Manual — Improving Replication Performance (Multithreaded Replicas)
- MySQL 8.0 Reference Manual — The Binary Log / Point-in-Time Recovery
