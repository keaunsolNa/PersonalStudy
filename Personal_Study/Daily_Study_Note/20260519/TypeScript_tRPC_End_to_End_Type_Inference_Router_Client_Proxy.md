Notion 원본: https://www.notion.so/3655a06fd6d381a885e7d87b7d7be2b1

# TypeScript tRPC End-to-End Type Inference — Router·Client Proxy 동작

> 2026-05-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- tRPC가 서버 라우터의 입출력 타입을 클라이언트까지 그대로 전파하는 *purely structural* 방식을 분해해 본다
- `inferRouterInputs`·`inferRouterOutputs` 가 라우터 트리에서 입출력 타입을 어떻게 끄집어내는지 추적한다
- Proxy 기반 클라이언트가 런타임 키 접근을 어떻게 path encoding 으로 변환하는지 파악한다
- 미들웨어 `meta`·`context` 변환이 타입 추론에 끼치는 영향과, 빌드 시간 폭증을 막는 라우터 분할 전략을 정리한다

## 1. tRPC의 설계 가설 — "스키마 없이 추론만으로"

tRPC는 OpenAPI·GraphQL Schema·gRPC `.proto` 처럼 *별도의 IDL 파일*을 두지 않는다. 서버 코드의 *TypeScript 타입 자체*가 계약이고, 클라이언트는 해당 타입을 import 한 뒤 Proxy로 호출 path를 구성한다. 빌드 산출물에 스키마 변환 단계가 없고, 클라이언트는 `typeof appRouter`라는 *형(type)*만 가져와 호출 시그니처를 만들어낸다.

이 설계는 두 가지 전제에 의존한다. 첫째, 서버와 클라이언트가 같은 워크스페이스에서 빌드되며 동일 패키지 버전을 본다. 둘째, 런타임 검증(zod 등)은 서버에서만 한 번 수행되고, 클라이언트는 *타입 수준*에서만 입력 타입을 보호받는다. 따라서 클라이언트가 외부 SDK 소비자라면 OpenAPI 어댑터(`trpc-openapi`)나 별도 zod 스키마 export가 필요하다.

이 모델의 강점은 *zero-runtime contract drift*다. 서버 라우터의 한 줄이 바뀌면 클라이언트 호출부에 즉시 타입 에러가 나며, 인스펙터 없이 IDE 자동완성이 항상 진실을 보장한다.

## 2. Router 정의 — `t.router({...})`의 내부 표현

서버는 `initTRPC.context<Ctx>().create()`로 빌더 `t`를 얻고, `t.procedure`·`t.router`·`t.middleware`로 라우트를 구성한다. 각 procedure는 *type-level descriptor*를 노출한다.

```ts
type ProcedureDef<I, O, Type> = {
  _def: { type: Type; inputs: I[]; output: O; meta: Record<string, unknown>; };
  _input_in: I; _input_out: I; _output_in: O; _output_out: O;
};
```

`t.router({...})`는 입력 객체의 각 키가 procedure 혹은 다른 router라는 *재귀적 구조*를 받아 묶는다. 타입 단에서 router는 *플레인 객체 형태*이므로 `appRouter['user']['getById']`처럼 키 접근 한 번으로 procedure 의 `_def`까지 도달한다. 클라이언트 Proxy 가 이 구조를 모방한다.

## 3. Input Parser — zod와 transform 의 입출력 분리

`t.procedure.input(zSchema)`는 단순히 검증기를 등록하는 게 아니라 *입력 타입을 두 단계*로 나눈다. zod 의 `z.transform`이 있을 때 입력과 출력 타입이 달라지기 때문이다.

```ts
const Schema = z.object({ id: z.string().transform((s) => Number.parseInt(s, 10)) });
type In = z.input<typeof Schema>;   // { id: string }
type Out = z.output<typeof Schema>; // { id: number }
```

tRPC는 *클라이언트가 보내는 형태*(In)와 *핸들러가 받는 형태*(Out) 둘 다 보존한다. `inferRouterInputs`는 In을, `inferRouterOutputs`는 핸들러 반환을 추출한다. 이 분리는 React Query 와 결합할 때 결정적이다. mutation 폼은 `In`을 받고, optimistic update 의 cache 는 `Out`을 다룬다.

## 4. `inferRouterInputs`·`inferRouterOutputs` — 라우터 트리 순회

핵심 두 헬퍼는 라우터의 record 를 깊이 우선으로 순회하면서 procedure 의 input/output 슬롯을 추출한다.

```ts
type inferRouterInputs<TRouter extends AnyRouter> = {
  [K in keyof TRouter['_def']['record']]:
    TRouter['_def']['record'][K] extends AnyRouter
      ? inferRouterInputs<TRouter['_def']['record'][K]>
      : TRouter['_def']['record'][K] extends AnyProcedure
      ? TRouter['_def']['record'][K]['_def']['inputs'][0]
      : never;
};
```

조건부 타입의 분기 순서가 중요하다. router 가 먼저 확인되어야 procedure 분기에서 `_def.inputs[0]`에 도달했을 때 *재귀가 항상 종결*된다. 잘못된 분기 순서는 무한 재귀 또는 *Type instantiation excessively deep* 에러를 만든다.

대형 라우터(노드 수 200+, 깊이 4+)에서는 위 mapped type 평가가 비싸다. tRPC v10 부터 procedure 의 `_def.inputs`를 *튜플 형태*로 둔 이유는 빈 튜플 분기로 무입력 procedure 의 평가를 빠르게 빠져나가도록 만들기 위함이다.

## 5. Client Proxy — 런타임 키 접근에서 path 문자열로

클라이언트는 `createTRPCProxyClient<AppRouter>({ links: [httpBatchLink({ url: '/trpc' })] })`로 만든다.

`client.user.getById.query({...})`는 다음으로 분해된다: Proxy 의 `get` 트랩이 발동하고 `path = ['user']` 누적된 새 Proxy 반환 → `.getById` 도 동일하게 `path = ['user', 'getById']` → `.query`는 path 누적의 *종결 키*로 처리되어 실제 `httpBatchLink` 호출 함수를 반환 → 함수 호출 시 인수와 path 가 `httpBatchLink` 의 `op` 객체로 만들어져 HTTP 요청으로 직렬화된다.

타입 단에서는 동일한 형태의 *재귀 mapped type*이 이 Proxy 구조를 흉내낸다. IDE 자동완성은 *Proxy 의 런타임 동작*이 아니라 *정적 타입 트리 순회* 로 만들어진다. 두 세계는 우연이 아니라 *의도된 평행 구조*다.

## 6. Middleware 와 `meta` — context 변환의 타입 전파

`t.middleware`는 다음 두 가지 변환을 한 줄로 표현한다.

```ts
const isAuthed = t.middleware(({ ctx, next }) => {
  if (!ctx.user) throw new TRPCError({ code: 'UNAUTHORIZED' });
  return next({ ctx: { ...ctx, user: ctx.user } });
});
const protectedProcedure = t.procedure.use(isAuthed);
```

`next({ ctx: { user: ctx.user } })`의 인자 타입은 *반환되는 새 context*의 타입을 만든다. tRPC 는 이 변환을 procedure 의 `_def.ctx_in`→`_def.ctx_out`으로 추적한다. 이어 등록되는 미들웨어는 이전 단계의 ctx_out 을 입력으로 받는다.

`meta` 는 procedure 에 부착되는 임의 메타데이터 슬롯이다. RBAC·rate limit·OpenAPI 어댑터가 이 슬롯을 활용한다.

## 7. Subscription 과 Observable — 양방향 스트림의 타입

`t.procedure.subscription(...)` 은 클라이언트에서 `.subscribe({ next, error })`로 호출된다. tRPC 의 observable 은 RxJS 와 호환되지 않는 *경량 구현*이며, SSE 또는 WebSocket 링크가 활성일 때만 동작한다. 타입 추론 관점에서 subscription 은 두 가지 추가 슬롯을 갖는다(`_output_observable_value`, `_output_observable_error`).

## 8. 컴파일 시간과 빌드 출력 — 라우터 분할 전략

라우터가 커지면 `inferRouterInputs<AppRouter>`의 평가가 폭증한다. 실측치 기준 procedure 수 N 에 대해 평가 비용이 대략 O(N · depth)이고, depth 4·N=300인 모노레포에서 `tsc --extendedDiagnostics`의 instantiations 가 700만을 넘는 사례가 보고된다. 이 경우 다음 셋을 조합한다.

첫째, *router-per-feature*로 분할하고 `mergeRouters` 로 최종 합친다. 각 sub-router 는 자체 패키지로 빌드되어 `.d.ts` 가 발사된다. 둘째, procedure 단위의 zod 스키마를 *별도 type alias* 로 export 해 클라이언트 폼이 직접 참조한다. 셋째, 자주 호출되는 procedure 의 인풋·아웃풋을 `type` alias 로 *materialize* 해두면 호출부의 추론 캐시 적중률이 올라간다.

## 9. 런타임 에러와 타입 — `TRPCError`·`onError`·`errorFormatter`

서버에서 던지는 `TRPCError`는 `{ code, message, cause, data }` 구조를 갖는다. 클라이언트의 React Query 통합에서 `error`는 `TRPCClientError<AppRouter>` 타입으로 와서 `error.data?.code`·`error.shape?.message`로 접근한다. `errorFormatter`(`initTRPC` 옵션)는 서버에서 던진 에러의 `shape`를 변형하면서 *클라이언트의 `error.data` 타입*도 같이 변경한다.

*런타임 한 곳의 변경이 클라이언트의 정적 타입까지 따라온다* 는 이 일관성이 tRPC 도입 가치의 핵심이다. SSR/RSC 환경에서는 서버 측에서 `appRouter.createCaller(ctx).user.getById({...})` 로 직접 호출이 가능하다. caller 는 HTTP를 우회하지만 *동일한 입력/출력 타입*을 가지므로, React Server Component 안에서 fetch 와 동일한 타입 안전성을 누릴 수 있다.

## 참고

- tRPC 공식 문서 — Routers, Procedures, Client Proxy <https://trpc.io/docs/server/routers>
- tRPC v10 Release Notes — type-level redesign
- tRPC + React Query 통합 — `@trpc/react-query` 문서
- Zod transform 과 input/output 분리 <https://zod.dev/?id=transform>
- "Why tRPC isn't a replacement for OpenAPI" — Theo Browne 분석 영상
