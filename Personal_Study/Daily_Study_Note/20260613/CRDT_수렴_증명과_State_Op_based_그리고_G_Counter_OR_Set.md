Notion 원본: https://www.notion.so/37e5a06fd6d381949dc9e4bb8489c9db

# CRDT 수렴 증명과 State · Op-based 그리고 G-Counter · OR-Set

> 2026-06-13 신규 주제 · 확장 대상: CS

## 학습 목표

- CRDT가 락·합의 없이 강한 최종 일관성(SEC)을 달성하는 수학적 조건을 설명한다
- State-based(CvRDT)와 Op-based(CmRDT)의 전송 모델과 요구 조건 차이를 구분한다
- G-Counter·PN-Counter·G-Set·OR-Set의 병합 규칙과 한계를 구현 수준으로 안다
- 동시 삭제/재추가 시나리오에서 OR-Set이 의도를 보존하는 원리를 설명한다

## 1. 왜 CRDT인가

다중 노드가 각자 쓰기를 받고 비동기로 복제하는 시스템에서 강한 일관성은 합의(Paxos/Raft)나 락을 요구하며 가용성과 지연을 희생한다. CRDT는 자료구조 자체를 어떤 순서로 병합해도 같은 결과에 수렴하도록 설계해, 조정 없이도 모든 복제본이 결국 동일 상태가 되게 한다. 이를 강한 최종 일관성(SEC)이라 한다: 같은 업데이트 집합을 본 두 복제본은 즉시 같은 상태다.

## 2. 수렴의 수학적 조건

State-based CRDT의 수렴은 상태들이 결합 반격자(join-semilattice)를 이루고 병합 함수가 그 격자의 최소 상계(⊔)일 때 보장된다. 병합 연산이 만족해야 할 세 성질:

- 교환법칙: a ⊔ b = b ⊔ a (병합 순서 무관)
- 결합법칙: (a ⊔ b) ⊔ c = a ⊔ (b ⊔ c) (그룹핑 무관)
- 멱등성: a ⊔ a = a (중복 수신 무해)

이 세 성질이 성립하면 메시지 재정렬·중복·지연이 있어도 모든 복제본이 동일 상계로 수렴한다. 네트워크가 at-least-once 전달만 보장하면 된다.

## 3. State-based vs Op-based

State-based(CvRDT)는 전체 상태를 주고받아 merge로 합친다. 멱등·교환·결합이 보장되면 어떤 통신 패턴에서도 안전하지만 대역폭 비용이 크다(델타 CRDT가 완화). Op-based(CmRDT)는 연산만 전파한다. 대역폭은 작지만 연산이 정확히 한 번·인과적 순서로 전달돼야 하며 동시 연산들은 교환 가능해야 한다. 따라서 신뢰 있는 causal broadcast 미들웨어가 필요하다.

## 4. G-Counter — 증가만 가능한 카운터

```python
class GCounter:
    def __init__(self, node_id, num_nodes):
        self.node = node_id
        self.counts = [0] * num_nodes
    def increment(self):
        self.counts[self.node] += 1
    def value(self):
        return sum(self.counts)
    def merge(self, other):
        self.counts = [max(a, b) for a, b in zip(self.counts, other.counts)]
```

원소별 max는 교환·결합·멱등을 만족한다. 마지막 쓰기가 이기는 게 아니라 각 노드의 최대 관측치를 취한다는 게 핵심이다.

## 5. PN-Counter — 감소 지원

G-Counter는 감소가 불가하다(max 병합이 깨짐). PN-Counter는 증가용 G-Counter `P`와 감소용 `N` 두 개를 쌍으로 들고 값은 `sum(P) - sum(N)`으로 계산한다. 작은 CRDT를 곱(product)으로 합성해 복잡한 CRDT를 만드는 일반적 설계 기법이다.

## 6. G-Set과 2P-Set의 한계

G-Set은 추가만 가능하고 병합은 합집합이라 즉시 CRDT다. 2P-Set은 추가 집합 A와 제거 tombstone R을 두고 A - R을 값으로 본다. 하지만 한 번 제거된 원소는 R에 영구히 남아 재추가가 불가하고, 동시 추가 vs 제거 시 제거가 무조건 이긴다.

## 7. OR-Set — 동시 추가/삭제의 정확한 해석

```python
class ORSet:
    def __init__(self):
        self.adds = {}     # element -> set of tags
        self.removes = {}  # element -> set of removed tags
    def add(self, e, tag):
        self.adds.setdefault(e, set()).add(tag)
    def remove(self, e):
        observed = self.adds.get(e, set()) - self.removes.get(e, set())
        self.removes.setdefault(e, set()).update(observed)
    def contains(self, e):
        return bool(self.adds.get(e, set()) - self.removes.get(e, set()))
    def merge(self, other):
        for e, tags in other.adds.items():
            self.adds.setdefault(e, set()).update(tags)
        for e, tags in other.removes.items():
            self.removes.setdefault(e, set()).update(tags)
```

각 추가에 고유 태그를 붙이고, 삭제는 삭제 시점에 관측한 태그만 제거한다. A가 x를 추가(t1)하고 B가 (t1 관측 후) 삭제, 그 사이 A가 x를 다시 추가(t2)하면, 병합 결과 removes={t1}, adds={t1,t2} → {t2}가 남아 x는 존재한다. 즉 동시 추가는 삭제를 이긴다(add-wins). 비용은 tombstone 누적이며 버전 벡터 기반 최적화나 GC가 필요하다.

## 8. LWW-Register와 버전 벡터

```python
class LWWRegister:
    def __init__(self):
        self.value = None
        self.ts = (0, 0)   # (timestamp, node_id)
    def set(self, value, ts, node):
        if (ts, node) > self.ts:
            self.value, self.ts = value, (ts, node)
    def merge(self, other):
        if other.ts > self.ts:
            self.value, self.ts = other.value, other.ts
```

타임스탬프가 같을 때 노드 ID로 전순서를 만들어 결정성을 확보한다. LWW는 단순하지만 동시 쓰기 중 하나를 버린다 — 손실이 허용되는 설정값 등에만 적합하다. 버전 벡터는 두 업데이트가 인과적 선후인지 진짜 동시인지 판별해 add-wins/remove-wins 정책을 의도대로 적용하게 한다.

## 9. Delta-State CRDT — 대역폭 최적화

기본 State-based는 매번 전체 상태를 전송하는 낙비가 있다. δ-CRDT는 이번 변경의 차이(delta)만 전파하되, delta 자체도 join-semilattice의 원소라 기존 merge로 안전하게 합쳐진다. Op-based의 대역폭 효율과 State-based의 전달 보장 완화를 절충한다. Akka Distributed Data, Redis CRDT 등이 채택한다.

## 10. 실무 적용과 한계

CRDT는 공동 편집(Yjs/Automerge), 분산 KV(Redis CRDT, Riak), 오프라인 우선 동기화에 쓰인다. 장점은 중앙 조정 없이 오프라인 쓰기·즉시 로컬 반영·자동 충돌 해소다. 한계는 tombstone으로 인한 공간 증가, 수렴한 값이 항상 의도와 일치하지는 않음(의미적 충돌은 못 막음), 전역 불변식(잔고 음수 불가)을 강제할 수 없음이다. 따라서 가용성·낮은 지연·자동 병합이 일관성 제약보다 중요한 시나리오에 선택적으로 적용한다.

## 참고

- Shapiro et al., "Conflict-free Replicated Data Types" (2011): https://hal.inria.fr/inria-00609399
- A comprehensive study of CRDTs (INRIA technical report)
- Automerge / Yjs: https://automerge.org/ , https://docs.yjs.dev/
- Riak DT / Redis CRDT 운영 문서
