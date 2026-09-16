Notion 원본: https://www.notion.so/3dd5a06fd6d38153840dc68697d39409

# Oracle AWR ASH 대기 이벤트 분석과 Top SQL 튜닝 및 시계열 진단

> 2026-09-16 신규 주제 · 확장 대상: Oracle 심화(실행계획·인덱스), SQLD 이후의 운영 진단 영역

## 학습 목표

- DB Time 과 AAS 로 "느리다"를 정량 지표로 바꿈다
- AWR 스냅샷과 ASH 샘플의 성격 차이를 알고 상황에 맞게 고른다
- 주요 대기 이벤트 클래스를 원인 계층으로 분류해 다음 조사 지점을 정한다
- ASH 를 직접 쿼리해 특정 시각 구간의 병목을 스스로 좁힌다

## 1. DB Time: 모든 튜닝의 공통 단위

Oracle 성능 진단의 출발점은 **DB Time** 이다. 정의는 "모든 포그라운드 세션이 데이터베이스 호출 안에서 소비한 시간의 합"이며, CPU 사용 시간과 대기 시간을 모두 포함한다. 사용자가 느낀 시간 중 DB 안에서 쓰인 부분이 여기에 들어간다.

DB Time 을 경과 시간(Elapsed)으로 나눈 값이 **AAS**(Average Active Sessions)다.

```
AAS = DB Time / Elapsed Time
```

60분 구간의 DB Time 이 480분이면 AAS 는 8이다. "평균적으로 8개의 세션이 항상 DB 안에서 작업 중이었다"는 뜻이다. 이 값을 CPU 코어 수와 비교하면 첫 판단이 나온다. 코어가 8개인데 AAS 가 8이면 시스템은 포화 상태다. 코어가 32개인데 AAS 가 8이면 CPU 는 여유가 있고 병목은 다른 곳이다.

AWR 리포트 헤더의 다음 줄이 이 계산의 원재료다.

```
              Snap Id      Snap Time        Sessions  Curs/Sess
Begin Snap:      4821  16-Sep-26 14:00:12       412       18.3
  End Snap:      4822  16-Sep-26 15:00:07       438       19.1
   Elapsed:               59.92 (mins)
   DB Time:              481.37 (mins)
```

주의할 함정이 둘 있다. 첫째, DB Time 은 **백그라운드 프로세스를 포함하지 않는다**. DBWR 이나 LGWR 이 바빨도 여기 안 잡힐므로, 별도로 봐야 한다. 둘째, 유휴 대기(idle wait)는 제외된다. `SQL*Net message from client` 는 클라이언트를 기다리는 시간이라 DB Time 에 들어가지 않는다. 그래서 "애플리케이션은 3초 걸리는데 DB Time 은 0.2초"인 상황이 정상적으로 발생한다. 이 경우 병목은 네트워크나 애플리케이션 쪽이다.

## 2. AWR 과 ASH: 집계와 샘플

두 도구는 수집 방식이 근본적으로 다르다.

**AWR**(Automatic Workload Repository)은 기본 60분 간격으로 동적 성능 뷰의 **누적 통계를 스냅샷**으로 저장한다. 두 스냅샷의 차이가 그 구간의 통계다. 장점은 완전성이다. 모든 이벤트가 빠짐없이 집계된다. 단점은 해상도다. 1시간을 하나의 평균으로 뭉개므로, 3분간의 격심한 장애는 평균에 묻힌다.

**ASH**(Active Session History)는 **1초마다 활성 세션의 상태를 샘플링**한다. 각 샘플에는 세션 ID, 현재 SQL_ID, 대기 이벤트, 블로킹 세션, 실행 계획 해시 등이 담긴다. `V$ACTIVE_SESSION_HISTORY` 는 SGA 의 순환 버퍼이며 대개 수십 분에서 몇 시간 분량을 보관한다. 그중 약 10분의 1이 `DBA_HIST_ACTIVE_SESS_HISTORY` 로 디스크에 내려간다.

| 항목 | AWR | ASH |
|---|---|---|
| 수집 방식 | 누적 통계 차분 | 1초 샘플링 |
| 시간 해상도 | 스냅샷 간격(기본 60분) | 1초 |
| 완전성 | 완전 | 통계적 근사 |
| 짧은 세션 포착 | 집계에 포함 | 1초 미만 세션은 누락 가능 |
| 적합한 질문 | "이 시간대 전반의 특성은?" | "14:37 에 무슨 일이 있었나?" |

실무 순서는 대개 이렇다. AWR 로 어느 시간대가 이상한지 찾고, 그 구간을 ASH 로 초 단위로 확대한다. 반대로 하면 노이즈에 파묻힐다.

ASH 의 통계적 성질은 유용한 성질을 하나 더 준다. **샘플 수가 곳 시간**이다. 1초 간격 샘플링이므로 특정 SQL 이 100개 샘플에 나타났다면 그 SQL 은 대략 100초간 활성 상태였다고 읽는다. 이 환산 덕분에 ASH 만으로도 시간 기여도를 계산할 수 있다.

## 3. 대기 이벤트를 클래스로 나눠 읽기

AWR 의 Top 10 Foreground Events 섹션이 진단의 중심이다.

```
Event                          Waits    Total Wait Time (sec)   Wait Avg(ms)   % DB time  Wait Class
------------------------------ -------- ----------------------- -------------- ---------- -----------
db file sequential read        4,821,03              12,847            2.67       44.5    User I/O
DB CPU                                                 8,120                      28.1
enq: TX - row lock contention        412               3,901            9.47       13.5    Application
log file sync                    281,44               1,204            0.43        4.2    Commit
gc buffer busy acquire            18,22                 902           49.51        3.1    Cluster
```

클래스별로 조사 방향이 갈린다.

**User I/O** — `db file sequential read`(단일 블록, 대개 인덱스 경유), `db file scattered read`(다중 블록, 풀 스캔), `direct path read`(PGA 직접 읽기, 병렬/대용량 스캔). 평균 대기가 1ms 미만이면 스토리지는 빠른 것이고, 문제는 **읽는 블록 수가 많다는 것**이다. 즉 SQL 튜닝 대상이다. 평균이 10ms 를 넘으면 스토리지 자체를 의심한다.

**Concurrency** — `latch: cache buffers chains`, `buffer busy waits`, `cursor: pin S`. 같은 블록이나 같은 커서에 세션이 몰린다는 뜻이다. 인덱스 우측 끝에 삽입이 집중되는 핫블록, 또는 바인드 변수를 쓰지 않아 라이브러리 캐시가 경합하는 상황이 전형이다.

**Application** — `enq: TX - row lock contention` 이 대표적이다. 애플리케이션 로직의 락 대기다. 대기 시간이 길다면 트랜잭션 범위가 넓거나 락 획득 순서가 일관되지 않다는 신호다.

**Commit** — `log file sync`. 커밋 시 LGWR 이 리두를 디스크에 쓰기를 기다린다. 평균이 5ms 를 넘으면 리두 로그 디스크의 지연을 본다. 대기 **횟수**가 과도하면 커밋이 너무 잦은 것이다. 루프 안에서 건별 커밋하는 배치가 흔한 원인이며, 이 경우 스토리지를 바꿔도 해결되지 않는다.

**Cluster** — RAC 전용. `gc buffer busy`, `gc cr block busy`. 인스턴스 간 블록 전송 경합이다. 서비스 분리나 파티셔닝으로 노드별 작업 세트를 나누는 것이 근본 대책이다.

여기서 자주 하는 실수는 **1위 이벤트만 보고 달려드는 것**이다. `db file sequential read` 가 44% 라도, 그 대부분이 정상적인 인덱스 조회라면 줄일 여지가 없을 수 있다. 판단은 "이 대기가 어느 SQL 에서 나오는가"까지 내려가야 하고, 그 연결이 바로 ASH 의 역할이다.

## 4. ASH 를 직접 쿼리하기

AWR 리포트를 읽는 것보다 ASH 를 직접 쿼리하는 편이 빠를 때가 많다. 특히 "몇 시 몇 분에 무슨 일이"류의 질문이 그렇다.

먼저 시간대별 AAS 추이를 본다.

```sql
SELECT TO_CHAR(sample_time, 'HH24:MI') AS minute,
       COUNT(*) / 60 AS aas,
       ROUND(SUM(CASE WHEN session_state = 'ON CPU' THEN 1 ELSE 0 END) / 60, 2) AS cpu_aas,
       ROUND(SUM(CASE WHEN session_state = 'WAITING' THEN 1 ELSE 0 END) / 60, 2) AS wait_aas
  FROM v$active_session_history
 WHERE sample_time BETWEEN TIMESTAMP '2026-09-16 14:00:00'
                       AND TIMESTAMP '2026-09-16 15:00:00'
 GROUP BY TO_CHAR(sample_time, 'HH24:MI')
 ORDER BY minute;
```

`COUNT(*) / 60` 이 AAS 인 이유는 1분에 60개의 샘플이 있기 때문이다. 이 결과를 보면 평균에 묻혀 있던 스파이크가 드러난다.

스파이크 구간이 특정되면 그 구간의 기여자를 분해한다.

```sql
SELECT NVL(h.event, 'ON CPU')  AS event,
       h.sql_id,
       COUNT(*)                AS samples,
       ROUND(RATIO_TO_REPORT(COUNT(*)) OVER () * 100, 1) AS pct
  FROM v$active_session_history h
 WHERE h.sample_time BETWEEN TIMESTAMP '2026-09-16 14:36:00'
                         AND TIMESTAMP '2026-09-16 14:40:00'
 GROUP BY NVL(h.event, 'ON CPU'), h.sql_id
 ORDER BY samples DESC
 FETCH FIRST 15 ROWS ONLY;
```

`session_state = 'ON CPU'` 인 샘플은 `event` 가 NULL 이므로 `NVL` 처리가 필요하다. 이 한 줄을 빠뜨리면 CPU 시간이 통째로 사라진다.

행 락 경합이 보이면 블로킹 체인을 추적한다.

```sql
SELECT h.sample_time,
       h.session_id                AS waiter,
       h.blocking_session          AS blocker,
       h.sql_id                    AS waiter_sql,
       o.object_name,
       h.current_obj#,
       h.event
  FROM v$active_session_history h
  LEFT JOIN dba_objects o ON o.object_id = h.current_obj#
 WHERE h.event LIKE 'enq: TX%'
   AND h.sample_time > SYSTIMESTAMP - INTERVAL '30' MINUTE
 ORDER BY h.sample_time;
```

`blocking_session` 이 채워져 있으면 그 SID 를 다시 ASH 에서 찾아 "블로커는 그 시각에 무엇을 하고 있었나"를 본다. 대개 블로커는 대기 중이 아니라 다른 긴 작업을 하고 있거나, 커밋하지 않은 채 애플리케이션 응답을 기다리고 있다. 후자라면 트랜잭션 경계 설계 문제다.

I/O 병목이 특정 세그먼트에 집중되는지도 확인 가능하다.

```sql
SELECT o.owner, o.object_name, o.object_type,
       COUNT(*) AS samples
  FROM v$active_session_history h
  JOIN dba_objects o ON o.object_id = h.current_obj#
 WHERE h.wait_class = 'User I/O'
   AND h.sample_time > SYSTIMESTAMP - INTERVAL '1' HOUR
 GROUP BY o.owner, o.object_name, o.object_type
 ORDER BY samples DESC
 FETCH FIRST 10 ROWS ONLY;
```

## 5. Top SQL 을 고르는 기준

AWR 의 SQL 섹션은 여러 정렬 기준으로 같은 SQL 집합을 보여준다. 어느 것을 볼지는 문제 유형에 달렸다.

| 섹션 | 정렬 기준 | 유용한 상황 |
|---|---|---|
| SQL ordered by Elapsed Time | 총 경과 시간 | 전반적 기여도 |
| SQL ordered by CPU Time | CPU 시간 | CPU 포화 시 |
| SQL ordered by Gets | 논리적 읽기(buffer gets) | 비효율 실행 계획 탐지 |
| SQL ordered by Reads | 물리적 읽기 | I/O 병목 시 |
| SQL ordered by Executions | 실행 횟수 | 과도 호출, N+1 패턴 |

실무에서 가장 먼저 보는 것은 **Gets** 다. 논리적 읽기는 캐시 히트 여부와 무관하게 "이 SQL 이 몇 개의 블록을 만졌는가"를 나타내므로, 실행 계획의 효율을 가장 직접적으로 보여준다. 실행당 Gets 가 수만 건인데 반환 행이 몇 개라면 거의 확실히 인덱스 문제다.

```sql
SELECT sql_id,
       executions,
       ROUND(buffer_gets / GREATEST(executions, 1)) AS gets_per_exec,
       ROUND(rows_processed / GREATEST(executions, 1), 1) AS rows_per_exec,
       ROUND(elapsed_time / GREATEST(executions, 1) / 1000, 1) AS ms_per_exec,
       SUBSTR(sql_text, 1, 80) AS sql_text
  FROM v$sqlstats
 WHERE executions > 0
 ORDER BY buffer_gets DESC
 FETCH FIRST 20 ROWS ONLY;
```

`Executions` 섹션도 놓치기 쉬운 가치가 있다. 실행당 1ms 인 SQL 이 시간당 2백만 번 호출되면 총 33분의 DB Time 을 먹는다. 이 SQL 을 튜닝하는 것보다 **호출 횟수를 줄이는 것**이 정답인 경우가 많고, 원인은 대개 ORM 의 N+1 이다. 이 진단은 DB 안에서만 보면 잘 보이지 않으므로, 애플리케이션 트레이싱과 함께 봐야 한다.

## 6. 실행 계획 변동 추적

같은 SQL 이 어제는 빠랐는데 오늘 느려졌다면 계획 변경을 의심한다. `DBA_HIST_SQLSTAT` 은 스냅샷별로 계획 해시를 보관한다.

```sql
SELECT s.snap_id,
       TO_CHAR(sn.begin_interval_time, 'MM-DD HH24:MI') AS snap_time,
       s.plan_hash_value,
       s.executions_delta AS execs,
       ROUND(s.elapsed_time_delta / GREATEST(s.executions_delta, 1) / 1000, 1) AS ms_per_exec,
       ROUND(s.buffer_gets_delta / GREATEST(s.executions_delta, 1)) AS gets_per_exec
  FROM dba_hist_sqlstat s
  JOIN dba_hist_snapshot sn
    ON sn.snap_id = s.snap_id
   AND sn.instance_number = s.instance_number
 WHERE s.sql_id = 'a1b2c3d4e5f6g'
   AND sn.begin_interval_time > SYSDATE - 7
 ORDER BY s.snap_id;
```

`plan_hash_value` 가 특정 시점부터 바뀌었고 그때부터 `ms_per_exec` 이 치솟았다면 원인이 확정된다. 계획이 바뀜는 흔한 계기는 통계 수집, 바인드 피킹(bind peeking)에 의한 적응적 커서 공유, 파라미터 변경, 인덱스 추가/삭제다.

계획을 고정해야 한다면 SQL Plan Baseline 을 쓴다.

```sql
-- 커서 캐시에서 원하는 계획을 베이스라인으로 등록
DECLARE
	v_count PLS_INTEGER;
BEGIN
	v_count := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(
		sql_id          => 'a1b2c3d4e5f6g',
		plan_hash_value => 1234567890,
		fixed           => 'YES',
		enabled         => 'YES'
	);
	DBMS_OUTPUT.PUT_LINE('loaded: ' || v_count);
END;
/
```

`fixed => 'YES'` 는 옵티마이저가 이 계획을 우선하도록 만든다. 다만 베이스라인 고정은 응급 처치다. 근본 원인(통계 부정확, 바인드 편향, 누락 인덱스)을 두고 계획만 묶으면 데이터 분포가 바뀌었을 때 더 나쁜 결과가 나온다. 고정한 항목은 목록으로 관리하고 주기적으로 재검토해야 한다.

실제 실행 통계를 보려면 `DBMS_XPLAN.DISPLAY_CURSOR` 를 쓴다.

```sql
SELECT * FROM TABLE(
	DBMS_XPLAN.DISPLAY_CURSOR('a1b2c3d4e5f6g', NULL, 'ALLSTATS LAST +PEEKED_BINDS')
);
```

`ALLSTATS LAST` 는 실제 행 수(A-Rows)와 추정 행 수(E-Rows)를 함께 보여준다. 이 둘의 괴리가 큰 연산자가 계획이 틀어진 지점이다. 단 이 정보를 얻으려면 `STATISTICS_LEVEL = ALL` 이거나 SQL 에 `/*+ GATHER_PLAN_STATISTICS */` 힌트가 있어야 한다.

## 7. 라이선스와 대안

AWR 과 ASH 는 Enterprise Edition 의 **Diagnostics Pack** 에 속한다. Standard Edition 이나 팩을 구매하지 않은 환경에서 `DBA_HIST_*`·`V$ACTIVE_SESSION_HISTORY` 를 조회하면 라이선스 위반이 된다. `CONTROL_MANAGEMENT_PACK_ACCESS` 파라미터로 접근을 차단해 사고를 막을 수 있다.

```sql
SHOW PARAMETER control_management_pack_access
-- NONE / DIAGNOSTIC / DIAGNOSTIC+TUNING
```

팩이 없는 환경의 대안은 Statspack 이다. AWR 의 전신이며 무료다. 스냅샷 기반 집계는 유사하게 제공하지만 ASH 에 해당하는 세션 샘플링이 없다. 이를 보완하려면 `V$SESSION` 을 주기적으로 샘플링해 자체 저장하는 스크립트를 두는 방법이 있다.

```sql
-- 간이 ASH: 1초마다 실행해 별도 테이블에 적재
INSERT INTO my_session_samples
SELECT SYSTIMESTAMP, sid, serial#, sql_id, event, wait_class,
       state, blocking_session, program, module
  FROM v$session
 WHERE status = 'ACTIVE'
   AND wait_class <> 'Idle'
   AND type = 'USER';
```

## 8. 진단 절차의 요약

정리하면 다음 순서가 대부분의 경우에 작동한다.

1. **범위 확정**: 사용자가 느린 시각과 대상 기능을 특정한다. "DB 가 느려요"에서 출발하면 끝이 없다.
2. **AAS 확인**: 해당 구간의 AAS 와 CPU 코어 수를 비교해 포화 여부를 본다.
3. **분해**: DB Time 을 대기 클래스로 나눈다. CPU 가 지배적인지, I/O 인지, 락인지 결정한다.
4. **SQL 연결**: 지배적 대기가 어느 SQL_ID 에서 나오는지 ASH 로 확인한다.
5. **계획 확인**: 그 SQL 의 실행 계획과 A-Rows/E-Rows 괴리를 본다.
6. **변경 이력 대조**: 계획 해시가 언제 바뀌었는지, 배포나 통계 수집 시각과 겹치는지 확인한다.
7. **조치와 재측정**: 변경 후 같은 지표를 다시 측정한다. 측정 없는 조치는 추측이다.

마지막 항목이 실무에서 가장 자주 생략된다. 인덱스를 추가하고 "좋아진 것 같다"로 끝내면, 실제로는 다른 SQL 의 계획을 망가뜨렸을 수 있다. 조치 전후의 AWR 을 같은 시간대·같은 요일로 비교하는 습관이 회귀를 막는다.

## 참고

- Oracle Database Performance Tuning Guide — Automatic Workload Repository: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgdba/
- Oracle Database Reference — V$ACTIVE_SESSION_HISTORY: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/
- Oracle Database SQL Tuning Guide — SQL Plan Management: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/
- Oracle Database Licensing Information User Manual — Management Packs
- Cary Millsap, *Optimizing Oracle Performance* — Method R
