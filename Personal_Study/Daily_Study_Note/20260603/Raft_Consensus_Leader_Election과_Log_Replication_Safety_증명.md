Notion 원본: https://www.notion.so/3745a06fd6d381d8ad27ceab6ccb4cc6

# Raft Consensus Leader Election과 Log Replication Safety 증명

> 2026-06-03 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- Raft 의 *term, candidate, leader, follower* 상태기계와 election timeout / heartbeat 의 상호작용을 그린다
- AppendEntries RPC 의 consistency check 와 log matching property 가 정합성을 어떻게 보장하는지 따라간다
- *commit index* 와 *applied index* 의 분리, 그리고 *log compaction* (snapshot) 의 운영 의미를 정리한다
- etcd / TiKV / CockroachDB 의 Raft 구현 차이와 multi-Raft 의 sharding 모델을 비교한다

## 1. 합의 알고리즘이 풀어야 하는 문제

합의(consensus) 는 *n 개 노드가 같은 값에 도달하는 것*. 비동기 네트워크 + 실패 가능 노드 결합 → *FLP 불가능성*. Paxos / Raft 는 *safety* 항상 보장, *liveness* 는 *eventual synchrony* 가정. Raft 는 단일 leader + log + replication. understandability 가 design goal.

## 2. 상태기계 — Follower / Candidate / Leader

**Follower**: 수동적, election timeout (150–300 ms 랜덤) 내 leader 메시지 안 오면 Candidate. **Candidate**: term + 1, RequestVote 모두에게, 과반수 vote 시 Leader, 더 높은 term 볼 시 Follower 회귀. **Leader**: heartbeat 50 ms 마다, log append + replication. election timeout 의 랜덤 범위가 vote split 방지.

## 3. Term — 논리 시계

term 은 단조 증가 정수. RPC 에 항상 term, 낮은 term 메시지 거부, 높은 term 을 본 노드는 즉시 자신의 term 업데이트하고 Follower 회귀. 동시에 두 leader 가 존재할 수 있지만 같은 term 에는 하나만.

## 4. RequestVote RPC 의 제약 — Up-to-date Log

*election restriction*: voter 는 자신의 마지막 log entry 가 candidate 의 마지막 log entry 보다 up-to-date 하면 vote 거부. up-to-date 비교: `(lastLogTerm, lastLogIndex)` lexicographic. 이 규칙이 *Leader Completeness Property* 보장.

## 5. AppendEntries 와 Log Matching Property

leader 가 새 command 를 log 에 append 한 뒤 follower 에 `AppendEntries(prevLogIndex, prevLogTerm, entries[])`. follower 는 prevLogIndex 의 term 이 일치 안 하면 거부. leader 는 prevLogIndex 모 1 씩 감소 재시도. *Log Matching Property*: 같은 index + term 동일 면 이전 모든 entry 동일.

## 6. Commit 의 조건과 Stale Read

leader 는 과반수 follower 가 entry 를 받았다고 응답한 entry 를 commit. *단, leader 자신의 term 의 entry* 여야 한다 — *Figure 8 problem*. linearizable read 는 *ReadIndex* 패턴.

## 7. Snapshot — 무한 log 의 회피

너무 뒤처진 follower 에 leader 는 `InstallSnapshot` RPC 로 snapshot 통째 전송. snapshot 시점 결정은 *snapshot 빈도 ↔ log 길이 ↔ 복구 시간* 3-trade-off. etcd default 10만 entry.

## 8. 운영 — etcd, TiKV, CockroachDB

| 시스템 | Raft 구성 | shard 단위 | snapshot 정책 |
|---|---|---|---|
| etcd | 단일 Raft group | 전체 KV 1개 | 10만 entry |
| Consul | 단일 Raft group | 전체 KV 1개 | 8MB 또는 16k entry |
| TiKV | multi-Raft | Region (96 MB) | 200MB 또는 split |
| CockroachDB | multi-Raft | Range (512 MB) | 64 MB 마다 |

**multi-Raft** 핵심 확장 패턴. 수천~수만 region 명당 독립 Raft group. heartbeat amplification → TiKV batched heartbeat. snapshot transfer load → throttling. cross-region transaction → 2PC / Percolator.

## 9. Trade-off 와 한계

장점: Paxos 보다 이해 쉬움. 한계: write throughput 의 leader bottleneck → multi-Raft. election storm → pre-vote 의 candidate 사전 조사. etcd v3.4+ 활성. Byzantine 에 약함 — crash failure 만 가정, BFT 가 필요하면 Tendermint / HotStuff.

## 참고

- Raft Paper (USENIX ATC 2014): https://raft.github.io/raft.pdf
- Diego Ongaro PhD Thesis (2014, full Raft)
- etcd Raft library: https://github.com/etcd-io/raft
- TiKV multi-Raft design
- CockroachDB Range / Raft architecture
