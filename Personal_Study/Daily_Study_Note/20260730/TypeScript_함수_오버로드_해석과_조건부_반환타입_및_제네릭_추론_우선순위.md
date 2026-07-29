Notion 원본: https://app.notion.com/p/3ac5a06fd6d381d7ac2bcb848eecce05

# TypeScript 함수 오버로드 해석과 조건부 반환타입 및 제네릭 추론 우선순위

> 2026-07-30 신규 주제 · 확장 대상: TypeScript(타입 시스템·컴파일러 추론 규칙 심화)

## 학습 목표

- 오버로드 시그니처가 위에서 아래로 순서대로 매칭되는 해석 규칙과 그 함정을 정리한다.
- 오버로드와 조건부 반환 타입 중 어느 쪽이 어떤 상황에 적합한지 구분한다.
- 제네릭 타입 인자 추론의 우선순위(추론 후보 수집·최적 공통 타입·문맥 타입)를 파악한다.
- `NoInfer`·기본 타입 인자·문맥적 추론을 활용해 원하는 추론 결과를 직접 유도해 검증한다.

## 1. 오버로드 — 하나의 구현, 여러 얼굴

함수 오버로드는 여러 개의 **시그니처 선언**과 하나의 **구현 시그니처**로 이뤄진다. 구현 시그니처는 호출자에게 보이지 않으며, 보이는 것은 오직 위에 나열한 오버로드 선언들뿐이다.

```ts
function parse(input: string): string[];
function parse(input: number): number;
function parse(input: boolean): boolean;
// 구현 시그니처 — 호출자에게 비공개, 모든 오버로드를 포괄해야 함
function parse(input: string | number | boolean): string[] | number | boolean {
  if (typeof input === "string") return input.split(",");
  if (typeof input === "number") return input * 2;
  return !input;
}

const a = parse("x,y");   // string[]
const b = parse(10);      // number
const c = parse(true);    // boolean
```

구현 시그니처의 반환 타입이 유니온(`string[] | number | boolean`)이어도, 호출자는 매칭된 오버로드의 구체 반환 타입만 본다. 이것이 오버로드의 핵심 가치다 — 입력에 따라 반환 타입을 정확히 좁혀준다.

## 2. 해석은 위에서 아래로, 첫 매칭이 이긴다

오버로드 해석은 선언 **순서대로** 진행되어 인자와 호환되는 **첫 시그니처** 를 택한다. 이 순서 의존성이 미묘한 버그의 원천이다. 더 넓은(일반적인) 시그니처를 위에 두면, 아래의 구체적 시그니처가 영영 선택되지 않는다.

```ts
// 잘못된 순서 — 넓은 것이 위
function pick(x: any): any;
function pick(x: string): string;   // 도달 불가 — any 가 먼저 매칭
// pick("hi") 의 결과 타입은 any (기대: string)

// 올바른 순서 — 좁은 것을 위로
function pick2(x: string): string;
function pick2(x: number): number;
function pick2(x: any): any;        // fallback 은 맨 아래
```

규칙: **구체적인 시그니처를 위에, 넓은 시그니처를 아래에** 둔다. 또한 선택적 매개변수나 유니온으로 표현 가능한 것을 굳이 오버로드로 나누지 않는다. `function f(x: string, y?: number)` 는 두 오버로드보다 명확하다. 오버로드는 "입력 타입과 반환 타입이 상관관계를 가질 때"에만 값어치가 있다.

## 3. 오버로드의 한계 — 제네릭 입력엔 약하다

오버로드는 구체 타입 목록을 나열하는 방식이라, 호출자가 제네릭 값을 넘기면 어느 오버로드에도 딱 맞지 않아 마지막(가장 넓은) 것으로 떨어지곤 한다. 입력과 출력의 관계가 **계산 가능한 규칙** 일 때는 조건부 반환 타입이 더 낫다.

```ts
// 오버로드로는 표현이 폭발하는 경우 — 조건부 타입으로
type Unwrap<T> =
  T extends Promise<infer U> ? U :
  T extends Array<infer E> ? E :
  T;

function unwrap<T>(value: T): Unwrap<T> {
  // 구현은 런타임 분기, 반환 타입은 조건부로 계산
  return value as Unwrap<T>;
}

const x = unwrap(Promise.resolve(1)); // number
const y = unwrap([true, false]);      // boolean
const z = unwrap("plain");            // string
```

조건부 반환 타입의 장점은 무한한 입력 타입에 대해 규칙 하나로 반환 타입을 계산한다는 것이다. 단점은 구현부에서 `as` 단언이 거의 불가피하다는 점 — 컴파일러가 조건부 타입과 런타임 분기의 대응을 스스로 검증하진 못한다. 그래서 조건부 반환 함수는 내부를 작고 테스트 가능하게 유지해야 한다.

## 4. 제네릭 추론의 3단계

`f<T>(x: T)` 를 인자와 함께 호출하면 컴파일러가 `T` 를 역으로 추론한다. 대략 세 단계다. 첫째, 각 매개변수 위치에서 **추론 후보(candidate)** 를 모은다. 둘째, 여러 후보가 있으면 **최적 공통 타입(best common type)** 을 찾는다. 셋째, 후보가 없거나 부족하면 **문맥 타입(contextual type)** 이나 기본 타입 인자로 메운다.

```ts
function firstOf<T>(...items: T[]): T { return items[0]; }

const r1 = firstOf(1, 2, 3);        // T = number (후보 셋 다 number)
const r2 = firstOf(1, "a", true);   // T = string | number | boolean (공통 상위 = 유니온)
const r3 = firstOf();               // T = unknown (후보 없음)
```

여러 위치에서 `T` 가 나타나면 각 위치의 후보를 종합한다. 리터럴은 문맥에 따라 넓혀지거나(widening) 유지된다 — 예컨대 `const` 문맥이나 `as const` 가 있으면 리터럴 타입이 보존되고, 일반 변수 할당에서는 넓혀진다.

## 5. 추론 위치의 우선순위 — 반환보다 인자

같은 타입 파라미터가 인자 위치와 반환 위치 양쪽에 있을 때, 컴파일러는 대체로 **인자 위치에서 먼저 추론** 을 확정한다. 그래서 반환 값으로 타입을 유도하려는 의도가 종종 빗나간다.

```ts
declare function make<T>(config: { value: T; validate: (v: T) => boolean }): T;

make({
  value: 5,
  validate: (v) => v > 0,   // v 는 number 로 이미 추론됨 (value 에서)
});
// T = number — value 위치가 먼저 T 를 고정하고, validate 의 v 가 그걸 따름
```

이 순서를 이해하면 "왜 콜백 파라미터 타입이 내가 원한 대로 안 잡히는가"를 설명할 수 있다. 콜백보다 데이터 인자에서 먼저 `T` 가 결정되므로, 데이터 위치를 잘 설계하면 콜백은 자연히 올바른 타입을 얻는다.

## 6. NoInfer 로 추론 오염 막기

때로는 특정 위치가 추론에 **참여하지 못하게** 막고 싶다. 기본값이 타입을 원치 않게 넓히는 경우가 대표적이다. TypeScript 5.4+ 의 `NoInfer<T>` 유틸리티가 이를 해결한다.

```ts
// 문제: defaultColor 가 union 을 넓혀버림
function paint1<C extends string>(colors: C[], defaultColor: C): C { return defaultColor; }
paint1(["red", "green"], "blue");
// C = "red" | "green" | "blue" — 오타 같은 "blue" 가 통과됨

// 해결: defaultColor 는 추론에 기여하지 않고, colors 로 좁힌 C 만 사용
function paint2<C extends string>(colors: C[], defaultColor: NoInfer<C>): C { return defaultColor; }
paint2(["red", "green"], "blue");
// 오류! "blue" 는 "red" | "green" 에 할당 불가 — 의도대로 검증됨
```

`NoInfer` 는 "이 위치의 타입은 다른 곳에서 이미 정해진 `C` 를 **소비만** 하라"는 지시다. 라이브러리 API 에서 기본값·검증 콜백이 제네릭을 오염시키는 흔한 실수를 컴파일 오류로 승격시킨다.

## 7. 기본 타입 인자와 문맥적 추론의 상호작용

타입 파라미터에 기본값(`<T = string>`)을 주면, 추론이 실패했을 때만 기본값이 쓰인다. 추론이 성공하면 기본값은 무시된다. 이를 문맥적 추론과 결합하면 유연한 API 를 만들 수 있다.

```ts
function createStore<S = Record<string, unknown>>(initial?: S): { state: S } {
  return { state: (initial ?? {}) as S };
}

const s1 = createStore();                    // S = Record<string, unknown> (기본값)
const s2 = createStore({ count: 0 });        // S = { count: number } (추론 성공)
const s3 = createStore<{ id: string }>();    // S = { id: string } (명시)
```

정리하면, 오버로드는 "입력→출력이 열거 가능한 유한 매핑"에, 조건부 반환 타입은 "규칙으로 계산되는 무한 매핑"에 쓴다. 제네릭 추론은 인자 위치 우선·최적 공통 타입·문맥 타입 순으로 작동하며, `NoInfer`·기본 타입 인자로 추론 흐름을 정교하게 통제할 수 있다. 이 규칙들을 알면 "타입이 왜 이렇게 잡히는가"를 우연이 아니라 예측으로 다룰 수 있고, 라이브러리 수준의 타입 안전한 API 를 설계할 수 있다.

## 8. 오버로드와 유니온 인자의 상호작용 — 분배되지 않는다

오버로드 해석에서 자주 걸려 넘어지는 지점이 유니온 타입 인자다. 조건부 타입은 네이키드 타입 파라미터에 대해 유니온을 분배(distribute)하지만, **오버로드 해석은 유니온을 분배하지 않는다**. 호출 인자가 유니온이면, 그 유니온 전체를 받아들이는 단일 오버로드를 찾으려 하고, 없으면 매칭에 실패한다.

```ts
function handle(x: string): "str";
function handle(x: number): "num";
function handle(x: string | number): "str" | "num";  // ← 유니온용 오버로드를 별도로 추가해야 함

declare const v: string | number;
const r = handle(v);   // 세 번째 오버로드가 없으면 컴파일 오류
```

이 때문에 "각 구성원용 오버로드를 다 만들었으니 유니온도 될 것"이라는 기대가 깨진다. 유니온 입력을 받으려면 유니온 자체를 명시한 오버로드를 추가하거나, 애초에 조건부 반환 타입으로 전환하는 편이 낫다. 조건부 타입 `T extends string ? "str" : "num"` 은 `string | number` 를 넣으면 `"str" | "num"` 으로 자연히 분배되기 때문이다.

## 9. 실무 판단 — 성능과 가독성 관점

조건부 반환 타입은 강력하지만 남용하면 컴파일러 부담과 오류 메시지 난해함이 커진다. 깊게 중첩된 조건부·재귀 타입은 타입 체크 시간을 눈에 띄게 늘리고, 실패 시 "Type 'X' is not assignable to type '[거대한 조건부 표현식]'" 같은 해독 불가능한 메시지를 뱉는다. `tsc --extendedDiagnostics` 로 타입 인스턴스화 수(`Instantiations`)를 관찰하면, 특정 유틸 타입이 수십만 번 인스턴스화되며 병목이 되는 걸 발견할 수 있다.

실무 지침은 이렇다. 공개 API 의 반환 타입 표현이 두세 갈래로 유한하면 오버로드가 읽기 쉽고 오류 메시지도 친절하다. 입력 타입이 사실상 무한하고 규칙이 명확하면 조건부 타입이 유일한 선택이다. 그리고 어느 쪽이든 구현부는 얇게 유지하고, 타입 단언(`as`)이 실제 런타임 동작과 일치하는지는 단위 테스트로 별도 보증해야 한다. 타입 시스템이 검증해주지 못하는 마지막 간극이 바로 거기이기 때문이다.

## 참고

- TypeScript Handbook — Functions (Overloads), Generics, Conditional Types
- TypeScript 5.4 Release Notes — `NoInfer` utility type
- TypeScript Spec — Overload Resolution, Type Argument Inference
- microsoft/TypeScript wiki — "Type Inference" 내부 동작
