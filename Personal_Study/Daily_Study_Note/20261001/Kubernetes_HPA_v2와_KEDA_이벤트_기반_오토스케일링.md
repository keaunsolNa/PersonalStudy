Notion 원본: https://app.notion.com/p/3ec5a06fd6d3818eaeb8cbcbafa450e0?pvs=204

# Kubernetes HPA v2 메트릭 파이프라인과 KEDA 이벤트 기반 오토스케일링 및 스케일링 진동 제어

> 2026-10-01 신규 주제 · 확장 대상: Docker·Kubernetes 운영

## 학습 목표

- HPA v2의 메트릭 수집 경로(metrics.k8s.io, custom.metrics.k8s.io, external.metrics.k8s.io)를 구분하고 목적에 맞는 메트릭 타입을 선택한다
- desiredReplicas 계산식과 tolerance, 안정화 창을 적용해 스케일링 결과를 손으로 예측한다
- KEDA ScaledObject가 HPA를 생성하고 0↔1 구간을 직접 제어하는 구조를 분석해 트리거를 설계한다
- behavior 정책과 KEDA 옵션을 조합해 스케일링 진동(flapping)을 진단하고 억제한다

## 1. HPA v2의 제어 루프와 계산식

HorizontalPodAutoscaler는 kube-controller-manager 안에서 돌아가는 컨트롤러다. 기본 15초(`--horizontal-pod-autoscaler-sync-period`)마다 대상 워크로드의 파드 메트릭을 조회하고, 목표값과 비교해 scale 서브리소스의 replicas를 갱신한다. 핵심 공식은 공식 문서에 명시된 다음 한 줄이다.

```
desiredReplicas = ceil[ currentReplicas * ( currentMetricValue / desiredMetricValue ) ]
```

예를 들어 현재 4개 파드의 평균 CPU 사용률이 90%이고 목표가 60%라면 ceil(4 × 90/60) = 6이 된다. 반대로 평균이 30%라면 ceil(4 × 0.5) = 2다. 비율이 1.0에 가까우면 불필요한 변동을 막기 위해 tolerance(기본 0.1, `--horizontal-pod-autoscaler-tolerance`)가 적용되어, 비율이 0.9~1.1 안이면 스케일하지 않는다. 목표 60%일 때 평균 CPU가 54~66% 사이면 아무 일도 일어나지 않는다는 뜻이다.

Resource 타입의 Utilization은 파드의 `resources.requests` 대비 사용률이다. requests가 없는 컨테이너가 있으면 HPA는 해당 메트릭을 계산하지 못하고 `FailedGetResourceMetric` 이벤트를 남긴다. 또한 아직 Ready가 아닌 파드와 메트릭이 없는 파드는 계산에서 보수적으로 처리된다. 스케일업 계산에서는 메트릭이 없는 파드를 0% 사용으로 가정해 과대 증설을 막고, 스케일다운에서는 100% 사용으로 가정해 과대 축소를 막는다. 시작 직후 CPU 급등을 걸러내는 `--horizontal-pod-autoscaler-cpu-initialization-period`(기본 5분)와 `--horizontal-pod-autoscaler-initial-readiness-delay`(기본 30초)도 같은 목적이다.

메트릭을 여러 개 지정하면 각각 desiredReplicas를 따로 계산한 뒤 그중 최댓값을 택한다. 이 성질은 뒤에서 다룰 KEDA 다중 트리거에서도 그대로 이어진다. 상태 확인은 다음과 같이 한다.

```bash
kubectl get hpa web -w
kubectl describe hpa web   # Conditions: AbleToScale, ScalingActive, ScalingLimited
```

`ScalingLimited=True`는 계산 결과가 minReplicas/maxReplicas 경계에 걸렸다는 신호이므로, 상한 때문에 지연이 생기는지 판단하는 첫 단서가 된다.

## 2. 메트릭 파이프라인: 세 개의 API 그룹

HPA 컨트롤러는 메트릭 저장소를 직접 알지 못하고 aggregation layer에 등록된 세 API만 호출한다. 각 API는 APIService 오브젝트로 특정 서버에 연결된다.

| API 그룹 | 대표 제공자 | 메트릭 타입 | 비고 |
|---|---|---|---|
| metrics.k8s.io | metrics-server | Resource, ContainerResource | kubelet 요약 메트릭 사용, 기본 수집 간격 15초(`--metric-resolution`) |
| custom.metrics.k8s.io | Prometheus Adapter 등 | Pods, Object | 쿠버네티스 오브젝트에 매핑되는 메트릭 |
| external.metrics.k8s.io | KEDA, Prometheus Adapter 등 | External | 클러스터 밖 시스템(큐, 브로커) 메트릭 |

metrics-server는 HPA/VPA 같은 자동 스케일링 용도로 설계되었고 모니터링 시스템 대체재가 아니다. 파드 CPU 메트릭이 신선해지기까지 수십 초가 걸리는 것은 정상이다. 정확한 지연은 kubelet 윈도와 수집 주기에 따라 다르므로 환경에 따라 다름이다. 한 가지 확인해 둘 점은, 하나의 external.metrics.k8s.io APIService에는 한 번에 하나의 서버만 등록된다는 사실이다. Prometheus Adapter와 KEDA를 둘 다 external 메트릭 제공자로 쓰려 하면 충돌하므로 한쪽으로 통일해야 한다.

메트릭 타입은 다섯 가지다. Resource(파드 CPU/메모리), ContainerResource(특정 컨테이너만, 사이드카 영향을 배제, 1.30에서 GA), Pods(파드당 평균 사용자 정의 메트릭), Object(Ingress 같은 단일 오브젝트의 메트릭), External(클러스터 외부 메트릭)이다. 사이드카가 CPU를 많이 쓰는 서비스 메시 환경에서 Resource 대신 ContainerResource를 쓰면 애플리케이션 컨테이너 기준으로 스케일된다.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: web
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 3
  maxReplicas: 30
  metrics:
  - type: ContainerResource
    containerResource:
      name: cpu
      container: app
      target:
        type: Utilization
        averageUtilization: 60
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "100"
```

위 설정은 CPU와 RPS 두 메트릭 중 더 많은 replicas를 요구하는 쪽을 따른다. CPU 기반만으로는 I/O 대기형 서비스의 포화를 감지하지 못하므로, 요청률이나 큐 길이 같은 부하 선행 지표를 함께 두는 편이 반응이 빠르다. 다만 Pods 메트릭은 Prometheus Adapter 규칙 작성과 라벨 매핑이라는 운영 부담이 따른다.

## 3. behavior 필드: 속도와 안정화 창

autoscaling/v2는 `spec.behavior`로 스케일업과 스케일다운을 따로 조절한다. 구성 요소는 stabilizationWindowSeconds, policies, selectPolicy 세 가지다. 안정화 창은 지정한 기간 동안 계산된 desiredReplicas 중 가장 보수적인 값을 택하는 장치다. 스케일다운에서는 창 안의 최댓값을 쓰기 때문에, 부하가 잠깐 떨어졌다 돌아오는 상황에서 파드를 줄였다 늘리는 낭비를 막는다. 스케일다운 기본 창은 300초이고 스케일업 기본 창은 0초다.

policies는 한 번의 주기(periodSeconds) 동안 허용하는 변화량 상한이다. type은 Pods(절대 개수) 또는 Percent(현재 replicas 비율)이며, 여러 정책이 있을 때 selectPolicy가 Max(기본, 변화량이 가장 큰 정책)인지 Min인지 Disabled(해당 방향 스케일 금지)인지 결정한다. 공식 문서가 밝히는 기본값은 스케일다운이 15초당 100%, 스케일업이 15초당 100%와 4 Pods 중 큰 쪽이다. 즉 기본 상태에서 스케일업은 매우 공격적이고 스케일다운은 창(300초)으로만 완충된다.

```yaml
spec:
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      selectPolicy: Max
      policies:
      - type: Percent
        value: 100
        periodSeconds: 30
      - type: Pods
        value: 4
        periodSeconds: 30
    scaleDown:
      stabilizationWindowSeconds: 600
      selectPolicy: Min
      policies:
      - type: Percent
        value: 10
        periodSeconds: 60
```

이 설정은 급증 시 30초마다 두 배 또는 4개 중 더 큰 쪽으로 늘리고, 감소는 10분간 최대 추천값을 유지한 뒤 1분에 10%씩만 줄인다. trade-off는 분명하다. 스케일다운을 느리게 할수록 진동은 줄지만 유휴 파드 비용이 늘고, 스케일업을 느리게 하면 급증 트래픽에서 지연과 오류가 늘어난다. 적정 수치는 파드 기동 시간, 트래픽 패턴, 비용 허용 범위에 따라 달라지므로 환경에 따라 다름이다. 클라우드 노드 오토스케일러(Cluster Autoscaler, Karpenter)가 뒤에 있으면 파드 증설이 곧 노드 프로비저닝 지연으로 이어진다는 점도 함께 고려해야 한다.

## 4. KEDA의 구조: HPA를 대체하지 않고 확장한다

KEDA(Kubernetes Event-driven Autoscaling)는 CNCF 졸업 프로젝트이며 HPA를 대체하지 않는다. 핵심 컴포넌트는 세 가지다. keda-operator는 ScaledObject를 감시하며 HPA를 생성·관리하고 0↔1 활성화를 담당한다. keda-operator-metrics-apiserver는 external.metrics.k8s.io를 구현해 스케일러가 읽은 값을 HPA에 노출한다. keda-admission-webhooks는 잘못된 설정을 검증한다. 역할 분담이 중요하다. replicas가 1 이상일 때의 증감은 일반 HPA가 external 메트릭을 보고 결정하고, 0과 1 사이의 전환만 keda-operator가 직접 scale 서브리소스를 조작한다. HPA는 기본적으로 0으로 줄일 수 없기 때문이다.

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda -n keda --create-namespace
kubectl get apiservice v1beta1.external.metrics.k8s.io
```

ScaledObject 한 개가 하나의 워크로드를 대상으로 하며, 같은 워크로드를 가리키는 HPA를 따로 만들면 충돌한다. KEDA가 만든 HPA는 `keda-hpa-<ScaledObject 이름>`으로 생성되므로 `kubectl get hpa`에서 확인할 수 있다. 소유권이 KEDA에 있으므로 이 HPA를 직접 수정해도 다음 조정 때 덮어써진다. 수정은 항상 ScaledObject를 통해 한다.

핵심 필드의 기본값은 pollingInterval 30초, cooldownPeriod 300초, minReplicaCount 0, maxReplicaCount 100이다. pollingInterval은 keda-operator가 트리거를 확인하는 주기이고, cooldownPeriod는 마지막 활성 트리거 이후 replicas를 1에서 0으로 줄이기까지 기다리는 시간으로, 0보다 큰 구간의 스케일다운에는 영향을 주지 않는다. 이 구분을 놓치면 cooldownPeriod를 줄였는데 왜 3→2 축소가 빨라지지 않느냐는 혼란이 생긴다. 그 구간은 HPA의 behavior가 결정한다.

## 5. ScaledObject 설계 예제: Kafka와 Prometheus

Kafka consumer lag 기반 스케일링은 KEDA의 대표 사례다. lagThreshold는 파드 하나가 감당할 lag이고, replicas ≈ ceil(전체 lag / lagThreshold)로 계산된다. 단 파티션 수보다 컨슈머가 많아도 일하지 못하므로 KEDA는 기본적으로 replicas를 토픽 파티션 수로 제한한다(`allowIdleConsumers: "true"`로 해제 가능).

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: order-consumer
spec:
  scaleTargetRef:
    name: order-consumer
  pollingInterval: 15
  cooldownPeriod: 300
  minReplicaCount: 0
  maxReplicaCount: 12
  fallback:
    failureThreshold: 3
    replicas: 4
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleDown:
          stabilizationWindowSeconds: 300
          policies:
          - type: Percent
            value: 25
            periodSeconds: 60
  triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka.svc:9092
      consumerGroup: orders
      topic: orders
      lagThreshold: "50"
      activationLagThreshold: "5"
```

`activationLagThreshold`는 0→1 전환 기준이다. lag가 5를 넘어야 첫 파드가 뜨고, 이후 증설은 lagThreshold 기준으로 계산된다. 이렇게 활성화 임계값과 스케일링 임계값을 분리하면 lag 1~2건의 잡음에 파드가 뜨는 일을 막을 수 있다. `fallback`은 스케일러 오류가 failureThreshold번 연속되면 지정 replicas로 고정하는 안전장치다. 브로커 장애 시 0으로 줄어드는 사고를 막아 주며, 이 기능은 metricType이 AverageValue인 트리거에서 동작한다.

Prometheus 트리거는 PromQL 결과를 그대로 메트릭으로 쓴다. 인증이 필요하면 TriggerAuthentication으로 시크릿을 분리한다.

```yaml
  triggers:
  - type: prometheus
    metadata:
      serverAddress: http://prometheus.monitoring:9090
      query: sum(rate(http_requests_total{app="web"}[2m]))
      threshold: "200"
      activationThreshold: "10"
    authenticationRef:
      name: prom-auth
```

쿼리의 rate 윈도(여기서는 2m)가 그대로 반응 지연이 된다. 윈도를 줄이면 빨라지지만 노이즈가 늘어 진동의 원인이 된다. 두 트리거를 함께 쓰면 HPA와 마찬가지로 더 큰 replicas 요구가 채택된다.

## 6. 스케일링 진동의 원인과 진단

진동(flapping)은 replicas가 짧은 주기로 늘었다 줄었다를 반복하는 현상이다. 원인은 대개 세 가지로 좁혀진다. 첫째, 증설 자체가 메트릭을 바꾸는 피드백이다. 평균 CPU 기준에서 파드가 늘면 평균이 떨어져 곧바로 축소 신호가 나온다. 둘째, 메트릭 지연과 노이즈다. 짧은 rate 윈도나 큐 lag의 톱니 패턴이 대표적이다. 셋째, 기동 시간이다. 준비되기까지 90초 걸리는 파드는 그동안 부하를 못 받아 메트릭이 계속 높게 유지되고, 그 사이 추가 증설이 과도하게 일어난다.

진단은 HPA 이벤트와 현재 메트릭 값을 시간순으로 보는 데서 시작한다.

```bash
kubectl describe hpa web | sed -n '/Events/,$p'
kubectl get --raw "/apis/external.metrics.k8s.io/v1beta1/namespaces/default/s0-kafka-orders"
kubectl get scaledobject order-consumer -o jsonpath='{.status.conditions}'
```

이벤트에 `SuccessfulRescale`이 수 분 간격으로 반복되고 방향이 번갈아 나온다면 진동이다. 대응은 원인별로 다르다. 피드백 구조라면 목표 utilization을 현실적으로 올리거나 Pods/External 같은 총량 기반 지표(AverageValue)로 바꾼다. 노이즈라면 rate 윈도를 늘리거나 scaleDown 안정화 창을 600초 이상으로 키운다. 기동이 느리다면 readinessProbe와 startupProbe를 정확히 잡고, scaleUp policies의 periodSeconds를 파드 준비 시간보다 길게 둔다. 이렇게 하면 이전 증설 결과가 메트릭에 반영되기 전에 다음 증설이 일어나는 것을 막는다.

## 7. KEDA 도입 판단과 trade-off

KEDA 도입 판단은 스케일 대상 신호가 CPU로 표현되는지에 달려 있다. 큐, 스트림, 스케줄, 외부 SaaS 지표처럼 CPU 이전에 부하를 알 수 있는 경우나 0으로 줄여 비용을 절감해야 하는 워크로드에는 KEDA가 유리하다. 반대로 단순 웹 서비스는 metrics-server와 HPA만으로 충분하고, KEDA는 운영할 컨트롤 플레인 컴포넌트와 외부 시스템 인증 정보 관리라는 비용을 더한다. 스케일 투 제로는 콜드 스타트 지연이 사용자에게 보이므로 동기 API에는 보통 minReplicaCount를 1 이상으로 둔다. 일시적으로 자동 스케일을 멈추려면 `autoscaling.keda.sh/paused-replicas: "3"` 어노테이션으로 replicas를 고정할 수 있다.

## 8. 운영 전 점검 항목

운영 전 확인할 항목은 다음과 같다. 컨테이너 requests 설정 여부, PodDisruptionBudget과 스케일다운 정책의 정합성, 노드 오토스케일러 반응 시간, 외부 메트릭 소스 장애 시 fallback 동작, ScaledObject와 별도 HPA의 중복 여부다. 실제 수치(반응 시간, 비용 절감률)는 부하 시험으로 직접 측정해야 하며 일반화된 숫자는 존재하지 않는다.

## 참고

- Kubernetes 공식 문서: Horizontal Pod Autoscaling, HorizontalPodAutoscaler Walkthrough (kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
- Kubernetes 공식 문서: Resource metrics pipeline, Metrics Server (kubernetes.io/docs/tasks/debug/debug-cluster/resource-metrics-pipeline/)
- KEDA 공식 문서: Scaling Deployments, Scalers, ScaledObject specification (keda.sh/docs)
- KEDA 공식 문서: Kafka scaler, Prometheus scaler, Authentication (keda.sh/docs/latest/scalers/)
- kubernetes-sigs/prometheus-adapter 프로젝트 문서
