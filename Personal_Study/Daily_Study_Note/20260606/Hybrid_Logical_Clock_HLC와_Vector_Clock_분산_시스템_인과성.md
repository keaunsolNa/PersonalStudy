Notion 원본: https://www.notion.so/3775a06fd6d38133ba6be3728f6ab935

# Hybrid Logical Clock(HLC)와 Vector Clock — 분산 시스템 인과성 추적

> 2026-06-06 신규 주제 · 확장 대상: 면접을_위한_CS_전공지식_노트

## 학습 목표

- 물리 시계의 한계와 Lamport/Vector clock이 인과성을 표현하는 방식을 구분한다
- Hybrid Logical Clock이 물리 시간 근접성과 인과성 보존을 동시에 달성하는 원리를 설명한다
- HLC의 갱신 규칙을 코드로 구현하고 clock drift 경계를 분석한다
- 실제 분산 DB(CockroachDB 등)에서 HLC가 트랜잭션 순서화에 쓰이는 방식을 정리한다

## 1. 왜 물리 시계만으로는 안 되나

각 노드의 물리 시계는 서로 어긋난다. NTP로 동기화해도 수 ms~수십 ms 오차(clock skew)가 남고 시계가 거꾸로 뛸 수 있다. 물리 timestamp만으로 순서를 매기면 인과성 위반이 생긴다. Lamport의 통찰은 순서를 물리 시간이 아니라 인과성(happens-before, →)으로 정의하는 것이었다. A→B는 같은 프로세스에서 A가 먼저거나, A가 send이고 B가 그 receive거나, 추이적으로 A→C→B일 때 성립한다.

## 2. Lamport Clock

```
- 로컬 이벤트:        L = L + 1
- 메시지 send:        L = L + 1; 메시지에 L 첨부
- 메시지 receive(m):  L = max(L, m.L) + 1
```

A→B이면 L(A) < L(B)를 보장한다(단조성). 하지만 역은 성립하지 않아 두 timestamp만 보고 인과인지 concurrent인지 구별하지 못한다.

## 3. Vector Clock

N개 노드 각각의 카운터를 담은 벡터 V[1..N]를 쓴다. 로컬 이벤트 V[i]+=1, send는 벡터 전체 첨부, receive는 모든 k에 V[k]=max(V[k], m.V[k]) 후 V[i]+=1.

| 시계 | 크기 | A→B 판정 | concurrent 판정 | 물리 시간 근접 |
|---|---|---|---|---|
| Lamport | O(1) | 약함(단방향) | 불가 | 없음 |
| Vector | O(N) | 정확 | 정확 | 없음 |
| HLC | O(1) | 정확(근사) | 제한적 | 있음 |

Vector clock은 인과성을 정확히 판정하지만 크기가 노드 수 N에 비례해 무겁다.

## 4. Hybrid Logical Clock

HLC timestamp는 (l, c) 쌍이다. l은 관측된 물리 시간의 상한, c는 같은 l 안에서 인과 순서를 매기는 작은 정수다. 비교는 사전식이다.

```python
class HLC:
    def __init__(self): self.l = 0; self.c = 0
    def send_or_local(self):
        l_old = self.l; self.l = max(l_old, now_pt())
        if self.l == l_old: self.c += 1
        else: self.c = 0
        return (self.l, self.c)
    def receive(self, l_m, c_m):
        l_old = self.l; self.l = max(l_old, l_m, now_pt())
        if self.l == l_old and self.l == l_m: self.c = max(self.c, c_m) + 1
        elif self.l == l_old: self.c = self.c + 1
        elif self.l == l_m: self.c = c_m + 1
        else: self.c = 0
        return (self.l, self.c)
```

HLC의 l은 항상 max(관측된 모든 물리시간, 받은 모든 l) 이상이므로 물리 시간을 뒤로 가지 않게 추적하면서 같은 l 구간에서 인과 순서를 c로 보존한다. |l - 실제 물리시간|은 clock skew 상한 ε로 유계다.

## 5. 왜 인과성이 보존되는가

같은 노드에서 A 다음 B면 l은 비감소하고 같은 l이면 c가 +1 되므로 HLC(A) < HLC(B). A가 send, B가 receive면 receive 규칙이 보낸 쪽 l_m 이상을 잡고 같으면 c = max(...) + 1로 더 크게 만든다. 다만 HLC는 Vector clock과 달리 concurrent 이벤트를 항상 구별하지는 못한다. HLC는 인과성을 깨지 않는 전순서를 O(1)에 주는 것이지 concurrent 판정기는 아니다.

## 6. 실전 — CockroachDB의 트랜잭션 순서화

CockroachDB는 HLC를 모든 노드에서 사용해 트랜잭션과 MVCC 버전에 timestamp를 부여한다. 핵심 파라미터는 max clock offset(기본 500ms)이다. 두 노드의 물리 시계 차이가 이 한계를 넘지 않는다는 가정 위에서 일관성을 보장하며, 넘는 노드는 스스로 클러스터에서 빠진다. Spanner의 TrueTime이 GPS/atomic clock으로 ε를 좁히는 것과 달리 HLC는 NTP만으로 동작하되 skew 상한을 명시적으로 가정한다. uncertainty interval에 걸친 읽기는 트랜잭션 재시작으로 해소하며, clock skew를 작게 유지할수록 재시도가 줄어든다.

## 7. 선택 가이드

단일 토픽 순서만 필요하면 Lamport, 정확한 concurrent 판정이 필요하면 Vector/version vector, 물리 시간 근접 + 인과성 + 경량(분산 DB의 MVCC)이면 HLC가 최적이다. CockroachDB, YugabyteDB 등이 채택했다.

## 8. 수치 예제 — 세 노드의 HLC 추적

표기는 (l, c), pt는 ms.

1. A에서 pt=100 로컬 e1: l=max(0,100)=100, c=0 → (100,0)
2. A에서 pt=100 e2: l=100, 안 늘었으니 c=1 → (100,1)
3. A가 e2를 B로 send. B의 l=0, pt=98(B가 느림). receive: l=max(0,100,98)=100. 받은 쪽 규칙 c=c_m+1=2 → B는 (100,2)

B의 물리시계는 98이었지만 A로부터 받은 인과적으로 앞선 timestamp(100)를 흡수해 B의 HLC가 100으로 전진했다. 인과적으로 앞선 이벤트가 더 큰 HLC를 갖는다는 성질이 물리시계 역전에도 지켜진다.

4. B에서 pt=105 로컬: l=105, 늘었으니 c=0 → (105,0). 물리시간이 충분히 전진하면 c가 0으로 리셋돼 다시 물리시간에 밀착한다. c는 물리시계가 정체된 짧은 구간에서만 누적되므로 무한정 커지지 않는다.

## 9. 정리

인과성 추적의 핵심은 물리 시간이 아니라 happens-before로 순서를 정의한다는 Lamport의 통찰이다. Lamport clock은 가볍지만 concurrent를 구별 못 하고, Vector clock은 정확하지만 O(N)으로 무겁다. HLC는 물리 시간과 논리 카운터를 한 쌍으로 묶어 NTP 시간에 ε-유계로 가까우면서 인과성을 보존하는 O(1) timestamp를 제공한다. 그 대가는 clock skew 상한 가정과 uncertainty window이며, 이를 NTP 정밀도로 관리하는 것이 운영의 본질이다.

## 참고

- Kulkarni et al., "Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases" (2014)
- Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System" (CACM 1978)
- Fidge/Mattern, Vector Clock 원논문
- CockroachDB Docs — "Life of a Distributed Transaction", "Clock synchronization"
- Google, "Spanner: Google's Globally-Distributed Database" (TrueTime 비교)
