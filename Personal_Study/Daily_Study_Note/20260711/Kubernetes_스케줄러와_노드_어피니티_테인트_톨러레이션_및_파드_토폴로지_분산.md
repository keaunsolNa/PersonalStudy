Notion 원본: https://app.notion.com/p/39a5a06fd6d3815aabdaf451864ef22b

# Kubernetes 스케줄러와 노드 어피니티 테인트 톨러레이션 및 파드 토폴로지 분산

> 2026-07-11 신규 주제 · 확장 대상: Kubernetes (CNI·HPA·Operator 학습됨)

## 학습 목표

- kube-scheduler의 필터링·스코어링 2단계 스케줄링 사이클을 설명한다
- 노드 어피니티와 파드 어피니티·안티어피니티의 표현식을 구분해 작성한다
- 테인트·톨러레이션으로 노드 격리와 전용 노드풀을 설계한다
- 토폴로지 분산 제약으로 가용 영역 간 균형 배치를 구성한다

## 1. 스케줄링 사이클 개요

`kube-scheduler`는 스케줄되지 않은 파드를 감시하다가 각 파드에 대해 두 단계를 거쳐 노드를 고른다. 첫 단계는 필터링(filtering)으로, 파드를 실행할 수 없는 노드를 제거해 실행 가능한(feasible) 노드 집합을 만든다. 리소스 부족, 노드 셀렉터 불일치, 톨러레이션 없는 테인트 등이 이 단계에서 걸러진다. 두 번째 단계는 스코어링(scoring)으로, 남은 노드에 점수를 매겨 가장 높은 노드에 파드를 바인딩한다.

이 과정은 스케줄링 프레임워크의 확장점(plugin) 위에서 동작한다. `PreFilter`, `Filter`, `PostFilter`, `PreScore`, `Score`, `Reserve`, `Permit`, `Bind` 등의 훅에 플러그인이 붙어 정책을 구현한다. 예를 들어 `NodeResourcesFit`은 필터·스코어 양쪽에서 동작해 리소스 여유를 판단하고 점수화한다.

바인딩이 결정되면 스케줄러는 파드의 `spec.nodeName`을 설정하고, 해당 노드의 kubelet이 이를 감지해 컨테이너를 띄운다. 스케줄러는 배치 결정만 하고 실제 실행은 kubelet이 한다는 책임 분리가 핵심이다.

## 2. nodeSelector와 노드 어피니티

가장 단순한 배치 제어는 `nodeSelector`다. 노드 레이블과 정확히 일치하는 노드에만 배치한다. 하지만 표현력이 부족해, 더 유연한 노드 어피니티가 이를 대체한다. 노드 어피니티는 두 종류다. `requiredDuringSchedulingIgnoredDuringExecution`은 필수 조건(필터 단계), `preferredDuringSchedulingIgnoredDuringExecution`은 선호 조건(스코어 단계)이다.

```yaml
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms:
      - matchExpressions:
        - key: disktype
          operator: In
          values: ["ssd"]
    preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 50
      preference:
        matchExpressions:
        - key: topology.kubernetes.io/zone
          operator: In
          values: ["ap-northeast-2a"]
```

`IgnoredDuringExecution`의 의미가 중요하다. 스케줄 시점에만 조건을 평가하고, 파드가 실행 중일 때 노드 레이블이 바뀌어도 파드를 쫓아내지 않는다. required 조건은 하나라도 만족하는 노드가 있어야 스케줄되고, preferred는 weight 합이 큰 노드일수록 점수가 높아 우선 선택된다. operator로 `In`, `NotIn`, `Exists`, `Gt`, `Lt` 등을 지원해 범위·존재 조건을 표현한다.

## 3. 파드 어피니티와 안티어피니티

파드 어피니티는 "다른 파드와 같은(또는 다른) 토폴로지 도메인에 배치"를 표현한다. 노드가 아니라 이미 배치된 파드를 기준으로 삼는다는 점이 노드 어피니티와 다르다. `topologyKey`가 도메인의 단위(노드·존·리전)를 정한다.

```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
    - labelSelector:
        matchLabels: { app: web }
      topologyKey: kubernetes.io/hostname
```

위 안티어피니티는 `app=web` 파드가 같은 노드(`hostname` 도메인)에 두 개 이상 배치되지 않게 한다. 고가용성을 위해 복제본을 서로 다른 노드에 흩뿌리는 전형적 패턴이다. `topologyKey`를 `topology.kubernetes.io/zone`으로 바꾸면 존 단위로 분산된다.

파드 어피니티는 계산 비용이 크다. 후보 노드마다 클러스터 전역의 파드 분포를 확인해야 하므로, required 안티어피니티를 대규모 클러스터에 광범위하게 걸면 스케줄링 지연이 커진다. 그래서 강한 분산이 꼭 필요한 경우가 아니면 다음 절의 토폴로지 분산 제약을 쓰는 편이 효율적이다.

## 4. 테인트와 톨러레이션

테인트(taint)는 노드에 "이 노드는 특정 파드만 받겠다"는 표시를 붙인다. 톨러레이션(toleration)이 없는 파드는 테인트된 노드에서 필터링 단계에 배제된다. 어피니티가 파드가 노드를 끌어당기는 것이라면, 테인트는 노드가 파드를 밀어내는 것으로 방향이 반대다.

```bash
kubectl taint nodes gpu-node-1 dedicated=gpu:NoSchedule
```

```yaml
tolerations:
- key: dedicated
  operator: Equal
  value: gpu
  effect: NoSchedule
```

effect는 세 가지다. `NoSchedule`은 톨러레이션 없는 파드의 신규 스케줄을 막는다. `PreferNoSchedule`은 되도록 피하되 강제하지는 않는다. `NoExecute`는 이미 실행 중인 파드까지 톨러레이션이 없으면 축출(evict)한다. `NoExecute` 톨러레이션에 `tolerationSeconds`를 주면 그 시간만큼 유예 후 축출된다. 노드가 `not-ready`/`unreachable` 상태가 되면 컨트롤 플레인이 자동으로 `NoExecute` 테인트를 붙여 장애 노드에서 파드를 옮기는데, 기본 톨러레이션 시간이 여기에 관여한다.

전용 노드풀 설계의 정석은 테인트와 노드 어피니티를 함께 쓰는 것이다. 테인트로 일반 파드를 밀어내고, 전용 파드에는 톨러레이션과 함께 노드 어피니티를 주어 그 노드에만 가도록 끌어당긴다. 톨러레이션만 있으면 파드가 다른 노드로 갈 수도 있으므로 어피니티로 못을 박는다.

## 5. 토폴로지 분산 제약

`topologySpreadConstraints`는 파드를 토폴로지 도메인(존·노드) 간에 고르게 분산시키는 선언적 방법이다. 안티어피니티보다 표현력이 좋고 "얼마나 불균형을 허용할지"를 `maxSkew`로 정량화한다.

```yaml
topologySpreadConstraints:
- maxSkew: 1
  topologyKey: topology.kubernetes.io/zone
  whenUnsatisfiable: DoNotSchedule
  labelSelector:
    matchLabels: { app: web }
```

`maxSkew: 1`은 어떤 두 존 사이의 파드 개수 차이가 1을 넘지 않도록 한다. `whenUnsatisfiable`이 `DoNotSchedule`이면 제약을 못 지킬 때 스케줄을 보류하고, `ScheduleAnyway`면 제약을 소프트 힌트로 삼아 점수만 낮춘다. 여러 제약을 동시에 걸어 존 간 균형과 노드 간 균형을 함께 요구할 수 있다.

토폴로지 분산은 롤링 업데이트나 클러스터 스케일 이벤트에서 특히 유용하다. 세 존에 6개 복제본을 균등 분산하면 한 존 장애 시에도 2/3가 살아남는다. 안티어피니티로 같은 효과를 내려면 복잡한 규칙이 필요하지만 분산 제약은 한 블록으로 표현된다.

## 6. 우선순위와 선점

파드에 `PriorityClass`를 부여하면 스케줄러가 우선순위를 고려한다. 자원이 부족해 고우선 파드를 배치할 수 없으면, 스케줄러는 저우선 파드를 축출(preemption)해 자리를 만든다. 이는 `PostFilter` 단계에서 일어난다.

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: high-priority }
value: 1000000
preemptionPolicy: PreemptLowerPriority
globalDefault: false
```

선점은 저우선 파드에 정상 종료 유예를 주고 축출한다. `preemptionPolicy: Never`로 두면 우선순위는 높지만 다른 파드를 밀어내지는 않는 "새치기만 하고 축출은 안 함" 동작이 된다. 시스템 크리티컬 컴포넌트는 예약된 고값 PriorityClass(`system-cluster-critical` 등)를 써서 자원 경쟁에서 밀리지 않게 한다.

## 7. 트레이드오프와 성능

| 기능 | 방향 | 강제성 | 계산 비용 |
|------|------|--------|-----------|
| nodeSelector | 끌어당김 | 필수 | 낮음 |
| node affinity | 끌어당김 | 필수/선호 | 낮음 |
| pod (anti)affinity | 파드 기준 | 필수/선호 | 높음 |
| taint/toleration | 밀어냄 | 필수 | 낮음 |
| topology spread | 분산 | 필수/선호 | 중간 |

required 제약을 과도하게 걸면 스케줄 가능한 노드가 사라져 파드가 `Pending`에 멈춘다. 특히 안티어피니티 required는 복제본 수가 노드 수를 초과하면 영원히 스케줄되지 않는다. 반대로 preferred 위주로 구성하면 유연하지만 원하는 배치가 보장되지 않는다. 실무에서는 안전에 직결되는 최소 제약만 required로, 나머지는 preferred나 분산 제약으로 두어 스케줄 실패를 피한다.

스케줄러 처리량도 고려 대상이다. 파드 어피니티 required는 노드×파드 계산을 유발해 초당 스케줄 파드 수를 떨어뜨린다. 수천 노드 클러스터에서는 토폴로지 분산 제약이 같은 목적을 더 낮은 비용으로 달성하는 경우가 많다.

## 8. 실전 배치 설계

전형적 프로덕션 구성은 계층별로 나뉜다. GPU·고메모리 같은 특수 노드는 테인트로 격리하고 전용 워크로드에 톨러레이션+어피니티를 준다. 상태 저장 서비스(DB 복제본)는 존 단위 안티어피니티나 토폴로지 분산으로 존 장애 내성을 확보한다. 스테이트리스 웹 계층은 노드 단위 분산으로 단일 노드 장애 시 트래픽 손실을 최소화한다.

앞서 학습한 HPA·Cluster Autoscaler와의 상호작용도 설계에 포함해야 한다. HPA가 복제본을 늘렸는데 분산 제약(`DoNotSchedule`)을 못 지키면 새 파드가 `Pending`에 걸리고, Cluster Autoscaler가 이를 감지해 새 노드를 프로비저닝한다. 이때 새 노드가 원하는 존에 뜨도록 노드 그룹을 존별로 구성해 두어야 오토스케일이 분산 제약과 맞물려 동작한다. 배치 정책과 오토스케일링을 따로 설계하면 확장 시점에 스케줄이 막히는 문제가 생긴다.

## 참고

- Kubernetes Docs, "Assigning Pods to Nodes" (kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node)
- Kubernetes Docs, "Taints and Tolerations", "Pod Topology Spread Constraints"
- Kubernetes Docs, "Scheduling Framework" 및 "Pod Priority and Preemption"
- kubernetes/kubernetes `pkg/scheduler` 소스
- "Kubernetes in Action", Marko Lukša
