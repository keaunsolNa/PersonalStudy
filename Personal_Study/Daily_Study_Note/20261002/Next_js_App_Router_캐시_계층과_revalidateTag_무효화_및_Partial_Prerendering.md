Notion 원본: https://www.notion.so/3ed5a06fd6d381b2be82c4e780d5e946

# Next.js App Router 캐시 계층과 revalidateTag 무효화 및 Partial Prerendering

> 2026-10-02 신규 주제 · 확장 대상: Next.js

## 학습 목표

- App Router의 캐시 계층(요청 메모이제이션, 데이터 캐시, 풀 라우트 캐시, 라우터 캐시)을 구분하고 각 계층의 수명을 추적한다.
- `revalidateTag`, `updateTag`, `revalidatePath`를 상황별로 골라 stale-while-revalidate와 즉시 만료를 설계한다.
- `'use cache'`, `cacheTag`, `cacheLife`로 데이터 단위와 UI 단위 캐시를 작성한다.
- Partial Prerendering의 정적 셸과 Suspense 경계를 배치해 정적 영역과 동적 영역을 분리한다.

## 1. 캐시 계층 지도: 요청마다 어디에 값이 머무는가

Next.js App Router의 "캐시"는 하나의 저장소가 아니라 위치와 수명이 다른 여러 층의 합이다. 이 층을 구분하지 못하면 "분명히 무효화했는데 화면이 안 바뀐다"는 증상을 디버깅할 수 없다. 전통적인 설명은 네 층이다. 요청 메모이제이션은 한 번의 서버 렌더 안에서 같은 `fetch`(또는 React `cache` 함수) 호출을 한 번만 실행하며 렌더가 끝나면 사라진다. 데이터 캐시는 `fetch`나 `unstable_cache` 결과를 서버에 저장하고 태그 기반 무효화가 작동하는 곳이다. 풀 라우트 캐시는 정적 라우트의 HTML과 RSC Payload를 저장한다. 라우터 캐시는 브라우저 메모리에 RSC Payload를 보관해 뒤로 가기와 프리페치의 서버 왕복을 줄인다.

| 계층 | 위치 | 범위 | 대표적인 무효화 수단 |
|---|---|---|---|
| 요청 메모이제이션 | 서버 메모리 | 단일 렌더 패스 | 렌더 종료 시 자동 소멸 |
| 데이터 캐시 | 서버 저장소 | 요청과 배포를 넘어 유지될 수 있음 | `revalidateTag`, `revalidatePath`, 시간 기반 `revalidate` |
| 풀 라우트 캐시 | 서버 디스크 또는 플랫폼 저장소 | 정적 라우트의 HTML과 RSC Payload | 재검증, 재배포 |
| 라우터 캐시 | 브라우저 메모리 | 사용자 세션 | 새로고침, `router.refresh()`, 서버 액션 내 재검증 |

Next 16의 Cache Components 모델에서는 저장 위치를 프리렌더된 HTML, 공유 저장소(서버 인스턴스 메모리 또는 원격 캐시 핸들러), 브라우저의 세 곳으로 다시 정리한다. 흔한 혼선은 서버에서 태그를 무효화했는데 브라우저에 남은 오래된 페이로드가 보이는 경우다. 서버 액션 안의 재검증 호출은 클라이언트 라우터 캐시까지 갱신하도록 설계되어 있지만, 외부 웹훅이 Route Handler로 들어와 일으킨 무효화는 이미 열려 있는 탭을 직접 갱신하지 못한다.

## 2. Next 14, 15, 16의 기본값 변화와 버전 가드

캐시 코드를 읽을 때 가장 먼저 확인할 것은 프로젝트의 Next.js 버전이다. 같은 `fetch('https://...')` 한 줄이 버전에 따라 정반대로 동작하기 때문이다. Next 14에서는 `fetch`가 기본적으로 캐시되었고 `GET` Route Handler도 정적으로 평가될 수 있었다. Next 15에서는 기본값이 뒤집혀 `fetch`와 `GET` Route Handler가 기본 캐시 대상에서 빠졌고, PPR은 `experimental.ppr`로 접근하는 실험 기능이었다. Next 16에서는 `cacheComponents`와 `'use cache'` 중심의 새 모델이 등장했다. 아래는 공식 문서와 릴리스 노트에서 확인되는 큰 흐름이며 마이너 버전 단위 동작은 해당 버전 문서로 재확인해야 한다.

| 항목 | Next 14 | Next 15 | Next 16 (현재 문서 16.x 기준) |
|---|---|---|---|
| `fetch` 기본 | 캐시됨 | 캐시 안 됨 | 캐시 안 됨, `force-cache`로 옵트인 |
| PPR 활성화 | 실험적 도입 | `experimental.ppr`(실험) | `cacheComponents: true`에서 기본 동작 |
| 함수 단위 캐시 | `unstable_cache` | `unstable_cache` | `'use cache'` 권장, `unstable_cache`도 이전 모델 문서에 존속 |
| `revalidateTag` 인자 | 태그 1개 | 태그 1개 | 두 번째 인자(profile) 권장, 1개 인자는 deprecated |
| 즉시 만료 API | 없음 | 없음 | `updateTag`(서버 액션 전용) |

Cache Components를 켜면 `dynamic`, `revalidate`, `fetchCache` 같은 라우트 세그먼트 설정과 충돌하거나 동작이 달라지는 부분이 있으므로, 켜기 전에 설정 문서의 호환성 안내를 읽고 `next.config`의 `cacheComponents`와 `experimental` 블록을 먼저 점검한다.

## 3. 이전 모델의 태그와 시간 기반 재검증

Cache Components를 쓰지 않는 프로젝트는 `fetch` 옵션, `unstable_cache`, 라우트 세그먼트 설정으로 캐시를 다룬다. `fetch`에는 `next.revalidate`(초)와 `next.tags`를 지정하고, `fetch`가 아닌 DB 호출은 `unstable_cache`(`tags`, `revalidate` 옵션)로 감싼다.

```ts
// app/lib/data.ts  (Cache Components 미사용 프로젝트)
export async function getPosts() {
  const res = await fetch('https://api.example.com/posts', {
    next: { revalidate: 3600, tags: ['posts'] },
  })
  return res.json()
}
```

시간 기반은 "최대 이만큼은 낡아도 된다"는 안전망이고 태그는 "변경 즉시 알린다"는 신호이므로 함께 쓴다. 라우트 세그먼트의 `revalidate`는 `600`처럼 정적 리터럴이어야 한다고 현재 문서에 명시되어 있다.

## 4. revalidateTag, updateTag, revalidatePath 선택 기준

무효화 API는 세 개이고, 각자 답하는 질문이 다르다. `revalidateTag(tag, profile)`는 "이 태그가 붙은 데이터가 낡았음을 표시하라"이며 stale-while-revalidate 의미를 가진다. 현재 문서(16.x) 기준 시그니처는 `revalidateTag(tag: string, profile: string | { expire?: number }): void`이고, 권장 값은 `'max'`다. 호출 직후 다음 요청은 낡은 값을 받으면서 백그라운드에서 재검증이 시작되고, 두 번째 인자가 허용한 시간을 넘기면 요청이 재검증 완료까지 블록된다. 재검증은 호출 자체가 아니라 요청에 의해 일어나므로 태그를 쓰는 페이지들은 방문되는 순서대로 점진적으로 갱신된다.

`{ expire: 0 }`을 주면 낡은 값을 제공하지 않고 다음 요청이 블로킹 재검증이 된다. 인자 없는 단일 인자 형태는 deprecated이며 `{ expire: 0 }`과 같게 동작한다. `revalidateTag`는 Server Function과 Route Handler에서 호출할 수 있고 Client Component나 Proxy에서는 쓸 수 없다. `updateTag(tag)`는 서버 액션 안에서만 호출 가능하며 태그를 즉시 만료시켜 다음 요청이 새 값을 기다리게 하므로, 작성자가 자기 글을 곧바로 보아야 하는 read-your-own-writes에 맞다. `revalidatePath`는 태그가 아닌 경로(페이지 또는 레이아웃)를 무효화한다.

| 상황 | 권장 호출 | 이유 |
|---|---|---|
| 서버 액션에서 글 작성 후 목록 즉시 반영 | `updateTag('posts')` | 낡은 값을 주지 않아 본인 변경이 바로 보임 |
| CMS 웹훅(Route Handler)으로 콘텐츠 갱신 | `revalidateTag('posts', 'max')` | 응답 지연 없이 SWR, `updateTag`는 사용 불가 |
| 웹훅에서 낡은 값을 허용할 수 없음 | `revalidateTag('price', { expire: 0 })` | 다음 요청이 블로킹 재검증 |

서버 액션은 본인에게 즉시 반영하고, 웹훅은 외부 변경을 SWR로 전파한다. 웹훅에는 반드시 시크릿 검증을 두어야 한다. 검증 없는 재검증 엔드포인트는 누구나 캐시를 비우게 하는 공격면이다.

```ts
// app/actions.ts
'use server'
import { updateTag } from 'next/cache'
import { redirect } from 'next/navigation'

export async function createPost(formData: FormData) {
  const post = await db.post.create({ data: { title: String(formData.get('title')) } })
  updateTag('posts')
  updateTag(`post-${post.id}`)
  redirect(`/posts/${post.id}`)
}
```

```ts
// app/api/revalidate/route.ts
import { revalidateTag } from 'next/cache'
import type { NextRequest } from 'next/server'

export async function POST(req: NextRequest) {
  if (req.headers.get('x-webhook-secret') !== process.env.REVALIDATE_SECRET) {
    return Response.json({ ok: false }, { status: 401 })
  }
  const { tag } = await req.json()
  if (typeof tag !== 'string' || tag.length > 256) {
    return Response.json({ ok: false }, { status: 400 })
  }
  revalidateTag(tag, 'max')
  return Response.json({ ok: true, now: Date.now() })
}
```

태그는 대소문자를 구분하고 256자를 넘으면 무효화해도 아무 일도 일어나지 않는다고 문서에 적혀 있다. `posts`(목록), `post-<id>`(단건)처럼 두 단계로 두면 부분 갱신과 전체 갱신을 모두 표현할 수 있다.

## 5. Cache Components: use cache, cacheTag, cacheLife

Next 16에서 `next.config`에 `cacheComponents: true`를 설정하면 새 캐시 모델이 켜진다. 핵심은 `'use cache'` 지시어다. 비동기 함수나 컴포넌트, 혹은 파일 전체의 반환값을 캐시하며, 인자와 클로저로 캡처된 값이 자동으로 캐시 키의 일부가 된다. 데이터 단위로 쓰면 여러 컴포넌트가 공유하는 쿼리를 캐시하고, UI 단위로 쓰면 컴포넌트나 페이지 전체의 렌더 결과를 캐시한다. 문서는 모든 캐시 지시어에 `cacheLife`를 함께 지정하길 권하며, 지정하지 않으면 암묵적 `default` 프로필이 적용된다.

```tsx
// app/lib/posts.ts
import { cacheLife, cacheTag } from 'next/cache'

export async function getPosts() {
  'use cache'
  cacheLife('hours')
  cacheTag('posts')
  return db.post.findMany({ orderBy: { createdAt: 'desc' } })
}
```

`cacheTag`로 붙인 태그는 이전 절의 `revalidateTag`와 `updateTag`가 그대로 소비한다. 즉 무효화 API는 두 모델에서 공통이고, 태그를 붙이는 방법만 `fetch`의 `next.tags` 또는 `unstable_cache`에서 `cacheTag`로 바뀐다. `cacheLife`는 `stale`(클라이언트 신선 시간), `revalidate`(백그라운드 재검증 시작), `expire`(낡은 값 제공 중단)의 세 값으로 수명을 표현하며, 수명이 너무 짧은 항목은 정적 셸에서 제외될 수 있다. 정확한 프로필 값은 `cacheLife` 문서를 확인해야 한다.

저장 위치의 기본은 서버 인스턴스별 메모리여서 서버리스에서는 요청마다 비어 있을 수 있다. 인스턴스 간 공유가 필요하면 `'use cache: remote'`와 캐시 핸들러를 쓰지만 네트워크 왕복 비용이 있어 적중률이 높을 때만 이득이다. 쿠키나 헤더를 직접 읽어야 하면 `'use cache: private'`를 쓰며 결과는 브라우저에만 머문다. 모든 저장소는 배포 단위로 격리되어, 캐시 키에 빌드 ID가 들어가므로 새 배포 후 항목은 이어지지 않는다.

## 6. Partial Prerendering: 정적 셸과 동적 구멍

PPR은 한 라우트 안에서 정적 부분은 빌드 시점의 셸로 CDN에서 즉시 내려주고 동적 부분만 요청 시점에 스트리밍한다. 현재 공식 문서(16.x)는 Cache Components를 켠 상태에서 이를 기본 동작으로 설명한다. 빌드 시 컴포넌트 트리를 렌더하며 각 컴포넌트는 사용한 API에 따라 처리된다. `'use cache'` 결과는 수명이 너무 짧지 않다면 셸에 포함되고, `<Suspense>` 안의 요청 시점 작업은 폴백만 셸에 들어가며, 모듈 임포트나 순수 계산은 자동으로 셸에 포함된다. `cookies()`, `headers()`, `searchParams`, `Math.random()`, `Date.now()` 같은 작업은 캐시하거나 Suspense 안으로 옮겨야 하고, 요청마다 다른 값이 필요하면 `connection()`을 먼저 호출한다.

```tsx
// app/blog/page.tsx
import { Suspense } from 'react'
import { cookies } from 'next/headers'
import { cacheLife, cacheTag } from 'next/cache'

export default function BlogPage() {
  return (
    <>
      <header><h1>Our Blog</h1></header>   {/* 정적: 셸에 포함 */}
      <BlogPosts />                         {/* 캐시: 셸에 포함 */}
      <Suspense fallback={<p>Loading...</p>}>
        <UserPreferences />                 {/* 요청 시점: 폴백만 셸에 */}
      </Suspense>
    </>
  )
}

async function BlogPosts() {
  'use cache'
  cacheLife('hours')
  cacheTag('posts')
  const posts = await db.post.findMany()
  return <ul>{posts.map((p) => <li key={p.id}>{p.title}</li>)}</ul>
}

async function UserPreferences() {
  const theme = (await cookies()).get('theme')?.value ?? 'light'
  return <aside>Theme: {theme}</aside>
}
```

가장 큰 설계 원칙은 비동기 작업을 트리의 깊은 곳으로 내리는 것이다. 레이아웃 최상단의 `await params`는 레이아웃 전체를 요청 시점 데이터에 묶어 셸을 줄이므로, params Promise를 아래로 넘겨 작은 컴포넌트에서만 await한다. `generateStaticParams`로 알려진 URL은 구체적인 셸을 갖고, 모르는 파라미터는 공통 앱 셸이 먼저 제공된 뒤 ISR로 채워진다. 봇과 크롤러는 셸을 건너뛰고 전체를 요청 시점에 렌더한다.

## 7. 트레이드오프와 측정

캐시는 지연을 줄이는 대신 일관성 위험과 디버깅 비용을 늘린다. 아래 수치는 설명용 예시이며 실제 측정값이 아니다.

| 시나리오(예시 수치, 검증되지 않음) | 원본 조회 포함 | 캐시 적중 | 관찰 포인트 |
|---|---|---|---|
| 상품 설명(`days`) | 약 180ms | 약 15ms | 적중률이 높을수록 이득 |
| `{ expire: 0 }` 직후 첫 요청 | 원본 시간만큼 블록 | 해당 없음 | 낡은 값 없음의 대가 |

측정은 개발 모드가 아닌 프로덕션 빌드(`next build` 후 `next start`)에서 해야 한다. 개발 모드에서는 페이지가 항상 요청 시점에 렌더된다. `'use cache: remote'`는 공유와 내구성을 주지만 조회마다 네트워크 왕복이 붙는다. 사용자별 데이터를 공유 캐시에 넣는 실수는 성능 문제가 아니라 보안 사고이므로, 캐시 전에 "이 데이터가 모든 사용자에게 같은가"를 먼저 물어야 한다.

## 8. 자주 겪는 함정과 점검 순서

무효화했는데 화면이 안 바뀔 때는 바깥에서 안쪽으로 점검한다. 먼저 Next 버전과 `cacheComponents` 설정으로 어떤 모델인지 확정하고, 태그가 캐시 항목에 실제로 붙어 있는지(대소문자, 256자)를 본다. 서버 액션 밖에서 `updateTag`를 부르면 오류가 나고, 단일 인자 `revalidateTag(tag)`는 현재 문서상 deprecated다. `revalidateTag(tag, 'max')`는 SWR이라 호출 직후 첫 요청에서 낡은 값이 보이는 것이 정상이다. 마지막으로 브라우저 라우터 캐시와 CDN 캐시를 의심한다.

두 번째 함정은 Suspense 없이 런타임 API를 쓰는 것이다. Cache Components에서는 블로킹 라우트 인사이트가 나타나며, 접근을 Suspense 안으로 옮기거나 값을 추출해 캐시 함수의 인자로 넘기면 해소된다. 세 번째는 환경 차이다. 로컬에서는 인메모리 캐시가 잘 맞아 보이지만 서버리스에서는 요청마다 비어 있을 수 있다. 이전 모델에서 옮기는 팀은 변경이 적고 트래픽이 많은 라우트부터 옮기며 지표를 비교하는 편이 안전하다.

## 참고

- Next.js Docs, Caching (Cache Components): https://nextjs.org/docs/app/getting-started/caching
- Next.js Docs, Caching and Revalidating (Previous Model): https://nextjs.org/docs/app/guides/caching-without-cache-components
- Next.js Docs, revalidateTag: https://nextjs.org/docs/app/api-reference/functions/revalidateTag
- Next.js Docs, updateTag: https://nextjs.org/docs/app/api-reference/functions/updateTag
- Next.js Docs, use cache: https://nextjs.org/docs/app/api-reference/directives/use-cache
- Next.js Docs, cacheLife: https://nextjs.org/docs/app/api-reference/functions/cacheLife
- Next.js Docs, cacheTag: https://nextjs.org/docs/app/api-reference/functions/cacheTag
