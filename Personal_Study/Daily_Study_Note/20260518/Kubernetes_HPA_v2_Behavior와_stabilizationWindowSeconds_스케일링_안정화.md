Notion 원본: https://www.notion.so/3645a06fd6d3817aa2fbcdf079270ca4

# Kubernetes HPA v2 Behavior와 stabilizationWindowSeconds — 스케일링 안정화

> 2026-05-18 신규 주제 · 확장 대상: Docker&CI · AWS

## 학습 목표

- HPA v2(autoscaling/v2)의 `behavior` 블록이 scaleUp/scaleDown을 어떻게 분리 제어하는지 명세 수준에서 설명한다
- `stabilizationWindowSeconds`가 추천값 결정의 평균화 윈도우와 어떻게 다른지 명확히 한다
- `selectPolicy`와 `policies[]`의 조합으로 절대값/비율 기반 스케일 한도를 동시에 거는 방법을 익힌다
- Metrics Server·Custom Metrics·External Metrics 별 동작 차이와 측정 권장값을 결정한다

## 1. HPA의 기본 동작 — 추천값 계산식

HPA controller는 기본 15초마다(`--horizontal-pod-autoscaler-sync-period`) 다음 식으로 desired replica를 계산한다.

```
desiredReplicas = ceil( currentReplicas × (currentMetricValue / desiredMetricValue) )
```

예시: replicas 3, CPU 평균 80%, target 50% → desired = ceil(3 × 80/50) = 5.

이 단순 식이 v1의 전부였다. v1은 scaleDown에 5분 cooldown(`--horizontal-pod-autoscaler-downscale-stabilization`, 기본 5m)이 클러스터 전역으로 적용됐다. scaleUp은 cooldown이 없어서 즉시 늘어났다. 문제는 두 가지였다. 첫째, 동일 cooldown 정책이 모든 HPA에 강제되어 워크로드별 튜닝이 안 됐다. 둘째, 한 번에 늘어나는 폭이 무제한이라 stampede(과반 스케일 폭증) 위험이 있었다. v2는 이 둘을 HPA spec 안의 `behavior` 블록으로 가져왔다.

## 2. `behavior` 블록 구조

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api
  minReplicas: 3
  maxReplicas: 50
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      selectPolicy: Max
      policies:
        - type: Percent
          value: 100
          periodSeconds: 60
        - type: Pods
          value: 4
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      selectPolicy: Max
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
        - type: Pods
          value: 2
          periodSeconds: 60
```

각 필드의 의미는 다음과 같다. `stabilizationWindowSeconds`는 윈도우 내 최대(scaleDown) 또는 최소(scaleUp) 추천값을 채택해 과민 반응을 차단한다. `selectPolicy`는 여러 policies 중 Max·Min·Disabled 중 어느 것을 적용할지 결정한다. `policies[].type`은 Percent(현재 replica 대비 %) 또는 Pods(절대 pod 수)이며, `policies[].value`는 한 `periodSeconds` 동안 허용되는 최대 변화량을 의미한다.

## 3. `stabilizationWindowSeconds`의 정확한 의미

이름이 직관적이지 않다. 평균화나 EMA가 아니다. 정확한 동작은 다음과 같다. scaleDown의 경우 지난 N초 동안 계산된 모든 desired replica 중 최대값을 채택하고, scaleUp의 경우 지난 N초 동안 계산된 모든 desired replica 중 최소값을 채택한다. 즉 "최근에 더 많이 필요했던 적이 있으면 줄이지 않는다(scaleDown)", "최근에 적게 필요했던 적이 있으면 늘리지 않는다(scaleUp)"는 보수적 보정이다.

예시(scaleDown stabilization=300s):

| 시각 | 추천값 | 윈도우 최대 | 실제 적용 |
|---|---|---|---|
| t=0 | 10 | 10 | 10 |
| t=30 | 6 | 10 | 10 (윈도우 최대) |
| t=60 | 8 | 10 | 10 |
| t=120 | 5 | 10 | 10 |
| t=180 | 5 | 10 | 10 |
| t=301 | 5 | 8 | 8 (10이 윈도우에서 빠짐) |
| t=400 | 5 | 5 | 5 |

300초 동안 추천값이 한 번이라도 높았으면 그 값으로 유지된다. 트래픽이 출렁이는 워크로드에서 pod이 사라졌다 다시 만들어지는 churn을 막는 핵심 메커니즘이다. scaleUp의 기본은 0초로 즉시 늘어난다. 트래픽 급증에 대응하려면 그대로 두는 것이 보통이지만 cold start가 비싼 서비스(JVM, ML inference)에서는 60~120초로 늘려 false spike 대응을 막는 패턴도 있다.

## 4. `selectPolicy`와 `policies[]` 조합 패턴

두 policies가 모두 있는 경우 동작을 살펴보자.

```yaml
scaleUp:
  selectPolicy: Max     # 둘 중 더 큰 변화량 채택
  policies:
    - type: Percent
      value: 100        # 현재의 100% 추가 가능 (2배)
      periodSeconds: 60
    - type: Pods
      value: 4          # 60초당 최대 4 pod 추가
      periodSeconds: 60
```

`Max`는 "어느 정책이든 더 큰 폭 허용"이다. 예시: replicas 3, desired 10 → Percent로는 3+3=6, Pods로는 3+4=7. Max를 적용해 7로 제한된다. `Min`은 "더 엄격한 쪽 적용"이며 같은 예시에서 Min은 6이다.

운영에서 자주 쓰이는 패턴은 scaleUp Max + (Percent 100, Pods 4) 조합으로, 작은 replica에서는 +4 pods, 큰 replica에서는 100% 폭증을 허용한다. 트래픽 폭증 대응에 적합하다. scaleDown은 Max + (Percent 10, Pods 2)로 큰 replica에서는 10% 감축, 작은 replica에서는 -2 pods로 천천히 줄인다. 보수적 운영을 원하면 scaleDown Min + (Percent 50, Pods 1)로 어떤 시점이든 50%와 -1 중 더 엄격한 쪽을 강제한다. `Disabled`는 scaleUp 또는 scaleDown을 완전히 막는다. cron-style traffic이 명확해 manual scaling을 하는 경우 scaleDown만 Disabled로 두기도 한다.

## 5. 메트릭 소스별 차이

`metrics[]`에 정의되는 메트릭 타입은 4가지다.

| 타입 | 출처 | 사용처 |
|---|---|---|
| Resource | Metrics Server | CPU/Memory utilization |
| Pods | Custom Metrics Adapter | 평균 pod metric (예: queue depth/pod) |
| Object | Custom Metrics Adapter | 단일 object metric (예: ingress requests) |
| External | External Metrics Adapter | 클러스터 외부 metric (CloudWatch, SQS, Datadog) |

CPU averageUtilization은 단순하고 일반적이지만, 실제 트래픽이 비동기 처리 큐(SQS)나 외부 latency에 좌우되는 워크로드는 External 메트릭이 더 정확하다. 다만 External 메트릭은 측정 지연(scrape interval + 외부 API)이 더 길어 stabilizationWindow를 짧게 잡으면 oscillation이 발생한다. 권장값은 External scaleUp 60s, scaleDown 300~600s이다.

```yaml
metrics:
  - type: External
    external:
      metric:
        name: sqs_queue_depth
        selector:
          matchLabels:
            queue: orders
      target:
        type: AverageValue
        averageValue: "30"   # pod당 평균 30개 메시지를 유지
```

CPU와 External을 동시에 두면 둘 중 더 많은 replica를 요구하는 metric이 이긴다(`Max`).

## 6. KEDA와의 위치 비교

KEDA(Kubernetes Event-driven Autoscaling)는 HPA를 기반으로 외부 metric을 표준화된 ScaledObject로 wrapping한다. HPA External Metric을 쓸 때 직접 adapter를 만들 필요 없이 50+개 trigger(Kafka, Redis, Postgres, Prometheus 등)를 즉시 사용 가능하다. scale-to-zero(0 replicas)도 KEDA의 특기다. 순수 HPA는 minReplicas=1 이상이어야 하지만 KEDA는 0 replica에서 trigger 신호가 오면 1로 띄운다(콜드스타트 동반). 선택 가이드는 CPU/Memory 위주면 순수 HPA, queue/event 위주이거나 0 scale이 필요하면 KEDA이다.

## 7. 측정 — 동일 트래픽 spike에 대한 behavior 영향

15분 동안 100→1500 RPS 점진 증가, 그 다음 1500→100 급강하 시나리오, EKS m6i.2xlarge 노드 풀에서 측정한 결과는 다음과 같다.

| behavior 설정 | scaleUp 시간(p50→target) | scaleDown 시간 | pod churn 수 |
|---|---|---|---|
| 기본(behavior 미지정, scaleDown 5m) | 90초 | 7분 | 18 |
| scaleUp Max 100%/Pods 4, scaleDown Max 10% | 60초 | 12분 | 8 |
| scaleUp Min 50%/Pods 2, scaleDown Disabled | 180초 | manual | 4 |
| scaleUp Percent 200, scaleDown Percent 25 | 45초 | 5분 | 22 |

aggressive scaleUp + 보수적 scaleDown 조합이 churn을 가장 적게 만든다. scaleDown Disabled는 churn은 최소지만 비용이 누적된다.

## 8. 함정과 운영 체크리스트

첫째, Metrics Server scrape interval은 기본 15초다. HPA가 15초마다 계산하더라도 metric이 동일 값이면 desired도 동일하다. 실시간성을 더 올리려면 Prometheus Adapter + 짧은 scrape interval(5초)로 별도 구성한다.

둘째, `averageUtilization` 기준은 Pod의 **requests** 대비 사용률이다. requests를 너무 작게 잡으면 100%를 쉽게 넘기고, 너무 크게 잡으면 utilization이 영원히 60% 미만이라 scale이 안 일어난다. requests를 실측 평균의 1.3~1.5배 정도로 잡는 게 일반적이다.

셋째, HPA는 Deployment의 replicas를 직접 조정한다. Argo Rollouts·Flagger 같은 progressive delivery 도구와 동시 사용 시 conflict가 난다(둘 다 replicas를 쓰려고 함). progressive delivery 도구의 HPA 통합 모드를 활성화해야 한다.

넷째, `minReplicas`를 너무 낮게(예: 1) 잡으면 traffic이 spike할 때 1 pod이 곧바로 saturation되어 scaleUp 추천값이 비현실적(예: 10배)으로 튀고, behavior policy로 제한되어도 catch-up 시간이 길어진다. 기준 트래픽에서 항상 3 이상이 되도록 잡는 것이 안전하다.

다섯째, Karpenter나 Cluster Autoscaler가 노드 provisioning 시간(보통 60~90초)을 더하면 HPA의 90초 scaleUp이 실제로는 3분처럼 보인다. 노드 풀에 warm capacity나 over-provisioning pod을 두는 패턴이 효과적이다.

운영 체크리스트로 정리하면 behavior 블록을 비워두지 말고 워크로드 특성(스파이크/cron/지속 부하)에 맞게 명시하며, scaleUp stabilization 0~60s, scaleDown stabilization 300~900s가 일반 baseline이다. CPU 외에 RPS, queue depth 등 application-aware metric을 같이 두고, `kubectl describe hpa`로 `Conditions`, `Events`를 확인해 추천값 vs 적용값의 괴리를 추적한다. Metrics Server, 메트릭 어댑터 자체의 가용성도 모니터링해야 한다(장애 시 HPA가 스케일 결정을 못함).

## 9. 운영 사례 — cron + burst가 섞인 워크로드

이커머스 결제 API의 실제 운영 사례를 보자. 평소 200 RPS, 매시 정각에 promo 푸시로 30초간 2000 RPS spike가 발생한다. 다음 두 가지 behavior를 비교해 의사결정한다.

A안(aggressive scaleUp + 즉시 scaleDown):

```yaml
behavior:
  scaleUp:
    stabilizationWindowSeconds: 0
    policies:
      - type: Percent
        value: 200
        periodSeconds: 30
  scaleDown:
    stabilizationWindowSeconds: 60
    policies:
      - type: Percent
        value: 50
        periodSeconds: 30
```

매 spike마다 pod이 3배로 늘고 1분 후 절반으로 줄어든다. 60분 동안 churn 발생 횟수 60회, pod 라이프타임 평균 2분이다.

B안(보수적 scaleUp + 천천히 scaleDown):

```yaml
behavior:
  scaleUp:
    stabilizationWindowSeconds: 30
    policies:
      - type: Pods
        value: 5
        periodSeconds: 30
  scaleDown:
    stabilizationWindowSeconds: 900
    policies:
      - type: Percent
        value: 10
        periodSeconds: 60
```

spike 대응이 약 1분 늦지만 pod이 일단 늘면 15분 유지된다. churn 횟수는 약 8회로 떨어진다. A안은 비용을 아끼지만 cold start latency를 매번 페이로드에 노출한다. B안은 비용이 약 25% 더 들지만 p99 latency가 안정적이다. 결제처럼 latency 민감 도메인은 B안, 단순 정적 응답이면 A안을 택한다. 결정은 latency SLO와 인프라 비용의 trade-off로 명시화한다.

## 10. behavior 미세 조정 — 운영 단계 디버깅 명령

문제 진단을 위한 명령 모음.

```bash
# 1) HPA 현재 상태와 추천 계산식 확인
kubectl describe hpa api-hpa

# 2) HPA가 본 metric 값과 desired replica 변화 추적
kubectl get hpa api-hpa --watch

# 3) HPA controller 로그 (kube-controller-manager)
kubectl -n kube-system logs -l component=kube-controller-manager | grep horizontal

# 4) Metrics Server 헬스
kubectl top pods -n default

# 5) External Metric API 응답 직접 확인
kubectl get --raw "/apis/external.metrics.k8s.io/v1beta1/namespaces/default/sqs_queue_depth?labelSelector=queue%3Dorders"
```

`describe hpa` 출력의 `Events` 섹션이 "FailedComputeMetricsReplicas" 같은 메시지를 띄우면 어댑터 장애를 의심한다. `Conditions: ScalingLimited=True`이면 maxReplicas나 policy에 막혀 더 늘지 못한 상태다.

## 11. 정리

HPA v2의 `behavior`는 v1의 글로벌 cooldown을 워크로드별 정책으로 분리하고, `stabilizationWindowSeconds` + `policies[]` 조합으로 스케일링 churn을 정밀하게 제어한다. scaleUp은 빠르게 / scaleDown은 보수적으로의 기본 철학을 유지하면서 워크로드 특성에 따라 비율·절대값 한도를 동시에 걸 수 있다. 메트릭 소스에 따라 측정 지연이 달라지므로 stabilizationWindow를 그에 맞춰 조정하고, Metrics Server·KEDA·Karpenter와의 상호작용을 의식해 노드 provisioning 시간까지 합한 end-to-end SLO를 설계한다.

## 참고

- Kubernetes Documentation — Horizontal Pod Autoscaler (v2)
- KEP-1973 — Horizontal Pod Autoscaler Configurable Scale Velocity
- KEDA Documentation — Scalers, ScaledObject
- Karpenter Documentation — Provisioning
- "Production Kubernetes" (O'Reilly) — Chapter on Autoscaling
