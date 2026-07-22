Notion 원본: https://app.notion.com/p/3a55a06fd6d3810b871fde63c1767cb2

# TypeScript tRPC 엔드투엔드 타입 추론과 라우터 병합 및 입력 검증

> 2026-07-22 신규 주제 · 확장 대상: TypeScript(타입 시스템·제네릭 설계)

## 학습 목표

- tRPC가 코드 생성 없이 서버 라우터 타입을 클라이언트로 전파하는 원리를 타입 레벨에서 추적한다.
- `initTRPC` 빌더 체인이 컨텍스트·메타·에러 포매터 타입을 어떻게 누적하는지 분석한다.
- `inferRouterInputs` / `inferRouterOutputs` 유틸리티가 라우터 정의에서 입출력 타입을 역추출하는 메커니즘을 설명한다.
- Zod 입력 스키마와 procedure 체인이 결합해 런타임 검증과 컴파일 타임 타입을 동시에 보장하는 경계를 구분한다.

## 1. tRPC가 해결하는 문제 — 스키마 없는 타입 공유

REST나 GraphQL은 서버와 클라이언트 사이에 별도의 계약(OpenAPI 스펙, GraphQL SDL)을 두고, 그 계약으로부터 클라이언트 타입을 코드 생성한다. 생성 단계가 있으면 스키마 변경과 클라이언트 재생성 사이에 항상 시차가 생기고, 그 시차 동안 타입은 거짓말을 한다. tRPC의 핵심 명제는 "TypeScript 자체가 이미 계약 언어인데 왜 두 번째 계약이 필요한가"이다.

tRPC는 서버 라우터의 정적 타입을 `typeof appRouter`로 추출해 클라이언트가 **타입만** import 하도록 만든다. 런타임 코드는 넘어가지 않고 타입 정보만 넘어가므로 번들 크기에 영향이 없다. 클라이언트는 이 타입을 제네릭 인자로 받아 프록시 객체를 구성하고, 개발자가 `client.user.getById.query()`를 호출하면 그 반환 타입이 서버 procedure의 반환 타입과 컴파일 타임에 동일함이 보장된다. 서버에서 필드를 하나 지우면 클라이언트 호출부에 즉시 타입 에러가 뜬다. 이것이 "엔드투엔드 타입 안전성"의 실체다.

```typescript
// server/router.ts
import { initTRPC } from '@trpc/server';
import { z } from 'zod';

const t = initTRPC.context<Context>().create();

export const appRouter = t.router({
  user: t.router({
    getById: t.procedure
      .input(z.object({ id: z.string().uuid() }))
      .query(({ input }) => {
        return { id: input.id, name: 'Kim', age: 30 };
      }),
  }),
});

export type AppRouter = typeof appRouter;   // 이 타입만 클라이언트로 전달
```

클라이언트는 `AppRouter`를 값이 아니라 타입으로만 가져온다. `import type { AppRouter }`는 컴파일 후 완전히 사라진다.

## 2. initTRPC 빌더의 타입 누적 — 팬텀 제네릭

`initTRPC`는 메서드 체인마다 제네릭 파라미터를 누적하는 빌더다. `.context<Context>()`는 이후 모든 procedure의 resolver에 전달될 컨텍스트 타입을 고정하고, `.meta<Meta>()`는 procedure 메타데이터 타입을, `.create()`는 누적된 타입 파라미터를 최종 확정한다. 이 패턴은 "팬텀 타입(phantom type)"에 가깝다 — 런타임 값은 거의 없지만 타입 파라미터를 실어 나르는 것이 목적이다.

```typescript
const t = initTRPC
  .context<{ userId: string | null }>()
  .meta<{ requiresAuth: boolean }>()
  .create({
    errorFormatter({ shape, error }) {
      return {
        ...shape,
        data: { ...shape.data, zodError: error.cause instanceof ZodError ? error.cause.flatten() : null },
      };
    },
  });
```

여기서 중요한 타입 레벨 동작은 `errorFormatter`의 반환 타입이 클라이언트 에러 타입에 그대로 반영된다는 점이다. 위처럼 `zodError` 필드를 추가하면 클라이언트의 `error.data.zodError`가 타입으로 존재하게 된다. 빌더가 누적한 shape 타입이 최종 라우터 타입에 실려 클라이언트까지 흐르는 구조다.

## 3. procedure 체인과 미들웨어의 컨텍스트 좁히기

procedure는 `.input()`, `.use()`(미들웨어), `.query()`/`.mutation()`을 체이닝한다. 각 단계는 이전 단계의 타입을 입력받아 새 타입을 반환하는 순수 타입 변환이다. 특히 미들웨어는 컨텍스트 타입을 **좁히는** 데 쓰인다. 인증 미들웨어가 `userId: string | null`을 `userId: string`으로 좁히면, 그 미들웨어를 거친 procedure의 resolver에서는 `ctx.userId`가 non-null로 추론된다.

```typescript
const isAuthed = t.middleware(({ ctx, next }) => {
  if (!ctx.userId) {
    throw new TRPCError({ code: 'UNAUTHORIZED' });
  }
  return next({
    ctx: { userId: ctx.userId },   // string 으로 좁혀진 컨텍스트를 전달
  });
});

const protectedProcedure = t.procedure.use(isAuthed);

const meRouter = t.router({
  me: protectedProcedure.query(({ ctx }) => {
    // ctx.userId 는 여기서 string (null 아님) 으로 추론된다
    return { userId: ctx.userId };
  }),
});
```

`next({ ctx })`가 반환하는 컨텍스트 타입이 다음 미들웨어/resolver의 `ctx` 타입으로 합성된다. 이것은 `next`의 제네릭 반환 타입이 인자로 전달된 `ctx` 객체 타입과 기존 컨텍스트의 교집합으로 계산되기 때문이다. 여러 미들웨어를 체이닝하면 컨텍스트 타입이 순차적으로 정제되는 파이프라인이 만들어진다.

## 4. inferRouterInputs / inferRouterOutputs — 역방향 타입 추출

라우터 정의에서 입출력 타입을 뽑아내는 유틸리티가 tRPC 타입 안전성의 실용적 진입점이다. 프론트엔드 컴포넌트가 서버 반환 타입을 정확히 알아야 할 때 이 유틸리티로 타입을 추출한다.

```typescript
import type { inferRouterInputs, inferRouterOutputs } from '@trpc/server';

type RouterInput = inferRouterInputs<AppRouter>;
type RouterOutput = inferRouterOutputs<AppRouter>;

type GetUserInput = RouterInput['user']['getById'];   // { id: string }
type GetUserOutput = RouterOutput['user']['getById']; // { id: string; name: string; age: number }
```

내부적으로 이 유틸리티는 라우터의 `_def` 프로퍼티에 저장된 procedure 정의 타입을 매핑 타입으로 순회한다. 각 procedure는 `_def._input_in`(입력의 입력 타입, 즉 검증 전)과 `_def._output_out`(출력의 출력 타입, 즉 직렬화 후) 같은 팬텀 프로퍼티를 타입 레벨에 보관한다. `infer`를 써서 이 프로퍼티들을 추출하고 라우터 구조를 재귀적으로 매핑한 결과가 위 타입이다. 여기서 `_input_in`과 `_input_out`을 구분하는 것이 핵심인데, Zod의 `transform`이나 `default`가 있으면 입력(사용자가 넣는 값)과 출력(검증 후 값)이 달라지기 때문이다.

## 5. Zod 입력 스키마와 검증 경계

`.input(schema)`는 두 가지를 동시에 한다. 컴파일 타임에는 `schema`의 `z.infer` 타입을 resolver의 `input` 파라미터 타입으로 확정하고, 런타임에는 실제 요청 페이로드를 `schema.parse()`로 검증한다. 이 이중성이 tRPC 입력 안전성의 본질이다 — 타입은 컴파일러가, 값은 Zod가 보증한다.

```typescript
const createUser = t.procedure
  .input(
    z.object({
      email: z.string().email(),
      age: z.coerce.number().min(0).default(18),   // 입력 string, 출력 number
    }),
  )
  .mutation(({ input }) => {
    // input.age 는 number (coerce + default 적용 후)
    return { created: true, age: input.age };
  });
```

`z.coerce.number()`와 `default(18)` 때문에 이 procedure의 입력 타입(`_input_in`)은 `{ email: string; age?: unknown }`에 가깝지만 resolver가 받는 타입(`_input_out`)은 `{ email: string; age: number }`다. 클라이언트가 `inferRouterInputs`로 얻는 것은 전자, resolver 내부에서 쓰는 것은 후자다. 이 구분을 놓치면 "클라이언트에서는 age를 안 보내도 되는데 왜 서버 타입에는 필수인가" 같은 혼란이 생긴다.

trade-off 관점에서, tRPC는 입력 검증을 procedure마다 명시적으로 붙여야 한다. 스키마를 빠뜨리면 `input`은 `unknown`이 되어 런타임 검증이 전혀 없다. GraphQL이 스키마를 강제하는 것과 달리 tRPC는 검증 누락을 컴파일 타임에 막지 못하므로, 팀 컨벤션이나 lint 규칙으로 "모든 procedure는 input을 가진다"를 강제하는 것이 실무적으로 필요하다.

## 6. 라우터 병합과 네임스페이스 조합

대규모 앱에서는 라우터를 도메인별로 쪼갠 뒤 병합한다. `t.router({ ... })`에 하위 라우터를 중첩하거나, `t.mergeRouters()`로 평면 병합한다. 타입 레벨에서 병합은 두 라우터의 `_def.procedures` 레코드를 합치는 교집합/합집합 연산이다.

```typescript
const userRouter = t.router({ getById: /* ... */, list: /* ... */ });
const postRouter = t.router({ create: /* ... */, feed: /* ... */ });

// 중첩 병합 — client.user.getById, client.post.create
export const appRouter = t.router({
  user: userRouter,
  post: postRouter,
});

// 평면 병합 — 키 충돌 시 컴파일 에러
export const flatRouter = t.mergeRouters(userRouter, postRouter);
```

중첩 병합은 네임스페이스를 만들어 이름 충돌을 피하고 클라이언트 호출 경로를 계층화한다. 평면 병합은 같은 레벨에 두므로 키가 겹치면 타입 에러가 난다. 실무에서는 도메인 경계를 명확히 하기 위해 중첩 병합을 선호하며, 이 계층 구조가 그대로 클라이언트 프록시의 접근 경로(`client.a.b.c`)로 반영된다.

| 병합 방식 | 호출 경로 | 키 충돌 처리 | 권장 상황 |
|---|---|---|---|
| 중첩 `t.router({ ns: sub })` | `client.ns.proc` | 네임스페이스로 격리 | 도메인 분리 |
| 평면 `mergeRouters(a, b)` | `client.proc` | 컴파일 에러 | 소규모/공통 유틸 |

## 7. 클라이언트 프록시의 타입 추론 원리

클라이언트 `createTRPCClient<AppRouter>()`는 실제로는 재귀적 Proxy 객체를 만든다. `client.user.getById.query`에 접근할 때마다 Proxy의 get 트랩이 경로 세그먼트를 누적하고, `.query(input)`을 호출하는 순간 누적된 경로 `["user", "getById"]`를 실제 HTTP 요청 경로로 변환한다. 타입 레벨에서는 `AppRouter` 타입을 재귀 매핑 타입으로 순회하며, 각 procedure를 `{ query: (input: TInput) => Promise<TOutput> }` 형태의 호출 가능 객체로 변환한다.

```typescript
// 개념적 타입 변환 (단순화)
type DecorateProcedure<TProc> =
  TProc extends { _def: { _input_in: infer TIn; _output_out: infer TOut } }
    ? { query: (input: TIn) => Promise<TOut> }
    : never;

type DecorateRouter<TRouter> = {
  [K in keyof TRouter]: TRouter[K] extends Router
    ? DecorateRouter<TRouter[K]>       // 하위 라우터 재귀
    : DecorateProcedure<TRouter[K]>;   // procedure 데코레이트
};
```

이 재귀 매핑 덕분에 라우터가 아무리 깊게 중첩돼도 클라이언트 타입이 자동으로 따라온다. 다만 라우터가 극단적으로 커지면 이 재귀 인스턴스화가 tsserver의 타입 체크 시간을 늘려 IDE 반응성이 떨어진다. 이 경우 라우터를 lazy 하게 분할하거나 `inferRouterOutputs`로 필요한 부분만 추출해 컴포넌트에 넘기는 식으로 타입 계산 부담을 줄인다.

## 8. 실무 trade-off와 한계

tRPC는 프론트와 백이 **같은 TypeScript 모노레포**에 있을 때 가장 강력하다. 서버 타입을 직접 import 해야 하므로 언어가 다른 클라이언트(모바일 네이티브, 외부 파트너)에는 적용할 수 없다. 이 경우 tRPC는 내부 BFF 계층에만 쓰고 외부 경계는 OpenAPI로 노출하는 하이브리드 구성을 택한다. `trpc-openapi` 같은 어댑터가 procedure에 REST 메타데이터를 붙여 OpenAPI 스펙을 생성해 주지만, 이는 tRPC 순수 타입 흐름의 이점을 일부 포기하는 절충이다.

또 하나의 한계는 버전 관리다. 코드 생성 스펙이 없으므로 클라이언트와 서버가 항상 동일 커밋의 타입을 공유해야 한다. 독립 배포되는 서비스라면 서버가 먼저 배포돼 필드를 지웠을 때, 아직 이전 타입을 가진 클라이언트는 컴파일 시점엔 몰랐지만 런타임에 깨진다. 그래서 tRPC는 "함께 배포되는" 풀스택 앱에 적합하고, 독립 수명 주기를 갖는 마이크로서비스 경계에는 부적합하다. 이 경계 판단이 tRPC 도입의 핵심 의사결정이다.

## 9. React Query 통합과 타입 흐름의 종착점

tRPC 타입 흐름의 실전 종착점은 프론트엔드 데이터 페칭이다. `@trpc/react-query`는 tRPC 프록시를 TanStack Query 훅으로 감싸, 서버 procedure의 입출력 타입이 `useQuery`/`useMutation` 훅까지 그대로 이어지게 한다.

```typescript
export const trpc = createTRPCReact<AppRouter>();

function UserProfile({ id }: { id: string }) {
  // 입력 { id: string } 타입 검사, data 는 { id, name, age } 로 추론
  const { data, isLoading } = trpc.user.getById.useQuery({ id });
  if (isLoading) return <Spinner />;
  return <div>{data.name} ({data.age})</div>;  // data 프로퍼티 자동완성
}
```

여기서 두 라이브러리의 제네릭이 합성된다. tRPC가 procedure에서 추출한 `TInput`/`TOutput`이 TanStack Query의 `useQuery<TOutput, TError>` 제네릭으로 주입되고, 쿼리 키도 procedure 경로에서 자동 생성돼 캐시 무효화(`utils.user.getById.invalidate()`)가 타입 안전하게 동작한다. 서버에서 반환 필드를 바꾸면 컴포넌트의 `data.xxx` 접근부까지 컴파일 에러가 전파되는 것이 이 통합의 핵심 가치다.

주의할 함정은 직렬화 경계다. tRPC 기본 전송은 JSON이므로 `Date`, `Map`, `Set`, `BigInt` 같은 타입은 그대로 넘어가지 못한다. 서버 procedure가 `Date`를 반환해도 클라이언트는 문자열을 받는데, 타입은 여전히 `Date`로 추론돼 런타임과 타입이 어긋난다. 이를 막으려면 `superjson` 같은 transformer를 `initTRPC.create({ transformer })`에 등록해 양쪽에서 동일하게 직렬화·역직렬화한다. transformer를 붙이면 `Date`가 실제로 `Date` 인스턴스로 복원돼 타입과 런타임이 일치한다. 이 설정을 빠뜨리면 "타입은 Date인데 `.getTime()`이 런타임에 터지는" 미묘한 버그가 생긴다. 엔드투엔드 타입 안전성은 직렬화 계층까지 일관될 때 비로소 완성된다.

## 참고

- tRPC 공식 문서 — Concepts, Server, Client (trpc.io/docs)
- Zod 공식 문서 — Inference, Coercion, Transform (zod.dev)
- TypeScript Handbook — Mapped Types, Conditional Types, `infer`
- Colin McDonnell, "Zod and the Standard Schema" 설계 노트
