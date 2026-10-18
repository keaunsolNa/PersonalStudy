Notion 원본: https://app.notion.com/p/3fd5a06fd6d381368b3ce23d1ff6b3e5?pvs=204

# TypeScript Project References와 composite·incremental 빌드 tsbuildinfo 구조 및 모노레포 빌드 그래프 설계

> 2026-10-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `composite`가 강제하는 제약과 `references`의 오류 코드를 재현해서 구분한다.
- `.tsbuildinfo`의 `version`/`signature` 구조로 증분 빌드의 전파 범위를 예측한다.
- `tsc -b --verbose` 출력으로 up-to-date 판정과 타임스탬프 갱신 경로를 읽는다.
- 모노레포 빌드 그래프와 CI 캐시 단위를 설계한다.

## 1. 왜 프로젝트를 쪼개는가

Gradle 멀티모듈에 익숙하다면 Project References는 `implementation project(':core')`에 가깝다. 단일 `tsc -p`는 모든 파일을 한 `Program`에 올려 한 줄만 고쳐도 전체 그래프를 다시 따지고, tsserver도 전부 메모리에 올린다. 또한 `packages/a`가 `packages/b/src/internal`을 상대경로로 import 해도 막을 수단이 없다. Project References는 프로그램을 여러 개로 나누고, 참조하는 쪽이 상대 프로젝트의 **소스가 아니라 출력 `.d.ts`**를 읽게 만든다. 그래서 참조 대상의 `.d.ts`가 바뀌지 않으면 하위 프로젝트는 재검사 없이 넘어갈 수 있다.

출력은 모두 TypeScript 6.0.3에서 `/home/claude/verify/tsref`, `tsref-err`의 실제 모노레포로 얻었다. 버전이 다르면 메시지와 기본값이 다를 수 있다.

## 2. 검증용 모노레포와 composite 계약

구성은 `shared` ← `core` ← `web`의 선형 의존이다. 공통 설정은 base에 두고 패키지 tsconfig는 경로만 가진다.

```jsonc
// tsconfig.base.json
{ "compilerOptions": {
    "target": "ES2022", "module": "NodeNext", "moduleResolution": "NodeNext",
    "strict": true, "skipLibCheck": true,
    "composite": true, "declarationMap": true, "noEmitOnError": true } }

// packages/core/tsconfig.json
{ "extends": "../../tsconfig.base.json",
  "compilerOptions": { "rootDir": "src", "outDir": "dist", "tsBuildInfoFile": "dist/.tsbuildinfo" },
  "include": ["src"],
  "references": [{ "path": "../shared" }] }

// tsconfig.json (솔루션 파일: 자체 소스 없음)
{ "files": [], "references": [
    { "path": "packages/shared" }, { "path": "packages/core" }, { "path": "apps/web" } ] }
```

`composite: true`는 `declaration`과 `incremental`을 기본 활성화하고, 출력 위치를 입력에서 계산할 수 있게 `rootDir` 기본값을 tsconfig 디렉터리로 고정하며, 모든 입력이 `include`/`files`에 속하도록 요구한다. 참조하는 쪽은 `import "../../shared/src/index.js"`를 만나면 shared의 설정으로 `src/index.ts → dist/index.d.ts`를 계산해 대신 읽는다. 이 매핑이 결정적이어야 하므로 위 제약이 필요하다. 실제로 core는 shared의 소스 경로를 import 하지만 `rootDir: src` 위반 오류가 나지 않고 통과한다. `references`가 그 경로를 shared의 출력으로 리다이렉트하기 때문이다.

## 3. 오류 코드 재현

각 오류는 두 개의 작은 프로젝트 a, b를 만들어 의도적으로 설정을 깨뜨려 얻었다. a는 b가 import 하는 `export const x=1;` 하나짜리 프로젝트다.

| 상황 | 실제 출력(요약) |
|---|---|
| 참조 대상 a에 `composite` 없음 | `error TS6306: Referenced project '.../a' must have setting "composite": true.` |
| a와 b가 서로 참조 | `error TS6202: Project references may not form a circular graph. Cycle detected: .../b/tsconfig.json` 다음 줄에 `.../a/tsconfig.json` |
| `references` 없이 a의 소스를 import | `error TS6059: File '.../a/src/i.ts' is not under 'rootDir' '.../b/src'` 와 `error TS6307: File ... is not listed within the file list of project ...` |
| references는 있으나 a를 빌드하기 전에 `tsc -p b` | `error TS6305: Output file '.../a/dist/i.d.ts' has not been built from source file '.../a/src/i.ts'.` |
| 참조 대상 a에 `noEmit: true` | `error TS6310: Referenced project '.../a' may not disable emit.` |

TS6305는 가장 자주 만나는 혼란이다. 참조 대상이 있는 프로젝트는 `tsc -p`가 아니라 `tsc -b`로 빌드해야 한다. `-p`는 참조 대상의 `.d.ts`가 이미 존재한다고 가정하기 때문이다. 또 TS 6.0에서는 `composite`가 아닌 a에서 `rootDir` 없이 `outDir`만 쓰면 `TS5011: The common source directory of 'tsconfig.json' is './src'. The 'rootDir' setting must be explicitly set ...`가 나왔다. 버전에 따라 다를 수 있으므로, 모든 패키지에 `rootDir`을 명시하는 편이 안전하다.

## 4. tsc -b와 up-to-date 판정

처음 빌드하면 세 프로젝트가 위상 순서대로 빌드된다. 아래는 `tsc -b --verbose` 출력에서 타임스탬프 접두사를 제거한 것이다.

```text
Project 'packages/shared/tsconfig.json' is out of date because output file 'packages/shared/dist/.tsbuildinfo' does not exist
Building project '/home/claude/verify/tsref/packages/shared/tsconfig.json'...
Project 'packages/core/tsconfig.json' is out of date because output file 'packages/core/dist/.tsbuildinfo' does not exist
Building project '/home/claude/verify/tsref/packages/core/tsconfig.json'...
Project 'apps/web/tsconfig.json' is out of date because output file 'apps/web/dist/.tsbuildinfo' does not exist
Building project '/home/claude/verify/tsref/apps/web/tsconfig.json'...
```

바로 다시 실행하면 모두 건너뛴다.

```text
Project 'packages/shared/tsconfig.json' is up to date because newest input 'packages/shared/src/index.ts' is older than output 'packages/shared/dist/.tsbuildinfo'
Project 'packages/core/tsconfig.json' is up to date because newest input 'packages/core/src/index.ts' is older than output 'packages/core/dist/.tsbuildinfo'
Project 'apps/web/tsconfig.json' is up to date because newest input 'apps/web/src/main.ts' is older than output 'apps/web/dist/.tsbuildinfo'
```

판정 기준 출력 파일이 `.tsbuildinfo`임에 주목한다. 이제 shared의 함수 **본문만** 바꾸고(`a + b` → `b + a`) 다시 빌드한다.

```text
Project 'packages/shared/tsconfig.json' is out of date because output '.../dist/.tsbuildinfo' is older than input 'packages/shared/src/index.ts'
Building project '/home/claude/verify/tsref/packages/shared/tsconfig.json'...
Project 'packages/core/tsconfig.json' is up to date with .d.ts files from its dependencies
Updating output timestamps of project '/home/claude/verify/tsref/packages/core/tsconfig.json'...
Project 'apps/web/tsconfig.json' is up to date with .d.ts files from its dependencies
Updating output timestamps of project '/home/claude/verify/tsref/apps/web/tsconfig.json'...
```

shared만 다시 컴파일되고 core, web은 "업스트림 `.d.ts`가 그대로"이므로 타임스탬프만 갱신된다. 주석 한 줄을 추가한 경우도 같은 결과였다. 반대로 공개 시그니처를 바꾸면(`add(a, b)` → `add(a, b, c = 0)`) core는 재빌드되지만 web은 다시 타임스탬프 갱신만 한다.

```text
Project 'packages/core/tsconfig.json' is out of date because output '.../core/dist/.tsbuildinfo' is older than input 'packages/shared'
Building project '/home/claude/verify/tsref/packages/core/tsconfig.json'...
Project 'apps/web/tsconfig.json' is up to date with .d.ts files from its dependencies
Updating output timestamps of project '/home/claude/verify/tsref/apps/web/tsconfig.json'...
```

core가 재빌드되었지만 `total`의 시그니처는 그대로여서 변경이 web까지 전파되지 않았다. 변경은 **선언 형태가 바뀌는 곳까지만** 번진다. 이 성질 때문에 공개 함수의 반환 타입을 명시해 두면(`total(xs): number`) 구현 변경이 추론 결과를 흔들어 전파 범위를 넓히는 일을 막을 수 있다.

업스트림에 타입 오류를 넣은 경우(`export const bad: number = "x";`)에도 출력은 `packages/shared/src/index.ts(3,14): error TS2322: ...` 한 줄 뒤에 core, web이 위와 같은 타임스탬프 갱신으로 이어졌다. 이 실험은 `noEmitOnError: true`라 shared의 `.d.ts`가 갱신되지 않았기 때문에 얻은 결과다. 오류가 있을 때 하위 프로젝트가 어떻게 처리되는지는 버전과 옵션에 따라 다르다. TS 6.0.3의 `tsc -b --help`에는 `--stopBuildOnErrors`와 `--noCheck`가 있으므로 필요하면 직접 확인한다.

## 5. .tsbuildinfo 내부

core의 `dist/.tsbuildinfo`는 이 구성에서 13,658바이트였고 대부분이 표준 라이브러리(`lib.*.d.ts`) 항목이다. 최상위 키는 `fileNames`, `fileIdsList`, `fileInfos`, `root`, `options`, `referencedMap`, `latestChangedDtsFile`, `version`이었다. 오류가 없는 빌드라 `semanticDiagnosticsPerFile`은 나타나지 않았다. 프로젝트 파일만 추리면 다음과 같다.

```text
fileNames (끝 두 항목):  "../../shared/dist/index.d.ts", "../src/index.ts"
fileInfos 마지막 항목:   { "version": "845e18cf...", "signature": "63487d36...", "impliedFormat": 1 }
root:                    [65]                      // fileNames 의 인덱스 (../src/index.ts)
referencedMap:           [[65, 1]]                 // 65번 파일이 fileIdsList[1] ([64], shared d.ts)를 import
options:                 { "composite": true, "module": 199, "rootDir": "../src", "outDir": "./", ... }
latestChangedDtsFile:    "./index.d.ts"
version:                 "6.0.3"
```

핵심은 `fileInfos`의 두 해시다. `version`은 파일 텍스트의 해시라서 주석만 바꿔도 달라지고, `signature`는 그 파일이 외부에 보여 주는 선언 형태의 해시다. shared의 `.d.ts`는 core 입장에서는 입력 파일 하나이므로 core의 `fileInfos`에도 shared의 `.d.ts`가 들어 있고, 그 내용 해시가 바뀐 때만 core가 영향을 받는다. `options`는 enum이 숫자로 직렬화되며(`module: 199`은 NodeNext), 옵션이 바뀌면 캐시가 무효화된다. 파일 경로는 `.tsbuildinfo` 위치 기준의 상대경로이고 `version`이 다르면 전체가 재사용되지 않는다. 형식은 버전마다 바뀌어 온 내부 구현이므로 파싱하는 도구를 만들지 말고 개념으로만 이해한다.

위치는 `tsBuildInfoFile`이 있으면 그 경로이고, 위 예제처럼 `dist/.tsbuildinfo`로 두면 `dist`와 생명주기가 같아진다. `tsc -b --clean`은 산출물을 지우며(옵션 `--clean`은 `--verbose`와 함께 쓰면 `error TS6370: Options 'clean' and 'verbose' cannot be combined.`), 이후 `tsc -b --dry`는 세 프로젝트 모두 `A non-dry build would build project ...`로 보고했다.

## 6. 빌드 그래프 설계

그래프는 DAG여야 하며 순환은 TS6202로 막힌다. 순환처럼 보이면 공통 타입을 별도 leaf 프로젝트(`contracts` 등)로 뺀다. 설계 판단 기준은 세 가지다.

첫째, **변경 빈도와 fan-in을 반대로 배치한다.** 많은 프로젝트가 의존하는 노드의 `.d.ts`는 안정적이어야 한다. §4에서 봤듯 시그니처 변경만 하위로 전파되므로, 자주 바뀌는 코드는 소비자 쪽(그래프 상단)에 두고 안정적인 계약은 하단에 둔다.

둘째, **실행 환경과 용도로 나눈다.** 브라우저/Node 코드는 `lib`/`types`가 다르고, 테스트의 `@types/jest` 같은 전역 타입이 라이브러리 소비자에 새면 안 된다. 테스트는 해당 패키지를 참조하는 별도 tsconfig로 둔다. 반대로 너무 잘게 나누면 프로젝트마다 Program 생성과 `lib` 파싱 고정 비용이 붙으므로, 독립 배포나 독립 환경 단위를 기준으로 삼는다. 실제 임계 크기는 측정해서 정한다(§8).

셋째, **package.json 의존성을 단일 원천으로 삼는다.** `references`는 그 파생물이다. 둘이 어긋나면 한쪽은 빌드 순서가, 다른 쪽은 런타임 설치가 틀어진다. 동기화는 손으로 하지 말고 스크립트나 사용 중인 모노레포 도구의 기능으로 자동화한다.

공개 API 경계에서는 반환 타입을 명시하고 `package.json`의 `exports`로 deep import를 막는다. `declarationMap: true`로 에디터의 "정의로 이동"이 `.d.ts`가 아니라 `.ts`로 가게 한다. 앱을 번들러로 빌드한다면 참조 대상은 `noEmit`을 쓸 수 없으므로(TS6310) `emitDeclarationOnly`로 선언만 내보내거나, 참조되지 않는 최종 앱은 composite를 끄고 `noEmit` 검사 전용으로 둔다.

## 7. CI 캐시와 오케스트레이터

`git clone` 직후에는 모든 소스 mtime이 "현재"이므로, 캐시로 `dist`를 복원해도 `tsc -b`는 입력이 출력보다 새롭다고 판단한다. 이때 `.tsbuildinfo`가 함께 복원되어 있으면 내부 증분이 내용 해시를 비교해 검사와 emit을 줄일 수 있다. 실측 시간은 측정하지 않았다. 확실한 규칙은 `dist`와 `.tsbuildinfo`를 한 세트로 캐시하는 것이다. 한쪽만 복원되면 파일 상태와 기록이 어긋난다.

```yaml
# GitHub Actions 예시 구조 (이 노트에서 실행하지 않음)
- uses: actions/cache@v4
  with:
    path: |
      packages/*/dist      # tsBuildInfoFile 이 dist/.tsbuildinfo 이므로 한 세트
      apps/*/dist
    key: tsc-${{ runner.os }}-${{ hashFiles('package-lock.json', 'tsconfig.base.json') }}-${{ github.sha }}
    restore-keys: |
      tsc-${{ runner.os }}-${{ hashFiles('package-lock.json', 'tsconfig.base.json') }}-
- run: npx tsc -b --verbose
```

키에 lockfile(컴파일러 버전)과 base tsconfig 해시를 넣는 이유는 `.tsbuildinfo`가 `version`과 `options` 불일치 시 재사용되지 않기 때문이다. 

Turborepo나 Nx 같은 오케스트레이터와는 역할을 나눈다. `tsc -b`는 타입 수준의 정밀한 증분을, 오케스트레이터는 입력 해시 기반 원격 캐시와 패키지 단위 병렬 실행을 담당한다. 겹쳐 쓸 때는 각 패키지의 `build` 태스크가 `tsc -b`를 실행하고, 오케스트레이터의 캐시 `outputs`에 `dist/**`가 포함되는지(그 안에 `.tsbuildinfo`도 있는지) 확인한다.

## 8. 진단과 의사결정

증분이 기대대로 동작하지 않을 때는 다음 순서로 본다.

| 증상 | 확인 방법 |
|---|---|
| 항상 전체가 재빌드됨 | `tsc -b --verbose`의 "out of date because ..." 사유. `.tsbuildinfo` 미보존, 컴파일러 버전 변경, 옵션 변경 |
| 하위가 계속 재컴파일됨 | 상위 `.d.ts`가 빌드마다 달라지는지(비결정적 코드젠, 타임스탬프 주석) |
| 포함 파일이 많음 | `tsc -p <proj> --listFilesOnly`, `--explainFiles` |
| 검사 시간이 큼 | `--extendedDiagnostics`, `--generateTrace <dir>` |
| TS6305 | 참조 대상을 `tsc -b`로 먼저 빌드 |
| 에디터가 느림 | `disableSourceOfProjectReferenceRedirect`, `disableSolutionSearching`, `disableReferencedProjectLoad`로 로딩 범위 축소(효과는 저장소별로 측정) |

도입 판단은 규모에 달렸다. 패키지가 3~5개이고 빌드가 충분히 빠르면 단일 tsconfig와 `incremental`로 충분하며 references는 과설계일 수 있다. 에디터 지연이나 CI 시간이 실제 문제가 되는 규모에서 Project References를 쓰고, 팀 단위 원격 캐시가 필요해지면 오케스트레이터를 얹는다. 라이브러리 소스를 앱 번들러가 직접 컴파일하는 "internal package" 방식은 설정이 단순하지만 타입 검사가 앱 기준으로 한 번에 일어나 증분 이점이 줄어든다.

## 참고

- TypeScript Handbook, Project References: https://www.typescriptlang.org/docs/handbook/project-references.html
- TSConfig Reference, `composite`, `incremental`, `tsBuildInfoFile`: https://www.typescriptlang.org/tsconfig
- TypeScript Handbook, Compiler Options (`--build` 모드, `--verbose`, `--clean`, `--dry`): https://www.typescriptlang.org/docs/handbook/compiler-options.html
- TypeScript Performance Wiki: https://github.com/microsoft/TypeScript/wiki/Performance
