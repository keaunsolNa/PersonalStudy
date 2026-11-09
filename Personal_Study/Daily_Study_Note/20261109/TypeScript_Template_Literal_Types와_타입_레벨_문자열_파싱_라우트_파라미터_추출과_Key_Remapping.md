Notion 원본: https://app.notion.com/p/4135a06fd6d38187bf35d57839c16dbc

# TypeScript Template Literal Types와 타입 레벨 문자열 파싱: 라우트 파라미터 추출과 Key Remapping

> 2026-11-09 신규 주제 · 확장 대상: TypeScript (타입 시스템 심화)

## 학습 목표

- Template Literal Type 의 유니온 분배와 `infer` 패턴 매칭으로 문자열을 타입 수준에서 분해한다
- `/users/:id/posts/:postId` 에서 파라미터 이름을 추출해 객체 타입으로 변환한다
- `as` 절 Key Remapping 으로 getter/setter/이벤트 핸들러 이름을 자동 생성한다
- 재귀 깊이 한계와 컴파일 비용을 측정하고 꼬리 재귀 형태로 최적화한다

## 1. Template Literal Type 의 기본 동작

TypeScript 4.1 에서 도입된 Template Literal Type 은 문자열 리터럴 타입을 JS 템플릿 문법으로 조합한다. 핵심은 **유니온에 대한 분배**다. 보간 위치에 유니온이 오면 카테시안 곱이 만들어진다.

```ts
type Method = 'get' | 'post';
type Path = '/users' | '/orders';
type Route = `${Uppercase<Method>} ${Path}`;
// 'GET /users' | 'GET /orders' | 'POST /users' | 'POST /orders'
```

유니온 크기가 곱으로 늘기 때문에 보간 위치마다 10개짜리 유니온을 4번 곱하면 10,000개 멤버가 되고, 컴파일러는 약 10만 개를 넘으면 `Expression produces a union type that is too complex to represent` (TS2590) 오류를 낸다. 즉 편리한 만큼 조합 폭발에 주의해야 한다. 내장 보조 타입은 `Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize` 네 가지이며 모두 컴파일러 내부 구현(intrinsic)이라 직접 정의할 수 없다.

## 2. infer 로 문자열 패턴 매칭

Conditional Type 안에서 Template Literal 과 `infer` 를 결합하면 정규식 없이 문자열을 분해할 수 있다. `infer` 는 가장 짧게 매칭하는 방향으로 동작해, 구분자가 반복되면 앞쪽 `infer` 는 첫 구분자까지만 가져가고 나머지는 뒤쪽 `infer` 가 받는다.

```ts
type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}`
    ? [Head, ...Split<Tail, D>]
    : [S];

type A = Split<'a.b.c', '.'>; // ['a', 'b', 'c']

type Trim<S extends string> =
  S extends ` ${infer R}` | `${infer R} ` ? Trim<R> : S;
```

`D` 가 빈 문자열일 때 무한 재귀가 되므로 실무에서는 `D extends ''` 가드를 추가한다.

## 3. 라우트 파라미터 추출기

Express/Fastify 스타일 경로 `/users/:id/posts/:postId` 에서 파라미터 이름만 뽑아 `{ id: string; postId: string }` 를 만드는 예제다.

```ts
type ExtractParams<P extends string> =
  P extends `${string}:${infer Param}/${infer Rest}`
    ? Param | ExtractParams<`/${Rest}`>
    : P extends `${string}:${infer Param}`
      ? Param
      : never;

type ParamsOf<P extends string> = { [K in ExtractParams<P>]: string };

type T = ParamsOf<'/users/:id/posts/:postId'>;
// { id: string; postId: string }

function get<P extends string>(
  path: P,
  handler: (params: ParamsOf<P>) => void,
): void { /* 런타임 라우터 등록 */ }

get('/users/:id/posts/:postId', (p) => {
  p.id;       // string
  p.postId;   // string
  // p.userId // 오류: 존재하지 않는 속성
});
```

제네릭 `P` 가 리터럴로 추론되려면 인자가 `string` 이 아니라 `P extends string` 으로 선언되어야 한다. 이 한 줄이 tRPC, Hono, TanStack Router 가 경로 타입 안전성을 얻는 원리와 동일하다.

## 4. Key Remapping (`as` 절)

4.1 과 함께 들어온 mapped type 의 `as` 절은 키를 변환하거나 `never` 로 필터링한다.

```ts
type Getters<T> = {
  [K in keyof T & string as `get${Capitalize<K>}`]: () => T[K];
};

type User = { name: string; age: number };
type UserGetters = Getters<User>;
// { getName: () => string; getAge: () => number }

type OmitByValue<T, V> = {
  [K in keyof T as T[K] extends V ? never : K]: T[K];
};

type EventHandlers<E extends Record<string, unknown>> = {
  [K in keyof E & string as `on${Capitalize<K>}`]: (payload: E[K]) => void;
};
```

`keyof T & string` 교차는 `symbol`/`number` 키를 제외해 `Capitalize` 제약(string)을 만족시키기 위한 관용구다.

## 5. 재귀 깊이와 컴파일 비용

조건부 타입 재귀는 기본 **인스턴스화 깊이 50**(비꼬리 재귀)에서 막히고, 4.5 부터 꼬리 재귀 최적화가 적용되면 최대 **1,000** 까지 허용된다. 꼬리 위치는 조건부 타입의 분기 결과가 자기 자신 호출 그 자체인 형태여야 한다. 누산기(accumulator)를 인자로 전달하면 꼬리 형태가 된다.

```ts
// 비꼬리: 결과를 배열로 감싸므로 깊이 ~50 에서 TS2589
type SplitSlow<S extends string> =
  S extends `${infer H},${infer T}` ? [H, ...SplitSlow<T>] : [S];

// 꼬리 재귀: 누산기 사용
type SplitFast<S extends string, Acc extends string[] = []> =
  S extends `${infer H},${infer T}` ? SplitFast<T, [...Acc, H]> : [...Acc, S];
```

`tsc --extendedDiagnostics` 로 `Instantiations` 수치를 비교하면 구분자 200개 문자열에서 SplitSlow 는 오류, SplitFast 는 수만 회 인스턴스화로 통과한다. 타입 정교함이 에디터 지연으로 직결되므로 라우트 수백 개 프로젝트에서는 경로 문자열 타입을 `as const` 객체로 한 번만 계산해 재사용한다.

## 6. 실전 trade-off

타입 레벨 파서는 런타임 코드 없이 안전성을 주지만 오류 메시지가 난해해지고 IDE 응답이 느려진다. 팀 규칙으로 (1) 재귀 깊이 20 이하 입력만 지원, (2) 복잡한 파싱은 코드 생성(OpenAPI → 타입)으로 대체, (3) 라이브러리 경계에서만 사용하고 도메인 코드에는 결과 타입만 노출하는 방식을 권장한다.

## 7. 검증용 타입 테스트

```ts
type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;

type _t1 = Expect<Equal<ExtractParams<'/a/:x/b/:y'>, 'x' | 'y'>>;
type _t2 = Expect<Equal<Split<'a,b', ','>, ['a', 'b']>>;
```

`tsc --noEmit` 이 CI 에서 이 파일을 컴파일하면 타입 회귀가 즉시 잡힌다. `tsd`, `expect-type` 라이브러리도 같은 원리다.

## 참고

- TypeScript 4.1 Release Notes: Template Literal Types
- TypeScript 4.5 Release Notes: Tail-Recursion Elimination on Conditional Types
- TypeScript Handbook: Mapped Types – Key Remapping via `as`
- type-challenges (GitHub)
