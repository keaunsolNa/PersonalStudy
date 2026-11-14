Notion 원본: https://www.notion.so/4185a06fd6d381f8baf8fc6659d3f3a7

# Oracle 인덱스 스캔 방식과 Clustering Factor: 인덱스 범위 스캔 비용 모델과 테이블 재정렬

> 2026-11-13 신규 주제 · 확장 대상: Oracle(인덱스 기본과 실행계획 읽기), SQLD(옵티마이저와 인덱스)

## 학습 목표

- Unique, Range, Full, Fast Full, Skip 다섯 가지 인덱스 스캔의 I/O 방식과 정렬 보장 여부를 구분한다
- Clustering Factor 의 계산 방식과 인덱스 범위 스캔 비용 공식에서 차지하는 비중을 계산한다
- 같은 선택도에서 옵티마이저가 인덱스 대신 풀 스캔을 고르는 이유를 통계 값으로 설명한다
- 테이블 재정렬, Attribute Clustering, 커버링 인덱스 중 상황에 맞는 개선 수단을 고른다

## 1. 인덱스가 있는데 풀 스캔을 하는 상황

조건절 컬럼에 인덱스가 있고 조건에 맞는 행이 전체의 1~2% 뿐인데도 실행계획에 TABLE ACCESS FULL 이 나타나는 일이 있다. 통계가 오래됐거나 옵티마이저가 잘못 판단했다고 생각하고 힌트로 인덱스를 강제하면 응답 시간이 오히려 길어지기도 한다. 반대로 같은 구조의 다른 테이블에서는 10% 를 읽는 쿼리가 인덱스를 타고도 빠르다. 이 차이를 만드는 변수가 Clustering Factor 다. 인덱스의 효율은 인덱스 자체의 구조보다 "인덱스 순서와 테이블에 행이 저장된 물리적 순서가 얼마나 비슷한가"에 좌우되고, 옵티마이저는 그 정도를 숫자 하나로 요약해 비용 계산에 사용한다. 이 값의 의미를 이해하려면 먼저 인덱스를 읽는 방식들이 각각 어떤 I/O 를 만드는지부터 구분해야 한다.

## 2. 다섯 가지 인덱스 스캔 방식

INDEX UNIQUE SCAN 은 유니크 인덱스에 등치 조건이 모든 컬럼에 주어졌을 때 사용된다. 루트에서 리프까지 한 경로만 내려가 최대 한 건을 찾으므로 읽는 블록 수는 인덱스 높이와 같다. INDEX RANGE SCAN 은 가장 일반적인 방식으로, 조건의 시작 지점까지 수직으로 내려간 뒤 리프 블록을 연결 순서대로 수평 탐색한다. 결과가 인덱스 키 순서로 나오므로 ORDER BY 의 정렬 연산을 생략시킬 수 있고, 한 번에 한 블록씩 읽는 단일 블록 I/O 를 사용한다.

INDEX FULL SCAN 은 리프 블록 전체를 처음부터 끝까지 연결 순서대로 읽는다. 선두 컬럼 조건이 없더라도 정렬된 결과가 필요할 때 선택되며 마찬가지로 단일 블록 I/O 다. INDEX FAST FULL SCAN 은 인덱스 세그먼트를 테이블 풀 스캔과 같은 방식, 즉 디스크에 놓인 순서대로 다중 블록 I/O 로 읽는다. 리프 연결 구조를 따르지 않으므로 결과의 순서가 보장되지 않고 정렬 생략 효과가 없지만, 쿼리가 필요로 하는 컬럼이 모두 인덱스에 있을 때 테이블보다 훨씬 작은 세그먼트를 빠르게 훑는 용도로 쓰이며 병렬 처리도 가능하다. `COUNT(*)` 가 NOT NULL 컬럼의 인덱스로 처리될 때 흔히 나타난다.

INDEX SKIP SCAN 은 결합 인덱스의 선두 컬럼에 조건이 없을 때, 선두 컬럼의 고유 값마다 논리적인 하위 인덱스가 있는 것처럼 건너뛰며 탐색한다. 선두 컬럼의 고유 값 수가 적을 때만 유리하고, 고유 값이 많으면 탐색 횟수가 그만큼 늘어 풀 스캔보다 나빠진다. 다섯 가지 중 Range, Full, Skip 스캔은 인덱스에 없는 컬럼이 필요하면 ROWID 로 테이블 블록을 찾아가는 단계가 뒤따르고, 이 단계의 비용이 다음 절의 주제다.

## 3. Clustering Factor 의 정의와 계산 방식

Clustering Factor 는 인덱스를 키 순서대로 처음부터 끝까지 읽으면서 각 엔트리의 ROWID 가 가리키는 테이블 블록이 직전 엔트리와 달라질 때마다 1 씩 올린 누적값이다. 통계 수집 시 계산되어 `DBA_INDEXES.CLUSTERING_FACTOR` 에 저장된다. 테이블의 행이 인덱스 키 순서와 같은 순서로 저장되어 있으면 한 블록의 행을 모두 지나갈 때까지 블록이 바뀌지 않으므로 값이 테이블 블록 수에 가깝다. 행이 키와 무관하게 흩어져 있으면 엔트리마다 블록이 바뀌어 값이 행 수에 가까워진다. 따라서 이 값은 테이블 블록 수를 하한, 행 수를 상한으로 가지며, 어느 쪽에 가까운지로 좋고 나쁨을 판단한다.

```sql
SELECT i.index_name,
       i.blevel,
       i.leaf_blocks,
       i.clustering_factor,
       t.blocks   AS table_blocks,
       t.num_rows AS table_rows
  FROM user_indexes i
  JOIN user_tables  t ON t.table_name = i.table_name
 WHERE i.table_name = 'ORDERS';
```

이 조회 결과에서 `CLUSTERING_FACTOR` 를 `TABLE_BLOCKS` 와 `TABLE_ROWS` 사이의 어디쯤인지 비교하는 것이 진단의 첫 단계다. 주문 테이블의 주문일시 인덱스처럼 입력 순서와 키 순서가 일치하는 컬럼은 대개 블록 수에 가까운 값이 나오고, 고객 번호나 상태 코드처럼 입력 순서와 무관한 컬럼은 행 수에 가까운 값이 나온다. 한 테이블의 물리적 순서는 하나뿐이므로 모든 인덱스의 Clustering Factor 를 동시에 좋게 만들 수는 없다는 점이 이 지표의 본질적인 제약이다.

## 4. 인덱스 범위 스캔 비용 공식

옵티마이저가 인덱스 범위 스캔 뒤 테이블에 접근하는 경로에 매기는 I/O 비용은 세 항의 합으로 근사된다. 인덱스 높이를 나타내는 `BLEVEL`, 읽어야 할 리프 블록 수인 `LEAF_BLOCKS × 선택도`, 그리고 방문할 테이블 블록 수의 추정치인 `CLUSTERING_FACTOR × 선택도` 다. 뒤의 두 항은 올림 처리된다. 세 번째 항이 핵심으로, 옵티마이저는 "조건에 맞는 행을 가져오기 위해 테이블 블록을 몇 번 읽게 되는가"를 Clustering Factor 에 선택도를 곱해 추정한다.

구체적인 수치로 계산해 본다. 행 수 1,000,000, 테이블 블록 10,000, 인덱스 `BLEVEL` 2, `LEAF_BLOCKS` 2,500 인 테이블에서 선택도 1% 인 조건을 가정한다. Clustering Factor 가 하한에 가까운 10,000 이면 비용은 2 + 25 + 100 = 127 이다. 상한에 가까운 1,000,000 이면 2 + 25 + 10,000 = 10,027 이 된다. 같은 인덱스 구조, 같은 선택도에서 비용이 약 79배 차이 나며 그 차이는 전부 세 번째 항에서 나온다. 1만 건을 가져오는 데 전자는 테이블 블록 100개를 읽으면 되지만 후자는 건마다 다른 블록을 읽어 1만 번의 단일 블록 읽기가 필요하다는 뜻이다.

풀 스캔 비용과 비교하면 판단 근거가 보인다. 시스템 통계가 기본값(noworkload)이고 블록 크기 8KB, 다중 블록 읽기 수 8 인 환경에서 다중 블록 읽기 한 번의 시간은 단일 블록 읽기의 약 2.17배로 계산된다(26ms 대 12ms). 풀 스캔의 I/O 비용은 대략 (10,000 ÷ 8) × 2.17 ≈ 2,710 이다. Clustering Factor 가 좋은 경우의 127 은 풀 스캔보다 훨씬 싸고, 나쁜 경우의 10,027 은 풀 스캔보다 약 3.7배 비싸다. 후자의 상황에서 옵티마이저가 1% 조건에도 풀 스캔을 고르는 것은 오판이 아니라 공식에 따른 결과다. 이 환경에서 손익분기 선택도를 구하면 나쁜 경우에는 약 0.27%, 좋은 경우에는 약 21.7% 가 된다. "인덱스는 몇 % 이하일 때 유리하다"는 고정된 기준이 존재하지 않는 이유가 여기에 있다. 위 수치는 공식에 통계 값을 대입한 계산이며, 실제 비용은 CPU 비용 항과 시스템 통계에 따라 달라지므로 대상 시스템에서는 실행계획의 Cost 열로 확인해야 한다.

## 5. 실행계획과 실행 통계로 확인하기

추정이 실제와 맞는지는 실행 통계로 확인한다. `GATHER_PLAN_STATISTICS` 힌트로 실행한 뒤 커서의 실행계획을 조회하면 단계별 실제 행 수와 버퍼 읽기 수가 나온다.

```sql
SELECT /*+ GATHER_PLAN_STATISTICS INDEX(o ix_orders_cust) */
       SUM(o.amount)
  FROM orders o
 WHERE o.customer_id BETWEEN 1000 AND 1999;

SELECT *
  FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'));
```

출력에서 볼 것은 INDEX RANGE SCAN 단계와 TABLE ACCESS BY INDEX ROWID 단계의 Buffers 차이다. 테이블 접근 단계의 Buffers 에서 인덱스 단계의 Buffers 를 뺀 값이 테이블 블록 방문 횟수이고, 이것을 A-Rows 로 나누면 행당 블록 방문 수가 된다. 이 비율이 1 에 가까우면 행마다 다른 블록을 읽고 있다는 뜻으로 Clustering Factor 가 나쁜 인덱스의 전형이며, 0.01 수준이면 블록 하나에서 여러 행을 함께 얻고 있다는 뜻이다. Oracle 12c 부터는 이 단계가 TABLE ACCESS BY INDEX ROWID BATCHED 로 표시되는 경우가 많다. 인덱스에서 ROWID 를 여러 개 모은 뒤 블록 순서로 정리해 테이블을 읽음으로써 물리적 읽기를 줄이는 방식인데, 버퍼 캐시에 없는 블록을 읽을 때의 효율을 높여 줄 뿐 방문해야 할 서로 다른 블록의 수 자체를 줄이지는 못한다.

## 6. TABLE_CACHED_BLOCKS: 통계가 실제보다 비관적일 때

Clustering Factor 의 기본 계산은 "직전 엔트리와 블록이 다르면 증가"라는 엄격한 규칙을 쓴다. ASSM 세그먼트에 여러 세션이 동시에 입력하면 연속된 키 값의 행이 인접한 몇 개 블록에 번갈아 들어가는데, 이 경우 실제로는 소수의 블록만 반복해서 읽으므로 버퍼 캐시 적중률이 높음에도 계산상으로는 엔트리마다 블록이 바뀌어 값이 행 수 가까이 치솟는다. 통계가 현실보다 비관적으로 잡혀 인덱스가 부당하게 외면받는 상황이다.

이를 보정하는 것이 통계 수집 환경설정 `TABLE_CACHED_BLOCKS` 다. 기본값은 1 이고 1~255 범위에서 지정하며, 최근에 방문한 N 개 블록 안에 있는 블록으로 돌아가는 경우에는 값을 증가시키지 않는다.

```sql
BEGIN
  DBMS_STATS.SET_TABLE_PREFS(USER, 'ORDERS', 'TABLE_CACHED_BLOCKS', '16');
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS', cascade => TRUE);
END;
/
```

값을 바꾼 뒤에는 통계를 다시 수집해야 반영된다. 이 설정은 데이터의 물리적 배치를 바꾸지 않고 측정 방식만 바꾸는 것이므로, 동시 입력으로 인한 미세한 뒤섞임에는 타당하지만 행이 테이블 전체에 실제로 흩어진 경우에 값을 크게 올려 수치만 좋게 만들면 옵티마이저가 비싼 인덱스 경로를 싸다고 오판하게 된다. 적용 전후로 5절의 방법으로 행당 블록 방문 수를 측정해 통계가 실제와 가까워졌는지 확인하는 절차가 필요하다.

## 7. 물리적 순서를 바꾸는 방법과 대가

실제 배치를 개선하려면 테이블을 원하는 키 순서로 다시 써야 한다. 전통적인 방법은 ORDER BY 를 포함한 CTAS 로 새 테이블을 만들어 교체하는 것이다. 인덱스 재생성(REBUILD)은 인덱스 세그먼트만 다시 만들 뿐 테이블의 행 순서를 바꾸지 않으므로 Clustering Factor 를 개선하지 못한다는 점은 자주 혼동되는 부분이다. Oracle 12.1.0.2 부터는 Attribute Clustering 으로 정렬 기준을 테이블 속성으로 선언할 수 있다.

```sql
ALTER TABLE orders ADD CLUSTERING BY LINEAR ORDER (customer_id);
ALTER TABLE orders MOVE ONLINE;
```

`MOVE ONLINE` 은 12.2 부터 지원되며 그 이전 버전에서는 MOVE 후 인덱스를 재생성해야 한다. 선언만으로는 기존 데이터가 움직이지 않고, 이후의 직접 경로 입력(`INSERT /*+ APPEND */`, CTAS)과 `ALTER TABLE MOVE`, 온라인 재정의 같은 데이터 이동 작업에서 정렬이 적용된다. 일반적인 단건 INSERT 에는 적용되지 않으므로 OLTP 테이블에서는 시간이 지나면 다시 흩어지고, 주기적인 재구성이 운영 부담으로 남는다. 또한 한 컬럼 기준으로 재정렬하면 그 컬럼의 인덱스는 좋아지지만 기존에 입력 순서 덕분에 좋았던 일시 컬럼 인덱스는 나빠진다. 어떤 접근 경로가 가장 많은 I/O 를 차지하는지 AWR 의 SQL 통계로 확인한 뒤 기준 컬럼을 정해야 한다. 기본키 순서의 접근이 지배적이라면 테이블 자체를 인덱스 구조로 저장하는 IOT 가 대안이 되지만 보조 인덱스가 논리적 ROWID 를 사용해 느려지는 대가가 있다.

## 8. 테이블 방문 자체를 없애는 설계와 선택 기준

재정렬이 곤란하면 테이블 방문 횟수를 줄이는 쪽으로 접근한다. 쿼리가 참조하는 컬럼을 모두 인덱스에 포함시키면 테이블 접근 단계가 사라지고 Clustering Factor 는 비용에서 빠진다. 앞의 집계 쿼리라면 `(customer_id, amount)` 결합 인덱스로 인덱스만 읽고 끝난다. 인덱스가 커지고 DML 비용이 늘어나는 대신 가장 확실한 효과를 낸다. 조건 컬럼이 여러 개라면 결합 인덱스에 필터 컬럼을 추가해 인덱스 단계에서 최대한 걸러 낸 뒤 남은 행만 테이블에서 확인하도록 만드는 것도 같은 방향이다. 테이블 방문 수가 "인덱스 조건을 통과한 행 수"에 비례하기 때문이다.

정리하면 순서는 이렇다. 먼저 통계 조회로 Clustering Factor 의 위치를 확인하고, 실행 통계로 행당 블록 방문 수를 측정한다. 통계가 실제보다 비관적이면 `TABLE_CACHED_BLOCKS` 를 조정한다. 실제로 흩어져 있고 해당 쿼리가 중요하면 커버링 인덱스를 우선 검토하고, 넓은 범위를 읽는 배치성 쿼리가 주된 부하이며 테이블 재구성 시간을 확보할 수 있으면 Attribute Clustering 과 MOVE 를 적용한다. 어느 것도 맞지 않는 넓은 범위 조회라면 풀 스캔과 파티션 프루닝의 조합이 인덱스보다 나은 답일 수 있다. 힌트로 인덱스를 강제하는 것은 4절의 계산이 보여 주듯 대부분 해결책이 아니다.

이 문서의 SQL 과 비용 수치는 Oracle 문서에 기술된 동작과 비용 공식에 근거해 작성했으며, 이 노트를 만드는 환경에는 Oracle 인스턴스가 없어 직접 실행해 검증하지는 않았다. 적용 전에는 대상 버전의 개발 DB 에서 실행계획과 실행 통계로 확인해야 한다.

## 참고

- Oracle Database SQL Tuning Guide, Optimizer Access Paths: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/optimizer-access-paths.html
- Oracle Database Reference, ALL_INDEXES 의 CLUSTERING_FACTOR: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_INDEXES.html
- Oracle Database PL/SQL Packages and Types Reference, DBMS_STATS 의 TABLE_CACHED_BLOCKS: https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_STATS.html
- Oracle Database Data Warehousing Guide, Attribute Clustering: https://docs.oracle.com/en/database/oracle/oracle-database/19/dwhsg/attribute-clustering.html
- Oracle Database SQL Tuning Guide, DBMS_XPLAN 으로 실행계획 확인: https://docs.oracle.com/en/database/oracle/oracle-database/19/tgsql/generating-and-displaying-execution-plans.html
- Jonathan Lewis, Cost-Based Oracle Fundamentals, Apress (인덱스 비용 공식과 Clustering Factor 장)
- Richard Foote's Oracle Blog, Clustering Factor 및 TABLE_CACHED_BLOCKS 관련 글: https://richardfoote.wordpress.com/
