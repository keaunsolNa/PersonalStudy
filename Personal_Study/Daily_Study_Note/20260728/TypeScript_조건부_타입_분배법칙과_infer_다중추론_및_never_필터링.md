Notion 원본: https://app.notion.com/p/3ab5a06fd6d381e08f83faba36f69557

# TypeScript 조건부 타입 분배법칙과 infer 다중추론 및 never 필터링

> 2026-07-28 신규 주제 · 확장 대상: TypeScript(조건부 타입·타입 추론)

## 학습 목표

- 네이키드 타입 파라미터에서 조건부 타입이 유니온에 분배되는 규칙과 억제 조건을 구분한다.
- infer 가 공변·반공변 위치에서 유니온/인터섹션으로 갈라 추론되는 원리를 정리한다.
- never 가 유니온의 항등원으로서 필터링·매핑에 쓰이는 메커니즘을 파악한다.
- 실무 유틸리티 타입을 분배·infer·never 조합으로 직접 구현해 검증한다.

## 1. 분배 조건부 타입의 발동 조건

T extends U ? X : Y 에서 T 가 네이키드 타입 파라미터이고 유니온이면 조건부 타입이 각 멤버에 분배된다.

```ts
type ToArray<T> = T extends any ? T[] : never;
type A = ToArray<string | number>;   // string[] | number[]
```

분배를 끄려면 대괄호로 감싸 네이키드 상태를 깨뜨린다.

```ts
type ToArrayND<T> = [T] extends [any] ? T[] : never;
type B = ToArrayND<string | number>;   // (string | number)[]
type IsNever<T> = [T] extends [never] ? true : false;
type C1 = IsNever<never>;   // true
```

never 는 멤버 0개의 유니온이라 네이키드 조건부에 넣으면 분배 대상이 없어 결과가 never 로 붕괴한다. 그래서 never 판정에 대괄호가 필수다.

## 2. never 는 유니온의 항등원

T | never === T. 각 멤버를 검사해 통과하면 자신을, 탈락하면 never 를 반환하면 never 가 자동 소거되어 필터 결과만 남는다.

```ts
type MyExclude<T, U> = T extends U ? never : T;
type D = MyExclude<"a" | "b" | "c", "b">;   // "a" | "c"
type MyExtract<T, U> = T extends U ? T : never;
type MyNonNullable<T> = T extends null | undefined ? never : T;
```

## 3. infer — 매칭된 위치의 타입 추출

```ts
type ElementType<T> = T extends (infer E)[] ? E : T;
type ReturnTypeOf<T> = T extends (...args: any[]) => infer R ? R : never;
type Awaited1<T> = T extends Promise<infer V> ? V : T;
```

## 4. infer 위치가 만드는 유니온 vs 인터섹션

공변 위치(반환 타입) 다중 infer 는 유니온, 반공변 위치(파라미터) 다중 infer 는 인터섹션이 된다.

```ts
type Co<T> = T extends { a: infer U; b: infer U } ? U : never;
type K = Co<{ a: string; b: number }>;   // string | number

type UnionToIntersection<U> =
  (U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never;
type M = UnionToIntersection<{ a: 1 } | { b: 2 }>;   // { a: 1 } & { b: 2 }
```

앞부분이 유니온을 분배해 각 멤버를 파라미터 위치 함수로 만들고 뒤의 단일 infer I 가 반공변이라 인터섹션으로 합쳐진다.

## 5. 재귀 조건부 타입

```ts
type DeepReadonly<T> = T extends (infer E)[]
  ? ReadonlyArray<DeepReadonly<E>>
  : T extends object
    ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
    : T;
type DeepAwaited<T> = T extends Promise<infer V> ? DeepAwaited<V> : T;
```

재귀에는 인스턴스화 깊이 한계(약 50~100단계)가 있어 종료 조건을 명확히 두는 것이 핵심이다.

## 6. 실무 유틸리티 조합 예제

```ts
type FunctionKeys<T> = {
  [K in keyof T]: T[K] extends (...args: any[]) => any ? K : never;
}[keyof T];
```

매핑 타입이 각 키를 검사해 함수면 키 리터럴을 아니면 never 를 두고 [keyof T] 로 뽑으면 never 가 소거되어 함수 키만 남는다.

## 7. 정리 — 세 도구는 함께 작동한다

분배는 유니온을 멤버 단위로 쪼개고, infer 는 매칭 위치에서 타입을 끄집어내며(공변->유니온, 반공변->인터섹션), never 는 탈락 멤버를 지워 필터링을 완성한다.

## 8. 문자열 파싱 — infer 와 템플릿 리터럴의 결합

```ts
type PathParams<T extends string> =
  T extends `${string}:${infer Param}/${infer Rest}`
    ? Param | PathParams<`/${Rest}`>
    : T extends `${string}:${infer Param}` ? Param : never;
type R = PathParams<"/users/:userId/posts/:postId">;   // "userId" | "postId"

type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}` ? [Head, ...Split<Tail, D>] : [S];
type S = Split<"a,b,c", ",">;   // ["a", "b", "c"]
```

tRPC·라우터 라이브러리가 경로 문자열로부터 핸들러 인자 타입을 유도할 때 쓰는 핵심 메커니즘이다.

## 9. 실무 주의 — 분배가 만드는 함정과 성능

boolean 은 내부적으로 true | false 유니온이라 조건부에 넣으면 분배되어 갈라진다.

```ts
type Wrap<T> = T extends any ? { v: T } : never;
type W = Wrap<boolean>;   // { v: true } | { v: false }
```

깊은 재귀·대형 유니온 분배는 컴파일러 인스턴스화를 폭증시켜 IDE·빌드를 느리게 한다. 표현할 수 있다와 실용적으로 빠르다는 다르다.

## 10. 종합 예제 — 중첩 키 경로 타입

```ts
type Paths<T, Prev extends string = ""> = T extends object
  ? { [K in keyof T & string]:
      Prev extends "" ? K | Paths<T[K], K> : `${Prev}.${K}` | Paths<T[K], `${Prev}.${K}`>;
    }[keyof T & string]
  : never;
```

이 하나의 타입에 분배·매핑·never 소거·재귀 종료·템플릿 리터럴이 모두 들어 있다. 깊이·유니온 크기 한계에 빠르게 부딪히므로 필요한 서브트리에만 적용하는 절제가 필요하다.

## 참고

- TypeScript Handbook — Conditional Types — https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript Handbook — Mapped Types — https://www.typescriptlang.org/docs/handbook/2/mapped-types.html
- TypeScript 소스 lib.es5.d.ts — Exclude / Extract / NonNullable
- type-challenges — https://github.com/type-challenges/type-challenges
