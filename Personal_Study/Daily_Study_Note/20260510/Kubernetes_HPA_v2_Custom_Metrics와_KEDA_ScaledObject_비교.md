Notion 원본: https://www.notion.so/35c5a06fd6d381fcb7b6ecdc94f6c69a

# Kubernetes HPA v2 Custom Metrics와 KEDA ScaledObject 비교

> 2026-05-10 신규 주제 · 확장 대상: DevOps

## 학습 목표

- HPA v2 의 metric source 4종(Resource, ContainerResource, Pods, Object, External) 이 metrics-server 와 어떻게 통신하는지 따라간다
- HPA v2 의 scaling 알고리즘이 desiredReplicas 를 계산하는 공식과 stabilizationWindow, behavior 의 의미를 정확히 본다
- KEDA 의 ScaledObject / TriggerAuthentication 이 HPA 위에 올라가는 layered 구조를 이해한다
- HPA Custom Metrics Adapter 와 KEDA scaler 가 같은 일을 다르게 푸는 trade-off 를 비교한다

## 1. HPA v1 의 한계와 v2 의 이유

HPA v1 (2016 정도) 은 CPU utilization 한 가지 metric 으로만 scale 했다. v2 는 다음을 가능하게 했다:

- 다중 metric: 여러 metric 중 가장 큰 desiredReplicas 채택
- custom application metric: queue length, request rate, latency
- external metric: SQS, Kafka lag, CloudWatch

이 모든 게 metrics API 라는 표준 위에서 돌아간다. v2 는 metric 의 실제 출처를 추상화한 것이지 새 알고리즘을 가진 게 아니다.

## 2. HPA v2 manifest 의 구조

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
        target: { type: Utilization, averageUtilization: 70 }
    - type: Pods
      pods:
        metric: { name: http_requests_per_second }
        target: { type: AverageValue, averageValue: "100" }
    - type: External
      external:
        metric:
          name: kafka_lag
          selector: { matchLabels: { topic: "orders" } }
        target: { type: Value, value: "1000" }
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
        - type: Pods
          value: 4
          periodSeconds: 30
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
```

selectPolicy: Max(여러 policy 중 가장 큰 변화량) 가 보통 정답. behavior 의 stabilizationWindow 는 *최근 N 초 동안의 desired 중 최소값 사용* 이라는 의미라 oscillation 을 막는다.

## 3. desiredReplicas 계산 공식

```
desiredReplicas = ceil(currentReplicas * currentMetricValue / desiredMetricValue)
```

예: 현재 5 replica, 평균 CPU 90%, target 70% → desiredReplicas = ceil(5 * 90 / 70) = 7.

HPA 는 매 15초(default) 마다 이 계산을 한다. 변화 폭은 behavior 가 제한.

stabilizationWindow 작동 예: scaleDown stabilization 300초이면, 최근 5분 동안 계산된 desiredReplicas 중 *최댓값* 이 적용된다.

## 4. metrics-server vs custom metrics adapter

### metrics-server (Resource type)
- 모든 노드의 kubelet 에서 cAdvisor → kubelet summary API 데이터를 수집
- `metrics.k8s.io` API group 으로 노출
- 15초 폴링 default. 메모리 footprint 는 1k Pod 클러스터에서 약 50-100MB

### Custom Metrics Adapter (Pods, Object type)
- `custom.metrics.k8s.io` API group 을 구현하는 별도 컴포넌트
- 가장 흔한 구현체: `prometheus-adapter`

prometheus-adapter 의 rule:

```yaml
rules:
- seriesQuery: 'http_requests_per_second{namespace!="",pod!=""}'
  resources:
    overrides:
      namespace: { resource: namespace }
      pod:       { resource: pod }
  name:
    matches: "^(.*)_per_second$"
    as: "${1}"
  metricsQuery: 'rate(http_requests_total{<<.LabelMatchers>>}[2m])'
```

### External Metrics Adapter (External type)
- `external.metrics.k8s.io` API group
- AWS CloudWatch adapter, Azure Monitor adapter, Datadog adapter 등

## 5. KEDA — ScaledObject 가 푸는 문제

HPA + custom adapter 조합의 운영 부담:
- 각 metric source 마다 adapter 를 따로 설치/유지
- scale-to-zero (replica 0 으로) 가 HPA 자체로는 불가능. minReplicas >= 1
- adapter 와 HPA 의 polling 동기화 문제

KEDA (Kubernetes Event-driven Autoscaling) 는 위 문제를 한 컴포넌트로 묶는다:
- 50+ scaler 내장
- HPA 를 *생성/관리* 하는 controller
- scale-to-zero 지원
- TriggerAuthentication 으로 secret/IAM role 을 통합 관리

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: order-worker
spec:
  scaleTargetRef:
    name: order-worker
  pollingInterval: 30
  cooldownPeriod: 300
  minReplicaCount: 0
  maxReplicaCount: 100
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka.svc.cluster.local:9092
        consumerGroup: order-group
        topic: orders
        lagThreshold: "100"
      authenticationRef:
        name: kafka-auth
```

## 6. scale-to-zero 의 동작

cooldownPeriod (default 300초) 동안 lag 이 threshold 미만으로 유지되면 KEDA controller 가 deployment.spec.replicas 를 0 으로 직접 PATCH. 새 message 가 들어와 lag > 0 이 되는 순간, KEDA 가 deployment 를 1 로 PATCH. 1 → N 부터는 HPA 가 정상 작동.

trade-off:
- 비용: idle 시간 동안 Pod 0 → 그만큼 절약.
- cold start: Pod 시작 + 애플리케이션 warmup 시간이 첫 요청에 추가됨. 보통 5-30초.
- false positive: source polling 이 30초인데 message 가 그 사이에 짧게 burst 만 일으키고 사라지면 KEDA 가 Pod 안 띄우고 lag 해소까지 다음 polling 까지 기다림.

## 7. HPA + KEDA 결정 매트릭스

| 요구 | HPA only | HPA + Custom Adapter | KEDA |
| --- | --- | --- | --- |
| CPU/Memory 만 | 적합 | 과잉 | 과잉 |
| Prometheus app metric | 부적합 | 적합 | 적합, 더 단순 |
| Kafka/SQS/PubSub lag | 부적합 | 어댑터 필요 | 적합 (built-in) |
| scale-to-zero | 불가 | 불가 | 적합 |
| 다중 metric AND/OR 로직 | OR (max) only | OR only | AND/OR 가능 |
| polling 자유도 | 15s 고정 | adapter 설정 | per-trigger 설정 |
| 보안 (secret/IAM 통합) | 별개로 관리 | adapter 별 | TriggerAuthentication 통합 |

## 8. 흔한 실패 모드와 대처

**oscillation**: scaleUp 후 즉시 scaleDown 반복. 대처: scaleDown.stabilizationWindowSeconds 를 300-600 으로, scaleDown.policies 를 30-60초당 10% 정도로 보수적 설정.

**custom metric stale**: prometheus-adapter 가 30초 간격으로 캐싱하는데 HPA 가 15초마다 같은 값을 본다. 대처: adapter 의 metricsRelistInterval 을 짧게 (10s) 두거나 KEDA 로 전환.

**replica flapping during deploy**: rolling update 중 새 Pod 가 metric 을 못 노출. 대처: scaleUp.stabilizationWindowSeconds 60초, deployment.spec.minReadySeconds 30초 이상.

**External metric 401/403**: KEDA TriggerAuthentication secret rotation 이 안 되어 polling 실패. 대처: KEDA의 metrics-apiserver Pod 의 log + ScaledObject status condition 모니터링.

**스케일이 너무 느림**: behavior.scaleUp.policies 가 너무 보수적. 대처: percent-based policy 와 pods-based policy 두 개 두고 selectPolicy: Max.

규칙: HPA 와 KEDA 의 동작은 결국 매 polling cycle 에 *목표 metric 과 현재 metric 의 비* 를 보고 ceil 곱셈으로 desiredReplicas 를 정한다는 한 공식 위에 있다.

## 참고

- Kubernetes HPA v2 — Reference: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
- HPA Behavior Configuration: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/#configurable-scaling-behavior
- prometheus-adapter — Custom Metrics: https://github.com/kubernetes-sigs/prometheus-adapter
- KEDA Documentation: https://keda.sh/docs/latest/concepts/scaling-deployments/
- KEDA Scaler Catalog: https://keda.sh/docs/latest/scalers/
- metrics-server: https://github.com/kubernetes-sigs/metrics-server
