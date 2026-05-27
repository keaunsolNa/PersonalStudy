Notion 원본: https://www.notion.so/36d5a06fd6d381a5bedddec1fcbbe542

# MongoDB 7 Sharded Cluster — Chunk Migration과 Balancer Throttling 운영

> 2026-05-27 신규 주제 · 확장 대상: NoSQL 분산 / MongoDB 운영

## 학습 목표

- MongoDB 7 의 sharded cluster 토폴로지(mongos, config server, shard replica set)에서 chunk 가 어떻게 정의되고 라우팅되는지 설명한다
- Balancer 가 chunk 를 옮길 때 거치는 migration commit 프로토콜과 OpRange 잠금을 추적한다
- chunkMigrationConcurrency, _secondaryThrottle, balancerWindow 등 throttling 옵션이 운영 부하에 미치는 영향을 비교한다
- Range vs Hashed sharding key 선택과 사후 reshardCollection 운영 사례를 정리한다

## 1. Sharded Cluster 의 구성

| 컴포넌트 | 역할 |
|---|---|
| `mongos` | 쿼리 라우터. chunk 분포 메타를 캐싱해 라우팅 결정 |
| Config Server (CSRS) | 메타데이터 저장 replica set |
| Shard | 실제 데이터 replica set |

mongos 가 stateless 라서 ALB 뒤에 N대 두면 라우팅 처리량이 거의 선형 확장된다. balancer 가 chunk 를 옮기면 *version bump* 이 일어나 다음 쿼리에서 mongos 가 stale 을 감지한다.

## 2. Chunk 모델

shard 는 *chunk* 단위로 데이터를 나눠 가진다. chunk 는 shard key 의 *반-개방 구간 [min, max)* 로 정의. 기본 chunkSize 는 *128 MiB* (MongoDB 6.0 부터 64→128).

chunkSize 가 크면 *migration 횟수가 줄지만 1회 migration 시간이 길어진다*. 작으면 반대. 6.0+ 부터 컴퍼세채별로 지정 가능(`sh.configureCollectionBalancing(ns, { chunkSize: 512 })`).

## 3. Balancer 의 동작 사이클

```
loop every balancerRoundInterval (default 10s):
  if balancerWindow 밖이면 skip
  for each sharded collection ns:
    1) 모든 shard 의 chunk 개수 / 사이즈 분포 조회
    2) imbalance 계산
    3) optimal donor → recipient pair 선정
    4) moveRange/moveChunk command 발행
    5) 결과 기록, version bump
```

```js
use config;
db.settings.updateOne(
  { _id: "balancer" },
  { $set: { activeWindow: { start: "01:00", stop: "06:00" }, mode: "full" } },
  { upsert: true }
);
```

## 4. Chunk Migration Commit 프로토콜

1. *Setup*: donor 가 recipient 에 chunk 메타와 인덱스 spec 전달
2. *Clone*: recipient 가 chunk 범위 문서를 *bulk fetch* 로 가져와 적용. 이 동안에도 donor 는 write 받음
3. *Catchup*: donor 가 clone 중 발생한 change 를 oplog 로 전송
4. *Critical Section*: donor 가 writes 를 짧게 차단하고 남은 변경 적용 후 commit. config server 의 chunk 메타가 atomically 갱신
5. *Cleanup*: donor 가 자기 chunk 의 *orphaned documents* 를 background 로 제거

critical section 은 *latency 가 발생하는 유일한 구간*. 7.0 기준 정상 환경에서 1~50ms.

## 5. Throttling 옵션 비교

| 옵션 | 위치 | 효과 |
|---|---|---|
| `chunkSize` | per-collection | clone 단계 1회 분량 |
| `_waitForDelete` | moveRange option | orphan delete 동기/비동기 |
| `_secondaryThrottle` | moveRange option | clone 시 recipient secondary 가 writeConcern=majority 만족 |
| `chunkMigrationConcurrency` | server parameter | recipient 이 동시 받는 chunk 수 (default 1, 7.0 부터 가변) |
| `migrateCloneInsertionBatchSize` | server parameter | clone batch 크기 |
| `migrateCloneInsertionBatchDelayMS` | server parameter | batch 사이 지연 |

## 6. Shard Key 선정 — Range vs Hashed

| 기준 | Range Key | Hashed Key |
|---|---|---|
| 라우팅 | range 쿼리 효율적 | 단일 키 lookup만 효율적 |
| 분포 | 편향 가능 | 균일 |
| Range Scan | 효율 | 모든 shard scatter |
| Monotonic insert 핫스팟 | 발생 | 회피 |
| Compound key | O | partial |

monotonic insert (`_id`=ObjectId, timestamp) 에서 range key 는 *마지막 chunk* 만 계속 쓰여 hotspot. hashed key 가 안전하지만 *시간 범위 조회* 가 모두 fan-out 되는 trade-off.

## 7. reshardCollection — 5.0 GA, 7.0 개선

```js
db.adminCommand({
  reshardCollection: "shop.orders",
  key: { tenantId: 1, _id: "hashed" },
  numInitialChunks: 1024,
  zones: [
    { min: { tenantId: MinKey, _id: MinKey }, max: { tenantId: 100, _id: MaxKey }, zone: "tier-a" },
    { min: { tenantId: 100, _id: MinKey }, max: { tenantId: MaxKey, _id: MaxKey }, zone: "tier-b" }
  ]
});
```

7.0 에서는 *zero-downtime*. 1TB 컴퍼세 수시간~수십시간, critical section 는 수초~수십초.

## 8. Jumbo Chunk 와 분할

chunk 가 chunkSize 의 *2배* 를 넘으면서 split 할 수 없는 상태가 되면 *jumbo* 로 마킹. balancer 가 옮기지 않으므로 reshardCollection 으로 해결.

## 9. 운영 메트릭과 알람

| 메트릭 | 의미 | 권장 알람 |
|---|---|---|
| `mongos:routerStats.numHostsTargeted` | 평균 fan-out 수 | shard 수에 비해 큼 → 라우팅 비효율 |
| `countDonorMoveChunkLockTimeout` | critical section timeout | non-zero 지속 |
| chunk count per shard 표준편차 | imbalance 지표 | 평균의 ±20% 초과 |
| orphan document count | cleanup 지연 | 빠른 증가 |

## 10. 자주 겪는 운영 사고

| 사고 | 원인 | 대응 |
|---|---|---|
| balancer 가 한 chunk 만 반복 이동 | shard key 가 monotonic | reshardCollection 또는 hashed key 도입 |
| migration 이 계속 abort | write 부하 > clone 처리량 | chunkSize 축소, write rate 제한 |
| orphan 누적으로 디스크 증가 | `_waitForDelete=false` 누적 | `cleanupOrphaned` 수동 |
| critical section 길어짐 | `_secondaryThrottle=true` + majority 지연 | secondary replica 상태 확인 |
| mongos 가 stale chunk 라우팅 | 메타 cache 갱신 실패 | `flushRouterConfig` 수동 |

## 11. 비교: Sharding 대안과의 trade-off

| 솔루션 | 자동 rebalance | 트랜잭션 | 운영 부담 |
|---|---|---|---|
| MongoDB sharded | O (balancer) | multi-document(7.0 cross-shard) | 메타 관리, key 선정 중요 |
| PostgreSQL Citus | O (rebalancer) | distributed | extension 의존 |
| CockroachDB | O (ranges) | 강한 직렬화 | 학습 곡선 |
| Cassandra | partition by token | LWT (Paxos) | 노드/토큰 직접 관리 |
| TiDB | O (PD scheduler) | distributed | 4+ component |

## 12. Zone Sharding

```js
sh.addShardTag("shardEU", "EU");
sh.addShardTag("shardKR", "KR");
sh.updateZoneKeyRange(
  "shop.users",
  { region: "EU", _id: MinKey },
  { region: "EU", _id: MaxKey },
  "EU"
);
```

GDPR / 개인정보보호법처럼 *데이터 거주 지역* 이 정해진 경우 핵심. balancer 는 zone 위반 chunk 를 발견하면 우선적으로 옮긴다.

## 13. Read/Write Concern 과 일관성 모드

| 조합 | 의미 |
|---|---|
| `primary` + `local` | 강한 일관성 |
| `primaryPreferred` + `local` | primary 우선 |
| `secondaryPreferred` + `majority` | 부하 분산 |
| `nearest` + `local` | latency 최적 |
| `primary` + `linearizable` | 가장 강한 일관성 |

write 는 `w=majority` 다폴트 권장. cross-shard transaction 은 `readConcern=snapshot` + `writeConcern=majority`.

## 참고

- MongoDB 공식 문서 — Sharding (https://www.mongodb.com/docs/manual/sharding/)
- MongoDB Engineering Blog — "Improvements to the Balancer in 7.0"
- "Resharding in MongoDB", VLDB 2022 paper
- "Operating MongoDB at Scale" — Square Engineering
- mongodb_exporter Prometheus repo (https://github.com/percona/mongodb_exporter)
