Notion 원본: https://www.notion.so/36c5a06fd6d3815cbcdefd664e4fa133

# TypeScript Module Resolution — node16·nodenext·bundler 차이와 paths Resolution

> 2026-05-26 신규 주제 · 확장 대상: TypeScript / Node.js / Next.js

## 학습 목표
- `moduleResolution` 4가지 전략(`classic`/`node`/`node16`·`nodenext`/`bundler`)의 해석 알고리즘을 비교한다.
- `package.json` `exports` 필드와 conditional resolution을 분석해 라이브러리 진입점이 어떻게 결정되는지 추적한다.
- `paths`·`baseUrl` 별칭이 컴파일러 차원의 매핑임을 확인하고, 런타임/번들러와 동기화하는 패턴을 정리한다.
- CommonJS → ESM 마이그레이션과 dual-publish, types-conditional 배치 전략을 실제 tsconfig 예제로 검증한다.

## 1. moduleResolution 4가지 history — classic, node, node16/nodenext, bundler

TypeScript의 모듈 해석 전략은 2015년 `classic`에서 시작해 2026년 현재 `bundler`까지 네 단계로 발전했다. `classic`은 TS 1.x 초창기 ambient module 시대의 잔재로, 상대 경로는 파일 시스템을 그대로 따라가지만 비상대 경로는 단순히 컴파일 루트에서 한 단계씩 위로 올라가며 `.ts`/`.d.ts`만 찾는다. `node_modules`를 모르기 때문에 npm 생태계가 등장한 이후로는 사실상 사용하지 않는다.

`node`는 TS 2.0에서 도입되어 Node.js의 CommonJS `require` 알고리즘(node10 동작)을 흉내낸다. `node_modules`를 디렉터리 위로 탐색하고, `package.json`의 `main` 필드와 `index.js` 폴백을 본다. `exports` 필드를 모르기 때문에 2018년 이후 발행된 ESM-first 패키지에서 종종 진입점을 잘못 잡는다. TS 5.0부터 이 옵션은 `node10`으로 이름이 바뀌었고, `node`는 alias로만 남았다.

`node16`/`nodenext`는 TS 4.7에서 도입된 본격적인 Node.js ESM 지원이다. Node 12 이상에서 안정화된 `package.json` `"type"` 필드, `.mjs`/`.cjs` 확장자, conditional exports를 모두 따른다. `node16`은 Node 16 동작 기준으로 고정되고, `nodenext`는 최신 LTS(현재 Node 22) 동작을 추적한다. 두 값은 `module` 옵션도 동일하게 `node16`/`nodenext`로 묶여야 한다.

`bundler`는 TS 5.0에서 추가됐다. webpack/vite/esbuild처럼 자체 resolver를 가진 번들러 환경을 가정해, ESM의 엄격한 규칙 중 일부를 완화한다. `exports` 필드와 conditional resolution은 지원하되, import 구문에 `.js` 확장자를 강제하지 않는다. Next.js 14 이후, Vite 5 이후 권장 설정이 이 값이다.

| 옵션 | 도입 | 주요 알고리즘 | extension 강제 | exports 지원 |
| --- | --- | --- | --- | --- |
| `classic` | TS 1.x | 부모 디렉터리 순회 | 불필요 | 없음 |
| `node10`(=`node`) | TS 2.0 | CJS require | 불필요 | 없음 |
| `node16`/`nodenext` | TS 4.7 | Node ESM | 강제 | 지원 |
| `bundler` | TS 5.0 | 번들러 친화 | 면제 | 지원 |

## 2. node16/nodenext의 ESM·CJS dual resolution

`node16`/`nodenext`는 파일이 ESM인지 CJS인지 명확히 구분한다. 판정 우선순위는 확장자 → 가장 가까운 `package.json`의 `"type"` 필드다. `.mts` 또는 `.cts`는 무조건 ESM/CJS로 고정되고, `.ts`는 `package.json`을 따른다.

```jsonc
// package.json
{
  "name": "my-lib",
  "type": "module",
  "exports": {
    ".": {
      "import": "./dist/index.js",
      "require": "./dist/index.cjs",
      "types": "./dist/index.d.ts"
    }
  }
}
```

```typescript
// src/index.ts (ESM으로 해석됨)
import { foo } from "./utils.js"; // ← .ts가 아닌 .js를 명시

export const bar = () => foo() + 1;
```

ESM 파일에서 상대 경로 import에 `.js`를 강제하는 이유는 Node.js의 ECMAScript loader가 확장자 자동 추론을 하지 않기 때문이다. TypeScript는 컴파일 결과 `.js` 파일에서 해당 import가 그대로 동작해야 하므로, 소스에서도 미리 `.js`로 쓰도록 요구한다.

CJS와 ESM이 한 패키지에 공존할 때, `.cts` → `.cjs`, `.mts` → `.mjs`로 출력되며 `module: nodenext`가 이를 자동 처리한다. 단, ESM에서 CJS를 import할 때 default export 호환 문제가 발생할 수 있어 `esModuleInterop: true`를 함께 켠다.

## 3. bundler resolution (TS 5.0+)

`bundler` 옵션은 Vite, webpack 5, esbuild, Rspack, Turbopack 같은 번들러가 자체 resolver를 갖는다는 전제로 만든 lax 모드다. Node ESM의 엄격함 중 두 가지를 완화한다. 첫째, 상대 import에 `.js` 확장자를 강제하지 않는다. 둘째, `package.json` `"type"` 필드와 무관하게 `.ts` 파일을 항상 ESM처럼 다룬다.

```jsonc
// tsconfig.json — Next.js 14+ / Vite 5 권장
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "preserve",
    "moduleResolution": "bundler",
    "esModuleInterop": true,
    "allowImportingTsExtensions": false,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "preserve"
  }
}
```

`module: "preserve"`는 TS 5.4에서 추가된 값으로, import/export 구문을 그대로 보존해 번들러에 위임한다. trade-off는 명확하다. `bundler` 모드는 번들러 없이 Node로 직접 실행하면 import가 깨진다. 라이브러리는 `nodenext`, 애플리케이션은 `bundler`로 나누는 것이 정석이다.

## 4. paths와 baseUrl — 컴파일러 차원 별칭

`paths`는 TypeScript가 모듈 식별자를 다시 매핑하는 컴파일러 차원의 기능이다. 런타임에는 어떤 효과도 없다. `tsc`가 출력하는 `.js`에는 `paths`가 적용된 결과가 그대로 남지 않고, 원본 경로가 그대로 남는다.

```jsonc
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@/*": ["src/*"],
      "@shared/*": ["../shared/src/*"],
      "@config": ["src/config/index.ts"]
    }
  }
}
```

Next.js 13/14는 `tsconfig.json`의 `paths`를 자동으로 읽어 webpack/Turbopack alias로 변환한다. 반면 Node로 직접 실행하는 백엔드(express, NestJS)는 `tsc-alias`, `tsconfig-paths` 같은 런타임 헬퍼가 필요하다. 측정해 보면 `tsconfig-paths/register`는 cold start에 50~120ms를 추가하므로, 프로덕션 빌드에서는 `tsc-alias`로 출력물의 import 경로를 미리 치환하는 편이 안전하다.

## 5. package.json exports field

`exports` 필드는 Node 12.7부터 안정화된 라이브러리 진입점 명세다. `main`/`module`/`browser` 필드를 한꺼번에 대체하며, 외부에서 접근 가능한 서브패스를 캡슐화한다.

```jsonc
{
  "name": "my-lib",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    },
    "./client": {
      "types": "./dist/client.d.ts",
      "browser": "./dist/client.browser.js",
      "import": "./dist/client.js"
    },
    "./icons/*": {
      "types": "./dist/icons/*.d.ts",
      "import": "./dist/icons/*.js"
    }
  }
}
```

conditional key는 위에서 아래로 평가되며 첫 매치를 반환한다. `types`는 반드시 가장 위에 두어야 하는데, TypeScript가 다른 조건보다 먼저 매치되면 타입 파일을 찾지 못하기 때문이다. subpath pattern `./icons/*`는 와일드카드 매핑이다.

## 6. module과 moduleResolution 호환 매트릭스 (TS 5.x)

| `module` | 허용 `moduleResolution` | 출력 import 형태 | 주요 용도 |
| --- | --- | --- | --- |
| `commonjs` | `node10` | `require(...)` | 레거시 Node |
| `node16` | `node16` | ESM/CJS 혼합 | Node 16 라이브러리 |
| `nodenext` | `nodenext` | ESM/CJS 혼합 | Node 22 라이브러리 |
| `esnext` | `bundler`, `node10` | ESM 그대로 | 번들러 환경 |
| `preserve` | `bundler` | 원본 보존 | TS 5.4+ 번들러 |

`module: "nodenext"`로 잡으면 자동으로 ESM 규칙이 적용된다. import에 `.js` 확장자가 없으면 `TS2835` 에러, `.cts`에서 top-level `import`를 쓰면 `TS1259` 에러가 난다.

```jsonc
// Node 22 LTS 라이브러리용 정석
{
  "compilerOptions": {
    "target": "ES2023",
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "verbatimModuleSyntax": true,
    "esModuleInterop": true,
    "declaration": true,
    "outDir": "dist"
  },
  "include": ["src/**/*"]
}
```

`verbatimModuleSyntax`는 TS 5.0에서 추가됐으며, `import type`/`export type`을 정확히 보존해 런타임 부작용을 막는다. ESM-first 라이브러리에서는 사실상 필수다.

## 7. Monorepo / Workspace 시나리오

pnpm/yarn workspaces 기반 모노레포에서는 `paths`와 project references를 함께 쓴다. workspace 의존성(`"@org/shared": "workspace:*"`)은 pnpm이 symlink로 해결하므로, TypeScript는 그 symlink를 따라가면서 타입을 본다.

```jsonc
// apps/web/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "moduleResolution": "bundler",
    "paths": {
      "@org/shared": ["../../packages/shared/src/index.ts"]
    }
  },
  "references": [
    { "path": "../../packages/shared" }
  ]
}
```

`composite: true`는 incremental 빌드 메타데이터를 생성해 `tsc --build`가 의존 그래프를 따라 부분 빌드한다. `paths`로 소스 경로를 가리키면 IDE는 즉시 변경을 반영하지만, 실제 런타임은 `dist/`의 빌드 산출물을 본다. 이 불일치 때문에 모노레포에서는 종종 "타입은 맞는데 import가 깨진다"는 현상이 발생한다. 해결책은 두 가지다. (1) 애플리케이션 빌드 전에 항상 `pnpm -r build`로 의존 패키지를 먼저 빌드한다. (2) Vite/webpack alias를 `packages/*/src`로 직접 가리키고 dev 모드에서만 활성화한다.

## 8. CommonJS → ESM 마이그레이션과 dual-publish

기존 CJS 라이브러리를 ESM으로 옮기는 절차는 다음 순서로 진행한다. 첫째, `package.json`에 `"type": "module"`을 추가하고 모든 소스를 ESM 문법으로 통일한다. 둘째, `tsconfig.json`을 `module: "nodenext"`, `moduleResolution: "nodenext"`로 변경하고 import에 `.js` 확장자를 일괄 추가한다. 셋째, `__dirname`/`__filename`처럼 ESM에 없는 식별자를 `import.meta.url` 기반으로 교체한다.

```typescript
// ESM에서 __dirname 대체
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
```

dual-publish는 ESM과 CJS를 한 패키지에 함께 발행해 양쪽 소비자를 지원하는 패턴이다. `tsup`, `unbuild`, `rollup` 같은 빌드 도구를 쓰면 두 포맷을 한 번에 생성한다.

```jsonc
// dual-publish 패키지의 package.json
{
  "name": "my-lib",
  "version": "2.0.0",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "types": {
        "import": "./dist/index.d.ts",
        "require": "./dist/index.d.cts"
      },
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    }
  }
}
```

types-conditional은 두 가지 배치가 있다. 단순한 경우 최상위에 `"types"` 하나만 둔다. ESM/CJS 양쪽에서 타입이 달라야 하면 위 예제처럼 `types` 안에 다시 `import`/`require`를 중첩한다.

dual-publish의 trade-off는 번들 크기와 유지비다. 결과물이 두 배가 되고, 두 진입점이 미묘하게 다른 동작을 보이면 디버깅이 어렵다. 신규 라이브러리는 ESM 단일 발행을 우선 검토하고, Node 18+ 소비자만 가정하면 CJS 출력을 생략해도 무방하다.

## 참고
- TypeScript Handbook — Module Resolution: https://www.typescriptlang.org/docs/handbook/modules/reference.html
- TypeScript Release Notes 4.7 (node16/nodenext): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- TypeScript Release Notes 5.0 (bundler resolution): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript Release Notes 5.4 (`module: preserve`): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-4.html
- Node.js Docs — Modules: Packages: https://nodejs.org/api/packages.html
- Node.js Docs — ECMAScript modules: https://nodejs.org/api/esm.html
- package.json `exports` 명세: https://nodejs.org/api/packages.html#conditional-exports
- Andrew Branch — "Publishing types to npm": https://github.com/andrewbranch/example-subpath-exports-ts-compat
