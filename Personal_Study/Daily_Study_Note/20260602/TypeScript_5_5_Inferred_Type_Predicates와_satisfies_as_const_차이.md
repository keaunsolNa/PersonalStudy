Notion 원본: https://www.notion.so/3735a06fd6d38196bf4ad38b9f035f29

# TypeScript 5.5 Inferred Type Predicates와 satisfies, as const 차이

> 2026-06-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TS 5.5 의 inferred type predicate 가 어떤 식으로 narrowing 을 자동 생성하는지 컴파일러 동작을 추적한다
- `satisfies`, `as const`, type predicate 가 각각 좁히는 범위와 평가 시점 차이를 분리한다
- `Array.prototype.filter` 에서 작성하지 않아도 boolean returning predicate 가 narrowing 되는 조건을 정리한다
- 라이브러리 작성자 입장에서 어떤 시그니처를 선택해야 하는지 기준을 세운다

## 1. TS 5.5 이전의 문제 — filter 가 좁혀주지 않는다

5.5 이전에는 `arr.filter(x => x != null)` 의 반환 타입이 *입력 타입과 동일* 했다. `(T | null)[]` 을 입력하면 결과도 `(T | null)[]`. 사용자가 narrowing 을 원하면 type predicate 를 명시적으로 적었다.

```ts
const ids: (string | null)[] = ['a', null, 'b'];
const result = ids.filter(x => x !== null);
// 5.4 이전: (string | null)[] — 좁아지지 않음
// 5.5 이후: string[] — 자동 narrowing
```

자동 narrowing 이 되려면 화살표 함수가 다음 모든 조건을 만족해야 한다:

- 매개변수가 정확히 1개
- 매개변수 타입이 union
- 함수 body 가 expression 1개 또는 단순 `return expr`
- expression 이 truthiness / type 비교(`x !== null`, `typeof x === 'string'`, `x instanceof Foo`, discriminant 비교 등) 로만 구성
- 함수 본문이 `any` 결과를 만들지 않는다

이 조건을 만족하면 컴파일러가 *그 함수의 narrowing path* 를 추적해 자동으로 `x is T` predicate 를 합성한다. 못 만족하면 5.4 이전 동작으로 폴백한다.

## 2. 자동 합성의 원리 — control flow analysis 의 재활용

TS 의 narrowing 은 control flow analysis (CFA) 가 담당한다. CFA 는 함수 본문을 statement 별로 순회하며 변수의 *현재 추론 타입* 을 갱신한다.

```ts
function isString(x: unknown): x is string {
  return typeof x === 'string';
}
```

위와 같은 user-defined predicate 는 *return expression 의 truthiness 가 narrowing 결과를 반영한다* 는 약속이다. 5.5 는 그 약속을 **컴파일러가 본문을 본 결과 자명한 경우** 에 한해 *암묵적으로 적용* 한다. 본문이 `typeof x === 'string'` 같은 atomic 비교라면 narrowing path 가 결정적이라 합성이 안전하다.

```ts
// 자동 합성 OK
const f1 = (x: string | number) => typeof x === 'string';
// 합성된 시그니처: (x: string | number) => x is string

// 합성 실패 — body 가 비단순
const f2 = (x: string | number) => {
  const t = typeof x;
  return t === 'string';
};
// 시그니처: (x: string | number) => boolean
```

합성에서 제외되는 케이스:

| 본문 형태 | 합성 여부 |
|---|---|
| `x => x !== null` | OK |
| `x => typeof x === 'string'` | OK |
| `x => x instanceof Foo` | OK |
| `x => x.kind === 'a'` (discriminant) | OK |
| `x => { return x !== null; }` (block body) | OK (5.5 부터) |
| `x => doCheck(x)` (helper 호출) | NO |
| `x => { if (...) return x; return false; }` | NO |
| `x => x` (truthiness) | OK, but narrowing 은 `NonNullable<T>` 까지만 |

## 3. satisfies 의 역할 — 타입 *체크만* 하고 *추론* 은 보존

`satisfies` 는 5.0 에서 도입된 후 라이브러리 코드에서 광범위하게 채택됐다. 핵심은 *expression 의 추론 타입을 잃지 않으면서 type 호환만 검증* 한다.

```ts
type Config = Record<string, { url: string; timeout?: number }>;

const config = {
  api: { url: '/api', timeout: 3000 },
  auth: { url: '/auth' },
} satisfies Config;

config.api.timeout;  // number | undefined — 보존
config.auth.timeout; // undefined — 정확히 추론
```

`: Config` 어노테이션이면 `config.auth.timeout` 이 `number | undefined` 가 된다. `satisfies` 는 *원본 객체 리터럴의 narrow 한 타입을 그대로 유지* 한다.

런타임 영향 없음. 컴파일 후 JS 에서 `satisfies` 키워드는 사라진다.

## 4. as const 의 역할 — readonly 와 literal narrowing 동시 적용

`as const` 는 type assertion 의 특수형. 적용된 expression 에 세 가지 효과:

- 모든 string/number/boolean 리터럴이 그 *literal type* 으로 고정
- 모든 object property 가 `readonly`
- 모든 array 가 `readonly tuple`

```ts
const colors = ['red', 'green', 'blue'] as const;
// readonly ['red', 'green', 'blue']

const point = { x: 1, y: 2 } as const;
// { readonly x: 1; readonly y: 2 }
```

`as const` 와 `satisfies` 는 합쳐서 쓸 때 가장 강력하다.

```ts
const routes = {
  home: { path: '/', method: 'GET' },
  user: { path: '/users/:id', method: 'GET' },
} as const satisfies Record<string, { path: string; method: 'GET' | 'POST' }>;

type RouteName = keyof typeof routes;  // 'home' | 'user'
routes.home.method;  // 'GET' — literal 보존, 검증도 통과
```

순서가 중요하다 — `as const satisfies T` 가 정상. 반대로 `satisfies T as const` 는 syntax error.

## 5. 세 기능의 평가 시점 차이

평가 단계를 명확히 분리해서 보면 이해가 빠르다.

| 단계 | 동작 |
|---|---|
| Inference | `as const` 가 literal/readonly 결정에 개입 |
| Type checking | `satisfies` 가 호환 검증, `: T` 가 widening 결정 |
| Narrowing | type predicate (명시/암묵) 가 CFA 에 개입 |

같은 변수에 셋을 모두 적용한 사례.

```ts
type EventKind = 'click' | 'hover' | 'focus';

const handlers = {
  click: (e: MouseEvent) => e.clientX,
  hover: (e: MouseEvent) => e.clientY,
  focus: (e: FocusEvent) => e.relatedTarget,
} as const satisfies Record<EventKind, (e: any) => unknown>;

function isClickHandler(
  fn: (e: any) => unknown,
): fn is (e: MouseEvent) => number {
  return fn.length === 1 && fn.name === 'click';
}

const h = handlers.click;
if (isClickHandler(h)) {
  // h: (e: MouseEvent) => number
}
```

`as const` 는 `handlers.click` 의 정확한 함수 타입을 보존, `satisfies` 는 키가 `EventKind` 와 일치하는지 확인, `isClickHandler` 는 narrowing path 를 만든다.

## 6. 5.5 inferred predicate 의 실전 패턴

### filter 체이닝

```ts
type User = { id: string; email: string | null };
const users: User[] = await fetchUsers();

// 5.4 이전
const valid = users.filter(u => u.email !== null);
// User[] — email 이 여전히 string | null

// 5.5
const valid = users.filter(u => u.email !== null);
// valid 의 element 는 `User & { email: string }` 까지는 좁히지 않음
// 매개변수 타입 자체에 union 이 없어 narrowing 대상이 없기 때문
```

함정 — 위 코드는 **자동 narrowing 이 일어나지 않는다**. 매개변수 타입 `User` 가 union 이 아니다. `email` 만 union 이지 `User` 자체는 아니다. 자동 narrowing 은 *매개변수 1개가 union* 인 경우에만 동작한다. 객체 속성을 좁히려면 명시적 type predicate 가 여전히 필요.

```ts
function hasEmail(u: User): u is User & { email: string } {
  return u.email !== null;
}
const valid = users.filter(hasEmail);  // (User & { email: string })[]
```

### Mixed array 의 정제

```ts
const items: (string | number | null)[] = ['a', 1, null, 'b'];
const strings = items.filter(i => typeof i === 'string');
// 5.5: string[]
// 5.4 이전: (string | number | null)[]
```

이 케이스는 매개변수 자체가 union 이라 자동 narrowing 이 동작한다.

### find, some, every 도 영향

5.5 의 합성은 *type predicate 시그니처를 받는 모든 표준 라이브러리 메서드* 에 영향을 준다.

```ts
const first = items.find(i => typeof i === 'number');
// 5.5: number | undefined
// 5.4 이전: string | number | null | undefined
```

## 7. 라이브러리 작성자 가이드 — 언제 무엇을 쓸까

세 기능의 선택 기준.

라이브러리 API 의 *공개 함수* 가 narrowing 을 제공하려면 *명시적 type predicate* 를 작성한다. 자동 합성은 함수 본문 형태에 의존하므로 refactoring 시 *조용히 사라질 수 있다*. 명시적이면 시그니처가 계약이다.

```ts
// 권장
export function isError(x: unknown): x is Error {
  return x instanceof Error;
}

// 비권장 — 5.5 의 자동 합성에만 의존하면 미래에 깨질 수 있음
export const isError = (x: unknown) => x instanceof Error;
```

내부 헬퍼라면 자동 합성을 활용해 boilerplate 를 줄여도 좋다. 단 *5.5+ 만 지원하는 패키지* 임을 `package.json` 의 `typescript` peerDependency 에 명시.

설정/라우팅 객체 정의는 `as const satisfies T` 조합이 표준이다. 라이브러리가 받는 인풋 타입을 정의하면서 *사용자의 리터럴 정보는 보존* 한다.

타입 가드를 *제너릭* 하게 만들고 싶으면 `predicate` 만으론 부족하고 conditional type 으로 분기해야 한다.

```ts
function exists<T>(x: T): x is NonNullable<T> {
  return x != null;
}
const xs = [1, null, 2].filter(exists);  // number[]
```

## 8. 호환성과 마이그레이션 영향

5.5 inferred predicate 가 *기존 코드의 추론 결과를 좁힌다* 는 점은 호환성 위험이다. 다음 시나리오에서 컴파일 에러가 새로 발생할 수 있다.

```ts
const result: (string | number)[] = items.filter(i => typeof i === 'string');
// 5.5: string[] 을 (string | number)[] 에 assign — OK
// 단, 반대로 좁혀진 타입에 의존한 후속 코드가 깨짐
```

다음과 같이 좁혀진 결과를 *어딘가에 더 넓은 타입으로 명시* 한 경우는 영향이 없다. 하지만 추론에 의존해 변수 타입을 받은 경우는 *후속 코드가 string-only API* 를 호출하기 시작한다. CI 가 통과해도 사용자가 의도한 동작과 다를 수 있다.

마이그레이션 권장:

- 5.5 업그레이드 후 `tsc --noEmit` 를 *strict* 모드로 한 번 돌려본다
- `git diff` 로 declaration emit 변화를 확인 — 공개 API 의 .d.ts 가 좁아졌다면 의도된 변경인지 검토
- DefinitelyTyped 에 의존하는 패키지는 `@types/node` 등 일부 모듈의 시그니처가 5.5 의 합성을 활용해 *더 좁아진* 결과를 emit 하는 경우가 있다

## 9. 컴파일 성능 영향

자동 합성은 *함수 본문 분석 비용* 을 추가한다. TS 팀 벤치마크(monaco-editor 빌드 기준) 는 type checking phase 가 약 1.5% 증가, narrowing phase 가 3~4% 증가했다고 보고했다. 실측 가능한 수준은 아니지만 대형 monorepo 에선 누적될 수 있다.

| 측정 항목 | 5.4 | 5.5 |
|---|---|---|
| `tsc --noEmit` (monaco) | 12.4s | 12.7s |
| `tsc --noEmit` (TypeScript self-host) | 38s | 39s |
| Incremental `--watch` rebuild | 변동 미미 | 변동 미미 |

회피할 이유는 없다. 그러나 *함수 body 가 복잡* 한 코드베이스에서 incremental rebuild 가 늦어진다면 *명시적 predicate* 로 본문 분석을 우회할 수 있다.

## 참고

- TypeScript 5.5 Release Notes — Inferred Type Predicates
- TypeScript 5.0 Release Notes — satisfies operator
- TypeScript Handbook — Narrowing
- Microsoft/TypeScript PR #57465 — Inferred type predicate implementation
- Ef