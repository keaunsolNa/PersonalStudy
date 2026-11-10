Notion 원본: https://www.notion.so/4145a06fd6d381328fa5fee475634522

# Redis Streams Consumer Group: PEL과 XAUTOCLAIM 장애 복구, Spring Data Redis 소비자 설계

> 2026-11-10 신규 주제 · 확장 대상: Redis (캐시 이후 메시징)

## 학습 목표

- Stream 과 Consumer Group 의 ID 체계, `XADD`/`XREADGROUP`/`XACK` 흐름을 구성한다
- PEL(Pending Entries List)로 미처리 메시지를 추적하고 `XPENDING` 으로 진단한다
- `XAUTOCLAIM` 으로 죽은 컨슈머의 메시지를 인계하고 전달 횟수로 DLQ 를 구현한다
- Spring Data Redis 로 소비자를 구성하고 at-least-once 에 맞는 멱등 처리를 설계한다

## 1. Stream 과 Consumer Group

Redis Stream 은 append-only 로그다. 엔트리 ID 는 `<밀리초 타임스탬프>-<시퀀스>` 이며 `XADD` 의 `*` 로 자동 생성된다. Consumer Group 은 스트림에 대한 **읽기 커서(last-delivered-id)와 컨슈머별 pending 목록**을 서버에 보관하는 단위다. Pub/Sub 과 달리 메시지가 저장되므로 컨슈머가 죽어도 유실되지 않고, 같은 그룹 안의 컨슈머들은 메시지를 **분배**받는다(한 메시지는 그룹 내 한 컨슈머에게만 전달). 서로 다른 그룹은 같은 메시지를 각각 받는다.

```
XGROUP CREATE orders:stream order-workers $ MKSTREAM
XADD orders:stream * orderId 1001 amount 5000
XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 2000 STREAMS orders:stream >
XACK orders:stream order-workers 1731234567890-0
```

`>` 는 "이 그룹에 아직 전달되지 않은 새 메시지"를 뜻한다. `$` 로 그룹을 만들면 생성 이후 메시지만, `0` 으로 만들면 처음부터 읽는다. 컨슈머는 **처음 사용될 때 자동 생성**된다.

## 2. PEL: 전달됐지만 ACK 되지 않은 메시지

`XREADGROUP` 으로 메시지를 받는 순간 해당 엔트리는 그 컨슈머의 **PEL** 에 들어간다. `XACK` 하면 PEL 에서 제거된다. 컨슈머가 처리 중 죽으면 엔트리는 PEL 에 남아 있다. 이것이 at-least-once 의 근거다: ACK 전에 죽으면 반드시 다시 처리할 기회가 생기고, 그만큼 **중복 처리**가 가능하다.

```
XPENDING orders:stream order-workers                 # 요약: 총 개수, 최소/최대 ID, 컨슈머별 개수
XPENDING orders:stream order-workers IDLE 60000 - + 10   # 60초 이상 idle 한 항목 상세
```

상세 출력에는 엔트리 ID, 소유 컨슈머, **idle 시간(ms)**, **delivery count** 가 포함된다. delivery count 가 계속 오르는 메시지는 처리 로직 자체가 실패하는 "독약 메시지(poison message)"일 가능성이 높다.

컨슈머는 재시작 후 `XREADGROUP ... STREAMS orders:stream 0` (ID 에 `>` 대신 `0`)로 **자신의 PEL** 을 먼저 읽어 이전에 처리하지 못한 메시지를 재처리하는 것이 표준 패턴이다.

## 3. XAUTOCLAIM 으로 소유권 이전

컨슈머가 영구히 사라지면 그 PEL 의 메시지는 아무도 읽지 않는다. 다른 컨슈머가 `XCLAIM` 으로 소유권을 가져올 수 있는데, Redis 6.2 에서 추가된 `XAUTOCLAIM` 은 PEL 을 스캔하며 idle 기준을 넘는 항목을 한 번에 인계한다.

```
XAUTOCLAIM orders:stream order-workers worker-2 60000 0-0 COUNT 20
```

응답은 `[다음 커서 ID, 인계된 엔트리들, 삭제된 ID 목록(7.0+)]` 이다. 다음 호출에 반환된 커서를 시작 ID 로 넘겨 반복하고, 커서가 `0-0` 으로 돌아오면 한 바퀴를 마친 것이다. 인계되면 해당 엔트리의 delivery count 가 증가하고 idle 이 초기화된다. `JUSTID` 옵션은 본문 없이 ID 만 받는다. idle 임계값은 **정상 처리 시간의 최댓값보다 충분히 크게** 잡아야 한다. 처리가 느린 컨슈머의 메시지를 다른 컨슈머가 가로채 중복 처리하는 사고를 피하기 위해서다.

## 4. DLQ 와 멱등성

delivery count 가 임계값(예: 5)을 넘으면 별도 스트림(`orders:dlq`)으로 옮기고 ACK 한다.

```
XRANGE orders:stream 1731234567890-0 1731234567890-0       # 본문 조회
XADD orders:dlq * origId 1731234567890-0 reason "max-retries" orderId 1001
XACK orders:stream order-workers 1731234567890-0
```

at-least-once 이므로 소비 로직은 멱등이어야 한다. 주문 ID 를 키로 DB 유니크 제약을 걸거나, 처리 이력을 `SET processed:{orderId} 1 NX EX 86400` 로 선점하는 방식이 흔하다. 단 "선점 후 처리 실패"가 남지 않도록 DB 트랜잭션 안에서 이력 INSERT 와 비즈니스 쓰기를 함께 커밋하는 쪽이 정확하다. Redis 와 DB 는 하나의 트랜잭션이 아니므로 exactly-once 는 보장되지 않는다.

## 5. Spring Data Redis 소비자

Spring Data Redis 는 `StreamMessageListenerContainer` 로 폴링 소비자를 제공한다.

```java
@Bean
StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
        RedisConnectionFactory cf, OrderStreamListener listener) {
    var opts = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
        .builder()
        .pollTimeout(Duration.ofSeconds(2))
        .batchSize(10)
        .build();
    var container = StreamMessageListenerContainer.create(cf, opts);
    container.receive(
        Consumer.from("order-workers", "worker-" + hostname()),
        StreamOffset.create("orders:stream", ReadOffset.lastConsumed()),
        listener);
    container.start();
    return container;
}

@Component
class OrderStreamListener implements StreamListener<String, MapRecord<String, String, String>> {
    private final StringRedisTemplate redis;
    private final OrderService service;

    public void onMessage(MapRecord<String, String, String> record) {
        service.handleIdempotently(record.getValue());
        redis.opsForStream().acknowledge("order-workers", record);   // 성공 후 ACK
    }
}
```

`receive` 는 기본적으로 자동 ACK 하지 않는 `receive`(수동)와 `receiveAutoAck` 로 나뉜다. 위 형태는 수동 ACK 이므로 예외가 나면 ACK 되지 않아 PEL 에 남고 이후 재처리 대상이 된다. 리스너 안에서 예외를 삼키지 말고, 별도 스케줄러에서 `XAUTOCLAIM`(`opsForStream().claim(...)`) 또는 PEL 재조회로 복구 작업을 돌린다. 컨슈머 이름은 재시작 후에도 같은 이름을 쓰면 자신의 PEL 을 이어받고, 매번 새 이름을 쓰면 옛 이름의 PEL 이 방치되므로 `XAUTOCLAIM` 이 필수가 된다.

## 6. 운영 고려

스트림은 무한 증가하므로 `XADD ... MAXLEN ~ 100000`(근사 트리밍)이나 `XTRIM` 으로 길이를 제한한다. 단 PEL 에 남은 엔트리가 트리밍으로 지워질 수 있어(본문이 사라져 `XAUTOCLAIM` 응답의 삭제 ID 목록에 나타남) 보존 기간을 처리 지연 최대치보다 넉넉히 둔다. `XINFO GROUPS`, `XINFO CONSUMERS` 의 `pending`, `lag`(7.0+) 를 모니터링 지표로 삼는다. Cluster 에서는 스트림이 단일 키이므로 하나의 슬롯(샤드)에 몰린다. 처리량이 한계면 키를 `orders:stream:{0..n}` 처럼 샤딩해 컨슈머를 나눈다.

## 7. trade-off

Kafka 와 비교하면 설정과 운영이 가볍고 지연이 낮지만, 디스크 기반 장기 보존·파티션 리밸런싱·exactly-once 트랜잭션은 제공하지 않는다. 메모리에 상주하므로 보존량이 곧 비용이다. 이미 Redis 를 운영 중이고 유실 허용 범위가 명확한 작업 큐(알림, 비동기 후처리) 에 적합하다.

## 참고

- Redis Docs: Redis Streams (Consumer groups, Pending entries list)
- Redis Command Reference: XAUTOCLAIM, XPENDING, XREADGROUP, XACK
- Spring Data Redis Reference: Redis Streams (StreamMessageListenerContainer)
