Notion 원본: https://app.notion.com/p/3e75a06fd6d3811aa67fc2fe5e0ba1ea

# GitHub Actions 재사용 워크플로우와 OIDC 기반 AWS 자격증명 페더레이션 설계

> 2026-09-26 신규 주제 · 확장 대상: GitHub Actions · AWS 배포 경험

## 학습 목표

- `workflow_call`을 이용한 재사용 워크플로우와 컴포지트 액션(composite action)의 구조적 차이를 구분한다
- 매트릭스 전략과 재사용 워크플로우를 결합해 다중 환경 배포 파이프라인을 설계한다
- 장기 액세스 키 대신 OIDC 토큰 기반으로 AWS IAM 역할을 임시로 위임받는 흐름을 신뢰 정책 수준에서 재현한다
- OIDC 자격증명 페더레이션이 기존 액세스 키 방식보다 안전한 근거를 토큰 수명과 시크릿 노출 범위 관점에서 설명한다

## 1. 컴포지트 액션과 재사용 워크플로우의 경계

GitHub Actions에서 반복되는 로직을 추출하는 방법은 크게 두 가지다. 컴포지트 액션(`action.yml`)은 여러 스텝을 하나의 액션으로 묶어 다른 워크플로우의 한 스텝처럼 호출되는 반면, 재사용 워크플로우(`workflow_call`)는 잡(job) 단위, 심지어 여러 잡으로 구성된 워크플로우 전체를 하나의 잡처럼 호출할 수 있다.

```yaml
# .github/actions/setup-node-cache/action.yml (컴포지트 액션)
runs:
  using: "composite"
  steps:
    - uses: actions/setup-node@v4
      with:
        node-version: "20"
    - run: npm ci
      shell: bash
```

```yaml
# .github/workflows/reusable-deploy.yml (재사용 워크플로우)
on:
  workflow_call:
    inputs:
      environment:
        required: true
        type: string
    secrets:
      AWS_ROLE_ARN:
        required: true
jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}
    steps:
      - uses: actions/checkout@v4
      - run: echo "deploying to ${{ inputs.environment }}"
```

컴포지트 액션은 `runs-on`, `permissions`, `secrets` 선언을 가질 수 없고 호출하는 잡의 컨텍스트를 그대로 물려받는 반면, 재사용 워크플로우는 독립된 잡으로 실행되므로 자체적인 `permissions`와 `secrets` 인터페이스를 선언할 수 있다. 이 차이 때문에 여러 잡에 걸친 배포 파이프라인 전체(빌드 → 테스트 → 배포)를 표준화하려면 재사용 워크플로우가 적합하고, 단순히 몇 개 스텝을 여러 잡에서 공유하려면 컴포지트 액션이 더 가볍다.

## 2. 매트릭스와 재사용 워크플로우의 결합

여러 환경(dev/staging/prod)에 동일한 배포 로직을 다른 파라미터로 반복 적용할 때는 매트릭스 전략과 재사용 워크플로우 호출을 결합한다.

```yaml
jobs:
  deploy:
    strategy:
      matrix:
        environment: [dev, staging, prod]
      max-parallel: 1  # prod까지 동시에 배포되지 않도록 순차 실행 강제
    uses: ./.github/workflows/reusable-deploy.yml
    with:
      environment: ${{ matrix.environment }}
    secrets:
      AWS_ROLE_ARN: ${{ secrets[format('AWS_ROLE_ARN_{0}', matrix.environment)]  }}
```

`max-parallel: 1`은 매트릭스 잡이 병렬로 실행되는 기본 동작을 억제해, dev 배포가 실패했는데 prod 배포가 이미 시작되어버리는 상황을 방지한다. 다만 매트릭스와 재사용 워크플로우를 결합하면 각 조합마다 워크플로우 전체가 별도로 실행되므로, GitHub Actions의 동시 실행 잡 수 제한(플랜에 따라 다름)에 영향을 줄 수 있어 대규모 매트릭스에서는 순차성과 실행 시간 사이의 트레이드오프를 함께 고려해야 한다.

## 3. 왜 장기 액세스 키가 문제인가

전통적인 CI/CD에서 AWS 배포는 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`를 GitHub Secrets에 등록해 사용해왔다. 이 방식의 근본적인 문제는 이 키가 만료 없이 영구적으로 유효하다는 점이다. 저장소 시크릿이 유출되면(로그 마스킹 실패, 서드파티 액션의 시크릿 탈취, 포크된 PR의 워크플로우 악용 등) 공격자는 키가 수동으로 폐기될 때까지 무기한 AWS 리소스에 접근할 수 있다. 또한 여러 저장소나 여러 환경이 같은 키를 재사용하는 경우가 많아, 하나의 유출이 전체 조직의 AWS 계정 전체를 위협하는 규모로 번지기도 한다.

## 4. OIDC 페더레이션의 동작 원리

GitHub Actions는 각 워크플로우 실행마다 GitHub의 OIDC 프로바이더가 서명한 단기 JWT를 발급할 수 있다. 이 토큰은 저장소, 브랜치, 워크플로우 이름 등의 클레임을 담고 있으며, AWS는 이 토큰을 신뢰하도록 미리 설정된 IAM 역할에 대해 `AssumeRoleWithWebIdentity`를 수행해 자체적으로 시크릿을 저장하지 않고도 임시 자격증명을 발급한다.

```yaml
permissions:
  id-token: write   # OIDC 토큰 요청에 필요한 권한
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/github-actions-deploy
          aws-region: ap-northeast-2
      - run: aws s3 sync ./dist s3://my-bucket
```

`permissions.id-token: write`가 없으면 `configure-aws-credentials`는 OIDC 토큰을 요청할 수 없어 즉시 실패한다. 이 액션은 내부적으로 GitHub이 제공하는 `ACTIONS_ID_TOKEN_REQUEST_URL`과 `ACTIONS_ID_TOKEN_REQUEST_TOKEN` 환경 변수를 이용해 JWT를 발급받고, 이를 `sts:AssumeRoleWithWebIdentity` 호출의 `WebIdentityToken` 파라미터로 전달해 AWS로부터 15분~1시간짜리(역할 설정에 따름) 임시 자격증명을 받는다.

## 5. AWS 측 신뢰 정책의 세밀한 제한

IAM 역할의 신뢰 정책(trust policy)에서 `sub` 클레임 조건을 어떻게 설정하느냐가 이 방식의 보안 강도를 결정한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:my-org/my-repo:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

`sub` 클레임을 `repo:my-org/my-repo:ref:refs/heads/main`처럼 특정 저장소의 특정 브랜치로 제한하면, 같은 조직의 다른 저장소나 같은 저장소의 다른 브랜치(예: 임의의 PR 브랜치)에서 실행된 워크플로우는 이 역할을 절대 위임받을 수 없다. 만약 이 조건을 `repo:my-org/*:*`처럼 느슨하게 두면, 조직 내 어떤 저장소에서 실행된 워크플로우든 이 강력한 배포 역할을 가져갈 수 있게 되어 OIDC 도입의 보안 이점이 크게 희석된다. 실전에서 자주 발생하는 실수는 `pull_request` 이벤트로 트리거되는 워크플로우에도 배포 권한이 있는 역할을 신뢰 정책에서 허용해버리는 것으로, 이 경우 외부 기여자가 올린 포크 PR이 시크릿에 접근하지 못하더라도(포크 PR은 기본적으로 시크릿이 마스킹됨) `pull_request_target` 이벤트를 악용해 배포 역할을 탈취하려는 공급망 공격 벡터가 열릴 수 있다.

## 6. 환경 보호 규칙과의 결합

GitHub Environments의 보호 규칙(필수 리뷰어, 배포 브랜치 제한)을 OIDC와 결합하면 이중 방어선을 구성할 수 있다.

<table header-row="true"><tr><td>계층</td><td>통제 지점</td><td>우회 난이도</td></tr><tr><td>GitHub Environment</td><td>필수 리뷰어 승인, 허용된 브랜치만 배포 가능</td><td>저장소 관리자 권한 필요</td></tr><tr><td>OIDC sub 클레임</td><td>토큰 발급 자체가 특정 저장소/브랜치로 제한</td><td>AWS IAM 신뢰 정책 변경 필요</td></tr><tr><td>IAM 정책 최소 권한</td><td>역할에 부여된 액션이 배포 작업으로 제한</td><td>IAM 정책 변경 필요</td></tr></table>

```yaml
jobs:
  deploy-prod:
    environment: production  # 필수 리뷰어 승인 게이트
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/github-actions-prod-deploy
          aws-region: ap-northeast-2
```

세 계층이 각각 독립적으로 실패하지 않는 한 우회할 수 없는 구조이므로, 하나의 계층에서 설정 실수가 있어도 나머지 계층이 방어선 역할을 한다. 이는 Heroku의 API 키 기반 배포처럼 단일 시크릿이 곧 전체 권한과 동일시되는 구조와 대비되는 심층 방어(defense in depth) 설계다.

## 7. 마이그레이션 실전 체크리스트

기존 액세스 키 기반 파이프라인을 OIDC로 전환할 때 실무에서 확인하는 항목은 다음과 같다.

1. IAM OIDC 아이덴티티 프로바이더(`token.actions.githubusercontent.com`)가 계정에 등록되어 있는지, 그리고 프로바이더의 지문(thumbprint)이 최신인지 확인한다.
2. 신뢰 정책의 `sub` 조건이 저장소/브랜치/환경 단위로 최소화되어 있는지 검토한다.
3. 전환 직후 일정 기간은 액세스 키와 OIDC 역할을 병행 운영하며 CloudTrail에서 `AssumeRoleWithWebIdentity` 호출이 예상한 저장소에서만 발생하는지 모니터링한다.
4. 검증이 끝나면 기존 액세스 키를 IAM에서 완전히 폐기하고, GitHub Secrets에서도 제거해 시크릿 노출 표면을 없앤다.

이 절차를 거치면 배포 파이프라인이 더 이상 만료되지 않는 시크릿을 저장소 어딘가에 영구히 보관하지 않게 되어, 유출 사고가 나더라도 피해 범위가 토큰 수명(최대 1시간) 이내로 제한된다는 것이 OIDC 전환의 핵심적인 이점이다.

## 8. 흔한 실패 패턴과 진단

OIDC 전환 과정에서 반복적으로 마주치는 오류들은 대부분 신뢰 정책의 조건 불일치에서 비롯된다.

<table header-row="true"><tr><td>오류 메시지</td><td>원인</td><td>해결</td></tr><tr><td>Not authorized to perform sts:AssumeRoleWithWebIdentity</td><td>sub 클레임 조건과 실제 실행 컨텍스트(브랜치/환경) 불일치</td><td>신뢰 정책의 StringLike 조건을 실제 실행 경로에 맞게 수정</td></tr><tr><td>Could not load credentials from any providers</td><td>permissions.id-token: write 누락</td><td>워크플로우 또는 잡 레벨에 permissions 블록 추가</td></tr><tr><td>OIDC token has expired</td><td>서드파티 액션이 토큰을 오래 캐싱한 뒤 재사용</td><td>매 스텝마다 configure-aws-credentials 재호출</td></tr><tr><td>InvalidIdentityToken: audience 불일치</td><td>aud 클레임이 sts.amazonaws.com이 아님</td><td>아이덴티티 프로바이더 등록 시 audience 값 재확인</td></tr></table>

`sub` 클레임은 이벤트 트리거 종류에 따라 형식이 달라진다는 점도 실전에서 자주 놓치는 부분이다. `pull_request` 이벤트로 실행된 워크플로우의 `sub`는 `repo:org/repo:pull_request`처럼 브랜치 대신 이벤트 타입을 담고, `environment` 보호 규칙이 적용된 잡은 `repo:org/repo:environment:production` 형식을 갖는다. 이 형식을 신뢰 정책 조건 문자열에 정확히 반영하지 않으면, 워크플로우 자체는 정상적으로 실행되는데 AWS 자격증명 발급 단계에서만 매번 실패하는 상황이 발생해 원인 파악에 시간이 걸린다.

## 9. 동시성 제어와 배포 경합 방지

재사용 워크플로우로 여러 환경 배포를 자동화하면, 같은 환경에 대한 배포가 중첩 실행되는 경합 상황을 별도로 방지해야 한다. GitHub Actions는 `concurrency` 키로 동일한 그룹의 워크플로우 실행이 중복되지 않도록 제어한다.

```yaml
concurrency:
  group: deploy-${{ inputs.environment }}
  cancel-in-progress: false  # 진행 중인 배포는 취소하지 않고 대기열에 쌓음
```

`cancel-in-progress: false`는 새 배포 요청이 들어와도 이미 진행 중인 배포를 강제 종료하지 않고 대기시킨다는 의미로, 배포 도중 강제 취소되면 인프라가 절반만 갱신된 상태로 남을 위험이 있는 프로덕션 배포에 적합한 설정이다. 반대로 CI 빌드나 테스트 워크플로우처럼 최신 커밋만 검증하면 되는 경우에는 `cancel-in-progress: true`로 오래된 실행을 취소해 러너 자원을 절약하는 것이 일반적인 선택이다. OIDC 기반 배포와 동시성 제어를 함께 설계하면, 짧은 수명의 자격증명이 여러 동시 배포 프로세스에서 충돌 없이 순차적으로만 발급·사용되도록 보장할 수 있다.

## 참고

- GitHub Docs, "Reusing workflows" 및 "Security hardening with OpenID Connect"
- AWS 공식 문서, "Creating OpenID Connect (OIDC) identity providers"
- aws-actions/configure-aws-credentials GitHub 저장소 README
- GitHub Docs, "Using environments for deployment"
