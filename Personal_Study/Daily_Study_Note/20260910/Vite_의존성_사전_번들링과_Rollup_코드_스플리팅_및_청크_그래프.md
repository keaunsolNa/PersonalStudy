Notion 원본: https://www.notion.so/3d75a06fd6d38178a2a0c3444f2f7182

# Vite 의존성 사전 번들링과 Rollup 코드 스플리팅 및 청크 그래프

> 2026-09-10 신규 주제 · 확장 대상: React

## 학습 목표

- Vite 개발 서버의 요청 변환 경로와 사전 번들링 캐시 동작을 추적한다.
- Rollup 의 트리 셰이킹·청크 컬러링 규칙에 맞춰 `manualChunks` 를 설계한다.
- React 라우트 스플리팅에서 워터폴과 캐시 무효화 연쇄를 제어한다.
- 번들 문제를 추측이 아니라 측정 절차로 진단한다.

## 1. 개발 서버의 구조적 차이

webpack dev server 는 요청을 받기 전에 일을 끝내둔다. 진입점에서 모듈 그래프를 전부 순회해 트랜스파일하고 번들로 합쳐 메모리에 올려두면 브라우저는 완성품을 받는다. 콜드 스타트가 규모에 선형 비례한다.

Vite 는 순서를 뒤집는다. 브라우저에 `<script type="module">` 하나만 주고 나머지는 네이티브 ESM 로더가 `import` 를 따라가며 요청하게 둔다. 서버는 요청이 들어온 파일만 그 자리에서 변환하며, 핵심은 **import 경로 재작성**이다. 브라우저는 `import React from 'react'` 같은 bare specifier 를 해석하지 못하므로 소스를 파싱해 절대 경로로 바꿔 내려보낸다.

```js
// 요청한 /src/App.tsx 에 대한 응답(개념)
import React from "/node_modules/.vite/deps/react.js?v=8f3a1c2d";
import { Button } from "/src/ui/Button.tsx";
import "/@fs/D:/ws/shared-ui/theme.css";
```

`/@fs/` 는 루트 바깥 파일(모노레포 형제 패키지, 링크된 로컬 의존성)에 붙으며 `server.fs.allow` 에 없으면 403 이다. `/@id/` 는 플러그인이 만든 가상 모듈처럼 실체 없는 id 를 URL 로 실어 나른다.

콜드 스타트 비용은 사라진 게 아니라 **이동**했다. 기동 시점의 전체 번들링이 첫 요청의 온디맨드 변환과 사전 번들링으로 분산됐을 뿐이어서, 규모가 커지면 번들러 방식은 기동 시간이, ESM 방식은 첫 화면 요청 수가 늘어난다.

## 2. 의존성 사전 번들링

`node_modules` 는 소스와 성격이 다르다. 거의 안 바뀌고, 양이 많고, 형식이 제각각이라 Vite 는 이 영역만 esbuild 로 미리 번들링한다.

첫째 이유는 **CJS/UMD → ESM 호환**이다. 브라우저 ESM 로더는 `module.exports` 를 모르는데 아직 CJS 로만 배포되는 패키지가 있다. 사전 번들링은 이를 유효한 ESM 으로 바꾸고 named export 를 정적으로 추출한다. 둘째는 **요청 폭증 방지**다. 배럴 파일로 된 아이콘·유틸 패키지는 하나만 import 해도 수백~수천 개 파일이 딸려오지만, 합치면 요청 수가 1이 된다.

결과물은 `node_modules/.vite/deps` 에 쌓이고 `_metadata.json`(입력 해시, 파일 매핑, 브라우저 해시)과 `package.json`(`{"type":"module"}`)이 함께 놓인다. 재작성된 URL 뒤의 `?v=` 쿼리가 이 해시이고, 브라우저는 이 값 기준으로 강한 캐시를 걸어 재요청 자체를 안 한다. 캐시는 lockfile, `patches` 디렉터리, `package.json` 의존성, `optimizeDeps`·`resolve.alias` 설정이 바뀌면 무효화되고, 강제 재생성은 `vite --force` 다.

```ts
// vite.config.ts
export default defineConfig({
  optimizeDeps: {
    include: ["react-hook-form"],        // 스캔에 안 걸리는 조건부 의존성
    exclude: ["@my-org/design-system"],  // 소스가 자주 바뀌는 로컬 링크 패키지
  },
});
```

개발 중 "new dependencies optimized ... reloading" 로그와 함께 전체 새로고침이 걸리는 증상은 초기 스캔이 놓친 의존성이 나중에 발견되는 것이 원인이고, 해결은 `include` 에 명시하는 것이다. 반대로 모노레포 내부 소스 패키지를 `include` 에 넣으면 스냅샷이 고정돼 수정이 반영되지 않으므로 `exclude` 가 맞다.

## 3. HMR 경계

Vite 는 요청을 처리하며 모듈 그래프를 유지한다. 파일이 저장되면 해당 노드를 무효화하고 importers 방향으로 올라가며 **HMR 경계**, 즉 `import.meta.hot.accept` 로 변경을 받아들이겠다고 선언한 모듈을 찾는다. 경계를 만나면 멈추고 그 모듈만 교체하며, entry 까지 못 만나면 전체 새로고침으로 폴백한다.

```ts
// self-accepting 모듈
export const config = { theme: "dark" };

if (import.meta.hot) {
  import.meta.hot.accept((newModule) => {
    if (!newModule) return;         // 새 모듈 평가 중 에러 → 폴백
    applyConfig(newModule.config);
  });
  import.meta.hot.dispose(() => teardown()); // 교체 전 정리
}
```

`import.meta.hot.invalidate()` 는 "이 변경은 처리 못 하겠다"고 선언해 전파를 위로 다시 밀어 올린다. React 에서는 `@vitejs/plugin-react` 가 컴포넌트 모듈을 자동 self-accepting 으로 만드는데, 상태가 유지되려면 리렌더 간 **컴포넌트 동일성**을 추적할 수 있어야 한다.

- **익명 export**: `export default () => <Page />` 는 이름이 없어 추적되지 않는다. 이름 붙인 함수를 export 한다.
- **HOC 로 감싼 export**: `export default withAuth(Page)` 는 평가마다 새 컴포넌트가 생겨 재마운트된다.
- **컴포넌트 외 값 혼재**: 컴포넌트와 다른 값을 함께 export 하면 안전 판단이 안 돼 전체 새로고침이 된다.
- **소문자로 시작하는 이름**: 컴포넌트로 인식되지 않는다.

파일당 컴포넌트 하나 원칙은 파일 수를 늘리지만 경계가 좁아져 피드백이 빨라지고, 배럴 파일에 재export 를 몰면 무효화가 크게 번진다.

## 4. 프로덕션 빌드에서 Rollup 이 하는 일

`vite build` 는 개발 서버와 전혀 다른 경로다. Rollup 이 entry 부터 모듈 그래프를 완전히 구성하고 최적화한다. **트리 셰이킹**은 ESM 의 정적 구조에 의존한다. `import`/`export` 는 최상위에만 오고 이름이 정적으로 결정되므로 어떤 export 가 쓰이는지 실행 없이 계산된다. 문제는 "안 쓰이는 것"과 "지워도 되는 것"이 다르다는 점이다. 최상위 부수 효과(전역 등록, 폴리필, CSS import) 때문에 확신이 없으면 남긴다.

```jsonc
{ "sideEffects": false }                          // 부수 효과 없음
{ "sideEffects": ["*.css", "./src/polyfill.js"] } // 있는 파일만 열거
```

```js
const icon = /*#__PURE__*/ createIcon("check"); // 결과 미사용 시 제거 가능
```

`sideEffects` 는 `@rollup/plugin-node-resolve` 가 읽어 **모듈 단위** 제거를, `/*#__PURE__*/` 는 **함수 호출 단위** 제거를 허용한다. 라이브러리라면 둘 다 챙겨야 소비자 번들이 줄어든다. **scope hoisting** 은 모듈들을 한 스코프로 끌어올려 함수 래퍼 없이 이어 붙이는 것으로, 크기가 줄고 엔진 인라이닝도 잘 먹는다.

CommonJS 는 이 모두를 방해한다. `module.exports` 는 런타임에 변형 가능한 객체고 `require` 는 조건문 안에 올 수 있으며 인자가 변수일 수도 있다. export 목록을 정적으로 확정할 수 없으니 `@rollup/plugin-commonjs` 가 감싸도 전체를 남기기 쉽다. 대응은 ESM 빌드를 제공하는 패키지를 고르는 것이다(`lodash` → `lodash-es`).

## 5. 청크 그래프 생성 알고리즘

Rollup 의 청크 분할은 옵션이 아니라 그래프 성질에서 나온다. 청크가 새로 시작되는 지점은 **entry** 와 **동적 import 대상** 둘뿐이다. 이들을 각각 하나의 "색"으로 보고 모든 모듈에 자기를 도달시킨 색의 집합을 기록한 뒤, **같은 색 집합끼리 한 청크로 묶는다**. entry A, B 가 `shared.ts` 를 둘 다 import 하면 색 집합이 `{A, B}` 라 별도 청크가 되고, 중복 없이 공유 청크가 자동 생성된다.

```ts
build: {
  rollupOptions: {
    output: {
      manualChunks(id) {
        if (!id.includes("node_modules")) return;
        if (/[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/.test(id)) {
          return "react-vendor";
        }
        if (/echarts|zrender/.test(id)) return "charts";
      },
    },
  },
}
```

함정이 둘이다. 첫째는 **순환 청크 의존**이다. 한 패키지의 일부만 다른 청크로 빼면 A 가 B 를, B 가 다시 A 를 import 하는 형태가 생긴다. ESM 순환은 허용되지만 초기화 순서가 엉켜 `Cannot access 'X' before initialization` 으로 터진다. 결합된 패키지는 한 그룹으로 묶어야 한다(`scheduler` 를 함께 넣은 이유).

둘째는 **중복**이다. 강제 배치한 모듈이 색 집합상 다른 청크에도 필요하면 양쪽에 복제된다. 기준은 "이 모듈이 항상 함께 로드되는가"여야 하고, 확신이 없으면 자동 계산에 맡긴다. `manualChunks` 는 크기 최적화가 아니라 캐시 정책 표현 수단이다.

## 6. 코드 스플리팅과 프리로드

`import()` 를 만나면 Rollup 은 그 지점을 새 색으로 등록해 별도 청크를 만든다. 문제는 **워터폴**이다. 라우트 청크를 받고 → 파싱하고 → 그 안의 정적 import 를 발견하고 → 다시 요청하는 직렬 구조가 되면 지연이 누적된다. Vite 는 `import()` 를 `__vitePreload` 헬퍼로 감싸 이를 막는다. 빌드 시점에 각 동적 청크의 하위 청크 목록을 알고 있으므로, 대상을 가져오기 전에 의존 청크들에 `<link rel="modulepreload">` 를 주입해 왕복을 한 번의 병렬 요청으로 접는다. 정적 entry 의 직접 의존 청크는 HTML 에 미리 박힌다(`build.modulePreload` 로 조정).

```tsx
import { lazy, Suspense } from "react";
import { createBrowserRouter, RouterProvider } from "react-router-dom";

const Dashboard = lazy(() => import("./routes/Dashboard"));
const Report = lazy(() => import("./routes/Report"));

const wrap = (node: React.ReactNode) => (
  <Suspense fallback={<RouteSkeleton />}>{node}</Suspense>
);

const router = createBrowserRouter([
  { path: "/", element: wrap(<Dashboard />) },
  { path: "/report", element: wrap(<Report />) },
]);

export default function App() {
  return <RouterProvider router={router} />;
}
```

링크 hover 시 `import("./routes/Report")` 를 미리 호출하는 프리페치를 붙이면 클릭 지연이 거의 사라진다. 같은 모듈의 `import()` 는 캐시되므로 중복 요청도 없다. trade-off 는 가지 않을 경로까지 받는 대역폭 낭비이므로 전환 확률이 높은 링크에만 적용한다. Next.js 는 이 프리페치를 `<Link>` 가 기본 제공한다는 점이 다르다.

## 7. 캐시 전략

프로덕션 파일명에는 콘텐츠 해시가 들어간다. 내용이 같으면 이름도 같아 CDN·브라우저 캐시가 그대로 재사용된다.

```ts
build: {
  rollupOptions: {
    output: { chunkFileNames: "assets/[name]-[hash].js" },
  },
}
```

핵심은 **연쇄 무효화**다. 청크 X 가 바뀌면 해시가 바뀌고, X 를 import 하는 Y 는 소스가 안 바뀌었어도 내부 import 경로 문자열이 달라져 해시가 바뀐다. 전파는 entry 까지 올라간다. "잘게 쪼개면 캐시 적중률이 오른다"가 절반만 맞는 이유다.

그래서 vendor 분리 기준은 "라이브러리냐 소스냐"가 아니라 **변경 빈도**다. 배포마다 바뀌는 앱 코드와 분기에 한 번 올릴까 말까 한 React 는 캐시 수명이 달라 분리 가치가 있지만, 매주 올리는 사내 디자인 시스템을 vendor 로 묶으면 이득이 사라진다.

| 분리 후보 | 변경 빈도 | 판단 |
| --- | --- | --- |
| react, react-dom, scheduler | 매우 낮음 | 단일 vendor 청크로 분리 |
| 차트·에디터 등 대형 단일 용도 | 낮음 | 동적 import 로 라우트 격리 |
| 사내 UI 패키지(주간 릴리스) | 높음 | 앱 청크에 남김 |
| 라우트별 화면 코드 | 매우 높음 | 라우트 청크(자동 분리) |

## 8. 번들 진단

수치는 추정하지 말고 측정한다. 먼저 `rollup-plugin-visualizer` 로 treemap 을 뽑아 분포를 본다(`gzipSize` 를 켜야 전송량 기준이다). 의심 모듈이 어느 청크에 왜 들어갔는지는 5절의 색 집합으로 역추적하고, 무엇이 끌어왔는지는 `build.sourcemap: true` 로 원본을 확인해 import 체인을 찾는다. alias 오적용이나 중복 설치가 의심되면 `vite build --debug` 로 로그를 켠다.

```ts
import { visualizer } from "rollup-plugin-visualizer";

plugins: [react(), visualizer({ filename: "stats.html", gzipSize: true })],
build: {
  chunkSizeWarningLimit: 600, // 경고 임계값(KB) — 목표치를 팀 합의로 명시
}
```

개선 축은 셋이다. **대체 가능한 대형 의존성**: `moment` 는 로케일 전체를 끌어오므로 `dayjs` 나 `date-fns`(함수 단위 import) 로 교체가 자주 통하지만, 배럴 경유 import 는 이점이 줄 수 있어 treemap 으로 확인한다. **중복 설치**: 같은 패키지의 다른 메이저가 둘 다 들어가 있으면 lockfile 을 정리하거나 `resolve.dedupe` 를 쓴다. **타깃**: `build.target` 이 필요 이상으로 낮으면 헬퍼가 커진다.

측정은 **같은 조건의 전후 비교**로 한다. 캐시를 지우고, 동일 커밋에 변경 하나만 적용하고, gzip 바이트와 초기 로드 청크 수를 함께 기록한다. 총 크기만 보면 총합을 늘리면서 초기 로드는 줄인 개선을 놓친다.

## 9. Rolldown 전환과 trade-off

지금까지의 구조에는 근본적 이질감이 있다. **개발은 esbuild + 네이티브 ESM, 프로덕션은 Rollup** 이라는 이원화다. 두 도구는 CJS 상호운용과 부수 효과 판단이 같지 않아 "개발에선 되는데 빌드하면 깨진다"는 버그가 여기서 나온다. Rolldown 은 이를 없애려는 시도로, Rust 로 구현하면서 Rollup 플러그인 호환을 목표로 하고 사전 번들링과 프로덕션 번들링을 같은 엔진으로 처리하려 한다.

```jsonc
// package.json — 별도 브랜치에서 시험
{ "overrides": { "vite": "npm:rolldown-vite@latest" } }
```

Next.js 의 Turbopack 도 문제 인식은 같다. Rust 기반 단일 엔진으로 개발과 빌드를 통합하고 증분 캐싱으로 재빌드를 줄인다. 차이는 위치다. Turbopack 은 Next.js 에 결합된 내장 도구고, Rolldown 은 Vite 생태계 전반과 라이브러리 빌드까지 겨냥한 범용 번들러다. 기존 플러그인 자산을 그대로 쓸 수 있느냐가 가장 큰 갈림길이다.

- **빌드 시간이 실제 병목인가.** 빌드가 1~2분이면 기대 이득이 작고, 병목이 CI 대기나 테스트면 체감이 없다.
- **플러그인 의존도가 높은가.** 자체 Rollup 플러그인에 크게 기대면 호환성 검증 비용이 이득을 넘길 수 있다.
- **동작 차이를 흡수할 여유가 있는가.** 청크 구성과 파일명이 달라지면 캐시 전략을 다시 세워야 한다.

보수적 결론은 이렇다. 프로덕션 파이프라인은 당분간 안정된 조합을 유지하되, 별도 브랜치에서 Rolldown 빌드를 돌려 산출물 크기와 청크 구성 차이를 측정해 둔다. 그러면 기본값이 바뀌는 시점에 전환 비용이 이미 알려진 값이 된다.

## 참고

- [Vite — Dependency Pre-Bundling](https://vite.dev/guide/dep-pre-bundling)
- [Vite — Why Vite](https://vite.dev/guide/why)
- [Vite — HMR API (`import.meta.hot`)](https://vite.dev/guide/api-hmr)
- [Vite — Building for Production](https://vite.dev/guide/build)
- [Vite — Build Options](https://vite.dev/config/build-options)
- [Vite — Dep Optimization Options](https://vite.dev/config/dep-optimization-options)
- [Rollup — `treeshake` options](https://rollupjs.org/configuration-options/#treeshake)
- [Rollup — `output.manualChunks`](https://rollupjs.org/configuration-options/#output-manualchunks)
- [Rolldown — Introduction](https://rolldown.rs/guide/)
