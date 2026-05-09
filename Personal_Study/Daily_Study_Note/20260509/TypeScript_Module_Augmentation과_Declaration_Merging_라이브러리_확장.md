Notion 원본: https://www.notion.so/35b5a06fd6d38192adecee898afd55f9

# TypeScript Module Augmentation과 Declaration Merging 라이브러리 확장

> 2026-05-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Declaration Merging 의 4가지 합법 결합 (interface ↔ interface, namespace ↔ namespace, namespace ↔ class, namespace ↔ function) 이 어떤 심볼 공간을 어떻게 합치는지 정확히 구분한다
- `declare module "..."` 로 외부 패키지의 타입을 안전하게 확장하는 module augmentation 패턴을 손에 익힌다
- Express `Request`, Fastify `FastifyRequest`, NestJS Decorator metadata 등 실무 라이브러리가 augmentation 을 강제하는 이유를 이해한다
- Global augmentation 과 module augmentation 의 적용 범위·로딩 순서·tsconfig include 영향을 추적해 디버깅 한다

## 1. Declaration Merging 의 기본 모델

TypeScript 의 심볼은 *값 공간(value space)* 과 *타입 공간(type space)* 이 분리된다. `interface`, `type alias`, `namespace`(타입 부분) 는 타입 공간에, `const`, `let`, `function`, `class`(인스턴스 측) 는 값 공간에 등록된다. Declaration Merging 은 *같은 이름의 다른 종류 선언* 을 컴파일러가 단일 심볼로 합쳐주는 규칙이다.

| 합칠 수 있는 조합 | 결과 |
|---|---|
| interface + interface | 멤버 합집합 (override 아님) |
| namespace + namespace | 내부 export 합집합 |
| namespace + class | class 인스턴스에 정적 멤버처럼 namespace 멤버 추가 |
| namespace + function | 함수에 정적 프로퍼티처럼 namespace 멤버 추가 |
| namespace + enum | enum 에 정적 멤버 확장 |

조합이 합법이려면 *같은 파일 내 또는 같은 모듈 스코프* 에 두 선언이 있어야 한다. 타입 alias(`type Foo = ...`) 는 합쳐지지 않는다. 동일 이름의 `type Foo` 가 두 번 등장하면 즉시 에러다.

## 2. interface 합치기 — 가장 흔한 패턴

```ts
interface User { id: string; }
interface User { name: string; }

const u: User = { id: "u1", name: "K" }; // OK
```

같은 시그니처를 두 번 적은 게 아니라 *서로 보충* 하는 방식이다. 멤버가 같은 이름이지만 시그니처가 다르면 *오버로드 합집합* 이 된다.

```ts
interface Logger { log(msg: string): void; }
interface Logger { log(msg: string, level: number): void; }
// 호출 시 두 시그니처 모두 매칭 가능
```

함수 시그니처 순서가 *오버로드 우선순위* 를 만든다는 점에 주의. 같은 인터페이스 내에서는 위에서 아래로 우선이지만, *나중에 합쳐진 인터페이스의 시그니처가 먼저 매칭되도록* 컴파일러가 정렬하지는 않는다. 예측 가능성을 원하면 가장 일반적인 시그니처를 마지막에 둔다.

## 3. Module Augmentation: 외부 패키지 타입 확장

외부 패키지가 export 한 타입에 *우리 코드 베이스만의 필드* 를 추가하고 싶을 때 사용한다. 대표 사례: Express 의 `Request` 객체에 인증 미들웨어가 붙인 `req.user` 를 타입화.

```ts
// src/types/express.d.ts
import "express"; // 기존 모듈을 임포트해야 augmentation 으로 인식됨

declare module "express-serve-static-core" {
  interface Request {
    user?: { id: string; roles: string[] };
  }
}
```

핵심 규칙:

1. **augmentation 을 적은 파일은 반드시 *모듈* 이어야 한다**. 즉 `import` 또는 `export` 가 적어도 하나 있어야 한다. 없으면 *글로벌 스크립트* 로 해석되어 augmentation 이 무시된다.
2. **augmentation 대상 모듈을 한 번 이상 import** 해야 컴파일러가 그 선언 공간을 안다.
3. `declare module "<exact-name>"` 의 이름은 패키지가 *실제로 export 하는 모듈명* 이어야 한다. Express 의 `Request` 는 `express-serve-static-core` 안에 있고 `express` 가 다시 export 한다. 보통 *원본 모듈명* 으로 augment 해야 안전하다.
4. tsconfig `include` 에 `.d.ts` 파일이 잡혀야 한다. `src/types/**/*.d.ts` 패턴을 명시하라.

## 4. Fastify 의 `FastifyRequest`/`FastifyInstance` 확장

Fastify 는 plugin 마다 인스턴스에 `decorateRequest`, `decorate`, `decorateReply` 를 붙이는 구조라 augmentation 이 거의 *필수* 다.

```ts
declare module "fastify" {
  interface FastifyRequest {
    requestId: string;
  }
  interface FastifyInstance {
    db: { query<T>(sql: string): Promise<T[]> };
  }
}
```

이렇게 해두면 `fastify.db.query<User>("SELECT ...")` 와 `req.requestId` 가 타입 자동완성 된다. 런타임 `decorate("db", ...)` 호출이 빠져 있으면 *런타임 에러* 가 나지만 타입체커는 통과한다 — 타입 안전성과 런타임 등록은 별개의 책임임을 명심.

## 5. NestJS·Reflect Metadata 와 결합

NestJS 의 `@SetMetadata`, `@Roles` 같은 데코레이터는 클래스/메서드에 메타데이터를 붙인다. Guard 에서 그 키를 읽어올 때, augment 로 *데코레이터 함수의 이름* 을 좁힐 수 있다.

```ts
declare module "@nestjs/common" {
  interface ExecutionContext {
    getRolesMeta(): string[] | undefined;
  }
}
```

런타임 prototype 패치와 augmentation 을 짝으로 한다. NestJS 자체는 `Reflector.get(ROLES_KEY, handler)` 를 권장하지만, 프로젝트 차원에서 helper 를 메서드처럼 쓰고 싶을 때 augmentation 이 선택지가 된다.

## 6. Global Augmentation

브라우저 `Window` 또는 Node `globalThis` 에 필드를 추가해야 할 때:

```ts
export {}; // 파일을 모듈로 만들기 위한 빈 export

declare global {
  interface Window {
    __APP_CONFIG__: { apiBase: string };
  }
}
```

`declare global` 은 *모듈 파일 안에서만* 의미가 있다. `export {}` 가 없으면 그 파일 자체가 글로벌 스크립트로 해석되어 `declare global` 이 무의미해진다. (전역 스크립트 안에서는 그냥 `interface Window` 만 적어도 합쳐진다.)

## 7. namespace + class/function 패턴

라이브러리 빌더 함수에 정적 helper 를 붙이는 데 자주 쓰인다.

```ts
function http(url: string) { /* ... */ }

namespace http {
  export function withTimeout(url: string, ms: number) { /* ... */ }
  export const DEFAULTS = { timeout: 5000 };
}

http("/x");
http.withTimeout("/x", 1000);
http.DEFAULTS.timeout;
```

런타임에서는 `http.withTimeout` 이 함수 객체의 프로퍼티가 된다. namespace 컴파일 결과가 동일 이름의 함수에 IIFE 로 멤버를 붙이는 식이라, 트리쉐이킹에 약간 불리할 수 있다. ESM-only 모노레포에서는 `Object.assign(http, { withTimeout, DEFAULTS })` 같은 패턴을 쓰고 타입만 namespace augmentation 으로 흉내내기도 한다.

## 8. 함정과 디버깅

### 8.1 augmentation 이 적용 안 됨

증상: `req.user` 타입이 여전히 `any` 또는 `undefined`.

체크리스트:

- 해당 `.d.ts` 가 tsconfig `files`/`include` 에 포함되어 있는가
- `import "express"` 같은 *부모 모듈 임포트* 가 들어 있는가
- 파일이 `export {}` 같은 모듈 마커를 갖고 있는가 (글로벌 스크립트로 인식되어 무시되는 경우)
- 모노레포 환경에서 *해당 패키지가 빌드된 결과* 가 `paths`/`baseUrl` 매핑과 일치하는가
- 다른 augmentation 이 *동일 인터페이스에 대해 다른 시그니처* 를 등록해 충돌하지 않는가

### 8.2 같은 필드를 두 라이브러리가 augment

`req.user` 를 우리 인증 라이브러리가 `{ id }` 로, 다른 미들웨어가 `{ email }` 로 augment 하면 *교집합* 이 아니라 *유니온/intersection* 이 일어난다. 두 augmentation 이 모두 import 되면 `Request.user` 는 `{ id: string } & { email: string }` 같은 형태가 된다. 의도된 거면 좋지만, *서로 다른 의미의 user* 라면 한쪽 augmentation 을 별도 타입으로 분리해야 한다.

### 8.3 ambient 모듈 정의와 충돌

`declare module "lodash" { ... }` 처럼 *기존에 정의 없는 패키지* 에 대한 ambient module 선언과 *augmentation* 의 차이를 혼동하지 말 것. `import "lodash"` 가 가능한 상태에서 `declare module "lodash"` 를 적으면 augmentation 으로 동작한다. 패키지가 `@types/lodash` 를 통해 이미 정의되어 있다면 우리는 *augment* 만 하는 것이고, 새로 정의하려고 하면 충돌이 나기 쉽다.

## 9. 실측: 컴파일 영향

augmentation 은 컴파일러가 *모듈 그래프 전체에서 같은 이름의 인터페이스를 모아* 머지하는 작업을 추가시킨다. 다수 패키지에 augment 가 산재한 모노레포에서는 type-check 시간이 비례적으로 증가한다. tsc `--extendedDiagnostics` 의 *Resolve module* / *Bind* 단계 시간이 의심 신호.

| augmentation 위치 | 권장 |
|---|---|
| 공통 라이브러리(Express, Fastify) | `src/types/*.d.ts` 1개로 모음 |
| 도메인별 모듈 | 도메인 폴더 내부 `*.d.ts` |
| Global Window/Node | 루트 `globals.d.ts` |
| 외부 패키지 마이너 보강 | 패키지별 디렉터리에 분리, 주석 명시 |

## 참고

- TypeScript Handbook — Declaration Merging
- TypeScript Handbook — Module Augmentation / Global Augmentation
- DefinitelyTyped CONTRIBUTING.md — augmentation 가이드
- Express @types/express 소스 — `express-serve-static-core` 패턴
- NestJS 공식 문서 — Custom Decorators / Reflector
- Fastify 공식 문서 — Type Providers / decorators
