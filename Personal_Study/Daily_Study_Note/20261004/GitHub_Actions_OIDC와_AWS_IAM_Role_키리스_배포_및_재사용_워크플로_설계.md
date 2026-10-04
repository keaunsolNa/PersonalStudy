Notion 원본: https://www.notion.so/3ef5a06fd6d3812591dadacf19b5321e

# GitHub Actions OIDC와 AWS IAM Role 신뢰 정책 기반 키리스 배포 및 재사용 워크플로(workflow_call) 설계

> 2026-10-04 신규 주제 · 확장 대상: GitHub Actions CI/CD, AWS 배포

## 학습 목표

- GitHub Actions OIDC 토큰이 AWS STS를 거쳐 임시 자격 증명으로 바뀌는 흐름을 추적한다.
- IAM Role 신뢰 정책에서 `aud`와 `sub` 클레임 조건을 작성해 배포 가능한 저장소와 브랜치를 좁힌다.
- `aws-actions/configure-aws-credentials`와 `permissions: id-token: write`로 장기 액세스 키 없는 배포 워크플로를 구성한다.
- `workflow_call` 재사용 워크플로로 배포 로직을 한 곳에 모으고, 호출 측과 피호출 측의 권한 경계를 설계한다.

## 1. 장기 액세스 키 방식의 문제와 OIDC가 바꾸는 것

GitHub Actions에서 AWS로 배포하는 가장 단순한 방법은 IAM 사용자를 만들고 액세스 키를 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` 시크릿으로 저장하는 것이다. 이 방식은 동작하지만 몇 가지 구조적인 약점이 있다. 키는 만료되지 않으므로 유출되면 회수할 때까지 유효하고, 로테이션은 사람이 주기적으로 챙겨야 하며, 저장소가 늘어날수록 키가 복제되어 어느 키가 어디에서 쓰이는지 추적하기 어려워진다. 또한 키 자체에는 "어느 저장소의 어느 브랜치에서 온 요청인가"라는 문맥이 없어서, 같은 키를 가진 어떤 워크플로든 같은 권한을 행사한다.

OIDC(OpenID Connect) 연동은 이 문제를 신원 기반으로 바꾼다. GitHub가 워크플로 실행마다 서명된 JWT를 발급하고, AWS는 그 토큰을 검증한 뒤 IAM Role을 맡을 수 있게 해 주는 단기 자격 증명을 내어 준다. 저장소에는 비밀 값이 하나도 남지 않는다. 대신 "이 토큰이 정말 GitHub가 발급한 것인가", "토큰이 주장하는 저장소와 브랜치가 우리가 허용한 것인가"를 AWS가 매번 판단한다. 보안의 중심이 비밀 보관에서 신뢰 정책 설계로 이동한다는 점이 핵심이다.

이 방식의 trade-off도 분명하다. 시크릿 관리 부담은 사라지지만, 신뢰 정책을 잘못 쓰면 오히려 더 넓은 권한이 열린다. 예를 들어 `sub` 조건을 빠뜨리면 해당 OIDC 공급자를 신뢰하는 모든 GitHub 저장소가 그 Role을 맡을 수 있다. 키 유출 사고는 줄어들지만 정책 오구성 사고의 영향 범위는 커질 수 있으므로, 이 문서의 상당 부분을 신뢰 정책 설계에 할애한다.

## 2. 인증 흐름: 토큰 발급부터 임시 자격 증명까지

흐름은 다섯 단계로 나뉜다. 첫째, 워크플로 잡이 `permissions: id-token: write` 권한을 가지면 러너 환경에 OIDC 토큰 요청용 URL과 토큰이 주입된다. 둘째, `aws-actions/configure-aws-credentials` 액션이 이 엔드포인트에 `audience`를 지정해 JWT를 요청한다. 기본 audience는 `sts.amazonaws.com`이다. 셋째, 액션이 AWS STS의 `AssumeRoleWithWebIdentity`를 호출하며 JWT와 Role ARN을 전달한다. 넷째, STS는 IAM에 등록된 OIDC 공급자(`token.actions.githubusercontent.com`)로 서명을 검증하고, Role의 신뢰 정책 조건을 JWT 클레임과 대조한다. 다섯째, 통과하면 액세스 키, 시크릿 키, 세션 토큰으로 이루어진 임시 자격 증명이 반환되고 액션이 이를 환경 변수로 내보낸다. 이후 단계의 `aws s3 sync`나 `aws ecs update-service` 같은 명령은 평소처럼 동작한다.

GitHub가 발급하는 JWT에는 여러 클레임이 담긴다. 신뢰 정책에서 자주 쓰는 것은 다음과 같다.

| 클레임 | 의미 | 예시 |
|---|---|---|
| `iss` | 토큰 발급자 | `https://token.actions.githubusercontent.com` |
| `aud` | 토큰 대상 | `sts.amazonaws.com` |
| `sub` | 실행 주체를 나타내는 식별자 | `repo:ORG/REPO:ref:refs/heads/main` |
| `repository` | 저장소 전체 이름 | `ORG/REPO` |
| `ref` | 워크플로를 트리거한 Git ref | `refs/heads/main` |
| `environment` | 잡이 사용하는 GitHub Environment | `production` |
| `job_workflow_ref` | 실제 실행 중인 잡이 정의된 워크플로 파일 | `ORG/shared/.github/workflows/deploy.yml@refs/heads/main` |

`sub`의 모양은 트리거와 설정에 따라 달라진다. 브랜치 푸시는 `repo:ORG/REPO:ref:refs/heads/main`, 잡이 Environment를 쓰면 `repo:ORG/REPO:environment:production`, 풀 리퀘스트 이벤트는 `repo:ORG/REPO:pull_request` 형태다. 잡에 `environment`가 지정되면 `sub`는 ref 기반이 아니라 environment 기반 값으로 바뀐다는 점이 흔한 함정이다. 브랜치 조건으로 신뢰 정책을 써 놓고 잡에 Environment를 붙였더니 AssumeRole이 거부되는 사례가 여기서 나온다.

## 3. AWS 측 준비: OIDC 공급자와 신뢰 정책

먼저 IAM에 OIDC 공급자를 한 번 등록한다. 공급자 URL은 `https://token.actions.githubusercontent.com`, 대상(audience)은 `sts.amazonaws.com`이다. 계정당 이 URL의 공급자는 하나만 만들 수 있으므로 여러 저장소가 같은 공급자를 공유하고, 저장소별 구분은 Role의 신뢰 정책이 담당한다. AWS CLI로는 다음처럼 만든다.

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

과거에는 TLS 인증서 지문(thumbprint)을 직접 계산해 넣어야 했지만, AWS는 현재 GitHub OIDC 공급자에 대해 자체 신뢰 CA 라이브러리로 검증하는 것으로 안내하고 있다. 정확한 동작은 AWS 문서의 OIDC 공급자 생성 항목에서 확인하는 것이 안전하다. 같은 이유로 CLI 버전에 따라 thumbprint 인자 요구 여부가 다를 수 있다.

다음은 Role의 신뢰 정책이다. `main` 브랜치에서 실행된 특정 저장소의 워크플로만 허용하는 가장 기본적인 형태는 아래와 같다. `ACCOUNT_ID`, `ORG`, `REPO`는 실제 값으로 바꾼다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowGitHubActionsMainBranch",
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:ORG/REPO:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

여기서 `aud` 조건은 토큰이 AWS STS를 대상으로 발급되었음을 보장하고, `sub` 조건은 저장소와 브랜치를 고정한다. 두 조건 모두 필수라고 보는 것이 좋다. 특히 `sub` 없이 `aud`만 두면, 해당 계정의 GitHub OIDC 공급자를 통해 토큰을 받을 수 있는 임의의 GitHub 저장소가 Role을 맡을 수 있다.

## 4. 단일 워크플로에서의 키리스 배포

신뢰 정책이 준비되면 워크플로 쪽 설정은 짧다. 핵심은 잡 수준(또는 워크플로 수준)에서 `id-token: write`를 명시하는 것이다. `permissions`를 한 번이라도 명시하면 나머지 권한은 기본적으로 `none`이 되므로, 체크아웃에 필요한 `contents: read`도 함께 적어야 한다.

```yaml
name: deploy

on:
  push:
    branches: [main]

permissions:
  id-token: write   # OIDC 토큰 요청에 필요
  contents: read    # actions/checkout 에 필요

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy-main
          aws-region: ap-northeast-2
          role-session-name: gha-${{ github.run_id }}

      - name: Who am I
        run: aws sts get-caller-identity

      - name: Sync static site
        run: aws s3 sync ./dist s3://example-bucket --delete
```

`role-session-name`을 지정하면 CloudTrail에서 어느 실행이 어떤 API를 호출했는지 추적하기 쉬워진다. 세션 이름에 `run_id`를 넣는 것은 단순하지만 유용한 습관이다. 세션 지속 시간은 `role-duration-seconds` 입력으로 조절할 수 있으나 Role의 최대 세션 시간을 넘길 수 없고, 기본값에서 충분하다면 짧게 유지하는 편이 유출 시 피해를 줄인다.

## 5. sub 클레임 설계: 브랜치, Environment, 태그

신뢰 정책에서 가장 중요한 설계 결정은 어떤 `sub` 값을 허용할 것인가다. 세 가지 패턴이 있고 각각 성격이 다르다.

| 패턴 | `sub` 예시 | 장점 | 한계 |
|---|---|---|---|
| 브랜치 고정 | `repo:ORG/REPO:ref:refs/heads/main` | 단순하고 직관적이다 | 브랜치 보호 규칙이 약하면 푸시 권한만으로 배포 가능하다 |
| Environment 고정 | `repo:ORG/REPO:environment:production` | Environment의 승인자, 대기 시간, 브랜치 제한과 결합할 수 있다 | 잡에 `environment:`를 지정해야만 매칭된다 |
| 태그 패턴 | `repo:ORG/REPO:ref:refs/tags/v*` | 릴리스 태그 기반 배포에 어울린다 | 태그 생성 권한 관리가 별도로 필요하다 |

운영 환경 배포에는 Environment 기반이 상대적으로 강력하다. GitHub Environment에는 필수 리뷰어와 배포 가능 브랜치 제한 같은 보호 규칙을 걸 수 있고, `sub`가 `environment:production`을 포함하므로 이 Environment를 거치지 않은 잡은 Role을 맡지 못한다. 즉 AWS 쪽 신뢰 정책과 GitHub 쪽 보호 규칙이 이중 방어가 된다. 반면 Environment 규칙은 저장소 관리자가 변경할 수 있으므로, 그 권한 자체를 누가 가지는지도 위협 모델에 포함해야 한다.

## 6. workflow_call 재사용 워크플로로 배포 로직 모으기

저장소가 여러 개이고 배포 절차가 비슷하다면, 같은 YAML을 복사해 두는 것은 변경 비용을 키운다. `on: workflow_call`로 정의한 재사용 워크플로를 조직 공용 저장소에 두고 각 저장소가 `uses:`로 호출하면 배포 절차를 한 곳에서 고칠 수 있다. 입력은 `inputs`, 호출자가 넘기는 시크릿은 `secrets`, 결과는 `outputs`로 선언한다.

공용 저장소 `ORG/shared-workflows`의 `.github/workflows/aws-deploy.yml` 예시는 다음과 같다.

```yaml
name: aws-deploy (reusable)

on:
  workflow_call:
    inputs:
      role-arn:
        description: 배포에 사용할 IAM Role ARN
        type: string
        required: true
      aws-region:
        type: string
        default: ap-northeast-2
      environment:
        description: GitHub Environment 이름
        type: string
        required: true
      artifact-path:
        type: string
        default: dist
    outputs:
      deployed-sha:
        description: 배포된 커밋 SHA
        value: ${{ jobs.deploy.outputs.sha }}

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}
    permissions:
      id-token: write
      contents: read
    outputs:
      sha: ${{ steps.meta.outputs.sha }}
    steps:
      - uses: actions/checkout@v4
      - id: meta
        run: echo "sha=${GITHUB_SHA}" >> "$GITHUB_OUTPUT"
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ inputs.role-arn }}
          aws-region: ${{ inputs.aws-region }}
          role-session-name: gha-${{ github.run_id }}
      - name: Deploy
        run: aws s3 sync "${{ inputs.artifact-path }}" s3://example-bucket-${{ inputs.environment }} --delete
```

호출하는 쪽 저장소의 워크플로는 아래처럼 간결해진다. 호출 잡에도 `permissions`를 선언해야 한다는 점이 중요하다. 피호출 워크플로는 호출자가 가진 권한 이내에서만 권한을 가질 수 있기 때문에, 호출 측이 `id-token: write`를 주지 않으면 피호출 측의 선언만으로는 토큰을 받을 수 없다.

```yaml
name: release

on:
  push:
    branches: [main]

jobs:
  deploy-prod:
    permissions:
      id-token: write
      contents: read
    uses: ORG/shared-workflows/.github/workflows/aws-deploy.yml@v1
    with:
      role-arn: arn:aws:iam::123456789012:role/gha-deploy-prod
      environment: production
```

재사용 워크플로를 참조할 때는 `@main` 같은 움직이는 브랜치보다 태그나 커밋 SHA를 쓰는 편이 안전하다. 공용 저장소의 `main`이 바뀌면 모든 호출자의 배포 동작이 동시에 바뀌기 때문이다. 반대로 `@v1` 같은 주 버전 태그를 이동시키며 운영하면 호출자는 수정 없이 개선을 받지만, 태그 이동 권한이 곧 조직 전체 배포 경로를 바꾸는 권한이 된다. 공용 저장소의 쓰기 권한과 태그 보호를 엄격히 관리해야 하는 이유다. 또한 재사용 워크플로에는 중첩 호출 깊이 등 문서화된 제한이 있고, 사설 저장소의 워크플로를 다른 저장소가 호출하려면 공용 저장소 설정에서 Actions 접근을 허용해야 하므로 해당 문서를 확인한다.

## 7. 재사용 워크플로와 sub, job_workflow_ref의 상호작용

재사용 워크플로를 쓸 때 가장 헷갈리는 부분은 OIDC 토큰의 `sub`가 무엇을 가리키는가다. 기본 `sub`는 호출한 저장소의 문맥을 따른다. 즉 `ORG/app-a`가 공용 워크플로를 호출하면 `sub`는 여전히 `repo:ORG/app-a:...` 형태이고, 공용 워크플로가 어느 저장소에 있는지는 `sub`에 나타나지 않는다. 대신 `job_workflow_ref` 클레임에 실제 실행 중인 워크플로 파일의 경로와 ref가 담긴다. 따라서 신뢰 정책을 설계하는 방식은 두 가지다.

첫째는 기본 `sub`만 쓰는 방식이다. 각 서비스 저장소가 고유 Role을 가지고, 신뢰 정책은 `repo:ORG/app-a:environment:production` 같은 값을 허용한다. 구조가 단순하지만 "공용 워크플로를 통해서만 배포한다"는 규칙은 AWS가 강제하지 못한다. 서비스 저장소가 공용 워크플로를 쓰지 않고 자체 잡에서 같은 Role을 맡아도 통과하기 때문이다.

둘째는 `job_workflow_ref`를 조건에 포함해 공용 워크플로 경유를 강제하는 방식이다. 방법은 둘로 나뉜다. 하나는 GitHub가 제공하는 OIDC `sub` 클레임 커스터마이징 API로 저장소 또는 조직 수준에서 `sub`에 포함할 클레임(예: `job_workflow_ref`)을 지정하는 것이다. 다른 하나는 AWS IAM 신뢰 정책에서 GitHub OIDC의 추가 클레임을 조건 키로 직접 참조하는 것이다. 후자가 지원하는 클레임 목록과 키 이름은 AWS 문서의 GitHub OIDC 조건 키 항목에서 확인해 사용해야 하며, 키 이름을 추측으로 쓰면 조건이 조용히 맞지 않아 모든 요청이 거부될 수 있다. 어느 쪽이든 목표는 "신뢰할 수 있는 공용 워크플로 파일이 실행한 잡만 배포 Role을 맡는다"를 신뢰 정책에 새기는 것이다.

## 8. 운영 점검 항목과 트러블슈팅

처음 구성할 때 가장 흔한 오류는 `Not authorized to perform sts:AssumeRoleWithWebIdentity`다. 원인은 대부분 세 곳에 있다. 첫째, 워크플로에 `id-token: write`가 없어서 토큰 자체를 받지 못한 경우로, 이때는 액션이 OIDC 토큰 요청 변수가 없다는 취지의 오류를 낸다. 둘째, `sub` 문자열 불일치다. 대소문자, 조직과 저장소 이름, `ref:refs/heads/` 접두어, Environment 사용 시 `sub` 형태 변화가 원인이 된다. 셋째, `aud` 불일치로, 액션의 `audience` 입력을 바꿨는데 신뢰 정책은 `sts.amazonaws.com` 그대로인 경우다. 실제 `sub` 값을 확인하려면 디버깅용 잡에서 토큰을 디코딩해 클레임을 출력하는 방법이 있지만, 토큰 원문을 로그에 남기면 유효 기간 내 재사용 위험이 있으므로 페이로드의 클레임만 출력하고 임시로만 사용한다.

정리하면 키리스 배포는 비밀 값 관리를 없애는 대신 신뢰 관계를 코드로 설계하는 작업이다. 설계의 우선순위는 다음 순서가 합리적이다. 먼저 `aud`와 `sub`를 정확히 고정하고, 운영 배포에는 Environment 보호 규칙을 결합하며, 여러 저장소가 공유하는 배포 로직은 `workflow_call`로 모으되 호출 측 `permissions`와 공용 저장소 변경 통제를 함께 관리한다. 공용 워크플로 경유를 AWS 쪽에서 강제할지는 우회 위험과 정책 유지 비용을 저울질해 결정하면 된다. 각 선택의 정확한 지원 범위는 시간이 지나며 바뀔 수 있으므로 아래 공식 문서를 기준으로 재확인한다.

## 참고

- GitHub Docs, About security hardening with OpenID Connect: https://docs.github.com/en/actions/security-for-github-actions/security-hardening-your-deployments/about-security-hardening-with-openid-connect
- GitHub Docs, Configuring OpenID Connect in Amazon Web Services: https://docs.github.com/en/actions/security-for-github-actions/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services
- GitHub Docs, Reusing workflows: https://docs.github.com/en/actions/sharing-automations/reusing-workflows
- GitHub Docs, Controlling permissions for GITHUB_TOKEN: https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication
- GitHub Docs, Managing environments for deployment: https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/managing-environments-for-deployment
- AWS Docs, Create an OpenID Connect (OIDC) identity provider in IAM: https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html
- AWS Docs, AssumeRoleWithWebIdentity (STS API Reference): https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRoleWithWebIdentity.html
- aws-actions/configure-aws-credentials: https://github.com/aws-actions/configure-aws-credentials
