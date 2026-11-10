Notion 원본: https://www.notion.so/4145a06fd6d3818bac67c1f0738ac710

# Oracle Bind Peeking과 Adaptive Cursor Sharing: 히스토그램과 실행계획 불안정 진단

> 2026-11-10 신규 주제 · 확장 대상: Oracle (옵티마이저 / 실행계획)

## 학습 목표

- Bind Peeking 이 첫 하드 파싱의 바인드 값으로 실행계획을 고정하는 동작을 재현한다
- 편향 컬럼의 히스토그램이 계획을 불안정하게 만드는 현상을 확인 쿼리로 검증한다
- Adaptive Cursor Sharing(ACS)의 bind-sensitive → bind-aware 전환을 `V$SQL` 로 추적한다
- `DBMS_XPLAN.DISPLAY_CURSOR` 의 실측(A-Rows) 대비 추정(E-Rows)으로 오추정 지점을 찾는다

## 1. Bind Peeking

바인드 변수를 쓰는 SQL 이 처음 하드 파싱될 때 옵티마이저는 **그 시점의 바인드 값을 들여다보고(peek)** 카디널리티를 추정해 계획을 만든다. 이후 같은 SQL(커서가 공유되는 한) 은 값이 달라도 같은 계획을 재사용한다. 값 분포가 균등하면 문제가 없지만, 한 값이 전체의 90% 를 차지하는 컬럼이라면 처음 들어온 값이 희소 값인지 빈번 값인지에 따라 인덱스 스캔/풀 스캔이 갈리고, 이후 다른 값에서 성능이 급락한다. "어제까지 빨랐는데 재기동 후 느려졌다" 같은 증상이 전형적이다. 공유 풀에서 커서가 밀려나거나 통계 갱신으로 무효화된 뒤 다시 파싱될 때 계획이 바뀌기 때문이다.

```sql
CREATE TABLE ord (id NUMBER PRIMARY KEY, status VARCHAR2(10), payload VARCHAR2(100));
INSERT INTO ord SELECT level, CASE WHEN level <= 99000 THEN 'DONE' ELSE 'FAIL' END, 'x'
  FROM dual CONNECT BY level <= 100000;
CREATE INDEX ix_ord_status ON ord(status);
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORD',
    method_opt => 'FOR COLUMNS status SIZE 254');   -- 히스토그램 생성
END;
/
```

`status` 는 DONE 99%, FAIL 1% 로 편향되어 있다. 히스토그램이 있으므로 옵티마이저는 `status='FAIL'` 에는 인덱스 접근(INDEX RANGE SCAN), `status='DONE'` 에는 풀 스캔을 선호한다.

## 2. 계획 확인: 추정 vs 실측

```sql
VAR s VARCHAR2(10)
EXEC :s := 'FAIL'
SELECT /*+ gather_plan_statistics */ count(payload) FROM ord WHERE status = :s;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST +PEEKED_BINDS'));
```

출력의 `E-Rows`(추정)와 `A-Rows`(실제), `Buffers` 를 비교한다. `+PEEKED_BINDS` 를 붙이면 계획 하단 Peeked Binds 섹션에 하드 파싱 때 사용된 값이 표시된다. 이 값이 'FAIL' 인데 이후 `:s := 'DONE'` 으로 실행하면, ACS 가 개입하기 전에는 INDEX RANGE SCAN 으로 99,000 행을 인덱스를 통해 읽는 비효율이 생기고 `A-Rows` 가 `E-Rows` 보다 수십~수백 배 커져 있는 것을 볼 수 있다. 오추정 지점은 `A-Rows` 와 `E-Rows` 의 비율이 처음으로 크게 벌어지는 연산자이며, 실행 계획 해석의 출발점이다.

## 3. Adaptive Cursor Sharing (11g+)

ACS 는 "바인드 값에 따라 최적 계획이 다른 커서"를 감지해 **자식 커서를 여러 개** 유지한다. 단계는 다음과 같다. 히스토그램이 있고 바인드를 조건에 쓰는 문장은 먼저 `IS_BIND_SENSITIVE = 'Y'` 로 표시된다. 이후 실행에서 값에 따른 행 수 차이가 크게 관측되면 `IS_BIND_AWARE = 'Y'` 로 전환되어 새 자식 커서가 만들어지고, 이후 바인드 값의 선택도 범위에 맞는 커서가 선택된다. 모든 문장이 대상은 아니며, 바인드 값이 한 번 확인된 뒤 **다음 실행부터** 반영되므로 첫 몇 번의 실행은 여전히 나쁜 계획일 수 있다.

```sql
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware, is_shareable,
       executions, buffer_gets
  FROM v$sql
 WHERE sql_text LIKE 'SELECT /*+ gather_plan_statistics */ count(payload)%';

SELECT * FROM v$sql_cs_selectivity WHERE sql_id = '&sql_id';   -- 선택도 범위
SELECT * FROM v$sql_cs_histogram   WHERE sql_id = '&sql_id';   -- 버킷별 실행 분포
```

`V$SQL_CS_HISTOGRAM` 은 실행 시 사용된 행 수 버킷 분포를, `V$SQL_CS_SELECTIVITY` 는 bind-aware 커서가 담당하는 선택도 범위를 보여 준다. `IS_SHAREABLE = 'N'` 인 자식 커서는 더 이상 재사용되지 않는다. ACS 를 켜고 끄는 공식 파라미터는 없고(`_optimizer_adaptive_cursor_sharing` 은 히든), 문장 수준에서 `NO_BIND_AWARE`/`BIND_AWARE` 힌트로 제어한다.

## 4. 대응 전략

우선 **히스토그램이 정말 필요한가**를 따진다. 편향이 없는 컬럼에 히스토그램을 두면 오히려 불안정해진다. `method_opt => 'FOR ALL COLUMNS SIZE 1'` 로 막거나, 자동(`SIZE AUTO`)이 만든 히스토그램 중 불필요한 것은 컬럼 단위로 제거한다. 값 분포에 따라 계획이 달라져야 하는 쿼리는 ACS 에 맡기거나, 아예 분기해서 서로 다른 SQL 텍스트로 나눈다(예: 희소 상태만 조회하는 목록 쿼리와 전체 집계 쿼리를 분리). 계획을 고정해야 한다면 SQL Plan Management(SPM) 의 baseline 으로 검증된 계획만 사용하게 한다.

```sql
DECLARE n PLS_INTEGER;
BEGIN
  n := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(sql_id => '&sql_id', plan_hash_value => &phv);
END;
/
```

힌트(`/*+ INDEX(...) */`)로 고정하는 방법은 단순하지만 데이터 분포가 변하면 오히려 해가 되므로 임시 처방으로만 쓴다. 통계 갱신 시점도 관리 대상이다. 운영 중 통계 수집은 커서를 무효화(`no_invalidate` 기본값 `DBMS_STATS.AUTO_INVALIDATE`, 수 시간 내 점진 무효화)하므로 "배치 직후 계획이 바뀐다"는 현상의 원인이 된다.

## 5. trade-off

Bind Peeking 은 리터럴 SQL 의 하드 파싱 폭주 없이 값 인지형 계획을 얻게 해 주지만 계획 일관성을 포기한다. ACS 는 이를 완화하지만 자식 커서 증가(`V$SQL` 의 version count 증가, 라이브러리 캐시 메모리·래치 경합)를 대가로 한다. 극단적으로 값 종류가 많은 컬럼에서는 커서가 늘어날 수 있으므로 모니터링한다. 애플리케이션 계층(JPA/MyBatis)에서는 바인드를 쓰는 것이 기본이고, 편향 컬럼 조건만 리터럴로 쓰는 절충(상수가 몇 개뿐일 때)도 선택지다.

## 참고

- Oracle Database SQL Tuning Guide: Adaptive Cursor Sharing
- Oracle Database SQL Tuning Guide: Reading Execution Plans (DBMS_XPLAN)
- Oracle Database PL/SQL Packages Reference: DBMS_STATS, DBMS_SPM
