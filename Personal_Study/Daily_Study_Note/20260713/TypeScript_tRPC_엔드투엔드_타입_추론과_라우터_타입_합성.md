Notion 원본: https://app.notion.com/p/39c5a06fd6d381bbbba7cc344cd0f159

# TypeScript tRPC 엔드투엔드 타입 추론과 라우터 타입 합성

> 2026-07-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- tRPC가 코드 생성 없이 서버 라우터의 타입을 클라이언트로 전파하는 원리를 타입 레벨에서 분해한다
- `initTRPC` 빌더가 만들어 내는 프로시저 타입과 `AnyRouter` 합성 구조를 읽는다
- 클라이언트 프록시가 런타임 `Proxy`와 `inferRouterInputs`/`inferRouterOutputs`로 입출력 타입을 복원하는 흐름을 추적한다
- 대규모 라우터에서 `tsc` 추론 비용이 폭증하는 지점과 완화 전략을 실측 기준으로 정리한다

## 1. tRPC가 푸는 문제: 스키마 중복 제거

REST/GraphQL은 서버와 클라이언트가 각각 타입을 소유한다. OpenAPI나 GraphQL codegen은 IDL을 중간 산물로 두고 코드를 생성한다. 생성 단계가 있으면 스키마와 구현이 어긋날 수 있고, 빌드 파이프라인이 늘어난다.

tRPC의 전제는 서버와 클라이언트가 같은 TypeScript 모노레포에 있다는 것이다. 서버 라우터의 정적 타입 그 자체를 `import type`으로 클라이언트가 가져와, 컴파일러가 입력·출력 타입을 추론한다. 런타임 코드는 넘어가지 않고 타입만 넘어가므로 번들에는 포함되지 않는다. 즉 tRPC의 타입 안전은 별도 산출물이 아니라 `typeof appRouter`를 통과시키는 순수 타입 연산이다.

## 2. initTRPC 빌더와 프로시저 타입의 골격

```typescript
import { initTRPC } from '@trpc/server';
import { z } from 'zod';

interface AppContext {
  userId: string | null;
  db: DatabaseClient;
}

const t = initTRPC.context<AppContext>().create();
export const router = t.router;
export const publicProcedure = t.procedure;
```

`.input(z.object({ id: z.string() }))`를 붙이면 입력 타입이 `{ id: string }`로 좁혀지고, 이어 `.query(({ input }) => ...)`의 리졸버 반환 타입이 출력 타입으로 캡처된다. 결과 프로시저 객체는 입력과 출력 타입을 모두 타입 파라미터로 들고 있으며, 이 두 타입이 나중에 클라이언트에서 뽑히는 원천이다.

## 3. 라우터 합성: 레코드를 타입으로 접는다

```typescript
export const appRouter = router({
  user: router({
    byId: publicProcedure
      .input(z.object({ id: z.string() }))
      .query(({ input, ctx }) => ctx.db.user.find(input.id)),
  }),
  post: router({
    create: publicProcedure
      .input(z.object({ title: z.string(), body: z.string() }))
      .mutation(({ input, ctx }) => ctx.db.post.insert(input)),
  }),
});

export type AppRouter = typeof appRouter;
```

`AppRouter['user']['byId']`로 인덱싱하면 그 프로시저의 입출력 타입이 나온다. 이 인덱싱 가능성이 tRPC 전체를 지탱한다. 라우터는 값이면서 동시에 그 `typeof`가 완전한 API 명세 역할을 하는 타입이다.

## 4. inferRouterInputs / inferRouterOutputs

```typescript
import type { inferRouterInputs, inferRouterOutputs } from '@trpc/server';
import type { AppRouter } from '../server/router';

type Inputs = inferRouterInputs<AppRouter>;
type Outputs = inferRouterOutputs<AppRouter>;
type ByIdInput = Inputs['user']['byId'];   // { id: string }
type ByIdOutput = Outputs['user']['byId']; // User
```

내부 구현은 라우터 레코드를 재귀적으로 순회하며 프로시저면 입력/출력 타입을 꺼내고, 하위 라우터면 다시 매핑드 타입을 적용한다. 라우터 트리가 깊고 넓을수록 이 재귀 전개 비용이 커진다.

## 5. 클라이언트 프록시: 런타임 Proxy + 컴파일타임 타입

`client.user.byId.query({ id: '1' })`는 런타임에 존재하지 않는 메서드다. 클라이언트는 `Proxy`로 접근 경로(`['user','byId','query']`)를 문자열로 누적하고, 함수 호출 시 경로를 조립해 HTTP 요청을 만든다. 타입 쪽은 완전히 별개로, `AppRouter`를 매핑드 타입으로 변환해 각 프로시저를 `{ query: (input) => Promise<output> }` 형태로 바꾼다.

런타임 Proxy는 타입을 전혀 모른 채 문자열 경로만 조립하고, 컴파일러는 클라이언트 타입으로 그 경로의 정당성과 입출력 타입을 독립적으로 검증한다. 두 세계가 만나는 접점은 오직 프록시에 클라이언트 타입을 씌우는 `as` 한 줄이다. 그래서 잘못된 경로는 컴파일 에러가 나지만 런타임 프록시는 어떤 경로든 받아들인다.

## 6. 미들웨어와 컨텍스트 좁히기

```typescript
const isAuthed = t.middleware(({ ctx, next }) => {
  if (!ctx.userId) {
    throw new TRPCError({ code: 'UNAUTHORIZED' });
  }
  return next({ ctx: { userId: ctx.userId } }); // string | null -> string
});
const protectedProcedure = publicProcedure.use(isAuthed);
```

`protectedProcedure`로 만든 리졸버에서 `ctx.userId`는 `string`으로 좁혀진다. `next`의 인자 타입이 다음 단계 컨텍스트로 병합되기 때문이다. 미들웨어 체인이 길어지면 컨텍스트 타입이 교집합으로 누적되어 타입 계산이 무거워질 수 있다.

## 7. 컴파일 성능: 어디서 폭발하는가

`tsc`는 클라이언트가 라우터 타입을 인덱싱할 때마다 재귀 매핑드 타입을 전개한다. 라우터가 수백 개 프로시저를 가지면 에디터 타입 체크 지연이 눈에 띄게 늘어난다.

| 증상 | 원인 | 완화책 |
|---|---|---|
| 자동완성 3~5초 지연 | 거대 단일 라우터의 재귀 전개 | 도메인별 라우터 분할, lazy import type |
| tsc 메모리 급증 | Zod 스키마의 깊은 z.infer 중첩 | 스키마를 별도 타입으로 추출 |
| 증분 빌드 캐시 무효화 잦음 | 라우터 한 곳 변경이 전체 재계산 유발 | Project References로 경계 고정 |

실무 완화는 세 가지다. `z.infer`를 인라인에 두지 말고 명시적 인터페이스로 추출하고, 라우터를 여러 파일로 나누고, `AppRouter`는 `import type`만 사용한다. TypeScript 5.x의 `--isolatedDeclarations`는 명시적 반환 타입을 요구해 추론 비용을 미리 고정하는 효과가 있다.

## 8. GraphQL codegen과의 트레이드오프

tRPC는 IDL과 코드 생성 단계를 없앤 대신 서버와 클라이언트가 같은 TS 빌드 그래프에 있어야 한다는 제약을 진다. 서버가 다른 언어이거나 퍼블릭 API로 노출해야 하면 tRPC의 전제가 깨지고, 이때는 OpenAPI/GraphQL의 명시적 계약이 낫다. 단일 TS 모노레포의 내부 API라면 tRPC가 스키마 중복과 생성 파이프라인을 통째로 제거한다. 선택 기준은 타입을 공유할 수 있는 경계 안인가이다.

## 참고

- tRPC 공식 문서: Concepts / Inference Helpers (trpc.io/docs)
- TypeScript Handbook: Mapped Types, Conditional Types, infer
- MDN: Proxy 객체와 트랩 핸들러
- Zod 문서: z.infer 타입 추출과 스키마 합성
