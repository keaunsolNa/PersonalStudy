Notion 원본: https://app.notion.com/p/3535a06fd6d381a7a42ce0bf2118e1a9

# Kubernetes HPA v2 KEDA ScaledObject — 외부 메트릭 기반 스케일링

> 2026-05-01 신규 주제 · 확장 대상: Kubernetes / DevOps

## 학습 목표

- HPA v2(autoscaling/v2) 의 알고리즘과 desiredReplicas 계산식 정리
- External metrics adapter 가 metrics-server 와 다른 점, 그리고 KEDA 가 채우는 빈자리
- KEDA `ScaledObject` 의 동작 흐름 — Activation, Cooldown, MinReplicaCount=0 의 의미
- Kafka, RabbitMQ, Prometheus, AWS SQS 트리거를 사용한 실전 예시 작성

## 1. HPA v2 가 하는 일

HorizontalPodAutoscaler 는 Deployment/StatefulSet/ReplicaSet 의 replica 수를 메트릭에 따라 자동 조정한다. v2 부터는 단일 메트릭(CPU)이 아니라 다중 metric source 를 지원한다.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 2
  maxReplicas: 50
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 60 }
    - type: Pods
      pods:
        metric: { name: http_requests_per_second }
        target: { type: AverageValue, averageValue: "100" }
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
```

핵심 계산식: `desiredReplicas = ceil(currentReplicas * (currentMetricValue / desiredMetricValue))`. 여러 metric 이 있으면 각각 계산해 **최댓값**을 채택한다(보수적). `behavior` 의 `stabilizationWindowSeconds` 는 최근 N초 동안의 desiredReplicas 중 최댓값을 사용해 진동(thrashing)을 막는다.

## 2. metrics-server vs custom-metrics vs external-metrics

쿠버네티스 HPA 가 메트릭을 가져오는 경로는 세 가지 API 다.

| API 그룹 | 출처 | 예시 |
|---|---|---|
| `metrics.k8s.io` | metrics-server | CPU, Memory utilization |
| `custom.metrics.k8s.io` | prometheus-adapter, datadog-adapter 등 | pod 내 비즈니스 메트릭 |
| `external.metrics.k8s.io` | KEDA, prometheus-adapter, cloud provider | Kafka lag, SQS depth |

`metrics-server` 만 깔린 클러스터는 CPU/Memory 만 본다. Pod 외부 신호(큐 깊이, DB 커넥션 수)로 스케일하려면 external-metrics 어댑터가 필요하다. 직접 prometheus-adapter 를 깔 수도 있지만 ConfigMap 작성이 복잡하다.

## 3. KEDA 가 채우는 갭

KEDA(Kubernetes Event-Driven Autoscaling) 는 두 책임을 진다.

- **External Metrics Server 구현체**: 60+ scaler(Kafka, Pulsar, Redis, Postgres, AWS SQS, GCP PubSub, Azure Service Bus 등)를 builtin 으로 제공한다. 어댑터 직접 작성 불필요.
- **Scale-to-Zero**: HPA 단독으로는 minReplicas >= 1 이다(0 이면 metric 을 못 받음). KEDA 는 trigger 가 비어 있으면 Pod 을 0개로 줄였다가, 신호가 오면 1개로 띄우고 그 뒤부터 HPA 가 인계받는다.

아키텍처는 이중 계층이다. `ScaledObject` 가 만들어지면 KEDA Operator 가 내부적으로 HPA 객체를 자동 생성한다. KEDA 의 `keda-metrics-apiserver` 는 external metrics API 서버 역할을 하고, HPA 는 평소처럼 메트릭을 가져와 계산한다.

## 4. ScaledObject 풀 예시 — Kafka

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: order-consumer
  namespace: orders
spec:
  scaleTargetRef:
    name: order-consumer-deployment
  minReplicaCount: 0
  maxReplicaCount: 30
  pollingInterval: 30        # KEDA가 trigger 를 polling 하는 주기 (초)
  cooldownPeriod: 300        # 0으로 줄이기 전 마지막 활성 이후 대기 시간
  fallback:
    failureThreshold: 3
    replicas: 5
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleDown:
          stabilizationWindowSeconds: 180
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker:9092
        consumerGroup: order-consumer
        topic: orders
        lagThreshold: "100"     # consumer lag 100 초과당 Pod 1개
        offsetResetPolicy: earliest
      authenticationRef:
        name: kafka-trigger-auth
```

`lagThreshold: 100` 의 의미: 컨슈머 그룹 lag 가 1000 이면 desiredReplicas = ceil(1000 / 100) = 10. lag 가 0 이고 cooldownPeriod 내 신호가 없으면 0 으로 축소된다.

`fallback` 은 trigger 에 일시적으로 접근 못할 때(Kafka 가 잠깐 끊김 등) 사용할 replica 수다. 이게 없으면 메트릭 실패 시 Pod 이 0 으로 떨어져 데이터 적체가 폭발한다.

## 5. Activation Phase — 0→1 전이의 비밀

KEDA 에는 두 단계 임계가 있다 — `lagThreshold`(스케일링 임계) 와 `activationLagThreshold`(activation 임계). Pod 이 0개일 때는 HPA 가 동작 못 하므로 KEDA 가 직접 trigger 를 polling 해서 활성화 여부를 결정한다.

```yaml
triggers:
  - type: kafka
    metadata:
      lagThreshold: "100"
      activationLagThreshold: "10"  # lag가 10 넘으면 0→1 전이
```

`activationLagThreshold` 가 없으면 기본값 0 — lag 가 1만 있어도 즉시 1 Pod 으로 깬다. 트래픽 패턴에 따라 이 값을 조정해 쓸데없는 cold start 를 줄인다.

## 6. ScaledJob — 단발성 작업

배치 작업(이미지 처리, ETL job)은 Deployment 가 아니라 Job 으로 띄우는 게 자연스럽다. KEDA 의 `ScaledJob` 은 trigger 가 있으면 Job 객체를 생성하고, 끝나면 Pod 이 사라진다.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledJob
metadata:
  name: image-processor
spec:
  jobTargetRef:
    template:
      spec:
        containers:
          - name: worker
            image: image-worker:1.2.0
        restartPolicy: Never
    backoffLimit: 3
  pollingInterval: 30
  successfulJobsHistoryLimit: 5
  failedJobsHistoryLimit: 5
  maxReplicaCount: 50
  triggers:
    - type: aws-sqs-queue
      metadata:
        queueURL: https://sqs.ap-northeast-2.amazonaws.com/123456/img-queue
        queueLength: "5"        # 메시지 5개당 Job 1개
        awsRegion: ap-northeast-2
```

Job 은 idempotent 하게 설계해야 한다 — KEDA 가 메시지 수 기반으로 Job 을 만들고 각 Job 이 SQS receive 를 호출하는 모델. 동시에 두 Job 이 같은 메시지를 받지 않도록 Visibility Timeout 을 충분히 길게 잡는다.

## 7. Multiple Triggers 와 Composite

여러 trigger 를 OR 조합으로 사용 가능하다. 각 trigger 가 산출한 desiredReplicas 중 **최댓값**을 채택한다.

```yaml
triggers:
  - type: kafka
    metadata:
      topic: orders
      lagThreshold: "200"
  - type: prometheus
    metadata:
      serverAddress: http://prometheus:9090
      threshold: "500"
      query: sum(rate(http_requests_total{app="order-api"}[1m]))
```

위 예시는 "Kafka lag 가 200 단위로 증가하거나 HTTP RPS 가 500 단위로 증가"할 때 모두 반응한다. 하나가 0 이어도 다른 하나가 활성화하면 Pod 이 안 줄어든다.

## 8. 운영 관점 함정과 해결

- **Pod 시작 지연 vs metric 지연**: Pod 이 뜨고 ready 되기까지 30~60초 걸리는 동안 lag 가 더 쌓인다. HPA 가 desiredReplicas 를 다시 계산해 더 많이 띄운다 → over-provisioning. 해결: `behavior.scaleUp.policies` 로 분당 증가폭을 제한.
- **Scale to zero 후 첫 요청 지연(Cold Start)**: 0→1 전환 + Pod 시작 + 워밍업 = 1분 이상. Latency-sensitive 워크로드는 minReplicaCount=1 이 안전.
- **HPA 와 KEDA HPA 충돌**: 같은 Deployment 에 수동 HPA 를 따로 만들면 KEDA 의 자동 HPA 와 충돌한다. KEDA 가 만든 HPA 만 두고, 수동 HPA 는 삭제.
- **External metrics 캐싱**: KEDA metrics server 가 메트릭을 캐싱하지 않고 매번 polling 한다. SQS API 호출 비용이 만만치 않다. `pollingInterval` 을 30초 이상으로 잡거나 운영 비용을 측정해 조정.

## 9. Helm 으로 KEDA 설치와 인증

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace
```

각 trigger 의 인증 정보(Kafka SASL, AWS Access Key)는 `TriggerAuthentication` 에 분리한다.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-secrets
type: Opaque
data:
  sasl-username: <base64>
  sasl-password: <base64>

---
apiVersion: keda.sh/v1alpha1
kind: TriggerAuthentication
metadata:
  name: kafka-trigger-auth
spec:
  secretTargetRef:
    - parameter: username
      name: kafka-secrets
      key: sasl-username
    - parameter: password
      name: kafka-secrets
      key: sasl-password
```

AWS 의 경우 `awsRoleArn` + IRSA(IAM Roles for Service Accounts)로 키 없이 인증 가능하다. 가장 보안 권장 방식.

## 10. 모니터링 — 확장이 잘 동작했는지 확인

KEDA 자체의 메트릭은 `keda-operator` 가 `:8080/metrics` 로 노출한다. 핵심 지표.

| 메트릭 | 의미 |
|---|---|
| `keda_scaler_metrics_value` | 각 trigger 가 산출한 현재 메트릭 값 |
| `keda_scaler_active` | 1=활성, 0=비활성 |
| `keda_scaled_object_paused` | 일시정지된 ScaledObject 수 |
| `keda_scaler_errors` | trigger 호출 실패 카운터 |
| `keda_resource_totals` | 관리하는 ScaledObject/ScaledJob 수 |

Prometheus + Grafana 에서 "각 ScaledObject 의 desiredReplicas vs currentReplicas" 패널을 만들면 스케일링 적시성을 한눈에 본다.

## 11. 트레이드오프

| 접근 | 장점 | 단점 |
|---|---|---|
| HPA + metrics-server (CPU only) | 단순, 빌트인 | CPU 가 워크로드 부하 신호로 부정확 |
| HPA + prometheus-adapter | 유연, 모든 PromQL | ConfigMap 손으로 작성, 디버깅 어려움 |
| KEDA ScaledObject | 60+ trigger, scale-to-zero | 추가 컴포넌트 운영 부담 |
| Knative Serving | request-driven, 자동 0→N | 전체 서비스 메시 필요 |

이벤트-기반 워크로드(메시지 큐 컨슈머, batch processor) 는 KEDA 가 압도적으로 편하다. HTTP 요청 기반은 prometheus-adapter + 표준 HPA, 또는 Knative Serving 이 더 적합한 경우가 많다. 한 클러스터에서 여러 패턴을 혼용하는 게 실무다.

## 참고

- KEDA 공식 문서 — https://keda.sh/docs/
- KEDA Scalers 카탈로그 — https://keda.sh/docs/scalers/
- Kubernetes HPA 알고리즘 — https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
- "External Metrics with KEDA" CNCF 발표 — https://www.youtube.com/watch?v=keda-cncf-talk
- prometheus-adapter — https://github.com/kubernetes-sigs/prometheus-adapter
- IRSA(IAM Roles for Service Accounts) — https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html
