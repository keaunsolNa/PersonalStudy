Notion 원본: https://app.notion.com/p/3535a06fd6d381b8b9dac8ce2ee15514

# Consistent Hashing과 Jump Hash — 가상 노드 분산 라우팅

> 2026-05-01 신규 주제 · 확장 대상: CS / 분산시스템

## 학습 목표

- Consistent Hashing 의 ring 구조와 노드 추가/삭제 시 키 이동량 비율 분석
- Virtual Node(vnode) 가 균일성을 어떻게 보정하는지 수치로 검증
- Google Jump Hash 알고리즘의 O(ln N) 비용과 한계 이해
- Maglev, Rendezvous(HRW) 해싱과의 비교 — 어떤 상황에 어떤 알고리즘을 고를지 결정

## 1. 모듈로 해싱이 깨지는 이유

`hash(key) % N` 로 N개 노드에 분배하면, N 이 변할 때 **거의 모든 키가 다른 노드로 이동**한다. N=4 → N=5 로 바꾸면 모듈로 결과가 뒤섞여 약 80% 이상의 키가 재배치된다. 캐시 시스템에서는 곧바로 cache stampede(원본 DB 폭주)로 이어진다.

이상적인 분배 알고리즘의 목표는 단순하다.

- 균일성: 각 노드에 대략 1/N 만큼의 키가 할당된다.
- 최소 이동: 노드 1개 추가/삭제 시 약 1/N 의 키만 이동한다.
- 결정성: 같은 입력은 항상 같은 노드로 매핑된다.
- 합리적 비용: 매핑 시간이 O(log N) 이하.

Consistent Hashing(David Karger, 1997) 이 이 목표를 처음 풀었다. Akamai CDN 의 핵심 알고리즘이고, Memcached 클라이언트, Cassandra, DynamoDB, Riak 의 partitioner 가 변형을 채택한다.

## 2. Ring 기본 알고리즘

각 노드와 키를 같은 해시 함수로 0~2^32-1 범위(또는 2^64)에 매핑한다. 키가 들어오면 해시값보다 크거나 같은 첫 노드(시계 방향으로 가장 가까운 노드)에 할당한다.

```
   ring 위치 →

   노드 A: hash("nodeA") = 1000
   노드 B: hash("nodeB") = 5000
   노드 C: hash("nodeC") = 9000
   노드 D: hash("nodeD") = 13000

   키 K1: hash("k1") = 3500  → A 다음 = B
   키 K2: hash("k2") = 6200  → B 다음 = C
   키 K3: hash("k3") = 12500 → C 다음 = D
   키 K4: hash("k4") = 14500 → D 다음 (wrap) = A
```

노드 B 가 빠지면 B 에 있던 키만 다음 노드 C 로 이동한다. 다른 노드들의 매핑은 전혀 변하지 않는다. 이게 **최소 이동성** 의 본질이다.

```python
import hashlib
from sortedcontainers import SortedDict

class ConsistentHash:
    def __init__(self):
        self.ring = SortedDict()  # hash → node

    def _h(self, key):
        return int(hashlib.md5(key.encode()).hexdigest(), 16)

    def add_node(self, node):
        self.ring[self._h(node)] = node

    def remove_node(self, node):
        self.ring.pop(self._h(node), None)

    def get_node(self, key):
        if not self.ring:
            return None
        h = self._h(key)
        idx = self.ring.bisect_right(h)
        if idx == len(self.ring):
            idx = 0
        return self.ring.values()[idx]
```

`SortedDict.bisect_right` 로 O(log N) 검색. 노드 추가/삭제도 O(log N).

## 3. 균일성 문제와 Virtual Nodes

위 단순 구현은 N=4 노드일 때 ring 위치가 균등하게 분포하지 않는다. 무작위 해시는 분산이 크다 — 노드 A 가 ring 의 50% 를, 노드 D 가 5% 만 책임지는 일이 흔하다.

**Virtual Nodes(vnode)** 는 각 물리 노드를 ring 에 V 번(보통 100~200) 등록한다. `nodeA#1`, `nodeA#2`, ..., `nodeA#150` 각각의 해시값으로 ring 에 점이 찍히므로, 시각적으로 보면 ring 이 훨씬 촘촘해지고 분배가 균일해진다.

```python
def add_node(self, node, virtual=150):
    for i in range(virtual):
        h = self._h(f"{node}#{i}")
        self.ring[h] = node
```

V 값에 따른 분배 표준편차(N=10 노드, 1M 키 시뮬레이션):

| V (vnodes per node) | 분배 표준편차 (% of mean) |
|---|---|
| 1 | ~70% (매우 불균일) |
| 10 | ~30% |
| 100 | ~10% |
| 200 | ~7% |
| 500 | ~5% |
| 1000 | ~3% |

V=200 정도면 충분하다. 더 늘리면 메모리 비용(SortedDict 크기)만 증가한다. Cassandra 기본값은 256 vnodes/node, Riak 은 64.

노드 1개를 빼면 키 이동량은 1/N 그대로 유지된다 — vnode 가 N*V 개로 많아져도 어떤 노드의 vnode 들이 빠질 때 인접 vnode(다른 노드 소속)가 이어받기 때문에 평균 이동량이 늘지 않는다.

## 4. Jump Consistent Hash — O(ln N) 메모리 0

Google 의 John Lamping & Eric Veach(2014) 가 제안한 알고리즘. 노드 리스트가 0..N-1 정수 ID 라는 가정 하에 ring/vnode 자료구조 없이 키 → 노드 ID 를 직접 계산한다.

```c
int32_t jump_consistent_hash(uint64_t key, int32_t num_buckets) {
    int64_t b = -1, j = 0;
    while (j < num_buckets) {
        b = j;
        key = key * 2862933555777941757ULL + 1;
        double r = (double)((key >> 33) + 1) / (double)(1LL << 31);
        j = floor((b + 1) / r);
    }
    return b;
}
```

핵심 통찰: N=k → N=k+1 로 바꿀 때, 정확히 1/(k+1) 의 키가 새 노드로 이동한다. 위 함수는 그 확률을 PRNG 로 시뮬레이션해 마지막으로 "옮겨진" 노드 ID 를 반환한다. 시간 복잡도 O(ln N), 공간 복잡도 O(1).

성능: 백만 키 매핑이 N=100 일 때 0.1ms 미만. ring 기반 구현 대비 10배 빠르고 메모리 사용 0.

**한계**: 노드 ID 가 0..N-1 정수여야 한다. 임의 노드를 중간에서 빼면(예: 노드 5 삭제) 뒤의 모든 노드 ID 가 한 칸씩 당겨지면서 배분이 다 바뀐다. 따라서 Jump Hash 는 "노드 추가만 하는" 구조(append-only)에 적합하다. Vitess, Google 일부 내부 시스템이 사용.

## 5. Rendezvous Hashing (HRW)

또 다른 후보. 각 노드 N에 대해 `hash(key, node_id)` 를 계산하고 **최댓값을 갖는 노드** 를 선택한다.

```python
def rendezvous(key, nodes):
    return max(nodes, key=lambda n: hash(f"{key}|{n}"))
```

장점: 임의 노드 추가/삭제에 1/N 키만 이동. 코드가 ring 보다 훨씬 단순.
단점: 매번 N 개 노드 모두에 해싱 — O(N) 비용. 노드가 100 개 넘으면 ring 보다 느려진다.

소규모 클러스터(N < 50)나 cache 라우팅처럼 동적 멤버십이 잦은 환경에서 유리하다.

## 6. Maglev Hashing — Google L4 LB 의 알고리즘

Maglev(Eisenbud et al., NSDI 2016) 는 Google 자체 L4 로드밸런서가 쓴다. Lookup table M(보통 65537, 소수)을 사전 계산하고 매핑은 단순 `table[hash(key) % M]` 로 한다.

테이블 생성:
1. 각 노드는 두 정수 (offset, skip) 를 갖는다 — 다른 PRNG seed 로 계산.
2. 라운드 로빈으로 각 노드가 (offset + i*skip) % M 자리를 차지한다 — 충돌 시 다음 자리로.
3. M 자리가 모두 채워지면 종료.

이 테이블은 매우 균일하고(대부분 노드가 M/N ± 1 자리), 노드 변경 시 평균 100/N % 의 자리만 재배치된다. lookup 은 O(1).

쿠버네티스 IPVS 모드, Cilium 의 외부 LB 가 Maglev 변형을 쓴다. 트래픽 분산 균일성과 lookup 속도가 모두 중요한 환경에서 최적.

## 7. 알고리즘 선택 매트릭스

| 알고리즘 | lookup | add/remove | 균일성 | 임의 노드 제거 | 메모리 |
|---|---|---|---|---|---|
| 모듈로 N | O(1) | 모든 키 이동 | 완벽 | OK | O(1) |
| Ring | O(log N) | 1/N 이동 | vnode 필요 | OK | O(N*V) |
| Jump Hash | O(ln N) | 1/N 이동 (append만) | 완벽 | ❌ | O(1) |
| Rendezvous | O(N) | 1/N 이동 | 완벽 | OK | O(1) |
| Maglev | O(1) | 100/N % 이동 | 거의 완벽 | OK | O(M=65537) |

## 8. 실전 — Memcached 클라이언트 분배

Spymemcached, ketama 등 대부분 클라이언트가 ring + 160 vnode 기본 설정을 쓴다. 이게 안정성과 성능의 균형점이다. 대안으로 Twemproxy(twitter 의 memcached/redis proxy)는 modulo, ketama, fnv 등 여러 알고리즘을 옵션으로 제공한다.

```yaml
# twemproxy(nutcracker) config
my-cluster:
  listen: 0.0.0.0:22121
  hash: fnv1a_64
  distribution: ketama
  servers:
    - 10.0.0.1:11211:1 server1
    - 10.0.0.2:11211:1 server2
    - 10.0.0.3:11211:1 server3
```

`distribution: ketama` 가 ring + vnode. 가중치(`:1`) 가 vnode 수에 곱해져 더 강한 노드에 더 많은 자리를 준다.

## 9. 분산 시스템에서의 응용

- **Cassandra**: Murmur3Partitioner + 256 vnodes/node. token range 가 ring 의 vnode 영역.
- **DynamoDB**: 내부적으로 consistent hashing + replication factor 3.
- **Riak**: 256 vnode/node 기본. claim algorithm 으로 vnode 분배.
- **Akamai/Cloudflare CDN**: 클라이언트 IP → edge node 매핑. 사용자별 캐시 워밍을 위한 안정성 필수.
- **Service Mesh L7 LB(Envoy)**: `ring_hash` 와 `maglev` 두 LB 정책을 둘 다 지원. session affinity 가 필요할 때 선택.

## 10. 트레이드오프 정리와 운영 팁

- **vnode 수 결정**: 노드가 적고 균일성이 중요하면 V=200~500. 노드가 많고 메모리가 비싸면 V=64.
- **해시 함수 선택**: MD5(ring 표준), Murmur3(빠름), xxHash(매우 빠름, 균일). 암호 해시는 불필요 — 보안 목적이 아니라 분산이 목적.
- **노드 가중치**: 다른 사양의 노드가 섞여 있으면 vnode 수에 가중치를 곱해 강한 노드에 더 많이 할당.
- **재배치 모니터링**: 노드 변경 시 키 이동량을 측정해 알고리즘이 약속대로 움직이는지 확인. cache hit rate 가 잠시 떨어졌다 회복되는 그래프가 정상.
- **Sticky session vs reshard**: session affinity 가 필요한 경우 ring_hash 가 정답. stateless workload 라면 정확한 분배가 더 중요한 maglev 유리.

## 참고

- David Karger et al., "Consistent Hashing and Random Trees" (1997) — https://www.akamai.com/research/papers/CHRT.pdf
- John Lamping & Eric Veach, "A Fast, Minimal Memory, Consistent Hash Algorithm" (Jump Hash) — https://arxiv.org/abs/1406.2294
- Maglev: A Fast and Reliable Software Network Load Balancer — https://research.google/pubs/pub44824/
- Damian Gryski, "Consistent Hashing: Algorithmic Tradeoffs" — https://dgryski.medium.com/consistent-hashing-algorithmic-tradeoffs-ef6b8e2fcae8
- Riak Core 코드(claim algorithm) — https://github.com/basho/riak_core
- Envoy `ring_hash` 와 `maglev` 정책 — https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/load_balancers
