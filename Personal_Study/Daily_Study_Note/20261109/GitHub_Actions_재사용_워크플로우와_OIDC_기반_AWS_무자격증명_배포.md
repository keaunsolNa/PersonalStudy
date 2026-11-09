Notion 원본: https://app.notion.com/p/4135a06fd6d381a893defa8faddebf28

# GitHub Actions 재사용 워크플로우와 OIDC 기반 AWS 무자격증명 배포

> 2026-11-09 신규 주제 · 확장 대상: DevOps (GitHub Actions · AWS)

## 학습 목표

- 장기 Access Key 를 없애고 GitHub OIDC 토큰으로 AWS IAM Role 을 맡는다
- IAM 신뢰 정책 `sub` 조건으로 브랜치와 환경 단위 접근을 제한한다
- `workflow_call` 재사용 워크플로우로 빌드·배포 파이프라인을 표준화한다
- 캐시와 `concurrency` 로 CI 시간과 중복 배포를 줄인다

## 1. 장기 자격증명의 문제

저장소 Secrets 에 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` 를 넣는 방식은 키가 유출되면 수동 회수 전까지 유효하고 회전도 번거롭다. OIDC 방식은 워크플로우 실행마다 GitHub 가 서명한 단기 JWT 를 발급하고, AWS STS 가 이를 검증해 **1시간 이내 임시 자격증명**을 돌려준다. 저장되는 비밀이 없다.

## 2. AWS 측 설정

IAM 에 ID 공급자 `token.actions.githubusercontent.com`(audience `sts.amazonaws.com`)를 등록하고, 신뢰 정책으로 허용 대상을 좁힌다. 핵심은 `sub` 조건이며 이를 빼면 어떤 저장소든 이 Role 을 맡을 수 있다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:keaunsolNa/my-app:ref:refs/heads/main"
      }
    }
  }]
}
```

환경(Environment)을 쓰면 sub 가 `repo:ORG/REPO:environment:production` 형태가 되어 승인 규칙과 결합할 수 있다. PR 빌드에서 배포 Role 을 맡지 못하도록 브랜치/환경 단위로 Role 을 분리한다.

## 3. 워크플로우 작성

`id-token: write` 권한이 있어야 OIDC 토큰을 요청할 수 있다. 권한을 명시하면 나머지는 기본 none 이 되므로 `contents: read` 도 함께 적는다.

```yaml
name: deploy
on:
  push:
    branches: [main]

permissions:
  id-token: write
  contents: read

concurrency:
  group: deploy-${{ github.ref }}
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy
          aws-region: ap-northeast-2
      - run: aws sts get-caller-identity
      - run: ./gradlew bootJar && aws s3 cp build/libs/app.jar s3://my-bucket/app.jar
```

`aws sts get-caller-identity` 출력이 assumed-role ARN 이면 성공이다. 배포는 `cancel-in-progress: false` 로 진행 중 배포를 끊지 않고 대기열에 둔다. CI 빌드는 반대로 true 로 구세대 실행을 취소해 러너 시간을 아낀다.

## 4. 재사용 워크플로우

여러 저장소가 같은 빌드·배포 절차를 쓰면 `workflow_call` 로 한 곳에서 관리한다.

```yaml
# .github/workflows/java-build.yml (공용 저장소)
on:
  workflow_call:
    inputs:
      java-version: { type: string, default: '21' }
    secrets:
      SONAR_TOKEN: { required: false }
    outputs:
      artifact-name:
        value: ${{ jobs.build.outputs.artifact }}
jobs:
  build:
    runs-on: ubuntu-latest
    outputs:
      artifact: app-${{ github.sha }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '${{ inputs.java-version }}', cache: gradle }
      - run: ./gradlew test bootJar
```

호출 측은 `uses: ORG/ci-templates/.github/workflows/java-build.yml@v1` 로 참조하고 `with:` 로 입력을 넘긴다. `@v1` 처럼 태그나 SHA 로 고정해 공용 워크플로우 변경이 즉시 모든 저장소에 번지지 않게 한다. 재사용 워크플로우는 중첩 깊이 4단계까지 허용된다.

## 5. 캐시 전략

`setup-java` 의 `cache: gradle` 은 `~/.gradle/caches` 를 의존성 파일 해시 키로 저장한다. 캐시 적중 시 의존성 다운로드 구간이 사라져 빌드 시간이 크게 줄어든다. 단 캐시 총량은 저장소당 10GB 이며 7일 미사용 항목은 삭제된다. 빌드 산출물 자체 캐시는 키 설계가 중요해, `hashFiles('**/*.gradle*', 'gradle/wrapper/**')` 처럼 변경 감지 범위를 정확히 지정한다.

## 6. 보안 체크리스트

서드파티 Action 은 태그가 아니라 커밋 SHA 로 고정한다. `pull_request_target` 에서 외부 PR 코드를 체크아웃하면서 권한 있는 토큰을 쓰지 않는다. Role 권한은 배포 대상 리소스만 허용하는 최소 권한으로 구성하고, CloudTrail 에서 `AssumeRoleWithWebIdentity` 이벤트의 `sub` 값을 주기적으로 감사한다.

## 참고

- GitHub Docs: Configuring OpenID Connect in Amazon Web Services
- GitHub Docs: Reusing workflows, Workflow syntax – concurrency
- aws-actions/configure-aws-credentials README
- AWS IAM Docs: Creating OpenID Connect identity providers
