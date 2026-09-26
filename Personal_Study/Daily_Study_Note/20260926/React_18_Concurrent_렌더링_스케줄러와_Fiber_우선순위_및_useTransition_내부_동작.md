Notion 원본: https://app.notion.com/p/3e75a06fd6d38100bde0de954769d523

# React 18 Concurrent 렌더링 스케줄러와 Fiber 우선순위 및 useTransition 내부 동작

> 2026-09-26 신규 주제 · 확장 대상: React

## 학습 목표

- React 18의 Concurrent 렌더링이 기존 동기 렌더링과 구조적으로 다른 지점(중단 가능성)을 구분한다
- Fiber 트리에서 우선순위(Lane)가 스케줄링 결정에 어떻게 반영되는지 추적한다
- `useTransition`과 `useDeferredValue`가 각각 어떤 렌더링 경로를 지연시키는지 실전 코드로 비교한다
- 시간 분할(Time Slicing)이 브라우저 프레임 예산과 상호작용하는 방식을 `requestIdleCallback` 계열 스케줄러와 대응시킨다

## 1. 동기 렌더링에서 중단 가능한 렌더링으로

React 17까지의 렌더링은 한 번 시작되면 끝까지 동기적으로 완료되는 스택 기반 재귀 알고리즘이었다. 컴포넌트 트리가 깊거나 큰 리스트를 렌더링할 때, 이 재귀는 자바스크립트 콜 스택을 오래 점유해 메인 스레드를 블로킹하고, 그 사이 사용자 입력이나 애니메이션 프레임 갱신이 지연되는 현상(입력 지연, jank)을 일으켰다. React 16에서 도입된 Fiber 아키텍처는 재귀 대신 링크드 리스트 형태의 트리 순회로 렌더링 알고리즘을 재작성해, 원칙적으로는 임의의 지점에서 작업을 멈추고 나중에 재개할 수 있는 구조를 마련했다. React 18의 Concurrent 렌더링은 이 구조를 실제로 활용해, 우선순위가 낮은 렌더링 작업을 브라우저에 남은 프레임 시간이 부족해지면 중단하고 제어권을 브라우저에 돌려주는 시간 분할을 구현한다.

## 2. Fiber 노드와 작업 단위

Fiber는 컴포넌트 인스턴스에 대응하는 자바스크립트 객체로, 각 Fiber는 하나의 "작업 단위(unit of work)"를 나타낸다. 렌더링(정확히는 리액트 내부 용어로 "Render 단계")은 이 Fiber 트리를 깊이 우선으로 순회하며 각 노드마다 `beginWork`와 `completeWork`를 호출하는 과정이다.

```javascript
function workLoopConcurrent() {
  while (workInProgress !== null && !shouldYield()) {
    performUnitOfWork(workInProgress);
  }
}
```

`shouldYield()`가 핵심이다. 이 함수는 현재 프레임에 할당된 시간 예산(기본적으로 약 5ms 단위)을 소진했는지 확인하고, 소진했다면 `true`를 반환해 루프를 빠져나가게 만든다. 루프를 빠져나가면 React는 나머지 작업을 다음 매크로태스크(주로 `MessageChannel`을 이용한 콜백)로 예약하고 제어권을 브라우저 이벤트 루프에 돌려준다. 이는 `requestIdleCallback`과 목적은 비슷하지만, `requestIdleCallback`의 콜백 실행 시점이 브라우저 스케줄러에 완전히 위임되어 지연이 불확실한 것과 달리, React 자체 스케줄러(`scheduler` 패키지)는 우선순위 큐와 자체 타이머 기반 구현으로 더 예측 가능한 타이밍을 확보한다.

## 3. Lane 모델: 우선순위의 비트마스크 표현

React 18은 업데이트의 우선순위를 "Lane"이라는 31비트 비트마스크로 표현한다. 각 비트는 서로 다른 우선순위 레벨(또는 같은 레벨 내 배치 그룹)을 나타내며, 여러 업데이트가 동시에 대기 중일 때 비트 연산만으로 우선순위 병합과 비교를 빠르게 수행할 수 있다.

<table header-row="true"><tr><td>Lane 종류</td><td>대표 트리거</td><td>특징</td></tr><tr><td>SyncLane</td><td>동기 이벤트 핸들러 내 setState</td><td>즉시, 중단 불가</td></tr><tr><td>InputContinuousLane</td><td>드래그, 스크롤 등 연속 입력</td><td>높은 우선순위지만 중단 가능</td></tr><tr><td>DefaultLane</td><td>일반적인 setState, 데이터 fetch 완료</td><td>중간 우선순위</td></tr><tr><td>TransitionLane</td><td>startTransition으로 감싼 업데이트</td><td>낮은 우선순위, 중단·폐기 가능</td></tr><tr><td>IdleLane</td><td>거의 실행되지 않아도 되는 작업</td><td>최저 우선순위</td></tr></table>

같은 컴포넌트에 여러 Lane의 업데이트가 동시에 존재할 수 있다는 점이 중요하다. 예를 들어 사용자가 입력 필드에 타이핑하는 것은 `SyncLane`(또는 `InputContinuousLane`)으로 즉시 반영되어야 하지만, 그 입력값으로 필터링한 대용량 리스트 재렌더링은 `TransitionLane`으로 낮은 우선순위를 부여해, 타이핑 반응성을 해치지 않으면서 리스트는 여유가 생길 때 갱신되도록 분리할 수 있다.

## 4. useTransition의 내부 동작

`useTransition`이 반환하는 `startTransition` 함수로 감싼 상태 갱신은 `TransitionLane`으로 스케줄링된다.

```javascript
function SearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [isPending, startTransition] = useTransition();

  function handleChange(e) {
    setQuery(e.target.value); // SyncLane: 입력창은 즉시 반영
    startTransition(() => {
      setResults(computeExpensiveFilter(e.target.value)); // TransitionLane
    });
  }

  return (
    <>
      <input value={query} onChange={handleChange} />
      {isPending && <Spinner />}
      <ResultList items={results} />
    </>
  );
}
```

`setQuery`는 일반 업데이트이므로 입력창의 값은 매 키 입력마다 즉시 반영되어 타이핑 지연이 없다. 반면 `setResults`는 `TransitionLane`으로 스케줄링되어, React 스케줄러가 더 급한(높은 우선순위) 작업이 없을 때 처리한다. 만약 사용자가 빠르게 연속으로 타이핑하면, 이전에 시작된 `TransitionLane` 렌더링 작업은 완료되지 않은 상태에서 새로운 `TransitionLane` 작업으로 대체(interrupt)될 수 있는데, 이는 Concurrent 렌더링이 "커밋되지 않은 렌더링 결과는 언제든 버려도 안전하다"는 순수성 가정 위에서 동작하기 때문에 가능한 최적화다. `isPending`은 이 전환 작업이 아직 진행 중인지를 나타내는 플래그로, 스피너 같은 UI 피드백을 연결하는 용도로 쓰인다.

## 5. useDeferredValue와의 차이

`useDeferredValue`는 `useTransition`과 유사한 지연 효과를 내지만, 상태 갱신 함수 자체가 아니라 이미 존재하는 값의 "지연된 사본"을 만든다는 점이 다르다.

```javascript
function SearchPage() {
  const [query, setQuery] = useState("");
  const deferredQuery = useDeferredValue(query);

  return (
    <>
      <input value={query} onChange={(e) => setQuery(e.target.value)} />
      <ResultList query={deferredQuery} />
    </>
  );
}
```

여기서는 `setQuery`가 상태 갱신의 유일한 진입점이고, `deferredQuery`는 React가 내부적으로 "최신 값을 낮은 우선순위로 따라가는 값"으로 관리한다. `query`가 빠르게 바뀌는 동안 `deferredQuery`는 이전 값을 유지하다가, 메인 스레드에 여유가 생기면 최신 값으로 갱신된다. `useTransition`은 "이 상태 갱신 자체를 낮은 우선순위로 만들겠다"는 명령형 API이고, `useDeferredValue`는 "이 값의 소비를 낮은 우선순위로 지연시키겠다"는 선언형 API라는 것이 실전에서 어떤 훅을 선택할지 가르는 기준이 된다. 상태를 직접 소유하고 있고 갱신 트리거를 감쌀 수 있다면 `useTransition`이, 부모로부터 받은 props나 제어할 수 없는 외부 상태를 다뤄야 한다면 `useDeferredValue`가 더 적합하다.

## 6. 렌더 단계와 커밋 단계의 분리

Concurrent 렌더링에서 중단 가능한 것은 어디까지나 "Render 단계"뿐이다. React는 Render 단계(Fiber 트리를 순회하며 어떤 변경이 필요한지 계산하는 단계)와 Commit 단계(계산된 변경을 실제 DOM에 반영하는 단계)를 명확히 분리하며, Commit 단계는 항상 동기적으로 한 번에 완료된다.

```
Render 단계 (중단·재개·폐기 가능) → Commit 단계 (동기, 중단 불가) → 브라우저 페인트
```

이 분리가 필요한 이유는, DOM 변경을 부분적으로만 적용한 채 중단하면 화면이 일관성 없는 중간 상태로 사용자에게 보이는 문제가 생기기 때문이다. Render 단계에서 만들어지는 `useState`, `useMemo` 계산 결과는 실제 DOM에 아직 반영되지 않은 순수 계산의 산출물이므로 자유롭게 버리거나 다시 계산해도 부작용이 없지만, Commit 단계에서 실행되는 `useLayoutEffect`나 실제 DOM 조작은 되돌리기 어려운 부작용을 동반하므로 반드시 원자적으로 완료되어야 한다는 설계 원칙이 이 두 단계의 경계를 결정한다.

## 7. 시간 분할이 실제로 체감되는 조건

시간 분할의 효과는 렌더링해야 할 컴포넌트 수가 많고, 각 컴포넌트의 렌더링 자체는 가볍지만 총합이 한 프레임 예산(약 16ms, 60fps 기준)을 넘어설 때 가장 뚜렷하게 나타난다. 반대로 단일 컴포넌트의 렌더링 함수 자체가 무거운 동기 연산(예: 큰 배열의 정렬을 렌더링 함수 안에서 직접 수행)을 포함하면, Fiber 단위로 작업이 쪼개지지 않으므로 그 컴포넌트 하나의 처리 시간 동안은 여전히 메인 스레드가 블로킹된다.

<table header-row="true"><tr><td>상황</td><td>시간 분할 효과</td><td>권장 대응</td></tr><tr><td>많은 자식 컴포넌트의 얕은 렌더링 합산 지연</td><td>큼</td><td>TransitionLane으로 낮은 우선순위 부여</td></tr><tr><td>단일 컴포넌트 내부의 무거운 동기 계산</td><td>없음(Fiber 경계 안에서는 분할 불가)</td><td>useMemo로 캐싱, 웹 워커로 이전</td></tr><tr><td>연속 입력 이벤트 폭주</td><td>큼</td><td>InputContinuousLane 자동 적용, 디바운스 병행</td></tr></table>

이 때문에 `useTransition`을 적용했는데도 체감 성능이 개선되지 않는다면, 문제가 "렌더링해야 할 컴포넌트가 많아서"가 아니라 "하나의 렌더 함수 안에서 무거운 연산을 직접 수행해서"인지부터 프로파일러(React DevTools Profiler)로 구분하는 것이 실전 디버깅의 첫 단계다.

## 8. 더블 버퍼링: current 트리와 workInProgress 트리

Fiber 아키텍처는 화면에 실제로 반영된 상태를 나타내는 `current` 트리와, 다음 렌더링을 계산 중인 `workInProgress` 트리 두 벌을 동시에 유지한다. 이는 그래픽스 프로그래밍의 더블 버퍼링과 개념적으로 동일하다. 렌더링이 진행되는 동안에도 화면에는 여전히 완성된 `current` 트리가 그대로 보이고, `workInProgress` 트리에서의 계산이 모두 끝나 커밋 단계에 들어가는 순간 두 트리의 역할이 맞바뀐다(포인터 스왑). 이 구조 덕분에 렌더링이 중단되었다가 재개되어도, 그리고 심지어 중단된 렌더링이 통째로 폐기되어도, 사용자에게 보이는 화면(`current` 트리)은 항상 마지막으로 커밋된 완전한 상태를 유지한다. 각 Fiber 노드는 `alternate` 포인터로 자신의 짝을 참조하며, 새 렌더링을 시작할 때 기존 `alternate`를 재사용해 메모리 할당을 줄인다.

## 9. StrictMode와 Concurrent 렌더링의 상호작용

React 18의 `StrictMode`는 개발 모드에서 컴포넌트 함수와 일부 훅을 의도적으로 두 번 호출해, 렌더링 함수가 부작용 없이 순수해야 한다는 Concurrent 렌더링의 전제를 개발자가 어기고 있는지 조기에 드러낸다.

```javascript
function ProductList({ items }) {
  console.log("render"); // StrictMode 개발 모드에서 연속 두 번 출력됨
  const sorted = items.slice().sort((a, b) => a.price - b.price);
  return <ul>{sorted.map((item) => <li key={item.id}>{item.name}</li>)}</ul>;
}
```

만약 렌더링 함수 안에서 외부 변수를 직접 변경하거나 전역 카운터를 증가시키는 식의 부작용이 있다면, 두 번 호출되는 개발 모드에서 그 부작용도 두 번 발생해 버그가 명확히 드러난다. 이는 실제 Concurrent 렌더링이 필요에 따라 같은 컴포넌트를 여러 번 다시 계산하거나 계산 결과를 버릴 수 있다는 것을 프로덕션에서 조용히 겪기 전에, 개발 단계에서 강제로 재현시켜 발견하게 만드는 안전장치다.

## 참고

- React 공식 문서, "Concurrent Features" 및 "useTransition", "useDeferredValue" 레퍼런스
- Dan Abramov 외, React 18 Working Group 논의 아카이브(GitHub Discussions)
- Lin Clark, "A Cartoon Intro to Fiber" React Conf 발표
- React 소스 저장소, packages/scheduler 구현
