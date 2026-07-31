Notion 원본: https://www.notion.so/3ae5a06fd6d38174a746f98109619e20

# TypeScript enum과 const enum 및 isolatedModules 제약과 유니온 리터럴

> 2026-07-31 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 숫자 enum 과 문자열 enum 의 컴파일 출력과 역매핑 차이를 구분한다
- const enum 의 인라인 방식과 isolatedModules 하에서의 제약을 이해한다
- enum 과 유니온 리터럴 타입의 타입 안전성·번들 크기 trade-off 를 판단한다
- as const 객체로 enum 을 대체하는 실무 패턴을 코드로 검증한다

## 1. enum 이 컴파일되면 무엇이 남는가

TypeScript enum 은 타입인 동시에 **런타임 값**을 만든다. 이 점이 다른 타입 문법과 근본적으로 다르다. `interface` 나 `type` 은 컴파일 후 완전히 사라지지만, enum 은 JavaScript 객체(IIFE)를 남긴다. 숫자 enum 은 특히 **양방향 역매핑(reverse mapping)** 을 만든다.

```typescript
enum Direction { Up, Down, Left, Right }
```

이것은 대략 다음으로 컴파일된다.

```javascript
var Direction;
(function (Direction) {
  Direction[Direction["Up"] = 0] = "Up";
  Direction[Direction["Down"] = 1] = "Down";
  Direction[Direction["Left"] = 2] = "Left";
  Direction[Direction["Right"] = 3] = "Right";
})(Direction || (Direction = {}));
// 결과: { 0:'Up', 1:'Down', ..., Up:0, Down:1, ... }
```

`Direction.Up === 0` 도 되고 `Direction[0] === 'Up'` 도 된다. 이 역매핑은 편리해 보이지만 두 가지 부작용이 있다. 첫째, 객체에 이름→값과 값→이름 항목이 모두 들어가 **크기가 두 배**다. 둘째, `Object.keys(Direction)` 가 숫자 키까지 포함해 순회 시 예상과 다르게 동작한다. 반면 **문자열 enum 은 역매핑을 만들지 않는다**. 문자열 값으로부터 이름을 되찾을 방법이 없기 때문이다.

```typescript
enum Status { Active = 'ACTIVE', Closed = 'CLOSED' }
// 컴파일 결과: { Active:'ACTIVE', Closed:'CLOSED' } — 단방향
```

이 차이 때문에 실무에서는 값이 로그·API 에 그대로 노출되어 디버깅이 쉬운 **문자열 enum** 을 선호하는 경우가 많다.

## 2. const enum — 완전 인라인과 그 대가

`const enum` 은 런타임 객체를 아예 만들지 않고, 사용처를 **리터럴 값으로 인라인**한다. 번들 크기를 줄이는 최적화다.

```typescript
const enum Color { Red, Green, Blue }
const c = Color.Green;
```

컴파일 결과는 객체 생성 없이 이렇게 된다.

```javascript
const c = 1 /* Color.Green */;   // Color 객체는 산출물에 없음
```

이점은 명확하다. 열거 상수만 쓰고 객체 자체를 순회하지 않는다면, 런타임 오버헤드가 0 이 된다. 그러나 대가가 크다. 인라인은 **컴파일러가 enum 정의를 볼 수 있어야** 가능하다. 즉 값을 사용하는 파일이 정의를 실제로 타입 검사하며 컴파일해야 한다. 이 전제가 다음 절의 `isolatedModules` 와 정면으로 충돌한다. 또한 라이브러리가 `const enum` 을 공개 API 로 내보내면, 소비자 빌드 환경에 따라 인라인이 안 되어 "정의되지 않은 참조" 런타임 오류가 날 수 있다. 그래서 **라이브러리의 공개 API 로 const enum 을 쓰지 말라**는 것이 정설이다.

## 3. isolatedModules 가 const enum 을 막는 이유

Babel·esbuild·SWC 같은 **단일 파일 트랜스파일러**는 파일을 하나씩 독립적으로 변환한다. 다른 파일의 타입 정보를 참조하지 않는다(타입 검사는 별도 tsc 에 위임). `const enum` 인라인은 정의 파일의 내용을 알아야 하므로, 파일 단위 변환기는 이를 수행할 수 없다. `isolatedModules: true` 는 "각 파일이 독립적으로 안전하게 변환 가능한가"를 tsc 가 미리 검사하게 하는 옵션이며, 위반 패턴을 컴파일 오류로 만든다.

```typescript
// isolatedModules: true 인 프로젝트에서
export const enum E { A, B }
// ✗ error TS1206 계열: 단일 파일 변환기가 처리할 수 없는 const enum

// 타입만 재수출할 때도 명시가 필요
import { SomeType } from './types';
export { SomeType };
// ✗ 값인지 타입인지 파일 단독으로 알 수 없어 오류
// ✓ 해결: export type { SomeType } 로 타입 전용 재수출 명시
```

`isolatedModules` 가 강제하는 규칙은 세 가지로 요약된다. `const enum` 을 일반 enum 으로 바꾸거나 대체 패턴을 쓸 것, 타입 전용 import/export 는 `import type` / `export type` 로 명시할 것, 값과 타입을 섞어 재수출하지 말 것. 현대 툴체인(Vite, Next.js SWC 등)은 대부분 파일 단위 변환기를 쓰므로 `isolatedModules` 를 켜는 것이 사실상 표준이다. 참고로 `--isolatedDeclarations` 는 여기서 한발 더 나아가 선언 파일(.d.ts) 생성까지 파일 단독으로 가능하도록 요구하는 별개의 최신 옵션이다.

## 4. 유니온 리터럴 타입이라는 대안

enum 이 남기는 런타임 객체와 인라인 이슈를 통째로 피하는 방법은 **유니온 리터럴 타입**이다. 순수 타입이므로 컴파일 후 아무것도 남지 않는다.

```typescript
type Status = 'ACTIVE' | 'CLOSED' | 'PENDING';

function transition(s: Status) {
  switch (s) {
    case 'ACTIVE': return 1;
    case 'CLOSED': return 2;
    case 'PENDING': return 3;
    default: {
      const _exhaustive: never = s;   // 누락된 케이스가 있으면 컴파일 오류
      return _exhaustive;
    }
  }
}
```

`never` 를 이용한 **완전성 검사(exhaustiveness check)** 는 enum switch 에서도 되지만, 유니온 리터럴에서 특히 자연스럽다. 새 상태를 유니온에 추가하면 `default` 의 `never` 할당이 깨져 처리 누락 지점을 컴파일러가 전부 지목한다. 유니온 리터럴의 장점은 런타임 비용 0, import 없이 문자열 그대로 사용, 트리 쉐이킹 친화다. 단점은 "모든 값을 런타임에 순회"하는 기능이 없다는 것이다. enum 은 객체라 `Object.values(Status)` 로 전체 목록을 얻지만, 순수 타입은 런타임에 존재하지 않아 그럴 수 없다.

## 5. as const 객체 — 두 세계의 절충

값 목록을 런타임에도 순회하고 싶고 타입 안전성도 원한다면, `as const` 객체가 enum 을 대체하는 실무 정석이다.

```typescript
const Status = {
  Active: 'ACTIVE',
  Closed: 'CLOSED',
  Pending: 'PENDING',
} as const;

// 값들의 유니온 타입을 파생
type Status = typeof Status[keyof typeof Status];
// => 'ACTIVE' | 'CLOSED' | 'PENDING'

function isTerminal(s: Status): boolean {
  return s === Status.Closed;             // ✓ 타입 안전
}

// 런타임 순회도 가능
Object.values(Status).forEach((v) => console.log(v));  // 일반 객체
```

`as const` 는 객체 속성을 리터럴 타입으로 좁히고 `readonly` 로 만든다. `typeof Status[keyof typeof Status]` 로 값들의 유니온을 뽑아내면, enum 처럼 이름 접근(`Status.Active`)과 타입 제약(`s: Status`)을 모두 얻으면서 **일반 JavaScript 객체**라 트랜스파일러 제약이 없다. 역매핑도 없어 크기가 작고, `Object.values` 순회도 된다. 이 패턴은 `isolatedModules` 환경에서 아무 문제가 없고 번들도 가볍다.

## 6. 선택 기준 정리

세 가지 선택지의 판단을 명확히 하면 다음과 같다. **순수 타입 제약만 필요하고 런타임 순회가 불필요**하면 유니온 리터럴 타입이 가장 가볍다(0 런타임 비용, 완전성 검사 우수). **값 목록을 런타임에 순회하거나 이름↔값 접근이 필요**하면 `as const` 객체가 enum 의 실용적 대체재다(트랜스파일러 무제약, 트리 쉐이킹 친화). **레거시 호환이나 팀 컴벤션상 enum 을 써야** 하면 숫자 enum 보다 **문자열 enum** 을 택해 역매핑 부작용과 로그 가독성 문제를 피한다.

`const enum` 은 성능 이점이 있으나 `isolatedModules`·라이브러리 배포와 충돌하므로, 애플리케이션 내부의 성능 크리티컬한 상수에 한정하고 공개 API 로는 내보내지 않는다. 핵심 통찰은 enum 이 "타입이면서 값"이라는 이중성을 갖기에 다른 타입 문법과 달리 **번들·트랜스파일 파이프라인에 흔적을 남긴다**는 점이다. 현대 프론트엔드 툴체인이 파일 단위 변환으로 이동하면서, 흔적을 남기지 않는 유니온 리터럴과 `as const` 가 새로운 기본값으로 자리 잡았다.

## 7. 이질적 enum과 계산된 멤버의 함정

enum 은 숫자와 문자열 멤버를 섞은 **이질적(heterogeneous) enum** 도 허용하지만, 거의 항상 나쁜 선택이다. 값 타입이 일관되지 않아 순회·직렬화가 예측 불가능해진다.

```typescript
enum Mixed { No = 0, Yes = 'YES' }   // 문법상 허용되나 지양
```

또한 멤버 값을 상수 표현식이 아니라 **계산된 값(computed member)** 으로 줄 수 있는데, 이 경우 제약이 생긴다. 계산된 멤버 뒤에 오는 멤버는 반드시 초기값을 명시해야 한다(자동 증가 불가). 그리고 `const enum` 은 계산된 멤버를 아예 허용하지 않는다. 인라인해야 하는데 런타임 계산 결과를 컴파일 타임에 알 수 없기 때문이다.

```typescript
enum FileAccess {
  Read = 1 << 1,          // 계산된 멤버 (비트 플래그)
  Write = 1 << 2,
  ReadWrite = Read | Write,
  None,                    // ✗ error: 계산된 멤버 뒤에는 초기값 필요
}
```

비트 플래그 용도로 enum 을 쓰는 것은 정당한 사례다. 그러나 이때도 `const enum` 대신 일반 enum 을 쓰거나, `as const` 객체 + 유니온으로 표현하는 편이 툴체인 제약에서 자유롭다.

## 8. enum 타입 좁히기와 런타임 검증

외부 입력(HTTP 파라미터, JSON)을 enum 타입으로 받을 때, 타입 단언(`as Status`)은 위험하다. 실제 값이 enum 에 없어도 컴파일러는 통과시키고 런타임에 잘못된 값이 흘러든다. 유효성 검사는 반드시 런타임에서 해야 한다.

```typescript
enum Status { Active = 'ACTIVE', Closed = 'CLOSED' }

function parseStatus(input: string): Status {
  // Object.values 로 실제 값 집합과 대조 (문자열 enum 이라 값만 나옴)
  if ((Object.values(Status) as string[]).includes(input)) {
    return input as Status;
  }
  throw new Error(`Invalid status: ${input}`);
}
```

`as const` 객체 + 유니온 조합에서는 런타임 검증이 더 깔끔하다.

```typescript
const Status = { Active: 'ACTIVE', Closed: 'CLOSED' } as const;
type Status = typeof Status[keyof typeof Status];

const values = Object.values(Status);        // ['ACTIVE', 'CLOSED']
function isStatus(x: unknown): x is Status {
  return typeof x === 'string' && (values as string[]).includes(x);
}
```

`isStatus` 는 타입 가드라 통과 후 `x` 가 `Status` 로 좁혀진다. Zod 같은 스키마 검증 라이브러리를 쓰면 `z.enum(['ACTIVE','CLOSED'])` 로 검증과 타입 파생을 한 번에 얻는다. 요점은 enum 이든 유니온이든 **경계(boundary)에서의 런타임 검증**은 타입 시스템이 대신해 주지 않는다는 것이다. 컴파일 타임 타입은 내부 코드의 일관성을 보장할 뿐, 외부에서 들어온 문자열이 실제로 그 집합에 속하는지는 런타임 코드로 확인해야 한다. 이 경계 검증을 빼먹는 것이 enum 관련 프로덕션 버그의 가장 흔한 원인이다.

## 참고

- TypeScript Handbook: Enums / const enums / Objects vs Enums
- TSConfig Reference: isolatedModules, isolatedDeclarations
- TypeScript Handbook: Everyday Types — Literal Types / Exhaustiveness
- TypeScript Deep Dive (Basarat) — Enum 및 const enum pitfalls
