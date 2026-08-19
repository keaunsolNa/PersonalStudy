Notion 원본: https://app.notion.com/p/3c15a06fd6d381799db4ca5367e47fc2?pvs=204

# React Server Components 페이로드 직렬화와 스트리밍 경계

> 2026-08-19 신규 주제 · 확장 대상: React

## 학습 목표

- RSC 페이로드(Flight)의 행 포맷을 읽고 SSR HTML 과 무엇이 다른지 구분한다
- 직렬화 가능한 값의 경계를 판정해 `'use client'` 를 붙일 위치를 결정한다
- Suspense 경계가 스트리밍 청크 단위와 하이드레이션 순서에 미치는 영향을 추적한다
- Server Actions 의 요청 흐름과 재검증(revalidate) 경로를 캐시 계층별로 정리한다

## 1. SSR 과 RSC 는 다른 문제를 푼다

기존 SSR 은 컴포넌트를 서버에서 실행해 **HTML 문자열**을 만든다. 그 HTML 을 브라우저가 그리고, 같은 컴포넌트 코드를 클라이언트로도 내려보내 이벤트 핸들러를 붙인다(하이드레이션). 즉 컴포넌트 코드는 결국 클라이언트로 전부 간다. SSR 이 개선하는 것은 첫 페인트 시점이지 번들 크기가 아니다.

RSC 는 서버 전용 컴포넌트의 코드를 **클라이언트로 보내지 않는다**. 대신 그 컴포넌트가 렌더링한 결과를 직렬화된 트리로 보낸다. 마크다운 파서, 날짜 포맷 라이브러리, ORM 접근 코드가 서버에만 남으므로 번들이 줄어든다. 그리고 서버 컴포넌트는 async 함수일 수 있어 데이터 페칭을 컴포넌트 안에서 직접 한다.

```tsx
// app/posts/[id]/page.tsx — 서버 컴포넌트 (기본값)
import { db } from '@/lib/db';
import { renderMarkdown } from '@/lib/markdown';   // 무거운 라이브러리, 서버에만 존재
import { LikeButton } from './LikeButton';

export default async function PostPage({ params }: { params: { id: string } }) {
	const post = await db.post.findUnique({ where: { id: params.id } });
	if (!post) {
		return <NotFound />;
	}
	const html = renderMarkdown(post.body);

	return (
		<article>
			<h1>{post.title}</h1>
			<div dangerouslySetInnerHTML={{ __html: html }} />
			<LikeButton postId={post.id} initialCount={post.likeCount} />
		</article>
	);
}
```

`renderMarkdown` 이 아무리 커도 클라이언트 번들에는 0바이트다. `LikeButton` 만 클라이언트 컴포넌트로 내려간다.

## 2. Flight 페이로드의 행 구조

RSC 트리는 HTML 이 아니라 자체 포맷으로 직렬화된다. 이 스트림은 개행으로 구분된 행들이며 각 행은 `<id>:<tag><json>` 형태다.

```text
0:["$","div",null,{"className":"post","children":["$","h1",null,{"children":"제목"}]}]
1:I["./chunks/LikeButton.js",["chunk-a1b2.js"],"LikeButton"]
2:{"postId":"42","initialCount":17}
3:"2026-08-19T11:30:00.000Z"
```

행 태그의 의미는 이렇다.

- 태그 없음: 일반 모델(엘리먼트 트리, 값)
- `I`: 클라이언트 컴포넌트 참조. 모듈 경로·필요 청크·export 이름을 담는다
- `H`: 리소스 힌트(preload 등)
- `E`: 에러
- `$@n`: 아직 해결되지 않은 Promise 참조. 나중 행에서 채워진다

`"$"` 는 React 엘리먼트를 나타내는 마커이고, `["$", type, key, props]` 가 엘리먼트 하나다. 문자열 값 앞에 `$` 가 필요하면 `$$` 로 이스케이프한다.

여기서 결정적인 점은 `1:I[...]` 행이다. 서버는 클라이언트 컴포넌트를 **실행하지 않고** "이 모듈의 이 export 를, 이 props 로 렌더하라"는 참조만 남긴다. props(`2:` 행)는 직렬화되어 함께 간다. 브라우저 런타임이 이 행을 만나면 해당 청크를 동적 로드해 컴포넌트를 실행한다.

SSR 과의 관계는 보완이다. Next.js App Router 는 첫 요청에서 Flight 스트림을 만들고, 동시에 그것을 SSR 해 HTML 도 만든다. HTML 은 첫 페인트용, Flight 는 하이드레이션과 이후 내비게이션용이다. 그래서 응답 HTML 안에 `self.__next_f.push(...)` 형태로 Flight 행이 인라인된다.

## 3. 직렬화 경계 — 무엇을 넘길 수 있는가

서버 → 클라이언트 props 는 Flight 로 직렬화되어야 한다. 넘길 수 있는 것과 없는 것이 명확히 갈린다.

| 값 | 가능 | 비고 |
|---|---|---|
| 원시값, 배열, 평범한 객체 | 가능 | |
| `Date`, `Map`, `Set`, `BigInt` | 가능 | React 19 / Flight 가 지원 |
| `Promise` | 가능 | 클라이언트에서 `use()` 로 해제 |
| JSX 엘리먼트 | 가능 | children 으로 서버 트리 전달 |
| Server Action 함수 | 가능 | `'use server'` 표시된 것만 |
| 일반 함수, 콜백 | 불가 | 런타임 오류 |
| 클래스 인스턴스 | 불가 | 프로토타입 복원 불가 |
| `Symbol` (전역 심볼 제외) | 불가 | |
| 순환 참조 | 가능 | Flight 가 참조로 처리 |

가장 자주 부딪히는 것이 함수다. ORM 이 반환한 모델 인스턴스를 그대로 넘기면 클래스 인스턴스라 실패하거나, getter 가 사라진 평범한 객체로 변한다. 규칙은 **경계에서 명시적 DTO 로 변환**하는 것이다.

```tsx
// 나쁨: Prisma 모델 그대로
<UserCard user={user} />

// 좋음: 필요한 필드만 평범한 객체로
<UserCard user={{ id: user.id, name: user.name, avatarUrl: user.avatarUrl }} />
```

이 변환은 성능에도 직결된다. 넘긴 props 는 전부 페이로드에 실려 네트워크를 타고, 브라우저 메모리에도 남는다. 40개 필드짜리 모델을 통째로 넘기면 목록 100건에서 수백 KB 가 낭비된다.

## 4. 'use client' 의 의미와 배치 전략

`'use client'` 는 "이 파일부터 아래가 클라이언트 번들"이라고 선언하는 **경계 표시**다. 이 파일이 import 하는 모든 모듈이 클라이언트 번들에 포함된다. 즉 트리의 잎이 아니라 **경계**를 표시하는 것이므로 위치가 곧 번들 크기다.

```tsx
'use client';

import { useState, useOptimistic } from 'react';
import { toggleLike } from './actions';

export function LikeButton({ postId, initialCount }: {
	postId: string;
	initialCount: number;
}) {
	const [count, setCount] = useState(initialCount);
	const [optimistic, addOptimistic] = useOptimistic(count, (c, delta: number) => c + delta);

	return (
		<button
			onClick={async () => {
				addOptimistic(1);
				const next = await toggleLike(postId);
				setCount(next);
			}}
		>
			♥ {optimistic}
		</button>
	);
}
```

흔한 실수는 레이아웃 최상단에 `'use client'` 를 붙이는 것이다. 그러면 그 아래 전체가 클라이언트가 되어 RSC 이득이 사라진다. Context Provider 가 필요할 때 특히 이 함정에 빠진다. 해결은 Provider 만 얇은 클라이언트 컴포넌트로 만들고 `children` 을 서버에서 채우는 것이다.

```tsx
// providers.tsx
'use client';
export function Providers({ children }: { children: React.ReactNode }) {
	return <ThemeProvider><QueryProvider>{children}</QueryProvider></ThemeProvider>;
}

// layout.tsx (서버 컴포넌트)
export default function RootLayout({ children }: { children: React.ReactNode }) {
	return (
		<html lang="ko">
			<body>
				<Providers>{children}</Providers>
			</body>
		</html>
	);
}
```

`children` 은 서버에서 렌더링된 트리가 그대로 전달되므로, Provider 가 클라이언트여도 그 안의 자식들은 서버 컴포넌트로 남는다. 이 패턴이 RSC 아키텍처의 핵심 관용구다. 클라이언트 컴포넌트는 자기 자식을 직접 import 하지 말고 `children` 이나 props 로 받아야 서버 컴포넌트를 품을 수 있다.

## 5. Suspense 경계와 스트리밍 청크

RSC 스트림은 순차적으로 흐른다. 서버 컴포넌트가 `await` 로 멈추면 그 지점 이후가 늦어지는데, `<Suspense>` 로 감싸면 그 부분만 나중에 보내고 나머지는 먼저 흘린다.

```tsx
export default function Dashboard() {
	return (
		<div>
			<Header />
			<Suspense fallback={<StatsSkeleton />}>
				<Stats />          {/* 느린 집계 쿼리 */}
			</Suspense>
			<Suspense fallback={<FeedSkeleton />}>
				<Feed />           {/* 별도 API 호출 */}
			</Suspense>
		</div>
	);
}
```

스트림 관점에서 일어나는 일은 이렇다. 첫 청크에 Header 와 두 개의 fallback, 그리고 `$@1`, `$@2` 형태의 미해결 참조가 나간다. `Stats` 가 완료되면 `1:[...]` 행이 추가로 흘러 브라우저가 fallback 을 실제 내용으로 교체한다. 순서는 완료 순이지 선언 순이 아니다.

Suspense 경계가 없으면 전체가 가장 느린 컴포넌트를 기다린다. 반대로 경계를 너무 잘게 나누면 레이아웃 시프트가 누적되어 CLS 가 나빠진다. 실무 기준은 "시각적으로 독립된 블록 단위"다. 카드 하나하나가 아니라 카드 목록 전체를 하나의 경계로 묶는다.

병렬 페칭도 주의점이다. 서버 컴포넌트를 중첩하면 부모의 `await` 가 끝나야 자식이 시작되는 워터폴이 생긴다.

```tsx
// 워터폴: user 를 기다린 뒤 posts 시작
async function Profile({ id }: { id: string }) {
	const user = await getUser(id);
	const posts = await getPosts(id);
	return <>...</>;
}

// 병렬
async function Profile({ id }: { id: string }) {
	const [user, posts] = await Promise.all([getUser(id), getPosts(id)]);
	return <>...</>;
}
```

더 나은 형태는 Promise 를 그대로 자식에게 넘기고 자식이 `use()` 로 해제하는 것이다. 부모는 기다리지 않고 즉시 렌더링되며, 각 자식이 자기 데이터가 오는 대로 스트리밍된다.

```tsx
function Profile({ id }: { id: string }) {
	const userPromise = getUser(id);
	const postsPromise = getPosts(id);
	return (
		<>
			<Suspense fallback={<UserSkeleton />}>
				<UserInfo userPromise={userPromise} />
			</Suspense>
			<Suspense fallback={<PostsSkeleton />}>
				<PostList postsPromise={postsPromise} />
			</Suspense>
		</>
	);
}
```

## 6. 하이드레이션 순서와 선택적 하이드레이션

React 18 이후 하이드레이션은 Suspense 경계 단위로 나뉘고, **사용자 상호작용이 우선순위를 바꾼다**. 아직 하이드레이트되지 않은 영역을 클릭하면 React 가 이벤트를 기록해 두고 그 경계를 먼저 하이드레이트한 뒤 이벤트를 재생한다. 그래서 화면 하단의 무거운 컴포넌트가 상단 버튼의 반응성을 막지 않는다.

이 메커니즘이 제대로 동작하려면 경계가 존재해야 한다. Suspense 없이 전체를 한 덩어리로 두면 하이드레이션도 한 덩어리라 TTI 가 전체 번들 파싱에 묶인다. 즉 Suspense 경계는 스트리밍뿐 아니라 하이드레이션 분할의 단위이기도 하다.

하이드레이션 불일치는 여전히 오류다. 서버와 클라이언트가 다른 결과를 내는 대표적 원인은 `Date.now()`, `Math.random()`, `typeof window` 분기, 로케일 의존 포맷이다. 마지막 것이 특히 잦다. 서버는 UTC 로, 브라우저는 KST 로 날짜를 포맷하면 텍스트가 달라진다. 해결은 서버에서 ISO 문자열로 넘기고 클라이언트에서만 포맷하거나, `suppressHydrationWarning` 을 그 노드에만 붙이는 것이다.

## 7. Server Actions 의 요청 흐름

`'use server'` 가 붙은 함수는 클라이언트에서 호출 가능한 RPC 엔드포인트가 된다. 번들에는 함수 본문이 아니라 **ID 참조**만 들어간다.

```tsx
// actions.ts
'use server';

import { revalidatePath } from 'next/cache';
import { auth } from '@/lib/auth';
import { db } from '@/lib/db';

export async function toggleLike(postId: string): Promise<number> {
	const session = await auth();
	if (!session) {
		throw new Error('UNAUTHORIZED');
	}
	const updated = await db.$transaction(async (tx) => {
		const existing = await tx.like.findUnique({
			where: { postId_userId: { postId, userId: session.userId } },
		});
		if (existing) {
			await tx.like.delete({ where: { id: existing.id } });
			return tx.post.update({
				where: { id: postId },
				data: { likeCount: { decrement: 1 } },
			});
		}
		await tx.like.create({ data: { postId, userId: session.userId } });
		return tx.post.update({
			where: { id: postId },
			data: { likeCount: { increment: 1 } },
		});
	});
	revalidatePath(`/posts/${postId}`);
	return updated.likeCount;
}
```

호출 시 브라우저는 현재 페이지 URL 로 POST 를 보내고 헤더에 액션 ID 를 담는다. 응답은 반환값과, 재검증이 일어났다면 **갱신된 RSC 페이로드**를 함께 담은 스트림이다. 클라이언트는 별도 라우터 새로고침 없이 트리를 부분 갱신한다.

보안상 반드시 지킬 것이 두 가지다. 첫째, Server Action 은 공개 엔드포인트다. 클라이언트에서 렌더링되지 않는 조건 뒤에 숨겨도 누구나 직접 호출할 수 있다. **모든 액션 안에서 인증·인가를 다시 확인**해야 한다. 위 코드의 `auth()` 검사가 그것이다. 둘째, 입력을 신뢰하지 않는다. 클라이언트가 보내는 인자는 임의 값이므로 스키마 검증을 거친다.

```tsx
'use server';
import { z } from 'zod';

const schema = z.object({
	title: z.string().min(1).max(200),
	body: z.string().max(50_000),
});

export async function createPost(formData: FormData) {
	const parsed = schema.safeParse({
		title: formData.get('title'),
		body: formData.get('body'),
	});
	if (!parsed.success) {
		return { ok: false as const, errors: parsed.error.flatten().fieldErrors };
	}
	// ...
}
```

액션은 순차 실행된다. 같은 시점에 여러 액션을 호출하면 큐잉되어 하나씩 처리되므로, 목록의 여러 항목을 동시에 토글하는 UI 는 체감 지연이 생긴다. 이 경우 낙관적 업데이트(`useOptimistic`)로 UI 를 먼저 바꾸고 서버 확인을 뒤로 미룬다.

## 8. 캐시 계층과 재검증

App Router 에는 여러 캐시가 겹쳐 있고, 이를 구분하지 못하면 "데이터가 안 바뀐다" 는 문제를 진단할 수 없다.

- **Request Memoization**: 같은 렌더링 패스 안에서 동일한 `fetch` 를 중복 제거. 자동, 요청 종료 시 소멸
- **Data Cache**: `fetch` 결과를 서버에 영속 저장. `revalidate` 옵션과 태그로 제어
- **Full Route Cache**: 렌더링된 RSC 페이로드와 HTML 을 라우트 단위로 저장. 정적 라우트만
- **Router Cache**: 브라우저 메모리의 RSC 페이로드 캐시. 클라이언트 내비게이션 시 사용

`revalidatePath` 는 Data Cache 와 Full Route Cache 를 무효화하고, 응답 헤더로 Router Cache 도 갱신하게 만든다. 더 정밀하게는 태그를 쓴다.

```tsx
const res = await fetch(`${API}/posts/${id}`, { next: { tags: [`post:${id}`] } });
// 액션 안에서
revalidateTag(`post:${id}`);
```

태그 방식은 여러 경로에 흩어진 동일 데이터를 한 번에 무효화한다. 경로 기반은 어떤 경로가 그 데이터를 쓰는지 사람이 추적해야 하므로 규모가 커지면 누락이 생긴다.

동적 렌더링으로 강제 전환되는 조건도 알아야 한다. `cookies()`, `headers()`, `searchParams` 를 읽거나 `cache: 'no-store'` 를 쓰면 그 라우트는 요청마다 렌더링된다. 의도치 않게 최상위 레이아웃에서 `cookies()` 를 읽으면 사이트 전체가 동적이 되어 정적 캐시 이득이 사라진다. 빌드 로그의 라우트 표기(Static / Dynamic)로 매 배포마다 확인하는 것이 실무 습관이다.

## 참고

- React Documentation — Server Components, `'use client'`, `'use server'`
- React RFC — React Server Components (reactjs/rfcs #188)
- Next.js Documentation — Caching, Data Fetching, Server Actions and Mutations
- facebook/react 저장소 — `react-server-dom-webpack` 의 Flight 프로토콜 구현
- React Documentation — `<Suspense>`, `useOptimistic`, `use`
