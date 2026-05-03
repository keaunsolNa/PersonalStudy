Notion 원본: https://www.notion.so/3555a06fd6d38157b81ddf848a846391

# TypeScript moduleResolution — NodeNext, Bundler, ESM ↔ CJS 상호운용 실전

> 2026-05-03 신규 주제 · 확장 대상: TypeScript Compiler API, ts-morph

## 학습 목표

- TypeScript 가 import 문을 만났을 때 어떤 알고리즘으로 실제 파일을 찾아내는지 단계별로 추적한다
- `moduleResolution` 옵션 5종 (`Classic`, `Node`, `Node10`, `Node16`, `NodeNext`, `Bundler`) 의 차이를 동작 결과로 비교한다
- `package.json` 의 `exports`, `imports`, `type` 필드가 해상도에 미치는 영향을 케이스별로 분석한다
- ESM 패키지를 CJS 프로젝트에서 (혹은 그 반대로) 안전하게 사용하기 위한 옵션 조합을 결정한다

## 1. 모듈 해석은 두 단계로 나뉜다

먼저 큰 그림. TypeScript 의 import 처리는 본질적으로 두 단계다.

1. **모듈 해석(module resolution)**: `import x from "lodash/fp"` 라는 문자열을 실제 디스크상의 `.ts/.d.ts/.js` 파일로 매핑
2. **모듈 형식(module emit format)**: 컴파일된 결과물을 ESM(`import/export`) 또는 CJS(`require`) 중 무엇으로 출력할지 결정

`moduleResolution` 은 1단계에 관여하고, `module` 옵션은 2단계에 관여한다. 둘은 독립이지만 `Node16/NodeNext` 처럼 일부 옵션은 두 단계를 묶어 결정한다. 옵션 조합을 잘못 짜면 "해석은 되는데 emit 한 결과는 런타임에 깨지는" 상황이 생긴다.

## 2. 옵션 5종 비교 표

| 옵션 | TS 도입 | 대상 런타임 | exports/imports 지원 | .js 확장자 | 권장 용도 |
|---|---|---|---|---|---|
| `Classic` | 1.0 | 레거시 | 없음 | 자동 추가 | 사실상 사용 금지 |
| `Node` (= `Node10`) | 2.0 | Node 10 ~ 14 | 일부 | 자동 추가 | 구 CJS 프로젝트 유지보수 |
| `Node16` | 4.7 | Node 16+ | 전체 | **명시 필수** | Node 직접 실행 ESM/CJS 혼합 |
| `NodeNext` | 4.7 | Node latest | 전체 | **명시 필수** | 최신 Node + ESM 표준 |
| `Bundler` | 5.0 | webpack/vite/esbuild | 전체 | 선택 | 번들러 사용 프론트엔드 |

핵심 변화는 4.7 (`Node16`) 부터다. 이전까지 TypeScript 는 `import './foo'` 가 `./foo.ts` 로 컴파일되어도 emit 시 `./foo.js` 로 자동 변환해 주는 "관용적" 동작을 했지만, ESM 표준은 import specifier 에 확장자를 요구한다. `NodeNext` 는 이 표준에 맞춰 `import './foo.js'` 를 사용자가 직접 쓰게 한다 — TypeScript 소스에 `.js` 가 들어가는 어색함이 여기서 나온다.

## 3. Node16/NodeNext 의 알고리즘 흐름

`import 'pkg/sub'` 같은 specifier 를 만났을 때 NodeNext 가 수행하는 단계를 의사코드로 추적해보자.

```
1. specifier 가 "./" 나 "../" 로 시작하는 상대 경로인가?
   yes → 현재 파일 위치 기준으로 .ts → .tsx → .d.ts → .js 순서로 시도
        파일이 ESM 컨텍스트면 .js 확장자가 명시되지 않으면 거부

2. specifier 가 "node:fs" 같은 빌트인인가?
   yes → @types/node 의 d.ts 에서 해석

3. node_modules/<pkg>/package.json 을 읽는다
   - "exports" 필드가 있으면 그 안의 매핑만 사용 (조건: "import"/"require"/"types"/"node")
   - "exports" 가 없으면 legacy: "main"/"types"/"typings" 폴백
   - "type": "module" 이면 모든 .js 를 ESM 으로 해석
   - 디렉터리 내 package.json 이 가장 가까운 것이 적용됨 (nearest ancestor)

4. types 조건으로 해석 후, 해당 파일의 emit 형식을 결정
   - 파일 확장자 .mts → ESM
   - 파일 확장자 .cts → CJS
   - .ts/.tsx → 가장 가까운 package.json "type" 필드 따라감
```

가장 자주 만나는 함정은 3번의 `exports` 매핑이다. 어떤 패키지가 `exports` 를 정의했는데 거기에 `./sub` 가 명시되지 않았다면 `import 'pkg/sub'` 는 *해석 자체가 실패* 한다. legacy `main` 만 보던 시절에는 디렉터리 안의 어떤 파일이든 접근할 수 있었지만 ESM 표준은 패키지 내부 구조를 캡슐화한다.

## 4. exports 조건부 매핑 케이스 스터디

라이브러리 측의 `package.json` 을 예로 든다.

```json
{
  "name": "my-lib",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    },
    "./client": {
      "types": "./dist/client.d.ts",
      "import": "./dist/client.mjs",
      "require": "./dist/client.cjs"
    },
    "./package.json": "./package.json"
  }
}
```

`moduleResolution: "Bundler"` 또는 `NodeNext` 환경에서 다음 import 가 어떻게 매핑되는지 정리한다.

| 사용자 측 import | NodeNext (ESM) | NodeNext (CJS) | Bundler |
|---|---|---|---|
| `import x from 'my-lib'` | `dist/index.mjs` + `index.d.ts` | `dist/index.cjs` + `index.d.ts` | `dist/index.mjs` + `index.d.ts` |
| `import x from 'my-lib/client'` | `dist/client.mjs` | `dist/client.cjs` | `dist/client.mjs` |
| `import x from 'my-lib/internal'` | **해석 실패** | **해석 실패** | **해석 실패** |
| `require('my-lib/package.json')` | OK (조건 매핑 명시) | OK | OK |

라이브러리 작성자는 `types` 조건을 가장 먼저 두는 것이 권장된다. 조건 우선순위는 *위에서 아래로* 매칭되며 첫 매치를 사용하기 때문에 `types` 가 `import/require` 뒤에 있으면 무시된다. 이 규칙은 Node 의 ESM Resolution 알고리즘에 정의되어 있다.

## 5. dual package hazard 와 회피

ESM 과 CJS 양쪽으로 동일 패키지를 export 할 때, 사용자 코드가 직간접적으로 양쪽 모두를 import 하면 **동일 클래스의 두 개의 다른 인스턴스** 가 만들어지는 문제가 생긴다. 모듈 캐시는 형식별로 분리되기 때문이다. 다음과 같은 상황이다.

```
app(esm) → my-lib (esm 분기)         ↘
                                       서로 다른 두 인스턴스
app(esm) → some-cjs-dep → my-lib(cjs) ↗
```

`instanceof` 검사가 false 가 되거나 모듈 레벨 싱글톤이 두 벌이 되어 버린다. 회피 전략:

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "node": "./dist/index.cjs",
      "default": "./dist/index.mjs"
    }
  }
}
```

Node 환경에서는 무조건 CJS 만 노출하도록 매핑하면 dual instance 가 생기지 않는다. 또는 라이브러리 내부 상태가 없는(stateless) ESM-only 로 설계하는 것이 가장 깔끔하다 (Sindre Sorhus 의 ESM-only 정책 참고).

## 6. .mts / .cts / 가까운 package.json

같은 디렉터리의 `.ts` 파일이라도 *가까운 package.json 의 `type` 필드* 에 따라 ESM 또는 CJS 로 emit 된다. 이 동작은 Node 의 동작을 그대로 모방한 결과로, 동일한 코드가 디렉터리 위치만 바꿔도 다른 형식으로 컴파일될 수 있어 처음에는 헷갈린다.

```
/repo
  package.json          → { "type": "module" }
  src/
    a.ts                → ESM
  legacy/
    package.json        → { "type": "commonjs" }
    b.ts                → CJS
    c.mts               → 명시적 ESM (확장자 우선)
    d.cts               → 명시적 CJS
```

복잡한 모노레포에서 일부만 CJS 로 남겨야 할 때는 위처럼 폴더에 자체 `package.json` 을 두는 것이 가장 명료하다. 또는 `.mts`/`.cts` 확장자로 파일별 강제도 가능하다.

## 7. Bundler 모드는 무엇이 다른가

`Bundler` 는 TypeScript 5.0 에 추가됐고, "TypeScript 만 만족시키면 된다 — 실제 import 는 webpack/vite/esbuild 가 처리한다" 는 전제로 동작한다.

특징:

- `package.json` 의 `exports` 를 *읽긴 하지만* `import`/`require` 조건 분기를 하지 않고 `default` 로 폴백
- `.ts` import 시 `.js` 확장자 강제하지 않음 (번들러가 알아서 함)
- `allowImportingTsExtensions: true` 와 함께 `import './x.ts'` 도 가능
- emit 은 사실상 권장하지 않음 — `noEmit: true` 를 동반하는 게 일반적

따라서 React/Next.js/Vite 프론트엔드 프로젝트에서는 `Bundler` 가 가장 자연스럽다. 반대로 Node 직접 실행하는 백엔드 서비스는 `NodeNext` 를 쓰는 것이 정석이다.

## 8. 케이스별 권장 tsconfig

다음 표는 프로젝트 형태별로 적용 가능한 `tsconfig.compilerOptions` 조각이다.

| 프로젝트 | module | moduleResolution | type(package.json) | 비고 |
|---|---|---|---|---|
| Vite + React | `ESNext` | `Bundler` | `module` | `noEmit: true` |
| Next.js | `ESNext` | `Bundler` | `module` | Next 가 transpile 담당 |
| Node 백엔드 ESM | `NodeNext` | `NodeNext` | `module` | import 에 `.js` 명시 |
| Node 백엔드 CJS 레거시 | `CommonJS` | `Node` (or `Node10`) | `commonjs` | 변환 부담 X |
| 라이브러리 (dual publish) | `NodeNext` | `NodeNext` | `module` | tsup/unbuild 로 mjs+cjs |
| Deno | `ESNext` | `Bundler` 또는 `NodeNext` | n/a | URL import 별도 |

라이브러리는 `tshy` 같은 도구가 `.mts` ↔ `.cts` dual emit 을 자동화하므로 직접 두 번 빌드하지 말고 도구를 쓴다.

## 9. tsc 로 trace 떠보기

해석이 의도대로 되지 않을 때 `--traceResolution` 옵션이 가장 강력하다.

```bash
tsc --traceResolution --noEmit | grep "my-lib"
```

출력 예시:

```
======== Resolving module 'my-lib/client' from '/repo/src/index.ts'. ========
Module resolution kind is not specified, using 'NodeNext'.
Found 'package.json' at '/repo/node_modules/my-lib/package.json'.
Saw non-matching condition 'require'.
Matched 'exports' condition 'import'.
File '/repo/node_modules/my-lib/dist/client.d.ts' exists.
======== Module name 'my-lib/client' was successfully resolved to '/repo/node_modules/my-lib/dist/client.d.ts' with Package ID 'my-lib/dist/client.d.ts@1.0.0'. ========
```

조건 매칭 순서, 실패한 조건, 최종 선택된 파일이 한눈에 보인다. CI 에 `traceResolution` 결과를 한 번 떠 두면 라이브러리 업그레이드 시 회귀를 빠르게 잡을 수 있다.

## 10. 자주 만나는 오류 5종과 처방

| 증상 | 원인 | 해결 |
|---|---|---|
| `Cannot find module 'pkg/sub' or its corresponding type declarations` | exports 에 sub-path 미정의 | 라이브러리에 PR / 정의된 sub-path 만 사용 |
| `An import path can only end with a '.ts' extension when 'allowImportingTsExtensions' is enabled` | Bundler 모드 외에서 `.ts` 명시 | `Bundler` + `noEmit` 조합 |
| `Relative import paths need explicit file extensions in ECMAScript imports` | NodeNext 에서 확장자 누락 | 소스에 `.js` 명시 |
| `The current file is a CommonJS module whose imports will produce 'require' calls` | 가까운 package.json 의 `type` 누락 | `"type": "module"` 추가 또는 `.mts` 사용 |
| `is not a module` 디폴트 import 실패 | `esModuleInterop` 미설정 + CJS 라이브러리 | `esModuleInterop: true` 추가 |

`esModuleInterop` 은 `import _ from 'lodash'` 같은 디폴트 import 가 CJS 모듈을 잘 흡수하도록 보정한다. 새 프로젝트에서는 항상 켜는 것이 권장된다.

## 11. 결론

`moduleResolution` 은 "어디 있는 파일을 찾는가" 의 알고리즘 선택이고, `module` 은 "어떻게 emit 할 것인가" 의 형식 선택이다. 두 옵션은 *대상 런타임* 에서 거꾸로 결정해야 한다 — Node 에서 직접 돌리면 `NodeNext`, 번들러가 후처리하면 `Bundler`. 라이브러리 작성자는 `exports` 매핑에서 `types` 를 첫 조건으로 두고, dual package hazard 를 피하려면 가능한 ESM-only 또는 Node 조건 CJS 우선 매핑을 선택한다.

## 참고

- TypeScript Handbook — Module Resolution
- TypeScript 4.7 release notes — `node16`/`nodenext` 도입 배경
- TypeScript 5.0 release notes — `Bundler` resolver
- Node.js Documentation — Modules: ECMAScript modules → Resolution Algorithm
- isaacs/tshy — TypeScript 듀얼 빌드 도구 README
- Andrew Branch, "Choosing Compiler Options" (TypeScript 공식 블로그 모듈 시리즈)
