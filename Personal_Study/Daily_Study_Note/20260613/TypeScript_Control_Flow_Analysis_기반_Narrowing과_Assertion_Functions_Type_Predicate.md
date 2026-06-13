Notion 원본: https://www.notion.so/37e5a06fd6d381d0ac59d67d035097f8

# TypeScript Control Flow Analysis 기반 Narrowing과 Assertion Functions · Type Predicate

> 2026-06-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 컴파일러가 코드 경로를 따라 타입을 좁히는 control flow analysis(CFA)의 동작 시점을 예측한다
- typeof·instanceof·in·동등성·판별 속성 등 내장 가드별로 좁혀지는 결과를 구분한다
- 사용자 정의 type predicate(`x is T`)와 assertion function(`asserts x is T`)을 작성하고 둘의 차이를 설명한다
- 클로저·재할당·`let` 변수에서 좁힘이 풀리는(widening) 함정을 회피한다

## 1. Control Flow Analysis란

TypeScript는 변수의 선언 타입과 별개로, 코드 실행 경로상 각 지점에서 그 변수가 가질 수 있는 타입을 따로 계산한다. 이를 control flow analysis라 하며, 분기·반복·return·throw를 따라 타입을 좁히거나 다시 넓힌다. 좁힘은 정적 분석이므로, 런타임 검사를 흉낼 수 있는 "가드" 표현식을 만났을 때만 일어난다.

```ts
function f(x: string | number) {
  // 여기서 x: string | number
  if (typeof x === "string") {
    x.toUpperCase(); // x: string
  } else {
    x.toFixed(2);    // x: number
  }
}
```

핵심은 좁힘이 "위치 기반"이라는 점이다. 동일 변수라도 if 블록 안과 밖에서 타입이 다르며, 컴파일러는 각 노드마다 flow node를 두고 타입을 캐시한다.

## 2. 내장 가드별 좁힘 규칙

`typeof`는 원시 타입(`"string"`, `"number"`, `"boolean"`, `"object"`, `"function"`, `"undefined"`, `"bigint"`, `"symbol"`)에 대해서만 작동한다. `instanceof`는 클래스 인스턴스를, `in` 연산자는 속성 존재 여부로 객체 유니온을 좁힌다.

```ts
type Dog = { bark: () => void };
type Cat = { meow: () => void };

function speak(animal: Dog | Cat) {
  if ("bark" in animal) {
    animal.bark(); // animal: Dog
  } else {
    animal.meow(); // animal: Cat
  }
}
```

동등성 비교도 가드가 된다. `x === null`, `x == null`(null·undefined 동시), 리터럴 비교 등이다. 특히 `=== undefined` 후의 분기, `if (x)` 같은 truthiness 검사는 `0`/`""`/`false`도 함께 떨어내므로 의도와 다른 좁힘을 만들 수 있어 주의해야 한다.

```ts
function len(s?: string) {
  if (s) return s.length; // s: string — 단, s === "" 이면 이 분기로 안 들어옴
  return 0;
}
```

## 3. 판별 유니온(Discriminated Union)과 한계

공통의 리터럴 태그 속성을 가진 유니온은 그 속성 비교만으로 각 멤버로 정확히 좁혀진다. 이것이 Effect/Redux 등에서 태그드 유니온이 선호되는 이유다.

```ts
type Shape =
  | { kind: "circle"; r: number }
  | { kind: "square"; side: number };

function area(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2; // s: circle
    case "square": return s.side ** 2;        // s: square
  }
}
```

한계는 판별 속성이 **리터럴 타입**이어야 한다는 것이다. 태그가 `string`으로 넓혀지면 좁힘이 동작하지 않는다. 또 두 멤버가 같은 태그 값을 공유하면 판별이 불가능하다. 중첩 객체의 깊은 속성으로는 판별이 되지 않으므로(컴파일러는 1단계 속성만 판별자로 인식) 태그는 최상위 평면 속성으로 두는 것이 안전하다.

## 4. exhaustiveness 검사와 never

모든 멤버를 처리했음을 컴파일러에게 강제하려면 `default`에서 `never`로 받는다. 멤버가 추가되면 그 지점에서 타입 에러가 나 누락을 잡아 준다.

```ts
function assertNever(x: never): never {
  throw new Error(`Unhandled: ${JSON.stringify(x)}`);
}

function area2(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2;
    case "square": return s.side ** 2;
    default: return assertNever(s); // Shape에 멤버 추가 시 컴파일 에러
  }
}
```

이 패턴은 런타임 안전망과 컴파일 타임 검증을 동시에 제공한다. 새 `kind`를 추가하면 `s`가 `never`로 좁혀지지 않아 `assertNever(s)` 호출이 타입 에러가 되므로, 처리 누락이 머지 전에 드러난다.

## 5. 사용자 정의 Type Predicate

내장 가드로 표현 불가능한 검사는 반환 타입을 `x is T`로 선언한 type predicate 함수로 만든다. 함수가 `true`를 반환하면 호출부에서 인자가 `T`로 좁혀진다.

```ts
interface ApiOk { ok: true; data: string }
interface ApiErr { ok: false; error: string }

function isOk(r: ApiOk | ApiErr): r is ApiOk {
  return r.ok;
}

function handle(r: ApiOk | ApiErr) {
  if (isOk(r)) {
    console.log(r.data);  // r: ApiOk
  } else {
    console.log(r.error); // r: ApiErr
  }
}
```

주의할 점은 predicate 본문의 정확성을 컴파일러가 보장하지 않는다는 것이다. `return r.ok;`를 `return true;`로 잘못 써도 통과한다 — 즉 type predicate는 "내가 책임진다"는 단언이다. TypeScript 5.5부터는 단순한 필터 콜백의 경우 predicate를 명시하지 않아도 컴파일러가 추론해 주지만, 복잡한 검사는 여전히 명시가 필요하다.

## 6. Assertion Functions

`asserts x is T`는 함수가 정상 반환했다는 사실만으로 이후 코드에서 `x`를 `T`로 좁힌다. 검사 실패 시 throw 하는 것이 계약이다.

```ts
function assertString(x: unknown): asserts x is string {
  if (typeof x !== "string") throw new TypeError("not a string");
}

function upper(v: unknown) {
  assertString(v);
  return v.toUpperCase(); // v: string — assert 이후 좁혀짐
}
```

type predicate가 분기(boolean 반환) 안에서 좁히는 반면, assertion function은 분기 없이 호출 지점 이후 전체 흐름을 좁힌다. `asserts x`(타입 없이)는 truthiness 단언으로, `assert(cond)` 류 유틸리티에 쓴다. 두 형태 모두 함수 시그니처에 명시적 반환 타입 주석이 반드시 있어야 하며, 화살표 함수 변수에 할당할 때는 추론이 되지 않아 타입을 직접 적어야 한다.

## 7. 좁힘이 풀리는 함정

CFA의 결과는 변수 재할당과 클로저 경계에서 무효화된다. `let` 변수를 좁힌 뒤 함수 호출이 끼면, 컴파일러는 그 호출이 변수를 바꿨을 수 있다고 보수적으로 가정해 좁힘을 유지하거나 풁다. 특히 콜백/클로저 안에서 외부 `let` 변수를 참조하면 좁힘이 사라진다.

```ts
function process(x: string | null) {
  if (x === null) return;
  // 여기서 x: string
  [1, 2].forEach(() => {
    // 클로저 내부: x: string | null 로 다시 넓어짐
    // x.length;  // 에러: x가 null일 수 있음
  });
}
```

회피책은 좁힌 값을 `const` 지역 변수에 복사하는 것이다. `const narrowed = x;`로 받으면 재할당이 불가능하므로 클로저 안에서도 좁힘이 보존된다. 또 객체 속성 접근(`obj.prop`) 후 함수 호출이 끼면 그 속성의 좁힘도 풀릴 수 있어, 핫 패스에서는 속성을 지역 `const`로 구조 분해해 두는 편이 안전하고 가독성도 좋다.

## 8. unknown·never와 좁힘의 상호작용

`unknown`은 모든 타입을 받지만 좁히기 전에는 어떤 연산도 허용하지 않는 안전한 최상위 타입이다. 외부 입력은 `any` 대신 `unknown`으로 받고 가드로 좁혀 쓰는 것이 정석이다.

```ts
function parse(raw: unknown): { id: number } {
  if (
    typeof raw === "object" && raw !== null &&
    "id" in raw && typeof (raw as Record<string, unknown>).id === "number"
  ) {
    return { id: (raw as { id: number }).id }; // 단계적으로 좁힘
  }
  throw new TypeError("invalid payload");
}
```

`in` 가드 후에도 속성 타입까지 좁히려면 위처럼 추가 `typeof` 검사가 필요하다. 한편 `never`는 "도달 불가"를 표현한다. 모든 가드를 통과해 남는 것이 없으면 변수가 `never`로 좁혀지는데, 이를 이용한 `assertNever`가 §4의 exhaustiveness 검사다. 빈 배열·throw만 하는 함수의 반환 타입이 `never`인 것도 같은 맥락이다.

## 9. 좁힘 디버깅과 satisfies

좁힘이 기대대로 동작하는지 확인하는 가장 빠른 방법은 IDE에서 변수에 마우스를 올려 그 지점의 타입을 읽는 것이다. 코드로 단언하려면 헬퍼를 둔다.

```ts
type Expect<T extends true> = T;
type Equal<A, B> =
  (<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false;

function demo(x: string | number) {
  if (typeof x === "string") {
    type _ = Expect<Equal<typeof x, string>>; // 좁힘 검증(컴파일 타임)
    return x.length;
  }
  return x;
}
```

`satisfies` 연산자는 좁힘은 아니지만 관련 함정을 막는다. 객체 리터럴이 특정 타입을 만족하는지 검사하면서도 추론된 좁은 타입을 보존하므로, 판별 유니온 상수를 정의할 때 태그가 `string`으로 넓어지는 것을 방지해 이후 좁힘이 정상 동작하게 돕는다.

```ts
const routes = {
  home: { kind: "static" },
  user: { kind: "dynamic" },
} satisfies Record<string, { kind: "static" | "dynamic" }>;
// routes.home.kind 는 "static"으로 보존됨 (string 으로 넓어지지 않음)
```

## 10. 실무 적용 정리

좁힘을 신뢰성 있게 쓰려면 데이터를 판별 유니온으로 설계하고 태그를 최상위 리터럴 속성으로 두는 것이 출발점이다. 외부 입력(JSON·`unknown`)은 type predicate나 Zod 같은 스키마 검증으로 경계에서 `T`로 좁힌 뒤 내부에서는 좁혀진 타입만 다루도록 한다. 분기를 모두 처리해야 하는 곳에는 `assertNever`로 exhaustiveness를 강제해 모델 변경이 곲 컴파일 에러로 드러나게 만든다. 마지막으로, 좁힘이 풀리는 클로저·재할당 경계를 인지하고 `const` 복사로 방어하면 "분명 좁혔는데 왜 에러?"의 대부분을 예방할 수 있다.

## 참고

- TypeScript Handbook — Narrowing: https://www.typescriptlang.org/docs/handbook/2/narrowing.html
- TypeScript 5.5 Release Notes — Inferred Type Predicates: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- TypeScript Handbook — Discriminated Unions & never
- "Assertion Functions" TS 3.7 release notes
