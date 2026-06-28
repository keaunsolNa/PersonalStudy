Notion 원본: https://app.notion.com/p/38d5a06fd6d381e6a89efdf462ca5361

# TypeScript 타입 레벨 프로그래밍 재귀 산술과 튜플 길이 및 재귀 깊이 한계

> 2026-06-28 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 튜플 길이 속성을 카운터로 사용해 타입 레벨 덧셈·뀅셈·비교를 구현한다
- 재귀 조건부 타입의 동작 모델과 꼬리재귀 최적화(tail-recursion elimination)를 구분한다
- 컴파일러의 인스턴스화 깊이 한계(약 50/1000)와 회피 전략을 측정한다
- 타입 레벨 연산이 컴파일 시간에 미치는 비용을 평가하고 실무 적용 범위를 판단한다

## 타입은 함수형 언어다

TypeScript 타입 시스템은 튜링 완전에 가깝다. 타입 함수를 제네릭+조건부 타입으로 작성하고 재귀까지 지원한다. 산술의 출발점은 튜플의 length 속성을 자연수의 대용물로 쓰는 것이다.

```ts
type BuildTuple<N extends number, Acc extends unknown[] = []> =
  Acc['length'] extends N ? Acc : BuildTuple<N, [...Acc, unknown]>;
type Five = BuildTuple<5>;
```

## 덧셈·뀅셈·곱셈

```ts
type Add<A extends number, B extends number> = [...BuildTuple<A>, ...BuildTuple<B>]['length'];
type Subtract<A extends number, B extends number> =
  BuildTuple<A> extends [...BuildTuple<B>, ...infer Rest] ? Rest['length'] : never;
```

`A < B`면 매칭 실패로 never가 되어 자연수 범위 밖을 타입 레벨에서 막는다. 곱셈은 덧셈을 B번 반복하는 재귀로 만들지만 결과가 큰 수면 거대한 튜플을 인스턴스화해 비용이 가파르다.

## 비교와 Equal 트릭

```ts
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Assert<T extends true> = T;
type _t1 = Assert<Equal<Add<2, 3>, 5>>;
```

`Equal`은 불변 위치의 함수 조건부 타입을 비교해 정확한 동일성을 판정하며, 타입 레벨 단위 테스트의 핵심 도구다.

## 재귀 깊이 한계

| 한계 | 대략 값 | 증상 |
|---|---|---|
| 일반 재귀 | 약 50 | excessively deep (2589) |
| 꼬리재귀 최적화 | 약 1000 | TR-eligible일 때만 |
| 조건부 누적 | 메모리 한계 | tsserver OOM |

4.5의 꼬리재귀 조건부 타입 최적화가 핵심이다. 재귀 호출이 꼬리 위치에 있으면 스택을 쌓지 않고 반복으로 펀쳐 한계가 50에서 1000으로 확장된다.

```ts
// 꼬리재귀: 누적을 인자로 넘기고 결과를 그대로 반환
type GoodRepeat<N, Acc extends unknown[] = []> =
  Acc['length'] extends N ? Acc : GoodRepeat<N, [...Acc, unknown]>;
```

## 실전 응용과 비용

```ts
type FixedArray<T, N extends number> = BuildTuple<N> extends infer Tup ? { [K in keyof Tup]: T } : never;
type RGB = FixedArray<number, 3>;
```

`tsc --extendedDiagnostics`의 Instantiations가 백만 단위로 치솔거나 Check time이 늘면 타입을 단순화하거나 명시 타입으로 경계를 끊어야 한다. 타입 레벨 연산은 라이브러리 공개 API의 타입 안전성을 끈어올릴 때만 정당화된다. 디버깅은 IDE hover와 twoslash, `Pretty<T>` 트릭, 그리고 Assert 타입 단위 테스트로 한다.

## 참고

- TypeScript 4.5 Release Notes: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- Recursive Conditional Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- type-challenges: https://github.com/type-challenges/type-challenges
