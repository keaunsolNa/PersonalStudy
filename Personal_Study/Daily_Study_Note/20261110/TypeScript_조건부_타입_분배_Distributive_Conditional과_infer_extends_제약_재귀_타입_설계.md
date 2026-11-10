Notion 원본: https://www.notion.so/4145a06fd6d381068315e870a56d1757

# TypeScript 조건부 타입 분배(Distributive Conditional)와 infer extends 제약: 재귀 타입 설계

> 2026-11-10 신규 주제 · 확장 대상: TypeScript (타입 레벨 프로그래밍)

## 학습 목표

- 조건부 타입이 유니언에 분배되는 조건과 `[T] extends [U]` 로 분배를 끄는 방법을 구분한다
- `never` 가 분배에서 만들어내는 함정을 `IsNever` 로 우회한다
- `infer X extends C` 로 추론 위치에서 제약과 리터럴 추론을 동시에 건다
- 재귀 조건부 타입(Flatten, DeepReadonly, UnionToIntersection)을 작성하고 깊이 한계를 파악한다

## 1. 분배 조건부 타입이란

`T extends U ? X : Y` 에서 검사 대상 `T` 가 **네이키드 타입 파라미터**(다른 타입으로 감싸지 않은 제네릭 파라미터)이면, `T` 에 유니언이 들어올 때 컴파일러는 유니언의 각 멤버에 대해 조건을 따로 평가하고 결과를 다시 유니언으로 합친다. 이를 분배(distribution)라 한다.

```ts
type ToArray<T> = T extends unknown ? T[] : never;
type R1 = ToArray<string | number>;   // string[] | number[]

type NoDist<T> = [T] extends [unknown] ? T[] : never;
type R2 = NoDist<string | number>;    // (string | number)[]
```

`R1` 은 `ToArray<string> | ToArray<number>` 로 풀리지만, `R2` 는 검사 대상이 `[T]` 라는 튜플이어서 네이키드가 아니므로 분배되지 않고 유니언 전체가 한 번에 평가된다. 분배 여부는 **정의 시점의 문법 형태**로 결정된다는 점이 핵심이다. 별칭을 거쳐도, 인스턴스화 시점에 유니언이 들어와도 형태가 바뀌지 않으면 같은 규칙이 적용된다.

## 2. 분배가 필요한 곳과 끄고 싶은 곳

분배는 유니언 멤버별 변환이 목적일 때 유용하다. 표준 라이브러리의 `Exclude<T, U>`(`T extends U ? never : T`)와 `Extract`, `NonNullable` 이 전부 분배에 의존한다. `Exclude<"a" | "b" | "c", "a">` 가 `"b" | "c"` 가 되는 이유는 각 멤버가 독립적으로 `never` 또는 자기 자신으로 평가되기 때문이다.

반대로 "유니언 전체가 U 의 부분 타입인가"를 묻고 싶다면 분배를 꺼야 한다. 다음 `Eq` 헬퍼는 테스트에서 자주 쓰는 정확한 타입 동일성 검사로, 제네릭 함수 시그니처의 동일성 비교 규칙을 이용해 분배 없이 두 타입이 완전히 같은지 판정한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2)
    ? true : false;

const t1: Eq<R1, string[] | number[]> = true;
const t2: Eq<R2, (string | number)[]> = true;
```

이런 컴파일 타임 단언을 `*.test-d.ts` 로 두고 CI 에서 `tsc --noEmit` 만 돌려도 타입 라이브러리의 회귀를 잡을 수 있다. 런타임 테스트가 필요 없는 영역이다.

## 3. never 의 함정과 IsNever

`never` 는 **빈 유니언**이다. 빈 유니언을 분배하면 평가할 멤버가 하나도 없으므로 결과도 빈 유니언, 즉 `never` 가 된다.

```ts
type IsNeverBad<T> = T extends never ? true : false;
type X = IsNeverBad<never>;          // never (true 가 아니다)

type IsNever<T> = [T] extends [never] ? true : false;
type Y = IsNever<never>;             // true
const t3: Eq<ToArray<never>, never> = true;
const t4: Eq<IsNever<never>, true> = true;
```

제네릭 유틸리티가 `never` 입력을 받을 가능성이 있다면(예: 키 필터링 결과가 비는 경우) 검사 대상을 `[T]` 로 감싸는 것이 안전하다. 다만 `any` 는 조건부 타입에서 양쪽 분기의 유니언으로 평가되는 별도 규칙이 있어, `IsAny` 가 필요하면 `0 extends 1 & T` 같은 별도 관용구를 쓴다.

## 4. infer 와 extends 제약 (TS 4.7+)

`infer` 는 조건부 타입의 `extends` 절 안에서 타입 변수를 새로 선언해 매칭된 부분을 캡처한다. TS 4.7 부터 `infer H extends string` 처럼 캡처 지점에 제약을 걸 수 있다. 제약을 만족하지 않으면 그 분기는 거짓이 된다.

```ts
type FirstString<T> = T extends [infer H extends string, ...unknown[]] ? H : never;
const t5: Eq<FirstString<["a", 1]>, "a"> = true;
const t6: Eq<FirstString<[1, "a"]>, never> = true;
```

제약 없이 `infer H` 만 쓰고 뒤에서 `H extends string ? H : never` 로 한 번 더 검사하는 구버전 패턴보다 간결하고, 템플릿 리터럴과 함께 쓸 때 `infer N extends number` 가 `"42"` 를 숫자 리터럴 `42` 로 파싱해 주는 효과도 있다. 한 가지 주의: 같은 `infer` 변수가 **반공변(함수 파라미터) 위치**에 여러 번 나오면 후보가 교차(intersection)로, **공변 위치**에 여러 번 나오면 유니언으로 추론된다. 이 성질을 이용한 것이 다음 절의 `UnionToIntersection` 이다.

## 5. 재귀 조건부 타입

```ts
type UnionToIntersection<U> =
  (U extends unknown ? (k: U) => void : never) extends (k: infer I) => void ? I : never;
const t7: Eq<UnionToIntersection<{ a: 1 } | { b: 2 }>, { a: 1 } & { b: 2 }> = true;

type Flatten<T> = T extends readonly (infer U)[] ? Flatten<U> : T;
const t8: Eq<Flatten<number[][][]>, number> = true;

type Last<T extends unknown[]> = T extends [...unknown[], infer L] ? L : never;
const t9: Eq<Last<[1, 2, 3]>, 3> = true;

type DeepReadonly<T> =
  T extends (...a: never[]) => unknown ? T
  : T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
  : T;
type DR = DeepReadonly<{ a: { b: number[] } }>;
const t11: Eq<DR, { readonly a: { readonly b: readonly number[] } }> = true;
```

`UnionToIntersection` 의 동작을 풀어 보면, 첫 번째 분배 조건부가 유니언 멤버마다 `(k: U) => void` 를 만들어 **함수 유니언**을 만들고, 이를 `(k: infer I) => void` 에 매칭하면 `I` 가 반공변 위치이므로 후보들이 교차로 합쳐진다. `DeepReadonly` 는 함수 타입을 객체로 오인해 매핑하지 않도록 함수 분기를 먼저 둔 점, 배열이 `readonly number[]` 로 변하는 점(homomorphic mapped type 이 배열을 보존하며 readonly 를 붙임)을 확인할 수 있다.

## 6. 재귀 깊이와 꼬리 재귀 최적화

TS 컴파일러는 타입 인스턴스화 깊이를 제한한다(인스턴스화 깊이 한계 약 100 계열 오류 TS2589, 꼬리 재귀가 아닌 조건부 타입은 대략 50 단계 안팎에서 막히는 경우가 흔하다). TS 4.5 부터 조건부 타입의 **꼬리 위치** 재귀는 최적화되어 최대 1000 단계까지 허용된다. 누산기 파라미터를 두고 결과를 끝에서 반환하는 형태로 바꾸면 된다.

```ts
type Reverse<T extends unknown[], Acc extends unknown[] = []> =
  T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;
type Rv = Reverse<[1, 2, 3]>;   // [3, 2, 1]
```

재귀가 꼬리 위치(분기의 결과가 곧바로 자기 자신 호출)여야 최적화가 적용되므로, `[H, ...Reverse<R>]` 처럼 호출 결과를 다시 감싸면 일반 재귀가 되어 깊이 한계에 걸린다.

## 7. 실무 trade-off

타입 레벨 프로그래밍은 API 의 사용성을 올리지만 **컴파일 시간**과 **에러 메시지 가독성**을 대가로 한다. 깊은 재귀·큰 유니언 분배(유니언 크기 곱이 폭발)는 `tsc --extendedDiagnostics` 의 Instantiations 수치로 확인한다. 라이브러리 공개 타입에는 재귀 깊이를 설계상 제한(예: 객체 깊이 5 단계까지만 경로 타입 생성)하고, 분배가 의도인지 주석으로 남기는 것이 유지보수에 유리하다.

## 8. 정리 체크

- 네이키드 `T extends` 는 분배, `[T] extends` 는 비분배
- `never` 검사는 반드시 `[T] extends [never]`
- `infer X extends C` 로 추론과 제약을 한 번에
- 재귀는 누산기를 둔 꼬리 재귀로 작성해 1000 단계 한계까지 활용

## 참고

- TypeScript Handbook: Conditional Types (Distributive Conditional Types)
- TypeScript 4.7 Release Notes: extends Constraints on infer Type Variables
- TypeScript 4.5 Release Notes: Tail-Recursion Elimination on Conditional Types
