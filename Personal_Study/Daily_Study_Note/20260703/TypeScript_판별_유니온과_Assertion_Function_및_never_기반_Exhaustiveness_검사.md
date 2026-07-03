Notion 원본: https://www.notion.so/3925a06fd6d3811a9d6bc79f7d6e023f

# TypeScript 판별 유니온과 Assertion Function 및 never 기반 Exhaustiveness 검사

> 2026-07-03 신규 주제 · 확장 대상: TypeScript(타입 narrowing·control flow analysis 학습됨)

## 학습 목표

- 판별 유니온(discriminated union)이 제어 흐름 분석으로 좁혀지는 원리를 설명한다
- `never`를 이용한 컴파일 타임 exhaustiveness 검사를 구현한다
- 타입 술어(`x is T`)와 어서션 함수(`asserts x is T`)의 서명·좁힘 차이를 구분한다
- 상태 머신·리듀서·파싱기에 세 도구를 조합해 케이스 누락을 컴파일러가 잡게 만든다

## 1. 판별 유니온과 제어 흐름 분석

판별 유니온은 각 멤버가 공통의 리터럴 필드(판별자, discriminant)를 갖는 유니온이다. 컴파일러는 그 필드를 검사하는 분기 안에서 유니온을 해당 멤버로 좁힌다.

```ts
type Shape =
  | { kind: "circle"; radius: number }
  | { kind: "rect"; width: number; height: number }
  | { kind: "triangle"; base: number; height: number };

function area(s: Shape): number {
  switch (s.kind) {
    case "circle":
      return Math.PI * s.radius ** 2; // s 는 circle 로 좁혀짐
    case "rect":
      return s.width * s.height;
    case "triangle":
      return (s.base * s.height) / 2;
  }
}
```

판별자로 쓰려면 필드가 각 멤버에서 서로소인 리터럴 타입이어야 한다. `kind: string`처럼 넓은 타입이면 좁힘이 동작하지 않는다. 판별자는 문자열뿐 아니라 숫자·불리언 리터럴도 가능하다.

제어 흐름 분석(control flow analysis)은 대입·`typeof`·`in`·`instanceof`·동등 비교·트루시 검사 같은 좁힘 지점을 추적해 각 지점에서 변수의 "현재 타입"을 재계산한다. `switch`의 각 `case`가 좁힘 지점이 되어 위처럼 동작한다.

## 2. never 기반 exhaustiveness 검사

위 `area`에 새 도형 `square`를 유니온에 추가하고 `case`를 빠뜨리면, 함수는 그 경우 `undefined`를 반환하지만 컴파일러는 침묵한다. 이를 컴파일 오류로 승격시키는 관용구가 `never` 검사다.

```ts
function assertNever(x: never): never {
  throw new Error(`Unhandled case: ${JSON.stringify(x)}`);
}

function area(s: Shape): number {
  switch (s.kind) {
    case "circle":
      return Math.PI * s.radius ** 2;
    case "rect":
      return s.width * s.height;
    case "triangle":
      return (s.base * s.height) / 2;
    default:
      return assertNever(s); // 모든 case 를 처리했다면 s 는 never
  }
}
```

원리는 이렇다. 모든 판별자 값을 `case`로 소진하면 `default`에 도달하는 `s`의 타입은 `never`로 좁혀진다. `assertNever(x: never)`는 `never`만 받으므로 `s`가 `never`일 때만 호출이 성립한다. 나중에 `Shape`에 `square`를 추가하면 `default`의 `s`가 `{ kind: "square"; ... }`로 남아 `never`에 할당 불가가 되고, `assertNever(s)`에서 컴파일 오류가 난다. 즉 유니온을 확장하는 순간 처리 안 된 모든 지점이 빨간 줄로 드러난다.

`return` 대신 `throw`로 실패시키고 싶다면 `default: assertNever(s);`만 두면 된다. 런타임 안전망(예상 못 한 데이터)까지 겸한다.

## 3. 타입 술어: x is T

사용자 정의 타입 가드는 반환 타입 위치에 `parameterName is Type`을 적는다. 이 함수가 `true`를 반환하면 호출부에서 인자가 `Type`으로 좁혀진다.

```ts
interface Cat { meow(): void }
interface Dog { bark(): void }

function isCat(a: Cat | Dog): a is Cat {
  return (a as Cat).meow !== undefined;
}

function speak(a: Cat | Dog) {
  if (isCat(a)) a.meow(); // a: Cat
  else a.bark();          // a: Dog
}
```

타입 술어는 "불리언을 반환하되, 그 불리언이 참일 때 타입 정보를 전달"한다. 주의할 점은 컴파일러가 함수 본문이 실제로 그 술어를 보장하는지 검증하지 않는다는 것이다. `a is Cat`이라 적고 아무 검사도 안 해도 통과한다. 술어의 정확성은 작성자 책임이며, 이는 `satisfies`와 달리 안전성 구멍이 될 수 있다. TypeScript 5.5부터는 단순 술어를 컴파일러가 자동 추론하기도 하지만, 명시적 술어는 여전히 검증되지 않는다.

## 4. 어서션 함수: asserts x is T

어서션 함수는 반환값이 아니라 "함수가 정상 반환했다는 사실 자체"로 타입을 좁힌다. 서명은 `asserts x is T` 또는 조건 없는 `asserts x`다.

```ts
function assertIsDefined<T>(val: T): asserts val is NonNullable<T> {
  if (val === undefined || val === null) {
    throw new Error(`Expected defined, got ${val}`);
  }
}

function loadName(user?: { name?: string }) {
  assertIsDefined(user);        // 이후 user 는 { name?: string }
  assertIsDefined(user.name);   // 이후 user.name 은 string
  return user.name.toUpperCase();
}
```

타입 술어(`is`)와 어서션(`asserts`)의 결정적 차이는 제어 흐름이다. 술어는 `if` 분기 안에서만 좁히지만, 어서션은 호출 이후의 모든 후속 코드에서 좁힘이 유지된다(정상 반환 = 조건 성립 보장). 예외를 던져 잘못된 경로를 끊는 것이 어서션의 계약이다.

어서션 함수를 화살표 함수 변수에 담을 때는 명시적 타입 애노테이션이 필요하다. 컴파일러가 `asserts` 서명을 추론하지 못하기 때문이다.

```ts
const assertString: (v: unknown) => asserts v is string = (v) => {
  if (typeof v !== "string") throw new Error("not string");
};
```

세 도구의 좁힘 성격을 표로 정리한다.

| 도구 | 서명 | 좁힘 범위 | 본문 검증 여부 |
|---|---|---|---|
| 판별 유니온 | (내장) | 분기 내부 | 컴파일러가 자동 |
| 타입 술어 | `x is T` | `if`/`else` 분기 | 검증 안 함(작성자 책임) |
| 어서션 함수 | `asserts x is T` | 호출 이후 전체 | 검증 안 함(예외로 계약) |
| never 검사 | `(x: never)` | default 분기 | 유니온 소진 여부 강제 |

## 5. 실전: 타입 안전 리듀서

Redux 스타일 리듀서에 판별 유니온과 `never`를 결합하면 액션을 추가할 때 처리 누락이 컴파일 오류가 된다.

```ts
type Action =
  | { type: "increment"; by: number }
  | { type: "reset" }
  | { type: "setLabel"; label: string };

interface State { count: number; label: string }

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "increment":
      return { ...state, count: state.count + action.by };
    case "reset":
      return { ...state, count: 0 };
    case "setLabel":
      return { ...state, label: action.label };
    default:
      return assertNever(action);
  }
}
function assertNever(x: never): never {
  throw new Error(`Unhandled action: ${JSON.stringify(x)}`);
}
```

새 액션 `{ type: "decrement"; by: number }`를 `Action`에 추가하면 즉시 `default`에서 오류가 뜬다. 케이스 누락을 코드 리뷰가 아니라 컴파일러가 잡아 준다.

## 6. 함정과 트레이드오프

첫째, `never` 검사는 `switch`의 모든 분기가 값을 반환하거나 `assertNever`로 끝나야 유효하다. 중간에 `break` 후 fall-through가 있으면 좁힘 흐름이 깨져 `never` 보장이 무너진다.

둘째, 타입 술어와 어서션은 컴파일러가 본문을 검증하지 않으므로, 잘못 작성하면 런타임과 타입이 어긋나는 "거짓 안전"이 된다. 가능하면 술어 본문을 단순하게 유지하고, 복잡한 검증은 Zod 같은 런타임 스키마로 옮긴 뒤 그 결과로 좁히는 편이 안전하다.

셋째, `strictNullChecks`가 꺼져 있으면 `never` 좁힘과 `NonNullable` 어서션이 의도대로 동작하지 않는다. 이 패턴들은 `strict` 모드를 전제한다.

## 7. 판별자가 없을 때: in·typeof·tagged tuple

모든 유니온이 깔끔한 `kind` 판별자를 갖지는 않는다. 외부 API가 서로 다른 프로퍼티 집합만으로 케이스를 구분하면 `in` 연산자로 좁힌다. `"prop" in obj`가 참인 분기에서 컴파일러는 그 프로퍼티를 가진 멤버로 유니온을 좁힌다.

```ts
type ApiResult =
  | { data: string[] }
  | { error: { code: number; message: string } };

function handle(res: ApiResult) {
  if ("error" in res) {
    console.error(res.error.message); // res: { error: ... }
  } else {
    res.data.forEach((d) => console.log(d)); // res: { data: ... }
  }
}
```

원시값 유니온은 `typeof`로 좁힌다. `typeof x === "string"` 분기에서 `x`는 `string`으로 좁혀진다. 객체 인스턴스는 `instanceof`, 배열은 `Array.isArray`가 각각 타입 가드로 동작한다. 이들은 모두 제어 흐름 분석이 인식하는 내장 좁힘 지점이다.

튜플 유니온(tagged tuple)은 첫 원소를 판별자로 쓰는 함수형 패턴이다. Redux-Saga나 이펙트 시스템에서 자주 보인다.

```ts
type Cmd =
  | ["move", number, number]
  | ["rotate", number]
  | ["scale", number];

function exec(cmd: Cmd): void {
  switch (cmd[0]) {
    case "move":   return draw(cmd[1], cmd[2]); // cmd: ["move", number, number]
    case "rotate": return turn(cmd[1]);
    case "scale":  return zoom(cmd[1]);
    default:       return assertNever(cmd);
  }
}
function assertNever(x: never): never { throw new Error(String(x)); }
```

## 8. 실전: 파서 결과의 안전한 소비

파서가 성공/실패를 판별 유니온으로 돌려주고, 어서션으로 성공을 강제하는 조합은 견고한 경계를 만든다. `never` 검사까지 얹으면 결과 종류가 늘 때 처리 누락이 컴파일 오류가 된다.

```ts
type ParseResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: string };

function unwrap<T>(r: ParseResult<T>): asserts r is { ok: true; value: T } {
  if (!r.ok) throw new Error(r.error);
}

function useParsed(r: ParseResult<number>) {
  unwrap(r);          // 실패면 여기서 throw
  return r.value * 2; // 이후 r 은 성공 케이스로 좁혀짐
}
```

주의: 어서션 함수의 서명 `asserts r is { ok: true; ... }`는 컴파일러가 본문을 검증하지 않는다. `r.ok`를 실제로 확인하지 않고도 통과하므로, 어서션 본문과 서명의 일치는 작성자가 책임진다. 판별 유니온 좁힘(컴파일러가 자동 검증)을 최대한 쓰고, 어서션은 "경계에서 한 번만" 사용하는 것이 안전한 관행이다.

## 참고

- TypeScript Handbook — Narrowing / Discriminated Unions / Exhaustiveness checking
- TypeScript Handbook — Release Notes 3.7 (Assertion Functions)
- TypeScript Handbook — Release Notes 5.5 (Inferred Type Predicates)
- TypeScript Handbook — The never type
