Notion 원본: https://app.notion.com/p/38d5a06fd6d38144bf10f56a28486f9f

# CRDT 충돌 없는 복제 데이터 타입과 GCounter PNCounter 및 OR Set

> 2026-06-28 신규 주제 · 확장 대상: 분산 시스템 / CS

## 학습 목표

- 상태 기반(CvRDT)과 연산 기반(CmRDT) CRDT의 수렴 조건을 수학적 성질로 구분한다
- GCounter·PNCounter의 머지 규칙이 교환·결합·멱등 법칙을 만족함을 검증한다
- OR-Set이 add/remove 동시성을 태그로 해결하는 원리를 구현 수준으로 이해한다
- CRDT의 메타데이터 비용과 tombstone 누적 트레이드오프를 실무 기준으로 판단한다

## 합의 없는 수렴

CRDT는 자료구조 자체를 수학적으로 설계해 복제본들이 임의 순서로 업데이트를 받아도 같은 상태로 수렴하게 한다. 머지 연산이 반격자 구조를 이루면(교환·결합·멱등) 메시지가 재정렬·중복되어도 결과가 같다. 이것이 강한 수렴(SEC)의 핵심이다.

## 두 모델

CvRDT(상태 전파, 약한 네트워크 가정, 멱등 필수)와 CmRDT(연산 전파, exactly-once causal 필요, 경제적). 실무는 절충인 delta-state CRDT를 많이 쓴다.

## GCounter

```python
class GCounter:
    def increment(self, a=1): self.counts[self.node_id] += a
    def value(self): return sum(self.counts.values())
    def merge(self, other):
        for n in self.counts:
            self.counts[n] = max(self.counts[n], other.counts[n])
```

각 노드는 자기 칸만 증가해 단조 증가하므로 칸별 max로 병합하면 손실이 없다. max는 교환·결합·멱등을 모두 만족.

## PNCounter

감소를 N 카운터의 증가로 변환하는 트릭. P와 N 각각은 단조 증가 GCounter이고 최종값은 P-N. 단점은 증가했다 감소하면 P, N 양쪽이 영구히 커져 메타데이터가 줄지 않는다.

## OR-Set

각 add에 고유 태그를 붙이고, remove는 그 시점에 관측된 태그만 지운다. 동시 add의 태그는 관측 못해 살아남는다(add-wins).

```python
class ORSet:
    def add(self, x): self.adds.setdefault(x, set()).add(uuid.uuid4())
    def remove(self, x):
        obs = self.adds.get(x, set()) - self.removes.get(x, set())
        self.removes.setdefault(x, set()).update(obs)
    def contains(self, x):
        return bool(self.adds.get(x, set()) - self.removes.get(x, set()))
```

add/remove 모두 합집합으로 머지하므로 교환·결합·멱등을 만족해 수렴한다.

## tombstone 트레이드오프

removes는 tombstone — 지운 태그를 영구 보관해야 해 시간이 지나면 실제 데이터보다 커진다. 모든 복제본이 특정 태그를 관측했음이 확실해지면 GC로 제거하지만, 이 GC 자체가 약한 동기화를 요구한다.

## LWW-Register와 선택

단일 값을 저장하되 (ts, node_id) 튜플 비교로 결정론적 선택. 물리 시계 드리프트 함정 때문에 실무는 HLC를 쓴다.

| 자료 의미 | 적합 CRDT |
|---|---|
| 단조 증가 | GCounter |
| 증감(좋아요) | PNCounter |
| 집합(장바구니) | OR-Set(add-wins) |
| 단일 값(설정) | LWW-Register |

CRDT는 가용성을 일관성보다 우선하는 AP 시스템에서 병합 규칙이 도메인 의미와 일치할 때 강력하다.

## 참고

- Shapiro et al., CRDTs (2011): https://hal.inria.fr/inria-00609399/document
- A comprehensive study of CRDTs: https://hal.inria.fr/inria-00555588/
- Automerge / Yjs: https://automerge.org
- Redis CRDT: https://redis.io/docs/latest/operate/rs/databases/active-active/
