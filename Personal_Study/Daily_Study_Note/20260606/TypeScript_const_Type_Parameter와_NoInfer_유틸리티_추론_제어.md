Notion 원본: https://www.notion.so/3775a06fd6d38141b36de3113ba334c4

# TypeScript const Type Parameter와 NoInfer 유틸리티 — 추론 제어

> 2026-06-06 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript의 기본 추론이 리터럴을 넓히는(widening) 동작과 그 한계를 설명한다
- const type parameter(5.0)가 호출 인자를 as const 없이 좁게 추론시키는 원리를 분석한다
- NoInfer<T>(5.4)가 특정 위치를 추론 후보에서 제외해 추론 우선순위를 제어하는 방식을 정리한다
- 두 기능을 라이브러리 API 설계(설정 객체, 기본값, 이벤트 맵)에 적용하는 패턴을 구현한다

## 1. 문제의 출발 — TypeScript는 기본적으로 타입을 넓힌다

```typescript
let a = "hello";           // string
const b = "hello";         // "hello"
const obj = { x: "hello" }; // { x: string }
function id<T>(x: T): T { return x; }
const r = id(["a", "b"]);  // string[]
```

라이브러리 작성자 입장에서 이 넓힘은 종종 손해다. 전통적 해결책은 호출자에게 as const를 요구하는 것이었으나 매번 붙이라고 강요하는 API는 사용성이 나쁘다.

## 2. const Type Parameter (TS 5.0)

```typescript
function id<const T>(x: T): T { return x; }
const r = id(["a", "b"]);   // readonly ["a", "b"]  ← as const 없이!
declare function createRouter<const Routes extends readonly string[]>(
  routes: Routes
): { paths: Routes[number] };
const router = createRouter(["/home", "/about", "/users/:id"]);
type Path = typeof router.paths; // "/home"|"/about"|"/users/:id"
```

const T는 이 타입 파라미터로 들어오는 인자를 const-like 추론하라는 지시다. 단 const는 추론에만 작용하므로 인자가 이미 선언된 변수면 그 변수 타입이 쓰인다. mutable이 필요한 곳에 readonly tuple이 들어가면 제약 충돌이 나므로 extends readonly unknown[]로 제약을 잡는다.

## 3. NoInfer<T> (TS 5.4)

```typescript
function createState<T>(initial: NoInfer<T>, options: T[]): T { return initial; }
createState("dark", ["dark", "light"]); // OK, T = "dark"|"light"
createState("blue", ["dark", "light"]); // 에러! "blue"는 T에 할당 불가
```

NoInfer<T>로 감싼 initial 위치는 추론에 기여하지 않는다. T는 오직 options에서 정해지고 initial은 검사만 받는다. NoInfer<T>는 컴파일러 내장 intrinsic 유틸리티 타입으로, T와 동일하지만 추론 엔진에 이 위치를 candidate로 쓰지 말라는 표시를 단다.

| 도구 | 푸는 문제 | 작용 |
|---|---|---|
| as const (호출자) | 리터럴 넓힘 방지 | 호출부에 명시 부담 |
| const T (5.0) | 리터럴 넓힘 방지 | 선언부에서 자동, 호출자 부담 0 |
| NoInfer<T> (5.4) | 다중 위치 추론 충돌 | 특정 위치를 추론에서 제외 |

## 4. 두 기능의 조합

```typescript
function defineConfig<const T extends Record<string, unknown>>(
  config: T, defaults: NoInfer<Partial<T>>
): T { return { ...defaults, ...config } as T; }
const cfg = defineConfig(
  { theme: "dark", level: 3 },  // T = { theme: "dark"; level: 3 }
  { theme: "light" }            // 추론에 관여 안 함, 검사만
);
```

const T로 config의 리터럴이 보존되고 NoInfer<Partial<T>>로 defaults는 T를 정하는 데 끼어들지 못한 채 호환성만 검증받는다.

## 5. 흔한 함정

const T인데 안 좁혀지면 인자가 인라인 리터럴이 아니라 선언된 변수일 수 있다. readonly 충돌은 제약을 readonly unknown[]로 완화한다. NoInfer는 타입을 정하는 인자가 아니라 검사만 받는 인자를 감싸야 한다. const T는 TS≥5.0, NoInfer는 TS≥5.4 요구.

## 6. 언제 쓰나

이 둘은 애플리케이션 코드보다 라이브러리/프레임워크 API 설계에서 진가가 난다. 라우터, 상태 머신, 폼 스키마, 설정 객체, 이벤트 emitter처럼 호출자가 넘긴 리터럴을 정밀한 타입으로 보존해야 하는 표면에 적용한다. 이 API 사용성을 위해 호출자가 as const를 붙여야 하나? 라면 const T가 답이고, 두 인자 중 하나로만 타입을 정하고 싶다면 NoInfer가 답이다.

## 7. 추론 엔진 관점 — candidate 수집과 우선순위

호출이 일어나면 컴파일러는 각 타입 파라미터에 대해 inference candidate들을 수집한다. 같은 T가 여러 위치에 나타나면 각 위치에서 candidate가 모이고 합쳐서 최종 타입을 정한다. NoInfer<T>는 candidate 수집 단계에 개입해 그 위치가 candidate를 기여하지 않게 하고, const type parameter는 candidate를 literal/readonly 형태로 캡처하게 바꾼다. 즉 const T는 candidate를 얼마나 좁게 만들 것인가를, NoInfer<T>는 이 위치가 candidate를 만들 것인가 말 것인가를 제어한다.

```typescript
declare function pick<const T extends readonly string[]>(
  keys: T, fallback: NoInfer<T[number]>
): T[number];
pick(["red", "green", "blue"], "green"); // OK
pick(["red", "green", "blue"], "pink");  // 에러
```

## 8. 정리

const type parameter(5.0)는 호출 인자를 as const 없이 좁게 추론시켜 호출자의 부담을 없애고, NoInfer<T>(5.4)는 특정 인자 위치를 추론 후보에서 제외해 어느 인자가 타입을 정하는지를 명시한다. 둘 다 런타임 코드가 없는 순수 추론 제어 장치이며, 조합하면 호출자가 넘긴 리터럴을 보존하면서 잘못된 인자를 컴파일 타임에 잡는 정밀한 라이브러리 타입을 만든다.

## 참고

- TypeScript 5.0 Release Notes — "const Type Parameters"
- TypeScript 5.4 Release Notes — "The NoInfer Utility Type"
- TypeScript Handbook — Type Inference, Literal Widening, as const
- microsoft/TypeScript PR #51865, #56794
- TypeScript Deep Dive — Inference 우선순위와 candidate 수집
