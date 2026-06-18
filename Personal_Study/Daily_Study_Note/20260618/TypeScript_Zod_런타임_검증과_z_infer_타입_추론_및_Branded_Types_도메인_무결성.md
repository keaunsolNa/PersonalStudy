Notion 원본: https://www.notion.so/3835a06fd6d381f8ad93d79abf676cdc

# TypeScript Zod 런타임 검증과 z.infer 타입 추론 및 Branded Types 도메인 무결성

> 2026-06-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Zod 스키마에서 정적 타입을 `z.infer` 로 단일 진실 원천(SSOT)으로 도출한다
- `parse`/`safeParse`, transform, refine 의 동작과 `ZodError` 구조를 코드로 다룬다
- `input`/`output` 타입 분리가 필요한 transform 스키마를 정확히 추론한다
- Branded type 을 Zod `.brand()` 로 만들어 검증을 통과한 값만 도메인 타입에 들어가도록 강제한다

## 1. 왜 런타임 검증인가

TypeScript 타입은 컴파일 시점에 지워진다(type erasure). 외부 입력은 타입 단언을 붙여도 실제 형태를 보장하지 못한다. Zod 는 스키마를 런타임 값으로 가져 경계에서 검증하고 동시에 정적 타입을 추론한다.

```ts
import { z } from "zod";
const UserSchema = z.object({
  id: z.number().int().positive(),
  email: z.string().email(),
  role: z.enum(["admin", "user"]),
  createdAt: z.coerce.date(),
});
type User = z.infer<typeof UserSchema>;
```

## 2. parse vs safeParse 와 ZodError

```ts
const result = UserSchema.safeParse(req.body);
if (!result.success) return res.status(400).json({ issues: result.error.issues });
const user = result.data;
```

`ZodError.issues` 의 각 항목은 `{ code, path, message }`. `path` 가 배열로 위치를 알려준다.

## 3. refine 과 superRefine

```ts
const SignupSchema = z.object({ password: z.string().min(8), confirm: z.string() })
  .refine((d) => d.password === d.confirm, { message: "비밀번호 불일치", path: ["confirm"] });
```

`refine` 이 붙은 스키마는 `ZodEffects` 로 감싸져 `.pick()`·`.extend()` 를 직접 못 쓴다. base 단계에서 먼저 조작한다.

## 4. transform 과 input/output 타입 분리

```ts
const DateRange = z.object({ from: z.string(), to: z.string() })
  .transform((d) => ({ from: new Date(d.from), to: new Date(d.to) }));
type RangeIn = z.input<typeof DateRange>;
type RangeOut = z.infer<typeof DateRange>;
```

폼 `defaultValues` 에는 `z.input`, 검증 후 로직에는 `z.infer` 를 쓴다.

## 5. Branded Types 로 도메인 무결성

```ts
const Email = z.string().email().brand<"Email">();
type Email = z.infer<typeof Email>;
function sendMail(to: Email) {}
const safe = Email.parse("a@b.com");
sendMail(safe);
```

스키마를 통과한 값만 브랜드 타입을 획득해, 검증되지 않은 값이 도메인 함수에 들어오는 사고를 막는다.

## 6. 스키마 합성과 재사용

```ts
const Base = z.object({ id: z.number(), name: z.string() });
const CreateInput = Base.omit({ id: true });
const Shape = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("circle"), radius: z.number() }),
  z.object({ kind: z.literal("rect"), w: z.number(), h: z.number() }),
]);
```

## 7. 단위 테스트로 검증 규칙 고정

```ts
import { describe, it, expect } from "vitest";
describe("UserSchema", () => {
  it("createdAt 을 Date 로 변환", () => {
    const r = UserSchema.safeParse({ id:1, email:"a@b.com", role:"admin", createdAt:"2026-06-18" });
    expect(r.success).toBe(true);
  });
  it("brand 된 Email 검증", () => { expect(() => Email.parse("bad")).toThrow(); });
});
```

## 8. 실전: tRPC·react-hook-form·환경변수 검증

```ts
const Env = z.object({ DATABASE_URL: z.string().url(), PORT: z.coerce.number().int().default(3000) });
export const env = Env.parse(process.env);
```

react-hook-form 은 `zodResolver`, tRPC 는 `.input(schema)` 로 end-to-end 추론을 얻는다.

## 9. 커스텀 에러 맵과 국제화

```ts
const koErrorMap: z.ZodErrorMap = (issue, ctx) => {
  if (issue.code === z.ZodIssueCode.too_small) return { message: `최소 ${issue.minimum}` };
  return { message: ctx.defaultError };
};
z.setErrorMap(koErrorMap);
```

## 10. 성능·운영상 트레이드오프

검증은 런타임 작업이라 매우 큰 배열을 매 요청 검증하면 지연이 누적된다. 신뢰 경계에서만 검증하고 내부에서 재검증하지 않는다. "한 번 검증, 이후엔 타입 신뢰"가 핵심이며 branded type 이 그 경계를 컴파일러로 강제한다.

## 참고

- Zod 공식 문서 (https://zod.dev)
- Zod GitHub — Branded types (https://github.com/colinhacks/zod#brand)
- TypeScript Handbook — Type Compatibility (https://www.typescriptlang.org/docs/handbook/type-compatibility.html)
- "Parse, don't validate" — Alexis King (https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
