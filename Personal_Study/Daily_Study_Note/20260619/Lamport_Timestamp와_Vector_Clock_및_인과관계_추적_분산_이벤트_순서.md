Notion 원본: https://www.notion.so/3845a06fd6d3811cb246f6ab55f642f6

# Lamport Timestamp와 Vector Clock 및 인과관계 추적 분산 이벤트 순서

> 2026-06-19 신규 주제 · 확장 대상: 면접을 위한 CS 전공지식 노트

## 학습 목표

- 분산 시스템에서 물리 시계가 신뢰할 수 없는 이유와 논리 시계의 필요성을 설명한다
- Lamport timestamp의 happens-before 규칙과 전순서 부여 방법을 구현한다
- Vector clock으로 인과관계와 동시성(concurrency)을 정확히 판별한다
- 두 기법의 트레이드오프와 실제 시스템(Dynamo 계열) 적용을 분석한다

## 1. 왜 물리 시계로는 안 되는가

각 노드의 물리 시계는 결코 완벽히 동기화되지 않는다. 클럭 드리프트가 있고 NTP로 보정해도 네트워크 지연 때문에 오차가 남으며, 시계가 뒤로 점프할 수도 있다. 그 결과 서로 다른 노드의 타임스탬프를 비교해 사건 순서를 단정할 수 없다.

핵심 통찰: 분산 시스템에 정말 필요한 것은 절대 시각이 아니라 "무엇이 무엇보다 먼저인가"라는 인과 순서다. Lamport는 물리 시간 대신 논리 시계로 이 순서를 포착하는 방법을 제시했다.

## 2. happens-before 관계

happens-before(→)는 인과적으로 영향을 줄 수 있는 순서다. (1) 같은 프로세스 안에서 a가 b보다 먼저면 a→b. (2) a가 메시지 송신이고 b가 그 수신이면 a→b. (3) 이행성.

a→b도 b→a도 아니면 두 사건은 동시적(a ∥ b)이다. 이는 "정확히 같은 시각"이 아니라 "서로 인과적으로 무관"하다는 뜻이다. 이 동시성을 정확히 식별하는 것이 충돌 감지의 핵심이다.

## 3. Lamport Timestamp: 전순서를 위한 단일 카운터

각 프로세스가 정수 카운터 C 하나를 유지한다. (1) 이벤트 전 C=C+1. (2) 송신 시 C 첨부. (3) 수신 시 C=max(C, 수신_C)+1.

```python
class LamportClock:
    def __init__(self): self.time = 0
    def tick(self): self.time += 1; return self.time
    def send(self): self.time += 1; return self.time
    def receive(self, msg_time):
        self.time = max(self.time, msg_time) + 1
        return self.time
```

이 규칙은 a→b이면 C(a)<C(b)를 보장한다. 하지만 역은 성립하지 않는다 — C(a)<C(b)라고 a→b는 아니다. 동시적이어도 대소가 생긴다. 따라서 Lamport만으로는 인과인지 동시적인지 구분할 수 없다. 전순서가 필요하면 타임스탬프 동점을 프로세스 ID로 tie-break한다(분산 락).

## 4. Vector Clock: 인과관계의 완전한 포착

각 프로세스가 모든 프로세스의 카운터 벡터 V[1..N]를 유지한다. 로컬 이벤트 시 V_i[i]+=1, 송신 시 벡터 첨부, 수신 시 원소별 max 후 V_i[i]+=1.

```python
class VectorClock:
    def __init__(self, node_id, num_nodes):
        self.id = node_id; self.v = [0]*num_nodes
    def tick(self): self.v[self.id] += 1
    def send(self): self.v[self.id] += 1; return list(self.v)
    def receive(self, other):
        self.v = [max(a,b) for a,b in zip(self.v, other)]
        self.v[self.id] += 1
```

비교: V(a)<V(b)(모든 원소 ≤, 하나 <) ⇔ a→b. 둘 다 아니면 동시적 a∥b. 이것이 Lamport와 결정적으로 다른 점이다 — vector clock은 두 사건이 동시적인지 인과적인지를 모호함 없이 구분한다.

| 특성 | Lamport Timestamp | Vector Clock |
|---|---|---|
| 저장 크기 | 정수 1개 | 정수 N개 |
| a→b ⇒ 작은 값 | 보장 | 보장 |
| 동시성 판별 | 불가 | 가능 |
| 용도 | 전순서 부여 | 인과/충돌 감지 |

## 5. 실전: Dynamo 계열의 충돌 감지

Amazon Dynamo 계열(Riak, Voldemort)은 가용성을 위해 다중 노드 비동기 쓰기를 허용한다. 읽기 시 두 버전의 vector clock을 비교해 한쪽이 다른 쪽을 인과적으로 포함하면 최신을 채택하고, 동시적이면 진짜 충돌라 두 버전(sibling)을 보존한다.

```
cart: {value:[milk], vc:[1,0]}
A: {value:[milk,eggs], vc:[2,0]}
B: {value:[milk,ham], vc:[1,1]}
[2,0] vs [1,1] -> 동시적 -> 충돌! 두 sibling 보존 -> 다음 읽기에서 병합
```

LWW(물리 타임스탬프 최댓값)는 단순하지만 클랭 스큐로 인해 인과적으로 나중인 쓰기가 버려질 수 있다(silent data loss). vector clock 기반 충돌 보존은 이 손실을 막는 대신 응용에 병합 책임을 지운다.

## 6. Vector Clock의 비용과 변종

약점은 크기가 노드 수 N에 비례한다는 점이다. entry에 타임스탬프를 붙여 가지치기(pruning)하거나, 클라이언트 단위 폭증을 해결하는 Dotted Version Vector(DVV)를 Riak 등이 채택했다. Hybrid Logical Clock(HLC)는 물리 시각과 논리 카운터를 결합해 거의 물리 시각에 가까우면서 인과 순서를 보존한다(CockroachDB).

## 7. 정리: 무엇을 언제 쓰나

모든 노드가 합의하는 전역 순서 하나면 되면(분산 락) Lamport로 충분하다. 사건이 인과적인지 동시적인지 구분해야 하면(복제 충돌 감지) vector clock이 필요하다. 물리 근접성까지 필요하면 HLC를 고려한다.

## 8. 인과 일관성과 메시지 순서 보장

인과 일관성은 인과적으로 연관된 연산은 모든 노드에서 같은 순서로 보이되 동시적 연산은 순서가 달라도 된다는 모델이다. 원 글(A)과 댓글(B)은 A→B인데 복제 지연으로 B를 먼저 받으면 "원 글 없는 댓글"이 보인다. 수신 측이 의존성이 충족될 때까지 적용을 보류해야 한다.

```python
def can_deliver(local_vc, msg_vc, sender):
    if msg_vc[sender] != local_vc[sender] + 1: return False
    for k in range(len(local_vc)):
        if k != sender and msg_vc[k] > local_vc[k]: return False
    return True
```

| 모델 | 보장 | 비용 |
|---|---|---|
| 선형화(strong) | 실시간 전역 순서 | 합의 필요, 지연 큼 |
| 인과 일관성 | 인과 순서만 보존 | vector clock, 합의 불요 |
| 최종 일관성 | 언젠가 수렴 | 가장 저렴, 이상현상 허용 |

논리 시계는 이 스펙트럼의 중간(인과 일관성)을 합의 없이 구현하게 해 주는 핵심 도구다. 분산 시스템이 필요로 하는 것은 절대 시각이 아니라 인과 의존성의 보존이다.

## 참고

- Leslie Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System" (1978)
- Colin Fidge / Friedemann Mattern, Vector Clocks 원논문 (1988)
- DeCandia et al., "Dynamo: Amazon's Highly Available Key-value Store" (2007)
- Kulkarni et al., "Logical Physical Clocks (HLC)" (2014)
- Martin Kleppmann, "Designing Data-Intensive Applications" — Ch.5, Ch.9
