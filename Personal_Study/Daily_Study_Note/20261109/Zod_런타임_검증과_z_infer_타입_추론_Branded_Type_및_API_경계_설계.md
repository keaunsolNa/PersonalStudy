Notion 원본: https://app.notion.com/p/4135a06fd6d381549aadff6808cd1cb2

# Zod 런타임 검증과 z.infer 타입 추론: Branded Type 및 API 경계 설계

> 2026-11-09 신규 주제 · 확장 대상: TypeScript (런타임 검증 라이브러리)

## 학습 목표

- 컴파일 타임 타입과 런타임 값의 간극을 Zod 스키마 하나로 단일화한다
- `z.infer` / `z.input` / `z.output` 의 차이를 transform 과 default 로 확인한다
- Branded Type 으로 UserId 와 OrderId 같은 구조적으로 동일한 ID 의 혼용을 차단한다
- 외부 입력 경계(HTTP, 환경변수)에서 `safeParse` 와 오류 포맷팅 패턴을 적용한다

## 1. 왜 런타임 검증이 필요한가

TypeScript 타입은 컴파일 후 지워진다. `const body = req.body as CreateUser` 는 타입 단언일 뿐 실제 값을 검증하지 않는다. 외부 JSON, 환경변수, 쿼리스트링 같은 **신뢰 경계**에서는 런타임 검증이 필수이며, 같은 구조를 interface 와 검증 코드로 이중 정의하면 둘이 어긋난다. Zod 는 스키마를 단일 진실 원천으로 두고 `z.infer` 로 타입을 파생시킨다.

```ts
import { z } from 'zod';

const CreateUser = z.object({
  email: z.string().email(),
  age: z.number().int().min(0).max(150),
  role: z.enum(['admin', 'member']).default('member'),
});

type CreateUser = z.infer<typeof CreateUser>;
// { email: string; age: number; role: 'admin' | 'member' }
```

## 2. parse 와 safeParse

`parse` 는 실패 시 `ZodError` 를 던지고, `safeParse` 는 판별 유니온 `{ success: true; data } | { success: false; error }` 를 반환한다. 컨트롤러 경계에서는 예외 흐름보다 `safeParse` 가 400 응답 매핑에 적합하다.

```ts
const result = CreateUser.safeParse(req.body);
if (!result.success) {
  return res.status(400).json({
    errors: result.error.issues.map((i) => ({
      path: i.path.join('.'),
      message: i.message,
    })),
  });
}
const user = result.data; // CreateUser 타입으로 좁혀짐
```

## 3. input 과 output 타입 분리

`transform`, `default`, `coerce` 를 쓰면 입력 타입과 출력 타입이 달라진다. 이때 `z.infer` 는 **출력** 타입이다.

```ts
const Query = z.object({
  page: z.coerce.number().int().positive().default(1),
  tags: z.string().transform((s) => s.split(',')),
});

type QueryIn  = z.input<typeof Query>;   // { page?: unknown; tags: string }
type QueryOut = z.output<typeof Query>;  // { page: number; tags: string[] }
```

쿼리스트링은 항상 문자열이므로 `z.coerce.number()` 를 써야 한다. 단 `coerce.boolean()` 은 `Boolean('false') === true` 라서 위험하므로 `z.enum(['true','false']).transform(v => v === 'true')` 를 쓴다.

## 4. Branded Type 으로 명목적 구분

TypeScript 는 구조적 타입 시스템이라 `type UserId = string`, `type OrderId = string` 은 서로 대입 가능하다. 브랜딩은 타입에만 존재하는 유령 태그를 붙여 구분한다.

```ts
const UserId = z.string().uuid().brand<'UserId'>();
const OrderId = z.string().uuid().brand<'OrderId'>();
type UserId = z.infer<typeof UserId>;
type OrderId = z.infer<typeof OrderId>;

function cancel(order: OrderId, by: UserId) { /* ... */ }

const u = UserId.parse(crypto.randomUUID());
const o = OrderId.parse(crypto.randomUUID());
cancel(o, u);   // OK
// cancel(u, o) // 오류: UserId 는 OrderId 에 대입 불가
```

런타임 비용은 0 이다. 브랜드는 `string & z.BRAND<'UserId'>` 로 컴파일 타임에만 존재한다.

## 5. 환경변수 검증

서버 기동 시점에 한 번 검증해 잘못된 설정을 즉시 실패(fail-fast)시킨다.

```ts
const Env = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().url(),
});

export const env = Env.parse(process.env);
```

이후 `env.PORT` 는 number 로 보장된다. 누락 시 부팅이 즉시 실패하므로 배포 후 런타임에서 `undefined` 를 만나는 사고를 막는다.

## 6. 판별 유니온과 재귀 스키마

```ts
const Event = z.discriminatedUnion('type', [
  z.object({ type: z.literal('click'), x: z.number(), y: z.number() }),
  z.object({ type: z.literal('key'), code: z.string() }),
]);

type Category = { name: string; children: Category[] };
const Category: z.ZodType<Category> = z.lazy(() =>
  z.object({ name: z.string(), children: z.array(Category) }),
);
```

`discriminatedUnion` 은 판별 키로 바로 분기해 일반 `union` 보다 빠르고 오류 메시지도 정확하다. 재귀 스키마는 타입을 먼저 선언하고 `z.lazy` 로 감싸야 순환 추론 오류를 피한다.

## 7. 성능과 대안 비교

Zod 는 가독성과 생태계(react-hook-form, tRPC)가 강점이나 대량 배열 파싱에서는 AJV 나 Valibot 보다 느릴 수 있다. 10만 건 객체 배열 검증에서 컴파일형 검증기(AJV)는 수 배 빠른 경우가 많으므로, 핫패스는 벤치마크 후 결정한다. 번들 크기가 중요한 프론트엔드에서는 트리셰이킹이 좋은 Valibot 도 후보다. 성능이 병목이 아니면 개발 생산성 때문에 Zod 를 유지하는 편이 합리적이다.

## 참고

- Zod 공식 문서 (zod.dev): Basic usage, Branded types, Coercion
- TypeScript Handbook: Type Compatibility (Structural typing)
- Valibot / AJV 공식 문서 벤치마크 섹션
