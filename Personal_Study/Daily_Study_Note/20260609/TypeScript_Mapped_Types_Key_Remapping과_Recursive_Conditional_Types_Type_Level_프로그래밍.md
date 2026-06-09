Notion 원본: https://www.notion.so/37a5a06fd6d3817090e8ed799dd2b3f7

# TypeScript Mapped Types Key Remapping과 Recursive Conditional Types Type-Level 프로그래밍

> 2026-06-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Mapped Type의 `as` 절(Key Remapping)로 키를 필터링·치환·재조합하는 패턴을 구현한다
- Recursive Conditional Type으로 중첩 구조를 타입 수준에서 순회하고 변환한다
- 분배 조건부 타입(distributive)과 `infer`를 결합해 키 경로 문자열을 추출한다
- 타입 인스턴스화 깊이 제한과 컴파일 성능 저하를 측정하고 회피한다

## 1. Mapped Type의 본질과 `as` 절

Mapped Type은 `{ [K in Keys]: ValueType }` 형태로 유니온 키 집합을 순회하며 새 객체 타입을 만든다. TypeScript 4.1에서 도입된 `as` 절은 순회 과정에서 키 자체를 다른 타입으로 다시 매핑(remapping)할 수 있게 한다. 핵심은 `as` 우변이 `string | number | symbol`로 평가되어야 하며, `never`로 평가되면 해당 키가 결과에서 제거된다는 점이다. 이 "never로 매핑하면 키가 사라진다"는 규칙이 키 필터링의 기반이다.

```typescript
// 함수 타입 프로퍼티만 골라내기
type FunctionKeys<T> = {
  [K in keyof T as T[K] extends (...args: any[]) => any ? K : never]: T[K];
};

interface Service {
  id: number;
  name: string;
  fetch: () => Promise<void>;
  save: (v: string) => void;
}

type Callable = FunctionKeys<Service>;
// { fetch: () => Promise<void>; save: (v: string) => void; }
```

조건부 타입이 `K`를 그대로 통과시키거나 `never`로 떨어뜨리면서, `id`/`name`은 키 자체가 `never`가 되어 결과 객체에서 누락된다. 이는 `Pick`/`Omit`보다 표현력이 높은데, 값 타입을 술어로 사용해 키를 선택하기 때문이다.

## 2. Template Literal과 결합한 키 치환

`as` 절 우변에 Template Literal Type을 쓰면 키 이름 자체를 재작성할 수 있다. getter 자동 생성이 대표적 예다.

```typescript
type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};

interface User { name: string; age: number; }

type UserGetters = Getters<User>;
// { getName: () => string; getAge: () => number; }
```

`string & K` 교차로 `K`가 `string`임을 보장한 뒤 `Capitalize`로 첫 글자를 올린다. 주의할 점은 `K`가 `number`나 `symbol`일 수 있으므로 `string &`로 좁히지 않으면 Template Literal에 넣을 수 없다는 것이다. 실무에서는 ORM 엔티티에서 DTO를 파생하거나, 이벤트 핸들러 prop을 자동 생성(`onClick` → `onClickCapture`)할 때 쓴다.

## 3. Recursive Conditional Type으로 중첩 순회

조건부 타입은 자기 자신을 참조할 수 있다. 이를 Mapped Type과 결합하면 깊은 객체를 재귀적으로 변환한다. `DeepReadonly`가 표준 예시다.

```typescript
type DeepReadonly<T> = T extends (infer E)[]
  ? ReadonlyArray<DeepReadonly<E>>
  : T extends object
    ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
    : T;

interface Config {
  server: { port: number; hosts: string[] };
  debug: boolean;
}

type Frozen = DeepReadonly<Config>;
// server.hosts 는 readonly string[], 모든 필드 readonly
```

배열을 먼저 분기하는 이유는 배열도 `object`에 속하므로 순서를 바꾸면 배열이 인덱스 시그니처를 가진 일반 객체로 잘못 매핑되기 때문이다. 함수 타입을 보존해야 한다면 `T extends Function ? T : ...` 분기를 객체 분기보다 앞에 둬야 한다. 분기 순서가 곧 우선순위다.

## 4. `infer`와 분배 조건부로 경로 추출

중첩 객체의 모든 키 경로를 `"server.port"` 같은 점 표기 문자열 유니온으로 뽑는 패턴은 타입 안전한 i18n 키나 `lodash.get` 경로 검증에 쓰인다.

```typescript
type Paths<T> = T extends object
  ? {
      [K in keyof T & string]: T[K] extends object
        ? `${K}` | `${K}.${Paths<T[K]>}`
        : `${K}`;
    }[keyof T & string]
  : never;

interface Settings {
  ui: { theme: string; layout: { columns: number } };
  locale: string;
}

type SettingPaths = Paths<Settings>;
// "ui" | "ui.theme" | "ui.layout" | "ui.layout.columns" | "locale"
```

`{ [K in ...]: ... }[keyof T & string]`로 인덱싱하면 각 키의 결과 유니온을 합쳐 평탄화한다. 이 "Mapped Type을 만든 뒤 keyof로 인덱싱해 값 유니온을 얻는" 패턴은 type-level 프로그래밍의 핵심 관용구다. 분배 조건부는 유니온의 각 멤버에 독립적으로 적용되므로 `keyof T`가 유니온일 때 자연스럽게 재귀가 펼쳐진다.

## 5. 분배 조건부의 함정과 `[T]` 무력화

`T extends U ? X : Y`에서 `T`가 naked 타입 파라미터이고 유니온이면 분배가 일어난다. 분배를 막으려면 양변을 튜플로 감싼다.

```typescript
type IsNever<T> = [T] extends [never] ? true : false;
// T 가 never 일 때 분배되면 조건부 자체가 never 가 되어 의도와 어긋남

type A = IsNever<never>;   // true (튜플로 감싸 분배 차단)
type B = IsNever<number>;  // false
```

`never`는 빈 유니온이므로 naked 분배 시 조건부 전체가 `never`로 단락된다. `[T] extends [never]`로 감싸야 "T 전체가 never인가"를 올바르게 검사한다. 이 idiom은 조건부 결과를 디버깅할 때 가장 자주 만나는 함정이다.

## 6. 인스턴스화 깊이 제한과 성능

재귀 조건부 타입은 기본적으로 약 50단계, Tail-recursion 최적화가 적용되면 1000단계까지 인스턴스화된다. TypeScript 4.5부터 조건부 타입의 꼬리 재귀를 평탄화해 깊은 재귀가 가능해졌지만, Mapped Type 내부 재귀에는 적용되지 않는다.

| 패턴 | 깊이 한계 | 비고 |
|---|---|---|
| 일반 재귀 조건부 | ~50 | `Type instantiation is excessively deep` 에러 |
| Tail-recursive 조건부(accumulator) | ~1000 | 4.5+ 평탄화 적용 |
| Mapped Type 내부 재귀 | 객체 깊이에 비례 | 평탄화 미적용, 폭증 주의 |

```typescript
// 꼬리 재귀: 누산기 패턴으로 깊이 한계 완화
type BuildTuple<N extends number, Acc extends unknown[] = []> =
  Acc['length'] extends N ? Acc : BuildTuple<N, [...Acc, unknown]>;

type Ten = BuildTuple<10>['length']; // 10
```

깊은 경로 타입을 라이브러리에 넣을 때는 `tsc --extendedDiagnostics`로 `Instantiation count`를 측정해야 한다. 경로 추출 타입 하나가 수만 건의 인스턴스화를 유발해 IDE 자동완성이 수 초씩 멈추는 사례가 흔하다. 실무에서는 깊이를 명시적으로 제한(예: 깊이 5까지만 펼치고 그 이하는 `string`)하는 가드를 둔다.

## 7. 실전: 타입 안전한 부분 업데이트 빌더

Key Remapping과 재귀를 합쳐, 중첩 필드를 점 경로로 안전하게 수정하는 함수 시그니처를 만들 수 있다.

```typescript
type PathValue<T, P extends string> =
  P extends `${infer K}.${infer Rest}`
    ? K extends keyof T ? PathValue<T[K], Rest> : never
    : P extends keyof T ? T[P] : never;

function setIn<T, P extends Paths<T>>(obj: T, path: P, value: PathValue<T, P>): T {
  // 런타임 구현은 path.split('.') 순회. 타입만으로 경로/값 정합성 강제
  return obj;
}

declare const settings: Settings;
setIn(settings, 'ui.layout.columns', 3);   // OK
// setIn(settings, 'ui.layout.columns', 'x'); // 컴파일 에러: number 기대
// setIn(settings, 'ui.unknown', 1);          // 컴파일 에러: 경로 없음
```

`Paths<T>`가 허용 경로 유니온을 만들고, `PathValue<T, P>`가 그 경로의 값 타입을 역추적해 세 번째 인자를 강제한다. 런타임 로직 없이 컴파일 타임에 경로 오타와 타입 불일치를 모두 잡는다.

## 8. homomorphic mapped type과 modifier 보존

`{ [K in keyof T]: ... }`처럼 `keyof T`를 직접 순회하는 형태를 homomorphic(준동형) mapped type이라 한다. 이 형태는 원본 타입의 `readonly`·`?`(optional) modifier와 배열·튜플 구조를 자동으로 보존한다. 반면 `{ [K in SomeUnion]: ... }`처럼 별도 유니온을 순회하면 modifier가 따라오지 않는다. 이 차이가 `Partial`/`Required`/`Readonly` 같은 표준 유틸리티가 의도대로 동작하는 이유다.

```typescript
// homomorphic: T 의 구조(배열/튜플/modifier)를 보존
type Clone<T> = { [K in keyof T]: T[K] };
type T1 = Clone<readonly string[]>; // readonly string[] 유지

// modifier 가감: +/- 로 readonly·optional 명시 제어
type Mutable<T> = { -readonly [K in keyof T]-?: T[K] }; // readonly·optional 제거
type T2 = Mutable<{ readonly a?: number }>; // { a: number }
```

`-readonly`와 `-?`는 modifier를 "벗기고", `+readonly`/`+?`(또는 그냥 `readonly`/`?`)는 "붙인다". `Required<T>`가 `-?`로 옵셔널을 제거하는 식이다. 키 리매핑(`as`)을 쓰면 homomorphic 성질이 깨져 modifier가 보존되지 않으므로, 구조 보존이 중요하면 `as` 사용 여부를 신중히 결정해야 한다.

## 9. trade-off 정리

타입 수준 프로그래밍은 런타임 비용 없이 정합성을 보장하지만, 컴파일 시간과 에러 메시지 가독성을 희생한다. 깊은 재귀 타입은 `error TS2589`(과도한 인스턴스화)를 던지거나 IDE를 느리게 만든다. 따라서 (1) 라이브러리 공개 API 경계에만 적용하고 내부 구현은 단순 타입으로 두기, (2) 깊이 가드로 폭증 차단, (3) `--extendedDiagnostics`로 정량 측정이라는 세 가지 원칙을 지키는 것이 핵심이다. 표현력과 빌드 성능은 명백한 trade-off 관계이며, 측정 없이 "타입으로 다 막자"는 접근은 팀 전체의 IDE 응답성을 떨어뜨린다.

## 참고

- TypeScript Handbook — Mapped Types / Key Remapping via `as` (https://www.typescriptlang.org/docs/handbook/2/mapped-types.html)
- TypeScript Handbook — Conditional Types / `infer` (https://www.typescriptlang.org/docs/handbook/2/conditional-types.html)
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types
- type-challenges (GitHub) — medium/hard 문제로 본 type-level 패턴 모음
