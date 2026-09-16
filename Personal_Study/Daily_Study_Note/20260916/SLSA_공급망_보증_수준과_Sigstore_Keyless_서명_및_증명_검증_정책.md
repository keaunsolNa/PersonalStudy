Notion 원본: https://www.notion.so/3dd5a06fd6d3814fbd0bfd73666b0a32

# SLSA 공급망 보증 수준과 Sigstore Keyless 서명 및 증명 검증 정책

> 2026-09-16 신규 주제 · 확장 대상: DevOps 심화(GitHub Actions, Kubernetes 어드미션 정책, OpenTofu 상태 관리)

## 학습 목표

- SLSA 빌드 레벨 1~3 이 각각 어떤 위협을 막는지 구체적 공격 시나리오로 구분한다
- Sigstore 의 키리스 서명에서 OIDC·Fulcio·Rekor 가 맡는 역할을 설명한다
- in-toto attestation 과 DSSE 봉투 구조를 읽고 필요한 주장을 추출한다
- 클러스터 어드미션에서 증명 검증 정책을 작성하고 실패 모드를 설계한다

## 1. 공급망 공격이 노리는 지점

애플리케이션 코드에 취약점이 없어도 배포된 아티팩트가 오염될 수 있다. 실제 사례에서 반복적으로 관찰된 침투 지점은 네 군데다.

첫째, 의존성 자체. 악성 패키지를 유사한 이름으로 게시하거나(typosquatting), 인수한 정상 패키지에 백도어를 심는다. 둘째, 빌드 환경. CI 러너에 침투해 컴파일 과정에서 산출물을 바꾼다. 소스 저장소는 깨끗하므로 코드 리뷰로는 잡힐 수 없다. 셋째, 아티팩트 저장소. 레지스트리에 푸시된 이미지를 같은 태그로 덮어쓴다. 넷째, 배포 경로. 클러스터가 당기는 이미지를 중간에서 바꾼다.

SLSA(Supply-chain Levels for Software Artifacts)는 이 중 주로 두 번째와 세 번째를 다루는 프레임워크다. "이 아티팩트가 정말 그 소스에서, 그 빌드 과정을 거쳤는가"를 검증 가능하게 만드는 것이 목표다. 취약점 스캔과는 다른 축이다. SLSA 는 *무엇이 들어 있는가*가 아니라 *어디서 어떻게 왔는가*를 묻는다.

## 2. 빌드 레벨의 실질적 구분

현행 SLSA 는 트랙(Track) 개념을 쓰며, 성숙한 것은 Build Track 이다. 레벨은 L0~L3 이다.

| 레벨 | 요구사항 | 막는 공격 |
|---|---|---|
| L0 | 없음 | — |
| L1 | 프로버넌스(provenance)를 생성하고 배포 | 출처 불명 아티팩트, 수동 빌드 |
| L2 | 호스팅된 빌드 플랫폼에서 빌드, 프로버넌스에 서명 | 프로버넌스 위조, 개발자 로컬 빌드 |
| L3 | 빌드가 위변조 저항적, 실행 간 격리 보장 | 빌드 중 주입, 서명 키 탈취 |

L1 과 L2 의 차이는 "서명"이다. L1 의 프로버넌스는 누구나 만들 수 있으므로, 공격자가 오염된 아티팩트에 그럴듯한 프로버넌스를 붙이면 구분이 안 된다. L2 는 빌드 플랫폼이 서명하므로 위조하려면 플랫폼의 서명 능력이 필요하다.

L2 와 L3 의 차이는 **빌드 정의를 빌드 자체가 바꿀 수 없다는 보장**이다. GitHub Actions 를 예로 들면, 일반 워크플로 잡은 같은 러너에서 스크립트를 실행하므로 프로버넌스 생성 단계와 빌드 단계가 같은 신뢰 경계 안에 있다. 빌드 스크립트가 침해되면 프로버넌스도 조작 가능하다. 이것이 L2 다.

L3 를 달성하려면 서명 단계를 빌드와 분리해야 한다. GitHub 의 재사용 가능 워크플로(reusable workflow)는 별도 잡으로 실행되고 호출자가 그 내부를 바꿀 수 없으며, OIDC 토큰의 `job_workflow_ref` 클레임에 그 워크플로의 정확한 참조가 박힌다. 검증자는 이 클레임을 보고 "신뢰하는 빌더가 만들었는가"를 판단한다.

```yaml
jobs:
  build:
    outputs:
      digest: ${{ steps.build.outputs.digest }}
    steps:
      - id: build
        run: |
          # 이미지 빌드 및 푸시
          echo "digest=$(crane digest ...)" >> "$GITHUB_OUTPUT"

  provenance:
    needs: [build]
    permissions:
      id-token: write      # OIDC 토큰 발급
      packages: write
      contents: read
    uses: slsa-framework/slsa-github-generator/.github/workflows/generator_container_slsa3.yml@v2.0.0
    with:
      image: ghcr.io/org/app
      digest: ${{ needs.build.outputs.digest }}
```

`permissions: id-token: write` 가 핵심이다. 이 권한이 있어야 잡이 GitHub 의 OIDC 공급자로부터 토큰을 받을 수 있다. 기본값이 아니므로 명시해야 한다.

## 3. Sigstore 키리스 서명의 구조

전통적 코드 서명의 최대 난점은 개인키 관리다. 키를 HSM 에 넣으면 CI 에서 쓰기 번거롭고, CI 시크릿에 넣으면 CI 침해 시 그대로 유출된다. 키 교체도 어렵다.

Sigstore 는 "오래 사는 키를 아예 두지 않는" 방향을 택했다. 흐름은 이렇다.

1. 서명자가 임시 키쌍을 **메모리에서** 생성한다.
2. OIDC 공급자(GitHub Actions, Google, GitLab 등)에서 신원 토큰을 받는다.
3. **Fulcio**(인증 기관)에 공개키와 OIDC 토큰을 제출한다. Fulcio 는 토큰을 검증하고, 신원 정보를 X.509 확장에 박은 **10분짜리 인증서**를 발급한다.
4. 임시 개인키로 아티팩트 다이제스트에 서명한다.
5. 서명·인증서·다이제스트를 **Rekor**(투명성 로그)에 기록한다. Rekor 는 Merkle 트리 기반 append-only 로그이며, 포함 증명(inclusion proof)과 서명된 엔트리 타임스탬프(SET)를 돌려준다.
6. 임시 개인키를 폐기한다.

검증자는 나중에 "인증서가 만료됐는데 이 서명이 유효한가?"를 묻게 된다. 답은 Rekor 의 타임스탬프다. **서명 시각이 인증서 유효 기간 안이었음**을 로그가 증명하므로, 인증서 만료 후에도 서명은 유효하다. 이것이 짧은 인증서를 쓸 수 있는 이유다.

```bash
# 키리스 서명 (CI 에서는 OIDC 가 자동 감지됨)
cosign sign --yes ghcr.io/org/app@sha256:abcd...

# 검증 — 신원 조건을 반드시 명시해야 한다
cosign verify \
  --certificate-identity-regexp '^https://github\.com/org/app/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/org/app@sha256:abcd...
```

`--certificate-identity` 와 `--certificate-oidc-issuer` 는 선택이 아니다. 생략하면 "누군가 Sigstore 로 서명했다"만 확인하는 꼴이 되고, 공격자도 자기 GitHub 계정으로 같은 다이제스트에 서명할 수 있으므로 보안 가치가 0 이 된다. cosign 이 이 플래그를 필수로 요구하도록 바뀐 것도 같은 이유다.

신원 정규식을 작성할 때 주의할 점은 **앵커링**이다. `^...$` 를 붙이지 않으면 `https://github.com/evil/app/.github/workflows/release.yml@...` 같은 문자열이 부분 일치로 통과할 수 있다.

## 4. in-toto attestation 과 DSSE

서명은 "이 다이제스트를 이 신원이 승인했다"만 말한다. 더 풍부한 주장을 담으려면 증명(attestation)이 필요하다. in-toto attestation 은 다음 구조다.

```json
{
  "_type": "https://in-toto.io/Statement/v1",
  "subject": [
    {
      "name": "ghcr.io/org/app",
      "digest": { "sha256": "abcd..." }
    }
  ],
  "predicateType": "https://slsa.dev/provenance/v1",
  "predicate": {
    "buildDefinition": {
      "buildType": "https://slsa-framework.github.io/github-actions-buildtypes/workflow/v1",
      "externalParameters": {
        "workflow": {
          "ref": "refs/tags/v1.4.2",
          "repository": "https://github.com/org/app",
          "path": ".github/workflows/release.yml"
        }
      },
      "resolvedDependencies": [
        { "uri": "git+https://github.com/org/app@refs/tags/v1.4.2",
          "digest": { "gitCommit": "9f2a..." } }
      ]
    },
    "runDetails": {
      "builder": { "id": "https://github.com/slsa-framework/slsa-github-generator/.github/workflows/generator_container_slsa3.yml@refs/tags/v2.0.0" },
      "metadata": { "invocationId": "https://github.com/org/app/actions/runs/1234567890/attempts/1" }
    }
  }
}
```

`subject` 는 이 주장이 어느 아티팩트에 대한 것인지를 다이제스트로 못 박는다. `predicateType` 은 주장의 종류다. 프로버넌스 외에도 SBOM(SPDX, CycloneDX), 취약점 스캔 결과, 테스트 통과 여부 등 여러 종류가 표준화되어 있다.

이 Statement 는 **DSSE**(Dead Simple Signing Envelope)로 감싸 서명한다.

```json
{
  "payloadType": "application/vnd.in-toto+json",
  "payload": "<base64(Statement)>",
  "signatures": [ { "sig": "<base64>", "keyid": "" } ]
}
```

DSSE 의 설계 요점은 **PAE**(Pre-Authentication Encoding)다. 서명 대상은 payload 자체가 아니라 `"DSSEv1" || SP || len(payloadType) || SP || payloadType || SP || len(payload) || SP || payload` 형태로 길이를 명시해 직렬화한 바이트열이다. 이 때문에 payloadType 을 바꿔치기해 같은 서명을 다른 맥락에서 재사용하는 공격이 막힌다. JSON 정규화에 의존하지 않는 것도 장점이다.

```bash
# SBOM 을 증명으로 첨부
syft ghcr.io/org/app@sha256:abcd... -o spdx-json > sbom.json
cosign attest --yes \
  --predicate sbom.json \
  --type spdxjson \
  ghcr.io/org/app@sha256:abcd...

# 증명 검증 및 페이로드 추출
cosign verify-attestation \
  --type slsaprovenance1 \
  --certificate-identity-regexp '^https://github\.com/org/.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/org/app@sha256:abcd... \
  | jq -r '.payload' | base64 -d | jq '.predicate.buildDefinition'
```

## 5. 클러스터 어드미션에서의 정책

서명과 증명을 만들어도 검증하지 않으면 의미가 없다. Kubernetes 에서는 어드미션 웹훅 단계에서 검증한다. 대표적인 구현이 Sigstore Policy Controller 와 Kyverno 다.

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: verify-slsa-provenance
spec:
  validationFailureAction: Enforce
  webhookTimeoutSeconds: 20
  rules:
    - name: check-provenance
      match:
        any:
          - resources:
              kinds: [Pod]
              namespaces: ["prod-*"]
      verifyImages:
        - imageReferences:
            - "ghcr.io/org/*"
          mutateDigest: true
          verifyDigest: true
          required: true
          attestations:
            - type: https://slsa.dev/provenance/v1
              attestors:
                - entries:
                    - keyless:
                        subject: "https://github.com/slsa-framework/slsa-github-generator/*"
                        issuer: "https://token.actions.githubusercontent.com"
                        rekor:
                          url: https://rekor.sigstore.dev
              conditions:
                - all:
                    - key: "{{ predicate.buildDefinition.externalParameters.workflow.repository }}"
                      operator: Equals
                      value: "https://github.com/org/app"
                    - key: "{{ predicate.buildDefinition.externalParameters.workflow.ref }}"
                      operator: AnyIn
                      value: ["refs/heads/main"]
```

`mutateDigest: true` 가 중요한 설정이다. 태그 참조를 검증한 다이제스트로 치환한다. 이것이 없으면 검증 시점과 이미지 풀 시점 사이에 태그가 다른 이미지를 가리키도록 바뀌는 TOCTOU 공격이 가능하다. 검증한 것과 실행되는 것이 같음을 보장하려면 다이제스트 고정이 필수다.

`conditions` 는 증명의 *내용*을 검사한다. 서명이 유효한 것만으로는 부족하고, "우리 레포의 main 브랜치에서 나왔는가"까지 봐야 한다. 이 조건이 없으면 같은 빌더로 만든 임의의 레포 이미지가 통과한다.

## 6. 실패 모드 설계

검증 정책의 가장 큰 운영 리스크는 **가용성**이다. Rekor 나 Fulcio 조회가 실패하면 파드가 뜨지 않는다. 외부 서비스 장애가 곧 우리 클러스터 장애가 된다.

완화 수단은 세 가지다.

첫째, **번들 사용**. 서명 시 `--bundle` 로 검증에 필요한 인증서·Rekor 엔트리·포함 증명을 한 파일에 담으면, 검증 시 네트워크 조회가 필요 없다. OCI 레지스트리에 함께 저장되므로 이미지만 있으면 오프라인 검증이 가능하다.

```bash
cosign sign --yes --bundle app.bundle ghcr.io/org/app@sha256:abcd...
cosign verify --bundle app.bundle --offline \
  --certificate-identity-regexp '...' \
  --certificate-oidc-issuer '...' \
  ghcr.io/org/app@sha256:abcd...
```

둘째, **웹훅 failurePolicy 결정**. `Fail` 로 두면 웹훅 장애 시 배포가 전면 중단된다. `Ignore` 로 두면 검증이 조용히 건너뛰어진다. 실무에서는 프로덕션 네임스페이스만 `Fail`, 나머지는 `Ignore` 로 나누고, `namespaceSelector` 로 kube-system 등 부트스트랩 경로를 반드시 제외한다. 이 제외가 없으면 클러스터 재시작 시 검증 컨트롤러 자신이 뜨지 못하는 데드락이 생긴다.

셋째, **단계적 도입**. `validationFailureAction: Audit` 으로 시작해 어떤 이미지가 걸리는지 수집한다. 대개 사이드카, 인프라 차트의 서드파티 이미지가 대량으로 걸린다. 이들을 예외 목록에 넣거나 자체 재빌드로 전환한 뒤에 `Enforce` 로 올린다. 순서를 바꾸면 롤아웃이 중단된다.

## 7. 서드파티 의존성에 대한 현실

내부 이미지는 통제 가능하지만 외부 이미지는 그렇지 않다. 실질적 전략은 **재빌드 후 자체 서명**이다.

```
upstream image → 사내 레지스트리 미러 → 스캔 → 자체 키리스 서명 → 어드미션 통과
```

미러링 단계에서 다이제스트를 고정하고, 승인된 다이제스트에만 서명을 붙인다. 어드미션 정책은 "사내 서명이 있는 이미지만"으로 단일화되어 예외 규칙이 줄어든다. 업스트림이 서명을 제공한다면 미러링 파이프라인에서 그 서명을 먼저 검증하고, 통과한 것만 재서명하는 것이 이상적이다.

의존성 레벨에서는 별도의 축이 있다. npm 의 `--provenance` 플래그는 패키지 게시 시 Sigstore 프로버넌스를 함께 발행한다. PyPI 도 신뢰 게시자(Trusted Publisher) 흐름으로 OIDC 기반 게시를 지원한다. 소비자 측 검증은 아직 도구마다 성숙도가 다르지만, 최소한 **우리가 게시하는 패키지**에는 프로버넌스를 붙이는 것이 비용 대비 효과가 크다.

```bash
npm publish --provenance --access public
```

## 8. 도입 순서 제안

한 번에 L3 를 목표로 하면 대개 실패한다. 단계별로 나누면 각 단계가 독립적인 가치를 낸다.

1. **다이제스트 고정**. 모든 배포 매니페스트에서 태그 대신 다이제스트를 쓴다. 서명 없이도 재현성이 크게 올라간다.
2. **서명 시작**. CI 에서 `cosign sign` 을 붙인다. 검증은 아직 하지 않는다. 데이터를 먼저 쌓는다.
3. **감사 모드 검증**. 어드미션 정책을 Audit 으로 배포하고 위반 로그를 수집한다.
4. **프로버넌스 생성**. 신뢰 가능한 빌더로 전환하고 SLSA L3 프로버넌스를 만든다.
5. **프로덕션 강제**. 프로덕션 네임스페이스부터 Enforce 로 전환한다.
6. **내용 조건 추가**. 브랜치·레포·빌더 조건을 정책에 넣는다.

각 단계에서 롤백 경로를 확보해 두는 것이 중요하다. 특히 5단계는 정책 리소스 하나를 지우면 즉시 되돌아가도록 설계해야 한다. 보안 통제가 장애 대응을 막으면 결국 통제 자체가 꺼진다.

## 참고

- SLSA Specification v1.0 — Build Track: https://slsa.dev/spec/v1.0/levels
- Sigstore Documentation: https://docs.sigstore.dev/
- in-toto Attestation Specification: https://github.com/in-toto/attestation/blob/main/spec/v1/statement.md
- DSSE — Dead Simple Signing Envelope: https://github.com/secure-systems-lab/dsse/blob/master/protocol.md
- Kyverno — Verify Images: https://kyverno.io/docs/policy-types/cluster-policy/verify-images/
- GitHub Docs — OpenID Connect in GitHub Actions: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/about-security-hardening-with-openid-connect
