Notion 원본: https://app.notion.com/p/3a85a06fd6d381988a86cd22d465427a

# Kubernetes 어드미션 웹훅과 동적 어드미션 제어 및 정책 검증

> 2026-07-25 신규 주제 · 확장 대상: Kubernetes(API 서버·어드미션 파이프라인)

## 학습 목표

- API 요청이 인증·인가 이후 어드미션 컨트롤러 체인을 거쳐 etcd에 저장되는 순서를 파악한다.
- Mutating과 Validating 웹훅의 실행 순서·재실행 규칙과 멱등성 요구사항을 구분한다.
- `failurePolicy`, `timeoutSeconds`, `namespaceSelector`가 클러스터 가용성에 미치는 영향을 정리한다.
- CEL 기반 ValidatingAdmissionPolicy가 웹훅을 대체하는 조건과 트레이드오프를 설명한다.

## 1. API 요청 처리 파이프라인에서 어드미션의 위치

kube-apiserver가 리소스 생성/수정 요청을 받으면 여러 단계를 순서대로 통과시킨다. 인증(누구인가) → 인가(권한이 있는가) → **어드미션 제어**(허용·변형해도 되는가) → 스키마 검증 → etcd 저장 순이다. 어드미션은 인가를 통과한 요청을 최종 저장 직전에 가로채 거부하거나 내용을 바꾸는 관문이다.

어드미션 컨트롤러는 두 종류다. 컴파일된 in-tree 컨트롤러(예: `ResourceQuota`, `LimitRanger`)와, 클러스터 외부의 HTTP 서비스를 호출하는 **동적 어드미션 웹훅**이다. 후자가 이 노트의 주제로, 사용자가 임의 정책을 코드로 구현해 API 서버에 끼워 넣는 확장 지점이다.

어드미션 단계는 다시 두 하위 단계로 나뉜다. 먼저 **Mutating** 단계에서 요청 객체를 변형하고, 그다음 **Validating** 단계에서 최종 객체를 검증한다. 순서가 고정된 이유는 명확하다 — 변형이 모두 끝난 뒤에 검증해야 "실제로 저장될 객체"를 검사할 수 있기 때문이다.

## 2. Mutating 웹훅 — 객체 변형

`MutatingWebhookConfiguration`은 매칭되는 요청 객체를 JSON Patch로 수정한다. 대표 용도는 사이드카 주입(Istio의 Envoy 프록시), 기본 레이블·어노테이션 부여, 리소스 기본값 설정이다.

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: MutatingWebhookConfiguration
metadata:
  name: sidecar-injector
webhooks:
  - name: inject.example.com
    rules:
      - apiGroups: [""]
        apiVersions: ["v1"]
        operations: ["CREATE"]
        resources: ["pods"]
    clientConfig:
      service:
        name: injector-svc
        namespace: sidecar-system
        path: /mutate
      caBundle: <base64-CA>
    admissionReviewVersions: ["v1"]
    sideEffects: None
    reinvocationPolicy: IfNeeded
    failurePolicy: Ignore
    timeoutSeconds: 5
```

웹훅 서비스는 `AdmissionReview` 요청을 받아 `patch`(base64 인코딩된 JSON Patch)와 `patchType: JSONPatch`를 담은 응답을 돌려준다. 중요한 제약은 **멱등성**이다. Mutating 웹훅은 다른 웹훅이 객체를 바꾸면 재실행될 수 있으므로(`reinvocationPolicy: IfNeeded`), 같은 입력에 두 번 적용돼도 결과가 같아야 한다. 사이드카를 이미 주입했는데 재실행 때 또 주입하면 중복 컨테이너가 생긴다 — 그래서 주입 전 "이미 주입됨" 어노테이션을 확인하는 가드가 필수다.

## 3. Validating 웹훅 — 정책 강제

`ValidatingWebhookConfiguration`은 객체를 변형하지 않고 허용/거부만 판단한다. Mutating 이후 실행되므로 모든 변형이 반영된 최종 객체를 본다. 용도는 조직 정책 강제다 — "모든 Pod에 리소스 limit 필수", "latest 태그 이미지 금지", "특정 레지스트리만 허용" 등.

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingWebhookConfiguration
metadata:
  name: image-policy
webhooks:
  - name: images.example.com
    rules:
      - operations: ["CREATE", "UPDATE"]
        apiGroups: [""]
        apiVersions: ["v1"]
        resources: ["pods"]
    clientConfig:
      service: { name: policy-svc, namespace: policy-system, path: /validate }
      caBundle: <base64-CA>
    admissionReviewVersions: ["v1"]
    sideEffects: None
    failurePolicy: Fail
    timeoutSeconds: 3
```

웹훅은 `allowed: true/false`와 거부 시 `status.message`를 응답한다. 같은 단계의 여러 Validating 웹훅은 병렬 호출되며, 하나라도 거부하면 요청 전체가 거부된다. Validating 웹훅은 변형하지 않으므로 재실행·멱등성 부담이 없어 Mutating보다 추론하기 쉽다. 가능하면 정책은 Validating으로, 변형은 Mutating으로 분리하는 것이 설계 원칙이다.

## 4. failurePolicy — 가용성과 안전성의 딜레마

웹훅 서비스가 다운되거나 타임아웃되면 어떻게 할 것인가. `failurePolicy`가 이를 결정한다. `Fail`은 웹훅 응답 실패 시 요청을 **거부**한다(안전 우선). `Ignore`는 실패를 무시하고 **허용**한다(가용성 우선).

이 선택은 클러스터 운영의 핵심 트레이드오프다. `failurePolicy: Fail`로 설정한 Validating 웹훅 서비스가 죽으면, 매칭되는 모든 리소스 생성이 막힌다. 만약 그 웹훅이 `pods`를 매칭하는데 웹훅 서비스 자신도 Pod로 돌고 있다면, 서비스가 재시작해야 하는 순간 새 Pod를 못 띄워 **자기 잠금(self-lockout)** 데드락에 빠진다. 실제로 이 시나리오는 프로덕션 장애의 흔한 원인이다.

방어책은 여러 겹이다. 첫째, `namespaceSelector`로 웹훅 자신이 속한 시스템 네임스페이스와 `kube-system`을 웹훅 매칭에서 제외한다. 둘째, `objectSelector`로 대상을 최소화한다. 셋째, `timeoutSeconds`를 짧게(예: 3초 이하) 잡아 느린 웹훅이 API 서버 전체를 지연시키지 않게 한다. 넷째, 웹훅 서비스를 다중 레플리카·PodDisruptionBudget으로 고가용 구성한다. 정책성 웹훅은 `Fail`이 맞지만, 위 격리 장치가 갖춰졌을 때만 안전하다.

## 5. CEL 기반 ValidatingAdmissionPolicy

웹훅은 강력하지만 별도 서비스를 운영·인증서 갱신·고가용 유지해야 하는 부담이 크다. Kubernetes 1.30에서 GA된 `ValidatingAdmissionPolicy`는 **CEL(Common Expression Language)** 표현식으로 검증 로직을 API 서버 안에서 직접 평가한다. 외부 서비스가 필요 없다.

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: require-resource-limits
spec:
  matchConstraints:
    resourceRules:
      - apiGroups: [""]
        apiVersions: ["v1"]
        operations: ["CREATE", "UPDATE"]
        resources: ["pods"]
  validations:
    - expression: >
        object.spec.containers.all(c,
          has(c.resources.limits) && has(c.resources.limits.memory))
      message: "모든 컨테이너에 memory limit 이 필요합니다"
```

`ValidatingAdmissionPolicy`가 정책을 정의하고, `ValidatingAdmissionPolicyBinding`이 그 정책을 특정 네임스페이스·리소스에 바인딩한다. 이 구조의 장점은 명확하다. 외부 서비스가 없으니 웹훅 다운으로 인한 가용성 위험·인증서 관리·네트워크 지연이 사라진다. API 서버 인프로세스 평가라 지연도 무시할 수준이다.

한계도 있다. CEL은 순수 표현식 언어라 외부 데이터 조회(다른 리소스 검색, DB 조회, HTTP 호출)를 할 수 없다. 객체 변형(mutating)도 불가하다 — 검증 전용이다. 복잡한 크로스 리소스 정책이나 외부 시스템 연동이 필요하면 여전히 웹훅이 필요하다. 그래서 실무 지침은 "단일 객체에 대한 검증은 CEL 정책으로, 변형·크로스 리소스·외부 연동은 웹훅으로" 나누는 것이다. 많은 조직이 기존 Validating 웹훅 중 자족적 검증들을 CEL 정책으로 이관해 운영 부담을 줄이고 있다.

## 6. 인증서·매칭 정밀도와 운영 함정

웹훅의 `clientConfig.caBundle`은 API 서버가 웹훅 서버의 TLS 인증서를 검증하는 CA다. 이 인증서가 만료되면 `failurePolicy: Fail` 웹훅은 모든 매칭 요청을 거부한다 — 인증서 만료 하나가 클러스터 배포를 마비시킬 수 있다. 그래서 cert-manager로 인증서를 자동 갱신하고 `caBundle`을 주입(예: cert-manager의 CA Injector)하는 구성이 사실상 표준이다.

매칭 정밀도도 운영 안정성에 직결된다. `rules`의 `operations`·`resources`·`scope`를 넓게 잡으면 불필요한 요청까지 웹훅을 거쳐 지연·실패 위험이 커진다. `matchPolicy`는 `Exact`와 `Equivalent` 두 값을 갖는데, `Equivalent`(권장 기본)는 같은 리소스의 다른 API 버전도 매칭해 버전 우회를 막는다. `Exact`는 지정한 정확한 버전만 매칭하므로, 사용자가 다른 버전으로 리소스를 만들면 정책을 우회할 수 있는 보안 구멍이 된다.

```yaml
webhooks:
  - name: images.example.com
    matchPolicy: Equivalent          # 버전 우회 방지
    namespaceSelector:
      matchExpressions:
        - key: kubernetes.io/metadata.name
          operator: NotIn
          values: ["kube-system", "policy-system"]   # 자기 잠금 방지
    objectSelector:
      matchLabels: { policy-check: "enabled" }        # 대상 최소화
```

## 7. 순서 보장과 관측성

Mutating과 Validating의 큰 단계 순서는 고정이지만, **같은 단계 내 여러 웹훅의 순서는 보장되지 않는다**. 여러 Mutating 웹훅이 같은 필드를 건드리면 최종 결과가 비결정적일 수 있다. 이를 다루는 안전한 방법은 두 가지다. 첫째, Mutating 웹훅 간 의존을 없애 순서 무관하게 설계한다. 둘째, Mutating 이후 재실행(`reinvocationPolicy: IfNeeded`) 라운드에서 모든 변형이 수렴하도록 멱등하게 만든다 — 재실행은 다른 웹훅이 객체를 바꾼 경우 자기 웹훅을 다시 부르므로, 최종 상태가 안정점에 도달해야 한다.

운영에서는 어드미션 실패의 관측성이 중요하다. API 서버는 웹훅 호출 지연·거부·실패를 메트릭으로 노출한다(`apiserver_admission_webhook_admission_duration_seconds`, `apiserver_admission_webhook_rejection_count` 등). 이 메트릭으로 특정 웹훅이 p99 지연을 끌어올리거나 거부율이 급증하는지 모니터링하고 알림을 건다. 웹훅 도입 후 클러스터 배포가 느려졌다면 거의 항상 어드미션 지연이 원인이므로 이 메트릭이 첫 진단 지점이다.

종합하면 동적 어드미션은 강력한 정책 확장 지점이지만, 그 강력함이 곧 단일 장애점이 될 수 있다. 그래서 실무는 CEL 정책으로 옮길 수 있는 검증은 옮겨 웹훅 표면을 줄이고, 남는 웹훅은 인증서 자동화·매칭 최소화·`Equivalent` 매칭·멱등성·자기 잠금 방지·관측성이라는 여섯 겹의 방어를 갖춘 뒤에만 `Fail` 정책으로 강제한다. 이 규율이 어드미션을 "보안을 지키는 관문"으로 유지하고 "클러스터를 마비시키는 폭탄"이 되지 않게 한다.

## 참고

- Kubernetes Documentation — Dynamic Admission Control (https://kubernetes.io/docs/reference/access-authn-authz/extensible-admission-controllers/)
- Kubernetes Documentation — Validating Admission Policy (https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)
- Kubernetes Documentation — Admission Controllers Reference (https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/)
- CEL Language Definition (https://github.com/google/cel-spec/blob/master/doc/langdef.md)
