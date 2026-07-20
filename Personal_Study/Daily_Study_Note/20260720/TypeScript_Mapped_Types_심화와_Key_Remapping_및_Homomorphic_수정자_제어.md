Notion 원본: https://www.notion.so/3a35a06fd6d38142bc26f9a9413218b7

# TypeScript Mapped Types 심화와 Key Remapping 및 Homomorphic 수정자 제어

> 2026-07-20 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- Mapped Type 의 문법 구조를 분해하고 `keyof` 순회가 컴파일러 내부에서 어떻게 처리되는지 파악한다
- Homomorphic mapped type 이 수정자(modifier)와 배열/튜플 구조를 보존하는 조건을 구분한다
- `as` 절 key remapping 으로 키를 필터링·재명명하는 실전 유틸리티를 작성한다
- `+`/`-` 수정자와 `--strict` 조합에서 발생하는 추론 함정을 진단한다

## 1. Mapped Type 의 본질과 문법 분해

Mapped type 은 "기존 타입의 키를 순회하며 각 프로퍼티를 변환해 새 객체 타입을 만드는" 타입 수준의 for 루프다. 문법은 `{ [P in K]: T }` 한 줄로 압축되지만, 실제로는 네 개의 독립된 축이 겹쳐 있다. 순회 대상 유니온 `K`, 순회 변수 `P`, 선택적으로 키를 다시 쓰는 `as NewKey` 절, 그리고 값 타입 `T` 다. 여기에 프로퍼티 수정자 `readonly` 와 `?` 가 앞뒤로 붙는다.

```typescript
type MyPartial<T> = {
  [P in keyof T]?: T[P];
};

type MyReadonly<T> = {
  readonly [P in keyof T]: T[P];
};
```

`keyof T` 는 `string | number | symbol` 의 부분집합인 유니온을 만든다. 컴파일러는 이 유니온을 순회하며 각 리터럴 키에 대해 `T[P]` 를 인덱스 액세스로 평가한다. 중요한 점은 mapped type 의 순회 대상이 반드시 `keyof` 여야 하는 것은 아니라는 것이다. 임의의 `string` 서브타입 유니온을 넣을 수 있고, 이 차이가 뒤에서 다룰 homomorphic 여부를 가른다.

```typescript
type Flags = {
  [K in 'read' | 'write' | 'execute']: boolean;
};
// { read: boolean; write: boolean; execute: boolean }
```

## 2. Homomorphic Mapped Type 이란 무엇인가

TypeScript 컴파일러는 mapped type 을 두 부류로 나눈다. 순회 대상이 `keyof T` 형태(즉 `{ [P in keyof T]: ... }`)이면 이를 **homomorphic**(동형) mapped type 이라 부른다. 반대로 `{ [P in SomeUnion]: ... }` 처럼 독립적인 유니온을 순회하면 non-homomorphic 이다. 이 구분은 단순한 용어 문제가 아니라 컴파일러의 실제 동작을 바꾼다.

Homomorphic mapped type 은 원본 타입 `T` 의 구조적 특성을 보존한다. 각 프로퍼티의 `readonly`/`?` 수정자가 그대로 복사되고, `T` 가 배열이면 결과도 배열이 되고 튜플이면 튜플 길이가 유지되며, `T` 가 유니온이면 mapped type 이 유니온의 각 구성원에 분배된다.

```typescript
interface User {
  readonly id: number;
  name?: string;
}

type Clone<T> = { [P in keyof T]: T[P] };
type C = Clone<User>;
// { readonly id: number; name?: string } — 수정자 보존

type ArrClone = Clone<number[]>;
// number[] — 배열 구조 보존 (객체가 아님)
```

만약 `Clone` 을 `{ [P in keyof T & string]: T[P] }` 처럼 `keyof T` 에 교집합을 걸어 변형하면 homomorphic 성질이 깨지면서 배열이 평범한 객체로 붕괴한다. 이 미묘한 차이가 라이브러리 유틸리티 버그의 흔한 원인이다.

## 3. 수정자 추가/제거: `+`, `-` 연산자

수정자는 명시적으로 붙이거나 뗄 수 있다. `readonly` 와 `?` 앞에 `+` 또는 `-` 를 두어 제어한다.

```typescript
type Mutable<T> = {
  -readonly [P in keyof T]: T[P];
};

type Required2<T> = {
  [P in keyof T]-?: T[P];
};
```

표준 라이브러리의 `Partial`, `Required`, `Readonly` 는 모두 이 문법으로 구현되어 있다. `Required<T>` 가 `-?` 를 쓰는 이유는 옵셔널 표시를 제거하는 동시에 `undefined` 를 값 유니온에서 빼기 위해서다. 다만 `-?` 는 `undefined` 를 제거하지만, 값 자체에 명시적으로 `| undefined` 가 포함된 경우는 건드리지 않는다.

| 수정자 표현 | 의미 | 표준 유틸리티 |
|---|---|---|
| `?` 또는 `+?` | 옵셔널 추가 | `Partial<T>` |
| `-?` | 옵셔널 제거 + `undefined` 제거 | `Required<T>` |
| `readonly` 또는 `+readonly` | 읽기 전용 추가 | `Readonly<T>` |
| `-readonly` | 읽기 전용 제거 | `Mutable<T>` (커스텀) |

## 4. `as` 절 Key Remapping

TypeScript 4.1 에서 도입된 `as` 절은 순회 중 키를 다른 타입으로 다시 매핑한다. 반환 타입이 `never` 이면 해당 키는 결과에서 제외된다. 이 두 성질(재명명 + 필터링)이 key remapping 의 전부이며, 조합만으로 강력한 유틸리티가 나온다.

```typescript
type OmitByValue<T, V> = {
  [P in keyof T as T[P] extends V ? never : P]: T[P];
};

interface Mixed {
  id: number;
  name: string;
  createdAt: Date;
  updatedAt: Date;
}
type NoDates = OmitByValue<Mixed, Date>;
// { id: number; name: string }
```

키 재명명은 template literal type 과 결합해 getter 생성 같은 실전 코드를 만든다.

```typescript
type Getters<T> = {
  [P in keyof T as `get${Capitalize<string & P>}`]: () => T[P];
};

interface Person {
  name: string;
  age: number;
}
type PersonGetters = Getters<Person>;
// { getName: () => string; getAge: () => number }
```

주의할 점은 `as` 절을 사용하는 순간 mapped type 이 non-homomorphic 로 취급될 수 있다는 것이다. 키를 재명명하면 원본과 키 집합이 달라지므로 수정자 자동 보존이 보장되지 않는다.

## 5. 유니온 키 병합과 충돌 처리

`as` remapping 이 서로 다른 원본 키를 같은 결과 키로 매핑하면 값 타입이 유니온으로 병합된다.

```typescript
type PickBySuffix<T, S extends string> = {
  [P in keyof T as P extends `${string}${S}` ? P : never]: T[P];
};

interface Entity {
  userId: number;
  orderId: number;
  name: string;
}
type OnlyIds = PickBySuffix<Entity, 'Id'>;
// { userId: number; orderId: number }
```

대규모 도메인 모델에서 특정 접미사를 가진 필드만 추출하는 데 유용하다.

## 6. 실전: 깊은 변환과 재귀 mapped type

```typescript
type DeepPartial<T> = T extends (...args: any[]) => any
  ? T
  : T extends readonly (infer U)[]
    ? readonly DeepPartial<U>[]
    : T extends object
      ? { [P in keyof T]?: DeepPartial<T[P]> }
      : T;
```

함수 타입을 먼저 걸러내지 않으면 `{ [P in keyof T]?: ... }` 가 함수의 프로퍼티(`length`, `name` 등)까지 순회해 실용성이 사라진다. 이 순서(함수 → 배열 → 객체 → 원시)가 실무 안전 패턴이다.

## 7. 성능과 인스턴스화 깊이 한계

Mapped type 은 값 타입마다 인덱스 액세스와 조건부 평가를 수행하므로, 넓은 유니온 키나 깊은 재귀와 결합되면 컴파일 시간이 급증한다. TypeScript 는 인스턴스화 깊이 한계(약 50 단계)와 인스턴스화 횟수 한계(약 5,000,000)를 두어 무한 재귀를 차단한다. `type` 별칭은 지연 평가되어 사용처마다 재계산되지만, `interface` 로 물질화하면 컴파일러가 캐시할 수 있다.

```typescript
interface Config extends DeepReadonly<HugeRawConfig> {}
```

## 8. 흔한 함정 정리

`as` 절로 키를 재명명하는 순간 homomorphic 성질이 사라져 `readonly`/`?` 가 자동 승계되지 않는다. `keyof T` 가 `string | number | symbol` 을 포함하므로 template literal 과 결합할 때 `string & P` 로 좁히지 않으면 `number`/`symbol` 키에서 에러가 난다. `-?` 는 `undefined` 를 제거하지만 값에 명시된 `| null` 은 건드리지 않는다. mapped type 안에서 클래스 메서드 오버로드가 뭉개질 수 있다. 이 네 가지는 유틸리티 타입 라이브러리를 직접 만들 때 반드시 테스트로 고정해야 하는 지점이다.

## 참고

- TypeScript Handbook — Mapped Types (https://www.typescriptlang.org/docs/handbook/2/mapped-types.html)
- TypeScript Handbook — Key Remapping via `as`
- TypeScript 4.1 Release Notes — Key Remapping in Mapped Types
- microsoft/TypeScript Wiki — Performance (Instantiation depth and caching)
