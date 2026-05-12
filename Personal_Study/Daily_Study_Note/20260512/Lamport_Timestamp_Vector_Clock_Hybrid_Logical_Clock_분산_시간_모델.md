Notion 원본: https://www.notion.so/35e5a06fd6d381d49e64ecb23b48bf09

# Lamport Timestamp · Vector Clock · Hybrid Logical Clock 분산 시간 모델

> 2026-05-12 신규 주제 · 확장 대상: 면접을_위한_CS_전공지식_노트 / 자료구조&알고리즘

## 학습 목표

- Lamport, Vector Clock, HLC 가 각각 보장하는 ordering 의 강도를 정확히 구분
- causal consistency 와 total order 의 차이를 예제로 설명
- HLC 가 NTP drift 와 결합해 동작하는 방식과 실 구현 코드 검토
- Spanner TrueTime 과 HLC 의 trade-off 비교

## 1. 시간을 바라보는 두 가지 시각

분산 시스템에서 "이 이벤트가 저 이벤트보다 먼저 일어났다" 는 명제는 자명하지 않다. 두 가지 시각이 있다.

- **물리 시간(physical time)**: 벽시계. NTP 로 노드 간 ±수 ms ~ 수십 ms 의 drift.
- **논리 시간(logical time)**: 이벤트의 인과 관계만 보는 시간. 물리 시간과 무관.

Leslie Lamport 가 1978 년 정의한 *happens-before*(`→`) 관계는 다음 세 가지로만 결정된다.

1. 같은 process 내에서 A 가 B 보다 먼저 실행되면 A → B
2. process p 가 보낸 메시지 m 의 send 이벤트는 다른 process 의 receive 이벤트보다 먼저 → A → B
3. 추이성(transitivity)

이 관계로 *비교 불가능한* 두 이벤트가 있다. concurrent 라 부른다(`A || B`).

## 2. Lamport Timestamp

각 process 는 정수 카운터 `L` 을 유지한다.

```
이벤트 발생 시:        L := L + 1
메시지 송신 시:        L := L + 1, 메시지에 L 첨부
메시지 수신 시:        L := max(L, msg.L) + 1
```

이렇게 부여된 timestamp 는 `A → B  ⇒  L(A) < L(B)` 를 만족한다. *역은 성립하지 않는다*. `L(A) < L(B)` 라도 두 이벤트는 concurrent 일 수 있다.

활용:

- **Total order broadcast**: timestamp 가 같으면 process ID 로 tiebreak. 모든 노드가 같은 순서를 본다.
- **Optimistic locking**: 자원 요청에 timestamp 를 붙여 작은 쪽이 우선.

한계: causal ordering 을 검증할 수 없다. 즉 "B 가 A 의 결과를 본 후에 일어났다" 는 보장을 못 한다.

```java
class LamportClock {
    private long counter = 0L;

    synchronized long tick() {
        counter++;
        return counter;
    }

    synchronized long receive(long remote) {
        counter = Math.max(counter, remote) + 1L;
        return counter;
    }
}
```

## 3. Vector Clock

Vector Clock 은 N 개 process 가 있는 시스템에서 각 process 가 길이 N 의 벡터 `V` 를 유지한다.

```
process i 의 이벤트:    V[i] := V[i] + 1
메시지 송신(i → j):    V[i] := V[i] + 1, 메시지에 V 첨부
메시지 수신(j 가 수신): V[k] := max(V[k], msg.V[k]) for all k
                       V[j] := V[j] + 1
```

비교:

- `V_A ≤ V_B`  ⇔  `V_A[i] ≤ V_B[i] for all i`
- `V_A < V_B`  ⇔  `V_A ≤ V_B` 이고 어딘가에서 strict <
- `V_A < V_B`  ⇔  `A → B` (이번엔 양방향)
- 비교 불가능 → concurrent

장점: causal relationship 을 정확히 판정. concurrent 가 명시적으로 보인다.

단점: 벡터 크기가 process 수 N 에 비례. 노드가 동적으로 추가/제거되는 시스템(대규모 cluster, peer-to-peer)에서 관리가 까다롭다. Dynamo, Riak 가 대표 사용 사례.

## 4. Dotted Version Vector — VC 의 변형

Riak 의 update 경로는 client-driven 이라 conflict 처리가 핵심이다. *Dotted Version Vector* 는 각 update 에 process 의 *고유 sequence number*("dot")를 추가로 부여해 sibling 이 발생해도 정확히 추적할 수 있게 한다. 일반 VC 에 비해 conflict 검출 후 병합 가능성을 더 정밀하게 표현한다.

## 5. Hybrid Logical Clock(HLC)

Sandeep Kulkarni 외 2014 년 논문에서 제안. 핵심 아이디어: **물리 시간을 단조 증가하도록 보정한 logical clock**.

각 노드는 두 값을 유지한다.

```
pt    : 마지막으로 본 physical time(NTP 기반)
l     : logical part(이번 physical 단위 안에서의 카운터)
```

타임스탬프는 `(l, c)` 형식, 보통 64-bit 안에 `l` 48bit + `c` 16bit 로 패킹.

알고리즘:

```
send(now):
  pt := PhysicalTime.now()
  if pt > l.last:
    l.last = pt
    l.count = 0
  else:
    l.count += 1
  return (l.last, l.count)

receive(msg, now):
  pt := PhysicalTime.now()
  newL = max(l.last, msg.last, pt)
  if newL == l.last == msg.last:
    l.count = max(l.count, msg.count) + 1
  elif newL == l.last:
    l.count += 1
  elif newL == msg.last:
    l.count = msg.count + 1
  else:
    l.count = 0
  l.last = newL
```

HLC 의 보장:

1. **단조 증가** — physical clock 이 잠시 뒤로 점프해도 timestamp 는 절대 줄지 않는다.
2. **causality 보존** — `A → B` ⇒ `HLC(A) < HLC(B)` (Lamport 와 동일)
3. **drift 한계** — physical clock 과 HLC 의 차이는 message delay + clock drift bound 안에 머무른다.
4. **64-bit 안에서 표현 가능** — 운영 시스템과 wire format 친화적.

CockroachDB, YugabyteDB, MongoDB($timestamp 6.x+) 가 HLC 또는 그 변형을 사용한다.

## 6. TrueTime 과의 비교

Google Spanner 는 GPS + 원자시계로 *시간 불확실성 구간* `TT.now() = [earliest, latest]` 를 노출한다. 트랜잭션 커밋 후 `latest` 가 지나면 모든 노드가 그 트랜잭션 결과를 본다고 안전하게 가정할 수 있다. 이를 *commit wait* 이라 부른다.

| 모델 | 하드웨어 의존 | drift 보장 | 구현 난이도 |
|---|---|---|---|
| Lamport | 없음 | 없음 | 매우 낮음 |
| Vector Clock | 없음 | 없음 | 낮음 |
| HLC | NTP | NTP drift bound | 중간 |
| TrueTime | GPS/원자시계 | <7ms (Google 환경) | 매우 높음 |

HLC 는 일반 datacenter NTP 환경에서 가능한 최상의 절충안이다.

## 7. 어떤 모델을 언제 쓰는가

| 사용 시나리오 | 권장 모델 |
|---|---|
| 단일 broadcast 순서만 필요 | Lamport |
| 정확한 causal sibling 추적이 필요(Dynamo, Riak) | Vector Clock / DVV |
| Global 순서 + 단조 증가 timestamp 가 필요(분산 DB) | HLC |
| 외부 일관성(External Consistency) 보장 | TrueTime / commit wait |
| 단일 datacenter, 모든 노드가 같은 leader 의 sequence 사용 | Sequential number (Raft index) |

## 8. 실수하기 쉬운 점

1. **`System.currentTimeMillis()` 만으로 HLC 퓜내** — clock skew 가 1초 점프하면 timestamp 가 1초 점프한다. logical 부분이 없으니 같은 ms 안의 이벤트 순서가 무너진다.
2. **NTP 가 시계를 뒤로 돌릴 때** — Linux 는 일반적으로 `step` 모드를 피하고 `slew` 로 천천히 조정한다(`-x` 옵션). 그래도 가상화 환경의 `kvm-clock` drift 는 ms 단위로 점프한다. HLC 의 `max(...)` 가 이를 흡수.
3. **Vector Clock 의 N 폭주** — 노드 추가 시 vector 가 자라기만 한다. 일정 주기로 *epoch 기반 garbage collection* 또는 *bounded vector clock* 같은 변형 필요.
4. **client 가 timestamp 를 보냄** — 클라이언트가 logical clock 의 일부가 되면 악의적 client 가 미래 timestamp 를 주입할 수 있다. server-side 에서 max bound 를 두야 한다(CockroachDB 는 `max_offset` 으로 제한).

## 참고

- Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System" — CACM 1978
- Kulkarni 외, "Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases" — OPODIS 2014
- Corbett 외, "Spanner: Google's Globally Distributed Database" — OSDI 2012
- Riak Docs, "Causal Context and Sibling Resolution" — https://docs.riak.com/riak/kv/latest/learn/concepts/causal-context/
- CockroachDB blog, "Living Without Atomic Clocks" — https://www.cockroachlabs.com/blog/living-without-atomic-clocks/
