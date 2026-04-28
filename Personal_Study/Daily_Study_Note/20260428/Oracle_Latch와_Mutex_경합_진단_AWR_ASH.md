Notion 원본: https://www.notion.so/3505a06fd6d38143827cfae4fcb2e166

# Oracle Latch와 Mutex 경합 진단 — AWR / ASH 활용 실전

> 2026-04-28 신규 주제 · 확장 대상: Oracle, SQLD

## 학습 목표

- Oracle Latch와 Mutex의 차이를 SGA 자료구조 보호 관점에서 구분한다.
- AWR / ASH 보고서에서 경합을 식별하는 wait event 시그니처를 읽어낸다.
- `cursor: pin S wait on X`, `library cache: mutex X` 핫스팟의 원인을 SQL ID 단위로 추적한다.
- 라이브러리 캐시 경합을 줄이기 위한 SQL 튜닝 / 시퀀스 캐시 / 바인드 변수 / hash partition latch 분산을 실행 가능한 코드로 적용한다.

## 1. Latch와 Mutex의 자료구조 차이

Oracle은 SGA의 공유 자료구조(예: 라이브러리 캐시 버킷, 버퍼 캐시 LRU 체인, 리두 카피 버퍼)를 보호하기 위해 두 종류의 직렬화 메커니즘을 쓴다. Latch는 단일 atomic 변수에 spin-wait 또는 sleep 방식으로 접근을 직렬화하는 가벼운 락이다. 10g까지는 거의 모든 곳에서 latch가 쓰였다. Mutex는 11g부터 도입되어 cursor pin / library cache pin / library cache hash chain 같은 일부 경합 핫스팟을 더 작은 단위로 잘게 나누어 보호한다. 둘 다 전통적인 OS-level mutex와는 다르며, "shared/exclusive" 모드를 가지지만 수면-깨우기 비용이 OS 락보다 훨씬 작다.

핵심 차이는 다음과 같다. Latch는 instance-wide global 자원이라 동일 latch 번호 하나에 모든 세션이 줄을 선다. Mutex는 보호 대상 자체(예: SQL 커서 객체)에 직접 임베드되어 있어 객체 단위로 독립적이다. 따라서 같은 SQL을 수만 번 실행하는 시스템에서는 그 SQL 한 개의 mutex만 핫해진다.

## 2. wait event 사전

| event | 보호 대상 | 발생 패턴 |
| --- | --- | --- |
| latch: cache buffers chains | 버퍼 캐시 hash bucket | 같은 블록을 여러 세션이 동시 읽기 |
| latch: shared pool | 공유 풀 free list | 하드 파싱 폭증, ORA-04031 직전 |
| library cache: mutex X | 커서/객체 핸들 | 동일 SQL 고동시성 실행 + 무효화 |
| cursor: pin S wait on X | child cursor 핀 | DDL/통계 수집 중 같은 SQL 실행 |
| cursor: pin S | child cursor share-pin | child cursor 폭증 |
| latch: row cache objects | 데이터 딕셔너리 캐시 | 시퀀스 NEXTVAL 폭증, recursive SQL |

이 매핑은 진단의 출발점이다. AWR Top 10 Foreground Events에 위 이벤트가 1순위로 잡히면 곧장 ASH 분석으로 넘어간다.

## 3. AWR에서 경합 식별

먼저 시간대를 잡고 보고서를 생성한다.

```sql
-- snap id 후보 확인
SELECT snap_id, begin_interval_time
FROM dba_hist_snapshot
WHERE begin_interval_time BETWEEN
      TO_DATE('2026-04-28 09:00','YYYY-MM-DD HH24:MI')
  AND TO_DATE('2026-04-28 11:00','YYYY-MM-DD HH24:MI')
ORDER BY snap_id;

-- 보고서 생성
@?/rdbms/admin/awrrpt
```

Top Timed Events 섹션에서 wait class 가 "Concurrency"인 항목의 비중을 본다. DB Time 대비 30% 이상이면 경합이 시스템 처리량을 좌우하는 상태이다. 그 다음 "SQL ordered by Parse Calls"와 "Library Cache Activity" 섹션을 확인한다. 후자에는 namespace별 hit ratio, reload, invalidations 가 표시된다. invalidation이 높으면 (>10/s) DDL 또는 통계 수집이 실행 계획 캐시를 무효화시키고 있다는 신호이다.

## 4. ASH로 핫 SQL과 핫 객체 추적

ASH는 1초 단위 활성 세션 샘플이다. 누적 이벤트가 아니라 "그 순간 누가 어디서 막혔는가"를 보여 준다.

```sql
SELECT sql_id, event, p1, p2, p3, COUNT(*) AS samples
FROM   gv$active_session_history
WHERE  sample_time BETWEEN
       TIMESTAMP '2026-04-28 09:30:00'
   AND TIMESTAMP '2026-04-28 09:45:00'
   AND event IN (
       'library cache: mutex X',
       'cursor: pin S wait on X',
       'latch: shared pool'
   )
GROUP BY sql_id, event, p1, p2, p3
ORDER BY samples DESC
FETCH FIRST 20 ROWS ONLY;
```

`library cache: mutex X`의 P1은 hash 값, P2는 mutex value, P3은 location. 동일 sql_id가 압도적으로 많이 잡히면 그 SQL의 child cursor 가 핫스팟이다. `cursor: pin S wait on X`에서 P2는 holder의 SID 인코딩 값이라 다음으로 디코드한다.

```sql
SELECT s.sid, s.serial#, s.username, s.program, s.event, s.sql_id
FROM   v$session s
WHERE  s.sid = TO_NUMBER(BITAND(:p2, POWER(2,16)-1));
```

이렇게 holder를 특정하면 이 세션이 무엇을 하는지(통계 수집, DDL, 긴 파싱) 알아내는 게 다음 작업이다.

## 5. 사례 — 시퀀스 NEXTVAL 폭증

배치 잡이 야간에 매 행마다 시퀀스 NEXTVAL을 호출하면서 row cache objects latch가 핫해졌다. 시퀀스의 CACHE 값이 20이라 매 20건마다 SGA 데이터 딕셔너리 캐시를 갱신해야 했다. 처리 라인 수가 시간당 5천만 건일 때, 250만 회/시 = 700/s의 row cache 갱신이 발생한다.

진단 쿼리:

```sql
SELECT sequence_owner, sequence_name, cache_size
FROM   dba_sequences
WHERE  cache_size <= 100;
```

수정:

```sql
ALTER SEQUENCE app.order_seq CACHE 5000;
```

대부분의 OLTP에서는 1000~5000 정도가 적정이다. RAC 환경에서는 인스턴스별 캐시이므로 시퀀스 값 점프 허용 여부를 확인해야 한다. 강한 ORDER BY 의미가 필요하면 NOCACHE/ORDER 가 강제되는데, 이때 RAC 인스턴스 간 SCN 협상으로 더 큰 비용이 든다.

## 6. 사례 — `cursor: pin S wait on X` 폭주

정시 통계 수집 잡이 `dbms_stats.gather_table_stats`를 실행하는 중 OLTP 핫 SQL의 child cursor가 invalidate 되어 reparsing 폭증, 동일 SQL을 동시에 수백 세션이 실행하면서 child cursor 핀 경합이 발생했다.

확인 쿼리:

```sql
SELECT child_number, executions, parse_calls, last_active_time, plan_hash_value
FROM   v$sql
WHERE  sql_id = 'fkbq2x9zgu7q0'
ORDER BY child_number;
```

child cursor가 50개 이상 만들어졌다면 bind sensitivity / NLS / cursor sharing 으로 인한 분기이다. 해결 방향은 두 가지다. 첫째, 통계 수집을 점진(incremental) 모드로 전환하여 invalidate 시간을 줄인다.

```sql
EXEC DBMS_STATS.SET_TABLE_PREFS('APP','ORDERS','INCREMENTAL','TRUE');
EXEC DBMS_STATS.SET_TABLE_PREFS('APP','ORDERS','INCREMENTAL_LEVEL','PARTITION');
```

둘째, `NO_INVALIDATE => DBMS_STATS.AUTO_INVALIDATE`로 무효화를 시간에 분산시킨다.

```sql
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname     => 'APP',
    tabname     => 'ORDERS',
    method_opt  => 'FOR ALL COLUMNS SIZE AUTO',
    no_invalidate => DBMS_STATS.AUTO_INVALIDATE);
```

## 7. 사례 — `library cache: mutex X`와 cursor obsolete

같은 SQL이 100개의 child cursor를 누적했고, 11g 후반부터 적용된 cursor obsolescence(2.5만회 이상의 child 수에서 obsolete) 메커니즘이 Mutex 경합을 만든다. 11.2 패치 이전에는 child cursor 수가 사실상 무제한이었다. 12c 이후는 `_cursor_obsolete_threshold` (기본 1024)로 제어된다.

```sql
SELECT KSPPINM, KSPPSTVL
FROM   sys.x$ksppi a, sys.x$ksppcv b
WHERE  a.indx = b.indx AND a.KSPPINM = '_cursor_obsolete_threshold';
```

Mutex 경합이 child cursor 폭증에서 비롯되면 다음을 점검한다. 바인드 변수 누락(literal 값으로 SQL이 매번 다르게 보임), `OPTIMIZER_ADAPTIVE_FEATURES`로 인한 plan 분기, `CURSOR_SHARING=FORCE`의 부작용. 운영에서는 다음 패치를 적용한다.

```sql
ALTER SYSTEM SET cursor_sharing = EXACT SCOPE=BOTH;
-- 그리고 애플리케이션 단의 PreparedStatement 사용 강제
```

JDBC라면 `oracle.jdbc.implicitStatementCacheSize=200` 같은 statement cache 설정으로 hard parse를 줄인다.

## 8. cache buffers chains latch 분산

`latch: cache buffers chains` 경합은 buffer cache hash bucket 단위 latch에서 발생한다. hash bucket 수는 `_db_block_hash_buckets` (자동 산정)이고, latch 수는 `_db_block_hash_latches`이다. 둘 다 자동 조정이 권장이지만 매우 핫한 단일 블록(예: 인덱스 루트 블록)에서는 어떻게 해도 직렬화된다. 해결 방향은 SQL이 핫 블록을 덜 만지게 만드는 것이다. 단조 증가 시퀀스 PK + 시간 기반 인덱스 → reverse key index 또는 hash partitioned index, 한 행 update 폭주 → row-level lock 분산을 위한 multi-row 분할.

```sql
-- reverse key index로 leaf 블록 분산
CREATE INDEX app.idx_orders_id ON app.orders(id) REVERSE;

-- hash partitioned global index (12c+)
CREATE INDEX app.idx_orders_id ON app.orders(id) GLOBAL
PARTITION BY HASH (id) PARTITIONS 8;
```

## 9. 자가 검진 스크립트

```sql
WITH top_concurrency AS (
  SELECT event, SUM(time_waited_micro)/1e6 AS sec_waited
  FROM   dba_hist_system_event
  WHERE  wait_class = 'Concurrency'
    AND  snap_id BETWEEN :begin_snap AND :end_snap
  GROUP BY event
)
SELECT event, ROUND(sec_waited,1) AS sec_waited
FROM   top_concurrency
ORDER BY sec_waited DESC
FETCH FIRST 10 ROWS ONLY;
```

이 결과를 매주 같은 시간대에 비교하면 경합이 누적되고 있는지를 본다. 30% 이상 증가했다면 새로 배포된 SQL/잡이 원인일 가능성이 높다.

## 10. 운영 가이드 요약

라이브러리 캐시 mutex 경합은 대부분 하드 파싱 폭증이 본질이다. 바인드 변수 일관성 점검, statement cache 설정, 통계 수집 invalidation 분산, child cursor 분기 원인 제거(adaptive features off, cursor sharing exact)가 90% 사례를 해결한다. cache buffers chains latch 경합은 SQL이 같은 블록을 비정상적으로 자주 건드리는 핫 블록 문제이며, 인덱스 설계 및 데이터 분포 조정이 정공법이다. 시퀀스/딕셔너리 latch 경합은 캐시 크기 조정이 첫 처방이다.

## 참고

- Oracle Database Performance Tuning Guide 19c — Library Cache Concurrency
- Tanel Põder, "Oracle Mutex Hashtables and Cursor: pin S wait on X" 블로그 시리즈
- Christian Antognini, "Troubleshooting Oracle Performance" 2nd Edition — Chapter 13
- My Oracle Support Note 1298015.1 — Library Cache: Mutex X Wait
- Jonathan Lewis, "Cost-Based Oracle Fundamentals" — Hot block 분석 절
