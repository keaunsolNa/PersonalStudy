Notion 원본: https://www.notion.so/34d5a06fd6d381508409cb313e1d6069

# React Suspense와 Concurrent Rendering Internals

> 2026-04-25 신규 주제 · 확장 대상: React (기본 학습됨), Next.js (App Router 학습됨)

## 학습 목표

- React 18의 concurrent rendering이 reconciler에 도입한 lane / priority 모델을 따라 읽는다
- Suspense가 throw된 promise를 잡는 메커니즘을 commit phase까지 추적한다
- useTransition / useDeferredValue가 만들어내는 두 가지 트리(committed vs pending)를 구분한다
- Streaming SSR + selective hydration의 패킷 시퀀스를 실제 응답으로 본다

---

## 1. Concurrent Rendering의 출발점

React 17까지는 reconciliation이 동기적 단일 스택이었다. setState가 일어나면 fiber tree를 끝까지 한 번에 reconcile 한 후 commit 했다. 이 흐름은 큰 트리 또는 비싼 컴포넌트에서 메인 스레드를 수십~수백 ms 점유했고, 입력 응답성을 떨어뜨렸다.

React 18은 reconciliation을 **interruptible**하게 바꿨다. 이제 reconciler가 fiber 노드를 하나 처리할 때마다 `shouldYield()`를 호출해 브라우저에 메인 스레드 제어를 돌려줄 수 있다. 우선순위가 더 높은 update가 들어오면 진행 중인 reconciliation을 버리고 다시 시작한다. 이게 concurrent rendering이다.

핵심 트레이드오프: 같은 컴포넌트가 commit되기 전에 여러 번 render될 수 있다. 그래서 render 함수는 순수해야 한다(side-effect 금지). React는 이 가정을 디버그 모드에서 의도적으로 두 번 호출(StrictMode double-invoke)해 검증한다.

## 2. Lane 모델

React 18의 update는 더이상 단순 "expirationTime"이 아니다. 32-bit lane bitmap으로 표현되는 우선순위 그룹이다. 주요 lane:

| Lane | 용도 |
|---|---|
| SyncLane | flushSync, ref attach |
| InputContinuousLane | 마우스 / 터치 드래그 |
| DefaultLane | 일반 setState |
| TransitionLane (1~16) | startTransition 안에서의 update |
| RetryLane | Suspense fallback에서 promise resolve 후 재시도 |
| IdleLane | 아무도 기다리지 않는 백그라운드 |

reconciler는 동시에 여러 lane을 마킹한 fiber tree를 갖고 있다가, 가장 높은 lane을 선택해 부분 렌더링한다. transition update가 진행 중이어도 input update가 들어오면 즉시 input lane을 우선해서 처리한다. 이게 사용자가 입력하는 동안 transition이 끊기지 않게 하는 메커니즘이다.

## 3. Suspense의 본질: Promise를 throw 한다

Suspense는 자식 컴포넌트가 render 함수 안에서 **Promise를 throw**하면 가장 가까운 `<Suspense>` 경계가 그 promise를 잡고, 자기 fallback을 렌더한다. 이 때 React는 promise에 `.then()`을 등록해 resolve 시 같은 lane으로 retry 한다.

```jsx
function ProductDetail({ id }) {
  const data = use(fetchProduct(id)); // 첫 호출 시 promise throw
  return <h1>{data.name}</h1>;
}

<Suspense fallback={<Skeleton />}>
  <ProductDetail id={42} />
</Suspense>
```

`use()` 훅(React 19에서 stable)이 표준 인터페이스다. 내부적으로 fetchProduct가 반환한 promise가 pending 상태면 `throw promise`를, fulfilled면 그 값을 반환한다. 이 trick이 동기 코드처럼 보이는 비동기 데이터 패칭의 핵심이다.

## 4. SuspenseList와 Reveal Order

여러 Suspense를 함께 노출하면 사용자에게 깜빡임이 보인다. SuspenseList는 자식 Suspense들의 reveal 순서를 제어한다.

```jsx
<SuspenseList revealOrder="forwards" tail="collapsed">
  <Suspense fallback={<Skeleton />}><ItemA /></Suspense>
  <Suspense fallback={<Skeleton />}><ItemB /></Suspense>
  <Suspense fallback={<Skeleton />}><ItemC /></Suspense>
</SuspenseList>
```

`revealOrder="forwards"`는 A가 끝날 때까지 B, C가 fallback으로 남아 있도록 강제한다. `tail="collapsed"`는 끝까지 가지 않은 fallback 중 마지막 하나만 표시한다. UX 관점에서 데이터가 무작위로 깜빡이는 느낌을 막는 도구다.

## 5. useTransition: 두 트리

`useTransition`은 우선순위가 낮은 update를 명시적으로 만든다. React는 그 동안 이전 commit된 트리를 유지하고, 새 트리를 별도로 만들고 있다는 사실을 `isPending`으로 노출한다.

```jsx
function SearchBox() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [isPending, startTransition] = useTransition();

  function onChange(e) {
    setQuery(e.target.value);                // urgent
    startTransition(() => {
      setResults(searchExpensive(e.target.value)); // transition
    });
  }
  return <>
    <input value={query} onChange={onChange} />
    {isPending && <span>updating…</span>}
    <ResultList items={results} />
  </>;
}
```

여기서 query는 입력 lane(즉시 commit), results는 transition lane(브라우저 idle 시 commit). 사용자가 빠르게 타이핑하면 results 트리가 commit되기 전에 다시 invalidate되어 메인 스레드가 입력 응답을 잃지 않는다.

## 6. useDeferredValue

비슷한 도구지만 사용 위치가 다르다. `useTransition`이 setter 호출자 쪽에서 우선순위를 정한다면, `useDeferredValue`는 소비자 쪽에서 "이 값이 약간 늦어도 된다"고 표시한다.

```jsx
function ResultList({ query }) {
  const deferred = useDeferredValue(query);
  const list = useMemo(() => filter(items, deferred), [deferred]);
  return <List items={list} stale={deferred !== query} />;
}
```

라이브러리 컴포넌트 안에서 호출자가 transition을 쓸지 모르는 상황에 자체적으로 응답성을 보존할 수 있다. 그래서 SearchBox 같은 재사용 컴포넌트에 자주 들어간다.

## 7. Streaming SSR + Selective Hydration

React 18 SSR의 큰 변화는 `renderToPipeableStream`이다. 서버는 HTML을 청크 단위로 흘려보내고, 각 Suspense 경계가 resolve될 때마다 추가 HTML 청크 + 그것을 원래 자리에 끼워넣는 인라인 스크립트를 함께 송신한다.

```http
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Transfer-Encoding: chunked

<!doctype html>
<div id="root">
  <header>...</header>
  <!--$?--><template id="B:0"></template><Skeleton/><!--/$-->
</div>

(client에서 fetchProduct resolve 후 다음 청크 도착)

<div hidden id="S:0"><h1>Product 42</h1></div>
<script>$RC("B:0","S:0")</script>
```

`$RC`(replace children) 함수가 ID로 표시된 두 노드를 찾아 fallback을 실제 컨텐츠로 교체한다. 이 방식은 head-of-line blocking을 없애고, 느린 데이터 한 컴포넌트가 전체 페이지의 First Byte를 막지 않는다.

**Selective hydration**: 청크가 도착해도 React는 모든 컴포넌트를 한 번에 hydrate하지 않는다. 우선순위(예: 사용자가 클릭한 영역)에 따라 부분 hydrate한다. 그 사이 hydrate 안 된 영역도 React 18은 events를 captured queue에 보관했다가 hydrate 완료 후 replay한다. 사용자가 화면을 보고 있는데 클릭 이벤트가 사라지지 않게 하는 트릭.

## 8. 흔한 함정

**hydration mismatch**. 서버 렌더 결과와 클라이언트 첫 render 결과가 다르면 React는 트리 전체를 재생성한다. 자주 만나는 원인: `Date.now()`, `Math.random()`, locale에 따라 다른 포맷, 클라이언트 전용 useLayoutEffect 결과를 SSR에 흘리는 경우.

**Suspense 안의 setState**. Suspense fallback이 떠 있는 동안 자식 컴포넌트의 setState는 동일 lane에 합쳐진다. 그 결과 fallback이 깜빡거리는 경우가 있다. `<Suspense>` 트리 바깥에서 `useDeferredValue`로 입력을 dampen해야 깜빡임을 줄일 수 있다.

**use() in event handler**. `use()` 훅은 render 함수 안에서만 호출 가능. 이벤트 핸들러에서 호출하면 React error를 받는다. 데이터 fetch 트리거는 `startTransition` + setState로, 데이터 소비는 `use()`로 분리해야 한다.

## 참고

- React 18 RFC, "Concurrent React"
- Andrew Clark, "React 18 deep dive" (React Conf 2021)
- React Source Tree, packages/react-reconciler/src/ReactFiberLane.js
- React Docs, "Suspense for Data Fetching"
- Dan Abramov, "Why Selective Hydration"
