Notion 원본: https://www.notion.so/34a5a06fd6d3815fa53ff0a763cc82bd

# Next.js App Router — Server Components vs Client Components 경계 설계

> 2026-04-22 신규 주제 · 확장 대상: Next.js, React (Pages Router → App Router / RSC 심화)

## 학습 목표

- React Server Components(RSC)와 Client Component가 각각 어디서 실행되고 네트워크 경계를 어떻게 넘는지 이해한다
- `"use client"` 경계를 잘 설계해 **클라이언트 JS 번들을 줄이고 초기 렌더 지연을 최소화**한다
- Server Actions, Streaming SSR + Suspense, Partial Prerendering(PPR)을 조합해 데이터 패칭 전략을 짠다
- App Router의 캐시(fetch 캐시, full route cache, request memoization)와 재검증(`revalidateTag`, `revalidatePath`)을 실전에서 안전하게 쓴다

---

## 1. App Router 기본 구조

`app/` 디렉터리는 파일 시스템이 곧 라우팅이다. 주요 파일명 컨벤션:

| 파일 | 역할 |
|---|---|
| `page.tsx` | 해당 라우트의 UI |
| `layout.tsx` | 중첩되는 공통 레이아웃 (상태 유지) |
| `template.tsx` | layout과 비슷하나 네비게이션마다 새로 렌더 |
| `loading.tsx` | Suspense fallback 자동 적용 |
| `error.tsx` | Error Boundary 자동 적용 |
| `not-found.tsx` | `notFound()` 호출 시 렌더 |
| `route.ts` | REST-style API 라우트 (GET/POST/...) |

예를 들어 `app/dashboard/orders/page.tsx`는 `/dashboard/orders` URL에 대응하고, `app/dashboard/layout.tsx`는 `/dashboard` 하위 모든 페이지에 감싸인다. Layout은 네비게이션 중 **state가 유지**되므로 사이드바의 스크롤 위치, 폼 입력 상태가 날아가지 않는다.

Route Group `(group)`은 URL에는 영향을 주지 않으면서 파일을 그룹핑한다. `app/(marketing)/about/page.tsx`는 `/about`이 되고 marketing 그룹 전용 layout을 적용할 수 있다.

Parallel Routes(`@slot`)과 Intercepting Routes(`(..)folder`)는 모달과 대시보드 분할 같은 고급 UI를 구현할 때 유용하지만 학습 난이도가 높다. 초기에는 건너뛰어도 무방.

## 2. 서버 컴포넌트 vs 클라이언트 컴포넌트 — 경계의 물리학

**App Router의 모든 컴포넌트는 기본적으로 서버 컴포넌트(RSC)** 다. 브라우저에 JS가 전송되지 않고, 서버에서만 렌더링되어 HTML + React Flight payload로 스트리밍된다. `useState`, `useEffect`, 브라우저 API(`window`, `document`, 이벤트 핸들러)를 쓸 수 없다. 대신 DB/파일 시스템/서버 시크릿에 **직접 접근**할 수 있다.

**`"use client"`** 지시자가 파일 최상단에 있으면 그 파일과 그 파일이 import하는 모든 모듈이 클라이언트로 번들링된다. 여기서부터는 전통 React와 같다. Hooks, 이벤트 핸들러, 브라우저 API 전부 사용 가능하지만 DB 접근은 불가.

```tsx
// app/dashboard/page.tsx  — 서버 컴포넌트 (기본)
import { db } from "@/lib/db";
import { Chart } from "./chart";  // 클라이언트 컴포넌트

export default async function Dashboard() {
  const data = await db.query("SELECT ...");  // 서버에서 직접 DB 접근
  return (
    <main>
      <h1>Dashboard</h1>
      <Chart data={data} />  {/* props로 전달 → 직렬화됨 */}
    </main>
  );
}
```

```tsx
// app/dashboard/chart.tsx
"use client";
import { useState } from "react";
export function Chart({ data }: { data: DataPoint[] }) {
  const [hovered, setHovered] = useState<number | null>(null);
  // 상호작용 가능
  return /* ... */;
}
```

**직렬화 경계**. 서버 → 클라이언트 props로 전달할 수 있는 것은 JSON + 몇 가지 React 확장 타입(Promise, Date)에 한정된다. **함수, 클래스 인스턴스, Map/Set, Symbol**은 직렬화 불가. 실수로 `onClick={() => ...}` 같은 콜백을 서버 컴포넌트 prop으로 넘기면 런타임 에러.

**번들 크기 관점**. `"use client"`는 "이 컴포넌트와 그 하위 전체"가 아니라 **"이 컴포넌트와 그 모듈이 import하는 것 전체"**를 번들에 포함시킨다. 즉 클라 컴포넌트가 서버 컴포넌트를 자식으로 렌더링하려면 `children` prop으로 받아야 한다. 이를 **composition pattern**이라고 한다.

```tsx
// ❌ 안티패턴
"use client";
import { ServerComp } from "./server-comp";  // 서버 컴포넌트를 직접 import
export function ClientWrapper() {
  return <ServerComp />;  // 동작 안 함
}

// ✅ composition
"use client";
export function ClientWrapper({ children }: { children: React.ReactNode }) {
  return <div className="fancy-wrapper">{children}</div>;
}
// 부모(서버)에서 <ClientWrapper><ServerComp /></ClientWrapper>
```

## 3. 데이터 패칭 — fetch의 새로운 의미

App Router는 전역 `fetch`를 패치해 네 가지 동작을 통합했다.

```tsx
// 1. 정적 생성 (기본) - 빌드 타임 또는 첫 요청 시 한 번 패치, 영구 캐시
const data = await fetch("https://api.example.com/posts");

// 2. 매 요청마다 패치 (동적)
const data = await fetch("https://api.example.com/posts", { cache: "no-store" });

// 3. 시간 기반 재검증 (ISR)
const data = await fetch("https://api.example.com/posts", {
  next: { revalidate: 60 }  // 60초마다 백그라운드 갱신
});

// 4. 태그 기반 재검증 (on-demand)
const data = await fetch("https://api.example.com/posts", {
  next: { tags: ["posts"] }
});
// 나중에 revalidateTag("posts") 호출 시 무효화
```

같은 URL로 같은 요청 안에서 여러 번 `fetch`를 호출해도 React는 자동으로 **deduplication** 해준다(request memoization). 레이아웃과 페이지에서 같은 사용자 정보를 각자 fetch해도 HTTP 요청은 한 번만 나간다.

**직접 DB 접근 vs API Route**. Server Component 안에서 Prisma/Drizzle로 DB를 직접 때릴 수 있다. 모노레포의 웹앱이면 이게 가장 단순하고 빠르다. 반면 DB가 별도 백엔드 팀 소유면 기존 REST/GraphQL API를 `fetch`로 호출하는 게 조직적으로 맞다. 결정은 아키텍처 관점에서 내린다.

**캐시 디버깅**. `next build` 출력을 보면 각 라우트 옆에 ○(static), λ(dynamic), ƒ(dynamic), ● (SSG with generateStaticParams) 기호가 찍힌다. 의도한 것과 다르면 캐시 옵션을 재검토한다. 많이 실수하는 케이스: `headers()`, `cookies()`, `searchParams` 같은 동적 API 한 번만 호출해도 해당 라우트가 **dynamic으로 자동 승격**된다.

## 4. Streaming SSR과 Suspense

데이터 페칭이 느릴 때 페이지 전체가 기다리는 대신, **완성된 부분부터 HTML 청크로 스트리밍**할 수 있다. Suspense 경계가 "여기는 기다릴 수 있음, 로딩 UI 대신 보여줘"를 선언한다.

```tsx
// app/product/[id]/page.tsx
import { Suspense } from "react";

export default function ProductPage({ params }: { params: { id: string } }) {
  return (
    <article>
      <ProductHeader id={params.id} />  {/* 빠름, 즉시 렌더 */}

      <Suspense fallback={<ReviewsSkeleton />}>
        <Reviews productId={params.id} />  {/* 느림, 나중에 스트리밍 */}
      </Suspense>

      <Suspense fallback={<RelatedSkeleton />}>
        <RelatedProducts productId={params.id} />  {/* 독립적으로 스트리밍 */}
      </Suspense>
    </article>
  );
}

async function Reviews({ productId }: { productId: string }) {
  const reviews = await fetch(`/api/reviews/${productId}`).then(r => r.json());
  return <ReviewList reviews={reviews} />;
}
```

첫 번째 HTML 청크에 헤더와 스켈레톤이 즉시 전송되고, Reviews와 RelatedProducts는 **병렬로** 로딩되며 준비되는 대로 스트리밍된다. **TTFB와 FCP는 급격히 개선**되지만 LCP(대표 이미지가 Reviews 안에 있다면)는 오히려 늦어질 수 있으니 무엇이 Above-the-fold인지 기준으로 경계를 긋는다.

`loading.tsx`는 page.tsx 전체를 자동으로 Suspense로 감싸는 문법 설탕이다. 세밀한 제어가 필요하면 명시적 `<Suspense>`.

## 5. Server Actions — 폼 전송의 재발견

Server Action은 `"use server"` 지시자를 붙인 함수로, **클라이언트에서 호출해도 실제로는 서버에서 실행**된다. 전통적인 REST 핸들러 없이 form action에 직접 바인딩할 수 있다.

```tsx
// app/posts/new/page.tsx
import { db } from "@/lib/db";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";

async function createPost(formData: FormData) {
  "use server";
  const title = formData.get("title") as string;
  const content = formData.get("content") as string;
  if (!title) throw new Error("Title required");

  const post = await db.post.create({ data: { title, content } });
  revalidatePath("/posts");  // 목록 페이지 캐시 무효화
  redirect(`/posts/${post.id}`);
}

export default function NewPostPage() {
  return (
    <form action={createPost}>
      <input name="title" />
      <textarea name="content" />
      <button type="submit">Save</button>
    </form>
  );
}
```

**Progressive Enhancement**. JavaScript가 꺼져 있어도 form은 일반 HTML form으로 제출돼 동작한다. 이게 Server Action의 철학적 핵심.

**`useActionState` / `useOptimistic`**. 상태 업데이트와 낙관적 UI를 위한 Hooks.

```tsx
"use client";
import { useActionState } from "react";
import { createPost } from "./actions";

export function PostForm() {
  const [state, formAction, isPending] = useActionState(createPost, { error: null });
  return (
    <form action={formAction}>
      <input name="title" />
      {state.error && <p className="error">{state.error}</p>}
      <button disabled={isPending}>{isPending ? "Saving..." : "Save"}</button>
    </form>
  );
}
```

**보안 주의**. Server Action은 자동으로 CSRF 보호(Origin 검증)를 한다. 하지만 **인증/권한 체크는 함수 본문에서 반드시 직접** 수행해야 한다. URL 경로가 드러나지 않는다고 해도 엔드포인트는 존재하며 누구나 호출할 수 있다.

```tsx
async function deletePost(id: string) {
  "use server";
  const session = await getServerSession();
  if (!session?.user) throw new Error("Unauthorized");

  const post = await db.post.findUnique({ where: { id } });
  if (post.authorId !== session.user.id) throw new Error("Forbidden");

  await db.post.delete({ where: { id } });
  revalidatePath("/posts");
}
```

## 6. Partial Prerendering(PPR)

PPR은 한 페이지 안에서 **정적 부분과 동적 부분을 동시에** 서빙한다. 정적 셸이 CDN 엣지에서 즉시 반환되고, 동적 "홀(hole)"은 서버가 스트리밍으로 채운다.

```tsx
export const experimental_ppr = true;

export default function ProductPage() {
  return (
    <article>
      <StaticHeader />  {/* 빌드 타임 프리렌더 */}
      <StaticDescription />

      <Suspense fallback={<Skeleton />}>
        <PersonalizedPrice />  {/* 요청 시 cookies() 기반 동적 */}
      </Suspense>
    </article>
  );
}
```

ISR(Incremental Static Regeneration)은 페이지 **전체를 통째로** 재생성하는 반면, PPR은 **같은 응답 안에서 섞는다**. 아직 실험 기능(Next.js 15 기준)이라 production 도입은 신중해야 하지만, 방향성은 App Router의 미래다.

## 7. 인증과 세션 처리

App Router의 서버 API.

```tsx
import { cookies, headers } from "next/headers";

export default async function Page() {
  const cookieStore = await cookies();
  const token = cookieStore.get("session")?.value;
  const userAgent = (await headers()).get("user-agent");
  // ...
}
```

**Middleware에서 인증 체크**는 모든 요청이 거쳐 가므로 light한 검증만 한다. JWT 서명 검증까지 하면 Edge Runtime 비용이 커지므로, Middleware는 쿠키 존재 여부만 보고 리다이렉트하고 실제 검증은 layout 또는 page에서 한다.

```tsx
// middleware.ts
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

export function middleware(req: NextRequest) {
  const hasSession = req.cookies.has("session");
  if (!hasSession && req.nextUrl.pathname.startsWith("/dashboard")) {
    return NextResponse.redirect(new URL("/login", req.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/dashboard/:path*"] };
```

**Auth.js v5** 또는 **iron-session**이 App Router와 잘 맞는 세션 라이브러리다. Auth.js는 OAuth 프로바이더 통합이 강하고, iron-session은 자체 세션 쿠키만 다룬다.

## 8. 캐시 계층과 재검증

App Router는 네 층의 캐시를 가진다.

1. **Request Memoization**: 한 요청 안에서 같은 `fetch`의 deduplication. 자동.
2. **Data Cache**: `fetch` 응답 저장. `revalidate` 옵션으로 수명 설정.
3. **Full Route Cache**: 렌더된 HTML + RSC payload. `revalidate` 또는 `revalidateTag` 또는 `revalidatePath`로 무효화.
4. **Router Cache**: 클라이언트 사이드 내비게이션용 in-memory 캐시. 앞뒤로 가기가 빠른 이유.

**`revalidateTag`** 가 가장 세밀한 제어를 준다. 상품 하나가 변경됐을 때 상품 목록과 해당 상품 상세만 재생성하려면:

```tsx
// 목록과 상세에서 같은 태그 사용
const products = await fetch("/api/products", { next: { tags: ["products"] } });
const product = await fetch(`/api/products/${id}`, { next: { tags: ["products", `product:${id}`] } });

// 업데이트 Server Action에서
async function updateProduct(id: string, data: any) {
  "use server";
  await db.product.update({ where: { id }, data });
  revalidateTag(`product:${id}`);  // 해당 상세만
  // 또는 revalidateTag("products")로 목록+전부
}
```

**`dynamic = 'force-dynamic'`** 은 Route Segment 레벨에서 모든 캐시를 끈다. 디버깅에는 유용하지만 운영 배포에 섞이면 서버 비용이 폭증한다. 프로덕션 PR 리뷰에서 이 옵션을 찾으면 반드시 근거를 물어야 한다.

## 9. 성능 측정과 배포

**Bundle Analyzer**로 클라이언트 번들을 감시한다.

```js
// next.config.js
const withBundleAnalyzer = require('@next/bundle-analyzer')({ enabled: process.env.ANALYZE === 'true' });
module.exports = withBundleAnalyzer({});
```

`ANALYZE=true npm run build`로 돌리면 client/server 번들 map이 브라우저에 뜬다. 500KB 넘는 단일 라이브러리가 있으면 dynamic import로 분리한다.

```tsx
import dynamic from "next/dynamic";
const HeavyChart = dynamic(() => import("./heavy-chart"), {
  ssr: false,     // 서버에서 렌더 안 함
  loading: () => <p>Loading...</p>,
});
```

**Core Web Vitals**. Vercel에 배포하면 자동 수집되지만, 자체 호스팅이면 `web-vitals` 패키지로 Google Analytics/Datadog에 보낸다.

```tsx
"use client";
import { useReportWebVitals } from "next/web-vitals";
export function WebVitals() {
  useReportWebVitals(metric => {
    navigator.sendBeacon("/api/vitals", JSON.stringify(metric));
  });
  return null;
}
```

**배포 옵션**. Vercel은 Edge 캐시·이미지 최적화·스트리밍이 기본 작동해 가장 편하다. 자체 호스팅은 `next build && next start`로 Node.js 서버를 돌리거나, `output: 'standalone'` 모드로 Dockerfile을 쓴다. 이미지 최적화는 별도 CDN(Cloudflare Images, Imgix) 연동이 필요. Cold start가 문제면 서버리스 대신 always-on 컨테이너(ECS Fargate, Cloud Run)가 안전하다.

---

## 참고

- 기학습 연계: [Next.js](./Next.js.md), [React](./React.md), [Javascript](./Javascript.md)
- [Next.js Docs — App Router](https://nextjs.org/docs/app)
- [Next.js Docs — Caching](https://nextjs.org/docs/app/building-your-application/caching)
- [React Docs — Server Components](https://react.dev/reference/rsc/server-components)
- [Vercel Blog — Partial Prerendering](https://vercel.com/blog/partial-prerendering-with-next-js-creating-a-new-default-rendering-model)
- [Auth.js v5 Docs](https://authjs.dev/)
