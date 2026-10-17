Notion 원본: https://app.notion.com/p/3fc5a06fd6d381e090eec2758e8eb147?pvs=204

# TypeScript moduleResolution node16·nodenext·bundler와 package.json exports·conditions 해석 구조

> 2026-10-17 신규 주제 · 확장 대상: TypeScript(tsc 컴파일러 옵션과 모듈 해석)

## 학습 목표

- `module`과 `moduleResolution`이 각각 emit 문법과 import 경로 탐색을 나눠 맡는 구조를 구분한다
- node16·nodenext가 `.mts`/`.cts`/`type` 필드로 파일마다 ESM·CJS를 판정하는 규칙을 추적한다
- `exports` 맵에서 서브패스와 조건이 선택되는 순서를 따라가 타입 해석 결과를 예측한다
- bundler 모드와 `customConditions`를 모노레포에 적용하고 `--traceResolution`으로 검증한다

## 1. module과 moduleResolution은 서로 다른 질문에 답한다

tsconfig의 `module`은 "컴파일 결과물에 어떤 모듈 문법을 내보낼 것인가"를 정하고, `moduleResolution`은 "`import "x"` 문자열을 어떤 파일로 연결할 것인가"를 정한다. 둘은 TypeScript 4.x까지 느슨하게 짝지어 쓰였지만, 5.2부터는 `module: nodenext`이면 `moduleResolution`도 `nodenext`여야 하도록 조합을 강제한다. 해석 전략은 총 다섯 가지(classic, node10, node16, nodenext, bundler)이며 node10은 예전 이름이 `node`다. classic은 TypeScript 1.x 시절 방식이라 새 프로젝트에서 쓸 이유가 없다.

핵심 차이는 `package.json`의 `exports` 필드를 읽는가와 ESM 규칙(확장자 필수, 디렉터리 import 금지)을 강제하는가에 있다. node10은 `exports`를 전혀 보지 않고 `main`, `types`, `typesVersions`만 본다. 그래서 `exports`로만 서브패스를 공개하는 패키지(예: `pkg/feature`)가 node10에서는 `Cannot find module` (TS2307)로 실패하는 일이 흔하다. 반대로 node16 이상에서는 `exports`가 있으면 `main`과 최상위 `types`는 무시된다.

| 전략 | exports 해석 | 상대 경로 확장자 | 파일별 ESM/CJS 판정 | 짝이 되는 module |
|---|---|---|---|---|
| node10 | 읽지 않음 | 생략 가능 | 없음 | commonjs 등 |
| node16 | 읽음 | ESM 파일에서는 필수 | 있음 | node16 |
| nodenext | 읽음 (최신 Node 동작 추적) | ESM 파일에서는 필수 | 있음 | nodenext |
| bundler | 읽음 | 생략 가능 | 없음 (import 조건 기준) | es2015 이상, preserve |

node16은 Node 16 시점의 규칙으로 고정되어 있고, nodenext는 TypeScript 버전이 올라가며 최신 Node 동작을 따라간다. 예를 들어 TypeScript 5.8의 `nodenext`는 Node 22 계열에서 가능해진 `require()`로 ESM 불러오기를 허용한다. 장기 지원 Node 버전에 맞춰 재현성을 고정하고 싶다면 node16을, 항상 최신 규칙을 따르고 싶다면 nodenext를 고른다. 아래는 Node 위에서 직접 실행되는 서버·CLI 프로젝트의 기본 형태다.

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "strict": true,
    "outDir": "dist",
    "declaration": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
```

## 2. node16·nodenext: 파일마다 ESM인지 CJS인지 판정한다

Node는 `.mjs`는 ESM, `.cjs`는 CJS로 보고, `.js`는 가장 가까운 상위 `package.json`의 `"type"` 값(`module`이면 ESM, 없거나 `commonjs`이면 CJS)으로 정한다. TypeScript는 이 규칙을 소스 확장자에 그대로 대응시킨다. `.mts`는 `.mjs`로, `.cts`는 `.cjs`로 emit되고, `.ts`는 `type` 필드에 따라 어느 쪽이든 될 수 있다. 같은 `.ts` 파일이라도 위치한 패키지의 `type`에 따라 emit 결과가 `import` 구문 그대로이거나 `require` 호출로 바뀐다는 뜻이다.

ESM으로 판정된 파일에서는 Node의 ESM 로더 규칙이 적용된다. 상대 경로에 확장자를 반드시 적어야 하며, 확장자는 소스(`.ts`)가 아니라 emit될 파일(`.js`)로 쓴다. 생략하면 TS2835 ("Relative import paths need explicit file extensions in ECMAScript imports ... Did you mean './util.js'?")가 나고, 컴파일러가 올바른 제안까지 해 준다. 디렉터리 import(`./lib`가 `./lib/index.js`를 가리키는 방식)도 ESM에서는 금지된다.

```ts
// src/main.ts  (package.json 에 "type": "module" 이 있는 프로젝트)
import { greet } from "./util.js";      // util.ts 가 있어도 .js 로 쓴다
import { readFile } from "node:fs/promises";

const text = await readFile(new URL("./data.txt", import.meta.url), "utf8");
console.log(greet(text.trim()));
```

```ts
// src/util.ts
export function greet(name: string): string {
  return `hello, ${name}`;
}
```

CJS 파일에서 ESM 전용 패키지를 `import x from "esm-only"`로 불러오면 emit이 `require`가 되므로 TS1479 ("The current file is a CommonJS module whose imports will produce 'require' calls; however, the referenced file is an ECMAScript module and cannot be imported with 'require'") 가 난다. 이때의 표준 우회로는 동적 `import()`다. node16 계열은 CJS 파일에서도 `import()` 표현식을 `require`로 바꾸지 않고 그대로 두기 때문에 동작한다.

```ts
// src/legacy.cts  (CJS 로 emit 되는 파일)
export async function loadEsmOnly() {
  const mod = await import("esm-only-pkg");
  return mod.default;
}
```

반대 방향의 비용도 있다. ESM 파일에서는 `__dirname`, `require`, `import x = require()` 구문을 쓸 수 없고, `import.meta.url`(Node 20.11 이상이면 `import.meta.dirname`도)로 대체해야 한다. 기존 CJS 코드베이스에 `"type": "module"`을 켜는 순간 상대 import 수백 줄에 `.js`를 붙이는 기계적 수정이 필요하다는 점이 가장 큰 마이그레이션 비용이다. 이 비용을 피하려고 번들러를 거치는 프로젝트라면 5절의 bundler 모드가 더 맞다.

## 3. exports 맵은 어떻게 서브패스를 고르는가

`exports`는 패키지의 공개 표면을 선언하는 필드다. 문자열 하나를 쓰면 루트 진입점만 공개하는 축약형이고, 객체로 쓰면 키가 `.`으로 시작하는 서브패스 맵이 된다. 맵에 없는 서브패스는 존재하는 파일이라도 import할 수 없다(캡슐화). 따라서 `pkg/dist/internal.js`처럼 내부 파일에 직접 접근하던 코드는 node16 이상으로 옮기는 순간 TS2307로 깨진다. 의도적으로 열고 싶은 경로만 맵에 넣으면 된다.

서브패스 패턴은 키와 값에 별표를 정확히 한 번씩 써서 만든다. 패턴 하나로 폴더 전체를 공개할 수 있는 대신, 값 쪽 치환 결과가 `.d.ts`와 `.js`의 짝을 맞춰 주지는 않으므로 조건 객체와 함께 써야 타입 파일을 찾는다.

```json
{
  "name": "@acme/kit",
  "version": "1.0.0",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "default": "./dist/index.js"
    },
    "./features/*": {
      "types": "./dist/features/*.d.ts",
      "default": "./dist/features/*.js"
    },
    "./package.json": "./package.json"
  }
}
```

이 패키지를 node16 이상에서 가져오는 소비자 코드는 다음과 같이 해석된다. 첫 줄은 `.` 키, 둘째 줄은 `./features/*` 키에 `*`가 `date`로 치환된 결과를 따라간다. 세 번째 줄은 맵에 없는 경로라 실패한다.

```ts
import { createKit } from "@acme/kit";                // dist/index.d.ts
import { parseDate } from "@acme/kit/features/date";  // dist/features/date.d.ts
import { secret } from "@acme/kit/dist/internal.js";  // TS2307: 캡슐화로 차단
```

`./package.json`을 명시적으로 공개하는 관례는 도구들이 버전을 읽을 때 `exports` 캡슐화에 막히지 않도록 하기 위한 것이다. 한 가지 더 알아 둘 점은 패키지가 자기 이름으로 자기 자신을 import할 수 있다는 것이다(self-reference). `exports`가 있는 패키지 안에서 `@acme/kit/features/date`를 쓰면 상대 경로 없이도 같은 맵을 거쳐 해석되므로, 테스트 코드가 실제 소비자와 동일한 경로를 검증하게 만들 수 있다.

## 4. conditions 매칭: 순서가 곧 우선순위다

값 자리에 객체를 쓰면 키가 조건 이름이 된다. 해석기는 객체의 키를 위에서 아래로 훑으며 현재 활성화된 조건과 일치하는 첫 번째 키를 채택하고, 그 아래 키는 보지 않는다. 일치 여부는 이름 비교일 뿐이고 키의 의미를 해석기가 알지는 못한다. 그래서 `default`는 어떤 조건이든 일치하는 항목이므로 반드시 마지막에 둬야 하며, `types`는 `import`/`require`보다 앞에 둬야 타입 해석이 먼저 걸린다.

TypeScript가 활성화하는 조건은 모드에 따라 다르다. 아래 표는 TypeScript 공식 문서의 모듈 해석 설명을 요약한 것이다.

| 모드·상황 | 활성 조건 |
|---|---|
| node16/nodenext, ESM 파일의 import | `types`, `import`, `node`, `default` |
| node16/nodenext, CJS 파일의 require | `types`, `require`, `node`, `default` |
| bundler, import 구문 | `types`, `import`, `default` + `customConditions` |
| bundler, require 호출·import = require | `types`, `require`, `default` + `customConditions` |

여기서 `node` 조건은 node16 계열에서만 켜지고 bundler에서는 꺼져 있다는 점이 실무에서 자주 문제를 만든다. `exports`에 `"node": "./dist/node.js"`와 `"default": "./dist/browser.js"`를 나눠 둔 패키지는 node16에서는 node 쪽 타입이, bundler에서는 default 쪽 타입이 잡힌다. 두 쪽의 `.d.ts`가 달라지면 같은 소스가 모드에 따라 다른 타입 에러를 낸다.

이중 형식(dual) 패키지에서는 `import`와 `require` 아래에 각각 `types`를 둬야 한다. `.d.ts` 하나를 양쪽이 공유하면, 그 파일이 `type: module` 범위에서는 ESM으로, 별도 확장자가 없으면 같은 패키지 범위 규칙이 적용되기 때문에 CJS 소비자가 ESM 선언을 읽게 되어 "ESM 모듈을 require 한다"는 가짜 오류가 난다.

```json
{
  "name": "@acme/dual",
  "version": "2.0.0",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "import": {
        "types": "./dist/index.d.ts",
        "default": "./dist/index.js"
      },
      "require": {
        "types": "./dist/index.d.cts",
        "default": "./dist/index.cjs"
      }
    },
    "./package.json": "./package.json"
  }
}
```

최상위 `main`, `module`, `types`는 `exports`를 모르는 구형 도구와 node10을 위한 폴백으로만 남긴다. Node는 `module` 필드를 알지 못하며 번들러 관례일 뿐이라는 점도 기억해 둔다.

## 5. bundler 모드: 번들러가 하는 일을 흉내 낸다

`moduleResolution: bundler`는 TypeScript 5.0에서 추가되었다. webpack, Vite, esbuild 같은 번들러는 상대 경로의 확장자를 생략해도 찾아 주고 디렉터리 `index`도 해석해 주므로, 번들러 위에서 도는 프론트엔드·라이브러리 개발에서 node16의 확장자 강제는 이득 없이 비용만 만든다. bundler 모드는 `exports`와 `imports` 필드는 읽되 ESM 확장자 강제는 하지 않는 절충이다. 이 모드의 `module`은 es2015 이상이어야 하고, TypeScript 5.4에서 추가된 `module: preserve`가 가장 자연스러운 짝이다. preserve는 import/export를 변환하지 않고 그대로 남긴다.

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "preserve",
    "moduleResolution": "bundler",
    "noEmit": true,
    "strict": true,
    "verbatimModuleSyntax": true,
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
```

이 구성에서 `noEmit: true`는 타입 검사만 tsc가 맡고 변환은 번들러가 맡는다는 분업을 뜻한다. `allowImportingTsExtensions`는 `import "./util.ts"`처럼 `.ts`를 그대로 적는 것을 허용하는 옵션인데, tsc가 이를 `.js`로 바꿔 주지 않기 때문에 `noEmit`이나 `emitDeclarationOnly`와 함께일 때만 켤 수 있다. `verbatimModuleSyntax`는 `import type`이 없는 import는 emit 후에도 남긴다는 규칙을 강제해 번들러와 tsc의 판단 차이를 없앤다.

bundler 모드의 trade-off는 분명하다. 얻는 것은 확장자 없는 import와 번들러 생태계와의 일치다. 잃는 것은 Node에서의 실행 보장이다. tsc가 통과해도 같은 코드를 `node dist/main.js`로 돌리면 ESM 확장자 규칙에 걸려 `ERR_MODULE_NOT_FOUND`가 날 수 있다. 그래서 npm에 배포하는 라이브러리를 bundler 모드로 컴파일하고 결과물을 번들 없이 그대로 배포하는 구성은 위험하다. 소비자가 node16 이상이면 확장자 없는 상대 import가 선언 파일(`.d.ts`) 안에서도 해석에 실패해 타입이 `any`로 무너질 수 있기 때문이다. 라이브러리는 node16/nodenext로 검증하거나, 번들러로 내보낼 때 선언 파일 확장자까지 정리해야 한다.

## 6. 이중 패키지의 타입 함정과 점검 방법

dual package는 한 패키지가 ESM과 CJS를 동시에 내보내는 구성이다. 두 진입점이 서로 다른 모듈 인스턴스를 만들 수 있다는 dual package hazard(같은 클래스가 두 번 로드되어 `instanceof`가 깨지는 문제)가 런타임 쪽 대표 함정이고, 타입 쪽 대표 함정은 앞서 본 `.d.ts` 공유 문제다. 가장 단순한 회피책은 ESM 단독 배포이며, Node 22 이상에서 `require(esm)`이 지원되고 TypeScript 5.8의 nodenext가 이를 허용하기 때문에 ESM 단독 배포의 호환성 비용은 예전보다 줄었다. 다만 오래된 Node 지원 범위를 가진 라이브러리라면 여전히 dual이 필요하다.

배포 전 점검에는 `@arethetypeswrong/cli`(attw)가 쓸 만하다. tarball을 풀어 node10, node16(CJS), node16(ESM), bundler 네 가지 해석을 모두 시뮬레이션하고, 문제를 유형별로 보고한다. 대표 유형은 NoResolution(해석 실패), FalseCJS(CJS로 해석되지만 실제는 ESM 선언), FalseESM(그 반대), CJSResolvesToESM(require가 ESM으로 연결됨)이다.

```bash
npm pack
npx @arethetypeswrong/cli ./acme-dual-2.0.0.tgz
# 또는 패키지 디렉터리에서 한 번에
npx @arethetypeswrong/cli --pack .
```

빌드 도구 쪽에서는 tsup이나 unbuild 같은 도구가 `.cjs`/`.d.cts` 쌍을 자동 생성하지만, 생성물이 위 `exports`와 일치하는지는 결국 attw 같은 검증으로 확인해야 한다. 점검 비용은 CI에서 명령 한 줄이고, 놓쳤을 때의 비용은 소비자 프로젝트마다 TS1479나 `any` 추론이 나타나는 이슈 폭주다. 그래서 "빌드 산출물 → pack → attw"를 릴리스 파이프라인의 필수 단계로 두는 편이 낫다.

## 7. imports 필드와 customConditions로 모노레포 소스를 직접 참조하기

`exports`가 외부에 공개하는 표면이라면 `imports`는 패키지 내부 전용 별칭이다. 키는 반드시 `#`으로 시작하며 `tsconfig`의 `paths`와 달리 Node 런타임도 같은 규칙으로 해석하므로, 컴파일 타임과 런타임이 어긋나지 않는다. node16, nodenext, bundler에서 `resolvePackageJsonImports`가 기본으로 켜져 있다.

```json
{
  "name": "@acme/app",
  "type": "module",
  "imports": {
    "#db/*": {
      "types": "./src/db/*.ts",
      "default": "./dist/db/*.js"
    }
  }
}
```

```ts
import { connect } from "#db/pool.js";
```

모노레포에서 자주 쓰는 패턴은 `customConditions`다(TypeScript 5.0, node16·nodenext·bundler에서만 사용 가능). 패키지가 `exports`에 비표준 조건을 추가해 소스 `.ts`를 직접 가리키게 하고, 개발 중에는 그 조건을 켜서 빌드 없이 타입과 런타임을 함께 참조한다.

```json
{
  "name": "@acme/ui",
  "type": "module",
  "exports": {
    ".": {
      "@acme/source": "./src/index.ts",
      "types": "./dist/index.d.ts",
      "default": "./dist/index.js"
    }
  }
}
```

```json
{
  "compilerOptions": {
    "module": "preserve",
    "moduleResolution": "bundler",
    "customConditions": ["@acme/source"],
    "noEmit": true
  }
}
```

커스텀 조건 키를 `types`보다 앞에 두는 것이 핵심이다. 조건이 꺼진 일반 소비자에게는 이 키가 건너뛰어지고, 켠 쪽에서만 먼저 매칭된다. 런타임 도구에도 같은 조건을 알려야 한다. Node는 `node --conditions=@acme/source main.js` 형태로, Vite는 `resolve.conditions` 옵션으로 지정한다. 한쪽만 켜면 타입은 소스를, 실행은 dist를 보게 되어 디버깅하기 어려운 불일치가 생긴다. 또 tsc로 emit하는 프로젝트에서 소스 `.ts`를 다른 패키지에서 끌어오면 `rootDir` 밖의 파일이 포함되어 구조가 어긋나므로, 이 패턴은 `noEmit` 검사용 구성이나 번들러 구성에 한정하는 편이 안전하다.

## 8. 해석을 추적하는 방법과 모드 선택 기준

해석이 기대와 다를 때는 추측하지 말고 컴파일러에게 해석 과정을 출력시킨다. `--traceResolution`은 import 하나마다 후보 경로 시도 순서와 최종 채택 파일, 사용된 조건을 모두 보여 준다. 출력이 길기 때문에 관심 있는 모듈명으로 걸러 본다.

```bash
npx tsc --noEmit --traceResolution | grep -A 25 "Resolving module '@acme/kit'"
npx tsc --noEmit --explainFiles | grep "@acme/kit"
npx tsc --showConfig        # 상속까지 합쳐진 최종 옵션
```

출력에서 "Matched 'exports' condition 'import'" 같은 줄이 어느 조건으로 해석됐는지를 알려 준다. 자주 만나는 오류는 아래처럼 정리된다.

| 오류 | 원인 | 해결 |
|---|---|---|
| TS2307 Cannot find module | exports에 없는 서브패스, 또는 node10에서 exports 전용 패키지 | 맵에 경로 추가, 또는 모드를 node16 이상/bundler로 변경 |
| TS2835 확장자 필요 | ESM 파일의 상대 import에 확장자 없음 | `./util.js`처럼 emit 확장자로 작성 |
| TS1479 require 불가 | CJS 파일에서 ESM 전용 패키지 import | 동적 `import()` 사용, 또는 파일을 `.mts`로 전환 |
| 타입이 any 로 무너짐 | 조건 객체에 types 누락, 또는 types가 default 뒤에 위치 | types를 가장 앞으로 이동 |

모드 선택은 실행 환경으로 정리된다. Node가 직접 실행하는 서버와 CLI, 그리고 npm에 배포하는 라이브러리는 nodenext가 맞다. 컴파일러가 Node의 실제 동작과 같은 규칙을 강제하므로 "tsc는 통과하지만 런타임에서 깨짐"이 가장 적다. Vite, webpack, esbuild 등으로 번들되는 애플리케이션은 bundler와 `module: preserve`를 쓰고 변환은 번들러에 맡긴다. 이미 CJS 위주인 레거시 코드는 node10을 당장 버릴 이유가 없으나, `exports` 전용 의존성이 늘어날수록 TS2307이 누적되므로 의존성 중 `exports`만 있는 패키지가 생기는 시점이 이전 시점이다. 이전은 `module`과 `moduleResolution`을 함께 바꾸고, `tsc --noEmit`이 내는 오류를 위 표의 네 가지 유형으로 분류해 하나씩 줄이는 순서로 진행하면 된다.

## 참고

- TypeScript 공식 문서, Modules - Theory (module/moduleResolution 개념): https://www.typescriptlang.org/docs/handbook/modules/theory.html
- TypeScript 공식 문서, Modules - Reference (해석 전략, exports·imports·conditions): https://www.typescriptlang.org/docs/handbook/modules/reference.html
- TypeScript 공식 문서, Modules - Choosing Compiler Options: https://www.typescriptlang.org/docs/handbook/modules/guides/choosing-compiler-options.html
- TypeScript TSConfig Reference, moduleResolution·customConditions: https://www.typescriptlang.org/tsconfig/#moduleResolution
- TypeScript 5.0 릴리스 노트 (bundler, customConditions): https://devblogs.microsoft.com/typescript/announcing-typescript-5-0/
- Node.js 공식 문서, Packages (exports, imports, conditional exports): https://nodejs.org/api/packages.html
- Node.js 공식 문서, ECMAScript modules (확장자 규칙, 판정 방식): https://nodejs.org/api/esm.html
- Are the Types Wrong? (attw): https://arethetypeswrong.github.io/
