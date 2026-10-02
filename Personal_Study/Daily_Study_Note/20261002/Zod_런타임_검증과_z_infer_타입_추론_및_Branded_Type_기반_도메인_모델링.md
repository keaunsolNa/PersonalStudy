Notion 원본: https://www.notion.so/3ed5a06fd6d381cfb42dcc61cf52f322

# Zod 런타임 검증과 z.infer 타입 추론 및 Branded Type 기반 도메인 모델링

> 2026-10-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Zod 스키마 하나에서 런타임 검증과 정적 타입을 함께 도출한다
- z.input, z.output, z.infer의 차이를 transform, default, coerce 예제로 구분한다
- Branded Type으로 같은 원시 타입의 도메인 값을 컴파일 시점에 분리한다
- Express, tRPC, NestJS 경계에서 parse와 safeParse를 상황에 맞게 선택한다

## 1. 스키마가 단일 진실 공급원이 되는 이유

TypeScript의 타입은 컴파일이 끝나면 사라진다. 그래서 `JSON.parse`가 돌려준 값이나 HTTP 요청 본문처럼 프로세스 바깥에서 들어온 데이터에는 타입 선언이 아무 보증도 주지 못한다. `as User` 단언은 컴파일러만 조용하게 할 뿐 값을 확인하지 않고, interface와 검증 함수를 따로 쓰면 둘이 어긋나는 순간 타입이 거짓말을 한다.

Zod는 스키마 값 하나로 이 문제를 푼다. 스키마는 런타임 객체이므로 `parse`로 검증하고, `z.infer`는 같은 스키마에서 정적 타입을 뽑아낸다. 검증 규칙을 먼저 쓰고 타입이 따라오는 방향이다.

```ts
import { z } from "zod";

export const SignupSchema = z.object({
  email: z.email(),
  age: z.number().int().min(0),
});

export type Signup = z.infer<typeof SignupSchema>;

const raw: unknown = JSON.parse('{"email":"a@b.dev","age":31}');
const signup: Signup = SignupSchema.parse(raw);
console.log(signup);
```

`raw`는 `unknown`이고 `parse`를 통과한 뒤에야 `Signup`이 된다. 검증을 통과한 값만 타입을 얻는다는 점이 타입 단언과 다르다. 대신 번들 크기와 CPU 비용이 늘고, 조건부 타입 같은 복잡한 타입 연산은 스키마로 표현하기 어렵다. 기본 `z.object`는 모르는 키를 조용히 제거하며, 거부하려면 `z.strictObject`를 쓴다.

## 2. z.input, z.output, z.infer와 transform, default, coerce

스키마는 입출력 모양이 다를 수 있다. Zod는 `z.input<typeof S>`(parse에 넣을 값)와 `z.output<typeof S>`(parse가 돌려주는 값)를 구분하고, `z.infer`는 `z.output`의 별칭이다.

```ts
import { z } from "zod";

const Query = z.object({
  page: z.coerce.number().int().min(1).default(1),
  tags: z.string().transform((s) => s.split(",").filter(Boolean)),
  since: z.string().datetime().transform((s) => new Date(s)).optional(),
});

type QIn = z.input<typeof Query>;
type QOut = z.output<typeof Query>;

const inp: QIn = { tags: "a,b" };            // page 생략 가능
const out: QOut = Query.parse({ page: "3", tags: "a,b", since: "2026-10-02T00:00:00Z" });
console.log(inp, out.page + 1, out.since instanceof Date); // { tags: 'a,b' } 4 true
console.log(Query.parse({ tags: "x" }));     // { page: 1, tags: [ 'x' ] }
```

출력의 `page`는 필수 `number`이고, 입력에서는 default 때문에 생략할 수 있다. `tags`는 입력이 `string`, 출력이 `string[]`이다. 핸들러 안에서는 출력 타입을, 클라이언트 요청 타입에는 입력 타입을 쓴다.

`z.coerce.number()`는 내부적으로 `Number(value)`를 쓰므로 빈 문자열과 `null`이 모두 0이 된다(실행 확인). 빈 값을 거르려면 `regex`와 `transform(Number)`를 명시한다. 버전별 차이는 `zod@3.25.76`과 `zod@4.6.5`를 각각 설치해 확인했다.

| 항목 | Zod 3 (3.25.76) | Zod 4 (4.6.5) |
| --- | --- | --- |
| `z.coerce.number()`의 input 타입 | `number` | `unknown` |
| `.default(v)` 뒤에 transform이 있을 때 | 기본값도 transform을 통과 | 기본값이 출력 타입이면 transform을 건너뜀 |

4.x에서는 기본값이 변환을 거치지 않고 그대로 반환되며(`z.number().default(5).transform(String)` 실행 확인), 3.x 방식이 필요하면 `.prefault()`를 쓴다.

## 3. refine, superRefine, pipe

필드 간 관계나 비즈니스 규칙은 `refine`과 `superRefine`으로 표현한다. `refine`은 불리언 술어이고, `superRefine`은 컨텍스트로 이슈를 여러 개, 다른 경로에 직접 추가할 수 있다. `pipe`는 앞 스키마의 출력을 뒤 스키마의 입력으로 넘겨 단계를 나눈다.

```ts
import { z } from "zod";

const PasswordForm = z
  .object({ password: z.string().min(8), confirm: z.string() })
  .refine((v) => v.password === v.confirm, {
    path: ["confirm"],
    error: "비밀번호가 일치하지 않습니다",
  });

const Range = z
  .object({ from: z.number(), to: z.number() })
  .superRefine((v, ctx) => {
    if (v.from > v.to) {
      ctx.addIssue({ code: "custom", path: ["to"], message: "to 는 from 이상이어야 합니다" });
    }
  });

const PortFromEnv = z
  .string()
  .regex(/^\d+$/)
  .transform(Number)
  .pipe(z.number().int().min(1).max(65535));

console.log(PasswordForm.safeParse({ password: "12345678", confirm: "x" }).error?.issues[0]?.path); // [ 'confirm' ]
console.log(PortFromEnv.parse("8080"), PortFromEnv.safeParse("70000").success);                      // 8080 false
```

비동기 refine(예: DB 중복 확인)은 `parseAsync`나 `safeParseAsync`로만 실행되며, 동기 `parse`로 호출하면 `$ZodAsyncError`가 던져진다(실행 확인). I/O가 필요한 규칙은 스키마를 순수하게 유지하도록 서비스 계층에서 처리하는 편이 대개 낫다.

## 4. discriminatedUnion으로 상태 모델링

종류에 따라 필드가 달라지는 데이터는 판별자 필드를 가진 합집합으로 모델링한다. `z.union`은 옵션을 차례로 시도해 에러가 뒤섞이지만, `z.discriminatedUnion`은 판별자 값으로 옵션을 곧장 골라 에러가 읽기 쉽다.

```ts
import { z } from "zod";

const PaymentEvent = z.discriminatedUnion("type", [
  z.object({ type: z.literal("card"), last4: z.string().length(4), amount: z.number().positive() }),
  z.object({ type: z.literal("bank"), account: z.string().min(6), amount: z.number().positive() }),
  z.object({ type: z.literal("point"), points: z.number().int().positive() }),
]);
type PaymentEvent = z.infer<typeof PaymentEvent>;

function describe(e: PaymentEvent): string {
  switch (e.type) {
    case "card": return `card ****${e.last4} ${e.amount}`;
    case "bank": return `bank ${e.account} ${e.amount}`;
    case "point": return `point ${e.points}`;
    default: { const _x: never = e; return _x; }
  }
}
const r = PaymentEvent.safeParse({ type: "wire", amount: 1 });
console.log(r.success ? "ok" : r.error.issues[0]?.message);
// Invalid discriminator value. Expected 'card' | 'bank' | 'point'
```

`never` 대입은 새 종류를 추가하고 `describe`를 안 고치면 컴파일 에러가 나게 한다. 대신 판별자는 리터럴이어야 하고, 판별자가 없는 합집합은 `z.union`을 쓴다.

## 5. Branded Type: .brand()와 직접 만드는 unique symbol 브랜드

`type UserId = string`과 `type OrderId = string`은 구조적 타입 시스템에서 완전히 호환되어 인자 순서를 바꿔도 컴파일이 통과한다. Branded Type은 원시 타입에 컴파일 시점에만 존재하는 표식을 붙여 이를 끊는다. Zod는 `.brand<"이름">()`을 제공하고, 직접 만들 때는 `unique symbol` 키를 가진 교차 타입을 쓴다.

```ts
import { z } from "zod";

// 1) Zod 내장 brand
const UserId = z.uuid().brand<"UserId">();
const OrderId = z.uuid().brand<"OrderId">();
type UserId = z.infer<typeof UserId>;
type OrderId = z.infer<typeof OrderId>;

function findOrder(user: UserId, order: OrderId) { return `${user}:${order}`; }

const u = UserId.parse("3f2b8c1e-5a4d-4e0b-9a77-1c2d3e4f5a6b");
const o = OrderId.parse("0b9f1d52-7c3e-4a58-8e21-9f0a1b2c3d4e");
console.log(findOrder(u, o));
// @ts-expect-error 순서가 바뀌면 컴파일 에러
findOrder(o, u);

// 2) 직접 만든 branded type (unique symbol)
declare const brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [brand]: B };

type Won = Brand<number, "Won">;
const Won = (n: number): Won => {
  if (!Number.isInteger(n) || n < 0) throw new RangeError("Won must be a non-negative integer");
  return n as Won;
};
function addWon(a: Won, b: Won): Won { return (a + b) as Won; }
console.log(addWon(Won(1000), Won(500))); // 1500
// @ts-expect-error 일반 number 는 Won 이 아니다
addWon(Won(1), 5);
```

`declare const brand: unique symbol`은 값을 만들지 않고 타입 수준의 키만 선언하므로 런타임 속성이 없고(브랜드 문자열을 `JSON.stringify`해도 그냥 문자열) 비용이 0이다. 브랜드는 런타임 보증이 아니므로 붙이는 입구를 검증 지점(parse, 생성자 함수)으로 제한해야 하고, `as UserId`를 남발하면 장식이 된다.

Zod의 `.brand()`와 손으로 만든 브랜드는 키가 달라 호환되지 않으므로 한 방식으로 통일한다. 경계 데이터는 `.brand()`, 스키마 없는 도메인 값 객체는 직접 만든 쪽이 가볍다. 한계로, 산술 결과는 `number`로 돌아오므로 `addWon`처럼 재단언이 필요하고 이 지점이 안전 구멍이 된다.

## 6. parse와 safeParse의 에러 처리

`parse`는 실패 시 `ZodError`를 던지고 `safeParse`는 `{ success, data }` 또는 `{ success, error }`를 돌려준다. 부팅 시 환경 변수처럼 깨지면 프로그램 오류인 곳은 던지는 `parse`가, 사용자 입력처럼 정상적으로 일어나는 실패는 값으로 다루는 `safeParse`가 맞다.

```ts
import { z } from "zod";

const S = z.object({ name: z.string().min(2), tags: z.array(z.string()).max(2), nested: z.object({ n: z.number() }) });
const bad = { name: "a", tags: ["a", "b", "c"], nested: { n: "x" } };

try { S.parse(bad); } catch (e) { console.log(e instanceof z.ZodError); } // true

const r = S.safeParse(bad);
if (!r.success) {
  console.log(z.flattenError(r.error));
}
```

폼에는 `z.flattenError`, 중첩 구조에는 `z.treeifyError`가 맞다. 4.6.5 타입 선언에서 `error.format()`과 `error.flatten()`에는 `@deprecated` 표시가 있다. `issues` 원본을 그대로 내보내면 내부 스키마 구조가 API 계약이 되므로 응답 형식을 한 번 감싸고, 로그에는 입력 값 전체를 남기지 않는다.

## 7. 경계 검증: Express, tRPC, NestJS

원칙은 신뢰할 수 없는 데이터가 들어오는 경계(HTTP 요청, 큐 소비자, 환경 변수, 외부 API 응답)에서 한 번만 파싱하고 안쪽 코드는 파싱된 타입만 다루는 것이다. 브랜드 타입은 안쪽 함수 시그니처가 "검증된 값"을 요구하게 만든다.

Express에서는 검증 미들웨어 하나로 body, query, params를 같게 처리한다. `express@5.2.1`에서 실제 서버를 띄워 확인했다. `size=500`은 400, `page=2`만 보내면 `size`가 20으로 채워졌다.

```ts
import express, { type RequestHandler } from "express";
import { z } from "zod";

const ListQuery = z.object({
  page: z.coerce.number().int().min(1).default(1),
  size: z.coerce.number().int().min(1).max(100).default(20),
});

function validate<T extends z.ZodType>(source: "query" | "params" | "body", schema: T): RequestHandler {
  return (req, res, next) => {
    const r = schema.safeParse(req[source]);
    if (!r.success) {
      res.status(400).json({ error: "VALIDATION_FAILED", details: z.flattenError(r.error as z.ZodError) });
      return;
    }
    res.locals[source] = r.data; // 핸들러에서 z.output 타입으로 한 번 단언한다
    next();
  };
}

export const app = express();
app.get("/users", validate("query", ListQuery), (_req, res) => {
  res.json(res.locals.query as z.output<typeof ListQuery>);
});
```

약점은 `res.locals`에 타입이 없어 `as` 단언이 필요하다는 점이다. 핸들러 안에서 `safeParse`를 직접 호출하는 방식이 장황하지만 타입은 정확하다.

tRPC는 `.input(schema)`만 주면 핸들러의 `input`은 `z.output`, 클라이언트 인자는 `z.input` 타입이 된다. 실패는 `BAD_REQUEST`인 `TRPCError`이며 원인으로 `ZodError`가 남는다(`@trpc/server@11.19.0`, `createCallerFactory`로 확인).

```ts
import { initTRPC, TRPCError } from "@trpc/server";
import { z } from "zod";

const t = initTRPC.create();
const UserId = z.uuid().brand<"UserId">();

export const appRouter = t.router({
  userById: t.procedure
    .input(z.object({ id: UserId }))
    .query(({ input }) => ({ id: input.id, name: "홍길동" })),
});

t.createCallerFactory(appRouter)({}).userById({ id: "nope" as never }).catch((e) => {
  if (e instanceof TRPCError) console.log(e.code, e.cause?.constructor.name); // BAD_REQUEST ZodError
});
```

NestJS는 Pipe 하나로 Zod를 끼울 수 있고 `@Body(new ZodValidationPipe(Schema))`처럼 쓴다. 아래는 `@nestjs/common@12.1.2`에서 파이프 단위로만 확인했으며 컨트롤러와 HTTP 서버까지는 확인하지 않았다. Swagger가 DTO 클래스에 의존하면 문서 생성에 별도 어댑터가 필요할 수 있다.

```ts
import { BadRequestException, type ArgumentMetadata, type PipeTransform } from "@nestjs/common";
import { z } from "zod";

export class ZodValidationPipe<T extends z.ZodType> implements PipeTransform<unknown, z.output<T>> {
  constructor(private readonly schema: T) {}
  transform(value: unknown, _meta: ArgumentMetadata): z.output<T> {
    const r = this.schema.safeParse(value);
    if (!r.success) {
      throw new BadRequestException({ message: "VALIDATION_FAILED", issues: z.flattenError(r.error as z.ZodError) });
    }
    return r.data;
  }
}

const pipe = new ZodValidationPipe(z.object({ age: z.coerce.number().int() }));
console.log(pipe.transform({ age: "3" }, { type: "body" })); // { age: 3 }
```

## 8. 성능 고려사항

Zod 검증은 요청마다 객체 그래프를 순회하므로 핫 패스에서는 비용이 된다. 아래는 이 컨테이너에서 한 번 측정한 값이라 상대 감각으로만 읽는다. 중첩 객체를 포함한 1,000개 배열을 `zod@4.6.5`로 검증했을 때 `parse`와 `safeParse`는 반복당 약 0.25~0.29ms, 모든 요소가 실패하는 `safeParse`는 약 1.25ms였다. 같은 데이터의 `JSON.stringify` 후 `JSON.parse`는 약 1.0ms였다. 실패 경로는 이슈 객체를 만드느라 비싸므로 악의적 입력이 몰리면 병목이 될 수 있다.

실무 대응으로는 스키마를 모듈 최상단에서 한 번만 만들고, 같은 값을 계층마다 재검증하지 않고, 큰 배열은 `max`로 길이를 먼저 제한한다. 최적화는 측정부터 한다.

## 9. Zod 3과 4의 차이: 확인한 것과 확인하지 못한 것

npm 기준 `latest`는 4.6.5이고 3.x는 3.25.76이 마지막으로 보였으며, 각각 따로 설치해 확인했다. 확인한 차이는 문자열 포맷 검증이 `z.string().email()`에서 `z.email()`, `z.uuid()` 같은 최상위 함수로 옮겨 간 것, 에러 메시지를 `error` 옵션으로 지정하는 것(`message`도 여전히 동작), 에러 포맷팅이 `z.flattenError`, `z.treeifyError`, `z.prettifyError`로 제공되는 것, `z.toJSONSchema`가 draft 2020-12 JSON Schema를 만드는 것, `z.codec`으로 `z.decode`와 `z.encode` 양방향 변환을 정의할 수 있는 것이다.

확인하지 못한 부분은 단정하지 않는다. 3.x와 4.x의 성능 차이는 벤치마크하지 않았고, 각 기능이 어느 4.x 마이너에서 추가되었는지도 대조하지 않았다. 이 노트의 모든 코드는 위 버전에서 `tsc --noEmit --strict`와 `tsx`로 확인했으며, Zod 4 코드를 3.x에 붙이면 `z.email()` 등이 없어 컴파일되지 않는다.

## 참고

- Zod 공식 문서: https://zod.dev
- Zod 4 변경 이력과 마이그레이션: https://zod.dev/v4/changelog
- TypeScript Handbook, Type Compatibility: https://www.typescriptlang.org/docs/handbook/type-compatibility.html
- tRPC 입력 및 출력 검증: https://trpc.io/docs/server/validators
- NestJS Pipes: https://docs.nestjs.com/pipes
