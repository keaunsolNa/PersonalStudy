Notion 원본: https://www.notion.so/3675a06fd6d381138129f4475e741a38

# Kubernetes VPA(Vertical Pod Autoscaler) Recommender와 Goldilocks

> 2026-05-21 신규 주제 · 확장 대상: K8s HPA v2 Behavior(20260518), Karpenter Consolidation(20260516)

## 학습 목표

- VPA의 Recommender / Updater / Admission Controller 3-tier 아키텍처와 책임 경계.
- Recommender의 percentile 기반 자원 추천 알고리즘과 weighted decay.
- HPA와 VPA의 양립 가능 조건과 disabling-overlap 정책.
- Goldilocks advisory tool로 VPA off 환경에서도 안전한 sizing 획득.

## 1. VPA의 3-tier 아키텍처

3개 컴포넌트 협력. Recommender(추천값 계산), Updater(pod evict), Admission Controller(pod 생성 시 mutation). `updateMode: Off`로 추천만 받고 수동 적용이 production 안전 패턴.

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api-server
  updatePolicy: { updateMode: 'Off' }
  resourcePolicy:
    containerPolicies:
      - containerName: '*'
        minAllowed: { cpu: 100m, memory: 128Mi }
        maxAllowed: { cpu: 4, memory: 8Gi }
        controlledResources: ['cpu', 'memory']
```

## 2. Recommender percentile 알고리즘

`DecayingHistogram`. 250 bucket에 weighted hit, exponential decay(half-life 24h). target=P90, lowerBound=P50, upperBound=P95. P90이 OOM 방지 + 자원 낭비 방지의 경험적 균형.

## 3. Memory 추천의 bumping

OOMKilled 감지 시 메모리 추천 즉시 1.2배 bump. 1Gi → 1.2Gi. 메모리 leak 있으면 무한 bump → maxAllowed.memory 상한 critical.

## 4. HPA와 VPA 양립

| HPA metric | VPA mode | 양립? |
|---|---|---|
| CPU util | Initial/Off/Recreate | ❌ 진동 |
| Custom (RPS, queue) | Auto on CPU/memory | ✅ 표준 |
| Custom | Off | ✅ 안전 |

표준은 HPA는 비즈니스 metric으로 horizontal, VPA는 CPU/memory로 vertical.

## 5. In-place Resize

K8s 1.27 베타. CPU는 cgroup write로 즉시. memory shrink는 1.30 GA. 운영은 in-place increase만 허용, shrink는 recreate.

## 6. PDB 협조

PDB 없으면 모든 replica 동시 evict. 자체 경험 PDB 없이 Auto 켰다가 30 replica evict → 5분 outage.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
spec:
  minAvailable: 80%
  selector: { matchLabels: { app: api-server } }
```

## 7. Goldilocks

Fairwinds Goldilocks: namespace 단위 VPA 자동 생성 + dashboard. Auto 위험 없이 권고만.

```bash
helm install goldilocks fairwinds-stable/goldilocks --namespace goldilocks --create-namespace
kubectl label ns api-namespace goldilocks.fairwinds.com/enabled=true
```

권고: guaranteed(request=limit, OOM 안전), burstable(request P90, limit P99). stateful은 guaranteed, stateless API는 burstable.

## 8. 자체 측정 비용 효과

| 구분 | CPU% | mem% | 월 비용 |
|---|---|---|---|
| 적용 전 | 23 | 41 | $18,400 |
| Goldilocks 수동 | 38 | 56 | $14,200 |
| Auto + Karpenter | 51 | 68 | $11,100 |

VPA Auto로 38% 절감 가능하지만 분기 OOMKilled 3건, restart 12건.

## 9. 운영 권장

- 초기: 모두 Off + Goldilocks dashboard만
- 중기: stateless만 Auto, PDB + maxAllowed 강제
- 성숙: in-place + Karpenter consolidation

stateful(DB·Redis·Kafka) Auto 절대 금지. StatefulSet은 `updateMode: Initial`. 비정기 spike 잦은 서비스는 VPA disable + HPA horizontal에만 의존.

## 10. JVM 워크로드 특수 고려

가장 사고 내기 쉬운 워크로드. `-Xmx`가 컨테이너 75%인데 VPA가 메모리 줄이면 limit 초과 OOMKilled.

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=75
      -XX:InitialRAMPercentage=50
```

`-XX:MaxRAMPercentage`로 동적 결정. in-place resize 동작해도 JVM heap은 시작 시점 유지·즉시 반영 안됨. VPA Auto + JVM은 pod recreate 필요, cold-start 비싼 앱은 Off + 분기 수동 sizing. G1GC `-XX:G1HeapRegionSize`도 heap 크기 따라 조정.

## 참고

- VPA repo — github.com/kubernetes/autoscaler/tree/master/vertical-pod-autoscaler
- KEP-1287 In-place Update of Pod Resources
- Goldilocks — goldilocks.docs.fairwinds.com/
- 'Cloud Native DevOps with Kubernetes' 2nd ed — Justin Domingus
- KubeCon EU 2025 — VPA in production
