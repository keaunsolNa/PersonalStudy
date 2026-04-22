Notion 원본: https://www.notion.so/34a5a06fd6d381c5bfc8c9c8a010292c

# GitHub Actions로 Spring Boot CI/CD 파이프라인 구축

> 2026-04-22 신규 주제 · 확장 대상: Docker&CI, AWS (수동 배포 → 자동화/품질 게이트 심화)

## 학습 목표

- GitHub Actions 워크플로의 이벤트·잡·스텝 구조를 파악하고, `concurrency`·`permissions`·Reusable Workflow로 파이프라인 규율을 잡는다
- Gradle 빌드, Testcontainers 기반 테스트, Docker 멀티스테이지 이미지 빌드, AWS ECR Push, ECS/EKS 롤링 배포를 **하나의 파이프라인 yaml**로 묶는다
- AWS 정적 액세스 키 없이 **OIDC 신뢰 관계로 키리스(keyless) 배포**를 구성하고 `sub` 조건으로 권한을 좁힌다
- `actions/cache`·Buildx GHA 캐시로 빌드 시간을 절반 이하로 줄이고, JaCoCo/SonarCloud/Trivy 품질 게이트로 PR 단위 품질을 강제한다

---

## 1. GitHub Actions의 실행 모델

GitHub Actions는 크게 네 층으로 이해하면 된다. **이벤트(Event)** 가 트리거되면 GitHub이 가상머신(Runner)을 띄우고, 그 위에서 여러 **잡(Job)** 을 병렬로 실행하며, 각 잡은 순차적인 **스텝(Step)** 들로 구성된다. 스텝은 셸 명령이거나 재사용 가능한 **액션(Action)** 이다.

가장 자주 쓰는 이벤트는 `push`, `pull_request`, `workflow_dispatch`(수동 실행), `schedule`(cron)이다. PR 파이프라인에서는 거의 항상 다음 패턴을 쓴다.

```yaml
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

`concurrency` 블록이 핵심이다. 같은 브랜치에 연속 push가 들어오면 이전 잡을 **자동 취소**해서 Runner 비용과 대기 시간을 동시에 줄인다. 대신 `main` 브랜치 배포 잡에는 `cancel-in-progress: false`를 주거나 별도 concurrency 그룹(`deploy-main`)을 써서 **배포 중간에 끊기지 않도록** 분리하는 게 실전 팁이다.

Runner는 GitHub 호스트형(`ubuntu-latest`, `ubuntu-24.04` 등)과 self-hosted가 있다. 호스트형은 관리가 편하지만 private 네트워크 접근이 불가하므로 VPC 내부 리소스에 붙어야 한다면 self-hosted 또는 GitHub의 [larger runners] 옵션을 검토해야 한다.

## 2. Spring Boot 빌드 잡 구성

Spring Boot(Gradle) 프로젝트의 표준 빌드 잡은 다음과 같이 시작한다. 주석을 뜯어보면 한 줄 한 줄이 실전에서 필요한 이유를 가진다.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write       # dorny/test-reporter가 PR 체크에 쓰는 권한
      pull-requests: write
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0  # SonarCloud가 blame 정보 요구하므로 0

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle   # ~/.gradle/caches, wrapper 자동 캐시

      - name: Validate Gradle Wrapper
        uses: gradle/wrapper-validation-action@v3

      - name: Build & Test
        run: ./gradlew clean build --build-cache --scan
        env:
          GRADLE_OPTS: "-Xmx4g -Dorg.gradle.daemon=false"
```

`actions/setup-java`의 `cache: gradle` 옵션은 내부적으로 `actions/cache`를 호출해서 Gradle 홈과 Wrapper를 캐시한다. 별도 설정 없이도 **처음 빌드 대비 2회차부터 40~60% 단축**된다. Testcontainers를 쓴다면 Ubuntu Runner에 이미 Docker가 설치돼 있으므로 Docker-in-Docker 추가 설정은 불필요하다. 단, 컨테이너 띄우는 데 시간이 걸리므로 **테스트가 느려지면 `@Testcontainers(disabledWithoutDocker = true)` + 태그 분리**로 PR 파이프라인에서만 제한적으로 돌리는 전략을 추천한다.

## 3. 빌드 캐시 최적화 — CI 시간 반토막 내기

`setup-java`의 기본 캐시로 부족하면 두 단계 추가 최적화를 쓴다.

첫째, **의존성 캐시를 레이어별로 분리**한다. Gradle의 `~/.gradle/caches/modules-2`(의존성)과 `~/.gradle/caches/build-cache-1`(태스크 결과)는 수명이 다르다. `build.gradle.kts` 변경 시에만 후자를 무효화하도록 key를 다르게 가져가면 적중률이 올라간다.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches/modules-2
      ~/.gradle/caches/jars-9
    key: gradle-deps-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    restore-keys: |
      gradle-deps-

- uses: actions/cache@v4
  with:
    path: ~/.gradle/caches/build-cache-1
    key: gradle-taskcache-${{ github.ref }}-${{ github.sha }}
    restore-keys: |
      gradle-taskcache-${{ github.ref }}-
      gradle-taskcache-
```

둘째, 규모가 커지면 **Remote Build Cache**(Develocity/Gradle Enterprise, 또는 자체 S3)를 도입한다. 로컬 캐시는 Runner가 꺼지면 날아가지만 Remote Cache는 팀·조직 전체가 공유한다. 멀티 모듈 프로젝트에서 한 팀이 빌드한 태스크 결과를 다른 PR이 즉시 재사용할 수 있어 **체감상 가장 큰 속도 향상**을 준다.

셋째, 변경된 모듈만 빌드하려면 `dorny/paths-filter` + Gradle의 `--include-build` 조합이나, 모노레포라면 Nx/Bazel 같은 전용 도구를 도입한다. 단순히 `if: contains(github.event.head_commit.message, 'skip-ci')` 같은 꼼수는 신뢰성이 떨어지므로 지양한다.

## 4. 테스트 리포트와 커버리지 게이트

JUnit 결과 XML을 PR 체크로 올리고, JaCoCo 커버리지를 PR 코멘트로 달면 리뷰어가 바로 확인할 수 있다.

```yaml
- name: Publish Test Report
  if: always()            # 빌드가 실패해도 실행 — 실패 원인 파악 필수
  uses: dorny/test-reporter@v1
  with:
    name: JUnit Tests
    path: '**/build/test-results/test/*.xml'
    reporter: java-junit
    fail-on-error: false

- name: JaCoCo Coverage Comment
  uses: madrapps/jacoco-report@v1.7.0
  with:
    paths: '**/build/reports/jacoco/test/jacocoTestReport.xml'
    token: ${{ secrets.GITHUB_TOKEN }}
    min-coverage-overall: 70
    min-coverage-changed-files: 80

- name: Upload Failure Logs
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: failure-logs-${{ github.run_id }}
    path: |
      **/build/reports/tests/test/**
      **/build/reports/problems/**
    retention-days: 7
```

포인트는 `if: always()` 와 `if: failure()` 의 구분이다. 테스트 실패 시 리포트는 반드시 올라가야 하지만, 대용량 실패 로그 아티팩트는 실패했을 때만 업로드해 저장 용량을 아낀다. 커버리지 기준은 프로젝트마다 다르지만, 신규 PR 대상 `changed-files` 기준을 **전체 기준보다 10~15% 높게** 잡는 게 실전적이다. 레거시 코드 커버리지가 낮더라도 **새로 들어오는 코드는 테스트를 강제**하는 장치다.

## 5. 정적 분석과 보안 스캔

SonarCloud를 PR 품질 게이트로 붙이면 커버리지/중복/버그/취약점을 한 화면에서 본다. `sonar.projectKey`와 organization만 설정하면 되고, `fetch-depth: 0`을 체크아웃에 줬기 때문에 blame 기반 새 코드 판정이 정확히 돌아간다.

```yaml
- name: SonarCloud Scan
  uses: SonarSource/sonarqube-scan-action@v4
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}

- name: Trivy FS Scan (soruce/deps)
  uses: aquasecurity/trivy-action@master
  with:
    scan-type: fs
    severity: HIGH,CRITICAL
    exit-code: 1          # HIGH 이상 발견 시 CI 실패
    ignore-unfixed: true  # 픽스 없는 CVE는 제외(알람 피로 방지)
```

Dependabot은 GitHub UI의 Security 탭에서 활성화하면 의존성 업그레이드 PR을 자동 생성한다. Renovate는 커스터마이즈가 강하지만, 초기에는 Dependabot만으로 충분하다. **중요한 설정은 `auto-merge`를 patch 버전에만 허용**하는 것. minor/major는 반드시 사람이 리뷰한다.

SAST가 필요하면 CodeQL을 추가하되, 매 PR마다 돌리면 느리므로 별도 `schedule`(주 1회 + main push) 워크플로로 분리한다.

## 6. 컨테이너 이미지 빌드 — 멀티 스테이지 + GHA 캐시

운영 이미지는 **빌드 스테이지 ≠ 런타임 스테이지**로 분리해야 크기·보안 둘 다 잡힌다. `distroless/java21-debian12`는 쉘이 없어 shell-escape 공격면이 줄고, 이미지 크기도 ~200MB로 작다.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=builder /src/build/libs/*.jar app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
```

GitHub Actions에서 Buildx + GHA 캐시를 쓰면 레이어 캐시를 Runner 사이에서 공유할 수 있다. 이게 빠진 채 매번 풀빌드하면 이미지 빌드에만 4~5분이 더 붙는다.

```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v5
  with:
    context: .
    push: false
    tags: myapp:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
    provenance: true
    sbom: true
```

`provenance: true`와 `sbom: true`는 각각 빌드 출처 증명(SLSA)과 소프트웨어 구성 목록을 이미지에 부착한다. 공급망 보안 감사 요구가 있으면 필수다.

## 7. AWS OIDC 키리스 배포

**가장 중요하고 실수가 잦은 파트**다. GitHub이 서명한 OIDC 토큰을 AWS IAM이 신뢰하도록 설정하면 Access Key를 Secret에 저장할 필요가 없어진다. 키 유출 사고가 원천 차단된다.

AWS 쪽 선행 설정(Terraform 예시):

```hcl
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:my-org/my-repo:ref:refs/heads/main"]  # 브랜치 한정
    }
  }
}
```

`sub` 조건을 `repo:my-org/my-repo:*`로 너무 넓게 주면 **포크 PR에서도 배포 권한이 탈취**될 수 있다. 반드시 **브랜치/환경/태그 단위로 제한**한다.

워크플로 쪽:

```yaml
permissions:
  id-token: write       # OIDC 토큰 발급 권한 (필수)
  contents: read

steps:
  - name: Configure AWS
    uses: aws-actions/configure-aws-credentials@v4
    with:
      role-to-assume: arn:aws:iam::123456789012:role/github-deployer
      aws-region: ap-northeast-2

  - name: Login to ECR
    uses: aws-actions/amazon-ecr-login@v2

  - name: Build & Push
    run: |
      docker tag myapp:${{ github.sha }} $ECR/$REPO:${{ github.sha }}
      docker push $ECR/$REPO:${{ github.sha }}
    env:
      ECR: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
      REPO: myapp

  - name: ECS Deploy (rolling)
    uses: aws-actions/amazon-ecs-deploy-task-definition@v2
    with:
      task-definition: infra/task-def.json
      service: myapp-svc
      cluster: prod
      wait-for-service-stability: true
      wait-for-minutes: 10
```

EKS라면 `aws eks update-kubeconfig` 후 `kubectl set image ...` 또는 ArgoCD 쪽 Git 태그만 갱신하는 GitOps 방식이 더 깔끔하다. 파이프라인에서 직접 `kubectl apply`하는 방식은 **롤백 추적이 어렵다**는 단점이 있다.

## 8. 릴리스 전략과 환경 분리

GitHub Environments(`environment: production`)를 지정하면 배포 전에 **Approval Gate**(지정된 리뷰어의 승인)가 걸리고, 환경별 Secret을 분리할 수 있다. 프로덕션 배포는 반드시 별도 환경으로 분리한다.

```yaml
deploy-prod:
  needs: [build, push-image]
  runs-on: ubuntu-latest
  environment:
    name: production
    url: https://myapp.example.com
  if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')
  steps:
    - ... deploy ...
```

버전 관리는 [release-please](https://github.com/googleapis/release-please-action)처럼 Conventional Commits 기반 자동화 도구를 쓰면 CHANGELOG와 태그가 자동 생성된다. `feat:`는 minor, `fix:`는 patch, `BREAKING CHANGE:`는 major로 올라간다. 수동으로 태그를 관리하면 실수가 잦다.

Blue-Green이나 Canary가 필요하면 배포 자체는 AWS CodeDeploy / ArgoCD Rollouts에게 위임하고, GitHub Actions는 **트리거와 상태 조회 역할만** 맡도록 경계를 나눈다. 파이프라인에 트래픽 전환 로직을 넣으면 금방 복잡해진다.

## 9. 운영·비용 관점 체크리스트

실제 도입 후 6개월 운영해보면 다음 항목이 문제가 된다.

**권한 최소화**. 워크플로 파일 최상단에 `permissions: {}`로 모든 권한을 끄고, 필요한 잡에만 `permissions: {id-token: write, contents: read}`처럼 명시한다. Third-party 액션이 생각보다 많은 권한을 요구한다.

**Third-party 액션 핀**. `uses: some/action@v3` 대신 `uses: some/action@<full-sha>`로 고정해 공급망 공격을 방지한다. Renovate가 SHA 업그레이드 PR을 자동으로 만든다.

**비용 모니터링**. GitHub이 제공하는 Usage report에서 워크플로별 소요 분을 주기적으로 확인한다. Matrix 빌드가 과도하거나 `schedule`이 너무 자주 돌면 Private repo 요금이 순식간에 늘어난다. `timeout-minutes`를 잡별로 걸어 무한 반복을 차단한다.

**Reusable Workflow**. 여러 레포가 있으면 `.github/workflows/build.yml`을 조직 레포에 두고 `uses: org/.github/.github/workflows/build.yml@v1` 형태로 호출한다. 파이프라인이 일관되고, 한 곳에서 보안 패치를 반영할 수 있다.

**관측**. GitHub의 기본 UI만으로는 분석이 부족하면 [Datadog CI Visibility], [CircleCI Insights]처럼 CI 메트릭을 수집하는 외부 도구를 붙인다. "어느 스텝이 느려졌는가"를 시계열로 보는 게 개선의 출발점이다.

---

## 참고

- 기학습 연계: [Docker&CI](./Docker&CI.md), [AWS](./AWS.md), [Spring](./Spring.md)
- [GitHub Docs — About GitHub Actions](https://docs.github.com/en/actions)
- [AWS Blog — "Use IAM roles to connect GitHub Actions to AWS"](https://aws.amazon.com/blogs/security/use-iam-roles-to-connect-github-actions-to-deployments-in-aws/)
- [Gradle Docs — Build Cache](https://docs.gradle.org/current/userguide/build_cache.html)
- [SLSA Framework](https://slsa.dev/) — 빌드 공급망 보안 표준
- [release-please GitHub](https://github.com/googleapis/release-please-action) — Conventional Commits 기반 자동 릴리스
