Notion 원본: https://www.notion.so/35c5a06fd6d381b6b61ee2b270582b99

# TypeScript Conditional Types와 infer 활용 심화 - Distributive와 Tuple 추론

> 2026-05-10 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `T extends U ? X : Y` 형태의 조건부 타입이 *유니온* 을 만났을 때 자동으로 분배(distribute) 되는 메커니즘과 그것을 막는 표준 패턴을 익힌다
- `infer` 키워드를 사용해 함수 시그니처, 튜플, 가변 인자, Promise chain, 라이브러리 타입의 내부를 *위치별로* 추출하는 다섯 가지 패턴을 분류한다
- `infer X extends Constraint` (TS 4.7+) 와 `infer ... as const` 변형이 추론 결과 타입을 어떻게 좁혀 주는지 본다
- 분산형 조건부 타입의 *비용* — 컴파일 시간, 인스턴스화 깊이 한계 50, type instantiation count — 을 측정 가능한 형태로 다룬다

## 1. 조건부 타입의 평가 시점

`T extends U ? A : B` 는 평가 시점에 `T` 가 *naked type parameter* 이고 `T` 가 *유니온* 이면 각 멤버에 대해 분배된다.

```ts
type ToArray<T> = T extends any ? T[] : never;
type R1 = ToArray<string | number>;
//   ^? string[] | number[]
```

분배를 *원하지 않을 때* 의 표준 우회는 `[T] extends [U]` 처럼 양쪽을 한 칸짜리 튜플로 감싸는 것.

```ts
type ToArrayBoxed<T> = [T] extends [any] ? T[] : never;
type R2 = ToArrayBoxed<string | number>;
//   ^? (string | number)[]
```

표준 IsNever 구현: `type IsNever<T> = [T] extends [never] ? true : false;`

## 2. infer — 위치별 추출 다섯 패턴

### 2.1 함수 인자/반환 추출

```ts
type ReturnTypeX<T> = T extends (...args: any) => infer R ? R : never;
type ParametersX<T> = T extends (...args: infer P) => any ? P : never;
type FirstArg<T> = T extends (a: infer A, ...rest: any[]) => any ? A : never;
```

### 2.2 튜플 head / tail / last

```ts
type Head<T extends readonly any[]> = T extends [infer H, ...any[]] ? H : never;
type Tail<T extends readonly any[]> = T extends [any, ...infer R] ? R : [];
type Last<T extends readonly any[]> = T extends [...any[], infer L] ? L : never;
type Init<T extends readonly any[]> = T extends [...infer I, any] ? I : [];
```

### 2.3 문자열 패턴 추출 (template literal + infer)

```ts
type ParseDate<S extends string> =
  S extends `${infer Y}-${infer M}-${infer D}`
    ? { y: Y; m: M; d: D }
    : never;

type R = ParseDate<"2026-05-10">;
//   ^? { y: "2026"; m: "05"; d: "10" }
```

4.7+:

```ts
type ToNum<S extends string> = S extends `${infer N extends number}` ? N : never;
type Y = ToNum<"2026">; // 2026 (literal number)
```

### 2.4 Promise / 비동기 unwrapping

```ts
type Awaited2<T> = T extends Promise<infer U> ? Awaited2<U> : T;
```

### 2.5 라이브러리 내부 타입 추출

```ts
import type { UseQueryResult } from "@tanstack/react-query";

type DataOf<R> = R extends UseQueryResult<infer D, any> ? D : never;
type ErrorOf<R> = R extends UseQueryResult<any, infer E> ? E : never;
```

## 3. 분배 제어가 결정적인 케이스

### 3.1 NonNullable 의 정의

```ts
type NonNullable<T> = T extends null | undefined ? never : T;
```

### 3.2 Exclude / Extract

```ts
type Exclude<T, U> = T extends U ? never : T;
type Extract<T, U> = T extends U ? T : never;
```

### 3.3 분배가 *문제* 인 경우 — equality check

```ts
type Equal<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends
  (<T>() => T extends Y ? 1 : 2) ? true : false;
```

함수 타입 안에 들어가서 분배되지 않는다. 분배 제어의 *정해진* 패턴.

## 4. infer X extends Constraint (TS 4.7+)

```ts
type ToNum<S extends string> = S extends `${infer N extends number}` ? N : never;
type Year = ToNum<"2026">; // 2026
type Bad  = ToNum<"abc">;  // never
```

효과:
1. *결과 타입* 이 좁아진다.
2. *분기 결정* 이 빨라진다.

## 5. 분배 제어로 만들 수 있는 라이브러리 타입

| 유틸리티 | 정의 | 메모 |
| --- | --- | --- |
| `IsNever<T>` | `[T] extends [never] ? true : false` | naked T 면 분배가 빈 유니온이 되어 false 로만 평가됨 |
| `IsAny<T>` | `0 extends 1 & T ? true : false` | any 와의 교차는 any |
| `IsTuple<T>` | `T extends readonly any[] ? number extends T["length"] ? false : true : false` | length 가 numeric literal 이면 튜플 |
| `UnionToIntersection<U>` | `(U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never` | 함수 인자 위치는 contravariant → intersect |

## 6. 인스턴스화 비용과 한계

TS 컴파일러는 conditional type 의 *재귀 인스턴스화 깊이* 를 50 으로 제한 (ts(2589)).

회피 전략:

1. **꼬리 재귀** 로 변환:

```ts
type Reverse<T extends any[], Acc extends any[] = []> =
  T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;
```

2. **튜플 → 유니온** 으로 한 번 변환 후 처리.

성능 측정은 `tsc --extendedDiagnostics` 의 `Types: N`, `Instantiations: N`, `Check time: Xs` 메트릭.

## 7. 실전 예제 — Express 라우트 핸들러에서 path params 추출

```ts
type ExtractParam<P extends string> =
  P extends `:${infer Name}` ? Name : never;

type ExtractParams<Path extends string> =
  Path extends `${infer A}/${infer B}`
    ? ExtractParam<A> | ExtractParams<B>
    : ExtractParam<Path>;

type ParamsOf<Path extends string> = {
  [K in ExtractParams<Path>]: string;
};

type T = ParamsOf<"/users/:userId/posts/:postId">;
//   ^? { userId: string; postId: string }

declare function get<P extends string>(
  path: P,
  handler: (req: { params: ParamsOf<P> }) => void
): void;

get("/users/:userId/posts/:postId", (req) => {
  req.params.userId; // ok
});
```

## 8. trade-off 와 사용 가이드

- **에러 메시지가 깨지기 쉽다**. `Prettify<T> = { [K in keyof T]: T[K] } & {}` 같은 identity wrapper.
- **컴파일 시간** 이 비례해 늘어난다. `Instantiations` 메트릭으로 모니터링.
- **language service** (자동완성, hover) 에서 평가가 lazy.
- **소비자 코드의 가독성** 이 떨어질 수 있다. 외부 export 하는 타입은 단순 alias 한 겹으로 wrap.

규칙: *분배는 기본값이 아니라 명시적 결정* 이다. 새 conditional 타입을 쓸 때마다 "T 가 유니온이면 어떻게 동작해야 하는가" 를 문장으로 답하고, 답이 "분배되지 않아야 한다" 면 첫 자리부터 `[T]` 로 박싱하라.

## 참고

- TypeScript Handbook — Conditional Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.7 release notes — `infer ... extends`: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- type-challenges 저장소: https://github.com/type-challenges/type-challenges
- Microsoft TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- "Distributive Conditional Types" 원문 PR: https://github.com/microsoft/TypeScript/pull/21496
