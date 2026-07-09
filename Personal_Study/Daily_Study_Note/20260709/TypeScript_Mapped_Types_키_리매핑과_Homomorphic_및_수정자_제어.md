Notion 원본: https://app.notion.com/p/3985a06fd6d38106ad2bfc10b141562a

# TypeScript Mapped Types 키 리매핑과 Homomorphic 및 수정자 제어

> 2026-07-09 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Mapped Type 의 문법 구조와 `keyof`·인덱스 시그니처의 관계를 분해한다
- Homomorphic mapped type 이 원본의 수정자(readonly·optional)와 튜플·배열 구조를 보존하는 메커니즘을 설명한다
- `as` 절을 이용한 key remapping 으로 키를 필터링·재명명한다
- `+`/`-` 수정자로 readonly·optional 을 명시적으로 추가·제거하고 그 우선순위를 판별한다

## 1. Mapped Type 의 기본 구조

Mapped type 은 유니온으로 표현된 키 집합을 순회하며 각 키에 새 타입을 할당하는 타입 수준 반복문이다. 문법은 `{ [K in Keys]: ValueType }` 이며 `Keys` 자리에는 반드시 `string | number | symbol` 의 하위 타입 유니온이 와야 한다. 가장 흔한 형태는 기존 객체 타입에서 `keyof T` 로 키를 뽑아 순회하는 것이다.

```typescript
type Flags = { darkMode: boolean; beta: boolean; a11y: boolean };
type AllOptional<T> = { [K in keyof T]?: T[K] };
type PartialFlags = AllOptional<Flags>;
// { darkMode?: boolean; beta?: boolean; a11y?: boolean }
```

여기서 `T[K]` 는 indexed access type 으로, 순회 중인 키 `K` 에 대응하는 원본 값 타입을 다시 꺼내온다. 이 `[K in keyof T]: T[K]` 패턴이 표준 라이브러리의 `Partial`, `Required`, `Readonly`, `Pick` 이 모두 공유하는 뼈대다. 값 자리에 conditional type 을 결합하면 값 타입을 조건부로 변형할 수 있다.

## 2. Homomorphic Mapped Type 과 수정자 보존

`[K in keyof T]` 처럼 키 집합이 `keyof T` 로부터 직접 파생되는 mapped type 을 homomorphic(동형)이라 부른다. 이 경우 컴파일러는 원본 `T` 각 프로퍼티의 `readonly`·`?` 수정자를 결과에 복사한다. 키가 외부 유니온에서 오면 동형이 아니며 수정자 보존이 일어나지 않는다.

```typescript
interface User { readonly id: number; name?: string }
type Clone<T> = { [K in keyof T]: T[K] };
type C = Clone<User>; // { readonly id: number; name?: string }

type Keys = "id" | "name";
type NonHomo = { [K in Keys]: number }; // 수정자 없음
```

동형 mapped type 은 배열·튜플에도 특별하게 동작한다. `Partial<[number, string]>` 이 객체가 아니라 `[number?, string?]` 튜플로 나오는 이유가 이 규칙이다. conditional type 으로 감싸면 동형성이 깨져 수정자·튜플 보존이 사라질 수 있으므로 `[K in keyof T]` 형태를 유지하는 편이 안전하다.

## 3. `as` 절을 이용한 Key Remapping

TS 4.1 이후 키 절에 `as` 를 붙여 각 키를 다른 리터럴 타입으로 재매핑할 수 있다. 재매핑 결과가 `never` 인 키는 결과에서 제거되므로 필터링과 재명명을 동시에 수행한다.

```typescript
type PickByValue<T, V> = {
  [K in keyof T as T[K] extends V ? K : never]: T[K];
};
type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};
type P = { name: string; age: number };
type PGetters = Getters<P>; // { getName: () => string; getAge: () => number }
```

`string & K` 로 교차한 이유는 `keyof T` 가 `symbol` 을 포함할 수 있어 `Capitalize` 가 요구하는 `string` 으로 좁혀야 하기 때문이다. symbol 키는 이 교차에서 `never` 로 걸러진다.

## 4. `+`/`-` 수정자 명시 제어

`readonly` 와 `?` 앞에 `+`(추가, 보통 생략)·`-`(제거)를 붙일 수 있다. `Required<T>` 는 `-?`, `Mutable` 은 `-readonly` 를 쓴다.

```typescript
type Mutable<T> = { -readonly [K in keyof T]: T[K] };
type Concrete<T> = { [K in keyof T]-?: T[K] }; // Required 와 동일
```

`-?` 는 optional 플래그와 함께 값 타입의 `undefined` 유니온 성분도 제거하지만, `-readonly` 는 값 타입을 건드리지 않는다.

## 5. 표준 유틸리티의 재구성

| 유틸리티 | 정의 | 핵심 수정자 |
|---|---|---|
| `Partial<T>` | `{ [K in keyof T]?: T[K] }` | `?` 추가 |
| `Required<T>` | `{ [K in keyof T]-?: T[K] }` | `?` 제거 |
| `Readonly<T>` | `{ readonly [K in keyof T]: T[K] }` | `readonly` 추가 |
| `Pick<T,K>` | `{ [P in K]: T[P] }` | 키 유니온 제한 |
| `Record<K,V>` | `{ [P in K]: V }` | 비동형 |

`Omit<T, K>` 은 mapped type 이 아니라 `Pick<T, Exclude<keyof T, K>>` 조합이라 동형성이 깨진다. 이 때문에 `Omit` 이 유니온에 잘 분배되지 않는다.

## 6. 실무 적용과 성능 고려

깊은 재귀 mapped type(`DeepReadonly`)을 대형 타입에 적용하면 컴파일러 인스턴스화가 폭증한다. `tsc --extendedDiagnostics` 의 `Instantiations` 로 관측하고, 재귀 깊이 제한 카운터, 결과 명시 인터페이스 고정, key remapping 필터 남용 자제로 완화한다. 공개 유틸리티는 원본 계약(readonly 등) 존중을 위해 동형 형태를 유지해야 한다.

## 7. 검증 예시

타입 로직은 컴파일 타임 단언으로 검증한다.

```typescript
type Equals<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Assert<T extends true> = T;
interface Src { readonly id: number; name?: string }
type _t1 = Assert<Equals<Mutable<Src>, { id: number; name?: string }>>;
type _t2 = Assert<Equals<Concrete<Src>, { readonly id: number; name: string }>>;
```

CI 에서 이 파일을 `tsc --noEmit` 대상에 포함해 회귀를 막는다.

## 참고

- TypeScript Handbook — Mapped Types
- TypeScript Handbook — Key Remapping via `as`
- TypeScript 4.1 Release Notes — Key Remapping in Mapped Types
- `lib.es5.d.ts` 표준 유틸리티 타입 정의 (microsoft/TypeScript)
