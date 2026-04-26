Notion 원본: https://www.notion.so/34e5a06fd6d3817abef2dce2ad3cc604

# B+Tree vs LSM Tree 인덱스와 Write Amplification

> 2026-04-26 신규 주제 · 확장 대상: Oracle (Index 학습됨), 자료구조&알고리즘 (Tree 학습됨)

## 학습 목표

- B+Tree와 LSM Tree의 read/write path를 디스크 I/O 단위로 추적한다
- Write Amplification(WA)와 Read Amplification(RA) 정의를 정량 수식으로 계산한다
- Tiered vs Leveled compaction 전략의 I/O 패턴 차이를 RocksDB 운영 관점에서 비교한다
- 실제 사용 사례(MySQL InnoDB / RocksDB / Cassandra)에서 인덱스 선택의 trade-off를 평가한다

---

## 1. 두 자료구조가 겨누는 워크로드

스토리지 엔진의 인덱스는 disk I/O를 줄이는 자료구조다. 디스크는 random access가 sequential보다 100배 이상 느리다는 비대칭 위에 설계된다.

B+Tree는 read-optimized하다. 한 번의 point query에 4~5번의 page read로 끝난다. 모든 internal node는 sorted 상태를 유지하므로 range scan도 sequential read에 가깝다. 단점은 write다. row 하나 update에도 leaf page를 읽고, 수정해서, 다시 쓴다(read-modify-write). page split이 발생하면 더 많은 page write로 amplify 된다.

LSM Tree(Log-Structured Merge Tree)는 write-optimized다. 모든 write를 sequential append로 수행해 random write를 제거한다. 대신 read는 여러 SSTable을 거쳐야 하는 amplification 비용을 받아들인다. compaction 과정에서 background I/O가 발생해 throughput 변동(write stall)이 생길 수 있다.

| 워크로드 | 권장 자료구조 |
|---|---|
| OLTP read-heavy (e-commerce 상품 조회) | B+Tree |
| time-series ingest (메트릭, 로그) | LSM |
| OLTP write-heavy (이벤트 sourcing) | LSM |
| analytic scan (OLAP) | columnar (LSM과 별개) |

## 2. B+Tree 구조와 page split

B+Tree는 모든 leaf가 같은 깊이에 있고, key가 sorted 된 상태로 leaf에 저장된다. internal node는 routing 정보만 가진다.

```
                  [50, 100]
                 /    |    \
          [10,30]  [60,80]  [120,150]
           /|\      /|\       /|\
         (leaf pages with actual rows)
```

InnoDB의 page는 16KB이고, 한 leaf page에 평균 100~200개 row가 들어간다. row size 100 bytes 기준 fan-out 약 160. 1억 행 테이블의 깊이는 `log_160(1억) ≈ 4`다. point query 1회의 disk read는 4 page = 64KB이고, buffer pool에 상위 levels이 캐시되면 실질 disk read는 1회다.

write가 발생하면 다음 단계가 일어난다.

1. leaf page를 buffer pool에 로드 (없으면 disk read)
2. row 수정 / 삽입
3. page에 변경 marking (dirty flag)
4. WAL(redo log)에 변경 기록 → fsync
5. background에서 dirty page를 disk로 flush

문제는 leaf page가 가득 찰 때다. fill_factor 100%에서 새 row가 들어오면 page split이 발생해 1 page → 2 page로 분리하고, parent에 새 separator key를 추가한다. parent도 split이 필요하면 root까지 propagate 한다. split은 random disk write를 만들고 cache invalidate를 일으킨다.

`fill_factor`를 90%로 두면 split 빈도를 줄일 수 있다. PostgreSQL은 `fillfactor=90`이 default이고, InnoDB는 `MERGE_THRESHOLD`로 leaf page 병합 임계치를 조정한다.

write amplification 추정 (단순 모델):

```
WA_btree ≈ tree_height × pages_per_change
```

대부분의 update는 leaf 1 page write + WAL write로 끝나 WA ≈ 2이지만, page split이 발생하면 그 transaction에 한해 WA가 5~6까지 튄다. cache miss 빈도가 높은 워크로드일수록 WA가 커진다.

## 3. LSM Tree의 write path

LSM은 write를 다음 경로로 처리한다.

```
write
  → WAL append (sequential disk write)
  → MemTable insert (in-memory sorted structure, e.g. skiplist)
  → MemTable이 가득 차면 immutable 으로 전환
  → background flush → SSTable (sorted file on disk)
```

이 과정에 disk random write는 없다. WAL은 append-only sequential, MemTable flush도 sequential write다. 그래서 LSM의 write throughput은 B+Tree보다 5~10배 높다.

```cpp
// 개념적 의사 코드
class LsmTree {
    WAL wal;
    MemTable active;
    vector<MemTable> immutable;
    vector<vector<SSTable>> levels;  // L0, L1, L2, ...

    void put(Key k, Value v) {
        wal.append(k, v);
        active.insert(k, v);
        if (active.size() > THRESHOLD) {
            immutable.push_back(active);
            active = MemTable();
            schedule_flush();
        }
    }
};
```

read는 다음과 같다.

```cpp
Value get(Key k) {
    if (active.contains(k)) return active.get(k);
    for (auto& mem : immutable) {
        if (mem.contains(k)) return mem.get(k);
    }
    for (int level = 0; level < levels.size(); level++) {
        for (auto& sst : levels[level]) {
            if (sst.bloom_filter.may_contain(k) && sst.contains(k)) {
                return sst.get(k);
            }
        }
    }
    return NotFound;
}
```

read는 active → immutable → L0 → L1 → ... 순으로 모든 level을 뒤져야 한다. Bloom Filter가 SSTable마다 있어 not-contains를 빠르게 거른다. 그래도 worst case에 N개 SSTable의 metadata read가 일어나 read amplification이 커진다.

## 4. Compaction 전략

LSM은 compaction으로 SSTable을 병합해 정리한다. 두 가지 주류 전략이 있다.

### 4.1 Tiered (Cassandra default)

같은 level의 SSTable이 일정 개수에 도달하면 모두 병합해 다음 level의 SSTable 1개로 만든다. write가 단순하지만 같은 level에 같은 키가 여러 SSTable에 존재할 수 있어 read amplification이 크다.

```
L0: [A, B, C, D]    ← 4개 모두 병합
L1: [merged]
```

WA는 작다(`평균 약 2~3`). RA는 크다(`level수 × tier수`).

### 4.2 Leveled (RocksDB default)

각 level의 총 크기 비율(예: L1 256MB, L2 2.56GB, L3 25.6GB)을 유지한다. SSTable이 추가되면 다음 level의 overlapping SSTable과 병합되어 다음 level로 옮겨진다. 같은 키가 한 level에 한 번만 존재하도록 강제하는 invariant가 있다.

```
L1: [0-100][100-200][200-300]
        ↓ compaction (overlapping range만)
L2: [0-50][50-100]...   ← 이전 [0-100]과 겹치는 것만 병합
```

WA는 크다(평균 `10 × level_수`). RA는 작다(level당 1 SSTable lookup).

비교:

| 메트릭 | Tiered | Leveled |
|---|---|---|
| Write Amplification | 2~3 | 10~30 |
| Read Amplification | 큼 | 작음 |
| Space Amplification | 큼(중복 많음) | 작음 |
| 적합 워크로드 | write-heavy, time-series | mixed read/write |

RocksDB는 default leveled이지만 ColumnFamily 단위로 tiered로 바꿀 수 있다. write-heavy bulk insert 단계에는 tiered, 운영 단계에는 leveled로 전환하는 패턴이 있다.

## 5. Write Amplification 정량 계산

LSM의 leveled compaction에서 한 row가 `L0 → L_max`까지 이동하는 평균 횟수가 WA다.

```
WA_leveled ≈ Σ (size_ratio) × (compaction cost per level)
```

RocksDB 운영 가이드의 경험식: WA ≈ `10 × log(N) / log(size_ratio)`. size_ratio=10이고 N=10^9 row면 WA ≈ 10 × 9 / 1 = 90 이라는 모델인데, 실제로는 30~50 수준이다(Bloom filter, level 압축 효율).

이 비용을 무시하면 실제 disk write throughput이 application write의 30배가 된다. 100MB/s SSD에서 application은 약 3MB/s만 쓸 수 있다는 말이다. compaction tuning 없이는 ingest pipeline이 일찍 saturate 된다.

B+Tree의 WA는 평균 2~3 수준으로 LSM보다 일관적으로 작지만, page split과 random write 비용 때문에 IOPS 부담이 큰 SSD에서는 latency variance가 크다.

## 6. 실제 시스템 매핑

| 시스템 | 인덱스 구조 | 특징 |
|---|---|---|
| MySQL InnoDB | B+Tree (clustered) | PK가 leaf data 자체, secondary index는 PK를 가리킴 |
| PostgreSQL | B+Tree (heap pointer) | leaf가 heap row 위치를 가리킴, MVCC와 결합 |
| Oracle | B+Tree | 파티셔닝, IOT(Index-Organized Table)는 clustered 비슷 |
| RocksDB / Cassandra | LSM (leveled / tiered) | LevelDB 기반 |
| MongoDB WiredTiger | B+Tree (default) | LSM도 옵션 |
| Elasticsearch | Inverted Index + LSM-like segments | Lucene segment merge가 compaction에 해당 |

InnoDB의 clustered index는 PK 기준으로 모든 row 데이터가 leaf에 저장된다. PK 외의 인덱스(secondary index)는 leaf에 PK 값을 저장해, lookup 시 secondary → clustered 두 번 traverse 한다. PK가 random UUID라면 page split이 빈번해 WA가 커진다. AUTO_INCREMENT나 ULID 같은 sortable ID가 권장되는 이유다.

Cassandra는 LSM 기반이라 high-throughput write에 강하다. 단 read는 partition key + clustering key로 좁혀야 효율적이고, secondary index는 비용이 크다. 이 제약은 데이터 모델링 시점부터 query pattern을 결정하게 만든다.

## 7. 운영 측정과 tuning

RocksDB의 WA는 다음으로 측정한다.

```
rocksdb.compact.read.bytes / rocksdb.compact.write.bytes / rocksdb.bytes.written
WA = (compact.read + compact.write + bytes.written) / bytes.written
```

이 비율을 모니터링하다 30을 넘으면 compaction이 따라가지 못한다는 신호다. 대응:

- `max_background_compactions`를 증가
- `level0_slowdown_writes_trigger` / `level0_stop_writes_trigger` 임계치 조정
- write rate를 어플리케이션 측에서 throttle

InnoDB는 `innodb_buffer_pool_size`가 데이터셋 working set을 담을 만큼 크면 page cache miss가 줄어 WA가 안정된다. 일반 권장은 RAM의 70~80%다.

EXPLAIN과 buffer pool stats로 인덱스 효율을 검증한다. range scan 비중이 높은 워크로드에서 LSM은 SSTable boundary를 가로지르며 비싸지므로, B+Tree로 옮기거나 partition을 잘게 나누어 SSTable당 range를 좁히는 전략을 쓴다.

## 8. 선택 기준

write 비중이 70% 이상이고 random write IOPS 한계가 보이면 LSM이 자연스러운 선택이다. write가 sequential append만 일으키므로 SSD 수명에도 유리하다.

read latency p99이 결정적이면 B+Tree가 안전하다. LSM은 compaction 직후 SSTable count가 적어 빠르고, compaction 직전에 느려지는 변동을 가진다. 이를 받아들일 수 없는 latency-critical 시스템(결제, 인증)은 B+Tree를 쓴다.

mixed 워크로드에서는 RocksDB의 leveled compaction이 적정 절충이다. 또는 hybrid 전략으로 서로 다른 데이터 그룹을 다른 자료구조에 둔다. user profile은 InnoDB에, event stream은 RocksDB에 저장하는 식이다.

inverted index 같은 특수 자료구조는 그 자체로 LSM-like다. Elasticsearch / Solr는 Lucene segment merge가 compaction과 같은 역할을 한다. 본질적으로 search 워크로드에 LSM의 sequential write 이점이 잘 맞는다.

## 참고

- "Designing Data-Intensive Applications", Martin Kleppmann, O'Reilly (Chapter 3)
- RocksDB Wiki - "RocksDB Tuning Guide", "Choosing Between Leveled and Universal Compaction"
- Sanjay Ghemawat & Jeff Dean, "LevelDB Implementation Notes"
- MySQL 8.0 Reference Manual - InnoDB B-Tree Implementation
