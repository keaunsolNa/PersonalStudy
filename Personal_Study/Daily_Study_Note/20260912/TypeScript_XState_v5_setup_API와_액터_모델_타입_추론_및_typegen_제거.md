Notion 원본: https://app.notion.com/p/3d95a06fd6d3812fabb0ed32bd70cc1b?pvs=204

# TypeScript XState v5 setup API와 액터 모델 타입 추론 및 typegen 제거

> 2026-09-12 신규 주제 · 확장 대상: TypeScript Discriminated Union / 타입 안전 상태머신

## 학습 목표

- v4 의 `typegen` 코드 생성이 필요했던 타입 시스템적 이유를 규명한다
- `setup()` 이 제네릭 파라미터를 분리 바인딩해 추론을 역전시키는 구조를 분석한다
- 액터 계층의 `ActorRefFrom` / `SnapshotFrom` 추론 체인을 직접 작성해 검증한다
- React 연동과 `input` / `output` 타이핑의 경계를 판단해 도입 비용을 산정한다

## 1. Discriminated Union 상태머신의 한계

TypeScript 로 상태머신을 타입 안전하게 만드는 표준 접근은 discriminated union 이다.

```ts
type State =
	| { status: 'idle' }
	| { status: 'loading'; startedAt: number }
	| { status: 'success'; data: string[] }
	| { status: 'failure'; error: Error };

type Event =
	| { type: 'FETCH' }
	| { type: 'RESOLVE'; data: string[] }
	| { type: 'REJECT'; error: Error };

function reduce(state: State, event: Event): State {
	switch (state.status) {
		case 'idle':
			return event.type === 'FETCH' ? { status: 'loading', startedAt: Date.now() } : state;
		case 'loading':
			if (event.type === 'RESOLVE') {
				return { status: 'success', data: event.data };
			}
			if (event.type === 'REJECT') {
				return { status: 'failure', error: event.error };
			}
			return state;
		default:
			return state;
	}
}
```

이 코드는 상태별 데이터 형태를 컴파일 타임에 강제한다. `state.status === 'idle'` 분기에서 `state.data` 에 접근하면 즉시 에러다. 그러나 세 가지가 빠져 있다.

첫째, **전이의 합법성**이 타입으로 표현되지 않는다. `reduce` 가 `idle` 상태에서 `{ status: 'success', data: [] }` 를 반환해도 반환 타입 `State` 를 만족하므로 통과한다. 어떤 상태에서 어떤 이벤트가 어떤 상태로 가는지는 `switch` 본문의 관례일 뿐 타입 수준 계약이 아니다.

둘째, **부수 효과의 생애주기**가 없다. `loading` 진입 시 fetch 를 시작하고 `loading` 이탈 시 abort 해야 하는데, reducer 는 순수 함수여야 하므로 이 로직이 `useEffect` 로 흘러나가고 상태와 효과가 두 곳에 흩어진다.

셋째, **중첩·병렬 상태**를 표현하면 union 이 조합 폭발한다. `{ auth: 'signedIn' | 'signedOut' }` 와 `{ editor: 'clean' | 'dirty' | 'saving' }` 를 평탄한 union 으로 쓰면 6개 멤버가 되고, 축이 하나 늘 때마다 곱해진다.

XState 는 이 세 가지를 statechart(SCXML 계열 계층 상태머신) 모델로 해결하지만, 그 대가로 **설정 객체가 타입 추론의 주체**가 되어 버린다. 여기서 v4 의 고통이 시작된다.

## 2. v4 가 typegen 을 필요로 했던 구조적 이유

v4 의 API 는 `createMachine(config, options)` 형태였다.

```ts
// XState v4 — 실행하지 말 것, 구조 설명용
const machine = createMachine(
	{
		id: 'fetch',
		initial: 'idle',
		states: {
			idle: { on: { FETCH: 'loading' } },
			loading: {
				invoke: { src: 'fetchUsers', onDone: 'success', onError: 'failure' },
				entry: 'logStart',
			},
			success: { type: 'final' },
			failure: { on: { RETRY: 'loading' } },
		},
	},
	{
		actions: { logStart: (ctx, ev) => console.log(ctx, ev) },
		services: { fetchUsers: () => fetch('/users').then((r) => r.json()) },
	},
);
```

문제는 `config` 와 `options` 사이의 **상호 의존**이다. `config` 안의 `entry: 'logStart'` 는 `options.actions` 에 그 키가 있어야 유효하고, 반대로 `options.actions.logStart` 의 `(ctx, ev)` 파라미터 타입은 "logStart 가 실제로 어느 상태의 어느 이벤트에서 호출되는가"를 `config` 를 역으로 분석해야 알 수 있다. 두 인자가 하나의 호출에서 동시에 추론되므로 TypeScript 의 단방향 추론으로는 순환을 끊을 수 없다.

TypeScript 제네릭 추론은 인자 위치마다 추론 후보를 모아 한 번에 고정한다. `config` 의 문자열 리터럴에서 액션 이름 집합을 뽑아 `options` 의 키를 제약하려면 `config` 가 먼저 고정돼야 하는데, `options` 의 시그니처가 `config` 에 의존하는 순간 체커는 두 인자를 동시에 추론하며 서로를 `any` 또는 넓은 타입으로 후퇴시킨다. 실무에서는 `(ctx, ev)` 가 `ev: AnyEventObject` 로 떨어져 `ev.data` 접근이 그냥 통과하고, 오타난 액션 이름이 잡히지 않았다.

v4 의 해법은 타입 시스템 밖으로 나가는 것이었다. CLI 가 소스를 파싱해 `machine.typegen.ts` 를 생성하고, 개발자가 `createMachine<Context, Event, Typegen0>` 처럼 생성된 타입을 3번째 인자로 넣었다. 동작은 했지만 대가가 컸다. 파일을 저장할 때마다 코드 생성기를 돌려야 했고, 생성 파일을 커밋할지 gitignore 할지 팀마다 갈렸고, CI 에서 생성이 밀리면 타입 에러가 유령처럼 나타났다. IDE 는 생성 완료 전까지 잘못된 힌트를 띄웠다.

## 3. setup() — 추론 순서를 명시적으로 분리하기

v5 의 `setup()` 은 순환을 **호출을 두 단계로 쪼개서** 끊는다. 첫 호출에서 컨텍스트·이벤트·구현체를 고정하고, 그 결과 객체의 메서드에서 config 를 받는다.

```ts
import { setup, assign, fromPromise } from 'xstate';

type User = { id: number; name: string };

const fetchMachine = setup({
	types: {
		context: {} as { users: User[]; error: string | null; retries: number; pageSize: number },
		events: {} as { type: 'FETCH' } | { type: 'RETRY' } | { type: 'CANCEL' },
		input: {} as { pageSize: number },
	},
	actors: {
		fetchUsers: fromPromise(async ({ input }: { input: { pageSize: number } }) => {
			const res = await fetch(`/api/users?size=${input.pageSize}`);
			if (!res.ok) {
				throw new Error(`HTTP ${res.status}`);
			}
			return (await res.json()) as User[];
		}),
	},
	actions: {
		clearError: assign({ error: null }),
		bumpRetry: assign(({ context }) => ({ retries: context.retries + 1 })),
	},
	guards: {
		canRetry: ({ context }) => context.retries < 3,
	},
}).createMachine({
	id: 'fetch',
	initial: 'idle',
	context: ({ input }) => ({ users: [], error: null, retries: 0, pageSize: input.pageSize }),
	states: {
		idle: {
			on: { FETCH: { target: 'loading', actions: 'clearError' } },
		},
		loading: {
			invoke: {
				src: 'fetchUsers',
				input: ({ context }) => ({ pageSize: context.pageSize }),
				onDone: {
					target: 'success',
					actions: assign(({ event }) => ({ users: event.output })),
				},
				onError: {
					target: 'failure',
					actions: assign(({ event }) => ({ error: String(event.error) })),
				},
			},
		},
		success: { type: 'final' },
		failure: {
			on: {
				RETRY: { target: 'loading', guard: 'canRetry', actions: 'bumpRetry' },
			},
		},
	},
});
```

핵심은 `types: { context: {} as ... }` 라는 관용구다. `{} as T` 는 값으로는 빈 객체지만 타입으로는 `T` 다. 런타임에 아무 비용이 없고 컴파일 타임에는 제네릭 파라미터를 실어 보내는 통로가 된다. 별도 타입 인자 목록 대신 **값 위치에서 타입을 주입**하는 방식이라, `input`, `output`, `tags`, `emitted` 처럼 채널이 늘어나도 시그니처가 부풀지 않는다.

이제 추론 방향이 한쪽으로 정렬된다. `setup()` 호출이 끝나는 시점에 액션 키 집합은 `'clearError' | 'bumpRetry'` 로, 가드 키는 `'canRetry'` 로, 액터 키는 `'fetchUsers'` 로 **완전히 고정**된다. `createMachine` 은 이 고정된 집합을 제약으로 받으므로 `actions: 'clearErrro'` 같은 오타가 문자열 리터럴 union 불일치로 즉시 잡힌다. 반대 방향으로는 `bumpRetry` 의 `({ context })` 가 `setup` 의 `types.context` 에서 직접 온다 — config 를 역분석할 필요가 없다.

`onDone` 의 `event.output` 이 `User[]` 로 좁혀지는 것도 같은 원리다. `fromPromise` 의 콜백 반환 타입이 `Promise<User[]>` 이므로 `fetchUsers` 액터의 출력 타입은 `User[]` 이고, `invoke.src: 'fetchUsers'` 가 문자열 리터럴로 특정되면 `onDone` 의 이벤트는 `output: User[]` 를 가진 완료 이벤트로 매핑된다. v4 에서 typegen 이 하던 일이 조건부 타입과 리터럴 키 조회로 내려온 것이다.

## 4. 추론 체인 유틸리티와 액터 계층

머신을 정의하면 파생 타입을 유틸리티로 끌어낼 수 있다. 이 부분이 앱 코드 전반의 타입 안전을 결정한다.

```ts
import type { ActorRefFrom, SnapshotFrom, EventFromLogic, InputFrom } from 'xstate';

type FetchActor = ActorRefFrom<typeof fetchMachine>;
type FetchSnapshot = SnapshotFrom<typeof fetchMachine>;
type FetchEvent = EventFromLogic<typeof fetchMachine>;
type FetchInput = InputFrom<typeof fetchMachine>; // { pageSize: number }

function renderUsers(snapshot: FetchSnapshot): string {
	// value 가 상태 키 union 으로 좁혀진다
	if (snapshot.matches('success')) {
		return snapshot.context.users.map((u) => u.name).join(', ');
	}
	if (snapshot.matches('failure')) {
		return `실패: ${snapshot.context.error}`;
	}
	return '로딩 중';
}

function send(actor: FetchActor, event: FetchEvent): void {
	actor.send(event);
}
```

`matches` 는 중첩 상태에 대해 `'editor.dirty'` 같은 점 표기 경로도 받는데, 이 경로 문자열 union 은 config 의 `states` 트리를 재귀적으로 순회하는 template literal 타입으로 생성된다. 상태 키를 리터럴로 유지하는 것이 중요한 이유가 여기 있다. `states` 를 `Record<string, unknown>` 로 넓히는 헬퍼를 중간에 끼우면 경로 union 이 `string` 으로 무너지고 오타 검출이 사라진다.

액터 스폰까지 타입을 이어야 할 때는 `types.children` 을 선언한다.

```ts
const parentMachine = setup({
	types: {
		context: {} as { childRef: ActorRefFrom<typeof fetchMachine> | null },
		events: {} as { type: 'START' },
		children: {} as { fetcher: 'fetchLogic' },
	},
	actors: { fetchLogic: fetchMachine },
}).createMachine({
	initial: 'idle',
	context: { childRef: null },
	states: {
		idle: {
			on: {
				START: {
					target: 'running',
					actions: assign(({ spawn }) => ({
						childRef: spawn('fetchLogic', { id: 'fetcher', input: { pageSize: 20 } }),
					})),
				},
			},
		},
		running: {},
	},
});
```

`types.children` 으로 자식 id 와 로직 키를 대응시켜 두면 `getSnapshot().children.fetcher` 가 `FetchActor | undefined` 로 타이핑된다. 이 선언을 생략하면 `children` 이 인덱스 시그니처로 떨어져 자식 스냅샷 접근이 `any` 가 된다 — 도입 시 가장 자주 놓치는 지점이다.

| 항목 | v4 + typegen | v5 setup() |
|---|---|---|
| 액션 이름 오타 검출 | typegen 생성 후에만 | 즉시, 리터럴 union 불일치 |
| 이벤트 타입 좁히기 | 생성된 typegen 타입 의존 | 조건부 타입으로 직접 유도 |
| 빌드 파이프라인 | CLI watch 필수 | 없음 |
| `invoke` 출력 타입 | 수동 선언 또는 `any` | `fromPromise` 반환에서 추론 |
| 자식 액터 스냅샷 | 사실상 `any` | `types.children` 선언 시 정확 |
| 컴파일 부하 | 낮음(생성 파일은 평탄) | 재귀 조건부 타입으로 증가 |

## 5. 컴파일 성능 — 공짜가 아니다

typegen 제거의 비용은 타입 체크 시간으로 옮겨왔다. 상태 경로 union, 이벤트 매핑, 액션 키 제약이 모두 재귀 조건부 타입이므로 머신이 커지면 인스턴스화 수가 빠르게 늘어난다. 실무에서 40개 상태·80개 전이 규모의 머신 하나가 `tsc` 시간을 수 초 단위로 밀어 올리는 경우를 본다. 진단은 컴파일러 자체 도구로 한다.

```bash
tsc --noEmit --extendedDiagnostics
# Instantiations, Types, Check time 항목을 본다

tsc --noEmit --generateTrace ./trace
npx @typescript/analyze-trace ./trace
# 어느 파일·어느 타입 노드가 시간을 먹는지 트리로 출력
```

완화 수단은 세 가지다. **머신 분할** — 하나의 거대 머신보다 액터로 쪼갠 다수의 작은 머신이 인스턴스화 총량을 줄인다. 계층 상태로 축을 분리하면 union 곱셈도 피한다. **명시적 반환 타입** — 머신을 반환하는 팩토리 함수에 반환 타입 애너테이션을 달면 체커가 본문을 다시 추론하지 않는다. 다만 머신 타입을 손으로 쓰는 건 사실상 불가능하므로, 팩토리 대신 모듈 최상위 상수로 두고 `typeof` 로 참조하는 편이 낫다. **`isolatedDeclarations` 회피** — 머신 정의 파일에 이 옵션을 강제하면 추론 타입을 선언으로 내보낼 수 없어 수동 애너테이션 요구가 생긴다. 라이브러리로 머신을 공개할 계획이 아니면 켜지 않는다.

## 6. React 연동과 selector 경계

`@xstate/react` 의 `useSelector` 는 스냅샷에서 필요한 조각만 구독해 리렌더를 줄인다. 타입 관점에서는 selector 의 반환 타입이 그대로 흘러나온다.

```tsx
import { useActor, useSelector } from '@xstate/react';

function UserList() {
	const [snapshot, send, actorRef] = useActor(fetchMachine, { input: { pageSize: 20 } });

	// users 만 구독 — error 변화로는 리렌더하지 않는다
	const users = useSelector(actorRef, (s) => s.context.users);
	const canRetry = useSelector(actorRef, (s) => s.can({ type: 'RETRY' }));

	return (
		<div>
			<ul>
				{users.map((u) => (
					<li key={u.id}>{u.name}</li>
				))}
			</ul>
			<button disabled={!canRetry} onClick={() => send({ type: 'RETRY' })}>
				재시도
			</button>
		</div>
	);
}
```

`send({ type: 'RETRY' })` 에서 이벤트 타입이 머신의 이벤트 union 으로 제약되므로 존재하지 않는 이벤트나 필수 payload 누락이 컴파일 에러다. `s.can({ type: 'RETRY' })` 는 가드까지 평가해 현재 전이 가능 여부를 반환하므로, 버튼 비활성화 조건을 컴포넌트에 중복 구현하지 않아도 된다.

주의할 점은 selector 의 **참조 동등성**이다. `(s) => s.context.users.filter(...)` 처럼 매번 새 배열을 만들면 얕은 비교가 항상 실패해 매 스냅샷마다 리렌더한다. 파생값은 머신 컨텍스트에 미리 계산해 넣거나, `useSelector` 의 3번째 인자로 비교 함수를 넘긴다.

서버 컴포넌트와의 경계도 분명히 해야 한다. 머신 인스턴스는 가변 액터이므로 클라이언트 전용이다. 서버에서 초기 데이터를 내려주고 클라이언트에서 `input` 으로 주입하는 형태가 안전하다. `types.input` 을 선언해 두면 이 경계가 타입으로 문서화된다.

## 7. 도입 판단 기준

모든 폼 상태에 statechart 가 필요하지는 않다. 판단선은 **"불법 전이를 막는 것이 실제 버그를 막는가"** 다. 결제 플로우, 다단계 온보딩, WebSocket 연결 재시도, 낙관적 업데이트 롤백처럼 순서와 취소가 중요한 곳에서는 이득이 크다. 단순 토글이나 단일 fetch 는 `useState` 또는 TanStack Query 가 더 적은 코드로 끝난다.

Spring 백엔드와 함께 쓸 때 눈에 띄는 이득은 **프론트 상태와 서버 상태 기계의 대응**이다. 서버가 `PENDING → APPROVED → PAID` 같은 도메인 상태를 갖는다면, 프론트 머신의 상태 키를 같은 이름으로 두고 서버 응답 이벤트로 전이시키면 두 쪽의 불일치가 드러난다. 서버에 없는 전이를 프론트가 허용하는 상황이 config 리뷰에서 보이게 되는 것이 실질적 가치다.

마이그레이션은 v4 머신을 한꺼번에 옮기지 않는 편이 좋다. v5 는 `service` 가 `actor` 로 바뀌고, `machine.transition` 시그니처와 `assign` 콜백 인자 형태가 모두 달라졌으므로 머신 단위로 포팅하고 경계에서 어댑터를 둔다. typegen 파일과 `@xstate/cli` 는 포팅 완료 후 제거한다.

## 참고

- XState 공식 문서 — `setup()` API 와 타입 가이드: https://stately.ai/docs/setup
- XState v5 마이그레이션 가이드: https://stately.ai/docs/migration
- W3C SCXML: State Chart XML 명세: https://www.w3.org/TR/scxml/
- David Harel, "Statecharts: A Visual Formalism for Complex Systems", Science of Computer Programming, 1987
- TypeScript Wiki — Performance (타입 인스턴스화 진단): https://github.com/microsoft/TypeScript/wiki/Performance
