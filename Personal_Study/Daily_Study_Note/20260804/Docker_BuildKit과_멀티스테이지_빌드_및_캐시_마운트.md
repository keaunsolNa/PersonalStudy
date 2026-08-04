Notion 원본: https://app.notion.com/p/3b25a06fd6d3814aa5c2d6015ccf9153

# Docker BuildKit과 멀티스테이지 빌드 및 캐시 마운트

> 2026-08-04 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- BuildKit 의 DAG 기반 병렬 빌드가 레거시 빌더와 어떻게 다른지 구분한다
- 멀티스테이지 빌드로 최종 이미지 크기와 공격 표면을 줄이는 패턴을 익힌다
- cache mount·bind mount·secret mount 로 빌드 캐시와 비밀을 안전하게 다룬다
- 레지스트리 캐시 export/import 로 CI 러너 간 캐시를 공유해 빌드 시간을 단축한다

## 1. BuildKit — 순차 빌더에서 DAG 빌더로

전통적인 Docker 빌더는 Dockerfile 의 명령을 **위에서 아래로 순차 실행**했다. 각 명령이 하나의 레이어를 만들고, 앞 명령이 끝나야 다음이 시작된다. BuildKit 은 이 모델을 버리고, 빌드를 **의존성 그래프(DAG, LLB: Low-Level Build)** 로 변환한다. 서로 의존하지 않는 단계는 병렬로 실행하고, 최종 이미지에 필요 없는 단계는 아예 건너뛴다.

Docker 23.0 부터 BuildKit 이 기본 빌더다. `docker build` 가 내부적으로 BuildKit 엔진을 호출한다. 명시적으로 켜려면 환경 변수를 쓴다.

```bash
DOCKER_BUILDKIT=1 docker build -t app:latest .
# 또는 확장 CLI
docker buildx build -t app:latest .
```

BuildKit 의 핵심 이점 세 가지다. 첫째, **병렬 실행** — 독립적인 멀티스테이지 단계가 동시에 빌드된다. 둘째, **불필요한 단계 스킵** — 최종 타깃이 참조하지 않는 스테이지는 실행되지 않는다. 셋째, **정교한 캐시 제어** — cache mount 등 새 마운트 타입으로 레이어 캐시를 넘어선 최적화가 가능하다.

## 2. 멀티스테이지 빌드 — 빌드 도구를 최종 이미지에서 배제

멀티스테이지 빌드는 하나의 Dockerfile 에 여러 `FROM` 을 두고, 앞 단계의 산출물만 뒤 단계로 복사한다. 컴파일러·빌드 도구·소스코드는 빌드 단계에만 있고 최종 런타임 이미지에는 실행 산출물만 남는다.

```dockerfile
# syntax=docker/dockerfile:1.7
# --- 1단계: 빌드 ---
FROM gradle:8.7-jdk21 AS build
WORKDIR /src
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
# 의존성만 먼저 받아 레이어 캐시 극대화
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle bootJar --no-daemon

# --- 2단계: 런타임 ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

빌드 단계의 `gradle` 이미지는 700MB 이상이지만, 최종 이미지는 JRE + jar 만 담아 200MB 안팎이다. JDK·Gradle·소스는 최종 이미지에 존재하지 않아 이미지 크기와 CVE 공격 표면이 모두 준다. `COPY --from=build` 로 특정 단계의 파일만 선택 복사하는 것이 핵심이다.

`syntax=docker/dockerfile:1.7` 첫 줄은 BuildKit 프론트엔드 버전을 지정해 cache mount·secret 같은 최신 문법을 활성화한다. 이 줄이 없으면 마운트 문법이 파싱 에러를 낸다.

## 3. 레이어 캐시 — COPY 순서가 캐시 효율을 좌우

Docker 는 각 명령의 결과를 레이어로 캐싱하고, 입력이 바뀌지 않으면 재사용한다. `COPY` 는 파일 내용의 체크섬으로 캐시 무효화를 판단한다. 여기서 **자주 바뀌는 것을 나중에 복사**하는 순서가 결정적이다.

위 Dockerfile 이 `build.gradle` 을 소스보다 먼저 복사하는 이유가 이것이다. 소스코드는 커밋마다 바뀌지만 의존성 정의는 드물게 바뀐다. 의존성 다운로드 레이어를 소스 복사보다 앞에 두면, 소스만 고쳐 다시 빌드할 때 의존성 레이어가 캐시에서 재사용되어 네트워크 다운로드를 건너뛴다.

```dockerfile
# ✗ 나쁜 순서 — 소스 한 줄만 바뀌어도 의존성을 다시 받는다
COPY . .
RUN gradle build

# ✓ 좋은 순서 — 의존성 정의가 그대로면 다운로드 캐시 재사용
COPY build.gradle settings.gradle ./
RUN gradle dependencies
COPY src ./src
RUN gradle bootJar
```

`.dockerignore` 로 빌드 컨텍스트에서 `.git`, `build/`, `node_modules/` 를 제외하는 것도 중요하다. 컨텍스트가 크면 데몬 전송이 느려지고, 불필요한 파일 변경이 `COPY . .` 캐시를 깨뜨린다.

## 4. cache mount — 패키지 매니저 캐시를 영속화

레이어 캐시는 명령 전체가 캐시 히트일 때만 재사용된다. 소스가 바뀌어 `RUN npm ci` 가 다시 실행되면, 이전에 받은 패키지 캐시까지 통째로 날아간다. **cache mount** 는 이 문제를 해결한다. 특정 디렉터리를 레이어와 분리된 영속 캐시로 마운트해, 명령이 다시 실행돼도 이전 다운로드를 재사용한다.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:20-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
# npm 캐시를 빌드 간 영속화 — 재빌드 시 다운로드 생략
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
RUN npm run build
```

`--mount=type=cache,target=/root/.npm` 은 npm 의 캐시 디렉터리를 BuildKit 이 관리하는 캐시에 연결한다. `package-lock.json` 이 바뀌어 `npm ci` 가 재실행돼도, 변경되지 않은 패키지는 캐시에서 즉시 복원된다. Gradle(`/home/gradle/.gradle`), Maven(`/root/.m2`), pip(`/root/.cache/pip`), apt(`/var/cache/apt`) 모두 같은 방식으로 가속한다.

```dockerfile
# apt 캐시 마운트 — Alpine 이 아닌 Debian 계열에서 유용
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends curl
```

`sharing=locked` 는 동시 빌드가 같은 캐시에 접근할 때 락으로 직렬화한다. apt 처럼 동시 쓰기가 위험한 캐시에 쓴다.

## 5. secret mount — 비밀을 레이어에 남기지 않기

빌드 중 프라이빗 레지스트리 토큰이나 SSH 키가 필요할 때, `ARG` 나 `ENV` 로 넘기면 이미지 히스토리에 영구히 남는다. `docker history` 나 레이어 추출로 노출된다. **secret mount** 는 비밀을 빌드 시점에만 파일로 마운트하고 레이어에 기록하지 않는다.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:20-slim
WORKDIR /app
COPY package.json package-lock.json ./
# NPM_TOKEN 을 빌드 중에만 노출, 이미지에 남지 않음
RUN --mount=type=secret,id=npmrc,target=/root/.npmrc \
    npm ci
```

```bash
# 빌드 시 비밀 주입 — 파일 또는 환경변수에서
docker buildx build \
  --secret id=npmrc,src=$HOME/.npmrc \
  -t app:latest .
```

마운트된 `/root/.npmrc` 는 `RUN` 명령 실행 동안만 존재하고, 명령이 끝나면 사라진다. 최종 이미지 어느 레이어에도 토큰이 남지 않는다. SSH 접근이 필요하면 `--mount=type=ssh` 로 호스트의 SSH agent 를 빌드에 전달할 수 있다.

## 6. 레지스트리 캐시 — CI 러너 간 캐시 공유

CI 는 매번 깨끗한 러너에서 빌드하므로 로컬 레이어 캐시가 없다. BuildKit 의 **캐시 export/import** 는 빌드 캐시를 레지스트리에 저장했다가 다음 빌드에서 가져와, 러너가 바뀌어도 캐시를 재사용한다.

```bash
# 캐시를 레지스트리에 함께 푸시 (inline: 이미지에 캐시 메타 포함)
docker buildx build \
  --cache-to   type=inline \
  --cache-from type=registry,ref=myrepo/app:buildcache \
  --tag        myrepo/app:latest \
  --push .

# 더 정교한 registry 캐시 (모든 중간 레이어 캐시, mode=max)
docker buildx build \
  --cache-to   type=registry,ref=myrepo/app:buildcache,mode=max \
  --cache-from type=registry,ref=myrepo/app:buildcache \
  --tag        myrepo/app:latest --push .
```

`mode=max` 는 최종 이미지에 포함되지 않는 중간 단계 캐시까지 모두 export 한다. 멀티스테이지의 빌드 단계 캐시를 보존해 캐시 적중률이 크게 오르지만, 캐시 이미지 용량과 push 시간이 늘어난다. `mode=min`(기본)은 최종 이미지 레이어만 캐싱해 가볍다. GitHub Actions 는 `type=gha` 로 액션 캐시 백엔드를 직접 쓸 수도 있다.

```yaml
# GitHub Actions 예시
- uses: docker/build-push-action@v6
  with:
    push: true
    tags: myrepo/app:latest
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

## 7. 이미지 최적화 검증

이미지 크기와 레이어 구성은 배포 전에 측정한다. `docker history` 로 레이어별 크기를, `dive` 같은 도구로 레이어에 낭비된 파일을 찾는다.

```bash
# 레이어별 크기 확인
docker image history app:latest --no-trunc

# 빌드 결과를 다른 태그와 비교
docker images app --format "{{.Tag}}\t{{.Size}}"

# distroless/alpine 런타임 베이스로 크기 축소 확인
# eclipse-temurin:21-jre        ~280MB
# eclipse-temurin:21-jre-alpine ~200MB
# gcr.io/distroless/java21      ~230MB (셸 없음, 공격 표면 최소)
```

distroless 이미지는 셸·패키지 매니저가 없어 공격 표면이 가장 작지만, 컨테이너 안에서 디버깅(`exec` 후 셸)이 불가능하다는 운영상 제약이 있다. 디버깅 편의와 보안을 저울질해 선택한다.

## 8. 멀티 플랫폼 빌드 — 한 번에 amd64·arm64

Apple Silicon 개발 머신과 x86 프로덕션 서버가 공존하는 환경에서는 이미지를 여러 CPU 아키텍처로 빌드해야 한다. `docker buildx` 는 QEMU 에뮬레이션이나 원격 빌더로 여러 플랫폼을 한 매니페스트에 묶는다.

```bash
# 멀티 플랫폼 빌더 생성 (한 번만)
docker buildx create --name multi --driver docker-container --use

# amd64·arm64 동시 빌드 후 매니페스트 리스트로 push
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --tag myrepo/app:latest \
  --push .
```

푸시된 이미지는 매니페스트 리스트(fat manifest)가 되어, `docker pull` 시 클라이언트가 자기 아키텍처에 맞는 이미지를 자동 선택한다. 주의할 점은 크로스 빌드 시 아키텍처별로 다른 베이스 이미지·네이티브 의존성이 필요할 수 있다는 것이다. `--platform=$BUILDPLATFORM` 과 `$TARGETARCH` 빌드 인자로 크로스 컴파일을 명시하면 QEMU 에뮬레이션의 느린 실행을 피할 수 있다.

```dockerfile
FROM --platform=$BUILDPLATFORM golang:1.22 AS build
ARG TARGETOS TARGETARCH
RUN GOOS=$TARGETOS GOARCH=$TARGETARCH go build -o /app ./cmd
```

Go·Rust 처럼 크로스 컴파일이 쉬운 언어는 빌드 단계를 네이티브(BUILDPLATFORM)로 두고 타깃 바이너리만 생성해, 에뮬레이션 없이 빠르게 멀티 플랫폼 이미지를 만든다.

## 9. trade-off 정리

멀티스테이지는 최종 이미지를 극적으로 줄이지만 Dockerfile 복잡도와 빌드 단계 수가 늘어 캐시 관리가 까다로워진다. cache mount 는 재빌드를 크게 앞당기지만 캐시가 오염되면(손상된 패키지 캐시) 원인 추적이 어려워, `--no-cache` 로 초기화하는 절차를 CI 에 마련해 둬야 한다. 레지스트리 캐시 `mode=max` 는 적중률을 높이지만 캐시 이미지 용량과 push/pull 시간이 늘어, 빌드가 자주 도는 프로젝트에서만 이득이 크다. distroless 는 보안에 유리하나 운영 디버깅을 포기하는 대가가 있다. 프로젝트의 빌드 빈도·보안 요구·팀의 디버깅 관행을 종합해 조합을 정한다.

## 참고

- Docker Documentation — Build with BuildKit, Multi-stage builds
- Dockerfile reference — RUN --mount (cache, secret, ssh, bind)
- docker/build-push-action — GitHub Actions caching backends
- Google Distroless — gcr.io/distroless container images (github.com/GoogleContainerTools/distroless)
