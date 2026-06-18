Notion 원본: https://www.notion.so/3835a06fd6d381c3b0e1f3db93203918

# Next.js App Router Server Actions 트랜잭션 경계와 캐시 재검증 무효화 전략

> 2026-06-18 신규 주제 · 확장 대상: Next.js

## 학습 목표

- Server Action 의 실행 모델(서버 단일 실행, POST 직렬화)과 RPC 엔드포인트 자동 생성을 설명한다
- DB 트랜잭션을 Server Action 안에 둘 때의 경계와 오류 롤백·멱등성을 코드로 다룬다
- `revalidatePath`·`revalidateTag`·`cookies` 변경에 따른 캐시 무효화 흐름을 구분한다
- useActionState·useOptimistic 로 폼 상태와 낙관적 UI 를 안전하게 구성한다

## 1. Server Action 의 실행 모델

`'use server'` 함수는 클라이언트에서 호출하지만 항상 서버에서만 실행된다. Next.js 는 각 액션에 안정 ID 를 부여해 POST 로 직렬화한다(사실상 RPC).

```tsx
'use server';
import { revalidatePath } from 'next/cache';
export async function createPost(formData: FormData) {
  const title = String(formData.get('title') ?? '');
  if (title.trim().length < 3) return { ok: false, error: '3자 이상' };
  const post = await db.post.create({ data: { title } });
  revalidatePath('/posts');
  return { ok: true, id: post.id };
}
```

## 2. 입력 신뢰 경계와 검증

Server Action 은 공개 HTTP 엔드포인트다. 모든 입력을 서버에서 재검증하고 인증·인가를 액션 내부에서 확인한다.

```tsx
const Schema = z.object({ title: z.string().min(3), body: z.string().min(1) });
export async function createPost(_prev: unknown, fd: FormData) {
  const session = await auth();
  if (!session?.user) return { ok: false, error: '인증 필요' };
  const parsed = Schema.safeParse({ title: fd.get('title'), body: fd.get('body') });
  if (!parsed.success) return { ok:false, fieldErrors: parsed.error.flatten().fieldErrors };
}
```

## 3. 트랜잭션 경계 설정

```tsx
await db.$transaction(async (tx) => {
  const sender = await tx.account.update({ where:{id:from}, data:{ balance:{ decrement:amount } } });
  if (sender.balance < 0) throw new Error('INSUFFICIENT_FUNDS');
  await tx.account.update({ where:{id:to}, data:{ balance:{ increment:amount } } });
  await tx.ledger.create({ data:{ from, to, amount } });
});
revalidateTag(`account:${from}`); revalidateTag(`account:${to}`);
```

핵심: 캐시 재검증은 트랜잭션 커밋 이후에 호출하고, 멱등 키로 중복 처리를 흡수한다.

## 4. 캐시 무효화: revalidatePath vs revalidateTag

```tsx
const posts = await fetch(`${API}/posts`, { next: { tags: ['posts'], revalidate: 3600 } });
revalidateTag('posts');
```

| API | 대상 | 적합 |
|---|---|---|
| revalidatePath | 경로의 라우트 캐시 | 특정 페이지 갱신 |
| revalidateTag | 태그된 fetch 캐시 | 여러 화면 공유 데이터 |
| cookies().set | 동적 렌더 강제 | 인증·세션 변경 |

## 5. useActionState 로 폼 상태 관리

```tsx
'use client';
const [state, formAction] = useActionState(createPost, null);
const { pending } = useFormStatus();
```

액션 시그니처가 `(prevState, formData) => newState` 가 된다.

## 6. useOptimistic 으로 낙관적 UI

```tsx
const [optimistic, addOptimistic] = useOptimistic(todos,
  (state, text) => [...state, { id:'temp', text, pending:true }]);
async function action(fd) { addOptimistic(String(fd.get('text'))); await addTodo(...); }
```

실패 시 React 가 자동 롤백, 완료 후 revalidateTag 로 수렴시킨다.

## 7. 리다이렉트·쿠키·에러 처리의 함정

`redirect()` 는 특수 에러를 throw 하므로 try/catch 밖에서 호출한다. `cookies().set()` 은 액션/라우트 핸들러에서만 가능하다.

```tsx
export async function login(_prev, fd) {
  const session = await authenticate(fd);
  if (!session) return { ok:false };
  (await cookies()).set('session', session.token, { httpOnly:true, secure:true, sameSite:'lax' });
  redirect('/dashboard');
}
```

## 8. 액션 호출 위치와 직렬화 제약

인자·반환값은 직렬화 가능한 값(원시값, plain 객체, Date, FormData, Map/Set)만 주고받는다. 함수·클래스 인스턴스는 불가. `bind` 로 묶는 값도 클라이언트로 전송되므로 민감 정보를 넘기지 않는다.

## 9. 보안·성능 트레이드오프와 권장 기준

액션은 공개 엔드포인트이므로 검증·인증·인가가 필요하다. 조회는 서버 컴포넌트 fetch 로, 변경만 Server Action 으로 둔다. 무거운 작업은 큐로 분리, 트랜잭션은 짧게, 외부 호출은 트랜잭션 밖에서. revalidate 는 커밋 이후, 멱등성은 유니크 제약, 낙관적 UI 는 서버 재검증으로 수렴시킨다.

## 참고

- Next.js — Server Actions and Mutations (https://nextjs.org/docs/app/building-your-application/data-fetching/server-actions-and-mutations)
- Next.js — Caching (https://nextjs.org/docs/app/building-your-application/caching)
- React — useActionState, useOptimistic (https://react.dev/reference/react)
- Next.js — revalidatePath / revalidateTag (https://nextjs.org/docs/app/api-reference/functions)
