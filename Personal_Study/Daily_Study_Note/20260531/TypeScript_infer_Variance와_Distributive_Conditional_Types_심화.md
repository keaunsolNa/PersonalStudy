Notion 원본: https://www.notion.so/3715a06fd6d381f995c0e8dba9f69c94

# TypeScript infer 키워드 위치별 동작과 Variance(공변/반공변) 심화

> 2026-05-31 신규 주제 · 확장 대상: TypeScript Conditional Types (20260528) / Mapped Types (20260529)

## 학습 목표

- `infer` 키워드가 conditional type 내부에서 위치(반환·인자·튜플 head/tail·재귀)에 따라 어떻게 다른 타입을 추출하는지 식별한다
- 함수 파라미터의 공변/반공변(variance) 규칙과 `strictFunctionTypes` 가 호출 안전성에 미치는 영향을 설명한다
- TS 4.7 에서 도입된 명시적 variance 어노테이션(`in`, `out`, `in out`)을 generic class/interface 에 적용해 추론 정확도를 끌어올린다
- 다중 `infer` 사이트에서 발생하는 union vs intersection 추론 규칙을 이해하고, naked vs wrapped tuple trick 으로 제어한다

## 1. infer 의 의미와 컴파일러 내부 위치 추론

`infer T` 는 conditional type 의 `extends` 절 안에서만 등장하며, 컴파일러가 해당 위치에 들어올 타입을 자유 변수 `T` 로 묶어 추출하는 역할을 한다. 이는 단순한 "꺼내기" 가 아니라, TypeScript 의 type relation 알고리즘이 양변을 매칭하며 미지수 위치를 추적하는 과정이다. `Awaited<T>` 가 재귀로 `Promise<infer U>` 를 풀어내는 동작이 대표적이다.

```ts
type ReturnTypeOf<F> = F extends (...args: any[]) => infer R ? R : never
type Sync = ReturnTypeOf<() => Promise<number>>  // Promise<number>

type DeepAwaited<T> = T extends Promise<infer U> ? DeepAwaited<U> : T
type X = DeepAwaited<Promise<Promise<string>>>   // string
```

같은 자리에 `infer` 가 두 번 이상 등장하면 컴파일러는 위치에 따라 결합 방식을 달리한다. 함수 파라미터처럼 입력(contravariant) 위치에 두 번 등장하면 **intersection**, 함수 반환(covariant) 위치에 두 번 등장하면 **union** 으로 합쳐진다.

```ts
type ParamUnion<F> = F extends { (a: infer A): void; (a: infer A): void } ? A : never
// (string => void) & (number => void) 의 A → string & number  (intersection)

type ReturnUnion<F> = F extends { (): infer R; (): infer R } ? R : never
// (() => string) | (() => number) 의 R → string | number   (union)
```

## 2. 튜플과 가변 인자에서의 infer

TS 4.0 의 variadic tuple types 이후 `infer` 는 튜플의 머리/꼬리/중간을 분리하는 핵심 도구가 됐다. 라우터의 path param 파싱, 함수 합성, curry 구현에 거의 모든 사례가 이 패턴 위에서 굴러간다.

```ts
type Head<T extends readonly any[]> =
  T extends readonly [infer H, ...any[]] ? H : never
type Tail<T extends readonly any[]> =
  T extends readonly [any, ...infer R] ? R : []
type Last<T extends readonly any[]> =
  T extends readonly [...any[], infer L] ? L : never
type Init<T extends readonly any[]> =
  T extends readonly [...infer I, any] ? I : []
```

`infer` 가 rest 위치에 있으면 가변 길이로 추론된다. 함수 시그니처에 적용하면 partial application 을 타입 레벨로 안전하게 표현할 수 있다.

```ts
type Curry<F> = F extends (a: infer A, ...rest: infer R) => infer Ret
  ? R extends []
    ? (a: A) => Ret
    : (a: A) => Curry<(...args: R) => Ret>
  : never
```

## 3. Variance — 공변·반공변·불변·이변

서브타이핑 관계가 generic 컨테이너에 어떻게 전파되는지를 결정하는 것이 variance 다.

| 분류 | 정의 | 직관 | 대표 예 |
|---|---|---|---|
| covariant (공변) | `Dog <: Animal` ⇒ `F<Dog> <: F<Animal>` | 출력 위치 | `ReadonlyArray<T>` 의 `T` |
| contravariant (반공변) | `Dog <: Animal` ⇒ `F<Animal> <: F<Dog>` | 입력 위치 (함수 인자) | `(x: T) => void` 의 `T` |
| invariant (불변) | 양방향 모두 서브타입 아님 | 입출력 모두 사용 | `Array<T>` 의 `T` (mutable) |
| bivariant (이변) | 양방향 모두 서브타입 | TS method 단축 표기 | `interface X { f(x: T): void }` |

`strictFunctionTypes: true` 가 켜져 있어야 함수 파라미터가 **올바르게 반공변** 으로 평가된다. 끄면 bivariant 가 되어 안전하지 않은 콜백 대입이 통과한다. 이 옵션은 함수 시그니처 문법(`f: (x: T) => void`)에만 적용되고 method shorthand(`f(x: T): void`)에는 적용되지 않는다.

```ts
class Animal {}
class Dog extends Animal { bark() {} }

let logAnimal: (a: Animal) => void = (a) => {}
let logDog:    (d: Dog)    => void = (d) => d.bark()

logAnimal = logDog  // error: 반공변 위반
logDog    = logAnimal  // OK: Animal 처리기는 Dog 도 안전
```

## 4. 명시적 variance 어노테이션 (TS 4.7+)

```ts
interface Producer<out T> { get(): T }
interface Consumer<in T>  { set(value: T): void }
interface Channel<in out T> { get(): T; set(v: T): void }

interface Bad<out T> { set(v: T): void }
// Variance annotation 'out' does not match
```

복잡한 mapped type 이 깊게 중첩된 generic 에서는 추론이 폭발적으로 비싸진다. variance 를 명시하면 컴파일러가 양방향 검사를 단방향으로 축약해 type checker 가 빨라진다.

## 5. Distributive Conditional Types

`T extends U ? X : Y` 형태에서 `T` 가 **naked type parameter** 이고 union 일 때만 분배된다.

```ts
type Naked<T>   = T extends any ? T[] : never         // 분배 O
type Wrapped<T> = [T] extends [any] ? T[] : never     // 분배 X

type A = Naked<string | number>    // string[] | number[]
type B = Wrapped<string | number>  // (string | number)[]
```

```ts
type UnionToIntersection<U> =
  (U extends any ? (x: U) => void : never) extends (x: infer I) => void
    ? I : never

type X = UnionToIntersection<{ a: 1 } | { b: 2 }>   // { a: 1 } & { b: 2 }
```

## 6. 실전 — Branded ID 의 variance 안전성

```ts
declare const __brand: unique symbol
type Brand<B> = { readonly [__brand]: B }
type UserId  = string & Brand<'UserId'>
type OrderId = string & Brand<'OrderId'>

interface Repo<in TId, out TEntity> {
  findById(id: TId): TEntity | null
}

type UserRepo  = Repo<UserId, { name: string }>
declare const ur: UserRepo

const broken: Repo<UserId | OrderId, unknown> = ur
// in 위치이므로 더 넓은 id 를 받는 repo 가 좁은 repo 의 서브타입 위반 — 컴파일러가 차단
```

## 7. 다중 infer 가 만드는 미묘한 함정

```ts
type EventMap = {
  click:  (e: MouseEvent) => void
  keydown:(e: KeyboardEvent) => void
}

type AnyEvent<M> = M extends { [K in keyof M]: (e: infer E) => void } ? E : never
type E = AnyEvent<EventMap>   // 의도 union, 결과 intersection → never
```

해결책 — mapped type 안에서 distribution 을 유발하는 형태로 분해.

```ts
type AnyEventFixed<M> = {
  [K in keyof M]: M[K] extends (e: infer E) => void ? E : never
}[keyof M]

type EOK = AnyEventFixed<EventMap>   // MouseEvent | KeyboardEvent
```

## 8. 운영 체크리스트와 trade-off

| 체크 항목 | 점검 방법 | 비고 |
|---|---|---|
| `strictFunctionTypes` | tsconfig | `strict: true` 가 묶어 켜 줌 |
| method shorthand 함정 | API 시그니처 검토 | 함수형 시그니처로 변경 |
| variance 어노테이션 | generic class/interface | 추론 성능 30% 향상 |
| distribution 의도 | naked vs wrapped | `[T] extends [U]` 로 감쌀지 결정 |
| 다중 infer 결합 | 위치별 covariant/contravariant 식별 | indexed-access 로 union 추출 |

성능 측면에서, `infer` 가 깊게 재귀하면 instantiation depth 한도(기본 100)에 닿아 `Type instantiation is excessively deep` 가 난다. 운영 코드에서는 type-level recursion 깊이를 50 이하로 유지하는 게 안전하다.

## 참고

- TypeScript Handbook: Conditional Types — https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.7 Release Notes — Optional Variance Annotations https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- Anders Hejlsberg, "TypeScript: Variance, Bivariance, and `strictFunctionTypes`" (microsoft/TypeScript issues #18654)
- Effect-TS Library — variance 어노테이션 적용 사례 https://effect.website
- Type Challenges — Conditional Types 섹션 https://github.com/type-challenges/type-challenges
