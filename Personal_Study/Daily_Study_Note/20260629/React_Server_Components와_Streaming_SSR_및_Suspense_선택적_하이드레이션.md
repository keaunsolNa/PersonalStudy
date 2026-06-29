Notion 원본: https://app.notion.com/p/38e5a06fd6d3814385bcecc395af886e

# React Server Components와 Streaming SSR 및 Suspense 선택적 하이드레이션

> 2026-06-29 신규 주제 · 확장 대상: Frontend

## 학습 목표

- Server Component 와 Client Component 의 실행 경계와 직렬화 규약을 구분한다
- RSC Payload(Flight) 스트림이 어떻게 컴포넌트 트리를 전송하는지 설명한다
- Suspense 경계가 streaming SSR 에서 HTML 청크를 나누는 원리를 적용한다
- 선택적 하이드레이션이 TTI 를 개선하는 메커니즘과 비용을 측정한다

## 1. Server Component 와 Client Component 의 경계

React Server Components(RSC)는 컴포넌트를 *서버에서만 실행되는 것* 과 *클라이언트로 전송되어 하이드레이트되는 것* 으로 나눈다. Server Component 는 번들에 포함되지 않아 JS 페이로드를 0 으로 만들고, DB·파일 시스템에 직접 접근할 수 있다. 반면 상태(`useState`), 효과(`useEffect`), 브라우저 이벤트 핸들러는 Client Component 에서만 쓸 수 있다.

```tsx
// app/page.tsx — 기본은 Server Component
import db from "@/lib/db";
import { LikeButton } from "./like-button";

export default async function Page() {
	const post = await db.post.findFirst(); // 서버에서 직접 DB 접근
	return (
		<article>
			<h1>{post.title}</h1>
			<LikeButton postId={post.id} /> {/* 상호작용은 클라이언트로 */}
		</article>
	);
}
```

```tsx
// like-button.tsx
"use client"; // 이 지시문이 클라이언트 경계를 선언한다
import { useState } from "react";

export function LikeButton({ postId }: { postId: string }) {
	const [liked, setLiked] = useState(false);
	return <button onClick={() => setLiked((v) => !v)}>{liked ? "♥" : "♡"}</button>;
}
```

`"use client"` 는 *경계* 선언이다. 그 모듈과 거기서 import 하는 하위 트리가 클라이언트 번들에 포함된다. 핵심 규칙은 Server Component 가 Client Component 에 넘기는 props 가 *직렬화 가능* 해야 한다는 것이다. 함수, 클래스 인스턴스, Symbol 은 넘길 수 없고 — 단, Server Action(서버 함수)은 특별 처리로 넘길 수 있다 — 직렬화 가능한 값과 다른 Server Component 를 `children` 으로 전달하는 것은 허용된다.

## 2. RSC Payload — Flight 직렬화

서버는 Server Component 트리를 HTML 이 아니라 *RSC Payload(Flight 포맷)* 라는 특수한 직렬화 스트림으로 렌더한다. 이 페이로드는 렌더된 Server Component 의 결과와, Client Component 가 놀 자리(참조 + props)를 기술한다.

```
// RSC Payload 의 개념적 형태 (줄 단위 스트림)
0:["$","article",null,{"children":[
     ["$","h1",null,{"children":"제목"}],
     ["$","$L1",null,{"postId":"abc"}]   // $L1 = 클라이언트 컴포넌트 참조
   ]}]
1:I["./like-button.js",["chunk-x.js"],"LikeButton"]  // 모듈 청크 매핑
```

클라이언트의 React 런타임은 이 스트림을 읽어 Server Component 가 만든 DOM 구조는 그대로 반영하고, `$L1` 같은 클라이언트 참조 자리에는 해당 모듈을 로드해 하이드레이트한다. 중요한 점은 RSC Payload 가 *컴포넌트 트리 자체* 를 전송한다는 것이다. 그래서 클라이언트 측 라우팅으로 서버 컴포넌트를 다시 가져올 때 전체 페이지를 새로 그리지 않고 트리의 변경분만 병합할 수 있다.

## 3. Streaming SSR 와 Suspense 경계

전통적 SSR 은 서버에서 전체 HTML 을 완성한 뒤 한 번에 보냈다(`renderToString`). 느린 데이터 하나가 전체 응답을 막는 문제가 있었다. Streaming SSR(`renderToPipeableStream`)은 준비된 부분부터 HTML 청크로 흘려보내고, 느린 부분은 `<Suspense>` 경계로 감싸 나중에 채운다.

```tsx
import { Suspense } from "react";

export default function Page() {
	return (
		<main>
			<Header /> {/* 즉시 렌더되어 먼저 전송 */}
			<Suspense fallback={<Spinner />}>
				<SlowComments /> {/* 데이터 준비되면 스트리밍으로 교체 */}
			</Suspense>
		</main>
	);
}
```

동작 원리는 이렇다. 서버는 먼저 `Header` 와 `Spinner`(fallback)가 든 초기 HTML 을 즉시 보낸다. `SlowComments` 의 데이터가 준비되면, 서버가 그 부분의 HTML 조각과 함께 *인라인 `<script>`* 를 추가로 스트리밍한다. 이 스크립트가 DOM 에서 fallback 자리를 실제 콘텐츠로 교체한다. 사용자는 빈 화면 대신 헤더와 스피너를 즉시 보고, 댓글은 준비되는 대로 끊워진다. 이로써 TTFB 와 첫 콘텐츠 표시(FCP)가 가장 느린 데이터에 묶이지 않는다.

## 4. 선택적 하이드레이션 (Selective Hydration)

스트리밍과 짝을 이루는 것이 선택적 하이드레이션이다. 과거에는 전체 페이지 JS 가 모두 로드·하이드레이트되어야 어떤 부분이든 상호작용 가능했다(all-or-nothing). React 18 부터 `Suspense` 경계 단위로 *독립적으로* 하이드레이트한다.

핵심 효과 두 가지다. 첫째, 한 경계의 JS 가 아직 안 왔어도 다른 경계는 먼저 하이드레이트되어 인터랙티브해진다. 둘째, 사용자가 아직 하이드레이트되지 않은 영역을 클릭하면 React 가 그 이벤트를 기록해 두었다가, 해당 경계의 하이드레이션을 *우선순위로 끌어올려* 먼저 처리한다. 즉 사용자가 만지는 곳부터 살아난다.

```tsx
// 각 위젯을 독립 경계로 두면 서로 막지 않고 하이드레이트됨
<Suspense fallback={<SkeletonA />}><WidgetA /></Suspense>
<Suspense fallback={<SkeletonB />}><WidgetB /></Suspense>
```

이 덕분에 무겁고 느린 위젯이 가벼운 위젯의 상호작용을 막지 않는다. 경계를 적절히 나누는 것이 TTI(Time To Interactive) 최적화의 핵심 설계 결정이 된다.

## 5. 성능 효과 측정

스트리밍 + RSC + 선택적 하이드레이션의 효과는 지표별로 다르게 나타난다.

| 지표 | 전통 SSR | Streaming + RSC |
|---|---|---|
| TTFB | 모든 데이터 대기 후 | 즉시 (셰 먼저 전송) |
| FCP | 전체 HTML 완성 후 | 셰 도착 즉시 |
| JS 번들 크기 | 전체 컴포넌트 | Client Component 만 |
| TTI | 전체 하이드레이션 완료 | 경계별 점진적 |

가장 큰 정량 이득은 JS 번들이다. 데이터 페칭·포맷팅·마크다운 렌더링 등을 Server Component 로 옮기면 그 라이브러리들(예: 무거운 마크다운 파서)이 클라이언트 번들에서 완전히 사라진다. 콘텐츠 중심 페이지에서 클라이언트 JS 가 절반 이하로 줄어드는 경우가 흔하다. 반면 상호작용이 조밀한 대시보드는 대부분이 Client Component 라 RSC 의 번들 이득이 작다.

## 6. 흔한 함정

RSC 도입에서 자주 겪는 함정이 있다. 첫째, Client Component 안에서 Server Component 를 *import* 하면 그 Server Component 가 클라이언트화되어 의도한 서버 실행이 사라진다 — 해결책은 `children` props 로 *주입* 하는 컴포지션이다. 둘째, Server Component 에 `useState` 등을 쓰면 빌드 에러가 난다. 셋째, Server→Client props 직렬화 제약을 잊고 함수나 Date 가 아닌 객체를 넘기면 런타임 직렬화 오류가 난다. 넷째, `Suspense` 경계를 너무 잘게 나누면 스트리밍 청크와 fallback 깜빡임이 늘어 오히려 체감 성능이 나빠진다.

## 7. 채택 트레이드오프

RSC + 스트리밍은 콘텐츠 위주의 페이지(블로그, 커머스 목록, 문서)에서 번들 축소와 빠른 FCP 로 명확한 이득을 준다. 반대로 SPA 적 상호작용이 지배적인 앱(에디터, 실시간 협업 도구)에서는 대부분이 클라이언트 경계라 이득이 줄고, 서버/클라이언트 경계를 신경 쓰는 멘탈 모델 비용만 늘 수 있다. 또한 RSC 는 프레임워크(Next.js App Router 등)와 번들러의 깊은 통합을 요구해 인프라 종속이 크다. 팀의 페이지 성격이 콘텐츠 중심인지 상호작용 중심인지, 그리고 프레임워크 종속을 감내할지가 도입의 갈림길이다.

## 8. 데이터 페칭 패턴과 워터폴 방지

Server Component 에서는 `async/await` 로 데이터를 직접 가져온다. 그런데 부모-자식이 각자 순차적으로 `await` 하면 *요청 워터폴* 이 생겨 스트리밍 이득을 깍아먹는다. 핵심은 독립적인 요청을 병렬화하고, 의존적인 요청만 순차로 두는 것이다.

```tsx
// 나쁨 — 순차 워터폴: posts 를 기다린 뒤에야 user 시작
async function Bad() {
	const posts = await getPosts();   // 200ms
	const user = await getUser();     // +150ms (불필요한 대기)
	return <View posts={posts} user={user} />;
}

// 좋음 — 독립 요청 병렬화
async function Good() {
	const [posts, user] = await Promise.all([getPosts(), getUser()]);
	return <View posts={posts} user={user} />;
}
```

더 나아가, 느린 데이터는 `await` 하지 않고 *Promise 를 그대로 자식에 넘긴 뒤* 자식을 `<Suspense>` 로 감싸면, 부모는 즉시 셰을 스트리밍하고 느린 부분만 나중에 채운다. 이것이 RSC + Suspense 의 진짜 시너지다.

```tsx
function Page() {
	const slowData = getSlowData(); // await 안 함 — Promise 전달
	return (
		<Suspense fallback={<Skeleton />}>
			<SlowView dataPromise={slowData} /> {/* 내부에서 use(dataPromise) */}
		</Suspense>
	);
}
```

## 9. 캐싱·재검증과 클라이언트 내비게이션

RSC 의 또 다른 축은 캐싱이다. Server Component 는 요청 단위·영속 캐시에서 데이터 페칭 결과를 재사용해 같은 데이터를 중복 페칭하지 않는다(요청 메모이제이션). 변경이 생기면 태그·경로 기반 재검증으로 캐시를 무효화한다.

```tsx
// Next.js 예 — fetch 결과를 태그로 캐싱하고, 변경 시 그 태그만 무효화
async function getProduct(id: string) {
	const res = await fetch(`https://api/products/${id}`, {
		next: { tags: [`product-${id}`], revalidate: 3600 },
	});
	return res.json();
}

// Server Action 에서 변경 후 해당 태그 무효화
async function updateProduct(id: string, data: FormData) {
	"use server";
	await db.product.update(id, data);
	revalidateTag(`product-${id}`); // 이 제품 캐시만 무효화
}
```

클라이언트 내비게이션 시에는 전체 페이지를 새로 받지 않고 *RSC Payload 의 변경된 트리 segment 만* 가져와 병합한다. 그래서 페이지 전환이 SPA 처럼 빠르면서도 서버 데이터를 항상 최신으로 반영한다. 이 구조가 "MPA 의 단순함 + SPA 의 부드러움"을 동시에 노리는 RSC 의 설계 의도다. 다만 캐싱 계층이 여러 단(요청 메모이제이션, 데이터 캐시, 라우터 캐시)으로 나뉘어 멘탈 모델이 복잡하고, "왜 데이터가 갱신 안 되나" 또는 반대로 "왜 캐시가 안 먹나" 같은 디버깅이 RSC 학습 곡선의 가장 가파른 구간이다. 캐시 동작을 명시적으로 선언하고 재검증 경로를 단순하게 유지하는 것이 실무의 핵심 규율이다.

## 참고

- React Documentation — Server Components, "use client", Suspense
- React 18 Working Group — New Suspense SSR Architecture
- Next.js Documentation — Rendering: Server Components, Streaming
- Dan Abramov, RFC: React Server Components
