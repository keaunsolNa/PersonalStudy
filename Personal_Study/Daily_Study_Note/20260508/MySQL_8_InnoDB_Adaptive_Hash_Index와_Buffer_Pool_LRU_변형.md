Notion 원본: https://www.notion.so/35a5a06fd6d3810c9ddfdfd91a3b8779

# MySQL 8 InnoDB Adaptive Hash Index와 Buffer Pool LRU 변형

> 2026-05-08 신규 주제 · 확장 대상: Oracle / SQLD

## 학습 목표

- Adaptive Hash Index(AHI)의 동작 원리와 활성화 조건, 성능 효과를 정량적으로 이해한다
- InnoDB Buffer Pool의 midpoint LRU 변형이 sequential scan 오염을 어떻게 방지하는지 설명한다
- AHI partition, latch contention, hash collision 같은 운영 이슈를 진단하는 지표를 안다
- Buffer Pool 크기·instance 분할·dump/restore 옵션의 trade-off를 결정 근거와 함께 적용한다

## 1. InnoDB의 인덱스 구조와 AHI의 위치

InnoDB는 모든 데이터를 클러스터드 B+Tree에 저장한다. primary key 가 트리의 leaf 자체를 구성하고, secondary index 는 primary key를 가리키는 별도 B+Tree 다. point lookup 시 root → branch → leaf 까지 보통 3~4 페이지의 binary search가 발생한다.

Adaptive Hash Index는 자주 접근되는 인덱스 키 범위에 대해 **B+Tree leaf 페이지를 가리키는 in-memory hash table 을 자동으로 만들어** 트리 탐색을 한 번의 hash lookup으로 단축한다. AHI는 영속 디스크 구조가 아니며, 메모리에만 존재한다.

활성화 조건:

- 같은 키 prefix로 동일한 패턴의 lookup이 100회 이상 반복
- `innodb_adaptive_hash_index = ON`(default)

효과는 OLTP 환경의 point lookup heavy 워크로드에서 크다(대략 10~30% throughput 개선 사례 보고).

## 2. AHI의 한계와 실패 모드

- range scan, BETWEEN: hash 는 등치 비교만 가능
- 키가 매우 길거나 prefix가 길어 hash 충돌이 잦은 경우
- write 가 많은 워크로드: AHI 갱신을 위한 latch contention 발생

```sql
SHOW ENGINE INNODB STATUS\G
-- Hash searches/s, n non-hash searches/s 확인
```

`Hash searches / Non-hash searches` 비율이 0.5 이하라면 AHI 가 거의 도움이 안 되고 있다는 신호이고, 1.0 이상이면 AHI 가 자기 비용을 회수하고 있다고 본다.

## 3. Buffer Pool과 Working Set

- 단독 DB 서버: 사용 가능한 RAM 의 60~70%
- App 코로케이션: 30~50%
- 여러 instance 분할은 buffer pool ≥ 4GB 일 때만 의미 있음
- 8GB 미만에서는 instance 1개, 16~64GB 는 8개, 64GB 이상은 16개 부근

## 4. midpoint LRU 변형

### 4.1 LRU 분할

전체 LRU 리스트를 **young sublist** 와 **old sublist** 두 영역으로 나뢌다. `innodb_old_blocks_pct`(default 37%)가 old sublist 의 비율이고, 나머지가 young sublist 다. 이 분할은 sequential scan 으로 오염되는 것을 막기 위한 것이다.

### 4.2 promotion 지연

old sublist 에 들어간 페이지가 다시 접근됐을 때, 즉시 young sublist 로 옮기지 않고 `innodb_old_blocks_time`(default 1000ms) 가 지난 후 첫 접근에서야 young 으로 promotion 한다.

## 5. dirty page flush 와 IO scheduler

- `innodb_io_capacity` — 정상 상태 IO budget(default 200)
- `innodb_io_capacity_max` — 긴급 시 최대 IO(default 2000)
- `innodb_flush_neighbors` — SSD 에선 0 으로 두는 편이 정설
- `innodb_lru_scan_depth` — LRU tail 에서 evict 후보 검색 깊이(default 1024)

NVMe 라면 `io_capacity = 4000~10000`, `io_capacity_max = 20000` 정도로 시작한다.

## 6. Buffer Pool dump/restore — warmup 자동화

```sql
SET GLOBAL innodb_buffer_pool_dump_at_shutdown = ON;
SET GLOBAL innodb_buffer_pool_load_at_startup  = ON;
SET GLOBAL innodb_buffer_pool_dump_pct = 25;
```

25% 만 dump 해도 90% 이상의 hit ratio 회복이 가능한 경우가 많다.

## 7. doublewrite buffer 와 write 일관성

InnoDB 는 dirty page 를 datafile 에 직접 쓰기 전, 먼저 doublewrite buffer 에 sequential write 한 다음 datafile 의 원래 위치로 random write 한다. 8.0.20 부터는 doublewrite 의 위치를 시스템 tablespace 외부의 별도 파일로 분리할 수 있다.

## 8. 진단 쿼리 모음

| 지표 | 쿼리 |
|---|---|
| Buffer pool hit ratio | `SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';` |
| Dirty page 비율 | `SHOW ENGINE INNODB STATUS\G` |
| AHI 효과 | INSERT BUFFER AND ADAPTIVE HASH INDEX 의 hash searches/s |
| LRU 길이 분포 | `SELECT * FROM information_schema.INNODB_BUFFER_POOL_STATS;` |

`INNODB_BUFFER_PAGE` 테이블은 매우 비싸다. 트러블슈팅 1~2회 수준에서만 사용하는 편이 안전하다.

## 참고

- MySQL 8.0 Reference Manual — InnoDB Adaptive Hash Index
- MySQL 8.0 Reference Manual — InnoDB Buffer Pool
- High Performance MySQL 4판 — chapter 5
- Percona Blog — Tuning InnoDB Buffer Pool Size on NVMe
- innodb-internals 시리즈 — Jeremy Cole 의 GitHub 리포지토리
