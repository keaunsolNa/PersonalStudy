Notion 원본: https://www.notion.so/3805a06fd6d381689199f1fc453e7e28

# TypeScript Template Literal Types와 재귀 파싱 기반 타입 안전 라우팅

> 2026-06-15 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- Template Literal Type의 분배(distribution) 규칙과 `infer`를 결합한 문자열 패턴 분해를 구현한다
- 재귀 conditional type으로 `/users/:id/posts/:postId` 형태의 경로에서 파라미터 객체 타입을 추출한다
- tail-recursion 한계와 `Type instantiation is excessively deep` 에러를 회피하는 누적 제네릭 패턴을 적용한다
- 컴파일 타임 라우트 검증과 런타임 매칭을 하나의 진실 공급원(single source of truth)으로 묶는다

## 1. Template Literal Type의 기본 메커니즘

Template literal type은 TypeScript 4.1에서 도입되어 문자열 리터럴을 타입 수준에서 조립·분해할 수 있게 한다. 핵심은 두 가지다. 첫째, 유니온이 템플릿 안에 들어가면 곱집합(cartesian product)으로 분배된다. 둘째, `infer`를 패턴 위치에 두면 부분 문자열을 캡처할 수 있다.

```typescript
type Method = "get" | "post";
type Resource = "user" | "order";
// 곱집합: "getUser" | "getOrder" | "postUser" | "postOrder"
type Handler = `${Method}${Capitalize<Resource>}`;
```

유니온 분배는 항(term) 수가 곱으로 늘기 때문에 주의가 필요하다. TypeScript는 유니온 멤버가 약 100,000개를 넘어가면 컴파일을 거부한다. 라우팅처럼 경로 세그먼트를 다룰 때는 분배가 아니라 `infer` 기반 분해를 주로 쓴다. `Capitalize`, `Uppercase`, `Lowercase`, `Uncapitalize`는 컴파일러 내장 intrinsic이라 비용이 거의 없다.

## 2. infer로 경로 세그먼트 캡처하기

경로에서 파라미터를 뽑으려면 `:파라미터` 패턴을 재귀적으로 분해한다.

```typescript
type ParseParam<S extends string> =
  S extends `:${infer Param}` ? Param : never;

type A = ParseParam<":id">;     // "id"
type B = ParseParam<"users">;   // never
```

실제 경로는 슬래시로 구분된 다중 세그먼트다. 슬래시 기준으로 머리(head)와 꼬리(rest)를 분리해 재귀한다.

```typescript
type SplitPath<Path extends string> =
  Path extends `${infer Head}/${infer Rest}`
    ? Head | SplitPath<Rest>
    : Path;

type Segments = SplitPath<"users/:id/posts/:postId">;
// "users" | ":id" | "posts" | ":postId"
```

## 3. 파라미터 객체 타입 추출 (재귀 누적)

세그먼트에서 `:`로 시작하는 것만 키로 만들고 값은 `string`으로 매핑한다. 재귀하면서 교집합(`&`)으로 합친다.

```typescript
type PathParams<Path extends string> =
  Path extends `${infer Head}/${infer Rest}`
    ? ParamOf<Head> & PathParams<Rest>
    : ParamOf<Path>;

type ParamOf<Seg extends string> =
  Seg extends `:${infer Name}`
    ? { [K in Name]: string }
    : {};

type P = PathParams<"/users/:id/posts/:postId">;
// { id: string; postId: string }
```

선행 슬래시는 `Head`가 빈 문자열로 캡처되고 `ParamOf<"">`가 `{}`를 반환해 자연스럽게 무시된다. 교집합 표시를 평탄화하려면 `Prettify`를 쓴다.

```typescript
type Prettify<T> = { [K in keyof T]: T[K] } & {};
```

`& {}`는 컴파일러가 교집합을 단일 객체 리터럴로 즉시 평가하도록 유도하는 관용구다.

## 4. 타입 안전 라우터 구현

```typescript
type Handler<Path extends string> =
  (params: Prettify<PathParams<Path>>) => void;

class Router {
  private routes: { pattern: string; handler: Handler<string> }[] = [];
  add<Path extends string>(path: Path, handler: Handler<Path>): this {
    this.routes.push({ pattern: path, handler: handler as Handler<string> });
    return this;
  }
}

const router = new Router()
  .add("/users/:id/posts/:postId", (params) => {
    console.log(params.id, params.postId); // 정확히 추론
    // params.unknown → 컴파일 에러
  });
```

`handler: Handler<Path>`가 `path`의 리터럴 타입에 의존하므로, 핸들러 본문에서 `params`는 정확한 키만 노출한다.

## 5. 런타임 매칭과 타입의 정합성

컴파일 타임 타입과 런타임 매칭은 동일한 패턴 문자열을 진실 공급원으로 공유한다.

```typescript
function match(pattern: string, path: string): Record<string, string> | null {
  const names: string[] = [];
  const regexSource = pattern.split("/").map((seg) => {
    if (seg.startsWith(":")) { names.push(seg.slice(1)); return "([^/]+)"; }
    return seg.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }).join("/");
  const result = new RegExp(`^${regexSource}$`).exec(path);
  if (result === null) return null;
  const params: Record<string, string> = {};
  names.forEach((name, i) => { params[name] = decodeURIComponent(result[i + 1]); });
  return params;
}
```

타입은 `:`만 인식하지만 런타임은 정규식 이스케이프·URL 디코딩까지 처리한다. 와일드카드나 옵셔널 파라미터를 추가하려면 타입과 런타임 양쪽을 동시에 확장해야 한다.

## 6. 재귀 깊이 한계와 회피 전략

TypeScript 4.5부터 tail-recursion 최적화로 conditional type의 최종 분기가 자기 자신을 직접 호출하면 깊이 제한이 약 1,000회까지 완화된다. 그러나 `ParamOf<Head> & PathParams<Rest>`처럼 재귀 결과를 교집합으로 감싸면 tail position이 아니어서 기본 한계(약 50회)에 걸릴 수 있다. 누적 파라미터 패턴으로 tail position을 확보한다.

```typescript
type PathParamsAcc<
  Path extends string,
  Acc extends Record<string, string> = {},
> = Path extends `${infer Head}/${infer Rest}`
  ? PathParamsAcc<Rest, Acc & ParamOf<Head>>
  : Prettify<Acc & ParamOf<Path>>;
```

비최적화 버전은 세그먼트 약 45개에서 `excessively deep` 에러가 나는 반면, accumulator 버전은 수백 개까지 견딘다.

## 7. 쿼리 스트링과 옵셔널 파라미터 확장

```typescript
type QueryParams<S extends string> =
  S extends `${string}?${infer Query}` ? ParseQuery<Query> : {};
type ParseQuery<Q extends string> =
  Q extends `${infer Pair}&${infer Rest}` ? PairOf<Pair> & ParseQuery<Rest> : PairOf<Q>;
type PairOf<P extends string> =
  P extends `${infer Key}=${string}` ? { [K in Key]: string } : {};
```

옵셔널 세그먼트(`:id?`)는 `:${infer Name}?`를 먼저 검사해야 한다. 순서가 바뀌면 `Name`이 `"id?"`로 캡처된다.

## 8. 실무 적용과 한계

이 패턴은 Next.js typed routes, tRPC, Hono, Elysia가 내부적으로 활용한다. 장점은 라우트 정의 하나로 컴파일 타임 검증과 IDE 자동완성을 얻는 것이다. 한계: 컴파일러 부하가 라우트 수·경로 길이에 비례해 `tsserver`가 느려질 수 있고, 동적으로 조립된 경로는 `string`으로 넓어져 추론이 무력화되며, 정규식 매칭과 타입 파싱이 미묘하게 어긋날 수 있어 테스트로 정합성을 검증해야 한다.

```typescript
// 정합성 검증 (Vitest)
test("PathParams extracts named segments", () => {
  expectTypeOf<PathParams<"/a/:x/:y">>().toMatchTypeOf<{ x: string; y: string }>();
});
test("runtime match aligns with type", () => {
  expect(match("/users/:id", "/users/42")).toEqual({ id: "42" });
});
test("trailing slash without value is null", () => {
  expect(match("/users/:id", "/users/")).toBeNull();
});
```

## 9. 숫자 파라미터와 타입 변환

`:id(number)` 형태를 파싱해 값 타입을 `number`로 매핑한다.

```typescript
type TypedParam<Seg extends string> =
  Seg extends `:${infer Name}(number)` ? { [K in Name]: number }
  : Seg extends `:${infer Name}(boolean)` ? { [K in Name]: boolean }
  : Seg extends `:${infer Name}` ? { [K in Name]: string }
  : {};

type P2 = TypedParam<":age(number)">;  // { age: number }
```

가장 구체적인 패턴을 먼저 검사해야 한다. 런타임 변환기는 같은 규칙을 따르되 변환 실패(숫자 자리에 `"abc"`) 처리 정책을 정해야 한다. 타입은 성공 시의 모양만 보장하므로 런타임 검증 실패 경로는 별도 테스트로 보증한다.

## 참고

- TypeScript Handbook, "Template Literal Types": https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html
- TypeScript 4.5 Release Notes, "Tail-Recursion Elimination on Conditional Types": https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- Hono Documentation, "RegExpRouter / Path Parameters": https://hono.dev/docs/api/routing
- microsoft/TypeScript PR #45711, "Recursive conditional types": https://github.com/microsoft/TypeScript/pull/45711
