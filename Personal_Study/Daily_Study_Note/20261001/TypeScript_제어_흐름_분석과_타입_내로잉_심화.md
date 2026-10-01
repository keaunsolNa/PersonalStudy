Notion 원본: https://app.notion.com/p/3ec5a06fd6d3818fb9f5fd9ce463407d?pvs=204

# TypeScript 제어 흐름 분석(CFA)과 타입 내로잉 심화: 사용자 정의 타입 가드, Assertion Functions, 판별 유니온 완전성 검사

> 2026-10-01 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- 제어 흐름 분석이 선언 타입과 좁혀진 타입을 따로 추적하는 과정을 코드 한 줄씩 추적한다
- 사용자 정의 타입 가드와 5.5 추론 술어의 안전 조건을 판별해 올바르게 적용한다
- Assertion Function을 호출 대상 어노테이션 제약 안에서 정의하고 사용한다
- 판별 유니온에 `never` 기반 완전성 검사를 심어 누락된 분기를 컴파일 오류로 잡는다

## 1. 제어 흐름 분석이 타입을 좁히는 방식

TypeScript 컴파일러는 함수 본문을 훑으면서 각 지점마다 "이 변수는 지금 어떤 타입인가"를 계산한다. 이 계산이 제어 흐름 분석(Control Flow Analysis, CFA)이다. 핵심은 변수에 두 가지 타입이 있다는 점이다. 하나는 선언 타입으로, 변수를 만들 때 정해지고 바뀌지 않는다. 다른 하나는 특정 코드 위치에서의 좁혀진 타입으로, 그 위치에 도달하는 모든 경로에서 일어난 조건 검사와 대입을 모아 결정된다.

대입이 일어나면 좁혀진 타입은 대입된 값의 타입으로 갱신된다. 분기가 합쳐지는 지점에서는 각 경로의 타입을 합집합으로 모은다. 아래 예제는 이 세 규칙을 한 번에 보여준다.

```ts
function demo(flag: boolean) {
  let id: string | number = "abc";
  id.toUpperCase();          // 대입 직후: string

  id = 42;
  id.toFixed(1);             // 대입 후: number

  let x: string | number | boolean;
  x = "a";
  if (flag) {
    x = 1;
  }
  const merged = x;          // 합류 지점: string | number (boolean은 제거됨)
  return merged;
}
```

`boolean`은 어느 경로에서도 대입되지 않아 합류 후 빠진다. 이 추적은 반복문에서도 동작하며, 루프 머리에서는 반복 전후 경로를 합쳐 더 변하지 않을 때까지 계산한다. 복잡한 흐름의 컴파일 비용은 환경에 따라 다르므로 8장에서 측정 방법을 다룬다.

`strictNullChecks`가 꺼져 있으면 `if (x !== undefined)` 같은 기본 내로잉이 아무것도 걸러내지 못하므로, 이 문서의 모든 예제는 `"strict": true`를 전제한다.

## 2. 내장 내로잉 구문과 한계

컴파일러가 별도 선언 없이 이해하는 검사 구문은 정해져 있다. `typeof`, `instanceof`, `in`, 동등 비교(`===`, `!==`, `==`, `!=`), 진리값 검사, 판별 프로퍼티 비교, `Array.isArray` 같은 표준 라이브러리 타입 가드가 그것이다. 각 구문이 무엇을 좁히고 어디서 속기 쉬운지는 아래 표로 정리한다.

| 구문 | 좁혀지는 대상 | 주의할 점 |
|---|---|---|
| `typeof x === "string"` | 원시 타입 판별 | `typeof null`은 `"object"`이므로 객체 분기에 `null`이 남는다 |
| `"name" in x` | 해당 프로퍼티를 가진 유니온 멤버 | 4.9부터 목록에 없는 키도 `Record<"name", unknown>`과의 교차로 좁힌다 |
| `x === undefined` | `undefined` 제거 또는 선택 | `==`는 `null`과 `undefined`를 동시에 걸러낸다 |
| `if (x)` | falsy 값 제거 | `0`, `""`, `NaN`도 함께 제거되어 의도치 않은 분기가 생긴다 |

진리값 검사의 함정은 흔하다. `count: number | undefined`를 `if (count)`로 검사하면 `0`이 `undefined`와 같은 쪽으로 분류된다. `count !== undefined`처럼 의도를 그대로 쓰는 편이 안전하다.

`in` 연산자는 4.9에서 크게 개선되었다. 이전에는 좁히려는 대상이 그 프로퍼티를 선언한 유니온일 때만 동작했지만, 이제는 `object` 같은 타입에서도 키 존재 확인 뒤에 해당 프로퍼티에 접근할 수 있다.

```ts
function readName(input: unknown): string | undefined {
  if (input && typeof input === "object") {
    // 여기서 input: object
    if ("name" in input && typeof input.name === "string") {
      // input: object & Record<"name", unknown>, input.name: string
      return input.name;
    }
  }
  return undefined;
}
```

## 3. 사용자 정의 타입 가드

내장 구문으로 표현할 수 없는 검사, 예를 들어 "이 객체가 `User` 인터페이스를 만족하는가"는 반환 타입에 타입 술어(type predicate) `v is User`를 적어서 컴파일러에게 알린다. 함수 본문은 평범한 boolean 반환이지만, 호출 지점에서는 참이면 `User`로, 거짓이면 `User`가 제외된 타입으로 좁혀진다.

```ts
interface User {
  id: number;
  name: string;
}

function isUser(v: unknown): v is User {
  return (
    typeof v === "object" &&
    v !== null &&
    "id" in v && typeof v.id === "number" &&
    "name" in v && typeof v.name === "string"
  );
}

function greet(payload: unknown) {
  if (isUser(payload)) {
    return `안녕하세요, ${payload.name} (#${payload.id})`;
  }
  return "알 수 없는 사용자";
}
```

여기서 가장 중요한 사실은 컴파일러가 술어의 진위를 검증하지 않는다는 점이다. 본문이 `return true`여도 오류가 나지 않는다. 즉 타입 가드는 "내가 보증한다"는 선언이고, 틀리면 타입 시스템 전체가 그 지점에서 거짓말을 하게 된다. 더 미묘한 것은 거짓 분기다. 술어가 참일 때만이 아니라 거짓일 때도 의미를 가지기 때문에, 아래처럼 쓰면 else 분기가 `never`가 된다.

```ts
function isPositive(n: number): n is number {   // 잘못된 설계
  return n > 0;
}

function check(n: number) {
  if (isPositive(n)) {
    n;  // number
  } else {
    n;  // never: 컴파일러는 "number가 아님"으로 해석한다
  }
}
```

"양수인가"처럼 기존 타입의 부분집합을 가르는 검사는 브랜드 타입으로 표현해야 거짓 분기가 올바르게 남는다.

```ts
type Positive = number & { readonly __brand: "Positive" };

function isPositive(n: number): n is Positive {
  return n > 0;
}

function check(n: number) {
  if (isPositive(n)) {
    n;  // Positive
  } else {
    n;  // number (그대로 남음)
  }
}
```

5.5에서는 타입 술어를 컴파일러가 추론하는 기능이 들어왔다. 함수에 명시적 반환 타입이 없고, return 문이 하나이며, 매개변수를 변경하지 않고, 반환식이 매개변수의 내로잉과 연결된 boolean 식일 때 컴파일러가 술어를 붙여 준다. 대표적인 수혜 사례는 `filter`다.

```ts
const raw: (string | undefined)[] = ["a", undefined, "b"];

// 5.5 이상: string[] (이전 버전: (string | undefined)[])
const names = raw.filter(s => s !== undefined);
```

추론 규칙의 기준은 "참일 때만 그 타입이고, 그 타입이면 참인 경우(if and only if)"에만 술어를 붙인다는 것이다. `(number | undefined)[]`에 `n => !!n`을 쓰면 `0`이 거짓이면서 `number`이므로 이 조건을 어겨 술어가 추론되지 않는다.

## 4. Assertion Functions

타입 가드가 "참이면 좁힌다"라면 Assertion Function은 "예외 없이 반환되었다면 좁혀진 것으로 본다"는 계약이다. 3.7에서 도입되었고 두 가지 형태가 있다. `asserts v is T`는 반환 후 `v`가 `T`임을, `asserts cond`는 반환 후 `cond`가 truthy임을 알린다. 함수가 정상적으로 끝났다는 사실 자체가 증거이므로, 검사에 실패하면 반드시 예외를 던져야 한다.

```ts
function assert(cond: unknown, msg = "Assertion failed"): asserts cond {
  if (!cond) throw new Error(msg);
}

function assertIsString(v: unknown): asserts v is string {
  if (typeof v !== "string") {
    throw new TypeError(`string이 필요하지만 ${typeof v}를 받았습니다`);
  }
}

function load(input: unknown, port?: number) {
  assertIsString(input);
  input.trim();             // input: string

  assert(port !== undefined, "port 필요");
  return { input, port };   // port: number
}
```

주의할 제약이 하나 있다. Assertion Function은 호출되는 이름이 명시적 타입 어노테이션을 가져야 한다. 함수 선언문은 시그니처 자체가 어노테이션이라 문제없지만, 어노테이션 없는 `const` 화살표 함수에 담으면 호출 지점에서 TS2775 오류(Assertions require every name in the call target to be declared with an explicit type annotation)가 발생한다. 같은 이유로 `never`를 반환하는 함수도 도달 불가 판정에 쓰이려면 명시적 어노테이션이 필요하다.

```ts
// 오류 예: 어노테이션 없는 const 화살표 함수는 호출 지점에서 TS2775
// const bad = (v: unknown): asserts v is string => { ... };

// 해결: 함수 선언문 사용 (권장) 또는 변수에 함수 타입을 명시
function good(v: unknown): asserts v is string {
  if (typeof v !== "string") throw new Error("not string");
}
```

Node.js 타입 정의의 `assert`도 `asserts value`로 선언되어 있다. 실패가 정상 분기(사용자 입력 오류)면 타입 가드로, 불변식 위반이면 Assertion Function으로 즉시 중단한다. 후자는 `if` 중첩을 줄이지만 예외라는 부수 효과를 호출자가 인지해야 한다.

## 5. 판별 유니온과 완전성 검사

판별 유니온(discriminated union)은 모든 멤버가 같은 이름의 리터럴 타입 프로퍼티(판별자, discriminant)를 가지는 유니온이다. 판별자를 비교하면 컴파일러가 해당 멤버만 남기므로, `typeof`나 사용자 가드 없이도 안전하게 좁혀진다.

완전성 검사(exhaustiveness check)는 "모든 멤버를 처리했다면 마지막에 남는 타입은 `never`"라는 성질을 이용한다. `never`만 받는 함수에 남은 값을 넘기면, 새 멤버가 추가되었을 때 인자 타입이 `never`가 아니게 되어 컴파일 오류로 누락이 드러난다.

```ts
type Shape =
  | { kind: "circle"; r: number }
  | { kind: "rect"; w: number; h: number }
  | { kind: "tri"; base: number; height: number };

function assertNever(x: never): never {
  throw new Error(`처리되지 않은 케이스: ${JSON.stringify(x)}`);
}

function area(s: Shape): number {
  switch (s.kind) {
    case "circle":
      return Math.PI * s.r ** 2;
    case "rect":
      return s.w * s.h;
    case "tri":
      return (s.base * s.height) / 2;
    default:
      return assertNever(s);   // Shape에 "square"를 추가하면 여기서 컴파일 오류
  }
}
```

`Shape`에 `{ kind: "square"; side: number }`를 더하면 `default` 분기의 `s`가 `square` 멤버 타입으로 남아 `Argument of type ... is not assignable to parameter of type 'never'` 오류가 발생한다. `assertNever`는 런타임에는 도달하지 않아야 하지만, 컴파일된 JS로 잘못된 데이터가 들어올 가능성에 대비해 예외를 던지도록 구현한다.

한편 함수 반환 타입에 `undefined`가 없을 때는 `default` 없이도 컴파일러가 누락을 잡는다. 모든 멤버를 처리하지 않으면 일부 경로에서 값을 반환하지 못해 `Function lacks ending return statement and return type does not include 'undefined'`(TS2366) 오류가 난다. 반환 타입이 `void`인 핸들러에서는 이 검사가 없으므로 `assertNever`가 필요하다.

## 6. 함수 경계, 별칭 조건, 클로저에서의 내로잉

CFA는 한 함수 본문 안에서만 동작하므로, 조건 검사를 변수나 다른 함수로 빼면 정보가 끊기는 경우가 있다. 4.4는 그중 일부를 해결했다. 조건식을 `const` 변수에 담아도 그 변수를 `if`에서 쓰면 원래 검사와 같은 내로잉이 적용된다. 단 검사 대상이 `const` 변수, `readonly` 프로퍼티, 또는 한 번도 대입되지 않은 매개변수일 때만 해당한다. 4.6은 판별 유니온을 `const { kind, payload } = action`처럼 구조 분해한 경우에도 `kind` 비교로 `payload`가 좁혀지게 한다.

클로저는 오랫동안 약점이었다. 콜백 안에서는 외부 `let` 변수나 매개변수가 나중에 바뀔 수 있으므로 컴파일러가 안쪽에서 내로잉을 포기하고 선언 타입으로 되돌렸다. 5.4부터는 클로저가 만들어지기 전에 마지막 대입이 끝난 `let` 변수와 매개변수는 내로잉이 유지된다.

```ts
function getUrls(url: string | URL, names: string[]) {
  if (typeof url === "string") {
    url = new URL(url);        // 이후 대입이 없음
  }
  // 5.4 이상: 콜백 안에서도 url: URL
  return names.map(name => {
    url.searchParams.set("name", name);
    return url.toString();
  });
}
```

반대 방향의 위험도 기억해야 한다. 프로퍼티 접근 경로(`obj.prop`)의 내로잉은 함수 호출로 무효화되지 않는다. 모든 호출을 부수 효과로 가정하면 정상 코드까지 오류가 되기 때문에 낙관적으로 두는 설계이며, 이 절충은 TypeScript 저장소의 이슈 #9998("Trade-offs in Control Flow Analysis")에 정리되어 있다.

```ts
interface Box { value: string | number }

function risky(b: Box, mutate: () => void) {
  if (typeof b.value === "string") {
    mutate();            // 이 함수가 b.value = 1을 실행할 수도 있다
    b.value.length;      // 컴파일 통과, 런타임에서 undefined 접근 가능
  }
}
```

방어책은 단순하다. 좁힌 값을 지역 `const`에 복사해 두고 그 변수만 사용한다. `const v = b.value; if (typeof v === "string") { mutate(); v.length; }`는 호출 이후에도 안전하다.

## 7. 실전 적용: 경계 검증과 설정

외부 입력(HTTP 응답, JSON 파일)은 한 번만 검증하고, 이후 코드는 좁혀진 타입만 다루게 하는 구성이 가장 효과적이다. 검증 함수가 `unknown`을 받아 `{ ok: true; value: T }` 또는 `{ ok: false; error: string }` 같은 판별 유니온을 돌려주면 호출부 내로잉이 그대로 이어진다. 아래 설정은 이 흐름을 컴파일러와 린터 양쪽에서 강제한다.

```json
{
  "compilerOptions": {
    "strict": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "noUncheckedIndexedAccess": true
  }
}
```

`strict`는 `strictNullChecks`와 4.4부터 `catch` 변수를 `unknown`으로 만드는 `useUnknownInCatchVariables`를 포함한다. 린터는 typescript-eslint의 `@typescript-eslint/switch-exhaustiveness-check` 규칙이 타입 정보를 사용해 `switch` 누락을 잡아주므로, `assertNever`가 없는 코드에도 보조 안전망이 된다.

## 8. 트레이드오프와 측정

타입 가드는 컴파일 후 평범한 함수이므로 런타임 비용은 검사 로직 자체의 비용이고, 수치는 환경에 따라 다르다. 비용의 본질은 성능보다 신뢰다. 수작업 가드와 Assertion Function은 작성자가 보증하는 방식이라 스키마와 어긋나도 컴파일러가 모르며, 필드가 많은 객체는 스키마 검증 라이브러리가 유지보수에 유리하다. 반대로 분기 한두 개짜리 내부 검사에는 가드가 가볍다. 판별 유니온은 멤버가 매우 많아지면 분석 시간이 늘 수 있으므로 의심될 때 `tsc --noEmit --extendedDiagnostics`로 검사 시간을 보고, 원인 지점은 `--generateTrace <dir>`로 얻은 trace를 확인한다. 내로잉을 믿기 전 점검할 것은 세 가지다. 가드의 거짓 분기가 의도와 맞는지, 프로퍼티 내로잉 사이에 호출이 끼지 않았는지, `if (x)`가 falsy 값까지 버리지 않는지 확인한다.

## 참고

- TypeScript Handbook, Narrowing: https://www.typescriptlang.org/docs/handbook/2/narrowing.html
- TypeScript 3.7, 4.4, 4.6, 4.9, 5.3, 5.4, 5.5 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/overview.html
- Microsoft/TypeScript 이슈 #9998, Trade-offs in Control Flow Analysis: https://github.com/microsoft/TypeScript/issues/9998
- typescript-eslint, switch-exhaustiveness-check: https://typescript-eslint.io/rules/switch-exhaustiveness-check/
- Dan Vanderkam, Effective TypeScript (O'Reilly)
