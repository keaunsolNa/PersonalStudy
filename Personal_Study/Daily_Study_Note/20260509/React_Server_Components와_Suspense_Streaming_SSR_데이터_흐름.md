Notion 원본: https://www.notion.so/35b5a06fd6d3813b848ce320aba73477

# React Server Components와 Suspense Streaming SSR 데이터 흐름

> 2026-05-09 신규 주제 · 확장 대상: React / Next.js

## 학습 목표

- React Server Components(RSC) 가 *클라이언트 번들에 포함되지 않는 컴포넌트* 라는 관점에서 어떤 문제를 해결하는지 분해한다
- Server Component / Client Component / Shared Component 의 *경계* 를 `"use client"` 디렉티브와 모듈 그래프 관점에서 추적한다
- Suspense + streaming SSR 이 어떻게 *HTML 을 부분적으로 흘려 보내고*, 클라이언트 hydration 이 어떻게 *partial* 로 일어나는지 단계별로 본다
- React 19 의 `use(promise)` / `useFormState` / Action 흐름이 RSC 와 어떻게 연결되는지 정리한다

## 1. 문제 정의: 클라이언트 번들의 비대함

전통적 SPA 는 모든 컴포넌트가 *클라이언트 JS 번들* 에 들어간다. UI 트리가 커질수록 *초기 로딩 폭증*. SSR 은 첫 HTML 을 빠르게 주지만, hydration 시 *동일 트리의 JS 가 다시 클라이언트로 전송* 된다.

RSC 의 가설: "*상호작용이 없고 데이터에만 의존* 하는 컴포넌트는 서버에서만 실행되어야 한다. 클라이언트는 그 결과만 받아 그린다."

## 2. 컴포넌트 종류

| 종류 | 어디서 실행 | 클라이언트 번들 포함 | hooks |
|---|---|---|---|
| Server Component | 서버에서만 | 아니오 | 클라이언트 hooks 사용 불가 (`useState`, `useEffect` X) |
| Client Component | SSR 1회 + 클라이언트 | 예 | 모두 가능 |
| Shared Component | 환경에 따라 | 가능 | 환경에 따라 제한 |

`"use client"` 는 *모듈* 단위 디렉티브. 이 디렉티브가 있는 파일은 *그 자체 + import 한 모든 모듈* 이 클라이언트 번들에 포함된다 (단, 그 안에서 `"use server"` 로 마킹된 함수는 별도 처리).

```tsx
// app/page.tsx — 기본 Server Component
import { db } from "@/lib/db";
import Counter from "./Counter";

export default async function Page() {
  const posts = await db.post.findMany();   // 서버에서만 실행
  return (
    <div>
      <h1>Posts</h1>
      <Counter />                           // Client 컴포넌트 임포트
      {posts.map(p => <article key={p.id}>{p.title}</article>)}
    </div>
  );
}
```

```tsx
// app/Counter.tsx
"use client";
import { useState } from "react";

export default function Counter() {
  const [n, setN] = useState(0);
  return <button onClick={() => setN(n + 1)}>{n}</button>;
}
```

서버 컴포넌트는 *async function* 으로 적을 수 있다. 데이터 fetch 가 컴포넌트 본문에서 *직접 await* 가능. fetch 라이브러리 분리 부담이 사라진다.

## 3. RSC 페이로드와 직렬화

RSC 의 결과물은 HTML 이 아니라 *RSC payload* 라는 자체 직렬화 포맷. JSX 트리를 표현하되, 자식이 client component 인 경우 *해당 컴포넌트의 모듈 ID 와 props* 만 들어간다. 클라이언트 측 React 가 이 payload 를 받아 *진짜 React tree* 로 복원하고, client component 들을 hydrate 한다.

전송 형태(개념적):

```
M1:{"id":"./Counter.js","name":"default","chunks":["chunk-abc.js"]}
J0:["$","div",null,{"children":[
   ["$","h1",null,{"children":"Posts"}],
   ["$","@1",null,{}],   // ← M1 매핑된 client component
   ...
]}]
```

`@1` 마커가 *클라이언트 측에서 동적으로 import 해야 할 컴포넌트* 다. 서버는 props 만 직렬화하고, 컴포넌트 코드 자체는 클라이언트가 chunk 로 받아 실행한다.

## 4. Suspense + Streaming SSR

Suspense 경계가 있으면 React 18+ 의 `renderToPipeableStream` (Node) 또는 `renderToReadableStream` (Edge) 이 *완성된 부분부터* HTML 을 흘려 보낸다. `<Suspense fallback={...}>` 안쪽이 아직 준비 안 됐으면 fallback 으로 placeholder HTML 을 먼저 보내고, 데이터가 준비되는 즉시 *Out-of-Order* 로 실제 콘텐츠 청크를 추가 전송한다.

```html
<!doctype html>
<div>
  <h1>Posts</h1>
  <!-- Suspense placeholder -->
  <template id="B:0"></template>
  <div>Loading...</div>
</div>
... (브라우저는 그동안 위 HTML 을 점진 표시)

<!-- 잠시 후 추가 전송 -->
<div hidden id="S:0">실제 posts list HTML</div>
<script>$RC("B:0","S:0")</script>
```

`$RC` 같은 작은 inline 스크립트가 *placeholder 를 실제 콘텐츠로 교체* 한다. JS 다운로드/파싱이 끝나기도 전에 일부 HTML 은 이미 화면에 그려진 상태가 된다. 핵심: *FCP/LCP 가 가장 느린 데이터에 묶이지 않는다*.

## 5. Selective / Progressive Hydration

streaming SSR 의 결과로, 클라이언트에서 hydrate 할 때도 *모든 컴포넌트가 동시에* 가 아니라 *Suspense 경계 단위로* 처리된다. 사용자가 먼저 상호작용한 영역의 hydration 이 *우선순위* 를 가질 수 있다 (React 18 concurrent feature).

이는 *huge UI tree 에서 첫 인터랙션 응답성* 을 크게 개선한다. 단, hydration 까지의 짧은 구간 동안 onClick 이 무시되는 *click-before-hydrate* 이슈가 있을 수 있어, 사용자 인터랙션 디자인에서 fallback 동작을 의식해야 한다 (e.g. 폼 submit 은 native form action 으로 동작하도록 두기).

## 6. Server Actions

`"use server"` 함수는 클라이언트에서 *함수 참조처럼 import* 되지만, 호출 시 실제로는 RPC 가 발생해 서버에서 실행된다. Form action 으로 직접 연결할 수 있어 *JS 비활성 환경* 에서도 progressive enhancement 가 살아난다.

```tsx
// app/actions.ts
"use server";

export async function createPost(data: FormData) {
  const title = String(data.get("title"));
  await db.post.create({ data: { title } });
}
```

```tsx
// app/Form.tsx
"use client";
import { createPost } from "./actions";

export default function Form() {
  return (
    <form action={createPost}>
      <input name="title" />
      <button>저장</button>
    </form>
  );
}
```

Action 은 *함수 ID* 가 빌드 시 결정되어 클라이언트에 박힌다. 클라이언트 → 서버 호출 시 ID + 인수 직렬화 → 서버에서 매핑된 함수 실행. CSRF 방어, 인수 sanitize 는 프레임워크(Next.js 는 form action 호출에 별도 토큰)가 책임진다.

## 7. React 19 의 `use(promise)` 와 데이터 fetching

`use(promise)` 는 컴포넌트 렌더 도중 promise 를 *throw* 해 가장 가까운 Suspense 경계로 멈춘다. Server Component 안에서는 `await` 가 자연스럽지만, *Client Component* 에서 server-fetched data 를 받아야 할 때 `use(promise)` 가 깔끔.

```tsx
"use client";
import { use } from "react";

export default function Posts({ promise }: { promise: Promise<Post[]> }) {
  const posts = use(promise);
  return <ul>{posts.map(p => <li key={p.id}>{p.title}</li>)}</ul>;
}
```

서버 컴포넌트가 `<Posts promise={fetchPosts()} />` 형태로 promise 를 *전달* 하는 패턴이 흔하다. 이렇게 하면 fetch 시작 시점은 서버이고, 데이터 도착은 streaming 으로 자연스럽게 흘러간다.

## 8. 캐시와 revalidation (Next.js App Router 기준)

Next.js 14+ 는 fetch 호출에 자체 캐시 레이어를 입힌다.

```ts
// 기본: cache: "force-cache" 와 동등 (정적)
const data = await fetch("/api/x");

// 매 요청 fresh
await fetch("/api/x", { cache: "no-store" });

// 60초 ISR
await fetch("/api/x", { next: { revalidate: 60 } });

// 태그 기반 무효화
await fetch("/api/x", { next: { tags: ["posts"] } });
```

서버 액션에서 `revalidatePath("/")` / `revalidateTag("posts")` 호출 시 해당 경로/태그 캐시를 무효화. RSC 와 fetch 캐시는 *동일 요청 내* 에서 dedup 된다 (같은 URL 두 번 요청 시 한 번만 실제 fetch).

## 9. 한계와 함정

1. **"use client" 의 전염성**: 클라이언트 컴포넌트가 import 한 모듈은 다 클라이언트로 간다. 무거운 라이브러리(예: charts) 를 서버 컴포넌트 가까이에 두면 번들이 커진다. 라이브러리 wrapping 시 import 경계 설계 필수
2. **Server Component 안에서 클라이언트 hooks 사용 X**: `useState`, `useEffect`, `useContext` 사용 불가. 무심코 적으면 빌드 에러
3. **Class component 의 RSC 미지원**: function + hooks 모델로 강제. 레거시 마이그레이션 부담
4. **프록시/반복 직렬화**: server → client props 는 직렬화 가능해야. function, Date 일부, Map/Set 등 직렬화 한계 주의
5. **streaming 인 동안 SEO**: 검색엔진은 `<noscript>` 영역과 *완성된 HTML 응답* 을 본다. streaming 이 너무 길면 일부 봇이 timeout. revalidation 정책 + cache 활용 필수

| 항목 | 전통 SSR | RSC + Streaming |
|---|---|---|
| 첫 HTML | 데이터 다 모은 뒤 | 일부 부분 즉시 |
| JS 번들 | 모든 컴포넌트 | client 컴포넌트만 |
| hydration | 전체 한 번 | Suspense 단위 |
| 데이터 fetch | useEffect 또는 getServerSideProps | 컴포넌트 본문 await |

## 참고

- React Docs — Server Components, Client Components, `use` Hook
- Next.js App Router 문서 — Caching, Server Actions, Streaming
- RFC: React Server Components — facebook/react Discussions
- Lee Robinson, "Why Server Components" 블로그 시리즈
- Vercel Engineering Blog — Streaming SSR / Partial Prerendering
