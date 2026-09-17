Notion 원본: https://www.notion.so/3de5a06fd6d3814ea51fc2f0b20df4ab

# React Server Components Flight 직렬화와 서버-클라이언트 경계 설계

> 2026-09-17 신규 주제 · 확장 대상: React 컴포넌트·훅 기초, Next.js Pages Router → RSC 런타임 내부

## 학습 목표

- Flight 스트림의 행 포맷을 읽고 서버가 무엇을 보내는지 해석한다
- `"use client"` 경계가 번들러와 런타임에서 각각 무엇을 만들어내는지 구분한다
- 직렬화 가능한 값의 경계를 판정하고 props 전달 오류를 사전에 걸러낸다
- Server Actions 의 보안 경계와 재검증 흐름을 설계 관점에서 판단한다

## 1. RSC 는 SSR 이 아니다

가장 자주 생기는 오해부터 정리한다. SSR 은 컴포넌트를 서버에서 **HTML 문자열로** 렌더링하고, 클라이언트가 같은 컴포넌트 코드를 내려받아 하이드레이션한다. 즉 컴포넌트 코드는 양쪽에 다 있다.

RSC 는 서버 컴포넌트를 서버에서만 실행하고 **컴포넌트 코드를 클라이언트에 보내지 않는다.** 대신 렌더링 결과를 특수한 직렬화 포맷(Flight)으로 스트리밍한다. 클라이언트는 그 결과를 받아 React 엘리먼트 트리로 복원한다.

차이가 만드는 결과는 세 가지다. 번들 크기가 서버 컴포넌트가 쓰는 라이브러리만큼 줄어든다(마크다운 파서, 날짜 라이브러리, ORM 클라이언트가 전부 서버에 남는다). 데이터 접근이 컴포넌트 안에서 직접 가능해져 별도 API 층이 필요 없어진다. 그리고 서버 컴포넌트는 상태를 가질 수 없다 — `useState`, `useEffect`, 이벤트 핸들러가 없다.

실제 앱에서는 세 가지가 겹친다. 서버 컴포넌트가 Flight 로 내려오고, 클라이언트 컴포넌트는 SSR 로 초기 HTML 을 만든 뒤 하이드레이션되며, 이후 내비게이션은 Flight 스트림만 받는다.

## 2. Flight 스트림의 실제 모양

Flight 페이로드는 줄 단위 텍스트다. 각 행은 `<id>:<type><payload>` 형태다.

```
0:D"$Sreact.suspense"
1:I["./chunks/client-8a3f.js","Counter"]
2:["$","div",null,{"className":"page","children":[["$","h1",null,{"children":"대시보드"}],["$L3",null,null]]}]
3:["$","@1",null,{"initialCount":5}]
```

읽는 규칙은 이렇다.

- `I` 행은 클라이언트 참조다. 청크 경로와 export 이름을 담는다. 실제 컴포넌트 코드는 여기 없고, 클라이언트가 이 정보로 청크를 로드한다.
- 배열 `["$", type, key, props]` 가 React 엘리먼트의 직렬화 형태다.
- `"$L3"` 은 "3번 행이 나중에 올 것"이라는 지연 참조다. Suspense 경계 안쪽이 아직 준비되지 않았을 때 쓰인다.
- `"$@1"` 처럼 `@` 가 붙으면 1번 행이 정의한 클라이언트 참조를 가리킨다.
- `"$undefined"`, `"$Infinity"`, `"$D2026-09-17T…"` 같이 `$` 접두 문자열은 JSON 이 표현 못 하는 값의 인코딩이다. 문자열이 진짜 `$` 로 시작하면 `$$` 로 이스케이프한다.

핵심은 이 포맷이 **스트리밍 가능**하다는 점이다. 서버는 전체 트리가 완성되기를 기다리지 않고 준비된 행부터 내보낸다. 느린 데이터 페칭이 Suspense 로 감싸져 있으면 그 자리에 `$L` 참조를 먼저 보내고, 데이터가 오면 해당 행을 뒤늦게 붙인다. 클라이언트는 받는 즉시 부분 렌더링을 갱신한다.

Next.js App Router 에서 이 스트림을 직접 보려면 RSC 요청 헤더를 붙인다.

```bash
curl -s 'https://example.com/dashboard' -H 'RSC: 1' | head -40
```

응답이 HTML 이 아니라 위 형태의 텍스트로 온다. 어떤 컴포넌트가 클라이언트 참조로 나가고 있는지 여기서 바로 확인할 수 있어, 번들 크기 문제를 추적할 때 유용하다.

## 3. `"use client"` 경계가 하는 일

`"use client"` 는 "이 파일부터 아래는 클라이언트"라는 선언이 아니라 **모듈 그래프의 경계 표시**다. 번들러는 이 지시어가 있는 모듈을 진입점으로 삼아 별도 청크를 만들고, 서버 빌드에서는 그 모듈을 실제 코드 대신 "클라이언트 참조 프록시"로 대체한다.

```
app/page.tsx  (서버)
 └─ components/Chart.tsx  "use client"     ← 경계
     └─ node_modules/recharts              ← 클라이언트 번들에 포함
     └─ lib/format.ts                      ← 클라이언트 번들에 포함
```

경계 아래로 import 되는 모든 것이 클라이언트 번들에 들어간다. 그래서 `"use client"` 를 파일 최상단에 습관적으로 붙이면 의도치 않게 서버 전용 코드가 딸려간다. 반대로 서버 전용 모듈에 `import "server-only"` 를 넣어두면 클라이언트 그래프에 섞였을 때 빌드가 실패해 사고를 막는다.

```ts
// lib/db.ts
import "server-only";               // 클라이언트에서 import 되면 빌드 에러
import { Pool } from "pg";
export const pool = new Pool({ connectionString: process.env.DATABASE_URL });
```

경계 설계의 실용 원칙은 **경계를 잎(leaf) 쪽으로 밀어내는 것**이다. 페이지 전체를 클라이언트로 만들지 말고, 상호작용이 필요한 최소 단위만 클라이언트로 둔다. 상태를 상위에서 관리해야 한다면 클라이언트 컴포넌트를 껍데기로 쓰고 서버 컴포넌트를 `children` 으로 주입한다.

```tsx
// app/page.tsx — 서버 컴포넌트
import { Tabs } from "./Tabs";           // "use client"
import { ReportTable } from "./ReportTable";  // 서버 컴포넌트

export default async function Page() {
    return (
        <Tabs>
            <ReportTable data={await loadReport()} />   {/* 서버에서 렌더됨 */}
        </Tabs>
    );
}
```

`Tabs` 는 클라이언트지만 `children` 으로 받은 `ReportTable` 은 서버에서 렌더링된 결과가 Flight 로 전달된다. 부모가 클라이언트라고 자식까지 클라이언트가 되지 않는다는 것이 이 패턴의 요점이다.

## 4. props 직렬화 경계

서버 → 클라이언트 props 는 Flight 로 직렬화 가능해야 한다.

| 전달 가능 | 전달 불가 |
|---|---|
| 원시값, `Date`, `BigInt`, `Map`, `Set` | 함수(Server Action 제외) |
| 배열·평범한 객체 | 클래스 인스턴스, `Symbol` |
| `Promise` (클라이언트가 `use()` 로 언래핑) | `Error` 객체 (직렬화는 되나 제한적) |
| JSX 엘리먼트 | 순환 참조를 가진 객체 |
| Server Action 참조 | getter 가 있는 객체 |

가장 자주 걸리는 것이 ORM 이 반환한 모델 인스턴스다. Prisma 나 TypeORM 결과를 그대로 넘기면 프로토타입이 있는 클래스 인스턴스라 거부된다. 평범한 객체로 매핑해야 한다.

```tsx
// 실패
const user = await prisma.user.findUnique({ where: { id } });
return <Profile user={user} />;       // Decimal, 클래스 인스턴스 포함 시 에러

// 성공 — 경계에서 DTO 로 변환
return <Profile user={{ id: user.id, name: user.name, score: user.score.toNumber() }} />;
```

이 제약은 불편해 보이지만 좋은 규율을 강제한다. 서버 → 클라이언트 경계가 곧 API 계약이므로, 그 자리에서 명시적으로 형태를 정하게 만든다. 응답 크기도 통제된다 — 무심코 전체 컬럼 조회 결과를 통째로 넘기면 Flight 페이로드가 그만큼 커지고 그게 네트워크로 나간다.

`Promise` 전달은 흥미로운 패턴이다. 서버에서 `await` 하지 않고 Promise 그대로 넘기면 스트리밍이 유지된다.

```tsx
// 서버
export default function Page() {
    const commentsPromise = fetchComments();    // await 하지 않음
    return (
        <>
            <Article />
            <Suspense fallback={<Skeleton />}>
                <Comments promise={commentsPromise} />
            </Suspense>
        </>
    );
}

// 클라이언트
"use client";
import { use } from "react";
export function Comments({ promise }) {
    const comments = use(promise);              // 해결될 때까지 Suspense
    return <ul>{comments.map(c => <li key={c.id}>{c.body}</li>)}</ul>;
}
```

`Article` 이 즉시 내려가고 댓글은 준비되는 대로 뒤따라온다. 페이지 전체가 가장 느린 데이터에 묶이는 문제를 푼다.

## 5. Server Actions 의 실체와 보안

`"use server"` 가 붙은 함수는 클라이언트에서 호출 가능한 엔드포인트가 된다. 번들러가 각 액션에 ID 를 부여하고, 클라이언트에는 그 ID 로 POST 를 보내는 스텁만 남는다.

```ts
// app/actions.ts
"use server";
import { revalidatePath } from "next/cache";
import { auth } from "@/lib/auth";

export async function deletePost(id: string) {
    const session = await auth();                      // 반드시 여기서 검증
    if (!session) throw new Error("Unauthorized");

    const post = await db.post.findUnique({ where: { id } });
    if (post?.authorId !== session.userId) throw new Error("Forbidden");

    await db.post.delete({ where: { id } });
    revalidatePath("/posts");
}
```

인증 검사를 액션 **안에서** 해야 한다는 점이 중요하다. UI 에서 삭제 버튼을 숨겼다고 해서 액션이 보호되지 않는다. 액션 ID 만 알면 누구나 POST 할 수 있으므로, 모든 Server Action 은 공개 API 엔드포인트와 동일한 수준으로 취급해야 한다. 이것이 RSC 도입 시 가장 흔한 보안 실수다.

폼과 결합하면 JS 없이도 동작한다.

```tsx
export default function DeleteButton({ id }: { id: string }) {
    const deleteWithId = deletePost.bind(null, id);
    return (
        <form action={deleteWithId}>
            <button type="submit">삭제</button>
        </form>
    );
}
```

`bind` 로 넘긴 인자는 직렬화돼 클라이언트를 왕복한다. 즉 **클라이언트가 조작할 수 있다.** 사용자 ID 를 bind 로 넘기고 서버에서 그대로 믿는 코드는 취약하다. 신뢰 가능한 값은 항상 서버 세션에서 다시 읽어야 한다.

액션 반환값도 Flight 로 직렬화된다. `useActionState` 로 폼 상태를 받는 패턴이 표준이다.

```tsx
"use client";
import { useActionState } from "react";

export function Form({ action }) {
    const [state, formAction, pending] = useActionState(action, { error: null });
    return (
        <form action={formAction}>
            <input name="title" />
            {state.error && <p role="alert">{state.error}</p>}
            <button disabled={pending}>저장</button>
        </form>
    );
}
```

## 6. 캐시와 재검증의 층

App Router 에는 캐시 층이 여러 개 겹쳐 있고, 어느 층을 건드리는지 구분하지 못하면 "왜 갱신이 안 되지" 상황이 반복된다.

- **Request Memoization**: 한 번의 렌더 안에서 동일 `fetch` 중복 제거. 렌더 종료 시 소멸.
- **Data Cache**: `fetch` 결과를 서버에 영속 저장. `revalidate` 옵션과 `revalidateTag` 로 제어.
- **Full Route Cache**: 정적 렌더된 라우트의 HTML 과 Flight 페이로드. `revalidatePath` 로 무효화.
- **Router Cache**: 클라이언트 메모리의 Flight 페이로드. 뒤로 가기 시 재요청을 막는다.

`revalidateTag` 는 Data Cache 와 Full Route Cache 를 무효화하지만 이미 로드된 브라우저의 Router Cache 는 건드리지 못한다. Server Action 응답에 재검증 신호가 함께 실려 나가므로 액션 이후에는 자동으로 갱신되지만, 다른 탭이나 다른 사용자의 브라우저는 다음 요청까지 옛 데이터를 본다.

```ts
await fetch(url, { next: { tags: ["posts"], revalidate: 3600 } });
// 액션 안에서
revalidateTag("posts");     // 태그 걸린 fetch 전부 무효화
```

캐시 설정 API 는 Next.js 버전 사이에 변경이 잦았다. 프로젝트의 Next.js 버전 문서를 기준으로 확인하는 것이 안전하다.

## 7. 성능 관점의 함정

RSC 가 자동으로 빠르게 만들어주지 않는다. 오히려 나빠지는 패턴이 있다.

**폭포수 데이터 페칭.** 서버 컴포넌트에서 `await` 를 순차로 쓰면 그대로 직렬 실행된다. 독립적인 요청은 `Promise.all` 로 묶어야 한다. 부모가 `await` 를 끝내야 자식이 렌더되므로, 부모-자식 관계로 페칭이 이어지면 깊이만큼 지연이 쌓인다.

**과도한 Flight 페이로드.** 서버 컴포넌트가 렌더한 대형 테이블은 HTML 대신 Flight 엘리먼트 배열로 내려온다. 행이 1만 개면 페이로드가 수 MB 가 된다. 가상 스크롤이나 페이지네이션이 필요한 것은 클라이언트 렌더링과 동일하다.

**경계 오남용.** `"use client"` 를 최상위에 두면 RSC 의 이점이 전부 사라지고, 오히려 App Router 의 오버헤드만 남는다. 마이그레이션 중간 상태에서 자주 발생한다.

측정은 다음으로 한다. 네트워크 탭에서 RSC 요청의 크기와 TTFB, 그리고 클라이언트 번들 크기를 `@next/bundle-analyzer` 로 확인한다. 서버 컴포넌트로 옮겼는데 클라이언트 번들이 줄지 않았다면 경계가 잘못 그어진 것이다.

## 8. 채택 판단

RSC 가 확실히 유리한 경우는 콘텐츠 중심 페이지, 대시보드처럼 서버 데이터가 많고 상호작용이 국소적인 화면, 무거운 라이브러리(마크다운, 구문 강조, 차트 데이터 가공)를 서버에 둘 수 있는 경우다.

불리한 경우도 있다. 페이지 전체가 실시간 상호작용인 에디터나 캔버스 앱은 어차피 대부분이 클라이언트 컴포넌트라 얻을 것이 적고, 경계 규칙만 부담이 된다. 팀이 서버/클라이언트 구분에 익숙하지 않은 상태에서 도입하면 직렬화 오류와 경계 오남용으로 개발 속도가 느려진다.

기존 Pages Router 앱을 옮긴다면 라우트 단위로 점진 이전하는 것이 안전하다. App Router 와 Pages Router 는 공존 가능하며, 데이터 접근 계층을 먼저 `server-only` 로 격리해두면 이후 이전이 수월해진다.

## 참고

- React 문서, Server Components — https://react.dev/reference/rsc/server-components
- React 문서, Server Functions / `"use server"` — https://react.dev/reference/rsc/server-functions
- Next.js 문서, App Router 렌더링 — https://nextjs.org/docs/app/getting-started/server-and-client-components
- Next.js 문서, Caching — https://nextjs.org/docs/app/guides/caching
- React RFC, React Server Components — https://github.com/reactjs/rfcs/blob/main/text/0188-server-components.md
- OWASP Cheat Sheet Series — https://cheatsheetseries.owasp.org/
