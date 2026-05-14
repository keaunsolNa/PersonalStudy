Notion 원본: https://www.notion.so/3605a06fd6d3815c9701f14e8e7f7909

# ArgoCD ApplicationSet과 GitOps Multi-Cluster — Generator와 Sync Wave로 N개 클러스터를 1개 매니페스트로 관리

> 2026-05-14 신규 주제 · 확장 대상: DevOps

## 학습 목표

- ApplicationSet 의 Generator 종류 (List, Cluster, Git, Matrix, Merge, PullRequest) 가 각각 무엇을 생성하는지 식별
- Multi-Cluster 배포에서 *값만 다르고 매니페스트는 같은* 패턴을 ApplicationSet 으로 코드 한 벌로 표현
- Sync Wave 와 Hooks 가 트랜잭션 순서 (DB migration → app → cron) 를 어떻게 보장하는지
- 운영 함정: Generator 결과 변화로 인한 *예상치 못한 Application 삭제* 와 보호 정책

## 1. ArgoCD Application 의 한계와 ApplicationSet 의 등장

ArgoCD 의 기본 단위는 `Application` CRD 다. *한 리포 → 한 클러스터의 한 네임스페이스* 매핑이라 매니페스트 하나가 하나의 배포 단위다. 30개 마이크로서비스 × 5개 클러스터 × 3개 환경 (dev/stg/prod) = 450개 Application 매니페스트를 손으로 유지하는 건 비현실적이다.

`ApplicationSet` 은 *Application 의 팩토리* 다. 하나의 ApplicationSet 매니페스트가 *Generator* 가 만들어내는 파라미터마다 Application 을 자동 생성/삭제한다. 결과적으로 *코드 한 벌* 로 *N개 Application* 을 관리하게 된다.

## 2. Generator 6가지 한눈에

| Generator | 입력 | 출력 |
|---|---|---|
| List | YAML 에 직접 명시한 키/값 배열 | 정적 N개 |
| Cluster | ArgoCD 가 등록한 cluster secret | 등록된 모든/필터된 cluster |
| Git | Git 리포의 디렉터리·파일 패턴 | 각 디렉터리/파일당 1개 |
| Matrix | 두 generator 의 곱집합 | 카르테시안 |
| Merge | 두 generator 의 키 일치 join | 1:1 합성 |
| PullRequest | GitHub/GitLab PR 목록 | PR 당 preview env |

대부분의 multi-cluster 배포는 *Cluster × Git* matrix 로 충분하다. "각 클러스터에 대해, Git 리포의 각 서비스를 배포" 라는 자연스러운 의미.

## 3. List Generator — 가장 단순한 출발점

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: payments-multi-env
  namespace: argocd
spec:
  generators:
    - list:
        elements:
          - env: dev
            url: https://kubernetes.default.svc
            namespace: payments-dev
          - env: stg
            url: https://stg-cluster.example.com
            namespace: payments-stg
          - env: prod
            url: https://prod-cluster.example.com
            namespace: payments
  template:
    metadata:
      name: payments-{{env}}
    spec:
      project: default
      source:
        repoURL: https://github.com/acme/payments
        targetRevision: main
        path: deploy/overlays/{{env}}
      destination:
        server: '{{url}}'
        namespace: '{{namespace}}'
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
```

세 개의 Application (`payments-dev`, `payments-stg`, `payments-prod`) 이 자동 생성된다. List 는 *정적이고 명시적* 이라 디버깅이 쉽지만, 클러스터가 늘 때마다 ApplicationSet 매니페스트를 수정해야 한다.

## 4. Cluster Generator — 클러스터 등록만으로 자동 확장

ArgoCD 는 각 등록 클러스터를 `argocd` 네임스페이스의 secret 으로 보관한다. `cluster` generator 는 그 secret 을 source of truth 로 삼는다.

```yaml
spec:
  generators:
    - clusters:
        selector:
          matchLabels:
            environment: prod
            region: us-east-1
  template:
    metadata:
      name: payments-{{name}}
    spec:
      destination:
        server: '{{server}}'
        namespace: payments
      source:
        repoURL: https://github.com/acme/payments
        targetRevision: main
        path: deploy/base
```

새 prod 클러스터를 등록하기만 하면 자동으로 Application 이 추가된다. *반대로 secret 을 지우면 Application 도 함께 사라진다.* 이게 위험할 수 있다는 점은 §9 에서 다룬다.

## 5. Matrix — 클러스터 × 서비스의 카르테시안

```yaml
spec:
  generators:
    - matrix:
        generators:
          - clusters:
              selector:
                matchLabels:
                  environment: prod
          - git:
              repoURL: https://github.com/acme/platform
              revision: main
              directories:
                - path: services/*
  template:
    metadata:
      name: '{{path.basename}}-{{name}}'
    spec:
      project: default
      source:
        repoURL: https://github.com/acme/platform
        targetRevision: main
        path: '{{path}}/overlays/{{name}}'
      destination:
        server: '{{server}}'
        namespace: '{{path.basename}}'
```

`services/payments`, `services/orders`, `services/users` 디렉터리 × 3개 prod 클러스터 = 9개 Application. 새 서비스를 디렉터리로 추가하면 자동으로 N개 Application 이 생성된다. *GitOps 의 핵심* 인 "선언적 desired state" 가 generator 수준에서 메타화된 셈.

## 6. Git Generator — 디렉터리 패턴과 파일 패턴

```yaml
- git:
    repoURL: https://github.com/acme/platform
    revision: main
    directories:
      - path: services/*
      - path: services/internal/*
        exclude: true
```

`exclude: true` 로 *반대 매칭* 도 가능. 또는 `files` 키로 *각 파일을 파라미터 소스* 로 사용 가능.

```yaml
- git:
    files:
      - path: clusters/*/config.yaml
```

각 `config.yaml` 의 키가 template 의 `{{ }}` 변수로 매핑된다. 클러스터별 *세부 값* 을 별도 파일에 두고 관리하는 패턴. ApplicationSet 매니페스트는 그대로 두고 클러스터 추가는 *디렉터리 추가만* 으로 끝난다.

## 7. Sync Wave — 매니페스트 순서 보장

ArgoCD 가 동일 sync 안에서 *어떤 순서로 매니페스트를 적용할지* 는 `argocd.argoproj.io/sync-wave` annotation 으로 정한다. 정수, 음수 가능. 작은 수 → 큰 수 순으로 적용된다.

```yaml
# 1. Namespace 와 RBAC 먼저
apiVersion: v1
kind: Namespace
metadata:
  name: payments
  annotations:
    argocd.argoproj.io/sync-wave: "-2"
---
# 2. ConfigMap / Secret
apiVersion: v1
kind: ConfigMap
metadata:
  name: payments-config
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
---
# 3. DB migration Job
apiVersion: batch/v1
kind: Job
metadata:
  name: payments-migrate
  annotations:
    argocd.argoproj.io/sync-wave: "0"
    argocd.argoproj.io/hook: PreSync
    argocd.argoproj.io/hook-delete-policy: BeforeHookCreation
---
# 4. Deployment / Service
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments
  annotations:
    argocd.argoproj.io/sync-wave: "1"
```

DB migration Job (`PreSync` hook + wave 0) 이 *성공해야* wave 1 의 Deployment 가 적용된다. *실패하면 sync 가 중단* 되고 ArgoCD UI 에 빨갛게 잡힌다. 이 패턴 한 줄로 "마이그레이션 실패 시 배포 중단" 이 자동화된다.

| Wave | 적용 자원 | 의미 |
|---|---|---|
| -2 | Namespace, CRD | 기반 자원 |
| -1 | RBAC, ConfigMap | 의존성 |
| 0 | PreSync Job (migration) | 변경 적용 |
| 1 | Deployment, Service | 워크로드 |
| 2 | HPA, PDB, CronJob | 운영 자원 |

## 8. Hook 종류와 삭제 정책

```
argocd.argoproj.io/hook: PreSync | Sync | PostSync | SyncFail | PostDelete
argocd.argoproj.io/hook-delete-policy: HookSucceeded | HookFailed | BeforeHookCreation
```

`PreSync` 는 *모든 자원 적용 전*, `PostSync` 는 *적용 후 healthy 확인 후*, `SyncFail` 은 *실패 시* 실행. migration 은 PreSync, smoke test 는 PostSync, alert 발송은 SyncFail 이 표준.

`HookSucceeded` 정책은 hook 의 자원(주로 Job)을 *성공 후 삭제* 한다. 안 두면 Job 객체가 쌓여 namespace 가 더러워진다. `BeforeHookCreation` 은 *다음 sync 직전에* 이전 hook 자원을 삭제 — 디버깅을 위해 가장 최근 실패 Job 을 남겨두고 싶을 때 유용.

## 9. 운영 함정 — Generator 변화로 인한 자동 삭제

ApplicationSet 의 *가장 위험한 동작* 은 generator 가 만든 Application 이 자동 정리된다는 점이다. List 에서 항목 한 줄을 지우면 그 Application 이 자동 prune 된다. *production 클러스터의 모든 워크로드가 삭제* 되는 사고가 실제로 보고된 적이 있다.

방어 정책:

```yaml
spec:
  syncPolicy:
    preserveResourcesOnDeletion: true   # ApplicationSet 삭제 시 자원 보존
  template:
    spec:
      syncPolicy:
        syncOptions:
          - PrunePropagationPolicy=foreground
        automated:
          allowEmpty: false             # Application 이 빈 generator 결과면 prune 보류
```

추가로 `argocd.argoproj.io/sync-options: Prune=false` annotation 을 *중요 자원* 에 달면 prune 대상에서 제외된다. PVC, DB, secret 처럼 *영속 자원* 에는 거의 필수.

ApplicationSet v0.4+ 부터 *progressive sync* (Phased rollout) 기능이 stable 로 들어왔다.

```yaml
spec:
  strategy:
    type: RollingSync
    rollingSync:
      steps:
        - matchExpressions:
            - { key: env, operator: In, values: [dev] }
        - matchExpressions:
            - { key: env, operator: In, values: [stg] }
        - matchExpressions:
            - { key: env, operator: In, values: [prod] }
          maxUpdate: 25%
```

dev → stg → prod 순으로 sync 를 굴린다. prod 안에서도 25% 씩 wave 를 나눠 점진 배포. canary 와 결합하면 *글로벌 매니페스트 변경의 폭발 반경* 을 컨트롤 가능.

## 10. PullRequest Generator — preview environment

```yaml
- pullRequest:
    github:
      owner: acme
      repo: platform
      labels: [preview]
    requeueAfterSeconds: 60
```

GitHub PR 이 `preview` 라벨을 달면 ApplicationSet 이 *PR 마다 Application 을 생성* 한다. preview namespace 가 자동으로 생기고 PR close 시 자동 정리. SaaS 의 *PR 단위 환경 분리* 의 표준 구현이다. branch slug 가 namespace 이름에 들어가므로 `{{branch_slug}}` template 변수 사용. namespace 충돌을 막기 위해 `^[a-z0-9-]{1,30}$` 정규식 필터 권장.

## 11. ArgoCD 가 GitOps 의 *세 가지 약속* 을 어떻게 지키는가

| GitOps 약속 | ArgoCD 구현 |
|---|---|
| Declarative | Application(Set) 매니페스트가 desired state |
| Versioned | Git 이 단일 source of truth, ArgoCD 는 sync 만 |
| Reconciliation | controller 가 주기적으로 cluster 상태와 Git 비교, drift 자동 복구 (selfHeal) |

ApplicationSet 은 이 모델을 *generator → application → resource* 의 3계층으로 확장한 것. drift detection 도 동일 원리: ApplicationSet 의 desired Application 목록과 실제 Application CR 들을 비교해 차이를 정리한다.

## 12. 실무 도입 체크리스트

ApplicationSet 도입 전: 모든 *수동 생성 Application* 을 generator 로 표현할 수 있는지 검토. 표현이 어려운 한두 개는 그냥 Application 으로 두고 generator 에 포함시키지 않는 게 안전.

`preserveResourcesOnDeletion: true` 와 `Prune=false` annotation 을 영속 자원에 우선 적용.

progressive sync 로 *dev/stg/prod* 전면 동시 sync 를 막아두기.

Sync Wave 컨벤션을 *팀 전체* 합의로 고정 (-2 ns, -1 config, 0 migration, 1 workload, 2 ops).

CI 에서 ApplicationSet 매니페스트의 *dry-run* (`argocd appset list-resources --dry-run`) 으로 *예상 Application 목록* 을 PR 코멘트에 게시. 클러스터 삭제로 보이는 변경을 미리 잡는다.

ApplicationSet controller 자체 모니터링: `argocd_appset_reconcile_total`, `argocd_appset_reconcile_duration_seconds` 메트릭. reconcile latency 가 길어지면 generator (특히 Git) 의 호출이 rate-limit 에 걸린 것.

## 참고

- ArgoCD ApplicationSet 공식 문서: https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/
- ArgoCD Sync Phases and Waves: https://argo-cd.readthedocs.io/en/stable/user-guide/sync-waves/
- Christian Hernandez, *Real World GitOps with Argo CD*
- CNCF GitOps Working Group — Principles 1.0.0: https://opengitops.dev/
- Akuity blog — Multi-cluster patterns with ApplicationSet
