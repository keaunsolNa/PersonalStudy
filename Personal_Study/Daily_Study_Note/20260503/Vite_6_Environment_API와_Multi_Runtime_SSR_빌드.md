Notion 원본: https://www.notion.so/3555a06fd6d3814ea653d56279e911d0

# Vite 6 Environment API — Multi-Runtime SSR 빌드와 모듈 그래프 분리

> 2026-05-03 신규 주제 · 확장 대상: Next.js Partial Prerendering, React Server Components

## 학습 목표

- Vite 5 까지의 client/ssr 이중 빌드 모델의 한계를 이해하고, Vite 6 의 Environment API 가 어떤 추상으로 그것을 일반화했는지 정리한다
- `EnvironmentModuleGraph` 와 `RunnableDevEnvironment` 를 통해 Cloudflare Workers, Edge Runtime, Node.js 같은 멀티 런타임이 단일 dev 서버에서 동시에 동작하는 흐름을 추적한다
- 플러그인이 `applyToEnvironment`, `perEnvironmentPlugin` 으로 환경별 분기를 구현하는 패턴을 배운다
- React Server Components, Astro, Remix 등 메타프레임워크가 이 API 위에 어떻게 매핑되는지 살펴본다

## 1. Vite 5 까지의 한계

Vite 5 는 dev server 가 client 모듈 그래프 하나를 갖고, SSR 빌드는 별도 entry 와 별도 graph (`server: { ssr: true }`) 로 처리했다. 이중 모델로는 다음 케이스를 깨끗이 다루기 어려웠다.

- *3개 이상의 환경*: client + Node SSR + Cloudflare Workers + Edge Runtime
- 환경별 *다른 condition*: `worker` 조건이 켜져야 하는 패키지 vs `node`
- 환경별 *다른 transform*: edge 에서는 특정 polyfill 금지
- *RSC* 같이 server graph 가 client graph 로 직렬화되는 패턴

각 메타프레임워크가 임시방편으로 자체 환경 추상화를 끼웠고, 결과적으로 Vite 의 모듈 그래프와 어긋나는 캐시/HMR 버그가 자주 발생했다. Environment API 는 이 패턴을 코어로 흡수한 결과다.

## 2. Environment API 의 핵심 구조

Vite 6 에서 빌드/dev 의 단위는 **Environment** 다. 각 environment 는 독립된 모듈 그래프, 독립된 plugin pipeline, 독립된 resolver 를 갖는다.

```ts
// vite.config.ts
import { defineConfig } from 'vite'

export default defineConfig({
  environments: {
    client: {
      build: {
        outDir: 'dist/client',
        rollupOptions: { input: 'index.html' },
      },
    },
    ssr: {
      build: {
        outDir: 'dist/ssr',
        ssr: true,
        rollupOptions: { input: 'src/entry-server.ts' },
      },
      resolve: {
        conditions: ['node'],
        externalConditions: ['node'],
      },
    },
    workers: {
      build: {
        outDir: 'dist/workers',
        rollupOptions: { input: 'src/worker.ts' },
      },
      resolve: {
        conditions: ['workerd', 'worker'],
      },
    },
  },
})
```

각 environment 는 다음 객체를 갖는다.

| 객체 | 역할 |
|---|---|
| `EnvironmentModuleGraph` | 환경별 모듈 의존성 그래프, HMR 분리 |
| `RunnableDevEnvironment` | dev 시점에 코드를 실제로 실행시키는 인터페이스 |
| `EnvironmentResolveOptions` | conditions, alias, externals 환경별 |
| `EnvironmentBuildOptions` | rollup 빌드 설정 환경별 |

## 3. RunnableDevEnvironment 의 의미

이 부분이 가장 혁신적이다. dev 서버가 단순히 모듈을 *번들링* 하는 게 아니라 *해당 런타임에서 실제로 실행* 한다. 예를 들어 `workers` environment 의 RunnableDevEnvironment 는 miniflare/workerd 위에서 실행되어 fetch event 를 처리한다.

```ts
// 플러그인 측 코드
{
  name: 'my-runner',
  configureServer(server) {
    const env = server.environments.ssr as RunnableDevEnvironment
    server.middlewares.use(async (req, res) => {
      const mod = await env.runner.import('/src/entry-server.ts')
      const html = await mod.render(req.url)
      res.end(html)
    })
  },
}
```

`env.runner.import` 는 모듈을 *해당 환경의 컨텍스트* 에서 실행한다. Node SSR 환경이라면 Node VM 컨텍스트, Workers 환경이라면 workerd 컨텍스트다. dev/prod 의 동작 차이가 실제 런타임 레벨에서 사라진다.

## 4. Plugin 의 환경 분기

기존 Vite 플러그인은 `apply: 'serve' | 'build'` 로 분기하던 구조였다. Environment API 는 더 세밀한 분기를 제공한다.

```ts
import type { Plugin } from 'vite'

const myPlugin: Plugin = {
  name: 'my-plugin',
  applyToEnvironment(env) {
    return env.name === 'client' || env.name === 'ssr'
  },
  transform(code, id) {
    // this.environment 로 현재 환경 접근
    if (this.environment.name === 'workers') {
      return code.replace(/global\./g, 'globalThis.')
    }
    return null
  },
}
```

`applyToEnvironment` 는 boolean 또는 plugin 배열을 반환할 수 있어, 환경마다 다른 plugin 을 적용할 수도 있다. RSC 같이 server 환경에서만 활성화되어야 하는 플러그인이 정확히 이 형태다.

플러그인 훅 안에서 `this.environment` 로 현재 환경을 안다. 이전에는 `ssr: boolean` 단일 플래그만 있었지만 이제 환경 이름과 메타데이터 전체를 사용 가능하다.

## 5. resolve conditions 의 환경별 분기

`package.json` 의 `exports` 조건 매핑은 환경별로 달라야 한다. Cloudflare Workers 는 `workerd`/`worker` 조건을 켜야 하고, Edge Runtime 은 `edge-light` 조건을, Node 는 `node` 조건을 켠다.

```ts
environments: {
  workers: {
    resolve: {
      conditions: ['workerd', 'worker', 'browser', 'import', 'default'],
      externalConditions: ['workerd', 'worker'],
      noExternal: ['some-cjs-pkg'],     // 강제로 번들에 포함
    },
  },
}
```

`externalConditions` 는 의존성을 외부화할 때 적용할 조건이다 (build 시 노드 모듈을 번들에 포함시키지 않을 때). dev 와 build 가 같은 조건을 보도록 일관성을 맞춘다.

## 6. 모듈 그래프 분리와 HMR

각 environment 는 자체 `ModuleGraph` 를 갖는다. 동일 파일이 두 환경에서 import 되어도 두 환경의 그래프에 *각각* 등록된다.

```
src/util.ts
├── client graph: imported by src/main.ts, transform with browser conditions
└── ssr graph:    imported by src/entry-server.ts, transform with node conditions
```

HMR 도 환경 단위로 분리된다. client 그래프의 모듈을 수정하면 client HMR 만, ssr 그래프 모듈 수정 시 ssr 만 갱신. RSC 처럼 두 그래프가 cross-edge 로 연결된 경우 메타프레임워크가 명시적으로 양쪽 invalidation 을 트리거한다.

```ts
const clientGraph = server.environments.client.moduleGraph
const ssrGraph = server.environments.ssr.moduleGraph
const ssrMod = ssrGraph.getModuleById(id)
if (ssrMod) ssrGraph.invalidateModule(ssrMod)
```

## 7. RSC 매핑 사례

React Server Components 는 server-only graph 와 client graph 를 갖는다. 두 graph 의 boundary 에서 client component 의 reference (`'use client'` 표시) 를 `_payload_/...` 같은 stub 으로 직렬화한다. Vite Environment API 위에 이를 매핑하면:

```ts
environments: {
  client: { /* 일반 client */ },
  rsc: {
    resolve: { conditions: ['react-server', 'node'] },
    // 'use client' 모듈은 외부 reference 로 변환
  },
  ssr: {
    resolve: { conditions: ['node'] },
    // RSC payload 를 받아 HTML 로 stream
  },
}
```

세 environment 가 동시에 살아 있고, dev 서버는 한 요청에 대해 rsc 환경에서 component tree 를 실행 → ssr 환경에서 stream HTML → client 환경 모듈을 hydrate. waku, react-router framework, next-app-router 등이 이 패턴을 동일하게 따른다.

## 8. 빌드 출력과 manifest

`build` 명령은 모든 environment 를 순서대로 빌드한다. CLI:

```bash
vite build                    # 모든 environment
vite build --environment=ssr  # ssr 만
```

각 environment 는 자체 manifest 를 출력한다.

```json
// dist/client/.vite/manifest.json
{
  "src/main.ts": {
    "file": "assets/main-abc123.js",
    "isEntry": true,
    "imports": ["_chunk-def456.js"]
  }
}
```

server side 가 client manifest 를 읽어 hash 된 asset 경로를 HTML 에 주입하는 패턴이다. 환경별로 manifest 가 분리되어 RSC 처럼 client/server reference 를 cross-link 하는 경우에도 정확히 매핑된다.

## 9. 마이그레이션과 backward compatibility

Vite 5 의 `ssr.*` 옵션은 Vite 6 에서 deprecated 되어 자동으로 `environments.ssr.*` 로 변환된다. 단순 SSR 만 쓰는 프로젝트는 코드 변경 없이 6 로 올라간다.

플러그인 작성자는 다음 호환 패턴을 권장한다.

```ts
const plugin: Plugin = {
  name: 'compat-plugin',
  configEnvironment(name, config) {
    // 6.x 환경 단위 설정 가능
  },
  config(config) {
    // 5.x 호환 진입점 — environments 가 없으면 client/ssr 로 추론
  },
}
```

피어 의존성에 `vite: ^5 || ^6` 를 명시하고, `ResolvedConfig.environments` 의 존재 여부로 분기. Vite 7 에서는 5 호환 코드 제거가 예고됐으므로 6 채택 시점에 마이그레이션을 끝내는 것이 권장된다.

## 10. 성능 고려와 trade-off

| 항목 | Vite 5 | Vite 6 (Environment API) |
|---|---|---|
| Dev 서버 메모리 | client+ssr 두 그래프 | n 개 환경 → n 개 그래프 (선형 증가) |
| HMR 정밀도 | client/ssr 분리 | 환경 단위 정밀 분리 |
| Build 시간 | 두 번 빌드 | n 번 빌드 (병렬 가능) |
| RSC 구현 비용 | 메타프레임워크가 직접 graph 관리 | core 가 분리 graph 제공 |
| 프레임워크 결합도 | 높음 | 낮음, 메타프레임워크 cross-compat |
| Cloudflare/Deno 등 멀티 런타임 | 어려움 | 일급 지원 |

환경이 늘어나면 메모리/빌드 시간이 선형 증가한다. 보통 client+ssr 2개로 충분하므로 멀티 런타임이 정말 필요할 때만 환경을 늘린다.

## 11. 결론

Vite 6 Environment API 는 5.x 시절의 client/ssr 이중 모델을 일반화해 *임의 개수의 런타임 환경* 을 dev/build 의 일급 시민으로 만든다. 메타프레임워크 — Astro, Remix, Next, waku, react-router, qwik, SvelteKit, SolidStart — 가 각자 갖던 환경 추상화를 코어가 흡수해 cross-compat 가 개선됐다. 단일 SSR 프로젝트는 무리하게 다환경으로 쪼갤 필요 없이 client+ssr 기본 구성을 그대로 쓰고, 멀티 런타임 (Cloudflare/Edge/Workers) 이 필요할 때 environment 를 추가하는 단계적 도입이 권장된다.

## 12. Cloudflare Workers 통합 사례

`@cloudflare/vite-plugin` 은 Environment API 를 그대로 사용해 Workers 런타임을 dev 시점부터 실제로 띄운다.

```ts
import { defineConfig } from 'vite'
import { cloudflare } from '@cloudflare/vite-plugin'

export default defineConfig({
  plugins: [cloudflare()],
  environments: {
    workers: {
      build: { rollupOptions: { input: 'src/worker.ts' } },
    },
  },
})
```

이 플러그인이 내부에서 하는 일:

1. miniflare 인스턴스를 dev 시점에 spawn
2. `RunnableDevEnvironment` 를 miniflare 의 module loader 와 연결
3. fetch event 를 dev 서버 미들웨어로 라우팅
4. Workers 의 `compatibility_date`, `compatibility_flags` 를 환경 변수로 주입
5. KV/D1/R2 바인딩을 local emulation 으로 dev 시 매핑

기존에는 `wrangler dev` 와 Vite dev 서버를 *분리해서* 띄워야 했지만, Environment API 위에서는 한 dev 서버가 양쪽을 모두 처리한다. HMR 도 Workers 환경에서 그대로 작동한다.

## 13. 메타프레임워크 채택 현황

Vite 6 의 Environment API 가 안정화된 이후 메타프레임워크 채택 현황 (2025~2026 초 기준):

| 프레임워크 | 상태 |
|---|---|
| Astro | 4.x 부터 부분 적용, 5.x 전면 채택 |
| SvelteKit | 2.x 후반부터 채택 |
| Remix → React Router 7 | environments 에 전적으로 의존 |
| qwik-city | 1.x 채택 |
| SolidStart | 1.0 GA 시 채택 |
| Next.js | turbopack 우선이라 부분적 호환 검토 |

Next.js 만 자체 번들러(turbopack) 노선을 가서 Vite Environment API 와 직접 결합되지 않는다. 그 외 메타프레임워크는 거의 모두 Environment API 를 backbone 으로 삼고 있어, "Vite 위에서 메타프레임워크가 동거 가능한 표준" 이 자리 잡았다.

## 14. 디버깅과 트러블슈팅

다환경 dev 서버에서 자주 만나는 함정:

| 증상 | 원인 | 해결 |
|---|---|---|
| 동일 파일이 두 환경에서 다르게 transform | resolve.conditions 차이 | 의도된 동작, 환경별 alias 확인 |
| HMR 이 한쪽에서만 갱신 | 모듈이 다른 환경 그래프에 등록 안 됨 | `environments[name].moduleGraph` 직접 invalidate |
| `runner.import` 타입 오류 | `RunnableDevEnvironment` 가 아닌 환경 | `instanceof RunnableDevEnvironment` 체크 |
| 빌드 시 한 환경만 선택되도록 강제 | `vite build --environment=...` | CI 에서 환경별 분리 step |
| 플러그인이 의도치 않게 모든 환경에 적용 | `applyToEnvironment` 미설정 | 명시 분기 추가 |

`vite build --environment=client` 처럼 환경별 빌드를 CI 에서 분리하면 환경별 캐시가 서로 영향을 주지 않아 빌드가 안정적이다. Turborepo 와 결합해 환경별 task 를 정의하는 패턴도 권장된다.

## 참고

- Vite 6 release notes — Environment API
- Vite Documentation — Environment API guide (`vitejs.dev/guide/api-environment.html`)
- Patak / Hiroshi Ogawa, "Reimagining Vite's API" ViteConf 2024 발표
- Cloudflare Workers Vite plugin (`@cloudflare/vite-plugin`) 소스 코드
- React Server Components — RFC and `react-server` condition specification
- Hiroshi Ogawa, "Vite-Plugin-RSC" 구현 분석 블로그
- Astro 5.0 release notes — Environment API 채택 배경
- React Router 7 documentation — Vite Environment API 통합 가이드
- ViteConf 2024 keynote — "The Future of Vite" (Evan You)
- `vite-plugin-cloudflare` GitHub repo — RunnableDevEnvironment 사용 예시 코드
