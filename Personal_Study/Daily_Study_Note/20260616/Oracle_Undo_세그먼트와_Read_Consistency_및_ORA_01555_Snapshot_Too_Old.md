Notion 원본: https://www.notion.so/3815a06fd6d381a58104f5535d43f156

# Oracle Undo 세그먼트와 Read Consistency 및 ORA-01555 Snapshot Too Old

> 2026-06-16 신규 주제 · 확장 대상: Oracle

## 학습 목표

- Undo 세그먼트가 어떻게 읽기 일관성(read consistency)을 만들어내는지 SCN 관점에서 설명한다
- ORA-01555 가 발생하는 정확한 메커니즘과 재현 조건을 구분한다
- UNDO_RETENTION, 자동 튜닝, Guarantee 옵션의 trade-off 를 판단한다
- 긴 트랜잭션/긴 조회를 가진 워크로드에서 Undo 를 진단·튜닝하는 절차를 적용한다

## 1. 멀티버전 읽기 일관성의 출발점 — SCN 과 Undo

Oracle 은 읽기와 쓰기가 서로를 막지 않는다("readers don't block writers"). 이를 가능하게 하는 핵심이 **Undo** 다. 어떤 트랜잭션이 블록을 변경하면, 변경 *이전* 의 값(before-image)이 Undo 세그먼트에 기록된다. 동시에 모든 쿼리는 시작 시점의 **SCN(System Change Number)** 을 갖는다. 쿼리가 버퍼 캐시에서 블록을 읽을 때, 그 블록의 마지막 변경 SCN 이 자기 쿼리 SCN 보다 크면(= 내 쿼리가 시작된 후에 바뀐 블록이면) 그 변경을 "되돌려" 봐야 한다.

이때 Oracle 은 현재 블록을 복사한 뒤 Undo 에 저장된 before-image 를 적용해 **CR(Consistent Read) 블록**을 메모리에 구성한다. 쿼리는 이 CR 블록을 읽는다. 즉 디스크의 실제 블록은 최신 상태(current)지만, 각 쿼리는 자신의 SCN 시점 스냅샷을 본다. 이 과정이 멀티버전 동시성 제어(MVCC)의 Oracle 식 구현이다.

```sql
-- 현재 SCN 확인
SELECT current_scn FROM v$database;

-- 특정 과거 SCN 시점 데이터 조회 (Flashback Query — 동일하게 Undo 를 사용)
SELECT * FROM orders AS OF SCN 12345678 WHERE id = 100;
SELECT * FROM orders AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE);
```

Flashback Query 가 Undo 위에서 동작한다는 점은 Undo 의 본질을 잘 보여준다 — Undo 는 단순 롤백용이 아니라 "과거 버전 저장소" 다.

## 2. Undo 세그먼트의 순환 구조

Undo 테이블스페이스는 여러 Undo 세그먼트로 나뉘고, 각 세그먼트는 extent 들의 **원형 큐**처럼 동작한다. 커밋된 트랜잭션의 Undo 는 즉시 지워지지 않고 "만료 가능(expired)" 상태로 남아 있다가, 공간이 부족하면 가장 오래된 것부터 재사용(overwrite)된다. 여기서 핵심 긴장이 생긴다 — **커밋된 Undo 라도 누군가의 오래된 쿼리가 그 before-image 를 필요로 할 수 있다.**

Undo 상태는 세 가지다. ACTIVE(아직 커밋 안 된 트랜잭션), UNEXPIRED(커밋됐지만 UNDO_RETENTION 기간 내라 보존), EXPIRED(보존 기간 지나 재사용 후보).

```sql
-- Undo 사용 현황: 상태별 공간
SELECT tablespace_name, status, ROUND(SUM(bytes)/1024/1024) mb
FROM dba_undo_extents GROUP BY tablespace_name, status;

-- 세그먼트 자동 튜닝된 retention (초)
SELECT TO_CHAR(begin_time,'HH24:MI') t, tuned_undoretention, maxquerylen, ssolderrcnt
FROM v$undostat ORDER BY begin_time DESC FETCH FIRST 12 ROWS ONLY;
```

`v$undostat` 의 `ssolderrcnt`(snapshot-too-old 에러 카운트)와 `maxquerylen`(최장 쿼리 길이, 초)은 ORA-01555 진단의 1차 지표다.

## 3. ORA-01555 "Snapshot Too Old" 의 정확한 메커니즘

ORA-01555 는 **오래 도는 쿼리가, 자신이 필요로 하는 before-image 가 이미 다른 트랜잭션에 의해 덮어써졌을 때** 발생한다. 시나리오는 전형적으로 이렇다.

1. 09:00 — 큰 테이블을 도는 보고 쿼리 시작(SCN = S0). 풀스캔에 30분 소요.
2. 09:05~09:25 — 다른 OLTP 트랜잭션들이 같은 테이블을 활발히 변경/커밋. Undo 공간이 순환하며 09:00 이전 before-image 를 덮어씀.
3. 09:28 — 보고 쿼리가 "09:05 에 변경된 블록" 에 도달. S0 시점으로 되돌리려고 Undo 를 찾는데, 그 before-image 가 이미 덮어써져 없음 → **ORA-01555**.

즉 원인은 "쿼리가 길다 + Undo 보존이 짧다 + 동시 변경이 많다" 의 조합이다. 흔한 오해와 달리, 같은 테이블을 변경하지 않아도 발생할 수 있다(Undo 공간은 전역 자원이라 다른 테이블 변경이 내 before-image 를 밀어낼 수 있다).

또 다른 변종은 **delayed block cleanout** 이다. 대량 변경 후 커밋했지만 블록의 커밋 정보(ITL)가 아직 정리되지 않은 상태에서, 후속 쿼리가 그 블록의 커밋 SCN 을 확인하려 Undo 헤더를 봐야 하는데 그 트랜잭션 슬롯이 이미 재사용되어 SCN 을 확정할 수 없을 때도 ORA-01555 가 난다.

```sql
-- 안티패턴: "fetch across commit" — 커서를 돌면서 중간중간 커밋
-- 자기 자신이 Undo 를 재사용 가능 상태로 만들어 스스로 ORA-01555 를 유발
DECLARE CURSOR c IS SELECT id FROM big_table;
BEGIN
  FOR r IN c LOOP
    UPDATE big_table SET flag='Y' WHERE id=r.id;
    IF MOD(r.id,1000)=0 THEN COMMIT; END IF; -- 위험!
  END LOOP;
END;
```

## 4. UNDO_RETENTION 과 자동 튜닝, 그리고 Guarantee

`UNDO_RETENTION`(초)은 "커밋된 Undo 를 최소 이만큼 보존하려 시도" 하는 **목표값** 이지 강제값이 아니다. 자동 확장(AUTOEXTEND ON) Undo 테이블스페이스에서는 Oracle 이 `v$undostat.tuned_undoretention` 으로 retention 을 자동 조정한다. 고정 크기 테이블스페이스에서는 공간이 부족하면 retention 을 무시하고 UNEXPIRED Undo 를 덮어쓴다 — 이때 ORA-01555 위험이 커진다.

**Guarantee** 옵션을 켜면 의미가 바뀐다.

```sql
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;
```

이제 Oracle 은 UNDO_RETENTION 기간 내의 Undo 를 **절대 덮어쓰지 않는다**. ORA-01555 는 거의 사라지지만, 대신 OLTP 트랜잭션이 Undo 공간을 못 얻어 **ORA-30036(unable to extend undo)** 으로 실패할 수 있다. 즉 trade-off 가 명확하다 — Guarantee 는 "조회 일관성" 을 "쓰기 가용성" 보다 우선한다. 야간 배치/리포팅 윈도우에만 켜고 평소엔 끄는 운용도 흔하다.

| 설정 | ORA-01555 위험 | ORA-30036 위험 | 적합 상황 |
|---|---|---|---|
| AUTOEXTEND + 자동 튜닝 | 중 | 낮음(디스크 한계까지) | 일반 혼합 워크로드 |
| 고정 크기, retention 짧음 | 높음 | 중 | 공간 제약 환경 |
| RETENTION GUARANTEE | 매우 낮음 | 높음 | 장시간 리포팅/Flashback 의존 |

## 5. 진단 절차 — 실측 기반

ORA-01555 가 보고되면 다음 순서로 좁힌다. 첫째, `v$undostat` 에서 `maxquerylen` 을 보고 최장 쿼리가 몇 초인지 확인한다. 이 값이 `tuned_undoretention` 을 넘으면 retention 부족이 직접 원인이다. 둘째, alert log 와 trace 에서 ORA-01555 가 가리키는 세그먼트/SQL 을 식별한다. 셋째, 문제 쿼리가 "fetch across commit" 패턴인지 코드를 점검한다.

```sql
-- 현재 가장 오래 도는 쿼리와 시작 시각
SELECT s.sid, s.serial#, s.username,
       (SYSDATE - s.sql_exec_start)*86400 AS run_sec, q.sql_text
FROM v$session s JOIN v$sql q ON s.sql_id = q.sql_id
WHERE s.status='ACTIVE' AND s.sql_exec_start IS NOT NULL
ORDER BY run_sec DESC FETCH FIRST 10 ROWS ONLY;
```

해결책 우선순위는 (1) 쿼리 자체를 빠르게 — 인덱스/병렬/통계 개선으로 실행 시간을 retention 아래로 낮추는 것이 근본 처방, (2) Undo 테이블스페이스 확장 또는 retention 상향, (3) 장시간 조회 윈도우에 한해 RETENTION GUARANTEE, (4) 코드의 fetch-across-commit 제거 순이다. "Undo 만 키우면 된다" 는 흔한 오판이다 — 쿼리가 retention 보다 길면 Undo 를 아무리 키워도 한계가 있다.

## 6. Read Committed 와 Serializable 에서의 Undo 사용 차이

Oracle 의 기본 격리수준 **Read Committed** 에서는 일관성의 단위가 *statement* 다. 즉 각 SQL 문이 시작될 때의 SCN 기준으로 스냅샷을 본다. 같은 트랜잭션 안에서도 문장이 바뀌면 SCN 이 갱신되므로, 두 번째 SELECT 는 그 사이 커밋된 변경을 본다(non-repeatable read 허용).

**Serializable** 로 올리면 일관성 단위가 *transaction* 으로 바뀐다. 트랜잭션 시작 SCN 으로 끝까지 본다. 그만큼 더 오래된 Undo 가 필요해 ORA-01555 위험과 ORA-08177(can't serialize access) 위험이 함께 커진다.

```sql
ALTER SESSION SET ISOLATION_LEVEL = SERIALIZABLE;
-- 이 세션의 트랜잭션은 시작 시점 스냅샷을 끝까지 유지 → Undo 압박 증가
```

## 7. RAC 와 Active Data Guard 에서의 고려

RAC 에서는 각 인스턴스가 자신의 Undo 테이블스페이스를 갖는다. 한 인스턴스의 CR 블록 구성을 위해 다른 인스턴스의 Undo 가 Cache Fusion 으로 전송될 수 있어, 글로벌하게 Undo 일관성이 유지된다. Active Data Guard 의 read-only standby 에서 장시간 리포팅을 돌리면, primary 의 변경이 적용(redo apply)되면서 standby 에서도 ORA-01555 가 발생할 수 있다 — 이 경우 standby 에서 `UNDO_RETENTION` 과 apply lag 의 균형을 봐야 한다.

## 8. Undo vs Redo — 자주 헷갈리는 두 로그의 역할

Undo 와 Redo 는 이름이 비슷해 혼동되지만 목적이 정반대다. **Redo** 는 "다시 하기 위한" 로그로, 변경을 재현(roll forward)해 인스턴스 장애 후 복구를 보장한다. **Undo** 는 "되돌리기 위한" 데이터로, 롤백과 읽기 일관성(과거 버전 재구성)에 쓰인다. 흥미로운 점은 **Undo 의 생성 자체도 Redo 에 기록**된다는 것이다 — Undo 세그먼트도 데이터베이스 블록이므로 그 변경이 redo 로 보호되어야 복구 시 Undo 까지 재구성된다. 그래서 한 번의 DML 은 (1) 데이터 블록 변경, (2) Undo 블록 생성, (3) 둘 다에 대한 Redo 기록을 동반한다.

| 구분 | Undo | Redo |
|---|---|---|
| 저장 위치 | Undo 테이블스페이스(세그먼트) | Redo 로그 파일(+ 버퍼) |
| 용도 | 롤백, 읽기 일관성, Flashback | 인스턴스/미디어 복구(roll forward) |
| before/after | before-image | change vector(주로 redo 적용용) |
| 재사용 | 커밋 후 expired 되면 순환 재사용 | 아카이브 후 순환(ARCHIVELOG 모드) |

이 구조 이해가 진단에 직접 쓰인다. 대량 DELETE 가 느리고 시스템이 출렁이는 흔한 이유는, DELETE 가 INSERT 보다 Undo 를 훨씬 많이 만들기 때문이다(삭제된 행 전체가 before-image 로 보존됨). 반대로 TRUNCATE 는 DDL 로 Undo 를 거의 만들지 않아 빠르지만 롤백이 불가능하다. "대량 정리는 DELETE 대신 파티션 DROP/TRUNCATE 를 고려" 하라는 조언의 근거가 여기 있다.

## 9. 실무 시나리오 — 야간 배치와 OLTP 공존

같은 DB 에서 야간 리포팅 배치(장시간 조회)와 OLTP(잦은 짧은 트랜잭션)가 공존하면 §3 의 ORA-01555 가 구조적으로 발생하기 쉽다. 실무에서 자주 쓰는 완화책은 다음과 같다. 첫째, **리포팅 윈도우에만** `RETENTION GUARANTEE` 를 켜고 끝나면 끈다 — 이때 Undo 테이블스페이스가 ORA-30036 없이 버틸 만큼 크기를 확보해야 한다. 둘째, 리포팅을 Active Data Guard standby 로 분리해 primary 의 Undo 압박과 떼어 놓는다(단 §7 의 standby ORA-01555 도 함께 본다). 셋째, 배치 쿼리 자체를 빠르게 — 병렬 쿼리, 적절한 인덱스, 최신 통계로 실행 시간을 retention 아래로 끌어내리는 근본 처방이 가장 효과적이다.

```sql
-- 리포팅 윈도우 진입 시
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;
-- ... 야간 배치 수행 ...
-- 윈도우 종료 시 (OLTP 가용성 회복)
ALTER TABLESPACE undotbs1 RETENTION NOGUARANTEE;

-- 배치 전 통계 갱신으로 실행계획/실행시간 안정화
EXEC DBMS_STATS.GATHER_TABLE_STATS(USER, 'BIG_REPORT_TABLE', cascade => TRUE);
```

마지막으로, 모니터링 측면에서 `v$undostat` 을 주기적으로 수집해 `tuned_undoretention` 대비 `maxquerylen` 추세를 대시보드화하면, ORA-01555 가 터지기 전에 "최장 쿼리가 retention 에 근접" 하는 경고를 미리 잡을 수 있다. 사후 대응보다 이 선행지표 관측이 운영 안정성에 훨씬 유효하다.

## 참고

- Oracle Database Concepts — "Data Concurrency and Consistency" 장
- Oracle Database Administrator's Guide — "Managing Undo"
- Tom Kyte, "Expert Oracle Database Architecture" — Undo/Redo 장
- My Oracle Support: ORA-01555 진단 노트(Doc ID 기반 트러블슈팅)
