Notion 원본: https://app.notion.com/p/3b25a06fd6d38141a824daadd74a0ea4

# MySQL InnoDB 버퍼 풀과 체크포인트 및 리두 로그 그룹 커밋

> 2026-08-04 신규 주제 · 확장 대상: MySQL

## 학습 목표

- 버퍼 풀의 LRU 변형과 페이지 적중률이 쿼리 성능에 미치는 영향을 분석한다
- 더티 페이지 플러시와 퍼지 체크포인트가 쓰기 성능·복구 시간에 주는 trade-off 를 이해한다
- 리두 로그의 WAL 원칙과 LSN 기반 크래시 복구 절차를 추적한다
- 그룹 커밋과 innodb_flush_log_at_trx_commit 조합이 처리량과 내구성에 미치는 영향을 측정한다

## 1. 버퍼 풀 — InnoDB 성능의 심장

InnoDB 는 디스크의 데이터·인덱스 페이지(기본 16KB)를 메모리에 캐싱하는 **버퍼 풀**에서 거의 모든 작업을 수행한다. 쿼리가 요청한 페이지가 버퍼 풀에 있으면(캐시 히트) 디스크 I/O 없이 응답한다. 실무 튜닝의 첫 지표가 버퍼 풀 적중률인 이유다.

```sql
-- 적중률 계산: 1 - (디스크에서 읽은 페이지 / 논리적 읽기 요청)
SELECT
  1 - (
    (SELECT variable_value FROM performance_schema.global_status
       WHERE variable_name = 'Innodb_buffer_pool_reads')
    /
    (SELECT variable_value FROM performance_schema.global_status
       WHERE variable_name = 'Innodb_buffer_pool_read_requests')
  ) AS hit_ratio;
```

`Innodb_buffer_pool_reads` 는 디스크까지 내려간 물리적 읽기, `read_requests` 는 논리적 요청 총량이다. OLTP 워크로드에서 적중률이 99% 미만이면 버퍼 풀이 작거나 워킹셋이 메모리를 초과한다는 신호다. `innodb_buffer_pool_size` 는 전용 DB 서버에서 물리 메모리의 70~80% 를 할당하는 것이 관례다.

버퍼 풀은 내부적으로 `innodb_buffer_pool_instances` 개로 분할된다. 큰 버퍼 풀을 여러 인스턴스로 쪼개면 페이지 관리용 뮤텍스 경합이 분산되어 고동시성에서 확장성이 좋아진다. 각 인스턴스는 독립된 LRU 리스트와 뮤텍스를 가진다.

## 2. 변형된 LRU — 스캔 저항성

순수 LRU 는 큰 테이블 풀 스캔 한 번에 캐시 전체가 오염된다. 자주 쓰이는 페이지가 일회성 스캔 페이지에 밀려 쫓겨나기 때문이다. InnoDB 는 LRU 리스트를 **young(신규)** 과 **old(예전)** 두 구역으로 나눠 이를 방어한다.

새로 읽은 페이지는 리스트 head 가 아니라 old 구역의 head(전체의 약 37% 지점, `innodb_old_blocks_pct`)에 삽입된다. 그 페이지가 `innodb_old_blocks_time`(ms) 이후 다시 접근되어야만 young 구역으로 승격된다. 풀 스캔으로 한 번만 읽히는 페이지는 old 에 머물다 빠르게 방출되어, young 구역의 hot 페이지를 보호한다.

```sql
-- old 구역 체류 시간을 늘려 대량 스캔의 캐시 오염을 더 강하게 차단
SET GLOBAL innodb_old_blocks_time = 1000;   -- 기본 1000ms
SET GLOBAL innodb_old_blocks_pct  = 37;     -- old 구역 비율
```

배치 작업이 야간에 큰 테이블을 스캔해 낮 시간 OLTP 캐시를 날려버리는 문제는 이 파라미터로 완화된다. 읽기 성능이 아침에 갑자기 나빠지는 패턴이면 LRU 오염을 의심한다.

## 3. 더티 페이지와 WAL — 쓰기는 로그가 먼저

버퍼 풀에서 수정된 페이지는 즉시 디스크에 반영되지 않는다. 메모리에서만 바뀐 채 **더티 페이지**로 남고, 데이터 파일 갱신은 나중에 몰아서 한다. 대신 변경 사실은 **리두 로그(redo log)** 에 먼저 순차 기록된다. 이것이 WAL(Write-Ahead Logging) 원칙이다. "데이터 페이지보다 로그를 먼저 내구화한다."

랜덤 위치의 데이터 페이지를 매번 fsync 하면 디스크 랜덤 쓰기가 폭증한다. 대신 리두 로그는 append-only 순차 쓰기라 훨씬 빠르다. 커밋 시점에 비싼 랜덤 쓰기(데이터 파일)를 피하고 값싼 순차 쓰기(리두 로그)만 보장하는 것이 InnoDB 쓰기 성능의 근간이다.

리두 로그에서 위치를 가리키는 단조 증가 값이 **LSN(Log Sequence Number)** 이다. 모든 페이지는 자신이 마지막으로 반영한 리두 로그 위치를 `page LSN` 으로 기록한다. 복구 시 이 LSN 을 비교해 어떤 변경을 재적용할지 판단한다.

```sql
SHOW ENGINE INNODB STATUS\G
-- LOG 섹션 예시
-- Log sequence number       : 최신 리두 로그 위치 (버퍼 풀 기준)
-- Log flushed up to         : 디스크에 fsync 된 리두 위치
-- Pages flushed up to       : 데이터 파일에 반영된 페이지 LSN
-- Last checkpoint at        : 마지막 체크포인트 LSN
```

`Log sequence number` 와 `Last checkpoint at` 의 차이가 **체크포인트 에이지**다. 이 값이 리두 로그 총량에 근접하면 InnoDB 가 플러시를 강제로 가속(furious flushing)해 사용자 쓰기가 지연된다.

## 4. 퍼지 체크포인트 — 복구 시간과 쓰기 부하의 균형

체크포인트는 특정 LSN 까지의 더티 페이지를 데이터 파일에 반영하고 "이 지점 이전은 복구 시 다시 볼 필요 없음"을 표시하는 작업이다. InnoDB 는 서비스를 멈추지 않는 **퍼지(fuzzy) 체크포인트**를 쓴다. 더티 페이지를 백그라운드에서 조금씩 지속적으로 플러시한다.

플러시 속도는 균형의 문제다. 너무 느리면 더티 페이지가 쌓여 리두 로그 공간이 부족해지고, 크래시 시 복구할 로그가 많아 재시작이 느려진다. 너무 빠르면 데이터 파일 랜덤 쓰기가 늘어 사용자 쿼리와 I/O 대역을 다툰다.

```sql
-- 리두 로그 총 용량. 크면 더티 페이지를 오래 모아 랜덤 쓰기를 줄이지만
-- 크래시 복구 시간이 길어진다
SET GLOBAL innodb_redo_log_capacity = 4294967296;   -- 4GB (8.0.30+)

-- 버퍼 풀 중 더티 비율이 이 값을 넘으면 플러시 가속
SET GLOBAL innodb_max_dirty_pages_pct = 75;

-- 스토리지 IOPS 상한 힌트. SSD 면 높게, HDD 면 낮게
SET GLOBAL innodb_io_capacity     = 2000;
SET GLOBAL innodb_io_capacity_max = 4000;
```

`innodb_io_capacity` 는 InnoDB 가 백그라운드 플러시에 쓸 수 있는 IOPS 예산이다. NVMe SSD 에서 기본값(200)을 그대로 두면 플러시가 스토리지 성능을 못 따라가 더티 페이지가 적체된다. 스토리지 실측 IOPS 의 절반 정도를 기준으로 잡는다.

## 5. 크래시 복구 — redo 재적용과 undo 롤백

전원이 꺼졌다 재시작하면 InnoDB 는 두 단계로 일관성을 복원한다. 먼저 **redo 단계**: 마지막 체크포인트 LSN 부터 리두 로그 끝까지 순회하며, 각 페이지의 `page LSN` 과 로그 레코드 LSN 을 비교해 아직 반영되지 않은 변경을 데이터 페이지에 재적용한다. 커밋 여부와 무관하게 물리적 변경을 전부 되살린다.

그다음 **undo 단계**: redo 로 되살린 것 중 커밋되지 않은 트랜잭션을 언두 로그로 롤백한다. 언두 로그는 각 행의 이전 버전을 담고 있어, MVCC 의 일관된 읽기와 롤백 양쪽에 쓰인다.

```
[체크포인트 LSN] ────redo 재적용────> [로그 끝 LSN]
                                          │
                                          v
                            커밋 안 된 트랜잭션은 undo 로 되돌림
```

리두 로그가 클수록 체크포인트 간격이 벌어져 복구할 로그 구간이 길어지고 재시작이 느려진다. 반대로 작으면 잦은 체크포인트로 정상 운영 중 쓰기 부하가 는다. 이것이 리두 로그 용량 결정의 핵심 trade-off 다.

## 6. 그룹 커밋과 내구성 옵션

수많은 트랜잭션이 각각 리두 로그를 fsync 하면 디스크 fsync 호출이 병목이 된다. InnoDB 는 **그룹 커밋**으로 짧은 시간 창에 도착한 여러 트랜잭션의 커밋을 모아 한 번의 fsync 로 처리한다. 동시성이 높을수록 fsync 당 커밋 수가 늘어 처리량이 오른다.

내구성의 실제 강도는 `innodb_flush_log_at_trx_commit` 이 결정한다.

| 값 | 커밋 시 동작 | 내구성 | 처리량 |
| --- | --- | --- | --- |
| 1 (기본) | 매 커밋마다 리두 로그 write + fsync | 커밋 손실 없음 (ACID) | 낮음 |
| 2 | 매 커밋마다 write, fsync 는 1초 주기 | OS 크래시 시 최대 1초 손실 | 높음 |
| 0 | write·fsync 모두 1초 주기 | mysqld 크래시 시에도 손실 | 가장 높음 |

값 1 이 진짜 ACID 내구성이다. 은행 거래처럼 커밋 손실이 허용되지 않으면 반드시 1 을 쓴다. 값 2 는 mysqld 프로세스가 죽어도(로그가 OS 페이지 캐시에 있으므로) 데이터가 살아남지만, 서버 전체가 정전으로 꺼지면 최대 1초 손실이 난다. 로그성 데이터나 재생성 가능한 데이터에 유용하다.

```sql
-- 처리량 우선 (약간의 손실 허용)
SET GLOBAL innodb_flush_log_at_trx_commit = 2;

-- 바이너리 로그와의 그룹 커밋 지연을 늘려 배치 효율 상승
SET GLOBAL binlog_group_commit_sync_delay = 1000;   -- 마이크로초
```

`binlog_group_commit_sync_delay` 를 수백~수천 마이크로초로 주면 커밋을 일부러 살짝 지연시켜 더 많은 트랜잭션을 한 그룹으로 묶는다. 개별 지연은 늘지만 전체 처리량이 오른다. 복제 환경에서 바이너리 로그 fsync 부하를 줄이는 데 특히 효과적이다.

## 7. 실측으로 확인하기

옵션 변경의 효과는 반드시 벤치마크로 검증한다. `sysbench` 로 OLTP 쓰기 워크로드를 돌려 옵션별 TPS 를 비교한다.

```bash
# trx_commit=1 (내구성) vs 2 (처리량) 비교
sysbench oltp_write_only \
  --db-driver=mysql --mysql-db=bench \
  --tables=10 --table-size=1000000 \
  --threads=64 --time=120 run

# 관찰 지표
# - Innodb_os_log_fsyncs : fsync 총 호출 수 (그룹 커밋 효율 반비례)
# - Innodb_data_written  : 데이터 파일 쓰기 바이트
```

`Innodb_os_log_fsyncs` 를 커밋 수로 나누면 "fsync 당 커밋 수"가 나온다. 이 값이 1 에 가까우면 그룹 커밋이 거의 작동하지 않는 것이고, 동시성을 높이거나 `sync_delay` 를 주면 값이 커지면서 fsync 호출이 줄어든다.

## 8. 모니터링 체크리스트

정상 운영 지표로 `Innodb_buffer_pool_wait_free`(버퍼 풀에 빈 페이지가 없어 대기한 횟수)가 0 에 가까워야 한다. 0 이 아니면 플러시가 페이지 확보 속도를 못 따라가는 것이다. `Innodb_log_waits`(로그 버퍼 공간 부족 대기)가 증가하면 `innodb_log_buffer_size` 를 키운다. `SHOW ENGINE INNODB STATUS` 의 체크포인트 에이지가 리두 용량의 75% 를 지속적으로 넘으면 리두 로그를 키우거나 io_capacity 를 올린다.

## trade-off 정리

버퍼 풀은 크게 잡을수록 적중률이 오르지만, 지나치면 OS·다른 프로세스 메모리를 압박해 스와핑을 유발한다. 리두 로그는 크면 랜덤 쓰기를 줄여 처리량이 좋아지지만 복구 시간이 길어진다. `flush_log_at_trx_commit=1` 은 내구성을 보장하지만 fsync 병목을 만들고, 2 는 처리량을 얻는 대신 정전 손실을 감수한다. 워크로드의 데이터 가치와 SLA 를 기준으로 이 축들을 조율하는 것이 InnoDB 튜닝의 본질이다.

## 참고

- MySQL 8.0 Reference Manual — InnoDB Buffer Pool, Redo Log, Checkpoints
- MySQL Documentation — innodb_flush_log_at_trx_commit, Group Commit
- 『High Performance MySQL, 4th Edition』 (O'Reilly)
- Jeremy Cole — InnoDB architecture blog series (blog.jcole.us)
