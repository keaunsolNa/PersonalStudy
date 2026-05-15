Notion 원본: https://www.notion.so/3615a06fd6d38139bdf6d80f23952f43

# CRDT Conflict-free Replicated Data Type G-Counter OR-Set 분산 동기화

> 2026-05-15 신규 주제 · 확장 대상: 분산 시스템 / 협업 도구

## 학습 목표

- State-based(CvRDT) 와 Operation-based(CmRDT) CRDT 의 차이를 join semi-lattice 관점에서 정리한다
- G-Counter / PN-Counter / G-Set / OR-Set / LWW-Element-Set 의 머지 함수를 구현 코드로 분석한다
- Tombstone 누적 문제와 그것을 해결하는 Δ-CRDT, RGA, Causal Tree 접근을 비교한다
- Yjs / Automerge 같은 실제 라이브러리가 협업 편집에 적용하는 알고리즘을 추적한다

## 1. CRDT 가 풀려는 문제

분산 시스템에서 여러 노드가 같은 데이터를 가지고 있고 네트워크 분할로 잠시 통신이 끊겼다고 하자. 각자 쓰기를 받아도 통신 복구 시 자동으로 같은 상태로 수렴해야 한다. 이를 **Strong Eventual Consistency (SEC)** 라 한다. 조건은 모든 쓰기가 모든 노드에 결국 전달되고(eventual delivery), 같은 쓰기 집합을 본 두 노드는 같은 상태가 되어야 한다(convergence). CRDT 는 데이터 타입과 머지 함수를 수학적 구조로 설계해 이 조건을 보장한다. 핵심: 머지 함수 ⊔ 가 commutative, associative, idempotent(CAI) 면 어떤 순서로 머지해도 같은 결과가 된다.

## 2. 두 가지 형태: State-based vs Operation-based

**State-based (CvRDT)** 는 전체 상태를 주고받는다. 머지는 join semi-lattice 의 least upper bound (lub). 메시지 손실/순서 무관하고 idempotent 라 안전하지만 큰 데이터는 비용이 크다. **Operation-based (CmRDT)** 는 연산만 전송한다. 메시지가 작지만 exactly-once delivery 또는 causal delivery 채널이 필요하다.

대부분의 실용 라이브러리(Yjs, Automerge, Riak DT)는 **Δ-state CRDT** 라는 하이브리드를 쓴다. 변경 부분만 state 형태로 보내되 머지는 idempotent.

## 3. G-Counter (Grow-only Counter)

```ts
class GCounter {
    private counts: Map<NodeId, number> = new Map()
    constructor(private nodeId: NodeId) {}
    increment(amount = 1): void {
        const cur = this.counts.get(this.nodeId) ?? 0
        this.counts.set(this.nodeId, cur + amount)
    }
    value(): number {
        let sum = 0
        for (const v of this.counts.values()) sum += v
        return sum
    }
    merge(other: GCounter): void {
        for (const [nodeId, v] of other.counts) {
            const cur = this.counts.get(nodeId) ?? 0
            this.counts.set(nodeId, Math.max(cur, v))
        }
    }
}
```

`Math.max` 가 CAI 를 만족. 같은 increment 가 여러 번 머지되어도 결과 동일. **한계**: 감소 불가. 페이지뷰 / 좋아요 누적 같은 단방향 카운터에만 적합.

## 4. PN-Counter (Positive-Negative)

G-Counter 두 개로 증가/감소를 분리. `value() = P.value() - N.value()`. 음수 가능. 다만 같은 element 를 두 번 감소해도 멱등성 때문에 막을 방법이 없다는 한계는 그대로.

## 5. G-Set, OR-Set, LWW-Element-Set

집합 CRDT 는 항목 추가/제거 의미가 까다롭다. **G-Set**: 추가만. 머지 = 합집합. **2P-Set**: 추가 / 제거 분리, 한번 제거되면 다시 추가 불가. **OR-Set (Observed-Remove Set)**: 가장 실용적. 각 추가에 고유 ID 부여, 제거는 "관찰한 ID들"만 지움.

```ts
type Tag = string

class ORSet<T> {
    private elements = new Map<T, Set<Tag>>()
    private tombstones = new Set<Tag>()
    constructor(private nodeId: NodeId) {}
    add(elem: T): void {
        const tag = `${this.nodeId}:${crypto.randomUUID()}`
        if (!this.elements.has(elem)) this.elements.set(elem, new Set())
        this.elements.get(elem)!.add(tag)
    }
    remove(elem: T): void {
        const tags = this.elements.get(elem)
        if (!tags) return
        for (const t of tags) this.tombstones.add(t)
        this.elements.delete(elem)
    }
    has(elem: T): boolean {
        const tags = this.elements.get(elem)
        if (!tags) return false
        for (const t of tags) if (!this.tombstones.has(t)) return true
        return false
    }
    merge(other: ORSet<T>): void {
        for (const t of other.tombstones) this.tombstones.add(t)
        for (const [elem, tags] of other.elements) {
            const target = this.elements.get(elem) ?? new Set<Tag>()
            for (const t of tags) if (!this.tombstones.has(t)) target.add(t)
            if (target.size > 0) this.elements.set(elem, target)
        }
    }
}
```

**핵심**: "동시 add + remove" 시 add 가 이긴다. 노드 A 가 `x` 를 추가한 직후 노드 B 가 `x` 제거를 시도해도, B 는 A 의 새 tag 를 모르므로 그 tag 는 tombstone 에 들어가지 않는다. **LWW-Element-Set**: 각 추가/제거에 timestamp, 마지막 쓰기가 이긴다. 시계 동기화 필요(Lamport / HLC).

## 6. Tombstone 누적 문제

OR-Set 의 tombstone 은 영원히 남는다. 해결책: **Causal Stability** (모든 노드가 "이 tombstone 이전의 모든 메시지를 봤다" vector clock 합의 도달 시 GC), **Bounded Counter / Bounded Set** (도메인 제약), **Δ-state CRDT** (마지막 동기화 이후의 delta 만 전송, Riak / SoundCloud Roshi 적용).

## 7. 순서가 있는 컬렉션 — RGA / Causal Tree

**RGA (Replicated Growable Array)**: 각 글자에 (timestamp, nodeId), 새 글자는 "어떤 글자 다음에 들어간다" 명시. 같은 위치에 두 노드가 동시 삽입하면 timestamp 큰 쪽이 앞.

```ts
interface Char {
    id: [number, NodeId]    // (lamport, nodeId)
    after: [number, NodeId]
    char: string
    deleted: boolean
}
```

**Causal Tree (Yjs, Logoot)**: 글자마다 "부모", 형제들은 ID 로 정렬되는 트리. 텍스트는 트리의 in-order 순회. Yjs 의 `Y.Text` 가 이 모델을 따르며 매우 효율적인 운영 압축(GC + 인접 글자 병합).

## 8. 실제 라이브러리 비교

| 라이브러리 | 모델 | 강점 | 약점 |
|---|---|---|---|
| Yjs | 자체 CRDT (RGA-like) | 매우 작은 메시지, 빠름 | 마이그레이션 까다로움 |
| Automerge | 자체 CRDT (Hash-CRDT) | JSON 풍부 모델 | 큰 문서에서 느림 |
| Riak DT | OR-Set, Map, Counter | 운영 인프라 통합 | range query 약함 |
| Redis CRDB | LWW 중심 | Redis 친화 | LWW 한계 |

```ts
import * as Y from 'yjs'
const doc = new Y.Doc()
const text = doc.getText('content')
text.insert(0, 'Hello, ')
text.insert(7, 'world!')
doc.transact(() => Y.applyUpdate(doc, updateFromPeer))
```

`Y.applyUpdate` 가 머지 함수에 해당. 항상 idempotent.

## 9. CRDT 가 적합하지 않은 케이스

- **강한 invariant** ("재고는 0 미만이 될 수 없다") → CRDT 로 표현 불가. SEC 가 invariant 를 자동 보장하지 않는다.
- **트랜잭션** (여러 키의 원자적 변경) → CRDT 는 키 단위 수렴.
- **사용자가 충돌을 보고 결정** → 자동 머지가 의도와 다를 수 있음.

이런 경우엔 Raft / Paxos 기반 합의나 OT (Operational Transformation) 가 더 맞다.

## 10. 트레이드오프 정리

- **머지 함수의 CAI**: 수학적 보장이지만 구현 버그 한 줄이 전체 일관성을 깬다. 단위 테스트 필수.
- **tombstone GC**: 메시지 도달 가시성 필요. fully p2p 에선 어려움.
- **메시지 크기 vs 머지 비용**: state-based 크고 op-based 가벼움.
- **OT vs CRDT**: OT 는 서버 중심, intention preservation. CRDT 는 p2p 강함, 자동 머지.

## 참고

- Shapiro et al., "Conflict-Free Replicated Data Types" (2011): https://hal.inria.fr/inria-00609399v1/document
- Yjs 문서: https://docs.yjs.dev/
- Automerge: https://automerge.org/
- Riak DT: https://docs.riak.com/riak/kv/latest/learn/concepts/crdts/
- Almeida, "Δ-state CRDT" (2018): https://arxiv.org/abs/1603.01529
- Martin Kleppmann 강의: "CRDTs for Mortals"
