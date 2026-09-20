Notion 원본: https://www.notion.so/3e15a06fd6d381dd9cbbf9e2ef68b11a

# Argo CD 동기화 모델과 ApplicationSet 및 드리프트 감지 전략

> 2026-09-20 신규 주제 · 확장 대상: Docker&CI, AWS

## 학습 목표

- push CD 를 pull GitOps 로 옮길 때 이동하는 신뢰 경계를 식별한다
- `OutOfSync` 오탐을 3-way diff 계산에서 역추적하고 `ignoreDifferences` 로 차단한다
- Sync Option, sync wave, Resource Hook 으로 의존 순서가 있는 배포 시퀀스를 선언한다
- ApplicationSet 으로 멀티 클러스터 팬아웃을 구성하고 수백 Application 규모를 튜닝한다

## 1. push CD 와 pull GitOps 의 신뢰 경계

Actions 잡에서 `kubectl apply -f k8s/` 를 돌리는 push CD 의 본질은 **클러스터를 바꿀 자격증명이 CI 안에 있다**는 것이다. 워크플로를 고칠 수 있는 사람은 그 권한을 임의의 명령에 쓸 수 있고, 서드파티 액션의 공급망 침해가 곧바로 프로덕션 접근으로 번진다. pull GitOps 는 클러스터 안의 에이전트가 Git 을 읽어 적용하므로 CI 에 클러스터 자격증명이 필요 없다.

다만 신뢰 경계는 사라지지 않고 **이동**한다. `argocd-application-controller` 가 대상 클러스터들의 자격증명을 쥐므로 Argo CD 네임스페이스에 `secrets get` 이 있는 사람은 사실상 전 클러스터의 관리자이고, 통제 지점은 워크플로 리뷰에서 Argo CD RBAC·AppProject 로 옮겨간다.

| 축 | push CD | pull GitOps |
| --- | --- | --- |
| 자격증명 위치 | CI 러너 / IAM Role | 클러스터 내 controller |
| 드리프트 감지 | 없음(배포 시점만) | 주기 reconcile + selfHeal |
| 네트워크 방향 | CI → API 서버(인바운드) | controller → Git(아웃바운드) |

## 2. 컴포넌트 구조와 reconcile 루프

`argocd-application-controller`(StatefulSet)가 reconcile 의 주체로, watch API 로 클러스터 캐시를 유지하며 diff 를 계산하고 sync 를 실행한다. `argocd-repo-server` 는 Git 클론과 Helm/Kustomize 렌더링을 전담하는 stateless 워커로 결과를 Redis 에 캐싱하고(기본 24h), `argocd-server` 는 API/UI·RBAC·SSO 를 맡는다. 컨트롤러 워크큐는 reconcile(밀리초)과 sync operation(초)으로 분리된다.

폴링 주기 `timeout.reconciliation` 의 v3.5 문서 기준 기본값은 `120s` + jitter `60s` 로 실질 2~3분이다. 흔히 인용되는 "180초"는 지터 포함 최대값이거나 과거 기본값이므로 운영 버전을 직접 확인해야 한다.

```yaml
kind: ConfigMap
metadata: {name: argocd-cm, namespace: argocd}
data:
  timeout.reconciliation: "120s"
  timeout.reconciliation.jitter: "60s"   # 수백 앱 동시 refresh 스파이크 방지
  webhook.refresh.jitter.threshold: "10" # 영향 앱 10개 초과일 때만 지터
```

폴링만 쓰면 커밋에서 배포까지 최대 3분이 흐르므로, 실무에서는 Git 웹훅을 `argocd-server` 의 `/api/webhook` 에 걸어 즉시 refresh 하고 `timeout.reconciliation` 은 `15m` 등으로 늘린다. `0` 으로 끄면 웹훅 유실 시 드리프트를 못 잡는다.

## 3. 3-way diff 의 실제 계산과 Server-Side Apply 전환

기본(legacy) 전략은 **live**(현재 객체), **desired**(렌더링 결과), **last-applied-configuration**(동명 어노테이션의 직전 적용본) 셋을 비교한다. 세 번째가 있어야 삭제 의도와 남의 필드를 구분한다 — desired 에 없고 last-applied 에 있으면 삭제, 둘 다 없고 live 에만 있으면 남의 필드다. 이 어노테이션은 262144 바이트 제한이 있고 다른 도구가 덮어쓰면 diff 가 틀어진다.

`ServerSideApply=true` 를 켜면 `kubectl apply --server-side --force-conflicts` 를 쓰고 소유권이 `metadata.managedFields` 에 **field manager 단위**로 기록된다. 두 매니저가 같은 필드를 다투면 API 서버가 409 를 반환하지만 Argo CD 는 `--force-conflicts` 로 강제 취득하므로 HPA 가 소유한 `spec.replicas` 를 조용히 빼앗을 수 있다. HPA 대상 Deployment 에서 `replicas` 를 빼는 것이 정석인 이유다.

**Server-Side Diff**(v3.1.0 부터 Stable)는 리소스마다 SSA dry-run 을 돌려 예측 결과를 live 와 비교하므로 **admission controller 가 diff 단계에 참여**한다 — validating webhook 이 거부할 매니페스트를 sync 가 아니라 diff 시점에 알 수 있다. 신규 리소스 생성 시에는 dry-run 을 건너뛴다.

```yaml
# 전역: argocd-cmd-params-cm 에 아래 키 추가 후 컨트롤러 재시작
data: {controller.diff.server.side: "true"}
---
kind: Application                   # 앱 단위 + mutating webhook 결과까지 포함
metadata:
  annotations:
    argocd.argoproj.io/compare-options: ServerSideDiff=true,IncludeMutationWebhook=true
```

## 4. `OutOfSync` 오탐의 원인과 `ignoreDifferences`

sync 직후인데 바로 `OutOfSync` 인 상황은 거의 전부 "Git 에 없는 값을 다른 주체가 채워 넣었다"로 설명된다. **HPA 가 바꾸는 replicas**, **webhook 의 사이드카 주입**, **API 서버의 정규화**(`cpu: '1000m'` → `'1'`)가 대표적이다.

```yaml
kind: Application
spec:
  ignoreDifferences:
    - {group: apps, kind: Deployment, name: my-api, jsonPointers: [/spec/replicas]}
    - group: apps                       # 주입된 사이드카만 선택적으로
      kind: Deployment
      jqPathExpressions:
        - '.spec.template.spec.containers[] | select(.name == "istio-proxy")'
    - {group: '*', kind: '*', managedFieldsManagers: [kube-controller-manager]}
  syncPolicy:
    syncOptions: [RespectIgnoreDifferences=true]
```

선택 기준은 정밀도다. 필드 하나면 `jsonPointers`, 배열 항목을 내용으로 골라야 하면 `jqPathExpressions`, 컨트롤러 단위로 통째 잘라도 되면 `managedFieldsManagers` 다. 한편 `ignoreDifferences` 는 기본적으로 **diff 계산에만** 영향을 주므로 sync 를 실행하면 desired 가 그대로 적용되어 HPA 가 올린 replicas 가 도로 내려간다. 이 간극을 막는 것이 `RespectIgnoreDifferences=true` 이며 리소스가 이미 존재할 때만 유효하다.

## 5. Sync Options 와 selective sync

Sync Option 은 `spec.syncPolicy.syncOptions` 나 리소스의 `argocd.argoproj.io/sync-options` 어노테이션으로 지정한다.

| 옵션 | 효과와 주의점 |
| --- | --- |
| `CreateNamespace=true` | 네임스페이스 생성. `managedNamespaceMetadata` 로 라벨까지 관리하되 앱에 Namespace 매니페스트가 있으면 그쪽이 우선 |
| `PrunePropagationPolicy` | prune GC 전파(기본 foreground, `background`·`orphan` 가능) |
| `ApplyOutOfSyncOnly=true` | 변경분만 apply. 오브젝트 수천 개 앱의 API 부하를 크게 줄임 |
| `Replace=true` | `replace`/`create` 사용. 파괴적이며 `ServerSideApply` 보다 우선 |
| `ServerSideApply=true` | 어노테이션 크기 한계 회피, 부분 매니페스트 패치 가능(`Validate=false` 동반) |
| `Prune=confirm`·`Delete=confirm` | 삭제 전 사람 승인(`argocd.argoproj.io/deletion-approved` 또는 UI/CLI) |

**selective sync** 는 성격이 다르다. 일부 리소스만 골라 동기화하되 **Sync Hook 을 실행하지 않고 이력도 남기지 않는다**. 반면 `ApplyOutOfSyncOnly` 는 훅도 돌고 이력도 남는다. 장애 대응은 전자, 상시 최적화는 후자가 맞다.

## 6. Sync wave 와 Resource Hook 의 실행 순서

sync 는 `PreSync` → `Sync` → `PostSync` 로 나뉘고 실패하면 `SyncFail` 훅이 돈다(삭제 시에는 `PreDelete`·`PostDelete`). 페이즈 내부 순서가 sync wave 로, `argocd.argoproj.io/sync-wave` 에 정수를 넣고 낮은 값부터 적용된다(기본 0, 음수 가능). 전체 정렬은 **페이즈 → wave → kind(Namespace 먼저) → 이름** 순이다.

```yaml
metadata:            # DB 마이그레이션 Job
  annotations:
    argocd.argoproj.io/hook: PreSync
    argocd.argoproj.io/hook-delete-policy: HookSucceeded
    argocd.argoproj.io/sync-wave: "-2"
```

컨트롤러는 wave 하나를 적용한 뒤 그 리소스들이 Healthy 가 될 때까지 기다린다. wave 사이에는 기본 **2초** 지연(`ARGOCD_SYNC_WAVE_DELAY`)이 있는데, 없으면 오래된 객체를 보고 Healthy 로 오판해 다음 훅이 조기 발사된다. prune 때는 **순서가 뒤집혀** 높은 wave 부터 지워진다. 훅 정리는 `hook-delete-policy`(기본 `BeforeHookCreation`)로 하되 Job 에 `ttlSecondsAfterFinished` 를 거는 것은 피해야 한다 — Argo CD 가 Job 을 읽어 결과를 판정하는데 Kubernetes 가 먼저 지우면 sync 가 사라진 훅을 무한정 기다린다.

PreSync 는 같은 Application 의 Secret 보다 먼저 적용되므로, 마이그레이션 Job 이 그 Secret 을 읽으면 첫 로테이션 때 옛 값을 읽거나 실패한다. 훅은 매 sync 마다 돌아 멱등성도 필수다.

## 7. auto-sync + prune + selfHeal 이 만드는 위험

```yaml
spec:
  syncPolicy:
    automated: {enabled: true, prune: true, selfHeal: true, allowEmpty: false}
    retry: {limit: 5, backoff: {duration: 5s, factor: 2}}   # -1(무한)은 큐를 잠식
```

세 스위치를 모두 켠 조합은 GitOps 의 이상형처럼 보이지만 운영 사고가 가장 잦다. 장애 중 `kubectl scale deploy/my-api --replicas=20` 으로 급한 불을 끄면, self-heal 타임아웃(`--self-heal-timeout-seconds`, 기본 **5초**) 후 컨트롤러가 이를 드리프트로 판정해 Git 값으로 되돌린다. 응급 조치가 5초짜리 수명을 갖는 셈이다. `prune: true` 는 더 직접적이어서 Kustomize `resources` 에서 한 줄을 실수로 지운 커밋이 머지되면 그 리소스가 사라진다.

완화책은 층층이 쌓는다. (1) 긴급 시 끄는 절차를 문서화한다(`argocd app set my-api --sync-policy none`). (2) `allowEmpty: false` 로 렌더링이 빈 결과를 내는 사고가 전체 삭제로 번지지 않게 한다. (3) Namespace·PVC·CRD 에는 `Prune=confirm` 을 건다. (4) **Sync Window** 로 프로덕션 자동 sync 를 업무 시간에만 허용한다.

## 8. ApplicationSet 제너레이터와 Progressive Sync

ApplicationSet 컨트롤러는 제너레이터가 뱉은 파라미터 집합을 템플릿에 대입해 Application 을 양산한다. 제너레이터는 list(정적 목록), cluster(클러스터 시크릿 순회), git directory·git file(디렉터리 구조나 파일 내용), matrix(데카르트 곱), merge(공통 키 조인), pull request(PR 프리뷰), SCM provider(조직 리포 스캔), cluster decision resource, plugin 이 있다. `goTemplate` 과 `goTemplateOptions: ["missingkey=error"]` 를 함께 켜면 오타가 빈 문자열로 들어가는 사고를 렌더 단계에서 잡는다.

```yaml
kind: ApplicationSet
spec:
  goTemplate: true
  goTemplateOptions: ["missingkey=error"]
  generators:
    - matrix:                      # 클러스터 3개 × apps/* 8개 = Application 24개
        generators:
          - clusters: {selector: {matchLabels: {env: prod}}}
          - git:
              repoURL: https://github.com/keaunsolNa/platform-manifests.git
              revision: refs/heads/master   # 짧은 ref 는 repo-server 가 전 ref 를 순회
              directories: [{path: apps/*}]
  template:
    metadata: {name: '{{.path.basename}}-{{.name}}', labels: {envLabel: '{{.metadata.labels.env}}'}}
    spec:
      source: {repoURL: '…/manifests.git', targetRevision: refs/heads/master, path: '{{.path.path}}'}
      destination: {server: '{{.server}}', namespace: '{{.path.basename}}'}
      syncPolicy: {syncOptions: [CreateNamespace=true]}
  strategy:                        # Progressive Sync: dev 전체 → prod 10%씩
    type: RollingSync
    deletionOrder: Reverse         # 삭제는 역순(prod 먼저)
    rollingSync:
      steps:
        - matchExpressions: [{key: envLabel, operator: In, values: [env-dev]}]
        - matchExpressions: [{key: envLabel, operator: In, values: [env-prod]}]
          maxUpdate: 10%           # >0% 는 내림하되 최소 1개
```

**Progressive Sync(RollingSync)** 는 v3.3.0 기준 Beta 이며 `applicationsetcontroller.enable.progressive.syncs: "true"` 로 켜야 한다. 각 스텝의 모든 앱이 Healthy 가 되어야 다음으로 넘어간다. 한계도 분명하다. RollingSync 는 생성되는 **모든 Application 의 auto-sync 를 강제로 끄고**, 어느 스텝에도 매칭되지 않은 앱은 수동 sync 가 필요하다. 무엇보다 이 기능이 보는 것은 **Application 의 Health 뿐**이다. 즉 Progressive Sync 는 *클러스터·환경 사이*의 진행 제어이고, 카나리 비율을 올리며 Prometheus 쿼리로 자동 중단하는 *워크로드 안*의 제어는 **Argo Rollouts** 의 몫이라 둘은 함께 쓴다.

**App of Apps vs ApplicationSet.** App of Apps 는 Application 매니페스트 디렉터리를 가리키는 부모 Application 하나를 두는 패턴으로, 자식 스펙이 제각각이고 개수가 적은 플랫폼 부트스트랩(cert-manager, external-dns)에 적합하다. 자식들이 **같은 모양을 반복**하며 클러스터·환경 축으로 곱해지면 ApplicationSet 이다. 부트스트랩은 전자, 서비스는 후자로 나누는 2단 구성이 흔하다.

## 9. 시크릿 관리와 규모 한계 튜닝

시크릿을 평문으로 커밋하면 리포 읽기 권한이 곧 프로덕션 크레덴셜이 되고, Git 이력은 사실상 영구적이라 되돌리기 어렵다.

| 방식 | Git 에 저장 | 복호화 주체 | trade-off |
| --- | --- | --- | --- |
| SOPS (+age/KMS) | 암호문 | repo-server 플러그인 | 자기완결적이나 키 로테이션 시 전량 재암호화 |
| Sealed Secrets | 암호문 | 클러스터 내 controller 개인키 | 가장 단순. 키 분실 시 전량 재봉인 |
| External Secrets Operator | 참조만 | ESO 가 외부에서 fetch | Secrets Manager/SSM 직결, IRSA 인증. 값 변경이 Git 이력에 안 남음 |

EKS 에서 이미 Secrets Manager 를 쓴다면 ESO 가 자연스럽다. 다만 `ExternalSecret` 이 `status.refreshTime` 을 주기 갱신해 reconcile 을 유발하므로 `ignoreResourceUpdates` 설정이 함께 필요하다.

수백 Application 규모에서 병목은 거의 항상 repo-server 다. 렌더링은 fork/exec 이고 컨트롤러는 결과를 기다리다 타임아웃 나면 `Context deadline exceeded` 로 reconcile 을 실패시킨다. 레버는 (1) stateless 인 repo-server 증설, (2) `--repo-server-timeout-seconds` 상향, (3) `--parallelismlimit` 으로 OOMKill 방지 순이다. 모노레포라면 `argocd.argoproj.io/manifest-generate-paths` 를 반드시 붙인다 — 없으면 커밋 하나에 그 리포를 쓰는 **전 Application 의 캐시가 무효화**된다.

```yaml
kind: ConfigMap
metadata: {name: argocd-cmd-params-cm}
data:
  controller.status.processors: "50"      # 기본 20
  controller.operation.processors: "25"   # 기본 10
  controller.repo.server.timeout.seconds: "180"
  reposerver.parallelism.limit: "10"
```

컨트롤러는 샤딩으로 나눈다. StatefulSet 의 `replicas` 를 올리고 **같은 값을 `ARGOCD_CONTROLLER_REPLICAS` 에 반복해서 넣어야** 하며, 어긋나면 일부 클러스터가 어느 샤드에도 할당되지 않는다. 샤딩 단위는 **클러스터**이지 Application 이 아니므로 클러스터 하나에 Application 2000개라면 도움이 되지 않는다. 분배 알고리즘의 `round-robin`·`consistent-hashing` 은 여전히 Alpha 다.

Argo CD 는 릴리스가 잦고(2026-09-14 기준 최신 v3.5.3) 기본값이 마이너 버전 사이에 바뀌므로, 위 숫자는 운영 버전의 ConfigMap 과 문서로 교차 확인해야 한다.

## 참고

- Argo CD Sync Options — https://argo-cd.readthedocs.io/en/stable/user-guide/sync-options/
- Argo CD Diff Strategies — https://argo-cd.readthedocs.io/en/stable/user-guide/diff-strategies/
- Argo CD High Availability — https://argo-cd.readthedocs.io/en/stable/operator-manual/high_availability/
- ApplicationSet Progressive Syncs — https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Progressive-Syncs/
- Kubernetes Server-Side Apply — https://kubernetes.io/docs/reference/using-api/server-side-apply/
- OpenGitOps Principles — https://opengitops.dev/
