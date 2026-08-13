Notion 원본: https://app.notion.com/p/3bb5a06fd6d38150932cc2832283b48d?pvs=204

# 브라우저 렌더링 파이프라인과 INP 최적화 및 scheduler.yield 태스크 분할

> 2026-08-13 신규 주제 · 확장 대상: WEB, Javascript, React

## 학습 목표

- 이벤트 루프의 태스크·마이크로태스크·렌더링 스텝 순서를 기준으로 INP 3구간을 분해한다.
- Long Task 와 Long Animation Frame API 로 지연을 유발한 스크립트를 특정한다.
- `scheduler.postTask` 우선순위와 `scheduler.yield` 양보 패턴으로 긴 작업을 분할한다.
- `content-visibility`·`contain` 과 read/write 분리로 렌더링 비용을 낮춘다.

## 1. 이벤트 루프와 렌더링 스텝

이벤트 루프는 태스크 하나를 끝까지 실행하고, 마이크로태스크 체크포인트에서 큐가 빌 때까지 소진한 뒤 렌더링 업데이트 기회를 준다. 렌더링은 `requestAnimationFrame` → 스타일 → 레이아웃 → 프리페인트 → 페인트 → 컴포지트 순이며, 핵심은 **태스크 사이에만 끼어들 수 있다**는 점이다. 태스크 하나가 300ms 돌면 그동안 화면은 갱신되지 않고 입력도 큐에서 대기만 한다.

60Hz 라면 프레임 예산은 약 16.7ms, 120Hz 라면 약 8.3ms 다. W3C 스펙은 메인 스레드를 **50ms 이상** 점유하는 작업을 Long Task 로 규정한다.

```js
console.log('1. 동기 코드 (현재 태스크)');
queueMicrotask(() => console.log('3. 마이크로태스크'));
requestAnimationFrame(() => console.log('4. rAF — 렌더링 스텝 직전'));
setTimeout(() => console.log('5. 다음 태스크'), 0);
console.log('2. 동기 코드 끝');            // 출력 순서: 1 → 2 → 3 → 4 → 5
```

흔한 오해는 `await` 나 `queueMicrotask` 로 "양보했다"고 믿는 것이다. 마이크로태스크는 현재 태스크의 연장선이라 그 안의 100ms 계산도 그대로 Long Task 로 집계된다. 진짜 양보는 태스크 경계를 새로 만드는 것뿐이다.

## 2. INP 의 정의와 3구간 분해

INP 는 페이지 수명 동안의 상호작용(클릭/탭, 키 입력) 중 대표값 하나를 고르는 지표로, 상호작용이 적으면 최댓값에 가깝고 50회를 넘으면 상위 이상치를 하나씩 무시한다. 판정은 **200ms 이하 good, 500ms 이하 needs improvement, 그 위 poor** 이며 방문의 75 퍼센타일로 매긴다.

| 구간 | 의미 | 대표 원인 | 처방 |
| --- | --- | --- | --- |
| Input delay | 입력부터 첫 리스너 실행까지 | 진행 중 Long Task, 서드파티 파싱 | 태스크 분할, 지연 로드 |
| Processing time | 모든 리스너 실행 시간 | 무거운 핸들러, 대량 DOM 조작 | 핸들러 축소, 비긴급 양보 |
| Presentation delay | 리스너 종료부터 다음 프레임까지 | 대규모 리렌더, 큰 DOM | `content-visibility`, 가상 스크롤 |

모바일 필드 데이터에서 가장 큰 몫은 processing time 이 아니라 presentation delay 인 경우가 많다. 핸들러는 5ms 인데 상태 변경으로 3,000개 노드가 재계산되며 프레임이 250ms 뒤에 나오는 식이다.

```js
new PerformanceObserver((list) => {
  for (const entry of list.getEntries()) {
    if (!entry.interactionId) continue;
    const inputDelay = entry.processingStart - entry.startTime;
    const processing = entry.processingEnd - entry.processingStart;
    const presentation = entry.startTime + entry.duration - entry.processingEnd;
    if (entry.duration < 100) continue;
    console.table({
      type: entry.name, target: entry.target?.tagName ?? '(detached)',
      total: Math.round(entry.duration), inputDelay: Math.round(inputDelay),
      processing: Math.round(processing), presentation: Math.round(presentation),
    });
  }
}).observe({ type: 'event', durationThreshold: 16, buffered: true });
```

`durationThreshold` 하한은 16ms, 기본값은 104ms 다. 낮출수록 관측 범위는 넓어지나 오버헤드도 늘어 필드에서는 40~104ms 가 무난하다.

## 3. Long Task 와 LoAF 로 원인 특정

Long Tasks API 는 50ms 초과 태스크를 알려주지만 `attribution` 이 컨테이너 수준까지만 짚어 "어떤 함수가 범인인가"를 답하지 못한다. Long Animation Frames(LoAF) API 는 **렌더링 프레임 전체**가 50ms 를 넘긴 경우를 잡고, 렌더 시작 시각과 각 스크립트의 소스 URL·함수명·`invoker` 를 함께 준다. Chromium 계열 중심이라 다른 엔진에서는 수집되지 않음을 전제로 폴백을 둔다.

```js
if (PerformanceObserver.supportedEntryTypes?.includes('long-animation-frame')) {
  new PerformanceObserver((list) => {
    for (const frame of list.getEntries()) {
      const worst = frame.scripts.slice().sort((a, b) => b.duration - a.duration)[0];
      report('loaf', {
        frameDuration: Math.round(frame.duration),
        blockingDuration: Math.round(frame.blockingDuration), // 50ms 초과분 누계
        renderDuration: Math.round(frame.startTime + frame.duration - frame.renderStart),
        invoker: worst?.invoker,          // 'BUTTON#save.onclick'
        source: `${worst?.sourceURL}:${worst?.sourceFunctionName}`,
        scriptDuration: Math.round(worst?.duration ?? 0),
        forced: Math.round(worst?.forcedStyleAndLayoutDuration ?? 0),
      });
    }
  }).observe({ type: 'long-animation-frame', buffered: true });
}
```

`forcedStyleAndLayoutDuration` 은 그 스크립트가 유발한 **강제 동기 레이아웃** 시간으로, 프레임 시간의 절반을 넘으면 원인은 알고리즘이 아니라 DOM 읽기/쓰기 패턴이다. `blockingDuration` 은 프레임 내 50ms 초과분의 합이라 대시보드 지표로 좋다.

## 4. scheduler.postTask 우선순위

`scheduler.postTask` 는 `setTimeout` 을 대체하는 명시적 스케줄링 API 다. `user-blocking` 은 사용자가 결과를 기다리는 작업으로 먼저 실행되고, `user-visible` 은 기본값, `background` 는 로깅·프리페치처럼 인지되지 않는 작업이다. `AbortController` 로 취소하고 `TaskController` 로 대기 중 우선순위를 바꾼다. 역시 Chromium 계열 중심이라 폴백이 필요하다.

```js
const hasPostTask = typeof scheduler !== 'undefined' && 'postTask' in scheduler;

export function schedule(fn, { priority = 'user-visible', signal, delay = 0 } = {}) {
  if (hasPostTask) return scheduler.postTask(fn, { priority, signal, delay });
  return new Promise((resolve, reject) => {           // 폴백: 우선순위 개념 없음
    if (signal?.aborted) return reject(signal.reason);
    const id = setTimeout(() => { try { resolve(fn()); } catch (e) { reject(e); } }, delay);
    signal?.addEventListener('abort', () => { clearTimeout(id); reject(signal.reason); });
  });
}

let controller;
searchInput.addEventListener('input', (e) => {
  controller?.abort();                                 // 이전 렌더 작업 폐기
  controller = new TaskController({ priority: 'user-blocking' });
  schedule(() => renderResults(e.target.value), { signal: controller.signal });
  schedule(flushAnalytics, { priority: 'background' });
});
```

우선순위를 붙인다고 총 작업량이 줄지는 않는다. `background` 로 민 작업도 결국 실행되며 다른 상호작용의 input delay 를 만들므로, 우선순위 지정과 분할은 함께 가야 한다.

## 5. scheduler.yield 와 양보 수단의 함정

핵심은 "양보 후 내 작업이 돌아올 순서를 보장받느냐"다. `setTimeout(fn, 0)` 은 continuation 을 큐 **맨 뒤**에 붙여 다른 타이머가 끼어들면 완료가 늦고, 중첩 5단계를 넘으면 4ms 클램핑이 걸려 1,000개 청크에 4초가 붙는다. `scheduler.yield` 는 continuation 을 같은 우선순위의 새 태스크보다 **앞쪽**에 놓아 이를 푼다.

| 수단 | 태스크 경계 | 순서 보장 | 용도 |
| --- | --- | --- | --- |
| `queueMicrotask` / `await` | 없음 | 해당 없음 | 양보가 아님 |
| `setTimeout(fn, 0)` | 있음 | 큐 맨 뒤, 4ms 클램핑 | 폴백용 |
| `requestAnimationFrame` | 프레임 직전 | 렌더 직전 | 시각 갱신 전용 |
| `requestIdleCallback` | 있음 | 유휴 시간, 실행 보장 없음 | 비필수 사전 계산 |
| `scheduler.yield()` | 있음 | continuation 우선 | 긴 작업 분할 기본 |

```js
const s = typeof scheduler !== 'undefined' ? scheduler : null;
const yieldToMain =
  s && 'yield' in s ? () => s.yield()
  : s && 'postTask' in s ? () => s.postTask(() => {}, { priority: 'user-blocking' })
  : () => new Promise((r) => setTimeout(r, 0));

// 예산 기반 분할: 프레임 예산을 넘길 때만 양보한다
export async function runChunked(items, work, { budgetMs = 8, signal } = {}) {
  let deadline = performance.now() + budgetMs;
  for (let i = 0; i < items.length; i++) {
    signal?.throwIfAborted();
    work(items[i], i);
    if (performance.now() >= deadline) {
      await yieldToMain();
      deadline = performance.now() + budgetMs;
    }
  }
}

await runChunked(rawRows, normalize, { budgetMs: 8 });   // 20,000건 정규화
```

고정 개수(100개마다)보다 **시간 예산 기반**이 낫다. 항목당 비용이 기기마다 10배 이상 차이 나 개수 기준은 저사양 기기에서 그대로 Long Task 가 된다. 반대로 양보가 잦으면 전환 오버헤드로 총 처리 시간이 20~30% 늘 수 있어 8ms 안팎에서 실측 조정한다.

## 6. content-visibility·contain 으로 렌더링 범위 축소

Presentation delay 를 줄이는 CSS 수단은 브라우저가 **작업하지 않아도 되는 영역**을 명시하는 것이다. `content-visibility: auto` 는 뷰포트 밖 요소의 스타일·레이아웃·페인트를 건너뛴다. 건너뛴 요소는 크기를 몰라 스크롤바가 튀므로 `contain-intrinsic-size` 를 함께 주며, `auto 320px` 형태는 한 번 렌더된 뒤 실측값을 기억한다. `contain` 은 더 직접적이라 `layout` 은 자식 레이아웃이 바깥에 영향을 주지 않음을, `paint` 는 경계를 넘지 않음을, `size` 는 크기가 자식과 무관함을 보장한다.

```css
.feed-card {                       /* 긴 피드: 화면 밖 카드 렌더링 생략 */
  content-visibility: auto;
  contain-intrinsic-size: auto 320px;
}

.chat-panel { contain: layout paint; }   /* 내부 변경을 바깥과 격리 */

.skeleton-tile { contain: strict; width: 240px; height: 160px; } /* size+layout+paint+style */

.toast {                           /* 컴포지터에서만 도는 속성으로 애니메이션 */
  transition: transform 180ms ease-out, opacity 180ms ease-out;
  will-change: transform, opacity; /* 남발 금지: 레이어 메모리 증가 */
}
```

다만 `content-visibility: auto` 요소 내부는 페이지 내 찾기와 접근성 트리 노출이 달라질 수 있어 본문에 무분별하게 쓰면 사용성이 나빠진다. `will-change` 도 요소마다 합성 레이어를 만들어 수십 개에 붙이면 GPU 메모리 비용이 오히려 프레임을 늦춘다.

## 7. 강제 동기 레이아웃 진단과 read/write 분리

브라우저는 스타일 변경을 모아뒀다 렌더링 스텝에서 처리하지만, `offsetHeight`·`getBoundingClientRect()`·`getComputedStyle()` 을 읽으면 정확한 값을 주려고 **그 자리에서** 레이아웃을 다시 계산한다. 쓰기와 읽기를 번갈아 하면 회차마다 전체 레이아웃이 반복되는 layout thrashing 이 되고, 노드 500개면 반복문 한 번이 수백 ms 를 먹는다.

```js
function resizeAllBad(items) {                   // 매 회차 강제 레이아웃
  for (const el of items) {
    el.classList.add('expanded');                // write: 레이아웃 무효화
    const h = el.getBoundingClientRect().height; // read: 즉시 재계산 강제
    el.style.setProperty('--h', `${h}px`);       // write
  }
}

function resizeAllGood(items) {                  // 모두 읽고 → 모두 쓴다
  const heights = items.map((el) => el.getBoundingClientRect().height);
  requestAnimationFrame(() => items.forEach((el, i) => {
    el.classList.add('expanded');
    el.style.setProperty('--h', `${heights[i]}px`);
  }));
}
```

DevTools Performance 패널은 이를 "Forced reflow while executing JavaScript" 경고로 표시하고, 필드에서는 LoAF 의 `forcedStyleAndLayoutDuration` 이 같은 값을 잡는다. 개발 중 `getBoundingClientRect` 를 패치해 1ms 초과 호출의 스택을 찍으면 원인 코드를 좁힐 수 있다. 단순 크기 관측이면 렌더링 스텝의 정해진 시점에 콜백을 주는 `ResizeObserver`·`IntersectionObserver` 가 낫다.

## 8. React concurrent 렌더링과 필드 지표 수집

React 18 이후 concurrent 렌더러는 렌더를 중단 가능한 단위로 쪼개고 `startTransition` 업데이트를 낮은 우선순위로 처리한다. 입력값 반영(긴급)과 결과 렌더(비긴급)를 분리하면 input delay 와 processing time 이 줄어든다. 다만 React 스케줄러는 자체 `MessageChannel` 기반으로 약 5ms 단위 양보를 하는 별도 시스템이라 브라우저 `scheduler.postTask` 우선순위와 연결되지 않고, transition 은 렌더를 늦출 뿐 **커밋 시점의 DOM 변경량은 줄이지 않는다**.

```tsx
function SearchPanel({ index }: { index: SearchIndex }) {
  const [query, setQuery] = useState('');
  const [isPending, startTransition] = useTransition();
  const [results, setResults] = useState<Row[]>([]);

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);                      // 긴급: 입력 반영 즉시
    startTransition(() => setResults(index.search(e.target.value))); // 비긴급
  };

  return (
    <>
      <input value={query} onChange={onChange} />
      <ResultList rows={results} stale={isPending} />
    </>
  );
}
```

필드 수집은 `web-vitals` 를 쓰되 INP 는 페이지가 사라질 때 확정되므로 `visibilitychange` 에서 `sendBeacon` 으로 보내야 유실이 없고, 값만 보내면 분석이 안 되니 3구간을 함께 싣는다.

```js
import { onINP } from 'web-vitals/attribution';

const queue = [];
onINP((metric) => {
  const a = metric.attribution;
  queue.push({
    value: Math.round(metric.value),
    rating: metric.rating,               // good | needs-improvement | poor
    target: a.interactionTarget,         // CSS 셀렉터
    type: a.interactionType,             // 'pointer' | 'keyboard'
    inputDelay: Math.round(a.inputDelay),
    processing: Math.round(a.processingDuration),
    presentation: Math.round(a.presentationDelay),
  });
});

addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden' && queue.length) {
    navigator.sendBeacon('/rum', JSON.stringify(queue.splice(0)));
  }
});
```

끝으로 실험실과 필드의 괴리를 짚는다. Lighthouse 는 페이지를 로드만 하고 클릭하지 않아 **INP 를 측정하지 못하며**, 대신 TBT(Long Task 의 50ms 초과분 합)를 대리 지표로 쓴다. TBT 가 200ms 이하여도 특정 버튼 핸들러가 400ms 를 먹으면 필드 INP 는 poor 가 된다. CrUX 는 실제 사용자의 28일 이동 윈도우 75 퍼센타일이라 저사양 기기와 느린 네트워크가 섞여 개발자 기기 측정보다 2~4배 나쁘게 나오는 것이 정상이다. 그래서 순서는 항상 **필드로 상호작용 특정 → CPU 4x 스로틀로 재현 → LoAF 로 원인 확정 → 분할·양보·격리 → 필드 재검증** 이다.

## 참고

- [Interaction to Next Paint (INP) — web.dev](https://web.dev/articles/inp)
- [Optimize INP — web.dev](https://web.dev/articles/optimize-inp)
- [Optimize long tasks — web.dev](https://web.dev/articles/optimize-long-tasks)
- [Long Animation Frames API — web.dev](https://web.dev/articles/loaf)
- [Introducing scheduler.yield() — Chrome](https://developer.chrome.com/blog/introducing-scheduler-yield)
- [Scheduler.postTask() — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Scheduler/postTask)
- [content-visibility — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/content-visibility)
- [CSS contain — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/contain)
- [Layout thrashing — web.dev](https://web.dev/articles/avoid-large-complex-layouts-and-layout-thrashing)
- [Scheduling APIs — WICG](https://wicg.github.io/scheduling-apis/)
- [Long Tasks API — W3C](https://w3c.github.io/longtasks/)
- [startTransition — React](https://react.dev/reference/react/startTransition)
