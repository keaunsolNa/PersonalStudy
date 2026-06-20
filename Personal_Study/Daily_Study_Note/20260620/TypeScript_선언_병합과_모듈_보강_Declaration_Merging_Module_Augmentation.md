Notion 원본: https://app.notion.com/p/3855a06fd6d381b092aeff9b2bfd2b59

# TypeScript 선언 병합과 모듈 보강(Declaration Merging·Module Augmentation)

> 2026-06-20 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 동일 이름 선언이 인터페이스·네임스페이스·enum 별로 병합되는 규칙을 구분한다
- `declare module` 로 외부 라이브러리 타입을 확장하는 보강 기법을 작성한다
- 전역 스코프 확장(`declare global`)과 모듈 보강의 적용 범위를 나눈다
- 보강이 깨지는 흔한 원인(모듈 인식 실패·import 누락)을 진단한다

## 1. 선언 병합이란 무엇인가

TypeScript 컴파일러는 같은 이름으로 선언된 여러 개의 선언을 하나로 합친다. 이를 선언 병합(declaration merging)이라 한다. 모든 선언이 병합되는 것은 아니고, 선언이 만드는 "엔티티 종류" — 타입 공간(namespace of types)과 값 공간(namespace of values) — 에 따라 규칙이 다르다. 인터페이스는 타입만, 함수·변수는 값만, 클래스·enum·네임스페이스는 둘 다에 기여할 수 있다. 이 구조를 이해해야 어떤 선언끼리 합쳐지고 충돌하는지 예측할 수 있다.

## 2. 인터페이스 병합

가장 기본은 동일 이름 인터페이스의 병합이다. 멤버들이 합쳐져 하나의 인터페이스가 된다.

```typescript
interface Box {
  width: number;
}
interface Box {
  height: number;
}
// 병합 결과: Box 는 width 와 height 를 모두 가진다
const b: Box = { width: 10, height: 20 }; // OK
```

규칙에는 제약이 있다. 같은 이름의 비함수 멤버는 타입이 동일해야 한다. 다르면 충돌 에러다. 반면 같은 이름의 함수 멤버는 **오버로드** 로 쌓인다. 이때 나중에 선언된 인터페이스의 오버로드가 앞서 평가되며, 단일 리터럴 시그니처가 더 일반적인 시그니처보다 우선하는 정렬 규칙이 있다. 인터페이스 병합은 타입만 다루므로 런타임에는 아무 흔적도 남지 않는다.

## 3. 네임스페이스와의 병합

네임스페이스는 다른 선언과 합쳐져 "정적 멤버" 나 "중첩 타입" 을 부여하는 데 쓰인다. 함수·클래스·enum 에 네임스페이스를 병합하는 패턴이다.

```typescript
function buildLabel(name: string): string {
  return buildLabel.prefix + name;
}
namespace buildLabel {
  export let prefix = "Mr. ";
}
buildLabel("Kim"); // "Mr. Kim"  — 함수에 정적 프로퍼티가 병합됨

class Album {}
namespace Album {
  export class AlbumSelector {} // Album.AlbumSelector 로 접근 가능한 중첩 타입
}
```

함수에 네임스페이스를 병합하면 자바스크립트에서 흔한 "함수이면서 프로퍼티를 가진 객체" 패턴(예: jQuery 의 `$`)을 타입으로 표현할 수 있다. 단 병합되려면 네임스페이스의 export 멤버여야 하고, 클래스+네임스페이스 병합은 선언 순서상 클래스가 먼저 와야 한다.

## 4. 병합되지 않는 것들

클래스끼리는 병합되지 않는다(중복 식별자 에러). enum 은 같은 이름끼리 멤버가 병합되지만, 적어도 하나를 제외한 나머지에 초기값이 있어야 한다. 이 비대칭을 모르면 "왜 인터페이스는 합쳐지는데 클래스는 안 되는가" 에서 막힌다. 핵심은 인터페이스·네임스페이스·enum 처럼 **개방형(open-ended)** 으로 설계된 구문만 병합을 허용한다는 점이다. 클래스는 단일 구현 단위라 병합 대상이 아니다.

```typescript
enum Color { Red }
enum Color { Green = 1 } // OK: 초기값 명시
// enum Color { Blue }   // 에러: 비초기화 멤버 중복

// class Foo {}
// class Foo {}          // 에러: 중복 식별자
```

## 5. 모듈 보강: 외부 라이브러리 타입 확장

선언 병합의 가장 실용적 응용이 모듈 보강(module augmentation)이다. 직접 수정할 수 없는 외부 패키지의 타입에 멤버를 추가한다. `declare module "패키지명"` 블록 안에서 그 모듈이 export 하는 인터페이스를 다시 선언하면 병합된다.

```typescript
// express 의 Request 에 커스텀 프로퍼티 추가
import "express";

declare module "express-serve-static-core" {
  interface Request {
    user?: { id: string; roles: string[] }; // 미들웨어가 주입하는 필드
  }
}

// 이제 어디서나 req.user 가 타입 안전하게 인식됨
app.get("/me", (req, res) => {
  res.json({ id: req.user?.id });
});
```

핵심 규칙: 보강하려는 파일은 **모듈** 이어야 한다(최상위에 `import` 나 `export` 가 있어야 함). 그래서 위 예처럼 `import "express";` 한 줄을 넣는다. 이게 없으면 `declare module` 이 "앵비언트 모듈 선언" 으로 해석되어 기존 타입을 보강하는 대신 통째 덮어쓰거나 인식되지 않는다.

## 6. 전역 스코프 확장

모듈 안에서 전역 객체를 확장하려면 `declare global` 을 쓴다. `Window`, `globalThis`, 내장 프로토타입에 멤버를 더할 때 쓴다.

```typescript
export {}; // 이 파일을 모듈로 만들기 위한 표지

declare global {
  interface Window {
    __APP_CONFIG__: { apiBase: string };
  }
  interface Array<T> {
    last(): T | undefined; // 프로토타입 확장의 타입 선언
  }
}

// 런타임 구현은 별도로 필요 (타입만으로는 동작하지 않음)
Array.prototype.last = function () {
  return this[this.length - 1];
};
```

타입 선언과 런타임 구현은 별개다. `declare global` 은 컴파일러에게 "이 멤버가 존재한다" 고 알릴 뿐, 실제 `Array.prototype.last` 를 정의하지 않으면 런타임에 `undefined` 다. 내장 프로토타입 확장은 다른 라이브러리와 충돌하거나 `for...in` 순회를 오염시킬 수 있어 애플리케이션 코드에서는 신중해야 한다.

## 7. 흔한 실패 진단

모듈 보강이 "적용 안 됨" 으로 끝나는 원인은 대개 셋이다. 첫째, **보강 대상 모듈명이 틀림**. express 는 `Request` 를 `express` 가 아니라 `express-serve-static-core` 에서 export 하므로 후자를 보강해야 한다. 패키지의 `@types` 구조를 열어 실제 선언 위치를 확인해야 한다. 둘째, **파일이 모듈로 인식되지 않음**. `import`/`export` 가 없으면 스크립트로 취급되어 보강이 전역 덮어쓰기가 된다. `export {}` 로 강제 모듈화한다. 셋째, **tsconfig 의 포함 범위 밖**. 보강 파일(`*.d.ts` 또는 일반 `.ts`)이 `include`/`files` 에 잡혀야 컴파일러가 읽는다. `typeRoots`/`types` 설정이 좁으면 누락된다.

```jsonc
// tsconfig.json — 보강 파일이 컴파일 그래프에 포함되도록
{
  "include": ["src/**/*.ts", "src/types/**/*.d.ts"]
}
```

## 8. 값 공간과 타입 공간의 분리

병합 규칙을 정확히 다루려면 TypeScript 의 두 이름 공간을 이해해야 한다. 같은 식별자가 타입 공간과 값 공간에 동시에 존재할 수 있고, 선언 종류마다 어느 공간에 기여하는지가 다르다.

```typescript
interface Point { x: number; y: number; } // 타입 공간에만
const Point = 42;                          // 값 공간에만 → 충돌 없음

type T = Point;        // 타입 공간 참조 → 인터페이스
const v = Point;       // 값 공간 참조 → 42
```

클래스는 양쪽 모두에 기여한다. 클래스 이름은 타입(인스턴스 타입)이자 값(생성자 함수)이다. 그래서 클래스에 네임스페이스를 병합하면 값 공간의 생성자에 정적 멤버가, 타입 공간에 중첩 타입이 추가된다. enum 도 양쪽에 기여한다. 이 이중성을 이해하면 "왜 인터페이스와 const 는 같은 이름이 가능한데 두 const 는 안 되는가" 가 자연스럽게 풀린다. 같은 공간에서의 중복만 충돌이고 다른 공간이면 공존한다.

## 9. 제네릭 보강과 플러그인 아키텍처

현대 프레임워크는 모듈 보강을 **공식 확장 지점** 으로 설계한다. 사용자가 라이브러리의 특정 인터페이스를 보강하면, 라이브러리 내부 타입이 그 보강을 반영하도록 만든다. Vue 의 컴포넌트 속성, Fastify 의 요청 데코레이터, Redux 의 상태 타입 등이 대표적이다.

```typescript
// Vue 3: 전역 속성을 컴포넌트 인스턴스 타입에 보강
import { App } from "vue";

declare module "@vue/runtime-core" {
  interface ComponentCustomProperties {
    $http: HttpClient;       // this.$http 가 모든 컴포넌트에서 타입 인식
    $translate: (key: string) => string;
  }
}

// Fastify: 요청 객체에 인증 컨텍스트 보강
declare module "fastify" {
  interface FastifyRequest {
    currentUser: AuthenticatedUser;
  }
  interface FastifyInstance {
    authenticate: (req: FastifyRequest) => Promise<void>;
  }
}
```

이 패턴이 가능한 이유는 라이브러리가 자기 타입을 인터페이스로 **개방** 해 두었기 때문이다. 만약 라이브러리가 `type` 별칭이나 클래스로 닫아 두었다면 보강할 수 없다. 그래서 확장 가능성을 의도하는 라이브러리는 핵심 타입을 의도적으로 `interface` 로 노출한다. 라이브러리 설계 시 "이 타입을 사용자가 확장할 수 있어야 하는가" 를 판단해 interface 와 type 을 선택하는 것이 API 설계의 한 축이다.

## 10. 보강의 스코프와 충돌 관리

모듈 보강은 적용 범위가 프로젝트 전역이라는 점이 양날의 검이다. 한 `.d.ts` 파일에서 `Request` 를 보강하면 그 모듈을 쓰는 모든 코드가 영향을 받는다. 여러 라이브러리나 여러 보강 파일이 같은 인터페이스를 보강하면 병합되지만, 같은 멤버를 다른 타입으로 선언하면 충돌한다. 모노레포에서 여러 패키지가 같은 외부 타입을 보강하면 예측하기 어려운 충돌이 생긴다.

```typescript
// 안전한 보강: 옥셔널로 두어 "주입 안 됐을 수도 있음" 을 타입에 반영
declare module "express-serve-static-core" {
  interface Request {
    requestId?: string; // 미들웨어가 항상 채운다는 보장은 코드 책임
  }
}
```

운영 원칙은 세 가지다. 첫째, 보강은 **실제 런타임 계약과 1:1** 로 묶는다. 타입에 `user` 를 추가했으면 그것을 주입하는 미들웨어가 반드시 존재하고 함께 관리돼야 한다. 둘째, 보강 파일을 한곳에 모아(`src/types/` 등) 어떤 전역 확장이 있는지 추적 가능하게 한다. 셋째, 가능하면 전역·내장 프로토타입 보강을 피하고 명시적 유틸 함수나 래퍼 타입으로 대체한다.

## 11. 설계 관점의 트레이드오프

선언 병합은 강력하지만 전역 부수효과를 만든다. 한 곳에서 `Request` 에 `user` 를 추가하면 프로젝트 전체에서 `req.user` 가 보이고, 그것이 실제로 주입됐는지는 타입이 보장하지 않는다(옥셔널로 두는 이유). 따라서 보강은 "실제 런타임 계약을 타입으로 반영" 하는 용도로만 쓰고, 주입을 책임지는 미들웨어와 1:1로 묶어 관리하는 것이 안전하다. 라이브러리 저자라면 보강을 전제로 한 플러그인 시스템(예: Fastify 의 `FastifyRequest` 보강, Vue 의 `ComponentCustomProperties`)을 의도적으로 열어두기도 한다. 반대로 애플리케이션 코드에서 내장 프로토타입을 보강하는 것은 협업·라이브러리 충돌 위험이 커 유틸 함수로 대체하는 편이 낫다. 요컨대 선언 병합은 "닫힌 타입을 안전하게 여는 공식 통로" 이며, 그 개방성이 곧 책임이라는 점을 잊지 않는 것이 장기 유지보수의 핵심이다.

## 참고

- TypeScript Handbook — Declaration Merging
- TypeScript Handbook — Modules / Module Augmentation, Global Augmentation
- DefinitelyTyped 기여 가이드 — 보강 패턴과 `*.d.ts` 작성
- TypeScript Deep Dive — Declaration Merging 챕터
