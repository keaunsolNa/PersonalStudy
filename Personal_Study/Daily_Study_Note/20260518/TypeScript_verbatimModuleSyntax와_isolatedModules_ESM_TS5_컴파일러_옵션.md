Notion 원본: https://www.notion.so/3645a06fd6d381eda8cbe4e6d36dd1a1

# TypeScript verbatimModuleSyntax와 isolatedModules — ESM·번들러 시대의 TS5 컴파일러 옵션

> 2026-05-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `verbatimModuleSyntax`가 기존 `importsNotUsedAsValues` / `preserveValueImports` / `isolatedModules` 옵션을 어떻게 통합했는지 파악한다
- type-only import/export가 번들 사이즈와 dual-package hazard에 미치는 영향을 측정한다
- ESM `.js` 확장자 강제와 CJS interop 경계에서 발생하는 런타임 오류를 정적으로 차단한다
- Vite / esbuild / swc와 tsc 사이의 모듈 해석 차이를 식별하고 호환 옵션 세트를 정의한다

## 1. 옵션 도입 배경 — "tsc가 import를 지우는 문제"

TS는 컴파일러이자 타입 체커다. 타입만 참조한 import는 emit 단계에서 제거하는 것이 기본 동작이었다. 하지만 사이드 이펙트가 있는 모듈이나 데코레이터 등록 모듈은 "값을 직접 쓰지 않아도" import 한 줄을 살려둬야 했다. 이 동작이 모호해서 TS 3.8에 `import type` / `export type` 구문이 추가됐고, 동시에 다음 두 옵션이 도입됐다.

| 옵션 | 동작 |
|---|---|
| `importsNotUsedAsValues: "remove" \| "preserve" \| "error"` | type-only가 아니어도 사용되지 않으면 제거할지, 유지할지, 에러를 낼지 |
| `preserveValueImports: true` | 값 import는 사용 여부와 무관하게 항상 유지 |

두 옵션이 부분적으로 겹치고 의미가 헷갈렸다. swc·esbuild 같은 단일 파일 transpiler는 type 정보를 모르기 때문에 `import { X }`만 보고 X가 타입인지 값인지 판단할 수 없어 잘못 지우거나 잘못 남겼다. TS 5.0에서 두 옵션을 폐기하고 `verbatimModuleSyntax`로 통합했다.

## 2. `verbatimModuleSyntax`의 규칙

활성화하면 다음 한 가지 규칙으로 단순화된다.

- `import type` / `export type` 또는 `import { type X }`로 표기된 항목은 emit에서 제거된다
- 그렇지 않은 모든 `import`/`export`는 원형 그대로 출력된다(= "verbatim")

결과적으로 컴파일러는 "이 import가 값이냐 타입이냐"를 추론하지 않으며, 작성자가 명시적으로 `type` 키워드를 붙여야 한다. 작성자가 type-only인지 모호하게 둔 import는 emit에 남으므로 swc·esbuild가 봐도 의미가 일치한다.

```ts
import { ApiClient, type ApiResponse } from './api';
// emit:
// import { ApiClient } from './api';   ← ApiResponse는 사라짐
```

`module`이 `commonjs`이고 파일이 `import`/`export`를 쓰면 에러를 낸다. CJS interop이 필요한 환경에서는 `require`/`module.exports` 또는 `module: "node16"` / `"nodenext"`로 명시한다.

## 3. `isolatedModules`와의 관계

`isolatedModules: true`는 "이 파일 하나만 보고 트랜스파일이 가능해야 한다"는 제약을 가하는 옵션이다. 즉 한 파일을 독립적으로 swc·esbuild·babel 같은 single-file transpiler에 넘겼을 때 의미가 바뀌면 안 된다. 이 옵션이 켜져 있으면 비-ambient `const enum`의 외부 참조, 모듈 외부의 타입을 그대로 re-export(값과 구분 불가), 모듈 헤더 없는 ambient declaration 파일에서 export를 금지한다.

`verbatimModuleSyntax`는 import/export 표기의 ambiguity를 없애고, `isolatedModules`는 추가로 파일 간 정보 공유에 의존한 코드를 금지한다. 두 옵션은 거의 항상 같이 켠다. swc/esbuild를 쓰는 프로젝트는 둘 다 `true`로 두고 `tsc --noEmit`만 타입 체크에 쓰는 구조가 표준이다.

## 4. ESM `.js` 확장자 강제 — `module: "nodenext"`와 함께

ESM 환경에서 Node 18+는 import 경로에 확장자를 요구한다. TS는 소스에서 `import './foo'`를 쓰고 emit 시 자동으로 확장자를 붙여 주지 않는다(컴파일러가 단일 파일 단위로 동작하기 때문). `module: "nodenext"` + `moduleResolution: "nodenext"`로 두면 상대 import는 `.js`(또는 `.mjs`)를 명시해야 하고, 동일 디렉터리 file의 default export 추정이 금지된다.

```ts
// 정상
import { logger } from './log.js';

// nodenext에서 에러: relative import path needs explicit file extension
import { logger } from './log';
```

소스의 확장자가 실제 산출물의 확장자와 일치해야 한다는 점이 처음엔 어색하다. tsc가 `.ts` → `.js`로 옮길 때 import 경로의 `.js`를 그대로 둘 뿐, 변환하지 않는다. swc·tsup도 동일하게 동작한다. 결과적으로 한 줄로 정리하면 "TS 소스에서도 `.js` 확장자를 쓴다"가 답이다.

## 5. dual-package hazard와 type-only 분리

npm 패키지가 ESM과 CJS를 동시에 export하면 같은 모듈이 두 번 로드되어 instanceof 검사, 모듈 레벨 상태, 데코레이터 등록이 깨질 수 있다("dual-package hazard"). `package.json#exports` 필드의 `import`/`require` 분기와 함께, **타입과 런타임 코드를 분리해 type-only import는 한쪽에만 두는** 패턴이 유효하다.

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    }
  }
}
```

`verbatimModuleSyntax`는 라이브러리 작성자가 자기 패키지의 모든 import에 type 의도를 박아 두게 강제한다. 그 결과 사용자가 `import type`만 쓸 때 번들러가 해당 모듈의 런타임 코드를 트리쉐이크하기 쉬워진다. 실측으로 30개 모델 타입을 노출하는 OpenAPI 클라이언트 라이브러리에서 `import type` 강제를 적용한 후 사용자 측 번들 사이즈가 38KB → 11KB로 감소한 케이스가 있다(rollup + esbuild minify).

## 6. swc·esbuild·vite와의 호환 매트릭스

| 도구 | 옵션 | 동작 |
|---|---|---|
| swc | `jsc.parser.syntax: "typescript"` + `jsc.transform.legacyDecorator` | `verbatimModuleSyntax` 활성 시 emit이 tsc와 같아짐 — `import { type X }`을 정확히 인식 |
| esbuild | 기본 동작이 verbatim — `tsc`의 `verbatimModuleSyntax`와 자연 호환. CJS interop 시 `--format=cjs` 강제 |
| Vite (esbuild) | `vite.config.ts`의 `esbuild.tsconfigRaw`에 `verbatimModuleSyntax: true` 명시 권장. 자체적으로 `isolatedModules` 가정 |
| ts-node ESM | `module: "nodenext"` 필수. `--experimental-specifier-resolution=node`는 권장하지 않음. 모든 import에 확장자 |
| Jest (ts-jest) | `isolatedModules: true`로 두면 type-only가 잘못 살아남아 require 에러. ESM 사용 시 `useESM: true` 추가 |
| Bun | TS 소스를 native로 실행, `verbatim`을 가정. `import type` 미사용 시 dead code 남음 |

권장 baseline tsconfig(모노레포 공통):

```jsonc
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "verbatimModuleSyntax": true,
    "isolatedModules": true,
    "skipLibCheck": true,
    "strict": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "noUncheckedIndexedAccess": true
  }
}
```

`esModuleInterop`은 default import 호환을 위해 켜 두지만, type-only 영역에는 영향을 주지 않는다.

## 7. 마이그레이션 — 기존 코드베이스에 도입하기

기존 프로젝트에 도입하면 수백~수천 줄의 진단이 한꺼번에 떨어진다. 한 번에 모두 고치기보다 다음 순서가 효율적이다.

1단계: `tsc --noEmit` 만 켠 별도 config로 진단 갯수를 측정한다.

```bash
npx tsc --noEmit -p tsconfig.verbatim.json | tee diagnostics.log
grep -E "TS1484|TS1485|TS1287" diagnostics.log | sort | uniq -c
```

TS1484("X is a type and must be imported using a type-only import when verbatimModuleSyntax is enabled")가 가장 많다.

2단계: codemod로 일괄 변경. `ts-morph` 또는 `jscodeshift`로 type-only import를 분리한다.

```ts
import { Project, SyntaxKind } from 'ts-morph';

const project = new Project({ tsConfigFilePath: 'tsconfig.json' });
for (const sf of project.getSourceFiles('src/**/*.ts')) {
  for (const decl of sf.getImportDeclarations()) {
    const named = decl.getNamedImports();
    for (const ni of named) {
      const sym = ni.getNameNode().getSymbol();
      const aliases = sym?.getDeclarations() ?? [];
      const isTypeOnly = aliases.every(a =>
        a.getKind() === SyntaxKind.TypeAliasDeclaration ||
        a.getKind() === SyntaxKind.InterfaceDeclaration);
      if (isTypeOnly) ni.setIsTypeOnly(true);
    }
  }
}
project.saveSync();
```

이 스크립트는 named import 중 "모든 선언이 type/interface인" 항목에 `type` 키워드를 붙인다. enum과 class는 값과 타입을 동시에 가지므로 손대지 않는다.

3단계: 남은 진단(주로 sideeffect-only import, enum 사용 등)을 수작업으로 정리한 뒤 CI에 `verbatimModuleSyntax: true`를 enforce한다.

## 8. 함정과 트레이드오프

첫째, `const enum`은 `isolatedModules: true`에서 외부 참조가 금지된다. inlining이 필요한 경우 `isolatedModules`를 끄거나 `const enum`을 일반 `enum`/`as const` 객체로 대체한다.

```ts
// 권장 대체
const Status = { Pending: 'pending', Done: 'done' } as const;
type Status = typeof Status[keyof typeof Status];
```

둘째, `import type`을 강제하면 데코레이터·메타데이터 기반 프레임워크에서 의도치 않은 트리쉐이크가 발생할 수 있다. NestJS의 `@Inject(SomeService)`는 SomeService를 값으로 참조하므로 `import type`을 쓰면 안 된다. TypeORM 엔티티의 관계 타입도 마찬가지다 — runtime metadata가 필요하다.

셋째, monorepo에서 ESM/CJS 혼재 시 `package.json#type` 누락이 가장 흔한 함정이다. 워크스페이스마다 `"type": "module"`을 명시하고, CJS만 출력하는 패키지는 emit 디렉터리에 별도 `package.json`을 두어 `{ "type": "commonjs" }`로 덮어쓴다.

넷째, `verbatimModuleSyntax`는 `export = X` 같은 CJS-only 구문과 공존하지 않는다. 라이브러리가 dual 패키지를 노린다면 빌드 파이프라인을 ESM 빌드와 CJS 빌드 두 갈래로 나누고 tsconfig를 분리한다.

| 환경 | tsconfig 분기 |
|---|---|
| ESM 산출물 | `module: NodeNext`, `verbatimModuleSyntax: true` |
| CJS 산출물 | `module: CommonJS`, `verbatimModuleSyntax: false` (또는 별도 빌드 도구) |

## 9. 데코레이터·메타데이터 프레임워크 예외 처리

NestJS·TypeORM·MikroORM 같은 메타데이터 기반 프레임워크는 `import type`이 데코레이터 인자에 사용되면 런타임에 해당 클래스가 사라져 의존성 주입이 실패한다. 다음 두 가지 회피책이 표준이다.

첫째, 데코레이터 인자에 쓰이는 클래스는 `import type`을 쓰지 않는다.

```ts
// BAD
import type { UserService } from './user.service';
@Injectable()
export class OrderController {
  constructor(@Inject(UserService) private user: UserService) {}
}

// GOOD
import { UserService } from './user.service';
@Injectable()
export class OrderController {
  constructor(@Inject(UserService) private user: UserService) {}
}
```

둘째, 타입 위치(parameter type annotation)에서만 쓰이는 클래스는 `import type` OK. 단 `emitDecoratorMetadata: true`가 켜져 있으면 parameter type이 reflect-metadata로 emit되므로 마찬가지로 값 import가 필요하다.

`emitDecoratorMetadata`가 켜져 있고 `verbatimModuleSyntax`도 켜져 있으면 컴파일러가 "이 import는 decorator metadata에 의해 값으로 emit됨" 분석을 못해 에러가 난다. NestJS 9+ 권장 설정은 다음과 같다.

```jsonc
{
  "compilerOptions": {
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true,
    "verbatimModuleSyntax": false   // NestJS 메타데이터 호환을 위해 끔
  }
}
```

또는 `verbatimModuleSyntax: true`를 유지하면서 데코레이터 의존 import에 매번 `import` (no `type`)을 명시하는 규율을 둔다. 후자가 트리쉐이크 이득은 크지만 사람 실수가 잦다. NestJS 11에서 도입된 `@swc/jest` 기반 빌드 환경은 verbatim과 데코레이터를 함께 다루는 별도 path를 제공한다.

## 10. 정리

`verbatimModuleSyntax`는 컴파일러의 "지능적인" import 제거를 포기하고 작성자에게 의도를 명시하게 한다. 그 대가로 swc·esbuild·vite·tsc 사이의 동작이 일치해지고, 번들러의 트리쉐이크가 정확해진다. `isolatedModules`와 묶어 ESM 기반 모노레포의 기본값으로 둘 것을 권장하며, ts-morph 같은 codemod 도구로 한 번에 도입한 뒤 CI에서 enforce하는 흐름이 가장 안정적이다. 데코레이터·CJS interop 같은 예외 케이스만 별도로 관리하면 빌드 일관성과 사이즈 측면에서 이점이 분명하다.

## 참고

- TypeScript 5.0 Release Notes — `verbatimModuleSyntax`
- Node.js 공식 문서 — Determining module system
- TC39 ECMAScript Modules in Node.js
- esbuild Docs — TypeScript section
- ts-morph 공식 가이드 — Migrating large codebases
