Notion 원본: https://www.notion.so/37a5a06fd6d381fc9e87c108284d46dd

# Next.js Partial Prerendering(PPR)과 Server Actions 트랜잭션 경계 및 revalidate

> 2026-06-09 신규 주제 · 확장 대상: Next.js

## 학습 목표

- PPR이 정적 셸과 동적 홀(hole)을 하나의 응답으로 스트리밍하는 구조를 설명한다
- Suspense 경계가 PPR의 정적/동적 분할 단위가 되는 원리를 이해한다
- Server Actions의 실행 모델과 데이터 변경 후 캐시 무효화(revalidate) 경계를 설계한다
- 트랜잭션 정합성·동시성 관점에서 Server Action의 한계와 패턴을 분석한다

## 1. 정적과 동적의 오랜 딜레마

전통적으로 페이지는 정적(SSG, 빠르지만 데이터가 고정)이거나 동적(SSR, 최신이지만 매 요청 렌더)이었다. 실제 페이지는 대부분 혼합형이다 — 헤더·레이아웃·상품 설명은 정적이어도 되고, 장바구니·추천·재고는 요청별로 동적이어야 한다. 페이지 단위로 둘 중 하나를 고르면 정적 부분까지 매번 렌더하거나, 동적 부분을 캐시해 낡은 데이터를 보이게 된다. PPR(Partial Prerendering)은 한 페이지 안에서 이 둘을 공존시킨다.

## 2. PPR의 핵심: 정적 셸 + 동적 홀

PPR은 빌드 타임에 페이지의 정적 부분을 미리 렌더해 "정적 셸(static shell)"을 만들고, 동적 부분 자리에는 구멍(hole)을 남긴다. 요청이 오면 정적 셸을 즉시(엣지에서 캐시된 채로) 보내기 시작하고, 동적 홀은 서버에서 렌더되는 대로 같은 HTTP 응답에 스트리밍으로 채워 넣는다. 사용자는 TTFB 없이 셸을 즉시 보고, 동적 부분은 점진적으로 도착한다.

```tsx
import { Suspense } from 'react';

export const experimental_ppr = true; // 이 라우트에 PPR 활성

export default function ProductPage({ params }: { params: { id: string } }) {
  return (
    <main>
      {/* 정적 셸: 빌드 타임 프리렌더 */}
      <Header />
      <ProductDescription id={params.id} />

      {/* 동적 홀: Suspense 경계가 분할 단위 */}
      <Suspense fallback={<CartSkeleton />}>
        <Cart />        {/* cookies()/headers() 사용 → 동적 */}
      </Suspense>
      <Suspense fallback={<StockSkeleton />}>
        <LiveStock id={params.id} />
      </Suspense>
    </main>
  );
}
```

## 3. Suspense 경계가 분할의 단위

PPR에서 정적과 동적을 나누는 경계는 `<Suspense>`다. Suspense로 감싼 하위 트리는 동적 홀이 되고, fallback이 정적 셸에 인라인된다. 컴포넌트가 `cookies()`, `headers()`, `searchParams`, 캐시되지 않은 `fetch` 같은 동적 API에 의존하면 Next가 자동으로 그 부분을 동적으로 표시한다. 동적 API를 쓰면서 Suspense로 감싸지 않으면 빌드가 경고/오류를 내는데, 이는 "동적 부분은 명시적 경계 안에 있어야 한다"는 PPR의 규칙이다.

```tsx
import { cookies } from 'next/headers';

async function Cart() {
  const cart = (await cookies()).get('cart'); // 동적 API → 이 컴포넌트는 동적
  const items = await fetchCart(cart?.value);
  return <CartView items={items} />;
}
```

분할 입도 설계가 중요하다. Suspense를 너무 크게 잡으면 정적화할 수 있는 부분까지 동적으로 끌려가고, 너무 잘게 쯪면 스트리밍 청크가 많아져 오버헤드가 는다. "정적으로 둘 수 있는 최대 영역을 셸로, 진짜 동적인 최소 영역만 홀로" 두는 것이 원칙이다.

## 4. Server Actions의 실행 모델

Server Actions는 `'use server'`로 표시된 서버 함수로, 클라이언트에서 직접 호출하면 Next가 POST 요청으로 변환해 서버에서 실행한다. 폼 제출과 변경(mutation)을 별도 API 라우트 없이 처리한다.

```tsx
// app/actions.ts
'use server';

import { revalidatePath, revalidateTag } from 'next/cache';
import { redirect } from 'next/navigation';

export async function createOrder(formData: FormData) {
  const productId = String(formData.get('productId'));
  const qty = Number(formData.get('qty'));

  // 1) DB 변경 (트랜잭션 경계는 여기 안에서 직접 관리)
  await db.transaction(async (tx) => {
    await tx.decrementStock(productId, qty);
    await tx.insertOrder(productId, qty);
  });

  // 2) 영향받은 캐시 무효화
  revalidateTag(`stock:${productId}`);
  revalidatePath(`/product/${productId}`);

  redirect('/orders');
}
```

각 Server Action 호출은 독립된 요청-응답이다. 액션 함수 내부는 하나의 서버 실행이지만, **여러 액션 호출 사이에는 트랜잭션이 이어지지 않는다.** 따라서 원자적이어야 하는 변경은 반드시 한 액션 함수 안에서 DB 트랜잭션(`db.transaction`)으로 묶어야 한다. 액션 A에서 재고를 빼고 액션 B에서 주문을 넣는 식으로 나누면 중간 실패 시 정합성이 깨진다.

## 5. 트랜잭션 경계 설계

Server Action의 트랜잭션 경계 원칙은 "하나의 사용자 의도(주문 생성) = 하나의 액션 = 하나의 DB 트랜잭션"이다. 액션 내부에서 DB 라이브러리의 트랜잭션 API로 원자성을 보장하고, 트랜잭션 커밋 성공 후에만 `revalidate`를 호출해야 한다.

```tsx
'use server';
export async function transfer(formData: FormData) {
  const result = await db.transaction(async (tx) => {
    await tx.debit(from, amount);
    await tx.credit(to, amount);   // 둘 다 성공해야 커밋
    return { ok: true };
  });
  // 커밋 성공 후에만 캐시 무효화 — 실패 시 낡은 캐시 유지가 안전
  if (result.ok) revalidateTag('balance');
  return result;
}
```

`revalidate`를 트랜잭션 안에서 호출하면, 롤백됐는데도 캐시를 비워 "변경 안 됐는데 비싼 재요청"을 유발한다. 반드시 커밋 후 호출한다.

## 6. revalidate: 캐시 무효화 경계

Next App Router는 fetch 결과와 라우트를 캐시한다. 변경 후 무효화하는 도구는 세 가지다.

| API | 무효화 범위 | 사용 시점 |
|---|---|---|
| `revalidateTag(tag)` | 해당 태그가 붙은 모든 fetch | 특정 데이터 엔티티 변경 시 |
| `revalidatePath(path)` | 해당 경로의 라우트 캐시 | 특정 페이지 전체 갱신 |
| `revalidate` (라우트 세그먼트 설정) | 시간 기반 ISR | 주기적 재생성 |

```tsx
// fetch 에 태그 부여 → 나중에 태그로 정밀 무효화
const stock = await fetch(`${API}/stock/${id}`, {
  next: { tags: [`stock:${id}`], revalidate: 60 }, // 60초 ISR + 태그
}).then(r => r.json());
```

태그 기반 무효화는 "재고 변경 → `stock:123` 태그 무효화 → 그 데이터를 쓰는 모든 페이지가 다음 요청 시 재생성"으로 정밀하다. 경로 기반은 페이지 단위라 단순하지만 과도하게 무효화할 수 있다. PPR과 결합하면, 무효화된 동적 홀만 다음 요청에서 다시 렌더되고 정적 셸은 그대로 재사용되어 비용이 최소화된다.

## 7. 동시성과 한계

Server Actions는 RPC 추상일 뿐, 동시성 제어를 자동으로 해주지 않는다. 두 사용자가 동시에 마지막 재고 1개를 주문하면 race condition이 생긴다. 이는 DB 수준에서 낙관적 락(버전 컴럼)이나 비관적 락(`SELECT ... FOR UPDATE`)으로 막아야 한다.

```tsx
'use server';
export async function buyLast(productId: string) {
  return db.transaction(async (tx) => {
    // 조건부 UPDATE 로 원자적 차감 — 동시성 안전
    const updated = await tx.exec(
      `UPDATE products SET stock = stock - 1
       WHERE id = $1 AND stock > 0`, [productId]);
    if (updated.rowCount === 0) throw new Error('SOLD_OUT');
    await tx.insertOrder(productId);
  });
}
```

또한 Server Action은 사용자가 임의로 호출할 수 있는 엔드포인트이므로, 내부에서 반드시 인증·권한·입력 검증(Zod 등)을 수행해야 한다. "클라이언트 폼에서만 부르니 안전하다"는 가정은 틀렸다.

## 8. useActionState와 낙관 UI

Server Action의 결과(검증 오류, 성공 메시지)를 클라이언트에 반영하려면 `useActionState`(과거 `useFormState`)를 쓴다. 액션의 반환값이 상태가 되어 폼 옆에 오류를 표시할 수 있고, `useFormStatus`로 제출 중 로딩 상태를 안다.

```tsx
'use client';
import { useActionState } from 'react';
import { createOrder } from './actions';

export function OrderForm() {
  const [state, formAction, isPending] = useActionState(createOrder, { error: null });
  return (
    <form action={formAction}>
      <input name="productId" />
      <input name="qty" type="number" />
      {state.error && <p role="alert">{state.error}</p>}
      <button disabled={isPending}>{isPending ? '처리 중…' : '주문'}</button>
    </form>
  );
}
```

낙관적 업데이트는 `useOptimistic`으로 한다. 서버 응답을 기다리지 않고 UI를 먼저 갱신했다가, 액션이 실패하면 자동으로 이전 상태로 되돌린다. 이때 핵심은 "낙관적 상태는 화면용 임시값이고, 진실의 원천은 액션 커밋 + revalidate 후의 서버 데이터"라는 점이다. 낙관적 값과 서버 확정 값을 혼동하면 실패 롤백 시 화면이 깜빡이거나 불일치가 남는다. 즉 낙관 UI는 응답성을 높이지만, 트랜잭션 정합성의 최종 판정은 여전히 서버 커밋과 캐시 무효화에 있다.

## 9. trade-off 정리

PPR은 정적의 속도와 동적의 신선함을 한 페이지에 결합해 TTFB와 데이터 최신성을 동시에 잡지만, Suspense 경계 설계라는 인지 비용과 스트리밍 인프라(엣지 캐시) 의존을 추가한다. Server Actions는 별도 API 계층 없이 변경을 단순화하지만, 트랜잭션·동시성·보안을 개발자가 액션 내부에서 명시적으로 책임져야 한다. 핵심 원칙은 (1) 정적 최대·동적 최소로 Suspense 경계 잡기, (2) 원자적 변경은 한 액션 = 한 트랜잭션, (3) 커밋 후에만 태그 기반으로 정밀 revalidate, (4) 액션을 공개 엔드포인트로 간주해 인증·검증·동시성 락 적용이다. 단순성과 정합성 책임은 trade-off이며, 변경 로직이 복잡한 도메인일수록 액션 내부 규율이 더 중요해진다.

## 참고

- Next.js 공식 문서 — Partial Prerendering (https://nextjs.org/docs/app/getting-started/partial-prerendering)
- Next.js 공식 문서 — Server Actions and Mutations / revalidateTag·revalidatePath
- React 공식 문서 — Suspense 와 스트리밍 SSR
- Vercel Blog — Partial Prerendering 설계 배경
