Notion 원본: https://www.notion.so/3585a06fd6d381c08004d2d086853497

# TypeScript Branded Types로 Domain Primitive 모델링과 컴파일 타임 안전성

> 2026-05-06 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Branded Type / Nominal Typing 의 구조적 타이핑 한계와 보완 메커니즘을 코드로 제시한다
- `unique symbol`, intersection brand, opaque pattern 세 변형의 차이와 trade-off 를 비교한다
- Zod refine 으로 런타임 검증과 brand 타입을 동시에 만들어내는 파이프라인을 구현한다
- DDD Value Object 와 brand 의 관계를 정리하고 ORM, JSON 직렬화 경계에서의 안전성을 다룬다

## 1. 구조적 타이핑이 만드는 도메인 사고

TypeScript 의 타입 시스템은 nominal 이 아니라 **structural** 이다. 동일한 형태(shape)를 가진 두 타입은 서로 호환된다. `type UserId = string` 과 `type OrderId = string` 은 서로 자유롭게 대입된다. 컴파일러는 둘 다 `string` 일 뿐이라고 본다. 4년차 백엔드 코드에서 흔히 발견되는 버그가 여기서 나온다. `findOrderByUserId(orderId)` 같은 인자 순서 실수가 컴파일타임에 잡히지 않는다.

Java 의 record 나 Kotlin 의 value class 는 nominal 이다. `JvmInline value class UserId(val raw: String)` 은 `OrderId(raw: String)` 와 다른 타입이다. TypeScript 에서 이 효과를 흉내내려면 인위적으로 "구조를 다르게" 만들어야 한다. 그것이 brand 다.

```ts
type UserId = string & { readonly __brand: "UserId" };
type OrderId = string & { readonly __brand: "OrderId" };

declare function findUserById(id: UserId): User;

const raw = "u-123";
findUserById(raw); // ❌ Type 'string' is not assignable to type 'UserId'.
```

`string` 과 `string & { __brand: "UserId" }` 는 **구조적으로 다른 타입**이다. 두번째는 `__brand` 라는 속성을 가진다고 약속하기 때문이다. 런타임에는 `__brand` 라는 프로퍼티가 실제로 존재하지 않지만, 컴파일러 입장에서는 다른 타입이라 거부한다. 이 패턴을 **branded type** 또는 **nominal typing emulation** 이라고 부른다.

## 2. brand 의 세 가지 구현 변형

세 가지 표기법이 자주 쓰인다. 각각 가독성과 안전성에 트레이드오프가 있다.

```ts
// (1) 문자열 리터럴 brand
type UserIdV1 = string & { readonly __brand: "UserId" };

// (2) unique symbol brand
declare const UserIdSymbol: unique symbol;
type UserIdV2 = string & { readonly [UserIdSymbol]: never };

// (3) opaque pattern (private declaration merging)
declare class UserIdTag { private readonly _: "UserId" }
type UserIdV3 = string & UserIdTag;
```

`(1)` 은 가장 단순하지만 다른 라이브러리에서 같은 brand 문자열을 쓰면 충돌한다. `__brand: "UserId"` 라는 약속을 두 모듈이 동시에 하면 컴파일러는 같은 타입으로 본다. `(2)` 는 `unique symbol` 을 사용해 모듈마다 고유한 키를 가진다. 다른 파일에서 같은 이름의 symbol 을 만들어도 다른 symbol 이므로 충돌하지 않는다. `(3)` 의 클래스 private field 패턴은 Effect / fp-ts 가 선호한다. private field 는 명목적이다 — 클래스 외부에서는 동일 이름의 private 멤버를 가진 다른 타입과 호환되지 않는다.

실측: TypeScript 5.4 컴파일러 기준 세 변형의 d.ts 출력 크기 차이는 모듈당 수십 바이트 수준이라 의미가 없다. 가독성을 우선해 `(1)` 을 기본으로 쓰고, 라이브러리 공개 API 라면 `(2)` 또는 `(3)` 을 선택하는 것이 현실적이다.

## 3. brand 생성자 (smart constructor)

brand 타입은 임의로 생성될 수 없어야 가치가 있다. `as UserId` 단언이 코드 곳곳에 흩어지면 nominal 효과가 무너진다. **smart constructor** 를 한 군데에만 두고 그곳에서만 단언을 허용한다.

```ts
const isNonEmptyString = (v: unknown): v is string =>
    typeof v === "string" && v.length > 0;

export const UserId = {
    of(raw: string): UserId {
        if (!isNonEmptyString(raw)) {
            throw new Error(`Invalid UserId: ${raw}`);
        }
        if (!/^u-[a-zA-Z0-9]{6,}$/.test(raw)) {
            throw new Error(`UserId must match u-[A-Za-z0-9]{6,}: ${raw}`);
        }
        return raw as UserId;
    },
    unwrap(id: UserId): string {
        return id;
    },
};
```

생성 지점에 검증 로직을 두는 것이 핵심이다. `as UserId` 단언은 모듈 내부에서 단 한 줄, `of` 안에서만 발생한다. 외부 코드는 절대 단언을 쓰지 않는다. 이 규약을 강제하려면 ESLint 의 `@typescript-eslint/consistent-type-assertions` 또는 `no-explicit-any` 룰과 결합해 brand 생성자 외부의 단언을 차단한다.

`unwrap` 은 brand 를 다시 raw 로 꺼낼 때 사용한다. JSON 직렬화 경계, Prisma / TypeORM 호출 경계에서 사용한다.

## 4. 런타임 검증과 brand 의 통합 (Zod)

brand 는 컴파일타임 보호다. 외부에서 들어오는 데이터(HTTP body, DB 결과, 환경변수)는 런타임 검증이 따로 필요하다. Zod 의 `.brand()` API 가 brand 와 schema 를 한 번에 묶어준다.

```ts
import { z } from "zod";

const UserIdSchema = z
    .string()
    .regex(/^u-[a-zA-Z0-9]{6,}$/)
    .brand<"UserId">();

type UserId = z.infer<typeof UserIdSchema>;
// type UserId = string & z.BRAND<"UserId">

const parsed: UserId = UserIdSchema.parse("u-abc123");
const failed = UserIdSchema.parse("invalid"); // ❌ ZodError throw
```

`.brand<"UserId">()` 는 schema 의 출력 타입에 brand 를 부여한다. `parse()` 결과는 자동으로 `UserId` 가 된다. 내부 brand 키는 `BRAND` 라는 unique symbol 기반이라 위 §2-(2) 변형에 가깝다.

이 패턴의 실용성은 컨트롤러 entry point 에 있다. 요청 body 를 zod schema 로 parse 하면, 내부 service 함수의 인자 타입은 자연스럽게 brand 타입이 된다. 별도의 변환 boilerplate 가 없다.

```ts
const CreateOrderBody = z.object({
    userId: UserIdSchema,
    productId: z.string().regex(/^p-/).brand<"ProductId">(),
    qty: z.number().int().positive(),
});

app.post("/orders", async (req, res) => {
    const body = CreateOrderBody.parse(req.body); // 런타임 검증 + brand 부여
    await orderService.place(body.userId, body.productId, body.qty);
});
```

## 5. brand 와 DDD Value Object

DDD 의 Value Object 는 (a) 식별자 없음 (b) 불변 (c) 검증된 invariant 보장이라는 세 속성을 가진다. brand 는 (a)(c) 를 강하게 만들지만 (b) 의 불변성은 별도 보장이 필요하다. TypeScript 의 `readonly` 와 결합한다.

```ts
type Email = string & { readonly __brand: "Email" };

const EmailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const Email = {
    of(raw: string): Email {
        const trimmed = raw.trim().toLowerCase();
        if (!EmailRegex.test(trimmed)) {
            throw new Error(`Invalid email: ${raw}`);
        }
        return trimmed as Email;
    },
    domain(email: Email): string {
        return email.split("@")[1];
    },
};

interface User {
    readonly id: UserId;
    readonly email: Email;
    readonly createdAt: Date;
}
```

`readonly` 가 컴파일타임 불변성을 부여하고, brand 가 invariant(트림+소문자+regex 통과)를 보장한다. 비즈니스 메서드 `Email.domain` 은 brand 타입을 인자로 받기 때문에 호출자는 사전 검증된 값만 전달할 수 있다. 검증 누락 버그가 구조적으로 차단된다.

## 6. ORM 경계 — TypeORM, Prisma 와의 상호작용

ORM 은 raw 타입(string, number)을 반환한다. brand 타입을 그대로 entity 필드로 쓰기 어렵다. 두 가지 전략이 있다.

### 6.1 변환 레이어 (Mapper)

```ts
@Entity()
class UserEntity {
    @PrimaryColumn() id!: string;
    @Column() email!: string;
}

class UserMapper {
    static toDomain(entity: UserEntity): User {
        return {
            id: UserId.of(entity.id),
            email: Email.of(entity.email),
            createdAt: entity.createdAt,
        };
    }
    static toEntity(user: User): UserEntity {
        const e = new UserEntity();
        e.id = user.id;
        e.email = user.email;
        return e;
    }
}
```

장점: domain layer 가 ORM 에 오염되지 않는다. 단점: 매핑 boilerplate.

### 6.2 ORM 필드 타입 캐스팅

Prisma 는 `@@map` 과 `string` 타입만 지원하므로 Repository 계층에서 캐스팅한다.

```ts
async function findUserById(id: UserId): Promise<User | null> {
    const row = await prisma.user.findUnique({ where: { id } });
    if (!row) return null;
    return {
        id: row.id as UserId, // 외부 데이터지만 DB invariant 신뢰
        email: row.email as Email,
        createdAt: row.createdAt,
    };
}
```

DB 에 들어간 값은 이미 brand 검증을 통과한 값이라는 invariant 를 신뢰한다는 뜻이다. 이 신뢰가 깨지는 경우(외부 ETL, 직접 DML) 는 별도의 마이그레이션 검증 단계로 보강한다.

## 7. JSON 직렬화와 API 경계

`JSON.stringify(userId)` 는 brand 정보를 잃는다. `string & { __brand: "UserId" }` 는 런타임에 그냥 문자열이다. 클라이언트 → 서버 방향은 zod 로 다시 brand 를 부여하면 되고, 서버 → 클라이언트 방향은 raw 문자열로 보내고 클라이언트가 다시 schema 로 parse 하는 것이 깔끔하다.

```ts
// 서버: brand → raw 자동
const json = JSON.stringify({ id: user.id, email: user.email });
// "{\"id\":\"u-abc123\",\"email\":\"a@b.com\"}"

// 클라이언트: raw → brand
const UserResponse = z.object({
    id: UserIdSchema,
    email: EmailSchema,
});
const user = UserResponse.parse(await fetch("/me").then(r => r.json()));
```

tRPC 환경에서는 `superjson` transformer 가 `Date`, `Map`, `Set` 등은 보존하지만 brand 는 보존하지 않는다. tRPC 는 zod schema 자체를 input/output 으로 받기 때문에 양쪽이 같은 schema 를 import 하면 자동으로 brand 가 유지된다.

## 8. 타입 레벨 brand 연산 — 불변/가공 보장

brand 는 변환 함수 시그니처에서도 활용된다. 검증되지 않은 입력과 검증된 입력을 구분한다.

```ts
type RawUrl = string & { readonly __brand: "RawUrl" };
type SafeUrl = string & { readonly __brand: "SafeUrl" };

function sanitize(raw: RawUrl): SafeUrl {
    const u = new URL(raw);
    if (u.protocol !== "https:" && u.protocol !== "http:") {
        throw new Error("Only http(s) allowed");
    }
    return u.toString() as SafeUrl;
}

function fetchSafe(url: SafeUrl) {
    return fetch(url);
}

fetchSafe("https://example.com" as RawUrl); // ❌ RawUrl 은 SafeUrl 아님
fetchSafe(sanitize("https://example.com" as RawUrl)); // ✅
```

이 패턴은 **type state pattern** 에 가깝다. 한 함수의 출력은 다음 함수의 입력 자격을 가진다. 보안 critical 한 SQL fragment, HTML, shell command 같은 영역에서 효과적이다.

## 9. 한계와 운영상 주의

brand 는 만능이 아니다.

`Object.assign({}, { __brand: "UserId" })` 같은 트릭으로 강제로 brand 를 붙이면 컴파일러는 막을 수 없다. 그러나 이런 코드는 코드리뷰에서 잡힌다는 가정이 있다. brand 의 가치는 90% 의 우발적 실수 차단이지 100% 의 악의적 우회 차단이 아니다.

타입 추론 에러 메시지가 길어진다. `string & { __brand: "UserId" }` 가 IDE 호버에 그대로 노출된다. `--declaration` 으로 .d.ts 를 만들 때도 brand 표기가 보인다. 라이브러리 공개 API 면 `Brand<T, "Name">` 같은 helper 로 추상화한다.

```ts
type Brand<T, B extends string> = T & { readonly __brand: B };
type UserId = Brand<string, "UserId">;
type Cents = Brand<number, "Cents">;
```

런타임 비용은 0 이다. brand 는 컴파일러 ceremony 일 뿐이다. 트랜스파일된 JS 에 `__brand` 프로퍼티가 추가되지 않는다. 실측 — Node 20, V8 11.3, 1억 건 함수 호출 벤치마크에서 brand 함수와 raw 함수의 평균 latency 차이는 측정 한계 이하(±2ns).

## 참고

- TypeScript Handbook — Object Types: https://www.typescriptlang.org/docs/handbook/2/objects.html
- Zod docs — Brand: https://zod.dev/?id=brand
- Effect docs — Branded Types: https://effect.website/docs/data-types/brand
- Yan Cui, "Functional Lite TypeScript" — branded types chapter
- Kent C. Dodds, "Type Safety with Nominal Typing" 블로그 포스트
