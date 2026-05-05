Notion 원본: https://www.notion.so/3575a06fd6d38181a389d67f672a3215

# Raft 합의 알고리즘 — Leader Election 과 Log Replication 의 안전성 증명

> 2026-05-05 신규 주제 · 확장 대상: Cassandra LWT(Paxos), Vector Clock(분산 시간)

## 학습 목표

- Raft 의 5가지 안전성 properties (Election Safety / Leader Append-Only / Log Matching / Leader Completeness / State Machine Safety) 를 시나리오로 검증한다
- term + log index + commitIndex 의 세 변수만으로 합의가 어떻게 보장되는지 의사 코드로 추적한다
- Pre-Vote / Leader Lease / Joint Consensus 같은 운영 확장이 *기본 Raft* 에 어떤 안전성을 추가하는지 정리한다
- etcd / TiKV / RabbitMQ Quorum Queue 같은 실제 구현이 *논문 Raft* 와 어디서 갈라지는지 비교한다

## 1. Paxos 와 Raft — 같은 문제, 다른 설명

Paxos(Lamport, 1998) 는 *합의 가능성* 을 증명한 첫 알고리즘이지만 구현 가이드가 없어 시스템마다 변형이 달랐다. Raft(Ongaro & Ousterhout, 2014) 는 *이해 가능성* 을 일급 목표로 둔 합의 알고리즘이다. 두 알고리즘은 *합의를 만드는 능력* 면에서 동등하지만 Raft 는 *전체 시스템* 을 단일 모델로 묶는다.

| 측면 | Multi-Paxos | Raft |
|---|---|---|
| leader 개념 | 명시적 leader 없음 (파생 가능) | 명시적이고 단일 |
| log replication | 별도 기술 | 합의의 일부 |
| 멤버십 변경 | 재시작 / Vertical Paxos | Joint Consensus |
| 학습 곡선 | 매우 가파름 | 학생 86% 가 1주에 이해(논문 user study) |

Raft 의 핵심 단순화는 *strong leader* 다. 모든 client write 는 leader 에게 가고, leader 가 follower 에게 *AppendEntries* 로 전파한다. 한 시점에 leader 는 최대 1명이다. leader 가 죽으면 *Leader Election* 으로 새 leader 를 선출한다.

## 2. Term, Log Index, Commit Index — 세 변수의 의미

Raft 의 모든 안전성 증명은 세 변수를 추적한다.

**term** 은 *논리적 시간*. monotonically 증가. leader election 이 시작될 때마다 +1. 같은 term 에 leader 는 최대 1명. RPC 에 항상 term 이 포함되어, *낡은 term* 의 RPC 는 거부된다.

**log index** 는 log entry 의 위치. 1부터 시작. 각 entry 는 `(term, command)` 를 저장. log 는 leader 가 *append-only* 로 유지.

**commit index** 는 *과반수 이상에 복제된* 최대 index. leader 가 commit index 를 advance 하면 follower 도 따라간다. commit 된 entry 만 state machine 에 적용된다.

```
# Leader 의 핵심 상태 (의사 코드)
class RaftServer:
    state: NodeState                   # FOLLOWER / CANDIDATE / LEADER
    currentTerm: int                   # 본 노드가 본 가장 큰 term
    votedFor: NodeId | None           # currentTerm 에서 투표한 후보
    log: List[Entry]                   # log[i] = (term, cmd)
    commitIndex: int = 0
    lastApplied: int = 0

    # leader 만 가지는 상태
    nextIndex: Dict[NodeId, int]       # 각 follower 에 다음 보낼 entry index
    matchIndex: Dict[NodeId, int]      # 각 follower 에 복제 완료된 max index
```

leader 는 매 heartbeat (보통 50~100ms) 마다 `AppendEntries` 를 보낸다. follower 의 election timeout (보통 150~300ms 랜덤) 안에 heartbeat 가 안 오면 candidate 가 되어 새 election 을 시작한다.

## 3. Leader Election — split vote 와 randomized timeout

election 시작 시 candidate 는 *currentTerm += 1* 하고 *self vote* 후 다른 노드에 `RequestVote(term, candidateId, lastLogIndex, lastLogTerm)` 를 보낸다. follower 는 다음 조건을 모두 만족하면 grant.

1. `term ≥ currentTerm` (낡은 candidate 거부)
2. `votedFor == None or votedFor == candidateId` (이미 다른 노드에 투표하지 않음)
3. *candidate 의 log 가 자신의 log 만큼 up-to-date*
   - `lastLogTerm > self.lastLogTerm` 이거나 (term 비교 우선)
   - `lastLogTerm == self.lastLogTerm and lastLogIndex >= self.lastLogIndex`

**3번 조건이 Leader Completeness 의 핵심** 이다. 이미 commit 된 entry 가 어떤 노드의 log 에 있어야 하므로, 그 entry 를 가진 노드만 leader 가 될 수 있다. leader 가 없는 동안 commit 된 entry 가 사라지지 않는다.

split vote 가 발생하면(과반수 못 모음) 모든 candidate 가 *다음 randomized timeout* 후 다시 시도한다. timeout 이 random 이라 일반적으로 한 노드가 먼저 시작하고 나머지는 follower 가 된다.

```
# election 의사 코드
def start_election():
    currentTerm += 1
    votedFor = self.id
    state = CANDIDATE
    votes = {self.id}
    for peer in peers:
        send RequestVote(currentTerm, self.id, lastLogIndex, lastLogTerm)
    # 비동기로 응답 도착 시
    on response (granted, term):
        if term > currentTerm:
            currentTerm = term; state = FOLLOWER; return
        if granted: votes.add(peer)
        if len(votes) > len(cluster) / 2:
            become_leader()
```

## 4. Log Replication — Log Matching property

leader 는 client 요청을 받으면 자신의 log 에 entry 를 append 하고 `AppendEntries(term, prevLogIndex, prevLogTerm, entries[], leaderCommit)` 를 모든 follower 에게 보낸다. follower 는 *prev 매칭* 을 검사한다.

```
def append_entries(req):
    if req.term < currentTerm:
        return False
    if req.prevLogIndex > 0:
        if len(self.log) < req.prevLogIndex:
            return False
        if self.log[req.prevLogIndex].term != req.prevLogTerm:
            return False
    # 일치 → 그 뒤를 덮어쓰기
    self.log = self.log[:req.prevLogIndex+1] + req.entries
    if req.leaderCommit > self.commitIndex:
        self.commitIndex = min(req.leaderCommit, len(self.log) - 1)
    return True
```

**Log Matching Property**: 같은 (index, term) 의 entry 는 *모든 노드에서 동일* 하고, 그 이전의 모든 entry 도 동일하다. 이 property 는 *prev 매칭* 으로 귀납적으로 보장된다.

매칭 실패 시 leader 는 `nextIndex` 를 줄여 *더 이전 entry 부터* 재시도한다. 최악의 경우 1개 entry 까지 거슬러 올라가지만 *Log Index 별 binary search* 또는 *conflicting term 점프* 같은 최적화로 빠르게 수렴한다 (논문 §5.3).

## 5. Commitment Rule — 과반수만으로 충분하지 않다

leader 가 `matchIndex` 를 보고 *과반수 이상에 복제된 index* 를 commit 으로 advance 하는데, **자기 currentTerm 의 entry** 만 그렇게 commit 할 수 있다. 이전 term 의 entry 는 직접 commit 하지 않는다. *현재 term 의 entry 가 commit 되면, 그보다 이전의 entry 도 자동 commit*. 이 규칙이 없으면 *Leader Completeness 가 깨지는 시나리오* 가 존재한다(논문 §5.4 Figure 8 의 시나리오 S5).

```
시나리오 (5-노드 클러스터):
1. S1 leader (term 2), entry 2 를 S2 에 복제 후 crash.
2. S5 가 leader (term 3) 됨.
3. S5 가 entry 3 을 자기 log 에만 두고 crash.
4. S1 다시 leader (term 4). S1 의 log: [1,2]. entry 2 를 과반수에 복제 가능.
   → 이 시점에 entry 2 를 commit 하면 안전한가?
   답: NO. S5 가 다시 leader 되어 entry 3 을 가져오면 entry 2 가 덮어쓰일 수 있음.
   → 그래서 entry 2(이전 term)는 단독 commit 불가.
   → S1 이 entry 4(term 4) 를 commit 하면 entry 2 도 같이 commit.
```

이 규칙은 *직관에 어긋나* 구현에서 흔히 빠뜨린다. etcd 도 v0.4 에서 비슷한 버그가 있었다 (#3877).

## 6. Pre-Vote / Leader Lease / Joint Consensus

기본 Raft 의 운영 한계를 보완하는 세 가지 확장이 사실상 표준이다.

**Pre-Vote** (Ongaro 박사논문, §9.6) 는 partition 후 복귀한 노드가 currentTerm 을 함부로 올리지 못하게 한다. candidate 가 되기 전 *pre-vote* 단계로 *과반수가 자신을 leader 로 인정할 의사* 가 있는지 묻는다. 이 단계가 없으면 partition 노드가 복귀할 때마다 term 이 +1 되어 *현재 leader 가 unnecessarily step-down* 한다. etcd, TiKV 모두 채택.

**Leader Lease (or PreVote 와 함께 ReadIndex)** 는 *읽기 일관성* 을 위한 확장이다. 기본 Raft 는 read 도 log 에 적어 commit 후 응답해야 strict serializable. 이는 비싸다. lease 방식: leader 가 election timeout 보다 짧은 lease 를 유지하며 그 안의 read 는 *log 안 거치고 직접 응답*. 단, lease 는 *clock drift* 를 가정하므로 NTP 동기화가 안 되는 환경에서는 위험하다. ReadIndex 는 lease 대신 *현재 commitIndex 가 leader 의 commitIndex 와 일치한다는 RPC 응답* 을 받고 read 한다. lease 보다 안전하지만 RTT 1회 추가 비용.

**Joint Consensus** 는 *cluster membership 변경* 을 안전하게 한다. C_old 와 C_new 사이에 *C_old,new* 라는 transitional configuration 을 두고, 두 quorum 에서 모두 과반수가 commit 된 entry 만 commit. 이 접근으로 *single-step membership change* 의 race condition 을 회피. 실무에서는 더 단순한 *single-server addition* 만 허용하는 변형이 흔하다(etcd 의 learner 노드).

## 7. 실제 구현의 변형 — etcd / TiKV / RabbitMQ

**etcd Raft 라이브러리** (Go) 는 lib 으로 분리되어 다른 프로젝트 (Kubernetes API server, M3DB) 가 재사용한다. PreVote, ReadIndex, Lease, learner 노드, snapshot 까지 모두 포함. snapshot 은 *log 가 무한 커지는 것을 막기* 위해 state machine 을 통째로 직렬화해 저장하고 그 시점 이전의 log 를 truncate.

**TiKV** 는 HBase 의 region 개념을 차용해 *수만 개 Raft group* 을 노드 한 개에서 운영한다. region 마다 별도 Raft state machine 이 돌고, region 간 split/merge 가 추가된다. snapshot 전송이 빈번해 *batch / pipelining* 최적화가 핵심이다. TiKV 의 Raft 코드는 수만 라인 규모.

**RabbitMQ Quorum Queue** 는 큐별로 Raft 를 적용한 것. Raft 의 안전성 덕분에 mirroring 큐의 split-brain 문제가 사라졌다. 단, 큐마다 leader 가 분산되도록 *initial leader balancer* 가 필요하다.

| 시스템 | Raft 변형 특징 | 한계 |
|---|---|---|
| etcd | 표준에 가장 가까움 | 단일 Raft group → cluster 크기 한계 |
| Consul | etcd 와 거의 동일 | 동일 |
| TiKV | multi-Raft (region 단위) | snapshot 전송 부하 |
| CockroachDB | range 단위 multi-Raft | TiKV 와 유사 |
| RabbitMQ Quorum Queue | queue 단위 | per-queue leader balancing 필요 |
| RethinkDB | Raft 기반 메타데이터 | 데이터 자체는 다른 모델 |

## 8. 운영에서 본 4가지 함정

첫째, *clock 의 쓰임에 주의*. 기본 Raft 는 wall-clock 을 안 쓰지만 election timeout / heartbeat 만 monotonic clock 으로 측정한다. lease 기반 read 를 쓰면 clock skew 가 안전성에 직결되므로 NTP 동기화 / Spanner 의 TrueTime 같은 보강이 필요하다.

둘째, *과반수의 의미*. 5-노드 클러스터의 과반수는 3 이다. 2-노드 클러스터의 과반수는 2 이다. 즉 *2-노드 클러스터는 한 노드 죽으면 멈춘다*. 운영에서는 항상 *홀수 노드(3 또는 5)* 를 유지한다. 4-노드는 fault tolerance 가 3-노드와 같지만 latency 만 늘어 *효율이 떨어진다*.

셋째, *snapshot 시점*. snapshot 너무 자주 = 디스크 I/O 폭발. 너무 드물게 = 새 follower join 시 log replay 비용 증가. log size = 1GB 를 임계로 두는 운영이 흔하다.

넷째, *leader 의 부하 집중*. 모든 write 가 leader 로 가므로, leader 노드의 CPU/네트워크가 병목이 된다. *region 단위 multi-Raft* 가 해법이지만 기본 Raft 라이브러리 만으로는 leader 만 강하게 키워야 한다.

## 참고

- Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm" USENIX ATC 2014
- Ongaro 박사논문, "Consensus: Bridging Theory and Practice" Stanford 2014
- etcd Raft 라이브러리 GitHub — `etcd-io/raft`
- "TiKV in Action" - PingCAP Engineering Blog