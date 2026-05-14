Notion 원본: https://www.notion.so/3605a06fd6d381eca6a7c9ef4eaf46a3

# Consistent Hashing — 링 모델, Virtual Node, 그리고 Jump Consistent Hash의 메모리 0 구조

> 2026-05-14 신규 주제 · 확장 대상: CS

## 학습 목표

- modulo hashing 의 *rebalancing 폭발* 을 정량적으로 이해
- Consistent Hashing 의 ring 모델과 *virtual node* 가 분포를 어떻게 균일화하는지 설명
- Jump Consistent Hash 의 *메모리 O(1), 시간 O(log N)* 구조와 정수 산술만으로 작동하는 트릭
- 두 알고리즘이 실제 시스템(Cassandra, Memcached, Discord, Google Bigtable)에서 어떻게 쓰이는지

## 1. 왜 modulo hashing 으로는 부족한가

가장 단순한 분산 키 라우팅은 `node = hash(key) % N`. 노드가 추가되거나 빠지면 *대부분의 키* 가 다른 노드로 이동한다. 캐시라면 캐시 미스 폭발, 데이터베이스라면 거대한 rehash 작업.

정량적으로 보자. 노드 수가 N → N+1 로 변할 때 *유지되는 키 비율* 은 평균 1/(N+1). 즉 N=10 → 11 로 한 대 늘리면 키의 약 10% 만 자기 자리에 남고 90% 가 이동한다. CDN, 메모리 캐시, 샤딩된 DB 어디든 이 비용은 감당하기 어렵다.

목표: *노드 한 대가 추가될 때 평균 1/N 의 키만 이동한다.* 즉 절대적으로 최적인 K/N (K=총 키 수).

## 2. Consistent Hashing — Karger 1997

Karger 등이 1997년 *Consistent Hashing and Random Trees* 에서 제안한 ring 모델이 표준이다.

- 0 ~ 2^32 (또는 2^64) 의 *원형 해시 공간* 을 가정
- 각 노드를 `hash(nodeId)` 로 ring 위에 배치
- 키는 `hash(key)` 위치에서 *시계 방향으로 다음 노드* 에게 라우팅

노드 추가: 새 노드 위치 N_new 가 ring 에 들어가면, *N_new 바로 앞 노드부터 N_new 사이* 의 키만 N_new 로 이동. 나머지 키는 그대로. → 평균 K/N 이동.

노드 제거: 빠지는 노드의 키만 *시계방향 다음 노드* 가 떠맡는다. 마찬가지로 K/N.

## 3. 분포가 균일하지 않은 문제 — Virtual Node

ring 위에 노드 100개를 무작위로 배치하면 *분포가 매우 불균등* 하다. 어떤 구간은 1° 짧고 어떤 구간은 5° 길어 *worst-case 키 비율* 이 평균의 5배까지 튄다.

해결: 각 물리 노드를 *V 개의 가상 노드 (vnode)* 로 ring 에 배치. `hash("node-A#0")`, `hash("node-A#1")`, ..., `hash("node-A#V-1")` 처럼 V 개 위치에 점을 찍는다. 키는 여전히 가장 가까운 vnode 의 *물리 노드* 로 라우팅.

V 가 클수록 분포가 균일해진다. 표준 편차는 √(1/V) 에 비례하므로 V=128 정도면 ±5% 이내로 들어온다. Cassandra 의 기본 `num_tokens = 256`, Riak 의 `ring_size = 64` 가 그 산물이다.

## 4. 간단한 Python 구현

```python
import bisect
import hashlib

class ConsistentHashRing:
    def __init__(self, vnodes_per_node: int = 128):
        self._vnodes = vnodes_per_node
        self._sorted_keys: list[int] = []
        self._key_to_node: dict[int, str] = {}

    @staticmethod
    def _hash(s: str) -> int:
        # 128-bit MD5 의 상위 64-bit 만 사용 (속도/분포 균형)
        return int.from_bytes(hashlib.md5(s.encode()).digest()[:8], "big")

    def add_node(self, node: str) -> None:
        for i in range(self._vnodes):
            h = self._hash(f"{node}#{i}")
            bisect.insort(self._sorted_keys, h)
            self._key_to_node[h] = node

    def remove_node(self, node: str) -> None:
        for i in range(self._vnodes):
            h = self._hash(f"{node}#{i}")
            idx = bisect.bisect_left(self._sorted_keys, h)
            if idx < len(self._sorted_keys) and self._sorted_keys[idx] == h:
                self._sorted_keys.pop(idx)
                self._key_to_node.pop(h, None)

    def get_node(self, key: str) -> str | None:
        if not self._sorted_keys:
            return None
        h = self._hash(key)
        idx = bisect.bisect(self._sorted_keys, h)
        if idx == len(self._sorted_keys):
            idx = 0  # ring wrap-around
        return self._key_to_node[self._sorted_keys[idx]]
```

`bisect` 로 O(log V·N) 룩업. vnode 가 N·V 개이므로 메모리 O(N·V). 노드 1000개에 V=128 이면 128,000 개의 token 을 메모리에 두어야 한다 — 메모리는 크지 않지만 라우터마다 똑같이 들고 있어야 한다.

## 5. Replication 과 Quorum — Dynamo 모델

Cassandra/Dynamo 는 ring 위의 *시계방향 다음 R 개 노드* 에 키를 복제한다 (R=replication factor). 읽기/쓰기는 *W + R > N* 일 때 strict consistency 보장이지만, eventually consistent 시스템에서는 `R=W=1` 도 가능. 노드 추가/제거 시 replication 도 자동으로 새 위치로 이동되어야 한다 — Cassandra 의 `nodetool repair` 와 `streaming` 이 그 작업을 담당.

## 6. Consistent Hashing 의 한계

- 메모리: vnode 수 × 노드 수 만큼 token 보유 필요. 라우터가 여러 대면 *전부 동일한 ring view* 를 가져야 일관성 유지.
- view 동기화: 노드 추가/제거를 모든 클라이언트에 전파해야 한다. gossip protocol, ZooKeeper, etcd 같은 좌표시스템이 필요.
- 룩업 비용: O(log(N·V)).

이 한계를 정면 돌파한 게 *Jump Consistent Hash* 다.

## 7. Jump Consistent Hash — Google 2014

Lamping & Veach 의 2014년 논문 *A Fast, Minimal Memory, Consistent Hash Algorithm* 이 발표한 알고리즘. 다음 핵심 코드 한 덩어리가 전부다.

```c
int32_t jump_consistent_hash(uint64_t key, int32_t num_buckets) {
    int64_t b = -1;
    int64_t j = 0;
    while (j < num_buckets) {
        b = j;
        key = key * 2862933555777941757ULL + 1ULL;
        j = (int64_t)((b + 1) *
            ((double)(1LL << 31) / (double)((key >> 33) + 1)));
    }
    return (int32_t)b;
}
```

특성:

- 메모리 O(1) — 노드 테이블, vnode 도 필요 없음
- 시간 O(log N) — 위 while 루프가 평균 log₂N 번 돈다
- 분포 균등 — 이론상 완전 균일
- *추가만 가능* — `num_buckets` 가 변할 때 *마지막 버킷만* 영향. 임의 노드 제거는 불가능

알고리즘 의도: `num_buckets` 가 0 → N 까지 *한 번에 한 버킷씩 늘었다* 고 가정하면, 키가 *어떤 버킷에서 마지막으로 이동했는지* 만 알면 된다. 매 라운드 의사난수 j 를 만들어 *다음 이동 지점* 을 점프(jump). 키 별로 평균 log N 번 점프하면 도착.

내부 magic constant `2862933555777941757` 는 *Multiplicative Linear Congruential Generator (MLCG)* 의 multiplier 로, Pierre L'Ecuyer 의 잘 알려진 좋은 LCG.

## 8. 두 알고리즘의 트레이드오프

| 항목 | Ring + vnode | Jump |
|---|---|---|
| 메모리 | O(N·V) | O(1) |
| 룩업 시간 | O(log(N·V)) | O(log N) |
| 노드 추가 | 임의 | 마지막에만 |
| 노드 제거 | 임의 | 마지막에만 |
| 라우터 간 view 동기화 | 필수 | 불필요 (num_buckets 만 공유) |
| 가중치 (weight) 지원 | vnode 수 조정 | 별도 트릭 필요 |
| 구현 난이도 | 중 | 매우 단순 |

Jump 가 압도적으로 단순하지만 *임의 노드 제거 불가* 가 결정적 한계. *append-only* 한 클러스터 (storage shard, video chunk store) 에는 완벽하지만 *동적 스케일링* (수시로 노드가 죽고 새로 뜨는 캐시 클러스터) 에는 부적합.

## 9. Multi-Probe Consistent Hash (Google 2017)

Jump 의 한계를 보완한 *Multi-Probe Consistent Hash* (Appleton et al. 2017). 키마다 K 개의 후보 위치를 hash 로 만들어 *가장 가까운 노드* 를 선택. K=2 정도면 분포 균등성을 유지하면서 메모리는 O(N), 룩업은 O(K·log N) → 실질 O(K). vnode 의 메모리 N·V 를 K 배만 쓰므로 V=128 대비 64배 절약.

```python
def multi_probe(key, nodes, k=2):
    candidates = [(hash(f"{key}#{i}"), i) for i in range(k)]
    # 각 후보 위치에서 가장 가까운 노드 찾기
    ...
```

DiscordTaco, RingPop 등이 다양한 변형을 발표했다.

## 10. Maglev Hash — Google Load Balancer

Google Maglev (2016) 가 사용하는 변형. 큰 lookup table (예: 65537 entry) 을 *각 노드에 균등하게 슬롯 할당* 해 사전 계산. 룩업은 O(1) (배열 인덱스), 노드 추가/제거 시 *최소* 슬롯만 재할당. 메모리는 *고정 테이블 크기* 라 노드 수에 비교적 무관. L4 load balancer 처럼 *룩업이 패킷당 발생* 하는 환경에 최적.

## 11. 실제 시스템의 선택

| 시스템 | 알고리즘 | 이유 |
|---|---|---|
| Cassandra | Ring + vnode (256) | 임의 노드 추가/제거, 가중치 |
| Riak | Ring (64 vnode) | 동적 클러스터 |
| Memcached (Ketama) | Ring + vnode (~160) | 동적 캐시 클러스터 |
| Dynamo / DynamoDB | Ring + vnode | append + 임의 제거 |
| Google Bigtable tablet 분산 | sorted range (다름) | 범위 쿼리 지원 |
| Discord 메시지 샤딩 | Jump (변형) | append-only growth |
| Spotify 사용자 샤딩 | Jump | append-only |
| Google Maglev | Maglev | L4 LB, 패킷 throughput |
| YouTube Vitess | Jump | sharding lookup |

흥미로운 점: *append-only* 가 가능하면 거의 항상 Jump 가 이긴다. *동적 클러스터* 가 필수면 Ring + vnode 가 디폴트. *룩업이 초당 수백만* 이면 Maglev 같은 사전 계산 테이블.

## 12. 흔한 함정

핫스팟: 키 분포가 *비균질* 하면 (특정 사용자가 트래픽의 30% 를 차지) 어떤 해시도 도와주지 않는다. *상위 키* 만 따로 캐싱(local cache) 하거나 *키를 쪼개는* (user_id + bucket_id) 패턴이 필수.

view drift: 라우터마다 ring view 가 다르면 *같은 키* 가 다른 노드에 라우팅된다. eventually consistent 시스템에서는 큰 문제 아니지만 strict 시스템은 ZooKeeper 같은 좌표시스템 필수.

vnode 수 선택: 너무 작으면 분포 불균등, 너무 크면 메모리·동기화 비용. V=128~256 이 산업 표준.

해시 함수 선택: MD5/SHA1 의 상위 비트만 잘라 쓰는 게 일반적. xxhash 같은 비암호 해시도 분포가 균등하면 충분.

리프레시 트래픽: 노드 추가 후 *키 이동* 동안 새 노드에 일시적 부하 폭증. backpressure / rate limit 필수.

JumpHash 의 *signed/unsigned 혼용*: 위 C 코드의 `key >> 33` 는 *signed* 시프트를 쓰면 음수가 되어 망가진다. 반드시 `uint64_t` 로 다룬다.

## 참고

- Karger et al., *Consistent Hashing and Random Trees* (STOC 1997)
- Lamping & Veach, *A Fast, Minimal Memory, Consistent Hash Algorithm* (2014, arXiv:1406.2294)
- Eisenbud et al., *Maglev: A Fast and Reliable Software Network Load Balancer* (NSDI 2016)
- Appleton et al., *Multi-Probe Consistent Hashing* (2017)
- Cassandra Distributed Hashing: https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html
- Discord engineering blog — Storing Billions of Messages
- libketama (last.fm): https://github.com/RJ/ketama
