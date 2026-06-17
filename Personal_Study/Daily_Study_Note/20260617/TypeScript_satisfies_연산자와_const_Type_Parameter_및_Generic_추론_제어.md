Notion 원본: https://www.notion.so/3825a06fd6d3813d8965d46a3eafc250

# TypeScript satisfies 연산자와 const Type Parameter 및 Generic 추론 제어

> 2026-06-17 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- satisfies가 타입 검증과 리터럴 narrowing을 동시에 달성하는 원리를 설명한다
- as 단언 및 타입 애너테이션과 satisfies의 차이를 구체 예제로 구분한다
- const type parameter(5.0)로 호출 측 widening을 막아 리터럴 추론을 보존한다
- 제네릭 추론 우선순위와 NoInfer(5.4)로 추론 출처를 제어한다

## 1. 문제 — 검증과 narrowing은 서로를 방해한다

객체 리터럴이 어떤 타입을 "만족"하는지 검사하고 싶지만, 동시에 구체적인 리터럴 타입 정보는 잃고 싶지 않을 때가 있다.

```typescript
type Color = [number, number, number] | string;
type Palette = Record<string, Color>;

const paletteA: Palette = { primary: [255, 0, 0], accent: "#00ff00" };
// paletteA.primary 타입이 Color — 배열 메서드 못 씀

const paletteB = { primary: [255, 0, 0] } as Palette; // 검증 우회
```

애너테이션은 변수 타입을 선언 타입으로 넓혀버려 키별 구체 타입을 잃는다. `as`는 컴파일러 검사를 끄는 것에 가까워 안전성을 해친다.

## 2. satisfies — 검증하되 추론 타입은 보존

TypeScript 4.9의 `satisfies` 연산자는 "이 표현식이 타입 T에 할당 가능한지 **검증**하되, 변수의 타입은 **추론된 구체 타입 그대로** 둔다".

```typescript
const palette = {
	primary: [255, 0, 0],
	accent: "#00ff00",
} satisfies Palette;

palette.primary.at(0);        // ✅ number[]
palette.accent.toUpperCase(); // ✅ string
```

핵심: `palette.primary`는 `Color`가 아니라 추론된 `number[]`다. 동시에 `accent`에 잘못된 값을 넣으면 `Palette` 제약 위반으로 컴파일 에러가 난다.

## 3. as / 애너테이션 / satisfies 비교

| 방식 | 타입 검증 | 변수 타입 | 안전성 |
|---|---|---|---|
| `: T` (애너테이션) | O | T (넓어짐) | 안전하나 정보 손실 |
| `as T` (단언) | X (우회) | T | 위험(검사 무력화) |
| `satisfies T` | O | 추론된 구체 타입 | 안전 + 정보 보존 |

```typescript
const routes = {
	home: "/",
	user: "/users/:id",
	post: "/posts/:slug",
} satisfies Record<string, `/${string}`>;

type RouteKey = keyof typeof routes; // "home" | "user" | "post"
```

## 4. const Type Parameter — 호출 측 widening 차단

TypeScript는 제네릭 추론 시 리터럴을 넓힌다(widening). TypeScript 5.0의 **const type parameter**는 이를 함수 정의 측에서 강제한다.

```typescript
function asArray<const T>(items: readonly T[]): readonly T[] {
	return items;
}
const b = asArray(["x", "y"]); // T = "x" | "y" → readonly ["x", "y"]

function defineConfig<const T extends Record<string, unknown>>(config: T): T {
	return config;
}
const cfg = defineConfig({ mode: "dev", port: 3000 });
// cfg: { mode: "dev"; port: 3000 }
```

주의: const type parameter는 *추론* 에만 영향을 주고 실제 값을 readonly로 만들지는 않는다(런타임 불변성은 별개).

## 5. 제네릭 추론 우선순위와 NoInfer

TypeScript 5.4의 `NoInfer<T>` 유틸리티로 특정 위치를 추론 출처에서 제외한다.

```typescript
function createStateSafe<T>(initial: T, allowed: NoInfer<T>[]): T {
	return initial;
}
createStateSafe("idle", ["idle", "loading"]); // T = "idle" 로 고정
// createStateSafe("idle", ["x"]); ❌ "idle" 에 "x" 불가
```

`NoInfer<allowed>`로 두 번째 인자를 추론에서 빼면, `T`는 첫 인자에서만 결정되고 두 번째는 그 타입에 대한 검증으로만 쓰인다.

## 6. 조합 — satisfies + const + NoInfer로 견고한 빌더

```typescript
type EventMap = Record<string, (payload: never) => void>;

function defineEvents<const T extends EventMap>(handlers: T): T {
	return handlers;
}

const events = defineEvents({
	login: (p: { userId: string }) => console.log(p.userId),
	logout: () => console.log("bye"),
});

type EventName = keyof typeof events; // "login" | "logout"

function emit<K extends EventName>(
	name: K,
	payload: Parameters<typeof events[K]>[0],
): void {
	(events[name] as (p: unknown) => void)(payload);
}

emit("login", { userId: "u1" }); // ✅
```

## 7. 함정과 한계

`satisfies`는 *값 표현식* 에만 붙고 런타임에 아무 검사를 하지 않는다(타입 레벨 전용). const type parameter는 추론에만 작용하므로 넓긴 배열이 런타임에 mutable이라는 점은 그대로다 — 불변성이 필요하면 `Object.freeze`를 더한다. `NoInfer`는 5.4+ 에서만 내장이다.

## 8. 정리 — 언제 무엇을 쓰나

리터럴 정보를 보존하면서 형식을 검증하고 싶으면 `satisfies`. 함수 API가 호출자의 리터럴 인자를 그대로 타입으로 받아야 하면 `<const T>`. 제네릭 추론 출처를 특정 인자로 한정하고 나머지는 검증만 하려면 `NoInfer<T>`.

## 9. widening 메커니즘 깊게 보기

`let`은 리터럴을 기본 타입으로 넓히고 `const`는 보존하지만, 이 보존은 **원시값에만** 적용된다 — 객체 프로퍼티와 배열 요소는 `const`로 선언해도 내부가 넓어진다.

```typescript
const obj = { mode: "dev" };        // { mode: string }
const tup = [1, 2, 3] as const;     // readonly [1, 2, 3]
```

`as const`는 값 표현식에 붙이는 단언이고, `<const T>`는 API 제공자가 모든 호출에 일괄 적용하는 추론 정책이다.

## 10. 실전 패턴과 흔한 실수

```typescript
interface ColumnDef {
	type: "string" | "number" | "boolean";
	nullable?: boolean;
}

function defineTable<const T extends Record<string, ColumnDef>>(schema: T): T {
	return schema;
}

const userTable = defineTable({
	id: { type: "number" },
	name: { type: "string", nullable: false },
});

type ColumnName = keyof typeof userTable;        // "id" | "name"
type IdType = typeof userTable["id"]["type"];    // "number"
```

흔한 실수: (1) satisfies와 애너테이션을 함께 쓰면 이점 소멸 — satisfies만, (2) const type parameter가 런타임 불변성을 주지 않음, (3) `NoInfer`는 추론 출처 고정 도구, (4) satisfies가 런타임 검증 대체 아님. satisfies=검증하되 보존, `<const T>`=리터럴 보존, `NoInfer`=출처 지정.

## 참고

- TypeScript 4.9 Release Notes — "The satisfies Operator"
- TypeScript 5.0 Release Notes — "const Type Parameters"
- TypeScript 5.4 Release Notes — "The NoInfer Utility Type"
- TypeScript Handbook — "Type Inference", "Generics"
