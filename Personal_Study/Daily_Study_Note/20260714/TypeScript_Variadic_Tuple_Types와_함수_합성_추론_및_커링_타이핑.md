Notion 원본: https://app.notion.com/p/39d5a06fd6d381ca9dc3c60a0692eab0

# TypeScript Variadic Tuple Types와 함수 합성 추론 및 커링 타이핑

> 2026-07-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 가변 튜플 타입(`[...T]`)의 스프레드 위치 규칙과 라벨 보존 동작을 이해한다
- `Parameters`/`ReturnType`과 가변 튜플을 결합해 함수 합성의 타입을 추론한다
- 커링·부분 적용 함수의 반환 타입을 재귀 튜플로 표현한다
- 가변 튜플 추론이 실패하는 경계(무제한 재귀, 깊은 스프레드)를 확인한다

## 1. 가변 튜플 타입이 푸는 문제

TypeScript 4.0 이전에는 "앞에 임의 개수 인자가 오고 마지막에 콜백이 오는" 함수를 타입으로 표현하기 어려웠다. 가변 튜플 타입은 튜플 안에서 다른 튜플 타입을 스프레드(`...T`)할 수 있게 해, 길이와 요소 타입을 모두 제네릭으로 캡처한다.

```typescript
type Push<T extends unknown[], V> = [...T, V];
type Concat<T extends unknown[], U extends unknown[]> = [...T, ...U];
type C = Concat<[1, 2], [3, 4]>;  // [1, 2, 3, 4]
```

한 튜플에는 스프레드가 여러 번 올 수 있지만, 나머지(rest) 요소는 하나만 허용된다.

## 2. 함수 인자를 튜플로 다루기

함수 타입의 파라미터 목록은 사실상 튜플이다. `Parameters<F>`는 이 튜플을 꺼내고, `(...args: T) => R`은 튜플 `T`를 파라미터로 재구성한다.

```typescript
function bind1<A, Rest extends unknown[], R>(
  fn: (a: A, ...rest: Rest) => R,
  a: A
): (...rest: Rest) => R {
  return (...rest: Rest) => fn(a, ...rest);
}

const add3 = (a: number, b: number, c: number) => a + b + c;
const g = bind1(add3, 10);   // (b: number, c: number) => number
```

`Rest`가 가변 튜플로 캡처되므로 남은 인자의 개수·타입·순서가 그대로 전달되고 라벨까지 보존된다.

## 3. 함수 합성 compose/pipe 타이핑

여러 함수를 이어 붙이는 `pipe`는 각 함수의 반환 타입이 다음 함수의 입력 타입과 맞물리도록 재귀적으로 검사한다.

```typescript
type Pipe<Fns extends unknown[], Acc = never> =
  Fns extends [(arg: infer A) => infer B, ...infer Rest]
    ? Rest extends [(arg: B) => any, ...unknown[]]
      ? Pipe<Rest, B>
      : B
    : Acc;
```

`infer A`, `infer B`가 각 단계의 입출력 타입을 뽑고, 다음 함수가 이전 반환을 받는지를 타입 레벨에서 강제한다.

## 4. 커링의 재귀 타입

커링은 n-인자 함수를 1-인자 함수의 체인으로 바꿈다. 파라미터 튜플을 하나씩 소비하며 반환 타입을 재귀적으로 축소한다.

```typescript
type Curry<Args extends unknown[], R> =
  Args extends [infer First, ...infer Rest]
    ? (arg: First) => Curry<Rest, R>
    : R;
```

부분 적용까지 허용하려면 타입이 급격히 복잡해져, 실무에서는 완전 커링만 타입으로 표현하고 부분 적용은 별도 헬퍼로 분리한다.

## 5. 가변 튜플과 추론 위치

스프레드를 인자 목록 중간에 두고도 앞뒤 요소를 각각 추론할 수 있다.

```typescript
function partialRight<Head extends unknown[], Last, R>(
  fn: (...args: [...Head, Last]) => R,
  last: Last
): (...head: Head) => R {
  return (...head: Head) => fn(...head, last);
}
```

`[...Head, Last]` 패턴에서 컴파일러는 마지막 요소를 `Last`로, 앞부분을 `Head`로 분리 추론한다.

## 6. 실패하는 경계

컴파일러의 타입 인스턴스화 깊이 제한에 걸리면 "Type instantiation is excessively deep" 에러가 난다.

| 상황 | 추론 결과 |
|---|---|
| `[...FixedTuple, Last]` | Head/Last 분리 성공 |
| `[First, ...FixedTuple]` | First/Tail 분리 성공 |
| `[...OpenA, ...OpenB]` | 경계 불명 → `unknown[]` |
| 재귀 깊이 > ~1000 | 인스턴스화 깊이 초과 에러 |

스프레드가 둘 이상 불확정 길이면 어디서 나누는지 결정 불가라 추론이 `unknown[]`로 붕괴한다.

## 7. 성능과 대안

타입 레벨 재귀는 컴파일 시간에 직접 비용을 부과한다. `tsc --extendedDiagnostics`의 `Instantiations` 카운트로 확인하며, 완화책은 유한 오버로드, 한 번에 여러 요소를 뽑는 infer, 누적 튜플 꾬리 재귀이다. Ramda·fp-ts·Effect는 완전 재귀 대신 유한 오버로드 세트를 제공해 컴파일 성능과 에러 메시지 가독성을 지킨다.

## 7.5. zip과 튜플 병렬 순회

여러 튜플을 병렬로 묶는 `zip`은 요소별로 짝지어 쌍의 튜플로 만든다.

```typescript
type Zip<A extends unknown[], B extends unknown[]> =
  A extends [infer AH, ...infer AT]
    ? B extends [infer BH, ...infer BT]
      ? [[AH, BH], ...Zip<AT, BT>]
      : []
    : [];
type Z = Zip<[1, 2, 3], ["a", "b", "c"]>;  // [[1,"a"],[2,"b"],[3,"c"]]
```

두 튜플의 길이가 다르면 짧은 쪽 기준에서 멈춰 남는 요소는 버려진다. 이는 런타임 `zip` 구현과 정확히 일치하도록 설계된 것으로, 타입과 런타임이 어긋나지 않게 하는 좋은 예다.

## 8. 실전 지침

가변 튜플은 고차 함수의 타입을 오버로드 폭발 없이 표현하는 최선의 도구지만, 인자 개수가 정해진 API라면 명시적 시그니처가 더 읽기 쉽고 에러도 명확하다. 재귀 깊이가 실사용 범위를 넘지 않는지 극단 케이스로 검증하라.

## 참고

- TypeScript 4.0 Release Notes — Variadic Tuple Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-0.html
- TypeScript Handbook — Generics & Conditional Types: https://www.typescriptlang.org/docs/handbook/2/generics.html
- Type Challenges: https://github.com/type-challenges/type-challenges
- fp-ts function module: https://gcanti.github.io/fp-ts/modules/function.ts.html
