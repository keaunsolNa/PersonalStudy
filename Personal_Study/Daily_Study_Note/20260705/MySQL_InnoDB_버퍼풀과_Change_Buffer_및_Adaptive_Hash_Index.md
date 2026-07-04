Notion 원본: https://app.notion.com/p/3935a06fd6d3817091dcef2dcd0606b8

# MySQL InnoDB 버퍼풀과 Change Buffer 및 Adaptive Hash Index

> 2026-07-05 신규 주제 · 확장 대상: Oracle

## 학습 목표

- InnoDB 버퍼풀의 LRU 변형(Young/Old 서브리스트) 동작과 midpoint insertion 전략을 설명한다
- Change Buffer 가 세컨더리 인덱스의 랜덤 쓰기를 어떻게 지연·병합하는지 파악한다
- Adaptive Hash Index(AHI) 가 B+Tree 탐색을 언제 단축하고 언제 역효과를 내는지 판단한다
- 버퍼풀 인스턴스 분할과 innodb_buffer_pool_size 튜닝의 실측 기준을 세운다

## 1. 버퍼풀은 왜 단순 LRU 가 아닌가

InnoDB 버퍼풀은 디스크의 16KB 페이지를 메모리에 캐싱하는 핵심 구조다. 순수 LRU 를 쓰면 야간 배치가 큰 테이블을 풀스캔하면 한 번만 읽고 버릴 페이지가 hot 페이지를 밀어내 캐시가 오염된다. 이를 막기 위해 InnoDB 는 LRU 리스트를 **Young(new) 서브리스트**와 **Old(old) 서브리스트**로 나눈다.

새로 읽은 페이지는 리스트의 맨 앞이 아니라 old/young 경계인 **midpoint**(기본값 5/8 지점)에 삽입된다. `innodb_old_blocks_pct` 가 이 비율을 결정하며 기본 37%다. Old 서브리스트에 들어온 페이지가 `innodb_old_blocks_time`(기본 1000ms) 안에 다시 접근되면 풀스캔의 연속 읽기로 간주해 young 으로 승격시키지 않는다.

```sql
SET GLOBAL innodb_old_blocks_pct = 20;
SET GLOBAL innodb_old_blocks_time = 1000;
SHOW ENGINE INNODB STATUS\G
```

`Innodb_buffer_pool_reads`(디스크에서 실제로 읽은 페이지)와 `Innodb_buffer_pool_read_requests`(논리 읽기 요청)의 비율이 캐시 효율의 1차 지표다. reads 가 requests 대비 1% 를 넘으면 워킹셋을 버퍼풀이 담지 못하는 신호다.

## 2. 버퍼풀 인스턴스와 뮤텍스 경합

버퍼풀은 LRU·free·flush 리스트를 보호하는 뮤텍스로 직렬화된다. 코어가 많은 서버에서 단일 버퍼풀은 이 뮤텍스가 병목이 된다. `innodb_buffer_pool_instances` 로 버퍼풀을 여러 인스턴스로 쪼개면 각 인스턴스가 독립 뮤텍스와 리스트를 가져 경합이 분산된다. 페이지는 space_id 와 페이지 번호의 해시로 결정적 매핑된다.

실무 기준은 버퍼풀이 1GB 이상일 때만 분할이 의미 있고, 각 인스턴스가 최소 1GB 가 되도록 개수를 정한다. MySQL 8.0 은 총량 1GB 미만이면 인스턴스를 강제로 1로 만든다.

| 파라미터 | 기본값 | 튜닝 방향 |
|---|---|---|
| innodb_buffer_pool_size | 128MB | 물리 RAM 의 50~75% |
| innodb_buffer_pool_instances | 1(8.0) | (풀 GB)/1GB, 최대 64 |
| innodb_old_blocks_pct | 37 | 풀스캔 방어 시 20 내외 |
| innodb_buffer_pool_chunk_size | 128MB | 온라인 리사이즈 단위 |

MySQL 8.0 은 `innodb_buffer_pool_size` 를 온라인 변경할 수 있는데, 실제 변경은 `chunk_size × instances` 단위로 이뤄진다.

## 3. Change Buffer — 세컨더리 인덱스 랜덤 쓰기의 지연 병합

세컨더리 인덱스는 값 순서가 PK 순서와 달라 삽입·삭제가 디스크 곳곳에 흔어진 랜덤 I/O 를 만든다. Change Buffer 는 해당 세컨더리 인덱스 페이지가 **버퍼풀에 없을 때**, 변경을 즉시 디스크에 반영하지 않고 버퍼링해 둔다가 그 페이지가 나중에 읽힐 때 한꺼번에 **merge** 한다. 유니크 인덱스는 중복 검사를 위해 어차피 페이지를 읽어야 하므로 적용되지 않는다.

```sql
SET GLOBAL innodb_change_buffering = 'all';
SET GLOBAL innodb_change_buffer_max_size = 25;
```

SSD 에서는 랜덤 I/O 가 저렴해 이득이 작고 merge CPU 비용만 남으므로 낮추거나 none 이 나을 수 있다. HDD·대용량 벌크 로드에서는 극적 이득이 있다.

## 4. Adaptive Hash Index — B+Tree 위의 자동 해시 단축로

InnoDB 는 자주 조회되는 프리픽스에 **해시 인덱스를 자동 구축**해 해시 조회 한 번으로 리프 위치를 얻어 트리 순회를 건너뛴다. 등가 조회 반복 OLTP 에 효과적이다. 다만 AHI 도 `btr_search_latch` 로 보호되고, 다양한 조회 패턴이 섞이면 해시 구축·파기가 반복돼 경합과 오버헤드를 만든다.

```sql
SET GLOBAL innodb_adaptive_hash_index = OFF;
-- SHOW ENGINE INNODB STATUS: hash searches/s vs non-hash searches/s
```

`non-hash search` 비중이 큰데 CPU 가 높으면 AHI 를 꺼서 A/B 비교한다. 8.0 은 `innodb_adaptive_hash_index_parts`(8) 로 latch 를 분할했지만 부적합 워크로드의 근본 오버헤드는 남는다.

## 5. 더티 페이지 플러시와 체크포인트

더티 페이지는 flush 리스트에 oldest LSN 순으로 관리되고, LRU flushing 과 checkpoint flushing 두 경로로 디스크에 반영된다. `innodb_max_dirty_pages_pct`(기본 90%)를 넘으면 플러시가 공격적으로 변한다. Adaptive flushing 은 redo 생성 속도를 추적해 플러시 속도를 조절하며, `innodb_io_capacity`(200)/`io_capacity_max` 가 상한을 정한다. SSD 는 2000/4000 수준으로 올리되 실제 IOPS 의 절반~2/3 을 상한으로 잡는다.

## 6. 버퍼풀 워밍업과 프리로드

재시작 후 콜드 캐시를 피하려 InnoDB 는 종료 시 페이지 목록을 `ib_buffer_pool` 로 덤프하고 시작 시 백그라운드 로드한다.

```sql
SET GLOBAL innodb_buffer_pool_dump_at_shutdown = ON;
SET GLOBAL innodb_buffer_pool_load_at_startup = ON;
SET GLOBAL innodb_buffer_pool_dump_pct = 40;
```

`dump_pct` 를 100 으로 두면 완전하지만 로드 I/O 가 커지고, 상위 40% 면 워킹셋으로 충분한 경우가 많다.

## 7. 관측 지표와 튜닝 순서

튜닝은 측정→가설→단일 변수 변경→재측정 순으로 한다.

| 증상 | 지표 | 조치 |
|---|---|---|
| 콜드 캐시 후 느림 | reads 급증 | dump/load 활성화 |
| 멀티코어 확장 안 됨 | buffer pool mutex 대기 | instances 증가 |
| CPU 높고 정체 | non-hash search 비율 높음 | AHI OFF 후 비교 |
| 주기적 지연 스파이크 | flush storm, checkpoint age 급등 | io_capacity_max 상향 |

## 참고

- MySQL 8.0 Reference Manual — InnoDB Buffer Pool, Change Buffer, Adaptive Hash Index
- Jeremy Cole, "InnoDB: A journey to the core" 블로그 시리즈
- High Performance MySQL, 4th Edition (O'Reilly)
