Notion 원본: https://www.notion.so/3555a06fd6d381b5ba38dad629c873b2

# Cassandra Lightweight Transaction(LWT) — Paxos 합의와 Read Repair 메커니즘

> 2026-05-03 신규 주제 · 확장 대상: Raft 합의 알고리즘, Vector Clock과 Hybrid Logical Clock

## 학습 목표

- Cassandra 가 기본적으로 제공하는 *eventual consistency* 모델 위에 LWT 가 어떻게 *linearizable consistency* 를 얹는지 4-phase Paxos 흐름으로 추적한다
- LWT 의 비용이 일반 INSERT/UPDATE 대비 왜 4배 이상 비싼지를 round-trip 수와 디스크 I/O 관점에서 설명한다
- Read Repair 가 일반 read 와 LWT read 에서 어떻게 다르게 동작하는지 비교한다
- LWT 를 써야 할 케이스와 쓰지 말아야 할 케이스를 데이터 모델 관점에서 결정한다

## 1. Cassandra 의 일관성 기본 모델

Cassandra 는 분산 해시 테이블 위에 컬럼 패밀리를 얹은 AP 시스템 (CAP 분류) 이다. 기본 쓰기는 다음 흐름으로 동작한다.

1. 클라이언트가 임의 노드(Coordinator)에 쓰기 요청
2. Coordinator 가 파티션 키를 해시해 RF (replication factor) 만큼의 replica 를 결정
3. 쓰기를 모든 replica 에 병렬 전송
4. consistency level (CL) 만큼의 ACK 를 받으면 클라이언트에 성공 응답

`CL=QUORUM` (RF=3 이면 2) 으로 읽기/쓰기 모두 보내면 strong consistency (read-your-write) 가 보장되지만 *동일 row 에 동시 쓰기가 들어왔을 때의 순서* 는 보장되지 않는다. Cassandra 는 마지막 쓰기 승리 (LWW, Last Write Wins) 를 timestamp 기준으로 결정한다.

이 모델로는 다음 케이스를 안전하게 처리할 수 없다.

```sql
-- 두 클라이언트가 동시에 같은 username 으로 가입 시도
INSERT INTO users (username, email) VALUES ('alice', 'a@x.com');
INSERT INTO users (username, email) VALUES ('alice', 'a@y.com');
```

LWW 는 timestamp 가 늦은 쓰기를 살리지만 클라이언트는 두 INSERT 모두 "성공" 응답을 받는다. 가입 중복 같은 비즈니스 invariant 를 지키려면 *조건부 쓰기* 가 필요하다. 그 도구가 LWT 다.

## 2. LWT 의 CQL 문법

`IF NOT EXISTS` 또는 `IF <조건>` 절을 붙이면 해당 INSERT/UPDATE/DELETE 가 LWT 로 처리된다.

```sql
INSERT INTO users (username, email)
VALUES ('alice', 'a@x.com')
IF NOT EXISTS;

UPDATE accounts
SET balance = 90
WHERE id = 'a1'
IF balance = 100;
```

응답에는 `[applied]` 컬럼이 포함되어 적용 여부를 알린다. 적용되지 않은 경우 현재 row 의 실제 값이 같이 반환된다 — 즉 compare-and-set (CAS) 의미다.

## 3. Cassandra Paxos 의 4-phase 흐름

Cassandra LWT 는 표준 Paxos (Lamport, 1998) 를 적용해 합의를 얻는다. 단순화한 4단계:

1. **Prepare**: Coordinator 가 새로운 ballot number 를 생성해 모든 replica 에 promise 요청
   - replica 는 "이보다 작은 ballot 의 제안은 더 이상 받지 않겠다" 약속
   - 이미 더 큰 ballot 을 본 적 있으면 거절
2. **Read**: 과반(QUORUM) 이상 promise 를 받으면 Coordinator 가 현재 row 상태를 읽어 CAS 조건 평가
   - 이 단계에서 read repair 가 부분적으로 일어남
3. **Propose**: 조건이 충족되면 변경값을 ballot 과 함께 propose
   - replica 는 약속한 ballot 의 propose 만 수락
4. **Commit**: 과반 수락 시 commit 메시지 발행 → memtable 반영, hint log 기록
   - 거절되면 ballot number 갱신 후 1번부터 retry

각 단계가 모두 *전체 RF 노드와의 round-trip* 을 요구한다. 일반 INSERT 가 1 round-trip 이라면 LWT 는 4 round-trip 이다. 게다가 Paxos 상태(`paxos` 시스템 테이블)는 디스크에 쓰여 fsync 비용이 추가된다.

## 4. SERIAL vs LOCAL_SERIAL consistency level

LWT 는 별도의 일관성 레벨을 가진다.

| CL | 의미 | 사용처 |
|---|---|---|
| `SERIAL` | 모든 datacenter 의 과반 동의 | 글로벌 단일 진실 필요 |
| `LOCAL_SERIAL` | 같은 datacenter 의 과반 동의 | DC 간 latency 회피, region-local |

`LOCAL_SERIAL` 은 동일 DC 내 동시성만 직렬화하므로 다른 DC 와 동시 쓰기가 들어오면 race 가 가능하다. 즉 글로벌 invariant (예: unique username) 를 보장하지 않는다. invariant 의 범위(데이터센터 단위 vs 글로벌 단위)를 먼저 정해서 CL 을 고른다.

쓰기 commit 후 이를 읽는 read 도 SERIAL/LOCAL_SERIAL 로 해야 LWT 가 본 값을 같은 ballot 으로 본다. 그렇지 않으면 commit 단계 사이에 빠진 빈 구간을 읽을 수 있다.

## 5. paxos 시스템 테이블의 역할

각 노드의 `system.paxos` 테이블이 진행 중인/최근 완료된 ballot 을 영속화한다. 컬럼 구조:

```
CREATE TABLE system.paxos (
    row_key blob,
    cf_id uuid,
    in_progress_ballot timeuuid,
    most_recent_commit blob,
    most_recent_commit_at timeuuid,
    most_recent_commit_version int,
    proposal blob,
    proposal_ballot timeuuid,
    proposal_version int,
    PRIMARY KEY ((row_key), cf_id)
);
```

Paxos 가 중간에 끊겨도 다음 Coordinator 가 `system.paxos` 를 읽어 in-flight 합의를 이어받는다. 이 때문에 LWT 는 *node failure 직후에도* 진행 중인 ballot 을 잃지 않는다 — 단, 이 영속화 비용이 정확히 비싸다는 의미이기도 하다.

`paxos` 테이블은 정해진 TTL (기본 `paxos_purge_seconds=24h`) 후 정리된다. 너무 짧으면 retry 도중 데이터를 잃을 위험이, 너무 길면 디스크 사용량이 늘어난다.

## 6. Read Repair 동작 차이

일반 read (`CL=QUORUM`) 의 read repair:

- Coordinator 가 RF 노드에서 데이터/digest 를 받음
- digest 가 어긋나면 stale replica 에 비동기 sync 발행 (background)
- `read_repair_chance=0.0` 에서는 명시적 미스매치만 수정

LWT read (`CL=SERIAL`) 는 이보다 강하게 동작한다.

- Prepare 단계에서 노드별 `most_recent_commit` 을 비교
- 가장 큰 ballot 의 값을 다른 노드들이 *동기적으로* 받아 정합화
- 이 정합화가 끝나기 전까지 read 응답을 보내지 않음

따라서 LWT 는 read 단계에서도 약한 정합성 빈틈을 메운다. 대가는 SERIAL read 가 일반 QUORUM read 보다 약 2배 비싸다는 점이다.

## 7. 비용 모델

다음은 단일 row 에 대한 작업별 round-trip 과 디스크 I/O 를 비교한 표다 (RF=3, CL 기준).

| 작업 | round-trips | replica 디스크 write | 비고 |
|---|---|---|---|
| INSERT (CL=ONE) | 1 | 1 | hint 가능 |
| INSERT (CL=QUORUM) | 1 | 2 ack | 가장 일반 |
| INSERT IF NOT EXISTS | 4 | 4 (paxos+memtable) | LWT |
| UPDATE IF condition | 4 | 4 | LWT |
| SELECT (CL=QUORUM) | 1 | 0 | digest read |
| SELECT (CL=SERIAL) | 2~4 | 0~ | 진행중 ballot 정합화 시 추가 |

LWT 가 4배 이상 비싸다는 직관은 정확하다. 게다가 contention 이 있으면 ballot 충돌로 retry 가 발생해 latency 의 long-tail 이 매우 길어진다 — p99 가 수 초로 튀는 것이 LWT 의 전형적 모습이다.

## 8. 언제 쓰고 언제 쓰지 말 것인가

쓸만한 케이스:

- 신규 가입 username 의 unique 보장
- 분산 락의 owner 등록 (`IF lock IS NULL`)
- 쿠폰 1회 사용 보장
- 결제 idempotency 토큰 첫 등록

쓰지 말아야 할 케이스:

- 카운터 증가 — `counter` 컬럼 사용. LWT 는 동시성에서 retry 폭주
- 단순 timestamp 기반 dedup — bucket 키 + LWW 로 충분
- 빈번한 UPDATE — paxos 테이블 쓰기 폭증
- 글로벌 invariant 가 필요한데 DC 간 latency 가 100ms 이상 — UX 망가짐

LWT 사용은 *희소한 invariant* 에만 한정한다. 빈번한 쓰기 패턴에 LWT 를 적용하면 cluster 자체가 paxos 테이블 컴팩션에 잠식된다.

## 9. 모니터링 지표

LWT 헬스 체크용 지표:

```
nodetool tpstats
# Native-Transport-Requests, MutationStage, ReadStage 외에
# Paxos-Prepare, Paxos-Propose, Paxos-Commit 의 Pending/Completed 추적

JMX MBean:
org.apache.cassandra.metrics:type=ClientRequest,scope=CASRead,name=Latency
org.apache.cassandra.metrics:type=ClientRequest,scope=CASWrite,name=Latency
org.apache.cassandra.metrics:type=ClientRequest,scope=CASWrite,name=ContentionHistogram
```

`ContentionHistogram` 의 mean 값이 1 보다 크게 올라가면 동일 row 에 동시 LWT 가 몰린다는 뜻이다. 그 row 의 데이터 모델을 분리하거나 application-level rate limit 로 분산해야 한다.

## 10. 대안: epaxos / accord (Cassandra 5.x)

Cassandra 5.0 의 새 트랜잭션 엔진 `Accord` (CEP-15) 는 leaderless multi-key transaction 을 지원하기 위해 EPaxos 변형 합의를 사용한다. 단일 키 LWT 보다 round-trip 수를 줄이고 multi-key cross-partition 트랜잭션을 정합성 손해 없이 제공한다 (Cassandra 5.x 베타). Accord 가 안정화되면 LWT 의 일부 역할은 Accord 트랜잭션으로 대체될 가능성이 높다.

```sql
BEGIN TRANSACTION
    UPDATE accounts SET balance = balance - 100 WHERE id = 'a1';
    UPDATE accounts SET balance = balance + 100 WHERE id = 'a2';
COMMIT TRANSACTION;
```

(Cassandra 5.x preview 문법)

EPaxos 는 충돌이 없을 때 *fast path* (single round-trip) 로 동작하고 충돌이 있을 때만 두 번째 round 로 fallback 하므로, Paxos 의 4단계에 비해 평균 latency 가 절반 이하가 된다.

## 11. 결론

LWT 는 Cassandra 의 AP 모델에 *지점적으로* linearizable 보장을 주입하는 도구다. 비용이 일반 쓰기의 4배 이상이며, 모든 round-trip 이 RF 전체와 진행되므로 contention 시 latency long-tail 이 심하다. *희소한 invariant* — 가입 unique, 락 획득, 쿠폰 1회 — 에만 사용하고, 그 외는 데이터 모델로 회피한다. Cassandra 5.x 의 Accord 는 multi-key 트랜잭션이라는 새 가능성을 열고 있으므로 신규 설계는 두 옵션을 함께 검토한다.

## 12. 실전 코드 — Java 드라이버 LWT 적용

DataStax Java Driver 4.x 로 LWT 를 호출하는 패턴.

```java
ResultSet rs = session.execute(
    SimpleStatement.newInstance(
        "INSERT INTO users (username, email) VALUES (?, ?) IF NOT EXISTS",
        username, email
    ).setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL)
     .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
);

Row first = rs.one();
boolean applied = first.getBoolean("[applied]");
if (!applied) {
    String existingEmail = first.getString("email");
    throw new DuplicateUsernameException(username, existingEmail);
}
```

핵심:

- `SerialConsistencyLevel` 과 `ConsistencyLevel` 두 개를 함께 설정한다. 전자는 Paxos 단계, 후자는 commit 단계의 CL.
- 응답의 `[applied]` 컬럼은 boolean 으로 항상 들어 있다. false 면 현재 row 의 값이 같이 반환되어 멱등성 처리에 활용 가능.
- 같은 row 에 대한 LWT 호출 결과를 retry 하면 *이전 commit 이 다른 코디네이터에서 일어난 후* 의 view 를 보게 되므로, 보통 첫 실패가 정답이다. 무한 retry 는 금지.

## 13. 분산 락 패턴

LWT 를 분산 락 구현에 쓰는 흔한 패턴:

```sql
CREATE TABLE locks (
    name text PRIMARY KEY,
    owner text,
    acquired_at timestamp
) WITH default_time_to_live = 60;
```

```java
// 락 획득
ResultSet r = session.execute(
    "INSERT INTO locks (name, owner, acquired_at) VALUES (?, ?, toTimestamp(now())) IF NOT EXISTS USING TTL 30",
    "global-job", instanceId
);
boolean got = r.one().getBoolean("[applied]");

// 갱신 (lease 연장)
session.execute(
    "UPDATE locks USING TTL 30 SET acquired_at = toTimestamp(now()) WHERE name = ? IF owner = ?",
    "global-job", instanceId
);

// 해제
session.execute(
    "DELETE FROM locks WHERE name = ? IF owner = ?",
    "global-job", instanceId
);
```

`TTL` 을 사용해 lease 만료 후 자동 해제되도록 한다. `IF owner = ?` 로 *내 락만* 갱신/해제한다는 invariant 를 보장. Redis 분산 락(Redlock)과 비교해 *fence token* 이 필요하면 `acquired_at` 의 timestamp 를 그대로 사용 가능.

주의: 이 패턴은 *낮은 빈도* 의 분산 작업 락 (배치 잡 전역 직렬화, 리더 선출) 에만 적합하다. 초당 수십 회 락 획득이 필요한 경우 paxos 테이블 부하로 cluster 가 무너진다. 그 빈도라면 ZooKeeper / etcd / Redis 가 정답.

## 참고

- Apache Cassandra Documentation — Lightweight Transactions
- Leslie Lamport, "The Part-Time Parliament" (Paxos 원전), TOCS 1998
- Sylvain Lebresne, "Lightweight transactions in Cassandra 2.0" DataStax 블로그
- Iamaleksey, "CEP-15: Accord — General Purpose Transactions" Cassandra Enhancement Proposal
- Iulian Moraru et al., "Egalitarian Paxos" (EPaxos), SOSP 2013
- Marko Švaljek, "Cassandra LWT Pitfalls", DataStax Conference 2019
