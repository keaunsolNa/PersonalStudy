Notion 원본: https://www.notion.so/34b5a06fd6d3811baacbc7f14aeabb07

# Kubernetes HPA와 커스텀 메트릭 기반 오토스케일링

> 2026-04-23 신규 주제 · 확장 대상: DevOps (AWS / Docker & CI 학습됨)

## 학습 목표

- HPA v2의 metric 종류(Resource, Pods, Object, External)를 직접 정의해 동작시킨다
- metrics-server와 prometheus-adapter 파이프라인의 역할 분담을 이해한다
- CPU/메모리가 아닌 애플리케이션 지표(RPS, 큐 길이)로 스케일링하는 YAML을 작성한다
- 스케일 업/다운의 stabilization window와 behavior 정책을 실측 기반으로 튜닝한다

---

## 1. HPA는 무엇을 어떻게 결정하는가

Horizontal Pod Autoscaler 컨트롤러는 기본 15초 주기로 `metrics.k8s.io`, `custom.metrics.k8s.io`, `external.metrics.k8s.io` 세 API 서버에 질의해 각 메트릭의 현재값을 가져온다. 이후 `desiredReplicas = ceil[ currentReplicas * ( currentMetric / targetMetric ) ]`로 계산. 특이사항: (1) 현재 메트릭이 10% 이내로 target에 근접하면 변경하지 않음(tolerance), (2) 스케일 다운은 stabilization window(기본 5분) 동안 가장 높은 desired 값을 썼.

이 tolerance와 stabilization 때문에 HPA는 "너무 예민하게 덜컥거리지 않도록" 의도적으로 보수적이다. 트래픽 급변이 잦은 서비스는 이 두 값을 낮춰야 반응성이 붙는다.

## 2. 메트릭 파이프라인 구성

| 메트릭 종류 | API | 제공자 |
|---|---|---|
| Resource (CPU/mem) | metrics.k8s.io | metrics-server |
| Pods / Object | custom.metrics.k8s.io | prometheus-adapter |
| External | external.metrics.k8s.io | keda, prometheus-adapter |

metrics-server는 kubelet summary API에서 cadvisor 데이터를 수집해 짧은 윈도우 평균만 제공한다. 히스토리 저장용이 아니다. prometheus-adapter는 Prometheus에 쌏여 있는 메트릭을 PromQL로 질의해 `custom.metrics.k8s.io` API 형태로 재노출한다.

## 3. 기본: CPU 기반 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api
  minReplicas: 3
  maxReplicas: 30
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

`averageUtilization: 60`은 컨테이너의 `requests.cpu` 대비 비율. requests가 너무 크면 HPA가 절대 트리거되지 않는다. requests를 실측 p50 CPU의 1.3~1.5배로 잡는 것이 경험적으로 균형이 좋다.

## 4. Prometheus 메트릭 기반 HPA

```yaml
# prometheus-adapter ConfigMap
rules:
  custom:
    - seriesQuery: 'http_requests_total{namespace!="",pod!=""}'
      resources:
        overrides:
          namespace: {resource: "namespace"}
          pod:       {resource: "pod"}
      name:
        matches: "^(.*)_total$"
        as: "${1}_per_second"
      metricsQuery: 'sum(rate(<<.Series>>{<<.LabelMatchers>>}[2m])) by (<<.GroupBy>>)'
```

```yaml
metrics:
    - type: Pods
      pods:
        metric: { name: http_requests_per_second }
        target:
          type: AverageValue
          averageValue: "200"
```

`Pods` 타입은 자동으로 대상 Deployment의 pod 개수로 나눠 평균을 내 target과 비교한다. "pod 한 개당 200 RPS"를 목표로 붙이면, 현재 총 RPS가 1000이면 desired = 5 pod.

## 5. 큐 길이 기반(External) 스케일링

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: worker-sqs
spec:
  scaleTargetRef:
    name: worker
  minReplicaCount: 1
  maxReplicaCount: 30
  triggers:
    - type: aws-sqs-queue
      metadata:
        queueURL: https://sqs.ap-northeast-2.amazonaws.com/12345/orders
        queueLength: "30"
        awsRegion: ap-northeast-2
```

KEDA의 유용한 특징은 0으로 스케일 다운이 가능하다는 점. 콜드 스타트를 감당할 수 있는 배치/워커 워크로드에 적합. KEDA 내부적으로는 `external.metrics.k8s.io` API를 자체 구현해 HPA 컨트롤러가 평소처럼 돌 수 있게 해 준다.

## 6. behavior 로 스케일링 속도 조절

```yaml
spec:
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
        - type: Pods
          value: 8
          periodSeconds: 30
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
      selectPolicy: Max
```

급격한 트래픽 스파이크가 있는 서비스는 scaleUp을 공격적으로, scaleDown을 느리게 잡는다. Percent + Pods 정책을 함께 넣고 `selectPolicy: Max`로 두면 "초기 소수 pod일 때는 절대 수치로, 이미 많을 때는 비율로" 자연스럽게 바뀐다.

## 7. 실측 튜닝 체크리스트

1. `kubectl describe hpa <name>`의 Events에 `FailedGetResourceMetric`이 있는가 → metrics-server 문제
2. `kubectl top pod`가 값을 반환하는가
3. `status.currentMetrics`가 기대값인가 → prometheus-adapter rule mismatch
4. 대상 Deployment의 readiness probe가 너무 늦게 ready되는가 → HPA가 desired를 올려도 ready pod 수가 늘지 않음
5. requests/limits가 현실적인가

위 순서로 체크하면 90% 문제는 4번 안에서 걸린다.

## 8. VPA와의 관계

HPA(CPU)와 VPA(CPU)를 같이 쓰면 서로 싸운다. HPA는 "현재 평균 CPU가 높으면 pod를 늘린다", VPA는 "평균 CPU가 높으면 requests를 올린다" — 결과적으로 request가 올라가면 utilization이 떨어져 HPA가 오히려 스케일 다운을 시도한다. 권장 패턴은 HPA는 커스텀 메트릭(RPS/queue)으로 돌리고, VPA는 권장값만 출력(Off 모드)해서 사람이 PR로 반영하는 것.

## 참고

- Kubernetes 공식 문서 — Horizontal Pod Autoscaler: https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/
- prometheus-adapter: https://github.com/kubernetes-sigs/prometheus-adapter
- KEDA 공식 문서: https://keda.sh/docs/
- Kubernetes SIG Autoscaling — HPA v2 API reference
