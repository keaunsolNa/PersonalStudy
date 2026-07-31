Notion 원본: https://www.notion.so/3ae5a06fd6d381e89e94d2730e7b295b

# CRDT와 벡터 클록 및 최종 일관성 충돌 해소

> 2026-07-31 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- 벡터 클록으로 분산 이벤트의 인과관계와 동시성을 판뱄하는 규칙을 익힌다
- state-based(CvRDT)와 op-based(CmRDT) 병합 모델의 수학적 전제를 구분한다
- G-Counter·PN-Counter·LWW·OR-Set 을 코드로 구현하며 병합 규칙을 검증한다
- 충돌 자동 해소가 가능한 경계와 그 한계(불변식 위반)를 판단한다

## 1. 최종 일관성과 충돌의 본질

여러 노드가 조율(coordination) 없이 동시에 쓰기를 받으면, 같은 데이터에 대해 서로 다른 버전이 생긴다. 강한 일관성은 매 쓰기마다 합의를 요구해 지연·가용성을 희생한다. 반대로 **최종 일관성(eventual consistency)** 은 각 노드가 일단 로컬로 쓰고, 나중에 복제본을 교환해 수렴시킨다. 문제는 "교환했을 때 어느 쪽이 맞는가"이다. 마지막 쓰기가 이기게(LWW) 하면 데이터가 소실되고, 둘 다 보존하면 애플리케이션이 병합을 떠안는다. CRDT 는 이 병합을 **수학적으로 항상 같은 결과로 수렴**하도록 설계된 자료구조다.

수렴을 위해 CRDT 의 병합 함수는 세 성질을 만족해야 한다. **교환법칙**(a⊔b = b⊔a), **결합법칙**((a⊔b)⊔c = a⊔(b⊔c)), **멱등성**(a⊔a = a). 이 세 성질을 갖춘 병합은 곳 반격자(join-semilattice)의 최소상한(least upper bound)이며, 메시지가 중복·재정렬·지연되어도 최종 상태가 동일함을 보장한다. 이것이 "네트워크는 신뢰할 수 없다"는 전제 위에서 조율 없이 수렴하는 핵심 원리다.

## 2. 벡터 클록 — 인과관계의 판뱄

수렴을 논하기 전에 "어떤 이벤트가 먼저인가"를 알아야 한다. 물리 시계는 노드 간 드리프트로 신뢰할 수 없으므로 **논리 시계**를 쓴다. 벡터 클록은 노드마다 카운터를 하나씩 둔 벡터다. 규칙은 단순하다. (1) 로컬 이벤트가 생기면 자기 항목을 +1 한다. (2) 메시지를 보낼 때 벡터를 첨부한다. (3) 받으면 각 항목을 원소별 max 로 갱신하고 자기 항목을 +1 한다.

```python
def happens_before(a: dict, b: dict) -> bool:
    """a -> b (a 가 b 보다 인과적으로 앞) 이면 True"""
    nodes = set(a) | set(b)
    le = all(a.get(n, 0) <= b.get(n, 0) for n in nodes)
    lt = any(a.get(n, 0) <  b.get(n, 0) for n in nodes)
    return le and lt

def concurrent(a: dict, b: dict) -> bool:
    return not happens_before(a, b) and not happens_before(b, a)
```

두 벡터가 서로 `happens_before` 도 아니면 **동시(concurrent)** 이며, 이것이 곳 충돌 후보다. 예컨대 A=`{n1:2, n2:1}`, B=`{n1:1, n2:2}` 는 어느 쪽도 상대를 지배하지 않으므로 동시다. 벡터 클록은 "충돌이 있다"는 사실을 정확히 탐지하지만, 무엇이 올은 값인지는 말해주지 않는다. 그 결정을 자료구조 설계로 흡수한 것이 CRDT 다.

## 3. state-based(CvRDT) vs op-based(CmRDT)

CRDT 는 복제본을 동기화하는 방식으로 두 갈래다. **state-based(CvRDT)** 는 전체 상태를 주기적으로 통째로 보내고, 수신 측이 병합 함수 `merge` 로 합친다. 병합이 교환·결합·멱등이면 메시지 순서·중복에 강하지만, 상태 전체를 전송해 대역폭이 크다(델타 CRDT 로 완화). **op-based(CmRDT)** 는 연산만 브로드캐스트한다. 전송량은 작지만, 연산이 **정확히 한 번, 인과 순서대로** 전달된다는 신뢰성 있는 브로드캐스트를 전제한다. 실무 선택은 인프라에 달렸다. 신뢰 못 할 채널이면 CvRDT, 메시지 미들웨어가 순서·중복제거를 보장하면 CmRDT 가 효율적이다.

## 4. 카운터 — G-Counter와 PN-Counter

가장 단순한 CRDT 는 증가만 하는 **G-Counter** 다. 노드별 카운터 벡터를 두고, 값은 전 항목의 합, 병합은 원소별 max 다. max 는 교환·결합·멱등이므로 수렴이 보장된다.

```python
class GCounter:
    def __init__(self, node_id):
        self.node = node_id
        self.counts = {}

    def increment(self, n=1):
        self.counts[self.node] = self.counts.get(self.node, 0) + n

    def value(self):
        return sum(self.counts.values())

    def merge(self, other):
        merged = GCounter(self.node)
        for k in set(self.counts) | set(other.counts):
            merged.counts[k] = max(self.counts.get(k, 0),
                                   other.counts.get(k, 0))
        return merged
```

감소가 필요하면 **PN-Counter** 를 쓴다. 증가용 G-Counter(P)와 감소용 G-Counter(N)를 각각 두고 값은 `P.value - N.value` 로 계산한다. 왜 하나의 카운터를 직접 감소시키지 않는가? 감소를 허용하면 병합의 max 규칙이 깨져 멱등성이 사라지기 때문이다. 증가·감소를 각각 단조 증가하는 두 카운터로 분리해야 반격자 구조가 유지된다.

## 5. 레지스터와 집합 — LWW-Register와 OR-Set

단일 값을 다루는 **LWW-Register** 는 각 쓰기에 타임스탬프를 붙이고 병합 시 더 큰 타임스탬프가 이긴다. 구현은 쉽지만 동시 쓰기 중 하나가 **조용히 소실**된다는 근본 한계가 있다. 타임스탬프가 같으면 노드 ID 로 결정론적 tie-break 를 해야 노드마다 다른 값으로 갈라지지 않는다.

집합은 더 미묘하다. 순진한 2P-Set(추가/삭제 집합)은 "한번 지운 원소는 다시 못 넣는" 문제가 있다. 이를 해결한 것이 **OR-Set(Observed-Remove Set)** 이다. 원소를 넣을 때마다 고유 태그를 부여하고, 삭제는 "그 순간 관측한 태그들"만 지운다. 그래서 동시 add/remove 가 붙으면 **add 가 이긴다**(관측 못 한 태그는 살아남는다).

```python
class ORSet:
    def __init__(self):
        self.adds = {}     # element -> set of unique tags
        self.removes = {}  # element -> set of removed tags

    def add(self, element, tag):
        self.adds.setdefault(element, set()).add(tag)

    def remove(self, element):
        # 현재 관측된 태그만 tombstone 처리
        observed = self.adds.get(element, set())
        self.removes.setdefault(element, set()).update(observed)

    def contains(self, element):
        live = self.adds.get(element, set()) - self.removes.get(element, set())
        return len(live) > 0

    def merge(self, other):
        for e, tags in other.adds.items():
            self.adds.setdefault(e, set()).update(tags)
        for e, tags in other.removes.items():
            self.removes.setdefault(e, set()).update(tags)
```

OR-Set 은 "장바구니에 담기" 같은 협업 시나리오에서 자연스럽다. 두 기기에서 같은 상품을 동시에 담고 한쪽에서 지워도, 다른 쪽의 add 는 살아남아 사용자의 의도를 보존한다. 대가는 tombstone(삭제 태그) 누적이다. 오래된 tombstone 을 안전하게 청소하려면 모든 복제본이 그 삭제를 관측했다는 인과적 안정성(causal stability) 증명이 필요하며, 이 GC 가 CRDT 운영의 실질적 난제다.

## 6. 자동 해소의 경계와 한계

CRDT 는 **문법적(syntactic) 충돌**을 조율 없이 없앨다. 그러나 **의미적(semantic) 불변식**은 보장하지 못한다. 대표 반례가 "잔액은 음수가 될 수 없다"이다. 두 노드가 각각 잔액 100 에서 80 을 동시에 출금하면, PN-Counter 병합은 두 연산을 모두 보존해 잔액을 -60 으로 수렴시킨다. 각 노드는 로컬에서 "잔액 20, 정상"이라 판단했지만 전역 불변식이 깨진다. CRDT 는 수렴은 보장해도 이 불변식은 지키지 못한다.

따라서 설계 판단은 명확하다. **교환 가능하고 전역 불변식이 없는** 연산(좋아요 수, 협업 문서의 텍스트 삽입, 존재 상태, 장바구니)은 CRDT 로 조율 없이 처리해 가용성과 지연을 얻는다. 반대로 **재고 차감, 계좌 이체, 유일 좌석 배정**처럼 전역 하한/상한 불변식이 걸린 연산은 CRDT 만으로 안전하지 않으며, 이스크로우(escrow)로 미리 할당량을 나눠 갖거나, 그 순간만 합의(예: Raft)로 조율하는 하이브리드가 필요하다. 결국 CRDT 는 "어떤 데이터는 애초에 충돌하지 않게 모델링할 수 있다"는 통찰이고, 그 통찰이 통하는 경계를 정확히 아는 것이 실무 역량이다.

## 7. 실전 적용 — 협업 텍스트와 분산 KV 스토어

CRDT 가 가장 널리 쓰이는 두 무대는 협업 편집기와 분산 데이터베이스다. 협업 텍스트에서는 각 문자에 **전역 고유하고 조밀하게 정렬 가능한 위치 식별자**를 부여하는 sequence CRDT(RGA, LSEQ, Yjs 의 YATA 등)를 쓴다. 핵심 아이디어는 "두 문자 사이에 항상 새 위치를 만들 수 있는" 조밀 순서를 두어, 동시 삽입이 서로 다른 위치를 받게 하는 것이다. 그래야 두 사용자가 같은 지점에 동시에 타이핑해도 문자가 겹치거나 사라지지 않고 결정론적으로 정렬된다.

```python
# 개념 스케치: 두 위치 사이에 새 위치를 생성 (조밀 순서)
def alloc_between(left: list[int], right: list[int]) -> list[int]:
    # left < result < right 를 만족하는 새 식별자 경로를 만든다
    if left and right and right[0] - left[0] > 1:
        return [(left[0] + right[0]) // 2]
    # 자리가 없으면 한 단계 더 깊이 파고든다 (트리 경로 확장)
    return left + [1]
```

분산 KV 스토어(Riak, Redis 의 active-active CRDB, DynamoDB 스타일)는 값 타입별로 CRDT 를 매핑한다. 카운터는 PN-Counter, 플래그·집합은 OR-Set, 레지스터는 LWW 또는 MV-Register 다. MV-Register(Multi-Value)는 LWW 와 달리 **동시 쓰기를 소실시키지 않고 둘 다 보존**해, 읽을 때 애플리케이션에 충돌 후보들을 함께 돌려준다. 장바구니의 고전적 사례(Amazon Dynamo 논문)가 이것이다. 소실보다 "중복 담김"이 사용자에게 덜 나쁘다는 판단이다.

운영에서 반드시 고려할 것은 **메타데이터 증가**다. 벡터 클록·태그·tombstone 은 노드 수와 연산 이력에 비례해 커진다. 노드가 수천 개로 늘거나 오래 돌면 메타데이터가 실제 데이터를 압도할 수 있다. 그래서 dotted version vector, 인과적 안정성 기반 tombstone GC, 델타 상태 전파 같은 기법으로 오버헤드를 억제한다. CRDT 는 "조율 없는 수렴"이라는 강력한 보장을 주지만, 그 대가는 이 메타데이터 관리라는 점을 설계 초기에 반드시 계산에 넣어야 한다.

## 참고

- Shapiro et al., "Conflict-free Replicated Data Types" (INRIA RR-7687, 2011)
- Leslie Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System"
- Marc Shapiro et al., "A comprehensive study of CRDTs" 기술 보고서
- Martin Kleppmann, "Designing Data-Intensive Applications" — 5장 Replication
