Notion 원본: https://www.notion.so/35b5a06fd6d3815f9350fea96bd2737d

# TypeScript Const Type Parameters와 NoInfer Generic 추론 제어

> 2026-05-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `const` 타입 파라미터(TS 5.0)가 인수 위치에서 어떤 추론을 만들고, 호출자가 굳이 `as const` 를 붙이지 않아도 되는 경우를 식별한다
- `NoInfer<T>` 유틸리티(TS 5.4)로 동일 제네릭의 다른 위치 인수가 추론에 끼어드는 것을 차단하는 패턴을 익힌다
- Function Overload·Tuple 추론·Distributive Conditional 과 결합했을 때 `const`/`NoInfer` 가 만들어내는 차이를 구체 예제로 본다
- 라이브러리 설계 관점에서 추론 제어 실패가 어떻게 사용자 측 `as const` 강제, 잘못된 위젯 타입 widening, 잘못된 enum-like 타입 좁히기 실패로 이어지는지 추적한다

## 1. 기존 추론의 한계: literal widening

`<T>(value: T) => T` 형태 함수에 `"red"` 를 넘기면 TS는 `T = string` 으로 widening 한다. 호출자가 좁은 리터럴 타입을 원하면 `<const "red">` 처럼 `as const` 를 직접 붙여야 했다. 객체/배열은 더 심각하다.

```ts
declare function defineRoutes<T>(routes: T): T;

const r = defineRoutes({
  list: { method: "GET", path: "/users" },
  create: { method: "POST", path: "/users" }
});
// r.list.method 의 타입은 string
```

라이브러리가 `routes[K]["method"]` 를 `"GET" | "POST" | ...` 유니온으로 좁히려 해도 widening 때문에 `string` 이 되어버려, 후속 핸들러 매핑이 type-safe 하지 않다. tRPC 초기 버전, ts-rest 등 다수 라이브러리가 이 문제로 사용자에게 `as const` 강제를 요구했다.

## 2. `const` 타입 파라미터의 정확한 의미

TS 5.0 부터 다음 시그니처가 가능하다.

```ts
declare function defineRoutes<const T>(routes: T): T;

const r = defineRoutes({
  list: { method: "GET", path: "/users" },
  create: { method: "POST", path: "/users" }
});
// r.list.method 의 타입은 "GET"
```

핵심은 *호출 인수 표현식* 에 마치 `as const` 를 적용한 것처럼 추론한다는 점이다. 즉 객체 리터럴은 `readonly` + 필드별 리터럴 타입, 배열 리터럴은 `readonly tuple` 로 들어온다.

단, 다음 사실들을 정확히 알고 있어야 한다.

- `const` 는 *추론 결과* 만 좁힌다. 매개변수 타입을 `readonly` 로 만들거나 함수 본문에서 mutate 를 막는 효과는 없다. `function f<const T>(x: T)` 에서 `x` 는 그대로 `T` 다.
- 호출자가 이미 변수를 만들어 넘기면 효과가 사라진다.

```ts
const routes = { list: { method: "GET", path: "/users" } };
defineRoutes(routes); // T = { list: { method: string; path: string } }
```

`routes` 변수의 inferred 타입이 widened 된 후 함수에 넘어가므로, `const T` 는 더 좁힐 게 없다. 라이브러리는 이 한계를 문서화해야 한다.

- `const` 효과는 *해당 타입 파라미터 자리* 에서 추론되는 인수에만 적용된다. `f<const T>(a: T, b: T[])` 에서 `b` 는 이미 `T[]` 이라 `const` 가 직접 작용하지 않는다.

## 3. 사용 패턴: enum-like 문자열 좁히기

```ts
function variant<const V extends string>(v: V): { kind: V } {
  return { kind: v };
}

const a = variant("idle");   // { kind: "idle" }
const b = variant("loading");// { kind: "loading" }
```

`extends string` 제약 + `const` 조합으로, `as const` 없이도 호출자가 자연스럽게 좁은 타입을 받는다. 다만 `extends string` 만으로는 widening 이 일어나기 때문에 `const` 가 반드시 필요하다.

## 4. Tuple 추론과 Variadic Tuple 결합

```ts
function tuple<const T extends readonly unknown[]>(...items: T): T {
  return items;
}

const t = tuple("a", 1, true);
// readonly ["a", 1, true]
```

기존 `<T extends readonly unknown[]>` 만으로는 `(string | number | boolean)[]` 으로 추론되었다. `const` + `extends readonly unknown[]` 조합이 *positional tuple* 을 강제한다. Variadic Tuple Types(`[...T, U]`) 와 결합하면 빌더 API 의 인수 누적 타입을 구축할 수 있다.

## 5. 추론을 막아야 할 때: `NoInfer<T>`

같은 타입 파라미터가 여러 인수 위치에 등장하면, 각 위치 모두에서 추론이 일어나 *원하지 않는 위치가 추론을 좌우* 하는 일이 생긴다. 전형적 사례는 "값 + 그 값의 default" 패턴이다.

```ts
declare function pick<T>(options: T[], def: T): T;

pick(["a", "b", "c"], "b"); // T = "a" | "b" | "c"
pick(["a", "b", "c"], "z"); // T = "a" | "b" | "c" | "z"  ← def 가 추론을 늘림
```

두 번째 호출은 의도적으로는 컴파일 오류여야 한다. `def` 는 *목록에 있는 값* 이어야 한다. TS 5.4 의 `NoInfer<T>` 유틸은 *해당 위치를 추론에서 제외* 한다.

```ts
declare function pick<T>(options: T[], def: NoInfer<T>): T;

pick(["a", "b", "c"], "b"); // OK
pick(["a", "b", "c"], "z"); // Error: 'z' is not assignable to 'a'|'b'|'c'
```

`NoInfer` 는 매핑 타입과 비슷한 패턴으로 정의되며, intrinsic 타입으로 컴파일러가 인식해 *추론기여 차단* 만 수행한다. `T` 의 의미가 변하지는 않는다.

## 6. `const` 와 `NoInfer` 동시 적용

라이브러리에서 자주 쓰이는 조합:

```ts
type Route = { method: "GET" | "POST"; path: string };

declare function defineRoutes<const R extends Record<string, Route>>(
  routes: R,
  options?: { default: NoInfer<keyof R> }
): R;

defineRoutes(
  {
    list:   { method: "GET",  path: "/u" },
    create: { method: "POST", path: "/u" }
  },
  { default: "list" }    // ✅ "list" | "create" 로 좁혀짐
);
```

`const R` 가 첫 인수에서 `R` 을 좁히고, `NoInfer<keyof R>` 가 두 번째 인수가 `R` 추론에 영향을 주지 못하게 막는다. 결과적으로 `default` 는 정의된 라우트 키 중 하나여야만 통과한다.

## 7. Overload 와 함께

`const` 는 오버로드 시그니처에도 붙일 수 있고, 각 시그니처마다 효과를 결정할 수 있다. 빌더 패턴 라이브러리(예: `zod`, `valibot` 의 향후 버전)에서 첫 인수만 좁힐지, 두 번째 인수는 widen 시킬지를 구분할 때 유용하다.

```ts
declare function field<const Name extends string>(
  name: Name,
  rules: ((v: string) => boolean)[]
): { name: Name; rules: typeof rules };

const f = field("email", [v => v.includes("@")]);
// f.name 의 타입은 "email"
```

## 8. 한계와 함정

1. **Distributive Conditional 결합**: `T extends string ? ...` 같은 분배 조건부 안에서 `T` 가 widening 된 상태로 들어오면, 분배가 의도와 달리 일어난다. `const T` 가 widening 을 막아주므로 분배가 정확해진다. 단 `[T] extends ...` 처럼 비분배 형태로 적었으면 `const` 효과가 안 보일 수 있다.

2. **JSX/외부 컨텍스트**: React 컴포넌트 props 의 `<C const T={...}>` 같은 구문은 없다. 컴포넌트 props 추론은 함수 호출 시그니처가 아니라 JSX 변환을 통해 가는데, TS 5.0 이상은 *JSX 호출 자리에서도* `const T` 가 적용된다. 다만 IDE 표시 차이는 환경마다 갈리므로 공식 spec 을 확인할 것.

3. **인수가 변수에 미리 할당된 경우 무력화**: §2 마지막에서 본 것처럼, *인수 표현식 자체* 에 적용되어야 작동한다. 라이브러리 README 에 "직접 객체 리터럴로 넘기세요" 한 줄을 명시하자.

4. **`NoInfer` 의존성**: TS 5.4 미만 환경에서는 동등한 효과를 손수 만들어야 한다. 보통 다음 트릭을 썼다.

```ts
type NoInferLite<T> = [T][T extends any ? 0 : never];
```

이 트릭은 `T` 의 분배를 한 번 차단해 추론 우선순위를 떨어뜨린다. 의미는 비슷하지만 컴파일러 내부 처리 흐름이 다르므로 모든 케이스에서 동등하지는 않다. 5.4 이상이면 intrinsic `NoInfer` 를 그냥 쓰는 게 안전하다.

## 9. 실측: 빌드 시간/메모리

`const` 타입 파라미터는 호출 위치마다 더 풍부한 타입(literal/readonly tuple)을 만들어내므로, *동일한 함수가 수백 번 호출* 되는 코드베이스에서 IL 의 타입 캐시가 커진다. tsc의 `--diagnostics` 로 측정해보면, ts-rest 류 라우트 정의 200건 규모 모노레포에서 `const T` 도입 후 type-check 시간이 5~10% 증가하는 사례가 보고된다. 빌드 머신의 `NODE_OPTIONS=--max-old-space-size=8192` 같은 메모리 상한을 의식해 모노레포 규모가 크면 `incremental: true` + `composite: true` 와 함께 운용한다.

| 시나리오 | 추론 결과 | 비고 |
|---|---|---|
| `<T>(v: T) => T` | `string` | widening |
| `<const T>(v: T) => T` | `"red"` | literal 보존 |
| 인수가 변수 (`const c = "red"`) | `string` 또는 `"red"`(let/const 영향) | `const` 효과 약화 |
| `<T>(opts: T[], def: T)` | `def` 가 추론 늘림 | 버그 잠재 |
| `<T>(opts: T[], def: NoInfer<T>)` | `def` 추론 기여 0 | 의도 일치 |

## 참고

- TypeScript 공식 릴리즈 노트 5.0 — Const Type Parameters
- TypeScript 공식 릴리즈 노트 5.4 — `NoInfer<T>` Utility Type
- microsoft/TypeScript Issue #30680 — `as const` 호출자 부담 트래킹
- microsoft/TypeScript PR #56794 — `NoInfer` intrinsic 도입 PR
- Matt Pocock, "Total TypeScript" — Const Type Parameters & NoInfer 예제집
