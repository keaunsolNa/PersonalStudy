Notion 원본: https://www.notion.so/35f5a06fd6d381f79c7ed0bf63720234

# Kubernetes Pod Disruption Budget과 PriorityClass Preemption — 안전한 노드 드레인 운영

> 2026-05-13 신규 주제 · 확장 대상: DevOps (Kubernetes)

## 학습 목표

- PDB(Pod Disruption Budget) 가 *voluntary disruption* 에서만 동작하는 이유와 한계 인지
- PriorityClass 기반 Preemption 이 어떤 시그널 순서로 일어나는지 scheduler 코드 흐름으로 추적
- 노드 드레인(`kubectl drain`) / 클러스터 오토스케일러 / 카르페너(Karpenter) 의 disruption 통합 정책 비교
- 실제 운영에서 PDB 와 PriorityClass 가 충돌하는 시나리오 4가지와 회피 패턴

## 1. Voluntary vs Involuntary Disruption

쿠버네티스는 파드 종료 원인을 두 종류로 본다.

| 분류 | 예시 | PDB 적용 |
| --- | --- | --- |
| Voluntary | `kubectl drain`, 노드 업데이트, deployment rollout, autoscaler scale-down, HPA replicas 감소 | ✅ 적용 |
| Involuntary | 노드 하드웨어 장애, OOMKill, kubelet 충돌, 네트워크 파티션, kernel panic | ❌ 적용 안 됨 |

PDB(`policy/v1.PodDisruptionBudget`) 는 *voluntary disruption 의 속도를 제한*할 뿐이다. 모든 종류의 가용성 보장 도구로 오해되어 있지만 비계획적 장애는 막을 수 없다. 그 영역은 multi-AZ, HPA replica buffer, anti-affinity 가 담당한다.

## 2. PDB 정의 — minAvailable vs maxUnavailable

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: payment-pdb
spec:
  minAvailable: 80%
  selector:
    matchLabels:
      app: payment
```

`minAvailable` 과 `maxUnavailable` 은 *동시에 쓸 수 없다*. 둘 중 의미가 명확한 쪽을 골라야 한다. 운영 권장은 *비율 표현* 이다 — replicas 가 변하더라도 정책이 유효하다.

* `minAvailable: 80%` 와 replicas=10 이면 disruption 가능한 파드는 최대 2개
* `maxUnavailable: 1` 과 replicas=3 이면 한 번에 한 파드만 evict

특수 케이스로 `minAvailable: 100%` 또는 `maxUnavailable: 0` 은 *모든 voluntary disruption 차단* 이다. 이 설정은 노드 드레인을 영구히 막을 수 있어 위험하다. CI/CD 에서 PDB linter 가 이 케이스를 경고로 잡아주는 것이 좋다.

## 3. drain 의 동작 시퀀스

`kubectl drain <node>` 는 다음 순서로 동작한다.

1. 노드에 `node.kubernetes.io/unschedulable=true` taint 부착(cordon)
2. 노드에 있는 파드 목록 조회, daemonset / mirror pod 제외
3. 각 파드에 `eviction request` 전송 (POST /api/v1/namespaces/.../pods/.../eviction)
4. apiserver 는 PDB 평가 → 위반 시 429 Too Many Requests 반환
5. 위반이면 retry, 통과면 graceful termination (`SIGTERM` → `terminationGracePeriodSeconds` → `SIGKILL`)

429 응답이 반복되면 drain 은 무한정 대기한다. `--disable-eviction` 으로 raw DELETE 를 보내면 PDB 가 우회되지만, *PDB 의 목적 자체를 무너뜨리는* 옵션이므로 비상시 외엔 쓰지 않는다.

## 4. terminationGracePeriodSeconds 의 정확한 의미

`gracePeriod` 는 SIGTERM 발송 후 SIGKILL 까지의 *최대* 시간이다. 그 안에 컨테이너가 자발적으로 종료되면 즉시 SIGKILL 없이 끝난다.

```yaml
spec:
  terminationGracePeriodSeconds: 60
  containers:
  - name: app
    lifecycle:
      preStop:
        exec:
          command: ["sh", "-c", "sleep 10 && /app/drain-handler.sh"]
```

preStop 훅도 이 시간 안에 마쳐야 한다. preStop + SIGTERM 처리 시간 합이 grace period 를 넘으면 SIGKILL 이 강제로 떨어진다. JVM 앱은 `Runtime.getRuntime().addShutdownHook(...)` 으로 connection drain 을 직접 구현해야 grace period 가 의미를 갖는다.

이 시점에 *Service endpoint 에서 파드가 제거되는 타이밍* 이 큰 함정이다. preStop 시작 전에 미리 readinessProbe 를 fail 시켜 traffic 을 빠지게 하는 패턴(`sleep N && fail` preStop) 이 표준이다.

## 5. PriorityClass와 Preemption

`PriorityClass` 는 *scheduling 우선순위*와 *preemption 권한*을 부여한다.

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata:
  name: business-critical
value: 1000000
preemptionPolicy: PreemptLowerPriority
globalDefault: false
```

`PreemptLowerPriority` (기본값) 면 노드 자원이 부족할 때 *낮은 priority 의 파드를 evict* 시키고 자기가 들어간다. `Never` 로 두면 evict 안 함.

빌트인 PriorityClass:
* `system-cluster-critical` (2000000000)
* `system-node-critical` (2000001000)

사용자 정의는 1억 미만 권장. 너무 큰 값을 주면 시스템 컴포넌트와 충돌한다.

### Preemption 시퀀스 (kube-scheduler 1.30 기준)

1. 새로 들어온 high-priority Pod 가 *Schedulable Node* 를 못 찾음
2. scheduler 가 candidate nodes 를 추리고 각 노드에서 *어떤 victim 들을 제거하면* 들어갈 수 있는지 시뮬레이션
3. *PDB 위반 없이 victim 을 정할 수 있는 노드* 우선 선택
4. victim 파드에 `DeletionTimestamp` 부여, grace period 후 종료
5. 새 Pod 가 그 노드에 스케줄

여기서 *PDB 가 preemption 도 막느냐* 가 자주 헷갈리는데, 1.21+ 부터 scheduler 는 PDB 를 *best-effort* 로 존중한다. PDB 위반 없이 evict 가능한 victim 셋이 있으면 그쪽을 선호하지만, *전 노드에서 PDB-clean 한 셋이 없으면* PDB 를 위반하면서라도 preempt 한다. 따라서 PDB 만으로는 *priority 가 더 높은 파드의 preemption 을 절대적으로 막지 못한다*.

## 6. Cluster Autoscaler / Karpenter 의 disruption 정책

* *Cluster Autoscaler (CA)*: scale-down 시 노드 위 파드를 다른 노드로 옮길 수 있을 때만 노드 삭제. PDB 위반 시 해당 노드는 *unremovable* 로 표시되어 한동안 retry 안 됨.
* *Karpenter*: `disruption.consolidationPolicy` 와 `disruption.expireAfter` 두 축으로 노드 회수. CA 보다 공격적이고 *spot 인터럽션* 도 PDB 를 평가한 후 처리한다.
* Karpenter 0.32+ 는 `disruption.budgets` 필드로 *시간 윈도우별 disruption 비율* 까지 지정 가능.

```yaml
spec:
  disruption:
    consolidationPolicy: WhenUnderutilized
    expireAfter: 720h
    budgets:
    - nodes: "20%"
    - nodes: "0"
      schedule: "0 9 * * MON-FRI"  # 평일 9시엔 disruption 금지
      duration: 9h
```

## 7. 자주 부딪히는 충돌 시나리오

* *PDB minAvailable=100% + replicas=1*: 단일 파드 deployment 에서 PDB 가 모든 drain 을 막음 → replicas=2 로 가거나 PDB minAvailable=0 으로 완화
* *PriorityClass 미설정 + system-critical 부족*: kube-system 파드가 cluster-critical 인데 그보다 낮은 사용자 파드가 자원을 차지해 system 파드가 pending → 사용자 deployment 에 명시적 priority 부여
* *spot 인스턴스 + PDB 엄격*: AWS 가 2분 통보로 노드 회수 → PDB 가 의미 없음(involuntary). spot 전용 deployment 는 replicas buffer 로 대응
* *StatefulSet + PDB*: ordinal 순서 유지 때문에 동시에 한 파드만 종료됨. PDB maxUnavailable=1 과 중복 효과 — 굳이 필요 없음
* *Karpenter consolidation + 짧은 gracePeriod*: app 이 30초 connection drain 인데 grace=10초면 in-flight 요청 손실 발생 → grace 60초 + preStop sleep 패턴

## 8. 운영 권장 설정

| 항목 | 권장 |
| --- | --- |
| Stateless API (replicas≥3) | `minAvailable: 50%` 또는 `maxUnavailable: 1` |
| Stateless API (replicas=2) | `maxUnavailable: 1` (한 번에 하나) |
| Stateful DB primary | `maxUnavailable: 0` + 수동 failover 절차 |
| Batch worker | PDB 불필요 (재실행 가능) |
| Critical control plane | PriorityClass=system-cluster-critical |
| PriorityClass 일반 워크로드 | 1000~100000 사이 값 |
| terminationGracePeriodSeconds | 30~60 (HTTP) / 120~300 (gRPC long stream) |
| preStop sleep | readiness fail 후 5~10초 sleep |

배포 자동화에 PDB 누락 검증을 넣어두면 *replicas≥2 deployment 중 PDB 없는 것* 을 알람으로 빠르게 잡을 수 있다. OPA Gatekeeper / Kyverno 로 게이트하는 것이 사실상 표준.


## 9. 실전 사례 — 무중단 노드 업그레이드

3-node ETCD cluster + 50개 일반 워크로드 환경에서 모든 노드를 한 사이클로 교체하는 표준 절차:

```bash
# 1) 첫 노드 cordon
kubectl cordon node-1

# 2) drain — PDB 자동 평가
kubectl drain node-1 --ignore-daemonsets --delete-emptydir-data \
    --grace-period=120 --timeout=10m

# 3) 노드 OS 패치 / kubelet 업그레이드
ssh node-1 'sudo systemctl restart kubelet'

# 4) uncordon
kubectl uncordon node-1

# 5) 다음 노드로
```

이 절차에서 PDB 가 한 번에 한 파드씩만 evict 되도록 보장한다. 단, ETCD 같은 *quorum 필수* 컴포넌트는 직접 PDB 로 보호되지 않으므로 (system-cluster-critical 도 evict 가능) 절차를 *수동으로 한 노드씩* 수행해야 한다. 보통 `kubeadm upgrade node` 또는 ClusterAPI 의 *RollingUpdate* 전략이 이를 자동화.

### Health Gate 패턴

drain 직전 외부 LB(ALB/NLB)에서 노드를 빼는 것이 안전하다. 컴포넌트별 readiness 만 보면 *connection draining* 도중의 in-flight 요청이 손실될 수 있다.

```yaml
spec:
  containers:
  - name: api
    lifecycle:
      preStop:
        exec:
          command:
          - sh
          - -c
          - |
            touch /tmp/shutting-down
            sleep 15
            kill -TERM 1 && wait 1
```

readinessProbe 에서 `/tmp/shutting-down` 존재시 실패 응답. 이 패턴으로 SIGTERM 직후의 connection 손실이 99% 이상 사라진다.

## 10. 모니터링 메트릭

PDB / Preemption 관련 핵심 메트릭:

| 메트릭 | 의미 | 임계치 |
| --- | --- | --- |
| `kube_poddisruptionbudget_status_current_healthy` | PDB selector 의 healthy 파드 수 | `< minAvailable` 면 알람 |
| `kube_poddisruptionbudget_status_disruptions_allowed` | 현재 추가 disruption 가능 수 | 0 이 지속되면 drain 막힘 |
| `scheduler_preemption_attempts_total` | preemption 시도 횟수 | 갑자기 증가 시 자원 부족 |
| `scheduler_preemption_victims` | preempt 된 victim 파드 수 | 비정상 spike 감시 |
| `kubelet_pod_termination_duration_seconds` | 종료 소요 시간 | gracePeriod 의 P95 |

Prometheus + Grafana 로 *클러스터별 disruption rate* 대시보드를 두면 운영 중 이상 신호를 빠르게 잡는다. 특히 `disruptions_allowed=0` 이 30분 지속되는 deployment 는 *PDB 가 과보호* 상태일 가능성이 높다.

## 11. eviction policy v1 의 unhealthyPodEvictionPolicy

policy/v1 API (1.21+) 의 PDB 와 policy/v1beta1 (1.25 에서 삭제) 의 차이는 *unhealthy pod 처리* 다.

* v1beta1: unhealthy 파드도 PDB 보호 → CrashLoopBackOff 인 파드 때문에 drain 막힘
* v1: `unhealthyPodEvictionPolicy: AlwaysAllow` 옵션 추가 → unhealthy 파드는 무조건 evict 가능

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
spec:
  minAvailable: 50%
  unhealthyPodEvictionPolicy: AlwaysAllow  # 1.27+
  selector:
    matchLabels:
      app: api
```

이 옵션이 없으면 *fully-broken deployment 가 PDB 때문에 drain 을 영구 차단* 하는 운영 사고가 일어난다. 1.27 부터 기본값이 `IfHealthyBudget` 인데 운영에선 `AlwaysAllow` 가 더 안전한 경우가 많다.

## 12. 정책 게이트 — Kyverno / Gatekeeper

PDB 가 잘못 설정되는 케이스 (minAvailable=100%, replicas=1, PDB 누락) 를 *deployment-time* 에 차단하는 것이 운영 안정성의 큰 축이다. Kyverno 정책 예시:

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: require-pdb
spec:
  validationFailureAction: Enforce
  rules:
  - name: deployment-must-have-pdb
    match:
      any:
      - resources:
          kinds: [Deployment, StatefulSet]
    preconditions:
      all:
      - key: "{{ request.object.spec.replicas }}"
        operator: GreaterThanOrEquals
        value: 2
    validate:
      message: "replicas >= 2 인 워크로드는 PDB 가 필요합니다"
      foreach:
      - list: "request.object.metadata.labels"
        deny:
          conditions:
            all:
            - key: "{{ matching_pdb_count }}"
              operator: Equals
              value: 0
```

OPA Gatekeeper 로도 같은 정책을 작성 가능. *PDB 가 minAvailable=100% 인지*, *replicas 와 minAvailable 비율이 합리적인지* 도 같이 검사한다.

## 참고

- Kubernetes 공식 문서 — Specifying a Disruption Budget for your Application
- KEP-2017 — Pod Priority and Preemption
- KEP-3017 — PodDisruptionBudget Unhealthy Pod Eviction Policy
- Karpenter Disruption 문서 (https://karpenter.sh/docs/concepts/disruption/)
- "Production Kubernetes" — Josh Rosso et al., Chapter 8
- kube-scheduler preemption 소스: `pkg/scheduler/framework/preemption`
- Kyverno PDB 정책 라이브러리
