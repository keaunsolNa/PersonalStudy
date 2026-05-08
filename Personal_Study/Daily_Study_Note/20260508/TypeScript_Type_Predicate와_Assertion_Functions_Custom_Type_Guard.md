Notion 원본: https://www.notion.so/35a5a06fd6d381c09076fe00b7da7e70

# TypeScript Type Predicate와 Assertion Functions Custom Type Guard

> 2026-05-08 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- type predicate(`x is T`)와 assertion function(`asserts x is T`)의 동작 차이를 컴파일러 control flow 관점에서 구분한다
- narrowing이 깨지는 사례(클로저, 비동기 경계, this binding)를 식별하고 해결책을 적용한다
- `satisfies`, discriminated union, branded type과 결합한 안전한 사용자 정의 가드를 작성한다
- 런타임 검증 라이브러리(Zod, io-ts) 가 type predicate를 자동 생성하는 메커니즘을 이해한다

## 1. type predicate 기본 동작과 control flow analysis

```ts
function isString(v: unknown): v is string {
  return typeof v === "string";
}

declare const x: string | number;
if (isString(x)) {
  x.toUpperCase(); // x: string
} else {
  x.toFixed(2);    // x: number
}
```

컴파일러의 control flow analyzer는 `if` 분기를 만나면 두 갈래에 각각 narrow된 타입을 흘려보낸다.

## 2. predicate 함수가 신뢰성을 잃는 패턴

```ts
function isUser(v: unknown): v is { id: string; name: string } {
  return typeof v === "object"; // ← 부족한 검증
}

const data: unknown = { id: 1 };
if (isUser(data)) {
  data.name.length; // 컴파일은 통과, 런타임은 TypeError
}
```

## 3. assertion function — `asserts x is T`

```ts
function assertString(v: unknown): asserts v is string {
  if (typeof v !== "string") throw new TypeError("not string");
}

declare const x: unknown;
assertString(x);
x.toUpperCase(); // x: string — assertion 이후 line부터 narrow
```

다음 조건을 반드시 지켜야 한다.

- 반환 타입을 명시적으로 `asserts ...` 형태로 표시(추론 불가)
- `void` 반환이며 실제로 throw 하지 않으면 narrow가 거짓이 됨
- arrow function으로는 작성할 수 없음

## 4. discriminated union 과 결합한 정밀 narrowing

```ts
type Result<T> =
  | { kind: "ok"; value: T }
  | { kind: "err"; error: Error };

function isOk<T>(r: Result<T>): r is { kind: "ok"; value: T } {
  return r.kind === "ok";
}
```

## 5. 클로저·비동기 경계에서 narrow가 풀리는 현상

```ts
function setup(state: { user: User | null }) {
  if (state.user !== null) {
    setTimeout(() => {
      state.user.name; // Error — 클로저 캡쳐 시점에 다시 풀림
    }, 0);
  }
}
```

해결책:

```ts
function setup(state: { user: User | null }) {
  const user = state.user;
  if (user !== null) {
    setTimeout(() => user.name, 0); // OK
  }
}
```

## 6. branded type을 결합한 자기 검증 가드

```ts
type Email = string & { readonly __brand: "Email" };

function isEmail(v: string): v is Email {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v);
}

function sendMail(to: Email, body: string) { /* ... */ }
```

핵심은 "검증되지 않은 string으로 sendMail을 호출할 수 없다"는 약한 보장이다.

## 7. 런타임 스키마 라이브러리의 자동 생성

| 기능 | 결과 |
|---|---|
| 런타임 검증 | input이 스키마와 맞지 않으면 throw 또는 Error 반환 |
| 타입 추론 | `z.infer<typeof schema>` 로 정확한 TS 타입 생성 |

```ts
import { z } from "zod";

const User = z.object({ id: z.string(), name: z.string() });
type User = z.infer<typeof User>;

function isUser(v: unknown): v is User {
  return User.safeParse(v).success;
}
```

## 8. 컴파일러 옵션과 strict 모드 영향

| 옵션 | 영향 |
|---|---|
| `strictNullChecks` | else 분기에서 null/undefined 보집합이 별도로 유지 |
| `noUncheckedIndexedAccess` | 배열 인덱싱 결과가 `T \| undefined` |
| `useUnknownInCatchVariables` | `catch (e)` 의 `e` 가 `unknown` 으로 시작 |

```ts
function isError(v: unknown): v is Error {
  return v instanceof Error;
}

try {
  doStuff();
} catch (e) {
  if (isError(e)) console.error(e.stack);
  else            console.error("unknown error", e);
}
```

## 참고

- TypeScript Handbook — Narrowing, Using Type Predicates
- TypeScript 3.7 Release Notes — Assertion Functions
- TypeScript 4.0 Release Notes — `useUnknownInCatchVariables`
- Effective TypeScript — Item 22
- Zod 공식 문서 — Schema, safeParse, infer
