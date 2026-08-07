Notion 원본: https://www.notion.so/3b55a06fd6d381259f35f081ad434163

# Spring Cache 추상화와 Caffeine W-TinyLFU 및 캐시 스탬피드 방지
> 2026-08-07 신규 주제 · 확장 대상: Spring (AOP 프록시·트랜잭션 기학습) / Redis (캐시 전략 기학습)

## 학습 목표
- Spring Cache 추상화의 CacheManager/Cache 구조와 @Cacheable 계열 애너테이션의 AOP 프록시 동작 및 self-invocation 한계를 설명한다.
- Caffeine 의 Window-TinyLFU 어드미션 정책, frequency sketch, doorkeeper, SLRU 구조가 LRU/LFU 대비 히트율을 높이는 원리를 분석한다.
- 캐시 스탬피드의 원인과 요청 병합(single-flight), 확률적 조기 만료(XFetch), soft TTL + 백그라운드 refresh 세 가지 방어 기법을 구현한다.
- 로컬 캐시(Caffeine)와 원격 캐시(Redis)를 결합한 2단계 캐시의 무효화 전파와 TTL 정합성 전략을 설계한다.

## 1. Spring Cache 추상화 구조

Spring 캐시 추상화의 핵심은 두 인터페이스다. `CacheManager` 는 이름으로 `Cache` 를 조회하는 팩터리이고, `Cache` 는 `get/put/evict/clear` 를 정의한 저장소 SPI 다. 구현체로 `CaffeineCacheManager`, `RedisCacheManager` 등이 있으며, 코드는 애너테이션에만 의존하므로 제공자를 설정만으로 교체할 수 있다.

`@Cacheable` 은 실행 전 캐시를 조회해 히트면 본문을 건너뛰고 미스면 결과를 저장한다. `@CachePut` 은 항상 실행 후 결과를 덮어쓰므로 갱신 경로에 쓴다. `@CacheEvict` 는 항목 제거이며 `allEntries = true` 로 전체 삭제, `beforeInvocation = true` 로 실행 전 제거를 지정한다. 키는 기본 `SimpleKeyGenerator` 가 파라미터 조합으로 만들지만 SpEL 로 명시하는 편이 안전하다.

```java
@Service
public class ProductService {

	@Cacheable(cacheNames = "product", key = "#productId", unless = "#result == null", sync = true)
	public Product findProduct(long productId) {
		return productRepository.findById(productId)
			.orElse(null);
	}

	@CachePut(cacheNames = "product", key = "#product.id")
	public Product updateProduct(Product product) {
		return productRepository.save(product);
	}

	@CacheEvict(cacheNames = "product", key = "#productId")
	public void deleteProduct(long productId) {
		productRepository.deleteById(productId);
	}
}
```

이 애너테이션들은 `@EnableCaching` 이 등록하는 `CacheInterceptor`(AOP 어드바이스)가 프록시로 가로채며 동작한다. 따라서 **같은 클래스 내부에서 `this.findProduct(id)` 로 자기 호출(self-invocation)하면 프록시를 우회해 캐시가 동작하지 않는다.** `@Transactional` 과 동일한 구조적 한계로, 해결책은 빈 분리, 자기 자신 주입, AspectJ 위빙(`mode = AdviceMode.ASPECTJ`)이다. 프록시 기본 동작상 public 메서드에만 적용된다.

`sync = true` 는 동일 키 동시 미스 시 한 스레드만 메서드를 실행하고 나머지는 결과를 대기하게 하는 힌트다. Caffeine 구현에서는 `Cache.get(key, mappingFunction)` 의 per-key 원자적 로딩으로 매핑되어 사실상 single-flight 가 된다. 단 `unless` 와 병행할 수 없고 캐시 이름을 하나만 지정해야 한다.

## 2. Caffeine 아키텍처: Window-TinyLFU

Caffeine 은 Ben Manes 가 Guava Cache 를 재설계한 라이브러리로, 축출 정책으로 **W-TinyLFU(Window TinyLFU)** 를 사용한다. 순수 LRU 는 최근성만 보므로 스캔(일회성 대량 조회)에 취약하고, 순수 LFU 는 빈도 이력을 전부 유지해야 하며 과거에만 인기 있던 항목이 오래 남는 문제가 있다. W-TinyLFU 는 두 정책의 장점을 결합한다.

구조는 세 영역으로 나뉜다.

| 영역 | 비율(기본) | 정책 | 역할 |
|------|-----------|------|------|
| Window | 약 1% | LRU | 신규 항목의 진입 완충 지대. 버스트 최근성 흡수 |
| Main - Probation | 99% 중 20% | SLRU 하위 | window 에서 승격된 항목의 관찰 구간 |
| Main - Protected | 99% 중 80% | SLRU 상위 | probation 에서 재접근된 검증된 인기 항목 |

신규 항목은 먼저 window 에 들어가고, window 에서 밀려난 후보(candidate)는 probation 의 LRU 끝에 있는 희생자(victim)와 **어드미션 필터** 에서 경쟁한다. 두 항목의 접근 빈도 추정치를 비교해 후보가 더 자주 쓰였을 때만 진입을 허용한다. "한 번 들어오면 언젠가 밀려나는" LRU 와 달리 **애초에 들어올 자격을 검사** 하는 것이 TinyLFU 의 핵심이다.

빈도 추정은 **CountMin Sketch 변형(FrequencySketch)** 으로 한다. 항목당 4-bit 카운터(최대 15)를 여러 해시 행에 유지하고 조회 시 최솟값을 추정치로 쓴다. 전체 접근 횟수가 표본 크기(캐시 용량의 10배)에 도달하면 모든 카운터를 절반으로 줄이는 **aging(reset)** 을 수행해 오래된 인기도를 점차 소멸시키므로 LFU 의 "과거 인기 항목 고착" 문제가 사라진다.

**doorkeeper** 는 sketch 앞단의 블룸 필터다. 워크로드의 상당수는 한 번만 접근되는 one-hit wonder 인데, 첫 접근은 doorkeeper 에만 기록하고 두 번째 접근부터 sketch 카운터를 올린다. 일회성 항목의 sketch 오염을 막아 같은 메모리로 더 정확한 추정을 얻으며, aging 시점에 doorkeeper 도 함께 비운다.

히트율이 우수한 이유는 워크로드 적응성이다. 스캔·버스트는 window 가 흡수하고 어드미션 필터가 main 오염을 차단하며(LRU 약점 보완), 빈도 기반 보호는 aging 으로 최신성을 유지한다(LFU 약점 보완). TinyLFU 논문과 Caffeine 시뮬레이터의 트레이스 벤치마크(DB·검색·OLTP)에서 W-TinyLFU 는 LRU 를 크게 앞서고 ARC·LIRS 와 대등하거나 우수했다. Caffeine 은 여기에 window 비율을 히트율 피드백으로 동적 조정하는 적응형 정책(hill climbing)을 더해 최근성 편향 워크로드에도 대응한다.

## 3. 만료 정책과 크기 기반 축출

Caffeine 의 시간 기반 만료는 세 가지다.

| 설정 | 기준 시점 | 만료 시 동작 | 용도 |
|------|-----------|--------------|------|
| `expireAfterWrite` | 쓰기(생성/교체) 후 경과 | 제거, 다음 조회는 미스 | 데이터 신선도 보장(일반적 TTL) |
| `expireAfterAccess` | 마지막 읽기/쓰기 후 경과 | 제거 | 세션류, 유휴 데이터 정리 |
| `refreshAfterWrite` | 쓰기 후 경과 | **제거하지 않고** 조회 시 비동기 재적재, 그동안 stale 값 반환 | 핫 키 무중단 갱신 (LoadingCache 전용) |

`expireAfterWrite/Access` 처럼 TTL 이 전 항목 동일하면 쓰기/접근 순서 큐의 head 만 검사해 O(1) 로 만료를 판정한다. 항목별 만료가 다른 `expireAfter(Expiry)` 는 **계층형 TimerWheel** 로 관리한다. 시간을 2의 거듭제곱 버킷의 다중 휠(초·분·시·일 계층)로 해싱해 삽입·삭제를 상수 시간에 처리하고, 상위 휠 항목은 시간이 흐르면 하위 휠로 캐스케이드된다. O(log n) 우선순위 큐 대비 대량 항목에서 유리하다.

크기 기반 축출은 `maximumSize`(항목 수) 또는 `maximumWeight` + `Weigher`(항목별 비용)를 쓴다. 둘은 동시에 지정할 수 없다.

```java
Cache<String, byte[]> imageCache = Caffeine.newBuilder()
	.maximumWeight(64L * 1024L * 1024L)
	.weigher((String key, byte[] value) -> value.length)
	.expireAfterWrite(Duration.ofMinutes(10L))
	.recordStats()
	.build();
```

만료는 전용 스레드로 즉시 일어나지 않고 읽기/쓰기 작업에 편승한 유지보수(maintenance)로 처리되므로, 트래픽 없는 캐시에는 만료 항목이 물리적으로 남을 수 있다. `Scheduler.systemScheduler()` 를 지정하면 만료 시점 근처에 능동 정리한다.

## 4. LoadingCache, AsyncCache 와 비동기 유지보수

`LoadingCache` 는 `CacheLoader` 를 내장해 미스 시 로딩을 캐시가 수행한다. 같은 키의 동시 `get` 은 해시 테이블의 per-key 락(엔트리 단위 computeIfAbsent) 아래에서 **한 스레드만 로드하고 나머지는 블로킹 대기** 하므로 자체적으로 요청 병합이 된다. `AsyncCache`/`AsyncLoadingCache` 는 값을 `CompletableFuture` 로 저장해 로딩 자체를 논블로킹으로 만든다. 동시 미스 스레드들은 동일한 미완료 future 를 공유하므로 스레드 블로킹 없이 병합된다.

```java
AsyncLoadingCache<Long, Product> asyncCache = Caffeine.newBuilder()
	.maximumSize(10_000L)
	.refreshAfterWrite(Duration.ofMinutes(1L))
	.executor(loaderExecutor)
	.buildAsync(new CacheLoader<>() {
		@Override
		public Product load(Long key) {
			return productClient.fetch(key);
		}

		@Override
		public Product reload(Long key, Product oldValue) {
			return productClient.fetchIfModified(key, oldValue);
		}
	});

CompletableFuture<Product> future = asyncCache.get(1L);
```

내부 동시성 설계도 핵심이다. 축출 정책(LRU 큐, sketch)을 매 접근마다 락으로 갱신하면 경합이 병목이 되므로, Caffeine 은 **읽기는 striped ring buffer(lossy)**, **쓰기는 유계 write buffer** 에 이벤트만 기록하고 즉시 반환한다. 이후 `tryLock` 으로 유지보수 락을 잡은 스레드가 버퍼를 **drain** 하며 정책 상태를 일괄 반영한다. 읽기 버퍼가 차면 이벤트를 버려도 정책 품질 손실이 작다는 점을 이용해 읽기 경로를 사실상 락프리로 만들어 ConcurrentHashMap 에 근접한 처리량을 낸다.

## 5. 캐시 스탬피드(Thundering Herd) 방지

캐시 스탬피드는 인기 키가 만료되는 순간 다수 요청이 동시에 미스를 맞고 **전부 원본(DB·외부 API)으로 몰려 같은 값을 중복 계산** 하는 현상이다. 원본이 과부하로 느려질수록 동시 미스 구간이 길어져 더 많은 요청이 몰리는 양성 피드백으로 장애가 증폭된다. 배포 직후 콜드 캐시, 동일 TTL 일괄 적재로 인한 대량 동시 만료도 같은 문제를 만든다. 방어는 세 계열이다.

**(1) 요청 병합(single-flight).** 동일 키의 동시 재계산을 1회로 합친다. 로컬에서는 `@Cacheable(sync = true)` 또는 Caffeine `LoadingCache`/`AsyncCache` 의 per-key 로딩이 이를 보장한다. 단 **인스턴스 단위** 병합이므로 서버 N 대면 원본 요청도 최대 N 개다. 분산 병합은 Redis `SET key token NX PX` 로 재계산 권한 락을 잡고, 실패 스레드는 짧게 대기 후 캐시를 재조회하거나 stale 값을 반환한다.

**(2) 확률적 조기 만료(XFetch).** VLDB 2015 논문 "Optimal Probabilistic Cache Stampede Prevention" 의 기법으로, 값 저장 시 재계산 소요 시간 delta 를 함께 기록하고 조회 시 아래 조건이면 만료 전에 자발적으로 재계산한다.

```java
public boolean shouldRecomputeEarly(long nowMillis, long expiryMillis, long deltaMillis, double beta) {
	double xfetch = deltaMillis * beta * -Math.log(ThreadLocalRandom.current().nextDouble());
	return nowMillis + (long) xfetch >= expiryMillis;
}
```

`-ln(U(0,1))` 은 지수분포를 따르므로 만료가 가까울수록 조기 갱신 확률이 지수적으로 커지고, 재계산이 오래 걸리는 키(delta 큼)일수록 더 일찍 갱신된다. 논문은 `beta = 1` 이 최적임을 보였다. 갱신 시점이 요청마다 확률적으로 분산되어 만료 순간의 동시 폭주 자체가 사라지며, 트리거한 요청 하나만 재계산한다.

**(3) soft TTL + 백그라운드 refresh.** 논리적 만료(soft TTL)와 물리적 만료(hard TTL)를 분리해, soft TTL 이 지나면 stale 값을 즉시 반환하며 갱신을 백그라운드에 위임하므로 사용자 지연이 0 이다. Caffeine 의 `refreshAfterWrite` 가 이 패턴으로, 첫 조회 스레드가 비동기 reload 를 1회만 트리거하고(중복 방지 내장) 완료 전까지 기존 값을 반환한다. `reload(key, oldValue)` 오버라이드로 조건부 GET(ETag) 증분 갱신도 가능하다. "refresh 1분 + expire 10분"처럼 `expireAfterWrite` 와 병행해 트래픽 끊긴 키만 진짜 만료시키는 구성이 실무 표준이다.

보조 수단으로 TTL 지터(기본 TTL 의 ±10% 난수)로 대량 동시 만료를 흩뜨리거나, 만료 없이 스케줄러가 주기적으로 전체 키를 재적재하는 방식(정적 소규모 데이터)도 쓴다.

## 6. 2단계 캐시: 로컬 Caffeine + 원격 Redis

로컬 캐시는 마이크로초 미만 지연에 네트워크 홉이 없지만 인스턴스 간 불일치가 생기고, Redis 는 단일 뷰를 공유하지만 왕복 지연(같은 AZ 기준 수백 마이크로초~밀리초)과 직렬화 비용이 든다. 2단계 캐시는 조회를 L1(Caffeine) → L2(Redis) → 원본 순으로 폴스루하고 적재 시 역순으로 채운다. 핫 키 트래픽 대부분을 L1 이 흡수해 Redis 부하와 tail latency 를 동시에 줄인다.

핵심 난제는 **무효화 전파** 다. 한 인스턴스가 갱신하면 자신의 L1 과 L2 는 최신이지만 다른 인스턴스의 L1 은 stale 이다. 표준 해법은 Redis pub/sub 브로드캐스트다.

```java
@Component
public class CacheInvalidationListener implements MessageListener {

	private final CacheManager localCacheManager;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		InvalidationEvent event = deserialize(message.getBody());
		if (event.getOriginNodeId().equals(NodeId.current())) {
			return;
		}
		Cache cache = localCacheManager.getCache(event.getCacheName());
		if (cache != null) {
			cache.evict(event.getKey());
		}
	}
}
```

pub/sub 은 at-most-once 전달이므로 메시지 유실 가능성이 있다. 따라서 **L1 TTL 을 L2 보다 짧게**(예: L1 30초, L2 10분) 잡아 유실되더라도 불일치 창을 L1 TTL 이하로 상한을 두는 것이 TTL 정합성의 원칙이다. Redis 6 의 client-side caching(RESP3 invalidation push)이나 Spring 생태계의 다층 캐시 라이브러리도 같은 원리 위에 있다.

Spring 의 `CompositeCacheManager` 는 여러 CacheManager 를 순서대로 조회해 **캐시 이름을 처음 보유한 매니저 하나** 를 반환하는 구조라, 캐시별로 로컬/원격을 나눠 배정하는 용도이지 L1/L2 폴스루를 만들어 주지는 않는다. 진짜 2단계가 필요하면 `Cache` 인터페이스를 직접 구현해 두 캐시를 감싸는 어댑터를 작성하거나 전용 라이브러리를 쓴다.

## 7. 통계와 모니터링

`recordStats()` 를 켜면 `cache.stats()` 가 `CacheStats` 스냅숏을 반환한다. 히트 수, 미스 수, 로드 성공/실패 수, 총 로드 시간, 축출 수(evictionCount)와 축출 무게를 제공하며, `hitRate()`, `averageLoadPenalty()` 같은 파생 지표를 계산해 준다. 통계 기록은 LongAdder 기반이라 오버헤드가 작지만 0 은 아니므로 빌더에서 명시적으로 켜야 한다.

Spring Boot 에서는 `CaffeineCacheManager` 에 `recordStats` 가 켜진 빌더를 넘기면 Actuator 의 `CacheMetricsRegistrar` 가 자동으로 Micrometer 에 바인딩해 `cache.gets{result=hit|miss}`, `cache.puts`, `cache.evictions`, `cache.size` 메트릭을 노출한다.

```java
@Bean
public CacheManager cacheManager() {
	CaffeineCacheManager cacheManager = new CaffeineCacheManager("product", "category");
	cacheManager.setCaffeine(Caffeine.newBuilder()
		.maximumSize(10_000L)
		.expireAfterWrite(Duration.ofMinutes(10L))
		.recordStats());
	return cacheManager;
}
```

운영에서 보는 순서는 명확하다. 히트율이 낮으면 먼저 `evictionCount` 를 본다. 축출이 많으면 용량 부족(사이즈 증설 또는 weigher 재조정), 축출이 적은데도 미스가 많으면 TTL 이 과도하게 짧거나 키 분포 자체가 캐시 친화적이지 않은 것이다. `averageLoadPenalty` 가 크면 미스 1회의 비용이 크다는 뜻이므로 스탬피드 방어(refresh, sync)를 우선 적용한다. 히트율 1%p 개선이 원본 QPS 를 수십 % 줄이는 경우가 흔하므로(미스율 5% → 4% 는 원본 트래픽 20% 감소) 히트율은 백분율이 아니라 **미스율의 상대 변화** 로 읽어야 한다.

## 8. Ehcache/Guava 와의 비교와 Caffeine 채택 근거

| 항목 | Guava Cache | Ehcache 3 | Caffeine |
|------|-------------|-----------|----------|
| 축출 정책 | 세그먼트별 LRU | LRU 계열(설정형) | W-TinyLFU(적응형) |
| 만료 | write/access | TTL/TTI | write/access/variable(TimerWheel) + refresh |
| 비동기 API | 없음 | 제한적 | AsyncCache, CompletableFuture 네이티브 |
| 오프힙/디스크 | 없음 | 지원(계층형 저장) | 없음(온힙 전용) |
| JSR-107 | 미지원 | 지원 | 어댑터(jcache 모듈) 지원 |
| 동시성 구조 | 세그먼트 락 | 세그먼트/스토어 락 | 버퍼 + drain(읽기 경로 락프리에 근접) |

Guava Cache 는 Caffeine 의 직계 전신으로 API 가 거의 같지만, 세그먼트 락과 LRU 때문에 처리량과 히트율 모두 밀린다. Caffeine 공식 벤치마크(JMH, Zipfian 분포 읽기 위주 워크로드)에서 Caffeine 은 Guava 대비 수 배 이상의 처리량을 기록했고, 상한선인 무정책 ConcurrentHashMap 에 가장 근접한다. 히트율 시뮬레이션에서도 동일 용량에서 LRU(Guava·Ehcache 온힙) 대비 W-TinyLFU 가 일관되게 우위였다. Spring Framework 도 4.3 에서 Guava Cache 지원을 deprecated 하고 Caffeine 으로 대체했으며, Spring Boot 의 로컬 캐시 자동 구성 1순위도 Caffeine 이다.

Ehcache 가 여전히 유효한 경우는 오프힙·디스크 계층으로 힙보다 큰 데이터를 담아야 하거나 JSR-107 표준 준수가 계약 조건일 때다. 반면 "JVM 힙 안에서 가장 높은 히트율과 처리량"이 목표라면 Caffeine 이 표준적 선택이고, 분산 일관성이 필요한 부분은 6장의 2단계 구성으로 Redis 와 역할을 나누는 것이 현재의 지배적 아키텍처다.

## 참고
- Caffeine GitHub Wiki - Efficiency, Eviction, Refresh: https://github.com/ben-manes/caffeine/wiki
- Einziger, Friedman, Manes, "TinyLFU: A Highly Efficient Cache Admission Policy" (ACM ToS, 2017): https://arxiv.org/abs/1512.00727
- Vattani, Chierichetti, Lowenstein, "Optimal Probabilistic Cache Stampede Prevention" (VLDB 2015): https://www.vldb.org/pvldb/vol8/p886-vattani.pdf
- Spring Framework Reference - Cache Abstraction: https://docs.spring.io/spring-framework/reference/integration/cache.html
- Spring Boot Reference - Caching: https://docs.spring.io/spring-boot/reference/io/caching.html
