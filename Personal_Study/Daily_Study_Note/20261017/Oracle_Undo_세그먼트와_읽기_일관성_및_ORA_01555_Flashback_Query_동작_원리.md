Notion 원본: https://app.notion.com/p/3fc5a06fd6d3812fb654e798bd1a74b4?pvs=204

# Oracle Undo 세그먼트와 읽기 일관성 및 ORA-01555 Snapshot Too Old, Flashback Query 동작 원리

> 2026-10-17 신규 주제 · 확장 대상: Oracle

## 학습 목표

- Undo 세그먼트의 트랜잭션 테이블과 undo 레코드가 변경 이력을 보존하는 방식을 추적한다.
- 쿼리 시작 SCN 기준으로 CR 블록을 만드는 읽기 일관성 흐름을 단계별로 재현한다.
- V$UNDOSTAT과 DBA_UNDO_EXTENTS로 ORA-01555 원인을 진단하고 UNDO 크기를 산정한다.
- AS OF, VERSIONS BETWEEN, Flashback Transaction Query의 한계를 실습으로 확인한다.

## 1. Undo가 존재하는 이유와 세 가지 역할

Oracle은 데이터 블록을 직접 수정하면서도 다른 세션에게는 수정 이전 상태를 보여 줘야 한다. 이를 가능하게 하는 것이 undo 데이터다. 한 번의 UPDATE는 데이터 블록에 새 값을 쓰는 동시에, 이전 값을 되돌릴 수 있는 정보(undo 레코드)를 undo 테이블스페이스의 undo 블록에 기록하고, 그 undo 블록 변경분 역시 redo로 보호한다. 즉 undo 변경도 redo를 생성하므로, DML 한 건은 데이터 블록용 redo와 undo 블록용 redo를 함께 만든다.

Undo는 크게 세 가지 용도로 쓰인다. 첫째, ROLLBACK 시 변경을 되돌린다. 둘째, 다른 세션의 쿼리가 쿼리 시작 시점의 값을 보도록 읽기 일관성(read consistency)을 제공한다. 셋째, 인스턴스 장애 후 복구 단계에서 커밋되지 않은 트랜잭션을 롤백한다. 여기에 Flashback Query, Flashback Table 같은 기능이 undo의 과거 이미지를 재활용하는 형태로 얹혀 있다. 따라서 undo를 단순히 롤백용 공간으로만 보면 ORA-01555 같은 읽기 쪽 장애를 이해하기 어렵다.

현재 운영 환경에서 undo가 자동 관리 모드인지부터 확인하는 것이 출발점이다.

```sql
SHOW PARAMETER undo
-- undo_management  string  AUTO
-- undo_retention   integer 900
-- undo_tablespace  string  UNDOTBS1

SELECT tablespace_name, status, contents, retention
FROM   dba_tablespaces
WHERE  contents = 'UNDO';
```

UNDO_MANAGEMENT가 AUTO이면 Oracle이 undo 세그먼트의 생성, 크기, 재사용을 스스로 결정한다. UNDO_RETENTION의 기본값은 900초이며, 이는 커밋된 undo를 최소 15분 유지하려고 노력하겠다는 목표값이지 보장값이 아니다. 이 구분이 뒤에서 다룰 ORA-01555의 핵심이다.

## 2. Undo 세그먼트 구조: 트랜잭션 테이블과 익스텐트 상태

자동 undo 관리에서 undo 세그먼트는 undo 테이블스페이스 안에 시스템이 만든다. 각 세그먼트의 헤더 블록에는 트랜잭션 테이블이 있고, 슬롯 하나가 동시에 진행 중인 트랜잭션 하나에 대응한다. 슬롯에는 트랜잭션 상태(활성/커밋), 커밋 SCN, 마지막으로 쓴 undo 블록 주소, 슬롯 재사용 횟수(wrap) 등이 들어 있다. 트랜잭션 ID(XID)는 undo 세그먼트 번호, 슬롯 번호, wrap 값의 세 부분으로 구성된다.

데이터 블록 쪽에는 ITL(Interested Transaction List) 엔트리가 있어, 그 블록의 행을 수정한 트랜잭션의 XID와 undo 블록 주소(UBA)를 가리킨다. 행 하나에는 잠금 바이트가 ITL 번호를 가리킨다. 그래서 읽는 쪽은 블록을 열어 ITL을 보고, 해당 트랜잭션이 아직 활성인지 또는 어느 SCN에 커밋했는지를 undo 세그먼트 헤더에서 확인한 뒤, 필요하면 UBA를 따라 undo 레코드를 적용해 이전 이미지로 되돌린다.

Undo 익스텐트는 세 가지 상태로 분류된다. ACTIVE는 아직 커밋되지 않은 트랜잭션이 쓰는 익스텐트로 어떤 경우에도 덮어쓸 수 없다. UNEXPIRED는 커밋되었지만 UNDO_RETENTION 이내인 익스텐트로 읽기 일관성과 flashback에 쓰이며, 공간이 부족하면 덮어쓰일 수 있다. EXPIRED는 보존 시간이 지난 익스텐트로 언제든 재사용된다.

```sql
SELECT status, COUNT(*) extents,
       ROUND(SUM(bytes)/1024/1024) mb
FROM   dba_undo_extents
GROUP  BY status
ORDER  BY status;
-- ACTIVE     12     96
-- EXPIRED   610   4880
-- UNEXPIRED  95    760
```

위 수치는 예시 환경의 값이다. UNEXPIRED 비중이 크고 EXPIRED가 거의 없다면 undo 테이블스페이스가 빠듯하다는 신호이고, 이때 새 트랜잭션은 UNEXPIRED 익스텐트를 훔쳐 쓰기 시작해 장시간 쿼리가 ORA-01555를 만날 위험이 커진다. 반대로 ACTIVE가 계속 늘어나 테이블스페이스가 가득 차면 ORA-30036(unable to extend segment in undo tablespace)이 발생한다.

활성 트랜잭션이 얼마나 undo를 쓰는지는 V$TRANSACTION으로 본다.

```sql
SELECT s.sid, s.username, t.xidusn, t.xidslot, t.xidsqn,
       t.status, t.used_ublk, t.used_urec, t.start_scnb
FROM   v$transaction t JOIN v$session s ON s.taddr = t.addr;
```

USED_UBLK는 사용 중인 undo 블록 수, USED_UREC는 undo 레코드 수다. 대량 DELETE 같은 작업에서 이 값이 계속 커지고, 롤백할 때는 같은 양만큼 시간이 더 걸린다는 점도 기억해 둘 만하다.

## 3. 읽기 일관성: 쿼리 SCN과 CR 블록 생성 과정

Oracle의 기본 격리 수준은 READ COMMITTED이며, 읽기 일관성의 기준은 문장 시작 시점이다. 쿼리가 시작되는 순간 현재 SCN이 쿼리 SCN으로 고정된다. 이후 쿼리가 블록을 읽을 때마다 그 블록에 쿼리 SCN보다 늦게 커밋된(또는 아직 커밋되지 않은) 변경이 있는지 검사한다. 있다면 버퍼 캐시에서 해당 블록을 복제하여 CR(consistent read) 블록을 만들고, 그 복제본에 undo 레코드를 거꾸로 적용해 쿼리 SCN 시점의 이미지로 되돌린 뒤 그것을 읽는다. 원본 현재 블록은 건드리지 않으며, 변경이 여러 겹이면 undo 체인을 여러 번 따라간다.

이 과정을 눈으로 확인하려면 두 세션을 쓴다.

```sql
-- 세션 A
CREATE TABLE t_ur (id NUMBER PRIMARY KEY, val VARCHAR2(20));
INSERT INTO t_ur VALUES (1, 'before');
COMMIT;
VAR rc REFCURSOR
EXEC OPEN :rc FOR SELECT id, val FROM t_ur;   -- 이 시점 SCN 고정

-- 세션 B
UPDATE t_ur SET val = 'after' WHERE id = 1;
COMMIT;

-- 세션 A
PRINT rc      -- 'before' 가 출력된다 (OPEN 시점 기준)
```

OPEN 시점에 쿼리 SCN이 정해지므로 세션 B가 커밋한 뒤에 FETCH 해도 세션 A는 이전 값을 본다. 이때 세션 A의 통계에서 "data blocks consistent reads - undo records applied" 값이 증가한다.

```sql
SELECT n.name, m.value
FROM   v$mystat m JOIN v$statname n ON n.statistic# = m.statistic#
WHERE  n.name IN ('consistent gets',
                  'data blocks consistent reads - undo records applied',
                  'transaction tables consistent reads - undo records applied');
```

두 번째 통계가 오르면 데이터 블록을 되돌리기 위해 undo를 적용했다는 뜻이고, 세 번째 통계는 undo 세그먼트 헤더의 트랜잭션 테이블 자체를 과거 상태로 되돌려야 했다는 뜻이다. 후자가 크게 나오는 쿼리는 undo 헤더까지 되감을 만큼 오래 실행된 쿼리이며, ORA-01555 위험 지표로 쓸 만하다.

트랜잭션 수준의 일관성이 필요하면 SET TRANSACTION READ ONLY 또는 ISOLATION LEVEL SERIALIZABLE을 쓴다. 이 경우 기준 SCN이 문장이 아니라 트랜잭션 시작 시점이 되어, 트랜잭션이 길어질수록 필요한 undo 보존 기간도 길어진다. SELECT ... FOR UPDATE는 과거 이미지가 아닌 현재 블록을 읽으므로 CR 생성 대상이 아니다.

## 4. Delayed Block Cleanout과 ORA-01555의 발생 메커니즘

ORA-01555는 "snapshot too old: rollback segment number n with name ... too small"로 표시되며, 읽기 일관성에 필요한 undo 정보가 이미 덮어쓰여 CR 블록을 만들 수 없을 때 발생한다. 원인은 크게 두 갈래다.

첫 번째는 정공법적인 경우다. 오래 걸리는 쿼리가 블록을 읽는데, 그 사이 다른 트랜잭션들이 커밋하며 undo가 UNEXPIRED 상태에서 덮어쓰였다. 쿼리 SCN 시점으로 되돌릴 undo 레코드가 사라졌으므로 에러가 난다. 쿼리 실행 시간이 UNDO_RETENTION이나 실제 보존 기간보다 길고, 같은 시간대에 undo 발생량이 많을수록 확률이 높아진다.

두 번째는 delayed block cleanout이 얽힌 경우다. 트랜잭션이 커밋할 때 Oracle은 변경한 모든 블록을 다시 방문해 ITL을 정리하지 않는다. 커밋은 undo 세그먼트 헤더의 트랜잭션 테이블에 커밋 SCN을 기록하는 것으로 끝나고, 데이터 블록의 ITL은 나중에 그 블록을 처음 읽는 세션이 정리한다. 그런데 큰 변경이 버퍼 캐시 용량을 넘어 블록이 이미 디스크에 쓰였다면, 이후 읽기 세션은 ITL이 가리키는 트랜잭션의 커밋 SCN을 알아내기 위해 undo 헤더의 트랜잭션 테이블 슬롯을 조회해야 한다. 슬롯이 이미 재사용되었으면 정확한 커밋 SCN 대신 상한만 알 수 있고, 그 상한이 쿼리 SCN보다 크면 안전하게 판단할 수 없어 CR이 필요해지며, 이때 필요한 undo가 없으면 ORA-01555가 난다. 즉 읽기 전용으로 보이는 쿼리가 배치로 큰 갱신을 한 직후의 테이블을 스캔할 때 흔히 터진다.

대표적인 안티패턴은 커서를 열어 둔 채 루프 안에서 같은 테이블을 갱신하고 COMMIT 하는 fetch across commit이다.

```sql
-- 안티패턴: 커서를 연 채 커밋을 반복한다
DECLARE
  CURSOR c IS SELECT id FROM big_orders;
BEGIN
  FOR r IN c LOOP
    UPDATE big_orders SET flag = 'Y' WHERE id = r.id;
    COMMIT;            -- 자기 undo가 UNEXPIRED로 바뀌어 덮어쓰이기 쉽다
  END LOOP;
END;
/
```

커밋할 때마다 자신이 만든 undo가 보호받지 못하는 상태가 되고, 같은 쿼리가 나중에 그 블록을 다시 읽을 때 필요한 이전 이미지가 사라진다. 해결은 커밋 횟수를 줄이거나, 단일 UPDATE 문 또는 BULK COLLECT와 LIMIT, ROWID 범위 분할로 바꾸는 것이다.

LOB 열을 읽을 때는 비슷한 원인으로 ORA-22924(snapshot too old)가 나오기도 한다. LOB는 PCTVERSION 또는 RETENTION 설정에 따라 LOB 세그먼트 안에서 과거 버전을 별도로 관리하기 때문에 undo 테이블스페이스를 키워도 해결되지 않을 수 있다.

## 5. UNDO_RETENTION, 자동 튜닝, Retention Guarantee

UNDO_RETENTION은 커밋된 undo를 유지하려는 목표 시간(초)이다. 기본 동작에서 Oracle은 공간이 충분하면 이 시간 이상 보존하려 하지만, 새 트랜잭션이 공간을 필요로 하면 UNEXPIRED 익스텐트도 재사용한다. 이것이 "목표값이지 보장값이 아니다"라는 말의 의미다.

undo 테이블스페이스의 데이터 파일에 AUTOEXTEND가 켜져 있으면 동작이 달라진다. 이 경우 Oracle은 시스템에서 관측한 가장 긴 쿼리 길이에 맞춰 보존 기간을 자동으로 늘리고, 그 값은 V$UNDOSTAT의 TUNED_UNDORETENTION 열에 나타난다. 이 값은 설정한 UNDO_RETENTION보다 커질 수 있다. 반대로 데이터 파일이 고정 크기이면 자동 튜닝은 현재 공간 안에서 가능한 범위로 제한된다.

보존을 반드시 지켜야 하는 요구가 있으면 retention guarantee를 건다.

```sql
ALTER SYSTEM SET undo_retention = 7200 SCOPE=BOTH;     -- 2시간 목표
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;
SELECT tablespace_name, retention FROM dba_tablespaces
WHERE  tablespace_name = 'UNDOTBS1';                    -- GUARANTEE
-- 해제
ALTER TABLESPACE undotbs1 RETENTION NOGUARANTEE;
```

보장 모드에서는 UNEXPIRED 익스텐트를 절대 덮어쓰지 않으므로 ORA-01555는 줄지만, 대신 공간이 모자랄 때 쿼리가 아니라 DML이 실패한다(ORA-30036). 읽기 쪽 안정성을 쓰기 쪽 가용성과 맞바꾸는 셈이어서, 일반 OLTP에서는 신중히 써야 한다. Flashback 기능이 필수인 환경이거나, 보고서 쿼리가 길고 DML 부하가 예측 가능한 환경에서만 권한다.

| 구분 | 기본(NOGUARANTEE) | RETENTION GUARANTEE |
|---|---|---|
| UNEXPIRED 익스텐트 | 공간 부족 시 덮어쓰기 허용 | 덮어쓰기 금지 |
| 공간 부족 시 실패 대상 | 장시간 SELECT (ORA-01555) | 신규 DML (ORA-30036) |
| 보존 기간 의미 | 최선 노력 목표값 | 사실상 최소 보장값 |
| AUTOEXTEND 영향 | 보존 기간 자동 연장 가능 | 공간이 자라지 않으면 DML 중단 |
| 권장 환경 | 일반 OLTP | flashback 의존 또는 보고용 장시간 쿼리 |

## 6. V$UNDOSTAT 진단과 Undo 크기 산정

V$UNDOSTAT은 10분 단위 행으로 undo 사용 통계를 제공한다. 진단에서 쓰는 주요 열은 UNDOBLKS(그 구간에 소비한 undo 블록 수), TXNCOUNT(트랜잭션 수), MAXQUERYLEN(가장 긴 쿼리 실행 시간, 초), MAXQUERYID(해당 SQL_ID), SSOLDERRCNT(ORA-01555 발생 횟수), NOSPACEERRCNT(공간 부족 오류 횟수), UNXPBLKREUCNT(UNEXPIRED 블록이 재사용된 수), TUNED_UNDORETENTION이다.

```sql
SELECT TO_CHAR(begin_time,'MM-DD HH24:MI') bt,
       undoblks, txncount, maxquerylen, maxqueryid,
       ssolderrcnt, nospaceerrcnt, unxpblkreucnt, tuned_undoretention
FROM   v$undostat
ORDER  BY begin_time DESC
FETCH FIRST 12 ROWS ONLY;
```

해석은 단순하다. SSOLDERRCNT가 0이 아닌 구간에서 MAXQUERYLEN이 TUNED_UNDORETENTION이나 UNDO_RETENTION보다 길다면, 그 구간의 장시간 쿼리가 원인이다. UNXPBLKREUCNT가 양수라는 것은 이미 UNEXPIRED 블록이 덮어쓰이기 시작했다는 뜻이므로 에러가 나기 전의 선행 경고다. MAXQUERYID로 SQL을 찾아 튜닝 대상으로 삼을 수 있다.

필요한 undo 공간은 다음 공식으로 근사한다. 필요 용량 = UNDO_RETENTION(초) x 초당 undo 블록 수(UPS) x 블록 크기. UPS는 V$UNDOSTAT의 UNDOBLKS 최대치를 600초로 나눈 값을 쓴다.

```sql
SELECT ROUND(MAX(undoblks)/600, 1)                                   ups,
       (SELECT value FROM v$parameter WHERE name = 'undo_retention') ur,
       (SELECT value FROM v$parameter WHERE name = 'db_block_size')  bs,
       ROUND(MAX(undoblks)/600
             * (SELECT value FROM v$parameter WHERE name='undo_retention')
             * (SELECT value FROM v$parameter WHERE name='db_block_size')
             / 1024/1024/1024, 2) needed_gb
FROM   v$undostat;
```

예시로 피크 구간 UNDOBLKS가 120,000이면 UPS는 200이고, UNDO_RETENTION 900초, 8KB 블록이면 200 x 900 x 8192 = 약 1.47GB(1.37GiB)다. 보존을 7,200초로 늘리면 같은 부하에서 약 11.8GB로 8배가 된다. 배치가 일 1회 몰리는 시스템에서는 평균이 아닌 피크 UPS로 계산해야 하며, 여기에 활성 트랜잭션용 여유를 20~30% 더하는 것이 보통이다. 장기적 추이는 AWR이 저장하는 DBA_HIST_UNDOSTAT으로 본다.

## 7. Flashback Query: AS OF, VERSIONS BETWEEN, Transaction Query

Flashback Query는 undo의 과거 이미지를 일부러 읽는 기능이다. 읽기 일관성이 "내 쿼리 시작 시점"으로 되돌리는 것이라면, Flashback Query는 사용자가 지정한 과거 시점으로 되돌린다. 동작 원리는 같아서 CR 블록을 만들 때 쿼리 SCN 대신 지정한 SCN을 쓸 뿐이다. 따라서 필요한 undo가 UNDO_RETENTION 안에서 남아 있어야 하며, 그렇지 않으면 ORA-01555가 발생한다.

```sql
-- 15분 전 상태 조회
SELECT * FROM emp AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '15' MINUTE)
WHERE  empno = 7369;

-- SCN 기준
SELECT * FROM emp AS OF SCN 8123456 WHERE empno = 7369;

-- 실수로 지운 행을 복구: 과거 이미지에서 되살린다
INSERT INTO emp
SELECT * FROM emp AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '30' MINUTE)
WHERE  empno = 7369;
```

시간 지정에는 한계가 있다. 타임스탬프는 내부적으로 SCN으로 변환되는데, SCN과 시간의 매핑은 약 3초 단위 정밀도로 저장되므로 AS OF TIMESTAMP는 정확한 초 단위가 아니라 근사 시점이다. 정확히 하려면 SCN을 직접 지정한다. 또한 DDL(열 추가/삭제, 테이블 truncate 등)로 구조가 바뀐 시점 이전으로는 조회할 수 없고 ORA-01466(unable to read data - table definition has changed)이 난다. 지정 시점이 보존 범위 밖이면 ORA-08180(no snapshot found based on specified time) 또는 ORA-01555가 보인다.

행이 시간에 따라 어떻게 바뀌었는지는 VERSIONS BETWEEN으로 본다.

```sql
SELECT versions_starttime, versions_endtime,
       versions_xid, versions_operation, sal
FROM   emp VERSIONS BETWEEN TIMESTAMP
       (SYSTIMESTAMP - INTERVAL '1' HOUR) AND SYSTIMESTAMP
WHERE  empno = 7369
ORDER  BY versions_starttime;
-- 열: VERSIONS_XID, VERSIONS_OPERATION(I/U/D) 등 의사열
```

VERSIONS_XID로 해당 변경을 만든 트랜잭션을 알면 FLASHBACK_TRANSACTION_QUERY 뷰에서 그 트랜잭션이 실행한 SQL과 되돌리는 SQL(UNDO_SQL)을 볼 수 있다. 이 뷰를 쓰려면 supplemental logging(최소 `ALTER DATABASE ADD SUPPLEMENTAL LOG DATA`)이 켜져 있어야 하고 SELECT ANY TRANSACTION 권한이 필요하다.

```sql
SELECT operation, table_name, undo_sql
FROM   flashback_transaction_query
WHERE  xid = HEXTORAW('0A001B0042010000');   -- 위 쿼리의 VERSIONS_XID
```

세션 전체를 과거 시점으로 고정하는 방법은 DBMS_FLASHBACK 패키지다. ENABLE_AT_TIME 또는 ENABLE_AT_SYSTEM_CHANGE_NUMBER 호출 후에는 세션이 DISABLE을 호출하기 전까지 모든 쿼리가 과거를 보고, 이 모드에서는 DML이 허용되지 않는다. 시점 변환에는 TIMESTAMP_TO_SCN과 SCN_TO_TIMESTAMP 함수를 쓰며, 매핑 정보 보존이 제한적이라 오래된 SCN은 변환에 실패할 수 있다.

| 기능 | 데이터 원천 | 주요 제약 |
|---|---|---|
| AS OF TIMESTAMP/SCN | undo | 보존 기간, DDL 이전 불가 |
| VERSIONS BETWEEN | undo | 행 단위 이력, 보존 기간 |
| FLASHBACK_TRANSACTION_QUERY | undo + redo(보충 로깅) | supplemental logging, 권한 필요 |
| Flashback Table | undo | ROW MOVEMENT 활성화 필요 |
| Flashback Data Archive | 전용 아카이브 테이블 | 별도 설정, 보존 기간 지정 |

## 8. 실무 대응: ORA-01555 점검 순서와 튜닝 포인트

ORA-01555를 만나면 감으로 undo를 늘리기 전에 알림 로그와 V$UNDOSTAT으로 원인을 구분한다. 먼저 에러 메시지의 세그먼트 번호와 SQL을 확인하고, 해당 시점의 MAXQUERYLEN, UNXPBLKREUCNT, NOSPACEERRCNT를 조회한다. 쿼리 실행 시간이 보존 기간보다 길었다면 쿼리를 줄이는 것이 우선이고, 보존 기간 안에서 끝났는데도 났다면 undo 공간 부족이나 delayed cleanout을 의심한다.

대응책은 비용이 낮은 순서로 적용하는 편이 낫다. 첫째, 쿼리 자체를 튜닝하거나 처리 범위를 파티션/ROWID 구간으로 나눈다. 둘째, fetch across commit 패턴을 제거한다. 셋째, 대량 갱신 직후에는 해당 테이블을 한 번 전체 스캔하거나 통계 수집으로 블록 cleanout을 미리 끝내 둔다. 넷째, undo 테이블스페이스를 키우고 AUTOEXTEND를 켠 뒤 UNDO_RETENTION을 최장 쿼리 시간 이상으로 설정한다. 다섯째, 정말 필요한 경우에만 retention guarantee를 건다. 읽기 부하를 Active Data Guard 대기 DB로 옮기는 것도 근본적인 방법이다.

```sql
-- 최근 ORA-01555 발생 구간과 원인 SQL
SELECT begin_time, maxquerylen, maxqueryid, ssolderrcnt, nospaceerrcnt
FROM   v$undostat
WHERE  ssolderrcnt > 0
ORDER  BY begin_time DESC;

SELECT sql_id, elapsed_time/1e6 sec, sql_text
FROM   v$sqlarea WHERE sql_id = '&maxqueryid';

-- undo 소비가 큰 활성 세션
SELECT s.sid, s.username, t.used_ublk
FROM   v$transaction t JOIN v$session s ON s.taddr = t.addr
ORDER  BY t.used_ublk DESC;
```

12c 이후에는 TEMP_UNDO_ENABLED=TRUE로 임시 테이블의 undo를 임시 테이블스페이스에 쓰게 할 수 있어, 임시 테이블 DML이 일반 undo와 redo를 늘리는 문제를 줄인다. 또한 flashback을 업무 요건으로 삼는다면, undo 보존 기간에 의존하는 대신 Flashback Data Archive로 특정 테이블의 이력을 장기 보관하는 편이 안정적이다. 정리하면 undo 크기와 보존 정책은 쓰기 가용성, 읽기 일관성, 저장 비용 사이의 균형 문제이며, V$UNDOSTAT으로 실측한 UPS와 MAXQUERYLEN을 근거로 정하는 습관이 가장 중요하다.

## 참고

- Oracle Database Concepts - Data Concurrency and Consistency (Read Consistency, Undo): https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-concurrency-and-consistency.html
- Oracle Database Administrator's Guide - Managing Undo: https://docs.oracle.com/en/database/oracle/oracle-database/19/admin/managing-undo.html
- Oracle Database Development Guide - Using Oracle Flashback Technology: https://docs.oracle.com/en/database/oracle/oracle-database/19/adfns/flashback.html
- Oracle Database Reference - V$UNDOSTAT, DBA_UNDO_EXTENTS, FLASHBACK_TRANSACTION_QUERY: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/
- Thomas Kyte, Expert Oracle Database Architecture (Apress) - Undo 및 Redo 장
