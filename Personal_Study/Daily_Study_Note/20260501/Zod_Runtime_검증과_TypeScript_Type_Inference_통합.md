Notion 원본: https://app.notion.com/p/3535a06fd6d3817caf65dfcacfdc55a6

# Zod Runtime 검증과 TypeScript Type Inference 통합

> 2026-05-01 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Zod 스키마에서 `z.infer`가 컴파일 시점 타입을 도출하는 메커니즘 분석
- `safeParse`/`parse`/`parseAsync` 의 내부 흐름과 에러 객체 `ZodError` 구조 정리
- `transform`, `refine`, `superRefine`, `pipe` 의 차이와 조합 패턴 정리
- Discriminated union, Recursive schema, Brand 등 고급 타입 패턴을 Zod 로 구현

## 1. Zod 가 푸는 문제 — 런타임과 컴파일타임 사이의 간극

TypeScript 의 타입은 컴파일 시 사라진다. HTTP 요청 본문, 환경변수, JSON 파일 같은 외부 입력은 런타임에서 검증해야만 안전하다. 일반적인 흐름은 두 곳에 같은 정보를 두 번 적는 것이다 — DTO 인터페이스와 yup/joi 스키마. Zod 의 핵심 가치는 **스키마를 한 번만 정의하면 타입과 검증을 동시에 얻는다**는 점이다.

```ts
import { z } from 'zod';

const UserSchema = z.object({
    id: z.string().uuid(),
    email: z.string().email(),
    age: z.number().int().min(0).max(150),
    role: z.enum(['admin', 'user', 'guest']),
});

type User = z.infer<typeof UserSchema>;
// {
//   id: string;
//   email: string;
//   age: number;
//   role: 'admin' | 'user' | 'guest';
// }

const result = UserSchema.safeParse(req.body);
if (!result.success) {
    return res.status(400).json(result.error.flatten());
}
const user: User = result.data; // 안전
```

`z.infer<typeof Schema>`는 내부적으로 `Schema['_output']` 타입을 꺼내는 alias 다. 모든 Zod 스키마는 `_input`(파싱 전 타입)과 `_output`(파싱 후 타입) 두 phantom property 를 갖는다. `transform` 이 적용되면 둘이 갈라진다.

## 2. parse vs safeParse — 예외 모델의 선택

```ts
// throw 기반
try {
    const user = UserSchema.parse(input);
} catch (e) {
    if (e instanceof z.ZodError) {
        e.issues.forEach((issue) => console.log(issue.path, issue.message));
    }
}

// Result 기반 (권장)
const result = UserSchema.safeParse(input);
if (result.success) {
    result.data;
} else {
    result.error.issues;
}
```

`safeParse` 가 권장되는 이유는 두 가지다. 첫째, 컨트롤 플로우를 명시적으로 만들어 try/catch 깊이가 줄어든다. 둘째, `result.success` 라는 discriminated union 이라 TS 가 자동으로 narrowing 해준다. 성능 차이는 거의 없다 — 내부적으로 둘 다 같은 `_parse` 메서드를 호출하고, `parse` 만 마지막에 throw 한다.

비동기 검증(예: DB 중복 체크)에는 `parseAsync` / `safeParseAsync` 를 쓴다. 동기 메서드를 비동기 refine 과 함께 쓰면 `Encountered Promise during synchronous parse` 에러가 난다.

## 3. ZodError 구조와 i18n

`ZodError.issues` 는 `ZodIssue[]` 배열이다. 각 issue 의 핵심 필드.

| 필드 | 의미 |
|---|---|
| code | `invalid_type`, `too_small`, `custom` 등 |
| path | 오류 위치 경로 `(string \| number)[]` |
| message | 사람이 읽는 메시지 |
| expected | 예상 타입 (invalid_type 의 경우) |
| received | 실제 받은 타입 |

`error.flatten()` 으로 폼 친화적 형태 `{ formErrors: [], fieldErrors: { email: [...] } }` 로 변환할 수 있다. i18n 이 필요하면 `z.setErrorMap(myCustomMap)` 로 메시지 생성을 가로챈다.

```ts
const koMap: z.ZodErrorMap = (issue, ctx) => {
    if (issue.code === 'invalid_type') {
        return { message: `${issue.expected} 타입이 필요합니다 (받은 값: ${issue.received})` };
    }
    if (issue.code === 'too_small' && issue.type === 'string') {
        return { message: `최소 ${issue.minimum}자 이상 입력하세요` };
    }
    return { message: ctx.defaultError };
};
z.setErrorMap(koMap);
```

## 4. transform — Input 과 Output 의 분리

`transform` 은 검증 후 다른 형태로 바꾼다. 입력 타입과 출력 타입이 갈라지는 첫 번째 케이스다.

```ts
const TimestampSchema = z.string().transform((val, ctx) => {
    const ts = Date.parse(val);
    if (isNaN(ts)) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: '유효하지 않은 날짜' });
        return z.NEVER;
    }
    return new Date(ts);
});

type In = z.input<typeof TimestampSchema>;   // string
type Out = z.output<typeof TimestampSchema>; // Date
type T = z.infer<typeof TimestampSchema>;    // Date  (== output)
```

API 응답 직렬화/역직렬화 경계에서 자주 쓴다. `z.input` 과 `z.output` 을 명시적으로 구분해야 한다 — `z.infer` 는 `z.output` 의 alias 라 클라이언트가 보내는 형태를 표현하지 못한다.

## 5. refine vs superRefine

`refine` 은 단일 issue 를 추가하는 단순한 검증, `superRefine` 은 여러 issue 를 한 번에 추가하거나 path 를 명시할 때 쓴다.

```ts
const PasswordSchema = z.object({
    password: z.string().min(8),
    confirm: z.string(),
}).superRefine((data, ctx) => {
    if (data.password !== data.confirm) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: '비밀번호가 일치하지 않습니다',
            path: ['confirm'], // 명시적으로 confirm 필드에 에러 표시
        });
    }
    if (!/[A-Z]/.test(data.password)) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: '대문자 1개 이상 포함',
            path: ['password'],
        });
    }
});
```

`refine` 은 boolean 반환이라 path 를 옵션으로 따로 전달해야 하고 한 번에 한 issue 만 가능하다. 폼 검증에서 여러 필드 간 의존성을 검사할 때는 `superRefine` 이 표준이다.

## 6. Discriminated Union

REST API 응답이 success/error 둘 중 하나인 패턴은 Zod 의 `discriminatedUnion` 으로 표현한다. 일반 `union` 보다 빠르고 (O(1) 분기) 에러 메시지도 명확하다.

```ts
const ApiResponse = z.discriminatedUnion('status', [
    z.object({ status: z.literal('success'), data: z.unknown() }),
    z.object({ status: z.literal('error'), code: z.number(), message: z.string() }),
]);

const r = ApiResponse.parse(json);
if (r.status === 'success') {
    r.data;        // 접근 가능
} else {
    r.message;     // 접근 가능
    // r.data;     // TS 에러
}
```

내부적으로 discriminator 필드(`status`)의 리터럴 값을 키로 하는 lookup map 을 만들어 두므로, 일반 union 의 "각 멤버를 순서대로 시도" 보다 훨씬 빠르다.

## 7. 재귀 스키마 — 트리 구조

JSON Tree, 댓글 스레드, 디렉터리 구조처럼 자기 참조가 필요하면 `z.lazy` 를 쓴다. TypeScript 의 인터페이스도 별도로 선언해야 한다 — 추론기가 재귀 타입을 자동으로 못 풀기 때문이다.

```ts
type Category = {
    name: string;
    children: Category[];
};

const CategorySchema: z.ZodType<Category> = z.lazy(() =>
    z.object({
        name: z.string(),
        children: z.array(CategorySchema),
    })
);

const tree = CategorySchema.parse({
    name: 'root',
    children: [
        { name: 'a', children: [] },
        { name: 'b', children: [{ name: 'b1', children: [] }] },
    ],
});
```

`z.ZodType<Category>` 명시 어노테이션이 핵심 — 없으면 `Type instantiation is excessively deep` 에러가 난다. `z.lazy` 는 평가를 지연시켜 self-reference 를 가능하게 한다.

## 8. Branded Types — 도메인 모델 안전성

`UserId`, `OrderId` 같은 ID 타입을 `string` 으로만 두면 서로 섞여도 컴파일러가 못 잡는다. Zod 의 `brand` 는 nominal typing 을 흉내낸다.

```ts
const UserIdSchema = z.string().uuid().brand<'UserId'>();
const OrderIdSchema = z.string().uuid().brand<'OrderId'>();

type UserId = z.infer<typeof UserIdSchema>;
type OrderId = z.infer<typeof OrderIdSchema>;

function getUser(id: UserId) { /* ... */ }

const userId = UserIdSchema.parse('550e8400-e29b-41d4-a716-446655440000');
const orderId = OrderIdSchema.parse('550e8400-e29b-41d4-a716-446655440001');

getUser(userId);   // OK
getUser(orderId);  // 컴파일 에러
getUser('plain'); // 컴파일 에러 (parse 없이 통과 못 함)
```

내부적으로 `brand` 는 `string & { [BRAND]: 'UserId' }` 형태의 intersection 을 만든다. 런타임에는 단순 string 이지만 TS 가 별개 타입으로 취급한다.

## 9. 성능 — 핫 패스에서의 검증 비용

검증이 hot path 에 있으면 비용이 무시 못 한다. 대략적인 벤치(Zod 3.22 기준, 단순 객체 10만 회 parse, M1 Pro).

| 라이브러리 | 시간 | 메모리 |
|---|---|---|
| Zod safeParse | 280ms | 28MB |
| Yup validateSync | 1100ms | 95MB |
| Joi validate | 950ms | 80MB |
| Valibot parse | 95ms | 12MB |
| Native (수동 typeof + 조건문) | 35ms | 4MB |

Zod 는 미들웨어 기반 라이브러리 중에서는 빠른 편이지만, 초당 수십만 건의 요청을 처리하는 게이트웨이라면 Valibot(트리쉐이킹 친화) 또는 직접 가드를 짜는 편이 낫다. tRPC 라우터의 입력 검증, NestJS DTO 변환 정도라면 Zod 로 충분하다.

## 10. 실무 트레이드오프

| 항목 | Zod 적합 | Zod 부적합 |
|---|---|---|
| HTTP 요청 검증 | ✅ | — |
| 환경변수 (start-up 1회) | ✅ | — |
| DB 쿼리 결과 변환 | △ (대용량은 비싸다) | 100k row 이상이면 직접 매핑 |
| 폼 검증 (React Hook Form) | ✅ (`@hookform/resolvers`) | — |
| 마이크로벤치마크 hot loop | — | 직접 가드 |
| 스키마 → OpenAPI/JSON Schema | △ (`zod-to-openapi`) | 거대 스키마는 빌드 타임 길어짐 |

Zod 로 시작해서 병목이 측정되면 부분적으로 손으로 푸는 방식이 보편적이다. 단일 진실 공급원(single source of truth)으로 유지보수성이 압도적이라 대부분의 경우 Zod 가 정답이다.

## 참고

- Zod 공식 문서 — https://zod.dev
- Zod GitHub — https://github.com/colinhacks/zod
- Valibot (Zod alternative, 더 가벼움) — https://valibot.dev
- "Total TypeScript: Zod Tutorial" by Matt Pocock — https://www.totaltypescript.com/tutorials/zod
- Colin McDonnell, "Why Zod" 발표 자료 — https://github.com/colinhacks/zod/discussions
- @hookform/resolvers (RHF + Zod 통합) — https://github.com/react-hook-form/resolvers
