Notion 원본: https://www.notion.so/3605a06fd6d381e8bde2c2ba2eb62b98

# PostgreSQL pg_stat_statements와 auto_explain — 운영 중 쿼리의 실측 통계와 실행계획을 한 짝으로 추적

> 2026-05-14 신규 주제 · 확장 대상: DB

## 학습 목표

- `pg_stat_statements` 가 어떤 차원으로 쿼리를 *normalize* 하고 합산하는지 이해
- `auto_explain` 의 임계값 / 샘플링 옵션으로 *느린 쿼리만 골라* EXPLAIN 을 자동 캡처
- 두 extension 의 조합으로 *통계는 합산본*, *증거는 개별 plan* 의 양면을 얻는 워크플로
- 운영 중 부하 증가 / regression 의 원인 쿼리를 찾는 표준 절차

## 1. 운영에서 느린 쿼리를 잡는 두 갈래

production DB 의 느린 쿼리를 잡는 도구는 크게 둘로 나뉜다.

- *집계형*: 어떤 쿼리가 *전체 시간의 몇 %* 를 먹는지를 알려준다 → `pg_stat_statements`
- *증거형*: 특정 호출에서 실행계획이 *왜* 느렸는지 보여준다 → `EXPLAIN ANALYZE`, `auto_explain`

집계만 있으면 "이 쿼리가 95%다" 까지는 알아도 *왜* 느린지 모른다. EXPLAIN 만 있으면 한 건의 실행계획은 보여도 *전사적 영향* 을 모른다. 둘을 같이 켜고 합쳐 보는 게 표준 패턴이다.

## 2. pg_stat_statements 활성화

extension 은 shared library 로 사전 로드되어야 한다. `postgresql.conf`:

```
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.max = 10000
pg_stat_statements.track = top              # top | all | none
pg_stat_statements.track_utility = off
pg_stat_statements.save = on
```

`track=top` 은 client 가 직접 보낸 statement 만 집계. `all` 은 함수 안의 nested call 도 집계해 더 정확하지만 오버헤드 증가. PostgreSQL 16+ 부터 `track_planning = on` 옵션으로 planning time 도 분리 추적된다. 재시작 후:

```sql
CREATE EXTENSION pg_stat_statements;
```

## 3. 쿼리 normalization 의 규칙

`pg_stat_statements` 는 *parameter literal 을 placeholder 로 치환* 해서 동일 형태의 쿼리를 합산한다.

```sql
SELECT * FROM orders WHERE user_id = 123 AND status = 'PAID';
SELECT * FROM orders WHERE user_id = 456 AND status = 'PAID';
```

둘은 같은 `queryid` 로 합쳐져 `SELECT * FROM orders WHERE user_id = $1 AND status = $2` 형태로 표시된다. PostgreSQL 14 부터 `queryid` 는 `pg_stat_activity` · log line prefix · `pg_stat_statements` 가 *공통 식별자* 를 사용한다. 14 이전엔 일부 도구가 자체 hash 를 만들었다.

normalization 의 한계: `IN (1, 2, 3)` 과 `IN (1, 2, 3, 4)` 는 *원소 개수가 다르므로* 별도 query 로 잡힌다. `ANY($1)` 또는 `= ANY (VALUES ...)` 패턴으로 리팩토링하면 합쳐진다. ORM (Hibernate, Mybatis) 이 동적 IN 절을 만드는 경우 표가 폭발할 수 있다.

## 4. 핵심 컬럼 — 어떤 지표를 봐야 하는가

```sql
SELECT queryid,
       substr(query, 1, 60) AS q,
       calls,
       total_exec_time,
       mean_exec_time,
       max_exec_time,
       rows,
       shared_blks_hit,
       shared_blks_read,
       shared_blks_dirtied,
       wal_records,
       wal_bytes
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

| 컬럼 | 의미 |
|---|---|
| total_exec_time | 누적 실행 시간 (ms). "전체 부하의 몇 %" 를 가르는 기준 |
| mean_exec_time | calls 당 평균 시간 |
| max_exec_time | 단발 최악치. tail latency 추적 |
| shared_blks_hit / read | 버퍼 캐시 히트 vs 디스크 읽기. read 가 크면 cold cache |
| shared_blks_dirtied | 쿼리가 dirty 로 만든 페이지. UPDATE 위주 워크로드 식별 |
| wal_records / wal_bytes | 생성된 WAL 양. 복제 부하 추적 |

운영에서 *가장 먼저 봐야 할 정렬키* 는 `total_exec_time DESC`. "이 쿼리는 0.5ms 지만 1초에 1만 번 호출되어 전체 5초를 먹는다" 같은 잠복 부하가 여기서만 잡힌다.

## 5. reset 정책과 windowing

`pg_stat_statements` 는 통계가 *재시작 또는 명시적 reset* 까지 누적된다. 시계열을 추적하려면 정기적으로 스냅샷을 떠야 한다.

```sql
-- N 분마다 스냅샷
CREATE TABLE pss_snapshot AS
SELECT now() AS ts, * FROM pg_stat_statements;

-- 다음 스냅샷에서 차분
INSERT INTO pss_snapshot
SELECT now(), * FROM pg_stat_statements;

-- diff: 시간 t1 ~ t2 사이 호출량
SELECT a.queryid, a.query,
       b.calls - a.calls AS calls_delta,
       b.total_exec_time - a.total_exec_time AS time_delta
FROM pss_snapshot a JOIN pss_snapshot b USING (queryid)
WHERE a.ts = $t1 AND b.ts = $t2
ORDER BY time_delta DESC;
```

이 패턴이 `pganalyze`, `pg_observer`, `Datadog DBM` 등 상용 도구의 핵심 동작이다. 직접 수집할 때는 5~10분 간격이면 충분하다.

`pg_stat_statements_reset()` 은 모든 통계를 비운다 (PostgreSQL 12+ 는 특정 queryid 만 비우는 인자도 받는다). 배포 직후 *새 코드 영향만 보고 싶을 때* 호출.

## 6. auto_explain — 느린 호출의 실행계획 자동 캡처

`pg_stat_statements` 만으로는 "왜 이 쿼리가 느린지" 모른다. 같은 query text 라도 *bind value* 에 따라 다른 plan 이 선택되거나 (bind variable peeking), 통계가 변하면 plan 이 갈아끼워진다. `auto_explain` 은 *느린 쿼리만 골라* EXPLAIN 결과를 로그에 자동 저장한다.

```
shared_preload_libraries = 'pg_stat_statements,auto_explain'
auto_explain.log_min_duration = '500ms'      # 500ms 이상만
auto_explain.log_analyze = on                # EXPLAIN ANALYZE
auto_explain.log_buffers = on
auto_explain.log_wal = on
auto_explain.log_timing = on
auto_explain.log_triggers = on
auto_explain.log_format = json               # 도구 파싱에 유리
auto_explain.sample_rate = 0.1               # 10% 샘플링
```

`log_analyze=on` 은 *실행 통계도 함께* 수집하므로 EXPLAIN ANALYZE 와 동일한 비용을 갖는다. 짧은 쿼리에 적용하면 측정 자체가 부하가 되므로 `log_min_duration` 으로 임계값을 두는 게 핵심. 거기에 `sample_rate=0.1` 을 더하면 *느린 쿼리의 10%만* 캡처되어 로그 폭발을 막을 수 있다.

## 7. 두 도구를 합쳐서 보는 워크플로

표준 절차는 다음과 같다.

```sql
-- 1) 누가 부하의 95% 인가
WITH top AS (
    SELECT queryid, total_exec_time
    FROM pg_stat_statements
    ORDER BY total_exec_time DESC
    LIMIT 5
)
SELECT * FROM top;

-- 2) 그 쿼리의 평균/최악/호출수
SELECT calls, mean_exec_time, max_exec_time, stddev_exec_time
FROM pg_stat_statements WHERE queryid = $1;

-- 3) auto_explain 로그에서 같은 queryid 의 plan 추출
-- (log_line_prefix 에 %Q 를 넣으면 queryid 가 prefix 에 노출됨, PG14+)
```

PostgreSQL 14+ 의 `log_line_prefix = '%m [%p] %q queryid=%Q '` 설정으로 로그라인에 queryid 가 박힌다. ELK/Loki 로 수집하면 *"이 queryid 의 plan 들"* 을 한 번에 검색 가능. 이 조합이 *집계와 증거를 한 짝으로 묶는* 운영의 표준이다.

## 8. 회귀 (regression) 탐지 시나리오

운영에서 가장 흔한 시나리오: 어제 배포 이후 응답 지연이 늘었다.

```sql
SELECT a.queryid,
       substr(a.query, 1, 80) AS q,
       round(((b.total_exec_time - a.total_exec_time)
              / nullif(b.calls - a.calls, 0))::numeric, 2) AS mean_after,
       round((a.total_exec_time / nullif(a.calls, 0))::numeric, 2) AS mean_before,
       (b.calls - a.calls) AS calls_window
FROM pss_snapshot a JOIN pss_snapshot b USING (queryid)
WHERE a.ts = now() - interval '2 days'
  AND b.ts = now()
ORDER BY (mean_after - mean_before) DESC NULLS LAST
LIMIT 20;
```

이 차이 쿼리는 *배포 전후 평균 시간이 가장 늘어난 query* 를 노출한다. 빠르게 *후보군* 을 좁히고, 그 다음에 auto_explain 로그에서 *plan 이 바뀐 시점* 을 확인한다. 통계 자동 갱신(autovacuum analyze) 으로 plan 이 갈아끼워졌거나, 데이터 분포 변화로 *index scan → seq scan* 회귀가 흔한 원인이다.

## 9. 비용과 함정

`pg_stat_statements` 오버헤드는 일반적으로 1~2% 미만이다. 단 `pg_stat_statements.max` 가 작고 *쿼리 종류가 매우 많은* 경우 (마이크로서비스 + ORM) `LRU` 축출이 일어나며 대표성이 떨어진다. `track_activity_query_size` 도 함께 키워야 긴 쿼리가 잘리지 않는다.

`auto_explain.log_analyze=on` 은 매 실행을 측정하므로 1ms 미만 쿼리에 적용하면 측정 시간이 본문 시간을 넘는 경우가 있다. 반드시 `log_min_duration` 과 `sample_rate` 와 함께. JSON 출력은 사람 눈에는 어렵지만 *도구 파싱 (pev2, pganalyze)* 에 필수.

PgBouncer transaction pooling 환경에서는 *prepared statement* 의 이점이 사라져 `pg_stat_statements` 가 더 다양한 queryid 를 보게 된다. 14+ 의 prepared statement 가 보존되는 풀러(`PgBouncer 1.21+ statement pooling fix`, `pgcat`) 를 쓰면 동일 normalization 으로 합쳐진다.

`pg_stat_statements_info.dealloc` 컬럼은 LRU 축출 횟수다. 0 이 아니면 `max` 를 늘려야 한다.

## 10. 운영 체크리스트

배포 직전: `SELECT pg_stat_statements_reset();` 로 baseline 비움.
배포 직후 (T+15m, T+1h, T+6h): top 20 쿼리 스냅샷 비교.
yearly DB 업그레이드: PostgreSQL 메이저 버전마다 `pg_stat_statements` 컬럼이 추가된다. 추적 대시보드 SQL 도 함께 갱신.
지표 alert: `mean_exec_time` 의 percentile 변화가 +50% 면 PagerDuty 경고.
`pg_stat_kcache` extension 과 결합: CPU/IO usage 까지 추적해 *느린 원인* 을 더 좁힌다.
`pg_wait_sampling` 추가: 어떤 wait event 가 시간을 잡아먹는지까지 본다.

운영에서 *어디서 시간이 새는지* 는 결국 다음 다섯 질문으로 좁혀진다: 누가 시간을 가장 많이 먹는가, 그게 평균인가 tail 인가, 호출 수가 늘었는가 단가가 늘었는가, plan 이 바뀌었는가, lock/IO 가 원인인가. pg_stat_statements + auto_explain + pg_wait_sampling 의 세 짝이 그 답을 가장 빠르게 준다.

## 참고

- PostgreSQL 공식 문서 — pg_stat_statements: https://www.postgresql.org/docs/current/pgstatstatements.html
- PostgreSQL 공식 문서 — auto_explain: https://www.postgresql.org/docs/current/auto-explain.html
- pganalyze 블로그 — *Top 6 PostgreSQL Performance Tools*
- Lukas Fittl, *Identifying slow PostgreSQL queries*: https://pganalyze.com/blog
- Robert Treat, *PostgreSQL Diagnostic Toolset* (PGCon)
- PG14 release notes — queryid 통합: https://www.postgresql.org/docs/release/14.0/
