Notion 원본: https://www.notion.so/36d5a06fd6d381b99772e2b5908b473f

# TypeScript Type Inference Mechanism: Contextual Typing과 Bidirectional Inference, Generic Constraint Solving

> 2026-05-27 신규 주제 · 확장 대상: TypeScript 컴파일러 / 추론 엔진

## 학습 목표

- TypeScript 추론 엔진이 사용하는 contextual typing, return type inference, generic constraint solving 3축을 구분한다
- 콜백 함수에서 매개변수 타입이 어떻게 *밖→안* 으로 흐르는지(contextual typing) 단계별로 추적한다
- generic 의 inference site, constraint, default 가 어떤 순서로 평가되는지 컴파일러 알고리즘 관점에서 설명한다
- `NoInfer<T>`, `const` type parameter, contextual flow 차단 패턴으로 추론 실패를 디버깅한다

## 1. 추론의 두 방향: Outside-in vs Inside-out

TypeScript 추론은 *expression 의 위치* 가 알려주는 정보(outside-in, contextual typing)와 *expression 의 값* 이 알려주는 정보(inside-out, return/literal inference)를 동시에 본다. 이 둘을 합쳐 *bidirectional inference* 라 한다.

```ts
// inside-out: 우변 리터럴로부터 좌변 타입을 추론
const xs = [1, 2, 3]; // number[]

// outside-in: 좌변 타입이 우변의 빈 객체에 흘러간다
const point: { x: number; y: number } = { x: 1, y: 2 };

// 양방향이 동시에: 콜백 매개변수의 타입은 위치(외부)가, 반환 타입은 본문(내부)이 결정
const doubled = [1, 2, 3].map((n) => n * 2); // n: number, 반환 number[]
```

## 2. Contextual Typing 의 실제 흐름

| 자리 | 예 | 흐르는 방향 |
|---|---|---|
| 변수 선언 RHS | `const x: T = expr` | T → expr |
| 함수 인자 | `fn(expr)` | param type → expr |
| 반환문 | `function f(): T { return expr }` | T → expr |
| 할당 LHS | `obj.field = expr` | field type → expr |
| 객체 리터럴 프로퍼티 | `({ a: expr } as T)` | T['a'] → expr |
| as / satisfies | `expr satisfies T` | T → expr |
| JSX prop | `<Comp prop={expr}/>` | Comp 의 prop type → expr |

## 3. Generic Constraint Solving 알고리즘 (개요)

1. *Inference site 수집*: 인자 type 안에서 `T` 가 등장하는 모든 위치를 찾는다
2. *Candidate 수집*: 각 사이트에 대해 실제 인자 type 으로부터 `T` candidate 를 추출한다
3. *Common supertype 계산*: candidate 들의 공통 상위 type 을 구한다
4. *Constraint 적용*: `T extends X` 가 있으면 결과가 `X` 의 subtype 인지 검사
5. *Default 적용*: 추론 실패한 `T` 에 대해 `T = D` default 를 채운다

```ts
function pair<T, U = T>(a: T, b: U): [T, U] {
	return [a, b];
}
pair(1, 'x'); // T=1, U='x' → [number, string]
pair(1, 1);    // T=1, U=1 → [number, number]
pair<number>(1); // type error — b 필수
```

## 4. Return Type Inference 의 widening 과 const

```ts
const a = 'hello';     // 'hello' (literal type)
let b = 'hello';       // string (widened)

function tag<T extends string>(t: T): { tag: T } { return { tag: t }; }
const x = tag('ok'); // { tag: 'ok' } — generic 으로 literal 보존
```

TS 5.0 의 `const` type parameter 는 generic 호출 시점에 인자를 `as const` 로 추론하라는 hint 다.

## 5. NoInfer<T> 와 추론 차단 (TS 5.4+)

```ts
// 해결: NoInfer 로 defaultValue 위치를 추론에서 제외
function createField<K extends string>(name: K, defaultValue: NoInfer<K>): { name: K; defaultValue: K } {
	return { name, defaultValue };
}
const f = createField('username', 'guest'); // K = 'username', 'guest' 은 에러
```

`NoInfer<T>` 는 *intrinsic type* 으로 컴파일러가 inference site 수집 단계에서 해당 위치를 건너뛰게 한다. 라이브러리 저자가 *왜 이 type 이 이렇게 좁혀졌지?* 를 통제할 때 핵심 도구.

## 6. Contextual Type 이 함수 본문 안에 흐르는 깊이

| expression | contextual flow 통과 |
|---|---|
| Parenthesized `(expr)` | O |
| Conditional `cond ? a : b` | O (양쪽 가지 모두) |
| Comma `(a, b)` | O (마지막 항) |
| Assignment `(x = expr)` | O (RHS) |
| Non-null `expr!` | O |
| `as T` cast | X (T 가 새 context) |
| `satisfies T` | X (T 로 변경) |
| Function call result | X (반환 type) |

## 7. Inference Priority 와 candidate 정렬

1. 명시적 type 인자(`f<T>(...)`)
2. context 로부터 흐러온 type
3. 인자 type 의 candidate
4. 상한(constraint)
5. default

## 8. 실전 디버깅 순서

1. *hover 로 실제 추론된 type 확인*: VS Code Quick Info(Ctrl+K I)로 `T` 가 무엇으로 묶였는지 본다.
2. *strict 옵션 확인*: strictFunctionTypes / strictNullChecks
3. *implicit any 여부*: `noImplicitAny: true` 가 아니면 contextual flow 이 깨진 자리에서 `any` 가 squelch 한다
4. *Generic 의 inference site 개수*: 양쪽에 등장하면 *둘 다* 후보. 하나만 쓰고 싶으면 `NoInfer<T>` 사용
5. *as const 누락*: literal 보존 필요한 자리에 widening 이 일어났는지
6. *컴파일러 trace 활성화*: `tsc --traceResolution`, `--listFiles --explainFiles`

## 9. Performance: 컴파일 비용 관점

| 도구/옵션 | 효과 |
|---|---|
| `tsc --extendedDiagnostics` | type instantiation count, check time 출력 |
| `tsc --generateTrace ./trace` + Chrome `chrome://tracing` | hot spot 시각화 |
| `--incremental` + `tsbuildinfo` | 변경 없는 파일 재추론 건너뜀 |
| `Project References` | 큰 monorepo 의 instantiation 격리 |
| 분배 차단 `[T] extends [U]` | distributive conditional 폭발 방지 |
| `interface` 선호 | type alias 합산보다 인덱스 캐시가 잘 됨 |

실측 예: distributive `T extends string ? ... : never` 가 `T = 'a' | 'b' | ... 50 union` 일 때 instantiation 5000회 → `[T] extends [string]` 으로 바꾸면 100회로 떨어짐.

## 10. Variance 와 Inference 의 결합

TS 4.7+ 는 `in`/`out` variance annotation 으로 generic 의 variance 를 명시할 수 있다. 공변은 union, 반공변은 intersection, 불변은 exact match.

```ts
interface Producer<out T> { get(): T; }
interface Consumer<in T> { set(v: T): void; }
interface Pipe<in out T> { get(): T; set(v: T): void; }
```

## 11. 실전 사례: Zod, TanStack Query, tRPC

Zod 는 schema 자체가 generic 으로 parse 결과 type 을 들고 다닌다. TanStack Query v5 는 `const T extends readonly unknown[]` 로 queryKey 를 자동 literal tuple 추론. tRPC v11 은 procedure chain 의 builder pattern 으로 generic 누적, 큰 monorepo 에서는 project references 분리가 필수.

## 12. Higher-Order Function 추론의 함정

```ts
declare function pipe<A, B>(ab: (a: A) => B): (a: A) => B;
declare function pipe<A, B, C>(ab: (a: A) => B, bc: (b: B) => C): (a: A) => C;

const result = pipe(
	(x: number) => x.toString(),
	(s) => s.length,
);
```

TS 4.7 부터는 *higher-order function inference* 가 강화되어 chain 의 중간 함수에 explicit type 을 거의 붙이지 않아도 된다. 단, 첫 함수의 인자 type 만은 명시해야 *seed* 가 된다. `compose` 의 우→좌 합성은 TS 에서 추론이 약해 fp-ts, Effect 등은 `pipe(value, ...fns)` 형태를 선호한다.

## 13. 라이브러리 저자 체크리스트

| 항목 | 점검 포인트 |
|---|---|
| 콜백 시그니처 | callback param type 을 외부 generic 으로 노출 (any 회피) |
| literal 보존 | `const T extends string` 또는 `as const` 강제 안내 |
| 추론 차단 | default value, fallback 자리에 `NoInfer<T>` 적용 |
| 분배 폭발 방지 | conditional 안에서 `[T] extends [U]` 사용 |
| variance 명시 | `in`/`out` 으로 합산 방식 결정 |
| project references | 큰 router/builder 는 분리해 instantiation 격리 |

## 참고

- TypeScript Compiler Internals (https://github.com/microsoft/TypeScript/wiki/Architectural-Overview)
- TS 5.4 release notes — NoInfer<T> intrinsic
- TS 5.0 release notes — const type parameters
- "Type Inference for TypeScript" — Pacheco et al., 2021
- TS performance wiki (https://github.com/microsoft/TypeScript/wiki/Performance)
