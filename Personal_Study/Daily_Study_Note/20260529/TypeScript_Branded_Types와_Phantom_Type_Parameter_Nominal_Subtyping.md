Notion 원본: https://www.notion.so/36f5a06fd6d381e59c54c14c49eabf51

# TypeScript Branded Types와 Phantom Type Parameter Nominal Subtyping

> 2026-05-29 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 구조적 타입 시스템에서 nominal 구분이 필요한 시점을 식별한다
- intersection brand, unique symbol brand, opaque type 의 세 방식을 비교한다
- phantom type parameter 로 단위/상태/권한을 컴파일타임에 추적한다
- runtime 검증(Zod / io-ts)과 branded type 의 결합 패턴을 작성한다

## 1. Structural Typing 의 한계

TypeScript 는 구조적 타입 시스템이다. 두 타입의 멤버 모양이 같으면 호환된다. 그래서 다음 코드가 컴파일된다.

```typescript
type UserId = string;
type EmailAddress = string;

function getUser(id: UserId) { /* ... */ }

const email: EmailAddress = "user@example.com";
getUser(email);  // ✓ — 의도와 다르지만 통과
```

도메인 모델이 커지면 이런 실수의 비용이 커진다. branded type 은 의도적으로 nominal 구분을 도입해 이를 막는다.

## 2. Intersection Brand — 가장 단순한 패턴

```typescript
type Brand<T, B> = T & { readonly __brand: B };

type UserId = Brand<string, "UserId">;
type EmailAddress = Brand<string, "EmailAddress">;

function asUserId(s: string): UserId {
  if (!/^[a-z0-9-]+$/.test(s)) throw new Error("invalid");
  return s as UserId;
}

const id: UserId = asUserId("abc-123");

function getUser(id: UserId) { /* ... */ }
getUser(id);     // ✓
```

`__brand` 는 실제로 존재하지 않는 phantom field 다. runtime 에는 `id` 가 그냥 `string` 이다. 컴파일타임에만 구분된다는 게 핵심.

## 3. Unique Symbol Brand — 충돌 방지

문자열 brand 는 `"UserId"` 같은 매직 스트링에 의존한다. `unique symbol` 을 brand 로 쓰면 모듈 단위로 유일성이 보장된다.

```typescript
declare const UserIdBrand: unique symbol;
export type UserId = string & { readonly [UserIdBrand]: typeof UserIdBrand };

declare const EmailBrand: unique symbol;
export type EmailAddress = string & { readonly [EmailBrand]: typeof EmailBrand };
```

`declare const` 는 runtime 객체를 만들지 않으면서 타입 위치에 unique symbol 을 제공한다. 두 brand 의 symbol 이 서로 다른 unique symbol 이므로, 다른 모듈에서 같은 이름을 써도 호환되지 않는다.

## 4. Opaque Type — TypeScript 의 비공식 패턴

Flow 의 `opaque type` 처럼 module boundary 너머에서는 내부 구조를 알 수 없는 brand 를 만드는 패턴이다.

```typescript
const tag = Symbol("SecretToken");
type Tag = typeof tag;
export type SecretToken = string & { [tag]: Tag };

export function issue(value: string): SecretToken {
  return value as SecretToken;
}

export function reveal(t: SecretToken): string {
  return t;
}
```

`Symbol` 자체는 export 하지 않고 type 만 export 한다. 외부 모듈은 `SecretToken` 의 `[tag]` 멤버를 만들 방법이 없다(`any` cast 외에). 캡슐화를 컴파일러로 보장한다.

## 5. Phantom Type Parameter — 단위, 상태, 권한 추적

phantom type 은 generic parameter 가 실제 값으로 쓰이지 않고 타입 표시만 하는 패턴이다. 단위 시스템이 전형 예시다.

```typescript
type Quantity<U> = number & { readonly __unit: U };

type Meters = Quantity<"m">;
type Seconds = Quantity<"s">;
type MetersPerSecond = Quantity<"m/s">;

declare function add<U>(a: Quantity<U>, b: Quantity<U>): Quantity<U>;
declare function div<A, B>(a: Quantity<A>, b: Quantity<B>): Quantity<`${A & string}/${B & string}`>;

declare const d: Meters;
declare const t: Seconds;

const same = add(d, d);          // ✓ Meters
const wrong = add(d, t);         // ✗ Type mismatch
const v: MetersPerSecond = div(d, t);
```

상태 머신에도 같은 패턴이 쓰인다.

```typescript
type Connection<S extends "open" | "closed"> = {
  readonly __state: S;
  readonly socket: WebSocket;
};

declare function open(): Connection<"open">;
declare function send(c: Connection<"open">, msg: string): void;
declare function close(c: Connection<"open">): Connection<"closed">;

const c = open();
send(c, "hello");
const closed = close(c);
send(closed, "x");  // ✗
```

`Connection<"closed">` 인스턴스에 `send` 호출이 컴파일 단계에서 막힌다. 런타임 검증 없이 invariant 가 보장된다.

## 6. Zod / io-ts 와의 통합

```typescript
import { z } from "zod";

const UserIdSchema = z.string().regex(/^[a-z0-9-]+$/).brand<"UserId">();
type UserId = z.infer<typeof UserIdSchema>;

function getUser(id: UserId) { /* ... */ }

const raw = req.params.id;
const parsed = UserIdSchema.parse(raw);
getUser(parsed);  // ✓
getUser(raw);     // ✗
```

Zod 의 `.brand<"Name">()` 는 내부적으로 intersection brand 와 같은 구조를 만든다. parser 가 성공한 값에만 brand 가 붙으므로 "검증된 값" 과 "원시 값" 을 타입으로 구분한다. 도메인 코드의 입력을 brand 된 타입으로만 받으면 검증 누락이 빌드타임에 잡힌다.

## 7. JSON 직렬화와 boundary 변환

brand 는 컴파일타임 정보라 직렬화에 영향이 없지만, deserialize 시점에서 brand 가 자동으로 다시 붙지 않는 점에 주의한다.

```typescript
const id: UserId = asUserId("abc");
const json = JSON.stringify({ id });

const parsed: { id: string } = JSON.parse(json);
// parsed.id 는 string, UserId 아님
```

API boundary 에서 parser 를 통과시키지 않으면 brand 가 유실된다. 정형 패턴은 다음 셋이다. (a) DTO 는 raw `string` 으로 받고 entry point 에서 한 번에 brand 로 변환, (b) parser library 의 schema 가 직렬화/역직렬화 양쪽을 정의, (c) ORM 레벨에서 column 마다 brand 적용. (a) 가 가장 단순하고 디버깅이 쉽다.

## 8. 운영 관점 — 비용과 한계

branded type 의 비용은 런타임 0, 컴파일타임 약간(intersection 추가) 이다. 무거운 점은 다음이다. (a) `JSON.stringify` 결과의 type 표현이 길어져 IDE hover 가 지저분, (b) 표준 메서드(`array.map`, `string.slice`) 의 결과가 brand 를 잃음, (c) 외부 라이브러리에 brand 된 타입을 넘기면 `string` 으로 widening 되어 정보 손실.

대규모 도메인 모델(은행, 결제, 의료) 에서 branded type 의 ROI 가 가장 크다. 작은 CRUD 앱에서는 오버키다. 도입 기준은 "이 타입의 잘못된 사용이 production 사고로 이어질 가능성이 있는가" 다. UserId/EmailAddress 정도는 보통 그렇지 않다. AccountNumber, AuthToken, MoneyAmount 같은 안전민감 타입에는 거의 항상 도입한다.

## 참고

- TypeScript Handbook — Type Aliases, Discriminated Unions, Brand
- Zod documentation — `.brand` and parser-derived nominal types
- Flow handbook — Opaque type aliases (개념 비교)
- Microsoft TypeScript wiki — FAQ on nominal typing
