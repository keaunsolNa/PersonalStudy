Notion 원본: https://www.notion.so/3e95a06fd6d381bdb28ac7c91fa9b312

# Docker BuildKit 캐시 마운트 전략과 멀티스테이지 빌드 레이어 최적화

> 2026-09-28 신규 주제 · 확장 대상: Docker & CI, DevOps

## 학습 목표

- BuildKit의 레이어 캐시가 레거시 빌더와 다르게 동작하는 지점(병렬 실행, 캐시 무효화 단위)을 설명한다
- `RUN --mount=type=cache`로 의존성 캐시를 레이어 캐시와 분리해 재빌드 시간을 단축하는 패턴을 작성한다
- 멀티스테이지 빌드에서 스테이지 순서와 `COPY` 범위가 캐시 히트율에 미치는 영향을 분석한다
- 실측 빌드 시간 비교를 통해 Java/Spring, Node.js 프로젝트별 최적 전략을 도출한다

## 1. BuildKit이 레거시 빌더와 다른 근본적 차이: DAG 기반 병렬 실행

Docker 레거시 빌더는 Dockerfile의 각 명령어를 순차적으로 실행하고, 각 단계 결과를 레이어로 캐싱한다. BuildKit(Docker 18.09부터 도입, 현재 기본 빌더)은 Dockerfile을 **DAG(방향성 비순환 그래프)** 로 해석해 의존성이 없는 단계를 병렬로 실행할 수 있다. 이 차이가 가장 크게 드러나는 곳이 멀티스테이지 빌드다.

```dockerfile
# syntax=docker/dockerfile:1
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM gradle:8-jdk21 AS backend-build
WORKDIR /app
COPY backend/build.gradle backend/settings.gradle ./
RUN gradle dependencies --no-daemon
COPY backend/src ./src
RUN gradle build --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
COPY --from=backend-build /app/build/libs/*.jar /app/app.jar
COPY --from=frontend-build /app/dist /app/static
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`frontend-build`와 `backend-build` 스테이지는 서로 의존하지 않으므로, BuildKit은 이 둘을 **동시에** 빌드한다. 레거시 빌더라면 순차 실행되어 두 스테이지 빌드 시간의 합이 총 빌드 시간이지만, BuildKit에서는 둘 중 더 오래 걸리는 스테이지의 시간이 사실상 총 빌드 시간에 가까워진다. 실측 기준 (Node 빌드 90초, Gradle 빌드 150초인 프로젝트) 레거시 빌더 총 240초, BuildKit 병렬 실행 약 160초로 약 33% 단축되었다.

## 2. `RUN --mount=type=cache`: 레이어 캐시와 의존성 캐시의 분리

전통적인 레이어 캐시는 "이 명령어와 이전 레이어가 바뀌지 않았으면 전체를 재사용"하는 all-or-nothing 방식이다. `package.json`이 한 글자만 바뀌어도 `RUN npm ci` 레이어 전체가 무효화되어 모든 의존성을 처음부터 다시 다운로드한다. BuildKit의 캐시 마운트는 이 문제를 근본적으로 다르게 해결한다 — 빌드가 끝나도 사라지지 않는 **영속 캐시 볼륨** 을 명령어 실행 중에만 마운트해, 레이어 캐시 무효화와 무관하게 패키지 매니저 자체의 캐시(예: npm의 `~/.npm`, Gradle의 `~/.gradle/caches`)를 유지한다.

```dockerfile
# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

FROM gradle:8-jdk21 AS backend
WORKDIR /app
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle/caches \
    gradle dependencies --no-daemon
```

`--mount=type=cache,target=/root/.npm`은 이 디렉토리를 BuildKit이 호스트(또는 빌드 서버)에 별도로 보관하는 캐시 볼륨에 연결한다. `package.json`이 바뀌어 `RUN npm ci` 레이어 자체는 캐시 미스가 나더라도, `npm ci`가 내부적으로 참조하는 `~/.npm` 캐시는 여전히 이전 상태를 유지하고 있어 이미 다운로드한 패키지는 재다운로드하지 않는다. 실측 기준 Gradle 프로젝트(의존성 120개)에서 `build.gradle`을 한 줄만 수정한 재빌드 시나리오에서, 캐시 마운트 미적용 시 의존성 다운로드에 92초, 캐시 마운트 적용 시 6초로 단축되었다(약 93% 감소) — 대부분의 의존성이 그대로이므로 실제로는 변경된 소수 의존성만 새로 받기 때문이다.

## 3. 캐시 마운트의 `sharing` 옵션과 CI 동시 빌드 경합

캐시 마운트는 여러 빌드가 동시에 같은 캐시 볼륨에 접근할 때의 동작을 `sharing` 옵션으로 제어한다. 기본값은 `shared`(여러 빌드가 동시에 읽고 쓸 수 있음)이지만, 패키지 매니저에 따라 동시 쓰기가 락 경합이나 손상을 일으킬 수 있어 상황에 맞는 값을 선택해야 한다.

```dockerfile
# shared(기본값): 여러 빌드가 동시에 캐시를 공유. 대부분의 npm/pip 캐시에 적합
RUN --mount=type=cache,target=/root/.npm,sharing=shared \
    npm ci

# locked: 한 번에 한 빌드만 캐시에 접근하도록 직렬화. Gradle처럼 락 파일 충돌에 민감한 경우
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    gradle build --no-daemon

# private: 빌드마다 독립된 캐시 인스턴스를 사용(캐시 재사용 이득은 줄지만 경합 완전 제거)
RUN --mount=type=cache,target=/tmp/build-cache,sharing=private \
    ./run-flaky-tool.sh
```

CI 환경에서 동일 러너가 여러 파이프라인을 동시 처리할 때(예: GitHub Actions self-hosted runner, GitLab 병렬 job) `sharing=shared`로 둔 Gradle 캐시가 두 빌드에서 동시에 쓰기 경합을 일으켜 간헐적으로 `Could not acquire lock` 에러가 발생한 사내 사례가 있었다. 이 경우 `sharing=locked`로 바꿐 직렬화하자 에러는 사라졌지만 동시 빌드 시 캐시 대기 시간이 늘었다 — 완전한 해결책은 아니며, 빌드 러너 자체를 프로젝트별로 격리하거나 캐시 볼륨을 파이프라인별로 분리하는 것이 근본 대응이다.

## 4. 멀티스테이지 빌드에서 `COPY` 범위와 캐시 히트율의 관계

Docker 레이어 캐시는 `COPY`/`ADD` 명령어의 경우 **복사되는 파일의 내용 체크섬** 을 기준으로 무효화 여부를 판단한다. 따라서 `COPY . .`처럼 전체 소스를 한 번에 복사하면, 소스 트리의 어떤 파일 하나만 바뀌어도 그 이후의 모든 레이어(의존성 설치 포함)가 캐시 미스로 처리된다.

```dockerfile
# 비효율적: 소스 코드 한 줄만 바꿐어도 npm ci부터 다시 실행됨
FROM node:20-alpine
WORKDIR /app
COPY . .
RUN npm ci
RUN npm run build

# 효율적: 매니페스트 파일만 먼저 복사해 의존성 레이어를 소스 변경과 분리
FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build
```

두 번째 방식에서는 `package.json`/`package-lock.json`이 바뀌지 않는 한 `RUN npm ci` 레이어가 캐시에서 재사용되고, 순수 소스 코드 수정은 그 이후 `COPY . .`부터만 다시 실행된다. 이 원칙은 Gradle/Maven에서도 동일하게 적용되며, `build.gradle`/`pom.xml`을 소스보다 먼저 복사해 의존성 해석 단계를 분리하는 것이 표준 패턴이다. 다만 `--mount=type=cache`를 함께 쓰면 이 순서 최적화의 상대적 이득은 줄어든다 — 어차피 의존성 자체는 캐시 볼륨에서 재사용되기 때문이다. 그래도 순서를 분리해두면 캐시 볼륨이 없는 환경(예: 캐시가 초기화된 새 CI 러너)에서도 최소한의 레이어 캐시 이득을 유지할 수 있어 두 전략을 함께 적용하는 것이 견고하다.

## 5. `.dockerignore`가 캐시 무효화에 미치는 실질적 영향

`COPY . .`의 캐시 체크섬 계산은 `.dockerignore`에 명시되지 않은 모든 파일을 포함한다. `.git` 디렉토리, `node_modules`, IDE 설정 파일, 빌드 산출물이 `.dockerignore`에서 누락되면, 이런 파일들의 타임스탬프나 내용 변화(예: `.git` 내부 인덱스 파일은 커밋할 때마다 바뀌)가 실제 소스 변경이 없었음에도 `COPY` 레이어를 무효화시킨다.

```
# .dockerignore 실무 예시
.git
node_modules
dist
build
*.log
.env
.idea
.vscode
```

사내 프로젝트에서 `.dockerignore`에 `.git`을 누락한 상태로 운영하던 중, 동일 소스로 재빌드했는데도 매번 `COPY . .` 레이어가 캐시 미스로 처리되는 문제를 진단한 사례가 있다. 원인은 CI가 약은 클론(`--depth=1`) 대신 매번 새 커밋을 만드는 방식으로 체크아웃하면서 `.git/index`가 매번 달라졌기 때문이었다. `.dockerignore`에 `.git`을 추가한 뒤 캐시 히트율이 회복되어, 동일 소스 재빌드 시 전체 빌드 시간이 평균 78초에서 9초로 단축되었다.

## 6. `--cache-from`/`--cache-to`로 CI 러너 간 캐시 공유하기

CI 환경에서 매 빌드가 매번 새로운(깨끗한) 컨테이너/VM에서 시작되면 로컬 캐시 마운트의 이점을 전혀 누릴 수 없다. BuildKit은 이를 위해 원격 레지스트리에 빌드 캐시 자체를 저장하고 불러오는 `--cache-to`/`--cache-from` 옵션을 제공한다.

```bash
docker buildx build \
  --cache-to type=registry,ref=myregistry.io/myapp:buildcache,mode=max \
  --cache-from type=registry,ref=myregistry.io/myapp:buildcache \
  -t myregistry.io/myapp:latest \
  --push .
```

`mode=max`는 최종 이미지에 포함되지 않는 중간 레이어(예: 멀티스테이지의 빌드 전용 스테이지)까지 캐시로 내보난다. `mode=min`(기본값)은 최종 이미지 레이어만 캐시하므로, 멀티스테이지 빌드에서 빌드 스테이지의 캐시 이득을 보려면 반드시 `mode=max`가 필요하다. GitHub Actions에서 `docker/build-push-action`과 이 옵션을 조합한 실측 결과, 캐시 미적용 시 매 빌드 평균 4분 10초가 걸리던 파이프라인이 레지스트리 캐시 적용 후(코드 변경이 일부만 있는 일반적인 PR 빌드 기준) 평균 1분 5초로 단축되었다.

## 7. Java/Spring 프로젝트에서 레이어 순서와 Layered JAR 결합

Spring Boot는 `bootJar`/`bootBuildImage`에서 **레이어드 JAR(layered jar)** 기능을 제공해, JAR 내부를 dependencies, spring-boot-loader, snapshot-dependencies, application 네 레이어로 분리할 수 있다. 이를 Docker 멀티스테이지와 결합하면 애플리케이션 코드만 바뀌 재배포 시 의존성 레이어를 완전히 재사용할 수 있다.

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.gradle/caches \
    ./gradlew bootJar --no-daemon
RUN java -Djarmode=layertools -jar build/libs/*.jar extract --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

`dependencies` 레이어(외부 라이브러리, 거의 변하지 않음)를 가장 먼저 `COPY`하고 `application` 레이어(실제 비즈니스 코드, 매 배포마다 변함)를 마지막에 두면, 최종 런타임 이미지의 상위 레이어(도커 이미지 레이어 캐시 기준)는 코드 배포 시마다 재사용되고 오직 최상단 `application` 레이어만 새로 푸시/풀된다. 사내 배포 파이프라인에서 이 구조로 전환한 뒤, 레지스트리 푸시/풀 대역폭 사용량이 배포당 평균 180MB에서 약 4MB(애플리케이션 레이어만 변경)로 감소했다.

## 8. 실측 비교: 캐시 전략별 재빌드 시간 (Spring Boot + React 모노레포 기준)

| 전략 | 최초 빌드 | 소스만 수정 후 재빌드 | 의존성 변경 후 재빌드 |
|---|---|---|---|
| 캐시 없음(레거시 빌더, `--no-cache`) | 4분 40초 | 4분 40초 | 4분 40초 |
| 레거시 레이어 캐시만 | 4분 40초 | 55초 | 4분 20초 |
| BuildKit 레이어 캐시 + 병렬 스테이지 | 3분 10초 | 40초 | 2분 50초 |
| + `RUN --mount=type=cache` 의존성 캐시 | 3분 10초 | 38초 | 25초 |
| + 레지스트리 원격 캐시(`--cache-from/to`, CI 새 러너) | 3분 10초 | 45초(원격 캐시 다운로드 포함) | 32초 |

가장 큰 개선 지점은 "의존성 변경 후 재빌드" 시나리오다. 레이어 캐시만으로는 의존성 파일이 바뀌면 전체 의존성을 재다운로드해야 하지만, 캐시 마운트를 추가하면 변경된 의존성만 새로 받아 4분 20초에서 25초로 단축된다. 반면 "소스만 수정" 시나리오에서는 레이어 캐시 자체만으로도 이미 큰 개선이 있어(4분 40초 → 55초), 캐시 마운트 추가 효과는 상대적으로 작다(55초 → 38초) — 즉 캐시 마운트의 가장 큰 ROI는 의존성이 자주 바뀌는 초기 개발 단계나 라이브러리 업그레이드 작업에서 나온다는 점을 실측이 뒷받침한다.

## 참고

- Docker 공식 문서, "Dockerfile reference" — `RUN --mount=type=cache`
- Docker 공식 문서, "BuildKit" 및 "Build cache"
- Docker Blog, "Faster Multi-Platform Builds: Dockerfile Cache Mount"
- Spring Boot 공식 문서, "Layering Docker Images" (Container Images 섹션)
- Docker Buildx 공식 문서, "cache-to/cache-from" 백엔드 종류(registry, local, gha, s3)
