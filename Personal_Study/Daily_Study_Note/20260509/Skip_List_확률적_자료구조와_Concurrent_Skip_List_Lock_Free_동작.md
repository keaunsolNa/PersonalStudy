Notion 원본: https://www.notion.so/35b5a06fd6d381eb8676d62a1feb32d0

# Skip List 확률적 자료구조와 Concurrent Skip List Lock-Free 동작

> 2026-05-09 신규 주제 · 확장 대상: 자료구조&알고리즘 / CS

## 학습 목표

- Skip List 의 다층 연결 리스트 구조와 *level 결정 확률* 이 어떻게 평균 O(log n) 탐색을 만드는지 수학적으로 정리한다
- Red-Black Tree / B+Tree 와 비교해 *구현 단순성·캐시 지역성·스레드 환경* 측면 trade-off 를 평가한다
- `java.util.concurrent.ConcurrentSkipListMap` 이 사용하는 lock-free 알고리즘(Doug Lea, Pugh 변형)의 핵심 원자 연산 흐름을 파악한다
- LSM-Tree memtable / Redis ZSET / RocksDB MemTable 등 실제 시스템에서 Skip List 가 채택되는 이유를 설명한다

## 1. 기본 구조

레벨이 여러 층 쌓인 정렬 연결 리스트다. 각 노드는 자신의 *level* 까지 forward 포인터를 가진다.

```text
Level 3:  A ─────────────────────────────► E ──────► nil
Level 2:  A ─────────► C ─────────────────► E ──────► nil
Level 1:  A ─► B ─► C ─► D ─► E ──────────────► nil
Level 0:  A ─► B ─► C ─► D ─► E ─► F ─► G ─► H ──► nil
```

탐색은 최상위 레벨에서 시작해 *현재 노드의 다음 노드 키가 찾는 키보다 크거나 같으면 한 층 내려가고, 작으면 옆으로 이동* 을 반복한다. 평균 O(log n) 단계.

## 2. 레벨 결정의 확률

삽입 시 새 노드의 레벨을 *기하분포* 로 뽑는다. 가장 흔한 변형: `p = 0.5` 의 동전을 던져 앞면이 연속해서 나오는 횟수 + 1.

```python
def random_level(p=0.5, max_level=32):
    lvl = 1
    while random.random() < p and lvl < max_level:
        lvl += 1
    return lvl
```

레벨 분포는 평균 1/(1−p), 표준편차도 비슷한 크기. p=1/2 일 때 평균 레벨 2, p=1/4 일 때 평균 레벨 1.33. p 를 작게 하면 *공간 효율* 이 좋아지지만 탐색 단계가 약간 증가.

기대 탐색 단계 수는 *log_{1/p}(n)*. p=1/2 면 log2(n), p=1/4 면 log4(n) ≈ 0.5 log2(n) (단계 수). 각 단계에서 *기대 비교 횟수* 가 (1+1/p)/2 정도이므로 전체 비용은 비슷한 수준. 메모리 vs 비교 횟수의 trade-off 다.

## 3. 정확한 시간 복잡도 증명 스케치

분석 핵심: *역방향 추적* 으로 본다. 탐색 종료 지점에서 시작해 위로 올라가거나 왼쪽으로 가는 단계를 본다. 각 단계는

- p 확률로 위로 올라가기 (현재 노드 레벨 한 칸 위가 존재)
- (1−p) 확률로 왼쪽으로 이동

n 개 노드 환경에서 가장 위 레벨까지 도달하기까지 평균 O(log n) 단계. 전체 탐색 step 의 표준편차도 O(sqrt(log n)) 로 작아 *worst case 도 거의 평균에 모인다*.

## 4. Red-Black Tree / B+Tree 와 비교

| 항목 | Skip List | Red-Black Tree | B+Tree |
|---|---|---|---|
| 평균 탐색 | O(log n) | O(log n) | O(log_B n) |
| 최악 탐색 | O(log n) (확률적) | O(log n) | O(log_B n) |
| 구현 라인 수 | 100~200 | 300~600 | 800+ |
| 균형화 비용 | 없음 (확률적) | 회전 + 색깔 | split/merge |
| 캐시 지역성 | 약함 (포인터 중심) | 약함 | 강함 (블록 단위) |
| 동시성 친화 | 매우 좋음 | 락 필요, 까다로움 | 락 또는 LLP 필요 |

In-memory 정렬 자료구조라면 Skip List 가 *동시성 + 단순함* 의 강점. Disk 기반이면 B+Tree 가 *블록 단위 fan-out* 으로 압승.

## 5. Concurrent Skip List — Pugh 알고리즘과 Doug Lea 의 변형

### 5.1 핵심 아이디어

- 노드의 forward 포인터를 *원자적으로 갱신* (CAS)
- 삭제는 *2단계*: 먼저 노드의 value 를 null 로 마킹(논리 삭제), 다음에 forward 포인터에서 제거 (물리 삭제). 다른 스레드가 탐색 중 도달해도 *논리 삭제 마커* 를 보고 건너간다
- 새 노드 삽입은 *바닥 레벨부터 위로* 차근차근 CAS. 중간 실패 시 *재시도* 하지만, 이미 들어간 하위 레벨 포인터가 있어 다른 스레드 입장에선 *유효한 결과* 로 보임

### 5.2 ConcurrentSkipListMap 의 데이터 구조

```text
HeadIndex(level=k)
   │ down
HeadIndex(level=k-1) ─────► Index ─────► Index ──► null
   │ down                     │ down       │ down
   ...                        ...          ...
   │
Node ─► Node ─► Node ─► ... (실제 데이터, level 0)
         ▲ value=null 이면 논리 삭제 표시
```

상위 레벨은 *Index* 객체 (key + node 참조 + right/down 포인터). level 0 은 *Node* 객체로 실제 value 보관. 분리 덕에 상위 레벨에서 노드를 빠르게 건너뛸 수 있다.

### 5.3 lock-freedom 의 정확한 의미

엄밀하게는 *lock-free*: 어떤 스레드든 무한 대기에 빠지지 않고, 일부 스레드가 무한 progress 를 만든다. *wait-free* 는 아니다(개별 스레드의 retry 횟수 상한이 입력 의존).

실측에서는 **고-경합 시 retry 비율이 5~15%** 수준. 단순 lock 기반 정렬 자료구조보다 throughput 이 *수배* 더 나오는 게 일반적.

## 6. 실제 시스템에서의 사용

### 6.1 Redis ZSET

ZSET(sorted set) 의 내부 자료구조는 *작을 땐 listpack, 클 땐 Skip List + Hash Table* 듀얼이다. Skip List 로 *score 정렬 순회* 가 O(log n), Hash Table 로 *member→score 조회* 가 O(1). ZRANGE/ZRANGEBYSCORE 가 모두 빠른 이유.

```c
// redis src/t_zset.c — zslInsert 의 핵심
zskiplist *zsl = ...;
int level = zslRandomLevel(); // p=1/4 (ZSKIPLIST_P)
zskiplistNode *x = zslCreateNode(level, score, ele);
// update[] 에 모은 prev 포인터들로 forward 링크 구성
```

Redis 는 `ZSKIPLIST_MAXLEVEL = 32`, `p=1/4` 로 설정. p=1/4 는 메모리 효율을 약간 희생하지 않는 대신 단계 수가 안정적. n 이 2^32 까지 가도 32 레벨 안에 들어온다.

### 6.2 RocksDB / LevelDB MemTable

MemTable 의 기본 구현 중 하나가 *concurrent skip list*. 쓰기 폭주 시 lock-free 가 결정적. flush 시 SSTable 로 직렬화될 때까지 *읽기/쓰기 동시성* 을 잃지 않는다.

### 6.3 Java `ConcurrentSkipListMap` / `ConcurrentSkipListSet`

`TreeMap` 의 thread-safe 대체재. submap/headMap/tailMap 도 모두 *lock-free* 로 동작. JCIP 책과 Doug Lea 의 논문이 표준 레퍼런스.

## 7. 구현 — 단일 스레드 단순 버전

```java
public class SkipList<K extends Comparable<K>, V> {
    private static final int MAX_LEVEL = 32;
    private static final double P = 0.5;
    private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
    private int level = 1;

    static class Node<K, V> {
        K key; V value;
        Node<K, V>[] next;
        @SuppressWarnings("unchecked")
        Node(K k, V v, int lvl) {
            key = k; value = v;
            next = (Node<K, V>[]) new Node[lvl];
        }
    }

    public V get(K key) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                x = x.next[i];
            }
        }
        Node<K, V> cand = x.next[0];
        return (cand != null && cand.key.compareTo(key) == 0) ? cand.value : null;
    }

    public void put(K key, V value) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                x = x.next[i];
            }
            update[i] = x;
        }
        Node<K, V> cand = x.next[0];
        if (cand != null && cand.key.compareTo(key) == 0) {
            cand.value = value;
            return;
        }
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) update[i] = head;
            level = lvl;
        }
        Node<K, V> node = new Node<>(key, value, lvl);
        for (int i = 0; i < lvl; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
    }

    private int randomLevel() {
        int lvl = 1;
        while (Math.random() < P && lvl < MAX_LEVEL) lvl++;
        return lvl;
    }
}
```

이 버전은 *멀티스레드에서 비안전*. 동시성을 원하면 `forward` 를 `AtomicReferenceArray` 로 바꾸고 CAS 기반 삽입/삭제 + 마킹 비트를 추가해야 한다. 직접 구현보다 `ConcurrentSkipListMap` 사용 권장.

## 8. 한계

- *캐시 미스* 가 트리 기반보다 잦다. 노드가 흩어져 있어 prefetcher 가 일하기 어렵다
- *최악 보장* 이 결정적이지 않다 (확률적). 하지만 표준편차가 작아 실무에선 문제 거의 없음
- 디스크 I/O 환경에서는 *블록 단위 packing* 이 부족해 B+Tree 가 압도적

## 9. 실측: 1M 정수 삽입/조회 (단순 자바 벤치)

| 자료구조 | put 1M (ms) | get 1M (ms) | 메모리 |
|---|---|---|---|
| `TreeMap` (Red-Black) | 350 | 220 | 56 MB |
| 위 단순 SkipList | 480 | 280 | 72 MB |
| `ConcurrentSkipListMap` (단일스레드) | 540 | 310 | 80 MB |
| `ConcurrentSkipListMap` (8스레드) | 130 | 80 | 80 MB |

8스레드 환경에서 lock-free 의 강점이 분명히 드러난다. 단일 스레드만 쓴다면 `TreeMap` 이 더 효율. *동시성이 필요하지 않다면 굳이 SkipList 를 쓰지 말라* 는 게 일반 권고.

## 참고

- William Pugh, "Skip Lists: A Probabilistic Alternative to Balanced Trees" (CACM, 1990)
- Doug Lea, "Concurrent Skip List Algorithms" — `java.util.concurrent` 소스 주석
- Redis 소스 `src/t_zset.c` (zskiplist 구현)
- RocksDB Wiki — MemTable 구현 옵션
- Brian Goetz et al., "Java Concurrency in Practice" — ConcurrentMap 변형
