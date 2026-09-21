Notion 원본: https://www.notion.so/3e25a06fd6d381a780d9c102ffa168a5

# Signals 기반 세밀 반응성과 의존성 그래프 및 글리치 없는 전파

> 2026-09-21 신규 주제 · 확장 대상: React, Javascript

## 학습 목표

- push-pull 하이브리드 전파가 글리치와 불필요 계산을 동시에 제거하는 구조를 설명한다
- 자동 의존성 추적의 구독 갱신 절차를 소스 코드 수준으로 재구성한다
- VDOM diff 모델과 세밀 반응성 모델의 갱신 비용을 상황별로 비교한다
- 비동기 경계·정리(cleanup)·메모리 누수 지점을 식별하고 방어 코드를 작성한다

## 1. 문제 정의 — 무효화의 범위

React 의 갱신 모델은 "컴포넌트 단위 무효화 + 가상 DOM diff" 다. `setState` 가 호출되면 해당 컴포넌트 함수 전체가 재실행되고, 반환된 트리를 이전 트리와 비교해 실제 DOM 변경분을 찾는다. 정확하지만 **변경량과 무관하게 컴포넌트 크기에 비례하는 작업**이 발생한다.

세밀 반응성(fine-grained reactivity)은 반대 방향이다. 상태 하나하나가 자신을 읽는 곳의 목록을 들고 있어, 값이 바뀜면 **그 값을 실제로 쓰는 DOM 노드만** 갱신한다. 컴포넌트 함수는 최초 1회만 실행되고 이후 다시 호출되지 않는다.

| 항목 | VDOM(React) | Signals(Solid, Preact Signals, Angular, Vue) |
| --- | --- | --- |
| 컴포넌트 함수 실행 | 상태 변경마다 | 최초 1회 |
| 갱신 단위 | 컴포넌트 서브트리 | 개별 바인딩 |
| 갱신 비용 | O(트리 크기) | O(의존 개수) |
| 의존성 선언 | 수동(deps 배열) | 자동 |
| 메모이제이션 | 개발자 책임(또는 컴파일러) | 기본 내장 |
| 클로저 신선도 | stale closure 위험 | 항상 최신 |
| 디버깅 | 렌더 로그 명확 | 그래프 추적 필요 |

## 2. 핵심 3요소: signal, computed, effect

```ts
const count = signal(0);                       // 쓰기 가능한 소스
const doubled = computed(() => count() * 2);   // 파생값(순수, 캐시됨)
effect(() => console.log(doubled()));          // 부수효과(그래프의 잎)

count.set(1);   // → effect 재실행, doubled 재계산
```

그래프에서 `signal` 은 루트, `computed` 는 중간 노드, `effect` 는 잎이다. 데이터는 루트에서 잎으로 흐르고, **오직 잎만이 계산을 강제로 개시한다**. `computed` 는 아무도 읽지 않으면 계산하지 않는다(lazy).

의존성은 선언하지 않는다. 실행 중에 자동으로 수집된다.

```ts
let activeConsumer: Computation | null = null;

function signal<T>(initial: T) {
  let value = initial;
  const observers = new Set<Computation>();

  const read = () => {
    if (activeConsumer) {
      observers.add(activeConsumer);
      activeConsumer.sources.add(node);   // 양방향 링크
    }
    return value;
  };
  read.set = (next: T) => {
    if (Object.is(value, next)) return;  // 동등하면 전파 중단
    value = next;
    for (const o of observers) o.markDirty();
  };
  return read;
}
```

읽기 시점에 전역 `activeConsumer` 를 보고 링크를 건다. 이것이 "자동 추적"의 전부다. 결과적으로 **조건문 안의 읽기도 정확히 추적된다**.

```ts
const showDetail = signal(false);
const detail = signal("...");

effect(() => {
  if (showDetail()) console.log(detail());   // false 일 때 detail 은 의존성이 아님
});
```

`showDetail` 이 false 인 동안 `detail` 을 바꿔도 effect 는 실행되지 않는다. React 의 deps 배열로는 표현할 수 없는 정밀도다. 대신 **실행마다 의존성 집합이 달라지므로 매번 재수집하고 이전 집합과의 차이를 정리**해야 한다.

```ts
function runComputation(c: Computation) {
  const prevSources = c.sources;
  c.sources = new Set();
  const prev = activeConsumer;
  activeConsumer = c;
  try {
    c.fn();
  } finally {
    activeConsumer = prev;
    // 이번에 읽지 않은 소스에서 구독 해제 — 누수 방지의 핵심
    for (const s of prevSources) {
      if (!c.sources.has(s)) s.observers.delete(c);
    }
  }
}
```

이 정리 단계를 빠뜨리면 조건 분기가 바뀔 때마다 구독이 누적되어 메모리와 계산량이 함께 늘어난다.

## 3. 글리치와 push-pull 하이브리드

순진한 push 전파는 **글리치(glitch)**, 즉 일시적으로 모순된 중간 상태를 만든다.

```
a = signal(1)
b = computed(() => a() + 1)
c = computed(() => a() * 2)
d = computed(() => b() + c())   // 항상 3a + 1 이어야 함

a.set(2) 시 순진한 push:
  a → b 갱신(3) → d 갱신: b=3, c=2(아직 구버전) → d=5   ← 글리치! (정답 7)
  a → c 갱신(4) → d 갱신: b=3, c=4 → d=7
```

`d` 가 5 라는 값을 한 번 내보냈다. 그 값이 effect 를 타고 DOM 에 반영되면 화면이 깜빡이고, 네트워크 요청을 트리거하면 잘못된 요청이 나간다.

현대 signal 구현은 **push-pull 하이브리드**로 이를 없씤다.

**Push 단계 (동기, 얕음)** — 값이 바뀌면 후손 노드를 `DIRTY`/`CHECK` 로 표시만 한다. 계산은 하지 않는다.

**Pull 단계 (지연, 깊음)** — effect 가 실행되거나 누군가 값을 읽을 때, 그 노드가 자신의 소스를 **위쪽으로 재귀 검증**하고 필요한 것만 계산한다.

```ts
const CLEAN = 0, CHECK = 1, DIRTY = 2;

function markDirty(node) {
  node.state = DIRTY;
  for (const o of node.observers) markCheck(o);   // 후손은 CHECK 로만
}
function markCheck(node) {
  if (node.state !== CLEAN) return;   // 이미 표시됨 → 중복 순회 차단
  node.state = CHECK;
  for (const o of node.observers) markCheck(o);
  if (node.isEffect) scheduleEffect(node);
}

function readComputed(node) {
  if (node.state === CHECK) {
    // 소스를 거슬러 올라가며 "정말 바뀜는지" 확인
    for (const s of node.sources) {
      readComputed(s);                     // 재귀 검증
      if (node.state === DIRTY) break;     // 소스가 실제로 값을 바꿨다
    }
  }
  if (node.state === DIRTY) {
    const prev = node.value;
    node.value = runComputation(node);
    if (!Object.is(prev, node.value)) {
      for (const o of node.observers) o.state = DIRTY;   // 진짜 변화만 전파
    }
  }
  node.state = CLEAN;
  return node.value;
}
```

이 구조가 두 가지를 동시에 해결한다.

**글리치 제거** — `d` 는 자신이 읽히는 시점에 `b` 와 `c` 를 모두 최신화한 뒤 계산하므로, 중간 상태를 외부에 노출하지 않는다.

**불필요 계산 제거** — `CHECK` 상태에서 소스를 검증했는데 값이 실제로 안 바뀜으면 재계산 자체를 건너뛴다. 이를 **equality short-circuit** 이라 하며, `Object.is` 비교가 그 판정 기준이다.

```ts
const items = signal([1, 2, 3]);
const count = computed(() => items().length);
const label = computed(() => `총 ${count()}개`);

items.set([4, 5, 6]);   // 배열은 바뀜 → count 재계산 → 3 === 3 → label 은 재계산 안 함
```

`label` 이 비싼 계산이었다면 이 차단이 그대로 이득이다. React 에서는 `useMemo` 를 두 단계로 나눠야 같은 효과를 낸다.

## 4. 배칭과 트랜잭션

여러 신호를 연속으로 바꾸면 effect 가 여러 번 실행될 수 있다. 배칭은 이를 하나로 합친다.

```ts
batch(() => {
  firstName.set("Gildong");
  lastName.set("Hong");
});   // fullName 을 읽는 effect 가 1회만 실행
```

구현은 단순하다. 배치 중에는 effect 를 즉시 실행하지 않고 큐에 모았다가, 배치 종료 시 중복을 제거하고 **위상 순서대로** 한 번씩 실행한다. 위상 정렬이 필요한 이유는 깊이가 얕은 노드를 먼저 처리해야 재실행이 없기 때문이다.

대부분의 프레임워크는 이벤트 핸들러와 라이프사이클을 암묵적으로 배칭한다. 명시적 `batch` 가 필요한 곳은 비동기 콜백, 타이머, 외부 라이브러리 콜백처럼 프레임워크 밖에서 들어오는 경로다.

읽기는 하되 구독하지 않으려면 `untrack` 을 쓴다.

```ts
effect(() => {
  const id = userId();              // 구독
  const cfg = untrack(() => config()); // 읽기만, 구독 안 함
  fetchUser(id, cfg);
});
```

## 5. 비동기 경계에서 추적이 끊긴다

자동 추적은 **동기 실행 스택**에서만 동작한다. `await` 이후의 읽기는 `activeConsumer` 가 이미 복원된 상태라 추적되지 않는다.

```ts
// 잘못된 코드
effect(async () => {
  const id = userId();          // 추적됨
  const r = await fetch(`/u/${id}`);
  const v = filter();           // 추적 안 됨 — await 이후
  render(await r.json(), v);
});
```

규칙은 하나다. **비동기 작업 시작 전에 모든 의존성을 동기적으로 읽어 둔다.**

```ts
effect(() => {
  const id = userId();
  const v = filter();           // 먼저 전부 읽기
  const ac = new AbortController();
  fetch(`/u/${id}`, { signal: ac.signal })
    .then(r => r.json())
    .then(d => render(d, v))
    .catch(e => { if (e.name !== "AbortError") throw e; });
  onCleanup(() => ac.abort());  // 재실행/해제 시 이전 요청 취소
});
```

`onCleanup` 이 없으면 경쟁 상태가 생긴다. `userId` 가 빠르게 A→B 로 바뀜면 A 의 응답이 B 보다 늦게 도착해 화면에 A 가 남을 수 있다. 취소가 불가능한 작업이면 시퀀스 번호로 방어한다.

```ts
let seq = 0;
effect(() => {
  const id = userId();
  const my = ++seq;
  load(id).then(d => { if (my === seq) setData(d); });
});
```

프레임워크가 `resource`/`createResource` 류 프리미티브를 제공한다면 그것을 쓰는 편이 낫다. 취소·경쟁·로딩 상태·에러를 이미 처리하고 있고, 서버 렌더링 시 스트리밍 경계와도 연결된다.

## 6. 렌더링과의 연결 — 왜 컴포넌트가 1회만 실행되나

Solid 같은 프레임워크는 JSX 를 VDOM 이 아니라 **DOM 생성 코드 + 세밀 바인딩**으로 컴파일한다.

```jsx
// 소스
function Counter() {
  const [n, setN] = createSignal(0);
  return <button onClick={() => setN(n() + 1)}>Count: {n()}</button>;
}

// 컴파일 결과(개념)
const tmpl = template(`<button>Count: </button>`);
function Counter() {
  const [n, setN] = createSignal(0);
  const el = tmpl.cloneNode(true);
  el.addEventListener("click", () => setN(n() + 1));
  const text = el.firstChild.nextSibling;
  effect(() => { text.data = String(n()); });   // 이 effect 만 재실행
  return el;
}
```

`Counter` 함수는 마운트 시 1회 실행된다. 이후 `n` 이 바뀌면 텍스트 노드 하나를 갱신하는 effect 만 돌린다. diff 도, 컴포넌트 재실행도 없다.

이 모델의 결과로 **훅 규칙이 사라진다**. 컴포넌트 본문이 한 번만 실행되므로 조건문 안에서 signal 을 만들어도 되고, 클로저가 오래된 값을 잡는 문제도 없다. 대신 새로운 함정이 생긴다.

```jsx
// props 구조분해가 반응성을 끊는다
function Bad({ value }) {          // 이 시점의 값으로 고정
  return <p>{value}</p>;           // 영원히 갱신 안 됨
}
function Good(props) {
  return <p>{props.value}</p>;     // getter 호출 → 추적됨
}
```

props 는 getter 를 가진 프록시 객체다. 구조분해하면 getter 를 즉시 호출해 값으로 고정시킨다. 리스트 렌더링도 `map` 대신 전용 `<For>` 를 써야 키 기반 재사용이 동작한다. **"자바스크립트처럼 보이지만 컴파일러가 개입한다"**는 점이 학습 비용의 대부분이다.

## 7. 메모리 누수와 그래프 수명

signal 그래프는 양방향 링크를 가지므로 해제 규칙이 중요하다.

**소스는 옵저버를 강하게 참조한다.** 전역 signal 을 읽는 effect 를 만들고 정리하지 않으면, 컴포넌트가 사라져도 effect 와 그 클로저가 살아남는다. 컴포넌트 스코프에 묶인 effect 는 프레임워크가 언마운트 시 자동 해제하지만, `createRoot` 나 모듈 최상단에서 만든 effect 는 직접 `dispose()` 해야 한다.

```ts
const dispose = createRoot((d) => {
  effect(() => syncToLocalStorage(theme()));
  return d;
});
// 나중에
dispose();
```

**조건부 구독이 누적되지 않도록** 2절의 구독 정리가 필수다. 직접 반응성 시스템을 구현할 일은 드물지만, 라이브러리 간 브리지를 만들 때는 이 규칙을 지켜야 한다.

**외부 상태와의 브리지는 양쪽 정리를 짝지운다.**

```ts
function fromEvent(target: EventTarget, type: string) {
  const s = signal<Event | null>(null);
  const h = (e: Event) => s.set(e);
  target.addEventListener(type, h);
  onCleanup(() => target.removeEventListener(type, h));
  return s;
}
```

진단은 Chrome DevTools 의 힙 스냅샷에서 `Computation`/`Effect` 인스턴스 수를 라우팅 전후로 비교하는 방식이 효과적이다. 라우트를 오갔을 때 수가 단조 증가하면 해제되지 않는 effect 가 있다.

## 8. 언제 쓰고 언제 쓰지 않는가

세밀 반응성이 확실히 유리한 조건은 **갱신이 잦고, 갱신 범위가 좁고, 트리가 큰** 경우다. 실시간 대시보드, 시세 테이블, 협업 커서, 대규모 폼이 전형적이다. 갱신당 비용이 트리 크기와 무관하므로 노드 수가 늘어도 프레임 예산을 지킬 수 있다.

반대로 **전체가 함께 바뀌는 화면**(라우트 전환, 목록 전체 교체)에서는 이점이 거의 없다. 이 경우 어느 모델이든 DOM 작업량이 지배적이다.

| 상황 | 권장 |
| --- | --- |
| 고빈도 부분 갱신 | Signals |
| 전체 교체가 잦음 | 차이 없음 |
| 기존 React 생태계 의존 | React(+ 컴파일러) |
| 번들 크기 민감 | Signals 계열이 대체로 작음 |
| 팀 학습 비용 최소화 | 기존 스택 유지 |

React 도 컴파일러로 자동 메모이제이션을 도입해 "수동 최적화 제거"라는 목표에서는 수렴하고 있다. 다만 무효화 단위가 여전히 컴포넌트라는 점은 그대로이므로, 두 접근은 같은 문제를 다른 층위에서 푸다. TC39 의 Signals 제안이 표준화되면 프레임워크 간 상태 공유가 가능해지는데, 현재 stage 1 이고 명세가 유동적이므로 프로덕션 채택은 이르다. 지금 배워야 할 것은 특정 API 가 아니라 **의존성 그래프와 push-pull 전파라는 모델 자체**다. 이 모델을 이해하면 Solid, Vue, Angular, Svelte 5 의 반응성이 모두 같은 그림으로 보인다.

## 참고

- TC39 Proposal — JavaScript Signals standard proposal (README, 알고리즘 절)
- SolidJS Documentation — Reactivity: Fine-grained / Lifecycle
- Preact Signals — Announcement 및 소스(`packages/core/src/index.ts`)
- Vue.js Documentation — Reactivity in Depth
- Angular Documentation — Signals / Glitch-free computations
- Svelte 5 — Runes / Fine-grained reactivity
