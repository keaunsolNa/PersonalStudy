Notion 원본: https://www.notion.so/3925a06fd6d38157b6cde550db3a747f

# Prometheus PromQL과 히스토그램 및 Recording Rule Alertmanager

> 2026-07-03 신규 주제 · 확장 대상: DevOps(GitHub Actions OIDC·Istio 관측성 학습됨)

## 학습 목표

- Prometheus의 풀 기반 수집·시계열 데이터 모델과 네 가지 메트릭 타입을 구분한다
- `rate`/`irate`와 히스토그램 `histogram_quantile`로 지연 분위수를 계산한다
- Recording Rule로 무거운 쿼리를 사전 집계하고 Alerting Rule로 경보를 정의한다
- Alertmanager의 그룹핑·억제·라우팅으로 경보 폭주를 제어한다

## 1. 데이터 모델과 수집 방식

Prometheus는 각 타깃의 `/metrics` 엔드포인트를 주기적으로 긁어오는 풀(pull) 모델이다. 모든 데이터는 시계열이며, 하나의 시계열은 메트릭 이름과 레이블 집합으로 유일하게 식별된다. 예: `http_requests_total{method="GET", handler="/api", status="200"}`. 각 고유 레이블 조합이 별도의 시계열이므로, 카디널리티(레이블 값의 조합 수) 관리가 성능의 핵심이다.

메트릭 타입은 넷이다. Counter는 단조 증가값(요청 수, 에러 수)으로 재시작 시 0으로 리셋된다. Gauge는 오르내리는 값(메모리, 큐 길이). Histogram은 관측값을 사전 정의된 버킷에 누적 카운트하고 `_bucket`/`_sum`/`_count` 시계열을 만든다. Summary는 클라이언트에서 분위수를 직접 계산한다.

## 2. rate와 irate: 카운터를 읽는 법

Counter의 절대값은 의미가 적고, "초당 증가율"이 관심사다. `rate()`는 지정 구간에서 초당 평균 증가율을 계산하며 카운터 리셋(재시작)을 자동 보정한다.

```promql
# 지난 5분간 초당 요청 수(핸들러별)
sum(rate(http_requests_total[5m])) by (handler)

# 5xx 에러 비율(전체 대비)
sum(rate(http_requests_total{status=~"5.."}[5m]))
  / sum(rate(http_requests_total[5m]))
```

`rate`는 구간 양 끝점 기반의 평활된 평균이라 짧은 스파이크를 뭉개다. 순간 변화가 필요하면 `irate`(마지막 두 샘플 기반)를 쓰지만, 대시보드·경보에는 노이즈가 적은 `rate`가 표준이다. 구간(`[5m]`)은 스크레이프 간격의 최소 4배 이상으로 잡아야 샘플이 충분해 안정적이다.

## 3. 히스토그램과 분위수

지연시간(latency)의 p95/p99는 평균으로 못 잡는다. Histogram 메트릭은 관측을 버킷에 누적하고, `histogram_quantile()`로 분위수를 근사한다.

```promql
# 핸들러별 p99 지연(초). le(less-or-equal) 레이블이 버킷 경계
histogram_quantile(
  0.99,
  sum(rate(http_request_duration_seconds_bucket[5m])) by (le, handler)
)
```

핵심은 `rate`를 버킷 카운터에 먼저 적용한 뒤 `le`로 집계하고, 그 위에 `histogram_quantile`을 씨우는 순서다. 분위수의 정확도는 버킷 경계 설계에 달렸다. p99가 중요한 구간에 버킷이 없으면 선형 보간으로 크게 빗나간다. 그래서 관심 SLO 근처를 초초히 버킷팅한다. 최신 Prometheus의 native histogram은 이 버킷 사전 정의 문제를 지수 버킷으로 완화한다.

Summary와의 차이도 분명하다. Summary는 클라이언트가 분위수를 미리 계산해 서버 집계(여러 인스턴스의 p99 합산)가 불가능하지만, Histogram은 서버에서 집계 후 분위수를 뽑을 수 있어 다중 인스턴스 환경에 적합하다.

| 항목 | Histogram | Summary |
|---|---|---|
| 분위수 계산 위치 | 서버(쿼리 시) | 클라이언트(수집 시) |
| 인스턴스 간 집계 | 가능 | 불가능 |
| 정확도 | 버킷 설계에 의존 | 설정 분위수에 정확 |
| 클라이언트 비용 | 낮음 | 높음(슬라이딩 윈도우) |

## 4. Recording Rule: 사전 집계

`histogram_quantile` 같은 무거운 쿼리를 대시보드가 매번 계산하면 부하가 크다. Recording Rule은 이런 표현식을 주기적으로 미리 계산해 새 시계열로 저장한다.

```yaml
# rules/recording.yml
groups:
  - name: http_slo
    interval: 30s
    rules:
      - record: job:http_request_duration:p99_5m
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_request_duration_seconds_bucket[5m])) by (le, job))
      - record: job:http_error_ratio:5m
        expr: |
          sum(rate(http_requests_total{status=~"5.."}[5m])) by (job)
            / sum(rate(http_requests_total[5m])) by (job)
```

규칙 이름은 `level:metric:operation` 관례를 따른다. 이렇게 미리 계산해 두면 대시보드와 경보는 가벼운 `job:http_error_ratio:5m`만 조회한다. 집계 레벨을 미리 낮춰 카디널리티도 줄인다.

## 5. Alerting Rule과 for 절

Alerting Rule은 조건이 참인 시계열을 pending→firing으로 승격시킨다. `for`는 "조건이 이만큼 지속되어야 firing"이라는 디바운스로, 순간 스파이크에 의한 오경보를 막는다.

```yaml
# rules/alerts.yml
groups:
  - name: slo_alerts
    rules:
      - alert: HighErrorRatio
        expr: job:http_error_ratio:5m > 0.05
        for: 10m                         # 10분 지속되어야 발화
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.job }} 5xx 비율 5% 초과"
          description: "현재 {{ $value | humanizePercentage }}"
```

`for`가 없으면 한 번의 스크레이프 스파이크로도 경보가 뜼다. 반대로 너무 길면 실제 장애 통지가 늦는다. SLO의 심각도에 맞춰 조절한다. 다중 윈도우 번레이트(짧은 창+긴 창을 AND) 기법을 쓰면 빠른 감지와 낮은 오경보를 동시에 얻는다.

## 6. Alertmanager: 그룹핑·억제·라우팅

Prometheus는 경보를 "발화"만 하고, 통지 정책은 Alertmanager가 맡는다. 세 축이 핵심이다.

그룹핑은 같은 원인의 경보를 하나의 통지로 묶는다. 노드 50대가 동시에 죽으면 50개 알림 대신 한 묶음으로 보낸다. 억제(inhibition)는 상위 경보가 있을 때 하위 경보를 죽인다(클러스터 다운 시 개별 서비스 경보 억제). 라우팅은 레이블에 따라 다른 수신자로 보낸다.

```yaml
# alertmanager.yml
route:
  group_by: ['alertname', 'job']
  group_wait: 30s          # 첫 통지 전 같은 그룹 경보를 모으는 대기
  group_interval: 5m       # 그룹에 새 경보 추가 시 통지 간격
  repeat_interval: 4h      # 미해결 경보 반복 통지 주기
  receiver: slack-default
  routes:
    - match: { severity: critical }
      receiver: pagerduty-oncall
inhibit_rules:
  - source_match: { severity: critical }
    target_match: { severity: warning }
    equal: ['job']         # 같은 job 이면 critical 이 warning 을 억제
receivers:
  - name: slack-default
    slack_configs: [{ channel: '#alerts' }]
  - name: pagerduty-oncall
    pagerduty_configs: [{ service_key: '<key>' }]
```

`group_wait`는 첫 알림을 살짝 늦춰 연관 경보를 모으는 값(보통 30초), `repeat_interval`은 미해결 경보를 다시 찌르는 주기다. 이 값들이 경보 피로도와 대응 속도의 균형을 결정한다.

## 7. 스크레이프 설정과 서비스 디스커버리

풀 모델의 실무 핵심은 "무엇을 언제 긁을지"를 정하는 스크레이프 설정이다. 정적 타겟 대신 동적 환경에서는 서비스 디스커버리(Kubernetes, Consul, EC2 등)로 타겟을 자동 발견하고, `relabel_configs`로 어떤 것을 남기고 어떤 레이블을 붙일지 가공한다.

```yaml
scrape_configs:
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs: [{ role: pod }]
    relabel_configs:
      # prometheus.io/scrape=true 어노테이션이 있는 파드만 수집
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
```

`relabel_configs`는 수집 전에 타겟 목록을 필터·변형하고, `metric_relabel_configs`는 수집 후 개별 시계열을 걸러낸다(고카디널리티 메트릭 드롭에 유용). 스크레이프 간격(`scrape_interval`)은 15~30초가 흔하며, 이 값이 `rate()` 구간의 하한을 결정한다. 푸시가 불가피한 경우(배치 잡처럼 수명이 짧아 풀할 수 없는 워크로드)에만 Pushgateway를 쓴다.

## 8. 트레이드오프와 함정

첫째, 카디널리티 폭발이 최대 위험이다. 사용자 ID·요청 경로 원본처럼 값이 무한한 레이블을 붙이면 시계열이 폭증해 메모리와 쿼리가 붕괴한다. 고카디널리티 차원은 로그·트레이스로 보내고 메트릭 레이블은 유한 집합으로 제한한다.

둘째, 히스토그램 분위수는 근삫값이다. 버킷 경계 밖의 정밀도는 보장되지 않으므로 "정확한 p99"가 필요하면 버킷을 SLO 근처에 초초히 두거나 native histogram을 검토한다.

셋째, Prometheus 단일 인스턴스는 로컬 디스크에 저장해 장기 보관·글로벌 뷰에 한계가 있다. 장기 저장·수평 확장이 필요하면 Thanos나 Mimir 같은 원격 스토리지 계층을 얇는다.

## 참고

- Prometheus Documentation — Querying / Functions (rate, histogram_quantile)
- Prometheus Documentation — Recording Rules / Alerting Rules
- Alertmanager Documentation — Configuration / Inhibition
- Google SRE Workbook — Alerting on SLOs (Multiwindow Burn-Rate)
