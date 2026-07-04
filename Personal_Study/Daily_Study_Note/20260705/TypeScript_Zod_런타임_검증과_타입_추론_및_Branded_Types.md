Notion 원본: https://app.notion.com/p/3935a06fd6d381f4aa99ea09e401ea85

# TypeScript Zod 런타임 검증과 타입 추론 및 Branded Types

> 2026-07-05 신규 주제 · 확장 대상: REST_API

## 학습 목표

- 컴파일 타임 타입과 런타임 검증의 간극을 Zod 스키마로 메우는 원리를 설명한다
- z.infer 로 스키마에서 정적 타입을 단일 진실원(SSOT)으로 도출한다
- parse/safeParse, transform, refine 의 동작과 에러 처리 흐름을 파악한다
- Branded Types 로 검증된 값을 타입 수준에서 구분하는 기법을 구현한다

## 1. 왜 런타임 검증인가

TS 타입은 컴파일 후 지워진다(type erasure). fetch 응답·JSON.parse·폼 입력을 `as User` 로 단언하는 건 거짓말이다. Zod 는 스키마를 런타임 값으로 정의해 검증하고, 그 스키마에서 정적 타입을 추론해 검증과 타입을 한 곳에서 관리한다.

```typescript
import { z } from "zod";
const UserSchema = z.object({
  id: z.number().int().positive(),
  name: z.string().min(1),
  email: z.string().email(),
  role: z.enum(["admin", "user"]),
});
type User = z.infer<typeof UserSchema>;
```

## 2. parse vs safeParse

parse 는 실패 시 ZodError 를 던지고, safeParse 는 `{success,data}|{success,error}` 판별 유니온을 반환한다. API 경계는 safeParse 가 안전하다.

```typescript
const result = UserSchema.safeParse(await res.json());
if (!result.success) {
  return reply.status(400).send({ errors: result.error.flatten() });
}
const user = result.data;
```

## 3. transform — 입력≠출력 타입

```typescript
const DateSchema = z.string().transform((s) => new Date(s));
type In = z.input<typeof DateSchema>;    // string
type Out = z.infer<typeof DateSchema>;   // Date
const CoerceNum = z.coerce.number();     // "42" -> 42
```

검증이 아니라 변형이므로 유효성은 transform 전에 refine 으로 거른다.

## 4. refine / superRefine

```typescript
const SignupSchema = z.object({ password: z.string().min(8), confirm: z.string() })
  .refine((d) => d.password === d.confirm, { message: "불일치", path: ["confirm"] });
```

path 를 지정해야 프론트가 어느 필드에 에러를 표시할지 안다. superRefine 은 교차 필드·다중 이슈에 쓴다.

## 5. Branded Types

string 은 그냥 string 이라 "검증된 이메일"을 구분 못 한다. `.brand()` 로 명목적 태그를 붙인다.

```typescript
const Email = z.string().email().brand<"Email">();
type Email = z.infer<typeof Email>;   // string & BRAND<"Email">
function sendMail(to: Email) {}
const email = Email.parse("a@b.com");   // 검증 통과해야 Email 획득
sendMail(email);
```

순수 TS 로도 가능하다.

```typescript
declare const brand: unique symbol;
type Brand<T, B> = T & { readonly [brand]: B };
type UserId = Brand<number, "UserId">;
function toUserId(n: number): UserId {
  if (!Number.isInteger(n) || n <= 0) throw new Error("invalid");
  return n as UserId;
}
```

핵심: `as Brand` 단언은 검증 직후 한 곳에서만 한다.

## 6. 스키마 합성

```typescript
const BaseUser = z.object({ id: z.number(), name: z.string(), email: z.string().email() });
const CreateUserDto = BaseUser.omit({ id: true });
const UpdateUserDto = CreateUserDto.partial();
```

하나의 베이스에서 생성·수정·응답 DTO 를 파생하면 필드 변경 시 한 곳만 고쳐도 전 계층이 동기화된다.

## 7. 성능과 주의점

스키마는 모듈 상단에 한 번만 정의해 재사용한다. 대량은 `z.array(Item)` 한 번이 낫다. Zod 는 경계에서만 쓰고, 신뢰 경계에서 한 번 검증해 브랜드 타입으로 바꾼 뒤 내부에서는 그 타입을 믿는다.

| 기능 | 용도 | 주의 |
|---|---|---|
| parse | 실패 시 throw | 내부, try/catch |
| safeParse | 결과 객체 | API 경계 권장 |
| transform | 검증 후 변형 | input≠output |
| refine | 커스텀 검증 | path 필수 |
| brand | 명목 구분 | 검증 뒤 한 곳만 단언 |

## 참고

- Zod 공식 문서 (zod.dev) — Schemas, Branded types, Error handling
- TypeScript Handbook — Type erasure, structural typing
- "Parse, don't validate" — Alexis King
