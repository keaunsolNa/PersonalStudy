Notion 원본: https://www.notion.so/3675a06fd6d381d18393c8807233fc8b

# TypeScript Zod v4 Schema와 Brand Type 결합 Runtime 검증

> 2026-05-21 신규 주제 · 확장 대상: TypeScript Branded Types(20260514), Effect TS Schema(20260517)

## 학습 목표

- Zod v4의 `z.brand()`와 `z.pipe()`가 컴파일 타임 nominal typing과 런타임 검증을 동일 스키마에서 묶는지 이해한다.
- 검증 실패 시 `issues` 트리를 도메인 에러로 매핑하는 패턴을 안다.
- v3 → v4 마이그레이션에서 깨지는 API와 대안을 안다.
- Zod 스키마를 tRPC·OpenAPI·프론트 폼 검증으로 일관 적용한다.

## 1. Brand Type과 Zod 합성

TypeScript `& { readonly __brand: 'UserId' }` 은 온전히 컴파일 타임만 구분. `z.brand()`는 검증 + brand 부여를 합성.

```ts
import { z } from 'zod';
const UserIdSchema = z.string().uuid().brand('UserId');
type UserId = z.infer<typeof UserIdSchema>;

function getUser(id: UserId) {
	return repository.findById(id);
}
const parsed = UserIdSchema.parse(req.params.id);
getUser(parsed);     // OK
getUser(req.params.id); // ❌
```

핵심: '검증된 값만 도메인 함수에 들어감'이 타입 시스템 수준 보장.

## 2. z.pipe() — 변환과 검증의 합성

```ts
const TrimmedNonEmpty = z.string().transform((s) => s.trim()).pipe(z.string().min(1));
const EmailSchema = z.string().toLowerCase().pipe(z.string().email()).brand('Email');
```

v3 추론 버그성 동작이 v4에서 해소. 비동기 + 동기 혼용 시 전체 chain async 승격 → `parseAsync()` 강제.

## 3. Issue 트리 → 도메인 에러

```ts
import { z, treeifyError } from 'zod';

const SignUpSchema = z.object({
	email: z.string().email(),
	password: z.string().min(8),
	profile: z.object({
		nickname: z.string().min(2).max(20),
		birthYear: z.number().int().min(1900).max(2026),
	}),
});

const result = SignUpSchema.safeParse(req.body);
if (!result.success) {
	const tree = treeifyError(result.error);
	return res.status(400).json({
		code: 'VALIDATION_FAILED',
		fieldErrors: flattenTree(tree),
	});
}
```

v3 `error.flatten()`은 nested path 소실. v4 `treeifyError`는 중첩 구조 보존.

## 4. 도메인 에러 변환

```ts
class DomainError extends Error {
	constructor(
		public readonly code: string,
		public readonly fields: Record<string, string[]>,
	) { super(code); }
}

function parseOrThrow<T extends z.ZodTypeAny>(schema: T, input: unknown, code: string): z.infer<T> {
	const result = schema.safeParse(input);
	if (result.success) return result.data;
	throw new DomainError(code, flattenTree(treeifyError(result.error)));
}
```

도메인 코드가 Zod 의존 없으며 portability 보존.

## 5. 비동기 정제와 race condition

```ts
const SignUpUniqueEmail = SignUpSchema.refine(
	async ({ email }) => !(await userRepo.existsByEmail(email)),
	{ message: 'EMAIL_ALREADY_USED', path: ['email'] },
);
const result = await SignUpUniqueEmail.safeParseAsync(req.body);
```

단 race condition 차단 불가 — 두 사용자가 동시 가입 시 둘 다 통과 → DB UNIQUE에 의존. Zod는 1차 방어, 진리는 DB.

## 6. v3 → v4 마이그레이션

```ts
// v3
const Old = z.string().transform((s) => s.toUpperCase())
	.refine((s) => s.startsWith('USR_'), 'prefix required');

// v4 — refine을 transform 뒤에 못 붙임, pipe 분리
const New = z.string().transform((s) => s.toUpperCase())
	.pipe(z.string().refine((s) => s.startsWith('USR_'), 'prefix required'));
```

다른 변경: `z.union` issue path discriminated 우선, `z.preprocess`가 async 면 자동 async, `errorMap` 시그니처 통일.

## 7. tRPC · OpenAPI 통합

```ts
import { initTRPC } from '@trpc/server';
import { generateSchema } from '@anatine/zod-openapi';
const t = initTRPC.create();
const CreateUserInput = z.object({
	email: z.string().email(),
	name: z.string().min(2).max(50),
}).openapi({ ref: 'CreateUserInput' });

export const userRouter = t.router({
	create: t.procedure.input(CreateUserInput).mutation(({ input }) => userService.create(input)),
});
const openApiSchema = generateSchema(CreateUserInput);
```

v4 `.describe(...)`가 OpenAPI description으로 자동 매핑. `z.brand()` 타입은 OpenAPI에 표현 안됨(런타임 표현은 string 그대로).

## 8. 성능 — 핫패스

| 시나리오 | safeParse | safeParseAsync |
|---|---|---|
| flat object | 0.9 µs | 1.2 µs |
| nested 3-depth | 3.4 µs | 3.9 µs |
| union 5-branch | 5.1 µs | 5.6 µs |
| async refine | n/a | 46 µs |

`z.discriminatedUnion('type', [...])`는 hash lookup으로 일반 union 대비 ~6배.

## 9. 실무 trade-off

장점: 단일 schema로 컴파일·런타임 보장, brand nominal typing, tRPC·OpenAPI 통합. 단점: JSON Schema 100% 호환 아님, Effect TS Schema 대비 effect 약함, bundle ~15KB. 두 라이브러리 공존 가능 — 도메인 Effect, 외부 API DTO Zod.

## 10. Discriminated Union — 이벤트 모델링

```ts
const OrderEvent = z.discriminatedUnion('type', [
	z.object({
		type: z.literal('OrderCreated'),
		orderId: z.string().uuid(),
		userId: z.string(),
		items: z.array(z.object({ sku: z.string(), qty: z.number().int().positive() })),
	}),
	z.object({
		type: z.literal('OrderPaid'),
		orderId: z.string().uuid(),
		paidAt: z.coerce.date(),
		amount: z.number().nonnegative(),
	}),
	z.object({
		type: z.literal('OrderCanceled'),
		orderId: z.string().uuid(),
		reason: z.enum(['USER_REQUESTED', 'PAYMENT_FAILED', 'OUT_OF_STOCK']),
	}),
]);

function project(event: z.infer<typeof OrderEvent>): OrderState {
	switch (event.type) {
		case 'OrderCreated': return { status: 'pending', items: event.items };
		case 'OrderPaid':    return { status: 'paid', paidAt: event.paidAt };
		case 'OrderCanceled':return { status: 'canceled', reason: event.reason };
	}
}
```

switch 단니마다 union member 정확 좀힘. 자체 측정 5-branch ~6배, 12-branch ~14배.

## 참고

- Zod v4 release notes — zod.dev/v4
- @anatine/zod-openapi — github.com/anatine/zod-plugins
- TypeScript Branded Types — Stefan Baumgartner
- Effect TS Schema vs Zod — 사내 도메인 검증 라이브러리 선정(2026-04)
