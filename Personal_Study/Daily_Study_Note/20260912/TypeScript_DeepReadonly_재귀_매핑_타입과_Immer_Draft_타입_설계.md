Notion 원본: https://app.notion.com/p/3d95a06fd6d381ae9b70db076b6084c6?pvs=204

# TypeScript DeepReadonly 재귀 매핑 타입과 Immer Draft 타입 설계

> 2026-09-12 신규 주제 · 확장 대상: TypeScript Mapped Types / 불변성 모델링

## 학습 목표

- `readonly` 수정자가 얕은 수준에만 적용되는 이유를 타입 시스템 관점에서 규명한다
- 재귀 매핑 타입으로 `DeepReadonly` 를 작성하고 내장 객체·함수·유니온 경계를 처리한다
- Immer 의 `Draft` / `Immutable` 타입이 `WritableDraft` 로 수정자를 역전시키는 구조를 분석한다
- 재귀 타입의 인스턴스화 비용을 측정해 어디까지 깊이를 허용할지 결정한다

## 1. readonly 는 왜 얕은가

TypeScript 의 `readonly` 는 프로퍼티 수정자다. 프로퍼티 하나에 붙어 그 슬롯에 대한 할당을 금지하며, 그 슬롯이 가리키는 값의 내부까지는 관여하지 않는다.

```ts
interface Config {
	readonly name: string;
	readonly retry: { max: number; backoffMs: number };
	readonly hosts: string[];
}

declare const cfg: Config;

cfg.name = 'x';            // Error — 슬롯 자체는 보호된다
cfg.retry = { max: 1, backoffMs: 0 }; // Error
cfg.retry.max = 99;        // OK — 내부는 전혀 보호되지 않는다
cfg.hosts.push('evil');    // OK — 배열 메서드도 그대로 살아 있다
```

이것은 버그가 아니라 구조적 타입 시스템의 일관된 귀결이다. `readonly` 는 값의 표현이 아니라 **접근 경로에 대한 제약**이고, `cfg.retry` 로 얻은 참조는 타입 `{ max: number; backoffMs: number }` 를 가진 별개의 표현식이다. 그 타입에는 `readonly` 가 없으므로 할당이 허용된다.

더 중요한 사실은 `readonly` 가 **할당 가능성 판정에 거의 참여하지 않는다**는 점이다.

```ts
interface Mutable { a: number }
interface Frozen { readonly a: number }

declare let m: Mutable;
declare let f: Frozen;

m = f; // OK — readonly 가 벗겨진다
f = m; // OK — readonly 가 붙는다
```

프로퍼티 수정자 차이는 객체 타입 간 호환성 검사에서 무시된다. 오직 `readonly` 배열과 가변 배열 사이에서만 단방향 제약이 걸린다(`readonly T[]` 에 `T[]` 는 대입 가능, 역은 불가). 즉 `readonly` 로 불변성을 "보장"할 수는 없고, **의도를 표현하고 직접 할당 실수를 잡는 수준**까지가 현실적 기대치다. 이 전제를 잊고 `DeepReadonly` 로 안전성을 확보했다고 믿는 것이 가장 흔한 오해다.

## 2. DeepReadonly 를 직접 작성하기

가장 단순한 재귀 매핑 타입은 이렇게 시작한다.

```ts
type DeepReadonlyNaive<T> = {
	readonly [K in keyof T]: DeepReadonlyNaive<T[K]>;
};
```

동작하지만 실무 데이터에는 곧바로 깨진다. `T` 가 `Date`, `Map`, 함수, 유니온일 때 모두 잘못된 결과가 나온다. `Date` 에 매핑 타입을 적용하면 메서드 프로퍼티가 `readonly` 로 재작성되면서 `getTime` 의 `this` 컨텍스트가 흔들리고, 함수 타입에 매핑 타입을 걸면 호출 시그니처가 사라져 호출 불가능한 객체가 된다.

경계를 하나씩 처리한 버전이다.

```ts
type Primitive = string | number | boolean | bigint | symbol | null | undefined;

type DeepReadonly<T> = T extends Primitive
	? T
	: T extends (...args: never[]) => unknown
		? T // 함수는 그대로 — 호출 시그니처 보존
		: T extends readonly (infer U)[]
			? ReadonlyArray<DeepReadonly<U>>
			: T extends Map<infer K, infer V>
				? ReadonlyMap<DeepReadonly<K>, DeepReadonly<V>>
				: T extends Set<infer U>
					? ReadonlySet<DeepReadonly<U>>
					: T extends Date | RegExp | Error
						? T // 내장 가변 객체는 래핑하지 않는다
						: { readonly [K in keyof T]: DeepReadonly<T[K]> };
```

몇 가지 설계 판단이 들어 있다.

**함수 분기의 `(...args: never[]) => unknown`** — `(...args: any[]) => any` 를 쓰면 `any` 가 전파되어 의도치 않은 분기 매칭이 생긴다. 파라미터 위치는 반공변이므로 `never[]` 가 모든 함수 타입을 받는 최상위 패턴이 되고, 반환 위치는 공변이므로 `unknown` 이 최상위다. 이 조합이 "모든 함수"를 정확히 표현한다.

**튜플 보존** — `T extends readonly (infer U)[]` 는 튜플도 매칭하는데, `ReadonlyArray<...>` 로 바꾸면 길이 정보와 위치별 타입이 사라진다. 튜플을 보존하려면 분기를 추가한다.

```ts
type DeepReadonlyArray<T> = T extends readonly [infer Head, ...infer Tail]
	? readonly [DeepReadonly<Head>, ...DeepReadonlyArray<Tail>]
	: T extends readonly (infer U)[]
		? ReadonlyArray<DeepReadonly<U>>
		: never;
```

가변 튜플 패턴 `[infer Head, ...infer Tail]` 로 하나씩 벗기며 재귀한다. 튜플 길이가 100 을 넘으면 인스턴스화 깊이 한계에 닿으므로 실무 튜플에만 쓴다.

**유니온의 분산** — 조건부 타입은 네이키드 타입 파라미터에서 자동 분산된다. `DeepReadonly<{ a: 1 } | { b: 2 }>` 는 `DeepReadonly<{a:1}> | DeepReadonly<{b:2}>` 로 풀리는데, 이건 대개 원하는 동작이다. 다만 분산을 막아야 할 때는 `[T] extends [Primitive]` 처럼 튜플로 감싼다.

**옵셔널 수정자 보존** — 홈모픽(homomorphic) 매핑 타입, 즉 `{ [K in keyof T]: ... }` 형태는 `readonly` 와 `?` 수정자를 원본에서 복사한다. `{ [K in keyof T as K]: ... }` 처럼 `as` 절을 넣거나 `keyof T` 가 아닌 키 소스를 쓰면 홈모픽 성질이 깨지고 옵셔널이 사라진다. `DeepReadonly` 에서 키 필터링을 하고 싶은 유혹이 있어도 별도 타입으로 분리하는 편이 안전하다.

검증 코드로 확인한다.

```ts
type Nested = {
	id: number;
	meta: { tags: string[]; createdAt: Date; owner?: { name: string } };
	handler: (x: number) => void;
	cache: Map<string, { hits: number }>;
};

type Frozen = DeepReadonly<Nested>;

declare const f: Frozen;
f.meta.tags.push('x');       // Error — ReadonlyArray 에 push 없음
f.meta.owner!.name = 'y';    // Error — 중첩 readonly 적용
f.meta.createdAt.setTime(0); // OK — Date 는 의도적으로 통과
f.handler(1);                // OK — 호출 시그니처 보존
f.cache.set('a', { hits: 1 }); // Error — ReadonlyMap 으로 치환
```

`Date` 가 통과하는 것을 버그로 볼지 사양으로 볼지는 팀이 결정한다. 진짜로 막고 싶다면 `Readonly<Pick<Date, 'getTime' | 'toISOString'>>` 같은 축소 타입으로 대체하되, 그러면 `Date` 를 요구하는 함수에 넘길 수 없게 되므로 실용성이 떨어진다.

## 3. 반대 방향 — DeepMutable 과 수정자 제거

`-readonly` 수정자로 제거도 가능하다. 테스트 픽스처를 만들거나 API 응답 타입을 로컬에서 수정 가능하게 풀 때 쓴다.

```ts
type DeepMutable<T> = T extends Primitive
	? T
	: T extends (...args: never[]) => unknown
		? T
		: T extends ReadonlyArray<infer U>
			? DeepMutable<U>[]
			: T extends ReadonlyMap<infer K, infer V>
				? Map<DeepMutable<K>, DeepMutable<V>>
				: { -readonly [K in keyof T]: DeepMutable<T[K]> };
```

`-readonly` 와 `-?` 는 홈모픽 매핑 타입에서만 의미가 있다. `Required<T>` 가 `{ [K in keyof T]-?: T[K] }` 로 정의된 것이 표준 예다.

| 유틸리티 | 정의 골자 | 깊이 | 내장 객체 처리 |
|---|---|---|---|
| `Readonly<T>` | `{ readonly [K in keyof T]: T[K] }` | 1단 | 무관 |
| `DeepReadonly<T>` | 재귀 + 조건부 분기 | 무제한(깊이 한계 내) | 직접 화이트리스트 필요 |
| Immer `Immutable<T>` | 재귀 + `Draft` 역함수 | 무제한 | Date/Map/Set 전용 분기 내장 |
| `as const` | 값 위치 리터럴 고정 | 리터럴 구조 전체 | 해당 없음 |
| `Object.freeze` | 런타임 1단 동결 | 1단 | 런타임 동작 |

`as const` 는 타입 유틸리티가 아니라 값 표현식의 위드닝을 막는 어서션이지만, 리터럴 전체를 깊게 `readonly` 로 만들기 때문에 상수 테이블에는 이쪽이 간단하다.

```ts
const ROLES = {
	admin: { level: 3, perms: ['read', 'write', 'delete'] },
	viewer: { level: 1, perms: ['read'] },
} as const;

type Role = keyof typeof ROLES;                    // 'admin' | 'viewer'
type Perm = (typeof ROLES)[Role]['perms'][number]; // 'read' | 'write' | 'delete'
```

`as const` 는 컴파일 타임에 전파만 하므로 인스턴스화 비용이 사실상 없다. 재귀 조건부 타입을 쓰기 전에 `as const` 로 해결되는지 먼저 확인하는 것이 성능상 이득이다.

## 4. Immer 의 Draft 타입 — 수정자를 역전시키는 설계

Immer 는 "불변 데이터를 가변처럼 쓰고 결과만 불변으로 받는다"를 Proxy 로 구현한다. 타입 층에서는 두 방향 변환이 서로 역함수가 되어야 한다.

```ts
import { produce, type Draft, type Immutable } from 'immer';

type State = Immutable<{
	users: { id: number; name: string; tags: string[] }[];
	selectedId: number | null;
}>;

const next = produce(current, (draft) => {
	// draft 는 WritableDraft<State> — 모든 readonly 가 벗겨진 형태
	draft.users[0].tags.push('vip');
	draft.selectedId = 1;
});
// next 는 다시 State — readonly 복원
```

`produce` 의 시그니처가 이 왕복을 강제한다. 개념적으로는 다음 형태다.

```ts
declare function produce<Base>(
	base: Base,
	recipe: (draft: Draft<Base>) => void | Draft<Base>,
): Base;
```

`Draft<T>` 는 `DeepMutable` 과 같은 일을 하되 Immer 가 실제로 프록시로 감쌀 수 있는 타입만 벗긴다. `Map`/`Set` 은 `enableMapSet()` 플러그인을 켠 경우에만 드래프트 대상이므로, 타입 층에서도 `Draft<ReadonlyMap<K,V>>` 가 `Map<Draft<K>, Draft<V>>` 로 매핑된다. 클래스 인스턴스는 `[immerable]` 심볼이 없으면 드래프트화되지 않고 그대로 통과한다 — 타입은 벗겨지는데 런타임은 원본을 공유하는 불일치가 생기는 지점이라, 도메인 클래스를 상태에 넣는 설계는 피하는 편이 안전하다.

`produce` 의 반환 타입이 `Base` 라는 점이 실용적으로 중요하다. 드래프트에서 무엇을 하든 결과 타입은 입력과 동일하므로, `State` 가 `Immutable<...>` 로 선언돼 있으면 상태 트리 전체가 계속 `readonly` 로 유지된다. 리듀서 체인 전체에서 불변 계약이 보존된다는 뜻이다.

**커링 형태**는 타입 추론 방향이 달라진다.

```ts
// 커링 producer — 첫 인자로 recipe 만 받고 함수를 반환
const toggleUser = produce((draft: Draft<State>, id: number) => {
	const u = draft.users.find((x) => x.id === id);
	if (u) {
		u.tags = u.tags.includes('vip') ? [] : ['vip'];
	}
});

const next2: State = toggleUser(current, 7);
```

커링 형태에서는 `draft` 파라미터에 **명시적 애너테이션이 필수**다. 추론할 base 값이 아직 없으므로 체커가 드래프트 타입을 알아낼 근거가 없다. 애너테이션을 빼면 `draft: any` 로 떨어지고 내부 오타가 전부 통과한다. 커링 producer 를 쓰는 코드베이스에서 타입 안전이 조용히 무너지는 전형적 원인이다.

`original` 과 `current` 헬퍼도 타입 경계가 있다.

```ts
import { original, current, isDraft } from 'immer';

produce(state, (draft) => {
	const before = original(draft.users); // Draft 를 벗긴 원본 참조
	const snapshot = current(draft.users); // 지금까지의 수정이 반영된 순수 복사본
	if (isDraft(draft.users)) {
		// 타입 가드로 Draft 여부 판정
	}
});
```

`original` 은 `T | undefined` 를 반환한다 — 드래프트가 아닌 값을 넘기면 `undefined` 이므로 옵셔널 체이닝이 필요하다. `current` 는 깊은 복사를 수행하므로 큰 서브트리에 대해 루프 안에서 호출하면 즉시 성능 문제가 된다. 로깅이나 비교 목적으로만 쓰고, 비교는 가능하면 `produce` 밖에서 이전/이후 참조 동등성으로 판정한다.

## 5. 구조적 공유와 참조 동등성

Immer 의 실질적 가치는 타입이 아니라 **구조적 공유**다. 수정된 경로의 노드만 새로 만들고 나머지는 원본 참조를 재사용한다.

```ts
const s1 = { a: { x: 1 }, b: { y: 2 } };
const s2 = produce(s1, (d) => {
	d.a.x = 9;
});

s2 !== s1;     // true — 루트는 새 객체
s2.a !== s1.a; // true — 수정 경로는 새 객체
s2.b === s1.b; // true — 미수정 서브트리는 참조 공유
```

이 성질이 React `memo` / `useMemo` / Zustand selector 의 얕은 비교와 정확히 맞물린다. 반면 직접 `structuredClone` 이나 `JSON.parse(JSON.stringify(...))` 로 복사하면 모든 참조가 바뀌어 전체 리렌더가 발생한다. 실측 감각으로, 1만 노드 트리에서 한 노드를 수정할 때 전체 깊은 복사는 트리 크기에 비례하지만 Immer 는 수정 경로 깊이에 비례한다. 이 차이가 리스트 편집 UI 의 입력 지연을 결정한다.

Proxy 오버헤드는 공짜가 아니다. 드래프트 접근마다 트랩이 실행되므로, 수십만 회 반복 루프를 드래프트 안에서 돌리면 순수 가변 배열 조작보다 느리다. 이럴 때는 `produce` 밖에서 평범한 배열을 만들고 드래프트에 한 번 대입한다.

```ts
const next3 = produce(state, (draft) => {
	// 나쁜 예: 10만 번 트랩 실행
	// for (let i = 0; i < 100000; i++) { draft.items.push(make(i)); }

	// 좋은 예: 밖에서 만들어 한 번 대입
	draft.items = Array.from({ length: 100000 }, (_, i) => make(i));
});
```

## 6. 재귀 타입의 컴파일 비용 측정

`DeepReadonly` 류는 타입 체크 시간을 먹는다. 상태 트리 타입이 깊고 넓으면 인스턴스화 수가 곱으로 늘어난다. 측정 없이 도입하면 나중에 원인을 찾기 어렵다.

```bash
# 총량 파악
tsc --noEmit --extendedDiagnostics
# Instantiations / Types / Memory used / Check time

# 어느 타입이 시간을 먹는지
tsc --noEmit --generateTrace ./trace
npx @typescript/analyze-trace ./trace
```

경험적 기준선으로, 중간 규모 프론트엔드 프로젝트에서 `Instantiations` 가 100만을 넘으면 체크 시간이 체감된다. 500만을 넘으면 IDE 응답이 느려지고 `tsserver` 메모리가 불안해진다. `DeepReadonly` 를 상태 트리 루트에 한 번만 적용하는 것과, 컴포넌트 props 마다 적용하는 것의 차이가 여기서 갈린다.

완화 전략 세 가지.

**결과를 인터페이스로 고정** — 파생 타입을 매번 계산하지 말고 한 번 계산해 별칭으로 둔다. 타입 별칭은 지연 평가되므로 여러 곳에서 참조하면 여러 번 인스턴스화되지만, `interface` 로 선언 병합해 구체화하면 캐시된다. 실무에서는 상태 루트 타입만 `DeepReadonly` 를 통과시키고 하위 컴포넌트에는 그 타입의 프로퍼티를 조회해 넘긴다.

**깊이 제한** — 재귀 깊이를 명시적으로 끊는다.

```ts
type Prev = [never, 0, 1, 2, 3, 4, 5];

type DeepReadonlyN<T, D extends number = 5> = D extends 0
	? T
	: T extends Primitive
		? T
		: { readonly [K in keyof T]: DeepReadonlyN<T[K], Prev[D]> };
```

깊이 카운터를 튜플 인덱싱으로 감소시켜 무한 재귀와 폭발을 함께 막는다. 상태 트리가 5단을 넘는다면 설계 자체를 재검토할 신호이기도 하다.

**Immer 의 기성 타입 사용** — 직접 만든 `DeepReadonly` 대신 `Immutable<T>` 를 쓰면 내장 객체 분기와 깊이 처리가 이미 검증돼 있고, `Draft` 와의 역함수 관계가 보장된다. 자체 구현은 Immer 를 쓰지 않는 프로젝트에서만 의미가 있다.

## 7. 런타임 불변성과의 분리

타입 층의 `readonly` 는 컴파일 후 사라진다. 외부에서 들어온 JSON, `as` 어서션, `any` 경유 접근은 전부 우회로다. 진짜 동결이 필요하면 런타임 수단을 병행한다.

```ts
function deepFreeze<T>(obj: T): DeepReadonly<T> {
	if (obj && typeof obj === 'object' && !Object.isFrozen(obj)) {
		Object.freeze(obj);
		for (const key of Object.getOwnPropertyNames(obj)) {
			deepFreeze((obj as Record<string, unknown>)[key]);
		}
	}
	return obj as DeepReadonly<T>;
}
```

Immer 는 개발 모드에서 `produce` 결과를 자동 `freeze` 한다(`setAutoFreeze(true)` 가 기본). 프로덕션에서 성능을 위해 끄는 선택이 가능하지만, 끄면 결과 객체를 실수로 수정했을 때 조용히 상태가 오염된다. 개발에서 켜 두고 프로덕션에서 끄는 조합이 일반적이며, 이 경우 개발에서만 재현되는 `TypeError: Cannot assign to read only property` 가 오히려 유용한 조기 경보다.

정리하면 층이 세 개다. `readonly` 타입은 **의도 표현과 직접 할당 실수 차단**, Immer `produce` 는 **구조적 공유와 가변 문법의 편의**, `Object.freeze` 는 **런타임 강제**. 세 층의 역할을 섞어 생각하면 "타입이 readonly 인데 왜 값이 바뀌었나" 같은 질문에 갇힌다.

## 참고

- TypeScript Handbook — Mapped Types 와 수정자 조작: https://www.typescriptlang.org/docs/handbook/2/mapped-types.html
- TypeScript Handbook — Conditional Types 와 `infer`: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- Immer 공식 문서 — TypeScript 사용과 `Draft`/`Immutable`: https://immerjs.github.io/immer/typescript
- Immer 공식 문서 — 성능과 auto-freeze: https://immerjs.github.io/immer/performance
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- Chris Okasaki, *Purely Functional Data Structures*, Cambridge University Press, 1998 (구조적 공유의 이론적 배경)
