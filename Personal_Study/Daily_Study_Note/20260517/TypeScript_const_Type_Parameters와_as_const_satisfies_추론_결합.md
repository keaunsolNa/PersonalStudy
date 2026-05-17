Notion 원본: https://www.notion.so/3635a06fd6d3810db99ad699642e88ee

# TypeScript const Type Parameters와 as const, satisfies 추론 결합

> 2026-05-17 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TS 5.0+ const Type Parameter(`<const T>`) 가 generic 인자 추론에서 만들어 내는 차이를 위치(call-site)와 함께 추적한다
- `as const` assertion, `satisfies` 연산자, `const T` 세 가지의 wideing 억제 범위를 구분해 적재적소 사용한다
- 라이브러리 작성자 입장에서 const 제네릭을 받았을 때 추론 결과를 보존하면서 widening이 필요한 일부 자리만 풀어내는 패턴을 익힌다
- 빌더/DSL/라우터 라이브러리에서 const 제네릭으로 리터럴 보존을 달성한 실제 코드(`zod`, `valibot`, `hono`)의 구조를 분석한다

## 1. 배경: TypeScript의 widening 규칙

TypeScript는 사용자가 리터럴 값을 작성하면 가능한 한 사용 시점에서 "넓힌(wider)" 타입으로 추론한다. `const a = "hello"` 는 `"hello"` 타입이지만 `let a = "hello"` 는 `string` 으로 widening 된다. 함수 호출에서도 비슷하다.

```ts
declare function f<T>(x: T): T;
const r1 = f("hello"); // T = string, r1: string  (widening 발생)
const r2 = f("hello" as const); // T = "hello", r2: "hello"
```

`f` 의 시그니처는 어떤 위치에서도 widening 을 차단하지 않으므로, 호출부에서 `as const` 를 매번 붙여 줘야 했다. 라이브러리 작성자 입장에서는 "호출자에게 매번 as const 를 강요" 하는 것 외에는 추론을 보존할 방법이 없었다. TS 5.0이 추가한 `const` modifier 가 그 비대칭을 해소한다.

## 2. const Type Parameter 가 하는 일

```ts
declare function f<const T>(x: T): T;
const r = f({ kind: "a", payload: [1, 2] });
// T = { readonly kind: "a"; readonly payload: readonly [1, 2] }
// r: { readonly kind: "a"; readonly payload: readonly [1, 2] }
```

`<const T>` 는 *호출부에서* 인자를 마치 `as const` 가 붙은 것처럼 추론하라는 지시다. 정확히는 추론기가 인자의 widening literal type 을 만들 때 "이 자리는 widening 하지 마라" 라고 표시한다. 이때 다음 변환이 동시에 일어난다.

객체 리터럴의 속성은 `readonly` 가 된다. 배열 리터럴은 readonly tuple 로 추론된다. 문자열/숫자/불리언 리터럴은 widening 되지 않은 리터럴 타입으로 유지된다.

이 동작은 `T` 한 자리에만 적용된다. 같은 함수에서 다른 매개변수가 일반 `<U>` 라면 `U` 는 평소대로 widening 된다.

```ts
declare function g<const T, U>(x: T, y: U): { x: T; y: U };
const r = g({ kind: "a" }, "hello");
// T = { readonly kind: "a" }, U = string
```

## 3. as const, satisfies, const T 비교

세 가지는 자주 비교되지만 동작 위치와 범위가 다르다.

| 기법 | 적용 위치 | widening 억제 범위 | 호출자 부담 |
|---|---|---|---|
| `x as const` | 값 표현식 | 그 표현식 전체 | 매 호출마다 명시 |
| `expr satisfies T` | 값 표현식 | type-check 만 수행, widening 그대로 | 매 표현식마다 명시 |
| `<const T>` | 함수 시그니처 | 그 매개변수 위치만 | 호출자는 평범히 호출 |

`as const` 는 값을 readonly literal 로 재해석한다. `satisfies` 는 값이 어떤 타입에 맞는지 *검사* 만 하고 추론 결과는 좁은 그대로 둔다(즉, `const x = { kind: "a" } satisfies Shape` 의 `x` 는 여전히 `{ kind: "a" }`). `<const T>` 는 라이브러리 작성자가 한 번 선언해 두면 모든 호출이 자동으로 좁은 추론을 받는다.

세 기법이 함께 쓰일 수 있다.

```ts
const config = {
  mode: "dev",
  ports: [80, 443],
} as const satisfies AppConfig;

declare function defineConfig<const T extends AppConfig>(c: T): T;
const c = defineConfig({ mode: "dev", ports: [80, 443] });
// as const 없이도 c.ports 는 readonly [80, 443]
```

라이브러리가 `<const T>` 를 제공한다면 `as const` 를 호출부에서 생략할 수 있어 DX가 개선된다.

## 4. tuple 추론: 가장 큰 차이가 나는 자리

가장 자주 const 제네릭이 빛나는 곳은 가변 길이 tuple 을 받는 함수다.

```ts
declare function pickFirst<T extends readonly unknown[]>(arr: T): T[0];
const a = pickFirst([1, "x", true]); // T = (string | number | boolean)[], 반환 = string|number|boolean

declare function pickFirstC<const T extends readonly unknown[]>(arr: T): T[0];
const b = pickFirstC([1, "x", true]); // T = readonly [1, "x", true], 반환 = 1
```

`pickFirst` 는 배열 리터럴을 `(string|number|boolean)[]` 로 widening 해서 인덱싱 결과가 의미를 잃는다. `pickFirstC` 는 tuple 로 추론되어 첫 원소 리터럴 타입이 그대로 반환된다.

이 차이는 라우터 DSL, validator chain, builder pattern 모두에서 결정적이다. 다음은 라우터 DSL 의 path 추론 예시다.

```ts
type ExtractParam<S extends string> =
  S extends `${string}:${infer P}/${infer R}` ? P | ExtractParam<R> :
  S extends `${string}:${infer P}` ? P :
  never;

declare function route<const Path extends string>(
  path: Path,
  handler: (params: Record<ExtractParam<Path>, string>) => unknown
): void;

route("/users/:id/posts/:postId", (p) => {
  p.id;     // string
  p.postId; // string
  // @ts-expect-error
  p.unknown;
});
```

`const Path` 가 없으면 `Path` 가 `string` 으로 widening 되어 `ExtractParam<Path>` 가 `never` 로 떨어진다.

## 5. 객체 추론: 라이브러리 작성자가 자주 잘못하는 자리

객체를 const 로 받는 게 항상 좋은 것은 아니다. const 제네릭은 *모든 속성을 readonly* 로 만들기 때문에, 호출자가 `--strict` 환경에서 그 객체를 다시 mutation 하려고 하면 컴파일 오류가 난다.

```ts
declare function defineRoutes<const R extends Record<string, () => unknown>>(r: R): R;
const routes = defineRoutes({ "/": () => {}, "/about": () => {} });
routes["/contact"] = () => {}; // error: readonly
```

이 동작이 의도라면 OK 다. 만약 "스키마 자리만 const 로 추론, 나머지는 가변" 이 목적이라면 const 를 푸는 helper 가 필요하다.

```ts
type Mutable<T> = { -readonly [K in keyof T]: T[K] };
declare function defineRoutes<const R extends Record<string, () => unknown>>(r: R): Mutable<R>;
```

또는 두 단계로 나눠 const 자리는 가짜로 받고, 내부에서 좁은 타입을 별도로 추출한다.

```ts
declare function defineRoutes<R extends Record<string, () => unknown>>(r: R): R;
function defineRoutesTyped<const R extends Record<string, () => unknown>>(r: R) {
  return defineRoutes(r as Mutable<R>);
}
```

## 6. 실제 라이브러리 사례: hono, zod, valibot

### hono

hono v3 의 `app.get(path, handler)` 는 path 의 :param 을 추론한다. 시그니처는 대략:

```ts
get<P extends string, R extends Schema, I extends Input>(
  path: P,
  handler: Handler<E, P, I, R>
): Hono<E, S & { [K in P]: { ... } }, BasePath>;
```

hono 는 명시적으로 `<const P>` 를 사용하지 않고 *제약 위치* 에서 `P extends string` 만 둔다. 호출자가 일반 string literal 을 인자로 주면 TS 가 contextual type 으로 좁게 추론한다(이는 const 제네릭과는 다른 메커니즘으로, 함수 인자가 직접 string literal 이면 좁게 추론된다). 객체 인자가 섞이는 zod/valibot 은 사정이 다르다.

### zod 4

zod 의 `z.object({ ... })` 는 const 제네릭을 거치지 않는다. 대신 호출자가 작성한 객체의 각 속성을 ZodType 으로 받고 `infer` 로 출력 타입을 도출한다. shape 자체가 readonly 되면 `pick`/`omit` 같은 메서드 체이닝이 까다로워지기 때문이다.

### valibot

valibot 은 핵심 API `object({ name: string() })` 에서 비슷한 전략을 쓰며, const 제네릭이 필요한 자리는 `picklist(["a", "b"])` 같은 enum-like 함수에 한정한다.

```ts
declare function picklist<const T extends readonly string[]>(values: T): Schema<T[number]>;
const Status = picklist(["active", "inactive"]); // Schema<"active" | "inactive">
```

배열 자리에는 const 제네릭, 객체 자리에는 일반 제네릭, 이라는 분리가 라이브러리 작성자의 실용적인 합의다.

## 7. const 제네릭과 inference 한계

const 제네릭은 *값 인자* 의 widening 만 차단한다. 다음은 막아 주지 못한다.

타입 매개변수에 명시적으로 `T = string` 같은 default 가 있고 호출자가 인자를 생략하면 default 가 적용된다. `const T extends string` 의 제약은 widening 차단과 별개로 추론 후의 narrowing 만 강제한다. union 으로 들어가는 contextual type 은 여전히 union 그대로 유지된다.

```ts
declare function f<const T extends "a" | "b">(x: T): T;
const r = f(Math.random() > 0.5 ? "a" : "b"); // T = "a" | "b"
```

호출자가 좁은 리터럴을 작성하지 않으면 const 제네릭도 그 이상은 못 한다.

또한 const 제네릭은 spread 인자에서 의도와 다르게 동작할 수 있다.

```ts
declare function tuple<const T extends readonly unknown[]>(...args: T): T;
const t = tuple(1, "x", true); // T = readonly [1, "x", true]  OK

const arr = [1, "x", true]; // (string|number|boolean)[]
const t2 = tuple(...arr);
// T = readonly (string|number|boolean)[]  (이미 widening 된 변수를 다시 좁힐 수 없음)
```

const 는 *호출부에서 새로 작성된 표현식* 에만 작동한다. 변수에 한 번 담긴 값은 그 시점의 타입을 사용한다.

## 8. 컴파일러 옵션과의 상호작용

`--noImplicitAny` 가 꺼져 있어도 const 제네릭의 추론은 동일하게 동작한다. 추론 결과 타입이 `any` 가 아닌 readonly literal 이라 strict 옵션과 무관하다.

`--isolatedModules` 와도 충돌 없다. const 제네릭은 emitted JS 에 영향이 없다(타입 시스템 전용).

`--useDefineForClassFields` 같은 emit 옵션도 무관하다. const 제네릭은 함수 호출의 inference 만 바꾸므로 클래스/필드 출력 의미를 바꾸지 않는다.

`tsserver` 의 quick info(hover) 에서도 const 제네릭이 만든 추론은 readonly 키워드와 함께 정확히 표시된다. IDE 의 hover 결과를 보고 const 가 의도대로 적용됐는지 확인하는 게 디버깅 1차 수단이다.

## 9. 정리: 언제 const 제네릭을 추가할까

라이브러리 시그니처에 const 제네릭을 추가할 가장 좋은 후보는 다음 패턴이다.

값이 *키* 또는 *enum like* 인 자리. picklist, route key, event name 등. 사용자가 그 리터럴을 다른 곳에서 재사용하기 때문에 추론 결과가 좁아야 한다. tuple 자리, 그리고 builder pattern 의 fluent 체인. 체인 끝까지 타입을 누적해야 한다면 처음 인자부터 좁게 잡아 둬야 한다. config object 의 자리이면서 readonly 가 도메인적으로 자연스러운 자리. 라이브러리가 그 객체를 mutate 하지 않는다면 const 가 안전하다.

반대로 다음 자리에는 *쓰지 않는다*. handler/callback 의 *내부 변수* 자리. 사용자가 그 변수를 다시 가공해야 할 수 있다. 객체를 받아 *그 객체를 mutate 해 돌려주는* API. readonly 충돌이 일어난다. JSX props 처럼 contextual type 이 이미 좁은 추론을 보장하는 자리.

const 제네릭은 *마지막에 추가하기 쉬운* 시그니처 변경이다. 처음부터 모든 자리에 다는 것보다, 호출자가 매번 `as const` 를 붙이는 경험을 한 번 본 뒤 그 자리만 const 로 바꾸는 게 라이브러리 진화 전략으로 안전하다.

## 참고

- TypeScript 5.0 Release Notes — Const Type Parameters: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html#const-type-parameters
- TypeScript Pull Request #51865 (const modifier on type parameters): https://github.com/microsoft/TypeScript/pull/51865
- valibot picklist 구현: https://github.com/fabian-hiller/valibot
- hono 라우터 타입 추론 분석: https://hono.dev/concepts/stacks
- Matt Pocock — const generics 와 widening: https://www.totaltypescript.com
