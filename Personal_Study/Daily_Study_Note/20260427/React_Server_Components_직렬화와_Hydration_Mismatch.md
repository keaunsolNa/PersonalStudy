Notion 원본: https://www.notion.so/34f5a06fd6d38139835de88631b3af22

# React Server Components 직렬화와 Hydration Mismatch 디버깅

> 2026-04-27 신규 주제 · 확장 대상: React / Next.js

## 학습 목표

- RSC payload(Flight 포맷)의 구조와 직렬화 가능한 props 규칙을 파악한다
- Server Component 트리가 Client Component 트리로 stitch 되는 wire 구조를 추적한다
- Hydration Mismatch 가 어떤 패턴에서 발생하는지 원인 카테고리별로 정리한다
- Next.js 15 환경에서 발생한 mismatch 를 디버깅하는 도구와 절차를 손에 익힌다

## 1. RSC payload 는 무엇인가

전통적인 React SSR은 서버에서 렌더링한 HTML 과 클라이언트에서 다시 실행할 JS 번들을 함께 보냈다. 클라이언트는 HTML 을 보면서 같은 컴포넌트 트리를 다시 렌더링해 가상 DOM을 맞추는 hydration 을 수행했다.

React Server Components 는 이 모델을 두 갈래로 쪼갰다. Server Component 는 서버에서만 실행되고 결과는 React 가 정의한 wire format(Flight 포맷)으로 직렬화돼 전송된다. Client Component(`"use client"` 가 선언된 모듈)는 그 안에서 client island 로 표현되며, props 의 직렬화된 값과 함께 클라이언트에서 hydrate 된다.

Flight 포맷은 JSON-like 한 라인 기반 스트림이다. 각 라인은 ID 와 페이로드를 가진다. Next.js 15 의 한 페이지를 받아 보면 다음과 비슷한 모양이 나온다(축약):

```
0:I["./node_modules/next/dist/client/components/app-router.js","app-router"]
1:I["./components/Button.client.tsx","Button"]
2:["$","html",null,{"lang":"ko","children":[
  ["$","head",null,{}],
  ["$","body",null,{"children":[
    ["$","main",null,{"children":[
      ["$","h1",null,{"children":"주문 목록"}],
      ["$L1",null,{"label":"새 주문"}]
    ]}]
  ]}]
]}]
```

`$` 는 React element, `$L1` 은 ID 1 로 등록된 Client Component 참조다. 클라이언트는 이 스트림을 받아 React tree 를 재구성하고, `$L1` 위치에 실제 Button 컴포넌트를 끼워 넣어 hydrate 한다.

## 2. 직렬화 가능한 props 규칙

Server Component → Client Component 로 props 를 넘길 때 React 는 그 값을 Flight 포맷으로 직렬화한다. 직렬화 불가능한 값은 런타임 에러로 거부된다.

직렬화 가능: primitive(string/number/boolean/null/undefined), Plain object, Array, Date, Map, Set, BigInt, Promise, JSX(React element), Server Action(함수 참조 ID로 변환).

직렬화 불가: 일반 함수, class instance, Symbol(globalSymbol 제외), Error 객체, DOM node, RegExp(특정 버전).

Promise 가 직렬화된다는 점이 RSC 의 가장 큰 무기다. Server Component 가 fetch 로 만든 Promise 를 Client Component 의 prop 으로 그대로 넘길 수 있고, 클라이언트는 `use(promise)` 훅으로 해당 값을 읽는다.

```tsx
// app/orders/page.tsx — Server Component
import { OrderList } from './OrderList';

async function fetchOrders() {
    const res = await fetch('https://api.example.com/orders', {
        next: { revalidate: 30 }
    });
    return res.json();
}

export default function OrdersPage() {
    const ordersPromise = fetchOrders();        // Promise 그대로 전달
    return <OrderList ordersPromise={ordersPromise} />;
}
```

```tsx
// app/orders/OrderList.tsx
'use client';
import { use } from 'react';

export function OrderList({ ordersPromise }: { ordersPromise: Promise<Order[]> }) {
    const orders = use(ordersPromise);          // Suspense 경계에서 resolve
    return (
        <ul>
            {orders.map(o => <li key={o.id}>{o.id} — {o.status}</li>)}
        </ul>
    );
}
```

이 패턴이 잘 동작하려면 `<Suspense>` 가 OrderList 위쪽에 있어야 한다. Promise pending 상태에서 fallback 이 동작한다.

## 3. Server Action 의 직렬화

Server Action 은 함수지만 직렬화된다. 정확히는 함수가 아니라 함수의 참조 ID 가 직렬화돼 전송되고, 클라이언트가 호출하면 ID 와 인자가 서버로 POST 된다.

```tsx
// app/orders/actions.ts
'use server';

export async function cancelOrder(orderId: string) {
    const session = await getSession();
    await db.orders.update({ where: { id: orderId }, data: { status: 'CANCELED' } });
    revalidateTag(`orders:${session.userId}`);
}
```

```tsx
// app/orders/CancelButton.tsx
'use client';

import { cancelOrder } from './actions';

export function CancelButton({ orderId }: { orderId: string }) {
    return <button onClick={() => cancelOrder(orderId)}>취소</button>;
}
```

Server Action 은 보안 면에서 주의가 필요하다. CSRF 보호가 기본 활성화돼 있고 같은 origin 만 허용되지만, 인자가 클라이언트에서 임의로 조작될 수 있으므로 서버 측에서 권한 검증을 반드시 한다. orderId 만 받고 owner 검증을 빠뜨리면 IDOR 취약점이 된다.

## 4. Hydration Mismatch 가 발생하는 원인

Hydration Mismatch 는 서버에서 렌더한 HTML 과 클라이언트에서 첫 렌더한 React tree 가 일치하지 않을 때 발생한다. 콘솔에는 `Text content does not match server-rendered HTML` 또는 `Hydration failed because the initial UI does not match what was rendered on the server` 가 출력된다.

원인은 크게 4 가지로 분류된다.

첫째, **시간/지역에 의존하는 값**. `new Date().toLocaleString()`, `Math.random()`, `Date.now()` 를 컴포넌트 본문에서 직접 호출하면 서버와 클라이언트의 결과가 다르다. 해결은 `useEffect` 안에서 호출하거나 서버 값을 props 로 고정해 전달한다.

둘째, **타임존 차이**. 서버는 UTC, 클라이언트는 KST 인 경우 같은 Date 를 toLocaleString 하면 다른 문자열이 나온다. 해결은 ISO 문자열로만 props 로 넘기고, 클라이언트에서 Intl.DateTimeFormat 으로 포맷한다.

셋째, **브라우저 전용 API 직접 호출**. window, localStorage, navigator 같은 객체를 컴포넌트 본문에서 호출하면 서버에서 undefined 다. typeof 체크 또는 useEffect 로 옮긴다.

넷째, **HTML 문법 위반**. `<p>` 안에 `<div>` 를 넣거나 `<table>` 안에 `<a>` 를 잘못 넣으면 브라우저 파서가 자동으로 트리를 교정하는데 React tree 와 일치하지 않게 된다. validator 로 잡거나 `<div>` 대신 `<span>` 같이 inline element 로 대체한다.

## 5. Next.js 15 의 디버깅 도구

Next.js 15 는 mismatch 발생 시 콘솔에 정확한 노드 위치를 표시한다. dev 모드에서는 다음 형태로 출력된다:

```
Warning: Text content did not match. Server: "23:45" Client: "12:45"
    at p
    at div
    at OrderTimeBadge (./components/OrderTimeBadge.tsx:8:3)
    at OrderCard (./components/OrderCard.tsx:23:5)
```

위치를 알면 절반은 끝났다. 다음으로 점검할 체크리스트는 다음과 같다.

```bash
# 1. SSR HTML 확인 — 서버가 보낸 HTML 이 어떻게 생겼는가
curl -s 'http://localhost:3000/orders' | grep -A 2 'order-time-badge'

# 2. RSC payload 확인 — Flight 스트림에서 해당 컴포넌트 경로 찾기
curl -s 'http://localhost:3000/orders' \
  -H 'RSC: 1' \
  -H 'Next-Router-State-Tree: ...' \
  | head -100

# 3. 클라이언트 첫 렌더 비교 — DevTools Components 패널에서 동일 위치 React tree 확인
```

dev 모드에서 mismatch 가 났는데 prod 빌드에서는 안 나는 경우, 서버 환경(타임존, 환경변수, NODE_ENV) 차이를 의심한다. 흔한 케이스는 dev 머신은 KST 인데 컨테이너 빌드의 base image 가 UTC 인 경우다.

## 6. Streaming SSR 과 Hydration 의 순서

Next.js 의 `<Suspense>` 는 서버에서 컴포넌트가 resolve 되기 전에 fallback 을 먼저 HTML 로 보내고, resolve 가 끝나면 추가 HTML 을 stream 으로 이어붙인다. 클라이언트는 fallback HTML 부터 hydrate 하고, 후속 chunk 가 도착하면 해당 영역만 다시 hydrate 한다.

이 모델은 TTFB 를 단축하지만 디버깅을 어렵게 만든다. 같은 페이지에서 한 영역은 fallback 으로 hydrate 됐다가 1초 후 실제 데이터로 다시 hydrate 되기 때문이다. 이 사이에 사용자가 click 같은 이벤트를 시도하면 React 18+ 의 selective hydration 이 우선순위를 조정한다.

```tsx
// 적절한 Suspense 분할
export default function OrdersPage() {
    return (
        <main>
            <h1>주문 목록</h1>
            <Suspense fallback={<OrderListSkeleton />}>
                <OrderList />
            </Suspense>
            <Suspense fallback={<RecommendationsSkeleton />}>
                <Recommendations />
            </Suspense>
        </main>
    );
}
```

OrderList 가 200ms 만에 끝나고 Recommendations 가 1.5s 걸리는 페이지에서, 위 구조는 헤더와 OrderList 를 먼저 stream 으로 보내고 Recommendations 는 별도 chunk 로 도착한다. 사용자는 헤더와 주문 목록을 빨리 보고 추천은 늦게 본다.

## 7. Cache 와 Hydration 의 충돌

Next.js 의 fetch 캐시(Data Cache)와 라우터 캐시(Router Cache)는 서버 렌더링과 클라이언트 네비게이션 양쪽에서 동작한다. 같은 페이지가 캐시된 상태에서 사용자가 데이터를 바꾸면 cache invalidation 이 안 된 화면이 보일 수 있다.

`revalidateTag` / `revalidatePath` 가 invalidate 의 기본 도구다. Server Action 안에서 호출하면 다음 요청부터 새 값이 반영된다. 다만 라우터 캐시는 클라이언트에 있으므로, 클라이언트가 같은 라우트로 다시 들어와도 캐시된 RSC payload 를 쓸 수 있다. 이 때 `router.refresh()` 가 강제 reload 트리거다.

```tsx
'use client';

import { useRouter } from 'next/navigation';
import { cancelOrder } from './actions';

export function CancelButton({ orderId }: { orderId: string }) {
    const router = useRouter();

    const handleClick = async () => {
        await cancelOrder(orderId);
        router.refresh();   // RSC payload 재요청
    };

    return <button onClick={handleClick}>취소</button>;
}
```

## 8. 자주 만나는 mismatch 패턴 5 가지

운영 중인 Next.js 앱을 디버깅하다 보면 같은 패턴이 반복된다. 정리하면 다음과 같다.

| 패턴 | 증상 | 원인 | 해결 |
| --- | --- | --- | --- |
| 시간 표시 | "Text content did not match" 와 시간 문자열 차이 | 서버/클라 타임존 차이 | ISO 문자열만 prop 으로 넘기고 클라이언트에서 포맷 |
| 다크모드 깜빡임 | 첫 렌더에 light → dark 로 깜빡 | localStorage 값을 useEffect 에서 읽어서 적용 | `<script>` 로 SSR 전 cookie 또는 system preference 조회 |
| 광고/AB 테스트 | 영역마다 다른 컴포넌트 | 클라이언트 JS 가 후처리로 DOM 변경 | suppressHydrationWarning 또는 Client-only 영역 분리 |
| Next/Image | layout shift 후 mismatch | width/height 누락 | sizes/fill 명시 |
| 브라우저 확장 | mismatch 로그 + DOM 추가 attribute | Grammarly/Dark Reader 가 attribute 주입 | suppressHydrationWarning on body 또는 무시 |

`suppressHydrationWarning` 는 silver bullet 이 아니다. 같은 요소 안의 children 만 워닝을 억제하고 그 자손은 그대로 검사된다. 진짜 차이가 있는 영역을 한정해서 사용해야 다른 mismatch 를 가린다.

## 9. 운영에서의 모니터링 전략

Hydration mismatch 는 dev 에서만 강한 워닝을 띄우고 prod 에서는 silent 하게 fallback render(서버 트리 버리고 클라이언트만 다시 렌더) 한다. 운영에서 FCP/INP 가 갑자기 나빠졌다면 mismatch 가 있을 가능성이 있다.

Sentry, Datadog RUM 같은 도구로 client error 를 수집하되, hydration 관련 에러는 별도 필터로 모은다. 일반적으로 다음 메시지를 매칭한다:

```
Hydration failed because the initial UI does not match
Text content does not match server-rendered HTML
There was an error while hydrating
```

이 에러가 일정 임계 이상이면 release 와 매칭해 어떤 배포가 회귀를 만들었는지 보고, 해당 release 의 PR diff 에서 RSC/CSR 경계 변경을 확인한다. 회귀의 80% 는 직렬화 불가능한 prop 추가, 시간/난수 관련 변경, 새 브라우저 API 직접 호출 중 하나에서 발생한다.

## 참고

- React 공식 문서, "Server Components": https://react.dev/reference/rsc/server-components
- React 공식 문서, "use" 훅: https://react.dev/reference/react/use
- Dan Abramov, "RSC From Scratch" 시리즈: https://github.com/reactwg/server-components/discussions/5
- Next.js 15 공식 문서, Caching: https://nextjs.org/docs/app/building-your-application/caching
- Vercel Engineering, "How React Server Components Work": https://vercel.com/blog/understanding-react-server-components
