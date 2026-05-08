Notion 원본: https://www.notion.so/35a5a06fd6d38124b47be1dde9b31f3b

# TypeScript Variadic Tuple Types와 가변 제네릭 함수 시그니처

> 2026-05-08 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Variadic tuple types(가변 튜플 타입)의 문법과 컴파일러가 제공하는 추론 규칙을 정확히 구분한다
- `[...A, ...B]` spread 위치에 따른 타입 동등성과 라벨드 튜플의 의도된 사용처를 이해한다
- `concat`, `pipe`, `curry`, `bind` 같은 가변 인자 함수의 타입을 안전하게 작성한다
- 튜플 리터럴 보존을 위해 `as const`, `const T extends ...`, contextual typing의 차이점을 활용한다

## 1. 가변 튜플의 등장 배경과 핵심 문법

TypeScript 4.0 이전까지 가변 인자 함수의 타입은 `(...args: any[])` 또는 오버로드 다중 선언으로만 표현 가능했다. 함수형 합성, 커링, `Function.prototype.bind` 처럼 인자 리스트의 앞·뒤에 다른 인자를 붙이는 패턴은 타입 시스템 안에서 표현할 수 없었다. 4.0에서 도입된 variadic tuple type은 튜플 안에 다른 튜플을 spread 한 위치를 그대로 유지하며 추론할 수 있게 만든다.

```ts
type Concat<T extends readonly unknown[], U extends readonly unknown[]> =
  [...T, ...U];

type R = Concat<[1, 2], [3, 4]>; // [1, 2, 3, 4]
```

여기서 `T`, `U`는 `unknown[]` 제약이 걸린 임의 길이 튜플이다. 컴파일러는 `[...T, ...U]`를 만나면 첫 번째 spread를 fixed prefix, 두 번째 spread를 fixed suffix 취급하다가 길이를 알 수 없는 부분이 등장하면 그 시점부터 rest 영역으로 흡수한다. 즉 spread는 **하나만 unknown-length 일 수 있고**, 나머지는 알려진 길이여야 한다.

## 2. 추론이 깨지는 케이스와 라벨드 튜플의 역할

```ts
declare function tail<T extends unknown[]>(arr: [unknown, ...T]): T;

const a = tail([1, 2, 3] as const); // readonly [2, 3]
const b = tail([1, 2, 3]);           // number[]  ← 좁혀지지 않음
```

`as const`가 없으면 입력이 `number[]`로 widening 되어 `T`가 `number[]`로만 잡힌다. 4.7부터 도입된 `const T extends` modifier를 사용하면 호출부에서 `as const` 없이도 좁힐 수 있다.

```ts
declare function tail<const T extends unknown[]>(arr: [unknown, ...T]): T;
const c = tail([1, 2, 3]); // [2, 3]
```

라벨드 튜플(`[head: number, ...rest: string[]]`)은 IDE 호버나 시그니처 도움말 외에는 타입 동등성에 영향을 주지 않는다.

## 3. 가변 인자 함수 시그니처의 실전 패턴

### 3.1 합성과 파이프

```ts
type Last<T extends readonly unknown[]> =
  T extends readonly [...unknown[], infer L] ? L : never;

declare function pipe<Fns extends readonly ((arg: any) => any)[]>(
  ...fns: Fns
): (a: Parameters<Fns[0]>[0]) => ReturnType<Last<Fns>>;
```

### 3.2 커링

```ts
type Curry<P extends readonly any[], R> =
  P extends readonly [infer H, ...infer T]
    ? T extends [] ? (h: H) => R : (h: H) => Curry<T, R>
    : R;
```

### 3.3 bind와 partial application

```ts
declare function bind<
  Bound extends readonly unknown[],
  Rest extends readonly unknown[],
  R,
>(fn: (...args: [...Bound, ...Rest]) => R, ...bound: Bound): (...rest: Rest) => R;
```

## 4. 튜플 리터럴 보존 — `const` modifier vs contextual typing

```ts
declare function f1<T extends unknown[]>(args: T): T;
declare function f2<const T extends unknown[]>(args: T): T;

const x = f1([1, "a"]);          // (string | number)[]
const y = f2([1, "a"]);          // [1, "a"]
```

## 5. 컴파일 성능과 깊이 제한

| 요인 | 비용 영향 | 회피 방법 |
|---|---|---|
| 재귀 깊이 50+ | Type instantiation is excessively deep | tail-recursion + accumulator |
| union 분해 후 재조합 | quadratic 폭발 | `[T] extends [U]` 로 distributive 차단 |
| `infer Rest extends T[]` 누락 | 매번 narrowing 재시도 | `extends infer R extends T[]` 캐싱 |

```ts
type Reverse<T extends readonly unknown[], Acc extends readonly unknown[] = []> =
  T extends readonly [infer H, ...infer R]
    ? Reverse<R, [H, ...Acc]>
    : Acc;
```

## 6. spread element와 rest parameter의 결합

```ts
function f(...args: [...string[], number]): void {}
f("a", "b", 1); // OK
f(1);           // OK
f("a");         // Error: number 자리 누락
```

## 7. 라이브러리에서의 활용

- **TanStack Query** — `useQueries([...] as const)` 형태에서 각 쿼리의 결과 타입을 위치별로 보존
- **tRPC** — router의 `mergeRouters(a, b, c)` 가 가변 튜플로 정의되어 모든 라우터의 procedure를 결합
- **fp-ts / Effect** — `pipe(value, ...fns)`가 가변 튜플로 구현
- **typed-fastify, Hono** — 라우트 path parameter를 template literal type과 가변 튜플로 결합

## 8. 디버깅과 검증

```ts
type Expect<T extends true> = T;
type Equal<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends
  (<T>() => T extends Y ? 1 : 2) ? true : false;

type _t1 = Expect<Equal<Concat<[1,2], [3,4]>, [1,2,3,4]>>;
```

`tsc --noEmit`만으로 단위 테스트가 가능하므로 type-level 검증의 표준 패턴이다.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types
- TypeScript 4.7 Release Notes — `const` Type Parameter Modifiers
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- type-challenges 저장소 — variadic tuple 관련 medium~hard 문제
- Microsoft/TypeScript pull request #39094 — Variadic Tuple Types 도입 PR
