Notion 원본: https://www.notion.so/3655a06fd6d381d6bd2def509d18601e

# TypeScript `infer` 키워드와 Variance Annotation — Conditional Type 추론 메커니즘

> 2026-05-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Conditional Type의 분배(distributive) 동작과 `infer` 슬롯이 검사 위치(check type)에서 어떻게 단방향 추론을 만드는지 파악한다
- 함수·튜플·문자열 리터럴 패턴에서 `infer`가 잡아내는 위치별 분산(variance) 차이를 식별한다
- TypeScript 4.7의 `in`·`out` annotation이 재귀 타입과 캐시 일관성에 미치는 영향을 이해한다
- 추론 한계(여러 후보가 union으로 합쳐지는 케이스, contravariant 위치의 intersection 변환)를 회피하는 코드 패턴을 작성한다

## 1. Conditional Type 평가 모델과 `infer`의 위치

Conditional type `T extends U ? X : Y`는 타입 체커가 `T`를 `U`에 대해 *할당 가능성(assignability)* 검사하는 시점에 평가된다. `infer` 키워드는 `U` 안의 임의 위치에 *타입 변수*를 도입해, 검사 도중 그 위치에 실제로 대응되는 타입을 캡처한다. 즉 `T extends F<infer X> ? X : never`는 "만약 `T`가 `F`의 어떤 구체적 인자 `X`로 만들어진 모양과 호환된다면, 그 `X`가 무엇인지 알려달라"는 뜻이다.

추론은 항상 *check type* 쪽이 아니라 *extends type* 쪽에서 일어난다는 점이 핵심이다. 다음 두 정의는 비슷해 보이지만 동작이 다르다.

```ts
type ReturnA<T> = T extends (...a: any[]) => infer R ? R : never;
type ReturnB<T> = T extends (...a: any[]) => any ? T : never; // R 추론 없음, T 자체를 반환
```

`ReturnA`는 함수의 반환부에 `infer R`를 두어 그 위치의 타입을 끌어낸다. `ReturnB`는 추론 슬롯이 없어 단순한 필터로 동작한다. `infer`는 일종의 단방향 패턴 매칭이며, 그 영향은 동일한 conditional 표현 안에서만 유효하다.

분배 동작도 함께 작동한다. `T`가 *naked* type parameter이고 union이면, 컴파일러는 union을 분해해 각 멤버에 대해 conditional을 평가한 뒤 결과를 union으로 합친다. 따라서 `ReturnA<(()=>string) | (()=>number)>`는 `string | number`다. 분배를 막아 union 자체로 한 번에 평가하고 싶다면 `[T] extends [...] ? ... : ...` 처럼 튜플로 감싼다.

## 2. 함수 시그니처에서의 `infer` — 인수·반환·this의 위치별 분산

함수 타입은 인수에서 *반공변(contravariant)*, 반환에서 *공변(covariant)*이라는 비대칭 구조를 갖는다. `infer`가 인수 위치에 들어가면 컴파일러는 여러 후보를 *intersection*으로, 반환 위치에서는 *union*으로 합친다. 표준 라이브러리 `Parameters`·`ReturnType`은 이 동작을 활용한다.

```ts
type Parameters<T extends (...a: any[]) => any> =
  T extends (...a: infer P) => any ? P : never;

type ReturnType<T extends (...a: any[]) => any> =
  T extends (...a: any[]) => infer R ? R : never;
```

오버로드된 함수에 `Parameters`를 적용하면 *마지막* 오버로드만 채택된다. 컴파일러는 오버로드 리스트를 순회하면서 가장 마지막 시그니처를 conditional의 `extends` 자리에 사용하기 때문이다. 이 동작은 RxJS·Express 미들웨어처럼 오버로드를 통한 변형 시그니처를 사용하는 라이브러리에서 자주 함정이 된다. 모든 오버로드를 가져오려면 `OverloadUnion` 트릭을 별도로 작성해야 한다.

`this` 매개변수도 동일한 규칙을 따른다. `T extends (this: infer S, ...a: any[]) => any ? S : never`로 `this` 컨텍스트 타입을 추출할 수 있다. 인수 위치의 `infer`가 여러 번 등장하면 후보들이 intersection되므로, 동일 슬롯에서 두 번 잡지 않도록 한다.

## 3. 튜플 패턴 매칭 — 헤드·테일·가변 길이

튜플과 가변 인수는 `infer`를 통해 헤드/테일/스프레드 위치에서 자유롭게 분해된다. Vue Router·tRPC·React Query 같은 라이브러리가 함수 인수를 부분 적용(partial application)할 때 핵심으로 쓴다.

```ts
type Head<T extends readonly any[]> = T extends readonly [infer H, ...any[]] ? H : never;
type Tail<T extends readonly any[]> = T extends readonly [any, ...infer R] ? R : [];
type Last<T extends readonly any[]> = T extends readonly [...any[], infer L] ? L : never;

type DropLast<T extends readonly any[]> =
  T extends readonly [...infer Rest, any] ? Rest : [];
```

스프레드 위치에 `infer`를 두면 *가변 길이*가 그대로 보존된다. 추론 비용은 튜플 길이와 nested conditional 깊이의 곱에 비례한다. 길이 30 이상의 튜플에서 머리·꼬리를 반복 분해하면 `Type instantiation is excessively deep and possibly infinite` 에러가 나기 쉬우므로, *tail-recursive* 형태(누적자 파라미터)로 재작성한다.

```ts
type ReverseTail<T extends readonly any[], Acc extends readonly any[] = []> =
  T extends [infer H, ...infer R] ? ReverseTail<R, [H, ...Acc]> : Acc;
```

TS 4.5에서 도입된 tail-call optimization은 conditional의 *최외곽 재귀 호출*만 인식하므로, `[...X<...>, ...]` 같은 형태로 감싸면 최적화가 꺼지고 깊이 제한 1000에 빠르게 부딪힌다.

## 4. 문자열 리터럴 패턴 — Template Literal과 `infer`

`infer`는 template literal 안에 들어가 부분 문자열을 캡처한다. URL 파서·이벤트 이름 디스패처·snake_case 변환기의 핵심 도구다.

```ts
type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}` ? [Head, ...Split<Tail, D>] : [S];
```

리터럴 패턴에서는 *greedy*가 아니라 *leftmost* 매칭이 우선이다. 패턴 안에서 같은 변수를 두 번 쓰면 *back-reference*가 아니라 *intersection*이 발생한다. 정규표현식의 \1 같은 backref는 TS에서 표현할 수 없다.

특수 케이스로 `infer X extends Y` 문법(4.7+)을 쓰면 잡힌 값에 제약을 거는 동시에 *narrowed* 타입으로 받을 수 있다. 숫자 리터럴 파싱에 자주 쓰인다.

```ts
type ParseInt<S extends string> =
  S extends `${infer N extends number}` ? N : never;
type X = ParseInt<"42">; // 42
```

## 5. 변수 결합 — 동일 슬롯 다중 캐처와 union 변환

같은 conditional 안에서 `infer X`가 여러 번 나타날 때 슬롯의 *분산(variance)*이 결합 방식을 결정한다.

| 위치 | 슬롯 분산 | 결합 |
|---|---|---|
| 객체 프로퍼티 값 | covariant | union |
| 함수 반환 | covariant | union |
| 함수 인수 | contravariant | intersection |
| 튜플 원소 | covariant | union |

```ts
type Co<T> = T extends { a: infer X; b: infer X } ? X : never;
type T1 = Co<{ a: string; b: number }>; // string | number
```

이 결합 규칙은 union을 intersection으로 변환하는 잘 알려진 트릭의 토대다.

```ts
type UnionToIntersection<U> =
  (U extends any ? (x: U) => void : never) extends ((x: infer I) => void) ? I : never;
```

분배 conditional이 union을 펼친 뒤 각 멤버를 함수의 인수 위치로 옮겨놓고, 마지막에 같은 `I` 슬롯으로 다시 모으면 contravariant intersection이 일어난다.

## 6. Variance Annotation `in`·`out` — 4.7 이후의 명시적 제어

추론 엔진은 generic의 분산을 자동 결정하지만, 재귀 타입이나 phantom type처럼 *구조적*으로 보이는 사용처가 없는 경우 컴파일러는 invariant로 보수적으로 처리한다. 4.7의 `in`·`out` annotation이 이 문제를 푼다.

```ts
interface State<out T> { read(): T; }            // covariant
interface Setter<in T> { write(v: T): void; }    // contravariant
interface Channel<in out T> { read(): T; write(v: T): void; } // invariant
```

명시 annotation의 효과는 두 가지다. 첫째, 호환성 판정 시 *구조 확인*을 건너뛰고 선언된 분산에 따라 비교한다. 둘째, 캐시 키가 정확해져 동일 시그니처가 반복 등장해도 재추론 비용이 들지 않는다. 큰 모노레포에서 `Promise`·`Observable`처럼 빈번히 등장하는 제네릭 컨테이너에 annotation을 달면 `tsc --extendedDiagnostics`의 *instantiations* 카운트가 10~30% 줄어드는 경우가 있다.

annotation은 *선언*이지 *증명*이 아니다. 실제 사용이 선언과 모순되면 컴파일러가 에러를 낸다.

## 7. 추론 한계와 회피 패턴

첫째, *higher-kinded type*(HKT)은 TS에 없다. fp-ts·Effect-TS는 *URI to Kind* 패턴으로 시뮬레이션한다. 둘째, *과한 분배*가 의도와 다르게 결과를 union으로 펼친다. 사용자 입력 타입을 그대로 보전하고 싶다면 `[T] extends [U] ? ... : ...`로 단일 슬롯에 가둔다. 셋째, *오버로드된 함수*의 모든 시그니처를 잡아내려면 마지막에서 빼낸 시그니처를 제외시키고 다시 패턴 매칭하는 재귀를 직접 작성해야 한다. 표준 라이브러리는 마지막 오버로드만 지원한다. 넷째, `infer X extends Y` 문법으로 좁힌 결과는 *후속 평가*에서 다시 일반화되지 않는다. 다섯째, 재귀 깊이 1000 제한은 단순한 안전망이다. 1000을 넘는 길이의 튜플·문자열을 처리하려면 chunking으로 분할 정복하거나 *iteration* 헬퍼를 도입한다.

## 8. 성능 디버깅 — `tsc --extendedDiagnostics`와 추론 단가

복잡한 `infer` 패턴은 컴파일 시간을 폭증시킨다. `tsc --extendedDiagnostics`는 instantiation 횟수와 캐시 미스율을 보여준다. Check time이 길거나 Instantiations이 200만을 넘으면 *동일한 conditional이 반복 평가*되고 있을 가능성이 크다. 자주 쓰는 mapped/conditional 결과를 `type` alias로 캐시하지 말고, 상수 입력으로 *materialize*한다(`type X = Compute<...>` 패턴). 재귀를 tail position에 두어 메모이즈가 작동하게 만든다. variance annotation을 추가해 캐시 키를 좁힌다. 깊은 union·intersection은 `Simplify<T> = { [K in keyof T]: T[K] }` 트릭으로 평탄화해 후속 비교 비용을 떨어뜨린다.

`tsc --generateTrace ./trace` 옵션은 Chromium Tracing 형식의 프로파일을 만든다. perfetto.dev에 올려 *checkSourceFile* / *resolveAlias* / *getResolvedSignature*가 어느 파일에서 폭발하는지 시각적으로 확인할 수 있다.

## 9. 실전 패턴 모음 — 라우트·쿼리·매퍼

URL 패스 파라미터 추출 — Express·Hono·tRPC가 동일한 패턴을 사용한다.

```ts
type Params<S extends string> =
  S extends `${string}:${infer P}/${infer Rest}` ? { [K in P | keyof Params<`/${Rest}`>]: string }
  : S extends `${string}:${infer P}` ? { [K in P]: string }
  : {};
```

Promise 체이닝 — `await` 결과의 깊이를 평탄화한다. DB row → DTO 매퍼 — snake_case 키를 camelCase로 변환한다.

세 패턴 모두 *checked type*과 *extends type*이 비대칭이고, `infer`가 그 격차를 한 줄로 메운다는 공통점이 있다. 이 비대칭을 잘 활용하면 라이브러리 사용자의 코드 자체가 *타입 수준 DSL*이 된다.

## 참고

- TypeScript Handbook — Conditional Types <https://www.typescriptlang.org/docs/handbook/2/conditional-types.html>
- TS 4.7 Release Notes — Variance Annotations
- TS 4.5 Release Notes — Tail-Recursive Conditional Types
- TS 4.7 Release Notes — `extends` Constraint on `infer`
- Anders Hejlsberg, "TypeScript's type system" (TSConf 2019)
