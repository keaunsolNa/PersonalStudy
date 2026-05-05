Notion 원본: https://www.notion.so/3575a06fd6d3815e994afd4f902c25c5

# Redis Streams — Consumer Group 과 XPENDING 기반 재처리 모델

> 2026-05-05 신규 주제 · 확장 대상: Redis Cluster 슬롯 마이그레이션, Spring Cloud Gateway Rate Limiting

## 학습 목표

- Redis Streams 의 self-incrementing entry ID 와 trim 정책이 메시지 retention 과 어떻게 결합되는지 정리한다
- Consumer Group 의 PEL(Pending Entries List) 가 Kafka offset 모델과 어떻게 다른지 코드와 명령으로 검증한다
- XPENDING / XCLAIM / XAUTOCLAIM 을 활용한 *crashed consumer 회복* 패턴을 실전 코드로 작성한다
- Spring Data Redis Stream listener 의 thread 모델, ack 시점, retry 정책을 운영 관점에서 정리한다

## 1. Stream entry ID 와 retention 의 본질

Redis Stream 의 entry ID 는 `<millis>-<sequence>` 형태로 monotonic 증가한다. `XADD mystream * field value` 의 `*` 는 *서버가 ID 를 자동 부여* 하라는 뜻이고, 명시적 ID 를 주면 *과거 ID 는 거부* 된다. 이 *monotonic 보장* 은 단일 master 노드 안에서만 유효하며, Redis Cluster 환경에서는 *키 단위* 로만 유지된다.

```
> XADD orders * userId 42 amount 19900
"1714896623000-0"
> XADD orders * userId 7  amount 7800
"1714896623000-1"
> XADD orders 1714896600000-0 field x   ❌ ERR The ID specified in XADD is equal or smaller than the target stream top item
```

retention 은 두 가지 축으로 한다. 첫째, `XADD ... MAXLEN ~ 1000000` 는 *대략 1M entries* 로 trim 한다. `~` 는 approximate 모드로, radix tree node 단위로 trim 해서 성능이 약 10배 빠르다. 둘째, `MINID 1714896000000-0` 는 *특정 시간 이전 entry* 만 삭제한다.

trim 은 *consumer 가 ack 했는지 여부와 무관* 하다. 즉 consumer group 이 아직 처리 못 한 entry 도 trim 으로 사라질 수 있다. 이 점이 Kafka 의 retention 과 비슷하지만, Kafka 는 `min.compaction.lag.ms`, `log.retention.ms` 같은 정책이 *consumer offset commit* 을 고려해 보호 영역을 제공하는 옵션이 더 풍부하다. Redis Streams 는 단순한 만큼 *retention 보다 더 빠른 consumer 처리* 를 운영자가 보장해야 한다.

## 2. Consumer Group 과 PEL — Kafka offset 과의 차이

Consumer Group 은 group 단위로 *마지막 read entry ID* 를 가지고 있고, *PEL(Pending Entries List)* 라는 별도 자료구조로 *읽었으나 아직 ack 되지 않은 entry* 를 추적한다. 이는 Kafka 의 단일 offset 모델과 다르다.

```
> XGROUP CREATE orders group1 $ MKSTREAM
> XREADGROUP GROUP group1 consumer-A COUNT 100 STREAMS orders >
1) 1) "orders"
   2) 1) 1) "1714896623000-0"
         2) 1) "userId" 2) "42" 3) "amount" 4) "19900"
      ...
> XACK orders group1 1714896623000-0
(integer) 1
```

`>` 는 *아직 어떤 consumer 도 받지 않은* entry 를 의미한다. 같은 consumer 가 같은 group 에서 `XREADGROUP ... STREAMS orders 0` 으로 호출하면 *자기 PEL* 에 있는 entry 를 다시 받는다. 이 메커니즘이 *consumer crash 후 재기동 시 같은 entry 를 다시 받는* 핵심이다.

| 모델 | Kafka | Redis Streams |
|---|---|---|
| 진행 추적 | partition 별 단일 offset | group 의 last_id + PEL |
| 부분 ack | 불가 (offset commit = 모든 이전 메시지 처리됨 의미) | 가능 (개별 entry 단위 ack) |
| 동일 partition 다중 consumer | 불가 | 가능 (consumer name 다르면) |
| 메시지 순서 보장 | partition 내 strict | stream 내 strict |

PEL 의 단점은 *PEL 자체가 RAM 을 점유* 한다는 점이다. consumer 가 죽고 재기동되지 않으면 PEL 이 무한히 자란다. `XINFO GROUPS orders` 로 `pel-count` 를 모니터링한다.

## 3. XPENDING / XCLAIM / XAUTOCLAIM — crashed consumer 회복

consumer 가 crash 했을 때 PEL 에 묶인 entry 를 다른 consumer 로 *옮겨야* 한다. Redis 5.0 에서 `XCLAIM` 으로 수동 옮기기가 도입됐고, 6.2 에서 `XAUTOCLAIM` 으로 자동화됐다.

```
# 1. 어떤 entry 가 얼마나 오래 PEL 에 있는지 확인
> XPENDING orders group1 IDLE 60000 - + 100
1) 1) "1714896600000-0"  consumer-A  120000  3
   2) ...
# 위 4-tuple = (id, consumer, idleMs, deliveryCount)

# 2. 60s 이상 idle 한 entry 를 consumer-B 로 옮기기
> XAUTOCLAIM orders group1 consumer-B 60000 0 COUNT 100
1) "0-0"            # 다음 회차 시작 ID
2) 1) 1) "1714896600000-0"  ...    # 옮겨진 entry 본문
3) 1) "1714896598000-2"            # 더 이상 stream 에 없는 entry (trim 됨)
```

`XAUTOCLAIM` 은 idle 시간 기반으로 PEL 을 *재배분* 한다. 이상적으로는 *짝수 시간마다 cron* 이 아니라, 각 consumer 가 자기 무료 시간에 한 번씩 호출하는 패턴이 좋다. delivery count 가 N(예: 5) 을 넘은 entry 는 dead-letter stream 으로 옮긴다.

```java
public void reclaimAndProcess() {
    var args = XAutoClaimArgs.Builder
        .xautoclaim(Consumer.from("group1", "consumer-B"), Duration.ofMinutes(1))
        .startId("0-0")
        .count(100);
    var result = streamCommands.xautoclaim("orders", args);
    for (var entry : result.getMessages()) {
        if (deliveryCount(entry) >= 5) {
            deadLetter(entry);
            xack(entry.getId());
            continue;
        }
        process(entry);   // 멱등 처리
        xack(entry.getId());
    }
}
```

이 코드의 핵심은 *멱등 처리* 다. PEL 회복 시 같은 entry 가 두 번 이상 처리될 수 있다. `idempotency-key` 헤더(stream entry field)를 두고 외부 DB 의 unique constraint 로 중복을 막는다. `XACK` 호출 자체는 PEL 에서 entry 를 제거하지만, *처리 도중 crash* 하면 ack 되지 않은 채 남는다.

## 4. Spring Data Redis Stream Listener 의 thread 모델

Spring Data Redis 3.x 는 `StreamMessageListenerContainer` 로 stream consumer 를 추상화한다. 두 가지 모드가 있다.

첫째, *non-blocking* 모드. `pollTimeout` 으로 명시한 주기마다 `XREADGROUP` 을 호출한다. timeout 안에 entry 가 없으면 빈 응답을 받고 다시 polling.

둘째, *blocking* 모드. `XREADGROUP BLOCK ms` 를 사용해 서버 측에서 대기. Redis connection 한 개를 *점유* 하므로 connection pool 크기를 consumer 수만큼 늘려야 한다.

```java
@Bean
StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
        RedisConnectionFactory cf) {
    var options = StreamMessageListenerContainer
        .StreamMessageListenerContainerOptions.builder()
        .pollTimeout(Duration.ofSeconds(1))
        .batchSize(50)
        .targetType(Map.class)
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();

    var container = StreamMessageListenerContainer.create(cf, options);
    container.receiveAutoAck(
        Consumer.from("group1", "consumer-" + InetAddress.getLocalHost().getHostName()),
        StreamOffset.create("orders", ReadOffset.lastConsumed()),
        new OrderListener());
    return container;
}
```

`receiveAutoAck` 는 listener 호출 *직후 자동 ack* 한다. 즉 *handler 가 throw 해도 ack* 된다. 멱등성이 보장된 처리에는 편리하지만, 처리 실패 시 dead-letter 로 옮길 수 없다. 운영에서는 `receive` (manual ack) 를 쓰고 listener 안에서 명시적으로 `streamCommands.xack(...)` 를 호출하는 것이 안전하다.

| 옵션 | autoAck | manual ack |
|---|---|---|
| 처리 실패 시 PEL 잔존 | X (이미 ack) | O |
| 멱등성 요구 | 외부 DB 에 의존 | PEL 회복 + 멱등 모두 가능 |
| 코드 복잡도 | 낮음 | 중 |
| dead-letter 분기 | 불가 | 가능 |

## 5. Stream + Sentinel/Cluster 의 운영 함정

Redis Cluster 환경에서 stream 키는 *단일 슬롯* 에 위치한다. 즉 한 stream 의 producer / consumer 는 같은 master 와 통신한다. stream 키를 *해시 태그* (`{orders}.events`) 로 묶으면 같은 슬롯에 강제할 수 있다.

failover 가 발생하면 *replica 가 master 로 승격* 되는데, 비동기 복제이므로 *복제 지연만큼의 entry 손실 가능성* 이 있다. PEL 은 master 의 dataset 에 있어 함께 복제되지만, 마지막 몇 ms 의 데이터는 lost 될 수 있다. *at-most-once* 시나리오로 봐야 안전하다. *exactly-once* 가 필요하면 producer 측에서 dedup key + outbox 패턴을 결합한다.

Sentinel 환경(단일 master + replicas) 에서도 마찬가지다. failover 후 신규 master 의 PEL 이 *직전 master 와 다를 수 있다*. consumer 는 `XAUTOCLAIM` 을 보수적으로 설정해 잘못 옮긴 entry 가 발생하지 않도록 idle 시간 기준을 길게 잡는다(보통 2x heartbeat).

## 6. 처리량 / latency / 비용의 trade-off

| 시나리오 | RPS | latency P99 | 자원 |
|---|---|---|---|
| 단일 노드, batchSize=50, 8 consumer | 60k | 8ms | CPU 35%, RAM 4GB |
| 단일 노드, batchSize=500, 8 consumer | 90k | 18ms | CPU 60%, RAM 6GB |
| 3-node cluster, 8 consumer/노드 | 220k | 12ms | CPU 50% / 노드 |
| Kafka 비교 (3-broker, replicas=3) | 800k+ | 15ms | 더 큰 인프라 |

처리량 측면에서 Redis Streams 의 천장은 Kafka 보다 낮지만, *latency 와 운영 단순성* 은 우위에 있다. 일일 수억 건 이하 + low-latency + 단일 데이터센터라면 Redis Streams 가 합리적이다. 반대로 cross-region replication, schema evolution, 1M+ RPS 가 필요하면 Kafka.

## 7. Outbox 패턴 통합

도메인 트랜잭션과 stream publish 를 *원자적* 으로 만들려면 outbox 패턴이 표준이다. application 의 트랜잭션 안에 outbox row 를 INSERT 하고, *별도 worker* 가 outbox 를 polling 해 stream 에 publish 한다.

```sql
INSERT INTO outbox (id, aggregate_id, type, payload, created_at, status)
VALUES (UUID(), :orderId, 'OrderPlaced', :payload, NOW(), 'PENDING');
```

worker 는 batch 로 `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` 후 `XADD` 와 `UPDATE outbox SET status='SENT'` 를 *같은 트랜잭션* 에 묶는다. SKIP LOCKED 가 없으면 worker 끼리 row 경합으로 처리량이 떨어진다.

stream entry 의 field 에 *outbox row id* 를 넣어 두면 dead-letter 분석이 쉽다. 운영에서는 outbox 의 `created_at` 과 stream 의 entry id (millis 부분) 사이 lag 을 P99 로 모니터링해 *publish lag SLA* 를 정의한다.

## 8. 운영에서 본 도입 결정 기준

| 상황 | 권장 |
|---|---|
| 기존 Redis 가 이미 있고 RPS ≤ 200k | Stream 도입, Kafka 미도입 |
| Kafka 운영 인력 부재 | Stream 우선 |
| schema evolution 빈번 / cross-region | Kafka |
| at-most-once 허용 | Stream + autoAck |
| exactly-once 필수 | Stream + Outbox + 멱등 처리 |

Redis Streams 는 *범용 메시지 브로커가 아니라 ephemeral pub/sub 의 진화형* 이다. 강한 retention 이 필요한 도메인 이벤트는 Kafka, *작업 큐 / 실시간 fan-out / 단일 DC RPS 200k 이내* 는 Streams 가 합리적이다. 5.0 의 Streams + 6.2 의 XAUTOCLAIM + 7.0 의 multi-stream consumer 가 합쳐지며 운영 도구는 충분히 성숙했다.

## 참고

- Redis Documentation — Streams (commands.html#stream), Stream Tutorial
- "Redis in Action" 2nd edition — Chapter 8
- Spring Data Redis 3.x Reference — StreamMessageListenerContainer
- Redis 6.2 Release Notes — XAUTOCLAIM 추가 사유