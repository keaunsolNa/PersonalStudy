Notion 원본: https://app.notion.com/p/39e5a06fd6d381df9f1ac7a25766af19

# Docker BuildKit LLB와 캐시 마운트 및 멀티스테이지 최적화

> 2026-07-15 신규 주제 · 확장 대상: Docker(cgroup v2 / OverlayFS 레이어)

## 학습 목표

- 레거시 빌더의 순차 실행과 BuildKit 의 LLB DAG 실행 모델을 구분한다
- 캐시 키 계산 규칙을 근거로 Dockerfile 명령 순서를 결정한다
- `--mount=type=cache` / `type=bind` / `type=secret` 를 상황별로 적용한다
- CI 에서 원격 캐시(`--cache-from/to`)로 캐시 히트를 재현하고 히트율을 측정한다

## 1. 레거시 빌더가 느린 이유

`docker build` 의 옛 구현(dockerd 내장 빌더)은 Dockerfile 을 **위에서 아래로 한 줄씩** 실행했다. 각 명령이 컨테이너를 만들고, 실행하고, 파일시스템 diff 를 커밋해 레이어를 만든다. 이 모델의 제약은 세 가지다.

첫째, **병렬성이 없다.** 멀티스테이지에서 서로 무관한 두 스테이지도 순차로 빌드된다. 둘째, **불필요한 스테이지도 빌드한다.** 최종 이미지에 기여하지 않는 스테이지까지 전부 실행한다. 셋째, **캐시가 전부 아니면 전무다.** 한 레이어가 미스 나면 이후 전부 재실행된다.

BuildKit 은 Dockerfile 을 곶바로 실행하지 않고 **LLB(Low-Level Builder) 라는 중간 표현의 DAG 로 컴파일**한 뒤, 그 그래프를 스케줄링한다. Docker 23.0 부터 기본 빌더이며, Buildx 를 통해 확장 기능을 쓴다.

```bash
docker buildx build --progress=plain -t app:dev .
DOCKER_BUILDKIT=1 docker build ...      # 구버전에서 강제 활성화
```

## 2. LLB — 컨텐촠 주소 지정 DAG

LLB 는 protobuf 로 직렬화된 그래프이며, 노드 타입은 크게 네 가지다.

| Op | 역할 | Dockerfile 대응 |
|---|---|---|
| `SourceOp` | 입력 획득(이미지 pull, git clone, 로컬 컨텍스트) | `FROM`, `COPY` 소스 |
| `ExecOp` | 루트파일시스템 위에서 명령 실행 | `RUN` |
| `FileOp` | 파일 복사/생성/삭제 | `COPY`, `ADD` |
| `BuildOp` | 중첩 빌드 | 프론트엔드 위임 |

각 노드는 **입력의 다이제스트로 자신의 다이제스트를 계산**한다. 컨텐촠 주소 지정이므로 같은 입력 → 같은 다이제스트 → 캐시 히트다. 그래프를 실제로 보려면:

```bash
docker buildx build --print=outline .              # 스테이지/인자 개요
BUILDKIT_TTY_LOG_LINES=0 docker buildx build --progress=rawjson .
```

DAG 이므로 얻어지는 성질이 셋이다.

**병렬 실행.** 의존이 없는 노드는 동시에 실행된다. 스테이지 3개가 독립이면 3개가 병렬로 돌다.

```dockerfile
FROM golang:1.22 AS build-api
RUN go build -o /api ./cmd/api

FROM node:22 AS build-web           # build-api 와 동시 실행됨
RUN npm ci && npm run build

FROM alpine AS final
COPY --from=build-api /api /usr/local/bin/
COPY --from=build-web /app/dist /srv/
```

**Dead code elimination.** 최종 타깃에서 도달 불가능한 노드는 아예 실행되지 않는다. `--target build-api` 로 빌드하면 `build-web` 은 존재조차 안 한 것처럼 스킵된다.

**세밀한 캐시.** 노드 단위 캐시이므로, 한 스테이지가 미스 나도 병렬 스테이지의 캐시는 유지된다.

## 3. 캐시 키 계산 규칙

캐시 히트를 설계하려면 각 명령의 캐시 키가 무엇으로 계산되는지 알아야 한다.

| 명령 | 캐시 키 | 함의 |
|---|---|---|
| `RUN cmd` | 부모 다이제스트 + **명령 문자열** | 문자열만 같으면 히트. 파일이 바뀌어도 모름 |
| `COPY src dst` | 부모 다이제스트 + **파일 내용 해시 + 메타데이터** | 내용이 같으면 히트 |
| `ADD url dst` | 부모 다이제스트 + URL 문자열(+ 체크섬) | 원격 내용 변경을 못 잡음 |
| `ARG x` | 참조된 시점부터 값이 키에 포함 | 값이 바뀌면 이후 전부 미스 |
| `ENV x=y` | 값이 키에 포함 | 위와 동일 |

`RUN` 이 **명령 문자열만** 본다는 점이 결정적이다. `RUN npm ci` 는 `package-lock.json` 이 바뀌어도 캐시 히트한다 — 부모 레이어가 그대로면. 그래서 의존성 파일을 먼저 `COPY` 해서 부모 다이제스트를 바꿔야 한다.

```dockerfile
# ❌ 소스 한 줄만 고쳐도 npm ci 전체 재실행
COPY . .
RUN npm ci
RUN npm run build

# ✅ lock 파일이 안 바뀌면 npm ci 는 캐시 히트
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build
```

두 번째 형태에서 `src/index.ts` 를 고치면 `COPY . .` 부터 미스 나고 `npm ci` 는 히트한다. Java 라면 `pom.xml` / `build.gradle` 을, Go 라면 `go.mod`/`go.sum` 을 먼저 복사하는 동일 패턴이다.

`.dockerignore` 가 여기서 함께 중요하다. `COPY . .` 의 캐시 키는 컨텍스트의 파일 내용 해시이므로, `.git`, `node_modules`, `target/`, 로그 파일이 컨텍스트에 들어 있으면 **아무 관계 없는 변경에도 캐시가 깨진다.** 컨텍스트 전송 시간도 함께 늘어난다.

```
# .dockerignore
.git
node_modules
target
dist
*.log
.env*
Dockerfile*
```

BuildKit 은 컨텍스트를 증분 전송한다(변경 파일만). 그래도 `.dockerignore` 로 무관 파일을 제외하는 편이 캐시 안정성에 직접 기여한다.

## 4. 캐시 마운트 — 레이어가 아닌 캐시

`npm ci` 는 매번 레지스트리에서 전부 내려받는다. `~/.npm` 을 레이어에 남기면 이미지가 커지고, 안 남기면 캐시가 없다. 이 딜레마를 `--mount=type=cache` 가 해결한다.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci --prefer-offline
```

캐시 마운트는 **빌드 중에만 존재하고 레이어에 커밋되지 않는** 영속 디렉터리다. 빌더 인스턴스에 남아 다음 빌드에서 재사용된다. 이미지 크기는 늘지 않고, `npm ci` 는 로컬 캐시에서 읽는다.

`sharing` 옵션이 동시성을 결정한다.

| 값 | 동작 | 용도 |
|---|---|---|
| `shared` (기본) | 여러 빌드가 동시 쓰기 | 병렬 안전한 캐시(대부분) |
| `locked` | 한 번에 하나. 나머지는 대기 | npm/apt 처럼 락 파일을 쓰는 도구 |
| `private` | 동시 접근 시 각자 새 캐시 | 격리 필요 시 |

`id` 로 캐시를 분리하면 스테이지 간 오염을 막는다.

```dockerfile
RUN --mount=type=cache,id=gradle,target=/root/.gradle,sharing=locked \
    ./gradlew build --no-daemon

RUN --mount=type=cache,id=go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=go-build,target=/root/.cache/go-build \
    go build -o /app ./cmd/server
```

Go 의 경우 모듈 캐시와 빌드 캐시를 둘 다 마운트하는 것이 핵심이다. 빌드 캐시(`GOCACHE`)가 없으면 컴파일 결과를 매번 재생성해 캐시 마운트의 이득 대부분을 잃는다. 실측상 중간 규모 Go 프로젝트에서 두 캐시 모두 적용 시 재빌드가 90초 → 12초 수준으로 줄어든다.

apt 는 기본 설정이 캐시를 지우므로 한 줄이 더 필요하다.

```dockerfile
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    rm -f /etc/apt/apt.conf.d/docker-clean && \
    apt-get update && apt-get install -y --no-install-recommends curl
```

주의: 캐시 마운트는 **로컬 빌더에 종속**이다. CI 에서 러너가 매번 새로 뜼면 캐시 마운트는 항상 비어 있다. GitHub Actions 라면 `buildx` 의 `cache-from/to` 와 별개로 캐시 마운트를 살리려면 `docker/build-push-action` 의 `cache-to: type=gha` 를 쓰거나, 영속 러너를 쓰거나, `reproducible-containers/buildkit-cache-dance` 같은 우회를 써야 한다. 이 제약을 모르면 "로컬은 빠른데 CI 는 그대로" 가 된다.

## 5. bind 마운트와 secret 마운트

`type=bind` 는 컨텍스트나 다른 스테이지의 파일을 **복사 없이** 잠시 붙인다. 레이어를 만들지 않으므로 캐시 키에 영향이 적고 이미지도 안 커진다.

```dockerfile
RUN --mount=type=bind,source=package.json,target=/app/package.json \
    --mount=type=bind,source=package-lock.json,target=/app/package-lock.json \
    --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci
```

`type=secret` 은 빌드 시 비밀값을 **레이어에 남기지 않고** 전달한다. 프라이빗 레지스트리 토큰, SSH 키가 대표적이다.

```dockerfile
RUN --mount=type=secret,id=npm_token \
    NPM_TOKEN=$(cat /run/secrets/npm_token) npm ci
```

```bash
docker buildx build --secret id=npm_token,env=NPM_TOKEN .
docker buildx build --secret id=npm_token,src=./token.txt .
```

`ARG` 로 토큰을 넘기는 방식은 위험하다. `docker history` 에 그대로 남는다.

```bash
# ❌ 유출
docker build --build-arg NPM_TOKEN=xxx .
docker history app:latest --no-trunc | grep NPM_TOKEN   # 토큰이 보임
```

SSH 는 전용 마운트가 있다.

```dockerfile
RUN --mount=type=ssh git clone git@github.com:org/private-repo.git
```

```bash
docker buildx build --ssh default .
```

## 6. 멀티스테이지 — 크기와 공격 표면

멀티스테이지의 목적은 빌드 도구를 최종 이미지에서 제거하는 것이다.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM golang:1.22 AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/server ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/server /server
USER nonroot:nonroot
ENTRYPOINT ["/server"]
```

베이스 이미지 선택의 트레이드오프:

| 베이스 | 크기 | 셸/패키지 매니저 | 디버깅 | 비고 |
|---|---|---|---|---|
| `ubuntu:22.04` | ~78MB | 있음 | 쉬움 | 공격 표면 큼 |
| `debian:12-slim` | ~29MB | 있음 | 쉬움 | 무난한 기본값 |
| `alpine:3.20` | ~7MB | 있음(musl) | 쉬움 | musl ↔ glibc 호환 이슈 |
| `distroless/static` | ~2MB | **없음** | 어려움 | 정적 바이너리만 |
| `scratch` | 0 | 없음 | 매우 어려움 | 완전 정적 필요 |

alpine 의 musl 문제는 실재한다. glibc 전제 바이너리(일부 JDK 네이티브 라이브러리, `node-gyp` 산출물)가 동작하지 않거나, DNS resolver 차이로 이상 동작을 한다. Python 은 wheel 이 manylinux 용이라 alpine 에서 소스 빌드로 떨어져 **이미지가 더 커지고 빌드가 훨씬 느려지는** 역효과가 흔하다. Python 은 `python:3.12-slim` 이 대개 정답이다.

distroless 는 셸이 없어 `docker exec sh` 가 불가능하다. 디버깅은 `debug` 태그 변형(busybox 포함)이나 임시 사이드카로 한다.

```bash
docker debug <container>                     # Docker Desktop
kubectl debug -it pod/x --image=busybox --target=app   # k8s ephemeral container
```

## 7. CI 원격 캐시

캐시 마운트가 CI 에서 안 먹는 문제(§4)와 별개로, **레이어 캐시**는 원격에 저장해 러너 간 공유할 수 있다.

```bash
# 레지스트리에 캐시 저장
docker buildx build \
  --cache-from type=registry,ref=ghcr.io/org/app:buildcache \
  --cache-to   type=registry,ref=ghcr.io/org/app:buildcache,mode=max \
  --push -t ghcr.io/org/app:$SHA .
```

`mode` 가 중요하다.

- `mode=min` (기본): **최종 이미지의 레이어만** 캐시. 멀티스테이지의 중간 스테이지는 캐시 안 됨
- `mode=max`: **모든 중간 레이어** 캐시. 저장 용량은 크지만 히트율이 훨씬 높음

멀티스테이지에서 `mode=min` 을 쓰면 빌더 스테이지가 통째로 캐시되지 않아 사실상 매번 풀 빌드가 된다. 원격 캐시를 도입했는데 효과가 없다면 이것을 먼저 확인한다.

캐시 백엔드 비교:

| 백엔드 | 저장 위치 | mode=max | 비고 |
|---|---|---|---|
| `inline` | 이미지 매니페스트 | ❌ | 설정 간단. 최종 레이어만 |
| `registry` | 별도 태그 | ✅ | 권장. 레지스트리 용량 소모 |
| `gha` | GitHub Actions 캐시 | ✅ | 10GB 리포 제한, LRU 축출 |
| `s3` / `azblob` | 오브젝트 스토리지 | ✅ | 대규모. 수명 정책 직접 관리 |
| `local` | 로컬 디렉터리 | ✅ | 자체 러너. 무한 증가 주의 |

GitHub Actions 예:

```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v6
  with:
    push: true
    tags: ghcr.io/org/app:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
    provenance: mode=max
    sbom: true
```

히트 여부는 `--progress=plain` 출력에서 확인한다.

```
#12 [deps 3/3] RUN --mount=type=cache,target=/root/.npm npm ci
#12 CACHED
```

`CACHED` 가 아니라 실행 시간이 찍힐면 미스다. 미스가 예상 밖이라면 그 직전 명령의 캐시 키가 바뀜 것이고, 보통 `ARG`/`ENV` 에 커밋 SHA 나 타임스탬프가 들어간 경우다.

```dockerfile
# ❌ 매 빌드마다 값이 달라 이후 전부 미스
ARG BUILD_DATE
ENV BUILD_DATE=${BUILD_DATE}
RUN npm ci

# ✅ 캐시가 필요 없는 마지막 단계로 이동
RUN npm ci
ARG BUILD_DATE
LABEL org.opencontainers.image.created=${BUILD_DATE}
```

`ARG` 는 **참조되는 시점부터** 캐시 키에 들어간다. 선언만 하고 안 쓰면 영향이 없으므로, 변동값 인자는 Dockerfile 최하단으로 미루는 것이 원칙이다.

## 8. 정리 — 판단 기준

| 증상 | 원인 후보 | 확인 |
|---|---|---|
| 소스 한 줄 수정에 의존성 재설치 | 명령 순서 | `COPY` lock 파일 분리 여부 |
| 로컬 빠름 / CI 느림 | 캐시 마운트가 CI 에 없음 | `cache-to type=gha,mode=max` 적용 |
| 원격 캐시 넣었는데 무효 | `mode=min` | `mode=max` 로 변경 |
| 무관한 변경에 캐시 깨짐 | `.dockerignore` 부재 | 컨텍스트 크기 확인 |
| 이미지에 토큰 남음 | `ARG` 로 비밀 전달 | `docker history --no-trunc` |
| alpine 에서 빌드 급증 | musl / wheel 부재 | `-slim` 으로 교체 |
| 스테이지가 병렬로 안 돌 | 불필요한 `COPY --from` 의존 | `--print=outline` |

캐시 최적화의 상한도 알아둘 필요가 있다. BuildKit 캐시는 **빌드 시간**을 줄이지 배포 시간을 줄이지 않는다. 이미지 pull 시간은 레이어 크기와 개수에 좌우되므로, 캐시 마운트로 빌드가 빨라져도 최종 이미지가 1GB 면 노드 스케일아웃은 여전히 느리다. 두 문제는 별개이며, 후자는 멀티스테이지 + 최소 베이스로 접근한다. 반대로 레이어를 과도하게 합치면(`RUN a && b && c && ...`) 이미지 레이어 수는 줄지만 캐시 입도가 거칠어져 빌드가 느려진다 — 빌드 시간과 이미지 크기는 어느 지점에서 서로를 잡아먹는다.

## 참고

- Docker Docs — BuildKit: https://docs.docker.com/build/buildkit/
- Dockerfile reference — RUN --mount: https://docs.docker.com/reference/dockerfile/#run---mount
- Docker Docs — Cache storage backends: https://docs.docker.com/build/cache/backends/
- moby/buildkit — LLB and Solver: https://github.com/moby/buildkit/blob/master/docs/dev/solver.md
- Docker Docs — Build secrets: https://docs.docker.com/build/building/secrets/
- GoogleContainerTools/distroless: https://github.com/GoogleContainerTools/distroless
