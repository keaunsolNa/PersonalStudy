Notion 원본: https://www.notion.so/3a25a06fd6d381ca833cf4f2a12836de

# TypeScript 타입레벨 프로그래밍과 재귀 튜플 연산 및 인스턴스화 깊이 한계

> 2026-07-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 튜플의 `length` 와 스프레드를 이용해 타입 수준에서 산술을 구현하는 원리를 설명한다.
- 재귀 조건부 타입이 언제 tail-recursion 으로 최적화되는지 판별한다.
- 인스턴스화 깊이·복잡도 한계(에러 2589/2321)의 실제 임계를 실측으로 확인한다.
- 타입레벨 연산을 도입할지 말지를 컴파일 비용 관점에서 판단한다.

## 1. 타입은 왜 계산이 되는가

TypeScript 의 타입 시스템은 튜링 완전에 가깝다. 이는 세 기능의 조합에서 나온다. 조건부 타입(`A extends B ? X : Y`)이 분기를, `infer` 가 패턴 매칭과 바인딩을, 재귀 타입 별칭이 반복을 제공한다.

실무에서 타입레벨 프로그래밍이 필요한 순간은 값이 아니라 타입 자체가 데이터로 다뤄질 때다. 라우팅 라이브러리가 `"/users/:id"` 를 타입 수준에서 파싱해 파라미터 객체 타입을 뽑는 것이 대표 예다. 핵심은 타입레벨 프로그래밍이 순수 함수형 언어라는 점이다. 재할당·반복문이 없고 재귀만 있으며 모든 것이 불변이다.

## 2. 튜플 length 로 만드는 자연수

타입 수준에는 숫자 연산자가 없다. 대신 튜플의 길이를 자연수의 표현으로 삼는다.

```typescript
type BuildTuple<N extends number, Acc extends unknown[] = []> =
  Acc['length'] extends N ? Acc : BuildTuple<N, [...Acc, unknown]>;

type Add<A extends number, B extends number> =
  [...BuildTuple<A>, ...BuildTuple<B>]['length'] extends infer R
    ? R extends number ? R : never : never;

type Sub<A extends number, B extends number> =
  BuildTuple<A> extends [...infer _Rest, ...BuildTuple<B>]
    ? _Rest['length'] : never;
```

`Sub` 는 뺄셈을 "제거"로 표현한다. 다만 길이 N 튜플을 만들려면 N 번 재귀하므로, N 이 1000 을 넘으면 깊이 한계에 걸린다.

## 3. infer 를 이용한 튜플 해체와 재구성

```typescript
type Head<T extends unknown[]> = T extends [infer H, ...unknown[]] ? H : never;
type Reverse<T extends unknown[]> =
  T extends [infer H, ...infer Rest] ? [...Reverse<Rest>, H] : [];
```

`Reverse` 는 맨 앞을 떼어 나머지를 뒤집은 결과의 뒤에 붙인다. 재귀 호출이 결과를 감싸므로 tail-recursive 가 아니다.

## 4. Tail-Recursion 최적화의 정확한 조건

TypeScript 4.5 부터 컴파일러는 꼬리 재귀 조건부 타입을 반복문으로 펼친다. 재귀 호출이 분기 최상단에 단독으로 있어야 한다.

```typescript
type ReverseNaive<T extends unknown[]> =
  T extends [infer H, ...infer Rest] ? [...ReverseNaive<Rest>, H] : [];

type ReverseTail<T extends unknown[], Acc extends unknown[] = []> =
  T extends [infer H, ...infer Rest] ? ReverseTail<Rest, [H, ...Acc]> : Acc;
```

차이는 누산기를 인자로 넘기느냐다. `ReverseNaive` 는 약 45~50개에서 에러 2589를 던지고 `ReverseTail` 은 수백~1000개까지 견딘다.

| 구현 | 재귀 형태 | 실측 한계(대략) |
|---|---|---|
| ReverseNaive | non-tail | 약 45~50 |
| ReverseTail | tail | 약 999 |
| BuildTuple | tail | 약 999 |

숫자 999 는 컴파일러가 꼬리 재귀를 펼칠 때 내부 반복 카운터를 1000 회로 제한하기 때문이다.

## 5. 두 종류의 한계: 깊이 vs 복잡도

깊이 한계(2589)는 재귀가 너무 많이 중첩될 때다. 복잡도 한계(2321)는 깊이는 얕은데 각 단계가 만드는 유니온·교차 조합이 폭발할 때다. 유니온의 순열을 만드는 타입은 원소 7~8개만 돼도 N! 크기라 터진다. 답은 최적화가 아니라 설계 후퇴다.

## 6. 실전 예: 경로 파라미터 추출

```typescript
type ExtractParams<Path extends string> =
  Path extends `${string}:${infer Param}/${infer Rest}`
    ? Param | ExtractParams<`/${Rest}`>
    : Path extends `${string}:${infer Param}` ? Param : never;
```

이 재귀는 경로 세그먼트 수만큼만 깊어져 깊이 한계와 무관하게 안전하다. 한계를 걱정할 필요가 없는 것이 좋은 타입레벨 설계의 신호다.

## 7. 성능: 컴파일 시간이라는 숨은 비용

```bash
tsc --noEmit --extendedDiagnostics       # Instantiations 수치
tsc --noEmit --generateTrace ./trace-out # chrome://tracing
```

`Instantiations` 가 핵심 지표다. 타입레벨 프로그래밍은 호출부 타입 안전성을 사는 대신 컴파일 시간·가독성을 지불한다. 라이브러리라면 수지가 맞지만 애플리케이션 코드에서 남용하면 팀 전체가 컴파일 지연과 해독 불가 에러를 떠안는다.

## 8. 언제 물러설 것인가

후퇴 신호는 셋이다. 입력 크기가 bounded 되지 않을 때, 타입 에러가 팀에게 안 읽힐 때, 컴파일 시간이 체감될 때다. 결국 타입레벨 프로그래밍은 라이브러리 경계에서 사용자에게 안전을 제공하는 데는 탁월하고, 애플리케이션 도메인 로직을 타입으로 증명하려는 시도는 대개 비용이 이득을 넘는다.

## 참고

- TypeScript Handbook — "Conditional Types", "Template Literal Types"
- TypeScript 4.5 Release Notes — "Tail-Recursion Elimination on Conditional Types"
- TypeScript Wiki — "Performance"
- microsoft/TypeScript 이슈 트래커 — recursion depth limit(1000)
- type-challenges 저장소
