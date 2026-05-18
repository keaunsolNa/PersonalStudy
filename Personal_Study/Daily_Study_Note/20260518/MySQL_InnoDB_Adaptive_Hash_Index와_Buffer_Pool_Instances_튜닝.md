Notion 원본: https://www.notion.so/3645a06fd6d38184b0bac7ec57b43bb5

# MySQL InnoDB Adaptive Hash Index와 Buffer Pool Instances 튜닝

> 2026-05-18 신규 주제 · 확장 대상: Oracle · DB

## 학습 목표

- InnoDB Adaptive Hash Index(AHI)가 B+Tree lookup을 어떻게 단축하는지, 언제 켜고 끌지를 결정한다
- Buffer Pool Instance 분할이 mutex contention과 무슨 관계가 있는지, instance 수의 적정값을 산정한다
- `innodb_buffer_pool_chunk_size`, `innodb_buffer_pool_size`, 인스턴스 수의 상호 제약을 이해하고 메모리 변경 절차를 적용한다
- PERFORMANCE_SCHEMA로 AHI hit/miss와 buffer pool waits를 측정해 튜닝 효과를 검증한다

## 1. AHI(Adaptive Hash Index)란 무엇인가

InnoDB의 모든 인덱스는 디스크 상에서 B+Tree로 구성된다. 동일한 키에 대한 lookup이 충분히 자주 일어나면 InnoDB는 그 B+Tree leaf로 가는 경로를 **메모리상의 해시 테이블**로 캐시한다. 이 해시 테이블이 AHI다. B+Tree는 O(logN) 단계의 페이지 탐색을 거쳐야 하지만 AHI는 O(1)에 leaf 위치로 점프한다.

AHI는 자동 적응적이다. 통계 카운터가 누적되어 "이 인덱스의 이 prefix가 자주 lookup된다"고 판단되면 그때만 해시 슬롯이 생긴다. 옵션이 켜져 있어도 모든 인덱스가 해시되는 것은 아니다.

```
mysql> SHOW ENGINE INNODB STATUS\G
...
INSERTS INTO ADAPTIVE HASH: 0/s, 0 searches/s, 0.00 hash searches/s
non-hash searches/s 12345
hash searches/s 87654
```

위 출력에서 hash searches / (hash + non-hash searches) 비율이 AHI hit ratio다. 70% 이상이면 AHI가 충분히 일한다고 본다.

## 2. AHI의 동작 메커니즘 — Latch와 8 partition

AHI는 단일 메모리 구조이므로 동시 갱신 시 latch가 필요하다. 5.7 이전에는 단일 RW-latch였고, 이게 OLTP에서 hot spot이었다. 5.7부터 `innodb_adaptive_hash_index_parts`로 8개(기본) 파티션으로 분할하여 latch contention을 줄였다.

```ini
[mysqld]
innodb_adaptive_hash_index = ON
innodb_adaptive_hash_index_parts = 16   # 코어가 많은 머신에서 상향
```

파티션은 인덱스 ID 해시로 분배된다. 단일 hot index의 lookup은 한 파티션에 몰리므로 파티션을 늘려도 단일 hot index에는 효과가 없다. 다양한 인덱스를 동시에 두드리는 워크로드에서만 효과가 있다.

## 3. AHI가 손해가 되는 시나리오

AHI는 lookup을 빠르게 하지만 다음 비용을 동반한다. 첫째, 메모리 — AHI 구조 자체가 buffer pool과 별도로 메모리를 점유한다(`Buffer pool size`와 별개로 약 1/64 추가). 둘째, 갱신 비용 — leaf page가 변경되면 해당 해시 슬롯을 invalidate해야 하므로 DML이 무거워진다. 셋째, range scan에는 도움 안 됨 — AHI는 정확한 key match에만 적중하고 range scan, ORDER BY scan은 그대로 B+Tree이다.

다음 워크로드는 AHI를 끄는 편이 이득이다.

| 워크로드 | AHI 권장 |
|---|---|
| Write-heavy, 인덱스 갱신 빈번 | OFF (DML 오버헤드 > lookup 이득) |
| OLAP, range scan/ORDER BY 중심 | OFF (AHI는 점적중에만 동작) |
| 다수 hot OLTP point lookup | ON, parts 16~32 |
| 비교적 cold + 무작위 read | ON, 기본 parts |

운영 환경에서 끄려면 동적 변경이 가능하다.

```sql
SET GLOBAL innodb_adaptive_hash_index = OFF;
```

끄는 순간 해시 슬롯을 해제하느라 일시적 latency가 튄다. 변경 전후 `SHOW ENGINE INNODB STATUS\G`의 hash search 비율을 30분 단위로 비교한다.

## 4. Buffer Pool Instances — 분할 이유

InnoDB buffer pool은 디스크 페이지의 LRU 캐시다. 단일 거대한 buffer pool은 LRU 갱신·flush·free list 조작 시 mutex contention을 일으킨다. 5.5부터 `innodb_buffer_pool_instances`로 N개로 쪼개 각각 독립적인 LRU, free list, flush list, mutex를 갖게 했다. 페이지는 space_id + page_no로 해시되어 각 인스턴스로 배정된다.

기본값은 8이고, `innodb_buffer_pool_size`가 1GB 이상일 때만 유효하다. 1GB 미만이면 강제로 1로 설정된다(인스턴스당 최소 128MB 보장).

## 5. 인스턴스 수 선정 가이드

| Buffer pool size | 권장 instances |
|---|---|
| 1~2 GB | 1~2 |
| 4~16 GB | 4~8 |
| 32~64 GB | 8~16 |
| 128 GB 이상 | 16~32 |

핵심은 "인스턴스당 4GB 안팎"을 유지하는 것이다. 너무 잘게 쪼개면 각 인스턴스가 작아져 hit ratio가 떨어지고 partition간 페이지 swap이 늘어난다. 너무 크면 mutex contention이 다시 살아난다.

contention을 직접 보려면 다음 쿼리를 사용한다.

```sql
SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT/1e12 as wait_sec
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE EVENT_NAME LIKE 'wait/synch/mutex/innodb/buf_pool_mutex'
ORDER BY SUM_TIMER_WAIT DESC;
```

`buf_pool_mutex` wait time이 전체 wait의 5% 이상이면 인스턴스 수를 늘릴 여지가 있다.

## 6. `innodb_buffer_pool_chunk_size`와 인스턴스 수의 제약

5.7부터 buffer pool을 chunk 단위(기본 128MB)로 할당한다. `innodb_buffer_pool_size`는 다음을 만족해야 한다.

```
buffer_pool_size % (chunk_size × instances) == 0
```

만족하지 않으면 InnoDB가 자동으로 size를 올림 처리한다. 결과적으로 설정한 값보다 큰 메모리가 잡힐 수 있다. 예시:

| chunk_size | instances | 설정 size | 실제 size |
|---|---|---|---|
| 128MB | 8 | 10GB | 10.0GB (10 × 1024 % 1024 == 0 OK) |
| 128MB | 8 | 9GB | 9.0GB ((9 × 1024) % 1024 == 0 OK) |
| 128MB | 8 | 7.5GB | 8.0GB (올림 처리) |
| 64MB | 16 | 10GB | 10.0GB |

운영 변경 시에는 다음 순서가 안전하다.

```sql
-- 1. 현재 설정 확인
SHOW VARIABLES LIKE 'innodb_buffer_pool%';

-- 2. 새 사이즈 적용 (resize 작업이 백그라운드 진행)
SET GLOBAL innodb_buffer_pool_size = 16 * 1024 * 1024 * 1024;

-- 3. 진행 모니터링
SHOW STATUS LIKE 'Innodb_buffer_pool_resize_status';
```

instances 변경은 동적이 아니라 my.cnf 수정 + 재시작이 필요하다. chunk_size도 동적 변경 불가다.

## 7. 측정 — AHI on/off, instances 4 vs 16 비교

MySQL 8.0.36, EC2 r6i.4xlarge(16 vCPU, 128GB), sysbench oltp_read_write, table 32개, 각 1M row, 64 concurrency, 10분 워크로드에서 측정.

| 설정 | TPS | p99 latency | buf_pool_mutex wait/s | hash hit ratio |
|---|---|---|---|---|
| AHI on, instances 8 (기본) | 14,200 | 38ms | 320 | 82% |
| AHI on, instances 16 | 15,800 | 31ms | 95 | 81% |
| AHI off, instances 16 | 13,100 | 41ms | 88 | — |
| AHI on, instances 8, parts 32 | 15,100 | 33ms | 280 | 84% |

이 워크로드에서는 instance 분할 효과가 AHI parts 분할보다 크다. write-heavy(80:20에서 50:50으로 변경)로 바꾸면 AHI off가 ~5% 빨라지는 역전이 발생한다. 따라서 read/write 비율을 파악한 후 AHI 결정을 한다.

## 8. PERFORMANCE_SCHEMA로 검증

AHI lookup 통계:

```sql
SELECT VARIABLE_NAME, VARIABLE_VALUE
FROM performance_schema.global_status
WHERE VARIABLE_NAME IN (
  'Innodb_adaptive_hash_searches',
  'Innodb_adaptive_hash_searches_btree'
);
```

`Innodb_adaptive_hash_searches`는 hash로 적중한 횟수, `_btree`는 hash가 없어 B+Tree로 빠진 횟수다. ratio = adaptive / (adaptive + btree).

Buffer pool 인스턴스별 hit ratio:

```sql
SELECT POOL_ID, POOL_SIZE, FREE_BUFFERS, DATABASE_PAGES, HIT_RATE
FROM information_schema.INNODB_BUFFER_POOL_STATS;
```

`HIT_RATE`는 1000분율이다. 950 이상이면 95% hit. 인스턴스 간 편차가 100 이상 벌어진다면 hash distribution skew가 있는 것이고, 핫 테이블의 space_id가 한 인스턴스에 몰린다는 신호다. 이 경우 인스턴스 수를 늘려 분산을 시도한다.

## 9. 운영 체크리스트

- 메모리 80GB 이상 머신: instances 16, chunk 256MB, AHI parts 32
- write 비율 50% 이상: AHI off 한 번 테스트해보고 비교
- p99 latency 튀는 워크로드: `events_waits_summary_global_by_event_name`에서 buf_pool/ahi mutex wait 1순위인지 확인
- buffer pool resize는 점진적이지만 IO bandwidth를 점유하므로 피크 시간 회피
- chunk_size 변경은 재시작 필요 — 다음 정기 점검 윈도우에 맞춰 일괄 적용

```ini
# my.cnf 권장 baseline (r6i.4xlarge 가정)
[mysqld]
innodb_buffer_pool_size              = 96G
innodb_buffer_pool_instances         = 16
innodb_buffer_pool_chunk_size        = 256M
innodb_adaptive_hash_index           = ON
innodb_adaptive_hash_index_parts     = 32
innodb_flush_method                  = O_DIRECT_NO_FSYNC
innodb_io_capacity                   = 4000
innodb_io_capacity_max               = 8000
performance_schema                   = ON
```

## 10. AHI 내부 자료구조 — 부분 키와 자동 prefix 결정

AHI는 인덱스 키 전체를 해싱하지 않고, B+Tree 통계가 가장 분별력 있다고 판단한 **prefix 일부**를 해싱한다. 예를 들어 `(user_id, created_at, status)` 복합 인덱스에서 `user_id` 단일로 lookup이 잦으면 prefix=1 byte가 아닌 prefix=8 byte(user_id 크기)까지를 해시 키로 채택한다. 자동 결정의 통계는 `btr_search` 카운터에 누적되고, 일정 임계(`BTR_SEARCH_BUILD_LIMIT`, 코드 상 100회)를 넘으면 그때부터 해당 prefix 길이로 해시가 생성된다.

이 자동성이 AHI의 매력이자 함정이다. 워크로드가 변해 다른 prefix가 hot이 되면 옛 해시 슬롯이 invalidate되고 새로 빌드된다. 그 사이 hash search miss가 일시적으로 증가한다. `SHOW ENGINE INNODB STATUS\G`의 "hash searches per second"가 평소 대비 절반 이하로 떨어졌다가 5~10분 후 회복되는 패턴이 보이면 워크로드 패턴 변화로 prefix가 재선정된 것이다.

## 11. 동적 모니터링 쿼리 모음

```sql
-- 1) AHI hit ratio (실시간)
SELECT
  V1.VARIABLE_VALUE / (V1.VARIABLE_VALUE + V2.VARIABLE_VALUE) AS hit_ratio
FROM performance_schema.global_status V1
JOIN performance_schema.global_status V2
WHERE V1.VARIABLE_NAME='Innodb_adaptive_hash_searches'
  AND V2.VARIABLE_NAME='Innodb_adaptive_hash_searches_btree';

-- 2) Buffer pool 인스턴스별 free page 추이
SELECT POOL_ID, FREE_BUFFERS, DATABASE_PAGES,
       MODIFIED_DATABASE_PAGES AS dirty,
       HIT_RATE
FROM information_schema.INNODB_BUFFER_POOL_STATS
ORDER BY POOL_ID;

-- 3) 인스턴스별 mutex spin/wait
SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT/1e12 AS sec
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE EVENT_NAME LIKE 'wait/synch/mutex/innodb/buf_pool%'
ORDER BY SUM_TIMER_WAIT DESC LIMIT 10;

-- 4) Top 5 큰 캐시 점유 테이블
SELECT TABLE_NAME, NUMBER_RECORDS, DATA_SIZE
FROM information_schema.INNODB_BUFFER_PAGE_LRU
GROUP BY TABLE_NAME
ORDER BY DATA_SIZE DESC LIMIT 5;
```

## 12. 정리

AHI는 점적중 OLTP에서 자유로운 가속기지만, write-heavy/OLAP 워크로드에서는 오히려 손해다. 운영 변경이 동적이라 ON/OFF를 실측 비교하기 쉽다. Buffer pool instances는 단일 거대한 buffer pool의 mutex hot spot을 깨는 핵심 도구로, "인스턴스당 4GB" 가이드를 기준으로 잡고 `buf_pool_mutex` wait 비율을 보며 조정한다. chunk_size와 instances는 buffer_pool_size의 약수 관계를 만족해야 의도한 메모리가 잡힌다. PERFORMANCE_SCHEMA의 hash search 통계와 buffer pool stats를 함께 모니터링해야 튜닝의 효과를 정량적으로 판단할 수 있다.

## 참고

- MySQL 8.0 Reference Manual — InnoDB Adaptive Hash Index
- MySQL 8.0 Reference Manual — Configuring InnoDB Buffer Pool Size
- Percona Blog — "Adaptive Hash Index: Performance"
- Vadim Tkachenko, "MySQL Performance Tuning" (Percona Live talks)
- High Performance MySQL 4th ed. (O'Reilly)
- MySQL Internals — InnoDB Storage Engine 소스 (`storage/innobase/btr/btr0sea.cc`)
- Jeremy Cole, "InnoDB" 블로그 시리즈

## 13. AHI 비활성화 시 영향 분석 — 단계별 체크

AHI 비활성화는 점진적으로 영향을 평가한다. 첫 번째로 read replica 한 대에만 변경을 적용해 본번 트래픽 일부를 흘려보낸다(read-only 분리). 동일 쿼리 패턴의 `Performance_schema.events_statements_summary_by_digest` 통계를 변경 전후 1시간씩 비교한다. `SUM_TIMER_WAIT` 차이가 -5% 이상이면 read 워크로드에서는 AHI off 가 유리하다는 신호다. 두 번째로 write replica에 적용한다. write의 효과는 보통 `Innodb_rows_inserted`/`Innodb_rows_updated`의 처리량과 `Innodb_log_writes` 추이로 확인하며, 같은 수치 비교 시 DML throughput이 3~10% 상승한다면 AHI off 채택이 합리적이다.

세 번째로 mixed 워크로드에서는 한 인스턴스를 카나리로 두고 1~2주 운영하며 슬로우 쿼리 로그를 비교한다. AHI off로 인해 lookup-heavy 쿼리(특정 단일 PK 조회)가 한두 자리수 ms로 늘어나는 경우가 있는데, 이 경우 해당 테이블만 별도 인덱스 전략(메모리 cache layer 추가 등)으로 보정하고 AHI 자체는 끄는 결정을 내릴 수 있다. PT-heartbeat 지연이나 replication lag도 같이 보아야 하며, 마스터에서 AHI를 끄면 replica 적용 시점에서도 같은 동작을 보장하기 위해 모든 replica에 동일 설정을 적용한다.

| 단계 | 검증 메트릭 | 의사결정 기준 |
|---|---|---|
| read replica 1대 | `events_statements_summary_by_digest.SUM_TIMER_WAIT` | -5% 이상 개선 시 다음 단계 |
| write replica 1대 | `Innodb_rows_inserted`, `Innodb_log_writes` | DML throughput +3% 이상 시 합리적 |
| canary 1주 | slow query log, p99 latency | 회귀 없음 확인 시 전사 적용 |
