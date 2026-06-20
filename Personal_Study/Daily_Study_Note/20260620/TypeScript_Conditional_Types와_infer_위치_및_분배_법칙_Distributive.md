Notion 원본: https://app.notion.com/p/3855a06fd6d381999ffffc235dd356bb

# TypeScript Conditional Types와 infer 위치 및 분배 법칙(Distributive)

> 2026-06-20 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 조건부 타입 `T extends U ? X : Y` 의 평가 규칙과 지연 평가를 설명한다
- 분배 조건부 타입이 유니온을 순회하는 메커니즘과 차단법을 구분한다
- `infer` 로 타입을 추출하는 위치별 패턴(반환·요소·매개변수)을 작성한다
- 동일 변수 다중 `infer` 와 공변·반변 위치의 추론 차이를 해석한다

## 1. 조건부 타입은 타입 수준의 삼항 연산자다

조건부 타입은 `T extends U ? X : Y` 형태로, `T` 가 `U` 에 할당 가능한지에 따라 `X` 또는 `Y` 로 해석된다. 값 수준의 삼항 연산자를 타입 수준으로 옮긴 것이다. 이것이 강력한 이유는 입력 타입에 따라 출력 타입이 달라지는 함수를 타입으로 표현할 수 있기 때문이다.

```typescript
type IsString<T> = T extends string ? true : false;
type A = IsString<"hello">; // true
type B = IsString<number>;  // false
```

여기서 `extends` 는 클래스 상속이 아니라 **할당 가능성(assignability)** 검사다. "왼쪽이 오른쪽의 부분집합인가" 를 묻는다. 조건부 타입의 진짜 가치는 제네릭과 결합해 타입 변환 로직을 캐프슐화할 때 드러난다.

## 2. 지연 평가와 제네릭 의존

조건부 타입은 그 조건이 아직 미해결 타입 변수에 의존하면 **즉시 평가되지 않고 보류** 된다. 이 지연(deferred) 성질이 제네릭 함수의 정확한 타이핑을 가능케 한다.

```typescript
type Flatten<T> = T extends Array<infer Item> ? Item : T;

function unwrap<T>(value: T): Flatten<T> {
  // T 가 구체화되기 전까지 Flatten<T> 는 보류 상태로 시그니처에 남는다
  return Array.isArray(value) ? value[0] : (value as Flatten<T>);
}

const x = unwrap([1, 2, 3]); // number
const y = unwrap("hi");      // string
```

`T` 가 구체적으로 정해질 때 비로소 `Flatten<T>` 가 풀린다. 보류된 조건부 타입은 호출 지점마다 다른 결과를 내므로, 하나의 함수가 입력에 따라 다른 반환 타입을 갖는 다형성을 타입 안전하게 표현한다.

## 3. 분배 조건부 타입

조건부 타입의 검사 대상이 **벌거뱗은 타입 매개변수(naked type parameter)** 이고 그 인자가 유니온이면, 조건부 타입은 유니온의 **각 구성원에 분배** 되어 적용된다. 결과를 다시 유니온으로 합친다.

```typescript
type ToArray<T> = T extends any ? T[] : never;
type R = ToArray<string | number>;
// 분배: ToArray<string> | ToArray<number>
//     = string[] | number[]   (NOT (string | number)[])
```

이 분배는 매우 유용하다. 예를 들어 유니온에서 특정 타입만 걸러내는 `Exclude` 와 추출하는 `Extract` 가 분배로 구현된다.

```typescript
type MyExclude<T, U> = T extends U ? never : T;
type MyExtract<T, U> = T extends U ? T : never;

type E1 = MyExclude<"a" | "b" | "c", "a">; // "b" | "c"
type E2 = MyExtract<"a" | "b" | 1 | 2, string>; // "a" | "b"
```

`MyExclude` 가 동작하는 원리: 유니온의 각 멤버에 대해 `U` 면 `never`(유니온에서 사라짐), 아니면 자신을 유지한다. `never` 가 유니온에서 흡수되므로 결과적으로 필터링이 된다.

## 4. 분배 차단하기

분배를 원하지 않을 때가 있다. 유니온 전체를 하나의 덩어리로 검사하려면 검사 대상을 `[T]` 처럼 **튜플로 감싸** 벌거뱗은 매개변수가 아니게 만든다.

```typescript
type IsNever<T> = [T] extends [never] ? true : false;
type N1 = IsNever<never>; // true  (튜플로 감싸 분배 차단)
type N2 = IsNever<number>; // false

// 차단하지 않으면 never 는 "빈 유니온" 이라 분배가 0번 일어나 항상 never 가 됨
type Broken<T> = T extends never ? true : false;
type Wrong = Broken<never>; // never (true 가 아님!)
```

`never` 는 빈 유니온으로 취급되어, 분배 조건부 타입에 넣으면 순회할 멤버가 없어 결과가 `never` 가 된다. 이 함정 때문에 "정확히 never 인가" 를 검사하려면 반드시 튜플 래핑이 필요하다.

## 5. infer: 타입 추출의 핵심

`infer` 는 조건부 타입의 `extends` 절 안에서 새 타입 변수를 선언해, 매칭되는 위치의 타입을 캐처한다. 표준 유틸리티 `ReturnType`, `Parameters` 가 모두 `infer` 로 만들어진다.

```typescript
type MyReturnType<T> = T extends (...args: any[]) => infer R ? R : never;
type MyParameters<T> = T extends (...args: infer P) => any ? P : never;

type Fn = (a: number, b: string) => boolean;
type Ret = MyReturnType<Fn>;  // boolean
type Par = MyParameters<Fn>;  // [a: number, b: string]
```

`infer R` 은 "이 자리에 들어갈 타입이 무엇이든 R 로 부르겠다" 는 선언이다. 패턴 매칭처럼 동작해, 구조에서 원하는 조각을 뽑아난다. 추출 위치는 반환 타입, 매개변수, 배열 요소, Promise 내부, 객체 속성 등 어디든 될 수 있다.

## 6. 재귀적 infer 와 깊은 추출

`infer` 를 재귀와 결합하면 중첩 구조를 끝까지 파고들 수 있다. 깊게 감싼 Promise 나 배열을 풀어내는 것이 대표 예다.

```typescript
type DeepAwaited<T> = T extends Promise<infer Inner> ? DeepAwaited<Inner> : T;
type D1 = DeepAwaited<Promise<Promise<Promise<number>>>>; // number

// 문자열 리터럴 파싱: 경로를 세그먼트 튜플로
type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}`
    ? [Head, ...Split<Tail, D>]
    : [S];
type Seg = Split<"a/b/c", "/">; // ["a", "b", "c"]
```

`Split` 은 템플릿 리터럴 타입의 `infer` 로 구분자 앞뒤를 분리하고, 꾬리를 재귀 처리한다. 이런 type-level 파싱은 라우터 경로에서 파라미터를 추출하거나 SQL 문자열에서 컬럼을 뽑는 라이브러리의 토대가 된다. 단, 재귀 깊이에는 컴파일러 한도(인스턴스화 깊이)가 있어 매우 긴 입력에서는 멈춘다.

## 7. 동일 변수 다중 infer: 공변과 반변

같은 이름의 `infer` 를 여러 위치에 두면, 컴파일러가 그 위치들의 분산(variance)에 따라 결과를 합친다. **공변(covariant) 위치** — 보통 출력/반환 — 에서는 유니온으로, **반변(contravariant) 위치** — 함수 매개변수 — 에서는 인터섭션으로 추론한다.

```typescript
// 공변 위치(유니온): 객체 속성 두 곳
type Covariant<T> = T extends { a: infer U; b: infer U } ? U : never;
type C = Covariant<{ a: string; b: number }>; // string | number

// 반변 위치(인터섭션): 함수 매개변수 두 곳
type Contravariant<T> = T extends {
  f: (x: infer U) => void;
  g: (x: infer U) => void;
} ? U : never;
type Co = Contravariant<{ f: (x: string) => void; g: (x: number) => void }>;
// string & number  → never (둘 다 받을 수 있어야 안전하므로 인터섭션)
```

이 비대칭은 타입 안전성에서 나온다. 반환값을 받는 쪽은 둘 중 어느 것이든 받으면 되니 유니온이 안전하고, 인자를 넘기는 쪽은 두 함수 모두를 만족시켜야 하니 둘 다인 인터섭션이 안전하다. 이 규칙을 이용하면 유니온을 인터섭션으로 변환하는 `UnionToIntersection` 같은 고급 타입을 만들 수 있다.

## 8. 실무 적용과 주의점

조건부 타입과 `infer` 는 라이브러리 API 의 타입 추론 품질을 좌우한다. ORM 이 쿼리 결과 타입을 컬럼 선택에서 도출하거나, 폼 라이브러리가 스키마에서 값 타입을 끕어내는 일이 모두 이 위에 선다. 다만 남용은 비용을 부른다. 깊은 재귀 조건부 타입은 컴파일 시간을 크게 늘리고, IDE 의 타입 힌트를 느리게 한다. 또한 분배 동작은 직관과 어긋날 때가 많아(특히 `never` 와 `boolean`= `true | false` 의 분배), 의도하지 않은 결과를 낳는다. 원칙은 이렇다. 분배가 필요하면 벌거뱗은 매개변수를, 통째 검사가 필요하면 튜플 래핑을 명시적으로 선택하고, 복잡한 type-level 로직은 `// @ts-expect-error` 가 아니라 작은 단위 타입 테스트(예: `Expect<Equal<...>>` 헬퍼)로 회귀를 막는다. 타입도 코드이므로 검증 가능한 단위로 쪼개는 것이 유지보수의 핵심이다.

## 9. UnionToIntersection: 반변을 이용한 변환

6절의 반변 인터섭션 추론을 응용하면, 유니온을 인터섭션으로 바꾸는 유명한 타입을 만들 수 있다. 유니온 멤버를 각각 함수 매개변수 위치에 놓아 분배시킨 뒤, 다시 `infer` 로 그 매개변수를 추론하면 반변 규칙에 의해 인터섭션이 된다.

```typescript
type UnionToIntersection<U> =
  (U extends any ? (k: U) => void : never) extends (k: infer I) => void
    ? I
    : never;

type U = { a: 1 } | { b: 2 };
type I = UnionToIntersection<U>; // { a: 1 } & { b: 2 }
```

동작을 풀어보면, 먼저 `U extends any ? ...` 가 분배되어 함수 유니온이 만들어지고, 이를 `(k: infer I) => void` 에 매칭하면 매개변수는 반변 위치라 두 매개변수 타입의 인터섭션이 `I` 로 추론된다. 이 패턴은 여러 모듈의 타입을 하나로 합치거나, 유니온의 마지막 멤버를 추출하는 등 고급 type-level 유틸리티의 빌딩 블록이 된다.

## 10. boolean 분배 함정과 디버깅

조건부 타입을 쓰다 가장 자주 당황하는 지점은 `boolean` 이 사실 `true | false` 유니온이라 분배된다는 점이다.

```typescript
type IsTrue<T> = T extends true ? "yes" : "no";
type R = IsTrue<boolean>; // "yes" | "no" (boolean = true | false 가 각각 분배됨)

// 의도가 "boolean 전체를 하나로 검사" 라면 튜플 래핑으로 차단
type IsExactlyBoolean<T> = [T] extends [boolean] ? true : false;
type R2 = IsExactlyBoolean<boolean>; // true
```

type-level 코드의 디버깅은 값 코드보다 어렵다. 컴파일러가 중간 결과를 보여주지 않기 때문이다. 실무 기법은 두 가지다. 첫째, 작은 타입 별칭으로 단계를 쪼개 IDE 의 호버로 각 단계 결과를 확인한다. 둘째, 등가 검사 헬퍼로 단위 테스트를 작성한다.

```typescript
type Equal<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends (<T>() => T extends Y ? 1 : 2) ? true : false;
type Expect<T extends true> = T;

// 타입 테스트: 컴파일되면 통과, 어긋나면 컴파일 에러
type _t1 = Expect<Equal<ToArray<string | number>, string[] | number[]>>;
```

이 `Equal` 헬퍼는 두 타입이 양방향으로 동일한지를 함수 동일성으로 비교한다. 복잡한 조건부 타입을 만들 때 이런 타입 테스트를 함께 두면, 리팩터링 시 의도치 않은 분배·추론 변화를 컴파일 단계에서 잡는다. 타입도 회귀 테스트 대상이라는 관점이 type-level 프로그래밍의 신뢰성을 만든다.

## 11. 성능과 실무 가이드

조건부 타입과 재귀 `infer` 는 강력하지만 컴파일러 비용을 무겁게 만든다. 깊은 재귀(긴 문자열 파싱, 큰 튜플 변환)는 타입 인스턴스화 횟수를 폭증시켜 빌드 시간과 에디터 응답성을 떨어뜨린다. TypeScript 는 재귀 깊이에 안전장치(과도한 인스턴스화 시 에러)를 두지만, 그 직전까지 가는 타입은 IDE 를 체감상 느리게 한다. 실무 가이드는 명확하다. 라이브러리의 공개 API 추론처럼 가치가 분명한 곳에만 복잡한 조건부 타입을 쓰고, 애플리케이션 코드에서는 단순한 유틸리티(`Pick`, `Omit`, `ReturnType` 등 표준 제공)로 충분한 경우가 대부분이다. 또한 `--extendedDiagnostics` 로 타입 검사 시간을 측정해 병목 타입을 찾고, 과도한 분배·재귀는 튜플 래핑이나 중간 캐싱 타입으로 평탄화한다. 타입 시스템은 표현력과 컴파일 비용의 균형 위에서 쓰는 도구이며, "할 수 있다" 와 "해야 한다" 를 구분하는 것이 숙련의 핵심이다.

## 참고

- TypeScript Handbook — Conditional Types, Distributive Conditional Types
- TypeScript Handbook — Type Inference in Conditional Types (`infer`)
- TypeScript Release Notes 2.8 — Conditional Types 도입
- type-challenges 저장소 — Medium/Hard 문제 모음
