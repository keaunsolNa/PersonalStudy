Notion 원본: https://app.notion.com/p/3985a06fd6d381978ce9ffccb82ba8c7

# TypeScript 모듈 해석 전략과 nodenext ESM 및 declaration maps

> 2026-07-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `moduleResolution` 값 `node10`·`node16`·`nodenext`·`bundler` 의 해석 규칙 차이를 구분한다
- package.json 의 `exports`·`imports`·`type` 필드가 TS 모듈 해석에 미치는 영향을 설명한다
- ESM 환경에서 상대 임포트에 `.js` 확장자를 붙여야 하는 이유를 컴파일러 관점에서 분석한다
- `declaration`·`declarationMap`·`composite` 조합으로 타입 정의와 소스 점프를 구성한다

## 1. moduleResolution 전략의 계보

`node10`(구 `node`)은 CommonJS require 해석을 모사해 확장자 없는 임포트를 탐색하고 `exports` 필드를 무시한다. `node16`·`nodenext` 은 Node 의 이중 모듈(ESM+CJS)을 정확히 반영해 확장자(`.mts`/`.cts`)와 가장 가까운 package.json 의 `type` 으로 모듈 종류를 판정한다. `node16` 은 버전 고정, `nodenext` 는 최신 추종이다. `bundler` 는 번들러 해석을 모사해 `exports` 는 존중하되 확장자를 강제하지 않는다.

| 값 | exports 필드 | 확장자 필수 | ESM/CJS 판정 | 주 용도 |
|---|---|---|---|---|
| node10 | 무시 | 아니오 | 없음 | 레거시 CJS |
| node16 | 존중 | ESM 에서 예 | 예(고정) | Node 라이브러리 |
| nodenext | 존중 | ESM 에서 예 | 예(최신) | Node 라이브러리 |
| bundler | 존중 | 아니오 | 없음 | 번들되는 앱 |

## 2. module 과 moduleResolution 의 짝

`module` 은 출력 형식을, `moduleResolution` 은 임포트 해석을 정하며 짝이 맞아야 한다. `module: nodenext` 는 자동으로 `moduleResolution: nodenext`, `module: preserve` 는 `bundler` 를 기본으로 한다.

```jsonc
{ "compilerOptions": { "module": "nodenext", "moduleResolution": "nodenext", "target": "es2022" } }
```

`verbatimModuleSyntax` 는 타입 전용 임포트를 명시 강제해 CJS/ESM 혼용 시 임포트가 지워지는지 남는지를 예측 가능하게 만든다.

## 3. package.json exports 와 조건부 해석

조건 키(`import`·`require`·`types`·`default`)는 위에서 아래로 매칭되며 `types` 는 반드시 먼저 와야 TS 가 선언을 찾는다.

```jsonc
{
  "type": "module",
  "exports": {
    ".": { "types": "./dist/index.d.ts", "import": "./dist/index.js", "require": "./dist/index.cjs" }
  }
}
```

이중 패키지에서 ESM·CJS 타입이 다르면 dual package hazard 가 발생하므로 `arethetypeswrong` 로 배포 전 점검한다. 내부 임포트용 `imports` 필드(`#` 프리픽스)도 TS 가 해석한다.

## 4. ESM 상대 임포트와 .js 확장자

`nodenext` 에서 ESM 상대 임포트에는 확장자가 필수다. 소스가 `.ts` 라도 임포트에는 `.js` 를 쓴다.

```typescript
import { validate } from "./validator.js";
import type { User } from "./types.js";
```

`tsc` 는 경로 문자열을 재작성하지 않고 그대로 출력에 복사하므로, 소스에 쓴 경로가 런타임에 실행될 `.js` 경로여야 한다. TS 5.7 의 `rewriteRelativeImportExtensions`, `allowImportingTsExtensions` 는 제한된 상황에서만 `.ts` 임포트를 허용한다.

## 5. declaration 과 declarationMap

`declaration: true` 는 `.d.ts` 를, `declarationMap: true` 는 `.d.ts.map` 을 만들어 선언과 원본 `.ts` 를 연결한다(원본 소스도 배포해야 점프 동작). `sourceMap` 은 런타임 스택 트레이스를 원본 줄로 매핑하는 별개 기능이다.

```jsonc
{ "compilerOptions": { "declaration": true, "declarationMap": true, "outDir": "./dist", "rootDir": "./src" } }
```

## 6. Project References 와 composite 연동

패키지 간 참조는 대상이 `composite: true` 여야 하며, composite 는 `declaration` 을 강제하고 `.tsbuildinfo` 로 증분 빌드를 가능하게 한다. `references` 로 의존을 명시하고 `tsc --build` 로 위상 정렬 빌드한다.

```jsonc
{ "compilerOptions": { "module": "nodenext", "moduleResolution": "nodenext" }, "references": [{ "path": "../../packages/core" }] }
```

declarationMap+composite 를 함께 켜면 모노레포 내부 소스 점프가 된다.

## 7. 검증 예시

`tsc --traceResolution` 으로 후보 경로를 확인한다. 실패해야 할 임포트가 실제 에러를 내는지 단언한다.

```typescript
// @ts-expect-error : Relative import paths need explicit file extensions
import { a } from "./missing-ext";
import { b } from "./present-ext.js"; // 통과
```

배포 타입 정합성은 `arethetypeswrong --pack` 을 CI 에 넣어 exports 조건별 해석을 자동 점검한다.

## 참고

- TypeScript Handbook — Modules Theory / Reference
- TypeScript 5.0 Release Notes — `moduleResolution: bundler`, `verbatimModuleSyntax`
- Node.js Documentation — Packages: `exports`, `imports`, conditional exports
- arethetypeswrong (github.com/arethetypeswrong/arethetypeswrong.github.io)
