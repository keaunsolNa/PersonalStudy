Notion 원본: https://app.notion.com/p/3fc5a06fd6d381bb9805ebdc774b57d8?pvs=204

# TypeScript satisfies 연산자와 const 타입 파라미터 및 리터럴 추론 widening 규칙

> 2026-10-17 신규 주제 · 확장 대상: TypeScript(타입 추론과 제네릭)

## 학습 목표

- 변수 선언 방식별로 리터럴 타입이 widening 되는 시점과 보존되는 시점을 구분한다
- satisfies 연산자로 타입 검증과 추론 보존을 동시에 얻는 설정 객체를 작성한다
- const 타입 파라미터로 호출 지점의 리터럴과 튜플 추론을 제어한다
- as const, 타입 주석, satisfies, const 타입 파라미터 중 상황에 맞는 도구를 고른다

## 1. 문제의 출발점: 검증과 추론 사이의 양자택일

TypeScript에서 설정 객체나 라우트 테이블 같은 값을 정의할 때 오랜동안 개발자는 두 가지 선택지 사이에서 타협해야 했다. 변수에 타입 주석을 붙이면 오타나 누락 키를 컴파일러가 잡아 주지만, 변수의 타입이 주석에 적힌 넓은 타입으로 고정되어 값이 가진 구체적인 정보가 사라진다. 반대로 주석을 생략하면 추론된 정밀한 타입을 쓸 수 있지만 잘못된 키를 넣어도 아무도 알려 주지 않는다. 다음 예제는 색상 팔레트를 `string | number[]` 값을 가진 레코드로 정의할 때 이 문제가 어떻게 나타나는지 보여 준다.

```ts
type Palette = Record<"red" | "green" | "blue", string | number[]>;

// 주석 방식: 검증은 되지만 각 값이 string | number[] 로 넓어진다
const annotated: Palette = {
  red: [255, 0, 0],
  green: "#00ff00",
  blue: [0, 0, 255],
};

// @ts-expect-error string | number[] 에는 map 이 공통으로 존재하지 않는다
annotated.red.map((n) => n * 2);

// 주석 생략: 정밀하지만 키 오타를 잡지 못한다
const loose = { red: [255, 0, 0], gren: "#00ff00", blue: [0, 0, 255] };
loose.red.map((n) => n * 2);
```

`annotated.red`는 실제로는 배열이지만 컴파일러는 문자열일 가능성을 남겨 두기 때문에 분기 처리나 단언이 필요하다. `loose`는 `gren`이라는 오타가 있어도 통과한다. TypeScript 4.9(2022년 11월)에 도입된 `satisfies` 연산자는 정확히 이 간극을 메우기 위해 만들어졌고, 5.0(2023년 3월)의 `const` 타입 파라미터는 같은 사상을 제네릭 함수의 호출 지점으로 확장한다. 이 두 기능을 제대로 쓰려면 먼저 컴파일러가 리터럴을 언제 넓히는지, 즉 widening 규칙을 알아야 한다.

## 2. 리터럴 타입과 widening의 기본 규칙

`"hi"`, `42`, `true` 같은 리터럴 표현식은 문맥에 따라 두 종류의 타입을 가진다. 표현식이 처음 만들어질 때는 fresh 리터럴 타입이며, 이는 widening 가능한 리터럴 타입이라고도 부른다. 이 타입이 변경 가능한 위치에 저장되면 대응하는 기본 타입(string, number, boolean)으로 넓어진다. 핵심 규칙은 위치가 재할당 가능한가 하는 점이다. `const` 변수는 다시 대입될 수 없으므로 리터럴 타입을 유지하고, `let` 변수와 객체 프로퍼티, 배열 요소는 변경될 수 있으므로 넓어진다. 아래 코드는 `Equal` 헬퍼로 타입을 정확히 비교해 이 규칙을 컴파일 시점에 증명한다.

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

let a = "hi";
const b = "hi";
const o = { mode: "dark" };
const arr = ["a", 1];
const o2 = { mode: "dark" } as const;
const tup = ["a", 1] as const;

const t1: Equal<typeof a, string> = true;
const t2: Equal<typeof b, "hi"> = true;
const t3: Equal<typeof o, { mode: string }> = true;
const t4: Equal<typeof arr, (string | number)[]> = true;
const t5: Equal<typeof o2, { readonly mode: "dark" }> = true;
const t6: Equal<typeof tup, readonly ["a", 1]> = true;
console.log(t1, t2, t3, t4, t5, t6);
```

여기서 흥미로운 지점은 `const b = "hi"`의 타입이 `"hi"`라는 사실보다, 이 리터럴이 여전히 widening 가능한 상태라는 점이다. `const b = "hi"; let c = b;`로 쓰면 `c`는 `string`이 된다. 선언 시점의 `const` 초기화는 리터럴을 보존하지만 그 값을 다시 `let`에 복사하면 widening이 일어난다. 반면 `as const`나 명시적 타입 주석 `const b: "hi" = "hi"`로 만들어진 non-widening 리터럴은 `let`에 복사해도 `"hi"`를 유지한다. 객체 리터럴의 프로퍼티가 넓어지는 이유도 같다. `{ mode: "dark" }`의 `mode`는 나중에 `o.mode = "light"`로 바뀔 수 있어야 하므로 `string`이 되어야 한다는 것이 설계 의도다.

| 선언 형태 | 추론 결과 | 이유 |
| --- | --- | --- |
| `let x = "hi"` | `string` | 재할당 가능한 위치이므로 widening |
| `const x = "hi"` | `"hi"` | 재할당 불가, widening 가능한 리터럴 유지 |
| `const o = { k: "v" }` | `{ k: string }` | 프로퍼티는 변경 가능하므로 widening |
| `const o = { k: "v" } as const` | `{ readonly k: "v" }` | const 단언으로 non-widening, readonly 부여 |
| `const a = [1, 2]` | `number[]` | 요소 위치는 변경 가능 |
| `const a = [1, 2] as const` | `readonly [1, 2]` | 튜플로 추론하고 readonly 부여 |

## 3. as const의 능력과 한계

`as const`(TypeScript 3.4)는 표현식 전체에 대해 widening을 끄고, 객체 프로퍼티와 튜플을 모두 `readonly`로 만든다. 값의 구체적 정보를 타입으로 끌어올려 `typeof` 와 인덱스 접근 타입으로 유니온을 파생하는 패턴에서 특히 유용하다. 다음 코드는 한 곳의 상수 정의에서 유니온 타입을 뽑아내는 대표 패턴이다.

```ts
const ROLES = ["admin", "editor", "viewer"] as const;
type Role = (typeof ROLES)[number];

function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

const r: Role = "admin";
// @ts-expect-error "owner" 는 Role 에 속하지 않는다
const bad: Role = "owner";
console.log(r, isRole("editor"), bad);
```

`as const`의 한계는 검증 기능이 전혀 없다는 점이다. 키 이름이 틀려도, 값의 형태가 요구 사항과 달라도 그대로 통과한다. 또한 `readonly` 가 전파되기 때문에 변경 가능한 배열을 요구하는 API에 넘기면 오류가 난다. `as const` 의 결과를 `string[]` 파라미터에 전달하면 `readonly ["a", "b"]` 는 `string[]` 에 대입할 수 없다는 메시지를 만난다. 이 경우 함수의 파라미터를 `readonly string[]` 로 바꾸는 것이 올바른 해법이며, 단언으로 readonly 를 벗기는 것은 안전하지 않다. 정리하면 `as const`는 정밀한 추론을 주지만 검증을 주지 않고, 타입 주석은 검증을 주지만 정밀도를 빼앗는다.

## 4. satisfies 연산자: 검증하되 추론은 지킨다

`expr satisfies T`는 `expr`이 `T`에 대입 가능한지 검사하되, 결과 타입은 `T`가 아니라 `expr`에서 추론된 타입 그대로 둔다. 두 가지 효과가 동시에 일어난다. 첫째, `T`는 `expr`의 문맥 타입(contextual type)이 되므로 콜백 파라미터가 자동으로 타입을 얻고 리터럴이 `T`에 포함된 리터럴로 보존된다. 둘째, 초과 프로퍼티 검사가 수행되어 오타 키가 잡힌다. 1절의 팔레트 문제는 다음과 같이 풀린다.

```ts
type Palette = Record<"red" | "green" | "blue", string | number[]>;

const palette = {
  red: [255, 0, 0],
  green: "#00ff00",
  blue: [0, 0, 255],
} satisfies Palette;

// 각 키의 추론 타입이 보존되므로 분기 없이 사용할 수 있다
const doubled = palette.red.map((n) => n * 2);
const upper = palette.green.toUpperCase();

// @ts-expect-error gren 은 Palette 의 키가 아니므로 검증 단계에서 실패한다
const typo = { red: [1], gren: "x", blue: [0] } satisfies Palette;
console.log(doubled, upper, typo);
```

콜백이 포함된 객체에서는 문맥 타입 효과가 더 분명해진다. 아래 핸들러 테이블에서 `e`와 `k`는 선언 어디에도 타입이 적혀 있지 않지만 `satisfies` 덕분에 각각 이벤트 객체와 문자열로 추론되고, 반환 타입은 선언한 대로 유지된다.

```ts
type Handlers = {
  onClick: (e: { x: number; y: number }) => void;
  onKey: (k: string) => void;
};

const handlers = {
  onClick: (e) => console.log(e.x + e.y),
  onKey: (k) => k.length,
} satisfies Handlers;

// onKey 의 실제 반환 타입 number 가 void 로 지워지지 않는다
const len: number = handlers.onKey("abc");
console.log(len);
```

리터럴 보존 규칙도 중요하다. `T`의 해당 위치가 리터럴 유니온이면 값은 그 리터럴로 유지되지만, `string` 같은 넓은 타입이면 평소처럼 widening 된다. 즉 satisfies는 "제약에 리터럴이 있으면 보존한다"는 조건부 동작이므로, 무조건 리터럴을 얻고 싶다면 `as const`를 함께 써야 한다.

```ts
type Theme = { mode: "dark" | "light"; name: string };

const t1 = { mode: "dark", name: "ocean" } satisfies Theme;
const m1: "dark" = t1.mode; // 리터럴 보존
const n1: string = t1.name; // 제약이 string 이므로 widening

let v = "a" satisfies string; // let 은 여전히 넓어진다
v = "b";

const t2 = { mode: "dark", name: "ocean" } as const satisfies Theme;
const n2: "ocean" = t2.name; // as const 가 name 까지 보존
console.log(m1, n1, v, n2);
```

`as const satisfies T` 조합에서는 `T`가 변경 가능한 배열이어도 오류가 나지 않는다. 컴파일러는 제약이 mutable 배열이면 readonly 가 아닌 변경 가능한 튜플로 추론하며, 아래 코드에서 `Equal`로 두 경우의 결과 타입이 다름을 확인할 수 있다(TypeScript 5.6.3과 6.0.3에서 확인).

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

const mutableCtx = ["a", "b"] as const satisfies string[];
const readonlyCtx = ["a", "b"] as const satisfies readonly string[];

const c1: Equal<typeof mutableCtx, ["a", "b"]> = true;
const c2: Equal<typeof readonlyCtx, readonly ["a", "b"]> = true;
console.log(c1, c2);
```

따라서 불변성을 보장하고 싶다면 제약 쪽을 `readonly string[]`로 적어야 한다. 제약을 `string[]`로 쓰면 값은 `as const`를 붙였는데도 변경 가능한 튜플이 되어 의도와 다른 타입이 노출된다.

## 5. 타입 단언, 타입 주석, satisfies의 차이

세 도구는 모두 "이 값은 이 타입이다"라는 정보를 컴파일러에 주지만 컴파일러가 하는 일이 다르다. 타입 주석 `const x: T = expr`은 변수의 타입을 `T`로 선언하고 `expr`을 `T`에 대해 검사한다. 단언 `expr as T`는 검사를 완화해서 `expr`의 타입과 `T`가 충분히 겹치기만 하면 통과시키고 결과 타입을 `T`로 바꾼다. 따라서 필수 키가 빠진 객체도 단언으로는 통과한다. `satisfies`는 주석처럼 엄격히 검사하면서 결과 타입은 바꾸지 않는다.

```ts
type User = { id: number; name: string };

// 단언: 필수 프로퍼티 name 이 빠졌지만 컴파일러가 막지 않는다
const asserted = { id: 1 } as User;

// @ts-expect-error satisfies 는 name 누락을 잡는다
const checked = { id: 1 } satisfies User;

const fine = { id: 1, name: "kim", admin: true } as User;
// 초과 프로퍼티 admin 은 단언에서는 허용되지만 satisfies 에서는 오류다
// @ts-expect-error admin 은 User 에 없다
const excess = { id: 1, name: "kim", admin: true } satisfies User;
console.log(asserted, checked, fine, excess);
```

| 도구 | 검증 강도 | 결과 타입 | 문맥 타입 제공 | 주된 용도 |
| --- | --- | --- | --- | --- |
| `const x: T = e` | 엄격(초과 프로퍼티 포함) | `T` | 예 | 공개 API 경계, 의도적으로 넓은 타입 |
| `e as T` | 느슨(겹침만 확인) | `T` | 예 | 외부 데이터 좋히기, 불가피한 탈출구 |
| `e as const` | 없음 | 가장 좋은 readonly 타입 | 아니오 | 상수 테이블, 유니온 파생 |
| `e satisfies T` | 엄격(초과 프로퍼티 포함) | `e` 의 추론 타입 | 예 | 설정 객체, 라우트, 핸들러 테이블 |

실무 기준을 하나만 꼽자면, 값을 정의하는 위치에서는 `satisfies`를 쓰고 값이 흘러가는 경계(함수 파라미터, 반환 타입, 모듈 export 타입)에서는 명시적 타입을 쓰는 것이다. satisfies 로 만든 객체의 타입은 값에 종속적이어서 값을 수정하면 타입도 바뀌고, 그 변화가 하위 사용처에 조용히 번질 수 있기 때문이다.

## 6. const 타입 파라미터

`satisfies`가 값을 정의하는 위치를 다룬다면, TypeScript 5.0의 `const` 타입 파라미터는 제네릭 함수를 호출하는 위치를 다룬다. 이전에는 호출자가 인자 뒤에 `as const`를 붙여야 리터럴과 튜플 정보가 보존되었다. 함수 작성자가 타입 파라미터 앞에 `const`를 붙이면 컴파일러는 해당 타입 파라미터를 추론할 때 인자 표현식에 `as const`가 적용된 것처럼 동작한다.

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

declare function plain<T extends readonly unknown[]>(x: T): T;
declare function strict<const T extends readonly unknown[]>(x: T): T;

const p = plain(["a", 1, true]);
const s = strict(["a", 1, true]);

const e1: Equal<typeof p, (string | number | boolean)[]> = true;
const e2: Equal<typeof s, readonly ["a", 1, true]> = true;

// 객체에도 동일하게 적용된다
declare function pick<const T extends { kind: string; tags: readonly string[] }>(x: T): T;
const picked = pick({ kind: "post", tags: ["ts", "generic"] });
const e3: Equal<typeof picked, { readonly kind: "post"; readonly tags: readonly ["ts", "generic"] }> = true;
console.log(e1, e2, e3);
```

실제 활용 사례로는 라우트 정의 함수가 있다. 제약 타입에 `method: "GET" | "POST"` 같은 리터럴 유니온이 있으면 일반 제네릭에서도 method는 보존되지만 `path`는 `string`으로 넓어진다. `const` 를 붙이면 경로 문자열까지 리터럴로 남으므로 라우트 이름에서 타입을 파생할 수 있다.

```ts
type Route = { path: string; method: "GET" | "POST" };

function defineRoutes<const T extends readonly Route[]>(routes: T): T {
  return routes;
}

const routes = defineRoutes([
  { path: "/users", method: "GET" },
  { path: "/users", method: "POST" },
  { path: "/health", method: "GET" },
]);

type Path = (typeof routes)[number]["path"];
const p1: Path = "/health";
// @ts-expect-error 정의되지 않은 경로
const p2: Path = "/missing";
console.log(routes.length, p1, p2);
```

주의할 점은 `const` 타입 파라미터가 오직 호출 인자로 직접 쓰인 리터럴 표현식에만 영향을 준다는 것이다. 이미 다른 변수에 담겨 widening 된 값을 넘기면 효과가 없다.

```ts
declare function strict<const T extends readonly unknown[]>(x: T): T;

const names = ["a", "b"]; // string[] 으로 이미 넓어진 값
const viaVar = strict(names); // 그대로 string[] 이다
const direct = strict(["a", "b"]); // readonly ["a", "b"]

const check1: typeof viaVar = ["x", "y", "z"];
// @ts-expect-error direct 는 길이 2 의 리터럴 튜플이다
const check2: typeof direct = ["x", "y", "z"];
console.log(check1, check2);
```

제약을 변경 가능한 배열로 쓰는 경우의 동작은 버전에 따라 달라진 영역이다. 아래 코드가 보여 주듯 TypeScript 5.6.3과 6.0.3에서는 `<const T extends unknown[]>`로 선언해도 호출 인자가 변경 가능한 튜플 `["a", "b"]`로 추론된다. 반면 readonly 제약은 `readonly ["a", "b"]`를 만든다. 어느 쪽이든 의도한 불변성은 제약 타입의 readonly 여부가 결정하므로, 라이브러리 시그니처에서는 `readonly unknown[]`을 기본으로 쓰고 호출 측이 변경을 필요로 할 때만 mutable 제약을 쓰는 것이 일관적이다.

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

declare function mutableConst<const T extends unknown[]>(x: T): T;
declare function readonlyConst<const T extends readonly unknown[]>(x: T): T;

const m = mutableConst(["a", "b"]);
const r = readonlyConst(["a", "b"]);

const k1: Equal<typeof m, ["a", "b"]> = true;
const k2: Equal<typeof r, readonly ["a", "b"]> = true;
console.log(k1, k2);
```

## 7. 두 기능의 결합: 타입 안전한 설정 정의 함수

`satisfies`와 `const` 타입 파라미터는 서로 대체 관계가 아니라 적용 위치가 다르다. 값을 변수에 직접 담을 때는 `satisfies`, 함수 호출을 통해 값을 등록할 때는 `const` 타입 파라미터가 알맞다. 두 가지를 같이 쓰면 설정 정의 헬퍼가 검증, 자동완성, 리터럴 키 파생을 모두 제공한다. 아래는 이벤트 이름과 페이로드 타입을 한 번에 선언하는 예다.

```ts
type EventSpec = Record<string, { readonly fields: readonly string[] }>;

function defineEvents<const T extends EventSpec>(spec: T): T {
  return spec;
}

const events = defineEvents({
  login: { fields: ["userId", "at"] },
  logout: { fields: ["userId"] },
});

type EventName = keyof typeof events;
type FieldOf<E extends EventName> = (typeof events)[E]["fields"][number];

function emit<E extends EventName>(name: E, payload: Record<FieldOf<E>, string>): string {
  return `${name}:${JSON.stringify(payload)}`;
}

console.log(emit("login", { userId: "u1", at: "2026-10-17" }));
// @ts-expect-error logout 의 필드는 userId 뿐이다
emit("logout", { userId: "u1", at: "x" });
```

헬퍼 함수는 `T`를 그대로 반환하므로 런타임 비용이 사실상 없다. 반환 타입을 `T`로 두는 것이 핵심이며, 제약 타입 `EventSpec`으로 반환하면 모든 정밀도가 사라진다. 헬퍼를 쓰지 않고 `events` 를 `as const satisfies EventSpec` 으로 선언해도 같은 타입이 나오지만, 헬퍼는 호출 지점에서 `as const`를 잊는 실수를 구조적으로 막아 준다는 장점이 있다. 라이브러리 작성자 입장에서는 사용자가 `as const`를 기억하지 않아도 되는 API를 제공할 수 있다는 점이 const 타입 파라미터의 가장 큰 가치다.

## 8. 실무 판단 기준과 함정 모음

도구 선택은 세 가지 질문으로 정리된다. 첫째, 값의 구체적 타입이 이후 코드에서 필요한가. 필요하지 않다면 타입 주석이 더 단순하고 의도가 분명하다. 둘째, 값이 변수에 직접 정의되는가, 함수 호출 인자로 전달되는가. 전자는 satisfies, 후자는 const 타입 파라미터다. 셋째, 불변 값을 원하는가. 그렇다면 `as const`, 혹은 `as const satisfies`를 쓴다.

흔한 함정은 다음과 같다. satisfies 는 변수의 선언 타입을 바꾸지 못하므로 `let` 변수나 이후 재할당이 있는 경우 widening 이 그대로 일어난다. 객체를 함수 반환값으로 내보낼 때는 `satisfies` 로 얻은 정밀 타입이 공개 API 의 일부가 되어 구현 변경이 곧 타입 변경이 된다. `const` 타입 파라미터로 받은 튜플은 `readonly` 이므로 내부에서 `push` 같은 변경 연산을 하려면 복사해야 한다. 마지막으로 큰 객체 리터럴에 `satisfies` 와 복잡한 조건부 타입을 함께 쓰면 오류 메시지가 길어지고 편집기 응답이 느려질 수 있으므로, 제약 타입을 별도의 이름 있는 타입으로 분리해 오류 위치를 좁히는 편이 좋다.

| 상황 | 권장 도구 | 근거 |
| --- | --- | --- |
| 환경별 설정 객체, 각 키의 값 타입이 다름 | `satisfies Record<K, V>` | 키 오타 검출과 값별 정밀 타입 동시 확보 |
| 상수 목록에서 유니온 파생 | `as const` | 검증이 필요 없고 readonly 튜플이 이득 |
| 라우트, 이벤트 등록 헬퍼 | `<const T extends ...>` | 호출자가 `as const` 를 쓰지 않아도 됨 |
| 모듈 공개 API 의 파라미터와 반환 | 명시적 타입 주석 | 구현 변경이 타입에 새지 않도록 경계 고정 |
| 외부 JSON 을 좋히는 경우 | 런타임 검증 후 타입 가드 | 단언과 satisfies 모두 런타임 보장은 없음 |

세 기능 모두 컴파일 시점의 정보만 다루며 생성되는 JavaScript 는 달라지지 않는다. `satisfies` 와 `as const` 는 출력에서 완전히 사라지고, `const` 타입 파라미터는 타입 소거 대상이다. 따라서 도입에 따른 번들 크기나 실행 속도 변화는 없고, 비용은 컴파일러의 타입 검사 시간과 이해 비용에 한정된다. 기존 코드에서 `as Foo` 로 우회하던 곳을 satisfies 로 바꾸면 누락 키 오류를 새로 발견하는 경우가 많으므로, 바꾼 직후에는 컴파일 오류가 늘어나는 것이 정상이다.

## 참고

- TypeScript 4.9 릴리스 노트, The satisfies Operator: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html
- TypeScript 5.0 릴리스 노트, const Type Parameters: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript 3.4 릴리스 노트, const assertions: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-4.html
- TypeScript Handbook, Everyday Types와 Type Inference: https://www.typescriptlang.org/docs/handbook/2/everyday-types.html
- Effective TypeScript 2nd Edition, Dan Vanderkam, O'Reilly (추론과 widening 관련 항목): https://effectivetypescript.com/
