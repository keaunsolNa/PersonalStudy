Notion 원본: https://www.notion.so/3635a06fd6d381678b10ed88fe09033b

# Redis Streams Consumer Group과 PEL XCLAIM XAUTOCLAIM 메시지 재처리

> 2026-05-17 신규 주제 · 확장 대상: Redis

## 학습 목표

- Redis Stream 의 자료구조(엔트리 ID, radix tree)와 Consumer Group(PEL, last delivered id)의 내부 관계를 추적한다
- XREADGROUP / XACK / XCLAIM / XAUTOCLAIM 의 라이프사이클을 통해 at-least-once 메시지 처리 모델을 구성한다
- 죽은 consumer 의 inflight 메시지 회수 전략을 idle time 임계로 설계한다
- Kafka Consumer Group, RabbitMQ ack-deliver 와 비교해 Redis Streams 의 trade-off 를 분석한다

## 1. Stream 자료구조 복습

Redis Stream 은 append-only log 다. 각 엔트리는 `<ms>-<seq>` 형태의 ID 와 field-value 맵을 갖는다.

```
XADD orders * id 1001 amount 99000
→ 1715925000123-0
XADD orders * id 1002 amount 12000
→ 1715925000123-1
```

ms 가 같은 엔트리는 seq 가 증가한다. 내부 구조는 radix tree 로, key prefix 공유로 메모리 사용량을 줄인다. 100만 엔트리 stream 의 메모리 사용량은 평균 50~80MB. stream 자체는 *consumer 정보를 모른다*. 누가 어디까지 읽었는지는 Consumer Group 이 별도로 관리한다.

## 2. Consumer Group 의 두 가지 핵심 상태

```
XGROUP CREATE orders order-workers $ MKSTREAM
```

`$` 는 "그룹 생성 시점 이후 새 엔트리부터 시작" 의 의미. `0` 으로 주면 stream 전체 처음부터. 그룹은 두 가지 핵심 상태를 갖는다:

**last-delivered-id**: 그룹이 마지막으로 어떤 consumer 에게든 전달한 엔트리 ID. 다음 `XREADGROUP ... >` 호출 시 이 ID 이후부터 새 엔트리를 분배.

**PEL (Pending Entries List)**: 각 consumer 가 *받았지만 아직 XACK 하지 않은* 엔트리 목록. 엔트리당 `delivery_count`, `last_delivery_time` (idle), `consumer` 가 함께 저장.

```
XPENDING orders order-workers
1) (integer) 7
2) "1715925000123-0"   ← 가장 오래된 pending
3) "1715925002100-0"   ← 가장 최근 pending
```

PEL 이 비어 있지 않다는 것은 "어떤 consumer 가 받았지만 처리/응답을 보내지 않은 메시지가 있다" 의 의미.

## 3. 표준 워크플로우

```
worker-1:  XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 2000 STREAMS orders >
```

`>` 는 "내가 아직 받은 적 없는 새 엔트리만 달라" 의 신호. Redis 는 last-delivered-id 이후 엔트리를 최대 10개, 2초 BLOCK 하며 분배한다. 처리 후 ack:

```
XACK orders order-workers 1715925000123-0 1715925000123-1
```

PEL 에서 해당 엔트리 제거. 만약 worker-1 이 처리 중에 죽으면 그 엔트리는 PEL 에 남고 last-delivered-id 는 이미 진행되어 *다른 consumer 도 새로 받지 못한다*.

## 4. XCLAIM: 명시적 회수

```
XCLAIM orders order-workers worker-2 30000 1715925000123-0
```

"worker-2 가 1715925000123-0 엔트리의 ownership 을 가져가겠다. 단, 30초 이상 지났을 때만." XCLAIM 옵션: `IDLE ms`, `RETRYCOUNT n`, `FORCE`, `JUSTID`. XCLAIM 만으로 회수 시스템을 만들려면 *주기적으로 XPENDING 으로 idle 이 긴 엔트리 ID 를 찾아 XCLAIM* 해야 한다. 이게 번거로워서 6.2 부터 XAUTOCLAIM 이 추가됐다.

## 5. XAUTOCLAIM: 자동 일괄 회수

```
XAUTOCLAIM orders order-workers worker-2 30000 0 COUNT 100
```

"이 그룹의 PEL 중 30초 이상 idle 인 엔트리를 0 ID 부터 최대 100개까지 worker-2 가 회수." 반환값은 `[next_cursor, claimed_entries, deleted_entries]` 형태.

```python
cursor = "0"
while True:
    cursor, claimed, _ = r.xautoclaim(
        "orders", "order-workers", "worker-2",
        min_idle_time=30_000, start_id=cursor, count=100,
    )
    for entry_id, fields in claimed:
        process(entry_id, fields)
        r.xack("orders", "order-workers", entry_id)
    if cursor == "0-0":
        time.sleep(5)
```

cursor 가 `0-0` 으로 떨어지면 한 바퀴 완료.

## 6. delivery_count 기반 dead-letter

XCLAIM/XAUTOCLAIM 으로 회수된 엔트리는 `delivery_count` 가 1 증가한다. 같은 엔트리가 N 번 재전달됐다는 뜻은 *그 엔트리 자체에 처리 불가능한 결함* 이 있다는 신호.

```python
if delivery_count > 5:
    r.xadd("orders-dlq", fields)
    r.xack("orders", "order-workers", entry_id)
```

5회 이상 재전달된 엔트리는 dead-letter stream 으로 이동 + 원본 ack.

## 7. 트리밍과 PEL 의 충돌

stream 은 `XADD orders MAXLEN ~ 100000 *` 로 트리밍하지 않으면 무한히 자란다. 함정은, *PEL 에 남아 있는 엔트리도 트리밍의 대상* 이라는 점. 해결책은 **MAXLEN 을 보수적으로 설정** (PEL 의 가장 오래된 엔트리 ID 보다 여유 있게) 또는 **MINID 트리밍 사용** (`XTRIM orders MINID ~ 1715900000000-0` 시간 기준).

## 8. Kafka Consumer Group vs Redis Streams Consumer Group

| 항목 | Kafka | Redis Streams |
|---|---|---|
| 메시지 영속성 | 디스크 segment | 메모리(+AOF/RDB) |
| 그룹 단위 진행률 | partition offset | last-delivered-id + PEL |
| Inflight 추적 | client 측 offset | server 측 PEL |
| 회수 메커니즘 | rebalance | XCLAIM/XAUTOCLAIM |
| 메시지 재전달 | offset reset | XCLAIM (개별 엔트리) |
| 처리량 (단일 노드) | 수십만 msg/s | 수만 msg/s |
| 운영 복잡도 | Zookeeper/KRaft 필요 | Redis 단독 |

Redis Streams 의 가장 큰 차별점은 *PEL 이 서버에 있다* 는 점. consumer 가 죽어도 다른 consumer 가 그 inflight 메시지를 정확히 회수할 수 있다. Kafka 는 consumer 측 offset commit 모델. 처리량은 Kafka 상당히 우세 (단일 broker 50만 msg/s vs Redis 5만 msg/s).

## 9. 운영 패턴 모음

**consumer 이름 = pod 이름** — Kubernetes Pod 이름을 consumer 명으로 사용. **idle 임계는 처리 시간 P99 의 3배 이상** — 5분 평균 처리 + P99 30초 작업이라면 idle 임계 90초 이상. **XACK 누락 방지를 위해 try-finally** — 처리 실패 시 PEL 에 남아 XAUTOCLAIM 으로 자연 회수. **consumer 측 in-flight 한도** — XREADGROUP COUNT 와 worker concurrency 의 곱이 한 consumer 의 in-flight 메시지 상한. **Cluster 모드에서 stream 키 hash slot** — 한 도메인은 같은 hash tag (`{orders}.events`) 로 묶어 같은 slot 에. **XINFO STREAM FULL 의 비용** — 운영 점검은 `XINFO STREAM <key>` + `XPENDING <key> <group>` (summary form). Redisson 의 `RStream` 추상화는 XAUTOCLAIM 을 자동으로 호출해 주지 않으므로 회수 루프는 반드시 직접 작성한다.

## 참고

- Redis Streams 공식 문서: https://redis.io/docs/latest/develop/data-types/streams/
- Redis Streams Tutorial — Salvatore Sanfilippo (antirez): https://redis.io/docs/latest/develop/data-types/streams-tutorial/
- XAUTOCLAIM 도입 RFC: https://github.com/redis/redis/issues/5466
- Comparing Redis Streams and Kafka (Redis Labs Blog): https://redis.com/blog/redis-streams-vs-kafka/
- Redisson RStream API: https://redisson.org/
