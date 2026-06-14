Notion 원본: https://www.notion.so/37f5a06fd6d381ae8407f724e902d121

# MySQL InnoDB Redo Log WAL과 Doublewrite Buffer 및 Group Commit

> 2026-06-14 신규 주제 · 확장 대상: Oracle / SQLD

## 학습 목표

- WAL 원칙과 redo log 의 LSN, 체크포인트 관계를 추적한다
- doublewrite buffer 가 partial page write 를 막는 메커니즘을 설명한다
- `innodb_flush_log_at_trx_commit` 세 값의 내구성·성능 trade-off 를 구분한다
- binlog 와 redo 의 group commit 2-phase 흐름을 단계별로 설명한다

## 1. WAL: 로그 먼저, 데이터는 나중에

InnoDB 는 Write-Ahead Logging 원칙을 따른다. 데이터 페이지의 변경을 디스크의 실제 테이블스페이스(.ibd)에 즉시 반영하는 대신, 변경 내용을 redo log 에 먼저 순차 기록(append)하고 commit 을 끝낸다. 실제 더티 페이지는 버퍼 풀에 남아 있다가 나중에 백그라운드로 천천히 디스크에 내려간다. 이렇게 하는 이유는 랜덤 I/O 인 데이터 페이지 쓰기를 순차 I/O 인 로그 쓰기로 대체해 commit 지연을 줄이기 위함이다. 장애가 나도 redo log 만 있으면 커밋된 변경을 재적용(roll-forward)해 복구할 수 있다.

redo log 는 모든 변경에 단조 증가하는 LSN(Log Sequence Number)을 부여한다. LSN 은 redo log 에 쓰인 바이트 누적량에 대응하며, 각 데이터 페이지 헤더에도 그 페이지에 마지막으로 적용된 변경의 LSN 이 기록된다. 복구 시 InnoDB 는 페이지의 LSN 과 redo 레코드의 LSN 을 비교해, 페이지에 아직 반영되지 않은 변경만 골라 재적용한다.

## 2. 체크포인트와 redo log 공간 재활용

redo log 는 고정 크기 파일을 원형(circular)으로 재사용한다. 따라서 오래된 로그 영역을 덮어쓰려면 그 로그가 가리키는 더티 페이지가 이미 디스크에 안전하게 내려가 있어야 한다. 이 안전선을 표시하는 것이 체크포인트다. checkpoint LSN 이전의 변경은 모두 데이터 파일에 반영되었음을 의미하므로, 복구는 checkpoint LSN 부터 redo 끝까지만 스캔하면 된다.

문제는 더티 페이지 flush 가 늦어 checkpoint 가 진전되지 못할 때 발생한다. redo 가 가득 차면 InnoDB 는 강제로 flush 를 몰아치는 furious flushing 에 들어가 처리량이 급락한다. 이를 피하려면 redo log 용량(`innodb_redo_log_capacity`, 8.0.30+)을 충분히 키워 체크포인트에 여유를 줘야 한다. 일반적으로 피크 시간 1시간 동안 생성되는 redo 양 정도를 기준으로 잡는다.

```sql
-- 1분 간격 LSN 증가량으로 redo 생성 속도 측정
SHOW ENGINE INNODB STATUS\G   -- LOG 섹션의 "Log sequence number" 관찰
-- 8.0.30 이상: 동적 변경 가능
SET GLOBAL innodb_redo_log_capacity = 4 * 1024 * 1024 * 1024; -- 4GB
```

## 3. doublewrite buffer: partial page write 방어

InnoDB 페이지는 기본 16KB 인데, OS·디스크의 원자적 쓰기 단위(보통 4KB 섹터)는 그보다 작다. 16KB 페이지를 쓰는 도중 전원 장애가 나면 페이지의 앞 8KB 는 새 내용, 뒤 8KB 는 옆 내용인 찢어진 페이지(torn page)가 디스크에 남을 수 있다. redo log 는 "이 페이지의 이 부분을 이렇게 바꿔라"는 물리-논리 변경이라 손상된 페이지 위에 그대로 재적용해도 올바른 결과를 보장하지 못한다. 즉 복구의 출발점인 페이지 자체가 깨지면 redo 로도 못 고친다.

doublewrite buffer 는 이를 막는다. 더티 페이지를 .ibd 의 최종 위치에 쓰기 전에, 먼저 doublewrite 영역(연속된 시스템 공간)에 순차로 한 번 기록하고 fsync 한 뒤, 그다음 본래 위치에 쓴다. 복구 시 본래 위치 페이지의 체크섬이 깨져 있으면 doublewrite 영역의 온전한 사본으로 복원한다. "두 번 쓰지만 한 번은 순차 쓰기"라 비용이 생각보다 크지 않으며, 본래 페이지가 정상이면 doublewrite 사본은 그냥 버려진다.

```sql
SHOW VARIABLES LIKE 'innodb_doublewrite';        -- ON 권장
-- Fusion-io 등 atomic write 를 하드웨어가 보장하면 OFF 로 비용 절감 가능
SHOW STATUS LIKE 'Innodb_dblwr_pages_written';   -- doublewrite 활동량 모니터
```

ZFS 처럼 copy-on-write 로 partial write 가 구조적으로 불가능한 파일시스템이나 atomic write 를 지원하는 디바이스라면 doublewrite 를 꺼도 안전하다. 그 외 일반 환경에서는 반드시 켜 두어야 한다.

## 4. innodb_flush_log_at_trx_commit 의 세 값

이 변수는 commit 시 redo log 를 어디까지 강제하느냐를 정한다. 내구성과 처리량을 직접 맞바꾸는 가장 중요한 설정이다.

| 값 | commit 시 동작 | 장애 시 손실 | 처리량 |
|----|--------------|------------|--------|
| 1 (기본) | redo 를 OS 로 write + fsync | 손실 없음(ACID 완전) | 가장 낮음 |
| 2 | OS 로 write 만(fsync 는 1초 주기) | OS 정상·서버 크래시: 손실 없음 / OS·전원 장애: 최대 1초 | 중간 |
| 0 | 1초 주기로 write + fsync | mysqld 크래시 포함 최대 1초 | 가장 높음 |

값 1 은 매 commit 마다 디스크 fsync 를 보장하므로 가장 안전하지만 디스크 IOPS 에 직접 묶인다. 값 2 는 commit 시 OS 페이지 캐시까지만 쓰므로 mysqld 프로세스가 죽어도 OS 가 살아 있으면 데이터가 보존되지만, 호스트 전체가 전원 장애로 죽으면 마지막 ~1초가 날아간다. 금융처럼 단 한 건도 잃으면 안 되는 시스템은 1, 손실이 허용되는 로그성 대량 적재는 2 또는 0 을 고려한다. 단, group commit 이 도입된 이후로는 값 1 의 fsync 비용이 상당히 분산되므로 무턱대고 2 로 낮추기보다 group commit 효과를 먼저 확인하는 편이 좋다.

## 5. binlog–redo group commit 2-phase

복제와 PITR(시점 복구)을 위해 binlog 를 켜면, 한 트랜잭션이 redo(InnoDB)와 binlog(서버 계층) 두 로그에 모두 기록되어야 하고 두 로그의 커밋 순서가 일치해야 한다. 이를 위해 MySQL 은 내부 XA 2-phase commit 과 group commit 을 결합한다. group commit 은 동시에 commit 을 요청한 여러 트랜잭션을 한 번의 fsync 로 묶어 디스크 동기화 횟수를 줄이는 기법이다.

흐름은 세 단계로 진행된다. 첫째 flush 단계: 리더 스레드가 큐에 모인 트랜잭션들의 redo 를 prepare 상태로 flush 하고 binlog 캐시를 binlog 파일에 write 한다. 둘째 sync 단계: 모인 그룹 전체에 대해 binlog 를 한 번 fsync 한다(여기서 fsync 1회가 N 개 트랜잭션을 커버). 셋째 commit 단계: 각 트랜잭션의 InnoDB commit(redo 에 commit 마크)을 순서대로 처리한다.

```sql
-- group commit 윈도우를 인위적으로 늘려 묶음 효율을 높임(쓰기 폭주 시)
SET GLOBAL binlog_group_commit_sync_delay = 100;       -- 100us 대기
SET GLOBAL binlog_group_commit_sync_no_delay_count = 20; -- 20개 모이면 즉시
```

`binlog_group_commit_sync_delay` 는 sync 직전 일부러 잠깐 기다려 더 많은 트랜잭션을 한 fsync 에 묶는 트릭이다. 개별 트랜잭션 지연은 약간 늘지만 전체 처리량(throughput)은 크게 오른다. 복구 정합성 측면에서, 서버가 prepare 와 commit 사이에서 죽으면 재기동 시 binlog 에 해당 트랜잭션이 있으면 commit, 없으면 rollback 으로 redo 와 binlog 를 일치시킨다.

## 6. crash recovery 의 redo·undo 두 단계

장애 후 재기동 시 InnoDB 복구는 두 단계로 진행된다. 첫째 redo 적용(roll-forward): checkpoint LSN 부터 redo 끝까지 스캔하며, 각 페이지에 아직 반영되지 않은 변경(페이지 LSN < redo LSN)을 재적용한다. 이 단계는 커밋·미커밋을 가리지 않고 모든 변경을 일단 페이지에 복원한다. 둘째 undo 되돌리기(roll-back): 복구 시점에 커밋되지 않았던 트랜잭션의 변경을 undo log 를 이용해 되돌린다. redo 가 "물리적 페이지 상태를 장애 직전으로 복원"한다면, undo 는 "미완 트랜잭션의 논리적 효과를 제거"한다.

undo log 는 복구뿐 아니라 MVCC 의 일관된 읽기에도 쓰인다. 한 행이 갱신되면 이전 버전이 undo 에 남고, 다른 트랜잭션은 자신의 read view 기준으로 undo 체인을 따라가 과거 버전을 본다. 즉 undo 는 롤백·복구·MVCC 세 역할을 동시에 한다. 그래서 장수명 트랜잭션이 undo 를 오래 붙들면 purge 가 밀려 history list length 가 커지고 테이블스페이스가 부푸는 부작용이 생긴다.

```sql
SHOW ENGINE INNODB STATUS\G   -- "History list length" 관찰, 비정상 증가 시 장수명 TX 의심
SELECT * FROM information_schema.innodb_trx
  ORDER BY trx_started ASC LIMIT 5;  -- 가장 오래된 트랜잭션 추적
```

## 7. fsync 비용과 스토리지 선택

WAL·doublewrite·group commit 의 성능은 결국 스토리지의 fsync 지연에 묶인다. `innodb_flush_log_at_trx_commit=1` 에서 commit 처리량의 상한은 사실상 "초당 가능한 fsync 횟수"다. 그래서 디스크 캐시 정책이 중요하다. 배터리/플래시 백업 캐시(BBU/FBWC)가 있는 RAID 컨트롤러나, 전원 손실 보호(PLP)가 있는 엔터프라이즈 SSD 는 fsync 를 캐시 수준에서 안전하게 완료 처리해 지연이 극적으로 낮다. 반면 PLP 없는 소비자용 SSD 는 데이터 보존을 위해 실제 NAND flush 를 기다려 fsync 가 느리다.

| 스토리지 | 안전한 fsync | commit 처리량 영향 |
|---------|------------|------------------|
| BBU/FBWC RAID | 캐시에서 완료 | 매우 높음 |
| PLP 지원 엔터프라이즈 SSD | 캐시 완료 | 높음 |
| PLP 없는 SSD/HDD | 매체 flush 대기 | 낮음(group commit 의존도↑) |

PLP 가 없는 환경일수록 group commit 의 묶음 효과가 절실하고, `binlog_group_commit_sync_delay` 로 의도적 묶음을 강화하는 효과도 커진다. 반대로 BBU 가 있으면 fsync 가 싸므로 `=1` 의 비용 부담이 작아 굳이 내구성을 낮추려 이유가 줄어든다. 즉 `innodb_flush_log_at_trx_commit` 값 선택은 스토리지의 fsync 특성과 함께 판단해야 하며, 같은 설정이라도 하드웨어에 따라 체감 성능이 크게 달라진다.

## 8. 정리

WAL 은 commit 비용을 순차 로그 쓰기로 낮추고, 체크포인트는 복구 범위와 로그 재활용을 통제하며, doublewrite 는 복구의 전제인 페이지 무결성을 지킨다. `innodb_flush_log_at_trx_commit` 는 내구성을, group commit 은 그 내구성의 비용을 분산한다. 데이터를 절대 잃으면 안 되는가에 대한 답이 Yes 라면 값 1 + binlog sync 1 + doublewrite ON 이 출발점이고, 손실 허용 폭과 처리량 요구에 따라 그 위에서 완화해 나간다.

## 참고

- MySQL 8.0 Reference Manual — InnoDB Redo Log, Doublewrite Buffer, Checkpoints
- MySQL 8.0 Reference Manual — innodb_flush_log_at_trx_commit, Group Commit
- Jeremy Cole, "InnoDB redo log and the physiology of recovery" (blog.jcole.us)
- Baron Schwartz et al., "High Performance MySQL" — Transaction and durability internals
