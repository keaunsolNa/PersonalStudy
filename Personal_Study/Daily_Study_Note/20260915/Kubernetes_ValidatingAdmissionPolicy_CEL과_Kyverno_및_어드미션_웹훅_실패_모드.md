Notion 원본: https://www.notion.so/3db5a06fd6d381aa9701e78534dfec98

# Kubernetes ValidatingAdmissionPolicy CEL과 Kyverno 및 어드미션 웹훅 실패 모드

> 2026-09-15 신규 주제 · 확장 대상: Kubernetes Operator 패턴과 controller-runtime Reconcile Loop

## 학습 목표

- API 서버 요청 처리 파이프라인에서 어드미션 단계의 위치와 순서를 정확히 짚는다
- CEL 기반 `ValidatingAdmissionPolicy` 로 네트워크 홉 없는 정책을 작성한다
- 웹훅 `failurePolicy`·`timeoutSeconds` 오설정이 만드는 클러스터 전체 장애를 예방한다
- 내장 정책과 Kyverno 를 역할에 따라 나누어 배치한다

## 1. 어드미션은 어디에 있는가

kubectl 이 보낸 요청이 etcd 에 도달하기까지의 경로는 다음과 같다.

```
HTTP 요청
  → 인증(Authentication)        : 너는 누구인가
  → 인가(Authorization, RBAC)   : 그 일을 할 권한이 있는가
  → Mutating Admission          : 객체를 고친다 (내장 플러그인 → MutatingWebhook)
  → Object Schema Validation    : OpenAPI 스키마·필드 검증
  → Validating Admission        : 받아들일지 결정한다 (ValidatingAdmissionPolicy / Webhook)
  → etcd 저장
```

중요한 성질이 셋이다.

첫째, **변경(mutating)이 검증(validating)보다 먼저**다. 사이드카 주입이나 기본값 채우기는 mutating 단계에서 일어나고, 검증은 그 결과를 본다. 그래서 "사이드카가 주입됐는지" 검증하는 정책은 정상 동작하지만, mutating 웹훅이 검증 웹훅의 결과에 의존하게 만들 수는 없다.

둘째, mutating 웹훅끼리는 **순서가 보장되지 않는다**. 웹훅 A 가 넣은 필드를 웹훅 B 가 보아야 한다면 `reinvocationPolicy: IfNeeded` 로 재호출을 허용해야 하고, 그러면 웹훅은 **멱등**해야 한다. 두 번 호출돼도 같은 결과를 내지 않으면 사이드카가 두 개 붙는다.

셋째, 어드미션은 **쓰기 요청에만** 관여한다. 이미 클러스터에 있는 리소스는 검사하지 않는다. 정책을 새로 배포해도 기존 워크로드는 그대로 돌아가므로, 기존 자원 준수 여부는 별도 스캔(Kyverno 백그라운드 리포트 등)으로 확인해야 한다.

## 2. ValidatingAdmissionPolicy — 프로세스 안에서 도는 정책

전통적인 방식은 웹훅이다. API 서버가 외부 HTTPS 엔드포인트를 호출해 판정을 받는다. 네트워크 홉이 생기고, 그 서비스가 죽으면 클러스터가 흔들린다.

`ValidatingAdmissionPolicy` 는 정책을 CEL(Common Expression Language) 표현식으로 선언하고 **API 서버 프로세스 안에서 직접 평가**한다. 1.26 알파, 1.28 베타를 거쳤 1.30 에서 GA 됐다. 외부 컴포넌트가 없으므로 가용성 문제 자체가 사라진다.

정책은 두 리소스로 나뉜다. **정책 본문**과 **적용 범위 바인딩**이다.

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: require-resource-limits
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
      - apiGroups: ["apps"]
        apiVersions: ["v1"]
        operations: ["CREATE", "UPDATE"]
        resources: ["deployments", "statefulsets"]
  matchConditions:
    - name: skip-system-namespaces
      expression: "!(request.namespace in ['kube-system', 'kube-public'])"
  variables:
    - name: containers
      expression: "object.spec.template.spec.containers"
    - name: limited
      expression: >-
        variables.containers.filter(c,
          has(c.resources) && has(c.resources.limits) &&
          has(c.resources.limits.memory) && has(c.resources.limits.cpu))
  validations:
    - expression: "size(variables.containers) == size(variables.limited)"
      message: "모든 컨테이너에 cpu/memory limits 가 필요합니다"
      reason: Invalid
    - expression: >-
        variables.containers.all(c,
          quantity(c.resources.limits.memory).isGreaterThan(quantity('64Mi')))
      messageExpression: >-
        "memory limit 은 64Mi 초과여야 합니다 (네임스페이스: " + request.namespace + ")"
```

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: require-resource-limits-binding
spec:
  policyName: require-resource-limits
  validationActions: ["Deny"]
  matchResources:
    namespaceSelector:
      matchLabels:
        policy.company.io/enforce-limits: "true"
```

분리 구조가 만드는 이점이 크다. 같은 정책을 **네임스페이스 그룹마다 다른 강도로** 적용할 수 있다. 신규 팀은 `["Deny"]`, 이행 중인 팀은 `["Warn", "Audit"]` 로 바인딩한다.

정책을 만들 때 실제 운영 순서는 `Audit` → `Warn` → `Deny` 다. 감사 로그로 위반 규모를 먼저 파악하고, 경고로 개발자에게 알리고, 마지막에 잠그다. 처음부터 `Deny` 로 배포하면 배포 파이프라인이 한꺼번에 멈춘다.

### CEL 표현식에서 쓸 수 있는 것

| 변수 | 내용 |
|---|---|
| `object` | 요청된 새 객체 (DELETE 시 null) |
| `oldObject` | 기존 객체 (CREATE 시 null) |
| `request` | AdmissionRequest 메타데이터 (namespace, userInfo, operation 등) |
| `params` | `paramKind`/`paramRef` 로 연결한 설정 객체 |
| `namespaceObject` | 대상 네임스페이스 객체 (레이블·애너테이션 접근) |
| `authorizer` | 요청자의 권한을 정책 안에서 조회 |

`authorizer` 는 강력하다. "이 애너테이션을 붙이려면 해당 리소스에 대한 별도 권한이 있어야 한다" 같은 규칙을 RBAC 만으로는 표현할 수 없는데, 정책 안에서 권한을 되물을 수 있다.

```yaml
validations:
  - expression: >-
      !has(object.metadata.annotations) ||
      !('company.io/bypass-quota' in object.metadata.annotations) ||
      authorizer.group('').resource('resourcequotas')
        .namespace(request.namespace).check('delete').allowed()
    message: "bypass-quota 애너테이션은 quota 관리 권한자만 설정할 수 있습니다"
```

파라미터화도 유용하다. 정책 로직은 하나, 값은 CRD 나 ConfigMap 으로 분리한다.

```yaml
spec:
  paramKind:
    apiVersion: v1
    kind: ConfigMap
  validations:
    - expression: >-
        object.spec.template.spec.containers.all(c,
          c.image.startsWith(params.data['allowedRegistry']))
```

### 제약과 한계

CEL 은 튜링 완전하지 않다. 무한 루프가 불가능하고, 표현식마다 **비용(cost) 예산**이 계산되어 상한을 넘으면 평가가 거부된다. 이는 API 서버를 보호하기 위한 의도적 설계다. 대신 다음은 불가능하다.

- 외부 시스템 조회(이미지 레지스트리에 태그 존재 여부 질의, 이미지 서명 검증)
- 클러스터의 다른 리소스 조회(같은 네임스페이스의 다른 Pod 개수 세기)
- 객체 변경(1.32 에서 CEL 기반 `MutatingAdmissionPolicy` 가 알파로 들어갔으나, 성숙도는 버전별로 확인이 필요하다)

즉 **"이 객체만 보면 판정 가능한 규칙"**이 내장 정책의 영역이다. 그 밖은 여전히 웹훅이 필요하다.

## 3. 웹훅의 실패 모드 — 클러스터를 멈추는 법

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingWebhookConfiguration
metadata:
  name: policy-validator
webhooks:
  - name: validate.policy.company.io
    failurePolicy: Fail          # ← 여기가 핵심
    timeoutSeconds: 5            # 기본 10, 최대 30
    sideEffects: None
    admissionReviewVersions: ["v1"]
    matchPolicy: Equivalent
    namespaceSelector:
      matchExpressions:
        - key: kubernetes.io/metadata.name
          operator: NotIn
          values: ["kube-system", "policy-system"]   # ← 자기 자신과 시스템 제외
    rules:
      - apiGroups: ["apps"]
        apiVersions: ["v1"]
        operations: ["CREATE", "UPDATE"]
        resources: ["deployments"]
        scope: Namespaced
    clientConfig:
      service:
        namespace: policy-system
        name: policy-validator
        path: /validate
        port: 443
      caBundle: <base64>
```

**`failurePolicy: Fail` + 자기 네임스페이스 미제외**가 고전적인 자살 조합이다. 웹훅 파드가 전부 죽으면, 그 파드를 다시 만들려는 요청조차 웹훅 호출에 실패해 거부된다. 복구 방법은 `ValidatingWebhookConfiguration` 을 수동 삭제하는 것뿐이고, 그 시점에는 이미 클러스터 전역이 멈춰 있다. 예방책은 셋이다.

1. 웹훅 자신의 네임스페이스와 `kube-system` 을 `namespaceSelector` 로 제외한다.
2. `rules` 의 `resources` 를 최소화한다. `resources: ["*"]` 는 거의 항상 잘못된 선택이다.
3. 웹훅 파드를 노드 분산 + PDB 로 보호하고, 최소 2 레플리카를 유지한다.

**`timeoutSeconds`** 는 짧게 잡는다. API 서버는 이 시간만큼 요청을 붙잡고 있으며, 웹훅이 느려지면 API 서버의 요청 슬롯이 소진된다. 5초를 넘기는 판정 로직이라면 어드미션에서 할 일이 아니다.

**`sideEffects`** 는 정직하게 선언해야 한다. `None` 이 아니면 `kubectl apply --dry-run=server` 가 웹훅을 호출하지 않거나 거부한다. 웹훅이 외부 상태를 건드린다면 `NoneOnDryRun` 으로 선언하고 `request.dryRun` 을 실제로 확인해 부작용을 건너뛰어야 한다.

**`matchPolicy: Equivalent`** 를 기본으로 둔다. `Exact` 면 같은 리소스를 다른 API 버전으로 보냈을 때 웹훅이 호출되지 않아, 정책 우회 경로가 생긴다.

마지막으로 **인증서 만료**가 조용한 살인자다. `caBundle` 과 서빙 인증서가 만료되면 TLS 핸드쉐이크가 실패하고, `failurePolicy: Fail` 이면 즉시 장애다. cert-manager 로 자동 갱신하고 만료 30일 전 알람을 건다.

## 4. Kyverno — 정책 엔진이 필요한 영역

내장 정책이 못 하는 일이 Kyverno 의 자리다.

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: baseline-controls
spec:
  background: true
  rules:
    # 1) mutate — 기본값 주입
    - name: add-default-labels
      match:
        any:
          - resources:
              kinds: ["Deployment"]
      mutate:
        patchStrategicMerge:
          metadata:
            labels:
              app.kubernetes.io/managed-by: "{{ request.userInfo.username }}"

    # 2) generate — 파생 리소스 자동 생성
    - name: default-network-policy
      match:
        any:
          - resources:
              kinds: ["Namespace"]
      generate:
        apiVersion: networking.k8s.io/v1
        kind: NetworkPolicy
        name: default-deny-ingress
        namespace: "{{ request.object.metadata.name }}"
        synchronize: true
        data:
          spec:
            podSelector: {}
            policyTypes: ["Ingress"]

    # 3) verifyImages — 외부 조회가 필요한 검증
    - name: verify-signature
      match:
        any:
          - resources:
              kinds: ["Pod"]
      verifyImages:
        - imageReferences: ["registry.company.io/*"]
          attestors:
            - entries:
                - keyless:
                    subject: "https://github.com/company/*"
                    issuer: "https://token.actions.githubusercontent.com"
```

`generate` 와 `verifyImages` 는 CEL 정책으로 대체 불가능하다. 전자는 새 리소스를 만들고, 후자는 레지스트리와 Sigstore 에 네트워크 요청을 보낸다.

`background: true` 는 어드미션과 별개로 **이미 존재하는 리소스**를 주기적으로 스캔해 `PolicyReport` 를 생성한다. 앞서 말한 "어드미션은 기존 자원을 보지 않는다"는 한계를 메우는 장치다.

Kyverno 1.10 이후 컨트롤러가 역할별로 분리됐다(어드미션, 백그라운드, 리포트, 클린업). 어드미션 컨트롤러만 API 서버 요청 경로에 있으므로, 가용성 확보는 이 컴포넌트에 집중하면 된다. 그리고 Kyverno 역시 웹훅이므로 3절의 실패 모드가 그대로 적용된다. Kyverno 자신의 네임스페이스는 반드시 정책 대상에서 제외한다.

## 5. 역할 분담 설계

| 요구 | 도구 | 이유 |
|---|---|---|
| 필드 존재·값 범위 검사 | ValidatingAdmissionPolicy | 네트워크 홉 없음, 가용성 문제 없음 |
| 레지스트리 허용 목록(문자열 접두사) | ValidatingAdmissionPolicy | 객체만 보면 판정 가능 |
| 요청자 권한 연동 검사 | ValidatingAdmissionPolicy (`authorizer`) | RBAC 로 표현 불가한 조건 |
| 기본 레이블·사이드카 주입 | Kyverno mutate / 전용 웹훅 | 객체 변경 필요 |
| 네임스페이스 생성 시 부속 리소스 | Kyverno generate | 다른 리소스 생성 필요 |
| 이미지 서명·SBOM 검증 | Kyverno verifyImages | 외부 조회 필요 |
| 기존 리소스 준수 현황 | Kyverno background + PolicyReport | 어드미션 범위 밖 |
| 파드 보안 기준(PSS) | Pod Security Admission | 내장 기능, 별도 정책 불필요 |

주목할 항목은 마지막 줄이다. 파드 보안은 `Pod Security Admission` 이라는 내장 어드미션 컨트롤러가 네임스페이스 레이블만으로 처리한다.

```bash
kubectl label namespace payments \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/enforce-version=v1.31 \
  pod-security.kubernetes.io/warn=restricted
```

이것으로 충분한 요구를 굳이 Kyverno 정책으로 다시 작성하는 것은 낭비이자 불일치 원인이다.

## 6. 테스트와 롤아웃

정책은 코드이므로 테스트한다.

```bash
# 내장 정책: 실제 클러스터에서 Warn 모드로 먼저 관찰
kubectl apply --dry-run=server -f deployment.yaml

# Kyverno: CLI 로 CI 에서 검증
kyverno apply policies/ --resource tests/fixtures/ --detailed-results
kyverno test tests/
```

롤아웃 순서는 다음을 권한다.

1. 정책을 `Audit` 전용으로 배포하고 1~2주간 위반 건수를 수집한다.
2. 위반 상위 팀에 개별 공유하고 유예 기간을 명시한다.
3. `Warn` 으로 올려 `kubectl` 사용자에게 직접 노출한다.
4. 네임스페이스 레이블 기반으로 준비된 팀부터 `Deny` 바인딩을 붙인다.
5. 전체 전환 후, 웹훅 기반 정책 중 CEL 로 대체 가능한 것을 하나씩 내장 정책으로 이관해 웹훅 표면적을 줄인다.

마지막 항목이 장기적으로 가장 중요하다. 어드미션 웹훅은 API 서버의 가용성에 직접 묶인 의존성이다. 줄일 수 있는 것은 줄이고, 남길 것만 남긴 뒤 그것을 제대로 운영하는 편이 전부를 웹훅으로 처리하는 것보다 안전하다.

## 참고

- Kubernetes 공식 문서 — Validating Admission Policy, Admission Controllers Reference
- Kubernetes 공식 문서 — Dynamic Admission Control (Webhook 설정과 failurePolicy)
- Kubernetes 공식 문서 — Common Expression Language in Kubernetes
- Kubernetes 공식 문서 — Pod Security Admission
- Kyverno 공식 문서 — Writing Policies, Background Scans, Verify Images
- Kubernetes Enhancement Proposal — KEP-3488 CEL for Admission Control
