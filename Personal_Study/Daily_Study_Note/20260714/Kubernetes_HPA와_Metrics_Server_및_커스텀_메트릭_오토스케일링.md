Notion 원본: https://app.notion.com/p/39d5a06fd6d38149ade2daae9fc2c606

# Kubernetes HPA와 Metrics Server 및 커스텀 메트릭 오토스케일링

> 2026-07-14 신규 주제 · 확장 대상: Kubernetes

## 학습 목표

- HPA 컨트롤러의 조정 루프와 목표 레플리카 계산식을 정확히 이해한다
- Metrics Server가 리소스 메트릭을 수집·노출하는 경로(metrics.k8s.io)를 확인한다
- 커스텀·외부 메트릭 API로 CPU 외 신호(QPS, 큐 길이)로 스케일하는 구성을 파악한다
- 스케일 안정화·플래핑 방지와 HPA/VPA/Cluster Autoscaler 역할 분리를 구분한다

## 1. HPA 조정 루프의 기본

HPA는 기본 15초마다 메트릭을 읽어 레플리카 수를 조정한다. 핵심은 비례식 하나다.

```
desiredReplicas = ceil( currentReplicas × (currentMetricValue / desiredMetricValue) )
```

현재 3개가 평균 CPU 90%, 목표 60%면 ceil(3 × 90/60) = 5개로 늘린다. HPA가 동작하려면 `resources.requests`가 반드시 설정돼 있어야 한다. CPU 사용률은 사용량/request로 계산되므로 request가 없으면 사용률을 산출하지 못해 스케일이 안 된다.

## 2. Metrics Server와 metrics.k8s.io

각 노드의 kubelet은 cAdvisor로 리소스 사용량을 수집하고, Metrics Server가 kubelet Summary API를 긁어 집계해 metrics.k8s.io로 노출한다. `kubectl top`과 HPA가 이를 소비한다.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: web }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: web }
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 60 }
```

Metrics Server는 순간 사용량만 메모리에 유지하는 경량 컴포넌트로, 장기 시계열은 Prometheus가 담당한다.

## 3. 세 개의 메트릭 API 계층

autoscaling/v2 HPA는 Resource(metrics.k8s.io), Pods/Object(custom.metrics.k8s.io), External(external.metrics.k8s.io) 세 소스를 지원한다. 뒤 두 API는 Prometheus Adapter나 KEDA가 채운다. CPU가 부하와 비례하지 않는 I/O 바운드·큐 소비자·웹소켓은 QPS나 큐 적체로 스케일해야 한다.

## 4. 커스텀 메트릭으로 스케일하기

Prometheus Adapter는 시계열을 custom.metrics.k8s.io로 변환해 HPA에 노출한다.

```yaml
metrics:
  - type: Pods
    pods:
      metric: { name: http_requests_per_second }
      target: { type: AverageValue, averageValue: "100" }
```

큐 소비자라면 외부 메트릭으로 큐 길이를 읽어 스케일한다.

```yaml
metrics:
  - type: External
    external:
      metric:
        name: sqs_queue_depth
        selector: { matchLabels: { queue: orders } }
      target: { type: AverageValue, averageValue: "30" }
```

KEDA는 Kafka lag·SQS depth·Cron 등 수십 스케러를 제공하고 트래픽이 없을 때 0개까지 축소(scale-to-zero)한다. 순수 HPA는 0으로 축소하지 못한다.

## 5. 플래핑 방지: 안정화 윈도우와 정책

HPA는 기본 10% 허용 오차를 두고, `behavior` 필드로 방향별 안정화 윈도우와 변화 속도를 제어한다.

```yaml
behavior:
  scaleDown:
    stabilizationWindowSeconds: 300
    policies:
      - { type: Percent, value: 50, periodSeconds: 60 }
  scaleUp:
    stabilizationWindowSeconds: 0
    policies:
      - { type: Pods, value: 4, periodSeconds: 60 }
```

기본 정책은 축소를 보수적(5분 안정화), 확장을 공격적(즉시)으로 잡는다. 축소 안정화 윈도우 동안 HPA는 그 구간의 최대 desired를 선택한다.

## 6. HPA/VPA/Cluster Autoscaler 역할 분리

| 오토스케일러 | 조정 대상 | 신호 |
|---|---|---|
| HPA | 레플리카 수 | CPU/메모리/커스텀 |
| VPA | 파드 request/limit | 과거 사용량 |
| Cluster Autoscaler | 노드 수 | 스케줄 불가 파드/유휴 노드 |

HPA가 파드를 늘렸는데 스케줄할 노드가 부족하면 Cluster Autoscaler가 노드를 추가한다. 주의: HPA와 VPA를 같은 리소스(CPU)에 동시 적용하면 서로의 신호를 오염하므로 피해야 한다.

## 7. 실측 감각과 튜닝

동기 주기 15초 + Metrics Server 수집 지연이 겹쳐 스파이크가 스케일로 이어지기까지 수십 초 지연이 있다. 지연 SLO가 있는 서비스는 목표를 50~60%로 낮춰 버퍼를 확보하는 것이 안전하다.

## 7.5. 다중 메트릭과 스케일 결정 규칙

HPA는 메트릭 배열에 여러 메트릭을 둘 수 있고, 각각이 독립적으로 목표 레플리카를 계산해 그중 가장 큰 값을 채택한다. CPU 기준 5개, QPS 기준 8개면 8개로 스케일한다. 함정은 준비 안 된 파드 처리다. HPA는 워밍 중인 파드를 보정하는데, readinessProbe가 부정확하면 새 파드가 평균을 끔어내려 방금 늘린 파드를 다시 줄이는 진동을 일으킨다.

## 8. 도입 판단

CPU가 부하와 잘 비례하는 stateless 웹 서비스라면 Resource 메트릭 HPA + Metrics Server만으로 충분하다. 지연·큐가 CPU와 어긋나면 Prometheus Adapter나 KEDA를 도입한다. request 설정, Cluster Autoscaler 병행, behavior 튜닝이 실전 안정성의 세 기둥이다.

## 참고

- Kubernetes — Horizontal Pod Autoscaling: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
- Kubernetes — HPA Walkthrough & behavior: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale-walkthrough/
- Kubernetes SIG — Metrics Server: https://github.com/kubernetes-sigs/metrics-server
- KEDA Documentation: https://keda.sh/docs/latest/concepts/
