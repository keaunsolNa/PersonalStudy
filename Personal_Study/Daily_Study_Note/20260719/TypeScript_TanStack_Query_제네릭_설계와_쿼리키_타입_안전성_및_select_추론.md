Notion 원본: https://www.notion.so/3a25a06fd6d3817d9d82fa59383bcd82

# TypeScript TanStack Query 제네릭 설계와 쿼리키 타입 안전성 및 select 추론

> 2026-07-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `useQuery` 의 4개 제네릭 파라미터가 각각 어디로 흘르는지 추적한다.
- `queryFn` 반환 타입에서 `data` 타입이 자동 추론되는 메커니즘을 설명한다.
- `select` 옵션이 `TData` 를 변형하면서도 캐시 타입을 보존하는 원리를 이해한다.
- 쿼리키 팩토리와 `queryOptions` 로 키·타입을 한 곳에서 안전하게 묶는다.

## 1. 왜 라이브러리가 제네릭을 4개나 쓰는가

```typescript
function useQuery<
  TQueryFnData = unknown,   // queryFn 원본 타입
  TError = DefaultError,
  TData = TQueryFnData,     // select 적용 후 최종 data
  TQueryKey extends QueryKey = QueryKey,
>(options: UseQueryOptions<TQueryFnData, TError, TData, TQueryKey>): UseQueryResult<TData, TError>;
```

`TQueryFnData` 는 서버 원본, `TData` 는 `select` 가공 후 UI 타입이라 둘이 다를 수 있다. 분리하지 않으면 "캐시에는 원본, 컴포넌트에는 가공본"이 타입으로 표현되지 않는다. 설계 원칙은 "독립적으로 변하는 타입은 별도 파라미터로 분리"다.

## 2. queryFn 에서 data 타입이 흘러나오는 경로

```typescript
async function fetchUser(id: number): Promise<User> { /* ... */ }
const query = useQuery({ queryKey: ['user', 1] as const, queryFn: () => fetchUser(1) });
query.data;  // User | undefined
if (query.isSuccess) query.data;  // User
```

`queryFn` 반환이 `Promise<User>` 라 `TQueryFnData = User`. `data` 가 `User | undefined` 인 이유는 로딩 중에는 데이터가 없기 때문이다. `UseQueryResult` 는 `status` 판별 유니온이라 `isSuccess` 로 좁히면 `undefined` 가 사라진다.

## 3. select: 캐시 타입을 지키면서 UI 타입을 바꾸기

```typescript
const userName = useQuery({
  queryKey: ['user', 1] as const,
  queryFn: () => fetchUser(1),
  select: (user) => user.name,   // user: User 추론
});
userName.data;  // string | undefined
```

`select: (user: TQueryFnData) => TData` 이므로 반환값이 `TData` 를 결정한다. 원본은 `User` 로 캐시에 남고 UI 만 `string` 을 본다. `select` 함수는 매 렌더 새로 만들어지면 재계산되므로 무거운 변환은 `useCallback` 으로 참조를 고정한다.

## 4. 판별 유니온과 status 좁히기

```typescript
type QueryResult<TData, TError> =
  | { status: 'pending'; data: undefined; error: null }
  | { status: 'error'; data: undefined; error: TError }
  | { status: 'success'; data: TData; error: null };
```

판별 유니온은 `data!` 같은 단언을 없애기 위한 설계다. 조기 반환 패턴(`if (query.isPending) return ...`)으로 좁히면 이후 `query.data` 는 `User` 로 확정된다.

## 5. 쿼리키 팩토리

```typescript
const userKeys = {
  all: ['users'] as const,
  lists: () => [...userKeys.all, 'list'] as const,
  detail: (id: number) => [...userKeys.all, 'detail', id] as const,
};
queryClient.invalidateQueries({ queryKey: userKeys.all });
```

`as const` 가 핵심이다. 없으면 `string[]` 로 넓혐져 키의 각 위치 타입이 사라진다. 튜플 리터럴을 고정하면 `queryKey[2]` 를 `number` 로 정확히 꺼낼 수 있다.

## 6. queryOptions 헬퍼

```typescript
function userDetailOptions(id: number) {
  return queryOptions({ queryKey: userKeys.detail(id), queryFn: () => fetchUser(id) });
}
const q = useQuery(userDetailOptions(1));
const cached = queryClient.getQueryData(userDetailOptions(1).queryKey); // User | undefined
```

`queryOptions` 는 런타임 항등 함수지만 타입 수준에서 `queryKey` 와 `queryFn` 반환 타입을 묶어 `getQueryData` 까지 전파한다.

## 7. 흔한 타입 실수

`res.json()` 은 `Promise<any>` 라 그대로 두면 `data` 가 `any` 로 오염된다. 반환 타입을 명시하거나 Zod 로 파싱한다. `enabled: false` 로 조건부 실행하면 영원히 `pending` 일 수 있어 `isSuccess` 로 좁히는 것이 안전하다.

## 8. 설계 원칙 정리

독립적으로 변하는 타입은 분리하고, 기본값으로 흔한 경우를 무료로 만들며, 판별 유니온으로 단언을 없애고, `as const` 와 헬퍼로 키·타입을 결속한다. 이 원칙은 제네릭이 많은 라이브러리 API 를 읽는 렌즈로도 유용하다.

## 참고

- TanStack Query 공식 문서 — "TypeScript", "Query Options", "Query Keys"
- TanStack Query v5 마이그레이션 가이드
- TkDodo 블로그 — "React Query and TypeScript"
- TypeScript Handbook — "Generics", "Discriminated Unions"
