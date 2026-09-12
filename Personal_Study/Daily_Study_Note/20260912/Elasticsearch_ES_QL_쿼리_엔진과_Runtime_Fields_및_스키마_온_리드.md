Notion 원본: https://app.notion.com/p/3d95a06fd6d381b9b720d6ab444d4579?pvs=204

# Elasticsearch ES|QL 쿼리 엔진과 Runtime Fields 및 스키마 온 리드

> 2026-09-12 신규 주제 · 확장 대상: Elasticsearch BM25·집계 / 인덱스 매핑 설계

## 학습 목표

- Query DSL 의 중첩 JSON 집계가 파이프라인 분석에 불리한 구조적 이유를 규명한다
- ES|QL 의 파이프 문법과 컴퓨트 엔진 실행 단계를 분석한다
- Runtime Fields 로 인덱스 재구축 없이 필드를 추가하고 비용 경계를 진단한다
- 색인 시점 매핑과 조회 시점 계산의 트레이드오프를 판단해 설계 기준을 세운다

## 1. Query DSL 의 구조적 한계

Elasticsearch 의 집계는 JSON 트리다. 필터 → 그룹 → 메트릭 → 정렬 → 파생 계산을 표현하면 중첩이 급격히 깊어진다.

```json
{
  "size": 0,
  "query": {
    "bool": {
      "filter": [
        { "range": { "@timestamp": { "gte": "now-7d" } } },
        { "term": { "service": "payroll-api" } }
      ]
    }
  },
  "aggs": {
    "per_endpoint": {
      "terms": { "field": "http.route", "size": 20, "order": { "p95": "desc" } },
      "aggs": {
        "p95": { "percentiles": { "field": "duration_ms", "percents": [95] } },
        "error_rate": {
          "bucket_script": {
            "buckets_path": { "errors": "errors>_count", "total": "_count" },
            "script": "params.errors / params.total"
          }
        },
        "errors": { "filter": { "range": { "http.status": { "gte": 500 } } } }
      }
    }
  }
}
```

문제는 세 층이다.

**표현력 대비 가독성** — `bucket_script` 의 `buckets_path` 문법은 형제 집계를 상대 경로로 참조하는 별도의 미니 언어다. 집계가 3단 이상 중첩되면 경로 문자열이 복잡해지고, 오타는 런타임 에러로만 드러난다.

**파이프라인 집계의 제약** — `bucket_script`, `bucket_selector`, `derivative` 같은 파이프라인 집계는 부모 집계가 **모두 완료된 뒤** 버킷 결과 위에서 동작한다. 따라서 중간 결과로 필터링하고 다시 그룹하는 다단계 변환이 어렵다. "엔드포인트별 p95 를 구하고, p95 가 1초 넘는 것만 골라, 그것들을 다시 시간대별로 쪼개라"는 요구는 DSL 하나로 표현하기 어렵고 클라이언트에서 두 번 호출하게 된다.

**조인 부재** — 두 인덱스를 결합하는 수단이 사실상 없어 애플리케이션에서 합친다.

ES|QL(Elasticsearch Query Language)은 이 문제를 **파이프 기반 데이터 흐름 언어**로 다시 설계했다. 위 쿼리는 이렇게 된다.

```sql
FROM logs-payroll-*
| WHERE @timestamp >= NOW() - 7 days AND service == "payroll-api"
| EVAL is_error = CASE(http.status >= 500, 1, 0)
| STATS p95 = PERCENTILE(duration_ms, 95),
        total = COUNT(*),
        errors = SUM(is_error)
    BY http.route
| EVAL error_rate = errors::DOUBLE / total
| WHERE p95 > 1000
| SORT p95 DESC
| LIMIT 20
```

각 단계가 이전 단계의 출력을 입력으로 받는 단방향 흐름이고, `STATS` 이후에 다시 `WHERE` 로 필터링하는 다단계 변환이 자연스럽다. SQL 의 `HAVING` 에 해당하는 별도 절이 필요하지 않다 — 파이프의 위치가 의미를 결정한다.

## 2. 컴퓨트 엔진 실행 모델

ES|QL 은 기존 집계 프레임워크를 쓰지 않고 별도 실행 엔진(내부적으로 "컴퓨트 엔진")을 쓴다. 실행은 네 단계로 나뉜다.

**파싱과 논리 계획** — 쿼리 문자열을 논리 계획 트리로 만든다. 이 단계에서 필드 이름 해석과 타입 검증이 일어난다. 잘못된 필드나 타입 불일치는 실행 전에 에러로 반환된다 — Query DSL 의 스크립트가 실행 중에 터지는 것과 대비된다.

**최적화** — 술어 밀어내기(predicate pushdown)가 핵심이다. `WHERE @timestamp >= ...` 는 Lucene 쿼리로 변환되어 세그먼트 스캔 자체를 줄이고, `LIMIT` 은 가능한 한 아래로 내려간다. `FROM logs-*` 의 인덱스 패턴은 데이터 스트림의 시간 범위 메타데이터와 대조해 관련 없는 인덱스를 아예 건드리지 않는다(index-level pruning). 이 최적화 덕분에 30일 데이터 스트림에 7일 필터를 걸면 대상 인덱스 수가 줄어든다.

**물리 계획과 분산** — 논리 계획을 노드별 물리 계획으로 쪼갠다. 데이터 노드에서 실행되는 부분과 코디네이터에서 실행되는 부분이 분리되고, `STATS` 는 부분 집계(데이터 노드) → 병합(코디네이터) 2단으로 나뉜다.

**벡터화 실행** — 실행 단위가 문서 하나가 아니라 **블록**이다. 컬럼 단위로 값을 묶어 한 번에 처리하므로 JIT 가 벡터화하기 쉽고 분기 예측이 안정적이다. Query DSL 의 `script` 필드가 문서마다 스크립트 엔진을 호출하는 것과 성능 특성이 근본적으로 다르다.

| 항목 | Query DSL 집계 | ES\|QL |
|---|---|---|
| 표현 형태 | 중첩 JSON | 파이프 문법 |
| 다단계 변환 | 파이프라인 집계로 제한적 | `STATS` 후 `WHERE`/`EVAL` 자유 |
| 타입 검증 시점 | 실행 중(스크립트) | 계획 수립 시 |
| 실행 단위 | 문서 단위 + 버킷 트리 | 컬럼 블록(벡터화) |
| 조인 | 없음 | `LOOKUP JOIN` (제한적) |
| 반환 형태 | 중첩 집계 트리 JSON | 컬럼/행 테이블 |
| 결과 행 상한 | 집계 버킷 수 제한 | 기본 1만 행 상한 |

반환 형태가 테이블이라는 점은 클라이언트 코드에 직접적인 영향을 준다. 중첩 집계 응답을 파싱하는 재귀 코드가 필요 없고, 그대로 표에 바인딩하거나 CSV 로 내보낼 수 있다.

```json
POST /_query?format=json
{
  "query": "FROM logs-payroll-* | STATS c = COUNT(*) BY http.route | SORT c DESC | LIMIT 5"
}
```

```json
{
  "columns": [
    { "name": "c", "type": "long" },
    { "name": "http.route", "type": "keyword" }
  ],
  "values": [
    [15234, "/api/payroll/calculate"],
    [8821, "/api/employees"],
    [412, "/api/payroll/close"]
  ]
}
```

## 3. 실무 쿼리 패턴

**시간 버킷 집계** — `DATE_TRUNC` 과 `BUCKET` 을 쓴다.

```sql
FROM logs-payroll-*
| WHERE @timestamp >= NOW() - 24 hours
| EVAL hour = DATE_TRUNC(1 hour, @timestamp)
| STATS reqs = COUNT(*), p50 = PERCENTILE(duration_ms, 50),
        p99 = PERCENTILE(duration_ms, 99)
    BY hour, service
| SORT hour ASC
```

`BUCKET` 은 목표 버킷 개수를 주면 간격을 자동 선택한다 — 대시보드 시간 범위가 가변일 때 유용하다.

```sql
FROM logs-payroll-*
| WHERE @timestamp >= ?start AND @timestamp <= ?end
| STATS reqs = COUNT(*) BY slot = BUCKET(@timestamp, 50, ?start, ?end)
```

`?start` 는 파라미터 바인딩이다. 문자열 보간 대신 파라미터를 쓰면 쿼리 문법 주입을 막고 계획 캐시 재사용 가능성이 올라간다.

```json
POST /_query
{
  "query": "FROM logs-* | WHERE service == ?svc | STATS COUNT(*)",
  "params": [{ "svc": "payroll-api" }]
}
```

**문자열 파싱** — `DISSECT` 와 `GROK` 으로 비정형 메시지를 컬럼으로 쪼갠다. 색인 시점 ingest pipeline 없이 조회 시점에 처리한다.

```sql
FROM logs-legacy-app
| WHERE message LIKE "*PayrollBatch*"
| DISSECT message "%{ts} %{level} [%{thread}] %{logger} - %{msg}"
| WHERE level == "ERROR"
| STATS c = COUNT(*) BY logger
| SORT c DESC
| LIMIT 10
```

`DISSECT` 는 구분자 기반이라 정규식보다 훨씬 빠르다. 구분자가 불규칙할 때만 `GROK` 을 쓴다.

**룩업과 엔리치** — `ENRICH` 는 미리 정의된 enrich 정책으로 참조 데이터를 붙인다.

```sql
FROM logs-payroll-*
| WHERE @timestamp >= NOW() - 1 day
| ENRICH company-policy ON company_code WITH company_name, tier
| STATS reqs = COUNT(*), avg_ms = AVG(duration_ms) BY tier, company_name
| SORT reqs DESC
```

enrich 정책은 사전에 실행해 조회용 인덱스를 만들어 두는 구조다. 정책 데이터가 바뀌면 `_execute` 로 재빌드해야 하므로, 자주 변하는 데이터에는 맞지 않는다. 회사 코드 → 회사명처럼 변화가 드문 마스터 데이터가 적합하다.

**카디널리티와 상위 N** — `COUNT_DISTINCT` 는 HyperLogLog++ 기반 근사값이다. 정확도가 필요하면 `STATS BY` 로 그룹핑한 행 수를 세되 그룹 수가 크면 비용이 급증한다.

```sql
FROM logs-payroll-*
| WHERE @timestamp >= NOW() - 7 days
| STATS unique_users = COUNT_DISTINCT(user_id),
        unique_companies = COUNT_DISTINCT(company_code)
    BY DATE_TRUNC(1 day, @timestamp)
```

## 4. Runtime Fields — 조회 시점 계산

Runtime field 는 색인되지 않고 조회 시점에 계산되는 필드다. 매핑에 스크립트를 넣어 선언한다.

```json
PUT /logs-payroll-000001/_mapping
{
  "runtime": {
    "duration_bucket": {
      "type": "keyword",
      "script": {
        "source": "long d = doc['duration_ms'].size() == 0 ? -1 : doc['duration_ms'].value; if (d < 0) { emit('unknown'); } else if (d < 100) { emit('fast'); } else if (d < 1000) { emit('normal'); } else { emit('slow'); }"
      }
    },
    "cost_krw": {
      "type": "double",
      "script": { "source": "emit(doc['duration_ms'].value * 0.0012)" }
    }
  }
}
```

`emit()` 이 값을 내보낸다. 배열을 만들려면 여러 번 호출한다. `doc['field'].size() == 0` 체크가 필수인데, 해당 필드가 없는 문서에서 `.value` 접근이 예외를 던지기 때문이다. 이 방어를 빼면 일부 문서 때문에 전체 쿼리가 실패한다.

Runtime field 는 매핑을 **즉시** 바꾼다. 재색인(reindex)도, 인덱스 재생성도 필요 없다. 기존 문서에 소급 적용되고, 필요 없어지면 매핑에서 지우면 된다. 이 성질이 로그·감사 데이터에서 특히 유용하다.

```sql
FROM logs-payroll-*
| STATS c = COUNT(*), total_cost = SUM(cost_krw) BY duration_bucket
| SORT total_cost DESC
```

ES|QL 의 `EVAL` 과 역할이 겹치지만 차이가 있다. `EVAL` 은 쿼리 안에서만 유효한 일회성 계산이고, runtime field 는 **매핑에 등록된 재사용 가능한 정의**다. 여러 쿼리·대시보드가 같은 파생 값을 쓰면 runtime field 로 올리는 편이 정의 중복을 막는다. 반면 한 번만 쓰는 계산은 `EVAL` 이 가볍다.

**비용 구조**를 정확히 이해해야 한다. Runtime field 는 매칭된 문서마다 스크립트를 실행한다. 따라서 비용이 **스캔 문서 수에 비례**한다.

| 사용 위치 | 비용 특성 | 권장 여부 |
|---|---|---|
| 응답 필드로 반환 | 반환 문서 수(보통 10~100)만 실행 | 안전 |
| `STATS BY` 그룹 키 | 매칭 문서 전체 실행 | 스캔 범위를 좁힌 뒤에만 |
| 좁은 `WHERE` 조건 | 인덱스 미사용, 전체 스캔 유발 | 위험 — 색인 필드 병행 필터 필수 |
| `SORT` 키 | 매칭 문서 전체 실행 후 정렬 | 회피 |

핵심은 runtime field 로 필터링하면 **역인덱스를 쓸 수 없다**는 점이다. `WHERE duration_bucket == 'slow'` 만으로 7일치 로그를 조회하면 전체 문서에 스크립트를 돌린다. 반드시 색인된 필드(`@timestamp`, `service`)로 먼저 좁힌 뒤 runtime field 를 적용한다.

```sql
-- 나쁜 예: 전체 스캔
FROM logs-payroll-*
| WHERE duration_bucket == "slow"
| STATS COUNT(*)

-- 좋은 예: 색인 필드로 먼저 좁힌다
FROM logs-payroll-*
| WHERE @timestamp >= NOW() - 1 hour AND service == "payroll-api"
| WHERE duration_bucket == "slow"
| STATS COUNT(*)
```

`doc_values` 접근과 `_source` 접근의 차이도 중요하다. `doc['field']` 는 컬럼형 저장소(doc values)를 읽으므로 빠르지만, `text` 타입 필드는 doc values 가 없어 접근 불가다. `params._source['field']` 는 원본 JSON 을 파싱하므로 훨씬 느리고, 문서마다 JSON 파싱이 발생한다. 가능하면 `doc[]` 를 쓰고, `text` 필드가 필요하면 `keyword` 서브필드를 색인해 둔다.

## 5. 스키마 온 라이트 vs 스키마 온 리드

Runtime field 와 색인 필드의 선택은 비용을 언제 낼지의 문제다.

| 축 | 색인 필드 (schema on write) | Runtime field (schema on read) |
|---|---|---|
| 색인 비용 | 높음 — 역인덱스 + doc values 생성 | 없음 |
| 디스크 | 필드당 증가 | 0 |
| 조회 비용 | 낮음 — 역인덱스 사용 | 문서 수 비례 스크립트 실행 |
| 스키마 변경 | 재색인 필요 | 즉시 |
| 과거 데이터 적용 | 재색인 후에만 | 즉시 소급 |
| 집계 성능 | 빠름 | 스캔 범위에 따라 가변 |

실무 판단 기준은 **쿼리 빈도 대 필드 수**다.

자주 조회하는 소수의 필드는 색인한다. `@timestamp`, `service`, `http.route`, `http.status`, `company_code` 같은 핵심 차원은 색인 필드여야 한다. 반면 **가끔 조사할 때만 필요한 다수의 필드**는 runtime field 가 맞다. 로그 페이로드에서 특정 장애 조사를 위해 필드를 하나 꺼내 봐야 하는데, 그걸 위해 전체 재색인을 하는 것은 비용이 맞지 않는다.

하이브리드 전략이 표준이다. Runtime field 로 먼저 필드를 정의해 실제로 유용한지 확인하고, 유용하다고 판단되면 ingest pipeline 에 프로세서를 추가해 **이후 색인되는 문서**에서는 색인 필드로 만든다. 과거 데이터는 runtime field 정의를 유지해 커버하고, 필드 이름을 동일하게 두면 쿼리는 바뀌지 않는다. Elasticsearch 는 같은 이름의 색인 필드가 있으면 그것을 우선하고 없는 인덱스에서는 runtime 정의를 쓴다.

```json
PUT /_ingest/pipeline/payroll-logs
{
  "processors": [
    {
      "script": {
        "source": "long d = ctx.duration_ms; ctx.duration_bucket = d < 100 ? 'fast' : (d < 1000 ? 'normal' : 'slow');"
      }
    }
  ]
}
```

## 6. 운영 제약과 주의점

**결과 행 상한** — ES|QL 은 기본적으로 1만 행을 넘기지 않는다. 대량 추출 용도가 아니라 분석·집계 용도로 설계됐다. 전체 데이터를 내려야 한다면 여전히 `search_after` 나 PIT(point-in-time) 스크롤을 쓴다.

**지원 필드 타입** — 모든 타입이 ES|QL 에서 동등하게 지원되지는 않는다. `nested` 필드는 직접 조회가 제한되고, `text` 필드는 집계 키로 쓸 수 없다(`keyword` 서브필드를 쓴다). `dense_vector` 를 다루려면 별도 함수가 필요하다. 버전에 따라 지원 범위가 계속 넓어지는 영역이므로 사용 전에 해당 배포판 문서의 지원 타입 표를 확인한다.

**LOOKUP JOIN** — 인덱스 간 결합이 가능해졌지만 제약이 있다. 오른쪽(룩업) 인덱스는 조회 전용으로 준비돼야 하고, 조인 키는 정확 일치여야 하며, 룩업 인덱스 크기가 작아야 성능이 유지된다. RDB 의 해시 조인 같은 대규모 결합을 기대하면 안 된다.

**크로스 클러스터** — `FROM remote:logs-*` 형태로 크로스 클러스터 검색이 가능하지만, 원격 노드로 밀어내는 연산 범위가 버전에 따라 다르다. 원격에서 집계까지 수행되는지 아니면 원시 데이터를 끌어와 로컬에서 집계하는지가 네트워크 비용을 결정하므로, `_query` 의 프로파일 출력으로 확인한다.

**쿼리 프로파일링** — 성능 문제는 계획을 봐야 한다.

```json
POST /_query?format=json
{
  "query": "FROM logs-* | WHERE duration_bucket == 'slow' | STATS COUNT(*)",
  "profile": true
}
```

프로파일 출력에서 각 드라이버(연산 파이프라인)의 실행 시간과 처리 행 수를 본다. 술어가 Lucene 으로 밀려 내려갔는지, 아니면 전체 스캔 후 필터로 남았는지가 여기서 드러난다. Runtime field 필터가 밀려 내려가지 않는 것을 확인하는 가장 확실한 방법이다.

**보안** — ES|QL 은 문서 수준/필드 수준 보안(DLS/FLS)을 존중한다. 다만 runtime field 스크립트가 FLS 로 가려진 필드를 참조하면 실행이 거부되므로, 권한 분리된 환경에서는 runtime 정의와 역할 정의를 함께 검토해야 한다.

## 7. Kibana 와 애플리케이션 통합

Kibana 의 Discover 와 Lens 는 ES|QL 모드를 지원해 파이프 쿼리 결과를 그대로 시각화한다. 이것이 실무에서 가장 큰 생산성 차이를 만드는 지점이다. DSL 로는 표현하기 어려운 다단계 변환을 쿼리 한 줄로 쓰고 바로 차트로 본다.

애플리케이션에서는 `_query` 엔드포인트를 HTTP 로 호출한다. Java 클라이언트에서는 문자열 쿼리와 파라미터 바인딩을 함께 쓴다.

```java
@Service
public class LogAnalyticsService {

	private final ElasticsearchClient client;

	public List<RouteLatency> topSlowRoutes(String service, Duration window, int limit) throws IOException {
		String query = """
				FROM logs-payroll-*
				| WHERE @timestamp >= NOW() - ?window AND service == ?svc
				| STATS p95 = PERCENTILE(duration_ms, 95), reqs = COUNT(*) BY http.route
				| WHERE reqs > 100
				| SORT p95 DESC
				| LIMIT ?limit
				""";
		// 실제 바인딩 방식은 클라이언트 버전에 따라 다르므로
		// esql().query(...) 의 params 구성 문서를 확인해 맞춘다
		return this.client.esql()
				.query((q) -> q.query(query).params(buildParams(service, window, limit)))
				.let(this::toRouteLatencies);
	}
}
```

컬럼/행 형태로 오므로 매핑 코드가 단순하다. 다만 컬럼 순서에 의존하는 파싱은 쿼리 수정에 취약하므로 `columns` 의 `name` 으로 인덱스를 찾아 매핑한다. 결과 타입도 `columns[].type` 에서 확인해 `long`/`double`/`keyword` 변환을 분기한다.

정리하면 ES|QL 은 Query DSL 을 대체하는 것이 아니라 **분석 질의의 표현 수단**을 추가한 것이다. 문서 검색(BM25 관련도 정렬, 하이라이팅, 제안)은 여전히 `_search` 의 영역이고, 집계·파이프라인 분석·임시 조사는 ES|QL 이 훨씬 짧고 빠르게 끝난다. Runtime field 는 그 위에서 "매핑을 확정하지 않고도 질문할 수 있게" 만드는 도구이며, 필터 조건으로 쓸 때의 비용만 지키면 운영 부담 없이 쓸 수 있다.

## 참고

- Elasticsearch 공식 문서 — ES|QL: https://www.elastic.co/docs/explore-analyze/query-filter/languages/esql
- Elasticsearch 공식 문서 — ES|QL 함수와 연산자 레퍼런스: https://www.elastic.co/docs/reference/query-languages/esql
- Elasticsearch 공식 문서 — Runtime fields: https://www.elastic.co/docs/manage-data/data-store/mapping/runtime-fields
- Elasticsearch 공식 문서 — Ingest pipelines: https://www.elastic.co/docs/manage-data/ingest/transform-enrich/ingest-pipelines
- Apache Lucene — doc values 와 역인덱스 구조: https://lucene.apache.org/core/documentation.html
- Flajolet et al., "HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm", 2007
