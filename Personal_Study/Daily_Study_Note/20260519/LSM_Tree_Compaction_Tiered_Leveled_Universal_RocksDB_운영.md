Notion 원본: https://www.notion.so/3655a06fd6d381efb860efc99a5ac368

# LSM Tree Compaction — Tiered·Leveled·Universal RocksDB 운영

> 2026-05-19 신규 주제 · 확장 대상: CS

## 학습 목표

- LSM(Log-Structured Merge) tree 의 write/read/space amplification 삼각관계를 정의하고 measure 한다
- Leveled·Tiered(Size-Tiered)·Universal compaction 전략의 동작 모델과 적용 워크로드를 비교한다
- RocksDB 의 compaction 옵션(`level0_*`, `target_file_size_*`, `compression_per_level`) 이 운영에 미치는 영향을 정리한다
- write stall·universal compaction trigger·sub-compaction parallelism 등의 운영 메트릭과 튜닝 포인트를 식별한다

## 1. LSM tree 의 기본 형상 — memtable·WAL·SST level

LSM tree 는 *순차 쓰기에 최적화* 된 자료구조다. 모든 쓰기는 WAL(crash recovery 의 단일 진실원)과 memtable(RAM, skip list)에 동시에 들어간다. memtable 이 일정 크기(default 64MB)에 다달으면 *immutable* 로 마킹되고 새 memtable 이 생성된다. immutable memtable 은 백그라운드로 *SST(Sorted String Table)* 파일로 flush 된다. SST 는 키 순서로 정렬된 *불변 파일* 이며, 디스크의 한 level 에 추가된다.

읽기는 *역순 머지*. memtable → immutable → L0(여러 파일 머지) → L1 → L2 ... 의 순서로 키를 찾는다. 모든 행이 *불변*이므로 같은 키의 *최신 시퀀스 번호* 가 우선이고, 삭제는 *tombstone* 이라는 특수 마커로 표현된다. 이 구조의 비대칭이 *amplification* 을 만든다. *Write Amplification(WA)* — 1바이트 사용자 쓰기당 디스크에 쓰이는 실제 바이트. *Read Amplification(RA)* — 키 한 번 조회 시 읽는 SST 수. *Space Amplification(SA)* — 사용자 데이터 대비 실제 디스크 사용량. WA·RA·SA 는 *동시에 모두 작게* 만들 수 없다. 셋 중 둘만 줄일 수 있는 *RUM Conjecture* 가 알려진 한계다.

## 2. Leveled Compaction — RocksDB 의 기본값

Leveled compaction 은 *각 level 이 정렬되고 비겹친다* 는 강한 invariant 를 유지한다. L0 는 예외(memtable flush 가 직접 들어가므로 키 범위 겹침 허용)이고, L1 이상은 *전체 level 안에서 한 키가 한 파일* 에만 존재한다.

compaction 트리거 — level 의 크기가 *target 크기* 를 초과하면 발동. target 크기는 `max_bytes_for_level_base`(L1) 와 `max_bytes_for_level_multiplier`(보통 10) 로 결정된다. 동작 — 한 SST 를 골라 다음 level 의 *키 범위가 겹치는 SST 들* 과 머지한다. amplification 특성: WA = level 수 × 10 ≈ 20~30, RA ≈ 6~8, SA < 1.1. 읽기 성능과 공간 효율이 좋고 *예측 가능*. OLTP 와 mixed workload 에 적합하다.

## 3. Tiered (Size-Tiered) Compaction — Cassandra·HBase 스타일

Tiered compaction 은 *같은 level 안에 같은 크기의 SST 들이 K 개 모이면* 머지한다. 머지 결과는 다음 level 의 *새 SST* 가 되고, 그 level 에 K 개 SST 가 또 모이면 다시 머지된다. 특징은 *level 안에서 키 범위 겹치 허용*. 같은 키가 한 level 안의 여러 SST 에 동시에 존재 가능하다. 읽기는 *모든 SST 의 bloom filter 와 인덱스* 를 통과해야 하므로 RA 가 크다. amplification — WA = log_K(N) × K ≈ 8~12, RA ≈ level × K ≈ 20~30, SA = 1.5~2.0. WA 가 작아 *write-heavy* 워크로드에 적합. 시계열·로그·이벤트 적재가 전형적 사용처. 단점— *공간 폭발*. K 개 SST 가 머지 직전이면 이미 K-1 배의 동일 데이터가 디스크에 있다.

## 4. Universal Compaction — RocksDB 의 write-optimized 옵션

Universal compaction(`compaction_style = kCompactionStyleUniversal`) 은 Tiered 의 변형이다. SST 를 *시간 순* 으로 줄세우고, 다음 셋 중 하나가 트리거되면 머지한다. `level0_file_num_compaction_trigger`(default 4) L0 파일 수 초과, `size_ratio` 도달(default 1%), `max_size_amplification_percent` 초과(default 200%, 즉 SA=3.0 한도) 시 *전체 머지*. 전체 머지는 모든 SST 를 한 번에 하나의 큰 SST 로 만든다. 비용이 크지만 *SA 를 강제로 1 에 가깝게 다시 정렬* 한다. 전체 머지 시 거대한 spike 가 생겨 SSD I/O queue 가 길어지며 write stall 의 주요 원인이 된다. RocksDB 가 universal 을 권장하는 케이스— *데이터 셋이 한 머신에 다 들어가고*, *write-heavy* 이며, *주기적 전체 머지 비용을 감수*할 수 있는 환경. 카프카-스타일 메시지 저장소.

## 5. RocksDB 의 핵심 옵션

`write_buffer_size`(default 64MB) memtable 크기. 크게 하면 flush 감소. `max_write_buffer_number`(default 2) 동시 memtable 수. `level0_file_num_compaction_trigger`(default 4) L0→L1 compaction 시작. `level0_slowdown_writes_trigger`(default 20) 쓰기 느려짐. `level0_stop_writes_trigger`(default 36) 쓰기 완전 정지. `target_file_size_base`(default 64MB) L1 SST 목표 크기. `max_bytes_for_level_base`(default 256MB) L1 총 크기. `compression_per_level` — level 별 압축. L0/L1 은 *snappy*(빠름), L2+ 는 *zstd*(높은 압축률). 추가로 Bloom Filter 관련 `bloom_locality`(SST 가 크면 partial bloom)과 `optimize_filters_for_hits`(negative case 의 bloom 효율을 낮춰 size 절약).

## 6. Write stall 의 진단

운영 사고의 절반 이상이 write stall 이다. 메트릭으로 진단한다. `rocksdb.stall.micros` 누적 stall 시간 — 평소 0에 가까워야 함. `rocksdb.num.files.at.level0` L0 파일 수 — trigger 의 80% 를 넘으면 위험. `rocksdb.compaction.pending` 대기 compaction 수 — 0보다 크면 *compaction 이 쓰기를 못 따라잡고 있음*. `rocksdb.bg.compactions.pending` 백그라운드 compaction job queue 길이.

해결 순서 — compaction 쓰레드 증가 → target_file_size 줄이기 → L0 trigger 완화(임시) → application 단 throttle. 대용량 쓰기 spike 에서는 *rate limit* 이 제어 가능한 상한선을 보장한다. `Options::rate_limiter = NewGenericRateLimiter(100MB/s)`.

## 7. Sub-compaction parallelism

RocksDB 5.4 부터 한 compaction job 을 *키 범위로 분할해 병렬 실행*한다. `Options::max_subcompactions = N`. 이점— 큰 compaction(예: L4→L5, 10GB+)의 wall time 이 N 배 가까이 줄어 *compaction lag* 감소. 비용— CPU·I/O 사용량이 N 배. 권장값— `max_background_compactions = vCPU/2`, `max_subcompactions = 4~8`.

## 8. Compaction filter — TTL·tombstone GC

`CompactionFilter` 는 compaction 도중 *키마다 호출* 되어 *제거 여부* 를 결정한다. TTL 구현·소프트 삭제 정리·schema migration 의 핵심 도구다. tombstone 은 *해당 키의 모든 버전이 같은 compaction 에 포함될 때만* GC 된다. `DeleteRange` API (5.13+) 는 *키 범위 단위의 효율적인 삭제*다. tombstone 하나가 범위 전체를 표현하므로 시계열 데이터의 주기적 trim 에 매우 효율적. 단 *읽기 성능에 영향*이 있다.

## 9. 워크로드별 선택과 운영 체크

선택 가이드 — *읽기/쓰기 비율 1:1 이상*, *예측 가능한 공간*: **Leveled**. *쓰기 폭주, 공간 여유 있음*: **Tiered**. *디스크 한계, 주기적 풀 머지 감수*: **Universal**.

체크리스트 8개. `rocksdb.stall.micros` 알람이 24시간 0 인가. L0 파일 수 평균이 trigger 의 50% 이하인가. WA 가 30 이하인가. SA 가 1.5 이하인가. Bloom filter hit rate 가 99% 이상인가. block cache hit rate 가 90% 이상인가. compaction priority 가 trade-off 와 일치하는가. WAL fsync 정책이 데이터 안전과 일치하는가.

운영 한 줄 — *measure, then change one option at a time*. 옵션 8개를 한 번에 바꾸면 어느 것이 원인인지 알 수 없다. canary 노드에서 한 옵션씩 검증해 production 으로 옮긴다.

## 참고

- Sears & Ramakrishnan, "bLSM: A General Purpose Log Structured Merge Tree" (SIGMOD 2012)
- Niv Dayan et al., "Monkey: Optimal Navigable Key-Value Store" (SIGMOD 2017)
- RocksDB Wiki — Compaction <https://github.com/facebook/rocksdb/wiki/Compaction>
- RocksDB Tuning Guide
- Niv Dayan & Stratos Idreos, "The Log-Structured Merge-Bush" (SIGMOD 2019)
