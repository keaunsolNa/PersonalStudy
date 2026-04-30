Notion 원본: https://app.notion.com/p/3525a06fd6d381ea98fef8cf5c708aae

# React 19 useOptimistic과 useFormStatus — Form Actions 통합 패턴

> 2026-04-30 신규 주제 · 확장 대상: React · Next.js Server Actions

## 학습 목표

- `useOptimistic` hook 의 dispatch / commit / revert 흐름을 React 18 패턴과 비교
- `useFormStatus` 가 가까운 `<form>` 의 pending 상태를 어떻게 추적하는지 분석
- Server Action 실패 시 optimistic state 롤백을 React 가 자동 보장하는 메커니즘
- 좋아요·댓글·체크리스트 같은 UI에서 깜빡임 없는 사용자 경험 구현

## 1. React 18 의 한계와 React 19 의 응답

React 18 에서 "버튼 누르면 즉시 반영" 을 만들려면 다음을 직접 짰다.

- 로컬 상태에 새 항목 push.
- `fetch` 로 API 호출.
- 응답 도착 시 서버가 부여한 ID 로 로컬 항목 갱신.
- 실패 시 push 취소.

이 패턴은 비동기 race(연속 클릭, 빠른 입력)와 에러 복구가 까다롭다. React 19 는 두 hook 으로 표준 해법을 제공한다.

- `useOptimistic(state, reducer)` → 낙관적 미리보기 상태 관리
- `useFormStatus()` → 부모 form 의 pending / data / method / action 노출

이 둘은 Server Actions(Next.js 14+) 와 결합돼 "form 제출 → 즉시 UI 반영 → 서버 응답 → 자동 commit/revert" 의 파이프라인을 이룬다.

## 2. useOptimistic 시그니처

```ts
function useOptimistic<State, Optimistic>(
  state: State,
  updateFn: (currentState: State, optimistic: Optimistic) => State
): [State, (optimistic: Optimistic) => void];
```

반환된 첫 번째는 "현재 commit 된 상태에 낙관적 변경을 합친 결과" 다. 두 번째는 dispatch 함수. 호출하면 React 가 다음 렌더에 `updateFn` 을 실행해 임시 상태를 만든다.

핵심: 이 임시 상태는 **transition 이 끝날 때까지만 유지**된다. transition 이 성공하면 `state` (서버가 준 진짜 상태)가 갱신되고 임시 dispatch 는 모두 지워진다. 실패하면 자동으로 롤백 → `state` 그대로.

## 3. 좋아요 버튼 — 가장 단순한 예

```tsx
"use client";

import { useOptimistic, useTransition } from "react";
import { toggleLikeAction } from "./actions";

type Props = {
  postId: string;
  initialLikes: number;
  initialLiked: boolean;
};

export function LikeButton({ postId, initialLikes, initialLiked }: Props) {
  const [, startTransition] = useTransition();
  const [optimistic, setOptimistic] = useOptimistic(
    { likes: initialLikes, liked: initialLiked },
    (state, action: "toggle") => ({
      likes: state.liked ? state.likes - 1 : state.likes + 1,
      liked: !state.liked,
    })
  );

  return (
    <button
      onClick={() => {
        startTransition(async () => {
          setOptimistic("toggle");
          await toggleLikeAction(postId);
        });
      }}
      aria-pressed={optimistic.liked}
    >
      {optimistic.liked ? "♥" : "♡"} {optimistic.likes}
    </button>
  );
}
```

`startTransition` 안에서 `setOptimistic` 을 부르고 곧바로 server action 을 await 한다. 서버에서 revalidate 된 새 props 가 넘어오면 React 는 옛 optimistic 을 버리고 진짜 값으로 재렌더한다.

`useTransition` 없이 직접 부르면 "non-urgent" 표시가 안 돼 React 가 commit 즉시 임시 상태를 지운다. 반드시 transition 으로 감싼다.

## 4. 체크리스트 — 다중 항목 낙관 UI

```tsx
"use client";

import { useOptimistic, useTransition } from "react";
import { toggleTodoAction } from "./actions";

type Todo = { id: string; text: string; done: boolean };

type Action =
  | { kind: "toggle"; id: string }
  | { kind: "add"; todo: Todo };

export function TodoList({ todos }: { todos: Todo[] }) {
  const [, startTransition] = useTransition();
  const [optimistic, dispatch] = useOptimistic(todos, (state, action: Action) => {
    switch (action.kind) {
      case "toggle":
        return state.map((t) => (t.id === action.id ? { ...t, done: !t.done } : t));
      case "add":
        return [...state, action.todo];
    }
  });

  return (
    <ul>
      {optimistic.map((t) => (
        <li key={t.id}>
          <button
            onClick={() => {
              startTransition(async () => {
                dispatch({ kind: "toggle", id: t.id });
                await toggleTodoAction(t.id);
              });
            }}
          >
            {t.done ? "☑" : "☐"} {t.text}
          </button>
        </li>
      ))}
    </ul>
  );
}
```

여러 클릭이 빠르게 들어와도 React 는 모든 transition 의 dispatch 를 누적해서 보여 준다. 마지막 transition 이 끝나면 모든 optimistic dispatch 가 비워지고 새 props 가 들어온다.

## 5. useFormStatus — 자식 컴포넌트의 자동 pending 표시

```tsx
"use client";

import { useFormStatus } from "react-dom";

export function SubmitButton({ children }: { children: React.ReactNode }) {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending} aria-busy={pending}>
      {pending ? "저장 중…" : children}
    </button>
  );
}
```

이 컴포넌트는 prop 으로 pending 을 받지 않는다. **가장 가까운 `<form>` 의 상태**를 React 가 context 로 흘려준다. `<form action={createTodoAction}>` 을 부모에 두면 동작.

```tsx
"use client";

import { useOptimistic, useRef } from "react";
import { createTodoAction } from "./actions";
import { SubmitButton } from "./SubmitButton";

export function NewTodoForm({ todos }: { todos: Todo[] }) {
  const formRef = useRef<HTMLFormElement>(null);
  const [optimistic, addOptimistic] = useOptimistic(
    todos,
    (state, text: string) => [...state, { id: crypto.randomUUID(), text, done: false }]
  );

  return (
    <>
      <form
        ref={formRef}
        action={async (formData: FormData) => {
          const text = String(formData.get("text") ?? "");
          if (!text.trim()) return;
          addOptimistic(text);
          formRef.current?.reset();
          await createTodoAction(text);
        }}
      >
        <input name="text" autoComplete="off" />
        <SubmitButton>추가</SubmitButton>
      </form>
      <ul>
        {optimistic.map((t) => (
          <li key={t.id}>{t.text}</li>
        ))}
      </ul>
    </>
  );
}
```

`<form action={...}>` 에 함수 자체를 넘기면 React 가 자동으로 transition 으로 감싸 실행한다. 이 안에서 `useOptimistic` 의 dispatcher 를 호출하면 transition 종료 시 자동 commit/revert 된다.

## 6. 에러 처리 패턴

서버 action 이 throw 하면 transition 도 reject 되고 optimistic dispatch 가 모두 revert 된다. 사용자에게 메시지를 주려면 form 의 새 hook 인 `useActionState` 를 함께 쓴다.

```tsx
"use client";

import { useActionState } from "react";
import { createTodoAction } from "./actions";

type ActionResult = { ok: boolean; error?: string };

async function action(prev: ActionResult, formData: FormData): Promise<ActionResult> {
  try {
    await createTodoAction(String(formData.get("text") ?? ""));
    return { ok: true };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}

export function FormWithError() {
  const [state, submit] = useActionState(action, { ok: true });
  return (
    <form action={submit}>
      <input name="text" />
      {state.error && <p role="alert">{state.error}</p>}
      <button type="submit">추가</button>
    </form>
  );
}
```

## 7. 서버 사이드 책임 — revalidate 호출

낙관적 UI는 서버 측 캐시 무효화와 짝을 이룬다. Next.js 14+ Server Action 에선 다음 순서.

```ts
"use server";

import { revalidatePath } from "next/cache";
import { db } from "@/db";

export async function createTodoAction(text: string) {
  await db.todo.create({ data: { text } });
  revalidatePath("/todos");
}
```

`revalidatePath` 가 클라이언트로 "이 path의 RSC payload 다시 받아라" 를 알린다. 새 데이터가 stream 으로 도착하면 React 는 server component tree 를 새 props 로 교체 → useOptimistic 의 base state 가 갱신 → 임시 dispatch 비움.

## 8. useTransition + useOptimistic 의 race 처리

빠르게 두 번 클릭한 경우를 보자.

1. `t=0`: 클릭 1 → optimistic A 추가, action 1 시작.
2. `t=50ms`: 클릭 2 → optimistic B 추가, action 2 시작.
3. `t=300ms`: action 1 완료, 새 props 도착. React 는 transition 1 종료 → optimistic A revert. 그러나 B 는 아직 transition 2 안에 살아 있음.
4. `t=600ms`: action 2 완료, 새 props 도착. transition 2 종료 → optimistic B revert. 새 props 에 둘 다 포함.

이 보장이 useOptimistic 의 본질. 사용자 코드는 단순하지만 race 에 안전하다.

## 9. 트레이드오프

| 경우 | 적합 | 부적합 |
|---|---|---|
| 멱등성이 보장되는 UI(좋아요, 토글) | ✓ | |
| 결제·주문 확정 | | ✗ — 실제 완료 후 보여야 안전 |
| 결과가 서버 상태에 강하게 의존 | (응답 시간 지수가 짧으면 ✓) | (긴 처리 / 검증 실패 가능성 높으면 ✗) |
| 오프라인 우선 PWA | (보조로 활용) | (메인 큐는 별도 라이브러리) |

낙관적 UI 가 거짓말한 경우 **사용자가 그 거짓말을 바탕으로 다음 행동을 한다** 는 점이 가장 큰 리스크다. revert 시 명확한 토스트로 알리는 UX 를 항상 같이 설계한다.

## 참고

- React 19 공식 문서, `useOptimistic` / `useFormStatus` / `useActionState`
- Dan Abramov 발표, "What's new in React 19" — React Conf 2024
- Next.js 공식 문서, "Server Actions and Mutations"
- Lenz Weber-Tronic, "Optimistic UI in React 19" — 글로벌 상태와의 결합 분석
