Notion 원본: https://www.notion.so/3a15a06fd6d3816989a6e823b49b75af

# TypeScript Zod 스키마 추론과 런타임 검증 경계 및 브랜디드 타입

> 2026-07-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 타입 소거(type erasure)로 신뢰 경계에서 런타임 검증이 필수가 되는 구조를 파악한다.
- `z.infer`, `z.input`, `z.output` 이 갈라지는 지점을 팬텀 필드 관점에서 분석한다.
- `parse`/`safeParse`, `transform`/`refine`/`pipe` 의 실행 순서를 설계에 반영한다.
- 브랜디드 타입으로 검증 통과 사실을 타입에 인코딩하는 경계 계층을 구현한다.

## 1. 타입은 지워진다 — 신뢰 경계라는 근본 문제

TypeScript 의 타입은 `tsc` 가 JavaScript 를 뱉는 순간 전부 사라진다. `interface User { id: string }` 는 컴파일 결과물에 한 바이트도 남지 않는다. 버그가 아니라 설계 목표로, "런타임 동작에 영향을 주는 문법을 추가하지 않는다"는 원칙 아래 런타임 자기 기술(self-description)을 포기하고 JS 생태계와의 호환성을 얻은 것이다.

Java 진영에서 온 사람에게는 낯선 감각이다. Java 도 제네릭은 소거되지만 클래스는 `.class` 파일에 메타데이터로 남고, `@Valid @RequestBody UserDto dto` 가 동작하는 건 프레임워크가 리플렉션으로 필드와 애너테이션을 훑기 때문이다. TypeScript 에는 그 대상이 없다. 그래서 다음 코드는 컴파일도 실행도 되지만 거짓말이다.

```ts
app.post('/users', async (req, res) => {
  const body = req.body as CreateUserBody; // 아무 검증도 일어나지 않음
  await userService.create(body.email, body.age);
});
```

`as` 는 컴파일러에게 "믿어달라"고 말할 뿐 런타임 코드를 생성하지 않는다. `{ email: 42, age: "hello" }` 여도 통과하고, 운이 나쁘면 그 값이 DB 까지 흘러들어가 며칠 뒤에 터진다. 신뢰 경계(trust boundary)란 "타입 시스템이 보증하지 않는 값이 들어오는 곳"이다.

| 경계 | 실제 런타임 타입 | 흔한 실수 |
| --- | --- | --- |
| HTTP 요청 바디/쿼리 | `any` (Express) 또는 `unknown` | `as Dto` 캐스팅 |
| `process.env` | `Record<string, string \| undefined>` | `process.env.PORT!` |
| `fetch().json()` / `JSON.parse()` | `any` | 제네릭 `get<T>()` 로 거짓 보증 |
| 메시지 큐 / DB raw query | `string \| null`, `any[]` | 스키마 변경 시 조용히 깨짐 |

`fetch(...).then(r => r.json())` 의 반환 타입이 `Promise<any>` 라는 점이 핵심이다. `any` 는 타입 검사를 무력화하며 전파되므로 외부 값 하나가 전체 타입 안전성 그래프에 구멍을 뚫는다. 제네릭으로 감싼 클라이언트(`api.get<UserDto>('/users/1')`)는 `any` 에 라벨만 붙여 놓고 보증을 착각하게 만들어 더 위험하다. 해법은 경계에 **실제로 실행되는 검증 코드**를 두는 것뿐이다.

## 2. 스키마가 곧 타입 — `z.infer` 의 동작 원리

Zod 는 순서를 뒤집는다. 타입을 쓰고 검증기를 따로 만드는 대신, 스키마에서 타입을 추출한다.

```ts
import { z } from 'zod';

const CreateUserSchema = z.object({
  email: z.string().email(),
  age: z.number().int().min(0).max(150),
  nickname: z.string().optional(),
});

type CreateUser = z.infer<typeof CreateUserSchema>;
// { email: string; age: number; nickname?: string | undefined }
```

`CreateUserSchema` 는 런타임에 실존하는 객체로, `_def` 트리를 들고 있고 `parse()` 가 그 트리를 순회한다. Zod v3 의 베이스 클래스는 대략 이렇다.

```ts
abstract class ZodType<Output = any, Def extends ZodTypeDef = ZodTypeDef, Input = Output> {
  readonly _output!: Output;  // 값이 대입되지 않는 팬텀 필드
  readonly _input!: Input;
  readonly _def!: Def;
  abstract _parse(input: ParseInput): ParseReturnType<Output>;
}
```

제네릭 파라미터가 3개다. `Output`(파싱 성공 시 나오는 타입), `Def`(런타임 정의), `Input`(파싱에 넣을 수 있는 타입). `_output`, `_input` 은 **팬텀 필드(phantom field)** 로, `!` 단언이 붙어 있고 어디서도 값이 대입되지 않으며 런타임에는 존재하지 않는다. 구조적 타이핑에서는 타입 파라미터가 멤버에 나타나지 않으면 서로 다른 인스턴스화가 호환돼 버리므로, `ZodType<string>` 과 `ZodType<number>` 를 구별하려면 `Output` 이 프로퍼티 타입으로 등장해야 한다. 덕분에 `z.infer` 는 인덱스 접근 타입 한 줄이 된다.

```ts
type infer<T extends ZodType<any, any, any>> = T['_output'];
type input<T extends ZodType<any, any, any>> = T['_input'];
```

마법은 `z.object({...})` 같은 팩토리가 인자로부터 `Output` 을 계산하는 데 있다. `ZodObject<Shape>` 는 각 필드의 `_output` 을 매핑하고 optional 키에 `?` 를 붙이는 매핑 타입을 조립한다. 즉 `z.object()` 호출 순간 값 레벨에서는 스키마 트리가, 타입 레벨에서는 재귀적 매핑 타입 계산이 동시에 일어난다.

## 3. `z.input` vs `z.output` — 두 타입이 갈라지는 지점

대부분의 스키마에서 둘은 같다. `Input = Output` 이 기본값이라 `z.string()` 은 넣는 것도 나오는 것도 string 이다. 갈라지는 경우는 셋이다.

```ts
// (1) transform: 파싱 성공 후 값을 변형
const S = z.string().transform((s) => s.length);
type In = z.input<typeof S>;   // string
type Out = z.output<typeof S>; // number

// (2) default: 입력에서는 생략 가능, 출력에는 반드시 존재
const Cfg = z.object({ retries: z.number().default(3), timeout: z.number() });
type CfgIn = z.input<typeof Cfg>;   // { retries?: number | undefined; timeout: number }
type CfgOut = z.output<typeof Cfg>; // { retries: number; timeout: number }

// (3) coerce: 강제 변환은 입력을 unknown 으로 연다
const Port = z.coerce.number().int().positive();
type PortIn = z.input<typeof Port>; // unknown
Port.parse('8080');                 // 8080 (number)
```

`z.coerce.number()` 는 `Number(input)` 을 먼저 돌리므로 `Number('')` 이 `0` 이 되는 등 JS 강제 변환 함정을 물려받는다. env 처럼 항상 문자열이 오는 곳에서는 `z.string().regex(/^\d+$/).transform(Number)` 가 의도를 더 정확히 드러낸다.

여기서 실무 규칙이 나온다. **`z.infer` 는 `z.output` 의 별칭이다.** 스키마에 `default`/`transform`/`coerce` 가 하나라도 있으면 `z.infer` 를 "이 API 가 받는 요청 타입"으로 쓰면 안 된다. 클라이언트 SDK 나 테스트 픽스처 인자에는 `z.input`, 서비스 계층으로 넘기는 값에는 `z.output` 이다.

```ts
function makeClientBad(cfg: z.infer<typeof Cfg>) {} // 잘못됨: 호출자가 retries 를 채워야 함

function makeClient(rawCfg: z.input<typeof Cfg>) {  // 올바름
  const cfg: z.output<typeof Cfg> = Cfg.parse(rawCfg);
}
```

Zod 는 두 타입을 문법적으로 구분해 "요청 DTO 와 도메인 입력은 다른 타입"임을 컴파일러가 강제하게 한다.

## 4. parse vs safeParse — 예외냐 결과 타입이냐

```ts
const user = CreateUserSchema.parse(req.body); // 실패 시 ZodError throw

const result = CreateUserSchema.safeParse(req.body);
if (!result.success) return res.status(400).json({ errors: toFieldErrors(result.error) });
const user2 = result.data; // 여기서 CreateUser 로 내로잉됨
```

`safeParse` 의 반환 타입은 discriminated union 이다.

```ts
type SafeParseReturnType<Input, Output> =
  | { success: true; data: Output }
  | { success: false; error: ZodError<Input> };
```

`success` 가 리터럴 타입 `true`/`false` 로 판별자 역할을 하므로 `if (!result.success)` 로 early return 하면 그 아래에서 `result.data` 접근이 열린다. `result.error` 는 성공 브랜치에 없어 접근하면 컴파일 에러다. Rust 의 `Result<T, E>` 와 같은 발상이다.

| 상황 | 권장 | 이유 |
| --- | --- | --- |
| HTTP 요청 검증 | `safeParse` | 400 응답으로 매핑해야 함 |
| env 부팅 검증 | `parse` | 실패 = 프로세스가 뜨면 안 됨 |
| 외부 API 응답 | `safeParse` | 재시도/폴백 분기 필요 |

비동기 refine 이 섞이면 `parseAsync`/`safeParseAsync` 를 써야 한다. 동기 `parse` 에 `.refine(async ...)` 스키마를 넣으면 Zod 가 런타임 에러를 던지며 컴파일 타임에 잡히지 않는다. 애초에 비동기 검증(DB 중복 체크 등)은 스키마가 아니라 서비스 계층에 두는 게 낫다. 또 `parse` 는 실패 시 스택 트레이스를 캡처하므로, 실패가 잦은 경로에서 반복하면 비용이 누적된다.

## 5. transform, refine, superRefine 의 실행 순서

규칙은 "**선언된 순서대로, 앞 단계가 성공해야 다음 단계가 실행된다**"이다.

```ts
const Slug = z
  .string()                                 // 1. 타입 검사
  .min(3)                                   // 2. 내장 체크
  .refine((s) => !s.startsWith('_'))        // 3. 커스텀 검증 (입력: string 원본)
  .transform((s) => s.toLowerCase().trim()) // 4. 변형
  .refine((s) => s.length <= 30);           // 5. transform 이후 값에 대한 검증
```

4번 이후의 `refine` 콜백이 받는 값은 **transform 의 출력**이고 타입도 그렇게 추론된다. "trim 후 길이"를 검사하려면 transform 뒤에, 원본 형식을 검사하려면 앞에 놓아야 한다. 앞 단계가 실패하면 뒤 단계는 실행되지 않으므로 transform 안에서 타입 가드를 다시 할 필요는 없다. 다만 `z.object()` 안의 **형제 필드들**은 독립적으로 검사되어 에러가 모두 수집된다. 한편 `refine` 은 boolean 술어라 에러가 하나만 나오지만, `superRefine` 은 `ctx` 로 여러 이슈를 추가할 수 있어 폼 검증에 유용하다.

```ts
const PasswordChange = z
  .object({ current: z.string(), next: z.string().min(8), confirm: z.string() })
  .superRefine((val, ctx) => {
    const issue = (message: string, path: string) =>
      ctx.addIssue({ code: z.ZodIssueCode.custom, message, path: [path] });

    if (val.next !== val.confirm) issue('확인이 일치하지 않습니다', 'confirm');
    if (val.next === val.current) issue('이전 비밀번호와 같습니다', 'next');
  }); // path 로 폼 필드에 정확히 매핑된다
```

`ctx.addIssue` 를 호출하면 파싱은 실패로 마킹되지만 콜백은 끝까지 실행되므로 모든 문제를 한 번에 수집한다. 조기 중단은 `fatal: true` 와 `z.NEVER` 반환을 조합한다. 한편 `.pipe()` 는 transform 의 출력을 다시 완전한 스키마로 검증한다.

```ts
const PositiveIntFromString = z
  .string()
  .transform((s) => Number.parseInt(s, 10))
  .pipe(z.number().int().positive());

PositiveIntFromString.parse('abc'); // ZodError: Expected number, received nan
```

`transform` 은 무엇이든 반환할 수 있어 검증 구멍이 되기 쉬운데, `pipe` 의 두 번째 인자는 온전한 스키마라 출력을 다시 검증해 준다.

## 6. 브랜디드 타입 — 검증 사실을 타입에 새기기

TypeScript 는 구조적 타이핑이라 모양이 같으면 같은 타입이다. Java 라면 `UserId`/`OrderId` 를 클래스로 만들어 명목적(nominal) 구분을 얻지만, TypeScript 의 `type UserId = string` 과 `type OrderId = string` 은 자유롭게 섞인다. 같은 효과를 내는 게 브랜디드 타입이다.

```ts
declare const brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [brand]: B };

type UserId = Brand<string, 'UserId'>;
type OrderId = Brand<string, 'OrderId'>;

function cancelOrder(orderId: OrderId) {}
cancelOrder('u_123' as UserId); // 컴파일 에러: 'UserId' 는 'OrderId' 에 할당할 수 없음
```

`string & { readonly [brand]: 'UserId' }` 는 교차 타입이다. 런타임에는 그냥 string 이고 `brand` 프로퍼티는 존재하지 않는다 — 2절의 `_output` 과 같은 팬텀 기법으로, 구조적 타이핑 안에서 명목적 타이핑을 흉내낸 것이다. 타입 검사기 입장에서 `UserId` 는 `OrderId` 가 요구하는 `[brand]: 'OrderId'` 를 갖지 않으므로 할당 불가다. 키를 `unique symbol` 로 두면 문자열 키(`__brand`)와 달리 선언 위치마다 고유해 브랜드가 겹칠 여지가 없다. Zod 는 `.brand()` 로 이를 내장 지원한다.

```ts
const EmailSchema = z.string().email().toLowerCase().brand<'Email'>();
type Email = z.infer<typeof EmailSchema>; // string & z.BRAND<'Email'>

function sendWelcome(to: Email) {}
sendWelcome('a@b.com');                    // 컴파일 에러
sendWelcome(EmailSchema.parse('A@B.com')); // OK, 정규화된 Email
```

`.brand()` 는 런타임에 아무 일도 하지 않는다. 값을 그대로 통과시키고 출력 타입에만 `BRAND<T>` 교차를 얹으므로 비용이 없다.

이것이 "**Parse, don't validate**" 원칙의 실체다. `validate(x: string): void` 는 검증 사실을 **버린다** — 함수가 끝나면 컴파일러는 x 가 검증됐는지 모르므로, 방어적으로 다시 검증하거나 검증했다고 믿고 넘어가다 사고가 난다. 반면 `parse(x: unknown): Email` 은 검증 사실을 **반환 타입에 담는다**. `Email` 값을 들고 있다는 것 자체가 증거다.

Spring 의 `@Email String email` 은 컨트롤러 경계에서만 유효하고 서비스로 넘어간 `String` 은 다시 그냥 String 이라, 서비스를 직접 호출하는 배치 잡이나 테스트에서는 검증이 통째로 빠진다. 브랜디드 타입은 이 구멍을 타입 레벨에서 막는다. 다만 `as Email` 로 위조가 가능하므로 생성은 `parse` 를 통하게 하고 캐스팅은 린트로 막는다.

## 7. discriminatedUnion — O(1) 디스패치와 에러 품질

웹훅 이벤트처럼 여러 형태 중 하나인 데이터에서는 두 방식의 차이가 크다.

```ts
const Created = z.object({ type: z.literal('CREATED'), orderId: z.string().uuid(), amount: z.number() });
const Cancelled = z.object({ type: z.literal('CANCELLED'), orderId: z.string().uuid(), reason: z.string() });
const Failed = z.object({ type: z.literal('FAILED'), orderId: z.string().uuid(), code: z.enum(['EXPIRED']) });

const EventUnion = z.union([Created, Cancelled, Failed]);                   // 순차 시도
const EventDU = z.discriminatedUnion('type', [Created, Cancelled, Failed]); // 판별자 디스패치
```

`z.union` 은 입력을 각 옵션에 **순서대로 전부 시도**하고, 전부 실패하면 `invalid_union` 이슈 안에 모든 옵션의 에러를 `unionErrors` 로 담아 던진다. N개 옵션이면 최악의 경우 N번 파싱한다. 반면 `z.discriminatedUnion` 은 스키마 생성 시점에 판별자 리터럴을 읽어 `Map<판별자값, 스키마>` 를 만들고, 파싱 때는 `input.type` 으로 Map 조회 한 번을 해 대상 스키마만 실행한다. 조회는 O(1), 파싱은 1회다. 성능보다 더 체감되는 건 **에러 품질**이다.

| | `z.union` | `z.discriminatedUnion` |
| --- | --- | --- |
| 에러 구조 | `invalid_union` 하나, 3개 옵션의 실패 이유 전부 중첩 | `orderId` 경로에 `invalid_string` 하나 |
| 실용성 | "type 이 CREATED 가 아님" 같은 노이즈가 섞임 | 사용자에게 그대로 노출 가능 |
| 파싱 횟수 | 최대 3회 | 1회 |

union 은 어느 옵션을 의도했는지 알 수 없어 전부 나열하지만, discriminatedUnion 은 판별자로 의도를 확정해 그 스키마의 에러만 보고한다. 제약도 있다. 판별자 필드는 각 옵션에서 `z.literal`(또는 `z.enum`)이어야 하고 값이 겹치면 안 되는데, 위반하면 **모듈 로드 시점**에 에러가 난다. 파싱 결과는 discriminated union 으로 추론되어 `switch` 에 exhaustiveness 검사가 붙는다.

```ts
function handle(e: z.infer<typeof EventDU>) {
  switch (e.type) {
    case 'CREATED':   return charge(e.amount); // e.amount 접근 가능
    case 'CANCELLED': return refund(e.reason);
    case 'FAILED':    return alert(e.code);
    default: {
      const _exhaustive: never = e; // 이벤트 추가 시 컴파일 에러로 누락을 알림
      throw new Error(`unhandled: ${_exhaustive}`);
    }
  }
}
```

## 8. 성능과 번들 — 어디서 비용이 발생하는가

**스키마 객체 생성 비용.** `z.object({...})` 는 인스턴스를 만들고 `_def` 트리를 구성하며, `.min()`/`.email()` 같은 체이닝 메서드는 불변이라 **새 인스턴스를 반환**한다. 핸들러 안에서 `z.object(...)` 를 호출하면 매 요청 트리를 재생성하므로, 스키마는 모듈 상수로 두고 재사용해야 한다.

**tsc 타입 인스턴스화 부하.** 런타임이 아니라 개발 경험 비용이다. `z.object` 의 출력 타입은 매핑 타입의 재귀 계산이라, 깊게 중첩되거나 `.extend()` 를 여러 번 겹친 스키마는 IDE 자동완성을 느리게 하거나 `Type instantiation is excessively deep and possibly infinite` 에러를 낸다. 중간에서 `type X = z.infer<typeof S>` 로 타입을 **고정**하면 그 아래 계산이 재개되지 않는다. 재귀 스키마는 `z.lazy` 만으로는 추론이 순환하므로 `const C: z.ZodType<Category> = z.lazy(...)` 처럼 명시적 애너테이션을 준다.

**번들 크기.** Zod v3 는 메서드가 단일 클래스의 프로토타입에 붙어 있어 트리 셰이킹이 잘 되지 않아, `z.string()` 하나만 써도 라이브러리 상당 부분이 번들에 들어온다. 수치는 버전·환경에 따라 달라지므로 특성 위주로 비교한다.

| | Zod (v3) | io-ts | Valibot |
| --- | --- | --- | --- |
| API 스타일 | 메서드 체이닝, 클래스 기반 | fp-ts 기반, `Either` 반환 | 함수 파이프라인 |
| 학습 곡선 | 낮음 | 높음 (fp-ts 필요) | 낮음~중간 |
| 트리 셰이킹 | 어려움 | 부분적 | 설계 목표. 쓴 것만 번들 |
| 생태계 | 매우 큼 (tRPC, react-hook-form 등) | 작음 | 성장 중 |

서버 사이드라면 번들 크기가 무관하고 생태계 이점이 크므로 Zod 가 무난하고, 프론트엔드에서 kB 를 다툰다면 Valibot 을 검토한다. 무엇을 고르든 "경계에서 파싱하고 결과 타입만 내부로 흘린다"는 원칙은 같다. 그리고 **검증을 없애는 최적화는 거의 항상 잘못된 최적화다.** 파싱 비용은 대개 DB 왕복에 비해 무시할 만하다.

## 9. 실무 경계 설계 — DTO, env, 에러 매핑

**스키마를 단일 소스로.** DTO 인터페이스와 스키마를 따로 만들면 둘이 어긋난다. 스키마만 두고 타입은 파생시킨다.

```ts
// user.schema.ts
export const CreateUserRequest = z.object({
  email: z.string().email().toLowerCase().brand<'Email'>(),
  age: z.coerce.number().int().min(14),
  role: z.enum(['ADMIN', 'MEMBER']).default('MEMBER'),
});
export type CreateUserIn = z.input<typeof CreateUserRequest>;   // 클라이언트/테스트용
export type CreateUserOut = z.output<typeof CreateUserRequest>; // 서비스 계층용

export const UserResponse = z.object({ id: z.string().uuid().brand<'UserId'>() });
```

요청과 응답 스키마를 분리해야 `password` 가 응답에 새는 식의 오염을 막는다. 참고로 Zod 의 기본은 unknown 키를 **에러 없이 조용히 제거(strip)** 하는 것이므로, 요청에서 오타 필드를 잡으려면 `.strict()` 를 명시해야 한다.

**env 검증은 부팅 시 1회, fail fast.**

```ts
const EnvSchema = z.object({
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().url(),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET 은 32자 이상이어야 합니다'),
});

const parsed = EnvSchema.safeParse(process.env); // 모듈 로드 시 1회
if (!parsed.success) {
  for (const i of parsed.error.issues) console.error(`  ${i.path.join('.')}: ${i.message}`);
  process.exit(1); // fail fast
}
export const env = parsed.data;
```

`env.PORT` 는 `number` 로 확정되고 `env.JWT_SECRET!` 같은 non-null 단언이 사라진다. 오타난 환경변수 때문에 배포 30분 뒤에 터지는 대신 컨테이너가 즉시 죽는다. Spring 의 `@ConfigurationProperties` + `@Validated` 와 같다.

**ZodError → API 응답 매핑.** `ZodError.issues` 는 구조화된 배열이고 각 이슈에 `code`, `path`(배열), `message` 가 있다.

```ts
export function toFieldErrors(err: z.ZodError) {
  return err.issues.map((i) => ({
    field: i.path.length > 0 ? i.path.join('.') : '_root',
    code: i.code,
    message: i.message,
  }));
}
```

`issue.path` 가 `['items', 0, 'quantity']` 배열이므로 `join('.')` 하면 `items.0.quantity` 가 되어 폼 필드에 그대로 매핑된다. `err.flatten()`/`err.format()` 내장 헬퍼도 있지만 직접 매핑 함수를 두는 편이 낫다 — 라이브러리 출력 형태가 공개 API 계약이 되면 버전 업그레이드가 파괴적 변경이 된다. 기본 에러 메시지는 내부 제약을 드러내므로 응답 계층에서 한 번 걸러야 한다.

검증은 미들웨어 한 곳에 모으고 `req.body` 를 파싱 결과로 **교체**한다. 원본을 남겨두면 핸들러가 검증 전 값을 읽을 수 있다. 규칙은 세 줄이다. 신뢰 경계마다 스키마를 하나 둔다. `parse` 를 통과한 값만 안쪽으로 흘리고, 중요한 값은 브랜드를 붙인다. 경계 안쪽에서는 다시 검증하지 않는다 — 타입이 이미 증거다.

## 참고

- [TypeScript Handbook — Type Compatibility (구조적 타이핑)](https://www.typescriptlang.org/docs/handbook/type-compatibility.html)
- [TypeScript Handbook — Narrowing / Discriminated Unions](https://www.typescriptlang.org/docs/handbook/2/narrowing.html)
- [Zod 공식 문서](https://zod.dev/)
- [Alexis King — Parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
- [Valibot 공식 문서](https://valibot.dev/)
