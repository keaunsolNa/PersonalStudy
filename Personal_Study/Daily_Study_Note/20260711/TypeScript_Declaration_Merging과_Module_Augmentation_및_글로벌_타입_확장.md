Notion 원본: https://app.notion.com/p/39a5a06fd6d381eeb183c2c6bd7ad5c3

# TypeScript Declaration Merging과 Module Augmentation 및 글로벌 타입 확장

> 2026-07-11 신규 주제 · 확장 대상: TypeScript (타입 시스템·모듈 해석 학습됨)

## 학습 목표

- 인터페이스·네임스페이스·enum의 선언 병합(declaration merging) 규칙을 구분해 적용한다
- `declare module`로 서드파티 모듈과 자체 모듈의 타입을 안전하게 확장한다
- `declare global`과 `globalThis` 확장의 스코프 규칙을 판단한다
- 병합 충돌과 우선순위 문제를 진단하고 회피 전략을 선택한다

## 1. 선언 병합의 기본 원리

TypeScript는 같은 이름의 선언을 하나로 합치는 declaration merging을 지원한다. 이는 타입 세계에만 존재하는 기능으로, 런타임 자바스크립트에는 흔적이 없다. 병합 가능한 선언은 크게 세 부류다. 인터페이스는 서로 병합되고, 네임스페이스는 서로 병합되며, 함수·클래스·enum·네임스페이스는 특정 조합으로 서로 병합된다.

가장 흔한 예는 인터페이스 병합이다. 같은 스코프에서 같은 이름의 인터페이스를 두 번 선언하면 멤버가 합쳐진다.

```ts
interface Box { width: number; }
interface Box { height: number; }
// 최종 Box는 { width: number; height: number; }
const b: Box = { width: 10, height: 20 };
```

이 동작이 타입 별칭(`type`)과 결정적으로 다른 지점이다. `type Box = {...}`를 두 번 선언하면 "Duplicate identifier" 오류가 난다. 인터페이스는 열려 있고(open), 타입 별칭은 닫혀 있다(closed). 라이브러리가 사용자 확장을 허용하려면 공개 계약을 인터페이스로 노출하는 이유가 여기 있다.

## 2. 병합 시 멤버 규칙과 오버로드 순서

인터페이스 병합에서 비함수 멤버는 이름이 겹치면 타입이 동일해야 한다. 다르면 오류다. 함수 멤버(메서드)는 오버로드로 취급되어 모두 살아남는다. 이때 오버로드 해석 순서가 중요하다. 같은 인터페이스 안에서는 소스에 나온 순서대로, 서로 다른 인터페이스 선언 간에는 나중에 선언된 인터페이스의 오버로드가 먼저 고려된다. 단, 리터럴 타입을 받는 특수 오버로드는 우선순위가 올라간다.

```ts
interface Doc { createElement(tag: "div"): HTMLDivElement; }
interface Doc { createElement(tag: string): HTMLElement; }
interface Doc { createElement(tag: "canvas"): HTMLCanvasElement; }
```

위에서 `"canvas"`를 넘기면 마지막 선언(가장 나중)의 구체적 오버로드가 먼저 매칭된다. 이 규칙 때문에 라이브러리 확장 시 구체적인 리터럴 오버로드를 뒤쪽 선언에 두면 사용자 코드에서 더 정확한 반환 타입을 얻는다.

## 3. 네임스페이스와 다른 선언의 병합

네임스페이스는 함수·클래스·enum과 병합되어 "정적 멤버 부착" 패턴을 만든다. 함수에 프로퍼티를 붙이거나, 클래스에 내부 타입을 중첩할 때 쓴다.

```ts
function greet(name: string) { return `Hello ${name}`; }
namespace greet {
  export const version = "1.0";
  export type Options = { loud: boolean };
}
greet("world");
greet.version;          // "1.0"
let o: greet.Options;   // 타입 접근
```

병합 순서 규칙이 있다. 클래스·함수·enum이 먼저 선언되고 네임스페이스가 뒤에 와야 한다. 반대 순서면 오류다. 네임스페이스가 값과 병합될 때는 네임스페이스에서 `export`한 멤버만 외부에 노출된다. enum과 네임스페이스 병합은 enum에 계산된 정적 헬퍼를 붙이는 데 유용하다.

주의할 점은 클래스끼리는 병합되지 않는다는 것이다. 클래스 + 인터페이스는 병합되어(인터페이스가 인스턴스 측 타입을 확장) 데코레이터나 믹스인 패턴에서 인스턴스 형태를 넓히는 데 쓰이지만, 클래스 + 클래스는 중복 식별자 오류다.

## 4. Module Augmentation으로 서드파티 확장

이미 존재하는 모듈의 타입을 확장하려면 `declare module "모듈명"` 블록을 쓴다. 이것이 module augmentation이다. 대표적으로 Express의 `Request`에 사용자 정의 필드를 추가하는 경우다.

```ts
// express.d.ts (프로젝트 내부, import 문이 있는 모듈 스코프여야 함)
import "express";

declare module "express-serve-static-core" {
  interface Request {
    user?: { id: string; roles: string[] };
  }
}
```

핵심 제약이 있다. augmentation 파일이 최상위 `import`/`export`를 하나라도 가져 "모듈"로 인식되어야 `declare module`이 augmentation으로 동작한다. 파일에 import/export가 전혀 없으면 그 파일은 전역 스크립트로 취급되고, `declare module "x"`는 augmentation이 아니라 "새 앰비언트 모듈 선언"이 되어 원래 타입을 통째로 덮어써 버린다. 이 차이가 실무에서 가장 흔한 함정이다.

또한 augmentation은 기존 선언에 멤버를 "추가"만 할 수 있고 최상위 export의 타입을 바꾸거나 제거할 수는 없다. 확장 대상이 인터페이스여야 병합이 되므로, 라이브러리가 타입 별칭으로 내보낸 형태는 augmentation으로 확장할 수 없다.

## 5. 전역 타입 확장 — declare global

모듈 파일 안에서 전역 스코프를 확장하려면 `declare global` 블록을 쓴다. 모듈(import/export 있는 파일) 내부에서는 최상위 선언이 전역이 아니라 모듈 로컬이 되므로, 전역을 건드리려면 명시적으로 `declare global`로 감싸야 한다.

```ts
export {}; // 이 파일을 모듈로 만든다

declare global {
  interface Window {
    __APP_CONFIG__: { apiBase: string };
  }
  interface Array<T> {
    last(): T | undefined;
  }
}

Array.prototype.last = function () { return this[this.length - 1]; };
```

`interface Window`는 lib.dom.d.ts의 전역 `Window` 인터페이스와 병합된다. `globalThis`에 프로퍼티를 추가하려면 `interface globalThis`가 아니라 `var`를 `declare global` 안에서 선언한다.

```ts
declare global {
  // eslint-disable-next-line no-var
  var appVersion: string;
}
```

`let`/`const`가 아닌 `var`여야 `globalThis.appVersion`으로 접근 가능한 전역 변수가 된다. `let`은 전역 렉시컬 스코프에 들어가 `globalThis` 프로퍼티가 되지 않기 때문이다.

## 6. 병합 우선순위와 앰비언트 vs 비앰비언트

한 심볼에 값·타입·네임스페이스의 세 의미(meaning)가 동시에 존재할 수 있다. 예컨대 클래스는 값(생성자)이자 타입(인스턴스)이고, enum은 값이자 타입이다. 병합은 "같은 의미 공간(declaration space)"에서만 충돌한다. 그래서 인터페이스(타입 공간)와 변수(값 공간)는 이름이 같아도 공존한다.

```ts
interface Config { port: number; }
const Config = { port: 3000 };  // 값 Config와 타입 Config 공존
const c: Config = Config;
```

앰비언트 선언(`declare`)과 실제 선언의 병합에도 규칙이 있다. `.d.ts`의 `declare`는 구현 없는 선언이고, 실제 `.ts`의 선언과 이름이 겹치면 병합 규칙에 따라 합쳐지거나 충돌한다. 라이브러리 타입 정의를 확장할 때 이 경계를 이해해야 예상치 못한 덮어쓰기를 피한다.

## 7. 충돌 진단과 트레이드오프

병합의 편의성은 곧 위험이다. 여러 파일에서 같은 인터페이스를 확장하면 최종 형태가 파일 전체에 흩어져 추적이 어렵다. 특히 `declare global`은 프로젝트 전역에 영향을 미쳐, 어느 라이브러리가 `Array.prototype`을 확장했는지 파악하기 힘들게 만든다.

| 확장 수단 | 스코프 | 위험 | 권장 상황 |
|-----------|--------|------|-----------|
| interface 병합 | 선언 스코프 | 낮음 | 자체 타입의 점진 확장 |
| declare module | 대상 모듈 | 중간(파일 모듈성 조건) | 서드파티 타입 보강 |
| declare global | 전역 | 높음 | 폴리필, 전역 설정 |

실무 지침은 세 가지다. 첫째, augmentation 파일에는 반드시 `import`나 `export {}`를 넣어 모듈로 만든다. 둘째, 전역 확장은 최소화하고 한 파일(`global.d.ts`)에 모아 추적성을 높인다. 셋째, 확장 대상이 인터페이스인지 타입 별칭인지 먼저 확인한다. 타입 별칭이면 병합이 불가능하므로 래퍼 타입이나 교차 타입(`&`)으로 우회한다.

## 8. 실전 시나리오

NestJS·Express 미들웨어에서 요청 객체에 인증 정보를 붙이는 것은 module augmentation의 교과서 사례다. `Request` 인터페이스에 `user`를 optional로 추가하되, 인증 미들웨어를 통과하지 않은 라우트에서 `undefined` 가능성을 타입으로 강제하려면 optional로 두는 것이 안전하다. 필수로 만들면 인증 전 접근 경로에서 타입이 거짓 안전을 준다.

Vite·Webpack의 `import.meta.env`나 커스텀 파일 확장자(`*.svg`) import를 타이핑할 때도 `declare module "*.svg"` 패턴을 쓴다. 이 경우는 augmentation이 아니라 앰비언트 모듈 선언이므로 파일에 import/export가 없어도 되며, 오히려 없어야 와일드카드 앰비언트 모듈로 동작한다.

라이브러리 저자 입장에서는 사용자 확장 지점을 인터페이스로 설계하고, 확장 포인트를 문서화한다. 예를 들어 상태 관리 라이브러리가 `interface Register {}`라는 빈 인터페이스를 노출하면 사용자가 module augmentation으로 자신의 스토어 타입을 등록해 전역 추론에 참여시킬 수 있다. 이는 tRPC·i18next 같은 라이브러리가 "type registration" 패턴으로 사용하는 기법으로, 빈 인터페이스가 확장 훅 역할을 한다.

## 참고

- TypeScript Handbook, "Declaration Merging" (typescriptlang.org/docs/handbook/declaration-merging.html)
- TypeScript Handbook, "Modules — Ambient Modules & Augmentation"
- lib.dom.d.ts, microsoft/TypeScript 소스의 전역 인터페이스 정의
- DefinitelyTyped 기여 가이드 (github.com/DefinitelyTyped/DefinitelyTyped)
- "Type Registration" 패턴 사례: tRPC, i18next 공식 문서
