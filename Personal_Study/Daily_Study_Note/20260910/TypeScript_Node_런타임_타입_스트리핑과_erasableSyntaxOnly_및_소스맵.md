Notion 원본: https://www.notion.so/3d75a06fd6d38104bc8df326d8f89128

# TypeScript Node 런타임 타입 스트리핑과 erasableSyntaxOnly 및 소스맵

> 2026-09-10 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Node 타입 스트리핑의 버전별 이력과 안정성 등급으로 런타임 실행 가능 여부를 판단한다
- 공백 치환 방식이 소스맵을 불필요하게 만드는 원리를 변환 전후 코드로 추적한다
- `erasableSyntaxOnly`·`verbatimModuleSyntax`·`rewriteRelativeImportExtensions` 를 묶어 tsconfig 를 구성한다
- enum·parameter properties·namespace 를 지울 수 있는 구문으로 치환해 코드베이스를 이전한다

## 1. TS 실행 방식의 계보

TypeScript 실행은 언제나 "먼저 JavaScript 로 만든 뒤 실행한다"였다. 차이는 변환이 **언제, 누구에 의해** 일어나느냐뿐이다.

1세대 `tsc` 사전 컴파일은 타입 체크와 코드 생성을 한 프로세스에 묶어 정확했지만 실행 파일과 작성 파일을 갈라놓았다. 스택 트레이스는 `dist` 를 가리키고 디버거를 원본에 붙이려면 소스맵이 필요하다. 2세대 `ts-node`·`tsx` 는 모듈 로더에 개입해 `.ts` 를 읽는 순간 변환한다. 산출물이 사라지는 대신 로더 부팅 비용이 생기고 ESM 로더 API 변경을 계속 따라가야 한다. 3세대 번들러는 변환을 네이티브로 옮겨 속도를 얻고 타입 체크를 버렸다. `isolatedModules` 제약이 이때 표준이 된다.

4세대가 런타임 내장 스트리핑이다. Node 가 `node app.ts` 를 그대로 받아들이고 도구 설치도 로더 등록도 없다. 대가는 명확하다. `tsconfig.json` 을 읽지 않으며 다운레벨 변환도 타입 체크도 하지 않는다.

| 세대 | 변환 시점 | 타입 체크 | 남은 비용 |
| --- | --- | --- | --- |
| `tsc` | 빌드 | 포함 | 빌드 지연, 소스맵 필수 |
| `ts-node`/`tsx` | 로드 시 | 선택 | 로더 부팅 비용, 로더 API 추종 |
| 번들러 | 빌드 | 없음 | 파일 단위 제약, 설정 표면 증가 |
| Node 스트리핑 | 로드 시 | 없음 | 지울 수 없는 구문 금지, tsconfig 무시 |

## 2. 타입 스트리핑의 원리

Node 의 구현체 [amaro](https://github.com/nodejs/amaro) 는 `@swc/wasm-typescript`(SWC TS 파서의 WebAssembly 포트)를 감싼다. 핵심은 `strip-only` 모드다. 파싱해 타입 노드를 찾은 뒤 **그 자리를 같은 길이의 공백으로 덮어쓴다.** 삭제 후 재출력하지 않는다.

```js
const amaro = require('amaro');
const { code } = amaro.transformSync("const foo: string = 'bar';", { mode: 'strip-only' });
console.log(code); // "const foo         = 'bar';"
```

`: string` 8글자가 공백 8칸이 됐다. 전체 길이가 그대로이므로 모든 토큰의 줄·열 번호가 원본과 **바이트 단위로 동일**하다. 여러 줄 예제로 보면(공백을 `·` 로 표기):

```ts
// 원본 user.ts
export interface User { id: number }
export function greet(user: User, p: string = 'Hi'): string {
  return `${p}, ${user.name}`;
}
```

```js
// 런타임이 실제로 평가하는 코드
·····································
export function greet(user······, p········ = 'Hi')········ {
  return `${p}, ${user.name}`;
}
```

`interface` 줄은 통째로 공백이 되지만 **줄 자체는 사라지지 않는다.** 그래서 `greet` 이 2행이라는 사실이 변하지 않고, Node 문서도 소스맵이 불필요하며 생성하지 않는다고 못 박는다.

trade-off는 정직하다. 소스맵 비용이 0 이지만 **길이가 달라지는 변환은 원천적으로 불가능하다.** Bloomberg 의 `ts-blank-space` 도 같은 전략과 제약을 공유한다.

## 3. 지울 수 없는 구문

Node 는 인라인 타입만 제거하므로 TS 구문을 **새 JS 구문으로 바꿔야 하는** 기능은 전부 에러다. 런타임 에러 코드는 `ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX` 다.

```ts
// (1) enum — 객체와 역매핑 테이블을 생성해야 한다
enum Direction { Up, Down }
// 필요한 JS: var Direction; (function(D){ D[D["Up"]=0]="Up"; })(Direction ||= {});

// (2) 값이 있는 namespace — 클로저와 프로퍼티 대입이 필요하다
namespace Config { export let port = 3000; }

// (3) parameter properties — 생성자 본문에 this 대입문을 삽입해야 한다
class Point { constructor(public x: number, public y: number) {} }

// (4) import = / export = — CommonJS require 호출로 바꿔야 한다
import foo = require('foo');
export = Point;
```

반면 **값을 담지 않은 namespace 는 통과한다.** `namespace TypeOnly { export type A = string }` 은 지울 대상이 타입뿐이라 정상 동작한다.

데코레이터는 별개 사유로 막힌다. TC39 Stage 3 제안이라 아직 JS 문법이 아니고 Node 는 폴리필을 주지 않아 파서 에러가 난다. `.tsx` 도 지원되지 않는다.

이 구문들을 런타임에서 쓰려면 변환(transform) 모드가 필요하다. `--experimental-transform-types` 가 v22.7.0 에 추가되어 enum·namespace 를 실제로 변환했지만 **v26.0.0 에서 제거되었다**(nodejs/node#61803). v26 이상은 코어 플래그 대신 userland amaro 로더를 쓴다.

```bash
# 변환 모드는 길이가 바뀌므로 소스맵이 반드시 필요하다
node --enable-source-maps --import="amaro/transform" app.ts
```

`--enable-source-maps` 를 빼면 스택 트레이스의 줄·열이 어긋난다. 스트리핑과 변환의 차이가 소스맵 필요 여부로 그대로 드러난다. (`--experimental-sourcemaps` 라는 플래그는 확인되지 않았고 실제 옵션명은 `--enable-source-maps` 다.)

## 4. `erasableSyntaxOnly`

3절의 구문들은 런타임에서야 터진다. 실행 전에 잡으려고 TypeScript 5.8 이 추가한 옵션이 `erasableSyntaxOnly` 다. **런타임 동작을 갖는 TS 전용 구문 대부분을 컴파일 에러로 승격**한다.

```ts
class C {
  constructor(public x: number) {}
  //          ~~~~~~~~~~~~~~~~
  // error! This syntax is not allowed when 'erasableSyntaxOnly' is enabled.
}
```

금지 목록은 Node 가 거부하는 목록과 정확히 겹친다. `enum`, 런타임 코드를 가진 `namespace`/`module`, parameter properties, 비-ECMAScript `import =`/`export =` 대입이다. "내 코드가 공백 치환만으로 실행 가능한가"를 `tsc` 가 대신 증명해 준다.

함께 켜야 하는 짝이 `verbatimModuleSyntax` 다. 스트리핑은 파일 하나만 보므로 `import { Type } from './m.ts'` 에서 `Type` 이 타입인지 값인지 알 수 없고, 값 import 로 남겨 런타임 에러를 낸다. 이 옵션은 import elision 을 끄고 `type` 키워드를 강제한다.

```ts
import type { Type1 } from './module.ts';   // OK
import { fn, type FnParams } from './fn.ts'; // OK
import { fn, FnParams } from './fn.ts';      // 런타임 에러 (FnParams 가 값으로 남는다)
```

`isolatedModules` 는 더 넓은 "파일 단위 변환 가능성" 제약이고 `verbatimModuleSyntax` 는 그중 모듈 구문에 대한 더 엄격한 규칙이다. Node 문서 권장 조합은 TS 5.8 이상 + 다음 설정이다.

```json
{
  "compilerOptions": {
    "noEmit": true,
    "target": "esnext",
    "module": "nodenext",
    "rewriteRelativeImportExtensions": true,
    "erasableSyntaxOnly": true,
    "verbatimModuleSyntax": true
  }
}
```

`noEmit` 은 `.ts` 만 실행할 때 쓰고 `.js` 를 배포할 계획이면 뺀다. `module: nodenext` 는 Node 22 부터 허용된 CJS→ESM `require()` 를 TS 5.8 이 인정하는 유일한 모드라 혼재 환경에서 필수다.

## 5. 모듈 해석의 함정

Node 로 `.ts` 를 직접 실행할 때 가장 많이 걸려 넘어지는 곳은 타입이 아니라 **경로**다.

첫째, **확장자는 생략할 수 없다.** ESM 의 필수 확장자 규칙이 적용되고 하위 호환 때문에 `require()` 에서도 필수다. `.ts` 를 실행하므로 써야 하는 확장자는 `.ts` 다.

```ts
import { load } from './config.ts'; // OK
import { load } from './config';    // ERR_MODULE_NOT_FOUND
```

`tsc` 쪽에서는 `allowImportingTsExtensions` 가 `.ts` import 를 타입 체크만 통과시키고, `rewriteRelativeImportExtensions` 가 출력 시 `.ts` → `.js` 재작성을 맡는다. 이 둘이 "소스에는 `.ts`, 산출물에는 `.js`" 를 성립시킨다.

둘째, **모듈 시스템은 확장자와 가장 가까운 package.json 이 결정한다.** `.ts` 는 `.js` 와 동일 규칙이라 `import`/`export` 를 쓰려면 `"type": "module"` 이 필요하다. `.mts` 는 항상 ESM, `.cts` 는 항상 CJS 이며 Node 는 둘 사이를 변환하지 않는다.

셋째, **`paths` 별칭은 런타임에 동작하지 않는다.** `tsconfig.json` 을 읽지 않으므로 `@app/*` 은 그대로 모듈 해석에 넘어가 실패한다. 대체재는 subpath imports 이며 키가 `#` 로 시작해야 한다.

```json
// package.json
{
  "type": "module",
  "imports": {
    "#config/*": "./src/config/*",
    "#db": "./src/db/index.ts"
  }
}
```

이제 `import { pool } from '#db'` 가 런타임과 `tsc` 양쪽에서 동일하게 해석된다. 모노레포라면 `exports` 에 커스텀 조건(`"typescript": "./src/index.ts"`)을 두고 `node --conditions=typescript` 로 내부 패키지를 재빌드 없이 쓴다.

마지막 함정. **`node_modules` 아래의 TS 파일은 Node 가 처리하지 않는다.** 패키지 저자의 TS 소스 배포를 막으려는 의도적 결정이다.

## 6. 타입 체크는 여전히 별도

되풀이할 가치가 있다. **Node 는 타입을 확인하지 않는다.** 문법만 유효하면 `const port: number = "8080"` 도 불평 없이 실행된다. 그래서 타입 체크는 별도 게이트로 남겨야 하고, 실행과 체크를 프로세스 단위로 분리하는 구성이 가장 단순하다.

```json
{
  "scripts": {
    "dev": "node --watch src/index.ts",
    "typecheck": "tsc --noEmit",
    "typecheck:watch": "tsc --noEmit --watch --preserveWatchOutput",
    "ci": "npm run typecheck && node --test"
  }
}
```

개발 중에는 `dev` 와 `typecheck:watch` 를 별도 터미널에서 돌린다. 실행은 스트리핑 속도로 즉시 반응하고 타입 오류는 비동기로 따라온다(`--preserveWatchOutput` 은 화면을 지우지 않아 로그를 함께 볼 때 유용하다). 사전 컴파일 시절에는 빌드가 곧 타입 체크였지만 이제 둘은 독립된 관심사다. **명시적 CI 게이트로 세우지 않으면 아무도 타입 체크를 하지 않는다.**

## 7. 도구 비교

| 항목 | Node 스트리핑 | tsx | ts-node | Bun | Deno |
| --- | --- | --- | --- | --- | --- |
| 설치 | 불필요 | devDep | devDep | 별도 런타임 | 별도 런타임 |
| tsconfig 반영 | 없음 | 있음 | 있음 | 부분 | 부분 |
| enum·데코레이터 | 불가 | 가능 | 가능 | 가능 | 가능 |
| `paths` 별칭 | 불가 | 가능 | 가능 | 가능 | import map |
| 타입 체크 | 없음 | 없음 | 기본 켜짐 | 없음 | `deno check` |
| 소스맵 | 불필요 | 자동 | 자동 | 자동 | 자동 |
| 부팅 오버헤드 | 가장 낮음 | 낮음 | 높음 | 매우 낮음 | 낮음 |

선택 기준은 단순하다. **새 코드이고 enum·데코레이터가 없다면 내장 스트리핑**이다. 의존성이 늘지 않는다. **데코레이터(NestJS, TypeORM)나 `paths` 가 이미 깔려 있다면 `tsx`** 를 `node --import=tsx app.ts` 로 얹는다. **타입 체크를 실행 시점에 강제하려면 `ts-node`** 지만 부팅 지연을 감수해야 한다. Bun·Deno 는 런타임을 바꾸는 결정이라 TS 실행 편의만으로 고를 사안이 아니다.

## 8. 배포 전략

스트리핑이 빌드를 없앤다는 말은 **애플리케이션에 한해** 참이다.

**라이브러리는 여전히 빌드해야 한다.** (1) `node_modules` 내부 TS 를 Node 가 거부하므로 TS 소스만 배포하면 소비자가 실행할 수 없다. (2) 타입을 주려면 `tsc` 가 생성한 `.d.ts` 가 필요하다. (3) 구형 Node 지원에는 다운레벨 변환이 필요하고, 번들러의 트리 셰이킹도 정적 분석 가능한 `.js` 를 전제로 한다.

**애플리케이션·스크립트·툴링은 반대다.** 배포 대상이 자신이 관리하는 프로세스뿐이면 산출물 디렉터리를 유지할 이유가 없다. 일회성 마이그레이션 스크립트나 CLI 툴은 `node scripts/migrate.ts` 한 줄로 끝난다. Docker 에서는 계산이 미묘해진다.

```dockerfile
FROM node:24-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
CMD ["node", "src/index.ts"]   # 빌드 스테이지 없음
```

멀티스테이지 빌드와 `tsc` 단계가 사라져 Dockerfile 이 짧아진다. 대가는 둘이다. **런타임 이미지에 소스가 그대로 들어가** 주석과 원본 구조가 노출되고, **매 시작마다 스트리핑 비용을 낸다.** 파일당 비용은 작지만 수천 개면 누적되므로, 콜드 스타트가 지표인 서버리스에서는 사전 컴파일이 여전히 유리하다.

## 9. 마이그레이션 체크리스트

**enum → union + const 객체.** `as const` 와 `typeof`/`keyof` 조합이 런타임 객체와 타입을 동시에 준다.

```ts
// Before
enum Status { Active = 'ACTIVE', Banned = 'BANNED' }

// After — 완전히 지울 수 있는 구문
export const Status = { Active: 'ACTIVE', Banned: 'BANNED' } as const;
export type Status = (typeof Status)[keyof typeof Status]; // 'ACTIVE' | 'BANNED'

function check(s: Status) { /* Status.Active 로 접근하는 코드는 그대로 동작 */ }
```

값 사용처와 타입 사용처 문법이 그대로라 호출부 수정이 거의 없고, 숫자 enum 의 역매핑 함정도 사라진다.

**parameter properties → 명시 필드 대입.**

```ts
// Before
class Service { constructor(private repo: Repo) {} }

// After
class Service {
  readonly #repo: Repo;
  constructor(repo: Repo) { this.#repo = repo; }
}
```

**namespace 제거.** 값이 있는 namespace 는 ES 모듈로 평탄화한다. `namespace Config { export let port = 3000 }` 은 `config.ts` 로 옮겨 최상위 `export` 로 바꾸고 `import * as Config from './config.ts'` 로 참조한다. 타입만 담은 namespace 는 그대로 두어도 된다.

도입 순서는 이렇게 잡는다. (1) TypeScript 5.8 이상으로 올린다. (2) `verbatimModuleSyntax` 를 먼저 켜고 `type` 키워드 누락을 고친다. (3) `erasableSyntaxOnly` 로 남은 금지 구문을 컴파일 에러로 뽑아낸다. (4) enum → parameter properties → namespace 순으로 치환한다. (5) import 확장자를 정리하고 `paths` 를 `imports` 로 옮긴다. (6) 스크립트 하나를 `node script.ts` 로 돌려본 뒤 진입점으로 범위를 넓힌다. (7) `tsc --noEmit` 을 CI 게이트로 고정한다.

Node 최소 버전은 목적에 따라 다르다. 기본 활성화면 v22.18.0/v23.6.0 이상, 실험 경고 제거는 v22.18.0/v24.3.0 이상, Stability 2(Stable) 보장은 v24.12.0/v25.2.0 이상이다. v26 부터는 `--experimental-transform-types` 가 없으므로 enum 이 남은 코드는 `amaro/transform` 로더로 처리해야 한다.

## 참고

- [Node.js Docs — Modules: TypeScript](https://nodejs.org/api/typescript.html)
- [Node.js Docs — Command-line API](https://nodejs.org/api/cli.html)
- [Node.js Docs — Packages: subpath imports](https://nodejs.org/api/packages.html#subpath-imports)
- [Node.js Learn — Running TypeScript Natively](https://nodejs.org/learn/typescript/run-natively)
- [nodejs/amaro](https://github.com/nodejs/amaro)
- [nodejs/node#61803](https://github.com/nodejs/node/pull/61803)
- [TypeScript 5.8 — The `--erasableSyntaxOnly` Option](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-8.html#the---erasablesyntaxonly-option)
- [TSConfig — `verbatimModuleSyntax`](https://www.typescriptlang.org/tsconfig/#verbatimModuleSyntax)
