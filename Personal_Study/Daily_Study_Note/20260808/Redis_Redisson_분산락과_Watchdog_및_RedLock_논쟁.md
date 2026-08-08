Notion 원본: https://www.notion.so/3b65a06fd6d381a88929db98ff0a6ceb

# Redis Redisson 분산락과 Watchdog 및 RedLock 논쟁

> 2026-08-08 신규 주제 · 확장 대상: Redis (캐시·Stream Consumer Group·Cluster 슬롯 기학습)

## 학습 목표

- SETNX 수동 락의 결함 4가지를 짚고 Redisson RLock의 Lua 원자화·재진입 구조로 해소 과정을 추적한다
- Watchdog 자동 연장의 동작 조건과 leaseTime 지정 시 꺼지는 함정을 검증한다
- RedLock 알고리즘과 Martin Kleppmann–antirez 논쟁을 정리하고 fencing token의 역할을 설명한다
- Semaphore·CountDownLatch·RateLimiter 등 Redisson 동기화 프리미티브를 실무 시나리오에 배치한다

## 1. 소박한 SETNX 락의 결함 해부

분산락의 출발점은 `SET key value NX PX 30000` — 키가 없을 때만 TTL과 함께 설정하는 원자 명령이다. 그러나 이 단순 구현에는 결함이 누적되어 있다. 첫째, **오해제(unlock by non-owner)**: 락 보유자 A의 작업이 TTL을 넘겨 락이 만료되고 B가 획득한 상태에서, 뒤늦게 끝난 A가 `DEL`을 호출하면 B의 락을 지운다. 해제 전 소유자 검사와 삭제가 원자적이어야 하므로 Lua 스크립트가 필수다.

```lua
-- 소유자 확인 후 삭제 (원자적)
if redis.call("GET", KEYS[1]) == ARGV[1] then
	return redis.call("DEL", KEYS[1])
else
	return 0
end
```

둘째, **TTL 딜레마**: 짧으면 작업 중 만료(상호배제 붕괴), 길면 보유자 크래시 시 그 시간만큼 전체 대기. 셋째, **재진입 불가**: 같은 스레드가 중첩 획득하면 자기 자신에 데드락. 넷째, **폴링 대기**: 획득 실패 시 재시도 루프가 Redis에 부하를 준다. Redisson RLock은 이 네 가지를 각각 Lua 원자화, Watchdog(§3), hash 기반 재진입 카운트(§2), pub/sub 대기(§2)로 해소한 구현체다.

## 2. Redisson RLock 내부 — hash 구조와 pub/sub 대기

RLock은 락 키를 문자열이 아닌 **hash**로 저장한다. 필드명이 `<UUID>:<threadId>`(클라이언트 인스턴스 UUID + 스레드 ID), 값이 재진입 카운트다. 획득 Lua는 대략 다음 논리다: 키가 없으면 hash 생성 후 TTL 설정(획득), 자기 필드가 있으면 카운트 증가 후 TTL 갱신(재진입), 남의 락이면 남은 TTL(PTTL)을 반환(실패). 해제는 카운트를 감소시키고 0이 되면 키를 지운 뒤 **해제 채널에 publish**한다.

```java
RedissonClient redisson = Redisson.create(config);
RLock lock = redisson.getLock("order:12345");

// waitTime 5s 안에 획득 시도, 획득 실패 시 false — 이 오버로드는 watchdog 동작
boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);
if (!acquired) {
	throw new ConcurrentModificationException("주문 잠금 실패");
}
try {
	processOrder();
} finally {
	if (lock.isHeldByCurrentThread()) { // 만료 후 오해제 방지 가드
		lock.unlock();
	}
}
```

대기 구현이 폴링이 아니라는 점이 중요하다. 획득 실패한 클라이언트는 `redisson_lock__channel:{키}` 채널을 구독하고 세마포어로 블록한다. 해제 publish가 오면 깨어나 재시도하므로, 경합이 심해도 Redis 부하는 획득 시도 횟수에 비례하지 않는다. 실패 시 반환된 PTTL만큼만 타임아웃을 걸어 구독 누수도 막는다. 클러스터 환경에서는 락 키에 해시태그가 없어도 단일 키 연산이라 문제없지만, `multiLock`으로 여러 키를 묶을 때는 키들이 다른 슬롯에 있어도 클라이언트가 순차 획득하므로 슬롯 제약은 없다.

## 3. Watchdog — 자동 연장의 정확한 조건

TTL 딜레마의 Redisson 해법이 **Watchdog(락 감시 스레드)** 다. 동작 규칙이 미묘해 사고가 잦으니 정확히 짚는다. **leaseTime을 지정하지 않고**(`lock()`, `tryLock(waitTime, unit)`) 획득한 경우에만 활성화되며, 기본 30초(`lockWatchdogTimeout`) TTL을 걸고 **그 1/3인 10초마다** 보유 여부를 확인해 TTL을 30초로 재설정한다. 클라이언트 프로세스가 죽으면 갱신이 먎고 최대 30초 뒤 락이 자연 만료된다 — 크래시 안전과 작업 중 만료 방지를 동시에 얻는 구조다.

함정은 두 가지다. 첫째, **`tryLock(waitTime, leaseTime, unit)`처럼 leaseTime을 명시하면 Watchdog은 꺼진다.** "안전하게 10초로 걸어두자"는 의도의 leaseTime 지정이 오히려 장기 작업에서 락 만료 → 동시 실행 사고로 이어지는 대표 패턴이다. 연장이 필요하면 leaseTime을 빼는 것이 옳다. 둘째, Watchdog 갱신은 클라이언트가 살아 있고 **Redis와 통신 가능**해야 한다. GC 장기 정지(STW)나 네트워크 단절이 30초를 넘으면 갱신이 실패해 락이 만료되는데, 애플리케이션 스레드는 자신이 락을 잃은 사실을 모른 채 임계 구역을 계속 실행한다. 이것이 §5 fencing token 논의의 출발점이다.

```java
// 안티패턴: leaseTime 지정 → watchdog off → 10초 뒤 만료, 작업은 60초 소요
lock.tryLock(5, 10, TimeUnit.SECONDS);

// 권장: leaseTime 미지정 → watchdog이 30초 TTL을 10초마다 연장
lock.tryLock(5, TimeUnit.SECONDS);
```

## 4. 단일 인스턴스 락의 한계와 RedLock 알고리즘

여기까지의 락은 Redis 마스터 1대 기준의 상호배제다. 마스터-레플리카 구성에서 **복제는 비동기**이므로, 락 SET 직후 마스터가 죽고 레플리카가 승격되면 락 정보가 유실되어 두 클라이언트가 동시에 락을 쥐다. 이를 겨냥해 antirez(Salvatore Sanfilippo)가 제안한 것이 **RedLock**이다: 독립된 Redis 인스턴스 N대(권장 5)에 같은 키·같은 랜덤 값으로 순차 SET을 시도해, **과반(N/2+1) 획득 + 소요 시간이 유효시간보다 충분히 짧음**이 확인되면 락 성립으로 본다. 유효시간은 `TTL - 획득 소요 시간 - 클럭 드리프트 보정`으로 줄여 계산하고, 실패 시 전 인스턴스에 해제를 보낸다.

Redisson은 과거 `getRedLock()` API를 제공했으나 **3.16.0에서 deprecated** 됐다. 명목상 이유는 "단일 RLock도 충분히 안전해졌다"는 것이지만, 배경에는 다음 절의 논쟁과 "5개 독립 인스턴스 운영 비용 대비 보장이 애매하다"는 실무 평가가 있다. 현재 공식 권장은 단일 RLock(필요 시 복제 구성에서 WAIT 명령 보조) 또는 진짜 강한 보장이 필요하면 합의 기반 시스템으로 옮기는 것이다.

## 5. Kleppmann–antirez 논쟁과 fencing token

2016년 Martin Kleppmann은 "How to do distributed locking"에서 RedLock을 두 축으로 비판했다. 첫째, **타이밍 가정의 취약성**: RedLock의 안전성은 클럭 점프(NTP 보정, 관리자 조작)와 프로세스 정지(GC STW, VM 마이그레이션)가 유효시간 대비 작다는 가정에 의존하는데, 이는 비동기 시스템 모델에서 보장 불가능하다. 둘째, 더 본질적으로 **락 만료 뒤에도 자신이 보유자라 믿는 클라이언트의 지연된 쓰기**는 어떤 락 알고리즘도 클라이언트 측만으로는 못 막는다. 해법으로 제시한 것이 **fencing token** — 락 서비스가 획득마다 단조 증가 토큰을 발급하고, **보호 대상 리소스(DB·스토리지)가 더 낮은 토큰의 쓰기를 거부**하는 방식이다. antirez는 "클럭 점프는 운영으로 통제 가능하고, fencing이 가능한 리소스라면 애초에 락이 불필요한 경우가 많다"고 반박했으나, "효율성 목적의 락(중복 작업 방지)에는 Redis 락, **정확성 목적의 락(데이터 무결성)에는 합의 시스템 + fencing**"이라는 Kleppmann의 구분 자체는 업계 표준 감각으로 정착했다.

실무 적용은 fencing의 근사 구현으로 가능하다. 락 획득 시 Redis `INCR`로 토큰을 따고, DB 쓰기를 조건부 UPDATE로 만든다.

```sql
-- 리소스 테이블에 last_token 컬럼 유지
UPDATE inventory
SET qty = qty - 1, last_token = :token
WHERE item_id = :id AND last_token < :token;
-- 갱신 행 수 0 → 자신보다 새 보유자가 이미 썼음 → 중단
```

락 없이도 조건부 쓰기(낙관적 락, 유니크 제약)만으로 무결성이 지켜지는 설계라면 그것이 항상 우선이다 — 분산락은 "동시 실행을 줄이는 효율 장치"로 격하해 두는 것이 안전한 기본 자세다.

## 6. Semaphore와 CountDownLatch — 락 이외의 동기화

Redisson은 락 외의 분산 동기화 프리미티브도 제공하며, 각각 뚜렷한 실무 자리가 있다. **RSemaphore**는 허가(permit) n개를 여러 프로세스가 나눠 갖는 구조로, 외부 API 동시 호출 상한·배치 동시 실행 수 제한에 쓴다. 주의할 점은 RSemaphore의 permit에는 TTL이 없어 보유 프로세스가 죽으면 permit이 누수된다는 것 — 이를 보완한 **RPermitExpirableSemaphore**는 permit마다 ID와 TTL을 부여해 크래시 시 자동 회수된다. 동시성 상한이 중요한 운영 환경이라면 후자가 기본 선택이다.

```java
RPermitExpirableSemaphore sem =
	redisson.getPermitExpirableSemaphore("ext-api:quota");
sem.trySetPermits(10); // 최초 1회 설정 (이미 있으면 무시)

String permitId = sem.tryAcquire(2_000, 30_000, TimeUnit.MILLISECONDS);
// 대기 2초, permit TTL 30초
if (permitId != null) {
	try {
		callExternalApi();
	} finally {
		sem.release(permitId);
	}
}
```

**RCountDownLatch**는 분산 작업 n개의 완료 대기(팬아웃 후 조인)에, **RRateLimiter**는 토큰 버킷 기반 전역 유량 제한(`trySetRate(RateType.OVERALL, 100, 1, RateIntervalUnit.SECONDS)`)에 쓴다. RRateLimiter는 인스턴스 수와 무관하게 클러스터 전체 합산 유량을 제한한다는 점에서, 인스턴스 로컬 Bucket4j/Resilience4j와 용도가 갈린다.

## 7. Spring 통합 패턴 — AOP 락과 트랜잭션 경계

Spring에서 분산락은 어노테이션 + AOP로 감싸는 것이 표준 관례다. 이때 가장 흔한 결함이 **락 해제와 트랜잭션 커밋의 순서 역전**이다. `@Transactional` 메서드 내부에서 락을 잡고 메서드 끝에서 해제하면, 해제(finally) → 커밋(프록시 반환 후) 순서가 되어 커밋 전 틈에 다른 프로세스가 락을 잡고 아직 커밋 안 된 상태를 읽는다.

```java
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

	private final RedissonClient redisson;
	private final TxRunner txRunner; // REQUIRES_NEW 실행기

	@Around("@annotation(distLock)")
	public Object around(ProceedingJoinPoint pjp, DistLock distLock) throws Throwable {
		String key = resolveKey(pjp, distLock.key()); // SpEL 평가
		RLock lock = redisson.getLock(key);
		boolean acquired = lock.tryLock(distLock.waitMs(), TimeUnit.MILLISECONDS);
		if (!acquired) {
			throw new LockAcquisitionException(key);
		}
		try {
			// 트랜잭션을 락 스코프 "안쪽"에 새로 열어 커밋까지 끝냄
			return txRunner.runInNewTransaction(pjp::proceed);
		} finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
```

락 스코프가 트랜잭션을 완전히 감싸도록 `REQUIRES_NEW`로 안쪽에 여는 이 구조가 정석이다. 반대로 락 획득을 기존 트랜잭션 안에서 하면(외부 `@Transactional` → 내부 락) 커밋 시점이 락 밖으로 새므로, AOP `@Order`로 락 어스펙트가 트랜잭션 어드바이저보다 먼저 실행되게 강제하는 방법도 쓰인다.

## 8. 운영 장애 시나리오와 대응

실서비스에서 겪는 대표 장애를 정리한다. **(1) 페일오버 락 유실** — §4의 비동기 복제 문제. Sentinel/Cluster 페일오버 직후 중복 실행 가능성을 전제하고, 임계 작업은 §5의 조건부 쓰기로 이중 방어한다. **(2) STW로 인한 만료** — 힙이 큰 JVM에서 Full GC 30초+는 드물지 않다. `lockWatchdogTimeout` 상향(예: 60초)과 GC 튜닝을 병행하되, 근본적으로는 임계 구역 축소가 답이다. **(3) unlock 누락 — IllegalMonitorStateException**: 만료 후 unlock 호출 시 발생하며, `isHeldByCurrentThread()` 가드가 관례다(§2 코드). 예외 자체보다 "만료됐었다"는 신호로 받아들여 해당 작업의 결과 검증을 트리거해야 한다. **(4) pub/sub 유실 대기 지연** — 클러스터 리샤딩·failover 중 해제 알림이 유실되면 대기자가 타임아웃까지 자는다. Redisson은 PTTL 기반 타임아웃 재시도로 자가 회복하지만 waitTime을 여유 있게 잡는다. 모니터링 지표로는 락 획득 소요 시간 히스토그램, tryLock 실패율, watchdog 갱신 실패 로그(`Can't update lock ... expiration`)를 대시보드에 올려두면 대부분의 이상을 조기에 잡는다.

## 9. 기술 선택 — Redis 락 vs DB 락 vs ZooKeeper·etcd

마지막으로 대안 비교다. **DB 비관적 락**(`SELECT ... FOR UPDATE`)·네임드 락(MySQL `GET_LOCK`)은 이미 트랜잭션 안에 있는 데이터 보호라면 추가 인프라 없이 가장 강한 일관성을 준다 — 단일 DB로 처리량이 감당되면 이것이 첫 선택지다. **Redis(Redisson)** 는 DB 부하 분리·수 ms 획득 지연·풍부한 프리미티브가 장점이고, 보장 수준은 "거의 항상 상호배제"(페일오버 엣지 존재)다. **ZooKeeper/etcd**는 합의(ZAB/Raft) 기반이라 페일오버에도 락 상태가 유지되고 세션·리스 기반 자동 해제와 단조 revision(fencing token으로 사용 가능)을 제공하지만, 운영 복잡도와 획득 지연(수십 ms)이 비용이다. 판단 규칙을 요약하면: 정확성이 돈과 직결(결제·재고 차감)이면 DB 조건부 쓰기 우선 + 필요시 합의 시스템, 중복 작업 방지·캐시 재생성 억제 같은 효율 문제면 Redisson 단일 RLock으로 충분하며, 어떤 선택이든 "락이 실패해도 데이터는 안 깨지는" 멱등·조건부 쓰기 설계를 락 아래에 깔아두는 것이 원칙이다.

## 참고

- Redis 공식 문서 — Distributed Locks with Redis (redis.io/docs/latest/develop/use/patterns/distributed-locks)
- Martin Kleppmann, "How to do distributed locking" (martin.kleppmann.com, 2016)
- Salvatore Sanfilippo, "Is Redlock safe?" (antirez.com, 2016)
- Redisson Wiki — 8. Distributed locks and synchronizers (github.com/redisson/redisson/wiki)
- Martin Kleppmann, 『Designing Data-Intensive Applications』 8~9장 (분산 시스템 오류 모델·합의)
