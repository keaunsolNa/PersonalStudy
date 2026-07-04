Notion 원본: https://app.notion.com/p/3935a06fd6d381349c2cf81903d118e7

# Raft 합의 알고리즘 리더 선출과 로그 복제 및 멤버십 변경

> 2026-07-05 신규 주제 · 확장 대상: 면접을_위한_CS_전공지식_노트

## 학습 목표

- Raft 의 세 상태(Follower/Candidate/Leader)와 term 기반 리더 선출을 설명한다
- 로그 복제의 매칭 속성과 커밋 규칙, commitIndex 진행 조건을 파악한다
- 이전 term 엔트리를 직접 커밋하지 않는 안전성 규칙의 이유를 이해한다
- Joint Consensus 로 무중단 멤버십 변경을 수행하는 절차를 정리한다

## 1. 왜 Raft 인가

Paxos 는 정확하나 이해·구현이 어렵다. Raft 는 이해 가능성을 목표로 문제를 리더 선출·로그 복제·안전성으로 분해했다. etcd, Consul, TiKV, CockroachDB 가 채택한다. 강한 리더 모델로 모든 요청이 리더를 거치고 로그는 리더→팔로워 단방향으로만 흐른다.

## 2. 상태와 term — 논리적 시계

각 노드는 Follower/Candidate/Leader 중 하나다. term 은 단조 증가 정수로 논리 시계이며 한 term 에 리더는 최대 한 명이다. 모든 RPC 는 term 을 싷고, 더 큰 term 을 보면 즉시 갱신하고 Follower 로 물러나 stale leader 를 자동 퇴위시킨다.

## 3. 리더 선출 — 랜덤 타임아웃

Follower 는 election timeout(150~300ms) 동안 하트비트를 못 받으면 term+1 하고 자신에게 투표한 뒤 RequestVote 를 뿌린다. 과반 득표 시 Leader 가 된다. 노드는 term 당 한 번만 투표하고, Candidate 로그가 (마지막 term, 인덱스) 사전순으로 최신일 때만 투표한다. 타임아웃을 랜덤화해 split vote 를 회피한다.

```python
def on_request_vote(req):
    if req.term < current_term:
        return VoteResponse(current_term, granted=False)
    if req.term > current_term:
        current_term = req.term; voted_for = None; become_follower()
    log_ok = (req.last_log_term, req.last_log_index) >= (my_last_log_term, my_last_log_index)
    if voted_for in (None, req.candidate_id) and log_ok:
        voted_for = req.candidate_id; reset_election_timer()
        return VoteResponse(current_term, granted=True)
    return VoteResponse(current_term, granted=False)
```

## 4. 로그 복제와 매칭 속성

리더는 명령을 (term, index, command) 엔트리로 추가해 AppendEntries 로 복제한다. 각 RPC 는 prevLogIndex/prevLogTerm 을 실어, 팔로워는 그 위치 term 이 일치할 때만 수락한다. 불일치 시 nextIndex 를 낮춰 백트래킹 후 리더 로그로 덮어쓴다. 이로써 같은 인덱스·term 이면 이전 모두 동일하다는 Log Matching Property 가 성립한다.

```python
def on_append_entries(req):
    if req.term < current_term:
        return AppendResponse(current_term, success=False)
    reset_election_timer()
    if req.prev_log_index > 0:
        if len(log) < req.prev_log_index or log[req.prev_log_index].term != req.prev_log_term:
            return AppendResponse(current_term, success=False)
    append_or_overwrite(req.entries, start=req.prev_log_index + 1)
    if req.leader_commit > commit_index:
        commit_index = min(req.leader_commit, last_new_index)
    return AppendResponse(current_term, success=True)
```

## 5. 커밋 규칙과 이전 term 함정

엔트리가 과반 복제되면 커밋·apply 한다. 단 리더는 이전 term 엔트리를 "과반 복제됨"만으로 커밋하면 안 된다(Figure 8). 과반 복제된 이전-term 엔트리가 새 리더에 의해 덮여 커밋이 뒤집힐 수 있기 때문이다. 리더는 자신의 현재 term 엔트리를 커밋할 때 딸린 이전 term 엔트리를 함께 확정한다. 그래서 새 리더는 취임 직후 no-op 엔트리를 넣어 이전 로그를 간접 확정한다.

## 6. 멤버십 변경 — Joint Consensus

구성을 한 번에 바꾸면 옛·새 구성이 서로 다른 과반을 만들어 두 리더가 나올 수 있다. Raft 는 C_old,new 과도 구성을 먼저 커밋해 이 기간 모든 결정이 C_old 과반 AND C_new 과반을 요구하게 한다. 그 뒤 C_new 만 커밋해 전환한다. 새 노드는 캐치업 동안 투표권 없는 learner 로 참여시킨다.

```
C_old --[C_old,new 커밋]--> C_old,new --[C_new 커밋]--> C_new
```

## 7. 실무 — 스냅샷과 읽기 일관성

로그는 스냅샷으로 압축하고, 뒤처진 팔로워에는 InstallSnapshot 으로 전송한다. stale read 를 막으려면 ReadIndex(과반 하트비트 재확인) 또는 lease read 를 쓴다.

| 항목 | 메커니즘 | 목적 |
|---|---|---|
| 리더 선출 | 랜덤 타임아웃+term | 단일 리더 |
| 로그 복제 | prevLog 일관성 검사 | Log Matching |
| 커밋 | 과반+현재 term | Figure 8 안전성 |
| 멤버십 | Joint Consensus | split brain 방지 |
| 선형 읽기 | ReadIndex/lease | stale read 방지 |

## 참고

- Ongaro, Ousterhout, "In Search of an Understandable Consensus Algorithm (Raft)" (2014)
- Ongaro 박사학위 논문 "Consensus: Bridging Theory and Practice"
- raft.github.io, etcd-io/etcd raft 패키지 문서
