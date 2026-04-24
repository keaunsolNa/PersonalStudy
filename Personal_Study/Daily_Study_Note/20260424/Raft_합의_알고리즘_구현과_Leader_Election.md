Notion 원본: https://www.notion.so/34c5a06fd6d3817f9c75e4dd265ff704

# Raft 합의 알고리즘 구현과 Leader Election

> 2026-04-24 신규 주제 · 확장 대상: 자료구조 & 알고리즘 (Consistent Hashing 학습됨)

## 학습 목표

- Raft 노드의 세 상태(Follower/Candidate/Leader) 전이 규칙을 설명한다
- Leader Election의 term, vote, 랜덤 타임아웃 메커니즘을 구현한다
- Log Replication에서 commitIndex와 matchIndex를 구분한다
- 실제 시스템(etcd, Hazelcast, TiKV)의 Raft 구현을 대조한다

---

## 1. 분산 합의 문제

Paxos는 1989년 Lamport의 거의 사그로 간주되지만 배우기 어렵고 구현하기는 더 어렵다. Ongaro와 Ousterhout가 2014년에 내놓은 Raft는 동일한 안전성/활동성 속성을 유지하면서도 이해와 구현이 훨씬 쉽도록 설계됐다. 분산 합의의 핵심 목표는 **단 하나의 로그 순서**에 괴수의 노드가 동의하는 것이다.

## 2. 상태 기계

각 노드는 Follower, Candidate, Leader 세 상태 중 하나.

- **Follower**: 리더의 AppendEntries를 동기시 적용. 시작 상태.
- **Candidate**: 타임아웃이 나면 term++ 하고 자신에게 투표, RequestVote로 과반수 확보 시도.
- **Leader**: 과반수 득표 후 선출됨. AppendEntries를 주기적으로 전송해 authority 유지.

term은 단조 증가 정수. 신/구 리더가 섞일 때 term으로 식별해 마지막 term의 리더가 우선이다.

## 3. Leader Election 구현

```java
class RaftNode {
    enum Role { FOLLOWER, CANDIDATE, LEADER }
    Role role = Role.FOLLOWER;
    long currentTerm = 0;
    Integer votedFor = null;
    long electionTimeoutMs;
    long lastHeartbeatAt;

    void tick() {
        long now = System.currentTimeMillis();
        if (role != Role.LEADER && now - lastHeartbeatAt > electionTimeoutMs) {
            startElection();
        }
        if (role == Role.LEADER) sendHeartbeats();
    }

    void startElection() {
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = selfId;
        int votes = 1;
        for (int peer : peers) {
            RequestVoteResponse r = rpcClient.requestVote(peer,
                new RequestVote(currentTerm, selfId, log.lastIndex(), log.lastTerm()));
            if (r.voteGranted) votes++;
        }
        if (votes > peers.size() / 2) becomeLeader();
        // 아니면 다음 타임아웃까지 대기
    }
}
```

타임아웃은 **랜덤화**(150~300ms)한다. 모두 같은 값이면 split vote가 반복되기 때문. 이게 Raft 논문 핵심인데 실제 구현에서 빼먹으면 2노드 시스템에서 선거가 무한 반복된다.

## 4. Vote 제약 (Election Restriction)

Candidate가 보내는 RequestVote에는 `lastLogIndex`, `lastLogTerm`이 들어 있다. Follower는 "내 로그보다 최신이 아닌 candidate는 나보다 뷰진때 자격 없음"으로 거부한다. 이로써 과거 리더가 커밋하지 못한 로그를 넣석 삼킨 노드가 새 리더로 뽑히는 사태를 막는다. 이게 Raft의 안전성을 보장하는 밑셐이다.

## 5. Log Replication

리더는 클라이언트 요청을 받으면 자신의 로그에 append하고, AppendEntries RPC로 모든 follower에게 전파한다. Follower가 "나의 prevLogIndex의 term이 리더가 보낸 prevLogTerm과 일치"해야 받아들이고, 거부시엔 리더가 nextIndex를 줄여 재전송. 과반수 follower가 성공 응답하면 **commitIndex**를 올리고 해당 명령을 스테이트 머신에 적용.

```java
class AppendEntries {
    long term;
    int leaderId;
    long prevLogIndex, prevLogTerm;
    List<LogEntry> entries;
    long leaderCommit;
}
class AppendEntriesResponse {
    long term;
    boolean success;  // false 시 leader가 nextIndex--
}
```

## 6. matchIndex vs commitIndex

| 인덱스 | 의미 | 근락 |
|---|---|---|
| nextIndex[i] | 리더가 follower i에게 다음 보낼 로그 번호 | 리더 |
| matchIndex[i] | follower i에 설치 완료된 마지막 로그 | 리더 |
| commitIndex | 과반수 노드에 복제된 마지막 로그 | 전역(leader가 전파) |

commitIndex는 리더가 매 요청 후 matchIndex를 정렬해 중간값(N=5면 3번째 값)을 구해 갱신한다. 단 현재 term에서 생성된 엔트리만 commitIndex 증가 대상이다—이게 Figure 8 안전성 문제의 해결책.

## 7. Log Compaction (Snapshotting)

로그가 무한히 커지면 디스크/메모리 문제가 생긴다. 주기적으로 state machine의 스냅샷을 뜨고 그 이전 로그는 버린다. 뒤처진 follower가 이주해 오면 InstallSnapshot RPC로 전체 스냅샷을 전송하고 이후 로그 스트림을 이어붙인다.

## 8. Membership Changes (Joint Consensus)

노드 추가/제거는 조심스럽다. 중간 상태에서 두 개의 과반수 집합(구 구성원 과반수 + 신 구성원 과반수) 모두를 충족해야 ' commit 가능'으로 간주하는 Joint Consensus 단계를 거친다. 실제 구현은 마앙(`majority-of-both`) 방식이 상식적인 변형이다.

## 9. 실제 구현 대조

- **etcd (Go, CoreOS/Red Hat)**: Kubernetes 클러스터 상태 저장소. 신뢰성 기준점. `etcd-io/raft` 라이브러리라서 앱기프에 그대로 이식 가능.
- **Hazelcast Jet / CP Subsystem (Java)**: JVM 기반 분산 클러스터에서 IMap lock 용도.
- **TiKV (Rust)**: Raft 그룹을 무수히 잔대별로 운용해 수평 확장. 각 그룹이 독립적 로그.
- **Apache Kafka (3.3+, KRaft)**: ZooKeeper 제거. controller metadata 관리에 Raft 변형 사용.

운영 관점 교훈: (1) network partition 대응 시나리오를 반드시 문서화, (2) 리더 선거 중 p99가 기본 300ms를 넘는 구간이 소수 초단위 발생 가능, (3) 디스크 I/O 대기가 리더 RPC latency와 직접 연결—쓴라뒤 관리가 정량적으로 중요.

## 참고

- Diego Ongaro, John Ousterhout "In Search of an Understandable Consensus Algorithm" (USENIX ATC 2014)
- Raft 문서: https://raft.github.io
- etcd raft 라이브러리: https://github.com/etcd-io/raft
- "Designing Data-Intensive Applications" (M. Kleppmann) Ch.9
