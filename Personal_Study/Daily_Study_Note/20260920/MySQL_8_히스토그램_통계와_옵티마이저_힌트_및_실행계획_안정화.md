Notion 원본: https://www.notion.so/3e15a06fd6d381db94e3e31f67591335

# MySQL 8 히스토그램 통계와 옵티마이저 힌트 및 실행계획 안정화

> 2026-09-20 신규 주제 · 확장 대상: Oracle, ORM

## 학습 목표

- 인덱스 카디널리티와 컬럼 히스토그램이 각각 어느 추정 단계에 투입되는지 구분해 통계 수집 전략을 설계한다
- `information_schema.COLUMN_STATISTICS` 의 JSON 을 읽어 버킷 타입·샘플링률·스테일 여부를 판정한다
- 옵티마이저 힌트와 `optimizer_switch`, 구식 인덱스 힌트의 우선순위를 근거로 실행계획을 고정한다
- `EXPLAIN ANALYZE` 의 추정·실측 괴리를 분해해 원인을 통계·조인 알고리즘·조건 필터링으로 특정한다

## 1. 통계의 두 축 — 카디널리티 추정과 선택도 추정

MySQL 의 통계는 InnoDB 가 인덱스 트리에서 뽑는 **인덱스 카디널리티**와 딕셔너리의 **컬럼 히스토그램** 두 덩어리다. 카디널리티는 선두 N개 컬럼 조합의 distinct 개수로 `rows`(접근당 행 수)를 계산해 조인 순서와 인덱스 선택을 좌우하는데, 전수 조사가 아니라 무작위 다이브로 추정하므로 **두 번 돌리면 다른 숫자가 나올 수 있다.** 계획이 배포마다 흔들리는 1차 용의자가 여기다.

```sql
ALTER TABLE orders STATS_PERSISTENT=1, STATS_AUTO_RECALC=1, STATS_SAMPLE_PAGES=200;
ANALYZE TABLE orders;   -- 동기(포그라운드) 재계산
```

다이브 기본값 20 페이지는 수억 행에 너무 작은 표본이라 위처럼 테이블 옵션으로 올린다. `auto_recalc` 는 행의 10% 초과가 변경되면 재계산하지만 비동기이므로, 배치 적재 뒤 즉시 조회하는 파이프라인은 끝에 `ANALYZE TABLE` 로 동기 재계산을 강제해야 한다.

반면 히스토그램은 **선택도**를 답한다. `status='CANCELED'` 가 0.3% 인지 40% 인지는 distinct 개수로 알 수 없고, distinct 가 5개면 무조건 20% 로 균등 가정한다. 실무 데이터는 거의 항상 편향돼 있어 이 가정이 계획을 무너뜨린다.

## 2. 히스토그램 생성과 COLUMN_STATISTICS JSON 해부

버킷 수는 1~1024 이고 **생략하면 100** 이며, 삭제는 `DROP HISTOGRAM ON c` 다. `ON c1, c2` 로 만든 뒤 `ON c1` 만 다시 돌려도 **c2 는 그대로 남으므로**, 운영 배치는 컬럼 목록을 메타 테이블로 관리해 매번 전체를 넘겨야 누락이 없다.

```sql
ANALYZE TABLE orders UPDATE HISTOGRAM ON status, channel_cd WITH 32 BUCKETS;

SELECT COLUMN_NAME, HISTOGRAM->>'$."histogram-type"' AS hist_type,
       HISTOGRAM->>'$."sampling-rate"'               AS sampling_rate,
       HISTOGRAM->>'$."last-updated"'                AS last_updated_utc
FROM   information_schema.COLUMN_STATISTICS WHERE SCHEMA_NAME='shop';
```

딕셔너리 테이블에는 직접 접근할 수 없고 위 뷰로만 읽는다. `sampling-rate` 는 **1 이면 전수 스캔, 미만이면 표본**이라 0.02 가 찍혔는데 계획이 어긋난다면 표본이 희귀값을 놓쳤을 가능성부터 의심한다. `last-updated` 는 **UTC** 기준이라 9시간 차이를 "최신" 으로 오판하는 사고도 잦다.

## 3. singleton 과 equi-height, 그리고 버킷 수의 의미

타입은 사용자가 고르지 않는다. **distinct 개수가 지정 버킷 수 이하면 `singleton`, 초과하면 `equi-height`** 로 자동 결정된다. `singleton` 은 값 하나가 버킷 하나를 독점하므로 등치 비교 선택도가 **정확**하다. `status` 같은 코드성 컬럼은 distinct 가 10~20 이라 32 버킷만 줘도 singleton 이 되고, 투자 대비 효과가 가장 큰 구간이 여기다. `equi-height` 는 각 버킷이 비슷한 **행 수**를 담아 범위 조건 추정이 좋아지는 대신, 등치 비교는 버킷 안 균등 분포를 가정해 오차가 남는다.

기본 100 이면 대부분의 범위 조건에 충분하고, 512~1024 는 생성 비용과 JSON 크기(수백 KB급)를 치르므로 극심한 skew 에만 쓴다. 비용은 `histogram_generation_max_mem_size`(기본 20MB)로 통제돼 이 안에 담기면 전수 읽기, 넘치면 표본이다. **8.0.19(WL#8777)부터 InnoDB 가 자체 샘플링을 제공해 풀 스캔 없이 표본을 뽑아**, 그 이전까지 위험했던 대형 테이블 히스토그램 생성이 현실적인 작업이 됐다.

## 4. 히스토그램이 이기는 자리와 밀리는 자리

적용 술어는 `=`, `<>`, 부등호, `IS [NOT] NULL`, `[NOT] BETWEEN`, `[NOT] IN` 같은 **상수 비교**로 한정되고, `WHERE col1 = col2` 같은 컬럼 간 비교에는 개입하지 못한다.

핵심은 **우선순위**다. 옵티마이저는 **range optimizer 의 행 추정을 히스토그램보다 선호**하고, 인덱스 있는 컬럼의 등치 비교는 **index dive** 로 실제 행 수를 세므로 더 나은 추정을 낸다. 그래서 "인덱스를 걸었으니 히스토그램도" 는 헛수고다. 주된 전장은 **인덱스가 없는 컬럼**, 그중에서도 `filtered` 를 통해 조인 순서를 좌우하는 필터 컬럼이다.

```sql
SET SESSION optimizer_switch='condition_fanout_filter=off';  -- A/B 비교
EXPLAIN FORMAT=JSON SELECT ...;   -- filtered 가 100.00 으로 수렴하는지 확인
```

이 방법은 세션 단위로 되돌릴 수 있어 편하지만 **조건 필터링 전반을 끄므로 무관한 추정까지 함께 바뀐다.** 스테일 통계의 트레이드오프도 인덱스와 정반대다. 인덱스는 DML 마다 즉시 갱신되는 대신 쓰기 비용을 물고, 히스토그램은 오버헤드가 없는 대신 계속 낡아진다. 월말에 `status='CLOSED'` 가 5%에서 70%로 뒤집히는 정산 테이블이라면 **낡은 히스토그램은 통계가 없느니만 못하니, 그 주기에 맞춰 재생성하거나 아예 걸지 않는다.**

## 5. 옵티마이저 힌트 체계와 우선순위

힌트는 `/*+ ... */` 안에 쓰며 `SELECT`·`UPDATE`·`DELETE` 등의 **첫 키워드 직후**에만 파서가 인식한다. 이 위치 제약이 ORM 연동에서 결정적 함정이 된다(9절). 한 블록에 **주석은 하나만** 허용되고 충돌이 있으면 **먼저 나온 것이 이긴다.** 먹었는지는 `EXPLAIN` 직후 `SHOW WARNINGS` 로 확인하는데, 확장 출력에는 **사용된 힌트만** 찍힌다.

구식 인덱스 힌트와의 관계는 8.0.20 에서 정리됐다. `INDEX` / `JOIN_INDEX` / `GROUP_INDEX` / `ORDER_INDEX` 는 `FORCE INDEX [FOR ...]` 와 **동등**하고 동시에 쓰이면 **옵티마이저 힌트가 이기며, 이 4종이 다른 모든 힌트보다 우선**한다. 놓치기 쉬운 점 하나 — `INDEX()` 는 `USE INDEX` 가 아니라 `FORCE INDEX` 라서 **그 인덱스로 행을 찾을 방법이 없을 때만** 테이블 스캔으로 떨어진다. 권고 수준을 원하면 반대로 쓰기 싫은 인덱스를 `NO_INDEX` 로 배제하는 편이 정확하다.

`optimizer_switch` 와의 규칙은 "적용 가능한 힌트가 스위치를 이긴다" 이고, 반대로 힌트가 **부적용**이면 스위치 값이 산다.

```sql
SET optimizer_switch='index_merge_intersection=off';
SELECT /*+ INDEX_MERGE(t1 i_b, i_c) */ * ... ;  -- 힌트가 이겨 Index Merge 사용
SELECT /*+ INDEX_MERGE(t1 i_b)      */ * ... ;  -- 인덱스 1개 → 부적용 → off 적용
```

## 6. 조인 순서·서브쿼리·실행 제한 힌트

`JOIN_FIXED_ORDER` 는 `FROM` 절 순서를 그대로 강제해 `STRAIGHT_JOIN` 과 같고, `JOIN_ORDER` 는 전부, `JOIN_PREFIX` 는 **앞쪽만**, `JOIN_SUFFIX` 는 **뒤쪽만** 고정한다. 테이블이 추가되거나 통계가 변하면 전면 고정은 재앙이 되지만 구동 테이블 고정은 나머지 탐색 여지를 남기므로, 운영에서는 `JOIN_PREFIX` 쪽이 낫다.

```sql
SELECT /*+ JOIN_PREFIX(o) INDEX(o idx_ord_dt) */ o.order_id, c.name
FROM   orders o JOIN customers c ON c.id = o.customer_id
WHERE  o.ord_dt >= '2026-09-01' AND c.grade = 'VIP';   -- o 를 구동 테이블로 고정
```

`SEMIJOIN` 전략은 `DUPSWEEDOUT`, `FIRSTMATCH`, `LOOSESCAN`, `MATERIALIZATION` 넷이며 8.0.17 부터 안티조인에도 적용된다. 지정 전략이 부적용이면 `DUPSWEEDOUT` 으로 떨어지는데, **이를 비활성화하면 greedy 탐색의 가지치기 때문에 최적과 한참 먼 계획이 나올 수 있어** `optimizer_prune_level=0` 으로 확인해야 한다. 세미조인 변환이 안 되면 `SUBQUERY(MATERIALIZATION)` / `SUBQUERY(INTOEXISTS)` 로 직접 고르며, 상관 서브쿼리가 외부 행마다 재평가되면 대개 전자가 답이다.

`MAX_EXECUTION_TIME(N)` 은 밀리초 타임아웃이며 `SELECT` 의 **읽기 전용** 문장에만 적용된다. UNION·서브쿼리가 있으면 **첫 `SELECT` 직후**에 와야 하고, **저장 프로그램 안에서는 조용히 무시된다.** `SET_VAR(join_buffer_size=64M)` 은 해당 문장 동안만 세션 값을 바꿔 `SET` → 실행 → 복원을 한 줄로 줄인다.

## 7. optimizer_switch 플래그와 해시 조인의 계보

| 플래그 | 기본 | 끌 때의 위험 |
| --- | --- | --- |
| `derived_merge` | on | 파생 테이블·뷰·CTE 가 구체화돼 임시 테이블 I/O 급증 |
| `block_nested_loop` | on | 8.0.20 이후 해시 조인만 제어. 끄면 중첩 루프로 퇴화 |
| `hash_join` | on | **8.0.18 에서만** 유효. 이후엔 무효과 |
| `condition_fanout_filter` | on | `filtered` 가 100 으로 수렴, 조인 순서가 바뀜 |

해시 조인은 버전 경계를 정확히 외워야 한다. **8.0.18** 에서 등치 조인 조건은 있으나 쓸 인덱스가 없을 때용으로 도입됐고 이 버전에서만 `HASH_JOIN` 힌트와 `hash_join` 플래그가 동작한다. **8.0.19** 에서 둘 다 무효화됐고, **8.0.20** 에서 **BNL 지원이 제거**돼 그 자리를 해시 조인이 대체하면서 비등치·아우터·세미·안티 조인까지 범위가 넓어졌다.

해시 조인 메모리는 `join_buffer_size` 를 넘지 못하고 넘치면 디스크로 스필하는데, 이때 파일 수가 `open_files_limit` 를 초과하면 **조인 자체가 실패**할 수 있다. 세션 변수이므로 대형 조인 한 건에만 `SET_VAR` 로 국소 적용한다. 전역으로 올리면 동시 접속 수만큼 곱해져 메모리가 날아간다.

## 8. EXPLAIN FORMAT=JSON 과 EXPLAIN ANALYZE 로 괴리 진단하기

포맷은 `TRADITIONAL`(기본), `JSON`, 8.0.16 에 추가된 `TREE` 셋이다. **해시 조인 사용 여부를 보여주는 유일한 포맷은 `TREE`** 이며 `EXPLAIN ANALYZE` 는 항상 `TREE` 를 쓰고 쿼리를 실제로 실행해 실측을 붙인다. `FORMAT=JSON` 은 옵티마이저의 **생각**을 보여주는데, `cost_info` 는 절대값보다 **형제 노드 간 비율**이 중요하고 `filtered` 가 히스토그램 효과가 드러나는 자리다.

```
-> Inner hash join (t2.c2 = t1.c1) (cost=4.70 rows=6) (actual time=0.032..0.035 rows=6 loops=1)
    -> Table scan on t2 (cost=0.06 rows=6) (actual time=0.003..0.005 rows=6 loops=1)
    -> Hash
        -> Table scan on t1 (cost=0.85 rows=6) (actual time=0.018..0.022 rows=6 loops=1)
```

`actual time=A..B` 에서 **A 는 첫 행까지, B 는 마지막 행까지**이며 **단위는 밀리초**다. `loops` 는 실행 횟수이고 표시된 시간은 **1회 실행 평균**이라 총 소요는 `B × loops` 로 어림하는데, 안쪽 노드의 `loops` 가 수만이면 노드당 0.05ms 라도 전체로는 수 초다. 진단은 노드마다 **추정 대 실측 `rows` 비율**을 구하는 기계적 절차다.

| 증상 | 해석 | 1차 조치 |
| --- | --- | --- |
| 추정 ≪ 실측 | 선택도 낙관. 히스토그램 부재·스테일 | `UPDATE HISTOGRAM` 후 재측정 |
| 추정 ≫ 실측 | 인덱스 카디널리티가 낡음 | `ANALYZE TABLE`, 샘플 상향 |
| rows 는 맞는데 느림 | 접근 방법 문제 | filesort·임시 테이블 확인 |
| 안쪽 `loops` 과대 | 조인 순서가 뒤집힘 | `JOIN_PREFIX` 로 구동 고정 |

실제로 실행하므로 **DML 은 절대 넣지 말 것**이며, 읽기라도 대형 쿼리는 운영 부하를 유발하니 스테이징에서 돌리거나 `MAX_EXECUTION_TIME` 을 함께 건다.

## 9. 실행계획 안정화 — Oracle 과의 격차를 메우는 법

Oracle 의 **SQL Plan Baseline**(`DBMS_SPM`)은 검증된 계획만 쓰도록 허용 목록을 유지해 새 계획은 더 낫다고 증명되기 전까지 쓰지 않고, **SQL Profile** 은 SQL 텍스트 대신 통계 보정 정보를 붙인다. 둘 다 **코드 변경 없이** DBA 가 계획을 통제하는 수단인데, MySQL 8 에는 대응 기구가 **없다.**

**(1) 힌트 인라인.** 가장 확실하지만 코드에 박히는 순간 통계 변화에 대한 적응력을 잃으므로, 전면 고정보다 `JOIN_PREFIX` 구동 테이블 고정처럼 범위를 최소화한다. **(2) `optimizer_switch` 세션 고정.** 특정 배치나 리포트 커넥션에만 플래그를 건다. 전역 변경은 전체 워크로드를 흔들어 거의 항상 나쁜 선택이다. **(3) Rewriter 플러그인.** SQL 텍스트를 손댈 수 없을 때 유일한 서버 측 수단이다.

```sql
INSERT INTO query_rewrite.rewrite_rules (pattern, replacement, pattern_database)
VALUES ('SELECT * FROM orders WHERE status = ?',
        'SELECT /*+ INDEX(orders idx_st) */ * FROM orders WHERE status = ?', 'shop');
CALL query_rewrite.flush_rewrite_rules();
```

**8.0.12 이전에는 `SELECT` 만** 대상이었고 이후 DML 이 추가됐다. **뷰 정의나 저장 프로그램 안의 문장은 재작성되지 않으며**, 문장마다 비용이 붙으니 **임시 우회 장치**로 취급하고 근본 수정 후 룰 제거까지 세트로 만든다.

**JPA/Hibernate 에서의 힌트 주입.** 실무자들이 가장 많이 빠지는 함정이다. `@QueryHint` 로 흔히 쓰는 `org.hibernate.comment` 는 `prependComment` 구현상 **SQL 앞에 주석을 붙인다.**

```java
@QueryHints(@QueryHint(name = "org.hibernate.comment", value = "+ INDEX(o idx_ord_dt)"))
List<Order> findByOrdDtBetween(LocalDate from, LocalDate to);
```

이 코드가 만드는 SQL 은 `/* + INDEX(...) */ select ...` 이고, **MySQL 파서는 `SELECT` 앞의 주석을 힌트로 인식하지 않는다**(5절의 위치 제약). 즉 이 경로는 slow log 태깅에는 훌륭하지만 **힌트 전달 수단이 아니다.** dialect 경로(`getQueryHintString`)도 답이 아니다. `MySQLDialect` 의 `IndexQueryHintHandler` 가 끼워 넣는 것은 구식 `use index (...)` 인 데다, 정규식 기반이라 `WHERE` 가 없는 쿼리에는 아무 일도 하지 않는다.

현실적 선택지는 `@Query(nativeQuery = true)` 로 네이티브 SQL 을 쓰거나, 통계 의존도가 높은 소수의 집계 쿼리만 MyBatis 같은 계층으로 분리하거나, Rewriter 로 주입하는 셋이다. 검증은 어느 쪽이든 같다. 실제로 보낸 SQL 을 `events_statements_history_long` 로 캡처해 그대로 `EXPLAIN` + `SHOW WARNINGS` 에 넣어 **힌트가 살아남았는지** 확인하고, 이를 통합 테스트에 넣어 힌트가 조용히 증발하는 사고를 막는다.

## 참고

- MySQL 8.0 Manual, 10.9.6 Optimizer Statistics — https://dev.mysql.com/doc/refman/8.0/en/optimizer-statistics.html
- MySQL 8.0 Manual, 10.9.3 Optimizer Hints — https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html
- MySQL 8.0 Manual, 10.9.2 Switchable Optimizations — https://dev.mysql.com/doc/refman/8.0/en/switchable-optimizations.html
- MySQL 8.0 Manual, 10.2.1.4 Hash Join Optimization — https://dev.mysql.com/doc/refman/8.0/en/hash-joins.html
- MySQL 8.0 Manual, 15.7.3.1 ANALYZE TABLE — https://dev.mysql.com/doc/refman/8.0/en/analyze-table.html
- MySQL 8.0 Manual, 15.8.2 EXPLAIN Statement — https://dev.mysql.com/doc/refman/8.0/en/explain.html
- MySQL 8.0 Manual, 7.6.4 Rewriter Query Rewrite Plugin — https://dev.mysql.com/doc/refman/8.0/en/rewriter-query-rewrite-plugin.html
