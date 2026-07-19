Notion 원본: https://www.notion.so/3a25a06fd6d38130b935e5980f0e6b04

# Redis 분산락과 Redisson Watchdog 및 RedLock 안전성 논쟁

> 2026-07-19 신규 주제 · 확장 대상: Redis

## 학습 목표

- 단일 Redis 분산락의 SET NX PX + 토큰 삭제 패턴이 필요한 이유를 설명한다.
- Redisson 의 Watchdog 자동 연장과 재진입 구현을 내부 자료구조로 이해한다.
- RedLock 알고리즘의 주장과 반론(Kleppmann vs antirez)을 정리한다.
- fencing token 이 필요한 경계를 판단한다.

## 1. 왜 SETNX 하나로는 안 되는가

`SETNX` 만 쓰면 락을 잡은 프로세스가 죽을 때 데드락이 된다. 만료(`PX`)를 붙이면 다른 사람 락을 지우는 문제가 생긴다. 해법은 소유권 토큰이다.

```bash
SET lock:order:42 <uuid-token> NX PX 30000

EVAL "if redis.call('get', KEYS[1]) == ARGV[1] then
        return redis.call('del', KEYS[1])
      else return 0 end" 1 lock:order:42 <uuid-token>
```

해제를 Lua 로 감싸는 이유는 GET 후 DEL 사이의 경쟁을 없애기 위함이다.

## 2. 만료 시간 딜레마

짧게 잡으면 상호배제 위반, 길게 잡으면 가용성 저하. 가변 작업은 고정값이 옵지 않다. 해법은 락 자동 연장(lease renewal)으로, 살아있는 동안 갱신하고 죽으면 멈춰 자연 만료되게 한다.

## 3. Redisson Watchdog 내부

인자 없는 `lock()` 은 기본 30초로 잡고 Watchdog 이 10초마다 만료를 30초로 되돌린다.

```java
RLock lock = redissonClient.getLock("lock:order:42");
lock.lock();   // leaseTime 미지정 → Watchdog 활성화
try { processOrder(42L); } finally { lock.unlock(); }
```

`leaseTime` 을 지정하면 Watchdog 이 꺼진다. GC·네트워크 단절로 갱신을 못 하면 락이 만료돼도 작업 스레드는 계속 돌아 RedLock 논쟁의 핵심 시나리오가 된다.

## 4. 재진입락과 Hash 자료구조

재진입을 위해 락 값을 Hash(field=clientId:threadId, value=홀드 카운트)로 저장한다. 대기자는 스핀 대신 Pub/Sub 으로 해제 알림을 구독해 Redis 부하를 줄인다.

## 5. RedLock 알고리즘과 안전성 논쟁

단일/마스터-복제는 복제 승격 시 두 클라이언트가 동시 획득할 수 있다. RedLock 은 N(5)개 독립 마스터 과반 획득으로 이를 완화한다. Kleppmann 은 GC·시계 점프 시 타이밍 가정이 깨져 락 자체로는 안전성을 못 준다며 fencing token 을 요구했고, antirez 는 monotonic clock 으로 완화되고 효율성 락에는 충분하다고 반박했다.

## 6. Fencing Token

락 획득마다 단조 증가 번호를 발급하고 자원이 더 낮은 번호의 쓰기를 거부한다. token=33 이 멈춘 사이 token=34 가 기록되면 33 은 거부된다. Redis 는 단조 토큰을 자연히 주지 못하므로 강한 안전성이 필수면 ZooKeeper(zxid)·etcd(revision) 가 더 적합하다.

## 7. 효율성 락 vs 정합성 락

| 구분 | 효율성 락 | 정합성 락 |
|---|---|---|
| 목적 | 중복 작업 방지 | 데이터 손상 방지 |
| 실패 영향 | 낭비 | 치명 |
| 적정 도구 | 단일 Redis + Redisson | ZooKeeper/etcd + fencing |

대부분 실무 락은 효율성 락이라 Redisson 으로 충분하다. 정합성 락은 자원 수준 방어(DB 유니크, 낙관적 락, 조건부 UPDATE)를 병행해야 한다.

## 8. 실전 체크리스트

```java
boolean acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
if (!acquired) throw new LockAcquisitionException("잠시 후 재시도");
try { processOrder(42L); }
finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
```

DB 제약으로 풀리면 락보다 안전하다. 효율성/정합성 구분, 자동 연장 여부, 실패 시 동작을 먼저 정의한다.

## 참고

- Redis 공식 문서 — "Distributed Locks with Redis"
- Martin Kleppmann — "How to do distributed locking"
- antirez — "Is Redlock safe?"
- Redisson Wiki — "Distributed locks and synchronizers"
- 『Designing Data-Intensive Applications』 — 8장
