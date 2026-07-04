Notion 원본: https://app.notion.com/p/3935a06fd6d38166b0a7df5e4ad159f5

# LSM Tree vs B Tree 스토리지 엔진과 Compaction 및 쓰기 증폭

> 2026-07-05 신규 주제 · 확장 대상: Oracle

## 학습 목표

- B+Tree 와 LSM Tree 의 쓰기 경로 차이를 구조적으로 설명한다
- MemTable/SSTable/WAL 구성과 읽기 경로의 계층 탐색을 파악한다
- 쓰기 증폭, 읽기 증폭, 공간 증폭의 삼각 트레이드오프를 이해한다
- Leveled vs Size-tiered compaction 의 특성과 선택 기준을 정리한다

## 1. 두 스토리지 엔진의 근본 차이

관계형 DB 는 대부분 B+Tree, RocksDB/Cassandra 는 LSM Tree 기반이다. 본질은 쓰기 반영 방식이다. B+Tree 는 제자리(in-place) 갱신(랜덤 쓰기), LSM 은 추가(append-only) 후 병합(순차 쓰기)이다. 순차 쓰기가 빨라 LSM 은 쓰기 처리량이, B+Tree 는 읽기·공간 효율이 강하다.

## 2. B+Tree 의 쓰기 경로와 쓰기 증폭

삽입·갱신은 리프 페이지를 제자리 수정하고, 가득 차면 분할이 상위로 전파된다. WAL 기록 + 페이지 쓰기로 한 논리 쓰기가 최소 두 번 디스크에 반영된다(쓰기 증폭). 읽기는 루트→리프 높이(3~4단)만 내려가 예측 가능하게 빠르고 범위 스캔도 리프 연결로 순차적이다.

## 3. LSM Tree 구성

MemTable(메모리 정렬), WAL(순차 append 복구용), SSTable(정렬 불변 파일), Bloom Filter/인덱스 블록으로 구성된다. 삭제도 append 라 tombstone 표식을 남기고, 갱신도 새 값을 추가할 뿐이라 같은 키의 여러 버전이 흩어진다.

```
쓰기: WAL(append) + MemTable -> full -> flush -> SSTable(L0)
읽기: MemTable -> immutable -> L0 -> L1 -> ...  (각 SSTable 은 Bloom 으로 스킵)
```

## 4. 읽기 경로와 읽기 증폭

최신부터 MemTable→하위 레벨 순으로 찾고, 없거나 오래된 데이터는 여러 SSTable 을 뒤져야 한다(읽기 증폭). Bloom Filter 가 "확실히 없음"을 판정해 디스크 접근을 스킵한다(false negative 없음). 범위 스캔은 여러 SSTable 병합이라 상대적으로 비싸다.

```python
def get(key):
    for t in [memtable, *immutable_memtables]:
        v = t.get(key)
        if v is not None: return None if v.is_tombstone else v
    for level in levels:
        for sst in level.candidates(key):
            if not sst.bloom.might_contain(key): continue
            v = sst.get(key)
            if v is not None: return None if v.is_tombstone else v
    return None
```

## 5. Compaction — 두 전략

Compaction 은 SSTable 을 병합해 중복 최신값만 남기고 tombstone 을 실제 제거한다. Size-tiered(STCS)는 비슷한 크기를 병합해 쓰기 증폭이 낮지만 읽기·공간 증폭이 크다(Cassandra 기본). Leveled(LCS)는 L1+ 에서 키 범위가 겹치지 않게 유지해 읽기·공간 증폭이 작지만 쓰기 증폭이 크다(RocksDB 기본).

| 특성 | Size-tiered | Leveled |
|---|---|---|
| 쓰기 증폭 | 낮음 | 높음 |
| 읽기 증폭 | 높음 | 낮음 |
| 공간 증폭 | 높음 | 낮음 |
| 적합 | 쓰기 많음 | 읽기 많음 |

## 6. RUM 추측

Read/Update/Memory 증폭 중 둘을 최적화하면 나머지가 나빠진다. LSM 은 쓰기 증폭을 낮추고 읽기·공간을 Bloom·compaction 으로 보정, B+Tree 는 읽기·공간을 잡고 쓰기 증폭을 감수한다. 시계열·이벤트 로그는 LSM, OLTP·범위 스캔·트랜잭션은 B+Tree 가 유리하다. MyRocks 처럼 엔진 교체 사례도 있다.

## 7. 실무 튜닝

LSM 은 compaction 부하로 write stall(L0 과다 누적 시 쓰기 정지)이 생겨 `level0_stop_writes_trigger`·rate limiter 로 조절한다. tombstone 이 많으면 범위 스캔이 느려진다(Cassandra tombstone 문제). B+Tree 는 페이지 단편화·fill factor 가 튜닝 포인트다. 스토리지 엔진 선택은 공 세 증폭 중 무엇을 희생할지의 선택이다.

## 참고

- Patrick O'Neil et al., "The Log-Structured Merge-Tree" (1996)
- RocksDB Wiki — Leveled/Universal Compaction
- Athanassoulis et al., "Designing Access Methods: The RUM Conjecture" (2016)
- Designing Data-Intensive Applications, Ch.3 (Martin Kleppmann)
