Notion 원본: https://app.notion.com/p/3a85a06fd6d381c0bed7c5efbc4dc525

# TypeScript 선언 병합과 모듈 augmentation 및 앰비언트 타입 확장

> 2026-07-25 신규 주제 · 확장 대상: TypeScript(타입 시스템·모듈 해석)

## 학습 목표

- 선언 병합(declaration merging)이 인터페이스·네임스페이스·enum에서 어떤 규칙으로 합쳐지는지 구분한다.
- `declare module`을 이용한 모듈 augmentation으로 외부 라이브러리 타입을 안전하게 확장한다.
- 전역 스코프 확장(`declare global`)과 앰비언트 선언(`.d.ts`)의 적용 범위와 위험을 정리한다.
- Express `Request`, 프로세스 환경 변수 등 실무에서 자주 쓰는 augmentation 패턴을 적용한다.

## 1. 선언 병합이란 — 같은 이름을 합치는 규칙

TypeScript는 컴파일 과정에서 동일한 이름의 여러 선언을 하나의 정의로 합친다. 이를 선언 병합이라 한다. JavaScript에는 없는 개념으로, 타입 레벨과 값 레벨을 분리해서 다루는 TypeScript의 구조 때문에 가능하다. 모든 선언이 병합되는 것은 아니며, 병합 가능한 대상은 인터페이스, 네임스페이스, enum, 그리고 이들과 함수·클래스의 특정 조합이다.

가장 기본은 인터페이스 병합이다. 같은 이름의 인터페이스를 여러 번 선언하면 멤버가 합쳐진다.

```typescript
interface Box { height: number; }
interface Box { width: number; }
// 병합 결과: interface Box { height: number; width: number; }

const b: Box = { height: 10, width: 20 };  // 두 멤버 모두 필요
```

이는 우연한 이름 충돌이 아니라 의도적 확장 메커니즘이다. 라이브러리가 공개한 인터페이스에 사용자가 필드를 덧붙일 수 있게 하는 것이 핵심 활용처다. 반면 `type` 별칭은 병합되지 않는다 — 같은 이름의 `type`을 두 번 선언하면 중복 식별자 에러가 난다. 이 차이가 라이브러리 공개 타입을 `interface`로 두는 주요 이유 중 하나다.

## 2. 병합 시 충돌 규칙

인터페이스 병합에는 제약이 있다. 같은 이름의 비함수 멤버는 **타입이 동일해야** 한다. 다르면 충돌 에러다.

```typescript
interface A { x: number; }
interface A { x: string; }  // Error: 후속 프로퍼티 선언의 타입이 불일치
```

함수 멤버(메서드)는 오버로드로 취급되어 여러 시그니처가 누적된다. 이때 나중에 선언된 인터페이스의 시그니처가 **앞선 오버로드보다 먼저** 배치된다는 규칙이 중요하다. 오버로드 해석은 위에서 아래로 첫 매치를 찾으므로, 더 구체적인 시그니처가 나중 선언에 있으면 우선순위를 갖는다.

네임스페이스는 인터페이스와, 그리고 함수·클래스·enum과도 병합된다. 함수에 네임스페이스를 병합하면 함수에 정적 프로퍼티를 타입 안전하게 붙일 수 있다.

```typescript
function greet(name: string) { return `Hi ${name}`; }
namespace greet {
  export const version = '1.0';
}
greet('kim');          // 함수 호출
greet.version;         // 정적 프로퍼티 — 타입: string
```

클래스 + 네임스페이스 병합은 내부 타입이나 정적 헬퍼를 클래스 이름 공간 안에 조직하는 데 쓰인다. enum + 네임스페이스는 enum 멤버에 메타데이터 함수를 붙이는 패턴에 유용하다.

## 3. 모듈 augmentation — 외부 라이브러리 확장

가장 실전적인 활용은 모듈 augmentation이다. 설치된 npm 패키지의 타입 정의를 소스 수정 없이 확장한다. `declare module '패키지명'` 블록 안에서 그 모듈이 내보낸 인터페이스를 재선언하면 병합된다.

```typescript
// express 의 Request 에 커스텀 필드 추가
import 'express';

declare module 'express-serve-static-core' {
  interface Request {
    user?: { id: string; roles: string[] };
    requestId: string;
  }
}
```

이제 프로젝트 전역에서 `req.user`, `req.requestId`가 타입 안전하게 인식된다. 핵심 규칙 두 가지가 있다. 첫째, augmentation 파일은 반드시 **모듈**이어야 한다 — 최소 하나의 `import` 또는 `export`가 파일에 있어야 전역 스크립트가 아닌 모듈로 인식되고 `declare module`이 augmentation으로 동작한다. 둘째, 확장 대상 모듈 이름이 실제 타입이 선언된 모듈과 정확히 일치해야 한다. Express의 경우 `Request`는 `express`가 아니라 `express-serve-static-core`에 선언되어 있어 그 이름을 써야 한다.

## 4. 전역 스코프 확장 — `declare global`

모듈 내부에서 전역 타입을 확장하려면 `declare global` 블록을 쓴다. 대표 사례가 `process.env`의 타입 강화다.

```typescript
export {};  // 이 파일을 모듈로 만들기 위한 빈 export

declare global {
  namespace NodeJS {
    interface ProcessEnv {
      DATABASE_URL: string;
      NODE_ENV: 'development' | 'production' | 'test';
      PORT?: string;
    }
  }
}
```

이제 `process.env.NODE_ENV`가 `string`이 아니라 세 리터럴 유니온으로 좁혀지고, 오탈자 접근이 컴파일 타임에 잡힌다. 단 이는 **타입 수준 약속일 뿐 런타임 검증이 아니다** — 실제 환경 변수가 비어 있어도 컴파일러는 `string`이 있다고 믿는다. 그래서 부팅 시점에 Zod 등으로 실제 값을 검증하는 계층과 병행해야 안전하다.

전역 확장은 강력한 만큼 위험하다. 확장한 타입이 프로젝트 전체에 적용되므로, 라이브러리를 만들 때 전역을 오염시키면 사용자의 타입 환경과 충돌할 수 있다. 라이브러리 패키지는 가능한 한 모듈 로컬 타입으로 한정하고, 전역 확장은 애플리케이션 코드에서만 신중히 사용한다.

## 5. 앰비언트 선언과 `.d.ts`

`declare` 키워드는 "이 심볼은 다른 곳에 존재하니 구현 없이 타입만 알려준다"는 앰비언트 선언이다. 타입 정의만 담는 `.d.ts` 파일이 대표적 형태다.

```typescript
// images.d.ts — 번들러가 처리하는 비-JS import 에 타입 부여
declare module '*.svg' {
  const content: string;
  export default content;
}

declare module '*.module.css' {
  const classes: { readonly [key: string]: string };
  export default classes;
}
```

와일드카드 모듈 선언(`*.svg`)은 번들러(webpack, Vite)가 자산을 모듈로 변환하는 환경에서 import 구문에 타입을 부여한다. 이것이 없으면 `import icon from './x.svg'`가 타입 에러를 낸다.

`.d.ts`가 프로젝트에 포함되는 방식은 `tsconfig.json`의 `include`/`files`와 `typeRoots`/`types` 설정에 달렸다. 전역 앰비언트 `.d.ts`(import/export 없는 스크립트 파일)는 컴파일 대상에 포함되기만 하면 자동으로 전역에 반영된다. 반면 `import`가 있는 `.d.ts`는 모듈이 되어 전역이 아니라 명시적으로 import해야 적용된다 — 전역 augmentation을 의도했는데 무심코 `import`를 넣으면 적용이 안 되는 흔한 함정이다(이때는 `declare global`로 감싸야 한다).

## 6. 병합 우선순위와 디버깅

여러 augmentation이 겹치거나 라이브러리 자체 타입과 충돌할 때 동작을 예측하려면 병합 순서 규칙을 알아야 한다. 인터페이스 병합에서 비메서드 멤버는 타입 동일성이 강제되므로 충돌 시 명확한 에러가 난다. 메서드 오버로드는 파일 처리 순서와 선언 순서에 따라 배치되는데, 프로젝트 내 선언은 파일 포함 순서의 영향을 받고 `node_modules`의 타입보다 프로젝트 로컬 선언이 나중에 병합되는 경향이 있다.

augmentation이 적용되지 않을 때 점검 순서는 다음과 같다. 첫째, 파일이 모듈인가(import/export 존재). 둘째, `declare module` 대상 이름이 실제 선언 모듈과 일치하는가. 셋째, 그 파일이 `tsconfig` 컴파일 범위에 포함되는가. 넷째, 확장하려는 심볼이 `interface`인가 — `type` 별칭이나 `class` 인스턴스 형태는 병합되지 않으므로 애초에 확장 불가다. 이 네 가지가 대부분의 "augmentation이 안 먹힌다" 문제의 원인이다.

## 7. 라이브러리 저자를 위한 확장 지점 설계

선언 병합은 라이브러리 사용자가 코어를 수정하지 않고 타입을 확장하게 해주는 유일한 표준 메커니즘이다. 성숙한 라이브러리들은 이를 의도적 확장 지점으로 노출한다. 대표 사례가 Redux Toolkit, Vue, styled-components의 테마 타입이다. 이들은 빈 인터페이스를 공개하고 사용자가 병합으로 채우게 한다.

```typescript
// 라이브러리 측 (styled-components 유사)
declare module 'styled-components' {
  export interface DefaultTheme {}  // 의도적으로 빈 인터페이스
}

// 사용자 측
import 'styled-components';
declare module 'styled-components' {
  export interface DefaultTheme {
    colors: { primary: string; secondary: string };
    spacing: (n: number) => string;
  }
}
```

이 패턴의 설계 의도는 "코어는 구조를 모르지만 사용자가 채우면 전역적으로 타입 안전"이다. 라이브러리 저자 관점에서 확장 지점을 `interface`로 노출하면(`type`이 아니라) 사용자 병합이 가능해진다. 반대로 확장을 막고 싶은 타입은 `type` 별칭으로 봉인한다.

## 8. 병합과 제네릭·조건부 타입의 상호작용

선언 병합은 정적 이름 기반이라 제네릭 파라미터를 병합할 수 없다. 같은 이름의 제네릭 인터페이스를 병합하려면 **타입 파라미터 목록이 동일**해야 한다. 이름과 개수가 다르면 충돌 에러다.

```typescript
interface Container<T> { value: T; }
interface Container<T> { fallback: T; }   // OK — 파라미터 동일
// interface Container<T, U> { ... }       // Error — 파라미터 목록 불일치
```

또한 augmentation으로 추가한 멤버는 조건부 타입·매핑 타입에서도 코어 멤버와 동일하게 취급된다. 즉 `keyof Request`는 병합으로 추가한 `user`·`requestId`를 포함한다. 이 성질은 강력하지만 함정도 만든다 — 유틸리티 타입(`Partial<Request>`, `Pick<Request, ...>`)이 augmentation된 필드까지 포함하므로, 서드파티 augmentation이 예기치 않게 파생 타입의 형태를 바꿀 수 있다.

실무 권고를 정리하면 세 가지다. 첫째, 애플리케이션 코드에서는 augmentation을 `types/` 디렉터리에 집중하고 각 파일 상단에 확장 목적을 주석으로 남긴다. 둘째, 라이브러리를 만들 때는 전역 오염을 피하고 모듈 스코프 확장 지점(빈 `interface`)만 신중히 노출한다. 셋째, `tsc --explainFiles`나 IDE의 참조 추적으로 어떤 `.d.ts`가 컴파일에 포함되는지 주기적으로 점검해 유령 augmentation을 방지한다. 선언 병합은 타입 시스템의 개방성을 보장하는 대신 그 개방성이 무질서로 흐르지 않게 규율이 필요한 기능이다.

## 참고

- TypeScript Handbook — Declaration Merging (https://www.typescriptlang.org/docs/handbook/declaration-merging.html)
- TypeScript Handbook — Modules: Ambient Declarations (https://www.typescriptlang.org/docs/handbook/modules/introduction.html)
- TypeScript Deep Dive — Declaration Merging & Global Modules (https://basarat.gitbook.io/typescript/type-system/global.d.ts)
- DefinitelyTyped Contribution Guide — Module Augmentation Patterns (https://github.com/DefinitelyTyped/DefinitelyTyped)
