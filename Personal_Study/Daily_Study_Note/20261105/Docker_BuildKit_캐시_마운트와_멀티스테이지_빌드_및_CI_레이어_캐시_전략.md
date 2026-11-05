Notion 원본: https://app.notion.com/p/40f5a06fd6d381e7ad37d78d8f9612d7

# Docker BuildKit 캐시 마운트와 멀티스테이지 빌드 및 CI 레이어 캐시 전략

> 2026-11-05 신규 주제 · 확장 대상: Docker

## 학습 목표

- 레이어 캐시 무효화 규칙을 이해하고 Dockerfile 명령 순서를 재배치한다
- `RUN --mount` 의 cache · bind · secret 유형을 목적에 맞게 사용한다
- 멀티스테이지로 빌드 도구를 런타임 이미지에서 분리한다
- GitHub Actions에서 레지스트리 · gha 캐시 백엔드를 구성한다

## 1. 레이어 캐시의 동작 규칙

도커 이미지는 Dockerfile 명령 하나가 만드는 읽기 전용 레이어의 스택이다. 빌더는 각 명령에 대해 캐시 키를 계산해 일치하는 레이어가 있으면 재사용한다. `RUN`은 명령 문자열이, `COPY`·`ADD`는 복사 대상 파일 내용의 체크섬이 키가 된다. 한 레이어가 무효화되면 그 이후 모든 레이어가 다시 만들어진다. 따라서 자주 바뀌는 소스는 뒤에, 거의 바뀌지 않는 의존성 정의 파일은 앞에 두는 것이 기본 원칙이다. `.dockerignore`로 `.git`, `build`, `node_modules`를 빌드 컨텍스트에서 제외하면 컨텍스트 전송 시간이 줄고 불필요한 `COPY` 무효화도 줄어든다.

```dockerfile
# 나쁜 순서: 소스 한 줄만 바뀌어도 의존성 설치가 반복된다
COPY . /app
RUN npm ci

# 좋은 순서
COPY package.json package-lock.json /app/
RUN npm ci
COPY . /app
```

## 2. BuildKit 활성화와 문법 지정

도커 23.0부터 BuildKit이 기본 빌더이고 `docker buildx build`도 같은 엔진을 쓴다. 고급 기능을 안정적으로 쓰려면 Dockerfile 첫 줄에 `# syntax=docker/dockerfile:1`을 두어 프런트엔드 버전을 고정한다. BuildKit은 의존이 없는 스테이지를 병렬로 실행하고 사용되지 않는 스테이지는 건너뛴다. 이 점이 레거시 빌더와의 가장 큰 구조적 차이다.

## 3. RUN --mount=type=cache

레이어 캐시는 의존성 파일이 한 줄만 바뀌어도 패키지 전체를 다시 내려받는다. 캐시 마운트는 패키지 매니저의 다운로드 디렉터리를 빌드 사이에 유지되는 별도 영속 공간으로 마운트해 이를 해결한다. 마운트된 디렉터리의 내용은 최종 이미지 레이어에 포함되지 않는다. 이미지 크기를 키우지 않으면서 재빌드 속도를 높이는 방법이다.

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

FROM node:20-slim AS web
WORKDIR /web
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
```

여러 빌드가 같은 캐시 디렉터리를 동시에 쓰면 충돌할 수 있어 `sharing=locked`(한 번에 하나), `shared`(기본), `private` 중 선택하고, apt처럼 락을 쓰는 도구는 `locked`가 안전하다. 캐시 마운트 내용은 `--cache-to`로 내보내는 레이어 캐시에 포함되지 않는다는 점도 중요하다. CI 러너가 매번 새로 뜨면 캐시 마운트는 비어 있으므로 별도로 복원해야 한다.

## 4. bind · secret · ssh 마운트

`--mount=type=bind`는 빌드 컨텍스트나 다른 스테이지의 파일을 레이어에 복사하지 않고 읽기 전용으로 노출한다. 설치 후 지울 파일을 `COPY`하면 이미 그 레이어에 남아 크기가 줄지 않는 문제를 피한다. `--mount=type=secret`은 토큰·인증서를 빌드 중 일시적으로 파일(기본 `/run/secrets/<id>`)로 제공하며 이미지 히스토리에 남지 않는다. `ARG`나 `ENV`로 비밀을 넘기면 `docker history`에 노출되므로 금지해야 한다. `--mount=type=ssh`는 호스트의 SSH 에이전트를 빌드 중에만 전달해 private 저장소를 클론할 때 쓴다.

```dockerfile
RUN --mount=type=secret,id=npm_token \
    NPM_TOKEN=$(cat /run/secrets/npm_token) npm ci
```

```bash
docker buildx build --secret id=npm_token,env=NPM_TOKEN -t app:ci .
```

## 5. 멀티스테이지와 최종 이미지

빌드 도구(JDK, Gradle, 컴파일러)는 런타임에 필요 없다. 멀티스테이지 빌드에서 최종 스테이지는 `COPY --from=build`로 산출물만 가져온다. JVM 애플리케이션은 JRE 기반 이미지를 쓰거나 `jlink`로 만든 최소 런타임을 쓴다. 비루트 사용자(`USER`)를 지정하고, 시그널 전달을 위해 `ENTRYPOINT`를 exec 형식(JSON 배열)으로 쓴다. 셸 형식은 PID 1이 셸이 되어 SIGTERM이 애플리케이션에 전달되지 않을 수 있다.

```dockerfile
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

`-XX:MaxRAMPercentage`는 컨테이너 메모리 제한을 JVM이 인식(cgroup 지원)하는 최신 JDK에서 힙 비율을 지정하는 옵션이다. 원하는 값은 워크로드와 비힙 메모리 사용량을 보고 정해야 한다.

## 6. CI에서 레이어 캐시 유지

CI는 매번 깨끗한 러너에서 시작하므로 캐시를 외부에 내보내고 불러와야 한다. `docker/build-push-action`은 `cache-from`·`cache-to`로 백엔드를 지정한다. `type=gha`는 GitHub Actions 캐시 서비스를 쓰고 용량 제한이 있다(저장소당 기본 10GB). `type=registry`는 별도 태그의 캐시 이미지를 레지스트리에 올려 여러 러너와 브랜치가 공유한다. `mode=max`는 중간 스테이지 레이어까지 내보내 적중률이 높지만 용량이 커진다.

```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v6
  with:
    context: .
    push: true
    tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
    cache-from: type=registry,ref=ghcr.io/${{ github.repository }}:buildcache
    cache-to: type=registry,ref=ghcr.io/${{ github.repository }}:buildcache,mode=max
```

## 7. 측정과 점검

`docker buildx build --progress=plain`은 각 단계의 CACHED 여부와 시간을 평문으로 보여준다. `docker history <image>`로 레이어별 크기를, `dive` 같은 도구로 레이어별 파일 변화를 볼 수 있다. 캐시가 기대와 달리 무효화되면 `COPY` 대상에 타임스탬프나 빌드 번호 파일이 섞였는지, `RUN` 안의 `apt-get update`가 별도 레이어로 분리되어 오래된 인덱스를 캐시하는지(같은 `RUN`에서 `update && install`을 묶어야 한다) 확인한다. 베이스 이미지는 태그가 아닌 다이제스트(`image@sha256:...`)로 고정하면 재현성이 올라가지만 보안 패치를 수동으로 올려야 한다는 trade-off가 있다.

## 참고

- Docker Docs — Build cache, Cache backends, Dockerfile reference (RUN --mount)
- Docker Docs — Multi-stage builds, Build secrets
- docker/build-push-action 저장소 README (cache 항목)
