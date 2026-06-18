Notion 원본: https://www.notion.so/3835a06fd6d381928ad3e4a597a69025

# Raft 합의 알고리즘과 리더 선출 및 로그 복제 안전성 보장

> 2026-06-18 신규 주제 · 확장 대상: 면접을 위한 CS 전공지식 노트

## 학습 목표

- term·로그 인덱스·상태 머신으로 구성된 Raft 의 합의 모델을 설명한다
- RequestVote·AppendEntries RPC 와 선출 타임아웃의 동작을 의사코드로 구현한다
- Log Matching·Leader Completeness 성질이 안전성을 보장하는 원리를 추적한다
- 커밋 규칙과 commitIndex 전파, 스플릿 브레인 방지 조건을 정리한다

## 1. 합의 문제와 Raft 의 설계 목표

복제 상태 머신은 여러 노드가 동일한 로그에 합의하면 같은 순서로 명령을 적용해 동일 상태에 도달한다. Raft 는 이해 가능성을 목표로 리더 선출·로그 복제·안전성으로 분해했다. 한 term 에 리더는 최대 한 명이고 흐름은 리더→팔로워 단방향이다.

## 2. Term: 논리적 시계

```
on receive RPC with term T:
    if T > currentTerm: currentTerm=T; votedFor=null; state=FOLLOWER
    if T < currentTerm: reply false
```

이 규칙이 오래된 리더가 클러스터를 오염시키는 것을 막는다.

## 3. 리더 선출과 RequestVote

```
function startElection():
    currentTerm += 1; state=CANDIDATE; votedFor=self; votes=1
    for peer: send RequestVote { term, candidateId, lastLogIndex, lastLogTerm }

function onRequestVote(req):
    if req.term < currentTerm: return false
    if (votedFor in {null, req.candidateId}) and candidateLogIsUpToDate(...):
        votedFor = req.candidateId; return true
    return false
```

과반 투표가 split brain 을 막는다 — 두 과반 집합은 반드시 교집합이 있고 한 term 에 한 번만 투표한다. 랜덤 타임아웃은 split vote 를 줄인다.

## 4. 로그 복제와 AppendEntries

```
function onAppendEntries(req):
    if req.term < currentTerm: return false
    if log[req.prevLogIndex].term != req.prevLogTerm: return false
    appendNewEntries(req.entries)
    if req.leaderCommit > commitIndex: commitIndex = min(req.leaderCommit, lastNewIndex)
    return true
```

팔로워가 거부하면 리더는 `nextIndex` 를 줄여 일치 지점까지 거슬러 올라가 리더 로그로 수렴시킨다.

## 5. 안전성: Log Matching 과 Leader Completeness

Log Matching: 같은 index·term 엔트리는 같은 명령이며 이전도 동일하다. Leader Completeness: 커밋된 엔트리는 더 높은 term 리더 로그에 반드시 존재한다 — 선출 시 "후보 로그가 과반만큼 최신"을 요구하기 때문이다.

```
주의: 리더는 "현재 term" 엔트리가 과반 복제될 때만 직접 커밋한다(Figure 8 방지).
```

## 6. 커밋과 상태 머신 적용

```
while lastApplied < commitIndex:
    lastApplied += 1; stateMachine.apply(log[lastApplied].command)
```

| 용어 | 의미 |
|---|---|
| term | 임기, 논리 시계 |
| commitIndex | 커밋 확정 최대 index |
| lastApplied | 상태 머신 적용 최대 index |
| nextIndex/matchIndex | 팔로워별 복제 추적 |

## 7. 네트워크 분할 시나리오 추적

5노드에서 {A,B}와 {C,D,E}로 분할되면 A(term5)는 과반을 못 얻어 커밋 불가(가용성 손실, 정합성 보존). {C,D,E}는 C가 term6으로 선출된다. 복구 시 A는 term6 메시지를 보고 follower 로 강등, 커밋 안 된 엔트리는 폐기되지만 커밋된 데이터는 손실 없다. Raft 는 CAP 의 CP 를 택한다.

## 8. 멤버십 변경·스냅샷·실무 적용

구성 변경은 joint consensus 나 single-server change 로, 로그 폭증은 스냅샷·InstallSnapshot 으로 해결한다. etcd·Consul·TiKV·CockroachDB 에 쓰인다. N 노드는 ⌊(N-1)/2⌋ 장애까지 견디며 홀수(3,5,7)로 구성한다. 모든 쓰기는 과반 왕복이 필요해 정족수 노드의 네트워크 근접성이 쓰기 지연을 좌우한다.

## 참고

- Ongaro, Ousterhout — In Search of an Understandable Consensus Algorithm (https://raft.github.io/raft.pdf)
- The Raft Consensus Algorithm (https://raft.github.io/)
- etcd — Raft 구현 (https://etcd.io/docs/latest/learning/)
