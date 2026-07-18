Notion 원본: https://www.notion.so/3a15a06fd6d381979dafd4d1c0626f7e

# TypeScript Conditional Types와 infer 및 분배 조건부 타입 제어

> 2026-07-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 조건부 타입이 즉시 평가되는 경우와 지연(deferred)되는 경우를 컴파일러 규칙 수준에서 구분한다.
- naked type parameter 규칙과 `[T] extends [U]` 우회로 union 분배를 의도대로 제어한다.
- `infer` 의 공변/반공변 위치 차이가 union·intersection 을 만드는 메커니즘을 코드로 재현한다.
- 재귀 조건부 타입의 깊이 한계와 instantiation 폭발을 `--extendedDiagnostics` / `--generateTrace` 로 진단한다.

## 1. 조건부 타입의 평가 규칙과 지연 해석

`T extends U ? X : Y` 는 삼항 연산자를 닮았지만 값이 아니라 타입 관계(assignability)를 판정한다. Java 의 `T extends Number` 가 nominal 상한 제약인 반면, TypeScript 의 `extends` 는 여기서 관계 연산자로 승격되어 "T 가 U 에 대입 가능한가"라는 구조적 subtyping 판정을 수행한다.

핵심은 컴파일러가 이 판정을 **언제** 하느냐다. 체크 타입이나 확장 타입에 미해결 타입 파라미터가 남아 있으면 판정을 미루고 조건부 타입 노드 자체를 결과로 남긴다. 이것이 deferred conditional type 이다.

```ts
type IsString<T> = T extends string ? "yes" : "no";

type A = IsString<"hello">; // "yes" — 즉시 평가
type B = IsString<42>;      // "no"

function probe<T>(value: T): IsString<T> {
  // return "yes"; // Error: '"yes"' is not assignable to 'IsString<T>'
  // return "no";  // Error 동일 — 컴파일러는 T 를 모른다
  return (typeof value === "string" ? "yes" : "no") as IsString<T>;
}
```

지연된 조건부 타입은 미지수로 취급되므로 컴파일러가 `IsString<T>` 를 `"yes" | "no"` 로 낙관적으로 좁혀주지 않는다. 반면 이를 **소비**하는 쪽은 규칙이 다르다. 조건부 타입의 기본 제약(default constraint)이 두 분기의 union 으로 계산되므로 `IsString<T>` 를 `"yes" | "no"` 파라미터에 넘기는 것은 허용된다. 즉 **지연 조건부 타입은 반환 위치에서 불투명하고 인자 위치에서 union 으로 완화된다.** 이 비대칭이 `as` 캐스트를 강제하는 대부분의 원인이다. 반환 타입을 조건부로 계산하는 API 는 구현부 단언을 피할 수 없으므로, 단언을 한 곳으로 몰고 오버로드 시그니처로 외부 계약을 고정하는 편이 안전하다.

반드시 기억할 예외는 `any` 다. 체크 타입이 `any` 면 조건이 양쪽으로 성립할 수 있어 컴파일러는 **두 분기의 union** 을 반환한다. `any` 로 오염된 값이 타입 레벨 분기를 통과할 때 조용히 두 배로 퍼진다는 뜻이다.

```ts
type C = IsString<any>;     // "yes" | "no"
type D = IsString<never>;   // never   ← 이유는 2장
type E = IsString<unknown>; // "no"
```

## 2. 분배 조건부 타입과 naked type parameter

조건부 타입의 체크 타입이 **naked type parameter** — 아무것도 감싸지 않은 순수 타입 파라미터 — 이고 그 자리에 union 이 인스턴스화되면, 컴파일러는 union 의 각 구성원에 조건부를 나눠 적용한 뒤 결과를 다시 union 으로 합친다. `[T] extends [U]` 로 튜플에 감싸면 체크 타입이 naked 가 아니므로 분배가 꺼진다. 이 스위치를 의식하지 않으면 의도와 정반대 결과를 얻는다. 특히 표의 마지막 두 행이 실전적인 대비다. 분배가 켜지면 조건을 만족하는 구성원만 골라내는 필터로 동작하지만, 끄면 union 전체가 한 덩어리로 판정되어 `string | number` 가 `string` 에 대입 불가하므로 `never` 가 된다.

| 표현식 | 분배 | `T = string \| number` 결과 |
| --- | --- | --- |
| `T extends any ? T[] : never` | 켜짐 | `string[] \| number[]` |
| `[T] extends [any] ? T[] : never` | 꺼짐 | `(string \| number)[]` |
| `T[] extends any[] ? T : never` | 꺼짐 (naked 아님) | `string \| number` |
| `T extends string ? T : never` | 켜짐 | `string` |
| `[T] extends [string] ? T : never` | 꺼짐 | `never` |

가장 악명 높은 함정은 `never` 다. `never` 는 **구성원이 0개인 union** 이므로 분배 대상이 되는 순간 반복할 대상이 없어 결과도 `never` 다. 조건부 본문은 아예 실행되지 않는다.

```ts
type Wrap<T> = T extends any ? { value: T } : never;
type W2 = Wrap<never>; // never  ← { value: never } 가 아니다

type IsNeverWrong<T> = T extends never ? true : false;
type X1 = IsNeverWrong<never>; // never  ← true 가 아님

type IsNever<T> = [T] extends [never] ? true : false;
type X2 = IsNever<never>;  // true
type X3 = IsNever<string>; // false
```

`never` 의 정체를 묻는 순간 분배가 질문 자체를 소멸시키므로 대괄호로 union 신분을 박탈해야 답을 얻는다. `IsNever` 가 타입 레벨 라이브러리의 사실상 표준인 이유다. `boolean` 도 조용한 함정이다. 내부적으로 `true | false` union 이라 분배 대상이며, `type ToArray<T> = T extends unknown ? T[] : never` 에 `boolean` 을 넣으면 `boolean[]` 이 아니라 `true[] | false[]` 가 나온다. `null`/`undefined` 도 각각의 구성원이므로 `strictNullChecks` 환경의 유틸리티는 진입점에서 `NonNullable` 로 정규화해야 한다.

## 3. infer 의 위치와 공변·반공변 추론 규칙

`infer` 는 조건부 타입의 `extends` 절 안에서만 쓸 수 있는 선언으로, "이 자리에 들어올 타입을 포획하라"는 지시다. 컴파일러는 두 타입을 구조적으로 매칭하며 후보(candidate)를 수집한다. 핵심은 **같은 이름의 `infer` 가 여러 위치에 나타날 때의 병합 규칙**이며, 이는 위치의 변성(variance)에 따라 갈린다. 공변(covariant) 위치 — 프로퍼티, 반환 타입, 배열 원소 — 의 후보는 **union** 으로, 반공변(contravariant) 위치 — 함수 파라미터 — 의 후보는 **intersection** 으로 병합된다.

```ts
type CoInfer<T> = T extends { a: infer U; b: infer U } ? U : never;
type C1 = CoInfer<{ a: string; b: number }>; // string | number

type ContraInfer<T> = T extends {
  a: (x: infer U) => void;
  b: (x: infer U) => void;
} ? U : never;
type C2 = ContraInfer<{ a: (x: string) => void; b: (x: number) => void }>;
// string & number
```

직관에 반하지만 타당하다. 두 함수 모두에 안전하게 넘길 수 있는 값은 `string` 이면서 동시에 `number` 인 값뿐이므로 intersection 이 정답이고, 두 프로퍼티에서 읽어낸 값을 담을 타입은 둘 중 무엇이든 될 수 있으니 union 이 정답이다. Java 의 `? super T` / `? extends T` 와 같은 방향의 논리이며, TypeScript 는 이를 추론 엔진 규칙으로 자동 적용한다. 이 규칙을 무기로 쓰는 것이 `UnionToIntersection` 이다.

```ts
type UnionToIntersection<U> =
  (U extends unknown ? (k: U) => void : never) extends (k: infer I) => void
    ? I
    : never;

type U1 = UnionToIntersection<{ a: 1 } | { b: 2 }>; // { a: 1 } & { b: 2 }
```

동작을 분해하면, 첫째 `U extends unknown ? (k: U) => void : never` 는 naked 이므로 분배되어 `((k: {a:1}) => void) | ((k: {b:2}) => void)` 가 된다. 둘째 이 함수 union 을 `(k: infer I) => void` 에 매칭하면 `I` 후보가 파라미터(반공변) 위치에서 두 개 수집된다. 셋째 반공변 규칙에 따라 intersection 으로 병합된다. 분배 규칙과 변성 기반 병합 규칙을 연쇄시킨 것으로, 둘 중 하나만 몰라도 마법처럼 보인다. 이를 응용한 `LastOf`/`UnionToTuple` 도 같은 원리지만, union 구성원의 순서는 컴파일러 내부 표현에 의존하는 미명세 동작이라 프로덕션 계약이 순서에 의존하면 마이너 업그레이드에서 조용히 깨진다.

## 4. constrained infer 와 리터럴 추론 제어

TypeScript 4.8 부터 `infer X extends C` 형태의 제약 있는 추론이 가능하다. 이전에는 추론 후 중첩 조건부로 검증해야 했던 패턴이 한 줄로 줄어들고, 제약 불만족 시 그 자리에서 매칭이 실패한다.

```ts
// 4.8 이전: T extends [infer H, ...unknown[]] ? (H extends string ? H : never) : never
// 4.8 이후 — 추론 자리에서 제약
type FirstStr<T> = T extends [infer H extends string, ...unknown[]] ? H : never;
type S1 = FirstStr<["a", 1, true]>; // "a"
type S2 = FirstStr<[1, "a"]>;       // never

// 템플릿 리터럴과 결합 시 리터럴 재해석
type ParseInt<S extends string> = S extends `${infer N extends number}` ? N : never;
type P1 = ParseInt<"42">;  // 42   ← number 리터럴 타입
type P2 = ParseInt<"abc">; // never
```

제약이 없으면 `${infer N}` 은 항상 `string` 을 추론한다. 제약을 붙여야 컴파일러가 문자열 리터럴을 해당 원시 타입의 리터럴로 재해석한다. 다만 이는 문자열→숫자 변환의 완전한 시뮬레이션이 아니다. `"1e3"`, `"0x10"`, 선행 `+` 같은 표기는 기대와 다르게 동작할 수 있으므로 **컴파일 시점에 알려진 리터럴** 에만 적용해야 한다. 같은 원리를 재귀와 엮으면 라우트 파라미터를 뽑아낼 수 있다.

```ts
type PathParams<S extends string> =
  S extends `${string}:${infer P}/${infer Rest}`
    ? P | PathParams<`/${Rest}`>
    : S extends `${string}:${infer P}` ? P : never;

type Params = PathParams<"/users/:userId/orders/:orderId">; // "userId" | "orderId"
type Obj = { [K in PathParams<"/users/:userId">]: string };  // { userId: string }
```

`PathParams<string>` — 리터럴이 아닌 넓은 `string` — 은 `never` 를 낸다. 런타임에 조립된 경로는 리터럴성을 잃어 보호를 전혀 받지 못하므로 `as const` 가 전제 조건이다.

## 5. 재귀 조건부 타입과 tail-recursion elimination

TypeScript 4.1 에서 조건부 타입의 자기 참조 재귀가 공식 지원되며 타입 레벨 리스트 처리가 가능해졌다. 그러나 재귀는 곧 깊이 한계와의 싸움이다. 컴파일러는 무한 인스턴스화를 막기 위해 인스턴스화 깊이와 누적 횟수에 상한을 두고, 초과 시 `TS2589: Type instantiation is excessively deep and possibly infinite` 를 낸다. TypeScript 4.5 는 **tail-recursion elimination** 을 도입해, 분기 결과가 곧바로 또 다른 조건부 타입인 꼬리 재귀를 스택 대신 반복문으로 펼친다. 핵심은 **누산기(accumulator)를 타입 파라미터로 넘겨 꼬리 위치를 확보하는 것**으로, 함수형 언어에서 익숙한 패턴 그대로다.

```ts
// 꼬리 재귀 아님 — 결과 조립이 재귀 바깥에 남는다
type ReverseNaive<T extends unknown[]> =
  T extends [infer H, ...infer Rest] ? [...ReverseNaive<Rest>, H] : [];

// 꼬리 재귀 — 누산기를 넘기고 재귀 호출이 곧 결과
type Reverse<T extends unknown[], Acc extends unknown[] = []> =
  T extends [infer H, ...infer Rest] ? Reverse<Rest, [H, ...Acc]> : Acc;

type R = Reverse<[1, 2, 3]>; // [3, 2, 1]
```

짧은 튜플에서는 둘 다 동작하지만 길이가 수십을 넘으면 `ReverseNaive` 가 먼저 `TS2589` 로 무너지고 `Reverse` 는 훨씬 긴 입력을 견딘다. 꼬리 재귀라도 무한하지 않으며 컴파일러는 별도 반복 상한으로 차단한다. 정확한 임계값은 버전과 타입 복잡도에 따라 달라지므로, 특정 숫자를 계약으로 삼는 대신 **입력 길이 상한을 정하고 테스트로 고정** 하는 방어가 현실적이다.

```ts
type Expect<T extends true> = T;
type Equals<A, B> =
  (<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false;

type _t1 = Expect<Equals<Reverse<[1, 2, 3]>, [3, 2, 1]>>;
```

`Equals` 가 기묘한 이유도 조건부 타입 내부 동작 때문이다. 컴파일러는 두 조건부 타입을 비교할 때 체크·확장 타입의 **내부 동일성(identity)** 을 보므로, 제네릭 시그니처로 감싸면 일반 대입 검사로는 구분되지 않는 `any` 대 `unknown` 이나 union 순서 차이까지 갈라낸다. 한편 성능을 지키는 가장 강력한 수단은 **재귀를 아예 하지 않는 것**이다. 튜플 길이 산술은 `T["length"]` 로, 슬라이싱은 가변 튜플 패턴으로 재귀 없이 처리된다.

## 6. 표준 유틸리티 타입의 실제 정의

`lib.es5.d.ts` 는 조건부 타입과 `infer` 의 최고의 교과서다. 매일 쓰는 유틸리티들이 정확히 이 두 기능만으로 구현되어 있다.

```ts
type Exclude<T, U> = T extends U ? never : T;
type Extract<T, U> = T extends U ? T : never;

type Parameters<T extends (...args: any) => any> =
  T extends (...args: infer P) => any ? P : never;

type ReturnType<T extends (...args: any) => any> =
  T extends (...args: any) => infer R ? R : any;
```

`Exclude`/`Extract` 가 동작하는 유일한 이유는 분배 규칙이다. `Exclude<"a" | "b" | "c", "a">` 는 세 번 분배되어 `never | "b" | "c"` 가 되고, union 에서 `never` 는 흡수 원소이므로 사라져 `"b" | "c"` 만 남는다. 2장의 "`never` 는 empty union" 이라는 성질이 여기서는 기능으로 쓰인다.

`ReturnType` 의 false 분기가 `never` 가 아니라 `any` 인 것은 의도적이다. 제약을 통과한 이상 도달 불가능한 분기지만, 지연 평가 중에도 합리적인 constraint 를 갖도록 `any` 를 둔다. 반면 `Parameters` 의 false 분기는 `never` 다. 반환 위치와 인자 위치의 트레이드오프를 다르게 판단한 결과다. `NonNullable` 은 TypeScript 4.8 에서 `T extends null | undefined ? never : T` 라는 조건부 정의를 버리고 `type NonNullable<T> = T & {}` 로 바뀌었다. `{}` 가 "null 도 undefined 도 아닌 모든 값"을 뜻하므로 intersection 만으로 nullish 를 걷어낸다. 실익은 조건부가 아니라서 제네릭 미해결 상태에서도 지연되지 않고 인스턴스화 비용이 싸다는 점, 부작용은 분배가 사라진다는 점이다. 한편 `Uppercase`/`Capitalize` 는 `type Uppercase<S extends string> = intrinsic;` 처럼 컴파일러 내장으로 선언되어 있고 TypeScript 5.4 의 `NoInfer<T>` 도 마찬가지다. 타입 시스템만으로 표현 불가능한 연산이 존재한다는 증거다.

## 7. 성능: instantiation 폭발의 진단과 통제

조건부 타입은 값이 아니라 **인스턴스화**를 만든다. 각 인스턴스화는 캐시되지만 캐시 키가 달라지는 만큼 새 타입 객체가 생긴다. N개 union 에 분배되는 조건부 안에서 다시 M개 union 에 분배되는 조건부를 호출하면 N×M 이 되고, 몇 단계만 쌓여도 IDE 응답이 느려진다.

```bash
tsc --noEmit --extendedDiagnostics          # 1차 진단
tsc --noEmit --generateTrace ./trace-out    # 2차 진단
npx @typescript/analyze-trace ./trace-out
```

`--extendedDiagnostics` 출력에서 주목할 항목은 `Instantiation count`, `Type Count`, `Check time` 이다. 컴파일러는 인스턴스화 수가 내부 상한을 넘으면 판정을 포기하고 `TS2589` 를 던지므로, 에러 전이라도 이 수치의 급증은 경고 신호다. `--generateTrace` 는 Chrome trace viewer 나 Perfetto UI 에서 열 수 있는 JSON 을 남기고, `@typescript/analyze-trace` 는 비용이 큰 체크 지점을 요약한다. 대개 특정 파일의 표현식 하나가 전체 체크 시간의 상당 부분을 차지하며, 그 지점이 폭발의 진원지다.

| 전략 | 방법 | 트레이드오프 |
| --- | --- | --- |
| 분배 차단 | `[T] extends [U]` 로 감싸기 | union 필터 의미를 잃음 |
| 조기 종료 | 재귀 전 `IsNever` 가드로 컷 | 가드 자체도 인스턴스화 비용 |
| 결과 캐싱 | `interface` 로 고정해 재계산 차단 | 제네릭성 상실 |
| 꼬리 재귀화 | 누산기 파라미터 도입 | 정의 가독성 저하 |
| 깊이 상한 | 재귀 파라미터에 카운터 부여 | 초과 시 조용히 `never` 반환 |
| 계산 포기 | 명시 타입으로 교체 | 타입 안전성 손실 |

효과가 가장 큰 것은 대개 명시적 깊이 상한이다. `TS2589` 는 컴파일러가 포기했다는 신호일 뿐 어디서 포기했는지 알려주지 않으므로, 스스로 상한을 정하면 실패가 예측 가능해진다.

```ts
type Prev = [never, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9];

type DeepReadonly<T, D extends number = 5> =
  [D] extends [never]
    ? unknown                       // 상한 도달 — 더 파고들지 않는다
    : T extends (infer E)[]
      ? readonly DeepReadonly<E, Prev[D]>[]
      : T extends object
        ? { readonly [K in keyof T]: DeepReadonly<T[K], Prev[D]> }
        : T;
```

`Prev[D]` 로 깊이를 하나씩 소모하고 0 에 닿으면 `Prev[0]` 이 `never` 가 되어 `[D] extends [never]` 가 참이 된다. 여기서 분배를 끄기 위해 대괄호가 필수라는 점이 2장과 맞물린다.

## 8. 실무 적용의 한계선

타입 레벨 라우터, `DeepPartial`, 경로(Path) 추출은 프로덕션에서 가장 자주 사고를 내는 지점이며, 실패 원인은 셋으로 압축된다.

**첫째, 리터럴성 소실.** `const path = "/users/" + id` 는 `string` 으로 넓어져 모든 보호를 잃는다. 템플릿 리터럴 타입은 런타임 조립을 추적하지 못하므로 경로는 상수 테이블에 `as const` 로 고정하고 조립은 타입이 아는 함수로만 수행해야 한다.

**둘째, 내장 객체 침투.** `DeepPartial` 을 순진하게 정의하면 `Date`, `Map`, 함수까지 파고들어 쓸모없는 타입을 만든다.

```ts
type Primitive = string | number | boolean | bigint | symbol | null | undefined;

type DeepPartial<T> =
  T extends Primitive ? T
  : T extends (...args: any[]) => any ? T
  : T extends Date | RegExp ? T
  : T extends ReadonlyArray<infer E> ? ReadonlyArray<DeepPartial<E>>
  : T extends Map<infer K, infer V> ? Map<DeepPartial<K>, DeepPartial<V>>
  : T extends object ? { [K in keyof T]?: DeepPartial<T[K]> }
  : T;
```

분기 순서가 곧 우선순위다. `T extends object` 를 위로 올리면 `Date` 도 `Map` 도 그 분기에 먹히므로 좁은 조건이 반드시 먼저 와야 한다. 이 순서 의존성이 조건부 체인의 본질적 취약점이며, 새 내장 타입을 지원할 때마다 분기를 추가해야 하는 부채로 남는다. 게다가 `T extends Date` 는 nominal 검사가 아니라 구조적 검사이므로 동일한 프로퍼티 집합을 가진 다른 타입과 구분되지 않을 수 있다.

**셋째, 순환 참조.** TypeScript 는 재귀 참조를 만나면 "구조적으로 동일한 인스턴스화를 이미 본 적 있는가"를 검사해 무한 루프를 끊는다. 그러나 매 단계 타입이 미묘하게 달라지는 유틸리티에서는 동일성 감지가 걸리지 않아 깊이 상한까지 내달린다. 트리·그래프 모델에 재귀 유틸리티를 걸 때 7장의 카운터 기반 상한이 유일하게 신뢰할 만한 안전장치인 이유다.

중첩 객체의 모든 경로를 뽑는 `Path<T>` 는 위 세 문제를 동시에 갖는다.

```ts
type Path<T, D extends number = 4> =
  [D] extends [never] ? never
  : T extends Primitive | Date | ((...a: any[]) => any) ? never
  : T extends object
    ? { [K in keyof T & string]: K | `${K}.${Path<T[K], Prev[D]>}` }[keyof T & string]
    : never;

type P = Path<{ id: string; customer: { address: { city: string } } }>;
// "id" | "customer" | "customer.address" | "customer.address.city"
```

폭 3, 깊이 4 정도의 평범한 DTO 에서도 결과 union 이 빠르게 커진다. Spring DTO 를 그대로 옮긴 넓은 객체에 걸면 인스턴스화 수가 급증하고 자동완성이 눈에 띄게 느려진다. **경로 집합이 유한하고 소수라면 union 리터럴을 손으로 적는 편이 낫고**, 자동 도출이 필요하면 깊이 상한을 못 박고 대상 타입을 화이트리스트로 제한해야 한다.

종합하면 도입 판단 순서는 이렇다. 같은 안전성을 오버로드 시그니처나 판별 union(discriminated union)으로 얻을 수 있다면 그쪽을 택하고, 조건부 타입이 꼭 필요하면 라이브러리 경계 한 곳에만 둔다. 도입했다면 `Expect<Equals<...>>` 테스트를 함께 커밋해 컴파일러 업그레이드가 의미를 바꾸는 것을 CI 에서 잡고, 인스턴스화 수를 기준선으로 기록해 회귀를 감시한다. Java 제네릭이 타입 소거와 상한 제약에 머무는 대신 짧은 에러 메시지를 주는 반면, 조건부 타입은 런타임 코드를 한 줄도 늘리지 않고 계약을 정밀하게 표현하는 대가로 컴파일 시간과 진단 가독성을 지불한다. 타입이 코드보다 어려워지는 순간이 후퇴 신호이며, 그때는 명시적 타입과 런타임 스키마 검증으로 물러서는 편이 총비용에서 유리하다.

## 참고

- TypeScript Handbook — Conditional Types (분배 규칙, `infer` 추론, 다중 후보 병합): https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.1 Release Notes — Recursive Conditional Types, Template Literal Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- TypeScript 4.8 Release Notes — `infer` Types in Template String Types, `NonNullable` 변경: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- TypeScript Wiki — Performance / Performance Tracing (`--generateTrace`, `@typescript/analyze-trace`): https://github.com/microsoft/TypeScript/wiki/Performance
- `lib.es5.d.ts` 원문 (Exclude/Extract/ReturnType/Parameters 정의): https://github.com/microsoft/TypeScript/blob/main/src/lib/es5.d.ts
