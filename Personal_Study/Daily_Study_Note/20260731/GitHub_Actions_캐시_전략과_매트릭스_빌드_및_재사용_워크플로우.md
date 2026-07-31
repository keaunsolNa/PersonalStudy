Notion 원본: https://www.notion.so/3ae5a06fd6d38182b34aff60b7a0c200

# GitHub Actions 캐시 전략과 매트릭스 빌드 및 재사용 워크플로우

> 2026-07-31 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- actions/cache 의 key·restore-keys 매칭 규칙과 불변 캐시 정책을 정확히 이해한다
- 매트릭스 빌드로 다차원 조합을 병렬 실행하고 include/exclude 로 조합을 제어한다
- reusable workflow 와 composite action 의 차이와 선택 기준을 구분한다
- concurrency·permissions·OIDC 로 파이프라인의 안전성과 비용을 최적화한다

## 1. 캐시의 동작 모델과 불변성

CI 시간의 상당 부분은 의존성 다운로드와 빌드 산출물 재생성에 쓰인다. `actions/cache` 는 이를 job 사이에 보존한다. 핵심은 캐시가 **key 로 식뱄되는 불변(immutable) 객체**라는 점이다. 특정 key 로 캐시가 한번 저장되면 그 key 의 내용은 덮어쓸 수 없다. 그래서 key 는 "캐시 대상이 바뀌면 값도 바뀌는" 해시를 포함해야 한다. 보통 잠금 파일(lockfile)의 해시를 쓴다.

```yaml
- name: Cache Gradle
  uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches
      ~/.gradle/wrapper
    # 잠금/빌드 스크립트가 바뀌면 새 캐시 key 생성
    key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    # 정확히 일치가 없으면 접두사로 가장 최근 캐시를 복원
    restore-keys: |
      ${{ runner.os }}-gradle-
```

동작 순서를 정확히 알아야 한다. job 시작 시 `key` 로 **정확히 일치(exact hit)** 하는 캐시를 찾는다. 없으면 `restore-keys` 접두사로 **가장 최근 부분 일치(partial hit)** 를 복원한다. 부분 일치로 복원했더라도, job 끝의 post 단계에서 원래 `key` 로 **새 캐시를 저장**한다(exact hit 였다면 저장하지 않음). 이 "부분 복원 + 새 저장" 사이클 덕분에 의존성이 조금 바뀌어도 이전 캐시를 기반으로 증분만 받으면 된다.

주의할 함정 두 가지. 첫째, 캐시는 **브랜치 스코프**다. 기본 브랜치에서 만든 캐시는 PR 브랜치에서 복원되지만, PR 브랜치에서 만든 캐시는 다른 PR 로 넘어가지 않는다. 그래서 첫 CI 를 빠르게 하려면 main 에서 캐시를 워밍업하는 것이 유효하다. 둘째, 저장소당 캐시 총량(약 10GB) 한도를 넘으면 LRU 로 오래된 캐시가 제거되므로, path 를 과도하게 넓게 잡으면 정작 필요한 캐시가 밀려난다.

## 2. setup 액션 내장 캐시와의 관계

`actions/setup-node`, `setup-java`, `setup-python` 등은 `cache` 옵션을 내장한다. 내부적으로 `actions/cache` 를 감싸 패키지 매니저 디렉터리를 자동 캐싱한다.

```yaml
- uses: actions/setup-node@v4
  with:
    node-version: 20
    cache: 'npm'                    # ~/.npm 자동 캐시 + lockfile 해시 key
    cache-dependency-path: pnpm-lock.yaml
```

내장 캐시는 "패키지 매니저 캐시 디렉터리"만 다룬다. 빌드 산출물(컴파일된 클래스, `.next`, `target/`)까지 캐싱하려면 별도 `actions/cache` 스텝이 필요하다. 즉 둘은 배타적이 아니라 계층적으로 함께 쓴다. 의존성은 setup 액션이, 빌드 산출물은 명시적 cache 스텝이 담당하도록 나누는 것이 깔끔하다.

## 3. 매트릭스 빌드 — 다차원 조합의 병렬화

여러 런타임·OS 조합을 검증할 때 매트릭스를 쓴다. 각 조합은 **독립 job 으로 병렬 실행**된다.

```yaml
jobs:
  test:
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false            # 한 조합 실패해도 나머지 계속 실행
      max-parallel: 4
      matrix:
        os: [ubuntu-latest, windows-latest]
        java: [17, 21]
        include:
          - os: ubuntu-latest     # 특정 조합에만 변수 추가
            java: 21
            coverage: true
        exclude:
          - os: windows-latest    # 불필요한 조합 제거
            java: 17
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: gradle
      - run: ./gradlew test
```

설계 포인트. `fail-fast: true`(기본)는 한 조합이 실패하면 나머지를 즉시 취소한다. 빠른 피드백에는 좋지만, 전체 매트릭스의 실패 분포를 보고 싶으면 `false` 로 둔다. `include` 는 기존 조합에 변수를 덧붙이거나 새 조합을 추가하고, `exclude` 는 데카르트 곱에서 특정 조합을 뺀다. 매트릭스가 커질수록 러너 사용량(=비용)이 곱으로 늘어나므로, 핵심 조합만 남기고 `exclude` 로 가지치기하는 것이 비용 관리의 핵심이다.

## 4. reusable workflow — 파이프라인 단위 재사용

여러 저장소·워크플로우에서 같은 CI 절차를 복제하면 유지보수가 지옥이 된다. **reusable workflow** 는 워크플로우 전체를 함수처럼 호출한다.

```yaml
# .github/workflows/reusable-build.yml
on:
  workflow_call:                  # 호출 가능 워크플로우로 선언
    inputs:
      java-version:
        type: string
        default: '21'
    secrets:
      SONAR_TOKEN:
        required: false
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ inputs.java-version }}
      - run: ./gradlew build
```

```yaml
# 호출 측 워크플로우
jobs:
  ci:
    uses: my-org/ci-templates/.github/workflows/reusable-build.yml@v2
    with:
      java-version: '21'
    secrets:
      SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

버전 고정(`@v2` 또는 커밋 SHA)이 중요하다. `@main` 으로 참조하면 템플릿이 바뀌는 순간 모든 소비 파이프라인이 동시에 흔들린다. 태그·SHA 로 고정하고 의도적으로 올리는 것이 안전하다.

## 5. composite action 과의 차이

**composite action** 은 여러 스텝을 하나의 액션으로 묶는다. reusable workflow 가 "job 여러 개를 포함하는 파이프라인 단위"라면, composite action 은 "한 job 안에서 재사용하는 스텝 묶음"이다.

```yaml
# .github/actions/setup-project/action.yml
name: 'Setup Project'
runs:
  using: 'composite'
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: '21'
        cache: gradle
    - run: ./gradlew dependencies
      shell: bash            # composite 스텝은 shell 명시 필수
```

선택 기준은 명확하다. **여러 스텝을 하나의 job 안에서** 반복하면 composite action, **여러 job 으로 이뤄진 전체 파이프라인**을 통째로 재사용하면 reusable workflow 다. composite action 은 매트릭스의 각 job 안에서 setup 을 통일하는 데 적합하고, reusable workflow 는 "빌드→테스트→배포"라는 절차 자체를 표준화하는 데 적합하다.

## 6. concurrency·권한·OIDC 로 안전·비용 최적화

**concurrency** 는 중복 실행을 억제한다. PR 에 커밋을 연달아 푸시하면 이전 실행이 낭비되는데, 같은 그룹의 진행 중 실행을 취소해 러너 비용을 아낀다.

```yaml
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true       # 새 푸시가 오면 진행 중 실행 취소
```

**permissions** 는 `GITHUB_TOKEN` 의 스코프를 job 단위로 최소화한다. 기본을 좁게 두고 필요한 job 에만 권한을 부여하는 것이 공급망 보안의 기본이다.

```yaml
permissions:
  contents: read                 # 워크플로우 전역 기본을 읽기로 축소
jobs:
  deploy:
    permissions:
      id-token: write            # OIDC 토큰 발급용 (클라우드 인증)
      contents: read
```

마지막으로 **OIDC(id-token)** 는 장기 클라우드 자격증명을 저장소 시크릿에 넣지 않게 해준다. AWS·GCP 에 단명 토큰으로 페더레이션 인증하므로, 유출 시 피해가 제한되고 키 로테이션 부담이 사라진다. 정리하면 GitHub Actions 최적화는 세 축이다. 캐시로 시간을, 매트릭스 가지치기와 concurrency 로 비용을, 최소 권한과 OIDC 로 보안을 관리한다. 세 축은 독립이 아니라 서로 맞물려 파이프라인의 총 비용(시간·요금·위험)을 결정한다.

## 7. Docker 레이어 캐시와 아티팩트 전달

컨테이너 빌드는 CI 에서 특히 느린 단계다. `docker/build-push-action` 은 BuildKit 의 캐시 내보내기를 지원해 레이어를 job 간에 재사용한다. GitHub Actions 전용 캐시 백엔드(`type=gha`)를 쓰는 것이 표준이다.

```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v6
  with:
    context: .
    push: false
    tags: myapp:ci
    cache-from: type=gha           # 이전 빌드의 레이어 캐시를 복원
    cache-to: type=gha,mode=max    # 모든 중간 레이어를 캐시에 저장
```

`mode=max` 는 최종 이미지뿐 아니라 멀티스테이지의 중간 레이어까지 캐싱해 적중률을 높인다. 대신 캐시 용량을 더 쓰므로, 앞서 말한 10GB 한도와의 균형을 봐야 한다. Dockerfile 은 자주 바뀌는 레이어(소스 복사·빌드)를 뒤로, 거의 안 바뀌는 레이어(의존성 설치)를 앞으로 배치해야 캐시 무효화 범위가 최소화된다.

job 간에 **빌드 산출물을 전달**할 때는 캐시가 아니라 아티팩트를 쓴다. 캐시는 "다음 실행을 위한 재사용"이고, 아티팩트는 "같은 실행 내 job 간 전달·다운로드 보존"으로 목적이 다르다.

```yaml
  build:
    steps:
      - run: ./gradlew bootJar
      - uses: actions/upload-artifact@v4
        with:
          name: app-jar
          path: build/libs/*.jar
          retention-days: 7
  deploy:
    needs: build
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: app-jar
```

`needs: build` 로 의존성을 걸어 순서를 보장하고, 산출물을 아티팩트로 넘긴다. 정리하면 캐시는 실행 간 시간 절약, 아티팩트는 실행 내 job 간 데이터 전달, Docker 레이어 캐시는 컨테이너 빌드 가속이라는 세 도구를 목적에 맞게 구분해 쓰는 것이 파이프라인 설계의 기본기다.

## 참고

- GitHub Docs: Caching dependencies to speed up workflows
- GitHub Docs: Using a matrix for your jobs / Reusing workflows
- GitHub Docs: Creating a composite action
- GitHub Docs: About security hardening with OpenID Connect
