Notion 원본: https://www.notion.so/37a5a06fd6d3814d9be9c55dadc92a29

# TypeScript Branded Types와 unique symbol 기반 Nominal Typing 그리고 런타임 검증 결합

> 2026-06-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 구조적 타이핑의 한계를 이해하고 Branded Type으로 명목적(nominal) 구별을 구현한다
- `unique symbol` 기반 브랜드가 문자열 리터럴 브랜드보다 안전한 이유를 설명한다
- 스마트 생성자(smart constructor)로 브랜드 부여와 런타임 검증을 결합한다
- 브랜드 타입을 Zod·Effect 등 검증 라이브러리와 통합하는 경계를 설계한다

## 1. 구조적 타이핑의 한계

TypeScript는 구조적(structural) 타입 시스템이다. 두 타입의 멤버 구조가 같으면 이름이 달라도 호환된다. 이는 유연하지만, 의미가 다른 동일 구조 값이 섞이는 사고를 막지 못한다.

```typescript
type UserId = string;
type OrderId = string;

function loadOrder(id: OrderId) { /* ... */ }

const userId: UserId = 'u_123';
loadOrder(userId); // 컴파일 통과! UserId 와 OrderId 가 모두 string 이므로 구별 안 됨
```

`UserId`와 `OrderId`는 둘 다 `string`이라 컴파일러가 구별하지 못한다. 금액(원 vs 달러), 좌표(픽셀 vs 미터), 검증 전/후 문자열처럼 "같은 원시 타입이지만 의미가 다른" 값이 섞이면 런타임 버그가 된다. 명목적 타이핑이 필요한 지점이다.

## 2. 교차 타입 브랜딩의 기본형

가장 단순한 브랜딩은 원시 타입에 가짜 프로퍼티를 교차(intersection)로 붙이는 것이다.

```typescript
type Brand<T, B> = T & { readonly __brand: B };

type UserId = Brand<string, 'UserId'>;
type OrderId = Brand<string, 'OrderId'>;

const uid = 'u_1' as UserId;   // 단언으로 브랜드 부여
const oid = 'o_9' as OrderId;

declare function loadOrder(id: OrderId): void;
loadOrder(uid); // 컴파일 에러: '__brand'의 'UserId'와 'OrderId'가 호환 안 됨
```

`__brand`는 런타임에 실제로 존재하지 않는 팬텀(phantom) 프로퍼티다. 컴파일 타임에만 두 타입을 구별하게 하고 런타임 오버헤드는 0이다. 단점은 `__brand`라는 이름이 충돌하거나, 외부에서 `as`로 우회하기 쉬운 점이다.

## 3. unique symbol로 브랜드 강화

문자열 리터럴 브랜드는 같은 문자열을 쓰면 우연히 충돌할 수 있다. `declare const ... : unique symbol`로 만든 심볼 키는 전역적으로 유일해 충돌이 구조적으로 불가능하다.

```typescript
declare const brand: unique symbol;

type Branded<T, B extends string> = T & { readonly [brand]: B };

type Email = Branded<string, 'Email'>;
type Url = Branded<string, 'Url'>;
```

`[brand]` 인덱스는 `unique symbol`로 키가 고정되어 다른 모듈의 브랜드와 키 자체가 다르다. 또한 심볼 키는 객체 리터럴로 직접 만들 수 없어(컴파일러가 `unique symbol` 키 객체 생성을 막음) 의도치 않은 브랜드 위조가 더 어렵다. 라이브러리 공개 타입에는 `unique symbol` 방식이 권장된다.

## 4. 스마트 생성자: 브랜드와 런타임 검증 결합

브랜드는 타입 시스템만의 약속이므로, "이 값이 정말 유효한 이메일인가"는 런타임에서 검증해야 한다. 검증을 통과한 값에만 브랜드를 부여하는 스마트 생성자가 핵심 패턴이다.

```typescript
declare const brand: unique symbol;
type Branded<T, B extends string> = T & { readonly [brand]: B };
type Email = Branded<string, 'Email'>;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function makeEmail(raw: string): Email {
  if (!EMAIL_RE.test(raw)) {
    throw new TypeError(`Invalid email: ${raw}`);
  }
  return raw as Email; // 검증 통과 지점에서만 단언
}

function sendTo(to: Email) { /* 이미 유효성 보장됨 */ }

sendTo(makeEmail('a@b.com')); // OK
// sendTo('a@b.com');         // 컴파일 에러: string 은 Email 아님
```

핵심은 `as Email` 단언이 검증을 통과한 단 한 지점에만 존재한다는 것이다. 이후 `Email`을 받는 모든 함수는 추가 검증 없이 유효성을 신뢰할 수 있다. "검증은 경계에서 한 번, 내부는 타입으로 신뢰"라는 원칙이 구현된다. 실패를 예외 대신 `Result`로 표현하려면 다음처럼 한다.

```typescript
type Result<T, E> = { ok: true; value: T } | { ok: false; error: E };

function parseEmail(raw: string): Result<Email, string> {
  return EMAIL_RE.test(raw)
    ? { ok: true, value: raw as Email }
    : { ok: false, error: `Invalid email: ${raw}` };
}
```

## 5. 숫자 단위 브랜딩과 산술 안전성

물리 단위나 통화처럼 숫자 의미 구별에도 유효하다. 다만 브랜드된 숫자끼리 산술을 하면 결과가 `number`로 풀리는 점을 알아야 한다.

```typescript
type Won = Branded<number, 'Won'>;
type Usd = Branded<number, 'Usd'>;

const price = 1000 as Won;
const fee = 200 as Won;

const total = (price + fee) as Won; // 산술 결과는 number, 재단언 필요
// const wrong: Won = price + (5 as Usd); // 산술 자체는 막지 못함
```

`+` 연산자는 브랜드를 보존하지 못하므로 산술 결과를 다시 단언해야 한다. 따라서 단위 안전성을 강하게 원한다면 산술도 전용 함수(`addWon(a, b)`)로 감싸는 편이 안전하다. 브랜딩은 "함수 인자 경계"에서 가장 강력하고, "자유로운 산술" 영역에서는 약하다는 trade-off가 있다.

## 6. Zod와 브랜드 통합

Zod는 `.brand<T>()`로 파싱 결과에 브랜드를 부여한다. 런타임 스키마 검증과 컴파일 브랜드가 한 번에 묶인다.

```typescript
import { z } from 'zod';

const EmailSchema = z.string().email().brand<'Email'>();
type Email = z.infer<typeof EmailSchema>;
// string & z.BRAND<'Email'>

const parsed = EmailSchema.parse('a@b.com'); // Email 타입, 런타임 검증 동시 수행
```

`z.infer`로 추출한 타입이 자동으로 브랜드를 포함하므로, 스마트 생성자를 손으로 작성할 필요가 없다. API 입력 경계에서 `schema.parse(req.body)`를 한 번 호출하면 이후 도메인 로직 전체가 브랜드된 타입으로 흐른다. 검증 책임과 타입 책임을 한 선언에 모으는 것이 Zod 브랜딩의 이점이다.

## 7. 브랜드 타입 설계 가이드

| 결정 | 권장 | 이유 |
|---|---|---|
| 브랜드 키 방식 | `unique symbol` | 충돌·위조 방지, 라이브러리 안전 |
| 단언 위치 | 스마트 생성자 1곳 | 검증 단일 진실 공급원 |
| 실패 표현 | `Result`/예외 중 도메인 일관성 | 경계 정책에 맞춤 |
| 산술 안전 | 전용 연산 함수 | 연산자는 브랜드 미보존 |
| 검증 라이브러리 | Zod `.brand` 등 | 런타임+타입 통합 |

브랜드를 남발하면 모든 원시값에 생성자가 붙어 보일러플레이트가 폭증한다. 실제로 혼동 사고가 보고됐거나(예: ID 종류 혼용, 검증 전후 문자열), 도메인적으로 의미가 분명히 다른 값에만 선택적으로 적용하는 것이 비용 대비 효과가 크다.

## 8. Flavoring과 약한 브랜딩, 그리고 직렬화 경계

엄격한 브랜딩(`__brand: B`)은 단언 없이는 절대 호환되지 않는다. 반대로 "flavoring"이라 불리는 약한 변형은 브랜드 프로퍼티를 옵셔널로 두어, 원시 리터럴을 브랜드 타입에 직접 대입하는 것은 허용하되 서로 다른 flavor끼리는 막는다.

```typescript
type Flavor<T, F> = T & { readonly __flavor?: F };

type UserId = Flavor<string, 'UserId'>;
type OrderId = Flavor<string, 'OrderId'>;

const uid: UserId = 'u_1';     // OK — 리터럴 직접 대입 허용(옵셔널 브랜드)
declare function loadOrder(id: OrderId): void;
// loadOrder(uid);             // 에러 — 서로 다른 flavor 는 여전히 충돌
```

flavoring은 생성자 없이도 쓸 수 있어 도입 비용이 낮은 대신, "검증된 값만 브랜드를 갖는다"는 보장이 약해진다. 식별자 혼용 방지가 목적이면 flavoring으로 충분하고, "검증 통과를 타입으로 증명"해야 하면 엄격한 브랜딩이 필요하다.

직렬화 경계도 주의점이다. 브랜드 프로퍼티는 팬텀이라 `JSON.stringify`에 나타나지 않지만, 역직렬화(`JSON.parse`) 결과는 `any`/`unknown`이므로 브랜드가 사라진다. 따라서 네트워크·DB 경계를 넘어 들어온 값은 반드시 다시 스마트 생성자나 Zod로 통과시켜 브랜드를 재부여해야 한다. "경계 안은 브랜드 신뢰, 경계를 넘으면 재검증"이 일관된 규칙이다.

## 9. trade-off 정리

Branded Type은 런타임 비용 없이 구조적 타이핑의 빈큐을 메워 의미가 다른 동형 값의 혼용을 컴파일 타임에 차단한다. 비용은 (1) 생성·단언 보일러플레이트, (2) 산술·문자열 연산에서 브랜드가 풀리는 불완전성, (3) 팀원의 학습 곡선이다. 핵심 전략은 "검증과 브랜딩을 스마트 생성자 또는 Zod 스키마 한 곳에 묶어 경계에서만 부여하고, 내부는 신뢰"하는 것이다. 명목적 안전성과 코드 단순성은 trade-off이며, 사고 위험이 높은 식별자·단위·검증 상태에 한정 적용할 때 ROI가 가장 높다.

## 참고

- TypeScript Handbook — Symbols / `unique symbol` (https://www.typescriptlang.org/docs/handbook/symbols.html)
- Zod 공식 문서 — `.brand()` Branded Types (https://zod.dev/?id=brand)
- Effect-TS — `Brand` 모듈 (https://effect.website)
- "Making illegal states unrepresentable" — 명목적 타이핑 설계 원칙 논의
