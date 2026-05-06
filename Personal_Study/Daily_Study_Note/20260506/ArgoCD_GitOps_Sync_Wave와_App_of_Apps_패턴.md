Notion 원본: https://www.notion.so/3585a06fd6d381d7a7d1d42f5e7967f2

# ArgoCD GitOps Sync Wave와 App of Apps 멀티 클러스터 패턴

> 2026-05-06 신규 주제 · 확장 대상: DevOps

## 학습 목표

- ArgoCD 의 reconciliation loop 와 git-as-source-of-truth 모델이 동작하는 흐름을 정리한다
- Sync Wave 와 Sync Phase(PreSync, Sync, PostSync, SyncFail) 의 차이를 manifest 어노테이션 단위로 분석한다
- App of Apps 패턴과 ApplicationSet 으로 다 클러스터 / 다 환경 관리를 코드화한다
- Drift detection, auto-prune, self-heal 의 trade-off 와 운영 사고 회피 전략을 분석한다

## 1. GitOps 의 본질 — 선언적 desired state

ArgoCD 는 Kubernetes 에 manifest 를 적용하는 도구지만, 그보다 GitOps 를 강제하는 tool 이라고 보는 편이 정확하다. 핵심 가정 셋.

1. 클러스터의 desired state 는 git 리포지토리에 있다
2. 어떤 변경도 git → cluster 방향으로만 흐른다 (역방향 금지)
3. 실제 cluster state 가 git 에서 멀어지면(drift) 자동으로 git 쪽으로 복귀한다

ArgoCD 는 controller 형 application 이다. `Application` 이라는 CRD 를 etcd 에 넣어두면 controller 가 주기적으로(`timeout.reconciliation`, 기본 3분) git 을 fetch 하고 manifest 를 렌더링한 뒤 cluster 와 diff 한다. diff 가 있고 sync policy 가 auto 이면 apply 한다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: payment-service
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/example/k8s-manifests
    targetRevision: main
    path: apps/payment-service/overlays/prod
  destination:
    server: https://kubernetes.default.svc
    namespace: payment
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ApplyOutOfSyncOnly=true
```

`prune: true` 는 git 에서 사라진 리소스를 cluster 에서 삭제한다. `selfHeal: true` 는 git 변경 없이 cluster 가 drift 하면 git 상태로 되돌린다. 두 옵션 모두 GitOps 의 단방향 흐름을 강제한다.

## 2. Reconciliation Loop 의 내부

ArgoCD repo-server 가 git clone + helm/kustomize render → application-controller 가 cluster live state 와 비교 → 차이를 actionable diff 로 변환 → API server 에 apply.

apply 자체는 server-side apply(`kubectl apply --server-side`)에 해당하는 동작으로, conflict 를 manage 하기 위해 fieldManager 를 ArgoCD 가 가져간다. 다른 controller 가 같은 필드를 만지면 conflict warning 이 뜨고 sync 가 OutOfSync 로 표시된다.

reconciliation 주기는 `application.resync` 환경변수와 application 별 어노테이션 `argocd.argoproj.io/refresh: hard` 로 강제 트리거한다. webhook(`/api/webhook` 엔드포인트) 등록 시 push 즉시 sync 가 가능해 latency 가 수초까지 줄어든다.

## 3. Sync Phase 와 Sync Wave

큰 manifest 를 한 번에 적용하면 의존 순서가 꼬인다. ConfigMap 이 먼저 들어가야 Deployment 가 그것을 mount 할 수 있다. CRD 가 먼저 등록돼야 Custom Resource 가 admit 된다. ArgoCD 는 두 메커니즘으로 순서를 제어한다.

### 3.1 Sync Phase (4 단계)

| Phase | 시점 | 용도 |
|---|---|---|
| PreSync | 메인 sync 전 | DB 마이그레이션, 백업, lock |
| Sync | manifest apply | 일반 리소스 |
| PostSync | sync 성공 후 | smoke test, notification |
| SyncFail | sync 실패 시 | rollback, alert |

Phase 는 어노테이션으로 지정.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: db-migrate
  annotations:
    argocd.argoproj.io/hook: PreSync
    argocd.argoproj.io/hook-delete-policy: HookSucceeded
spec:
  template:
    spec:
      containers:
        - name: migrate
          image: registry/payment-migrate:v1.0.0
          command: ["./flyway", "migrate"]
      restartPolicy: Never
```

`hook-delete-policy: HookSucceeded` 는 Job 이 성공하면 자동 삭제. 실패 시 남겨서 디버깅 가능. `BeforeHookCreation` 옵션은 같은 hook 의 이전 인스턴스를 새 hook 시작 전에 지운다.

### 3.2 Sync Wave (정수 순서)

같은 Phase 안에서도 순서를 매기고 싶을 때 sync-wave 를 쓴다.

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-5"
```

Wave 가 작은 것부터 apply 된다. 동일 wave 의 리소스는 병렬 apply.

전형적 순서:

| Wave | 리소스 | 이유 |
|---|---|---|
| -10 | CRDs | custom resource 가 들어가기 전에 등록 |
| -5 | Namespaces, ResourceQuotas | scope 와 limit 먼저 |
| 0 | ConfigMap, Secret | workload 가 mount |
| 5 | Deployment, StatefulSet | core workload |
| 10 | Service, Ingress | traffic 진입은 마지막 |
| 20 | HorizontalPodAutoscaler | metrics-server 가 ready 한 후 |

ArgoCD 는 wave 단위로 batched apply 하고 wave 가 healthy 가 되면 다음 wave 로 진행한다. wave 단위 healthy 판정 시간이 길면 전체 sync 시간이 늘어나므로, healthy 판정이 명확한 리소스만 sync-wave 분리 대상으로 둔다.

## 4. App of Apps 패턴

수십 개 application 을 하나하나 등록하는 건 비효율. App of Apps 는 한 application 이 다른 application 들을 git path 에 정의하는 메타 패턴이다.

```yaml
# apps/root.yaml — root application
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: root-apps
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/example/k8s-manifests
    targetRevision: main
    path: apps/registry
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

`root-apps` 한 번만 등록하면 그 아래 application 들이 자동으로 생성/sync 된다. 추가/삭제는 `apps/registry/` 에 yaml 을 넣고 빼는 git 변경으로 표현된다.

## 5. ApplicationSet — Generator 기반 다 클러스터

App of Apps 는 application 한 개당 yaml 한 개를 손으로 써야 한다. ApplicationSet controller(2.3+)는 generator 로 application 을 동적 생성한다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: cluster-addons
  namespace: argocd
spec:
  generators:
    - matrix:
        generators:
          - clusters:
              selector:
                matchLabels:
                  env: prod
          - list:
              elements:
                - addon: cert-manager
                  path: addons/cert-manager
                - addon: external-dns
                  path: addons/external-dns
                - addon: prometheus
                  path: addons/prometheus
  template:
    metadata:
      name: '{{name}}-{{addon}}'
    spec:
      project: default
      source:
        repoURL: https://github.com/example/k8s-manifests
        targetRevision: main
        path: '{{path}}'
      destination:
        server: '{{server}}'
        namespace: '{{addon}}'
      syncPolicy:
        automated: { prune: true, selfHeal: true }
```

매트릭스 generator: clusters × list = N × 3 개의 application 이 자동 생성된다. cluster 추가 시 ApplicationSet 어노테이션 변경 없이 새 클러스터가 selector 에 매칭되면 자동으로 N 개의 addon 이 배포된다.

다른 generator:

- `git` — git 디렉터리 또는 파일을 walk 해서 application 을 만든다
- `pull-request` — PR 단위 ephemeral 환경
- `scm` — 여러 repo 를 자동 발견
- `cluster-decision-resource` — 외부 결정자(예: Karmada, Flux) 의 결과를 입력으로 사용

PR generator 는 MR 단위 preview 환경을 만드는 데 강력하다. PR open → ephemeral namespace + ingress 자동 생성, PR merge/close → 정리.

## 6. Drift Detection 과 selfHeal

`selfHeal: true` 는 cluster live state 가 git 에서 벗어나면 git 으로 되돌린다. 강력하지만 위험하다.

### 6.1 위험 시나리오

운영자가 incident 대응 중 `kubectl scale deploy payment --replicas=20` 으로 임시 scale-up 했다고 하자. ArgoCD 의 selfHeal 이 발동하면 git 의 replicas 값(예: 5)으로 되돌려 incident 가 더 악화될 수 있다.

해결책 셋:

(a) **selfHeal 을 끄고 수동 sync 만 허용**. 운영 사고 시 git 변경 없이 손으로 manifest 를 적용한 뒤 incident 종료 후 git 에 반영한다.

(b) **특정 필드를 ignore 처리**. `ignoreDifferences` 로 HPA-managed replicas 같은 필드를 sync 비교에서 제외한다.

```yaml
spec:
  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas
```

(c) **sync window**. 업무시간에는 자동 sync, 새벽에는 수동만 허용 등 시간대 분리.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: prod
spec:
  syncWindows:
    - kind: deny
      schedule: "0 22 * * *"
      duration: 8h
      applications:
        - "*"
      manualSync: true
```

### 6.2 Auto-prune 의 안전 가드

`prune: true` 가 PVC 같은 stateful 리소스를 git 에서 우연히 빠뜨렸을 때 데이터 손실로 이어질 수 있다. ArgoCD 는 `Prune=false` 어노테이션으로 개별 리소스를 prune 대상에서 제외하게 해준다.

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-options: Prune=false
```

## 7. Health 평가와 Custom Health Check

ArgoCD 의 Sync 진행은 wave healthy 판정에 의존한다. 기본 health check 는 표준 Kubernetes 리소스(Deployment, StatefulSet, PVC ...)에 대해 built-in 으로 구현돼 있다. CRD 는 default 로 health 가 `Unknown` 이다.

Lua 스크립트로 custom health check 를 등록한다.

```yaml
# argocd-cm ConfigMap
data:
  resource.customizations.health.argoproj.io_Rollout: |
    hs = {}
    if obj.status ~= nil then
      if obj.status.phase == "Healthy" then
        hs.status = "Healthy"
        hs.message = obj.status.message
        return hs
      end
      if obj.status.phase == "Degraded" then
        hs.status = "Degraded"
        hs.message = obj.status.message
        return hs
      end
    end
    hs.status = "Progressing"
    return hs
```

이 정의가 있어야 Argo Rollouts 의 Canary 가 progress 중 → ArgoCD 도 Progressing 으로 표시되어 다음 wave 로 넘어가지 않는다.

## 8. Multi-Tenant — Project 와 RBAC

`AppProject` 는 application 의 그룹의 단위 + 권한 boundary 다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: payment-team
  namespace: argocd
spec:
  description: Payment domain apps
  sourceRepos:
    - https://github.com/example/payment-manifests
  destinations:
    - namespace: payment-*
      server: https://kubernetes.default.svc
  clusterResourceWhitelist: []
  namespaceResourceWhitelist:
    - group: '*'
      kind: '*'
  roles:
    - name: deployer
      policies:
        - p, proj:payment-team:deployer, applications, sync, payment-team/*, allow
      groups:
        - okta:payment-team
```

`sourceRepos` 가 화이트리스트 — 이 repo 에서만 manifest 를 가져올 수 있다. `destinations` 가 namespace 화이트리스트 — `payment-*` 패턴만 배포 가능. cluster scoped 리소스는 빈 리스트로 막아둔다.

OIDC group `okta:payment-team` 멤버는 `deployer` role 을 받아 sync 만 수행 가능하다(application 생성/삭제 불가). 운영 사고를 줄이는 RBAC 격리 패턴.

## 9. 운영 체크리스트

manifest 의 fieldManager 가 ArgoCD 단독인지. mutating webhook 이나 다른 controller(KEDA, cert-manager, Linkerd)가 같은 필드를 만지면 conflict 가 반복된다. `managedFieldsManagers` 옵션으로 ignore 처리한다.

`argocd-server` replica 가 2+ 이고 cluster autoscaler 가 노드를 회전시켜도 leader election 이 깔끔히 넘어가는지. application-controller 의 `--shard-id` 환경변수로 application 을 controller pod 간 분산해 처리한다.

webhook 이 등록돼 있는지. webhook 없이는 git push → 다음 reconciliation 까지 최대 3분 latency. webhook 등록 시 5초 이내 sync 시작.

private repo 의 SSH key / token 회전 정책. ArgoCD 의 `Repository` secret 에 들어 있는 토큰을 KMS / SealedSecret 로 관리하고, 회전 시 git ops 흐름 자체로 회전한다.

`extra args` 에 `--insecure` 가 켜져 있지 않은지. on-prem 환경에서 self-signed 인증서로 작업할 때 일시 disable 했다가 그대로 운영 진입하는 사례를 종종 본다.

## 참고

- Argo CD Docs — Sync Phases and Waves: https://argo-cd.readthedocs.io/en/stable/user-guide/sync-waves/
- Argo CD Docs — App of Apps Pattern: https://argo-cd.readthedocs.io/en/stable/operator-manual/cluster-bootstrapping/
- Argo CD Docs — ApplicationSet Generators: https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Generators/
- Weaveworks — GitOps Principles: https://www.weave.works/technologies/gitops/
- "GitOps and Kubernetes" (Manning) — Sync Phase 운영 챕터
