Notion 원본: https://www.notion.so/3775a06fd6d381249aebffa213fcaec9

# TypeScript Zod — 런타임 스키마 검증과 z.infer 타입 추론 내부

> 2026-06-06 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 컴파일 타임 타입과 런타임 검증의 경계, 그리고 그 경계에서 Zod의 역할을 구분한다
- z.infer가 스키마 객체로부터 정적 타입을 역도출하는 타입 레벨 메커니즘을 분석한다
- discriminated union·transform·refine 스키마가 입력/출력 타입을 어떻게 분리하는지 설명한다
- 경계(API 핸들러, 환경변수, 폼)에서 single source of truth로 Zod를 적용하는 패턴을 구현한다

## 1. 왜 런타임 검증이 필요한가

TypeScript의 타입은 컴파일 시점에만 존재하고 tsc가 JS로 변환하면 완전히 지워진다(type erasure). 따라서 JSON.parse(req.body) as User 같은 단언은 런타임에 아무것도 검사하지 않는다. 외부에서 들어온 데이터는 컴파일러가 보장할 수 없는 미지의 값이다. Zod는 스키마를 한 번 선언하면 런타임 검증 함수와 그 스키마에 대응하는 정적 타입을 동시에 제공한다.

## 2. 스키마는 값이자 타입의 생성기

```typescript
import { z } from "zod";
const UserSchema = z.object({
  id: z.string().uuid(),
  name: z.string().min(1),
  age: z.number().int().nonnegative(),
  role: z.enum(["admin", "user"]),
  tags: z.array(z.string()).default([]),
});
type User = z.infer<typeof UserSchema>;
const result = UserSchema.safeParse(JSON.parse(rawBody));
if (!result.success) throw new BadRequestError(result.error.issues);
const user: User = result.data;
```

type User와 UserSchema가 단일 출처에서 나온다. 스키마를 고치면 타입이 자동으로 따라온다.

## 3. z.infer의 내부

z.infer<T>는 마법이 아니라 평범한 conditional type이다. 모든 Zod 스키마는 내부적으로 입력/출력 타입을 제네릭 파라미터로 들고 있다.

```typescript
abstract class ZodType<Output = any, Input = Output> {
  readonly _output!: Output;  // phantom: 런타임엔 없고 타입만
  readonly _input!: Input;
  abstract parse(data: unknown): Output;
}
type infer<T extends ZodType> = T["_output"];
```

z.infer는 런타임에 실행되는 코드가 전혀 아니다. 컴파일러가 스키마 객체의 정적 형태를 따라가며 타입을 계산한다. .min(1)이나 .uuid() 같은 런타임 제약은 타입에 영향을 주지 않고(둘 다 string), 구조와 modifier만 타입에 반영된다.

## 4. 입력 타입 ≠ 출력 타입

```typescript
const FormSchema = z.object({
  age: z.string().transform((s) => parseInt(s, 10)),  // 입력 string → 출력 number
  active: z.boolean().default(true),                   // 입력 optional → 출력 required
  createdAt: z.coerce.date(),
});
type FormInput  = z.input<typeof FormSchema>;
type FormOutput = z.output<typeof FormSchema>; // = z.infer
```

| 구분 | z.input | z.output (= z.infer) |
|---|---|---|
| 시점 | parse 이전(raw) | parse 이후(검증/변형) |
| default 필드 | optional | required |
| transform 필드 | 변형 전 타입 | 변형 후 타입 |
| 용도 | 요청/폼 입력 | 도메인 로직, 응답 |

transform은 출력 타입을 바꾸고 default는 입력에서 optional이지만 출력에서 required다. 이 비대칭을 모르면 혼란이 생긴다.

## 5. Discriminated Union

```typescript
const Event = z.discriminatedUnion("type", [
  z.object({ type: z.literal("click"), x: z.number(), y: z.number() }),
  z.object({ type: z.literal("key"), key: z.string() }),
  z.object({ type: z.literal("scroll"), delta: z.number() }),
]);
type Event = z.infer<typeof Event>;
function handle(e: Event) {
  switch (e.type) {
    case "click": return e.x + e.y;
    case "key":   return e.key.length;
    case "scroll":return e.delta;
  }
}
```

판별 필드(type)의 literal로 TypeScript가 자동 narrowing을 해 각 case에서 해당 변형의 필드만 접근 가능하고, 일반 z.union보다 검증 성능도 좋다.

## 6. 실전 패턴 — 경계마다 single source of truth

```typescript
const Env = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]),
  PORT: z.coerce.number().int().positive().default(3000),
  DATABASE_URL: z.string().url(),
});
export const env = Env.parse(process.env); // 부팅 시 한 번, 실패하면 즉시 크래시
```

부팅 시점에 환경변수를 검증하면 운영 중 빈 환경변수로 터지는 문제를 시작 시점으로 앞당긴다(fail fast). API 핸들러에서는 safeParse로 검증 후 parsed.data를 안전한 타입으로 쓴다.

## 7. 스키마 합성과 재사용

```typescript
const BaseUser = z.object({
  id: z.string().uuid(), email: z.string().email(),
  password: z.string().min(8), createdAt: z.coerce.date(),
});
const CreateUserDto = BaseUser.omit({ id: true, createdAt: true });
const UpdateUserDto = CreateUserDto.partial();
const UserResponse = BaseUser.omit({ password: true });
const AdminUser = BaseUser.extend({ permissions: z.array(z.string()) });
```

BaseUser에 필드를 추가하면 파생 스키마 전체가 자동 반영돼 검증과 타입이 절대 어긋나지 않는다. refine은 런타임 검증만 추가하고 타입은 바꾸지 않아 타입으로 표현 못 하는 제약(필드 간 교차 검증)을 메우는 자리다. 프론트(react-hook-form + zod resolver)와 백엔드 핸들러가 같은 스키마를 공유하면 검증 규칙이 한 출처에서 나와 영원히 일치한다.

## 8. 비용과 주의점

Zod 검증은 런타임 연산이라 핫패스에서 매우 큰 객체를 매번 parse하면 비용이 든다. 경계에서 한 번만 검증하고 내부에선 타입을 신뢰하거나, 성능이 중요하면 TypeBox 같은 컴파일된 검증기를 고려한다. .parse(throw)와 .safeParse(결과 객체)를 상황에 맞게 선택한다. 모든 경계에 Zod를 single source of truth로 두면 컴파일 타임 타입과 런타임 실제 형태가 어긋날 여지를 구조적으로 없앤다.

## 참고

- Zod 공식 문서 — Schema methods, z.infer/z.input/z.output, discriminatedUnion
- TypeScript Handbook — Type Erasure, Conditional Types, Mapped Types
- Colin McDonnell, Zod 설계 노트
- "Parse, don't validate" (Alexis King)
- TypeBox 문서 — 컴파일된 검증기 성능 비교
