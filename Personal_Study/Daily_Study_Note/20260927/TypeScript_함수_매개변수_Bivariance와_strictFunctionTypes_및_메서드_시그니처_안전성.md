Notion 원본: https://www.notion.so/3e85a06fd6d381039d9ed7cc5dd3e8ed

# TypeScript 함수 매개변수 Bivariance와 strictFunctionTypes 및 메서드 시그니처 안전성

> 2026-09-27 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 공변/반공변/이변/불변을 함수 서브타이핑 규칙으로 설명하고 TypeScript 동작에 대입한다
- `strictFunctionTypes`가 프로퍼티와 메서드 시그니처에 다르게 적용되는 이유를 구조적 타입 이론으로 근거를 든다
- 배열 covariance와 콜백 이변이 결합해 런타임 오류로 이어지는 패턴을 재현하고 방어 코드를 작성한다
- `in`/`out` variance annotation과 `readonly`로 제네릭 타입의 variance를 명시적으로 선언한다

## 1. Variance 이론 기초: 서브타이핑과 함수 타입

"A가 B의 서브타입이다(A <: B)"라는 관계는 복합 타입(배열, 함수, 제네릭 컨테이너)을 구성했을 때 어떻게 전파되는지까지 정의해야 실질적 의미를 가진다. 이 전파 방식을 **variance(변성)**라 부르며 네 가지로 분류한다.

**공변(covariance)**은 구성 요소의 서브타입 방향이 그대로 유지되는 경우다. `Dog <: Animal`일 때 `Dog[] <: Animal[]`이면 배열은 공변이다. **반공변(contravariance)**은 방향이 뒤집히는 경우로, 더 넓은 입력을 받는 함수가 더 좁은 입력만 받는 함수 자리에 안전하게 대입되므로 함수 매개변수가 이론적으로 여기 해당한다. **이변(bivariance)**은 양방향 서브타입을 모두 인정하는, 안전성을 일부 포기한 완화 규칙이다. **불변(invariance)**은 완전히 동일한 타입만 인정하는 가장 보수적 규칙으로, `push`/`set`이 가능한 가변 컨테이너의 이론적 정답이다.

함수 서브타이핑의 표준 규칙(Liskov substitution principle의 적용)은, 함수 `F`가 `G`의 서브타입이려면 매개변수 타입이 같거나 **슈퍼타입**(반공변)이어야 하고 반환 타입은 같거나 **서브타입**(공변)이어야 한다는 것이다.

| Variance | 방향 규칙 | 대표 위치(이론) | TypeScript 실제 동작 |
| --- | --- | --- | --- |
| 공변 | A <: B ⟹ F\<A\> <: F\<B\> | 반환 타입, 배열 요소 | 배열은 항상 공변(알려진 구멍) |
| 반공변 | A <: B ⟹ F\<B\> <: F\<A\> | 함수 매개변수 | strictFunctionTypes ON + 함수 타입 위치에서만 강제 |
| 이변 | 양방향 모두 인정 | 이론상 드묾 | strictFunctionTypes OFF 기본값, 메서드는 항상 이 규칙 |
| 불변 | 완전히 같은 타입만 | 가변 컨테이너의 정답 | `in out` 명시 없으면 문맥에 따라 다름 |

핵심은 "이론적으로 옳은 반공변 대신 실무적으로 이변을 기본값으로 택했다"는 사실이며, 이는 버그의 근원이자 구조적 타이핑을 실용적으로 만드는 타협이다.

## 2. 함수 매개변수가 기본적으로 이변적으로 취급되는 이유

TypeScript는 명목적 타입 시스템이 아니라 구조적 타입 시스템을 채택했다. 어떤 타입이 다른 타입이 요구하는 속성/메서드를 "모양(shape)"으로 만족하면 서브타입으로 인정되며, 이 철학이 매개변수 variance 선택에 영향을 준다. 매개변수에 반공변을 엄격 적용하면 콜백을 받는 표준 라이브러리와 이벤트 핸들러 패턴 다수가 실무에서 타입 에러를 일으킨다.

```typescript
interface Event { timestamp: number; }
interface MouseEvent extends Event { x: number; y: number; }
// DOM/React 이벤트 설계에서 표준적으로 쓰이는 패턴
function addHandler(handler: (e: Event) => void) {}
function handleMouse(e: MouseEvent) { console.log(e.x, e.y); }
addHandler(handleMouse); // 반공변이면 에러여야 하나(매개변수가 더 좁음), 이변 덕분에 허용
```

매개변수 반공변을 엄격 적용했다면 개발자는 매번 핸들러 매개변수를 부모 타입으로 넓히거나 타입 단언(`as`)을 남발해야 했을 것이다. TypeScript 팀의 초기 설계 논의(Design and Evolution 문서)의 반복되는 논리는 "매개변수 반공변을 엄격히 강제하면 실무 편의성이 훼손된다"는 것이다. 그래서 함수 타입 위치의 매개변수 검사에서 기본적으로 **이변(bivariant)** 규칙을 채택했다 — `(e: MouseEvent) => void`와 `(e: Event) => void`가 서로 대입 가능한 것으로 취급된다. 문제는 핸들러가 이벤트별 고유 속성에 의존하면서 다른 서브타입 이벤트가 들어올 수 있는 다형적 콜백 상황에서만 드러난다.

## 3. strictFunctionTypes: 함수 타입 위치에서 반공변 강제

TypeScript 2.6에 도입된 `strictFunctionTypes`(`strict: true` 포함)는 이변 완화 규칙을 부분적으로 되돌린다. 옵션이 켜지면 **함수 타입 선언 위치**(변수, 프로퍼티, 화살표 함수)에서 매개변수가 이론대로 반공변으로 검사된다.

```json
// tsconfig.json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "strict": true,
    "strictFunctionTypes": true,
    "noImplicitAny": true
  }
}
```

```typescript
type Handler = (e: Event) => void;

function handleMouse(e: MouseEvent) {
  console.log(e.x, e.y); // 고유 속성 의존
}

// strictFunctionTypes: true 상태에서 아래는 컴파일 에러
const h: Handler = handleMouse;
// Property 'x' is missing in type 'Event' but required in type 'MouseEvent'.
```

`Handler` 타입 함수는 언젠가 `Event`(예: `KeyboardEvent`)로 호출될 수 있는데, `handleMouse`는 `x`, `y`가 없는 `Event`를 받으면 런타임에서 `undefined.x` 접근 오류를 낼 수 있다. `strictFunctionTypes`는 함수 타입이 대입될 때 매개변수가 대상보다 같거나 더 넓은 타입으로만 좁혀지도록 강제한다.

예외로 아래에서 다룰 **메서드 문법**은 검사 대상에서 제외되고, 반환 타입은 여전히 공변으로 검사된다. `strictFunctionTypes: false`에서는 위 코드가 에러 없이 컴파일되고 `true`로 바꾸는 순간에만 에러를 낸다 — 함수 타입 위치의 매개변수 검사에 국한된 스위치임을 보여준다.

## 4. 메서드 시그니처 vs 프로퍼티(화살표 함수) 시그니처

`strictFunctionTypes`를 처음 접할 때 가장 혼란스러운 지점은, 동일한 함수 멤버인데도 **메서드 문법**과 **프로퍼티(화살표 함수) 문법**의 검사 결과가 다르다는 사실이다.

```typescript
interface HandlerObjA { handle(e: Event): void; }        // (A) 메서드 시그니처
interface HandlerObjB { handle: (e: Event) => void; }    // (B) 프로퍼티 시그니처

class MouseHandler {
  handle(e: MouseEvent) { console.log(e.x); }
}

const a: HandlerObjA = new MouseHandler(); // OK — 이변 허용
const b: HandlerObjB = new MouseHandler(); // Error — 반공변 검사
```

`strictFunctionTypes: true`에서도 `(A)`는 통과하고 `(B)`만 에러가 난다: **메서드 문법은 항상 이변으로 검사되고, 프로퍼티/화살표 함수 시그니처만 반공변 검사를 받는다.** 근거는 두 가지다. **실용성** — 메서드 오버라이드 시 매개변수를 좁히는 패턴(`Animal.eat(food: Food)`을 `Dog.eat(food: DogFood)`로)이 흔해, 엄격한 반공변을 적용하면 기존 OOP 코드베이스 대부분이 컴파일에 실패한다. **배열 공변성과의 정합성** — 배열은(5절) 공변인데 요소의 메서드 매개변수까지 반공변으로 검사하면 공변 취급 자체가 무의미해질 만큼 다형적 사용이 막힌다는 절충이며, microsoft/TypeScript GitHub 이슈에도 이 결정이 명시되어 있다.

| 선언 형태 | 예시 | strictFunctionTypes | variance |
| --- | --- | --- | --- |
| 메서드 시그니처 | `handle(e: Event): void` | 받지 않음 | 항상 이변 |
| 프로퍼티(화살표 함수) | `handle: (e: Event) => void` | 받음 | 반공변 |
| 독립 함수 타입 변수 | `type F = (e: Event) => void` | 받음 | 반공변 |
| 생성자 타입 | `new (e: Event) => T` | 받음 | 반공변 |

실무 시사점: 콜백 저장 속성은 화살표 함수 타입으로 선언해야 `strictFunctionTypes`가 검증해 준다. 오버라이드 유연성을 원한다면 메서드 문법을 유지하되 위험을 인지해야 한다.

## 5. 실무 버그 재현: 배열 covariance와 매개변수 이변의 결합

배열의 covariant 취급은 TypeScript가 알려진 상태로 감수하는 타입 안전성 구멍이다. `Dog[]`은 `Animal[]`의 서브타입으로 취급되지만, 그 배열에 `Cat`을 `push`할 수 있어 이론적으로 불건전(unsound)하다.

```typescript
class Animal { name: string = "animal"; }
class Dog extends Animal { bark() { console.log("woof"); } }
class Cat extends Animal { meow() { console.log("meow"); } }

function addCat(animals: Animal[]) {
  animals.push(new Cat()); // Animal[] 관점에서는 완전히 유효한 호출
}

const dogs: Dog[] = [new Dog(), new Dog()];
addCat(dogs); // 컴파일 통과 — Dog[]는 Animal[]의 서브타입(공변)이므로 허용
dogs.forEach(d => d.bark()); // 런타임 TypeError — 세 번째 요소는 실제로 Cat 인스턴스
```

이 코드는 `tsc --strict`로도 경고 없이 컴파일되며 실행 시점에만 오류가 드러난다. 어떤 strict 옵션도 이 구멍을 막지 않는다 — JavaScript 배열의 런타임 동작과 구조적 타이핑의 편의성을 고려한 의도적 설계다(핸드북 "Type Compatibility" 챕터).

매개변수 이변이 여기 결합하면 더 미묘한 버그가 나타난다. `onShape: (s: Shape) => void` 프로퍼티에 `Circle` 고유 속성 `c.radius`에 의존하는 `processCircle`을 `strictFunctionTypes: false`에서 `as any`로 대입하면, 이후 `Square` 등 다른 서브타입이 전달될 때 결과가 `NaN`이 되는 조용한 버그로 이어진다. 이벤트 버스나 플러그인 시스템에서 자주 나타나며, 옵션을 켜두면 이런 대입이 차단된다.

## 6. 콜백/이벤트 핸들러 타입 설계의 실수와 안전한 패턴

가장 흔한 실수는 핸들러가 필요로 하는 구체 타입을 콜백 매개변수로 그대로 사용하는 것이다.

```typescript
type OnClick = (e: MouseEvent) => void;

class Button {
  private handlers: OnClick[] = [];
  onClick(handler: OnClick) { this.handlers.push(handler); } // TouchEvent로 확장되면 문제가 생긴다
}
```

콜백 타입을 `(e: MouseEvent | TouchEvent) => void`로 넓히면 기존 `OnClick` 핸들러가 `TouchEvent` 속성에 접근하지 못해 오류가 날 수 있다. `strictFunctionTypes`가 켜져 있으면 확장 시점에 에러로 알려주지만, 꺼져 있거나 메서드 문법이면 `TouchEvent`가 실제 전달되는 순간에만 드러난다.

안전한 설계 패턴은 콜백 시그니처를 항상 프로퍼티(화살표 함수) 문법으로 선언하고, 실제로 쓰는 최소한의 공통 인터페이스만 요구하는 것이다(인터페이스 분리 원칙과 유사).

```typescript
interface PointerLike { clientX: number; clientY: number; }
interface PointerHandlers { onPointerEvent: (e: PointerLike) => void; }

const safeHandler: PointerHandlers = {
  onPointerEvent: (e: PointerLike) => console.log(e.clientX, e.clientY),
};
// clientX/clientY만 있으면 어떤 이벤트든 구조적으로 호환 — strictFunctionTypes 통과
```

핸들러가 요구하는 타입이 콜백이 제공하는 타입과 일치하거나 더 넓기 때문에, 이 접근은 반공변 규칙과 마찰 없이 통과한다.

## 7. `readonly`와 `in`/`out` variance annotation

TypeScript 4.7부터 제네릭 타입 매개변수에 `in`(반공변), `out`(공변), `in out`(불변) 어노테이션을 붙여 개발자가 의도한 variance를 명시할 수 있다.

```typescript
interface Producer<out T> { produce(): T; }                 // out: 읽기 전용 — 공변
interface Consumer<in T> { consume(value: T): void; }        // in: 입력 전용 — 반공변
interface Box<in out T> { get(): T; set(value: T): void; }   // 양쪽 모두 — 불변
class Animal { name = "animal"; }
class Dog extends Animal { bark() {} }

declare const dogProducer: Producer<Dog>;
const animalProducer: Producer<Animal> = dogProducer; // OK — 공변 명시

declare const animalConsumer: Consumer<Animal>;
const dogConsumer: Consumer<Dog> = animalConsumer; // OK — 반공변 명시

declare const dogBox: Box<Dog>;
// const animalBox: Box<Animal> = dogBox; // Error — in out(불변)이므로 금지
```

`out T`로 선언한 `Producer<Dog>`를 `Producer<Animal>`에 대입할 수 있는 이유는 컴파일러가 "T는 읽기 전용 위치에서만 등장한다"는 계약을 신뢰하기 때문이다. 어노테이션 없이도 구조적 분석으로 대부분 같은 결론에 도달하지만, `.d.ts` 타입 검사 성능 개선, 재귀적 제네릭에서 추론이 지나치게 보수적일 때의 variance 강제, API 계약 문서화라는 가치가 있다.

`readonly` 배열/튜플도 variance와 밀접하다. `ReadonlyArray<T>`는 `push`, `splice` 등 쓰기 연산이 제거되어 있어, 일반 `Array<T>`보다 공변으로 취급해도 안전한 범위가 넓어진다.

```typescript
function printAll(items: ReadonlyArray<Animal>) {
  items.forEach(a => console.log(a.name)); // items.push(...)는 컴파일 에러 — 쓰기 메서드가 없음
}
printAll([new Dog()]); // 안전한 공변 — 내부 쓰기가 원천 차단되어 5절의 버그가 발생할 수 없다
```

5절 버그의 해법은 콜백/함수 매개변수를 `ReadonlyArray<T>`로 받아 공변 대입을 안전하게 봉인하는 것이다.

## 8. 대규모 코드베이스에서 strictFunctionTypes 마이그레이션 전략

`strictFunctionTypes: true`를 기존 코드베이스에 신규 적용하면 에러는 대체로 세 부류다: **진짜 버그**(콜백이 서브타입 속성에 의존), **거짓 양성**(표기가 우연히 좁은 안전한 패턴), **서드파티 타입(`@types/*`) 충돌**(라이브러리 선언이 이변 가정으로 작성됨).

마이그레이션은 `tsconfig.strict.json`으로 신규 디렉터리부터 점진 적용하고 나머지는 `// @ts-expect-error`로 억제해 부채 목록화하는 **파일 단위 점진 적용**, 콜백을 계층별로 몰아 6절의 `PointerLike`식 패턴을 반복 적용하는 **계층별 그룹화**, 프로퍼티 시그니처를 메서드로 바꿔 회피하는 코드를 리뷰에서 걸러내는 **"메서드 회피" 금지**의 세 원칙으로 진행하면 효과적이다.

| 에러 패턴 | 근본 원인 | 권장 조치 |
| --- | --- | --- |
| 이벤트 핸들러 콜백 불일치 | 핸들러가 서브타입 속성에 의존 | 좁은 인터페이스로 재설계(6절) |
| 배열 콜백 대량 에러 | 내부 콜백이 프로퍼티 시그니처 | `ReadonlyArray<T>`, 콜백 제네릭화 |
| 서드파티 타입 충돌 | `.d.ts`가 이변 가정으로 작성됨 | 라이브러리 업데이트, `declare module` 보강 |
| 메서드로 우회한 코드 | 이변 구멍을 이용한 회피 | 프로퍼티 시그니처로 되돌려 수정 |

`strictFunctionTypes` 마이그레이션은 단순 토글이 아니라 콜백 타입 설계를 재검토하는 계기다. 드러나는 에러의 상당수가 잠재된 런타임 버그였음이 실측으로 확인되며, 이것이 `strict: true`의 하위 항목으로 권장되는 이유다.

## 참고

- TypeScript Handbook — Type Compatibility
- TypeScript Handbook — TSConfig Reference: `strictFunctionTypes`
- TypeScript Handbook — Release Notes, TypeScript 2.6
- TypeScript Handbook — Release Notes, TypeScript 4.7
- microsoft/TypeScript GitHub Wiki — Design Goals
- microsoft/TypeScript GitHub Issues — strictFunctionTypes / 메서드 vs 프로퍼티 variance
- microsoft/TypeScript GitHub — Variance Annotations(`in`/`out`) RFC