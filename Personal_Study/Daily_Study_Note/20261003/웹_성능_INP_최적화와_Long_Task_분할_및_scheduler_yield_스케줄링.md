Notion 원본: https://www.notion.so/3ef5a06fd6d3817b9db0f0c7694d6371

# 웹 성능 INP 최적화와 Long Task 분할 및 scheduler yield 스케줄링

> 2026-10-03 신규 주제 · 확장 대상: React 18 Concurrent 렌더링 스케줄러와 useTransition, Signals 기반 세밀 반응성

## 학습 목표

- INP가 입력 지연, 처리 시간, 프레젠테이션 지연 세 구간으로 구성됨을 분해해 병목 구간을 판별한다.
- Long Task(50ms 초과)가 입력 응답을 막는 메커니즘과 `scheduler.yield()` 기반 분할 방법을 구현한다.
- `PerformanceObserver`와 `web-vitals` 어트리뷰션으로 느린 상호작용의 원인 요소와 스크립트를 찾는다.
- React의 `useTransition`·`useDeferredValue`와 CSS `content-visibility`로 렌더링 비용을 줄인다.

## 1. INP는 무엇을 측정하는가

INP(Interaction to Next Paint)는 페이지 방문 중 발생한 모든 클릭·탭·키 입력 상호작용의 **응답 지연** 가운데 거의 최악에 해당하는 값을 대표값으로 쓰는 지표다. 2024년 3월 12일부터 Core Web Vitals에서 FID를 대체했다. 한 번의 상호작용 지연은 입력이 발생한 시점부터 그 입력의 결과가 화면에 그려지는 다음 프레임까지의 시간이다. 구글 기준으로 페이지 로드의 75번째 백분위에서 **200ms 이하가 Good, 200~500ms가 Needs Improvement, 500ms 초과가 Poor**다.

FID는 첫 입력의 "입력 지연" 부분만 쟀지만 INP는 모든 상호작용을 대상으로 하고, 이벤트 핸들러 실행과 렌더링까지 포함한다. 그래서 첫 로드는 빠르지만 사용 중에 느려지는 SPA의 문제가 INP에서 드러난다. 상호작용이 많은 페이지는 가장 느린 것 하나(상호작용 50회당 최악 하나를 제외하는 방식으로 이상치를 일부 보정)가 대표값이 된다.

한 상호작용의 지연은 세 구간으로 쪼갠다.

**입력 지연(Input delay)**: 입력이 발생했지만 메인 스레드가 다른 작업(Long Task)으로 바빠 핸들러가 시작되지 못한 시간. **처리 시간(Processing time)**: 이벤트 핸들러 콜백들이 실행되는 시간. **프레젠테이션 지연(Presentation delay)**: 핸들러가 끝난 뒤 브라우저가 스타일 계산, 레이아웃, 페인트를 거쳐 다음 프레임을 그리기까지의 시간. 최적화 방향은 구간마다 다르므로 먼저 어느 구간이 큰지 판별해야 한다.

## 2. 메인 스레드와 Long Task

브라우저의 메인 스레드는 JS 실행, 스타일·레이아웃, 페인트, 입력 이벤트 처리를 **하나의 스레드**에서 번갈아 한다. 실행 중인 태스크는 중단되지 않으므로, 한 태스크가 길면 그동안 입력 이벤트는 큐에서 기다린다. 50ms를 넘는 태스크를 **Long Task**라 부른다. 이 기준은 사용자가 100ms 이내를 즉각적이라고 느낀다는 RAIL 모델에서, 입력 처리에 쓸 여유를 남기기 위해 태스크를 50ms로 제한하라는 데서 나왔다.

따라서 입력 지연을 줄이는 방법은 긴 작업을 **짧은 조각으로 나누고 조각 사이에 메인 스레드를 브라우저에 돌려주는 것**(yield)이다. 조각 사이에 대기 중이던 입력 이벤트가 처리된다.

## 3. 양보하기: setTimeout에서 scheduler.yield까지

가장 오래된 방법은 `setTimeout(fn, 0)`이다. 다음 태스크로 작업을 미루어 메인 스레드를 양보하지만, 단점이 있다. 큐의 맨 뒤로 들어가므로 이미 대기하던 다른 작업이 먼저 실행되어 **내 작업의 재개가 늦어질 수 있고**, 중첩 타이머는 최소 지연(약 4ms)이 적용되기도 한다.

`scheduler.yield()`(Chromium 계열 129 이후에서 지원, 다른 브라우저는 지원 여부를 확인해야 한다)는 양보한 뒤 **재개되는 작업에 우선권을 준다.** 즉 입력 처리 같은 대기 작업을 먼저 처리하되, 내 작업은 다른 일반 작업보다 앞서 이어서 실행된다. 반환값이 Promise이므로 `await`로 자연스럽게 쓴다. 미지원 브라우저를 위해 `setTimeout` 폴백을 둔다.

```ts
declare global {
	interface Scheduler { yield?: () => Promise<void>; }
	var scheduler: Scheduler | undefined;
}

export function yieldToMain(): Promise<void> {
	if (typeof scheduler !== "undefined" && typeof scheduler.yield === "function") {
		return scheduler.yield();
	}
	return new Promise((resolve) => setTimeout(resolve, 0));
}

export async function processInChunks<T>(
	items: readonly T[],
	handle: (item: T) => void,
	budgetMs = 50,
	now: () => number = () => performance.now(),
): Promise<number> {
	let yields = 0;
	let deadline = now() + budgetMs;
	for (const item of items) {
		handle(item);
		if (now() >= deadline) {     // 예산을 넘기면 양보하고 다음 조각 시작
			await yieldToMain();
			yields++;
			deadline = now() + budgetMs;
		}
	}
	return yields;
}
```

핵심은 **조각 크기를 개수가 아니라 시간 예산으로 정하는 것**이다. 항목 100개씩 같은 고정 개수는 항목마다 비용이 달라 예측이 어렵다. 위 코드는 시계를 주입받아 테스트 가능하게 만들었다. 아래 테스트는 TypeScript 5.9.3으로 컴파일해 Node 22에서 실행했고, 각 항목이 20ms를 쓰는 가상 시계에서 6개 항목 처리 중 정확히 2번 양보(`yields=2`)하고 모든 항목을 순서대로 처리하는 것을 확인했다.

```ts
let t = 0;
const clock = () => t;
const handled: number[] = [];
const y = await processInChunks([1, 2, 3, 4, 5, 6], (n) => { handled.push(n); t += 20; }, 50, clock);
// 항목 3 처리 후 t=60 ≥ 50 → 양보, 항목 6 처리 후 t=60 ≥ 50 → 양보
console.assert(y === 2 && handled.length === 6);
```

양보에도 비용이 있다. 각 양보는 태스크 스케줄링 오버헤드를 더하므로 너무 잘게 나누면 전체 완료 시간이 늘어난다. 예산을 5~10ms처럼 지나치게 작게 잡는 것은 피한다. 반대로 `scheduler.postTask()`는 우선순위(`user-blocking`, `user-visible`, `background`)를 지정해 작업을 예약하는 API이며, 백그라운드 분석 코드는 `background`로 보내 입력 처리와 경쟁하지 않게 한다.

### 핸들러 안에서 "먼저 그리고, 나중에 처리"

처리 시간 구간의 대표적 개선은 사용자에게 보여야 하는 변화를 먼저 반영하고 나머지를 양보 뒤로 미루는 것이다. 버튼을 눌렀을 때 로딩 표시 같은 즉각적 피드백을 먼저 DOM에 반영하고, 무거운 계산·네트워크·분석 전송은 그 뒤로 보낸다.

```ts
button.addEventListener("click", async () => {
	button.disabled = true;                  // 1. 즉각적 시각 피드백
	spinner.hidden = false;
	await yieldToMain();                     // 2. 브라우저가 다음 프레임을 그릴 기회를 줌
	const result = computeHeavy(input);      // 3. 무거운 작업
	render(result);
	await yieldToMain();
	sendAnalytics("clicked");                // 4. 사용자 가치와 무관한 작업은 맨 뒤로
});
```

## 4. 측정: 어느 구간이 문제인가

개선은 측정에서 시작한다. **현장 데이터(RUM)** 와 **실험실 데이터**를 구분한다. INP는 실제 사용자 상호작용이 필요하므로 현장 측정이 기준이다. 구글 Chrome UX Report(CrUX)와 PageSpeed Insights가 현장 값을 제공한다. 자체 수집은 `web-vitals` 라이브러리의 attribution 빌드를 쓴다.

```ts
import { onINP } from "web-vitals/attribution";

onINP((metric) => {
	const a = metric.attribution;
	navigator.sendBeacon("/rum", JSON.stringify({
		value: metric.value,                       // ms
		rating: metric.rating,                     // good | needs-improvement | poor
		target: a.interactionTarget,               // 느린 상호작용의 요소 선택자
		type: a.interactionType,                   // pointer | keyboard
		inputDelay: a.inputDelay,
		processingDuration: a.processingDuration,
		presentationDelay: a.presentationDelay,
		loadState: a.loadState,
	}));
});
```

세 구간 값이 나오므로 해석은 간단하다. 입력 지연이 크면 상호작용 시점에 다른 Long Task가 있었던 것이므로 스크립트 로딩, 타이머, 폴링을 점검한다. 처리 시간이 크면 핸들러 자체와 그 안의 동기 작업(상태 업데이트에 따른 동기 렌더링 포함)이 원인이다. 프레젠테이션 지연이 크면 DOM이 너무 크거나 레이아웃·스타일 재계산이 무겁다.

세부 원인은 **Long Animation Frames(LoAF) API**(`long-animation-frame` 타입, Chrome 123 이후)로 찾는다. 50ms를 넘는 프레임 동안 어떤 스크립트가 얼마나 실행됐는지(소스 URL, 함수 이름, 호출 방식)를 알려줘 Long Task API보다 원인 추적이 쉽다. 같은 방식으로 `event` 타입 엔트리에 `durationThreshold`를 지정해 느린 이벤트를 수집한다.

```ts
new PerformanceObserver((list) => {
	for (const entry of list.getEntries() as PerformanceEventTiming[]) {
		if (entry.duration >= 200 && entry.interactionId) {
			console.warn("느린 상호작용", entry.name, entry.duration, entry.target);
		}
	}
}).observe({ type: "event", durationThreshold: 200, buffered: true });

try {
	new PerformanceObserver((list) => {
		for (const f of list.getEntries()) {
			console.warn("LoAF", Math.round(f.duration), (f as any).scripts?.map((s: any) => s.sourceURL));
		}
	}).observe({ type: "long-animation-frame", buffered: true });
} catch { /* 미지원 브라우저 */ }
```

실험실에서는 Chrome DevTools Performance 패널에서 상호작용을 직접 수행하고 "Interactions" 트랙의 막대를 열어 세 구간을 확인한다. 쓰로틀링(CPU 4~6배 감속)을 걸어 저사양 기기를 흉내 내면 현장의 느린 사례가 재현되기 쉽다. 고사양 개발 머신에서 INP가 양호해도 중저가 모바일에서는 Poor일 수 있다.

## 5. 프레임워크 레벨 전략: React

React 18의 동시성 기능은 INP 개선과 직접 연결된다. 상태 업데이트 중 급하지 않은 것을 `startTransition`/`useTransition`으로 표시하면 React가 렌더링을 **중단 가능**하게 만들고, 더 급한 입력이 들어오면 우선 처리한다.

```tsx
function Search({ items }: { items: string[] }) {
	const [query, setQuery] = useState("");
	const [isPending, startTransition] = useTransition();
	const [filter, setFilter] = useState("");

	return (
		<>
			<input
				value={query}
				onChange={(e) => {
					setQuery(e.target.value);                  // 긴급: 입력창 즉시 갱신
					startTransition(() => setFilter(e.target.value)); // 비긴급: 목록 필터링
				}}
			/>
			{isPending && <span>필터링 중…</span>}
			<List items={items} filter={filter} />
		</>
	);
}
```

`useDeferredValue(query)`는 같은 효과를 값 단위로 얻는 방법이다. 한계도 분명하다. Transition은 **렌더링을 쪼개줄 뿐** 렌더 함수 자체가 느리면(수천 개 노드를 매번 다시 만드는 경우) 한 번의 렌더 조각이 여전히 길 수 있다. 이때는 가상 스크롤(windowing), 메모이제이션(`React.memo`, `useMemo`), 상태 위치 조정(상태를 필요한 하위 트리 안으로 내리기)을 병행한다. 또 이벤트 핸들러 안에서 같은 Long Task를 일으키는 동기 계산은 transition으로 해결되지 않으므로 앞 절의 양보 패턴이나 Web Worker로 옮겨야 한다.

## 6. 프레젠테이션 지연 줄이기

핸들러가 빨라도 DOM 변경 후 레이아웃과 스타일 재계산이 크면 프레임이 늦는다. 대량의 DOM(수천 개 이상)은 스타일 재계산 범위를 넓힌다. **`content-visibility: auto`** 는 화면 밖 섹션의 렌더링 작업(레이아웃, 페인트)을 건너뛰게 해 초기 비용과 이후 갱신 비용을 줄인다. 건너뛴 영역의 크기를 몰라 스크롤바가 흔들리지 않도록 `contain-intrinsic-size`로 예상 크기를 지정한다.

```css
.feed-item {
	content-visibility: auto;
	contain-intrinsic-size: auto 320px; /* auto: 한 번 렌더한 뒤 실제 크기를 기억 */
}
```

레이아웃 스래싱도 흔한 원인이다. 같은 프레임에서 DOM 쓰기와 `offsetHeight`, `getBoundingClientRect` 같은 읽기를 번갈아 하면 브라우저가 매번 강제 동기 레이아웃을 수행한다. 읽기를 먼저 모두 모은 뒤 쓰기를 일괄 처리한다. 애니메이션은 `transform`/`opacity`처럼 합성(composite) 단계에서 처리 가능한 속성만 바꾸는 편이 레이아웃을 유발하지 않는다.

## 7. 서드파티 스크립트와 이벤트 위임

실무에서 INP 악화의 큰 비중은 **서드파티 태그**(분석, A/B 테스트, 채팅 위젯)가 차지한다. 이들이 `click`이나 `pointerdown`에 핸들러를 달아 우리 핸들러 앞뒤로 동기 작업을 끼워 넣기 때문이다. LoAF의 `scripts` 정보로 출처를 확인해 지연 로딩(`defer`, 사용자 상호작용 후 로드), Partytown 같은 워커 격리, 불필요한 태그 제거를 검토한다. 이벤트 핸들러는 가능하면 `passive` 옵션으로 등록하고, 문서 전체에 거는 무거운 `mousemove`·`scroll` 핸들러는 `requestAnimationFrame` 또는 쓰로틀링으로 호출 빈도를 줄인다.

이벤트 위임도 도움이 된다. 리스트의 각 항목에 핸들러를 붙이면 항목 수만큼 리스너 메모리와 등록 비용이 든다. 부모에 하나의 리스너를 두고 `event.target.closest()`로 분기하면 DOM이 커져도 비용이 일정하다.

## 8. 개선 절차와 trade-off

실제 개선은 다음 순서로 진행한다. (1) CrUX/RUM으로 INP가 Poor인 페이지와 상호작용을 식별한다. (2) 어트리뷰션으로 세 구간 중 지배적인 구간을 확인한다. (3) 입력 지연이면 Long Task 원인(번들 크기, 서드파티, 주기적 작업)을 줄이고, 처리 시간이면 핸들러를 분할·지연하며, 프레젠테이션 지연이면 DOM과 렌더링 비용을 줄인다. (4) 배포 후 RUM에서 75번째 백분위가 내려갔는지를 최소 1~2주 데이터로 확인한다. 현장 지표는 방문자 구성에 민감하므로 단기간 값으로 효과를 단정하지 않는다.

trade-off도 있다. 작업을 잘게 나누면 응답성은 좋아지지만 **전체 완료 시간이 늘고**, 양보 사이에 상태가 바뀌어 **일관성 문제**가 생길 수 있다(사용자가 처리 도중 다른 입력을 하면 이전 작업을 취소하거나 재시작해야 한다). `AbortController`로 취소를 구현하고, 조각 사이마다 취소 여부를 확인하는 것이 안전하다. Web Worker로 계산을 옮기면 메인 스레드를 완전히 비울 수 있지만 데이터 직렬화(structured clone) 비용과 DOM 접근 불가라는 제약이 따른다. 큰 배열은 Transferable로 넘기면 복사를 피할 수 있다.

위 INP 임계값과 API 지원 범위는 이 노트 작성 시점의 공식 문서(web.dev, MDN)에 근거한 것이며, 브라우저 지원 현황은 자주 바뀌므로 적용 전 MDN 호환성 표를 확인해야 한다.

## 참고

- web.dev: Interaction to Next Paint (INP), Optimize Interaction to Next Paint, Optimize long tasks
- web.dev: Long Animation Frames API
- MDN: `Scheduler.yield()`, `Scheduler.postTask()`, `PerformanceEventTiming`, `content-visibility`
- GoogleChrome/web-vitals 저장소: attribution 빌드 문서
- React 공식 문서: `useTransition`, `useDeferredValue`
