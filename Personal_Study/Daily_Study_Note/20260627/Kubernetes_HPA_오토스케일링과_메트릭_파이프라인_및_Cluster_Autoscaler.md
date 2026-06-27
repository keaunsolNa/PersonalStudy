Notion 원본: https://app.notion.com/p/38c5a06fd6d381a4a44ff987062260ff

# Kubernetes HPA 오토스케일링과 메트릭 파이프라인 및 Cluster Autoscaler

> 2026-06-27 신규 주제 · 확장 대상: DevOps(Kubernetes)

## 학습 목표

- HPA의 desiredReplicas 계산식과 메트릭 수집 경로를 안다.
- HPA·VPA·Cluster Autoscaler 세 계층을 구분한다.
- stabilization window와 scaling policy로 진동을 막는다.
- requests/limits·PDB와 오토스케일링의 상호작용을 이해한다.

## 1. 세 계층

HPA는 파드 개수, VPA는 파드 requests, Cluster Autoscaler는 노드 수를 조절한다. HPA가 파드를 늘렸는데 자원이 부족하면 Pending, 이때 CA가 노드를 추가한다. HPA와 VPA는 같은 CPU를 동시 조절하면 충돌한다.

## 2. desiredReplicas

desiredReplicas = ceil(currentReplicas × (currentMetric / targetMetric)). 여러 메트릭은 가장 큰 값 채택.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: api }
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 50 }
```

CPU Utilization은 requests 대비 사용률이므로 requests가 없으면 HPA가 동작하지 않는다.

## 3. 메트릭 파이프라인

Resource Metrics API(metrics-server), Custom Metrics API(Prometheus Adapter), External Metrics API(SQS·Kafka lag). KEDA로 이벤트 기반·0→1 스케일링.

## 4. behavior — 진동 방지

기본 스케일 다운 안정화 300초, 관측된 desiredReplicas 중 최댓값 채택. "빨리 늘리고 천천히 줄인다".

```yaml
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies: [{ type: Percent, value: 50, periodSeconds: 60 }]
    scaleUp:
      stabilizationWindowSeconds: 0
      policies: [{ type: Percent, value: 100, periodSeconds: 30 }]
      selectPolicy: Max
```

## 5. Cluster Autoscaler

CA는 실제 사용률이 아니라 파드 requests 합을 기준으로 판단한다. PDB가 축소 중 가용성을 보호한다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: api-pdb }
spec:
  minAvailable: 2
  selector: { matchLabels: { app: api } }
```

Karpenter는 Pending 파드 요구에 맞춰 인스턴스를 직접 선택한다.

## 6. 상호작용

| 컴포넌트 | 조절 대상 | 위험 |
| --- | --- | --- |
| HPA | 파드 수 | requests 누락 시 미동작 |
| VPA | 파드 requests | HPA와 CPU 충돌, 재시작 |
| Cluster Autoscaler | 노드 수 | requests 부정확 시 과/소 프로비전 |

## 7. VPA 모드와 HPA 충돌 회피

VPA는 파드를 재시작해야 변경이 반영된다. 권장: HPA는 CPU·커스텀 수평, VPA는 메모리 requests만.

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata: { name: api-vpa }
spec:
  targetRef: { apiVersion: apps/v1, kind: Deployment, name: api }
  updatePolicy: { updateMode: "Initial" }
  resourcePolicy:
    containerPolicies:
      - containerName: api
        controlledResources: ["memory"]
```

## 8. 스케일링 타임라인

메트릭 수집(15초) → HPA 평가(15초) → 파드 기동·readiness(수십초~분) → CA 노드 추가. over-provisioning(음수 우선순위 placeholder)으로 콜드 스타트를 흡수한다.

## 9. 안티패턴

메모리 기준 HPA는 GC·캐시 때문에 스케일 다운이 안 돼 비용이 고정될 수 있다. throttling 비율과 Pending 파드 수를 대시보드로 본다.

## 참고

- Kubernetes 공식 문서 — Horizontal Pod Autoscaling, behavior(v2)
- Kubernetes — Resource Metrics Pipeline, Custom/External Metrics APIs
- Cluster Autoscaler FAQ, Karpenter 공식 문서
- KEDA 공식 문서
