Notion 원본: https://www.notion.so/3515a06fd6d381858050d3ea588e173a

# ArgoCD ApplicationSet — GitOps 멀티 클러스터 배포

> 2026-04-29 신규 주제 · 확장 대상: AWS, Docker&CI

## 학습 목표

- ArgoCD 의 핵심 CRD(Application/Project/AppProject) 와 GitOps 동기화 모델 파악
- ApplicationSet 의 Generator 구조로 멀티 클러스터/멀티 환경 배포를 선언적으로 표현
- App-of-Apps 와 ApplicationSet 의 본질적 차이와 운영 트레이드오프 비교
- ApplicationSet Progressive Sync 로 카나리/단계 롤아웃을 자동화

## 1. ArgoCD 의 동기화 모델

ArgoCD 는 Git 저장소를 desired state, Kubernetes 클러스터를 actual state 로 두고 둘을 비교(`diff`)해 actual 을 desired 로 수렴시키는 컨트롤러다. 핵심 CRD 는 다음과 같다.

| CRD | 역할 |
| --- | --- |
| `Application` | "이 Git path/revision 의 manifest 를 이 클러스터의 이 namespace 에 적용" 한 단위 |
| `AppProject` | 여러 Application 의 RBAC, 허용 source/destination 그룹 |
| `ApplicationSet` | Application 들을 generator 로 동적 생성 |

Application 의 핵심은 source 와 destination 두 쌍이다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: shop-api-prod
  namespace: argocd
spec:
  project: shop
  source:
    repoURL: https://github.com/example/k8s-config.git
    path: apps/shop-api/overlays/prod
    targetRevision: HEAD
  destination:
    server: https://k8s-prod.example.com
    namespace: shop
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ApplyOutOfSyncOnly=true
```

`automated.prune` 은 Git 에서 사라진 리소스를 클러스터에서도 삭제, `selfHeal` 은 클러스터에 수동으로 변경된 리소스를 desired 로 되돌린다. `selfHeal=true` 인 경우 운영자가 `kubectl edit` 로 임시 변경한 값이 ArgoCD 에 의해 자동 롤백되므로 emergency hot-fix 시에는 잠시 sync 를 비활성화하는 운영 절차가 필요하다.

## 2. 다환경 다클러스터 — 수동 Application 의 한계

운영 환경이 dev/staging/prod 3개, 클러스터가 region 별로 us-east-1/ap-northeast-2/eu-west-1 3개라면 단일 서비스에 9개의 Application 매니페스트를 만들어야 한다. 새 마이크로서비스를 추가할 때마다 9개씩 늘어나고, 신규 region 을 추가하면 모든 서비스의 매니페스트를 또 추가해야 한다. 이 폭발을 막는 도구가 ApplicationSet 이다.

## 3. ApplicationSet Generator 의 구조

ApplicationSet 은 generator 로 desired Application 목록을 동적으로 생성한다. generator 는 입력(클러스터 목록, Git 경로, 외부 API 등) 을 받아 매개변수 맵 배열을 반환한다. 각 매개변수는 template 의 placeholder 로 흘러들어가 Application CR 을 만든다.

대표 generator 6 종.

| Generator | 입력 | 사용 사례 |
| --- | --- | --- |
| List | 명시적 키-값 배열 | 소규모, 명시적 |
| Cluster | ArgoCD 에 등록된 Cluster Secret | 모든 클러스터에 자동 배포 |
| Git Files | Git 저장소의 파일 패턴 | 파일별 환경 정의 |
| Git Directories | Git 저장소의 디렉터리 패턴 | 디렉터리 = 서비스 매핑 |
| Pull Request | GitHub/GitLab PR API | PR 마다 preview 환경 |
| Matrix | 두 generator 의 조합 | (서비스, 클러스터) 쌍 |

### 3.1 Cluster Generator

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: shop-api
  namespace: argocd
spec:
  generators:
    - clusters:
        selector:
          matchLabels:
            env: prod
  template:
    metadata:
      name: 'shop-api-{{name}}'
    spec:
      project: shop
      source:
        repoURL: https://github.com/example/k8s-config.git
        path: 'apps/shop-api/overlays/{{metadata.labels.env}}/{{metadata.labels.region}}'
        targetRevision: HEAD
      destination:
        server: '{{server}}'
        namespace: shop
      syncPolicy:
        automated: { prune: true, selfHeal: true }
```

ArgoCD 에 cluster 등록 시 secret 에 라벨을 넣어두면 selector 로 필터링된다. prod 라벨을 가진 클러스터가 3개면 자동으로 3개의 Application 이 생성된다.

### 3.2 Matrix Generator — 서비스 × 클러스터

```yaml
spec:
  generators:
    - matrix:
        generators:
          - git:
              repoURL: https://github.com/example/k8s-config.git
              revision: HEAD
              directories:
                - path: apps/*
          - clusters:
              selector:
                matchLabels:
                  env: prod
  template:
    metadata:
      name: '{{path.basename}}-{{name}}'
    spec:
      source:
        path: '{{path}}/overlays/{{metadata.labels.env}}'
      destination:
        server: '{{server}}'
        namespace: '{{path.basename}}'
```

`apps/*` 디렉터리 N 개와 prod 클러스터 M 개가 곱해져 N*M Application 이 자동 생성된다. 새 마이크로서비스를 추가하려면 `apps/<svc>/overlays/...` 디렉터리만 만들면 끝, 새 region 을 추가하려면 cluster secret 만 등록하면 끝이다.

### 3.3 Pull Request Generator — 동적 Preview 환경

```yaml
spec:
  generators:
    - pullRequest:
        github:
          owner: example
          repo: shop-api
          tokenRef: { secretName: github-token, key: token }
          labels: [ preview ]
        requeueAfterSeconds: 60
  template:
    metadata:
      name: 'shop-api-pr-{{number}}'
    spec:
      source:
        repoURL: https://github.com/example/shop-api.git
        targetRevision: '{{head_sha}}'
        path: deploy/preview
        kustomize:
          images: ['shop-api=ghcr.io/example/shop-api:{{head_sha}}']
      destination:
        server: https://k8s-preview.example.com
        namespace: 'pr-{{number}}'
      syncPolicy:
        automated: { prune: true }
```

PR 이 열리면 `pr-123` 네임스페이스에 자동 배포, PR 이 닫히면 ApplicationSet 이 해당 Application 을 자동 삭제한다. 60초 주기로 GitHub 를 polling 하므로 PR 이벤트와 환경 라이프사이클이 자동 동기화된다.

## 4. App-of-Apps vs ApplicationSet

이전 패턴인 App-of-Apps 는 부모 Application 이 자식 Application CR 들을 Git 의 manifest 로 들고 있는 방식이다.

```
parent-app/
├── child-shop-api.yaml      # kind: Application
├── child-shop-web.yaml
└── child-shop-batch.yaml
```

비교는 다음과 같다.

| 항목 | App-of-Apps | ApplicationSet |
| --- | --- | --- |
| 자식 정의 | Git 에 명시적 YAML | Generator 로 동적 |
| 새 환경 추가 | 매니페스트 N개 추가 PR | Cluster secret 또는 디렉터리 추가 |
| Drift 감지 | 부모-자식 양쪽 | ApplicationSet → Application |
| 자식 일괄 삭제 | 부모만 지우면 됨 | ApplicationSet 만 지우면 됨 |
| Preview 환경 | 어려움 | PR Generator |
| Progressive Sync | 직접 구현 필요 | 1급 지원 |

규모가 커지면 ApplicationSet 이 압도적으로 유지보수 비용이 낮다. 다만 generator 의 매개변수 추론이 잘못되면 의도치 않은 Application 이 대량 생성되며, `dryRun` 로 미리 확인하지 않으면 운영 사고가 된다.

## 5. Progressive Sync — 단계 롤아웃

`spec.strategy.type: RollingSync` 를 켜면 ApplicationSet 이 단계별로 sync 한다.

```yaml
spec:
  strategy:
    type: RollingSync
    rollingSync:
      steps:
        - matchExpressions:
            - { key: env, operator: In, values: [ canary ] }
        - matchExpressions:
            - { key: env, operator: In, values: [ prod ] }
              maxUpdate: 25%
        - matchExpressions:
            - { key: env, operator: In, values: [ prod ] }
              maxUpdate: 100%
```

순서는 다음과 같다. 1단계 canary 클러스터에 sync, 모두 Healthy 가 되면 2단계로 진행해 prod 의 25% 만 sync, 다시 Healthy 면 3단계 100%. 각 단계 사이에 자동으로 hold 가 들어가지만, 외부 메트릭(SLO, error rate) 으로 자동 abort 시키려면 Argo Rollouts 와 결합해야 한다. ApplicationSet 자체는 단순히 "Healthy 면 다음 단계" 외의 판단을 하지 않는다.

## 6. Sync Wave 와 의존성 정렬

같은 Application 안에서 리소스 간 순서가 필요한 경우 `argocd.argoproj.io/sync-wave` annotation 으로 정렬한다.

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
```

음수 wave 가 먼저, 0, 1, 2... 순서. CRD 설치(`-2`) → Operator 배포(`-1`) → CR 인스턴스(`0`) → 후처리 Job(`1`) 순으로 정렬해 의존성을 표현한다. 같은 wave 안에서는 병렬 sync 다. PreSync / Sync / PostSync hook 도 같은 annotation 으로 표현된다.

ApplicationSet 레벨의 RollingSync 와 Application 내부의 Sync Wave 는 직교한다. 전자는 Application 단위 순서, 후자는 Application 내부 manifest 순서.

## 7. 운영 트레이드오프와 측정값

50개 마이크로서비스 × 5개 클러스터(총 250 Application) 환경에서 실측.

| 지표 | App-of-Apps | ApplicationSet |
| --- | --- | --- |
| 신규 region 추가 | PR 50개 | cluster secret 1개 |
| 신규 서비스 추가 | PR 5개(클러스터 수만큼) | path 디렉터리 1개 |
| ArgoCD controller CPU | baseline | +10% (generator 평가) |
| Application reconcile 주기 | 3분(설정) | 동일 |

ApplicationSet controller 는 generator 결과를 메모리에 캐시하지만, PullRequest generator 처럼 외부 API 를 polling 하는 경우 GitHub rate limit 에 걸리지 않도록 `requeueAfterSeconds` 를 충분히 크게 잡아야 한다. 50개 ApplicationSet 이 각각 60초 주기로 GitHub API 를 호출하면 분당 50회 — 단일 토큰의 secondary rate limit 에 가깝다.

## 8. 보안 — Generator 의 SSRF 위험

Pull Request, SCM Provider, Plugin generator 는 외부 시스템과 통신한다. 잘못된 URL 이 들어오면 ApplicationSet controller 가 내부망의 의도치 않은 endpoint 를 호출할 수 있다(SSRF).

방어책은 다음과 같다. 첫째, ApplicationSet controller 의 NetworkPolicy 로 egress 를 명시적으로 제한한다. 둘째, `appsets-in-any-namespace` 옵션을 켜더라도 `argocd.argoproj.io/applicationset-namespace-allow-list` 에 신뢰 가능한 namespace 만 등록한다. 셋째, 일반 사용자에게 ApplicationSet `create/update` 권한을 주지 말고 별도 PR 리뷰 게이트를 두는 것이 안전하다.

## 참고

- Argo CD 공식 문서 (https://argo-cd.readthedocs.io)
- Argo CD ApplicationSet 문서 (https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/)
- Christian Hernandez, "GitOps with Argo CD" 시리즈
- CNCF Argo Project 케이스 스터디
- Argo Rollouts 와 ApplicationSet Progressive Sync 결합 패턴
