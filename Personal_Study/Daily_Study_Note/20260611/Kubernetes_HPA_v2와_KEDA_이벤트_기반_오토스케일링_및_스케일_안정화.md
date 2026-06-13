Notion 원본: https://www.notion.so/37e5a06fd6d38102b9f1fbe78e862827

# Kubernetes HPA v2와 KEDA 이벤트 기반 오토스케일링 및 스케일 안정화

> 2026-06-11 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- HPA v2의 메트릭 종류와 desired replica 계산식을 정확히 적용한다
- thrashing을 막는 stabilization window와 scaling policy를 설정한다
- KEDA의 ScaledObject가 외부 이벤트 소스로 0→N 스케일을 구현하는 원리를 설명한다
- HPA·KEDA·VPA·Cluster Autoscaler의 역할 분담과 충돌을 정리한다

## 1. HorizontalPodAutoscaler의 기본 원리

HPA는 워크로드(Deployment, StatefulSet 등)의 replica 수를 메트릭에 따라 자동 조절한다. 컨트롤러는 기본 15초 주기로 메트릭을 수집해 목표 replica를 계산하고 `scale` 서브리소스를 갱신한다. 핵심 계산식은 단순하다.

```text
desiredReplicas = ceil( currentReplicas × (currentMetricValue / desiredMetricValue) )
```

예를 들어 현재 3 replica에서 평균 CPU 사용률이 90%이고 목표가 60%라면, `ceil(3 × 90/60) = ceil(4.5) = 5` replica로 늘린다. 이 비례식이 HPA의 전부이며, 메트릭이 목표보다 높으면 늘리고 낮으면 줄인다. 단, 작은 변동에 과민 반응하지 않도록 0.1(10%)의 tolerance가 기본 적용되어, 비율이 0.9~1.1 사이면 스케일하지 않는다.

## 2. HPA v2의 메트릭 종류

autoscaling/v2 API는 네 종류 메트릭을 지원한다.

- **Resource**: Pod의 CPU/메모리. `Utilization`(request 대비 %) 또는 `AverageValue`(절대값).
- **Pods**: Pod당 평균 커스텀 메트릭(예: 초당 처리 요청 수). custom metrics API 필요.
- **Object**: 단일 객체에 연결된 메트릭(예: Ingress의 RPS).
- **External**: 클러스터 외부 메트릭(예: 클라우드 큐 길이). external metrics API 필요.

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
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
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

여러 메트릭을 동시에 지정하면 HPA는 각 메트릭으로 desired replica를 계산한 뒤 **그중 가장 큰 값**을 채택한다. 즉 어떤 신호든 스케일아웃이 필요하다고 하면 늘린다(보수적으로 안전 쪽). Resource 메트릭의 `Utilization`은 반드시 컨테이너에 `resources.requests`가 설정돼 있어야 계산되므로, request 누락은 HPA 무동작의 흔한 원인이다.

## 3. Thrashing과 Stabilization Window

오토스케일링의 고전적 문제는 thrashing, 즉 메트릭이 출렁일 때 replica가 늘었다 줄었다를 반복하는 현상이다. 스케일은 비용이 크다. 새 Pod 기동에는 이미지 풀·초기화 시간이 들고, 스케일인은 진행 중 요청을 끊을 수 있다. HPA v2는 `behavior` 필드로 이를 제어한다.

```yaml
spec:
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 최근 5분 중 최대 desired 를 사용
      policies:
        - type: Percent
          value: 50          # 한 번에 최대 50% 축소
          periodSeconds: 60
        - type: Pods
          value: 2           # 또는 한 번에 최대 2개 축소
          periodSeconds: 60
      selectPolicy: Min       # 가장 보수적인 정책 선택
    scaleUp:
      stabilizationWindowSeconds: 0    # 스케일업은 즉시
      policies:
        - type: Percent
          value: 100         # 한 번에 최대 2배
          periodSeconds: 30
        - type: Pods
          value: 4
          periodSeconds: 30
      selectPolicy: Max
```

`stabilizationWindowSeconds`는 그 시간 창 동안 계산된 desired replica들 중 (스케일다운은 최댓값을) 사용해 급격한 축소를 막는다. 기본값은 스케일다운 300초, 스케일업 0초다. 이 비대칭은 의도적이다. 부하 급증에는 빠르게 대응(즉시 스케일업)하되, 부하 감소에는 천천히 줄여(5분 관찰) 다시 늘어날 때의 콜드스타트를 피한다. `policies`는 한 주기당 변화 폭을 제한하고, `selectPolicy`로 여러 정책 중 어느 것을 적용할지 정한다.

## 4. HPA의 한계와 KEDA의 등장

HPA만으로는 부족한 지점이 있다. 첫째, HPA는 `minReplicas`를 1 미만으로 둘 수 없어 **0으로 스케일(scale-to-zero)** 이 안 된다. 유휴 시에도 최소 1 Pod가 떠 있어 비용이 든다. 둘째, 큐 길이·Kafka lag·클라우드 메트릭 같은 **이벤트 기반 소스**를 쓰려면 external metrics adapter를 직접 구축해야 해 번거롭다.

KEDA(Kubernetes Event-Driven Autoscaling)가 이 공백을 메운다. KEDA는 50종 이상의 scaler(Kafka, RabbitMQ, AWS SQS, Prometheus, Redis Streams, Azure Queue 등)를 내장하고, 이벤트 소스의 지표를 읽어 스케일을 구동한다. 중요한 점은 KEDA가 HPA를 대체하는 게 아니라 **그 위에 얹힌다**는 것이다. KEDA는 external metrics를 제공하는 metrics adapter 역할을 하며, 내부적으로 표준 HPA 객체를 생성해 1→N 스케일은 HPA에 위임한다.

## 5. KEDA ScaledObject 동작

KEDA의 핵심 리소스는 `ScaledObject`다. 트리거(이벤트 소스)와 스케일 대상, 범위를 선언한다.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: consumer-scaler
spec:
  scaleTargetRef:
    name: order-consumer        # 대상 Deployment
  minReplicaCount: 0            # scale-to-zero
  maxReplicaCount: 30
  cooldownPeriod: 300          # 0 으로 줄이기 전 유휴 대기
  pollingInterval: 15          # 트리거 폴링 주기(초)
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka:9092
        consumerGroup: order-group
        topic: orders
        lagThreshold: "100"     # consumer lag 100 당 1 replica
```

동작 흐름은 두 단계로 나뉜다. **0↔1 전환**은 KEDA Operator가 직접 담당한다. 이벤트가 없으면(예: Kafka lag 0) `cooldownPeriod` 후 Deployment를 0으로 줄이고, 이벤트가 도착하면 activation으로 1로 깨운다. **1↔N 스케일**은 KEDA가 생성한 HPA가 `lagThreshold` 기준 비례식으로 처리한다. 즉 KEDA는 "잠자고 깨우는" 게이트키퍼이자 external metrics 공급자이고, 일단 깨어난 뒤의 수평 확장은 익숙한 HPA 메커니즘 그대로다.

이 구조 덕분에 배치성·버스트성 워크로드(메시지 컨슈머, 이미지 처리 워커, 야간 배치)에서 유휴 시 비용을 0으로 만들면서도 부하 시 빠르게 확장할 수 있다. 콜드스타트 지연이 허용되는 비동기 워크로드에 특히 적합하다.

## 6. 스케일러 4종의 역할 분담

오토스케일링 컴포넌트가 여러 개라 역할을 구분해야 충돌을 피한다.

| 컴포넌트 | 조절 대상 | 축 | 비고 |
|---|---|---|---|
| HPA | replica 수 | 수평 | CPU/메모리/커스텀 메트릭 |
| KEDA | replica 수(0 포함) | 수평 + 이벤트 | 내부적으로 HPA 생성 |
| VPA | Pod의 request/limit | 수직 | 적정 리소스 산정 |
| Cluster Autoscaler | 노드 수 | 인프라 | Pending Pod 발생 시 노드 추가 |

가장 흔한 충돌은 **HPA와 VPA를 같은 메트릭(CPU)으로 동시에 쓰는 것**이다. HPA가 CPU% 기준으로 replica를 늘리는데 VPA가 동시에 request를 조정하면 분모가 바뀌어 서로의 계산을 어지럽힌다. 권장은 HPA는 CPU/커스텀 메트릭으로 수평 스케일, VPA는 CPU가 아닌 다른 리소스나 `recommendation` 모드로만 쓰는 식의 분리다.

또 HPA/KEDA가 replica를 늘려 Pod가 Pending이 되면 Cluster Autoscaler가 노드를 추가해야 실제로 스케줄된다. 즉 수평 스케일(Pod)과 인프라 스케일(노드)은 함께 설계해야 한다. maxReplicas만 키우고 노드 확장을 안 두면 Pod가 Pending에 머문다.

## 7. 운영 점검과 함정

오토스케일링이 기대대로 동작하지 않을 때 점검 순서는 다음과 같다.

```bash
# HPA 현재 상태와 메트릭 값 확인
kubectl describe hpa web-hpa

# metrics 파이프라인 살아있는지
kubectl top pods
kubectl get apiservices | grep metrics

# KEDA 트리거 상태
kubectl get scaledobject
kubectl describe scaledobject consumer-scaler
```

자주 만나는 함정을 정리하면, 첫째 `resources.requests` 누락으로 Utilization 계산 불가, 둘째 metrics-server 미설치로 `<unknown>` 표시, 셋째 KEDA에서 scale-to-zero 후 콜드스타트 지연이 SLA를 위반하는 경우(이때는 `minReplicaCount: 1`로 워밍 풀 유지), 넷째 stabilization window를 너무 짧게 잡아 thrashing이 재발하는 경우다. 메트릭 지연(스크래핑 간격)도 고려해야 한다. Prometheus 기반 메트릭은 수집·평가 지연으로 실제 부하보다 늦게 반영되므로, 스케일업 반응이 둔하면 폴링·스크래핑 주기를 점검한다.

## 8. 정리

Kubernetes 오토스케일링의 뼈대는 HPA의 단순한 비례식이고, `behavior`의 stabilization window와 policy가 그 위에 안정성을 더한다. KEDA는 HPA를 대체하지 않고 그 위에 이벤트 소스와 scale-to-zero 능력을 얹어, 비동기·버스트 워크로드의 비용과 탄력성을 동시에 잡는다. VPA와 Cluster Autoscaler는 각각 수직·인프라 축을 담당해 수평 스케일을 보완한다.

설계 지침을 요약하면, 동기 트래픽(웹 API)은 HPA + CPU/RPS 메트릭에 비대칭 stabilization(빠른 업, 느린 다운)을 적용하고, 비동기 컨슈머는 KEDA로 큐/lag 기반 스케일과 scale-to-zero를 쓰되 콜드스타트 SLA를 확인한다. HPA와 VPA를 같은 CPU 메트릭으로 겹치지 않게 하고, maxReplicas와 노드 확장(Cluster Autoscaler)을 함께 설계한다. 메트릭 파이프라인(requests 설정, metrics-server)이 살아 있어야 모든 것이 동작한다는 점을 잊지 않는 것이 출발점이다.

## 9. 실전 사례: Kafka 컨슈머 KEDA scale-to-zero

가장 자주 쓰는 KEDA 패턴이 메시지 컨슈머의 lag 기반 스케일이다. 동작과 함정을 구체적으로 보자.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: order-consumer
spec:
  scaleTargetRef:
    name: order-consumer
  minReplicaCount: 0
  maxReplicaCount: 50
  cooldownPeriod: 120
  pollingInterval: 10
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka:9092
        consumerGroup: order-group
        topic: orders
        lagThreshold: "500"
        offsetResetPolicy: latest
```

`lagThreshold: 500`은 "lag 500당 replica 1"을 의미한다. lag가 5000이면 `ceil(5000/500)=10` replica를 목표로 한다. 단, **maxReplicaCount는 토픽 파티션 수를 넘지 못한다**는 점이 중요하다. Kafka 컨슈머 그룹에서 한 파티션은 한 컨슈머만 읽으므로, 파티션이 30개인데 maxReplica를 50으로 둬도 31번째 이후 Pod는 할당받을 파티션이 없어 놀게 된다. KEDA 설정 전에 파티션 수를 확인해 max를 맞춰야 한다.

scale-to-zero의 함정도 있다. `minReplicaCount: 0`이면 lag가 0일 때 `cooldownPeriod` 후 모든 컨슈머가 사라진다. 이때 새 메시지가 오면 KEDA가 1로 깨우는데, Pod 기동 + 컨슈머 그룹 리밸런싱 시간(수 초~수십 초)만큼 첫 메시지 처리가 지연된다. 지연이 허용되는 배치성 워크로드면 비용 0이 큰 이점이지만, 낮은 지연이 필요하면 `minReplicaCount: 1`로 워밍 Pod를 유지한다.

운영 모니터링 관점에서 점검할 지표를 정리하면 다음과 같다.

| 지표 | 의미 | 경보 기준 |
|---|---|---|
| consumer group lag | 처리 지연 누적 | 지속 증가 시 max 부족·처리 병목 |
| replica 수 vs 파티션 수 | 과잉 스케일 | replica > 파티션이면 낭비 |
| Pod 기동 시간 | 콜드스타트 | SLA 대비 초과 시 minReplica 상향 |
| scaledobject Ready 상태 | 트리거 인증·연결 | False면 스케일 정지 |

이 사례는 KEDA의 강점(이벤트 기반·scale-to-zero)과 도메인 제약(Kafka 파티션 상한, 리밸런싱 지연)을 함께 보여준다. 오토스케일링은 메트릭만 연결하면 끝이 아니라, 대상 시스템의 동시성 모델(여기서는 파티션-컨슈머 1:1)을 이해해야 실효를 낸다. 도구의 비례식보다 워크로드의 물리적 제약을 먼저 파악하는 것이 안정적 스케일링의 출발점이다.

## 참고

- Kubernetes Docs — Horizontal Pod Autoscaling: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
- Kubernetes Docs — HPA Configurable Scaling Behavior: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/#configurable-scaling-behavior
- KEDA Documentation — Concepts & Scalers: https://keda.sh/docs/latest/concepts/
- Kubernetes Docs — Vertical Pod Autoscaler / Cluster Autoscaler: https://github.com/kubernetes/autoscaler
