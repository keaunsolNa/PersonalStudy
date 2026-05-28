Notion 원본: https://www.notion.so/36e5a06fd6d38181b3b3f6bf4f326b18

# Kubernetes Pod Topology Spread Constraints와 maxSkew, TopologyKey

> 2026-05-28 신규 주제 · 확장 대상: Kubernetes — Pod 분산 배치와 가용영역 균형

## 학습 목표

- topologySpreadConstraints 의 4 핵심 필드 (maxSkew, topologyKey, whenUnsatisfiable, labelSelector) 의 정확한 의미를 정리한다.
- AZ / Node / Rack 단위 균형 배포가 스케줄러 score 에 어떻게 반영되는지 추적한다.
- nodeAffinityPolicy, nodeTaintsPolicy, matchLabelKeys, minDomains (1.27+) 옵션의 동작을 정리한다.
- ClusterAutoscaler / Karpenter 와 함께 동작할 때의 함정과 디버깅 패턴을 익힌다.

## 1. 배경 — podAntiAffinity 의 한계

podAntiAffinity 는 binary 규칙. "4개 Pod 를 3개 zone 에 균형" 같은 정량적 요구 표현 불가. 1.18 GA topologySpreadConstraints 가 *skew* 개념 도입.

## 2. 정의와 핵심 필드

```yaml
spec:
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: DoNotSchedule
      labelSelector:
        matchLabels:
          app: api
```

maxSkew: 도메인 간 matching pod 수 차이 최대 허용치. whenUnsatisfiable: DoNotSchedule(hard) / ScheduleAnyway(soft).

## 3. 동작 예시 — Zone 균형 배포

replicas=6, 3 zone, maxSkew=1, hard 일 때 (2,2,2) 강제. 짝수 분할에 가까운 배치.

## 4. Hard vs Soft

DoNotSchedule: Pending. ScheduleAnyway: best-effort. 운영은 zone hard + hostname soft 패턴.

## 5. 1.25+ 신규 옵션

nodeAffinityPolicy/nodeTaintsPolicy (1.26+) Honor: 실제 갈 수 있는 노드만 spread 계산. minDomains (1.27 GA): 최소 도메인 수 강제. matchLabelKeys (1.27+): pod-template-hash 로 RS 단위 독립 spread.

## 6. ClusterAutoscaler / Karpenter 상호작용

Hard spread 불만족 → Pending → 새 노드 프로비저닝. Karpenter v0.31+ 부팁 TopologySpread 인식. spare capacity Deployment 로 배포 지연 해소.

## 7. 디버깅

```
kubectl get pods -l app=api -o wide
kubectl get nodes -L topology.kubernetes.io/zone
kubectl describe pod ... | tail -20
```

"didn't match topology spread constraints" 메시지가 핵심.

## 8. 실측 — 균형 효과

```
EKS 3 AZ, m6i.xlarge x 9, replicas=30

constraint 없음        : zone-a:15, b:9, c:6
podAntiAffinity preferred : 13/11/6
spread maxSkew=1 hard    : 10/10/10  ← 1 AZ 장애 시 33% 손실
```

## 9. Trade-off 가이드

| 패턴 | 상황 | 주의 |
|---|---|---|
| zone hard, hostname soft | 일반 API | 다중 zone 필수 |
| zone hard + minDomains | 3 AZ SLA | zone 2개로 축소 시 전부 Pending |
| matchLabelKeys | Rolling update | RS 단위 skew |
| ScheduleAnyway only | 비용 우선 | best-effort |
| spread + nodeAffinity | 노드풀 한정 | nodeAffinityPolicy=Honor |

설계 원칙 — zone-level hard 1개로 시작. 운영 후 Pending 빈도 보고 소프트 추가.

## 10. defaultConstraints

scheduler config 에서 기본 spread 강제. 매니페스트에 없을 때만 적용. ScheduleAnyway 로 두어 capacity 부족 Pending 방지.

## 11. ServiceMesh / Ingress

sidecar 는 라벨 다르므로 영향 없음. ingress controller 는 별도 챙김 필요. ALB/NLB cross-zone load balancing 옵션 활성화.

## 참고

- Kubernetes Documentation — Pod Topology Spread Constraints
- KEP-895, KEP-3022, KEP-3243
- Karpenter TopologySpread Awareness
- "Production Kubernetes" O'Reilly
