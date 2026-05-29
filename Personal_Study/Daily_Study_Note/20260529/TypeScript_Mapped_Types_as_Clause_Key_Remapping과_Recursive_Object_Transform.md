Notion 원본: https://www.notion.so/36f5a06fd6d38161853cc6694fce1299

# TypeScript Mapped Types as Clause Key Remapping과 Recursive Object Transform

> 2026-05-29 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Mapped type 의 `as` 절이 key 를 재매핑·필터링하는 정확한 의미를 익힌다
- `template literal type` 과 결합해 setter/getter, snake↔camel 변환을 type-level 로 표현한다
- Recursive mapped type 의 depth limit 와 stack overflow 회피 패턴을 적용한다
- 표준 `Pick`, `Omit`, `Record` 와의 호환과 inference 의 trade-off 를 점검한다

## 1. Mapped Type 과 `as` 절의 기본

Mapped type 의 골격은 `{ [K in keyof T]: U }` 형태로, key 집합을 그대로 유지한다. TS 4.1 에서 도입된 `as` clause 는 이 key 를 변환하거나 제외할 수 있게 한다.

```typescript
type RemapKeys<T, F extends (k: keyof T) => PropertyKey>
  = { [K in keyof T as F extends (k: K) => infer R ? R & PropertyKey : never]: T[K] };
```

`as` 가 `never` 로 평가되면 그 key 는 결과 타입에서 사라진다. 이게 type-level `filter` 의 기본 원리다.

```typescript
type Sample = { id: number; name: string; createdAt: Date };

type OnlyStrings<T> = {
  [K in keyof T as T[K] extends string ? K : never]: T[K]
};

type R = OnlyStrings<Sample>;
```

## 2. Template Literal Type 과 결합 — Getter/Setter 시뮬레이션

```typescript
type Getters<T> = {
  [K in keyof T as `get${Capitalize<K & string>}`]: () => T[K]
};

type Setters<T> = {
  [K in keyof T as `set${Capitalize<K & string>}`]: (v: T[K]) => void
};

type Proxy<T> = T & Getters<T> & Setters<T>;
```

`K & string` 이 필요한 이유는 `keyof T` 가 `string | number | symbol` 인데, template literal 의 placeholder 는 `string` 만 받기 때문이다. `Capitalize<S>` 는 4.1 부터 빌튼 intrinsic 이다.

## 3. Snake ↔ Camel 변환

```typescript
type SnakeToCamel<S extends string> =
  S extends `${infer Head}_${infer Tail}`
    ? `${Head}${Capitalize<SnakeToCamel<Tail>>}`
    : S;

type CamelKeys<T> = {
  [K in keyof T as SnakeToCamel<K & string>]: T[K]
};
```

역방향은 더 까다롭다. camelCase 는 단어 경계가 대문자뿐이라 conditional 의 패턴 매칭이 character 단위로 들어간다.

```typescript
type CamelToSnake<S extends string> =
  S extends `${infer C}${infer Rest}`
    ? C extends Uppercase<C>
      ? `_${Lowercase<C>}${CamelToSnake<Rest>}`
      : `${C}${CamelToSnake<Rest>}`
    : S;
```

## 4. Recursive Mapped Type 의 depth limit

TS 컴파일러는 conditional type 의 instantiation depth 를 50 까지 허용한다. tail-recursion 형태로 작성해 컴파일러가 stack frame 을 재사용하게 한다.

```typescript
type DeepReadonly<T> = T extends Function
  ? T
  : T extends object
  ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
  : T;
```

`Function` 을 먼저 분기하지 않으면 callable object 의 `apply`, `bind` 같은 메서드까지 readonly 가 되어 호출이 거부된다.

```typescript
type DeepKeys<T> = T extends object
  ? { [K in keyof T & string]: `${K}` | `${K}.${DeepKeys<T[K]>}` }[keyof T & string]
  : never;
```

이 패턴은 `[keyof T & string]` 으로 distribute 한 결과를 곧바로 union 으로 닫아 깊은 호출에서도 type alias 가 한 번에 펼쳐진다.

## 5. 조건부 필터링 — Optional 필드만 추출

```typescript
type RequiredKeys<T> = {
  [K in keyof T]-?: {} extends Pick<T, K> ? never : K
}[keyof T];

type OptionalKeys<T> = {
  [K in keyof T]-?: {} extends Pick<T, K> ? K : never
}[keyof T];

type PickRequired<T> = Pick<T, RequiredKeys<T>>;
type PickOptional<T> = Pick<T, OptionalKeys<T>>;
```

핵심 트릭은 `{} extends Pick<T, K>` 다. `K` 가 optional 이면 `Pick<T, K>` 는 `{}` 를 subtype 으로 받아들이고, required 면 받지 않는다. `-?` 는 mapped modifier 로 optional 을 제거해 정확히 판정하기 위한 normalize 다.

## 6. 함수 시그니쳐 변환

```typescript
type Asyncified<T> = {
  [K in keyof T]: T[K] extends (...args: infer A) => infer R
    ? (...args: A) => Promise<Awaited<R>>
    : T[K]
};
```

`Awaited<R>` 가 중요하다. 원래 `R` 이 `Promise<X>` 면 unwrap 해서 `Promise<X>` 가 중첩되지 않게 한다. 이게 없으면 `Promise<Promise<...>>` 가 된다.

## 7. Pick · Omit 과의 호환 — 표준 유틸리티 재정의

표준 `Omit` 은 사실 `Pick<T, Exclude<keyof T, K>>` 다. `as` 절을 쓰면 다음처럼 직접 정의할 수 있다.

```typescript
type Omit2<T, K extends keyof T> = {
  [P in keyof T as P extends K ? never : P]: T[P]
};
```

차이가 보이는 경우가 있다. 표준 `Omit` 은 `K` 가 `keyof T` 의 subset 일 것을 강제하지 않는다(`K extends keyof any`). 그래서 오타로 존재하지 않는 key 를 넘겨도 컴파일러가 잡지 못한다. 위 `Omit2` 는 `K extends keyof T` 로 좁혀 잡아낸다.

## 8. 운영 관점 — IDE 성능과 inference 비용

Mapped type 이 깊어지면 IDE 의 hover, autocomplete 가 느려진다. TS 5.x 의 `--generateTrace` 옵션으로 트레이스를 떠 보면, 한 type alias 의 instantiation 이 수천 회로 늘어나는 경우가 흔하다.

```bash
tsc --noEmit --generateTrace trace --incremental false
npx @typescript/analyze-trace trace
```

가장 흔한 cost driver 는 다음 셋이다. (a) recursive deep type 이 `keyof` 의 모든 element 에 대해 평가됨, (b) template literal 이 character 단위 재귀로 풀림, (c) union 의 distribution 이 큰 N×M 곱으로 폭발. 완화책은 (a) tail-recursion + accumulator, (b) snake↔camel 같은 string 변환을 빌드타임 codegen 으로 옮김, (c) `[K in keyof T] = ...` 의 결과를 `type X = { ... }` 로 한 번 alias 해 캐시.

마지막으로 inference 영향을 본다. mapped type 의 결과를 함수 generic 으로 받으면 TS 가 mapped 구조를 역추론해야 하는데, `as` 절이 들어가면 inference 가 닫혀 generic 이 `unknown` 으로 떨어지는 경우가 있다.

## 참고

- TypeScript Handbook — Mapped Types and Key Remapping via `as`
- TypeScript 4.1 release notes — Template Literal Types and Key Remapping
- TypeScript 5.0 release notes — `--moduleResolution bundler`, decorator stage 3
- Microsoft/TypeScript performance wiki — analyze-trace, generateTrace
