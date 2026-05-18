Notion 원본: https://www.notion.so/3645a06fd6d381d2a5e3d4c3a116ce17

# Raft Leader Election과 Log Replication — etcd 구현 분석

> 2026-05-18 신규 주제 · 확장 대상: 통신_네트워크 · 면접을_위한_CS_전공지식_노트

## 학습 목표

- Raft 프로토콜의 term, log index, commit index, applied index 의미와 노드 상태 전이를 명세 수준으로 설명한다
- Leader Election의 randomized timeout과 split vote 회피 메커니즘을 구현 코드 수준에서 추적한다
- Log Replication의 AppendEntries, prevLogIndex/Term 일치 규칙, log compaction을 etcd `raft` 모듈 코드로 확인한다
- 운영 시 발생하는 leader flap, slow follower, snapshot 폭주 같은 장애 패턴을 사전 진단·튜닝한다

## 1. Raft가 푸는 문제 — 합의(Consensus)의 정의

분산 시스템의 합의는 "한 명령의 적용 순서를 모든 노드가 동일하게 결정한다"는 문제다. Paxos가 이론적으로 먼저 풀었지만 구현이 어려워, Stanford의 Diego Ongaro가 2014년 "understandability"를 명시적 목표로 한 Raft를 발표했다.

Raft는 강한 leader 모델이다. 한 시점에 클러스터에는 leader가 하나뿐이고, 모든 client write는 leader를 통해 들어가 log entry로 추가된 뒤 majority의 follower에 복제되면 commit된다. 합의는 leader가 단일 결정자이기 때문에 단순해진다.

state machine은 다음 셋이다.

| 상태 | 역할 |
|---|---|
| Follower | leader의 AppendEntries / heartbeat 수신, election timeout 시 candidate로 전환 |
| Candidate | election 시작, RequestVote 송신, majority vote 획득 시 leader |
| Leader | client request 수신, AppendEntries 송신, heartbeat 유지 |

## 2. Term과 Log Index — 합의의 두 좌표

Term은 단조 증가하는 정수다. 새 election이 시작될 때마다 1씩 증가한다. 메시지는 sender의 term을 항상 포함하고, receiver는 자신의 term보다 낮은 메시지를 거절하며, 높은 term을 보면 즉시 follower로 전환하고 자신의 term을 올린다. term이 동일 클러스터에 동시에 두 명의 leader가 존재하지 않음을 보장한다.

Log Index는 log entry의 1부터 시작하는 순번이다. 각 entry는 `(term, index, command)`로 식별된다. 두 노드의 log가 같은 (term, index)를 가지면 그 이전까지 동일하다는 강한 invariant("Log Matching Property")가 Raft의 핵심이다.

추가로 두 개의 "watermark"를 가진다. **commitIndex**는 클러스터 majority에 복제 완료된 마지막 index로, 이 index 이하는 안전하게 state machine에 적용 가능하다. **lastApplied**는 실제로 state machine에 적용된 마지막 index로, commitIndex 이하에서 단조 증가한다.

## 3. Leader Election — randomized timeout과 split vote 회피

각 follower는 election timeout(기본 150~300ms 랜덤)을 가진다. timeout 내에 leader로부터 AppendEntries(빈 heartbeat 포함)를 받지 못하면 candidate로 전환하면서 자신의 term을 +1하고, 자기 자신에게 vote하며, 모든 peer에게 RequestVote RPC를 송신한다. majority vote를 수령하면 leader로 전환해 즉시 heartbeat를 전송하고, 다른 leader의 AppendEntries(자기 term ≤ 상대 term)를 수신하면 follower로 전환한다. election timeout이 재발생하면 term을 더 올리고 다시 시도한다.

split vote는 두 candidate가 동시에 election을 시작해 vote를 나눠 가질 때 발생한다. randomized timeout이 두 candidate의 timeout 시점을 비확률적으로 어긋나게 만들어 다음 라운드에서는 한쪽이 먼저 vote를 모은다.

vote 부여 규칙은 단순하다. 자신의 currentTerm > 요청의 term이면 거절, 같은 term에서 이미 누군가에게 vote했으면 거절, candidate의 lastLogTerm/lastLogIndex가 자신보다 "최소한 같거나" 더 최신이면 승인한다. 마지막 조건이 "Election Restriction"이다. commit된 entry를 가진 follower만 leader가 되어 commit history를 잃지 않게 한다.

## 4. Log Replication — AppendEntries와 일치 검사

leader는 client command를 받으면 즉시 자기 로그에 append하고, 각 follower에게 AppendEntries RPC를 보낸다.

RPC payload:

| 필드 | 의미 |
|---|---|
| term | leader의 term |
| leaderId | leader 식별자 |
| prevLogIndex | 이번에 보내는 entry 직전 index |
| prevLogTerm | prevLogIndex의 term |
| entries[] | 추가할 entry 배열 |
| leaderCommit | leader의 commitIndex |

follower의 검증은 다음 순서로 진행된다. term이 자신보다 낮으면 거절(false), prevLogIndex 위치에 entry가 없거나 term이 prevLogTerm과 다르면 거절(false), 통과하면 entries를 자기 log에 append하고 충돌 시 그 이후 모든 entry를 truncate, leaderCommit이 자신의 commitIndex보다 크면 min(leaderCommit, lastNewEntryIndex)로 갱신한다.

이 검증이 "Log Matching"을 보장한다. follower가 false를 반환하면 leader는 `nextIndex`를 1 줄여 다시 보내고, 일치할 때까지 backtracking한다(etcd는 hint를 추가해 한 번에 큰 점프로 backtracking).

commit 조건은 두 가지를 모두 만족할 때다. entry의 index가 majority replication을 달성해야 하고, entry의 term이 leader의 현재 term과 같아야 한다("Leader Completeness Rule"). 두 번째가 핵심이다. 이전 term에서 만들어진 entry는 majority 복제만으로는 commit 못한다. 같은 leader가 자기 term의 new entry를 commit하는 순간에 함께 commit된다. 이 규칙이 없으면 새 leader가 옛 entry를 잘못 덮어쓰는 시나리오가 가능해진다.

## 5. etcd의 `raft` 모듈 — `raft.Node` 인터페이스

etcd는 Raft 구현을 라이브러리(`go.etcd.io/raft/v3`)로 분리해 두었다. 핵심은 `raft.Node` 인터페이스다. 사용자가 직접 네트워크·디스크·state machine을 붙이고, 라이브러리는 순수 상태기계(`raft.raft`)만 담당한다.

```go
// 사용자 측 메인 루프
for {
    select {
    case <-time.Tick(100 * time.Millisecond):
        n.Tick()                       // 내부 election/heartbeat 타이머 진척

    case rd := <-n.Ready():             // 처리할 변경 사항 수령
        saveToStorage(rd.HardState, rd.Entries)   // 1. WAL/snapshot에 entry 저장
        send(rd.Messages)                          // 2. RPC 송신
        for _, ent := range rd.CommittedEntries {  // 3. state machine에 적용
            process(ent)
        }
        n.Advance()                               // 4. 다음 batch로 진행

    case <-stop:
        return
    }
}
```

`Ready` 구조체는 한 번에 처리해야 할 "변경 사항 묶음"을 담는다. WAL persist → 네트워크 송신 → 적용 → Advance의 순서가 깨지면 안 된다. WAL 먼저, 그 다음 네트워크, 그 다음 apply 순서가 crash recovery의 안전성을 보장한다.

## 6. 운영 장애 패턴 — leader flap, slow follower, snapshot 폭주

**Leader flap**(잦은 leader 교체)은 election timeout이 RTT보다 짧거나, 네트워크 지연이 spike하거나, leader가 disk IO에 막혀 heartbeat를 못 보낼 때 발생한다.

대응:

```
--election-timeout=1000       # 기본 1000ms
--heartbeat-interval=100      # 1/10 비율 유지
```

heartbeat가 election timeout의 1/5~1/10이 되도록 둔다. WAL이 NVMe SSD 위에 있는지 확인하고, sync 비활성화 옵션(`--unsafe-no-fsync`)은 데이터 손실 가능해 절대 금지.

**Slow follower**: 한 follower가 GC pause나 디스크 stall로 뒤처지면 leader의 `nextIndex`가 점점 작아져 한 번에 보내는 entry가 늘어난다. 따라잡지 못하면 leader가 결국 snapshot을 보낸다. `etcdctl endpoint status`로 `raftAppliedIndex` 차이를 추적한다.

```
ENDPOINT     ID         REVISION   RAFT-INDEX   APPLIED-INDEX   ...
node-1       a1...      512345     512345       512345
node-2       b2...      512345     512345       512300          ← 45 lag
node-3       c3...      512100     512100       512100          ← 245 lag
```

3번 노드가 250 entry 이상 뒤처지면 leader는 snapshot 전송 모드로 전환한다.

**Snapshot 폭주**: snapshot은 디스크 IO와 네트워크를 동시에 점유한다. `--snapshot-count=100000` 기본값을 잘 조정한다. 너무 작으면 snapshot 빈도가 높아져 leader IO를 잡아먹고, 너무 크면 WAL이 비대해져 recovery가 느려진다. 운영에서는 10만~50만 사이가 일반적이다.

## 7. Membership Change — joint consensus

노드 추가/제거 시 단순히 config를 바꾸면 "split brain" 위험이 있다. Raft 원논문의 single-server change는 한 번에 한 노드만 바꾸는 안전한 변경, joint consensus는 두 config(`C_old`, `C_new`)의 majority를 동시에 요구하는 중간 단계를 거치는 방식이다.

etcd는 single-server change를 기본으로 사용한다. 노드 한 명씩 추가/제거하고, 그 사이 클러스터가 quorum을 유지하도록 운영자가 조심해야 한다. `etcdctl member add`/`remove`로 진행한다.

```bash
etcdctl member add node-4 --peer-urls=https://10.0.0.4:2380
# 새 노드 시작 (--initial-cluster-state=existing)
# 등록 완료 후 다음 노드 작업
```

3노드 클러스터에서 한 노드를 추가→제거 두 단계로 교체하는 동안 quorum(2)이 깨질 수 있다. 안전하려면 5노드로 임시 확장한 후 옛 노드를 제거하는 패턴을 쓴다.

## 8. 측정 — Raft 성능 지표

etcd의 Prometheus metric 핵심:

| metric | 의미 | 임계 |
|---|---|---|
| `etcd_server_leader_changes_seen_total` | leader 교체 누적 | 5분에 1 이상이면 비정상 |
| `etcd_server_proposals_failed_total` | propose 실패 | 지속 증가 = quorum 문제 |
| `etcd_disk_wal_fsync_duration_seconds` | WAL fsync p99 | 100ms 초과 시 디스크 stall |
| `etcd_network_peer_round_trip_time_seconds` | peer RTT | election_timeout의 1/10 이하 유지 |
| `etcd_server_proposals_committed_total` | commit 누적 | 처리량 추적 |

벤치마크는 `benchmark` 도구로:

```bash
benchmark put --conns=100 --clients=100 --key-size=8 --val-size=256 --total=100000
```

3노드 클러스터(NVMe SSD)에서 약 20,000~30,000 ops/s가 baseline이다.

## 9. Raft vs Paxos vs Zab — 선택 가이드

| 알고리즘 | 사용처 | leader 모델 | 핵심 차이 |
|---|---|---|---|
| Raft | etcd, Consul, CockroachDB, TiKV | strong single leader | 가장 단순. 구현 코드량 적음 |
| Multi-Paxos | Spanner, ChubbyLock | 약한 leader, 다중 proposer 가능 | 이론적으로 더 유연하지만 구현 난이도 높음 |
| Zab | ZooKeeper | primary-backup, atomic broadcast | totally ordered broadcast가 primary 목적 |
| EPaxos | (실험적) | leaderless | leader 병목 제거, 그러나 commit 경로 복잡 |
| Viewstamped Replication | (학술적) | 강한 view leader | Raft의 직접적 조상 |

Raft가 산업 표준에 가까운 이유는 명세서 자체가 짧고, reference 구현이 다수 존재하며, leader 단일성으로 운영 관찰이 쉽기 때문이다. 신규 분산 시스템 설계 시 합의 알고리즘 선택은 거의 항상 Raft가 baseline이다.

## 10. Read Path 최적화 — ReadIndex와 Lease Read

Raft를 그대로 쓰면 read도 모든 quorum 라운드를 거쳐야 하는데, 이는 read latency를 크게 늘린다. etcd는 두 가지 최적화를 제공한다. **ReadIndex**는 leader가 현재 commitIndex를 기억하고 quorum heartbeat로 자신이 여전히 leader임을 확인한 다음 commitIndex가 applied된 시점에 read를 응답한다. quorum write보다 가볍지만 여전히 1-RTT heartbeat가 필요하다. **Lease Read**는 leader가 election timeout 동안은 자신이 leader임을 가정하고 heartbeat 없이도 즉시 응답한다. clock drift에 의존하므로 NTP 동기화가 필수이며, 운영자가 명시 활성화해야 한다(`--leader-lease-timeout`).

linearizable read가 필요하면 ReadIndex, eventually consistent read로 충분하면 Lease Read, 또는 follower read(stale OK)를 사용한다. etcdctl의 `--consistency=l|s` 옵션으로 호출 측에서 선택 가능하다.

## 11. 정리

Raft는 단일 leader + log replication + term 기반 election으로 합의를 단순하게 만든다. 핵심 invariant는 Election Restriction, Log Matching Property, Leader Completeness Rule이며, etcd는 이를 `Ready` 루프 기반의 라이브러리로 캡슐화해 외부에 노출한다. 운영에서는 leader flap, slow follower, snapshot 폭주 같은 패턴을 election_timeout·heartbeat_interval·snapshot_count의 튜닝과 WAL 디스크 성능 확보로 막는다. membership change는 single-server 모드라도 quorum 깨짐을 피하려면 임시 확장 패턴이 안전하다.

## 참고

- Diego Ongaro, "In Search of an Understandable Consensus Algorithm" (USENIX 2014)
- Raft Consensus Algorithm 공식 사이트 (https://raft.github.io)
- etcd Documentation — Operations, Performance
- etcd-io/raft GitHub README
- Heidi Howard, "Distributed Consensus Revised" (PhD thesis)
