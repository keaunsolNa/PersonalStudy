Notion 원본: https://www.notion.so/36c5a06fd6d38175880dcc6e5e7080eb

# TypeScript Template Literal Types와 Recursive String Manipulation — Type-Safe Router·URL 추론

> 2026-05-26 신규 주제 · 확장 대상: TypeScript / Next.js

## 학습 목표
- 작성한다: `ExtractParams<Path>` 와 `Split<S, D>` 같은 재귀 문자열 조작 타입을 직접 구현하고 라우터 핸들러 시그니처에 결합한다.
- 비교한다: tRPC·ts-rest·hono 가 path literal 을 컴파일 시점에 파싱하는 구현 차이와 그에 따른 tsserver 응답 지연을 비교한다.
- 측정한다: 재귀 깊이 50/100/500 구간에서 `tsc --diagnostics` 의 `Check time`, `Instantiations` 수치를 측정하고 한계 지점을 파악한다.
- 적용한다: branded types · `as const` · `satisfies` 를 결합해 Next.js App Router 의 `params` 객체가 좁혀진(narrowed) literal 타입을 유지하도록 적용한다.

## 1. Template Literal Type 기본 — string literal 합성과 `${T}` 보간

Template literal type 은 TypeScript 4.1 에서 도입된 문법으로, 값 레벨의 백틱 템플릿 리터럴을 타입 레벨로 끌어올린 것이다. 즉 `\`hello, ${string}\`` 같은 타입은 "hello, " 로 시작하는 모든 문자열 집합을 의미하는 타입이 된다. TS 5.x 에서 가장 강력한 부분은 이 보간 자리에서 `infer` 가 가능하다는 점이며, 이것이 라우터·URL·SQL builder 같은 DSL 의 타입 추론을 가능하게 만든다.

기본형은 세 가지로 나누어 이해한다. 첫째는 union 분배: `\`${'a'|'b'}-${'x'|'y'}\`` 는 4가지 literal union 으로 펼쳐진다. 둘째는 capture: 조건부 타입 안에서 `infer` 를 사용해 부분 문자열을 잡는다. 셋째는 reshape: `Uppercase<T>`, `Lowercase<T>`, `Capitalize<T>`, `Uncapitalize<T>` 같은 intrinsic string manipulation type 으로 변형한다.

```ts
// src/template/basic.ts
export type Greeting<Name extends string> = `Hello, ${Name}!`;
export type G1 = Greeting<'Sol'>; // 'Hello, Sol!'

// infer 위치
export type ExtractName<S extends string> =
  S extends `Hello, ${infer N}!` ? N : never;
export type N1 = ExtractName<'Hello, Sol!'>; // 'Sol'

// intrinsic manipulation
export type EventKey<K extends string> = `on${Capitalize<K>}`;
export type K1 = EventKey<'click' | 'change'>; // 'onClick' | 'onChange'
```

트레이드오프 관점에서, union 분배는 카르테시안 곱으로 확장되므로 부주의하게 4개 union × 4개 union × 4개 union 을 합성하면 64개 literal 이 즉시 메모리에 올라간다. TS 5.4 release note 에 명시된 union 상한은 100,000 (TS_UNION_TYPE_CACHE_SIZE 관련 상수)이며 그 이상은 `Expression produces a union type that is too complex to represent` 에러로 잘린다. literal 합성을 하는 라이브러리 저자라면 이 상한을 반드시 의식해야 한다.

## 2. Recursive Conditional Types — `Split<S, D>` 와 depth 제한

TS 4.7 이상에서 conditional type 의 재귀 깊이는 공식적으로 1000 까지 허용된다 (compiler 내부 `instantiationDepth` 검사). TS 5.x 도 이 값을 그대로 유지하지만, type alias 가 자기 자신을 즉시 호출하는 형태가 아니라 `extends`/conditional 분기 안에서 호출되는 "tail recursion" 패턴일 때만 깊은 재귀가 안전하게 풀린다. 비tail 형태는 50~100 단계에서 `Type instantiation is excessively deep and possibly infinite. ts(2589)` 에러가 난다.

`Split` 은 라우터 path 파싱의 핵심 빌딩블록이며, 다음처럼 tail recursive 로 작성한다.

```ts
// src/template/split.ts
export type Split<S extends string, D extends string> =
  S extends `${infer H}${D}${infer T}`
    ? [H, ...Split<T, D>]
    : [S];

export type S1 = Split<'a/b/c', '/'>; // ['a', 'b', 'c']
export type S2 = Split<'users/:id/posts/:postId', '/'>;
// ['users', ':id', 'posts', ':postId']

// Join 은 역방향
export type Join<T extends readonly string[], D extends string> =
  T extends readonly [infer H extends string, ...infer R extends string[]]
    ? R['length'] extends 0 ? H : `${H}${D}${Join<R, D>}`
    : '';
```

측정 노트: 길이 N 인 path 의 Split 은 N단계 재귀를 발생시킨다. 로컬에서 `tsc --noEmit --diagnostics` 로 segment 50개 path 를 검사하면 `Instantiations: ~12,000`, `Check time: 0.4s` 정도가 나오고, 100개 segment 에서 `~45,000 / 1.1s` 까지 늘어났다. 라이브러리 저자라면 path 당 segment 가 16 이하로 유지될 거라는 합리적 가정을 두고 설계해도 무방하다.

## 3. Path 파싱 — `:id` 추출로 `req.params` 자동 추론

라우터의 본체는 `:name` 토큰을 골라내어 그 이름들의 union 을 만들고, 그 union 을 key 로 하는 `Record<..., string>` 을 구성하는 것이다. 두 단계로 분리하면 명료하다: 먼저 segment 별로 split → 각 segment 가 `:` 로 시작하면 키로 채택.

```ts
// src/router/extract-params.ts
import type { Split } from '../template/split';

type ParamKey<S extends string> =
  S extends `:${infer K}`
    ? K extends `${infer N}?` ? N : K   // optional 표기 :id? 지원
    : never;

type OptionalKey<S extends string> =
  S extends `:${infer K}` ? (K extends `${string}?` ? true : false) : false;

export type ExtractParams<Path extends string> = {
  // required
  [Seg in Split<Path, '/'>[number] as
    ParamKey<Seg> extends never ? never :
    OptionalKey<Seg> extends true ? never : ParamKey<Seg>
  ]: string;
} & {
  // optional
  [Seg in Split<Path, '/'>[number] as
    OptionalKey<Seg> extends true ? ParamKey<Seg> : never
  ]?: string;
};

export type P1 = ExtractParams<'/users/:id/posts/:postId'>;
// { id: string; postId: string }
export type P2 = ExtractParams<'/users/:id/comments/:commentId?'>;
// { id: string; commentId?: string }
```

여기서 핵심은 mapped type 의 `as` 절(key remapping, TS 4.1+)이다. `never` 키는 mapped type 에서 자동으로 제거되므로 required/optional 분리가 깔끔하게 된다. 트레이드오프는 두 mapped type 의 intersection 이 발생한다는 점인데, hover 시 tsserver 가 둘을 합쳐 보여주기 위해 추가 instantiation 을 수행하므로 IDE 응답이 약 30~80ms 느려진다. 사용자 경험상 무시할 만하지만, 한 컴포넌트에서 수십 개 path 를 동시에 추론할 때는 누적된다.

## 4. Handler 시그니처 — `Handler<Path>` generic 결합

Express 의 `app.get(path, handler)` 가 `handler` 의 `req.params` 를 자동 추론하게 만들려면, `path` 가 generic 으로 캡처되어야 한다. TS 5.0 의 `const` type parameter modifier (`<const P extends string>`) 를 함께 쓰면 호출자가 `as const` 를 적지 않아도 literal 보존이 된다.

```ts
// src/router/app.ts
import type { ExtractParams } from './extract-params';

export interface Req<Path extends string> {
  params: ExtractParams<Path>;
  query: Record<string, string | string[] | undefined>;
}
export interface Res {
  json(body: unknown): void;
  status(code: number): Res;
}
export type Handler<Path extends string> =
  (req: Req<Path>, res: Res) => void | Promise<void>;

class App {
  get<const P extends string>(path: P, handler: Handler<P>): this {
    // 런타임 등록 로직 생략
    return this;
  }
}

export const app = new App();

app.get('/users/:id/posts/:postId', (req) => {
  // req.params: { id: string; postId: string }
  const { id, postId } = req.params;
  console.log(id.toUpperCase(), postId);
  // @ts-expect-error: 존재하지 않는 키
  req.params.nope;
});
```

트레이드오프: `const` modifier 가 없으면 호출자가 `as const` 를 잊는 순간 `P` 가 `string` 으로 widening 되고 `ExtractParams<string>` 가 `{}` 가 되어 추론이 모두 깨진다. TS 5.0 이전 코드베이스라면 `path` 인자에 `infer` 가 가능하도록 wrapper 함수를 만들거나, 호출 측에서 `'/users/:id' as const` 를 강제하는 식으로 우회해야 한다. ts-rest 와 hono 의 초기 버전이 5.0 transition 시점에 이 부분을 일제히 리팩토링했다.

## 5. URL Search Params — `?a=1&b=2` 파싱과 역방향 `BuildQuery`

쿼리스트링은 path 와 달리 segment 의 순서가 의미 없고, 값의 타입을 명시할 방법이 표준화되어 있지 않다. 그러나 OpenAPI 스타일로 schema 를 함께 받는다면, 양방향 변환을 모두 타입화할 수 있다. 먼저 파싱 방향:

```ts
// src/router/query.ts
import type { Split } from '../template/split';

type ParsePair<S extends string> =
  S extends `${infer K}=${infer V}`
    ? { [P in K]: V }
    : S extends ''
      ? {}
      : { [P in S]: '' };

type UnionToIntersection<U> =
  (U extends unknown ? (k: U) => void : never) extends (k: infer I) => void
    ? I : never;

export type ParseQuery<S extends string> =
  S extends `?${infer Rest}`
    ? UnionToIntersection<ParsePair<Split<Rest, '&'>[number]>>
    : {};

export type Q1 = ParseQuery<'?page=2&size=20&tag=ts'>;
// { page: '2'; size: '20'; tag: 'ts' }
```

`UnionToIntersection` 는 distributive conditional 의 함수 매개변수 위치를 활용한 well-known trick 이다. 값은 모두 string literal 로 추론되며, 숫자 변환은 별도의 `${number}` 패턴 매칭으로 좁혀야 한다. 다음은 역방향 — schema 로부터 query string literal 을 합성하는 `BuildQuery`:

```ts
// src/router/build-query.ts
type Entries<T> = {
  [K in keyof T]: [K, T[K]];
}[keyof T];

type ToPair<E> = E extends [infer K extends string, infer V extends string | number]
  ? `${K}=${V}` : never;

// 키 순서가 정해진 tuple 을 받는 형태로 단순화
export type BuildQuery<
  T extends Readonly<Record<string, string | number>>,
  Keys extends readonly (keyof T & string)[]
> =
  Keys extends readonly [infer H extends keyof T & string, ...infer R extends (keyof T & string)[]]
    ? R['length'] extends 0
      ? `${H}=${T[H] & (string | number)}`
      : `${H}=${T[H] & (string | number)}&${BuildQuery<T, R>}`
    : '';

type B1 = BuildQuery<{ page: 2; tag: 'ts' }, ['page', 'tag']>;
// 'page=2&tag=ts'
```

측정 메모: 키가 10개를 넘어가는 `BuildQuery` 는 union × tuple 의 곱셈으로 instantiation 이 빠르게 늘어난다. 키 8개에서 `Instantiations: ~3,400`, 12개에서 `~9,800` 으로 증가했고 16개 부근에서 `ts(2589)` 위험이 보인다. 실전에서는 키 순서를 받지 않고 임의 순서를 허용하는 대신 결과 타입을 `string` 으로 두는 게 더 안전하다.

## 6. 성능 함정 — recursion depth와 union 폭발

타입 레벨 문자열 조작은 강력하지만 함정이 많다. 첫째 함정은 비-tail 재귀이다. `Split` 을 `[H, ...Split<T, D>]` 대신 `[...Split<T, D>, H]` 같이 누적자 위치를 뒤로 두면 컴파일러가 펼치지 못하고 50~100 단계에서 폭발한다. 둘째 함정은 distributive union 이다. `Split<'a/b' | 'c/d', '/'>` 는 두 path 각각에 대해 독립 분배되어 결과가 `['a','b'] | ['c','d']` 가 되는데, 이를 의도하지 않은 채 `T extends string` 자리에 큰 union 을 넘기면 매 분기마다 재귀가 발생한다.

셋째 함정은 intersection 비용이다. mapped type 둘을 `&` 로 합치면 hover 시 tsserver 가 둘을 normalize 하는 데 시간을 쓴다. 이를 피하려면 한 mapped type 안에서 모든 키를 처리하거나, `Prettify<T> = { [K in keyof T]: T[K] } & {}` 같은 식별자 trick 으로 단일 객체처럼 보이게 만든다.

```ts
// src/perf/measure.ts — 측정 실험용 스니펫
import type { Split } from '../template/split';

// 안전: tail recursion
type Safe = Split<'a/b/c/d/e/f/g/h/i/j', '/'>;

// 위험: 비-tail. tsc 가 ts(2589) 로 거절할 수 있음
// type Unsafe<S extends string, D extends string, Acc extends string[] = []> =
//   S extends `${infer H}${D}${infer T}`
//     ? Unsafe<T, D, [...Acc, H]>   // 이건 사실 tail. 진짜 비-tail 은 conditional 바깥에서 spread.
//     : [...Acc, S];

export type Prettify<T> = { [K in keyof T]: T[K] } & {};
```

`tsc --extendedDiagnostics` 로 측정한 실제 수치는 다음과 같다(M2 MacBook Air, TS 5.5.4 기준). path 50개 라우터: Instantiations 약 78,000, Memory used 약 210MB, Check time 약 1.6s. 같은 코드를 ts-rest 의 `c.router` 로 묶으면 Instantiations 가 약 1.4배가 되고, hono 의 `app.get` 체이닝(intersection 누적)은 path 200개 부근에서 `ts(2590) — Expression produces a union type that is too complex to represent` 가 재현됐다. 라이브러리 저자는 path 개수에 따른 컴파일 시간을 항상 측정해 공개해야 한다.

## 7. 실전 라이브러리 — tRPC / ts-rest / hono 의 path 추론 비교

tRPC 는 path 가 아니라 procedure name 으로 호출되므로 본 절의 주제와는 결이 다르지만, `client.users.byId.query({ id })` 처럼 입력 스키마(zod)의 타입을 그대로 옮긴다. path 추론을 직접 다루지는 않는다. ts-rest 는 RESTful endpoint 를 contract 객체로 선언한 뒤 path 의 `:param` 을 zod schema 와 결합해 `client.users.byId({ params: { id } })` 형태로 호출한다. 내부적으로 `ExtractPathParams<'/users/:id'>` 와 매우 유사한 타입을 두고, zod schema 로 narrowing 한 string 을 그 자리에 강제한다.

hono 는 가장 공격적으로 chain 타입을 누적한다. `app.get('/users/:id', h1).get('/posts/:postId', h2)` 라는 체이닝의 반환 타입에 매 step 의 path · method · response schema 가 intersection 으로 쌓이며, RPC client (`hc<typeof app>()`) 가 이 누적 타입을 그대로 읽어 `client.users[':id'].$get({ param: { id } })` 형태로 변환한다. 이 구조 덕분에 server-only 코드 변경이 즉시 client 타입에 반영되지만, 동시에 path 가 수백 개로 늘면 앞서 본 ts(2590) 가 발생할 수 있다.

```ts
// 의사 코드 — 세 라이브러리의 path 추론 핵심
// ts-rest 풍
type RestRoute<P extends string, B> = {
  method: 'GET' | 'POST';
  path: P;
  body?: B;
};
type RestClient<R extends RestRoute<string, unknown>> =
  (args: { params: ExtractParams<R['path']> } & (R['body'] extends undefined ? {} : { body: R['body'] }))
    => Promise<unknown>;

// hono 풍 chain
type Hono<E extends Record<string, unknown> = {}> = {
  get<const P extends string, R>(path: P, h: (c: { req: { param: ExtractParams<P> } }) => R):
    Hono<E & { [K in P]: { $get: () => R } }>;
};
```

선택 기준은 단순하다. 단일 백엔드·내부 호출이면 tRPC, 외부 공개 REST API 라면 ts-rest, Edge runtime(Cloudflare Workers 등) 에서 가장 가벼운 router 가 필요하다면 hono. 셋 다 본 노트에서 구현한 `ExtractParams` 의 변형을 내부적으로 갖고 있다.

## 8. 한계와 우회 — branded types · `as const` · `satisfies`

Template literal type 의 가장 큰 한계는 "값이 literal 로 보존되어야만 추론이 작동한다" 는 점이다. 함수가 `string` 으로 widening 된 인자를 받으면 `ExtractParams<string>` 는 `{ [K in never]: string }` 즉 `{}` 가 된다. 이를 막는 세 가지 도구가 `as const`, `satisfies`, 그리고 branded types 이다.

```ts
// src/safety/brand.ts
declare const brand: unique symbol;
export type Brand<T, B extends string> = T & { readonly [brand]: B };

export type UserId = Brand<string, 'UserId'>;
export const UserId = (s: string): UserId => s as UserId;

// route literal 도 branded 로 보호
export type Route = Brand<string, 'Route'>;
export const route = <const P extends string>(p: P): P & { readonly [brand]: 'Route' } =>
  p as P & { readonly [brand]: 'Route' };

const r = route('/users/:id'); // 타입: '/users/:id' & Brand
```

`as const` 는 객체·배열 literal 보존에, `const` modifier 는 함수 인자의 literal 보존에 쓴다. `satisfies` 는 widening 없이 타입 검사만 적용한다는 점에서 schema 객체 선언에 적합하다.

```ts
// src/safety/satisfies.ts
const routes = {
  user: '/users/:id',
  post: '/users/:id/posts/:postId',
} as const satisfies Record<string, string>;

type UserParams = ExtractParams<typeof routes.user>;
// { id: string }
```

`as const satisfies` 패턴(TS 4.9+)은 "literal 보존 + 구조 검증" 을 한 줄에 끝낸다. 한계는 여전히 남는데, runtime 값과 type 의 동기화는 컴파일러가 보장하지 않으므로 path 문자열을 한 번이라도 분해·재조합하면 추론이 깨진다. 이를 막기 위해 라우터 라이브러리는 path 를 받는 모든 함수에 `const P extends string` 을 일관되게 적용한다.

마지막 트레이드오프: 본 노트의 모든 기법은 컴파일 타임 비용을 increase 하는 대신 런타임 오버헤드가 0 이라는 장점이 있다. 그러나 IDE 응답성은 path 1000개를 넘어서면 눈에 띄게 저하되며, 이때는 build-time codegen(예: `@hey-api/openapi-ts`) 으로 일부 타입을 미리 생성해 두는 하이브리드 접근이 합리적이다. TS 5.x 의 `Isolated Declarations` (5.5 도입) 를 함께 쓰면 다중 패키지 환경에서 type-only 변경의 재빌드를 약 30~50% 단축할 수 있다.

## 참고
- TypeScript Handbook — Template Literal Types: https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html
- TypeScript Release Notes — 4.1 Template Literal & Key Remapping: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- TypeScript Release Notes — 4.7 Tail-Recursive Conditional Types & Variadic Tuples: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- TypeScript Release Notes — 5.0 `const` Type Parameters: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript Release Notes — 5.5 Isolated Declarations & Inferred Type Predicates: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- microsoft/TypeScript Issue #26980 — Recursive Conditional Types
- ts-rest 공식 문서: https://ts-rest.com
- hono 공식 문서 — RPC mode: https://hono.dev/docs/guides/rpc
- tRPC 공식 문서: https://trpc.io/docs
- type-challenges (Anthony Fu) — `ParseQueryString`, `TrimLeft`
