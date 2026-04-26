Notion 원본: https://www.notion.so/34e5a06fd6d381e0afafc169164c62e2

# Next.js Partial Prerendering(PPR) 동작 원리

> 2026-04-26 신규 주제 · 확장 대상: Next.js (App Router 학습됨), React (Suspense 학습됨)

## 학습 목표

- SSR / SSG / ISR 모델의 한계를 정량적으로 정리하고 PPR이 풀려는 문제 정의를 명확히 한다
- Static Shell과 Dynamic Hole이 빌드 시점/런타임에 어떻게 분리·결합되는지 보인다
- `Suspense` 경계가 dynamic hole의 경계임을 코드로 증명한다
- `cookies()`, `headers()`, `unstable_noStore()` 호출이 prerender 결과에 미치는 영향을 검증한다

---

## 1. 기존 렌더링 모델의 빈 자리

App Router 학습 단계에서 SSR / SSG / ISR을 익혔다면, 각 모델이 한 가지 trade-off를 강제한다는 점을 이미 안다.

| 모델 | TTFB | Personalization | Cache 효율 |
|---|---|---|---|
| SSG | 매우 빠름 (CDN) | 불가능 | 100% CDN 적중 |
| ISR | 빠름 | 어려움 | 90%+ |
| SSR | 보통 | 모두 가능 | 0% (페이지 전체 동적) |

문제는 한 페이지가 두 종류의 콘텐츠를 동시에 가질 때 발생한다. 예를 들어 이커머스 상품 상세 페이지는 상품 설명·리뷰는 정적이고, 사용자별 가격·재고·장바구니 상태는 동적이다. 기존 모델은 페이지 전체를 둘 중 하나로 분류하라고 강요한다. SSR을 택하면 정적 부분도 매번 렌더링된다.

PPR은 페이지 단위가 아니라 **컴포넌트 단위**로 정적/동적을 섞는다. 정적 부분은 빌드 시점에 prerender 되어 CDN에서 즉시 제공되고, 동적 부분은 streaming으로 채워진다.

## 2. Static Shell과 Dynamic Hole

PPR이 적용된 페이지는 빌드 시점에 다음과 같은 결과물을 만든다.

```
페이지 빌드 산출물
  ├── shell.html          - Suspense fallback이 포함된 정적 골격
  ├── shell.rsc           - React Server Components payload (정적)
  └── dynamic-hole-1.rsc  - Suspense 경계 안의 동적 컴포넌트 (런타임 생성)
```

요청이 들어오면 Edge / Node 서버는 shell을 즉시 응답으로 보낸다. 사용자는 fallback UI(예: 스켈레톤)를 보면서 빠른 TTFB를 얻는다. 동시에 서버는 dynamic hole 컴포넌트를 렌더링해 streaming HTML chunk로 이어붙인다. 브라우저는 React가 chunk를 받을 때마다 fallback을 실제 컴포넌트로 교체한다.

```tsx
// app/products/[id]/page.tsx
import { Suspense } from 'react';
import { ProductDetail } from './ProductDetail';
import { CartStatus } from './CartStatus';
import { CartStatusSkeleton } from './CartStatusSkeleton';

export const experimental_ppr = true;

export default async function ProductPage({ params }: { params: { id: string } }) {
  return (
    <main>
      <ProductDetail id={params.id} />
      <Suspense fallback={<CartStatusSkeleton />}>
        <CartStatus productId={params.id} />
      </Suspense>
    </main>
  );
}
```

빌드 시점에 Next.js는 페이지 트리를 traverse 하면서 dynamic API 호출이 어디에 있는지 추적한다. `CartStatus` 안에서 `cookies()`나 `headers()`나 `unstable_noStore()` 같은 dynamic API가 호출되면 그 컴포넌트는 dynamic hole로 분류된다. 가장 가까운 상위 `Suspense` 경계가 hole의 경계다. 그래서 fallback이 정적 shell에 포함되고, hole의 RSC payload는 런타임에 생성된다.

Suspense 경계가 없는 dynamic API 호출은 빌드 실패다. PPR은 페이지 전체에 적어도 하나의 정적 경계를 요구한다.

## 3. Dynamic API의 영향

Next.js는 다음 호출을 dynamic 신호로 본다.

```
cookies()            // 요청별 쿠키
headers()            // 요청 헤더
draftMode()          // 미리보기 모드
unstable_noStore()   // 명시적 dynamic 선언
searchParams         // page props로 받은 query string
fetch(..., { cache: 'no-store' })  // 캐시 우회 fetch
fetch(..., { next: { revalidate: 0 } })  // 0초 revalidate
```

```tsx
// app/products/[id]/CartStatus.tsx
import { cookies } from 'next/headers';

export async function CartStatus({ productId }: { productId: string }) {
  const sessionId = cookies().get('session')?.value;
  const cart = await fetchCart(sessionId);
  const inCart = cart.items.some(item => item.productId === productId);
  return inCart ? <span>장바구니에 있음</span> : <AddToCartButton id={productId} />;
}
```

`cookies()` 호출 때문에 이 컴포넌트는 dynamic이다. PPR은 빌드 시점에 이 호출을 감지하고 컴포넌트를 hole로 분리한다.

흔한 함정: 컴포넌트 자체는 정적이지만 import한 데이터 fetcher가 내부에서 `headers()`를 호출하면 그 컴포넌트도 dynamic이 된다. 빌드 결과를 검증하려면 `next build` 출력의 `○ (Static)` / `λ (Dynamic)` / `◐ (PPR)` 마커를 본다.

```
Route (app)                              Size  First Load JS
◐ /products/[id]                         12 kB  120 kB
○ /about                                  3 kB  100 kB
λ /api/checkout                           N/A
```

`◐` 마커가 PPR이 적용된 페이지다.

## 4. Streaming의 동작 모델

PPR의 streaming은 React 18+의 server streaming 기능을 그대로 쓴다. 구체적으로는 `renderToReadableStream`에 Suspense fallback이 포함된 트리를 넘기고, 비동기 컴포넌트가 resolve 될 때마다 React가 `<template>` chunk를 추가하는 방식이다.

```html
<!-- 첫 응답 -->
<main>
  <h1>Product Title</h1>
  <p>Static description...</p>
  <div id="suspense-1">
    <div class="skeleton">Loading cart status...</div>
  </div>
</main>
<!-- 이 시점에 응답이 닫히지 않고 streaming 유지 -->

<!-- 100ms 후 cart fetch 완료, chunk 추가 -->
<template id="suspense-1-content">
  <span>장바구니에 있음</span>
</template>
<script>
  $RC("suspense-1", "suspense-1-content");
</script>
```

`$RC`는 React가 주입하는 client-side runtime 함수로, fallback DOM을 실제 콘텐츠로 swap 한다. 이 모든 과정이 hydration 이전에 일어나, 사용자는 JavaScript bundle 다운로드를 기다리지 않고 콘텐츠를 본다.

이런 streaming의 비용은 HTTP keep-alive와 응답 길이가 미리 정해지지 않는다는 점이다. CDN 레벨에서는 chunk 단위로 캐시 키를 분리할 수 없으니 dynamic hole은 본질적으로 origin에서 매번 렌더링된다.

## 5. 캐시 전략과 PPR의 결합

PPR이 적용된 페이지의 정적 shell은 ISR 처럼 revalidate 주기를 가질 수 있다.

```tsx
export const revalidate = 3600; // 1시간마다 shell 재생성
export const experimental_ppr = true;
```

shell은 build time에 한 번 만들어지고, 1시간마다 백그라운드에서 재생성된다. 이 사이 모든 요청은 CDN에 cached shell을 받는다. dynamic hole은 매 요청마다 origin에서 만들어진다.

shell 안의 fetch 요청도 캐싱이 가능하다.

```tsx
// app/products/[id]/ProductDetail.tsx
export async function ProductDetail({ id }: { id: string }) {
  const product = await fetch(`https://api.example.com/products/${id}`, {
    next: { revalidate: 3600, tags: [`product-${id}`] }
  }).then(r => r.json());
  return <article>...</article>;
}
```

`tags`는 on-demand revalidation에 쓴다. 상품 정보가 변경되면 다음과 같이 invalidate.

```tsx
import { revalidateTag } from 'next/cache';

export async function updateProduct(id: string, data: ProductUpdate) {
  await db.product.update({ where: { id }, data });
  revalidateTag(`product-${id}`);
}
```

shell이 즉시 재생성되지는 않고, 다음 요청에서 stale-while-revalidate 동작으로 background regeneration이 트리거된다.

## 6. Edge vs Node.js 런타임

PPR은 두 런타임 모두에서 동작하지만 trade-off가 다르다.

| 런타임 | shell 응답 latency | dynamic hole latency | DB connection |
|---|---|---|---|
| Edge | 매우 낮음 (CDN PoP) | DB 거리 영향 큼 | HTTP-based만 가능 |
| Node.js | 보통 | 안정적 | TCP pool 가능 |

Edge에서 shell은 사용자에게 가장 가까운 PoP에서 즉시 응답되지만, dynamic hole이 PostgreSQL 같은 region-bound DB에 접근하면 round-trip이 region까지 가야 한다. 이 경우 PPR의 streaming 이득이 dynamic hole의 latency에 묻힌다.

해결책은 dynamic hole에서 cached external API나 read-replica를 사용하거나, dynamic hole을 client component로 옮겨 사용자 device에서 직접 fetch 하게 하는 것이다.

```tsx
'use client';
export function CartStatus({ productId }: { productId: string }) {
  const { data } = useSWR(`/api/cart/check?id=${productId}`, fetcher);
  if (!data) return <CartStatusSkeleton />;
  return data.inCart ? <span>장바구니에 있음</span> : <AddToCartButton id={productId} />;
}
```

이 경우 PPR은 client component를 정적 shell의 일부로 처리한다. JavaScript bundle은 shell에 포함되지만 데이터는 hydration 이후 client에서 fetch 한다.

## 7. 실측 비교

같은 상품 페이지를 SSR / ISR / PPR 세 방식으로 구현해 측정한 결과(가상의 기준 수치, p99).

| 방식 | TTFB | LCP | CDN 적중률 | origin RPS |
|---|---|---|---|---|
| SSR | 280ms | 720ms | 0% | 1000 |
| ISR (1h revalidate) | 60ms | 380ms | 92% | 80 |
| PPR | 40ms | 320ms | shell 92% / hole 0% | hole 분량 1000 |

PPR의 TTFB는 shell이 CDN에서 즉시 응답되어 ISR보다 짧다. LCP는 정적 부분이 먼저 painted 되므로 가장 빠르다. dynamic hole은 origin RPS를 그대로 받지만 hole이 페이지 전체보다 가벼우면 origin 부하가 줄어든다.

origin 비용이 줄어드는 정도는 dynamic hole의 비중에 비례한다. 페이지의 90%가 정적이라면 origin 처리량이 1/10로 줄어드는 효과다.

## 8. 도입 전 점검 사항

PPR은 Next.js 14에서 experimental 단계로 들어와 15에서 stable 후보였다. 운영 도입 시 사용 중인 Next.js 버전의 PPR 안정성 정책을 확인한다. canary 채널에서만 활성화되는 시기에는 production 배포를 권장하지 않는다.

`experimental_ppr = true`는 페이지 단위 opt-in이다. layout / route group 단위로 묶고 싶으면 layout.tsx에 같은 export를 추가한다.

```tsx
// app/products/layout.tsx
export const experimental_ppr = true;
```

dynamic API 호출이 의도치 않은 곳에 숨어있을 수 있다. 라이브러리 내부에서 `headers()`를 호출하는 분석 SDK 같은 것들. 빌드 시점에 PPR이 의도한 정적 경계가 깨지면 console에 경고가 나온다. 모든 경고를 제거한 뒤 production에 올린다.

JavaScript 비활성 환경에서는 dynamic hole이 fallback 상태로 남는다. SEO와 접근성을 고려한다면 hole의 fallback도 의미 있는 콘텐츠를 보여줘야 한다. 단순 스켈레톤 대신 "현재 사용자 상태 정보 로딩 중"같은 텍스트가 권장된다.

## 9. Trade-off 요약

PPR은 SSR과 SSG의 중간 지점이 아니라, **둘을 한 페이지 안에서 동시에 쓰는** 새 모델이다. 동적 부분이 있다는 이유로 페이지 전체를 SSR로 강등시킬 필요가 없어진다.

성능 이득은 dynamic hole의 비중과 origin 응답 시간에 달렸다. hole이 무거운 외부 API 호출을 한다면 PPR이 streaming으로 보여주는 fallback 시간이 길어진다. 이 경우 hole 자체의 latency 최적화가 PPR 도입보다 우선이다.

빌드 시점에 dynamic API 검출이 보수적이라, "내가 보기엔 정적이어야 하는데 dynamic으로 분류됨" 케이스가 자주 생긴다. `next build` 출력의 마커와 개별 컴포넌트의 dynamic API 호출 여부를 코드 리뷰 시 점검 항목으로 둔다.

## 참고

- Next.js Documentation - Partial Prerendering
- Vercel Blog, "Partial Prerendering in Next.js"
- React 18 Documentation - Suspense for Server Rendering
- Lee Robinson, "The Future of Rendering with PPR" (Vercel)
