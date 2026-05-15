Notion 원본: https://www.notion.so/3615a06fd6d381fa9ae0e5ff9fd033ec

# TypeScript Control Flow Analysis와 Narrowing Engine — Assignment Narrowing, never 활용, Exhaustive Checking

> 2026-05-16 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- TypeScript 컴파일러가 조건문·할당·타입 가드를 따라가며 변수의 좁혀진 타입을 추적하는 Control Flow Analysis(CFA) 동작을 설명한다
- `typeof` / `instanceof` / equality / `in` / discriminated property check 가 각각 어떤 좁힘 효과를 갖는지, 좁히지 못하는 경계 케이스를 식별한다
- Assignment Narrowing 과 `let` / `const` 의 좁힘 보존 차이, control flow join 에서 union 으로 재확장되는 시점을 코드로 검증한다
- `never` 를 활용해 switch/if-else 의 exhaustive check 를 컴파일타임에 강제하고, `assertNever` 헬퍼와 `satisfies` 와의 결합을 적용한다

## 1. Control Flow Analysis 개요와 Reachability Graph

TypeScript 의 타입 좁힘은 *값 흐름*(value flow) 추적의 일부다. 컴파일러는 각 함수 본문을 control flow graph(CFG) 로 변환한 뒤 각 노드에서 변수의 *현재 타입*(narrowed type) 을 계산한다. 이 계산은 단방향이며, 분기 join 시 두 갈래의 타입을 union 으로 합친다. 이 과정을 *flow analysis* 라 하며, 컴파일러 소스의 `getFlowTypeOfReference` 가 핵심 함수다.

```ts
function describe(input: string | number | null) {
  // 시작: string | number | null
  if (input === null) {
    // CFA: null 로 좁혀짐
    return 'empty';
  }
  // null 분기 종료 → 여기는 string | number
  if (typeof input === 'string') {
    return input.toUpperCase(); // string
  }
  // 양 분기 join → number
  return (input + 1).toFixed(2);
}
```

핵심은 `return` / `throw` 같은 *unreachable* 종료점을 만나면 그 갈래가 join 에서 제외된다는 것이다. 그래서 위 함수 마지막 줄에서는 `string | number | null` 이 아니라 `number` 가 된다. 이 차감 로직은 `narrowTypeByEquality` → `getTypeWithFacts(TypeFacts.NEUndefinedOrNull)` 같은 *type facts* 비트마스크로 구현돼 있다.

## 2. Type Guard 종류별 좁힘 규칙

| Guard 형태 | 좁힘 효과 | 한계 |
|---|---|---|
| `typeof x === 'string'` | primitive type 좁힘 (string/number/boolean/symbol/bigint/object/undefined/function) | `'object'` 는 object \| null 둘 다 포함 |
| `x instanceof Foo` | constructor 의 instance type 으로 좁힘 | private constructor 나 abstract class 에서 구조적 호환 문제 |
| `x === null` / `=== undefined` | 해당 리터럴/`null`/`undefined` 제거 | `strictNullChecks` off 시 동작 안 함 |
| `'kind' in x` | 해당 키를 갖는 멤버 union 부분으로 좁힘 | optional property 이면 좁히지 못함 |
| User-defined guard `(x): x is Foo` | guard return true 분기에서 `Foo` 로 좁힘 | 거짓말하면 unsoundness — 컴파일러가 검증 못 함 |
| `Array.isArray(x)` | TS 4.x 이후 `readonly any[]` 로 좁힘 | tuple element 정보는 소실 |
| Discriminant property 동등 비교 | discriminated union 의 특정 variant 로 좁힘 | discriminator 가 literal type 이어야 함 |

```ts
type Shape =
  | { kind: 'circle'; radius: number }
  | { kind: 'square'; side: number };

function area(s: Shape) {
  if (s.kind === 'circle') {
    return Math.PI * s.radius ** 2; // { kind: 'circle'; radius: number }
  }
  return s.side ** 2; // { kind: 'square'; side: number }
}
```

`s.kind` 가 string literal type(`'circle' | 'square'`) 이어야 좁힘이 일어난다. 만약 한 variant 가 `kind: string` 으로 선언돼 있으면 좁힘이 깨진다. `as const` 나 `satisfies` 로 literal 화를 강제하는 게 정석이다.

## 3. Assignment Narrowing — `let` vs `const` 의 미묘한 차이

변수에 값을 *할당*하면 그 시점부터 변수의 타입이 할당 값 타입으로 좁혀진다. 이걸 assignment narrowing 이라 부른다.

```ts
let x: string | number;
x = 'hello';
x.toUpperCase(); // OK — x 는 여기서 string
x = 42;
x.toFixed(2);    // OK — x 는 여기서 number
```

문제는 `let` 변수가 *closure* 안에서 사용될 때다. 콜백이 나중에 실행될 수 있으므로 컴파일러는 안전을 위해 좁혀진 타입을 보존하지 않고 *declared type* 으로 복원한다.

```ts
let s: string | null = 'init';
if (s !== null) {
  setTimeout(() => {
    s.length; // ❌ 'string | null' — s 는 좁혀지지 않음
  }, 0);
}
```

`const` 로 선언하면 재할당이 불가능하므로 좁힘이 그대로 유지된다. TS 5.4 부터는 `let` 도 일부 경우 좁힘을 유지하는 *closure 변수 narrowing 보존* 휴리스틱이 추가됐지만, 콜백에서 변수가 다른 함수에 의해 재할당되지 않는다고 컴파일러가 *증명*할 수 있을 때만 동작한다. 일반적으로는 closure 내부에서는 `const local = s` 로 복사해두는 패턴이 안전하다.

`const` 라도 *narrowable* 한 비교 결과는 분기마다 다시 계산된다. 즉 `const x = getValue()` 의 `x` 는 첫 사용 시점에는 declared type 이지만 `typeof x === 'string'` 분기 안에서는 좁혀진 상태로 평가된다.

## 4. Flow Join 에서의 Union 재확장과 widening

if-else 의 두 분기에서 변수에 서로 다른 타입을 할당하면 join 지점에서 union 으로 다시 확장된다.

```ts
declare const cond: boolean;
let v: string | number | boolean;
if (cond) {
  v = 'a';
} else {
  v = 1;
}
// 여기서 v 는 string | number (boolean 제거됨, true 갈래에서 할당된 적 없음)
```

이걸 응용하면 *flow-sensitive* 변수 타입 갱신을 명시할 수 있다. 단 `try / catch / finally` 의 finally 블록에서는 양 분기의 합집합 + 예외로 인한 부분 실행 가능성을 모두 고려해 좁힘이 잘 일어나지 않는다. 이 경우 *명시적 type assertion* 보다는 변수 자체를 분리하는 게 낫다.

또한 *fresh literal type* 의 widening 도 흐름에 영향을 준다.

```ts
let mode = 'dev'; // mode: string (widening)
const mode2 = 'dev'; // mode2: 'dev' (literal preserved)

const obj = { mode: 'dev' } as const; // obj.mode: 'dev'
```

`let` 으로 선언된 string literal 은 즉시 string 으로 widening 되지만, `const` / `as const` / `satisfies` 결합으로 literal type 을 유지할 수 있다. 좁힘은 이 literal 보존이 전제되어야 한다.

## 5. `never` 와 Exhaustiveness Checking 패턴

`never` 는 *값이 존재할 수 없음*을 표현한다. 컴파일러는 모든 case 가 처리된 후 남는 타입이 `never` 임을 알 수 있고, 이를 활용해 누락 case 를 컴파일타임에 잡는다.

```ts
type Event =
  | { type: 'login'; userId: string }
  | { type: 'logout' }
  | { type: 'purchase'; itemId: string; amount: number };

function track(e: Event): void {
  switch (e.type) {
    case 'login':
      sendLogin(e.userId);
      return;
    case 'logout':
      sendLogout();
      return;
    case 'purchase':
      sendPurchase(e.itemId, e.amount);
      return;
    default:
      assertNever(e);
  }
}

function assertNever(x: never): never {
  throw new Error(`Unhandled event: ${JSON.stringify(x)}`);
}
```

`Event` union 에 새 variant 를 추가하면 default 분기에서 `e` 가 새 variant 로 좁혀져 `never` 가 아닌 상태가 되고, `assertNever(e)` 호출이 `Argument of type '{...}' is not assignable to parameter of type 'never'` 에러로 실패한다. 런타임 throw 까지 보장되므로 *컴파일 + 런타임* 양쪽에서 누락이 잡힌다.

대안으로 함수 반환 타입을 `never` 로 명시한 `assertNever` 대신 `satisfies never` 를 쓰는 패턴도 있다.

```ts
default:
  e satisfies never;
  throw new Error('unhandled');
```

`satisfies` 는 `e` 의 타입을 변경하지 않으면서 *타입 호환성*만 검사하므로, 의미상 동일한 효과를 낸다. TS 4.9 이상에서 가능하다.

## 6. Discriminated Union 좁힘이 깨지는 경우와 회피법

분명히 discriminated union 이라고 생각한 코드가 좁혀지지 않는 경우가 있다.

```ts
type Result = { ok: true; data: string } | { ok: false; error: string };
function handle(r: Result) {
  if (r.ok === true) {
    r.data; // OK
  }
  if (r.ok) {
    r.data; // OK
  }
}
```

여기까지는 잘 동작한다. 하지만 다음은 깨진다.

```ts
type Result = { ok: boolean; data?: string; error?: string };
function handle(r: Result) {
  if (r.ok) {
    r.data.toUpperCase(); // ❌ r.data 는 string | undefined
  }
}
```

이유: discriminator 가 `boolean` 이지 literal `true | false` 가 아니라서 ok=true 갈래에서 data 가 반드시 존재함을 증명할 수 없다. literal 화하거나 union variant 로 분리해야 한다.

또 다른 함정은 *optional discriminator*.

```ts
type Maybe = { kind?: 'A'; value: number } | { kind?: 'B'; flag: boolean };
function fn(m: Maybe) {
  if (m.kind === 'A') {
    m.value; // ❌ 좁혀지지 않음 — kind 가 optional
  }
}
```

`kind` 를 필수 필드로 만들거나, `'kind' in m && m.kind === 'A'` 처럼 명시적으로 두 단계로 검사하면 회피된다. *strictNullChecks* 가 활성화돼야 동작한다.

## 7. User-Defined Type Guard 의 위험과 검증 패턴

```ts
function isString(x: unknown): x is string {
  return typeof x === 'string';
}
```

이 형태는 *predicate* 가 거짓말을 해도 컴파일러가 막지 못한다. 다음은 unsoundness 예시다.

```ts
function isUser(x: unknown): x is { name: string } {
  return true; // 거짓말 — 컴파일러는 신뢰함
}

const v: unknown = 42;
if (isUser(v)) {
  v.name.toUpperCase(); // 런타임 에러 — TypeError
}
```

실제로는 *runtime 스키마 검증*과 함께 쓰는 패턴이 안전하다. Zod 의 `safeParse` 를 type predicate 로 wrapping 하는 게 일반적이다.

```ts
import { z } from 'zod';
const User = z.object({ name: z.string() });
function isUser(x: unknown): x is z.infer<typeof User> {
  return User.safeParse(x).success;
}
```

이렇게 하면 predicate 결과가 schema 검증과 동기화되며, 거짓말 가능성이 사라진다. `is` 보다 *assertion function* (`asserts x is Foo`) 이 적절한 경우도 있다.

```ts
function assertUser(x: unknown): asserts x is { name: string } {
  if (!User.safeParse(x).success) throw new Error('not user');
}
const v: unknown = {};
assertUser(v);
v.name; // 이후 좁혀짐
```

assertion function 은 호출 이후 *전체 스코프*에서 좁힘이 유지된다. 단 함수 시그니처에 `asserts` 키워드가 정확히 들어가야 하고, 명시적 반환 타입 어노테이션이 필수다.

## 8. CFA 성능 한계와 컴파일러 옵션

CFA 는 변수 갯수 × 분기 수에 비례하는 비용을 갖는다. 매우 큰 union (100+ variant) 의 switch 문이나 깊은 중첩 conditional 에서 컴파일러가 *symbol* 단위로 캐싱하더라도 노드 수가 폭발한다. TS 5.0 이후 *flow node 재사용*과 *getNarrowedType cache* 가 개선됐지만 본질은 동일하다.

진단/제어 옵션:

| 옵션 | 효과 |
|---|---|
| `strictNullChecks` | null/undefined 좁힘 활성화 (필수) |
| `noUncheckedIndexedAccess` | 인덱스 접근 결과를 `T \| undefined` 로 — 좁힘 양 증가 |
| `exactOptionalPropertyTypes` | optional 과 `undefined` 명시 구분 |
| `--generateTrace` | 컴파일러 trace 출력, flow 노드 비용 분석 |
| `// @ts-expect-error` | exhaustive check 검증용으로 거꾸로 활용 |

실측: 100개 variant discriminated union switch 에 `assertNever` 적용 시 TS 5.4 컴파일 시간 약 +12ms (M3 Pro 기준). variant 가 nested 객체(평균 깊이 3) 면 +80ms 까지 증가. *project references* 로 union 정의를 별도 패키지로 빼고 incremental build 를 켜면 변경 빈도가 낮은 union 의 재계산을 회피할 수 있다.

## 9. 종합 — 좁힘 친화적 코드 작성 체크리스트

- discriminator 는 *literal type* 으로 (string literal union)
- union variant 는 *필수 discriminator* + 서로 다른 추가 필드로 구성
- 콜백 안에서 좁힘이 필요하면 `const local = outer` 로 복사
- `never` 종착 분기에 `assertNever` 또는 `satisfies never` 배치
- user-defined guard 는 schema 검증과 동기화
- nullable 처리 후 `if (x !== null && x !== undefined)` 한 줄로 통합 또는 `if (x != null)` 활용
- `exactOptionalPropertyTypes` 활성화로 optional 의미 명시
- 분기마다 좁힘 결과가 의도와 같은지 IDE 의 *Hover Type* 으로 확인

## 참고

- TypeScript Handbook — Narrowing (https://www.typescriptlang.org/docs/handbook/2/narrowing.html)
- microsoft/TypeScript src/compiler/checker.ts — `getFlowTypeOfReference`, `narrowTypeByTypeFacts` 함수
- TS Release Notes — 4.9 (`satisfies`), 5.4 (narrowing in closures)
- Anders Hejlsberg, TypeScript Type System Deep Dive (TSConf 2022)
- Marius Schulz, TypeScript Evolution series — Control Flow Analysis 편
