Notion 원본: https://www.notion.so/3de5a06fd6d3812d9212e059f207bbec

# Redis Cluster 슬롯 재분배와 페일오버 및 Redisson 분산락 안전성

> 2026-09-17 신규 주제 · 확장 대상: Redis 캐시 자료구조·TTL 기초 → 클러스터 운영과 분산 조정

## 학습 목표

- 16384 해시 슬롯의 소유권 이동 절차와 MIGRATING/IMPORTING 상태의 클라이언트 동작을 추적한다
- 자동 페일오버 판정 조건을 계산하고 `cluster-node-timeout` 을 근거 있게 정한다
- Redlock 알고리즘의 안전성 논쟁 지점을 짚고 Redisson 락의 실제 보장 범위를 판정한다
- 슬롯 이동 중에도 깨지지 않는 멀티키 연산 설계를 한다

## 1. 슬롯은 데이터가 아니라 소유권 단위다

Redis Cluster 는 키를 `CRC16(key) mod 16384` 로 슬롯에 매핑하고, 슬롯을 마스터 노드에 배정한다. 슬롯 수가 16384 인 이유는 클러스터 버스에서 노드가 주고받는 슬롯 비트맵 크기와 관련이 있다. 16384 비트는 2KB 라 하트비트 패킷에 매번 실어 보내도 부담이 없고, 1000 노드 규모까지 슬롯당 충분한 분할이 나온다.

해시 태그는 이 매핑에 개입하는 유일한 수단이다. 키에 `{...}` 가 있으면 중괄호 안 문자열만 CRC16 에 넣는다.

```
user:1001:profile      → CRC16("user:1001:profile") % 16384
{user:1001}:profile    → CRC16("user:1001") % 16384
{user:1001}:cart       → CRC16("user:1001") % 16384   ← 같은 슬롯
```

멀티키 명령(`MGET`, `SUNIONSTORE`, Lua 스크립트의 KEYS)은 **모든 키가 같은 슬롯**에 있어야 한다. 노드가 같아도 슬롯이 다르면 `CROSSSLOT` 오류다. 슬롯 이동 중에는 같은 노드에 있던 키가 갈라질 수 있으므로, 노드 동일성이 아니라 슬롯 동일성을 보장해야 한다는 점이 중요하다.

해시 태그 남용은 반대 문제를 만든다. `{tenant:A}` 를 모든 키에 붙이면 그 테넌트의 전체 데이터가 한 슬롯에 몰려 리샤딩으로도 분산되지 않는다. 슬롯은 분할의 최소 단위이므로 "한 슬롯에 몰린 데이터"는 영원히 한 노드에 남는다.

## 2. 재분배 절차와 클라이언트가 보는 리다이렉션

슬롯 하나를 A 에서 B 로 옮기는 절차는 다음과 같다.

```bash
# 1) 목적지 노드를 IMPORTING 으로
redis-cli -p 7001 CLUSTER SETSLOT 1234 IMPORTING <A-node-id>
# 2) 출발지 노드를 MIGRATING 으로
redis-cli -p 7000 CLUSTER SETSLOT 1234 MIGRATING <B-node-id>
# 3) 키를 배치로 옮긴다
redis-cli -p 7000 CLUSTER GETKEYSINSLOT 1234 100
redis-cli -p 7000 MIGRATE 127.0.0.1 7001 "" 0 5000 KEYS key1 key2 ...
# 4) 모든 노드에 새 소유자 통보
redis-cli -p 7000 CLUSTER SETSLOT 1234 NODE <B-node-id>
```

실무에서는 `redis-cli --cluster reshard` 가 이 절차를 감싼다. 알아둘 것은 이 과정에서 클라이언트가 받는 두 오류의 의미 차이다.

| 응답 | 의미 | 클라이언트 동작 | 캐시 갱신 |
|---|---|---|---|
| `MOVED 1234 host:port` | 슬롯 소유권이 영구히 이동됨 | 새 노드로 재시도 | 슬롯 맵 갱신 |
| `ASK 1234 host:port` | 이 키만 이미 옮겨감, 이동 진행 중 | `ASKING` 후 1회 재시도 | 갱신하지 않음 |
| `TRYAGAIN` | 멀티키 중 일부만 이동됨 | 잠시 후 재시도 | 갱신하지 않음 |
| `CLUSTERDOWN` | 슬롯 미할당 또는 정족수 붕괴 | 재시도 무의미 | — |

`ASK` 를 `MOVED` 처럼 처리해 슬롯 맵을 갱신해 버리는 클라이언트는 이동이 끝나기 전에 잘못된 라우팅을 고착시킨다. Lettuce·Redisson 등 성숙한 클라이언트는 구분하지만, 직접 구현한 래퍼나 커넥션 프록시를 끼우면 이 처리가 빠지는 경우가 있다.

`MIGRATING` 상태의 출발지 노드는 키가 존재하면 정상 응답하고, **존재하지 않으면** `ASK` 를 반환한다. 즉 아직 안 옮긴 키는 그대로 읽히고, 옮겨간 키만 리다이렉션된다. 쓰기도 마찬가지라 이동 중 무중단이 성립한다. 다만 `MIGRATE` 는 출발지에서 직렬화 → 전송 → 목적지 복원 → 출발지 삭제를 동기로 수행하므로, 큰 값(수 MB 짜리 Hash/List)이 섞여 있으면 그 시간 동안 두 노드 모두 블로킹된다. 리샤딩 전 `--bigkeys` 로 큰 키를 찾아 미리 쯪개는 것이 표준 절차다.

## 3. 페일오버 판정 — 타임아웃 값이 결정하는 것

노드 장애 판정은 두 단계다. 어떤 노드가 `cluster-node-timeout` 동안 PONG 을 못 받으면 그 노드를 **PFAIL**(possible fail)로 표시하고 가십으로 전파한다. 마스터 정족수의 과반이 같은 노드를 PFAIL 로 보고하면 **FAIL** 로 승격되고, 해당 마스터의 복제본들이 선거를 시작한다.

선거에는 지연이 붙는다. 복제본은 다음만큼 기다린 뒤 투표를 요청한다.

```
지연 = 500ms + random(0..500ms) + (복제 순위 × 1000ms)
```

복제 순위는 마스터와의 복제 오프셋 차이로 매긴 순번이다. 가장 최신인 복제본이 순위 0 이라 먼저 나서고, 뒤처진 복제본은 1초씩 밀린다. 데이터 손실을 줄이기 위한 설계다.

따라서 전체 페일오버 시간은 대략 `cluster-node-timeout + 500~1000ms + 투표 수집` 이다. `cluster-node-timeout` 이 15초(기본)면 최악 17초가량 쓰기가 실패한다. 이를 2초로 줄이면 복구는 빨라지지만 네트워크 지터나 긴 `BGSAVE` 로 인한 일시 지연에도 페일오버가 발생한다. GC 정지가 긴 환경, 클라우드 간 지연이 큰 환경에서는 오탐이 잦아진다.

`cluster-replica-validity-factor` 는 "너무 오래 마스터와 끕겨 있던 복제본은 승격 자격이 없다"는 규칙이다. 기본 10 이면 `node-timeout × 10` 이상 끕겨 있던 복제본은 선거에 나서지 않는다. 0 으로 두면 아무리 뒤처진 복제본도 승격하므로 가용성은 오르고 데이터 손실 위험은 커진다.

데이터 손실은 구조적으로 존재한다. Redis 복제는 비동기라 마스터가 클라이언트에 OK 를 응답한 뒤 복제본에 전파되기 전에 죽으면 그 쓰기는 사라진다. `WAIT numreplicas timeout` 으로 동기 확인을 강제할 수 있지만, 이는 "합의"가 아니라 "그 시점에 몇 개가 받았는지 확인"일 뿐이며 네트워크 분단 상황에서 손실을 막지 못한다.

## 4. Redlock 논쟁의 실제 쟁점

Redis 기반 분산락을 논할 때 빠지지 않는 것이 Martin Kleppmann 과 Salvatore Sanfilippo 의 2016년 논쟁이다. 쟁점을 정확히 나누면 이렇다.

**효율성(efficiency) 목적의 락**은 "중복 작업을 줄이기 위해" 쓴다. 두 워커가 같은 작업을 해도 결과가 같고 낭비만 생기는 경우다. 이때 Redis 락은 충분하다.

**정확성(correctness) 목적의 락**은 "두 워커가 동시에 들어가면 데이터가 깨지는" 경우다. 이때 Redlock 은 안전하지 않다. 이유는 두 가지다.

첫째, 타이밍 가정에 의존한다. 락 만료는 벽시계 기준인데, 워커의 GC 정지나 스케줄링 지연으로 프로세스가 멈췄다가 깨어나면 락이 이미 만료됐는데도 자신이 락을 쥐고 있다고 믿는다. 이 구간에 다른 워커가 락을 얻으면 동시 진입이다. 노드를 5개로 늘려도 이 문제는 해결되지 않는다 — 다수결은 노드 장애를 막지 시간 가정을 보강하지 않는다.

둘째, 펜싱 토큰이 없다. 안전한 락은 락을 얻을 때마다 단조 증가하는 번호를 주고, 보호 대상 자원이 "더 낮은 번호의 요청은 거부"해야 한다. Redis 락은 이 번호를 제공하지 않으며, 제공하더라도 보호 대상(예: DB, 스토리지)이 검사해 주지 않으면 무의미하다.

실무적 결론은 명확하다. 돈·재고·중복 결제처럼 정확성이 걸린 경계는 락이 아니라 **저장소 수준의 원자성**으로 지켜야 한다. DB 유니크 제약, 조건부 UPDATE(`WHERE version = ?`), `SELECT ... FOR UPDATE` 가 그 수단이다. Redis 락은 그 앞에서 부하를 줄이는 최적화로 쓴다.

## 5. Redisson 락이 실제로 보장하는 것

Redisson 의 `RLock` 은 단일 마스터(+복제본) 기준으로 동작하는 락이며, `RedissonRedLock` 은 독립된 여러 인스턴스를 묶는다. 내부 구현의 핵심은 Lua 스크립트와 워치독이다.

```java
RLock lock = redisson.getLock("order:" + orderId);

// 대기 5초, 점유 10초 — 명시적 만료
if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
    try {
        processOrder(orderId);
    } finally {
        lock.unlock();
    }
}
```

`leaseTime` 을 생략하면 워치독이 켜진다. 기본 30초 만료로 잡고 10초마다(만료의 1/3) 갱신한다. 프로세스가 살아 있는 한 락이 유지되므로 "작업이 예상보다 오래 걸려 락이 풀리는" 사고를 막는다. 반대로 프로세스가 무한 대기에 빠지면 락이 영원히 유지되므로, 작업 시간 상한이 명확하면 `leaseTime` 을 명시하는 편이 낫다.

해제는 소유권 검사를 포함한 Lua 로 이뤄진다. 이 원자성이 없으면 "만료된 남의 락을 지우는" 고전적 버그가 난다.

```lua
-- 해제 스크립트의 핵심 아이디어 (Redisson 실제 구현은 재진입 카운트 포함)
if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then
    return nil
end
local counter = redis.call('hincrby', KEYS[1], ARGV[1], -1)
if counter > 0 then
    redis.call('pexpire', KEYS[1], ARGV[2])
    return 0
else
    redis.call('del', KEYS[1])
    redis.call('publish', KEYS[2], ARGV[3])   -- 대기자 깨우기
    return 1
end
```

Hash 자료구조를 쓰는 이유는 재진입 카운트 때문이다. 필드명이 `UUID:threadId` 라 같은 스레드의 재진입은 카운트 증가로 처리된다. `publish` 는 대기 중인 클라이언트를 즉시 깨우기 위한 것으로, 폴링 대비 대기 지연을 크게 줄인다.

클러스터에서 주의할 점은 락 키와 세마포어 키가 같은 슬롯에 있어야 한다는 것이다. Redisson 은 내부적으로 관련 키에 해시 태그를 붙여 처리하지만, 사용자가 `RBatch` 로 여러 락을 묶으면 `CROSSSLOT` 이 난다.

## 6. Stream Consumer Group 과의 조합

분산 작업 분배에 락을 쓰는 대신 Stream 의 Consumer Group 을 쓰면 락 자체가 필요 없어지는 경우가 많다.

```bash
XGROUP CREATE orders:stream workers $ MKSTREAM
XREADGROUP GROUP workers worker-1 COUNT 10 BLOCK 2000 STREAMS orders:stream >
XACK orders:stream workers 1726500000000-0
```

`>` 는 "아직 아무에게도 배달되지 않은 메시지"를 뜻하고, 특정 ID 를 주면 그 컨슈머의 PEL(Pending Entries List)에서 재읽기다. 메시지는 한 컨슈머에게만 배달되므로 동시 처리 자체가 성립하지 않는다.

장애 컨슈머의 미처리 메시지는 `XAUTOCLAIM` 으로 회수한다.

```bash
XAUTOCLAIM orders:stream workers worker-2 60000 0-0 COUNT 100
```

60초 이상 ACK 되지 않은 메시지를 worker-2 가 가져간다. 여기서 "60초"가 사실상 락 만료와 같은 역할을 하며, 동일한 타이밍 가정 문제를 안고 있다. 차이는 PEL 이 배달 횟수를 기록하므로 **재배달 횟수 기반 처리**가 가능하다는 점이다. `XPENDING` 으로 delivery count 가 임계를 넘은 메시지를 DLQ 로 보내면 독약 메시지 무한 재시도를 막는다.

Stream 은 슬롯 단위로 한 노드에 있으므로 단일 Stream 의 처리량은 한 노드 상한이다. 샤딩이 필요하면 `orders:stream:{0}` ~ `{15}` 처럼 해시 태그로 분할하고 프로듀서가 키 해시로 분배한다.

## 7. 운영 지표와 진단 순서

클러스터 이상을 진단할 때 보는 순서는 다음과 같다.

```bash
redis-cli --cluster check 127.0.0.1:7000        # 슬롯 커버리지·정합성
redis-cli -p 7000 CLUSTER INFO                  # cluster_state, slots_assigned
redis-cli -p 7000 CLUSTER NODES | grep fail     # PFAIL/FAIL 노드
redis-cli -p 7000 INFO replication               # 복제 오프셋 차이
redis-cli -p 7000 INFO commandstats | sort -t= -k2 -rn | head
redis-cli -p 7000 LATENCY HISTORY command
redis-cli -p 7000 SLOWLOG GET 10
```

`cluster_state:ok` 인데 특정 키만 실패하면 슬롯 이동 중이거나 클라이언트 슬롯 캐시가 낡은 것이다. `cluster_slots_assigned` 가 16384 미만이면 미할당 슬롯이 있어 그 범위 키는 전부 실패한다. 리샤딩이 중간에 끕겼을 때 흔한 상태이며 `redis-cli --cluster fix` 로 복구한다.

복제 오프셋 차이(`master_repl_offset` 과 복제본의 `slave_repl_offset`)가 계속 벌어지면 페일오버 시 손실 구간이 그만큼이다. 복제본 쪽 `client-output-buffer-limit replica` 에 걸려 연결이 끕기는 경우가 잦으므로, 큰 쓰기가 몰리는 시스템에서는 이 값을 넣넉히 잡는다.

## 8. 설계 판단 정리

Redis Cluster 도입 판단은 데이터 크기가 아니라 **접근 패턴**으로 한다. 단일 인스턴스로 메모리가 감당되고 멀티키 연산이 많다면 클러스터는 손해다. `CROSSSLOT` 제약 때문에 애플리케이션이 복잡해지고, Lua 스크립트로 묶던 원자 연산을 쯪개야 한다. 복제본을 늘려 읽기를 분산하고 수직 확장하는 편이 낫다.

클러스터가 맞는 경우는 키 공간이 자연스럽게 분할되고(테넌트별·사용자별) 멀티키 연산이 그 분할 경계 안에서만 일어나는 구조다. 이때 해시 태그를 분할 경계로 설계하면 슬롯 이동이 애플리케이션에 투명해진다.

분산락은 마지막 수단으로 남긴다. 순서대로 (1) 저장소 원자 연산으로 해결되는지, (2) 작업 큐로 단일 소비자를 만들 수 있는지, (3) 그래도 안 되면 락. 그리고 락을 쓰기로 했다면 그 락이 효율성용인지 정확성용인지 문서에 명시해야 한다. 정확성용이라면 락만으로는 부족하다는 사실도 함께.

## 참고

- Redis Cluster Specification — https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/
- Redis, "Distributed Locks with Redis" — https://redis.io/docs/latest/develop/use-cases/patterns/distributed-locks/
- Martin Kleppmann, "How to do distributed locking" — https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- Redisson Wiki, Distributed locks and synchronizers — https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers
- Redis Streams 소개 — https://redis.io/docs/latest/develop/data-types/streams/
- Martin Kleppmann, *Designing Data-Intensive Applications*, 8~9장
