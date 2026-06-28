Notion 원본: https://app.notion.com/p/38d5a06fd6d381d18633ef5c4dfe5835

# TypeScript tRPC 엔드투엔드 타입 추론과 Router Merge 및 inferRouterInputs

> 2026-06-28 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- tRPC가 코드 생성 없이 서버 라우터 타입을 클라이언트로 전파하는 메커니즘을 추론 관점에서 분해한다
- `initTRPC` 빌더 체인이 Context·Meta·input 파서를 어떻게 타입에 누적하는지 추적한다
- `inferRouterInputs` / `inferRouterOutputs` 유틸리티가 라우터 정의에서 입출력 타입을 역추출하는 원리를 구현 수준으로 이해한다
- 라우터 머지·미들웨어 체인에서 발생하는 타입 추론 비용과 컴파일 성능 트레이드오프를 측정 기준으로 판단한다

tRPC의 핵심 명제는 하나다. 서버에서 정의한 라우터의 타입(`typeof appRouter`)을 클라이언트가 `import type`으로 가져가면, 런타임 코드는 트리쉐이킹으로 제거되고 타입 정보만 남아 호출 시그니처가 완성된다. OpenAPI나 GraphQL처럼 IDL을 두고 codegen을 돌리는 방식이 아니라, TypeScript 컴파일러의 구조적 타이핑과 제네릭 추론을 그대로 채널로 쓴다.

```ts
const t = initTRPC.context<Context>().create();
export const appRouter = t.router({
  user: t.router({
    byId: t.procedure
      .input(z.object({ id: z.string().uuid() }))
      .query(({ input, ctx }) => ctx.db.user.find(input.id)),
  }),
});
export type AppRouter = typeof appRouter;
```

`import type`은 컴파일 후 사라지므로 서버 번들이 클라이언트로 새지 않으면서, 컴파일 타임에는 거대한 객체 타입을 통째로 들고 있다. 프록시는 경로 접근을 런타임에는 경로 문자열로, 컴파일 타임에는 타입으로 따라간다.

## initTRPC 빌더 체인의 타입 누적

`.context<Context>()`는 TContext를 교체한 새 빌더를 반환하는 phantom type 패턴이다. `.input()`은 호출될 때마다 파서의 출력 타입을 누적하고, `inferParser<P>['out']`으로 resolver의 input을 좁힌다. Zod뿐 아니라 Parser 인터페이스를 만족하는 Valibot·Effect Schema도 동일하게 동작한다.

## inferRouterInputs의 역추출

라우터는 중첩된 procedure 레코드다. `inferRouterInputs<TRouter>`는 트리를 재귀 순회하며 각 procedure의 입력 타입만 뽑는다. `_input_in`(클라이언트가 보내는 타입)과 `_input_out`(resolver가 받는 타입)을 구분하는 게 핵심으로, transform이 있는 프로시저에서 이 분리를 놓치면 타입이 틀어진다. 덕분에 React Hook Form·서버 액션·테스트 픽스처가 라우터 단일 출처에서 타입을 끌어 쓴다.

## 라우터 머지

중첩(`t.router({user, post})`)은 경로를 분리하고, 평면 머지(`t.mergeRouters`)는 같은 키 충돌을 컴파일 타임에 잡는다. 머지 깊이가 깊어질수록 AppRouter 타입이 곱셈으로 커지므로 `type RouterInput = inferRouterInputs<AppRouter>`로 별칭화해 캐시하는 것이 컴파일 성능에 직접적이다.

## 미들웨어 체인

```ts
const isAuthed = t.middleware(({ ctx, next }) => {
  if (!ctx.user) throw new TRPCError({ code: 'UNAUTHORIZED' });
  return next({ ctx: { user: ctx.user } });
});
```

`next({ ctx })`의 반환 타입이 다음 단계 TCtx로 흘러 `.use()` 연결마다 컨텍스트가 정제된다.

## 추론 한계

추론이 깨지는 케이스는 any 캐스팅, 순환 import, excessively deep 에러다. 마지막은 반환 타입을 명시해 추론을 끊으면 해결되며, 명시적 경계는 컴파일 시간을 줄이고 에러 위치를 국한시킨다. httpBatchLink는 타입과 무관한 런타임 최적화이므로 타입 추론 성능과 네트워크 성능을 분리해 진단한다.

## React Query 통합

```ts
const { data } = trpc.user.byId.useQuery({ id }); // data: User | undefined
```

`invalidate`의 인자 타입도 inferProcedureInput에서 나오므로 쿼리 키 오타가 컴파일 타임에 잡힌다.

## 다른 접근과의 비교

tRPC는 TS 모노레포에서 타입을 직접 공유할 때만 성립한다. 언어 경계를 못 넘고(모바일·외부 파트너), 공개 API엔 별도 명세가 필요하다. 다양한 소비자를 가진 공개 API엔 GraphQL/OpenAPI가 정석이다.

## 참고

- tRPC: https://trpc.io/docs
- Conditional/Mapped Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- Zod: https://zod.dev
