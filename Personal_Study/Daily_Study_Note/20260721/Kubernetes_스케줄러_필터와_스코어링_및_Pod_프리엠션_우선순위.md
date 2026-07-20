Notion 원본: https://app.notion.com/p/3a35a06fd6d3814d96ebefffe5a7cc1e

# Kubernetes 스케줄러 필터와 스코어링 및 Pod 프리엠션 우선순위

> 2026-07-21 신규 주제 · 확장 대상: AWS

## 학습 목표

- kube-scheduler 의 스케줄링 사이클을 Filter/Score 두 단계로 나눠 설명한다
- 스케줄링 프레임워크의 확장점과 대표 플러그인의 역할을 구분한다
- PriorityClass 와 프리엠션이 리소스 부족 상황에서 어떻게 작동하는지 추적한다
- 실전에서 Pending Pod 를 진단하고 스케줄링 정책을 설계하는 기준을 세운다

## 1. 스케줄러가 하는 일과 사이클

kube-scheduler 는 노드 미배정 Pod 를 감시하다가 가장 적합한 노드를 골라 바인딩한다. Filtering 은 못 올리는 노드를 걸러 후보를 좁히고, Scoring 은 후보마다 점수를 매겨 최고점을 고른다.

## 2. Filtering 단계

`NodeResourcesFit` 은 Pod 의 requests 를 노드 allocatable 와 비교한다(limits 가 아닌 requests 기준). `NodeAffinity`, `TaintToleration`, `PodTopologySpread`, `VolumeBinding` 등이 함께 검사한다.

```yaml
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: node.kubernetes.io/accelerator
            operator: In
            values: ["nvidia-a100"]
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "gpu"
    effect: "NoSchedule"
```

## 3. Scoring 단계

`NodeResourcesBalancedAllocation` 은 균형, `NodeResourcesFit` 은 LeastAllocated(분산)와 MostAllocated(bin packing)로 나뉰다.

```yaml
apiVersion: kubescheduler.config.k8s.io/v1
kind: KubeSchedulerConfiguration
profiles:
- pluginConfig:
  - name: NodeResourcesFit
    args:
      scoringStrategy:
        type: MostAllocated
```

## 4. 스케줄링 프레임워크

확장점은 `PreFilter → Filter → PostFilter → PreScore → Score → Reserve → Permit → PreBind → Bind → PostBind` 다. PostFilter 에서 프리엠션이 동작하고, Permit 은 갱 스케줄링을 가능하게 한다.

## 5. PriorityClass 와 프리엠션

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata:
  name: high-priority
value: 1000000
preemptionPolicy: PreemptLowerPriority
```

높은 우선순위 Pod 가 노드를 못 찾으면 낮은 우선순위 Pod 를 쪽아내 공간을 확보한다.

## 6. 프리엠션의 미묘한 규칙

PDB 를 최대한 존중하지만 보장하지 않고, `preemptionPolicy: Never` 는 앞자리만 차지하며, 같거나 높은 우선순위 Pod 는 대상이 아니다. QoS(Guaranteed > Burstable > BestEffort)도 축출 순서에 영향을 준다.

## 7. Pending Pod 진단

```bash
kubectl describe pod <pod> | sed -n '/Events/,$p'
kubectl get events -A --field-selector reason=FailedScheduling --sort-by=.lastTimestamp
```

requests 과대가 만성 Pending 의 가장 흔한 원인이며, VPA 권고치나 실사용량으로 현실화하는 것이 정공법이다.

## 8. 정책 설계와 트레이드오프

가용성(topology spread)과 비용(bin packing)은 상충한다. 우선순위를 남용하면 기아가 생긴다. 우선순위 클래스를 소수로 유지하고, 상태 서비스는 spread, 배치는 bin packing, requests 는 실측 기반으로 정직하게 잡는다. 좋은 스케줄링은 좋은 리소스 선언에서 시작한다.

## 참고

- Kubernetes 공식 문서: Scheduling Framework, kube-scheduler
- Kubernetes 공식 문서: Pod Priority and Preemption
- Kubernetes 공식 문서: Assigning Pods to Nodes, Taints and Tolerations
- KubeSchedulerConfiguration v1 API 레퍼런스
