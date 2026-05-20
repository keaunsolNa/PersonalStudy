Notion 원본: https://www.notion.so/3665a06fd6d38110a33bebfb8e2eb620

# TypeScript Higher-Kinded Types 시뮬레이션 — fp-ts URI Encoding과 Functor·Monad 추상화

> 2026-05-20 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript에 *직접적인 HKT 문법*이 없는 이유와 그 한계를 식별한다
- fp-ts가 사용하는 *URI 인코딩* 방식으로 type constructor를 일급으로 다루는 기법을 분석한다
- Functor·Monad·Traversable 추상 인터페이스를 URI 기반으로 표현하는 패턴을 작성한다
- Effect-TS 등 모던 라이브러리의 HKT 발전을 살펴 trade-off를 정량 비교한다

## 1. Higher-Kinded Type이란

```ts
// 다음은 TypeScript에서 *불가능*하다
interface Functor<F<_>> {
  map<A, B>(fa: F<A>, f: (a: A) => B): F<B>;
}
```

TypeScript는 type parameter를 *완성된 타입*만 받도록 설계됐다. 그래서 *우회적 인코딩*이 필요하다.

## 2. URI 인코딩 — fp-ts가 택한 우회 방법

```ts
interface URItoKind<A> {
  Option: Option<A>;
  Array: Array<A>;
  Either: Either<unknown, A>;
}

type URIS = keyof URItoKind<unknown>;
type Kind<F extends URIS, A> = URItoKind<A>[F];

interface Functor1<F extends URIS> {
  readonly URI: F;
  map<A, B>(fa: Kind<F, A>, f: (a: A) => B): Kind<F, B>;
}

const arrayFunctor: Functor1<"Array"> = {
  URI: "Array",
  map: (fa, f) => fa.map(f),
};
```

`URI: "Array"`라는 *literal 필드*가 type system에서 F를 fix한다.

## 3. 사용자 정의 type constructor 등록

```ts
export type Result<E, A> = { ok: true; value: A } | { ok: false; error: E };

declare module "fp-ts/HKT" {
  interface URItoKind<A> {
    Result: Result<Error, A>;
  }
}

const resultFunctor: Functor1<"Result"> = {
  URI: "Result",
  map: (fa, f) => fa.ok ? { ok: true, value: f(fa.value) } : fa,
};
```

TypeScript의 *interface는 같은 이름이면 merge*된다는 특성을 활용한 결과다.

## 4. 다중 type parameter

```ts
interface URItoKind2<E, A> {
  Either: Either<E, A>;
  IOEither: IOEither<E, A>;
  TaskEither: TaskEither<E, A>;
}

type URIS2 = keyof URItoKind2<unknown, unknown>;
type Kind2<F extends URIS2, E, A> = URItoKind2<E, A>[F];

interface Functor2<F extends URIS2> {
  readonly URI: F;
  map<E, A, B>(fa: Kind2<F, E, A>, f: (a: A) => B): Kind2<F, E, B>;
}
```

## 5. Functor에서 Monad로

```ts
interface Monad1<F extends URIS> extends Functor1<F> {
  of<A>(a: A): Kind<F, A>;
  chain<A, B>(fa: Kind<F, A>, f: (a: A) => Kind<F, B>): Kind<F, B>;
}

function liftA2<F extends URIS>(M: Monad1<F>) {
  return <A, B, C>(fa: Kind<F, A>, fb: Kind<F, B>, f: (a: A, b: B) => C): Kind<F, C> =>
    M.chain(fa, a => M.chain(fb, b => M.of(f(a, b))));
}
```

`liftA2`는 monad URI에 의존하지 않는다.

## 6. Effect-TS의 접근 — Variance 인코딩

```ts
export interface Effect<R, E, A> {
  readonly [TypeId]: TypeId;
  readonly _R: (_: R) => void;   // contravariant
  readonly _E: () => E;          // covariant
  readonly _A: () => A;          // covariant
}
```

phantom field는 런타임에 사용되지 않지만 컴파일러가 variance를 추론하는 데 결정적 역할을 한다.

## 7. 실전 use case — domain layer 추상화

```ts
interface UserRepo<F extends URIS2> {
  findById(id: string): Kind2<F, AppError, User>;
  save(user: User): Kind2<F, AppError, void>;
}

function activateUser<F extends URIS2>(M: Monad2<F>, repo: UserRepo<F>) {
  return (id: string): Kind2<F, AppError, User> =>
    M.chain(repo.findById(id), user => {
      if (user.status === "active") return M.of(user);
      const activated = { ...user, status: "active" as const };
      return M.chain(repo.save(activated), () => M.of(activated));
    });
}

const activateAsync = activateUser(taskEitherMonad, taskEitherRepo);
const activateSync = activateUser(eitherMonad, eitherRepo);
```

## 8. 비용·트레이드오프

| 차원 | URI 인코딩(fp-ts) | Effect-TS variance | 직접 구현 |
|---|---|---|---|
| 학습 곡선 | 가파름 | 매우 가파름 | 낮음 |
| Type inference 안정성 | 좋음 | 매우 좋음 | 좋음 |
| 런타임 오버헤드 | 없음 | 매우 적음 | 없음 |
| IDE hint 가독성 | 추상적 | 구체적 | 자연스러움 |
| 라이브러리 생태계 | 크고 안정 | 성장 중 | 직접 작성 |

## 9. Traversable·Apply·확장

```ts
interface Apply1<F extends URIS> extends Functor1<F> {
  ap<A, B>(fab: Kind<F, (a: A) => B>, fa: Kind<F, A>): Kind<F, B>;
}

interface Traversable1<T extends URIS> extends Functor1<T> {
  traverse<F extends URIS>(F: Applicative1<F>): <A, B>(
    ta: Kind<T, A>,
    f: (a: A) => Kind<F, B>
  ) => Kind<F, Kind<T, B>>;
}
```

TS 4.7의 `in`·`out` annotation으로 variance를 명시적으로 고정할 수 있다. 큰 모노레포에서는 type checking 시간을 최대 20% 가까이 단축할 수 있다.

## 참고

- Yallop & Bracker, "Lightweight Higher-Kinded Polymorphism" (FLOPS 2014)
- fp-ts Documentation — HKT, URItoKind, Functor, Monad
- Effect-TS Documentation — Effect type signature, Variance, Type Lambdas
- Microsoft TypeScript Issue #1213 — "Higher-Kinded Types support" (still open)
- "Type-Level TypeScript" by Anthony Fu (2023)
