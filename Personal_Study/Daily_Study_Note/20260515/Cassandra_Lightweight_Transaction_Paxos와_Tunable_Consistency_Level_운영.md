Notion 원본: https://www.notion.so/3615a06fd6d38120b49df409e1659232

# Cassandra Lightweight Transaction Paxos와 Tunable Consistency Level 운영

> 2026-05-15 신규 주제 · 확장 대상: NoSQL / 분산 합의

## 학습 목표

- Cassandra LWT 가 사용하는 4단계 Paxos(prepare/promise/propose/accept) 흐름을 추적한다
- ConsistencyLevel(ONE/QUORUM/ALL) 과 SerialConsistencyLevel(SERIAL/LOCAL_SERIAL) 의 의미를 분리한다
- LWT 의 지연 비용을 일반 INSERT 대비 정량적으로 비교한다
- 멀티 DC 환경에서 LOCAL_SERIAL vs SERIAL 선택과 그에 따른 데이터 분기 가능성을 정리한다

## 1. 일반 쓰기 vs Lightweight Transaction

Cassandra 의 일반 쓰기는 **eventual consistency** 를 따른다. 두 클라이언트가 동시에 같은 row 에 `INSERT` 하면 마지막 timestamp(LWW: Last Write Wins) 가 이긴다. 충돌 검출도 없다.

```sql
INSERT INTO users (id, email) VALUES (1, 'a@x.com') IF NOT EXISTS;
UPDATE accounts SET balance = 500 WHERE id = 7 IF balance = 1000;
```

`IF` 절이 붙으면 일반 쓰기 경로가 아니라 **Paxos 합의** 경로로 들어간다. 응답에는 `applied: true/false` 와 현재 값이 같이 돌아온다.

## 2. LWT 의 4단계 Paxos 흐름

Cassandra LWT 는 multi-Paxos 가 아닌 **single-decree Paxos** 를 매 트랜잭션마다 새로 돌린다:

```
1) PREPARE  : coordinator → replicas, 새 ballot 제안
2) PROMISE  : replicas → coordinator, 더 큰 ballot 못 봤다고 약속
3) READ     : 현재 row 값 확인 (IF 조건 평가용)
4) PROPOSE  : coordinator → replicas, 조건 통과면 새 값 제안
5) ACCEPT   : replicas → coordinator, accept 표시 + commit log 기록
6) COMMIT   : 일반 쓰기 경로로 mutation 전파
```

각 단계는 모두 **SerialConsistencyLevel** (보통 QUORUM) 을 충족해야 한다. 일반 쓰기 1라운드와 비교하면 LWT 는 사실상 4라운드. 응답 지연은 **약 4배**가 된다.

```java
SimpleStatement stmt = SimpleStatement.builder(
		"UPDATE accounts SET balance = ? WHERE id = ? IF balance = ?")
	.addPositionalValue(500).addPositionalValue(7).addPositionalValue(1000)
	.setConsistencyLevel(ConsistencyLevel.QUORUM)
	.setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
	.build();
ResultSet rs = session.execute(stmt);
boolean applied = rs.one().getBoolean("[applied]");
```

## 3. ConsistencyLevel과 SerialConsistencyLevel 의 분리

LWT 가 자주 헷갈리는 이유는 일관성 레벨을 **2개** 지정해야 하기 때문이다.

- **SerialConsistencyLevel** (SERIAL / LOCAL_SERIAL): Paxos 의 합의 단계가 요구하는 replica 정족수
- **ConsistencyLevel** (ONE / QUORUM / ALL / LOCAL_QUORUM 등): COMMIT 단계 응답 정족수

| 시나리오 | Serial | Consistency |
|---|---|---|
| 단일 DC, 강한 합의 | SERIAL | QUORUM |
| 단일 DC, 빠른 commit | SERIAL | ONE |
| 멀티 DC, DC 로컬 합의 | LOCAL_SERIAL | LOCAL_QUORUM |
| 멀티 DC, 전역 합의 | SERIAL | LOCAL_QUORUM 또는 EACH_QUORUM |

## 4. LOCAL_SERIAL 의 함정

LOCAL_SERIAL 은 같은 DC 내 replica 정족수만 가지고 합의한다. 멀티 DC 환경에서 지연을 줄이는 효과가 크지만 두 DC 가 각자 LOCAL_SERIAL 로 LWT 를 동시에 실행하면 **다른 결과**가 나올 수 있다. 두 DC 가 통신이 끊긴 split-brain 동안 한쪽에서 `IF NOT EXISTS` 가 성공하고 다른 쪽에서도 성공하면, 통신 복구 후 LWW 로 한쪽이 사라진다. 합의의 의미가 사실상 사라진다.

운영 규칙: LOCAL_SERIAL 은 한 DC 가 master 역할을 분명히 맡는 토폴로지에서만 안전. 양 DC active-active 라면 SERIAL + LOCAL_QUORUM 조합으로 전역 합의를 강제한다.

## 5. Paxos 메타데이터 테이블

각 partition 의 진행 중 Paxos 상태는 `system.paxos` 테이블에 보관. LWT 가 끝나면 `most_recent_commit` 이 채워지고 `in_progress_ballot` 이 비워진다. paxos 테이블에 row 가 누적되면 LWT 가 점점 느려지는 현상이 있고, Cassandra 4.x 의 `paxos_state_purging` 옵션으로 자동 정리.

## 6. 성능 측정

c6g.large 3노드 클러스터, RF=3, single DC, 4KB row:

| Operation | p50 | p95 | p99 |
|---|---|---|---|
| INSERT (CL=ONE) | 1.2ms | 3ms | 7ms |
| INSERT (CL=QUORUM) | 1.8ms | 4ms | 9ms |
| LWT INSERT IF NOT EXISTS | 6ms | 14ms | 32ms |
| LWT UPDATE IF | 7ms | 16ms | 38ms |
| 같은 키에 동시 LWT 100개 | 80ms | 250ms | 500ms |

LWT 는 일반 INSERT 대비 약 4-5배 느리다. 동일 키에 경합이 몰리면 비용이 폭증.

**경합 시 클라이언트 동작**: `WriteTimeoutException` 의 `WriteType = CAS` 가 반환. 단순 재시도가 안전. `WriteType = SIMPLE` 은 Paxos 자체는 성공, mutation 전파만 실패한 경우로 후속 SELECT 로 적용 여부 확인이 필요.

## 7. 대체 패턴

LWT 가 비싸기 때문에 종종 우회. **UUID 기반 unique key**(UUIDv4 / UUIDv7 / ULID), **Materialized View + 일반 쓰기**(MV 자체도 race condition, Cassandra 4.x 에서 deprecated 권고 흐름), **외부 합의 서비스**(ZooKeeper / etcd / DynamoDB Conditional Writes), **트랜잭션 후보군이 매우 적은 경우만 LWT**(결제 / 좌석 / 재고처럼 정합성이 결정적인 곳만).

## 8. Cassandra 5.0 의 Accord 트랜잭션

Cassandra 5.0(2024 GA) 부터 **Accord** 라는 새 합의 알고리즘이 들어온다. Accord 는 EPaxos 계열로 leaderless 멀티 키 트랜잭션을 단일 라운드로 처리.

```sql
BEGIN TRANSACTION
  UPDATE accounts SET balance = balance - 100 WHERE id = 7;
  UPDATE accounts SET balance = balance + 100 WHERE id = 8;
COMMIT TRANSACTION;
```

단일 키 LWT 대비 비슷한 지연(p50 ~6ms), 멀티 키 트랜잭션 지원, 전역 일관성. 다만 5.0 시점에는 experimental 단계이고 production 도입은 5.x 후반대 권장.

## 트레이드오프 정리

- **LWT 는 강력하지만 비싸다**. 일반 쓰기보다 4-5배 느림. 경합 시 더 악화.
- **SerialConsistencyLevel 과 ConsistencyLevel 은 별개 축**. 둘 다 명시적으로 지정.
- **LOCAL_SERIAL 은 멀티 DC active-active 에서 위험**. 합의 의미가 사실상 사라진다.
- **남용 금지**: 모든 쓰기를 LWT 로 하면 Cassandra 의 처리량 이점이 사라진다.
- **Accord 가 다음 세대**. 5.0 GA, 멀티 키 + 전역 일관성. 일반 사용 가능 시점까지 LWT 가 표준.

## 참고

- Cassandra LWT 공식 문서: https://cassandra.apache.org/doc/latest/cassandra/operating/lightweight_transactions.html
- Lamport, "Paxos Made Simple" (2001): https://lamport.azurewebsites.net/pubs/paxos-simple.pdf
- Accord CEP-15: https://cwiki.apache.org/confluence/display/CASSANDRA/CEP-15%3A+General+Purpose+Transactions
- DataStax — "Why LWTs are expensive"
- "Cassandra: The Definitive Guide" 3rd ed., Carpenter & Hewitt, O'Reilly
