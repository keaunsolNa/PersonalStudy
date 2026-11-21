Notion 원본: https://www.notion.so/41f5a06fd6d381bfae69fc206b56b329

# TypeScript 인터섹션 타입 의미론: 프로퍼티 충돌과 never 축소, interface extends 와의 차이, 함수 인터섹션과 오버로드 순서

> 2026-11-21 신규 주제 · 확장 대상: Javascript(객체 병합·Object.assign), TypeScript(유니온·판별 유니온)

## 학습 목표

- 같은 이름의 프로퍼티가 충돌할 때 프로퍼티만 `never` 가 되는 경우와 객체 전체가 `never` 로 축소되는 경우를 구분한다
- `interface extends` 와 `&` 가 충돌을 다루는 방식의 차이를 컴파일 오류로 확인한다
- 함수 인터섹션이 오버로드로 해석되는 순서 규칙과 `ReturnType` 이 고르는 시그니처를 확인한다
- `Object.assign` 의 반환 타입 `T & U` 가 런타임 값과 어긋나는 지점을 재현하고 스프레드 타입과 비교한다

## 1. 인터섹션은 병합이 아니라 조건의 누적이다

`A & B` 는 "A 이면서 동시에 B 인 값" 의 집합이다. 객체 타입은 "이 프로퍼티들을 가진다" 는 조건이므로 조건 두 개를 함께 요구하면 프로퍼티가 합쳐진 것처럼 보인다. 이 때문에 인터섹션을 객체 병합 연산으로 기억하기 쉬운데, 그렇게 기억하면 같은 이름의 프로퍼티가 양쪽에 있을 때의 동작을 잘못 예측한다. 병합이라면 뒤쪽이 앞쪽을 덮어쓸 것이고, 조건의 누적이라면 두 조건을 모두 만족하는 값만 남는다. TypeScript 는 후자다.

아래 코드는 모두 `strict` 옵션에서 `tsc --noEmit` 으로 검사했다. `Equal` 은 두 타입이 동일한지 판정하는 보조 타입이고, `const e: Equal<X, Y> = true` 가 오류 없이 통과하면 X 와 Y 가 같은 타입이라는 뜻이다.

```typescript
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

type A = { id: string; name: string };
type B = { id: number; age: number };
type AB = A & B;
type Id = AB["id"];
const e1: Equal<Id, never> = true;
const e2: Equal<AB, never> = false;
const ab: AB = { id: "x", name: "n", age: 1 };
```

```text
inter.ts(11,18): error TS2322: Type 'string' is not assignable to type 'never'.
```

`AB["id"]` 는 `string & number`, 곧 `never` 다. 그런데 `AB` 자체는 `never` 가 아니다(`e2` 가 `false` 로 통과). 타입은 존재하지만 `id` 에 넣을 수 있는 값이 없어서, 객체 리터럴로는 만들 수 없는 타입이 된다. 오류 위치도 `AB` 선언이 아니라 값을 만드는 11행이다. 선언 시점에는 아무 경고가 없으므로, 서로 다른 모듈의 타입을 `&` 로 묶은 뒤 한참 뒤에야 "never 에 할당할 수 없다" 는 오류를 만나게 된다.

## 2. 판별 프로퍼티가 충돌하면 객체 전체가 never 가 된다

충돌하는 프로퍼티가 리터럴 타입 같은 단위 타입이면 결과가 달라진다.

```typescript
type Circle = { kind: "circle"; r: number };
type Square = { kind: "square"; side: number };
type CS = Circle & Square;
const e3: Equal<CS, never> = true;

type Loose = { kind: string; r: number } & { kind: number };
const e4: Equal<Loose, never> = false;

type BoolTag = { ok: true; v: number } & { ok: false; e: string };
const e5: Equal<BoolTag, never> = true;
```

`e3`, `e4`, `e5` 는 모두 오류 없이 통과했다. `Circle & Square` 는 `kind` 가 `"circle" & "square"` 가 되어 값이 존재할 수 없고, 이 경우 TypeScript 는 프로퍼티만이 아니라 객체 타입 전체를 `never` 로 축소한다. TypeScript 3.9 릴리스 노트의 "Intersections Reduced By Discriminant Properties" 항목이 이 동작이다. 반면 `string` 과 `number` 처럼 단위 타입이 아닌 경우(`Loose`)는 1절과 같이 프로퍼티만 `never` 로 남는다.

이 차이가 판별 유니온에서 의미를 가진다. 유니온에 인터섹션을 적용하면 각 구성원에 분배되는데, 판별 프로퍼티가 맞지 않는 구성원은 `never` 가 되어 유니온에서 사라진다.

```typescript
type Shape = (Circle | Square) & { kind: "circle" };
declare const sh: Shape;
const rr: number = sh.r;
```

`Shape` 는 `(Circle & { kind: "circle" }) | (Square & { kind: "circle" })` 로 분배되고 뒤쪽이 `never` 로 축소되어 `Circle` 만 남는다. 그래서 `sh.r` 접근이 통과한다. `Extract<Shape, { kind: "circle" }>` 과 같은 결과를 내는 다른 경로다. 축소 규칙이 없었다면 뒤쪽 구성원이 "kind 가 never 인 Square" 로 남아 `sh.r` 이 오류가 됐을 것이다.

## 3. interface extends 는 같은 충돌을 선언 지점에서 막는다

```typescript
interface IA { id: string }
interface IB extends IA { id: number }
interface IC extends IA { id: "fixed" }
```

```text
inter.ts(25,11): error TS2430: Interface 'IB' incorrectly extends interface 'IA'.
  Types of property 'id' are incompatible.
    Type 'number' is not assignable to type 'string'.
```

`extends` 는 하위 인터페이스의 프로퍼티가 상위 프로퍼티에 할당 가능해야 한다는 검사를 한다. `IB` 는 선언에서 바로 오류가 나고, `IC` 처럼 더 좁은 타입(`"fixed"` 는 `string` 의 서브타입)으로 다시 선언하는 것만 허용된다. 같은 충돌을 `&` 로 쓰면 1절처럼 조용히 `never` 프로퍼티가 된다.

| 구분 | `interface X extends A` | `type X = A & B` |
|---|---|---|
| 프로퍼티 타입 충돌 | 선언에서 TS2430 | 오류 없음, 프로퍼티가 `never` |
| 판별 프로퍼티 충돌 | 선언에서 TS2430 | 오류 없음, 전체가 `never` |
| 좁은 타입으로 재선언 | 허용 | 인터섹션 결과가 좁은 타입 |
| 유니온을 대상으로 사용 | 불가(객체 타입만 확장) | 가능, 구성원에 분배 |

충돌을 일찍 알고 싶은 계층 구조는 `interface extends` 가 맞고, 유니온을 좁히거나 제네릭 타입 매개변수에 조건을 덧붙이는 용도는 `&` 만 할 수 있다. TypeScript 위키의 Performance 문서는 객체 타입 합성에 인터섹션보다 `interface extends` 를 권하는데, 인터페이스는 평탄한 단일 객체 타입으로 캐시되고 인터섹션은 프로퍼티를 확인할 때마다 구성원을 다시 합쳐야 한다는 것이 근거로 적혀 있다.

## 4. 수정자는 느슨한 쪽이 아니라 엄격한 쪽을 따른다

```typescript
type Opt = { a?: string } & { a: string };
type Ro = { readonly b: number } & { b: number };
declare const opt: Opt;
declare const ro: Ro;
const s: string = opt.a;
ro.b = 2;
```

두 줄 모두 오류 없이 통과했다. 한쪽이 선택, 다른 쪽이 필수이면 결과는 필수다. 값이 두 조건을 모두 만족해야 하므로 "있어도 되고 없어도 된다" 와 "반드시 있다" 의 교집합은 "반드시 있다" 이다. `readonly` 는 반대 방향처럼 보인다. 한쪽이 `readonly` 여도 다른 쪽이 쓰기를 허용하면 결과는 쓰기 가능이다. `readonly` 는 "이 참조로는 쓰지 않는다" 는 제한일 뿐 값의 조건이 아니어서, 쓰기 가능한 구성원을 통해 쓰는 것이 허용된다. 불변을 기대하고 `Readonly<T> & U` 를 만들면 `U` 쪽에 같은 이름의 프로퍼티가 있는 순간 보호가 풀린다.

## 5. 함수 인터섹션은 오버로드이고 순서가 결과를 바꾼다

```typescript
type F1 = ((x: string) => "s") & ((x: unknown) => "u");
type F2 = ((x: unknown) => "u") & ((x: string) => "s");
declare const f1: F1;
declare const f2: F2;
const r1 = f1("a");
const r2 = f2("a");
const e6: Equal<typeof r1, "s"> = true;
const e7: Equal<typeof r2, "u"> = true;
const e8: Equal<ReturnType<F1>, "u"> = true;
```

세 줄 모두 통과했다. 함수 타입의 인터섹션은 호출 시그니처를 구성원 순서대로 나열한 오버로드 집합으로 다뤄진다. 호출하면 앞에서부터 인자가 맞는 첫 시그니처가 선택되므로 `F1` 은 `"s"`, `F2` 는 `"u"` 를 돌려준다. 집합론으로는 `A & B` 와 `B & A` 가 같아야 하지만 함수에서는 교환법칙이 성립하지 않는다. 넓은 시그니처를 앞에 두면 뒤의 좁은 시그니처는 도달할 수 없다.

`e8` 은 다른 함정이다. `ReturnType` 처럼 조건부 타입의 `infer` 로 오버로드 함수에서 타입을 뽑으면 마지막 시그니처가 기준이 된다. `f1("a")` 의 실제 결과 타입은 `"s"` 인데 `ReturnType<F1>` 은 `"u"` 다. 이벤트 핸들러 맵이나 라이브러리의 오버로드된 함수에 `Parameters`, `ReturnType` 을 적용할 때 결과가 호출 결과와 다를 수 있다는 뜻이다.

## 6. 원시 타입과 유니온에서의 정규화

```typescript
type U = (string | number | null) & {};
const e9: Equal<U, string | number> = true;
type P = string & number;
const e10: Equal<P, never> = true;
type Lit = ("a" | "b" | "c") & ("b" | "c" | "d");
const e11: Equal<Lit, "b" | "c"> = true;
type Brand = string & { readonly __brand: "UserId" };
const e12: Equal<Brand, never> = false;
```

모두 통과했다. `{}` 는 `null` 과 `undefined` 를 제외한 모든 값을 받는 타입이라 `T & {}` 는 `null` 과 `undefined` 를 걸러 낸다. 표준 라이브러리의 `NonNullable<T>` 이 TypeScript 4.8 부터 `T & {}` 로 정의된 이유다. 서로 다른 원시 타입끼리는 즉시 `never` 가 되고, 리터럴 유니온끼리는 공통 원소만 남는다. `Brand` 는 원시 타입과 객체 타입의 인터섹션인데 `never` 로 축소되지 않는다. 실제로 그런 값을 만들 수는 없지만 타입 검사기는 이 조합을 살려 두며, 브랜디드 타입 기법이 여기에 기댄다.

## 7. Object.assign 의 반환 타입이 런타임과 어긋나는 지점

```typescript
const merged = Object.assign({ v: "text" }, { v: 1 });
type M = typeof merged["v"];
const e13: Equal<M, never> = true;

const spread = { ...{ v: "text" }, ...{ v: 1 } };
const e14: Equal<typeof spread, { v: number }> = true;
```

```text
> const m = Object.assign({ v: "text" }, { v: 1 }); console.log(m, typeof m.v)
{ v: 1 } number
```

표준 라이브러리는 `Object.assign(target: T, source: U): T & U` 로 선언돼 있다. 런타임은 뒤쪽 값으로 덮어써서 `v` 가 `1` 인데, 타입은 `string & number` 곧 `never` 다. `merged.v` 를 쓰는 코드는 어떤 타입에도 할당되므로 오류 없이 컴파일되고, 런타임에는 숫자가 흘러 들어간다. 스프레드 문법은 컴파일러가 덮어쓰기 의미를 따로 구현하고 있어 `{ v: number }` 로 정확히 추론한다. 같은 이름의 키가 겹칠 수 있는 병합은 스프레드로 쓰는 편이 타입과 런타임이 일치한다. 제네릭 함수 안에서는 스프레드 결과도 `T & U` 로 추론되므로, 덮어쓰기를 타입에 반영하려면 `Omit<T, keyof U> & U` 를 반환 타입으로 직접 적어야 한다.

## 8. 인덱스 시그니처와 묶을 때

```typescript
type Dict = { [k: string]: number } & { label: string };
declare const d: Dict;
const lab: string = d.label;
const other: number = d.anything;
const bad: Dict = { label: "x", n: 1 };
```

```text
inter.ts(70,7): error TS2322: Type '{ label: string; n: number; }' is not assignable to type 'Dict'.
  Type '{ label: string; n: number; }' is not assignable to type '{ [k: string]: number; }'.
    Property 'label' is incompatible with index signature.
      Type 'string' is not assignable to type 'number'.
```

읽을 때는 편하다. 알려진 키 `label` 은 `string`, 나머지는 `number` 로 나온다. 그러나 이 타입의 값을 객체 리터럴로 만들 수는 없다. `label` 이 인덱스 시그니처의 `number` 조건도 만족해야 하기 때문이다. "특정 키 몇 개와 나머지 임의 키" 구조를 이 방식으로 표현하면 읽는 쪽은 통과하고 만드는 쪽은 단언이 필요해진다. 값을 직접 만들어야 하는 타입이라면 임의 키 부분을 별도 프로퍼티(`{ label: string; values: Record<string, number> }`)로 분리하는 편이 단언 없이 끝난다.

## 9. 실무 판단

- 서로 다른 출처의 객체 타입을 `&` 로 묶기 전에 겹치는 키가 있는지 확인한다. `keyof A & keyof B` 가 `never` 가 아니면 충돌 후보가 있다.
- 계층 관계는 `interface extends` 로 선언해 충돌을 선언 지점의 오류로 받는다.
- 유니온을 좁히는 용도의 `&` 는 판별 프로퍼티가 리터럴 타입일 때만 구성원이 제거된다. `kind: string` 처럼 넓게 선언된 타입에서는 제거되지 않는다.
- 오버로드된 함수 타입에 `ReturnType`, `Parameters` 를 쓰면 마지막 시그니처가 나온다.
- 덮어쓰기가 있는 병합에 `Object.assign` 의 반환 타입을 그대로 믿지 않는다.

검증 환경: TypeScript 7.0.2 에서 `strict: true`, `target: es2022`, `module: nodenext` 로 `tsc --noEmit` 을 실행했다. 본문의 코드는 한 파일(`inter.ts`)의 발췌이고, 인용한 오류 세 건이 그 파일에서 나온 오류의 전부다. 7절의 런타임 출력은 Node.js 22.23.5 에서 실행한 결과다. 3절의 성능 관련 서술은 TypeScript 위키 문서에 근거하며 직접 측정하지 않았다.

## 참고

- TypeScript Handbook, Object Types (Intersection Types, Interface Extension vs. Intersection): https://www.typescriptlang.org/docs/handbook/2/objects.html
- TypeScript 3.9 릴리스 노트 (Intersections Reduced By Discriminant Properties): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-9.html
- TypeScript 4.8 릴리스 노트 (Improved Intersection Reduction, `NonNullable<T>` 의 `T & {}` 정의): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- TypeScript Wiki, Performance (Preferring Interfaces Over Intersections): https://github.com/microsoft/TypeScript/wiki/Performance
- TypeScript 소스, `lib.es2015.core.d.ts` (`ObjectConstructor.assign` 선언): https://github.com/microsoft/TypeScript/blob/main/src/lib/es2015.core.d.ts
- TypeScript Handbook, Function Overloads: https://www.typescriptlang.org/docs/handbook/2/functions.html#function-overloads
