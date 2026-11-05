Notion 원본: https://app.notion.com/p/40f5a06fd6d381f58ecdcedc8e18f841

# TypeScript 선언 병합과 모듈 증강(Module Augmentation) 및 d.ts 작성 실전

> 2026-11-05 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- interface · namespace · class · enum 사이의 선언 병합 규칙을 구분해 적용한다
- `declare module` 모듈 증강과 `declare global` 전역 증강의 제약을 코드로 확인한다
- Express Request 같은 외부 라이브러리 타입을 안전하게 확장한다
- `.d.ts` 파일 작성과 tsconfig 포함 규칙의 함정을 진단한다

## 1. 선언 공간과 병합이 가능한 이유

TypeScript의 식별자는 값(value), 타입(type), 네임스페이스(namespace) 세 공간 중 하나 이상에 속한다. `class`는 값과 타입을 동시에 만들고, `interface`는 타입 공간에만 이름을 만든다. 선언 병합(declaration merging)은 컴파일러가 같은 이름의 선언이 여러 개 있을 때 이를 하나의 정의로 합치는 동작이며, 병합이 허용되는 조합은 정해져 있다. 같은 공간을 서로 다른 종류의 선언이 차지하면 `Duplicate identifier` 오류가 난다. 예를 들어 `type A = {...}`와 `interface A {...}`는 둘 다 타입 공간을 쓰지만 type alias는 병합을 지원하지 않으므로 충돌한다. 이 차이가 라이브러리 타입을 확장할 때 `interface`를 선택해야 하는 가장 실질적인 이유다.

병합이 가능한 대표 조합은 interface+interface, namespace+namespace, namespace+class, namespace+function, namespace+enum이다. class+class, class+interface 중 interface가 class 이름과 같은 경우(클래스 인스턴스 타입 확장)는 가능하지만 class끼리의 병합은 불가능하다.

## 2. interface 병합 규칙

같은 이름의 interface 선언들은 멤버가 합쳐진다. 비함수 멤버는 선언이 여러 번 나와도 타입이 동일해야 하며 서로 다르면 `Subsequent property declarations must have the same type` 오류가 난다. 함수 멤버는 오버로드로 취급되어 합쳐지고, 이때 나중에 선언된 interface 집합의 오버로드가 앞쪽에 배치된다(우선순위가 높다). 단 매개변수 타입이 단일 문자열 리터럴인 시그니처는 순서와 무관하게 오버로드 목록 맨 앞으로 끌어올려진다.

```ts
interface Cloner {
  clone(animal: Animal): Animal;
}
interface Cloner {
  clone(animal: Sheep): Sheep;
}
interface Cloner {
  clone(animal: Dog): Dog;
  clone(animal: Cat): Cat;
}
// 병합 결과: 나중 선언 묶음이 앞에 오며, 묶음 내부 순서는 유지된다
// clone(animal: Dog): Dog;
// clone(animal: Cat): Cat;
// clone(animal: Sheep): Sheep;
// clone(animal: Animal): Animal;
```

오버로드 해석은 위에서 아래로 첫 번째로 맞는 시그니처를 고르므로, 더 구체적인 타입이 뒤쪽 선언에 있어야 의도대로 선택된다. 반대로 Animal 시그니처가 먼저 오면 Sheep 인자도 Animal 오버로드에 걸린다.

## 3. namespace 병합과 class · function · enum 확장

namespace는 class, function, enum과 병합되어 정적 멤버를 덧붙이는 용도로 쓸 수 있다. 규칙은 namespace 선언이 병합 대상 class 또는 function 선언보다 같은 파일에서 뒤에 와야 한다는 것이다. 순서가 어긋나면 컴파일 오류가 난다. enum은 순서 제약이 없고 enum 객체에 헬퍼 함수를 붙일 때 쓰인다.

```ts
function buildLabel(name: string): string {
  return buildLabel.prefix + name + buildLabel.suffix;
}
namespace buildLabel {
  export let suffix = "";
  export let prefix = "Hello, ";
}
console.log(buildLabel("Sam")); // Hello, Sam

enum Color { Red, Green }
namespace Color {
  export function parse(s: string): Color {
    return s === "Red" ? Color.Red : Color.Green;
  }
}
```

`isolatedModules`나 트랜스파일 전용 도구(esbuild, swc)를 쓰는 환경에서는 namespace 사용이 제한될 수 있다. `erasableSyntaxOnly` 옵션이 켜져 있으면 namespace와 enum 자체가 오류가 되므로, 최신 프로젝트에서는 객체 리터럴과 `as const` 조합으로 대체하는 편이 안전하다.

## 4. 모듈 증강 declare module

외부 모듈의 타입을 확장하려면 증강 대상 모듈을 import 가능한 모듈 파일 안에서 `declare module "specifier"` 블록으로 선언한다. 핵심 제약은 두 가지다. 첫째, 증강 블록에서는 새로운 최상위 선언을 추가할 수 없고 기존 선언에 멤버를 덧붙이는 패치만 가능하다. 둘째, default export는 증강할 수 없고 이름 있는 export만 증강할 수 있다. 또 증강을 담은 파일 자체가 모듈이어야 하므로 최상위에 `import` 또는 `export`가 하나도 없으면 증강이 아니라 ambient 모듈 선언(전체 덮어쓰기)으로 해석되어 원래 타입이 사라진다. 이 때문에 증강 파일 끝에 `export {};`를 두는 관례가 생겼다.

```ts
// src/types/express-augment.d.ts
import "express-serve-static-core";

declare module "express-serve-static-core" {
  interface Request {
    user?: { id: string; roles: string[] };
    traceId: string;
  }
}
export {};
```

Express에서는 `express`가 아니라 실제로 Request 인터페이스를 정의한 `express-serve-static-core`를 증강해야 핸들러의 `req`에 반영된다. 어느 모듈이 인터페이스를 정의하는지는 `go to definition`으로 확인한다.

## 5. 전역 증강 declare global

모듈 파일 안에서 전역 스코프를 확장하려면 `declare global { ... }`을 쓴다. `Window`, `ProcessEnv` 같은 전역 interface에 멤버를 추가하는 용도다. 이 역시 모듈 파일 안에서만 허용된다. Node 환경변수 타입 지정이 가장 흔한 사례다.

```ts
// env.d.ts
export {};
declare global {
  namespace NodeJS {
    interface ProcessEnv {
      DATABASE_URL: string;
      NODE_ENV: "development" | "production" | "test";
    }
  }
  interface Window {
    __APP_CONFIG__: { apiBase: string };
  }
}
```

런타임에 값이 실제로 존재하는지는 타입이 보장하지 못한다. 환경변수는 Zod 같은 런타임 검증으로 한 번 파싱하고 타입을 파생시키는 편이 증강보다 안전하다.

## 6. 와일드카드 모듈과 d.ts 작성

번들러가 처리하는 정적 자원은 와일드카드 ambient 모듈로 선언한다. 이 선언은 import나 export가 없는 스크립트 형태의 `.d.ts`에 둔다.

```ts
// assets.d.ts  (모듈이 아닌 스크립트 파일이어야 한다)
declare module "*.svg" {
  const url: string;
  export default url;
}
declare module "*.module.css" {
  const classes: { readonly [key: string]: string };
  export default classes;
}
```

CommonJS 스타일 라이브러리의 `module.exports = fn` 형태는 `export =` 문법으로 선언하고, 소비하는 쪽에서는 `esModuleInterop` 또는 `import x = require()`로 가져온다. `.d.ts` 안에서는 구현을 쓸 수 없으며 최상위 선언에는 `declare` 또는 `export`가 필요하다.

## 7. 병합이 일어나지 않는 흔한 원인

증강이 반영되지 않을 때 확인할 항목은 다음 순서가 효율적이다. 먼저 증강 파일이 컴파일 대상인지 본다. tsconfig의 `include`가 `src/**/*.ts`만 포함하고 `.d.ts`를 다른 폴더에 두었다면 파일이 프로그램에 들어오지 않는다. `tsc --listFiles | grep augment`로 확인한다. 다음으로 증강 대상 모듈 specifier가 실제 선언 모듈과 일치하는지 본다. 별칭 경로나 re-export 모듈을 증강하면 원본 interface는 변하지 않는다. 마지막으로 파일이 모듈인지 스크립트인지 확인한다. `export {}` 누락이 가장 빈번하다. `ts-node` 실행 시에는 `--files` 옵션이나 `ts-node.files` 설정이 없으면 `.d.ts`가 자동 로드되지 않는다.

## 8. 설계 지침과 trade-off

라이브러리 타입 증강은 전역 상태를 바꾸는 작업이어서 한 번 추가하면 프로젝트 전체의 해당 타입이 바뀐다. Express의 `req.user`처럼 모든 핸들러가 공유하는 필드에는 적합하지만, 특정 라우트에서만 쓰는 값까지 증강하면 선택 필드가 늘어 타입 안정성이 떨어진다. 이 경우 `Request & { user: User }` 같은 로컬 타입 가드나 제네릭 핸들러 래퍼가 낫다. 또 증강은 라이브러리 메이저 업그레이드 때 깨지기 쉬우므로 증강 파일을 한 디렉터리(`src/types/`)에 모으고 CI에서 `tsc --noEmit`으로 항상 검증한다.

## 9. 검증 방법

병합 결과는 임시 파일에 `const r: import("express").Request = null as any; r.traceId;` 를 쓰고 `tsc --noEmit`이 통과하는지 보거나, 의도적으로 틀린 타입을 대입해 오류가 나는지(양성·음성 케이스 모두) 확인한다. 타입 테스트 라이브러리(tsd, expect-type)를 쓰면 병합 결과를 CI 테스트로 고정할 수 있다.

## 참고

- TypeScript Handbook — Declaration Merging
- TypeScript Handbook — Modules .d.ts, Module Augmentation 항목
- DefinitelyTyped 기여 가이드 (declaration 작성 규칙)
- TypeScript 릴리스 노트 — erasableSyntaxOnly 옵션
