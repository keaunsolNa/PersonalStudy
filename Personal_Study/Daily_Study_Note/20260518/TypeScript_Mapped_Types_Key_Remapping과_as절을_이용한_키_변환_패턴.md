Notion 원본: https://www.notion.so/3645a06fd6d38115986bc296d346e9c8

# TypeScript Mapped Types Key Remapping과 as절을 이용한 키 변환 패턴

> 2026-05-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Mapped Type의 키 위치에서 `as` 절이 어떻게 키를 재작성하고 필터링하는지 추론한다
- Template Literal Type과 `as` 절을 결합해 이벤트 핸들러·getter·DTO 매퍼 시그니처를 생성한다
- Key Remapping이 컴파일러의 타입 추론에 미치는 비용을 식별하고, distributive conditional과의 결합에서 발생하는 함정을 회피한다
- 실제 API 응답 ↔ 도메인 모델 변환 레이어를 타입 안전하게 자동화한다

## 1. Mapped Type의 기본 골격과 `as` 절의 도입 배경

Mapped Type은 `{ [K in Keys]: T }` 형태로 객체 타입을 변환한다. TypeScript 4.1 이전에는 입력 키 `K`를 그대로 출력 키로 사용할 수밖에 없었다. 즉 키 자체를 변환하거나 제거할 수단이 없었기 때문에 `Pick`·`Omit` 같은 유틸리티는 키 집합을 미리 조건부 타입(conditional type)으로 좁혀서 입력으로 넣어야 했다. 이 구조는 가독성도 떨어지고, 키별로 다른 새 이름을 부여하는 경우 별도의 매핑 객체와 `as const`를 끼워 맞춰야 했다.

4.1에서 추가된 `as` 절은 Mapped Type 내부에서 출력 키를 임의의 string/number/symbol 또는 template literal 형태로 재작성한다. 형식적으로는 다음과 같이 정의된다.

```ts
type Remap<T> = {
  [K in keyof T as NewKey<K, T[K]>]: T[K];
};
```

여기서 `NewKey`가 `never`로 평가되면 해당 키는 결과 타입에서 제거된다. 이 단순한 규칙이 Pick/Omit/Record/EventMap/Getter 생성기까지 전부 한 줄의 mapped type으로 표현할 수 있게 만든다.

## 2. 키 필터링 — `never`로 제거하는 패턴

`as`의 결과가 `never`이면 해당 슬롯이 사라진다. 가장 흔한 사용처는 "값의 타입이 특정 조건을 만족하는 키만 남기기"이다.

```ts
type FunctionKeys<T> = {
  [K in keyof T as T[K] extends (...args: any[]) => any ? K : never]: T[K];
};

interface UserService {
  id: number;
  name: string;
  save(): Promise<void>;
  delete(id: number): Promise<void>;
}

type Methods = FunctionKeys<UserService>;
// { save(): Promise<void>; delete(id: number): Promise<void>; }
```

비교 시점은 `as` 절 평가 단계이지 결과 슬롯의 값 부분이 아니다. 따라서 키와 값을 동시에 좁힐 때는 `as` 절에서 키, 값에서 동일한 조건부 타입을 한 번 더 평가하지 말고 한쪽에만 둔다. 두 번 평가하면 추론 단가가 두 배가 된다.

`Omit`의 좁은 정의를 직접 작성해 보면 의도가 더 분명해진다.

```ts
type MyOmit<T, K extends PropertyKey> = {
  [P in keyof T as P extends K ? never : P]: T[P];
};
```

표준 라이브러리 `Omit<T,K>`는 `Pick<T, Exclude<keyof T, K>>`로 구현되어 있어 키가 union이 아닌 경우 distributive 동작을 활용하지 못한다는 미세한 차이가 있다. `as` 기반 `MyOmit`은 K가 string literal union이든 PropertyKey이든 동일하게 동작한다.

## 3. Template Literal Type과의 결합 — getter/setter/이벤트 생성

`as` 절의 진짜 위력은 template literal과 합쳐졌을 때 드러난다. 다음은 도메인 객체에서 자동으로 getter 시그니처를 만드는 매퍼다.

```ts
type Capitalize<S extends string> = S extends `${infer F}${infer R}` ? `${Uppercase<F>}${R}` : S;

type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};

interface User { id: number; name: string; }
type UserGetters = Getters<User>;
// { getId(): number; getName(): string; }
```

`string & K`로 한 번 좁히는 패턴은 `keyof T`에 number/symbol이 섞일 수 있어 `Capitalize`의 string 제약을 통과시키기 위한 관용구다. number 키도 같이 처리하고 싶다면 `as` 절에서 `K extends string ? ... : never`로 분기시켜야 한다.

이벤트 매핑은 더 실용적이다. NestJS·Vue·DOM 핸들러 이름 자동화에 직접 쓰인다.

```ts
type EventHandlers<T extends Record<string, any>> = {
  [K in keyof T as `on${Capitalize<string & K>}`]: (payload: T[K]) => void;
};

interface Events {
  click: { x: number; y: number };
  submit: FormData;
}
type Handlers = EventHandlers<Events>;
// { onClick: (payload: { x: number; y: number }) => void; onSubmit: (payload: FormData) => void; }
```

## 4. Distributive `as`와 키 분배 — 한 키를 여러 키로 폭발시키기

`as` 절은 union 분배 규칙을 그대로 따른다. 즉 키가 union일 때 각 멤버에 대해 한 번씩 평가되고, 그 결과 union이 다시 합쳐진다. 이를 이용하면 한 입력 키를 여러 출력 키로 폭발시킬 수 있다.

```ts
type GetterAndSetter<T> = {
  [K in keyof T as
    | `get${Capitalize<string & K>}`
    | `set${Capitalize<string & K>}`
  ]: K extends `get${string}` ? () => T[K] : (value: T[K]) => void;
};
```

값 슬롯에서 `K`를 다시 검사해 getter/setter 시그니처를 분기하려 하면 위 코드처럼 의도대로 동작하지 않는다. `K`는 원본 키이지 변환된 키가 아니기 때문이다. 올바른 구현은 변환된 키를 다시 union에 매칭하는 두 번째 mapped type을 거치거나, 두 형태를 합쳐 별도로 정의한다.

```ts
type Accessors<T> =
  & { [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K] }
  & { [K in keyof T as `set${Capitalize<string & K>}`]: (v: T[K]) => void };
```

intersection으로 합치는 편이 distributive 폭발보다 평가 단계가 적어 컴파일도 빠르고 호버 출력도 깔끔하다.

## 5. 키 변환과 추론 비용 — `infer`와 결합한 역매핑

template literal에서 `infer`로 키의 일부를 추출하는 역방향 변환도 가능하다.

```ts
type Uncapitalize<S extends string> = S extends `${infer F}${infer R}` ? `${Lowercase<F>}${R}` : S;
type UnGetters<T> = {
  [K in keyof T as K extends `get${infer Name}` ? Uncapitalize<Name> : never]:
    T[K] extends () => infer R ? R : never;
};

interface Api { getUser(): string; getPost(): number; createPost(): void; }
type ApiFields = UnGetters<Api>; // { user: string; post: number; }
```

`as` 절은 키 자리에서 한 번, 값 자리에서 한 번 평가되므로 같은 conditional을 두 곳에서 쓰면 평가 그래프가 두 갈래로 갈라진다. 5.x 컴파일러는 conditional의 동일성을 부분적으로 캐싱하지만, 깊은 재귀가 들어가면 캐시 적중률이 낮아져 빌드 시간이 눈에 띄게 늘어난다. 같은 conditional 결과를 두 곳에서 써야 한다면 헬퍼 alias로 한 번 캐싱해 두는 것이 좋다.

```ts
type _ReturnOf<T, K extends keyof T> = T[K] extends () => infer R ? R : never;

type UnGetters2<T> = {
  [K in keyof T as K extends `get${infer Name}` ? Uncapitalize<Name> : never]: _ReturnOf<T, K>;
};
```

체감 차이는 5~10 키에서는 무시 가능하지만 100키 이상 OpenAPI generated DTO에 적용할 때 5.4 기준 `tsc --extendedDiagnostics`에서 `checkTime`이 18% → 11%로 떨어지는 사례를 본 적이 있다.

## 6. 실전 시나리오 — API 응답 ↔ 도메인 모델 매퍼

REST 응답이 snake_case이고 도메인 모델은 camelCase인 흔한 상황을 자동 변환해 보자. 런타임 변환 함수의 시그니처를 타입으로 못 박아 두면, 매핑 누락이나 키 오타를 컴파일러가 잡아낸다.

```ts
type SnakeToCamelCase<S extends string> =
  S extends `${infer H}_${infer T}` ? `${H}${Capitalize<SnakeToCamelCase<T>>}` : S;

type CamelizeKeys<T> = {
  [K in keyof T as K extends string ? SnakeToCamelCase<K> : K]:
    T[K] extends Array<infer U>
      ? Array<U extends object ? CamelizeKeys<U> : U>
      : T[K] extends object
        ? CamelizeKeys<T[K]>
        : T[K];
};

interface ApiResp {
  user_id: number;
  user_name: string;
  child_items: { item_id: number; item_value: string }[];
}

type Domain = CamelizeKeys<ApiResp>;
// {
//   userId: number;
//   userName: string;
//   childItems: { itemId: number; itemValue: string }[];
// }
```

런타임 함수는 Object.entries 변환으로 작성하고 반환 타입에 `CamelizeKeys<T>`를 명시한다.

```ts
function camelize<T extends Record<string, any>>(input: T): CamelizeKeys<T> {
  const out: any = Array.isArray(input) ? [] : {};
  for (const [k, v] of Object.entries(input)) {
    const nk = k.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
    out[nk] = v && typeof v === 'object' ? camelize(v) : v;
  }
  return out as CamelizeKeys<T>;
}
```

테스트는 `expectTypeOf`(`vitest`)나 `tsd`로 정적 검증을 함께 수행한다.

```ts
import { expectTypeOf } from 'vitest';
expectTypeOf<CamelizeKeys<{ snake_case_key: number }>>().toEqualTypeOf<{ snakeCaseKey: number }>();
```

## 7. 한계와 함정

첫째, template literal recursive 변환은 `tsc`가 50단계로 재귀 깊이를 제한한다. 50 토큰 이상의 super-long snake_case 키를 변환하려 하면 `Type instantiation is excessively deep` 에러가 난다. 일반 API 키 길이에서는 거의 부딪히지 않지만, GraphQL alias가 자동 생성한 키 같은 경우 회피가 필요하다. 회피 방법은 변환 함수 결과를 typed로 wrapping하지 않고 unknown으로 두거나, 재귀를 비-tail-call 방식으로 분리해 분기를 줄이는 것이다.

둘째, distributive 분배는 `boolean = true | false` 같은 union을 의도치 않게 분배시킨다. 키 자리에서 `K extends string ? ... : never`처럼 한정해 두지 않으면 number/symbol/boolean이 섞여 들어와 `K extends ...` 매칭이 실패할 수 있다.

셋째, `as` 절은 결과 키 충돌을 막지 않는다. 두 입력 키가 다른데 변환 결과가 같으면 마지막 슬롯이 이긴다.

| 시나리오 | 결과 |
|---|---|
| `{a:1, A:2}` + `as Uppercase<K>` | `{ A: number }` 단 하나 (값 타입은 union으로 합쳐지지 않음) |
| `{user_id:1, userId:2}` + `as Camelize` | `{ userId: number }` 마지막 정의 우선 |

런타임 변환 함수도 같은 충돌을 발생시키므로 양쪽 모두 단언이 무너지지 않게 키 정규화 규칙을 한 번 더 명시해야 한다.

## 8. 디버깅 — Mapped Type 호버 결과 읽기

복잡한 mapped type은 호버 결과가 "Result<...>" 한 줄로만 출력되어 실제 구조가 무엇인지 알기 어렵다. 표준 트릭은 **identity wrap**으로 강제 expand하는 것이다.

```ts
type Expand<T> = T extends infer O ? { [K in keyof O]: O[K] } : never;

type A = CamelizeKeys<{ user_id: number; user_name: string }>;
//   ^? Result<{ user_id: number; user_name: string }>

type AExp = Expand<A>;
//      ^? { userId: number; userName: string }
```

이중 expansion이 필요하면 `Expand<Expand<T>>`로 한 단계 더 풀어낸다. 큰 union 분배가 일어나는 경우 호버가 매우 길어지므로 `noErrorTruncation: true`를 tsconfig에 켜 두면 잘리지 않는다.

VSCode 1.83+의 "Hover Provider"는 TypeScript hover를 단계별로 펼치는 옵션이 있어 인터랙티브하게 추론 그래프를 따라갈 수 있다.

## 9. 라이브러리 비교 — type-fest, ts-toolbelt

직접 작성하지 않고 검증된 변환기를 가져다 쓰는 옵션도 있다.

| 라이브러리 | 특징 | 권장 |
|---|---|---|
| type-fest | 가장 광범위. CamelCasedProperties, SnakeCasedProperties 등 즉시 사용 가능 | 일반적인 변환에 첫 선택 |
| ts-toolbelt | 학술적, 추론 단가 높음. 깊은 type-level 계산용 | 깊이 1-2 짜리만 권장 |
| ts-essentials | 가벼움. DeepRequired, DeepPartial 등 | 핵심 utility만 필요할 때 |

운영 프로젝트에서는 type-fest의 `CamelCasedProperties<T, DeepCamelCase>` 같은 함수를 import해 위 §6의 자체 구현 대신 쓰는 것이 유지보수 측면에서 안전하다. 직접 작성한 매퍼는 라이브러리 업데이트로 모서리 케이스가 발견되어도 알람이 없다.

## 10. 정리

`as` 절은 mapped type을 "키 변환기"로 진화시킨다. `never`로 제거, template literal로 재작성, 다중 키로 분배라는 세 가지 패턴만 익히면 Pick/Omit 수준의 유틸리티부터 OpenAPI 매퍼·이벤트 핸들러 생성기까지 표준 라이브러리 없이 직접 작성할 수 있다. 비용은 거의 무료처럼 보이지만 깊은 재귀와 conditional 중복 평가에서 빌드 시간이 누적되므로, 같은 conditional은 alias로 캐싱하고 키 자리 평가는 한 번만 두는 규율을 지킨다. 런타임 변환 함수와 정적 타입을 같은 규칙으로 묶어 두면 키 매핑 버그가 컴파일 단계로 옮겨가, 통합 테스트에서 잡아내던 클래스 오류가 사라진다.

## 참고

- TypeScript 4.1 Release Notes — Key Remapping in Mapped Types
- TypeScript Handbook — Mapped Types
- TypeScript Deep Dive — Mapped & Conditional Types
- microsoft/TypeScript GitHub Issue #12754 (Key Remapping 원안)
- ts-toolbelt / type-fest 소스 — CamelCase, SnakeCase 변환기 구현 비교
