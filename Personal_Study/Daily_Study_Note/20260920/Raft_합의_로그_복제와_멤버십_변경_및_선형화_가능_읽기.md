Notion 원본: https://www.notion.so/3e15a06fd6d38116838de128bc2a2bbe

# Raft 합의 로그 복제와 멤버십 변경 및 선형화 가능 읽기

> 2026-09-20 신규 주제 · 확장 대상: 면접을 위한 CS 전공지식 노트, OS

## 학습 목표

- RequestVote 의 up-to-date 비교 규칙을 적용하고 주어진 RTT 에서 election timeout 을 정한다
- AppendEntries 실패 시 conflict term/index 역추적 로직을 의사코드 수준으로 구현한다
- ReadIndex · lease read · no-op write 를 지연·안전성 기준으로 비교해 배치한다
- joint consensus 와 단일 노드 변경의 안전 조건을 구분하고 learner 교체 절차를 설계한다

## 1. 다섯 안전성 속성과 Multi-Paxos·ZAB 과의 차이

Raft 논문(2014)은 알고리즘을 다섯 속성으로 압축한다. **Election Safety**(한 term 에 리더 최대 하나), **Leader Append-Only**(리더는 자기 로그를 덮어쓰지 않는다), **Log Matching**(같은 인덱스에 같은 term 엔트리가 있으면 그 앞 전부가 동일하다), **Leader Completeness**(커밋된 엔트리는 이후 모든 term 의 리더 로그에 있다), **State Machine Safety**(같은 인덱스에 다른 엔트리가 적용되지 않는다).

이 다섯은 사슬이다. Log Matching 은 AppendEntries 의 prevLogIndex/prevLogTerm 검사에서, Leader Completeness 는 RequestVote 의 up-to-date 검사와 커밋 규칙에서 나오고, State Machine Safety 는 그 따름정리다. 방어할 지점은 선거의 로그 비교와 커밋 인정 규칙 둘뿐이다.

| 항목 | Raft | Multi-Paxos | ZAB |
| --- | --- | --- | --- |
| 로그 구멍 | 불허(연속 prefix) | 허용 | 불허 |
| 리더 식별 | term | ballot number | epoch |
| 선출 시 로그 비교 | RequestVote 내장 | Phase 1 사후 복구 | 별도 synchronization |
| 이전 리더 엔트리 확정 | 현재 term 커밋으로 간접 | Phase 1 재제안 | NEWLEADER 후 일괄 |
| 구현 | etcd, TiKV | Chubby | ZooKeeper |

## 2. 리더 선출: up-to-date 비교와 randomized timeout

term 은 논리 시계다. 모든 RPC 에 실려 다니고, 더 큰 term 을 본 노드는 즉시 follower 로 내려간다. 투표 조건은 이 term 에 미투표일 것과 후보 로그가 최소한 자기만큼 최신일 것이다.

```
candidateUpToDate = (args.LastLogTerm > myLastLogTerm) ||
   (args.LastLogTerm == myLastLogTerm && args.LastLogIndex >= myLastLogIndex)
```

길이가 아니라 **마지막 엔트리의 term 을 먼저** 본다. 더 높은 term 엔트리를 가졌다는 건 그 term 리더와 접촉했다는 뜻이고, 그 리더는 이전 term 의 커밋 엔트리를 전부 갖고 있었기 때문이다. 커밋도 당선도 과반이라 두 집합은 겹치므로, 커밋된 엔트리를 빠뜨린 후보는 당선될 수 없다.

split vote 는 election timeout 을 `[T, 2T]` 균등분포에서 매번 다시 뽑아 완화한다. 먼저 깬 노드가 표를 모으는 지연을 d 라 하면 다른 n-1 노드가 그 안에 같이 깨지 않을 확률이 대략 `(1 - d/T)^(n-1)` 이고, n=5·T=150ms·d=10ms 면 `≈ 0.75` 로 기대 라운드가 약 1.33 회다. 랜덤화 폭을 d 수준까지 줄이면 이 값이 0 으로 수렴해 split vote 가 반복된다. 논문의 요구 조건은 `broadcastTime ≪ electionTimeout ≪ MTBF`(0.5~20ms, 10~500ms)다.

여기에 **PreVote**(term 을 올리기 전 승산을 먼저 묻기)와 **CheckQuorum**(과반 응답을 못 받은 리더를 물러나게 하기)을 더하면 복귀 노드의 불필요한 재선출과 소수 측 리더의 잘못된 읽기를 함께 막는다.

## 3. 로그 복제: 일관성 검사와 nextIndex 역추적

AppendEntries 는 보낼 엔트리 앞의 `(prevLogIndex, prevLogTerm)` 을 실어 보내고 팔로워가 그 위치를 검사한다. 앞이 같음이 확인된 뒤를 같게 덮어쓰므로 성공 순간 prefix 가 리더와 같아지는데, 이것이 Log Matching 의 귀납 스텝이다.

```go
func (rf *Raft) AppendEntries(a *Args, r *Reply) {
    if a.Term < rf.currentTerm { r.Success = false; return }
    rf.becomeFollower(a.Term); rf.resetElectionTimer()

    if a.PrevLogIndex > rf.lastIndex() {                  // (1) 로그가 짧다
        r.ConflictIndex, r.ConflictTerm = rf.lastIndex()+1, -1; return
    }
    if t := rf.term(a.PrevLogIndex); t != a.PrevLogTerm { // (2) term 불일치
        i := a.PrevLogIndex
        for i > rf.snapshotIndex && rf.term(i-1) == t { i-- }
        r.ConflictTerm, r.ConflictIndex = t, i            // 그 term 의 첫 인덱스
        return
    }
    rf.truncateAndAppend(a.PrevLogIndex+1, a.Entries)     // (3) 충돌 지점 절단 후 append
    rf.commitIndex = min(a.LeaderCommit, a.PrevLogIndex+len(a.Entries))
    r.Success = true
}
```

절단은 **커밋되지 않은** 엔트리만 버리므로 Leader Append-Only 와 모순되지 않는다. 논문 기본 규칙은 거절 시 `nextIndex` 를 1씩 줄이는 것이라 1,000 엔트리 뒤처진 팔로워에는 RPC 가 1,000번 오가니, 위처럼 conflict 정보를 돌려받아 건너뛴다.

```go
if reply.ConflictTerm == -1 {                              // 팔로워 로그가 짧음
    rf.nextIndex[i] = reply.ConflictIndex
} else if last, ok := rf.lastIndexOfTerm(reply.ConflictTerm); ok {
    rf.nextIndex[i] = last + 1                             // 리더도 보유 → 그 뒤부터
} else {
    rf.nextIndex[i] = reply.ConflictIndex                  // 없는 term → 통째 건너뜀
}
```

왕복 횟수가 엔트리 수가 아니라 **불일치 구간의 term 개수**에 비례한다. 엔트리가 이미 압축됐다면 8절의 InstallSnapshot 으로 넘어간다.

## 4. Figure 8: 이전 term 엔트리를 카운팅만으로 커밋하면 안 되는 이유

"과반에 저장됐으니 커밋"은 **이전 term 엔트리에 대해 틀린다**. Figure 8 을 5노드로 따라가 보자.

1. **term 2** — S1 이 인덱스 2 에 엔트리(term 2)를 쓰고 S2 에만 복제한 뒤 죽는다.
2. **term 3** — S5 가 S3·S4 표로 당선된다(둘은 인덱스 2 가 비어 up-to-date 검사를 통과시킨다). S5 는 인덱스 2 에 자기 엔트리(term 3)를 쓰고 복제 전에 죽는다.
3. **term 4** — S1 이 복귀해 당선되고 term 2 엔트리를 S3 에 복제해 S1·S2·S3 과반이 된다. **여기서 커밋을 선언하면 사고가 난다.**
4. **term 5** — S1 이 죽고 S5 가 S2·S3·S4 표로 재당선될 수 있다(S5 의 마지막 term 3 이 S2·S3 의 2 를 이긴다). S5 가 인덱스 2 를 덮어쓰면 커밋됐다던 값이 사라진다.

**과반 저장이 곧 영속성은 아니다.** 그 엔트리 없는 소수가 더 높은 term 으로 이기면 덮어써진다.

> 리더는 **자기 현재 term 의 엔트리**가 과반에 복제됐을 때만 커밋을 선언한다. 이전 term 엔트리는 카운팅하지 않고, 현재 term 엔트리가 커밋되는 순간 Log Matching 에 의해 앞선 전부와 **함께** 간접 커밋된다.

3단계에서 S1 이 term 4 엔트리를 인덱스 3 에 써서 과반에 복제하면 그 과반은 인덱스 2 도 전부 갖고, S5 는 term 5 선거에서 이길 수 없다. 부작용은 새 리더가 자기 term 엔트리를 커밋하기 전까지 **`commitIndex` 를 신뢰할 수 없다**는 것이라, etcd-raft 를 비롯한 구현은 당선 직후 **no-op 엔트리**를 붙이고 그것이 커밋되기 전까지 ReadIndex 응답을 보류한다.

## 5. 멤버십 변경: joint consensus, 단일 노드 변경, learner

위험은 구 설정 과반과 신 설정 과반이 겹치지 않아 **같은 term 에 리더가 둘 생기는 것**이다. 일부가 {A,B,C} 를, 일부가 {A,B,C,D,E} 를 믿으면 {A,B} 와 {C,D,E} 가 각자 리더를 뽑는다.

**joint consensus** 는 중간에 `C_old,new` 를 두고 모든 결정이 **구 과반과 신 과반을 동시에** 만족하게 한다. 이 엔트리는 append 즉시 효력이 발생하고 커밋된 뒤에야 `C_new` 로 넘어가므로 분열이 차단되지만 합의 라운드가 두 번 든다. **단일 노드 변경**(박사논문 권장안)은 한 번에 한 노드만 바꾸는데, 설정이 한 노드만 다르면 구·신 과반이 반드시 겹치므로(3→4 노드면 과반 2→3, 총원 4 에서 2+3 > 4) 설정 엔트리 하나로 끝난다.

다만 2015년에 허점이 지적됐다. 같은 term 안에서는 연속한 두 설정이 한 노드만 차이 남을 리더가 보장하지만, **term 경계를 넘으면** 서로 다른 리더의 설정 변경이 경쟁하며 겹치지 않는 과반이 생길 수 있다. Ongaro 의 수정은 **리더가 자기 현재 term 엔트리를 하나 커밋하기 전에는 새 설정 엔트리를 append 할 수 없다**는 한 줄이다(확장판의 joint consensus 는 이 버그와 무관하다).

새 노드를 곧바로 투표 멤버로 넣으면 따라잡는 동안 과반 계산에 포함돼 커밋 지연이 오르므로 **learner**(non-voting)로 먼저 붙인다. 로그는 받되 과반 계산에서 빠지고, `matchIndex` 가 근접하면 승격한다.

```bash
etcdctl member add node-new --learner --peer-urls=https://10.0.3.14:2380
etcdctl endpoint status -w table   # RAFT APPLIED INDEX 격차 확인
etcdctl member promote 9e3f4a1b2c5d6e7f
# 교체는 제거부터: 3노드에 먼저 더하면 과반이 3 이 돼 내결함성이 줄어든다
```

## 6. 선형화 가능 읽기 세 가지 구현

쓰기는 로그를 타니 자연히 선형화 가능하지만, 분단돼 이미 밀려난 구 리더도 메모리에서 낡은 값을 읽어줄 수 있다.

```java
public byte[] linearizableRead(byte[] key) throws NotLeaderException {
    if (!raft.hasCommittedEntryInCurrentTerm()) { raft.waitForNoOpCommit(); }
    long readIndex = raft.getCommitIndex();
    if (!raft.confirmLeadershipByHeartbeat()) { throw new NotLeaderException(); }
    raft.getStateMachine().awaitApplied(readIndex);
    return raft.getStateMachine().get(key);
}
```

**(a) ReadIndex.** heartbeat 확인이 안전성의 전부다. 과반이 지금 나를 리더로 인정하면, 다른 과반이 더 높은 term 리더를 뽑아 새 값을 커밋했을 수 없기 때문이다. 비용은 **1 RTT** 뿐이고 **fsync 는 없으며** 동시 읽기를 한 라운드로 묶을 수 있다(동일 AZ 0.3~1ms, 리전 간 수십 ms).

**(b) Lease read.** 과반 heartbeat 응답 시각 `t0` 부터 `t0 + lease` 까지는 heartbeat 없이 로컬에서 읽는다. 팔로워가 election timeout 동안 기다려주므로 그 전에는 새 리더가 생길 수 없다는 논증인데, **벽시계 가정 위에 있다**. 그래서 드리프트 ε 을 빼 `lease ≤ election_timeout × (1 - ε)` 로 잡는다. TiKV 의 `raft-store-max-leader-lease` 기본 `9s` 가 election timeout 10s 에서 1초를 남긴 예다. 지연은 가장 낮지만 VM 정지·긴 GC pause·NTP step 이 가정을 깨서, 12초 멈췄다 깨어난 리더는 lease 가 유효하다고 착각한다.

**(c) 로그에 no-op 쓰기.** 읽기를 로그 엔트리로 만들어 합의를 돌린다. 시계 가정이 없는 대신 **fsync 포함 완전한 합의 라운드**를 치러 ReadIndex 대비 수 배 느리다. 기본값은 ReadIndex 다.

## 7. 의도적인 follower read / stale read 의 계약

**선형화를 유지하는 follower read** 는 팔로워가 리더에게 ReadIndex 숫자만 받아(1 RTT) 자기 `appliedIndex` 가 그 값을 넘을 때까지 기다린 뒤 **데이터는 로컬에서** 읽어, 큰 스캔에서 리더 대역폭을 아낀다.

**stale read** 는 그 1 RTT 마저 포기한다. 남는 보장은 **각 복제본이 커밋된 로그의 어떤 prefix 를 반영한다**는 것이라, 읽은 값은 언젠가 실제로 커밋됐던 상태이며 한 복제본에 고정(session pinning)하면 단조 읽기가 성립한다. 포기하는 것은 최신성과 복제본 간 단조성이라, A 에서 x=5 를 읽고 뒤처진 B 로 가면 x=3 이 나온다. Jepsen 의 etcd 3.4.3 분석에서도 기본 읽기는 strict serializable 로 관측된 반면 `serializable` 플래그를 켠 읽기에서는 문서대로 stale read 가 나왔다.

따라서 읽은 값으로 분기해 쓰는 경로는 ReadIndex 나 트랜잭션(`Txn` + `mod_revision` 비교)으로 처리하고, 수백 ms 지연이 무방한 조회만 stale read 로 내린다.

## 8. 로그 압축: 스냅샷과 InstallSnapshot 의 상호작용

스냅샷에는 `lastIncludedIndex`, `lastIncludedTerm`, 그리고 **그 시점의 멤버십**이 반드시 들어간다. 재시작한 노드가 설정을 모르면 누구에게 투표를 요청할지조차 모르기 때문이다. 보낼 `nextIndex` 가 압축된 구간을 가리키면 `InstallSnapshot` 으로 전환하고 청크로 쪼개 보낸다. 팔로워는 `lastIncludedIndex/Term` 이 자기 로그와 일치하면 **앞부분만 버리고 뒤는 유지**하고, 불일치하면 로그 전체를 버린 뒤 스냅샷으로 교체한다.

진행 중 복제와의 충돌은 셋이다. **대역폭 경합**(스냅샷 전송이 정상 복제와 링크를 나눠 쓰면 커밋 지연이 튀므로 TiKV `snap-max-write-bytes-per-sec` 로 제한), **apply 중 읽기 정지**(적용 중인 노드는 follower read 라우팅에서 뺀다), **주기의 트레이드오프**(자주 찍으면 복구는 빠르나 I/O 가 반복되고 드물면 재생할 WAL 이 길어진다 — etcd `--snapshot-count` 기본 100,000).

## 9. 운영 튜닝 수치와 흔한 오해 정정

etcd 기본값은 `--heartbeat-interval=100ms`, `--election-timeout=1000ms` 다. 문서 기준은 heartbeat 를 멤버 간 **평균 RTT 의 0.5~1.5배**, election timeout 을 **ping 의 최소 10배** 이상으로 두는 것이고, 값은 전 멤버가 같아야 한다. WAN 배치로 RTT 가 40ms 면 heartbeat 40~60ms, election timeout 400~600ms 이상이어야 지터를 장애로 오인한 재선출이 사라지고, 올린 만큼 감지·복구는 느려진다(상한 권고 50,000ms).

TiKV 는 tick 기반이라 `raft-base-tick-interval` `1s`, `raft-heartbeat-ticks` `2`, `raft-election-timeout-ticks` `10` 으로 heartbeat 2초, election timeout 10초다. 노드 하나가 수만 개 Region 을 돌려 heartbeat 총량을 줄여야 해서 etcd 보다 크다. `raft-min/max-election-timeout-ticks` 기본 `0` 은 `[10, 20]` tick 을 뜻해 2절의 `[T, 2T]` 그대로다.

**fsync 가 커밋 지연의 지배 요인이다.** 커밋 지연은 대략 `리더 fsync + RTT + 과반 팔로워 fsync 최댓값` 이라, NVMe 0.1~1ms 와 회전 디스크 5~10ms 의 차이가 그대로 반영된다. etcd 문서 권장은 `etcd_disk_wal_fsync_duration_seconds` p99 10ms 미만, `etcd_disk_backend_commit_duration_seconds` p99 25ms 미만이며, 넘으면 heartbeat 처리까지 밀려 리더가 튄다.

**"Raft 는 리더가 죽어도 데이터가 유실되지 않는다."** 정확히는 *커밋된* 엔트리에 한정된다. 성공 응답은 과반 기록 뒤에 나가므로 응답받은 쓰기는 보존되고, 리더 로그에만 있던 엔트리는 절단되지만 애초에 응답이 나가지 않았다. 예외는 fsync 를 껐을 때(TiKV `sync-log=false`)의 동시 정전, 그리고 과반을 영구히 잃어 단일 노드 강제 재구성을 타야 하는 경우다.

**"리더에서 읽으면 최신이다."** 6절의 확인 절차가 없으면 거짓이다. **"노드를 늘리면 쓰기가 빨라진다."** 반대로 과반이 커져 쓰기 지연은 오르며, 증설은 내결함성과 읽기 확장에만 기여한다.

## 참고

- In Search of an Understandable Consensus Algorithm (Extended Version) — https://raft.github.io/raft.pdf
- Consensus: Bridging Theory and Practice (Ongaro 박사학위 논문) — https://github.com/ongardie/dissertation
- Safety of Raft single-server membership changes — https://gist.github.com/ongardie/a11f32b70581e20d6bcd
- etcd Tuning — https://etcd.io/docs/v3.4/tuning/
- TiKV Raftstore Config — https://tikv.org/docs/4.0/tasks/configure/raftstore/
- Jepsen: etcd 3.4.3 — https://jepsen.io/analyses/etcd-3.4.3
