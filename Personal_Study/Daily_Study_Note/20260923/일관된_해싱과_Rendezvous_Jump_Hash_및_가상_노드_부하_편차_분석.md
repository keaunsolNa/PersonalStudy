Notion 원본: https://www.notion.so/3e45a06fd6d381da903cef15a7020d8a

# 일관된 해싱과 Rendezvous·Jump Hash 및 가상 노드 부하 편차 분석

> 2026-09-23 신규 주제 · 확장 대상: Redis

## 학습 목표

- 모듈로 샤딩 대비 일관된 해싱이 줄이는 재배치량을 식으로 유도한다
- 가상 노드 수와 부하 표준편차의 관계를 실측으로 확인한다
- Rendezvous(HRW)·Jump Consistent Hash·Maglev 를 비용과 제약으로 비교한다
- 유계 부하 일관된 해싱으로 핫 키 편중을 제어한다

## 1. 모듈로 샤딩이 무너지는 지점

키를 노드에 배정하는 가장 단순한 방법은 `node = hash(key) % N` 이다. 분포는 균등하고 계산은 O(1)이다. 문제는 N 이 바뀔 때 드러난다.

N=4 에서 N=5 로 바꾸면, 키가 같은 노드에 남을 확률은 대략 1/5 다. 즉 **약 80% 의 키가 이동한다**. 일반화하면 N → N+1 에서 유지되는 비율은 약 1/(N+1) 이고, 이동량은 N/(N+1) 이다. 캐시라면 거의 전면 콜드 스타트이고, 데이터 저장소라면 전체 재분배다.

일관된 해싱은 이 이동량을 K/N 으로 줄인다(K = 전체 키 수, N = 노드 수). 노드 하나 추가 시 전체의 1/N 만 움직인다. 4노드에서 5노드로 갈 때 80% 가 아니라 20% 다.

```
모듈로:      이동 비율 = N / (N+1)        4→5 노드에서 80%
일관된 해싱: 이동 비율 = 1 / (N+1)        4→5 노드에서 20%
```

## 2. 링 구조와 가상 노드

일관된 해싱은 해시 출력 공간(예: 32비트 또는 64비트 정수)을 원형으로 보고, 노드와 키를 같은 함수로 링 위에 배치한다. 키는 자신의 위치에서 시계 방향으로 만나는 첫 노드에 귀속된다. 노드가 사라지면 그 노드가 담당하던 구간만 다음 노드로 넘어가고, 나머지 구간은 그대로다.

단, 노드를 링에 하나씩만 올리면 구간 길이가 매우 불균등해진다. N 개 점을 원 위에 무작위로 뿌릴 때 최대 구간은 평균의 O(log N) 배까지 커진다. 그래서 각 노드를 여러 개의 가상 노드(vnode)로 복제해 링에 올린다.

```java
public final class ConsistentHashRing<T> {

	private final NavigableMap<Long, T> ring = new TreeMap<>();
	private final int virtualNodeCount;
	private final Function<String, Long> hashFunction;

	public ConsistentHashRing(int virtualNodeCount, Function<String, Long> hashFunction) {
		this.virtualNodeCount = virtualNodeCount;
		this.hashFunction = hashFunction;
	}

	public void addNode(T node, String nodeKey) {
		for (int i = 0; i < virtualNodeCount; i++) {
			ring.put(hashFunction.apply(nodeKey + "#" + i), node);
		}
	}

	public void removeNode(T node, String nodeKey) {
		for (int i = 0; i < virtualNodeCount; i++) {
			ring.remove(hashFunction.apply(nodeKey + "#" + i));
		}
	}

	public T locate(String key) {
		if (ring.isEmpty()) {
			throw new IllegalStateException("ring is empty");
		}
		long hash = hashFunction.apply(key);
		Map.Entry<Long, T> entry = ring.ceilingEntry(hash);
		return entry != null ? entry.getValue() : ring.firstEntry().getValue();
	}

	public int ringSize() {
		return ring.size();
	}
}
```

```java
class ConsistentHashRingTest {

	private static long murmur(String value) {
		return Hashing.murmur3_128().hashUnencodedChars(value).asLong();
	}

	@Test
	void 노드_제거_시_다른_노드_소속_키는_유지된다() {
		ConsistentHashRing<String> ring = new ConsistentHashRing<>(160, ConsistentHashRingTest::murmur);
		List.of("n1", "n2", "n3", "n4").forEach(n -> ring.addNode(n, n));

		List<String> keys = IntStream.range(0, 100_000)
				.mapToObj(i -> "key-" + i)
				.toList();
		Map<String, String> before = keys.stream()
				.collect(Collectors.toMap(k -> k, ring::locate));

		ring.removeNode("n3", "n3");

		long moved = keys.stream()
				.filter(k -> !before.get(k).equals(ring.locate(k)))
				.count();
		long ownedByRemoved = before.values().stream().filter("n3"::equals).count();

		assertThat(moved).isEqualTo(ownedByRemoved);
	}

	@Test
	void 가상_노드_160개면_부하_편차가_10퍼센트_이내다() {
		ConsistentHashRing<String> ring = new ConsistentHashRing<>(160, ConsistentHashRingTest::murmur);
		List.of("n1", "n2", "n3", "n4", "n5").forEach(n -> ring.addNode(n, n));

		Map<String, Long> counts = IntStream.range(0, 200_000)
				.mapToObj(i -> ring.locate("key-" + i))
				.collect(Collectors.groupingBy(n -> n, Collectors.counting()));

		double mean = 200_000 / 5.0;
		double maxDeviation = counts.values().stream()
				.mapToDouble(c -> Math.abs(c - mean) / mean)
				.max().orElseThrow();

		assertThat(maxDeviation).isLessThan(0.10);
	}
}
```

첫 번째 테스트가 일관된 해싱의 본질을 그대로 검증한다 — 제거된 노드가 갖고 있던 키만 정확히 이동하고, 나머지는 한 건도 움직이지 않는다.

## 3. 가상 노드 수와 편차의 관계

가상 노드 수를 V 라 할 때, 노드별 부하의 상대 표준편차는 대략 1/√(V) 에 비례한다. 실측 감각을 표로 정리하면 다음과 같다(노드 10개, 키 100만 개, MurmurHash3 기준의 전형적 값).

| 가상 노드 수 | 최대 편차 | 상대 표준편차 | 링 엔트리 수 | 조회 비용 |
|---|---|---|---|---|
| 1 | 약 200% | 약 0.9 | 10 | O(log 10) |
| 10 | 약 60% | 약 0.30 | 100 | O(log 100) |
| 100 | 약 18% | 약 0.10 | 1,000 | O(log 1000) |
| 160 | 약 12% | 약 0.08 | 1,600 | O(log 1600) |
| 1,000 | 약 5% | 약 0.03 | 10,000 | O(log 10000) |

실무 선택값이 100~200 근처에 몰리는 이유가 보인다. Ketama(memcached 의 표준 구현)가 노드당 160 포인트를 쓰는 것, Cassandra 가 vnode 기본값으로 16~256 사이를 쓰는 것이 모두 이 구간이다. V 를 더 올려도 편차 개선은 √ 스케일로 둔화되는 반면, 링 메모리와 노드 추가/제거 비용은 선형으로 증가한다.

이 균일성은 노드 용량이 동일하다는 전제에 기대다. 이기종 노드(메모리 64GB 와 16GB 혼재)라면 가상 노드 수를 용량에 비례해 배정하는 가중 일관된 해싱을 쓴다 — 64GB 노드에 320개, 16GB 노드에 80개 식이다.

## 4. Rendezvous Hashing (HRW)

링 대신 "모든 노드에 대해 점수를 계산하고 최댓값을 고르는" 방식이 Rendezvous(Highest Random Weight) 해싱이다.

```java
public final class RendezvousHash<T> {

	private final List<T> nodes;
	private final ToLongBiFunction<T, String> scoreFunction;

	public RendezvousHash(List<T> nodes, ToLongBiFunction<T, String> scoreFunction) {
		this.nodes = List.copyOf(nodes);
		this.scoreFunction = scoreFunction;
	}

	public T locate(String key) {
		T best = null;
		long bestScore = Long.MIN_VALUE;
		for (T node : nodes) {
			long score = scoreFunction.applyAsLong(node, key);
			if (score > bestScore) {
				bestScore = score;
				best = node;
			}
		}
		return best;
	}

	/**
	 * 상위 k개 노드를 반환한다. 복제본 배치에 사용한다.
	 */
	public List<T> locateTopK(String key, int k) {
		return nodes.stream()
				.sorted(Comparator.comparingLong((T n) -> scoreFunction.applyAsLong(n, key)).reversed())
				.limit(k)
				.toList();
	}
}
```

HRW 의 장점은 세 가지다. 첫째, 링과 가상 노드가 필요 없어 구조가 단순하고 부하 균일성이 이론적으로 완벽하다(해시 함수가 균등하다면). 둘째, 상위 k개를 자연스럽게 얻을 수 있어 복제본 배치에 그대로 쓰인다 — 링에서는 "시계 방향 다음 k개" 가 물리적으로 같은 랙에 몰릴 수 있는데 HRW 는 그런 상관이 없다. 셋째, 노드 추가/제거 시 이동량이 최소 보장을 만족한다.

단점은 조회가 O(N) 이라는 점이다. 노드 10개면 무시할 만하지만 1,000개면 키마다 1,000번 해시 계산이 필요해 링의 O(log(N·V)) 보다 불리해진다. 실무 경계선은 대략 수백 노드다. Kafka 의 일부 파티셔너, Ceph 의 CRUSH 계층 선택 등이 HRW 계열을 쓴다.

## 5. Jump Consistent Hash

Google 의 Jump Consistent Hash 는 메모리 0바이트, O(log N) 시간으로 키를 `[0, N)` 버킷에 배정한다. 코드가 놀랄 만큼 짧다.

```java
public final class JumpConsistentHash {

	private JumpConsistentHash() {
	}

	public static int bucketOf(long key, int numBuckets) {
		long b = -1;
		long j = 0;
		while (j < numBuckets) {
			b = j;
			key = key * 2862933555777941757L + 1L;
			j = (long) ((b + 1) * ((double) (1L << 31) / (double) ((key >>> 33) + 1)));
		}
		return (int) b;
	}
}
```

```java
class JumpConsistentHashTest {

	@Test
	void 버킷_증가_시_이동_비율이_이론값에_수렴한다() {
		int keys = 1_000_000;
		int moved = 0;
		for (int i = 0; i < keys; i++) {
			long k = mix(i);
			if (JumpConsistentHash.bucketOf(k, 10) != JumpConsistentHash.bucketOf(k, 11)) {
				moved++;
			}
		}
		double ratio = (double) moved / keys;
		assertThat(ratio).isBetween(0.085, 0.10); // 이론값 1/11 ≈ 0.0909
	}

	@Test
	void 부하가_균등하게_분포한다() {
		int[] counts = new int[16];
		for (int i = 0; i < 1_600_000; i++) {
			counts[JumpConsistentHash.bucketOf(mix(i), 16)]++;
		}
		double mean = 100_000.0;
		for (int c : counts) {
			assertThat(Math.abs(c - mean) / mean).isLessThan(0.01);
		}
	}
}
```

균일성은 사실상 완벽하고(편차 1% 미만), 메모리는 전혀 쓰지 않는다. 결정적 제약은 **버킷이 0..N-1 로만 표현되고, 제거는 오직 마지막 버킷에서만 가능하다**는 점이다. 중간 노드 하나가 죽어서 빠지는 시나리오를 표현할 수 없다.

따라서 Jump Hash 는 "논리 샤드 수를 고정하고 샤드를 물리 노드에 매핑하는" 2단계 구조에서 빛난다. 논리 샤드 1,024개를 Jump Hash 로 정하고, 샤드 → 노드 매핑은 별도 라우팅 테이블로 관리한다. 노드가 죽으면 그 노드가 맡던 샤드만 다른 노드로 재할당하면 되고, 키 → 샤드 매핑은 전혀 건드리지 않는다. Redis Cluster 의 16,384 해시 슬롯이 정확히 이 구조다.

## 6. Maglev 해싱

Google 의 L4 로드밸런서 Maglev 는 조회 테이블을 미리 만들어 O(1) 조회를 달성한다. 크기 M(소수, 노드 수의 100배 이상)인 테이블을 각 노드의 선호 순열(permutation)로 채운다.

각 노드 i 는 `offset = h1(name_i) mod M`, `skip = h2(name_i) mod (M-1) + 1` 로 자신의 선호 순열 `p_i(j) = (offset + j × skip) mod M` 을 만든다. 그다음 노드들이 순서대로 돌아가며 자신의 선호 위치 중 비어 있는 첫 칸을 차지한다.

| 방식 | 조회 시간 | 메모리 | 균일성 | 최소 이동 | 중간 노드 제거 |
|---|---|---|---|---|---|
| 모듈로 | O(1) | O(1) | 완벽 | ✗ | ○ |
| 링 + vnode | O(log NV) | O(NV) | V 의존 | ○ | ○ |
| Rendezvous | O(N) | O(N) | 우수 | ○ | ○ |
| Jump Hash | O(log N) | O(1) | 우수 | ○ | ✗(끝만) |
| Maglev | O(1) | O(M) | 우수 | 근사 | ○ |

Maglev 의 트레이드오프는 "최소 이동" 을 엄밀히 보장하지 않는다는 점이다. 노드 변경 시 테이블을 다시 채우면 소수의 키가 불필요하게 이동할 수 있다. 대신 조회가 배열 인덱싱 한 번이라 초당 수천만 패킷을 처리하는 L4 경로에 적합하다. 커넥션 단위 상태를 별도로 추적해 기존 커넥션은 이동시키지 않는 방식으로 보완한다.

## 7. 유계 부하 일관된 해싱

일관된 해싱은 키가 균등할 때만 부하가 균등하다. 특정 키가 전체 트래픽의 30% 를 차지하는 핫 키가 있으면 링을 아무리 잘 만들어도 그 노드가 죽는다.

Consistent Hashing with Bounded Loads 는 각 노드에 용량 상한 `C = ⌈(1 + ε) × 전체부하 / N⌉` 을 두고, 링에서 만난 노드가 이미 가득 차 있으면 다음 노드로 넘어가게 한다. ε 이 작으면 균형이 좋지만 이동이 지고, 크면 그 반대다. HAProxy 와 Envoy 가 이 알고리즘을 제공한다.

```yaml
# Envoy: 유계 부하 링 해시
lb_policy: RING_HASH
ring_hash_lb_config:
  minimum_ring_size: 1024
  maximum_ring_size: 8388608
  hash_function: XX_HASH
common_lb_config:
  consistent_hashing_lb_config:
    use_hostname_for_hashing: false
    hash_balance_factor: 125   # ε = 0.25 에 해당
```

`hash_balance_factor: 125` 는 "어떤 노드도 평균의 1.25배를 넘지 않는다" 는 의미다. 초과 시 오버플로가 다음 노드로 흘러간다. 세션 어피니티(같은 사용자 → 같은 백엔드)를 유지하면서도 편중을 막는 절충안이고, 어피니티가 완벽하지 않아도 되는 캐시 계층에 적합하다. 상태를 반드시 같은 노드에 유지해야 하는 경우라면 이 알고리즘은 쓸 수 없다.

## 8. 어느 것을 고를 것인가

의사결정을 조건으로 정리하면 다음과 같다.

**노드 수가 수십~수백이고 임의 노드가 빠질 수 있다** → 링 + 가상 노드(V=100~200). 구현이 널리 검증되어 있고 라이브러리 지원이 가장 많다.

**복제본 k개를 서로 다른 장애 도메인에 배치해야 한다** → Rendezvous. 상위 k개가 상관없이 뽑히고, 랙/AZ 제약을 점수 계산에 녹이기 쉽다.

**샤드 수가 고정이고 재배치를 2단계로 관리한다** → Jump Hash + 슬롯 테이블. 메모리 0, 균일성 최고. Redis Cluster·Vitess 계열의 접근.

**초당 수백만 이상 조회, L4 경로** → Maglev. O(1) 조회가 다른 모든 고려를 압도한다.

**핫 키가 존재하고 어피니티가 완벽할 필요는 없다** → 유계 부하 일관된 해싱.

어떤 경우든 반드시 측정해야 할 지표는 동일하다. 노드별 키 수의 상대 표준편차, 노드별 실제 트래픽(키 수가 아니라 요청 수)의 편차, 그리고 노드 추가/제거 시 실측 이동 비율이다. 키 수는 균등한데 트래픽이 편중되면 해싱 알고리즘의 문제가 아니라 접근 패턴의 문제이고, 이때 필요한 것은 알고리즘 교체가 아니라 핫 키 로컬 캐시나 키 분할이다.

## 참고

- Karger 외, "Consistent Hashing and Random Trees", STOC 1997
- Thaler, Ravishankar, "Using Name-Based Mappings to Increase Hit Rates", IEEE/ACM ToN 1998 (Rendezvous)
- Lamping, Veach, "A Fast, Minimal Memory, Consistent Hash Algorithm", arXiv:1406.2294
- Eisenbud 외, "Maglev: A Fast and Reliable Software Network Load Balancer", NSDI 2016
- Mirrokni, Thorup, Zadimoghaddam, "Consistent Hashing with Bounded Loads", SODA 2018
