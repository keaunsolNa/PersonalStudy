Notion 원본: https://app.notion.com/p/3d95a06fd6d381edb93bf181e3baf23f?pvs=204

# Crossplane 컨트롤 플레인과 Composition Functions 및 Claim 기반 인프라 추상화

> 2026-09-12 신규 주제 · 확장 대상: Terraform 상태 관리 / ArgoCD GitOps

## 학습 목표

- Terraform 의 상태 파일 기반 모델과 컨트롤 루프 기반 조정의 차이를 규명한다
- XRD / Composition / Claim 3계장이 만드는 추상화 경계를 분석한다
- Composition Functions 로 조건 분기와 반복을 표현하는 파이프라인을 작성한다
- 컨트롤 플레인 도입 비용과 드리트 자동 복구의 위험을 판단한다

## 1. 상태 파일 모델의 구조적 약점

Terraform 은 선언된 설정과 실제 인프라 사이에 **상태 파일**이라는 제3의 진실을 둔다.

```hcl
resource "aws_db_instance" "payroll" {
	identifier        = "payroll-prod"
	engine            = "postgres"
	engine_version    = "16.3"
	instance_class    = "db.r6g.xlarge"
	allocated_storage = 200
	backup_retention_period = 14
}
```

`terraform apply` 는 세 값을 바교한다. 설정(HCL), 상태(tfstate), 실제(API 조회). 세 값이 어긋나면 사람이 개입한다. 이 구조에서 나오는 운영 문제가 여럿 있다.

**조정이 이벤트가 아니라 명령이다** — `apply` 를 실행한 순간에만 조정이 일어난다. 누군가 콘솔에서 `instance_class` 를 바꿔도 다음 `apply` 까지 아무 일도 일어나지 않는다. 드리트 감지를 위해 `terraform plan` 을 주기적으로 돌리는 CI 액을 별도로 만들고, 감지했을 때 자동 복구할지 알림만 보낼지 정책을 또 만들어야 한다.

**상태 파일이 단일 장어점** — 원거 백엔드 록이 풀리지 않으면 전체 팀이 멈춘다. 상태와 실제가 어긋나면 `import`/`state rm`/`state mv` 같은 수술이 필요하고, 이 명령들은 되돌리기 어렵다.

**추상화의 계층이 얕다** — 모듈로 감싸 "표준 DB"를 제공할 수 있지만, 모듈 사용자는 여전히 Terraform 을 실행할 권한과 클라우드 자감 증명을 필요로 한다. 애플리케이션 팀에게 "DB 를 요구하는 인테페이스"만 주고 실행 권한은 플랫폼 팀이 갖는 구조를 만들기 어렵다.

Crossplane 은 **Kubernetes 컨트롤 루프를 인프라 조정 엔진으로 쓴다**. 인프라 리소스를 CRD 로 표현하고, 컨트롤러가 지속적으로 실제 상태를 조회해 선언과 맞춤다. 상태 파일이 없고, `status` 서부리소스와 클라우드 API 가 진실이다.

## 2. 세 개의 계층

Crossplane 의 개념은 세 층으로 나뉜다. 이 구분을 정확히 잡아야 나머지가 이해된다.

**Managed Resource (MR)** — 클라우드 리소스 하나에 1:1 대응하는 CRD. 프로바이더가 제공한다.

```yaml
apiVersion: rds.aws.upbound.io/v1beta2
kind: Instance
metadata:
  name: payroll-prod-db
spec:
  forProvider:
    region: ap-northeast-2
    engine: postgres
    engineVersion: "16.3"
    instanceClass: db.r6g.xlarge
    allocatedStorage: 200
    backupRetentionPeriod: 14
    dbSubnetGroupNameSelector:
      matchLabels:
        network: payroll-prod
    passwordSecretRef:
      namespace: crossplane-system
      name: payroll-db-password
      key: password
  writeConnectionSecretToRef:
    namespace: payroll
    name: payroll-db-conn
  providerConfigRef:
    name: aws-prod
```

`spec.forProvider` 아래는 프로바이더 API 스키마를 그대로 반영한다. Upbound 의 프로바이더는 Terraform 프로바이더 스키마에서 생성되므로 필드 이름이 Terraform 과 거의 같다. `writeConnectionSecretToRef` 는 생성 후 접속 정보(엔드포인트, 포트, 사용자명, 밀번호)를 지정한 네임스페이스의 Secret 으로 쓰 준다 — 애플리케이션이 그 Secret 을 볼륨이나 환경변수로 마운트하면 인프라와 앱이 연결된다.

`dbSubnetGroupNameSelector` 처럼 Selector 로 끝나는 필드는 **라벨 기반 참조 해석**이다. 서버넷 그룹의 실제 이름을 모르더라도 라벨로 찾게 두면, 컨트롤러가 매칭되는 리소스를 찾아 이름을 채운다. 이 간접 참조가 콤포지션에서 리소스 간 연결을 만드는 주요 수단이다.

**Composite Resource Definition (XRD)** — 애플리케이션 팀에게 노출할 **추상 API** 를 정의한다.

```yaml
apiVersion: apiextensions.crossplane.io/v1
kind: CompositeResourceDefinition
metadata:
  name: xpostgresqlinstances.platform.igis.io
spec:
  group: platform.igis.io
  names:
    kind: XPostgreSQLInstance
    plural: xpostgresqlinstances
  claimNames:
    kind: PostgreSQLInstance
    plural: postgresqlinstances
  defaultCompositionRef:
    name: postgres-aws-standard
  versions:
    - name: v1alpha1
      served: true
      referenceable: true
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              properties:
                parameters:
                  type: object
                  properties:
                    size:
                      type: string
                      enum: [small, medium, large]
                      default: small
                    environment:
                      type: string
                      enum: [dev, stage, prod]
                    highAvailability:
                      type: boolean
                      default: false
                    retentionDays:
                      type: integer
                      minimum: 1
                      maximum: 35
                      default: 7
                  required: [environment]
              required: [parameters]
            status:
              type: object
              properties:
                endpoint:
                  type: string
```

이 스키마가 계약이다. 애플리케이션 팀은 `size`, `environment`, `highAvailability`, `retentionDays` 네 개만 알으며, VPC·서버넷 그룹·파라미터 그룹·보안 그룹·인스턴스 클래스 매핑은 전부 감춰진다. `enum` 과 `minimum`/`maximum` 은 Kubernetes API 서버가 검증하므로 잘못된 값은 `kubectl apply` 시점에 거부된다 — 파이프라인을 돌리기 전에 막힌다.

**Claim** — 네임스페이스 범위의 요구 객체. 애플리케이션 팀이 실제로 쓰는 것이다.

```yaml
apiVersion: platform.igis.io/v1alpha1
kind: PostgreSQLInstance
metadata:
  name: payroll-db
  namespace: payroll
spec:
  parameters:
    size: medium
    environment: prod
    highAvailability: true
    retentionDays: 14
  writeConnectionSecretToRef:
    name: payroll-db-conn
```

Claim 이 생성되면 Crossplane 이 대상하는 클러스터 범위 Composite Resource(XR)를 만들고, Composition 을 적용해 Managed Resource 들을 생성한다. 네임스페이스 RBAC 만으로 인프라 요구 권한을 관리할 수 있다는 점이 실용적 핵심이다. 애플리케이션 팀은 클라우드 자감 증명을 전혀 갖지 않는다.

| 계층 | 범위 | 소유자 | 대상 개념(Terraform) |
|---|---|---|---|
| Managed Resource | 클러스터 | 플랫폼 | `resource` 바록 |
| Composition | 클러스터 | 플랫폼 | 모듈 구현 |
| XRD | 클러스터 | 플랫폼 | 모듈 variables 스키마 |
| Composite Resource | 클러스터 | Crossplane 내부 | 모듈 인스턴스 |
| Claim | 네임스페이스 | 애플리케이션 팀 | 모듈 호출 |

## 3. Composition — 추상에서 구제로

Composition 은 XR 하나를 여러 MR 로 폈치는 재칙이다. 초기 Crossplane 은 `patches` 배열로 필드를 매핑하는 선언적 방식만 지원했다.

```yaml
# 레거시 patch-and-transform 스타일 — 구조 설명용
resources:
  - name: rds-instance
    base:
      apiVersion: rds.aws.upbound.io/v1beta2
      kind: Instance
      spec:
        forProvider:
          engine: postgres
    patches:
      - fromFieldPath: spec.parameters.size
        toFieldPath: spec.forProvider.instanceClass
        transforms:
          - type: map
            map:
              small: db.t4g.medium
              medium: db.r6g.xlarge
              large: db.r6g.4xlarge
```

이 방식의 한계는 **조건과 반복을 표현할 수 없다**는 것이다. `highAvailability: true` 일 때만 읽기 복제본을 추가하거나, 요구된 리전 목록마다 리소스를 반복 생성하는 것이 불가능하다. 우회책으로 Composition 을 환경별로 여러 개 만들고 `compositionSelector` 로 고르는 패턴이 쓰여는데, 조합 수가 늘면 Composition 이 폭발한다.

Composition Functions 가 이 문제를 푸는다. Composition 을 **함수 파이프라인**으로 정의하고, 각 함수가 원하는 리소스 목록을 계산해 다음 함수로 넘긴다.

```yaml
apiVersion: apiextensions.crossplane.io/v1
kind: Composition
metadata:
  name: postgres-aws-standard
spec:
  compositeTypeRef:
    apiVersion: platform.igis.io/v1alpha1
    kind: XPostgreSQLInstance
  mode: Pipeline
  pipeline:
    - step: render-templates
      functionRef:
        name: function-go-templating
      input:
        apiVersion: gotemplating.fn.crossplane.io/v1beta1
        kind: GoTemplate
        source: Inline
        inline:
          template: |
            {{- $params := .observed.composite.resource.spec.parameters }}
            {{- $classMap := dict "small" "db.t4g.medium" "medium" "db.r6g.xlarge" "large" "db.r6g.4xlarge" }}
            apiVersion: rds.aws.upbound.io/v1beta2
            kind: Instance
            metadata:
              annotations:
                gotemplating.fn.crossplane.io/composition-resource-name: primary
            spec:
              forProvider:
                region: ap-northeast-2
                engine: postgres
                engineVersion: "16.3"
                instanceClass: {{ get $classMap $params.size }}
                allocatedStorage: {{ if eq $params.environment "prod" }}200{{ else }}20{{ end }}
                backupRetentionPeriod: {{ $params.retentionDays }}
                multiAZ: {{ $params.highAvailability }}
                deletionProtection: {{ eq $params.environment "prod" }}
              writeConnectionSecretToRef:
                namespace: crossplane-system
                name: {{ .observed.composite.resource.metadata.name }}-primary-conn
            ---
            {{- if $params.highAvailability }}
            apiVersion: rds.aws.upbound.io/v1beta2
            kind: Instance
            metadata:
              annotations:
                gotemplating.fn.crossplane.io/composition-resource-name: replica
            spec:
              forProvider:
                region: ap-northeast-2
                instanceClass: {{ get $classMap $params.size }}
                replicateSourceDbSelector:
                  matchControllerRef: true
            {{- end }}
    - step: auto-ready
      functionRef:
        name: function-auto-ready
```

핵심 변화가 세 가지다.

**조건 분기** — `if $params.highAvailability` 로 복제본을 조건 생성한다. Composition 하나로 HA 구성과 단일 구성을 모두 커버한다.

**계산** — `dict` 와 `get` 으로 size 에서 instanceClass 로 가는 매핑을 표현하고, 환경 바교로 환경별 값을 분기한다. `patches` 의 `map` 트랜스폼보다 훨씬 자유롭다.

**관재된 상태 접근** — `.observed` 아래에 XR 의 현재 spec/status 와 이미 생성된 MR 들의 실제 상태가 들어온다. 이전 단계의 결과를 보고 다음 리소스를 결정하는 다단계 프로버젬닝이 가능하다. `.desired` 에는 파이프라인 이전 단계가 계산한 리소스가 들어 있어 누적 변환이 된다.

`function-auto-ready` 는 모든 구성 리소스가 준버되었을 때 XR 을 Ready 로 표시한다. 이 함수를 빼면 XR 이 영원하 준버되지 않은 상태로 남으므로 파이프라인 마지막 단계에 관용적으로 넣는다.

함수는 gRPC 서버로 동작하는 독립 컨테이너다. 따라서 Go 로 직접 작성할 수도 있고, KCL·Python·CUE 기반 함수도 커뮤니티에 있다. 복잡한 로직이 필요하면 Go 함수를 작성해 단위 테스트를 붙이는 것이 실무적으로 가장 견고하다.

```yaml
apiVersion: pkg.crossplane.io/v1beta1
kind: Function
metadata:
  name: function-go-templating
spec:
  package: xpkg.upbound.io/crossplane-contrib/function-go-templating:v0.9.2
---
apiVersion: pkg.crossplane.io/v1beta1
kind: Function
metadata:
  name: function-auto-ready
spec:
  package: xpkg.upbound.io/crossplane-contrib/function-auto-ready:v0.4.1
```

## 4. 렌더링 검증 — apply 전에 확인하기

Composition 을 클러스터에 넣고 Claim 을 만들어 결과를 보는 방식은 피드백이 느리다. `crossplane render` 가 로컬에서 파이프라인을 실행해 결과 MR 을 출력한다.

```bash
crossplane render xr.yaml composition.yaml functions.yaml
```

`xr.yaml` 에 테스트할 XR 을, `functions.yaml` 에 함수 정의를 넣으면 생성될 MR 매니페스트가 표준 출력으로 나온다. Docker 가 함수 컨테이너를 실행하므로 실제 파이프라인과 동일한 결과다. 이것을 CI 에 넣어 골든 파일과 바교하면 Composition 회귀를 잡는다.

```bash
#!/usr/bin/env bash
set -euo pipefail

for case in tests/cases/*; do
	name=$(basename "$case")
	crossplane render "$case/xr.yaml" composition.yaml functions.yaml > "/tmp/$name.actual"
	if ! diff -u "$case/expected.yaml" "/tmp/$name.actual"; then
		echo "FAIL: $name"
		exit 1
	fi
	echo "PASS: $name"
done
```

추는 size, environment, highAvailability 조합의 대표값으로 구성한다. 특히 **경계 조합**(prod + small, dev + HA)이 의도한 결과를 내는지 확인해야 한다. `enum` 으로 막지 못하는 논리적 모순은 함수 안에서 검증하고 에러를 반환한다.

```yaml
    - step: validate
      functionRef:
        name: function-go-templating
      input:
        apiVersion: gotemplating.fn.crossplane.io/v1beta1
        kind: GoTemplate
        source: Inline
        inline:
          template: |
            {{- $p := .observed.composite.resource.spec.parameters }}
            {{- if and (eq $p.environment "prod") (eq $p.size "small") }}
            {{- fail "prod 환경에는 size=small 을 허용하지 않는다" }}
            {{- end }}
```

## 5. 컨트롤 루프의 양면 — 자동 복구와 그 위험

Crossplane 컨트롤러는 주기적으로 클라우드 API 를 조회해 `spec` 과 바교하고 다르면 고친다. 기본 동기화 간간은 프로바이더 설정으로 조절한다.

이 동작은 드리트 자동 복구를 공짜로 준다. 누가 콘솔에서 `backupRetentionPeriod` 를 7로 내리면 다음 조정 주기에 14로 되돌아간다. Terraform 에서 별도 CI 액으로 구현해야 했던 것이 기본 동작이다.

그러나 같은 성질이 위험이기도 하다. **장어 대상 중 사람이 한 임시 조치를 컨트롤러가 되돌린다.** 트래픽 폭증으로 인스턴스 클래스를 급해 올렸는데 Git 의 Claim 이 그대로면 다음 조정에서 다운스킬된다. 운영 사고로 직결되는 시나리오다.

이 문제에 대한 통제 수단이 `managementPolicies` 와 정지 어노테이션이다.

```bash
# 특정 MR 의 조정을 일시 중단 — 장어 대상 창구
kubectl annotate instance payroll-prod-db crossplane.io/paused=true
```

```yaml
# 관재만 하고 변경하지 않는 정책
spec:
  managementPolicies: ["Observe"]
```

`managementPolicies` 는 Create/Update/Delete/Observe/LateInitialize 조합으로 지정한다. 기존 인프라를 Crossplane 으로 가지고올 때 먼저 Observe 만 붙여 상태를 읽고, 매니페스트가 실제와 일치하는지 확인한 뒤 전체 권한으로 전환하는 것이 안전한 임포트 절차다.

**삭제 정책**은 특히 심중해야 한다. Claim 을 삭제하면 XR 이 삭제되고 MR 이 삭제되며 클라우드 리소스가 실제로 없어진다. `kubectl delete` 한 줄이 프로덕션 DB 를 지울 수 있다.

```yaml
spec:
  deletionPolicy: Orphan   # MR 삭제 시 클라우드 리소스는 남긴다
  forProvider:
    deletionProtection: true
```

프로덕션 데이터 장소에는 `deletionPolicy: Orphan` 과 클라우드 산 `deletionProtection` 을 **둘 다** 건다. 추가로 Kyverno 나 OPA Gatekeeper 로 프로덕션 네임스페이스의 Claim 삭제를 차단하는 정책을 두는 것이 실무 관행이다.

## 6. GitOps 와의 결합

Crossplane 자체는 Git 을 모른다. 리소스가 Kubernetes 객체이므로 ArgoCD 나 Flux 가 그대로 동기화한다. 조합 구조는 두 층으로 나뉜다.

**플랫폼 레팜** — Provider, ProviderConfig, Function, XRD, Composition. 플랫폼 팀이 소유하고 리뷰 게이트가 엄경하다. ArgoCD Application 하나로 `crossplane-system` 에 동기화한다.

**애플리케이션 레팜** — Claim 만. 애플리케이션 팀이 소유하고, 자기 네임스페이스에만 권한이 있다. Claim 은 Deployment 와 같은 레팜에 두어 "앱과 그 앱이 필요한 인프라"를 함께 버전 관리한다.

Deployment 가 `payroll-db-conn` Secret 을 참조하고 그 Secret 은 Claim 이 만들므로 순서 의존이 생긴다. ArgoCD 의 sync wave 로 Claim 을 먼저 배포한다.

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
```

Secret 이 준버되기 전 Pod 가 뜨면 CrashLoop 가 되지만 Secret 생성 후 재시작으로 복구된다. 다만 초기 프로버젬닝이 수 분 걸리므로 Argo 의 sync timeout 을 뇄넷하게 잔거나 재시도를 구성한다.

ArgoCD 가 Crossplane 이 채우는 `status` 필드를 드리트로 오인하는 문제가 있다. `ignoreDifferences` 로 제외한다.

```yaml
spec:
  ignoreDifferences:
    - group: platform.igis.io
      kind: PostgreSQLInstance
      jsonPointers:
        - /status
    - group: "*"
      kind: "*"
      managedFieldsManagers:
        - crossplane
```

## 7. 도입 판단 — 언제 쓰지 말아야 하는가

Crossplane 은 **플랫폼 팀이 있고 여러 애플리케이션 팀에 셀프서버스 인프라를 제공해야 할 때** 가치가 있다. 그 조건이 아니면 비용이 이득을 넘는다.

비용 항목이 구제적이다. Kubernetes 클러스터 자체가 인프라 프로버젬닝의 전제가 되므로 **부트스트랩 순환**이 생긴다 — 클러스터를 만들 도구가 따로 필요하다(대게 Terraform 이나 eksctl 로 관리 클러스터만 만드다). 프로바이더 CRD 는 수백 개에서 수천 개 규모라 API 서버 메모리와 etcd 부담이 크고, 필요한 리소스만 단은 축소 프로바이더(provider-family 분할)를 쓰는 튜닝이 필요하다. 프로바이더 버전 업그레이드는 CRD 스키마 변경을 수반하므로 기존 MR 매니페스트가 깨질 수 있다. Composition 디버깅은 XR 의 `status.conditions` 와 이벤트, 각 MR 의 상태를 따라가야 해서 `terraform plan` 의 명료함에 모자란다.

반대로 Terraform 이 유리한 경우도 분명하다. 인프라 변경이 드므고 사람이 검토 후 적용하는 편이 나은 경우, 인프라가 소수 팀에 의해 중앙 관리되는 경우, 클라우드가 아닌 온프레미스 리소스가 주인 경우다. 실제로 두 도구를 **계층 분리**해 쓰는 조합이 흔하다. 네트워크·클러스터·IAM 같은 기반 레이어는 Terraform 으로, 애플리케이션이 요구하는 DB·큐·버킷은 Crossplane Claim 으로 관리한다. 변경 번도와 요구 주체가 다르기 때문에 도구도 다르게 둘 수 있다.

한 가지 더, Crossplane 은 Terraform 을 감싸는 프로바이더(`provider-terraform`)도 제공한다. 기존 Terraform 모듈 자산이 많다면 이것을 MR 로 래핑해 점진적으로 옮기는 경로가 있다. 다만 이 경로는 Terraform 실행을 파드 안에서 수행하므로 상태 파일 문제가 되살아나며, 과도기 수단으로만 쓴다.

## 참고

- Crossplane 공식 문서 — Composition 과 Composition Functions: https://docs.crossplane.io/latest/concepts/compositions/
- Crossplane 공식 문서 — Composite Resource Definitions: https://docs.crossplane.io/latest/concepts/composite-resource-definitions/
- Crossplane 공식 문서 — Managed Resources 와 management policies: https://docs.crossplane.io/latest/concepts/managed-resources/
- Upbound Provider AWS 레퍼런스: https://marketplace.upbound.io/providers/upbound/provider-family-aws
- Kubernetes 공식 문서 — Custom Resources 와 컨트롤러 패턴: https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/
- ArgoCD 공식 문서 — Sync Waves: https://argo-cd.readthedocs.io/en/stable/user-guide/sync-waves/
