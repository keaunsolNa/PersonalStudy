Notion 원본: https://www.notion.so/3825a06fd6d381bb9771e3a359133c96

# Consistent Hashing과 Virtual Node 및 Rendezvous · Jump Hashing 분산 배치

> 2026-06-17 신규 주제 · 확장 대상: 자료구조&알고리즘, Redis

## 학습 목표

- 단순 모듈로 샤딩의 재배치 폭주 문제를 정량적으로 이해한다
- Consistent Hashing의 링 구조와 Virtual Node로 부하 균등을 달성하는 원리를 구현한다
- Rendezvous(HRW) Hashing과 Jump Consistent Hash의 동작·장단점을 비교한다
- 각 기법이 Redis Cluster, DynamoDB, CDN 등 실제 시스템에서 어디에 쓰이는지 연결한다

## 1. 모듈로 샤딩은 왜 무너지는가

N개의 노드에 키를 분산하는 가장 단순한 방법은 `node = hash(key) % N`이다. 균등 분배는 잘 되지만 치명적 약점이 있다: **N이 바뀌면 거의 모든 키의 매핑이 바뀜다**. 노드를 4개에서 5개로 늘리면 `% 4`와 `% 5`의 결과가 달라져, 평균적으로 약 80%의 키가 다른 노드로 이동한다. 캐시라면 대규모 cache miss, 데이터 스토어라면 대규모 리밸런싱 트래픽이 동시에 터진다.

```python
# 모듈로 샤딩: N 변경 시 재배치 비율 측정
def remap_ratio(keys, n_old, n_new):
	moved = sum(1 for k in keys if hash(k) % n_old != hash(k) % n_new)
	return moved / len(keys)

# 4 -> 5 노드: 약 0.8 (80% 이동) — 사실상 전면 재배치
```

목표는 노드 1개 추가/제거 시 이동하는 키를 **1/N 수준**으로 최소화하는 것이다. 이것이 consistent hashing의 동기다.

## 2. Consistent Hashing — 링 위에 키와 노드를 함께 배치

핵심 아이디어: 키와 노드를 같은 해시 공간(예: 0 ~ 2³²-1)의 **원형 링**에 올린다. 각 키는 링을 시계 방향으로 돌다 처음 만나는 노드에 할당된다. 노드를 추가하면 그 노드와 직전 노드 사이 구간의 키만 새 노드로 이동하고, 나머지는 그대로다 — 이동량이 평균 K/N으로 제한된다.

```python
import bisect, hashlib

def _h(s):
	return int(hashlib.md5(s.encode()).hexdigest(), 16)

class HashRing:
	def __init__(self):
		self.ring = []      # 정렬된 해시값
		self.node_of = {}   # 해시값 -> 노드명

	def add_node(self, node):
		hv = _h(node)
		bisect.insort(self.ring, hv)
		self.node_of[hv] = node

	def get_node(self, key):
		if not self.ring:
			return None
		hv = _h(key)
		idx = bisect.bisect_right(self.ring, hv) % len(self.ring)
		return self.node_of[self.ring[idx]]
```

조회는 정렬된 배열의 이진 탐색으로 O(log N). 노드 추가/제거는 링에 점 하나를 넣고 빼는 일이다.

## 3. Virtual Node — 부하 불균형을 깨는 핵심 보완

위 단순 링에는 문제가 있다. 노드가 적으면 링 위 분포가 들쳤날쥐해 어떤 노드는 넓은 구간을, 어떤 노드는 좌은 구간을 맡아 **부하가 최대 수 배 차이** 난다. 또 노드 하나가 죽으면 그 부하가 전부 *다음 한 노드*로 쓸린다. 해결책은 각 물리 노드를 여러 개의 **Virtual Node(가상 노드/replica)** 로 링에 흔뿌리는 것이다.

```python
class HashRing:
	def __init__(self, vnodes=160):
		self.vnodes = vnodes
		self.ring = []
		self.node_of = {}

	def add_node(self, node):
		for i in range(self.vnodes):
			hv = _h(f"{node}#{i}")   # 가상 노드마다 다른 해시점
			bisect.insort(self.ring, hv)
			self.node_of[hv] = node
```

가상 노드가 많을수록(보통 100~200개) 각 물리 노드의 부하가 평균에 수렴한다. 노드가 죽으면 그 부하가 *남은 모든 노드*로 골고루 분산된다. 대가는 메모리(링 크기 = 물리노드 × vnodes)와 약간의 조회 비용이다. 이 기법은 Amazon Dynamo 논문에서 대중화되었고, Cassandra의 `num_tokens`, Riak의 partition이 같은 개념이다.

## 4. Rendezvous(HRW) Hashing — 링 없는 대안

**Rendezvous Hashing**(Highest Random Weight, HRW)은 링을 쓰지 않는다. 각 키에 대해 모든 노드와의 결합 해시 `hash(key, node)`를 계산하고, **가장 큰 값**을 내는 노드를 고른다. 놀랍게도 이 단순한 규칙이 consistent hashing의 최소 이동 성질을 그대로 만족한다 — 노드가 사라지면 그 노드를 1순위로 삼던 키만 각자의 2순위로 이동하고, 다른 키는 영향받지 않는다.

```python
def hrw_node(key, nodes):
	# 각 노드와의 결합 해시 중 최댓값을 내는 노드 선택
	return max(nodes, key=lambda n: _h(f"{key}:{n}"))
```

장점: 가상 노드 없이도 분포가 균등하고, 데이터 구조가 없어 구현이 단순하며, top-k(복제본 N개) 선택이 자연스럽다. 단점: 조회가 O(N)으로 노드 수에 선형이다. 수십~수백 개 규모에서는 매우 실용적이다.

## 5. Jump Consistent Hash — 메모리 0, O(log N)

구글의 **Jump Consistent Hash**(Lamping & Veach, 2014)는 어떤 자료구조도 저장하지 않고, 키 해시와 버킷 수만으로 0..N-1 중 버킷 번호를 계산한다.

```python
def jump_consistent_hash(key, num_buckets):
	b, j = -1, 0
	k = key & 0xFFFFFFFFFFFFFFFF  # 64-bit key
	while j < num_buckets:
		b = j
		k = (k * 2862933555777941757 + 1) & 0xFFFFFFFFFFFFFFFF
		j = int((b + 1) * (float(1 << 31) / float((k >> 33) + 1)))
	return b
```

메모리 0, 속도 O(log N), 거의 완벽한 균등 분배. 그러나 **버킷 추가는 마지막 번호(N)에만 가능**하고, 중간 노드의 임의 제거를 지원하지 않는다. 샤드 splitting에 잘 맞지만, 임의 노드가 동적으로 들고나는 캐시 클러스터에는 부적합하다.

## 6. 기법 비교

| 기법 | 조회 | 메모리 | 노드 추가/제거 | 부하 균등 |
|---|---|---|---|---|
| 모듈로 | O(1) | 0 | 전면 재배치(나쁨) | 균등 |
| Consistent(+vnode) | O(log N) | O(N×vnode) | 임의, 1/N 이동 | vnode로 균등 |
| Rendezvous(HRW) | O(N) | 0 | 임의, 최소 이동 | 매우 균등 |
| Jump | O(log N) | 0 | 끕에만 추가 | 매우 균등 |

선택 가이드: 동적으로 노드가 들고나며 복제본 선택이 필요하면 vnode 링 또는 HRW. 노드 수가 작고 단순함이 중요하면 HRW. 샤드 번호가 안정적이고 메모리·속도가 극단적으로 중요하면 Jump. 캐시 클라이언트(예: memcached의 ketama)는 vnode 링이 사실상 표준이다.

## 7. 실제 시스템 매핑

**Redis Cluster**는 흥미롭게도 consistent hashing이 아니라 16384개 고정 **해시 슬롯**을 쓴다. `CRC16(key) % 16384`로 슬롯을 정하고 슬롯을 노드에 배정한다. 슬롯이라는 중간 추상층 덕분에 리샤딩이 "슬롯 단위 이동"으로 명시적이고 제어 가능하다 — vnode의 변형으로 볼 수 있다. **DynamoDB/Cassandra**는 consistent hashing + vnode로 파티션을 배치하고 링에서 시계방향 N개 노드에 복제한다. **CDN/로드밸런서**는 HRW나 ketama로 캐시 친화도를 유지해 origin 부하를 줄인다. **Maglev**(구글 LB)는 또 다른 consistent hashing 변형으로 연결 일관성과 균등성을 동시에 노린다.

## 8. 주의 — 핫 키와 데이터 스큐

consistent hashing은 *키 분포가 균등*하다는 가정 위에 선다. 특정 키에 트래픽이 몰리는 **핫 키**(예: 인기 상품 ID)는 어떤 분산 기법으로도 한 노드에 쓸린다. 이 경우 해싱이 아니라 핫 키 복제(여러 노드에 캐싱)나 키 분할(`key#shard`로 인위 분산) 같은 애플리케이션 수준 대응이 필요하다. 또 가상 노드 수가 적으면 통계적 편차가 커지므로, 운영 전 실제 키 샘플로 노드별 적재량 분포를 시뮬레이션해 표준편차를 확인하는 것이 안전하다.

```python
# 부하 분포 검증
from collections import Counter
ring = HashRing(vnodes=160)
for n in ["node-a","node-b","node-c","node-d"]:
	ring.add_node(n)
dist = Counter(ring.get_node(f"key-{i}") for i in range(100000))
# 각 노드가 ~25%에 가까운지, 표준편차가 작은지 확인
```

## 9. 복제와 일관성 — N개 노드 선택

분산 스토어는 가용성을 위해 각 키를 여러 노드에 복제한다. consistent hashing에서 복제본 배치는 자연스럽다 — 링에서 키 위치부터 시계방향으로 만나는 **서로 다른 물리 노드 N개**를 선택한다(이른바 preference list). 가상 노드를 쓸 때는 같은 물리 노드의 vnode를 건너뛰어 실제로 다른 N개 물리 노드를 확보해야 한다.

```python
def get_nodes(self, key, replicas):
	if not self.ring:
		return []
	hv = _h(key)
	idx = bisect.bisect_right(self.ring, hv) % len(self.ring)
	result, seen = [], set()
	for i in range(len(self.ring)):
		node = self.node_of[self.ring[(idx + i) % len(self.ring)]]
		if node not in seen:       # 같은 물리 노드 중복 방지
			seen.add(node)
			result.append(node)
			if len(result) == replicas:
				break
	return result
```

Rendezvous Hashing은 복제본 선택이 더 우아하다 — 결합 해시값 기준 상위 N개 노드를 고르면 끝이다. 정렬만 하면 되므로 preference list가 자연스럽게 나온다. Dynamo 계열은 이 preference list에 quorum(R+W>N) 읽기/쓰기를 얹어 최종 일관성과 가용성을 조율한다.

## 10. 운영 시 고려사항 — 리밸런싱과 데이터 이동

이론상 노드 추가 시 1/N만 이동하지만, 실제 운영에서는 그 1/N의 데이터를 **언제·어떻게** 옮길지가 관건이다. 데이터 이동 중에도 읽기/쓰기가 계속되므로, 이동 대상 키 범위에 대한 읽기를 신구 노드 양쪽에서 확인하거나(double read), 이동 완료 전까지 옛 노드로 라우팅하는 핸드오프 단계가 필요하다. Redis Cluster가 슬롯을 `MIGRATING`/`IMPORTING` 상태로 두고 `ASK` 리다이렉션을 쓰는 이유가 이것이다 — 슬롯 단위 이동 중에도 클라이언트가 올바른 노드를 찾게 한다.

또 하나의 함정은 **노드 추가 순서 의존성**이다. vnode 해시점은 노드 이름에 의존하므로, 같은 노드 집합이라도 추가 순서와 무관하게 결정적이어야 클러스터 전체가 동일한 링을 공유한다(이름 기반 해싱이 이를 보장). 노드 이름을 IP로 쓰면 IP가 바뀔 때 모든 매핑이 깨지므로, 안정적 논리 ID를 노드 식별자로 쓰는 것이 정석이다.

마지막으로 가상 노드 수 선택의 트레이드오프: vnode가 많을수록 부하 균등성이 좋아지지만 링 메모리와 노드 추가/제거 시 갱신 비용(vnode 개수만큼 링 연산)이 늘고, 클러스터 전체에 전파해야 할 메타데이터도 커진다. 수백 개(100~256)가 균등성과 비용의 일반적 균형점으로 알려져 있으며, 실제 키 샘플로 노드별 적재 표준편차를 측정해 결정하는 것이 안전하다.

## 참고

- Karger et al., "Consistent Hashing and Random Trees" (STOC 1997)
- DeCandia et al., "Dynamo: Amazon's Highly Available Key-value Store" (SOSP 2007)
- Lamping & Veach, "A Fast, Minimal Memory, Consistent Hash Algorithm" (2014)
- Thaler & Ravishankar, "Rendezvous Hashing" (HRW, 1998)
- Redis Cluster Specification — redis.io/docs/reference/cluster-spec
