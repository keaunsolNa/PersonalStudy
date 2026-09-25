Notion 원본: https://app.notion.com/p/3e65a06fd6d381bf92cbcca00a0c402f

# Redisson 분산 락 구현 원리와 Watchdog 및 페일오버 시 이중 획득 방지

> 2026-09-25 신규 주제 · 확장 대상: Redis

## 학습 목표

- `RLock`의 락 획득/해제가 Lua 스크립트로 원자화되는 방식을 설명한다
- Watchdog의 자동 갱신(lease renewal) 메커니즘과 그것이 필요한 이유를 구분한다
- 마스터-슬레이브 페일오버 상황에서 락이 이중으로 획득될 수 있는 조건을 식별한다
- RedLock 알고리즘의 전제와 Redisson `MultiLock`/`RedissonRedLock` 적용 시 트레이드오프를 판단한다

## 1. RLock의 기본 구조와 Lua 스크립트

Redisson의 `RLock`은 단순 `SETNX`가 아니라 Redis Hash 자료구조와 Lua 스크립트를 조합해 **재진입 가능한(reentrant)** 락을 구현한다. 락 하나는 Redis에서 다음과 같은 Hash로 표현된다.

```
KEY: "my-lock"
FIELD: "<clientId>:<threadId>"   (예: "8f3a...:1")
VALUE: <재진입 카운트>
```

락 획득 시 실행되는 Lua 스크립트(단순화된 형태)는 대략 다음과 같다.

```lua
-- KEYS[1]: 락 키, ARGV[1]: 만료시간(ms), ARGV[2]: 락 소유자 식별자
if (redis.call('exists', KEYS[1]) == 0) then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil                       -- 획득 성공
end
if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil                       -- 재진입 성공(같은 소유자)
end
return redis.call('pttl', KEYS[1])   -- 획득 실패, 남은 TTL 반환
```

이 스크립트가 원자적으로 실행되기 때문에 "락이 없는지 확인 후 설정하는" 사이의 레이스 컨디션이 존재하지 않는다. 재진입 카운트를 Hash Field에 저장하므로, 같은 스레드가 동일 락을 중첩 획득(`lock() -> lock() -> unlock() -> unlock()`)해도 첫 `unlock()`에서 즉시 풀리지 않고 카운트가 0이 될 때만 실제로 해제된다.

```java
RLock lock = redissonClient.getLock("order:lock:" + orderId);
lock.lock();               // 재진입 카운트 1
try {
    lock.lock();            // 재진입 카운트 2 (같은 스레드)
    try {
        processOrder(orderId);
    } finally {
        lock.unlock();       // 재진입 카운트 1, 아직 해제 안 됨
    }
} finally {
    lock.unlock();           // 재진입 카운트 0, 실제 해제
}
```

## 2. Watchdog: 자동 만료 갱신

분산 락에서 가장 어려운 문제 중 하나는 "락을 획득한 프로세스가 죽었을 때 락이 영원히 풀리지 않는 것"과 "락을 획득한 프로세스가 살아있는데 TTL이 만료되어 다른 프로세스가 동시에 락을 얻는 것" 사이의 균형이다. Redisson은 이를 **Watchdog**으로 해결한다.

`lock()`을 leaseTime 없이 호출하면 Redisson은 기본 30초(`lockWatchdogTimeout`)의 TTL로 락을 설정하고, 내부적으로 별도 스케줄러가 TTL의 1/3 지점마다(기본 10초 간격) 해당 락을 보유 중인 클라이언트가 살아있는 한 `pexpire`로 TTL을 갱신한다. 클라이언트 프로세스가 비정상 종료되면 이 갱신 스레드도 함께 사라지므로, 최대 30초 후 락이 자연 만료되어 다른 프로세스가 획득할 수 있게 된다.

```java
// leaseTime 미지정: Watchdog이 자동으로 TTL을 갱신
RLock lock = redissonClient.getLock("order:lock:" + orderId);
lock.lock();
try {
    longRunningTask(); // 30초 넘어도 걸려도 Watchdog이 TTL을 계속 연장
} finally {
    lock.unlock();
}
```

```java
// leaseTime 명시 지정: Watchdog이 개입하지 않음
lock.lock(10, TimeUnit.SECONDS); // 10초 후 무조건 만료, 갱신 없음
```

`leaseTime`을 명시적으로 지정하면 Watchdog은 동작하지 않는다. 이는 "작업이 예상보다 오래 걸려도 락을 붙들고 있으면 안 되는" 배치성 작업에는 유용하지만, 작업 시간이 leaseTime을 초과하면 락이 해제된 상태에서 계속 작업이 진행되는 **락 없는 임계 구역 진입**이 발생할 수 있다는 점을 반드시 인지해야 한다. 일반적인 애플리케이션 락은 leaseTime을 지정하지 않고 Watchdog에 맡기는 것이 안전하다.

## 3. 페일오버 상황에서의 이중 획득 문제

Redisson의 Watchdog은 "프로세스가 죽으면 락이 풀린다"는 문제는 해결하지만, **Redis 자체의 마스터-슬레이브 복제 지연**에서 오는 문제는 해결하지 못한다. 다음 시나리오를 보자.

1. 클라이언트 A가 마스터에 락을 획득한다(`SET lock A NX PX 30000`).
2. 마스터가 이 쓰기를 슬레이브로 복제하기 전에 마스터가 다운된다.
3. Redis Sentinel 또는 Cluster가 슬레이브를 새 마스터로 승격시킸다.
4. 새 마스터에는 A가 획득한 락 정보가 없다.
5. 클라이언트 B가 같은 키에 락을 요청하면 **성공**한다.
6. 이제 A와 B가 동시에 같은 락을 보유한 것처럼 착각하는 상태가 된다.

이는 Redis 복제가 **비동기(async)**이기 때문에 발생하는 근본적인 한계이며, Redisson뿐 아니라 Redis 기반의 모든 단일 인스턴스/단일 마스터 락 구현에 공통되는 문제다. 이 문제는 "Redis 자체 장애"가 드물게 발생하는 조건에서만 나타나므로, 락으로 보호하는 자원의 중요도에 따라 추가 방어가 필요한지 판단해야 한다.

## 4. RedLock 알고리즘과 Redisson의 MultiLock

Redis 공식 문서와 Redisson은 이 문제에 대한 완화책으로 **RedLock** 알고리즘을 제안한다. 핵심 아이디어는 서로 복제 관계가 없는 N개(통상 5개)의 독립된 Redis 마스터에 각각 락 획득을 시도하고, **과반수(N/2+1)**에서 성공하며 그 성공까지 걸린 총 시간이 락의 유효시간보다 충분히 짧을 때만 락 획득으로 인정하는 것이다.

```java
RLock lock1 = redissonClient1.getLock("order:lock:" + orderId);
RLock lock2 = redissonClient2.getLock("order:lock:" + orderId);
RLock lock3 = redissonClient3.getLock("order:lock:" + orderId);

RedissonMultiLock redLock = new RedissonRedLock(lock1, lock2, lock3);
redLock.lock();
try {
    processOrder(orderId);
} finally {
    redLock.unlock();
}
```

과반수 이상의 독립된 노드에서 동시에 락을 획득해야 하므로, 위 3-6단계 시나리오처럼 단일 노드의 페일오버만으로는 다른 클라이언트가 과반수를 다시 획득하기 어렵다. 다만 RedLock은 논쟁의 여지가 있는 설계다. Martin Kleppmann은 "RedLock은 프로세스 정지(GC pause)나 네트워크 지연으로 인한 펜싱 토큰 부재 문제를 근본적으로 해결하지 못하며, 분산 락을 '효율성'(성능 최적화, 중복 작업 방지) 목적이 아니라 '정합성'(correctness) 보장 목적으로 쓰는 것 자체가 위험하다"고 지적했다. 반면 Redis 저자 Salvatore Sanfilippo는 이에 반박하며 RedLock이 실용적으로 충분히 안전하다고 주장했다. 이 논쟁의 실무적 시사점은, **락이 실패했을 때 발생하는 피해가 재고 차감 오류처럼 되돌릴 수 없는 것이라면 Redis 락만으로 정합성을 보장하려 하지 말고, DB 유니크 제약이나 낙관적 락(버전 컴럼) 같은 최종 방어선을 함께 두어야 한다**는 것이다.

## 5. 펜싱 토큰(Fencing Token) 패턴

RedLock 없이도 부분적으로 이중 획득 위험을 완화하는 방법이 펜싱 토큰이다. 락을 획득할 때마다 단조 증가하는 토큰(예: Redis `INCR`로 발급)을 함께 발급받고, 보호 대상 자원(DB, 파일 등)에 쓰기 작업을 수행할 때 "지금 내가 가진 토큰이 최신인지"를 자원 쪽에서 검증하게 만드는 방식이다.

```java
long token = redissonClient.getAtomicLong("order:lock:token:" + orderId).incrementAndGet();
lock.lock();
try {
    // UPDATE 쿼리에 토큰 조건을 추가해, 더 최신 토큰을 가진 클라이언트의 쓰기만 반영되게 함
    int updated = jdbcTemplate.update(
        "UPDATE order_lock_state SET status = ?, fencing_token = ? " +
        "WHERE order_id = ? AND fencing_token < ?",
        "PROCESSED", token, orderId, token);
    if (updated == 0) {
        throw new StaleLockException("더 최신 토큰이 이미 처리함");
    }
} finally {
    lock.unlock();
}
```

이 방식은 락 자체의 정합성이 아니라 **자원 쪽에서 순서를 최종 검증**하기 때문에, 페일오버로 인한 이중 획득이 발생하더라도 뒤늬게 도착한 쓰기가 자동으로 거부된다. RedLock보다 구현 복잡도는 있지만 정합성 보장 수준은 더 확실하다.

## 6. 락 종류별 성능/보장 수준 비교

| 방식 | 장애 대응 범위 | 정합성 보장 수준 | 추가 인프라 비용 |
|---|---|---|---|
| 단일 Redis + RLock (Watchdog) | 클라이언트 프로세스 장애 | 낮음(마스터 페일오버 시 취약) | 없음 |
| RedLock (5노드 과반수) | 단일 노드 장애 + 일부 완화 | 중간(논쟁적) | Redis 노드 5개 운영 |
| RLock + 펜싱 토큰 | 클라이언트 장애 + 페일오버 | 높음(자원 쪽 최종 검증) | DB 컬럼 추가 정도 |
| DB 유니크 제약/낙관적 락만 사용 | 해당 없음(락 불필요) | 매우 높음 | 없음(단, 처리량 낮을 수 있음) |

## 7. tryLock과 대기 큐 동작

`RLock.tryLock(waitTime, leaseTime, unit)`은 즉시 실패하지 않고 `waitTime` 동안 락 획득을 재시도한다. 이때 Redisson은 무작정 폴링하지 않고 **Redis Pub/Sub 채널**을 활용한다. 락 해제 시(`unlock()`) 해당 락 전용 채널로 메시지를 발행하고, 대기 중인 클라이언트는 이 채널을 구독하고 있다가 메시지를 받는 즉시 재시도한다. 이는 순수 폴링 방식보다 Redis 부하와 락 획득 지연을 모두 줄이는 설계다.

```java
boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
if (!acquired) {
    throw new LockAcquisitionTimeoutException("5초 내 락 획득 실패: " + orderId);
}
try {
    processOrder(orderId);
} finally {
    lock.unlock();
}
```

`tryLock`을 쓸 때 흔한 실수는 `finally`에서 `unlock()`을 호출하기 전에 **현재 스레드가 실제로 락을 보유하고 있는지** 확인하지 않는 것이다. `acquired`가 `false`인 경로에서 `unlock()`을 호출하면 `IllegalMonitorStateException`이 발생하므로, 반드시 획득 성공 여부에 따라 `unlock()` 호출을 분기해야 한다.

## 8. 실무 설계 가이드

Redisson 락을 도입할 때는 우선 "이 락이 지키려는 것이 성능 최적화(중복 작업 방지)인지, 데이터 정합성인지"를 먼저 구분해야 한다. 단순히 스케줄러의 중복 실행 방지(여러 인스턴스 중 한 곳만 배치를 실행) 같은 용도라면 기본 `RLock` + Watchdog만으로 충분하다. 반면 재고 차감, 포인트 적립처럼 이중 처리가 금전적 손실로 이어지는 경우에는 락과 별개로 DB 유니크 제약이나 버전 기반 낙관적 락을 반드시 병행하고, 가능하다면 펜싱 토큰까지 적용해 락의 실패를 자원 쪽에서 한 번 더 거르는 이중 방어 구조를 갖추는 것이 안전하다. 또한 leaseTime을 명시적으로 지정할 때는 해당 임계 구역의 실제 실행 시간을 P99 기준으로 측정해, leaseTime이 이를 충분히 여유 있게 초과하도록 설정해야 Watchdog 없이도 안전하게 동작한다.

## 9. 모니터링과 장애 탐지

Redisson 락을 운영에 투입한 뒤에는 락 획득 대기 시간과 실패율을 별도로 계측해야 한다. `tryLock`이 `waitTime` 안에 실패하는 비율이 겑자기 증가한다면 특정 키에 경합이 몰리고 있다는 신호이며, 이는 청크 크기를 줄이거나 락의 범위를 더 세밀한 단위(예: 주문 단위가 아니라 주문-상품 단위)로 쪼개야 한다는 힌트가 된다. 또한 Redisson 클라이언트는 `org.redisson.pubsub` 관련 로거에 재연결 이벤트를 기록하므로, Redis 마스터 페일오버가 발생한 시점과 락 이상 동작 의심 시점을 교차 확인하는 용도로 활용할 수 있다. 운영 대시보드에는 락 보유 시간의 분포(P50/P99)와 Watchdog 갱신 실패 횟수를 함께 노출해, "락은 잡았지만 갱신이 안 되고 있는" 상황을 조기에 감지하는 것이 바람직하다.

Redis 자체의 `INFO replication` 출력에서 `master_repl_offset`과 슬레이브의 `slave_repl_offset` 차이(복제 지연)를 주기적으로 확인하는 것도 유효하다. 이 차이가 크게 벌어지는 구간에서 페일오버가 발생하면 3절에서 설명한 이중 획득 시나리오가 실제로 발생할 확률이 높아지므로, 복제 지연이 임계치를 넘으면 알림을 울리는 것만으로도 위험 구간을 사전에 인지할 수 있다.

## 참고

- Redisson Wiki, "Distributed locks and synchronizers"
- Redis 공식 문서, "Distributed Locks with Redis" (RedLock 알고리즘)
- Martin Kleppmann, "How to do distributed locking" (RedLock 비판 아티클)
- Salvatore Sanfilippo, "Is Redlock safe?" (RedLock 반박 아티클)
