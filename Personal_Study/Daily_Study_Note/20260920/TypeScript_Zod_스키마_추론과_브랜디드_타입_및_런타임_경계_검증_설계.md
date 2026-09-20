Notion 원본: https://www.notion.so/3e15a06fd6d38104b8dafe8078abd3f5

# TypeScript Zod 스키마 추론과 브랜디드 타입 및 런타임 경계 검증 설계

> 2026-09-20 신규 주제 · 확장 대상: REST_API, Validator

## 학습 목표

- `z.input` / `z.output` / `z.infer` 가 갈라지는 지점을 `transform` · `default` · `coerce` 로 구분한다
- `.brand()` 로 만든 명목 타입을 파싱 게이트에 묶어 미검증 값의 내부 유입을 차단한다
- `ZodError.issues` 를 RFC 9457 Problem Details 로 변환하는 어댑터를 작성한다
- Zod 4 · Zod Mini · Valibot 의 번들·`tsc` 비용을 비교해 채택 기준을 정한다

## 1. 타입 소거와 경계: Parse, don't validate

TypeScript 의 타입은 `tsc` 를 통과하는 순간 사라진다. `JSON.parse()` 의 반환 타입이 `any` 인 것은 버그가 아니라 정직한 선언이다. Jackson 이 역직렬화 시점에 필드 타입을 확인해 주는 Spring 과 달리 TS 는 경계에서 아무 일도 하지 않으므로, `await req.json() as CreateOrder` 는 검증이 아니라 **거짓말**이다.

Alexis King 의 "Parse, don't validate" 가 설계적 해법이다. 검증(validate)은 `boolean` 을 돌려주고 정보를 버리지만, 파싱(parse)은 `unknown` 을 더 좁은 타입으로 바꾸며 그 사실을 반환 타입에 남긴다. Zod 는 이 parse 를 스키마로 선언하고 거기서 정적 타입을 역으로 뽑는다. 타입을 먼저 쓰고 검증기를 붙이는 DTO + `@NotNull` 과 방향이 반대다. 단일 소스는 스키마, 타입은 파생물이다.

## 2. `z.input` / `z.output` / `z.infer` 가 갈라지는 지점

Zod 스키마는 사실상 `Input → Output` 함수다. 보통은 두 타입이 같지만, 네 연산이 둘을 어긋나게 만든다: `.default()`, `.transform()`, `z.coerce.*`, `z.pipe()` / `z.codec()`.

```ts
import * as z from "zod"; // zod@4.6.5

const CreatePost = z.object({
  title: z.string().min(1),
  tags: z.array(z.string()).default([]),
  publishedAt: z.coerce.date(),
  slug: z.string().transform((s) => s.toLowerCase()),
});

type In = z.input<typeof CreatePost>;
// { title: string; tags?: string[] | undefined; publishedAt: unknown; slug: string }

type Out = z.output<typeof CreatePost>;
// { title: string; tags: string[]; publishedAt: Date; slug: string }

type Same = z.infer<typeof CreatePost>; // z.output 과 동일한 별칭
```

세 가지를 짚는다. `.default([])` 는 입력에서 키를 선택적으로, 출력에서 필수로 만든다. `z.coerce.*` 의 입력 타입은 **기본이 `unknown`** 이다(`new Date(value)` 로 그대로 넘기기 때문) — 좁히려면 `z.coerce.number<number>()` 처럼 제네릭을 준다. `z.infer` 는 `z.output` 의 별칭이라, 폼 라이브러리에 "사용자가 입력할 값"의 타입으로 넘기면 변환이 끝난 타입을 요구하게 되어 에러가 난다. 폼 상태에는 `z.input`, 도메인 로직에는 `z.output` 이 규칙이다.

`zod@4.1` 부터는 `z.codec()` 이 이 비대칭을 양방향으로 정식화했다. `.decode()` 는 Input→Output, `.encode()` 는 Output→Input 이다.

```ts
const IsoDate = z.codec(z.iso.datetime(), z.date(), {
  decode: (s) => new Date(s),
  encode: (d) => d.toISOString(),
});

IsoDate.decode("2026-09-20T00:00:00Z"); // Date
IsoDate.encode(new Date());             // string
```

DTO ↔ 엔티티 매퍼를 두 벌 쓰던 자리를 스키마 하나가 대신한다.

## 3. 브랜디드 타입: 파싱을 통과해야만 얻는 타입

TypeScript 는 구조적 타입 시스템이라 `type UserId = string` 은 그냥 `string` 이고, `deleteUser(orderId)` 같은 인자 뒤바뀜을 못 잡는다. 명목 타입을 흉내 내려면 교차 타입에 실재하지 않는 브랜드 필드를 얹는다.

```ts
declare const brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [brand]: B };
type UserId = Brand<string, "UserId">;

declare function deleteUser(id: UserId): void;
const raw = "u_1";
// deleteUser(raw);           // ❌ string 은 UserId 에 대입 불가
// deleteUser(raw as UserId); // ⚠️ 컴파일은 되지만 검증은 없다
```

`as` 로 브랜드를 붙일 수 있다는 점이 이 패턴의 구멍이다. `.brand()` 는 그 구멍을 **파싱 게이트**로 막는다.

```ts
const UserId = z.string().regex(/^u_[0-9a-f]{12}$/).brand<"UserId">();
type UserId = z.infer<typeof UserId>; // string & z.$brand<"UserId">

const id = UserId.parse("u_0a1b2c3d4e5f"); // ✅ 통과해야만 얻는다
```

세 가지가 중요하다. `.brand()` 는 **런타임 동작을 바꾸지 않는다** — `.parse()` 는 원래 문자열을 그대로 돌려주고, 브랜드는 순수 정적 구성물이라 번들에 코드를 추가하지 않는다. 기본값은 **출력 타입만** 브랜딩되며 방향은 두 번째 제네릭으로 지정한다(`.brand<"UserId", "in">()`, `"inout"`). 그리고 `as UserId` 를 팀 규칙으로 금지해야 실효가 있다.

효과는 "미검증 문자열"이라는 상태를 타입 시스템에서 표현 불가능하게 만드는 것이다. 리포지토리를 `findById(id: UserId)` 로 바꾸면 쿼리 파라미터에서 꺼낸 `string` 은 DB 계층에 닿을 수 없다.

## 4. 경계 한 곳에서만 파싱하기 — Spring `@Valid` 와의 대조

규칙은 단순하다. **외부 입력은 진입점에서 한 번 파싱하고 그 안쪽은 신뢰한다.** 외부 입력에는 HTTP body·query, 환경변수, 서드파티 응답, 큐 페이로드, DB 의 JSON 컬럼이 포함된다.

```ts
const Env = z.object({
  NODE_ENV: z.enum(["development", "production", "test"]),
  PORT: z.coerce.number<string>().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.url(),
  FEATURE_X: z.stringbool().default(false), // "yes"/"1"/"on" → true
});

export const env = Env.parse(process.env); // 부팅 시 1회, 실패하면 서버가 안 뜬다
```

`@Valid @RequestBody CreateOrderRequest` 와 핸들러 첫 줄의 `CreateOrder.parse(body)` 는 목적이 같다 — 경계에서 한 번 막고 서비스 계층은 유효한 객체만 본다. 다른 점이 설계를 가른다. Spring 은 **타입이 먼저**고 제약(`@NotBlank`, `@Size`)이 애너테이션으로 얹히며, 위반은 `MethodArgumentNotValidException` 으로 올라와 `@RestControllerAdvice` 가 처리한다. Zod 는 **스키마가 먼저**고 타입이 파생되며, 예외는 프레임워크가 아니라 내 코드가 잡는다. Jackson 이 떠맡던 타입 불일치 검출까지 Zod 몫이라는 뜻이기도 하다. 반대 방향의 이득도 있다. `.transform()` 으로 정규화를 같은 선언에서 끝내 DTO → 도메인 매퍼 층이 줄고, 스키마가 값이라서 `z.toJSONSchema()` 로 OpenAPI 문서나 테스트 픽스처에 재사용된다.

## 5. `safeParse` vs `parse`, 그리고 RFC 9457 응답 계약

`parse()` 는 실패 시 `ZodError` 를 던지고 `safeParse()` 는 `{ success: true, data }` 또는 `{ success: false, error }` 를 돌려준다. **실패가 예상 시나리오면 `safeParse`, 프로그래밍 오류나 부팅 실패면 `parse`.** 사용자 입력은 전자, 환경변수와 내부 불변식은 후자다.

`ZodError.issues` 는 `code`, `path: PropertyKey[]`, `message` 를 공통으로 갖고 코드별 추가 필드(`expected`, `minimum`, `keys` 등)를 붙인다. 이 배열을 그대로 노출하면 Zod 의 내부 구조가 API 계약이 된다. RFC 9457(Problem Details, RFC 7807 대체)로 번역해 계약을 고정한다.

```ts
type Problem = {
  type: string; title: string; status: number; instance?: string;
  errors: { pointer: string; code: string; message: string }[];
};

// JSON Pointer(RFC 6901): ["items", 0, "qty"] → "/items/0/qty"
const toPointer = (p: PropertyKey[]) =>
  "/" + p.map((s) => String(s).replace(/~/g, "~0").replace(/\//g, "~1")).join("/");

export function toProblem(err: z.ZodError, instance: string): Problem {
  return {
    type: "https://example.com/probs/validation-error",
    title: "Request validation failed",
    status: 422,
    instance,
    errors: err.issues.map((i) => ({
      pointer: toPointer(i.path), code: i.code, message: i.message,
    })),
  };
}
```

응답은 `Content-Type: application/problem+json` 으로 내보낸다. 로그용으로는 `z.prettifyError(err)` 가 이슈 목록을 여러 줄 문자열로 정리해 준다. Zod 4 는 에러 커스터마이징도 통합해, Zod 3 의 `message` · `invalid_type_error` · `required_error` · `errorMap` 네 갈래가 단일 `error` 파라미터로 합쳐졌다(`message` 는 deprecated 유지). 이제 `z.string({ error: (issue) => issue.input === undefined ? "필수 항목입니다" : "문자열이어야 합니다" })` 한 갈래로 쓴다.

## 6. 재귀 스키마와 추론이 깨지는 지점

재귀 타입은 Zod 가 오래 고전한 영역이다. `z.lazy()` 로 순환 참조를 만들면 TS 가 "`const` 가 자신의 타입 애너테이션에 간접적으로 참조된다"며 추론을 포기한다. Zod 3 의 정석은 타입을 손으로 쓰고 `z.ZodType<T>` 로 캐스팅하는 것이었는데, 그 순간 스키마와 타입이 따로 놀아 어긋나도 컴파일러가 못 잡는다.

Zod 4 는 **getter 문법**으로 이를 해결했다. getter 는 평가가 지연되므로 순환이 성립하고, 캐스팅 없이 추론이 끝까지 간다.

```ts
const Category = z.object({
  name: z.string(),
  get subcategories() { return z.array(Category); },
});
type Category = z.infer<typeof Category>;
// { name: string; subcategories: Category[] }
```

상호 재귀도 같은 방식이고, 결과가 평범한 `ZodObject` 라서 `.pick()` · `.partial()` · `.extend()` 가 그대로 쓰인다. 여전히 `z.lazy()` 가 필요한 경우는 유니온을 포함한 재귀(임의 JSON 값)이며 — 내장 `z.json()` 자체가 `z.lazy()` 구현이다 — **명시적 타입 애너테이션이 불가피**하다.

```ts
type Json = string | number | boolean | null | Json[] | { [k: string]: Json };
const Json: z.ZodType<Json> = z.lazy(() =>
  z.union([z.string(), z.number(), z.boolean(), z.null(), z.array(Json), z.record(z.string(), Json)]),
);
```

재귀 스키마는 깊이 제한이 없어 깊게 중첩된 JSON 이 스택을 태울 수 있다. body 크기 제한이 검증보다 앞선다.

## 7. Zod 4 에서 실제로 달라진 것과 Standard Schema

Zod 4 는 stable 이고 npm `latest` 는 **4.6.5**(2026-09-13 기준)다. 공식 릴리스 노트에 근거가 있는 항목만 적는다.

| 항목 | Zod 3 | Zod 4 |
|---|---|---|
| `tsc` 타입 인스턴스화(object + extend) | 25,000 초과 | 약 175 |
| `.extend()`/`.omit()` 체인 컴파일 | 약 4,000ms | 약 400ms |
| core 번들(gzip, `z.boolean()`) | 12.47kB | 5.36kB |
| `z.string().parse` | 기준 | 약 14.7배 |
| `z.object().safeParse`(Moltar) | 기준 | 약 6.5배 |

재작성의 핵심은 `ZodObject` 제네릭 단순화로 "인스턴스화 폭발"을 없앤 것이다. 스키마가 수백 개인 모노레포에서는 에디터 반응 속도로 체감된다. API 도 바뀌었다. 객체 정책은 `z.object()`(초과 키 제거)·`z.strictObject()`(거부)·`z.looseObject()`(통과)로 최상위 함수화됐고, `z.email()` 같은 문자열 포맷도 최상위로 올라오며 `z.string().email()` 류는 deprecated 됐다. 베타 때 논의되던 `z.interface` 는 **현재 공식 문서에 없으므로 쓰지 말 것**. `@zod/mini` 는 4.5 부터 `zod` 와 버전을 맞춘 독립 패키지로 배포되며, 체이닝 대신 `z.optional(z.string())` 같은 함수형 API 로 트리셰이킹을 가능하게 한다(같은 예제 gzip **1.88kB**, Zod 3 대비 85% 감소).

성능 도구도 둘 늘었다. `z.compile()` 은 스키마를 루프 없는 평탄한 검증기로 AOT 컴파일하고(20키 객체 301ns → 38ns, 7.8배), `.validate()` 는 `ZodError` 없이 boolean 만 돌려주는 타입 가드로 컴파일된 스키마에서 `.safeParse().success` 대비 최대 34.9배 빠르다 — 에러 메시지가 필요 없는 경로 전용이다.

**Standard Schema** 는 Zod·Valibot·ArkType 저자들이 함께 만든 스펙으로, 스키마가 `~standard` 프로퍼티 하나를 노출하기로 한 최소 합의다.

```ts
interface StandardSchemaV1<Input = unknown, Output = Input> {
  readonly "~standard": {
    readonly version: 1;
    readonly vendor: string; // "zod" | "valibot" | "arktype" | ...
    readonly types?: { readonly input: Input; readonly output: Output };
    readonly validate: (v: unknown) => Result<Output> | Promise<Result<Output>>;
  };
}
```

덕분에 tRPC·TanStack Form 은 라이브러리별 어댑터 없이 `StandardSchemaV1` 만 받으면 된다. 내 미들웨어도 같다.

```ts
import type { StandardSchemaV1 as SS } from "@standard-schema/spec";

export async function parseBody<S extends SS>(schema: S, body: unknown): Promise<SS.InferOutput<S>> {
  const r = await schema["~standard"].validate(body);
  if (r.issues) throw new BadRequest(r.issues);
  return r.value;
}
```

Valibot 으로 갈아타도 이 미들웨어는 그대로다. 다만 스펙이 보장하는 것은 `validate` 와 이슈의 `message` · `path` 뿐이라, `issue.code` 나 `z.prettifyError` 에 기대는 순간 교체 가능성은 사라진다. 경계 유틸은 스펙 수준으로, 에러 포매팅은 벤더 종속 계층으로 나누는 것이 타협점이다.

## 8. 비용 판단과 `as` · `satisfies` 의 역할 분담

번들 수치는 Valibot 문서가 로그인 폼 스키마 하나를 esbuild 로 측정해 공개한 값이니 자기 스키마로 재측정해야 한다.

| 라이브러리 | 로그인 폼 번들(esbuild) | API 스타일 | 특징 |
|---|---|---|---|
| Zod 4 | 약 17.7kB | 메서드 체이닝 | 생태계·타입 추론 품질 최상 |
| Zod Mini(`@zod/mini`) | 약 6.88kB | 함수형 | 트리셰이킹 가능, Zod 와 1:1 대응 |
| Valibot | 약 1.37kB | 함수형 파이프 | 모듈식, 클라이언트 번들에 유리 |
| TypeBox | (JSON Schema 산출) | 빌더 | AJV 로 JIT 검증, OpenAPI 와 맞물림 |
| ArkType | (타입 문법 파싱) | 문자열 DSL | TS 문법 그대로, 런타임 코드 생성 |

판단 기준은 실행 위치다. 서버 전용 코드에서 17.7kB 는 의미가 없으니 Zod 를 쓰고, 브라우저 번들에 들어가는 폼 검증이면 Zod Mini 나 Valibot 을 검토한다. OpenAPI 가 단일 소스면 TypeBox + AJV 가 낫다.

**런타임 검증을 하지 말아야 할 곳**도 분명하다. 이미 파싱된 내부 호출 사이, 방금 만든 객체, 핫 루프의 매 요소 — 재검증은 순수 손실이다. 대량 배열은 요소마다 `.parse()` 하는 대신 `z.array(Item)` 으로 감싼다.

세 도구의 역할도 구분한다. `as` 는 **컴파일러에게 검증 책임 면제를 요청**하는 것이라 경계에서는 쓰지 않는다. `satisfies` 는 값을 넓히지 않으면서 제약 충족만 확인하므로 **설정 객체·리터럴 맵의 형태 검증**에 쓴다.

```ts
const HANDLERS = {
  created: (o: Order) => {},
  shipped: (o: Order) => {},
} satisfies Record<OrderStatus, (o: Order) => void>; // 키 누락을 컴파일 타임에 잡는다
```

사용자 정의 타입 가드(`x is T`)는 **서술과 구현이 분리**돼 있어 검증 로직이 틀려도 컴파일러가 못 잡는다. Zod 스키마는 검증 코드와 타입이 같은 선언에서 나오므로 어긋날 수 없다. 정리하면 외부 데이터는 Zod 로 파싱하고, 결과에는 `.brand()` 로 표식을 남기며, 내부 상수는 `satisfies` 로 지키고, `as` 는 테스트 픽스처나 라이브러리 타입 정의의 구멍에만 쓴다.

## 참고

- Zod 4 Release notes — https://zod.dev/v4
- Zod Defining schemas (Branded types, Coercion) — https://zod.dev/api
- Zod Codecs — https://zod.dev/codecs
- Zod 4.6 announcement — https://zod.dev/blog/zod-4-6
- Standard Schema specification — https://standardschema.dev/
- RFC 9457: Problem Details for HTTP APIs — https://www.rfc-editor.org/rfc/rfc9457.html
