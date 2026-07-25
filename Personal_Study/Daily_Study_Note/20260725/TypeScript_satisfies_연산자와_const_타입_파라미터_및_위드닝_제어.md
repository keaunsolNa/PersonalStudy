Notion 원본: https://app.notion.com/p/3a85a06fd6d38118bdc4f4efaad63673

# TypeScript satisfies 연산자와 const 타입 파라미터 및 위드닝 제어

> 2026-07-25 신규 주제 · 확장 대상: TypeScript(타입 시스템·타입 추론)

## 학습 목표

- `satisfies` 연산자가 타입 어노테이션 및 `as` 단언과 어떻게 다른 검사·추론 결과를 내는지 구분한다.
- 리터럴 위드닝(widening)이 발생하는 지점과 `as const` / `const` 타입 파라미터로 이를 억제하는 방법을 정리한다.
- TypeScript 5.0 `const` 타입 파라미터가 제네릭 함수의 추론 위드닝을 어떻게 막는지 파악한다.
- `satisfies` + `const` 조합으로 설정 객체의 안전성과 좁은 추론을 동시에 확보하는 패턴을 적용한다.

## 1. 문제의 출발점 — 검사와 추론은 상충한다

TypeScript에서 값에 타입을 부여하는 방식은 세 가지다. 타입 어노테이션(`const x: T = ...`), 타입 단언(`... as T`), 그리고 값 그대로 두기(추론에 맡김). 각각은 "검사"와 "추론"이라는 두 목표에서 다르게 동작하는데, 이 둘은 근본적으로 상충한다.

타입 어노테이션은 값이 `T`에 할당 가능한지 검사하지만, 그 대가로 변수의 정적 타입을 `T`로 **위드닝**한다. 즉 값이 실제로는 더 구체적이어도 컴파일러는 `T`까지만 안다. 반대로 어노테이션을 생략하면 추론은 좁게 유지되지만, 값이 어떤 상위 계약을 만족하는지 검사할 방법이 없다. `satisfies`는 정확히 이 간극 — "계약 검사는 하되 좁은 추론은 유지" — 를 메우기 위해 TypeScript 4.9에서 도입되었다.

```typescript
type Colors = Record<string, [number, number, number] | string>;

// 어노테이션: 검사는 되지만 추론이 Colors 로 위드닝
const paletteA: Colors = {
  red: [255, 0, 0],
  green: '#00ff00',
};
// paletteA.red 의 타입은 [number,number,number] | string — 튜플 접근 불가

// satisfies: 검사는 Colors 로 하되 추론은 리터럴 그대로
const paletteB = {
  red: [255, 0, 0],
  green: '#00ff00',
} satisfies Colors;

paletteB.red[0].toFixed();   // OK — red 는 number[] 로 좁게 추론됨
paletteB.green.toUpperCase(); // OK — green 은 string 으로 좁게 추론됨
```

`paletteB`는 `Colors` 계약을 만족하는지 검사받았지만, 각 프로퍼티의 실제 타입은 리터럴 수준으로 남는다. 어노테이션이었다면 `red`와 `green` 모두 `[number,number,number] | string` 유니온이 되어 개별 사용처에서 좁히기가 필요했을 것이다.

## 2. `satisfies` vs `as` — 방향이 반대다

`as` 단언은 컴파일러에게 "내가 옳으니 믿어라"라고 지시한다. 검사를 우회하므로 잘못된 단언이 런타임 버그로 이어진다. `satisfies`는 반대로 "내가 이 계약을 지키는지 **검사해라**, 단 추론은 바꾸지 마라"이다.

```typescript
type Route = { path: string; method: 'GET' | 'POST' };

const r1 = { path: '/users', method: 'PUT' } as Route;
// 통과함 — as 는 검사를 우회. 'PUT' 은 잘못된 값이지만 컴파일 통과

const r2 = { path: '/users', method: 'PUT' } satisfies Route;
// Error: Type '"PUT"' is not assignable to type '"GET" | "POST"'
```

`as`는 오탈자·구조 불일치를 잡지 못하지만 `satisfies`는 잡는다. 실무에서 라우팅 테이블, 환경 설정, 상태 머신 전이표처럼 "구조는 고정 계약을 따르되 값은 좁게 알고 싶은" 객체에 `satisfies`가 이상적이다. `as`는 타입 정보 손실이 불가피한 경계(JSON 파싱 결과, 외부 라이브러리 반환값)에만 제한적으로 사용한다.

## 3. 리터럴 위드닝의 메커니즘

`satisfies`를 이해하려면 위드닝을 먼저 알아야 한다. 위드닝은 리터럴 타입을 그 기본 타입으로 넓히는 추론 규칙이다. `let x = 'hello'`에서 `x`는 `string`으로 추론되는데, `let`은 재할당 가능하므로 리터럴 `'hello'`로 고정할 수 없기 때문이다. `const x = 'hello'`면 재할당이 불가능하므로 `'hello'` 리터럴로 유지된다.

문제는 객체 프로퍼티다. `const`로 선언한 객체라도 프로퍼티는 가변이므로 내부 리터럴은 위드닝된다.

```typescript
const config = {
  env: 'production',   // 추론: string (위드닝됨)
  retries: 3,          // 추론: number
};
// config.env 의 타입은 string — 'production' 리터럴이 아님

type Env = 'production' | 'staging' | 'dev';
function connect(env: Env) {}
connect(config.env);  // Error: string 은 Env 에 할당 불가
```

`config.env`가 `string`으로 위드닝됐기 때문에 `Env` 파라미터에 넘길 수 없다. 해결책은 위드닝을 억제하는 것이다.

## 4. `as const` — 가장 강한 억제

`as const`는 객체 전체를 깊게 `readonly`로 만들고 모든 리터럴 위드닝을 막는다.

```typescript
const config = {
  env: 'production',
  retries: 3,
  tags: ['a', 'b'],
} as const;
// config.env: 'production'  (리터럴 유지)
// config.retries: 3
// config.tags: readonly ['a', 'b']

connect(config.env);  // OK
// config.retries = 5;  // Error: readonly
```

`as const`는 강력하지만 두 가지 부작용이 있다. 첫째, 모든 것이 `readonly`가 되어 이후 변경이 필요한 경우 부적합하다. 둘째, **계약 검사를 하지 않는다**. 오탈자가 있어도 그냥 그 오탈자 리터럴 타입으로 굳어질 뿐이다. 그래서 `as const`와 `satisfies`를 결합하는 패턴이 자주 쓰인다.

```typescript
const config = {
  env: 'production',
  retries: 3,
} as const satisfies { env: Env; retries: number };
// as const 로 리터럴 고정 + satisfies 로 계약 검사
// env 에 오탈자('prod')를 쓰면 satisfies 가 잡아냄
```

순서가 중요하다. `as const`가 먼저 적용돼 리터럴을 고정한 뒤, `satisfies`가 그 고정된 타입이 계약을 만족하는지 검사한다.

## 5. TypeScript 5.0 `const` 타입 파라미터

객체 리터럴은 `as const`로 위드닝을 막을 수 있지만, 값을 **제네릭 함수에 전달**하면 다시 위드닝된다. 호출 지점에서 매번 `as const`를 붙이는 것은 번거롭다. TypeScript 5.0의 `const` 타입 파라미터는 이 위드닝을 라이브러리 쪽에서 한 번에 억제한다.

```typescript
// const 없이 — 인자가 위드닝됨
function makeRouterOld<T>(routes: T): T { return routes; }
const r = makeRouterOld(['/a', '/b']);
// r: string[]  — 위드닝됨

// const 타입 파라미터 — 호출자가 as const 없이도 리터럴 유지
function makeRouter<const T>(routes: T): T { return routes; }
const r2 = makeRouter(['/a', '/b']);
// r2: readonly ['/a', '/b']  — 좁게 추론됨
```

`<const T>`는 "이 타입 파라미터로 추론할 때 인자를 `as const`로 취급하라"는 의미다. 라이브러리 저자가 이를 선언하면 사용자는 매 호출마다 `as const`를 반복하지 않아도 정확한 리터럴 타입을 얻는다. 상태 머신 정의, 라우터 빌더, 폼 스키마 DSL 같은 API에서 개발자 경험을 크게 개선한다.

주의할 점은 `const` 타입 파라미터가 **위드닝만 억제할 뿐 `readonly`를 강제하지는 않는다**는 것이다. 파라미터 타입에 `readonly` 제약이 없으면 가변 배열도 받을 수 있고, 추론된 타입만 좁아진다. 또한 이미 위드닝된 값(예: `string`으로 선언된 변수)을 전달하면 `const` 파라미터도 그 이상 좁힐 수 없다 — 좁은 추론은 리터럴 소스가 있어야 가능하다.

## 6. 실전 조합 트레이드오프

세 도구의 선택 기준을 표로 정리한다.

| 도구 | 계약 검사 | 좁은 추론 | readonly 강제 | 주 용도 |
|---|---|---|---|---|
| `: T` 어노테이션 | O | X (T로 위드닝) | X | 변수 타입을 T로 고정하고 싶을 때 |
| `as T` | X (우회) | 부분 | X | 타입 정보 소실 경계 |
| `as const` | X | O (완전) | O | 불변 상수 테이블 |
| `satisfies T` | O | O | X | 계약 준수하는 설정 객체 |
| `as const satisfies T` | O | O | O | 불변 + 계약 검증 상수 |
| `<const T>` 파라미터 | 파라미터 제약만 | O | X | 제네릭 API 저자 |

실무 원칙은 다음과 같다. 설정·상수 객체의 프로퍼티를 개별적으로 좁게 쓰고 싶으면서 전체 구조는 계약으로 강제하려면 `satisfies`가 첫 선택이다. 완전 불변까지 필요하면 `as const satisfies`를 쓴다. 라이브러리 API를 설계해 사용자가 별도 어노테이션 없이 좁은 타입을 얻게 하려면 `const` 타입 파라미터를 쓴다. 단순히 변수의 표면 타입을 계약으로 고정하고 내부 리터럴이 필요 없다면 여전히 일반 어노테이션이 가장 간단하다.

한 가지 흔한 함정: `satisfies`는 **표현식의 정적 타입을 바꾸지 않는다**. 즉 `const x = {...} satisfies T`에서 `x`의 타입은 리터럴 추론 결과이지 `T`가 아니다. 함수 파라미터 자리에서 `T`로서의 표면이 필요하면 별도 어노테이션이 필요하다. 반대로 이 성질 덕분에 `satisfies` 이후에도 프로퍼티별 좁은 타입을 유지할 수 있는 것이다.

## 7. 실전 패턴 — 설정 맵과 판별 유니온

`satisfies`가 가장 빛나는 실전 패턴은 "키는 좁게, 값은 계약대로"인 설정 맵이다. 예를 들어 이벤트 핸들러 레지스트리를 보자. 각 이벤트 이름은 리터럴로 유지해 타입 안전한 조회를 하고, 각 핸들러는 공통 시그니처 계약을 만족해야 한다.

```typescript
type Handler = (payload: unknown) => void;

const handlers = {
  click: (p: MouseEvent) => console.log(p.clientX),
  submit: (p: SubmitEvent) => console.log(p.submitter),
} satisfies Record<string, Handler>;

// 키가 좁게 유지되어 자동완성·오탈자 검출이 됨
type EventName = keyof typeof handlers;  // 'click' | 'submit'

function dispatch<K extends EventName>(name: K) {
  return handlers[name];  // 각 핸들러의 구체 타입이 보존됨
}
```

어노테이션 `const handlers: Record<string, Handler>`였다면 `keyof typeof handlers`가 `string`이 되어 `EventName` 유니온을 얻지 못했을 것이다. `satisfies`가 인덱스 시그니처 계약을 검사하면서도 실제 키 집합을 리터럴로 남겨준 덕분에 타입 안전한 디스패치가 가능하다.

또 하나 주의할 상호작용은 **enum과의 관계**다. TypeScript enum은 그 자체로 명목적(nominal) 타입을 만들고 위드닝 규칙이 리터럴과 다르다. 설정에서 enum 대신 `as const` 객체 + `satisfies`를 쓰면, 명목 타입의 경직성 없이 리터럴 유니온의 유연함과 계약 검사를 동시에 얻는다. 이것이 최근 커뮤니티에서 enum보다 `as const` 객체를 선호하는 이유 중 하나다.

```typescript
// enum 대신
const Direction = {
  Up: 'UP',
  Down: 'DOWN',
} as const satisfies Record<string, string>;

type Direction = (typeof Direction)[keyof typeof Direction];  // 'UP' | 'DOWN'
```

이 패턴은 `isolatedModules`·번들러 환경에서 enum이 유발하는 런타임 코드 생성 문제도 피한다. `as const` 객체는 순수 값이라 트리 셔이킹이 자연스럽고, 위드닝 제어와 계약 검사가 컴파일 타임에 완결된다. 정리하면 `satisfies`는 단독 기능이 아니라 `as const`, `const` 타입 파라미터, `keyof typeof`와 조합될 때 "타입 안전한 상수 기반 설계"라는 하나의 일관된 스타일을 완성한다.

마지막으로 `satisfies`의 한 가지 미묘한 동작을 짚어둔다. `satisfies`는 **excess property check**(초과 프로퍼티 검사)를 여전히 수행한다. 계약에 없는 프로퍼티를 객체 리터럴에 넣으면 어노테이션과 마찬가지로 에러가 난다. 그러나 이는 리터럴에 직접 적용할 때만이고, 이미 변수에 담긴 객체를 `satisfies`로 검사하면 초과 프로퍼티 검사가 완화된다 — 구조적 할당 가능성만 본다. 이 차이 때문에 오탈자 방어를 원한다면 객체 리터럴에 곴바로 `satisfies`를 붙이는 것이 가장 강한 검사를 받는다.

```typescript
type Opt = { timeout: number };
const a = { timeout: 5, retries: 3 } satisfies Opt;
// Error: 'retries' 는 Opt 에 없음 — 리터럴 직접 검사라 초과 검사 작동

const tmp = { timeout: 5, retries: 3 };
const b = tmp satisfies Opt;  // OK — 변수 경유라 초과 검사 완화, 구조만 확인
```

이 성질은 설정 객체를 인라인 리터럴로 정의하고 즉시 `satisfies`로 봉인하는 스타일을 권장하는 또 하나의 이유다. 중간 변수를 거치지 않을수록 컴파일러가 더 엄격하게 오류를 잡는다.

## 참고

- TypeScript 4.9 Release Notes — The satisfies Operator (https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html)
- TypeScript 5.0 Release Notes — const Type Parameters (https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html)
- TypeScript Handbook — Literal Types and Widening (https://www.typescriptlang.org/docs/handbook/2/everyday-types.html)
- TypeScript Deep Dive — Type Widening and Const (https://basarat.gitbook.io/typescript/)
