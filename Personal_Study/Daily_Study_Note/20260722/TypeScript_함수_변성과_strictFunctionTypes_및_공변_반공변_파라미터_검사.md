Notion 원본: https://app.notion.com/p/3a55a06fd6d381ada8d0ebb7781b265e

# TypeScript 함수 변성과 strictFunctionTypes 및 공변·반공변 파라미터 검사

> 2026-07-22 신규 주제 · 확장 대상: TypeScript(타입 시스템·할당 가능성)

## 학습 목표

- 공변성·반공변성·이변성·불변성 네 가지 변성 규칙을 함수 타입 할당 가능성으로 구분한다.
- `strictFunctionTypes` 플래그가 함수 파라미터 검사를 이변성에서 반공변으로 전환하는 지점을 파악한다.
- 메서드 문법과 프로퍼티 문법이 동일 시그니처에서 다른 변성 검사를 받는 이유를 설명한다.
- 명시적 변성 애노테이션(`in` / `out`)을 제네릭 타입 파라미터에 적용해 검사 방향을 제어한다.

## 1. 변성이란 무엇인가 — 서브타입 관계의 전파 방향

변성(variance)은 "타입 생성자가 타입 인자의 서브타입 관계를 어떻게 전파하는가"에 대한 규칙이다. `Dog`가 `Animal`의 서브타입일 때, `Array<Dog>`는 `Array<Animal>`의 서브타입인가? `(x: Dog) => void`는 `(x: Animal) => void`의 서브타입인가? 이 질문의 답이 변성이다.

네 가지 경우가 있다. **공변(covariant)**은 서브타입 관계가 그대로 유지된다 — `Dog <: Animal`이면 `F<Dog> <: F<Animal>`. **반공변(contravariant)**은 관계가 뒤집힌다 — `Dog <: Animal`이면 `F<Animal> <: F<Dog>`. **이변(bivariant)**은 양방향 모두 허용, **불변(invariant)**은 양방향 모두 거부다. 이 구분은 추상적으로 보이지만 함수 타입 할당에서 매일 마주치는 실전 규칙이다.

건전한(sound) 타입 시스템에서 함수 반환 타입은 공변, 파라미터 타입은 반공변이어야 한다. 반환은 "더 구체적인 것을 돌려주면 안전"하고, 파라미터는 "더 넓은 것을 받을 수 있으면 안전"하기 때문이다. TypeScript는 역사적 이유로 이 규칙을 부분적으로만 강제한다.

## 2. 반환 타입 공변성 — 직관과 일치

함수 반환 타입은 공변이다. 이는 대부분의 개발자 직관과 맞는다. `() => Dog`는 `() => Animal`이 필요한 자리에 넣을 수 있다. Dog를 돌려주는 함수는 "Animal을 돌려준다"는 계약을 만족하기 때문이다.

```typescript
interface Animal { name: string; }
interface Dog extends Animal { bark(): void; }

type ProduceAnimal = () => Animal;
type ProduceDog = () => Dog;

let makeAnimal: ProduceAnimal;
const makeDog: ProduceDog = () => ({ name: 'Rex', bark() {} });

makeAnimal = makeDog;  // OK — 반환 타입 공변, Dog <: Animal
// makeDog = makeAnimal;  // Error — Animal 은 Dog 가 아닐 수 있음
```

이 방향은 `strict` 여부와 무관하게 항상 이렇게 검사된다. 반환 타입 공변성은 논쟁의 여지가 없는 건전한 규칙이다.

## 3. 파라미터 반공변성과 이변성의 역사

파라미터 타입이 흥미롭다. 건전성만 따지면 반공변이어야 한다. `(x: Animal) => void`를 `(x: Dog) => void` 자리에 넣는 것이 안전하다 — Animal을 다 처리할 수 있는 함수는 Dog도 당연히 처리한다. 반대 방향, `(x: Dog) => void`를 `(x: Animal) => void` 자리에 넣는 것은 위험하다 — Dog 전용 처리 함수에 Cat을 넘길 수 있기 때문이다.

그런데 TypeScript는 기본적으로 파라미터를 **이변**으로 검사했다. 즉 양방향 모두 허용했다. 이유는 배열 같은 흔한 패턴 때문이다. `Array<Dog>`를 `Array<Animal>`에 할당하고 싶은데, 배열의 `push` 메서드 파라미터가 반공변으로 엄격히 검사되면 이 할당이 거부된다. 실용성을 위해 TypeScript 초기 설계는 파라미터 이변성을 채택했고, 이것이 건전성 구멍이 되었다.

```typescript
// strictFunctionTypes 없이는 둘 다 허용 (이변성)
type Handler<T> = (x: T) => void;

let animalHandler: Handler<Animal> = (a) => console.log(a.name);
let dogHandler: Handler<Dog> = (d) => d.bark();

animalHandler = dogHandler;  // 이변성 하에서 허용 — 하지만 위험
// animalHandler 로 Cat 을 넘기면 dogHandler 가 d.bark() 호출 → 런타임 에러
```

## 4. strictFunctionTypes — 반공변 검사로의 전환

`strictFunctionTypes` 플래그(그리고 이를 포함하는 `strict`)는 이 구멍을 부분적으로 막는다. 이 플래그가 켜지면 **함수 타입으로 작성된** 파라미터가 반공변으로 검사된다. 위 예제의 `animalHandler = dogHandler`가 이제 컴파일 에러가 된다.

```typescript
// strictFunctionTypes: true
type Handler<T> = (x: T) => void;

let animalHandler: Handler<Animal>;
let dogHandler: Handler<Dog>;

// animalHandler = dogHandler;
//   Error: Type 'Handler<Dog>' is not assignable to 'Handler<Animal>'.
//   Types of parameters 'x' and 'x' are incompatible.
dogHandler = animalHandler;  // OK — Animal 핸들러는 Dog 도 처리 가능 (반공변)
```

핵심 제약은 "함수 타입 문법으로 작성된 경우에만" 반공변 검사가 적용된다는 것이다. 다음 절에서 볼 메서드 문법은 여전히 이변으로 남는다. 이 비대칭이 `strictFunctionTypes`를 이해하는 가장 헷갈리는 지점이다.

## 5. 메서드 문법 vs 프로퍼티 문법 — 의도적 비대칭

TypeScript는 같은 시그니처라도 **메서드 문법**(`method(x: T): void`)과 **프로퍼티 함수 문법**(`method: (x: T) => void`)을 다르게 검사한다. 메서드 문법은 이변, 프로퍼티 문법은 (strictFunctionTypes 하에서) 반공변이다.

```typescript
interface MethodStyle {
  handle(x: Dog): void;          // 메서드 문법 → 이변 유지
}
interface PropertyStyle {
  handle: (x: Dog) => void;      // 프로퍼티 문법 → 반공변 검사
}

declare let ms: MethodStyle;
declare let msAnimal: { handle(x: Animal): void };
ms = msAnimal;  // OK (메서드 이변)

declare let ps: PropertyStyle;
declare let psAnimal: { handle: (x: Animal) => void };
ps = psAnimal;  // OK — Animal 핸들러 → Dog 핸들러 (반공변, 안전)
```

왜 메서드는 이변으로 남겼는가? 바로 `Array<T>` 같은 내장 제네릭 때문이다. `Array.prototype.push`는 메서드 문법으로 정의돼 있어서 `Array<Dog>`를 `Array<Animal>`에 할당하는 흔한 코드가 계속 동작한다. 만약 메서드도 반공변으로 강제하면 대량의 기존 코드가 깨진다. TypeScript 팀은 "건전성을 완벽히 얻기보다 실용성과 절충"을 택했고, 그 절충의 표식이 이 문법별 비대칭이다. 실무 교훈은 명확하다 — 콜백을 안전하게 검사받고 싶으면 프로퍼티 함수 문법으로 선언하라.

## 6. 제네릭 인터페이스의 변성 측정

TypeScript는 구조적 타입 시스템이라 제네릭 타입의 변성을 **자동으로 측정**한다. 타입 파라미터가 어느 위치(반환 위치, 파라미터 위치)에 나타나는지 분석해 공변/반공변/불변을 추론한다. 반환 위치에만 나타나면 공변, 파라미터 위치에만 나타나면 반공변, 양쪽 다면 불변이다.

```typescript
interface Producer<T> { get(): T; }              // T 반환 위치 → 공변
interface Consumer<T> { set(value: T): void; }   // T 파라미터 위치 → 반공변
interface Box<T> { get(): T; set(v: T): void; }  // 양쪽 → 불변

declare let pDog: Producer<Dog>;
declare let pAnimal: Producer<Animal>;
pAnimal = pDog;  // OK (공변)

declare let cDog: Consumer<Dog>;
declare let cAnimal: Consumer<Animal>;
cDog = cAnimal;  // OK (반공변, strictFunctionTypes 하)
```

이 자동 측정은 재귀적 제네릭에서 비용이 크다. 그래서 TypeScript 3.7 이후 순환 참조가 있는 제네릭은 변성 측정을 포기하고 구조적 비교로 폴백하기도 한다.

## 7. 명시적 변성 애노테이션 — in / out

TypeScript 4.7부터 타입 파라미터에 `in`(반공변), `out`(공변), `in out`(불변) 애노테이션을 명시할 수 있다. 이는 두 목적을 가진다. 첫째, 컴파일러의 변성 측정을 건너뛰어 대형 제네릭의 타입 체크 성능을 개선한다. 둘째, 의도한 변성과 실제 사용이 어긋나면 컴파일 에러로 잡아준다 — 일종의 변성 단위 테스트다.

```typescript
interface Producer<out T> { get(): T; }
interface Consumer<in T> { set(value: T): void; }
interface Invariant<in out T> { get(): T; set(v: T): void; }

// 애노테이션과 실제 위치가 어긋나면 에러
interface Wrong<out T> {
  set(value: T): void;   // Error: T is declared covariant (out) but used contravariantly
}
```

`out T`로 선언했는데 `T`를 파라미터 위치에 쓰면 컴파일러가 "공변으로 선언했는데 반공변으로 쓴다"고 지적한다. 대규모 라이브러리에서 이 애노테이션은 성능과 정확성을 동시에 잡는 도구다. 다만 애노테이션은 컴파일러 측정을 **덮어쓰지 않고 검증만** 한다 — 잘못 붙이면 에러이지 강제로 변성을 바꾸는 것이 아니다.

## 8. 실무 판단 — 변성 지식을 언제 쓰나

첫째, 이벤트 핸들러·콜백 API를 설계할 때. React의 `onChange` 같은 콜백 타입을 프로퍼티 함수로 선언하면 반공변 검사를 받아 잘못된 핸들러 할당을 컴파일 타임에 막을 수 있다. 둘째, 라이브러리에서 "읽기 전용 뷰"와 "쓰기 가능 뷰"를 분리할 때. `Producer<out T>`와 `Consumer<in T>`를 나누면 `ReadonlyArray`처럼 안전한 서브타입 관계를 제공할 수 있다.

trade-off는 실용성과 건전성 사이의 긴장이다. TypeScript는 `Array` 호환성을 위해 메서드 이변성을 남겨 건전성 구멍을 하나 유지한다. 실무에서는 이 구멍을 인지하고, 안전성이 중요한 콜백 경계에서는 프로퍼티 함수 문법과 명시적 변성 애노테이션으로 방어하는 것이 정석이다. 변성은 "언제 엄격하고 언제 느슨할지"를 선택하는 도구지 무조건 엄격해야 하는 규칙이 아니다.

## 9. 실전 사례 — Promise·배열·React 이벤트의 변성

변성 지식이 실제로 드러나는 세 지점을 보자. 첫째 **Promise**다. `Promise<T>`는 `.then(onFulfilled: (value: T) => ...)`에서 T를 콜백 파라미터 위치에 두지만, 결과적으로 T는 공변으로 취급된다. `Promise<Dog>`를 `Promise<Animal>`에 할당할 수 있어 대부분의 직관과 맞는다. 이는 Promise가 값을 "생산"하는 컨테이너로 설계됐기 때문이다.

```typescript
const dogPromise: Promise<Dog> = Promise.resolve({ name: 'Rex', bark() {} });
const animalPromise: Promise<Animal> = dogPromise;  // OK — 공변
```

둘째 **배열의 불건전성**이다. `Array<T>`는 읽기(공변)와 쓰기(반공변)를 모두 하므로 이론상 불변이어야 하지만, TypeScript는 `push`를 메서드 문법으로 두어 이변을 허용한다. 그 결과 `Dog[]`를 `Animal[]`에 할당한 뒤 Cat을 push 하는 불건전한 코드가 컴파일된다. 이 구멍을 막으려면 `ReadonlyArray<T>`를 쓴다 — 쓰기 메서드가 없어 순수 공변이 되고 안전하다.

```typescript
const dogs: Dog[] = [{ name: 'Rex', bark() {} }];
const animals: Animal[] = dogs;   // 허용되지만 불건전
animals.push({ name: 'Whiskers' });  // dogs 에 bark 없는 객체가 들어감!

const safeDogs: readonly Dog[] = dogs;
const safeAnimals: readonly Animal[] = safeDogs;  // 공변, 안전 (push 불가)
```

셋째 **React 이벤트 핸들러**다. React 타입 정의는 이벤트 핸들러를 프로퍼티 함수 문법으로 선언하므로 `strictFunctionTypes` 하에서 반공변 검사를 받는다. 더 구체적인 이벤트만 받는 핸들러를 더 일반적인 핸들러 자리에 넣으면 타입 에러가 나, 잘못된 핸들러 결합을 컴파일 타임에 막는다. 이처럼 변성은 "왜 이 할당이 되고 저 할당은 안 되는가"를 설명하는 근본 원리이며, 안전한 API 경계 설계(읽기 전용 뷰, 프로퍼티 콜백)의 이론적 토대다. 컴파일러의 변성 판단을 이해하면 낯선 타입 에러를 추측이 아니라 규칙으로 진단할 수 있다.

## 참고

- TypeScript Handbook — Type Compatibility, Variance Annotations
- TypeScript 2.6 Release Notes — `strictFunctionTypes`
- TypeScript 4.7 Release Notes — Optional Variance Annotations for Type Parameters
- Anders Hejlsberg, "Method vs Property function types" 설계 논의 (microsoft/TypeScript 이슈 트래커)
