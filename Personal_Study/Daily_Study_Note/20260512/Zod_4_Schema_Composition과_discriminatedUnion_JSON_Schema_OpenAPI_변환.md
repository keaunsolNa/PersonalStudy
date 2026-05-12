Notion 원본: https://www.notion.so/35e5a06fd6d381fb9bdaf69de914cb5f

# Zod 4 Schema Composition과 discriminatedUnion — JSON Schema/OpenAPI 변환

> 2026-05-12 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Zod 4 의 schema 합성(`.extend`, `.merge`, `.pick`, `.omit`, `.and`)이 internal 타입에 미치는 영향 추적
- `z.discriminatedUnion` 과 일반 `z.union` 의 성능·에러 메시지 차이 분석
- `@asteasolutions/zod-to-openapi` 로 Zod schema 에서 OpenAPI 3.1 문서를 생성
- runtime validation 과 compile-time inference 가 결합한 type-driven API 설계 패턴 이해

## 1. 왜 Zod 인가 — runtime + compile-time 의 동시 충족

TypeScript 타입은 컴파일 후 사라진다. API 경계, JSON 파일, 환경변수, 메시지 큐 같은 *외부 입력* 은 컴파일러가 검증해주지 않는다.

전통적 해법:

- `class-validator` + `class-transformer`: 데코레이터 기반, NestJS 가 채택. 클래스 의존.
- `Joi`, `ajv`: validation 만, 타입 추론은 별도.
- `io-ts`, `Effect Schema`: 함수형, learning curve 가 높음.
- **Zod**: schema 1개에서 *parse + type inference* 동시에. 4.x 부터 성능과 트리쉩이킹 개선.

## 2. 기본 schema 와 inference

```ts
import { z } from "zod";

const Order = z.object({
  id: z.string().uuid(),
  userId: z.string(),
  items: z.array(z.object({
    sku: z.string(),
    qty: z.number().int().positive(),
  })).min(1),
  total: z.number().nonnegative(),
  status: z.enum(["PENDING", "PAID", "SHIPPED", "CANCELED"]),
  createdAt: z.coerce.date(),
});

type Order = z.infer<typeof Order>;

const r = Order.safeParse(req.body);
if (!r.success) return res.status(400).json(r.error.flatten());
const order = r.data;
```

## 3. 합성 — extend / merge / pick / omit / partial

```ts
const BaseEntity = z.object({
  id: z.string().uuid(),
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date(),
});

const UserCreate = z.object({
  email: z.string().email(),
  name: z.string().min(1),
  age: z.number().int().min(13).max(120).optional(),
});

const User = BaseEntity.merge(UserCreate);
const UserPublic = User.omit({ updatedAt: true });
const UserUpdate = UserCreate.partial();
const UserSummary = User.pick({ id: true, email: true });
```

| 메서드 | 동작 | 키 충돌 시 |
|---|---|---|
| `.extend({...})` | 새 키 추가/덮어쓰기 | 인자 키가 승리 |
| `.merge(other)` | 두 ZodObject 합성 | 인자 schema 가 승리 |
| `.pick({...})` | 일부 키만 유지 | n/a |
| `.omit({...})` | 일부 키 제거 | n/a |
| `.partial()` | 모든 키 optional | n/a |
| `.required()` | 모든 키 required | n/a |
| `.passthrough()` | 정의되지 않은 키 보존 | 기본은 strip |
| `.strict()` | 정의 외 키 있으면 에러 | 기본은 silent |
| `.catchall(z.X)` | 나머지 키의 타입 강제 | n/a |

`.strict()` 는 API 요청 검증에서 *유효하지 않은 필드를 발견* 하고 싶을 때 필수.

## 4. discriminatedUnion vs union

```ts
const Event = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("CLICK"), x: z.number(), y: z.number() }),
  z.object({ kind: z.literal("SCROLL"), delta: z.number() }),
  z.object({ kind: z.literal("INPUT"), value: z.string() }),
]);

const e = Event.parse({ kind: "CLICK", x: 10, y: 20 });
```

장점:

1. **O(1) 분기** — 직접적 latency 차이는 작지만 deeply nested 한 경우 누적
2. **명확한 에러** — `kind` 가 잘못된 경우 "expected one of CLICK | SCROLL | INPUT" 으로 정확히 알려줌
3. **타입 narrowing 향상** — 컴파일 시 더 정확

## 5. Recursive schema

```ts
type Comment = {
  id: string;
  text: string;
  replies: Comment[];
};

const Comment: z.ZodType<Comment> = z.lazy(() =>
  z.object({
    id: z.string(),
    text: z.string(),
    replies: z.array(Comment),
  })
);
```

## 6. Brand 와 Domain Primitive

```ts
const UserId = z.string().uuid().brand<"UserId">();
type UserId = z.infer<typeof UserId>;

function getUser(id: UserId) { /* ... */ }

const safeId = UserId.parse("550e8400-e29b-41d4-a716-446655440000");
getUser(safeId);  // OK
```

## 7. Refinement 와 transform

```ts
const Password = z
  .string()
  .min(8)
  .refine((v) => /[A-Z]/.test(v), { message: "대문자 1개 이상" })
  .refine((v) => /[0-9]/.test(v), { message: "숫자 1개 이상" })
  .refine((v) => !/^(password|qwerty)/i.test(v), {
    message: "약한 비밀번호 패턴 금지",
  });

const SignupForm = z
  .object({
    password: Password,
    passwordConfirm: z.string(),
  })
  .refine((d) => d.password === d.passwordConfirm, {
    message: "비밀번호가 일치하지 않습니다",
    path: ["passwordConfirm"],
  });

const NormalizedEmail = z
  .string()
  .email()
  .transform((s) => s.toLowerCase().trim());
```

`transform` 은 *parse 결과* 의 타입을 바꿞다. input/output 타입이 달라지는 경우 `z.input<typeof X>` 와 `z.output<typeof X>` 를 구분해 사용.

## 8. JSON Schema / OpenAPI 변환

```ts
import { extendZodWithOpenApi, OpenAPIRegistry, OpenApiGeneratorV3 } from
  "@asteasolutions/zod-to-openapi";
import { z } from "zod";

extendZodWithOpenApi(z);

const registry = new OpenAPIRegistry();

const Order = z.object({
  id: z.string().uuid().openapi({ example: "550e8400-..." }),
  total: z.number().nonnegative(),
  status: z.enum(["PENDING", "PAID", "SHIPPED"])
    .openapi({ description: "주문 상태" }),
}).openapi("Order");

registry.registerPath({
  method: "get",
  path: "/orders/{id}",
  summary: "주문 조회",
  request: {
    params: z.object({ id: z.string().uuid() }),
  },
  responses: {
    200: { description: "성공", content: { "application/json": { schema: Order } } },
    404: { description: "없음" },
  },
});

const generator = new OpenApiGeneratorV3(registry.definitions);
const doc = generator.generateDocument({
  openapi: "3.0.3",
  info: { title: "Orders API", version: "1.0.0" },
});
```

## 9. 성능 측면

| 작업 | Zod 3.x | Zod 4.x | 비교 |
|---|---|---|---|
| 단순 object parse | ~5μs | ~2.5μs | ~2x 빠름 |
| discriminatedUnion(10 variants) | ~12μs | ~4μs | ~3x |
| recursive(depth 10) | ~80μs | ~40μs | ~2x |
| `.transform()` chain 5단 | ~30μs | ~15μs | ~2x |

schema 는 모듈 top-level 에 한 번 정의. hot path 에서 매번 `z.object({...}).parse(...)` 로 schema 자체를 새로 만들면 캐시 효과가 사라진다.

## 10. NestJS / Fastify / Hono 통합

NestJS:

```ts
@Post()
createUser(
  @Body(new ZodValidationPipe(UserCreate)) dto: z.infer<typeof UserCreate>
) { /* ... */ }
```

Fastify:

```ts
fastify.withTypeProvider<ZodTypeProvider>().post(
  "/users",
  { schema: { body: UserCreate, response: { 200: User } } },
  async (req) => createUser(req.body)
);
```

Hono:

```ts
app.post("/users", zValidator("json", UserCreate), (c) => {
  const body = c.req.valid("json");
  return c.json(createUser(body));
});
```

## 참고

- Zod Docs — https://zod.dev/
- "Zod 4 release notes" — https://github.com/colinhacks/zod/releases
- @asteasolutions/zod-to-openapi — https://github.com/asteasolutions/zod-to-openapi
- Fastify Type Provider Zod — https://github.com/turkerdev/fastify-type-provider-zod
- Total TypeScript: Zod patterns — https://www.totaltypescript.com/tutorials/zod
