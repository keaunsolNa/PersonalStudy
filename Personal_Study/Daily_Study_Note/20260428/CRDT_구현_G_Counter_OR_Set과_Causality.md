Notion 원본: https://www.notion.so/3505a06fd6d3810fb59aece6e31b9133

# CRDT 구현 — G-Counter, OR-Set과 Causality 추적

> 2026-04-28 신규 주제 · 확장 대상: 자료구조&알고리즘, 면접을_위한_CS_전공지식_노트

## 학습 목표

- State-based CRDT(CvRDT)와 Operation-based CRDT(CmRDT)의 합의 비용 차이를 ε-quasi commutativity 관점에서 비교한다.
- G-Counter, PN-Counter, OR-Set, LWW-Element-Set 의 merge 연산을 직접 구현해 join semilattice 성질을 검증한다.
- vector clock vs version vector vs dotted version vector(DVV)의 인과 관계 추적 차이를 코드로 본다.
- Yjs/Automerge 같은 협업 편집 라이브러리가 RGA / WOOT / Y-Tree 어떤 자료구조를 쓰는지 정리한다.

## 1. 왜 CRDT인가

분산 환경에서 동일 데이터를 여러 노드가 동시에 갱신하면 충돌이 생긴다. 전통 해법은 strong consistency(quorum-based, single leader, two-phase commit)이지만 latency와 가용성 비용이 크다. CRDT는 "merge 연산이 commutative + associative + idempotent"이라는 수학적 성질을 데이터 타입에 직접 부여하여, 노드끼리 임의 순서로 update를 교환해도 결국 같은 상태로 수렴(strong eventual consistency)한다는 보장을 만든다. 이 성질을 join semilattice라 부르며 다음 세 조건이 모두 성립해야 한다.

`a ⊔ b = b ⊔ a` (commutative), `(a ⊔ b) ⊔ c = a ⊔ (b ⊔ c)` (associative), `a ⊔ a = a` (idempotent).

## 2. G-Counter — 가장 단순한 예

G-Counter는 monotonic increment-only counter이다. 노드별 카운터 값을 map에 저장하고, value는 모든 노드 값의 합으로 정의한다. merge는 노드별 max를 취한다.

```java
public final class GCounter {
    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    public void increment(String nodeId) { counts.merge(nodeId, 1L, Long::sum); }
    public long value() { return counts.values().stream().mapToLong(Long::longValue).sum(); }
    public GCounter merge(GCounter other) {
        GCounter result = new GCounter();
        Stream.concat(counts.keySet().stream(), other.counts.keySet().stream())
              .distinct()
              .forEach(k -> result.counts.put(k, Math.max(
                      counts.getOrDefault(k, 0L),
                      other.counts.getOrDefault(k, 0L))));
        return result;
    }
}
```

`max`는 자명하게 commutative/associative/idempotent이므로 G-Counter는 valid CvRDT이다. 단점은 값을 감소시킬 수 없다는 점이다.

## 3. PN-Counter — increment + decrement

G-Counter 두 개를 합쳐 P(positive)와 N(negative)로 두고, value = P − N. 감소도 단조 증가하는 카운터로 표현해서 join semilattice 성질을 유지한다. 이 패턴은 "역할별로 monotonic component를 분리하면 monotonicity가 유지된다"는 일반 원리를 보여준다.

## 4. OR-Set — Add/Remove 가능한 집합

집합에 element를 add/remove 할 수 있어야 하는데, 단순 union/diff로는 commutative 하지 않다. (A: add x, B: remove x를 동시에 하면 결과가 정해지지 않는다.) OR-Set은 add 시점에 unique tag를 같이 부여하고, remove는 그 tag를 tombstone에 기록한다.

```java
public final class ORSet<E> {
    private final Map<E, Set<UUID>> add = new HashMap<>();
    private final Map<E, Set<UUID>> rem = new HashMap<>();

    public void add(E elem) {
        add.computeIfAbsent(elem, k -> new HashSet<>()).add(UUID.randomUUID());
    }
    public void remove(E elem) {
        Set<UUID> tags = add.getOrDefault(elem, Set.of());
        rem.computeIfAbsent(elem, k -> new HashSet<>()).addAll(tags);
    }
    public Set<E> value() {
        Set<E> out = new HashSet<>();
        for (var e : add.keySet()) {
            Set<UUID> alive = new HashSet<>(add.get(e));
            alive.removeAll(rem.getOrDefault(e, Set.of()));
            if (!alive.isEmpty()) out.add(e);
        }
        return out;
    }
    public ORSet<E> merge(ORSet<E> o) {
        ORSet<E> r = new ORSet<>();
        Stream.of(this.add, o.add).flatMap(m -> m.entrySet().stream())
              .forEach(en -> r.add.computeIfAbsent(en.getKey(), k -> new HashSet<>()).addAll(en.getValue()));
        Stream.of(this.rem, o.rem).flatMap(m -> m.entrySet().stream())
              .forEach(en -> r.rem.computeIfAbsent(en.getKey(), k -> new HashSet<>()).addAll(en.getValue()));
        return r;
    }
}
```

핵심 의미는 "동일 element의 add와 remove가 concurrent 하면 add 가 이긴다"이다. tombstone에는 add 시점에 발급된 tag만 들어가므로 새 add는 옛 remove에 묻히지 않는다. 단점은 tombstone이 영원히 남아 메모리가 누적된다는 것. 실서비스에서는 주기적으로 GC 또는 epoch 기반 압축이 필요하다.

## 5. LWW-Element-Set — Last-Writer-Wins

타임스탬프를 비교해 더 최근의 연산이 이긴다. 시계 동기화에 의존한다는 점이 본질적 약점. NTP가 흔들리면 결과가 흔들린다. Hybrid Logical Clock(HLC)을 쓰면 wall-clock과 logical 단조성을 결합해 안정성을 높일 수 있다.

| 데이터 타입 | concurrent add/remove 시 | 시계 의존 | tombstone |
| --- | --- | --- | --- |
| OR-Set | add wins | 없음 | 누적 |
| LWW-Element-Set | timestamp 큰 쪽 wins | 강하게 의존 | 부분 |
| Add-Wins-Set (AW) | add wins | 없음 | 누적 |
| Remove-Wins-Set (RW) | remove wins | 없음 | 누적 |

## 6. Vector Clock vs Version Vector vs DVV

merge 시 어느 update 가 어느 update를 인과적으로 포함하는지 알아야 안정적인 충돌 처리가 가능하다. 세 가지 표현이 자주 등장한다.

Vector Clock: 노드별 monotonic counter. 두 vector v, w 가 있을 때 `v ≤ w iff ∀i v[i] ≤ w[i]`. 합치면 `max(v[i], w[i])`. 메시지 카운트가 N²이라 노드 수가 늘면 비용이 커진다.

Version Vector: vector clock과 자료구조는 같으나 의미가 "누가 마지막으로 update 했는지". 객체 단위 인과 추적에 흔히 쓰인다.

Dotted Version Vector (DVV): version vector에 "dot"이라는 가장 최근 update의 (id, counter) 쌍을 추가. concurrent update가 있을 때 어떤 dot 쌍이 active 인지 정확히 표현한다. Riak이 "sibling explosion"을 줄이려 도입했다.

Java 의사 구현:

```java
public final class DVV {
    Map<String, Long> base;     // 인과적으로 포함된 버전들
    Set<Dot> dots;              // active replica의 최신 dot 집합
    record Dot(String node, long counter) {}
    public DVV update(String node) {
        long n = base.getOrDefault(node, 0L) + dots.stream()
                .filter(d -> d.node.equals(node)).mapToLong(d -> d.counter).max().orElse(0L) + 1;
        return new DVV(base, Set.of(new Dot(node, n)));
    }
}
```

## 7. Operation-based CRDT (CmRDT)

State-based 가 전체 상태를 주고받는 반면, Operation-based 는 update operation 자체를 broadcast 하고 모든 replica가 동일 집합을 적용해야 수렴한다. 전제는 (1) reliable causal broadcast 가 가능, (2) operation 들이 commutative. 장점은 메시지 사이즈가 작고, 단점은 메시지 인프라가 강제된다(예: gossip + DAG 기반 dependency).

협업 편집 도메인 라이브러리는 보통 op-based 변형이다. Yjs는 Y-Tree(custom RGA-like 구조)를 사용한다. 각 character가 unique ID와 (left, right) 이웃을 가지는 doubly linked list로 표현되며, insert/delete가 commutative 한 op 형태로 표현된다. Automerge는 RGA(Replicated Growable Array)를 베이스로 한다.

## 8. RGA의 핵심 아이디어

RGA는 sequence(텍스트, 리스트)를 표현하는 op-CRDT. 각 element는 globally unique ID를 가지고 "어느 element 다음에 삽입되는가"를 ID로 참조한다. 동일 위치에 동시 삽입이 있을 때 ID 비교로 결정론적 순서를 결정한다.

```
insert(id=A, after=null, value="H")
insert(id=B, after=A, value="i")
// concurrent
insert(id=C, after=A, value="o")  // by replica 2
// merge: A 다음에 B와 C가 모두 들어간다. ID 비교로 정렬.
```

이 단순 규칙으로 모든 replica가 동일한 sequence로 수렴한다. delete는 element를 tombstone로 표시한다.

## 9. 적용 사례 시나리오

| 도메인 | CRDT | 비고 |
| --- | --- | --- |
| 협업 문서 (Notion, Figma, Linear) | RGA / Y-Tree | 인덱스 단위 op |
| 분산 캐시 카운터 | PN-Counter | invariant: ≥0이면 Bounded Counter |
| Shopping Cart | OR-Set | tombstone GC 주기 필요 |
| presence (online users) | LWW-Element-Set | HLC 권장 |
| 분산 DB (Riak, Redis CRDT) | OR-Set, PN-Counter | DVV 기반 |

## 10. 한계와 트레이드오프

CRDT는 "응답성과 가용성"을 산다. 그 비용은 첫째, tombstone과 메타데이터 오버헤드. OR-Set은 element 수에 비례해 tombstone이 누적된다. 둘째, "강한 invariant"를 표현하기 어렵다. 예를 들어 잔액이 음수가 되면 안 된다는 invariant는 PN-Counter로 자연스럽게 표현되지 않는다(escrow / bounded counter 같은 추가 패턴 필요). 셋째, 사용자 의도를 잃을 수 있다. add-wins 는 직관적이지만 "삭제했어야 마땅한 데이터가 살아 돌아오는" 경험을 만든다. 따라서 CRDT는 도메인 invariant 와 UX 둘 다 고려해 선택해야 한다.

## 참고

- Marc Shapiro et al., "Conflict-free Replicated Data Types" (INRIA RR-7687, 2011)
- Carlos Baquero, "Why Logical Clocks are Easy" (CACM, 2016)
- Yjs Documentation — Y-Tree internals (docs.yjs.dev)
- Martin Kleppmann, "Local-First Software" essay (inkandswitch.com)
- Riak Engineering — Dotted Version Vectors blog series
