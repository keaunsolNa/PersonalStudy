Notion 원본: https://www.notion.so/3575a06fd6d381199d27ce41910d57a0

# TypeScript Higher-Kinded Types — HKT 시뮬레이션과 Type Lambda 설계

> 2026-05-05 신규 주제 · 확장 대상: Conditional Types, Variance

## 학습 목표

- TypeScript 가 native HKT 를 지원하지 않는 이유와, fp-ts·Effect 가 사용하는 *URI 트릭* 의 동작 원리를 정리한다
- Effect 3.0 의 `TypeLambda` 인터페이스가 어떤 식으로 type lambda 를 시뮬레이션하는지 단계적으로 재현한다
- HKT 가 필요한 실제 사례(Functor, Traversable, Free 변환자)를 코드로 구현해 본다
- Native HKT 가 들어올 때까지 *현실적인* 추상화 한계와 우회법을 정리한다

## 1. 왜 TypeScript 는 HKT 가 없는가

Higher-Kinded Type 은 *타입을 인자로 받는 타입 생성자(generic of generic)* 이다. Haskell 의 `Functor f => f a -> f b`, Scala 의 `F[_]: Monad` 처럼 컨테이너를 추상화한다. TypeScript 는 generic 의 generic 을 일급으로 받지 못한다.

```ts
// 우리가 원하는 것
interface Functor<F> {
  map<A, B>(fa: F<A>, f: (a: A) => B): F<B>;
}
//             ~~~ ❌ "Type 'F' is not generic"
```

원인은 TypeScript 의 type checker 가 *structural* 이고 *first-order* 이기 때문이다. type parameter 위치에는 구체 타입만 들어갈 수 있다. 이는 의도적 결정이다. 1) 추론기의 복잡도가 EXPTIME 으로 폭발할 수 있고, 2) JS 의 런타임 모델과 mismatch 가 커진다. 그래서 TypeScript 4.7 의 `extends` constraint, 5.0 의 `const` modifier 등이 들어왔지만 HKT 는 여전히 거부됐다. issue #1213 은 2014 년에 열린 이래 12년째 열려 있다.

## 2. URI 트릭 — fp-ts 의 우회

fp-ts 는 우회 수단으로 *registry pattern* 을 도입했다. 모든 컨테이너에 *문자열 URI* 를 부여하고, 전역 인터페이스 `URItoKind<A>` 를 declaration merging 으로 누적한다.

```ts
// 1. 컨테이너마다 URI 정의
declare module "./HKT" {
  interface URItoKind<A> {
    Option: Option<A>;
    Either_E: Either<E, A>;     // 인자가 둘이면 partial application 처럼
    Array: ReadonlyArray<A>;
  }
}

// 2. Kind helper
type URIS = keyof URItoKind<any>;
type Kind<F extends URIS, A> = URItoKind<A>[F];

// 3. Functor 정의
interface Functor<F extends URIS> {
  readonly URI: F;
  map<A, B>(fa: Kind<F, A>, f: (a: A) => B): Kind<F, B>;
}

const ArrayFunctor: Functor<"Array"> = {
  URI: "Array",
  map: (fa, f) => fa.map(f),
};
```

trick 의 본질은 *런타임 string* 을 *컴파일 타임 키* 로 빌려 쓰는 것이다. 인자가 둘 이상인 컨테이너(`Either<E, A>`) 는 `URItoKind2<E, A>`, `URItoKind3<R, E, A>` 처럼 arity 별로 인터페이스를 분리한다. 이는 fp-ts 1.x → 2.x 전환의 가장 큰 변경이었다.

문제는 두 가지다. 첫째, arity 별로 *별도 인터페이스* 를 둬야 해서 라이브러리 코드가 폭발한다. 둘째, *higher-rank polymorphism* 이 안 된다. `<F extends URIS>(...)` 는 호출 시 단일 `F` 로 고정된다. Free monad, monad transformer 처럼 type lambda 자체를 추상화해야 하는 패턴은 짜기 어렵다.

## 3. Effect 3.0 의 `TypeLambda` — 한 단계 진화

Effect 3.0 은 fp-ts 의 URI 트릭을 *type lambda* 로 일반화한다. `TypeLambda` 는 입력 타입을 어떻게 출력 타입으로 변환할지 *하나의 인터페이스* 로 표현한다.

```ts
import { TypeLambda } from "effect/HKT";

// 1. type lambda 인터페이스 (4-arity)
//    Out2 = output (success), Out1 = error, In = requirements, etc.
interface OptionTypeLambda extends TypeLambda {
  readonly type: Option<this["Target"]>;
}

interface EffectTypeLambda extends TypeLambda {
  readonly type: Effect<this["Target"], this["Out1"], this["Out2"]>;
}

// 2. Kind apply
type Kind<F extends TypeLambda, In, Out2, Out1, Target> = (F & {
  readonly In: In;
  readonly Out2: Out2;
  readonly Out1: Out1;
  readonly Target: Target;
})["type"];

// 3. Functor
interface Functor<F extends TypeLambda> {
  map<A, B, R, O1, O2>(
    f: (a: A) => B
  ): (self: Kind<F, R, O2, O1, A>) => Kind<F, R, O2, O1, B>;
}
```

핵심 트릭은 `this["Target"]` 이다. TypeScript 는 인터페이스 안에서 `this` 가 *가장 derived 한 타입* 을 가리킨다. `TypeLambda` 를 확장한 인터페이스에 `In/Out1/Out2/Target` 슬롯이 추가되면, `OptionTypeLambda["type"]` 안의 `this["Target"]` 이 그 슬롯을 읽는다. 결과적으로 *type-level 함수* 가 만들어진다.

| 모델 | 표현력 | 단점 |
|---|---|---|
| URI 트릭 (fp-ts) | 단순, 학습 곡선 낮음 | arity 별 인터페이스, higher-rank 미지원 |
| TypeLambda (effect) | 4-arity 단일 인터페이스, 변성 명시 가능 | this["Target"] 의도 학습 필요 |
| 가짜 native HKT (TS proposal) | 직관적 | 컴파일러 변경 필요, 불가 |

## 4. Functor / Applicative / Monad 의 구체 구현

`OptionTypeLambda` 와 `Functor<F>` 를 합쳐 실제 사용 코드를 보자.

```ts
import { Option, identity } from "effect";

const OptionFunctor: Functor<OptionTypeLambda> = {
  map: (f) => (self) => Option.match(self, {
    onNone: () => Option.none(),
    onSome: (a) => Option.some(f(a)),
  }),
};

const inc = OptionFunctor.map((n: number) => n + 1);
inc(Option.some(3));    // Option.some(4)
inc(Option.none());     // Option.none()
```

같은 `Functor` 인터페이스를 `EffectTypeLambda` 로 인스턴스화하면 `Effect` 에 대한 `map` 도 *동일한 시그니처* 로 얻는다. *추상화의 가치* 는 라이브러리 함수 하나로 두 컨테이너를 모두 다룰 수 있다는 점이다.

`Applicative` 는 `pure: A → F<A>`, `ap: F<A→B> → F<A> → F<B>` 를 추가한다. `Monad` 는 `flatMap` 을 추가한다. Effect 의 표준 모듈은 모두 이 type class 인스턴스를 export 한다 (`effect/Option#Functor`, `effect/Effect#Applicative`).

## 5. Traversable — 진짜 HKT 가 필요한 자리

`Traversable` 은 *컨테이너 안의 컨테이너* 를 뒤집는 연산이다. `Array<Effect<R, E, A>> → Effect<R, E, Array<A>>` 가 대표적이다. 이때 두 개의 type lambda 가 동시에 필요하다.

```ts
interface Traversable<T extends TypeLambda> {
  traverse<F extends TypeLambda>(
    F: Applicative<F>
  ): <A, B, R, O1, O2>(
    f: (a: A) => Kind<F, R, O2, O1, B>
  ) => <Tin, Tout1, Tout2>(
    self: Kind<T, Tin, Tout2, Tout1, A>
  ) => Kind<F, R, O2, O1, Kind<T, Tin, Tout2, Tout1, B>>;
}
```

이 시그니처가 fp-ts 의 URI 트릭으로는 *작성 자체가 불가능* 하다. 두 개의 generic-of-generic 를 동시에 받아야 하기 때문이다. Effect 의 `TypeLambda` 모델은 이를 깔끔하게 표현한다. 실제로 효과 라이브러리에서 가장 자주 쓰이는 `Effect.forEach` 가 결국 Array Traversable 의 인스턴스다.

| 일상 사용 | 본질 |
|---|---|
| `Promise.all(items.map(fetchOne))` | Array Traversable for Promise (구조적 동시성 X) |
| `Effect.forEach(items, fetchOne, { concurrency: 8 })` | Array Traversable for Effect (구조적 동시성 O) |
| `Object.entries(o).map(...)` 후 fromEntries | Record Traversable 를 손으로 구현한 형태 |

## 6. Free 변환자 — Type Lambda 합성의 끝판

interpreter 패턴(commands → effects) 을 type-safe 하게 만드는 가장 우아한 방법이 *Free monad* 다. Effect 도 내부적으로 `FiberRuntime` 이 비슷한 일을 한다. 사용자 코드는 *서술* 만 하고 실행은 인터프리터가 한다.

```ts
type DslF<A> =
  | { _tag: "Pure"; value: A }
  | { _tag: "Read"; key: string; next: (s: string) => DslF<A> }
  | { _tag: "Write"; key: string; value: string; next: () => DslF<A> };

const interpret = <A>(p: DslF<A>): Effect.Effect<A, never, KV> =>
  Effect.gen(function* () {
    const kv = yield* KV;
    switch (p._tag) {
      case "Pure":  return p.value;
      case "Read":  return yield* interpret(p.next(yield* kv.get(p.key)));
      case "Write": yield* kv.set(p.key, p.value); return yield* interpret(p.next());
    }
  });
```

여기서 *컨테이너* 는 `DslF` 이고, *효과* 는 `Effect` 다. 둘 다 type lambda 가 필요하다. 도메인 코드는 `DslF` 만 알고, 인프라 코드는 `Effect` 만 안다. 두 영역을 교차 추상화하는 코드를 짜려면 fp-ts 시절엔 거의 불가능했지만, Effect 의 `TypeLambda` 로는 자연스럽게 표현된다. 실제 도메인에서 Free 까지 가는 일은 드물지만, 이런 추상화의 *상한선* 을 인지하고 있어야 작은 추상화의 비용이 정당화된다.

## 7. native HKT 의 가능성과 limit

TypeScript 팀이 HKT 거부의 사유로 자주 인용하는 것은 다음과 같다. (1) generic 의 generic 가 들어오면 추론기의 unification 알고리즘 복잡도가 폭발한다. (2) declaration merging 의 의미가 모호해진다. (3) JS 와의 런타임 mismatch.

따라서 TypeScript 5.x ~ 6.x 에서도 native HKT 가 들어올 가능성은 낮다. *현실적 우회* 의 권장 우선순위는 다음과 같다.

첫째, *대부분의 도메인 코드* 는 HKT 가 필요 없다. Effect 의 `Effect.map`, `Effect.flatMap` 등 구체 메서드를 그대로 쓰면 된다. type class instance 를 받는 함수를 *직접 정의* 하는 일은 라이브러리 작성 시에만 의미 있다.

둘째, 라이브러리를 작성한다면 `effect/HKT` 의 `TypeLambda` 를 그대로 따라 쓰는 게 안전하다. 자체 정의한 URI 트릭은 다른 라이브러리와 호환되지 않는다.

셋째, *type-level 프로그래밍* 의 한계를 인지한다. TypeScript 의 conditional type 재귀 깊이는 50으로 제한된다. type lambda 를 깊게 합성하면 `Type instantiation is excessively deep and possibly infinite` 가 발생한다. 이때는 *추상화를 한 단계 낮추거나* `infer` + tail-recursive accumulator pattern 으로 풀어준다.

## 8. 운영에서 본 trade-off — 도입 결정 기준

| 상황 | 권장 |
|---|---|
| 라이브러리(특히 backend BFF / data 라이브러리) | TypeLambda 적극 도입 |
| 신규 백엔드 도메인 코드 | 구체 메서드 사용. 굳이 Functor 인스턴스 손으로 만들지 말 것 |
| 기존 fp-ts 코드 마이그레이션 | URI 트릭 제거 → TypeLambda. 일괄이 아닌 *모듈 단위* 전환 |
| 1~2 명 풀스택 스타트업 | 보류. 표준 `await` 만으로 충분 |

HKT 시뮬레이션은 *말장난이 아니라 추상화의 도구*다. 그러나 같은 도구로 같은 코드를 짜더라도, *생산성* 은 팀의 type-level 프로그래밍 숙련도에 비례한다. Conditional types 와 mapped types 가 익숙한 팀이 아니라면, TypeLambda 를 *직접 만드는 것* 이 아니라 *Effect 가 제공하는 것을 쓰는 것* 만으로도 충분한 가치를 얻는다.

## 참고

- TypeScript Issue #1213 — Higher-Kinded Types 제안 (2014 ~ 현재)
- effect-ts/effect — `packages/effect/src/HKT.ts` 의 TypeLambda 정의
- "Lightweight Higher-Kinded Polymorphism" — Yallop, White (ML Workshop 2014)
- fp-ts 2.x 문서 — URItoKind / URIS 설명