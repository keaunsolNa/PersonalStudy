Notion 원본: https://app.notion.com/p/3c15a06fd6d38176aa71eb45274ce86d?pvs=204

# GitHub Actions OIDC 페더레이션과 재사용 워크플로우 및 공급망 서명

> 2026-08-19 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- OIDC 토큰의 `sub` 클레임 구조를 해석해 AWS 신뢰 정책의 조건을 최소 권한으로 좁힌다
- reusable workflow 와 composite action 의 실행 컨텍스트 차이를 secrets·permissions 관점으로 구분한다
- `pull_request_target` 과 캐시 포이즈닝 등 실제 공격 경로를 차단하는 설정을 적용한다
- cosign keyless 서명과 SLSA provenance 를 빌드 파이프라인에 붙이고 검증 게이트를 건다

## 1. 장기 자격증명을 없애는 이유

CI 에서 클라우드에 접근하려면 자격증명이 필요하다. 전통적으로는 IAM 사용자를 만들어 액세스 키를 발급하고 GitHub Secrets 에 넣었다. 이 방식의 문제는 세 겹이다. 키가 만료되지 않으므로 유출 시 무기한 유효하고, 로테이션이 수동이라 실제로는 몇 년째 같은 키가 돌며, 리포지토리 협업자 누구나 워크플로우를 수정해 그 키를 외부로 유출시킬 수 있다.

OIDC 페더레이션은 키 자체를 없앤다. GitHub 이 워크플로우 실행마다 짧은 수명의 JWT 를 발급하고, 클라우드가 그 JWT 를 검증해 임시 자격증명을 내준다. 저장되는 비밀이 없으므로 유출될 것도 없다.

```yaml
permissions:
  id-token: write   # OIDC 토큰 요청 권한 (반드시 명시)
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy
          aws-region: ap-northeast-2
```

`id-token: write` 가 없으면 액션이 토큰을 못 받아 실패한다. 이 권한은 기본값이 아니며, 잡 단위로도 줄 수 있으므로 필요한 잡에만 부여하는 것이 원칙이다.

## 2. sub 클레임 구조와 신뢰 정책 좁히기

발급되는 JWT 의 핵심은 `sub`(subject)다. 트리거 종류에 따라 형태가 달라진다.

```text
브랜치 푸시:   repo:myorg/myrepo:ref:refs/heads/main
태그:          repo:myorg/myrepo:ref:refs/tags/v1.2.3
풀리퀘스트:    repo:myorg/myrepo:pull_request
환경 지정:     repo:myorg/myrepo:environment:production
```

가장 흔한 실수는 신뢰 정책을 `repo:myorg/*:*` 처럼 넓게 쓰는 것이다. 이러면 조직 내 아무 리포지토리, 아무 브랜치에서도 그 역할을 가져갈 수 있다. 인턴이 만든 실험 리포에서 프로덕션 배포 역할을 assume 할 수 있다는 뜻이다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub":
          "repo:myorg/myrepo:environment:production"
      }
    }
  }]
}
```

`environment:production` 으로 묶는 것이 가장 강한 형태다. GitHub Environments 에는 필수 리뷰어·대기 시간·브랜치 제한을 걸 수 있으므로, 승인 없이는 토큰 자체가 발급되지 않는다. 브랜치 기반(`ref:refs/heads/main`)은 main 에 푸시 권한이 있는 사람이면 누구나 우회 가능하므로 한 단계 약하다.

`aud` 검증도 빠뜨리면 안 된다. `StringEquals` 대신 `StringLike` 를 쓰면서 `aud` 조건을 생략하는 정책이 실제 사고로 이어진 사례가 있다. 조건은 항상 `sub` 와 `aud` 를 함께 `StringEquals` 로 건다.

와일드카드가 꼭 필요하면 접두사 고정형으로 쓴다.

```json
"StringLike": {
  "token.actions.githubusercontent.com:sub": "repo:myorg/myrepo:ref:refs/tags/v*"
}
```

## 3. 재사용 워크플로우와 composite action

코드 중복을 줄이는 두 수단이 있는데 격리 수준이 다르다.

| 항목 | Reusable Workflow | Composite Action |
|---|---|---|
| 호출 위치 | `jobs.<id>.uses` | `steps[].uses` |
| 실행 컨텍스트 | 별도 잡, 러너 새로 할당 가능 | 호출 잡과 동일 러너·동일 스텝 흐름 |
| secrets 전달 | `secrets:` 또는 `secrets: inherit` 명시 필요 | 호출자 컨텍스트 그대로 접근 |
| permissions | 호출자 권한의 부분집합만 가능 | 호출 잡 권한 그대로 |
| 중첩 | 최대 4단계 | 최대 10단계 |
| matrix 사용 | 호출 잡에서 가능 | 불가 |

보안 관점에서 재사용 워크플로우가 우월하다. secrets 를 명시적으로 넘겨야 하므로 필요한 것만 전달할 수 있고, `permissions` 가 호출자보다 커질 수 없다. composite action 은 호출 잡의 모든 컨텍스트에 접근하므로, 서드파티 composite action 을 쓰면 그 잡의 모든 secrets 가 노출된 것과 같다.

```yaml
# .github/workflows/reusable-deploy.yml
on:
  workflow_call:
    inputs:
      environment:
        required: true
        type: string
      image-tag:
        required: true
        type: string
    secrets:
      SLACK_WEBHOOK:
        required: false

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.DEPLOY_ROLE_ARN }}
          aws-region: ap-northeast-2
      - run: aws ecs update-service --cluster prod --service api --force-new-deployment
```

호출 측은 이렇게 쓴다.

```yaml
jobs:
  call-deploy:
    uses: myorg/shared-workflows/.github/workflows/reusable-deploy.yml@v1.4.0
    with:
      environment: production
      image-tag: ${{ github.sha }}
    secrets:
      SLACK_WEBHOOK: ${{ secrets.SLACK_WEBHOOK }}
```

`secrets: inherit` 는 편하지만 호출자의 모든 secrets 를 통째로 넘기므로 공유 워크플로우가 침해되면 전부 유출된다. 개별 명시가 원칙이다.

버전 고정도 중요하다. `@main` 으로 참조하면 공유 리포의 변경이 즉시 모든 소비자에 반영된다. 태그(`@v1.4.0`)나 커밋 SHA 로 고정하고, 서드파티 액션은 반드시 전체 SHA 로 핀한다.

```yaml
- uses: actions/checkout@08eba0b27e820071cde6df949e0beb9ba4906955  # v4.3.0
```

태그는 리포 소유자가 다른 커밋으로 옮길 수 있다. SHA 는 불변이다. Dependabot 이 SHA 핀을 자동 갱신해 주므로 유지보수 부담은 크지 않다.

## 4. 실제 공격 경로 세 가지

**첫째, `pull_request_target` 오용.** 이 트리거는 포크 PR 에서도 베이스 리포의 secrets 와 쓰기 토큰을 받는다. 여기서 PR 브랜치 코드를 체크아웃해 실행하면, 외부인이 보낸 임의 코드가 secrets 를 가진 채 돌아간다.

```yaml
# 위험
on: pull_request_target
jobs:
  build:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}   # 신뢰 불가 코드
      - run: npm install && npm test                        # 임의 코드 실행
```

`npm install` 하나로 `postinstall` 스크립트가 돌아 secrets 를 외부로 보낼 수 있다. 규칙은 단순하다. `pull_request_target` 에서는 PR 코드를 절대 체크아웃하거나 실행하지 않는다. 라벨 붙이기·코멘트 같은 메타 작업만 한다. 빌드·테스트가 필요하면 `pull_request` 를 쓰고(secrets 없음), 승인 후 실행이 필요하면 Environment 승인 게이트를 건다.

**둘째, 스크립트 인젝션.** `run:` 블록 안에 표현식을 직접 넣으면 그 값이 셸 스크립트로 치환된다.

```yaml
# 위험: PR 제목에 백틱을 넣으면 명령 실행
- run: echo "Title: ${{ github.event.pull_request.title }}"
```

방어는 환경변수 경유다.

```yaml
- env:
    PR_TITLE: ${{ github.event.pull_request.title }}
  run: echo "Title: $PR_TITLE"
```

환경변수는 셸이 값으로만 취급하므로 치환이 일어나지 않는다. `github.event.*` 중 사용자가 제어 가능한 필드(title, body, branch name, commit message, author name)는 전부 이 규칙을 적용한다.

**셋째, 캐시 포이즈닝.** `actions/cache` 는 브랜치 스코프를 갖지만 기본 브랜치 캐시는 모든 브랜치에서 읽힌다. 반대 방향은 막혀 있어 PR 브랜치가 만든 캐시를 main 이 읽지는 않는다. 그러나 같은 PR 브랜치 내에서는 오염된 캐시가 재사용되므로, 캐시 키에 lock 파일 해시를 반드시 포함시켜 내용 불일치를 막는다.

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.gradle/caches
    key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    restore-keys: |
      gradle-${{ runner.os }}-
```

릴리스 빌드는 캐시를 아예 쓰지 않는 선택도 정당하다. 재현성과 속도 중 재현성을 택하는 것이다.

## 5. 기본 권한을 read-only 로 내리기

`GITHUB_TOKEN` 의 기본 권한은 조직 설정에 따라 read-write 일 수 있다. 이 상태에서 서드파티 액션이 침해되면 리포에 커밋을 밀어 넣을 수 있다. 조직 전체 설정에서 기본을 read 로 내리고, 워크플로우마다 필요한 것만 올린다.

```yaml
permissions:
  contents: read

jobs:
  release:
    permissions:
      contents: write      # 태그·릴리스 생성
      packages: write      # GHCR 푸시
      id-token: write      # keyless 서명
```

최상단 `permissions` 는 모든 잡의 기본값이 되고, 잡 단위 선언이 이를 덮어쓴다. 최상단에 `contents: read` 만 두면 명시하지 않은 나머지는 전부 none 이 된다.

## 6. cosign keyless 서명

컨테이너 이미지에 서명을 붙이면 "이 이미지가 우리 CI 에서, 이 커밋으로부터 나왔다"를 검증 가능하게 만든다. keyless 방식은 OIDC 토큰으로 단기 인증서를 발급받아 서명하고, 서명 기록을 공개 투명성 로그(Rekor)에 남긴다. 개인키를 보관할 필요가 없다.

```yaml
jobs:
  build-and-sign:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
      id-token: write
    steps:
      - uses: actions/checkout@v4

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - id: build
        uses: docker/build-push-action@v6
        with:
          push: true
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}

      - uses: sigstore/cosign-installer@v3

      - name: Sign image
        run: |
          cosign sign --yes \
            ghcr.io/${{ github.repository }}@${{ steps.build.outputs.digest }}
```

서명은 반드시 **다이제스트**에 건다. 태그는 나중에 다른 이미지를 가리킬 수 있으므로 태그 서명은 의미가 약하다.

검증 측에서는 신원 조건을 명시해야 한다. 조건 없이 "서명이 있다"만 확인하면 아무나 만든 서명도 통과한다.

```bash
cosign verify \
  --certificate-identity-regexp "^https://github.com/myorg/myrepo/.github/workflows/release.yml@refs/tags/v.*$" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/myorg/myrepo@sha256:abc123...
```

`certificate-identity` 가 워크플로우 파일 경로와 ref 까지 포함하므로, "release.yml 이 태그에서 실행된 경우"만 통과시킬 수 있다.

## 7. SLSA provenance 와 검증 게이트

서명은 출처를 증명하지만 "무엇으로부터 어떻게 빌드했는가"는 담지 않는다. provenance 는 소스 커밋, 빌더 신원, 빌드 파라미터를 구조화된 증명(attestation)으로 남긴다. GitHub 은 이를 내장 액션으로 제공한다.

```yaml
      - uses: actions/attest-build-provenance@v2
        with:
          subject-name: ghcr.io/${{ github.repository }}
          subject-digest: ${{ steps.build.outputs.digest }}
          push-to-registry: true
```

검증은 배포 직전 게이트로 건다.

```bash
gh attestation verify \
  oci://ghcr.io/myorg/myrepo@sha256:abc123... \
  --repo myorg/myrepo \
  --signer-workflow myorg/myrepo/.github/workflows/release.yml
```

쿠버네티스 환경이라면 admission controller 단계에서 강제하는 것이 실효적이다. Kyverno 나 Sigstore policy-controller 로 "서명과 provenance 가 없는 이미지는 스케줄링 거부" 정책을 건다.

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: require-signed-images
spec:
  validationFailureAction: Enforce
  rules:
    - name: verify-ghcr-signature
      match:
        any:
          - resources:
              kinds: [Pod]
      verifyImages:
        - imageReferences: ["ghcr.io/myorg/*"]
          attestors:
            - entries:
                - keyless:
                    subject: "https://github.com/myorg/*/.github/workflows/release.yml@refs/tags/v*"
                    issuer: "https://token.actions.githubusercontent.com"
```

이 정책이 없으면 서명은 장식일 뿐이다. 서명·증명 생성과 **검증 강제**는 항상 짝으로 도입해야 하며, 검증 없는 서명 파이프라인은 비용만 늘리고 보안 이득이 없다.

## 8. 도입 순서 정리

한 번에 전부 바꾸려 하면 배포가 멈춘다. 실무 순서는 이렇다. 첫째, 조직 기본 `GITHUB_TOKEN` 권한을 read 로 내리고 실패하는 워크플로우에 개별 permissions 를 추가한다. 둘째, 서드파티 액션을 전부 SHA 로 핀하고 Dependabot 을 켠다. 셋째, `run:` 안의 표현식 인젝션을 정적 검사(`zizmor`, `actionlint`)로 잡는다. 넷째, 클라우드 자격증명을 OIDC 로 전환하되 처음에는 스테이징 역할부터 한다. 다섯째, 서명·provenance 를 붙이고 처음에는 경고 모드(`Audit`)로 운영하다 안정되면 `Enforce` 로 올린다.

각 단계마다 롤백 경로를 남긴다. 특히 OIDC 전환 시 기존 액세스 키를 즉시 삭제하지 말고 비활성화 상태로 며칠 두었다가, IAM Credential Report 에서 마지막 사용 시각이 전환 이전임을 확인한 뒤 제거한다.

## 참고

- GitHub Docs — Security hardening for GitHub Actions
- GitHub Docs — About security hardening with OpenID Connect
- AWS Documentation — Creating OpenID Connect (OIDC) identity providers
- Sigstore Documentation — cosign keyless signing, Rekor transparency log
- SLSA v1.0 Specification — Build Provenance
- OpenSSF — Scorecard, `actionlint`, `zizmor` 정적 분석 도구
