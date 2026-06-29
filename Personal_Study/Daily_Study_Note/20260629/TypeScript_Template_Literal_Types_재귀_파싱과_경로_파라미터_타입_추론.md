Notion 원본: https://app.notion.com/p/38e5a06fd6d3816e848bd7ea48790bc9

# TypeScript Template Literal Types 재귀 파싱과 경로 파라미터 타입 추론

> 2026-06-29 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Template Literal Types 의 패턴 매칭과 `infer` 결합으로 문자열을 타입 수준에서 분해한다
- 재귀 조건부 타입으로 `/users/:id/posts/:postId` 같은 경로에서 파라미터 키를 추출한다
- 분배 동작과 `Uppercase`/`Lowercase` 등 내장 intrinsic 으로 키 변환을 구현한다
- 재귀 깊이 한계와 컴파일 성능 비용을 측정해 실무 적용 경계를 정한다

## 1. Template Literal Types 의 기본 원리

Template Literal Type 은 런타임 템플릿 리터럴과 동일한 문법(`` `${...}` ``)을 타입 위치에서 사용한다. 핵심은 문자열 리터럴 타입을 *조합* 할 뿐 아니라 `infer` 와 결합해 *분해* 할 수 있다는 점이다. 조합 방향은 단순하다.

```typescript
type Method = "get" | "post";
type Resource = "user" | "order";
// 분배 법칙으로 2 x 2 = 4 개 리터럴이 생성된다
type Endpoint = `${Method}_${Resource}`;
// "get_user" | "get_order" | "post_user" | "post_order"
```

유니온이 템플릿 슬롯에 들어가면 각 멤버에 대해 분배되어 곱집합이 만들어진다. 슬롯이 N 개면 결과 크기는 각 유니온 크기의 곱이므로, 큰 유니온을 여러 슬롯에 넣으면 폭발적으로 증가한다. TypeScript 는 유니온 멤버가 100,000 개를 넘으면 `Expression produces a union type that is too complex to represent` 오류를 던진다. 이 상한은 조합형 템플릿을 설계할 때 가장 먼저 고려해야 하는 제약이다.

## 2. infer 로 문자열 분해하기

분해는 조건부 타입 안에서 `infer` 로 슬롯의 내용을 캡처한다. 예를 들어 `"key=value"` 형태를 키와 값으로 쪼갠다.

```typescript
type ParsePair<S extends string> =
	S extends `${infer K}=${infer V}` ? { key: K; value: V } : never;

type R = ParsePair<"limit=10">; // { key: "limit"; value: "10" }
```

`infer K` 는 가장 짧은 매칭(non-greedy)을 우선한다. 즉 `` `${infer K}=${infer V}` `` 에서 `K` 는 첫 번째 `=` 이전까지만 잡는다. `"a=b=c"` 를 넣으면 `K="a"`, `V="b=c"` 가 된다. 이 non-greedy 규칙을 이해하면 구분자가 여러 번 등장하는 문자열을 재귀로 안전하게 쪼갤 수 있다.

## 3. 재귀 조건부 타입으로 경로 파라미터 추출

실무에서 가장 자주 쓰는 패턴은 라우트 경로에서 `:param` 토큰을 모아 객체 타입을 만드는 것이다. Express, Fastify, React Router 모두 이 형태의 타입 안전성을 제공하려고 동일한 기법을 쓴다.

```typescript
type PathParams<Path extends string> =
	Path extends `${string}:${infer Param}/${infer Rest}`
		? Param | PathParams<`/${Rest}`>
		: Path extends `${string}:${infer Param}`
			? Param
			: never;

type P = PathParams<"/users/:id/posts/:postId">;
// "id" | "postId"
```

첫 번째 분기는 `:param` 뒤에 또 다른 세그먼트(`/...`)가 남아 있는 경우를 처리하며, 남은 부분을 `/${Rest}` 로 다시 감싸 재귀 호출한다. 두 번째 분기는 경로 끝의 마지막 파라미터를 처리하는 종료 조건이다. 이렇게 추출한 유니온을 매핑 타입으로 객체화하면 핸들러 인자의 타입을 자동 도출한다.

```typescript
type RouteHandler<Path extends string> = (
	params: { [K in PathParams<Path>]: string }
) => void;

const handler: RouteHandler<"/users/:id/posts/:postId"> = (params) => {
	params.id; // string, 자동완성됨
	params.postId; // string
	// @ts-expect-error 존재하지 않는 키
	params.unknown;
};
```

`PathParams<Path>` 가 `never` 를 반환하면(파라미터 없는 경로) 매핑 타입은 빈 객체 `{}` 가 되어 인자를 비워도 통과한다. 이는 유니온 `never` 가 매핑 타입의 키 소스로 쓰일 때 키가 0 개가 되는 성질을 활용한 것이다.

## 4. 분배와 내장 intrinsic 으로 키 변환

API 응답의 snake_case 키를 camelCase 로 바꾸는 변환은 Template Literal + 재귀 + intrinsic 의 종합 예제다.

```typescript
type SnakeToCamel<S extends string> =
	S extends `${infer Head}_${infer Tail}`
		? `${Head}${Capitalize<SnakeToCamel<Tail>>}`
		: S;

type CamelKeys<T> = {
	[K in keyof T as K extends string ? SnakeToCamel<K> : K]: T[K];
};

type Api = { user_id: number; created_at: string };
type Client = CamelKeys<Api>;
// { userId: number; createdAt: string }
```

`Capitalize` 는 컴파일러가 내장한 intrinsic 타입으로, 첫 글자를 대문자화한다. `Uppercase`, `Lowercase`, `Uncapitalize` 도 동일하게 제공된다. 이들은 `.d.ts` 에 구현이 없고 컴파일러 내부에서 직접 처리하므로 재귀 안에서 써도 추가 인스턴스화 비용이 거의 없다. `as` 절은 매핑 타입의 key remapping 으로, 키를 변환하면서 동시에 값을 보존한다.

## 5. 재귀 깊이 한계와 꼬리 재귀 최적화

TypeScript 4.5 부터 조건부 타입에 *꼬리 재귀 제거(tail recursion elimination)* 가 적용된다. 마지막 동작이 자기 자신 호출이면 컴파일러가 스택을 누적하지 않고 반복으로 평탄화한다. 다음 표는 재귀 형태별 실효 한계다.

| 재귀 형태 | 실효 깊이 한계 | 비고 |
|---|---|---|
| 비꼬리 재귀 (중첩) | 약 50 | `Type instantiation is excessively deep` 오류 |
| 꼬리 재귀 (4.5+, accumulator) | 약 1,000 | 평탄화로 한계 상향 |
| 유니온 곱집합 | 멤버 100,000 | too complex to represent |

문자열을 한 글자씩 누적 파싱할 때는 결과를 accumulator 파라미터에 쌓아 꼬리 위치로 만드는 것이 핵심이다.

```typescript
type Split<S extends string, D extends string, Acc extends string[] = []> =
	S extends `${infer Head}${D}${infer Tail}`
		? Split<Tail, D, [...Acc, Head]> // 꼬리 위치 → 평탄화
		: [...Acc, S];

type Parts = Split<"a/b/c/d", "/">; // ["a", "b", "c", "d"]
```

`[...Acc, Head]` 를 인자로 넘기는 방식이 꼬리 재귀를 보장한다. 만약 `[Head, ...Split<...>]` 처럼 호출 결과를 다시 감싸면 비꼬리 재귀가 되어 깊이 50 근처에서 막힌다.

## 6. 컴파일 성능 비용과 측정

타입 수준 파싱은 런타임 비용은 0 이지만 컴파일 비용은 실재한다. `tsc --extendedDiagnostics` 로 인스턴스화 횟수를 본다.

```bash
$ tsc --noEmit --extendedDiagnostics
Instantiations: 412,938     # 타입 인스턴스화 누적 횟수
Check time:     2.41s
```

경험칙으로 단일 라이브러리의 `Instantiations` 가 100 만을 넘으면 IDE 의 타입 힌트 응답이 눈에 띄게 느려진다. 무거운 재귀 타입은 가능하면 결과를 별칭으로 한 번 평가해 캐싱하거나, 정말 큰 입력에는 타입 수준 파싱 대신 코드 생성(ts-morph 등)으로 우회하는 편이 빌드 전체 처리량에 유리하다. tRPC, Zod, 라우터 라이브러리들이 입력 크기에 비례해 IDE 가 느려지는 근본 원인이 바로 이 인스턴스화 누적이다.

## 7. 실무 적용 시 트레이드오프

타입 수준 경로 파싱은 라우트 정의와 핸들러 인자 사이의 불일치를 컴파일 타임에 잡아주는 강력한 안전망이지만, 세 가지를 감수해야 한다. 첫째, 오류 메시지가 난해해진다. 재귀가 실패하면 `never` 가 전파되어 정작 원인과 먼 곳에서 타입 에러가 뜬다. 둘째, 컴파일/IDE 성능이 입력 문자열 길이와 라우트 개수에 민감하다. 셋째, 동적으로 생성되는 경로(런타임 문자열 조합)에는 적용할 수 없다 — 리터럴 타입이 보존되어야만 동작한다. 따라서 라우트 수가 수백 개 이내이고 정적으로 선언되는 프로젝트에서 가장 효과적이며, 그 이상이면 코드 생성이나 명시적 제네릭 파라미터로 분리하는 설계가 낫다.

## 8. 옵셔널·와일드카드·쿼리스트링 확장

실제 라우터는 단순 `:param` 보다 복잡하다. 옵셔널 세그먼트(`:id?`), catch-all 와일드카드(`*`), 쿼리스트링까지 타입에 반영하려면 분기를 늘린다. 다음은 옵셔널과 catch-all 을 구분해 파라미터의 타입까지 다르게 매핑하는 예다.

```typescript
type Segment<S extends string> =
	S extends `${infer Name}?`        // 옵셔널 파라미터
		? { [K in Name]?: string }
		: S extends `*${infer Name}`    // catch-all
			? { [K in Name]: string[] }
			: { [K in S]: string };

type Merge<T> = { [K in keyof T]: T[K] };

type ParseRoute<Path extends string, Acc = {}> =
	Path extends `${infer Head}/${infer Rest}`
		? Head extends `:${infer P}`
			? ParseRoute<Rest, Acc & Segment<P>>
			: ParseRoute<Rest, Acc>
		: Path extends `:${infer P}`
			? Merge<Acc & Segment<P>>
			: Merge<Acc>;

type R = ParseRoute<"/users/:id/files/*rest/:tab?">;
// { id: string; rest: string[]; tab?: string }
```

옵셔널 파라미터를 `[K in Name]?` 으로 매핑하면 결과 객체에서 해당 키가 선택적이 되어, 핸들러에서 `params.tab` 이 `string | undefined` 로 추론된다. 이렇게 라우트 문법의 표현력을 타입에 1:1 로 대응시키면 라우트 정의 변경이 곧 핸들러 타입 변경으로 이어지는 강한 결합(좋은 의미의)을 얻는다.

## 9. 디버깅 — 중간 타입 들여다보기

재귀 타입이 의도대로 도는지 확인하려면 중간 결과를 눈으로 봐야 한다. IDE 의 hover 로는 `...` 으로 접히는 경우가 많아, 강제 평가 트릭과 컴파일 타임 단언을 함께 쓴다.

```typescript
// 1) 유니온/객체를 강제로 펼쳐 hover 가독성을 높이는 유틸
type Expand<T> = T extends infer O ? { [K in keyof O]: O[K] } : never;

type Debug = Expand<ParseRoute<"/a/:x/:y">>; // hover 시 { x: string; y: string }

// 2) 타입 수준 단위 테스트 — 기대와 다르면 컴파일 에러
type Equals<A, B> =
	(<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

type Assert<T extends true> = T;

type _t1 = Assert<Equals<PathParams<"/u/:id">, "id">>;
// @ts-expect-error 기대와 다르면 여기서 컴파일 실패
type _t2 = Assert<Equals<PathParams<"/u/:id">, "wrong">>;
```

`Equals` 는 두 타입이 *완전히* 같은지를 함수 식별성으로 비교하는 정석 구현이다. 이 패턴으로 타입 수준 함수에 단위 테스트를 붙이면, 라이브러리를 리팩터링할 때 타입 회귀를 컴파일 단계에서 잡을 수 있다. `dtslint` 나 `tsd` 같은 도구가 이 기법을 표준화한 것이다.

## 참고

- TypeScript Handbook — Template Literal Types (https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html)
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- TypeScript Wiki — Performance (https://github.com/microsoft/TypeScript/wiki/Performance)
- type-fest 라이브러리 소스의 `CamelCase`, `Split` 구현
