Notion 원본: https://www.notion.so/3d75a06fd6d38143bedffcf52cf998dd

# Apache Iceberg 테이블 포맷과 메타데이터 트리 및 스냅샷 격리

> 2026-09-10 신규 주제 · 확장 대상: Hadoop

## 학습 목표

- Hive 포맷의 구조적 결함을 Iceberg 메타데이터 4계층과 대응시켜 진단한다
- 스냅샷 원자적 커밋과 낙관적 동시성 제어를 설정값으로 제어한다
- 히든 파티셔닝·파티션 진화·CoW/MoR 를 워크로드에 맞춰 선택한다
- 압축·스냅샷 만료·고아 파일 정리 잡을 설계한다

## 1. Hive 테이블 포맷의 한계

Hive 포맷은 "테이블 = 디렉터리, 파티션 = 하위 디렉터리"라는 규약 위에 서 있다. `/warehouse/events/dt=2026-09-10/` 아래 파일은 전부 그 파티션의 데이터다. 테이블의 실체가 파일시스템 구조라는 점에서 결함이 파생된다.

첫째, **커밋에 원자성이 없다**. 파티션 덮어쓰기는 삭제와 쓰기라는 여러 연산이라 잡이 죽으면 테이블이 반쯤 지워진 채 남는다. HDFS 의 rename 은 원자적이지만 S3 에는 rename 이 없어 클라우드에서는 그 안전장치마저 사라진다.

둘째, **파일 나열 비용**이 플래닝을 지배한다. 파티션마다 LIST 를 호출하는데, LIST 는 페이지당 1,000개 키로 끊겨 파티션이 수만 개면 플래닝만 수 분 걸린다.

셋째, **스키마 변경이 위험하다**. 컬럼을 이름으로 볼지 위치로 볼지가 엔진마다 달라, 중간 컬럼을 지우면 이전 파일과 어긋나 값이 밀려 읽힌다.

넷째, **파티션 컬럼이 물리 구조로 노출된다**. `WHERE ts >= ...` 가 아니라 `WHERE dt = '2026-09-10'` 를 써야 프루닝이 걸린다. 컬럼을 빠뜨린 쿼리가 전체 스캔을 부르고, 전략 변경은 전체 재작성을 요구한다.

Iceberg 는 테이블 정의를 메타데이터로 옮겨 이를 푼다. 소속 파일을 메타데이터가 열거하고, 루트 포인터 교체 한 번이 커밋이 된다.

## 2. 메타데이터 트리

| 계층 | 파일 | 담는 정보 | 규모 |
|---|---|---|---|
| 1 | `vN.metadata.json` | 스키마·스펙 이력, 정렬 순서, 속성, 스냅샷 목록, 현재 스냅샷 ID | 커밋마다 1개 |
| 2 | manifest list (`snap-*.avro`) | 참조 매니페스트 목록, 매니페스트별 파티션 값 범위 | 스냅샷당 1개 |
| 3 | manifest file (`*.avro`) | 파일 엔트리, 파티션 값, 레코드 수, 크기, 컬럼 통계 | 스냅샷당 수~수천 개 |
| 4 | data / delete file | Parquet·ORC·Avro 데이터, position/equality delete | 수천~수억 개 |

핵심은 3계층의 **컬럼별 통계**다. 매니페스트 엔트리는 데이터 파일마다 컬럼 ID 별 `lower_bound` / `upper_bound` / `null_value_count` / `value_count` 를 갖는다. `WHERE user_id = 90210` 이 들어오면 엔진은 범위에 값이 없는 파일을 열지 않고 제외한다. 통계를 파일 밖으로 끌어올려 **열기 전에** 판단하는 점이 Parquet 푸터 통계와 다르다. 2계층도 파티션 값 범위를 가져 프루닝이 계단식으로 걸리므로, 매니페스트가 수천 개여도 읽는 것은 수 개다.

```sql
SELECT committed_at, snapshot_id, parent_id, operation, summary
FROM db.events.snapshots ORDER BY committed_at DESC LIMIT 10;

SELECT file_path, record_count, partition, lower_bounds, upper_bounds
FROM db.events.files LIMIT 20;
```

대가는 커밋마다 메타데이터가 쌓인다는 점이다. 잦은 커밋은 메타데이터와 작은 파일을 함께 늘리므로 `write.metadata.previous-versions-max` 와 `write.metadata.delete-after-commit.enabled=true` 가 필수다.

## 3. 스냅샷과 격리 수준

스냅샷은 특정 시점 테이블의 전체 파일 목록이다. 쓰기는 데이터 파일 → 매니페스트 → manifest list → metadata.json 을 만든 뒤 **카탈로그의 테이블 포인터를 교체**한다. 이 교체 한 번만 원자적이면 전체 쓰기가 원자적이고, 그전 파일은 아무도 참조하지 않아 실패해도 무해하다.

동시성 제어는 락이 아니라 **낙관적** 방식이다. 커밋 순간 "내가 읽은 스냅샷이 아직 현재인가"를 검사하고, 충돌이면 새 스냅샷 기준으로 검증을 다시 해 재시도한다. 메타데이터만 다시 만들면 되므로 재시도가 싸다.

| 속성 | 기본값 | 의미 |
|---|---|---|
| `commit.retry.num-retries` | 4 | 커밋 충돌 시 재시도 횟수 |
| `commit.retry.min-wait-ms` | 100 | 지수 백오프 시작 대기 |
| `commit.retry.max-wait-ms` | 60000 | 재시도 대기 상한 |
| `commit.retry.total-timeout-ms` | 1800000 | 재시도 전체 제한 시간 |
| `write.delete.isolation-level` | `serializable` | DELETE 충돌 검증 강도 |
| `write.update.isolation-level` | `serializable` | UPDATE 충돌 검증 강도 |
| `write.merge.isolation-level` | `serializable` | MERGE 충돌 검증 강도 |

`serializable` 은 내가 읽은 조건 범위에 **새 데이터 파일이 하나라도 추가됐으면** 커밋을 거부한다. `snapshot` 은 지우려던 파일이 이미 지워졌는지만 보므로, 동시에 들어온 새 행이 DELETE 조건에 걸려도 살아남는다. 성공률과 정확성의 교환이다.

```sql
ALTER TABLE db.events SET TBLPROPERTIES (
  'commit.retry.num-retries' = '10',
  'commit.retry.min-wait-ms' = '200',
  'commit.retry.total-timeout-ms' = '600000',
  'write.merge.isolation-level' = 'snapshot'
);
```

재시도만 늘리면 두 잡이 서로의 커밋을 밀어내 처리량이 떨어진다. 근본 해결은 쓰기 잡의 파티션 분리다.

## 4. 카탈로그

카탈로그는 "테이블 이름 → 현재 metadata.json 경로"를 관리하며 그 포인터를 **compare-and-swap 으로 교체**한다. 커밋 원자성은 전적으로 카탈로그의 몫이다.

| 카탈로그 | CAS 보장 주체 | 특징 |
|---|---|---|
| Hive Metastore | HMS 백엔드 RDB 트랜잭션 | Hadoop 생태계 호환, 운영 부담 |
| JDBC | RDB 의 조건부 UPDATE | 가장 단순, RDB 하나면 충분 |
| AWS Glue | Glue API 의 version 조건부 갱신 | 관리형, IAM 통합, 리전 종속 |
| Nessie | Nessie 서버 | Git 유사 브랜치·머지를 카탈로그에서 제공 |
| REST | REST 서버 구현체 | 스펙만 정의, 엔진은 HTTP 만 알면 됨 |
| Hadoop(파일시스템) | 원자적 rename | 객체 스토리지에는 비권장 |

Spark 로 쓰고 Trino 로 읽고 Flink 로 스트리밍한다면 세 엔진이 **같은 카탈로그를 같은 이름 공간으로** 봐야 한다. 카탈로그가 갈리면 현재 스냅샷이 달라져 원자성이 깨진다. REST 카탈로그는 엔진별 클라이언트 부담을 없애지만 서버가 단일 장애점이 된다.

```
spark.sql.catalog.prod = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.prod.type = rest
spark.sql.catalog.prod.uri = https://catalog.internal:8181
spark.sql.catalog.prod.warehouse = s3://lake/warehouse
```

## 5. 히든 파티셔닝과 파티션 진화

Iceberg 는 파티션 값을 별도 컬럼이 아니라 원본 컬럼에 **변환(transform)** 을 적용한 결과로 정의한다.

| 변환 | 예시 | 용도 |
|---|---|---|
| `identity` | `identity(region)` | 카디널리티 낮은 범주형 |
| `year`/`month`/`day`/`hour` | `days(ts)` | 시간 기반 파티션 |
| `bucket[N]` | `bucket(16, user_id)` | 고카디널리티 균등 분산, 조인 최적화 |
| `truncate[W]` | `truncate(10, zipcode)` | 앞부분 기준 그룹핑 |
| `void` | `void(col)` | 진화 시 필드 무력화 |

```sql
CREATE TABLE prod.db.events (
  id BIGINT, user_id BIGINT, ts TIMESTAMP, region STRING, payload STRING
) USING iceberg
PARTITIONED BY (days(ts), bucket(16, user_id));

SELECT count(*) FROM prod.db.events
WHERE ts >= TIMESTAMP '2026-09-01 00:00:00'
  AND ts <  TIMESTAMP '2026-09-02 00:00:00';
```

엔진은 `ts` 술어를 `days(ts)` 변환에 통과시켜 파티션 값 범위를 계산하고 그 범위의 파일만 남긴다. 파티션 컬럼을 쓰지 않아도 프루닝이 걸리는 것은 "어떤 컬럼에 어떤 변환"이 메타데이터에 남기 때문이다.

**진화**는 스펙에 ID 를 부여해 관리한다. 스펙을 바꿔도 기존 데이터는 이전 스펙 ID 를 단 채 남고, 읽을 때 스펙별 프루닝 결과를 합친다.

```sql
ALTER TABLE prod.db.events REPLACE PARTITION FIELD days(ts) WITH hours(ts);
ALTER TABLE prod.db.events ADD PARTITION FIELD region;
```

대가는 읽기 복잡도다. 스펙이 섞이면 플래닝이 갈라지고 오래된 구간은 새 스펙의 이점을 못 누리므로, 스펙 변경과 함께 그 구간을 `rewrite_data_files` 로 정리해야 한다.

## 6. 행 수준 갱신

**Copy-on-Write(CoW)** 는 수정 행이 든 파일 전체를 다시 쓴다. 쓰기에 비용을 전부 치르는 대신 읽기는 평범한 Parquet 스캔이다. **Merge-on-Read(MoR)** 는 원본을 두고 삭제 정보만 delete 파일로 남긴다.

- **position delete**: "이 파일의 N 번째 행이 삭제됨"을 경로 + 행 위치로 기록. 대상 파일을 알 때 쓰이며 읽기 병합이 싼 편이다.
- **equality delete**: "`user_id = 42` 인 행이 삭제됨"을 값 조건으로 기록. 대상 파일을 몰라도 써서 Flink CDC 업서트에서 생기지만, 읽을 때 관련 파일 전부와 조인해야 해 비싸다.

엔진은 읽기 시 데이터 파일과 delete 를 병합해 삭제 행을 걸러낸다. delete 가 쌓일수록 읽기가 느려져 MoR 은 압축 잡이 필수다.

```sql
ALTER TABLE prod.db.events SET TBLPROPERTIES (
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'copy-on-write'
);
```

| 상황 | 권장 | 이유 |
|---|---|---|
| 일 1회 배치 갱신, 읽기 잦음 | CoW | 쓰기 비용을 한 번에 치르고 읽기를 깨끗이 유지 |
| 스트리밍 업서트/CDC | MoR | 건당 파일 재작성 비용을 감당 불가 |
| 소량 행을 넓은 파티션에서 삭제 | MoR | CoW 는 몇 행 때문에 거대 파일을 재작성 |
| 개인정보 삭제 후 완전 제거 | CoW 또는 MoR+압축 | 원본에 값이 남으면 안 됨 |
| 지연에 민감한 대화형 쿼리 | CoW | 병합 오버헤드 제거 |

MoR 은 공짜로 빨라지는 게 아니라 비용을 압축 잡으로 이연하는 선택이다.

## 7. 유지보수 작업

```sql
-- 작은 파일 압축 + 정렬. delete 파일도 함께 흡수된다
CALL prod.system.rewrite_data_files(
  table => 'db.events',
  strategy => 'sort',
  sort_order => 'ts DESC NULLS LAST',
  where => "ts >= TIMESTAMP '2026-09-01 00:00:00'",
  options => map('target-file-size-bytes','536870912','min-input-files','5')
);

-- 매니페스트가 잘게 쪼개져 플래닝이 느릴 때
CALL prod.system.rewrite_manifests('db.events');

-- 참조 없는 데이터 파일까지 실제로 삭제된다
CALL prod.system.expire_snapshots(
  table => 'db.events',
  older_than => TIMESTAMP '2026-09-03 00:00:00',
  retain_last => 10
);
```

`expire_snapshots` 는 **시간여행 보존 기간과 스토리지 비용의 정면 트레이드오프**다. 기준이 7일이면 그 이전으로는 롤백도 `AS OF` 조회도 불가능하고, 90일이면 교체된 파일이 모두 남는다. 기준은 사고 인지부터 롤백 결정까지의 최대 시간이며 `history.expire.max-snapshot-age-ms` 로 건다.

`remove_orphan_files` 는 가장 위험하다. **다른 쓰기 잡이 방금 만든 파일도 참조되지 않는 파일로 보이기** 때문이다. 보존 기간 3일을 줄이면 커밋 직전 파일을 지워 잡을 깨뜨린다.

```sql
CALL prod.system.remove_orphan_files(
  table => 'db.events',
  older_than => TIMESTAMP '2026-09-01 00:00:00',
  dry_run => true
);
```

`dry_run` 으로 후보를 확인하고 쓰기 잡이 없는 시간대에 실행한다.

## 8. 시간 여행과 브랜치/태그

```sql
SELECT * FROM prod.db.events TIMESTAMP AS OF '2026-09-09 00:00:00';
SELECT * FROM prod.db.events VERSION AS OF 3821550127947089009;
SELECT * FROM prod.db.events VERSION AS OF 'audit';

CALL prod.system.rollback_to_snapshot('db.events', 3821550127947089009);

ALTER TABLE prod.db.events CREATE TAG `month-end-2026-08` RETAIN 365 DAYS;
ALTER TABLE prod.db.events CREATE BRANCH `audit`;
```

롤백은 새 스냅샷으로 과거 상태를 가리키므로 롤백 자체도 이력에 남아 다시 되돌릴 수 있다. 태그는 스냅샷을 고정하고(월말 마감본 등), 브랜치는 독립적으로 커밋을 이어간다.

이를 이용한 것이 **WAP(write-audit-publish)** 다. 브랜치에 먼저 커밋하고 검증한 뒤 통과한 경우만 메인에 반영하므로, 소비자는 불량 데이터를 보지 않는다.

1. 적재 잡이 `etl-<runId>` 브랜치에 쓴다.
2. 검증 잡이 브랜치를 읽어 규칙을 확인한다 — 행 수 급감(전일 대비 임계치), 필수 컬럼 NULL 비율, 키 중복, 참조 무결성.
3. 통과하면 메인에 반영한다(`fast_forward` 또는 브랜치 병합; 엔진·버전마다 지원 범위가 다르다).
4. 실패하면 브랜치를 삭제하고 알린다. 메인은 손대지 않아 영향이 없다.

Spark 는 `spark.wap.branch` 로 세션 쓰기를 브랜치로 유도해 ETL 코드 변경 없이 WAP 을 얹는다. 브랜치가 참조하는 스냅샷은 만료에서 빠지므로 검증 브랜치는 짧게 쓰고 지운다.

## 9. 선택 기준과 비교

| 항목 | Iceberg | Delta Lake | Hudi |
|---|---|---|---|
| 메타데이터 | metadata.json → manifest list → manifest | JSON 트랜잭션 로그 + 체크포인트 | 타임라인 + 파일 그룹 |
| 파티셔닝 | 히든 파티셔닝, 스펙 진화 | 디렉터리(+클러스터링) | 디렉터리 |
| 갱신 전략 | CoW / MoR 선택 | 주로 CoW(삭제 벡터 보완) | CoW / MoR, 인덱스 업서트 |
| 원자성 주체 | 카탈로그의 CAS | 로그 파일 생성 | 타임라인 커밋 파일 |
| 성격 | 카탈로그 중립·멀티 엔진 | Spark 출발 후 확장 | 스트리밍 업서트·증분 |

엔진 지원은 빠르게 변하므로 조건부로 봐야 한다. 대체로 Spark 와 Flink 가 읽기·쓰기 모두 가장 성숙하고, Trino 는 읽기와 상당 범위의 DML·프로시저를 지원하며, 경량 엔진은 읽기가 먼저 들어온다. 도입 전 사용 버전 문서에서 MoR delete 읽기, 파티션 진화, 브랜치 지원을 확인한다.

운영 비용은 셋이다. **카탈로그 운영** — 죽으면 쓰기도 읽기도 멈춘다. JDBC 로 기존 RDB 를 재활용하는 것이 가장 싸고, 자체 REST 서버는 가용성·백업 책임을 스스로 지겠다는 뜻이다. **압축 잡 스케줄링** — `rewrite_data_files` 를 쓰기 잡과 겹치지 않게 배치해야 하며 잡 자체가 컴퓨트를 먹는다. **스냅샷 만료와 고아 파일 정리** — 빠뜨리면 스토리지가 누적되고 플래닝이 느려진다.

파티션 전략이 안정적이고 갱신이 없으며 엔진이 하나뿐이라면 Hive 포맷 + Parquet 로도 버틴다. Iceberg 도입의 실질적 이유는 (1) 여러 엔진이 같은 테이블을 보거나, (2) 행 수준 갱신·삭제가 필요하거나, (3) 파티션 전략을 재작성 없이 바꾸거나, (4) 잘못된 적재를 되돌려야 할 때다.

## 참고

- [Apache Iceberg Documentation](https://iceberg.apache.org/docs/latest/)
- [Iceberg Table Spec](https://iceberg.apache.org/spec/)
- [Table Configuration Properties](https://iceberg.apache.org/docs/latest/configuration/)
- [Partitioning](https://iceberg.apache.org/docs/latest/partitioning/)
- [Spark Procedures](https://iceberg.apache.org/docs/latest/spark-procedures/)
- [Spark Queries](https://iceberg.apache.org/docs/latest/spark-queries/)
- [Branching and Tagging](https://iceberg.apache.org/docs/latest/branching/)
- [Maintenance](https://iceberg.apache.org/docs/latest/maintenance/)
- [Iceberg REST Catalog API Spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)
