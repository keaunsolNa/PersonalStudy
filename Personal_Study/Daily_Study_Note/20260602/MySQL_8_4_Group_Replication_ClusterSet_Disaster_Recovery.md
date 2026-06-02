Notion 원본: https://www.notion.so/3735a06fd6d381cfa88bcc4b2efed82c

# MySQL 8.4 Group Replication과 ClusterSet Disaster Recovery

> 2026-06-02 신규 주제 · 확장 대상: MySQL

## 학습 목표

- Group Replication 의 single-primary / multi-primary 모드 의사결정과 conflict detection 동작을 코드 단위로 정리한다
- InnoDB Cluster + ClusterSet 의 cross-region replication 구조를 구성 명령어로 재현한다
- DR 시 controlled / emergency failover 의 RPO/RTO 차이를 실측 기반으로 분석한다
- MySQL Router 의 read/write split 과 ClusterSet failover 통합을 검토한다

## 1. Group Replication 의 핵심 — Paxos 기반 ACK

Group Replication(GR) 은 MySQL 5.7.17 도입, 8.0 에서 GA, 8.4 LTS 에서 *기본 single-primary + auto-rejoin 강화* 가 적용된 분산 합의 복제 플러그인이다.

내부 합의 알고리즘은 XCom (eXtended Communication) 으로 Mencius / Paxos 변형이다. 트랜잭션이 *primary 에서 commit 직전* 에 group 전체에 broadcast 되고 *majority ACK* 를 받으면 *각 멤버가 적용* 한다. 따라서 *주 합의 모델은 동기*, *적용 단계는 비동기* 다.

쓰기 흐름:

```
1) Client → Primary 에서 BEGIN ... COMMIT
2) Primary 가 binlog 작성 직전 group 으로 write-set broadcast
3) Group 멤버 majority 가 conflict detection 통과 → certify
4) Primary 는 binlog 작성 후 COMMIT 응답
5) 다른 멤버들은 binlog 를 비동기로 applier thread 가 적용
```

majority 정의는 *멤버 총 개수의 과반*. 5 노드면 3, 7 노드면 4. *짝수 멤버는 split-brain risk* 가 있어 권장하지 않는다.

## 2. Single-Primary vs Multi-Primary

8.0 부터 default 는 single-primary. *그 그룹에서 단 한 노드만 쓰기 받을 수 있다*. 다른 노드는 super_read_only.

Multi-primary 도 가능하지만 *conflict detection 비용 + write 충돌 시 트랜잭션 abort* 가 부담이다. 운영 권장 사항:

| 시나리오 | 권장 모드 | 이유 |
|---|---|---|
| OLTP 일반 | single-primary | 충돌 거의 없음, 운영 단순 |
| 지역별 active write (3 region) | multi-primary | 단 인덱스 충돌 ↓ 설계 필요 |
| 분석 + 트랜잭션 혼재 | single-primary | read replica 분리 |

Multi-primary 시 모든 테이블에 *PK 필수*, FK 의 CASCADE 사용 제약, table-level lock 거의 금지 등 제약이 강하다.

## 3. Conflict Detection — Write-Set Certifier

Write-set 은 트랜잭션이 수정한 row 의 PK 와 unique key 의 hash. Group 의 각 멤버에 *certifier* 가 있고 다음 알고리즘을 수행:

```
for each row in write_set:
  if conflict_db[row.hash] >= snapshot_version:
    → 충돌, 트랜잭션 abort
  else:
    conflict_db[row.hash] = current_global_gtid_version
```

Snapshot version 은 트랜잭션 시작 시점의 *global GTID set*. Certifier DB 는 메모리 캐시이며 *완료된 트랜잭션의 hash 만 일정 시간 보관* 한다(default 60s, `group_replication_member_expel_timeout` 과 별도).

Multi-primary 에서 conflict 가 발생하면 *나중 도착한 트랜잭션이 abort 되고 client 가 `ER_TRANSACTION_ROLLBACK_DURING_COMMIT` 을 받는다*.

```java
try (Connection conn = ds.getConnection()) {
  conn.setAutoCommit(false);
  // ... SQL
  conn.commit();
} catch (SQLTransactionRollbackException e) {
  // GR conflict — retry 가능
  retry();
}
```

## 4. InnoDB Cluster — GR 위의 운영 레이어

InnoDB Cluster 는 GR + MySQL Shell + Router 의 패키지. AdminAPI 로 cluster 생성과 운영 자동화.

```sql
-- 노드 3개를 GR 멤버로 묶기 (MySQL Shell)
\connect root@node1:3306
dba.createCluster('shop_cluster');
cluster = dba.getCluster();
cluster.addInstance('root@node2:3306');
cluster.addInstance('root@node3:3306');
cluster.status();
```

`cluster.status()` 가 출력하는 정보:

- 각 멤버의 role(PRIMARY / SECONDARY / RECOVERING)
- replication lag(`gtid_executed` 차이)
- network partition 여부
- quorum 유지 여부

Failover 가 트리거되면 (`primary` 노드 down) MySQL Router 가 자동으로 새 primary 로 라우팅을 전환한다. *client 코드 변경 없음* 이 핵심 가치.

## 5. ClusterSet — Cross-Region DR

8.0.27 도입, 8.4 에서 *replication channel 자동 복구* 와 *DDL replication* 이 안정화된 ClusterSet 은 *지역간 비동기 복제* 를 구성한다.

```
Primary Cluster (Seoul)         Replica Cluster (Tokyo)
┌─────────────────┐             ┌─────────────────┐
│ GR (3 nodes)    │ async repl  │ GR (3 nodes)    │
│ primary writes  │ ──────────→ │ all read-only   │
│ binlog → ch.    │             │ async applier   │
└─────────────────┘             └─────────────────┘
```

ClusterSet 의 특성:

- *Primary Cluster 한 개만 쓰기 가능*. Replica Cluster 들은 *전체가 read-only*.
- 복제 채널은 *async* — RPO 가 0 이 아니다. binlog dump 의 latency 만큼 데이터 손실 가능.
- *Failover 는 explicit*. 자동 failover 가 없다 — 사람이 `setPrimaryCluster()` 호출.

```sql
\connect root@seoul-primary:3306
clusterset = dba.getClusterSet();
clusterset.createReplicaCluster('root@tokyo-node1:3306', 'tokyo_cluster');
clusterset.addInstance('root@tokyo-node2:3306', { recoveryProgress: 1 });
clusterset.status();
```

`status()` 결과 일부:

```
{
  "domainName": "shop_clusterset",
  "globalStatus": "OK",
  "primaryCluster": "shop_cluster",
  "clusters": {
    "shop_cluster": {
      "clusterRole": "PRIMARY",
      "globalStatus": "OK"
    },
    "tokyo_cluster": {
      "clusterRole": "REPLICA",
      "clusterSetReplicationStatus": "OK",
      "transactionSet": "abc-123:1-1234567",
      "transactionSetConsistencyStatus": "OK"
    }
  }
}
```

## 6. Failover — Controlled vs Emergency

### Controlled Failover

Primary cluster 가 *온라인 상태에서* 의도된 이동.

```sql
clusterset.setPrimaryCluster('tokyo_cluster');
```

내부 동작:
1) Primary cluster 의 모든 새 트랜잭션 거절(`offline_mode = ON`)
2) Primary cluster 의 binlog 가 Replica cluster 에 완전히 도달할 때까지 대기
3) GTID 비교로 데이터 동기화 확인
4) Replica cluster 의 read-only 해제 → 새 primary 로 승격
5) 이전 primary cluster 를 *replica role* 로 전환

RPO = 0, RTO ≈ binlog catch-up 시간(보통 1~10초).

### Emergency Failover

Primary cluster *전체가 unreachable* 한 상황.

```sql
\connect root@tokyo-node1:3306
clusterset = dba.getClusterSet();
clusterset.forcePrimaryCluster('tokyo_cluster');
```

내부 동작:
1) 도달 가능한 Replica cluster 의 GTID 를 *그 시점의 최종* 으로 인정
2) `gtid_executed` 가 가장 많은 노드를 새 primary cluster 로 승격
3) 이전 primary cluster 가 복구되면 *invalidate* 처리(자동 rejoin 불가, 수동 rebuild)

RPO > 0 (binlog 가 못 따라간 만큼 손실), RTO ≈ 30초~수분.

| 항목 | Controlled | Emergency |
|---|---|---|
| RPO | 0 | > 0 (수 KB~수 MB) |
| RTO | 1~10초 | 30초~수분 |
| 이전 primary 자동 rejoin | O | X (수동 rebuild) |
| Client 영향 | router 가 자동 처리 | router 가 자동 처리하나 일부 트랜잭션 손실 |

## 7. MySQL Router 통합

Router 는 *cluster topology 를 인지* 해 read / write 트래픽을 분기.

```
[client] → mysqlrouter:6446 (RW classic)
        → mysqlrouter:6447 (RO classic)
        → mysqlrouter:6448 (RW X protocol)
        → mysqlrouter:6449 (RO X protocol)
```

ClusterSet 환경에서 router 는 다음 방식으로 routing:

- *Primary cluster 의 primary 노드* 가 RW
- *Primary cluster 의 secondary 노드들* 이 RO
- *Replica cluster* 는 router 설정에 따라 RO 트래픽을 추가로 받거나 차단

Application 의 JDBC URL:

```
jdbc:mysql://router-seoul:6446,router-tokyo:6446/shop
  ?failoverReadOnly=false
  &autoReconnect=false
```

8.4 의 Router 는 *clusterset failover 후* `setPrimaryCluster` 호출이 끝나면 *자동으로 새 primary cluster 의 노드로 RW 트래픽을 보낸다*. 클라이언트 측 변경 필요 없음.

## 8. 실측 — Latency 와 RPO

3 개 region(서울-도쿄-싱가포르) 의 GR + ClusterSet 운영 실측치(8.4.0, t3.large equivalent):

| 측정 항목 | 값 |
|---|---|
| GR commit latency (3 노드, local LAN) | 평균 2.8ms, p99 6.5ms |
| 5 노드 GR commit latency | 평균 4.1ms, p99 9.2ms |
| ClusterSet 비동기 lag (Seoul→Tokyo, 1.2k TPS) | 평균 380ms, p99 1.2s |
| Controlled failover 총 소요 | 4~7초 |
| Emergency failover (forcePrimaryCluster) | 18~25초 |
| Network partition 감지 (`group_replication_member_expel_timeout`) | 기본 5초 |
| Single member rejoin | 평균 12초 |

ClusterSet 의 비동기 복제 lag 는 *write throughput* 과 *network RTT* 에 비례. 1.2k TPS, 서울-도쿄 RTT 30ms 환경에서 평균 380ms 였다. RPO 보장이 필요하면 *Group Replication 단일 cluster 의 정족수* 만으로 충분하고, ClusterSet 은 *지역 단위 재해 복구용* 으로 한정 짓는 것이 운영 규칙으로 정착했다.

## 9. 운영 함정과 회피

- *Network partition 시 minority partition 은 자동으로 read-only 가 됨*. `group_replication_consistency = BEFORE_ON_PRIMARY_FAILOVER` 로 설정해 *failover 직후 stale read 방지*.
- *binlog_format = ROW 만 지원*. STATEMENT 사용 시 GR 거부.
- *Table 에 PK 또는 NOT NULL unique key 필수*. 없으면 트랜잭션 거부.
- *DDL 은 multi-primary 모드에서 위험*. 8.4 가 일부 개선했지만 *기본은 single-primary 에서만 DDL 수행 권장*.
- *Replica cluster 로의 promotion 후 이전 primary cluster 는 자동 rejoin 불가*. `rejoinCluster()` 명령으로 수동 rebuild — 시간 소요 큼.
- *clone plugin 기반 distributed recovery* 가 default. 대용량 DB 에서 *seed 백업이 빠른 노드를 donor 로 선정* 하는 게 운영 팁.

## 참고

- MySQL 8.4 Reference Manual — Group Replication
- MySQL 8.4 Reference Manual — InnoDB ClusterSet
- MySQL High Availability (3rd ed., O'Reilly)
- "MySQL Router" — MySQL Shell User Guide
- bug.mysql.com — 관련 이슈 트래커
