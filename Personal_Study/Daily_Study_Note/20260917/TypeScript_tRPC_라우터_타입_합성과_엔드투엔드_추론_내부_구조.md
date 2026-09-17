Notion 원본: https://www.notion.so/3de5a06fd6d381e29886cc72fff9472c

# TypeScript tRPC 라우터 타입 합성과 엔드투엔드 추론 내부 구조

> 2026-09-17 신규 주제 · 확장 대상: TypeScript 제네릭·타입 추론 → 타입 전용 API 계약 설계

## 학습 목표

- 코드 생성 없이 서버 타입이 클라이언트로 전달되는 메커니즘을 설명한다
- 빌더 체인이 제네릭 파라미터를 누적하는 방식과 미들웨어 컨텍스트 확장 규칙을 추적한다
- `DecorateRouterRecord` 류 매핑 타입이 프록시 객체를 타이핑하는 구조를 읽는다
- tRPC·GraphQL·OpenAPI 의 계약 검증 시점을 비교해 채택을 판정한다

## 1. "코드 생성 없음"이 성립하는 조건

OpenAPI 나 GraphQL 은 스키마를 중간 산출물로 두고, 거기서 클라이언트 타입을 생성한다. 생성 단계가 있으면 스키마와 코드가 어긋날 여지가 생기고, CI 에 생성 검증 단계를 넣어야 한다.

tRPC 는 중간 산출물을 없앤다. 서버 라우터의 **타입만** `import type` 으로 클라이언트에 가져오고, 그 타입에서 클라이언트 API 형태를 유도한다.

```ts
// server/router.ts
export const appRouter = router({ /* ... */ });
export type AppRouter = typeof appRouter;

// client/trpc.ts
import type { AppRouter } from "../server/router";     // 값이 아니라 타입만
export const trpc = createTRPCClient<AppRouter>({ links: [httpBatchLink({ url })] });
```

`import type` 은 컴파일 후 완전히 사라지므로 서버 코드가 클라이언트 번들에 들어가지 않는다. 대신 성립 조건이 붙는다.

- 서버와 클라이언트가 **같은 TypeScript 프로젝트** 또는 타입을 공유할 수 있는 모노레포 구조여야 한다
- 서버 코드가 타입 체크 가능해야 한다 — 서버에 타입 오류가 있으면 클라이언트 추론도 무너진다
- 배포 시점 불일치를 타입이 잡아주지 않는다 — 서버만 먼저 배포되면 컴파일은 통과하고 런타임에 깨진다

세 번째가 tRPC 의 실질적 한계다. 타입 안전성은 **빌드 시점의 소스 일치**를 보장할 뿐 런타임 버전 일치를 보장하지 않는다. 별도 팀이 소유하는 공개 API 에 tRPC 를 쓰지 말라는 권고가 여기서 나온다.

## 2. 빌더 체인이 제네릭을 누적하는 방식

tRPC 의 프로시저 빌더는 각 메서드가 **새로운 제네릭 파라미터를 채운 새 타입**을 반환한다. 체인이 진행될수록 타입 정보가 쌓인다.

```ts
const t = initTRPC.context<Context>().create();

export const getUser = t.procedure
    .input(z.object({ id: z.string().uuid() }))       // Input 확정
    .output(z.object({ name: z.string() }))            // Output 확정
    .query(({ input, ctx }) => {                       // input.id 가 string 으로 추론됨
        return userService.find(input.id);
    });
```

빌더 타입을 단순화하면 다음 형태다.

```ts
interface ProcedureBuilder<TContext, TInput, TOutput> {
    input<S extends StandardSchema>(schema: S):
        ProcedureBuilder<TContext, inferIn<S>, TOutput>;

    output<S extends StandardSchema>(schema: S):
        ProcedureBuilder<TContext, TInput, inferOut<S>>;

    use<TNewContext>(mw: Middleware<TContext, TNewContext>):
        ProcedureBuilder<TContext & TNewContext, TInput, TOutput>;

    query<R extends TOutput>(resolver: (opts: { ctx: TContext; input: TInput }) => R):
        Procedure<"query", TInput, R>;
}
```

핵심은 `input()` 이 `this` 를 반환하지 않고 **인자 타입에서 유도한 새 제네릭 인스턴스**를 반환한다는 점이다. 그래서 `.query()` 시점에 `input` 의 타입이 이미 확정도 있다.

`inferIn`/`inferOut` 이 스키마 라이브러리별 추론 헬퍼다. Zod 는 `z.infer<S>` 와 `z.input<S>` 를 구분하는데, `transform` 이나 `default` 가 있으면 입력 타입과 출력 타입이 다르기 때문이다.

```ts
const schema = z.object({
    page: z.string().transform(Number).default("1"),
});
// z.input<typeof schema>  → { page?: string }      ← 클라이언트가 보내는 형태
// z.infer<typeof schema>  → { page: number }       ← 리졸버가 받는 형태
```

tRPC 는 클라이언트 호출 시그니처에 `input` 타입을, 서버 리졸버에 `output` 타입을 쓴다. 이 구분을 잘못 쓴 래퍼를 직접 만들면 변환이 있는 스키마에서 타입이 어걱난다.

## 3. 미들웨어의 컨텍스트 확장

미들웨어는 컨텍스트를 좁히거나 넓힌다. 반환 타입이 다음 단계의 컨텍스트가 되는 구조라 인증 패턴이 타입으로 강제된다.

```ts
const isAuthed = t.middleware(({ ctx, next }) => {
    if (!ctx.session?.user) {
        throw new TRPCError({ code: "UNAUTHORIZED" });
    }
    return next({
        ctx: { session: ctx.session, user: ctx.session.user },   // user 가 non-null 로 좁혀짐
    });
});

export const protectedProcedure = t.procedure.use(isAuthed);

export const me = protectedProcedure.query(({ ctx }) => {
    return ctx.user.id;      // 옵셔널 체이닝 불필요 — 타입 수준에서 보장됨
});
```

`next({ ctx })` 의 인자 타입이 그대로 하류 제네릭에 합쳐진다. 미들웨어를 통과하지 않은 프로시저에서 `ctx.user` 를 쓰면 컴파일 오류다. "인증 검사를 빠뜨렸다"가 런타임 버그가 아니라 타입 오류가 되는 것이 이 설계의 실익이다.

권한 검사를 파라미터화하려면 미들웨어 팩토리로 만든다.

```ts
const hasRole = (role: Role) =>
    t.middleware(({ ctx, next }) => {
        if (!ctx.user.roles.includes(role)) {
            throw new TRPCError({ code: "FORBIDDEN" });
        }
        return next();
    });

export const adminProcedure = protectedProcedure.use(hasRole("ADMIN"));
```

`next()` 를 인자 없이 호출하면 컨텍스트가 그대로 전달된다. 미들웨어는 `await next()` 결과를 받아 후처리도 할 수 있어 로깅·타이밍 계측 지점으로도 쓴다.

```ts
const timing = t.middleware(async ({ path, type, next }) => {
    const start = Date.now();
    const result = await next();
    console.log(`${type} ${path} ${Date.now() - start}ms ok=${result.ok}`);
    return result;
});
```

## 4. 라우터 레코드에서 클라이언트 타입으로

`router({...})` 는 런타임에 프로시저 맵을 담은 객체를 만들고, 타입 수준에서는 그 구조를 `_def.record` 에 보존한다. 클라이언트는 이 레코드를 매핑 타입으로 훑어 호출 가능한 형태로 바꿈다.

```ts
// 개념적으로 이런 형태의 매핑
type DecorateRouter<TRecord> = {
    [K in keyof TRecord]: TRecord[K] extends Router<infer Inner>
        ? DecorateRouter<Inner>                        // 중첩 라우터 → 재귀
        : TRecord[K] extends Procedure<"query", infer I, infer O>
            ? { query: (input: I, opts?: Options) => Promise<O> }
            : TRecord[K] extends Procedure<"mutation", infer I, infer O>
                ? { mutate: (input: I, opts?: Options) => Promise<O> }
                : never;
};
```

런타임 구현은 **Proxy** 다. `trpc.user.getById.query({...})` 를 호출하면 프록시가 접근 경로(`["user","getById"]`)를 누적하다가 마지막 `query` 호출에서 HTTP 요청을 만든다.

```ts
// 경로 수집 프록시의 뾈대
function createRecursiveProxy(callback, path = []) {
    return new Proxy(() => {}, {
        get(_, key) {
            if (typeof key !== "string") return undefined;
            return createRecursiveProxy(callback, [...path, key]);
        },
        apply(_, __, args) {
            const method = path[path.length - 1];        // "query" | "mutate"
            return callback({ path: path.slice(0, -1), method, args });
        },
    });
}
```

즉 타입은 매핑 타입이 만들고, 값은 프록시가 만든다. 둘이 같은 구조를 따르도록 유지하는 것이 tRPC 내부 구현의 전부라고 해도 과언이 아니다. 이 구조 덕분에 라우터에 프로시저를 추가하면 클라이언트 자동완성에 즉시 반영된다 — 빌드 단계가 없다.

중첩 라우터도 같은 방식이다.

```ts
export const appRouter = router({
    user: router({ getById: getUser, list: listUsers }),
    post: router({ create: createPost }),
});
// trpc.user.getById.query(...)
// trpc.post.create.mutate(...)
```

## 5. 입출력 타입 추출과 재사용

컴포넌트 props 에 API 응답 타입을 쓰고 싶을 때, 라우터 타입에서 직접 뽑는다.

```ts
import type { inferRouterInputs, inferRouterOutputs } from "@trpc/server";
import type { AppRouter } from "../server/router";

type RouterInput = inferRouterInputs<AppRouter>;
type RouterOutput = inferRouterOutputs<AppRouter>;

type User = RouterOutput["user"]["getById"];
type ListArgs = RouterInput["user"]["list"];

function UserCard({ user }: { user: User }) { /* ... */ }
```

별도 DTO 타입을 손으로 정의하지 않아도 되고, 서버 응답 형태가 바똖면 컴포넌트에서 컴파일 오류가 난다. 이것이 실무에서 체감되는 가장 큰 이득이다.

주의할 점은 이 타입이 **직렬화 후** 형태가 아니라는 것이다. 리졸버가 `Date` 를 반환하면 타입은 `Date` 지만 JSON 을 거치면 문자열이 된다. `superjson` 같은 transformer 를 붙이면 `Date`, `Map`, `Set`, `BigInt` 가 복원되어 타입과 런타임이 일치한다.

```ts
const t = initTRPC.context<Context>().create({ transformer: superjson });
// 클라이언트도 동일 transformer 를 링크에 설정해야 한다
```

transformer 불일치는 타입으로 잡힐지 않는 대표적 사고다. 서버만 붙이면 클라이언트가 `{json: ..., meta: ...}` 래퍼를 그대로 받아 런타임에 깨진다.

## 6. 배치 링크와 N+1 요청

`httpBatchLink` 는 같은 틱에 발생한 여러 호출을 하나의 HTTP 요청으로 묶는다.

```
GET /api/trpc/user.getById,post.list?batch=1&input={"0":{...},"1":{...}}
```

응답은 배열로 오고 링크가 각 호출에 분배한다. 리스트 화면에서 아이템마다 쿼리를 걸어도 요청은 한 번이라, 클라이언트 측 N+1 이 완화된다.

다만 배치에는 대가가 있다. URL 길이 제한(대략 2~8KB, 프록시마다 다름)에 걸리면 414 가 나오므로 입력이 큰 쿼리가 섞이면 `maxURLLength` 옵션으로 분할을 강제해야 한다. 또 배치 안의 한 프로시저가 느리면 전체 응답이 그만큼 늦는다 — HTTP 응답은 하나이므로 가장 느린 것에 묶인다.

```ts
httpBatchLink({
    url,
    maxURLLength: 2083,
    headers: () => ({ authorization: getToken() }),
})
```

느린 쿼리와 빠른 쿼리를 분리하려면 `splitLink` 로 조건부 라우팅한다.

```ts
splitLink({
    condition: (op) => op.context.skipBatch === true,
    true: httpLink({ url }),            // 단건 요청
    false: httpBatchLink({ url }),      // 배치
})
```

서버 측 N+1 은 별개 문제다. tRPC 는 데이터 로딩에 관여하지 않으므로 DataLoader 나 배치 쿼리를 직접 붙여야 한다. GraphQL 처럼 리졸버 그래프를 알지 못하기 때문이다.

## 7. TanStack Query 통합과 캐시 키

React 에서는 TanStack Query 와 결합해 쓴다. 쿼리 키가 라우터 경로와 입력에서 자동 생성되므로 수동 키 관리가 사라진다.

```tsx
const { data, isLoading } = useQuery(
    trpc.user.getById.queryOptions({ id })
);

const mutation = useMutation(
    trpc.post.create.mutationOptions({
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: trpc.post.list.queryKey() });
        },
    })
);
```

`queryKey()` 가 만드는 키는 `[["post","list"], { input, type }]` 형태다. 인자 없이 호출하면 그 프로시저의 모든 입력 조합을 포괄하는 접두 키가 되어 부분 무효화가 된다. 수동으로 문자열 키를 관리할 때 발생하던 오타·누락이 사라진다.

버전에 따라 API 형태가 달랐다. 예전에는 `createTRPCReact` 로 `trpc.user.getById.useQuery()` 를 썼고, 현재는 TanStack Query 의 네이티브 옵션 객체를 반환하는 방식이 권장된다. 프로젝트의 tRPC 버전 문서를 확인해야 한다.

## 8. 채택 판단 — 무엇과 비교하는가

| 항목 | tRPC | GraphQL | OpenAPI+생성기 |
|---|---|---|---|
| 계약 검증 시점 | 빌드(같은 저장소) | 스키마 체크 | 스키마 체크 |
| 코드 생성 | 없음 | 필요 | 필요 |
| 다른 언어 클라이언트 | 불가 | 가능 | 가능 |
| 공개 API 적합성 | 낮음 | 높음 | 높음 |
| 오버페칭 제어 | 프로시저 단위 | 필드 단위 | 엔드포인트 단위 |
| 런타임 검증 | 스키마 라이브러리 | 스키마 | 생성 코드에 따라 |
| 학습 비용 | 낮음 | 높음 | 중간 |

tRPC 가 잘 맞는 자리는 **TypeScript 모노레포의 내부 API** 다. 프론트와 백이 같은 저장소에 있고 함께 배포되며, 클라이언트가 TypeScript 하나뿐인 경우 코드 생성 단계를 통째로 없앵 수 있다. Next.js 앱에서 API Route 를 tRPC 로 감싸는 구성이 전형적이다.

맞지 않는 자리도 분명하다. 모바일 앱(Swift/Kotlin)이 같은 API 를 쓴다면 별도 계약이 필요하고, 외부 파트너에게 제공하는 API 라면 문서화 가능한 스키마가 필수다. 이 경우 tRPC 라우터에 OpenAPI 어댑터를 얹는 방법이 있지만 이중 관리가 생긴다.

Server Actions 가 등장한 이후 Next.js 단독 앱에서는 tRPC 의 필요성이 줄었다는 평가도 있다. 다만 Server Actions 는 뮤테이션 중심이고 쿼리 캐싱·배치·구독 패턴이 약하므로, 클라이언트 상태가 복잡한 앱에서는 여전히 tRPC 조합이 유리하다. 판단은 "클라이언트가 몇 종류인가"와 "API 를 누가 소유하는가" 두 축으로 하는 것이 실용적이다.

## 참고

- tRPC 공식 문서 — https://trpc.io/docs
- tRPC, Define Routers / Procedures — https://trpc.io/docs/server/routers
- tRPC, Links (httpBatchLink, splitLink) — https://trpc.io/docs/client/links
- Zod 문서, `input`/`output` 타입 추론 — https://zod.dev/
- TanStack Query 문서 — https://tanstack.com/query/latest/docs/framework/react/overview
- superjson 저장소 — https://github.com/flightcontrolhq/superjson
