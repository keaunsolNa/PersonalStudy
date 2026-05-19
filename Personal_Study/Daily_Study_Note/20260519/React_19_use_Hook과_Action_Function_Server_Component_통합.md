Notion 원본: https://www.notion.so/3655a06fd6d381f19721c5bf7755a764

# React 19 `use` Hook과 Action Function — Server Component 통합

> 2026-05-19 신규 주제 · 확장 대상: Frontend

## 학습 목표

- `use` hook 이 *조건부 호출 허용* / *Promise·Context 동시 처리* 라는 두 특이점을 어떤 런타임 변경으로 가능케 했는지 추적한다
- Action function 과 `useActionState` / `useFormStatus` / `useOptimistic` 의 책임 분담을 정리한다
- Server Component 와 Client Component 경계에서 `use(promise)` 의 *직렬화 페이로드* 와 *재실행 시점* 을 식별한다
- Next.js 15 App Router 의 caching·revalidation 정책과 결합한 실제 폼·데이터 흐름을 코드로 작성한다

## 1. `use` hook — 기존 hook 규칙의 일부를 깨다

React 의 hook 규칙은 두 가지로 요약된다. *조건문 안에서 호출 금지*, *반복 호출 시 순서 고정*. `use` 는 이 둘 중 *조건문 금지* 만 깨는다.

```tsx
function Comment({ id, showAuthor }: { id: string; showAuthor: boolean }) {
  const comment = use(fetchComment(id));
  if (showAuthor) {
    const author = use(fetchAuthor(comment.authorId));
    return <div>{comment.text} — {author.name}</div>;
  }
  return <div>{comment.text}</div>;
}
```

이 동작이 가능한 이유는 `use` 가 *hook 슬롯에 상태를 저장하지 않기* 때문이다. 대신 *Suspense throw* 메커니즘으로 Promise 가 pending 인 동안 렌더링을 중단하고, fulfilled 일 때 *같은 식별자의 Promise* 로 캐시된 결과를 즉시 반환한다.

`use(context)` 는 별도 경로다. context 값을 *현재 컴포넌트의 부모 체인* 에서 찾아 반환한다. `useContext` 와 동일하지만 조건문 안에서 호출 가능하다는 점만 다르다.

## 2. Promise identity 와 `cache`

`use(promise)` 는 *동일한 Promise 참조* 를 안정적으로 받기 위한 추가 메커니즘이 필요하다. 매 렌더링마다 `fetch(url).then(...)` 를 새로 만들면 *영원히 pending* 상태로 보인다. 서버에서는 React 의 `cache` 함수가 *요청 스코프* 캐시를 제공한다.

```ts
import { cache } from 'react';
export const fetchComment = cache(async (id: string) => {
  const res = await fetch(`https://api.example.com/comments/${id}`);
  return res.json();
});
```

같은 요청 안에서 `fetchComment('42')` 를 여러 번 호출해도 *내부 Promise 가 한 번만 생성* 된다. 클라이언트에서는 명시적 캐싱을 직접 하거나 React Query 의 thenable 을 노출해 `use` 에 전달한다.

## 3. Action function — form action 의 의미 변화

19 에서 `<form action={fn}>` 의 `action` 이 *함수* 를 받을 수 있게 되었다. 함수는 form 데이터를 `FormData` 로 받아 처리한다. 서버에서 정의된 함수에 `'use server'` 디렉티브를 붙이면 *Server Action* 이 된다. RSC bundler 가 이 함수의 *직렬화 가능한 참조* 를 클라이언트로 전달하고, 클라이언트는 *RPC 호출* 처럼 인보크한다. 응답은 새 RSC payload 와 함께 stream 으로 돌아온다.

```tsx
async function createComment(formData: FormData) {
  'use server';
  await db.insert({ text: formData.get('text') });
  revalidatePath('/posts/[id]');
}
```

## 4. `useActionState` — pending·data·error 의 표준 슬롯

```tsx
const [state, formAction, isPending] = useActionState(createComment, initialState);
```

reducer 와 비슷하다. action 의 첫 인자는 *이전 state*, 두 번째는 `FormData`. 반환은 `[현재 state, wrapped action, isPending]`. wrapped action 을 form 에 넘기면 자동으로 pending 표시·state 갱신이 처리된다. 서버 액션과 결합할 때 wrapped action 자체가 서버까지 RPC 로 전달된다. 즉 *동일한 action 함수가 클라이언트 form 과 서버 핸들러 양쪽에서 동작* 한다. 이 일관성이 19 의 *isomorphic mutation* 모델의 핵심이다.

## 5. `useFormStatus` — 자식 컴포넌트에서 부모 form 의 상태 보기

```tsx
function SubmitButton() {
  const { pending } = useFormStatus();
  return <button disabled={pending}>{pending ? '저장 중...' : '저장'}</button>;
}
```

prop drilling 이 필요 없다. 단점— 같은 form 안에서만 동작하므로, form 밖에서 호출하면 `pending: false` 로 무의미한 값이 나온다. 의도치 않게 사용처가 form 밖으로 이동했을 때 *조용히 깨진다*.

## 6. `useOptimistic` — 낙관적 업데이트와 자동 롤백

server action 의 응답을 기다리지 않고 UI 를 먼저 업데이트하고 싶을 때 `useOptimistic` 을 쓴다.

```tsx
const [optimistic, addOptimistic] = useOptimistic(
  comments,
  (state: Comment[], newComment: Comment) => [...state, { ...newComment, sending: true }]
);
```

핵심 동작 — `addOptimistic` 으로 추가한 항목은 *현재 transition 이 끝나면 자동으로 사라진다*. server action 이 새 RSC 트리를 push 하면 그 트리의 진짜 데이터가 optimistic 데이터를 대체한다. 실패해 server 상태가 바뀌지 않아도 optimistic 은 *transition 종료 시점에 사라지므로* 사용자에게 일관된 view 가 된다. 중복 항목을 막으려면 *client-side UUID*(crypto.randomUUID) 를 미리 발급해 form 의 hidden field 에 같이 넘긴다.

## 7. Server Component 와 `use(promise)` 의 직렬화

RSC 에서 `use(promise)` 는 *컴포넌트가 fulfilled 결과를 받은 뒤* 트리를 렌더링한다. 즉 서버는 Promise 의 *결과* 만 wire 로 보내고, 클라이언트는 그 결과를 그대로 받아 hydrate 한다.

그러나 *server 가 client 에 Promise 자체를 보낼 수도* 있다. RSC payload 의 일부로 *직렬화된 thenable* 이 들어가고, 클라이언트의 `use(promise)` 가 hydration 도중 그 thenable 을 받아 *streaming* 형태로 부분 렌더링한다. 이 패턴은 *서버에서 fetch 시작 → 클라이언트에서 await*. *streaming SSR* 의 자연스러운 표현형.

직렬화 가능한 타입에 *function 은 포함되지 않는다*. 따라서 server 가 만든 Promise 의 resolve 값에 함수가 들어 있으면 직렬화 에러. server action(`'use server'` 함수)만 예외적으로 *RPC 참조* 로 직렬화된다.

## 8. Next.js 15 App Router 와의 통합

`fetch` 의 *기본 캐싱이 no-store* 로 변경됐다(15부터). 명시적으로 캐싱하려면 `fetch(url, { next: { revalidate: 60 } })` 또는 `cache: 'force-cache'`. `revalidatePath` / `revalidateTag` 는 server action 안에서 호출되어 *해당 경로/태그* 의 캐시를 무효화한다. Server Action 은 *기본적으로 POST* 로만 호출되고 같은 origin 에서만 사용된다. CSRF 는 *Next.js 내부 form action ID* 검증으로 막힌다.

## 9. 마이그레이션 체크리스트 — 18 → 19 / 14 → 15

18 → 19 변경에서 깨질 가능성이 있는 항목. `forwardRef` 가 *deprecated*. 함수 컴포넌트가 prop `ref` 를 직접 받을 수 있다. `<Context.Provider>` 가 `<Context>` 로 단축. `PropTypes` 와 `defaultProps`(함수 컴포넌트) 제거. `useTransition` 의 callback 이 *async* 가능해 server action 과 자연스러운 결합.

14 → 15 변경에서. `fetch` 기본 캐싱 변경(no-store). `cookies()` / `headers()` 가 *Promise 반환* 으로 변경. GET route handler 의 *기본 dynamic* 동작이 변경. 이 4개를 한 PR 로 옮기면 코드 베이스 60~80% 가 영향을 받는다. *단계적* 으로 *fetch 옵션 명시 → cookies/headers 비동기화 → forwardRef 제거* 순서가 안전하다.

## 참고

- React 19 Release Notes <https://react.dev/blog/2024/12/05/react-19>
- `use` API 레퍼런스 <https://react.dev/reference/react/use>
- Server Components RFC
- Next.js 15 Upgrade Guide <https://nextjs.org/docs/app/guides/upgrading/version-15>
- "Forms in React" 시리즈 — Vercel Engineering Blog
