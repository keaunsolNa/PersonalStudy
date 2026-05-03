Notion 원본: https://www.notion.so/3555a06fd6d381e48a05d1608b34ecd4

# TypeScript Variance와 strictFunctionTypes — Method/Property Signature와 Bivariance

> 2026-05-03 신규 주제 · 확장 대상: TypeScript Conditional Types, Mapped Types

## 학습 목표

- 공변(covariance) · 반공변(contravariance) · 양변(bivariance) · 불변(invariance) 네 가지 검사 방향을 함수 타입 호환성 관점에서 구분한다
- `strictFunctionTypes` 가 켜진 상태와 꺼진 상태에서 함수 파라미터 검사 방향이 어떻게 달라지는지 컴파일 결과로 확인한다
- `Method Signature` 와 `Property Signature` 가 동일 의미의 함수처럼 보여도 호환성 검사 시 다르게 다뤄지는 이유를 설명한다
- 콜백을 받는 라이브러리 API 를 설계할 때 `(arg: T) => void` 와 `interface { fn(arg: T): void }` 중 어느 것을 쓸지 의식적으로 고른다

## 1. 변성(variance) 의 네 가지 분류

타입 시스템에서 변성은 "타입 생성자(컨테이너)에 들어 있는 인자가 부모-자식 관계일 때 컨테이너 자체의 부모-자식 관계가 어떻게 결정되는가" 를 가리킨다. 정의를 명확히 정리해 두면 이후 함수 타입 분석이 쉬워진다. `A <: B` 를 "A 는 B 의 서브타입" 이라고 읽는다.

| 변성 | 정의 | 함수에서의 위치 |
|---|---|---|
| 공변 | `A <: B` 이면 `F<A> <: F<B>` | 반환 타입 |
| 반공변 | `A <: B` 이면 `F<B> <: F<A>` | 파라미터 타입(strict) |
| 양변 | 양쪽 다 허용. `F<A>` 와 `F<B>` 가 서로 호환 | 파라미터(메서드 시그니처, 비-strict) |
| 불변 | 어느 방향도 허용하지 않음 | 가변 컬렉션, mutable ref |

TypeScript 는 안전하게 동작하는 가장 강한 변성 대신 *실용적으로 통과시킬 수 있는 가장 약한 변성* 을 자주 선택한다. 함수 파라미터의 양변 검사가 그 대표 예시다.

## 2. 함수 반환 타입은 항상 공변

가장 직관적인 부분이다. 다음 코드는 어떤 옵션에서도 통과한다.

```ts
class Animal { name = '' }
class Dog extends Animal { bark() {} }

type GetAnimal = () => Animal
type GetDog    = () => Dog

const f1: GetAnimal = (() => new Dog()) as GetDog
```

반환 타입은 호출자가 "그 이상 또는 동일한 정보" 를 받기만 하면 안전하므로 `Dog` 를 반환하는 함수는 `Animal` 을 반환하는 함수가 들어갈 자리에 항상 들어갈 수 있다. 공변이라고 부르는 이유다. 반환 타입을 좁히면(narrow) 호환되고 넓히면(widen) 호환되지 않는다.

## 3. 함수 파라미터는 strict 모드에서 반공변

`strictFunctionTypes: true` (또는 `strict: true`) 가 켜져 있을 때, **함수 타입 표현식(`(x: T) => U`) 의 파라미터** 는 반공변으로 검사된다.

```ts
type AcceptsAnimal = (a: Animal) => void
type AcceptsDog    = (d: Dog) => void

declare const f: AcceptsAnimal
declare const g: AcceptsDog

const a: AcceptsDog = f       // OK   — Animal 받는 함수는 Dog 도 처리 가능
const b: AcceptsAnimal = g    // ERROR — Dog 만 받는 함수에 Animal 을 넘기면 위험
```

직관: Dog 만 받을 수 있는 함수에 임의의 Animal 을 넘기면 `bark()` 가 없는 Animal 이 들어와 런타임 오류가 난다. 그래서 파라미터 방향에서는 *더 넓은 타입* 을 받는 함수가 *더 좁은 타입* 을 받는 함수의 자리에 들어갈 수 있다 — 부모-자식 관계가 뒤집힌다. 이것이 반공변이다.

`tsc --strictFunctionTypes` 또는 `--strict` 플래그가 없는 코드 베이스에서는 이 검사가 양변(둘 다 OK)이 되어 위 두 줄이 모두 통과한다. 1.x 시절 TypeScript 의 호환성 결정이며, 2.6 에서 `strictFunctionTypes` 가 도입되어 옵트인 방식으로 바로잡혔다.

## 4. Method Signature 는 strict 에서도 양변

여기서 흥미로운 함정이 등장한다. 동일한 함수처럼 보이지만 작성 방식에 따라 검사 방향이 달라진다.

```ts
interface PropertyForm {
  handle: (a: Animal) => void          // property signature
}
interface MethodForm {
  handle(a: Animal): void               // method signature
}

declare const p: PropertyForm
declare const m: MethodForm

const np: { handle: (d: Dog) => void } = p   // ERROR — 반공변 검사
const nm: { handle(d: Dog): void } = m       // OK    — 양변 검사
```

`PropertyForm` 의 `handle` 은 일반 함수 타입 프로퍼티이므로 strict 룰에 따라 반공변으로 검사되고, `MethodForm` 의 `handle` 은 메서드 시그니처이므로 양변으로 검사된다. 이 차이를 모르고 인터페이스를 설계하면 의도하지 않은 빈틈이 생긴다.

왜 메서드 시그니처는 양변으로 남았는가? `Array<T>.push(x: T): number` 같은 표준 라이브러리 시그니처를 strict 화하면 `Array<Dog>` 를 `Array<Animal>` 자리에 넣으려는 기존 코드 대다수가 깨지기 때문이다. 양변은 안전하지 않은 절충이지만 마이그레이션 비용을 감안한 의도적 결정이다 (TypeScript 2.6 릴리스 노트, microsoft/TypeScript#18654).

## 5. tsc 컴파일 결과로 확인하기

다음 두 파일을 `tsc --noEmit --strict` 로 검사하면 method 쪽만 통과한다. 동일 시그니처에 대해 컴파일러가 다른 결정을 내린다는 것을 직접 보는 것이 이해에 가장 좋다.

```ts
// variance-property.ts
interface Box<T> { value: T; set: (v: T) => void }
declare const dogBox: Box<Dog>
const animalBox: Box<Animal> = dogBox    // ERROR ts(2322)
//                                value 는 OK(공변), set 의 (v:T)=>void 는 invariant 처리
```

```ts
// variance-method.ts
interface Box<T> { value: T; set(v: T): void }
declare const dogBox: Box<Dog>
const animalBox: Box<Animal> = dogBox    // OK — set 가 method signature 라 양변
```

설명하자면, `Box<T>` 의 `set` 처럼 `T` 가 입출력 양쪽에 등장(여기서는 입력)할 때, property signature 형은 strict 모드에서 안전한 invariance/반공변 결정을 내려 거부한다. method signature 형은 같은 의미인데도 양변으로 통과시킨다.

## 6. 클래스 메서드도 method signature 다

클래스 본문에서 `method() {}` 로 선언한 멤버 역시 method signature 로 emit 된다. 따라서 다음도 strict 에서 통과한다.

```ts
class EventBus<T> {
  on(handler: (payload: T) => void) {}
}

declare const bus: EventBus<MouseEvent>
const ub: EventBus<UIEvent> = bus     // OK — 양변
```

반대로 `on` 을 다음처럼 화살표 함수 프로퍼티로 정의하면 반공변으로 바뀌어 위 할당이 거부된다.

```ts
class EventBus<T> {
  on = (handler: (payload: T) => void) => {}  // arrow property → 반공변
}
```

이 차이는 단순한 스타일 차이가 아니라 라이브러리 사용자에게 노출되는 변성을 결정하므로, 콜백 등록 API 를 설계할 때 의식적으로 고른다.

## 7. 변성 어노테이션 (TS 4.7+)

TypeScript 4.7 에서 명시적 변성 마커 `in`, `out` 이 추가되어 일부 인터페이스에서 의도된 변성을 강제할 수 있다.

```ts
interface Producer<out T> { get(): T }            // 공변만 허용
interface Consumer<in T>  { set(v: T): void }     // 반공변만 허용
interface Box<in out T>   { value: T }            // 불변
```

`out T` 를 단 인터페이스에서 `T` 가 입력 위치에 등장하면 컴파일 오류를 띄운다. 즉, "이 제네릭 파라미터가 의도와 다른 위치에 들어갔다" 를 정적으로 잡아준다. method signature 의 양변 우회를 막고 싶을 때 유용하다.

```ts
interface SafeBox<in out T> { set(v: T): void; get(): T }
declare const dogBox: SafeBox<Dog>
const animalBox: SafeBox<Animal> = dogBox        // ERROR — invariance 강제
```

`--strictFunctionTypes` + 명시적 `in out` 조합은 안전성이 중요한 도메인 모델 인터페이스에 권장된다.

## 8. trade-off 와 실무 가이드

| 상황 | 권장 형태 | 이유 |
|---|---|---|
| 콜백을 받는 라이브러리 API | property signature `on: (h: T) => void` | strict 반공변으로 호환성 사고 차단 |
| 표준 컬렉션류 메서드 | method signature `push(x: T): number` | 마이그레이션 부담, 양변 허용 |
| 도메인 모델, 이벤트 페이로드 | `interface Foo<in out T>` 명시 | 변성 사고를 컴파일 타임에 차단 |
| 함수형 라이브러리 (`fp-ts`, `Effect`) | property signature, `--strict` | 법칙(law) 검증을 위해 변성 정확성 필수 |

추가 권장: 새 프로젝트는 반드시 `tsconfig.json` 에 `"strict": true` 를 둔다. `strict` 는 `strictFunctionTypes`, `strictNullChecks`, `noImplicitAny` 등 7개 플래그를 한 번에 켠다. method signature 의 양변 함정을 명시적으로 닫고 싶다면, ESLint 의 `@typescript-eslint/method-signature-style: ['error', 'property']` 규칙을 추가해 모든 인터페이스 메서드를 property signature 로 강제할 수 있다.

## 9. 결론과 점검 체크리스트

함수 타입의 변성은 "안전성 vs 마이그레이션 비용" 의 교환에서 결정된 산물이다. 같은 의미처럼 보이는 두 시그니처가 다른 검사를 받는 이유는 공식 릴리스 노트와 GitHub 이슈에 명시되어 있다. 정리:

- 반환 타입 : 항상 공변
- 파라미터 타입 (function type / property method) : strict 에서 반공변
- 메서드 시그니처 (`m(x: T): U`) : strict 에서도 양변
- 명시적 변성 마커 `in out` 으로 양변 우회 차단 가능

점검 체크리스트:

1. `tsconfig.json` 에 `"strict": true` 가 있는가
2. 라이브러리 공개 API 의 콜백은 property signature 로 작성했는가
3. 내부 도메인 모델 제네릭에 `in out` 변성 마커를 의도적으로 부여했는가
4. ESLint `method-signature-style` 규칙이 활성화되어 있는가

## 10. 실전 케이스 — Promise/Observable 의 변성

`Promise<T>` 와 RxJS `Observable<T>` 의 변성도 실무에서 자주 부딪히는 주제다. `Promise<Dog>` 가 `Promise<Animal>` 자리에 들어갈 수 있는가?

```ts
const dogPromise: Promise<Dog> = Promise.resolve(new Dog())
const animalPromise: Promise<Animal> = dogPromise   // OK 인가?
```

`Promise<T>` 는 `T` 가 `then(onFulfilled: (value: T) => U)` 의 *입력 위치* 에 등장하므로 엄밀하게는 invariant 다. 그러나 실제 lib.es5.d.ts 에서 `then` 은 method signature 로 선언되어 있어 양변으로 통과한다. 결과적으로 위 할당은 strict 에서도 통과한다.

```ts
// lib.es5.d.ts (요약)
interface Promise<T> {
  then<U>(onfulfilled?: (value: T) => U | PromiseLike<U>): Promise<U>
  catch<U>(onrejected?: (reason: any) => U | PromiseLike<U>): Promise<T | U>
}
```

만약 `Promise<T>` 가 property signature 로 정의됐다면 위 할당이 거부됐을 것이다. 표준 라이브러리가 method signature 를 채택한 이유가 곧 *기존 코드 호환* 이다.

같은 이유로 `Array<Dog>` → `Array<Animal>` 도 strict 에서 통과한다 — 안전하지 않은데도(예: `arr.push(new Cat())` 로 invariant 깨짐). 표준 라이브러리의 양변 결정은 명백히 trade-off 다.

## 11. 변성 검증 유틸리티 타입

자체 타입 시스템에서 변성을 *검증* 하고 싶을 때 다음 헬퍼 타입이 유용하다.

```ts
// 두 타입이 정확히 같은지 (양방향 호환)
type Equal<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends
  (<T>() => T extends Y ? 1 : 2) ? true : false

// 서브타입 검증
type Extends<X, Y> = X extends Y ? true : false

type _t1 = Equal<(d: Dog) => void, (a: Animal) => void>     // false (반공변 검사)
type _t2 = Extends<(a: Animal) => void, (d: Dog) => void>   // true (부모가 자식 자리에 가능)
```

`Equal<X, Y>` 는 conditional type 의 *동일성 검사를 우회하는* 트릭이다. 단순 `X extends Y ? Y extends X` 만으로는 함수 변성 같은 케이스에서 false positive 가 나오기 때문이다. tsd / type-fest 같은 라이브러리가 이 정의를 그대로 쓴다.

## 참고

- TypeScript 2.6 release notes — Stricter checking of function types under `--strictFunctionTypes`
- TypeScript 4.7 release notes — Optional Variance Annotations for Type Parameters
- microsoft/TypeScript#18654 — Discussion on method signature bivariance trade-off
- Anders Hejlsberg, "TypeScript: Type Soundness vs Practicality" 2019 talk (TSConf)
- Eric Lippert, "Variance" series (참고 개념: C# 의 `in/out` 키워드와 동일 모델)
- Type Challenges 저장소 (`type-challenges/type-challenges`) — variance 관련 문제 다수
- Marius Schulz, "TypeScript Function Type Variance: Bivariance and Strict Function Types" 블로그
- Patrick Stapfer, "Practical TypeScript: Variance Annotations" YouTube 영상 (TS 4.7 출시 직후)
- Microsoft Learn, "Variance in Generic Types (C#)" — TS 의 in/out 모델이 차용한 원본 문서
