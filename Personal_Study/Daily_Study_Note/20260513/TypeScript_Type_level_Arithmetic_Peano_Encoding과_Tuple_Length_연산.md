Notion 원본: https://www.notion.so/35f5a06fd6d381e6baf0ffedd392e98e

# TypeScript Type-level Arithmetic — Peano Encoding과 Tuple Length로 컴파일 타임 산술 구현

> 2026-05-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript 타입 시스템이 *튜링 완전(Turing-complete)* 임을 활용해 컴파일 타임에 정수 산술을 표현
- Peano 인코딩(0과 후행 함수 S) 을 tuple 길이로 흉내내고 덧셈/뺄셈/곱셈/비교 연산자를 작성
- 재귀 깊이 한계(약 1000)와 instantiation budget 을 우회하기 위한 tail-recursion 패턴 적용
- 실전 라이브러리(ts-toolbelt, type-fest, hotscript)의 Math 모듈 내부 구현 해석

## 1. 왜 타입에 숫자를 넣는가

런타임의 `number` 는 타입 시스템에서 `number` 한 종류로만 보인다. `number literal` 까지는 표현이 되지만 `1 + 2 = 3` 같은 연산은 기본 제공되지 않는다. 그런데 다음과 같은 시나리오에서는 *값이 아니라 타입 자체로 산술* 이 필요해진다.

* 고정 길이 tuple 의 길이 검증: `Vector<3>` 끼리만 dot product 허용
* DB 컬럼 길이 제약: `VarChar<255>` 보다 긴 문자열은 컴파일 에러
* HTTP route param 파싱에서 `/items/:id` 와 같은 동적 분리 후 자릿수 제한
* SQL builder 의 placeholder index 자동 증가: `$1, $2, $3 ...`

`number` 리터럴끼리는 `extends` 비교 외엔 거의 다룰 수 없으므로, 실제 산술은 *tuple 의 길이*로 환원해서 구현한다. 이는 자연수의 Peano 정의(0, S(0), S(S(0))) 와 일대일 대응이며 TypeScript 컴파일러의 tuple length inference 가 정확히 그 후행 연산자 역할을 한다.

## 2. Peano 인코딩의 핵심 — 길이 보존 튜플

```ts
// Tuple<5> => [unknown, unknown, unknown, unknown, unknown]
type Tuple<N extends number, T extends unknown[] = []> =
    T['length'] extends N ? T : Tuple<N, [...T, unknown]>;

type T5 = Tuple<5>; // [unknown, unknown, unknown, unknown, unknown]
type Len = T5['length']; // 5
```

핵심은 두 가지다.

1. *조건부 타입의 재귀*. `T['length'] extends N` 가 false 면 `[...T, unknown]` 를 누적해서 자기 자신을 재호출한다. TypeScript 4.1 부터 *recursive conditional types* 를 공식 지원했고, 5.x 에서는 tail recursion 시 instantiation depth 가 평탄화된다.
2. *튜플 length 추론*. `['a', 'b']['length']` 는 `number` 가 아니라 *literal `2`* 로 좁혀진다. 이 좁힘이 보장되어야 비교가 성립한다.

## 3. 덧셈 — `[...A, ...B]` 의 길이

```ts
type Add<A extends number, B extends number> =
    [...Tuple<A>, ...Tuple<B>]['length'] extends number
        ? [...Tuple<A>, ...Tuple<B>]['length']
        : never;

type _2plus3 = Add<2, 3>; // 5
type _0plus7 = Add<0, 7>; // 7
type _50plus50 = Add<50, 50>; // 100
```

`[...Tuple<A>, ...Tuple<B>]` 는 두 길이의 합 만큼의 unknown 튜플이 되고, 그 길이 인덱스는 자동으로 `A + B` 의 정수 리터럴로 반환된다. *주의*: extends number 조건은 형식상 필요하다 — `length` 가 generic 컨텍스트에서 `number` 로 widening 될 수 있기 때문이다.

## 4. 뺄셈 — 흡수 패턴

뺄셈은 더 까다롭다. `Sub<A, B>` 는 `A - B` 만큼의 튜플 길이를 얻어야 하는데, 음수 결과를 표현할 자연수가 없으므로 *Tuple<A> 가 Tuple<B> 의 superset인 경우*만 정의한다.

```ts
type Sub<A extends number, B extends number> =
    Tuple<A> extends [...Tuple<B>, ...infer Rest]
        ? Rest['length']
        : never;

type _7minus3 = Sub<7, 3>;  // 4
type _3minus3 = Sub<3, 3>;  // 0
type _3minus7 = Sub<3, 7>;  // never  (음수 표현 불가)
```

`Tuple<A> extends [...Tuple<B>, ...infer Rest]` 는 *A 의 앞쪽 B 개를 떼어내고 나머지를 Rest 로 추론* 한다. 이 패턴은 type-level pattern matching 의 정수 버전이고, 음수 결과 분기를 `never` 로 처리하는 것이 관례다. 음수까지 표현하려면 Sign + Magnitude 인코딩(`{ sign: '-', mag: 3 }`)이 필요한데 가독성이 매우 떨어지므로 실전에선 *positive integer arithmetic* 으로 제한한다.

## 5. 곱셈 — 누적 덧셈

곱셈은 *덧셈을 B 번 반복*하는 fold 로 구현한다.

```ts
type Mul<A extends number, B extends number, Acc extends number = 0> =
    B extends 0
        ? Acc
        : Mul<A, Sub<B, 1>, Add<Acc, A>>;

type _4times3 = Mul<4, 3>; // 12
type _0times9 = Mul<0, 9>; // 0
```

여기서 *재귀 깊이* 가 문제가 된다. `Mul<10, 100>` 은 B 가 100번 줄어드는 동안 100 단계 재귀가 들어가고 매 단계마다 `Add` 가 또 1단계를 잡아먹는다. TypeScript 의 instantiation depth 한계는 약 500~1000 인데, 4.5 부터 도입된 *tail-recursive elimination* 이 적용되려면 마지막 표현식이 *자기 자신의 호출 한 개* 여야 한다. 위 코드는 Mul 의 마지막 결과가 곧 Mul 의 결과이므로 tail-call 로 평탄화되어 잘 동작한다.

반대로 다음 형태는 평탄화되지 않는다:

```ts
// 안티 패턴: tail-call 아님
type MulBad<A, B, Acc = 0> =
    B extends 0 ? Acc : [Mul<A, Sub<B,1>, Add<Acc, A>>][0];
```

`[...]` 로 감싸는 순간 마지막 표현식이 *튜플 인덱스 접근*이 되어 컴파일러가 tail position 으로 인식하지 못한다.

## 6. 비교 — 사전식 길이 매칭

```ts
type Eq<A extends number, B extends number> =
    A extends B ? (B extends A ? true : false) : false;

type Lt<A extends number, B extends number> =
    Tuple<A> extends [...Tuple<B>, ...unknown[]] ? false : true;

type Gt<A extends number, B extends number> = Lt<B, A>;

type _3lt5 = Lt<3, 5>;  // true
type _3gt5 = Gt<3, 5>;  // false
type _5eq5 = Eq<5, 5>;  // true
```

비교의 모든 분기는 위의 *흡수 패턴*(rest 추론) 한 번이면 충분하다. 이 한 줄짜리 트릭이 type-level fizzbuzz, type-level RPN evaluator 같은 토이의 토대다.

## 7. 실전 라이브러리 비교

| 라이브러리 | Math 모듈 | 최대 표현 가능 정수 | 비고 |
| --- | --- | --- | --- |
| ts-toolbelt 9.x | `N.Add / N.Sub / N.Mul` | 약 999 | string 기반 자릿수 누적, 음수 지원 |
| type-fest 4.x | (limited) | 99 정도 | 유저 친화 위주, Math 는 제한적 |
| hotscript | `Numbers.Add` | 999~ | HKT 인코딩과 결합, lazy evaluation |
| @arktype/util | `add / sub` | 1000 부근 | runtime 과 일관된 타입 추론 |

ts-toolbelt 가 가장 풍부하지만 컴파일 시간 비용도 크다. tsc `--extendedDiagnostics` 로 측정해 보면 `N.Mul<87, 13>` 한 번 호출에 약 35~50 ms 의 type checking 비용이 추가된다. 단위 테스트 5000건 규모에서 헤비하게 쓰면 build time 이 30초 가량 증가할 수 있어, *프로젝트 어딘가에서 단발성으로 한 번* 쓰는 정도가 안전선이다.

## 8. 한계와 대안

타입 시스템 산술은 매혹적이지만 다음 한계가 분명하다.

* 재귀 깊이 ~1000: `Mul<100, 50>` 부터 instantiation depth exceeded
* 음수, 부동소수, 큰 정수는 표현 매우 까다로움
* 컴파일러 메모리 폭증 가능성: 한 instantiation 이 다른 instantiation 을 참조할 때 메모이제이션이 항상 적용되진 않음
* IDE 가 type hover 에 instantiation 을 미리 풀어 보여주려 하면 응답 지연

따라서 실전 권장 패턴은 다음과 같다.

1. *작은 자연수 도메인* 만 타입에 둔다. SQL placeholder index, HTTP method 갯수 등.
2. *큰 수 계산은 런타임* 으로 위임하고, 타입은 brand 로 표식만 남긴다(`type PageSize = number & { __brand: 'PageSize<=100>' }`).
3. *컴파일 시간 측정*: CI 에서 `tsc --diagnostics` 으로 instantiate count 와 check time 을 baseline 으로 잡고 회귀 감지.


## 9. 실전 응용 — SQL Placeholder Index 자동 증가

PostgreSQL 드라이버는 `$1, $2, $3 ...` 으로 placeholder 를 받는다. 쿼리 빌더를 만들 때 컴파일 시점에 인덱스를 자동 부여하면 *런타임에 카운터를 들고 다닐 필요가 없다*. 다음은 type-level 산술의 가장 흔한 실전 적용 예다.

```ts
type IncStr<S extends string> =
    S extends `${infer N extends number}`
        ? `${Add<N, 1>}`
        : never;

type Inc1 = IncStr<'1'>; // '2'
type Inc7 = IncStr<'7'>; // '8'

type BindParam<
    Q extends string,
    Idx extends number = 1
> = Q extends `${infer Pre}?${infer Rest}`
    ? `${Pre}$${Idx}${BindParam<Rest, Add<Idx, 1>>}`
    : Q;

type Q1 = BindParam<'SELECT * FROM users WHERE id = ? AND active = ?'>;
// 'SELECT * FROM users WHERE id = $1 AND active = $2'
```

knex 나 drizzle 의 일부 헬퍼가 이런 패턴을 내부에 가지고 있다. 호출자는 *placeholder index 를 신경 쓸 필요 없이* JDBC 스타일 `?` 만 적으면 컴파일러가 변환해준다. 다만 BindParam 의 재귀는 비-tail 이라 query 길이 200자 정도가 한계임을 주의.

## 10. Boolean Logic — 산술의 사촌

타입 산술의 직계 친척이 boolean logic 이다. `And<A, B>`, `Or<A, B>`, `Not<A>` 는 다음처럼 한 줄로 정의한다.

```ts
type And<A extends boolean, B extends boolean> =
    [A, B] extends [true, true] ? true : false;
type Or<A extends boolean, B extends boolean> =
    [A, B] extends [false, false] ? false : true;
type Not<A extends boolean> = A extends true ? false : true;
type Xor<A extends boolean, B extends boolean> =
    [A, B] extends [true, true] | [false, false] ? false : true;
```

산술 비교 결과(`Lt`, `Gt`, `Eq`) 와 이 boolean 연산자들을 조합하면 `IfElse<C, T, E>` 패턴으로 type-level switch 가 가능하다.

```ts
type IfElse<C extends boolean, T, E> = C extends true ? T : E;

type ResponseStatus<Code extends number> =
    IfElse<Lt<Code, 300>, 'success',
        IfElse<Lt<Code, 400>, 'redirect',
            IfElse<Lt<Code, 500>, 'client_error', 'server_error'>>>;

type S = ResponseStatus<201>; // 'success'
type E = ResponseStatus<404>; // 'client_error'
```

HTTP 라이브러리 타이핑에 이 패턴이 자주 등장한다. axios / ky 같은 wrapper 가 *상태코드 별로 다른 응답 스키마* 를 반환하도록 구성할 때 효과적이다.

## 참고

- TypeScript Handbook — Conditional Types (https://www.typescriptlang.org/docs/handbook/2/conditional-types.html)
- Microsoft TypeScript Issue #26980 — Recursive Conditional Types
- ts-toolbelt N module 소스 (https://github.com/millsp/ts-toolbelt)
- gcanti / hotscript HKT pattern (https://github.com/gvergnaud/hotscript)
- Anders Hejlsberg — TypeScript Type System is Turing Complete (TSConf 2021)
- type-fest Math 모듈 (https://github.com/sindresorhus/type-fest)
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
