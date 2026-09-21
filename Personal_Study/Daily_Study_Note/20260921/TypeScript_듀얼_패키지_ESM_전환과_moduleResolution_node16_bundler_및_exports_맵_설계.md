Notion 원본: https://www.notion.so/3e25a06fd6d381b4a617f08de9ffae0b

# TypeScript 듀얼 패키지 ESM 전환과 moduleResolution node16 bundler 및 exports 맵 설계

> 2026-09-21 신규 주제 · 확장 대상: TypeScript, Javascript

## 학습 목표

- `moduleResolution` 다섯 모드의 해석 규칙 차이를 조건부 exports 관점에서 구분한다
- `package.json` exports/types 조건 순서를 작성하고 `arethetypeswrong` 으로 검증한다
- 듀얼 패키지 해저드가 발생하는 조건을 식별하고 회피 구조를 설계한다
- ESM 전용 의존성이 CJS 소비자를 깨뜨리는 상황에 대한 이관 경로를 정한다

## 1. 왜 모듈 해석이 타입 문제가 되었나

Node.js 12 가 ESM 을 정식 지원하면서, 하나의 파일이 CommonJS 인지 ES 모듈인지가 **확장자와 가장 가까운 `package.json` 의 `type` 필드**로 결정되기 시작했다. `.cjs` 는 항상 CJS, `.mjs` 는 항상 ESM, `.js` 는 `type` 에 따른다. 이 판정이 중요한 이유는 모듈 종류가 달라지면 허용되는 문법과 해석 규칙이 함께 달라지기 때문이다.

| 항목 | CommonJS | ES Module |
| --- | --- | --- |
| 상대 임포트 확장자 | 생략 가능 | 필수(`./a.js`) |
| 디렉터리 index 자동 해석 | O | X |
| `__dirname`/`require` | 사용 가능 | 없음(`import.meta.url`) |
| top-level await | 불가 | 가능 |
| 순환 참조 | 부분 초기화 객체 | live binding(TDZ) |

TypeScript 는 오래도록 `moduleResolution: "node"` 하나로 CJS 규칙만 헉내 냈다. 그 결과 "타입 체크는 통과하는데 런타임에 `ERR_MODULE_NOT_FOUND`" 같은 불일치가 생겼다. `node16`/`nodenext` 는 이 간극을 메우기 위해 **컴파일러가 각 파일의 모듈 종류를 실제 Node 와 동일한 규칙으로 판정하고, 그에 맞는 해석 규칙을 적용**하도록 바꾼 모드다.

## 2. moduleResolution 다섯 모드 비교

```
classic  — TS 1.x 유산. 사용 금지
node10   — 구 "node". CJS 규칙만. exports 맵 무시
node16   — Node 16 규칙. type/확장자로 CJS·ESM 판정, exports/imports 지원
nodenext — node16 과 동일 기반 + 최신 Node 동작 추종
bundler  — 번들러 가정. exports 지원 + 확장자 생략 허용
```

가장 큰 분기는 **`exports` 필드를 보는가**와 **상대 임포트 확장자를 요구하는가** 두 축이다.

| 모드 | exports 맵 | 확장자 생략 | ESM 판정 | 주 사용처 |
| --- | --- | --- | --- | --- |
| node10 | 무시 | 허용 | 없음 | 레거시 유지 |
| node16/nodenext | 사용 | 금지(ESM 파일) | O | Node 직접 실행, 라이브러리 배포 |
| bundler | 사용 | 허용 | X(`module` 로 결정) | Vite/webpack 앱 |

`bundler` 는 `--module esnext`(또는 `preserve`)와만 조합되고 `import ... = require()` 를 허용하지 않는다. 앱 코드에는 편하지만, **라이브러리를 만든다면 `nodenext` 로 체크하는 것이 안전하다**. 번들러는 관대해서 잘못된 exports 맵도 통과시키지만 Node 직접 실행은 그러지 않기 때문이다.

실무 규칙: 앱은 `bundler`, 배포 패키지는 `nodenext`. 모노레포에서 두 가지가 섞이면 패키지별 tsconfig 를 나누고 `--build` 로 project references 를 구성한다.

## 3. exports 맵: 조건 순서가 전부다

`exports` 는 **위에서 아래로 첫 매칭**을 취한다. 순서가 틀리면 조용히 잘못된 파일이 선택된다.

```json
{
  "name": "@acme/sdk",
  "version": "2.0.0",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs",
      "default": "./dist/index.js"
    },
    "./client": {
      "types": "./dist/client.d.ts",
      "import": "./dist/client.js",
      "require": "./dist/client.cjs"
    },
    "./package.json": "./package.json"
  },
  "main": "./dist/index.cjs",
  "types": "./dist/index.d.ts"
}
```

네 가지가 규칙이다.

첫째, **`types` 는 항상 맨 위**다. 아래에 두면 `import`/`require` 가 먼저 매칭되어 타입 해석이 JS 파일로 떨어진다.

둘째, **`default` 는 항상 맨 아래**다. 어떤 조건도 맞지 않을 때의 폴백이므로 중간에 두면 그 아래 조건이 죽는다.

셋째, **`exports` 를 선언하는 순간 나머지 경로는 전부 차단된다**. 소비자가 `@acme/sdk/dist/util.js` 를 직접 import 할 수 없게 되므로, 공개하고 싶은 서브패스를 빠짐없이 나열해야 한다.

넷째, **CJS·ESM 각각에 대응하는 `.d.ts` 를 따로 둔다**. 하나의 `index.d.ts` 를 양쪽이 공유하면 `export default` 의 상호운용 동작이 달라 타입이 틀어진다.

```json
"exports": {
  ".": {
    "import": { "types": "./dist/index.d.mts", "default": "./dist/index.mjs" },
    "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
  }
}
```

이 중첩 형태가 권장형이다. 조건별로 타입과 구현을 짝지어 두면 해석 경로가 절대 섞이지 않는다.

## 4. 듀얼 패키지 해저드

하나의 패키지가 CJS 빌드와 ESM 빌드를 모두 제공할 때, 한 프로세스 안에서 **두 인스턴스가 동시에 로드**될 수 있다. 의존성 그래프의 일부는 `require("@acme/sdk")` 로, 다른 일부는 `import "@acme/sdk"` 로 들어오면 Node 는 서로 다른 모듈 레코드를 만든다.

결과는 상태 분열이다.

```js
// a.cjs
const { registry } = require("@acme/sdk");
registry.set("k", 1);

// b.mjs
import { registry } from "@acme/sdk";
registry.get("k"); // undefined — 다른 Map 인스턴스
```

`instanceof` 도 깨진다. CJS 쪽에서 만든 `SdkError` 가 ESM 쪽 `SdkError` 의 인스턴스가 아니게 되어 `catch` 분기가 조용히 빗나간다. 싱글턴 캐시, 플러그인 레지스트리, 심볼 기반 브랜딩을 쓰는 패키지에서 특히 치명적이다.

회피 전략은 세 가지다.

**(1) 상태 없는 패키지로 만든다.** 순수 함수와 타입만 내보내면 인스턴스가 둘이어도 무해하다. 가장 확실한 해법이며, 대부분의 유틸리티 패키지는 여기에 해당한다.

**(2) 상태를 별도 CJS 전용 패키지로 분리한다.** `@acme/sdk-core`(CJS only)에 상태를 두고, `@acme/sdk` 의 ESM·CJS 빌드가 모두 그것을 `require` 하게 한다. ESM 에서 CJS 를 require 하는 것은 가능하므로 인스턴스가 하나로 수렴한다.

**(3) ESM 전용으로 간다.** 가장 깨끗하지만 CJS 소비자를 버리는 결정이다. Node 22 이후 `require(esm)` 이 동기 ESM 에 한해 지원되므로 제약이 예전만큼 크지는 않다. 다만 top-level await 이 있는 모듈은 여전히 require 불가이므로, ESM 전용 패키지라면 TLA 사용을 신중히 결정해야 한다.

`globalThis` 에 심볼 키로 상태를 붙이는 방식도 쓰이지만, 이는 해저드를 숨기는 우회책이지 해결이 아니다. 버전이 다른 두 사본이 같은 심볼을 공유하면 더 나쁜 버그가 된다. 심볼 키에 메이저 버전을 포함시키는 것이 최소한의 방어다.

## 5. verbatimModuleSyntax 와 타입 임포트

`isolatedModules` 환경(esbuild, swc, Babel)에서는 파일 단위로만 트랜스파일하므로, `import { Foo } from "./foo"` 의 `Foo` 가 타입인지 값인지 판단할 수 없다. TS 5.0 의 `verbatimModuleSyntax` 는 이 모호함을 제거한다.

```ts
import type { User } from "./types";   // 출력에서 완전히 제거
import { createUser } from "./api";    // 출력에 그대로 남음
import { type Role, ROLES } from "./roles"; // 인라인 type 수식자
```

규칙은 단순하다. **`type` 수식자가 붙은 것만 지워지고, 나머지는 문자 그대로 남는다.** 부작용만 필요한 임포트(`import "./polyfill"`)가 보존되는 것도 이 옵션의 효과다. 기존 `importsNotUsedAsValues` 와 `preserveValueImports` 를 대체한다.

`verbatimModuleSyntax` 를 켜면 CJS 출력이 필요한 파일에서 ESM 문법을 쓸 수 없다. `.cts` 파일에서는 `import x = require("x")` / `export = x` 를 써야 한다. 이 제약이 오히려 도움이 되는 이유는, 빌드 산출물의 모듈 종류가 소스에서 이미 확정되어 "TS 가 알아서 변환해 주겠지"라는 모호함이 사라지기 때문이다.

## 6. 검증: 손으로 확인하지 말 것

exports 맵은 조합이 많아 눈으로 검증하기 어렵다. 도구를 파이프라인에 넣는다.

```bash
# 1. 타입 해석 정합성 — 9가지 소비 시나리오를 전부 검사
npx @arethetypeswrong/cli --pack .

# 2. 발행될 파일 목록 확인
npm pack --dry-run

# 3. Node 실행 시 실제 해석 경로 추적
node --experimental-import-meta-resolve -e \
  'console.log(await import.meta.resolve("@acme/sdk"))'

# 4. CJS 소비 경로 직접 확인
node -e 'console.log(require.resolve("@acme/sdk"))'
```

`arethetypeswrong` 이 잡아내는 대표 문제는 다음과 같다.

| 코드 | 의미 | 원인 |
| --- | --- | --- |
| `FalseESM` | 타입은 ESM 인데 구현이 CJS | `.d.ts` 를 양쪽이 공유 |
| `FalseCJS` | 타입은 CJS 인데 구현이 ESM | `types` 조건 위치 오류 |
| `CJSResolvesToESM` | require 가 ESM 을 가리킴 | `require` 조건 누락 |
| `NoResolution` | 해석 실패 | exports 맵에 경로 누락 |
| `MissingExportEquals` | `export =` 없음 | CJS `.d.ts` 형태 불일치 |

CI 에 `attw --pack . --profile node16` 를 넣어 두면 exports 맵 회귀를 릴리스 전에 차단할 수 있다. 실제로 이 검사 하나가 "사용자가 업그레이드했더니 타입이 any 가 됐다"류 이슈의 대부분을 막는다.

## 7. 점진적 전환 경로

기존 CJS 패키지를 옆길 때의 순서는 다음과 같다. 각 단계는 독립적으로 릴리스 가능해야 한다.

**1단계 — 소스 정리.** `moduleResolution` 을 `node16` 으로 올리고 상대 임포트에 확장자를 붙인다. 이 작업은 `ts-morph` 나 `eslint-plugin-import` 의 `extensions` 규칙으로 자동화한다. 아직 출력은 CJS 로 유지하므로 소비자에게 영향이 없다.

**2단계 — 듀얼 빌드 추가.** tsup·rollup 등으로 `.cjs` + `.mjs` 를 함께 내고 exports 맵을 중첩 형태로 작성한다. `main`/`types` 는 구형 도구용 폴백으로 남긴다. 여기서 `attw` 를 CI 에 추가한다.

**3단계 — 상태 분리.** 4절의 해저드 점검. 싱글턴이 있으면 core 패키지로 분리하거나 상태를 제거한다. 이 단계를 건너뛰면 2단계 릴리스가 미묘한 버그를 만든다.

**4단계 — ESM 전용 전환.** 메이저 버전을 올리고 CJS 빌드를 제거한다. 릴리스 노트에 "Node 20+ / require(esm) 필요" 를 명시한다.

되돌릴 수 없는 결정은 4단계뿐이고 나머지는 롤백 가능하므로, 3단계까지를 마이너 버전에서 소화하는 것이 실무적으로 가장 마찰이 적다.

## 8. 흔한 함정 모음

**서브패스 와일드카드의 `*` 는 glob 이 아니다.** `"./utils/*": "./dist/utils/*.js"` 에서 `*` 는 단일 문자열 치환이며 `/` 를 포함할 수 있다. 의도치 않게 `./utils/a/b` 가 매칭되므로 공개 범위를 좁히려면 명시적 목록이 낫다.

**`imports` 필드는 내부 전용 별칭이다.** `#internal/*` 형태로 선언하면 패키지 내부에서만 쓰이고 외부에는 노출되지 않는다. tsconfig `paths` 와 달리 런타임에도 동작하므로 번들러 설정 없이 절대 경로 임포트를 쓸 수 있다.

**`paths` 는 배포 패키지에서 쓰면 안 된다.** 타입은 해석되지만 소비자의 런타임은 그 별칭을 모른다. `.d.ts` 에 `@/foo` 같은 경로가 남으면 소비자 쪽에서 해석 실패한다. 빌드 시 `tsc-alias` 등으로 상대 경로로 펴거나, 처음부터 `imports` 필드를 쓴다.

**`__dirname` 대체는 `import.meta.dirname`.** Node 20.11+ 에서 `import.meta.dirname` / `import.meta.filename` 이 추가되어 `fileURLToPath(new URL(".", import.meta.url))` 관용구가 필요 없다. 하위 호환이 필요하면 기존 관용구를 유지한다.

**JSON 임포트는 조건부다.** ESM 에서 `import data from "./x.json" with { type: "json" }` 형태가 필요하며, TS 는 `resolveJsonModule` 과 `module: nodenext` 조합에서 이를 검사한다. 번들러 환경과 Node 직접 실행의 동작이 달라 라이브러리에서는 JSON 임포트 대신 코드로 인라인하는 편이 안전하다.

마지막으로, exports 맵을 바꾸는 것은 **거의 항상 파괴적 변경**으로 취급해야 한다. 서브패스 하나를 닫으면 그것을 쓰던 소비자가 즉시 깨진다. 공개 표면을 넓게 열어 두었다면 좁히는 작업은 메이저 버전으로 미루고, 새 패키지는 처음부터 최소한의 서브패스만 노출하는 것이 장기적으로 유지 비용이 낮다.

## 참고

- Node.js Documentation — Modules: Packages (exports, imports, conditions)
- Node.js Documentation — Dual CommonJS/ES module packages
- TypeScript Handbook — Modules: Theory / Reference / Choosing Compiler Options
- TypeScript 5.0 Release Notes — verbatimModuleSyntax
- arethetypeswrong/arethetypeswrong.github.io — 검사 규칙 문서
- Node.js — `require(esm)` 지원 관련 릴리스 노트
