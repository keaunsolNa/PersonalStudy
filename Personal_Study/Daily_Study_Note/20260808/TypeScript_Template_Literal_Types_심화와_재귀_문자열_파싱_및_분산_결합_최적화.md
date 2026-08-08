Notion 원본: https://www.notion.so/3b65a06fd6d381d48a40d8497dd754ca

# TypeScript Template Literal Types 심화와 재귀 문자열 파싱 및 분산 결합 최적화

> 2026-08-08 신규 주제 · 확장 대상: TypeScript 타입 시스템 (조건부 타입·infer·Mapped Types 기학습)

## 학습 목표

- Template Literal Type의 유니온 분산(cross-product) 규칙과 조합 폭발 한계를 계산한다
- `infer`와 재귀 조건부 타입으로 타입 레벨 문자열 파서(Split, Trim, 경로 파라미터 추출)를 구현한다
- 꼬리 재귀 최적화(tail-recursion elimination)가 적용되는 조건과 재귀 깊이 한계(999/1000)를 실측한다
- 라우트 타입 추출, 타입 세이프 i18n 키, dot-path 접근자 등 실무 패턴에 적용한다

## 1. Template Literal Type의 평가 모델

Template Literal Type은 문자열 리터럴 타입을 보간(interpolation)해 새 문자열 리터럴 타입을 만든다. 핵심은 보간 슬롯에 유니온이 들어오면 **각 조합으로 분산(cross-product)** 된다는 점이다.

```typescript
type Vertical = "top" | "middle" | "bottom";
type Horizontal = "left" | "center" | "right";

// 3 × 3 = 9개 리터럴 유니온으로 전개된다
type Position = `${Vertical}-${Horizontal}`;
// "top-left" | "top-center" | ... | "bottom-right"
```

분산은 슬롯 단위로 독립적으로 일어나므로 슬롯이 n개이고 각 유니온 크기가 k라면 결과는 k^n으로 증가한다. TypeScript 컴파일러는 이 전개 결과가 **1,000,000개(백만 개) 조합을 초과하면 `Expression produces a union type that is too complex to represent` 오류(ts2590 계열)** 를 낸다. 예를 들어 `${Digit}${Digit}${Digit}${Digit}${Digit}${Digit}` (10^6)은 정확히 한계선에 걸린다. 이 한계는 checker.ts의 `getTemplateLiteralType` 내부에서 조합 수를 사전 계산해 걸러내는 방식이라, 실제 메모리를 소진하기 전에 컴파일 타임에 안전하게 실패한다.

보간 슬롯에 들어갈 수 있는 타입은 `string | number | bigint | boolean | null | undefined` 계열이다. `number`가 들어가면 숫자의 문자열 표현 규칙을 따르는데, `` `${0.1}` ``은 `"0.1"`이지만 `` `${1e21}` ``은 `"1e+21"`이 되는 등 JavaScript의 `String(n)` 동작과 동일하다. 이 때문에 숫자 인덱스를 문자열 키로 변환하는 `${K & number}` 패턴에서 지수 표기 경계를 넘는 값은 주의해야 한다.

## 2. infer를 이용한 패턴 매칭의 동작 원리

Template Literal Type을 조건부 타입의 `extends` 좌변 패턴으로 쓰면 문자열 분해가 가능하다. 컴파일러는 패턴의 리터럴 부분을 앵커로 삼아 **가장 이른(leftmost) 매칭**을 시도한다.

```typescript
type SplitOnce<S extends string, D extends string> =
	S extends `${infer L}${D}${infer R}` ? [L, R] : [S];

type A = SplitOnce<"a.b.c", ".">; // ["a", "b.c"] — 첫 번째 "."에서 분할
```

주의할 점 두 가지가 있다. 첫째, `infer` 슬롯이 **연속으로 두 개** 오면(`${infer A}${infer B}`) 첫 슬롯은 정확히 한 글자만 매칭한다. 리터럴 앵커가 없으면 모호성을 해소하기 위해 컴파일러가 "첫 infer는 1문자, 나머지는 잔여 전체"라는 규칙을 쓰기 때문이다. 둘째, 구분자 `D`가 유니온이면 각 멤버로 분산 매칭이 일어나 결과도 유니온이 된다. 의도치 않은 유니온 전파를 막으려면 `[D] extends [string]` 같은 비분산 래핑이 필요하다.

```typescript
// 첫 글자 추출 — 연속 infer의 1문자 규칙 활용
type FirstChar<S extends string> =
	S extends `${infer C}${infer _Rest}` ? C : never;

type F = FirstChar<"hello">; // "h"
```

`infer X extends Number` 형태(4.7+)의 제약 붙은 infer를 쓰면 매칭과 동시에 타입 좁히기가 일어나, 문자열 → 숫자 변환 같은 패턴이 한 단계로 끝난다.

```typescript
type ToNumber<S extends string> =
	S extends `${infer N extends number}` ? N : never;

type N1 = ToNumber<"42">;   // 42 (number 리터럴)
type N2 = ToNumber<"abc">;  // never
```

## 3. 재귀 문자열 파서 구현 — Split, Trim, Replace

재귀 조건부 타입과 결합하면 타입 레벨에서 완전한 문자열 알고리즘을 구현할 수 있다. 아래는 실무에서 가장 많이 쓰는 세 가지다.

```typescript
// 1) Split: 구분자로 전체 분할 (누산기 방식 — 꼬리 재귀 형태)
type Split<S extends string, D extends string, Acc extends string[] = []> =
	S extends `${infer L}${D}${infer R}`
		? Split<R, D, [...Acc, L]>
		: [...Acc, S];

type Parts = Split<"a.b.c.d", ".">; // ["a", "b", "c", "d"]

// 2) Trim: 공백 문자 유니온으로 앞뒤 제거
type Whitespace = " " | "\t" | "\n" | "\r";
type TrimLeft<S extends string> =
	S extends `${Whitespace}${infer R}` ? TrimLeft<R> : S;
type TrimRight<S extends string> =
	S extends `${infer L}${Whitespace}` ? TrimRight<L> : S;
type Trim<S extends string> = TrimLeft<TrimRight<S>>;

// 3) ReplaceAll: 재귀적으로 모든 매칭 치환
type ReplaceAll<S extends string, From extends string, To extends string> =
	From extends "" ? S
	: S extends `${infer L}${From}${infer R}`
		? `${L}${To}${ReplaceAll<R, From, To>}`
		: S;
```

`Split`을 누산기(Acc) 패턴으로 쓴 이유는 §5의 꼬리 재귀 최적화 대상이 되기 때문이다. `[Split<R, D>, L]`처럼 재귀 결과를 다시 감싸는 형태는 꼬리 위치가 아니라서 깊이 한계가 절반 이하로 떨어진다.

## 4. 실무 패턴 1 — 라우트 경로 파라미터 추출

Express/Hono 스타일의 `/users/:id/posts/:postId` 경로에서 파라미터 객체 타입을 뽑는 패턴은 tRPC·Hono·MSW가 실제로 쓰는 기법이다.

```typescript
type PathParams<Path extends string> =
	Path extends `${infer _Head}:${infer Param}/${infer Rest}`
		? { [K in Param | keyof PathParams<`/${Rest}`>]: string }
		: Path extends `${infer _Head}:${infer Param}`
			? { [K in Param]: string }
			: {};

type P = PathParams<"/users/:id/posts/:postId">;
// { id: string; postId: string }

declare function route<Path extends string>(
	path: Path,
	handler: (params: PathParams<Path>) => void
): void;

route("/users/:id/posts/:postId", (params) => {
	params.id;      // string — OK
	params.postId;  // string — OK
	// params.slug; // 오류: 존재하지 않는 파라미터
});
```

여기서 `{ [K in Param | keyof PathParams<...>]: string }`처럼 Mapped Type 안에서 재귀 결과의 keyof를 유니온으로 합치면 중간 객체 생성 없이 한 번에 평탄한 객체가 나온다. `PathParams<A> & PathParams<B>` 교차 타입으로 합치는 방식보다 IDE 호버 표시가 깨끗하고(교차 타입이 펼쳐진 단일 객체로 보임) 인스턴스화 수도 적다.

## 5. 재귀 깊이 한계와 꼬리 재귀 최적화

TypeScript 컴파일러는 조건부 타입 재귀에 두 단계 한계를 둔다. **꼬리 재귀가 아닌 일반 재귀는 약 50 수준의 인스턴스화 깊이에서, 꼬리 재귀로 인정되면 1,000회 반복에서** `Type instantiation is excessively deep and possibly infinite`(ts2589)로 중단된다. 꼬리 재귀 제거(tail-recursion elimination)는 TypeScript 4.5에서 도입됐으며, **조건부 타입의 참/거짓 분기 결과가 곧바로 자기 자신 호출인 경우**에만 적용된다.

```typescript
// 꼬리 재귀 O — 분기 결과가 바로 재귀 호출
type Repeat<S extends string, N extends number, Acc extends string = "",
	Counter extends unknown[] = []> =
	Counter["length"] extends N
		? Acc
		: Repeat<S, N, `${Acc}${S}`, [...Counter, unknown]>;

type Ok = Repeat<"ab", 999>; // 통과 (999 < 1000)
// type Fail = Repeat<"ab", 1000>; // ts2589

// 꼬리 재귀 X — 재귀 결과를 다시 템플릿으로 감싼다
type RepeatBad<S extends string, N extends number, C extends unknown[] = []> =
	C["length"] extends N ? "" : `${S}${RepeatBad<S, N, [...C, unknown]>}`;
// 약 45~48 부근에서 ts2589 발생
```

실측 기준(TS 5.x, `tsc --noEmit`)으로 누산기형 `Split`은 원소 999개까지 처리되고, 감싸기형은 50개 남짓에서 실패한다. 긴 문자열 처리 타입을 설계할 때는 **항상 누산기 파라미터를 추가해 꼬리 위치로 옮기는 것**이 원칙이다. 한계를 더 늘려야 한다면 한 스텝에서 두 원소씩 소비하는 배치 처리(`${infer A}${D}${infer B}${D}${infer R}`)로 반복 횟수 자체를 절반으로 줄이는 기법이 있다.

## 6. 실무 패턴 2 — dot-path 접근자와 타입 세이프 i18n

중첩 객체를 `"a.b.c"` 형태의 경로 문자열로 접근하는 API(lodash `get`, react-hook-form의 `FieldPath`, i18next의 키 타입)는 Template Literal Type의 대표 활용처다.

```typescript
type Paths<T, Depth extends unknown[] = []> =
	Depth["length"] extends 6 ? never // 깊이 가드 — 조합 폭발 방지
	: T extends object
		? { [K in keyof T & string]:
				T[K] extends object
					? K | `${K}.${Paths<T[K], [...Depth, unknown]>}`
					: K
			}[keyof T & string]
		: never;

type PathValue<T, P extends string> =
	P extends `${infer K}.${infer Rest}`
		? K extends keyof T ? PathValue<T[K], Rest> : never
		: P extends keyof T ? T[P] : never;

interface Messages {
	auth: { login: { title: string; failed: string }; logout: string };
	common: { ok: string };
}

declare function t<P extends Paths<Messages>>(key: P): PathValue<Messages, P>;

t("auth.login.title"); // string — 자동완성 지원
// t("auth.login.titel"); // 컴파일 오류 — 오타 즉시 검출
```

`Depth` 가드는 필수다. 순환 참조 타입(트리 노드처럼 자기 자신을 참조)이 들어오면 가드 없는 `Paths`는 무한 전개를 시도한다. 또한 키 개수가 많은 실제 i18n 리소스(수천 키)에서는 `Paths` 전개 자체가 유니온 한계에 부딪힐 수 있으므로, 네임스페이스 단위로 분리해 `Paths<Messages["auth"]>`처럼 좁혀 쓰는 것이 컴파일 시간 면에서 유리하다.

## 7. 내장 intrinsic 타입과 케이스 변환

`Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize` 네 개는 타입 시스템으로 구현된 것이 아니라 컴파일러 내부 함수로 처리되는 **intrinsic 타입**이다(선언부가 `type Uppercase<S extends string> = intrinsic;`). 이들과 재귀를 조합하면 camelCase ↔ snake_case 변환을 타입 레벨에서 구현할 수 있다.

```typescript
// snake_case → camelCase
type CamelCase<S extends string> =
	S extends `${infer H}_${infer T}`
		? `${H}${CamelCase<Capitalize<T>>}`
		: S;

// camelCase → SNAKE_CASE (대문자 경계 탐지)
type SnakeCase<S extends string, Acc extends string = ""> =
	S extends `${infer C}${infer Rest}`
		? C extends Uppercase<C>
			? C extends Lowercase<C> // 숫자·기호는 대소문자 동일
				? SnakeCase<Rest, `${Acc}${C}`>
				: SnakeCase<Rest, `${Acc}_${Lowercase<C>}`>
			: SnakeCase<Rest, `${Acc}${C}`>
		: Acc;

type A1 = CamelCase<"user_profile_image">; // "userProfileImage"
type A2 = SnakeCase<"userProfileImage">;   // "user_profile_image"
```

DB 컬럼(snake_case) ↔ 도메인 모델(camelCase) 매핑 계층에서 `{ [K in keyof T as CamelCase<K & string>]: T[K] }` 형태의 키 리매핑과 결합하면, 런타임 변환 함수(예: camelcase-keys)의 반환 타입을 정확히 표현할 수 있어 `as any` 캐스팅이 사라진다.

## 8. 컴파일 성능 — 인스턴스화 비용 측정과 최적화

Template Literal 재귀 타입은 잘못 쓰면 컴파일 시간을 지배한다. 측정 도구는 두 가지다. `tsc --extendedDiagnostics`로 전체 인스턴스화 횟수(Instantiations)와 체크 시간을 보고, `tsc --generateTrace trace_dir`로 생성한 트레이스를 Perfetto UI에 올려 어떤 타입이 병목인지 찾는다.

| 패턴 | 인스턴스화 경향 | 대응 |
|---|---|---|
| 유니온 슬롯 다중 보간 | k^n 조합 폭발 | 슬롯 수 축소, 조합 전개 대신 검증형(`S extends Pattern ? S : never`)으로 전환 |
| 감싸기형 재귀 | 깊이 50 한계 + 캐시 미스 | 누산기 도입해 꼬리 재귀화 |
| 조건부 타입 중첩 분기 | 분기마다 배 증가 | 판별 키를 앞으로 빼서 얕은 분기로 재배열 |
| 거대 리터럴 유니온에 Mapped | 키 수 × 본문 비용 | 네임스페이스 분할, `Pick`으로 범위 축소 |

핵심 원칙은 "**전개(generate)보다 검증(validate)**"이다. 가능한 모든 문자열을 유니온으로 만들어 놓고 매칭하는 대신, 입력 문자열을 받아 패턴에 맞는지 조건부 타입으로 검사하면 인스턴스화가 입력 1건에 비례한다. 예를 들어 시각 문자열 `"HH:mm"` 검증은 `${Digit}${Digit}:${Digit}${Digit}` 전개(10^4 유니온)보다, 제네릭 파라미터로 받은 `S`를 분해 검사하는 쪽이 훨씬 싸다. 또한 동일 타입 인자의 재인스턴스화는 컴파일러가 캐시하므로, 자주 쓰는 조합은 별도 이름의 타입 별칭으로 고정해 캐시 적중률을 높인다.

## 9. 한계와 대안 — 어디까지 타입으로 풀 것인가

타입 레벨 문자열 처리의 실용적 한계를 정리하면 다음과 같다. 첫째, **정규식 수준의 표현력은 없다.** 백트래킹·수량자·문자 클래스가 없으므로 앵커 기반 선형 파싱만 가능하다. 복잡한 문법(예: SQL)을 타입으로 완전 파싱하는 라이브러리(ts-sql 류)는 데모로는 훌륭하지만 대형 쿼리에서 ts2589와 컴파일 지연을 피할 수 없다. 둘째, **오류 메시지가 사용자 친화적이지 않다.** 매칭 실패 시 `never`가 전파되어 실제 오류 지점과 먼 곳에서 폭발하므로, 실패 분기에서 `{ __error: "expected HH:mm format" }` 같은 브랜드 오류 타입을 반환해 진단 가능성을 높이는 패턴이 권장된다. 셋째, 런타임 검증과 이중화가 필요하다. 타입은 컴파일 타임 계약일 뿐이므로 외부 입력에는 Zod의 `z.templateLiteral()`(v4)이나 커스텀 타입 가드로 런타임 검사를 병행하고, 타입 쪽은 그 스키마에서 추론하도록 단일 소스화하는 것이 유지보수 면에서 옳다.

결론적으로 Template Literal Type은 **경로·키·식별자처럼 구조가 단순하고 길이가 짧은 문자열 DSL**에 적용할 때 투자 대비 효과가 가장 크고, 그 이상의 복잡도는 코드 생성(ts-morph)이나 런타임 스키마로 내리는 것이 총비용이 낮다.

## 참고

- TypeScript Handbook — Template Literal Types (typescriptlang.org/docs/handbook/2/template-literal-types.html)
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- microsoft/TypeScript PR #40336 — Template literal types 원 구현
- microsoft/TypeScript Wiki — Performance (인스턴스화 진단 가이드)
- type-challenges (github.com/type-challenges/type-challenges) — 문자열 조작 문제군
