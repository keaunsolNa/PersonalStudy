Notion 원본: https://www.notion.so/3715a06fd6d381afa984ee31e2a5c273

# React 19 Compiler — 자동 메모이제이션과 useMemo/useCallback의 종말

> 2026-05-31 신규 주제 · 확장 대상: React 기본 (Personal_Study/React) / Next.js (Personal_Study/Next.js)

## 학습 목표

- React Compiler 가 빌드 타임에 어떤 분석을 거쳐 useMemo / useCallback / React.memo 와 동등한 메모이제이션을 자동 삽입하는지 추적한다
- Rules of React(불변성, 순수 컴포넌트) 와 컴파일러의 옵트인/옵트아웃 메커니즘을 식별한다
- 컴파일 결과물(useMemoCache / c() 헬퍼) 을 읽고, 어떤 표현식이 자동 메모이즈됐는지 디버깅한다
- React Compiler 도입 후 수동 메모이제이션을 어떻게 정리할지 마이그레이션 전략을 정한다

## 1. 수동 메모이제이션의 부담

의존성 배열 수동 유지, eslint 자동 fix 한계, 메모이제이션 필요한 위치 판단 난해 — React Compiler 가 정적 분석으로 이 부담을 컴파일러로 옮긴다.

## 2. Rules of React

- 컴포넌트는 순수 (같은 props/state → 같은 JSX)
- props/state/hooks 반환값 불변
- hooks 는 최상위에서만 호출
- 렌더 중 ref.current 읽기 금지

컴파일러가 위반 감지 시 해당 컴포넌트 자동 메모이제이션에서 제외("bailout").

## 3. useMemoCache 삽입

```tsx
// 원본
function ProductList({ products, filter, onSelect }) {
  const filtered = products.filter(p => p.tags.includes(filter));
  const handleClick = (id) => onSelect(id, Date.now());
  return (
    <ul>
      {filtered.map(p => <ProductRow key={p.id} product={p} onClick={handleClick} />)}
    </ul>
  );
}

// 컴파일 후 (개념적)
import { c as _c } from 'react/compiler-runtime';
function ProductList({ products, filter, onSelect }) {
  const $ = _c(6);
  let filtered;
  if ($[0] !== products || $[1] !== filter) {
    filtered = products.filter(p => p.tags.includes(filter));
    $[0] = products; $[1] = filter; $[2] = filtered;
  } else { filtered = $[2]; }
  // ... 생략
}
```

`_c(6)` 은 컴포넌트 인스턴스 단위 mutable 배열. 의존성 자동 추출 + fine-grained + JSX 트리 자체 메모.

## 4. 메모이즈 대상 / 비대상

대상: 객체/배열 literal, 함수 표현식, 연산 결과, JSX 표현식.
비대상: side effect, mutable 인자, hook 호출.

```tsx
function Bad({ user }) {
  user.lastSeen = Date.now();   // props mutate — bailout
  return <div>{user.name}</div>;
}
```

## 5. 옵트인 / 옵트아웃

```js
// next.config.js
module.exports = {
  experimental: { reactCompiler: true },
};
```

```tsx
function LegacyComponent({ data }) {
  "use no memo";
  return <div>{data.title}</div>;
}
```

`compilationMode: 'annotation'` 으로 `"use memo"` 명시된 컴포넌트만 컴파일도 가능.

## 6. eslint-plugin-react-compiler

```json
{
  "plugins": ["react-compiler"],
  "rules": { "react-compiler/react-compiler": "error" }
}
```

대표 경고 — "Mutating component props is not allowed", "Effects must not run during render", "Hooks must be called at the top level".

## 7. 수동 메모이제이션의 미래

| 제거 대상 | 이유 |
|---|---|
| useMemo(() => expr, [deps]) | 컴파일러 자동 메모, deps 자동 |
| useCallback(fn, [deps]) | 동일 |
| React.memo(Component) | JSX 트리 자체 메모이즈 |

남는 useMemo: 외부 리소스 lifecycle(`new Worker()`), 사용자 의도 제어.

마이그레이션:
```
1. eslint-plugin-react-compiler 도입, 위반 0
2. compilationMode: 'infer' default 로 컴파일러 활성화
3. 1~2 주 dogfooding (DevTools Profiler)
4. 새 코드에서 useMemo/useCallback 작성 중단
5. 기존 메모이제이션 자연 정리
```

## 8. 디버깅

- panicThreshold: 'all_errors' 로 컴파일 실패 명확화
- DevTools "Compiled" 배지
- Profiler 비교 (렌더 횟수)
- bailout reason 콘솔 출력
- 인스턴스 캐시 크기 (n > 50 시 props 분해 검토)

```tsx
import { Profiler } from 'react';
<Profiler id="ProductList" onRender={(id, phase, actualDuration) => {
  console.log(id, phase, actualDuration);
}}>
  <ProductList products={products} filter={filter} onSelect={handleSelect} />
</Profiler>
```

## 9. 운영 trade-off

| 측면 | 수동 | Compiler |
|---|---|---|
| 정확성 | deps 누락 위험 | 컴파일러 추출 |
| 가독성 | useMemo/useCallback 노이즈 | 평범한 함수 |
| 빌드 시간 | 영향 없음 | +5~15% |
| 번들 크기 | 그대로 | +2KB runtime |
| 메모리 | 의도된 자리만 | 캐시 배열 증가 (대개 무시) |
| 도구 | exhaustive-deps lint | react-compiler lint |

벤치 — 평균 리렌더 ~40% 감소, INP 8~15% 개선 (Vercel 사례). 이미 잘 메모된 베이스는 추가 이득 적음.

권고 — 새 프로젝트 default, 기존 프로젝트 점진 옵트인. 컴파일러가 모든 메모이제이션 대체는 아니지만, 90%+ fine-grained 메모이제이션 자동화만으로 큰 개선.

## 참고

- React Docs — React Compiler https://react.dev/learn/react-compiler
- React Conf 2024 — "What's new in React 19" Keynote
- babel-plugin-react-compiler https://www.npmjs.com/package/babel-plugin-react-compiler
- Joe Savona, "React Compiler Deep Dive" (React Labs Blog)
- eslint-plugin-react-compiler https://www.npmjs.com/package/eslint-plugin-react-compiler
