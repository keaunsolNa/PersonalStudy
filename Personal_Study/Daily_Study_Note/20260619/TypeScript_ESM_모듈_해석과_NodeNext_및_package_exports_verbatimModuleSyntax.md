Notion 원본: https://www.notion.so/3845a06fd6d381349511fa4e9f4caf82

# TypeScript ESM 모듈 해석과 NodeNext 및 package exports verbatimModuleSyntax

> 2026-06-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- moduleResolution 전략(node10 / node16 / nodenext / bundler)의 차이와 선택 기준을 정한다
- package.json의 type, exports, imports 필드가 모듈 해석에 미치는 영향을 분석한다
- ESM에서 확장자 명시(.js)가 강제되는 이유와 TS 소스↔출력 매핑을 이해한다
- verbatimModuleSyntax와 isolatedModules로 트랜스파일러 호환 코드를 작성한다

## 1. 모듈 시스템의 두 세계: CJS와 ESM

Node.js에는 CommonJS(require/module.exports, 동기 로딩)와 ESM(import/export, 정적 분석 가능)이 공존한다. Node는 파일이 어느 쪽인지를 .mjs면 ESM, .cjs면 CJS, .js면 가장 가까운 package.json의 `type`을 따른다(`module`이면 ESM).

TypeScript는 .ts를 작성하지만 실행되는 것은 emit된 .js다. 따라서 TS의 모듈 해석은 "내가 출력할 파일이 어떤 모듈 시스템으로 실행될지"를 예측해야 하고, 이것이 module/moduleResolution 옵션의 핵심 책임이다.

## 2. moduleResolution 전략 비교

| 전략 | 대상 | 확장자 .js 요구 | exports |
|---|---|---|---|
| node10 | 레거시 CJS | 아니오 | 무시 |
| node16 | Node ESM/CJS 혼용 | 예(ESM 시) | 존중 |
| nodenext | 최신 Node 추종 | 예(ESM 시) | 존중 |
| bundler | Vite/webpack/esbuild | 아니오 | 존중 |

node10은 구 node 모드로 확장자 없는 경로를 보완하고 exports를 무시한다. node16/nodenext는 Node의 실제 ESM 해석 규칙을 모사해 확장자 명시와 exports를 존중한다. bundler(TS 4.7+)는 exports는 존중하되 확장자 명시는 요구하지 않는다.

```json
{
  "compilerOptions": {
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "verbatimModuleSyntax": true,
    "strict": true
  }
}
```

## 3. 왜 ESM에서 .js 확장자를 써야 하는가

TS 소스는 a.ts인데 import문에는 `./a.js`라고 써야 한다. 이유는 TypeScript가 import 경로를 다시 쓰지 않기(no rewriting) 때문이다. 소스의 경로를 그대로 emit하므로, 런타임에 실제 존재할 출력 파일명(.js)을 적어야 Node ESM 로더가 찾는다.

```typescript
import { add } from "./math.js"; // .ts로 쓰면 ERR_MODULE_NOT_FOUND
```

ESM은 CJS와 달리 확장자 자동 보완을 하지 않는다. allowImportingTsExtensions는 noEmit/번들러 환경 전용이다.

## 4. package.json exports: 패키지의 공개 경계

exports 필드는 패키지가 외부에 노출하는 진입점을 통제한다. 정의되면 exports에 없는 deep import는 차단된다. 조건부 exports로 ESM/CJS 소비자에게 다른 파일을 줄 수 있다.

```json
{
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    }
  }
}
```

조건 키 순서가 중요하다. types는 항상 먼저 와야 TS가 타입을 찾는다. dual package는 dual package hazard(양쪽 로드 시 인스턴스 분리)가 있다. imports 필드(#로 시작)는 패키지 내부 별칭을 정의한다.

## 5. verbatimModuleSyntax: 타입과 값의 명확한 분리

TS 5.0에서 도입. 핵심 동작은 "type 한정자가 붙은 import/export만 제거하고 나머지는 작성한 그대로 emit"이다. 추론을 없애면 esbuild/swc/Babel 호환성이 보장된다 — 이들은 단일 파일만 보며 타입 정보가 없기 때문이다.

```typescript
import { type User, createUser } from "./user.js";
import type { Config } from "./config.js";
```

규칙이 엄격해지는 대신 동작이 결정적이다. CJS 출력 파일에서 ESM 구문을 쓰면 에러를 낸다.

## 6. isolatedModules: 파일 단위 트랜스파일 안전성

타입 정보 없이 파일 하나만 보고 변환하는 도구(Babel, esbuild)를 위해 cross-file 정보가 필요한 위험 구문을 막는다.

```typescript
export const enum Color { Red } // const enum 금지
export { SomeType } from "./types"; // -> export type { SomeType } 필요
```

verbatimModuleSyntax와 함께 켜는 것이 보편적이다.

| 옵션 | 역할 | 권장 상황 |
|---|---|---|
| verbatimModuleSyntax | type 외 import/export 보존 | 라이브러리, 빠른 빌드 |
| isolatedModules | 단일 파일 트랜스파일 안전 | Babel/esbuild/swc |

## 7. 실전 트러블슈팅 체크리스트

ERR_MODULE_NOT_FOUND는 거의 항상 상대 import의 .js 확장자 누락이다. "Cannot use import statement outside a module"는 type 설정과 emit 모듈 형식 불일치다. 라이브러리 타입을 못 찾으면 exports 조건 키 순서를 의심한다.

```bash
tsc --traceResolution --noEmit | grep -A3 "my-lib"
```

## 8. CJS에서 ESM으로의 마이그레이션 실무

ESM에는 __dirname, __filename, require가 없다. 대체 방법:

```typescript
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { createRequire } from "node:module";
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const require = createRequire(import.meta.url);
```

라이브러리 배포는 dual build가 현실적 해법이다. tsup으로 ESM·CJS 두 형식을 빌드하고 main/module/types 폴백을 제공한다. 검증은 양쪽 import 스모크 테스트와 arethetypeswrong 같은 도구로 마무리한다.

## 참고

- TypeScript Handbook — Modules: Theory / Reference / Choosing Compiler Options
- TypeScript 5.0 Release Notes — verbatimModuleSyntax
- Node.js 공식 문서 — Modules: Packages (exports, imports)
- TypeScript 4.7 Release Notes — moduleResolution node16/nodenext
