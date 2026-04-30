Notion 원본: https://app.notion.com/p/3525a06fd6d381fbae1af0b904323986

# Vector Clock과 Hybrid Logical Clock — 분산 시간 동기화

> 2026-04-30 신규 주제 · 확장 대상: 분산 시스템 · CS

## 학습 목표

- Lamport timestamp 가 happens-before 관계를 보존하는 원리와 그 한계
- Vector Clock 의 노드별 카운터가 동시성(concurrent) 을 정확히 식별하는 방법
- Hybrid Logical Clock(HLC) 가 wall clock 과 logical clock 을 결합해 인과성과 사람-가독성을 동시에 얻는 방식
- CockroachDB · MongoDB 등이 HLC 를 어떻게 트랜잭션 정렬에 쓰는지

## 1. 시간 동기화가 어려운 이유

분산 시스템에서 "어떤 이벤트가 먼저 일어났는가?" 라는 질문은 자명하지 않다. 노드 A 와 B 의 NTP 시계는 수십 ms 차이가 나며, leap second 도 있고, 가상 머신은 시계가 잠시 뒤로 갈 수도 있다. wall clock 만 믿으면 다음 두 사건이 같은 100ms 안에 분간되지 않는다.

- 사용자가 `account.balance` 를 100원에서 200원으로 바꿈.
- 다른 트랜잭션이 그 잔고를 100원으로 본 후 인출 시도.

해결책은 시계를 **인과성(causality)을 보존하는 논리 시계** 로 두는 것이다.

## 2. Lamport Timestamp — 가장 단순한 논리 시계

규칙 두 개로 끝난다.

1. 노드는 자기 카운터 `L` 을 갖는다. 로컬 이벤트마다 `L = L + 1`.
2. 메시지를 받을 때 `L = max(L_local, L_msg) + 1`.

이 규칙은 **causality 보존** 을 보장한다 — 만약 이벤트 a 가 b 보다 먼저 일어났다면 `L(a) < L(b)`.

그러나 **역은 성립하지 않는다**. `L(a) < L(b)` 라고 해서 a 가 b 의 원인이라는 보장은 없다. 두 이벤트가 무관할 수도 있다. 즉 Lamport 만으로는 "동시성(concurrent)" 을 식별할 수 없다.

```python
class LamportClock:
    def __init__(self):
        self.t = 0

    def tick(self):
        self.t += 1
        return self.t

    def receive(self, msg_t: int):
        self.t = max(self.t, msg_t) + 1
        return self.t
```

## 3. Vector Clock — 동시성을 식별하다

Vector Clock 은 노드 N 개에 대해 길이 N 의 벡터를 가진다. 노드 i 의 카운터를 `V[i]` 라 한다.

규칙:

1. 로컬 이벤트: `V[self] += 1`.
2. 메시지 송신: 자기 벡터 `V` 를 첨부.
3. 메시지 수신: 각 j 에 대해 `V[j] = max(V[j], V_msg[j])`, 그 후 `V[self] += 1`.

비교 규칙:

- `V_a < V_b` (a → b 인과 선후): 모든 j 에서 `V_a[j] <= V_b[j]` 이고 어느 하나에서 `<`.
- `V_a == V_b`: 모든 j 에서 같음(같은 이벤트).
- 그 외: **concurrent**. 어느 쪽도 다른 쪽의 원인이 아님.

```python
class VectorClock:
    def __init__(self, node_id: int, n: int):
        self.id = node_id
        self.v = [0] * n

    def tick(self):
        self.v[self.id] += 1
        return list(self.v)

    def receive(self, other: list[int]):
        for j in range(len(self.v)):
            self.v[j] = max(self.v[j], other[j])
        self.v[self.id] += 1
        return list(self.v)

    @staticmethod
    def compare(a: list[int], b: list[int]) -> str:
        less = any(x < y for x, y in zip(a, b))
        more = any(x > y for x, y in zip(a, b))
        if less and not more:
            return "before"
        if more and not less:
            return "after"
        if not less and not more:
            return "equal"
        return "concurrent"
```

장점은 정밀함. 단점은 크기. N 노드면 매 메시지에 N 개 정수가 따라붙는다. Cassandra 같은 시스템은 수십 노드로 확장되면 vector clock 메타데이터가 부담이 된다.

## 4. Lamport vs Vector — 사용처 차이

| 시스템 | 시계 | 이유 |
|---|---|---|
| Apache Cassandra (구버전) | Vector Clock | concurrent write 충돌을 식별해 LWW (Last Write Wins) 보다 똑똑하게 처리 |
| Riak | Vector Clock + Dotted Version Vector | sibling resolution |
| Apache Kafka | offset (Lamport-like) | 단일 partition 내 전체 순서만 필요 |
| Bitcoin | wall clock + height | "가장 긴 체인" 으로 인과 대체 |

## 5. Hybrid Logical Clock(HLC)

HLC 는 Lamport + 물리 시계의 결합이다. 한 시계 값은 두 부분으로 구성된다.

```
HLC = (l, c)
l: physical time component (대체로 ms 단위)
c: logical counter (l 가 같을 때 tie-break)
```

규칙(노드 j 에서):

```
local event:
  pt = wall_clock_now()
  l_new = max(l_old, pt)
  if l_new == l_old:
      c_new = c_old + 1
  else:
      c_new = 0
  emit (l_new, c_new)

receive (l_m, c_m):
  pt = wall_clock_now()
  l_new = max(l_old, l_m, pt)
  if l_new == l_old == l_m:
      c_new = max(c_old, c_m) + 1
  elif l_new == l_old:
      c_new = c_old + 1
  elif l_new == l_m:
      c_new = c_m + 1
  else:
      c_new = 0
```

핵심 성질 세 가지.

1. **Causality 보존**: a → b 이면 `HLC(a) < HLC(b)` (lexicographic).
2. **Wall clock 근접**: `l` 컴포넌트가 실제 시간과 거의 일치(노드 간 clock skew 한계 내).
3. **상수 크기**: 8+8 byte 면 충분. Vector Clock 처럼 노드 수에 비례하지 않는다.

```python
import time

class HLC:
    def __init__(self):
        self.l = 0
        self.c = 0

    @staticmethod
    def _now_ms() -> int:
        return int(time.time() * 1000)

    def tick(self) -> tuple[int, int]:
        pt = self._now_ms()
        if pt > self.l:
            self.l, self.c = pt, 0
        else:
            self.c += 1
        return self.l, self.c

    def receive(self, lm: int, cm: int) -> tuple[int, int]:
        pt = self._now_ms()
        l_new = max(self.l, lm, pt)
        if l_new == self.l == lm:
            c_new = max(self.c, cm) + 1
        elif l_new == self.l:
            c_new = self.c + 1
        elif l_new == lm:
            c_new = cm + 1
        else:
            c_new = 0
        self.l, self.c = l_new, c_new
        return self.l, self.c
```

## 6. CockroachDB 에서의 HLC 사용

CockroachDB 는 HLC 를 트랜잭션의 commit timestamp 로 쓴다. 각 노드가 자기 HLC 를 가지고 클러스터 메시지마다 HLC 를 교환한다.

흐름:
1. 트랜잭션 T1 시작 — 코디네이터가 자기 HLC 로 timestamp `ts1` 부여.
2. T1 이 데이터를 읽고 쓸 때, 만나는 노드의 HLC 를 추적.
3. 어느 노드의 HLC 가 `ts1` 보다 미래라면 `ts1` 을 끌어올린다(timestamp push).
4. Commit 시점에 결정된 HLC 가 이 트랜잭션의 영구 시간.

**Uncertainty Interval**: 노드 A 가 시간 `t` 에 쓴 값을 노드 B 가 `t-skew` 시점에 읽으면 stale read 가능. CockroachDB 는 `max_offset` (기본 500ms) 를 두고, 이 범위 내의 값은 "확실하지 않음" 으로 보고 트랜잭션을 retry. NTP 신뢰가 깨지면 클러스터 가용성이 무너진다.

## 7. Spanner 와의 비교 — TrueTime

Google Spanner 는 GPS+원자시계 하드웨어로 만든 **TrueTime API** 를 사용한다. `TT.now()` 가 `[earliest, latest]` 구간을 반환하며 실제 시간은 그 안에 있다고 보장. 트랜잭션 commit 시 `latest` 까지 대기(commit wait, 보통 5ms 이하)해서 외부 일관성을 얻는다.

CockroachDB / YugabyteDB 는 TrueTime 같은 하드웨어 없이도 비슷한 보장을 위해 HLC + max_offset 방식을 채택. 정확성은 NTP 품질에 의존한다.

## 8. MongoDB 의 cluster time

MongoDB 4.0+ 는 cluster time 으로 HLC 와 유사한 구조를 쓴다. 모든 oplog 엔트리에 `(ts, t)` 가 박힌다. `ts` 는 BSON timestamp, `t` 는 항(term, replica election epoch). Causal Consistency 옵션을 켜면 클라이언트가 본 가장 큰 cluster time 을 다음 query 에 첨부 → 서버는 그 시간 이상의 oplog 가 적용된 후에 응답.

## 9. 적용 가이드

| 요구사항 | 권장 |
|---|---|
| 동시성 식별이 핵심(분산 KV) | Vector Clock 또는 Dotted Version Vector |
| Causality 보존 + 작은 메타데이터 | HLC |
| 단일 partition 안 전체 순서만 필요 | Lamport / sequence number |
| 외부 일관성 + 짧은 commit wait 가능 | Spanner-style (전용 하드웨어 필요) |
| 단순 timeline 표시(로그·디버깅) | wall clock(NTP) — causality 는 별도로 |

운영 측면에서 HLC 가 가장 실용적이다. wall clock 친화적이라 사람 디버깅이 쉽고, vector clock 의 과한 메타데이터 비용도 없다.

## 참고

- Leslie Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System" (1978)
- Sandeep Kulkarni et al., "Logical Physical Clocks" (2014) — HLC 원전
- CockroachDB Engineering Blog, "Living without Atomic Clocks"
- Spanner 논문, "Globally-Distributed Database" (Corbett et al., OSDI 2012)
- MongoDB 공식 문서, "Causal Consistency and Read and Write Concerns"
