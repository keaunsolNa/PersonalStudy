Notion 원본: https://www.notion.so/3835a06fd6d3815d8b88cc33865e8b90

# TypeScript Mapped Types Key Remapping과 Homomorphic modifier 보존 및 재귀 변환

> 2026-06-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Mapped Type 의 `as` 절(key remapping)로 키를 재명명·필터링하는 메커니즘을 코드로 구현한다
- Homomorphic mapped type 이 `readonly`·`?` modifier 와 튜플/배열 구조를 보존하는 조건을 구분한다
- `+`/`-` modifier 와 `keyof`·`as` 조합으로 깊은 변환 유틸리티 타입을 작성한다
- 재귀 mapped type 의 종료 조건과 컴파일러 성능 한계를 측정값으로 파악한다

## 1. Mapped Type 의 기본 해부

Mapped type 은 `{ [K in Keys]: T }` 형태로, union `Keys` 의 각 멤버를 키로 펼쳐 객체 타입을 합성한다. 핵심은 `in` 우변이 임의의 union 이라는 점이다. `keyof T` 를 쓰면 기존 객체를 순회하지만, 리터럴 union 을 직접 줘도 된다.

```ts
type Flags = { [K in "darkMode" | "beta"]: boolean };
type Stringify<T> = { [K in keyof T]: string };
```

`keyof T` 를 순회하는 형태(`[K in keyof T]`)는 컴파일러가 homomorphic(준동형) 으로 인식한다. 이 인식 여부가 modifier 보존의 분기점이다.

## 2. `as` 절을 통한 Key Remapping

TS 4.1 부터 mapped type 에 `as` 절이 추가되어 키 자체를 변환할 수 있다. 우변이 `never` 로 평가되면 그 키는 결과에서 제거된다.

```ts
type Getters<T> = { [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K] };
type FunctionKeys<T> = { [K in keyof T as T[K] extends Function ? K : never]: T[K] };
```

`as` 절은 여러 키를 하나로 합칠 수도 있다. remap 식이 단사(injective)인지 확인하는 습관이 필요하다.

## 3. Homomorphic Mapped Type 과 modifier 보존

`in` 우변이 정확히 `keyof T` 형태일 때 원본 `T` 의 `readonly`·optional modifier 와 배열/튜플 구조가 전파된다.

```ts
type Mapped<T> = { [K in keyof T]: T[K] };
type Mutable<T> = { -readonly [K in keyof T]: T[K] };
type Required2<T> = { [K in keyof T]-?: T[K] };
```

표준 라이브러리의 `Partial`·`Required`·`Readonly`·`Pick` 이 모두 homomorphic 형태로 정의되어 입력의 modifier 와 배열 구조를 유지한다.

| 형태 | homomorphic | readonly/? 보존 | 배열·튜플 보존 |
|---|---|---|---|
| [K in keyof T] | 예 | 보존 | 보존 |
| [K in keyof T as ...] | 부분 | 보존(원본키 연결 시) | 깨짐 |
| [K in "a" \| "b"] | 아니오 | 미보존 | 해당 없음 |

## 4. 깊은(Deep) 변환과 재귀 mapped type

```ts
type Primitive = string | number | boolean | bigint | symbol | null | undefined;
type DeepReadonly<T> = T extends Primitive | Function
  ? T
  : T extends ReadonlyArray<infer U>
    ? ReadonlyArray<DeepReadonly<U>>
    : { readonly [K in keyof T]: DeepReadonly<T[K]> };
```

base case 설계가 부실하면 `Date`·`Map`·`RegExp` 가 빈 `{}` 로 뭉개진다. TS 는 재귀 깊이 약 50, 총 인스턴스화 5,000,000 으로 제한하며 초과 시 TS2589 가 발생한다.

## 5. Key Remapping 실전: 이벤트 핸들러 매핑

```ts
type Handlers<E> = { [K in keyof E as `on${Capitalize<string & K>}`]: (payload: E[K]) => void };
type Handler<P> = [P] extends [void] ? () => void : (payload: P) => void;
```

`[P] extends [void]` 처럼 튜플로 감싸 distributive 동작을 막는다.

## 6. 성능과 디버깅 트레이드오프

`tsc --extendedDiagnostics` 로 `Instantiations` 수치를 모니터링하고, 자주 쓰는 변환은 구체 타입으로 캐시한다.

```ts
type Expand<T> = T extends infer O ? { [K in keyof O]: O[K] } : never;
```

## 7. 실전: 폼 모델에서 검증 에러 타입 자동 도출

```ts
type FormErrors<T> = {
  [K in keyof T]?: T[K] extends Primitive
    ? string
    : T[K] extends Array<infer U>
      ? U extends Primitive ? string[] : FormErrors<U>[]
      : FormErrors<T[K]>;
};
```

값 모델이 단일 진실 원천이 되어 에러·터치·dirty 타입을 같은 키 집합에서 파생할 수 있다.

## 8. as const 와 mapped type 의 조합

```ts
const routes = { user: "/users/:id", post: "/posts/:postId/comments/:commentId" } as const;
type RouteParams = { [K in keyof typeof routes]: ExtractParams<(typeof routes)[K]> };
```

mapped type 은 conditional·template literal type 과 결합할 때 진가를 발휘하나 재귀 비용을 동반하므로 내부 도구에 한정한다.

## 9. 안티패턴과 권장 기준

단순히 키를 고르는 작업은 `Pick`/`Omit`, 값만 바꾸면 homomorphic mapped type 이면 된다. `as` 절은 키 이름이 바뀌거나 키를 걸러야 할 때로 한정한다. 공개 API 반환 타입으로 깊은 재귀 변환을 노출하면 소비자 컴파일이 느려지므로 평탄화된 구체 타입을 제공한다.

## 참고

- TypeScript Handbook — Mapped Types (https://www.typescriptlang.org/docs/handbook/2/mapped-types.html)
- TypeScript 4.1 Release Notes — Key Remapping (https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html)
- TypeScript Handbook — Conditional Types (https://www.typescriptlang.org/docs/handbook/2/conditional-types.html)
- microsoft/TypeScript Wiki — Performance (https://github.com/microsoft/TypeScript/wiki/Performance)
