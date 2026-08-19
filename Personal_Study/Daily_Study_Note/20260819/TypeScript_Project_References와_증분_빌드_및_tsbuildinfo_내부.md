Notion 원본: https://app.notion.com/p/3c15a06fd6d3817baebee5a0543cfaa9?pvs=204

# TypeScript Project References와 증분 빌드 및 tsbuildinfo 내부

> 2026-08-19 신규 주제 · 확장 대상: Javascript

## 학습 목표

- `composite` / `declaration` / `declarationMap` 세 옵션이 각각 무엇을 강제하는지 구분한다
- `.tsbuildinfo` 의 파일 시그니처와 참조 그래프가 재빌드 대상을 결정하는 절차를 추적한다
- `tsc -b` 의 up-to-date 판정 규칙을 이해하고 불필요한 전체 재빌드를 제거한다
- 모노레포에서 Project References 와 패키지 매니저 워크스페이스를 정합적으로 배선한다

## 1. 단일 tsconfig 의 한계

모노레포에서 패키지가 늘면 단일 `tsconfig.json` 으로 전체를 컴파일하는 방식이 무너진다. 파일 하나만 고쳐도 전체 프로그램을 다시 타입 체크하고, 패키지 간 의존 방향을 컴파일러가 강제하지 못해 순환 참조가 조용히 생기며, 에디터가 프로젝트 전체를 하나의 거대한 프로그램으로 로드해 메모리를 수 GB 씩 쓴다.

Project References 는 이를 여러 개의 독립 프로그램으로 쪼갠다. 각 프로젝트는 자신의 소스만 체크하고, 의존 프로젝트는 **소스가 아니라 생성된 `.d.ts`** 를 통해 참조한다. 이 경계가 세 가지를 동시에 준다. 재빌드 범위 축소, 의존 방향 강제, 에디터 메모리 절감이다.

핵심 전환은 "타입 정보의 단위가 소스 파일에서 선언 파일로 바뀐다"는 것이다. 이 때문에 참조되는 프로젝트는 반드시 `.d.ts` 를 만들어야 하고, 그 요구가 `composite: true` 로 표현된다.

## 2. composite 가 강제하는 것

```json
{
  "compilerOptions": {
    "composite": true,
    "declaration": true,
    "declarationMap": true,
    "incremental": true,
    "rootDir": "src",
    "outDir": "dist",
    "tsBuildInfoFile": "dist/.tsbuildinfo"
  },
  "include": ["src/**/*"]
}
```

`composite: true` 를 켜면 컴파일러가 다음을 강제한다.

- `declaration: true` 가 자동으로 켜진다. 끄면 오류다.
- `incremental` 이 기본 true 가 되어 `.tsbuildinfo` 를 남긴다.
- `rootDir` 이 명시되지 않으면 tsconfig 위치로 추론된다.
- `include`/`files` 로 모든 입력 파일이 **명시적으로 열거 가능**해야 한다. 참조하는데 목록에 없는 파일이 있으면 오류다.

마지막 조건이 실무에서 가장 자주 걸린다. `include: ["src/**/*"]` 인데 `src/../shared/util.ts` 를 import 하면 "File is not listed within the file list of project" 오류가 난다. 이건 버그가 아니라 설계다. 프로젝트 경계 밖의 파일을 직접 소비하면 증분 판정이 불가능해지므로, 그 파일은 별도 프로젝트로 승격되어 참조로 연결되어야 한다.

`declarationMap: true` 는 실무 체감이 큰 옵션이다. 이게 없으면 에디터에서 "Go to Definition" 이 `.d.ts` 로 점프해 구현을 볼 수 없다. 켜면 `.d.ts.map` 을 통해 원본 `.ts` 로 정확히 이동한다. 배포 패키지 크기가 조금 늘지만 개발 경험 이득이 훨씬 크다.

## 3. references 배선

소비자 프로젝트가 생산자 프로젝트를 참조한다.

```json
// packages/api/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "composite": true,
    "rootDir": "src",
    "outDir": "dist"
  },
  "include": ["src/**/*"],
  "references": [
    { "path": "../core" },
    { "path": "../shared" }
  ]
}
```

`path` 는 tsconfig 파일 자체 또는 그것을 담은 디렉터리를 가리킨다. 참조된 프로젝트는 반드시 `composite: true` 여야 한다.

모듈 해석은 별도 문제다. `references` 는 빌드 순서와 타입 소스를 알려줄 뿐, `import { X } from '@myorg/core'` 라는 경로를 해석해 주지는 않는다. 두 가지 중 하나가 필요하다.

```json
// 방법 1: paths 매핑 (tsconfig.base.json)
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@myorg/core": ["packages/core/src/index.ts"],
      "@myorg/shared": ["packages/shared/src/index.ts"]
    }
  }
}
```

`paths` 를 소스로 직접 매핑하면 에디터에서 항상 최신 구현이 보이지만, `tsc -b` 는 여전히 `.d.ts` 를 쓰므로 두 경로가 미묘하게 달라진다. 더 정합적인 방법은 패키지의 `package.json` 이 빌드 산출물을 가리키고, 워크스페이스 심볼릭 링크로 해석되게 두는 것이다.

```json
// packages/core/package.json
{
  "name": "@myorg/core",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    }
  }
}
```

이 경우 최초 1회 빌드 전에는 `dist` 가 없어 에디터가 타입을 못 찾는다. `pnpm -r build` 를 부트스트랩 단계에 넣어 해결한다. 최근 TypeScript 는 `customConditions` 를 통해 개발 시 소스를, 배포 시 산출물을 가리키는 이중 배선도 지원한다.

```json
// tsconfig: 개발용
{ "compilerOptions": { "moduleResolution": "bundler", "customConditions": ["@myorg/source"] } }

// package.json
{
  "exports": {
    ".": {
      "@myorg/source": "./src/index.ts",
      "types": "./dist/index.d.ts",
      "default": "./dist/index.js"
    }
  }
}
```

번들러(Vite, esbuild)에도 동일 condition 을 주면 개발 중에는 소스를 직접 읽어 빌드 없이 HMR 이 돌고, 배포 빌드는 `.d.ts` 를 쓴다.

## 4. tsbuildinfo 의 구조

`.tsbuildinfo` 는 증분 빌드의 상태 저장소다. 내부는 JSON 이고 주요 필드는 다음과 같다.

```json
{
  "program": {
    "fileNames": ["../src/a.ts", "../src/b.ts", "../node_modules/..."],
    "fileInfos": [
      { "version": "ab12cd...", "signature": "ef34gh...", "affectsGlobalScope": false }
    ],
    "referencedMap": { "1": [2, 3] },
    "exportedModulesMap": { "1": [2] },
    "semanticDiagnosticsPerFile": [1, 2],
    "options": { "composite": true, "strict": true }
  },
  "version": "5.6.2"
}
```

핵심은 `version` 과 `signature` 의 구분이다.

- `version` 은 **소스 파일 내용의 해시**다. 파일을 저장만 해도 바뀐다.
- `signature` 는 그 파일이 내보내는 **선언(.d.ts) 내용의 해시**다. 함수 본문만 고치고 시그니처가 그대로면 변하지 않는다.

재빌드 판정은 이렇게 흐른다. 파일 A 를 수정하면 A 의 `version` 이 바뀌어 A 를 다시 컴파일한다. 그 결과 A 의 새 `signature` 를 계산하는데, 이전과 같으면 **A 를 참조하는 파일들은 다시 체크하지 않는다**. 시그니처가 바뀌었으면 `referencedMap` 을 역추적해 A 를 참조하는 모든 파일을 다시 체크하고, 그 파일들의 시그니처 변화를 다시 전파한다.

그래서 함수 본문만 고치는 변경은 그 파일 하나만 재컴파일되고, `export` 하는 인터페이스를 고치면 하위 전체가 재컴파일된다. 실무 최적화 지침이 여기서 나온다. **타입 선언과 구현을 같은 파일에 두면 구현 수정이 시그니처 변경으로 오인될 확률이 올라간다.** 자주 바뀌는 구현과 안정적인 타입 선언을 파일 단위로 분리하면 증분 효율이 올라간다.

`affectsGlobalScope` 도 중요하다. `declare global` 이나 전역 타입 선언을 포함한 파일은 이 플래그가 켜지고, 이 파일이 바뀌면 **프로젝트 전체가 재체크**된다. 앰비언트 선언 파일을 자주 건드리는 구조는 증분 빌드의 이득을 통째로 날린다.

`options` 필드는 컴파일러 옵션 스냅샷이다. tsconfig 를 하나라도 바꾸면 이 해시가 달라져 전체 재빌드가 발생한다. CI 에서 환경별로 옵션을 바꿔가며 빌드하면 캐시가 매번 무효화되므로, 옵션 조합 수를 최소화하는 것이 좋다.

## 5. tsc -b 의 up-to-date 판정

`tsc -b`(build mode)는 참조 그래프를 위상 정렬해 의존 순서대로 빌드하고, 각 프로젝트가 최신인지 판정한다.

```bash
tsc -b packages/api            # 의존 프로젝트까지 순서대로 빌드
tsc -b --verbose               # 각 프로젝트의 up-to-date 판정 이유 출력
tsc -b --dry                   # 실제 빌드 없이 무엇을 빌드할지만 표시
tsc -b --clean                 # 산출물 제거
tsc -b --watch                 # 감시 모드
```

`--verbose` 출력은 튜닝의 출발점이다.

```text
Project 'packages/core/tsconfig.json' is out of date because output file
  'packages/core/dist/index.js' does not exist

Project 'packages/api/tsconfig.json' is up to date with .d.ts files from
  its dependencies
```

두 번째 메시지가 핵심 최적화다. `core` 를 다시 빌드했지만 생성된 `.d.ts` 내용이 이전과 동일하면, `api` 는 재빌드하지 않는다. 이 판정 덕분에 "구현만 바뀐 라이브러리"의 변경이 상위 패키지로 전파되지 않는다.

판정 기준은 타임스탬프와 내용 두 가지다. 출력 파일이 입력보다 오래되었으면 재빌드하고, 재빌드 결과 `.d.ts` 내용이 같으면 상위 전파를 끊는다. CI 에서 git checkout 은 모든 파일의 mtime 을 현재 시각으로 만들므로, 타임스탬프만으로는 항상 stale 로 판정된다. 그래서 CI 캐시는 `.tsbuildinfo` 와 `dist` 를 함께 복원해야 의미가 있다.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      packages/*/dist
      packages/*/tsconfig.tsbuildinfo
    key: tsbuild-${{ runner.os }}-${{ hashFiles('pnpm-lock.yaml') }}-${{ github.sha }}
    restore-keys: |
      tsbuild-${{ runner.os }}-${{ hashFiles('pnpm-lock.yaml') }}-
```

## 6. 흔한 실패 모드

| 증상 | 원인 | 조치 |
|---|---|---|
| 매번 전체 재빌드 | 전역 스코프 영향 파일 수정 | `declare global` 을 별도 안정 파일로 격리 |
| `.tsbuildinfo` 무시됨 | tsconfig 옵션 변경 | 옵션 조합 수 최소화, base 설정 공유 |
| CI 에서만 느림 | mtime 리셋 + 캐시 미복원 | dist + tsbuildinfo 동시 캐싱 |
| 에디터가 옛 타입 표시 | `.d.ts` 미갱신 | watch 빌드 또는 customConditions 로 소스 직결 |
| TS6059 rootDir 오류 | 프로젝트 밖 파일 import | 해당 파일을 별도 프로젝트로 승격 |
| 순환 참조 오류 | references 사이클 | 공통 타입을 하위 프로젝트로 추출 |

순환 참조는 Project References 가 명시적으로 금지한다. `A → B → A` 는 빌드 순서를 정할 수 없으므로 오류다. 단일 tsconfig 에서는 이런 순환이 조용히 허용되어 나중에 번들 순환 참조 버그로 터지는데, References 로 전환하면 이 문제가 컴파일 타임에 드러난다. 이것 자체가 도입 이유 중 하나다.

해결은 공통 부분을 추출하는 것이다. `A` 와 `B` 가 서로 필요한 타입이 있다면 그 타입만 `types` 프로젝트로 내리고 둘 다 그것을 참조하게 만든다.

## 7. 솔루션 스타일 루트 구성

에디터가 모노레포 전체를 하나로 인식하게 하려면 루트에 파일이 없는 "솔루션" tsconfig 를 둔다.

```json
// tsconfig.json (루트)
{
  "files": [],
  "references": [
    { "path": "packages/shared" },
    { "path": "packages/core" },
    { "path": "packages/api" },
    { "path": "apps/web" }
  ]
}
```

`files: []` 가 핵심이다. 루트 자체는 아무 파일도 컴파일하지 않고 참조만 나열한다. 이러면 `tsc -b` 한 번으로 전체가 위상 순서대로 빌드되고, VS Code 는 각 파일이 속한 프로젝트를 자동으로 판별해 필요한 프로그램만 메모리에 올린다.

빌드 전용과 타입 체크 전용을 나누는 구성도 흔하다. 애플리케이션 코드는 `tsconfig.json`, 테스트는 `tsconfig.test.json` 으로 분리하고 테스트가 앱을 참조한다. 테스트 파일이 배포 산출물의 `.d.ts` 에 섞이는 사고를 구조적으로 막는다.

```json
// packages/core/tsconfig.test.json
{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "composite": true,
    "noEmit": false,
    "outDir": "dist-test",
    "rootDir": "."
  },
  "include": ["src/**/*", "test/**/*"],
  "references": [{ "path": "./tsconfig.json" }]
}
```

## 8. 도입 효과 측정

전환 전후를 같은 지표로 재야 판단할 수 있다.

```bash
# 전체 빌드 시간
time npx tsc -b --clean && time npx tsc -b

# 단일 파일 수정 후 증분 시간
touch packages/core/src/service.ts && time npx tsc -b

# 타입 체크 비용 상세
npx tsc -b --extendedDiagnostics
```

`--extendedDiagnostics` 의 `Check time`, `Instantiations`, `Memory used` 세 항목을 기록한다. 일반적으로 패키지 10개 규모 모노레포에서 전체 빌드는 References 도입으로 오히려 5~15% 느려지고(프로젝트 간 오버헤드), **증분 빌드는 한 자릿수 배 빨라진다**. 즉 CI 의 클린 빌드에는 이득이 없거나 손해이고, 로컬 개발과 캐시가 있는 CI 에서 이득이 난다. 이 트레이드오프를 모르면 "도입했는데 CI 가 안 빨라졌다"는 잘못된 결론에 이른다.

에디터 메모리는 명확히 개선된다. 단일 프로그램으로 3GB 를 쓰던 프로젝트가 References 분할 후 활성 프로젝트만 로드해 수백 MB 로 떨어지는 사례가 흔하다. tsserver 응답 지연이 개발 생산성에 미치는 영향이 크므로, 빌드 시간보다 이쪽을 도입 근거로 삼는 편이 현실적이다.

## 참고

- TypeScript Handbook — Project References
- TypeScript Handbook — Configuring Watch, Compiler Options (`composite`, `incremental`, `tsBuildInfoFile`)
- microsoft/TypeScript Wiki — Performance
- TypeScript Release Notes 5.0 — `--moduleResolution bundler`, `customConditions`
- pnpm Documentation — Workspaces
