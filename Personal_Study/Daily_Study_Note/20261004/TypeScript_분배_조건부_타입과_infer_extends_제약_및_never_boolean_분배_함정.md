Notion 원본: https://www.notion.so/3ef5a06fd6d381989addf9dc066132a3

# TypeScript 분배 조건부 타입(Distributive Conditional Types)과 infer extends 제약 및 never·boolean 분배 함정

> 2026-10-04 신규 주제 · 확장 대상: TypeScript Mapped Types와 템플릿 리터럴 타입 재귀 파싱

## 학습 목표

- 조건부 타입이 유니온에 분배되는 조건과 분배가 일어나지 않는 조건을 구분한다.
- `never`와 `boolean`이 분배 과정에서 만드는 예상 밖 결과를 재현하고 고친다.
- `infer X extends C` 제약으로 추론 결과를 좁히고 템플릿 리터럴 파싱에 적용한다.
- 분배 설계를 컴파일 성능 관점에서 측정하는 절차를 세운다.

## 1. 분배가 일어나는 정확한 조건

조건부 타입 `T extends U ? X : Y`는 검사 대상 `T`가 "벌거벗은 타입 매개변수(naked type parameter)"일 때, 그리고 그 자리에 유니온이 인스턴스화될 때 유니온의 각 멤버에 개별 적용된 뒤 결과가 다시 유니온으로 합쳐진다. 이것이 분배(distribution)다. 핸드북은 이를 "distributive conditional types"라 부르며, 대표 예가 `Exclude<T, U>`, `Extract<T, U>`, `NonNullable<T>`다. 이 유틸리티들은 모두 분배 덕분에 "유니온에서 일부 멤버를 걸러내는" 동작을 한다.

```ts
type ToArray<T> = T extends unknown ? T[] : never;

type A = ToArray<string | number>;
// string[] | number[]  (멤버별로 적용된 뒤 합쳐짐)

type MyExclude<T, U> = T extends U ? never : T;
type B = MyExclude<"a" | "b" | "c", "a" | "c">; // "b"
```

여기서 중요한 단어는 "벌거벗은"이다. 검사 위치가 `T` 그대로여야 하고, `T[]`, `[T]`, `Promise<T>`, `T & {}` 처럼 다른 구조로 감싸이면 분배는 일어나지 않는다. 또 하나 놓치기 쉬운 점은 분배가 "타입 별칭이 선언된 형태"에 의해 결정된다는 것이다. 즉 호출 시점에 유니온을 넘기는 것이 아니라, 선언부에서 검사 대상이 타입 매개변수 그 자체인지가 기준이다. 아래처럼 같은 의미처럼 보이는 두 선언이 서로 다른 결과를 낸다.

```ts
type Dist<T>   = T extends string ? "yes" : "no";
type NoDist<T> = [T] extends [string] ? "yes" : "no";

type R1 = Dist<string | number>;   // "yes" | "no"
type R2 = NoDist<string | number>; // "no"  (유니온 전체가 string에 할당 가능한가?)
type R3 = NoDist<"a" | "b">;       // "yes"
```

`Dist`는 "유니온의 각 멤버가 string인가"를 묻고 결과를 합친다. `NoDist`는 "유니온 전체가 string에 할당 가능한가"를 한 번만 묻는다. 어느 쪽이 맞는지는 목적에 달렸지만, 의도 없이 한쪽을 쓰면 뒤에서 다룰 함정에 빠진다. 또한 분배는 타입 매개변수에만 해당하므로, 별칭 밖에서 `string | number extends string ? 1 : 2`처럼 리터럴하게 쓰면 분배 없이 유니온 전체가 한 번에 검사된다.

## 2. 분배를 끄는 방법과 선택 기준

분배를 억제하는 가장 흔한 방법은 양쪽을 튜플로 감싸는 `[T] extends [U]`다. 이 관용구는 TypeScript 저장소의 이슈와 핸드북 설명에서 반복적으로 등장하며 사실상 표준이다. 검사 대상이 더 이상 벌거벗은 매개변수가 아니므로 유니온은 쪼개지지 않고 하나의 타입으로 비교된다. 우변도 함께 감싸야 한다는 점에 주의한다. `[T] extends U`로 쓰면 튜플이 `U`에 할당 가능한지를 묻게 되어 의미가 완전히 달라진다. 선택 기준은 단순하다. 입력 유니온의 각 멤버를 독립 변환하는 매핑 성격이면 분배를 그대로 쓰고, 유니온 전체에 대한 성질(전체 포함 여부, never 여부)을 묻는 술어 성격이면 튜플로 감싼다.

| 구분 | 선언 형태 | 질문 | 유니온 입력 시 결과 형태 |
| --- | --- | --- | --- |
| 분배 | `T extends U ? X : Y` | 각 멤버가 U인가 | 멤버별 결과의 유니온 |
| 비분배 | `[T] extends [U] ? X : Y` | 전체가 U에 할당 가능한가 | 단일 결과 |
| 부분 감쌈(오류 소지) | `[T] extends U ? X : Y` | 튜플이 U에 할당 가능한가 | 단일 결과, 의미 변질 |

## 3. never 분배 함정

`never`는 공집합 유니온(멤버가 0개인 유니온)으로 취급된다. 분배는 "멤버마다 적용"이므로 멤버가 없으면 적용할 대상이 없고, 결과는 다시 `never`가 된다. 따라서 분배형 조건부 타입에 `never`를 넣으면 true 분기도 false 분기도 평가되지 않고 곧장 `never`가 나온다. 핸드북에 명시된 개념은 아니지만 TypeScript의 동작으로 널리 알려져 있고 직접 실행해 확인할 수 있다.

```ts
type IsString<T> = T extends string ? true : false;

type X1 = IsString<never>;   // never  (false가 아니다!)
type X2 = IsString<string>;  // true
type X3 = IsString<number>;  // false
```

"never는 string이 아니니 false"라고 기대하기 쉬워서 이 결과가 함정이 된다. 해법은 분배를 꺼서 never 자체를 하나의 값으로 취급하는 것이다.

```ts
type IsNever<T> = [T] extends [never] ? true : false;

type Y1 = IsNever<never>;     // true
type Y2 = IsNever<string>;    // false
type Y3 = IsNever<never[]>;   // false (never[] 는 never가 아니다)

type SafeIsString<T> = [T] extends [never]
  ? false
  : T extends string ? true : false;
type Y4 = SafeIsString<never>; // false
```

반대로 `never`의 분배 결과가 소멸하는 성질을 이용하는 것이 `Exclude`의 원리다. `T extends U ? never : T`에서 걸러진 멤버는 `never`로 바뀌고 유니온에서 `never`는 사라지므로 결과에서 빠진다. 즉 `never`는 분배 맥락에서 "항등원" 역할을 한다. 문제는 이 성질이 입력이 통째로 `never`일 때도 작동한다는 점이다. 필터링한 결과가 비었을 때 `never`가 나오는 것은 자연스럽지만, 술어 결과(true/false)가 필요한 곳에서 `never`가 새어 나오면 뒤따르는 조건부 타입이 연쇄적으로 `never`가 되어 오류 위치를 찾기 어렵다. 실무에서는 함수 반환 타입이 `never`로 추론되어 이후 코드가 "도달 불가능"으로 처리되는 식으로 드러나기도 한다.

주의할 별도 사례가 있다. 분배는 타입 매개변수 자리에서만 일어나므로, 매개변수를 거치지 않은 `never extends string ? 1 : 2`는 분배 없이 평가되어 `1`이다. 같은 `never`라도 별칭을 통과하느냐에 따라 결과가 달라지는 셈이다. 또 `any`가 검사 대상일 때는 true 분기와 false 분기의 유니온이 되는 특수 규칙이 있어, `IsString<any>`는 `boolean`이 된다. 라이브러리 타입 테스트에서 `any`와 `never`를 항상 별도 케이스로 넣는 이유다.

## 4. boolean이 true | false로 쪼개지는 함정

`boolean`은 원시 타입처럼 보이지만 타입 시스템에서는 `true | false` 유니온이다. 따라서 분배형 조건부 타입에 `boolean`을 넣으면 `true`와 `false`가 각각 평가되고 결과가 합쳐진다. enum도 같은 이유로 멤버 유니온으로 분해된다. 직관과 어긋나는 대표 사례는 다음과 같다.

```ts
type IsTrue<T> = T extends true ? "T" : "F";
type B1 = IsTrue<boolean>;        // "T" | "F"  ("T"도 "F"도 아님)

type Not<T extends boolean> = T extends true ? false : true;
type B2 = Not<boolean>;           // boolean (= false | true), 우연히 맞아 보임

type Both<A, B> = A extends true ? (B extends true ? true : false) : false;
type B3 = Both<boolean, boolean>; // boolean  (진리표 4개 조합이 합쳐진 결과)
```

`Both<boolean, boolean>`이 `boolean`이 되는 이유는 A와 B가 모두 분배되어 (true,true)→true, 나머지 세 조합→false가 만들어지고, 그 합집합이 `true | false`이기 때문이다. "둘 다 boolean이면 AND는 불확실하다"는 의미로는 맞지만, 의도가 "정확히 true 타입인가"였다면 틀린 결과다. 질문이 "T가 정확히 true인가"라면 `[T] extends [true]`로 비분배 비교를 쓰고, "T가 boolean인가(true|false 둘 다 포함하는가)"를 가리려면 양방향 할당 검사 `[boolean] extends [T]`를 함께 쓴다.

```ts
type IsExactlyTrue<T>  = [T] extends [true] ? ([true] extends [T] ? true : false) : false;
type IsBooleanUnion<T> = [T] extends [boolean] ? ([boolean] extends [T] ? true : false) : false;

type C1 = IsExactlyTrue<true>;     // true
type C2 = IsExactlyTrue<boolean>;  // false
type C3 = IsBooleanUnion<boolean>; // true
type C4 = IsBooleanUnion<true>;    // false
```

## 5. infer와 분배의 상호작용

`infer`는 조건부 타입의 `extends` 절 안에서 타입 변수를 "추론해서 이름 붙이는" 장치다. 분배가 켜져 있으면 유니온 멤버마다 별도로 추론이 수행된다. 이를 이용하면 유니온에서 일괄 추출이 가능하지만, 추론 위치에 따라 결과 형태가 달라진다. 공변 위치(반환 타입, 프로퍼티)에서 여러 후보가 나오면 유니온으로 합쳐지고, 반공변 위치(함수 매개변수)에서 여러 후보가 나오면 교차 타입으로 합쳐진다. 핸드북은 이 차이를 "multiple candidates for the same type variable in co-variant positions causes a union type to be inferred" 식으로 설명한다.

```ts
type Elem<T> = T extends (infer U)[] ? U : never;
type E1 = Elem<string[] | number[]>; // string | number (분배 후 각각 추론)

type Cov<T> = T extends { a: infer U; b: infer U } ? U : never;
type E2 = Cov<{ a: string; b: number }>;   // string | number

type Contra<T> = T extends { a: (x: infer U) => void; b: (x: infer U) => void } ? U : never;
type E3 = Contra<{ a: (x: string) => void; b: (x: number) => void }>; // string & number (= never)
```

## 6. infer extends 제약(TypeScript 4.7)

TypeScript 4.7부터 `infer U extends C` 문법으로 추론되는 타입 변수에 제약을 직접 걸 수 있다. 이전에는 추론 후 한 번 더 조건부 타입으로 걸러야 했다. 릴리스 노트의 예시처럼 튜플의 첫 원소가 문자열인 경우만 받고 싶을 때 코드가 크게 줄어든다.

```ts
// 4.7 이전 방식: 중첩 조건부 타입이 필요했다
type FirstString_Old<T> =
  T extends [infer S, ...unknown[]]
    ? S extends string ? S : never
    : never;

// 4.7 이후: 추론과 제약을 한 번에
type FirstString<T> =
  T extends [infer S extends string, ...unknown[]] ? S : never;

type F1 = FirstString<["hello", 1]>; // "hello"
type F2 = FirstString<[42, "x"]>;    // never
```

## 7. 템플릿 리터럴과 infer extends number

TypeScript 4.8은 템플릿 리터럴 안의 `infer`에 `extends`로 `number`, `bigint`, `boolean` 같은 원시 타입 제약을 걸 때, 문자열을 해당 리터럴 타입으로 파싱하도록 개선했다. 예를 들어 `"100"`에서 `infer N extends number`로 추론하면 `string`이 아니라 숫자 리터럴 `100`이 된다. 이전에는 문자열 조각을 숫자로 바꾸는 표준 방법이 없어서 룩업 테이블이나 튜플 길이 트릭을 썼다.

```ts
type ParseInt<S> = S extends `${infer N extends number}` ? N : never;

type P1 = ParseInt<"42">;     // 42
type P2 = ParseInt<"-7">;     // -7
type P3 = ParseInt<"4x">;     // never  (숫자 리터럴로 왕복 변환되지 않음)
type P4 = ParseInt<"042">;    // never  (선행 0은 왕복 변환 불가)

type SplitNums<S extends string> =
  S extends `${infer H extends number},${infer R}`
    ? [H, ...SplitNums<R>]
    : S extends `${infer L extends number}` ? [L] : [];

type P5 = SplitNums<"1,2,3">; // [1, 2, 3]
```

`"042"`가 `never`가 되는 이유는 변환 규칙 때문이다. 4.8 릴리스 노트는 문자열을 숫자로 파싱한 뒤 다시 문자열로 되돌렸을 때 원본과 같아야만 숫자 리터럴로 인정한다고 설명한다. 따라서 선행 0, 후행 공백, `1e3` 같은 표기는 걸러진다. 입력 검증용으로는 유리하지만 "느슨한 파싱"이 필요한 경우 별도 처리가 필요하다.

여기에 분배 함정이 겹친다. 입력 `S`가 `string`(넓은 타입)일 때 `ParseInt<string>`은 `never`가 되고, 유니온 `"1" | "2"`를 넣으면 `1 | 2`가 된다. 반면 `never` 입력은 앞서 본 대로 결과도 `never`라서 "파싱 실패"와 "입력 없음"을 구분하지 못한다. 파서 타입의 오류 보고를 위해 실패 시 `never` 대신 에러 리터럴 타입(예: `{ error: "NaN" }`)을 반환하는 설계가 흔하다.

```ts
type ParseIntSafe<S extends string> =
  [S] extends [never] ? { error: "empty" }
  : S extends `${infer N extends number}` ? N
  : { error: "NaN" };

type Q1 = ParseIntSafe<"12">;   // 12
type Q2 = ParseIntSafe<"12a">;  // { error: "NaN" }
type Q3 = ParseIntSafe<never>;  // { error: "empty" }
```

## 8. 실무 설계 지침과 컴파일 비용 측정

정리하면 분배는 "멤버별 변환"에 쓰고, 술어는 튜플 래핑으로 비분배화하며, `never`·`boolean`·`any`는 항상 별도 테스트 케이스를 둔다. 라이브러리 수준에서는 타입 테스트를 `Equal<A, B>` 같은 정확 비교 헬퍼와 함께 작성해 회귀를 막는다. 아래는 외부 의존성 없이 쓸 수 있는 최소 단언 도구다.

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;

type _t1 = Expect<Equal<IsNever<never>, true>>;
type _t2 = Expect<Equal<IsTrue<boolean>, "T" | "F">>;
type _t3 = Expect<Equal<ParseInt<"42">, 42>>;
```

이 파일은 `tsc --noEmit`으로 검사하며, 단언이 틀리면 컴파일 오류가 난다. 분배의 trade-off는 성능에도 있다. 유니온 멤버 수만큼 조건부 평가가 일어나고, 분배된 결과의 유니온은 정규화(중복 제거, 서브타입 축소 여부 판단) 비용을 발생시킨다. 재귀 타입은 깊이 한도가 있어 일반 재귀는 약 50단계 안팎에서 "Type instantiation is excessively deep" 오류가 나고, 4.5에서 도입된 꼬리 재귀 조건부 타입 최적화는 한도를 크게 늘린다(공식 노트 기준 1000단계). 구체 수치는 버전에 따라 달라질 수 있으므로 사용 중인 버전에서 직접 확인한다.

비용은 추정하지 말고 측정한다. 방법은 다음과 같다. 첫째 `tsc --noEmit --extendedDiagnostics`를 실행해 `Instantiations`, `Types`, `Check time`을 기록한다. 둘째 `tsc --noEmit --generateTrace ./trace`로 트레이스를 만들고 `trace/trace.json`을 Chrome의 `chrome://tracing` 또는 Perfetto에 열어 `checkSourceFile` 아래 오래 걸린 타입을 찾는다. 셋째 분배형 정의와 `[T] extends [U]` 정의 각각에 유니온 크기(예: 10, 100, 1000 멤버)를 바꿔 넣는 최소 재현 파일을 만들어 같은 명령으로 비교한다. 같은 머신에서 여러 번 반복해 중앙값을 보고 TypeScript 버전을 함께 기록해야 비교가 의미 있다. 여기서는 임의 수치를 제시하지 않으며 위 절차로 얻은 값만 근거로 삼는다.

## 참고

- TypeScript Handbook, Conditional Types (Distributive Conditional Types, Inferring Within Conditional Types): https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.7 Release Notes, `extends` Constraints on `infer` Type Variables: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- TypeScript 4.8 Release Notes, Improved Inference for `infer` Types in Template String Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- TypeScript 4.5 Release Notes, Tail-Recursion Elimination on Conditional Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- TypeScript Wiki, Performance (tsc --generateTrace, extendedDiagnostics): https://github.com/microsoft/TypeScript/wiki/Performance
