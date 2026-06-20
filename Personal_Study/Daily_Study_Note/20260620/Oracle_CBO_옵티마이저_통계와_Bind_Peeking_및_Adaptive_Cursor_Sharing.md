Notion 원본: https://app.notion.com/p/3855a06fd6d381c7aee1cfc09ed27012

# Oracle CBO 옵티마이저 통계와 Bind Peeking 및 Adaptive Cursor Sharing

> 2026-06-20 신규 주제 · 확장 대상: Oracle

## 학습 목표

- CBO 가 카디널리티를 추정하는 통계 항목(NDV·히스토그램·클러스터링 팩터)을 해석한다
- Bind Peeking 이 첫 실행 시 만든 실행계획이 후속 호출을 망치는 시나리오를 재현한다
- Adaptive Cursor Sharing 의 bind-sensitive·bind-aware 전이 조건을 구분한다
- 통계 수집 옵션(METHOD_OPT·ESTIMATE_PERCENT·AUTO_SAMPLE_SIZE)을 상황에 맞게 선택한다

## 1. CBO 는 통계로 비용을 추정한다

Oracle 의 Cost-Based Optimizer 는 SQL 을 받으면 가능한 실행계획 후보들을 만들고, 각 단계의 **카디널리티(처리 행 수)** 와 비용을 추정해 가장 싼 계획을 고른다. 이 추정의 모든 근거가 옵티마이저 통계다. 통계가 실제 데이터 분포와 어긋나면 카디널리티 추정이 빗나가고, 인덱스를 써야 할 곳에서 풀스캔을, 풀스캔이 나은 곳에서 인덱스를 타는 잘못된 계획이 굳어진다.

핵심 통계는 테이블 수준(행 수 NUM_ROWS, 블록 수 BLOCKS, 평균 행 길이), 컬럼 수준(NDV=Number of Distinct Values, NULL 수, 최소·최대값, 히스토그램), 인덱스 수준(LEAF_BLOCKS, 높이 BLEVEL, **클러스터링 팩터**)로 나뉜다. 이 중 NDV 와 클러스터링 팩터가 카디널리티·인덱스 효율 추정의 중심이다.

## 2. 카디널리티 추정 공식

균등 분포를 가정할 때 등치 조건의 선택도(selectivity)는 `1/NDV` 다. 카디널리티는 `NUM_ROWS * selectivity` 로 계산된다.

```sql
-- 통계 확인
SELECT column_name, num_distinct, num_nulls, histogram, num_buckets
  FROM user_tab_col_statistics
 WHERE table_name = 'ORDERS';

-- NDV 가 1000, NUM_ROWS 가 1,000,000 이면
-- WHERE status = 'X' 의 추정 카디널리티 = 1,000,000 / 1000 = 1000 행
```

문제는 분포가 균등하지 않을 때다. `status` 컬럼이 'DONE' 99% 와 'PENDING' 1% 로 **편향(skew)** 되어 있으면 `1/NDV` 가정은 둘 다 50%에 가깝게 잘못 추정한다. 이를 바로잡는 장치가 히스토그램이다. Frequency 히스토그램은 각 값의 실제 빈도를, Hybrid/Top-Frequency 히스토그램은 대표값들의 빈도를 저장해 편향된 컬럼의 선택도를 실측에 맞춘다. 단, 히스토그램은 바인드 변수와 결합하면 다음 절의 Bind Peeking 부작용을 일으킨다.

## 3. 클러스터링 팩터와 인덱스 선택

클러스터링 팩터는 "인덱스 순서대로 행을 읽을 때 테이블 블록이 몇 번 바뀌는가" 를 센 값이다. 인덱스 키 순서와 테이블의 물리적 행 배치가 비슷할수록 작아지고(블록 재방문 적음), 무작위일수록 NUM_ROWS 에 가깝게 커진다. CBO 는 인덱스 레인지 스캔의 테이블 액세스 비용을 클러스터링 팩터로 추정하므로, 같은 NDV·같은 행 수라도 클러스터링 팩터가 크면 인덱스를 포기하고 풀스캔을 택한다.

```sql
SELECT index_name, clustering_factor, leaf_blocks, blevel
  FROM user_indexes
 WHERE table_name = 'ORDERS';
-- clustering_factor 가 NUM_ROWS 에 근접 → 인덱스 레인지 스캔 비효율 신호
```

이 값은 ANALYZE 가 아니라 데이터의 물리적 배치에서 비롯되므로, 통계 재수집만으로는 개선되지 않는다. 개선하려면 테이블을 인덱스 키 순으로 재구성(`ALTER TABLE ... MOVE` 후 재정렬, 혹은 IOT 사용)해야 한다.

## 4. Bind Peeking 의 등장과 함정

바인드 변수(`:status`)는 SQL 텍스트를 고정해 하드 파싱을 줄여 라이브러리 캐시 효율을 높인다. 하지만 파싱 시점에 옵티마이저는 `:status` 의 실제 값을 모르므로 카디널리티를 추정할 수 없다. Oracle 9i 부터 도입된 **Bind Peeking** 은 첫 하드 파싱 때 바인드 값을 한 번 "엿보고" 그 값 기준으로 계획을 만든 뒤, 이후 같은 SQL 의 모든 실행에 그 계획을 재사용한다.

```sql
-- 첫 실행에서 희귀값이 들어오면
EXEC :status := 'PENDING';   -- 전체의 1%, CBO 는 인덱스 레인지 스캔 선택
SELECT * FROM orders WHERE status = :status;

-- 같은 커서를 재사용하는 후속 실행에서 흔한값이 들어와도
EXEC :status := 'DONE';      -- 전체의 99%인데도 인덱스 레인지 스캔 계획을 그대로 사용!
SELECT * FROM orders WHERE status = :status; -- 수십만 행을 인덱스로 → 재앙적 느림
```

즉 첫 실행 값에 따라 계획이 "복불복" 으로 굳는다. 히스토그램이 있는 편향 컬럼일수록 이 변동성이 크다. 운영 중 "어제까지 빠르던 쿼리가 오늘 갑자기 느려졌다" 의 흔한 원인이 바로 커서 무효화 후 다른 첫 값으로 재파싱된 Bind Peeking 이다.

## 5. Adaptive Cursor Sharing 의 해법

Oracle 11g 는 이 변동성을 완화하려 **Adaptive Cursor Sharing(ACS)** 을 도입했다. 동작은 두 단계다. 먼저 바인드 값에 따라 카디널리티가 크게 달라질 수 있는 커서를 **bind-sensitive** 로 표시한다. 이후 실제 실행에서 처리 행 수가 예측과 크게 벌어지면 그 커서를 **bind-aware** 로 승격하고, 바인드 값의 선택도 구간별로 **서로 다른 자식 커서(child cursor)** 를 만들어 적절한 계획을 각각 사용한다.

```sql
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware, is_shareable
  FROM v$sql
 WHERE sql_id = '&target_sql_id';
-- is_bind_sensitive = Y : ACS 감시 대상
-- is_bind_aware     = Y : 선택도 구간별 자식 커서 분기 활성화

-- 선택도 구간(버킷) 확인
SELECT * FROM v$sql_cs_selectivity WHERE sql_id = '&target_sql_id';
-- 구간별 실행 통계
SELECT * FROM v$sql_cs_statistics  WHERE sql_id = '&target_sql_id';
```

ACS 의 한계도 명확하다. bind-aware 승격은 **여러 번 잘못된 실행을 겪은 뒤** 일어나므로 첫 몇 번의 나쁜 실행은 막지 못한다(학습형 보정). 또한 자식 커서가 늘면 라이브러리 캐시 메모리와 파싱 부하가 커진다. 변동이 극단적인 핵심 쿼리는 ACS 에 맡기기보다 SQL Plan Baseline 으로 계획을 고정하거나, 값별로 쿼리를 분기하는 편이 예측 가능하다.

## 6. 통계 수집 옵션 선택

```sql
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(
    ownname          => 'APP',
    tabname          => 'ORDERS',
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE, -- 11g+ 권장: 근사 NDV 알고리즘
    method_opt       => 'FOR ALL COLUMNS SIZE AUTO', -- 편향 컬럼만 히스토그램 자동
    cascade          => TRUE,                        -- 인덱스 통계 포함
    degree           => 4);                          -- 병렬 수집
END;
/
```

`AUTO_SAMPLE_SIZE` 는 11g 의 hash-based NDV 근사 알고리즘으로 100% 스캔에 근접한 정확도를 표본 비용으로 얻는다. 과거처럼 `ESTIMATE_PERCENT => 100` 을 강제하면 대형 테이블에서 수집 시간만 수배로 늘고 정확도 이득은 미미하다. `METHOD_OPT` 의 `SIZE AUTO` 는 컬럼 사용 패턴(SYS.COL_USAGE$)을 보고 편향이 있고 술어에 쓰이는 컬럼에만 히스토그램을 만든다. Bind Peeking 변동을 줄이려면 오히려 불필요한 컬럼의 히스토그램을 `SIZE 1` 로 끄는 선택이 유효할 때가 있다.

## 7. 계획 안정화 도구

ACS 가 충분하지 않은 운영 쿼리는 다음으로 계획을 고정한다.

```sql
-- SQL Plan Management: 좋은 계획을 베이스라인으로 등록
DECLARE
  plans PLS_INTEGER;
BEGIN
  plans := DBMS_SPM.LOAD_PLANS_FROM_CURSOR_CACHE(sql_id => '&good_sql_id');
END;
/
-- 이후 옵티마이저는 베이스라인에 검증된 계획만 사용, 신규 계획은 검증 후 채택
```

베이스라인은 "더 나은 계획이 나타나도 검증 전에는 쓰지 않는다" 는 보수적 정책이라 회귀를 막는다. 반면 데이터가 성장해 더 나은 계획이 필요해진 경우엔 베이스라인 진화(evolve)를 수동으로 돌려야 하므로 운영 부담이 있다. 단기 핫픽스로는 `SQL Profile` 이나 힌트가, 장기 안정화로는 베이스라인이 적합하다.

## 8. 진단 워크플로 정리

느려진 쿼리를 만나면 순서는 이렇다. 먼저 `DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST')` 로 **E-Rows(추정) 대 A-Rows(실제)** 를 비교한다. 둘이 10배 이상 벌어지는 단계가 통계 또는 Bind Peeking 문제의 진앙이다. 다음으로 `v$sql` 의 `is_bind_sensitive`/`is_bind_aware` 와 자식 커서 수를 확인해 ACS 가 개입했는지 본다. 통계가 오래됐으면(`user_tables.last_analyzed`) 재수집하고, 편향 컬럼이면 히스토그램 유무를 점검한다. 마지막으로 변동이 본질적이면 베이스라인으로 고정한다. 이 순서를 지키면 "통계를 무작정 다시 모으는" 식의 추측성 대응을 피할 수 있다.

## 9. 실행계획 읽기: E-Rows 대 A-Rows

진단의 출발점은 추정과 실제의 괴리를 눈으로 보는 것이다. `GATHER_PLAN_STATISTICS` 힌트나 `STATISTICS_LEVEL=ALL` 로 실행 후 실제 행 수를 수집한다.

```sql
SELECT /*+ GATHER_PLAN_STATISTICS */ o.id, c.name
  FROM orders o JOIN customers c ON c.id = o.customer_id
 WHERE o.status = :s;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST'));
```

출력에서 `E-Rows`(옵티마이저 추정)와 `A-Rows`(실제 처리)를 단계별로 비교한다. 둘이 한 자릿수 배 이상 벌어지는 첫 단계가 오추정의 진앙이다. 흔한 패턴은 조인 순서 오류(작은 결과가 나올 테이블을 나중에 조인), 잘못된 조인 방식(NESTED LOOPS 가 적절한데 HASH JOIN), 누락된 히스토그램으로 인한 편향 컬럼 오추정이다. `A-Time` 과 `Buffers`(논리 읽기) 컬럼으로 어느 단계가 실제로 비용을 먹는지도 함께 본다. 카디널리티가 맞는데도 느리면 통계 문제가 아니라 물리적 IO(클러스터링 팩터)나 인덱스 부재가 원인이다.

## 10. 통계 수집 운영 전략

야간 자동 통계 수집(AUTO STATS JOB)은 "stale" 임계(기본 10% 행 변경)를 넘은 객체만 다시 모은다. 이 자동화가 대개 충분하지만, 두 가지 예외가 운영 사고를 만든다. 첫째, **대량 적재 직후**. 배치로 수백만 건을 넣고 통계 갱신 전에 쿼리가 돌면, 옵티마이저는 "빈 테이블" 통계로 계획을 세워 풀스캔 대신 인덱스를 타거나 그 반대로 오판한다. 적재 파이프라인 끝에 명시적 `GATHER_TABLE_STATS` 를 넣어야 한다. 둘째, **편향이 큰 신규 컬럼**. 자동 잡은 컬럼 사용 통계가 쌓인 뒤에야 히스토그램을 만들므로, 도입 초기에는 히스토그램이 없어 오추정한다.

```sql
-- 통계를 고정해 변동을 막아야 하는 안정 테이블
BEGIN
  DBMS_STATS.LOCK_TABLE_STATS('APP', 'CODE_MASTER'); -- 코드성 소형 테이블 고정
END;
/
-- 펜딩 통계로 운영 반영 전 검증
BEGIN
  DBMS_STATS.SET_TABLE_PREFS('APP', 'ORDERS', 'PUBLISH', 'FALSE'); -- 펜딩으로 수집
END;
/
```

펜딩 통계(pending statistics)는 수집한 통계를 바로 공개하지 않고, `OPTIMIZER_USE_PENDING_STATISTICS=TRUE` 세션에서만 미리 검증한 뒤 게시할 수 있게 한다. 통계 갱신이 곧 계획 회귀로 이어지는 핵심 테이블에서 안전망이 된다. 반대로 거의 변하지 않는 코드성 테이블은 통계를 잠가(lock) 불필요한 재수집과 변동을 막는다.

## 11. 동적 샘플링과 확장 통계

조인·필터가 복잡해 정적 통계로는 상관관계를 못 잡을 때가 있다. 두 컬럼이 함수적으로 종속(예: 도시-우편번호)이면 옵티마이저는 둘을 독립으로 보고 선택도를 곱해 과소추정한다. **확장 통계(extended statistics)** 로 컬럼 그룹의 결합 분포를 수집해 보정한다.

```sql
-- 컬럼 그룹 통계: city 와 zip 의 상관관계를 옵티마이저에 알림
SELECT DBMS_STATS.CREATE_EXTENDED_STATS('APP', 'CUSTOMERS', '(city, zip)') FROM dual;
EXEC DBMS_STATS.GATHER_TABLE_STATS('APP', 'CUSTOMERS', method_opt => 'FOR ALL COLUMNS SIZE AUTO');
```

또한 통계가 없거나 부족하면 옵티마이저가 파싱 시점에 표본을 직접 떠 추정하는 **동적 샘플링(`OPTIMIZER_DYNAMIC_SAMPLING`)** 이 작동한다. 레벨이 높을수록 더 많은 블록을 표본하지만 파싱 시간이 늘어난다. 12c 의 **동적 통계(adaptive statistics)** 와 **SQL Plan Directives** 는 한 번 오추정한 술어를 기억해 다음 실행부터 동적 샘플링을 자동 적용하는데, 부하가 큰 환경에서는 이 자동 기능이 파싱 폭증을 일으킬 수 있어 의도적으로 끄는 운영도 있다. 정답은 환경 의존이며, AWR 의 파싱 시간·하드파싱 비율을 보고 결정한다.

## 참고

- Oracle Database SQL Tuning Guide — Optimizer Statistics Concepts / Adaptive Cursor Sharing
- Oracle Database SQL Tuning Guide — Influencing the Optimizer, SQL Plan Management
- DBMS_STATS / DBMS_XPLAN / DBMS_SPM PL/SQL Packages Reference
- Jonathan Lewis, "Cost-Based Oracle Fundamentals"
