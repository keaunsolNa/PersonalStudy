Notion 원본: https://www.notion.so/37f5a06fd6d3817896c4e97ccac119bc

# TypeScript tRPC End-to-End 타입 추론과 Builder 패턴 기반 Procedure 합성

> 2026-06-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 코드 생성 없이 서버 타입을 클라이언트가 추론하는 구조를 설명한다
- builder 패턴이 미들웨어로 context 를 누적·확장하는 타입 추론을 추적한다
- input 검증 스키마에서 출력 타입까지 `inferRouterOutputs` 가 흐르는 경로를 본다
- tRPC 의 적용 한계(단일 TS 모노레포 전제)와 대안을 판단한다

## 1. end-to-end 타입 안전의 원리

tRPC 의 핵심은 코드 생성이나 스키마 파일(OpenAPI, GraphQL SDL) 없이, 서버에서 정의한 router 의 타입을 클라이언트가 `import type` 으로 그대로 가져와 추론하는 것이다. 런타임 의존이 아니라 타입 의존이므로 빌드 산출물에는 서버 코드가 섞이지 않는다. 서버가 `AppRouter` 라는 거대한 타입을 export 하면, 클라이언트는 그 타입만으로 모든 프로시저의 입력·출력 형태를 컴파일 타임에 안다.

```typescript
// server/router.ts
export const appRouter = router({
  user: router({
    byId: publicProcedure
      .input(z.object({ id: z.string() }))
      .query(({ input }) => findUser(input.id)), // 반환: User
  }),
});
export type AppRouter = typeof appRouter; // 타입만 export
```

```typescript
// client.ts — 런타임 import 없이 타입만 공유
import type { AppRouter } from '../server/router';
const trpc = createTRPCClient<AppRouter>({ links: [httpBatchLink({ url })] });

const user = await trpc.user.byId.query({ id: 'u1' }); // user: User 로 추론
// trpc.user.byId.query({ id: 123 }) → 컴파일 에러: id 는 string
```

`typeof appRouter` 가 모든 중첩 router·procedure·input·output 을 담은 한 덩어리 타입이므로, 클라이언트의 `trpc.user.byId.query` 경로와 인자·반환 타입이 전부 서버 정의에서 파생된다. 서버에서 procedure 를 바꾸면 클라이언트 호출부에 즉시 타입 에러가 떠 drift 가 원천 차단된다.

## 2. builder 패턴과 context 누적

tRPC 의 procedure 는 builder 체인으로 만든다. `.use(middleware)`, `.input(schema)` 를 이어 붙일 때마다 builder 의 제네릭 파라미터가 갱신되어 다음 단계로 누적된 타입 정보를 전달한다. 특히 미들웨어가 context 를 확장하면 그 확장된 context 타입이 핸들러까지 흘러간다.

```typescript
const t = initTRPC.context<{ db: Db; user?: User }>().create();

const isAuthed = t.middleware(({ ctx, next }) => {
  if (!ctx.user) throw new TRPCError({ code: 'UNAUTHORIZED' });
  return next({ ctx: { user: ctx.user } }); // user 가 non-null 로 narrow 된 새 ctx
});

const protectedProcedure = t.procedure.use(isAuthed);

const me = protectedProcedure.query(({ ctx }) => {
  return ctx.user.name; // ctx.user 는 User (optional 아님) — 미들웨어 결과 추론
});
```

여기서 builder 가 타입을 어떻게 나르는지가 핵심이다. `t.procedure` 의 타입 파라미터에는 현재 context 형태가 들어 있고, `.use(isAuthed)` 는 `next({ ctx })` 가 반환한 새 context 타입을 추출해 builder 의 context 파라미터를 덮어쓴 새 builder 를 만든다. 결과적으로 `protectedProcedure` 의 핸들러에서 `ctx.user` 가 `User`(non-null)로 추론된다. 미들웨어 체인을 길게 이어도 각 단계의 context 변형이 타입 수준에서 합성된다.

```typescript
interface ProcedureBuilder<TContext, TInput> {
  use<TNewContext>(
    mw: Middleware<TContext, TNewContext>,
  ): ProcedureBuilder<TNewContext, TInput>; // context 교체
  input<TSchema extends ZodType>(
    schema: TSchema,
  ): ProcedureBuilder<TContext, z.infer<TSchema>>; // input 타입 주입
  query<TOutput>(
    resolver: (opts: { ctx: TContext; input: TInput }) => TOutput,
  ): Procedure<TInput, Awaited<TOutput>>;
}
```

`.input(schema)` 가 `z.infer<TSchema>` 로 input 타입을 추출해 builder 에 심고, `.query(resolver)` 가 그 input 과 누적된 context 를 resolver 인자로 묶어 준다. 모든 정보가 제네릭 파라미터를 타고 한 방향으로 흘른다.

## 3. input 검증 스키마에서 출력 타입까지

`.input()` 에 넘기는 것은 단순 타입이 아니라 Zod 같은 런타임 검증 스키마다. 이것이 tRPC 의 두 번째 안전망이다. 컴파일 타임에는 `z.infer` 로 input 타입을 추론하고, 런타임에는 같은 스키마로 실제 요청 본문을 파싱·검증한다. 타입과 검증이 단일 소스(스키마 하나)에서 나오므로 둘이 어긋날 수 없다.

```typescript
const createUser = publicProcedure
  .input(z.object({
    email: z.string().email(),
    age: z.number().int().min(0),
  }))
  .mutation(({ input }) => {
    // input: { email: string; age: number } — z.infer 로 추론
    return userRepo.save(input);
  });
```

출력 타입은 별도 도구로 역추출한다. 클라이언트가 응답 타입을 다른 곳(예: React state)에 재사용할 때 `inferRouterOutputs` 와 `inferRouterInputs` 를 쓴다.

```typescript
import type { inferRouterOutputs, inferRouterInputs } from '@trpc/server';

type Outputs = inferRouterOutputs<AppRouter>;
type Inputs = inferRouterInputs<AppRouter>;

type CreatedUser = Outputs['user']['create'];
type CreateUserInput = Inputs['user']['create'];
```

이 유틸들은 router 타입을 재귀적으로 순회하며 각 procedure 의 input/output 위치 타입을 매핑된 타입으로 뽑아낸다. 덕분에 컴포넌트 props 나 폼 타입을 손으로 다시 선언하지 않고 서버 정의에서 직접 끌어 쓴다.

## 4. 어떤 trade-off 를 받아들이는가

tRPC 의 강력함은 강한 전제 위에 선다. 첫째 클라이언트와 서버가 같은 TypeScript 모노레포에서 타입을 공유해야 한다. 서버가 Java/Go 거나, 외부 서드파티가 API 를 소비하거나, 다국어 클라이언트가 붙는 공개 API 라면 tRPC 의 타입 공유 모델이 성립하지 않는다. 이 경우 OpenAPI·GraphQL 처럼 언어 중립적 계약(contract)이 필요하다.

둘째 tRPC 는 명세 산출물을 만들지 않는다. 타입은 코드 안에만 있으므로 외부에 문서화된 스키마를 제공하려면 `trpc-openapi` 같은 어댑터로 OpenAPI 를 별도 생성해야 한다. 셋째 거대한 `AppRouter` 타입은 컴파일러 부담이 될 수 있다. procedure 수가 수백 개로 늘면 타입 인스턴스화가 깊어져 IDE 응답과 빌드가 느려질 수 있고, router 분할·`satisfies` 활용·타입 단순화가 필요해진다.

| 비교 | tRPC | OpenAPI/codegen | GraphQL |
|------|------|----------------|---------|
| 타입 공유 방식 | TS 타입 직접 import | 코드 생성 | SDL + codegen |
| 언어 중립성 | 없음(TS 전용) | 있음 | 있음 |
| 코드 생성 단계 | 불필요 | 필요 | 필요 |
| 외부 공개 API | 부적합 | 적합 | 적합 |
| 모노레포 내부 API | 매우 적합 | 과함 | 과함 |

## 5. links 체인과 배치 전송의 타입 보존

클라이언트는 `links` 배열로 요청 파이프라인을 구성한다. 각 link 는 요청을 가공·전달하는 미들웨어로, 로깅·재시도·배치·분기 등을 담당하면서도 end-to-end 타입을 깨지 않는다. 대표적으로 `httpBatchLink` 는 짧은 시간 창 안의 여러 query 를 하나의 HTTP 요청으로 묶어 라운드트립을 줄인다. 중요한 점은 이 배치가 순전히 전송 계층 최적화이고, 호출부의 타입은 그대로 유지된다는 것이다.

```typescript
const trpc = createTRPCClient<AppRouter>({
  links: [
    loggerLink({ enabled: () => true }),
    httpBatchLink({ url: '/api/trpc', maxURLLength: 2083 }),
  ],
});

const [a, b] = await Promise.all([
  trpc.user.byId.query({ id: 'u1' }), // a: User
  trpc.post.list.query({ limit: 10 }), // b: Post[]
]);
```

link 는 분기도 가능하다. `splitLink` 로 구독(subscription)은 WebSocket link 로, 일반 query/mutation 은 HTTP batch link 로 나눴 보내면서도 단일 `AppRouter` 타입을 공유한다. 전송 방식이 달라져도 타입 계약은 하나로 유지되는 것이 핵심이다.

## 6. React Query 통합과 타입 흐름

실전에서 tRPC 는 TanStack Query(React Query)와 결합해 쓰는 경우가 많다. `@trpc/react-query` 는 각 procedure 를 `useQuery`/`useMutation` 훅으로 노출하면서, 서버에서 추론된 input·output 타입을 훅 시그니처에 그대로 전파한다. 캐시 키도 procedure 경로에서 자동 생성되므로 수동 키 관리가 사라진다.

```typescript
const utils = trpc.useUtils();
const { data, isLoading } = trpc.user.byId.useQuery({ id: 'u1' });
//      data: User | undefined — 서버 반환 타입이 훅까지 전파

const mutation = trpc.user.create.useMutation({
  onSuccess: () => utils.user.list.invalidate(), // 타입 안전한 캐시 무효화
});
mutation.mutate({ email: 'a@b.com', age: 30 }); // input 타입 검증
```

여기서 `utils.user.list.invalidate()` 의 경로가 실제 router 구조와 일치하지 않으면 컴파일 에러가 난다. 즉 캐시 무효화 대상 procedure 의 존재 여부까지 타입으로 보장된다. 서버에서 procedure 이름을 바꾸면 클라이언트의 호출·캐시 키·무효화 코드가 한꺼번에 타입 에러로 드러나, 리팩터링 안전성이 매우 높다.

## 7. 정리

tRPC 는 "서버 타입을 클라이언트가 그대로 추론한다"는 한 가지 발상을, builder 의 제네릭 누적·`z.infer` 기반 input 추론·`inferRouterOutputs` 역추출로 끝까지 밀어붙인 결과다. 코드 생성 없이 컴파일 타임 안전과 런타임 검증을 단일 스키마로 통일하는 점이 가장 큰 가치다. "tRPC 를 항상 써야 하는가"의 답은 No 다. TS 단일 스택의 내부 API(특히 Next.js 풀스택)에서는 강력하게 Yes 지만, 다국어 클라이언트나 공개 계약이 필요한 순간 OpenAPI/GraphQL 로 가는 것이 옷다. 선택 기준은 "타입을 코드로 공유할 수 있는 폐쇄된 TS 경계인가"이다.

## 참고

- tRPC 공식 문서 — Procedures, Middlewares, Inferring Types, Context
- Zod 문서 — z.infer 와 schema-first 타입 파생
- Theo Browne / Ping Labs — tRPC end-to-end type safety 설명 자료
- trpc-openapi 어댑터 문서 — tRPC router 에서 OpenAPI 스키마 생성
