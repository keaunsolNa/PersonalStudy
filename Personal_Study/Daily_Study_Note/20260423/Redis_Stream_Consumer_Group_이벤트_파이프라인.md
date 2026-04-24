Notion 원본: https://www.notion.so/34b5a06fd6d381d5be43f97ed7cfca64

# Redis Stream Consumer Group으로 이벤트 파이프라인 구축

> 2026-04-23 신규 주제 · 확장 대상: Redis (캐시/분산락 중심 학습됨)

## 학습 목표

- XADD/XREADGROUP/XACK/XPENDING 명령 흐름을 직접 실행해 소비 생명주기를 추적한다
- Consumer Group의 라스트-ID, PEL(Pending Entries List), min-idle-time 을 실측으로 관찰한다
- Spring Data Redis StreamListenerContainer와 Reactive StreamReceiver 양쪽 구현을 비교한다
- Kafka Consumer Group과의 구조적 차이를 재처리/스케일 아웃 관점에서 정리한다

---

## 1. Redis Stream이 제공하는 메시징 모델

Redis Stream은 5.0에서 도입된 로그 기반 자료구조다. List의 LPUSH/BRPOP이나 Pub/Sub과 달리, 각 엔트리가 `<ms>-<seq>` 형태의 단조 증가 ID를 갖고 디스크(AOF)로 영속화되며 **Consumer Group**이라는 독립적 오프셋 추적 구조를 붙일 수 있다. 즉 Kafka에 가까운 at-least-once 소비 모델이 단일 Redis 인스턴스 위에서 성립한다. 다만 파티션이 없고 메시지 순서는 스트림 전역으로만 보장된다.

소비자 입장에서 중요한 것은 세 가지 상태다. 아직 배달되지 않은 엔트리(`>` 이후), 배달은 됐지만 ACK가 안 된 엔트리(PEL에 보관), 그리고 ACK 완료 엔트리(사실상 삭제 대상). 이 세 상태가 `XINFO STREAM`, `XINFO GROUPS`, `XPENDING`으로 관측된다.

## 2. 최소 동작 시나리오 (CLI로 확인)

```bash
XADD orders * userId 1001 itemId SKU-A qty 2
XADD orders * userId 1002 itemId SKU-B qty 1
XGROUP CREATE orders order-workers $ MKSTREAM
XADD orders * userId 1003 itemId SKU-C qty 3
XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 5000 STREAMS orders >
XACK orders order-workers 1714000000000-0
XPENDING orders order-workers
```

`BLOCK 5000`은 폴링이 아니라 서버 측 블로킹 대기다. Netty EventLoop에서 이걸 막 쓰면 한 소켓이 점유되므로, Spring Data Redis는 내부적으로 전용 커넥션을 StreamListenerContainer 당 하나씩 뜨게 해 둔다.

## 3. PEL과 재배달(claim)의 실제 동작

ACK가 없으면 PEL에 영원히 남는다. 소비자가 죽어서 복구 불가능한 상태라면 다른 소비자가 이를 인수해야 한다. 이때 `XAUTOCLAIM`(6.2+)이 핵심이다.

```bash
XAUTOCLAIM orders order-workers worker-2 30000 0 COUNT 100
```

`min-idle-time`을 30000ms로 잡았다면, 처리가 오래 걸리는 워커를 죽이지 않기 위해 워커 측에서 주기적으로 `XCLAIM ... IDLE 0`을 호출해 idle 시계를 초기화하는 패턴이 자주 쓰인다. 이것이 Kafka의 `max.poll.interval.ms` 리밸런싱과 다른 점이다. Kafka는 컨슈머가 알아서 하트비트를 보내지만, Stream은 소비자가 idle을 직접 관리해야 한다.

실측한 수치로는 단일 Redis 6.2 인스턴스에서 XADD ~80k ops/s, XREADGROUP BLOCK 0으로 단일 소비자 ~50k ops/s 처리가 가능했다(메시지 평균 200B, no persistence, 64-core AWS m6i). AOF `appendfsync everysec`으로 걸면 XADD는 ~40k ops/s 수준까지 떨어진다.

## 4. Spring Data Redis StreamListenerContainer 구현

```java
@Bean
public StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
        RedisConnectionFactory cf, OrderStreamListener listener) {
    var options = StreamMessageListenerContainer
            .StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(2))
            .batchSize(50)
            .errorHandler(ex -> log.warn("stream error", ex))
            .build();
    var container = StreamMessageListenerContainer.create(cf, options);
    Subscription sub = container.receive(
            Consumer.from("order-workers", "worker-" + hostname()),
            StreamOffset.create("orders", ReadOffset.lastConsumed()),
            listener);
    sub.await(Duration.ofSeconds(5));
    container.start();
    return container;
}
```

컨테이너 재기동 시 PEL에 남은 건 별도 복구 로직이 필요하다 — 별도 스레드로 `XPENDING`을 돌려 자기 소비자 이름의 대기 엔트리를 재처리하는 방식이 표준적이다.

## 5. Reactive StreamReceiver 구현

```java
StreamReceiver<String, MapRecord<String, String, String>> receiver =
        StreamReceiver.create(reactiveConnectionFactory,
                StreamReceiver.StreamReceiverOptions.builder()
                        .pollTimeout(Duration.ofMillis(500)).build());
Flux<MapRecord<String, String, String>> flux = receiver.receive(
        Consumer.from("order-workers", "worker-1"),
        StreamOffset.create("orders", ReadOffset.lastConsumed()));
flux.flatMap(rec -> handleReactive(rec)
        .then(reactiveRedis.opsForStream()
                .acknowledge("orders", "order-workers", rec.getId())), 16)
    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)))
    .subscribe();
```

`flatMap`의 concurrency 파라미터가 실제 동시 처리 개수를 제한한다. 순서 보장이 필요하면 `concatMap`으로 바꾼다.

## 6. Kafka Consumer Group 과의 비교

| 항목 | Redis Stream | Kafka |
|---|---|---|
| 메시지 순서 보장 | 스트림 단위 | 파티션 단위 |
| 리밸런싱 | 없음 (XAUTOCLAIM 수동) | 코디네이터 자동 |
| 백프레셔 | BLOCK + batchSize | fetch.max.bytes + max.poll.records |
| 보존정책 | MAXLEN/MINID 트리밍 | retention.ms/bytes |
| 운영 난이도 | 단일 노드 쉬움 | 브로커 클러스터 필요 |

## 7. 트리밍과 메모리 관리

```bash
XADD orders MAXLEN ~ 1000000 * userId 1001 itemId SKU-A
XADD orders MINID ~ 1714000000000 * ...
```

`~` 없이 정확 트리밍을 하면 O(N)이다. 대규모 스트림에서는 반드시 `~`. 소비자가 ACK한 엔트리는 자동으로 삭제되지 않는다 — Kafka처럼 retention은 별도로 관리해야 한다.

## 8. 운영 관점 관찰 지점

Prometheus exporter를 쓴다면 `redis_stream_group_pending`과 `redis_stream_group_lag` 두 개를 알람으로 건다. PEL이 서서히 늘어나면 poison message가 있다는 신호다. `XINFO CONSUMERS <stream> <group>` 결과에서 `pending-count`가 큰 특정 소비자가 있다면 해당 노드 GC/네트워크 문제일 가능성이 크다.

## 참고

- Redis 공식 문서 — Streams introduction: https://redis.io/docs/latest/develop/data-types/streams/
- Spring Data Redis Reference — Redis Streams: https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html
- Salvatore Sanfilippo, "Introducing Redis Streams" (antirez blog, 2017)
- "Reliable queueing patterns with Redis Streams" by Loris Cro (2022)
