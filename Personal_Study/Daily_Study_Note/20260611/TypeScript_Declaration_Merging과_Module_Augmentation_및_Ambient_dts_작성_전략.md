Notion 원본: https://www.notion.so/37c5a06fd6d381bdad10e5180d63501d

# TypeScript Declaration Merging과 Module Augmentation 및 Ambient d.ts 작성 전략

> 2026-06-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- interface 병합, namespace 병합, namespace + 함수/클래스 병합 규칙을 구분해 적용한다
- `declare module`로 외부 패키지와 전역 스코프를 안전하게 확장한다
- ambient 선언(`.d.ts`)에서 global / module / UMD 선언을 상황에 맞게 작성한다
- 병합과 augmentation의 충돌·우선순위 규칙을 파악해 라이브러리 타입 충돌을 디버깅한다

## 1. Declaration Merging의 기본 개념

TypeScript 컴파일러는 같은 이름의 선언 여러 개를 하나의 정의로 합친다. 이를 declaration merging이라 하며, 병합 가능 여부는 선언의 종류(declaration "공간")에 따라 다르다. TypeScript의 식별자는 값(value), 타입(type), 네임스페이스(namespace) 세 공간 중 하나 이상에 속한다.

- `interface`: 타입 공간. 같은 이름끼리 멤버가 병합된다.
- `type` 별칭: 타입 공간. **병합 불가**. 중복 선언 시 에러.
- `namespace`: 네임스페이스 공간. 병합되며 다른 종류와도 병합 가능.
- `class`: 값 + 타입 공간 둘 다 차지. class끼리는 병합 불가하지만 namespace와는 병합 가능.
- `enum`: namespace와 병합 가능.
- `function`: 값 공간. namespace와 병합 가능.

핵심은 "interface와 namespace는 병합 가능, type alias는 병합 불가"라는 점이다. 라이브러리 타입을 확장할 때 interface를 우선 쓰는 이유가 여기에 있다.

## 2. interface 병합 규칙

같은 이름의 interface 두 개는 멤버가 합쳐진다. 비함수 멤버는 이름이 겹치면 타입이 같아야 하고, 함수 멤버는 오버로드로 누적된다.

```ts
interface Box {
  height: number;
  width: number;
}

interface Box {
  scale: number;
}

const b: Box = { height: 5, width: 6, scale: 10 }; // 세 멤버 모두 필요
```

함수 시그니처(오버로드) 병합에는 순서 규칙이 있다. 같은 interface 내 오버로드는 선언 순서대로지만, **서로 다른 interface 블록 간에는 나중에 선언된 블록이 앞쪽 오버로드로 들어간다**. 단, 리터럴 타입처럼 더 구체적인 시그니처가 우선 매칭되도록 의도적으로 배치할 때 이 규칙이 중요하다.

```ts
interface Cloner {
  clone(animal: Animal): Animal;
}
interface Cloner {
  clone(animal: Dog): Dog; // 더 구체적
}
// 결과 오버로드 순서: Dog 시그니처가 Animal 시그니처보다 앞에 위치
```

## 3. namespace 병합과 값+타입 결합

namespace는 다른 namespace, 그리고 class/function/enum과 병합된다. 이를 이용해 "함수에 정적 프로퍼티를 붙이거나", "클래스에 내부 타입을 첨부"하는 패턴을 만든다.

```ts
function buildLabel(name: string): string {
  return buildLabel.prefix + name + buildLabel.suffix;
}

namespace buildLabel {
  export let prefix = 'Hello, ';
  export let suffix = '!';
}

console.log(buildLabel('World')); // "Hello, World!"
```

class + namespace 병합은 "클래스에 정적 멤버나 중첩 타입을 추가"할 때 쓴다.

```ts
class Album {
  label: Album.AlbumLabel = new Album.AlbumLabel();
}

namespace Album {
  export class AlbumLabel {}
}
```

주의할 점은 병합 대상 namespace가 class/function보다 **뒤에** 와야 한다는 것이다. namespace가 앞에 오면 값이 초기화되기 전에 참조하는 문제가 생긴다.

## 4. Module Augmentation — 외부 패키지 확장

설치한 라이브러리의 타입을 수정 없이 확장할 때 module augmentation을 사용한다. `declare module '패키지명'` 블록 안에서 그 모듈이 export하는 interface를 다시 선언하면 원본과 병합된다.

대표 사례가 Express의 `Request`에 사용자 정보를 붙이는 것이다.

```ts
// types/express.d.ts
import 'express';

declare module 'express-serve-static-core' {
  interface Request {
    user?: {
      id: string;
      roles: string[];
    };
  }
}
```

augmentation 파일에는 최소 하나의 top-level `import` 또는 `export`가 있어야 한다. 그래야 이 파일이 전역 스크립트가 아니라 모듈로 취급되어, `declare module` 블록이 "전역 ambient 선언"이 아니라 "기존 모듈 확장"으로 해석된다. 이 한 줄이 빠지면 augmentation이 무시되거나 의도와 다르게 새 모듈을 선언하는 결과가 된다.

또 하나의 제약은 augmentation으로는 기존 멤버의 타입을 바꾸거나 새로운 top-level export를 추가할 수 없다는 점이다. augmentation은 기존 interface/namespace에 멤버를 "더하는" 것만 가능하다.

## 5. 전역 스코프 확장 (global augmentation)

전역 객체(`window`, `globalThis`, `process.env` 등)를 확장하려면 `declare global` 블록을 모듈 파일 안에서 사용한다.

```ts
// types/global.d.ts
export {}; // 이 파일을 모듈로 만든다

declare global {
  interface Window {
    __APP_VERSION__: string;
  }

  namespace NodeJS {
    interface ProcessEnv {
      DATABASE_URL: string;
      NODE_ENV: 'development' | 'production' | 'test';
    }
  }
}
```

`export {}`로 파일을 모듈화한 뒤 `declare global`로 전역에 진입하는 패턴이 표준이다. 이렇게 하면 `process.env.NODE_ENV`가 `string` 대신 리터럴 union으로 좁혀져, 오타나 잘못된 환경값을 컴파일 단계에서 잡을 수 있다.

## 6. Ambient 선언과 .d.ts 작성 전략

ambient 선언은 구현 없이 타입만 기술한다. JS로 작성된 라이브러리나 빌드 산출물에 타입을 입힐 때 쓴다. 작성 형태는 크게 세 가지다.

| 형태 | 구문 | 사용 상황 |
|---|---|---|
| Module 선언 | `declare module 'x' { export ... }` | npm 패키지에 타입이 없을 때 |
| Global 선언 | 모듈 import/export 없는 `.d.ts`의 `declare const` | `<script>`로 전역 주입되는 라이브러리 |
| UMD 선언 | `export as namespace X` | 전역과 모듈 양쪽으로 쓰이는 라이브러리 |

타입 없는 패키지에 최소 stub을 다는 예:

```ts
// types/legacy-lib.d.ts
declare module 'legacy-lib' {
  export interface Options {
    timeout?: number;
    retries?: number;
  }
  export function connect(url: string, opts?: Options): Promise<void>;
  const _default: { version: string };
  export default _default;
}
```

비-코드 에셋을 import 가능하게 만드는 와일드카드 모듈 선언도 자주 쓴다.

```ts
// types/assets.d.ts
declare module '*.svg' {
  const content: string;
  export default content;
}
declare module '*.css' {
  const classes: { readonly [key: string]: string };
  export default classes;
}
```

`tsconfig.json`에서 이 ambient 파일들이 컴파일에 포함되도록 `include`나 `typeRoots`/`types`를 설정해야 한다. 흔한 실수는 `.d.ts`를 `src` 밖에 두고 `include`에서 누락해 타입이 적용되지 않는 경우다.

## 7. 충돌·우선순위와 디버깅

여러 선언이 병합되거나 augmentation이 겹치면 충돌이 발생할 수 있다. 자주 보는 문제와 진단법은 다음과 같다.

첫째, `@types/*` 패키지 두 개가 같은 전역을 다르게 선언하면 "Duplicate identifier" 에러가 난다. `tsconfig`의 `types` 배열로 포함할 `@types` 패키지를 명시적으로 제한해 해결한다.

```jsonc
{
  "compilerOptions": {
    "types": ["node", "jest"] // 이 둘만 자동 포함
  }
}
```

둘째, augmentation이 적용되지 않을 때는 (1) 파일이 모듈인지(`import`/`export` 존재), (2) `declare module`의 모듈 경로가 정확한지(Express처럼 실제 타입이 `express-serve-static-core`에 있는 경우), (3) 파일이 컴파일 그래프에 포함됐는지를 순서대로 확인한다.

셋째, interface 병합에서 같은 이름 비함수 멤버를 서로 다른 타입으로 선언하면 에러다. 이때는 union으로 통일하거나 한쪽 선언을 제거한다.

디버깅 시 `tsc --traceResolution`으로 모듈 경로 해석을 추적하고, IDE의 "Go to Type Definition"으로 실제로 병합된 결과가 어느 파일에서 왔는지 확인하는 것이 빠르다.

## 8. 트레이드오프와 권장 패턴

declaration merging과 augmentation은 외부 코드를 건드리지 않고 타입을 확장하는 강력한 수단이지만, 전역 상태처럼 "보이지 않는 결합"을 만든다. 전역 augmentation은 프로젝트 어디서나 효과가 미치므로, 한 곳에서 `Window`에 추가한 프로퍼티가 다른 모듈의 가정과 충돌할 수 있다.

권장 사항을 정리하면, 라이브러리 타입 확장은 가능한 한 좁은 스코프의 module augmentation으로 한정하고 전역 확장은 최소화한다. ambient `.d.ts`는 `types/` 디렉터리에 모아 `include`에 명시하고, 패키지마다 파일을 분리해 충돌 원인을 추적하기 쉽게 한다. 새 코드라면 ambient 선언보다 실제 타입을 가진 래퍼 모듈을 만드는 편이 장기적으로 유지보수가 쉽다. augmentation은 "남의 타입을 고칠 수 없을 때의 마지막 수단"으로 보는 관점이 안전하다.

```ts
// 권장: augmentation 대신 명시적 래퍼 + 타입 가드
import type { Request } from 'express';

interface AuthedRequest extends Request {
  user: { id: string; roles: string[] };
}

function isAuthed(req: Request): req is AuthedRequest {
  return typeof (req as AuthedRequest).user?.id === 'string';
}
```

이 방식은 전역 오염 없이 명시적으로 타입을 좁혀, 어느 핸들러가 인증을 전제하는지 코드에서 드러난다. augmentation의 편의성과 명시 래퍼의 추적 가능성 사이에서 프로젝트 규모와 팀 컨벤션에 맞춰 선택하면 된다.

## 9. 실전 사례: 라이브러리별 augmentation 패턴

자주 쓰는 라이브러리마다 augmentation의 정확한 대상 모듈과 패턴이 다르다. 잘못된 모듈 경로를 지정하면 조용히 무시되므로 정확한 위치를 아는 것이 중요하다.

**Vue 3 — 전역 컴포넌트 속성**

```ts
// vue-shim.d.ts
import { Router } from 'vue-router';

declare module '@vue/runtime-core' {
  interface ComponentCustomProperties {
    $router: Router;
    $filters: { currency(v: number): string };
  }
}
```

Vue의 컴포넌트 인스턴스 속성은 `vue`가 아니라 `@vue/runtime-core`의 `ComponentCustomProperties`에 추가해야 한다.

**Redux — 미들웨어 dispatch 확장**

```ts
declare module 'redux' {
  export interface Dispatch<A extends Action = AnyAction> {
    <R>(asyncAction: ThunkAction<R, any, any, A>): R;
  }
}
```

**Jest / Vitest — 커스텀 matcher**

```ts
interface CustomMatchers<R = unknown> {
  toBeValidEmail(): R;
}
declare module 'vitest' {
  interface Assertion<T = any> extends CustomMatchers<T> {}
  interface AsymmetricMatchersContaining extends CustomMatchers {}
}
```

이처럼 "어느 패키지의 어느 interface에 추가하는가"를 정확히 찾는 것이 augmentation의 절반이다. 방법은 IDE에서 원본 타입의 정의로 이동(Go to Definition)해 실제 export되는 interface 이름과 모듈 경로를 확인하는 것이다. `node_modules/@types/...` 또는 패키지의 `dist/*.d.ts`에서 대상 interface를 찾으면 된다.

검증 절차도 정해두면 좋다. augmentation 파일을 추가한 뒤 (1) 그 파일이 `import`/`export`를 가진 모듈인지, (2) `tsconfig`의 `include`에 포함되는 경로인지, (3) 확장한 속성이 실제 사용처에서 자동완성·타입체크되는지를 차례로 확인한다. 하나라도 빠지면 "분명 선언했는데 타입이 안 잡힌다"는 전형적 증상이 나타난다. 이때 `tsc --noEmit`으로 전체 타입 그래프를 한 번 컴파일해보면 augmentation이 실제 반영됐는지 가장 확실하게 검증된다.

## 참고

- TypeScript Handbook — Declaration Merging: https://www.typescriptlang.org/docs/handbook/declaration-merging.html
- TypeScript Handbook — Modules / Module Augmentation: https://www.typescriptlang.org/docs/handbook/modules/reference.html
- TypeScript Handbook — Declaration Files (.d.ts) 작성 가이드: https://www.typescriptlang.org/docs/handbook/declaration-files/introduction.html
- DefinitelyTyped 기여 가이드(ambient 선언 컨벤션): https://github.com/DefinitelyTyped/DefinitelyTyped
