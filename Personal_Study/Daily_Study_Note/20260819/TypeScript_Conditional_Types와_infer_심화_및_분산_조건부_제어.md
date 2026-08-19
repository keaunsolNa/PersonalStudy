Notion 원본: https://app.notion.com/p/3c15a06fd6d3817ebe0ff43c75a04e18?pvs=204

# TypeScript Conditional Types와 infer 심화 및 분산 조건부 제어

> 2026-08-19 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 네이키드 타입 파라미터가 유니온을 분산시키는 조건과 `[T]` 로 이를 차단하는 원리를 구분한다
- `infer` 의 공변·반변 위치가 각각 유니온과 인터섹션을 만드는 이유를 추론 규칙으로 설명한다
- 조건부 타입의 지연 평가(deferred evaluation)가 제네릭 함수 본문에서 일으키는 할당 오류를 해소한다
- 재귀 조건부 타입의 깊이 한계와 꼬리 재귀 제거 최적화 조건을 실측으로 확인한다

## 1. 조건부 타입의 판정 기준은 할당 가능성이다

`T extends U ? X : Y` 의 `extends` 는 클래스 상속이 아니라 **할당 가능성(assignability)** 검사다. `T` 의 값을 `U` 타입 자리에 넣을 수 있으면 참 분기다.

```ts
type A = 'a' extends string ? 1 : 0;        // 1  리터럴은 string 에 할당 가능
type B = string extends 'a' ? 1 : 0;        // 0  반대는 불가
type C = { x: 1; y: 2 } extends { x: 1 } ? 1 : 0;  // 1  구조적 부분 타입
type D = never extends string ? 1 : 0;      // 1  never 는 모든 타입에 할당 가능
type E = any extends string ? 1 : 0;        // 1 | 0  any 는 양쪽 분기 모두
```

`any` 가 두 분기를 모두 반환하는 것은 사양에 명시된 특례다. `any` 는 어떤 타입에도 할당 가능하고 어떤 타입도 `any` 에 할당 가능하므로 판정이 불가능하고, 컴파일러는 두 결과의 유니온을 내놓는다. 이 성질을 이용해 `any` 를 탐지할 수 있다.

```ts
type IsAny<T> = 0 extends 1 & T ? true : false;
type T1 = IsAny<any>;      // true
type T2 = IsAny<unknown>;  // false
type T3 = IsAny<number>;   // false
```

`1 & T` 는 `T` 가 `any` 일 때만 `any` 로 붕괴하고, `0 extends any` 는 참이다. 다른 타입이면 `1 & number` 같은 결과가 되어 `0` 이 할당 불가하므로 거짓이다. `unknown` 과 `never` 탐지도 비슷한 트릭을 쓴다.

```ts
type IsNever<T> = [T] extends [never] ? true : false;
type IsUnknown<T> = unknown extends T
	? IsAny<T> extends true ? false : true
	: false;
```

`IsNever` 에서 `[T]` 튜플로 감싸는 이유는 다음 절의 분산 때문이다.

## 2. 분산 조건부 타입의 발동 조건

조건부 타입의 검사 대상이 **네이키드 타입 파라미터**(다른 타입으로 감싸이지 않은 순수 `T`)이고, 그 `T` 에 유니온이 들어오면 컴파일러는 유니온의 각 멤버에 대해 조건부를 개별 적용한 뒤 결과를 유니온으로 합친다.

```ts
type ToArray<T> = T extends unknown ? T[] : never;
type R1 = ToArray<string | number>;   // string[] | number[]

type ToArrayNoDist<T> = [T] extends [unknown] ? T[] : never;
type R2 = ToArrayNoDist<string | number>;  // (string | number)[]
```

`R1` 과 `R2` 의 차이가 분산의 전부다. `[T]` 로 감싸면 `T` 가 네이키드가 아니게 되어 분산이 꺼진다.

분산의 부수 효과 중 가장 자주 부딪히는 것은 `never` 다. `never` 는 "멤버가 0개인 유니온"이므로, 분산이 일어나면 반복할 대상이 없어 결과도 `never` 가 된다.

```ts
type Wrap<T> = T extends unknown ? { value: T } : never;
type W = Wrap<never>;   // never — { value: never } 가 아님

type WrapSafe<T> = [T] extends [unknown] ? { value: T } : never;
type WS = WrapSafe<never>;  // { value: never }
```

표준 유틸리티 타입 상당수가 분산에 의존한다. `Exclude<T, U> = T extends U ? never : T` 는 분산이 있어야 유니온에서 멤버를 걸러낼 수 있다. `NonNullable<T>` 도 마찬가지다. 반면 `Extract` 와 조합할 때 `never` 입력이 들어오면 조용히 `never` 를 반환하므로, 라이브러리 경계에서는 명시적 `never` 가드를 두는 편이 안전하다.

| 형태 | 분산 | 비고 |
|---|---|---|
| `T extends U ? X : Y` | 발동 | T 가 네이키드 |
| `[T] extends [U] ? X : Y` | 차단 | 튜플 래핑 |
| `T[] extends U ? X : Y` | 차단 | 배열로 감싸짐 |
| `keyof T extends U ? X : Y` | 차단 | keyof 로 감싸짐 |
| `T extends U ? X : Y` (T = never) | 결과 never | 빈 유니온 |
| `T & {} extends U ? ...` | 발동 | `& {}` 는 네이키드 유지 |

## 3. infer 의 위치가 결과를 바꾼다

`infer` 는 조건부 타입의 참 분기에서만 쓸 수 있고, 패턴 매칭으로 타입을 추출한다. 같은 이름의 `infer` 가 여러 위치에 나타나면 컴파일러가 후보들을 합치는데, **합치는 방식이 위치의 변성(variance)에 따라 달라진다**.

```ts
// 공변 위치(반환 타입, 프로퍼티) → 유니온
type Cov<T> = T extends { a: infer U; b: infer U } ? U : never;
type C1 = Cov<{ a: string; b: number }>;   // string | number

// 반변 위치(함수 파라미터) → 인터섹션
type Contra<T> = T extends {
	a: (x: infer U) => void;
	b: (x: infer U) => void;
} ? U : never;
type C2 = Contra<{ a: (x: string) => void; b: (x: number) => void }>;
// string & number → never
```

함수 파라미터는 반변 위치다. "이 함수 자리에 들어갈 수 있으려면 파라미터가 더 넓어야 한다"는 규칙 때문에, 여러 후보를 만족시키려면 교집합이 필요하다. 이 성질이 유니온을 인터섹션으로 바꾸는 유명한 트릭의 기반이다.

```ts
type UnionToIntersection<U> =
	(U extends unknown ? (arg: U) => void : never) extends (arg: infer I) => void
		? I
		: never;

type UI = UnionToIntersection<{ a: 1 } | { b: 2 }>;  // { a: 1 } & { b: 2 }
```

첫 단계에서 분산이 유니온을 `((arg: {a:1}) => void) | ((arg: {b:2}) => void)` 로 만들고, 두 번째 단계에서 반변 추론이 파라미터를 인터섹션으로 합친다.

여기에서 한 걸음 더 나가면 유니온의 마지막 멤버를 뽑을 수 있고, 이를 재귀로 돌리면 유니온을 튜플로 변환할 수 있다.

```ts
type LastOf<U> =
	UnionToIntersection<U extends unknown ? () => U : never> extends () => infer R
		? R
		: never;

type UnionToTuple<U, Acc extends unknown[] = []> =
	[U] extends [never]
		? Acc
		: LastOf<U> extends infer L
			? UnionToTuple<Exclude<U, L>, [L, ...Acc]>
			: never;

type Tup = UnionToTuple<'a' | 'b' | 'c'>;  // ['a', 'b', 'c']
```

주의할 점은 **유니온의 순서가 사양상 보장되지 않는다**는 것이다. 실제로는 선언 순서를 따르는 경우가 많지만 컴파일러 내부 정규화에 의존하므로, 이 결과에 로직을 걸면 TypeScript 버전 업그레이드에서 깨질 수 있다. 실무에서는 순서에 의존하지 않는 형태로 쓰거나, 순서가 필요하면 처음부터 튜플로 선언한다.

## 4. infer 의 extends 제약과 패턴 정교화

TypeScript 4.7 부터 `infer` 에 제약을 걸 수 있다.

```ts
// 4.7 이전: 두 단계 조건부 필요
type FirstNumOld<T> = T extends [infer F, ...unknown[]]
	? F extends number ? F : never
	: never;

// 4.7 이후: 한 줄
type FirstNum<T> = T extends [infer F extends number, ...unknown[]] ? F : never;

type F1 = FirstNum<[42, 'x']>;   // 42
type F2 = FirstNum<['x', 42]>;   // never
```

이 제약은 단순 축약이 아니다. 제약이 붙으면 추론 단계에서 **리터럴 타입을 보존**하는 방향으로 동작이 달라진다. 특히 템플릿 리터럴에서 숫자를 뽑을 때 차이가 크다.

```ts
type ExtractNum<S> = S extends `${infer N extends number}` ? N : never;
type N1 = ExtractNum<'42'>;   // 42 (number 리터럴)
// 제약이 없으면 '42' (string 리터럴)로 남는다
```

문자열 파싱과 조합하면 라우트 파라미터 추출 같은 실용 타입을 만들 수 있다.

```ts
type PathParams<S extends string> =
	S extends `${string}:${infer Param}/${infer Rest}`
		? Param | PathParams<`/${Rest}`>
		: S extends `${string}:${infer Param}`
			? Param
			: never;

type P = PathParams<'/users/:userId/posts/:postId'>;  // "userId" | "postId"

function route<S extends string>(
	path: S,
	handler: (params: Record<PathParams<S>, string>) => void
): void {
	// 구현 생략
}

route('/users/:userId/posts/:postId', (params) => {
	params.userId;   // OK
	params.postId;   // OK
	// params.unknown;  // 컴파일 오류
});
```

## 5. 지연 평가와 제네릭 함수 본문의 할당 오류

조건부 타입에 아직 확정되지 않은 타입 파라미터가 들어 있으면 컴파일러는 평가를 **지연**한다. 이 상태의 타입은 어떤 구체 타입과도 할당 가능성을 판정할 수 없어, 함수 본문 안에서 흔한 오류를 만든다.

```ts
type Boxed<T> = T extends string ? { text: T } : { value: T };

function box<T>(input: T): Boxed<T> {
	if (typeof input === 'string') {
		return { text: input };   // 오류: Boxed<T> 에 할당 불가
	}
	return { value: input };      // 오류
}
```

런타임 `typeof` 검사로 좁혀도 타입 파라미터 `T` 자체는 좁혀지지 않는다. `T` 는 호출자가 정하는 것이고, 함수 본문은 모든 가능한 `T` 에 대해 검증되어야 하기 때문이다.

해결책은 세 가지이며 각각 대가가 다르다. 첫째, 오버로드 시그니처로 외부 계약과 내부 구현을 분리한다.

```ts
function box(input: string): { text: string };
function box<T>(input: T): { value: T };
function box(input: unknown): unknown {
	if (typeof input === 'string') {
		return { text: input };
	}
	return { value: input };
}
```

외부에서 보이는 타입은 정확하고 내부만 느슨해진다. 오버로드 목록과 구현의 일치는 컴파일러가 완전히 검증하지 못하므로 구현 정확성은 테스트로 담보한다.

둘째, 단일 `as` 단언으로 좁혀진 경계를 명시한다. 셋째, 조건부 타입 대신 판별 유니온 반환으로 설계를 바꾼다. 세 번째가 가장 안전하지만 API 형태가 달라지므로 라이브러리 경계에서만 가능하다.

실무 판단 기준은 명확하다. **조건부 반환 타입은 공개 API 의 표현력을 위해 쓰고, 구현부는 오버로드나 단언으로 한 지점에서만 느슨하게 만든다.** 구현 전체를 `any` 로 흘리면 조건부 타입을 쓴 의미가 사라진다.

## 6. 재귀 깊이 한계와 꼬리 재귀 제거

재귀 조건부 타입은 기본적으로 인스턴스화 깊이 제한에 걸린다. 오류 메시지는 `Type instantiation is excessively deep and possibly infinite`(TS2589) 다. 일반 재귀는 약 50 단계, 꼬리 재귀 형태로 인식되면 1,000 단계까지 허용된다(TypeScript 4.5 의 tail-recursion elimination).

꼬리 재귀로 인식되려면 **조건부의 분기 결과가 곧바로 자기 자신의 호출**이어야 한다. 결과를 다른 타입으로 감싸면 인식되지 않는다.

```ts
// 꼬리 재귀 아님 — [..., ] 로 감싸므로 스택이 쌓인다
type ReverseBad<T extends unknown[]> =
	T extends [infer H, ...infer R] ? [...ReverseBad<R>, H] : [];

// 꼬리 재귀 — 누산기 사용, 분기 결과가 순수 재귀 호출
type Reverse<T extends unknown[], Acc extends unknown[] = []> =
	T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;
```

누산기 패턴은 재귀 타입 작성의 기본형이다. 길이 500 짜리 튜플 뒤집기가 `ReverseBad` 로는 TS2589 인데 `Reverse` 로는 통과한다.

깊이 제한을 우회하려는 시도보다 중요한 것은 **컴파일 시간 비용**이다. 재귀 타입은 인스턴스화마다 타입 객체를 만들고 캐시하므로, 깊은 재귀가 여러 곳에서 쓰이면 tsc 시간이 선형이 아니라 급격히 늘어난다. 진단은 `--diagnostics` 와 트레이스로 한다.

```bash
tsc --noEmit --extendedDiagnostics
# Instantiations, Types, Memory used 확인

tsc --noEmit --generateTrace ./trace
# trace/trace.json 을 chrome://tracing 또는 @typescript/analyze-trace 로 분석
npx @typescript/analyze-trace ./trace
```

`Instantiations` 가 수백만을 넘으면 재귀 타입이 원인일 가능성이 높다. 실무 임계선으로는 인스턴스화 100만 건을 넘으면 설계를 재검토한다. 문자열을 문자 단위로 재귀 파싱하는 타입은 특히 비싸므로, 런타임 검증(Zod 등)으로 넘기고 타입은 느슨하게 두는 판단이 합리적일 때가 많다.

## 7. 실전 조합 — 깊은 객체 경로 타입

조건부·재귀·템플릿 리터럴을 합치면 lodash `get` 같은 API 를 타입 안전하게 만들 수 있다.

```ts
type Paths<T, Depth extends unknown[] = []> =
	Depth['length'] extends 4
		? never
		: T extends readonly unknown[]
			? never
			: T extends object
				? {
						[K in keyof T & string]:
							| K
							| (T[K] extends object ? `${K}.${Paths<T[K], [...Depth, 1]>}` : never);
					}[keyof T & string]
				: never;

type ValueAt<T, P extends string> =
	P extends `${infer K}.${infer Rest}`
		? K extends keyof T ? ValueAt<T[K], Rest> : never
		: P extends keyof T ? T[P] : never;

declare function get<T, P extends Paths<T>>(obj: T, path: P): ValueAt<T, P>;

const config = {
	server: { host: 'localhost', port: 8080, tls: { enabled: true } },
	name: 'api',
};

const port = get(config, 'server.port');        // number
const tls = get(config, 'server.tls.enabled');  // boolean
// get(config, 'server.hostname');              // 컴파일 오류
```

`Depth` 누산기로 재귀 깊이를 4로 제한한 점이 실용상 핵심이다. 제한이 없으면 순환 참조가 있는 타입에서 무한 재귀에 빠지고, 깊이가 있는 설정 객체에서 인스턴스화가 폭발한다. 실무 경험상 깊이 3~5 가 표현력과 컴파일 시간의 균형점이다.

## 참고

- TypeScript Handbook — Conditional Types, Type Inference in Conditional Types
- TypeScript Release Notes 4.5 — Tail-Recursion Elimination on Conditional Types
- TypeScript Release Notes 4.7 — `extends` Constraints on `infer` Type Variables
- microsoft/TypeScript Wiki — Performance (Writing Easy-to-Compile Code)
- `@typescript/analyze-trace` 저장소 문서
