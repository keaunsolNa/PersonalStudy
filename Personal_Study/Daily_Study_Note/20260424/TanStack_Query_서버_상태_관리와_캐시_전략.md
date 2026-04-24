Notion 원본: https://www.notion.so/34c5a06fd6d3814cb78fce12cdbdde58

# TanStack Query로 서버 상태 관리와 캐시 전략

> 2026-04-24 신규 주제 · 확장 대상: React (Hook/Context 학습됨)

## 학습 목표

- 서버 상태(원격 데이터)가 클라이언트 상태와 다른 이유를 체감한다
- staleTime vs gcTime (cacheTime)의 역할을 실측으로 구분한다
- useMutation + onMutate로 optimistic update와 rollback을 구현한다
- Next.js App Router + hydration에서 prefetchQuery + dehydrate/Hydrate 패턴을 쓴다

---

## 1. 왜 서버 상태가 별도로 관리되어야 하는가

Redux·Zustand 같은 해결책은 "클라이언트가 소유한 상태" 관리에 맞게 설계됐다. 서버 데이터는 개념적으로 다르다: 소유권이 서버에 있고 클라이언트는 조회된 복사본만 보유하는게 정말이다. 그래서 완전성 유지는 불가능하고, staleness, deduplication, refetch on focus/reconnect, cache eviction 같은 설계 포인트가 별도로 필요하다. TanStack Query(React Query v4+ 계열)는 이런 포인트를 표준화한 라이브러리다.

## 2. useQuery 기본

```typescript
import { useQuery } from '@tanstack/react-query';

export function useOrder(id: string) {
  return useQuery({
    queryKey: ['order', id],
    queryFn: async ({ signal }) => {
      const res = await fetch(`/api/orders/${id}`, { signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return res.json() as Promise<Order>;
    },
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    retry: (failureCount, err) => {
      if (err instanceof Response && err.status >= 400 && err.status < 500) return false;
      return failureCount < 3;
    },
  });
}
```

`queryKey`는 cache 식별자다. 배열 요소 가늠 비교이므로 id 같은 종속 인자를 배열에 넣으면 각각 별도 엔트리로 캐싱된다. `signal`은 AbortController와 연동된다—결과에 관심이 없어진 쿼리는 자동으로 abort 된다.

## 3. staleTime vs gcTime

| 개념 | 의미 | 기본값 |
|---|---|---|
| staleTime | 이 시간이 지날 때까지는 캐시가 신선(fresh)으로 간주되어 추가 fetch 안 함 | 0 (즉시 stale) |
| gcTime | 구독자가 0개가 된 뒤 이 시간이 지나면 GC 된다 | 5분 |

staleTime=0이면 컴포넌트가 마운트될 때마다 background refetch가 일어난다. 임직원 리스트처럼 자주 바뀌는 데이터는 0 그대로 두는 게 맞고, 상세 설정 같이 거의 안 바뀌는 값은 staleTime을 몇 분 이상으로 잡아 불필요한 fetch를 줄인다.

## 4. Mutations와 optimistic update

```typescript
const queryClient = useQueryClient();
const updateTitle = useMutation({
  mutationFn: (next: { id: string; title: string }) =>
    fetch(`/api/todos/${next.id}`, { method: 'PATCH', body: JSON.stringify({ title: next.title }) }),
  onMutate: async (next) => {
    await queryClient.cancelQueries({ queryKey: ['todos'] });
    const prev = queryClient.getQueryData<Todo[]>(['todos']);
    queryClient.setQueryData<Todo[]>(['todos'], (old) =>
      (old ?? []).map((t) => (t.id === next.id ? { ...t, title: next.title } : t))
    );
    return { prev };
  },
  onError: (_err, _vars, ctx) => {
    if (ctx?.prev) queryClient.setQueryData(['todos'], ctx.prev);
  },
  onSettled: () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
});
```

전체 순서: `cancelQueries` → 스냅샷 복사 → optimistic update → 실기에는 실제 요청 발생 → 성공/실패에 관계없이 `invalidateQueries`로 서버 데이터 재조회. 실패 시에는 context에 저장해둔 스냅샷으로 롤백.

## 5. Infinite Queries

```typescript
const feed = useInfiniteQuery({
  queryKey: ['feed'],
  queryFn: ({ pageParam = 0 }) =>
    fetch(`/api/feed?cursor=${pageParam}`).then((r) => r.json()),
  initialPageParam: 0,
  getNextPageParam: (last, all) => last.nextCursor ?? undefined,
});
```

무한 스크롤은 `fetchNextPage()` 호출로 이어지고, `pages`는 순서대로 보존된다. 캐시는 "전체 feed" 하나로 묶여 있어 세분화 무효화가 어렵다. 대신 변경 후엔 `queryClient.setQueryData(['feed'], ...)` 로 해당 페이지를 들어다 바꾸거나 전체 무효화한다.

## 6. Next.js App Router와 SSR Hydration

```typescript
// app/orders/[id]/page.tsx (Server Component)
import { dehydrate, HydrationBoundary, QueryClient } from '@tanstack/react-query';
export default async function Page({ params }: { params: { id: string } }) {
  const qc = new QueryClient();
  await qc.prefetchQuery({
    queryKey: ['order', params.id],
    queryFn: () => fetchOrderFromDb(params.id),
  });
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <OrderView id={params.id} />
    </HydrationBoundary>
  );
}
```

서버에서 조회한 데이터가 dehydrate 되어 HTML에 직렬화, 클라이언트에서 HydrationBoundary가 다시 캐시에 주입한다. 이 덕분에 `OrderView` 내부의 `useQuery(['order', id])`가 이미 fresh 상태로 시작해 refetch가 없다.

## 7. QueryClient 설정 튜닝

```typescript
new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      networkMode: 'online',
      retry: (count, err) => count < 3 && !(err as any)?.status?.toString().startsWith('4'),
    },
    mutations: { retry: 0 },
  },
});
```

모바일 웹과 같이 활성 탭이 빈번하게 교체되는 환경에서는 refetchOnWindowFocus=true가 유용하고, 탭이 오래 열려있는 대시보드는 영역별로 끄면서 `refetchInterval`로 주기 폴링을 쓰는 게 관습적.

## 8. Redux와의 비교 시나리오

| 상황 | TanStack Query | Redux |
|---|---|---|
| 서버 데이터 캐싱 | 기본 지원 | boilerplate 많음 |
| 폼 UI 상태 | 적합하지 않음 | 적합(RTK slice) |
| 항해 중 Undo | 부적절 | 적합(time-travel) |
| WebSocket로 실시간 동기화 | setQueryData로 재주입 | reducer + middleware |

두 개를 겹치지 않게 썼는 것이 매우 효과적이다. 서버 물질은 TanStack, 클라이언트 물질은 Redux에게 맡기는 패턴. 실시간 동기화는 WebSocket handler에서 `queryClient.setQueryData`를 호출해 query cache를 직접 패치하는 방식이 일반적이다.

## 참고

- TanStack Query 공식 문서: https://tanstack.com/query/latest
- "Practical React Query" by TkDodo (2021–2024): https://tkdodo.eu/blog/practical-react-query
- Next.js 공식 가이드 — React Query with App Router: https://tanstack.com/query/latest/docs/framework/react/guides/ssr
- Mark Erikson "When Redux is the right choice" (2019)
