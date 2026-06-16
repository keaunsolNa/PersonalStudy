Notion 원본: https://www.notion.so/3815a06fd6d381bb8250db332e879b2a

# TypeScript Variadic Tuple Types와 타입 안전 함수 합성 (pipe/compose) 추론

> 2026-06-16 신규 주제 · 확장 대상: Javascript

## 학습 목표

- Variadic Tuple Types 의 spread 위치(앞/중간/뒤)와 추론 규칙을 설명한다
- 튜플 라벨·`infer`·재귀 조건부 타입으로 함수 합성의 타입을 도출한다
- 가변 인자 `pipe`/`compose` 를 타입 안전하게 오버로드 없이 구현한다
- 깊은 재귀의 한계와 컴파일 성능 trade-off 를 판단한다

## 1. Variadic Tuple Types 의 기본 — 튜플 안의 spread

TypeScript 4.0 의 Variadic Tuple Types 는 튜플 타입 안에서 다른 배열/튜플 타입을 `...` 로 펼칠 수 있게 한다. 제네릭으로 받은 튜플을 분해·재조립하는 길이 보존 연산이 가능해진다.

```ts
type Concat<A extends readonly unknown[], B extends readonly unknown[]> = [...A, ...B];
type R = Concat<[1, 2], [3, 4]>; // [1, 2, 3, 4]

// 앞/뒤 원소 분리 — head/tail
type Head<T extends readonly unknown[]> = T extends [infer H, ...unknown[]] ? H : never;
type Tail<T extends readonly unknown[]> = T extends [unknown, ...infer R] ? R : [];
type H = Head<[1, 2, 3]>; // 1
type T = Tail<[1, 2, 3]>; // [2, 3]
```

규칙상 **튜플 안의 spread 는 한 개만, 임의 위치**에 올 수 있다. `[...A, B]`(뒤), `[A, ...B]`(앞), `[A, ...B, C]`(중간)이 모두 유효하다. 단 두 개의 가변 spread(`[...A, ...B]` 에서 A,B 둘 다 길이 미정)는 경계가 모호해 추론이 제한된다.

## 2. 함수 인자를 튜플로 다루기 — Parameters 와 라벨

함수의 인자 목록은 튜플로 표현되며, `Parameters<F>` 로 추출한다. Variadic tuple 과 결합하면 "앞에 인자를 하나 더 붙인 함수" 같은 변형을 타입 레벨에서 만들 수 있다.

```ts
// 첫 인자를 미리 바인딩한 함수 타입 (partial application)
type Bound<F extends (...a: any) => any> =
  F extends (first: any, ...rest: infer R) => infer Ret
    ? (...rest: R) => Ret
    : never;

declare function send(url: string, body: object, retries: number): Promise<void>;
type WithoutUrl = Bound<typeof send>; // (body: object, retries: number) => Promise<void>
```

튜플 원소에 **라벨**(`[count: number, ...items: string[]]`)을 달면 추론 결과와 에디터 시그니처에 의미 있는 이름이 보존된다. 라벨은 타입 동작에는 영향이 없지만 DX 를 크게 개선한다.

## 3. compose 의 타입 — 두 함수 합성부터

함수 합성 `compose(f, g)(x) = f(g(x))` 는 "g 의 반환이 f 의 입력과 맞아야 한다" 는 제약을 타입으로 표현해야 한다.

```ts
// 2-인자 compose: g: A->B, f: B->C  =>  A->C
declare function compose<A, B, C>(
  f: (b: B) => C,
  g: (a: A) => B,
): (a: A) => C;
```

여기서 핵심은 **중간 타입 B 가 두 함수의 경계에서 일치하도록 추론을 강제**하는 것이다. 인자가 2개로 고정이면 단순하지만, 가변 개수를 받으면 재귀가 필요하다.

## 4. 가변 인자 pipe — 재귀 조건부 타입으로 체이닝 검증

`pipe(x, f, g, h)` 는 `h(g(f(x)))` 다. 함수 배열의 "이전 출력 = 다음 입력" 을 재귀적으로 검사하는 타입을 만든다.

```ts
// 함수 체인이 올바르게 연결되는지 검증하며 최종 반환 타입을 도출
type PipeChain<Fns extends readonly unknown[], In> =
  Fns extends [(arg: In) => infer Out, ...infer Rest]
    ? Rest extends readonly unknown[]
      ? PipeChain<Rest, Out>   // Out 을 다음 단계의 In 으로
      : Out
    : In;                      // 더 없으면 현재 타입이 결과

declare function pipe<In, Fns extends readonly ((arg: any) => any)[]>(
  input: In,
  ...fns: Fns
): PipeChain<Fns, In>;

const r = pipe(
  "42",
  (s: string) => s.length,   // string -> number
  (n: number) => n > 1,      // number -> boolean
  (b: boolean) => (b ? "Y" : "N"), // boolean -> "Y"|"N"
); // r: "Y" | "N"
```

체인 중간 타입이 어긋나면(예: 두 번째 함수가 `string` 을 받으려 하면) 해당 위치에서 타입 에러가 난다. 이 방식의 가치는 **오버로드 N개를 손으로 나열하지 않고** 임의 길이를 하나의 재귀 타입으로 처리한다는 점이다. 과거 lodash/Ramda 타입 정의는 `pipe` 를 7~10개 오버로드로 깔았는데, variadic + 재귀로 그 보일러플레이트가 사라진다.

## 5. 인자 검증을 더 엄격하게 — 단계별 에러 위치

위 `PipeChain` 은 결과 타입은 잘 도출하지만, 에러 메시지가 친절하지 않을 수 있다. 각 함수 입력을 "직전 출력" 으로 제약해 **틀린 단계를 정확히 짚는** 변형을 만들 수 있다.

```ts
// 각 함수의 인자 타입을 직전 함수의 반환 타입으로 매핑해 재작성
type StrictPipe<Fns extends readonly unknown[], In> =
  Fns extends [infer F, ...infer Rest]
    ? F extends (arg: infer A) => infer B
      ? [In] extends [A]                       // In 이 A 에 할당 가능한가
        ? [F, ...StrictPipe<Rest extends readonly unknown[] ? Rest : [], B>]
        : ["ERROR: 단계 입력 불일치", expected: A, got: In]  // 틀린 위치 표시
      : never
    : [];
```

이렇게 매핑 튜플을 만들어 `fns` 파라미터 타입으로 쓰면, 잘못 연결된 함수의 자리에 에러 메시지 튜플이 나타나 디버깅이 쉬워진다. 실무 라이브러리(Effect, fp-ts 계열)가 합성 유틸의 에러 경험을 개선할 때 쓰는 패턴이다.

## 6. 깊은 재귀의 한계와 성능 trade-off

재귀 조건부 타입은 강력하지만 컴파일러에 부담을 준다. 두 가지 한계를 알아야 한다.

첫째, **재귀 깊이 제한**. TypeScript 는 타입 인스턴스화 재귀가 일정 깊이(대략 수십~수백 단계, 버전에 따라 다름)를 넘으면 "Type instantiation is excessively deep and possibly infinite" 에러를 낸다. 수백 개를 한 번에 pipe 하는 일은 드물어 실무에서 큰 문제는 아니지만, 타입 레벨 알고리즘(긴 문자열 파싱 등)에서는 자주 부딫힌다. 꼬리재귀 형태(tail-recursive conditional type, TS 4.5+)로 작성하면 컴파일러가 최적화해 더 깊이 갈 수 있다.

둘째, **컴파일 시간**. 복잡한 재귀 타입을 여러 곳에서 인스턴스화하면 `tsc` 가 느려진다. `--diagnostics` 나 `--extendedDiagnostics`, `--generateTrace` 로 타입 체크 비용을 측정해 병목 타입을 찾을 수 있다.

```bash
tsc --noEmit --extendedDiagnostics   # Instantiations, Check time 확인
tsc --noEmit --generateTrace ./trace # trace 폴더 → chrome://tracing 에서 분석
```

`Instantiations` 수치가 수백만을 넘으면 재귀 타입이 과한 신호다. 이때는 (1) 합성 길이에 상한을 둔 오버로드 몇 개로 폴백, (2) 재귀를 꼬리재귀로 재작성, (3) 일부를 `any` 경계로 끊어 인스턴스화를 차단하는 식으로 trade-off 를 잡는다. "타입으로 모든 걸 증명" 하려다 빌드가 수 분 느려지면 본말전도다.

## 7. 실무 적용 가이드

가변 합성 유틸을 직접 만들 일은 많지 않다 — 대개 라이브러리(Effect, fp-ts, Remeda, Ramda 타입)를 쓴다. 그러나 그 타입이 왜 그렇게 동작하는지, 에러가 어느 단계에서 나는지를 읽으려면 variadic tuple + 재귀 조건부 + `infer` 의 결합을 이해해야 한다. 직접 작성할 때의 원칙은 다음과 같다. 합성 체인은 7~10단계를 넘기 드무므로 **재귀 + (안전망용) 소수 오버로드** 조합이 실용적이고, 에러 위치를 짚는 것이 중요하면 §5 의 에러 메시지 튜플 패턴을 쓰며, 빌드가 느려지면 즉시 `--extendedDiagnostics` 로 측정해 근거 기반으로 단순화한다. 타입 안전 합성의 목표는 "런타임 전에 연결 오류를 잡는 것" 이지 "타입 곡예" 자체가 아니다.

## 8. 응용 — curry, 부분 적용, 그리고 인자 누적

Variadic tuple 은 합성뿐 아니라 인자를 점진적으로 모으는 패턴에도 쓰인다. 커링(curry)은 "한 번에 다 받거나, 나눠 받거나" 를 타입으로 표현해야 하는데, 남은 인자 튜플을 재귀적으로 깎아내며 추론한다.

```ts
// 인자를 하나씩 혹은 여러 개씩 받아 다 차면 결과를 반환하는 curry 타입
type Curry<Args extends readonly unknown[], R> =
  Args extends [infer First, ...infer Rest]
    ? (arg: First) => Rest extends [] ? R : Curry<Rest, R>
    : R;

declare function curry<A extends readonly unknown[], R>(
  fn: (...args: A) => R,
): Curry<A, R>;

const add = (a: number, b: number, c: number) => a + b + c;
const c = curry(add);
const r = c(1)(2)(3); // r: number — 각 단계가 정확히 추론됨
```

핵심은 `[infer First, ...infer Rest]` 로 튜플의 머리와 꼬리를 분리하고, 꼬리가 빌 때까지 재귀로 함수를 한 겨씩 벗기는 것이다. 부분 적용(partial)도 같은 분해를 쓴다 — 앞쪽 일부 인자를 미리 받고 `...Rest` 를 남기는 새 함수 타입을 만든다.

```ts
// 앞 N 개를 미리 채우고 나머지를 받는 함수 타입
type PartialApply<A extends readonly unknown[], P extends readonly unknown[]> =
  A extends [...P, ...infer Rest] ? (...rest: Rest) => unknown : never;
```

여기서 `[...P, ...infer Rest]` 는 §1 에서 본 "중간 spread + 뒤쪽 infer" 패턴으로, 앞쪽이 P 와 매칭되고 남는 부분이 Rest 로 추론된다. 이 분해 능력이 variadic tuple 의 실질적 무기다.

## 9. const 타입 파라미터와 satisfies 의 시너지

가변 합성을 정확히 추론하려면 입력 튜플이 **넓혀지지(widening) 않아야** 한다. 예를 들어 `pipe("a", ...)` 에서 `"a"` 가 `string` 으로 넓혀지면 리터럴 정보가 사라진다. TypeScript 5.0 의 **const 타입 파라미터**(`<const T>`)는 호출 시 인자를 리터럴/튜플로 좁게 추론하도록 강제해 이를 막는다.

```ts
// const 파라미터로 인자 튜플을 좁게 고정
declare function pipe<const Fns extends readonly ((arg: any) => any)[], In>(
  input: In,
  ...fns: Fns
): PipeChain<Fns, In>;
```

`satisfies` 연산자는 합성 결과나 중간 설정 객체가 특정 제약을 만족하는지 **타입을 넓히지 않고** 검증한다. 합성 파이프라인의 단계 정의를 객체로 둘 때, `satisfies Record<string, (x: any) => any>` 로 형태만 검사하고 각 함수의 정확한 시그니처는 보존하는 식으로 쓴다. const 파라미터(추론을 좁게)와 satisfies(검증하되 안 넓힘)는 variadic 합성의 추론 정확도를 함께 끌어올리는 짝이다.

정리하면, variadic tuple types 는 (1) 튜플 머리/꼬리/중간 분해(`infer`), (2) 재귀 조건부 타입으로 임의 길이 처리, (3) const 파라미터·satisfies 로 추론 폭 제어라는 세 축으로 함수 합성·커링·부분 적용을 오버로드 없이 타입 안전하게 표현한다. 다만 §6 의 재귀 깊이·컴파일 성능 한계를 항상 함께 고려해, "증명" 과 "빌드 속도" 의 균형점을 측정으로 잡는 것이 실무의 핵심이다.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types, Labeled Tuple Elements
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- TypeScript Handbook — Template Literal & Recursive Conditional Types
- TypeScript Wiki — Performance (`--generateTrace`, 타입 체크 진단)
