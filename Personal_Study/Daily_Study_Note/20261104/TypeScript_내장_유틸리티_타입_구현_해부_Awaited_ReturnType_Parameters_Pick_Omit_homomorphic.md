Notion 원본: https://app.notion.com/p/40e5a06fd6d3814fbd7ace945d87ab64?pvs=204

# TypeScript 내장 유틸리티 타입 구현 해부: Awaited·ReturnType·Parameters·NonNullable와 Pick/Omit의 homomorphic 매핑 차이

> 2026-11-04 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Awaited·ReturnType·Parameters·NonNullable를 직접 재구현하고 내장 정의와 타입 동치임을 컴파일로 검증한다.
- thenable, 오버로드, 제네릭 함수에서 내장 유틸리티가 내놓는 결과가 직관과 어꺋나는 지점을 재현하고 원인을 짚는다.
- Pick이 보존하는 readonly·optional 수정자를 Omit이나 손으로 쓴 매핑 타입이 잃는 조건을 코드로 구분한다.
- 유니온과 튜플 위에서 Omit이 무너지는 경우를 판별하고 분배형 Omit 같은 대안을 선택한다.

## 1. 실험 환경과 검증 방법

이 노트의 모든 코드는 전역 설치된 `tsc` 7.0.2와 Node v22.23.4에서 직접 컴파일해 결과를 확인했다. 이 버전에서는 파일을 명령줄에 넘기면 같은 디렉터리의 `tsconfig.json`을 무시하지 않고 오류(TS5112)를 내므로 `--ignoreConfig`를 붙여야 한다. 명령은 모두 `tsc --ignoreConfig --noEmit --strict --target es2022 파일.ts` 형태다.

타입 수준 결과를 눈으로 읽기 위해 두 가지 도구를 쎨다. 하나는 두 타입이 정확히 같은지 판정하는 `Eq`로, 단순한 상호 할당 가능성 검사와 달리 `any`나 수정자 차이까지 구별한다. 다른 하나는 컴파일러 API로 타입 별칭이 해석된 결과를 문자열로 찍는 20줄짜리 스크립트다. `typeToString`에 `NoTruncation`을 주면 IDE 호버보다 긴 형태를 그대로 얻는다. 이후 본문에서 "실행 결과"라고 표기한 블록은 이 스크립트나 `tsc`가 실제로 출력한 값이다.

```ts
type Eq<A, B> = (<X>() => X extends A ? 1 : 2) extends (<X>() => X extends B ? 1 : 2) ? true : false;
type Assert<T extends true> = T;
```

`Eq`는 관례적으로 쓰이는 기법이지만 공식 보장은 아니므로, 결론은 해석된 타입 문자열과 함께 확인했다.

## 2. 네 가지 유틸리티를 재구현하고 동치를 증명한다

네 유틸리티의 핵심은 조건부 타입과 `infer`다. `ReturnType`과 `Parameters`는 함수 시그니처의 위치에 `infer`를 놓아 컴파일러가 그 자리의 타입을 후보로 수집하게 만든다. `Awaited`는 `then` 메서드의 첫 콜백 인자를 거꾸로 파고들며 재귀한다. `NonNullable`은 TS 4.8부터 조건부 타입이 아니라 교차 타입 `T & {}`다. 아래는 내장 정의와 같은 구조로 쓴 재구현이며 `Assert<Eq<...>>`가 모두 통과해야 컴파일이 끝난다.

```ts
type MyAwaited<T> =
  T extends null | undefined ? T :
  T extends object & { then(onfulfilled: infer F, ...args: infer _): any } ?
    F extends (value: infer V, ...args: infer _) => any ? MyAwaited<V> : never :
  T;
type MyReturnType<T extends (...args: any) => any> = T extends (...args: any) => infer R ? R : any;
type MyParameters<T extends (...args: any) => any> = T extends (...args: infer P) => any ? P : never;
type MyNonNullable<T> = T & {};

type T1 = Assert<Eq<MyAwaited<Promise<Promise<string>>>, Awaited<Promise<Promise<string>>>>>;
type T2 = Assert<Eq<MyReturnType<() => Promise<number>>, ReturnType<() => Promise<number>>>>;
type T3 = Assert<Eq<MyParameters<(a: string, b?: number, ...r: boolean[]) => void>,
                    Parameters<(a: string, b?: number, ...r: boolean[]) => void>>>;
type T4 = Assert<Eq<MyNonNullable<string | null | undefined>, NonNullable<string | null | undefined>>>;
```

실행 결과: `tsc --ignoreConfig --noEmit --strict a.ts` 가 `diagnostics: 0`, 종료 코드 0으로 끝났다. 네 쌍 모두 동일 타입으로 판정되었다는 뜻이다. 내장 `Awaited`가 `then`의 모양만 본다는 점이 이후 절의 핵심이다.

## 3. Awaited: Promise 단순 재귀와 무엇이 다른가

`Awaited`를 `T extends Promise<infer U> ? Awaited<U> : T`로 쓰면 되지 않느냐고 생각하기 쉽다. 이렇게 쓴 `NaiveAwaited`와 내장을 thenable 객체에 적용하면 차이가 난다. `await`는 런타임에서 `Promise` 인스턴스가 아니라 `then`이 호출 가능한 객체 전반을 풀어내므로 타입도 그 규칙을 따라야 한다. 또 `then`의 콜백이 함수가 아닌 병적인 객체는 `never`로 처리해 "절대 resolve되지 않는 값"을 표현한다.

```ts
type NaiveAwaited<T> = T extends Promise<infer U> ? NaiveAwaited<U> : T;
interface Thenable<T> { then(cb: (v: T) => void): void }
type A1 = Awaited<Thenable<number>>;
type A2 = NaiveAwaited<Thenable<number>>;
type A3 = Awaited<Promise<number> | boolean>;
type A4 = Awaited<{ then(cb: 1): void }>;
```

실행 결과:

```text
A1         = number
A2         = Thenable<number>
A3         = number | boolean
A4         = never
```

`A3`에서 보듯 유니온은 조건부 타입의 분배 때문에 각 멤버가 따로 풀린다. `A4`의 `never`는 버그가 아니라 의도된 결과다. 라이브러리가 `PromiseLike`만 구현한 객체를 돌려줄 때 직접 만든 단순 `Unwrap` 타입은 결과를 풀지 못하므로 내장 `Awaited`를 쓰는 편이 안전하다. 반대로 값이 `Promise`임이 보장된 내부 코드에서는 단순 재귀가 읽기 쉽고 타입 비용도 약간 낮다.

재귀 깊이에도 한계가 있다. `Promise`를 N겹 감싼 타입을 만드는 도우미 `Nest<N>`을 `Awaited`에 넘겨 보았다.

```text
N=50: ok
N=100: f.ts(2,17): error TS2589: Type instantiation is excessively deep and possibly infinite.
```

N=150, 200에서도 같은 TS2589가 났다. 이 실험은 `Nest`의 튜플 누적과 `Awaited` 재귀가 합쳐진 값이라서 한계가 어느 쪽에서 생기는지 분리하지 못했다. 따라서 "Awaited 재귀 한도는 N"이라고 단정하지 않고, 이 조합에서는 50겹은 통과하고 100겹은 실패했다는 사실만 기록한다. 재귀 조건부 타입을 직접 설계할 때는 같은 방식으로 한도를 측정해 두는 것이 좋다.

## 4. ReturnType과 Parameters: 오버로드와 제네릭의 함정

두 유틸리티는 함수 타입에 `infer`를 적용한다. 함수가 오버로드되어 있으면 추론은 모든 시그니처를 고려하지 않고 마지막 시그니처만 본다. 제네릭 함수는 타입 매개변수를 결정할 수 없으므로 제약(없으면 `unknown`)으로 대체된다. 이 두 규칙은 문서에 짧게 언급될 뿐이어서 처음 만나면 당황스럽다.

```ts
declare function ov(a: string): string;
declare function ov(a: number): number;
type R1 = ReturnType<typeof ov>;
declare function gen<T>(x: T): T[];
type R2 = ReturnType<typeof gen>;
type P1 = Parameters<typeof ov>;
type P2 = Parameters<(a: string, b?: number, ...r: boolean[]) => void>;
```

실행 결과:

```text
R1         = number
R2         = unknown[]
P1         = [a: number]
P2         = [a: string, b?: number | undefined, ...r: boolean[]]
```

`R1`과 `P1`은 선언 순서의 마지막인 `number` 시그니처에서 나왔다. 오버로드를 정리할 때 가장 일반적인 시그니처를 마지막에 두는 관례가 있는데, 이 관례를 지키면 `ReturnType`이 가장 넓은 결과를 돌려준다. `P2`는 `Parameters`가 튜플로 이름, 선택성, 나머지 요소를 모두 보존함을 보여준다. 이 성질 덕분에 `(...args: Parameters<F>) => ReturnType<F>` 형태의 래퍼가 원본과 같은 호출 규칙을 유지한다.

`R2`의 `unknown[]`은 제네릭 `T`를 포착하지 못한 결과다. 래퍼가 제네릭을 잃어버리는 문제는 유틸리티의 한계이므로 래퍼 쪽 시그니처를 제네릭으로 다시 선언하는 것이 근본 해결이다.

## 5. NonNullable: 조건부 타입에서 교차 타입으로

TS 4.7까지 `NonNullable<T>`는 `T extends null | undefined ? never : T`였고 4.8부터 `T & {}`다. 바뀐 이유는 `unknown`과 제네릭에서의 동작이다. 조건부 타입은 `unknown`을 분배하지 못해 그대로 `unknown`을 돌려주었지만 교차 타입은 `{}`로 좋힌다. 또 제네릭 `T` 안에서 `T & {}`는 지연 평가되지 않고 곧바로 "null이 아닌 T"로 취급되어 제어 흐름 분석과 잘 맞는다.

```ts
type OldNN<T> = T extends null | undefined ? never : T;
type N1 = NonNullable<unknown>;
type N2 = OldNN<unknown>;
type N3 = NonNullable<string | null>;
```

실행 결과:

```text
N1         = {}
N2         = unknown
N3         = string
```

`N2`가 `unknown`으로 남는 것이 구버전의 약점이었다. 교차 타입 방식의 대가는 `{}`가 "null과 undefined를 제외한 모든 값"을 뜻한다는 점을 개발자가 알아야 한다는 것이다. `{}`는 빈 객체 타입으로 오해하기 쉽지만 `NonNullable` 내부에서는 의도된 쓰임이다.

## 6. Pick과 Omit: homomorphic 매핑이란 무엇인가

매핑 타입 `{ [P in K]: ... }`는 키 집합 `K`가 `keyof T` 꼴이거나 `keyof T`로 제약된 타입 매개변수일 때 "homomorphic"이 된다. 이 경우에만 컴파일러는 원본 `T`의 프로퍼티별 `readonly`와 `?` 수정자를 결과에 복사한다. 내장 `Pick`은 `K extends keyof T`라는 제약 때문에 homomorphic이고, 같은 모양이어도 키가 임의의 리터럴 유니온이면 수정자 정보의 출처가 없어 사라진다.

```ts
interface User { readonly id: number; name?: string; email: string | null }
type Lookalike = { [P in 'id' | 'name']: User[P] };
type Plain = Record<'id' | 'name', number>;
type KeyOnly = Pick<User, 'id' | 'name'>;
```

실행 결과:

```text
KeyOnly    = { readonly id: number; name?: string | undefined; }
Lookalike  = { id: number; name: string | undefined; }
Plain      = { id: number; name: number; }
```

`KeyOnly`는 수정자가 살아 있고 `Lookalike`는 `readonly`와 `?`가 모두 사라졌다. 이 차이가 실제 오류로 나타나는지 `tsc`로 확인했다.

```ts
declare const p: Pick<User, 'id' | 'name'>;
declare const l: Lookalike;
p.id = 2;                                         // 5행
l.id = 2;                                         // 6행
const p2: Pick<User, 'id' | 'name'> = { id: 1 };  // 7행
const l2: Lookalike = { id: 1 };                  // 8행
```

실행 결과:

```text
c.ts(5,3): error TS2540: Cannot assign to 'id' because it is a read-only property.
c.ts(8,7): error TS2741: Property 'name' is missing in type '{ id: number; }' but required in type 'Lookalike'.
```

6행의 쓰기와 7행의 부분 객체는 오류가 없다. 즉 손으로 쓴 `Lookalike`는 읽기 전용 보장을 조용히 깨뜨리고 선택 프로퍼티를 필수로 승격한다. 구조가 같아 보여도 `K extends keyof T` 제약 유무로 의미가 달라진다. `Partial<User>`처럼 `keyof T`를 직접 순회하는 내장 매핑 타입도 같은 원리로 수정자를 보존하며 그 결과는 `{ readonly id?: number | undefined; name?: string | undefined; email?: string | null | undefined; }`였다.

## 7. Omit의 구조적 한계: 유니온, 인덱스 시그니처, 오타

`Omit<T, K>`는 `Pick<T, Exclude<keyof T, K>>`로 정의되고 `K extends keyof any`이다. 그래서 두 가지 성질이 따라온다. 첫째 `Pick`을 거치므로 수정자는 보존된다(`Omit<User, 'email'>`는 `{ readonly id: number; name?: string | undefined; }`). 둘째 `K`가 `keyof T`로 제약되지 않아 존재하지 않는 키를 넘겨도 조용히 통과한다. 오타가 컴파일 오류로 이어지지 않는 것은 설계 선택이며 `Omit<Tag, 'z'>`가 `{ x: 1; y: 2; }`를 그대로 돌려주는 실행 결과로 확인했다.

```ts
type Typo = Omit<User, 'emial'>;                  // 오류 없음
type StrictOmit<T, K extends keyof T> = Pick<T, Exclude<keyof T, K>>;
type Typo2 = StrictOmit<User, 'emial'>;           // 13행
```

실행 결과:

```text
c.ts(13,31): error TS2344: Type '"emial"' does not satisfy the constraint 'keyof User'.
```

`Omit`의 두 번째 한계는 유니온이다. `keyof (A | B)`는 공통 키만 남기고 `Omit`은 분배하지 않는다. 판별 유니온에서 판별 키를 지우려 하면 모든 멤버의 고유 프로퍼티까지 사라진다. 아래 실험에서 `Pick`은 공통 키만 합첤 돌려주고 `Omit`은 빈 객체가 된다. 분배형 `DistOmit`은 각 멤버에 `Omit`을 따로 적용해 구조를 지킨다.

```ts
type U = { kind: 'a'; a: 1 } | { kind: 'b'; b: 2 };
type OmitU = Omit<U, 'kind'>;
type DistOmit<T, K extends keyof any> = T extends any ? Omit<T, K> : never;
type OmitU2 = DistOmit<U, 'kind'>;
type PickU = Pick<U, 'kind'>;
```

실행 결과:

```text
OmitU      = {}
OmitU2     = Omit<{ kind: "a"; a: 1; }, "kind"> | Omit<{ kind: "b"; b: 2; }, "kind">
PickU      = { kind: "a" | "b"; }
```

인덱스 시그니처가 있는 타입에서도 주의가 필요하다. `Idx = { [k: string]: number; a: 1 }`에 `Omit<Idx, 'a'>`를 적용하면 `keyof Idx`가 `string | number`로 넓어져 `Exclude`가 `'a'`를 제거하지 못하고, 결과는 `{ [x: string]: number; [x: number]: number; }`로 구체 키 정보가 모두 사라졌다. 인덱스 시그니처를 가진 타입에는 `Omit`을 쓰지 않는 것이 원칙이다.

## 8. 튜플과 배열 위의 매핑, 그리고 비용 정리

homomorphic 매핑은 배열과 튜플에서 특별 대우를 받는다. `{ [K in keyof T]: ... }`에 튜플을 넣으면 요소 위치별로 변환된 튜플이 나오고 배열이면 배열이 나온다. 반면 `keyof T & string`처럼 키 집합을 가공하면 homomorphic성을 잃어 튜플이 객체로 풀려 `length`나 `map` 같은 메서드 키까지 순회하게 된다.

```ts
type Boxed<T> = { [K in keyof T]: T[K] | null };
type BT = Boxed<[string, number]>;
type BA = Boxed<string[]>;
type NonHomo<T> = { [K in keyof T & string]: T[K] };
type NH = NonHomo<[string, number]>;
```

실행 결과(NH는 일부만 발최, 실제 출력은 배열 메서드 전체를 포함해 매우 길다):

```text
BT         = [string | null, number | null]
BA         = (string | null)[]
NH         = { 0: string; 1: number; length: 2; toString: () => string; toLocaleString: { ... }; pop: () => string | number | undefined; ... }
```

마지막으로 비용이다. 같은 `Big` 인터페이스(프로퍼티 10개)에서 5개 키를 뽑는 `Pick`과 5개를 제거하는 `Omit`을 각각 컴파일하고 `--extendedDiagnostics`의 Instantiations를 비교했다. 아무 타입도 쓰지 않는 파일의 기준값이 28113이었다.

| 유틸리티 | Instantiations | 기준 대비 증가 |
|---|---|---|
| 기준(빈 파일) | 28113 | 0 |
| `Pick<Big, 5개 키>` | 28119 | 6 |
| `Omit<Big, 5개 키>` | 28153 | 40 |

키 5개짜리 단일 사용 측정이라 일반화는 피한다. `Omit`은 `keyof`, `Exclude`의 분배, `Pick`을 연달아 거치므로 `Pick`보다 인스턴스화가 많다. 선택할 키가 적고 명확할 때는 `Omit`보다 `Pick`이 의도도 명확하고 비용도 낮다. 정리하면 수정자 보존이 필요하면 `Pick`·`Omit`·`Partial`처럼 `keyof T`에서 파생된 키를 쓰고, 오타를 막으려면 `StrictOmit`, 유니온에는 `DistOmit`, 인덱스 시그니처 타입에는 둘 다 쓰지 않고 구조를 다시 설계한다.

## 참고

- TypeScript 공식 핸드북, Utility Types (https://www.typescriptlang.org/docs/handbook/utility-types.html)
- TypeScript 공식 핸드북, Mapped Types (https://www.typescriptlang.org/docs/handbook/2/mapped-types.html)
- TypeScript 공식 핸드북, Conditional Types (https://www.typescriptlang.org/docs/handbook/2/conditional-types.html)
- TypeScript 4.5 릴리스 노트, The Awaited Type and Promise Improvements
- TypeScript 4.8 릴리스 노트, Improved Intersection Reduction, Union Compatibility, and Narrowing
- TypeScript 소스 저장소, `src/lib/es5.d.ts`의 Pick·Omit·ReturnType·Parameters·NonNullable 선언
