Notion 원본: https://www.notion.so/3665a06fd6d3811b9502d4a85ecb2fc9

# TypeScript Variadic Tuple Types — Spreads, Tuple Manipulation으로 Function Composition 타입 추론

> 2026-05-20 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript 4.0 도입의 *variadic tuple types*가 어떤 한계를 해결했는지 식별한다
- spread element의 위치(앞·중간·뒤·다중 가변)에 따라 추론 알고리즘이 어떻게 동작하는지 분석한다
- Function composition·partial application·curry 같은 패턴을 type-level에서 정확히 추론하는 정의를 작성한다
- *type instantiation depth*와 *tail-recursion* 문제를 회피하는 작성 전략을 비교한다

## 1. Variadic Tuple Types란

```ts
type T1 = [string, ...number[], boolean];                  // 중간 가변
type T2<A extends any[], B extends any[]> = [...A, ...B];  // generic spread
type T3 = readonly [string, ...string[], number];          // readonly + 중간 가변
```

4.0에서 *variadic tuple types*가 도입되어 *임의 위치의 spread*와 *generic spread*가 허용되었다.

## 2. Spread element의 추론 동작

```ts
type Parameters<T extends (...a: any[]) => any> =
  T extends (...a: infer P) => any ? P : never;

type Concat<A extends any[], B extends any[]> = [...A, ...B];
type C1 = Concat<[1, 2], [3, 4]>;  // [1, 2, 3, 4]

type Tail<T extends any[]> = T extends [any, ...infer R] ? R : never;
type Init<T extends any[]> = T extends [...infer R, any] ? R : never;
```

`Init`(마지막 제외)이 가능해진 게 결정적이다.

## 3. Function Composition

```ts
type Compose<Fns extends readonly any[]> =
  Fns extends readonly [(...a: infer A) => infer R]
    ? (...a: A) => R
    : Fns extends readonly [(...a: infer A) => infer R, ...infer Rest]
      ? Rest extends readonly [(a: R, ...rest: any[]) => any, ...any[]]
        ? Compose<Rest> extends (...a: any[]) => infer Last
          ? (...a: A) => Last
          : never
        : never
      : never;
```

lodash·Ramda는 9~12개 오버로드로 작성해 왔던 정확한 시그니처를 variadic tuple로 한 정의로 가능해졌다.

## 4. Curry

```ts
type Curry<F extends (...args: any[]) => any> =
  F extends (...args: infer A) => infer R
    ? A extends []
      ? () => R
      : A extends [infer Head, ...infer Tail]
        ? (h: Head) => Curry<(...args: Tail) => R>
        : never
    : never;

declare function curry<F extends (...args: any[]) => any>(f: F): Curry<F>;

const add3 = curry((a: number, b: number, c: number) => a + b + c);
const x = add3(1)(2)(3);  // x: number
```

## 5. Tail-Recursive 패턴

```ts
// 비-tail-recursive: ~50 depth 한계
type Reverse_NT<T extends any[]> =
  T extends [infer H, ...infer R] ? [...Reverse_NT<R>, H] : [];

// Tail-recursive: 1000 depth 허용
type Reverse_T<T extends any[], Acc extends any[] = []> =
  T extends [infer H, ...infer R] ? Reverse_T<R, [H, ...Acc]> : Acc;
```

accumulator parameter의 차이다. 일반적인 list 처리 패턴을 tail-recursive로 작성하면 길이 100~500의 tuple도 안정적으로 처리한다.

## 6. Express Router 타입 추론

```ts
type ExtractParams<S extends string> =
  S extends `${string}:${infer Param}/${infer Rest}`
    ? [Param, ...ExtractParams<Rest>]
    : S extends `${string}:${infer Param}`
      ? [Param]
      : [];

type ParamObject<S extends string> = {
  [K in ExtractParams<S>[number]]: string;
};

declare function get<P extends string>(
  path: P,
  handler: (req: { params: ParamObject<P> }) => void
): void;

get("/users/:id/posts/:postId", (req) => {
  req.params.id;       // string
  req.params.postId;   // string
});
```

tRPC, Hono, TanStack Router의 type-safe routing의 토대다.

## 7. const Type Parameter

```ts
function zip<const A extends readonly any[], const B extends readonly any[]>(
  a: A, b: B
): { [K in keyof A]: K extends keyof B ? [A[K], B[K]] : never } {
  return a.map((v, i) => [v, b[i]]) as any;
}

const z = zip([1, 2, 3] as const, ["a", "b", "c"] as const);
// z: [[1, "a"], [2, "b"], [3, "c"]]
```

## 8. 성능 — 컴파일 시간

| 작업 | 안전 길이 | 위험 길이 | 메모 |
|---|---|---|---|
| Tuple length 측정 | ~500 | 1000+ | 거의 무료 |
| Reverse (tail-rec) | ~800 | 1000+ | 매우 가벼움 |
| Filter conditional | ~200 | 400+ | union 폭발 가능성 |
| Map with conditional | ~150 | 300+ | conditional이 무거우면 더 짧음 |
| Cartesian Product | ~10×10 | 20×20+ | n*m 폭발 |

## 9. 패턴 선택 가이드와 type-level 테스트

| 상황 | 권장 |
|---|---|
| 함수 인수 변환 | variadic tuple + tail-rec |
| Path/URL parsing | variadic tuple + template literal |
| 길이 1000+ tuple 처리 | runtime으로 이동 |
| Generic builder pattern | variadic tuple, const parameter |
| API response 정형화 | Zod·io-ts 같은 runtime schema |
| Cartesian product, permutation | type-level 비권장 |

```ts
import { expectTypeOf } from "expect-type";

type Curried = Curry<(a: string, b: number, c: boolean) => Date>;
expectTypeOf<Curried>().toEqualTypeOf<
  (a: string) => (b: number) => (c: boolean) => Date
>();
```

`expect-type`은 런타임 코드가 아니다. `tsc --noEmit`이 통과하면 type 검증이 끝난 것이다. CI에 type check 단계를 두면 type-level regression이 PR에서 잡힌다.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- Anders Hejlsberg, "TypeScript: A Programming Language for Programmer Happiness" (TSConf 2020)
- type-fest GitHub 저장소
- "Type-Level TypeScript" by Anthony Fu — Tuple Manipulation Patterns
