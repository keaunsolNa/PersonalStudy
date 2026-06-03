Notion 원본: https://www.notion.so/3745a06fd6d381fd928dd30d8c2d6525

# TypeScript tRPC End-to-End Type Inference inferRouterInputs Outputs와 Procedure Builder

> 2026-06-03 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- tRPC v11 의 `procedure` builder 가 어떻게 input/output 타입을 *체이닝* 으로 누적·추론하는지 따라간다
- `inferRouterInputs`, `inferRouterOutputs` 가 서버 router 정의로부터 client 측 타입을 재구성하는 방식을 코드로 분해한다
- middleware `ctx` 확장이 type narrowing 으로 흐르는 흐름을 검증한다
- 분산 모노레포에서 router 타입만 export 했을 때의 컴파일 시간·번들 영향을 측정한다

## 1. tRPC 의 핵심 명제 — Schema 없이 RPC 추론

tRPC 는 *runtime schema* 가 아니라 *TypeScript 타입 그 자체* 를 wire 양쪽이 공유하는 단일 source 로 본다. 서버에서 정의한 router 의 타입을 그대로 `import type { AppRouter } from '@server/root'` 한 뒤, client 가 `createTRPCProxyClient<AppRouter>()` 로 *프록시* 를 만든다. 타입은 router 정의에서 *완전히* 추론된다. `import type` 으로 가져온 router 는 *번들에 포함되지 않는다*.

## 2. Procedure Builder 의 타입 누적

```ts
import { z } from 'zod';
import { initTRPC } from '@trpc/server';

type Context = { user: { id: string } | null };
const t = initTRPC.context<Context>().create();

const byId = t.procedure
  .input(z.object({ id: z.string().uuid() }))
  .output(z.object({ id: z.string(), name: z.string() }))
  .query(({ input }) => {
    return { id: input.id, name: 'kim' };
  });
```

`.input(z.object(...))` 는 procedure 의 제네릭 `TInput` 슬롯을 `z.infer<typeof schema>` 로 채운다. 체이닝의 본질은 *제네릭 슬롯의 점진적 specialize*.

## 3. `inferRouterInputs` / `inferRouterOutputs` 의 내부

```ts
type AppRouter = typeof appRouter;
type Inputs = inferRouterInputs<AppRouter>;
type ByIdInput = Inputs['user']['byId']; // { id: string }
```

`inferRouterInputs` 의 구현은 conditional + mapped type 의 재귀로, 노드가 router 면 다시 walk 하고, procedure 면 `TInput` 슬롯을 꺼낸다. conditional 재귀 한계 (default 50) 안에서 동작하도록 *깊이 ≤ 5* 권장.

## 4. Middleware 와 ctx 확장의 narrowing

```ts
const protectedProcedure = t.procedure
  .use(({ ctx, next }) => {
    if (!ctx.user) throw new TRPCError({ code: 'UNAUTHORIZED' });
    return next({ ctx: { ...ctx, user: ctx.user } });
  });
```

핵심은 `next({ ctx: { ...ctx, user: ctx.user } })` 의 spread 객체 타입이 새 ctx 로 잡힌다는 점. spread 안의 `user: ctx.user` 는 narrowing 후의 `{ id: string }` 이고, 이 타입이 다음 procedure 의 ctx 로 propagate 된다.

## 5. Client 측 타입 — Proxy 의 작동

`createTRPCProxyClient<AppRouter>(opts)` 는 *재귀 Proxy* 를 만든다. 객체 property access 는 path 를 누적하고, 마지막 `.query(input)` 또는 `.mutate(input)` 호출이 path 를 URL 로 직렬화해 fetch 를 보낸다. router 가 변경되면 client 측 호출 사이트가 *즉시* 타입 에러를 낸다. Proxy 의 비용은 한 procedure 호출당 ~30 µs (Chrome 130).

## 6. Router 분할과 lazy router

router 가 커지면 IDE 의 타입 추론이 느려진다. 200 개 procedure 가 있는 root router 의 hover latency 가 600 ms 까지 올라간 사례. 해결은 *sub-router 분할 + 명시적 type annotation*. 또는 *lazy router* 패턴.

## 7. Zod 외의 schema — Effect, Valibot, ArkType

| 라이브러리 | 번들 크기 (gzip) | 특이점 |
|---|---|---|
| Zod v4 | ~12 KB | 가장 보편적, error map 풍부 |
| Valibot v0.40 | ~2 KB | tree-shakable, ESM-first |
| ArkType v2 | ~15 KB | TS-native syntax, 매우 빠른 검증 |
| Effect Schema | ~30 KB | Effect 런타임 통합 |

Edge runtime / mobile bundle 우선이면 Valibot, 풍부한 transform 이 필요하면 Effect Schema, 일반 풀스택은 Zod.

## 8. 운영 — Streaming, Subscription, RSC

tRPC v11 은 React Server Components 와 Server-Sent Events 기반 subscription 을 안정화했다. RSC 환경에서는 *server-only* helper `createCaller(ctx)` 가 fetch 없이 직접 procedure 를 호출한다. Subscription 은 generator 패턴으로 `for await` 로 받는다.

## 9. Trade-off

장점: 별도 schema 파일·codegen 없이 *컴파일 타임에* wire 양쪽 타입이 강제된다. 단점: *언어 lock-in* — 클라이언트가 TypeScript 가 아니면 직접 호출 URL 을 짜야 한다. router 가 *너무 커지면* TS server 의 inference 비용이 비례해 증가. 200+ procedure 부터는 sub-router cache, explicit annotation, project references 분리가 필요.

## 참고

- tRPC v11 Docs — Procedures: https://trpc.io/docs/server/procedures
- tRPC v11 Docs — Inferring Types
- Standard Schema Spec: https://github.com/standard-schema/standard-schema
- Theo Browne — tRPC vs server actions (2025)
- TypeScript 4.7 instantiation expressions
