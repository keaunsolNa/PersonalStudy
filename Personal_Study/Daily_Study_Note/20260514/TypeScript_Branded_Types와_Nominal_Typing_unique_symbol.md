Notion 원본: https://www.notion.so/3605a06fd6d3814eb4a3f1feac6152ea

# TypeScript Branded Types와 Nominal Typing — unique symbol로 구조적 타입 시스템에 명목성 주입

> 2026-05-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 구조적(structural) 타입 시스템의 한계와 *명목적(nominal)* 안전성이 필요한 도메인을 식별
- `unique symbol` · intersection 기반 branding 패턴을 비교하고 컴파일러가 어떻게 구별하는지 추론
- runtime cost 0 으로 `UserId` 와 `OrderId` 가 절대 섞이지 않도록 강제하는 API 설계
- Zod / Effect Schema / io-ts 의 brand 기능이 동일한 컴파일러 트릭을 어떻게 노출하는지 비교

## 1. 구조적 타입 시스템의 통증 지점

TypeScript 의 모든 타입 호환성은 *구조적*이다. `type UserId = string; type OrderId = string;` 이라고 선언해도 두 타입은 **완전히 호환** 되어 `repo.findUser(orderId)` 가 컴파일된다. Java 의 `class UserId { private final String value; }` 같은 nominal 보호는 기본 제공되지 않는다. 4년차 이상 백엔드를 다룬 적이 있다면 *문자열 ID 가 잘못된 인자에 들어가서* 발생한 장애를 한 번쯤 봤을 것이다. 구조적 시스템에선 `string` 인 한 컴파일러는 침묵한다.

이를 해결하기 위해 TS 커뮤니티는 *branded type* (== *opaque type*, *nominal type*) 패턴을 표준 관용구로 굳혔다. 핵심 아이디어는 "두 타입의 *구조* 가 동일해 보이지 않게 한쪽에 *유령(phantom)* 필드를 추가" 하는 것이다. 런타임에는 아무것도 추가되지 않고, 컴파일러만 차별한다.

## 2. 가장 단순한 brand — intersection + literal tag

```ts
type Brand<T, Tag extends string> = T & { readonly __brand: Tag };

type UserId = Brand<string, "UserId">;
type OrderId = Brand<string, "OrderId">;

function asUserId(raw: string): UserId {
    return raw as UserId; // 단 한 군데에서만 cast 허용
}

function findUser(id: UserId) { /* ... */ }

const uid = asUserId("u_123");
const oid = "o_999" as OrderId;

findUser(uid);  // OK
findUser(oid);  // ❌ Type 'OrderId' is not assignable to parameter of type 'UserId'.
findUser("u_123"); // ❌ string 은 UserId 아님
```

작동 원리는 `string & { __brand: "UserId" }` 와 `string & { __brand: "OrderId" }` 가 *intersect 된 객체 부분이 다르므로* 호환되지 않는다는 점이다. 런타임에는 `__brand` 라는 필드가 실제로 존재하지 않지만, 구조적 비교 시 컴파일러는 "OrderId 에는 `__brand: "UserId"` 가 없다" 고 판단한다.

## 3. `unique symbol` 로 충돌 가능성 0 으로

literal string tag 의 약점은 사용자가 우연히 같은 문자열을 쓰면 두 타입이 호환된다는 점이다. 보안 토큰처럼 모듈 외부에서 *위조* 가 불가능해야 한다면 `unique symbol` 을 쓴다.

```ts
declare const userIdBrand: unique symbol;
declare const orderIdBrand: unique symbol;

type UserId = string & { readonly [userIdBrand]: true };
type OrderId = string & { readonly [orderIdBrand]: true };
```

`unique symbol` 은 *각 선언 위치마다 컴파일러가 부여한 고유 ID* 를 갖는다. 같은 파일을 두 번 import 해도 동일하지만, 다른 파일에서 별도로 `declare const x: unique symbol` 을 만들면 *절대* 같지 않다. 이 점은 brand 충돌을 *언어 수준* 에서 막아준다. literal tag 의 "휴먼 컨벤션" 보장을 컴파일러 보장으로 끌어올린 것.

`declare const` 이므로 런타임 값이 존재하지 않아도 된다. *type position* 에서만 참조하므로 import 도 type-only 가 가능하다.

## 4. Smart Constructor — 검증을 brand 와 묶기

brand 의 진짜 가치는 *경계에서 한 번 검증한 값* 만 brand 타입을 들고 다닌다는 데서 나온다. 그래야 *내부 함수는 다시 검증하지 않아도 된다.*

```ts
type Email = string & { readonly [emailBrand]: true };
declare const emailBrand: unique symbol;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function parseEmail(raw: string): Email {
    if (!EMAIL_RE.test(raw)) {
        throw new Error(`invalid email: ${raw}`);
    }
    return raw as Email;
}

export function sendMail(to: Email, body: string) { /* ... */ }

// 사용처
const e = parseEmail(req.body.email); // throws on invalid
sendMail(e, "hi");                    // 검증 끝났으니 안전
sendMail(req.body.email, "hi");       // ❌ string 은 Email 아님 — 컴파일 거부
```

이는 *parse, don't validate* (Alexis King) 패턴의 TS 적용이다. boolean 을 반환하는 `isEmail()` 보다 *narrowing 결과를 타입에 가둔* `parseEmail()` 이 안전하다. 함수 시그니처만 보면 코드 리뷰어가 "이 인자는 이미 검증된 이메일" 임을 즉시 안다.

## 5. Result 타입과 결합해 throw 없이

throw 가 부담스러우면 `Result<E, T>` 패턴과 결합한다.

```ts
type Result<E, T> = { ok: true; value: T } | { ok: false; error: E };

export function parseEmailSafe(raw: string): Result<"invalid_email", Email> {
    return EMAIL_RE.test(raw)
        ? { ok: true, value: raw as Email }
        : { ok: false, error: "invalid_email" };
}

const r = parseEmailSafe(req.body.email);
if (!r.ok) return res.status(400).json({ error: r.error });
sendMail(r.value, "hi"); // r.value: Email
```

`Result` 가 *discriminated union* 이므로 `if (!r.ok) return;` 만 통과하면 r.value 가 `Email` 로 narrow 된다. brand 와 narrowing 이 자연스럽게 합쳐진다.

## 6. brand 가 *런타임 0 비용* 이 되는 이유

trick 의 정수는 `__brand` 필드 또는 symbol-keyed property 가 *실제로 객체에 추가되지 않는다는* 점이다. `tsc` 컴파일 후 출력 JS 를 보면:

```js
function parseEmail(raw) {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(raw)) throw new Error(...);
    return raw; // 그냥 string 그대로 반환
}
```

런타임 비용은 정확히 0. 메모리 footprint 변화도 없고, JSON 직렬화도 그대로 string 으로 나간다. 즉 brand 는 "타입 시스템에만 보이는 메타데이터" 다. 이는 Java 의 `record UserId(String value)` 가 객체 1개를 더 만드는 것과 대조된다.

## 7. 클래스 기반 nominal 과 비교

```ts
class UserIdClass {
    private readonly _tag = "UserId";
    constructor(public readonly value: string) {}
}
```

이 경우 *private 필드* 가 nominal 키 역할을 한다. TS 의 private 은 *런타임 캡슐화* 가 아니라 *컴파일 타임 호환성 비교 시 이름까지 일치해야 한다* 는 규칙으로 동작한다. 그래서 다른 클래스의 `_tag` 와 호환되지 않는다.

| 항목 | unique symbol brand | class with private |
|---|---|---|
| 런타임 객체 생성 | 없음 (raw string) | 매번 `new UserIdClass()` |
| JSON 직렬화 | 자동 | `value` 만 추출 필요 |
| 메서드 부착 | 별도 함수 | 인스턴스 메서드 가능 |
| 비교 (===) | `===` 그대로 | `value === value` 필요 |
| 단순성 | 매우 단순 | 객체 모델 도입 |

DDD value object 처럼 *행위* 가 있으면 class, 단순 ID/단위/검증된 원시값이면 brand 가 우월하다.

## 8. Zod / Effect Schema 의 `brand()` — 라이브러리가 노출하는 동일 패턴

Zod 4 는 `.brand<"UserId">()` 메서드로 동일 패턴을 노출한다.

```ts
import { z } from "zod";

const UserIdSchema = z.string().uuid().brand<"UserId">();
type UserId = z.infer<typeof UserIdSchema>;
// type UserId = string & z.BRAND<"UserId">

function findUser(id: UserId) { /* ... */ }

const uid = UserIdSchema.parse(req.body.id); // throws on invalid
findUser(uid);
findUser(req.body.id); // ❌ string is not UserId
```

내부 구현은 `intersection with { [BrandSymbol]: { UserId: "UserId" } }` 다. Effect Schema 의 `Schema.brand` 도 동일 원리이며, io-ts 의 `Branded<C, B>` 도 마찬가지다. *라이브러리가 다르더라도 컴파일러가 보는 type-level 트릭은 같다* — runtime validation 과 type-level brand 를 동시에 묶어 단일 호출로 끝내는 게 부가가치다.

## 9. 일반화된 brand 유틸리티

대형 코드베이스에서는 brand 를 다양한 primitive 위에 얹게 된다. 이때 다음 유틸리티가 유용하다.

```ts
declare const __brand: unique symbol;
export type Branded<T, B extends string> = T & { readonly [__brand]: B };

export type UserId   = Branded<string, "UserId">;
export type OrderId  = Branded<string, "OrderId">;
export type Cents    = Branded<number, "Cents">;
export type ISO8601  = Branded<string, "ISO8601">;

function add(a: Cents, b: Cents): Cents {
    return (a + b) as Cents;
}
```

`Branded<T, B>` 의 두 번째 type parameter 가 *literal string* 이면 충돌 가능성이 있다. 진짜 보안이 필요한 영역(권한 토큰, 검증된 SQL fragment)은 이 유틸 위에 별도의 `unique symbol` brand 를 한 번 더 얹어 *2중 brand* 로 보호한다.

## 10. 흔한 함정과 해결

**함정 1 — 라이브러리 경계에서 brand 가 사라진다.** 외부 함수가 `string` 을 반환하면 brand 가 떨어진다. 해결: *경계 함수* 를 `parseEmail` 처럼 직접 작성하고 그 안에서 한 번만 cast.

**함정 2 — JSON 역직렬화 후 brand 가 자동으로 다시 붙지 않는다.** `JSON.parse(body)` 의 결과는 `any` (또는 `unknown`). 다시 `parseUserId` 를 거치지 않으면 brand 가 없다. Zod 같은 schema layer 가 *진입점에 한 번* 끼면 자연스럽게 brand 가 회복된다.

**함정 3 — `any` 가 brand 를 무력화한다.** `as any as UserId` 한 줄로 무너진다. eslint 의 `@typescript-eslint/no-explicit-any` 와 `@typescript-eslint/no-unsafe-assignment` 을 *경계 코드* 에서만 풀어 주는 식으로 운영.

**함정 4 — discriminated union 과 혼동.** brand 는 *값이 같은데 타입만 다른* 케이스, discriminated union 은 *값 자체가 다른* 케이스. 둘 다 type narrowing 을 만들지만 도구 선택이 다르다. 사용자 입력 검증 → brand, 메시지 처리 분기 → union.

**함정 5 — brand 끼리 연산.** `Cents + Cents` 는 number 가 되어 brand 가 떨어진다. 위 9절의 `add(a, b): Cents` 처럼 *연산 함수도 brand 를 유지하도록* 명시적으로 cast 하거나, smart constructor 한 군데에서 brand 부착을 통일.

## 참고

- TypeScript Handbook — Nominal Types via Branded Types: https://www.typescriptlang.org/docs/handbook/utility-types.html
- Alexis King, *Parse, Don't Validate* (2019)
- Zod 4 API Reference — `.brand()`: https://zod.dev/api#brand
- Effect Schema docs — Brand & Refinement: https://effect.website/docs/schema/brand
- io-ts — Branded types: https://github.com/gcanti/io-ts/blob/master/index.md#branded-types
- TS issue #202 (Nominal typing): https://github.com/microsoft/TypeScript/issues/202
