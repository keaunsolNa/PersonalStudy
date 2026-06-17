Notion 원본: https://www.notion.so/3825a06fd6d3817aa807c041866529b9

# TypeScript Discriminated Union과 Exhaustiveness Checking 및 never 타입 안전 상태머신

> 2026-06-17 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 공통 리터럴 태그(discriminant)로 유니온을 좁히는 narrowing 원리를 설명한다
- never 기반 exhaustiveness 검사로 분기 누락을 컴파일 타임에 잡는다
- discriminated union으로 불가능한 상태(impossible state)를 타입에서 제거한다
- 상태 전이 함수를 타입 안전하게 설계해 잘못된 전이를 컴파일 에러로 만든다

## 1. Discriminated Union — 태그로 구분되는 합 타입

Discriminated Union(tagged union)은 각 멤버가 공통의 **리터럴 타입 필드**(discriminant)를 갖는 유니온이다. 이 태그를 `switch`나 `if`로 검사하면 control flow analysis가 해당 분기에서 타입을 정확히 좁힌다.

```typescript
type Circle = { kind: "circle"; radius: number };
type Square = { kind: "square"; side: number };
type Rectangle = { kind: "rectangle"; width: number; height: number };

type Shape = Circle | Square | Rectangle;

function area(shape: Shape): number {
	switch (shape.kind) {
		case "circle":
			return Math.PI * shape.radius ** 2; // shape: Circle 로 좁혀짐
		case "square":
			return shape.side ** 2;
		case "rectangle":
			return shape.width * shape.height;
	}
}
```

각 `case` 블록 안에서 `shape`는 해당 구체 타입으로 좁혀져 안전하다. 태그가 string/number/boolean 리터럴이어야 하고 모든 멤버가 같은 필드명을 공유해야 한다. enum이나 클래스 상속보다 가볍고 직렬화(JSON)에 자연스러운 모델링 도구다.

## 2. Exhaustiveness Checking — never로 분기 누락 잡기

모든 case를 처리하면 `default`에 도달한 `shape`는 `never`로 좁혀지고, never는 다른 타입에 할당되지 않는 성질을 이용한다.

```typescript
function assertNever(value: never): never {
	throw new Error(`Unhandled case: ${JSON.stringify(value)}`);
}

function area(shape: Shape): number {
	switch (shape.kind) {
		case "circle":
			return Math.PI * shape.radius ** 2;
		case "square":
			return shape.side ** 2;
		case "rectangle":
			return shape.width * shape.height;
		default:
			return assertNever(shape); // 모든 case 처리 시 shape는 never
	}
}
```

이제 `Triangle`을 `Shape`에 추가하면 `default`의 `shape`가 never가 아니게 되어 `assertNever(shape)`에서 **컴파일 에러**가 난다. 이 패턴은 유니온이 확장될 때 모든 사용처를 강제로 갱신하게 만드는 가장 강력한 도구다.

## 3. 불가능한 상태를 타입에서 제거하기

흔한 안티패턴은 여러 boolean/optional 필드로 상태를 표현하는 것이다. 이러면 의미 없는 조합(impossible state)이 타입상 허용된다.

```typescript
// ❌ 나쁜 모델 — 모순된 상태가 표현 가능
interface RequestState {
	isLoading: boolean;
	data?: User[];
	error?: Error;
}
```

discriminated union으로 바꾸면 가능한 상태만 정확히 4가지로 못 박힌다.

```typescript
type RequestState =
	| { status: "idle" }
	| { status: "loading" }
	| { status: "success"; data: User[] }
	| { status: "error"; error: Error };

function render(state: RequestState): string {
	switch (state.status) {
		case "idle":
			return "대기 중";
		case "loading":
			return "로딩 중...";
		case "success":
			return `${state.data.length}명 로드됨`; // data 접근 안전
		case "error":
			return `오류: ${state.error.message}`;   // error 접근 안전
	}
}
```

`loading` 상태에서 `state.data`에 접근하면 컴파일 에러다. "make illegal states unrepresentable" 원칙의 핵심 구현이다.

## 4. 타입 안전 상태 머신 — 전이까지 강제하기

상태를 union으로 모델링했다면, **전이(transition)** 도 타입으로 제약할 수 있다. 전이 가능한 (현재상태, 이벤트) 쌍만 받는 reducer를 설계한다.

```typescript
type State =
	| { status: "idle" }
	| { status: "loading" }
	| { status: "success"; data: User[] }
	| { status: "error"; error: Error };

type Event =
	| { type: "FETCH" }
	| { type: "RESOLVE"; data: User[] }
	| { type: "REJECT"; error: Error }
	| { type: "RESET" };

function reducer(state: State, event: Event): State {
	switch (state.status) {
		case "idle":
			return event.type === "FETCH" ? { status: "loading" } : state;
		case "loading":
			if (event.type === "RESOLVE") {
				return { status: "success", data: event.data };
			}
			if (event.type === "REJECT") {
				return { status: "error", error: event.error };
			}
			return state;
		case "success":
		case "error":
			return event.type === "RESET" ? { status: "idle" } : state;
		default:
			return assertNever(state);
	}
}
```

전이 규칙이 코드 구조 자체에 박혀 있어 "loading 중에만 RESOLVE를 받는다" 같은 불변식이 명시적이다. XState 같은 라이브러리가 이 방향이다.

## 5. 제네릭과 결합 — 재사용 가능한 Result 타입

```typescript
type Result<T, E = Error> =
	| { ok: true; value: T }
	| { ok: false; error: E };

function parseJson(text: string): Result<unknown> {
	try {
		return { ok: true, value: JSON.parse(text) };
	} catch (e) {
		return { ok: false, error: e instanceof Error ? e : new Error(String(e)) };
	}
}

const result = parseJson('{"a":1}');
if (result.ok) {
	console.log(result.value); // value 접근 안전
} else {
	console.error(result.error.message);
}
```

`result.ok`로 좁히기 전엔 `value`/`error` 어느 쪽도 접근 불가다. try/catch의 `unknown` 에러 타입 문제를 호출 경로에서 명시적으로 처리하게 만든다.

## 6. narrowing이 동작하는 조건과 함정

control flow narrowing은 강력하지만 몇 가지 전제가 있다. discriminant는 **리터럴 타입**이어야 한다 — `kind: string`이면 좁혀지지 않는다. 또 구조 분해 후엔 narrowing이 끊길 수 있다.

```typescript
function area(shape: Shape): number {
	const { kind } = shape; // kind만 분해
	switch (kind) {
		case "circle":
			// ❌ shape.radius 는 여전히 Shape 전체
			return Math.PI * (shape as Circle).radius ** 2;
	}
}
```

전체 객체를 분해하면 연관 필드 간 관계가 끊겨 narrowing이 실패한다. 따라서 **discriminant 검사 시에는 객체 자체(`shape.kind`)를 검사**해야 한다.

## 7. 기법 비교

| 표현 방식 | 불가능 상태 | 분기 누락 검출 | 직렬화 | 비고 |
|---|---|---|---|---|
| boolean 플래그 조합 | 허용됨(위험) | 불가 | 쉬움 | 안티패턴 |
| enum + 별도 필드 | 부분 허용 | 약함 | 보통 | 상태-데이터 분리 |
| Discriminated Union | 제거됨 | never로 강제 | 쉬움 | 권장 |
| class 상속 + instanceof | 제거됨 | 약함 | 어려움 | OOP 스타일 |

discriminated union은 JSON 친화적이고, exhaustiveness를 컴파일 타임에 강제하며, 불가능 상태를 구조적으로 배제한다. React reducer, API 응답 모델링, 도메인 이벤트 등에 1순위 도구다.

## 8. 정리 — 실무 적용 가이드

상호배타적 상태(로딩/성공/실패, 결제 단계, 폼 검증)는 항상 discriminated union을 우선 검토한다. (1) 동일 discriminant, (2) 각 상태에 그 데이터를 묶음, (3) 모든 소비처 switch에 `assertNever` default, (4) 전이가 있으면 (상태, 이벤트) reducer. 이 네 가지를 지키면 런타임에서야 발견되던 상태 버그가 컴파일 단계로 앞당겨진다.

## 9. 런타임 검증과의 결합 — 신뢰 경계에서의 좁히기

discriminated union은 컴파일 타임 도구다. 외부에서 들어온 데이터(API 응답, 메시지 큐 페이로드)는 타입 시스템이 보장하지 못하므로, 신뢰 경계에서 한 번 **런타임 검증**으로 좁혀야 한다. 타입 가드 함수가 그 다리 역할을 한다.

```typescript
function isShape(value: unknown): value is Shape {
	if (typeof value !== "object" || value === null || !("kind" in value)) {
		return false;
	}
	const kind = (value as { kind: unknown }).kind;
	return kind === "circle" || kind === "square" || kind === "rectangle";
}
```

수작업 타입 가드는 union이 커지면 유지보수가 어렵다. 실무에서는 Zod 같은 스키마 검증 라이브러리로 런타임 파싱과 정적 타입을 한 번에 얻는다.

```typescript
import { z } from "zod";

const ShapeSchema = z.discriminatedUnion("kind", [
	z.object({ kind: z.literal("circle"), radius: z.number() }),
	z.object({ kind: z.literal("square"), side: z.number() }),
	z.object({ kind: z.literal("rectangle"), width: z.number(), height: z.number() }),
]);

type Shape = z.infer<typeof ShapeSchema>;
const shape = ShapeSchema.parse(JSON.parse(input));
```

`z.discriminatedUnion`은 일반 `z.union`보다 빠르고 에러 메시지가 정확하다 — 스키마가 단일 진실 공급원이 되어 타입과 검증 로직의 불일치가 구조적으로 사라진다.

## 10. 성능과 대규모 union — lookup 테이블 패턴

union 멤버가 수십 개로 늘면 거대한 switch는 가독성과 유지보수가 떨어진다. 분기를 **데이터(매핑 객체)** 로 바꾸되 exhaustiveness는 `satisfies`로 보장하는 패턴이 깔끔하다.

```typescript
type Kind = Shape["kind"];

const areaHandlers = {
	circle: (s: Circle) => Math.PI * s.radius ** 2,
	square: (s: Square) => s.side ** 2,
	rectangle: (s: Rectangle) => s.width * s.height,
} satisfies { [K in Kind]: (s: Extract<Shape, { kind: K }>) => number };

function area(shape: Shape): number {
	const handler = areaHandlers[shape.kind] as (s: Shape) => number;
	return handler(shape);
}
```

`satisfies { [K in Kind]: ... }` 덕분에 새 도형을 union에 추가하면 매핑 객체에서 키 누락이 컴파일 에러가 된다. 분기가 5~6개 이하면 switch + assertNever가 더 읽기 좝고, 그 이상이거나 핸들러를 조합해야 하면 lookup 테이블이 유리하다 — 둘 다 exhaustiveness를 컴파일 타임에 강제한다는 점이 핵심이다.

## 참고

- TypeScript Handbook — "Narrowing", "Discriminated Unions", "Exhaustiveness checking"
- Effective TypeScript (Dan Vanderkam) — Item 28~33 (상태 모델링)
- "Making Impossible States Impossible" (Richard Feldman)
- XState Documentation — finite state machines & statecharts
