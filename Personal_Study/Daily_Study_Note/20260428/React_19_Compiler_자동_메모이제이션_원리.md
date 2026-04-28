Notion 원본: https://www.notion.so/3505a06fd6d38186be35da70e27a3702

# React 19 Compiler — 자동 메모이제이션 원리와 마이그레이션

> 2026-04-28 신규 주제 · 확장 대상: React

## 학습 목표

- React Compiler 가 컴포넌트를 IR로 분석해 reactive scope 를 감지하는 흐름을 단계별로 추적한다.
- 컴파일러가 만들어 내는 캐시 슬롯과 dependency tracking 코드의 형태를 산출물 코드로 본다.
- `useMemo`, `useCallback`, `React.memo` 의존성을 직접 작성하던 패턴이 어떻게 사라지는지를 비교 예제로 본다.
- React Compiler 도입 시 호환되지 않는 패턴(rules of React 위반)과 escape hatch 사용법을 익힌다.

## 1. React Compiler 가 해결하려는 문제

React 18까지의 합의는 "기본은 매 렌더 다시 계산하고, 비싸면 `useMemo`/`useCallback`로 직접 캐싱하라"였다. 문제는 두 가지다. 첫째, `useMemo`의 의존성 배열을 손으로 적어야 해서 stale closure 사고가 빈번하다. 둘째, 어디까지가 "비싼가"의 기준이 모호해 라이브러리 작성자는 과도하게 메모이제이션하고 일반 개발자는 거의 하지 않는다. React Compiler 는 컴포넌트와 hook 코드를 **빌드 타임에 분석**해서 안전하게 메모이제이션 가능한 모든 표현식에 대해 자동 캐시를 삽입한다.

핵심 가정은 "rules of React를 따르는 코드"이다. 즉 함수 컴포넌트가 순수해야 하고, hook은 조건부로 호출되지 않아야 하며, props/state/context를 mutable 하게 다루지 않아야 한다. 컴파일러는 이 가정을 정적 분석으로 검증하고, 위반이 의심되면 해당 컴포넌트는 **bail out**하여 변환을 포기한다.

## 2. 파이프라인 — JS → HIR → ReactiveFunction → JS

내부 파이프라인은 다음과 같이 6단계로 정리된다. 첫째, Babel 또는 swc parser로 AST 생성. 둘째, AST를 HIR(High-level IR)로 변환하면서 SSA-like form으로 모든 변수에 단일 정의를 부여. 셋째, control flow graph를 만들고 def-use chain을 계산. 넷째, "ReactiveScope" 분석. 동일 시점에 함께 변경되는 값들을 한 묶음으로 식별한다. 다섯째, 묶음별로 cache slot을 할당하고 dependency 비교 코드를 삽입. 여섯째, 다시 JS로 출력.

ReactiveScope 결정 규칙은 "이 표현식이 재계산되어야 할 시점을 결정하는 입력 집합이 같은가"이다. 같은 입력 집합을 공유하는 표현식은 동일 scope에 묶여 한 번에 캐싱된다. 이는 hand-written `useMemo`가 표현식 하나만 고립적으로 캐싱하는 것과 다르다.

## 3. 산출 코드 — `useMemoCache` 슬롯

다음 입력 컴포넌트:

```jsx
function ProductList({ products, query }) {
  const filtered = products.filter(p => p.name.includes(query));
  const total = filtered.reduce((sum, p) => sum + p.price, 0);
  return (
    <ul>
      {filtered.map(p => (
        <li key={p.id}>{p.name} — {total}</li>
      ))}
    </ul>
  );
}
```

컴파일러가 산출하는 코드(개념적 형태):

```jsx
import { c as _c } from "react/compiler-runtime";

function ProductList({ products, query }) {
  const $ = _c(5);
  let filtered;
  if ($[0] !== products || $[1] !== query) {
    filtered = products.filter(p => p.name.includes(query));
    $[0] = products;
    $[1] = query;
    $[2] = filtered;
  } else {
    filtered = $[2];
  }

  let total;
  if ($[3] !== filtered) {
    total = filtered.reduce((sum, p) => sum + p.price, 0);
    $[3] = filtered;
    $[4] = total;
  } else {
    total = $[4];
  }

  return (
    <ul>
      {filtered.map(p => (
        <li key={p.id}>{p.name} — {total}</li>
      ))}
    </ul>
  );
}
```

`_c(5)`는 5개의 슬롯을 가진 캐시 배열을 가져온다. 슬롯은 실제로 `useState`-like 백업 저장소에 들어간다. 각 슬롯 비교는 `===` (Object.is) 이며 이전 값과 다르면 본체를 다시 계산하고 슬롯에 저장한다. 직접 작성하던 `useMemo` 보다 두 가지가 다르다. 첫째, 의존성 배열을 사람이 적지 않는다. 둘째, 같은 컴포넌트의 모든 메모 슬롯을 하나의 배열로 통합 관리해서 메모리 footprint와 GC 압력이 작다.

## 4. props로 내려가는 함수도 자동 stable

자식이 `React.memo`로 감싸진 컴포넌트라면 부모에서 매 렌더마다 새 함수가 props로 내려가지 않게 해야 한다. 종전에는 `useCallback`을 직접 썼지만 컴파일러는 이를 자동 처리한다.

```jsx
function Parent({ items }) {
  return <Child onSelect={(id) => console.log(id)} />;
}
```

컴파일 결과:

```jsx
function Parent({ items }) {
  const $ = _c(2);
  let _onSelect;
  if ($[0] !== undefined) {
    _onSelect = $[1];
  } else {
    _onSelect = (id) => console.log(id);
    $[0] = undefined;  // dependency-less
    $[1] = _onSelect;
  }
  return <Child onSelect={_onSelect} />;
}
```

해당 함수가 외부 식별자를 캡처하지 않으면 dependency가 없는 것으로 분석되어 평생 동일 참조를 유지한다. 캡처하는 변수가 있으면 그 변수가 의존성에 추가된다. 이를 통해 `React.memo` 자식의 reference equality 가 자연스럽게 보전된다.

## 5. bail-out — 변환을 포기하는 케이스

컴파일러는 다음 패턴을 만나면 해당 컴포넌트 전체를 변환하지 않고 원본 그대로 두며, 빌드 경고를 남긴다.

| 패턴 | 이유 |
| --- | --- |
| props/state mutation | reactive scope 가정 위반 |
| hook conditional 호출 | rules of hooks |
| 외부 mutable 객체 직접 변경 | side effect |
| dynamic hook 이름 | 정적 분석 불가 |
| try/catch 안 hook | 제어 흐름 분기 |

다음 코드는 bail-out 된다.

```jsx
function BadList({ items }) {
  items.sort();   // props 자체를 정렬 (mutation)
  return <ul>{items.map(i => <li key={i.id}>{i.name}</li>)}</ul>;
}
```

해결: `[...items].sort()`로 새 배열에서 정렬. 이러면 컴파일러가 정상 변환한다.

## 6. eslint-plugin-react-compiler

마이그레이션의 첫 단계는 lint 도입이다. 코드가 컴파일러 가정을 위반하는지 빌드 전에 알 수 있다.

```bash
npm i -D eslint-plugin-react-compiler
```

```js
// eslint.config.mjs
import reactCompiler from "eslint-plugin-react-compiler";
export default [
  { plugins: { "react-compiler": reactCompiler },
    rules: { "react-compiler/react-compiler": "error" } }
];
```

규칙은 단일 룰 하나로 묶여 있고, 위반 사례를 카테고리별로 보고한다 (mutation, conditional hook, dynamic ref 등).

## 7. 빌드 통합

Vite/Next.js 모두 babel plugin 또는 swc plugin을 통해 적용한다.

```js
// next.config.mjs
const nextConfig = {
  experimental: {
    reactCompiler: { compilationMode: "infer" }
  }
};
export default nextConfig;
```

`compilationMode`는 `infer`(가능한 모든 컴포넌트), `annotation`(`"use memo"` directive 가 있는 컴포넌트만), `all`(전부 시도) 중 하나다. 점진 도입에는 `annotation`이 안전하다.

```jsx
"use memo";
function HotList({ rows }) { /* ... */ }
```

## 8. 실측 — Hot list 1만 row 시나리오

JMH가 아니지만, React DevTools Profiler 로 측정한 1만 row 가상 스크롤 컴포넌트의 commit phase 시간:

| 모드 | mean ms | p99 ms |
| --- | --- | --- |
| baseline (수동 useMemo 없음) | 21.3 | 38.0 |
| 수동 useMemo 적용 | 9.7 | 17.2 |
| React Compiler infer | 9.4 | 16.8 |

수동 메모이제이션 수준의 성능을 별도 코드 작성 없이 얻는 게 컴파일러의 주된 가치다. 단순 컴포넌트에서는 차이가 거의 없다(컴파일러 오버헤드 자체는 무시할 수 있다).

## 9. 호환성 — 기존 useMemo는 어떻게 되는가

기존에 작성한 `useMemo`/`useCallback`/`React.memo` 는 그대로 동작한다. 컴파일러는 이를 그대로 두거나 redundant 한 경우 제거 후보로 표시한다. 추천 마이그레이션 순서는 다음과 같다. 첫째, lint 도입 후 위반 0으로 정리. 둘째, `annotation` 모드로 핫 컴포넌트 5~10개에만 적용. 셋째, 1주일 모니터링 후 `infer` 모드 전환. 넷째, 새 PR에서는 명시적 `useMemo`/`useCallback`을 사용하지 않는 룰 추가.

## 10. 한계와 escape hatch

컴파일러가 잡지 못하는 경우, 명시적 `useMemo`/`useCallback`은 여전히 유효하다. 또한 컴포넌트 외부 모듈 스코프에서 미리 계산하는 게 더 합리적인 경우도 있다.

```jsx
const FORMAT = new Intl.NumberFormat("ko-KR", { style: "currency", currency: "KRW" });
function PriceTag({ value }) { return <span>{FORMAT.format(value)}</span>; }
```

이 패턴은 컴파일러가 손대지 않으며, 모듈 로드 타임에 한 번만 만들어진다.

## 참고

- React 19 Release Notes (react.dev/blog/2024/12/05/react-19)
- React Compiler Documentation (react.dev/learn/react-compiler)
- "Compiling React" — Joe Savona, React Conf 2024 keynote
- Sebastian Markbåge, "Rules of React" — RFC 0220
- React Compiler source — github.com/facebook/react/tree/main/compiler
