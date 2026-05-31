Notion 원본: https://www.notion.so/3715a06fd6d38102aa07c09693deaec8

# CRDT 내부구조 — State-based vs Operation-based, Yjs와 Automerge의 설계 차이

> 2026-05-31 신규 주제 · 확장 대상: CS 면접 전공지식 / 분산시스템 정합성

## 학습 목표

- CRDT 의 두 분류(State-based CvRDT, Operation-based CmRDT) 의 수학적 보장 조건(commutative, associative, idempotent / causal delivery)을 식별한다
- Yjs 의 Y.Doc 내부 구조(Item linked list + struct store + delete set)와 Automerge 의 OpSet 모델을 비교한다
- text editing 에서 RGA vs WOOT vs YATA 알고리즘이 어떻게 동시 삽입을 정렬하는지 추적한다
- 메모리/저장 비용, GC, encoded update 크기 측면에서 두 라이브러리의 운영 trade-off 를 정리한다

## 1. CRDT 분류

| 비교 | CvRDT | CmRDT |
|---|---|---|
| 전송 단위 | 상태(또는 delta) | operation 메시지 |
| 네트워크 보장 | at-least-once | causal broadcast |
| 메시지 크기 | 큼 | 작음 |
| 구현 난이도 | merge 함수 설계 | 모든 op pair commutative |

CvRDT: merge 가 (1) commutative `merge(a,b)=merge(b,a)`, (2) associative, (3) idempotent `merge(a,a)=a` 면 SEC 보장.

CmRDT: 모든 operation pair commutative + causal delivery 면 SEC 보장.

## 2. 시퀀스 CRDT — 동시 삽입 정렬

- **WOOT** (2006) — 글자 ID + 양쪽 이웃, tombstone 영구 보관
- **RGA** (2011) — timestamp + origin, reverse order 정렬
- **YATA** (Yjs 채택) — RGA 와 유사하지만 left/right 양쪽 origin, 사용자 의도에 더 가까움

```
초기: "AB"
user 1: A 뒤에 X 삽입 → "AXB"
user 2: A 뒤에 Y 삽입 → "AYB"
세 알고리즘 모두 X, Y 보존 — 순서 결정 규칙이 다름
```

## 3. Yjs 내부

- **Item**: id=(clientID, clock), originLeft/Right, parent, content, deleted
- **Struct Store**: clientID 별 array
- **Delete Set**: 압축 range 표현
- **State Vector**: clientID → 마지막 clock

```ts
import * as Y from 'yjs'

const ydoc = new Y.Doc()
const stateVector = Y.encodeStateVector(ydoc)
// peer 응답
const update = Y.encodeStateAsUpdate(peerDoc, stateVector)
Y.applyUpdate(ydoc, update)
```

`encodeStateAsUpdate(doc, sv)` 가 compact 바이너리. idempotent — 같은 update 가 두 번 적용돼도 결과 동일.

## 4. Automerge — OpSet + Materialize

```rust
struct Op {
    id: OpId,        // (counter, actor_id)
    action: Action,  // Set / Insert / Delete / MakeMap / MakeList
    obj: ObjId,
    key: Key,
    value: Value,
    pred: Vec<OpId>, // 대체하는 이전 Op 들
}
```

`pred` 가 인과 관계 명시. 같은 key 동시 write 면 두 value 모두 유지 → "conflict" 노출.

```ts
import * as Automerge from '@automerge/automerge'

let doc1 = Automerge.from({ title: 'Hello', tasks: [] })
let doc2 = Automerge.clone(doc1)
doc1 = Automerge.change(doc1, d => { d.tasks.push('A') })
doc2 = Automerge.change(doc2, d => { d.tasks.push('B') })
const merged = Automerge.merge(doc1, doc2)
```

## 5. 운영 trade-off

| 항목 | Yjs | Automerge |
|---|---|---|
| 언어 | TS | Rust+WASM |
| 데이터 모델 | Y.Text, Y.Array, Y.Map | JSON 트리 |
| 메모리 | tombstone, GC 옵션 | OpSet 영구 |
| update 크기 | ~100 bytes | ~250 bytes |
| 텍스트 알고리즘 | YATA | RGA-like |
| history | optional (UndoManager) | 항상 보존 |
| conflict 노출 | 자동 병합 | map 동시 write → conflict 객체 |

벤치 (1만 글자, 100명 동시): Yjs 500KB / Automerge 1.2MB. Automerge 는 history 비용.

## 6. GC와 tombstone

Yjs GC 조건 — 모든 peer ack + origin 미참조. 기본 off (`doc.gc = true` 로 켜기). Automerge 는 history 가 제품 기능이므로 더 어려움. `Automerge.save()` 가 OpSet compact 직렬화.

## 7. sync 프로토콜

```
1. A → B : SyncStep1 (state vector)
2. B → A : SyncStep2 (A 누락 update + B 의 sv)
3. A → B : SyncStep2
4. 이후 변경마다 Update broadcast
```

```ts
import { WebsocketProvider } from 'y-websocket'
const provider = new WebsocketProvider('wss://sync.example.com', 'room-id', ydoc)
```

## 8. 운영 문제

- **clientID 폭주** — 사용자 × 디바이스 수 누적
- **악의적 replica** — CRDT 는 인증/권한 없음 — 서버 게이트(예: y-redis) 필요
- **server-side persistence** — snapshot + diff 모델 (주기적 컴팩션)
- **무한 grow** — tombstone + history 가 영원히 커짐

## 9. 핵심 정리

- CvRDT: merge 의 (commute + associate + idempotent) 가 SEC 보장
- CmRDT: 모든 op pair commutative + causal delivery 가 SEC 보장
- 시퀀스 CRDT 는 동시 삽입 정렬 알고리즘이 본질
- Yjs 는 데이터 효율과 텍스트 협업 최적화, Automerge 는 JSON 모델과 history 보존 최적화
- tombstone 비용과 GC 안전성 사이의 trade-off 가 운영 핵심

## 참고

- Marc Shapiro et al., "Conflict-free Replicated Data Types" INRIA 2011 https://hal.inria.fr/inria-00609399
- Yjs Documentation https://docs.yjs.dev/
- Automerge Documentation https://automerge.org/
- Martin Kleppmann, "A Conflict-Free Replicated JSON Datatype" POPL 2017
- Kevin Jahns(Yjs creator) "YATA: Yet Another Transformation Approach"
