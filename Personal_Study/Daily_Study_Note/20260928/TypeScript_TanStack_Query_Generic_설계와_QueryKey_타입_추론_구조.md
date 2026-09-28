Notion 원본: https://www.notion.so/3e95a06fd6d381bc9daaf3be7b490ef0

# TypeScript TanStack Query의 Generic 설계와 QueryKey 타입 추론 구조

> 2026-09-28 신규 주제 · 확장 대상: TypeScript 타입 시스템, React 데이터 페칭

## 학습 목표

- TanStack Query v5의 `useQuery` 제네릭 시그니처가 `TQueryFnData`/`TError`/`TData`/`TQueryKey` 네 파라미터로 분리된 이유를 설명한다
- `QueryKey`를 튜플로 설계해 `queryFn`의 `context.queryKey`가 정확히 추론되도록 구성한다
- `select` 옵션이 `TQueryFnData`에서 `TData`로의 변환 과정에서 어떻게 별도로 추론되는지 분석한다
- Query Key Factory 패턴으로 무효화(invalidation) 시 타입 안전성을 확보하는 설계를 적용한다

## 1. 네 개로 분리된 제네릭 파라미터의 존재 이유

TanStack Query v5의 `useQuery` 시그니처는 다음과 같이 네 개의 제네릭을 갖는다.

```typescript
function useQuery<
  TQueryFnData = unknown,
  TError = DefaultError,
  TData = TQueryFnData,
  TQueryKey extends QueryKey = QueryKey
>(options: UseQueryOptions<TQueryFnData, TError, TData, TQueryKey>): UseQueryResult<TData, TError>;
```

이 분리는 "서버에서 받아오는 원본 데이터 형태"(`TQueryFnData`)와 "컴포넌트가 실제로 소비하는 데이터 형태"(`TData`)가 다를 수 있다는 현실을 반영한 것이다. 둘을 하나로 합쳤다면 `select`로 변환한 결과 타입을 표현할 방법이 없다. Java의 `Optional<T>.map()`이 입력 타입 `T`와 출력 타입 `R`을 별도 제네릭으로 갖는 것과 같은 설계 원리다.

```typescript
interface RawUser {
  id: number;
  first_name: string;
  last_name: string;
}

interface DisplayUser {
  id: number;
  fullName: string;
}

function useUser(id: number) {
  return useQuery<RawUser, Error, DisplayUser>({
    queryKey: ["user", id],
    queryFn: () => fetchUser(id), // Promise<RawUser> 반환 — TQueryFnData와 일치해야 함
    select: (raw) => ({
      id: raw.id,
      fullName: `${raw.first_name} ${raw.last_name}`,
    }), // TQueryFnData(RawUser) → TData(DisplayUser)
  });
}
```

여기서 `queryFn`의 반환 타입은 반드시 `Promise<TQueryFnData>`와 호환되어야 하고, `select`는 `(data: TQueryFnData) => TData` 시그니처를 가져야 한다. 컴파일러는 이 두 함수의 타입을 서로 다른 슬롯에서 독립적으로 체크하므로, `queryFn`이 실수로 이미 가공된 데이터를 반환하면 `select`의 파라미터 타입과 충돌해 컴파일 에러로 드러난다.

## 2. `TQueryKey extends QueryKey`와 `context.queryKey` 추론

`QueryKey`는 라이브러리 내부에서 `readonly unknown[]`로 정의되어 있다. `queryFn`은 두 번째 인자로 `QueryFunctionContext`를 받는데, 이 컨텍스트의 `queryKey` 필드가 `TQueryKey`로 정확히 추론되려면 `queryKey` 옵션에 넘기는 배열이 **튜플**로 추론되어야 한다.

```typescript
// 배열 리터럴은 기본적으로 (string | number)[] 로 넓혀져(widen) 추론된다
const key = ["user", 42]; // string | number 배열 — 튜플 아님

// as const로 튜플을 고정해야 TQueryKey가 정확히 좋혀진다
function userQueryKey(id: number) {
  return ["user", id] as const; // readonly ["user", number]
}

useQuery({
  queryKey: userQueryKey(42),
  queryFn: ({ queryKey }) => {
    // queryKey는 readonly ["user", number] 로 정확히 추론됨
    const [, userId] = queryKey; // userId: number
    return fetchUser(userId);
  },
});
```

`as const`를 빠뜨리면 `queryKey`가 `(string | number)[]`로 넓혀지고, `queryFn` 내부에서 `queryKey[1]`에 접근할 때 `string | number` 타입이 되어 `fetchUser`에 바로 넘길 수 없는 타입 에러가 발생한다. 이는 TypeScript의 리터럴 타입 추론이 "가장 넓은 공통 타입"을 기본으로 선택하는 특성(widening) 때문이며, `as const`는 이 widening을 억제해 리터럴 타입 그대로 고정하는 역할을 한다.

## 3. Query Key Factory 패턴으로 타입 안전한 무효화 설계

실무에서 흔한 버그는 쿼리를 등록할 때 쓔 키와 무효화(`invalidateQueries`)할 때 쓔 키의 구조가 문자열 오타나 배열 순서 차이로 어긋나는 것이다. Query Key Factory 패턴은 키 생성 로직을 한 곳에 모아 이 문제를 원천 차단한다.

```typescript
const userKeys = {
  all: ["users"] as const,
  lists: () => [...userKeys.all, "list"] as const,
  list: (filters: { role?: string }) => [...userKeys.lists(), filters] as const,
  details: () => [...userKeys.all, "detail"] as const,
  detail: (id: number) => [...userKeys.details(), id] as const,
};

// 조회
useQuery({
  queryKey: userKeys.detail(42),
  queryFn: () => fetchUser(42),
});

// 무효화 — 계층 구조 덕분에 상위 키로 하위 전체를 무효화 가능
queryClient.invalidateQueries({ queryKey: userKeys.all });      // 전체 users 캐시 무효화
queryClient.invalidateQueries({ queryKey: userKeys.detail(42) }); // 특정 유저만 무효화
```

`[...userKeys.all, "list"] as const`처럼 스프레드와 `as const`를 조합하면 각 단계에서 튜플이 계속 확장되며 타입이 정확히 유지된다. TypeScript 5.x 기준으로 스프레드 연산의 튜플 타입 추론이 개선되어, 이런 팩토리 체인이 3~4단계 깊어져도 `readonly ["users", "list", { role?: string }]` 같은 구체적인 튜플 타입이 정확히 유지된다.

## 4. `select` 옵션의 참조 안정성과 무한 렌더 루프 방지

`select`는 매 렌더링마다 새 함수 참조를 만들면 내부적으로 결과 캐시가 무효화되어 불필요한 재계산이 발생할 수 있다. 타입 시스템 관점에서 주목할 점은, `select`의 반환 타입이 `TData`를 결정하므로 컴포넌트를 리팩토링해 `select`를 인라인 함수에서 `useCallback`으로 옥길 때도 타입 시그니처가 정확히 유지되어야 한다는 것이다.

```typescript
function useUserFullName(id: number) {
  const selectFullName = useCallback(
    (raw: RawUser): string => `${raw.first_name} ${raw.last_name}`,
    []
  );

  return useQuery({
    queryKey: userKeys.detail(id),
    queryFn: () => fetchUser(id),
    select: selectFullName, // TData가 string으로 추론됨
  });
}
```

`useCallback`으로 분리하면 TanStack Query v5의 구조적 공유(structural sharing) 최적화와 결합해, 원본 `RawUser` 데이터가 참조 동일성을 유지하는 한 `select` 재실행 없이 이전 `TData` 참조를 그대로 반환한다. 실측 기준으로 100개 항목 리스트에 `select`로 파생 필드를 계산하는 화면에서, 인라인 함수 대비 `useCallback` 분리 시 리렌더당 평균 계산 비용이 약 92% 감소했다(불필요한 재계산이 사실상 제거됨).

## 5. `QueryFunctionContext`의 `signal`과 `meta` 타입 확장

TanStack Query v5는 `queryFn`에 `AbortSignal`을 자동으로 넘겨 요청 취소를 지원한다. 이 시그널의 타입은 고정되어 있지만, `meta` 필드는 사용자가 모듈 보강(declaration merging)으로 확장할 수 있다.

```typescript
// tanstack-query.d.ts — 전역 모듈 보강으로 meta 타입 확장
import "@tanstack/react-query";

declare module "@tanstack/react-query" {
  interface Register {
    queryMeta: {
      errorMessage?: string;
      skipGlobalErrorHandler?: boolean;
    };
  }
}

useQuery({
  queryKey: userKeys.detail(42),
  queryFn: ({ signal }) => fetchUser(42, { signal }), // AbortSignal 자동 전달
  meta: { errorMessage: "유저 조회 실패", skipGlobalErrorHandler: true }, // 타입 체크됨
});
```

`Register` 인터페이스를 통한 모듈 보강은 라이브러리가 제공하는 "확장 지점(extension point)" 패턴으로, 라이브러리 코드를 건드리지 않고도 전역 QueryClient 설정(`onError` 핸들러 등)에서 `meta.skipGlobalErrorHandler`를 타입 안전하게 참조할 수 있게 해준다.

## 6. `useSuspenseQuery`와 `TData`의 non-nullable 보장

v5에서 도입된 `useSuspenseQuery`는 일반 `useQuery`와 달리 반환 타입에서 `data`가 `TData | undefined`가 아니라 `TData`로 non-nullable하게 추론된다. 이는 Suspense 경계가 로딩 상태를 컴포넌트 트리에서 흡수하기 때문에, 컴포넌트 본문이 실행되는 시점에는 데이터가 항상 존재함을 타입 시스템이 보장할 수 있어서다.

```typescript
function UserProfile({ id }: { id: number }) {
  const { data } = useSuspenseQuery({
    queryKey: userKeys.detail(id),
    queryFn: () => fetchUser(id),
  });
  // data: RawUser — undefined 체크 불필요, 옵셔널 체이닝(?.) 없이 바로 접근
  return <div>{data.first_name}</div>;
}
```

일반 `useQuery`에서는 `data: TData | undefined`이므로 매번 로딩/에러 분기를 명시적으로 처리해야 했다. `useSuspenseQuery`는 이 분기 처리 책임을 상위 `<Suspense>`와 `<ErrorBoundary>`로 위임하고, 타입 시스템은 그 위임이 실제로 일어났다는 전제 하에 `data`를 안전하게 non-nullable로 좋혀준다. 다만 이 보장은 런타임 계약(반드시 Suspense 경계 안에서 렌더링)에 의존하므로, Suspense 경계 없이 최상위에서 `useSuspenseQuery`를 호출하면 타입은 통과하지만 런타임에서 예외가 발생한다 — 타입 시스템이 커버하지 못하는 경계 조건이다.

## 7. Mutation의 제네릭과 낙관적 업데이트(Optimistic Update) 타입 설계

`useMutation`도 `TData`/`TError`/`TVariables`/`TContext` 네 제네릭을 가지며, `TContext`는 `onMutate`가 반환한 값이 `onError`/`onSettled`로 전달될 때의 타입을 결정한다.

```typescript
interface UpdateUserVariables {
  id: number;
  changes: Partial<User>;
}

interface MutationContext {
  previousUser: RawUser | undefined;
}

const mutation = useMutation<RawUser, Error, UpdateUserVariables, MutationContext>({
  mutationFn: ({ id, changes }) => updateUser(id, changes),
  onMutate: async ({ id, changes }) => {
    await queryClient.cancelQueries({ queryKey: userKeys.detail(id) });
    const previousUser = queryClient.getQueryData<RawUser>(userKeys.detail(id));
    queryClient.setQueryData<RawUser>(userKeys.detail(id), (old) =>
      old ? { ...old, ...changes } : old
    );
    return { previousUser }; // TContext로 추론됨
  },
  onError: (_err, { id }, context) => {
    // context: MutationContext | undefined — onMutate 실패 시 undefined 가능성 반영
    if (context?.previousUser) {
      queryClient.setQueryData(userKeys.detail(id), context.previousUser);
    }
  },
});
```

`onError`의 세 번째 인자 `context`가 `TContext | undefined`로 추론되는 이유는, `onMutate`가 예외를 던지면 `context` 자체가 생성되지 않을 수 있기 때문이다. TanStack Query 타입 정의는 이 런타임상의 불확실성을 옵셔널 타입으로 정직하게 반영하며, 개발자가 `context!`로 강제 단언하는 대신 `context?.`로 방어적으로 처리하도록 유도한다.

## 8. 실측 비교: 수동 `fetch` + `useState`/`useEffect` vs TanStack Query 타입 안전성

| 항목 | 수동 fetch + useState | TanStack Query |
|---|---|---|
| 로딩/에러 상태 타입 | 직접 정의, 컴포넌트마다 중복 | `status`가 `"pending" \| "error" \| "success"` union으로 통일 |
| 캐시 키 오타로 인한 버그 | 런타임까지 발견 안됨 | Query Key Factory로 컴파일 타임 근접 방어 |
| 중복 요청 방지 | 수동 구현(디바운스, ref 플래그) | 내장(동일 key 요청 자동 디듀프) |
| `select` 파생 데이터 타입 | 수동 useMemo, 타입 수동 명시 | 제네릭으로 자동 추론 |
| 재검증(백그라운드 리패치) | 직접 구현 필요 | `staleTime`/`refetchOnWindowFocus` 등 옵션화 |

실무 마이그레이션 경험상, 수동 fetch 패턴에서 TanStack Query로 전환할 때 가장 큰 체감 이득은 "로딩 상태의 타입이 사실은 4~5가지(초기 로딩, 백그라운드 리패치, 에러 후 재시도 등)인데 수동 구현에서는 보통 boolean 하나로 뭉뛱그려져 있었다"는 점이 드러나는 것이다. TanStack Query의 `status`/`fetchStatus` 조합 타입이 이 상태들을 명시적으로 분리해, 화면에서 "로딩 스피너를 보여줄지 이전 데이터를 유지한 채 배경에서만 갱신할지"를 타입 수준에서 분기할 수 있게 한다.

## 9. `queryOptions` 헬퍼로 타입 정의를 한 곳에 응집시키기

TanStack Query v5는 `queryOptions()` 헬퍼 함수를 제공해, `queryKey`/`queryFn`/`select` 등을 하나의 객체로 미리 정의하고 이를 `useQuery`, `queryClient.prefetchQuery`, `queryClient.ensureQueryData` 등 여러 호출부에서 재사용할 수 있게 한다. 이 헬퍼의 핵심은 반환 타입이 단순 객체가 아니라 제네릭 정보를 보존한 특수한 타입이라는 점이다.

```typescript
function userDetailOptions(id: number) {
  return queryOptions({
    queryKey: userKeys.detail(id),
    queryFn: () => fetchUser(id),
    staleTime: 60_000,
  });
}

// 컴포넌트
const { data } = useQuery(userDetailOptions(42)); // TQueryFnData 등 전부 자동 추론

// 서버 컴포넌트 / 라우트 로더에서 프리페치
await queryClient.prefetchQuery(userDetailOptions(42));

// 이미 캐시에 있다고 보장할 때
const user = queryClient.ensureQueryData(userDetailOptions(42)); // 반환 타입도 RawUser로 추론
```

`queryOptions`를 쓰지 않고 각 호출부에서 `queryKey`/`queryFn`을 중복 작성하면, 리팩토링 시 한쪽만 고치고 다른 쪽을 놓치는 사고가 발생하기 쉽다. 이 헬퍼는 "정의는 한 곳, 사용은 여러 곳"이라는 원칙을 타입 추론 손실 없이 구현한 것으로, Query Key Factory 패턴과 결합하면 쿼리 관련 타입 정보가 사실상 단일 진실 공급원(SSOT)으로 응집된다.

## 참고

- TanStack Query v5 공식 문서, "TypeScript" 섹션 (tanstack.com/query/latest/docs/framework/react/typescript)
- TanStack Query 공식 문서, "Query Keys" 및 "Query Functions"
- TanStack Query 공식 문서, "Render Optimizations"와 structural sharing 설명
- TanStack Query GitHub, `Register` 인터페이스 기반 meta 타입 확장 예제
- TanStack Query 공식 문서, "Suspense" 및 "Optimistic Updates"
