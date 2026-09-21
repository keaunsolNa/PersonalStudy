Notion 원본: https://www.notion.so/3e25a06fd6d3812c88b7d075e54cff02

# TypeScript satisfies 연산자와 const 타입 파라미터 및 리터럴 추론 폭 제어

> 2026-09-21 신규 주제 · 확장 대상: TypeScript, Javascript

## 학습 목표

- 리터럴 타입의 widening 규칙을 fresh/regular 구분으로 설명하고 의도한 지점에서 차단한다
- `satisfies` 와 타입 애너테이션·`as const` 의 검사 시점 차이를 근거로 셋 중 하나를 선택한다
- `const` 타입 파라미터(TS 5.0)를 제네릭 API 에 적용해 호출부 `as const` 를 제거한다
- 설정 객체·라우트 테이블·상태 머신에서 키 유니온을 잃지 않는 시그니처를 설계한다

## 1. Widening: 추론이 값을 놓아주는 순간

TypeScript 의 추론은 "값이 앞으로 바뀔 수 있는가"를 기준으로 타입을 넓힌다. `let x = "a"` 는 `string`, `const x = "a"` 는 `"a"` 가 되는 것이 그 결과다. 내부적으로 이 동작은 **fresh literal type** 과 **regular literal type** 의 구분으로 구현돼 있다. 리터럴 표현식이 만들어 낸 타입은 "신선한(fresh)" 상태로 태그되고, 신선한 리터럴 타입은 mutable 위치(변경 가능한 변수, 변경 가능한 프로퍼티)에 대입될 때 base primitive 로 넓어진다.

```ts
const a = "GET";          // "GET" (fresh, const 바인딩이라 유지)
let b = "GET";            // string (fresh → mutable 위치 → widen)
const c = { m: "GET" };   // { m: string }  ← 프로퍼티가 mutable 이므로 widen
const d = { m: "GET" } as const; // { readonly m: "GET" }
```

`c` 가 `{ m: string }` 이 되는 것이 실무에서 가장 많이 부딪히는 지점이다. 객체 리터럴의 프로퍼티는 재대입 가능하므로 "이 값이 계속 `"GET"` 일 것"이라는 보장이 없고, 컴파일러는 보수적으로 넓힌다.

핵심은 **widening 이 대입의 순간에 일어난다**는 점이다. 따라서 타입을 좁게 유지하려면 (1) 대입 대상이 readonly 이거나(`as const`), (2) 대입 대상의 기대 타입이 리터럴 유니온이어서 contextual type 이 신선함을 붙잡아 두거나, (3) 제네릭 추론 자리에 `const` 수식자가 붙어 있어야 한다.

| 기법 | 넓힘 차단 | 초과 프로퍼티 검사 | 결과 타입 | readonly 강제 |
| --- | --- | --- | --- | --- |
| 애너테이션 `: T` | O(컨텍스트) | O | 선언한 `T` | X |
| `as const` | O | X | 리터럴 + readonly | O |
| `satisfies T` | O(컨텍스트) | O | 추론된 좁은 타입 | X |
| `as const satisfies T` | O | O | 리터럴 + readonly | O |
| `const` 타입 파라미터 | O(호출부) | O | 리터럴 | 부분적 |

## 2. 애너테이션이 잃는 것 — 하향 정보 손실

애너테이션은 검사와 동시에 **타입을 덮어쓴다**. 그래서 검사는 통과하지만 이후 사용에서 정보가 사라진다.

```ts
type RouteTable = Record<string, { method: "GET" | "POST"; auth: boolean }>;

const routes: RouteTable = {
  listUsers: { method: "GET", auth: false },
  createUser: { method: "POST", auth: true },
};

routes.listUser; // 오타인데 오류 없음 — 인덱스 시그니처라 any 아닌 값 타입 반환
type Keys = keyof typeof routes; // string — 키 유니온 소실
```

`RouteTable` 로 검사하고 싶지만 `keyof` 는 `"listUsers" | "createUser"` 로 남기고 싶다. 이것이 `satisfies` 가 해결하는 정확한 문제다.

```ts
const routes = {
  listUsers: { method: "GET", auth: false },
  createUser: { method: "POST", auth: true },
} satisfies RouteTable;

type Keys = keyof typeof routes;           // "listUsers" | "createUser"
type M = typeof routes.listUsers.method;   // "GET"  ← 리터럴 보존
routes.listUser;                            // 오류: 프로퍼티 없음
```

`satisfies` 는 "이 표현식이 `T` 에 대입 가능한지 검사하되, 표현식의 타입은 추론된 그대로 둔다"는 연산자다. 검사 과정에서 `T` 가 contextual type 으로 작용하므로 `method` 의 `"GET"` 은 넓어지지 않는다. 동시에 초과 프로퍼티 검사도 그대로 적용된다.

## 3. as const 와의 결정 기준

`as const` 는 **어서션**이다. 대상 표현식 전체를 가능한 한 좁고 readonly 로 만들 뿐, 어떤 타입에 맞는지는 검사하지 않는다.

```ts
const config = {
  retries: 3,
  backoff: "exponantial", // 오타 — as const 만으로는 잡힐 수 없다
} as const;
```

반대로 `satisfies` 만 쓰면 배열이 `string[]` 으로 남고 `readonly` 도 붙지 않는다. 튜플 고정이 필요하면 둘을 합친다.

```ts
type Backoff = "fixed" | "exponential";
interface Config {
  retries: number;
  backoff: Backoff;
  stages: readonly number[];
}

const config = {
  retries: 3,
  backoff: "exponential",
  stages: [100, 400, 1600],
} as const satisfies Config;

type Stages = typeof config.stages; // readonly [100, 400, 1600]
```

순서가 중요하다. `as const satisfies T` 는 먼저 리터럴로 고정한 뒤 `T` 대입 가능성을 검사한다. 역순은 파싱은 되지만 의미가 달라지고, `T` 가 mutable 배열을 요구하면 `readonly` 때문에 실패하므로 인터페이스 쪽을 `readonly` 로 맞춰야 한다. 실무에서 `as const satisfies` 조합이 실패하는 1순위 원인이 바로 "인터페이스가 `number[]` 인데 값이 `readonly [100, 400, 1600]`" 이다.

선택 기준을 한 줄로 정리하면 이렇다. **검사만 원하면 애너테이션, 검사 + 좁은 타입 보존이면 `satisfies`, 좁은 타입 보존 + 불변성까지면 `as const satisfies`.** 함수 매개변수처럼 이미 contextual type 이 있는 자리는 `satisfies` 가 불필요하다.

## 4. 판별 유니온과 satisfies 의 상호작용

`satisfies` 는 판별 유니온(discriminated union)에서 특히 효과가 크다. 애너테이션을 쓰면 유니온 전체 타입이 되어 판별자가 좁혀지지 않는다.

```ts
type Action =
  | { type: "add"; payload: number }
  | { type: "reset" };

const a1: Action = { type: "add", payload: 1 };
a1.payload; // 오류: "reset" 분기에 payload 없음

const a2 = { type: "add", payload: 1 } satisfies Action;
a2.payload; // number — 정확히 "add" 분기로 추론
```

리듀서 맵을 만들 때 이 차이가 누적된다.

```ts
type Handlers = { [K in Action["type"]]: (a: Extract<Action, { type: K }>) => void };

const handlers = {
  add: (a) => console.log(a.payload),  // a: { type: "add"; payload: number }
  reset: () => {},
} satisfies Handlers;

// 핸들러 누락 시 즉시 오류, 동시에 typeof handlers 는 정확한 함수 시그니처 유지
```

`satisfies Handlers` 가 contextual type 을 제공하므로 화살표 함수의 `a` 가 애너테이션 없이 추론된다. 이것이 "검사는 위에서, 타입은 아래에서" 라는 `satisfies` 의 설계 의도다.

## 5. const 타입 파라미터 — 호출부의 as const 를 제거

라이브러리를 만들 때 사용자에게 `as const` 를 요구하는 것은 나쁜 API 다. 잊어버리면 조용히 넓어지고, 에러도 호출부가 아니라 한참 뒤에 난다. TS 5.0 의 `const` 타입 파라미터가 이를 선언부로 옮긴다.

```ts
// Before: 호출부가 as const 를 써야 함
function pick<T extends readonly string[]>(keys: T): T { return keys; }
const p1 = pick(["id", "name"]);            // string[]
const p2 = pick(["id", "name"] as const);   // readonly ["id", "name"]

// After: 선언부에 const
function pickC<const T extends readonly string[]>(keys: T): T { return keys; }
const p3 = pickC(["id", "name"]);           // readonly ["id", "name"]
```

`const` 수식자는 "이 타입 파라미터로 추론할 때, 인자 표현식을 `as const` 로 쓴 것처럼 취급하라"는 지시다. 주의할 제약이 세 가지 있다.

첫째, **제약(constraint)이 mutable 이면 효과가 사라진다**. `<const T extends string[]>` 는 `readonly` 튜플을 만들 수 없으므로 컴파일러가 넓힘을 되돌린다. 제약은 반드시 `readonly ...[]` 또는 제약 없음이어야 한다.

둘째, **변수를 거쳐 전달하면 적용되지 않는다**. `const` 수식자는 인자 위치의 리터럴 표현식에만 작용한다.

```ts
const keys = ["id", "name"];
pickC(keys); // string[] — 이미 widen 된 변수를 받았으므로 복구 불가
```

셋째, **런타임 불변성은 없다**. 타입 수준 추론만 바뀌고 `Object.freeze` 같은 효과는 전혀 없다. `readonly` 표기는 컴파일러 계약일 뿐이다.

## 6. 실전 패턴: 타입 안전 설정 빌더

세 기법을 합쳤 자주 쓰는 형태를 만들어 보자. 요구는 "이벤트 이름 유니온을 유지하면서, 핸들러 시그니처는 페이로드 스키마에서 자동 도출" 이다.

```ts
type EventSchema = Record<string, Record<string, unknown>>;

function defineEvents<const S extends EventSchema>(schema: S) {
  return {
    schema,
    on<K extends keyof S>(name: K, fn: (payload: S[K]) => void) { /* ... */ },
    emit<K extends keyof S>(name: K, payload: S[K]) { /* ... */ },
  };
}

const bus = defineEvents({
  "user.created": { id: 0, email: "" },
  "order.paid": { orderId: "", amount: 0 },
});

bus.emit("user.created", { id: 1, email: "a@b.c" }); // OK
bus.emit("user.crated", { id: 1, email: "" });       // 오류: 이름 오타
bus.emit("order.paid", { orderId: "x" });            // 오류: amount 누락
```

`const S` 덕분에 호출부가 `as const` 없이도 `"user.created" | "order.paid"` 유니온을 얻는다. 스키마 값으로 "샘플 객체"를 쓰는 것이 어색하다면 브랜드 헬퍼를 두는 편이 낫다.

```ts
const t = <T>() => null as unknown as T;

const bus2 = defineEvents({
  "user.created": t<{ id: number; email: string }>(),
  "order.paid": t<{ orderId: string; amount: number }>(),
});
```

이 형태는 런타임 비용이 0 이고(값은 `null`), 타입만 실어 나른다. 런타임 검증까지 필요하면 Zod 스키마를 값으로 두고 `z.infer` 로 페이로드를 뽑는 쪽이 정석이다.

## 7. 추론 폭 제어가 실패하는 지점들

**배열 메서드를 거치면 유니온이 넓어진다.** `as const` 로 만든 튜플에 `.map()` 을 쓰면 결과는 튜플이 아니라 배열이고, 원소 타입은 유니온으로 합쳐진다. 튜플 매핑이 필요하면 매핑된 튜플 타입을 직접 써야 한다.

```ts
const xs = [1, "a", true] as const;
const ys = xs.map((x) => x);      // (1 | "a" | true)[] — 위치 정보 소실

type MapTuple<T extends readonly unknown[]> = { [K in keyof T]: T[K] };
```

**중첩 제네릭 경계에서 신선함이 사라진다.** `const` 타입 파라미터는 한 단계 호출에만 작용한다. 래퍼 함수가 인자를 받아 다시 넘기면 이미 넓어진 값이 전달되므로, 래퍼에도 `const` 를 달아 릴레이해야 한다.

```ts
function wrap<const T extends readonly string[]>(keys: T) { return pickC(keys); }
```

**조건부 타입의 분배가 리터럴을 쪼갠다.** `T extends string ? ... : ...` 형태는 유니온을 각 멤버별로 분배한다. 의도한 동작이 아니면 `[T] extends [string]` 으로 튜플 래핑해 분배를 끈다. `satisfies` 로 유지한 좁은 유니온이 조건부 타입을 통과하며 예상 밖으로 흩어지는 경우가 여기에 해당한다.

**`Object.keys` 는 여전히 `string[]` 이다.** `satisfies` 로 키 유니온을 유지해도 런타임 API 시그니처는 그대로다. 좁은 키가 필요하면 `Object.keys(o) as (keyof typeof o)[]` 로 단언하거나, 키 목록을 별도 튜플로 선언해 `satisfies` 로 스키마와 교차 검증한다.

```ts
const KEYS = ["id", "name"] as const satisfies readonly (keyof User)[];
// User 에 없는 키를 넣으면 컴파일 오류 — 런타임 배열과 타입이 동시에 보장된다
```

## 8. 성능과 코드베이스 규모의 균형

`as const` 로 고정한 대규모 리터럴(수백 개 원소의 튜플, 깊게 중첩된 설정 객체)은 체커가 만드는 타입 노드 수를 직접 늘린다. `--extendedDiagnostics` 의 Types 카운트와 Check time 이 증가하며, 특히 그 타입이 조건부·매핑 타입을 통과하면 인스턴스화가 곱셈으로 늘어난다.

경험칙은 이렇다. **경계에서만 좁히고, 내부로는 넓은 타입으로 전달한다.** 설정 파일 최상단 객체 하나에 `as const satisfies` 를 걸고, 거기서 파생된 키 유니온(`keyof typeof config`)만 내부 시그니처에 쓴다. 객체 전체를 제네릭으로 돌려가며 매핑하면 타입 인스턴스가 폭증한다.

| 증상 | 원인 후보 | 대응 |
| --- | --- | --- |
| 체크 시간이 특정 파일에서 급증 | 큰 `as const` + 매핑 타입 결합 | 유니온만 추출해 전달 |
| `keyof` 가 `string` 으로 나옴 | 인덱스 시그니처 애너테이션 | `satisfies` 로 교체 |
| 리터럴이 `string` 으로 넓어짐 | mutable 위치 대입 | `as const` 또는 `const` 파라미터 |
| 라이브러리 사용자가 매번 `as const` | 선언부에 `const` 수식자 누락 | `<const T extends readonly ...>` |
| `readonly` 불일치 오류 | 인터페이스가 mutable 배열 요구 | 인터페이스를 `readonly`로 |

마지막으로 `satisfies` 는 **타입 검사 전용이며 출력 JS 에 흔적이 전혀 없다**. `as const` 도 마찬가지다. 따라서 이 절의 모든 기법은 번들 크기와 런타임 성능에 영향을 주지 않으며, 비용은 오직 컴파일 시간에만 나타난다. 반대로 말하면 런타임 검증이 필요한 외부 입력(HTTP 바디, 환경 변수)에는 이 기법들이 아무 보호도 제공하지 않으므로, 경계에는 별도 파서를 두어야 한다.

## 참고

- TypeScript Handbook — Everyday Types / Literal Types
- TypeScript 4.9 Release Notes — The satisfies Operator
- TypeScript 5.0 Release Notes — const Type Parameters
- microsoft/TypeScript PR #46827 — satisfies operator 구현
- microsoft/TypeScript PR #51865 — const type parameters
- TypeScript Wiki — Performance (Preferring Base Types, Type Instantiation)
