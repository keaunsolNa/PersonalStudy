Notion 원본: https://www.notion.so/3ef5a06fd6d3817abac2cf3a64825f5f

# Prometheus TSDB 구조와 카디널리티 폭발 제어 및 Recording Rule 설계

> 2026-10-03 신규 주제 · 확장 대상: OpenTelemetry Collector 파이프라인과 테일 샘플링 및 스팬 메트릭 생성, Kubernetes HPA v2와 KEDA

## 학습 목표

- Head 블록, WAL, 2시간 블록, 컴팩션으로 이어지는 Prometheus TSDB 쓰기·읽기 경로를 설명한다.
- 시계열 수(카디널리티)가 메모리·쿼리 비용을 결정하는 이유와 폭발 원인을 식별한다.
- `/api/v1/status/tsdb`, `promtool tsdb analyze`로 고카디널리티 메트릭을 찾는다.
- 수집 단계 제한(`sample_limit` 등)과 relabel로 폭발을 차단하고, Recording Rule로 쿼리 비용을 줄인다.

## 1. 데이터 모델: 시계열은 레이블 조합이다

Prometheus의 시계열은 메트릭 이름과 레이블 집합의 **고유한 조합**으로 식별된다. `http_requests_total{method="GET", status="200", path="/api/users"}`와 `{method="GET", status="500", path="/api/users"}`는 서로 다른 두 시계열이다. 각 시계열은 (타임스탬프, 값) 샘플의 연속이다. 이 구조에서 비용을 지배하는 변수는 **샘플 수가 아니라 활성 시계열 수**다. 샘플은 압축이 잘 되지만(공식 문서는 샘플당 평균 약 1~2바이트라고 설명한다), 시계열마다 레이블 인덱스와 메모리 구조가 따로 필요하다.

카디널리티는 레이블 값 개수의 곱으로 폭증한다. 예를 들어 `method`(5개) × `status`(10개) × `path`(200개) × `instance`(50개)는 이론상 50만 시계열이다. 여기에 `user_id`나 `request_id`처럼 값이 무한히 늘어나는 레이블이 하나 들어가면 시계열이 요청마다 생성되어 서버가 메모리 부족으로 종료될 수 있다. **고유 식별자는 메트릭 레이블로 쓰지 않는다.** 그런 정보는 로그나 트레이스의 영역이다.

## 2. TSDB 구조: Head, WAL, 블록

쓰기 경로부터 따라가 보자. 스크레이프된 샘플은 메모리의 **Head 블록**에 추가된다. Head는 최근 데이터를 담으며, 시계열별로 현재 쓰고 있는 chunk를 유지한다(한 chunk는 대략 120 샘플까지 채워진다). 프로세스가 비정상 종료되어도 복구할 수 있도록 모든 쓰기는 먼저 **WAL**(Write-Ahead Log, `wal/` 디렉터리)에 기록된다. 재시작 시 WAL을 재생해 Head를 복원하는데, 시계열이 많을수록 재생에 오래 걸린다. 운영 중 재시작 후 한참 동안 쿼리가 불가능한 현상의 원인이 이것이다.

Head의 데이터가 일정 범위(기본 약 2시간 분량)를 넘으면 디스크의 불변 **블록**으로 내려간다. 블록은 디렉터리 하나이며 `chunks/`, `index`, `meta.json`, `tombstones`를 가진다. `index`는 레이블 → 시계열 ID를 찾는 역인덱스(postings)와 시계열 → chunk 위치 정보를 담는다. 이후 **컴팩션**이 작은 블록들을 더 큰 블록으로 병합해 쿼리 시 열어야 할 블록 수를 줄인다. 보존 기간(`--storage.tsdb.retention.time`, 기본 15일)이나 크기(`--storage.tsdb.retention.size`)를 초과한 블록은 삭제된다.

```text
data/
 ├─ wal/            000001, 000002 ...   (Head 복구용 로그)
 ├─ chunks_head/    (Head의 mmap chunk)
 ├─ 01H.../         (2h 블록: chunks/, index, meta.json, tombstones)
 └─ 01J.../         (컴팩션된 더 큰 블록)
```

쿼리는 요청 시간 범위와 겹치는 블록들과 Head를 모두 조회해 병합한다. 시간 범위가 길수록 읽을 블록이 늘고, 시계열이 많을수록 postings 교집합과 chunk 디코딩 비용이 커진다. 즉 **쿼리 비용 ≈ 매칭되는 시계열 수 × 시간 범위 안의 샘플 수**다.

## 3. 메모리 예산 감각

시계열 하나가 Head에서 차지하는 메모리는 레이블 크기, chunk 상태에 따라 다르며 흔히 시계열당 수 KB 수준으로 알려져 있다. 정확한 값은 버전과 레이블 구성에 따라 다르므로 직접 측정해야 한다. 측정 방법은 Prometheus 자체 메트릭이다.

```promql
# 활성(Head) 시계열 수
prometheus_tsdb_head_series

# 프로세스 메모리 / 시계열 수 → 시계열당 대략적 메모리(바이트)
process_resident_memory_bytes{job="prometheus"} / prometheus_tsdb_head_series

# 초당 새로 생성되는 시계열 (churn)
rate(prometheus_tsdb_head_series_created_total[5m])
```

`churn`은 카디널리티만큼 중요하다. 쿠버네티스에서 파드가 재시작될 때마다 `pod` 레이블 값이 바뀌어 새 시계열이 생성되므로, 총 시계열 수가 안정적이어도 Head에는 오래된 시계열과 새 시계열이 누적되고 인덱스 크기가 커진다. 배포가 잦은 환경에서 `rate(prometheus_tsdb_head_series_created_total[5m])`이 높게 유지되면 점검 대상이다.

## 4. 폭발 지점 찾기

가장 빠른 방법은 TSDB 상태 API다. 응답에는 시계열 수 상위 메트릭 이름, 레이블 이름별 값 개수, 레이블 값 쌍별 시계열 수 상위 목록이 담긴다.

```bash
curl -s http://localhost:9090/api/v1/status/tsdb | jq '.data.seriesCountByMetricName[:10]'
curl -s http://localhost:9090/api/v1/status/tsdb | jq '.data.labelValueCountByLabelName[:10]'
```

디스크에 있는 데이터를 오프라인으로 분석하려면 `promtool tsdb analyze <data-dir>`를 쓴다. 블록의 시계열 수, 레이블별 카디널리티, 고카디널리티 메트릭 상위 목록을 출력한다. PromQL로도 확인할 수 있지만 전체 시계열을 스캔하므로 서버가 큰 경우 부하가 크다.

```promql
# 메트릭 이름별 시계열 수 Top 10 (비용이 큰 쿼리, 평상시 상시 실행 금지)
topk(10, count by (__name__) ({__name__=~".+"}))
```

원인 후보를 좁히는 순서는 (1) `seriesCountByMetricName` 1위 메트릭 확인, (2) 그 메트릭의 `labelValueCountByLabelName`에서 값이 많은 레이블 확인, (3) 해당 레이블이 URL 경로(`/users/12345`), 사용자 ID, 에러 메시지 전문, 컨테이너 ID처럼 **열린 집합(open set)** 인지 판단하는 것이다. HTTP 경로 레이블은 라우트 템플릿(`/users/{id}`)으로 정규화해야 한다.

## 5. 수집 단계에서 막기

폭발이 서버에 도달하기 전에 스크레이프 설정에서 제한한다.

```yaml
scrape_configs:
  - job_name: app
    sample_limit: 50000          # 한 번의 스크레이프에서 허용할 샘플 수. 초과 시 해당 스크레이프 전체 실패
    label_limit: 30              # 시계열당 최대 레이블 수
    label_value_length_limit: 200
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: "go_gc_.*|go_memstats_.*"   # 불필요한 메트릭 드롭
        action: drop
      - regex: "request_id|trace_id"         # 위험한 레이블 제거
        action: labeldrop
      - source_labels: [path]
        regex: "/users/[0-9]+"
        target_label: path
        replacement: "/users/:id"             # 열린 값 정규화
```

`sample_limit`을 초과하면 해당 타깃의 스크레이프가 **통째로 실패**하고 `up`은 0이 된다(`scrape_samples_post_metric_relabeling` 기준으로 판단). 일부만 수집하는 것이 아니므로 알림(`up == 0`, `scrape_samples_scraped` 급증)과 함께 운영해야 한다. 안전망이지 정상 경로가 아니다. 근본적 해결은 계측 코드에서 레이블 설계를 고치는 것이다. `labeldrop`으로 레이블을 지울 때 남은 레이블 조합이 충돌하면 중복 시계열로 샘플이 버려질 수 있으므로 주의한다.

히스토그램도 버킷 수만큼 시계열이 늘어난다. 버킷 10개 × 레이블 조합 N개 = 10N(+`_sum`, `_count`)이다. 메서드·경로·상태 코드마다 히스토그램을 만들면 비용이 빠르게 커지므로, 지연 시간 히스토그램의 레이블은 최소한(예: 경로 템플릿 정도)으로 제한한다.

## 6. Recording Rule로 쿼리 비용 줄이기

대시보드가 매번 수천 시계열을 집계하면 서버에 부담이다. Recording Rule은 자주 쓰는 표현식을 주기적으로 미리 계산해 **새 시계열로 저장**한다. 대시보드와 알림은 미리 계산된 결과를 읽는다.

```yaml
groups:
  - name: http_aggregates
    interval: 30s
    rules:
      - record: job:http_requests:rate5m
        expr: sum by (job) (rate(http_requests_total[5m]))
      - record: job_status:http_requests:rate5m
        expr: sum by (job, status) (rate(http_requests_total[5m]))
      - record: job:http_request_duration_seconds:p99_5m
        expr: |
          histogram_quantile(0.99,
            sum by (job, le) (rate(http_request_duration_seconds_bucket[5m])))
```

네이밍은 공식 가이드의 `level:metric:operations` 형식을 따른다. `level`은 집계에 남은 레이블, `metric`은 원본 이름, `operations`는 적용한 연산이다. 일관된 이름은 어떤 규칙이 어떤 원본에서 왔는지 추적하게 해준다. 집계할 때는 원본의 `_total`을 뗀 이름 뒤에 `rate5m` 같은 연산을 붙인다.

설계 원칙이 몇 가지 있다. 먼저 **집계 결과는 반드시 `rate`/`increase`를 적용한 뒤 `sum`** 한다. 카운터를 먼저 합산한 뒤 `rate`를 계산하면 카운터 리셋 처리가 깨진다. `histogram_quantile`은 버킷 시계열을 `sum by (le, ...)`로 합친 뒤 호출한다. 다음으로 규칙의 평가 주기를 스크레이프 주기와 같거나 배수로 맞춘다. 마지막으로 `rate` 범위 윈도는 스크레이프 간격의 최소 4배를 권장한다. 스크레이프 간격이 15초라면 `[1m]` 이상이다. 윈도 안에 샘플이 2개 미만이면 결과가 없다.

trade-off는 명확하다. Recording Rule은 **쿼리 비용을 평가 비용과 저장 비용으로 바꾼다.** 레이블이 많이 남는 규칙은 오히려 시계열을 늘린다. 집계로 줄이는 레이블 차원이 클수록 이득이다. 사용 빈도가 낮은 규칙은 오히려 낭비이므로 대시보드·알림에서 실제 참조되는 표현식만 규칙화한다.

## 7. 알림과 운영 지표

서버 자신의 건강을 감시하는 최소 알림 세트를 제안한다.

```yaml
groups:
  - name: prometheus_self
    rules:
      - alert: PrometheusSeriesChurnHigh
        expr: rate(prometheus_tsdb_head_series_created_total[15m]) > 1000
        for: 30m
        annotations:
          summary: "시계열 생성 속도 급증 (임계값은 환경 기준선에 맞춰 조정)"
      - alert: PrometheusTargetScrapeSampleLimit
        expr: increase(prometheus_target_scrapes_exceeded_sample_limit_total[10m]) > 0
      - alert: PrometheusHeadSeriesGrowth
        expr: delta(prometheus_tsdb_head_series[1h]) > 100000
```

위 임계값은 예시이며, 환경의 평상시 기준선(7일 이상 관측)을 먼저 구한 뒤 설정해야 한다. 장기 보존이 필요하면 원격 쓰기(remote write)로 Thanos, Mimir, VictoriaMetrics 같은 외부 저장소에 보내는 구성을 검토한다. 이 경우 카디널리티 비용이 외부 저장소로 옮겨질 뿐 사라지지 않는다.

## 8. 점검 절차

실제 환경에서 다음 순서로 진단한다. (1) `prometheus_tsdb_head_series`와 `process_resident_memory_bytes`로 시계열당 메모리 추정. (2) `/api/v1/status/tsdb`로 상위 메트릭·레이블 확인. (3) 해당 계측 코드의 레이블 설계를 수정하고, 당장은 `metric_relabel_configs`로 차단. (4) 수정 후 `rate(prometheus_tsdb_head_series_created_total[5m])`이 내려가는지 확인. (5) 대시보드 쿼리 중 느린 것을 `prometheus_engine_query_duration_seconds`와 쿼리 로그(`query_log_file`)로 찾아 Recording Rule 후보로 정한다. 규칙 문법은 배포 전에 `promtool check rules rules.yml`로 검증하고, `promtool test rules`로 단위 테스트를 작성할 수 있다. 본 노트의 YAML과 PromQL은 문법과 공식 문서에 기반해 작성했으며, 실제 Prometheus 서버에서 실행해 검증하지는 않았다.

```yaml
# rules_test.yml (promtool test rules rules_test.yml)
rule_files: [rules.yml]
evaluation_interval: 30s
tests:
  - interval: 15s
    input_series:
      - series: 'http_requests_total{job="app", status="200", instance="a"}'
        values: '0+15x40'      # 15초마다 15 증가 = 1 req/s
    promql_expr_test:
      - expr: job:http_requests:rate5m{job="app"}
        eval_time: 5m
        exp_samples:
          - labels: 'job:http_requests:rate5m{job="app"}'
            value: 1
```

## 참고

- Prometheus 공식 문서: Storage, TSDB 포맷(`tsdb/docs/format`)
- Prometheus 공식 문서: Recording rules, Defining recording rules (naming best practices)
- Prometheus 공식 문서: Configuration — `scrape_config`(`sample_limit`, `label_limit`), `metric_relabel_configs`
- Prometheus 공식 문서: Instrumentation Best Practices — Labels
- Brian Brazil, "Cardinality is key" (robustperception.io)
