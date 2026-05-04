Notion 원본: https://www.notion.so/3565a06fd6d3817d87fcde161ad41188

# MongoDB Change Streams와 Resume Token — Oplog 기반 이벤트 파이프라인 설계

> 2026-05-04 신규 주제 · 확장 대상: Cassandra LWT, MySQL Binlog GTID

## 학습 목표

- Replica Set / Sharded Cluster 의 oplog 가 Change Stream 의 이벤트 소스로 어떻게 변환되는지 단계별로 정리한다
- Resume Token 의 데이터 구조(BSON `_data`, `_typeBits`, version) 와 *언제 안전하게 토큰이 유효한지* 의 보장 범위를 식별한다
- `fullDocument`, `fullDocumentBeforeChange`, `pre/post image` 옵션이 oplog · WiredTiger 에 어떤 비용을 전가하는지 정량적으로 비교한다
- `resumeAfter` vs `startAfter` vs `startAtOperationTime` 의 의미 차이와 장애·롤오버 상황에서 어떤 옵션이 안전한지 결정 기준을 세운다

## 1. Change Stream 이 Oplog 위에서 어떻게 만들어지는가

MongoDB 의 Replica Set 에서 모든 쓰기는 Primary 의 *oplog* (`local.oplog.rs`) 에 capped collection 형태로 기록된다. Secondary 는 oplog 를 tail 해서 자기 데이터를 따라잡는다. Change Stream 은 이 oplog tailing 을 사용자 레벨에서 추상화한 인터페이스다.

```
client.watch() 호출
  → mongod 가 $changeStream aggregation stage 를 시작
    → oplog tailing
    → BSON 변환·필터·재구성
    → 클라이언트에 stream
```

Change Stream 은 내부적으로 *aggregation pipeline* 으로 구현된다. 그래서 사용자가 `$match`, `$project`, `$addFields`, `$replaceRoot` 같은 stage 를 자유롭게 뒤에 붙일 수 있다.

```javascript
const pipeline = [
  { $match: { "fullDocument.region": "KR", operationType: { $in: ["insert", "update"] } } },
  { $project: { _id: 1, fullDocument: 1, ns: 1, operationType: 1 } }
]
const cs = db.collection("orders").watch(pipeline, { fullDocument: "updateLookup" })
for await (const change of cs) {
  await handle(change)
  await offsets.save(cs.resumeToken)
}
```

Sharded Cluster 의 경우 Change Stream 은 mongos 에서 모든 shard 의 oplog 를 *cluster-wide ordering* 으로 머지한다. 이 머지는 각 shard 의 *primary majority committed* 시점을 기준으로 정렬을 보장한다. 즉 잠시 동안 한 shard 만 빠르게 진행하면 stream 은 가장 느린 shard 가 따라올 때까지 대기한다.

## 2. Resume Token 의 구조와 버전

Resume Token 은 한 이벤트의 *재시작 가능 위치* 를 표현하는 불투명(opaque) 값이다. BSON 으로 직렬화되며 다음과 같은 구조를 가진다.

```
{
  _data: HEX_STRING,
  _typeBits?: BinData
}
```

`_data` 는 키-순서가 보존된 *KeyString* 인코딩으로, oplog 의 `ts`(timestamp), `_id`, `txnOpIndex`(트랜잭션 내 op 순서), `documentKey` 등을 포함한다. version 필드는 형식 자체에 들어 있고, MongoDB 4.x → 5.x → 6.x → 7.x 로 올라가면서 v0 → v1 → v2 로 변화했다. 클라이언트가 다른 메이저 버전에서 만든 토큰을 그대로 새 버전 mongod 에 줘도 backward 호환되지만, 내려가는 방향은 보장되지 않는다.

토큰이 *안전하게 유효한 기간* 은 *해당 oplog 엔트리가 oplog 에 아직 살아 있는 동안* 이다. capped oplog 가 회전(rollover) 해서 그 엔트리가 사라지면 토큰으로 재개할 수 없고 `ChangeStreamHistoryLost` 오류가 난다. Production 에서 토큰을 안전하게 보관하려면 *컨슈머 다운타임이 oplog 보관 기간보다 짧다는 것* 을 운영적으로 보장해야 한다.

`db.getReplicationInfo()` 의 `timeDiff` 가 oplog 가 커버하는 시간 범위다. 일반적으로 24시간 이상을 권장한다. 부하가 크면 oplog size 를 명시적으로 늘려야 한다.

## 3. fullDocument 와 pre/post image 옵션의 비용

Change Stream 의 기본 이벤트는 *변경 사실* 만 담는다. update 이벤트라면 `updateDescription.updatedFields` 에 변경된 필드 목록만 들어 있고, 변경 후 문서 전체는 들어 있지 않다. 변경 후 문서를 받으려면 다음 옵션이 필요하다.

```javascript
db.collection("orders").watch([], {
  fullDocument: "updateLookup",            // change 발생 직후 문서 전체 조회
  fullDocumentBeforeChange: "whenAvailable" // 변경 직전 문서 (preImage)
})
```

| 옵션 | 비용 | 비고 |
|---|---|---|
| `fullDocument: "default"` | 거의 무료 | update 시 변경 필드만 |
| `fullDocument: "updateLookup"` | 변경 후 즉시 *추가 read* 1회 | update 와 read 사이에 또 다른 update 가 끼면 latest 가 반환됨 |
| `fullDocument: "required"` | updateLookup + 누락 시 오류 | "정확히 그 시점" 문서가 사라지면 실패 |
| `fullDocumentBeforeChange: "whenAvailable"` | preImage 컬렉션이 켜져 있어야 동작 | 컬렉션 정의에 `changeStreamPreAndPostImages: { enabled: true }` 필요 |
| `fullDocumentBeforeChange: "required"` | 위 + 누락 시 오류 | preImage rollover 로 사라지면 실패 |

preImage / postImage 는 별도의 capped collection (`config.system.preimages`) 에 저장된다. 활성화하면 *모든 update/delete 가 변경 직전 문서를 추가로 기록* 하므로 디스크 IO 와 저장 공간이 크게 늘어난다. 한 컬렉션의 평균 문서 크기가 4KB 이고 update 가 초당 1000건이면, preImage 만으로 시간당 14GB 가 추가로 쌓인다. 보관 기간 `expireAfterSeconds` 를 짧게 잡아 회전시키는 게 일반적이다.

`updateLookup` 의 경고는 *시간차로 인한 inconsistency* 다. update A → update B 가 빠르게 연속되면 A 이벤트의 lookup 결과가 B 의 결과를 보여 줄 수 있다. 정확한 변경 시점 스냅샷이 필요하다면 preImage/postImage 를 켜야 한다.

## 4. resumeAfter · startAfter · startAtOperationTime 차이

Change Stream 을 재개하는 옵션 세 가지의 의미는 다르다.

| 옵션 | 동작 | 안전한 상황 |
|---|---|---|
| `resumeAfter: token` | 해당 토큰의 *다음* 이벤트부터. 토큰이 가리키는 oplog 엔트리가 살아 있어야 함 | 정상 종료 후 짧은 다운타임 재시작 |
| `startAfter: token` | `resumeAfter` 와 거의 같지만, 컬렉션 *drop/rename* 같은 invalidate 이벤트 이후에도 재개 가능 | 의도적 schema change 후 재시작 |
| `startAtOperationTime: ts` | 특정 BSON Timestamp 부터. 토큰이 없어도 됨 | 첫 부팅, 또는 토큰을 잃었을 때 |

`startAfter` 는 `resumeAfter` 의 super-set 이다. 정확히 같은 동작을 하면서 invalidate 도 통과하므로, 애플리케이션이 schema change 에 견뎌야 한다면 `startAfter` 를 기본값으로 쓰는 게 안전하다.

`startAtOperationTime` 은 *토큰을 못 받은 첫 시작* 에 유용하다. 예를 들어 실시간 인덱싱 파이프라인을 처음 띄울 때 "오늘 0시부터의 변경만" 같은 시작점을 잡고 싶다면 `Timestamp(seconds, 0)` 으로 지정한다. 단 이 시점이 oplog 에 살아 있어야 한다.

## 5. ChangeStreamHistoryLost 와 복구 전략

운영에서 가장 자주 마주치는 오류는 `ChangeStreamHistoryLost` 다. 토큰이 가리키는 oplog 엔트리가 capped 회전으로 사라졌을 때 mongod 가 명시적으로 던진다.

복구 전략 세 가지.

첫째, *토큰을 버리고 startAtOperationTime 으로 시점 점프*. 누락 구간이 생기지만 stream 자체는 살린다. 누락 구간을 보충하기 위해 *cold rebuild* 를 비동기로 같이 돌리는 패턴이 일반적이다. 예: 인덱서가 oplog 를 잃었을 때 누락 시간대만 컬렉션 풀스캔으로 reindex 하고, 그 사이의 새 변경은 stream 으로 따라잡는다.

둘째, *컨슈머 그룹 다중화*. 같은 stream 을 두 개의 컨슈머 그룹이 처리하고, 각자의 토큰을 따로 보관하면 한쪽이 다운돼도 다른 쪽이 stream 을 살려 둔다. capped rollover 위험은 토큰을 *체크포인트로 자주 저장* 하는 걸로 줄인다.

셋째, *oplog 보관 기간을 늘리는 운영적 결정*. mongod 의 `replication.oplogSizeMB` 또는 `replSetResizeOplog` 명령으로 늘릴 수 있다. 컨슈머 최대 다운타임의 5배 정도를 권장한다.

## 6. 트랜잭션과 Change Stream

MongoDB 4.0+ 의 multi-document transaction 안에서 발생한 변경은 Change Stream 으로 *트랜잭션이 commit 된 시점* 에 한꺼번에 보인다. 각 op 는 같은 `lsid`(logical session id) 와 `txnNumber` 를 공유하고, `txnOpIndex` 로 트랜잭션 내 순서가 매겨진다.

```javascript
{
  operationType: "insert",
  clusterTime: Timestamp(...),
  lsid: { id: UUID(...), uid: BinData(...) },
  txnNumber: NumberLong(7),
  txnOpIndex: 2,
  // ...
}
```

이 정보로 컨슈머는 *같은 트랜잭션의 모든 op 를 원자적으로 처리* 할 수 있다. 예를 들어 외부 인덱스에 반영할 때 트랜잭션 단위로 batch commit 을 묶으면 일관성이 깨지지 않는다.

함정 한 가지: 트랜잭션 abort 는 stream 에 *전혀 보이지 않는다*. abort 된 op 는 oplog 에 기록되지 않으므로 컨슈머는 그런 op 가 있었는지조차 모른다. 이건 잘못이 아니라 *원하는 동작* 이다.

## 7. 클러스터 와이드 vs 컬렉션 와이드 watch

`db.collection("x").watch()` 는 컬렉션 단위, `db.watch()` 는 데이터베이스 전체, `client.watch()` 는 cluster-wide 다. 후자로 갈수록 oplog 부하가 더 많이 mongod → 클라이언트로 이동한다.

cluster-wide watch 의 활용 사례는 다음과 같다.

- *실시간 백업/리플리케이션*: 모든 컬렉션의 변경을 한 stream 으로 받아 외부로 전송. 카프카 connector 등이 이 패턴.
- *글로벌 감사 로그*: 어떤 컬렉션에서든 발생한 변경을 한 곳에 누적.
- *하이브리드 검색 인덱스*: 여러 컬렉션의 변경을 한 elasticsearch / vector store 인덱스에 통합 반영.

cluster-wide stream 은 리밸런싱이 일어나는 sharded cluster 에서 chunk migration 이벤트(`migrateChunkToNewShard`, `reshardCollection`)도 받는다. 이 메타 이벤트는 일반 데이터 이벤트와 분리해서 처리해야 한다.

## 8. 카프카 커넥터와의 연동 패턴

MongoDB Kafka Source Connector 는 위 메커니즘을 그대로 사용해서 토픽으로 보낸다. 운영에서 흔히 부딪히는 튜닝 포인트.

| 설정 | 권장 | 이유 |
|---|---|---|
| `change.stream.full.document` | `updateLookup` | preImage 비용 없이 변경 후 문서 확보 |
| `publish.full.document.only` | `false` | 전체 change event 를 보존하면 디버깅 용이 |
| `output.format.value` | `schema` (Avro/Protobuf) | 토큰 _data 가 그대로 보존되어 재처리 가능 |
| `topic.namespace.map` | 명시 | DB.collection → topic 매핑 일관성 |
| `errors.tolerance` | `none` (운영) | 누락 방지. 폭발 시 즉시 알람 |
| `offset.flush.interval.ms` | 5000 | 토큰 저장 주기. 짧을수록 안전 |

토큰은 Kafka Connect 의 offset 저장소(보통 `__connect-offsets` 토픽)에 저장된다. Kafka 클러스터를 옮겨야 한다면 이 토픽을 함께 마이그레이션해야 같은 stream 을 잃지 않고 이어서 처리할 수 있다.

## 참고

- MongoDB Manual — Change Streams: https://www.mongodb.com/docs/manual/changeStreams/
- MongoDB Manual — Change Stream Resume Tokens: https://www.mongodb.com/docs/manual/changeStreams/#resume-token
- MongoDB Manual — Pre- and Post-Images: https://www.mongodb.com/docs/manual/changeStreams/#change-streams-with-document-pre--and-post-images
- MongoDB Engineering Blog — KeyString Encoding: https://www.mongodb.com/blog
- MongoDB Kafka Connector Reference: https://www.mongodb.com/docs/kafka-connector/current/source-connector/
