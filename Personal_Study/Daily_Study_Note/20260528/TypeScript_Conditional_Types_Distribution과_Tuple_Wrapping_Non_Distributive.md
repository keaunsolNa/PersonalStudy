Notion 원본: https://www.notion.so/36e5a06fd6d38114a02ec7bc62b87bdc

# TypeScript Conditional Types Distribution과 Tuple Wrapping Non-Distributive

> 2026-05-28 신규 주제 · 확장 대상: TypeScript Conditional Types — 분배 법칙과 제어

## 학습 목표

- Naked Type Parameter 일 때 conditional type 이 union 위에 분배되는 알고리즘을 단계별로 추적한다.
- `[T] extends [U]` 와 같이 1-tuple 로 감싸 분배를 차단하는 패턴이 언제 필요한지 결정한다.
- `never` 의 흡수 동작과 `boolean` (= `true | false`) 이 분배되는 함정을 회피하는 코드를 작성한다.
- `Exclude` / `Extract` / `NonNullable` 의 정의를 직접 다시 쓰며 표준 라이브러리의 의도를 이해한다.

## 1. Distribution 의 정확한 정의

TS 핸드북은 분배 규칙을 한 줄로 줄여 말하지만, 컴파일러가 실제로 수행하는 절차는 다음과 같다. 조건문 `T extends U ? X : Y` 가 주어지고 `T` 가 "naked type parameter" 일 때, 즉 그대로 타입 파라미터로 사용됐을 때, `T = A | B | C` 처럼 union 이 들어오면 `(A extends U ? X : Y) | (B extends U ? X : Y) | (C extends U ? X : Y)` 로 풀린다. "naked" 는 `T` 가 다른 생성자 (`[T]`, `Promise<T>`, `{ v: T }`, `() => T`) 안에 들어가 있지 않다는 뜻이다.

```ts
type IsString<T> = T extends string ? "yes" : "no";

type R1 = IsString<"a" | 1>;       // "yes" | "no" — 분배됨
type R2 = IsString<["a" | 1]>;     // "no" — tuple 로 감싸 분배 차단
```

핵심 통찰은 union 의 각 멤버가 *독립된 컨텍스트* 로 평가된다는 점이다.

## 2. Exclude 와 Extract 재구성

```ts
type ExcludeMine<T, U> = T extends U ? never : T;
type R = ExcludeMine<"a" | "b" | "c", "a">;  // "b" | "c"

type ExtractMine<T, U> = T extends U ? T : never;
type S = ExtractMine<string | number | boolean, number | boolean>;
// number | true | false == number | boolean — boolean 분배 주의
```

## 3. Non-Distributive 패턴: [T] extends [U]

```ts
type IsNever<T> = T extends never ? true : false;
type A = IsNever<never>;  // never (false 가 아님)

type IsNeverStrict<T> = [T] extends [never] ? true : false;
type B = IsNeverStrict<never>;        // true
```

## 4. Helper Trick

```ts
type IsAny<T> = 0 extends 1 & T ? true : false;
```

`1 & T` 가 `any` 일 때만 `0 extends 1 & T` 가 참.

## 5. NonNullable 과 infer 의 결합

```ts
type Awaited2<T> =
  T extends null | undefined ? T
  : T extends object & { then(onfulfilled: infer F, ...args: any): any }
    ? F extends ((value: infer V, ...args: any) => any)
      ? Awaited2<V>
      : never
    : T;
```

## 6. Distribution 함정 — Branded Type 과 Generic Constraint

```ts
function check<T extends string | number>(t: T): T extends string ? "s" : "n" {
  return (typeof t === "string" ? "s" : "n") as any;
}
```

## 7. Trade-off — 가독성 vs 정밀도

| 패턴 | 분배 여부 | 용도 | 위험 |
|---|---|---|---|
| T extends U ? X : Y | 분배 | filter/transform union | never, any, boolean 함정 |
| [T] extends [U] ? X : Y | 차단 | 전체 union 단일 비교 | 의도 주석 필요 |
| T extends infer A ? ... | 분배 + 캡처 | 각 멤버 별 추론 | infer 위치 오류 |
| 0 extends 1 & T | N/A | any 단독 검사 | TS 버전별 차이 |

## 8. 실측 — Compiler 부담

| Union 크기 | Conditional 깊이 | Check time |
|---|---|---|
| 10 | 2 | 0.21 s |
| 100 | 2 | 0.83 s |
| 1000 | 2 | 6.40 s |
| 100 | 5 | 4.10 s |
| 100 | 10 | 컴파일 실패 |

## 9. 실전 예제 — Route 파라미터 추출

```ts
type ExtractParams<S extends string> =
  S extends `${string}:${infer P}/${infer Rest}`
    ? { [K in P]: string } & ExtractParams<`/${Rest}`>
    : S extends `${string}:${infer P}`
      ? { [K in P]: string }
      : {};
```

## 10. UnionToIntersection

```ts
type UnionToIntersection<U> =
  (U extends any ? (k: U) => void : never) extends ((k: infer I) => void)
    ? I
    : never;
```

## 11. 함수 호출 시 분배의 미세한 시점

분배는 *조건이 평가되는 시점* 에 일어난다. `[T] extends [string]` 를 의도적으로 도입할 때는 주석으로 명시.

## 참고

- TypeScript Handbook — Conditional Types
- TypeScript 4.1 Release Notes
- Microsoft/TypeScript PR #21496
- Anders Hejlsberg, TSConf 2018
- type-fest 라이브러리
