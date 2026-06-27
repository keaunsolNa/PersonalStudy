Notion 원본: https://app.notion.com/p/38c5a06fd6d3815f9633cb6b891a9f79

# TypeScript Variance(공변성·반공변성)와 strictFunctionTypes 및 메서드 양방향성

> 2026-06-27 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- 공변성(covariance), 반공변성(contravariance), 이변성(bivariance), 불변성(invariance)을 구조적 타입 관점에서 구분한다.
- 함수 매개변수가 왜 반공변 위치이고 반환값이 공변 위치인지 할당 가능성 규칙으로 설명한다.
- `strictFunctionTypes` 옵션이 함수 타입과 메서드 타입에 서로 다르게 적용되는 이유를 안다.
- 배열·Promise·읽기 전용 컬렉션에서 변성이 실무 버그로 이어지는 지점을 코드로 재현하고 회피한다.

## 1. 변성이란 무엇인가

변성(variance)은 타입 `A`가 `B`의 서브타입일 때 `F<A>`와 `F<B>` 사이의 관계가 어떻게 결정되는가를 다루는 규칙이다. TypeScript는 구조적 타입 시스템이므로 변성은 멤버의 구조로부터 자동 추론된다. `Dog`는 `Animal`의 서브타입이다(더 구체적인 것을 덜 구체적인 자리에 넣는 것은 안전).

## 2. 반환 위치는 공변, 매개변수 위치는 반공변

반환값은 호출자가 받아 쓰므로 더 구체적인 타입을 반환해도 안전하다(공변). 매개변수는 더 넓은 타입을 받을수록 안전하다(반공변).

```typescript
type Fn<P, R> = (arg: P) => R;
declare let takeAnimal: Fn<Animal, void>;
declare let takeDog: Fn<Dog, void>;
takeDog = takeAnimal; // OK: Animal 을 받는 함수는 Dog 도 처리 가능 (반공변)
// takeAnimal = takeDog; // strictFunctionTypes 켜면 Error
```

## 3. strictFunctionTypes — 함수는 엄격, 메서드는 이변

메서드 문법으로 선언된 매개변수는 여전히 이변(bivariant)으로 검사된다. `foo(x: T): void`(메서드 단축)와 `foo: (x: T) => void`(프로퍼티 함수)는 변성이 다르다. 이 비대칭은 `Array<Dog>`를 `Array<Animal>`에 할당하는 흔한 패턴을 살리기 위한 의도된 설계다.

## 4. 배열은 공변 — 가장 흔한 불건전 지점

```typescript
const dogs: Dog[] = [{ name: "a", bark() {} }];
const animals: Animal[] = dogs; // OK: 공변
animals.push({ name: "cat" }); // 컴파일 통과!
dogs[1].bark(); // 런타임 TypeError
```

변경이 불필요한 곳에서 `readonly T[]`/`ReadonlyArray<T>`를 쓰면 `push`가 없어 공변이 안전해진다.

## 5. 제네릭 클래스의 변성과 in/out 애너테이션

TypeScript 4.7부터 `in`(반공변), `out`(공변), `in out`(불변) 애너테이션으로 추론된 변성을 검증한다. 공개 API 문서화와 리팩터링 안전성을 제공한다.

## 6. Promise·함수 합성에서의 변성 실측

| 위치 | 변성 | 안전 방향 |
| --- | --- | --- |
| 함수 반환값 | 공변 | 구체화 가능 |
| 함수 프로퍼티 매개변수 | 반공변 | 일반화 가능 |
| 메서드 매개변수 | 이변 | 양방향 허용 |
| T[] 원소 | 공변(불건전) | 읽기만 안전 |
| readonly T[] 원소 | 공변(건전) | 안전 |

## 7. 실무 가이드

콜백은 함수 프로퍼티 형태로 선언해 반공변 검사를 받고, 컬렉션 노출 시 변경 의도가 없으면 `readonly`로 공변 불건전성을 차단한다. 라이브러리는 공개 제네릭에 `in`/`out`을 명시해 변성을 계약으로 고정한다.

## 8. 함수 오버로드·교차 타입에서의 변성 상호작용

교차 타입 `A & B`로 합성된 콜백은 두 호출 형태를 모두 받아들여야 하므로 매개변수가 더 넓어지는 방향으로 검사된다.

```typescript
type Handler = ((e: MouseEvent) => void) & ((e: KeyboardEvent) => void);
declare let h: Handler;
h = (e: MouseEvent | KeyboardEvent) => {}; // OK: 더 넓은 매개변수(반공변)
```

## 9. 제네릭 제약과 추론에서 변성이 드러나는 순간

같은 타입 변수가 공변 위치(반환)와 반공변 위치(매개변수)에 동시에 등장하면 불변으로 취급되어 "정확히 일치"를 요구한다. `const` 타입 매개변수(`<const T>`)로 리터럴 보존을 제어한다. 변성은 할당 가능성뿐 아니라 추론의 방향까지 지배한다.

## 참고

- TypeScript Handbook — Type Compatibility, Variance on Type Parameters
- TypeScript 4.7 Release Notes — Optional Variance Annotations
- TypeScript Wiki — FAQ: "Why are function parameters bivariant?"
