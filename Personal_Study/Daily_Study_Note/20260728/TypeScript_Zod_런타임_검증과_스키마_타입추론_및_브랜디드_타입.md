Notion 원본: https://app.notion.com/p/3ab5a06fd6d381b3ac77c3b12a80e2aa

# TypeScript Zod 런타임 검증과 스키마 타입추론 및 브랜디드 타입

> 2026-07-28 신규 주제 · 확장 대상: TypeScript(런타임 검증·타입 안전 경계)

## 학습 목표

- 컴파일 타임 타입과 런타임 값 사이의 간극을 스키마 검증이 메우는 이유를 구분한다.
- z.infer 가 스키마로부터 정적 타입을 유도해 단일 진실원천(SSOT)을 만드는 원리를 정리한다.
- parse·safeParse·transform·refine 의 입력/출력 타입 분리와 오류 처리 전략을 파악한다.
- 브랜디드 타입으로 검증된 값과 원시 값을 타입 수준에서 구별하는 기법을 직접 구현해 검증한다.

## 1. 타입은 런타임에 사라진다

TypeScript 의 타입은 컴파일 후 완전히 제거된다(type erasure). 외부에서 들어온 JSON 이 실제로 그 형태인지 타입 시스템은 보장하지 못한다.

```ts
async function loadUser(): Promise<User> {
  const res = await fetch("/api/user");
  return res.json() as User;   // 거짓말 — 실제 형태를 검증하지 않음
}
```

as User 는 믿어라라고 말할 뿐이다. 신뢰 경계(네트워크 응답, 폼 입력, 환경변수, 파일 파싱)에서는 타입 단언이 아니라 런타임 검증이 필요하다.

## 2. 스키마 = 단일 진실원천

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

UserSchema 를 고치면 User 타입이 자동으로 따라온다. 검증 규칙과 타입이 어긋나지 않는 SSOT 다.

## 3. parse vs safeParse

```ts
const user = UserSchema.parse(rawJson);   // 실패 시 throw
const result = UserSchema.safeParse(rawJson);
if (!result.success) {
  return badRequest(result.error.flatten());
}
const validUser = result.data;   // User 로 좁혀짐
```

safeParse 는 판별 유니온을 반환해 분기 후 data 타입이 자동으로 좁혀진다. error.flatten() 은 필드별 오류를 폼 UI 에 바로 쓰기 좋다.

## 4. 입력 타입 != 출력 타입 — transform 과 coerce

```ts
const Schema = z.object({
  count: z.string().transform((s) => parseInt(s, 10)),
  tags: z.string().transform((s) => s.split(",")),
});
type Out = z.infer<typeof Schema>;   // { count: number; tags: string[] }
type In  = z.input<typeof Schema>;   // { count: string; tags: string }
```

폼(모든 값이 문자열)에서 도메인 모델로 넘어가는 경계를 정확히 표현한다. coerce 는 JS 강제변환 규칙을 따르므로 엄격한 입력에는 transform 이 안전하다.

## 5. refine / superRefine — 커스텀 검증

```ts
const SignupSchema = z.object({
  password: z.string().min(8),
  confirm: z.string(),
}).refine((d) => d.password === d.confirm, {
  message: "비밀번호가 일치하지 않습니다",
  path: ["confirm"],
});
```

path 를 지정하면 오류가 특정 필드에 귀속되어 flatten().fieldErrors 에서 조회된다.

## 6. 브랜디드 타입 — 검증된 값을 타입으로 구별

string 이면 다 같은 string 이라 검증되지 않은 이메일이 검증된 자리에 들어가도 컴파일러가 못 막는다. 브랜디드 타입이 이 구멍을 막는다.

```ts
const Email = z.string().email().brand<"Email">();
type Email = z.infer<typeof Email>;   // string & z.BRAND<"Email">
function sendMail(to: Email, body: string) {}
const raw = "user@example.com";
// sendMail(raw, "hi");            // 컴파일 오류
const verified = Email.parse(raw);
sendMail(verified, "hi");          // 검증된 값만 통과
```

런타임 표현은 그냥 문자열이지만 타입 시스템은 Email 을 별개 타입으로 취급해 검증된 이메일만 받는다를 컴파일 타임에 강제한다.

## 7. 정리 — 경계에서만 검증하고 안쪽은 타입을 믿는다

신뢰 경계에서 한 번 검증(parse)하고 이후 내부는 정적 타입만 신뢰한다. 브랜디드 타입까지 얹으면 검증됨이 타입에 각인되어 검증을 건너뛴 값이 도메인 로직에 스며드는 것을 컴파일러가 막는다.

## 8. 실전 통합 — env·API·폼 세 경계

```ts
const Env = z.object({
  NODE_ENV: z.enum(["development", "production", "test"]),
  PORT: z.coerce.number().int().default(3000),
  DATABASE_URL: z.string().url(),
  JWT_SECRET: z.string().min(32),
});
export const env = Env.parse(process.env);   // 부팅 시 실패하면 즉시 크래시
```

tRPC 는 input 스키마가 곧 핸들러 인자 타입이 되고, React Hook Form 은 zodResolver 로 같은 스키마를 클라이언트 검증에 재사용한다. 스키마 하나가 서버·클라이언트 검증·타입 정의 세 역할을 겸한다.

## 9. 대안과 트레이드오프 — 번들 크기·성능

| 라이브러리 | 강점 | 대가 |
|---|---|---|
| Zod | 생태계·표현력·DX | 큰 번들, 런타임 파싱 비용 |
| Valibot | 작은 번들, 트리 셰이킹 | 함수 조합형 API |
| TypeBox | JSON Schema + AJV 최고속 | 스키마 문법 장황 |
| Effect Schema | 함수형·양방향 변환 | 러닝 커브 |

검증은 경계에서만 돌리므로 성능이 병목인 경우가 드물다. 대부분의 서비스에서 Zod 의 DX 이점이 파싱 비용을 압도한다.

## 참고

- Zod 공식 문서 — https://zod.dev
- TypeScript Handbook — Type Compatibility — https://www.typescriptlang.org/docs/handbook/type-compatibility.html
- Colin McDonnell, Zod — https://github.com/colinhacks/zod
- Parse, don't validate (Alexis King) — https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/
