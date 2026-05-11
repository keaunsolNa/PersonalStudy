Notion 원본: https://www.notion.so/35d5a06fd6d381f7944ef2ef1ffa99d9

# Next.js 15 Partial Prerendering과 Server Actions 트랜잭션 경계

> 2026-05-11 신규 주제 · 확장 대상: Next.js (App Router)

## 학습 목표

- App Router 의 *Partial Prerendering* (PPR) 이 어떻게 한 페이지를 *static shell + dynamic hole* 로 쪼개 emit 하는지, 그 결과 LCP / TTFB / streaming 동작이 어떻게 바뀌는지를 보인다
- PPR 의 enable 절차 — `experimental.ppr`, 라우트 segment 별 `experimental_ppr = true` , `Suspense` 경계의 의미를 본다
- Server Actions 가 `useTransition` / `revalidatePath` / `redirect` 와 결합될 때 *트랜잭션 경계* 가 어디까지 묶이는지, 실패 시 *부분 commit* 이 가능한지 분석한다
- production checklist — middleware 호환, cookie/header mutation 의 제약, ISR + PPR 동시 사용, edge runtime 의 한계, Sentry / OpenTelemetry trace 와의 연동

## 1. Partial Prerendering — 한 줄 정의

**PPR 은 한 페이지를 빌드 시점에 *정적인 부분만 미리 emit* 해 두고, 동적인 부분은 런타임에 streaming 으로 끼워 보내는 모드다.**

| 모드 | 빌드 시점 | 응답 시점 |
|---|---|---|
| SSG | 페이지 통째 HTML emit | CDN edge 에서 그대로 |
| SSR | 아무것도 emit 안 함 | 매 요청 전체 렌더링 |
| ISR | 빌드 시 한 번 + revalidate 후 재생성 | edge cache hit |
| PPR | static shell 부분만 emit, dynamic hole 은 *placeholder* | shell 즉시 + hole 은 streaming 으로 채움 |

페이지 응답이 *두 개의 phase* 로 갈라진다. shell 은 CDN 캐시 hit 처럼 빠르게 도착하고, 그 뒤 Suspense boundary 안에 들어가는 dynamic 영역이 단일 HTTP 응답 안에서 streaming 으로 도착한다.

## 2. 활성화 — 두 가지 위치

`next.config.js` 에서 한 번:

```js
module.exports = {
  experimental: {
    ppr: "incremental",
  },
};
```

`"incremental"` 은 *opt-in 라우트만* PPR. 라우트 segment 의 `layout.tsx` 또는 `page.tsx` 에 다음을 추가:

```ts
export const experimental_ppr = true;
```

`"true"` 로 두면 *모든 라우트* 가 PPR 후보가 되는데, 한 곳에서라도 dynamic API(`cookies()`, `headers()`)가 Suspense 밖에서 호출되면 빌드가 실패한다. 마이그레이션 비용이 커서 production 에선 `"incremental"` 이 정석.

## 3. 정적 vs 동적 — 빌드 그래프에서 어디가 갈라지나

```tsx
// app/(shop)/product/[id]/page.tsx
import { Suspense } from "react";

export const experimental_ppr = true;

export default function Page({ params }: { params: { id: string } }) {
  return (
    <article>
      <h1>{getProductTitle(params.id)}</h1>
      <Suspense fallback={<PriceSkeleton />}>
        <PriceTag id={params.id} />
      </Suspense>
      <Suspense fallback={<ReviewsSkeleton />}>
        <Reviews id={params.id} />
      </Suspense>
    </article>
  );
}
```

`getProductTitle` 가 *fetch with cache: "force-cache"* 라면 build 시 채워진다. `PriceTag` 는 `cookies()` 를 호출해 사용자별 통화 표시를 한다 → 빌드 시 dynamic 으로 표시되어 placeholder 만 들어간다.

요청 처리는 다음 흐름이다.

1. CDN 이 page.html 의 shell 을 *즉시* 반환 (cache hit, TTFB ~30ms)
2. 서버 컴포넌트 런타임이 dynamic 슬롯을 평가하면서 결과를 같은 응답 스트림에 *후속 RSC chunk* 로 흘림
3. React DOM 이 client 측에서 chunk 가 도착할 때마다 Suspense boundary 의 fallback 을 교체

## 4. LCP / FCP / 캐시 hit-rate 의 변화

| 메트릭 | 기존 SSR | PPR |
|---|---|---|
| TTFB | 250~600 ms | 20~80 ms |
| FCP | TTFB + paint | shell paint 즉시 |
| origin 부하 | 매 요청 풀 SSR | dynamic slot 만 평가 |

origin 부하는 *slot 수와 cost 에 의존* 한다. dynamic slot 이 DB 1번이면 부하 절감 효과가 분명하지만, slot 안에서 N+1 fetch 가 일어나면 SSR 과 차이가 없다.

## 5. Server Actions 의 트랜잭션 경계

```tsx
// app/cart/actions.ts
"use server";

import { revalidatePath } from "next/cache";
import { db } from "@/lib/db";

export async function addItem(prev: State, fd: FormData): Promise<State> {
  const userId = await currentUserId();
  const sku = fd.get("sku") as string;
  const qty = Number(fd.get("qty"));

  await db.$transaction(async (tx) => {
    const product = await tx.product.findUniqueOrThrow({ where: { sku } });
    if (product.stock < qty) throw new Error("재고 부족");
    await tx.product.update({ where: { sku }, data: { stock: { decrement: qty } } });
    await tx.cartItem.upsert({
      where: { userId_sku: { userId, sku } },
      create: { userId, sku, qty },
      update: { qty: { increment: qty } },
    });
  });

  revalidatePath("/cart");
  return { ok: true };
}
```

여기서 *트랜잭션 경계는 `db.$transaction` 안* 이고, 그 바깥의 `revalidatePath` 는 트랜잭션과 무관하게 *commit 이후* 실행된다.

**부분 commit 위험은 더 미묘하다**: action 안에서 *여러 외부 시스템* 을 부르면, DB 트랜잭션이 covered 하지 못한다. 외부 결제 API 호출 후 DB 트랜잭션 commit 직전에 process crash → 외부엔 결제, DB 엔 cart 만 → 보상 트랜잭션이 필요. 이건 PPR 이 아니라 모든 server action 의 공통 문제. *outbox 패턴* 으로 풀어야 한다.

## 6. `redirect` 와 `useTransition` 의 흐름 차이

`redirect` 는 server action 안에서 호출되면 *throw* 처럼 동작해 함수가 그 자리에서 끝난다. 단 React 가 그 redirect 를 잡아 RSC payload 의 redirect instruction 으로 변환한다. 따라서 *redirect 이후 코드가 실행되지 않는다*.

```ts
export async function checkout(fd: FormData) {
  const orderId = await db.createOrder(/*…*/);
  redirect(`/order/${orderId}/done`);
  await db.recordAuditLog("checkout"); // ❌ 실행 안 됨
}
```

`useTransition` 으로 client 가 wrapping 하면 *pending 상태* 동안 UI 가 stale 데이터를 유지하면서 background 에서 다음 RSC 를 받아 갈아끼울 수 있다. PPR shell 의 즉시 응답성과 잘 맞는 조합.

## 7. middleware / edge runtime 제약

- middleware 에서 `NextResponse.next({ headers })` 로 헤더를 mutate 해 두면 PPR shell 의 *캐시 키* 에 영향을 준다. 사용자별로 다른 헤더(예: Authorization echo)를 넣으면 *모든 사용자 별로 shell 캐시가 분기* 되어 hit-rate 가 폭락한다.
- edge runtime 에선 Node.js 의 `fs`, `crypto.scrypt` 같은 일부 API 가 막혀 있다. PPR 의 shell 생성은 build 시점 node 에서 수행하니 영향 없음. 다만 dynamic slot 이 edge runtime 으로 옮겨가면 거기서 막힌다.
- ISR 과 PPR 동시 사용: revalidate 가 일어나면 *shell 만* 재생성된다. dynamic slot 은 어차피 매 요청 평가되므로 영향 없음.

## 8. 관측 — OpenTelemetry / Sentry

Next.js 15 의 `instrumentation.ts` 가 정식이다.

```ts
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    await import("./instrumentation.node");
  }
}
```

PPR 페이지의 trace 구조:

```
[GET /product/123]
├── span: app.render.shell
├── span: app.render.dynamic.PriceTag
│   └── db.query SELECT price ...
└── span: app.render.dynamic.Reviews
    └── http.client GET /api/reviews
```

dynamic slot 단위로 자동 span 이 생성된다. Sentry 의 Next.js SDK 8+ 가 같은 방식으로 동작.

## 9. PPR 도입 시 흔한 함정 5

1. **dynamic API 가 Suspense 바깥에 노출**: 빌드 단계에서 *전체 페이지가 dynamic 으로 fallback*. PPR 효과가 0.
2. **client component 가 RSC 트리 위에 있음**: layout 의 server component 가 client component 를 포함하고 그 client 가 다시 server component 를 sibling 으로 가지면 PPR 의 shell 추출이 막힌다.
3. **searchParams 사용**: `page` 의 `searchParams` prop 은 자동으로 dynamic. 검색 페이지 등은 PPR 효과가 사실상 없음.
4. **cookies() 를 layout 에서 직접 호출**: layout 전체가 dynamic 으로. cookies 가 필요한 부분만 별도 server component 로 분리하고 Suspense 로 감싸기.
5. **개발 모드 ≠ production**: `next dev` 에선 PPR 동작이 단순 SSR 로 fallback. 반드시 `next build && next start` 로 검증.

## 참고

- Next.js Docs — Partial Prerendering: https://nextjs.org/docs/app/api-reference/next-config-js/ppr
- Next.js Conf 2024 — *Partial Prerendering deep dive* 세션
- Vercel Blog — *How PPR works* (Lee Robinson, 2024-04)
- Next.js Docs — Server Actions and Mutations
- React RFC: Server Components — https://github.com/reactjs/rfcs/blob/main/text/0188-server-components.md
- OpenTelemetry for Next.js — https://nextjs.org/docs/app/building-your-application/optimizing/open-telemetry
