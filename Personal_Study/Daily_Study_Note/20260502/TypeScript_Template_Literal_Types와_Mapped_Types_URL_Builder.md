Notion 원본: https://www.notion.so/3545a06fd6d381c7b947f4e9f1b804a0

# TypeScript Template Literal Types와 Mapped Types로 만드는 Type-Safe URL Builder

> 2026-05-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Template Literal Types로 URL 패턴에서 path parameter 키를 추출한다
- Mapped Types와 Conditional Types 조합으로 query string 타입을 정적으로 검증한다
- `infer`와 분배 조건부 타입(distributive conditional types)으로 재귀 파싱을 작성한다
- TS 4.1+의 string manipulation 유틸과 v5.4의 `NoInfer`를 실제 라이브러리에서 활용한다

## 1. Template Literal Types 기초와 길이 한계

TypeScript 4.1에서 도입된 Template Literal Types는 런타임 문자열 리터럴 합성을 타입 수준으로 끌어올린다. 단순한 결합부터 시작해 path 패턴 파싱까지 확장 가능하다. 컴파일러는 내부적으로 type-level string을 char-by-char로 처리하지 않고 segment 단위로 분해하며, 이 때문에 너무 깊은 union이나 100,000개 이상의 분기는 `Type instantiation is excessively deep` 오류를 낸다. TS 5.0부터 reverse mapped types와 instantiation depth 제한이 50으로 강화되어, 실용 라이브러리는 보통 segment 깊이 7~8을 한계로 본다.

```ts
type Greet<T extends string> = `hello, ${T}`
type A = Greet<'world'>            // "hello, world"
type B = Greet<'A' | 'B'>          // "hello, A" | "hello, B"  (분배)

type Path = `/users/${string}/posts/${string}`
const ok: Path = '/users/42/posts/100'   // OK
// const ng: Path = '/users/42'          // Error
```

분배 동작은 union 입력이 들어오면 자동으로 cross-product 가 만들어지는 현상이다. 모든 조합을 만들 의도가 아니면 `[T] extends [U]` 처럼 튜플로 감싸 분배를 막는다.

## 2. Path Parameter 추출 — `infer` 기반 재귀 파싱

`/users/:id/posts/:postId` 형태에서 `{id: string, postId: string}` 객체 타입을 컴파일 타임에 도출하는 패턴은 tRPC, TanStack Router, hono, Express의 typed wrapper 등에 모두 공통으로 등장한다. 핵심은 재귀 conditional type에서 `infer`로 prefix·param·rest를 분해해 union으로 누적하는 방식이다.

```ts
type ExtractParam<Path extends string> =
  Path extends `${infer _Pre}:${infer Param}/${infer Rest}`
    ? Param | ExtractParam<`/${Rest}`>
    : Path extends `${infer _Pre}:${infer Param}`
      ? Param
      : never

type PathParams<Path extends string> = {
  [K in ExtractParam<Path>]: string
}

type R = PathParams<'/users/:id/posts/:postId'>
// { id: string; postId: string }
```

`Param` 변수가 슬래시를 만나기 전까지의 토큰을 흡수하기 때문에, `:id-suffix` 같이 dash가 섞여도 그대로 식별자로 잡힌다. 이를 막으려면 `/`, `?` 외에 dash 분기를 한 단계 더 두면 된다. 재귀 깊이는 약 45 단계에서 컴파일러 한계에 도달하므로, 100 segment 가 넘는 path 는 별도 어휘분석기를 만들지 말고 string 으로 둔다.

## 3. Path별 Method·Body·Response의 Type-Safe 매핑

OpenAPI나 직접 정의한 라우트 테이블을 단일 타입에서 관리할 때, key가 path 패턴이고 value가 메서드별 입출력 스키마인 mapped type을 쓴다.

```ts
type Routes = {
  '/users/:id': {
    GET: { params: { id: string }; response: { id: string; name: string } }
    DELETE: { params: { id: string }; response: void }
  }
  '/posts': {
    GET: { query: { page?: number }; response: Post[] }
    POST: { body: PostInput; response: Post }
  }
}

type ClientFn<R extends Routes> = <
  P extends keyof R,
  M extends keyof R[P]
>(
  path: P,
  method: M,
  init: Omit<R[P][M], 'response'>
) => Promise<R[P][M] extends { response: infer X } ? X : never>
```

호출부에서 path와 method를 입력하면 IDE가 정확히 그 라우트의 `params/body/query` 만 자동완성하고, 응답 타입까지 추론된다. 빌드 시점에 path 오타가 잡히는 것이 핵심 효용이다.

## 4. Query String 타입 안전 빌더

Template literal 안에서 `?key=...&key2=...` 패턴을 강제하면, query 객체에서 누락된 키를 컴파일 시점에 잡을 수 있다.

```ts
type ToQS<T extends Record<string, string | number | boolean>> = {
  [K in keyof T & string]: `${K}=${T[K] extends string | number | boolean ? T[K] : never}`
}[keyof T & string]

type JoinAll<U extends string, Acc extends string = ''> =
  [U] extends [never]
    ? Acc
    : U extends infer Head extends string
      ? Acc extends ''
        ? `${Head}${JoinAll<Exclude<U, Head>>}`
        : `${Acc}&${Head}${JoinAll<Exclude<U, Head>>}`
      : never

declare function buildUrl<P extends string, Q extends Record<string, string | number | boolean>>(
  path: P,
  query: Q
): `${P}?${JoinAll<ToQS<Q>>}`

const u = buildUrl('/search', { q: 'ts', page: 1 })
// "/search?q=ts&page=1" 형태로 추론, 단 union 순서는 비결정적
```

union 순서가 컴파일러 내부 hashing 에 의존하므로, 결과 문자열의 타입 자체가 deterministic 하지 않다. 따라서 "정확한 형태의 string literal을 비교"하는 용도가 아니라 "필요한 키가 모두 들어 있는가" 만 검증하는 데 쓰는 편이 안전하다.

## 5. Snake/Camel 케이스 변환과 키 이름 매핑

`Uppercase`·`Lowercase`·`Capitalize`·`Uncapitalize` 4 종 기본 유틸은 컴파일러 intrinsic 으로, 일반 conditional type 보다 빠르게 동작한다. snake_case → camelCase 변환은 흔한 응용이다.

```ts
type Camel<S extends string> =
  S extends `${infer H}_${infer T}`
    ? `${H}${Capitalize<Camel<T>>}`
    : S

type CamelKeys<T> = {
  [K in keyof T as K extends string ? Camel<K> : K]: T[K]
}

type X = CamelKeys<{ user_id: number; first_name: string }>
// { userId: number; firstName: string }
```

API gateway에서 snake로 오는 응답을 frontend 에서 camel로 받을 때 zod·valibot 의 `transform` 과 결합하면 런타임/타입 양쪽이 동기화된다. 다만 한국어가 섞인 키나 emoji 가 들어가면 intrinsic 도 그대로 통과시키므로 별도 normalization 이 필요하다.

## 6. NoInfer와 Generic Hint 분리 (TS 5.4)

TS 5.4 에 추가된 `NoInfer<T>` 는 generic 추론 위치에서 특정 위치만 추론 후보에서 제거한다. URL Builder 에서 path 의 param 키를 1 차 추론한 뒤, body/query 객체를 검증할 때 흔히 쓴다.

```ts
declare function defineHandler<P extends string, Q>(
  path: P,
  cfg: { handler: (params: PathParams<P>, query: NoInfer<Q>) => unknown }
): void

defineHandler('/users/:id', {
  // Q 가 handler 호출 시점이 아니라 cfg.handler 의 query 매개변수로만 결정됨
  handler: (params, query) => { /* params.id 만 자동완성 */ }
})
```

`NoInfer` 없이는 `handler` 의 query 매개변수가 `unknown` 으로 좁혀지면서 컴파일러가 양 위치를 동시에 후보로 삼아 의도와 다른 추론을 한다.

## 7. 성능과 컴파일 시간 — 실측치 기준 가이드

타입-레벨 파서는 컴파일러에 그대로 부담이 된다. tsserver 의 평균 type-checking time 은 small project 1~2 s, large monorepo 8~30 s 에 분포한다. 다음 패턴을 피하면 30 % 이상 단축된다.

| 패턴 | 영향 | 대안 |
| --- | --- | --- |
| 깊이 50+ 재귀 conditional | instantiation depth 한계 도달 | tail-recursive 형태로 Acc 누적 |
| `[K in U]: {...}` 안에서 다시 `K extends ...` 재추론 | 폭주 | Helper 타입으로 분리 후 캐싱 |
| Long string literal union (1000+) | Memory 폭증 | Branded type or `string & {}` |
| `infer X extends string` 누락 | Implicit any | 항상 constraint 명시 |

VS Code 의 `TypeScript: Open TS Server log` 와 `tsc --extendedDiagnostics` 의 `Check time` 항목으로 핫스팟을 잡고, `--generateTrace` 로 chrome://tracing 에서 시각화하면 어떤 재귀가 비용이 큰지 확인할 수 있다.

## 8. 실전 예: 라우터 정의를 자동완성과 런타임 검증에 동시에 쓰기

zod 스키마와 path 패턴을 한 곳에서 정의해 클라이언트·서버 양쪽에서 import 하면, 변경이 한 군데서 일어난다.

```ts
import { z } from 'zod'

export const routes = {
  'GET /users/:id': {
    response: z.object({ id: z.string(), name: z.string() })
  },
  'POST /users': {
    body: z.object({ name: z.string().min(1) }),
    response: z.object({ id: z.string() })
  }
} as const satisfies Record<string, { body?: z.ZodTypeAny; response: z.ZodTypeAny }>

type RouteKey = keyof typeof routes
type Method<K extends RouteKey> = K extends `${infer M} ${string}` ? M : never
type Path<K extends RouteKey>   = K extends `${string} ${infer P}` ? P : never

type Body<K extends RouteKey>     = (typeof routes)[K] extends { body: infer B extends z.ZodTypeAny } ? z.infer<B> : never
type Response<K extends RouteKey> = z.infer<(typeof routes)[K]['response']>

declare function call<K extends RouteKey>(
  key: K,
  init: { params: PathParams<Path<K>>; body: Body<K> }
): Promise<Response<K>>

await call('POST /users', { params: {} as never, body: { name: 'hi' } })
```

`as const satisfies` 는 TS 4.9 에서 추가된 패턴으로, 객체 리터럴의 정확한 타입을 유지하면서도 추가 제약을 검증해 준다. 이로써 IDE 자동완성과 zod 런타임 검증이 단일 source of truth 에서 파생된다.

## 9. 자주 빠지는 함정과 디버깅 팁

`infer` 가 들어간 conditional 의 입력 타입이 union 일 때 분배가 일어나 의도와 다른 결과가 만들어질 수 있다. 분배를 끄려면 `[T] extends [U]` 로 감싸고, 의도적으로 union 별로 처리하려면 그대로 둔다. `infer X extends string` 의 constraint 를 빼면 X 가 `any` 가 되어 후속 conditional 가 항상 true 로 평가되는 함정이 있다.

오류 메시지가 길어 읽기 힘들 때는 helper 타입에 결과를 한 번 alias 해 두고 hover 로 확인한다. `type _Debug<T> = T` 패턴은 자주 쓰인다. 그래도 한계에 부딪히면 `tsc --listFiles --traceResolution` 으로 의존성을 점검하고, `--diagnostics` 로 어떤 파일이 가장 비싼지를 본 뒤 helper 를 더 작은 단위로 쪼갠다.

## 참고

- TypeScript Handbook — Template Literal Types <https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html>
- TypeScript 5.4 Release Notes — `NoInfer` <https://devblogs.microsoft.com/typescript/announcing-typescript-5-4/>
- Matt Pocock, Total TypeScript Workshop materials (free chapters) <https://www.totaltypescript.com/>
- TanStack Router — Type-safe routing implementation <https://tanstack.com/router>
- tRPC v11 internals on path inference <https://trpc.io/docs>
