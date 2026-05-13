Notion 원본: https://www.notion.so/35f5a06fd6d381bdb743f21f591c55ca

# TypeScript Recursive Conditional Types와 Tail-Call Optimization — 1000 레벨 재귀 우회 전략

> 2026-05-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript 4.5 의 *tail-recursion elimination* 이 어떤 조건에서 동작하는지 instantiation 트리로 파악
- `Type instantiation is excessively deep and possibly infinite` 에러의 진짜 원인을 디버깅
- 100~1000 단계 재귀를 *분할 정복(divide & conquer)* 또는 *iteration unrolling* 으로 우회
- 실 라이브러리(react-hook-form, drizzle, zod) 에서 깊은 타입을 어떻게 잘라 쓰는지 패턴 분석

## 1. 재귀 한계의 정체

TypeScript 컴파일러는 type instantiation 마다 *instantiation depth counter* 를 1 씩 증가시킨다. 기본 한계는 *50 (depth) + 1000 (instantiations)* 으로, 둘 중 하나만 넘어도 다음 에러가 난다.

```
Type instantiation is excessively deep and possibly infinite. (ts2589)
```

이 한계 자체는 늘릴 수 없다. tsc 옵션도 없고, 컴파일러 빌드를 갈아치우는 수밖에 없다. 따라서 *재귀 횟수를 줄이거나, tail-call elimination 으로 평탄화* 하는 두 가지 길만 남는다.

## 2. Tail-Call 인식 조건

TS 4.5 부터 *tail position 의 재귀*는 stack 을 쌓지 않고 평탄화된다. 평탄화의 조건은 엄격하다.

* 마지막 표현식이 *자기 자신의 재귀 호출 하나* 만이어야 한다.
* 결과를 *추가 가공* 하면 안 된다 (`[Self<...>][0]` 같은 wrapping 금지).
* 조건부 분기의 *결과 위치* 에 있어야 한다.

```ts
// 평탄화 OK — A 의 결과가 곧바로 반환됨
type ReverseTuple<T extends unknown[], Acc extends unknown[] = []> =
    T extends [infer Head, ...infer Rest]
        ? ReverseTuple<Rest, [Head, ...Acc]>
        : Acc;

// 평탄화 NO — `[...Reverse<Rest>, Head]` 는 wrapping
type ReverseBad<T extends unknown[]> =
    T extends [infer Head, ...infer Rest]
        ? [...ReverseBad<Rest>, Head]
        : [];
```

`ReverseBad` 는 50개 길이 튜플만 넣어도 `ts2589` 가 발생한다. 반면 `ReverseTuple` 은 *accumulator pattern* 으로 tail position 을 확보해 1000개 길이 튜플도 무난히 처리한다. 이는 함수형 언어의 fold 와 동일한 패턴이고 type-level 에서도 그대로 적용된다.

## 3. 분할 정복으로 깊이 절반 만들기

선형 재귀가 한계라면 *반으로 쪼개기* 로 log N 깊이로 만든다.

```ts
type Repeat<T, N extends number, Acc extends T[] = []> =
    Acc['length'] extends N
        ? Acc
        : N extends Add<Acc['length'], 1>
            ? [...Acc, T]
            : Repeat<T, N, [...Acc, T]>;
```

위는 단순 누적이라 N=600 부근에서 한계에 부딪힌다. 다음과 같은 *halving* 으로 바꾸면 약 N=30000 까지 동작한다.

```ts
type RepeatFast<T, N extends number, Acc extends T[] = []> =
    Acc['length'] extends N
        ? Acc
        : [...Acc, ...Acc]['length'] extends infer Doubled extends number
            ? Doubled extends N
                ? [...Acc, ...Acc]
                : Doubled extends Gt<N> // 가상의 비교
                    ? RepeatFast<T, N, Acc>
                    : RepeatFast<T, N, [...Acc, ...Acc]>
            : never;
```

매 단계마다 `Acc` 의 길이가 두 배로 늘어나니 log2(N) 단계면 충분하다. 실전에서는 `ts-toolbelt` 의 `L.Concat` 이 비슷한 전략을 쓰고, 2배 도약 후 *나머지 채우기* 로 미세 조정을 한다.

## 4. Iteration unrolling — 100 step batching

또 다른 패턴은 *한 step 에 N 번씩 풀어 쓰기*다. 예를 들어 split 을 한 글자씩 하는 대신 4글자씩 처리하면 재귀 깊이가 4분의 1로 줄어든다.

```ts
type SplitFour<S extends string, Acc extends string[] = []> =
    S extends `${infer A}${infer B}${infer C}${infer D}${infer Rest}`
        ? SplitFour<Rest, [...Acc, A, B, C, D]>
        : S extends `${infer A}${infer B}${infer C}${infer Rest}`
            ? SplitFour<Rest, [...Acc, A, B, C]>
            : S extends `${infer A}${infer B}${infer Rest}`
                ? SplitFour<Rest, [...Acc, A, B]>
                : S extends `${infer A}${infer Rest}`
                    ? SplitFour<Rest, [...Acc, A]>
                    : Acc;
```

`SplitOne` 한 글자 버전이 500자 문자열에서 한계에 도달한다면 `SplitFour` 는 2000자까지 살아남는다. 가독성은 떨어지지만 라이브러리 내부에서 자주 쓰는 트릭이다.

## 5. 실제 라이브러리 예제 — drizzle 의 컬럼 추론

drizzle ORM 의 `select` 결과 타입은 컬럼 갯수와 join depth 에 따라 instantiation 이 폭증한다. 내부적으로는 다음과 같은 패턴을 쓴다.

```ts
// 의사 코드 — drizzle 내부
type InferSelectResult<T extends ColumnList, Acc = {}> =
    T extends [infer Head extends Column, ...infer Rest extends ColumnList]
        ? InferSelectResult<Rest, Acc & { [K in Head['name']]: InferColumnType<Head> }>
        : Acc;
```

여기서 *Acc 가 intersection 으로 누적* 되므로 IDE hover 가 무거워진다. drizzle 0.30 부터는 이를 `Prettify<T>` 헬퍼로 mapped type 화해 최종 결과를 *평탄한 객체* 로 normalise 한다. 이 변환은 instantiation 1단계만 더 잡아먹지만 IDE 응답이 체감 200~400 ms 빨라진다.

```ts
type Prettify<T> = { [K in keyof T]: T[K] } & {};
```

마지막 `& {}` 는 *intersection을 mapped type 으로 강제 evaluation* 시키는 마법의 한 줄이다.

## 6. 깊이 측정 — diagnostics 출력 읽기

`tsc --extendedDiagnostics` 를 켜면 instantiation count, type 비교 count 가 출력된다.

```
Files:                          412
Lines of Library:              42218
Lines of Definitions:         193057
Lines of TypeScript:           18324
Types:                         28733
Instantiations:               1284392
Memory used:                    548MB
Total time:                  9.43s
```

`Instantiations` 가 100만을 넘어가면 *어딘가 재귀가 과도하게 동작* 한다는 신호다. 다음으로 `tsc --generateTrace ./trace --incremental false` 로 trace 디렉토리를 만들고 `tsserver` perfetto 뷰어로 열면 *어느 type alias* 가 가장 시간을 잡아먹는지 시각화된다. zod 의 `ZodObject.extend` 한 줄이 300 ms 잡아먹는 케이스가 자주 보인다.

## 7. 깊은 타입 방지 패턴 — Brand + Phantom

깊이 폭증의 근본 원인은 *모든 정보를 타입에 욱여넣으려는 욕망*이다. 다음 패턴은 정보를 줄이면서도 *런타임 안전성*은 유지한다.

```ts
declare const __brand: unique symbol;
type Brand<T, B> = T & { readonly [__brand]: B };

type UserId = Brand<string, 'UserId'>;
type OrderId = Brand<string, 'OrderId'>;

function loadUser(id: UserId) { /* ... */ }
loadUser('abc' as UserId);
// loadUser('abc' as OrderId); // ❌ 컴파일 에러
```

Brand 는 *깊이 1짜리 phantom 정보* 만 추가하고 끝난다. 컴파일러 부담이 무시할 만하고, IDE hover 도 `UserId` 한 줄로 끝난다. 대규모 코드베이스의 *95% 케이스* 는 type-level 산술이 아니라 Brand 만으로 표현이 가능하다.

## 8. 권장 임계치 표

| 시나리오 | 안전 깊이 | 위험 깊이 | 한계 |
| --- | --- | --- | --- |
| 단순 tail recursion (Reverse, Length) | ~1000 | 1500 | 2000 (ts2589) |
| 비-tail recursion (Concat, Flatten) | ~50 | 100 | 200 |
| 분할 정복 (halving) | ~30000 | 50000 | 100000 |
| Template literal 한 글자씩 split | ~500 | 800 | 1000 |
| Template literal 4글자 unroll | ~2000 | 3000 | 4000 |
| Intersection 누적 (Acc & { ... }) | ~80 키 | 150 키 | 300 키 |


## 9. 실전 디버깅 — 어디서 깊이가 폭증하는가

```
$ tsc --extendedDiagnostics --noEmit
Files:                          412
Lines:                       253599
Types:                        28733
Instantiations:             1284392
Symbols:                     142113
Memory used:                    548MB
Total time:                  9.43s
```

`Instantiations` 가 정상치(50만 부근) 의 두 배 이상이면 *어떤 type alias 가 폭주* 한다는 신호다. trace 디렉토리를 생성해보면 *한 type alias 에 수십만 step 이 몰린* 경우가 보인다.

다음 단계로 *trace 파일 분석*. `--generateTrace ./trace` 옵션으로 perfetto 호환 JSON 이 떨어진다.

```bash
$ tsc --generateTrace ./trace --noEmit
$ ls trace/
trace.json  types.json
```

Chrome `chrome://tracing` 또는 `https://ui.perfetto.dev` 에서 trace.json 을 열면 *type alias 별 소요 시간 flame graph* 가 보인다. 90% 이상의 시간이 한두 alias 에 집중되어 있는 게 일반적이다 — 그곳이 최적화 대상.

자주 발견되는 패턴:
* zod `ZodObject.merge` 가 깊이 누적
* drizzle `select().from(...)` 의 결과 추론
* tRPC `router.merge` 또는 deep input/output schema
* immer 의 readonly transform

## 10. 권장 워크플로우

1. *측정 baseline* — CI 에서 `tsc --noEmit --diagnostics` 결과를 매 PR 기록
2. *임계치 가드* — Instantiations 가 baseline 의 1.5배 넘으면 PR 자동 코멘트
3. *깊이 한정자 활용* — 라이브러리 측에서 `MaxDepth` 제너릭 파라미터를 받아 사용자가 절단 가능하게
4. *Prettify 패턴* — 결과 타입은 `{ [K in keyof T]: T[K] } & {}` 로 평탄화해 IDE hover 개선
5. *Brand / Phantom* — 정보 양을 줄이고 정밀도는 유지

마지막으로, 라이브러리 사용자가 받는 *IDE 응답 지연* 은 1초 임계점을 넘어가면 *productivity 누락* 으로 직결된다. tsserver 의 `getCompletionsAtPosition` 응답이 800ms 를 넘기 시작하면 type 평탄화 작업을 우선 과제로 둬야 한다.

## 11. 실제 사례 — react-hook-form 의 Path 타입

react-hook-form 의 `FieldPath<TFieldValues>` 는 객체의 모든 가능한 dot-path 를 union 으로 만든다.

```ts
// 의사 코드
type Path<T> = T extends object
    ? { [K in keyof T]: K extends string
        ? T[K] extends object
            ? K | `${K}.${Path<T[K]>}`
            : K
        : never
      }[keyof T]
    : never;
```

이 타입은 *깊이 N 의 객체에 대해 O(N!) instantiation* 을 유발한다. 7단 중첩 form 에서 instantiation 100만을 넘기는 사례가 보고됐다. 라이브러리는 *MaxDepth 5* 제한을 두어 사용자가 5단까지만 path 자동완성을 받게 절단한다.

```ts
type Path<T, Depth extends number = 5> = Depth extends 0 ? never : /* ... */;
```

깊이 한정자 패턴은 *infinite generic* 을 방지하는 가장 단순하면서 효과적인 방어선이다.

## 참고

- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- Microsoft / TypeScript Performance.md (https://github.com/microsoft/TypeScript/wiki/Performance)
- gvergnaud / hotscript — Higher-Kinded Type 으로 instantiation 줄이기
- drizzle-orm — Prettify util 소스 (https://github.com/drizzle-team/drizzle-orm)
- Anders Hejlsberg — TypeScript Compiler Internals talk
- TypeScript Trace Viewer 가이드 (https://github.com/microsoft/TypeScript/wiki/Performance#performance-tracing)
- effect-ts — IDE 친화 타입 설계 패턴
- react-hook-form Path 타입 소스
