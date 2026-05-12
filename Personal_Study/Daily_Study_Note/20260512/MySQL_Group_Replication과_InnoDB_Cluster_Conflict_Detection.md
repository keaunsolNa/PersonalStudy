Notion 원본: https://www.notion.so/35e5a06fd6d381d59906e69b82bb78ce

# MySQL Group Replication과 InnoDB Cluster Conflict Detection

> 2026-05-12 신규 주제 · 확장 대상: Oracle / SQLD

## 학습 목표

- Group Replication 의 certification(인증) 단계가 conflict 를 어떻게 결정론적으로 감지하는지 이해
- Single Primary 와 Multi Primary 모드의 차이와 각 모드의 conflict-resolution 비용
- InnoDB Cluster + MySQL Router 의 failover 시퀀스와 client 측 영향
- Group 이 갈라졌을 때 split-brain 을 막는 majority(과반) 룰의 한계

## 1. Replication 의 진화 — Async, Semi-sync, Group

MySQL 의 복제는 세 세대를 거쳤다.

| 방식 | 커밋 시점 | 데이터 손실 가능성 | 복잡도 |
|---|---|---|---|
| Async | primary 커밋 직후 클라이언트 반환 | primary crash 시 unsent binlog 손실 | 낮음 |
| Semi-sync | 최소 1 replica 가 binlog 수신 ACK 후 반환 | rare, ACK 전 crash 시만 | 중간 |
| Group Replication | majority 가 transaction 을 certify 한 뒤 커밋 | 거의 없음 | 높음 |

Group Replication(이하 GR)은 **Paxos 변형(Mencius)** 을 사용해 트랜잭션의 글로벌 순서를 합의한다. 각 노드는 트랜잭션을 로컬에서 실행한 뒤 commit 직전에 *write set* 과 *gtid set* 을 그룹에 broadcast 한다. 모든 노드가 동일한 순서로 트랜잭션을 받아 *certification* 을 수행하고, 같은 결정을 내린다.

## 2. Write Set 과 Certification

write set 은 트랜잭션이 수정한 모든 row 의 **primary key 또는 unique key 해시** 집합이다. certification 은 다음 조건을 본다.

> 트랜잭션 T 가 시작될 때(자신의 gtid_executed snapshot) 보다 *뒤에* 그룹 순서로 들어온 다른 트랜잭션 T' 가 T 의 write set 과 교집합을 가진다면, T 는 abort.

요점:

1. 각 트랜잭션은 **자기 시작 gtid snapshot 이후 들어온** 트랜잭션들과만 비교된다. 오래된 트랜잭션은 비교 대상이 아니다.
2. 두 트랜잭션이 같은 row 를 수정했으면 글로벌 순서상 *나중* 트랜잭션이 abort 된다.
3. PK 가 없는 테이블은 conflict 감지가 불가능하므로 GR 가 거부한다. `binlog_format=ROW`, `enforce_gtid_consistency=ON` 필수.

certification 은 결정론적이라 모든 노드가 같은 결론에 도달한다. 별도의 master 없이도 일관성이 유지된다.

## 3. Single Primary vs Multi Primary

### 3.1 Single Primary (기본값, 권장)

쓰기는 항상 1 노드(primary)에서만. 다른 노드는 read-only(super_read_only=ON). 새 트랜잭션이 primary 에서만 발생하므로 conflict 가 *원리상* 없다. certification 은 여전히 수행되지만 거의 모든 트랜잭션이 통과한다.

primary 가 실패하면 GR 은 election 으로 새 primary 를 선출한다. 기본 election 알고리즘은 **weight 가 큰 순 → server_uuid 사전순**.

### 3.2 Multi Primary

모든 노드가 쓰기 가능. 동시에 같은 row 를 건드린 트랜잭션이 글로벌 순서로 들어오면 *뒤 트랜잭션이 abort* 된다. 클라이언트는 `ER_TRANSACTION_ROLLBACK_DURING_COMMIT (1180)` 또는 GR 전용 에러를 받는다.

제한:

- SERIALIZABLE isolation 금지(GR 가 거부)
- Foreign key cascade 금지 — write set 추적이 부정확해진다
- 동일 row 를 빈번히 갱신하는 워크로드(카운터, hot row)는 abort 폭주

multi-primary 는 *지리적으로 분산된 cluster 에서 각 region 이 자기 region 의 sharded 데이터만 쓰는 경우* 가 거의 유일한 정당화다. 일반 서비스는 single primary 가 정답이다.

## 4. InnoDB Cluster 와 MySQL Router

InnoDB Cluster = **GR + MySQL Shell admin + MySQL Router**. 각각의 역할:

| 컴포넌트 | 역할 |
|---|---|
| GR | 데이터 복제와 합의 |
| MySQL Shell `dba.*` API | cluster 생성/추가/제거/복구 자동화 |
| MySQL Router | client 와 cluster 사이의 라우터. metadata 를 읽어 primary 로만 write 트래픽 전송 |

Router 는 두 가지 listen port 를 노출한다.

```
6446: read-write  (primary 로 라우팅)
6447: read-only   (secondary round-robin)
```

애플리케이션은 `jdbc:mysql://router-host:6446/orders` 로 단순 연결. router 가 primary 식별을 처리한다.

failover 시퀀스:

1. primary 노드 crash
2. GR 가 5 초 내(기본) 멤버 손실 감지, election 수행
3. 새 primary 결정. Router 는 metadata 캐시를 새로고침
4. 기존 connection 은 그대로 끊김 → 애플리케이션이 reconnect 해야 함
5. reconnect 후 새 primary 로 자동 라우팅

이 동안 **5~15초 의 쓰기 단절** 이 발생한다. R2DBC/HikariCP 의 retry 정책으로 흡수해야 한다.

## 5. 멤버 멤버쉭과 quorum

GR 그룹은 짝수 개 노드도 동작은 하지만, **과반(majority)** 노드가 살아있어야 진행한다. 7개 노드 → 4개 살아있어야 함. 5개 → 3개. 짝수는 split-brain 시 *양쪽 모두 멈춰서* 비효율이다. 홀수가 정석.

5 노드 cluster 가 2:3 으로 분할되면 다음과 같다.

- 3 노드 측: majority 보유 → 계속 쓰기 가능
- 2 노드 측: minority → super_read_only 자동 적용, 사실상 멈춤
- 네트워크 복구 시 minority 측은 자동으로 다시 합류 → distributed recovery

distributed recovery 는 binary log 부터 따라잡는다. lag 이 크면 `clone plugin`(MySQL 8.0+)으로 *전체 데이터 디렉터리 복제* 가 자동 발동.

```sql
SHOW VARIABLES LIKE 'group_replication_clone_threshold';
-- 기본 9_223_372_036_854_775_807 (사실상 무한). 작게 줄이면 clone 빈도 증가.
```

## 6. Performance Schema 로 모니터링

핵심 테이블:

```sql
-- 멤버 상태
SELECT * FROM performance_schema.replication_group_members;

-- 트랜잭션 처리 상태
SELECT * FROM performance_schema.replication_group_member_stats;

-- 멤버별 큐 길이
SELECT MEMBER_ID, COUNT_TRANSACTIONS_IN_QUEUE, COUNT_TRANSACTIONS_CHECKED,
       COUNT_CONFLICTS_DETECTED, COUNT_TRANSACTIONS_ROWS_VALIDATING
  FROM performance_schema.replication_group_member_stats;
```

`COUNT_TRANSACTIONS_IN_QUEUE` 가 지속적으로 0 이상이면 secondary 가 따라가지 못한다는 신호다. `COUNT_CONFLICTS_DETECTED` 는 multi-primary 에서 핫스팟을 보여준다.

`group_replication_flow_control_mode=QUOTA` 가 기본. 큐가 임계값을 넘으면 primary 의 throughput 을 강제 제한해 secondary 가 따라잡을 시간을 준다. 트래픽 폭증 시에는 *primary 가 갑자기 느려 보이는 현상* 으로 나타난다.

## 7. 운영에서 가장 자주 보는 사고

### 7.1 PK 없는 테이블

```
ERROR 3098 (HY000): The table does not comply with the requirements by an external plugin.
```

PK 없는 테이블에 INSERT 시도 → GR 가 거부. 마이그레이션 단계에서 PK 일괄 점검 필요.

### 7.2 거대 트랜잭션

기본 `group_replication_transaction_size_limit=143MB`. binlog event 가 이걸 넘으면 GR 가 거부한다. ETL 일괄 INSERT, 거대 UPDATE 가 흔한 원인. 청크 단위로 쪠겜거나 limit 을 올린다(메모리 압박과 trade-off).

### 7.3 멤버가 ERROR 상태로 빠짐

```
SELECT MEMBER_HOST, MEMBER_STATE FROM performance_schema.replication_group_members;
-- ERROR
```

원인 대부분 disk full 또는 GTID 불일치. `super_read_only=ON` 으로 둔 채 binlog purge 와 clone 복구 절차 수행.

### 7.4 Router metadata stale

MySQL Router 가 cluster 변경을 늦게 인지하는 경우 `--connect-timeout` 만료까지 옷 primary 로 보낸다. Router 8.0.31+ 의 `ttl=0.5` 로 metadata 갱신 주기를 단축.

## 8. Conflict 회피 패턴

multi-primary 에서 사용해야만 할 때:

1. **Sharding by primary** — region/tenant 별로 쓰는 row 영역을 분리. 같은 row 에 두 primary 가 동시에 쓰지 않게 애플리케이션이 보장.
2. **Optimistic concurrency token** — version 컴럼을 두고 `UPDATE ... WHERE id=? AND version=?` 패턴. abort 가 발생하면 클라이언트가 재시도. ER_TRANSACTION_ROLLBACK 을 명시적 처리.
3. **Append-only 패턴** — 카운터 대신 event row 누적, 집계는 비동기. conflict 가 원리상 없다.
4. **Sequence/UUID** — auto_increment 사용 시 `auto_increment_increment`, `auto_increment_offset` 을 노드별로 다르게 설정해 PK 충돌 자체를 차단.

## 참고

- "Group Replication" — https://dev.mysql.com/doc/refman/8.4/en/group-replication.html
- "InnoDB Cluster" — https://dev.mysql.com/doc/mysql-shell/8.4/en/mysql-innodb-cluster.html
- Vitor Enes 외, "On the Diversity of Cluster Workloads and its Impact on Replication Performance" — VLDB
- "MySQL Router 8.4 Reference" — https://dev.mysql.com/doc/mysql-router/8.4/en/
- "Understanding Group Replication Flow Control" — https://mysqlhighavailability.com/the-king-is-dead-long-live-the-king-our-homegrown-paxos-based-consensus/
