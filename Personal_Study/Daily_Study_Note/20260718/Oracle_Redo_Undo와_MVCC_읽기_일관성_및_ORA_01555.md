Notion 원본: https://www.notion.so/3a15a06fd6d381d59dc2eb687de21c94

# Oracle Redo Undo와 MVCC 읽기 일관성 및 ORA-01555

> 2026-07-18 신규 주제 · 확장 대상: Oracle

## 학습 목표

- Redo 와 Undo 가 각각 무엇을 보장하는지 구분하고 블록 변경 시점의 내부 흐름을 추적한다.
- SCN 기반 읽기 일관성과 CR 블록 재구성 과정을 실행 통계로 확인한다.
- ORA-01555 의 두 가지 발생 경로를 구분하고 undo 파라미터를 근거 있게 산정한다.
- 배치·대용량 DML 의 트랜잭션 경계를 redo/undo 부담 관점에서 설계한다.

## 1. 왜 Redo 와 Undo 를 둘 다 만드는가

Redo 는 **변경을 다시 적용(roll forward)** 하기 위한 기록이고, undo 는 **변경을 되돌리기** 위한 이전 이미지(before image)다. 이름이 대칭적이라 같은 걸 반대로 적어둔 것처럼 보이지만 목적이 전혀 다르다.

Redo 가 필요한 이유는 **버퍼 캐시가 휘발성**이기 때문이다. Oracle 은 블록을 메모리에서 고치고 디스크에는 DBWR 이 나중에 느긋하게 쓰므로, 커밋한 변경이 아직 데이터 파일에 없는 상태에서 인스턴스가 죽으면 그 변경은 증발한다. 그래서 커밋 시점에 "무엇을 어떻게 바꿨는지"를 순차 기록에 강제로 먼저 내려쓰고, 복구 시 재생해 데이터 파일을 커밋 시점까지 끌어올린다.

Undo 가 필요한 이유는 정반대다. 버퍼 캐시가 차면 DBWR 은 커밋 여부와 무관하게 **미커밋 트랜잭션의 더티 블록도 데이터 파일에 써버린다**. 데이터 파일에 "아직 커밋 안 된 값"이 섞이므로, 롤백하거나 인스턴스가 죽으면 되돌려야 한다. 되돌릴 재료가 undo 이며, 동시에 undo 는 다른 세션이 "내 쿼리 시작 시점의 값"을 보게 해주는 재료이기도 하다.

| 구분 | Redo | Undo |
|---|---|---|
| 목적 | 변경 재적용, 복구 | 변경 취소, 읽기 일관성 |
| 보장하는 ACID | Durability | Atomicity, Isolation |
| 저장 위치 | Online/Archived Redo Log | Undo 테이블스페이스 (세그먼트 구조) |
| 접근 패턴 | 순차 append-only | 랜덤 I/O, 버퍼 캐시 경유 |
| redo 로 보호되는가 | 해당 없음 | 그렇다 |

## 2. 블록 하나를 UPDATE 할 때 벌어지는 일

`UPDATE emp SET sal = 5000 WHERE empno = 7369;` 의 순서는 이렇다. 로우가 든 블록을 버퍼 캐시로 읽고, undo 세그먼트에서 undo 블록을 확보해 "sal 이 원래 800 이었다"를 쓴다. 그 다음 데이터 블록의 ITL 슬롯에 XID 와 undo 주소(UBA)를 기록하고 값을 5000 으로 고친다.

핵심은 이 과정이 **redo 를 두 번 만든다**는 점이다. 데이터 블록 변경도 redo change vector 를 만들고, **undo 블록에 이전 이미지를 쓰는 행위 자체도 블록 변경이므로 redo 를 만든다**. 이유는 복구 절차에 있다. 크래시 후 Oracle 은 먼저 redo 를 재생해 **커밋/미커밋을 가리지 않고 모든 변경을 재적용**하는데, 이때 undo 세그먼트도 크래시 직전 상태로 복원되어야 그 다음 단계인 미커밋 트랜잭션 롤백이 가능하다. Undo 가 redo 로 보호되지 않으면 되돌릴 재료가 사라진다.

INSERT 는 undo 가 작고(로우 주소만 있으면 지우니까) DELETE 는 크므로(로우 전체를 되살려야 하니까), 같은 건수라도 DELETE 가 redo 를 훨씬 많이 만든다.

```sql
-- DML 전후로 각각 실행해 델타를 비교한다
SELECT n.name, s.value
  FROM v$mystat s
  JOIN v$statname n ON n.statistic# = s.statistic#
 WHERE n.name IN ('redo size', 'undo change vector size', 'db block changes');

INSERT INTO t_redo_test SELECT level, RPAD('x', 100, 'x')
  FROM dual CONNECT BY level <= 10000;
COMMIT;

DELETE FROM t_redo_test;   -- undo 비중이 INSERT 보다 훨씬 높게 나온다
COMMIT;
```

`undo change vector size` 는 `redo size` 안에 포함된 값이다. 대량 삭제라면 `TRUNCATE` 나 파티션 DROP 이 redo/undo 를 거의 만들지 않지만, 롤백이 불가능하고 DDL 이라 암묵적 커밋이 발생한다. Spring 의 `@Transactional` 안에서 TRUNCATE 를 호출하면 트랜잭션 경계가 그 지점에서 깨져 롤백을 기대한 코드가 조용히 망가진다.

## 3. Log Buffer → LGWR → Online Redo Log

Redo change vector 는 곧바로 파일로 가지 않고 SGA 의 **log buffer** 에 쌓이며, LGWR 이 online redo log 에 순차 기록한다.

| 트리거 | 설명 |
|---|---|
| 커밋(또는 롤백) | 해당 redo 를 디스크에 내리고 완료를 기다린 뒤 성공 응답 |
| 3초마다 | 주기적 타임아웃 |
| log buffer 가 1/3 이상 참 | 사용량 임계치 |
| 1MB 이상 미기록 redo 누적 | 크기 임계치 |
| DBWR 이 더티 블록 쓰기 직전 | Write-Ahead Logging 규약 |

마지막 항목이 WAL 의 본질이다. **데이터 블록이 디스크에 내려가기 전에 그 변경을 설명하는 redo 가 먼저 디스크에 있어야 한다.** 순서가 뒤집히면 복구가 불가능하므로, DBWR 은 블록을 쓰기 전 그 블록의 최고 redo 위치까지 flush 됐는지 확인하고 아니면 LGWR 에 요청한 뒤 기다린다.

커밋 시 동작이 "log force at commit" 이다. 커밋한 세션은 LGWR 이 redo 를 쓰고 OS 응답을 받을 때까지 `log file sync` 에서 대기한다(배치에서 이 이벤트가 상위에 뜨면 커밋 주기를 의심할 것). 줄이는 파라미터가 `COMMIT_LOGGING` 과 `COMMIT_WRITE` 다.

```sql
ALTER SESSION SET COMMIT_WRITE = 'BATCH,NOWAIT';   -- redo flush 완료를 안 기다림
COMMIT WRITE BATCH NOWAIT;                          -- 문장 레벨 지정
ALTER SESSION SET COMMIT_WRITE = 'IMMEDIATE,WAIT'; -- 기본값 복귀
```

`NOWAIT` 는 쓰기 완료를 기다리지 않고 반환하고, `BATCH` 는 redo 를 즉시 flush 하지 않고 다른 커밋과 묶는다. 응답시간은 개선되지만 **durability 를 명시적으로 포기하는 설정**이다. 커밋 응답 직후 인스턴스가 죽으면 그 커밋은 사라진다. 애플리케이션은 이미 "성공"을 보냈는데 DB 에는 없는 상태가 된다. 결제·원장에는 절대 쓰면 안 되고, 재계산 가능한 집계 적재처럼 유실을 감내할 수 있는 경로에만 국소 적용하는 것이 타협점이다.

## 4. SCN 과 읽기 일관성: CR 블록은 어떻게 만들어지는가

SCN(System Change Number)은 DB 전역에서 단조 증가하는 논리적 시계다. 모든 커밋은 SCN 을 소비하고, 모든 블록 헤더에는 마지막 변경 SCN 이 있다. 쿼리가 시작되면 그 순간의 SCN 을 **쿼리 SCN** 으로 고정한다. 블록 헤더 SCN 이 쿼리 SCN **이하면** 그대로 읽는다. **보다 크면** 봐서는 안 되는 미래의 변경이 담긴 것이므로 블록을 **복사**하고 ITL 이 가리키는 UBA 를 따라 undo 를 적용해 과거로 되감는다. 여전히 크면 undo 체인을 한 번 더 따라가고, 쿼리 SCN 이하가 될 때까지 반복한다.

이 사본이 **CR(Consistent Read) 블록** 이다. 읽기 전용 임시 결과물이라 디스크에 쓰이지 않는다. "읽기는 쓰기를 막지 않고 쓰기는 읽기를 막지 않는다"는 이 메커니즘의 요약으로, reader 는 락을 잡지 않고 자기 시점의 사본을 스스로 만든다.

| 지표 | 의미 | 발생하는 곳 |
|---|---|---|
| `consistent gets` | 특정 SCN 시점 기준의 일관된 읽기 | 일반 SELECT, DML 의 조회 단계 |
| `db block gets` (current) | **지금 이 순간**의 최신 블록 읽기 | UPDATE/DELETE 의 변경 대상 블록, 세그먼트 헤더 |

DML 은 과거를 볼 수 없어 값을 고치려면 current 블록이 필요하다. 그래서 UPDATE 통계에는 `db block gets` 가 반드시 등장한다.

```sql
SET AUTOTRACE ON STATISTICS
SELECT COUNT(*) FROM big_table;                   -- consistent gets 중심
UPDATE big_table SET flag = 'Y' WHERE id < 100;   -- db block gets 등장
ROLLBACK;

SELECT n.name, s.value                            -- CR 재구성 빈도
  FROM v$sysstat s JOIN v$statname n ON n.statistic# = s.statistic#
 WHERE n.name IN ('consistent gets', 'db block gets', 'consistent changes',
                  'data blocks consistent reads - undo records applied');
```

`undo records applied` 가 `consistent gets` 대비 높으면 한 블록을 읽으려 undo 를 수십·수백 번 되감는다는 뜻으로, 카운터 테이블처럼 소수 로우를 다수 세션이 갱신하는 구조의 전형이다. 대응은 로우 분산(카운터 샤딩)이고 trade-off 는 조회 시 SUM 이 필요해진다는 점이다.

MySQL InnoDB 와 대비하면 차이가 선명하다. InnoDB 는 각 로우의 `DB_TRX_ID`, `DB_ROLL_PTR` 로 **로우 단위** 버전 체인을 read view 가 판정하고 undo 는 레코드 단위 논리적 undo 다. Oracle 은 **블록 단위 before image** 를 통째로 되감아 스캔에 유리하지만, 갱신이 몰린 블록은 모든 reader 가 되감기 비용을 반복해 치른다.

## 5. ITL 슬롯과 블록 헤더

블록이 자기 안의 로우가 어느 트랜잭션에 의해 변경 중인지 아는 방법이 **ITL(Interested Transaction List)** 이다. 블록 헤더의 슬롯 배열이며, 각 슬롯은 XID(트랜잭션 ID), UBA(undo 위치), 커밋/cleanout 플래그, 잠근 로우 수, 커밋 SCN 을 담는다.

Oracle 에 별도의 "로우 락 테이블"이 없는 이유가 여기 있다. **락 정보가 블록 안에 있어서** 락 에스컬레이션이 없고, 잠긴 로우가 100만 개든 1개든 비용 구조가 같다.

슬롯 개수는 `INITRANS`(블록 생성 시 미리 잡는 수)와 `MAXTRANS` 로 조절하고, 블록에 빈 공간이 있으면 동적으로 늘어난다. 문제는 **블록이 꽉 차 ITL 을 늘릴 공간이 없을 때** 다. 쓸 슬롯이 없는 새 트랜잭션은 다른 트랜잭션이 끝나기를 기다리고, 이때 나타나는 대기가 `enq: TX - allocate ITL entry` 다.

```sql
SELECT event, total_waits, time_waited FROM v$system_event
 WHERE event LIKE 'enq: TX%';

SELECT owner, object_name, subobject_name, value  -- 어느 세그먼트가 범인인지
  FROM v$segment_statistics
 WHERE statistic_name = 'ITL waits' AND value > 0
 ORDER BY value DESC;

ALTER TABLE order_item INITRANS 8;
ALTER TABLE order_item MOVE;   -- 기존 블록에 반영하려면 재구성 필요
```

Trade-off 는 명확하다. 슬롯 하나가 블록 공간을 차지하므로 `INITRANS` 를 크게 잡으면 블록당 로우 수가 줄고 스캔 I/O 가 늘어, ITL waits 가 실제로 잡히는 세그먼트에만 적용해야 한다. 특히 **인덱스 리프 블록**은 로우가 조밀해 여유가 적어 동시 INSERT 가 몰리는 인덱스에서 경합이 자주 관찰된다.

## 6. ORA-01555 snapshot too old 의 정확한 메커니즘

`ORA-01555: snapshot too old` 는 **"원하는 과거 시점의 데이터를 재구성할 undo 가 이미 사라졌다"** 는 뜻이다. 데이터가 손상된 게 아니라 시간 여행에 실패한 것이며, 경로는 둘이다.

**경로 1 — undo 재사용(overwrite).** Undo 익스텐트는 원형으로 재사용된다. 커밋된 undo 는 `UNDO_RETENTION` 목표만큼 남기려 하지만 공간이 없으면 **retention 을 무시하고 덮어쓴다**. 09:00 에 장기 쿼리가 SCN 1000 을 고정한 채 도는 동안 다른 세션들이 대량 DML + 커밋으로 undo 를 소진해 09:40 에 09:00 무렵 익스텐트를 덮어쓰면, 09:41 에 장기 쿼리가 SCN 1500 인 블록을 되감으려 할 때 undo 가 없다. **에러 나는 세션과 원인을 만든 세션이 다르다.** 죽는 건 조회 쿼리인데 범인은 옆에서 커밋을 남발한 배치다.

**경로 2 — delayed block cleanout.** 커밋 시 Oracle 은 변경한 모든 블록의 ITL 을 즉시 정리하지 않는다. 커밋은 undo 세그먼트 헤더의 트랜잭션 테이블에 "커밋됨, SCN=1200" 만 쓰고 끝낸다(fast commit). 버퍼 캐시에 남은 일부는 커밋 세션이 바로 정리하지만, 이미 디스크로 내려갔거나 개수가 많으면 미룬다. 나중에 그 블록을 읽는 세션은 ITL 에 커밋 SCN 이 없어 "활성처럼 보이는" 상태를 만나고, XID 로 undo 세그먼트 헤더에 "커밋됐나?"를 묻는다. 그 사이 트랜잭션 테이블 슬롯이 재사용됐다면 커밋 SCN 을 알 수 없으므로 ORA-01555 를 던진다. 대표 시나리오는 **대량 적재 후 곧바로 그 테이블 풀스캔** 이고, undo 를 아무리 키워도 안 없어진다. 적재 직후 가벼운 풀스캔(`SELECT /*+ FULL(t) */ COUNT(*) FROM t;`)으로 cleanout 을 미리 끝내는 것이 회피책이며, 대가는 그 스캔이 redo 를 만든다는 점이다.

```sql
ALTER SYSTEM SET undo_retention = 3600 SCOPE=BOTH;
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;    -- 목표 → 보장
ALTER TABLESPACE undotbs1 RETENTION NOGUARANTEE;  -- 되돌리기

-- 크기 산정: retention(초) × 초당 undo 블록 × 블록 크기
SELECT (SELECT TO_NUMBER(value) FROM v$parameter WHERE name = 'undo_retention')
         * MAX(undoblks / ((end_time - begin_time) * 86400))
         * (SELECT TO_NUMBER(value) FROM v$parameter WHERE name = 'db_block_size')
         / 1024 / 1024 AS undo_needed_mb
  FROM v$undostat;
```

`UNDO_RETENTION` 은 **목표지 보장이 아니다.** 고정 크기 undo 테이블스페이스에서는 Oracle 이 자동으로 retention 을 튜닝하며 공간이 부족하면 목표를 무시한다. `RETENTION GUARANTEE` 를 걸면 retention 기간 내 undo 를 절대 덮어쓰지 않는다. 대신 **공간 부족 시 DML 이 `ORA-30036` 으로 실패한다.** "SELECT 가 죽는 것"과 "UPDATE 가 죽는 것" 사이의 선택인 셈이다. 리포팅 정합성이 최우선인 DW 성 시스템은 GUARANTEE 가 맞고, 트랜잭션이 멈추면 안 되는 OLTP 는 신중해야 한다. 산정 쿼리가 AVG 가 아닌 MAX 를 쓰는 이유는 피크(야간 배치) 기준이어야 해서다.

## 7. Fetch Across Commit — 스스로 무덤 파기

ORA-01555 를 **자기 자신이** 만들어내는 고전적 안티패턴으로, 커서를 열고 루프 안에서 커밋하는 구조다.

```sql
-- 안티패턴
DECLARE
  CURSOR c IS SELECT rowid rid, sal FROM emp;   -- 09:00, 쿼리 SCN 고정
  v_cnt NUMBER := 0;
BEGIN
  FOR r IN c LOOP
    UPDATE emp SET sal = r.sal * 1.1 WHERE rowid = r.rid;
    v_cnt := v_cnt + 1;
    IF MOD(v_cnt, 1000) = 0 THEN
      COMMIT;            -- 내 undo 가 재사용 가능해진다
    END IF;
  END LOOP;
  COMMIT;
END;
/
```

커서 `c` 는 09:00 SCN 을 고정한 채 루프 끝까지 그 시점 데이터를 요구하는데, 같은 루프의 UPDATE 가 블록 SCN 을 계속 올리고 `COMMIT` 이 그 undo 를 재사용 가능으로 표시한다. 공간이 빠듯하면 조금 전 내가 만든 undo 가 조금 후 내 커서에 필요해지는데 이미 덮어써진다. **자기가 만든 undo 를 자기가 못 찾는다.** "undo 줄이려고 자주 커밋했는데 ORA-01555 가 더 난다"는 상황이 여기서 나온다.

해결은 셋이다. 첫째, 커서 루프를 없애고 **한 방 SQL** 로 처리한다(`UPDATE emp SET sal = sal * 1.1;`). 단일 문장은 문장 단위 읽기 일관성 안에서 원자적으로 처리되므로 이 문제가 없다. 둘째, 루프가 불가피하면 **매 청크가 독립된 새 쿼리**가 되게 한다. 셋째, `BULK COLLECT ... LIMIT` + `FORALL` 을 쓰되 청크 재조회 구조는 유지한다.

```sql
-- 개선: 청크마다 새 쿼리 → 커서가 과거 SCN 을 붙잡지 않음
DECLARE
  v_rows NUMBER;
BEGIN
  LOOP
    UPDATE emp SET sal = sal * 1.1, upd_flag = 'Y'
     WHERE upd_flag IS NULL AND ROWNUM <= 5000;
    v_rows := SQL%ROWCOUNT;
    COMMIT;
    EXIT WHEN v_rows = 0;
  END LOOP;
END;
/
```

## 8. 진단 쿼리 실전

**롱 트랜잭션 찾기.** `used_ublk` 가 계속 크는데 `start_time` 이 몇 시간 전이면 그게 undo 를 먹어치우는 롱 트랜잭션이다.

```sql
SELECT s.sid, s.username, s.program, t.start_time,
       t.used_ublk AS undo_blocks, t.used_urec AS undo_records, r.segment_name
  FROM v$transaction t
  JOIN v$session s ON s.saddr = t.ses_addr
  JOIN dba_rollback_segs r ON r.segment_id = t.xidusn
 ORDER BY t.used_ublk DESC;
```

**Undo 사용 현황.** `ACTIVE` 는 미커밋 트랜잭션이 쓰는 중이라 재사용 불가, `UNEXPIRED` 는 커밋됐지만 retention 이 안 지나 공간 부족 시 덮어쓸 후보, `EXPIRED` 는 자유롭게 재사용 가능이다. **`EXPIRED` 가 대부분이고 `UNEXPIRED` 가 거의 없다면 undo 가 너무 빨리 재활용된다는 뜻**이고 ORA-01555 가 임박한 상태다.

```sql
SELECT tablespace_name, status,           -- ACTIVE / UNEXPIRED / EXPIRED
       COUNT(*) AS extents, ROUND(SUM(bytes)/1024/1024) AS mb
  FROM dba_undo_extents
 GROUP BY tablespace_name, status
 ORDER BY tablespace_name, status;
```

**Undo 통계 이력 — 튜닝의 근거.** `ssolderrcnt > 0` 인 구간을 찾고 같은 행의 `maxqueryid`(SQL_ID)로 `v$sql` 에서 범인 쿼리를 뽑는다. `tuned_undoretention` 이 설정값보다 작다면 Oracle 이 공간 압박으로 목표를 깎았다는 뜻이라 undo 테이블스페이스를 늘려야 한다.

```sql
SELECT TO_CHAR(begin_time, 'MM-DD HH24:MI') AS begin_t,
       undoblks, txncount, maxquerylen, maxqueryid, tuned_undoretention,
       nospaceerrcnt,   -- ORA-30036 계열
       ssolderrcnt      -- ORA-01555 발생 횟수
  FROM v$undostat
 ORDER BY begin_time DESC
 FETCH FIRST 30 ROWS ONLY;
```

**Redo 생성량.** `redo size` 를 `user commits` 로 나누면 커밋당 redo 가 나온다. `redo log space requests` 나 `redo buffer allocation retries` 가 늘면 log buffer 부족이거나 LGWR 이 못 따라가는 것이다.

```sql
SELECT n.name, s.value
  FROM v$sysstat s JOIN v$statname n ON n.statistic# = s.statistic#
 WHERE n.name IN ('redo size', 'redo entries', 'redo log space requests',
                  'redo buffer allocation retries', 'user commits');
```

**AWR 에서 볼 곳.** Load Profile 의 `Redo size per second` / `per transaction` 을 먼저 본다. per transaction 이 예상보다 크면 트랜잭션 하나가 과도한 작업을 한다는 뜻이다. 이어 Top Timed Events 에서 `log file sync`(커밋 대기)와 `log file parallel write`(LGWR 물리 쓰기)를 대조해, sync 는 큰데 parallel write 가 작으면 I/O 가 아니라 커밋 횟수 문제로 본다.

## 9. 애플리케이션 관점 — Spring 트랜잭션 경계와 연결

**커밋 주기.** "커밋을 자주 하면 undo 가 줄어든다"는 통념은 절반만 맞다. 커밋은 undo 를 재사용 가능하게 만들 뿐이고, 앞서 봤듯 오히려 ORA-01555 를 유발한다. 반대로 커밋을 안 하면 undo 가 `ACTIVE` 로 누적되어 `ORA-30036` 으로 죽는다. 절충은 **수천 건 단위 청크 커밋 + 각 청크가 독립 쿼리** 다. Spring Batch 의 `chunk()` + `commit-interval` 이 그 모양이다. 다만 reader 가 `JdbcCursorItemReader` 면 **커서를 열어둔 채 청크마다 커밋**하는 fetch across commit 이 그대로 재현된다. 장시간 배치라면 매 페이지가 새 SELECT 인 `JdbcPagingItemReader` 가 안전하고, 대신 정렬 키가 불변(PK 등)이어야 로우 누락·중복이 없다.

**트랜잭션 경계.** `@Transactional` 은 커넥션 하나를 잡고 그 안의 DML 을 하나의 Oracle 트랜잭션으로 묶는다. 서비스 메서드가 외부 API 호출을 포함한 채 감싸여 있으면 그 대기 내내 undo 가 `ACTIVE` 로 잡혀 다른 세션의 재사용 여지가 준다. **트랜잭션 안에서 네트워크 I/O 를 하지 말라**는 원칙은 커넥션 풀뿐 아니라 undo 관점에서도 근거가 있다.

**대용량 UPDATE 의 redo 부담.** 1억 건 UPDATE 는 데이터 redo + undo redo 를 모두 만든다. 로그 스위치가 폭주하고 아카이브 영역이 차면 **DB 전체가 멈춘다**(`ORA-00257`). 대안은 CTAS 로 새 테이블을 만들어 이름을 바꾸는 것이다.

```sql
SELECT force_logging FROM v$database;   -- 먼저 확인할 것
CREATE TABLE emp_new NOLOGGING PARALLEL 4 AS
SELECT empno, ename, sal * 1.1 AS sal, deptno FROM emp;

INSERT /*+ APPEND */ INTO target_tab SELECT * FROM source_tab;
COMMIT;

ALTER TABLE emp_new LOGGING;            -- 작업 후 반드시 원복
```

**위험을 정확히 알아야 한다.** `NOLOGGING` + direct-path 는 redo 를 최소화하지만 이는 곧 **"이 데이터는 백업으로 복구할 수 없다"** 는 뜻이다. 직후 데이터 파일이 손상되면 재생할 redo 가 없어 블록이 논리적으로 깨진 상태(`ORA-01578` 계열)로 남는다. 철칙은 둘. **(1) NOLOGGING 작업 직후 반드시 백업을 다시 뜬다. (2) Data Guard standby 가 있으면 primary 는 FORCE LOGGING 이라 NOLOGGING 은 무시되고 이득이 없다.** `/*+ APPEND */` 는 커밋 전까지 테이블에 **exclusive 락**을 걸어 다른 세션 DML 을 막으므로 야간 배치 전용 도구다.

결국 개발자가 쥔 레버는 **트랜잭션의 길이**와 **개수** 둘뿐이다. 길면 undo 가 쌓이고 롤백이 무서워지며, 짧고 많으면 `log file sync` 와 ORA-01555 가 온다. 그 사이 값은 감이 아니라 `v$undostat` 과 AWR 로 찾는다.

## 참고

- Oracle Database Concepts — "Data Concurrency and Consistency", "Logical Storage Structures"
- Oracle Database Administrator's Guide — "Managing Undo" (크기 산정, RETENTION GUARANTEE)
- Oracle Database Reference — `V$UNDOSTAT`, `V$TRANSACTION`, `DBA_UNDO_EXTENTS` 뷰 정의
- Oracle Database Backup and Recovery User's Guide — NOLOGGING 과 복구 불가 영역
- Thomas Kyte, *Expert Oracle Database Architecture* — Redo/Undo, Locking and Latching 장
- Jonathan Lewis, *Oracle Core: Essential Internals for DBAs and Developers* — Undo/Redo, Transactions and Consistency 장
