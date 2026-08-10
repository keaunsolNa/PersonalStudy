Notion 원본: https://www.notion.so/3b85a06fd6d3818bbae1e556949a0bab

# Redis RESP3 프로토콜과 Client Side Caching 및 Invalidation 추적

> 2026-08-11 신규 주제 · 확장 대상: DB (Redis Stream/Cluster/Redisson 분산락 기학습)

## 학습 목표

- RESP2 대비 RESP3의 타입 체계와 out-of-band push 메시지 구조를 분석한다
- CLIENT TRACKING의 default/broadcasting/OPTIN 모드별 무효화 추적 방식을 비교한다
- Lettuce·Redisson으로 서버 어시스트 클라이언트 캐시를 구성하고 일관성 한계를 검증한다
- 로컬 캐시 + Redis 2계층(near cache) 아키텍처의 정합성·메모리 트레이드오프를 평가한다

## 1. 왜 RESP3인가 — 프로토콜이 캐시 아키텍처를 바꾼다

기학습한 Redis 캐시 패턴(look-aside, 스탬피드 방지)은 모두 "애플리케이션 → Redis 네트워크 왕복"을 전제한다. 왕복은 아무리 빨라도 수백 µs이고, 초당 수십만 조회가 같은 키에 몰리는 hot key 워크로드에서는 Redis CPU와 NIC이 함께 병목이 된다. 다음 단계는 **클라이언트 프로세스 안의 로컬 캐시**인데, 로컬 캐시의 고전적 난제는 무효화다 — 다른 인스턴스가 키를 바꿨을 때 내 로컬 사본을 언제 버릴 것인가.

Redis 6이 도입한 **server-assisted client-side caching**(tracking)은 이 무효화를 서버가 밀어주는 구조이고, 그 전송로가 **RESP3의 push 메시지**다. RESP2는 엄격한 요청-응답 프로토콜이라 서버가 임의 시점에 메시지를 보낼 수 없었다(Pub/Sub은 커넥션 전체를 구독 모드로 전환하는 예외). RESP3는 `>` 타입의 out-of-band push 프레임을 도입해 **하나의 커넥션에서 요청-응답과 서버 발신 알림이 공존**할 수 있게 했다. 프로토콜 전환은 `HELLO 3`:

```
HELLO 3                        → 서버 메타데이터 맵 반환, 이후 RESP3 모드
GET user:1                     → $-notation 대신 타입드 응답
> invalidate ["user:1"]        → (비동기) 키 무효화 push
```

RESP3는 push 외에도 타입 체계를 확장했다: 맵(`%`), 셋(`~`), double(`,`), big number(`(`), boolean(`#`), verbatim string(`=`) 등. RESP2에서 `HGETALL`이 평평한 배열로 와서 클라이언트가 쌍으로 재조립하던 것이 RESP3에서는 맵 타입으로 직접 온다 — 드라이버의 역직렬화 모호성이 사라지는 실질적 개선이다. Redis 7.x까지 기본 프로토콜은 여전히 RESP2이며 HELLO로 옵트인한다.

## 2. CLIENT TRACKING default 모드 — 서버가 읽은 키를 기억한다

기본 모드의 동작: `CLIENT TRACKING on`을 켨 커넥션이 키를 **읽으면**, 서버는 "이 클라이언트가 이 키를 캐싱 중"이라는 사실을 **Invalidation Table**(전역 radix tree)에 기록한다. 이후 누구든 그 키를 변경(WRITE, DEL, 만료, eviction)하면 서버가 해당 클라이언트에 `invalidate` push를 보내고 테이블에서 항목을 제거한다.

```
# 클라이언트 A (RESP3)
HELLO 3
CLIENT TRACKING on
GET user:1            ← 서버: "A가 user:1 추적" 기록. A는 응답을 로컬 캐시에 저장

# 클라이언트 B
SET user:1 "changed"  ← 서버: A에게 push → > invalidate ["user:1"]

# 클라이언트 A: push 수신 → 로컬 캐시에서 user:1 제거. 다음 조회는 Redis로
```

설계 트레이드오프는 **서버 메모리**다. 추적 항목은 키×클라이언트 단위로 쌓이므로, 서버는 `tracking-table-max-keys`(기본 100만)를 넘으면 무작위 항목을 제거하며 해당 클라이언트에 강제 invalidate를 보낸다. 즉 추적은 best-effort 캐시 힌트지 영구 계약이 아니다. 커넥션이 끊기면 그 클라이언트의 추적 정보도 사라지므로, **클라이언트는 재연결 시 로컬 캐시 전체를 flush해야 한다** — 끊긴 동안의 무효화를 놓쳤을 수 있기 때문이다. 이것이 구현 라이브러리들이 반드시 지키는 첫 번째 안전 규칙이다.

## 3. broadcasting 모드와 OPTIN/OPTOUT — 추적 비용의 재배치

**BCAST 모드**(`CLIENT TRACKING on BCAST PREFIX user: PREFIX session:`)는 서버가 클라이언트별 읽은 키를 기억하지 않는 대신, 지정한 프리픽스에 대한 **모든 변경**을 구독자 전원에게 방송한다. 서버 메모리는 프리픽스당 구독자 목록만으로 일정해지지만, 클라이언트는 자신이 캐싱하지 않은 키의 무효화까지 수신하므로 네트워크·처리 비용이 변경률에 비례해 커진다. 정리하면: default 모드는 "서버 메모리로 정밀도를 산다", BCAST는 "네트워크로 서버 메모리를 산다". 키 변경률이 낮고 클라이언트가 많은 참조 데이터(설정, 코드 테이블)는 BCAST가, 임의 키를 넓게 읽는 워크로드는 default가 맞다.

**OPTIN**(`CLIENT TRACKING on OPTIN`)은 모든 읽기를 추적하는 대신 `CLIENT CACHING yes`를 선행한 다음 명령 하나만 추적한다. 로컬 캐시에 실제로 넣을 키만 선별 추적해 invalidation table을 아끼는 정밀 제어다. OPTOUT은 반대. 또 하나의 중요한 변형이 **redirect 모드**(`CLIENT TRACKING on REDIRECT <client-id>`)로, 무효화 push를 데이터 커넥션이 아닌 별도 커넥션(RESP2 Pub/Sub `__redis__:invalidate` 채널)으로 우회시킨다 — RESP2만 지원하는 레거시 드라이버나, 커넥션 풀 환경에서 "풀의 어느 커넥션이 push를 받나" 문제를 전용 커넥션 하나로 정리하는 실전 패턴이다.

```
# 전용 무효화 커넥션 (id=7)로 우회
CLIENT ID                      → 7   (무효화 수신 전용 커넥션에서)
SUBSCRIBE __redis__:invalidate

# 데이터 커넥션(풀)에서
CLIENT TRACKING on REDIRECT 7
```

## 4. 만료·eviction·flushall과 무효화의 완전성

무효화 push가 발생하는 사건의 전체 목록을 정확히 알아야 정합성 논증이 가능하다: 명시적 쓰기(SET/DEL/HSET/…), TTL 만료(lazy/active expire 시점), maxmemory eviction, FLUSHALL/FLUSHDB(null invalidate = 전체 무효화), 그리고 tracking table 축출. 주의할 미묘한 지점 두 가지. 첫째, **TTL 만료의 push는 서버가 만료를 인지한 시점**에 나간다 — active expire cycle이 도는 주기(기본 100ms 틱, 적응형)나 다음 접근 시점까지 지연될 수 있으므로, 로컬 캐시는 서버 push만 믿지 말고 **자체 TTL 상한**을 함께 가져야 한다. 둘째, 복제 토폴로지에서 tracking은 **마스터 기준**이다. 레플리카에서 읽는 클라이언트의 무효화 전파는 마스터→레플리카 복제 스트림의 DEL 전파에 의존하므로 복제 지연만큼 stale 윈도가 생긴다.

일관성 수준을 명시하면: server-assisted caching은 **최종 일관성**이다. 쓰기 완료 → push 도착 → 로컬 삭제 사이에는 수 ms의 윈도가 있고, 이 동안 다른 인스턴스는 stale 값을 읽을 수 있다. read-your-writes가 필요한 경로(방금 쓴 값을 즉시 읽는 UI 흐름)는 로컬 캐시를 우회해 Redis를 직접 치거나, 쓰기 시 로컬 캐시를 동기 무효화하는 write-through 로컬 정책을 결합해야 한다.

## 5. Lettuce로 구현 — ClientSideCaching API

Spring Data Redis의 기본 드라이버인 Lettuce는 6.x부터 `ClientSideCaching`을 제공한다:

```java
RedisClient client = RedisClient.create("redis://localhost");
StatefulRedisConnection<String, String> conn = client.connect();  // RESP3 기본 협상

Map<String, String> localStore = new ConcurrentHashMap<>();
CacheFrontend<String, String> frontend = ClientSideCaching.enable(
	CacheAccessor.forMap(localStore),
	conn,
	TrackingArgs.Builder.enabled().bcast().prefixes("user:"));

String v1 = frontend.get("user:1");   // miss → Redis GET → 로컬 저장
String v2 = frontend.get("user:1");   // 로컬 히트 (네트워크 0회)
// 다른 프로세스가 SET user:1 → push 수신 → localStore에서 자동 제거
```

프로덕션에서는 `ConcurrentHashMap` 대신 Caffeine을 CacheAccessor로 감싸 **크기 상한 + TTL 상한**을 강제한다(기학습한 W-TinyLFU가 여기서 재등장). Lettuce 구현이 처리해 주는 것: RESP3 push 리스너 등록, 재연결 시 로컬 캐시 flush. 처리해 주지 않는 것: 캐시 스탬피드(로컬 miss 폭주 시 동일 키 중복 로드), 널 캐싱 정책 — 이는 Caffeine의 `LoadingCache` 단일 플라이트로 보완한다.

Redisson은 같은 문제를 `RLocalCachedMap`으로 푼다. tracking 이전부터 Pub/Sub 토픽 기반 자체 무효화(`SyncStrategy.INVALIDATE`)를 제공했고, 최신 버전은 `localCacheOptions.useTrackingInvalidation(true)` 설정으로 Redis 6 tracking 경로도 선택 가능하다. Redisson 경로의 장점은 Map API·EvictionPolicy·storeMode 같은 상위 기능이 묶여 있다는 것, 단점은 해시 하나에 맵 전체를 담는 모델이라 키 단위 tracking보다 무효화 입도가 거칠 수 있다는 것이다.

## 6. Spring 통합 — @Cacheable 2계층 구성

Spring Cache 추상화 기학습 내용과 연결하면, 목표는 `@Cacheable`이 L1(Caffeine, tracking 무효화) → L2(Redis) 순서로 조회하는 CacheManager 합성이다:

```java
@Bean
public CacheManager cacheManager(RedisClient redisClient) {
	StatefulRedisConnection<String, byte[]> conn = redisClient.connect(
		RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));

	return new AbstractCacheManager() {
		@Override
		protected Collection<? extends Cache> loadCaches() {
			Caffeine<Object, Object> l1 = Caffeine.newBuilder()
				.maximumSize(10_000)
				.expireAfterWrite(Duration.ofMinutes(5));   // push 유실 대비 상한
			return List.of(new NearCache("user", l1, conn));   // 커스텀: L1 miss → L2 GET
		}
	};
}
```

핵심 설계 결정은 세 가지다. (1) **L1 TTL은 짧게** — push는 최적화이지 정합성 보장이 아니므로, 허용 stale 상한을 L1 TTL로 명문화한다. (2) **직렬화 경계** — L1은 역직렬화된 객체를, L2는 바이트를 저장하므로 L1 히트는 역직렬화 비용까지 절약한다(이것이 near cache의 숨은 이득으로, JSON 역직렬화가 무거운 큰 DTO에서 GET 왕복 절약보다 크게 나오기도 한다). (3) **쓰기 경로** — `@CacheEvict`는 L2 DEL만 하면 된다. DEL이 tracking push를 유발해 모든 인스턴스의 L1이 함께 비워진다 — 인스턴스 간 L1 무효화 브로드캐스트를 직접 만들 필요가 없다는 것이 tracking 도입의 최대 수혜다.

## 7. 성능·용량 산정 — 언제 이득이 나는가

수치 감각: 로컬 L1 히트는 수백 ns(맵 조회 + 없음 역직렬화), Redis GET은 동일 AZ 기준 300µs~1ms. 히트당 3자릿수 배율의 이득이므로, **L1 히트율이 조금만 나와도 왕복 절감은 크다**. 반면 비용은 (a) 서버 invalidation table 메모리(default 모드: 항목당 수십 바이트 × 활성 추적 키 × 클라이언트), (b) 무효화 push 트래픽(BCAST: 프리픽스 변경률 × 구독 인스턴스 수), (c) 각 인스턴스의 L1 힙 사용량과 GC 영향이다.

적합 판정 기준을 정리하면: 읽기:쓰기 비율이 높고(10:1↑), 키 접근이 파레토 분포(소수 hot key)이며, ms 단위 stale을 업무가 허용하는 데이터 — 상품 카탈로그, 권한/설정, 세션 읽기 경로 — 에서 최대 이득. 반대로 쓰기 빈번(카운터, 재고), 강한 read-your-writes 요구, 키 공간이 넓고 균등 접근(히트율 낮음)이면 tracking 오버헤드만 내고 이득이 없다. 도입 시 계측 항목: L1 히트율, invalidate push 수신율, `MEMORY STATS`의 tracking 테이블 크기, 그리고 stale 감지용 카나리(주기적으로 쓰고 다른 인스턴스에서 읽어 지연 측정).

## 8. 운영 체크리스트와 장애 모드

**재연결 flush 확인**: 드라이버가 재연결 시 L1을 비우는지 통합 테스트로 검증한다(장애 주입: `CLIENT KILL` 후 stale 읽기 여부). **tracking-table-max-keys 모니터링**: 상한 도달 시 강제 무효화 폭주로 L1 히트율이 급락한다 — `INFO clients`의 `tracking_clients`, `MEMORY STATS` 추적. **Cluster 환경**: tracking은 노드 단위다. 슬롯 리샤딩(기학습)으로 키가 다른 노드로 이동하면 원 노드의 추적 정보는 의미를 잃는다 — 마이그레이션 윈도의 무효화 유실 가능성 때문에 L1 TTL 상한이 재차 중요해진다. **프록시 경유(Envoy, Twemproxy)**: push 프레임을 투과하지 못하는 프록시가 있어 tracking 자체가 불가할 수 있다 — 도입 전 경로 전체의 RESP3 투과성을 확인한다. **관측성**: invalidate 수신을 메트릭으로 노출하지 않으면 "무효화가 안 오는" 장애(프록시, redirect 커넥션 사망)를 조용히 겪는다. redirect 모드라면 전용 커넥션의 생존을 헬스체크에 포함한다.

## 9. 정리 — 기학습 체계 안에서의 위치

Redis 학습 굤적에서 이 주제의 위치를 정의하면: 캐시 패턴(look-aside/스탬피드) → 분산 자료구조(Stream/락) → 클러스터 토폴로지에 이어, tracking은 **"Redis 앞의 마지막 1홉을 제거하는" 계층**이다. Spring 스택 기준 권장 구성은 Lettuce + Caffeine L1(짧은 TTL) + default 모드(좁은 hot set) 또는 BCAST(참조 데이터 프리픽스), 쓰기는 L2 DEL만 수행해 push로 전 인스턴스 L1을 정리하는 형태다. 다음 확장 깊이로는 RESP3 push를 활용하는 다른 기능(클러스터 토폴로지 갱신 push), kvrocks/Dragonfly 등 호환 구현의 tracking 지원 차이, 그리고 CDN edge까지 무효화를 연장하는 계층 설계가 이어진다.

## 참고

- Redis 공식 문서 — Client-side caching: https://redis.io/docs/latest/develop/clients/client-side-caching/
- Redis 공식 문서 — RESP3 명세: https://redis.io/docs/latest/develop/reference/protocol-spec/
- CLIENT TRACKING 커맨드 레퍼런스: https://redis.io/docs/latest/commands/client-tracking/
- Lettuce Reference — Client-side caching: https://lettuce.io/core/release/reference/
- Redisson Wiki — Local cached Map: https://github.com/redisson/redisson/wiki/7.-distributed-collections
