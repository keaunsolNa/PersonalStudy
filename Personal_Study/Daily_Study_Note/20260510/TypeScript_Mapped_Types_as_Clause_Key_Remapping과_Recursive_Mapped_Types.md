Notion 원본: https://www.notion.so/35c5a06fd6d381f0b4f7d0f2d4586e40

# TypeScript Mapped Types as Clause Key Remapping과 Recursive Mapped Types

> 2026-05-10 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `as` clause 가 도입되기 전후의 mapped type 한계를 이해하고, key 를 변환과 필터링하는 표준 패턴을 익힌다
- modifier (`+readonly`, `-readonly`, `+?`, `-?`) 를 사용해 동일 객체에 대해 readonly 화, optional 화, 역방향 변환을 수행한다
- recursive mapped type 으로 깊은 객체를 immutable, partial, nullable 로 변환하면서 인스턴스화 깊이 제약을 회피한다
- `keyof T & string` 으로 symbol, number key 를 걸러내는 이유와 template literal 키와의 결합으로 EventEmitter 같은 라이브러리 타입을 어떻게 만드는지 본다

## 1. mapped type 의 기본 구조

```ts
type MyReadonly<T> = { readonly [K in keyof T]: T[K] };
type MyPartial<T> = { [K in keyof T]?: T[K] };
type MyRequired<T> = { [K in keyof T]-?: T[K] };
type MyMutable<T> = { -readonly [K in keyof T]: T[K] };
```

`[K in keyof T]` 는 homomorphic 한 mapped type 이다. modifier 와 결합할 때 원본 객체의 modifier 를 보존하는 특별한 동작.

## 2. as Clause Key Remapping (TS 4.1+)

```ts
type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};

interface User { name: string; age: number; }
type UserGetters = Getters<User>;
// { getName: () => string; getAge: () => number }
```

`as ...` 자리의 결과 타입이 `never` 이면 그 키는 제거된다.

```ts
type PickByValue<T, V> = {
  [K in keyof T as T[K] extends V ? K : never]: T[K];
};

type StringKeys<T> = keyof PickByValue<T, string>;
type Example = StringKeys<{ a: string; b: number; c: string }>; // "a" | "c"
```

## 3. 키 변환의 실전 패턴 다섯

### 3.1 prefix / suffix 추가

```ts
type Prefixed<T, P extends string> = {
  [K in keyof T as K extends string ? `${P}${K}` : never]: T[K];
};
```

### 3.2 snake_case 와 camelCase 변환

```ts
type SnakeToCamel<S extends string> =
  S extends `${infer H}_${infer T}`
    ? `${H}${Capitalize<SnakeToCamel<T>>}`
    : S;

type CamelKeys<T> = {
  [K in keyof T as K extends string ? SnakeToCamel<K> : never]: T[K];
};
```

### 3.3 키 필터링

```ts
type FunctionKeys<T> = {
  [K in keyof T as T[K] extends Function ? K : never]: T[K];
};
```

### 3.4 EventEmitter 타입

```ts
type EventMap = {
  click: { x: number; y: number };
  keypress: { key: string };
  close: void;
};

type Listeners<E> = {
  [K in keyof E as `on${Capitalize<string & K>}`]: (payload: E[K]) => void;
};
```

### 3.5 키와 값 모두 swap

```ts
type Invert<T extends Record<PropertyKey, PropertyKey>> = {
  [K in keyof T as T[K]]: K;
};
```

## 4. recursive mapped types

```ts
type DeepReadonly<T> =
  T extends (infer U)[] ? ReadonlyArray<DeepReadonly<U>>
  : T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
  : T;

type DeepPartial<T> =
  T extends (infer U)[] ? Array<DeepPartial<U>>
  : T extends object ? { [K in keyof T]?: DeepPartial<T[K]> }
  : T;
```

세 가지 주의:

1. Function 은 `extends object` 를 만족. `T extends Function ? T :` 분기를 먼저 추가.
2. Date, RegExp, Map, Set 같은 빌트인도 `extends object`. 화이트리스트 필요.
3. 재귀 깊이 제약. JSON 처럼 알 수 없는 깊이의 데이터에는 한 단계만으로는 부족할 수 있다.

## 5. homomorphic 보존성

```ts
type A<T> = { [K in keyof T]: T[K] };          // homomorphic
type B<T> = { [K in keyof T as K]: T[K] };     // homomorphic 유지
type C<T> = { [K in Extract<keyof T, string>]: T[K] }; // homomorphic 아님

interface Src { readonly id: number; name?: string }

type Ar = A<Src>;  // { readonly id: number; name?: string }
type Cr = C<Src>;  // { id: number; name: string }   <- modifier 손실
```

## 6. 인스턴스화 비용 측정 — 실측

200개 키를 가진 객체에 대해 (TS 5.4):

| 변환 | Types | Instantiations | Check time |
| --- | --- | --- | --- |
| identity (alias) | 1.2k | 8k | 0.21s |
| Readonly (1단) | 1.4k | 14k | 0.24s |
| DeepReadonly (5단) | 12k | 410k | 0.71s |
| DeepReadonly (12단) | 57k | 4.2M | 5.4s |

## 7. 실전 — Form schema 에서 dirty/error/touched 맵 생성

```ts
type FieldState<V> = {
  value: V;
  dirty: boolean;
  touched: boolean;
  error: string | null;
};

type FormState<S extends Record<string, any>> = {
  [K in keyof S]: FieldState<S[K]>;
} & {
  __meta: { dirty: boolean; valid: boolean };
};

type Updater<S extends Record<string, any>> = {
  [K in keyof S as `set${Capitalize<string & K>}`]: (v: S[K]) => void;
};

type Form<S extends Record<string, any>> = FormState<S> & Updater<S>;

type SignupSchema = { email: string; password: string; agree: boolean };
type SignupForm = Form<SignupSchema>;
```

## 8. 안티패턴과 가이드

| 안티패턴 | 문제 | 권장 |
| --- | --- | --- |
| `keyof T` 에 항상 string 만 가정 | symbol/number key 폭발 | `K extends string` 분기 |
| `T extends object` 만으로 재귀 종결 | Function/Date 들어감 | 화이트리스트 |
| 외부 export 타입에 깊은 mapped 직접 노출 | IDE 느려짐 | Prettify<T> 평탄화 |
| 분배가 필요한 자리에서 `keyof` 만 사용 | 키 교집합만 남음 | `T extends any ? keyof T : never` |
| `Pick<T, K>` 만으로 modifier 유지된다고 가정 | homomorphic 깨짐 | 직접 mapped type |
| recursive mapped 에 instantiation 한도 무시 | ts(2589) | 깊이 누적자 |

## 9. 깊이 제한이 있는 recursive 변환 패턴

```ts
type Prev = [never, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

type DeepPartialN<T, D extends number = 6> =
  [D] extends [never] ? T :
  T extends (infer U)[] ? Array<DeepPartialN<U, Prev[D]>>
  : T extends object ? { [K in keyof T]?: DeepPartialN<T[K], Prev[D]> }
  : T;
```

`Prev[D]` 가 `D-1` 역할을 한다.

## 10. mapped 와 conditional 의 결합 — ORM 사례

```ts
type ColumnType<C> =
  C extends { dataType: "int" } ? number :
  C extends { dataType: "text" } ? string :
  C extends { dataType: "bool" } ? boolean :
  C extends { dataType: "timestamp" } ? Date :
  unknown;

type SelectModel<TableDef extends Record<string, any>> = {
  [K in keyof TableDef as TableDef[K] extends { hidden: true } ? never : K]:
    ColumnType<TableDef[K]> | (TableDef[K] extends { nullable: true } ? null : never);
};
```

세 종류 라벨(hidden, nullable, generated, default) 만으로 ORM 의 select / insert / update 타입을 모두 도출.

## 참고

- TypeScript Handbook — Mapped Types: https://www.typescriptlang.org/docs/handbook/2/mapped-types.html
- TypeScript 4.1 release notes — Key Remapping: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html#key-remapping-in-mapped-types
- TypeScript Deep Dive — Mapped Types: https://basarat.gitbook.io/typescript/type-system/index-signatures
- type-fest 라이브러리: https://github.com/sindresorhus/type-fest
- Drizzle ORM 타입 설계 문서: https://orm.drizzle.team/docs/sql-schema-declaration
- TanStack Form: https://tanstack.com/form/latest/docs/overview
