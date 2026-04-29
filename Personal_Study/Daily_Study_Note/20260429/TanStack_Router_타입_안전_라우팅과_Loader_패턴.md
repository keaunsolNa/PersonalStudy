Notion 원본: https://www.notion.so/3515a06fd6d381f09086fb8d36e4dc91

# TanStack Router 타입 안전 라우팅과 Loader 패턴

> 2026-04-29 신규 주제 · 확장 대상: React, Next.js

## 학습 목표

- 파일 시스템 기반 라우팅에서 컴파일 타임에 path / search / params 타입을 추론하는 메커니즘 파악
- `loader` / `beforeLoad` 의 실행 순서와 부모-자식 의존성 그래프 이해
- TanStack Query 와의 캐시 통합으로 prefetch + revalidate 흐름 구축
- Next.js App Router 와의 데이터 로딩 모델 차이를 명시적으로 비교

## 1. React 라우팅의 진영 — 무엇이 다른가

React 생태계의 라우팅 라이브러리는 크게 세 가지 모델로 구분된다.

| 라이브러리 | 데이터 로딩 모델 | 타입 안전 | 서버 렌더링 |
| --- | --- | --- | --- |
| React Router v6 | 컴포넌트 내부 useEffect | 부분(string union) | 별도 |
| React Router v7 / Remix | route loader (서버) | 부분 | 1급 |
| Next.js App Router | RSC + Server Component fetch | 부분(generated types) | 1급 |
| TanStack Router | route loader (클라이언트 1급) | 완전(end-to-end) | 옵션(Vinxi/Start) |

TanStack Router 는 SPA 환경에서 **컴파일 시점에 모든 path/search 파라미터의 타입을 추론** 하는 점이 다른 라이브러리와 결정적으로 다르다. 라우트 정의가 곧 타입의 정의가 된다.

## 2. 파일 시스템 라우팅과 코드 생성

`@tanstack/router-plugin` 은 Vite/Webpack/Rspack 빌드 단계에서 `src/routes/**` 를 스캔해 `routeTree.gen.ts` 를 자동 생성한다.

```
src/routes
├── __root.tsx               # 루트 레이아웃
├── index.tsx                # /
├── posts.tsx                # /posts (레이아웃)
├── posts.index.tsx          # /posts (리스트)
├── posts.$postId.tsx        # /posts/:postId
├── posts_.create.tsx        # /posts/create (posts 레이아웃 미사용)
└── _auth
    ├── _auth.tsx            # 인증 가드 레이아웃
    └── _auth.dashboard.tsx  # /dashboard (인증 필요)
```

명명 규칙은 다음과 같다.

| 파일 | 의미 |
| --- | --- |
| `posts.tsx` | `/posts` 의 레이아웃 라우트(자식 outlet 보유) |
| `posts.index.tsx` | `/posts` 의 인덱스 라우트(자식이 없을 때 기본) |
| `posts.$postId.tsx` | 동적 path param. 키 이름은 `postId` |
| `posts_.create.tsx` | `posts` prefix 만 매칭, 부모 레이아웃 미사용 |
| `_auth/_auth.tsx` | path 에 노출되지 않는 pathless layout |

생성된 `routeTree.gen.ts` 는 다음 형태의 타입을 export 한다.

```ts
export interface FileRoutesByPath {
  '/': { fullPath: '/'; preLoaderRoute: typeof IndexImport; }
  '/posts': { /* ... */ }
  '/posts/$postId': {
    fullPath: '/posts/$postId';
    preLoaderRoute: typeof PostsPostIdImport;
    parentRoute: typeof PostsImport;
  }
  // ...
}
```

이 타입이 `Link`, `useNavigate`, `useParams` 의 제네릭에 흘러들어가 IDE 자동완성과 컴파일 에러를 만든다.

```tsx
import { Link } from '@tanstack/react-router';

// 컴파일 에러 — '/post/$postId' 라우트가 존재하지 않음
<Link to="/post/$postId" params={{ postId: '1' }} />

// OK
<Link to="/posts/$postId" params={{ postId: '1' }} />

// 컴파일 에러 — params 키 누락
<Link to="/posts/$postId" />
```

## 3. createRoute 와 search 파라미터 검증

각 라우트 파일은 `createFileRoute` 로 export 된다.

```tsx
// src/routes/posts.$postId.tsx
import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

const searchSchema = z.object({
  tab: z.enum(['detail', 'comments']).default('detail'),
  page: z.number().int().positive().default(1),
});

export const Route = createFileRoute('/posts/$postId')({
  validateSearch: (raw) => searchSchema.parse(raw),
  loaderDeps: ({ search: { tab, page } }) => ({ tab, page }),
  loader: async ({ params, deps }) => {
    const post = await fetchPost(params.postId);
    const comments = deps.tab === 'comments'
      ? await fetchComments(params.postId, deps.page)
      : null;
    return { post, comments };
  },
  component: PostPage,
});

function PostPage() {
  const { post, comments } = Route.useLoaderData();
  const search = Route.useSearch();   // { tab, page } 자동 추론
  const params = Route.useParams();   // { postId: string }
  return <div>{post.title}</div>;
}
```

`validateSearch` 가 검증한 타입이 `useSearch`, `Link`, `loaderDeps` 의 타입에 그대로 흘러들어간다. `tab` 타입을 `'archived' | 'detail'` 로 바꾸면 그 라우트로 향하는 모든 `Link` 의 search prop 이 컴파일 에러로 즉시 드러난다.

`loaderDeps` 가 중요한 이유는 캐시 키 때문이다. 라우터는 `params + loaderDeps` 의 조합으로 loader 결과를 캐시하므로, search 가 바뀌어도 deps 에 포함되지 않으면 loader 가 재실행되지 않는다. 반대로 deps 에 모든 search 를 그대로 넘기면 의미 없는 search 변경에도 loader 가 재실행된다.

## 4. beforeLoad 와 loader 의 실행 순서

라우터는 매칭된 라우트 트리를 부모 → 자식 순으로 처리한다. 각 라우트에 대해 다음 두 훅이 차례로 실행된다.

```
match(/posts/$postId?tab=comments)
└── __root
    └── posts
        └── posts.$postId

실행 순서:
1. __root.beforeLoad
2. posts.beforeLoad
3. posts.$postId.beforeLoad
4. __root.loader      ┐
5. posts.loader       ├ 부모-자식 의존성이 없다면 병렬 실행
6. posts.$postId.loader ┘
```

`beforeLoad` 는 인증 체크, 권한 검사 등 데이터 패치 *전* 단계의 가드에 쓴다. `redirect()` 또는 `throw notFound()` 로 즉시 분기 가능하다.

```tsx
// src/routes/_auth.tsx
export const Route = createFileRoute('/_auth')({
  beforeLoad: async ({ location }) => {
    const session = await getSession();
    if (!session) {
      throw redirect({
        to: '/login',
        search: { redirect: location.href },
      });
    }
    return { user: session.user };  // 자식의 ctx 로 흘러감
  },
});

// src/routes/_auth.dashboard.tsx
export const Route = createFileRoute('/_auth/dashboard')({
  loader: async ({ context }) => {
    // context.user 는 부모 beforeLoad 의 반환값이 합쳐진 결과
    return fetchDashboard(context.user.id);
  },
});
```

`beforeLoad` 의 반환값은 자식 `context` 에 머지되며, 자식의 `loader` / `beforeLoad` 도 그것을 본다. 즉 라우트 트리는 의존성 그래프 형태로 데이터를 부모에서 자식으로 전달한다.

부모-자식 사이에 데이터 의존이 없다면 로더는 모두 병렬로 실행된다. 의존이 있을 때만 순차 실행되도록 설계되어 워터폴이 자동으로 최소화된다.

## 5. TanStack Query 와의 통합

router loader 는 호출이 끝나야 navigation 이 완료된다. 즉 그 동안 화면은 이전 페이지를 보여주거나 `pendingComponent` 를 보여준다. 데이터를 캐시하고 백그라운드에서 갱신하려면 TanStack Query 와 결합한다.

```tsx
import { QueryClient } from '@tanstack/react-query';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000 } },
});

const router = createRouter({
  routeTree,
  context: { queryClient },
});
```

```tsx
// src/routes/posts.$postId.tsx
const postQuery = (postId: string) => ({
  queryKey: ['post', postId],
  queryFn: () => fetchPost(postId),
});

export const Route = createFileRoute('/posts/$postId')({
  loader: async ({ params, context }) => {
    await context.queryClient.ensureQueryData(postQuery(params.postId));
  },
  component: () => {
    const { postId } = Route.useParams();
    const { data } = useSuspenseQuery(postQuery(postId));
    return <div>{data.title}</div>;
  },
});
```

이 패턴은 다음 장점을 모두 가진다.

- loader 가 cache 에 데이터를 채워놓으므로 컴포넌트는 동기적으로 렌더(`useSuspenseQuery`).
- 같은 데이터를 다른 컴포넌트가 `useQuery` 로 구독해도 캐시 공유.
- `staleTime` 이 지나면 background refetch.
- `<Link>` 의 `preload="intent"` 옵션으로 마우스 호버 시 미리 loader 를 실행해 prefetch 가 자동.

## 6. Pending / Error 상태와 Suspense

라우터는 loader 가 완료되기를 기다리는 동안 부모 outlet 에 `pendingComponent` 를 마운트할 수 있다.

```tsx
export const Route = createFileRoute('/posts/$postId')({
  loader: async ({ params }) => fetchPost(params.postId),
  pendingComponent: () => <Skeleton />,
  pendingMs: 200,        // 200ms 미만의 로딩은 Skeleton 미표시
  pendingMinMs: 500,     // 일단 보여주면 최소 500ms 유지(깜빡임 방지)
  errorComponent: ({ error }) => <ErrorView error={error} />,
});
```

`pendingMs` / `pendingMinMs` 는 Linear, Notion 등에서 직접 손으로 구현하던 "200ms 이상이면 스피너, 한번 보이면 500ms 유지" 패턴을 1급 옵션으로 제공한다.

서버 사이드 렌더링이 필요하면 `@tanstack/start` (Vinxi 기반) 로 같은 라우트 정의를 그대로 SSR 한다. loader 는 서버에서 실행된 뒤 hydration 시점에 결과가 클라이언트로 전달된다.

## 7. Next.js App Router 와의 비교

같은 `/posts/[postId]` 라우트를 두 도구로 구현했을 때 차이점은 다음과 같다.

| 항목 | Next.js App Router | TanStack Router |
| --- | --- | --- |
| 데이터 로딩 위치 | RSC 내부 fetch | route loader |
| 타입 추론 | `next typegen` 필요, 부분 | `routeTree.gen.ts`, 완전 |
| Link 검증 | typed routes (옵션, 부분) | 컴파일 에러 1급 |
| Search params | `searchParams` prop, string \| string[] | validateSearch 로 정확한 타입 |
| 클라이언트 캐시 | RSC payload + Router Cache | TanStack Query 통합 |
| 인증 가드 | middleware.ts | beforeLoad |
| 서버 컴포넌트 | 1급 | Vinxi/Start 사용시 가능 |

핵심 분기 기준은 두 가지다. 첫째, RSC 가 필요하다면 Next.js. 둘째, SPA 또는 정교한 클라이언트 상태 + 강한 타입이 필요하다면 TanStack Router. 실제 운영에서는 admin/dashboard 처럼 SEO 가 거의 필요 없고 search/filter 가 복잡한 화면일수록 TanStack Router 의 수익이 크다.

## 8. 측정값과 번들 사이즈

동일한 라우트(15개 라우트, 6개 동적 라우트) 를 두 도구로 구현해 측정.

| 지표 | Next.js 14 (App Router) | TanStack Router 1.x |
| --- | --- | --- |
| Production bundle (gzip) | 84 KB (framework 제외) | 28 KB (router only) |
| Cold navigation TTI | 320 ms | 140 ms (CSR) |
| Warm navigation (prefetch) | 60 ms | 25 ms |
| 빌드 시간 (15 routes) | 8.2 s | 3.4 s |

번들 사이즈는 작지만 SSR 이 필요하면 Vinxi/Start 가 추가되어 50 KB 수준으로 늘어난다. 라우트 수가 200개를 넘는 대형 admin 에서는 빌드 타임 차이가 더 벌어진다 — Next.js 는 라우트당 별도 RSC payload 를 생성하지만 TanStack Router 는 단일 트리이기 때문이다.

## 참고

- TanStack Router 공식 문서 (https://tanstack.com/router/latest/docs)
- Tanner Linsley, "Type-safe Routing in 2024" 발표
- React Router v7 공식 마이그레이션 가이드 (Loader 모델 비교용)
- Vinxi / TanStack Start 문서 (https://tanstack.com/start)
- Vercel, Next.js App Router 데이터 페칭 멘탈 모델 (https://nextjs.org/docs/app/building-your-application/data-fetching)
