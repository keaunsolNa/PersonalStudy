Notion 원본: https://www.notion.so/37e5a06fd6d381269de7cece3890151d

# LSM-Tree vs B+Tree 저장 엔진과 Write Amplification · Compaction

> 2026-06-11 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- B+Tree와 LSM-Tree의 읽기·쓰기 경로를 구조적으로 비교한다
- write/read/space amplification 세 지표로 저장 엔진을 평가한다
- LSM의 compaction 전략(leveled vs tiered)의 트레이드오프를 구분한다
- 워크로드 특성에 맞춰 저장 엔진을 선택하는 기준을 세운다

## 1. 두 저장 엔진의 출발점

디스크 기반 데이터베이스의 저장 엔진은 크게 두 계열로 나뉜다. B+Tree 계열(InnoDB, PostgreSQL, Oracle)과 LSM-Tree(Log-Structured Merge-Tree) 계열(RocksDB, LevelDB, Cassandra, HBase, ScyllaDB)이다. 둘의 근본 차이는 "쓰기를 어디에, 어떻게 배치하는가"다.

B+Tree는 데이터를 정렬된 트리로 제자리(in-place) 갱신한다. 키를 찾아 해당 페이지를 읽고, 수정한 뒤 다시 그 자리에 쓴다. LSM-Tree는 갱신을 제자리에서 하지 않고 메모리 버퍼에 모았다가 순차적으로 새 파일을 append한다. 같은 키의 옛 값은 그 자리에 남고, 나중 compaction이 정리한다. 이 한 가지 설계 결정이 두 엔진의 모든 성능 특성을 갈라놓는다.

## 2. B+Tree의 구조와 경로

B+Tree는 균형 트리로, 내부 노드는 키와 자식 포인터를, 리프 노드는 실제 데이터(또는 데이터 포인터)를 정렬 순으로 담는다. 리프는 양방향 연결 리스트로 이어져 범위 스캔이 효율적이다. 트리 높이는 보통 3~4단계라, 한 키 조회는 몇 번의 페이지 읽기로 끝난다.

쓰기 경로는 다음과 같다. 키 위치의 리프 페이지를 찾아 메모리(버퍼 풀)에 적재하고, 수정 후 dirty로 표시한다. 내구성을 위해 WAL(redo log)에 변경을 먼저 기록한다. dirty 페이지는 나중에 체크포인트 시 디스크의 원래 위치에 flush된다.

```text
INSERT 경로 (B+Tree)
1) WAL append (순차 쓰기, 작음)
2) 대상 리프 페이지 버퍼 적재 (랜덤 읽기 가능)
3) 페이지 내 정렬 위치에 삽입, dirty 표시
4) 페이지 가득 차면 split → 부모 갱신 전파
5) 체크포인트 시 페이지 in-place flush (랜덤 쓰기)
```

여기서 비용의 핵심은 "16KB 같은 페이지 단위로 쓴다"는 점이다. 한 행 100바이트를 바꿔도 페이지 전체를 다시 써야 하므로, 작은 갱신이 큰 물리 쓰기를 유발한다. 또 페이지 split은 트리 구조를 바꿔 랜덤 I/O를 더한다.

## 3. LSM-Tree의 구조와 경로

LSM-Tree는 여러 계층으로 구성된다. 쓰기는 먼저 메모리의 정렬 구조인 memtable(보통 skip list)에 들어가고, 동시에 WAL에 append해 내구성을 확보한다. memtable이 임계 크기에 도달하면 통째로 디스크에 정렬된 불변 파일 SSTable(Sorted String Table)로 flush된다.

```text
PUT 경로 (LSM-Tree)
1) WAL append (순차 쓰기)
2) memtable(skip list) 삽입 (메모리, 빠름)
3) memtable 가득 → 불변 SSTable 로 flush (순차 쓰기)
4) 백그라운드 compaction 이 SSTable 병합·정리
```

모든 디스크 쓰기가 순차 append라는 점이 LSM의 강점이다. 랜덤 쓰기가 없으므로 SSD/HDD 모두에서 쓰기 처리량이 높다. 대신 읽기는 복잡해진다. 한 키는 memtable, 그리고 여러 SSTable에 흩어져 있을 수 있어, 최신 값을 찾으려면 위에서 아래 계층으로 탐색한다. 이를 줄이려고 각 SSTable에 Bloom filter를 두어 "이 파일에 키가 없음"을 빠르게 판단하고, 블록 인덱스로 파일 내 위치를 좁힌다.

```text
GET 경로 (LSM-Tree)
1) memtable 조회
2) 최근 flush된 immutable memtable 조회
3) L0 → L1 → ... 각 SSTable: Bloom filter 통과 시에만 블록 읽기
4) 가장 최신(상위 계층) 값을 반환
```

## 4. 삭제와 tombstone

LSM은 제자리 수정을 하지 않으므로 삭제도 "삭제 마커(tombstone)"를 새로 기록하는 방식이다. 키를 지우면 tombstone이 append되고, 읽기 시 tombstone을 만나면 그 키가 삭제됐다고 판단한다. 실제 데이터 제거는 compaction이 옛 값과 tombstone을 함께 만났을 때 일어난다.

이 설계는 "삭제가 쓰기"라는 특성을 낳는다. 대량 삭제 워크로드에서 tombstone이 쌓이면 읽기 시 죽은 키를 계속 건너뛰어야 해 성능이 떨어진다(특히 범위 스캔). Cassandra 운영에서 tombstone 누적이 대표적 장애 원인인 이유다. B+Tree는 삭제 시 페이지에서 항목을 빼고 필요하면 병합하므로 이런 누적 문제는 없지만, 빈 공간 단편화가 생긴다.

## 5. 세 가지 Amplification

저장 엔진은 세 가지 증폭 지표로 평가한다. 셋은 동시에 최적화할 수 없는 trade-off 관계(RUM conjecture: Read, Update, Memory)다.

- **Write Amplification(WA)**: 논리적으로 쓴 데이터 1바이트당 실제 디스크에 쓰인 바이트. LSM은 compaction이 같은 데이터를 여러 번 다시 쓰므로 WA가 커진다. B+Tree는 페이지 단위 쓰기와 split으로 WA가 발생한다.
- **Read Amplification(RA)**: 논리 읽기 1건당 실제 디스크 읽기 횟수. LSM은 여러 SSTable을 뒤져야 해 RA가 크다(Bloom filter로 완화). B+Tree는 트리 높이만큼이라 RA가 작고 예측 가능.
- **Space Amplification(SA)**: 논리 데이터 크기 대비 실제 점유 디스크. LSM은 옛 값·tombstone이 compaction 전까지 남아 SA가 커질 수 있다. B+Tree는 페이지 내부 단편화(fill factor)로 SA가 생긴다.

| 지표 | B+Tree | LSM-Tree |
|---|---|---|
| Write Amplification | 중간(페이지 단위, split) | 높음(compaction 반복 재기록) |
| Read Amplification | 낮음(트리 높이) | 높음(다중 SSTable, Bloom으로 완화) |
| Space Amplification | 낮음~중간(단편화) | 전략에 따라 큼(중복·tombstone) |
| 쓰기 처리량 | 랜덤 쓰기 제약 | 순차 append로 높음 |
| 범위 스캔 | 리프 연결로 우수 | 다중 파일 병합 필요 |

## 6. Compaction 전략

LSM의 성능은 compaction 전략이 좌우한다. 대표적으로 leveled와 tiered(size-tiered) 두 가지가 있다.

**Leveled compaction**(RocksDB 기본)은 각 레벨(L1, L2, ...)이 키 범위가 겹치지 않는 SSTable들로 구성되고, 레벨 크기가 일정 배수(보통 10배)로 커진다. 한 레벨이 차면 그 일부를 다음 레벨과 병합한다. 레벨 내 키 범위가 겹치지 않으므로 읽기 시 레벨당 SSTable 한 개만 보면 되어 RA가 낮고 SA도 작다. 대신 병합이 자주 일어나 WA가 크다.

**Tiered(size-tiered) compaction**(Cassandra 기본 옵션)은 비슷한 크기의 SSTable이 여러 개 쌓이면 한꺼번에 병합해 더 큰 SSTable을 만든다. 병합 빈도가 낮아 WA가 작지만, 같은 키의 버전이 여러 SSTable에 동시에 존재할 수 있어 RA와 SA가 커진다.

| 전략 | WA | RA | SA | 적합 워크로드 |
|---|---|---|---|---|
| Leveled | 큼 | 작음 | 작음 | 읽기 비중 높음, 공간 절약 중요 |
| Tiered | 작음 | 큼 | 큼 | 쓰기 폭주, 디스크 여유 |

RocksDB는 두 전략과 universal compaction을 옵션으로 제공하고, write-heavy 구간에는 tiered, read-heavy에는 leveled로 조정하는 식의 튜닝이 가능하다. compaction은 백그라운드 작업이라 CPU·I/O를 소모하며, 너무 공격적이면 전경 쿼리 지연(p99 spike)을 유발하므로 throughput 제한(rate limiter)을 건다.

## 7. 워크로드별 선택 기준

엔진 선택은 워크로드 특성에서 출발한다.

쓰기가 매우 많고(IoT 센서, 로그, 시계열, 이벤트 수집) 순차 처리량이 중요하면 LSM이 유리하다. 모든 쓰기를 순차 append로 흡수하므로 랜덤 쓰기 병목이 없다. Cassandra, RocksDB, ScyllaDB가 이 영역의 주력이다.

읽기, 특히 점 조회와 범위 스캔의 낮은 지연·예측 가능성이 중요하고 갱신이 잦은 OLTP라면 B+Tree가 유리하다. 트리 높이만큼의 일정한 읽기 비용과 우수한 범위 스캔, 성숙한 트랜잭션·락 구현(MVCC)이 장점이다. MySQL InnoDB, PostgreSQL이 표준이다.

```text
선택 가이드 (요약)
- write throughput 극대화, 로그/시계열      → LSM (leveled or tiered)
- 낮은 read latency p99, OLTP, 강한 트랜잭션 → B+Tree
- 공간 효율 + 읽기 우선 LSM                  → leveled compaction
- 쓰기 폭주 흡수, 디스크 여유 LSM           → tiered compaction
```

실제로는 하이브리드도 흔하다. MySQL에 MyRocks(LSM) 스토리지 엔진을 붙여 쓰기 무거운 테이블만 LSM으로 두거나, TiDB·CockroachDB처럼 분산 계층은 RocksDB(LSM) 위에 트랜잭션·SQL 계층을 얹는 구조가 많다.

## 8. 정리

B+Tree와 LSM-Tree의 차이는 "제자리 갱신 vs append 후 병합"이라는 한 가지 결정에서 파생된다. 그 결정이 write/read/space amplification의 균형점을 정하고, LSM에서는 compaction 전략이 그 균형을 다시 조절한다. 세 증폭은 RUM 추측대로 동시에 최소화할 수 없으므로, 엔진 선택과 튜닝은 결국 "어느 증폭을 희생할 것인가"를 워크로드에 맞춰 정하는 일이다.

면접이나 설계 논의에서 핵심 메시지는 다음과 같다. 쓰기 처리량과 순차 I/O가 핵심이면 LSM, 낮고 안정적인 읽기 지연과 강한 트랜잭션이 핵심이면 B+Tree다. LSM을 고르면 compaction을 모니터링(컴팩션 지연, WA, tombstone 누적)하는 운영 부담을, B+Tree를 고르면 랜덤 쓰기와 페이지 단편화를 받아들이는 셈이다. 어느 쪽도 공짜가 아니며, 세 증폭의 트레이드오프를 이해하는 것이 저장 엔진 사고의 출발점이다.

## 9. 실측 감각과 Bloom filter 비용 계산

추상적 비교를 넘어 수치 감각을 잡아두면 설계 판단이 빨라진다.

**Bloom filter 메모리.** LSM의 read amplification을 줄이는 핵심이 Bloom filter다. false positive 확률 `p`와 키 개수 `n`에 대해 필요한 비트 수는 근사적으로 `m = -n·ln(p) / (ln2)²`다. 키당 비트 수는 `p`에만 의존한다.

| false positive | 키당 비트 | 키당 바이트 |
|---|---|---|
| 10% | 약 4.8 bit | 0.6 B |
| 1% | 약 9.6 bit | 1.2 B |
| 0.1% | 약 14.4 bit | 1.8 B |

10억 키를 1% false positive로 커버하려면 약 1.2GB 메모리가 든다. RocksDB의 기본 `bits_per_key=10`은 대략 1% 수준이다. false positive가 낮을수록 디스크 읽기는 줄지만 메모리가 선형으로 는다. 읽기 지연이 중요하면 비트를 늘리고, 메모리가 빠듯하면 줄이는 trade-off다.

**Leveled compaction의 WA 감각.** leveled에서 레벨 배수가 `T`(보통 10)이고 레벨 수가 `L`이면, 한 키가 최상위에서 최하위로 내려가며 대략 `T × L` 배수에 가까운 쓰기를 겪는다. 레벨 10단계, 배수 10이면 이론적 WA 상한이 100에 달할 수 있다. 실제로는 키 분포와 중복 갱신으로 더 작지만, "쓰기 1을 위해 디스크에는 수십 배를 쓴다"는 감각이 LSM 운영의 핵심이다. SSD 수명(P/E 사이클)에 직접 영향을 주므로, write-heavy LSM은 WA를 모니터링 지표로 둔다.

```text
RocksDB 모니터링 핵심 지표
- rocksdb.compaction.bytes.written / rocksdb.bytes.written  → 실측 WA
- rocksdb.bloom.filter.useful                               → Bloom 효과
- L0 file count / pending compaction bytes                  → write stall 예측
- rocksdb.stall.micros                                      → 전경 지연 누적
```

**Write stall.** LSM에서 쓰기가 compaction을 앞지르면 L0 파일이 쌓이고, 임계를 넘으면 RocksDB가 쓰기를 의도적으로 늦추거나(slowdown) 멈춘다(stop). 이는 데이터 유실 방지를 위한 backpressure지만 애플리케이션에는 지연 급증으로 나타난다. 대응은 compaction 스레드 증설, `level0_slowdown_writes_trigger` 조정, 쓰기 rate limiter 설정이다.

이 수치 감각을 종합하면, LSM은 "메모리(Bloom)로 읽기를 사고, 쓰기 증폭으로 순차 처리량을 산다"는 구조다. B+Tree는 그런 후처리 비용 없이 일정한 읽기를 주는 대신 랜덤 쓰기와 페이지 단편화를 감수한다. 어느 지표를 모니터링해야 하는지가 곧 어느 비용을 지불하는지를 드러낸다.

## 참고

- Designing Data-Intensive Applications, Martin Kleppmann — Ch.3 Storage and Retrieval
- RocksDB Wiki — Leveled / Universal Compaction: https://github.com/facebook/rocksdb/wiki/Compaction
- The Log-Structured Merge-Tree (O'Neil et al., 1996) 원논문
- RUM Conjecture (Athanassoulis et al., EDBT 2016): https://stratos.seas.harvard.edu/files/stratos/files/rum.pdf
