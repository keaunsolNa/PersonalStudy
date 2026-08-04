Notion 원본: https://app.notion.com/p/3b25a06fd6d381578384de6baa118e28

# TypeScript Mapped Types 심화와 키 리매핑 및 수정자 제어

> 2026-08-04 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 매핑된 타입의 기본 문법과 homomorphic 여부에 따른 수정자 보존 동작을 구분한다
- readonly·optional 수정자를 + / - 로 명시적으로 추가·제거하는 법을 익힌다
- as 절 키 리매핑으로 프로퍼티 이름을 변환하거나 필터링한다
- template literal 과 결합해 이벤트 핸들러·getter 타입을 자동 생성한다

## 1. 매핑된 타입의 기본 — 키를 순회해 새 타입을 짓다

매핑된 타입(mapped type)은 기존 타입의 키를 순회하며 각 프로퍼티를 변환해 새 객체 타입을 만든다. 문법은 인덱스 시그니처를 닮았지만 `in` 키워드로 유니온을 순회한다는 점이 다르다.

```typescript
type MyReadonly<T> = {
  readonly [K in keyof T]: T[K];
};

interface User {
  id: number;
  name: string;
}

type RO = MyReadonly<User>;
// { readonly id: number; readonly name: string; }
```

`[K in keyof T]` 는 `T` 의 모든 키를 하나씩 `K` 에 바인딩하며 순회한다. `T[K]` 는 해당 키의 값 타입을 **인덱스 접근 타입**으로 꺼낸다. 표준 유틸리티 `Partial`, `Required`, `Readonly`, `Pick`, `Record` 가 전부 이 문법으로 정의되어 있다.

```typescript
// 표준 라이브러리의 실제 정의
type Partial<T>  = { [P in keyof T]?: T[P] };
type Required<T> = { [P in keyof T]-?: T[P] };
type Record<K extends keyof any, V> = { [P in K]: V };
```

## 2. Homomorphic 매핑 — 원본 수정자를 보존하는가

매핑된 타입의 형태가 `{ [K in keyof T]: ... }` 처럼 **`keyof T` 를 직접 순회**하면 이를 homomorphic(준동형) 매핑이라 부른다. 이 경우 TypeScript 는 원본의 `readonly`·`optional` 수정자와 배열·튜플 구조를 자동으로 보존한다.

```typescript
interface Config {
  readonly host: string;
  port?: number;
}

type Clone<T> = { [K in keyof T]: T[K] };
type C = Clone<Config>;
// { readonly host: string; port?: number }  ← 수정자 그대로 유지

// 배열도 보존
type Boxed<T> = { [K in keyof T]: { value: T[K] } };
type B = Boxed<[number, string]>;
// [{ value: number }, { value: string }]  ← 튜플 구조 유지
```

반면 `K in keyof T` 가 아니라 다른 유니온(`K in "a" | "b"`)을 순회하면 non-homomorphic 이라 수정자·배열 구조가 보존되지 않는다. 이 구분은 유틸리티 타입을 직접 만들 때 미묘한 버그의 원인이 되므로 중요하다.

## 3. 수정자 명시 제어 — + 와 -

매핑 과정에서 `readonly` 와 `?`(optional)을 명시적으로 추가하거나 제거할 수 있다. 접두사 `+` 는 추가(생략 시 기본), `-` 는 제거를 뜻한다.

```typescript
// optional 제거 + readonly 제거 → 모두 필수·가변으로
type Mutable<T> = {
  -readonly [K in keyof T]-?: T[K];
};

interface Draft {
  readonly id?: number;
  readonly title?: string;
}

type Final = Mutable<Draft>;
// { id: number; title: string }  ← readonly·? 모두 벗겨짐
```

`Required<T>` 가 `-?` 로 optional 을 제거하는 것이 대표 예다. `-readonly` 는 불변 타입을 가변으로 바꿔 빌더 패턴이나 초기화 단계에서 유용하다. 주의할 점은 `-?` 가 프로퍼티에서 `undefined` 를 제거하지는 않는다는 것이다. optional 표시(`?`)만 없앨 뿐, 값 타입이 `string | undefined` 였다면 그 union 은 남는다.

```typescript
type NoUndef<T> = { [K in keyof T]-?: NonNullable<T[K]> };
// 진짜로 undefined 를 값에서도 제거하려면 NonNullable 병행
```

## 4. as 절 키 리매핑 — 프로퍼티 이름을 바꾸다

TypeScript 4.1 부터 매핑된 타입에 `as` 절을 붙여 **키 자체를 변환**할 수 있다. `[K in keyof T as NewKey]` 형태로, `NewKey` 자리에 template literal 이나 조건부 타입을 쓴다.

```typescript
// 모든 프로퍼티에 대한 getter 메서드 이름 생성
type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};

interface Person {
  name: string;
  age: number;
}

type PersonGetters = Getters<Person>;
// {
//   getName: () => string;
//   getAge:  () => number;
// }
```

`Capitalize` 는 내장 문자열 유틸리티 타입이고, `string & K` 는 `K` 가 `string | number | symbol` 일 수 있으므로 string 으로 좁히는 관용구다. `key: string` 을 template literal 에 넣으려면 반드시 string 으로 좁혀야 한다.

## 5. 키 필터링 — never 로 프로퍼티 제거

`as` 절이 `never` 를 반환하면 그 키는 결과 타입에서 **제외**된다. 이를 이용해 특정 조건의 프로퍼티만 남기는 필터를 만든다.

```typescript
// 값이 함수인 프로퍼티만 골라내기
type MethodsOnly<T> = {
  [K in keyof T as T[K] extends Function ? K : never]: T[K];
};

interface Widget {
  id: number;
  render(): void;
  update(x: number): void;
}

type WidgetMethods = MethodsOnly<Widget>;
// { render(): void; update(x: number): void }  ← id 제외
```

조건부 타입 `T[K] extends Function ? K : never` 가 함수가 아닌 키를 `never` 로 만들어 떨어뜨린다. 반대로 데이터 프로퍼티만 남기려면 `extends Function ? never : K` 로 뒤집는다. Redux 액션 타입에서 특정 접두사 키만 추출하거나, DTO 에서 함수를 걸러 순수 데이터만 남기는 데 자주 쓰인다.

```typescript
// 특정 접두사 키만 유지
type PickByPrefix<T, P extends string> = {
  [K in keyof T as K extends `${P}${string}` ? K : never]: T[K];
};

interface Env {
  API_URL: string;
  API_KEY: string;
  DB_HOST: string;
}
type ApiEnv = PickByPrefix<Env, "API_">;
// { API_URL: string; API_KEY: string }
```

## 6. 이벤트 핸들러 타입 자동 생성 — 실전 패턴

매핑된 타입과 template literal, 키 리매핑을 결합하면 상용구 타입을 자동 도출할 수 있다. 상태 객체의 각 필드에 대응하는 변경 이벤트 핸들러 인터페이스를 생성하는 예다.

```typescript
type Handlers<T> = {
  [K in keyof T as `on${Capitalize<string & K>}Change`]:
    (value: T[K], prev: T[K]) => void;
};

interface FormState {
  username: string;
  age: number;
  agreed: boolean;
}

type FormHandlers = Handlers<FormState>;
// {
//   onUsernameChange: (value: string,  prev: string)  => void;
//   onAgeChange:      (value: number,  prev: number)  => void;
//   onAgreedChange:   (value: boolean, prev: boolean) => void;
// }
```

이렇게 하면 `FormState` 에 필드를 추가하는 순간 대응하는 핸들러 타입이 자동으로 늘어난다. 수동으로 인터페이스를 중복 관리할 필요가 없어 필드 추가 시 핸들러 누락 같은 실수가 컴파일 단계에서 방지된다.

## 7. 유니온 배분과 깊은 매핑

매핑된 타입을 재귀적으로 적용하면 중첩 객체 전체를 변환하는 deep 유틸리티를 만들 수 있다. 다만 재귀 깊이와 배열·함수 처리에 주의해야 한다.

```typescript
type DeepReadonly<T> = T extends (infer E)[]
  ? ReadonlyArray<DeepReadonly<E>>
  : T extends Function
    ? T                                    // 함수는 그대로
    : T extends object
      ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
      : T;                                 // 원시값은 그대로

interface Nested {
  user: { name: string; tags: string[] };
}
type Frozen = DeepReadonly<Nested>;
// user 와 tags 원소까지 전부 readonly
```

함수와 배열을 먼저 분기 처리하지 않으면 함수의 프로퍼티까지 순회하거나 배열이 인덱스 객체로 취급되는 문제가 생긴다. deep 유틸리티는 실무에서 자주 쓰지만 순환 참조 타입에서 무한 재귀로 컴파일러가 멈출 수 있어, 깊이 제한이나 특정 타입 제외가 필요할 때가 있다.

## 8. 인덱스 시그니처·유니온 키의 함정

매핑된 타입이 인덱스 시그니처를 가진 타입을 순회하면 예상과 다른 결과가 나온다. `{ [k: string]: number }` 를 매핑하면 `keyof` 가 `string | number`(숫자 키도 문자열 인덱스로 접근 가능)를 내어, 리터럴 키 기대와 어긋난다.

```typescript
type Keys<T> = keyof T;
type K1 = Keys<{ a: 1; b: 2 }>;        // "a" | "b"
type K2 = Keys<{ [k: string]: number }>; // string | number
type K3 = Keys<{ [k: number]: number }>; // number
```

`as` 절 필터로 인덱스 시그니처만 제거하고 명시적 프로퍼티만 남기는 관용구가 유용하다. 리터럴 키와 인덱스 시그니처 키를 조건부로 구분한다.

```typescript
// 인덱스 시그니처를 걸러 명시 프로퍼티만 추출
type RemoveIndex<T> = {
  [K in keyof T as string extends K ? never
                 : number extends K ? never
                 : K]: T[K];
};

interface Mixed {
  known: string;
  [k: string]: unknown;
}
type OnlyKnown = RemoveIndex<Mixed>;   // { known: string }
```

`string extends K` 는 `K` 가 넓은 `string`(즉 인덱스 시그니처에서 온 키)일 때만 참이 되어 걸러진다. 리터럴 키 `"known"` 은 `string extends "known"` 이 거짓이라 살아남는다. 이 패턴은 서드파티 타입에 섞인 인덱스 시그니처를 정리해 정확한 키 유니온을 얻을 때 필수적이다.

## 9. 타입 레벨 테스트로 검증

매핑된 타입은 런타임 코드가 없으므로 타입 수준에서 동등성을 단언하는 테스트로 검증한다. 컴파일이 통과하면 타입이 기대대로 도출된 것이다.

```typescript
// 타입 동등성 단언 유틸
type Equals<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends
  (<T>() => T extends Y ? 1 : 2) ? true : false;

type Expect<T extends true> = T;

// 테스트 케이스 — 컴파일되면 통과
type _t1 = Expect<Equals<
  Getters<{ a: number }>,
  { getA: () => number }
>>;

type _t2 = Expect<Equals<
  Mutable<{ readonly x?: string }>,
  { x: string }
>>;

type _t3 = Expect<Equals<
  MethodsOnly<{ a: number; b(): void }>,
  { b(): void }
>>;
```

`Equals` 는 두 타입이 정확히 같을 때만 `true` 를 반환하는 정밀 비교 트릭이다(함수 시그니처의 조건부 타입 지연 평가를 이용). `Expect<T extends true>` 는 인자가 `true` 가 아니면 컴파일 에러를 낸다. `tsd` 나 `vitest` 의 `expectTypeOf` 같은 도구도 같은 원리로 타입을 CI 에서 검증한다.

```typescript
// vitest expectTypeOf 예시
import { expectTypeOf } from "vitest";
expectTypeOf<Getters<Person>>().toEqualTypeOf<{
  getName: () => string;
  getAge: () => number;
}>();
```

## trade-off 정리

매핑된 타입은 상용구를 제거하고 소스 오브 트루스를 한 곳으로 모아 유지보수성을 높인다. 그러나 중첩·재귀·조건부가 얽히면 타입 오류 메시지가 난해해져 디버깅이 어렵고, 깊은 재귀는 컴파일러의 인스턴스화 깊이 한계(기본 50)에 걸리거나 타입 체크 시간을 늘린다. `as` 절 리매핑과 template literal 은 강력하지만 지나치게 영리한 타입은 팀원이 읽기 어려워, 재사용도가 높은 유틸리티에만 국한하고 나머지는 명시적으로 적는 편이 낫다. 타입 수준 테스트를 CI 에 두어 리팩터링 시 회귀를 막는 것이 이런 고급 타입을 안전하게 운용하는 전제다.

## 참고

- TypeScript Handbook — Mapped Types, Key Remapping via `as`
- TypeScript 4.1 Release Notes — Key Remapping, Template Literal Types
- TypeScript lib.es5.d.ts — Partial, Required, Record 정의
- type-challenges (github.com/type-challenges/type-challenges)
