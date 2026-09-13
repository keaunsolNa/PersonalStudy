Notion 원본: https://app.notion.com/p/3da5a06fd6d3815d90dfe5e69562966d?pvs=204

# CSS Container Queries와 Cascade Layers 및 has 선택자 스타일 격리

> 2026-09-13 신규 주제 · 확장 대상: React 컴포넌트 설계 / 브라우저 렌더링 파이프라인

## 학습 목표

- 미디어 쿼리로 컴포넌트 반응형을 구현할 때 생기는 결합을 제거한다
- `@layer` 의 우선순위 규칙을 명시도·`!important` 와 함께 정확히 정리한다
- `:has()` 의 매칭 비용과 무효화 범위를 렌더링 엔진 관점에서 파악한다
- 세 기능을 조합해 디자인 시스템의 스타일 충돌을 구조적으로 막는다

## 1. 컴포넌트 반응형의 잘못된 기준점

카드 컴포넌트가 좁을 때는 세로 배치, 넓을 때는 가로 배치가 되어야 한다고 하자. 미디어 쿼리로 쓰면 이렇게 된다.

```css
.card {
	display: flex;
	flex-direction: column;
}

@media (min-width: 640px) {
	.card {
		flex-direction: row;
	}
}
```

이 코드의 전제는 "뷰포트가 640px 이상이면 카드도 넓다"는 것이다. 그런데 같은 카드를 사이드바(280px)에 넣으면 데스크톱 뷰포트에서 가로 배치가 되어 깨진다.

기존 해법은 부모가 변형 클래스를 주입하는 것이었다.

```jsx
<aside><Card variant="narrow" /></aside>
<main><Card variant="wide" /></main>
```

컴포넌트가 **자기 배치 맥락을 몰라도 되는 성질**이 여기서 깨진다. 부모가 자식의 레이아웃 사정을 알아야 하고, 중첩이 깊어지면 변형이 조합 폭발한다. React 로 컴포넌트를 나눠 놓고 CSS 로는 다시 합쳐 버리는 셈이다.

컨테이너 쿼리는 기준을 **가장 가까운 컨테이너의 크기**로 바꿈다.

```css
.card-wrapper {
	container-type: inline-size;
	container-name: card;
}

.card {
	display: flex;
	flex-direction: column;
}

@container card (min-width: 400px) {
	.card {
		flex-direction: row;
	}
}
```

이제 카드는 어디에 놓이든 자기가 받은 폭을 보고 결정한다. 사이드바든 메인이든 모달이든 동일한 컴포넌트가 맞는 모양으로 렌더링된다.

## 2. containment 와 순환 참조 문제

`container-type` 은 단순한 표식이 아니라 **CSS containment 를 활성화**한다. 값마다 적용 범위가 다르다.

| 값 | 적용되는 containment | 조회 가능한 축 |
|---|---|---|
| `normal` | 없음 | 크기 조회 불가(스타일 컨테이너만) |
| `inline-size` | `layout`, `style`, `inline-size` | 인라인 축(보통 가로) |
| `size` | `layout`, `style`, `size` | 양축 |

왜 containment 가 필요한가? 순환 참조를 막기 위해서다.

```css
@container (min-width: 400px) {
	.card { padding: 2rem; }
}
```

컨테이너의 크기가 자식 콘텐츠에 의해 결정되는데 자식 스타일이 컨테이너 크기에 따라 바뀐다면 무한 루프다. 패딩이 커져서 → 콘텐츠가 좁아져서 → 컨테이너가 작아져서 → 패딩이 작아져서 …

containment 는 "컨테이너의 크기는 자손과 무관하게 결정된다"고 선언해 루프를 끊는다. `inline-size` 는 가로 크기만 자손과 분리하므로, 세로는 여전히 콘텐츠에 따라 늘어난다. 대부분의 레이아웃에서 원하는 동작이다.

`container-type: size` 는 세로까지 분리한다. 즉 **높이를 명시하지 않으면 높이가 0 이 된다**. 실수하기 가장 쉽은 지점이다.

```css
/* 위험: 높이 0 이 될 수 있다 */
.panel { container-type: size; }

/* 안전 */
.panel {
	container-type: size;
	block-size: 100dvh;
}
```

또 하나 자주 틀리는 것: **요소는 자기 자신의 컨테이너가 될 수 없다**.

```css
/* 동작하지 않음 */
.card {
	container-type: inline-size;
}
@container (min-width: 400px) {
	.card { flex-direction: row; }
}
```

`@container` 는 항상 조상 컨테이너를 찾는다. 그래서 래퍼 요소가 하나 필요하다. 이 제약이 마크업을 늘리는 것이 컨테이너 쿼리의 실질적 비용이다.

## 3. 컨테이너 쿼리 단위

컨테이너 기준 상대 단위를 쓰면 조건 분기 없이 연속적으로 반응한다.

| 단위 | 의미 |
|---|---|
| `cqw` | 컨테이너 인라인 크기의 1% |
| `cqh` | 컨테이너 블록 크기의 1% |
| `cqi` | 인라인 축 1% |
| `cqb` | 블록 축 1% |
| `cqmin` / `cqmax` | `cqi`/`cqb` 중 작은/큰 쪽 |

```css
.card-title {
	font-size: clamp(1rem, 4cqi, 2rem);
}
```

컨테이너가 250px 이면 4cqi = 10px 이므로 `clamp` 의 하한 1rem 이 적용되고, 800px 이면 32px 이지만 상한 2rem 에서 멈춘다. 중간 구간에서는 폭에 비례해 부드럽게 변한다. 브레이크포인트 세 개를 선언하는 것보다 코드가 짧고 사이 구간의 어색함이 없다.

`cqi` 를 쓰려면 조상에 `container-type` 이 있어야 한다. 없으면 소형 뷰포트(small viewport)를 기준으로 폴백한다 — 조용히 잘못된 값이 나오므로 주의한다.

## 4. Cascade Layers 의 우선순위 규칙

`@layer` 는 명시도 싸움을 끝내기 위한 기능이다. 핵심 규칙 하나를 정확히 외워야 한다.

> **레이어 순서가 명시도보다 먼저 평가된다.**

```css
@layer reset, base, components, utilities;

@layer components {
	.button.primary.large#submit {
		background: blue;
	}
}

@layer utilities {
	.bg-red {
		background: red;
	}
}
```

`.bg-red` 는 클래스 하나(0,1,0)이고 위쪽은 ID 까지 붙은 (1,3,0) 이다. 그런데 **`utilities` 가 뒤에 선언됐으므로 빨강이 이긴다**. 명시도는 같은 레이어 안에서만 비교된다.

이 규칙이 Tailwind 같은 유틸리티 우선 접근과 컴포넌트 CSS 를 공존시킨다. 유틸리티를 마지막 레이어에 두면 `!important` 없이도 항상 덮어쓴다.

전체 우선순위 계단은 이렇다(아래가 강함).

```
1. 레이어에 속한 일반 선언 — 레이어 선언 순서대로
2. 레이어에 속하지 않은 일반 선언  ← 레이어보다 항상 강함
3. 레이어에 속한 !important — 레이어 선언 순서의 역순
4. 레이어에 속하지 않은 !important
```

두 가지가 직관에 어깋난다.

**레이어 밖 스타일이 모든 레이어보다 강하다.** 그래서 "일단 레이어 없이 쓰다가 나중에 정리하자"는 접근이 위험하다. 레이어로 옮기는 순간 우선순위가 떨어져 깨진다. 반대로, 서드파티 CSS 를 레이어로 감싸면 내 스타일이 자동으로 이긴다.

```css
@import url("vendor.css") layer(vendor);
@layer vendor, app;
```

**`!important` 에서는 레이어 순서가 뒤집힌다.** 먼저 선언된 레이어(`reset`)의 `!important` 가 나중 레이어(`utilities`)의 `!important` 보다 강하다. 의도는 "기반 레이어가 진짜 양보할 수 없는 것을 지킬 수 있게" 하는 것이다. 헷갈리기 쉬우니 레이어 안에서는 `!important` 를 아예 쓰지 않는 규율이 실용적이다.

레이어 순서는 **처음 등장 순서로 고정**되므로, 스타일시트 맨 위에 순서를 한 번에 선언해 두는 것이 정석이다.

```css
@layer reset, tokens, base, layout, components, utilities, overrides;
```

이후 어느 파일에서 어떤 순서로 `@layer components { ... }` 를 써도 순서는 이 선언을 따른다. 번들러의 파일 병합 순서에 의존하지 않게 되는 것이 큰 이득이다.

## 5. `:has()` 의 매칭 모델

`:has()` 는 조상이 자손 조건으로 선택되게 한다. "부모 선택자"라고 불리지만 실제로는 그보다 넓다.

```css
/* 이미지가 있는 카드만 그리드 2열 */
.card:has(> img) {
	display: grid;
	grid-template-columns: auto 1fr;
}

/* 체크된 입력을 가진 라벨 */
label:has(input:checked) {
	font-weight: 600;
}

/* 뒤에 오는 형제 조건 */
h2:has(+ p) {
	margin-block-end: 0.25rem;
}

/* 유효성 상태에 따른 폼 그룹 스타일 */
.field:has(input:user-invalid) {
	border-color: var(--color-danger);
}
```

마지막 예가 실무 가치가 높다. 지금까지는 React state 로 에러 여부를 들고 클래스를 토글해야 했던 것이 CSS 로 끝난다. 폼 상태를 JS 와 CSS 가 이중으로 관리하는 문제가 사라진다.

명시도 계산은 `:is()` 와 같다 — **인자 중 가장 높은 것**을 취하며, `:has()` 자체는 명시도를 더하지 않는다.

```css
.card:has(#special)   /* (1, 1, 0) — ID 가 인자에 있으므로 */
.card:has(.badge)     /* (0, 2, 0) */
```

인자에 ID 를 넣으면 명시도가 치솟으므로, 디자인 시스템에서는 인자를 클래스·요소·상태 의사 클래스로 제한하는 규칙을 두는 편이 낫다.

## 6. `:has()` 의 성능 — 무효화 범위

`:has()` 가 오래 표준화되지 못했던 이유는 구문이 아니라 **스타일 무효화(style invalidation)** 다.

일반 선택자는 왼쪽에서 오른쪽으로 좁혀지고, 브라우저는 실제로는 오른쪽부터 역방향으로 매칭한다. 어떤 요소의 클래스가 바뀌면 그 요소와 그 자손만 다시 계산하면 된다.

`:has()` 는 방향이 반대다. 자손이 바뀌면 **조상의 스타일이 바뀔 수 있다**. 브라우저는 "이 요소가 바뀜을 때 영향받는 조상이 누구인가"를 알아야 한다.

Blink/WebKit 는 이를 위해 무효화 집합에 특수한 플래그를 둔다. 문서에 `.card:has(> img)` 같은 규칙이 하나라도 있으면, `img` 요소의 추가/삭제 시 조상 체인을 거슬러 올라가며 `.card` 를 찾아 무효화 표시를 한다. 이 역방향 탐색이 비용이다.

비용을 줄이는 실천 규칙 셋:

**결합자를 좁힌다.** `:has(> img)` 는 직계 자식만 보므로 탐색이 한 단계다. `:has(img)` 는 서브트리 전체가 대상이다. 직계로 표현 가능하면 반드시 직계를 쓴다.

```css
/* 비쌀 */
.page:has(img) { }

/* 쌀 */
.card:has(> img) { }
```

**주어를 좁힌다.** `*:has(...)` 나 `body:has(...)` 는 문서 루트 근처를 무효화한다. 페이지 전체 리스타일이 발생하면 대형 페이지에서 수십 ms 가 든다.

```css
/* 피할 것 — 모달 열림 상태를 body 에서 감지 */
body:has(dialog[open]) { overflow: hidden; }
```

이 패턴은 편리하지만 대가가 크다. 모달 토글 시 전체 스타일 재계산이 일어난다. 빈도가 낮으니 허용할 만하다는 판단도 가능하지만, 스크롤이나 hover 처럼 **고빈도 이벤트와 엮지 않는 것**이 원칙이다.

```css
/* 절대 피할 것 */
.list:has(.item:hover) { }
```

**동적 조건과 정적 조건을 구분한다.** `:has(> img)` 처럼 DOM 구조가 거의 변하지 않는 조건은 초기 계산 후 무효화가 거의 없다. `:has(input:checked)` 처럼 자주 바뀌는 조건은 매번 무효화된다. 후자는 범위를 최소한으로 잡는다.

측정은 DevTools Performance 패널의 Recalculate Style 항목을 본다. 이벤트 한 번에 재계산 대상 요소 수가 수천 개라면 `:has()` 선택자를 의심한다.

## 7. 세 기능을 엮은 디자인 시스템 구조

실제로 조합하면 이런 골격이 된다.

```css
/* tokens.css */
@layer reset, tokens, base, components, utilities;

@layer tokens {
	:root {
		--space-1: 0.25rem;
		--space-4: 1rem;
		--color-surface: oklch(98% 0.005 250);
		--color-danger: oklch(55% 0.2 25);
		--radius-md: 0.5rem;
	}
}
```

```css
/* components/card.css */
@layer components {
	.card-container {
		container-type: inline-size;
		container-name: card;
	}

	.card {
		display: flex;
		flex-direction: column;
		gap: var(--space-4);
		padding: var(--space-4);
		background: var(--color-surface);
		border-radius: var(--radius-md);
	}

	.card:has(> .card-media) {
		padding-block-start: 0;
	}

	@container card (inline-size >= 400px) {
		.card {
			flex-direction: row;
			align-items: start;
		}

		.card > .card-media {
			flex: 0 0 40%;
		}
	}

	@container card (inline-size >= 700px) {
		.card {
			gap: calc(var(--space-4) * 2);
		}
	}
}
```

역할 분담이 명확하다. 레이어가 **우선순위 충돌**을, 컨테이너 쿼리가 **배치 맥락 의존**을, `:has()` 가 **상태에 따른 조건부 스타일**을 담당한다. 셋 다 JS 를 거치지 않으므로 리렌더링을 유발하지 않는다.

React 에서 쓰면 컴포넌트가 이렇게 단순해진다.

```jsx
export function Card({ media, title, children }) {
	return (
		<div className="card-container">
			<article className="card">
				{media && <div className="card-media">{media}</div>}
				<div className="card-body">
					<h3 className="card-title">{title}</h3>
					{children}
				</div>
			</article>
		</div>
	);
}
```

`variant` prop 도, `useResizeObserver` 도 없다. 크기 관찰을 JS 로 하던 코드를 걷어내면 리사이즈 중 발생하던 레이아웃 스래싱도 함께 사라진다.

## 8. 지원 범위와 점진적 적용

세 기능 모두 2023년에 주요 브라우저 안정 버전에 들어갔고, 2026년 현재 실무 대상 브라우저에서 사용 가능한 범주다. 그래도 구형 환경이 섞인 서비스라면 폴백을 설계한다.

```css
.card {
	display: flex;
	flex-direction: column;
}

@supports (container-type: inline-size) {
	.card-container {
		container-type: inline-size;
	}

	@container (min-width: 400px) {
		.card { flex-direction: row; }
	}
}

@supports not (container-type: inline-size) {
	@media (min-width: 768px) {
		.card { flex-direction: row; }
	}
}
```

`@layer` 는 폴백이 까다롭다. 지원하지 않는 브라우저는 `@layer` 블록 전체를 **무시**하므로 스타일이 통로 사라진다. 점진적 향상이 아니라 붕괴다. 따라서 레이어 도입은 지원 범위를 확정한 뒤 일괄 적용하는 편이 안전하다.

`:has()` 는 미지원 브라우저에서 해당 규칙만 무시되므로, 장식적 용도라면 폴백 없이 써도 된다. 다만 `:has()` 가 포함된 선택자 목록 전체가 무효가 된다는 점에 주의한다.

```css
/* 미지원 브라우저에서 .b 규칙까지 통로 날아감 */
.a:has(img), .b { color: red; }

/* 분리해서 작성 */
.a:has(img) { color: red; }
.b { color: red; }
```

`:is()` 로 감싸면 이 문제를 피할 수 있다 — `:is()` 는 인자 중 파싱 실패한 것만 버린다. 하지만 가독성을 해치므로 규칙을 나누는 쪽이 낫다.

## 참고

- CSS Containment Module Level 3 — Container Queries (https://www.w3.org/TR/css-contain-3/)
- CSS Cascading and Inheritance Level 5 — Cascade Layers (https://www.w3.org/TR/css-cascade-5/#layering)
- CSS Selectors Level 4 — `:has()` (https://www.w3.org/TR/selectors-4/#relational)
- MDN — Using container queries (https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_containment/Container_queries)
- Chromium Blog — CSS :has() and style invalidation (https://developer.chrome.com/blog/has-with-css)
