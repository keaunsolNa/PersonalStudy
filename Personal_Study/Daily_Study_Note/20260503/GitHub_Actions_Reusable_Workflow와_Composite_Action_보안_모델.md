Notion 원본: https://www.notion.so/3555a06fd6d3819db613c4702157968d

# GitHub Actions Reusable Workflow와 Composite Action — 권한, Secret, Supply Chain 보안

> 2026-05-03 신규 주제 · 확장 대상: GitHub Actions로 Spring Boot CI/CD 파이프라인 구축

## 학습 목표

- Reusable Workflow 와 Composite Action 의 차이를 호출 단위, 권한, secret 전달 방식으로 비교한다
- `permissions` 키, `GITHUB_TOKEN` 의 default scope 변화, OIDC 기반 단기 자격 증명 흐름을 정리한다
- Pinning, third-party action 위험, `pull_request_target` 의 위험성, 그리고 안전한 mitigation 을 케이스별로 제시한다
- 조직 단위 보안 정책 (`Allowed actions`, `Required workflows`, `Default permissions`) 을 어떻게 잠그는지 결정한다

## 1. Reusable Workflow 와 Composite Action 의 본질적 차이

겉보기에는 둘 다 "재사용 가능한 워크플로 조각" 처럼 보이지만 실행 모델이 다르다.

| 측면 | Reusable Workflow | Composite Action |
|---|---|---|
| 호출 키워드 | `uses: org/repo/.github/workflows/x.yml@ref` (job 레벨) | `uses: org/repo/.github/actions/x@ref` (step 레벨) |
| 실행 단위 | **별도 job** 으로 실행 | 호출 step 의 일부로 실행 |
| Runner | 자체 runner 새로 할당 | 호출 step 의 runner 재사용 |
| Secret | `secrets:` 또는 `secrets: inherit` 명시 필요 | 호출 워크플로의 env 자동 상속 (제한 없음) |
| Output | `outputs.<job_id>.<output>` | `step.outputs.<output>` |
| 중첩 | 최대 4단계 | composite 안에서 다른 composite 사용 가능 |
| 매트릭스 호출 | 가능 (`strategy.matrix`) | step 수준 — 매트릭스 별도 |
| CodeQL/security 분석 대상 | 워크플로로 분석 | action 메타데이터로 분석 |

**보안 관점에서 가장 중요한 차이**: Composite Action 은 호출 워크플로의 secret/env 에 자유롭게 접근하지만, Reusable Workflow 는 명시 전달된 secret 만 본다. 따라서 "신뢰 경계" 를 두고 싶을 때는 Reusable Workflow 가 안전하다. "단순 step 모음" 을 묶을 때는 Composite Action 이 가볍다.

## 2. permissions 와 GITHUB_TOKEN 의 default scope

`GITHUB_TOKEN` 은 워크플로 시작 시 자동 발급되는 단기 토큰이다. 2023 년부터 GitHub 는 organization/repository 의 *default permissions* 를 단계적으로 *restricted* 로 전환했다. 이전에는 `contents: write` + 다수의 권한이 기본이었으나 현재는 대부분 read-only 다.

명시적 권한 선언이 권장되는 형태:

```yaml
# 워크플로 전체 default
permissions:
  contents: read

jobs:
  release:
    permissions:
      contents: write       # tag, release 작성 필요
      id-token: write       # OIDC trust 필요 시
      packages: write       # GHCR push 시
    steps:
      - uses: actions/checkout@v4
      ...
```

권한은 *최소 권한 원칙* 으로 step/job 단위로 명시한다. 워크플로 전체 `permissions: read-all` 후 release job 에만 write 를 부여하는 형태가 표준이다.

## 3. OIDC 기반 단기 자격 증명

가장 큰 보안 개선은 OIDC (OpenID Connect) 다. AWS / GCP / Azure / HashiCorp Vault 와의 트러스트 관계를 통해 워크플로 실행마다 *단기 토큰* 을 발급받는다 — 정적 access key 를 secret 으로 두지 않아도 된다.

AWS IAM 의 OIDC trust policy 예시:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
      },
      "StringLike": {
        "token.actions.githubusercontent.com:sub": "repo:my-org/my-repo:ref:refs/heads/main"
      }
    }
  }]
}
```

워크플로 측:

```yaml
permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gh-deploy
          aws-region: ap-northeast-2
      - run: aws s3 sync ./out s3://my-bucket
```

`sub` 클레임 패턴을 정확히 적어 *해당 repo, 해당 브랜치, 해당 워크플로* 만 assume role 하도록 잠근다. 와일드카드 (`repo:my-org/*`) 는 권한 escape 의 흔한 원인이므로 사용하지 않는다. 환경별로 분리된 IAM Role 을 두고 environment 보호 규칙과 결합하면 production 배포에 manual approver 도 강제 가능하다.

## 4. Action 핀(pin)닝 정책

Marketplace 의 third-party action 은 본질적으로 임의 코드 실행이다. 다음 세 단계 중 어느 단계에 핀(pin)을 걸지가 보안 수준을 결정한다.

| 핀 형태 | 예시 | 안전성 |
|---|---|---|
| 메이저 태그 | `actions/checkout@v4` | 낮음 — v4 내 하위 태그가 임의로 이동 |
| 마이너/패치 태그 | `actions/checkout@v4.1.7` | 중간 — 태그 교체 가능 |
| 풀 SHA | `actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11` | 높음 — 변경 불가 |

조직 정책으로 *모든 third-party action 은 SHA 핀 필수* 가 권장된다. Dependabot 가 자동으로 SHA 업데이트 PR 을 만들어 주므로 유지보수 부담은 크지 않다.

`actions/checkout@v4` 같은 GitHub-owned 공식 action 만 태그 핀을 허용하고, 외부는 SHA 만 허용하는 정책 (`.github/workflows/policy.yml`) 도 가능하다. CODEOWNERS 로 `.github/` 변경에 보안팀 리뷰를 강제한다.

## 5. pull_request_target 의 위험과 mitigation

`pull_request_target` 트리거는 *base branch 의 워크플로* 를 *fork 에서 보낸 PR 의 컨텍스트* 로 실행한다. 즉 fork 가 보낸 코드는 default 로 checkout 되지 않지만 secret 과 write 권한은 사용 가능하다. 라벨링/triage 자동화에는 유용하나, 잘못 쓰면 fork 가 secret 을 탈취할 수 있다.

위험한 패턴:

```yaml
on: pull_request_target

jobs:
  test:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}    # ⚠️ fork 코드 checkout
      - run: npm test    # fork 의 package.json scripts 로 secret 탈취 가능
```

PR 작성자가 `package.json` 의 `pretest` 스크립트에 `curl ... ${{ secrets.NPM_TOKEN }}` 같은 코드를 심고 base branch 가 자동으로 실행해 secret 을 외부로 송출한다. 실제로 2021 ~ 2023 년 사이 다수의 OSS 프로젝트에서 같은 형태의 사고가 보고됐다.

안전한 형태:

```yaml
on: pull_request_target
permissions:
  pull-requests: write    # 라벨/코멘트 작성에만
jobs:
  triage:
    steps:
      - uses: actions/labeler@v5    # base branch 코드만 사용, fork 코드 미실행
```

빌드/테스트가 필요하면 `pull_request` 트리거 (secret 미접근) 또는 `workflow_run` 으로 분리한다.

## 6. Reusable Workflow 의 secret 전달 패턴

Reusable Workflow 는 secret 이 자동 전파되지 않으므로 명시적으로 넘겨야 한다.

```yaml
# .github/workflows/deploy.yml (calling)
jobs:
  deploy:
    uses: my-org/.github/.github/workflows/deploy-template.yml@v2
    secrets:
      AWS_ROLE_ARN: ${{ secrets.AWS_ROLE_ARN }}
      SLACK_WEBHOOK: ${{ secrets.SLACK_WEBHOOK }}
```

```yaml
# my-org/.github/.github/workflows/deploy-template.yml (called)
on:
  workflow_call:
    secrets:
      AWS_ROLE_ARN: { required: true }
      SLACK_WEBHOOK: { required: false }
```

`secrets: inherit` 는 호출 워크플로의 모든 secret 을 자동 상속한다. 신뢰 경계가 명확한 *동일 조직* 내부에서만 쓰고, 외부 워크플로 호출에는 절대 사용하지 않는다.

## 7. Composite Action 의 secret 사용

Composite Action 은 별도 secret 정의가 없고 호출 워크플로의 env 를 자동 본다. 따라서 액션을 작성할 때 secret 이름을 *문서화* 해서 호출자 측에 명시 의무를 알린다.

```yaml
# .github/actions/notify-slack/action.yml
name: Notify Slack
description: Send notification to Slack
inputs:
  message:
    description: Message text
    required: true
runs:
  using: composite
  steps:
    - name: Send
      shell: bash
      env:
        WEBHOOK: ${{ env.SLACK_WEBHOOK }}        # 호출자 워크플로 env 에서 주입
      run: |
        if [ -z "$WEBHOOK" ]; then
          echo "::error::SLACK_WEBHOOK env not set"
          exit 1
        fi
        curl -X POST -H 'Content-Type: application/json' \
             -d "{\"text\":\"${{ inputs.message }}\"}" "$WEBHOOK"
```

호출자가 secret 을 env 에 명시적으로 주입하도록 강제하는 패턴이다. Composite Action 은 secret 이 암시적이라는 점을 항상 의식한다.

## 8. 조직 단위 정책

Enterprise/Organization Settings 의 Actions 섹션에서 잠글 수 있는 항목:

| 정책 | 효과 |
|---|---|
| Allowed actions: Local only | 외부 marketplace action 사용 차단 |
| Allowed actions: Specific actions list | 화이트리스트의 publisher 만 허용 |
| Default permissions: Restricted | `GITHUB_TOKEN` 기본 read-only |
| Allow forks to use workflows | Fork PR 의 워크플로 자동 실행 차단 |
| Required workflows | 모든 repo 에 강제 적용되는 워크플로 (보안 스캔 등) |

`Required workflows` 는 GitHub Enterprise Cloud 한정으로, 보안 스캔/라이선스 검사 같은 워크플로를 모든 repo 에 강제할 때 유용하다. 우회가 불가능하다는 보장이 있어 컴플라이언스 요구사항을 충족한다.

## 9. SBOM 과 dependency review

워크플로 자체의 supply-chain 보안을 위해 Dependency Review action 이 권장된다.

```yaml
- uses: actions/dependency-review-action@v4
  with:
    fail-on-severity: high
    deny-licenses: AGPL-3.0, GPL-3.0
```

PR 단위로 새로 추가되는 의존성의 CVE 와 라이선스를 검사하고 위반 시 PR 을 차단한다. `pull_request` 트리거에서만 사용 가능하다 (`pull_request_target` 에서는 base branch 의 manifest 만 보므로 의미 없음).

GHCR 에 publish 하는 컨테이너 이미지에는 `actions/upload-artifact` 와 `actions/attest-build-provenance@v1` 으로 SLSA Level 3 provenance attestation 을 자동 첨부할 수 있다. 사용자가 cosign 으로 서명을 검증하면 빌드 이력의 무결성을 확인 가능하다.

## 10. 디버깅과 감사

Actions 로그는 30~90일 보존이 default 다. 보안 감사를 위해서는 다음을 권장한다.

- `actions: read` 권한으로 다른 워크플로의 로그를 외부 SIEM 으로 export 하는 스케줄 워크플로
- `secrets: inherit` 사용 워크플로 일괄 검색: `gh workflow list` + grep
- `pull_request_target` 사용 워크플로 일괄 검색
- third-party action 의 SHA 가 아닌 태그 핀 검색

워크플로 변경 자체에 대한 감사는 organization audit log (Enterprise 한정) 에서 `workflows.update` 이벤트로 추적한다.

## 11. 결론

GitHub Actions 의 보안은 *기본값을 잠그는 것* 으로 시작한다. Restricted default permissions, OIDC 단기 자격, third-party action 의 SHA 핀, `pull_request_target` 회피의 4가지가 가장 큰 위험을 가린다. Reusable Workflow 와 Composite Action 의 선택 기준은 "secret 격리가 필요한가" 다 — 격리 필요하면 Reusable, 단순 묶음이면 Composite. 조직 단위 정책으로 화이트리스트와 Required workflows 를 강제하면 개별 repo 의 실수를 cluster 차원에서 차단할 수 있다.

## 참고

- GitHub Docs — Reusing Workflows / Creating Composite Actions
- GitHub Docs — Security hardening for GitHub Actions
- GitHub Docs — About security hardening with OpenID Connect
- GitHub Security Lab, "Keeping your GitHub Actions and workflows secure" 시리즈 (Part 1~3)
- StepSecurity, "10 Real-World Stories of How We've Spotted Insecure Workflows" 보고서
- SLSA Framework — Build Level 3 specification
