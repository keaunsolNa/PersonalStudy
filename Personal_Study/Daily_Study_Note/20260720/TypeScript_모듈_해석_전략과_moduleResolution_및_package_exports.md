Notion 원본: https://www.notion.so/3a35a06fd6d381c49edde9e95493596d

# TypeScript 모듈 해석 전략과 moduleResolution 및 package exports

> 2026-07-20 신규 주제 · 확장 대상: TypeScript 컴파일러 옵션

## 학습 목표

- `moduleResolution` 의 각 모드(classic/node10/node16/nodenext/bundler)가 파일을 찾는 알고리즘 차이를 구분한다
- `package.json` 의 `exports`/`imports` 필드와 conditional exports 가 타입 해석에 미치는 영향을 추적한다
- ESM/CJS 이중 패키지에서 확장자(`.js`/`.mjs`/`.cjs`)와 `type` 필드의 상호작용을 진단한다
- `declaration` 과 `.d.ts` 해석, `declarationMap` 을 이용한 소스 이동을 설정한다

## 1. 모듈 해석이란 무엇을 결정하는가

`import { x } from './foo'` 한 줄을 만나면 컴파일러는 두 가지를 결정해야 한다. 첫째, `./foo` 가 실제로 어느 파일인가(파일 확장자 보강과 디렉터리 인덱스 탐색). 둘째, 그 파일의 타입 정보를 어디서 가져오는가(`.ts` 원본인지 `.d.ts` 선언인지). `moduleResolution` 컴파일러 옵션이 이 탐색 알고리즘을 통째로 바꾼다. 잘못 설정하면 런타임에는 동작하는데 타입 체크만 깨지거나, 반대로 타입은 맞는데 번들러가 파일을 못 찾는 불일치가 생긴다.

중요한 원칙은 TypeScript 의 모듈 해석이 반드시 실제 런타임(Node.js, 번들러)의 해석과 **일치해야** 한다는 것이다. TypeScript 는 타입만 검사할 뿐 파일을 재작성하지 않으므로, 컴파일러가 상상하는 경로와 런타임이 실제로 로드하는 경로가 어긋나면 곳바로 버그가 된다.

## 2. 레거시 모드: classic 과 node10

`classic` 은 TypeScript 초창기의 방식으로 `node_modules` 를 전혀 참조하지 않고 상대 경로만 위로 거슬러 탐색한다. 사실상 폐기된 모드이며 오늘날 쓸 이유가 없다. `node10`(구 이름 `node`)은 Node.js 의 CommonJS `require` 해석을 모사한다. 확장자 없는 import 에 대해 `.ts`, `.tsx`, `.d.ts` 를 순서대로 붙여 보고, 디렉터리면 `package.json` 의 `main` 필드나 `index` 파일을 찾는다.

```jsonc
{
  "compilerOptions": {
    "module": "commonjs",
    "moduleResolution": "node10"
  }
}
```

`node10` 의 핵심 한계는 `package.json` 의 `exports` 필드를 **이해하지 못한다**는 것이다. 최신 패키지들은 `exports` 로 진입점을 엄격히 통제하는데, `node10` 은 이를 무시하고 물리적 파일 경로로 직접 접근하려 한다. 그래서 `exports` 를 쓰는 최신 라이브러리를 `node10` 환경에서 import 하면 "Cannot find module" 또는 잘못된 `.d.ts` 를 집는 문제가 생긴다.

## 3. 현대 모드: node16 과 nodenext

`node16`/`nodenext` 는 Node.js 의 ESM 지원 이후 해석 규칙을 정확히 반영한다. 가장 큰 변화는 파일의 모듈 형식이 `package.json` 의 `type` 필드와 확장자로 결정된다는 점이다. `type: "module"` 이면 `.js` 는 ESM, `.cjs` 는 CommonJS 다. `type` 이 없거나 `commonjs` 면 `.js` 는 CJS, `.mjs` 는 ESM 이다.

이 모드에서는 상대 경로 import 에 **명시적 확장자**가 요구된다. ESM 은 Node.js 런타임에서 확장자 생략을 허용하지 않기 때문이다. 그런데 여기서 TypeScript 특유의 규칙이 등장한다. 소스에서는 `.js` 확장자를 써야 하며 컴파일러가 이를 `.ts` 로 역매핑해 찾는다.

```typescript
// src/main.ts 에서 src/util.ts 를 import 할 때
import { helper } from './util.js'; // .js 로 쓰지만 실제로는 util.ts 를 찾음
```

이 규칙이 낯설지만 논리는 일관적이다. 컴파일 결과물은 `util.js` 가 되고 ESM 런타임은 확장자를 요구하므로, 소스도 최종 산출물의 확장자를 미리 적어야 한다. `.ts` 로 쓰면 오히려 에러가 난다.

| 옵션 | exports 인식 | 확장자 요구 | 형식 결정 방식 |
|---|---|---|---|
| classic | 없음 | 불필요 | 항상 상대 탐색 |
| node10 | 무시 | 불필요 | 항상 CJS 가정 |
| node16/nodenext | 완전 지원 | 필수(.js) | type 필드 + 확장자 |
| bundler | 완전 지원 | 불필요 | import 문법 기준 |

## 4. bundler 모드: 번들러 친화 절충안

`bundler` 모드는 TypeScript 5.0 에서 추가되었다. Vite, esbuild, webpack 같은 번들러는 확장자 없는 import 를 알아서 해석하고 `exports` 필드도 존중한다. `bundler` 는 이 환경을 정확히 반영한다. `exports` 를 완전히 지원하면서도 상대 경로에 확장자를 강제하지 않는다. 프론트엔드 프로젝트 대부분이 이 모드로 정착했다.

```jsonc
{
  "compilerOptions": {
    "module": "esnext",
    "moduleResolution": "bundler",
    "resolvePackageJsonExports": true,
    "resolvePackageJsonImports": true
  }
}
```

`bundler` 를 선택할 때 `module` 은 `esnext` 또는 `preserve` 여야 한다. `commonjs` 와는 조합할 수 없다. 이유는 번들러가 ESM 문법을 그대로 소비하고 최종 번들링을 담당하므로 TypeScript 가 모듈 형식을 변환하지 않도록 두어야 하기 때문이다. `bundler` 모드는 Node.js 로 직접 실행하는 백엔드 서버에는 부적합하다. 이 경우는 반드시 `node16`/`nodenext` 를 써야 한다.

## 5. package.json exports 필드와 conditional exports

`exports` 는 패키지가 외부에 노출할 진입점을 선언하고 내부 파일 접근을 차단하는 필드다. conditional exports 는 조건(환경)에 따라 서로 다른 파일을 매핑한다. `types`, `import`, `require`, `default` 조건이 대표적이며 순서가 중요하다.

```jsonc
{
  "name": "my-lib",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    }
  }
}
```

조건은 위에서 아래로 첫 매칭이 채택되므로 `types` 를 항상 맨 앞에 둔다. 그렇지 않으면 `import`/`require` 가 먼저 매칭되어 타입 정보를 못 찾는다. `exports` 에 선언되지 않은 경로는 접근이 차단되어 캡슐화가 강제된다.

## 6. ESM/CJS 이중 패키지와 타입 이중화

라이브러리가 ESM 과 CJS 를 모두 지원하려면 각 형식에 맞는 `.d.ts` 를 분리해야 할 때가 있다. `export =` 문법(CJS 전용)과 `export default`(ESM)를 함께 노출하는 경우 조건별로 `index.d.ts` 와 `index.d.cts` 를 나눠야 한다.

```jsonc
{
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.mts", "default": "./dist/index.mjs" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    }
  }
}
```

`node16` 이상에서만 이 세밀한 분기가 정확히 동작하며, `node10` 은 무시하고 `main`/`types` 최상위 필드로 폴백한다. 이중 패키지의 가장 큰 함정은 "이중 패키지 위험(dual package hazard)"으로, 같은 클래스가 ESM 과 CJS 두 경로로 두 번 로드되면 `instanceof` 가 실패한다.

## 7. declaration 과 declarationMap 설정

`declaration: true` 로 각 소스에 대응하는 선언 파일을 만든다. `declarationMap: true` 를 켜면 `.d.ts.map` 이 함께 생성되어, 소비자가 IDE 에서 "Go to Definition" 을 눌렀을 때 `.d.ts` 가 아니라 원본 `.ts` 로 점프한다. 이는 모노레포에서 패키지 간 이동 경험을 크게 개선한다.

```jsonc
{
  "compilerOptions": {
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true,
    "outDir": "./dist",
    "rootDir": "./src"
  }
}
```

`declarationMap` 이 제대로 동작하려면 배포 패키지에 `.ts` 원본과 `.d.ts.map` 을 함께 포함해야 한다. 원본을 제외하고 배포하면 맵이 가리키는 파일이 없어 점프가 실패한다.

## 8. 진단 도구와 실전 디버깅

모듈 해석 문제를 진단하는 가장 강력한 도구는 `tsc --traceResolution` 이다. 컴파일러가 각 import 를 어떤 순서로 어떤 파일에서 찾았는지 전부 출력한다. "File exists"/"File does not exist" 로그를 따라가면 어느 단계에서 잘못된 파일을 집었는지 정확히 보인다.

```bash
tsc --traceResolution --noEmit 2>&1 | grep -A 20 "my-lib"
```

또 하나 유용한 것은 커뮤니티 도구 `arethetypeswrong`(attw)로, 배포된 패키지의 `exports`/`types` 조건이 ESM/CJS 각 환경에서 올바르게 해석되는지 매트릭스로 검사한다. 정리하면, 백엔드 Node.js 실행은 `nodenext`, 번들러 기반 프론트엔드는 `bundler`, 그리고 배포 패키지는 `exports` 의 `types` 조건을 맨 앞에 두는 것이 2026 년 기준 안전한 기본값이다.

## 참고

- TypeScript Handbook — Modules Reference / moduleResolution (https://www.typescriptlang.org/docs/handbook/modules/reference.html)
- TypeScript 5.0 Release Notes — `--moduleResolution bundler`
- Node.js Documentation — Packages: exports, conditional exports
- arethetypeswrong (attw) — 패키지 타입 해석 검증 도구
