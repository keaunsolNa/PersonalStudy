Notion 원본: https://www.notion.so/39f5a06fd6d381c38182fe46b6fcaf73

# React Server Components 렌더링 모델과 직렬화 경계 및 Next.js PPR

> 2026-07-16 신규 주제 · 확장 대상: React / Next.js

## 학습 목표

- RSC Payload 의 플라이트 포맷과 HTML 스트리밍의 차이를 구분한다
- 서버·클라이언트 경계를 넘는 props 의 직렬화 제약을 나열한다
- `"use client"` 가 컴포넌트가 아니라 모듈 그래프 진입점임을 확인한다
- PPR 의 정적 셹과 동적 홀 분리를 빌드 산출물 수준에서 해석한다

## 3줄 선행 정리

Pages Router 에 익숙하면 App Router 를 "폴더 구조가 바뀐 것" 으로 오해하기 쉽다. 실제로 바뀜 것은 **컴포넌트가 어디서 실행되는가** 다. `getServerSideProps` 는 페이지 단위로 서버에서 데이터를 가져와 컴포넌트에 props 로 넘기는 구조였다. RSC 는 컴포넌트 자체가 서버에서 실행된다.

## 1. SSR 과 RSC 는 다른 문제를 푸는다

가장 흔한 오해가 "RSC 는 SSR 의 개선판" 이라는 것이다. 둘은 목적이 다르다.

**SSR** 은 초기 HTML 을 서버에서 만들어 First Contentful Paint 를 앞당긴다. 하지만 그 컴포넌트의 코드는 **여전히 클라이언트 번들에 포함**된다. 하이드레이션 때 같은 컴포넌트를 브라우저에서 다시 실행해 이벤트 핸들러를 붙여야 하기 때문이다. HTML 을 먼저 보여줬을 뿐, 다운로드해야 할 JS 는 그대로다.

**RSC** 는 컴포넌트를 서버에서만 실행하고 **클라이언트 번들에서 제외**한다. 하이드레이션 대상이 아니다. 브라우저는 그 코드를 영원히 보지 않는다.

```tsx
// app/posts/[id]/page.tsx — Server Component (기본값)
import { marked } from "marked";          // 40KB
import sanitizeHtml from "sanitize-html"; // 60KB
import { db } from "@/lib/db";

export default async function PostPage({ params }: { params: { id: string } }) {
	const post = await db.post.findUnique({ where: { id: params.id } });
	const html = sanitizeHtml(marked(post.content));

	return <article dangerouslySetInnerHTML={{ __html: html }} />;
}
```

`marked`, `sanitize-html`, `db` 전부 클라이언트 번들에 들어가지 않는다. 브라우저가 받는 것은 렌더링 결과뿐이다. 100KB 이상의 라이브러리가 사라진다.

Pages Router 에서 같은 것을 하려면 `getServerSideProps` 에서 마크다운을 변환해 HTML 문자열을 props 로 넘겨야 했다. RSC 는 컴포넌트 안에서 직접 한다. 데이터 페칭과 렌더링이 같은 자리에 있다.

**두 번째 차이는 폭포수(waterfall) 제거다.**

```tsx
// 클라이언트에서 하면 3번의 왕복
// 1. 페이지 로드 → 2. user 조회 → 3. orders 조회

// 서버에서 하면 왕복 0번 (DB 는 같은 네트워크 안)
export default async function Dashboard({ userId }: { userId: string }) {
	const user = await db.user.findUnique({ where: { id: userId } });
	const orders = await db.order.findMany({ where: { userId: user.id } });
	return <OrderList user={user} orders={orders} />;
}
```

DB 와 서버 사이의 지연은 1ms 수준이고, 브라우저와 서버 사이는 50~200ms 다. 중첩 데이터 의존성이 있는 화면에서 이 차이가 결정적이다.

## 2. RSC Payload — HTML 이 아닌 것

서버 컴포넌트의 렌더링 결과는 HTML 이 아니다. **RSC Payload**(플라이트 포맷)라는 특수 직렬화 형식이다.

```
0:D{"name":"PostPage","env":"Server"}
1:I["./chunk-abc.js","LikeButton"]
2:["$","article",null,{"className":"post"}]
0:["$","div",null,{"children":[["$","h1",null,{"children":"제목"}],["$","$L1",null,{"postId":"42"}]]}]
```

각 줄은 청크다. 접두사가 종류를 나타낸다.

| 접두사 | 의미 |
|---|---|
| `I` | Client Reference — 클라이언트 컴포넌트의 청크 경로와 export 이름 |
| `$L1` | 위 `I` 청크에 대한 참조 (lazy) |
| `D` | 디버그 정보 (개발 모드) |
| `E` | 에러 |
| `$` | React Element 마커 |
| 숫자 | 청크 ID |

핵심은 `1:I["./chunk-abc.js","LikeButton"]` 이다. 서버는 `LikeButton` 을 **렌더링하지 않는다.** "이 자리에 이 청크의 이 컴포넌트를 놓아라" 는 지시만 남긴다. 브라우저가 해당 청크를 받아 렌더링한다.

**왜 HTML 이 아니라 이 포맷인가.** 세 가지 이유다.

첫째, **컴포넌트 트리 구조를 보존해야 한다.** HTML 로 만들면 클라이언트 컴포넌트가 어디에 들어가야 하는지 표시할 방법이 없다. RSC Payload 는 React Element 트리를 그대로 표현한다.

둘째, **클라이언트 상태를 파괴하지 않는 갱신이 가능하다.** 네비게이션 시 서버가 새 Payload 를 보내면 React 가 기존 트리와 재조정(reconcile)한다. 클라이언트 컴포넌트의 `useState` 가 유지된다. HTML 을 통째로 갈아끼우면 상태가 전부 날아간다.

셋째, **스트리밍이 자연스럽다.** 청크 단위로 도착하는 대로 처리한다.

**두 개의 스트림이 동시에 흐른다.** 최초 로드 시 Next.js 는 RSC Payload 로 HTML 을 만들어 보내면서, 그 Payload 자체도 `<script>` 태그로 인라인해 함께 보낸다.

```html
<!DOCTYPE html>
<html>
<body>
  <article>렌더링된 내용</article>
  <script>self.__next_f.push([1,"0:[\"$\",\"div\",null,{...}]\n"])</script>
</body>
</html>
```

HTML 은 즉시 보이기 위한 것이고, 인라인된 Payload 는 하이드레이션 시 React 가 트리를 복원하기 위한 것이다. 이후 클라이언트 사이드 네비게이션에서는 HTML 없이 Payload 만 오간다.

## 3. 직렬화 경계 — 무엇이 넘어갈 수 있는가

서버 컴포넌트에서 클라이언트 컴포넌트로 props 를 넘기려면 **직렬화 가능**해야 한다. Payload 에 담아야 하기 때문이다.

**넘어가는 것**

```tsx
<ClientComp
	str="text"
	num={42}
	big={9007199254740993n}
	bool={true}
	nul={null}
	undef={undefined}
	arr={[1, 2, 3]}
	obj={{ a: 1, nested: { b: 2 } }}
	date={new Date()}
	map={new Map([["k", "v"]])}
	set={new Set([1, 2])}
	typed={new Uint8Array([1, 2, 3])}
	promise={fetchData()}        // Promise 도 가능 — use() 로 받는다
	element={<ServerChild />}    // React Element 가능
/>
```

**안 넘어가는 것**

```tsx
<ClientComp
	fn={() => alert("hi")}       // 함수 (Server Action 제외)
	cls={new MyClass()}          // 클래스 인스턴스 (프로토타입 유실)
	sym={Symbol("s")}            // 등록되지 않은 심볼
	err={new Error("x")}         // 에러 객체
/>
// Error: Functions cannot be passed directly to Client Components
// unless you explicitly expose it by marking it with "use server".
```

**클래스 인스턴스가 안 되는 것**이 실무에서 자주 걸린다. Prisma 나 TypeORM 이 반환하는 엔티티는 클래스 인스턴스일 수 있고, 그대로 넘기면 메서드가 사라지거나 에러가 난다. 명시적으로 plain object 로 변환해야 한다.

```tsx
// 위험
const user = await prisma.user.findUnique({ where: { id } });
return <Profile user={user} />;   // Decimal, Date 등 특수 타입 주의

// 안전 — 필요한 필드만 명시적으로 뽑는다
return <Profile user={{ id: user.id, name: user.name, email: user.email }} />;
```

**Promise 를 넘길 수 있다**는 점이 강력하다. 서버에서 await 하지 않고 Promise 를 그대로 넘기면, 클라이언트가 `use()` 로 받는다. 서버는 그 데이터를 기다리지 않고 나머지를 먼저 보낼 수 있다.

```tsx
// Server Component — await 하지 않는다
export default function Page() {
	const commentsPromise = fetchComments();   // await 없음
	return (
		<>
			<Article />
			<Suspense fallback={<Skeleton />}>
				<Comments promise={commentsPromise} />
			</Suspense>
		</>
	);
}

// Client Component
"use client";
import { use } from "react";

function Comments({ promise }: { promise: Promise<Comment[]> }) {
	const comments = use(promise);   // Suspense 로 대기
	return <ul>{comments.map(c => <li key={c.id}>{c.text}</li>)}</ul>;
}
```

**함수는 Server Action 으로만 넘어간다.**

```tsx
// app/actions.ts
"use server";

export async function likePost(postId: string) {
	await db.like.create({ data: { postId } });
	revalidatePath(`/posts/${postId}`);
}

// Server Component
import { likePost } from "./actions";
<LikeButton action={likePost} postId="42" />

// Client Component
"use client";
function LikeButton({ action, postId }: { action: (id: string) => Promise<void>; postId: string }) {
	return <button onClick={() => action(postId)}>좋아요</button>;
}
```

넘어가는 것은 함수 자체가 아니라 **액션 ID 참조**다. 클라이언트가 호출하면 그 ID 로 POST 요청이 나가고, 서버가 해당 함수를 실행한다. 사실상 자동 생성된 RPC 엔드포인트다.

**보안 주의**: Server Action 은 공개 엔드포인트다. 클라이언트가 임의 인자로 호출할 수 있으므로 액션 내부에서 반드시 인증·인가·검증을 해야 한다.

```tsx
"use server";
import { z } from "zod";

const schema = z.object({ postId: z.string().uuid() });

export async function likePost(input: unknown) {
	const session = await auth();
	if (!session) throw new Error("Unauthorized");   // 인증 필수

	const { postId } = schema.parse(input);          // 검증 필수
	await db.like.create({ data: { postId, userId: session.userId } });
}
```

Spring 에서 `@PostMapping` 에 `@PreAuthorize` 와 `@Valid` 를 붙이는 것과 정확히 같은 책임이다. 함수처럼 보인다고 내부 호출로 착각하면 안 된다.

## 4. "use client" 는 모듈 그래프의 진입점이다

가장 오해가 많은 지점이다. `"use client"` 는 "이 컴포넌트를 클라이언트에서 실행" 이 아니라 **"여기부터 클라이언트 모듈 그래프 시작"** 을 뜻한다.

```tsx
// app/components/Chart.tsx
"use client";
import { Line } from "react-chartjs-2";   // 이 모듈도 클라이언트 그래프에 포함
import { formatDate } from "@/lib/utils"; // 이것도
import { heavyCalc } from "@/lib/math";   // 이것도

export function Chart({ data }: { data: Point[] }) { ... }
```

`"use client"` 는 파일 최상단에 한 번만 쓰면 되고, 그 파일이 import 하는 **모든 모듈이 자동으로 클라이언트 번들에 포함**된다. 하위 컴포넌트에 다시 붙일 필요가 없다.

**따라서 경계를 잘못 그으면 번들이 폭발한다.**

```tsx
// 나쁨 — 페이지 전체가 클라이언트로 넘어간다
"use client";
import { db } from "@/lib/db";       // 서버 전용인데 클라이언트 그래프에!
export default function Page() {
	const [tab, setTab] = useState("a");   // 이 하나 때문에
	return <div>{/* 거대한 트리 전체 */}</div>;
}

// 좋음 — 상태를 쓰는 최소 단위만 클라이언트
// page.tsx (Server)
export default async function Page() {
	const data = await db.query();
	return (
		<div>
			<Header />                    {/* Server */}
			<Tabs>                        {/* Client — 상태만 담당 */}
				<Content data={data} />   {/* Server — children 으로 주입 */}
			</Tabs>
			<Footer />                    {/* Server */}
		</div>
	);
}
```

**children 패턴**이 핵심 기법이다. 클라이언트 컴포넌트가 서버 컴포넌트를 `import` 할 수는 없지만, `children` 이나 props 로 **받을 수는 있다.**

```tsx
// Tabs.tsx
"use client";
export function Tabs({ children }: { children: React.ReactNode }) {
	const [active, setActive] = useState(0);
	return (
		<div>
			<nav>{/* 탭 버튼 */}</nav>
			<div>{children}</div>   {/* 이미 서버에서 렌더링된 결과가 들어온다 */}
		</div>
	);
}
```

이것이 가능한 이유는 §2 의 Payload 구조다. 서버가 `Content` 를 렌더링한 결과를 `Tabs` 의 `children` 슬롯에 넣어 Payload 에 담는다. `Tabs` 는 그 결과를 받아 배치만 한다. `Tabs` 의 코드는 `Content` 를 전혀 모른다.

**Context Provider 도 같은 패턴으로 처리한다.**

```tsx
// providers.tsx
"use client";
export function Providers({ children }: { children: React.ReactNode }) {
	return (
		<ThemeProvider>
			<QueryClientProvider client={queryClient}>
				{children}
			</QueryClientProvider>
		</ThemeProvider>
	);
}

// layout.tsx (Server)
export default function RootLayout({ children }: { children: React.ReactNode }) {
	return (
		<html>
			<body>
				<Providers>{children}</Providers>   {/* children 은 여전히 Server */}
			</body>
		</html>
	);
}
```

`Providers` 가 클라이언트여도 `children` 으로 들어오는 페이지는 서버 컴포넌트로 남는다.

**서버 전용 코드 보호**는 `server-only` 패키지로 한다.

```tsx
// lib/db.ts
import "server-only";   // 클라이언트 그래프에 포함되면 빌드 에러

export const db = new PrismaClient();
```

실수로 클라이언트 컴포넌트가 이 모듈을 import 하면 빌드가 실패한다. DB 커넥션 문자열이 번들에 새어 나가는 사고를 원천 차단한다. 반대 방향은 `client-only` 다.

## 5. Suspense 와 스트리밍

RSC 는 Suspense 로 부분 스트리밍한다.

```tsx
export default function Page() {
	return (
		<div>
			<Header />                                {/* 즉시 */}
			<Suspense fallback={<ProductSkeleton />}>
				<ProductInfo />                       {/* 100ms */}
			</Suspense>
			<Suspense fallback={<ReviewSkeleton />}>
				<Reviews />                           {/* 2000ms */}
			</Suspense>
		</div>
	);
}
```

응답이 이렇게 흐른다.

```
t=0ms    : <div><header>...</header><div id="S1">스켈레톤</div><div id="S2">스켈레톤</div>
t=100ms  : <script>$RC("S1", ...)</script>  ← ProductInfo 도착, 스켈레톤 교체
t=2000ms : <script>$RC("S2", ...)</script>  ← Reviews 도착
```

`$RC` 는 React 가 인라인하는 작은 함수로, 도착한 콘텐츠로 플레이스홀더를 교체한다. **Reviews 가 2초 걸려도 나머지는 즉시 보인다.** Pages Router 의 `getServerSideProps` 는 모든 데이터가 준비될 때까지 아무것도 못 보냈다. 가장 느린 쿼리가 전체를 잡아먹었다.

**Suspense 경계 설계 원칙**은 "지연 시간이 다른 것을 분리" 다. 캐시에서 오는 것과 외부 API 를 타는 것을 같은 경계에 두면 빠른 쪽이 느린 쪽을 기다린다.

**loading.tsx** 는 페이지 전체를 감싸는 Suspense 의 축약이다.

```
app/
  posts/
    loading.tsx   ← 자동으로 page.tsx 를 <Suspense> 로 감싼다
    page.tsx
```

## 6. PPR — 정적 셹과 동적 홀

**Partial Prerendering** 은 정적 생성(SSG)과 동적 렌더링(SSR)의 이분법을 깨다. Next.js 14 실험적 도입, 15 에서 발전 중이며 2026년 7월 현재도 실험 단계다.

문제 상황은 이렇다. 상품 페이지의 99% 는 모든 사용자에게 같지만, 장바구니 아이콘의 개수만 사용자별로 다르다. 기존에는 선택지가 둘뿐이었다. 페이지 전체를 동적으로 만들거나(CDN 캐시 포기), 장바구니를 클라이언트에서 fetch 하거나(추가 왕복 + 레이아웃 시프트).

PPR 은 **한 페이지 안에서 정적 부분과 동적 부분을 공존**시킨다.

```tsx
// next.config.js
module.exports = { experimental: { ppr: "incremental" } };

// app/products/[id]/page.tsx
export const experimental_ppr = true;

export default function ProductPage({ params }: { params: { id: string } }) {
	return (
		<main>
			<Nav />                                   {/* 정적 */}
			<ProductDetails id={params.id} />         {/* 정적 — 빌드 시 생성 */}
			<Suspense fallback={<CartSkeleton />}>
				<CartCount />                         {/* 동적 — cookies() 사용 */}
			</Suspense>
			<Suspense fallback={<RecsSkeleton />}>
				<Recommendations userId={...} />      {/* 동적 */}
			</Suspense>
			<Footer />                                {/* 정적 */}
		</main>
	);
}
```

**빌드 시 동작**이 핵심이다. Next.js 는 페이지를 렌더링하다가 `cookies()`, `headers()`, `searchParams` 같은 동적 API 를 만나면 그 Suspense 경계를 **홀(hole)로 남기고** 나머지를 정적 HTML 로 굳힌다.

```
빌드 산출물:
  .next/server/app/products/[id].html   ← 정적 셹 (홀 = fallback 스켈레톤)
  .next/server/app/products/[id].rsc    ← RSC Payload
  + 동적 홀을 채울 postponed 상태 직렬화
```

**런타임 동작**

```
1. CDN 이 정적 셹을 즉시 반환 (TTFB ~10ms)
   → 사용자는 상품 정보를 바로 본다
2. 같은 응답 스트림으로 서버가 동적 부분 렌더링을 재개(resume)
3. 준비되는 대로 홀을 채워 스트리밍
```

**단일 HTTP 응답**이라는 점이 결정적이다. 클라이언트가 추가 요청을 보내지 않는다. React 의 `prerender`/`resume` API 가 이를 가능하게 한다. 렌더링을 중단(postpone)했다가 나중에 그 지점부터 이어서 하는 능력이다.

| 전략 | TTFB | CDN 캐시 | 개인화 | 추가 왕복 |
|---|---|---|---|---|
| SSG | 최저 | 전체 | 불가 | 0 |
| SSR | 높음 | 불가 | 가능 | 0 |
| SSG + 클라이언트 fetch | 최저 | 전체 | 가능 | 1회 |
| ISR | 낮음 | 전체 | 불가 | 0 |
| **PPR** | 최저 | 정적 부분 | 가능 | 0 |

**제약과 현실**

PPR 은 실험 기능이다. `ppr: "incremental"` 로 페이지별 opt-in 이 가능하므로 점진 도입이 가능하지만, 프로덕션 채택 전 확인할 것이 있다.

동적 API 를 Suspense 로 감싸지 않으면 페이지 전체가 동적이 된다. 빌드 시 경고가 나오므로 확인해야 한다. 정적 셹이 캐시되므로 `revalidate` 전략을 별도로 설계해야 한다. 그리고 셀프 호스팅 환경에서는 CDN 계층을 직접 구성해야 이득이 난다. Vercel 이 아니면 설정 부담이 있다.

## 7. 캐싱 계층 — Next.js 15 의 변화

App Router 는 캐시가 네 겹이다. 처음 만나면 혼란스러운 부분이다.

| 캐시 | 위치 | 대상 | 지속 |
|---|---|---|---|
| Request Memoization | 서버 | 같은 렌더 패스의 중복 fetch | 요청 단위 |
| Data Cache | 서버 | fetch 결과 | 배포 넘어 지속 |
| Full Route Cache | 서버 | 정적 라우트의 HTML/Payload | 배포 단위 |
| Router Cache | 클라이언트 | RSC Payload | 세션 단위 |

**Next.js 15 에서 기본값이 바뀜다.** 14 까지는 `fetch` 가 기본 캐시(`force-cache`)였다. 이것이 "왜 데이터가 갱신되지 않는가" 라는 대량의 혼란을 낳았다. 15 부터 기본이 `no-store` 다. 캐시하려면 명시해야 한다.

```tsx
// Next.js 15 — 명시적 캐시
const res = await fetch(url, { cache: "force-cache" });
const res2 = await fetch(url, { next: { revalidate: 3600 } });
const res3 = await fetch(url, { next: { tags: ["products"] } });

// 태그 기반 무효화
import { revalidateTag } from "next/cache";
revalidateTag("products");
```

**Request Memoization** 은 별개다. 같은 렌더 패스에서 같은 URL 을 여러 번 fetch 하면 한 번만 나간다. 컴포넌트마다 필요한 데이터를 각자 fetch 하는 RSC 스타일을 가능하게 하는 장치다. props drilling 없이 각 컴포넌트가 자기 데이터를 가져와도 중복 요청이 없다.

```tsx
// 세 컴포넌트가 각자 fetch 해도 실제 HTTP 요청은 1회
async function Header() { const u = await fetch("/api/user"); ... }
async function Sidebar() { const u = await fetch("/api/user"); ... }
async function Content() { const u = await fetch("/api/user"); ... }
```

단 `fetch` 에만 적용된다. Prisma 같은 DB 클라이언트는 해당하지 않으므로 React 의 `cache()` 로 감싼다.

```tsx
import { cache } from "react";

export const getUser = cache(async (id: string) => {
	return db.user.findUnique({ where: { id } });
});
```

## 8. 마이그레이션 판단과 실무 지침

**Pages Router 에서 옮길 가치가 있는가**

옮길 이유가 명확한 경우는 번들 크기가 문제이고 서버 전용 로직(마크다운, 날짜 포맷, 대형 유틸)이 클라이언트로 새고 있을 때, 데이터 폭포수가 실측 병목일 때, 화면 일부만 느려서 전체가 대기하는 문제가 있을 때다.

옮길 이유가 약한 경우는 대부분이 클라이언트 상호작용인 대시보드형 앱, 이미 번들이 충분히 작은 경우, 팀이 RSC 를 학습할 여력이 없는 경우다. App Router 는 개념 부담이 크고, 캐싱 계층 네 겹은 실제로 혼란을 유발한다.

**경계 설계 체크리스트**

`"use client"` 를 붙이기 전에 이 컴포넌트가 정말 브라우저 API 나 상태가 필요한지 확인한다. 필요하다면 그 부분만 최소 단위로 잘라낸다. 클라이언트 컴포넌트가 서버 데이터를 필요로 하면 `children` 이나 props 로 주입받는다. 서버 전용 모듈에는 `server-only` 를 붙인다. Server Action 에는 인증과 검증을 반드시 넣는다.

**번들 확인**

```bash
ANALYZE=true next build
# 또는
npx @next/bundle-analyzer
```

빌드 출력의 First Load JS 를 본다. 클라이언트 컴포넌트로 표시된 청크에 서버 전용이어야 할 라이브러리가 들어 있으면 경계가 잘못 그어진 것이다.

**RSC Payload 직접 확인**

```bash
curl -H "RSC: 1" https://localhost:3000/posts/42
```

`RSC: 1` 헤더를 붙이면 HTML 대신 Payload 가 온다. 어떤 컴포넌트가 클라이언트 참조(`I` 청크)로 남았는지 육안으로 확인할 수 있다. 경계 설계를 검증하는 가장 직접적인 방법이다.

## 참고

- React Docs — Server Components (https://react.dev/reference/rsc/server-components)
- React Docs — Server Functions, `use` API
- Next.js Docs — App Router, Caching, Partial Prerendering (https://nextjs.org/docs/app)
- RFC: React Server Components (https://github.com/reactjs/rfcs/blob/main/text/0188-server-components.md)
- React Source — `react-server-dom-webpack` 플라이트 포맷 구현
- Next.js Blog — Partial Prerendering 소개 및 Next.js 15 캐싱 기본값 변경 노트
