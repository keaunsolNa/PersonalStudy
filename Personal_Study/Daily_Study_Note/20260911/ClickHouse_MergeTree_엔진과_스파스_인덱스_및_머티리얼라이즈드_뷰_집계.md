Notion 원본: https://app.notion.com/p/3d85a06fd6d3811cb3ead960b2900164?pvs=204

# ClickHouse MergeTree 엔진과 스파스 인덱스 및 머티리얼라이즈드 뷰 집계

> 2026-09-11 신규 주제 · 확장 대상: 컴럼 지향 저장 / OLAP

## 학습 목표

- MergeTree 파트 구조와 컴럼별 압축 코덱을 골라 저장 비용을 설계한다
- 스파스 primary index 와 skipping index 로 스캔 granule 수를 줄인다
- `AggregatingMergeTree` + MV 로 증분 집계를 구성하고 과거 데이터를 백필한다
- MySQL·Elasticsearch 대비 한계를 따져 ClickHouse 적용 여부를 판단한다

## 1. 컴럼 지향 저장의 물리 구조

테이블 디렉터리에는 `202609_1_9_2`(파티션ID_최소블록_최대블록_병합레벨) 형태의 **파트 디렉터리**가 여럿 있고, 그 안에 컴럼마다 `.bin`(압축 데이터)과 `.mrk3`(마크)가 한 쌍씩, 그리고 `primary.idx`·`checksums.txt` 가 들어있다. 테이블 하나를 `.ibd` 한 파일에 담는 InnoDB 와 대비된다. `.bin` 은 **압축 블록**(기본 64KiB~1MiB) 단위로 압축되고 마크가 "granule N 은 몇 번째 블록의 해제 후 몇 바이트 위치" 를 알려주므로 필요한 블록만 푸다. `sum(amount)` 가 나머지 100개 컴럼 파일을 건드리지 않는 이유다.

압축은 컴럼 단위다. 기본 `LZ4` 는 2~4배에 해제가 수 GB/s 로 빠르고 `ZSTD(3)` 은 압축률이 1.3~2배 좋아지는 대신 해제가 2~4배 느리다. **변환 코덱 → 범용 압축** 순으로 체이닝하면 시계열에서 차이가 커진다.

```sql
CREATE TABLE metrics
(
    ts          DateTime  CODEC(DoubleDelta, ZSTD(1)),  -- 단조 증가 → 10~50배
    host        LowCardinality(String),
    metric      LowCardinality(String),
    value       Float64   CODEC(Gorilla, ZSTD(1)),      -- 완만한 실수 게이지
    status_code UInt16    CODEC(T64, LZ4)               -- 좁은 정수 범위
)
ENGINE = MergeTree PARTITION BY toYYYYMM(ts)
ORDER BY (metric, host, ts) SETTINGS index_granularity = 8192;
```

`Delta`/`DoubleDelta` 는 차분, `Gorilla` 는 XOR, `T64` 는 비트 전치이며 실측은 `system.parts_columns` 의 원본/압축 바이트 비로 본다. 반대로 OLTP 단건 조회에는 최악이다. `WHERE id = 12345` 로 전 컴럼을 읽으려면 컴럼 수만큼 블록을 풀고 최소 granule 하나(8192행)를 읽어야 해서, 16KB 페이지 하나로 끝나는 InnoDB 의 0.1ms 대와 달리 수 ms~수십 ms 대다.

## 2. MergeTree 의 스파스 primary index

`ORDER BY` 키가 곳 primary key 다(`PRIMARY KEY` 를 따로 주면 그 접두사여야 한다). 파트 안에서 이 키로 정렬되고 `primary.idx` 에는 **granule 하나당 한 개**, 첫 행의 키 값만 남는다. `index_granularity = 8192` 면 10억 행의 마크가 약 12만 개, 수 MB 라 인덱스가 **항상 메모리에 상주**한다. InnoDB 클러스터드 인덱스는 리프에 모든 행이 있는 dense 구조라 "키 → 정확한 행" 이 되지만 인덱스가 데이터와 함께 커진다. 스파스 인덱스는 "키 → 값이 있을 수 있는 granule 범위" 까지만 알려주고 그 구간을 통째로 읽어 `WHERE` 를 다시 평가한다.

따라서 첫째, **primary key 는 유니크 제약이 아니다**. 같은 키 행을 몇 개든 넣어도 오류가 없어 MySQL 의 PK=유일성 감각을 옆기면 중복에 당한다. 둘째, **read amplification 이 granule 단위로 바닥을 친다**. 맞는 행이 하나여도 8192행을 읽는다.

```sql
EXPLAIN indexes = 1
SELECT count() FROM metrics WHERE metric = 'cpu.load' AND host = 'web-01';
-- PrimaryKey / Keys: metric, host / Parts: 3/9
-- Granules: 47/1220     <-- 1220 중 47개만 읽음
```

`Granules` 감소폭이 사실상 모든 인덱스 튜닝의 판정 기준이다. `index_granularity` 를 4096 으로 낮추면 프루닝은 정밀해지나 마크가 2배가 되어, 보통 기본값과 `index_granularity_bytes`(기본 10MB)의 적응형 동작에 맡긴다.

## 3. 파트 병합과 Too many parts

INSERT 한 번이 파트 하나를 만들고, 백그라운드 스레드가 같은 파티션의 작은 파트를 정렬 병합한 뒤 원본을 `old_parts_lifetime`(기본 480초) 후 지운다. LSM-Tree 와 닮았지만 memtable·WAL 레벨 구조가 없고, 무엇보다 **읽기 시점에 파트 간 중복 해소를 하지 않는다**. `ReplacingMergeTree` 조차 `FINAL` 없이는 중복을 그대로 보여주는 이유다.

```sql
SELECT partition, count() AS parts, formatReadableSize(sum(bytes_on_disk))
FROM system.parts WHERE table = 'metrics' AND active GROUP BY partition;
SELECT table, elapsed, progress, num_parts FROM system.merges;
```

`Too many parts (300)` 는 거의 항상 **작은 INSERT 남발**이 원인이다. 파티션당 활성 파트가 `parts_to_delay_insert`(기본 150)를 넘으면 INSERT 가 지연되고 `parts_to_throw_insert`(기본 300)에서 예외가 난다. 배치당 1만~10만 행, 초당 1회 이하로 묶거나 서버 측 버퍼링을 켜다.

```sql
SET async_insert = 1,
    wait_for_async_insert = 1,              -- 0 이면 처리량↑, 유실 위험
    async_insert_max_data_size = 10000000,  -- 10MB 또는
    async_insert_busy_timeout_ms = 1000;    -- 1초마다 플러시

ALTER TABLE metrics MODIFY TTL ts + INTERVAL 7 DAY TO VOLUME 'cold',
                               ts + INTERVAL 90 DAY DELETE;
```

TTL 은 파트 단위 이동·삭제를 선언하지만 **병합 시점에 평가**되므로 90일에 즉시 사라지지 않는다. 바로 정리하려면 `MATERIALIZE TTL` 을 돌린다.

## 4. 파티셔닝과 프라이머리 키 설계

파티션은 수명 관리, 정렬 키는 granule 프루닝이 역할이다. 기본값 `PARTITION BY toYYYYMM(ts)` 로 파티션을 수십~수백 개로 유지한다. 일 단위로 쪠개면 2년에 730개가 생기고 병합이 파티션 경계를 넘지 않아 파트가 곱절로 늘어 `Too many parts` 를 부른다. 총 1000개를 넘으면 설계를 의심한다. `user_id % 100` 해시 파티셔닝은 MySQL 감각으로는 자연스럽지만 여기선 안티패턴이다.

**파티션 프루닝**은 디렉터리를 통째로 건너뛰고 **granule 프루닝**은 선택된 파트 안에서 마크 범위를 좁힌다. 디버그 로그의 `Selected 3/9 parts by partition key` 와 `47/1220 marks by primary key` 가 각각이며, 둘은 독립이라 파티션만 잘려도 정렬 키가 나쁜면 파티션 전체를 스캔한다. `ORDER BY` 는 **카디널리티 오름차순**이 출발점이다. 저카디널리티가 앞이면 같은 값이 연속돼 압축률이 오른다. 반례는 **자주 쓰는 필터가 카디널리티보다 우선**이라는 점으로, 모든 쿼리가 `WHERE user_id = ?` 인데 카디널리티 1000만이라고 뒤로 미루면 영영 빨라지지 않는다. 키는 3~5개에서 끕고 다른 접근 패턴은 projection 으로 만든다.

```sql
ALTER TABLE metrics ADD PROJECTION p_by_host
    (SELECT host, metric, ts, value ORDER BY (host, ts));
ALTER TABLE metrics MATERIALIZE PROJECTION p_by_host;
```

## 5. Data skipping index

행 위치를 가리키는 InnoDB 세컨다리 인덱스와 달리 **granule N 개 묶음마다 요약**을 저장해 "이 묶음엔 확실히 없다" 만 판정한다. `GRANULARITY 4` 는 granule 4개(32768행)당 요약 1개이며, 작을수록 정밀하지만 인덱스와 병합 비용이 커진다.

| 타입 | 저장하는 것 | 적합 | 부적합 |
| --- | --- | --- | --- |
| `minmax` | 블록 최소·최대 | 정렬 키와 상관된 수치·날짜 | 무작위로 흙어진 값 |
| `set(N)` | 고유값 N개 | 상태코드 등 저카디널리티 | 고유값 N 초과 시 무력화 |
| `bloom_filter(p)` | 값 블룸필터 | `user_id = ?`, `IN`, `has()` | 범위 조건(`>`) |
| `ngrambf_v1` | n-gram 필터 | `LIKE '%abc%'` | 매우 짧은 검색어 |
| `tokenbf_v1` | 토큰 필터 | 로그의 `hasToken()` | 토큰 중간 문자열 |

```sql
ALTER TABLE events ADD INDEX idx_user user_id TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_msg message TYPE tokenbf_v1(16384, 3, 0) GRANULARITY 2;
ALTER TABLE events MATERIALIZE INDEX idx_user;  -- mutation 이라 파트 재작성 비용

EXPLAIN indexes = 1 SELECT count() FROM events WHERE user_id = 918273;
-- Skip / Name: idx_user / Granules: 12/3800   <-- 효과 있음
```

오용은 둘이다. 값이 무작위로 흙어져 **어느 블록에나 있는** 컴럼에 블룸필터를 걸면 스킵되는 granule 이 0 에 가깝고 필터 계산 CPU 와 디스크만 낭비한다. 정렬 키 선행 컴럼에 또 거는 것도 primary index 와 중복이다. `Granules: 3790/3800` 처럼 줄지 않으면 `DROP INDEX` 대상이다.

## 6. 특수 MergeTree 계열

변종들은 **병합 시점에 같은 정렬 키의 행을 어떻게 접을지**만 다르고, 공통 함정은 접기가 **최종적이지 즉시가 아니라는 점**이다. `ReplacingMergeTree(version, is_deleted)` 는 버전이 큰 행 하나만 남기고 그 행의 `is_deleted = 1` 이면 행을 제거해 CDC 삭제를 전파한다. 병합 전 조회는 중복을 그대로 반환하므로 `FINAL` 이 필요한데 파트 전체를 정렬 병합해 지연이 몇 배로 뛰다. `argMax` 로 직접 접는 쪽이 대개 싸다.

```sql
CREATE TABLE users_current (user_id UInt64, name String,
                            updated_at DateTime, is_deleted UInt8 DEFAULT 0)
ENGINE = ReplacingMergeTree(updated_at, is_deleted) ORDER BY user_id;

SELECT user_id, argMax(name, updated_at) AS name
FROM users_current WHERE user_id = 1 GROUP BY user_id
HAVING argMax(is_deleted, updated_at) = 0;
```

`SummingMergeTree` 는 수치 컴럼을 합산해 카운터 롤업에 편하지만 평균·유니크는 못 만든다. `AggregatingMergeTree` 는 집계 상태를 병합해 범용적이다. `CollapsingMergeTree` 는 `sign`(+1/-1)으로 상쇄하고 `VersionedCollapsingMergeTree` 는 버전을 더해 **순서 없이 도착해도** 상쇄되지만, 항상 `sum(value * sign)` 으로 써야 하고 `-1` 행의 나머지 컴럼이 원본과 같아야 한다.

## 7. 머티리얼라이즈드 뷰 — 결과 캐시가 아닌 INSERT 트리거

MySQL·Oracle 경험이 오히려 방해가 되는 지점이다. ClickHouse MV 는 **결과 캐시가 아니라 소스 테이블의 INSERT 트리거**다. 소스에 블록이 들어올 때 그 블록에만 `SELECT` 이 돌아 대상 테이블로 INSERT 되고 소스의 기존 데이터는 **전혀 읽지 않는다**. 대상은 `TO` 로 분리하는 게 표준이며 내부 숨은 테이블(`.inner.*`)은 백필·스키마 변경이 어렵다.

```sql
CREATE TABLE events_daily
(
    day        Date,
    metric     LowCardinality(String),
    cnt        SimpleAggregateFunction(sum, UInt64),
    uniq_users AggregateFunction(uniq, UInt64),
    latency    AggregateFunction(quantiles(0.5, 0.95, 0.99), Float64)
)
ENGINE = AggregatingMergeTree PARTITION BY toYYYYMM(day) ORDER BY (metric, day);

CREATE MATERIALIZED VIEW mv_events_daily TO events_daily AS
SELECT toDate(ts) AS day, metric, count() AS cnt,
       uniqState(user_id) AS uniq_users,
       quantilesState(0.5, 0.95, 0.99)(latency_ms) AS latency
FROM events GROUP BY day, metric;

SELECT metric, sum(cnt), uniqMerge(uniq_users) AS dau,
       quantilesMerge(0.5, 0.95, 0.99)(latency)
FROM events_daily WHERE day >= '2026-09-01' GROUP BY metric;
```

핵심은 `-State`/`-Merge` 짝이다. `uniqState` 는 숫자가 아니라 HyperLogLog 스케치를 만들어 병합에서 합쳐지고 조회는 반드시 `-Merge` 로 닫는다. 중간 상태가 결과와 같은 `sum`·`max` 는 직렬화 없는 `SimpleAggregateFunction` 이 가볍다. **백필**은 별도 작업이며, 대상 테이블에 직접 INSERT 하면 MV 가 재트리거되지 않아 안전하다.

```sql
INSERT INTO events_daily
SELECT toDate(ts), metric, count(), uniqState(user_id),
       quantilesState(0.5, 0.95, 0.99)(latency_ms)
FROM events WHERE ts < '2026-09-11'   -- MV 생성 이전만, 월 단위로 분할
GROUP BY toDate(ts), metric SETTINGS max_memory_usage = 20000000000;
```

23.x 의 **Refreshable MV**(`REFRESH EVERY 10 MINUTE`)는 이름만 같을 뿐 Oracle 의 주기 갱신 MV 에 가까워, 주기마다 전체 `SELECT` 을 돌려 대상을 원자적으로 교체한다. 증분 MV 는 지연이 거의 0 이나 조인·윈도우에 약하고, Refreshable 은 임의 쿼리를 지원하는 대신 주기만큼 낡고 매번 풀스캔 비용을 낸다.

## 8. 운영·통합 관점

Kafka 적재의 표준은 `Kafka` 엔진 테이블(한 번 읽으면 사라지는 큐 소비자) → MV → MergeTree 저장 테이블 3단 구성이다.

```sql
CREATE TABLE kafka_events (ts DateTime, user_id UInt64, metric String, latency_ms Float64)
ENGINE = Kafka SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'events',
    kafka_group_name = 'ch_events', kafka_format = 'JSONEachRow', kafka_num_consumers = 4;

CREATE MATERIALIZED VIEW mv_kafka_to_events TO events AS
SELECT ts, user_id, metric, latency_ms FROM kafka_events;
```

MySQL 은 `MySQL` 테이블 함수로 초기 스냅샷을 당기고 이후 변경은 Debezium → Kafka → `ReplacingMergeTree` CDC 로 받는다. Spring 에서 `clickhouse-jdbc` 를 쓸 때 핵심은 **배치**다. `JdbcTemplate.batchUpdate` 로 1만~10만 행씩 묶지 않고 행 단위로 보내면 3절의 `Too many parts` 로 직행한다. 대량 적재는 `FORMAT JSONEachRow`/`Parquet` 스트리밍이 빠르고, 파싱 관용도는 `input_format_skip_unknown_fields` 등 `input_format_*` 로 조절한다. 튜닝 근거는 `system.query_log` 의 `query_duration_ms`·`read_rows`·`memory_usage` 에서 뽑는다.

```sql
CREATE SETTINGS PROFILE api_readonly SETTINGS
    max_memory_usage = 4000000000, max_execution_time = 30, max_threads = 4,
    max_bytes_before_external_group_by = 2000000000, readonly = 1;
```

쿼리 하나가 `max_threads`(기본 = 코어 수)만큼 코어를 다 쓰고 `GROUP BY` 해시 테이블을 메모리에 올리는 **처리량 기계**라, 동시 쿼리 100개를 견디는 MySQL 감각으로 용량을 잡으면 안 된다. `max_concurrent_queries`(기본 100)와 쿼리당 `max_memory_usage`(기본 10GB)로 방어선을 치고 대시보드용과 배치용 프로파일을 분리한다.

## 9. 언제 쓰지 말아야 하는가

`ALTER TABLE ... UPDATE col = x WHERE ...` 는 **mutation** 으로 비동기 실행되며 한 행만 바뚈도 **해당 파트 전체를 재작성**한다. 수십 GB 파트의 한 줄 수정이 수십 GB I/O 를 부른다. 경량 `DELETE FROM` 은 마스크만 세워 싸지만 초당 수십 건씩 부를 물건은 아니다. 갱신이 잦으면 append-only 로 넣고 조회에서 접는 설계로 바꾸다.

```sql
SELECT table, mutation_id, command, parts_to_do, latest_fail_reason
FROM system.mutations WHERE NOT is_done;
KILL MUTATION WHERE mutation_id = 'mutation_42.txt';
```

트랜잭션도 없다. 다중 테이블 원자성·롤백이 없고 외래키·유니크 제약도 없어 정합성은 적재 파이프라인의 멱등성으로 확보한다. 조인은 오른쪽 테이블을 메모리 해시로 올리므로 **작은 쪽을 오른쪽에** 롐야 하고 큰 테이블끼리는 메모리 초과로 이어진다. 정규화 스키마를 그대로 옮기지 말고 `Dictionary` + `dictGet` 룩업으로 바꾼다.

| 요구사항 | ClickHouse | PostgreSQL / MySQL | Elasticsearch |
| --- | --- | --- | --- |
| 수억 행 스캔·집계 | 최적 | 부적합 | 메모리 부담 |
| 단건 PK 조회 | 불리(granule 증폭) | 최적(B+Tree) | 가능 |
| 잦은 UPDATE·트랜잭션 | 불가에 가까움 | 최적(MVCC·ACID) | 부적합 |
| 전문 검색·랭킹 | 제한적 | 제한적 | 최적 |
| 압축률 | 10~30배 | 2~3배 | 역색인 오버헤드 |

정리하면 **쓰기가 append-only 이고 읽기가 광범위한 스캔·집계라면 ClickHouse**, 단건 갱신과 정합성이 중심이면 RDBMS, 비정형 텍스트 검색이면 Elasticsearch 다. 실무에서는 MySQL 을 시스템 오브 레코드로 두고 CDC 로 ClickHouse 에 분석 사본을 흘려보내는 구성이 가장 흔하다.

## 참고

- ClickHouse Docs — MergeTree: https://clickhouse.com/docs/en/engines/table-engines/mergetree-family/mergetree
- ClickHouse Docs — Sparse Primary Indexes: https://clickhouse.com/docs/en/optimize/sparse-primary-indexes
- ClickHouse Docs — Data Skipping Indexes: https://clickhouse.com/docs/en/optimize/skipping-indexes
- ClickHouse Docs — Materialized View: https://clickhouse.com/docs/en/materialized-view
- ClickHouse Docs — Compression Codecs: https://clickhouse.com/docs/en/sql-reference/statements/create/table
- ClickHouse Blog — Asynchronous Inserts: https://clickhouse.com/blog/asynchronous-data-inserts-in-clickhouse
