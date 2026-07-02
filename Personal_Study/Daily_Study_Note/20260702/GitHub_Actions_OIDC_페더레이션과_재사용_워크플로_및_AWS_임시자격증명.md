Notion 원본: https://www.notion.so/3915a06fd6d38122aba6d5dbf88f7bf8

# GitHub Actions OIDC 페더레이션과 재사용 워크플로 및 AWS 임시자격증명

> 2026-07-02 신규 주제 · 확장 대상: DevOps(GitHub Actions·Docker CI 학습됨)

## 학습 목표

- 장수 액세스 키의 위험과 OIDC 페더레이션이 그것을 제거하는 원리를 설명한다
- GitHub OIDC 토큰의 클레임 구조와 AWS IAM 신뢰 정책의 매칭을 구성한다
- 재사용 워크플로와 composite action의 차이를 구분한다
- sub 조건 오구성으로 인한 권한 탈취 위험을 진단한다

## 1. 문제 - CI에 박아둔 장수 자격증명

가장 흔한 CI 취약점은 AWS_ACCESS_KEY_ID/SECRET를 GitHub Secrets에 저장하는 것이다. 이 키는 만료가 없고 유출 표면이 넓다. OIDC 페더레이션은 저장된 키 자체를 없액다. GitHub가 실행 시점에 신원을 증명하는 단명 JWT를 발급하고 AWS가 검증해 15분~1시간짜리 임시 자격증명을 내준다.

## 2. OIDC 토큰의 클레임 구조

| 클레임 | 의미 | 예시 |
|---|---|---|
| iss | 발급자 | token.actions.githubusercontent.com |
| sub | 주체(신뢰 조건 핵심) | repo:octo/app:ref:refs/heads/main |
| aud | 대상 | sts.amazonaws.com |
| repository | 레포 | octo/app |
| environment | 환경 | production |

sub 클레임이 접근 제어의 심장이다. AWS는 이 sub가 특정 패턴과 일치할 때만 역할 위임을 허용한다.

## 3. AWS 쪽 구성 - OIDC 공급자와 신뢰 정책

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": { "token.actions.githubusercontent.com:sub": "repo:octo/app:ref:refs/heads/main" }
    }
  }]
}
```

aud는 반드시 StringEquals(정확 일치)로, sub는 정책 목적에 맞게 지정한다.

## 4. GitHub 쪽 구성 - 워크플로 권한

```yaml
name: deploy
on:
  push:
    branches: [main]

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy
          aws-region: ap-northeast-2
      - run: aws s3 sync ./dist s3://my-bucket --delete
```

id-token: write 권한이 없으면 토큰 자체가 발급되지 않는다. 액션이 OIDC 토큰으로 AssumeRoleWithWebIdentity를 호출해 임시 자격증명을 환경 변수로 노출하고, 잡이 끝나면 사라진다.

## 5. 치명적 오구성 - sub 와일드카드

```json
"StringLike": { "token.actions.githubusercontent.com:sub": "repo:octo/*" }
```

이렇게 두면 조직 내 아무 레포나(공격자 신규 레포) 이 역할을 위임받을 수 있다. 방어 원칙은 aud 정확 일치, sub를 레포·브랜치(또는 environment)까지 좁히기, 프로덕션 배포 역할은 environment 클레임으로 보호 환경에 묶기다.

```json
"StringLike": { "token.actions.githubusercontent.com:sub": "repo:octo/app:environment:production" }
```

## 6. 재사용 워크플로 vs composite action

| 구분 | Reusable Workflow | Composite Action |
|---|---|---|
| 호출 방식 | jobs.<id>.uses | steps.uses |
| 단위 | 잡 전체 | 스텝 묶음 |
| 러너 | 자체 러너 | 호출한 잡의 러너 |
| secrets 전달 | secrets: inherit | 입력으로 전달 |

여러 잡·매트릭스를 통째로 표준화하려면 재사용 워크플로가, 공유 스텝 시퀀스를 묶으려면 composite action이 맞다. OIDC와 결합 시 permissions가 호출 워크플로에 명시돼야 하고 권한은 상속되지 않고 좁혀지기만 한다.

## 7. 임시 자격증명의 수명과 세션 정책

기본 수명은 1시간이며 역할의 최대 세션 시간으로 상한이 정해진다. 세션 정책으로 이번 실행에서만 권한을 더 좁힐 수 있다. 이 단명성 자체가 보안 이득의 핵심이다.

## 8. 포크 PR과 pull_request_target의 위험 교차점

포크 PR은 pull_request 이벤트로 실행되며 기본적으로 시크릿과 id-token: write가 제공되지 않아 포크 코드가 토큰을 얻지 못한다. 문제는 pull_request_target이다. 이 이벤트는 베이스 레포 컨텍스트(시크릿·권한 포함)로 실행되므로, 포크가 수정한 코드를 체크아웃해 실행하면 베이스 권한으로 OIDC 토큰을 발급받아 역할을 탈취할 수 있다.

```yaml
on: pull_request_target
jobs:
  build:
    permissions:
      id-token: write
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}   # 포크 코드!
      - run: ./scripts/build.sh
```

배포용 OIDC 권한이 필요한 잡은 포크 PR 컨텍스트에서 절대 실행하지 않고, 실제 배포는 신뢰된 push나 승인 게이트가 걸린 environment에서만 수행한다.

## 9. 멀티 클라우드로의 일반화

OIDC는 표준 프로토콜이므로 같은 GitHub 토큰으로 GCP(Workload Identity Federation), Azure(federated credentials), Vault(JWT auth) 등 여러 대상에 인증할 수 있다.

```yaml
- uses: google-github-actions/auth@v2
  with:
    workload_identity_provider: projects/123/locations/global/workloadIdentityPools/gh/providers/gh
    service_account: deployer@my-project.iam.gserviceaccount.com
```

핵심 이득은 일관성이다. 새 대상이 늘어도 저장할 비밀은 여전히 0개고, 관리 대상은 각 대상의 신뢰 정책뿐이다.

## 10. 결론

OIDC 페더레이션은 CI 보안을 비밀을 잘 저장하기에서 저장할 비밀을 없애기로 바꾸다. 관건은 sub·aud·environment 클레임으로 신뢰 경계를 정확히 좁히는 것이며, 여기가 느슨하면 오히려 조직 전체가 노출된다.

## 참고

- GitHub Docs — Security hardening with OpenID Connect
- aws-actions/configure-aws-credentials
- AWS IAM — OIDC identity providers, AssumeRoleWithWebIdentity
- GitHub Docs — Reusing workflows, Creating composite actions
