Notion 원본: https://www.notion.so/36f5a06fd6d381b190daee983c548fe3

# Argo Rollouts Canary AnalysisRun과 Prometheus Metric Gate

> 2026-05-29 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- `Rollout` CRD 의 canary step 과 `AnalysisRun` 의 평가 흐름을 작성한다
- `AnalysisTemplate` 의 `successCondition` / `failureCondition` 와 `inconclusive` 분기를 다루고
- Prometheus query 를 metric provider 로 등록해 P99 latency, 5xx error rate 를 gate 한다
- progressive delivery 의 failure 시 자동 rollback 과 수동 promote 의 trade-off 를 정한다

## 1. Argo Rollouts 가 해결하는 문제

Kubernetes 의 기본 `Deployment` 는 rolling update 시 readiness probe 외에 배포 진행 가부를 평가할 hook 이 없다. canary 비율 조정, header/cookie 기반 라우팅, 실패 자동 롤백 같은 progressive delivery 의 핵심 기능이 빠져 있다. Argo Rollouts 는 `Deployment` 를 `Rollout` 으로 대체하면서 다음 4 가지를 추가한다. (a) canary/blueblue 전략의 step DSL, (b) `AnalysisRun` 으로 외부 metric 을 평가, (c) Istio/NGINX/SMI 같은 service mesh 와의 traffic 분기, (d) experiment CRD 로 A/B 비교.

## 2. Rollout CRD 의 canary step 구조

`Rollout` 은 `Deployment` 와 spec 이 거의 동일하지만 `strategy.canary` 아래에 step 배열을 둔다. 가장 단순한 step 은 `setWeight` 와 `pause` 다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: api-server
spec:
  replicas: 10
  strategy:
    canary:
      canaryService: api-server-canary
      stableService: api-server-stable
      trafficRouting:
        istio:
          virtualService:
            name: api-server-vs
            routes: ["primary"]
      steps:
        - setWeight: 10
        - pause: {duration: 2m}
        - analysis:
            templates:
              - templateName: success-rate
              - templateName: latency-p99
            args:
              - name: service-name
                value: api-server-canary
        - setWeight: 30
        - pause: {duration: 5m}
        - analysis:
            templates:
              - templateName: success-rate
        - setWeight: 60
        - pause: {duration: 5m}
        - setWeight: 100
```

`setWeight` 가 새 ReplicaSet 으로 보낼 트래픽 비율이다. `trafficRouting.istio` 가 없으면 weight 는 단순히 ReplicaSet 의 pod 수 비율을 의미한다. `pause` 는 대기 시간이고 `duration` 을 생략하면 무기한 대기(수동 promote 필요)다.

## 3. AnalysisTemplate 와 metric 평가

`AnalysisTemplate` 은 재사용 가능한 metric 묶음이다. 각 metric 은 provider, interval, count, condition 으로 구성된다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: success-rate
spec:
  args:
    - name: service-name
  metrics:
    - name: success-rate
      interval: 30s
      count: 5
      successCondition: result[0] >= 0.99
      failureCondition: result[0] < 0.95
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus.monitoring.svc:9090
          query: |
            sum(rate(http_requests_total{
              service="{{args.service-name}}",
              code!~"5.."
            }[1m]))
            /
            sum(rate(http_requests_total{
              service="{{args.service-name}}"
            }[1m]))
```

`interval` × `count` 가 metric 의 총 평가 윈도우다. 위 예는 30s 간격으로 5회, 즉 2.5 분 동안 5 회 평가한다. `successCondition` 과 `failureCondition` 사이의 걭(0.95 ~ 0.99)이 inconclusive zone 이다. 걭이 없으면 작은 noise 가 fail/promote 를 흔든다.

## 4. P99 latency gate 의 Prometheus query

P99 latency 는 `histogram_quantile` 을 쓰지만, query 의 bucket label 정합성이 자주 깨진다.

```yaml
- name: latency-p99
  interval: 30s
  count: 5
  successCondition: result[0] < 0.4
  failureCondition: result[0] > 0.6
  failureLimit: 2
  provider:
    prometheus:
      address: http://prometheus.monitoring.svc:9090
      query: |
        histogram_quantile(0.99,
          sum by (le) (
            rate(http_request_duration_seconds_bucket{
              service="{{args.service-name}}"
            }[2m])
          )
        )
```

주의 사항이 둘 있다. 첫째, `sum by (le)` 의 `le` 를 빠뜨리면 bucket 이 합쳐지지 않아 결과가 NaN 이다. AnalysisRun 은 NaN 을 `Error` 로 처리하고 condition 이 평가되지 않는다. 둘째, rate window(`[2m]`) 가 너무 짧으면 traffic 적은 시간대에 bucket count 가 0 인 구간이 생긴다. `rate` window 는 interval × 4 이상으로 두는 게 안전하다.

## 5. failureLimit, consecutiveErrorLimit, inconclusiveLimit 의 의미 구분

| limit | 평가 | 초과 시 |
| --- | --- | --- |
| `failureLimit` | failureCondition 만족 횟수 | AnalysisRun → Failed |
| `consecutiveErrorLimit` | provider error 연속 횟수 | AnalysisRun → Error |
| `inconclusiveLimit` | inconclusive 횟수 | AnalysisRun → Inconclusive |

`Failed` 는 자동 rollback 을 일으킨다. `Error` 는 step 을 멈추고 사람 개입을 기다린다. 운영적으로는 Prometheus outage 가 `Error` 로 분리되어야 monitoring 장애가 production rollback 을 trigger 하지 않는다.

## 6. 자동 rollback 의 작동 메커니즘

`Rollout` 컨트롤러는 AnalysisRun 이 Failed 가 되면 즉시 canary ReplicaSet 의 weight 를 0 으로 떨구고, 이전 stable ReplicaSet 의 pod 수를 원복한다. traffic routing(Istio/NGINX) 의 weight 를 1:0 으로 reset 한다. 이 과정은 secondary controller loop 안에서 atomic 하지 않다. 즉 traffic 이 100% stable 로 돌아가기까지 mesh sync latency(보통 1~3s) 가 있다.

## 7. 수동 promote 와 abort 의 사용 사례

```bash
kubectl argo rollouts promote api-server
kubectl argo rollouts promote api-server --full
kubectl argo rollouts abort api-server
kubectl argo rollouts retry rollout api-server
```

`promote --full` 은 자동 평가를 건너뛰므로 incident response 용이다. 평시에 쓰면 progressive delivery 의 의의가 사라진다. 운영 룰은 "metric gate 통과한 step 만 자동 promote, dashboard 확인이 필요한 step 만 수동 promote" 로 단순화한다.

## 8. 운영 체크리스트와 실측 trade-off

운영 관점에서 다음을 점검한다. 첫째, `prometheus.address` 가 가리키는 인스턴스가 canary metric 을 정확한 시간에 본다. Prometheus 가 federation 으로 다른 cluster 의 metric 을 pulling 하면 scrape lag 가 30~60s 추가된다. AnalysisRun interval 은 이 lag 보다 크게 둔다. 둘째, canary weight 와 replica 수의 관계. `setWeight: 10` 인데 replica 10 이면 canary pod 1 개다. 한 pod 의 OOM 이 100% 실패로 보인다. 실용적 권장은 canary 가 항상 최소 3 replica 를 가지도록 설정하는 것이다. 셋째, AnalysisRun history 보관. 기본 25 개 history 가 유지되지만, debugging 을 위해 `spec.analysis.successfulRunHistoryLimit: 10`, `unsuccessfulRunHistoryLimit: 50` 으로 운영한다.

## 참고

- Argo Rollouts documentation — argoproj.github.io/argo-rollouts
- AnalysisTemplate spec reference — Argo Rollouts CRD reference
- Prometheus histogram_quantile function — prometheus.io/docs/prometheus/latest/querying
- Istio VirtualService traffic shifting — istio.io documentation
