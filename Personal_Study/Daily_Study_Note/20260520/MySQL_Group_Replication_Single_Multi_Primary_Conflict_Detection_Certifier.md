Notion 원본: https://www.notion.so/3665a06fd6d38134900df7eb4ebdf05d

# MySQL Group Replication — Single/Multi-Primary 모드와 Certifier 충돌 감지

> 2026-05-20 신규 주제 · 확장 대상: MySQL

## 학습 목표

- Group Replication이 Paxos 변형(XCom)으로 트랜잭션을 ordering하는 흐름을 추적한다
- Single-Primary와 Multi-Primary 모드에서 certification·conflict detection이 어떻게 다르게 동작하는지 식별한다
- write-set 기반 충돌 감지 알고리즘이 `gtid_executed`·certification info와 어떻게 결합하는지 분석한다
- 분할 회피, 노드 추방 임계치, flow control 튜닝으로 운영 안정성을 끌어올린다

## 1. Group Replication 위치 — Semi-sync·Galera와의 차이

InnoDB Cluster의 코어인 MySQL Group Replication(GR)은 *그룹 통신 시스템(GCS) 위의 합의 기반 멀티-마스터 리플리케이션 플러그인*이다. Semi-sync replication이 마스터가 슬레이브의 ACK 한 건을 기다리는 구조라면, GR은 *과반수의 동의를 받은 트랜잭션만 commit*된다는 점에서 모델이 다르다. 이론적 분류상 GR은 *상태 머신 복제(state machine replication)*에 가깝고, Galera Cluster의 wsrep 패치와 종종 비교된다.

## 2. XCom 프로토콜과 트랜잭션 순서 결정

```
1) 트랜잭션 종료 직전, 로컬 InnoDB가 write-set 수집
2) GR plugin이 trx payload + write-set + GTID 후보를 GCS로 broadcast
3) XCom이 합의 라운드를 실행, 그룹 멤버 과반수가 ack
4) 합의된 ordering으로 모든 노드가 동일한 certification 단계 수행
5) 통과한 트랜잭션만 binlog에 기록되고 commit
```

합의 단계와 certification 단계가 분리돼 있다는 점이 중요하다.

## 3. Certifier — write-set 기반 충돌 감지

```
certification_info: Map<rowHash, lastModifiedGTIDSet>

certify(trx):
    snapshot = trx.snapshot_gtid_executed
    for key in trx.writeset:
        last = certification_info.get(key)
        if last and !snapshot.contains(last):
            return ABORT
    for key in trx.writeset:
        certification_info[key] = trx.gtid
    return COMMIT
```

핵심 직관은 "내가 시작 시점에 본 *gtid_executed* 스냅샷에는 없던 트랜잭션이 같은 키를 *먼저 합의 순서로 들어왔다면*, 내 트랜잭션은 충돌이다". certification_info는 무한히 자라면 메모리를 갉아먹으므로 주기적으로 *garbage collect*된다.

## 4. Single-Primary 모드 — 자동 failover의 실제

Single-Primary는 단일 노드만 쓰기를 받고 나머지는 read-only다. 운영의 99%가 이 모드다. Primary 후보 순위는 `group_replication_member_weight`로 조정한다.

```
1) GCS가 5초 동안 ack을 못 받음 → suspicion 진입
2) 추방 카운트다운 시작
3) 그룹에서 expel
4) 과반수 유지 시 새 primary 선출
5) 클라이언트는 router를 통해 재연결
```

과반수가 깨지면 그룹 전체가 read-only로 빠진다. AZ 분포가 2-2-1 같은 형태면 한 AZ 장애만으로도 쿼럼이 깨질 수 있어, *3 AZ에 홀수개 분산*이 권장된다.

## 5. Multi-Primary 모드 — 가능하지만 함정이 많다

- **Cascading constraint**: foreign key의 `ON CASCADE`는 multi-primary에서 *unsupported*
- **Serializable isolation 비호환**: REPEATABLE READ까지만 안전
- **DDL 비원자성**: 두 노드에서 동시 ALTER가 들어가면 한쪽이 silent하게 실패
- **충돌 시**: certification에서 ABORT된 트랜잭션은 `ER_TRANSACTION_ROLLBACK_DURING_COMMIT (3101)`

```sql
SET GLOBAL group_replication_single_primary_mode = OFF;
SET GLOBAL group_replication_enforce_update_everywhere_checks = ON;
```

## 6. Flow Control

```ini
group_replication_flow_control_mode = QUOTA
group_replication_flow_control_certifier_threshold = 25000
group_replication_flow_control_applier_threshold = 25000
group_replication_flow_control_period = 1
```

DISABLED로 끄면 빠른 노드는 자유롭게 commit하지만 느린 노드가 OOM으로 죽는다. QUOTA 모드에서 그룹은 *가장 느린 노드의 적용 속도*를 기준으로 새 트랜잭션 인입 속도를 결정한다.

## 7. 운영 지표·튜닝 트레이드오프

| 파라미터 | 기본 | 권장 | 영향 |
|---|---|---|---|
| member_expel_timeout | 5초 | 5~30초 | 짧으면 GC pause에서 추방 |
| unreachable_majority_timeout | 0(무한) | 30~60초 | 과반수 잃은 노드 차단 |
| transaction_size_limit | 150MB | 그대로 또는 줄임 | 큰 트랜잭션이 broadcast 막음 |
| message_cache_size | 1GB | 4~8GB | 빠른 따라잡기 |
| compression_threshold | 1MB | 256KB | 네트워크 절약 |
| consistency | EVENTUAL | BEFORE_ON_PRIMARY_FAILOVER | failover stale read 회피 |

BEFORE_ON_PRIMARY_FAILOVER는 새 primary가 모든 backlog를 적용 완료할 때까지 새 트랜잭션을 받지 않게 해 failover 직후의 phantom read를 막는다. AFTER는 commit 이전에 그룹 전체가 적용을 마칠 때까지 기다린다.

## 8. View Change·GTID·InnoDB Cluster

```sql
SELECT * FROM performance_schema.replication_group_members;
SELECT * FROM performance_schema.replication_group_member_stats;
```

`COUNT_CONFLICTS_DETECTED`가 multi-primary에서 0이 아니라면 application의 hot-row 패턴을 봐야 한다. 운영 표준은 *InnoDB Cluster*(GR + MySQL Shell + MySQL Router) 묶음이다.

## 9. 실패 시나리오와 권장 대응

Split-brain 시도는 GR의 쿼럼 기반 설계로 원천 차단되지만, *과반수 동시 손실*은 위험하다. 5노드 중 3노드를 잃으면 남은 2노드는 *수동 강제 멤버십 재설정*(`group_replication_force_members`)을 명시적으로 입력해야 복구된다.

```sql
SET GLOBAL group_replication_force_members = '10.0.1.10:33061';
STOP GROUP_REPLICATION;
START GROUP_REPLICATION;
```

## 참고

- MySQL 8.0 Reference Manual — Chapter 18 Group Replication
- "Group Replication: A Journey to the Group Communication Core" (Bertolaccini et al., 2019)
- MySQL Router & InnoDB Cluster Administration Guide
- Percona Blog — "MySQL Group Replication: Conflict Detection in Multi-Primary Mode"
- MySQL Server Source — `plugin/group_replication/src/certifier.cc`
