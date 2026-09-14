Notion 원본: https://app.notion.com/p/3db5a06fd6d381d4816bc43e3691ab24?pvs=204

# View Transitions API와 크로스 도큐먼트 전환 및 스냅샷 의사 요소 트리

> 2026-09-14 신규 주제 · 확장 대상: 브라우저 렌더링·CSS 애니메이션

## 학습 목표

- DOM 변경 전후를 브라우저가 직접 캡처하는 전환 수명주기를 단계별로 추적한다
- 스냅샷 의사 요소 트리의 각 층이 무엇을 담당하는지 UA 스타일시트 수준에서 해부한다
- `view-transition-name` 의 유일성 제약과 격리 요건을 실패 사례와 함께 방어한다
- MPA 전환·React 연동·폴백까지 포함한 도입 판단 기준을 정리한다

## 1. FLIP 을 손으로 짜면 무엇이 남는가

리스트에서 카드를 눌렀을 때 그 카드가 상세 화면의 헤더 이미지로 자연스럽게 이어지는 연출을 생각해 보자. DOM 이 교체되는 순간 요소는 사라지고 다른 요소가 나타나므로, CSS transition 은 걸 곳이 없다. 전통적 해법이 FLIP(First, Last, Invert, Play)이다.

```js
function flip(el, mutate) {
	const first = el.getBoundingClientRect();
	mutate();
	const last = el.getBoundingClientRect();

	const dx = first.left - last.left;
	const dy = first.top - last.top;
	const sx = first.width / last.width;
	const sy = first.height / last.height;

	return el.animate(
		[
			{ transformOrigin: 'top left', transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
			{ transformOrigin: 'top left', transform: 'none' },
		],
		{ duration: 250, easing: 'ease' },
	).finished;
}
```

동작은 한다. 그런데 이 20줄이 커버하지 못하는 것이 많다. 스케일로 늘린 요소는 내부 텍스트와 border-radius 까지 같이 찌그러지므로 자식에 역보정 애니메이션을 또 걸어야 한다. 사라지는 요소는 DOM 에서 이미 제거됐으니 페이드아웃시키려면 삭제를 미루거나 복제본을 만들어야 한다. 스크롤 위치가 함께 바뀌면 `getBoundingClientRect()` 기준이 흔들린다. 그리고 `mutate()` 가 React 처럼 비동기 커밋이라면 "Last" 측정 시점을 잡는 것 자체가 프레임워크 내부 사정이 된다.

View Transitions API 는 이 문제를 애플리케이션 레이어가 아니라 렌더링 파이프라인에서 푼다. 브라우저가 변경 직전 화면을 이미지로 떠 놓고, 변경 후 화면도 이미지로 뜬 뒤, 그 둘을 의사 요소로 노출해 CSS 로 애니메이션시킨다. 구현은 Chromium 기반 브라우저에서 먼저 이뤄졌고 이후 다른 엔진으로 확산되고 있다.

## 2. startViewTransition 의 수명주기

`document.startViewTransition(callback)` 을 호출하면 명세가 규정한 순서대로 단계가 진행된다. 명세의 lifecycle 절은 이를 12단계로 쪼개 놓았는데, 실무에서 기억할 골자는 이렇다.

1. 현재 화면이 **구(old) 상태**로 캡처된다.
2. **렌더링이 일시 정지**된다. 이 구간 동안 화면은 얼어 있다.
3. 개발자가 넘긴 `callback` 이 호출되어 DOM 을 변경한다.
4. `updateCallbackDone` 이 fulfill 된다.
5. 변경된 화면이 **신(new) 상태**로 캡처된다.
6. 전환 의사 요소 트리가 생성된다.
7. 렌더링이 재개되며 의사 요소가 드러나고, `ready` 가 fulfill 된다.
8. 의사 요소가 애니메이션되고, 끝나면 트리가 제거되며 `finished` 가 fulfill 된다.

핵심은 3번이 2번과 5번 사이에 끼어 있다는 점이다. 콜백 안에서 DOM 을 아무리 크게 갈아엎어도 중간 상태가 화면에 새어 나오지 않는다. FLIP 에서 "Last 측정 시점"을 직접 잡아야 했던 문제가 여기서 사라진다.

```js
function navigateTo(url) {
	// 미지원 브라우저 폴백: 전환 없이 상태만 바꾼다
	if (!document.startViewTransition) {
		update(url);
		return;
	}
	document.startViewTransition(() => update(url));
}
```

콜백이 프라미스를 반환하면 브라우저는 그 프라미스가 settle 될 때까지 렌더링 정지를 유지한다. 이 성질은 양날이다. 데이터 페칭을 콜백 안에 넣으면 화면이 응답 시간만큼 얼어붙는다. 명세는 무한 정지를 막기 위한 타임아웃을 두고 있지만, 그 시간 동안 사용자는 클릭에도 반응 없는 페이지를 보게 된다. 원칙은 **데이터는 밖에서 미리 받고, 콜백 안에서는 DOM 커밋만 한다**는 것이다.

```js
async function navigateTo(url) {
	const data = await fetchPage(url); // 전환 시작 전에 완료
	if (!document.startViewTransition) {
		render(data);
		return;
	}
	const transition = document.startViewTransition(() => render(data));
	await transition.finished;
}
```

## 3. 세 개의 프라미스와 skipTransition

`startViewTransition()` 이 돌려주는 `ViewTransition` 객체는 프라미스 셋과 메서드 하나를 가진다. 셋을 구분해 쓰는 것이 이 API 를 제대로 쓰는지 가르는 지점이다.

| 멤버 | resolve 시점 | reject 조건 | 주 용도 |
| --- | --- | --- | --- |
| `updateCallbackDone` | 콜백이 반환한 프라미스가 fulfill 된 직후 | 콜백이 throw 하거나 reject | DOM 변경 자체의 성공 여부 확인 |
| `ready` | 의사 요소 트리가 만들어지고 애니메이션 직전 | 중복 `view-transition-name`, 콜백 실패 등 전환 시작 불가 | 의사 요소에 Web Animations 부착 |
| `finished` | 애니메이션 종료 후 의사 요소 제거까지 완료 | `updateCallbackDone` 이 reject 된 경우만 | 정리 작업, 후속 동작 |

`ready` 는 자주 reject 되고 `finished` 는 거의 reject 되지 않는다. 명세는 "전환이 시작되지 못했거나 스킵되어도 최종 상태에는 도달하므로 `finished` 는 fulfill 된다"고 못박는다. 반대로 콜백이 실패했다면 최종 상태 자체가 만들어지지 않았으므로 `finished` 도 reject 된다. 따라서 **전환 실패와 상태 변경 실패는 다른 사건**이고, 둘을 같은 catch 에서 처리하면 중복 이름 경고 때문에 정상 동작을 에러로 잡는 사고가 난다.

```js
const transition = document.startViewTransition(() => render(data));

// 전환이 못 떠도 앱은 정상이다. 조용히 삼킨다.
transition.ready.catch((err) => console.debug('view transition skipped:', err));

// 상태 변경 실패는 진짜 에러다.
transition.updateCallbackDone.catch(reportError);

transition.finished.then(() => releaseSnapshotHeavyResources());
```

`ready` 가 fulfill 된 시점에는 의사 요소가 실재하므로 Web Animations API 로 직접 애니메이션을 붙일 수 있다. 클릭 좌표 기준 원형 확장처럼 CSS 만으로는 표현할 수 없는 동적 값을 쓸 때 필요하다.

```js
transition.ready.then(() => {
	const { clientX: x, clientY: y } = lastClick;
	const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));

	document.documentElement.animate(
		{ clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
		{ duration: 400, easing: 'ease-in', pseudoElement: '::view-transition-new(root)' },
	);
});
```

`skipTransition()` 은 애니메이션을 즉시 끝내되 DOM 변경은 그대로 진행시킨다. 명세상 이 메서드는 **콜백 호출을 절대 막지 않는다**. `ready` 가 아직 resolve 전이면 `ready` 는 reject 되고, `finished` 는 `updateCallbackDone` 을 따라간다. 사용자가 전환 도중 다음 링크를 눌렀을 때 앞선 전환을 즉시 종료하는 용도로 쓴다.

## 4. 스냅샷 의사 요소 트리

전환이 활성화되면 루트 요소를 originating element 로 하는 의사 요소 트리가 생긴다. 구조는 4층이다.

```
::view-transition                       (전환 레이어 루트, snapshot containing block 을 덮음)
└─ ::view-transition-group(name)        (위치·크기 보간 담당)
   └─ ::view-transition-image-pair(name) (isolation: isolate 로 블렌딩 격리)
      ├─ ::view-transition-old(name)     (구 상태 스냅샷 이미지)
      └─ ::view-transition-new(name)     (신 상태 라이브 렌더)
```

`::view-transition` 은 일반 문서 콘텐츠와 별개의 **view transition layer** 라는 스태킹 레이어를 만들고, top layer(`<dialog>`, popover)를 포함한 모든 콘텐츠보다 위에 그려진다. 컨테이닝 블록은 뷰포트가 아니라 **snapshot containing block** 인데, 이는 모바일 URL 바나 소프트 키보드처럼 나타났다 사라지는 영역까지 포함해 구·신 상태의 좌표계를 일치시키기 위한 사각형이다. 스크롤바 유무로 좌표가 흔들리지 않는 이유가 여기 있다.

기본 크로스페이드는 UA 스타일시트가 만든다. 명세가 싣고 있는 정의를 발췌하면 이렇다.

```css
:root::view-transition-group(*) {
	position: absolute;
	top: 0;
	left: 0;
	animation-duration: 0.25s;
	animation-fill-mode: both;
}

:root::view-transition-image-pair(*) {
	isolation: isolate;
}

/* 전환 시작 시 동적으로 주입되는 규칙 */
:root::view-transition-old(name) {
	animation-name: -ua-view-transition-fade-out, -ua-mix-blend-mode-plus-lighter;
}
:root::view-transition-new(name) {
	animation-name: -ua-view-transition-fade-in, -ua-mix-blend-mode-plus-lighter;
}
```

기본 지속 시간이 **0.25s** 라는 것, 그리고 `mix-blend-mode: plus-lighter` 가 붙는다는 것이 실무에 직접 영향을 준다. 단순히 `opacity` 를 0→1, 1→0 으로 교차시키면 중간 지점에서 두 레이어의 합성 불투명도가 0.75 가 되어 배경이 비치는 "패임"이 생긴다. `plus-lighter` 는 동일한 픽셀끼리 더했을 때 원래 색을 그대로 유지시키므로 이 패임이 사라진다. 명세도 이를 "올바른 크로스페이드"라 표현한다. 커스텀 애니메이션을 짜면서 `animation-name` 을 덮어쓰면 이 블렌딩 규칙도 함께 날아가므로, 페이드를 유지하고 싶다면 직접 `mix-blend-mode: plus-lighter` 를 다시 선언해야 한다.

`::view-transition-group` 과 `::view-transition-old/new` 의 역할 분리도 중요하다. 그룹은 위치·크기를 보간하고, 이미지 쌍은 그 안에서 내용물을 블렌딩한다. 종횡비가 크게 달라지는 전환에서 내용이 찌그러지는 것은 `::view-transition-old/new` 의 `object-fit` 기본값 때문이며, 여기에 `object-fit: cover` 를 주면 FLIP 에서 자식 역보정으로 해결하던 문제를 한 줄로 대체할 수 있다.

## 5. view-transition-name 과 이름 유일성

기본값에서는 문서 전체가 `root` 라는 하나의 이름으로 캡처되어 페이지 단위 크로스페이드만 일어난다. 특정 요소를 독립된 애니메이션 그룹으로 승격시키려면 `view-transition-name` 을 준다.

```css
.hero-image {
	view-transition-name: hero;
}
.site-header {
	view-transition-name: header;
}
```

이름이 붙은 요소는 부모 그룹에서 떨어져 나와 자기만의 `::view-transition-group(hero)` 를 갖는다. 명세는 `view-transition-name` 이 `none` 이 아닌 요소에 대해 세 가지를 강제한다. **스태킹 컨텍스트를 형성**하고, **3D 변환에서 평탄화**되며, **backdrop root 를 형성**한다. 부모의 `filter` 나 `backdrop-filter` 가 이 요소를 통과하지 못하게 되므로, 이름을 붙였을 뿐인데 그림자나 블러가 달라 보이는 경우가 생긴다. 이름을 붙이는 행위가 시각적으로 무해하지 않다는 점을 기억해야 한다.

가장 자주 겪는 실패는 **이름 중복**이다. 같은 시점에 동일한 이름을 가진 요소가 둘 이상이면 브라우저는 어느 쪽을 짝지을지 결정할 수 없고, 전환 전체를 스킵한 뒤 `ready` 를 reject 한다. `updateCallback` 은 그대로 호출되므로 화면은 애니메이션 없이 즉시 바뀐다.

```js
// 안티패턴: 목록의 모든 카드에 같은 이름
items.forEach((item) => { item.style.viewTransitionName = 'card'; });

// 올바른 방식: 이번 전환에 관여하는 요소에만 고유 이름을 부여하고 끝나면 회수
function animateToDetail(cardEl, id) {
	cardEl.style.viewTransitionName = `card-${id}`;
	const t = document.startViewTransition(() => renderDetail(id));
	t.finished.finally(() => { cardEl.style.viewTransitionName = ''; });
}
```

이름을 회수하는 `finally` 가 없으면 다음 전환에서 상세 화면의 `card-7` 과 뒤로 가기로 복원된 목록의 `card-7` 이 충돌한다. CSS Nesting 이나 `:nth-child` 로 정적으로 고유 이름을 뿌리는 방법도 있지만, 가상 스크롤처럼 항목이 재활용되는 구조에서는 위처럼 명령형으로 붙였다 떼는 편이 안전하다. 레벨 2 명세는 요소마다 자동으로 고유 이름을 만들어 주는 키워드를 논의 중이나, 아직 전 브라우저 공통 기반으로 기대하기는 이르다.

또 하나의 함정은 **렌더링되지 않는 요소는 캡처되지 않는다**는 점이다. `display: none` 은 물론이고, `content-visibility` 로 렌더링이 생략된 서브트리에 이름을 붙이면 빈 스냅샷이 나온다. 여러 줄로 조각난 인라인 요소처럼 단일 사각형으로 떨어지지 않는 대상도 그룹으로 승격시키기 어렵다.

## 6. 커스텀 애니메이션과 view-transition-class

의사 요소는 보통의 요소처럼 스타일링되므로 `animation` 을 그냥 얹으면 된다. 좌우 슬라이드를 넣어 보자.

```css
@keyframes slide-from-right {
	from { transform: translateX(30px); opacity: 0; }
}
@keyframes slide-to-left {
	to { transform: translateX(-30px); opacity: 0; }
}

::view-transition-old(root) {
	animation: 180ms cubic-bezier(0.4, 0, 1, 1) both slide-to-left;
}
::view-transition-new(root) {
	animation: 220ms cubic-bezier(0, 0, 0.2, 1) 60ms both slide-from-right;
}
```

여기서 `animation` 단축 속성이 UA 가 넣어 둔 `-ua-mix-blend-mode-plus-lighter` 를 밀어내므로, 겹치는 구간에서 배경이 비친다면 `mix-blend-mode: plus-lighter` 를 명시적으로 붙여야 한다.

이름이 수십 개로 늘어나면 선택자 관리가 문제가 된다. `view-transition-class` 는 그룹에 클래스를 부여해 공통 스타일을 한 번에 걸 수 있게 한다.

```css
.product-card {
	view-transition-class: card;
}

::view-transition-group(.card) {
	animation-duration: 300ms;
	animation-timing-function: cubic-bezier(0.2, 0, 0, 1);
}

/* 이름 전체를 대상으로 하려면 와일드카드 */
::view-transition-group(*) {
	animation-duration: 300ms;
}
```

같은 화면 전환이라도 "다음 장"과 "이전 장"의 방향이 달라야 한다면 전환 **타입**을 쓴다. `startViewTransition({ update, types })` 로 타입을 붙이고 `:active-view-transition-type()` 으로 분기한다.

```js
document.startViewTransition({
	update: () => render(next),
	types: [next.index > current.index ? 'forward' : 'back'],
});
```

```css
html:active-view-transition-type(back) ::view-transition-old(root) {
	animation-name: slide-to-right;
}
html:active-view-transition-type(forward) ::view-transition-old(root) {
	animation-name: slide-to-left;
}
```

타입 이전에는 `documentElement.classList.add('is-back')` 같은 임시 클래스를 붙였다가 `finished` 에서 떼는 패턴을 썼는데, 전환이 겹치면 클래스가 남는 문제가 있었다. 타입은 전환 수명주기에 묶여 있어 그런 누수가 없다.

## 7. 크로스 도큐먼트(MPA) 전환

SPA 가 아니어도 전환을 쓸 수 있다. 출발 문서와 도착 문서 **양쪽 CSS 에** 다음 at-rule 이 있으면 브라우저가 내비게이션 사이에 전환을 끼워 넣는다.

```css
@view-transition {
	navigation: auto;
}
```

제약은 **same-origin** 이다. MDN 은 "크로스 도큐먼트 뷰 트랜지션이 동작하려면 내비게이션의 현재 문서와 도착 문서가 같은 오리진이어야 한다"고 명시한다. 오리진이 다르면 at-rule 이 있어도 전환은 일어나지 않는다. 이 한 줄만으로 서버 렌더링된 다중 페이지 사이트가 SPA 같은 연속감을 얻는다는 점이 크다. 라우터도, 클라이언트 상태 관리도 필요 없다.

세밀한 제어는 두 이벤트로 한다. 떠나는 문서에서는 `pageswap`, 도착하는 문서에서는 첫 렌더 직전에 `pagereveal` 이 발생하며, 둘 다 이벤트 객체에 `viewTransition` 을 싣는다.

```js
// 떠나는 문서: 목적지에 따라 어떤 요소를 캡처할지 결정
window.addEventListener('pageswap', (e) => {
	if (!e.viewTransition) return;
	const to = e.activation?.entry?.url;           // 목적지 URL
	if (to && new URL(to).pathname.startsWith('/detail/')) {
		document.querySelector('.thumb.selected')?.style.setProperty('view-transition-name', 'hero');
	}
	e.viewTransition.finished.then(cleanupNames);
});

// 도착 문서: 어디서 왔는지에 따라 타입을 조정
window.addEventListener('pagereveal', (e) => {
	if (!e.viewTransition) return;
	const from = navigation.activation?.from?.url;  // 이전 엔트리 URL
	if (from && new URL(from).pathname === '/list/') {
		e.viewTransition.types.add('from-list');
	}
});
```

`navigation.activation` 은 Navigation API 가 제공하는 객체로 `from`, `entry`, `navigationType` 을 담고 있어 "목록에서 왔는지, 뒤로 가기인지"를 판별할 수 있다. `pageswap` 의 `activation` 은 크로스 오리진 목적지에서는 채워지지 않는다.

MPA 전환의 실질적 비용은 **도착 문서의 첫 렌더가 전환 시작점**이라는 데 있다. 서버 응답이 느리면 사용자는 얼어붙은 이전 화면을 오래 본다. 렌더 블로킹 리소스를 줄이고 중요한 CSS 를 인라인하는 기존 최적화가 그대로 전환 품질로 이어진다.

## 8. 접근성과 성능

전환은 장식이다. 전정 장애가 있는 사용자에게 화면 전체가 미끄러지는 연출은 실제로 불쾌감을 유발한다. `prefers-reduced-motion` 대응은 선택이 아니다.

```css
@media (prefers-reduced-motion: reduce) {
	::view-transition-group(*),
	::view-transition-old(*),
	::view-transition-new(*) {
		animation: none !important;
	}
}
```

`animation: none` 으로 무력화하면 전환 자체는 일어나되 즉시 끝나므로, JS 분기 없이 안전하게 처리된다. 더 강하게 막고 싶다면 `matchMedia('(prefers-reduced-motion: reduce)').matches` 일 때 `startViewTransition` 을 건너뛰고 바로 `update()` 를 호출하면 된다.

성능 측면에서 비용은 세 곳에서 발생한다. 첫째, **스냅샷 캡처**다. 캡처 대상은 GPU 텍스처로 만들어지고 크기는 snapshot containing block 에 비례하므로, 고해상도 디스플레이의 전체 화면 캡처는 적지 않은 메모리를 쓴다. 둘째, **이름 개수**다. 이름 하나가 그룹·이미지 쌍·구·신 네 개의 의사 요소와 합성 레이어를 만든다. 목록의 30개 항목 전부에 이름을 붙이면 합성 부담이 그만큼 늘어 60fps 의 16.7ms 예산을 넘기기 쉽다. 실무 기준은 **한 전환당 독립 그룹 5개 이하**, 나머지는 `root` 크로스페이드에 맡기는 것이다. 셋째, **렌더링 정지 구간**이다. 콜백이 동기적으로 무거운 레이아웃을 유발하면 그 시간만큼 화면이 멈춘다.

입력 처리도 확인해야 한다. 명세는 전환이 `animating` 단계일 때 캡처된 요소와 그 콘텐츠가 **그려지지 않고 히트 테스트에도 응답하지 않는다**고 규정한다. 실제로 보이는 것은 의사 요소 쪽 이미지이고 원래 DOM 위치와 렌더 위치가 어긋나 있으므로, 그 좌표로 이벤트를 흘려보내면 잘못된 요소가 눌리기 때문이다. 결과적으로 전환 중 클릭은 사실상 무시된다. 지속 시간을 길게 잡을수록 사용자가 "먹통"을 체감하는 시간이 늘어난다. 기본값 0.25s 가 괜히 그 값인 것이 아니며, 300ms 를 넘기는 전환은 재검토 대상이다.

## 9. React·Next.js 연동과 FLIP 대비 정산

React 는 렌더 결과를 언제 커밋할지 스스로 결정하므로, `startViewTransition` 의 콜백 안에서 `setState` 를 호출해도 그 시점에 DOM 이 바뀐다는 보장이 없다. 동기 커밋을 강제하는 우회가 가능은 하다.

```jsx
import { flushSync } from 'react-dom';

function onSelect(id) {
	if (!document.startViewTransition) { setSelected(id); return; }
	document.startViewTransition(() => flushSync(() => setSelected(id)));
}
```

다만 `flushSync` 는 동시성 기능을 끄고 동기 렌더를 강제하므로 큰 트리에서는 긴 작업으로 이어진다. React 팀은 이 문제를 프레임워크 레벨에서 풀기 위해 실험 채널에서 `<ViewTransition>` 컴포넌트를 제공하고 있으며, `startTransition` 으로 표시된 업데이트의 커밋 시점에 브라우저 전환을 자동으로 엮는 방향이다. Next.js 는 `next.config` 의 `experimental.viewTransition` 플래그로 이 실험 기능을 켜는 경로를 두고 있다. 두 가지 모두 실험 단계이므로 API 형태가 바뀔 수 있고, 프로덕션 도입은 신중해야 한다.

한편 App Router 의 클라이언트 사이드 내비게이션은 문서를 교체하지 않으므로 `@view-transition { navigation: auto; }` 가 적용되지 않는다. MPA 전환을 기대했다가 동작하지 않는 사례 대부분이 여기에 해당한다.

마지막으로 FLIP 수동 구현과의 비교다.

| 항목 | FLIP + Web Animations | View Transitions API |
| --- | --- | --- |
| 요소 1개 전환 코드량 | 20~40줄 JS | CSS 1줄(`view-transition-name`) |
| 사라지는 요소 처리 | 복제본 생성 또는 삭제 지연 필요 | `::view-transition-old` 로 자동 제공 |
| 자식 왜곡 보정 | 자식마다 역스케일 애니메이션 추가 | `object-fit` 조정으로 대체 |
| 전체 페이지 크로스페이드 | 사실상 불가(전체 복제 필요) | 기본 동작 |
| 크로스 도큐먼트 | 불가 | `@view-transition` 으로 지원 |
| 애니메이션 대상 속성 | `transform`, `opacity` 위주 | 스냅샷 이미지라 레이아웃 변화 전반 |
| 실행 스레드 | 메인 스레드 측정 + 컴포지터 | 캡처 후 컴포지터 중심 |
| 브라우저 지원 | 전 브라우저 | Chromium 우선 구현, 확산 중 |
| 실패 모드 | 측정 타이밍 어긋남, 시각적 점프 | 이름 중복 시 전환 스킵(상태는 정상) |

정리하면, 두 방식의 차이는 편의성이 아니라 **실패 모드의 성격**이다. FLIP 은 실패하면 화면이 튀고 사용자는 버그를 본다. View Transitions 는 실패하면 애니메이션이 없을 뿐 상태 변경은 정상적으로 끝난다. 명세가 "전환은 상태 변경을 감싸는 향상 기능이며, 전환 실패가 상태 변경을 막아서는 안 된다"는 원칙을 명시적으로 적어 둔 이유다. 이 설계 덕분에 미지원 브라우저 폴백이 `if (!document.startViewTransition)` 세 줄로 끝나고, 점진적 향상 전략에 그대로 얹힌다.

## 참고

- [View Transition API — MDN](https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API)
- [Document.startViewTransition() — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Document/startViewTransition)
- [ViewTransition — MDN](https://developer.mozilla.org/en-US/docs/Web/API/ViewTransition)
- [view-transition-name — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/view-transition-name)
- [@view-transition — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/@view-transition)
- [Window: pageswap event — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Window/pageswap_event)
- [Window: pagereveal event — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Window/pagereveal_event)
- [CSS View Transitions Module Level 1 — W3C](https://drafts.csswg.org/css-view-transitions-1/)
- [CSS View Transitions Module Level 2 — W3C](https://drafts.csswg.org/css-view-transitions-2/)
- [Smooth transitions with the View Transition API — web.dev](https://web.dev/articles/view-transitions)
