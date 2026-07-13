Notion 원본: https://app.notion.com/p/39c5a06fd6d3817985ecff0cc6eaa53d

# TypeScript Control Flow Analysis와 Narrowing 심화 및 타입 가드 한계

> 2026-07-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 제어 흐름 분석(CFA)이 분기·할당·반환을 따라 유니온 타입을 좁히는 내부 모델을 이해한다
- `typeof`/`instanceof`/`in`/판별 프로퍼티 가드가 CFA에 어떻게 반영되는지 분해한다
- 사용자 정의 타입 가드와 assertion function이 좁힘을 어디까지 전파하는지 확인한다
- CFA가 좁힘을 잃어버리는 지점(클로저, 재할당, 별칭)을 실제 코드로 재현하고 회피한다

## 1. CFA란: 위치마다 다른 타입

TypeScript의 변수 타입은 선언 타입 하나로 고정되지 않는다. 컴파일러는 각 지점마다 그 지점에서 변수가 가질 수 있는 타입을 별도로 계산한다. 이것이 제어 흐름 분석이다.

```typescript
function f(x: string | number) {
  if (typeof x === 'string') {
    return x.toUpperCase(); // x: string
  }
  return x.toFixed(2);      // x: number
}
```

같은 `x`가 세 위치에서 각각 다른 타입을 가진다. CFA는 유니온 멤버를 분기 조건으로 나누고 도달 불가능한 멤버를 제거하는 집합 연산으로 이해하면 된다.

## 2. CFA 그래프와 좁힘의 전파

조건 분기는 참/거짓 각각에서 서로 다른 좁힘을 만든다. 할당은 그 지점에서 변수 타입을 대입값 타입으로 재설정한다. 도달 불가능(`return`/`throw`) 경로는 이후 계산에서 제외된다.

```typescript
function g(x: string | number | null) {
  if (x == null) return;   // null과 undefined 제거
  if (typeof x === 'number') {
    x = String(x);         // 이 지점부터 x: string
  }
  return x.trim();         // 두 경로 모두 string으로 수렴
}
```

`x == null`은 `null`과 `undefined`를 함께 걸러내는 관용적 좁힘이다. 두 분기가 합류하는 지점에서 각 경로의 타입을 유니온으로 병합한 뒤 다시 좁힌다.

## 3. 내장 타입 가드의 종류

`typeof` 가드는 원시 타입 문자열을 검사한다. `typeof x === "object"`는 `null`도 포함하므로 주의한다. `instanceof`는 프로토타입 체인으로, `in`은 프로퍼티 존재로 좁힌다.

```typescript
type Circle = { kind: 'circle'; radius: number };
type Square = { kind: 'square'; side: number };
type Shape = Circle | Square;

function areaByKind(s: Shape) {
  switch (s.kind) {
    case 'circle': return Math.PI * s.radius ** 2;
    case 'square': return s.side ** 2;
  }
}
```

판별 유니온은 공통 리터럴 프로퍼티(`kind`)를 `===`로 비교해 멤버를 정확히 고른다. 프로퍼티 이름이 충돌해도 리터럴 값으로 유일하게 판별되므로 `in`보다 견고하다.

## 4. 사용자 정의 타입 가드

```typescript
function isUser(value: unknown): value is User {
  return typeof value === 'object' && value !== null
    && 'id' in value && 'email' in value;
}
```

주의할 점은 프레디킷이 거짓말을 해도 컴파일러가 믿는다는 것이다. 본문이 실제로 조건을 검증하는지 확인하지 않으므로, 선언과 본문이 어긋나면 런타임에 타입이 깨진다. 그래서 검증은 Zod 같은 스키마로 도출하는 편이 안전하다. TypeScript 5.5부터는 단순 boolean 반환 함수의 프레디킷을 자동 추론하여 `array.filter(x => x !== null)`이 non-null 배열로 좁혀진다.

## 5. Assertion Function: throw 기반 좁힘

```typescript
function assertIsString(x: unknown): asserts x is string {
  if (typeof x !== 'string') throw new TypeError('expected string');
}

function upper(input: unknown) {
  assertIsString(input);
  return input.toUpperCase(); // input: string (이후 계속 유지)
}
```

assertion function은 if 블록 없이 좁힘을 직선 코드로 흘려보낼 수 있어 방어적 코드가 간결해진다. `assert(x != null)` 이후 `x`가 non-null로 좁혀지는 것도 같은 원리다.

## 6. CFA가 좁힘을 잃는 지점

### 6.1 클로저 안에서의 재좁힘 소실

```typescript
function process(value: string | null) {
  if (value === null) return;
  const later = () => {
    value.trim(); // 에러: value는 다시 string | null
  };
}
```

콜백은 나중에 실행되므로 그 사이 재할당되어 `null`이 될 수 있다고 가정한다. `let` 변수는 클로저 경계에서 좁힘이 초기화된다. 해결책은 좁혀진 값을 `const`에 담는 것이다.

### 6.2 객체 프로퍼티 별칭

함수 호출 사이에 프로퍼티가 다른 코드에 의해 변경될 수 있으면 컴파일러는 보수적으로 동작한다. 프로퍼티 접근을 지역 변수로 뽑으면(`const c = box.content`) 좁힘이 확실히 고정된다.

### 6.3 exhaustiveness 검사

```typescript
function assertNever(x: never): never {
  throw new Error(`unexpected: ${JSON.stringify(x)}`);
}
```

`Shape`에 새 멤버가 추가되면 `default` 분기에서 `s`가 `never`가 아니게 되어 `assertNever(s)`가 컴파일 에러가 된다. `never` 좁힘을 컴파일 타임 안전망으로 쓰는 관용구다.

## 7. 좁힘과 루프

루프 안에서는 CFA가 반복을 고정점까지 계산한다. 루프 본문의 좁힘은 다음 순회 진입에서 유지되지 않으므로, 좁혀진 타입에 의존하는 로직은 순회마다 다시 좁혀야 한다.

## 8. 실무 지침 정리

| 상황 | 좁힘 유지 | 대응 |
|---|---|---|
| if 분기 직선 코드 | 유지 | 그대로 사용 |
| let 변수 클로저 캡처 | 소실 | const 별칭으로 고정 |
| 객체 프로퍼티 + 함수 호출 사이 | 보수적 | 지역 변수로 추출 |
| 사용자 정의 가드 | 유지(검증 신뢰는 개발자 책임) | Zod 등으로 도출 |
| 루프 순회 경계 | 소실 | 순회마다 재좁힘 |

CFA는 값이 바뀌지 않는다는 보장이 있을 때만 좁힘을 유지한다. 좁힌 값은 즉시 `const`로 고정하고, 비동기 경계와 클로저를 넘길 때는 좁혀진 지역 변수를 넘겨라. `as`로 강제하기 전에 왜 컴파일러가 확신하지 못하는지를 먼저 확인하는 것이 안전하다.

## 참고

- TypeScript Handbook: Narrowing, Type Guards, Assertion Functions
- TypeScript 5.5 릴리스 노트: Inferred Type Predicates
- microsoft/TypeScript 소스: checker.ts의 getFlowTypeOfReference
- Effective TypeScript(Dan Vanderkam): 타입 좁히기 관련 아이템
