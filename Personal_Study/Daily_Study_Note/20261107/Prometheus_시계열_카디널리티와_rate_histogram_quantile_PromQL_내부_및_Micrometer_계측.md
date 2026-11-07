Notion 원본: https://www.notion.so/4115a06fd6d3818da0ccf570dc26a3b3

# Prometheus 시계열 카디널리티와 rate·histogram_quantile PromQL 내부, Micrometer 계측

> 2026-11-07 신규 주제 · 확장 대상: Docker / AWS / DevOps 모니터링

## 학습 목표

- 시계열 모델(메트릭명 + 레이블 집합)과 TSDB 헤드·블록 구조를 설명 없이 적용한다
- 레이블 카디널리티 폭발을 진단하고 상한을 설계한다
- `rate` / `increase` 의 외삽과 카운터 리셋 처리를 이해하고 오용을 피한다
- Histogram 버킷과 `histogram_quantile` 의 보간 오차를 계산한다
- Micrometer 로 Spring Boot 에서 SLO 중심 메트릭을 정의하고 알람 규칙까지 작성한다

## 1. 데이터 모델과 TSDB 저장

Prometheus 의 한 시계열은 `메트릭명{label1="a",label2="b"}` 라는 **유일한 레이블 집합**으로 식별되고, 값은 `(timestamp, float64)` 샘플의 연속이다. 레이블 값이 하나라도 다르면 별개의 시계열이다. 그래서 `http_requests_total{path="/users/1234"}` 처럼 ID 를 레이블에 넣으면 사용자 수만큼 시계열이 생긴다.

TSDB 는 최근 2시간치를 메모리 **head block** 에 저장하고 WAL 로 내구성을 확보한 뒤, 2시간 단위 블록으로 디스크에 컴팩션한다. 시계열당 head 메모리는 대략 수 KB(레이블 문자열, 인덱스 포스팅 리스트, 청크 포함)이며, 활성 시계열 100만 개면 보통 3~6GB 힙이 필요하다. 샘플 자체는 Gorilla 계열 압축으로 샘플당 약 1~2바이트에 불과하므로, 비용 대부분은 샘플 수가 아니라 **시계열 수**에서 온다는 점이 핵심이다.

## 2. 카디널리티 진단과 통제

```promql
# 시계열 수 상위 메트릭
topk(10, count by (__name__) ({__name__=~".+"}))

# 특정 메트릭에서 레이블별 고유 값 수
count(count by (path) (http_server_requests_seconds_count))

# 헤드 통계 (HTTP API)
# GET /api/v1/status/tsdb  → seriesCountByMetricName, labelValueCountByLabelName
```

폭발 원인의 전형은 사용자 ID·요청 ID·URL 원문·이메일·에러 메시지 전문 같은 **무한 집합 값**이다. 설계 원칙은 "레이블 값의 집합이 배포 시점에 열거 가능해야 한다"이다. HTTP 경로는 템플릿(`/users/{id}`)만 쓰고, 상태 코드는 클래스(`2xx`)로 묶으며, 클라이언트 지정 값은 화이트리스트 외 `other` 로 접는다. 스크레이프 단계의 방어는 `scrape_config` 의 `sample_limit`(초과 시 해당 타깃 스크레이프 전체 실패)과 `label_limit`, `metric_relabel_configs` 의 `labeldrop`/`drop` 이다.

```yaml
scrape_configs:
  - job_name: app
    sample_limit: 50000
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: "jvm_gc_pause_seconds_bucket"
        action: drop                       # 필요 없는 버킷 폭 제거
      - regex: "(request_id|trace_id)"
        action: labeldrop                  # 위험 레이블 제거
```

여러 인스턴스가 같은 시계열을 내보낼 때 `instance` 레이블이 곱해지는 점도 계산에 넣는다. 예: 메트릭 1개 × 경로 50 × 메서드 4 × 상태 5 × 버킷 12 × 인스턴스 30 = 36만 시계열이다. 히스토그램은 버킷 수만큼 곱해지므로 가장 먼저 비용이 큰 곳이다.

## 3. rate 의 정확한 의미

카운터는 증가만 하며 프로세스 재시작 시 0 으로 리셋된다. `rate(x[5m])` 는 5분 윈도우 안의 샘플로 초당 평균 증가율을 계산하되 **리셋을 감지해 보정**한다(값이 이전보다 줄어들면 리셋으로 보고 이어붙임). 그리고 윈도우 경계에 샘플이 정확히 없으므로 첫/마지막 샘플 시각 사이 증가량을 윈도우 길이로 **외삽**한다. 그래서 `increase(x[1m])` 은 정수 요청 카운터에서도 `1.0003` 같은 소수가 나온다.

실무 규칙은 이렇다. 윈도우는 스크레이프 간격의 **최소 4배 이상**(15초 간격이면 `[1m]` 이상)이어야 샘플이 2개 이상 들어가 결과가 안정적이다. `rate` 는 장기 추세와 알람에, `irate` 는 마지막 두 샘플만 사용하므로 순간 변동을 보는 대시보드에 쓰되 알람에는 쓰지 않는다. 합산은 반드시 **rate 후 sum** 이다.

```promql
# 올바름: 인스턴스별 rate 를 먼저 계산(리셋 보정) 후 합산
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# 잘못됨: sum 후 rate — 인스턴스 하나만 재시작되어도 합산 카운터가 감소해 오탐
rate(sum(http_server_requests_seconds_count)[5m:])
```

게이지에는 `rate` 를 쓰지 않으며 `deriv`, `delta` 를 쓴다. 카운터 이름은 관례적으로 `_total` 접미사를 쓴다.

## 4. Histogram 과 histogram_quantile

Histogram 은 `_bucket{le="..."}`(누적), `_sum`, `_count` 세 계열을 낸다. 서버는 분위수를 모르며, 쿼리 시 `histogram_quantile` 이 버킷 경계 사이를 **선형 보간**해 추정한다.

```promql
histogram_quantile(
  0.99,
  sum by (le, service) (rate(http_server_requests_seconds_bucket[5m]))
)
```

반드시 `le` 를 `by` 에 유지한 채 합산한다. `le` 를 빼면 분위수가 의미를 잃는다. 오차는 버킷 폭에서 나온다. 예컨대 버킷이 `0.1, 0.5, 1` 이고 실제 p99 가 0.12 초인데 99번째 백분위가 `(0.1, 0.5]` 구간 안이면, 보간 결과는 해당 구간 내 선형 위치(0.1 + 0.4 × 비율)가 되어 실제와 최대 0.4 초까지 어긋날 수 있다. 따라서 SLO 임계값(예: 300ms) 근처에 버킷 경계를 두어야 한다. 이렇게 하면 "300ms 이하 요청 비율"은 보간 없이 정확하다.

```promql
# SLO 달성률: le="0.3" 버킷 / 전체 — 보간 없는 정확한 계산
sum(rate(http_server_requests_seconds_bucket{le="0.3"}[5m]))
/ sum(rate(http_server_requests_seconds_count[5m]))
```

Prometheus 2.40+ 의 Native Histogram(실험적, 3.x 에서 정식화 진행)은 지수 버킷으로 해상도를 자동 확보해 버킷 수 선택 문제를 줄이지만, 클라이언트 지원과 Remote Write 호환성을 확인해야 한다. 평균 지연 `rate(_sum)/rate(_count)` 는 꼬리 지연을 숨기므로 p99 와 함께 본다. Summary 는 클라이언트에서 분위수를 계산해 인스턴스 간 집계가 불가능하다는 치명적 한계가 있어 서비스 전체 분위수에는 부적합하다.

## 5. Micrometer 로 Spring Boot 계측

Spring Boot Actuator 의 `http.server.requests` 는 Timer 이며 Prometheus 로는 `http_server_requests_seconds_*` 로 노출된다. 히스토그램 버킷은 기본 비활성이라 켜야 한다.

```yaml
management:
  endpoints.web.exposure.include: health,prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      slo:
        http.server.requests: 100ms,300ms,1s,3s     # SLO 경계에 버킷 고정
      minimum-expected-value:
        http.server.requests: 10ms
      maximum-expected-value:
        http.server.requests: 10s
    tags:
      application: order-service
```

`slo` 는 지정 경계만 버킷으로 노출하므로 시계열 수를 통제하기 좋다. 커스텀 비즈니스 메트릭은 `MeterRegistry` 로 만든다.

```java
@Component
class PaymentMetrics {
    private final Counter approved;
    private final Counter declined;
    private final Timer latency;

    PaymentMetrics(MeterRegistry reg) {
        this.approved = Counter.builder("payment.attempts")
            .tag("result", "approved").register(reg);          // 레이블 값은 열거형만
        this.declined = Counter.builder("payment.attempts")
            .tag("result", "declined").register(reg);
        this.latency = Timer.builder("payment.latency")
            .publishPercentileHistogram().serviceLevelObjectives(
                Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(2))
            .register(reg);
    }

    <T> T timed(Supplier<T> call) { return latency.record(call); }
    void count(boolean ok) { (ok ? approved : declined).increment(); }
}
```

`reg.counter("x", "userId", id)` 처럼 동적 레이블을 만들면 Micrometer 가 계속 새 Meter 를 레지스트리에 보관하여 **애플리케이션 힙도 누수**된다. `MeterFilter.maximumAllowableTags("payment.attempts", "merchant", 100, MeterFilter.deny())` 로 상한을 걸어 방어한다. 테스트에서는 `SimpleMeterRegistry` 를 주입해 `registry.get("payment.attempts").tag("result","approved").counter().count()` 로 검증한다.

## 6. 알람 설계: 번 레이트

단순 임계값 알람은 오탐과 지연이 크므로 SLO 기반 **멀티 윈도우 번 레이트** 알람을 쓴다. 99.9% 가용성 SLO(오류 예산 0.1%)에서 5분·1시간 윈도우의 오류율이 모두 14.4×0.001 를 넘으면, 30일 예산의 2%를 1시간 안에 소진하는 속도이므로 즉시 호출 대상이다.

```yaml
groups:
- name: slo
  rules:
  - record: service:error_ratio:rate5m
    expr: |
      sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
      / sum by (service) (rate(http_server_requests_seconds_count[5m]))
  - record: service:error_ratio:rate1h
    expr: |
      sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[1h]))
      / sum by (service) (rate(http_server_requests_seconds_count[1h]))
  - alert: ErrorBudgetFastBurn
    expr: service:error_ratio:rate5m > (14.4 * 0.001) and service:error_ratio:rate1h > (14.4 * 0.001)
    for: 2m
    labels: { severity: page }
```

Recording rule 은 무거운 쿼리를 사전 계산해 대시보드·알람의 평가 비용을 줄이며 `level:metric:operations` 명명 규칙을 따른다.

## 7. 용량 계획 요약

필요 메모리 ≈ 활성 시계열 수 × 약 4KB(여유 포함). 디스크 ≈ 초당 샘플 수 × 보존 초 × 1.5 byte. 예를 들어 100만 시계열 × 15초 간격이면 초당 약 6.7만 샘플, 15일 보존 시 약 6.7만 × 1,296,000 × 1.5 ≈ 130GB 이다. 이 수치를 넘으면 샤딩(기능별 Prometheus 분리)이나 Thanos/Mimir 같은 장기 저장 계층을 고려한다. 도입 전 `/api/v1/status/tsdb` 를 주간 점검해 카디널리티 증가 추세를 추적한다.

## 참고

- Prometheus 공식 문서 — Data model, Querying functions(rate, histogram_quantile), Histograms and summaries
- Google SRE Workbook — Alerting on SLOs (Multiwindow, Multi-burn-rate)
- Micrometer 문서 — Concepts, Histograms and percentiles
- Spring Boot Reference — Actuator Metrics, Prometheus
