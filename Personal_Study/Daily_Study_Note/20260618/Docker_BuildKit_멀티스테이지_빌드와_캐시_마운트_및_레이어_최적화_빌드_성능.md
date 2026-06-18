Notion 원본: https://www.notion.so/3835a06fd6d3819fa1a3f80dc3f0ac44

# Docker BuildKit 멀티스테이지 빌드와 캐시 마운트 및 레이어 최적화 빌드 성능

> 2026-06-18 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- BuildKit 의 DAG 기반 병렬 빌드와 콘텐츠 주소(content-addressable) 캐시 모델을 설명한다
- 멀티스테이지로 빌드/런타임을 분리해 최종 이미지를 최소화한다
- `RUN --mount=type=cache`·`type=secret`·`type=bind` 의 용도와 캐시 무효화 규칙을 코드로 적용한다
- registry cache export/import 로 CI 빌드 캐시를 노드 간 공유한다

## 1. 레거시 빌더 vs BuildKit

BuildKit(Docker 23.0+ 기본)은 Dockerfile 을 의존성 그래프(LLB)로 컴파일해 독립 스테이지를 병렬 실행하고 캐시를 콘텐츠 해시로 관리한다.

```dockerfile
# syntax=docker/dockerfile:1.7
```

이 지시어가 최신 frontend(cache/secret 마운트)를 활성화한다.

## 2. 멀티스테이지 빌드로 런타임 이미지 최소화

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
```

최종 이미지에는 JRE 와 jar 만 남는다.

## 3. 레이어 캐시와 명령 순서

한 레이어가 무효화되면 그 아래 모두 재빌드된다. 자주 바뀌는 것을 아래로 배치한다.

```dockerfile
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
```

`.dockerignore` 로 `node_modules`, `.git` 를 제외한다.

## 4. RUN --mount=type=cache

```dockerfile
RUN --mount=type=cache,target=/root/.npm npm ci
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked apt-get update && apt-get install -y curl
```

`sharing=locked` 는 배타적 접근(apt lock), `shared`(기본)는 동시 접근, `private` 는 빌드마다 새 캐시다.

## 5. type=secret 과 type=bind 마운트

```dockerfile
RUN --mount=type=secret,id=npm_token NPM_TOKEN=$(cat /run/secrets/npm_token) npm ci
```

```bash
docker buildx build --secret id=npm_token,src=$HOME/.npm_token .
```

비밀을 레이어에 굳히지 않아 `docker history` 유출을 막는다.

## 6. CI 에서 registry 캐시 공유

```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v6
  with:
    context: .
    push: true
    tags: ghcr.io/keaunsolna/app:latest
    cache-from: type=registry,ref=ghcr.io/keaunsolna/app:buildcache
    cache-to: type=registry,ref=ghcr.io/keaunsolna/app:buildcache,mode=max
```

`mode=max` 는 중간 스테이지까지 내보내 적중률을 극대화한다.

| 캐시 백엔드 | 공유 범위 | 비고 |
|---|---|---|
| inline | 이미지 동봉 | mode=min 만 |
| registry | 레지스트리 전체 | mode=max 지원 |
| gha | 같은 리포 액션 | 용량 한도 |

## 7. 빌드 그래프 디버깅과 캐시 적중 측정

```bash
DOCKER_BUILDKIT=1 docker build --progress=plain -t app .
docker buildx du
docker buildx prune --filter until=168h
```

`ARG` 는 선언 위치 아래 모든 레이어 캐시 키에 영향을 주므로 자주 바뀌는 값은 빌드 막바지에 배치한다. deps 스테이지를 분리해 소스 변경 시에도 node_modules 설치를 재사용한다.

## 8. 이미지 슬림화와 운영 트레이드오프

distroless 는 공격 표면이 작지만 디버깅이 어렵고, Alpine 은 musl libc 라 glibc 전제 바이너리와 호환 문제가 생긴다. 멀티스테이지로 빌드 도구를 떼는 것만으로 수백 MB 를 줄인다. non-root 실행, 멀티아키 빌드, SBOM/provenance 생성이 최신 표준이다.

## 참고

- Docker 공식 — BuildKit (https://docs.docker.com/build/buildkit/)
- Dockerfile reference — RUN --mount (https://docs.docker.com/reference/dockerfile/#run---mount)
- Docker 공식 — Multi-stage builds (https://docs.docker.com/build/building/multi-stage/)
- docker/build-push-action — Cache (https://docs.docker.com/build/ci/github-actions/cache/)
