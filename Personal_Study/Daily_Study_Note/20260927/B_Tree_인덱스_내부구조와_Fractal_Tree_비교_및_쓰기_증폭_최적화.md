Notion 원본: https://www.notion.so/3e85a06fd6d381cda594dd86bf67ddf9

# B+Tree 인덱스 내부구조와 Fractal Tree(Bε-tree) 비교 및 쓰기 증폭 최적화

> 2026-09-27 신규 주제 · 확장 대상: 자료구조&알고리즘, 최적화 기본

## 학습 목표

- 리프 연결 리스트와 페이지 split/merge 알고리즘을 코드 수준으로 재구성한다
- B+Tree의 쓰기 증폭 발생 지점(랜덤 삽입 캐스케이드)을 I/O 비용 관점에서 도출한다
- Bε-tree의 버퍼링 구조와 파라미터 ε의 읽기/쓰기 트레이드오프를 수식으로 설명한다
- InnoDB/Oracle이 B+Tree를 유지하는 이유를 LSM/Fractal Tree와의 삼각 비교로 논증한다

## 1. B+Tree 물리 구조 심화: 리프 연결 리스트와 즉시 재구조화

일반적인 B+Tree 설명은 "리프끼리 연결 리스트로 이어져 있다"는 수준에서 멈추는 경우가 많다. 실무에서 중요한 것은 이 연결 리스트가 range scan을 어떻게 최적화하는지, 삭제 시 왜 tombstone 없이 즉시 물리적 재구조화를 선택하는지다.

InnoDB의 클러스터드 인덱스 리프 페이지는 doubly linked list로 연결되어 있다(`FIL_PAGE_PREV`, `FIL_PAGE_NEXT`). `WHERE a BETWEEN x AND y` 같은 range scan에서 옵티마이저는 루트부터 내려가 시작 리프 하나만 탐색(O(log_B N))한 뒤 트리를 타지 않고 연결 리스트만 순회한다. B+Tree가 정렬 스캔에 유리한 근본 이유이며, 여러 SSTable을 merge-iterate해야 하는 LSM-Tree가 range scan에서 상대적으로 불리한 이유이기도 하다.

실무적으로 자주 오해되는 지점은 삭제 처리 방식이다. LSM-Tree 계열(RocksDB, Cassandra)은 삭제를 tombstone으로 남기고 나중 compaction 때 제거하지만, B+Tree는 삭제 즉시 레코드를 물리적으로 제거하고 필요하면 그 자리에서 재분배(redistribution) 또는 병합(merge)을 수행한다. 이는 "쓰기를 지연시켜 배치 처리하느냐 vs 매 연산마다 구조적 정합성을 즉시 유지하느냐"라는 설계 철학의 축을 가른다. B+Tree는 read-optimized 구조라 트리가 항상 "깨끗한" 상태여야 하고, 그 대가로 삭제·삽입 시점의 즉시 비용을 감수한다.

```sql
-- 리프 페이지 순서와 논리적 키 순서 일치 확인 (FIL_PAGE_PREV/NEXT 체인)
SELECT INDEX_NAME, PAGE_NO
FROM INFORMATION_SCHEMA.INNODB_BUFFER_PAGE
WHERE TABLE_NAME = "`test`.`orders`" AND PAGE_TYPE = 'INDEX'
ORDER BY PAGE_NO;
```

## 2. 페이지 분할/병합 알고리즘과 재분배 최적화

B+Tree의 리프 페이지는 고정 크기(InnoDB 기본 16KB)이며, 채움율이 100%에 도달하면 분할(split)이 일어난다. 절차는 삽입 키가 들어갈 자리 없음을 감지하고, 새 리프를 할당해 기존 레코드 절반(중간 키 기준)을 이동시킨 뒤, 두 리프 사이 연결 리스트 포인터(prev/next)를 갱신하고, 새 리프의 첫 키(분리 키)를 부모에 삽입하며, 부모도 가득 차면 이 과정을 재귀 반복(캐스케이드 분할)하는 순서로 진행된다.

재귀가 루트까지 도달하면 트리 높이가 1 증가하며, 이 캐스케이드가 쓰기 증폭의 핵심 메커니즘이다. 실무 최적화로 "즉시 분할" 대신 "재분배"를 먼저 시도하는 방법이 있다. 삽입할 리프가 가득 찼을 때 형제(sibling)에 여유 공간이 있으면 분할 없이 레코드 일부를 넘겨 채움율을 재조정하며, InnoDB는 순차 삽입(auto-increment PK)에서 별도 경로로 90/10 분할(형제 90%, 신규 10%만 채움)을 적용해 분할 빈도를 낮춘다.

```python
def insert_leaf(tree, leaf, key, value):
    if leaf.has_space():
        leaf.insert_sorted(key, value)
        return
    for sib in (leaf.right_sibling(), leaf.left_sibling()):  # 재분배 우선 시도
        if sib and sib.parent_same(leaf) and sib.free_ratio() > 0.3:
            return redistribute(leaf, sib, key, value)
    split_leaf(tree, leaf, key, value)   # 재분배 불가 시 split

def split_leaf(tree, leaf, key, value):
    entries = sorted(leaf.entries + [(key, value)])
    mid = len(entries) // 2
    new_leaf = Leaf()
    leaf.entries, new_leaf.entries = entries[:mid], entries[mid:]
    new_leaf.next, new_leaf.prev = leaf.next, leaf   # 연결 리스트 갱신
    if leaf.next: leaf.next.prev = new_leaf
    leaf.next = new_leaf
    insert_into_parent(tree, leaf.parent, new_leaf.entries[0].key, new_leaf)

def insert_into_parent(tree, parent, sep_key, new_child):
    if parent is None:              # 루트 분할 -> 트리 높이 +1
        tree.root = InternalNode(children=[tree.root, new_child], keys=[sep_key])
    elif parent.has_space():
        parent.insert_key_and_child(sep_key, new_child)
    else:                           # 부모도 가득 참 -> 캐스케이드 분할
        split_internal(tree, parent, sep_key, new_child)
```

병합(merge)은 삭제 시 역과정으로, 채움율이 임계치(보통 50%) 아래로 떨어지면 형제와의 재분배를 먼저 시도하고 불가능하면 병합 후 부모의 분리 키를 제거하며, 이 역시 부모까지 캐스케이드될 수 있다.

## 3. B+Tree의 쓰기 증폭: 랜덤 삽입 캐스케이드와 디스크 I/O 비용

쓰기 증폭(write amplification)은 "논리적으로 1건을 썼는데 디스크에는 그보다 훨씬 많은 바이트가 쓰이는 현상"이다. B+Tree에서 이 문제가 극명한 시나리오가 UUID나 랜덤 해시를 PK로 쓰는 경우다. 순차 키(auto-increment)는 항상 가장 오른쪽 리프에만 삽입되어 분할이 예측 가능하고 국소적인 반면, 랜덤 키는 삽입 위치가 트리 전체에 균등 분산되어 이미 디스크에 내려간(cold) 페이지를 다시 읽어(random read) 수정하고 다시 써야(random write) 한다. 버퍼 풀보다 인덱스가 커지는 순간부터 랜덤 I/O 비용이 급격히 증가한다.

16KB 페이지에 평균 100건의 로우가 들어간다고 가정하면, 분할 시 최소 2개의 16KB 페이지 전체가 flush된다(doublewrite buffer까지 고려하면 InnoDB는 이 데이터를 두 번 쓴다). 논리적으로 수십 바이트를 삽입했을 뿐인데 물리적으로 32KB 이상이 쓰이는 셈이며, WAL(redo log)까지 더하면 증폭 배율은 더 커진다. UUID v4 PK 테이블에서 버퍼 풀이 데이터셋보다 작을 때, 순차 키 대비 랜덤 키 삽입은 TPS가 5~10배 낮아지고 random read for split이 지배적 병목이 되는 경향이 실측에서 자주 보고된다.

```sql
-- 랜덤 PK 쓰기 증폭 간접 관찰: 페이지 분할/flush 카운터
SHOW GLOBAL STATUS LIKE 'Innodb_pages_created';
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_flushed';
-- UUID PK vs AUTO_INCREMENT PK 테이블에 동일 건수 INSERT 후 비교
```

## 4. Fractal Tree(Bε-tree) 구조: 버퍼링을 통한 쓰기 지연·배치 처리

Fractal Tree Index는 TokuDB(후속 PerconaFT)와 BetrFS가 채택한 구조로, 학술적으로 Bε-tree(B-epsilon tree)라 부른다. 핵심 아이디어는 "정렬된 트리를 유지하되 각 내부 노드에 버퍼(message buffer)를 붙여 쓰기를 즉시 리프까지 전파하지 않고 지연시킨다"는 것이다. B+Tree의 삽입은 루트에서 리프까지 내려가 즉시 기록되지만, Bε-tree는 삽입을 "메시지(insert/delete/upsert)" 형태로 루트 버퍼에 먼저 쌓아두고, 버퍼가 가득 차면 한 번에(batch) 자식 버퍼로 flush하는 과정을 재귀적으로 반복하며(자식별 pivot key 기준 그룹화), 리프에 도달했을 때만 데이터를 물리적으로 갱신한다. 랜덤 삽입도 루트~중간 노드 버퍼에 순차적으로(append) 쌓이므로, 매번 랜덤 위치 리프를 읽어와 수정할 필요 없이 한 번의 I/O로 훨씬 많은 논리적 쓰기를 상각한다.

```java
class BetaTreeNode {           // Bε-tree 노드의 단순화된 버퍼 구조
    List<Message> buffer;      // 미반영 메시지 (B^epsilon 크기 나머지 공간을 차지)
    int bufferCapacity;
    List<BetaTreeNode> children;
    List<Key> pivotKeys;
    boolean isLeaf;

    void insert(Key key, Value value) {
        buffer.add(new Message(MessageType.INSERT, key, value));
        if (buffer.size() >= bufferCapacity) flushBuffer();
    }

    void flushBuffer() {
        if (isLeaf) { applyMessagesDirectly(buffer); buffer.clear(); return; }
        Map<BetaTreeNode, List<Message>> grouped = groupByChild(buffer, pivotKeys, children);
        for (var e : grouped.entrySet()) {
            BetaTreeNode child = e.getKey();
            child.buffer.addAll(e.getValue());   // batch 이동 (1회 I/O)
            if (child.buffer.size() >= child.bufferCapacity) child.flushBuffer(); // 캐스케이드
        }
        buffer.clear();
    }
}
```

읽기(query) 시에는 리프까지의 경로상 모든 버퍼를 확인해야 한다는 점이 트레이드오프다. 특정 키 조회 시 루트부터 리프까지 각 노드 버퍼에 미반영 메시지가 있는지 검사해야 하므로, "쓰기를 빠르게 하는 대신 읽기가 느려질 수 있다"는 것이 Bε-tree의 근본 트레이드오프다.

## 5. 파라미터 ε(epsilon)과 이론적 I/O 복잡도

Bε-tree의 ε는 각 노드에서 버퍼와 자식 포인터(피벗)가 차지하는 공간 비율을 조절하는 파라미터다. 노드 크기를 B(디스크 블록 크기 단위)라 할 때, 각 노드는 자식 포인터를 B^ε개, 버퍼 공간을 나머지 대부분(B - B^ε)만큼 갖도록 설계한다. ε는 0과 1 사이 값이며, ε→1이면 버퍼가 작아지고 fan-out이 커져 일반 B+Tree에 근접(읽기는 빠르나 버퍼링 효과 감소)하고, ε→0이면 버퍼가 커지고 fan-out이 작아져 쓰기는 빠르지만 트리 높이와 읽기 비용이 커진다.

이론적 I/O 복잡도(N: 전체 엔트리 수, B: 블록당 엔트리 수)는 B-Tree 삽입 비용 O(log_B N) 대비, Bε-tree 삽입 비용(amortized)이 O( (log_B N) / B^(1-ε) )로 비교된다. ε가 1보다 충분히 작으면 분모 항이 커져 삽입당 상각 I/O 비용이 훨씬 작아진다. 예컨대 B=1000, ε=0.5면 B^(1-ε)≈31.6이므로 삽입 비용이 약 1/31로 줄어들지만, 읽기(point query)는 이론상 비슷해도 레벨별 버퍼 스캔 오버헤드로 range query 성능은 워크로드에 따라 편차가 크다. 정리하면 ε는 "쓰기 지연·배치"와 "읽기 시 버퍼 탐색" 사이의 슬라이더이며, TokuDB는 이를 직접 노출하진 않았지만 fan-out 설정으로 유사한 조정이 가능했다.

## 6. LSM-Tree와의 삼각 비교: 읽기/쓰기/공간 증폭

세 구조를 하나의 축에 놓으면 "쓰기 증폭을 어디서 흡수할 것인가"의 설계 철학 차이다. B+Tree는 즉시 반영으로 읽기를 최적화하고 쓰기 증폭을 감수하며, LSM-Tree는 순차 쓰기(memtable→SSTable→compaction)로 쓰기를 최적화하지만 컴팩션 과정에서 쓰기·공간 증폭이 동시에 발생하고 읽기 시 여러 레벨을 뒤져야 한다(read amplification). Bε-tree는 그 중간에서 버퍼링으로 쓰기 증폭을 완화하되 읽기 시 버퍼 스캔 비용을 지불한다.

| 특성 | B+Tree | LSM-Tree | Fractal Tree (Bε-tree) |
| --- | --- | --- | --- |
| 쓰기 방식 | in-place update | append-only (memtable→SSTable) | 노드 버퍼에 메시지 축적 후 batch flush |
| 쓰기 증폭 | 높음 (분할 캐스케이드) | 중간~높음 (compaction 재작성) | 낮음 (buffer flush로 상각) |
| 읽기 증폭 | 낮음 (O(log_B N), 단일 경로) | 높음 (다중 SSTable 병합 조회) | 중간 (경로상 버퍼 확인 필요) |
| 공간 증폭 | 낮음 (즉시 재구조화) | 높음 (tombstone/중복 누적) | 중간 (버퍼 공간 오버헤드) |
| Range Scan | 매우 우수 (리프 연결 리스트) | 보통 (merge iterator 필요) | 우수 (정렬 순회 가능) |
| 대표 구현체 | InnoDB, Oracle, PostgreSQL | RocksDB, LevelDB, Cassandra | TokuDB, PerconaFT, BetrFS |
| 랜덤 삽입 적합도 | 낮음 | 높음 | 높음 |

세 구조 중 어느 것도 읽기/쓰기/공간 증폭을 동시에 최소화하지 못한다. RUM 추측(Read/Update/Memory 세 증폭 중 둘을 최적화하면 나머지를 희생해야 한다는 경험적 원칙)이 성립함을 보여주는 대표 사례다.

## 7. 실무 사례: TokuDB/PerconaFT, BetrFS의 벤치마크 경향과 InnoDB가 여전히 B+Tree인 이유

TokuDB는 MySQL 스토리지 엔진으로 제공되었고(이후 Percona가 PerconaFT로 포크·유지), 삽입 위주 워크로드(로그 적재, 시계열, 배치 삽입)에서 InnoDB 대비 수 배의 삽입 처리량 우위를 보인 벤치마크가 다수 보고되었으며 압축 효율도 우수했다. BetrFS는 Bε-tree를 파일시스템 레벨로 확장한 연구로, 메타데이터·소규모 파일 쓰기 워크로드에서 ext4 대비 성능 우위를 보였으나 순차 대용량 파일 읽기에서는 이점이 크지 않았다.

그럼에도 Oracle과 MySQL InnoDB는 여전히 B+Tree를 유지하는데, OLTP 워크로드 대다수가 읽기 비중이 높거나 순차 키 설계로 증폭 문제를 우회할 수 있다는 점, Bε-tree 계열은 버퍼·크래시 복구 처리의 구현 복잡도가 훨씬 높아 운영 성숙도 격차로 이어진다는 점(TokuDB가 크게 성장하지 못한 이유이기도 하다), NVMe SSD 보급으로 랜덤 I/O 비용이 낮아져 쓰기 최적화 구조의 상대적 이점이 줄었다는 점, 이 세 가지로 요약된다(다만 로그·time-series DB처럼 쓰기 압도적 워크로드에서는 여전히 LSM 계열이 강세다).

## 8. 인덱스 설계 시 실무적 시사점: UUID PK 문제와 시퀀셜 키 대안

지금까지의 논의는 "PK를 무엇으로 설계할 것인가"에 답을 준다. UUID v4를 PK로 쓰면 삽입이 트리 전체에 랜덤 분산되어, 데이터가 버퍼 풀보다 커지는 순간부터 심각한 쓰기 증폭과 캐시 미스가 발생한다. 3절의 캐스케이드 분할 문제가 실무에서 직접 발현되는 사례다.

대안으로는 BIGINT AUTO_INCREMENT나 시퀀스를 PK로 쓰고 UUID는 별도 유니크 컬럼으로 분리하는 것, 굳이 UUID가 필요하면 시간 순 정렬이 되는 UUID v7로 국소성을 확보하는 것, 멀티 리전 환경이면 Snowflake ID류로 "대체로 증가하는" 키를 흉내 내는 것, 쓰기가 압도적이고 range scan이 적다면(로그·이벤트 적재) 아예 LSM-Tree 스토어(RocksDB 계열, Cassandra)를 고려하는 것이 있다.

```sql
-- 안티패턴: UUID를 그대로 클러스터드 PK로 사용 (InnoDB)
CREATE TABLE orders_bad (
    id CHAR(36) NOT NULL DEFAULT (UUID()),  -- 랜덤 분산 삽입 -> 쓰기 증폭 심화
    customer_id BIGINT NOT NULL,
    PRIMARY KEY (id)
);

-- 개선안: 시퀀셜 대리키를 PK로, UUID는 조회용 유니크 키로 분리
CREATE TABLE orders_good (
    seq_id BIGINT NOT NULL AUTO_INCREMENT,   -- 순차 삽입 -> split 국소화
    public_uuid CHAR(36) NOT NULL DEFAULT (UUID()),
    customer_id BIGINT NOT NULL,
    PRIMARY KEY (seq_id),
    UNIQUE KEY uq_public_uuid (public_uuid)
);
```

핵심은 인덱스 구조 이론(캐스케이드 분할, 버퍼링, RUM 추측)을 워크로드 특성에 대입해 PK 전략을 역산하는 것이다. Oracle/MySQL 실무자라면 새 스토리지 엔진 도입보다 B+Tree 특성을 이해하고 키 설계로 쓰기 증폭을 우회하는 편이 더 현실적이다.

## 참고

- Bender et al. (2007). "Cache-Oblivious Streaming B-trees"
- Bender et al. "An Introduction to Bε-trees and Write-Optimization"
- Jannen et al. (2015). "BetrFS: A Right-Optimized Write-Optimized File System" (FAST '15)
- Esmet, Bender, Farach-Colton, Kuszmaul. "The TokuFS Streaming File System"
- O'Neil et al. (1996). "The Log-Structured Merge-Tree"
- Percona. "PerconaFT Documentation"
- Graefe, G. "Modern B-Tree Techniques"
- Petrov, A. "Database Internals" (O'Reilly)