Notion 원본: https://app.notion.com/p/3d85a06fd6d381a38971c66510fe9c90?pvs=204

# Nix Flakes와 재현 가능한 빌드 및 개발 환경 격리

> 2026-09-11 신규 주제 · 확장 대상: Docker / 빌드 재현성

## 학습 목표

- Docker 이미지와 Gradle 락파일이 보장하지 못하는 재현성의 구멍을 구분한다
- Nix store 경로와 derivation 해시 모델로 순수 함수형 패키지 관리를 설명한다
- `flake.nix` / `flake.lock` 을 작성해 팀 개발 셀과 컨테이너 이미지를 고정한다
- JVM 프로젝트에 Nix 를 부분 도입할 때의 비용과 대안을 비교해 판단한다

## 1. 재현성 문제 정의 — Dockerfile 은 왜 재현 가능하지 않은가

Spring 프로젝트를 컨테이너로 말아 올릴 때 대부분 다음과 같은 Dockerfile 을 쓴다. 이 파일은 "환경을 코드로 고정했다"는 인상을 주지만, 실제로 고정하는 것은 **빌드 절차**이지 **빌드 결과**가 아니다.

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y openjdk-21-jdk curl postgresql-client
COPY . /app
WORKDIR /app
RUN ./gradlew bootJar
```

`apt-get update` 는 실행 시점의 Ubuntu 아카이브 인덱스를 내려받는다. 3월에 빌드하면 `openjdk-21-jdk` 가 21.0.2 로, 9월에 빌드하면 21.0.8 로 해석된다. `apt-get install` 은 버전을 명시하지 않았으므로 언제나 "그 시점의 최신"을 뜻하고, 명시하더라도 해당 버전이 아카이브에서 제거되면 빌드는 깨진다. `curl` 이 끌고 오는 OpenSSL, glibc 의 마이너 버전도 같은 방식으로 흔들린다.

`FROM ubuntu:22.04` 를 `FROM ubuntu@sha256:...` 로 바꾸면 베이스 레이어는 비트 단위로 고정된다. 그러나 그 위의 `RUN apt-get ...` 레이어는 여전히 네트워크에서 현재 상태를 읽어온다. 다이제스트 고정은 **출발점만** 고정할 뿐이고, 빌드 중 네트워크 접근이 열려 있는 한 결과는 시간의 함수로 남는다. Docker 레이어 캐시는 이 비결정성을 더 교묘하게 만든다. `RUN apt-get update` 라인은 텍스트가 바뀌지 않으므로 캐시 히트가 나고, 내 머신에서는 6개월 전 캐시된 레이어를 쓰고 CI 에서는 새로 받는다. 같은 커밋에서 두 개의 서로 다른 이미지가 나오는 정확한 구조가 여기다.

Gradle/Maven 락은 이 문제의 **한 층만** 해결한다. `gradle/verification-metadata.xml` 이나 `dependencyLocking` 은 `spring-boot-starter-web:3.3.4` 같은 JVM 아티팩트를 체크섬까지 고정한다. 훌륭하지만 JVM 자체, glibc, zlib, `libpq`, 빌드에 참여하는 `protoc` 이나 네이티브 이미지 툴체인은 락의 사정권 밖이다. 실무에서 깨지는 지점은 대개 이 시스템 라이브러리 층이다. 네이티브 라이브러리를 로드하는 테스트가 로컬 macOS 에서는 통과하고 CI 리눅스에서 깨지는 상황, JDK 마이너 버전 차이로 `Locale` 포맷 결과가 달라져 테스트가 빨개지는 상황이 전형적이다.

| 층 | 고정 수단 | 실제 보장 범위 |
|---|---|---|
| JVM 라이브러리 | Gradle 락 / verification-metadata | 아티팩트 체크섬까지 완전 고정 |
| JDK·툴체인 | Gradle toolchain, Dockerfile `apt` | 벤더·마이너 버전 흔들림 |
| 시스템 라이브러리 | 베이스 이미지 다이제스트 | 베이스 레이어만, 이후 `RUN` 은 비고정 |
| 빌드 환경변수·시각 | 없음 | 타임스탬프·로케일·`$HOME` 유입 |

"내 로컬엠 되는데"는 개인의 부주의가 아니라 **암묵적 입력이 선언되지 않는 구조** 그 자체다. 셀의 `PATH`, Homebrew 로 깐 `openssl`, 이전 실행이 남긴 `~/.gradle` 캐시가 모두 빌드 입력인데 어디에도 기록되지 않는다.

## 2. Nix 의 핵심 모델 — 경로가 곳 해시다

Nix 는 이 문제를 "패키지 빌드를 순수 함수로 취급한다"로 푸다. 패키지 하나는 `빌드(입력들) → 출력` 함수이고, 입력이 같으면 출력도 같아야 한다. 그래서 결과물의 설치 경로 자체에 **입력들의 해시**를 박는다.

```bash
$ nix build nixpkgs#jdk21
$ readlink -f ./result
/nix/store/9zq1p0k7w3bx2mj4v8cn5rd6hs0ytfla-openjdk-21.0.5+11
```

`9zq1p0k7...` 는 출력 파일의 해시가 아니라 **입력 집합의 해시**다. 사용한 소스 tarball, 컴파일러, 빌드 스크립트, 패치, 의존 패키지들의 store 경로가 전부 직렬화되어 해시로 접힌다. 따라서 JDK 를 빌드한 GCC 버전이 하나 달라지면 store 경로 전체가 바뀜다. `/nix/store` 는 빌드 직후 읽기 전용으로 잠기고, 그 뒤로 절대 수정되지 않는다. `/usr/lib` 처럼 전역 공유 위치에 덮어쓰는 일이 없으니 JDK 17 과 21, OpenSSL 1.1 과 3.0 이 한 머신에서 충돌 없이 공존한다.

사용자가 보는 "현재 환경"은 심볼릭 링크 묶음, 즉 프로필이다. `~/.nix-profile/bin/java` 는 특정 store 경로를 가리키고, 업그레이드는 링크를 새 경로로 갈아끼우는 원자적 연산이다. 롤백이 "이전 링크로 되돌리기"로 끝나는 이유이며, `apt upgrade` 도중 전원이 나가면 시스템이 반쯤 망가지는 것과 대조된다.

빌드 지시서는 `.drv` derivation 파일이다. 사람이 쓴 `.nix` 표현식을 평가하면 derivation 이 나오고, 실제 빌드는 derivation 만 보고 수행된다.

```bash
$ nix derivation show nixpkgs#hello | head -20
# builder, args, env, inputDrvs, outputs 가 전부 명시된 JSON
```

빌드는 샌드박스에서 실행된다. 리눅스에서는 별도 mount/network/PID 네임스페이스에 들어가고, **네트워크가 차단되며**, `$HOME` 은 존재하지 않는 경로로 설정되고, 파일 타임스탬프는 1970-01-01 +1초로 고정된다. 빌드 중 `curl` 로 뭔가를 받아오려 하면 그냥 실패한다. Dockerfile 의 `RUN apt-get update` 가 원천적으로 불가능한 환경이라고 보면 된다. 예외는 소스 다운로드처럼 네트워크가 꼭 필요한 경우인데, 이때는 결과물의 해시를 미리 선언하는 fixed-output derivation 을 쓴다(5절).

## 3. Flakes 가 추가한 것 — 입력의 스키마화와 락파일

Nix 자체는 2003년부터 있었지만, "이 프로젝트가 어떤 nixpkgs 를 쓰는가"는 오랫동안 사용자의 `NIX_PATH` 환경변수에 달려 있었다. 같은 `.nix` 파일이 사람마다 다르게 평가되는, Nix 답지 않은 구멍이었다. Flakes 는 이 마지막 암묵적 입력을 파일로 끌어냈다.

```nix
{
  description = "order-api backend";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell { packages = [ pkgs.jdk21 ]; };
      });
}
```

`nix build` / `nix develop` 을 처음 실행하면 `flake.lock` 이 생성된다. `nixos-24.11` 이라는 움직이는 브랜치 이름이 특정 커밋 SHA 와 NAR 해시로 못박힌 결과다.

```json
{
  "nodes": {
    "nixpkgs": {
      "locked": {
        "lastModified": 1733096140,
        "narHash": "sha256-1qRH7uAUsyQI7R1Uwl4T+XvdNv778H0Nb5njNrqvylY=",
        "owner": "NixOS", "repo": "nixpkgs",
        "rev": "5083ec887760adfe12af64830a66807423a859a7",
        "type": "github"
      }
    }
  },
  "version": 7
}
```

역할은 `package-lock.json`, `gradle.lockfile` 과 같지만 **범위가 다르다**. `package-lock.json` 은 npm 패키지를, Gradle 락은 Maven 좌표를 고정한다. `flake.lock` 은 nixpkgs 트리 전체 — 즉 JDK, glibc, PostgreSQL, `ripgrep` 까지 10만 개 패키지의 빌드 레시피 집합 — 를 커밋 하나로 고정한다. 의존성 목록이 아니라 **패키지 우주 전체의 스냅샷**을 고정하는 셈이다.

Flakes 는 여기에 순수 평가(pure evaluation)를 강제한다. flake 평가 중에는 `builtins.getEnv` 로 환경변수를 읽거나, 락에 없는 경로를 참조하거나, git 에 add 되지 않은 파일을 읽을 수 없다. 마지막 항목은 처음 쓰는 사람이 반드시 한 번 걸리는 함정이다. 새로 만든 `flake.nix` 를 `git add` 하지 않으면 "file not found" 가 난다. 정말 외부 상태가 필요하면 `--impure` 로 탈출할 수 있지만, 그 순간 재현성 보장은 사라진다.

```bash
nix flake metadata            # 입력 트리와 고정된 리비전 확인
nix flake update              # 모든 입력을 최신으로, flake.lock 갱신
nix flake update nixpkgs      # 특정 입력만 갱신 (Nix 2.19+)
nix flake check               # outputs 가 전부 평가·빌드되는지 검증
```

`nix flake update` 를 실행한 커밋의 diff 는 `flake.lock` 의 `rev` 한 줄이고, 그 한 줄이 팀 전원의 JDK·툴체인 업그레이드다. Renovate 가 `flake.lock` 을 갱신하는 PR 을 올리도록 붙여두면 Gradle 의존성 봇과 동일한 워크플로가 된다.

## 4. 개발 환경 격리 — devShells 와 direnv

가장 실익이 크고 위험이 작은 도입 지점은 개발 셀이다. 아래는 Spring 백엔드 + 프론트 스크립트가 섞인 저장소에서 바로 쓸 수 있는 형태다.

```nix
{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21
            gradle
            nodejs_22
            postgresql_16.out      # psql, pg_dump 클라이언트
            awscli2
            jq
          ];

          # JAVA_HOME 을 store 경로로 고정 — 호스트 설정과 무관해짐
          JAVA_HOME = "${pkgs.jdk21}";
          GRADLE_OPTS = "-Dorg.gradle.daemon=false";

          shellHook = ''
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "order-api dev shell | $(java -version 2>&1 | head -1)"
          '';
        };
      });
}
```

```bash
$ nix develop
order-api dev shell | openjdk version "21.0.5" 2024-10-15
$ which psql
/nix/store/3vd8...-postgresql-16.6/bin/psql
```

이 셀에서 `java`, `gradle`, `psql` 은 전부 store 경로를 가리킨다. 호스트에 JDK 가 설치돼 있든 없든, SDKMAN 으로 뭔 깔아놓았든 영향받지 않는다. 반대로 `git`, 에디터, `~/.ssh`, Docker 데몬은 그대로 쓸 수 있다. 파일시스템과 네트워크는 격리되지 않기 때문이다. 컨테이너와 결정적으로 다른 지점이며, 장점이자 한계다.

매번 `nix develop` 을 치는 게 번거로우면 direnv 를 붙인다. `.envrc` 에 `use flake` 한 줄을 넣고 `direnv allow` 하면 디렉터리에 `cd` 하는 순간 환경이 적용되고 나가면 해제된다. `nix-direnv` 를 쓰면 평가 결과가 캐시돼 재진입이 수백 밀리초로 떨어진다.

```bash
$ echo "use flake" > .envrc
$ direnv allow
direnv: loading .envrc
direnv: using flake
$ cd .. && cd order-api   # 자동 재적용
```

| 항목 | Nix devShell | Docker devcontainer |
|---|---|---|
| 최초 진입 | 수 분(빌드/다운로드), 이후 1초 내외 | 이미지 빌드 수 분, 이후 컨테이너 시작 수 초 |
| 격리 수준 | PATH·환경변수 수준. 파일시스템·네트워크는 호스트 공유 | 프로세스·파일시스템·네트워크 네임스페이스 격리 |
| 호스트 도구 접근 | IDE·git·SSH 키·Docker 를 그대로 사용 | 볼륨 마운트·포트 포워딩 설정 필요 |
| 파일 I/O 성능 | 네이티브 | macOS/Windows 에서 바인드 마운트 오버헤드 |
| 온보딩 비용 | Nix 설치 + 언어 학습 곡선 | Docker Desktop + devcontainer 확장 |
| 재현성 | `flake.lock` 으로 비트 단위 고정 | Dockerfile 재빌드 시 드리프트 가능 |

정리하면 devcontainer 는 강한 격리와 낮은 학습 비용, Nix devShell 은 강한 재현성과 네이티브 성능이다. IDE 인덱싱 성능이 중요한 JVM 프로젝트에서는 후자의 이점이 체감된다.

## 5. 빌드 — JVM 과 Nix 의 궁합은 솔직히 나빠다

`packages.default` 로 애플리케이션 자체를 빌드하는 단계에서 JVM 생태계는 정면으로 벽을 만난다. 원인은 단순하다. **Gradle 과 Maven 은 빌드 도중 네트워크에서 의존성을 받는 것을 전제로 설계됐는데, Nix 빌드 샌드박스는 네트워크를 막는다.**

우회로는 fixed-output derivation(FOD)이다. "네트워크를 써도 좋다, 대신 결과물의 해시를 미리 선언하라"는 예외 장치로, `fetchurl` 도 같은 원리로 동작한다. 의존성 다운로드 단계를 FOD 로 분리하고, 실제 컴파일은 그 결과를 입력으로 받아 오프라인으로 수행한다.

```nix
{ pkgs ? import <nixpkgs> {} }:
let
  # 1단계: 의존성만 받아 로컬 저장소를 만드는 FOD
  deps = pkgs.stdenv.mkDerivation {
    name = "order-api-deps";
    src = ./.;
    nativeBuildInputs = [ pkgs.gradle pkgs.jdk21 ];
    buildPhase = ''
      export GRADLE_USER_HOME=$PWD/.gradle
      gradle --no-daemon --offline=false resolveAllDependencies || true
    '';
    installPhase = ''cp -r .gradle/caches/modules-2 $out'';

    outputHashMode = "recursive";
    outputHashAlgo = "sha256";
    outputHash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  };
in
pkgs.stdenv.mkDerivation {
  pname = "order-api";
  version = "1.0.0";
  src = ./.;
  nativeBuildInputs = [ pkgs.gradle pkgs.jdk21 pkgs.makeWrapper ];
  buildPhase = ''
    export GRADLE_USER_HOME=$PWD/.gradle
    mkdir -p $GRADLE_USER_HOME/caches && cp -r ${deps} $GRADLE_USER_HOME/caches/modules-2
    gradle --offline --no-daemon bootJar
  '';
  installPhase = ''
    mkdir -p $out/{share,bin}
    cp build/libs/*.jar $out/share/order-api.jar
    makeWrapper ${pkgs.jdk21}/bin/java $out/bin/order-api \
      --add-flags "-jar $out/share/order-api.jar"
  '';
}
```

문제는 이 `outputHash` 를 안정적으로 유지하기 어렵다는 데 있다. Gradle 캐시 디렉터리에는 타임스탬프, 락 파일, 머신마다 다른 메타데이터가 섞이고, Gradle 버전이 오르면 캐시 레이아웃이 바뀜다. `build.gradle` 에 의존성 한 줄을 추가할 때마다 해시를 다시 계산해 커밋해야 하는데, 실패 메시지에 나오는 실제 해시를 붙여넣는 수작업이 반복된다. `gradle2nix` 는 Gradle 의존성 그래프를 읽어 각 아티팩트를 개별 `fetchurl` derivation 으로 생성하는 도구로 이 문제를 상당히 완화하지만, Gradle 플러그인 API 변화를 따라가야 해서 최신 Gradle 지원이 늦어지는 시기가 생긴다. Maven 쪽 `buildMavenPackage` 는 `~/.m2` 전체를 FOD 로 잡는 단순한 방식이라 비교적 안정적이지만 세밀함이 떨어진다.

근본 이유는 생태계 철학의 충돌이다. Go 나 Rust 는 `go.sum`, `Cargo.lock` 에 모든 의존성의 정확한 해시가 들어 있어 Nix 가 그대로 읽으면 되지만, Gradle 은 동적 버전, 커스텀 리포지토리, 빌드 스크립트에서 의존성을 조작하는 플러그인을 허용하는 튜링 완전한 빌드 시스템이다. 정적으로 의존성 그래프를 뽑는 것 자체가 어렵다.

현실적인 우회책은 **역할 분담**이다. Nix 는 개발 셀과 배포 이미지 조립(6절)에만 쓰고, JAR 컴파일은 Gradle 이 원래 하던 방식대로 CI 에서 수행한다. 만들어진 JAR 을 Nix 입력으로 넣는 구조가 유지보수 부담 대비 효과가 가장 좋다. `nix build` 로 JAR 까지 만드는 완전 통합은 nix-native 툴체인을 관리할 인력이 있는 팀에서만 권한다.

## 6. 컨테이너 이미지 생성 — Dockerfile 없이 재현 가능한 이미지

Nix 가 JVM 진영에서도 확실한 우위를 갖는 영역이 이미지 빌드다. `dockerTools` 는 Docker 데몬 없이 OCI 이미지 tarball 을 순수하게 조립한다.

```nix
packages.container = pkgs.dockerTools.buildLayeredImage {
  name = "order-api";
  tag = "latest";

  contents = [
    pkgs.jdk21_headless
    pkgs.cacert          # TLS 루트 인증서
    pkgs.tzdata
  ];

  config = {
    Cmd = [ "${pkgs.jdk21_headless}/bin/java" "-jar" "/app/order-api.jar" ];
    Env = [ "TZ=Asia/Seoul" "LANG=C.UTF-8" ];
    ExposedPorts = { "8080/tcp" = {}; };
  };

  extraCommands = ''mkdir -p app && cp ${./build/libs/order-api.jar} app/order-api.jar'';
  maxLayers = 100;
};
```

```bash
$ nix build .#container
$ docker load < result
Loaded image: order-api:latest
```

레이어 분할 방식이 Docker 와 근본적으로 다르다. Dockerfile 의 레이어는 **명령 순서**로 나뉘므로, 앞쪽 `RUN` 한 줄만 바뀌어도 뒤의 모든 레이어가 무효화된다. `buildLayeredImage` 는 의존성 그래프를 분석해 **패키지 단위로** 레이어를 만들고, 여러 이미지가 공유하는 정도와 크기를 기준으로 자동 배치한다. 그래서 JDK 는 자기 레이어, 애플리케이션 JAR 은 자기 레이어에 놓이고, 애플리케이션만 바뀌면 전송되는 레이어도 그것뿐이다. Dockerfile 에서 `COPY build.gradle` → `RUN gradle dependencies` → `COPY src` 순서를 손으로 배열해 캐시를 최적화하던 작업이 자동화되는 셈이다.

재현성 측면에서는 결과가 명확하다. 같은 `flake.lock` 과 같은 소스면 `nix build .#container` 는 어느 머신에서든 **같은 이미지 다이제스트**를 낸다. 모든 파일 타임스탬프가 0으로 고정되고 파일 순서가 결정적으로 정렬되기 때문이다. Dockerfile 로는 `SOURCE_DATE_EPOCH` 와 BuildKit 옵션을 동원해도 달성하기 까다로운 성질이다.

크기도 유리하다. `contents` 에 명시한 패키지와 그 실제 런타임 의존성만 들어가므로 패키지 매니저, 셀, `apt` 메타데이터가 없다. 이는 Google distroless 가 추구하는 것과 같은 목표인데, distroless 가 미리 만들어둔 몇 종의 베이스 이미지 중 고르는 방식이라면 Nix 는 필요한 조합을 매번 정확히 생성한다. 대신 `sh` 가 없어 `docker exec ... bash` 가 안 되므로, 디버깅이 필요하면 `pkgs.busybox` 를 넣은 디버그 변형을 따로 output 으로 두는 편이 낫다.

## 7. CI 통합 — GitHub Actions 와 바이너리 캐시

Nix 의 store 경로는 입력 해시이므로, **누군가 이미 그 해시를 빌드했다면 결과물을 그대로 받아쓸 수 있다.** 이게 바이너리 캐시이고, `cache.nixos.org` 가 nixpkgs 공식 빌드를 제공한다. 자체 패키지는 cachix 같은 서비스에 올린다.

```yaml
name: build
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: cachix/install-nix-action@v30
        with:
          extra_nix_config: |
            experimental-features = nix-command flakes
            accept-flake-config = true

      - uses: cachix/cachix-action@v15
        with:
          name: my-org-order-api
          authToken: '${{ secrets.CACHIX_AUTH_TOKEN }}'

      - run: nix flake check
      - run: nix build .#container
      - run: docker load < result
```

`cachix-action` 은 빌드 후 새로 만들어진 store 경로를 자동으로 캐시에 push 한다. 효과는 극단적으로 갈린다. 캐시 미스 시 nixpkgs 를 소스에서 빌드하면 JDK 컴파일만으로 수십 분이 날아가지만, 히트하면 바이너리 다운로드와 압축 해제뿐이라 수십 초로 끝난다. 그래서 Nix CI 의 성패는 사실상 **캐시 히트율 관리**다. 실무 규칙은 두 가지다. 첫째, `flake.lock` 의 nixpkgs 를 임의 커밋이 아니라 Hydra 가 이미 빌드해 둔 릴리스 브랜치(`nixos-24.11`)나 `nixpkgs-unstable` 에 맞춘다. 둘째, `overlays` 로 하위 패키지를 패치하면 그것에 의존하는 모든 상위 패키지의 해시가 바뀌어 대량 재빌드가 발생하므로, 꼭 필요한 경우가 아니면 피한다.

self-hosted runner 라면 `/nix/store` 가 잡 사이에 유지되므로 로컬 캐시가 그대로 살아 있다. 다운로드조차 생략되어 가장 빠르다. 다만 store 는 누적되기만 하므로 `nix-collect-garbage --delete-older-than 14d` 를 주기 작업으로 걸어 디스크를 관리해야 한다. GitHub 호스티드 러너에서 `actions/cache` 로 `/nix/store` 를 통째로 캐싱하는 방법도 있지만, store 크기가 수 GB 로 커지면 캐시 업로드·다운로드 자체가 병목이 되어 cachix 보다 불리해지는 구간이 온다.

## 8. 도입 트레이드오프 — 무엇을 포기하고 무엇을 얻는가

가장 큰 비용은 Nix 언어다. 지연 평가 기반 순수 함수형 언어인데, 지연 평가 때문에 오류가 **발생 지점이 아니라 값이 강제되는 지점**에서 터진다. `error: attribute 'foo' missing` 같은 메시지가 스택 트레이스 수십 줄과 함께 나오는데 정작 어느 `.nix` 파일의 어느 줄이 원인인지 알기 어렵다. Java 백엔드 엔지니어에게는 NPE 스택 트레이스에서 원인 프레임이 지워진 상태와 비슷한 경험이다. 최근 버전에서 `--show-trace` 와 오류 메시지가 개선됐지만, 여전히 첫 몇 주는 답답하다. 문서 파편화도 문제다. 같은 작업에 `nix-env`, `nix-shell`, `nix profile`, `nix develop` 이 공존하고 블로그 글의 시점에 따라 방식이 달라 검색 결과를 그대로 믿기 어렵다.

팀 확산 실패 패턴은 거의 정형화돼 있다. 한 명의 열정적인 엔지니어가 저장소 전체를 Nix 로 재구성하고, 나머지 팀원은 `flake.nix` 를 읽지 못한 채 사용만 한다. 그가 퇴사하면 아무도 JDK 버전을 올리지 못해 결국 Dockerfile 로 되돌아간다. 이를 막으려면 처음부터 최소 두 명이 수정 가능해야 하고, `flake.nix` 는 짧고 단순하게 유지해야 한다. 추상화를 쌓아 500줄짜리 라이브러리로 만드는 순간 버스 팩터는 1이 된다.

플랫폼 지원 현황도 확인이 필요하다. 리눅스는 완전 지원이다. macOS 는 Apple Silicon 포함 잘 동작하지만 Darwin 패키지 커버리지가 리눅스보다 얇고, macOS 메이저 업그레이드가 `/nix` 마운트 설정을 건드려 재설정이 필요한 경우가 있다. Windows 는 네이티브 지원이 없고 WSL2 안에서 리눅스로 쓰는 것이 유일한 경로다. WSL2 자체는 안정적이지만, IDE 를 Windows 쪽에서 실행하면 파일시스템 경계를 넘어야 하니 프로젝트를 WSL2 파일시스템 내부에 두고 IntelliJ 의 WSL 연동으로 여는 구성이 필요하다.

권하는 도입 순서는 **개발 셀 → CI 툴체인 → 이미지 빌드**다. `flake.nix` 에 `devShells.default` 만 두고 시작하면 기존 Dockerfile, Gradle, GitHub Actions 를 하나도 건드리지 않고, 마음에 안 들면 파일 하나 지우면 끝이다. 여기서 팀이 편익을 체감한 뒤에야 6절의 이미지 빌드로 넘어간다. 5절의 JAR 빌드까지 Nix 로 옮기는 것은 마지막이고, 대부분의 팀은 여기까지 갈 필요가 없다.

| 도구 | 재현성 강도 | 학습 비용 | 적합한 상황 |
|---|---|---|---|
| Nix Flakes | 매우 높음(비트 단위) | 높음 | 다년간 유지할 서비스, 다중 언어 모노레포, 규제·감사 대응 |
| Devbox | 높음(내부적으로 Nix) | 낮음 | Nix 이점을 원하나 언어 학습은 피하고 싶을 때 |
| mise / asdf | 중간(런타임 버전만) | 매우 낮음 | 언어 런타임 버전 통일이 목적일 때 |
| Docker + 다이제스트 고정 | 중간 | 이미 보유 | 팀이 Docker 에 익숙하고 드리프트를 감수 가능할 때 |

선택 기준은 명확하다. 문제가 "JDK 21 과 Node 22 를 팀이 똑같이 쓰게 하자"라면 mise 로 충분하고 Nix 는 과잉이다. "6개월 전 커밋을 오늘 체크아웃해서 그때와 동일한 바이너리를 만들어야 한다"거나 "빌드 환경을 감사 대상으로 증명해야 한다"면 Nix 가 유일하게 답을 주는 도구다. Devbox 는 `devbox.json` 에 패키지 목록만 적으면 내부적으로 Nix store 를 쓰는 절충안이라, 학습 곡선이 부담스러운 팀의 현실적인 첫 단추가 된다.

## 참고

- Nix 공식 학습 자료: https://nix.dev/
- Nix Reference Manual (nix 커맨드·언어): https://nixos.org/manual/nix/stable/
- Nixpkgs Manual (`dockerTools`, `mkShell`, 언어별 빌드 지원): https://nixos.org/manual/nixpkgs/stable/
- NixOS Wiki — Flakes: https://wiki.nixos.org/wiki/Flakes
- Cachix 문서 (바이너리 캐시·GitHub Actions 연동): https://docs.cachix.org/
- nix-direnv: https://github.com/nix-community/nix-direnv
- Devbox (Nix 기반 경량 개발 환경): https://www.jetify.com/docs/devbox/
