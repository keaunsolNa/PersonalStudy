Notion 원본: https://www.notion.so/39b5a06fd6d38121b8d6e10e463ca778

# TypeScript Variance 어노테이션과 공변·반공변 및 strictFunctionTypes

> 2026-07-12 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 공변(covariance)·반공변(contravariance)·불변(invariance)·이변(bivariance)의 정의를 예로 구분한다.
- 함수 파라미터가 왜 반공변이어야 타입 안전한지, `strictFunctionTypes` 가 무엇을 바꾸는지 설명한다.
- 4.7 의 `in` / `out` variance 어노테이션이 언제 필요한지 파악한다.
- 메서드 vs 함수 프로퍼티의 이변 예외와 배열 공변의 함정을 실무 관점에서 다룬다.

## 1. Variance 의 정의

Variance 는 "타입 파라미터의 서브타입 관계가 그 파라미터를 감싼 제네릭 타입의 서브타입 관계로 어떻게 전파되는가"를 말한다. `Dog <: Animal`(Dog 는 Animal 의 서브타입)일 때 `F<Dog>` 와 `F<Animal>` 의 관계가 무엇이냐가 variance 다.

- **공변(covariant):** `F<Dog> <: F<Animal>`. 방향 유지. 읽기 전용 컨테이너(생산자).
- **반공변(contravariant):** `F<Animal> <: F<Dog>`. 방향 반전. 소비자(함수 파라미터).
- **불변(invariant):** 두 방향 모두 성립 안 함. 읽기·쓰기 모두 가능한 가변 컨테이너.
- **이변(bivariant):** 두 방향 모두 성립(안전하지 않음). TS 의 특정 위치에서만 허용되는 실용적 예외.

```ts
interface Animal { name: string; }
interface Dog extends Animal { bark(): void; }

// 공변: 반환 위치
type Producer<T> = () => T;
declare let pd: Producer<Dog>;
declare let pa: Producer<Animal>;
pa = pd; // OK: Producer<Dog> <: Producer<Animal> (Dog 반환은 Animal 반환으로 쓸 수 있음)
```

## 2. 왜 함수 파라미터는 반공변인가

핵심 직관: **반환값은 공변, 파라미터는 반공변**일 때 함수 치환이 안전하다. `Dog` 를 기대하는 자리에 `Animal` 전체를 처리할 수 있는 함수를 넣는 것은 안전하다(더 일반적인 소비자). 반대로 `Animal` 을 기대하는 자리에 `Dog` 만 처리하는 함수를 넣으면, `Cat` 이 들어왔을 때 깨진다.

```ts
type Handler<T> = (arg: T) => void;

declare let handleAnimal: Handler<Animal>; // 모든 Animal 처리 가능
declare let handleDog: Handler<Dog>;       // Dog 전용

// 반공변이 올바른 방향:
handleDog = handleAnimal; // OK: Animal 처리기는 Dog 자리에 안전
// handleAnimal = handleDog; // 위험: Dog 전용 처리기를 Animal 자리에 넣으면
                             //       Cat 이 와서 bark() 접근 시 런타임 붕괴
```

즉 `Handler<Animal> <: Handler<Dog>` 이어야 안전하다. 파라미터 타입의 서브타입 방향(`Dog <: Animal`)이 함수 타입에서 반전(`Handler<Animal> <: Handler<Dog>`)됐으므로 반공변이다.

## 3. strictFunctionTypes 가 바꾸는 것

역사적으로 TypeScript 는 함수 파라미터를 **이변(bivariant)** 으로 취급했다. 편의를 위해 안전하지 않은 방향의 대입도 허용했던 것이다. `strictFunctionTypes`(strict 모드에 포함, 2.6+)를 켜면 **함수 타입 표기**의 파라미터를 제대로 반공변으로 검사한다.

```ts
// strictFunctionTypes: true
type Handler<T> = (arg: T) => void;
declare let ha: Handler<Animal>;
declare let hd: Handler<Dog>;

ha = hd; // Error: Dog 전용 처리기를 Animal 자리에 대입 불가 (반공변 검사)
hd = ha; // OK
```

중요한 예외가 있다. `strictFunctionTypes` 는 **함수 프로퍼티 표기**(`type F = (x: T) => void`)에만 반공변을 적용하고, **메서드 표기**(`interface I { f(x: T): void }`)에는 여전히 이변을 허용한다. 이는 배열·Promise 등 표준 라이브러리가 메서드 문법으로 정의돼 있고, 이들에 엄격 반공변을 강제하면 실무 코드가 대량으로 깨지기 때문에 내린 의도적 절충이다.

```ts
interface Comparer<T> {
    compare(a: T, b: T): number; // 메서드 표기 -> 이변(bivariant)
}
interface Comparer2<T> {
    compare: (a: T, b: T) => number; // 프로퍼티 표기 -> 반공변
}
```

이 차이는 "왜 인터페이스 메서드로 쓰면 대입되는데 화살표 프로퍼티로 바꾸면 에러가 나는가"라는 흔한 혼란의 원인이다. 안전성이 중요한 콜백 계약은 프로퍼티 표기로 선언해 반공변 검사를 받는 것이 낫다.

## 4. 배열 공변의 함정

TypeScript 배열은 공변이다. `Dog[] <: Animal[]` 이 허용된다. 읽기만 하면 안전하지만, 배열은 쓰기가 가능하므로 이는 원칙적으로 불건전(unsound)하다. TS 는 실용성을 위해 이 구멍을 의도적으로 남겨다.

```ts
const dogs: Dog[] = [{ name: "Rex", bark() {} }];
const animals: Animal[] = dogs; // OK (공변)
animals.push({ name: "Whiskers" }); // 컴파일 통과 — 그러나 dogs 에 Cat 이 섞임!
dogs[1].bark(); // 런타임 에러: Animal 에는 bark 가 없음
```

방어책은 읽기 전용을 명시하는 것이다. `ReadonlyArray<T>` 는 쓰기 메서드가 없어 공변이 안전하고, 의도를 타입으로 강제한다. 함수 파라미터를 `readonly T[]` 로 받으면 호출자의 배열을 변경하지 않겠다는 계약이자 공변 안전성을 함께 얻는다.

```ts
function totalNames(items: readonly Animal[]): string {
    return items.map(i => i.name).join(", "); // 읽기만 — 안전
}
```

## 5. in / out variance 어노테이션 (4.7+)

TypeScript 4.7 은 제네릭 타입 파라미터에 `in`(반공변), `out`(공변), `in out`(불변) 어노테이션을 도입했다. 이는 컴파일러의 자동 variance 추론을 **명시·검증**하는 용도다. 대부분은 불필요하지만, 두 경우에 유용하다.

첫째, 재귀적이거나 복잡한 타입에서 컴파일러의 구조적 variance 계산이 비싸질 때, 어노테이션으로 계산을 단축해 성능을 개선한다. 둘째, 의도한 variance 를 문서화하고 위반을 막고 싶을 때다.

```ts
// out: T 는 공변이어야 함(생산 위치에만 등장)을 선언 + 검증
interface Emitter<out T> {
    subscribe(fn: (value: T) => void): void;
}

// in: T 는 반공변(소비 위치에만)
interface Sink<in T> {
    write(value: T): void;
}

// in out: 불변(읽기+쓰기)
interface Cell<in out T> {
    get(): T;
    set(value: T): void;
}
```

컴파일러는 어노테이션과 실제 사용이 어긋나면 에러를 낸다. 예컨대 `out T` 라 선언했는데 T 를 파라미터(소비) 위치에 쓰면 "선언한 공변과 실제 반공변이 충돌"한다고 알려 준다. 이 검증 기능이 어노테이션의 실질적 가치다.

## 6. 조건부 타입에서의 variance 와 infer

`infer` 위치의 variance 는 추론 결과에 영향을 준다. 공변 위치에서 여러 후보가 잡힐면 **유니온**으로, 반공변 위치(함수 파라미터)에서 여러 후보가 잡힐면 **교차(intersection)** 로 합쳐진다.

```ts
// 반공변 위치의 infer -> intersection
type Bad = { a: (x: { p: 1 }) => void; b: (x: { q: 2 }) => void };
type Param = Bad[keyof Bad] extends (x: infer P) => void ? P : never;
// P = { p: 1 } & { q: 2 }  (교차)

// 공변 위치의 infer -> union
type Ret = { a: () => 1; b: () => 2 }[keyof ...] // (반환 추론 시 1 | 2)
```

이 규칙은 `UnionToIntersection` 같은 타입 유틸리티의 동작 원리이기도 하다. 함수 파라미터의 반공변성이 유니온을 교차로 뒤집는 성질을 이용한다.

## 7. Promise·Map 등 표준 타입의 variance

표준 라이브러리 타입의 variance 를 파악하면 실무 오류를 예측할 수 있다. `Promise<T>` 는 T 가 값을 "생산"하는 위치(`then` 콜백의 인자)에 주로 등장하므로 사실상 공변으로 동작한다. `Promise<Dog>` 를 `Promise<Animal>` 자리에 쓰는 것은 안전하다.

```ts
declare const pd: Promise<Dog>;
const pa: Promise<Animal> = pd; // OK (공변적으로 동작)
```

`Map<K, V>` 는 키·값 모두 읽기와 쓰기가 있어 불변에 가깝다. `Map<string, Dog>` 를 `Map<string, Animal>` 로 대입하려 하면, 배열 공변 함정과 같은 이유로 위험하다(대입 후 Cat 을 넣으면 원본이 오염). TypeScript 는 구조적 타이핑으로 이를 부분적으로 잡지만, 메서드 표기의 이변 예외 때문에 완전하지는 않다. 안전을 원하면 `ReadonlyMap<K, V>` 를 소비 인터페이스로 노출한다.

```ts
function names(m: ReadonlyMap<string, Animal>): string[] {
    return [...m.values()].map(a => a.name); // 읽기 전용 — 안전하게 공변
}
```

`Record<K, V>` 도 값 위치가 읽기·쓰기 양쪽이므로 실무에서 불변으로 취급하는 것이 안전하다. 이처럼 "이 제네릭 파라미터가 생산 위치인가 소비 위치인가"를 먼저 묻는 습관이 variance 관련 대입 에러를 직관적으로 해석하게 해 준다.

## 8. 실무 지침

정리하면, 콜백·핸들러 타입은 안전한 반공변 검사를 받도록 화살표 프로퍼티 표기와 `strictFunctionTypes`(=strict 모드)를 켜다. 가변 배열을 함수에 넘길 때는 `readonly T[]` 로 받아 공변 구멍과 의도치 않은 변경을 동시에 막는다. `in`/`out` 어노테이션은 라이브러리 수준의 공개 API 나 컴파일 성능 이슈가 실제로 관측될 때만 도입하고, 일반 애플리케이션 코드에서는 컴파일러의 자동 추론에 맡기는 것이 간결하다. variance 를 이해하면 "왜 이 함수 대입이 되고 저건 안 되는가"라는 TS 의 가장 흔한 수수ꅹ가 일관된 규칙으로 풀린다.

## 참고

- TypeScript Handbook — Type Compatibility, Variance
- TypeScript 2.6 Release Notes (strictFunctionTypes)
- TypeScript 4.7 Release Notes (Optional Variance Annotations)
- "Programming TypeScript", Boris Cherny (O'Reilly)
