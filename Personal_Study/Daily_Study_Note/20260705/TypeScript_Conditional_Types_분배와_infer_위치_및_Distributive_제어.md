Notion 원본: https://app.notion.com/p/3935a06fd6d3817c97f3f8185f7bd4ed

# TypeScript Conditional Types 분배와 infer 위치 및 Distributive 제어

> 2026-07-05 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 조건부 타입의 분배(distributive) 동작이 언제 발동하는지 구분한다
- `[T] extends [U]` 튜플 래핑으로 분배를 끄는 원리를 파악한다
- infer 를 공변·반공변 위치에 두었을 때 union/intersection 추론 차이를 이해한다
- 실전 유틸리티 타입을 분배 제어와 infer 로 직접 구현한다

## 1. 조건부 타입과 분배의 발동 조건

`T extends U ? X : Y` 는 T 가 네이키드 타입 파라미터이고 인자가 유니온일 때 각 멤버에 분배된다.

```typescript
type ToArray<T> = T extends any ? T[] : never;
type A = ToArray<string | number>;   // string[] | number[]
```

분배가 없으면 `(string|number)[]` 였을 것이다. never 는 빈 유니온이라 네이키드 파라미터에 들어오면 결과도 never 다.

```typescript
type Wrap<T> = T extends any ? T[] : never;
type N = Wrap<never>;   // never
```

## 2. 튜플 래핑으로 분배 끄기

`[T]` 로 감싸면 네이키드가 아니라 유니온 전체가 통째로 비교된다.

```typescript
type IsString<T> = T extends string ? true : false;
type D = IsString<string | number>;   // boolean
type IsStringStrict<T> = [T] extends [string] ? true : false;
type S = IsStringStrict<string | number>;  // false

type IsNever<T> = [T] extends [never] ? true : false;
type X = IsNever<never>;   // true
```

## 3. infer 기본

```typescript
type ElementType<T> = T extends (infer E)[] ? E : T;
type Awaited2<T> = T extends Promise<infer R> ? R : T;
type ReturnType2<F> = F extends (...a: any[]) => infer R ? R : never;
```

## 4. 공변 위치의 infer → union

같은 infer U 가 여러 공변 위치(프로퍼티 값)에 나오면 union 이 된다.

```typescript
type CovInfer<T> = T extends { a: infer U; b: infer U } ? U : never;
type C = CovInfer<{ a: string; b: number }>;   // string | number
```

## 5. 반공변 위치의 infer → intersection

함수 파라미터는 반공변이라 같은 infer U 가 여러 파라미터 위치에 나오면 intersection 이 된다. 이를 이용한 UnionToIntersection:

```typescript
type UnionToIntersection<U> =
  (U extends any ? (arg: U) => void : never) extends (arg: infer I) => void ? I : never;
type UI = UnionToIntersection<{ a: 1 } | { b: 2 }>;   // { a: 1 } & { b: 2 }
```

앞부분이 분배로 각 멤버를 함수로 감싸고, 뒤의 infer I 가 반공변 위치라 intersection 을 뽑는다.

## 6. 실전 유틸리티

```typescript
type Last<T extends any[]> = T extends [...any[], infer L] ? L : never;
type Params<F> = F extends (...a: infer P) => any ? P : never;
type Split<S extends string, D extends string> =
  S extends `${infer H}${D}${infer T}` ? [H, ...Split<T, D>] : [S];
type MyExclude<T, U> = T extends U ? never : T;   // 분배로 동작
```

## 6.5. 재귀 조건부 타입과 꼬리 재귀

TS 4.5+ 는 누산기를 인자로 넘기는 꼬리 재귀 조건부 타입을 최적화해 깊은 재귀를 허용한다.

```typescript
type Reverse<T extends any[], Acc extends any[] = []> =
  T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;
type Rev = Reverse<[1, 2, 3, 4]>;   // [4, 3, 2, 1]
```

## 7. 흔한 함정과 디버깅

| 증상 | 원인 | 해결 |
|---|---|---|
| never 입력에 never 결과 | 네이키드 분배 | [T] extends [U] 래핑 |
| union 통째 비교 원함 | 분배 켜짐 | 튜플 래핑 |
| infer 결과 예상외 union | 공변 다중 infer | 위치 축소 |
| infer 결과 never | 반공변 다중 infer | 파라미터 위치 주의 |
| 재귀 깊이 초과 | 비꼬리재귀 | 누산기 사용 |

TS 5.4+ 의 `NoInfer<T>` 로 원치 않는 위치 추론을 억제할 수 있다.

## 참고

- TypeScript Handbook — Conditional Types, Distributive Conditional Types, infer
- microsoft/TypeScript PR #21496
- type-challenges 저장소
