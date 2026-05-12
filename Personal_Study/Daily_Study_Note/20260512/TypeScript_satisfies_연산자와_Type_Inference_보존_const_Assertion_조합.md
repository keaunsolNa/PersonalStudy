Notion 원본: https://www.notion.so/35e5a06fd6d38124be5eec0a49c294be

# TypeScript satisfies 연산자 — Type Inference 보존과 const Assertion 조합 심화

> 2026-05-12 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `satisfies` 가 type annotation `: T` 와 어떻게 다른지 구체적 코드로 구분
- `as const` + `satisfies` 조합이 readonly literal 타입을 유지하면서 schema 제약을 거는 방식 이해
- TS 4.9 의 동작과 5.x 에서 개선된 narrowing 차이 파악
- 실제 라이브러리(Drizzle, tRPC, Zod)에서 `satisfies` 가 쓰이는 패턴 분석

## 1. 문제의 시작 — annotation 이 inference 를 망친다

TypeScript 에서 객체 리터럴을 정의할 때 흔한 패턴은 두 가지였다.

```ts
// 1) annotation
const colors: Record<string, string> = {
  red: "#FF0000",
  green: "#00FF00",
  blue: "#0000FF",
};

colors.red.toUpperCase();   // OK
const r = colors.red;       // r: string

// 2) annotation 없이
const colors2 = {
  red: "#FF0000",
  green: "#00FF00",
  blue: "#0000FF",
};

colors2.red.toUpperCase();  // OK
colors2.purple;             // 에러: 존재하지 않음
```

1) 은 *schema 검사는 되지만 키 정보가 string 으로 평탄화* 되어 `.purple` 같은 오타가 잡히지 않는다. 2) 는 inference 가 정확하지만 *값 타입 제약(예: 모든 값이 hex string)* 을 강제할 수 없다.

`satisfies` 는 이 둘을 동시에 해결한다.

## 2. `satisfies` 의 정의

> `expression satisfies T` 는 expression 의 타입이 T 의 부분타입(assignable to T)인지 검사하되, expression 의 *추론된 타입을 그대로 유지* 한다.

```ts
type ColorTable = Record<string, `#${string}`>;

const colors = {
  red: "#FF0000",
  green: "#00FF00",
  blue: "#0000FF",
} satisfies ColorTable;

colors.red.toUpperCase();   // OK
colors.purple;              // 에러
const r = colors.red;       // r: "#FF0000"  ← literal 보존
```

## 3. `: T` vs `as T` vs `satisfies T`

| 연산 | inference 보존 | 제약 검사 | 안전성 |
|---|---|---|---|
| `: T` (annotation) | ❌ T 로 widening | ✅ | ✅ |
| `as T` (assertion) | ❌ 강제 캐스팅 | ❌ 부분적 | ❌ unsafe |
| `satisfies T` | ✅ | ✅ | ✅ |

```ts
type Point = { x: number; y: number };

const p1 = { x: 1, y: 2, z: 3 } satisfies Point;
// 에러: 'z' does not exist in Point. excess property check 가 활성

const p2 = { x: 1, y: 2, z: 3 } as Point;
// 컴파일 통과(unsafe). z 는 사라진 것처럼 보이지만 런타임에는 존재
```

## 4. `as const` + `satisfies` 의 조합

```ts
type Route = {
  path: string;
  method: "GET" | "POST" | "PUT" | "DELETE";
  auth: boolean;
};

const routes = {
  listOrders: { path: "/orders", method: "GET", auth: true },
  createOrder: { path: "/orders", method: "POST", auth: true },
  health: { path: "/health", method: "GET", auth: false },
} as const satisfies Record<string, Route>;

type RouteName = keyof typeof routes;
type HealthPath = typeof routes.health.path;  // "/health"
```

이 패턴이 *type-safe route table* 의 기본 공식이다.

## 5. narrowing 과의 상호작용

```ts
type Event =
  | { kind: "click"; x: number; y: number }
  | { kind: "scroll"; delta: number };

function dispatch<E extends Event>(e: E): E {
  return e;
}

const result = dispatch({
  kind: "scroll",
  delta: 100,
} satisfies Event);
// result: { kind: "scroll"; delta: number } ← literal 유지
```

## 6. 함수 리턴타입에 `satisfies` 적용

```ts
type ApiResponse<T> = {
  ok: boolean;
  data: T;
  errors?: string[];
};

function loadUser(id: string) {
  return {
    ok: true,
    data: { id, name: "Hong", roles: ["admin"] },
  } satisfies ApiResponse<{ id: string; name: string; roles: string[] }>;
}

const u = loadUser("u1");
u.data.roles[0];  // OK
u.errors;         // optional 그대로
```

## 7. 실제 라이브러리 사례

### 7.1 Drizzle ORM

```ts
import { pgTable, serial, varchar, boolean } from "drizzle-orm/pg-core";

export const users = pgTable("users", {
  id: serial("id").primaryKey(),
  name: varchar("name", { length: 255 }).notNull(),
  active: boolean("active").default(true),
}) satisfies Record<string, unknown>;

type User = typeof users.$inferSelect;
type NewUser = typeof users.$inferInsert;
```

### 7.2 tRPC v11 router

```ts
import { initTRPC } from "@trpc/server";
import { z } from "zod";

const t = initTRPC.create();

export const appRouter = t.router({
  ping: t.procedure.query(() => "pong"),
  user: {
    list: t.procedure.query(() => []),
    byId: t.procedure
      .input(z.object({ id: z.string() }))
      .query(({ input }) => ({ id: input.id, name: "Hong" })),
  },
}) satisfies { _def: { record: object } };

export type AppRouter = typeof appRouter;
```

### 7.3 환경변수 schema

```ts
const env = {
  DATABASE_URL: process.env.DATABASE_URL!,
  REDIS_URL: process.env.REDIS_URL!,
  NODE_ENV: process.env.NODE_ENV ?? "development",
} satisfies Record<string, string>;
```

## 8. excess property check 의 함정

`satisfies` 는 excess property check 를 *활성화* 한다. 객체 리터럴이 아닐 때는 동작이 다르다.

```ts
type Point = { x: number; y: number };

const base = { x: 1, y: 2, z: 3 };
const p = base satisfies Point;
// 통과한다. base 가 식별 가능한 객체 리터럴이 아니므로
// excess property check 가 우회된다.

const p2 = { x: 1, y: 2, z: 3 } satisfies Point;
// 에러: 리터럴이므로 excess check 적용
```

*반드시 inline literal 에 직접* `satisfies` 를 붙이는 게 안전하다.

## 9. 디스크리미네이티드 유니온 강제

```ts
type Action =
  | { kind: "LOAD"; userId: string }
  | { kind: "SAVE"; payload: object }
  | { kind: "CLEAR" };

const actions = {
  LOAD: (userId: string): Action => ({ kind: "LOAD", userId }),
  SAVE: (payload: object): Action => ({ kind: "SAVE", payload }),
  CLEAR: (): Action => ({ kind: "CLEAR" }),
} satisfies Record<Action["kind"], (...args: never[]) => Action>;
```

`Record<Action["kind"], ...>` 가 *모든 variant 이름이 빠짐없이* 매핑되어야 함을 강제한다. exhaustiveness 컴파일 보장.

## 10. 한계와 안 쓰는 게 나은 경우

1. **재귀 타입과의 결합** — TS 컴파일러가 `satisfies` 의 reverse-mapping 을 잘 못 따라가는 케이스가 있다. complex conditional 타입은 `: T` 가 더 안정.
2. **JSON.parse 결과** — 런타임 검증이 빠진 채 컴파일 타임 신뢰. Zod 같은 runtime validator 가 필요한 경계.
3. **단순 const enum** — `as const` 한 줄로 충분한 경우 굳이 `satisfies` 까지 안 붙여도 된다.

## 참고

- TypeScript Handbook: The `satisfies` operator — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html#the-satisfies-operator
- TS 5.0 Release Notes — https://devblogs.microsoft.com/typescript/announcing-typescript-5-0/
- Total TypeScript: When to use satisfies — https://www.totaltypescript.com/clarifying-the-satisfies-operator
- Drizzle ORM Docs — https://orm.drizzle.team/docs/sql-schema-declaration
- tRPC Docs — https://trpc.io/docs/server/routers
