Notion 원본: https://app.notion.com/p/3d85a06fd6d381cabc0cc9d6ca9c9b52?pvs=204

# TypeScript 패키지 배포 타입 검증과 Dual Package Hazard 및 attw publint

> 2026-09-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- npm 배포 패키지의 타입 해석 실패를 `exports` 조건과 `moduleResolution` 관점으로 진단한다
- Dual Package Hazard 와 `.d.mts`/`.d.cts` 마스커레이딩 문제를 구분해 대응한다
- `@arethetypeswrong/cli` 와 `publint` 를 CI 게이트로 묶어 배포 회귀를 차단한다
- 듀얼 배포 유지와 ESM-only 전환의 비용을 비교해 배포 전략을 결정한다

## 1. 문제 정의 — 타입은 왜 소비자 쪽에서만 깨지는가

저장소 안에서는 `tsc --noEmit` 이 초록불인데 배포 후 소비자 프로젝트에서 `Cannot find module 'my-lib' or its corresponding type declarations` 가 뜬다. 원인은 경로가 다르기 때문이다. 나는 `src/index.ts` 를 상대경로로 직접 참조하지만, 소비자는 `node_modules/my-lib/package.json` 의 필드를 거쳐 간접적으로 파일을 찾는다. Maven 으로 치면 로컬 모듈 간 빌드는 통과하는데 배포된 JAR 의 레이아웃이 잘못돼 다운스트림에서 `NoClassDefFoundError` 가 나는 상황과 같다.

```ts
import lib from 'my-lib';        // ts2307: 모듈 또는 해당 형식 선언을 찾을 수 없음
import { helper } from 'my-lib'; // 타입은 통과, 런타임에 helper is not a function
const l = require('my-lib');     // 타입상 l.helper 인데 실제로는 l.default.helper
```

증상은 세 갈래다. `exports` 를 선언하고도 그 안에 `types` 조건을 빠뜨려 선언 파일을 못 찾는 경우, 소비자의 `moduleResolution` 이 `bundler` 냐 `nodenext` 냐에 따라 결과가 갈리는 경우, 그리고 CJS 소비자에서 default import 의 실제 형태가 타입과 어긋나는 경우다. 배포 후 이슈로 알아채는 방식은 비용이 0 처럼 보이지만 패치 릴리스로 되돌아온다. 반대로 모든 해석 모드에 대해 소비자 픽스처 프로젝트를 손으로 만들면 정확하지만 유지 비용이 크다. 뒤에 볼 attw 가 그 중간 지점이다.

## 2. `exports` 필드와 conditional exports

Node.js 의 conditional exports 는 객체 키를 **선언 순서대로 위에서 아래로** 매칭해 첫 번째로 성립하는 분기를 고른다. JSON 키 순서가 사양상 의미를 갖는 드문 사례다. `"default"` 를 위에 두면 아래의 `"import"`/`"require"` 는 영원히 도달하지 못한다. TypeScript 는 여기에 `"types"` 조건을 추가로 인식하는데, ESM 분기와 CJS 분기의 선언 파일이 달라야 하므로 `types` 는 각 분기 **안쪽**에, 그리고 그 분기에서 **가장 먼저** 와야 한다.

```json
{
  "name": "my-lib",
  "version": "1.0.0",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.cts",
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.ts", "default": "./dist/index.js" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    },
    "./utils": {
      "import": { "types": "./dist/utils.d.ts", "default": "./dist/utils.js" },
      "require": { "types": "./dist/utils.d.cts", "default": "./dist/utils.cjs" }
    },
    "./package.json": "./package.json"
  },
  "files": ["dist"],
  "sideEffects": false
}
```

최상위 `main`/`module`/`types` 는 지우지 말고 남기는 편이 낫다. `exports` 를 이해하지 못하는 `node10` 해석기를 위한 폴백이기 때문이며, 이때 최상위 `types` 는 `main` 이 가리키는 파일의 포맷과 맞춰야 한다(위 예시는 `main` 이 CJS 라 `.d.cts`). `typesVersions` 는 `exports` 이전에 서브패스 타입을 매핑하던 레거시 수단으로, 지금은 `node10` 폴백용 의미만 남았다. 두 곳에 정보가 중복되어 어긋나기 쉬우니 `node10` 소비자를 포기할 수 있다면 빼는 쪽이 유지보수에 유리하다.

## 3. Dual Package Hazard — 클래스로더 분리와 같은 문제

한 패키지를 ESM 과 CJS 두 벌로 배포하면 한 프로세스 안에서 같은 라이브러리가 **두 개의 독립 인스턴스**로 로드될 수 있다. 앱이 `import 'my-lib'` 로 ESM 사본을 쓰고 의존성 하나가 `require('my-lib')` 로 CJS 사본을 쓰면 Node 는 두 모듈 그래프를 별개로 관리한다. 같은 FQCN 의 클래스가 서로 다른 `ClassLoader` 로 로드되어 `ClassCastException` 이 나는 WAR/컨테이너 충돌과 구조가 같다. 다만 JS 에서는 예외 대신 조용한 오작동으로 나타난다.

```ts
// app.mjs — ESM 인스턴스
import { MyError, registry } from 'my-lib';
import { doWork } from 'some-cjs-dep'; // 내부에서 require('my-lib')

try {
  doWork();                            // CJS 인스턴스의 MyError 를 던진다
} catch (e) {
  console.log(e instanceof MyError);   // false — 생성자 identity 가 다르다
}
console.log(registry.size);            // CJS 쪽 등록분이 보이지 않는다
```

피해는 `instanceof` 및 브랜드 체크 실패, 모듈 최상위 싱글턴 상태(설정 객체, 플러그인 레지스트리, 커넥션 풀)의 분기, 모듈 로컬 `Symbol()` 키 불일치 세 가지로 나타난다. 마지막 항목은 realm 단위로 공유되는 `Symbol.for()` 로 우회할 수 있다. 근본 완화책은 상태를 가진 코드를 CJS 한 벌로만 두고 ESM 진입점은 그것을 재수출하는 상태 없는 래퍼로 만드는 것이다. 대신 ESM 소비자는 트리셰이킹 이점을 상당 부분 잃고 named export 를 래퍼에 손으로 나열해야 한다. 상태가 전혀 없는 순수 함수 라이브러리면 독립 빌드가 안전하고, 싱글턴이나 에러 클래스를 공개 API 로 노출한다면 래퍼 패턴이나 ESM-only 를 고려해야 한다.

## 4. Masquerading — 확장자가 선언하는 포맷과 실제 포맷의 불일치

TypeScript 는 선언 파일의 **확장자**로 그것이 기술하는 JS 의 모듈 포맷을 판단한다. `.d.mts` 는 ESM, `.d.cts` 는 CJS, 그냥 `.d.ts` 는 가장 가까운 `package.json` 의 `"type"` 을 따른다. `"type": "module"` 이면 ESM, 없거나 `"commonjs"` 면 CJS 로 해석되며 이는 `.js`/`.mjs`/`.cjs` 규칙과 정확히 대칭이다. 문제는 빌드 도구가 JS 는 `.cjs` 로 뽑으면서 선언은 `.d.ts` 하나만 내보내는 흔한 실수에서 생긴다.

```bash
# 위험 — 포맷이 어긋난다 ("type": "module" 기준)
dist/index.js      # ESM
dist/index.cjs     # CJS
dist/index.d.ts    # ESM 으로 해석됨 → index.cjs 를 설명할 수 없다

# 안전 — 확장자 쌍을 맞춘다
dist/index.js   dist/index.d.ts     # ESM
dist/index.cjs  dist/index.d.cts    # CJS
```

이 상태에서 타입 체커는 `import x from 'my-lib'` 의 ESM default import 를 허용하지만, 런타임에는 `module.exports` 전체가 들어와 `x.default` 여야 맞다. attw 가 `FalseESM`/`FalseCJS` 로 잡는 것이 정확히 이 불일치다. CJS 를 `.d.cts` 로 기술하면 `export = ` 를 쓴 패키지인지도 타입에 반영되며, 여기에 `esModuleInterop` 과 `verbatimModuleSyntax` 가 결과를 한 번 더 바꾼다. 손으로 `.d.cts` 를 유지하면 정확하지만 파일과 JSDoc 이 두 배가 되므로 `tsc` 를 두 번 돌리거나 tsup 에 맡기는 편이 현실적이다. `dist/` 안에 `{"type":"commonjs"}` 만 담은 미니 `package.json` 으로 우회하는 기법도 동작은 하지만, 중첩 `package.json` 이 패키지 경계처럼 보여 도구를 혼란스럽게 하므로 명시적 확장자가 더 예측 가능하다.

## 5. `moduleResolution` 별 해석 차이

소비자의 `tsconfig.json` 설정 하나로 같은 패키지가 전혀 다르게 읽힌다.

| 모드 | `exports` 인식 | `types` 조건 | 확장자 규칙 | 폴백 | 주 용도 |
|---|---|---|---|---|---|
| `classic` | 아니오 | 아니오 | `.ts`/`.d.ts` 탐색 | 상위 디렉터리 순회 | 사실상 폐기 |
| `node10` (구 `node`) | **아니오** | 아니오 | 확장자 생략 허용 | `main`/`types`/`index.d.ts` | 레거시 CJS, 구형 번들러 |
| `node16` | 예 | 예 | `.mjs`/`.cjs` 엄격 구분 | `exports` 없을 때만 node10 식 | Node 16 고정 타깃 |
| `nodenext` | 예 | 예 | `.mjs`/`.cjs` 엄격 구분 | `exports` 없을 때만 node10 식 | 최신 Node, 사양 추종 |
| `bundler` | 예 | 예 | 확장자 생략 허용 | `main`/`module` 참조 | Vite/webpack/Rollup 앱 |

```json
{
  "compilerOptions": {
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "target": "es2022",
    "strict": true,
    "declaration": true,
    "declarationMap": true,
    "verbatimModuleSyntax": true
  }
}
```

핵심 차이는 두 축이다. `exports` 를 보느냐(`node10`/`classic` 은 안 봄), 그리고 ESM/CJS 포맷을 엄격히 구분하느냐다. `bundler` 는 `exports` 는 읽지만 포맷 구분이 느슨해 혼용을 허용하므로, 앱 개발자가 `bundler` 로 잘 쓰던 패키지를 누군가 `nodenext` 로 가져가면 처음 깨진다. 라이브러리를 `nodenext` 로 컴파일하면 가장 엄격한 소비자와 같은 규칙을 스스로에게 적용해 문제를 미리 발견하지만, 모든 상대 import 에 `.js` 확장자를 붙여야 하는 부담이 따른다. 라이브러리는 `nodenext`, 앱은 `bundler` 라는 분업이 합리적이다.

## 6. `@arethetypeswrong/cli` — 소비자 시점 해석 시뮬레이터

attw 는 타르볼이나 디렉터리를 입력받아 **모든 해석 모드에서 가상의 소비자가 이 패키지를 import 하면 무슨 일이 일어나는지** 시뮬레이션한다. 단순 린트가 아니라 실제 해석 알고리즘을 재현하는 것이 특징이며, 타입 체크의 정확성이 아니라 "타입을 찾을 수 있는가, 찾은 타입이 런타임과 일치하는가"를 본다. 출력은 서브패스(행) × 해석 모드(열) 매트릭스라 어떤 진입점이 어떤 모드에서만 깨지는지 한눈에 들어온다.

```bash
npx --yes @arethetypeswrong/cli --pack .            # 설치 없이 현재 패키지 검사

npm pack --pack-destination /tmp                    # 실제 타르볼로 검사(배포본에 가장 근접)
npx --yes @arethetypeswrong/cli /tmp/my-lib-1.0.0.tgz

npx --yes @arethetypeswrong/cli --from-npm zod      # 이미 배포된 패키지 점검

npx --yes @arethetypeswrong/cli --pack . \
  --ignore-rules cjs-resolves-to-esm --format table # CI 용 엄격도 조절
```

주요 문제 코드는 이렇다. `NoResolution` 은 해당 모드에서 모듈 자체가 해석되지 않는 경우, `UntypedResolution` 은 JS 는 찾았으나 선언이 없는 경우다. `FalseESM` 은 타입이 ESM 이라 말하는데 실제 JS 가 CJS 인 마스커레이딩이고 `FalseCJS` 는 그 반대다. `CJSResolvesToESM` 은 `require` 조건이 ESM 파일로 이어져 런타임에 `ERR_REQUIRE_ESM` 이 나는 상황, `NamedExports` 는 CJS 소비자의 named import 를 Node 의 cjs-module-lexer 가 감지하지 못하는 경우, `FallbackCondition` 은 `node10` 폴백 경로로만 해석되는 취약한 상태, `MissingExportEquals` 는 실제로는 `module.exports = fn` 인데 선언이 `export default` 인 불일치다. `node10` 지원을 이미 포기했다면 관련 경고는 소음이므로 `--ignore-rules` 로 끄는 편이 낫지만, 무엇을 껐는지 팀이 합의하고 기록해야 나중에 무의식적으로 커버리지를 잃지 않는다.

## 7. `publint` — 패키지 배선 자체의 유효성

publint 는 attw 와 관심사가 다르다. attw 가 "소비자가 타입을 제대로 얻는가"를 본다면, publint 는 "이 `package.json` 과 타르볼이 npm 패키지로서 올바른가"를 본다. `exports` 에 선언한 파일이 실제 타르볼에 있는지(`files` 누락), `main`/`module` 이 없는 경로를 가리키는지, `"type"` 과 확장자가 어긋나는지, 도달 불가능한 `exports` 분기가 있는지, CJS 파일에 ESM 문법이 섞였는지 같은 것들이다.

```bash
npx --yes publint                     # 현재 디렉터리
npm pack --pack-destination /tmp
npx --yes publint /tmp/my-lib-1.0.0.tgz
npx --yes publint --strict            # 경고까지 실패 처리
```

| 관점 | publint | attw |
|---|---|---|
| 검사 대상 | `package.json` 필드와 타르볼 내용물 | 소비자 시점의 타입 해석 결과 |
| 파일 존재 여부 | 검사함 (`files` 누락 탐지) | 해석 실패로 간접 노출 |
| `exports` 조건 순서 | 도달 불가 분기 경고 | 잘못된 분기 선택 결과로 노출 |
| `.d.ts` / JS 포맷 일치 | 확장자·`type` 수준 경고 | `FalseESM`/`FalseCJS` 로 정밀 판정 |
| `moduleResolution` 별 차이 | 다루지 않음 | 모드별 매트릭스 제공 |
| CJS named export 감지 | 다루지 않음 | `NamedExports` 로 판정 |

둘을 함께 써야 하는 이유가 여기서 드러난다. publint 만 쓰면 파일은 다 있는데 타입 포맷이 어긋난 경우를 놓치고, attw 만 쓰면 `files` 에 `dist` 를 빠뜨려 타르볼이 비었을 때 원인 진단이 느려진다. 실행 순서는 publint 를 먼저 두는 편이 좋다. 배선이 깨진 상태에서 attw 를 돌리면 모든 칸이 `NoResolution` 으로 도배돼 정보량이 없기 때문이다.

## 8. 실전 배포 파이프라인

tsup 을 쓰면 esbuild 로 ESM/CJS 두 포맷을 뽑고 선언 파일도 확장자에 맞춰 생성한다. tsc 만으로 하려면 tsconfig 두 벌에 `module` 을 다르게 주고 산출물 확장자를 후처리하는데, 빌드 도구 의존은 줄지만 스크립트가 길어진다 — Gradle 에서 `classifier` 를 달리해 같은 소스로 두 JAR 를 뽑는 것과 발상이 같다.

```ts
// tsup.config.ts
import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts', 'src/utils.ts'],
  format: ['esm', 'cjs'],
  dts: true,
  sourcemap: true,
  clean: true,
  target: 'node18',
  outExtension: ({ format }) => ({ js: format === 'esm' ? '.js' : '.cjs' })
});
```

```json
{
  "scripts": {
    "build": "tsup",
    "check:exports": "attw --pack . --profile node16",
    "check:publish": "publint --strict",
    "prepack": "npm run build",
    "prepublishOnly": "npm run check:publish && npm run check:exports",
    "verify:pack": "npm pack --dry-run"
  },
  "devDependencies": {
    "@arethetypeswrong/cli": "^0.18.0",
    "publint": "^0.3.0",
    "tsup": "^8.3.0",
    "typescript": "^5.7.0"
  }
}
```

`npm pack --dry-run` 은 실제로 타르볼에 담기는 파일 목록과 크기를 보여 준다. `files` 를 좁게 잡았을 때 테스트 픽스처가 새어 나가는지, 반대로 `dist/` 가 통째로 빠졌는지를 배포 전에 확인하는 가장 싼 수단이다. `sideEffects: false` 는 번들러에게 미사용 모듈 제거가 안전하다고 알리는 신호인데, 폴리필이나 CSS import 처럼 로드만으로 효과가 있는 파일이 있다면 배열로 예외를 지정해야 한다. 잘못 켜면 런타임에 조용히 기능이 사라진다.

```yaml
# .github/workflows/release-check.yml
name: package-check
on:
  pull_request:
  push:
    branches: [master]

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
      - run: npm ci
      - run: npm run build
      - run: npx --yes publint --strict
      - run: npx --yes @arethetypeswrong/cli --pack . --format table
      - run: npm pack --dry-run
```

게이트를 PR 마다 돌리면 빌드 시간이 늘지만 `exports` 를 건드린 커밋에서 즉시 잡히고, 릴리스 직전에만 돌리면 빠른 대신 여러 변경이 쌓여 원인 추적이 어렵다. 빌드가 수십 초 수준이면 PR 게이트를 권한다.

## 9. ESM-only 로 가는 선택지와 트레이드오프

듀얼 배포의 유지 비용은 누적된다. 산출물이 두 배, 선언 파일이 두 배, 검증할 해석 조합이 두 배이고 Dual Package Hazard 라는 구조적 위험까지 떠안는다. ESM-only 로 전환하면 `exports` 가 단순해지고 `.d.cts` 가 사라지며 attw 매트릭스의 절반이 없어진다.

```json
{
  "name": "my-lib",
  "version": "2.0.0",
  "type": "module",
  "exports": {
    ".": { "types": "./dist/index.d.ts", "default": "./dist/index.js" },
    "./package.json": "./package.json"
  },
  "engines": { "node": ">=20" },
  "files": ["dist", "src"],
  "sideEffects": false
}
```

대가는 소비자가 치른다. CJS 프로젝트는 `require()` 를 쓸 수 없고 동적 `await import()` 로 우회해야 하는데 이는 동기 초기화 코드에 전파되는 변경이다. 최근 Node 릴리스에서 CJS 가 동기 ESM 그래프를 `require()` 로 로드하는 기능이 들어가 장벽은 낮아지는 추세지만 Jest 같은 도구 체인에는 여전히 마찰이 있다. 소비자가 주로 Node 20+ 앱이나 번들러 기반 프런트엔드라면 ESM-only 가 합리적이고, 레거시 CJS 서비스나 서드파티 플러그인 생태계를 지탱한다면 메이저 버전을 끊기 전까지 듀얼을 유지하는 편이 안전하다.

빌드 속도 측면의 곁가지로 `isolatedDeclarations` 가 있다. 공개 API 에 명시적 타입 주석을 강제해 선언 파일 생성이 파일 단위로 독립 가능해지고, 전체 프로그램 분석 없이 `.d.ts` 를 뽑을 수 있어 병렬화·캐싱과 비-tsc 도구 사용이 열린다. 대가는 내보내는 함수의 반환 타입을 전부 적어야 하는 작성 부담이다. 여기에 `declarationMap: true` 를 켜면 `.d.ts.map` 이 함께 나와 소비자가 IDE 에서 정의로 이동할 때 원본 `.ts` 로 점프한다 — Maven 의 `sources` classifier JAR 을 붙여 주는 것과 같은 개선이고, 이때는 위 예시처럼 `files` 에 `src` 도 포함해야 실제로 동작한다.

## 참고

- Node.js 공식 문서, Modules: Packages (exports, conditional exports, Dual Package Hazard) — https://nodejs.org/api/packages.html
- Node.js 공식 문서, Modules: ECMAScript modules — https://nodejs.org/api/esm.html
- TypeScript Handbook, Modules Reference (모듈 해석과 선언 파일 확장자 규칙) — https://www.typescriptlang.org/docs/handbook/modules/reference.html
- TypeScript Handbook, Modules Theory / Choosing Compiler Options — https://www.typescriptlang.org/docs/handbook/modules/theory.html
- `@arethetypeswrong/cli` 저장소와 문제 코드 정의 — https://github.com/arethetypeswrong/arethetypeswrong.github.io
- publint 공식 사이트와 규칙 목록 — https://publint.dev
- npm CLI 문서, package.json 필드 (`files`, `main`, `exports`) — https://docs.npmjs.com/cli/v10/configuring-npm/package-json
