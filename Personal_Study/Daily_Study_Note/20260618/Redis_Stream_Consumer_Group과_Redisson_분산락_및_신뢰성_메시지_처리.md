Notion 원본: https://www.notion.so/3835a06fd6d38110a1b0c410971af118

# Redis Stream Consumer Group과 Redisson 분산락 및 신뢰성 메시지 처리

> 2026-06-18 신규 주제 · 확장 대상: Redis

## 학습 목표

- Redis Stream 의 엔트리 ID·소비자 그룹·PEL 구조로 at-least-once 소비를 설계한다
- `XREADGROUP`·`XACK`·`XCLAIM`·`XAUTOCLAIM` 으로 장애 컨슈머의 미처리 메시지를 회수한다
- Redisson `RLock` 의 watchdog 갱신과 Pub/Sub 기반 대기 메커니즘을 코드로 이해한다
- Stream 과 Pub/Sub, List 기반 큐의 신뢰성·성능 트레이드오프를 구분한다

## 1. Stream 의 자료구조: append-only 로그

Redis Stream 은 5.0 에서 도입된 append-only 로그형 자료구조다. 각 엔트리는 `<ms>-<seq>` 형식의 단조 증가 ID 를 가진다.

```bash
XADD orders '*' orderId 1001 amount 50000
XLEN orders
XRANGE orders - +
```

Stream 은 List 기반 큐와 달리 소비해도 데이터가 사라지지 않는다. `XADD orders MAXLEN ~ 100000 '*' ...` 로 근사 트리밍한다.

## 2. Consumer Group 과 PEL

```bash
XGROUP CREATE orders order-workers $ MKSTREAM
XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 2000 STREAMS orders '>'
```

`>` 는 그룹이 아직 배달하지 않은 새 메시지다. 읽은 메시지는 PEL 에 등록되며 `XACK` 로 제거해야 한다. ACK 전 크래시하면 PEL 에 남아 회수 대상이 된다 — at-least-once 보장의 핵심이다.

## 3. 장애 컨슈머 회수: XCLAIM 과 XAUTOCLAIM

```bash
XPENDING orders order-workers IDLE 300000 - + 10
XCLAIM orders order-workers worker-2 60000 1718700000000-0
XAUTOCLAIM orders order-workers worker-2 60000 0 COUNT 25
```

delivery count 가 임계치를 넘는 poison message 는 dead-letter stream 으로 `XADD` 후 `XACK` 하여 무한 재처리를 끊는다.

## 4. 신뢰성 등급 비교: Pub/Sub vs Stream vs List

Pub/Sub 은 at-most-once, List 큐는 BRPOP 이 꺼내는 순간 제거(유실 위험), Stream+Group 은 PEL·ACK 로 at-least-once 를 구조적으로 보장한다.

| 방식 | 전달 보장 | 다중 소비 | 적합 |
|---|---|---|---|
| Pub/Sub | at-most-once | fan-out | 실시간 알림 |
| List 큐 | 최약 | 경쟁 소비 | 단순 작업 큐 |
| Stream+Group | at-least-once | 그룹별 분담 | 주문 처리·이벤트 소싱 |

## 5. Redisson 분산락의 내부

```java
RLock lock = redisson.getLock("stock:1001");
lock.lock();
try { decreaseStock(1001); } finally { lock.unlock(); }
boolean ok = lock.tryLock(5, 10, TimeUnit.SECONDS);
```

락은 Redis Hash 로 저장되며 Lua 스크립트로 원자적 증감, unlock 시 Pub/Sub 채널로 대기 노드를 깨운다.

## 6. Watchdog 와 leaseTime 트레이드오프

leaseTime 없이 `lock()` 하면 watchdog 가 30초 임대를 걸고 10초마다 갱신한다. GC 로 장시간 정지하면 락이 풀릴 수 있어 single Redis 에서 완전한 상호 배제를 보장하지 못한다(Redlock 비판). 정합성이 절대적인 자원은 DB 낙관적 락으로 최종 방어한다.

## 7. Cluster 슬롯과 Stream 키 배치

Cluster 는 `CRC16(key) % 16384` 로 슬롯을 정한다. Stream 도 단일 슬롯에 올라가 수평 분산되지 않으므로 해시 태그로 샤딩한다.

```bash
XADD 'orders:{shard3}' '*' orderId 1001
```

리샤딩 중에는 MOVED/ASK 리다이렉트가 발생하므로 cluster-aware 클라이언트를 쓴다.

## 8. 멱등 소비와 정확히 한 번 효과

```python
def consume(mid, fields):
    oid = fields[b"orderId"].decode()
    if not r.set(f"processed:{oid}", mid, nx=True, ex=86400):
        r.xack("orders", GROUP, mid); return
    process(fields); r.xack("orders", GROUP, mid)
```

또는 DB 유니크 제약으로 중복 INSERT 를 거부하게 한다.

## 9. 통합 운영 패턴과 모니터링

주문 이벤트를 Stream 으로 흘리고 컨슈머 안에서 주문 단위 RLock 을 건다. `XINFO GROUPS orders` 로 lag 과 PEL 크기를 모니터링한다. MAXLEN 트리밍이 소비보다 느리면 메모리가 무한 증가한다. Sentinel/Cluster 와 AOF `appendfsync everysec` 로 영속성을 확보한다.

## 참고

- Redis 공식 — Redis Streams (https://redis.io/docs/latest/develop/data-types/streams/)
- Redis 명령어 — XREADGROUP, XCLAIM, XAUTOCLAIM (https://redis.io/commands/)
- Redisson Wiki — Distributed locks (https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers)
- Martin Kleppmann — How to do distributed locking (https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)
