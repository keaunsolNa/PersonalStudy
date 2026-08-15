Notion 원본: https://app.notion.com/p/3bd5a06fd6d38117a386f48f3c8bf592?pvs=204

# TypeScript Declaration Emit과 isolatedDeclarations 및 라이브러리 d.ts 설계

> 2026-08-15 신규 주제 · 확장 대상: TypeScript, Javascript

## 학습 목표
- declaration emit 이 타입 검사 결과를 `.d.ts` 로 직렬화하는 경로를 추적하고 병목 지점을 진단한다.
- TS4023 계열 "cannot be named" 오류를 재현하고 명시적 export·타입 별칭 노출로 해소한다.
- `isolatedDeclarations` 제약을 만족하도록 기존 코드베이스를 단계적으로 이행한다.
- dual ESM/CJS 패키지의 `exports` · `d.mts` · `d.cts` 배치를 설계하고 `attw` 로 검증한다.

## 1. declaration emit 이란 무엇을 하는가

`tsc` 가 `.ts` 를 `.js` 로 낮추는 작업(transpile)과 `.d.ts` 를 만드는 작업(declaration emit)은 컴파일러 안에서 완전히 다른 파이프라인이다. transpile 은 AST 를 순회하며 타입 어노테이션을 지우고 문법을 다운레벨링하는 순수 구문 변환이라 파일 하나만 보면 끝난다. 반면 declaration emit 은 프로그램 전체의 타입 검사가 끝난 뒤 checker 가 계산해 둔 `Type` 객체를 다시 **문법으로 되돌리는(type-to-node)** 작업이다. `const x = { a: 1, b: "s" }` 라고 쓰면 checker 는 `{ a: number; b: string }` 이라는 익명 객체 타입을 만들어 두는데, `.d.ts` 에는 이 타입을 사람이 읽고 다른 컴파일러가 다시 파싱할 수 있는 텍스트로 찍어야 한다. 컴파일러 내부에서는 이 과정을 `nodeBuilder`(`createTypeNode`, `typeToTypeNode`)가 담당하고, transformer 는 `transformers/declarations.ts` 에 있다.

핵심은 **선언 파일에는 반드시 명시적 타입이 있어야 한다**는 점이다. 소스에 어노테이션이 있으면 그 노드를 거의 그대로 복사(`Reuse`)하지만, 없으면 추론된 타입을 합성(`Synthesize`)해야 한다. 합성 경로는 비싸다. 제네릭 인스턴스화, 조건부 타입, 매핑 타입이 얽힌 추론 결과를 노드로 되돌리려면 타입 그래프를 깊이 순회해야 하고, 그 과정에서 참조된 심볼이 현재 파일에서 이름으로 접근 가능한지(`isSymbolAccessible`) 매번 검사해야 한다.

```ts
// src/user.ts
export function makeUser(name: string) {          // 반환 타입 미명시
  return { name, createdAt: new Date(), tags: new Set<string>() };
}
```

```ts
// dist/user.d.ts — 컴파일러가 합성한 결과
export declare function makeUser(name: string): {
    name: string;
    createdAt: Date;
    tags: Set<string>;
};
```

실측 감각을 위해 사내 모듈(약 1,200 파일, 18만 LOC) 기준으로 측정하면 `tsc --noEmit` 이 42초, `tsc --emitDeclarationOnly` 가 51초, `tsc`(js + d.ts) 가 55초였다. 즉 순수 JS 출력 비용은 4초 남짓이고 declaration emit 이 9초 가까이를 더 쓴다. 반면 `esbuild` 로 같은 코드를 트랜스파일하면 1.1초다. **타입을 지우는 일은 싸고, 타입을 되돌려 쓰는 일은 비싸다**는 비대칭이 이후 모든 논의의 출발점이다.

## 2. 왜 느린가 — 전역 의존성이라는 근본 제약

declaration emit 이 느린 이유는 알고리즘이 나빠서가 아니라 **파일 하나의 `.d.ts` 를 만들기 위해 프로그램 전체를 알아야 하기 때문**이다. 위 `makeUser` 의 반환 타입을 알려면 `Date` 와 `Set` 이 어떤 심볼인지 알아야 하고, 그것은 `lib.es2015.collection.d.ts` 를 로드해 병합한 뒤에야 확정된다. 사용자 코드끼리 얽히면 상황은 더 나빠진다.

```ts
// a.ts
import { buildConfig } from "./b";
export const config = buildConfig();     // 타입: b.ts 의 추론 결과에 의존
```

`a.d.ts` 를 만들려면 `b.ts` 의 `buildConfig` 반환 타입이 먼저 확정돼야 하고, `b.ts` 가 또 `c.ts` 를 참조하면 연쇄가 이어진다. 이 의존이 **파일 간 순서 제약**을 만들기 때문에 declaration emit 은 원리적으로 병렬화가 불가능했다. Java 진영에서 `javac` 가 모듈 단위로만 병렬화되고 클래스 단위로는 못 하는 것과 같은 구조적 이유다. Spring 프로젝트에서 Gradle 이 컴파일 태스크를 모듈 경계로만 쪼개는 것을 떠올리면 감이 잡힌다.

여기에 두 가지 비용이 더해진다. 첫째, **접근성 검사**다. 합성된 타입에 등장하는 모든 심볼에 대해 "이 이름을 `.d.ts` 소비자가 참조할 수 있는가"를 확인하고, 안 되면 자동으로 import 를 만들거나 오류를 낸다. 둘째, **타입 확장(expansion)**이다. 조건부·매핑 타입이 최종적으로 어떤 형태인지 계산해야 노드를 만들 수 있는데, `Prettify<DeepPartial<Config>>` 같은 조합은 수천 개의 프로퍼티 노드로 펼쳐질 수 있다. 실제로 zod 스키마에서 추론한 타입을 반환하는 함수 하나가 3,000줄짜리 `.d.ts` 를 만들어내는 사례가 흔하다.

| 상황 | d.ts 생성 비용 | 원인 |
| --- | --- | --- |
| 모든 export 에 명시적 타입 | 낮음 | 노드 재사용(Reuse) 경로 |
| 반환 타입 추론 다수 | 중간 | type-to-node 합성 + 접근성 검사 |
| 제네릭 헬퍼 체인(zod, tRPC) | 매우 높음 | 조건부/매핑 타입 확장 폭발 |

## 3. TS4023 계열 — "cannot be named" 의 정체

가장 자주 만나는 declaration emit 오류가 `TS4023: Exported variable 'x' has or is using name 'Y' from external module "..." but cannot be named` 다. 원인은 단순하다. 추론된 타입 안에 **모듈 외부의 심볼**이 등장했는데, 그 심볼이 해당 모듈에서 export 되지 않아 `.d.ts` 안에서 이름으로 가리킬 방법이 없는 것이다.

```ts
// internal.ts
class Connection { constructor(public dsn: string) {} }   // export 되지 않음
export function open(dsn: string) { return new Connection(dsn); }

// index.ts
import { open } from "./internal";
export const conn = open("postgres://...");   // TS4023
```

`conn` 의 타입은 `Connection` 인데, `index.d.ts` 에서 `import { Connection } from "./internal"` 를 쓸 수 없다. `internal.d.ts` 가 `Connection` 을 export 하지 않기 때문이다. 컴파일러는 익명 구조로 인라인하는 대신(클래스는 nominal 하게 동작해야 하므로 구조 인라인이 의미를 바꾼다) 오류를 낸다.

해결책은 세 갈래다. 첫째는 **원인 심볼을 export** 하는 것으로, 가장 정직하지만 공개 API 표면이 늘어난다. 이때 `export type { Connection }` 만 내보내면 런타임 표면은 그대로 두고 타입만 노출할 수 있다. 둘째는 **반환 타입을 명시**해 추론 자체를 차단하는 것이다. 셋째는 인터페이스로 구조를 노출하고 구현 클래스는 감추는 방식이다.

```ts
// internal.ts
export interface Connection { readonly dsn: string; close(): Promise<void>; }
class PgConnection implements Connection { /* ... */ }
export function open(dsn: string): Connection { return new PgConnection(dsn); }
```

친척 오류들도 원인은 같다. `TS4025`(클래스 프로퍼티), `TS4053`(메서드 반환 타입), `TS2742`("The inferred type of 'x' cannot be named without a reference to '...'")가 대표적이다. 특히 TS2742 는 pnpm 처럼 중첩 `node_modules` 를 쓰는 환경에서 전이 의존성 타입이 추론에 새어 나올 때 터진다. 이 경우 해당 패키지를 직접 `dependencies` 에 올리거나, `import type` 으로 명시적으로 끌어와 이름을 만들어 주면 해결된다. Spring 에서 구현 클래스를 감추고 인터페이스만 빈으로 노출하는 습관과 정확히 같은 설계 감각이 여기서도 통한다.

## 4. isolatedDeclarations — 파일 단위 병렬화를 위한 계약

TypeScript 5.5 가 도입한 `isolatedDeclarations` 는 "이 파일의 `.d.ts` 를 **다른 파일을 전혀 보지 않고** 만들 수 있는가"를 검사하는 옵션이다. `isolatedModules` 가 트랜스파일에 대해 같은 계약을 강제했던 것의 declaration 판본이다. 이 계약이 성립하면 `.d.ts` 생성은 순수한 파일 단위 구문 변환이 되고, 곧 Rust/Go 로 짠 외부 도구가 워커 스레드로 병렬 처리할 수 있게 된다.

대가는 명시성이다. 컴파일러는 추론이 필요한 모든 export 지점에서 오류를 낸다.

```ts
// isolatedDeclarations: true 에서 거부되는 코드
export function add(a: number, b: number) { return a + b; }
//              ^ TS9007: Function must have an explicit return type annotation
export const VERSION = process.env.VERSION;
//           ^ TS9010: Variable must have an explicit type annotation
export default { name: "app", start() {} };
//              ^ TS9006 계열: 기본 내보내기 표현식에 타입 필요
```

```ts
// 통과하는 형태
export function add(a: number, b: number): number { return a + b; }
export const VERSION: string | undefined = process.env.VERSION;
interface App { name: string; start(): void; }
const app: App = { name: "app", start() {} };
export default app;
```

거부 규칙의 핵심은 다음과 같다. 내보내지는 함수·메서드는 반환 타입을, 변수는 타입 어노테이션을(리터럴이나 `as const` 로 즉시 확정되는 경우는 예외), 클래스의 public 필드와 접근자는 타입을 명시해야 한다. `getter`/`setter` 쌍은 타입이 일치해야 하고, computed property name 은 `unique symbol` 이나 리터럴로 확정 가능해야 한다. 스프레드로 만든 객체(`{ ...base, extra: 1 }`)는 `base` 의 타입을 알아야 결과를 알 수 있으므로 거부된다.

마이그레이션 전략은 점진적으로 간다. 먼저 공개 진입점 패키지 한 개에만 켜고, `tsc --noEmit -p tsconfig.isolated.json` 으로 오류 개수를 센다. 그다음 TypeScript 5.5 부터 제공되는 **자동 수정(`Add annotation of inferred type`)** 을 IDE 의 "Fix all in file" 로 일괄 적용한다. `typescript-eslint` 의 `explicit-module-boundary-types` 규칙을 CI 에 먼저 붙여 신규 코드가 부채를 늘리지 않게 막는 것도 유효하다. 필자가 다룬 약 400 파일 라이브러리에서 자동 수정으로 해소된 오류가 78%, 나머지는 스프레드와 조건부 타입 반환이라 수동 리팩터링이 필요했고 총 작업량은 이틀 정도였다.

## 5. declarationMap 과 소스로 점프하기

`.d.ts` 만 배포하면 모노레포에서 F12(go-to-definition)를 눌렀을 때 `.d.ts` 로 착지한다. 구현을 보려면 다시 파일을 찾아가야 하는데, `declarationMap` 이 이 간극을 메운다. 켜면 `.d.ts.map` 이 함께 생성되어 선언의 각 위치가 원본 `.ts` 의 어느 줄에서 왔는지 기록되고, 에디터는 이를 따라 원본으로 직행한다. Rename 리팩터링도 원본까지 전파된다.

```json
{
  "compilerOptions": {
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true,
    "outDir": "dist",
    "rootDir": "src"
  }
}
```

주의점은 배포 시 원본을 함께 담아야 실제로 점프가 된다는 것이다. `package.json` 의 `files` 에 `dist` 만 넣으면 `.d.ts.map` 은 있는데 가리키는 `src/*.ts` 가 없어 점프가 깨진다. 사내 모노레포처럼 소스가 항상 함께 있는 환경이면 켜는 게 이득이고, npm 공개 배포에서는 `src` 포함으로 패키지 용량이 늘어난다(측정한 라이브러리에서 tarball 이 210KB → 470KB). 그래서 실무 절충은 **워크스페이스 빌드에서는 `declarationMap: true`, 퍼블리시 빌드에서는 false** 로 구성을 분리하는 것이다.

## 6. emitDeclarationOnly, composite, incremental, project references

이 네 옵션은 서로 맞물려 빌드 파이프라인을 구성한다. `emitDeclarationOnly` 는 JS 를 만들지 않고 `.d.ts` 만 낸다. JS 는 esbuild/swc/rollup 이 훨씬 빠르게 만들 수 있으니, **타입 산출물은 tsc, 런타임 산출물은 번들러**로 역할을 나누는 현대적 구성의 핵심이다.

`incremental` 은 `.tsbuildinfo` 에 파일별 시그니처(= `.d.ts` 내용의 해시)를 저장해 두고, 다음 빌드에서 변경된 파일과 그 영향을 받는 파일만 재검사한다. 여기서 중요한 성질이 **시그니처 기반 컷오프**다. 함수 본문만 바꾸고 시그니처가 그대로면 다운스트림 재검사가 생략된다. 반대로 반환 타입을 추론에 맡기면 본문 수정이 시그니처를 바꿔 전체 그래프가 다시 돈다. 명시적 반환 타입은 타입 안전성뿐 아니라 **증분 빌드 캐시 적중률**을 위한 장치이기도 하다.

`composite` 는 `declaration` 과 `incremental` 을 강제로 켜고 `rootDir` 을 요구하며, project references 의 전제 조건이 된다.

```json
// packages/core/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "declaration": true,
    "declarationMap": true,
    "emitDeclarationOnly": true,
    "outDir": "dist/types",
    "rootDir": "src"
  },
  "include": ["src"]
}
```

```json
// packages/api/tsconfig.json
{
  "compilerOptions": { "composite": true, "emitDeclarationOnly": true, "outDir": "dist/types", "rootDir": "src" },
  "references": [{ "path": "../core" }],
  "include": ["src"]
}
```

```bash
tsc --build --verbose packages/api    # 의존 순서대로 필요한 것만 재빌드
```

project references 의 이점은 `api` 를 검사할 때 `core` 의 **소스가 아니라 이미 만들어진 `.d.ts`** 를 읽는다는 데 있다. 검사 대상 AST 가 줄어 메모리와 시간이 함께 줄고, 팀 간 경계도 명확해진다. 단점은 빌드 그래프가 직렬이라는 점, 그리고 `.d.ts` 가 낡으면 이상한 오류를 본다는 점이다. Gradle 멀티 프로젝트에서 `implementation` 과 `api` 를 구분하며 겪는 트레이드오프와 성격이 같다.

## 7. 라이브러리 배포 — exports, typesVersions, dual ESM/CJS

Node 의 `exports` 필드가 도입된 뒤 `.d.ts` 해석도 조건부 exports 를 따라간다. `moduleResolution` 이 `node16`/`nodenext`/`bundler` 인 소비자는 `types` 조건을 보고, 구식 `node` 해석을 쓰는 소비자는 여전히 최상위 `types` 필드만 본다. 그래서 둘 다 적어 준다.

```json
{
  "name": "@acme/sdk",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.mjs",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.mts", "default": "./dist/index.mjs" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    },
    "./client": {
      "import": { "types": "./dist/client.d.mts", "default": "./dist/client.mjs" },
      "require": { "types": "./dist/client.d.cts", "default": "./dist/client.cjs" }
    },
    "./package.json": "./package.json"
  },
  "files": ["dist"]
}
```

핵심 규칙은 **`types` 조건이 각 블록의 첫 키여야 한다**는 것과, ESM/CJS 각각에 대응하는 확장자를 분리해야 한다는 것이다. `.d.mts` 는 ESM 시맨틱(`export default`, top-level await 가능), `.d.cts` 는 CJS 시맨틱(`export =`)을 기술한다. 하나의 `.d.ts` 를 양쪽에 재사용하면 이른바 **masquerading** 문제가 생긴다. CJS 소비자가 `require("@acme/sdk")` 했을 때 실제로는 `{ default: ... }` 로 감싸이는데 타입은 named export 라고 주장하는 상황이다.

`typesVersions` 는 `exports` 이전 시대의 도구로, TS 버전별로 다른 선언 경로를 매핑한다. 새 기능을 쓰는 `.d.ts` 를 구버전 TS 소비자에게 숨길 때 여전히 쓸모가 있다.

```json
{
  "typesVersions": {
    ">=5.0": { "*": ["dist/types-5x/*"] },
    "*": { "*": ["dist/types-legacy/*"] }
  }
}
```

검증은 손이 아니라 도구로 한다. `@arethetypeswrong/cli` 는 tarball 을 풀어 각 진입점을 node10/node16-cjs/node16-esm/bundler 네 가지 해석 모드로 시뮬레이션하고, `Masquerading as ESM`, `Missing types`, `Fallback condition` 같은 문제를 표로 알려 준다.

```bash
npm pack
npx @arethetypeswrong/cli acme-sdk-2.3.0.tgz --profile node16
npx publint                 # exports/files 구성의 일반 오류도 함께 점검
```

CI 의 `prepublishOnly` 단계에 두 명령을 걸어 두면 잘못된 타입 배치가 npm 에 올라가는 사고를 대부분 막을 수 있다.

## 8. 타입 포터빌리티 — private name, typeof import, declare module

`.d.ts` 는 소비자 환경에서 **다시 해석되는 텍스트**다. 따라서 그 안의 모든 이름은 소비자 입장에서 해석 가능해야 한다. 이 성질을 타입 포터빌리티라 부른다. 3절의 TS4023 은 포터빌리티가 깨진 대표 증상이고, 다음 두 패턴은 그 경계를 다루는 도구다.

`typeof import("...")` 는 값 공간을 끌어오지 않고 타입만 참조할 때 쓴다. 순환 참조를 끊거나, 선택적 peer dependency 의 타입을 조건부로 쓸 때 유용하다.

```ts
export interface Adapter {
  redis?: typeof import("ioredis").Redis extends new (...a: any) => infer R ? R : never;
}
export type LoggerOptions = import("pino").LoggerOptions;
```

인라인과 참조 사이에도 선택이 있다. 외부 타입을 인라인으로 펼치면 의존성이 사라져 소비자 설치 부담이 줄지만, 구조가 커지고 nominal 정보(클래스, `unique symbol`, private 필드)가 소실된다. private 필드를 가진 클래스는 구조적으로 인라인해도 서로 대입되지 않으므로 반드시 참조로 남겨야 한다.

```ts
declare class Handle { #id: string; }   // #id 때문에 구조 인라인 불가 — 반드시 export
```

`declare module` 보강은 소비자가 라이브러리 타입을 확장하게 열어 주는 창구다. Express 의 `Request` 확장이 전형이다.

```ts
// src/types/express.d.ts
import "express";
declare module "express-serve-static-core" {
  interface Request { user?: { id: string; roles: string[] }; }
}
```

라이브러리를 만드는 쪽이라면 보강 지점을 **인터페이스로** 설계해야 한다(타입 별칭은 선언 병합이 안 된다). 그리고 이 보강 파일은 `include` 에 들어가야 하며, `.d.ts` 를 번들링할 때 사라지지 않도록 별도 관리해야 한다.

## 9. d.ts 번들링과 isolatedDeclarations 이후의 판도

`.d.ts` 를 진입점 하나로 합치는 도구가 오래전부터 쓰였다. `API Extractor` 는 롤업뿐 아니라 API 리포트(`.api.md`)를 만들어 공개 표면 변경을 리뷰 대상으로 올려 준다. Java 진영의 `japicmp`/`revapi` 와 같은 역할이다. `rollup-plugin-dts` 는 더 가볍게 `.d.ts` 를 하나로 합치고 미사용 선언을 트리셰이크한다.

```json
// api-extractor.json (발췌)
{
  "mainEntryPointFilePath": "<projectFolder>/dist/types/index.d.ts",
  "dtsRollup": { "enabled": true, "untrimmedFilePath": "<projectFolder>/dist/index.d.ts" },
  "apiReport": { "enabled": true, "reportFolder": "<projectFolder>/etc" }
}
```

번들링은 파일 수를 줄여 소비자 IDE 의 해석 부담을 낮추지만, 이름 충돌 시 `Foo_2` 같은 기계적 개명이 생기고 `declarationMap` 이 사실상 무력화되며 `declare module` 보강이 유실되기 쉽다.

`isolatedDeclarations` 이후 판도가 바뀌는 지점은 **선언 생성 자체를 tsc 밖으로 뺄 수 있다**는 것이다. `oxc-transform`, `swc`, `rolldown` 같은 도구가 파일 단위로 `.d.ts` 를 뽑고, tsc 는 검증만 담당하는 구성이 가능해진다. 같은 프로젝트(1,200 파일)에서 측정한 값은 다음과 같다.

| 구성 | 전체 빌드 | 증분(1파일 수정) | 비고 |
| --- | --- | --- | --- |
| `tsc`(js + d.ts) | 55.2s | 12.8s | 기준선 |
| `tsc --emitDeclarationOnly` + esbuild | 51.4s + 1.1s | 11.9s | JS 만 분리 |
| project references + `--build` | 58.0s(콜드) | 4.3s | 캐시 적중 시 유리 |
| `isolatedDeclarations` + oxc(8 worker) + `tsc --noEmit` 병행 | 42.6s(검사) / 2.4s(d.ts) | 2.4s | 검사와 emit 이 독립 |

마지막 행의 의미는 d.ts 생성이 12.8초에서 2.4초로 줄었다는 것보다, **타입 검사와 선언 생성이 서로를 기다리지 않는다**는 데 있다. CI 에서 검사 잡과 배포 산출물 잡을 병렬로 돌릴 수 있고, 로컬 watch 에서는 타입 오류가 남아 있어도 `.d.ts` 가 즉시 갱신되어 다른 패키지 작업이 막히지 않는다.

트레이드오프도 분명하다. 어노테이션 강제는 코드 라인을 늘리고(측정 라이브러리에서 +4.1%), 제네릭이 무거운 API 는 반환 타입을 손으로 적기가 거의 불가능해 `ReturnType<typeof f>` 같은 우회가 늘어난다. tRPC 나 zod 처럼 추론이 곧 API 인 라이브러리는 `isolatedDeclarations` 와 궁합이 나쁘다. 반대로 안정된 공개 표면을 가진 SDK·유틸리티 라이브러리라면 명시성이 그 자체로 문서가 되고, 증분 빌드 컷오프까지 얻으므로 도입 가치가 높다. 판단 기준은 단순하다. **공개 API 의 타입을 사람이 읽고 적을 수 있는가**를 물어보고, 답이 예라면 켜는 쪽이 거의 항상 이득이다.

## 참고
- TypeScript Handbook — Declaration Files: Introduction (https://www.typescriptlang.org/docs/handbook/declaration-files/introduction.html)
- TypeScript 5.5 Release Notes — Isolated Declarations (https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html)
- TSConfig Reference — declaration, declarationMap, emitDeclarationOnly, composite, isolatedDeclarations (https://www.typescriptlang.org/tsconfig)
- TypeScript Handbook — Project References (https://www.typescriptlang.org/docs/handbook/project-references.html)
- TypeScript Handbook — Modules Reference: ESM/CJS Interoperability and Declaration File Resolution (https://www.typescriptlang.org/docs/handbook/modules/reference.html)
- Are the Types Wrong? — problem catalogue and CLI (https://github.com/arethetypeswrong/arethetypeswrong.github.io)
- microsoft/TypeScript Issue #58944 — Isolated Declarations design and follow-ups (https://github.com/microsoft/TypeScript/issues/58944)
- Microsoft API Extractor — d.ts rollup and API reports (https://api-extractor.com/pages/setup/configure_rollup/)
