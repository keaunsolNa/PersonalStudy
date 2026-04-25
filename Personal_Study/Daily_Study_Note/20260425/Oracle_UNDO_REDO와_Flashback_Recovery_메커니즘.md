Notion 원본: https://www.notion.so/34d5a06fd6d381b58665f095c5ebda6c

# Oracle UNDO/REDO와 Flashback Recovery 메커니즘

> 2026-04-25 신규 주제 · 확장 대상: Oracle (DML/트랜잭션 학습됨), DB (MVCC 학습됨)

## 학습 목표

- UNDO 세그먼트와 REDO 로그의 역할을 트랜잭션 복구 / 인스턴스 복구 / 미디어 복구로 구분한다
- ORA-01555 Snapshot Too Old 에러의 발생 조건을 SCN과 UNDO retention 관점에서 설명한다
- Flashback Query, Flashback Table, Flashback Database를 적절한 RPO/RTO에 매칭한다
- Active DataGuard와 Flashback의 협업 패턴을 운영에서 실측한다

---

## 1. 트랜잭션 변경의 두 가지 기록

Oracle의 모든 DML은 두 위치에 변경을 기록한다. 첫째는 **UNDO segment**, 둘째는 **REDO log buffer → online redo log**다. 둘은 목적이 다르다.

| 구조 | 저장 내용 | 목적 |
|---|---|---|
| UNDO segment | "변경 전 이미지(before image)" | rollback, MVCC 일관 읽기, Flashback Query |
| REDO log | "변경 자체의 재현 정보(change vector)" | 인스턴스 / 미디어 복구 |

UPDATE 하나가 일어나면 buffer cache의 데이터 블록이 바뀌고, UNDO 블록에 변경 전 이미지가 쓰이고, 두 변경이 모두 REDO log buffer에 기록된다. COMMIT 시점에 REDO log buffer가 LGWR에 의해 online redo log 파일로 flush된다(공식적으로 group commit 가능). 이 시점이 트랜잭션의 durability 경계다.

## 2. SCN과 일관 읽기

SCN(System Change Number)은 Oracle 전체에서 단조 증가하는 논리 시계다. SELECT 문이 시작되면 그 시점의 SCN을 캡처하고, 읽으려는 블록의 ITL(Interested Transaction List)에서 그보다 큰 SCN의 트랜잭션이 발견되면 UNDO chain을 따라 거슬러 올라가 해당 SCN 시점의 이미지를 재구성한다.

```sql
-- 현재 SCN 확인
SELECT current_scn FROM v$database;

-- 5분 전 SCN으로 동일 쿼리
SELECT * FROM orders AS OF SCN 12345678 WHERE status = 'OPEN';
SELECT * FROM orders AS OF TIMESTAMP SYSTIMESTAMP - INTERVAL '5' MINUTE;
```

이게 Flashback Query의 본질이다. UNDO에 충분한 history가 남아 있는 한, Oracle은 과거 SCN의 데이터를 재구성할 수 있다. PostgreSQL의 MVCC가 같은 row의 옛 버전을 여러 vacuum 대상으로 보존하는 것과 다르게, Oracle은 in-place 업데이트 + UNDO chain 모델이다.

## 3. ORA-01555 Snapshot Too Old의 두 시나리오

이 에러는 두 가지 원인으로 발생한다.

**시나리오 A: UNDO retention 초과.** 긴 SELECT가 돌고 있는 사이에 UNDO segment의 해당 블록이 다른 트랜잭션에 의해 재사용되면, 일관 읽기에 필요한 before image가 사라진다. 운영 임계점은 `UNDO_RETENTION` (기본 900초) + UNDO tablespace 크기다.

```sql
ALTER SYSTEM SET undo_retention = 7200;  -- 2시간으로 늘림
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;  -- 절대 재사용 금지
```

`RETENTION GUARANTEE`는 강력하지만 long-running 트랜잭션이 UNDO를 다 차게 만들면 다른 DML이 ORA-30036을 받는다. trade-off다.

**시나리오 B: 블록 클린아웃 부족.** 큰 트랜잭션이 commit하면 모든 블록의 ITL을 즉시 청소하지 못한다. 다음 reader가 `delayed block cleanout`을 수행하면서 UNDO를 추적할 때 chain이 끊기면 ORA-01555가 발생한다. 대용량 INSERT 직후 SELECT 패턴에서 자주 발생.

대응은 ① UNDO_RETENTION 상향, ② SELECT를 작은 배치로 끊기(`ROWNUM`/페이지네이션), ③ 트랜잭션 크기 자체를 줄이기.

## 4. 인스턴스 복구와 Checkpoint

DB 인스턴스가 비정상 종료(예: 서버 정전)되면 datafile에는 commit 안 된 변경도 섞여 있고 commit 됐지만 datafile에 미반영된 변경도 있다. 시작 시 Oracle은 다음 단계를 수행한다.

1. **Roll forward**: 마지막 checkpoint SCN 이후의 모든 REDO를 datafile에 적용
2. **Open database**: 사용자 접속 허용
3. **Roll back**: UNDO를 사용해 commit 되지 않은 트랜잭션 되돌리기

이 순서가 핵심이다. roll back은 사용자 접속 이후에 백그라운드로 진행되며, 사용자가 만난 row의 ITL에 미완 트랜잭션이 있으면 SELECT가 UNDO chain을 통해 자기가 직접 roll back한 이미지를 본다. 그래서 인스턴스 복구는 분 단위가 아니라 초 단위다.

`FAST_START_MTTR_TARGET` 파라미터는 이 roll forward에 걸리는 시간을 직접 제한한다. 작게 잡으면 LGWR/DBWn이 더 자주 flush해서 IO 비용이 오르고, 크게 잡으면 복구 시간이 길어진다.

## 5. Flashback Table

`FLASHBACK TABLE`은 ROWID 기반으로 테이블을 특정 SCN/타임스탬프 이전 상태로 되돌린다. 단, 테이블의 ROW MOVEMENT가 enabled여야 한다.

```sql
ALTER TABLE orders ENABLE ROW MOVEMENT;
FLASHBACK TABLE orders TO TIMESTAMP SYSTIMESTAMP - INTERVAL '10' MINUTE;
```

내부적으로 UNDO chain을 역방향으로 적용한다. 그래서 UNDO retention과 UNDO 크기가 핵심 제약이다. 10분 전 상태로 돌리려면 그 사이의 모든 UNDO record가 살아 있어야 한다. DDL은 UNDO에 기록되지 않으므로(ALTER TABLE 등) Flashback Table은 DDL이 섞이면 ORA-01466으로 거부된다.

## 6. Flashback Database

스케일이 더 큰 복구는 **Flashback Database**다. 이 경우 UNDO 대신 별도의 **flashback log**가 사용된다. 활성화하면 RVWR(Recovery Writer) 백그라운드 프로세스가 변경 전 데이터 블록 이미지를 `db_recovery_file_dest` 아래에 기록한다.

```sql
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER SYSTEM SET db_flashback_retention_target = 1440; -- 24시간
ALTER DATABASE FLASHBACK ON;
ALTER DATABASE OPEN;

-- 24시간 안의 임의 시점으로 되돌리기
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
FLASHBACK DATABASE TO TIMESTAMP SYSTIMESTAMP - INTERVAL '6' HOUR;
ALTER DATABASE OPEN RESETLOGS;
```

Flashback Database는 RPO를 분 단위로, RTO를 분 단위로 만드는 가장 빠른 수단이다. 백업 / 복원 / 미디어 recovery보다 훨씬 빠르다. 단점은 ① 별도 디스크 공간, ② RVWR overhead(쓰기 IO 5~10%), ③ RESETLOGS 후의 incarnation 관리 복잡성.

## 7. Flashback과 DataGuard

운영에서 자주 만나는 패턴은 Active DataGuard standby + Flashback이다. 시나리오: primary에서 잘못된 batch가 돌았다. standby의 redo apply를 즉시 멈추고, primary를 flashback으로 되돌린 뒤 standby를 재정렬해야 한다.

```sql
-- standby에서
ALTER DATABASE RECOVER MANAGED STANDBY DATABASE CANCEL;
-- primary에서 flashback 후
ALTER DATABASE OPEN RESETLOGS;
-- standby에서 incarnation 재정렬
FLASHBACK DATABASE TO SCN <primary's resetlogs SCN - 1>;
ALTER DATABASE RECOVER MANAGED STANDBY DATABASE USING CURRENT LOGFILE DISCONNECT;
```

이 절차에 익숙해지지 않으면 RESETLOGS 이후 primary와 standby의 redo branch가 어긋나 재구축이 필요해진다. 운영 환경에서 standby 재구축은 수십 GB ~ TB 단위 전송이 필요해 RPO를 깬다.

## 8. UNDO 사이징과 모니터링

UNDO tablespace 크기 추정 공식.

```
필요 크기 = UNDO_RETENTION (sec) × UNDO 생성률 (bytes/sec) × 1.2 (안전 계수)
```

UNDO 생성률은 다음 쿼리로 측정.

```sql
SELECT undoblks * 8192 / NULLIF((end_time - begin_time) * 86400, 0) AS bytes_per_sec
FROM   v$undostat
ORDER BY begin_time DESC;
```

운영 중 ORA-01555가 간헐 발생한다면 ① v$undostat에서 maxquerylen이 retention보다 큰 시점이 있는지, ② tuned_undoretention(자동 튜닝된 실제 retention)이 설정값에 못 미치는지 확인한다. 자동 튜닝은 UNDO tablespace가 부족하면 retention을 줄이는 방향으로 동작한다. 즉 "설정은 7200초인데 실제는 1500초만 보장"되는 상황이 있다.

## 9. Recycle Bin과 PURGE

Oracle은 `DROP TABLE`이 즉시 영구 삭제가 아니다. 기본적으로 recycle bin에 들어가 `BIN$...` 형식 이름으로 보관된다.

```sql
DROP TABLE orders;
SELECT * FROM recyclebin;
FLASHBACK TABLE orders TO BEFORE DROP;

-- 영구 삭제
DROP TABLE orders PURGE;
PURGE RECYCLEBIN;  -- 사용자 본인 휴지통
PURGE DBA_RECYCLEBIN; -- 전체
```

이 동작이 디스크를 잠식하는 사고의 원인이 되기도 한다. 운영 데이터베이스에서 대용량 테이블을 임시로 만들었다 drop하는 패턴은 PURGE를 명시해야 안전하다.

## 참고

- Oracle Database 19c Concepts Guide, Chapter "Data Concurrency and Consistency"
- Oracle Database Backup and Recovery User's Guide
- Tom Kyte, "Expert Oracle Database Architecture", Apress
- Oracle Doc ID 1579088.1, "Tuning UNDO Tablespace for Enterprise Database"
- Oracle DataGuard Concepts and Administration 19c
