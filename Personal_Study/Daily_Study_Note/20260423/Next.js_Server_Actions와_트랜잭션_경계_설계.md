Notion 원본: https://www.notion.so/34b5a06fd6d3812882cee3ac2e388088

# Next.js Server Actions와 트랜잭션 경계 설계

> 2026-04-23 신규 주제 · 확장 대상: Next.js (Pages Router / RSC 학습됨)

## 학습 목표

- Server Actions의 실제 wire protocol(POST + multipart)과 RSC stream 응답 구조를 파악한다
- useFormStatus / useOptimistic / revalidatePath 조합을 한 폼에서 조율한다
- DB 트랜잭션 경계를 Server Action 안에 두는 경우의 실패/롤백 패턴을 구현한다
- form action vs Route Handler(/api/*)의 선택 기준을 명확히 한다

---

## 1. Server Actions 의 실체

Next.js 14+의 Server Action은 React 19의 `"use server"` 지시자를 쓴 async 함수를 서버 번들에만 포함하고, 클라이언트에서는 그 함수로의 불투명한 참조 ID만 넘겨받는다. 폼 submit 시 일어나는 일은 (1) 브라우저가 현재 페이지 URL로 POST 보냄, (2) 요청 헤더 `next-action: <action-id>`, (3) 서버는 action-id로 함수를 찾아 실행, (4) 응답은 RSC payload(`text/x-component`) — 리다이렉트/재검증/폼 상태 + 해당 라우트의 새 RSC 트리. 즉 REST 엔드포인트가 아니라 "암묵적 RPC 가 같은 URL 위에 얹힌 것"이다. **Server Action은 public API로 간주하면 안 된다**.

action-id는 빌드 시 결정되는 해시이므로 크로스 사이트 공격자가 예측하기 어렵고, Next.js는 기본적으로 Origin 헤더와 allowed origins(서버 기동 시 설정)를 비교해 차단한다.

## 2. 폼과 Server Action 연결하기

```typescript
// app/orders/new/actions.ts
"use server";
import { z } from "zod";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { db } from "@/lib/db";

const schema = z.object({
  userId: z.coerce.number().int().positive(),
  itemId: z.string().min(1),
  qty: z.coerce.number().int().min(1).max(100),
});

export async function createOrder(prev: State, formData: FormData): Promise<State> {
  const parsed = schema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) return { ok: false, errors: parsed.error.flatten().fieldErrors };

  const id = await db.transaction(async (tx) => {
    const { userId, itemId, qty } = parsed.data;
    const stock = await tx.item.findUnique({ where: { id: itemId } });
    if (!stock || stock.qty < qty) throw new Error("OUT_OF_STOCK");
    await tx.item.update({ where: { id: itemId }, data: { qty: { decrement: qty } } });
    const order = await tx.order.create({ data: { userId, itemId, qty } });
    return order.id;
  });

  revalidatePath("/orders");
  redirect(`/orders/${id}`);
}
```

`redirect()`는 실제로 `NEXT_REDIRECT`라는 특수 에러를 throw해서 프레임워크가 가로챈다. try/catch로 감싸면 리다이렉트가 죽는다. 반드시 catch 바깥에서 호출하거나 catch 후 다시 throw.

## 3. 트랜잭션 경계의 원칙

- **HTTP 응답 성공 ≠ DB 커밋 성공**: 네트워크 타임아웃 시 사용자는 "실패"로 보지만 서버 트랜잭션은 커밋됐을 수 있다. 멱등성 키(`Idempotency-Key`)를 클라이언트가 보내 중복 실행을 막는다.
- **트랜잭션 안에 외부 I/O 넣지 말기**: 결제 API, 이메일 발송은 commit 후 실행. 필요하면 Outbox 패턴으로 DB에 "발송 예정" 레코드를 커밋시점에 같이 넣고, 별도 워커가 발송한다.
- **redirect/revalidate는 commit 이후**: 트랜잭션 콜백이 끝난 뒤 호출. 트랜잭션 내부에서 `revalidatePath`를 호출해 봐야 이후 롤백되면 캐시만 어긋난다.

## 4. useOptimistic 와 롤백

```typescript
const [optimisticTodos, addOptimistic] = useOptimistic(
    todos,
    (state, newTitle: string) => [
      ...state,
      { id: `tmp-${Date.now()}`, title: newTitle, pending: true },
    ]
);

async function formAction(formData: FormData) {
  const title = formData.get("title") as string;
  startTransition(() => addOptimistic(title));
  await addTodo(title);
}
```

optimistic 상태는 action이 완료되고 RSC가 재렌더되면 자동으로 폐기된다. 실패(throw) 시에도 자동 롤백. 주의할 점은 optimistic 업데이트는 **렌더 중에만 유효**하기 때문에 `startTransition` 바깥에서 호출하면 경고가 뜬다.

## 5. revalidatePath 와 revalidateTag 의 차이

| 함수 | 대상 | 사용 시점 |
|---|---|---|
| revalidatePath(path) | 해당 경로 RSC 캐시, Data Cache | 라우트 단위 무효화 |
| revalidateTag(tag) | 해당 태그 Data Cache 엔트리 | 여러 라우트 걸친 동일 데이터 |

`fetch(url, { next: { tags: ['orders'] } })` 처럼 태그를 붙여둔 fetch 캐시는 `revalidateTag('orders')`로 한 방에 무효화할 수 있어 여러 페이지에 퍼져 있는 동일 데이터에 유리하다. 반면 페이지 단위 invalidation만 필요하면 `revalidatePath`가 단순하고 빠르다.

## 6. Route Handler vs Server Action 선택 기준

| 상황 | 권장 |
|---|---|
| 같은 앱 내 mutation | Server Action |
| 외부 호출용 public API | Route Handler |
| webhook 수신 | Route Handler |
| 파일 업로드 with 진행률 | Route Handler + XHR |
| 모바일 앱이 호출 | Route Handler |

Server Action은 내부 최적화 계층이 많아 RSC 재사용성 측면에서 DX가 좋지만, 외부 통합이나 진행률 추적 같이 브라우저 표준 API와 밀접한 케이스에서는 통제가 제한된다.

## 7. 실패 모드와 테스트

```typescript
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn(() => { throw new Error("NEXT_REDIRECT"); }) }));

it("should_return_field_errors_when_qty_is_zero", async () => {
  const fd = new FormData();
  fd.set("userId", "1"); fd.set("itemId", "SKU-A"); fd.set("qty", "0");
  const res = await createOrder({ ok: false }, fd);
  expect(res.ok).toBe(false);
  expect(res.errors?.qty).toBeDefined();
});
```

e2e는 Playwright로 실제 form submit을 돌린다. `page.waitForURL`로 redirect 확인. vitest에서 `next/navigation`을 모킹할 때 `redirect`가 throw하도록 맞춰두지 않으면 catch 바깥의 로직이 계속 실행되어 검증이 어긋난다.

## 8. 보안 체크리스트

- **Origin 검증**: Next는 기본적으로 Server Action 요청의 `Origin` 헤더를 확인하고 mismatch면 거절한다. Proxy 뒤에서 `X-Forwarded-Host`가 올바르지 않으면 false positive 난다.
- **인증**: middleware 레벨 + 각 action 내부 `getSession()` 이중 체크. 특히 Server Action은 공개되지 않은 RPC 성격이라 middleware만 믿으면 안 된다.
- **CSRF**: `SameSite=Lax` 쿠키 + CSP가 기본. 크로스 서브도메인에서 접근이 필요하면 `SameSite=None; Secure`와 Origin 허용 목록을 세팅한다.
- **Rate limiting**: middleware에서 IP/user 단위로 제한.

## 참고

- React Docs — Server Actions and Mutations: https://react.dev/reference/rsc/server-actions
- Next.js Docs — Forms and Mutations: https://nextjs.org/docs/app/building-your-application/data-fetching/server-actions-and-mutations
- Sebastian Markbåge — "React Server Components" RFC
- Lee Robinson — "Server Actions Deep Dive" (2024)
