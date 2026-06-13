Notion 원본: https://www.notion.so/37e5a06fd6d381d0ac59d67d035097f8

# TypeScript Control Flow Analysis 기반 Narrowing과 Assertion Functions · Type Predicate

> 2026-06-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 컴파일러가 코드 경로를 따라 타입을 좁히는 control flow analysis(CFA)의 동작 시점을 예측한다
- typeof·instanceof·in·동등성·판별 속성 등 내장 가드별로 좁혀지는 결과를 구분한다
- 사용자 정의 type predicate(`x is T`)와 assertion function(`asserts x is T`)을 작성하고 둘의 차이를 설명한다
- 클로저·재할당·`let` 변수에서 좁힘이 풀리는 함정을 회피한다

## 1. Control Flow Analysis란

TypeScript는 변수의 선언 타입과 별개로, 실행 경로상 각 지점에서 변수가 가질 수 있는 타입을 따로 계산한다. 이를 control flow analysis라 하며, 분기·반복·return·throw를 따라 타입을 좁히거나 넓힌다. 좁힘은 런타임 검사를 흉내 낼 수 있는 가드 표현식을 만났을 때만 일어난다.

```ts
function f(x: string | number) {
  if (typeof x === "string") {
    x.toUpperCase(); // x: string
  } else {
    x.toFixed(2);    // x: number
  }
}
```

좁힘은 위치 기반이다. 동일 변수라도 블록 안과 밖에서 타입이 다르며, 컴파일러는 노드마다 flow node를 두고 타입을 캐시한다.

## 2. 내장 가드별 좁힘 규칙

`typeof`는 원시 타입에만, `instanceof`는 클래스 인스턴스를, `in`은 속성 존재로 객체 유니온을 좁힌다.

```ts
type Dog = { bark: () => void };
type Cat = { meow: () => void };
function speak(animal: Dog | Cat) {
  if ("bark" in animal) animal.bark(); // Dog
  else animal.meow();                  // Cat
}
```

동등성 비교(`=== null`, `== null`, 리터럴)도 가드다. 특히 `if (x)` truthiness 검사는 `0`/`""`/`false`도 떨어내므로 의도와 다른 좁힘을 만들 수 있다.

## 3. 판별 유니온(Discriminated Union)과 한계

공통 리터럴 태그를 가진 유니온은 그 속성 비교만으로 각 멤버로 정확히 좁혀진다.

```ts
type Shape = { kind: "circle"; r: number } | { kind: "square"; side: number };
function area(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2;
    case "square": return s.side ** 2;
  }
}
```

한계는 판별 속성이 리터럴 타입이어야 한다는 것이다. `string`으로 넓혀지면 동작하지 않고, 중첩 깊은 속성으로는 판별이 안 된다(1단계 속성만 인식). 태그는 최상위 평면 속성으로 둔다.

## 4. exhaustiveness 검사와 never

```ts
function assertNever(x: never): never { throw new Error(`Unhandled: ${JSON.stringify(x)}`); }
function area2(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2;
    case "square": return s.side ** 2;
    default: return assertNever(s); // 멤버 추가 시 컴파일 에러
  }
}
```

새 kind를 추가하면 s가 never로 좁혀지지 않아 호출이 타입 에러가 되므로 누락을 머지 전에 잡는다.

## 5. 사용자 정의 Type Predicate

```ts
interface ApiOk { ok: true; data: string }
interface ApiErr { ok: false; error: string }
function isOk(r: ApiOk | ApiErr): r is ApiOk { return r.ok; }
function handle(r: ApiOk | ApiErr) {
  if (isOk(r)) console.log(r.data);  // ApiOk
  else console.log(r.error);         // ApiErr
}
```

컴파일러는 predicate 본문의 정확성을 보장하지 않는다 — `return true`로 잘못 써도 통과한다. 즉 내가 책임진다는 단언이다. TS 5.5부터 단순 필터 콜백은 추론되지만 복잡한 검사는 명시가 필요하다.

## 6. Assertion Functions

```ts
function assertString(x: unknown): asserts x is string {
  if (typeof x !== "string") throw new TypeError("not a string");
}
function upper(v: unknown) {
  assertString(v);
  return v.toUpperCase(); // v: string
}
```

type predicate가 분기 안에서 좁히는 반면, assertion function은 분기 없이 호출 이후 흐름을 좁힌다. 두 형태 모두 명시적 반환 타입 주석이 필수이며, 화살표 함수 변수에 할당 시 타입을 직접 적어야 한다.

## 7. 좁힘이 풀리는 함정

```ts
function process(x: string | null) {
  if (x === null) return;
  // x: string
  [1, 2].forEach(() => {
    // 클로저 내부: x: string | null 로 다시 넓어짐
  });
}
```

CFA 결과는 재할당과 클로저 경계에서 무효화된다. 회피책은 좁힌 값을 `const` 지역 변수에 복사하는 것이다 — 재할당 불가라 클로저 안에서도 좁힘이 보존된다. 속성 접근 후 함수 호출이 끼면 속성 좁힘도 풀릴 수 있어 const로 구조 분해해 둔다.

## 8. unknown·never와 좁힘의 상호작용

```ts
function parse(raw: unknown): { id: number } {
  if (typeof raw === "object" && raw !== null &&
      "id" in raw && typeof (raw as Record<string, unknown>).id === "number") {
    return { id: (raw as { id: number }).id };
  }
  throw new TypeError("invalid payload");
}
```

외부 입력은 `any` 대신 `unknown`으로 받고 가드로 좁혀 쓴다. `in` 가드 후 속성 타입까지 좁히려면 추가 typeof가 필요하다. `never`는 도달 불가를 표현하며 exhaustiveness의 기반이다.

## 9. 좁힘 디버깅과 satisfies

```ts
const routes = {
  home: { kind: "static" },
  user: { kind: "dynamic" },
} satisfies Record<string, { kind: "static" | "dynamic" }>;
// routes.home.kind 는 "static"으로 보존됨
```

좁힘 확인의 가장 빠른 방법은 IDE 호버다. `satisfies`는 좁힘은 아니지만, 객체가 타입을 만족하는지 검사하면서 추론된 좁은 타입을 보존해 태그가 string으로 넓어지는 것을 막아 이후 좁힘이 정상 동작하게 돕는다.

## 10. 실무 적용 정리

데이터를 판별 유니온으로 설계하고 태그를 최상위 리터럴로 둔다. 외부 입력은 type predicate나 Zod로 경계에서 좁힌 뒤 내부에서는 좁혀진 타입만 다룬다. 분기를 모두 처리해야 하는 곳엔 assertNever로 exhaustiveness를 강제한다. 클로저·재할당 경계는 const 복사로 방어하면 좁힘 관련 에러 대부분을 예방한다.

## 참고

- TypeScript Handbook — Narrowing: https://www.typescriptlang.org/docs/handbook/2/narrowing.html
- TS 5.5 Release Notes — Inferred Type Predicates: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- TypeScript Handbook — Discriminated Unions & never
- Assertion Functions (TS 3.7 release notes)
