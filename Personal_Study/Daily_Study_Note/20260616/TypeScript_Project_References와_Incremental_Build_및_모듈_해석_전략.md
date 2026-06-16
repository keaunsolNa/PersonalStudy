Notion 원본: https://www.notion.so/3815a06fd6d381f1b3f1f5eee0871c2c

# TypeScript Project References와 Incremental Build 및 모듈 해석 전략

> 2026-06-16 신규 주제 · 확장 대상: Javascript

## 학습 목표

- Project References 의 구조(composite, declaration, references)와 빌드 그래프를 설명한다
- `.tsbuildinfo` 기반 incremental build 와 `tsc --build`(빌드 모드)의 동작을 구분한다
- moduleResolution 옵션(node16/nodenext/bundler)과 package.json exports 의 상호작용을 이해한다
- 모노레포에서 빌드 성능과 타입 격리를 동시에 잡는 전략을 판단한다

## 1. Project References — 큰 코드베이스를 조각으로 나누기

단일 `tsconfig.json` 으로 거대한 코드베이스를 컴파일하면, 한 파일만 바뀌어도 전체를 다시 검사할 위험이 크고 모듈 간 경계가 흐려진다. **Project References**(TS 3.0+)는 프로젝트를 여러 하위 프로젝트로 쪼개고, 그들 사이의 의존 관계를 명시해 **부분 빌드**와 **타입 경계**를 동시에 얻는다.

핵심 설정은 셋이다. 참조되는(=라이브러리) 프로젝트는 `composite: true` 를 켜야 하고(이러면 `declaration: true` 가 강제되어 `.d.ts` 와 `.tsbuildinfo` 가 생성됨), 참조하는 쪽은 `references` 배열에 의존 프로젝트 경로를 적는다.

```jsonc
// packages/core/tsconfig.json (라이브러리)
{
  "compilerOptions": {
    "composite": true,        // 참조 대상이 되려면 필수
    "declaration": true,      // composite 가 자동 강제
    "declarationMap": true,   // d.ts → 소스 점프(go-to-definition) 지원
    "outDir": "dist",
    "rootDir": "src"
  }
}

// packages/api/tsconfig.json (core 를 사용)
{
  "compilerOptions": { "composite": true, "outDir": "dist" },
  "references": [{ "path": "../core" }]
}
```

참조하는 쪽은 의존 프로젝트의 **소스가 아니라 빌드 산출물(.d.ts)** 을 본다. 즉 core 가 먼저 빌드되어 타입 선언이 나와 있어야 api 가 컴파일된다. 이 강제된 순서가 곳 빌드 그래프다.

## 2. 빌드 모드 — `tsc --build` 와 위상 정렬

Project References 는 일반 `tsc` 가 아니라 **빌드 모드** `tsc --build`(= `tsc -b`)로 빌드한다. 빌드 모드는 references 그래프를 **위상 정렬(topological sort)** 해 의존성 순서대로, 그리고 **변경된 프로젝트만** 다시 빌드한다.

```bash
tsc -b packages/api            # api 와 그 의존(core)을 순서대로
tsc -b --verbose               # 어떤 프로젝트가 up-to-date 인지/재빌드되는지 출력
tsc -b --clean                 # 산출물 정리
tsc -b -w                      # watch 모드
```

빌드 모드는 각 프로젝트의 `.tsbuildinfo`(이전 빌드의 파일 해시/시그니처)와 산출물의 타임스탬프를 비교해 "이 프로젝트는 최신인가" 를 판단한다. core 가 안 바뀜으면 core 를 건너뛰고 api 만 검사하므로, 큰 모노레포에서 재빌드 시간이 극적으로 준다. `--verbose` 출력의 "Project 'X' is up to date" 메시지가 이 스킵을 확인하는 방법이다.

## 3. Incremental Build 와 `.tsbuildinfo`

`incremental: true`(또는 composite 가 자동으로 켬)는 빌드 정보를 `.tsbuildinfo` 파일에 저장해, 다음 빌드에서 **바뀐 파일과 그 영향 범위만** 다시 검사한다. composite 가 "프로젝트 단위" 스킵이라면, incremental 은 "파일 단위" 스킵이다. 둘은 함께 작동한다.

```jsonc
{
  "compilerOptions": {
    "incremental": true,
    "tsBuildInfoFile": "./.cache/api.tsbuildinfo" // 위치 지정(기본은 outDir)
  }
}
```

주의점: `.tsbuildinfo` 는 빌드 캐시이므로 CI 캐시에 포함시키면 클린 빌드 대비 큰 시간 이득을 본다. 단 컴파일러 버전이나 옵션이 바뀌면 캐시가 무효화되어 전체 재빌드가 일어난다. 또 산출물(.d.ts, .js)을 지우면서 `.tsbuildinfo` 만 남기면 컴파일러가 "최신" 으로 오판할 수 있으니, 클린은 `tsc -b --clean` 으로 일관되게 한다.

## 4. moduleResolution — node16 / nodenext / bundler

모듈 해석은 "`import x from 'y'` 에서 y 의 실제 파일을 어떻게 찾는가" 의 규칙이다. 현대 TypeScript 의 주요 옵션은 다음과 같다.

`node16`/`nodenext` 는 **Node.js 의 ESM/CJS 규칙을 그대로** 따른다. package.json 의 `"type"` 필드, `exports`/`imports` 맵, 그리고 **상대 임포트에 확장자(.js)를 요구** 하는 ESM 규칙까지 강제한다. 라이브러리를 publish 하거나 Node 에서 직접 실행할 코드라면 이 옵션이 정확하다. `bundler` 는 Vite/webpack/esbuild 같은 번들러가 해석을 책임지는 환경용으로, 확장자 없는 임포트를 허용하고 exports 맵은 보되 Node 의 엄격한 ESM 규칙은 완화한다.

```jsonc
// Node 에서 실행/배포되는 패키지
{ "compilerOptions": { "module": "nodenext", "moduleResolution": "nodenext" } }

// 번들러가 처리하는 앱 (Vite 등)
{ "compilerOptions": { "module": "esnext", "moduleResolution": "bundler" } }
```

흔한 함정: `nodenext` 에서 ESM 패키지인데 상대 임포트에 `.js` 를 안 붙이면 에러가 난다(`import './util'` → `import './util.js'`). TypeScript 소스가 `.ts` 여도 **출력될 `.js` 기준으로** 확장자를 적는 게 규칙이다. 처음 ESM 으로 전환할 때 가장 많이 부딪히는 지점이다.

## 5. package.json exports 와 타입 — 조건부 export

라이브러리를 배포할 때 `exports` 맵은 진입점과 타입 선언 위치를 함께 정의한다. 소비자의 moduleResolution 이 node16/nodenext/bundler 면 이 맵을 읽는다.

```jsonc
// 라이브러리 package.json
{
  "name": "@acme/core",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    },
    "./utils": {
      "types": "./dist/utils.d.ts",
      "import": "./dist/utils.js"
    }
  }
}
```

`"types"` 조건은 **반드시 다른 조건보다 먼저** 와야 하고, 각 진입점마다 타입을 매핑해야 소비자가 서브패스 임포트(`@acme/core/utils`)에서도 타입을 받는다. 듀얼 패키지(ESM+CJS)를 낼 때는 import/require 별로 다른 `.d.ts`(또는 `.d.cts`)가 필요할 수 있다 — 이른바 "dual package hazard". `attw`(Are The Types Wrong) 같은 도구로 배포 전 exports/types 정합성을 검증하는 것이 안전하다.

## 6. paths 별칭과 references 의 차이

모노레포에서 `tsconfig` 의 `paths` 로 `@acme/core` → `../core/src` 같은 별칭을 거는 방식과, Project References 로 묶는 방식은 다르다.

| 항목 | `paths` 별칭 | Project References |
|---|---|---|
| 빌드 격리 | 없음(소스 직접 참조) | 있음(.d.ts 경계) |
| 부분 재빌드 | 불가(전체 한 덩어리) | 가능(프로젝트 단위 스킵) |
| 런타임 해석 | 별도 도구 필요(tsc-alias 등) | 산출물이 실제 경로 |
| 적합 상황 | 소규모/번들러 위임 | 대규모 모노레포, 라이브러리 배포 |

`paths` 는 타입 체크 시 경로만 바꿔줄 뿐 출력 `.js` 의 import 경로는 안 바꿔다는 함정이 있다(런타임에 모듈을 못 찾음). 그래서 번들러가 없으면 별도 후처리가 필요하다. 반면 references 는 각 프로젝트가 실제 `dist` 를 내고 그 경로를 가리키므로 런타임 정합성이 자연스럽다. 다만 references 는 설정이 무겁고 모든 참조 대상에 `composite` 를 강제하므로, 프로젝트 규모와 배포 요구에 따라 고른다.

## 7. 모노레포 빌드 전략 정리

대규모 모노레포에서 권장 조합은 이렇다. 각 패키지를 composite 프로젝트로 만들고 의존을 references 로 연결한 뒤, 루트에 모든 패키지를 references 로 모은 "솔루션 tsconfig" 를 둔다. `tsc -b` 한 번으로 전체를 위상 정렬해 변경분만 빌드하고, CI 에서 `.tsbuildinfo` 를 캐시해 재빌드를 가속한다. 타입 체크(`tsc -b --noEmit` 성격)와 실제 트랜스파일(번들러/esbuild)을 분리하면, 빠른 트랜스파일은 esbuild 에 맡기고 정확한 타입 검증만 `tsc` 가 담당하는 하이브리드가 가능하다 — esbuild 는 타입을 안 보므로 `tsc` 의 타입 체크가 여전히 안전망 역할을 한다. moduleResolution 은 배포 패키지면 nodenext, 앱이면 bundler 로 두고, exports 맵의 types 조건을 `attw` 로 검증하면 소비자 측 타입 깨짐을 사전에 막는다. 핵심 원칙은 "빌드 그래프를 명시하고(.tsbuildinfo 로) 안 바뀐 것은 다시 안 하기" 와 "모듈 해석을 실행 환경과 일치시키기" 두 가지다.

## 8. isolatedModules 와 트랜스파일러 친화 설정

빠른 빌드를 위해 타입 체크(`tsc`)와 트랜스파일(esbuild/swc/Babel)을 분리하는 하이브리드(§7)에서는, 트랜스파일러가 **파일 하나씩 독립적으로** 변환한다는 제약이 생긴다. esbuild 는 타입 정보를 안 보고 한 파일만 보므로, 다른 파일의 타입을 알아야만 올바르게 변환되는 구문(예: `const enum`, 타입과 값이 섞인 re-export)을 잘못 처리할 수 있다. `isolatedModules: true` 는 이런 "파일 단독 변환이 불가능한 패턴" 을 컴파일 타임에 에러로 잡아준다.

```jsonc
{
  "compilerOptions": {
    "isolatedModules": true,        // 단일 파일 트랜스파일 안전성 보장
    "verbatimModuleSyntax": true    // import/export 의 type-only 를 명시적으로
  }
}
```

`verbatimModuleSyntax`(TS 5.0+)는 `import type`/`export type` 을 명시적으로 강제해, 타입만 쓰이는 import 가 런타임 코드에 남지 않도록(또는 의도대로 남도록) 보장한다. 이는 트랜스파일러가 "이 import 는 타입이라 지워도 된다" 를 타입 분석 없이 알 수 있게 해, ESM/CJS 상호운용에서 흔한 버그(부수효과 import 가 사라지거나, 타입 import 가 런타임에 남아 순환 참조를 일으킴)를 예방한다. Project References + 분리 트랜스파일 구성에서 이 두 옵션은 사실상 필수다.

## 9. 함정 모음과 디버깅

Project References 운용에서 반복되는 함정을 정리한다. 첫째, **참조 대상이 빌드 안 됨** — `tsc -b` 가 아니라 그냥 `tsc` 로 돌리면 references 가 무시되어 의존 프로젝트의 `.d.ts` 가 없거나 낡은 채로 에러가 난다. 빌드 모드 사용 여부를 먼저 확인한다. 둘째, **`.tsbuildinfo` 와 산출물 불일치** — 산출물을 수동 삭제하면 컴파일러가 "최신" 으로 오판하므로 정리는 항상 `tsc -b --clean`. 셋째, **순환 참조** — references 그래프에 사이클이 있으면 위상 정렬이 불가능해 빌드가 실패한다. `tsc -b --verbose` 로 빌드 순서를 출력해 사이클을 추적한다. 넷째, **declarationMap 누락** — 이걸 안 켜면 의존 패키지로 go-to-definition 시 `.d.ts` 로만 점프하고 원본 `.ts` 로 못 간다. 모노레포 DX 를 위해 라이브러리 쪽에 꾭 켜다.

```bash
tsc -b --verbose              # 각 프로젝트 up-to-date/rebuild 사유 출력
tsc -b --dry                  # 실제 빌드 없이 무엇이 빌드될지 미리보기
tsc --showConfig              # 상속·확장 적용된 최종 tsconfig 확인
```

마지막으로 모듈 해석 디버깅에는 `tsc --traceResolution` 이 결정적이다 — 특정 import 가 어떤 후보 경로를 순서대로 탐색했고 왜 실패했는지 전체 과정을 출력한다. `exports` 맵이나 `paths` 별칭이 의도대로 안 잡힐 때, 추측 대신 이 출력으로 정확한 실패 지점을 짚는 것이 가장 빠른 해결법이다.

## 참고

- TypeScript Handbook — Project References, `tsc --build`
- TypeScript Handbook — Module Resolution (node16/nodenext/bundler)
- Node.js 문서 — Packages: `exports`/`imports` 조건부 진입점
- Are The Types Wrong? (`@arethetypeswrong/cli`) — 배포 타입 정합성 검증
