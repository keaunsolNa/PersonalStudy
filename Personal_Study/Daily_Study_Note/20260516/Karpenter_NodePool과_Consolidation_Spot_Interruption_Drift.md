Notion 원본: https://www.notion.so/3615a06fd6d38167a87ee552c6c9ca23

# Karpenter NodePool과 Consolidation — Spot Interruption, Disruption, Drift 처리

> 2026-05-16 신규 주제 · 확장 대상: Kubernetes / DevOps (오토스케일링)

## 학습 목표

- Karpenter v1 의 NodePool / EC2NodeClass / NodeClaim 리소스 모델과 cluster-autoscaler 와의 차이를 식별한다
- Just-in-time 노드 프로비저닝 흐름과 binpacking 휴리스틱이 어떤 instance type 을 선택하는지 추적한다
- Consolidation(WhenEmpty / WhenUnderutilized), Drift, Spot Interruption 의 disruption budget 적용 순서를 설명한다
- 운영에서 흔히 빠지는 함정(PDB 무시되는 케이스, AMI drift, Spot 회수 폭주) 을 코드/명령으로 진단한다

## 1. Karpenter 의 위치 — Cluster Autoscaler 와의 본질 차이

기존 Cluster Autoscaler(CAS) 는 *Auto Scaling Group(ASG)* 또는 *Managed Node Group* 같은 *고정 인스턴스 타입 그룹*의 desired count 를 조절하는 컨트롤러다. 새 노드가 필요하면 ASG 에 "+1" 을 요청하고, AWS 가 사전 정의된 instance type 의 노드 하나를 띄운다. CAS 자체는 어떤 instance type 인지 *결정하지 않는다*.

Karpenter 는 이 모델을 뒤집는다. ASG 를 거치지 않고 *RunInstances API* 를 직접 호출해 노드를 띄운다. 그래서 *대기 중인 파드 스펙* 을 보고 가장 적합한 instance type 을 동적 선택할 수 있다.

| 비교 항목 | Cluster Autoscaler | Karpenter |
|---|---|---|
| 인스턴스 타입 결정 | ASG 사전 정의 | 파드 요구사항 보고 동적 선택 |
| 프로비저닝 latency | 60~120초 | 30~60초 (ASG 우회) |
| binpacking | ASG 별로 분리 | 단일 알고리즘으로 통합 |
| diversity | ASG 분리 필요 | NodePool 안에서 multi-instance, multi-AZ, multi-arch 가능 |
| Spot 회수 처리 | reactive | 사전 통지(2분 warning + EventBridge) 받아 graceful drain |

## 2. v1 리소스 모델 — NodePool / EC2NodeClass / NodeClaim

Karpenter v1 (2024 GA) 는 v0 의 Provisioner / AWSNodeTemplate 을 NodePool / EC2NodeClass 로 리네임했다.

```yaml
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: default
spec:
  template:
    metadata:
      labels:
        team: backend
    spec:
      requirements:
        - key: karpenter.k8s.aws/instance-category
          operator: In
          values: ['c', 'm', 'r']
        - key: karpenter.sh/capacity-type
          operator: In
          values: ['spot', 'on-demand']
      nodeClassRef:
        group: karpenter.k8s.aws
        kind: EC2NodeClass
        name: default
      taints:
        - key: dedicated
          value: gpu
          effect: NoSchedule
  disruption:
    consolidationPolicy: WhenEmptyOrUnderutilized
    consolidateAfter: 30s
    budgets:
      - nodes: '10%'
  limits:
    cpu: 1000
    memory: 1000Gi
```

```yaml
apiVersion: karpenter.k8s.aws/v1
kind: EC2NodeClass
metadata:
  name: default
spec:
  amiFamily: AL2023
  amiSelectorTerms:
    - alias: al2023@latest
  subnetSelectorTerms:
    - tags:
        karpenter.sh/discovery: cluster-x
  securityGroupSelectorTerms:
    - tags:
        karpenter.sh/discovery: cluster-x
  role: KarpenterNodeRole-cluster-x
  blockDeviceMappings:
    - deviceName: /dev/xvda
      ebs:
        volumeSize: 100Gi
        volumeType: gp3
```

NodeClaim 은 컨트롤러가 *자동 생성*하는 내부 리소스. 노드 lifecycle 상태와 1:1 매핑된 ownership 객체.

## 3. 노드 프로비저닝 흐름 — binpacking 휴리스틱

```
1. unscheduled pod 목록 수집
2. 각 NodePool 의 requirements 와 파드의 nodeSelector/affinity/taints 매칭
3. 후보 instance type 목록 생성
4. binpacking: 파드 요구 리소스를 instance 당 utilization 80~90% 를 목표로 묶기
5. 가장 저렴한 가격(Spot 우선, On-Demand fallback) 의 instance type 선택
6. RunInstances API 호출, NodeClaim 생성
7. 노드 join 후 파드 binding
```

binpacking 은 *first-fit-decreasing* 의 변형. 가격 최적화 알고리즘은 EC2 의 *최근 Spot 가격*과 *interruption rate* 를 결합한 score 를 계산.

## 4. Consolidation — 비용 절감의 핵심

| 정책 | 동작 |
|---|---|
| `WhenEmpty` | 노드에 user 파드가 0개일 때 제거 |
| `WhenUnderutilized` | 파드를 다른 노드로 재배치해 노드 제거 가능하면 수행 |
| `consolidateAfter` | 위 조건 만족 후 대기 시간 |

```yaml
disruption:
  consolidationPolicy: WhenEmptyOrUnderutilized
  consolidateAfter: 30s
```

내부 알고리즘은 *cost-driven simulator*. PDB 위반 시 후보에서 제외. 운영 사례: 30개 노드 → 22개, 월 EC2 27% 절감.

## 5. Disruption Budget

```yaml
disruption:
  budgets:
    - nodes: '10%'
    - nodes: '0'
      schedule: '@daily'
      duration: 4h
      reasons: ['Drifted']
```

| 이유 | 의미 |
|---|---|
| `Underutilized` | consolidation 으로 인한 교체 |
| `Empty` | 빈 노드 제거 |
| `Drifted` | NodeClass/NodePool 변경 또는 AMI 업데이트 |
| `Expired` | TTL 만료(`expireAfter`) |

운영적으로 *Drifted* 는 가장 주의해야 한다. AMI 자동 업데이트 시 클러스터 전체 노드가 동시 drain 될 수 있다.

## 6. Spot Interruption Handling

AWS 가 Spot instance 회수를 2분 전 알림으로 통지(EventBridge `EC2 Spot Instance Interruption Warning`). Karpenter 는 이 이벤트를 SQS 큐로 받아 처리한다.

```yaml
settings:
  interruptionQueue: karpenter-cluster-x
```

```
1. SQS 메시지 수신
2. 해당 노드를 cordon
3. graceful drain 시작
4. PDB 위반 시 timeout 까지 대기 또는 강제 evict
5. 대체 NodeClaim 사전 프로비저닝
6. 모든 파드 evict 완료 후 노드 termination
```

terminationGracePeriodSeconds 를 30~60초로 두는 게 일반적.

## 7. Drift 와 AMI 업데이트의 함정

```yaml
amiSelectorTerms:
  - alias: al2023@latest
```

`@latest` 는 AWS SSM 파라미터에서 최신 AMI ID 를 매일 polling. 새 AMI 릴리즈 시 전체 노드 Drift.

운영 함정 1: 새 AMI 에 kernel/CRI 변경 시 일부 워크로드가 깨질 수 있음. 권장은 *명시적 AMI ID* 로 고정.

```yaml
amiSelectorTerms:
  - id: ami-0abcd1234efgh5678
```

운영 함정 2: PDB 엄격하면 Drift split brain 상태가 지속.
운영 함정 3: NodePool requirements 살짝 변경에도 전체 Drift. CI 에 NodePool YAML diff 검토 게이트.

## 8. PDB(PodDisruptionBudget) 와 Karpenter 의 상호작용

Karpenter 는 *kube-apiserver 의 Eviction API* 를 통해 파드를 옮긴다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: order-service
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: order-service
```

consolidation 시뮬레이션이 PDB 고려. PDB *없음* 은 thundering herd 위험. OPA Gatekeeper / Kyverno 로 강제 권장. terminationGracePeriodSeconds 너무 크면 consolidation 매우 느려짐.

## 9. 운영 점검과 알람 — 필수 메트릭

| 메트릭 | 의미 / 권장 알람 |
|---|---|
| `karpenter_nodes_created_total` | 노드 생성 누적. spike 시 thrashing |
| `karpenter_nodes_terminated_total` | 노드 종료 누적 |
| `karpenter_nodes_termination_time_seconds` | 종료 소요 시간. p99 5분+ 이면 PDB/finalizer 의심 |
| `karpenter_disruption_eligible_nodes` | disruption 가능 후보 수 |
| `karpenter_provisioner_scheduling_simulation_duration_seconds` | 시뮬레이션 비용 |
| `karpenter_cloudprovider_errors_total` | EC2 API 에러 |
| `karpenter_interruption_received_messages_total` | Spot 회수 통지 수신 |

알람 예시 (PromQL):

```
rate(karpenter_nodes_created_total[1m]) * 60 > 5
increase(karpenter_interruption_received_messages_total[5m]) > 10
histogram_quantile(0.99, karpenter_nodes_termination_time_seconds_bucket) > 300
```

## 10. 멀티 NodePool 전략과 우선순위 가중치

| NodePool | 용도 |
|---|---|
| `default-spot` | 무상태 워크로드, Spot 비중 80%+ |
| `default-on-demand` | 동기 트래픽 critical path, On-Demand only |
| `gpu` | LLM inference / training, instance-family g5/g6 |
| `system` | kube-system, monitoring 등, NodePool 별 taint |

NodePool 간 우선순위는 `weight` 필드 (v1) 로 제어.

```yaml
spec:
  weight: 100
```

```yaml
# GPU NodePool
spec:
  template:
    spec:
      taints:
        - key: nvidia.com/gpu
          value: 'true'
          effect: NoSchedule
      requirements:
        - key: karpenter.k8s.aws/instance-family
          operator: In
          values: ['g5', 'g6', 'p5']
```

비용 모니터링 — *NodePool 별 EC2 비용* 을 라벨로 분해. AWS Cost Explorer + Karpenter 라벨(`karpenter.sh/nodepool`) 결합.

## 참고

- Karpenter Documentation (https://karpenter.sh/)
- AWS EKS Best Practices Guide — Karpenter (https://aws.github.io/aws-eks-best-practices/karpenter/)
- KubeCon NA 2023 발표 — Karpenter v1 GA roadmap (Ellis Tarn et al.)
- aws/karpenter-provider-aws GitHub 소스 — consolidation simulator 구현
- Spot Interruption Notice + EventBridge 통합 패턴 — AWS Compute Blog 시리즈
