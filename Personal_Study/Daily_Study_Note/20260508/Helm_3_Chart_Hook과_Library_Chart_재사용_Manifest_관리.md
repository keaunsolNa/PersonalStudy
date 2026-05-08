Notion 원본: https://www.notion.so/35a5a06fd6d38163b42be741f7986224

# Helm 3 Chart Hook과 Library Chart 재사용 Manifest 관리

> 2026-05-08 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- Helm Chart Hook의 lifecycle phase, weight, delete-policy를 정확히 이해한다
- Library Chart 패턴으로 공통 helper 와 Manifest 템플릿을 여러 application chart 에 공유한다
- Application chart 와 Library chart 의 차이, dependency 선언 방식의 차이를 알고 적절히 분리한다
- hook resource 가 cluster 에 남아 누적되는 문제와 GitOps(ArgoCD) 환경에서의 호환성 제약을 다룬다

## 1. Helm Hook의 lifecycle 와 phase

| Hook 이름 | 시점 |
|---|---|
| pre-install | release 의 첫 install 직전 |
| post-install | release install 직후 |
| pre-upgrade / post-upgrade | upgrade 직전/직후 |
| pre-rollback / post-rollback | rollback 직전/직후 |
| pre-delete / post-delete | uninstall 직전/직후 |
| test | `helm test` 명령 시 |

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: db-migrate
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade
    "helm.sh/hook-weight": "-5"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: my-registry/migrator:1.4.0
          command: ["./migrate", "up"]
```

## 2. delete-policy 의 함정

| 정책 | 의미 |
|---|---|
| before-hook-creation (default) | 다음 번 같은 hook 이 만들어지기 직전에 이전 instance 삭제 |
| hook-succeeded | hook 이 성공하면 즉시 삭제 |
| hook-failed | hook 이 실패하면 삭제 |

GitOps(ArgoCD) 사용 시 권장 정책:

- `hook-succeeded`(또는 `hook-failed`) 를 명시해 hook resource 가 성공/실패 직후 사라지게
- `argocd.argoproj.io/hook` annotation 도 함께 추가

## 3. application chart vs library chart

| 구분 | application | library |
|---|---|---|
| 목적 | release 가능한 워크로드 정의 | helper 모음 |
| 설치 가능 | `helm install` 가능 | 직접 설치 불가능 |
| Manifest 산출 | values + template → manifest | helper define 만 제공 |

```yaml
# Chart.yaml (library)
apiVersion: v2
name: common-lib
type: library
version: 0.5.0
```

## 4. Library Chart 활용 — 공통 helper 패턴

```yaml
# common-lib/templates/_labels.tpl
{{- define "common.labels.standard" -}}
app.kubernetes.io/name: {{ include "common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end }}
```

```yaml
# myapp/Chart.yaml
apiVersion: v2
name: myapp
type: application
version: 1.0.0
dependencies:
  - name: common-lib
    version: 0.5.0
    repository: file://../common-lib
```

## 5. Library Chart 로 Manifest 자체를 재사용

```yaml
# common-lib/templates/_deployment.tpl
{{- define "common.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "common.name" . }}
spec:
  replicas: {{ .Values.replicas | default 2 }}
  template:
    spec:
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
{{- end }}
```

## 6. dependencies 와 alias / condition

```yaml
dependencies:
  - name: postgresql
    version: 14.x.x
    condition: postgresql.enabled
  - name: postgresql
    version: 14.x.x
    alias: pg-replica
    condition: pgReplica.enabled
```

## 7. Hook 디버깅과 검증 패턴

- `--debug` + `--dry-run` 으로 hook manifest 를 미리 확인
- failure 시 보존: `helm.sh/hook-delete-policy: hook-failed` 만 사용
- `kubectl wait --for=condition=complete job/db-migrate` 로 hook job 의 상태를 외부에서 watch
- application Pod 에 `initContainer` 로 같은 검증을 한 번 더 두기

DB 마이그레이션처럼 비가역적인 hook 은 반드시 idempotent 해야 한다. flyway, liquibase 처럼 `schema_history` 테이블로 이력을 관리하는 도구가 사실상 표준이다.

## 8. Helm vs Kustomize, GitOps 와의 궁합

GitOps 환경에서는 다음 두 가지 중 하나를 선택해야 한다.

- Helm 을 그대로 쓰면서 hook 을 Argo CD hook 으로 변환(annotation 추가)
- chart 를 `helm template` 으로 렌더해 정적 manifest 로 만들고, hook job 을 PreSync 로 분리

후자가 GitOps 원칙에 더 가깝다.

## 참고

- Helm 공식 문서 — Chart Hooks
- Helm 공식 문서 — Library Charts
- Bitnami common chart — github.com/bitnami/charts/tree/main/bitnami/common
- Argo CD 공식 문서 — Resource Hooks
- helm-charts-best-practices 시리즈 — CNCF blog
