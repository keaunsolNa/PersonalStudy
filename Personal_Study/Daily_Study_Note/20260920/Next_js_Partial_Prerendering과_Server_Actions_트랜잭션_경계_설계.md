Notion 원본: https://www.notion.so/3e15a06fd6d38100a968f861d0a1c758

# Next.js Partial Prerendering과 Server Actions 트랜잭션 경계 설계

> 2026-09-20 신규 주제 · 확장 대상: Next.js, React

## 학습 목표

- 빌드 타임 정적 셸과 런타임 스트리밍 홀이 나뉘는 기준을 코드 레벨에서 판별한다
- `use cache` / `cacheLife` / `cacheTag` 와 `updateTag` / `revalidateTag` 의 전파 경로를 구분해 선택한다
- Server Action 을 공개 POST 엔드포인트로 간주하고 인가·검증·멱등성을 액션 내부에 배치한다
- DB 커밋과 캐시 무효화·외부 API 호출 사이의 원자성 공백을 식별하고 보상 전략을 설계한다

## 1. PPR 이 실제로 하는 일

Partial Prerendering(PPR)은 한 라우트 안에서 정적·동적 렌더링을 함께 쓰는 모델이다. 빌드 타임에 컴포넌트 트리를 렌더하다 지금 결정할 수 없는 지점을 만나면 그 자리에 `<Suspense>` fallback 을 박고 나머지를 HTML 로 굳힌다. 굳은 결과물이 **정적 셸(static shell)**, fallback 이 차지한 자리가 **홀(hole)** 이다. React 는 중단된 렌더 상태를 `postponedState` 로 직렬화해 두고, 요청이 오면 그 지점부터 이어 렌더한다.

요청이 **한 번**이라는 게 핵심이다. 클라이언트 JS 가 별도 fetch 로 구멍을 메우는 게 아니라 서버가 한 응답 안에서 셸 → 스트리밍 청크 순으로 밀어 넣는다. 셸은 CDN 에서 바로 나가 TTFB 가 오리진 왕복과 무관해지지만, LCP 요소가 홀 안에 있으면 체감은 SSR 과 다르지 않다.

```ts
// Next.js 14/15 — 실험 플래그 (레거시)
const nextConfig = { experimental: { ppr: 'incremental' } } // true | 'incremental'
export const experimental_ppr = true // app/dashboard/page.tsx

// Next.js 16+ — Cache Components 로 대체, PPR 이 기본 동작
const nextConfig16: NextConfig = { cacheComponents: true }
```

`'incremental'` 은 `experimental_ppr = true` 를 선언한 세그먼트만 PPR 로 돌리는 점진 도입 모드였다. Next.js 16 부터 두 플래그는 **Cache Components** 로 대체됐다(문서 기준 16.3.5). 마이너 버전 사이에도 플래그와 기본값이 바뀌어 왔으니 적용 전 버전 문서를 확인한다.

## 2. 정적/동적 경계를 가르는 규칙

경계를 결정하는 건 "런타임 API 를 어디서 await 하느냐"다. `cookies()`, `headers()`, `searchParams`, `params`, `connection()` 은 요청이 있어야 값이 정해진다. 이 호출이 `<Suspense>` 밖이면 위쪽 트리 전체가 프리렌더를 완료하지 못한다.

이전 모델에서는 이런 호출 하나가 라우트 전체를 동적으로 뒤집었다. Cache Components 에서는 경계 안이면 그 서브트리만 홀이 되고 셸과 캐시된 부분은 초기 HTML 에 실린다. 경계 밖이면 프리렌더가 막히고 dev 오버레이가 `blocking-route` 인사이트를 띄운다.

여기서 나오는 실전 패턴이 **셸 최대화**다. 레이아웃 최상단에서 `await params` 를 하면 레이아웃 전체가 셸에서 빠지지만, promise 를 경계 안으로 내려보내면 사이드바와 `children` 은 셸에 남는다.

```tsx
// app/shop/[slug]/layout.tsx — async 가 아니다
export default function Layout({ children, params }: LayoutProps<'/shop/[slug]'>) {
  return (
    <div>
      <Sidebar />
      <Suspense fallback={<h1>Loading...</h1>}>
        {params.then(({ slug }) => <SlugHeading slug={slug} />)}
      </Suspense>
      {children}
    </div>
  )
}
```

`Math.random()`, `Date.now()`, `crypto.randomUUID()` 도 프리렌더를 막는다. 요청마다 달라야 하면 `await connection()` 으로 미루고 `<Suspense>` 로 감싸며, 모두가 같은 값을 봐도 되면 `use cache` 안에 넣는다. 한편 봇·크롤러는 셸을 건너뛰고 전체를 요청 시점에 렌더하므로, 빌드 타임에만 닿는 데이터에 셸이 의존하면 크롤러에게만 실패한다.

## 3. SSG · ISR · SSR · PPR 의 자리

| 모델 | 첫 바이트 출처 | 개인화 | 오리진 부하 | 데이터 신선도 |
|---|---|---|---|---|
| SSG | CDN 정적 HTML | 불가(hydration 후에만) | 거의 0 | 빌드 시점 고정 |
| ISR | CDN 캐시 HTML | 불가 | 재생성 시에만 | `revalidate` 주기 |
| SSR | 오리진 렌더 결과 | 완전 가능 | 요청마다 풀 렌더 | 항상 최신 |
| PPR | CDN 정적 셸 | 홀 단위로 가능 | 홀 부분만 렌더 | 셸은 캐시, 홀은 최신 |

PPR 의 이득은 "정적 캐시 적중 + 개인화"를 양자택일이 아니게 만든 것이다. 대가도 있다. 셸과 홀의 경계가 곧 fallback UI 경계라 레이아웃 시프트를 fallback 설계로 통제해야 하고, 오리진 부하는 사라지는 게 아니라 **홀 개수만큼 쪼개진다** — 헤더·푸터 렌더링 비용은 없어져도 DB 쿼리 수는 그대로여서 PPR 이 DB 부하를 줄여주지는 않는다.

캐시 적중률도 낙관할 수 없다. `use cache` 결과는 기본적으로 **인스턴스별 인메모리 LRU** 라 서버리스에서는 요청 간 유지되지 않고, 공유하려면 `use cache: remote`(Redis/KV) 의 왕복 비용을 치러야 한다. 캐시 키에 빌드 ID 가 들어가 배포 한 번에 전부 무효화된다.

## 4. `use cache` 와 무효화 전파 경로

캐시 키는 빌드 ID + 함수 ID(위치·시그니처 해시) + 직렬화된 인자로 만들어지고, **클로저로 캡처한 외부 변수도 자동으로 키에 포함된다**. 모르면 엔트리가 폭증한다.

```ts
export async function getOrderSummary(accountId: string) {
  'use cache'
  cacheLife('hours')
  cacheTag(`orders:${accountId}`)
  return db.orders.findMany({ where: { accountId } })
}
```

`cacheLife` 를 생략하면 `default` 프로필이 적용된다 — stale 5분(클라이언트), revalidate 15분(서버), expire 없음. 클라이언트 라우터는 설정과 무관하게 **최소 30초 stale** 을 강제하고, 수명은 `x-nextjs-stale-time` 헤더로 전달된다.

캐시 스코프 안에서는 `cookies()`, `headers()`, `searchParams` 를 읽을 수 없다. 호출 스택을 따라 막히므로 캐시 함수가 부르는 헬퍼가 쿠키를 읽어도 `next-request-in-use-cache` 로 실패하는데, 동적 라우트에서는 빌드를 통과하고 `next start` 에서 터질 수 있다. 런타임 값은 밖에서 읽어 인자로 넘긴다.

| API | 호출 위치 | 즉시성 | 액션 응답에 재렌더 포함 |
|---|---|---|---|
| `updateTag(tag)` | Server Action 전용 | 즉시 만료, 다음 읽기가 대기 | 포함 |
| `revalidateTag(tag, profile)` | Action + Route Handler | stale-while-revalidate | 미포함 |
| `revalidatePath(path)` | Action + Route Handler | 경로 단위 즉시 무효화 | 포함 |
| `refresh()` | Server Action | 캐시 유지, RSC 페이로드만 재요청 | 포함 |

read-your-own-writes 가 필요하면 `updateTag`, 웹훅처럼 백그라운드 갱신이면 `revalidateTag` 다.

## 5. Server Action 의 실체는 공개 POST 엔드포인트다

`'use server'` 가 붙은 함수는 빌드 타임에 클라이언트 번들에서 구현이 제거되고 **액션 ID + 디스패처**로 치환된다. 클라이언트가 그 함수를 "호출"하면 실제로는 현재 페이지 URL 로 액션 ID 와 직렬화된 인자를 실어 POST 를 보낸다. 엔드포인트는 열려 있으므로, 폼을 로그인한 페이지에서만 렌더하는 건 보안 경계가 아니다.

프레임워크가 주는 보호는 네 가지뿐이다. `Origin` 과 `Host` 비교로 CSRF 차단(프록시 도메인은 `serverActions.allowedOrigins` 에 등록), 기본 1MB 바디 제한, 액션 ID 암호화와 미사용 액션 트리쉐이킹, 인라인 액션이 캡처한 **클로저 변수의 암호화**. 암호화 키는 인스턴스 간 동일해야 하므로 `NEXT_SERVER_ACTIONS_ENCRYPTION_KEY`(base64, 디코드 16/24/32바이트)를 고정 배포한다. 다만 암호화에만 기대면 안 된다 — 민감값은 애초에 클로저에 캡처하지 않는다.

액션 ID 는 소스가 그대로여도 최대 14일마다 회전하고 배포마다 바뀐다. 구버전 탭이 사라진 ID 로 POST 하면 `Failed to find Server Action` 이 나므로 재시도 UI 를 준비해 둔다.

인가는 반드시 액션 **안**에서 한다. 클라이언트가 보내는 것은 **무엇을 바꿀지**로 제한하고, 누구의 것인지는 세션에서 유도한다.

```ts
'use server'
// 위험: db.item.update({ where: { id: item.id } }) — 클라이언트가 준 id 를 그대로
// 믿으면 POST 를 만들 수 있는 누구나 남의 행을 바꾼다.
export async function completeItem(itemId: string) {
  const userId = (await auth())?.user?.id
  if (!userId) throw new Error('Unauthorized')
  const item = await db.item.findFirst({ where: { id: itemId, ownerId: userId } })
  if (!item) throw new Error('Forbidden')
  await db.item.update({ where: { id: item.id }, data: { completed: true } })
}
```

zod 같은 스키마 검증은 입력의 *형태*만 본다. 형태가 멀쩡한 객체가 남의 행을 가리킬 수 있다. 액션의 리턴은 그대로 클라이언트로 직렬화되므로 반환값도 UI 가 그리는 모양으로 좁힌다.

## 6. 트랜잭션 경계: 하나의 액션은 하나의 트랜잭션인가

결론부터. **아니다.** Server Action 은 트랜잭션 경계가 아니라 HTTP 요청 경계다. 프레임워크는 액션 시작 시 트랜잭션을 열지도, 예외 발생 시 롤백하지도 않는다.

```ts
'use server'
export async function transferPoints(toUserId: string, amount: number) {
  const me = (await auth())?.user?.id
  if (!me) throw new Error('Unauthorized')
  if (!Number.isInteger(amount) || amount <= 0) throw new Error('Invalid amount')

  await db.$transaction(async (tx) => {  // 경계는 여기서 명시적으로 연다
    const from = await tx.wallet.update({
      where: { userId: me },
      data: { balance: { decrement: amount } },
    })
    if (from.balance < 0) throw new Error('Insufficient balance') // 롤백
    await tx.wallet.update({
      where: { userId: toUserId },
      data: { balance: { increment: amount } },
    })
  })

  updateTag(`wallet:${me}`) // 커밋이 끝난 뒤에만 무효화한다
  updateTag(`wallet:${toUserId}`)
}
```

핵심은 `updateTag` 의 **위치**다. 트랜잭션 콜백 안에서 부르면 커밋 전에 태그가 만료되고 재렌더가 시작될 수 있다. 그 재렌더가 읽는 스냅샷에는 변경이 없거나, 최악의 경우 롤백될 값을 캐시에 채워 넣는다. Spring 의 `afterCommit` 과 같은 이유다.

두 번째 공백은 **외부 API 와 DB 의 원자성 부재**다. 결제 게이트웨이 호출과 주문 INSERT 는 같은 트랜잭션에 들어가지 않아, 결제 성공 후 커밋 직전에 죽으면 돈만 빠져나간다. 액션 안에서 풀 문제가 아니라 outbox 나 상태 머신(`PENDING → PAID`)으로 보상 경로를 만들어야 한다.

세 번째는 **중복 제출**이다. 액션 URL 은 재현 가능한 POST 라 재시도·더블클릭·재발행이 모두 중복 실행을 만든다. 멱등키를 클라이언트가 만들어 보내고 서버가 유니크 제약으로 걸러내는 방식이 가장 견고하다.

```ts
'use server'
export async function placeOrder(idempotencyKey: string, cartId: string) {
  const userId = (await auth())?.user?.id
  if (!userId) throw new Error('Unauthorized')
  try {
    return await db.$transaction(async (tx) => {
      // (userId, key) 에 UNIQUE 인덱스 — 두 번째 시도는 여기서 걸린다
      await tx.actionLog.create({ data: { userId, key: idempotencyKey } })
      return await createOrder(tx, userId, cartId)
    })
  } catch (e) {
    if (isUniqueViolation(e)) return await findOrderByKey(idempotencyKey)
    throw e
  }
}
```

`useActionState` 의 `isPending` 으로 버튼을 잠그는 건 UX 장치지 정합성 보장이 아니다.

## 7. 동시성, 낙관적 업데이트, 에러 처리

Next.js 클라이언트 디스패처는 **클라이언트당 Server Action 을 한 번에 하나씩** 보낸다. 세 개를 연달아 트리거하면 두 번째는 첫 번째가 끝날 때까지 기다린다 — 재렌더된 서버 트리가 그것을 만든 액션 결과와 어긋나지 않게 하기 위해서다. 그래서 `Promise.all` 로 액션을 병렬화하는 건 의미가 없고, 병렬 작업은 한 액션 안에서 하거나 Route Handler 로 뺀다.

다만 이 직렬화는 **그 탭 안에서만** 성립한다. 다른 탭, 모바일·웹 동시 조작, 재시도 요청은 서버에서 그대로 동시 실행된다. race 의 방어선은 DB 의 낙관적 락(버전 컬럼)이나 조건부 UPDATE 다. 프런트의 순차 보장을 정합성 보장으로 오해하면 안 된다.

`useOptimistic` 은 서버 응답 전에 UI 를 미리 바꾼다. 액션이 끝나면 React 가 낙관적 상태를 버리고 서버의 실제 상태로 돌아가므로 실패 시 **롤백은 자동**이다 — 다만 그게 "방금 한 일이 소리 없이 사라짐"으로 보이므로 실패는 따로 알린다.

```tsx
'use client'
export function LikeButton({ postId, likes }: { postId: string; likes: number }) {
  const [optimistic, addOptimistic] = useOptimistic(likes, (n: number) => n + 1)
  const [state, act, isPending] = useActionState(likeAction, { error: null })
  const onClick = () =>
    startTransition(() => {
      addOptimistic(null) // 서버 응답 전 +1, 실패하면 자동으로 되돌아온다
      act(postId)
    })

  return (
    <>
      <button disabled={isPending} onClick={onClick}>좋아요 {optimistic}</button>
      {state.error && <p role="alert">{state.error}</p>}
    </>
  )
}
```

에러는 성격에 따라 갈린다. 검증 실패·잔액 부족처럼 **사용자가 대응할 수 있는 실패**는 throw 하지 말고 결과 객체(`{ ok: false, error }`)로 반환해 `useActionState` 가 폼 옆에 표시하게 한다. throw 하면 프로덕션에서 메시지가 마스킹되고 `error.tsx` 경계가 떠 폼 입력이 날아간다. 반대로 인가 실패·불변식 위반처럼 **일어나선 안 되는 일**은 throw 해 요란하게 실패시키는 편이 안전하다. `redirect()` 는 제어 흐름 예외를 던지므로 무효화 호출은 그 **앞**에 둔다.

## 8. `@Transactional` 과 무엇이 다른가

| 관심사 | Spring `@Transactional` | Server Action |
|---|---|---|
| 경계 선언 | 어노테이션으로 메서드에 선언 | 없음. `db.$transaction` 등으로 직접 |
| 롤백 트리거 | 언체크 예외 시 자동 롤백 | 자동 롤백 없음 |
| 전파 속성 | `REQUIRES_NEW`, `NESTED` 등 | 개념이 없음 |
| 커밋 후 훅 | `afterCommit` 동기화 | 수동으로 커밋 뒤에 배치 |
| 중복 호출 | 호출부가 내부라 통제 가능 | 공개 POST, 재시도 전제 |
| 캐시 무효화 | 애플리케이션 관심사 | 프레임워크 API(`updateTag` 등)와 결합 |

정리하면 Server Action 은 서비스 계층이 아니라 **컨트롤러**에 가깝다. 액션은 얇게 두고 인증 → 검증 → 도메인 서비스 호출 → 커밋 후 무효화만 담당하게 하면, 도메인 로직은 Next.js 런타임 없이 테스트할 수 있다.

PPR 도 같은 선상이다. 응답 형태를 최적화할 뿐 데이터 계층의 문제를 풀어주지 않는다. 홀 안에서 도는 쿼리 수와 격리 수준은 여전히 백엔드의 몫이다.

## 참고

- Caching — Cache Components 와 PPR — https://nextjs.org/docs/app/getting-started/caching
- use cache 디렉티브 — https://nextjs.org/docs/app/api-reference/directives/use-cache
- Server Actions and Mutations — https://nextjs.org/docs/app/guides/server-actions
- Data Security — https://nextjs.org/docs/app/guides/data-security
- updateTag — https://nextjs.org/docs/app/api-reference/functions/updateTag
- React: Server Functions — https://react.dev/reference/rsc/server-functions
