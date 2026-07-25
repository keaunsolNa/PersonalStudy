Notion 원본: https://app.notion.com/p/3a85a06fd6d381908759f1952fcfac1a

# MySQL 옵티마이저 힌트와 인덱스 스킵 스캔 및 조인 순서 제어

> 2026-07-25 신규 주제 · 확장 대상: MySQL(옵티마이저·실행 계획)

## 학습 목표

- 인덱스 스킵 스캔(Index Skip Scan)이 선행 컴럼 없는 복합 인덱스를 활용하는 조건과 한계를 파악한다.
- 옵티마이저 힌트(`/*+ ... */`)와 인덱스 힌트(`USE/FORCE INDEX`)의 차이 및 우선순위를 구분한다.
- `JOIN_ORDER`, `STRAIGHT_JOIN`으로 조인 순서를 고정하는 상황과 위험을 정리한다.
- `EXPLAIN ANALYZE`로 추정 비용과 실측 비용의 괴리를 진단하는 절차를 적용한다.

## 1. 복합 인덱스와 선행 컴럼 제약

복합 인덱스 `(a, b, c)`는 B-tree에서 `a`로 먼저 정렬되고, 같은 `a` 안에서 `b`, 그 안에서 `c`로 정렬된다. 그래서 전통적으로 선행 컴럼 `a`에 대한 조건이 없으면 이 인덱스를 범위 스캔에 쓸 수 없었다. `WHERE b = 10`만 있으면 옵티마이저는 인덱스 전체를 훑거나 테이블 풀 스캔을 택했다.

MySQL 8.0.13부터 도입된 인덱스 스킵 스캔은 이 제약을 부분적으로 완화한다. 선행 컴럼 `a`의 distinct 값이 적을 때, 옵티마이저가 `a`의 각 고유값을 순회하며 `(a=값1 AND b=10)`, `(a=값2 AND b=10)` … 형태의 여러 범위 스캔으로 분해해 인덱스를 활용한다. 마치 선행 컴럼 조건을 "채워 넣는" 방식이다.

```sql
CREATE TABLE orders (
  id INT PRIMARY KEY,
  status TINYINT,          -- 선행 컴럼, 값 종류 적음 (0~3)
  created_at DATETIME,
  INDEX idx_status_created (status, created_at)
);

-- 선행 컴럼 status 조건이 없어도 스킵 스캔 가능
EXPLAIN SELECT * FROM orders
WHERE created_at >= '2026-07-01';
-- Extra: Using index for skip scan
```

## 2. 스킵 스캔이 유리한 조건과 한계

스킵 스캔은 선행 컴럼의 **카디널리티가 낮을 때만** 이득이다. `status`가 4종류면 4번의 범위 스캔으로 끓나지만, 선행 컴럼이 수만 종류면 수만 번의 서브 스캔이 되어 풀 스캔보다 느려진다. 옵티마이저는 비용 모델로 이를 판단해 스킵 스캔이 불리하면 채택하지 않는다.

실무 관점의 함정은, 스킵 스캔에 의존해 인덱스를 설계하면 안 된다는 것이다. 스킵 스캔은 "선행 컴럼 조건을 빠뜨린 쿼리를 구제하는 최적화"이지 정상 설계가 아니다. 만약 `created_at` 단독 조회가 빈번하다면 `(created_at)` 또는 `(created_at, status)` 인덱스를 별도로 두는 것이 항상 더 빠르다. 스킵 스캔의 서브 스캔 반복 비용(각 선행 값마다 B-tree 재탐색)이 전용 인덱스의 단일 범위 스캔보다 크기 때문이다. 스킵 스캔은 인덱스 추가 여력이 없는 레거시 스키마에서 임시로 성능을 확보하는 안전망으로 보는 것이 맞다.

```sql
SELECT /*+ SKIP_SCAN(orders idx_status_created) */ ...;      -- 강제
SELECT /*+ NO_SKIP_SCAN(orders) */ ...;                       -- 금지
```

## 3. 옵티마이저 힌트 vs 인덱스 힌트

MySQL의 힌트는 두 체계가 공존한다. 오래된 **인덱스 힌트**(`USE INDEX`, `FORCE INDEX`, `IGNORE INDEX`)는 SQL 표준 밖 문법으로 테이블 뒤에 붙인다. 새로운 **옵티마이저 힌트**(`/*+ ... */`)는 8.0에서 확장된 주석형 문법으로, 인덱스뿐 아니라 조인 순서·조인 방식·서브쿼리 전략까지 제어한다.

```sql
-- 인덱스 힌트 (테이블 참조 위치)
SELECT * FROM orders FORCE INDEX (idx_status_created) WHERE status = 1;

-- 옵티마이저 힌트 (SELECT 직후 주석)
SELECT /*+ INDEX(orders idx_status_created) */ * FROM orders WHERE status = 1;
```

`FORCE INDEX`는 "이 인덱스를 쓸 수 있으면 풀 스캔보다 무조건 우선하라"는 강한 지시다. `USE INDEX`는 "이 인덱스들 중에서 고려하라"는 약한 후보 제한이며, 옵티마이저가 여전히 풀 스캔을 택할 수 있다. 둘이 동시에 지정되면 옵티마이저 힌트가 인덱스 힌트보다 우선한다. 실무에서는 표현력이 크고 위치가 명확한 옵티마이저 힌트를 우선 사용하고, 인덱스 힌트는 레거시 호환 목적으로만 남기는 추세다.

힌트 사용의 대원칙은 "옵티마이저의 오판이 EXPLAIN으로 입증됐을 때만" 쓴다는 것이다. 힌트는 통계·데이터 분포가 바뀜면 오히려 나쁜 계획을 고착시킨다. 강제한 인덱스가 나중에 삭제되면 `FORCE INDEX`는 무시되지만, 그 인덱스에 의존한 성능 가정은 조용히 무너진다.

## 4. 조인 순서 제어

조인은 옵티마이저가 어느 테이블을 먼저 읽고(구동 테이블) 어느 것을 나중에 조인할지 결정한다. 구동 테이블 선택이 잘못되면 성능이 수십 배 차이 난다. 이상적으로는 필터 후 행 수가 가장 적은 테이블이 구동 테이블이어야 한다.

옵티마이저가 통계 부족으로 잘못된 순서를 택하면 `JOIN_ORDER` 힌트나 `STRAIGHT_JOIN`으로 고정한다.

```sql
-- 옵티마이저 힌트: users 를 먼저, 그다음 orders
SELECT /*+ JOIN_ORDER(u, o) */ *
FROM users u JOIN orders o ON o.user_id = u.id
WHERE u.region = 'KR';

-- STRAIGHT_JOIN: FROM 절 기재 순서대로 강제
SELECT * FROM users u STRAIGHT_JOIN orders o ON o.user_id = u.id;
```

`STRAIGHT_JOIN`은 `FROM`에 적힌 순서를 그대로 조인 순서로 강제하는 무딘 도구다. `JOIN_ORDER`는 특정 테이블 그룹의 순서만 지정해 더 세밀하다. 조인 순서를 고정할 때 주의점은, 데이터 분포가 시간에 따라 변한다는 것이다. 오늘 `users`가 작은 테이블이어도 6개월 뒤 커지면 고정된 순서가 병목이 된다. 그래서 조인 순서 힌트는 최후의 수단이고, 우선은 조인 컴럼 인덱스와 통계 갱신(`ANALYZE TABLE`)으로 옵티마이저가 스스로 옳은 순서를 찾게 하는 것이 정석이다.

## 5. EXPLAIN ANALYZE로 추정과 실측 대조

`EXPLAIN`은 옵티마이저의 **추정** 계획만 보여준다. 추정 행 수(`rows`)가 실제와 크게 다르면 계획이 틀어진다. `EXPLAIN ANALYZE`(8.0.18+)는 쿼리를 실제로 실행하며 각 단계의 실측 시간·실측 행 수를 함께 출력해 추정 오차를 드러낸다.

```sql
EXPLAIN ANALYZE
SELECT * FROM orders o JOIN users u ON o.user_id = u.id
WHERE u.region = 'KR' AND o.status = 1;
```

출력의 각 노드에는 `cost=... rows=...`(추정)와 `actual time=... rows=... loops=...`(실측)가 병기된다. 진단 포인트는 두 가지다. 첫째, **추정 rows와 actual rows의 괴리**. 추정이 10인데 실측이 100000이면 통계가 낡았거나 히스토그램이 없어 카디널리티 추정이 실패한 것이다. `ANALYZE TABLE`로 통계를 갱신하거나 특정 컴럼에 히스토그램(`ANALYZE TABLE t UPDATE HISTOGRAM ON col`)을 만들어 개선한다. 둘째, **actual time의 loops 배수**. 중첩 루프 조인에서 안쪽 노드가 `loops=100000`이면 그 노드의 단위 비용이 작아도 총합이 커진다 — 조인 순서나 인덱스로 안쪽 반복 자체를 줄여야 한다.

## 6. 진단·튜닝 워크플로우 정리

실전 튜닝은 다음 순서를 따른다. 먼저 `EXPLAIN ANALYZE`로 병목 노드를 찾고 추정/실측 괴리를 확인한다. 괴리가 크면 통계·히스토그램 갱신을 우선 시도한다 — 대부분의 나쁜 계획은 힌트가 아니라 낡은 통계가 원인이다. 통계를 고쳐도 옵티마이저가 여전히 오판하면 그때 힌트를 도입하되, 인덱스 관련은 옵티마이저 힌트(`INDEX`/`NO_INDEX`/`SKIP_SCAN`)로, 조인은 `JOIN_ORDER`로 최소 범위만 고정한다.

트레이드오프를 정리하면, 스킵 스캔은 인덱스 설계 결함을 임시 구제하지만 전용 인덱스만 못하다. 힌트는 즉효약이지만 데이터 변화에 취약해 유지보수 부채가 된다. 반면 통계 갱신과 올바른 복합 인덱스 설계는 근본 처방으로 데이터가 변해도 견고하다. 따라서 힌트는 "지금 당장 급한 프로덕션 쿼리를 막는 지혈"로 쓰고, 배포 주기 안에서 인덱스·통계 기반 근본 해결로 대체하는 것이 지속 가능한 접근이다.

## 7. 커버링 인덱스와 조인 방식 힌트

조인 성능을 좌우하는 또 다른 축은 **조인 방식**이다. MySQL 8.0.18부터 해시 조인(Hash Join)이 도입되어, 인덱스 없는 대량 조인에서 기존 중첩 루프(Nested-Loop) 조인보다 크게 빠를 수 있다. 옵티마이저가 조인 방식을 잘못 택하면 힌트로 지정한다.

```sql
SELECT /*+ HASH_JOIN(o, u) */ *
FROM orders o JOIN users u ON o.user_id = u.id
WHERE o.status = 1;

SELECT /*+ NO_HASH_JOIN(o, u) */ ...;   -- 중첩 루프 강제
```

해시 조인은 조인 컴럼에 인덱스가 없거나 조인 결과 집합이 큰 등가 조인(equi-join)에서 유리하다. 한쪽 테이블을 메모리 해시 테이블로 빌드한 뒤 다른 쪽을 프로브하므로 인덱스 탐색 반복이 없다. 반면 인덱스가 잘 갖춰지고 구동 테이블 필터 후 행이 적으면 중첩 루프가 여전히 빠르다. 해시 빌드 비용(`join_buffer_size` 한계 초과 시 디스크 스필)이 이득을 상쇄할 수 있기 때문이다.

또한 인덱스에 조회 컴럼이 모두 포함된 **커버링 인덱스**는 테이블 접근(random I/O)를 아예 제거해 힌트보다 확실한 이득을 준다. `EXPLAIN`의 `Extra`에 `Using index`가 뜼면 커버링이 성립한 것이다.

```sql
-- 필요한 컴럼을 인덱스에 모두 포함해 커버링 유도
CREATE INDEX idx_cover ON orders (status, created_at, amount);
SELECT status, created_at, amount FROM orders WHERE status = 1;
-- Extra: Using index  → 테이블 접근 없음
```

## 8. 통계·히스토그램과 카디널리티 추정 실패

옵티마이저의 모든 판단은 카디널리티 추정에 기반한다. 추정이 틀리는 두 전형적 원인은 **데이터 스큐**와 **컴럼 간 상관관계**다. `status = 1`인 행이 전체의 90%인데 옵티마이저가 균등 분포를 가정하면 심각한 오판이 나온다. 이때 히스토그램이 실제 분포를 알려준다.

```sql
ANALYZE TABLE orders UPDATE HISTOGRAM ON status, created_at WITH 100 BUCKETS;
-- 이후 옵티마이저가 status 값별 실제 비율을 반영해 추정 정확도 향상
```

히스토그램은 인덱스가 없는 컴럼의 분포도 잡아주므로, 인덱스를 추가하기 부담스러운 컴럼의 필터 선택도(selectivity) 추정에 특히 유용하다. 다만 히스토그램은 자동 갱신되지 않으므로 데이터가 크게 바뀌면 재생성해야 한다. 컴럼 간 상관관계(예: 도시와 우편번호)로 인한 추정 오차는 MySQL이 다중 컴럼 통계를 제한적으로만 지원하므로, 이 경우 복합 인덱스로 옵티마이저가 실제 조합 카디널리티를 참조하게 유도하는 것이 현실적 우회책이다.

한 가지 실전 주의는 `optimizer_switch` 시스템 변수와의 관계다. 스킵 스캔·해시 조인 등 개별 최적화는 `optimizer_switch`로 전역/세션 단위 on/off가 가능하다. 특정 최적화가 회귀를 일으켜 전역 비활성화했다면, 힌트로 강제해도 동작하지 않을 수 있으므로 진단 시 `SELECT @@optimizer_switch`로 현재 스위치 상태를 먼저 확인한다. 또한 옵티마이저 힌트는 쿼리 텍스트에 박히므로 ORM이 생성하는 쿼리에는 적용이 까다롭다. JPA/MyBatis 환경에서는 힌트를 네이티브 쿼리나 SQL 주석 삽입 기능으로 넣거나, 애초에 힌트가 필요 없도록 인덱스·통계로 해결하는 편이 유지보수에 유리하다. 힌트가 코드 곳곳에 흘어지면 스키마 변경 시 어느 쿼리가 어떤 인덱스에 의존하는지 추적이 어려워진다.

최종적으로 진단 워크플로우를 한 문장으로 요약하면 이렇다. `EXPLAIN ANALYZE`로 추정/실측 괴리를 확인하고, 괴리가 크면 `ANALYZE TABLE`·히스토그램으로 통계를 바로잡고, 그래도 계획이 나쁜면 커버링 인덱스로 I/O를 없애고, 마지막에야 힌트로 인덱스·조인 방식·순서를 고정한다 — 힌트는 근본 처방이 모두 소진된 뒤의 최후 수단이다.

## 참고

- MySQL 8.0 Reference Manual — Index Skip Scan (https://dev.mysql.com/doc/refman/8.0/en/range-optimization.html)
- MySQL 8.0 Reference Manual — Optimizer Hints (https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html)
- MySQL 8.0 Reference Manual — EXPLAIN ANALYZE (https://dev.mysql.com/doc/refman/8.0/en/explain.html)
- MySQL 8.0 Reference Manual — Optimizer Statistics and Histograms (https://dev.mysql.com/doc/refman/8.0/en/analyze-table.html)
