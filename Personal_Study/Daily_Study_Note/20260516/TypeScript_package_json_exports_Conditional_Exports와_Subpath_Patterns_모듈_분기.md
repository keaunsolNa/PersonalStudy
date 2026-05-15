Notion 원본: https://www.notion.so/3615a06fd6d3816da4c9c1017f184647

# TypeScript package.json "exports" Conditional Exports와 Subpath Patterns — 모듈 entrypoint 분기

> 2026-05-16 신규 주제 · 확장 대상: TypeScript 모듈 시스템 / NodeNext

## 학습 목표

- Node.js `package.json` "exports" 필드의 동작 규칙(우선순위, fallback, conditional resolution) 을 이해한다
- `import` / `require` / `types` / `node` / `browser` / `default` condition 의 해석 순서와 dual-package 위험을 식별한다
- subpath patterns 와 subpath exports 의 차이, 와일드카드 매칭 규칙을 코드로 검증한다
- TypeScript `moduleResolution: NodeNext` 환경에서 라이브러리 작성자가 ESM/CJS dual 빌드를 안전하게 노출하는 방식을 설계한다

## 1. exports 필드 도입 배경 — main 필드 한계

Node.js 12.7 이전 모듈 해석은 `package.json` "main" 필드 + 디렉터리 인덱스 휴리스틱(`index.js`) + 자유로운 deep import(`import x from 'pkg/dist/internal/foo'`) 로 동작했다. 이 구조의 문제는 다음과 같다.

- 패키지 내부 구현 경로(`dist/internal/...`) 가 사실상 *public API* 처럼 노출돼 깨면 사용자 빌드가 깨진다
- ESM 과 CJS 양쪽 빌드를 같은 패키지에서 제공하려면 환경에 따라 다른 진입점을 선택할 수단이 없다
- 브라우저와 Node.js 가 같은 패키지를 다르게 import 하려면 `browser` 필드 같은 비표준 확장에 의존해야 한다
- `types` 필드는 TypeScript 전용으로, runtime 해석과 분리돼 동기화가 깨진다

"exports" 필드는 위 문제를 *통합 해결*한다. exports 필드가 존재하면 Node.js 는 main 필드 + 인덱스 휴리스틱을 *완전히 무시*하고 exports 규칙만 적용한다. 그래서 exports 필드를 도입하는 순간 외부 사용자가 deep import 하던 경로가 끊긴다. 이게 *encapsulation* 효과다.

## 2. exports 필드의 형태와 우선순위

가장 단순한 형태는 단일 string:

```json
{ "exports": "./dist/index.js" }
```

이건 패키지 root import (`import x from 'pkg'`) 만 허용하고 deep import 를 모두 거부한다.

조건부 객체 형태:

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs",
      "default": "./dist/index.js"
    }
  }
}
```

키 순서가 *우선순위*다. Node 해석기는 위에서부터 차례로 매칭을 시도해 첫 번째 매칭에서 멈춘다. 그래서 `types` 를 가장 위에 두는 게 관례다 (TS 가 먼저 보게).

| 조건 키 | 매칭 조건 |
|---|---|
| `node` | Node.js 런타임 |
| `node-addons` | C++ addon 지원하는 Node |
| `browser` | 번들러가 browser 환경 타깃 |
| `import` | ESM `import` 구문으로 import |
| `require` | CJS `require` 호출로 import |
| `types` | TypeScript 가 타입 해석 |
| `default` | 위 모두 매칭 안 됐을 때 fallback |
| `development` / `production` | 일부 번들러가 NODE_ENV 기반 분기 |

키 순서가 매우 중요하다. 다음은 *잘못된* 예시다.

```json
{
  "exports": {
    ".": {
      "import": "./dist/index.js",
      "types": "./dist/index.d.ts"
    }
  }
}
```

TypeScript 가 패키지를 해석할 때 `types` 가 `import` 뒤에 있으면 `import` 키의 `.js` 파일을 먼저 매칭한 뒤 거기 옆에 `.d.ts` 가 있는지 찾는 *문법적* 처리가 일어나는데, `nodenext` 의 엄밀한 해석에서는 `types` 가 먼저 와야 한다. TS 5.0 의 `--verbatimModuleSyntax` 와 결합되면 이 순서 오류가 컴파일 에러로 드러난다.

## 3. Dual-Package Hazard 와 "exports" 의 역할

같은 패키지를 ESM 과 CJS 양쪽으로 노출하면 *서로 다른 인스턴스*가 생긴다.

```ts
// ESM에서
import { Singleton } from 'pkg';
console.log(Singleton.count); // ESM 인스턴스
```

```js
// CJS에서
const { Singleton } = require('pkg');
console.log(Singleton.count); // CJS 인스턴스 (다른 객체)
```

만약 패키지가 모듈 내부에 *전역 상태*(싱글톤 카운터, 등록된 핸들러 맵 등) 를 유지한다면 ESM 과 CJS 에서 *다른 상태* 가 만들어진다. 이게 dual-package hazard 다.

회피책 3가지:

1. **CJS-only 또는 ESM-only 로 결정**. exports 필드에 한쪽 조건만 노출하고 다른 쪽은 throw error string 으로.
2. **상태를 갖는 코드는 별도 inner 패키지에 두고 CJS 로 작성**. ESM wrapper 는 그걸 `require` 로 가져와 re-export. 이러면 두 진입점이 같은 inner 모듈 인스턴스를 공유한다.
3. **Pure facade 만 dual 화**. 상태가 없는 함수만 dual export, 상태 있는 코드는 별도 subpath.

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    },
    "./state": {
      "types": "./dist/state.d.ts",
      "require": "./dist/state.cjs"
    }
  }
}
```

`./state` subpath 는 CJS 만 노출. ESM 사용자도 `import { x } from 'pkg/state'` 로 가져오면 Node 가 ESM→CJS interop 으로 default import 처럼 처리한다.

## 4. Subpath Exports — 부분 경로 명시

```json
{
  "exports": {
    ".": "./dist/index.js",
    "./parser": "./dist/parser.js",
    "./internal": null
  }
}
```

- `./parser` — `import x from 'pkg/parser'` 허용
- `./internal` — `null` 로 명시적 거부. deep import 시 `ERR_PACKAGE_PATH_NOT_EXPORTED`

루트 `.` 만 정의하고 subpath 를 정의하지 않으면 deep import 가 *모두* 차단된다. 이게 encapsulation 의 본질이다. 마이그레이션 시 기존 사용자의 deep import 가 깨진다는 점이 가장 큰 마찰 요인이다.

## 5. Subpath Patterns — 와일드카드 매칭

Node 16+ 부터 `*` 패턴을 지원한다.

```json
{
  "exports": {
    "./features/*": "./dist/features/*.js",
    "./features/*.js": "./dist/features/*.js"
  }
}
```

- `import x from 'pkg/features/auth'` → `./dist/features/auth.js`
- `import x from 'pkg/features/login/form'` → `./dist/features/login/form.js`

`*` 는 *single wildcard* 가 아니라 *substring match* 로, `/` 도 포함할 수 있다. 즉 임의 깊이 매칭이다. 다만 패턴은 정확히 하나의 `*` 만 가질 수 있다.

TypeScript 는 5.0 부터 패턴 패스를 지원하지만 *exact match* 가 우선이므로 다음은 주의해야 한다.

```json
{
  "exports": {
    "./features/auth": "./dist/features/auth-special.js",
    "./features/*": "./dist/features/*.js"
  }
}
```

`./features/auth` 명시 매칭이 와일드카드보다 우선. 그래서 auth 만 다른 파일을 가리키게 할 수 있다.

## 6. types 키와 typesVersions 의 충돌

TypeScript 5.0 이전 호환을 위해 `typesVersions` 필드를 같이 쓰는 경우가 있다.

```json
{
  "typesVersions": {
    "*": {
      "parser": ["./dist/parser.d.ts"]
    }
  },
  "exports": {
    "./parser": {
      "types": "./dist/parser.d.ts",
      "import": "./dist/parser.js"
    }
  }
}
```

`exports.types` 가 우선하지만, TypeScript 가 NodeNext 모드가 아니면 `typesVersions` 만 보는 경우도 있다. 라이브러리 작성자라면 *둘 다* 일치하게 두는 게 안전하다.

## 7. moduleResolution=NodeNext / Bundler 와 라이브러리 작성

`tsconfig.json` 의 `moduleResolution` 설정에 따라 TS 가 exports 필드를 읽는 방식이 다르다.

| 옵션 | 동작 |
|---|---|
| `node` (legacy) | exports 필드 무시, main/typesVersions 만 사용 |
| `node10` | 동일 (TS 5.0+ 별칭) |
| `node16` / `nodenext` | exports 완전 지원, condition 우선순위 적용 |
| `bundler` | exports 지원, 단 `.js` 확장자 강제 없음 (TS 5.0+) |

검증 도구:

- `arethetypeswrong` CLI — exports 매트릭스를 시뮬레이션해서 누락이나 잘못된 매핑을 찾아줌
- `publint` — package.json 의 일반 규칙 위반 검사
- `node --conditions=import --print "require.resolve('pkg')"` — Node 가 실제로 어떤 파일을 해석하는지 출력

## 8. 실전 예시 — TypeScript 라이브러리 dual 빌드 템플릿

```json
{
  "name": "@scope/my-lib",
  "version": "1.0.0",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.mjs",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "types": {
        "import": "./dist/index.d.mts",
        "require": "./dist/index.d.cts"
      },
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs",
      "default": "./dist/index.mjs"
    },
    "./parser": {
      "types": {
        "import": "./dist/parser.d.mts",
        "require": "./dist/parser.d.cts"
      },
      "import": "./dist/parser.mjs",
      "require": "./dist/parser.cjs"
    },
    "./package.json": "./package.json"
  },
  "files": ["dist", "package.json", "README.md"]
}
```

핵심 포인트:

- `types` 가 객체 형태로 *import/require* 분기. 이는 TS 4.7+ 에서 지원되며, ESM 측은 `.d.mts`, CJS 측은 `.d.cts` 로 *동일 모듈에서 다른 declaration 파일* 을 가질 수 있게 한다.
- `default` 키를 항상 두어 알 수 없는 환경에서도 fallback 동작.
- `./package.json` 을 명시적으로 export 해야 일부 번들러나 ESM 환경에서 package.json 자체를 import 할 수 있다.
- `files` 필드로 publish 대상을 dist 로 제한.

tsup 예시:

```ts
import { defineConfig } from 'tsup';
export default defineConfig({
  entry: ['src/index.ts', 'src/parser.ts'],
  format: ['esm', 'cjs'],
  dts: true,
  outExtension: ({ format }) => ({ js: format === 'esm' ? '.mjs' : '.cjs' }),
  clean: true,
});
```

## 9. 흔한 함정과 진단 패턴

| 증상 | 원인 | 해결 |
|---|---|---|
| `Cannot find module 'pkg/x'` | exports 에 subpath 가 없음 | exports 에 `./x` 추가 또는 typesVersions 보완 |
| ESM import 가 `default` 가 빈 객체로 나옴 | CJS 빌드만 노출돼 named export 정적 분석 실패 | ESM 빌드 추가 또는 `default` import 후 destructure |
| TS 는 통과하는데 Node 런타임에서 ERR_PACKAGE_PATH_NOT_EXPORTED | exports 정의가 TS 와 Node 에 불일치 | `arethetypeswrong` 으로 검증 후 정렬 |
| Monorepo 에서 workspace 패키지가 src 를 직접 import | exports 가 dist 만 노출, src 가 차단됨 | dev 시점에 `./src/*` subpath 추가 또는 빌드 산출물 사용 |
| `--moduleResolution node` 인데 동작 안 함 | legacy resolver 는 exports 무시 | `nodenext` 또는 `bundler` 로 변경 |

실측 사례: 어떤 패키지가 `exports` 에 `types` 키를 마지막에 둬서 TS 4.9 이하에서 정상 동작했지만 5.0 nodenext 에서는 `types` 가 매칭되지 않아 declaration 을 찾지 못한 사례가 있었다. 해결은 `types` 를 항상 *조건 객체의 첫 키* 로 옮기는 것.

## 10. Monorepo 와 internal subpath — workspace 소비 패턴

pnpm/yarn/npm workspace 에서 패키지 간 *소스 직접 import* 를 원하는 경우가 있다. 라이브러리 작성자 입장에서는 dist 만 export 하고 싶지만, 같은 monorepo 내부 패키지는 hot-reload 와 type 추론 속도를 위해 src 를 직접 보고 싶다.

해결책: *조건부 export* 를 활용해 dev 환경에서만 src 를 노출.

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs",
      "development": "./src/index.ts"
    }
  }
}
```

Vite / esbuild / tsup 같은 번들러가 `--conditions=development` 로 동작하면 `development` 키가 우선 매칭된다.

함정: TypeScript 가 `development` condition 을 *항상* 매칭하지는 않는다. `tsconfig.json` 의 `customConditions` 옵션 (TS 5.0+) 으로 명시.

```json
{
  "compilerOptions": {
    "moduleResolution": "bundler",
    "customConditions": ["development"]
  }
}
```

internal 의 변경은 minor 가 아닌 *major* 로 처리하는 정책을 CHANGELOG 에 명시하는 게 일반적.

## 참고

- Node.js Docs — Modules: Packages (https://nodejs.org/api/packages.html)
- TypeScript Docs — Modules Reference / Node16 Resolution (https://www.typescriptlang.org/docs/handbook/modules/reference.html)
- arethetypeswrong CLI (https://github.com/arethetypeswrong/arethetypeswrong.github.io)
- publint (https://publint.dev/)
- TC39 — ECMAScript Modules, Node.js ESM Resolution Algorithm (NODE_RESOLVE)
