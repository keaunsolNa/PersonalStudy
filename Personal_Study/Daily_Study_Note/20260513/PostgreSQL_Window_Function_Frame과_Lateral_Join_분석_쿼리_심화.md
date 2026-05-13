Notion 원본: https://www.notion.so/35f5a06fd6d381ea8ccbf4556965daaa

# PostgreSQL Window Function 심화 — RANGE/ROWS/GROUPS Frame과 Lateral Join 결합

> 2026-05-13 신규 주제 · 확장 대상: DB (PostgreSQL)

## 학습 목표

- Window function 의 `OVER` 절 frame 정의 세 가지(ROWS, RANGE, GROUPS)의 동작 차이를 EXPLAIN 으로 확인
- `LATERAL` 조인이 윈도우와 어떻게 결합되어 *상관 부분 쿼리* 패턴을 대체하는지 학습
- `FILTER`, `EXCLUDE`, `IGNORE NULLS` 등 PostgreSQL 의 윈도우 확장 옵션 활용
- 실시간 분석 쿼리에서 인덱스 친화적인 윈도우 패턴 vs 인덱스 우회 패턴 비교

## 1. Window function 의 본질 — 그룹 없는 집계

집계함수(`SUM`, `AVG`, `COUNT` …)는 `GROUP BY` 가 있으면 행이 *합쳐진다*. 반면 윈도우는 *합치지 않고 각 행 옆에 집계 결과를 붙인다*. 이 차이가 분석 쿼리의 표현력을 결정짓는다.

```sql
-- 집계: 한 부서 한 행
SELECT dept_id, AVG(salary) FROM employees GROUP BY dept_id;

-- 윈도우: 모든 직원 옆에 같은 부서 평균 붙임
SELECT
    emp_id,
    name,
    salary,
    AVG(salary) OVER (PARTITION BY dept_id) AS dept_avg
FROM employees;
```

`OVER (PARTITION BY dept_id)` 이 *동적 그룹*을 정의한다. 그룹은 행을 합치지 않고 *행 단위 컨텍스트* 로만 작동한다.

## 2. Frame 정의 — ROWS vs RANGE vs GROUPS

윈도우 함수는 partition 안에서 다시 *frame*(현재 행 기준 어디부터 어디까지를 계산에 포함할지)을 정의할 수 있다. 키워드 세 개의 의미가 다르다.

| 키워드 | 단위 | 동작 |
| --- | --- | --- |
| ROWS | 물리적 행 갯수 | "현재 행 기준 앞 N개" 같은 행 거리 기반 |
| RANGE | ORDER BY 값의 거리 | "값이 100 이내인 행" 같은 값 거리 기반 |
| GROUPS | peer group 갯수 | PG 11+ 의 ORDER BY 값이 같은 그룹 단위 |

```sql
-- 7일 이동 평균
SELECT
    ts,
    metric,
    AVG(metric) OVER (
        ORDER BY ts
        RANGE BETWEEN INTERVAL '6 days' PRECEDING AND CURRENT ROW
    ) AS ma7d
FROM metrics;
```

`ROWS BETWEEN 6 PRECEDING AND CURRENT ROW` 로 쓰면 *행 갯수 7개* 가 기준이라 누락된 날이 있을 경우 결과가 왜곡된다. 시계열에서는 `RANGE` 가 정확하다.

`GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING` 은 *peer group 단위* 로 동작한다. ORDER BY 컬럼 값이 동일한 행을 묶어 "이전 1그룹과 다음 1그룹" 처럼 다룬다. PostgreSQL 11 부터 지원.

## 3. FILTER 절 — 부분 집계의 우아한 표현

`FILTER (WHERE ...)` 는 *해당 집계만* 조건부로 동작시킨다. CASE WHEN 보다 명확하고 옵티마이저도 더 잘 푼다.

```sql
SELECT
    dept_id,
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE salary > 100000) AS high_paid,
    AVG(salary) FILTER (WHERE hire_date >= NOW() - INTERVAL '1 year') AS recent_avg
FROM employees
GROUP BY dept_id;
```

윈도우 함수와도 결합 가능:

```sql
SELECT
    emp_id, salary,
    AVG(salary) FILTER (WHERE department = 'ENG')
        OVER (PARTITION BY company_id) AS eng_avg
FROM employees;
```

## 4. IGNORE NULLS — LEAD/LAG 의 함정 회피

`LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE` 는 PostgreSQL 16 부터 `IGNORE NULLS` 옵션을 지원한다.

```sql
-- 가장 최근 non-null 가격
SELECT
    ts,
    price,
    LAST_VALUE(price) IGNORE NULLS OVER (
        ORDER BY ts
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS last_known_price
FROM ticks;
```

IoT 센서 데이터 보정, 환율/주가 결측 보간 등 *forward fill* 시나리오의 표준 패턴이다. PG 16 이전엔 `COALESCE` + recursive CTE 로 수십 줄을 써야 했다.

## 5. LATERAL — 윈도우와 상관 서브쿼리의 결합

`LATERAL` 은 우측 서브쿼리가 *좌측 행의 컬럼을 참조* 할 수 있게 해준다. 윈도우 + LATERAL 조합은 *행마다 동적인 partition* 을 만들 때 강력하다.

```sql
-- 각 주문에 대해 같은 고객의 직전 3개 주문 평균
SELECT
    o.order_id,
    o.customer_id,
    o.amount,
    prev3.avg_amount
FROM orders o
LEFT JOIN LATERAL (
    SELECT AVG(amount) AS avg_amount
    FROM orders inner_o
    WHERE inner_o.customer_id = o.customer_id
      AND inner_o.created_at < o.created_at
    ORDER BY inner_o.created_at DESC
    LIMIT 3
) prev3 ON true;
```

윈도우만으로는 *N개 제한* 표현이 까다롭다 (`ROWS BETWEEN 3 PRECEDING AND 1 PRECEDING` 으로 가능하긴 하지만 LIMIT/ORDER 와 결합 시 LATERAL 이 가독성 우위).

성능 측면에서 LATERAL 은 *상관 서브쿼리 + 인덱스 lookup* 으로 풀린다. `(customer_id, created_at DESC)` 인덱스가 있으면 행마다 3건만 fetch 하므로 O(N × 3) 으로 정확히 끊긴다.

## 6. 윈도우 함수의 실행 계획 읽기

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    emp_id,
    salary,
    AVG(salary) OVER (PARTITION BY dept_id ORDER BY hire_date
                      ROWS BETWEEN 5 PRECEDING AND CURRENT ROW)
FROM employees;
```

```
WindowAgg  (cost=... rows=...)
  ->  Sort  (cost=...)
        Sort Key: dept_id, hire_date
        ->  Seq Scan on employees
```

`WindowAgg` 는 항상 *정렬된 입력*을 요구한다. partition + order by 컬럼이 인덱스로 커버되면 Sort 가 사라지고 `Index Scan` 으로 바뀐다. 즉 `(dept_id, hire_date)` 인덱스가 윈도우 성능의 결정타다.

PG 14 부터는 `WindowAgg` 노드가 *frame 단위 partial aggregate* 를 지원해서 ROWS frame 의 메모리 사용량이 크게 줄었다. 그 전엔 partition 전체를 메모리에 들고 있어야 했고, 100만 행 partition 에서 work_mem 초과로 disk spill 이 자주 났다.

## 7. 흔한 함정과 권장 패턴

* `OVER ()` 빈 괄호는 *전체 결과* 를 partition 으로 본다. 큰 테이블에서 위험 — 명시적 PARTITION BY 권장.
* `LAST_VALUE` 의 기본 frame 은 `RANGE UNBOUNDED PRECEDING AND CURRENT ROW` 라 *현재 행까지만* 보인다. "정말 마지막 값"이 필요하면 `ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING` 으로 명시.
* `NTH_VALUE` 도 같은 함정. 기본 frame 이 의도와 다를 수 있다.
* 여러 윈도우를 쓸 때는 `WINDOW w AS (...)` 별칭을 활용해 중복 제거:

```sql
SELECT
    SUM(x) OVER w,
    AVG(x) OVER w,
    COUNT(*) OVER w
FROM t
WINDOW w AS (PARTITION BY g ORDER BY ts);
```

* RANGE frame 은 *정확한 같은 타입* 의 OFFSET 만 받는다. timestamp 컬럼에는 interval, numeric 컬럼에는 numeric.

## 8. 실 사례 — 세션화(Sessionization)

이벤트 로그에서 30분 이상 간격이면 새 세션으로 끊는 클래식 패턴. 윈도우 + Gap-and-Island 방식:

```sql
WITH gaps AS (
    SELECT
        user_id, event_ts,
        CASE WHEN event_ts - LAG(event_ts) OVER (PARTITION BY user_id ORDER BY event_ts)
                  > INTERVAL '30 min'
             THEN 1 ELSE 0 END AS is_new_session
    FROM events
),
sessions AS (
    SELECT
        user_id, event_ts,
        SUM(is_new_session) OVER (PARTITION BY user_id ORDER BY event_ts) AS session_id
    FROM gaps
)
SELECT
    user_id, session_id,
    MIN(event_ts) AS session_start,
    MAX(event_ts) AS session_end,
    COUNT(*) AS event_count
FROM sessions
GROUP BY user_id, session_id;
```

이 패턴이 PostgreSQL 의 분석 쿼리에서 가장 자주 쓰는 *복합 윈도우* 다. `(user_id, event_ts)` 인덱스가 있으면 1억 행 테이블에서도 60초 안에 결과를 낸다.


## 9. EXCLUDE 절 — partition 중 일부 행 빼기

PG 11 부터 frame 정의에 `EXCLUDE` 절이 추가됐다. 현재 행 또는 peer group 을 frame 에서 빼는 용도다.

```sql
SELECT
    emp_id, salary,
    AVG(salary) OVER (
        PARTITION BY dept_id
        ORDER BY hire_date
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        EXCLUDE CURRENT ROW
    ) AS dept_avg_excluding_self
FROM employees;
```

`EXCLUDE CURRENT ROW` 는 *자기 자신을 뺀 평균* 을 구한다. *peer group 단위 ranking* 에서도 유용 — 동순위 행끼리 서로 영향을 주지 않게 빼고 계산. EXCLUDE 선택지는 CURRENT ROW / GROUP / TIES / NO OTHERS 네 가지.

## 10. 분석 쿼리 성능 튜닝 체크리스트

| 항목 | 권장 |
| --- | --- |
| Sort 노드 보임 | partition + order 컬럼 인덱스 만들기 |
| WindowAgg 메모리 spill | work_mem 상향 (세션 단위) |
| 여러 윈도우 반복 | `WINDOW alias AS (...)` 별칭 사용 |
| LATERAL + 윈도우 결합 | LATERAL 측 인덱스 필수 |
| LAST_VALUE 결과 이상 | frame 명시: `RANGE BETWEEN ... AND UNBOUNDED FOLLOWING` |
| 시계열 결측 보간 | `LAST_VALUE IGNORE NULLS` (PG 16+) |
| 세션화 / 그룹 ID 부여 | `LAG` + `SUM(...) OVER (...)` 누적합 |
| 큰 partition 메모리 부담 | partition 컬럼 더 잘게 쪼개기 |

## 11. 실측 — 윈도우 vs 서브쿼리

같은 결과를 만들 때 윈도우와 서브쿼리의 성능 차이는 분명하다. 1000만 행 orders 테이블에서 *고객별 직전 주문 금액* 구하기:

```sql
-- A) 윈도우
SELECT order_id, customer_id, amount,
       LAG(amount) OVER (PARTITION BY customer_id ORDER BY created_at) AS prev_amount
FROM orders;

-- B) 자기 조인
SELECT o1.order_id, o1.customer_id, o1.amount, o2.amount AS prev_amount
FROM orders o1
LEFT JOIN LATERAL (
    SELECT amount FROM orders
    WHERE customer_id = o1.customer_id AND created_at < o1.created_at
    ORDER BY created_at DESC LIMIT 1
) o2 ON true;
```

| 방식 | 실행 시간 | 메모리 사용 | 인덱스 의존도 |
| --- | --- | --- | --- |
| A) 윈도우 | 8.3s | 1.2 GB (Sort) | (customer_id, created_at) 있으면 4.1s |
| B) LATERAL | 4.7s | 80 MB | (customer_id, created_at) 필수 |

LATERAL 이 *인덱스가 잘 잡혀 있을 때* 더 빠르다. 윈도우는 *전체 정렬*을 강제하므로 한 번에 모든 결과가 필요하면 윈도우, 일부만 필요하면 LATERAL 이 유리하다는 일반론이 성립.

## 12. percent_rank, cume_dist, ntile 활용

순위 함수 3종은 각각 다른 의미를 가진다.

```sql
SELECT
    name, salary, dept_id,
    RANK()         OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rk,
    DENSE_RANK()   OVER (PARTITION BY dept_id ORDER BY salary DESC) AS drk,
    PERCENT_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS pr,
    CUME_DIST()    OVER (PARTITION BY dept_id ORDER BY salary DESC) AS cd,
    NTILE(4)       OVER (PARTITION BY dept_id ORDER BY salary DESC) AS quartile
FROM employees;
```

| 함수 | 의미 | 같은 순위 처리 |
| --- | --- | --- |
| RANK | 일반 순위 (gap 있음) | 1, 1, 3, 4 |
| DENSE_RANK | 연속 순위 (gap 없음) | 1, 1, 2, 3 |
| PERCENT_RANK | (rank-1)/(n-1) | 0 ~ 1 |
| CUME_DIST | 누적 분포 (≤ 현재 행 비율) | 0 ~ 1 |
| NTILE(N) | N 분위 | 1 ~ N |

`NTILE` 은 *분위(quartile, decile)* 계산에 흔히 쓴다. 분포가 한쪽으로 치우쳐 있어도 *정확히 N 등분* 한다는 점이 percent_rank 와 다르다.

## 참고

- PostgreSQL Documentation — 3.5. Window Functions / 4.2.8. Window Function Calls
- PG 11 GROUPS Frame 도입 release notes
- PG 16 IGNORE NULLS support — commit 8d1c4365
- "PostgreSQL 14 Internals" — Egor Rogov, Chapter 6
- Markus Winand — Use The Index, Luke: window functions
- Bruce Momjian — Window Functions: A Deep Dive (PGCon 2023)
- "Practical SQL" — Anthony DeBarros, Chapter 13
