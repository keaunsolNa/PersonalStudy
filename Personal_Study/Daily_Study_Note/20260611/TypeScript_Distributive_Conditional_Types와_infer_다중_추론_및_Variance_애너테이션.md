Notion 원본: https://www.notion.so/37c5a06fd6d381c6b881f1c37a4fd85f

# TypeScript Distributive Conditional Types와 infer 다중 추론 및 Variance 애너테이션

> 2026-06-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 조건부 타입이 naked type parameter + union에서 분배(distribute)되는 규칙과 분배를 끄는 방법을 구분해 사용한다
- `infer` 키워드의 위치별 추론 동작(공변 위치 union, 반공변 위치 intersection)을 예측한다
- `in` / `out` variance 애너테이션이 타입 호환성 검사와 컴파일 성능에 미치는 영향을 설명한다
- 분배 동작을 이용해 `Exclude`, `Extract` 류 유틸리티를 직접 구현하고 함정을 회피한다

## 1. 조건부 타입의 분배 규칙

조건부 타입 `T extends U ? X : Y`는 `T`가 "naked type parameter"이고 동시에 union 타입일 때 union의 각 멤버에 개별 적용된 뒤 다시 union으로 합쳐진다. 이것을 distributive conditional type이라고 한다.

```ts
type ToArray<T> = T extends any ? T[] : never;

// 분배: (string extends any ? string[] : never) | (number extends any ? number[] : never)
type R1 = ToArray<string | number>; // string[] | number[]
```

여기서 핵심은 "naked"라는 조건이다. `T`가 다른 타입으로 감싸여 있으면(`[T]`, `T & {}`, `Promise<T>` 등) 분배가 일어나지 않는다. 분배를 의도적으로 끄려면 양변을 튜플로 감싼다.

```ts
type ToArrayNonDist<T> = [T] extends [any] ? T[] : never;

type R2 = ToArrayNonDist<string | number>; // (string | number)[]
```

분배는 `never`에서 특히 주의해야 한다. `never`는 "빈 union"으로 취급되므로, 분배 조건부 타입에 `never`를 넣으면 멤버가 0개라 결과도 `never`가 된다.

```ts
type IsNever<T> = T extends never ? true : false;
type Wrong = IsNever<never>; // never (true 가 아님!)

// 올바른 구현: 분배를 끈다
type IsNeverCorrect<T> = [T] extends [never] ? true : false;
type Right = IsNeverCorrect<never>; // true
```

이 차이는 실무에서 "유효성 검사 타입"을 만들 때 버그의 단골 원인이다. `never` 처리가 필요한 모든 조건부 타입은 분배를 끄는 것이 안전하다.

## 2. Exclude / Extract의 내부 구현

표준 라이브러리의 `Exclude`, `Extract`는 분배 조건부 타입 그 자체다.

```ts
// lib.es5.d.ts 원형
type Exclude<T, U> = T extends U ? never : T;
type Extract<T, U> = T extends U ? T : never;

type E1 = Exclude<'a' | 'b' | 'c', 'a'>; // 'b' | 'c'
type E2 = Extract<'a' | 'b' | 'c', 'a' | 'x'>; // 'a'
```

`Exclude<'a'|'b'|'c', 'a'>`의 평가 과정은 다음과 같다. union의 각 멤버에 분배되어 `('a' extends 'a' ? never : 'a') | ('b' extends 'a' ? never : 'b') | ('c' extends 'a' ? never : 'c')`가 되고, 이는 `never | 'b' | 'c'`이며, `never`는 union에서 흡수되어 `'b' | 'c'`로 정리된다.

이 원리를 응용하면 객체에서 특정 값 타입을 가진 키만 골라내는 유틸리티를 만들 수 있다.

```ts
type KeysOfType<T, V> = {
  [K in keyof T]-?: T[K] extends V ? K : never;
}[keyof T];

interface User {
  id: number;
  name: string;
  age: number;
  active: boolean;
}

type StringKeys = KeysOfType<User, string>; // 'name'
type NumberKeys = KeysOfType<User, number>; // 'id' | 'age'
```

mapped type의 각 키를 "조건을 만족하면 K, 아니면 never"로 다시 매핑한 뒤, `[keyof T]`로 인덱싱해 값들을 union으로 추출하는 패턴이다. `never`는 union에서 사라지므로 조건을 만족하는 키만 남는다.

## 3. infer의 기본과 위치 의미

`infer`는 조건부 타입의 `extends` 절 안에서 타입 변수를 선언해 매칭된 부분을 캡처한다. 가장 흔한 예가 함수 반환 타입 추출이다.

```ts
type MyReturnType<T> = T extends (...args: any[]) => infer R ? R : never;

type R = MyReturnType<() => Promise<number>>; // Promise<number>
```

`infer`의 중요한 성질은 같은 이름의 추론 변수가 여러 위치에 등장할 때의 동작이다. **공변(covariant) 위치**에 여러 번 등장하면 추론 결과는 union이 되고, **반공변(contravariant) 위치**에 등장하면 intersection이 된다.

```ts
// 공변 위치(반환 타입, 배열 원소 등) → union
type Cov<T> = T extends { a: infer U; b: infer U } ? U : never;
type C = Cov<{ a: string; b: number }>; // string | number

// 반공변 위치(함수 파라미터) → intersection
type Contra<T> = T extends {
  a: (x: infer U) => void;
  b: (x: infer U) => void;
} ? U : never;
type D = Contra<{ a: (x: string) => void; b: (x: number) => void }>; // string & number => never
```

이 동작은 우연이 아니라 타입 안전성을 위한 것이다. 함수 파라미터는 반공변이므로 두 함수를 모두 만족하는 인자 타입은 둘의 intersection이어야 한다. `string & number`는 `never`가 되어, 실제로 두 함수를 동시에 안전하게 호출할 인자가 없음을 타입으로 드러낸다.

## 4. infer 다중 추론과 재귀

`infer`는 한 조건부 타입 안에서 여러 개를 동시에 선언할 수 있고, 재귀 조건부 타입과 결합하면 강력한 파싱을 구현한다.

```ts
// 튜플의 head/tail 분리
type Head<T extends any[]> = T extends [infer H, ...any[]] ? H : never;
type Tail<T extends any[]> = T extends [any, ...infer Rest] ? Rest : [];

type H = Head<[1, 2, 3]>; // 1
type T = Tail<[1, 2, 3]>; // [2, 3]

// 재귀로 튜플 뒤집기
type Reverse<T extends any[]> =
  T extends [infer First, ...infer Rest]
    ? [...Reverse<Rest>, First]
    : [];

type Rev = Reverse<[1, 2, 3]>; // [3, 2, 1]
```

template literal type과 결합하면 문자열 파싱도 가능하다.

```ts
type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}`
    ? [Head, ...Split<Tail, D>]
    : [S];

type Parts = Split<'a.b.c', '.'>; // ['a', 'b', 'c']
```

재귀 깊이에는 제약이 있다. TypeScript는 tail-recursion으로 최적화된 경우 약 1000회까지 허용하지만, 비-tail 재귀는 약 50회 깊이에서 `Type instantiation is excessively deep and possibly infinite (2589)`로 차단된다. 큰 입력을 다루는 type-level 코드는 누적자(accumulator) 패턴으로 tail-recursive하게 작성해야 안전하다.

## 5. Variance 애너테이션 (in / out)

TypeScript 4.7부터 타입 파라미터에 명시적 variance 애너테이션 `in`(반공변), `out`(공변), `in out`(불변)을 붙일 수 있다. 이것은 구조적 타이핑에서 컴파일러가 추론하는 variance를 사람이 명시해 검증하고, 동시에 비교 성능을 개선하는 장치다.

```ts
interface Producer<out T> {
  produce(): T;
}

interface Consumer<in T> {
  consume(value: T): void;
}

interface Invariant<in out T> {
  value: T;
  setValue(v: T): void;
}
```

`out T`는 `T`가 출력(반환) 위치에만 쓰임을 선언한다. 따라서 `Producer<Dog>`는 `Producer<Animal>`에 할당 가능(공변)하다. `in T`는 입력 위치에만 쓰임을 선언하므로 `Consumer<Animal>`이 `Consumer<Dog>`에 할당 가능(반공변)하다.

```ts
class Animal {}
class Dog extends Animal {}

declare let pd: Producer<Dog>;
declare let pa: Producer<Animal>;
pa = pd; // OK: out 이므로 공변

declare let ca: Consumer<Animal>;
declare let cd: Consumer<Dog>;
cd = ca; // OK: in 이므로 반공변
```

애너테이션이 실제 사용과 모순되면 컴파일러가 잡아준다. 예컨대 `out T`로 선언했는데 `T`가 파라미터 위치에 나타나면 에러가 발생한다. 이로써 라이브러리 작성자는 의도한 variance를 계약으로 고정할 수 있다.

## 6. Variance 애너테이션의 성능 측면

variance 애너테이션은 단순한 문서화가 아니다. 제네릭 타입 간 호환성 검사 시 TypeScript는 기본적으로 구조적으로(structurally) 멤버를 하나하나 비교한다. 멤버가 많거나 깊게 중첩된 타입에서는 이 비교 비용이 크다. variance가 명시되면 컴파일러는 구조 비교를 건너뛰고 타입 인자(type argument)만 variance 규칙에 따라 비교한다.

| 항목 | 애너테이션 없음 | `out`/`in` 명시 |
|---|---|---|
| 호환성 비교 방식 | 구조적 멤버 전수 비교 | 타입 인자만 variance 규칙으로 비교 |
| 깊은 제네릭 비교 비용 | 멤버 수에 비례 | 인자 수에 비례(상수에 가까움) |
| 잘못된 사용 검출 | 불가 | 컴파일 에러로 검출 |
| 적용 권장 상황 | 일반 코드 | 재귀적/광범위하게 재사용되는 라이브러리 타입 |

실측 기준으로, 수십 개 멤버를 가진 깊게 중첩된 제네릭 인터페이스를 여러 곳에서 비교하는 대형 프로젝트에서 variance 애너테이션 추가로 `tsc` 타입 체크 시간이 눈에 띄게 줄어드는 사례가 보고된다. 다만 단순한 타입에서는 효과가 미미하므로, 컴파일러 진단(`tsc --extendedDiagnostics`)의 `Check time`이 병목일 때 선택적으로 적용하는 것이 합리적이다.

## 7. 실전 패턴: 안전한 DeepReadonly와 분배

분배 규칙과 `infer`를 조합한 실전 유틸리티로 `DeepReadonly`를 보자. 함수와 배열을 구분 처리해야 정확하다.

```ts
type DeepReadonly<T> =
  T extends (...args: any[]) => any
    ? T // 함수는 그대로
    : T extends readonly (infer E)[]
      ? ReadonlyArray<DeepReadonly<E>>
      : T extends object
        ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
        : T;

interface Config {
  name: string;
  servers: { host: string; port: number }[];
  onError: (e: Error) => void;
}

type RO = DeepReadonly<Config>;
// {
//   readonly name: string;
//   readonly servers: ReadonlyArray<{ readonly host: string; readonly port: number }>;
//   readonly onError: (e: Error) => void;
// }
```

조건 순서가 중요하다. 함수도 `object`에 매칭되므로 함수 분기를 먼저 두지 않으면 함수의 프로퍼티까지 readonly 매핑하려다 시그니처가 깨진다. 배열도 `object`에 매칭되므로 배열 분기를 객체 분기보다 앞에 둔다. 조건부 타입 분기는 위에서 아래로 평가되는 "타입 레벨 switch"임을 항상 의식해야 한다.

## 8. 디버깅과 트레이드오프

type-level 프로그래밍은 강력하지만 비용이 있다. 첫째, 컴파일 시간이다. 깊은 재귀 조건부 타입은 `tsc` 캐시 무효화 시 IDE 응답성을 떨어뜨린다. 둘째, 에러 메시지다. 복잡한 조건부 타입이 실패하면 IDE가 펼친 타입을 한 줄로 길게 출력해 원인 파악이 어렵다.

실무 권장 사항은 다음과 같다. 라이브러리 공개 API 경계에서만 정교한 조건부 타입을 쓰고, 애플리케이션 코드에서는 명시적 타입을 선호한다. 디버깅 시에는 중간 타입을 별칭으로 분리해 IDE 호버로 단계별 결과를 확인한다. `type _Debug = SomeComplexType<Input>`처럼 별칭을 만들면 호버에서 평가 결과를 볼 수 있다. 또한 `@ts-expect-error`와 타입 단위 테스트(`expectTypeOf` 류)를 두어 리팩터링 시 회귀를 방지한다.

```ts
// 타입 단위 테스트 예 (vitest expectTypeOf)
import { expectTypeOf } from 'vitest';

expectTypeOf<Exclude<'a' | 'b', 'a'>>().toEqualTypeOf<'b'>();
expectTypeOf<IsNeverCorrect<never>>().toEqualTypeOf<true>();
```

분배 조건부 타입, `infer`의 variance 의존 추론, `in`/`out` 애너테이션은 서로 맞물려 있다. 분배는 union 처리의 기본이고, `infer`는 구조 해체의 도구이며, variance 애너테이션은 그 결과의 호환성과 성능을 통제하는 계약이다. 셋을 함께 이해해야 type-level 코드를 예측 가능하게 작성할 수 있다.

## 9. 실전 체크리스트와 흔한 실수

현업에서 조건부 타입을 다룰 때 반복적으로 마주치는 함정을 모아두면 디버깅 시간이 크게 준다.

첫째, `boolean`은 사실 `true | false`의 union이다. 따라서 분배 조건부 타입에 `boolean`을 넣으면 두 멤버로 분배되어 예상 밖 결과가 나온다.

```ts
type Test<T> = T extends true ? 'yes' : 'no';
type R = Test<boolean>; // 'yes' | 'no' (분배 결과), 'no' 가 아님
```

둘째, `any`를 조건부 타입에 넣으면 양쪽 분기 union이 된다. `any extends X ? A : B`는 `A | B`로 평가된다. 입력에 `any`가 섞일 수 있는 공개 API에서는 의도치 않은 union 확장을 조심해야 한다.

셋째, 분배를 끄는 `[T] extends [U]` 패턴은 `never` 처리뿐 아니라 "union 전체를 한 덩어리로 비교"하고 싶을 때 일반적으로 유용하다. 예를 들어 두 union이 정확히 같은지 비교하는 `Equals` 타입도 이 기법에 의존한다.

```ts
type Equals<A, B> =
  (<T>() => T extends A ? 1 : 2) extends
  (<T>() => T extends B ? 1 : 2) ? true : false;

type Q1 = Equals<{ a: 1 }, { a: 1 }>; // true
type Q2 = Equals<string | number, number | string>; // true
type Q3 = Equals<{ a: 1 }, { a: 1; b: 2 }>; // false
```

이 `Equals`는 함수 타입의 식별성(identity)을 이용한 정밀 비교로, 단순 `A extends B ? B extends A`보다 정확하다. 타입 단위 테스트 프레임워크 내부도 유사한 기법을 쓴다.

마지막으로 점검 순서를 정리하면, (1) 분배가 필요한가 불필요한가를 먼저 정하고 `[T]` 래핑 여부를 결정한다, (2) `never`/`any`/`boolean` 같은 특수 입력의 분배 결과를 별도로 검증한다, (3) `infer`가 공변/반공변 어느 위치인지 확인해 union/intersection 결과를 예측한다, (4) 재귀 깊이가 깊으면 accumulator로 tail-recursive하게 바꾼다, (5) 공개 타입은 `expectTypeOf`로 회귀 테스트를 건다. 이 다섯 단계를 습관화하면 조건부 타입의 "마법 같은" 동작이 예측 가능한 규칙으로 바뀐다.

## 참고

- TypeScript Handbook — Conditional Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.7 Release Notes — Optional Variance Annotations: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- TypeScript Handbook — Mapped Types / Key Remapping: https://www.typescriptlang.org/docs/handbook/2/mapped-types.html
- microsoft/TypeScript Wiki — Performance (Variance & type comparison): https://github.com/microsoft/TypeScript/wiki/Performance
