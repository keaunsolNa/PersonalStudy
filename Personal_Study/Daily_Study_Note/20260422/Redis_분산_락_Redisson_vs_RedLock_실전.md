Notion 원본: https://www.notion.so/34a5a06fd6d38173bb66f2bbd58bee6b

# Redis 분산 락 실전 — Redisson vs RedLock vs SETNX

> 2026-04-22 신규 주제 · 확장 대상: Redis (캐시 중심 → 동시성 제어 심화)

## 학습 목표

- 단일 노드 `SET NX PX` 기반 락의 정확한 원자성 경계와 Lua 스크립트로 unlock할 때의 토큰 검증 이유를 설명한다
- Redisson `RLock`의 Pub/Sub 대기와 Watchdog 갱신 메커니즘을 코드와 RESP 명령 레벨에서 추적한다
- RedLock 알고리즘의 전제(클럭 드리프트, 과반수 쿼럼)와 Kleppmann의 반박을 비교해 **언제 쓰고 언제 쓰면 안 되는지** 판단 기준을 세운다
- Fencing Token 패턴으로 GC Pause/네트워크 파티션 중 이중 점유 문제를 보완하고, Spring Boot에서 AOP로 선언형 분산 락을 적용한다

---

## 1. 왜 DB 행 잠금이 아니라 분산 락인가

동시성 제어의 첫 번째 선택지는 언제나 **DB 행 잠금**(`SELECT ... FOR UPDATE`)이다. 트랜잭션 경계 안에서만 유효하고, 커밋/롤백과 자연스럽게 묶이며, Fencing Token을 별도로 관리할 필요가 없다. 대부분의 재고 차감/포인트 차감 문제는 이걸로 충분하다.

분산 락이 필요한 경우는 다음 중 하나다.

- **트랜잭션 경계를 넘어서는 작업**: 외부 API 호출(결제 PG 승인), 파일 업로드, 메시지 발행 같은 비트랜잭셔널 작업을 **단 한 번만** 실행해야 할 때. DB 트랜잭션은 롤백이 가능하지만 "이미 보낸 결제 요청"은 되돌릴 수 없다.
- **여러 데이터 소스 교차 갱신**: 서로 다른 DB나 외부 시스템을 동시에 업데이트해야 해서 DB 락만으로는 커버되지 않을 때.
- **DB 부하 분산**: 락 경쟁이 너무 심해 RDB의 락 큐가 포화되는 경우. 이때는 락을 Redis로 오프로드한다.
- **스케줄러 중복 실행 방지**: 여러 인스턴스에서 뜨는 cron 잡을 하나만 실행시키는 leader election 용도.

이 기준에 부합하지 않으면 **DB 락이 거의 항상 더 안전하고 단순하다**. 분산 락은 Fencing·시간·장애 복구 등 고려할 게 많아 난이도가 몇 배 올라간다.

## 2. SET NX PX — 원자적 락 획득의 기본

Redis 2.6.12부터 `SET`이 `NX`(없을 때만)와 `PX`(ms 단위 TTL) 옵션을 동시에 받게 되면서 단일 명령으로 락 획득이 원자적이 됐다. 그 이전의 `SETNX` + `EXPIRE` 조합은 두 명령 사이에서 프로세스가 죽으면 TTL 없는 락이 영구 남는 유명한 버그가 있었다.

```
SET lock:order:1234 <randomUUID> NX PX 30000
→ OK      (획득 성공)
→ (nil)   (이미 누가 잡고 있음)
```

여기서 **value에 반드시 랜덤 UUID를 넣는 이유**가 실전 핵심이다. unlock 시 `DEL lock:order:1234`를 그냥 실행하면 다음 시나리오에서 남의 락을 풀게 된다.

1. 프로세스 A가 락을 잡고(TTL 30초) 무거운 작업을 32초 돈다
2. TTL 만료로 락이 자동 해제
3. 프로세스 B가 같은 키로 락을 새로 잡음
4. 뒤늦게 A의 작업이 끝나고 `DEL`을 호출 → **B의 락이 풀려버림**

해결은 **unlock도 원자적 토큰 검증**으로 묶는 Lua 스크립트다.

```lua
-- unlock.lua
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("DEL", KEYS[1])
else
  return 0
end
```

```
EVAL <unlock.lua> 1 lock:order:1234 <원래 잡을 때 넣은 UUID>
```

Lua 스크립트는 Redis 서버에서 **원자적으로** 실행되므로 GET과 DEL 사이에 다른 명령이 끼어들지 않는다. 이것이 분산 락의 최소 필수 패턴이다.

## 3. Redisson RLock — 구현을 보지 않으면 쓰지 마라

Redisson은 `RLock`이라는 Java 친화적 분산 락 API를 제공한다. 내부적으로 Lua 스크립트와 Pub/Sub을 조합해 SETNX보다 세 가지 면에서 강하다.

**재진입(reentrancy)**. 같은 스레드가 같은 락을 여러 번 잡아도 허용한다. Redis value에 단순 UUID가 아니라 **해시 맵**을 써서 `{threadId: count}` 형태로 카운트한다. unlock은 카운트를 1 감소시키고, 0이 되면 DEL한다.

**Pub/Sub 기반 대기**. 락이 이미 잡혀 있을 때 `tryLock(waitTime, leaseTime)`은 바쁜 폴링(busy polling) 대신 `SUBSCRIBE redisson_lock__channel:...`로 락 해제 통지를 기다린다. 대기 중인 클라이언트가 많아도 Redis CPU가 덜 튀고 latency가 균일해진다.

**Watchdog 자동 갱신**. `lock()`에 leaseTime을 주지 않으면 기본 30초 TTL로 락을 걸되, Redisson 내부 `ScheduledExecutor`가 **10초마다 TTL을 30초로 다시 연장**한다. 작업이 길어지더라도 TTL 만료로 락이 빠질 걱정이 없다. 프로세스가 죽으면 스케줄러도 죽으므로 30초 뒤 자동 해제되는 안전 장치는 여전히 작동한다.

```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redisson() {
        Config cfg = new Config();
        cfg.useSingleServer()
           .setAddress("redis://prod-redis:6379")
           .setConnectionPoolSize(64)
           .setConnectionMinimumIdleSize(16);
        return Redisson.create(cfg);
    }
}

// 사용
RLock lock = redisson.getLock("order:" + orderId);
try {
    // waitTime=3s, leaseTime=-1(Watchdog 사용)
    if (!lock.tryLock(3, -1, TimeUnit.SECONDS)) {
        throw new LockAcquireFailedException();
    }
    // 임계 구역
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

**주의**: `leaseTime`을 명시하면 Watchdog이 동작하지 않는다. 즉 "leaseTime=10초"라고 주는 순간 10초 뒤 TTL 만료로 락이 빠진다. 장시간 작업이라면 **leaseTime을 생략**하고 Watchdog에 맡기거나, 진행률에 맞춰 직접 `lock.updateWatchdog()`을 호출한다.

## 4. RedLock — 유명하지만 논쟁적인 알고리즘

Antirez(Redis 개발자 Salvatore Sanfilippo)가 제안한 RedLock은 **여러 개의 독립 Redis 마스터**(일반적으로 5개)에 병렬로 락을 시도해서 **과반수(N/2+1) 성공** 시 락을 획득한 것으로 본다. 단일 마스터의 failover 시 순간적으로 락이 두 클라이언트에게 동시 허용되는 문제를 방지하는 게 목적이다.

```
1. 현재 시간 t1 기록
2. 5개 Redis에 동시에 SET NX PX <TTL> 전송, 각각 짧은 타임아웃(50ms)
3. 성공 응답 개수가 3개(N/2+1) 이상이고,
   소요 시간(t2-t1)이 TTL보다 충분히 작으면 락 획득
4. 실패 시 잡힌 마스터에서 모두 unlock
```

이론적으로는 단일 마스터 실패를 견딘다. 하지만 Martin Kleppmann이 "[How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-we-got-to-distributed-locking.html)"에서 지적한 두 가지 반박이 있다.

**첫째, 클럭 드리프트 가정이 너무 강하다**. RedLock은 "각 노드의 시계가 거의 같은 속도로 흐른다"를 전제한다. 실제 운영에서 NTP 보정, VM live migration, 컨테이너 정지(freeze) 등으로 **시계가 점프**하면 TTL 만료 판단이 틀어져 이중 점유가 발생할 수 있다.

**둘째, fencing token이 없어 GC Pause로 인한 이중 실행을 못 막는다**. 클라이언트가 락을 잡고 작업 중 긴 GC Pause에 빠지면 TTL이 만료되고, 다른 클라이언트가 락을 잡아 작업을 완료한다. 그 후 GC에서 깨어난 원본 클라이언트는 **자신이 여전히 락을 가지고 있다고 믿고** 외부 시스템(DB/파일)을 갱신한다.

결론: **RedLock은 "RDB failover로 인한 이중 점유"만 막고, 프로세스 stall이나 클럭 문제는 못 막는다**. 강한 정합성이 필요하면 Fencing Token(다음 섹션)이나 ZooKeeper/etcd 같은 consensus 기반 시스템을 써야 한다. 일반 서비스에서는 단일 Redis + 재시도 + 멱등성 설계가 더 현실적이다.

## 5. Fencing Token — GC Pause를 이겨내는 유일한 방법

Fencing Token은 **락을 잡을 때마다 단조 증가하는 정수**를 함께 발급받고, 외부 리소스에 쓸 때 토큰을 반드시 전달해서 **리소스 측에서 오래된 토큰을 거부**하도록 한다.

```
클라이언트 A: 락 획득, 토큰 33 발급
A: DB에 "version=33, value=X" 갱신 요청
(A GC Pause 50초)
TTL 만료 → 클라이언트 B: 락 획득, 토큰 34 발급
B: DB에 "version=34, value=Y" 갱신 (성공)
(A 깨어남)
A: DB에 "version=33, value=X" 갱신 요청
DB: "현재 version=34, 33은 낡음" → 거부
```

Redis만으로 Fencing Token을 만들려면 `INCR lock:fence:seq` 같은 카운터를 락 획득과 함께 받아오면 되지만, **Redis 자체가 failover될 때 카운터가 뒤로 돌아갈 수 있다**(Replication lag). 그래서 엄밀한 단조 증가를 원하면 ZooKeeper의 `zxid`, etcd의 `revision`, DB 시퀀스 등을 써야 한다.

실전에서는 다음과 같이 **Redis 분산 락 + DB Optimistic Lock** 조합이 가장 많이 쓰인다. Redis 락은 경쟁 부하를 줄이고, DB 버전 컬럼이 최종 Fencing을 담당한다.

```java
RLock lock = redisson.getLock("order:" + orderId);
if (lock.tryLock(3, -1, SECONDS)) {
    try {
        Order o = orderRepo.findById(orderId).orElseThrow();
        o.decreaseStock(qty);  // @Version이 붙은 엔티티
        orderRepo.save(o);     // 낙관적 락 실패 시 OptimisticLockException
    } finally {
        lock.unlock();
    }
}
```

## 6. Spring Boot AOP로 선언형 분산 락 만들기

매번 try/finally를 쓰는 건 실수하기 쉽다. 사용자의 기학습 Spring AOP 지식을 활용해 다음과 같이 어노테이션 하나로 락을 씌운다.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();                   // SpEL. e.g. "'order:' + #orderId"
    long waitTime() default 3000;
    long leaseTime() default -1;    // -1 = Watchdog
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}

@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {
    private final RedissonClient redisson;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(dl)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock dl) throws Throwable {
        String key = evaluateKey(pjp, dl.key());
        RLock lock = redisson.getLock(key);
        boolean acquired = lock.tryLock(dl.waitTime(), dl.leaseTime(), dl.unit());
        if (!acquired) {
            throw new LockAcquireFailedException(key);
        }
        try {
            return pjp.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
    // evaluateKey(): SpEL로 메서드 파라미터 바인딩해서 key 생성
}
```

사용 예:

```java
@Service
public class OrderService {
    @DistributedLock(key = "'order:' + #orderId")
    @Transactional
    public void placeOrder(Long orderId, int qty) {
        // ...
    }
}
```

**중요**: `@DistributedLock`과 `@Transactional`을 같이 쓸 때 **Lock이 반드시 Transaction 바깥**에서 잡혀야 한다. 트랜잭션이 커밋되기 전에 락이 풀리면, 다른 스레드가 락을 잡고 커밋 전의 데이터를 읽는 일이 생긴다. Spring AOP에서는 어노테이션이 붙은 순서가 아니라 **Advice의 order**로 제어한다. `@EnableTransactionManagement(order = ...)` 와 `@Order`를 이용해 분산 락 Advice를 트랜잭션 Advice보다 **낮은 숫자(먼저 실행)** 로 설정한다.

## 7. 장애 시나리오와 관측

실전에서 반드시 만나는 문제들이다.

**마스터 failover 중 이중 점유**. Redis Sentinel이 마스터 교체를 하는 수 초간 락 정보가 복제되지 않은 슬레이브로 넘어가 다른 클라이언트에게 락이 허용될 수 있다. 대응은 (a) 멱등성 설계로 이중 실행을 허용 가능하게 만들기, (b) Fencing Token으로 외부 리소스에서 거부, (c) ZooKeeper/etcd로 이전.

**Watchdog 지연**. JVM GC Pause로 Watchdog 스케줄러가 10초 내에 TTL을 갱신 못 하면 TTL 만료로 락이 빠진다. 대응은 (a) G1/ZGC로 Pause 줄이기, (b) TTL을 60~120초로 늘리기, (c) Fencing Token 병행.

**연결 풀 포화**. 분산 락을 쓰는 요청이 폭증하면 Redis 커넥션 풀이 고갈된다. Redisson의 `connectionPoolSize`는 기본 64인데, 경쟁이 심한 API에서는 128~256까지 올려야 한다. Micrometer 메트릭(`redisson.connection.pool.active`)을 Grafana로 감시한다.

**락 경쟁 hotspot**. 한 키에 초당 수천 요청이 몰리면 Redis 단일 노드 CPU가 100%를 친다. 이때는 락 자체를 없애고 **Redis `INCR`/`HINCRBY`로 원자적 카운터**만 쓰거나, 샤딩 키(예: `order:{id}:{userId % 16}`)로 분산한다.

관측 지표로는 다음을 기록해야 한다.

- `lock.acquire.attempt`, `lock.acquire.success`, `lock.acquire.fail` (Counter)
- `lock.wait.time` (Timer; waitTime 도달 비율 추적)
- `lock.hold.time` (Timer; 임계 구역 실제 소요)
- `lock.expired.unlock` (Counter; 락이 이미 만료된 상태에서 unlock 시도한 횟수 — 위험 신호)

## 8. 성능 측정과 대안 비교

JMeter로 10k TPS 재고 차감을 돌린 간단한 벤치마크 기준 체감치는 대략 다음과 같다(환경: c6i.xlarge 3대, Redis 1대, MySQL 1대).

- **DB `SELECT FOR UPDATE`**: 3.5k TPS에서 락 대기 큐 포화, p99 latency 800ms
- **SETNX Lua**: 8k TPS까지 안정, p99 latency 90ms. 단 이중 실행 방지만 가능(재진입 불가)
- **Redisson RLock**: 7.5k TPS, p99 latency 120ms. 재진입·Watchdog 포함하면 오버헤드 20%
- **ZooKeeper Curator InterProcessMutex**: 2k TPS 한계, p99 latency 300ms. 대신 Fencing Token이 견고

숫자 자체보다 **trade-off 축**이 중요하다. 단순 이중 실행 방지만 필요하고 TTL 관리를 직접 할 수 있으면 SETNX로 충분하다. Spring 애플리케이션에서 재진입과 자동 TTL 갱신이 필요하면 Redisson. 금융·결제처럼 **절대 이중 실행되면 안 되는** 도메인은 ZooKeeper + Fencing Token + 애플리케이션 멱등성의 3중 안전장치를 쓴다.

---

## 참고

- 기학습 연계: [Redis](./Redis.md), [ORM](./ORM.md) (낙관적 락), [Spring](./Spring.md) (AOP)
- [Redis Docs — Distributed Locks](https://redis.io/docs/manual/patterns/distributed-locks/)
- [Redisson Wiki — RLock](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- Martin Kleppmann (2016), "How to do distributed locking"
- Antirez 반박글: "Is Redlock safe?"
- 이상민, 『자바 성능 튜닝 이야기』 — GC Pause가 락에 미치는 영향 이해에 도움
