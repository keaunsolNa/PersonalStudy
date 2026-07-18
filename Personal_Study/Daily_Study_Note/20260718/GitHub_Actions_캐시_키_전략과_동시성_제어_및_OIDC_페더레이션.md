Notion 원본: https://www.notion.so/3a15a06fd6d3818ba32dc3b59438a085

# GitHub Actions 캐시 키 전략과 동시성 제어 및 OIDC 페더레이션

> 2026-07-18 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- actions/cache 의 immutable 저장 모델과 restore-keys 접두사 매칭을 근거로 rolling key 를 설계한다.
- 브랜치 스코프 격리가 캐시 적중률에 미치는 영향을 파악하고 default 브랜치 warm-up 워크플로를 구성한다.
- concurrency group 의 취소·큐잉 의미론을 구분해 PR 검증과 배포 잡에 각각 적용한다.
- OIDC 페더레이션의 JWT sub 클레임 구조를 이해하고 탈취 불가능한 IAM 신뢰 정책을 작성한다.

## 1. actions/cache 의 동작 모델: immutable 이 출발점이다

`actions/cache` 의 실제 동작은 GitHub 이 리포지터리마다 관리하는 **키-밸류 blob 저장소**에 tar 아카이브를 올리고 내리는 것에 가깝다. 여기엔 결정적인 제약이 하나 있다. **한 번 저장된 키는 덮어쓸 수 없다.** 같은 키로 다시 저장하려 하면 액션은 `Cache already exists` 경고를 남기고 조용히 넘어간다. 실패가 아니라 경고이므로 워크플로는 초록불로 끝나고, 개발자는 캐시가 갱신됐다고 착각한다.

잡 시작 시 `restore` 단계가 `key` 로 **완전 일치(exact match)** 조회를 한다. 없으면 `restore-keys` 를 위에서부터 훑으며 **접두사(prefix) 매칭**을 시도하고, 여럿이 걸리면 가장 최근 것을 고른다. 이때 파일은 복원되지만 `cache-hit` 은 **`false`** 다. 첫 번째 함정이 여기 있다.

```yaml
# 위험한 패턴 — partial hit 인데 의존성 해석을 건너뛴다
- if: steps.gradle-cache.outputs.cache-hit != 'true'
  run: ./gradlew dependencies
```

`cache-hit != 'true'` 는 "완전 일치가 아니었다"는 뜻이지 "아무것도 복원 안 됐다"는 뜻이 아니다. partial hit 을 완전 hit 으로 오해하면 오래된 의존성 트리 위에서 빌드가 돈다. 정확히 구분하려면 `cache-matched-key` output 을 `key` 와 비교해야 한다.

그리고 저장은 잡이 **성공적으로 끝났을 때만** post 단계에서 일어난다. 플레이키한 테스트가 있는 리포에서 "캐시가 왜 계속 miss 나지?"의 절반이 여기서 온다. 항상 저장하려면 `actions/cache/save` 를 `if: always()` 로 분리해야 한다.

| 상황 | 파일 복원 | `cache-hit` | post 단계 저장 |
|---|---|---|---|
| key 완전 일치 | O | `true` | 스킵 (이미 존재) |
| restore-keys 접두사 매칭 | O | `false` | 새 key 로 저장 |
| 아무것도 매칭 안 됨 | X | `false` | 새 key 로 저장 |
| 잡 실패 | 복원은 됨 | - | **저장 안 함** |

trade-off: immutable 모델은 캐시 무결성을 공짜로 준다. 동시에 도는 두 잡이 같은 키로 다른 내용을 밀어 넣어 아카이브가 깨지는 일이 없다. 대가는 "갱신"이라는 개념이 없다는 것이고, 그래서 모든 전략은 **키를 계속 새로 만드는** 방향으로 수렴한다.

## 2. rolling key 패턴과 hashFiles 의 실제 동작

갱신 대신 새 키를 만든다. 이게 rolling key 다. 키 안에 내용물의 지문을 박아 넣고 내용물이 바뀌면 자동으로 새 키가 되게 한다. miss 가 나더라도 restore-keys 로 직전 세대를 끌어와 증분만 갱신한다.

```yaml
key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', 'gradle/libs.versions.toml') }}
restore-keys: gradle-${{ runner.os }}-
```

`hashFiles` 는 glob(`GITHUB_WORKSPACE` 기준 상대경로)에 매칭된 파일들 각각의 SHA-256 을 구한 뒤 그 해시들을 다시 해싱해 단일 문자열을 반환한다. 매칭되는 파일이 하나도 없으면 **빈 문자열**을 돌려준다. 이게 두 번째 함정이다. 오타로 `**/*.gradl*` 이라고 썼다면 키는 `gradle-Linux-` 가 되고, 최초 실행에서 저장된 뒤 immutable 이라 **영원히 갱신되지 않는다.** 키에 해시가 실제로 박혔는지 로그에서 한 번은 확인해야 한다.

**lockfile 이 없는 프로젝트**가 진짜 문제다. Gradle 은 기본적으로 lockfile 을 만들지 않는다. 버전을 BOM 에 위임하거나 `2.7.+` 같은 동적 버전을 쓰면 `build.gradle` 은 그대로인데 실제 해석되는 의존성은 바뀔 수 있다. `hashFiles` 는 변하지 않고 캐시는 옛날 jar 를 계속 복원한다. 해결책은 셋이다. (1) `dependencyLocking` 을 켜서 `gradle.lockfile` 을 생성하고 그걸 해싱한다. (2) Version Catalog 로 버전을 고정하고 해시 대상에 포함한다. (3) 키에 `$(date -u +%Y-%V)` 같은 주차 값을 넣어 강제로 세대를 넘긴다 — 주 1회 cold build 를 감수하는 대신 무한히 낡은 캐시가 생기지 않는다. 같은 맥락에서 키에 수동 버전 접두사(`v3-gradle-...`)를 두면 커밋 한 줄로 캐시를 통째로 무효화할 수 있다.

## 3. 캐시 스코프와 브랜치 격리

캐시는 리포 전역이 아니라 **브랜치 단위로 격리**된다. 잡은 자기 브랜치에서 만들어진 캐시와, 그 브랜치의 **base 브랜치 및 default 브랜치**에서 만들어진 캐시를 읽을 수 있다. 형제 브랜치의 캐시는 읽지 못한다. `feature/a` 에서 만든 캐시를 `feature/b` 가 쓰는 일은 없다.

PR 은 `refs/pull/<n>/merge` 라는 별도 ref 스코프를 가지고, PR 워크플로가 만든 캐시는 그 PR 안에서만 재사용된다. 보안상 필연적인 설계다. 브랜치 간 캐시 공유가 자유롭다면 아무나 PR 을 열어 `~/.gradle/caches` 에 백도어가 심긴 jar 를 캐시에 올린 뒤 그게 main 빌드에 복원되기를 기다리면 된다.

부작용은 명확하다. **첫 PR 빌드는 거의 항상 cold 다.** 그래서 default 브랜치에서 캐시를 미리 데워두는(warm) 워크플로가 필요하다.

```yaml
name: Warm caches
on:
  push: { branches: [master] }
  schedule:
    - cron: '0 18 * * *'   # UTC, evict 타이머 리셋용
jobs:
  warm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/cache@v4
        with:
          path: ~/.gradle/caches/modules-2
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*') }}
          restore-keys: gradle-${{ runner.os }}-
      - run: ./gradlew --no-daemon build -x test
```

이 워크플로가 master 에 캐시를 얹어두면 이후 모든 PR 브랜치가 그 캐시를 읽는다. PR 브랜치가 새로 저장하는 캐시는 PR 스코프에 갇히므로, 결과적으로 **master 는 캐시 공급자, PR 은 소비자**라는 단방향 흐름이 만들어진다.

스케줄 트리거를 같이 두는 이유는 evict 방어다. GitHub 은 **7일 동안 접근되지 않은 캐시 항목을 삭제**한다. 활동이 뜸한 리포는 월요일마다 cold build 를 하게 되는데, 야간 스케줄이 타이머를 리셋한다(복원만 해도 접근으로 간주된다). 또한 리포당 캐시 저장소에는 **10GB 상한**이 있고, 초과하면 오래된 것부터 LRU 로 축출된다.

## 4. Gradle/Maven 캐시의 실전과 손익분기

`~/.gradle` 을 통째로 캐싱하는 것은 흔한 실수다. 이 디렉터리에는 의존성 jar 뿐 아니라 daemon 로그, 파일 해시 DB, 락 파일, native 바이너리가 섞여 있어 수 GB 로 부풀고 압축·업로드에만 몇 분이 든다. 게다가 daemon 락 파일(`*.lock`, `journal-1/`)이 섞여 들어가면 복원 후 Gradle 이 락 상태를 오해해 이상하게 느려지거나 멈추는 사례가 생긴다.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches/modules-2
      ~/.gradle/caches/jars-9
      ~/.gradle/wrapper/dists
    key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*') }}
    restore-keys: gradle-${{ runner.os }}-
```

`modules-2` 가 실제 의존성 저장소, `jars-9` 는 instrumented jar, `wrapper/dists` 는 Gradle 배포판이다. 이 셋만 담으면 크기가 줄고 락 파일도 배제된다. CI 에서는 `--no-daemon` 을 붙여 daemon 상태가 캐시에 남지 않게 하는 편이 안전하다. Maven 의 `~/.m2/repository` 는 경로가 단순한 대신 SNAPSHOT 이 문제다. SNAPSHOT jar 는 같은 좌표로 내용이 바뀌므로 캐시가 옛 것을 고정시킨다. 저장 전에 해당 항목을 지우거나 `-U` 로 강제 갱신을 걸어야 한다.

`gradle/actions/setup-gradle` 같은 전용 액션은 캐시 대상을 Gradle 내부 구조 이해를 바탕으로 선별하고, default 브랜치에서만 캐시를 쓰는 write 정책과 미사용 항목 정리를 제공한다. 직접 `actions/cache` 를 쓰면 경로를 통제하는 대신 캐시 레이아웃 변경을 따라가야 한다. 멀티 모듈 프로젝트라면 전용 액션 쪽이 유지보수 비용이 낮다.

**손익분기**를 잊지 말아야 한다. **압축 해제 + 다운로드 시간 > 캐시 없이 의존성 받는 시간**이면 캐시는 순손실이다. 의존성이 수백 MB 인 평범한 Spring Boot 앱에서는 대체로 이득이지만, 수 GB 짜리 `~/.gradle` 전체 캐시는 종종 손해다. 캐시 스텝 소요 시간을 캐시를 끈 빌드와 비교하면 된다. 추측하지 말고 측정한다.

## 5. Docker 레이어 캐시: type=gha 와 type=registry

`docker/build-push-action` 은 BuildKit 의 캐시 익스포터를 그대로 노출한다.

```yaml
- uses: docker/build-push-action@v6
  with:
    context: .
    tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

`type=gha` 는 앞서 본 그 캐시 저장소를 백엔드로 쓴다. 즉 **10GB 상한, 7일 evict, 브랜치 스코프 격리를 그대로 상속한다.** `mode=max` 는 중간 스테이지 레이어까지 전부 내보내므로 멀티 스테이지 빌드에서 캐시 양이 폭증한다. Gradle 캐시와 Docker 레이어 캐시를 같은 10GB 안에서 경쟁시키면 LRU 축출이 서로를 갉아먹어 둘 다 적중률이 떨어진다. 에러는 안 뜨고 그냥 빌드가 느려질 뿐이라 원인 파악이 어렵다.

레지스트리 캐시는 이 제약에서 벗어난다.

```yaml
    cache-from: type=registry,ref=ghcr.io/${{ github.repository }}:buildcache
    cache-to: type=registry,ref=ghcr.io/${{ github.repository }}:buildcache,mode=max
```

캐시 매니페스트를 레지스트리에 이미지처럼 저장한다. GHA 쿼터와 무관하고 브랜치 격리도 없어 self-hosted runner 나 다른 CI 에서도 같은 캐시를 쓸 수 있다. 대신 격리가 없다는 건 곧 **오염 위험**이다. 포크 PR 이 캐시 ref 에 쓰지 못하도록 push 권한을 통제하고 신뢰 경계를 스스로 그어야 하며, 스토리지 비용과 GC 도 직접 관리해야 한다.

| 항목 | `type=gha` | `type=registry` |
|---|---|---|
| 크기 제약 | 리포 10GB 공유 | 레지스트리 정책에 따름 |
| 브랜치 격리 | 있음 (자동) | 없음 (직접 설계) |
| evict | 7일 미접근 / LRU | 직접 GC |
| 러너 간 공유 | GitHub 호스티드 한정 | self-hosted 포함 가능 |

실무 판단: 이미지가 작고 PR 검증이 주 목적이면 `type=gha` 로 충분하고, 이미지가 크거나 10GB 쿼터가 빡빡하면 registry 로 옮긴다. 다만 Dockerfile 을 먼저 손보는 게 캐시보다 효과가 큰 경우가 많다. 의존성 해석 레이어와 소스 복사 레이어를 분리하지 않으면 어떤 백엔드도 구원해주지 못한다.

## 6. concurrency: 취소와 큐잉의 정확한 의미론

`concurrency` 는 같은 group 이름을 가진 워크플로/잡이 동시에 하나만 돌게 만든다. `cancel-in-progress: true` 는 새 실행이 들어오면 **진행 중인 실행을 취소**한다. `false`(기본값)면 새 실행이 **대기(pending)** 한다. 놓치기 쉬운 점은 대기열 길이가 1이라는 것이다. 이미 하나가 대기 중인데 또 들어오면 **기존 대기 중 실행이 취소되고 새 것이 그 자리를 차지한다.** `cancel-in-progress: false` 를 "모든 커밋이 순서대로 배포된다"는 뜻으로 이해하면 안 된다. 배포 큐가 아니라 **최신값 유지 슬롯**에 가깝다.

group 키를 짓는 법이 관건이다. `github.ref` 는 push 에서는 `refs/heads/feature-x`, PR 이벤트에서는 `refs/pull/123/merge` 다. `github.head_ref` 는 PR 에서만 소스 브랜치명을 갖고 push 에서는 빈 값이라 `github.head_ref || github.ref` 관용구가 쓰인다. PR 과 그 브랜치의 push 를 **같은 group** 으로 묶어 중복 실행이 붙는 걸 막는 것이다. `github.workflow` 를 넣는 건 서로 다른 워크플로가 같은 group 을 공유해 엉뚱하게 서로를 취소하는 사고를 막기 위해서다.

```yaml
# PR 검증 — 취소해도 무해, 러너 시간 절약
concurrency:
  group: pr-${{ github.workflow }}-${{ github.head_ref || github.ref }}
  cancel-in-progress: true

# 프로덕션 배포 — 절대 취소하면 안 됨
concurrency:
  group: deploy-production
  cancel-in-progress: false
```

배포에서 `cancel-in-progress: true` 는 재앙이다. `kubectl apply` 중간에 잡이 죽으면 클러스터가 어중간한 상태로 남고, terraform 이라면 state lock 이 걸린 채 방치된다. 배포는 **원자적이어야 하며 중단 가능하지 않다.** 그래서 `false` 로 두고 큐잉시킨다. 단 대기 슬롯이 1개뿐이라 커밋을 세 번 연달아 밀면 마지막 커밋만 배포된다. 프로덕션에서는 이게 오히려 합리적이지만(어차피 최신 상태로 수렴), 각 커밋이 반드시 배포되어야 한다면 별도 배포 큐가 필요하다.

group 키에서 ref 를 아예 빼면 서로 다른 브랜치의 배포까지 직렬화된다. 반대로 `group: deploy-${{ github.ref }}` 는 브랜치마다 병렬 배포를 허용하므로 staging 여러 개를 굴릴 때만 적합하다. 그리고 concurrency 로 취소된 실행은 **성공이 아니므로 캐시가 저장되지 않는다.** 1번 섹션의 캐시 miss 미스터리와 연결되는 지점이다.

## 7. OIDC 페더레이션: 장기 키를 없애는 원리

`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` 를 리포 시크릿에 박아두는 방식은 세 가지 문제가 있다. 유출되면 무기한 유효하고, 로테이션이 수동이며, 어떤 워크플로가 어떤 키를 썼는지 CloudTrail 에서 구분되지 않는다. OIDC 는 이걸 **단명 토큰 교환**으로 대체한다.

흐름은 이렇다. (1) 잡에 `id-token: write` 권한을 주면 러너 안에 `ACTIONS_ID_TOKEN_REQUEST_URL` 과 `ACTIONS_ID_TOKEN_REQUEST_TOKEN` 이 주입된다. (2) 액션이 이 엔드포인트를 호출해 GitHub OIDC provider(`token.actions.githubusercontent.com`)가 서명한 **JWT** 를 받는다. 어떤 리포의 어떤 ref 에서 어떤 이벤트로 실행 중인지가 클레임으로 박혀 있다. (3) 이 JWT 를 AWS STS 의 `AssumeRoleWithWebIdentity` 에 제출한다. (4) AWS 는 GitHub 의 JWKS 로 서명을 검증하고 IAM 신뢰 정책의 Condition 과 클레임을 대조한 뒤, 통과하면 **단명 STS 자격증명**을 돌려준다.

```yaml
permissions:
  id-token: write     # OIDC 토큰 발급에 필수
  contents: read      # 명시하면 나머지 권한은 자동으로 none
jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy-prod
          aws-region: ap-northeast-2
```

`permissions:` 블록을 하나라도 쓰면 명시하지 않은 나머지 스코프는 전부 `none` 이 된다. `id-token: write` 만 적고 `contents: read` 를 빠뜨리면 checkout 이 실패한다. JWT 의 핵심은 `sub` 클레임이고, 구조는 실행 맥락에 따라 달라진다.

| 실행 맥락 | `sub` 값 |
|---|---|
| master 브랜치 push | `repo:org/repo:ref:refs/heads/master` |
| pull_request 이벤트 | `repo:org/repo:pull_request` |
| environment 지정 잡 | `repo:org/repo:environment:production` |

**`environment:` 를 잡에 지정하면 `sub` 가 environment 형태로 바뀌어 ref 정보가 사라진다.** 이걸 모르면 ref 기반 Condition 을 걸어놓고 environment 를 추가한 순간 `AccessDenied` 가 나서 한참 헤맨다. AWS 쪽에서는 IAM OIDC identity provider 에 GitHub 을 한 번 등록해야 한다. `aud`(audience)는 `configure-aws-credentials` 기본값으로 `sts.amazonaws.com` 이다.

```json
{
  "Effect": "Allow",
  "Principal": {
    "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  },
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": {
    "StringEquals": {
      "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
      "token.actions.githubusercontent.com:sub": "repo:org/repo:environment:production"
    }
  }
}
```

`StringEquals` 로 `sub` 를 정확히 못 박고 `aud` 도 반드시 함께 검증한다. 이 역할은 production environment 에서 도는 잡만 assume 할 수 있다. 다른 리포도, 포크도 불가능하다.

## 8. OIDC 보안 함정: 와일드카드 하나가 계정을 연다

블로그를 따라 하다 보면 이런 Condition 을 보게 된다.

```json
"StringLike": { "token.actions.githubusercontent.com:sub": "repo:my-org/*" }
```

동작은 한다. 그리고 위험하다. 이 조건은 **조직 내 아무 리포의 아무 브랜치, 아무 PR** 이 이 역할을 assume 할 수 있다는 뜻이다. 리포가 50개 있고 그중 하나가 실험용이라면, 거기서 워크플로 하나 만들어 프로덕션 배포 역할을 가져갈 수 있다. 리포 하나로 좁힌 `repo:my-org/my-repo:*` 도 **그 리포의 임의 브랜치**를 허용한다. push 권한이 있는 사람 누구나 브랜치를 파고 워크플로를 커밋해 프로덕션 자격증명을 얻는다. 워크플로 파일 자체가 그 브랜치에서 정의되므로 코드 리뷰도 브랜치 보호도 우회된다. `:pull_request` 를 허용하면 더 나쁘다. 포크 PR 은 `id-token: write` 를 받지 못하지만 내부 협업자의 PR 은 받고, 워크플로를 수정해 올리면 리뷰 전에 실행된다. `sub` 만 보고 `aud` 를 빼는 것도 흔한 실수다. 두 조건은 항상 쌍으로 간다.

안전한 설계는 **environment 로 승인 게이트를 거는 것**에서 시작한다. GitHub environment 에 required reviewer 와 deployment branch 제한을 걸면, 그 environment 를 쓰는 잡은 승인 없이는 시작조차 못 하고 지정된 브랜치에서만 돈다. 신뢰 정책은 `environment:production` 을 `StringEquals` 로 못 박는다. 이러면 "역할 사용 = environment 통과 = 사람 승인 + 브랜치 제한"이 강제된다.

`pull_request_target` 은 별개의 지뢰다. 이 트리거는 **base 브랜치의 워크플로 정의**로 실행되면서 시크릿과 쓰기 가능한 `GITHUB_TOKEN` 을 받는다. 여기서 PR 의 head 코드를 checkout 해 빌드하면 포크의 임의 코드가 시크릿이 있는 컨텍스트에서 실행된다. `build.gradle` 하나면 충분하다. 이 트리거는 라벨 붙이기 같은 메타데이터 작업에만 쓰고, 포크 코드 검증은 `pull_request` 로 분리한다.

## 9. 진단과 운영

캐시 문제는 로그만 봐서는 안 보인다. `gh` CLI 로 실제 상태를 확인한다.

```bash
gh cache list --limit 100 --sort size_in_bytes --order desc
gh cache delete gradle-Linux-abc123   # immutable 을 우회하는 유일한 방법
gh cache delete --all
```

출력의 **ref** 컬럼을 보면 어떤 브랜치가 캐시를 만들고 있는지 드러난다. PR ref(`refs/pull/*/merge`)가 목록의 대부분을 차지한다면, PR 마다 수백 MB 씩 저장하면서 정작 서로 공유하지 못해 10GB 를 낭비하는 중이다. 이때는 PR 워크플로를 `actions/cache/restore` 만 쓰는 읽기 전용으로 바꾸고 저장은 default 브랜치에만 맡기는 게 정석이다. 저장소 압박이 줄고, 부수적으로 **캐시 오염 경로도 막힌다.** PR 이 캐시에 아무것도 쓸 수 없으면 poisoning 이 성립하지 않는다.

**self-hosted runner** 는 계산이 완전히 다르다. 러너가 영속적이면 `~/.gradle` 이 잡 사이에 남아 있어 `actions/cache` 는 순수 오버헤드가 된다. 반대로 러너를 여러 잡이 공유하면 캐시 디렉터리 동시 접근으로 락 경합이 생기고, 잡마다 다른 프로젝트가 돌면 캐시가 서로를 오염시킨다. 잡마다 컨테이너로 격리해 볼륨으로 캐시를 공유하거나, ephemeral runner 를 쓰면서 `actions/cache` 를 정상적으로 쓰거나 둘 중 하나로 가야 한다. 어중간한 조합은 두 방식의 단점만 합친다.

운영 습관 하나. 원인 불명의 빌드 이상이 생기면 가장 먼저 키의 버전 접두사를 올려본다. 캐시 무효화로 사라지는 문제라면 원인 범위가 몇 분 만에 좁혀지고, 그대로면 캐시는 용의선상에서 제외된다. 디버깅에서 가장 비싼 건 배제할 수 없는 가설이다.

## 참고

- GitHub Docs — Caching dependencies to speed up workflows (https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows)
- GitHub Docs — Using concurrency (https://docs.github.com/en/actions/using-jobs/using-concurrency)
- GitHub Docs — About security hardening with OpenID Connect (https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/about-security-hardening-with-openid-connect)
- GitHub Docs — Configuring OpenID Connect in AWS (https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services)
- Docker Docs — Cache storage backends (https://docs.docker.com/build/cache/backends/)
