Notion 원본: https://www.notion.so/3565a06fd6d38149b5d9e2db579cb3c3

# Merkle Tree와 Anti-Entropy — 분산 데이터 동기화 메커니즘

> 2026-05-04 신규 주제 · 확장 대상: CRDT 구현, Vector Clock과 HLC

## 학습 목표

- Merkle Tree 의 구성 방식(이진/n-진 해시 트리)과 *부분 트리만 비교해도 차이가 있는 leaf 를 빠르게 찾는 알고리즘* 을 단계별로 정리한다
- Anti-Entropy 가 *replica 간 일관성 회복* 의 어떤 종류의 비일관성(node failure, network partition, write loss)에 효과적인지 분류한다
- Cassandra `nodetool repair` 와 DynamoDB / Riak 의 hinted handoff·read repair·active anti-entropy 가 같은 문제를 다른 비용 구조로 푸는 방식을 비교한다
- Merkle Tree 깊이와 leaf 단위 데이터 양(token range chunk size)이 *비교 비용* 과 *전송 비용* 에 어떻게 트레이드오프되는지 정량적으로 분석한다

## 1. Merkle Tree 의 구조와 핵심 성질

Merkle Tree (해시 트리)는 *leaf 가 데이터 블록의 해시* 이고 *내부 노드가 자식 해시들의 결합 해시* 인 트리다. 이진 트리가 가장 흔하지만 n-진(보통 16진수 nibble 트리)도 자주 쓰인다.

```
                root = H( H1 || H2 )
               /                    \
       H1 = H(L1||L2)        H2 = H(L3||L4)
        /         \           /         \
     L1=H(b1)  L2=H(b2)   L3=H(b3)  L4=H(b4)
     b1         b2         b3         b4   ← 데이터 블록
```

핵심 성질 두 가지.

(1) *Tamper-evident*: 어떤 데이터 블록 하나가 1 비트라도 바뀌면 그 블록의 leaf 해시가 바뀌고, 그 변화가 root 까지 전파된다. root 만 비교하면 *전체 블록 셋이 동일한지* 한 번에 알 수 있다.

(2) *Logarithmic comparison*: 두 트리의 root 가 다르면, 자식 노드들을 비교해 다른 쪽 서브트리만 follow 한다. 다른 leaf 의 개수를 *k*, 전체 leaf 개수를 *n* 이라 할 때 비교 비용은 O(k log n) 으로 묶인다. 즉 *변경 비율이 작으면 거의 무료* 다.

이 두 성질이 분산 시스템의 anti-entropy 에서 결정적이다.

## 2. Anti-Entropy 의 정의와 보완 관계

분산 데이터 시스템에서 *replica 간 데이터 차이를 능동적으로 회복* 하는 모든 메커니즘을 Anti-Entropy 라고 부른다. 일반적으로 다음 세 메커니즘과 보완 관계를 이룬다.

| 메커니즘 | 회복 시점 | 비용 | 한계 |
|---|---|---|---|
| Hinted Handoff | 다운된 노드 복귀 직후 | 메모리 큐 + 짧은 ttl | 큐가 만료/소실되면 영구 손실 |
| Read Repair | 읽기 시점 | 읽기 latency 증가 | 한 번도 안 읽은 데이터는 영원히 mismatch |
| Active Anti-Entropy | 주기적 백그라운드 | 전체 데이터 비교 | CPU/네트워크 부담 |

세 가지를 다 결합해야 *모든* 비일관성을 회복할 수 있다. Hinted Handoff 만으로는 큐가 차서 hint 를 잃는 경우가 있고, Read Repair 만으로는 *읽지 않는 데이터* 가 영원히 어긋난다. Active Anti-Entropy 가 마지막 안전망이다.

Cassandra 의 `nodetool repair` 가 바로 이 active anti-entropy 다. 내부적으로 Merkle Tree 를 사용한다.

## 3. Cassandra repair 의 단계별 흐름

`nodetool repair` 가 한 키스페이스를 처리할 때의 흐름은 다음과 같다.

```
1. token range partition
   각 노드가 책임지는 token range 를 작은 sub-range 로 쪼갠다 (보통 32K leaf).

2. validation compaction
   각 replica 가 자기 데이터를 스캔하면서 sub-range 별 leaf 해시 계산.
   결과를 메모리에 Merkle Tree 형태로 보관.

3. tree exchange
   조정자(coordinator) 가 모든 replica 에게 트리를 요청, 받은 트리들을 비교.

4. diff computation
   root 부터 DFS 로 내려가며 mismatch 인 sub-range 를 식별.

5. streaming
   mismatch sub-range 의 데이터를 SSTable 단위로 stream 해서 보강.
```

핵심 비용 두 가지: *validation compaction* 의 디스크 IO 와 *streaming* 의 네트워크 비용. 전자는 트리 깊이로 어느 정도 제어할 수 있고, 후자는 mismatch 비율에 비례한다.

`nodetool repair -pr` 옵션은 *primary range 만* repair 한다. 모든 노드가 자기 primary range 만 책임지고 처리하면 클러스터 전체가 정확히 한 번씩 repair 된다. 작은 cluster 에서 가장 효율적인 운영 패턴이다.

## 4. Merkle Tree 깊이의 트레이드오프

Cassandra 의 기본 트리 깊이는 15(2^15 = 32768 leaf)이다. 이 값이 의미하는 것을 분해해 보자.

| 트리 깊이 | leaf 개수 | leaf 당 평균 키 수(1억 키 기준) | 단일 mismatch 시 stream 양 |
|---|---|---|---|
| 10 | 1,024 | 약 10만 | 큼 (1/1024 = 0.1% 의 데이터 전송) |
| 15 | 32,768 | 약 3,000 | 작음 (0.003%) |
| 18 | 262,144 | 약 380 | 매우 작음 (0.0004%) |
| 20 | 1,048,576 | 약 95 | 거의 정확 |

깊이가 깊을수록 *mismatch 시 streaming 양은 줄지만 트리 자체의 메모리 비용 + 비교 비용은 늘어난다*. 16바이트 해시 기준으로 깊이 15 의 트리는 32768 × 16 = 512KB 가량이고, 깊이 20 이면 16MB 다. 한 번의 repair 가 다루는 모든 sub-range 트리를 동시에 보관해야 하므로 이 메모리는 합산된다.

운영적 결정은 *데이터의 평균 변경 비율* 이 기준이다. 변경이 적고 mismatch 가 거의 없을 거라 예상되는 cold data 는 얕은 트리(깊이 12-13)로 비교 자체의 부담을 줄이고, hot data 는 깊은 트리로 streaming 양을 줄인다.

## 5. DynamoDB 와 Riak 의 변형

DynamoDB 의 active anti-entropy 메커니즘은 공식적으로 자세히 공개되지 않았지만 Werner Vogels 의 Dynamo 논문과 Riak (Dynamo 의 오픈소스 후예) 의 구현을 통해 패턴을 파악할 수 있다.

Riak 은 *partition tree*(Cassandra 의 트리와 비슷) + *bucket tree* 의 두 단계를 사용한다. 그리고 트리를 *영구 디스크에 저장* 한다. 이게 Cassandra 와의 큰 차이다. 영구 트리는 매번 validation compaction 을 다시 돌릴 필요 없이 *write 가 일어날 때 incremental 하게 갱신* 된다.

```
write(key, value)
  → partition: vnode 에 저장
  → tree update: hash(key) 의 leaf path 를 따라 부모까지 hash 갱신
```

이 방식의 장점은 anti-entropy 가 거의 무료다. 단점은 *write path 에 트리 갱신 비용이 추가* 되고, 트리가 망가졌을 때 (디스크 손상, partial write) 전체 재구성이 필요하다.

DynamoDB 는 클라이언트에 노출되지 않는 백그라운드로 anti-entropy 를 돌린다. AWS 가 직접 운영하므로 사용자가 신경 쓰지 않아도 된다는 점이 SaaS 의 장점이다. 비용은 *consumed capacity* 에 어느 정도 반영된다.

## 6. Git 의 packfile 과 Merkle Tree

Merkle Tree 는 분산 DB 에만 쓰이는 게 아니다. Git 의 object 모델 자체가 Merkle Tree 다. 각 commit 이 tree object 를 가리키고, tree object 는 다른 tree 와 blob 의 SHA-1(또는 SHA-256) 해시를 담는다.

```
commit  ──► tree_root
            ├─ blob:src/main.ts (sha)
            └─ tree:src/lib
                 ├─ blob:a.ts (sha)
                 └─ blob:b.ts (sha)
```

`git fetch` 가 *원격 저장소와 차이* 를 파악할 때 Merkle 비교를 정확히 사용한다. 로컬에 없는 commit 의 tree 부터 follow 하면서, 이미 가진 SHA 의 subtree 는 skip 한다. 이 덕분에 거대한 monorepo 에서도 fetch 가 *바뀐 일부* 만 가져온다.

Cassandra 의 anti-entropy 와 Git fetch 가 본질적으로 같은 알고리즘인 점은 우연이 아니다. *해시로 데이터를 식별하는 모든 시스템* 에서 변경 부분만 효율적으로 식별하고 싶으면 Merkle Tree 로 자연스럽게 도달한다.

## 7. 간단한 Java 구현으로 직관 잡기

작은 Merkle Tree 와 비교 알고리즘을 100 줄 안쪽으로 구현해 두면 직관이 굳는다.

```java
import java.security.MessageDigest;
import java.util.*;

public class MerkleTree {

  public static class Node {
    final byte[] hash;
    final Node left, right;
    Node(byte[] h) { this(h, null, null); }
    Node(byte[] h, Node l, Node r) { hash = h; left = l; right = r; }
    boolean isLeaf() { return left == null && right == null; }
  }

  public static Node build(List<byte[]> blocks) {
    List<Node> level = new ArrayList<>();
    for (byte[] b : blocks) level.add(new Node(sha256(b)));
    while (level.size() > 1) {
      List<Node> next = new ArrayList<>();
      for (int i = 0; i < level.size(); i += 2) {
        Node l = level.get(i);
        Node r = (i + 1 < level.size()) ? level.get(i + 1) : l;
        next.add(new Node(sha256Concat(l.hash, r.hash), l, r));
      }
      level = next;
    }
    return level.get(0);
  }

  public static List<Integer> diff(Node a, Node b, int idx, int leafCount) {
    if (Arrays.equals(a.hash, b.hash)) return List.of();
    if (a.isLeaf() && b.isLeaf()) return List.of(idx);
    int half = leafCount / 2;
    List<Integer> out = new ArrayList<>();
    out.addAll(diff(a.left, b.left, idx, half));
    out.addAll(diff(a.right, b.right, idx + half, leafCount - half));
    return out;
  }

  static byte[] sha256(byte[] in) {
    try { return MessageDigest.getInstance("SHA-256").digest(in); }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  static byte[] sha256Concat(byte[] a, byte[] b) {
    byte[] x = new byte[a.length + b.length];
    System.arraycopy(a, 0, x, 0, a.length);
    System.arraycopy(b, 0, x, a.length, b.length);
    return sha256(x);
  }
}
```

`diff` 함수가 핵심이다. 두 트리의 root 부터 시작해서 *자식 해시가 같으면 그 서브트리는 동일하다* 는 가정으로 skip 한다. 다른 서브트리만 follow 해서 leaf 인덱스를 수집한다. 1억 leaf 중 100개가 다르면 비교 호출은 약 100 × log2(10^8) ≈ 2700 회 안에 끝난다.

`build` 함수가 leaf 개수가 홀수일 때 마지막 노드를 *자기 자신과 결합* 하는 부분에 주의해야 한다. Bitcoin 의 Merkle Tree 가 이 처리 때문에 *동일 leaf 두 개를 의도적으로 만들 수 있는 취약점* (CVE-2012-2459)을 가졌다는 일화가 유명하다. 안전한 구현은 leaf 개수를 홀수일 때 *명시적 padding 마커* 로 채우거나, internal/leaf 노드에 *서로 다른 prefix byte* 를 넣어 충돌을 방지한다.

## 8. 운영 관점의 anti-entropy 일정 잡기

Cassandra 운영팀이 흔히 쓰는 권장 일정은 *gc_grace_seconds 안에 모든 keyspace 를 한 번 이상 repair* 다. 기본값 10일을 넘기면 *tombstone 회수* 가 정확히 동작하지 않아 *되살아나는 zombie 데이터* 가 생긴다.

| keyspace 크기 | 빈도 | 옵션 |
|---|---|---|
| < 100GB | 주 1회 전체 | `nodetool repair -pr` 야간 cron |
| 100GB ~ 1TB | 일별 incremental + 주별 full | reaper 같은 tool 권장 |
| > 1TB | 상시 incremental, 주별 sub-range | 전용 repair 노드 운영 권장 |

`reaper`(Spotify originated) 같은 외부 도구가 *작은 sub-range 단위* 로 repair 를 잘게 쪼개 줘 한 번에 전체를 도는 부담을 분산시킨다. AWS Keyspaces 같은 매니지드 서비스는 이 부분을 사용자에게 노출하지 않는다.

## 참고

- Bitcoin Whitepaper — Merkle Tree application: https://bitcoin.org/bitcoin.pdf
- Amazon Dynamo Paper (2007): https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf
- Cassandra Documentation — Repair: https://cassandra.apache.org/doc/latest/cassandra/operating/repair.html
- Riak Documentation — Active Anti-Entropy: https://docs.riak.com/riak/kv/latest/learn/concepts/active-anti-entropy/
- Pro Git Book — Git Internals (Merkle DAG): https://git-scm.com/book/en/v2/Git-Internals-Git-Objects
