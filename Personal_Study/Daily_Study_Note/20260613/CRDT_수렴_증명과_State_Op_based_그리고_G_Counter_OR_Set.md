Notion 원본: https://www.notion.so/37e5a06fd6d381949dc9e4bb8489c9db

# CRDT 수렴 증명과 State · Op-based 그리고 G-Counter · OR-Set

> 2026-06-13 신규 주제 · 확장 대상: CS

## 학습 목표

- CRDT가 락·합의 없이 강한 최종 일관성(SEC)을 달성하는 수학적 조건을 설명한다
- State-based(CvRDT)와 Op-based(CmRDT)의 전송 모델과 요구 조건 차이를 구분한다
- G-Counter·PN-Counter·G-Set·OR-Set의 병합 규칙과 한계를 직접 구현 수준으로 안다
- 동시 삭제/재추가 같은 충돌 시나리오에서 OR-Set이 의도를 보존하는 원리를 설명한다

## 1. 왜 CRDT인가

다중 노드가 각자 쓰기를 받고 비동기로 복제하는 시스템에서 강한 일관성은 합의(Paxos/Raft)나 락을 요구하며, 이는 가용성과 지연을 희생한다. CRDT(Conflict-free Replicated Data Type)는 다른 접근을 택한다 — 자료구조 자체를 "어떤 순서로 병합해도 같은 결과에 수렴"하도록 설계해, 조정 없이도 모든 복제본이 결국 동일 상태가 되게 한다. 이를 강한 최종 일관성(Strong Eventual Consistency, SEC)이라 한다: 같은 업데이트 집합을 본 두 복제본은 즉시 같은 상태다.

## 2. 수렴의 수학적 조건

State-based CRDT의 수렴은 상태들이 **결합 반격자(join-semilattice)**를 이루고, 병합 함수가 그 격자의 최소 상계(least upper bound, ⊔)일 때 보장된다. 격자의 병합 연산이 만족해야 할 세 성질은:

- **교환법칙(commutativity)**: `a ⊔ b = b ⊔ a` — 병합 순서 무관
- **결합법칙(associativity)**: `(a ⊔ b) ⊔ c = a ⊔ (b ⊔ c)` — 그룹핑 무관
- **멱등성(idempotency)**: `a ⊔ a = a` — 같은 상태 중복 수신 무해

이 세 성질이 성립하면, 메시지 재정렬·중복·지연이 있어도(단 결국 전달되기만 하면) 모든 복제본이 동일한 상계로 수렴한다. 네트워크가 at-least-once 전달만 보장하면 되므로 인프라 요구가 낮다.

## 3. State-based vs Op-based

State-based(CvRDT)는 복제본이 **전체 상태**를 주고받아 `merge`로 합친다. 멱등·교환·결합이 보장되면 어떤 통신 패턴(가십 등)에서도 안전하지만, 상태 전체 전송은 대역폭 비용이 크다(델타 CRDT가 이를 완화).

Op-based(CmRDT)는 **연산(operation)**만 전파한다. 대역폭은 작지만, 연산이 정확히 한 번(exactly-once) 그리고 인과적 순서(causal order)로 전달돼야 하며, 동시(concurrent) 연산들은 서로 교환 가능해야 한다. 따라서 신뢰성 있는 causal broadcast 미들웨어가 필요하다.

```text
CvRDT: 전체 상태 전송 + 멱등 merge   → 전달 보장 약해도 OK, 대역폭 큼
CmRDT: 연산만 전송 + 교환 가능 연산  → 정확히 한 번·인과순 전달 필요, 대역폭 작음
```

## 4. G-Counter — 증가만 가능한 카운터

가장 단순한 CvRDT. 각 복제본 i는 자기 카운트만 증가시키고, 상태는 "복제본별 카운트 벡터"다. 값은 벡터 원소의 합, 병합은 원소별 max다.

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

원소별 max는 교환·결합·멱등을 만족한다. 같은 상태를 두 번 병합해도(`max(a,a)=a`) 안전하고, 두 복제본이 각자 증가한 뒤 서로 병합하면 양쪽 증가가 모두 보존된다. "마지막 쓰기가 이긴다"가 아니라 "각 노드의 최대 관측치를 취한다"는 점이 핵심이다.

## 5. PN-Counter — 감소 지원

G-Counter는 감소가 불가능하다(max 병합이 깨짐). PN-Counter는 증가용 G-Counter `P`와 감소용 G-Counter `N` 두 개를 쌍으로 들고, 값은 `sum(P) - sum(N)`으로 계산한다. 두 카운터 각각이 CvRDT이므로 쌍도 CvRDT가 된다. 이처럼 작은 CRDT를 곱(product)으로 합성해 더 복잡한 CRDT를 만드는 것이 일반적 설계 기법이다.

## 6. G-Set과 2P-Set의 한계

G-Set(grow-only set)은 추가만 가능하고 병합은 합집합이다 — 합집합은 교환·결합·멱등이므로 즉시 CRDT다. 삭제를 넣으려는 첫 시도인 2P-Set(Two-Phase Set)은 추가 집합 `A`와 제거 집합(tombstone) `R`을 두고 `A - R`을 값으로 본다. 하지만 한 번 제거된 원소는 `R`에 영구히 남아 재추가가 불가능하고, "동시 추가 vs 제거" 시 제거가 무조건 이긴다는 편향이 있다. 실무 요구(삭제 후 재추가)를 만족하지 못한다.

## 7. OR-Set — 동시 추가/삭제의 정확한 해석

OR-Set(Observed-Remove Set)은 각 추가에 고유 태그(예: `(element, unique-id)`)를 붙여 이 문제를 푼다. 삭제는 "삭제 시점에 관측한 태그들"만 제거 대상으로 표시한다. 따라서 삭제와 동시에 일어난(아직 관측되지 않은) 추가는 살아남는다.

```python
class ORSet:
    def __init__(self):
        self.adds = {}     # element -> set of tags
        self.removes = {}  # element -> set of removed tags

    def add(self, e, tag):           # tag는 전역 고유(node+counter)
        self.adds.setdefault(e, set()).add(tag)

    def remove(self, e):             # 현재 관측된 태그만 제거
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

시나리오: 복제본 A가 원소 x를 추가(tag t1)하고, 동시에 복제본 B가 (t1을 관측한 상태에서) x를 삭제한다. 그 사이 A가 x를 다시 추가(tag t2)한다. 병합하면 removes에는 t1만 있고 adds에는 {t1, t2}가 있어, `{t1,t2} - {t1} = {t2}`가 비어있지 않으므로 x는 **존재**한다. 즉 "동시 추가는 삭제를 이긴다(add-wins)"는 직관적 의미가 보존된다. 비용은 tombstone(제거된 태그) 누적이며, 이를 정리하려면 인과 컨텍스트(버전 벡터) 기반의 최적화 OR-Set이나 주기적 가비지 컬렉션이 필요하다.

## 8. LWW-Register와 버전 벡터

단일 값을 담는 레지스터에서 동시 쓰기를 해소하는 가장 단순한 CRDT는 LWW-Register(Last-Write-Wins)다. 각 쓰기에 타임스탬프를 붙이고 병합 시 더 큰 타임스탬프를 채택한다.

```python
class LWWRegister:
    def __init__(self):
        self.value = None
        self.ts = (0, 0)   # (timestamp, node_id) — tie-break용 노드 ID

    def set(self, value, ts, node):
        if (ts, node) > self.ts:
            self.value, self.ts = value, (ts, node)

    def merge(self, other):
        if other.ts > self.ts:
            self.value, self.ts = other.value, other.ts
```

타임스탬프가 같을 때를 위해 노드 ID로 전순서(total order)를 만들어 결정성을 확보한다. LWW는 단순하지만 동시 쓰기 중 하나를 **버린다** — 두 변경이 모두 의미 있어도 한쪽이 소실되므로, 손실이 허용되는 설정값 등에만 적합하다. 물리 시계의 클럭 스큐 문제를 줄이려면 단순 타임스탬프 대신 Hybrid Logical Clock을 쓴다.

벡터 클럭/버전 벡터는 두 업데이트가 인과적으로 선후 관계인지, 아니면 진짜 동시인지를 판별하는 도구다. OR-Set의 최적화 버전과 텍스트 CRDT가 "무엇이 동시 발생인가"를 정확히 알기 위해 이를 사용한다. 동시 발생을 정확히 식별해야 add-wins/remove-wins 같은 충돌 정책을 의도대로 적용할 수 있다.

## 9. Delta-State CRDT — 대역폭 최적화

기본 State-based CRDT의 약점은 매번 전체 상태를 전송한다는 것이다. 집합이 수만 원소면 변경 하나에 전체를 보내는 낭비가 생긴다. Delta-State CRDT(δ-CRDT)는 "이번 변경으로 생긴 차이(delta)"만 전파하되, delta 자체도 join-semilattice의 원소라 기존 merge로 안전하게 합쳐진다. 즉 Op-based의 대역폭 효율과 State-based의 전달 보장 완화(중복·재정렬 내성)를 절충한다. Akka Distributed Data, Redis CRDT 등 실전 구현이 δ-CRDT를 채택해 가십 프로토콜 위에서 효율적으로 동작한다.

## 10. 실무 적용과 한계

CRDT는 공동 편집(텍스트 CRDT: RGA, YATA — Yjs/Automerge), 분산 KV 스토어(Redis CRDT, Riak), 오프라인 우선 모바일 동기화에 쓰인다. 장점은 중앙 조정 없이 오프라인 쓰기·즉시 로컬 반영·자동 충돌 해소를 제공한다는 것이다. 한계도 분명하다: (1) tombstone·메타데이터로 인한 공간 증가, (2) "수렴은 보장하지만 수렴한 값이 항상 사용자 의도와 일치하지는 않음"(예: 동시 편집의 의미적 충돌은 못 막음), (3) 전역 불변식(예: "잔고는 음수 불가")을 강제할 수 없어 강한 제약이 필요한 도메인엔 부적합. 따라서 CRDT는 "가용성·낮은 지연·자동 병합"이 일관성 제약보다 중요한 협업·복제 시나리오에 선택적으로 적용하는 도구다.

## 참고

- Shapiro et al., "Conflict-free Replicated Data Types" (2011): https://hal.inria.fr/inria-00609399
- "A comprehensive study of CRDTs" (INRIA technical report)
- Automerge / Yjs 문서: https://automerge.org/ , https://docs.yjs.dev/
- Riak DT / Redis CRDT 운영 문서
