Notion 원본: https://www.notion.so/3ef5a06fd6d38168a56eebf50c77ee07

# Redis Streams Consumer Group과 XAUTOCLAIM 기반 Pending 재처리 및 at-least-once 메시지 처리 설계

> 2026-10-04 신규 주제 · 확장 대상: Redis (캐시 중심 학습 → 메시징 심화), Spring Data Redis

## 학습 목표

- Consumer Group이 메시지를 분배하고 PEL(Pending Entries List)에 기록하는 흐름을 redis-cli로 재현한다.
- XPENDING, XCLAIM, XAUTOCLAIM으로 죽은 컨슈머의 메시지를 다른 컨슈머가 회수하게 만든다.
- 중복 처리를 전제로 한 멱등 소비자와 재시도 한도, Dead Letter 스트림을 설계한다.
- Spring Data Redis의 StreamMessageListenerContainer로 소비, ACK, 재처리 스케줄러를 구성한다.

## 1. 왜 Pub/Sub이 아니라 Streams인가

Redis를 캐시로만 쓰다가 메시징이 필요해지면 가장 먼저 만나는 기능이 Pub/Sub이다. Pub/Sub은 fire-and-forget 모델이라 구독자가 끊겨 있는 동안 발행된 메시지는 사라지고 저장도 재전송도 없다. 주문 이벤트처럼 "반드시 한 번 이상 처리"가 필요한 작업에는 맞지 않는다. List 큐(LMOVE)는 저장은 되지만 컨슈머가 꺼낸 뒤 죽으면 처리 중 항목 추적을 앱이 직접 해야 한다.

Streams(5.0 도입)는 append-only 로그에 ID를 붙여 저장하고, Consumer Group이라는 서버 측 상태를 통해 "누가 어디까지 받았고 무엇을 아직 확인(ACK)하지 않았는지"를 Redis가 기록한다. 이 기록이 PEL이다. 컨슈머가 죽어도 PEL에는 해당 메시지가 남아 있으므로 다른 컨슈머가 이어받을 수 있다. 이 문서가 다루는 at-least-once는 바로 이 PEL과 재처리 명령 위에서 성립한다. 반대로 말하면 Redis Streams 자체는 exactly-once를 제공하지 않으며, 중복 전달 가능성은 설계로 흡수해야 한다.

## 2. Consumer Group 기본 동작을 redis-cli로 확인하기

먼저 스트림과 그룹을 만든다. XGROUP CREATE의 `$`는 "그룹 생성 이후 들어오는 메시지부터"를 뜻하고, `0`은 스트림의 처음부터 읽겠다는 뜻이다. MKSTREAM을 붙이면 스트림이 아직 없을 때 빈 스트림을 함께 만든다.

```bash
# 그룹 생성 (스트림이 없으면 함께 생성)
redis-cli XGROUP CREATE orders order-workers $ MKSTREAM

# 프로듀서: ID를 자동 생성(*)하고 대략 10,000건 유지(~)
redis-cli XADD orders MAXLEN '~' 10000 '*' orderId 1001 type PAID amount 12000
redis-cli XADD orders MAXLEN '~' 10000 '*' orderId 1002 type PAID amount 8000

# 컨슈머 worker-1: '>' 는 "이 그룹에 아직 전달된 적 없는 새 메시지"
redis-cli XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 5000 STREAMS orders '>'
```

XREADGROUP에서 ID로 `>`를 주면 그룹이 아직 어떤 컨슈머에게도 전달하지 않은 메시지만 받는다. 이때 Redis는 메시지를 worker-1의 PEL에 올리고 전달 횟수(delivery count)를 1로 기록한다. 컨슈머는 첫 XREADGROUP 호출 때 생성된다. 처리를 마치면 XACK으로 PEL에서 제거한다.

```bash
redis-cli XACK orders order-workers 1728000000000-0
# 반환값 1 = 실제로 PEL에서 제거된 개수 (이미 ACK된 ID면 0)
```

여기서 흔한 오해가 둘 있다. 첫째, XACK은 스트림 엔트리를 지우지 않고 PEL에서만 뺀다(삭제는 XDEL/XTRIM). 둘째, `>` 대신 `0` 같은 구체적 ID로 XREADGROUP을 부르면 새 메시지가 아니라 해당 컨슈머의 PEL 이력을 돌려주며, 재시작 직후 자기 미확인 메시지를 먼저 비우는 패턴에 쓰인다.

## 3. PEL과 XPENDING: 무엇이 얼마나 오래 막혀 있는가

PEL은 "전달되었지만 ACK되지 않은 메시지"의 목록이다. 운영에서 가장 먼저 보아야 할 지표이므로 XPENDING과 XINFO 사용법을 익혀 둔다. 요약형은 그룹 전체의 pending 개수, 가장 작은/큰 ID, 컨슈머별 개수를 보여 준다.

```bash
# 요약형
redis-cli XPENDING orders order-workers

# 확장형: IDLE(ms) 이상 방치된 항목만, 최대 20개 (IDLE 옵션은 6.2+)
redis-cli XPENDING orders order-workers IDLE 60000 - + 20

# 항목 필드: [ID, 소유 컨슈머, 마지막 전달 후 경과 ms(idle), 전달 횟수]
```

확장형 출력의 idle은 해당 메시지가 마지막으로 전달된 뒤 흐른 시간이고, 전달 횟수는 이 메시지가 지금까지 몇 번 컨슈머에게 건네졌는지를 나타낸다. 이 두 값이 재처리 정책의 핵심 입력이며, 전달 횟수가 큰 메시지는 poison message 후보다.

그룹 단위 상태는 XINFO로 확인한다.

```bash
redis-cli XINFO GROUPS orders
# name, consumers, pending, last-delivered-id, entries-read, lag (entries-read/lag 은 7.0+)

redis-cli XINFO CONSUMERS orders order-workers

```

lag은 아직 전달하지 않은 엔트리 수(소비 지연)를, pending은 전달했으나 확인받지 못한 수(처리 지연)를 보여 주는 서로 다른 지표이므로 알림을 분리해 건다.

## 4. XCLAIM에서 XAUTOCLAIM으로: 회수 명령의 차이

컨슈머가 죽으면 그 컨슈머의 PEL 항목은 아무도 건드리지 않는 한 영원히 남는다. 소유권을 다른 컨슈머로 옮기는 명령이 XCLAIM이다. 다만 XCLAIM은 회수할 ID를 호출자가 미리 알아야 해서 XPENDING으로 목록을 얻은 뒤 다시 XCLAIM을 부르는 두 단계가 필요했다. 6.2에서 추가된 XAUTOCLAIM은 이 두 단계를 하나로 합쳐 "min-idle-time 이상 방치된 메시지를 start ID부터 훑어 내 것으로 가져온다"를 서버에서 처리한다.

```bash
# worker-2가, 60초 이상 방치된 pending 을 최대 10개 회수
redis-cli XAUTOCLAIM orders order-workers worker-2 60000 0-0 COUNT 10

# 응답 구조
# 1) 다음 스캔을 시작할 ID  (0-0 이면 PEL 끝까지 훑었다는 뜻)
# 2) 회수된 메시지 목록 [ID, [field, value, ...]]
# 3) PEL에는 있으나 스트림에서 이미 삭제된 ID 목록 (7.0+)
```

반환 첫 요소를 다음 호출의 start로 넘기면 PEL 전체를 커서처럼 순회할 수 있다. 값이 `0-0`으로 돌아오면 한 바퀴를 마친 것이다. COUNT를 작게 나눠 반복 호출해야 서버 블록 시간이 짧다. JUSTID를 주면 본문 없이 ID만 돌려주고 전달 횟수를 올리지 않는다.

회수 시 주의할 점은 셋이다. 회수하면 idle이 0으로 돌아가고 소유자가 바뀌므로 같은 메시지가 즉시 다시 회수되지 않는다. JUSTID가 아니면 전달 횟수가 1 증가해 재시도 카운터로 쓸 수 있다. 7.0 이상에서는 PEL에 남았지만 스트림에서 이미 삭제된 ID를 정리하고 세 번째 응답 요소로 알려 주는데, 6.2는 이 동작이 다르다.

min-idle-time 설정은 trade-off다. 너무 짧으면 처리 중인 정상 컨슈머의 메시지를 빼앗아 중복 처리가 늘고, 너무 길면 장애 복구가 그만큼 늦어진다. 기준은 "해당 작업의 정상 처리 시간 상한(예: p99)에 여유를 더한 값"이어야 한다. 이 상한은 추측하지 말고 실제 처리 시간 히스토그램에서 읽는 것이 맞다.

## 5. at-least-once 설계: 중복을 전제로 한 멱등 소비자

PEL과 XAUTOCLAIM 조합이 보장하는 것은 "ACK되기 전까지는 누군가 다시 처리한다"이다. 따라서 처리 후 ACK 직전에 프로세스가 죽거나, 느린 컨슈머의 메시지를 min-idle 경과 후 다른 컨슈머가 회수했는데 원래 컨슈머도 뒤늦게 끝내면 같은 메시지가 두 번 처리된다. 이는 at-least-once의 정의이며 소비 로직은 멱등해야 한다.

가장 단순한 멱등 키는 업무 키(orderId와 이벤트 종류의 조합)이다. DB 유니크 제약이나 Redis `SET key value NX EX`로 "이미 처리했는가"를 원자적으로 판정한다. 중요한 점은 순서다. 부수효과(DB 반영)와 중복 방지 기록이 같은 트랜잭션이어야 한다. 아래는 처리 이력 테이블(stream_key, entry_id 복합 PK의 processed_event)을 두는 예이다.

```java
@Transactional
public void handle(MapRecord<String, String, String> rec) {
    int inserted = jdbc.update(
        "INSERT INTO processed_event(stream_key, entry_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        rec.getStream(), rec.getId().getValue());   // PostgreSQL 문법
    if (inserted == 0) return;                      // 이미 처리됨
    orderService.applyPaid(Long.parseLong(rec.getValue().get("orderId")));
}
```

스트림 엔트리 ID는 프로듀서가 재시도로 XADD를 두 번 호출하면 서로 다른 ID가 되므로, 프로듀서 중복까지 막으려면 엔트리 ID가 아니라 업무 키를 멱등 키로 써야 한다. 반대로 컨슈머 쪽 재전달만 막으면 되는 경우 엔트리 ID가 가장 간단하다. 어느 쪽 중복을 막으려는지 먼저 정하고 키를 고르면 된다. ACK는 반드시 트랜잭션 커밋 이후에 호출한다. 순서를 뒤집어 ACK 후 처리하면 처리 도중 죽었을 때 메시지가 영구 유실되는 at-most-once로 퇴행한다.

## 6. 재시도 한도와 Dead Letter 스트림

계속 실패하는 poison message는 회수와 재처리를 무한 반복하며 자원을 소모한다. XPENDING의 전달 횟수가 한도(예: 5회)를 넘으면 별도 스트림 `orders:dlq`로 옮기고 원본을 ACK한다. Redis에는 DLQ 내장 기능이 없어 애플리케이션 책임이다.

클러스터에서 DLQ 이관과 ACK를 한 번에 묶으려면 두 키를 `{orders}`, `{orders}:dlq`처럼 해시 태그로 같은 슬롯에 두고 Lua나 MULTI/EXEC를 써야 한다. 번거롭다면 DLQ 적재 성공 후 ACK 순서를 지켜 유실 대신 중복을 택한다. DLQ 항목에는 원본 ID와 사유를 남겨야 원인 추적과 재주입이 가능하다.

## 7. Spring Data Redis로 구현하기

Spring Data Redis는 `StreamMessageListenerContainer`로 XREADGROUP 폴링 루프를 관리해 준다. 컨테이너는 별도 스레드에서 블로킹 읽기를 수행하고 레코드를 `StreamListener`에 전달한다. 중요한 선택은 자동 ACK 여부이다. `receiveAutoAck`는 메시지 전달과 동시에 ACK하는 모델이라 처리 중 장애 시 메시지가 유실된다. at-least-once가 목적이면 `receive`(수동 ACK)를 쓰고 처리 성공 후 직접 `acknowledge`를 호출해야 한다.

```java
@Configuration
public class StreamConfig {

    static final String STREAM = "orders";
    static final String GROUP = "order-workers";

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
            RedisConnectionFactory cf) {
        return StreamMessageListenerContainer.create(cf,
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .batchSize(10).pollTimeout(Duration.ofSeconds(2)).build());
    }

    @Bean
    public Subscription subscription(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            OrderStreamListener listener, StringRedisTemplate redis) {
        try { redis.opsForStream().createGroup(STREAM, ReadOffset.latest(), GROUP); }
        catch (RedisSystemException e) { /* BUSYGROUP: 이미 존재 */ }
        String consumerName = "worker-" + UUID.randomUUID();
        return container.receive(
                Consumer.from(GROUP, consumerName),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
                listener);
    }
}
```

스트림 키가 아직 없으면 `createGroup`이 오류를 내므로 MKSTREAM으로 키가 먼저 만들어져야 한다. 컨슈머 이름을 기동마다 랜덤으로 만들면 이전 기동의 컨슈머가 PEL을 쥔 채 사라지므로 XAUTOCLAIM 회수가 필수다. Pod 이름 같은 고정 이름은 재기동 시 자기 PEL을 `0`으로 복구할 수 있으나 동일 이름 중복 기동을 피해야 한다.

```java
@Component
public class OrderStreamListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    private final StringRedisTemplate redis;
    private final OrderHandler handler;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            handler.handle(record);                  // 멱등 처리 + DB 커밋
            redis.opsForStream().acknowledge("order-workers", record); // 커밋 이후 ACK
        } catch (Exception e) {
            // ACK 하지 않는다: PEL 에 남아 XAUTOCLAIM 대상이 된다
            log.warn("처리 실패 id={}", record.getId(), e);
        }
    }
}
```

재처리 스케줄러는 XAUTOCLAIM이 이상적이지만, 내가 확인한 범위에서 `StreamOperations`는 XPENDING(`pending`)과 XCLAIM(`claim`)만 확실히 래핑하므로(XAUTOCLAIM 메서드 유무는 사용 버전 문서로 확인 필요) 버전 의존이 낮은 두 메서드 조합을 쓴다. 저수준으로는 `RedisConnection.execute("XAUTOCLAIM", ...)`로 직접 보낼 수도 있다.

```java
@Scheduled(fixedDelay = 15_000)
public void reclaim() {
    PendingMessages pending = redis.opsForStream().pending(
            "orders", "order-workers", Range.unbounded(), 50);
    for (PendingMessage pm : pending) {
        if (pm.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(60)) < 0) continue;

        if (pm.getTotalDeliveryCount() >= 5) {
            moveToDlq(pm);                     // DLQ 적재 후 ACK
            continue;
        }
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                "orders", "order-workers", myConsumerName,
                XClaimOptions.minIdle(Duration.ofSeconds(60)).ids(pm.getId()));
        claimed.forEach(r -> reprocess(r));    // handle + ACK
    }
}
```

`XClaimOptions.minIdle`을 함께 주면 그 사이 다른 컨슈머가 먼저 회수한 메시지는 서버가 걸러 주므로 여러 인스턴스가 스케줄러를 돌려도 동시 회수는 드물다. 완전히 배제되지는 않으니 멱등 처리는 여전히 필요하다.

## 8. 스트림 크기 관리, 운영 점검, 측정 방법

XACK는 메모리를 해제하지 않으므로 XTRIM이나 XADD의 MAXLEN/MINID가 없으면 스트림은 계속 커진다. `MAXLEN ~ N`의 틸드는 radix tree 노드 단위로 근사 트리밍해 정확한 `=`보다 저렴하다. 함정은 트리밍이 PEL을 고려하지 않는다는 점이다. 미확인 엔트리도 삭제될 수 있어 PEL에는 ID만 남고 본문이 없어지므로, MAXLEN은 가장 느린 컨슈머가 밀릴 최대량보다 크게 잡거나 `MINID`로 보존 기간을 정해야 한다.

운영 점검 항목은 그룹별 lag과 pending 추이, XPENDING의 최대 idle과 최대 전달 횟수, 컨슈머별 pending 편중, DLQ 길이다. 복제는 기본적으로 비동기이고 AOF fsync 정책에 따라 장애 시 최근 쓰기가 유실될 수 있으므로 Streams는 Kafka 수준의 내구성 보장이 아니다.

성능과 회수 지연은 환경 의존이 크므로 수치를 적지 않았다(이 문서의 명령 예제는 직접 돌려 본 측정 결과가 아니다). 직접 측정하려면 프로듀서를 일정 속도로 XADD하고, 컨슈머 한 대를 처리 중 `kill -9`로 종료한 뒤, 회수 스케줄러가 남은 메시지를 모두 ACK할 때까지의 시간을 min-idle 값별로 비교한다. 중복 처리율은 처리 이력 테이블의 충돌 건수를 전체 처리 건수로 나눠 구하고, 서버 지연은 `redis-cli --latency`와 `SLOWLOG GET`으로 교차 확인한다. 

## 참고

- Redis Docs, Redis Streams: https://redis.io/docs/latest/develop/data-types/streams/
- Redis Docs, XAUTOCLAIM: https://redis.io/docs/latest/commands/xautoclaim/
- Redis Docs, XCLAIM: https://redis.io/docs/latest/commands/xclaim/
- Redis Docs, XPENDING: https://redis.io/docs/latest/commands/xpending/
- Redis Docs, XREADGROUP: https://redis.io/docs/latest/commands/xreadgroup/
- Redis Docs, XTRIM: https://redis.io/docs/latest/commands/xtrim/
- Spring Data Redis Reference, Redis Streams: https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html
