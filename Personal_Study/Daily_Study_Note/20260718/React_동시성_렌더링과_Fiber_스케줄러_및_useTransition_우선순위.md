Notion 원본: https://www.notion.so/3a15a06fd6d38168b614dd800ea60039

# React 동시성 렌더링과 Fiber 스케줄러 및 useTransition 우선순위

> 2026-07-18 신규 주제 · 확장 대상: React

## 학습 목표

- Stack Reconciler 의 중단 불가 구조와 Fiber 의 가상 스택 프레임 재구성을 대조한다
- Lane 비트마스크 모델과 이벤트 종류에 따른 우선순위 결정 경로를 추적한다
- useTransition / useDeferredValue / useSyncExternalStore 의 내부 동작을 근거로 선택 기준을 세운다
- 동시성 렌더가 이득인 워크로드와 손해인 경우를 판별한다

## 1. Stack Reconciler 의 한계와 Fiber 노드

React 15 이전의 재조정기는 컴포넌트 트리를 재귀 함수로 내려갔다. 순회 상태는 전적으로 **JavaScript 콜스택**에 담겼다. 어느 노드에 있고 다음에 무엇을 할지는 스택 프레임의 지역 변수와 반환 주소가 알고 있었다.

문제는 콜스택을 마음대로 멈췄다 이어붙일 수 없다는 점이다. 재귀가 시작되면 끝까지 내려갔다 올라와야 한다. 메인 스레드는 JS 실행, 레이아웃, 페인트, **이벤트 핸들러 디스패치**를 모두 처리하므로, 큰 트리를 렌더하느라 200ms 를 쓰면 그동안의 키 입력은 큐에 쌓여만 있다. 입력이 "밀리는" 체감은 여기서 나온다.

Java 라면 `ExecutorService` 로 넘기면 될 상황이지만, DOM 은 메인 스레드에서만 만질 수 있으므로 그 탈출구가 없다. 남은 선택지는 **한 스레드 안에서 작업을 잘게 쪼개는 것**뿐이었고, 쪼개려면 순회 위치를 콜스택 바깥으로 옮겨야 했다.

Fiber 는 스택 프레임을 힙 객체로 재구현한 것이다. 재귀 대신 명시적 포인터로 순회하고, 순회 위치를 전역 변수(`workInProgress`) 하나에 담아두면 언제든 빠져나갔다 재개할 수 있다.

```tsx
type Fiber = {
  tag: number;              // FunctionComponent | HostComponent | ...
  stateNode: any;           // HostComponent 면 실제 DOM 노드

  return: Fiber | null;     // 부모 — 스택의 return address
  child: Fiber | null;      // 첫 자식
  sibling: Fiber | null;    // 다음 형제

  memoizedState: any;       // 훅 연결 리스트의 head
  alternate: Fiber | null;  // 더블 버퍼링 짝
  lanes: number;            // 이 fiber 에 예약된 우선순위 비트마스크
  childLanes: number;       // 서브트리에 남은 작업 (bailout 판단용)
};
```

자식이 여럿이어도 배열이 아니라 **연결 리스트**라는 점이 핵심이다. 배열이면 몇 번째 인덱스까지 처리했는지 따로 기억해야 하지만, 연결 리스트는 노드 자신이 다음을 가리키므로 현재 노드 포인터 하나가 곧 순회 상태 전체다.

`childLanes` 는 성능의 축이다. `beginWork` 진입 시 props 도 안 바뀌었고 `childLanes` 에 현재 렌더 중인 lane 이 안 걸려 있으면 서브트리를 통째로 건너뛴다(bailout). 깊은 곳의 업데이트는 `return` 을 타고 루트까지 올라가며 조상들의 `childLanes` 에 비트를 OR 로 새기므로, 이 판정이 비트 연산 한 번으로 끝난다.

대가는 메모리다. 컴포넌트마다 수십 개 필드의 객체가 더블 버퍼링 때문에 두 벌 존재한다. 렌더 속도를 내주고 **중단 가능성**을 산 것이다.

## 2. workLoop 와 협력적 스케줄링

Fiber 순회는 두 국면으로 나뉜다. `beginWork` 는 내려가면서 컴포넌트 함수를 호출해 자식 fiber 를 만들고, `completeWork` 는 올라오면서 DOM 노드를 생성하고 effect 를 부모로 모은다. 깊이 우선 순회를 재귀 없이 while 루프로 편 것이다.

```tsx
function workLoopConcurrent() {
  while (workInProgress !== null && !shouldYield()) {
    performUnitOfWork(workInProgress);
  }
}

function performUnitOfWork(unit: Fiber) {
  const next = beginWork(unit.alternate, unit, renderLanes);
  if (next === null) completeUnitOfWork(unit); // completeWork + sibling/return
  else workInProgress = next;
}
```

동기 렌더용 `workLoopSync` 는 `shouldYield()` 조건만 없는 같은 루프다. 두 모드의 차이는 **양보 여부 단 하나**다.

`shouldYield()` 는 대략 5ms 마다 true 를 돌려준다. 60fps 프레임 예산 16.7ms 중 브라우저의 스타일/레이아웃/페인트 몫을 남기려는 값이다. 다만 이 판정은 **작업 단위 사이**에서만 이뤄진다. 컴포넌트 하나의 렌더 함수가 자체적으로 50ms 를 쓰면 React 는 개입할 방법이 없다.

Java 와의 대조가 선명해지는 지점이다. JVM 스레드는 **선점형(preemptive)** 이라 OS 가 타임 슬라이스를 다 쓴 스레드를 강제로 밀어내고, 대상 스레드는 밀려났다는 사실조차 모른다. React 는 **협력적(cooperative)** 이라 `Thread.yield()` 를 성실히 호출하는 스레드들만 사는 세계에 가깝다. 그래서 `while(true)` 하나가 탭 전체를 얼린다.

| 축 | JVM 스레드 | React Fiber |
|---|---|---|
| 양보 방식 | 선점(OS 타이머 인터럽트) | 협력(`shouldYield()` 체크) |
| 상태 보관 | 스레드별 네이티브 스택 | 힙의 Fiber 연결 리스트 |
| 중단 단위 | 임의의 바이트코드 경계 | `performUnitOfWork` 경계 |
| 중단된 작업 | 그대로 재개 | **폐기 후 재시작** |
| 병렬성 | 실제 멀티코어 병렬 | 단일 스레드 인터리빙 |

React 의 "동시성"은 병렬 실행이 아니라 시분할 인터리빙이며, 중단된 렌더는 **버려지고 처음부터 다시 시작**된다.

## 3. 더블 버퍼링과 render / commit 경계

React 는 두 개의 fiber 트리를 유지한다. `current` 는 화면에 반영된 트리, `workInProgress` 는 만드는 중인 트리다. 대응 노드는 `alternate` 로 서로를 가리키고, 새 렌더는 기존 노드를 복제해 재사용한다. 커밋이 끝나면 `root.current = finishedWork` 로 포인터만 스왑하며, 방금까지 화면이던 트리가 다음 작업용 버퍼가 된다.

이 구조가 성립하는 조건이 **render 단계는 부수효과가 없어야 한다**는 것이다. workInProgress 트리는 화면에 없으므로 버려도 사용자는 못 본다. 하지만 렌더 도중 외부 변수를 건드렸다면 그 흔적은 남는다.

```tsx
let renderCount = 0; // 모듈 스코프 — 렌더 밖의 세계

function Broken({ items }: { items: string[] }) {
  renderCount++;  // 중단 시 되돌아가지 않음
  items.sort();   // props 를 제자리 변형 — 더 나쁨
  return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>;
}

function Fixed({ items }: { items: string[] }) {
  const sorted = useMemo(() => [...items].sort(), [items]); // 복사본
  return <ul>{sorted.map(i => <li key={i}>{i}</li>)}</ul>;
}
```

`Broken` 은 동기 렌더에서는 잘 동작한다. 렌더가 한 번 끝까지 실행되니까. 동시성 렌더에서는 중단·재시작으로 렌더 함수가 여러 번 호출될 수 있어 `renderCount` 가 커밋 횟수와 무관해진다. "렌더 함수는 순수해야 한다"는 스타일 가이드가 아니라 **중단 가능성이 요구하는 계약**이다.

반면 commit 단계 — DOM mutation, layout effect, ref 부착 — 는 동기적이고 중단 불가다. DOM 을 절반만 바꾼 상태를 보여줄 수는 없다. 트랜잭션의 준비 구간과 커밋 구간을 나눈 것과 같다. 준비 구간은 롤백 가능해야 하고, 커밋 구간은 원자적이어야 한다.

## 4. Lane 모델 — 숫자 비교에서 비트 집합으로

React 16 은 `expirationTime` 이라는 숫자로 우선순위를 표현했고, 이번에 처리할지를 `updateExpirationTime <= renderExpirationTime` 같은 부등식으로 판정했다. 부등식이라는 점이 곧 한계였다. 우선순위가 **전순서(total order)** 로만 표현되므로 "높음과 낮음은 처리하되 중간은 건너뛴다" 같은 **집합** 표현이 불가능했다.

Lane 은 각 우선순위에 32비트 정수의 서로 다른 비트를 할당한다. 우선순위 묶음이 비트마스크가 되고, 조합·검사·제거가 상수 시간 비트 연산이다.

```tsx
const lanes = fiber.lanes;

const shouldRender = (lanes & renderLanes) !== 0; // 이번 렌더 대상인가
const highest = lanes & -lanes;                   // 가장 급한 lane 하나만
fiber.lanes = lanes & ~renderLanes;               // 처리 완료분 제거
parent.childLanes |= lanes;                       // 조상으로 표식 전파
```

| Lane | 발생 경로 | 중단 |
|---|---|---|
| SyncLane | discrete 이벤트(click, keydown), `flushSync` | 불가 |
| InputContinuousLane | continuous 이벤트(mousemove, scroll) | 사실상 즉시 |
| DefaultLane | 타이머, 네트워크 응답, 기본 setState | 가능 |
| TransitionLane (여러 개) | `startTransition` / `useTransition` | 가능 |
| RetryLane | Suspense 재시도 | 가능 |
| IdleLane | 화면 밖 / 최저 우선순위 | 가능 |

TransitionLane 이 **여러 비트로 나뉜 것**이 lane 모델의 대표적 이득이다. 트랜지션마다 각자의 lane 을 받으므로 A 진행 중에 B 가 들어와도 A 를 오염시키지 않는다. **entanglement** 도 비트 집합이라 가능해졌다. 두 lane 이 얽혀 있으면(같은 `useTransition` 안의 여러 업데이트 등) 함께 렌더돼야 일관성이 유지되므로, 루트가 얽힘 정보를 들고 있다가 한쪽을 렌더할 때 얽힌 lane 을 `renderLanes` 에 강제 포함시킨다.

lane 은 이벤트 종류에서 결정된다. React 는 DOM 이벤트를 discrete(의도가 명확 — click, keydown)와 continuous(연속 스트림 — mousemove, scroll)로 분류하고, 핸들러 실행 전에 그에 맞는 update priority 를 전역에 설정한다. 핸들러 안의 `setState` 는 `requestUpdateLane` 을 통해 그 전역값을 읽는다. 같은 `setCount(1)` 이라도 click 핸들러 안에서면 SyncLane, `setTimeout` 안에서면 DefaultLane, `startTransition` 안에서면 TransitionLane 이다. **어디서 호출됐는지가 우선순위를 정한다.**

## 5. Scheduler — MessageChannel 과 기아 방지

양보한 뒤 어떻게 되돌아올까. React 와 별개 패키지인 `scheduler` 가 재진입을 담당한다.

가장 단순한 후보인 `setTimeout(fn, 0)` 은 쓸 수 없다. HTML 명세가 중첩 깊이 5를 넘는 타이머에 **최소 4ms clamp** 를 강제하므로, 5ms 일하고 4ms 를 대기로 날리게 된다. `requestIdleCallback` 은 의미상 가까워 보이지만 호출 빈도가 낮고 편차가 커서 트랜지션 렌더를 맡기기엔 지연이 컸다.

그래서 `MessageChannel` 을 쓴다. `port.postMessage()` 의 수신 핸들러는 **clamp 없는 macrotask** 로 큐잉된다. macrotask 라는 점이 중요하다. microtask(Promise 등)로 반복 스케줄링하면 체크포인트가 비워질 때까지 렌더링이 진행되지 못해 양보가 아니게 된다. macrotask 사이에서만 브라우저가 페인트하고 대기 중인 입력을 처리한다.

```tsx
const channel = new MessageChannel();
const port = channel.port2;

channel.port1.onmessage = () => {
  deadline = performance.now() + yieldInterval; // ≈ 5ms
  const hasMoreWork = flushWork(performance.now());
  if (hasMoreWork) port.postMessage(null); // 다음 macrotask 예약
};
```

Scheduler 내부는 두 개의 최소 힙을 쓴다. `taskQueue` 는 `expirationTime` 순, `timerQueue` 는 지연 시작 시각 순이다. 각 우선순위에 타임아웃이 부여되어 `startTime + timeout` 이 `expirationTime` 이 된다. 뽑는 기준이 우선순위가 아니라 **만료 시각**이라는 점이 기아 방지의 1차 장치다.

React 쪽에도 승격 장치가 있다. 루트는 각 lane 이 처음 대기 상태가 된 시각을 기록해두고, 매 스케줄 시점에 `markStarvedLanesAsExpired` 로 오래 굶은 lane 을 **만료 처리**한다. 만료된 lane 은 다음 렌더에서 동기·중단 불가로 강제 실행된다. 트랜지션이 입력에 밀려 영원히 재시작만 하는 시나리오를 끊는 안전장치이며, Java 의 우선순위 큐가 기아를 aging 으로 푸는 것과 발상이 같다. 다만 승격의 결과가 "우선순위 상승"이 아니라 "중단 불가 모드로의 전환"이라 그 순간에는 프레임 드롭이 생길 수 있다. 응답성보다 진행 보장(liveness)을 택한 것이다.

## 6. useTransition — 긴급과 전환의 분리

`useTransition` 은 "이 업데이트는 급하지 않다"를 선언한다. 콜백 안의 `setState` 는 TransitionLane 을 받아, 더 급한 업데이트가 들어오면 중단된다.

```tsx
function SearchPage({ allItems }: { allItems: string[] }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<string[]>([]);
  const [isPending, startTransition] = useTransition();

  function onChange(e: React.ChangeEvent<HTMLInputElement>) {
    setQuery(e.target.value);          // urgent — SyncLane, 즉시 반영
    startTransition(() => {
      setResults(filterHeavy(allItems, e.target.value)); // TransitionLane
    });
  }

  return (
    <>
      <input value={query} onChange={onChange} />
      <ul style={{ opacity: isPending ? 0.6 : 1 }}>{/* ... */}</ul>
    </>
  );
}
```

두 `setState` 가 같은 핸들러 안에 있는데 lane 이 다르다. `startTransition` 은 실행 직전 전역 플래그를 세우고 콜백을 동기로 호출한 뒤 복원하며, 그 사이의 `requestUpdateLane` 이 TransitionLane 을 반환한다. **콜백이 동기라는 점이 중요하다.** 콜백 안에 `await` 를 넣으면 그 이후의 `setState` 는 이미 플래그가 복원된 뒤라 트랜지션이 아니다.

`isPending` 의 정체는 자체 관리되는 boolean state 다. `startTransition` 은 콜백 전에 `setPending(true)` 를 **트랜지션보다 높은 우선순위로** 커밋하고, 트랜지션 렌더가 완료되는 커밋에서 `setPending(false)` 를 반영한다. 즉시 true 가 되지만 false 복귀는 트랜지션 커밋에 묶인다. 이 훅을 쓰는 컴포넌트는 매번 리렌더되므로 소비 범위를 작게 잘라둘어야 한다.

트랜지션 렌더가 40% 진행된 시점에 키 입력이 오면 React 는 그 workInProgress 트리를 **버리고**, 급한 업데이트를 처리한 뒤, 트랜지션을 **처음부터** 다시 시작한다. 재개가 아니라 재시작이므로, 렌더 함수가 순수하지 않으면 즉시 깨지고 렌더 함수 자체가 무거우면 반복 호출로 **총 작업량이 오히려 늘어난다**.

`useDeferredValue` 는 같은 메커니즘의 다른 포장으로, 값을 받아 "이전 값을 잠시 유지했다가 트랜지션 우선순위로 새 값을 반영"한다. 단 지연시킬 자식이 `React.memo` 로 감싸져 있어야 실효가 있다. 자식 렌더를 건너뛰어야 이전 결과 재사용의 의미가 살기 때문이다.

| 상황 | 선택 |
|---|---|
| 업데이트를 **발생시키는 쪽**을 제어할 수 있음 | `useTransition` |
| props 로 값만 받는 등 setState 지점에 손댈 수 없음 | `useDeferredValue` |
| pending UI 를 직접 표시해야 함 | `useTransition` (`isPending`) |
| 무거운 자식만 뒤처지게 하고 싶음 | `useDeferredValue` + `memo` |

## 7. Tearing 과 useSyncExternalStore

동시성 렌더가 도입한 새로운 종류의 버그가 tearing 이다. 한 렌더가 여러 슬라이스에 걸쳐 진행되는데, 슬라이스 사이의 빈틈에서 외부 스토어가 변경되면 **같은 렌더 트리 안의 두 컴포넌트가 서로 다른 값을 읽는다**. 화면 상단은 "재고 5개", 하단은 "재고 3개"가 동시에 그려지는 식이다. React state 는 렌더 시작 시점 스냅샷으로 고정되지만 외부 스토어는 그 보호를 못 받는다.

```tsx
// 잘못된 구독 — 렌더 도중 store 가 바뀌면 컴포넌트마다 다른 값을 본다
function useBrokenStore(store: Store) {
  const [value, setValue] = useState(store.getState());
  useEffect(() => store.subscribe(() => setValue(store.getState())), [store]);
  return value;
}

// 올바른 구독
function useStore(store: Store) {
  return useSyncExternalStore(
    store.subscribe,      // (onChange) => unsubscribe
    store.getState,       // client snapshot
    store.getServerState  // SSR/hydration snapshot
  );
}
```

`useSyncExternalStore` 는 두 가지를 보장한다. 첫째, 이 훅으로 읽은 값이 관여한 업데이트는 동기(중단 불가) 경로로 처리해 슬라이스 틈을 없앤다. 둘째, 커밋 직전에 스냅샷을 다시 읽어 렌더 도중 읽은 값과 다르면 그 렌더를 폐기하고 재렌더한다. 훅 이름의 "Sync" 는 **동시성 포기 선언**이며, 외부 스토어 구독이 트랜지션의 이득을 받지 못한다는 뜻이다. 전역 상태를 외부 스토어로 빼는 설계의 숨은 비용이다.

`getSnapshot` 의 참조 안정성은 대표적 함정이다.

```tsx
// 무한 루프 — 매번 새 배열 참조
const todos = useSyncExternalStore(sub, () => store.getState().todos.filter(t => !t.done));

// 해결: 캐시된 참조를 반환하고 파생은 밖에서
const all = useSyncExternalStore(sub, store.getState);
const todos = useMemo(() => all.todos.filter(t => !t.done), [all.todos]);
```

React 는 스냅샷을 `Object.is` 로 비교한다. `getSnapshot` 이 매번 새 객체를 만들면 항상 "변경됨"이 되어 렌더 → 스냅샷 재확인 → 불일치 → 렌더의 루프에 빠진다. Zustand, Jotai, Redux 등이 이 훅 위에서 셀렉터 결과를 캐싱하고 `useSyncExternalStoreWithSelector` 같은 shim 을 두는 이유다.

## 8. Suspense 결합과 Automatic Batching

트랜지션과 Suspense 는 한 세트로 설계됐다. 이미 콘텐츠가 보이는 화면에서 트랜지션이 새 Suspense 바운더리를 트리거하면, React 는 **fallback 으로 되돌리지 않고 기존 UI 를 유지**한 채 뒤에서 새 트리를 준비한다. 트랜지션 없이 같은 업데이트를 하면 즉시 fallback 이 나타난다 — 급하다고 선언했으니 "지금 보여줄 수 있는 것"을 보여줄 뿐이다. 첫 마운트에는 이전 UI 가 없으므로 트랜지션이어도 fallback 이 나온다.

Automatic Batching 도 같은 lane 인프라 위에 있다. 17 까지는 이벤트 핸들러 안에서만 배칭됐고, `setTimeout` 이나 `.then()` 안의 `setState` 는 각각 별도 렌더를 유발했다.

```tsx
// React 17: 렌더 2회 / React 18+: 렌더 1회
fetch('/api/user').then(() => {
  setUser(u);
  setLoading(false);
});

// 배칭을 깨야 할 때 (레이아웃 측정 등)
flushSync(() => setUser(u)); // 즉시 커밋, 직후 DOM 읽기 가능
```

18 은 lane 이 같으면 어디서 발생했든 하나의 렌더로 합친다. `flushSync` 는 동시성의 이득을 국소적으로 포기하는 탈출구이므로 명확한 이유가 있을 때만 쓴다.

## 9. 실무 진단과 판단 기준

DevTools Profiler 의 Flamegraph 는 커밋 단위로 결과를 보여준다. 트랜지션이 동작한다면 한 번의 입력에 **커밋이 둘로 갈라진 모습**이 보여야 한다 — 입력값만 반영한 짧은 커밋과, 뒤이어 무거운 리스트를 반영한 긴 커밋. 여전히 하나의 긴 덩어리라면 트랜지션이 안 먹은 것이다.

`<StrictMode>` 의 이중 호출은 3절과 이어진다. 렌더 함수와 초기화 함수를 두 번 호출해 결과가 달라지면 순수성 위반이며, 그 위반은 프로덕션의 동시성 렌더에서 중단·재시작이 일어날 때 실제 버그가 된다. effect 를 mount → unmount → mount 로 돌리는 것은 cleanup 누락을 잡는다. 이중 호출은 노이즈가 아니라 **동시성 안전성 린터**다.

흔한 오해는 `useTransition` 이 느린 코드를 빠르게 만든다는 것이다. 트랜지션은 **총 작업량을 줄이지 않는다.** 재시작 때문에 오히려 늘리며, 순서를 재배치해 급한 것을 먼저 통과시킬 뿐이다.

| 상황 | 판단 |
|---|---|
| 무거운 렌더와 **동시에** 다른 입력이 들어옴 (검색어 입력 + 대형 결과 리스트, 탭 전환) | 효과 있음 |
| 무거운 렌더가 있지만 동시 입력이 없음 (버튼 → 무거운 화면) | 효과 없음. 지연만 늘어남 |
| 렌더 자체가 빠름(수 ms) | 효과 없음. `isPending` 리렌더로 손해 |
| 병목이 렌더가 아니라 네트워크 | 무관. Suspense 나 Worker 가 답 |
| 단일 컴포넌트 하나가 50ms 를 씀 | 효과 없음. `shouldYield` 는 unit 경계에서만 |

먼저 할 일은 트랜지션 도입이 아니라 원인 규명이다. 렌더 시간이 노드 수에 비례하면 가상 스크롤이, 컴포넌트 하나가 무거우면 `useMemo` 나 Worker 가, 렌더가 반복되면 `memo` 와 참조 안정화가 먼저다. 트랜지션은 **작업이 이미 최소화됐는데도 무겁고, 그 와중에 사용자 입력을 받아야 하는** 마지막 단계의 도구다.

게다가 트랜지션 렌더는 슬라이스로 쪼개진 만큼 macrotask 왕복 비용이 붙어 **벽시계 기준 완료 시간이 길어진다**. 즉각적 입력 반응을 얻는 대신 결과가 느려지는 거래이며, 유리한지는 측정으로 답해야 한다.

## 참고

- React 공식 문서 — `useTransition`, `useDeferredValue`, `useSyncExternalStore` API 레퍼런스 (react.dev)
- React 공식 블로그 — "React 18: Automatic Batching", "New Suspense SSR Architecture"
- facebook/react — `packages/react-reconciler/src/ReactFiberLane.js` (Lane 정의 및 entanglement)
- facebook/react — `packages/react-reconciler/src/ReactFiberWorkLoop.js` (workLoopConcurrent, markStarvedLanesAsExpired)
- facebook/react — `packages/scheduler/src/forks/SchedulerDOM.js` (MessageChannel 기반 host config)
- reactwg/react-18 Discussions — Concurrent Rendering, Tearing 논의
