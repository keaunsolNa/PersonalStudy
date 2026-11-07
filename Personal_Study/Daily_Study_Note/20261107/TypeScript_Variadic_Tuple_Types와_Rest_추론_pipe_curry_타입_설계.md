Notion 원본: https://www.notion.so/4115a06fd6d3812bacdcf2777bf61dee

# TypeScript Variadic Tuple Types와 Rest 추론: pipe·curry 타입 설계

> 2026-11-07 신규 주제 · 확장 대상: Javascript / TypeScript 타입 시스템

## 학습 목표

- 가변 인자 튜플(`[...T, ...U]`)의 추론 규칙과 라벨드 튜플을 다룬다
- `Parameters` / `ReturnType` 과 재귀 조건부 타입으로 `pipe` 의 연쇄 타입 검증을 구현한다
- 부분 적용(`curry`)을 튜플 분해로 타입화하고 재귀 깊이 한계를 측정한다
- `const T extends readonly unknown[]` 로 리터럴 튜플 추론을 보존한다
- 오버로드 방식과 가변 튜플 방식의 컴파일 비용·에러 메시지 품질을 비교한다

## 1. 튜플 타입의 확장: TS 4.0 Variadic

TS 4.0 이전 튜플은 길이가 고정된 정적 타입이었고, 두 튜플을 합치는 `concat` 같은 함수는 오버로드를 여러 개 써야 했다. 4.0 부터 튜플 안에서 제네릭 튜플을 spread 할 수 있게 되었다.

```ts
type Concat<A extends readonly unknown[], B extends readonly unknown[]> = [...A, ...B];

type X = Concat<[1, 2], [string]>;          // [1, 2, string]
type Y = Concat<[number, ...string[]], [boolean]>; // [number, ...string[], boolean]  (TS 4.2+: 중간 rest 허용)

function concat<A extends readonly unknown[], B extends readonly unknown[]>(a: A, b: B): [...A, ...B] {
  return [...a, ...b];
}
const r = concat([1, "a"] as const, [true] as const); // readonly 가 [...A] 에서 벗겨져 [1, "a", true]
```

핵심 규칙은 두 가지다. 하나, `[...T]` 에서 T 가 제네릭이면 T 가 확정될 때까지 튜플 평가가 지연되고, 확정되면 평탄화(flatten)된다. 둘, 중간 rest 는 TS 4.2 부터 허용되지만 rest 요소는 튜플에 하나만 올 수 있다(`[...A[], ...B[]]` 불가). 배열 spread 와 달리 튜플 spread 는 요소별 `readonly` 가 아니라 튜플 전체 `readonly` 이므로 결과 튜플은 mutable 이 된다는 점도 알아둔다.

## 2. Rest 파라미터 추론과 Parameters

함수의 파라미터 목록은 튜플이다. `Parameters<F>` 는 `F extends (...args: infer P) => any ? P : never` 이며, 선택적/라벨드 요소가 보존된다.

```ts
declare function connect(host: string, port?: number, ...opts: string[]): void;
type P = Parameters<typeof connect>; // [host: string, port?: number, ...opts: string[]]

// 첫 인자를 제거한 시그니처 — bind 류
type Tail<T extends readonly unknown[]> = T extends readonly [unknown, ...infer R] ? R : [];
type Head<T extends readonly unknown[]> = T extends readonly [infer H, ...unknown[]] ? H : never;

function partial1<A, R extends unknown[], Ret>(fn: (a: A, ...rest: R) => Ret, a: A) {
  return (...rest: R): Ret => fn(a, ...rest);
}
const addUrl = partial1((base: string, path: string, q?: number) => base + path, "https://x");
// (path: string, q?: number) => string  — 라벨과 optional 유지
```

`infer R` 가 rest 위치에 오면 라벨과 optional 정보가 그대로 `R` 에 담긴다. 이 성질 덕분에 에디터의 파라미터 힌트(`path: string`)가 유지된다. 단, `Parameters<F>` 를 거쳐 `T[number]` 로 일반화하면 라벨이 사라진다.

## 3. pipe 의 타입: 연쇄 검증

`pipe(f1, f2, f3)` 는 `f1` 의 반환이 `f2` 의 인자와 호환되어야 한다. 오버로드 방식은 라이브러리마다 최대 10~20개 오버로드를 쓰지만, 가변 튜플 + 재귀 조건부 타입으로 일반화할 수 있다.

```ts
type Fn = (arg: any) => any;

// fns 튜플이 연쇄 가능한지 검증하고, 불가능하면 위반 위치에 에러 타입을 넣는다
type ValidatePipe<F extends readonly Fn[], In = Parameters<F[0]>[0]> =
  F extends readonly [infer H extends Fn, ...infer T extends readonly Fn[]]
    ? [In] extends [Parameters<H>[0]]
      ? [H, ...ValidatePipe<T, ReturnType<H>>]
      : [(arg: In) => ReturnType<H>]            // 불일치: 기대 인자 타입을 노출
    : [];

type PipeResult<F extends readonly Fn[]> =
  F extends readonly [...Fn[], infer L extends Fn] ? ReturnType<L> : never;

declare function pipe<const F extends readonly [Fn, ...Fn[]]>(
  ...fns: F & ValidatePipe<F>
): (arg: Parameters<F[0]>[0]) => PipeResult<F>;

const toLen = pipe(
  (s: string) => s.trim(),
  (s: string) => s.length,
  (n: number) => n * 2,
);
// (arg: string) => number
// pipe((s: string) => s.length, (s: string) => s)  // 에러: number 는 string 에 할당 불가
```

`F & ValidatePipe<F>` 교차 트릭은 추론은 `F` 로 하면서 검증 결과를 제약으로 되돌린다. 불일치 지점에서 `(arg: In) => ...` 형태의 "기대 시그니처"를 넣으면 에러 메시지에 기대 타입이 그대로 나타나서 디버깅이 쉽다. 컨텍스트 타이핑(람다 파라미터 암묵 추론)이 필요하면 위 예처럼 첫 함수만 파라미터를 명시하고 나머지는 이전 반환값에서 유도하는 별도 설계(`pipe(value, f1, f2)` 형태로 값을 먼저 받기)가 훨씬 단순하다. 값 우선 시그니처는 `In` 을 첫 인자에서 확정하므로 각 람다의 파라미터를 추론시킬 수 있다.

## 4. curry: 튜플 분해로 부분 적용 타입화

```ts
type Curried<A extends readonly unknown[], R> =
  A extends readonly [infer H, ...infer T]
    ? <const X extends readonly [H, ...Partial<T>]>(...args: X) =>
        // 받은 개수만큼 소비한 나머지로 재귀
        Curried<Drop<A, X["length"]>, R>
    : R;

type Drop<A extends readonly unknown[], N extends number, Acc extends unknown[] = []> =
  Acc["length"] extends N ? A
  : A extends readonly [unknown, ...infer T] ? Drop<T, N, [...Acc, unknown]> : [];

declare function curry<A extends readonly unknown[], R>(fn: (...a: A) => R): Curried<A, R>;

const add3 = curry((a: number, b: number, c: number) => a + b + c);
const r1 = add3(1)(2)(3);   // number
const r2 = add3(1, 2)(3);   // number
const r3 = add3(1, 2, 3);   // number
```

`Drop` 은 길이 카운터 `Acc` 를 늘려가며 `N` 개를 제거하는 재귀다. 파라미터가 10개 이하인 일반 함수에서는 문제가 없지만, 인스턴스화 깊이는 기본 한도 약 50(일반 재귀) / 꼬리 재귀 최적화 시 1000 으로 제한되고 위반 시 `TS2589: Type instantiation is excessively deep` 가 난다. 꼬리 재귀 최적화는 조건부 타입의 분기 결과가 곧바로 자기 자신 호출일 때만 적용되므로, 위 `Drop` 처럼 누산기를 인자로 넘기는 형태로 작성해야 한다.

## 5. const 타입 파라미터와 리터럴 튜플 보존

TS 5.0 의 `const T` 는 `as const` 없이도 리터럴 추론을 요청한다. 단 `readonly` 튜플로 추론되므로 제약이 `readonly unknown[]` 여야 한다(mutable `unknown[]` 제약이면 추론이 실패하고 일반 배열로 후퇴한다).

```ts
declare function tuple<const T extends readonly unknown[]>(...xs: T): T;
const t = tuple("a", 1, true);  // readonly ["a", 1, true]

declare function tupleBad<const T extends unknown[]>(...xs: T): T;
const u = tupleBad("a", 1);     // (string | number)[] — readonly 튜플이 제약에 맞지 않아 후퇴
```

rest 인자(`...xs: T`)는 호출 구문 자체가 튜플을 만드므로 `const` 없이도 `["a", 1, true]` 형태로 추론되지만 리터럴 폭은 `[string, number, boolean]` 로 넓어진다. 이름·키처럼 리터럴이 필요한 곳에서 `const` 가 차이를 만든다.

## 6. 오버로드 vs 가변 튜플: 비용과 메시지

| 항목 | 오버로드 (N개 나열) | 가변 튜플 + 재귀 |
|---|---|---|
| 지원 인자 수 | 정해진 N | 이론상 무제한(깊이 한계 내) |
| 컴파일 비용 | 후보 순회, 대체로 선형 | 재귀 인스턴스화, 캐시 적중 시 저렴 |
| 에러 메시지 | "No overload matches this call" + 후보 나열 | 위반 위치의 기대 타입 노출 가능 |
| 람다 파라미터 추론 | 위치별 컨텍스트 타이핑 양호 | 설계에 따라 제한적 |
| 유지보수 | 반복 코드 | 타입 로직 학습 비용 |

실무에서는 람다 파라미터 추론 품질이 오버로드가 우수한 경우가 많다. 그래서 fp-ts·RxJS 의 `pipe` 는 지금도 오버로드를 사용한다. 새로 만드는 내부 유틸이라면 값 우선 `pipe(value, ...fns)` 오버로드 8~10개를 두고 그 이상은 배열 형태 가변 튜플 시그니처로 폴백하는 하이브리드가 현실적이다.

## 7. 디버깅과 측정

`tsc --extendedDiagnostics` 의 `Instantiations` 와 `Check time` 으로 재귀 타입의 비용을 확인한다. 특정 호출이 느리면 `tsc --generateTrace trace` 로 `checkExpression` 이벤트를 열어 해당 줄의 `instantiateType` 소요를 본다. 타입 단위 테스트는 `expectTypeOf`(vitest) 또는 `tsd` 를 사용한다.

```ts
import { expectTypeOf, test } from "vitest";
test("pipe 타입", () => {
  const f = pipe((s: string) => s.length, (n: number) => n > 3);
  expectTypeOf(f).toEqualTypeOf<(arg: string) => boolean>();
});
```

## 8. 정리

가변 튜플은 "인자 목록을 값처럼 다루는" 도구다. 파라미터 보존(`infer P`), 분해(`[infer H, ...infer T]`), 결합(`[...A, ...B]`) 세 연산만 익히면 `bind`, `curry`, `pipe`, 이벤트 시그니처 매핑을 모두 표현할 수 있다. 한계는 재귀 깊이와 컨텍스트 타이핑이며, 라이브러리 공개 API 라면 오버로드 하이브리드가 안전하다.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types
- TypeScript 4.2 Release Notes — Leading/Middle Rest Elements
- TypeScript 5.0 Release Notes — const Type Parameters
- TypeScript PR #40002 (Variadic tuple types), #45711 (Tail-recursion elimination on conditional types)
