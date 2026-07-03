Notion 원본: https://www.notion.so/3925a06fd6d381a1836bfafacf263a58

# Consistent Hashing과 가상 노드 및 Rendezvous Hashing 분산 파티셔닝

> 2026-07-03 신규 주제 · 확장 대상: CS(분산 트랜잭션·Lamport/Vector Clock 학습됨)

## 학습 목표

- 단순 모듈로 샤딩이 노드 변경 시 대량 재배치를 유발하는 이유를 정량으로 설명한다
- 일관 해싱 링 구조와 노드 추가/삭제 시 이동하는 키 비율을 계산한다
- 가상 노드로 부하 편중과 재배치 불균형을 완화하는 원리를 코드로 구현한다
- Rendezvous(HRW) 해싱과의 차이·장단점을 비교해 상황별로 선택한다

## 1. 모듈로 샤딩의 근본 결함

키를 N개 노드에 분산하는 가장 단순한 방법은 `hash(key) % N`이다. 균등 분포는 좋지만 치명적 약점이 있다. N이 바뀌면(노드 추가/삭제) 나머지 연산의 기준이 통카 바뀜어 거의 모든 키의 소속이 재계산된다.

노드가 N개에서 N+1개로 늘면, 키가 원래 자리에 그대로 남을 확률은 대략 1/(N+1)에 불과하다. 즉 캐시 서버 10대에 1대를 추가하면 약 91%의 키가 다른 서버로 이동해 캐시가 사실상 전멸한다. 이 "리해싱 폭풍"은 캐시·샤딩 스토리지에서 용납하기 어렵다.

```python
def modulo_shard(key: str, nodes: list[str]) -> str:
    return nodes[hash(key) % len(nodes)]
# nodes 길이가 바뀌는 순간 대부분 키의 반환 노드가 달라진다
```

## 2. 일관 해싱: 링 위의 노드와 키

일관 해싱은 노드와 키를 같은 해시 공간(예: 0 ~ 2^32-1)의 원형 링 위에 배치한다. 키는 자신의 해시값에서 링을 시계방향으로 돌다 처음 만나는 노드에 소속된다. 노드를 추가하면 그 노드와 직전 노드 사이 구간의 키만 새 노드로 이동하고, 나머지 키는 전혀 영향받지 않는다.

핵심 성질: 노드 하나를 추가/삭제할 때 이동하는 키는 평균적으로 전체의 약 **1/N**뿐이다(N은 노드 수). 모듈로의 ~(N-1)/N과 극명히 대비된다.

```python
import bisect, hashlib

def _h(s: str) -> int:
    return int(hashlib.md5(s.encode()).hexdigest(), 16)

class HashRing:
    def __init__(self):
        self._ring: dict[int, str] = {}
        self._keys: list[int] = []          # 정렬된 해시 위치

    def add_node(self, node: str) -> None:
        p = _h(node)
        self._ring[p] = node
        bisect.insort(self._keys, p)

    def get_node(self, key: str) -> str:
        if not self._keys:
            raise ValueError("empty ring")
        p = _h(key)
        idx = bisect.bisect(self._keys, p) % len(self._keys)  # 시계방향 첫 노드
        return self._ring[self._keys[idx]]
```

노드 조회는 정렬된 위치 배열에서 이진 탐색이라 O(log M)이다(M은 링 위 포인트 수).

## 3. 가상 노드: 편중과 불균형 해소

기본 링에는 두 문제가 있다. 첫째, 노드가 적으면 해시가 링에 고르게 안 퍼져 특정 노드가 큰 호(arc)를 담당해 부하가 편중된다. 둘째, 노드가 빠질 때 그 노드의 부하 전부가 링에서 바로 뒤의 단일 노드로 몰린다.

가상 노드(virtual node, replica)는 물리 노드 하나를 링 위 여러 지점에 뾌린다. 예를 들어 노드당 150개의 가상 지점을 만들면, 각 물리 노드가 링 곳곳에 흔어져 담당 호가 잘게 쪼개진다. 그 결과 부하가 균등해지고, 한 노드가 빠지면 그 부하가 여러 노드로 분산 흡수된다.

```python
class HashRing:
    def __init__(self, vnodes: int = 150):
        self._vnodes = vnodes
        self._ring: dict[int, str] = {}
        self._keys: list[int] = []

    def add_node(self, node: str) -> None:
        for i in range(self._vnodes):
            p = _h(f"{node}#{i}")           # 물리 노드를 여러 지점에 배치
            self._ring[p] = node
            bisect.insort(self._keys, p)

    def remove_node(self, node: str) -> None:
        for i in range(self._vnodes):
            p = _h(f"{node}#{i}")
            del self._ring[p]
            self._keys.remove(p)
```

가상 노드 수는 트레이드오프다. 많을수록 부하가 균등해지지만 링 자료구조 메모리와 조회 상수 비용이 커진다. 실무에서 노드당 100~200개가 흔한 절충값이다. Dynamo/Cassandra 계열이 이 방식을 쓴다.

| 방식 | 노드 변경 시 이동 키 | 부하 균등성 | 조회 비용 | 메모리 |
|---|---|---|---|---|
| 모듈로 | ~(N-1)/N (대량) | 좋음 | O(1) | 최소 |
| 일관 해싱(기본) | ~1/N | 편중 위험 | O(log M) | 노드 수 |
| 일관 해싱+가상노드 | ~1/N, 분산 흡수 | 우수 | O(log M) | 노드×vnode |
| Rendezvous(HRW) | ~1/N | 우수 | O(N) | 최소 |

## 4. Rendezvous(HRW) 해싱

Rendezvous 해싱(Highest Random Weight)은 링 없이 같은 목표를 달성한다. 키마다 모든 노드에 대해 `weight = hash(key, node)`를 계산하고, 가장 큰 가중치를 주는 노드를 선택한다.

```python
def rendezvous(key: str, nodes: list[str]) -> str:
    return max(nodes, key=lambda n: _h(f"{key}:{n}"))
```

노드가 하나 빠지면, 그 노드를 최고 가중치로 뽑았던 키들만 각자의 "두 번째 높은" 노드로 재배치되고 나머지 키는 그대로다. 이동 비율은 일관 해싱과 같은 ~1/N이며, 가상 노드 튜닝 없이도 자연스럽게 균등하다. 단점은 조회가 노드 수 N에 비례(O(N))한다는 것이라, 노드가 수천 개 규모면 링 방식이 유리하다. 반대로 노드가 수십~수백 개면 HRW가 구현이 단순하고 균등성이 좋아 자주 선택된다.

## 5. 실전 고려: 복제와 핫키

분산 스토리지는 보통 키를 한 노드가 아니라 N개 복제본에 저장한다. 일관 해싱에서는 링을 시계방향으로 돌며 만나는 "서로 다른 물리 노드" N개를 선호 목록(preference list)으로 삼는다. 가상 노드를 쓸 때는 같은 물리 노드의 가상 지점을 건너뛰어야 복제본이 실제로 다른 장비에 놓인다.

```python
def get_preference_list(self, key: str, n: int) -> list[str]:
    if not self._keys:
        return []
    p = _h(key)
    start = bisect.bisect(self._keys, p) % len(self._keys)
    result, seen = [], set()
    i = start
    while len(result) < n and len(seen) < len(set(self._ring.values())):
        node = self._ring[self._keys[i % len(self._keys)]]
        if node not in seen:                # 물리 노드 중복 제거
            seen.add(node); result.append(node)
        i += 1
    return result
```

핫키(특정 키에 트래픽 집중) 문제는 해싱만으로는 못 푸는다. 해싱은 키 분포를 고르게 하지만, 한 키가 뜨거우면 그 키를 맡은 노드가 여전히 과부하다. 이때는 키에 접미사를 붙여 여러 키로 쪼개는 salting이나, 인기 키를 모든 노드에 복제하는 별도 전략을 얇는다.

## 6. Jump Consistent Hash와 Bounded-Load 변형

일관 해싱 링은 유연하지만 노드별 위치 테이블(가상 노드 포함 수천~수만 포인트)를 메모리에 유지해야 한다. Google의 Jump Consistent Hash는 이 테이블 없이, 키와 버킷 수만으로 O(log N) 시간에 버킷을 계산하는 알고리즘이다. 메모리가 상수이고 분포가 완벽히 균등하다.

```python
def jump_hash(key: int, num_buckets: int) -> int:
    b, j = -1, 0
    while j < num_buckets:
        b = j
        key = (key * 2862933555777941757 + 1) & 0xFFFFFFFFFFFFFFFF
        j = int((b + 1) * (float(1 << 31) / float((key >> 33) + 1)))
    return b
```

제약이 분명하다. Jump hash는 버킷이 `0..N-1`로 연속 번호일 때만 동작하고, "임의의 노드"를 빼는 것을 지원하지 않는다(끝 버킷만 추가/제거 가능). 그래서 노드 집합이 순수하게 늘고 주는 샤딩(예: 샤드 수 조정)에는 이상적이지만, 특정 장비가 죽어 중간 노드를 빼야 하는 캐시 클러스터에는 부적합하다. 이 경우 링이나 HRW가 맞다.

또 다른 실무 문제는 부하 불균형이다. 해시가 통계적으로 균등해도 키 인기의 편차 때문에 특정 노드가 과부하될 수 있다. Bounded-Load consistent hashing은 각 노드에 용량 상한(평균의 c배, 예: 1.25배)을 두고, 상한에 도달한 노드는 건너뛰어 링의 다음 노드로 넘긴다. 이렇게 하면 어떤 노드도 평균의 c배를 넘지 않도록 보장하면서 일관 해싱의 최소 재배치 성질을 대부분 유지한다. Vimeo와 HAProxy가 이 기법을 로드 밸런싱에 적용한 사례가 알려져 있다.

## 7. 트레이드오프 정리

일관 해싱과 HRW 모두 노드 변경 시 이동 키를 ~1/N로 최소화한다는 목표는 같다. 선택 기준은 규모와 균등성 요구다. 노드가 많고(수천) 조회가 초고빈도면 O(log M) 링+가상 노드가 낫다. 노드가 적당하고 균등성·구현 단순성이 중요하면 HRW가 낫다.

가상 노드 수는 균등성과 자원의 트레이드오프이고, 너무 크게 잡으면 리밸런싱과 멤버십 변경 비용이 커진다. 어떤 방식이든 해시 함수의 균일성이 전제이며, 편향된 해시(예: 나쁜 문자열 해시)를 쓰면 모든 이점이 무너지므로 MD5/xxHash 등 분포가 좋은 함수를 쓴다.

## 참고

- Karger et al., "Consistent Hashing and Random Trees" (1997, STOC)
- DeCandia et al., "Dynamo: Amazon's Highly Available Key-value Store" (2007, SOSP)
- Thaler & Ravishankar, "A Name-Based Mapping Scheme for Rendezvous" (HRW, 1998)
- Lamping & Veach, "A Fast, Minimal Memory, Consistent Hash Algorithm" (Jump Hash, 2014)
- Mirrokni, Thorup & Zadimoghaddam, "Consistent Hashing with Bounded Loads" (2016)
- Apache Cassandra Documentation — Data Distribution / Virtual Nodes
