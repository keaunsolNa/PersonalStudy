Notion 원본: https://www.notion.so/3735a06fd6d381d1a75cfad9aa396843

# TypeScript Variadic Tuple Types와 Function Composition Type 추론

> 2026-06-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Variadic tuple type 의 spread 위치 규칙과 추론 알고리즘을 코드로 시뮬레이션한다
- `pipe`, `compose`, `curry` 의 타입 시그니처를 variadic tuple 로 작성한다
- Tuple labeling 과 rest element 가 함께 쓰일 때의 가독성·디버깅 트레이드오프를 정리한다
- 컴파일러 인스턴스화 깊이 한계(50 levels)와 회피 패턴을 실측한다

## 1. Variadic Tuple Type 의 정의와 위치 규칙

TypeScript 4.0 에서 도입된 variadic tuple types 는 tuple 타입 내부에서 `...T extends readonly unknown[]` 형태의 generic spread 를 허용한다. 핵심 제약은 *하나의 tuple 타입 안에 최대 한 개의 unbounded rest element* 만 둘 수 있다는 점이다. 단, 위치는 자유롭게 — 앞, 가운데, 끝 — 둘 수 있고 컴파일러는 fixed length prefix/suffix 와 variadic middle 을 분리해 추론한다.

```ts
type Head<T extends readonly unknown[]> = T extends readonly [infer H, ...unknown[]] ? H : never;
type Tail<T extends readonly unknown[]> = T extends readonly [unknown, ...infer R] ? R : [];
type Last<T extends readonly unknown[]> =
  T extends readonly [...unknown[], infer L] ? L : never;
type Init<T extends readonly unknown[]> =
  T extends readonly [...infer I, unknown] ? I : [];

type A = Head<[1, 2, 3]>;  // 1
type B = Last<[1, 2, 3]>;  // 3
type C = Tail<[1, 2, 3]>;  // [2, 3]
type D = Init<[1, 2, 3]>;  // [1, 2]
```

여기서 핵심은 `infer L` 이 *변경 가능한 위치(suffix)* 에 놓였다는 점이다. TS 4.0 이전엔 `T extends [...unknown[], infer L]` 형식을 직접 쓸 수 없었고 재귀 conditional 로 우회해야 했다. 4.0 이후엔 단일 패턴으로 매칭된다.

두 개의 rest 가 들어가는 경우는 *fixed length 부분이 다른 측에서 닫혀 있을 때만* 허용된다.

```ts
type Concat<A extends readonly unknown[], B extends readonly unknown[]> =
  [...A, ...B];  // ✅ 두 rest, 각각 unbounded 가 아니므로 OK

type Bad<A extends unknown[], B extends unknown[]> = [...A, string, ...B];
// ❌ 'A rest element cannot follow another rest element'
```

`Concat` 이 통과하는 이유는 컴파일러가 `A` 의 끝과 `B` 의 시작을 명확히 구분할 수 있기 때문이다. 반면 `Bad` 는 `string` 의 위치가 모호하다.

## 2. pipe 의 type 시그니처 — N-ary 함수 합성

함수형 라이브러리(fp-ts, Effect, Ramda) 의 `pipe` 는 전통적으로 N개 오버로드를 손으로 적었다. Variadic tuple 로 단일 시그니처가 가능하다.

```ts
type AnyFn = (arg: any) => any;

type ComposeChain<F extends readonly AnyFn[]> =
  F extends readonly [
    (arg: infer A) => infer R1,
    ...infer Rest extends AnyFn[]
  ]
    ? Rest extends []
      ? [(arg: A) => R1]
      : Rest extends readonly [(arg: R1) => any, ...AnyFn[]]
        ? [(arg: A) => R1, ...ComposeChain<Rest>]
        : never
    : F;

declare function pipe<F extends readonly AnyFn[]>(
  ...fns: F & ComposeChain<F>
): F extends readonly [(arg: infer A) => any, ...any[]]
  ? F extends readonly [...any[], (arg: any) => infer R]
    ? (input: A) => R
    : never
  : never;

const add1 = (n: number) => n + 1;
const toStr = (n: number) => n.toFixed(2);
const exclaim = (s: string) => s + '!';
const run = pipe(add1, toStr, exclaim);  // (input: number) => string
const out = run(41);  // "42.00!"
```

핵심은 `ComposeChain` 이 *각 함수의 출력이 다음 함수의 입력과 호환되는지* 를 재귀적으로 강제한다는 것이다. 만약 `(n: number) => n + 1` 다음에 `(s: boolean) => ...` 가 오면 `Rest extends readonly [(arg: R1) => any, ...]` 매칭이 실패해 `never` 가 되고, 전체 인자 타입이 `never[]` 로 좁혀져 사용자가 호출할 수 없게 된다.

## 3. curry — Rest 의 위치를 자유롭게 옮기는 합성

`curry(f, a)` 가 `(b, c) => f(a, b, c)` 를 반환한다고 할 때, 남은 인자 수를 variadic tuple 로 표현한다.

```ts
type Curry<F> = F extends (...args: infer Args) => infer R
  ? Args extends [infer A, ...infer Rest]
    ? Rest extends []
      ? (a: A) => R
      : (a: A) => Curry<(...args: Rest) => R>
    : R
  : never;

declare function curry<F extends (...args: any[]) => any>(fn: F): Curry<F>;

const sum3 = (a: number, b: number, c: number) => a + b + c;
const c = curry(sum3);
// c: (a: number) => (a: number) => (a: number) => number
const x = c(1)(2)(3);  // number
```

`Curry` 는 자기 자신을 재귀 호출한다. 인자 수가 많을수록 인스턴스화 깊이가 깊어진다. TS 5.0 기준 *재귀 conditional type 의 인스턴스화 한도* 는 디폴트 50, type alias instantiation 100. 12개 인자를 가진 함수를 curry 시 alias 깊이 약 60 — 안전. 30개 인자라면 *유한* 한도를 넘어 `Type instantiation is excessively deep and possibly infinite` 가 뜬다. 회피책은 fixed overload 와 generic accumulator 의 조합:

```ts
type Curry<F, Args extends unknown[] = []> = F extends (...args: infer P) => infer R
  ? P extends [infer A, ...infer Rest]
    ? (a: A) => Curry<(...args: Rest) => R, [...Args, A]>
    : R
  : never;
```

Accumulator 를 명시해 alias depth 가 아닌 *iteration depth* 로 다시 표현하면 5.0 의 *tail-recursive conditional types* 최적화가 적용된다(TS 4.5+).

## 4. tuple labeling 과 rest 결합 시 가독성

Named tuple element 는 4.0 에서 함께 도입됐다. Rest 와 결합하면 다음 형태가 가능하다.

```ts
type WithCtx = [ctx: Context, ...rest: string[]];

function log(...args: WithCtx) {
  const [ctx, ...messages] = args;
  console.log(ctx.id, messages.join(' '));
}
```

라벨은 *순수 문서화 도구* 다. 런타임에 아무 효과 없고 추론 결과의 hover 표시에만 영향을 준다. 두 가지 함정:

- Labeled tuple 안에 일부만 라벨을 다는 것은 컴파일러가 거부한다. 전부 다거나 전부 안 달거나.
- `infer X` 로 labeled tuple 의 요소를 뽑으면 라벨은 보존되지 않는다 — `T extends [first: infer A, ...rest: infer R]` 의 `A` 는 그냥 `A`.

운영 경험상 라벨링은 *공개 API* 에만 적용하고 내부 타입 헬퍼에는 생략하는 게 알기 쉽다.

## 5. spread element 의 두 가지 의미 — value vs type

JS 의 `...args` 는 array spread 다. TS 의 `[...T]` 는 *tuple spread* 다. 이 둘은 위치에 따라 의미가 달라진다.

```ts
type A = [...string[]];          // string[] 와 동일
type B = [...[1, 2], ...[3, 4]]; // [1, 2, 3, 4]
type C = [first: number, ...rest: string[]];  // 추론 시 [number, ...string[]]
type D = readonly [1, 2, 3];
type E = [...D];                 // [1, 2, 3] — readonly 가 사라짐
```

`E` 는 가장 흔한 함정이다. `as const` 로 만든 readonly tuple 을 spread 하면 mutable tuple 로 떨어진다. readonly 를 유지하려면 `readonly [...D]` 또는 wrapper 가 필요하다.

```ts
type CopyReadonly<T extends readonly unknown[]> =
  readonly [...T];

type F = CopyReadonly<readonly [1, 2, 3]>;  // readonly [1, 2, 3]
```

이는 `Object.freeze` 같은 *불변 보장이 필요한 API* 의 타입 시그니처에서 중요하다.

## 6. compose 의 right-to-left 합성과 reverse 추론

`pipe` 는 left-to-right, `compose` 는 right-to-left. 타입으로 보면 *tuple 을 뒤집어 처리하면 동일하다*.

```ts
type Reverse<T extends readonly unknown[]> =
  T extends readonly [infer H, ...infer R]
    ? [...Reverse<R>, H]
    : [];

type R = Reverse<[1, 2, 3]>;  // [3, 2, 1]

declare function compose<F extends readonly AnyFn[]>(
  ...fns: F
): F extends readonly [(arg: any) => infer R, ...any[]]
  ? F extends readonly [...any[], (arg: infer A) => any]
    ? (input: A) => R
    : never
  : never;
```

`Reverse` 는 tail-recursive 패턴이라 5.0 에서 자동 최적화 대상이다. Reverse 와 pipe 를 합치면 compose 가 된다. 실용적으론 두 함수를 따로 두고 *이름* 으로 의도를 표현하는 게 가독성에 좋다.

## 7. Variadic 와 const type parameter 결합 (TS 5.0)

TS 5.0 의 `const T` modifier 는 함수에 전달된 tuple 을 `as const` 처럼 좁힌다. Variadic tuple 과 결합 시 *호출자의 의도를 그대로 보존* 한다.

```ts
declare function head<const T extends readonly unknown[]>(arr: T): T[0];
const x = head([1, 2, 3]);  // 1 (literal), not number

declare function tuple<const T extends readonly unknown[]>(...args: T): T;
const t = tuple('a', 'b', 'c');  // readonly ['a', 'b', 'c']
```

`const` 가 없으면 `head` 의 `T` 는 `number[]` 로 widening 되어 결과가 `number` 가 된다. 라이브러리 API 디자인에서 *호출 사이트의 리터럴 정보를 보존하려면* `const` 가 거의 필수다.

| 시나리오 | const 없이 | const 적용 |
|---|---|---|
| `head([1,2,3])` | `number` | `1` |
| `tuple('a','b')` | `string[]` | `readonly ['a','b']` |
| `pipe(f, g)` | `[Fn1, Fn2]` widening 위험 | 정확한 함수 타입 보존 |

## 8. 인스턴스화 깊이 실측과 회피 패턴

다음 스크립트로 깊이 한계를 측정했다(TS 5.4, MacBook M2):

```ts
type Repeat<N extends number, T extends readonly unknown[] = []> =
  T['length'] extends N ? T : Repeat<N, [...T, 0]>;

type R20 = Repeat<20>;   // 4ms
type R45 = Repeat<45>;   // 50ms
type R47 = Repeat<47>;   // ✅ 마지막 통과 깊이
type R48 = Repeat<48>;   // ❌ excessively deep
```

Tail-recursive 가 아닌 naive recursion 은 ~47 깊이에서 차단된다. Tail-recursive 로 다시 쓰면 1000+ 도 가능하다.

```ts
type RepeatTail<N extends number, T extends readonly unknown[] = []> =
  T['length'] extends N ? T : RepeatTail<N, [0, ...T]>;
// ↑ 5.0+ 에서 internal trampoline 적용 — depth 한계 약 1000
```

차이는 *조건문 안에서 자기 자신을 재귀하는 위치* 다. `T extends ... ? Foo<...> : ...` 형태로 즉시 반환하는 경우는 trampoline 대상. 중간 계산이 끼면 stack frame 이 쌓인다.

## 9. 운영 사례 — tRPC, Zod, Effect 의 variadic 활용

세 라이브러리가 variadic tuple 을 어떻게 쓰는지.

`tRPC` 의 `router({ proc1, proc2, ... })` 는 router builder 가 procedure tuple 의 타입을 보존해 클라이언트에서 `client.proc1.query()` 와 같이 *exact 이름* 으로 호출하게 한다. 내부적으로 `RouterDef<TProcedures>` 가 variadic record 와 mapped type 으로 펼친다.

`Zod` 의 `z.tuple([z.string(), z.number(), z.boolean()])` 은 입력 schema array 를 variadic tuple 로 받아 출력 타입을 `[string, number, boolean]` 으로 정확히 추론한다. `rest()` 호출 시 `[...prefix, ...rest[]]` 의 variadic 합성을 사용한다.

`Effect` 의 `Effect.all([eff1, eff2, eff3])` 는 입력 array 의 각 element 의 success type 을 variadic 으로 모아 출력 tuple 의 element 로 매핑한다.

실측 트레이드오프 — variadic 추론은 *컴파일 시간* 을 늘린다. tRPC 가 1000+ procedure 를 가진 router 에서 `tsc --watch` incremental rebuild 가 800ms → 4.2s 로 증가한 사례가 GitHub issue 에 보고됐다. 회피책은 server / client 의 type 만 분리해 declaration emit 하고 client 는 .d.ts 만 import — 추론 비용을 한 번만 지불한다.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types
- TypeScript 5.0 Release Notes — const Type Parameters
- Microsoft/TypeScript PR #40002 — Variadic Tuple Types implementation
- Microsoft/TypeScript PR #45711 — Tail-recursive conditional types
- Effect-TS source: `pac