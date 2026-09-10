Notion 원본: https://www.notion.so/3d75a06fd6d3815798d6d57fabdcef47

# Signals 반응성 모델과 글리치 프리 전파 및 TC39 제안

> 2026-09-10 신규 주제 · 확장 대상: React

## 학습 목표

- VDOM 재렌더링과 fine-grained 구독의 실행 단위를 코드로 구분한다
- signal / computed / effect 를 push-pull 하이브리드로 직접 구현한다
- 다이아몬드 그래프의 글리치 발생 조건과 차단 기법을 재현한다
- TC39 proposal-signals 의 역할 분담을 파악하고 React 와의 접점을 설계한다

## 1. 반응성의 두 갈래

React 는 상태가 바뀌면 컴포넌트 함수를 **다시 실행**하고 결과 트리를 이전 트리와 비교해 DOM 을 갱신한다. 반응성의 최소 단위가 "함수 1회 호출"이다. Solid, Vue, Angular signals 의 fine-grained 모델은 최소 단위가 "값을 읽은 지점"이라, `count` 를 읽는 텍스트 노드만 구독자로 등록되고 갱신 시 그 노드만 다시 쓴다.

```jsx
function Counter({ label }) {              // React: 클릭마다 함수 전체 재실행
  const [n, setN] = useState(0);
  const heavy = useMemo(() => fmt(label), [label]);   // 방어막이 필요
  console.log("render");                              // 클릭마다 찍힘
  return <button onClick={() => setN(c => c + 1)}>{heavy}: {n}</button>;
}
function Counter(props) {                  // Solid: 최초 1회만 실행
  const [n, setN] = createSignal(0);
  const heavy = fmt(props.label);                     // useMemo 없이도 1회
  console.log("setup");                               // 클릭해도 안 찍힘
  return <button onClick={() => setN(c => c + 1)}>{heavy}: {n()}</button>;
}
```

Solid 의 JSX 는 컴파일 타임에 `el.textContent = n()` 형태의 이펙트로 변환된다. 클릭 1회에 실행되는 코드가 텍스트 대입 한 줄인 반면 React 는 함수 본문 + hooks 비교 + diff 까지 돈다. trade-off 는, React 는 실행량이 많은 대신 렌더를 통째로 버리거나 미룰 수 있고(동시성 렌더링의 전제), fine-grained 는 실행량이 적은 대신 본문이 "1회 실행되는 setup"이라는 별도 규칙을 요구하며 props 구조분해 한 번에 반응성이 끊긴다는 점이다.

## 2. Signal 의 기본 구성 요소

`signal` 은 상태 원자, `computed` 는 파생 캐시, `effect` 는 그래프 밖으로 나가는 부작용이다. 핵심은 **push-pull 하이브리드**다. 상태가 바뀌면 의존자에게 "더러워졌다"는 플래그만 push 하고, 읽는 순간 dirty 면 그때 pull 로 재계산한다. push 만 쓰면 아무도 안 보는 computed 까지 계산하고, pull 만 쓰면 매번 전면 재계산이 된다. 의존성은 전역 스택에 실행 중인 computation 을 올려 두고 읽을 때 top 에 자신을 등록시켜 자동 추적한다.

```js
let ACTIVE = null;              // 현재 실행 중인 computation
const STACK = [], PENDING = new Set();

function link(dep, sub) { dep.subs.add(sub); sub.deps.add(dep); }  // 양방향 링크

export function signal(value) {
  const node = { value, subs: new Set(), version: 0 };
  const read = () => { if (ACTIVE) link(node, ACTIVE); return node.value; };
  read.set = (next) => {
    if (Object.is(next, node.value)) return;   // 동등하면 전파 차단
    node.value = next; node.version++;
    markDirty(node); flush();
  };
  return read;
}

function markDirty(node) {      // 값 계산 없이 표시만 재귀 전파
  for (const s of node.subs) {
    if (s.state === "dirty") continue;         // 지수적 재방문 차단
    s.state = "dirty";
    if (s.kind === "effect") PENDING.add(s); else markDirty(s);
  }
}
```

computed 는 dirty 일 때만 재계산하되 그 전에 기존 의존성을 끊는다. 조건 분기로 의존 집합이 달라지는 경우(`show() ? a() : b()`)를 반영하려면 매 실행마다 새로 수집해야 한다.

```js
const mk = (kind, fn) => ({ kind, fn, state: "dirty",
                            deps: new Set(), subs: new Set(), cleanup: null });

export function computed(fn) {
  const node = mk("computed", fn);
  return () => {
    if (ACTIVE) link(node, ACTIVE);
    if (node.state === "dirty") run(node, () => { node.value = node.fn(); });
    return node.value;
  };
}

export function effect(fn) {
  const node = mk("effect", fn);
  runEffect(node);
  return () => dispose(node);
}

function run(node, body) {
  for (const dep of node.deps) dep.subs.delete(node);   // 이전 구독 해제
  node.deps.clear();
  STACK.push(ACTIVE); ACTIVE = node;
  try { body(); } finally { ACTIVE = STACK.pop(); node.state = "clean"; }
}

const runEffect = (n) =>
  (n.cleanup?.(), run(n, () => { n.cleanup = n.fn() ?? null; }));
```

`untrack` 은 `ACTIVE` 를 잠시 `null` 로 두고 읽으면 된다.

## 3. 글리치 문제

다이아몬드는 `a` 에서 `b`, `c` 가 파생되고 `d` 가 둘을 읽는 모양이다. `b = a`, `c = a*2`, `d = b + c` 라면 언제나 `d = 3a` 다. `markDirty` 없이 "쓰기 즉시 의존자를 순서대로 재계산"하면 이렇게 된다.

```js
a.set(1);            // b=1, c=2, d=3
a.set(2);
// a → b 전파: b=2, 그 즉시 d 재계산 → d = 2 + 2(옛 c) = 4   ← 글리치
// a → c 전파: c=4, 다시 d 재계산 → d = 2 + 4 = 6
```

관측된 `4` 는 어떤 시점의 `a` 로도 설명되지 않는다. effect 가 읽었다면 존재한 적 없는 상태로 요청을 보낸다. 차단 방식은 셋이다.

**위상 정렬** — 노드에 깊이를 부여하고 낮은 것부터 처리한다. `d` 는 `b`, `c` 보다 깊으니 둘이 끝난 뒤 실행된다. 조건 분기로 의존성이 바뀌면 깊이를 다시 매겨야 한다.

**버전 카운터** — 각 노드가 마지막 계산 시 본 의존자의 `version` 을 기록한다. `b` 의 결과가 이전과 동등하면(`Object.is`) version 이 안 올라, dirty 표시가 있어도 `d` 는 계산을 건너뛴다.

**lazy pull** — 위 미니 구현의 방식으로 `set` 은 dirty 표시만 하고 값은 읽는 쪽이 당긴다. 중간 상태가 구조적으로 관측 불가능해진다.

trade-off: lazy pull 은 단순하고 글리치가 원천 차단되나 effect 는 아무도 읽어주지 않아 pull 을 시작할 스케줄러가 필요하다. 위상 정렬은 값을 eager 하게 유지해 그래프를 그대로 들여다볼 수 있다.

## 4. 배칭과 스케줄링

`a.set()`, `b.set()` 을 연달아 부르면 effect 가 두 번 돌아 DOM 을 두 번 만진다. 배칭은 dirty 표시된 effect 를 큐에 모아 한 번에 비우는 일이다.

```js
let scheduled = false;

function flush() {
  if (scheduled) return;
  scheduled = true;
  queueMicrotask(() => {
    scheduled = false;
    const q = [...PENDING]; PENDING.clear();
    q.sort((x, y) => x.height - y.height);      // 부모 effect 를 자식보다 먼저
    for (const e of q) if (e.state === "dirty" && !e.disposed) runEffect(e);
  });
}

export function batch(fn) {     // 동기 구간을 명시적으로 묶는다
  const prev = scheduled; scheduled = true;
  try { fn(); } finally { scheduled = prev; flush(); }
}
```

마이크로태스크 배칭의 함정은 "쓴 직후 DOM 을 읽으면 아직 갱신 전"이라는 점이다. Vue 의 `nextTick`, Angular 의 `afterNextRender` 가 이 틈을 메우고, Solid 는 반대로 `createEffect` 를 렌더 큐에 붙여 동기 배칭에 가깝게 돌린다. 렌더링 조율에서는 DOM 을 쓰는 effect 를 전부 돌린 뒤 레이아웃을 읽는 것을 돌려야 강제 리플로우가 effect 개수만큼 나는 일을 막는다.

## 5. 메모리와 구독 해제

그래프는 양방향이다. `dep.subs` 로 전파하고 `sub.deps` 로 정리하며, 단방향이면 재계산 시 옛 구독을 끊을 수 없어 계속 자란다. 핵심 규칙은 **관측되지 않는 computed 는 아무것도 구독하지 않는다**이다. effect 가 dispose 되어 어떤 computed 를 더 이상 읽지 않으면 그 `subs` 가 비고, 그러면 그것도 자기 `deps` 에서 떨어져야 한다. 없으면 signal 하나가 죽은 computed 체인 전체를 붙잡는다.

```js
function dispose(node) {
  node.disposed = true; node.cleanup?.();
  for (const dep of node.deps) {
    dep.subs.delete(node);
    if (dep.kind === "computed" && dep.subs.size === 0) dispose(dep);  // 고아 정리
  }
  node.deps.clear(); PENDING.delete(node);
}
```

WeakRef 우회는 권장되지 않는다. GC 시점이 비결정적이라 "언제 구독이 끊기는가"가 관측 가능한 동작이 되고, 짧게 사는 이펙트에서는 정리가 늦어 오히려 누수처럼 보인다. 대신 **소유권(owner/scope)** 을 쓴다. effect 를 만들 때 현재 owner 에 등록하고 owner 가 dispose 되면 자식을 전부 정리한다. Solid 의 `createRoot`, Vue 의 `effectScope` 가 그것이다. 전형적 누수는 모듈 스코프 `effect()` 의 dispose 미회수, `addEventListener` 의 cleanup 미반환, 아이템별 effect 의 owner 를 리스트에 묶는 경우, 전역 signal 에 DOM 노드를 넣는 경우다.

## 6. TC39 Signals 제안

`tc39/proposal-signals` 는 프레임워크마다 제각각인 반응성 코어를 **언어 차원의 공통 기저**로 내리자는 제안이다. 챔피언 목록에 Angular, Vue, Solid, Preact, Ember, MobX 관계자가 함께 올라와 있다. `Signal.State` 는 `get()`/`set()` 을 가진 상태 원자, `Signal.Computed` 는 `get()` 만 가진 파생 캐시, `Signal.subtle.Watcher` 는 노드가 더러워졌음을 통지받는 저수준 훅이다.

```js
const counter = new Signal.State(0);
const parity = new Signal.Computed(() => (counter.get() & 1 ? "odd" : "even"));

const w = new Signal.subtle.Watcher(() => {   // 통지는 동기로 온다
  queueMicrotask(() => {                      // 값 읽기는 반드시 지연시킨다
    for (const s of w.getPending()) s.get();  // pull 로 최신화
    w.watch();                                // 다시 감시 등록
    render(parity.get());
  });
});
w.watch(parity);
```

`effect` 를 명세에서 빼고 `Watcher` 만 둔 것이 핵심 결정이다. effect 실행 시점은 Angular 는 렌더 사이클, Vue 는 `nextTick`, Solid 는 동기 큐로 제각각이라 표준이 정책을 못 박으면 어느 프레임워크도 쓸 수 없다. 표준은 **의존성 추적, 글리치 프리 전파, 캐싱**이라는 결정론적 부분만 담고 타이밍은 `Watcher` 위에 각자 얹도록 남겼다. `subtle` 이라는 이름이 앱 개발자용 API 가 아님을 알린다. 2026년 기준 **Stage 1** 이라 API 표면이 바뀔 수 있어 프로덕션 노출은 이르지만, 폴리필(`signal-polyfill`)로 실험은 가능하다.

## 7. 프레임워크별 구현 비교

| 프레임워크 | 상태 API | 추적 방식 | 컴포넌트 재실행 | effect 스코프 |
|---|---|---|---|---|
| Solid | `createSignal` / `createMemo` | 런타임 호출 + JSX 컴파일 | 없음(1회 setup) | `createRoot` / `onCleanup` |
| Vue 3 | `ref` / `reactive` / `computed` | Proxy 게터 | 없음(render effect 만) | `effectScope` |
| Angular | `signal` / `computed` / `effect` | 함수 호출 | 있음(OnPush 단위) | `DestroyRef` |
| Preact Signals | `signal` / `computed` | `.value` 게터 | 텍스트 바인딩은 없음 | `effect` 반환값 |
| Svelte 5 | `$state` / `$derived` / `$effect` | 컴파일러가 재작성 | 없음 | 컴포넌트 생명주기 |

Solid 는 컴파일러가 JSX 를 DOM 조작 코드로 낮춰 런타임 오버헤드가 가장 작은 대신 JSX 가 React 의미론과 달라진다(props 구조분해 금지 등). Vue 의 Proxy 는 `state.a.b.c = 1` 같은 중첩 변경을 그대로 추적해 주는 대신 래핑 비용과 원본/프록시 동일성 혼동이 따라온다. Angular 는 zoneless 의 발판으로 삼되 컴포넌트 재실행은 남긴다. Svelte 5 runes 는 문법이 가장 얇지만 컴파일러 없이 성립하지 않아 경계를 넘기 어렵다.

## 8. React 와의 관계

React 가 signals 를 코어로 채택하지 않은 이유는 성능 취향이 아니라 모델 충돌이다. 첫째, **동시성 렌더링**. 렌더를 중단하고 버렸다 다시 시작할 수 있어야 하는데 fine-grained 갱신은 "지금 즉시 이 DOM 노드를 바꾼다"라 중단 지점이 없다. 둘째, **시간 분할**. `useTransition` 은 옛 UI 와 새 UI 를 동시에 들어야 하고 이는 **불변 스냅샷**을 전제로 하는데, 가변 셀을 읽는 signal 은 "어느 시점의 값인가"가 정의되지 않는다. 셋째, tearing 이다. 외부 가변 저장소를 여러 컴포넌트가 각자 읽으면 렌더 중간에 값이 바뀌어 화면 일부만 새 값이 된다. 그래서 React 는 외부 스토어 구독의 공식 통로로 `useSyncExternalStore` 를 두었다.

```js
export function useSignal(read) {          // 미니 signal 을 안전하게 읽는다
  const [sub, snap] = useMemo(() => {
    let value = untrack(read), ls = new Set();
    const stop = effect(() => { value = read(); ls.forEach((l) => l()); });
    return [(cb) => (ls.add(cb),
              () => { ls.delete(cb); if (!ls.size) stop(); }),
            () => value];                  // 캐시된 참조를 그대로 반환
  }, [read]);
  return useSyncExternalStore(sub, snap, snap);
}
```

`getSnapshot` 이 매번 새 객체를 만들면 무한 루프가 나므로 캐시된 참조를 돌려줘야 하고, 세 번째 인자는 SSR 용 스냅샷이다. React Compiler 는 재렌더는 일어나되 바뀌지 않은 부분의 **재계산과 자식 재렌더를 자동으로 건너뛰어** `useMemo` 수기 작성을 없애는 것이 목표고, signals 는 **재렌더 자체를 일으키지 않는다**가 목표다. 전자는 모델을 유지한 채 상수 인자를 줄이고 후자는 모델을 바꿔 작업량의 차수를 줄인다.

## 9. 실전 적용 판단

이득이 큰 쪽은 **상태 개수 × 갱신 빈도**가 모두 큰 화면, 즉 시세 대시보드나 협업 에디터, 캔버스 툴의 속성 패널이다. 수백 개 셀이 초당 수십 번 갱신되면 재렌더 모델은 갱신 1건마다 트리 일부를 다시 돌지만 fine-grained 는 셀 하나의 텍스트 노드만 건드린다. 반대로 라우트 전환마다 데이터를 새로 받는 화면은 이득이 거의 없다. 대가는 디버깅이다. 추적이 암묵적이라 "이 effect 가 왜 돌았나"가 콜스택에 남지 않으므로 노드에 이름을 붙여 둔다.

```js
export const named = (name, node) =>
  (import.meta.env.DEV && (node.__name = name), node);
// run() 안에서: DEV 일 때 console.debug("recompute", node.__name)
```

SSR 의 첫 규칙은 그래프를 **요청 간 공유하지 않는 것**이다. 모듈 스코프에 `export const user = signal(null)` 을 두면 요청끼리 상태가 섞이므로 요청마다 스코프를 만들어 주입한다. 하이드레이션은 서버 마크업과 클라이언트 첫 계산이 일치해야 하므로 `Date.now()` 나 `window` 를 읽는 computed 는 effect 로 내린다.

부분 도입의 경계는 원칙 하나다. **signal 은 도메인 상태 계층에만 두고, UI 계층과는 단일 어댑터 훅으로만 만난다.** 8절의 `useSignal` 하나만 노출하면 구현을 TC39 표준이나 다른 라이브러리로 갈아 끼울 때 교체 지점이 한 곳에 모이지만, 곳곳에서 `.value` 를 직접 읽으면 롤백 비용이 폭증한다. 갱신이 가장 잦은 화면 하나만 먼저 옮겨 측정한 뒤 확장하는 순서가 안전하다.

## 참고

- [tc39/proposal-signals](https://github.com/tc39/proposal-signals)
- [Solid Reactivity](https://docs.solidjs.com/concepts/intro-to-reactivity)
- [Vue 3 Reactivity in Depth](https://vuejs.org/guide/extras/reactivity-in-depth.html)
- [Angular Signals](https://angular.dev/guide/signals)
- [Preact Signals](https://preactjs.com/guide/v10/signals/)
- [Svelte 5 Runes](https://svelte.dev/docs/svelte/what-are-runes)
- [React useSyncExternalStore](https://react.dev/reference/react/useSyncExternalStore)
