Notion 원본: https://www.notion.so/4145a06fd6d381b88967f17fa84a0ab3

# TypeScript Control Flow Analysis Narrowing과 Discriminated Union: 사용자 정의 타입 가드·assertion 함수·exhaustive 검사

> 2026-11-10 신규 주제 · 확장 대상: TypeScript (타입 narrowing)

## 학습 목표

- 컴파일러가 분기마다 변수 타입을 좁히는 Control Flow Analysis 의 규칙을 적용한다
- 판별 유니언(discriminated union)과 `never` 로 exhaustive 검사를 강제한다
- 사용자 정의 타입 가드(`x is T`)와 assertion 함수(`asserts v is T`)를 구분해 작성한다
- `in` 연산자와 optional 프로퍼티가 만드는 narrowing 함정을 회피한다

## 1. Control Flow Analysis

TypeScript 는 선언 타입(declared type)과 별개로, 코드 위치마다 **좁혀진 타입(narrowed type)** 을 흐름 그래프로 계산한다. `typeof`, `instanceof`, `===`, truthiness, `in`, 판별 프로퍼티 비교, 사용자 정의 타입 가드가 모두 좁히기 구문이다. 좁혀진 타입은 할당·함수 호출(let 변수의 경우 콜백 안에서는 초기화되지 않는 한 리셋)·루프 합류 지점에서 다시 계산된다.

```ts
function h(x: string | number | boolean) {
  if (typeof x === "string") return x.length;   // string
  if (typeof x === "number") return x;          // number
  const y: boolean = x;                         // 남은 건 boolean
  return y;
}
```

`const` 변수와 파라미터(재할당 없음)는 콜백 안에서도 좁혀진 타입이 유지되지만, `let` 변수는 콜백이 나중에 실행될 수 있어 선언 타입으로 되돌아간다. TS 5.4 는 마지막 할당 이후 클로저 안에서의 narrowing 유지를 개선했다.

## 2. 판별 유니언과 exhaustive 검사

공통 리터럴 프로퍼티(`kind`)를 태그로 가진 유니언은 `switch` 로 정확히 한 멤버로 좁혀진다. 모든 케이스를 처리한 뒤 `default` 에 도달한 값은 `never` 여야 하므로, `never` 를 받는 함수로 **누락된 케이스를 컴파일 에러로** 만들 수 있다.

```ts
type Shape =
  | { kind: "circle"; r: number }
  | { kind: "rect"; w: number; h: number }
  | { kind: "tri"; b: number; h: number };

function assertNever(x: never): never {
  throw new Error("unexpected " + JSON.stringify(x));
}

function area(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2;
    case "rect": return s.w * s.h;
    case "tri": return (s.b * s.h) / 2;
    default: return assertNever(s);
  }
}
```

`Shape` 에 `{ kind: "poly" }` 를 추가하면 `default` 의 `s` 가 더 이상 `never` 가 아니므로 `assertNever(s)` 호출에서 컴파일 에러가 난다. 런타임에는 외부 입력이 잘못될 수 있으므로 `assertNever` 가 throw 하도록 두는 것이 안전망 역할을 한다. 반환 타입을 `never` 로 선언하면 호출 이후 코드는 도달 불가로 처리되어 `area` 의 반환 경로 검사도 통과한다.

## 3. 사용자 정의 타입 가드와 assertion 함수

타입 가드는 `boolean` 을 반환하면서 참일 때 타입을 좁힌다. assertion 함수는 반환하지 않으면(throw) 이후를 좁힌다.

```ts
function isString(x: unknown): x is string { return typeof x === "string"; }

function assertIsDefined<T>(v: T, msg?: string): asserts v is NonNullable<T> {
  if (v == null) throw new Error(msg ?? "undefined");
}
function f(x: string | undefined) {
  assertIsDefined(x);
  return x.length;           // string
}

const isNonEmpty = <T,>(a: readonly T[]): a is readonly [T, ...T[]] => a.length > 0;
function g(a: number[]) {
  if (isNonEmpty(a)) { const h: number = a[0]; return h; }
  return 0;
}
```

가드 본문은 컴파일러가 검증하지 않는다. `x is string` 이라 선언하고 항상 `true` 를 반환해도 통과하므로, 가드는 **작은 함수로 격리하고 단위 테스트**하는 것이 원칙이다. assertion 함수는 호출 대상이 **함수 선언**이거나 **명시적 타입 어노테이션이 붙은 변수**여야 호출부에서 assertion 으로 인식된다. 어노테이션 없는 `const f = (v: unknown): asserts v => {...}` 를 호출하면 에러 TS2775 가 난다(`const g: (v: unknown) => asserts v = f` 처럼 변수에 타입을 명시하면 통과).

## 4. TS 5.5 추론된 타입 가드

TS 5.5 부터 단순한 필터 콜백은 컴파일러가 타입 가드를 **추론**한다. 과거에는 `filter((v): v is number => v !== undefined)` 처럼 직접 써야 했다.

```ts
const ev = [1, 2, undefined].filter(v => v !== undefined);
// TS 5.5+: number[]   (5.4 이하: (number | undefined)[])
```

추론은 "참이면 입력 타입이 좁혀지고 거짓이면 그 반대"가 성립할 때만 적용된다. `v => !!v` 는 0 이 거짓이라 역이 성립하지 않아 추론되지 않는다. 라이브러리가 5.4 이하 사용자도 지원해야 한다면 명시적 가드를 유지한다.

## 5. in 연산자와 optional 의 함정

`"a" in o` 는 `a` 를 가진(또는 optional 로 가질 수 있는) 멤버로 좁히지만, **optional 프로퍼티를 가진 멤버는 거짓 분기에서도 제거되지 않는다.** optional 은 "없을 수 있음"이므로 없는 경우에도 해당 멤버가 남는다.

```ts
function k(o: { a?: string } | { b: number }) {
  if ("a" in o) {
    const s: string | undefined = o.a;   // OK
    return s;
  }
  return o.b;   // 에러 TS2339: '{ a?: string }' 에는 'b' 가 없다
}
```

해결은 판별 프로퍼티를 두는 것이다. `{ type: "A"; a?: string } | { type: "B"; b: number }` 로 설계하면 `o.type === "B"` 로 정확히 좁혀진다. API 응답 모델을 설계할 때 `in` 에 의존하기보다 태그를 명시하는 편이 안전하다.

## 6. 실무 패턴

외부 입력(JSON, 쿼리)은 `unknown` 으로 받아 가드/스키마 검증(Zod 등)으로 좁힌 뒤 도메인 타입으로 다룬다. 이벤트·리듀서·상태 머신은 판별 유니언 + `assertNever` 로 구성하면 새 이벤트 추가 시 처리 누락이 컴파일 에러로 드러난다. `as` 단언은 좁히기가 아니라 컴파일러를 속이는 것이므로, 가드나 assertion 함수로 대체할 수 있는지 먼저 본다. `strict` 와 `noUncheckedIndexedAccess` 를 켜면 배열 인덱스 접근 결과가 `T | undefined` 가 되어 narrowing 사용 빈도가 늘지만 런타임 오류를 줄여 준다.

## 7. trade-off

판별 유니언은 데이터 모양을 "태그 + 페이로드"로 강제해 보일러플레이트가 늘고, 직렬화 포맷에 태그가 노출된다. 대신 분기 누락을 타입 시스템이 잡아 준다. 가드 함수는 재사용성을 주지만 거짓말하는 가드의 위험이 있어 테스트가 필수다.

## 참고

- TypeScript Handbook: Narrowing
- TypeScript 3.7 Release Notes: Assertion Functions
- TypeScript 5.5 Release Notes: Inferred Type Predicates
- TypeScript 5.4 Release Notes: Preserved Narrowing in Closures Following Last Assignments
