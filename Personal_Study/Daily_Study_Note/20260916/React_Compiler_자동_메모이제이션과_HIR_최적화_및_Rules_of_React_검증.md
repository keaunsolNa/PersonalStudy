Notion 원본: https://www.notion.so/3dd5a06fd6d3811c990ad649d68bdc40

# React Compiler 자동 메모이제이션과 HIR 최적화 및 Rules of React 검증

> 2026-09-16 신규 주제 · 확장 대상: React 심화(훅 렌더링 모델), Next.js 렌더링 파이프라인

## 학습 목표

- 수동 메모이제이션이 실패하는 구조적 이유를 참조 동일성 관점으로 설명한다
- 컴파일러가 HIR 로 변환해 반응형 스코프를 추론하는 과정을 단계별로 읽는다
- 컴파일러가 컴파일을 포기(bailout)하는 조건과 그 신호를 해석한다
- ESLint 규칙과 런타임 검증으로 도입 리스크를 단계적으로 줄인다

## 1. 수동 메모이제이션이 깨지는 지점

React 의 재렌더링은 "상태가 바뀜 컴포넌트와 그 아래 전부"를 다시 실행한다. 대부분의 경우 이것으로 충분하지만, 렌더 비용이 큰 서브트리에서는 `React.memo` 로 차단해야 한다. 문제는 `memo` 가 props 를 얕은 비교로 판단한다는 것이다.

```jsx
function Parent({ items }) {
	const [query, setQuery] = useState("");
	const visible = items.filter((it) => it.name.includes(query));

	return (
		<>
			<input value={query} onChange={(e) => setQuery(e.target.value)} />
			<ExpensiveList items={visible} onSelect={(id) => console.log(id)} />
		</>
	);
}
```

`ExpensiveList` 를 `memo` 로 감싸도 소용이 없다. `visible` 은 매 렌더마다 새 배열이고 `onSelect` 는 매 렌더마다 새 함수이므로, 얕은 비교가 항상 실패한다. 고치려면 두 값을 각각 `useMemo`·`useCallback` 으로 감싸야 한다.

```jsx
const visible = useMemo(
	() => items.filter((it) => it.name.includes(query)),
	[items, query],
);
const handleSelect = useCallback((id) => console.log(id), []);
```

이 방식의 문제는 셋이다. 첫째, **전염성**이다. 한 곳을 메모이제이션하면 그 값을 만드는 상류도 전부 메모이제이션해야 효과가 난다. 둘째, **의존성 배열의 정확성**을 사람이 유지해야 한다. 빠뜨리면 낙은 값(stale closure)이 남고, 과하게 넣으면 메모가 무효화되어 이득이 사라진다. 셋째, **비용 대비 이득이 불투명**하다. `useMemo` 자체도 비용이 있고, 얕은 비교와 의존성 배열 비교가 실제 계산보다 비쌀 수도 있다.

React Compiler 는 이 세 문제를 컴파일 타임으로 옮긴다. 개발자는 평범한 코드를 쓰고, 컴파일러가 어떤 값이 어떤 입력에 의존하는지 분석해 필요한 캐싱을 삽입한다.

## 2. 컴파일 결과물의 형태

컴파일러는 컴포넌트 본문 앞에 캐시 슬롯 배열을 만들고, 각 계산 결과를 슬롯에 저장한다.

```jsx
// 입력
function Greeting({ name, items }) {
	const upper = name.toUpperCase();
	const count = items.length;
	return <p>{upper} has {count}</p>;
}
```

대략 다음과 같은 코드가 나온다.

```jsx
import { c as _c } from "react/compiler-runtime";

function Greeting(t0) {
	const $ = _c(7);
	const { name, items } = t0;

	let upper;
	if ($[0] !== name) {
		upper = name.toUpperCase();
		$[0] = name;
		$[1] = upper;
	} else {
		upper = $[1];
	}

	let count;
	if ($[2] !== items) {
		count = items.length;
		$[2] = items;
		$[3] = count;
	} else {
		count = $[3];
	}

	let t1;
	if ($[4] !== upper || $[5] !== count) {
		t1 = <p>{upper} has {count}</p>;
		$[4] = upper;
		$[5] = count;
		$[6] = t1;
	} else {
		t1 = $[6];
	}
	return t1;
}
```

`_c(7)` 은 훅이다. 내부적으로 `useMemoCache` 에 해당하며, 컴포넌트 인스턴스별로 길이 7 짜리 배열을 유지한다. 초기값은 특수 심볼이라 첫 렌더에서 모든 비교가 실패하고 전부 계산된다.

중요한 점은 **JSX 요소 자체도 캐싱된다**는 것이다. `t1` 슬롯이 그것이다. 부모가 리렌더되어도 `upper`·`count` 가 같으면 동일한 요소 객체가 반환되고, React 의 재조정 단계는 `prevElement === nextElement` 인 서브트리를 건너뛴다. `memo` 없이도 자식 렌더가 생략되는 원리가 여기 있다.

## 3. HIR 로의 변환과 반응형 스코프 추론

컴파일러 파이프라인은 대략 이렇다.

1. **Babel AST → HIR**: 고수준 중간 표현으로 낮춘다. HIR 은 기본 블록(basic block)으로 구성된 CFG 이며, 각 명령은 SSA 형태의 임시값에 결과를 쓴다. `if`/`&&`/삼항 같은 제어 흐름이 전부 블록과 분기로 평탄화된다.
2. **타입 추론**: 지역적 타입을 추론한다. 배열 메서드, 훅 호출, 원시값 등을 식별해 이후 판단의 근거로 쓴다.
3. **가변성·별칭 분석**: 각 값이 변형(mutate)되는지, 어떤 값들이 같은 메모리를 공유하는지 추적한다. 컴파일러의 핵심이자 가장 어려운 부분이다.
4. **반응형 스코프 추론**: 함께 무효화되어야 하는 명령들을 묶어 스코프를 만들고, 각 스코프의 입력 집합을 계산한다.
5. **스코프 병합·정리**: 입력이 같은 인접 스코프를 합치고, 캐싱 이득이 없는 스코프(원시값 계산 등)를 제거한다.
6. **코드 생성**: 슬롯 인덱스를 배정하고 위와 같은 조건문을 만든다.

3번이 왜 어려운지는 예시로 드러난다.

```jsx
function Comp({ rows }) {
	const config = { sort: "asc" };
	const result = [];
	for (const row of rows) {
		result.push(transform(row, config));
	}
	return <Table rows={result} />;
}
```

`config` 는 리터럴이지만 `transform` 에 넘어간다. `transform` 이 `config` 를 변형할 수 있는지 컴파일러는 모른다. 알 수 없으면 보수적으로 "변형될 수 있다"고 가정하고, `config` 와 `result` 를 같은 스코프로 묶는다. 결과적으로 캐싱 단위가 커지고 무효화가 잦아진다. 반대로 `transform` 이 같은 파일에 있어 분석 가능하면, 순수 함수임을 확인하고 더 잘게 쪼걠 수 있다.

이 성질 때문에 실무 최적화 팁이 하나 나온다. **컴포넌트 밖으로 뿺 수 있는 상수는 밖으로 뻐다.** 모듈 스코프 상수는 렌더마다 만들어지지 않으므로 스코프 입력에서 아예 빠진다.

```jsx
const CONFIG = { sort: "asc" };   // 모듈 스코프

function Comp({ rows }) {
	const result = rows.map((row) => transform(row, CONFIG));
	return <Table rows={result} />;
}
```

## 4. 컴파일이 포기되는 조건

컴파일러는 안전을 보장할 수 없으면 해당 함수를 건드리지 않고 원본 그대로 둔다. 전체 빌드가 실패하지 않는다는 점이 중요하다. 주요 bailout 조건은 다음과 같다.

| 조건 | 이유 |
|---|---|
| 조건부 훅 호출 | 훅 순서 규칙 위반, 캐시 슬롯 배정 불가 |
| 루프 안 훅 호출 | 위와 동일 |
| 렌더 중 props·state 변형 | 값 의미론 보장 불가 |
| `eval` 사용 | 정적 분석 불가 |
| 미지원 문법(레거시 데코레이터 등) | 파싱 단계에서 제외 |
| `"use no memo"` 지시어 | 명시적 옵트아웃 |
| ref 를 렌더 중 읽기 | 렌더가 순수하지 않음 |

렌더 중 props 변형은 특히 흔한 위반이다.

```jsx
function Bad({ config }) {
	config.initialized = true;       // props 변형 — bailout
	return <Child config={config} />;
}
```

`"use no memo"` 는 문제가 있는 컴포넌트를 임시로 제외할 때 쓴다. 도입 초기에 회귀가 발견되면 해당 파일만 빠르게 격리하고 원인을 찾는 데 유용하다.

```jsx
function LegacyWidget() {
	"use no memo";
	// ...
}
```

컴파일 결과를 확인하려면 ESLint 플러그인의 진단을 보거나 빌드 로그를 켜다. 어떤 컴포넌트가 컴파일되었는지 세는 것이 도입 지표가 된다. 커버리지가 60% 라면 나머지 40% 에 실제 버그가 숨어 있을 가능성이 높다는 신호로 읽는다.

## 5. Rules of React 와 린트

컴파일러의 전제는 컴포넌트와 훅이 **순수**하다는 것이다. 같은 입력에 같은 출력, 렌더 중 부수효과 없음. 이 전제가 깨지면 컴파일러의 캐싱이 동작 변화를 일으킨다.

`eslint-plugin-react-hooks` 의 최신 계열은 컴파일러의 분석 엔진을 그대로 써서 이런 위반을 잡는다. 설정은 다음과 같다.

```js
// eslint.config.js
import reactHooks from "eslint-plugin-react-hooks";

export default [
	{
		files: ["**/*.{js,jsx,ts,tsx}"],
		plugins: { "react-hooks": reactHooks },
		rules: {
			...reactHooks.configs.recommended.rules,
		},
	},
];
```

잡힐는 대표적 패턴은 이렇다.

```jsx
// 렌더 중 외부 변수 변형
let renderCount = 0;
function Counter() {
	renderCount++;                      // 부수효과
	return <span>{renderCount}</span>;
}

// 렌더 중 ref 읽기
function Widget() {
	const ref = useRef(0);
	return <div>{ref.current}</div>;    // 순수하지 않음
}

// 컴포넌트 안에서 컴포넌트 정의
function Outer() {
	function Inner() { return <p>x</p>; }  // 매 렌더마다 새 타입 → 상태 소실
	return <Inner />;
}
```

세 번째는 컴파일러와 무관하게 이미 버그다. 컴포넌트 타입이 매번 바뀜므로 React 가 서브트리를 통 언마운트/마운트하고 내부 상태가 사라진다. 컴파일러 도입 과정에서 이런 잠재 버그가 드러나는 경우가 많다.

## 6. 설정과 점진적 도입

Babel 플러그인으로 붙인다. 번들러별 설정은 다르지만 개념은 같다.

```js
// vite.config.js
import react from "@vitejs/plugin-react";

export default {
	plugins: [
		react({
			babel: {
				plugins: [["babel-plugin-react-compiler", {}]],
			},
		}),
	],
};
```

전체 적용이 부담스러우면 디렉터리 단위로 제한한다.

```js
["babel-plugin-react-compiler", {
	sources: (filename) => filename.includes("/src/features/checkout/"),
}]
```

Next.js 는 설정 플래그로 노출한다.

```js
// next.config.js
module.exports = {
	experimental: { reactCompiler: true },
};
```

React 18 을 쓰는 프로젝트라면 `react-compiler-runtime` 심(shim)이 필요하다. `useMemoCache` 훅이 React 19 부터 내장이기 때문이다.

```js
["babel-plugin-react-compiler", { target: "18" }]
```

도입 순서는 다음이 안전하다.

1. 린트 플러그인만 먼저 켜다. 규칙 위반을 전부 수정한다.
2. 컴파일러를 **개발 빌드에만** 켜고 앱을 수동 테스트한다.
3. 일부 라우트나 기능 디렉터리에 한정해 프로덕션 적용한다.
4. 커버리지와 성능 지표를 비교한 뒤 범위를 넓힌다.
5. 안정화 후 불필요해진 `useMemo`/`useCallback` 을 제거한다.

5번을 서두르지 않는 것이 좋다. 기존 수동 메모이제이션은 컴파일러와 충돌하지 않는다. 컴파일러가 이미 캐싱한 값을 `useMemo` 로 한 번 더 감싸는 정도의 중복 비용만 생긴다. 안정성이 확인되기 전에 대량 삭제하면 롤백이 어려워진다.

## 7. 성능 효과를 어떻게 측정하는가

컴파일러의 이득은 "렌더 횟수 감소"로 나타난다. 측정 도구는 React DevTools Profiler 이고, 확인할 항목은 커밋당 렌더된 컴포넌트 수와 커밋 소요 시간이다.

```jsx
import { Profiler } from "react";

<Profiler
	id="checkout"
	onRender={(id, phase, actualDuration, baseDuration) => {
		// baseDuration: 메모이제이션 없이 전부 렌더했을 때의 추정 시간
		// actualDuration: 실제 소요 시간
		reportMetric({ id, phase, actualDuration, baseDuration });
	}}
>
	<Checkout />
</Profiler>
```

`baseDuration` 대비 `actualDuration` 비율이 메모이제이션 효율의 대리 지표가 된다. 다만 `Profiler` 자체가 오버헤드를 만드므로 프로덕션 상시 측정에는 적합하지 않다. 샘플링하거나 특정 세션에만 켜다.

기대치는 현실적으로 잡아야 한다. 컴파일러는 **렌더 비용이 병목인 경우**에만 효과가 크다. 병목이 네트워크 대기, 대형 리스트의 DOM 노드 수, 이미지 디코딩이라면 체감 변화가 거의 없다. 먼저 프로파일로 병목을 확인하고 나서 도입 효과를 예상하는 순서가 맞다.

또 하나, 컴파일러는 번들 크기를 **늘린다**. 조건 분기와 슬롯 배열 코드가 컴포넌트마다 추가되기 때문이다. 대략 함수당 수십 바이트에서 수백 바이트 수준이며, 컴포넌트가 수천 개인 앱에서는 무시하기 어렵다. gzip 후 증가분을 측정해 렌더 성능 이득과 비교해야 한다.

## 8. 남는 판단의 영역

컴파일러가 모든 메모이제이션을 대체하지는 않는다. 다음은 여전히 사람이 결정한다.

**비용이 극단적으로 큰 계산**. 컴파일러는 스코프 단위로 캐싱하지만, 부모가 언마운트되면 캐시도 사라진다. 컴포넌트 수명을 넘어 유지해야 하는 계산은 모듈 레벨 캐시나 데이터 레이어(TanStack Query 등)가 맡는다.

**참조 동일성이 계약인 경우**. 서드파티 라이브러리가 콜백의 참조 동일성에 의존해 이벤트 리스너를 재등록한다면, 컴파일러의 캐싱 범위와 그 요구가 맞는지 확인해야 한다. 이런 경계에서는 `useCallback` 을 명시적으로 남겨 두는 편이 의도가 분명하다.

**리스트 가상화**. 1만 행을 렌더하는 문제는 메모이제이션으로 풀리지 않는다. 화면에 보이는 것만 렌더하는 구조적 변경이 필요하다.

정리하면 React Compiler 는 "참조 동일성 관리"라는 반복적이고 오류가 잦은 작업을 기계에 넘기는 도구다. 아키텍처 선택과 데이터 흐름 설계는 여전히 사람의 몫이고, 컴파일러는 그 위에서 상수 배수의 낭비를 걷어난다.

## 참고

- React Docs — React Compiler: https://react.dev/learn/react-compiler
- React Docs — Rules of React: https://react.dev/reference/rules
- React Compiler 소스 (HIR·패스 구현): https://github.com/facebook/react/tree/main/compiler
- React Docs — `<Profiler>`: https://react.dev/reference/react/Profiler
- eslint-plugin-react-hooks: https://www.npmjs.com/package/eslint-plugin-react-hooks
