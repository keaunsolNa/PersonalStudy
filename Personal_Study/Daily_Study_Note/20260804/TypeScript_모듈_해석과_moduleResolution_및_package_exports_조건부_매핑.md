Notion 원본: https://app.notion.com/p/3b25a06fd6d381fcae67c2b76bb74a23

# TypeScript 모듈 해석과 moduleResolution 및 package exports 조건부 매핑

> 2026-08-04 신규 주제 · 확장 대상: Javascript

## 학습 목표

- moduleResolution 의 node10·node16·nodenext·bundler 전략 차이를 구분한다
- package.json 의 exports 필드와 조건부 매핑이 타입·런타임 진입점을 결정하는 원리를 이해한다
- ESM 과 CJS 상호운용에서 확장자·types 조건이 일으키는 문제를 진단한다
- verbatimModuleSyntax 와 import type 으로 타입 전용 임포트를 안전하게 다룬다

## 1. 모듈 해석이란 — 문자열을 파일로 바꾸는 규칙

`import { x } from "some-lib"` 를 만나면 컴파일러는 `"some-lib"` 라는 문자열이 실제로 어느 파일인지 찾아야 한다. 이 과정이 **모듈 해석(module resolution)** 이다. 상대 경로(`./util`)와 베어 스펙(`react`)이 다르게 취급되고, 확장자 보완·`node_modules` 탐색·package.json 필드 해석이 모두 여기 얽힌다. `moduleResolution` 옵션이 이 알고리즘을 고른다.

TypeScript 는 역사적으로 여러 전략을 쌓아 왔고, 현재 유효한 값은 `node10`(구 `node`), `node16`, `nodenext`, `bundler` 다. 잘못 고르면 "빌드는 되는데 런타임에 모듈을 못 찾는" 또는 "타입은 있는데 임포트가 빨간 줄" 같은 엇갈림이 생긴다.

## 2. node10 — 레거시 CommonJS 해석

`node10`(예전 이름 `node`)은 Node.js 의 옛 CommonJS `require` 해석을 모사한다. 확장자 없이 임포트하면 `.ts`→`.tsx`→`.d.ts` 순으로 보완하고, 디렉터리는 `index` 파일이나 package.json 의 `main` 필드를 본다.

```jsonc
// tsconfig.json
{
  "compilerOptions": {
    "module": "commonjs",
    "moduleResolution": "node10"   // = 구 "node"
  }
}
```

```typescript
import { helper } from "./util";     // ./util.ts 또는 ./util/index.ts
import express from "express";       // node_modules/express/package.json 의 main
```

문제는 `node10` 이 **package.json 의 `exports` 필드를 이해하지 못한다**는 점이다. `main`·`types` 만 본다. 최신 패키지들은 `exports` 로 진입점을 엄격히 통제하는데, node10 은 이를 무시하고 내부 파일에 임의 접근을 허용해 버린다. 새 프로젝트에서 node10 을 쓰면 안 되는 핵심 이유다.

## 3. node16 / nodenext — ESM 시대의 정식 해석

`node16` 과 `nodenext` 는 Node.js 의 현대적 모듈 해석을 따른다. 둘의 실질 차이는 거의 없고, `nodenext` 가 최신 Node 동작을 계속 추종하는 별칭이다. 핵심 특징은 세 가지다.

첫째, **package.json 의 `type` 필드**로 `.js` 파일이 ESM 인지 CJS 인지 결정한다. `"type": "module"` 이면 `.js` 가 ESM, 없으면 CJS 다. `.mts`/`.cts` 확장자로 파일 단위 강제도 가능하다.

둘째, **상대 임포트에 확장자를 명시**해야 한다. ESM 은 확장자 보완을 하지 않으므로, TypeScript 소스에서도 `.js` 확장자로 써야 한다(컴파일 후 실제 파일 기준).

```typescript
// node16/nodenext + ESM 에서는 확장자 필수
import { helper } from "./util.js";   // 소스는 util.ts 지만 .js 로 참조
// import { helper } from "./util";   // ✗ 에러: 확장자 필요
```

셋째, **`exports` 필드와 조건부 매핑을 완전히 지원**한다. 이것이 node10 과의 결정적 차이다.

```jsonc
{
  "compilerOptions": {
    "module": "nodenext",
    "moduleResolution": "nodenext"   // module 과 짝을 맞춰야 함
  }
}
```

`module: nodenext` 를 쓰면 `moduleResolution` 도 반드시 `nodenext` 여야 한다. 짝이 안 맞으면 컴파일러가 경고한다.

## 4. package.json exports — 진입점을 통제하는 관문

`exports` 필드는 패키지가 외부에 공개할 진입점을 명시적으로 선언한다. 선언되지 않은 내부 경로(`some-lib/dist/internal.js`)는 임포트가 차단된다. 캡슐화를 강제하는 것이다.

```jsonc
// 라이브러리의 package.json
{
  "name": "my-lib",
  "type": "module",
  "exports": {
    ".": {
      "types":   "./dist/index.d.ts",
      "import":  "./dist/index.js",     // ESM 진입점
      "require": "./dist/index.cjs"     // CJS 진입점
    },
    "./utils": {
      "types":  "./dist/utils.d.ts",
      "import": "./dist/utils.js"
    }
  }
}
```

`import "my-lib"` 는 `.` 매핑을, `import "my-lib/utils"` 는 `./utils` 매핑을 탄다. 각 매핑 안의 `types`·`import`·`require` 가 **조건(condition)** 이다. 소비자가 ESM 으로 임포트하면 `import` 조건이, CJS `require` 면 `require` 조건이 선택된다. TypeScript 는 `types` 조건으로 타입 선언 위치를 찾는다.

조건은 **위에서 아래로 첫 매치**가 이긴다. 그래서 `types` 를 가장 위에, 더 구체적인 조건을 일반 조건보다 앞에 두는 순서가 중요하다. `default` 는 항상 마지막 폴백으로 둔다.

```jsonc
{
  "exports": {
    ".": {
      "types": "./index.d.ts",
      "node": "./index.node.js",     // Node 런타임 전용
      "browser": "./index.browser.js",
      "default": "./index.js"        // 폴백 (맨 마지막)
    }
  }
}
```

## 5. bundler — 번들러가 해석하는 환경

Vite·webpack·esbuild 같은 번들러는 자체 모듈 해석을 갖는다. 이들은 `exports` 를 이해하지만, ESM 처럼 확장자를 강제하지는 않는다. `moduleResolution: "bundler"` 는 이 현실을 반영한다. `exports` 조건은 존중하되 상대 임포트 확장자는 생략을 허용한다.

```jsonc
{
  "compilerOptions": {
    "module": "esnext",
    "moduleResolution": "bundler",
    "noEmit": true                   // 번들러가 실제 빌드를 담당
  }
}
```

```typescript
import { helper } from "./util";      // 확장자 생략 OK (번들러가 해석)
import { z } from "zod";              // exports 조건 존중
```

프론트엔드 앱(Vite/Next.js)이나 esbuild 로 번들하는 프로젝트에는 `bundler` 가 적합하다. TypeScript 는 타입 검사만 하고 실제 모듈 결합은 번들러에 맡기므로, 소스에 확장자를 붙이는 ESM 의 번거로움을 피할 수 있다. 반대로 Node 에서 직접 실행되는(번들 없는) 백엔드는 `nodenext` 가 맞다.

| 전략 | exports 지원 | 확장자 필수 | 주 용도 |
| --- | --- | --- | --- |
| node10 | ✗ | ✗ | 레거시 CJS (신규 금지) |
| node16 / nodenext | ✓ | ✓(ESM) | 번들 없는 Node 실행 |
| bundler | ✓ | ✗ | Vite·webpack·esbuild 앱 |

## 6. ESM ↔ CJS 상호운용의 함정

CJS 모듈을 ESM 에서 임포트할 때 default vs named export 불일치가 흔하다. CJS 의 `module.exports = fn` 은 ESM 에서 default 로 보이지만, named export 는 정적 분석이 안 되는 경우가 있다. `esModuleInterop` 이 이 간극을 메운다.

```typescript
// esModuleInterop: true 여야 자연스럽게 동작
import express from "express";        // CJS default 임포트
import * as path from "path";         // 네임스페이스 임포트
```

`esModuleInterop` 은 CJS default 임포트에 헬퍼를 삽입해 `module.exports` 를 default 처럼 다루게 한다. 이 옵션은 대부분의 현대 설정에서 켜는 것이 표준이다. 함께 `allowSyntheticDefaultImports` 가 자동으로 켜져 타입 체크도 통과한다.

**dual package hazard** 도 주의 대상이다. 하나의 패키지가 ESM·CJS 두 버전을 `import`/`require` 조건으로 제공하면, 앱 안에서 같은 패키지의 두 인스턴스가 동시에 로드되어 `instanceof` 검사나 싱글턴 상태가 깨질 수 있다. 상태를 가진 라이브러리(예: 전역 레지스트리)에서 특히 위험하다.

## 7. import type 과 verbatimModuleSyntax

타입만 쓰는 임포트가 런타임 코드로 남으면 불필요한 의존성 로드나 순환 참조를 유발한다. `import type` 은 이 임포트가 타입 전용임을 명시해, 컴파일 시 완전히 제거되도록 보장한다.

```typescript
import type { User } from "./models.js";   // 컴파일 후 완전히 사라짐
import { type Role, createUser } from "./api.js";  // Role 만 타입 전용
```

`verbatimModuleSyntax: true` 는 이를 강제한다. 값으로 쓰이지 않는 임포트에 `type` 을 붙이지 않으면 에러를 내고, 반대로 `import type` 은 절대 emit 하지 않는다. 이전의 `importsNotUsedAsValues`·`isolatedModules` 조합을 대체하는 현대적 옵션으로, ESM/CJS 출력이 소스와 1:1 로 대응돼 예측 가능해진다.

```jsonc
{
  "compilerOptions": {
    "verbatimModuleSyntax": true,
    "isolatedModules": true          // 파일 단위 트랜스파일 안전성
  }
}
```

`isolatedModules` 는 각 파일을 독립적으로 트랜스파일해도 안전하도록 강제한다(esbuild·SWC·Babel 처럼 타입 정보 없이 파일 단위로 처리하는 도구 대응). const enum 재export 같은 파일 간 타입 의존 문법을 금지한다.

## 8. 설정 검증 — 실제 해석 경로 확인

모듈 해석 문제는 추측하지 말고 컴파일러가 실제로 어느 파일을 골랐는지 추적해 확인한다. `--traceResolution` 이 해석 과정을 전부 출력한다.

```bash
# 특정 임포트가 어떻게 해석되는지 단계별 로그
tsc --traceResolution --noEmit 2>&1 | grep -A5 "my-lib"

# 출력 예시
# Resolving module 'my-lib' ...
# Found 'package.json' at '.../my-lib/package.json'.
# 'exports' field ... matched pattern '.'.
# Using condition 'import'.
# File '.../my-lib/dist/index.js' exists - use it as resolved module.
```

라이브러리 저자라면 `@arethetypeswrong/cli`(attw) 로 배포 전 exports·types 조건이 ESM/CJS 소비자 모두에게 올바른지 검증한다. "types 조건 누락", "CJS 에서 ESM-only 타입" 같은 흔한 배포 실수를 잡아 준다.

```bash
# 배포 패키지의 타입 해석 정합성 검사
npx @arethetypeswrong/cli my-lib-1.0.0.tgz
```

## trade-off 정리

`nodenext` 는 Node 런타임과 정확히 일치하는 엄격한 해석을 주지만, 소스에 `.js` 확장자를 붙여야 하는 번거로움과 ESM/CJS 경계 관리 비용이 따른다. `bundler` 는 개발 편의가 좋지만 번들러 없이 직접 실행할 수 없어 백엔드에는 부적합하다. `exports` 필드는 캡슐화를 강제해 내부 구현을 숨기지만, 소비자가 기대하던 내부 경로 접근을 막아 마이그레이션 시 breaking change 가 된다. dual package 를 제공하면 호환성이 넓어지지만 hazard 위험을 떠안는다. 프로젝트가 번들되는지 직접 실행되는지, 라이브러리인지 앱인지를 먼저 판별하고 그에 맞는 전략을 고르는 것이 모든 결정의 출발점이다.

## 참고

- TypeScript Handbook — Modules, moduleResolution, ESM/CJS Interoperability
- Node.js Documentation — Packages: exports, conditional exports, dual packages
- TypeScript 5.0 Release Notes — verbatimModuleSyntax
- arethetypeswrong (github.com/arethetypeswrong/arethetypeswrong.github.io)
