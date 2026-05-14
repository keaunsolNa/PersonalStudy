Notion 원본: https://www.notion.so/3605a06fd6d381f9abb4e12960a11081

# TypeScript Template Literal Types와 String Manipulation — 라우트 경로에서 핸들러 파라미터 타입을 추론하기

> 2026-05-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Template Literal Types 가 `infer` 와 어떻게 결합해 *문자열을 토큰으로 분해* 하는지 파악
- intrinsic string manipulation (`Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize`) 의 컴파일러 처리 비용 이해
- Express / Hono / Next.js 라우트 패턴 `/users/:id/posts/:postId` 에서 `{ id: string; postId: string }` 을 *자동 추론*
- 분포(distribution) 와 재귀의 instantiation depth 한계를 인식하고 안전한 우회 패턴 사용

## 1. Template Literal Type 의 기본 동작

TS 4.1 부터 *string 리터럴 타입* 끼리 합칠 수 있다.

```ts
type Greeting = "hello";
type Name = "world";
type Hello = `${Greeting}, ${Name}!`; // "hello, world!"
```

placeholder 위치에 union 이 오면 *카르테시안 분포* 가 일어난다.

```ts
type Lang = "ko" | "en";
type Page = "home" | "about";
type Route = `/${Lang}/${Page}`;
// "/ko/home" | "/ko/about" | "/en/home" | "/en/about"
```

union 두 개의 곱집합이 되므로 *조합 수 폭발* 에 주의해야 한다. `Lang` 이 100개, `Page` 가 100개면 10,000 개 리터럴이 생성되어 컴파일러가 즉시 느려진다. 실무에서 분포 대상은 보통 10개 이하로 유지하거나, 분포 대신 *infer pattern* 으로 우회한다.

## 2. infer 로 문자열 *분해*

template literal type 안에서 `infer` 를 쓰면 정규식의 capture group 처럼 부분 문자열을 빼낼 수 있다.

```ts
type Head<S extends string> =
    S extends `${infer H}/${infer _Rest}` ? H : S;

type A = Head<"users/123/posts">; // "users"
type B = Head<"users">;           // "users"
```

`${infer H}/${infer Rest}` 패턴은 "첫 번째 슬래시 직전까지를 H 로, 이후를 Rest 로" 캡처한다. 정규식의 lazy match 와 같이 *최소 매칭* 으로 잡힌다. `Head<S>` 를 재귀적으로 호출하면 split 함수처럼 동작한다.

```ts
type Split<S extends string, D extends string> =
    S extends `${infer Head}${D}${infer Tail}`
        ? [Head, ...Split<Tail, D>]
        : [S];

type Parts = Split<"a/b/c/d", "/">; // ["a", "b", "c", "d"]
```

이 재귀는 *tail position* 에 있으므로 TS 4.5 의 *tail-call elimination* 으로 평탄화된다. accumulator 패턴이 아니어도 1000 토큰까지 안전하게 처리 가능한 이유다.

## 3. 라우트 경로에서 파라미터 추출

Express 스타일의 `/users/:id/posts/:postId` 에서 동적 세그먼트만 뽑아낸다.

```ts
type ExtractParams<S extends string> =
    S extends `${string}:${infer Param}/${infer Rest}`
        ? Param | ExtractParams<`/${Rest}`>
        : S extends `${string}:${infer Param}`
            ? Param
            : never;

type P = ExtractParams<"/users/:id/posts/:postId">;
// "id" | "postId"
```

세 단계로 읽으면 된다. 첫 분기: `:foo/...` 패턴 → 토큰 한 개와 나머지 분리. 둘째 분기: trailing 토큰 `:bar` → 마지막 한 개. else: 정적 세그먼트뿐 → never.

이걸 mapped type 으로 감싸면 *완전한 핸들러 파라미터 객체 타입* 이 된다.

```ts
type ParamsOf<S extends string> = { [K in ExtractParams<S>]: string };

type R = ParamsOf<"/users/:id/posts/:postId">;
// { id: string; postId: string }
```

## 4. 타입 안전한 미니 라우터 만들기

위 추론을 핸들러 시그니처에 묶으면 라우트 경로 변경이 *컴파일러 에러* 로 즉시 드러난다.

```ts
type Handler<S extends string> =
    (req: { params: ParamsOf<S>; query: Record<string, string> }) => Response;

interface Router {
    get<S extends string>(path: S, handler: Handler<S>): void;
}

declare const router: Router;

router.get("/users/:id", (req) => {
    req.params.id;       // string
    req.params.userId;   // ❌ Property 'userId' does not exist
    return new Response();
});

router.get("/orders/:orderId/items/:itemId", (req) => {
    const { orderId, itemId } = req.params; // 둘 다 string
    return new Response();
});
```

런타임 라우터 구현은 그대로 두고 *타입만* template literal types 로 강화한다. Hono, tRPC v11, TanStack Router 가 이 패턴을 본격적으로 채택했고 Next.js 15 의 typed routes 도 비슷한 추론을 컴파일러 플러그인으로 수행한다.

## 5. 옵셔널 / wildcard 세그먼트 처리

실제 라우팅은 `:id?`, `*`, `*splat` 같은 변형이 있다. 추론 룰을 늘려서 처리한다.

```ts
type ExtractParam<Token extends string> =
    Token extends `:${infer Name}?` ? { [K in Name]?: string } :
    Token extends `:${infer Name}`  ? { [K in Name]:  string } :
    Token extends `*${infer Name}`  ? { [K in Name]:  string[] } :
    {};

type Tokenize<S extends string> =
    S extends `${infer Head}/${infer Tail}` ? [Head, ...Tokenize<Tail>] : [S];

type ParamsOf2<S extends string> =
    Tokenize<S> extends infer Tokens extends string[]
        ? UnionToIntersection<{ [I in keyof Tokens]: ExtractParam<Tokens[I]> }[number]>
        : {};

type UnionToIntersection<U> =
    (U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never;
```

`UnionToIntersection` 은 *contravariant 위치에서 union 이 intersection 으로 뒤집힌다* 는 변성 트릭으로 표준 패턴이다. 각 토큰을 개별 객체로 변환한 뒤 intersection 으로 합쳐 `{ id: string; name?: string; splat: string[] }` 같은 정밀한 타입을 만든다.

## 6. intrinsic string manipulation 의 위치

TS 는 네 개의 *intrinsic* 타입을 제공한다: `Uppercase<S>`, `Lowercase<S>`, `Capitalize<S>`, `Uncapitalize<S>`. 이들은 일반 conditional type 이 아니라 *컴파일러 내장 함수* 다. 따라서 일반 type-level 코드와 비용 특성이 다르다.

```ts
type Method = "get" | "post" | "delete";
type UpperMethod = Uppercase<Method>; // "GET" | "POST" | "DELETE"

type EventName<S extends string> = `on${Capitalize<S>}`;
type OnClick = EventName<"click">; // "onClick"
```

내부적으로 *문자열 노드를 즉시 변환* 하므로 `Uppercase<S>` 는 `S` 의 길이에 무관하게 한 번에 결과를 만든다. 재귀 instantiation 없음. 반대로 우리가 작성한 `Split` 같은 재귀형은 토큰 수만큼 instantiation 이 쌓이므로 *길이가 매우 길면* `ts2589` 가 난다. 어느 쪽이든 *컴파일 속도* 가 우려되면 intrinsic 우선.

## 7. CSS 변수 / 클래스 이름 같은 정형 문자열 검증

배포 직전에야 발견되는 오타를 미리 잡는 흔한 활용처가 design system 의 클래스/토큰이다.

```ts
type Spacing = 0 | 1 | 2 | 4 | 8 | 12 | 16;
type Side = "t" | "r" | "b" | "l" | "x" | "y";
type TwSpacing = `p${Side | ""}-${Spacing}`;
// "p-0" | "p-1" | ... | "py-16" | "pl-12" ...

declare function cn(...classes: TwSpacing[]): string;

cn("p-4", "px-2");   // OK
cn("p-3");           // ❌ 3 은 허용 spacing 아님
cn("pa-4");          // ❌ "a" 는 Side 아님
```

Tailwind 의 공식 안전 패턴은 아니지만, 디자인 시스템 토큰을 *컴파일 타임에 화이트리스트* 로 강제할 때 유효하다. 단 union 폭발에 주의 — Side 7개 × Spacing 7개 = 49개로 멈춰야 한다. 200개를 넘어가면 IDE 자동완성이 무거워진다.

## 8. SQL/URL fragment 의 타입 안전 빌더

GraphQL field selection, SQL `SELECT` 컬럼 등을 문자열로 다룰 때 *유효한 컬럼명* 만 허용하도록 강제 가능.

```ts
type UserRow = { id: string; name: string; email: string; createdAt: Date };
type Columns<T> = Extract<keyof T, string>;

type SelectClause<T> =
    | `${Columns<T>}`
    | `${Columns<T>}, ${SelectClause<T>}`;

declare function select<T, S extends SelectClause<T>>(
    table: { _row: T },
    cols: S
): Pick<T, Extract<S extends `${infer C}, ${string}` ? C : S, keyof T>>;

declare const userTable: { _row: UserRow };

const rows = select(userTable, "id, name, email");
// rows: Pick<UserRow, "id" | "name" | "email">[]

select(userTable, "id, password"); // ❌ "password" 는 UserRow 키 아님
```

instantiation depth 한계로 컬럼 수가 많아지면 다른 표현 (튜플 + join) 으로 분해해야 한다. drizzle-orm 의 SELECT 빌더가 *튜플* 을 사용하는 이유가 이것 — 문자열로 받으면 컬럼 6~8개에서 한계에 도달하기 쉽다.

## 9. 비용과 디버깅

template literal types 는 ergonomics 가 좋지만 *컴파일 비용* 이 만만치 않다. 대형 라우트 객체(1000+ 라우트)에서 다음 증상이 보이면 의심:

- `tsc --extendedDiagnostics` 의 *Instantiations* 가 평소보다 5~10배 증가
- IDE 자동완성이 1초 이상 지연
- `ts2589` "excessively deep" 에러

해결책으로는 라우트 그룹을 file/module 단위로 *고립* 시켜 한 컴파일 단위의 instantiation 을 줄이는 것, `Tokenize` 같은 재귀 type 의 *결과를 한 번 alias 로 캐싱* 해서 재사용하는 것, intrinsic 으로 대체 가능한 부분은 intrinsic 으로 바꾸는 것, 분포 폭발이 의심되면 *literal union 대신 nominal brand* 로 좁히는 것이 있다.

디버깅 도구로는 `expect-type` 또는 `tsd` 로 *type-level 단위 테스트* 를 작성하면 회귀가 잡힌다.

```ts
import { expectTypeOf } from "expect-type";

expectTypeOf<ExtractParams<"/u/:id">>().toEqualTypeOf<"id">();
expectTypeOf<ParamsOf<"/u/:id/p/:pid">>().toEqualTypeOf<{ id: string; pid: string }>();
```

## 10. 실제 라이브러리에서의 적용

Hono 는 `app.get("/u/:id", c => c.req.param("id"))` 에서 `param` 의 키가 컴파일 타임에 좁혀진다. tRPC v11 은 `procedure.input(z.object({ id: z.string() }))` 의 입력 schema 와 결합해 client side 호출까지 end-to-end 타입을 흘린다. TanStack Router 는 `createRoute({ path: "/users/$userId" })` 에서 `$param` 컨벤션을 template literal 로 추출해 *링크 생성기* 까지 추론한다. Next.js 15 의 typed routes (`typedRoutes: true`) 는 컴파일 단계에서 라우트 트리를 자동 스캔하고 위와 같은 방식의 추론 타입을 코드젠으로 별도 파일에 출력해 *user code 의 컴파일 부담* 을 분리한다.

설계 관점에서 중요한 건 *컴파일러 무리* 와 *타입 정확성* 의 균형이다. 라우트 < 100개라면 template literal 추론이 단연 깔끔하지만, 그 이상이면 codegen 으로 우회하는 게 IDE 반응성과 빌드 시간을 살린다.

## 참고

- TypeScript 4.1 Release Notes — Template Literal Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- Anders Hejlsberg, *Recursive Conditional Types* PR: https://github.com/microsoft/TypeScript/pull/40002
- type-fest — Trim, Replace, Split utility types: https://github.com/sindresorhus/type-fest
- Hono Routing 문서: https://hono.dev/api/routing
- TanStack Router Typed Routes: https://tanstack.com/router/latest/docs/framework/react/guide/route-paths
- Next.js Typed Routes: https://nextjs.org/docs/app/api-reference/next-config-js/typedRoutes
