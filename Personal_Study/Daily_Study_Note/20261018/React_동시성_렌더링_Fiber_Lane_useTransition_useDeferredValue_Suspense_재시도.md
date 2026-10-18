Notion 원본: https://app.notion.com/p/3fd5a06fd6d381ef91d9d3f557663e16?pvs=204

# React 18/19 동시성 렌더링: Fiber Lane 우선순위와 useTransition·useDeferredValue 스케줄링 및 Suspense 재시도 동작

> 2026-10-18 신규 주제 · 확장 대상: React

## 학습 목표

- Fiber Lane 비트마스크가 업데이트 우선순위를 표현하고 배치를 묶는 방식을 소스 상수로 추적한다
- useTransition과 useDeferredValue가 각각 어느 lane에 렌더를 배정하는지 render 로그로 구분한다
- Suspense가 중단된 렌더를 언제 다시 시도하는지(ping, retry lane) 관찰하고 fallback 노출 조건을 제어한다
- 비동기 Action의 isPending 구간과 await 이후 setState의 함정을 재현해 회피 패턴을 고른다

검증 환경은 react / react-dom 19.1.0, jsdom, TypeScript 6.0.3(strict, `jsx: react-jsx`)이다. 아래 출력은 모두 `act`로 감싸 실제로 실행한 결과이고, 렌더 횟수와 순서는 마이너 버전에 따라 달라질 수 있다.

## 1. Lane은 31비트 마스크다

React 17의 expirationTime 대신 18부터 쓰는 Lane은 우선순위를 "숫자 크기"가 아니라 "켜진 비트의 집합"으로 표현한다. 집합이므로 서로 다른 업데이트를 한 렌더 배치에 합치거나(`a | b`), 일부만 떼어내거나(`a & ~b`), 여러 우선순위를 동시에 pending으로 들고 있을 수 있다. 우선순위 비교는 최하위 비트 추출(`lanes & -lanes`)로 끝난다. 아래 상수는 19.1.0의 `react-dom-client.development.js`(`getLabelForLane`, `claimNextTransitionLane`, `claimNextRetryLane`)에서 읽은 값이며, 내부 구현이라 마이너마다 바뀔 수 있다.

| Lane | 값 | 주 발생처 |
|---|---|---|
| Sync | 2 | 클릭 같은 discrete 이벤트, flushSync |
| InputContinuous | 8 | mousemove, scroll 같은 continuous 이벤트 |
| Default | 32 | 이벤트 밖 setState(타이머, 네트워크 콜백) |
| Transition | 256 ~ 2097152 (14개) | startTransition, useDeferredValue의 지연 렌더 |
| Retry | 4194304 ~ 33554432 (4개) | Suspense 경계 재시도 |
| Idle / Offscreen / Deferred | 2^28 / 2^29 / 2^30 | 유휴 작업, 숨겨진 트리, 지연 값 |

Transition lane이 14개인 이유는 서로 다른 transition을 독립 lane에 배정해 하나가 다른 하나를 불필요하게 덮지 않게 하려는 것이다. 15번째 transition은 다시 256으로 돌아온다. 아래 스크립트는 그 순환과 배치 규칙(`lanes & 42`, 즉 Sync·InputContinuous·Default는 한 묶음)을 상수로 재현한 것이다.

```ts
const TransitionLanes = 0x3fff00; // 비트 8..21
let next = 256;
const claim = () => { const l = next; next <<= 1; if ((next & TransitionLanes) === 0) next = 256; return l; };
const claimed = Array.from({ length: 16 }, claim);
console.log("transition #1,#2,#14,#15:", claimed[0], claimed[1], claimed[13], claimed[14]);
const pending = 0b10 | 0b100000 | claimed[0] | claimed[1];
const batch = (pending & 42) !== 0 ? pending & 42 : pending & -pending;
console.log("pending=", pending.toString(2), "-> batch=", batch.toString(2));
```

```text
transition #1,#2,#14,#15: 256 512 2097152 256
pending= 1100100010 -> batch= 100010 Sync
```

Sync와 Default가 transition과 함께 pending이어도 이번 렌더 배치는 `100010`(Sync|Default)만 처리하고 transition 두 개는 다음 차례로 밀린다. 사용자 입력이 transition 때문에 지연되지 않는 구조적 이유다. 반대로 Default 렌더 도중 더 높은 lane이 들어오면 진행 중이던 작업은 버려지고 처음부터 다시 시작한다. 이 "버리기"가 동시성 렌더링의 비용이므로, 렌더 함수는 순수해야 하고 두 번 호출돼도 안전해야 한다.

## 2. 업데이트가 lane을 받는 경로

`requestUpdateLane`은 세 갈래다. 렌더 중에 발생한 업데이트는 현재 렌더 중인 lane 중 최하위 비트를 그대로 쓴다. `ReactSharedInternals.T`(현재 transition)가 있으면 이미 진행 중인 transition에 엮인 lane(`currentEntangledLane`)이 있을 때 그것을, 없으면 새 transition lane을 받는다. 둘 다 아니면 이벤트 우선순위로 결정한다. 즉 `startTransition(() => setX(1))` 안의 모든 setState가 같은 lane을 공유하는 것은 "콜백 실행 동안만 전역 T가 켜져 있기 때문"이다.

이 모델에서 두 가지 실무 규칙이 나온다. 첫째, `startTransition` 콜백은 동기적으로 setState를 호출해야 한다. 콜백이 반환된 뒤 실행되는 setState에는 T가 없다. 둘째, 입력 value를 제어하는 state는 transition으로 감싸면 안 된다. 입력은 Sync lane에서 즉시 반영돼야 하고, 무거운 파생 상태만 낮은 lane으로 내려야 한다.

## 3. 렌더 대상 선택: getNextLanes

루트가 다음에 렌더할 lane은 `getNextLanes`가 고른다. 알고리즘의 핵심은 suspended lane을 건너뛴다는 점이다. 비유휴 pending lane 중 suspendedLanes를 뺀 것이 있으면 그중 최우선 그룹을 렌더한다. 없으면 pingedLanes(Promise가 풀려 깨어난 lane)를 보고, 그것도 없을 때만 아직 warm-up하지 않은 lane을 prewarm한다. 데이터가 오지 않은 Suspense 렌더가 다른 업데이트를 막지 못하는 이유이며, 동시에 "resolve 되면 같은 lane에서 재시도"가 어떻게 일어나는지의 답이다. 이미 렌더 중인 작업은 새 우선순위가 같은 레벨이거나 낮으면 계속 진행하고, 더 높으면 갈아탄다. 이 판단식은 `wipLanes`와 `nextLanes`의 최하위 비트를 비교한다.

## 4. useTransition: 낮은 lane에서 이전 UI를 유지한다

transition 안의 업데이트는 Transition lane에서 렌더되고, 그 렌더가 Suspense로 중단돼도 이미 화면에 있는 경계는 fallback으로 되돌리지 않는다. 같은 데이터 전환을 urgent로 했을 때와 비교한 실행 결과다(`use`로 읽는 캐시된 Promise 50ms, 코드는 `Suspense > Page`).

```tsx
const [isPending, start] = useTransition();
go = (n, viaTransition) => (viaTransition ? start(() => setId(n)) : setId(n));
```

```text
--- urgent setId(2)
App render id=2 isPending=false
after urgent (pending): DOM=<p style="display: none !important;">page 1</p><i>loading</i>
Page render id=2 (resolved)
after urgent (settled): DOM=<p style="">page 2</p>
--- transition setId(3)
App render id=2 isPending=true
Page render id=2 (resolved)
App render id=3 isPending=false
after transition (pending): DOM=<p style="">page 2</p>
App render id=3 isPending=false
Page render id=3 (resolved)
after transition (settled): DOM=<p style="">page 3</p>
```

urgent 업데이트는 기존 트리를 `display: none`으로 숨기고(언마운트하지 않으므로 state 보존) fallback을 보인다. transition은 먼저 `isPending=true`를 Sync로 커밋하고, 새 id=3 렌더를 시도한다. 이 시도가 중단되면 화면은 page 2 그대로이며, Promise가 풀린 뒤 `App render id=3`이 한 번 더 호출되는 것이 재시도다. 중간의 `Page render id=2`는 App이 `memo` 없이 재렌더되며 자식이 따라온 것으로, 로그의 실제 순서다. 로딩 표시가 필요하면 fallback 대신 `isPending`으로 기존 화면 위에 얇은 인디케이터를 얹는다.

## 5. useDeferredValue: 값의 두 번째 렌더

useDeferredValue는 이벤트 핸들러를 건드릴 수 없는 곳(라이브러리가 넘긴 prop 등)에서 같은 효과를 낸다. 첫 렌더는 이전 값을 그대로 반환하고, 그 커밋 직후 `requestDeferredLane`이 받은 transition lane에서 새 값으로 백그라운드 재렌더를 한다. 소스에서는 이미 Offscreen lane 렌더 중이면 Offscreen을, 아니면 새 transition lane을 배정한다. 기본 렌더와 비교하는 로그다.

```tsx
const Slow = memo(function Slow({ q }: { q: string }) { log.push(`Slow render q=${q}`); return <ul>{q}</ul>; });
function App() {
  const [q, setQ] = useState("a");
  const deferred = useDeferredValue(q);
  log.push(`App render q=${q} deferred=${deferred}`);
  return <Slow q={deferred} />;
}
```

```text
App render q=a deferred=a
Slow render q=a
--- setQ('ab')
App render q=ab deferred=a
App render q=ab deferred=ab
Slow render q=ab
```

핵심은 App이 `q=ab`로 두 번 호출되고, 비싼 `Slow`는 지연 패스에서만 갱신된다는 점이다. 그래서 `memo`(또는 React Compiler 자동 메모이제이션)가 없으면 이득이 없다. 지연 패스가 끝나기 전에 입력이 또 바뀌면 진행 중인 지연 렌더는 버려지고 최신 값으로 다시 시작해, 디바운스와 달리 고정 지연 시간 없이 기기 성능에 맞춰 따라간다. 대신 네트워크 요청 자체를 줄여 주지는 않는다. 요청 절감이 목적이면 디바운스가 여전히 필요하다. 오래된 결과를 흐리게 표시하려면 `q !== deferred`를 stale 플래그로 쓴다. React 19에서는 `useDeferredValue(value, initialValue)`로 첫 렌더 값을 지정할 수 있고, 타입은 `T`로 고정이다.

```text
src/bad.tsx(3,33): error TS2345: Argument of type 'number' is not assignable to parameter of type 'string'.
```

위는 `useDeferredValue(q, 0)`을 strict 모드로 컴파일했을 때의 실제 오류다. initialValue 인자도 같은 `T`로 검사된다.

## 6. Suspense 재시도 동작

Suspense 경계에서 컴포넌트가 중단(throw 또는 `use`)되면 React는 해당 lane을 suspended로 표시하고 Promise에 ping을 건다. resolve되면 pingedLanes에 올라가 `getNextLanes`가 같은 lane을 다시 고르며, 중단 컴포넌트가 처음부터 재호출된다. 중단 이전 hook state는 보존되지 않는다는 뜻이다. 그래서 Promise는 렌더마다 새로 만들면 안 되고, 캐시나 Suspense를 지원하는 라이브러리(Relay, TanStack Query, Next.js 서버 컴포넌트)에서 가져와야 한다. 새 Promise를 매 렌더 만들면 영원히 재시도한다. 아래는 `useDeferredValue`와 Suspense를 섞은 실행 결과다.

```text
## mount
App k=a dk=a
Slow attempt k=a
  fetch(a) started
Slow attempt k=a
Sibling render
DOM=<i>fallback</i>
Slow attempt k=a
Slow done k=a
Sibling render
DOM=<b>a</b><u>sib</u>
## setK('b') with useDeferredValue
App k=b dk=a
Slow attempt k=a
Slow done k=a
Sibling render
App k=b dk=b
Slow attempt k=b
  fetch(b) started
Sibling render
DOM(pending)=<b>a</b><u>sib</u>
App k=b dk=b
Slow attempt k=b
Slow done k=b
Sibling render
DOM(settled)=<b>b</b><u>sib</u>
```

마운트에서는 fallback이 커밋됐고, 이 19.1.0 실행에서는 fallback 커밋 전에 중단 컴포넌트가 한 번 더 시도되고 형제(Sibling)가 렌더됐다(형제를 미리 렌더해 다음 데이터 요청을 앞당기는 prewarm 성격이며 정확한 횟수는 버전에 따라 다름). 반면 갱신에서는 deferred 값 때문에 중단돼도 fallback이 나타나지 않고 이전 `<b>a</b>`가 유지된다. 이미 공개된 콘텐츠를 숨기는 fallback은 transition·deferred 렌더에서는 억제되고, 새로 마운트되는 경계에서만 허용된다는 규칙이다. 경계를 `key`로 갈아 끼우면 새 마운트라서 fallback이 다시 보이니, 의도적으로 로딩 화면을 보이고 싶을 때만 key를 바꾼다.

## 7. 비동기 Action과 isPending 구간

React 19의 `startTransition`은 async 함수를 받고, 그 Promise가 끝날 때까지 `isPending`이 true로 유지된다. 단 `await` 이후의 setState는 transition 컨텍스트를 잃는다는 점을 확인하자.

```tsx
run = () => start(async () => {
  await sleep(60);
  setN(x => x + 1); // await 이후: 전역 T가 꺼진 시점
});
```

```text
render n=0 isPending=false
render n=0 isPending=true
-- action running
render n=1 isPending=true
render n=1 isPending=false
```

`n=1`이 `isPending=true` 상태로 먼저 렌더되고 그다음에야 pending이 풀린다. setN이 transition이 아닌 lane에서 커밋됐다는 증거다. 공식 문서도 await 이후 상태 갱신은 `startTransition`으로 한 번 더 감싸라고 안내한다. 이를 놓치면 결과 화면이 Action 종료 전에 깜빡이며 바뀐다. 에러는 가장 가까운 Error Boundary로 전파되므로, 폼 제출에서는 try/catch로 결과 상태를 직접 만들거나 `useActionState`를 쓰는 편이 안전하다.

## 8. 선택 기준과 trade-off

| 상황 | 선택 | 비용 |
|---|---|---|
| 내가 setState를 호출하는 탭 전환·라우팅 | useTransition | 콜백 안 동기 호출 필수, isPending UI 직접 구성 |
| prop·외부 값을 받아 무거운 자식만 지연 | useDeferredValue + memo | 부모 2회 호출, memo 없으면 무의미 |
| 요청 횟수를 줄여야 함 | 디바운스 (+ 위 둘과 병용) | 고정 지연 시간이 체감 지연이 됨 |
| 이미 보이는 화면을 fallback으로 되돌리기 싫음 | transition / deferred | 오래된 UI가 잠시 남아 일관성 검토 필요 |
| 의도적으로 로딩 화면을 보임 | urgent 업데이트 또는 `key` 교체 | 기존 트리 숨김 후 state 보존 여부 확인 |

모든 업데이트를 transition으로 돌리면 낮은 lane이 계속 밀려 지연이 길어지고, 오래 밀린 lane은 기아 방지로 시간 분할 없이 렌더돼 장점이 사라진다. 측정은 React DevTools Profiler와 브라우저 Performance 패널의 Scheduler 트랙으로 하고, 이 노트의 jsdom 로그는 순서 검증용이지 시간 측정용이 아니다. 렌더 시간(ms)은 실측하지 않았으므로 수치 성능 비교는 직접 프로파일링해야 한다. 정리하면 transition은 "업데이트 쪽", deferred는 "값 쪽"에서 같은 lane 메커니즘을 쓰고, Suspense는 그 lane을 suspended와 pinged 상태로 오가게 만드는 장치다.

## 참고

- React 공식 문서: useTransition, useDeferredValue, Suspense, use (react.dev/reference/react)
- React 18 Working Group: "New in 18" 및 "Automatic batching"(github.com/reactwg/react-18)
- React 소스: `ReactFiberLane.js`, `ReactFiberWorkLoop.js` (github.com/facebook/react, packages/react-reconciler/src)
- React 19 릴리스 노트: Actions, `use`, `useActionState` (react.dev/blog/2024/12/05/react-19)
