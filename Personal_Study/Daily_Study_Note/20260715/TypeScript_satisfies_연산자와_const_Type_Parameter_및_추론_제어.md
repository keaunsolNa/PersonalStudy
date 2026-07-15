Notion 원본: https://app.notion.com/p/39e5a06fd6d381b6a788e6f81f35d988

# TypeScript satisfies 연산자와 const Type Parameter 및 추론 제어

> 2026-07-15 신규 주제 · 확장 대상: TypeScript(Control Flow Analysis / Variance)

## 학습 목표

- 타입 주석 / `as` 단언 / `satisfies` 의 추론 방향과 검증 방향을 구분한다
- 리터럴 위젯닝(widening) 규칙과 `as const` 의 정확한 효과를 설명한다
- `const` 타입 파라미터로 호출 지점의 리터럴 추론을 라이브러리 쪽에서 강제한다
- 설정 객체·라우터 테이블에서 "검증 + 좁은 추론" 을 동시에 얻는 패턴을 구현한다

## 1. 문제: 검증과 추론은 서로를 갉아먹는다

다음 설정 객체를 보자. 요구는 두 가지다. (a) 키가 정해진 형태를 만족하는지 컴파일 타임에 **검증**하고 싶다. (b) 개별 값의 **좁은 타입**은 유지하고 싶다.

```ts
type Config = Record<string, string | number[]>;

const config: Config = {
  host: "localhost",
  ports: [80, 443],
};

config.ports.map(p => p);  // ❌ Property 'map' does not exist on 'string | number[]'
```

타입 주석 `: Config` 를 붙이면 검증은 되지만, `config.ports` 의 타입이 선언 타입인 `string | number[]` 로 **넓어진다**. 값이 실제로 `number[]` 라는 사실이 사라진다.

반대로 주석을 떼면 추론은 좁아지지만 검증이 사라진다.

```ts
const config = {
  host: "localhost",
  ports: [80, 443],
  typo: true,          // Config 위반이지만 아무도 안 잡아줌
};
config.ports.map(p => p);  // ✅ number[]
```

`as Config` 는 최악이다. 검증이 아니라 **단언**이라 잘못된 값도 통과시키고, 추론도 넓어진다.

```ts
const config = { host: "localhost", ports: "80" } as Config;  // 통과. 런타임 폭발
```

## 2. satisfies 의 의미론

`satisfies` (TypeScript 4.9) 는 "이 표현식이 타입 T 에 **할당 가능한지 검사**하되, 결과 타입은 표현식의 추론 타입 그대로 둔다" 는 연산자다.

```ts
const config = {
  host: "localhost",
  ports: [80, 443],
} satisfies Config;

config.host;              // string
config.ports.map(p => p); // ✅ number[] — 좁은 타입 유지
```

세 연산자의 방향을 정리하면 이렇다.

| 문법 | 검증 방향 | 결과 타입 | 잘못된 값 |
|---|---|---|---|
| `const x: T = expr` | expr → T 할당 검사 | `T` (넓어짐) | 에러 |
| `const x = expr as T` | 양방향 겹침 검사만 | `T` | 대부분 통과 |
| `const x = expr satisfies T` | expr → T 할당 검사 | `typeof expr` (좁음) | 에러 |

핵심은 `satisfies` 가 **컨텍스추얼 타입(contextual type)으로도 작동한다**는 점이다. 단순 사후 검사가 아니다.

```ts
type Handlers = Record<string, (req: Request) => Response>;

const handlers = {
  get: (req) => new Response(req.url),   // req 가 Request 로 추론됨
} satisfies Handlers;
```

`req` 에 주석이 없는데도 `Request` 로 추론된다. `satisfies` 의 우변이 좌변 표현식에 컨텍스추얼 타입을 제공하기 때문이다. 이것이 `as` 와 결정적으로 다른 지점이다 — `as` 는 컨텍스추얼 타입을 주지 않으므로 `req` 가 암묵적 `any` 가 된다.

## 3. 위젯닝 규칙 재확인

`satisfies` 를 정확히 쓰려면 리터럴 위젯닝을 알아야 한다. TypeScript 는 **가변 위치**의 리터럴을 넓힌다.

```ts
let a = "hello";           // string     — let 은 재대입 가능 → 넓힘
const b = "hello";         // "hello"    — const 는 불변 → 유지
const c = { k: "hello" };  // { k: string } — 프로퍼티는 가변 → 넓힘
const d = [1, 2];          // number[]   — 배열 요소는 가변 → 넓힘
```

`const c` 자체는 불변이지만 `c.k` 는 재대입 가능하므로 `string` 으로 넓어진다. 이 규칙 때문에 `satisfies` 만으로는 리터럴이 보존되지 않는다.

```ts
type Route = { path: string; method: "GET" | "POST" };

const route = { path: "/users", method: "GET" } satisfies Route;
route.method;  // "GET" ← 어? 왜 좁지?
```

여기선 좁게 나온다. `satisfies Route` 가 컨텍스추얼 타입을 주고, `method` 의 컨텍스추얼 타입이 `"GET" | "POST"` 라는 유니온 리터럴이므로 위젯닝이 **억제**된다. 컨텍스추얼 타입이 리터럴 타입을 포함하면 그 리터럴은 넓어지지 않는다.

반면 컨텍스추얼 타입이 `string` 이면 넓어진다.

```ts
const route2 = { path: "/users", method: "GET" } satisfies { path: string; method: string };
route2.method;  // string
```

즉 `satisfies` 의 결과가 얼마나 좁은지는 **대상 타입이 얼마나 리터럴을 요구하느냐**에 달렸다. 이 상호작용이 실무에서 가장 헷갈리는 부분이다.

## 4. as const 와의 조합

리터럴을 무조건 보존하려면 `as const` 를 함께 쓴다. `as const` 는 이름과 달리 단언이 아니라 **위젯닝 억제 지시자**다. 객체의 모든 프로퍼티를 `readonly` + 리터럴로 고정한다.

```ts
const routes = {
  users: { path: "/users", method: "GET" },
  login: { path: "/login", method: "POST" },
} as const satisfies Record<string, Route>;

type UserPath = typeof routes.users.path;    // "/users"
type Keys = keyof typeof routes;             // "users" | "login"
```

순서가 중요하다. `as const satisfies T` 이지 `satisfies T as const` 가 아니다. 먼저 리터럴로 고정하고, 그 다음 검증한다.

여기서 미묘한 문제가 하나 생긴다. `as const` 는 `readonly` 를 붙이는데, 대상 타입이 가변이면 할당 불가가 된다.

```ts
type Config = { tags: string[] };

const c = { tags: ["a", "b"] } as const satisfies Config;
// ❌ readonly ["a","b"] 는 string[] 에 할당 불가
```

대상 타입을 `readonly string[]` 로 바꾸거나, `Config` 를 아예 readonly 로 설계해야 한다.

```ts
type Config = { readonly tags: readonly string[] };
const c = { tags: ["a", "b"] } as const satisfies Config;  // ✅
```

이 마찰은 우연이 아니다. `as const` 로 얻은 값은 불변이라는 계약을 갖고, 가변 타입에 넣으면 그 계약이 깨진다. TypeScript 의 배열 variance 규칙(가변 배열은 불변, readonly 배열은 공변)이 그대로 드러난 것이다.

## 5. const Type Parameter

`as const` 의 단점은 **호출자가 매번 써야 한다**는 것이다. 라이브러리를 만드는 입장에서 사용자가 `as const` 를 잊으면 추론이 무너진다.

```ts
declare function defineRoutes<T extends Record<string, Route>>(routes: T): T;

const r = defineRoutes({
  users: { path: "/users", method: "GET" },
});
r.users.path;  // string ← 넓어짐. 사용자가 as const 를 안 씀
```

TypeScript 5.0 의 `const` 타입 파라미터는 이 부담을 **선언 쪽으로 옮긴다**.

```ts
declare function defineRoutes<const T extends Record<string, Route>>(routes: T): T;

const r = defineRoutes({
  users: { path: "/users", method: "GET" },
});
r.users.path;  // "/users" ← as const 없이도 좁게 추론
```

`const T` 는 "이 타입 파라미터를 추론할 때, 인자 표현식을 `as const` 가 붙은 것처럼 다뤄라" 는 의미다. 리터럴 위젯닝이 억제되고 배열은 튜플로 추론된다.

```ts
declare function tuple<const T extends readonly unknown[]>(...args: T): T;

const t = tuple(1, "a", true);  // readonly [1, "a", true]
```

제약이 두 가지 있다. 첫째, **인자가 변수면 효과가 없다**. 위젯닝은 리터럴 표현식 지점에서 일어나므로, 이미 넓어진 변수를 넘기면 되돌릴 수 없다.

```ts
const obj = { users: { path: "/users", method: "GET" as const } };
const r2 = defineRoutes(obj);   // obj 는 이미 { path: string, ... }
r2.users.path;                  // string. const T 로도 못 살림
```

둘째, **제약(constraint)이 가변 타입이면 readonly 가 벗겨진다**. `const T extends Route[]` 처럼 가변 배열 제약을 걸면 추론된 readonly 튜플이 제약을 만족하지 못하므로, TypeScript 는 readonly 를 제거한 형태로 추론한다. 제약은 `readonly Route[]` 로 두어야 튜플이 유지된다.

```ts
// ❌ readonly 벗겨짐
declare function f1<const T extends string[]>(a: T): T;
const x1 = f1(["a", "b"]);      // string[]

// ✅ 튜플 유지
declare function f2<const T extends readonly string[]>(a: T): T;
const x2 = f2(["a", "b"]);      // readonly ["a", "b"]
```

## 6. 실전 패턴 — 타입 안전 이벤트 버스

세 도구를 조합해 런타임 값 하나에서 타입 전체를 파생시키는 패턴을 만든다.

```ts
type EventMap = Record<string, (payload: never) => void>;

function createBus<const T extends EventMap>(handlers: T) {
  const map = new Map<keyof T, Function[]>();

  return {
    on<K extends keyof T>(event: K, fn: T[K]) {
      const list = map.get(event) ?? [];
      list.push(fn);
      map.set(event, list);
      return () => {
        const cur = map.get(event) ?? [];
        map.set(event, cur.filter(f => f !== fn));
      };
    },
    emit<K extends keyof T>(event: K, payload: Parameters<T[K]>[0]) {
      for (const fn of map.get(event) ?? []) {
        (fn as T[K])(payload);
      }
    },
  };
}

const bus = createBus({
  "user:login": (p: { id: string }) => {},
  "cart:add": (p: { sku: string; qty: number }) => {},
});

bus.emit("user:login", { id: "u1" });        // ✅
bus.emit("cart:add", { sku: "s1", qty: 2 }); // ✅
bus.emit("user:login", { sku: "x" });        // ❌ 페이로드 불일치
bus.emit("user:logout", {});                 // ❌ 없는 이벤트
```

`EventMap` 의 payload 를 `never` 로 둔 것이 의도적이다. 함수 파라미터는 반공변이므로, 제약을 `(payload: never) => void` 로 두면 **어떤 파라미터 타입의 함수든** 이 제약을 만족한다. `unknown` 으로 두면 반대로 `(p: { id: string }) => void` 가 제약을 만족하지 못해 전부 에러가 난다. 이것이 variance 를 이해해야 제약을 올바르게 쓸 수 있는 대표 사례다.

## 7. satisfies 로 완전성(exhaustiveness) 검사하기

`satisfies` 는 유니온 전체를 다뤘는지 검사하는 데도 쓴다.

```ts
type Status = "pending" | "active" | "closed";

const labels = {
  pending: "대기",
  active: "활성",
  closed: "종료",
} satisfies Record<Status, string>;

labels.pending;              // string
type L = typeof labels;      // { pending: string; active: string; closed: string }
```

`Status` 에 `"archived"` 를 추가하면 `labels` 에서 즉시 에러가 난다. 주석 `: Record<Status, string>` 도 같은 검사를 하지만, 그 경우 `keyof typeof labels` 가 `Status` 가 되어 "실제로 정의된 키" 정보가 사라진다. `satisfies` 는 검사와 정보 보존을 동시에 한다.

부분 매핑을 허용하면서 오타만 잡고 싶다면 `Partial` 을 쓴다.

```ts
const partialLabels = {
  pending: "대기",
  typo: "x",     // ❌ 'typo' 는 Status 에 없음
} satisfies Partial<Record<Status, string>>;
```

## 8. 언제 무엇을 쓰는가

| 상황 | 선택 | 이유 |
|---|---|---|
| 함수 파라미터·리턴 타입 | 타입 주석 | 계약이 문서. 넓은 타입이 오히려 정상 |
| 설정 객체, 상수 테이블 | `as const satisfies T` | 검증 + 리터럴 키/값 보존 |
| 라이브러리 팩토리 함수 | `const` 타입 파라미터 | 호출자에게 `as const` 를 강요하지 않음 |
| 외부 JSON 파싱 결과 | Zod/Valibot 런타임 검증 | 컴파일 타임 도구는 런타임 값을 모름 |
| 타입 시스템이 못 아는 사실 | `as` (최소 범위로) | 어쩔 수 없는 탈출구. 주석으로 근거 명시 |

마지막 행이 중요하다. `satisfies` 는 `as` 를 **대체하지 않는다**. `as` 는 "컴파일러가 모르는 사실을 내가 안다"는 선언이고, `satisfies` 는 "컴파일러가 검사해달라"는 요청이다. DOM 쿼리 결과 같은 곳에서는 여전히 `as` 가 옳다.

```ts
// satisfies 로 대체 불가 — 컴파일러가 알 수 없는 런타임 사실
const canvas = document.getElementById("c") as HTMLCanvasElement;
```

성능 측면의 트레이드오프도 있다. `const` 타입 파라미터와 깊은 `as const` 는 리터럴 타입을 대량 생성한다. 수백 개 엔트리의 라우팅 테이블을 `as const satisfies` 로 고정하면 타입 인스턴스화 수가 크게 늘고, 체크 시간이 증가한다. `tsc --extendedDiagnostics` 의 `Instantiations` 수치가 급증했다면 이 패턴을 적용한 범위를 의심하는 것이 맞다. 좁은 추론이 실제로 필요한 지점(키 자동완성, 페이로드 매핑)에만 쓰고, 값만 필요한 곳은 평범한 주석으로 두는 것이 균형점이다.

## 참고

- TypeScript 4.9 Release Notes — the satisfies operator: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html
- TypeScript 5.0 Release Notes — const Type Parameters: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript Spec Notes — Type Widening and Inference: https://github.com/microsoft/TypeScript/wiki/FAQ
- TypeScript Handbook — Type Inference / Contextual Typing: https://www.typescriptlang.org/docs/handbook/type-inference.html
- Anders Hejlsberg, PR #47920 (satisfies operator design): https://github.com/microsoft/TypeScript/pull/47920
