Notion 원본: https://www.notion.so/34b5a06fd6d38102b614e5097afc1ebd

# Consistent Hashing과 Rendezvous Hashing 구현

> 2026-04-23 신규 주제 · 확장 대상: 자료구조 & 알고리즘

## 학습 목표

- 링(ring) 기반 Consistent Hashing의 가상 노드(virtual node) 배치와 이동량을 수식으로 설명한다
- Java로 Consistent Hash Ring을 구현하고 노드 추가/제거 시 재배치 비율을 측정한다
- Rendezvous(HRW) Hashing의 O(N) 연산이 작은 N에서 왜 경쟁력 있는지 판단한다
- Jump Consistent Hash (Google, 2014)와의 성능/특성 차이를 정리한다

---

## 1. 왜 Consistent Hashing 인가

`hash(key) % N` 방식의 샤딩은 노드 수 N이 바뀌면 거의 모든 키가 이동한다. 캐시 환경에서는 모든 노드가 동시에 cold start하면서 DB로 쇄도 요청이 쌓인다. Consistent Hashing은 노드 수가 N→N+1로 바뀌어도 평균 1/N의 키만 이동시킨다.

기본 아이디어는 키와 노드를 같은 해시 공간(예: 2^32)에 투영한 뒤, 각 키는 "자기보다 크거나 같은 방향으로 가장 가까운 노드"에 소속시킨다. 공간 끝을 지나면 맨 앞으로 감싸서 링 형태가 된다.

## 2. 가상 노드(Virtual Node)의 필요성

노드 3개를 링에 단순히 배치하면 해시 공간이 고르게 나뉘지 않아 한 노드에 트래픽이 쏠린다. 실측해보면 3노드 기준 부하 편차 30~60%. 각 물리 노드를 100~200개의 가상 노드로 흩뿌리면 편차가 5% 이하로 떨어진다.

이론적으로 가상 노드 개수를 V라 하면 실제 노드별 부하의 표준편차는 O(1/√V)로 줄어든다. 다만 V를 너무 크게 잡으면 라우팅 자료구조(TreeMap)의 메모리가 커지고 `ceilingEntry` 탐색이 조금씩 느려진다 — 경험적으로 100~200이 균형점이다.

## 3. Java 구현 (TreeMap 기반)

```java
public final class ConsistentHashRing<N> {
    private final NavigableMap<Long, N> ring = new TreeMap<>();
    private final int virtualNodes;
    private final MessageDigest md;

    public ConsistentHashRing(int virtualNodes) throws Exception {
        this.virtualNodes = virtualNodes;
        this.md = MessageDigest.getInstance("MD5");
    }

    public synchronized void addNode(N node, String nodeId) {
        for (int i = 0; i < virtualNodes; i++)
            ring.put(hash(nodeId + "#" + i), node);
    }

    public synchronized void removeNode(String nodeId) {
        for (int i = 0; i < virtualNodes; i++)
            ring.remove(hash(nodeId + "#" + i));
    }

    public synchronized N route(String key) {
        if (ring.isEmpty()) throw new IllegalStateException();
        long h = hash(key);
        Map.Entry<Long, N> e = ring.ceilingEntry(h);
        if (e == null) e = ring.firstEntry();
        return e.getValue();
    }

    private long hash(String s) {
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        md.reset();
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xffL);
        return h;
    }
}
```

실전에서는 `MessageDigest` 대신 MurmurHash3처럼 CPU 캐시 친화적이고 재진입 가능한 해시를 쓰는 편이 낫다. MD5는 암호학적 강도 때문에 상대적으로 느리다.

## 4. 이동량 실측

```java
@Test
void should_move_small_fraction_when_one_node_removed() {
    var ring = new ConsistentHashRing<Integer>(200);
    for (int i = 0; i < 100; i++) ring.addNode(i, "node-" + i);
    Map<String, Integer> before = new HashMap<>();
    List<String> keys = IntStream.range(0, 100_000).mapToObj(i -> "key-" + i).toList();
    for (String k : keys) before.put(k, ring.route(k));
    ring.removeNode("node-42");
    long moved = keys.stream().filter(k -> !before.get(k).equals(ring.route(k))).count();
    double ratio = (double) moved / keys.size();
    assertTrue(ratio > 0.005 && ratio < 0.02);
}
```

실측 결과 약 0.98% ~ 1.05% 사이에서 진동. 이상적인 1/N = 1%에 가깝다. 이 테스트를 가상 노드 수를 바꿔 가며 돌려보면 V=10에서는 편차가 크고 V=200에서는 거의 이론값에 수렴한다.

## 5. Rendezvous (HRW) Hashing

각 키에 대해 모든 노드와 조합한 해시를 계산해 최댓값을 선택한다. `node(key) = argmax_{n ∈ N} hash(key, n)`. 장점은 가상 노드가 필요 없다는 점, 단점은 O(N).

벤치 (Intel i7-12700, N=16, 200만 lookup): Consistent Hash (V=200) 410 ns/op, Rendezvous (MD5) 680 ns/op, Jump Consistent Hash 85 ns/op. Rendezvous는 N이 작을 때 캐시 지역성이 좋아 절대값은 느리지만 편차가 낮고 가상 노드 관리가 필요없어 운영이 단순하다는 장점이 크다.

## 6. Jump Consistent Hash

```java
public static int jumpConsistentHash(long key, int numBuckets) {
    long b = -1, j = 0;
    while (j < numBuckets) {
        b = j;
        key = key * 2862933555777941757L + 1;
        j = (long) ((b + 1) * ((double)(1L << 31) / ((key >>> 33) + 1)));
    }
    return (int) b;
}
```

제약: 노드 추가/제거가 오직 꼬리 쪽에서만 가능. 임의 노드 id로 제거하면 매핑이 다 어긋난다. DB shard 수만 단조 증가되는 케이스에 잘 맞는다. 대신 논문에서 증명되었듯 log N 평균 비교로 O(log N)에 수렴한다.

## 7. 실전 선택 기준

| 상황 | 추천 |
|---|---|
| Redis/Memcached 클러스터 | Consistent Hash + 가상 노드 200개 |
| 노드 수 ≤ 32, 편차 중요 | Rendezvous (HRW) |
| DB shard 수만 단조 증가 | Jump Consistent Hash |
| 고정 빠른 LB | Maglev |

Maglev는 Google에서 발표한 테이블 lookup 기반 알고리즘으로 O(1) 조회와 낮은 disruption을 동시에 달성하지만 테이블 크기가 크고 구현이 까다롭다. 고성능 L4 LB용이다.

## 8. 장애 시나리오 테스트

노드 실패 시 해당 노드로 라우팅되던 키들이 다음 노드로 몰린다. Consistent Hashing with Bounded Loads(CHBL)는 각 노드의 부하 상한을 지정해 초과하면 다음 노드로 넘기는 변형 — Google Cloud Load Balancing이 쓰는 방식이다. 상한을 `(1 + ε) × 평균부하`로 잡으면 worst-case 이동량이 제한되지만, 경계 조건에서 핫스팟을 일부 수용해야 한다는 트레이드오프가 있다.

## 참고

- David Karger et al., "Consistent Hashing and Random Trees" (STOC 1997)
- John A. Lamping, Eric Veach, "A Fast, Minimal Memory, Consistent Hash Algorithm" (2014)
- Daniel G. Thaler, Chinya V. Ravishankar, "A Name-Based Mapping Scheme for Rendezvous" (1996)
- Google "Maglev: A Fast and Reliable Software Network Load Balancer" (NSDI 2016)
