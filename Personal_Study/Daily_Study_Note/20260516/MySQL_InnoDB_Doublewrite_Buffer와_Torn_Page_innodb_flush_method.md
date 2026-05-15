Notion 원본: https://www.notion.so/3615a06fd6d381e3a85ec5abd5f6e0fc

# MySQL InnoDB Doublewrite Buffer와 Torn Page — innodb_flush_method 트레이드오프

> 2026-05-16 신규 주제 · 확장 대상: MySQL / InnoDB

## 학습 목표

- InnoDB 의 페이지 쓰기가 OS/디스크 단위와 어긋날 때 발생하는 torn page(partial page write) 문제와 영향을 설명한다
- Doublewrite Buffer 의 2단계 쓰기 흐름과 crash recovery 시 복구 로직을 추적한다
- `innodb_doublewrite`, `innodb_flush_method`, `innodb_flush_neighbors`, `innodb_doublewrite_pages` 옵션의 상호 작용을 정량 비교한다
- atomic write 가능한 디바이스/파일시스템에서 doublewrite 를 안전하게 비활성화하는 조건을 식별한다

## 1. InnoDB 페이지와 OS/디스크 단위 불일치

InnoDB 는 데이터를 *페이지* 단위(기본 16KB) 로 관리한다. 한 페이지는 헤더, B+Tree 노드, 인덱스 레코드, trailer 로 구성되며 trailer 의 *체크섬* 필드에 페이지 전체의 무결성 해시가 저장된다.

문제는 디스크 I/O 의 원자성 단위가 *대부분의 환경에서 4KB sector* 라는 점이다. OS 의 write(2) 호출은 atomic 이 아니다. 16KB 페이지를 디스크에 쓰는 도중 정전/커널 패닉이 발생하면, 4개 sector 중 일부만 디스크에 반영되고 나머지는 이전 값으로 남는다. 결과는 *torn page*: 헤더는 새 데이터지만 트레일러는 옛 체크섬, 또는 그 반대.

torn page 가 InnoDB 의 redo log 로 복구되지 않는 이유는 *redo 가 increment 가 아닌 logical 작업* 이라는 점이다. redo 는 "row N 의 column C 를 V 로 set" 같은 형식인데, 적용하려면 시작 상태의 페이지가 *완전한* 상태여야 한다. 깨진 페이지에서 시작해 redo 를 적용하면 결과가 엉뚱하다.

## 2. Doublewrite Buffer 의 2단계 쓰기

해결책: 본 데이터 영역에 쓰기 전에 *별도의 연속 영역*에 먼저 동일 페이지를 한 번 더 써둔다. 이걸 doublewrite buffer 라 한다.

```
[변경된 페이지들]
       ↓ 1단계: doublewrite buffer 영역(ibdata 또는 .dblwr)에 sequential write
       ↓ fsync 1
       ↓ 2단계: 실제 tablespace 의 페이지 위치에 random write
       ↓ fsync 2
       ↓ doublewrite buffer 의 해당 entry 를 free 로 표시
```

순서가 핵심이다.

1. 페이지를 doublewrite buffer 에 *연속*적으로 sequential write — 1단계.
2. fsync 로 강제 디스크 반영.
3. 본 위치에 random write — 2단계.
4. fsync 로 강제 반영.
5. doublewrite buffer 슬롯을 free.

각 단계 후 fsync 가 있으므로 어느 시점에 crash 가 일어나도 다음 셋 중 하나의 상태다.

- 1단계 fsync 전: doublewrite buffer 는 미반영 → 무시. 원본 페이지는 여전히 *완전한 이전 상태*. redo 로 복구 가능.
- 1단계 fsync 후 / 2단계 도중: doublewrite buffer 는 *완전한 새 페이지*. 원본은 torn 가능. → 복구 시 doublewrite 에서 페이지를 *그대로 복사*해 원본을 복원, 이후 redo 적용.
- 2단계 fsync 후: 두 위치 모두 완전. → 그대로 정상 동작.

doublewrite buffer 자체가 partial write 가 되면 어떻게 되나? doublewrite 영역에는 *checksum* 이 있으므로 깨진 entry 는 recovery 시 무시한다. 원본이 완전한 이전 상태로 남아 있으므로 redo 만으로 복구된다.

## 3. 구조와 위치 변천

| 버전 | doublewrite 위치 |
|---|---|
| 5.7 이하 | `ibdata1` 시스템 테이블스페이스 안에 고정 영역 (128 페이지 = 2MB) |
| 8.0.20+ | 기본적으로 별도 파일 `#ib_16384_0.dblwr`, `#ib_16384_1.dblwr` 등으로 분리 |
| 8.0.20+ | `innodb_doublewrite_dir`, `innodb_doublewrite_files`, `innodb_doublewrite_pages` 옵션 추가 |

8.0.20 이후 분리된 이유는 *동시성*이다. `innodb_doublewrite_pages` 는 한 batch 의 페이지 수.

## 4. innodb_flush_method 옵션

| 값 | 의미 | 비고 |
|---|---|---|
| `fsync` (Linux 기본) | 각 페이지 write 후 `fsync()` 호출 | 가장 호환성 높음 |
| `O_DSYNC` | open 시 `O_SYNC` 플래그로 메타데이터 제외 동기 쓰기 | 일부 환경에서 unstable |
| `O_DIRECT` | OS page cache 우회. write 가 직접 디스크로 | InnoDB buffer pool 이 이미 캐시 역할이라 *이중 캐싱* 방지 |
| `O_DIRECT_NO_FSYNC` | O_DIRECT + fsync 생략. 디스크가 atomic write 보장할 때만 안전 | 8.0+ |
| `nosync` | fsync 생략 (테스트용 — 운영 금지) | 데이터 손실 위험 |

권장: 일반 SSD/NVMe 환경에서 `O_DIRECT`. 일부 워크로드에서 throughput 이 10~20% 개선되는 사례가 보고된다(Percona 벤치마크).

## 5. 성능 비용 정량 분석

doublewrite 는 *디스크 쓰기 양을 정확히 2배로* 늘린다. 다만 doublewrite 영역은 *연속된 sequential write* 라서 SSD/NVMe 에서 비용이 random write 만큼 크지 않다. HDD 시절에는 25~30% throughput 감소, 현대 NVMe 에서는 5~10% 수준이 일반적이다.

sysbench OLTP write workload 실측 예시 (Percona, 8.0.32, NVMe):

| 설정 | TPS | p99 lat (ms) | 디스크 write MB/s |
|---|---|---|---|
| doublewrite=ON, batch=128 | 14,200 | 38 | 220 |
| doublewrite=ON, batch=256 | 15,100 | 35 | 220 |
| doublewrite=OFF | 16,300 | 28 | 110 |

doublewrite=OFF 가 명백히 빠르다. 하지만 끄는 건 *디바이스가 16KB atomic write 를 보장*할 때만 안전하다.

## 6. Atomic Write 디바이스와 doublewrite 비활성화 조건

| 환경 | 조건 |
|---|---|
| FusionIO / Intel PMem | 16KB atomic write 지원 명시 |
| ZFS / btrfs (CoW 파일시스템) | CoW 가 page 단위 atomic 을 보장 |
| MariaDB Galera Cluster | 다른 노드에서 복구 가능 (단일 노드 torn 도 보완) |
| 일부 NVMe + Linux 5.x + 실험적 옵션 | 일부 파일시스템에서 16KB atomic 가능 |

MySQL 8.0.21+ 의 `innodb_doublewrite=DETECT_AND_DISABLE` 옵션. 검증 통과 시 비활성화, 실패 시 ON 으로 유지.

운영 권장: *확신 없으면 doublewrite=ON 유지*. 5~10% 성능 손실은 데이터 무결성 가치에 비하면 작다.

## 7. crash recovery 시퀀스 상세

```
1. redo log 의 LSN(Log Sequence Number) 마지막 체크포인트 위치 확인
2. doublewrite buffer 영역 전체 스캔, 각 entry 의 page id + checksum 검증
3. 각 entry 마다 원본 위치 페이지를 읽어 checksum 검증
4. 원본이 torn(checksum mismatch) → doublewrite 의 정상 페이지로 덮어쓰기
5. redo log 를 시작 LSN 부터 끝까지 순차 적용
6. 미커밋 트랜잭션을 undo log 로 rollback
7. binary log 와 redo log 의 commit 일관성 검증 (XA 2PC)
```

doublewrite 가 없다면 4단계가 불가능하고, redo 가 깨진 페이지 위에 적용되면 *데이터 corrupted* 상태.

## 8. 운영 진단과 메트릭

| 메트릭 | 의미 |
|---|---|
| `Innodb_dblwr_pages_written` | 누적 doublewrite 페이지 수 |
| `Innodb_dblwr_writes` | doublewrite batch 횟수 |
| `Innodb_buffer_pool_pages_flushed` | flush 된 buffer pool 페이지 수 |
| `innodb_doublewrite_pages` (var) | batch 당 페이지 한계 |

평균 batch 페이지 수 = `pages_written / writes`. 너무 낮으면(예: 5~10) batch 가 잘 차지 않는 것.

OS 측 진단:

```bash
iostat -x 1
# %util 100% 이면 디스크 포화

vmstat 1
# bi/bo 로 block in/out 모니터링
```

`Innodb_buffer_pool_pages_dirty` / `Innodb_buffer_pool_pages_total` 비율을 75% 이하로 유지하는 게 안전선.

## 9. 결론과 운영 권장 설정

```ini
innodb_doublewrite = ON
innodb_doublewrite_pages = 128
innodb_flush_method = O_DIRECT
innodb_flush_neighbors = 0
innodb_io_capacity = 2000
innodb_io_capacity_max = 4000
innodb_redo_log_capacity = 8G
innodb_buffer_pool_size = 24G
innodb_use_native_aio = ON
```

doublewrite 를 *끄는 결정* 은 매우 신중해야 한다. 다음을 모두 만족할 때만:

1. 디바이스 매뉴얼에 16KB atomic write 보장 명시
2. ZFS/btrfs 같은 CoW 파일시스템 사용 또는 PMem
3. 정기 *crash recovery 드릴*로 검증 완료
4. binlog + GTID 기반 point-in-time recovery 인프라 보유

## 10. binlog / redo / doublewrite 의 합동 동작과 group commit

InnoDB 의 트랜잭션 durability 는 doublewrite 만으로 완성되지 않는다. 8.0+ 의 commit 시퀀스:

```
1. prepare phase: InnoDB redo log 에 prepare 상태 기록 + fsync
2. binlog ordered commit: binlog 에 트랜잭션 기록 (group commit 가능)
3. binlog fsync (sync_binlog=1)
4. commit phase: InnoDB redo log 에 commit 마크 + fsync
5. dirty page flush 는 비동기 (Checkpoint 시점에 doublewrite 경유)
```

여기서 *group commit* 은 여러 트랜잭션의 fsync 를 한 번에 묶어 throughput 을 끌어올린다. `binlog_group_commit_sync_delay` 와 `binlog_group_commit_sync_no_delay_count` 가 그룹 크기를 제어.

```ini
sync_binlog = 1
innodb_flush_log_at_trx_commit = 1
binlog_group_commit_sync_delay = 1000
```

ACID-D 가 필요 없는 경우 `innodb_flush_log_at_trx_commit=2` 로 redo fsync 가 1초당 1회로 감소.

## 참고

- MySQL 8.0 Reference Manual — InnoDB On-Disk Structures (https://dev.mysql.com/doc/refman/8.0/en/innodb-on-disk-structures.html)
- Percona Blog — Atomic Writes and InnoDB Doublewrite Buffer
- Yasufumi Kinoshita, "InnoDB Doublewrite Buffer Internals" (MySQL Connect 발표)
- Mark Callaghan, "Small Datum" 블로그 — InnoDB I/O 벤치마크 시리즈
- Linux 커널 문서 — Atomic Write Operations on Block Devices
