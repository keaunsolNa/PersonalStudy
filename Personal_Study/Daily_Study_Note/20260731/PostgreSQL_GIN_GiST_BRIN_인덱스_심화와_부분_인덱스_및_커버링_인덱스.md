Notion 원본: https://www.notion.so/3ae5a06fd6d381c6a05ae33e893b4c01

# PostgreSQL GIN·GiST·BRIN 인덱스 심화와 부분 인덱스 및 커버링 인덱스

> 2026-07-31 신규 주제 · 확장 대상: PostgreSQL

## 학습 목표

- B-tree 로 해결되지 않는 검색 패턴을 GIN·GiST·BRIN 이 각각 어떻게 처리하는지 구분한다
- 배열·JSONB·전문검색에 GIN 을 적용하고 연산자 클래스를 선택한다
- 부분 인덱스와 표현식 인덱스로 인덱스 크기와 쓰기 비용을 줄이는 경계를 설계한다
- INCLUDE 커버링 인덱스로 Index Only Scan 을 유도하고 visibility map 의 영향을 이해한다

## 1. 왜 B-tree 만으로는 부족한가

PostgreSQL 의 기본 인덱스인 B-tree 는 `=`, `<`, `>`, `BETWEEN`, `LIKE 'prefix%'` 같은 **선형 순서 기반** 조회에 최적이다. 그러나 실무에는 순서로 환원되지 않는 검색이 많다. 배열에 특정 원소가 포함되는지(`@>`), JSONB 에 특정 키가 존재하는지, 문서에 특정 단어가 들어 있는지(전문검색), 두 도형이 겹치는지(공간), 좁은 범위의 대용량 시계열을 스캔하는지 등이다. 이런 요구에 대해 PostgreSQL 은 **접근 메서드(access method)** 를 여러 개 제공한다. 핵심은 "인덱스는 하나가 아니라, 데이터 형태와 질의 연산자에 맞춰 고르는 것"이라는 사고 전환이다.

```sql
-- B-tree 로는 이런 질의가 인덱스를 못 탄다
SELECT * FROM article WHERE tags @> ARRAY['postgres'];      -- 배열 포함
SELECT * FROM doc     WHERE payload @> '{"status":"active"}';-- JSONB 포함
SELECT * FROM post    WHERE to_tsvector('english', body)
                            @@ to_tsquery('index & scan');    -- 전문검색
```

## 2. GIN — 다중 값 컬럼의 역색인

GIN(Generalized Inverted Index)은 **하나의 행이 여러 키를 갖는** 데이터에 쓰는 역색인이다. 배열, JSONB, tsvector 가 대표적이다. 내부적으로 각 요소(단어·배열 원소·JSONB 경로)를 키로 두고, 그 키를 포함하는 행 목록(posting list/tree)을 매단다. 따라서 "이 원소를 포함하는 모든 행"을 매우 빠르게 찾는다.

```sql
-- 배열 포함 검색
CREATE INDEX idx_article_tags ON article USING gin (tags);

-- JSONB: 기본 jsonb_ops 는 모든 키/값 경로를 색인 (범용, 큼)
CREATE INDEX idx_doc_payload ON doc USING gin (payload);

-- JSONB: jsonb_path_ops 는 @> 전용, 더 작고 빠름 (키 존재 ? 연산자는 미지원)
CREATE INDEX idx_doc_payload_path ON doc USING gin (payload jsonb_path_ops);
```

연산자 클래스 선택이 성능을 좌우한다. `jsonb_ops`(기본)는 `@>`, `?`, `?|`, `?&` 를 모두 지원하지만 인덱스가 크다. `jsonb_path_ops` 는 `@>` 만 지원하는 대신 인덱스가 작고 조회가 빠르다. "포함 검색만 쓴다"면 후자가 정답이다.

GIN 의 trade-off 는 **쓰기 비용**이다. 한 행 삽입 시 여러 키를 갱신해야 하므로 갱신이 무겁다. 이를 완화하는 것이 `fastupdate` 와 `gin_pending_list_limit` 다. 삽입을 pending list 에 모았다가 일괄 병합하는데, 벌크 로드 시 유용하지만 pending list 가 커지면 조회 시 순차 스캔이 섞여 지연이 튀다. 대량 적재 후에는 `VACUUM` 이나 `gin_clean_pending_list()` 로 병합을 강제하는 것이 좋다.

## 3. GiST — 겹침·근접·범위 질의

GiST(Generalized Search Tree)는 **균형 트리이지만 노드가 "포함 관계"를 표현**하는 일반화된 구조다. 각 내부 노드는 자식들을 감싸는 경계(bounding box, 범위, 부분집합)를 저장한다. 덕분에 "겹친다", "가깝다", "포함한다" 같은 **순서가 없는 위상 질의**를 처리한다. 공간 데이터(PostGIS), 범위 타입(`tsrange`, `int4range`), 최근접 이웃(KNN) 검색이 주 무대다.

```sql
-- 예약 시스템: 시간 범위가 겹치는지 (&&) 를 GiST 로 색인
CREATE TABLE reservation (
    room_id int,
    during  tsrange
);
CREATE INDEX idx_res_during ON reservation USING gist (during);

-- 배타 제약: 같은 방에서 시간대가 겹치는 예약을 DB 레벨에서 금지
ALTER TABLE reservation
    ADD CONSTRAINT no_overlap
    EXCLUDE USING gist (room_id WITH =, during WITH &&);

-- KNN: 특정 지점에서 가까운 순서로 정렬 (거리 연산자 <-> 활용)
SELECT * FROM place ORDER BY location <-> point(37.5, 127.0) LIMIT 5;
```

배타 제약(EXCLUDE)은 GiST 의 킬러 기능이다. 애플리케이션 코드로 "겹침 검사 후 삽입"을 하면 동시성 하에서 경쟁 조건이 생기지만, GiST 배타 제약은 DB 가 원자적으로 겹침을 막아준다. GIN 과 비교하면 GiST 는 조회가 GIN 보다 느릴 수 있으나 **갱신이 가볍고 손실 압축(lossy)** 이라 후보를 좁힌 뒤 재검사하는 구조다. 그래서 전문검색은 보통 GIN 을 쓰고, 공간·범위는 GiST 를 쓴다.

## 4. BRIN — 대용량 순차 데이터의 초경량 인덱스

BRIN(Block Range Index)은 **물리적으로 정렬된 대용량 테이블**을 위한 매우 작은 인덱스다. 개별 행이 아니라 연속된 블록 범위(기본 128 페이지)마다 그 안의 최소·최대값만 저장한다. 시계열 로그처럼 삽입 순서와 값의 순서가 일치하는 컬럼(예: `created_at`)에서, 인덱스 크기가 테이블의 수천 분의 1 인데도 범위 질의에서 불필요한 블록을 통째로 건너뛴다.

```sql
-- 수억 건 로그, created_at 이 대체로 물리 순서와 일치할 때
CREATE INDEX idx_log_created_brin ON log
    USING brin (created_at) WITH (pages_per_range = 64);

-- 특정 날짜 범위 스캔 시, 범위 밖 블록을 건너뛴다
SELECT count(*) FROM log
 WHERE created_at >= '2026-07-01' AND created_at < '2026-07-08';
```

BRIN 의 실측 감각: 1억 건 테이블에서 B-tree 인덱스가 수 GB 를 차지할 때 BRIN 은 수백 KB 수준이다. 대신 **정확도는 상관성(correlation)에 의존**한다. 값이 물리적으로 흩어져 있으면(잦은 UPDATE, 랜덤 삽입) 거의 모든 블록의 min-max 가 겹쳐 인덱스가 무용지물이 된다. `pg_stats.correlation` 이 1(또는 -1)에 가까운 컬럼에만 유효하다. 삽입이 순서를 흘트러뜨렸다면 `brin_summarize_new_values()` 또는 재구성으로 요약을 갱신해야 한다.

## 5. 부분 인덱스와 표현식 인덱스

**부분 인덱스(partial index)** 는 `WHERE` 조건을 만족하는 행만 색인한다. 전체의 소수만 조회 대상이거나, 특정 상태 값만 자주 찾을 때 인덱스 크기와 쓰기 비용을 극적으로 줄인다.

```sql
-- 미처리 주문만 자주 조회한다면, 그 행만 색인
CREATE INDEX idx_order_pending ON orders (created_at)
    WHERE status = 'PENDING';

-- soft delete: 살아있는 행에만 유니크 제약
CREATE UNIQUE INDEX uq_user_email_active ON users (email)
    WHERE deleted_at IS NULL;
```

부분 인덱스가 쓰이려면 질의의 `WHERE` 가 인덱스의 조건을 **논리적으로 포함**해야 한다. `status = 'PENDING'` 인덱스는 `WHERE status = 'PENDING' AND ...` 질의에서만 후보가 된다. 두 번째 예시는 실무에서 매우 유용하다. soft delete 환경에서 "활성 사용자만 이메일 유일"을 DB 로 강제하는 정석이다.

**표현식 인덱스(expression index)** 는 컬럼 값이 아니라 함수 결과를 색인한다. 대소문자 무시 검색이 대표적이다.

```sql
CREATE INDEX idx_users_lower_email ON users (lower(email));
-- 아래 질의가 인덱스를 타려면 좌변 표현식이 정확히 일치해야 한다
SELECT * FROM users WHERE lower(email) = lower('User@Example.com');
```

주의점은 옵티마이저가 인덱스를 선택하려면 질의의 표현식이 인덱스 정의와 **구문적으로 매칭**되어야 한다는 것이다. `email = '...'` 로 쓰면 `lower(email)` 인덱스를 못 탄다.

## 6. INCLUDE 커버링 인덱스와 Index Only Scan

**커버링 인덱스**는 질의가 필요로 하는 모든 컬럼을 인덱스가 담아, 힙(테이블) 접근 없이 인덱스만 읽고 끝내는 것이다(Index Only Scan). PostgreSQL 11+ 는 `INCLUDE` 로 검색 키가 아닌 컬럼을 인덱스 리프에 얹을 수 있다.

```sql
-- user_id 로 찾고 status, amount 를 반환하는 질의를 커버
CREATE INDEX idx_order_cover ON orders (user_id) INCLUDE (status, amount);

EXPLAIN (ANALYZE, BUFFERS)
SELECT status, amount FROM orders WHERE user_id = 42;
-- 계획에 "Index Only Scan using idx_order_cover" 가 뜼면 성공
```

`INCLUDE` 컬럼은 정렬·검색에 쓰이지 않으므로 인덱스 키 자릿수를 늘리지 않아 B-tree 깊이를 얕게 유지한다. 다만 결정적 함정이 있다. Index Only Scan 은 **visibility map(VM)** 이 해당 힙 페이지를 "모두 가시(all-visible)"로 표시했을 때만 힙 접근을 생략한다. 갱신이 잦아 VM 이 최신이 아니면 각 행마다 힙을 다시 확인(heap fetch)하게 되어 이점이 사라진다. `EXPLAIN (ANALYZE)` 출력의 `Heap Fetches` 값이 0 에 가까워야 진짜 커버링이 된 것이다. 그래서 커버링 인덱스는 **적절한 autovacuum 으로 VM 을 최신으로 유지**하는 것과 세트로 관리해야 한다. 요약하면 인덱스 선택은 데이터 형태(단일값·다중값·공간·순차)와 질의 연산자, 그리고 쓰기·공간 비용의 균형을 함께 보는 설계 행위다.

## 7. 인덱스 선택 진단과 실무 체크리스트

인덱스를 만들었는데 옵티마이저가 안 쓰는 일은 흔하다. 진단의 출발점은 `EXPLAIN (ANALYZE, BUFFERS)` 다. 계획에 원하는 인덱스가 등장하는지, 그리고 추정 행 수(`rows`)와 실제 행 수의 괴리를 본다.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM article WHERE tags @> ARRAY['postgres'];
--  Bitmap Heap Scan on article  (actual rows=1200 ...)
--    Recheck Cond: (tags @> '{postgres}')
--    ->  Bitmap Index Scan on idx_article_tags  (actual rows=1200 ...)
```

GIN·GiST 는 대개 **Bitmap Index Scan → Bitmap Heap Scan** 조합으로 나타난다. GIN 이 후보 tid 를 모아 비트맵을 만들고, 힙에서 실제 행을 확인(Recheck)하는 구조다. 만약 `Seq Scan` 이 뜼다면 몇 가지를 점검한다. 첫째, 대상 행이 전체의 큰 비율이면 옵티마이저가 순차 스캔을 더 싸다고 판단한다(정상). 둘째, 통계가 낡았으면 `ANALYZE table` 로 갱신한다. 셋째, 질의의 연산자가 인덱스 연산자 클래스와 맞는지 확인한다. `@>` 인덱스는 `=` 질의를 못 탄다.

BRIN 의 효용을 판단하려면 상관성을 먼저 본다.

```sql
SELECT attname, correlation
  FROM pg_stats
 WHERE tablename = 'log' AND attname = 'created_at';
-- correlation 이 0.99 처럼 1 에 가까우면 BRIN 이 매우 효과적
```

실무 결정 순서를 요약하면 이렇다. 단일 컬럼 등치·범위·정렬이면 B-tree, 다중 값(배열·JSONB·전문검색) 포함 검색이면 GIN, 겹침·근접·범위 배타 제약이면 GiST, 물리 순서와 값 순서가 일치하는 초대용량이면 BRIN 을 고른다. 그 위에 조회 대상이 소수 부분집합이면 부분 인덱스로, 반환 컬럼이 고정적이면 INCLUDE 커버링으로 최적화를 얹는다. 마지막으로 인덱스는 공짜가 아니다. 모든 INSERT/UPDATE 가 인덱스를 갱신하므로, 쓰기가 많은 테이블에 인덱스를 남발하면 쓰기 지연과 WAL 증가로 되돌아온다. `pg_stat_user_indexes.idx_scan` 이 0 에 가까운 인덱스는 제거 후보다.

## 참고

- PostgreSQL Documentation: Index Types / GIN / GiST / BRIN (postgresql.org/docs)
- PostgreSQL Documentation: Index-Only Scans and Covering Indexes
- Bruce Momjian, "Explaining the Postgres Query Optimizer"
- Egor Rogov, "PostgreSQL 14 Internals" — Index Access Methods 장
