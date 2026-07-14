Notion 원본: https://app.notion.com/p/39d5a06fd6d381d680b9cadda88cc165

# MySQL 복제 GTID와 반동기 복제 및 병렬 적용 워커

> 2026-07-14 신규 주제 · 확장 대상: MySQL

## 학습 목표

- 바이너리 로그 기반 복제의 이벤트 흐름(dump → relay → apply)을 단계별로 이해한다
- GTID가 포지션 기반 복제의 페일오버 문제를 어떻게 해결하는지 확인한다
- 비동기·반동기·손실 없는 반동기 복제의 내구성 차이를 구분한다
- 병렬 복제 워커(LOGICAL_CLOCK)가 복제 지연을 줄이는 원리를 파악한다

## 1. 복제의 기본 골격

MySQL 복제는 소스의 변경을 바이너리 로그에 기록하고 레플리카가 재생하는 구조다. 소스의 binlog dump 스레드가 이벤트를 스트리밍하고, 레플리카의 I/O 스레드가 릴레이 로그에 기록하며, SQL 스레드(applier)가 실제 적용한다. 바이너리 로그 포맷은 STATEMENT, ROW, MIXED가 있으며 실무 기본은 ROW다. ROW는 변경된 행의 before/after 이미지를 기록해 결정적이고 CDC 도구 호환성이 좋다.

## 2. 포지션 기반 복제의 문제

전통 복제는 레플리카가 소스의 binlog 파일명과 오프셋으로 위치를 추적한다.

```sql
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='10.0.0.1',
  SOURCE_LOG_FILE='mysql-bin.000042',
  SOURCE_LOG_POS=15320;
```

페일오버 때 소스 A가 죽어 레플리카 B를 승격하면, 다른 레플리카 C는 A 기준 오프셋을 갖고 있는데 이 값은 B의 binlog에서 전혀 다른 위치를 가리켜 수동 계산이 필요했다. 이 취약성이 자동 페일오버를 어렵게 만들었다.

## 3. GTID: 트랜잭션에 전역 신원 부여

GTID는 각 트랜잭션에 `source_uuid:transaction_id` 형태의 전역 고유 ID를 부여한다. 이 ID는 복제 토폴로지 전체로 전파되므로 오프셋이 아닌 ID로 "이미 적용했는가"를 판단한다.

```sql
SET GLOBAL gtid_mode = ON;
SET GLOBAL enforce_gtid_consistency = ON;
CHANGE REPLICATION SOURCE TO SOURCE_HOST='10.0.0.1', SOURCE_AUTO_POSITION = 1;
```

`SOURCE_AUTO_POSITION=1`이면 레플리카가 자신의 `gtid_executed`를 소스에 알리고 소스는 그 집합에 없는 트랜잭션만 보낸다. 페일오버 시 GTID 집합 비교로 빠진 트랜잭션이 자동 계산되어 자동 페일오버 도구(Orchestrator, MHA)의 토대가 된다.

## 4. 비동기 복제의 내구성 공백

기본 복제는 완전 비동기다. 소스는 로컬에 커밋하고 응답한 뒤 별개로 이벤트를 흘린다. 소스가 커밋 직후·전파 전에 크래시하면 클라이언트는 성공을 받았지만 그 트랜잭션은 어느 레플리카에도 없어 승격 시 영구 소실된다.

## 5. 반동기 복제(Semisynchronous)

반동기는 소스가 커밋을 응답하기 전에 최소 한 레플리카가 이벤트를 릴레이 로그에 기록하고 ACK를 보낼 때까지 기다린다.

```sql
INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
SET GLOBAL rpl_semi_sync_source_enabled = 1;
SET GLOBAL rpl_semi_sync_source_timeout = 1000;   -- ms
```

결정적 파라미터는 `rpl_semi_sync_source_wait_point`다. `AFTER_SYNC`(손실 없는 반동기, 기본)면 엔진 커밋 전에 ACK를 기다려 크래시 후에도 팬텀 리드가 없다. `AFTER_COMMIT`은 커밋 후 ACK를 기다려 미복제 트랜잭션을 다른 세션이 읽을 수 있다. 함정: 타임아웃이 지나면 소스가 조용히 비동기로 강등하므로 `Rpl_semi_sync_source_status`를 모니터링해야 한다.

## 6. 복제 지연과 단일 스레드 적용의 병목

전통적으로 레플리카의 SQL 스레드는 릴레이 로그를 하나씩 순차 적용했다. 소스의 병렬 쓰기 처리량을 단일 스레드로 따라잡지 못하면 복제 지연(Seconds_Behind_Source)이 누적된다.

## 7. 병렬 복제 워커: LOGICAL_CLOCK

`replica_parallel_type = LOGICAL_CLOCK`은 소스에서 같은 그룹 커밋 구간에 함께 커밋된 트랜잭션은 잠금 충돌이 없었다는 사실을 이용한다. 소스가 `last_committed`와 `sequence_number`를 binlog에 기록하고, 레플리카는 `last_committed`가 같은 트랜잭션들을 다른 워커에 분배해 병렬 실행한다.

```sql
SET GLOBAL replica_parallel_workers = 8;
SET GLOBAL replica_parallel_type = 'LOGICAL_CLOCK';
SET GLOBAL replica_preserve_commit_order = ON;
```

`replica_preserve_commit_order = ON`은 병렬 실행하되 커밋은 소스 순서대로 강제해 GTID 일관성과 체인 복제를 지킨다.

| 파라미터 | 효과 | 트레이드오프 |
|---|---|---|
| `replica_parallel_workers` | 병렬 적용 스레드 수 | 큼 때 스케줄링 오버헤드 |
| `binlog_group_commit_sync_delay` | 그룹 커밋 창 확대 → 병렬도↑ | 소스 커밋 지연 증가 |
| `replica_preserve_commit_order` | 커밋 순서 보존 | 병렬 이득 일부 감소 |

## 8. 조합 전략과 판단

고가용성 구성은 GTID + 손실 없는 반동기(AFTER_SYNC) + LOGICAL_CLOCK 병렬 적용을 함께 켜는 삼단 구성이다. 반동기는 커밋 지연(ACK RTT)을 추가하므로 쓰기 레이턴시에 민감하면 지연 폭을 측정해야 한다. 완전 동기와 다수결 페일오버가 필요하면 Group Replication(InnoDB Cluster)을 검토한다. "지연을 조금 감수하고 내구성을 살 것인가"가 반동기 채택의 핵심 판단이며 대부분 OLTP에서는 그 답이 예다.

## 참고

- MySQL 8.0 — Replication with GTIDs: https://dev.mysql.com/doc/refman/8.0/en/replication-gtids.html
- MySQL 8.0 — Semisynchronous Replication: https://dev.mysql.com/doc/refman/8.0/en/replication-semisync.html
- MySQL 8.0 — Replication Threads: https://dev.mysql.com/doc/refman/8.0/en/replication-threads.html
- MySQL 8.0 — Binary Logging Formats: https://dev.mysql.com/doc/refman/8.0/en/binary-log-formats.html
