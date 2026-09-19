Notion 원본: https://www.notion.so/3e05a06fd6d381319e02d762d435ba3a

# 브라우저 렌더링 파이프라인과 INP 최적화 및 content-visibility 컴포지팅

> 2026-09-19 신규 주제 · 확장 대상: WEB, React, Javascript

## 학습 목표

- Style → Layout → Paint → Composite 네 단계에서 각 CSS 속성이 어느 지점부터 다시 도는지 구분한다
- INP의 세 구간(입력 지연 / 처리 시간 / 프레젠테이션 지연)을 나눠 측정하고 각각의 처방을 적용한다
- `content-visibility`, `contain`, `will-change`로 렌더링 작업을 건너뛰는 조건과 비용을 판단한다
- Performance 패널과 `PerformanceObserver`로 롱 태스크를 실측해 분할 지점을 정한다

## 1. 렌더링 파이프라인 — 어디서부터 다시 도는가

브라우저가 프레임을 만드는 과정은 네 단계다.

1. **Style** — CSS 규칙을 매칭해 각 요소의 계산된 스타일을 정한다
2. **Layout(Reflow)** — 박스의 기하(위치·크기)를 계산한다
3. **Paint** — 각 레이어에 그릴 명령 목록을 만든다
4. **Composite** — 레이어를 GPU에서 합성해 화면에 올린다

변경한 속성에 따라 어느 단계부터 다시 도는지가 갈리고, 이것이 애니메이션 성능의 거의 전부를 결정한다.

| 변경 속성 | Style | Layout | Paint | Composite |
|---|---|---|---|---|
| `width`, `height`, `top`, `margin`, `font-size` | O | O | O | O |
| `color`, `background-color`, `box-shadow`, `border-radius` | O | — | O | O |
| `transform`, `opacity`, `filter` | O | — | — | O |

세 번째 행이 목표다. `transform`과 `opacity`는 컴포지터 스레드에서 처리되므로 메인 스레드가 바빠도 부드럽게 돈다. 같은 이동 효과를 두 방식으로 쓰면 비용이 완전히 다르다.

```css
/* 나쁨: 매 프레임 Layout → Paint → Composite */
.slide-bad {
  transition: left 300ms ease-out;
  position: absolute;
  left: 0;
}
.slide-bad.open { left: 320px; }

/* 좋음: Composite 만 */
.slide-good {
  transition: transform 300ms ease-out;
  transform: translateX(0);
}
.slide-good.open { transform: translateX(320px); }
```

Layout은 특히 비싸다. 한 요소의 기하가 바뀌면 형제·자손의 재계산이 연쇄한다. 그리고 JavaScript가 이 연쇄를 강제로 앞당기는 패턴이 있다.

## 2. 강제 동기 레이아웃(layout thrashing)

브라우저는 스타일 변경을 모아 뒀다가 프레임 직전에 한 번에 계산한다. 그런데 기하 정보를 **읽는** API를 호출하면 그 자리에서 Layout을 강제로 돌린다. 쓰기와 읽기가 번갈아 나오면 매 반복마다 Layout이 발생한다.

```js
// 나쁨: 루프마다 강제 동기 레이아웃 (N번의 Layout)
for (const el of items) {
  el.style.height = el.offsetHeight + 10 + 'px';   // 쓰기 직후 읽기
}

// 좋음: 읽기를 모으고 쓰기를 모은다 (Layout 1번)
const heights = items.map((el) => el.offsetHeight);   // 읽기 단계
items.forEach((el, i) => {
  el.style.height = heights[i] + 10 + 'px';           // 쓰기 단계
});
```

Layout을 강제하는 대표 API: `offsetTop/Left/Width/Height`, `clientTop/Left/Width/Height`, `scrollTop/Left/Width/Height`, `getComputedStyle()`, `getBoundingClientRect()`, `getClientRects()`, `focus()`, `scrollIntoView()`, `Range.getBoundingClientRect()`.

React 환경에서는 `useLayoutEffect` 안에서 측정한 뒤 곧바로 DOM을 수정하는 코드가 전형적인 발생 지점이다. 측정이 여러 컴포넌트에 흩어져 있으면 각각이 Layout을 유발한다. 측정이 꼭 필요하면 `ResizeObserver`나 `IntersectionObserver`로 대체하는 편이 낫다 — 이들은 브라우저가 레이아웃을 마친 후 콜백을 주므로 강제 동기 레이아웃이 없다.

Chrome DevTools의 Performance 패널에서는 보라색 Layout 블록 옆에 빨간 삼각형과 "Forced reflow" 경고가 뜬다. 이것을 검색하는 것이 튜닝의 출발점이다.

## 3. INP — 세 구간으로 나눠 보기

INP(Interaction to Next Paint)는 2024년 3월 FID를 대체한 Core Web Vitals 지표다. 페이지 생애 동안 발생한 모든 상호작용(클릭·탭·키 입력) 중 거의 최악에 해당하는 값을 보고한다. 기준은 **75퍼센타일에서 200ms 이하가 Good, 500ms 초과가 Poor**다.

INP는 세 구간의 합이다.

```
INP = 입력 지연(Input delay) + 처리 시간(Processing time) + 프레젠테이션 지연(Presentation delay)
      └ 메인 스레드가       └ 이벤트 핸들러        └ 스타일·레이아웃·페인트
        바빠서 대기            실행 시간              후 다음 프레임까지
```

구간별로 처방이 다르므로 합계만 봐서는 고칠 수 없다. 분해해 측정한다.

```js
new PerformanceObserver((list) => {
  for (const entry of list.getEntries()) {
    if (entry.interactionId) {
      const inputDelay = entry.processingStart - entry.startTime;
      const processing = entry.processingEnd - entry.processingStart;
      const presentation = entry.startTime + entry.duration - entry.processingEnd;
      console.log({
        type: entry.name,
        total: entry.duration,
        inputDelay,
        processing,
        presentation,
      });
    }
  }
}).observe({ type: 'event', durationThreshold: 16, buffered: true });
```

`durationThreshold`의 최솟값은 16ms이고 기본값은 104ms다. 개발 중에는 16으로 낮춰 놓아야 작은 것까지 보인다.

**입력 지연이 크다면** 메인 스레드가 다른 일로 막혀 있다는 뜻이다. 서드파티 스크립트, 하이드레이션, 큰 JSON 파싱이 범인이다. 롱 태스크를 쪼개는 것이 처방이다.

**처리 시간이 크다면** 핸들러 자체가 무겁다. 상태 업데이트에 딸린 연쇄 렌더링을 의심한다.

**프레젠테이션 지연이 크다면** DOM 변경 후 렌더링 작업이 크다. 한 번에 수천 개의 노드를 바꾸거나, 레이아웃 연쇄가 큰 구조다. 여기가 `content-visibility`가 효과를 내는 구간이다.

## 4. 롱 태스크 분할 — yield 하는 방법

50ms를 넘는 작업이 롱 태스크다. 그 동안 입력이 큐에 쌓인다. 해법은 작업을 쪼개고 중간에 메인 스레드를 양보하는 것이다.

가장 정확한 도구는 `scheduler.yield()`다. Chrome 129부터 기본 활성화된 표준 API로, **양보 후 현재 작업이 큐 앞쪽으로 돌아온다**는 점이 `setTimeout(0)`과 결정적으로 다르다. `setTimeout`으로 양보하면 그사이 들어온 다른 태스크 뒤로 밀려 전체 시간이 늘어난다.

```js
async function processInChunks(items, handler) {
  let lastYield = performance.now();
  for (const item of items) {
    handler(item);
    if (performance.now() - lastYield > 40) {   // 프레임 예산의 절반 정도
      await yieldToMain();
      lastYield = performance.now();
    }
  }
}

function yieldToMain() {
  if (typeof scheduler !== 'undefined' && 'yield' in scheduler) {
    return scheduler.yield();
  }
  return new Promise((resolve) => setTimeout(resolve, 0));
}
```

우선순위를 지정해 스케줄링할 수도 있다.

```js
// 사용자가 기다리는 작업
scheduler.postTask(() => renderResults(data), { priority: 'user-blocking' });
// 나중에 해도 되는 작업
scheduler.postTask(() => sendAnalytics(payload), { priority: 'background' });
```

React에서는 `useTransition`이 같은 역할을 한다. 긴급하지 않은 업데이트를 표시해 두면 입력 응답이 먼저 처리된다.

```jsx
const [isPending, startTransition] = useTransition();

function onChange(event) {
  setQuery(event.target.value);                 // 긴급: 입력창은 즉시 반영
  startTransition(() => {
    setResults(filterLargeList(event.target.value));   // 비긴급: 중단 가능
  });
}
```

그리고 핸들러 안에서 **시각적 피드백을 먼저 주고 나머지를 양보한 뒤 처리**하는 패턴이 INP에 직접 효과가 있다.

```js
button.addEventListener('click', async () => {
  showSpinner();              // 다음 프레임에 보일 것만 먼저
  await yieldToMain();        // 여기서 브라우저가 페인트한다
  await doExpensiveWork();    // 무거운 작업은 그 뒤에
});
```

## 5. content-visibility — 화면 밖 렌더링을 통째로 건너뛰기

`content-visibility: auto`는 요소가 뷰포트 밖에 있으면 자손의 스타일·레이아웃·페인트를 모두 생략한다. 긴 목록이나 아코디언에서 효과가 크다.

```css
.article-card {
  content-visibility: auto;
  contain-intrinsic-size: auto 480px;   /* 생략된 동안 가정할 높이 */
}
```

`contain-intrinsic-size`가 짝이다. 이걸 주지 않으면 생략된 요소의 높이가 0으로 취급되어 스크롤바가 널뛰고, 스크롤 위치가 어긋난다. `auto` 키워드를 앞에 붙이면 한 번이라도 렌더링된 요소는 **실제 크기를 기억**했다가 다시 화면 밖으로 나갈 때 그 값을 쓴다. 고정값만 쓰는 것보다 훨씬 안정적이다.

주의할 점이 몇 가지 있다.

- **접근성.** 화면 밖 콘텐츠는 접근성 트리에 유지되지만(`content-visibility: hidden`과 다름), 브라우저 내 찾기(Ctrl+F)와 앵커 링크는 자동으로 해당 요소를 렌더링해 스크롤한다. 이 동작은 `hidden`에서는 일어나지 않는다.
- **`position: fixed` 자손.** `contain`이 포함관계를 만들기 때문에 컨테이닝 블록이 바뀐다. 고정 위치 요소를 안에 두면 예상과 다르게 배치된다.
- **효과가 없는 경우.** 요소가 이미 화면 안에 있거나, 실제 렌더링 비용이 작으면 이득이 없고 관리 비용만 생긴다.

`contain` 속성은 더 세밀한 제어를 준다.

```css
.widget {
  contain: layout style paint;   /* = contain: content */
}
```

- `layout` — 내부 레이아웃이 외부에 영향을 주지 않음을 보장. 레이아웃 연쇄를 자른다
- `paint` — 자손이 경계를 넘어 그려지지 않음을 보장. 화면 밖이면 페인트 생략
- `style` — 카운터·quote 같은 스타일 효과가 밖으로 새지 않음
- `size` — 자손 크기가 요소 크기에 영향을 주지 않음. 크기를 명시해야 함

`contain: layout paint`는 독립적인 위젯(카드, 사이드바 패널)에 거의 항상 안전하게 적용할 수 있고, 레이아웃 재계산 범위를 좁혀 준다.

## 6. will-change와 레이어 — 남발하면 역효과

`will-change`는 브라우저에게 "이 속성이 곧 바뀐다"고 미리 알려 최적화(대개 별도 컴포지터 레이어 승격)를 준비시킨다.

```css
.drawer {
  will-change: transform;
}
```

문제는 레이어마다 GPU 메모리를 쓴다는 것이다. 요소 하나가 1920×1080 크기라면 4바이트/픽셀 기준 약 8MB다. 수십 개에 걸면 모바일에서 메모리 압박과 오히려 느려지는 결과를 낳는다.

규칙 세 가지.

1. **필요한 순간에만 켜고 끈다.** CSS에 영구히 선언하지 말고 JS로 토글하거나, `:hover` 같은 선행 상태에 건다.
2. **개수를 센다.** DevTools의 Layers 패널에서 레이어 수와 메모리를 확인한다.
3. **이미 컴포지팅되는 것에는 불필요.** `transform` 애니메이션이 이미 도는 요소는 자동으로 승격된다.

```css
/* 마우스가 올라왔을 때 미리 준비 */
.drawer-trigger:hover ~ .drawer { will-change: transform; }
.drawer { transition: transform 200ms; }
```

## 7. 실측 절차 — DevTools와 필드 데이터

**랩 측정(Performance 패널).** CPU 스로틀링을 4x 또는 6x로 걸고 기록한다. 개발 머신의 속도로는 문제가 재현되지 않는다.

확인할 것:
- Main 트랙의 빨간 삼선(롱 태스크)과 그 길이
- "Forced reflow" 경고
- Interactions 트랙 — INP 후보 상호작용이 표시되고 클릭하면 세 구간으로 나뉘어 보인다
- Layout Shifts 트랙 — CLS 원인

**필드 측정.** 랩과 필드는 자주 다르다. 실제 사용자의 기기·네트워크·확장 프로그램이 다르기 때문이다. `web-vitals` 라이브러리로 수집한다.

```js
import { onINP, onLCP, onCLS } from 'web-vitals/attribution';

onINP((metric) => {
  const attr = metric.attribution;
  navigator.sendBeacon('/rum', JSON.stringify({
    name: metric.name,
    value: metric.value,
    rating: metric.rating,
    target: attr.interactionTarget,       // 어떤 요소인지
    type: attr.interactionType,
    inputDelay: attr.inputDelay,
    processingDuration: attr.processingDuration,
    presentationDelay: attr.presentationDelay,
    loadState: attr.loadState,
  }));
}, { reportAllChanges: false });
```

`web-vitals/attribution` 빌드가 핵심이다. 값만 받으면 "느리다"는 것만 알고, attribution이 있어야 **어느 요소의 어떤 상호작용이** 느린지 안다.

**Long Animation Frames API**는 더 정밀한 원인 추적을 준다. 어떤 스크립트가 프레임을 막았는지 URL과 함수명까지 알려준다.

```js
new PerformanceObserver((list) => {
  for (const entry of list.getEntries()) {
    if (entry.duration > 100) {
      console.log('LoAF', entry.duration, entry.scripts.map((s) => ({
        source: s.sourceURL,
        fn: s.sourceFunctionName,
        duration: s.duration,
        invoker: s.invoker,
      })));
    }
  }
}).observe({ type: 'long-animation-frame', buffered: true });
```

서드파티 스크립트가 범인일 때 이 API가 증거를 준다. 기존 Long Tasks API가 "느린 태스크가 있었다"까지만 말해 주는 데 비해 LoAF는 프레임 단위로 스크립트 기여도를 나눠 준다.

## 8. 우선순위 — 무엇부터 손댈 것인가

측정 없이 최적화하지 않는다는 전제 아래, 대체로 효과 대비 비용 순서는 다음과 같다.

**1순위: 롱 태스크 제거.** INP의 입력 지연 구간을 직접 줄인다. 특히 페이지 로드 직후 하이드레이션 구간이 크다면, 상호작용이 필요한 컴포넌트만 선택적으로 하이드레이션하거나(islands) 서버 컴포넌트로 옮겨 클라이언트 번들 자체를 줄인다.

**2순위: 애니메이션 속성을 transform/opacity로 교체.** 코드 변경량 대비 효과가 확실하다. `left/top/width/height` 트랜지션을 전부 찾아 바꾼다.

**3순위: 강제 동기 레이아웃 제거.** 읽기/쓰기 분리. 측정 코드가 흩어져 있다면 Observer API로 재구성.

**4순위: content-visibility / contain 적용.** 긴 목록이나 무거운 위젯이 있을 때만. 없는데 적용하면 순이익이 0이거나 음수다.

**5순위: will-change / 레이어 튜닝.** 가장 마지막. 오용 시 역효과가 큰 영역이다.

마지막으로 트레이드오프를 분명히 해 둔다. INP를 줄이려고 작업을 잘게 쪼개면 **전체 처리 시간은 오히려 늘어난다.** 양보할 때마다 컨텍스트 전환 비용이 들기 때문이다. 배치 처리처럼 사용자가 보고 있지 않은 작업까지 쪼갤 이유는 없다. 반대로 사용자가 클릭하고 기다리는 경로라면 총 시간이 20% 늘더라도 반응이 즉각적인 쪽이 체감상 훨씬 빠르다. 지표를 맹목적으로 따르는 대신, 그 지표가 대리하는 사용자 경험이 무엇인지를 기준으로 판단해야 한다.

## 참고

- web.dev — "Interaction to Next Paint (INP)" / "Optimize INP"
- web.dev — "Optimize long tasks" (`scheduler.yield`, `scheduler.postTask`)
- web.dev — "content-visibility: the new CSS property that boosts your rendering performance"
- MDN Web Docs — `content-visibility`, `contain`, `contain-intrinsic-size`, `will-change`
- W3C CSS Containment Module Level 2
- Chrome for Developers — "Long Animation Frames API"
- GoogleChrome/web-vitals — attribution build 문서
