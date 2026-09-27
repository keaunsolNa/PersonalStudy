Notion 원본: https://www.notion.so/3e85a06fd6d3811191accfee2505dc4e

# TypeScript tRPC End-to-End 타입 추론과 라우터 타입 전파 및 클라이언트 프록시 생성

> 2026-09-27 신규 주제 · 확장 대상: TypeScript, REST_API

## 학습 목표

- tRPC가 코드 생성 없이 서버 라우터 타입을 클라이언트로 전파하는 구조적 전제조건(모노레포, `import type`)을 설명한다
- `initTRPC`와 procedure builder 체이닝이 제네릭을 누적시켜 라우터 타입을 합성하는 과정을 추적한다
- `createTRPCProxyClient`가 JS Proxy로 경로 호출을 procedure 호출로 변환하는 원리와 Zod 통합 타입 추론을 이해한다
- REST/GraphQL 대비 트레이드오프(컴파일 타임 비용, 폴리글랏 클라이언트 지원 불가)를 판단하고 마이그레이션 전략을 수립한다

## 1. tRPC의 핵심 아이디어 — 코드 생성 없는 타입 공유

REST의 근본적 한계는 서버가 노출하는 계약(contract)이 런타임에만 존재한다는 점이다. OpenAPI 스펙을 만들고 `openapi-typescript`나 `orval` 같은 codegen 도구로 클라이언트 타입을 생성하는 방식이 널리 쓰이지만, "서버 변경 → 스펙 재생성 → 클라이언트 타입 재생성" 파이프라인을 항상 거쳐야 한다(GraphQL도 `graphql-codegen`이 필요). 이 파이프라인이 끊기면 클라이언트 타입은 서버 실제 동작과 조용히 어긋나고 런타임에만 드러나는 버그로 이어진다.

tRPC의 핵심 아이디어는 이 codegen 스텝을 완전히 제거하는 것이다. 서버 라우터 객체의 타입을 TypeScript 타입 시스템 자체로 클라이언트가 그대로 참조하게 만든다. `import type`으로 값이 아닌 타입만 임포트할 수 있고 번들러가 이를 제거(erase)할 수 있어, 클라이언트는 서버 구현 코드를 번들에 포함하지 않으면서도 타입 정보만 컴파일 타임에 참조한다.

이 방식은 서버·클라이언트 패키지가 **동일한 TypeScript 프로젝트 그래프 안에 있어야** 성립한다. Turborepo, Nx, pnpm workspace 모노레포에서 `packages/server`와 `apps/web`이 하나의 `tsconfig` 참조 체계로 묶여야 하고, 클라이언트 타입체커가 서버 소스에 접근할 수 있어야 한다 — 이는 7절 트레이드오프의 근본 원인이다.

```typescript
// packages/server/src/index.ts — 값이 아닌 타입만 export
export type { AppRouter } from './router';
// apps/web/src/trpc.ts — import type이므로 번들에서 완전히 사라진다(런타임 코드 0바이트)
import type { AppRouter } from '@my-monorepo/server';
```

## 2. 라우터 정의와 제네릭 타입 합성 — procedure builder 체이닝

tRPC 서버 코드는 `initTRPC.create()`로 시작해 `Context`(인증 정보, DB 커넥션 객체) 타입을 제네릭으로 고정한다. `.use()` 체이닝은 런타임 미들웨어 등록을 넘어 **제네릭 타입 파라미터를 누적**시켜, `protectedProcedure`는 `userId: string`으로 좁혀진 새 컨텍스트를 담고 핸들러 안에서 `ctx.userId`가 `string`으로 추론된다.

```typescript
// packages/server/src/trpc.ts
import { initTRPC } from '@trpc/server';
interface Context { userId: string | null; db: DbClient; }

const t = initTRPC.context<Context>().create();
export const router = t.router;
export const publicProcedure = t.procedure;
export const protectedProcedure = t.procedure.use(({ ctx, next }) => {
  if (!ctx.userId) throw new Error('UNAUTHORIZED');
  return next({ ctx: { ...ctx, userId: ctx.userId } }); // string으로 narrowing
});
```

각 체이닝 메서드는 이전 제네릭을 소비해 더 구체적인 제네릭을 가진 빌더 타입을 반환한다("타입 상태 머신" 패턴). `input()`은 `TInput`을 Zod 추론 타입으로, `use()`는 `TContext`를 새 컨텍스트로 대체하며, 마지막 `query()`/`mutation()`이 누적된 제네릭을 소비해 procedure 타입(`QueryProcedure<TInput, TOut>`)을 만든다. 라우터도 동일한 합성으로, `t.router({ ... })`의 각 키가 procedure이거나 하위 라우터일 수 있고 반환 타입은 그 키-값 구조를 그대로 매핑한다.

```typescript
// packages/server/src/router.ts
import { z } from 'zod';
import { router, publicProcedure, protectedProcedure } from './trpc';

const userRouter = router({
  getById: publicProcedure.input(z.object({ id: z.string().uuid() }))
    .query(async ({ input, ctx }) => ctx.db.user.findUnique({ where: { id: input.id } })),
  updateProfile: protectedProcedure.input(z.object({ name: z.string().min(1) }))
    .mutation(async ({ input, ctx }) => ctx.db.user.update({ where: { id: ctx.userId }, data: input })),
});

export const appRouter = router({ user: userRouter });
export type AppRouter = typeof appRouter; // 클라이언트가 참조할 단 하나의 타입
```

`typeof appRouter`는 재귀적 객체 타입이다. `AppRouter['user']['getById']`처럼 경로를 따라가면 그 procedure의 input/output 타입에 도달하며, 이 재귀 구조가 클라이언트 Proxy가 활용하는 핵심 자료다.

## 3. 서버 라우터 타입을 클라이언트가 어떻게 아는가

`export type`/`import type`의 관계는 단순한 "타입 재사용" 이상이다. 컴파일러는 `import type`이 순수 타입 공간에서만 쓰인다는 것을 알고 트랜스파일 시점에 결과 JS에서 완전히 제거하며, `isolatedModules`가 켜진 환경(Vite, esbuild, SWC 계열)에서는 파일 단위로 이 제거가 안전하게 보장된다. 클라이언트 번들에는 서버의 리졸버, DB 쿼리, 미들웨어 구현이 1바이트도 포함되지 않으며, TypeScript 컴파일러만 `tsconfig.json`의 `references`/`paths`로 서버 소스를 읽는 위치에서 실행되어야 한다.

클라이언트가 참조하는 것이 빌드 결과물이든 소스든 **타입 정보만 필요하다**는 점이 핵심이다. 프로덕션에서는 `tsc --emitDeclarationOnly`로 `.d.ts`만 배포해 소스 노출을 막으면서도 타입 추론은 유지하기도 한다. REST는 발행-구독 모델이라 다른 언어여도 무방하지만, tRPC는 같은 타입 그래프를 공유하는 대가로 동기화 지연을 원천 차단한다.

## 4. 클라이언트 Proxy 생성 메커니즘

`AppRouter` 타입만으로는 네트워크 요청을 보낼 방법이 없다. tRPC 클라이언트의 핵심은 `createTRPCProxyClient`(v11에서는 `createTRPCClient`)가 JavaScript `Proxy`로 **런타임에는 존재하지 않는 메서드 호출을 타입 시스템 상으로만 존재하는 것처럼** 만든다는 데 있다.

```typescript
// apps/web/src/trpc.ts
import { createTRPCProxyClient, httpBatchLink } from '@trpc/client';
import type { AppRouter } from '@my-monorepo/server'; // 타입만!

export const trpc = createTRPCProxyClient<AppRouter>({
  links: [httpBatchLink({ url: 'http://localhost:3000/api/trpc' })],
});

// trpc.user.getById는 실제 객체에 없는 프로퍼티 — Proxy가 가로채 HTTP 요청으로 변환
async function loadUser(id: string) {
  return trpc.user.getById.query({ id }); // 반환 타입은 서버 리졸버 반환값으로 추론
}
```

`createTRPCProxyClient`가 반환하는 객체는 속이 텅 빈 target에 `Proxy` 핸들러를 씌운 것이다. `get` 트랩은 프로퍼티 접근마다 이름을 누적한 새 Proxy를 반환하고 `apply` 트랩이 최종 호출을 실제 요청으로 변환한다. `trpc.user.getById.query(...)`는 `get('user')→get('getById')→get('query')→apply(...)` 순서로 트랩을 통과하며 쌓인 경로와 인자로 HTTP 요청을 만든다. 존재 여부·호출 방식 검사는 오직 컴파일 타임에 `AppRouter` 제네릭으로만 이뤄진다 — "런타임 오버헤드 없는 타입 안전성"의 근거다. 클라이언트 타입은 재귀 구조를 순회하며 leaf procedure를 `{ query: (input) => Promise<output> }` 형태로 바꾸는 conditional mapped type이며, 이 타입이 Proxy의 "가짜" 선언이 되고 get/apply 트랩이 이를 뒷받침해 원격 함수가 로컬 함수처럼 보이게 만든다.

## 5. Zod 스키마와의 통합 — 하나의 스키마에서 검증과 타입이 동시에

REST API에서 입력 검증과 타입 정의는 보통 별개라 Joi나 class-validator로 런타임 검증을, 별도의 `interface`나 DTO로 타입을 정의하고 수동 동기화해야 한다. tRPC + Zod의 핵심 이점은 **검증 스키마 자체가 타입의 원천(single source of truth)** 이 된다는 것이다.

```typescript
import { z } from 'zod';

const createPostSchema = z.object({
  title: z.string().min(1).max(200),
  content: z.string(),
  tags: z.array(z.string()).max(5),
  publishedAt: z.date().optional(),
});
// z.infer는 조건부 타입으로 런타임 스키마로부터 정적 타입을 역추출한다
type CreatePostInput = z.infer<typeof createPostSchema>;
```

Zod의 모든 스키마는 `_output`이라는 팬텀 프로퍼티(타입 레벨에서만 존재)를 가진 `ZodType<Output, Def, Input>`의 인스턴스이며, `z.infer<T>`는 이를 `infer`로 추출하는 조건부 타입일 뿐이다. 스키마는 `.input()`에 전달되면 `TInput` 제네릭으로 흘러 들어가고 동시에 `schema.parse(rawInput)`이 서버 런타임 검증을 수행한다. 하나의 스키마가 (1) 런타임 검증, (2) 핸들러 `input` 타입, (3) 클라이언트 호출 인자 타입을 동시에 결정해, 수정 시 세 곳이 컴파일 에러로 강제 일관성을 유지한다.

```typescript
// 서버
export const postRouter = router({
  create: protectedProcedure.input(createPostSchema)
    .mutation(async ({ input, ctx }) => ctx.db.post.create({ data: { ...input, authorId: ctx.userId } })),
});
// 클라이언트 — tags에 문자열이 아닌 값을 넣으면 여기서 컴파일 에러 발생
await trpc.post.create.mutate({ title: '제목', content: '본문', tags: ['ts', 'trpc'] });
```

output 타입도 동일 원리로 추론되지만, `.output(schema)`를 지정하지 않으면 리졸버 반환 타입을 그대로 추론에 맡겨 ORM이 내부 필드까지 포함한 전체 row를 반환해도 타입 시스템이 걸러내지 않으므로, 민감 정보 누출 방지를 위해 `.output(schema)`로 명시적 화이트리스트를 거는 것이 안전하다.

## 6. 배치(batching)와 링크(link) 시스템

tRPC 클라이언트의 HTTP 계층은 `link`라는 미들웨어 체인으로 구성된다. `httpBatchLink`가 가장 흔히 쓰이는데, 짧은 시간(기본 0ms, 동일 microtask/tick 안)에 발생한 여러 procedure 호출을 **하나의 HTTP 요청으로 묶어서** 보낸다.

```typescript
const [user, posts, comments] = await Promise.all([
  trpc.user.getById.query({ id: 'u1' }),
  trpc.post.listByUser.query({ userId: 'u1' }),
  trpc.comment.recent.query({ limit: 10 }),
]);
```

`httpBatchLink`는 이 세 쿼리를 마이크로태스크 큐가 flush되기 전까지 모아뒀다가 단일 `POST /api/trpc/user.getById,post.listByUser,comment.recent?batch=1` 요청(JSON 배열 body)으로 병합해 보낸다. 서버 adapter는 배치를 파싱해 각 procedure를 병렬 실행하고 성공/실패를 담은 배열로 응답한다 — GraphQL의 단일 엔드포인트-복수 필드 조회와 유사한 효과를 REST 위에서 얻는 방식이다.

링크는 배열로 조합되며 각 링크가 다음 링크를 감싸는 미들웨어 체인(onion model)을 이뤄, 로깅 링크를 바깥에 HTTP 링크를 안쪽에 두면 인증 헤더 주입·로깅·URL 길이 초과 시 자동 POST 전환까지 체인 하나로 처리된다. 배치 트레이드오프도 있다. 여러 쿼리를 묶으면 요청 수는 줄지만 그중 하나가 느린 DB 쿼리를 포함하면 전체 응답이 그만큼 지연되므로(head-of-line blocking과 유사), 지연에 민감한 쿼리는 `httpLink`(배치 없음)로 별도 링크 체인을 구성하거나 `splitLink`로 procedure별 라우팅을 한다.

## 7. REST/GraphQL 대비 트레이드오프

세 방식을 실무 기준으로 비교하면 다음과 같다.

| 항목 | REST(+OpenAPI) | GraphQL | tRPC |
| --- | --- | --- | --- |
| 타입 동기화 | 스펙→codegen | 스키마→codegen | TS 타입 직접 공유 |
| 폴리글랏 클라이언트 | 가능 | 가능 | 불가(TS/JS 전용) |
| 모노레포 강제 | 불필요 | 불필요 | 사실상 강제 |
| 오버/언더페칭 | 설계 의존 | 필드 선택 가능 | REST와 유사 |
| 캐싱 | HTTP 캐시 활용 | 별도 레이어 필요 | 가능하나 배치로 복잡 |
| 대형 스키마 IDE 반응성 | 영향 적음 | 영향 적음 | 라우터 커질수록 저하 |
| 러닝커브 | 낮음 | 중간 | 낮음 |

가장 체감되는 트레이드오프는 두 가지다. 첫째, **폴리글랏 클라이언트 지원 불가**다. 타입 안전성이 컴파일러가 서버 타입을 읽을 수 있다는 전제에 의존하므로, 네이티브 앱이나 다른 언어 백엔드가 API를 소비해야 하면 부적합하며 REST/gRPC 같은 언어 중립적 계약이 다시 필요해진다. `trpc-openapi` 같은 어댑터로 REST 스펙을 부가 생성하기도 하지만 별도 동기화 지점을 다시 만들 뿐이다.

둘째, **대형 라우터의 컴파일 타임 비용**이다. procedure가 수백 개면 `AppRouter`가 매우 깊고 넓은 재귀적 타입이 되어, 매 호출마다 컴파일러가 조건부/매핑된 타입을 평가하는 비용이 라우터 크기에 선형보다 나쁘게 증가한다. procedure 500~1000개를 넘으면 tsserver 재계산에 수 초 이상 걸리거나 `tsc --noEmit` 빌드가 수 분 단위로 늘어나는 사례가 보고되며, 완화책은 라우터를 도메인별로 분리하고 `incremental`/`skipLibCheck`/`composite`를 켜는 것이다.

## 8. 실전 마이그레이션 전략과 REST 병행 운영

기존 REST 서비스를 tRPC로 전면 교체하는 것은 위험 부담이 크다. 검증된 접근은 **점진적 병행 운영(strangler fig 패턴)** — 신규 기능이나 내부 어드민 도구부터 tRPC로 작성하고, 기존 공개 REST 엔드포인트는 유지하면서 내부적으로 tRPC procedure를 호출하는 얇은 어댑터 레이어를 두는 방식이다.

```typescript
// packages/server/src/rest-adapter.ts — 기존 Express REST 라우트가 tRPC 프로시저를 재사용
import { appRouter } from './router';
import { createCallerFactory } from './trpc';

const createCaller = createCallerFactory(appRouter); // HTTP/Proxy 없이 내부에서 procedure 직접 호출
app.get('/api/v1/users/:id', async (req, res) => {
  const caller = createCaller({ userId: req.auth?.userId ?? null, db });
  res.json(await caller.user.getById({ id: req.params.id }));
});
// 신규 클라이언트(내부 어드민 등)는 tRPC 엔드포인트를 직접 사용
app.use('/api/trpc', createExpressMiddleware({ router: appRouter }));
```

핵심은 `createCallerFactory`로 procedure 로직을 한 번만 작성하고 REST 라우트는 그 결과를 JSON 직렬화하는 얇은 래퍼로 남기는 것이다. 로직 중복 없이 두 계약을 동시에 만족시키며, 외부 REST 계약을 유지하면서 내부 프론트엔드는 tRPC 타입 안전성을 그대로 누린다.

마이그레이션 우선순위는 클라이언트가 전적으로 TypeScript/모노레포 안에 있는 어드민·사내 도구부터 전환하고, 서드파티 연동 표면은 REST로 유지하는 기준이 유효하다. Next.js Server Components를 쓴다면 `createCaller`로 직접 호출해 네트워크 왕복을 생략할 수도 있다.

## 참고

- tRPC 공식 문서 — https://trpc.io/docs
- tRPC: Server-side Calls — https://trpc.io/docs/server/server-side-calls
- tRPC: Links — https://trpc.io/docs/client/links
- Zod 공식 문서 — https://zod.dev
- Zod: Type inference — https://zod.dev/?id=type-inference
- TS 핸드북: Type-Only Imports/Exports — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-8.html#type-only-imports-and-export