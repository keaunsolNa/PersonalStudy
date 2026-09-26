Notion 원본: https://app.notion.com/p/3e75a06fd6d38166ae05fe6f2f3fe4d1

# TypeScript Conditional Types와 infer 추론 및 분배 조건부 타입 심화

> 2026-09-26 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- 조건부 타입의 평가 모델을 `extends` 기반 타입 레벨 분기로 재구성한다
- `infer` 키워드가 타입 변수를 캡처하는 위치(공변/반공변)에 따른 추론 결과 차이를 구분한다
- 분배 조건부 타입이 유니온을 순회하는 방식과 이를 억제하는 튜플 래핑 패턴을 적용한다
- 재귀 조건부 타입의 컴파일러 제약(인스턴스화 깊이, 순환 감지)을 실전 유틸리티 타입 설계에 반영한다

## 1. 조건부 타입의 평가 모델

TypeScript의 조건부 타입 `T extends U ? X : Y`는 런타임의 삼항 연산자와 문법은 닮았지만 평가 시점과 대상이 다르다. 런타임 삼항 연산자는 값을 평가하지만, 조건부 타입은 타입 체커가 컴파일 타임에 `T`가 `U`의 서브타입인지를 구조적 할당 가능성(structural assignability) 규칙으로 판정한다. 이 판정은 `strictFunctionTypes`, `strictNullChecks` 같은 컴파일러 옵션에 따라 미묘하게 달라지는데, 특히 함수 타입의 매개변수 위치에서는 이변성(bivariant) 체크가 남아있는 메서드 문법과, 반공변으로 엄격하게 체크되는 함수 프로퍼티 문법 사이에 차이가 생긴다.

```typescript
type IsAssignable<T, U> = T extends U ? true : false;

type A = IsAssignable<"a", string>;      // true
type B = IsAssignable<string, "a">;      // false
type C = IsAssignable<any, string>;      // boolean (양쪽 분기 모두 성립 가능)
type D = IsAssignable<never, string>;    // never (조건부 타입 자체가 즉시 분배됨)
```

여기서 `any`가 조건부 타입에 들어가면 `true | false`가 아니라 `boolean`으로 표시되는 것은, 타입 체커가 `any`를 양쪽 분기 모두와 호환된다고 보고 두 분기의 유니온을 계산한 뒤 이를 `boolean`으로 정규화하기 때문이다. 이 특성 때문에 조건부 타입 기반 유틸리티를 설계할 때 `any`가 실수로 흘러 들어오면 검증 로직 전체가 무력화될 수 있어, 실전에서는 `IsAny<T>`를 별도로 만들어 가드하는 경우가 많다.

```typescript
type IsAny<T> = 0 extends 1 & T ? true : false;
```

`1 & T`는 `T`가 `any`일 때만 `0`과 호환되는 성질을 이용한 트릭으로, `unknown`이나 일반 타입에서는 `false`를 반환한다. 이런 패턴은 조건부 타입이 값이 아니라 "타입들 사이의 관계에 대한 논리식"이라는 점을 이해해야 설계할 수 있다.

## 2. infer 키워드와 타입 변수 캡처 메커니즘

`infer`는 조건부 타입의 `extends` 절 안에서만 등장할 수 있는 특수 키워드로, 패턴 매칭 중 특정 위치의 타입을 새로운 타입 변수로 바인딩한다. 내부적으로 컴파일러는 `infer` 위치마다 후보 타입들의 집합을 모은 뒤, 그 위치가 공변(covariant) 위치인지 반공변(contravariant) 위치인지에 따라 합집합(유니온) 또는 교집합(인터섹션)으로 좁힌다.

```typescript
type ElementType<T> = T extends (infer U)[] ? U : never;
type Ret<T> = T extends (...args: any[]) => infer R ? R : never;

// 반공변 위치: 함수의 매개변수 자리에서 infer는 인터섹션으로 합쳐진다
type ArgsToIntersection<T> =
  T extends { f(x: infer P): void } ? P : never;

type Combined = ArgsToIntersection<
  { f(x: { a: string }): void } | { f(x: { b: number }): void }
>;
// Combined: { a: string } & { b: number }
```

공변 위치(반환 타입, 배열 요소, 프로퍼티 값)에서 여러 후보가 나오면 유니온으로 합쳐지고, 반공변 위치(함수 매개변수)에서는 인터섹션으로 합쳐진다는 규칙은 TypeScript의 함수 서브타이핑 규칙과 정확히 대칭을 이룬다. 함수는 매개변수 타입에 대해 반공변적이어야 여러 시그니처를 동시에 만족하는 최소 상한(인터섹션)을 구할 수 있기 때문이다. 이 규칙을 모르면 오버로드된 함수 타입에서 `infer`로 매개변수를 추출했을 때 왜 유니온이 아니라 인터섹션이 나오는지 디버깅하기 어렵다.

## 3. 분배 조건부 타입의 동작 원리

조건부 타입 `T extends U ? X : Y`에서 `T`가 아무런 수식 없이 등장하는 "네이키드 타입 매개변수(naked type parameter)"이고 실제로 대입되는 타입이 유니온이면, 컴파일러는 이를 유니온의 각 멤버에 대해 개별적으로 분배(distribute)한 뒤 그 결과를 다시 유니온으로 합친다.

```typescript
type ToArray<T> = T extends any ? T[] : never;

type Result = ToArray<string | number>;
// 분배 과정: ToArray<string> | ToArray<number>
// 결과: string[] | number[]  (※ (string | number)[] 이 아님)
```

이 동작은 제네릭 함수가 유니온 타입 인자를 받았을 때 각 케이스에 맞는 오버로드를 자동으로 합성하는 효과를 낸다. `Exclude<T, U>`와 `Extract<T, U>` 같은 내장 유틸리티도 전부 이 분배 성질 위에서 동작한다.

```typescript
type Exclude<T, U> = T extends U ? never : T;
type Extract<T, U> = T extends U ? T : never;

type Without = Exclude<"a" | "b" | "c", "a">; // "b" | "c"
```

`Exclude`가 동작하는 원리는, 유니온 `"a" | "b" | "c"`가 각각 분배되어 `"a" extends "a" ? never : "a"`, `"b" extends "a" ? never : "b"`, `"c" extends "a" ? never : "c"`로 나뉘고, 첫 번째만 `never`가 되어 유니온에서 자동으로 소거되는 것이다. `never`가 유니온에 포함되면 그 멤버는 사라진다는 성질(`T | never == T`)과 결합해 필터링 효과가 생긴다.

## 4. 분배 억제와 튜플 래핑 패턴

분배가 항상 바람직한 것은 아니다. 유니온 자체를 하나의 단위로 검사하고 싶을 때는 `T`와 조건절의 대상을 튜플로 감싸 "네이키드 타입 매개변수"가 아니게 만들어 분배를 억제한다.

```typescript
type IsUnion<T, U = T> =
  T extends U ? ([U] extends [T] ? false : true) : never;

type X = IsUnion<"a" | "b">; // true
type Y = IsUnion<"a">;       // false
```

`[T] extends [U]`처럼 양쪽을 튜플로 감싸면 `T`가 더 이상 조건부 타입 최상위의 네이키드 타입 매개변수가 아니므로 분배가 일어나지 않는다. `IsUnion`은 이 성질을 이용해, `T`를 분배시켜 각 멤버에 대해 원래의 유니온 `U`와 비교하는 트릭으로 유니온 여부를 판별한다. 유니온의 한 멤버만 뽑아 전체와 비교했을 때 서로 다르면(즉 원래 유니온이 여러 멤버를 가졌으면) `true`가 된다.

실전에서는 문자열 유니온을 키로 하는 매핑 타입을 만들 때 분배가 필요 없는 경우가 흔하다. 예를 들어 여러 필드를 하나의 배열로 묶어야 하는데 분배 때문에 원치 않게 필드별로 쪼개진 배열 유니온이 생기는 버그가 대표적인 실수 패턴이다.

## 5. 재귀 조건부 타입과 인스턴스화 깊이 제한

TypeScript 컴파일러는 조건부 타입의 재귀 인스턴스화에 대해 내부적으로 깊이 제한과 순환 감지 로직을 두고 있다. TS 4.5 이후에는 꼬리 재귀(tail-recursive) 형태로 작성된 조건부 타입에 대해 인스턴스화를 상수 스택 공간으로 처리하는 최적화가 추가되어, 이전보다 훨씬 깊은 재귀도 "Type instantiation is excessively deep" 오류 없이 처리할 수 있게 되었다.

```typescript
// 꼬리 재귀 형태: 누산기(accumulator)를 사용해 즉시 값을 반환
type Join<T extends readonly string[], D extends string, Acc extends string = ""> =
  T extends readonly [infer Head extends string, ...infer Rest extends string[]]
    ? Join<Rest, D, Acc extends "" ? Head : `${Acc}${D}${Head}`>
    : Acc;

type Path = Join<["a", "b", "c"], ".">; // "a.b.c"
```

이 패턴이 꼬리 재귀로 인정받으려면 재귀 호출이 조건부 타입의 최종 반환 위치에 있어야 하고, 재귀 호출 결과에 대해 추가 연산(예: 배열에 요소를 더 붙이는 등)을 하지 않아야 한다. 만약 `Join`의 결과에 대해 바깥에서 다시 템플릿 리터럴을 합성하는 식으로 감싸면 컴파일러는 이를 꼬리 재귀로 인식하지 못하고, 깊은 배열에 대해서는 다시 깊이 제한에 걸릴 수 있다.

## 6. 실전 패턴: 유틸리티 타입 재구현

내장 유틸리티 타입을 직접 재구현해보면 `infer`와 분배, 재귀가 어떻게 조합되는지 체감할 수 있다.

```typescript
type MyAwaited<T> =
  T extends null | undefined ? T :
  T extends { then(onfulfilled: infer F): any } ?
    F extends (value: infer V, ...args: any[]) => any ?
      MyAwaited<V> :
      never :
    T;

type DeepReadonly<T> = T extends (infer U)[]
  ? ReadonlyArray<DeepReadonly<U>>
  : T extends object
    ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
    : T;

type UnionToIntersection<U> =
  (U extends any ? (k: U) => void : never) extends (k: infer I) => void
    ? I
    : never;
```

`UnionToIntersection`은 조건부 타입 심화 학습에서 가장 자주 인용되는 트릭이다. 먼저 `U extends any ? (k: U) => void : never`로 유니온을 함수 매개변수 위치에 분배시켜 `(k: A) => void | (k: B) => void` 형태의 함수 유니온을 만들고, 그 다음 이 함수 유니온 전체에서 매개변수를 다시 `infer`로 추출한다. 함수 매개변수는 반공변 위치이므로 여러 함수 타입의 매개변수를 추출하면 인터섹션으로 합쳐진다는 2절의 규칙이 여기서 그대로 적용된다.

## 7. 실측 관점의 컴파일 비용

조건부 타입과 재귀가 깊어질수록 타입 체킹 비용은 비선형적으로 증가하는 경향이 있다. `tsc --extendedDiagnostics` 옵션을 사용하면 `Types`, `Instantiations`, `Check time` 항목을 통해 특정 파일의 타입 인스턴스화 횟수를 확인할 수 있다.

```bash
tsc --noEmit --extendedDiagnostics src/types/deep-utils.ts
```

일반적으로 관찰되는 경향은, 문자열 리터럴 유니온에 대한 재귀적 템플릿 리터럴 조작(예: 경로 문자열 파싱)이 인스턴스화 수를 가장 빠르게 늘리는 패턴이라는 것이다. 유니온 멤버 수가 N일 때 분배가 중첩되는 조건부 타입을 다시 유니온 위에서 매핑하면 인스턴스화가 N²에 가깝게 늘어날 수 있어, 대규모 문자열 유니온(예: 수백 개의 API 엔드포인트 경로)에 대해 파서 타입을 적용할 때는 반드시 `--extendedDiagnostics`로 사전 측정을 해보는 것이 안전하다. 실무에서는 이런 비용이 커지면 타입 레벨 계산을 포기하고 `as` 단언 + 런타임 검증(Zod 등)으로 전환하는 것이 합리적인 트레이드오프가 된다.

## 8. IDE 반응성과의 트레이드오프

깊은 조건부 타입은 컴파일 시간뿐 아니라 언어 서버(tsserver)의 응답성에도 영향을 준다. VS Code에서 호버 툴팁이나 자동완성이 느려지는 경우, 대부분 특정 위치에서 조건부 타입이 계속 재평가되기 때문이다. 이를 완화하는 실전 전략은 다음과 같다.

<table header-row="true"><tr><td>전략</td><td>효과</td><td>비용</td></tr><tr><td>중간 결과 타입 별칭으로 캐싱</td><td>동일 인스턴스화 재사용</td><td>가독성 저하 가능</td></tr><tr><td>꼬리 재귀 리팩터링</td><td>스택 대신 상수 공간 사용</td><td>설계 난이도 상승</td></tr><tr><td>런타임 검증으로 위임</td><td>타입 레벨 계산 제거</td><td>컴파일 타임 보장 상실</td></tr><tr><td>유니온 멤버 수 제한</td><td>인스턴스화 폭발 방지</td><td>표현력 제한</td></tr></table>

실무에서는 라이브러리 공개 API처럼 여러 팀이 자주 열어보는 타입에 대해서는 첫 번째와 두 번째 전략을 우선 적용하고, 내부 전용 유틸리티에서는 컴파일 비용보다 표현력을 우선하는 식으로 기준을 나눠 적용하는 것이 합리적이다.

## 9. 종합 예제: 타입 세이프 이벤트 에미터

지금까지의 규칙을 하나로 묶어 실전에 가까운 예제를 구성해보면 각 개념이 어떻게 맞물리는지 확인할 수 있다. 아래는 이벤트 이름과 페이로드 타입을 매핑 타입으로 선언하고, `infer`와 분배 조건부 타입만으로 `emit`/`on`의 인자 타입을 완전히 추론하는 이벤트 에미터다.

```typescript
type EventMap = {
  login: { userId: string };
  logout: { userId: string; reason: "manual" | "timeout" };
  error: { code: number; message: string };
};

type Handler<T> = (payload: T) => void;

class TypedEmitter<M extends Record<string, unknown>> {
  private handlers: { [K in keyof M]?: Handler<M[K]>[] } = {};

  on<K extends keyof M>(event: K, handler: Handler<M[K]>): void {
    (this.handlers[event] ??= []).push(handler);
  }

  emit<K extends keyof M>(event: K, payload: M[K]): void {
    this.handlers[event]?.forEach((h) => h(payload));
  }
}

// infer로 핸들러 시그니처에서 페이로드 타입만 역추출하는 헬퍼
type PayloadOf<M, K extends keyof M> =
  M[K] extends infer P ? P : never;

const emitter = new TypedEmitter<EventMap>();
emitter.on("logout", (payload) => {
  // payload: { userId: string; reason: "manual" | "timeout" }
  console.log(payload.reason);
});
```

여기서 `Handler<M[K]>[]`의 요소 타입을 복원할 때는 2절에서 다룬 배열 요소 추출 패턴(`T extends (infer U)[] ? U : never`)을 그대로 재사용할 수 있고, `reason` 필드처럼 유니온이 중첩된 페이로드는 3절의 분배 규칙에 따라 각 핸들러 호출부에서 정확히 좁혀진 유니온으로 나타난다. 만약 이벤트 페이로드 정의에 조건부 타입을 섞어 동적으로 계산한다면(예: 특정 이벤트는 다른 이벤트의 페이로드를 재사용), 8절에서 설명한 인스턴스화 비용도 함께 고려해야 한다. 이벤트 종류가 수십 개를 넘어가는 대규모 시스템에서는 `PayloadOf`처럼 매핑 타입을 한 번 더 감싸는 레이어가 tsserver 응답성에 미치는 영향을 실측하고, 필요하면 이벤트 그룹별로 타입을 분리하는 것이 실전적인 절충안이다.

## 참고

- TypeScript Handbook, "Conditional Types" (microsoft/TypeScript 공식 문서)
- TypeScript 4.5 Release Notes, "Tail-Recursion Elimination on Conditional Types"
- Anders Hejlsberg, TSConf 발표자료 중 조건부 타입 설계 배경 세션
- type-challenges (GitHub) 저장소의 조건부 타입 관련 문제 세트
