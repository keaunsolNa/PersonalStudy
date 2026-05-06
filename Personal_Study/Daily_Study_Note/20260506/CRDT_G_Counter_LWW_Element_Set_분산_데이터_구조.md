Notion 원본: https://www.notion.so/3585a06fd6d3816e9b4cff71cdd75795

# CRDT G-Counter LWW-Element-Set Operation vs State Based 분산 데이터 구조

> 2026-05-06 신규 주제 · 확장 대상: CS

## 학습 목표

- CRDT(Conflict-free Replicated Data Type) 가 충돌을 자동으로 해소하는 수학적 조건을 정리한다
- State-based(CvRDT) 와 Operation-based(CmRDT) 의 차이를 구체적 자료구조로 비교한다
- G-Counter, PN-Counter, G-Set, 2P-Set, OR-Set, LWW-Element-Set 의 merge 함수를 코드로 구현한다
- Yjs / Automerge 같은 실서비스 라이브러리가 어떤 변형을 채택했는지와 그 이유를 분석한다

## 1. 분산 시스템의 충돌 — 왜 CRDT 인가

복제(replication)된 데이터는 동시 수정에서 충돌이 난다. Last-Write-Wins(LWW)는 단순하지만 늦게 도착한 update 가 사라지는 lossy 동작이다. Operational Transformation(OT)은 Google Docs 가 쓴 방식이지만 알고리즘이 복잡하고 중앙 서버 의존도가 높다.

CRDT 는 다른 길을 택한다. **자료구조 자체가 merge 함수 기준에서 수학적으로 합치는** 구조다. 충돌 해소 로직을 application 코드가 아니라 데이터 타입 자체가 담는다. 결과적으로 어느 순서로 merge 해도 같은 값에 수렴한다(strong eventual consistency).

수렴(convergence) 의 수학적 조건은 세 가지.

1. **교환법칙** (commutative): `merge(a, b) = merge(b, a)`
2. **결합법칙** (associative): `merge(merge(a, b), c) = merge(a, merge(b, c))`
3. **멱등성** (idempotent): `merge(a, a) = a`

이 세 조건을 만족하는 set 을 join semilattice 라고 부른다. CRDT 는 join-semilattice 위에서 정의된 자료구조다.

## 2. State-based vs Operation-based

CRDT 는 두 흐름으로 갈린다.

### 2.1 State-based (CvRDT — Convergent Replicated Data Type)

각 replica 는 자기 state 를 주기적으로 다른 replica 에게 보낸다. 받은 쪽은 `merge(local, received)` 를 수행한다. merge 가 join-semilattice 의 supremum(상한) 을 계산하기 때문에 어느 순서로 도착해도 결과가 같다.

장점: gossip protocol 위에 자연스럽게 얇힌다. 메시지 손실이나 중복에 강하다(idempotent).
주소: state 전체를 보내야 하므로 네트워크 비용이 크다. Delta-state CRDT 가 보완책이지만 구현 복잡도 증가.

### 2.2 Operation-based (CmRDT — Commutative Replicated Data Type)

operation 자체를 broadcast 한다. 모든 replica 가 같은 operation 집합을 받으면 같은 state 가 된다는 보장. 단, **causal broadcast** 가 필요하다 — operation 이 정확히 한 번 도달하고, 인과적으로 앞선 operation 이 먼저 적용돼야 한다.

장점: state 전체보다 operation 이 작아 네트워크 효율 좋음.
주소: causal delivery layer(Vector Clock + reorder buffer)가 필요. 구현 복잡.

## 3. G-Counter — 가장 단순한 CRDT

Grow-only Counter. 증가만 가능한 카운터. 각 replica 가 자기 ID 의 슬롯에만 increment 한다. merge 는 슬롯별 max.

```python
class GCounter:
    def __init__(self, replica_id: str):
        self.replica_id = replica_id
        self.counts: dict[str, int] = {replica_id: 0}

    def increment(self, n: int = 1):
        self.counts[self.replica_id] = self.counts.get(self.replica_id, 0) + n

    def value(self) -> int:
        return sum(self.counts.values())

    def merge(self, other: "GCounter") -> "GCounter":
        merged = GCounter(self.replica_id)
        keys = set(self.counts) | set(other.counts)
        for k in keys:
            merged.counts[k] = max(
                self.counts.get(k, 0), other.counts.get(k, 0)
            )
        return merged
```

수렴 검증:
- commutative: max 가 commutative
- associative: max 가 associative
- idempotent: `max(x, x) = x`

가시성 확인 — replica A 가 5번 증가, replica B 가 3번 증가:
```
A: {A:5, B:0}  value=5
B: {A:0, B:3}  value=3
A.merge(B): {A:5, B:3}  value=8
B.merge(A): {A:5, B:3}  value=8 ✓
```

## 4. PN-Counter — 감소도 가능

G-Counter 에 음의 카운터 P, N 두 개를 합치면 increment/decrement 둘 다 가능.

```python
class PNCounter:
    def __init__(self, replica_id: str):
        self.p = GCounter(replica_id)
        self.n = GCounter(replica_id)

    def increment(self, x: int = 1):
        self.p.increment(x)

    def decrement(self, x: int = 1):
        self.n.increment(x)

    def value(self) -> int:
        return self.p.value() - self.n.value()

    def merge(self, other: "PNCounter") -> "PNCounter":
        merged = PNCounter(self.p.replica_id)
        merged.p = self.p.merge(other.p)
        merged.n = self.n.merge(other.n)
        return merged
```

좋아요 / 싫어요 카운터, online 인원 카운터 등에 사용. 실제로는 음수 결과까지 표현 가능해서 "현재 잔액 추적" 같은 용도에는 잘 맞고, "물리적 재고 수량(음수 불가)" 에는 별도의 invariant 가 필요하다.

## 5. Set 계열 — G-Set, 2P-Set, OR-Set

### 5.1 G-Set (Grow-only Set)

추가만 가능한 set. merge 는 union.

```python
class GSet:
    def __init__(self):
        self.items: set = set()
    def add(self, x): self.items.add(x)
    def merge(self, other): r = GSet(); r.items = self.items | other.items; return r
```

union 은 commutative/associative/idempotent 다. 즉시 CRDT.

### 5.2 2P-Set (Two-Phase Set)

추가와 한 번의 삭제 가능. 추가 set + tombstone set 두 개. 삭제 후 재추가 불가.

```python
class TwoPSet:
    def __init__(self):
        self.added: set = set()
        self.removed: set = set()

    def add(self, x): self.added.add(x)
    def remove(self, x):
        if x in self.added:
            self.removed.add(x)

    def contains(self, x):
        return x in self.added and x not in self.removed

    def merge(self, other):
        r = TwoPSet()
        r.added = self.added | other.added
        r.removed = self.removed | other.removed
        return r
```

문제: 한 번 삭제된 element 는 영원히 삭제 상태. add → remove → add 를 표현 못한다.

### 5.3 OR-Set (Observed-Remove Set)

OR-Set 은 add 마다 unique tag(uuid) 를 붙인다. remove 는 그 시점에 보이는 tag 들을 tombstone 으로 묶는다. 같은 element 를 add → remove → add 해도 새 tag 로 다시 살아난다.

```python
import uuid

class ORSet:
    def __init__(self):
        self.elements: dict = {}
        self.tombstones: dict = {}

    def add(self, x):
        tag = str(uuid.uuid4())
        self.elements.setdefault(x, set()).add(tag)

    def remove(self, x):
        if x in self.elements:
            visible = self.elements[x] - self.tombstones.get(x, set())
            self.tombstones.setdefault(x, set()).update(visible)

    def contains(self, x):
        if x not in self.elements:
            return False
        visible = self.elements[x] - self.tombstones.get(x, set())
        return len(visible) > 0

    def merge(self, other):
        r = ORSet()
        for x, tags in self.elements.items():
            r.elements[x] = tags | other.elements.get(x, set())
        for x, tags in other.elements.items():
            r.elements.setdefault(x, set()).update(tags)
        for x, tags in self.tombstones.items():
            r.tombstones[x] = tags | other.tombstones.get(x, set())
        for x, tags in other.tombstones.items():
            r.tombstones.setdefault(x, set()).update(tags)
        return r
```

분산 set 을 자연스럽게 표현한다. Riak 의 `set` 데이터 타입이 OR-Set 기반이다. trade-off: tombstone 이 영구 누적된다.

## 6. LWW-Element-Set

OR-Set 의 tombstone 누적을 피하고 싶으면 timestamp 기반 LWW(Last-Write-Wins) Set 을 쓴다. add 와 remove 각각에 timestamp 를 기록하고, contains 판단 시 가장 늦은 작업이 add 면 in, remove 면 out.

```python
import time

class LWWElementSet:
    def __init__(self):
        self.added: dict = {}
        self.removed: dict = {}

    def add(self, x):
        ts = time.time()
        self.added[x] = max(self.added.get(x, 0), ts)

    def remove(self, x):
        ts = time.time()
        self.removed[x] = max(self.removed.get(x, 0), ts)

    def contains(self, x):
        a = self.added.get(x, -1)
        r = self.removed.get(x, -1)
        return a > r

    def merge(self, other):
        r = LWWElementSet()
        for k, v in self.added.items():
            r.added[k] = max(v, other.added.get(k, 0))
        for k, v in other.added.items():
            r.added.setdefault(k, v)
        for k, v in self.removed.items():
            r.removed[k] = max(v, other.removed.get(k, 0))
        for k, v in other.removed.items():
            r.removed.setdefault(k, v)
        return r
```

타이브레이커 규칙(예: add 우선) 을 명시적으로 정해야 동률 timestamp 처리가 결정적이다. 그리고 timestamp 가 wall clock 이면 clock skew 문제가 따라온다. Hybrid Logical Clock(HLC) 을 쓰면 이 문제를 완화할 수 있다.

## 7. RGA — 시퀀스/텍스트 CRDT

리스트와 텍스트는 set 보다 까다롭다. order 가 있고 동일 위치에 동시 insert 가 일어날 수 있기 때문이다. RGA(Replicated Growable Array)는 각 element 에 (timestamp, replica_id) 를 부여하고, 새 insert 는 "이 element 다음에" 라는 부모 reference 를 가진다.

핵심 아이디어:

- 각 노드는 unique ID `(t, rid)` 와 parent ID 를 가진다
- 형제 노드들의 정렬 순서는 (timestamp 큰 것이 앞 || timestamp 같으면 rid 사전순)
- delete 는 tombstone 으로 마킹

이 구조 위에서 두 사용자가 같은 자리에 동시에 insert 하면, RGA 의 sibling ordering 이 deterministic 하게 결과를 결정한다. Yjs 의 YText 가 RGA 의 변형이고, Automerge 도 유사 패턴을 쓴다.

복잡도: 위치 검색이 O(log n) tree 또는 B-tree 기반 구현으로 처리. 1만자 문서까지는 메모리에서 충분히 빠름. 100만자 단위에서는 layout caching 과 tombstone GC 가 운영 키 포인트.

## 8. 실전 라이브러리 비교

| 라이브러리 | 변형 | 주력 사용 |
|---|---|---|
| Yjs | YText/YArray/YMap (state-based 변형) | 협업 에디터(Notion-like) |
| Automerge | Op-based + columnar storage | 오프라인 우선 앱 |
| Riak Datatype | OR-Set / PN-Counter / Map | 분산 KV |
| Redis (CRDB) | counter / set 기반 | Active-Active 다중 DC |
| AntidoteDB | poly-CRDT | 학술/연구 |

Yjs 는 binary update encoding 으로 1 character insert 가 ~10 bytes 수준. 네트워크 비용이 매우 적다. operation log 를 columnar 압축한 Automerge 는 history 가 길어져도 스토리지가 선형 증가에 가깝다.

Redis Enterprise CRDB(Conflict-free Replicated DB) 는 multi-region active-active 구성에서 PN-Counter / OR-Set / LWW-Register 를 채택해 같은 key 에 대한 concurrent write 가 자동 머지된다. application 입장에서는 일반 Redis API 와 동일.

## 9. CRDT 의 한계와 함께 쓸 보완책

CRDT 가 만능은 아니다.

**Strong invariant 표현 불가**. "재고 수량이 음수가 되면 안 된다" 같은 unique constraint 는 CRDT 단독으로 보장 불가. PN-Counter 는 단순 수렴 보장만 하고 음수 차단은 application layer 의 saga / compensating transaction 으로 처리해야 한다.

**Tombstone GC 비용**. OR-Set, RGA 모두 tombstone 누적 → 메모리 / 네트워크 비용이 시간에 따라 증가. causal stability 가 보장된 epoch 이후 tombstone 을 GC 하는 알고리즘(예: Riak 의 dotted version vector)이 필요하다.

**Causal broadcast 의존(op-based)**. 메시지 ordering 보장 layer 가 따로 있어야 한다. UDP 위에서 그냥 broadcast 하면 의도대로 수렴하지 않는다. TCP / QUIC 위 Vector Clock 첨부, 또는 anti-entropy gossip 로 해결.

**의미 충돌 vs 구문 충돌**. 같은 셀에 100원과 200원이 동시 입력됐을 때 LWW 는 200원만 남길 뿐 사용자가 원한 의미("두 개 합산")를 알 수 없다. CRDT 는 구문 수렴을 보장할 뿐 의미 수렴은 application 도메인 문제로 남는다.

## 참고

- Marc Shapiro et al. — A comprehensive study of CRDTs (Inria 논문)
- "Conflict-free Replicated Data Types" 위키북 / 학술 자료
- Yjs 공식 docs — Y.Doc / shared types: https://docs.yjs.dev/
- Automerge — Building Local-first Software: https://www.inkandswitch.com/local-first/
- Riak Docs — Data Types: https://docs.riak.com/riak/kv/latest/developing/data-types/
