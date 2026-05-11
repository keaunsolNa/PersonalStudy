Notion 원본: https://www.notion.so/35d5a06fd6d381429628d50f6fea9c67

# TypeScript Variance Annotations in out과 Generic 파라미터 분산 제어

> 2026-05-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 함수 / 객체 / generic 위치에서 타입 변수가 갖는 *공변(covariant) · 반변(contravariant) · 불변(invariant) · 이변(bivariant)* 의 정의를 코드로 구분한다
- TypeScript 4.7 부터 도입된 `in` / `out` / `in out` variance annotation 의 의미와, 컴파일러가 이를 어떻게 검증/활용하는지 본다
- `strictFunctionTypes` 가 켜졌을 때 함수 매개변수가 contravariant 로 좁아지는 동작, 그리고 method shorthand 와 property arrow function 의 차이가 만드는 함정 5가지를 정리한다
- 변성을 잘못 선언하면 즉시 컴파일러가 `TS2636` 류 에러로 알려 주는 *self-check* 메커니즘과, 큰 라이브러리에서 그 보장이 얼마나 큰 컴파일 성능 향상을 만드는지 측정 관점에서 다룬다

## 1. 변성(variance) 4가지 정의

`F<T>` 가 어떤 타입 생성자일 때, `A` 가 `B` 의 서브타입이라는 사실로부터 `F<A>` 와 `F<B>` 사이에 어떤 관계가 따라오느냐를 정한 것이 변성이다.

- *covariant*: `A <: B ⇒ F<A> <: F<B>` (예: `ReadonlyArray<T>`, 함수 반환 위치)
- *contravariant*: `A <: B ⇒ F<B> <: F<A>` (예: 함수 매개변수 위치)
- *invariant*: 어느 방향도 성립하지 않음 (예: `Array<T>` — push 가능하므로 매개변수 위치 + readonly 가능하므로 반환 위치, 둘이 충돌해 불변)
- *bivariant*: 두 방향 모두 성립 — 타입 안전성을 일부 포기하는 *느슨한* 모드

TS 의 기본 함수 비교는 매개변수에 대해 *bivariant* 였다(2.6 이전). 2.6 에서 도입된 `strictFunctionTypes` 플래그가 *함수 타입 문법* (`(x: T) => R`) 에 한해 contravariant 비교로 바꿨다. 단 *method shorthand* (`{ foo(x: T): R }`) 는 여전히 bivariant 다. 이 비대칭은 의도된 것 — `Array<T>.push(item: T)` 를 invariant 하게 다루면 `Animal[]` 자리에 `Dog[]` 를 못 넣게 되어 실용 코드가 거의 깨지기 때문이다.

```ts
type FnArrow<T>  = { f: (x: T) => void };
type FnMethod<T> = { f(x: T): void };

let dogArrow: FnArrow<Dog>;
let aniArrow: FnArrow<Animal>;
// strictFunctionTypes=true 에서
aniArrow = dogArrow; // ❌ Animal 인자를 Dog 가 받을 수 없음 (contravariant)
dogArrow = aniArrow; // ✅

let dogMethod: FnMethod<Dog>;
let aniMethod: FnMethod<Animal>;
aniMethod = dogMethod; // ✅ method shorthand 는 bivariant 라 통과
dogMethod = aniMethod; // ✅
```

`class A { foo(x: T) {} }` 의 메서드 선언도 method shorthand 와 동일한 bivariant 다. 이 차이를 모르고 라이브러리 API 를 정의하면 *런타임 에러가 컴파일을 통과* 한다. 안전한 콜백 매개변수는 `cb: (x: T) => R` 형태(화살표 함수 타입) 로 선언해야 한다.

## 2. `in` / `out` / `in out` annotation

TS 4.7 에서 변성을 *명시* 할 수 있는 문법이 추가됐다.

```ts
interface Producer<out T>  { produce(): T; }              // covariant
interface Consumer<in  T>  { consume(x: T): void; }       // contravariant
interface Channel<in out T> {                              // invariant
  send(x: T): void;
  recv(): T;
}
```

annotation 의 효과는 두 가지다.

1. **자가 검증**: 선언된 변성이 *구현* 과 일치하지 않으면 컴파일러가 즉시 거절한다.
2. **타입 인스턴스 비교 가속**: 컴파일러가 비교 시 재귀적으로 풀어보지 않고 *선언된 variance 만으로* 빠르게 판단할 수 있어 큰 라이브러리에서 type-check 시간이 줄어든다(Microsoft 발표 기준 일부 케이스 ≥ 30%).

annotation 위반 예시:

```ts
interface Bad<out T> {
  put(x: T): void;   // ❌ TS2636: Type 'T' is declared as 'out' but used in 'in' position.
}
```

`T` 가 매개변수 위치(contravariant slot)에 있는데 `out` 으로 표시하면 컴파일이 깨진다. 라이브러리 작성자에게는 *선언이 곧 lint* 다.

## 3. 함수 매개변수가 contravariant 인 이유 — substitution 관점

서브타이핑의 본질은 "B 를 기대하는 자리에 A 를 안전하게 넣을 수 있다" 다. 함수 `f: (x: B) => void` 자리에 `g: (x: A) => void` 를 넣으려면 `g` 는 B 가 들어올 수 있는 모든 입력을 받아 줘야 한다. 즉 `A` 가 *더 넓어야* 한다. 따라서 매개변수 위치는 `A <: B` 가 아니라 *반대* 관계 `B <: A` 가 필요하다 = contravariant.

```ts
type AnimalHandler = (x: Animal) => void;
type DogHandler    = (x: Dog)    => void;

// Animal 자리에 Dog 핸들러를 넣는다?
const f: (x: Animal) => void = (x: Dog) => { /* x.bark() */ };
// strictFunctionTypes=true 에서 ❌
```

`f` 를 호출하는 쪽은 임의의 `Animal` 을 던질 수 있는데, 그게 Cat 이라면 내부에서 `x.bark()` 가 깨진다. 그래서 거절하는 게 옳다.

반환 위치는 반대다. `() => Dog` 는 `() => Animal` 자리에 넣어도 안전하다(반환받은 Dog 는 Animal 이기도 하므로). 그래서 반환은 covariant.

## 4. Array<T> 가 invariant 가 *아닌* 이유

엄밀히 보면 `Array<T>.push(item: T)` 가 매개변수 위치에 있으므로 `Array<T>` 는 invariant 가 맞다. 하지만 TS 는 *implicit any* 를 막느라 `Array<Dog>` 를 `Array<Animal>` 에 대입할 수 있게 일부러 covariant 로 약속해 두었다. 그래서 실용적으로는 covariant 처럼 동작한다. 이게 unsoundness 의 대표 사례다.

```ts
const dogs: Dog[] = [new Dog()];
const animals: Animal[] = dogs;  // ✅ TS 가 허용 (사실은 위험)
animals.push(new Cat());          // ✅ 타입 시스템은 통과
const d: Dog = dogs[1];           // 💥 런타임에 Cat 이 들어옴
```

`ReadonlyArray<T>` 는 매개변수 위치가 없으므로 *진정한* covariant 다. 가능하면 함수 매개변수 타입을 `ReadonlyArray<T>` 또는 `readonly T[]` 로 받으면 호출 쪽에서 covariant 한 대입이 안전하게 가능해진다.

```ts
function print<T extends { name: string }>(xs: readonly T[]) {
  for (const x of xs) console.log(x.name);
}
print(dogs);   // ✅
print(animals); // ✅
```

## 5. Promise / Generator 의 변성

`Promise<T>` 는 *대체로* covariant 다. `.then` 의 콜백 매개변수가 contravariant 인 게 충돌처럼 보이지만, 사용자에게 노출되는 `Promise<T>` 자체는 "T 를 만들어 주는" 컨테이너 — covariant.

`Generator<TYield, TReturn, TNext>` 는 세 변수가 각각 다르다.

- `TYield`: covariant (`generator.next()` 결과로 받는 쪽이므로)
- `TReturn`: covariant (마찬가지)
- `TNext`: contravariant (`generator.next(value)` 의 매개변수로 *받는* 쪽이므로)

TS 4.7 에서 lib.es2015.generator.d.ts 가 다음과 같이 갱신됐다.

```ts
interface Generator<out T = unknown, out TReturn = any, in TNext = unknown> {
  next(...args: [] | [TNext]): IteratorResult<T, TReturn>;
  return(value: TReturn): IteratorResult<T, TReturn>;
  throw(e: any): IteratorResult<T, TReturn>;
  [Symbol.iterator](): Generator<T, TReturn, TNext>;
}
```

`TNext` 가 `in` 으로 표시되어 있어 `Generator<X, X, Sub>` 를 `Generator<X, X, Super>` 자리에 안전하게 대입할 수 있게 됐다.

## 6. 변성을 활용한 라이브러리 패턴

### 6.1 Phantom Type 으로 brand 구현 시 invariant 가 필요한 경우

```ts
declare const __brand: unique symbol;
type Brand<T, B> = T & { readonly [__brand]: B };

type UserId = Brand<string, "UserId">;
type PostId = Brand<string, "PostId">;
```

`B` 자리는 *invariant* 가 되어야 `UserId` 와 `PostId` 가 서로 대입되지 않는다. `Brand` 정의에 generic 을 readonly 한 위치에 두면 자동으로 covariant 가 되어 unique brand 가 약해진다. 굳이 강제하려면 다음처럼 `in out` 을 명시한다.

```ts
type Brand<T, in out B> = T & { readonly [__brand]: B };
```

### 6.2 Functor 라이브러리에서 covariance 강제

```ts
interface Functor<out F> {
  map<A, B>(fa: HKT<F, A>, f: (a: A) => B): HKT<F, B>;
}
```

`F` 자리는 *type constructor 의 자리* 인데, 라이브러리 작성자는 "이 자리는 절대 매개변수 위치로 쓰지 않겠다" 를 `out` annotation 으로 보장할 수 있다. 누가 실수로 `consume(x: HKT<F, ...>)` 를 추가하면 즉시 컴파일 깨진다.

## 7. 실측 — annotation 이 만든 컴파일 시간 차이

TypeScript 팀이 4.7 릴리즈에서 보고한 수치:

| 코드베이스 | 4.6 (s) | 4.7 (s) | 절감 |
|---|---|---|---|
| Material-UI | 38.2 | 27.9 | 27% |
| Microsoft Office | 152 | 109 | 28% |
| TypeORM | 19.4 | 15.8 | 18% |

variance annotation 자체는 *옵션* 이지만, 라이브러리 작성자가 명시적으로 표시하면 *해당 generic 의 모든 인스턴스 비교* 가 빨라진다. 비교 알고리즘이 재귀를 펼치지 않고 선언된 variance 로 단축한다.

내부 동작은 `--generateTrace` 로 검증 가능하다.

```bash
tsc --noEmit --generateTrace trace
# trace/trace.json 안에서 variance check 횟수 확인
```

## 8. method shorthand vs property arrow function — 실전 함정

```ts
// Bad: method shorthand → bivariant
interface EventBus {
  on(event: string, cb: (e: MouseEvent) => void): void;
}

// Good: property arrow function → contravariant
interface EventBusStrict {
  on: (event: string, cb: (e: MouseEvent) => void) => void;
}
```

method shorthand 로 선언한 `on` 은 `cb: (e: Event) => void` 와도 호환된다. 그러면 호출자가 `e.button` 같은 MouseEvent 전용 필드를 접근하다가 런타임 폭발. property arrow 로 바꾸면 컴파일러가 호환성을 contravariant 로 검사해 막아 준다.

ESLint 의 `@typescript-eslint/method-signature-style` 룰이 이 차이를 정렬해 준다. 옵션을 `["error", "property"]` 로 두면 모든 함수 시그니처가 property arrow 로 강제된다.

## 9. 변성이 잘못된 코드를 컴파일러가 잡는 케이스 모음

```ts
// (1) Generic 함수 인자 위치 mismatch
type Bad1<out T> = (x: T) => void; // ❌ TS2636

// (2) class 의 readonly + 매개변수가 같이 쓰일 때
class Holder<in T> {
  get value(): T { /* ❌ T 가 'in' 인데 반환 위치(out) 사용 */ return null!; }
}

// (3) 인터페이스 상속 시 변성 호환
interface A<out T> { get(): T; }
interface B<in T> extends A<T> {} // ❌ A 는 out, B 는 in → 불일치
```

이런 자가검증 덕분에 *큰 generic 라이브러리* 에서 리팩토링 안정성이 크게 올라간다. 한 번 annotation 을 붙여 두면 이후 변경에서 변성을 깨는 작업은 컴파일 단계에서 멈춘다.

## 참고

- TypeScript 4.7 Release Notes — Optional Variance Annotations (https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html)
- Microsoft/TypeScript PR #48240 — *Optional Variance Annotations*
- *Programming Language Pragmatics* (Michael L. Scott) — Subtyping and Variance
- *Types and Programming Languages* (Benjamin C. Pierce) — Chapter 15 Subtyping
- Strict Function Types (TS 2.6) — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-6.html
- Microsoft Devblog — *Announcing TypeScript 4.7*, "Optional Variance Annotations for Type Parameters" 섹션
