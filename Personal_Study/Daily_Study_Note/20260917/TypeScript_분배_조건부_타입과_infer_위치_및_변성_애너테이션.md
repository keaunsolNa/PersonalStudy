Notion 원본: https://www.notion.so/3de5a06fd6d3812db2e9f000891b9cfb

# TypeScript 분배 조건부 타입과 infer 위치 및 변성 애너테이션

> 2026-09-17 신규 주제 · 확장 대상: TypeScript 타입 시스템 심화 — 조건부·매핑 타입의 평가 규칙

## 학습 목표

- 네이키드 타입 파라미터가 조건부 타입을 분배시키는 조건을 정확히 판정한다
- `infer` 가 공변 위치와 반변 위치에서 각각 합집합·교집합을 만드는 이유를 설명한다
- `in`/`out` 변성 애너테이션이 검사 성능과 오류 메시지에 미치는 영향을 구분한다
- 재귀 조건부 타입의 깊이 제한과 꼬리 재귀 최적화 조건을 안다

## 1. 분배는 "네이키드"일 때만 일어난다

조건부 타입 `T extends U ? X : Y` 에서 `T` 가 **타입 파라미터 그 자체**(네이키드)이고 인스턴스화되는 인자가 유니온이면, 유니온의 각 멤버에 대해 조건부가 따로 평가되고 결과가 다시 유니온으로 합쳐진다.

```ts
type ToArray<T> = T extends unknown ? T[] : never;

type A = ToArray<string | number>;
// string[] | number[]   ← 분배됨

type ToArrayNonDist<T> = [T] extends [unknown] ? T[] : never;

type B = ToArrayNonDist<string | number>;
// (string | number)[]   ← 튜플로 감싸 분배 차단
```

분배가 유용한 대표 사례가 `Exclude` 와 `Extract` 다. 표준 라이브러리 정의가 그대로 이 규칙에 기댄다.

```ts
type Exclude<T, U> = T extends U ? never : T;
type Extract<T, U> = T extends U ? T : never;

type C = Exclude<"a" | "b" | "c", "b">;   // "a" | "c"
```

`"a" | "b" | "c"` 가 세 번의 개별 평가로 쪼개지고, `"b"` 만 `never` 가 되며, `never` 는 유니온에서 흡수돼 사라진다.

여기서 초보자를 가장 자주 넘어뜨리는 것이 `never` 자체를 넣었을 때다.

```ts
type D = ToArray<never>;        // never  (기대: never[])
```

`never` 는 "멤버가 0개인 유니온"이므로 분배할 것이 없고, 결과도 빈 유니온 = `never` 다. 이 동작이 의도가 아니라면 `[T] extends [never]` 로 먼저 걸러야 한다.

```ts
type IsNever<T> = [T] extends [never] ? true : false;
type E = IsNever<never>;        // true
type F = IsNever<string>;       // false
```

`boolean` 도 조심할 대상이다. `boolean` 은 내부적으로 `true | false` 유니온이라 분배 대상이 된다.

```ts
type Flip<T> = T extends true ? false : true;
type G = Flip<boolean>;         // false | true = boolean  ← 분배 결과
```

## 2. `infer` 의 위치가 결정하는 것

`infer` 는 조건부 타입의 `extends` 절 안에서만 쓸 수 있고, 매칭에 성공하면 참 분기에서 그 변수를 쓸 수 있다. 같은 변수가 여러 곳에 나오면 위치에 따라 합성 방식이 달라진다.

**공변 위치**(반환 타입, 배열 원소, 객체 속성)에서 여러 후보가 나오면 **유니온**이 된다.

```ts
type Elem<T> = T extends (infer U)[] ? U : never;
type H = Elem<(string | number)[]>;         // string | number

type Union<T> = T extends { a: infer U; b: infer U } ? U : never;
type I = Union<{ a: string; b: number }>;   // string | number
```

**반변 위치**(함수 파라미터)에서 여러 후보가 나오면 **교집합**이 된다.

```ts
type Param<T> = T extends { (x: infer U): void; (x: infer U): void } ? U : never;
type J = Param<{ (x: string): void; (x: number): void }>;   // string & number = never
```

이 규칙을 이용한 것이 유명한 유니온 → 인터섹션 변환이다.

```ts
type UnionToIntersection<U> =
    (U extends unknown ? (k: U) => void : never) extends (k: infer I) => void
        ? I
        : never;

type K = UnionToIntersection<{ a: 1 } | { b: 2 }>;   // { a: 1 } & { b: 2 }
```

동작 순서를 뜯어보면 이렇다. 먼저 `U extends unknown ? ...` 가 분배되어 `((k: {a:1}) => void) | ((k: {b:2}) => void)` 가 만들어진다. 그다음 이 함수 유니온이 `(k: infer I) => void` 에 매칭될 때, 파라미터는 반변 위치라 후보들이 교집합으로 합쳐진다. 함수 유니온에 안전하게 전달 가능한 인자는 두 타입을 모두 만족해야 하므로 논리적으로 타당한 결과다.

`infer` 에는 제약도 걸 수 있다(TS 4.7+). 제약을 조건부로 다시 검사하는 것보다 간결하고, 검사 비용도 낮다.

```ts
// 4.7 이전
type FirstOld<T> = T extends [infer F, ...unknown[]]
    ? F extends string ? F : never
    : never;

// 4.7 이후
type First<T> = T extends [infer F extends string, ...unknown[]] ? F : never;
```

## 3. 템플릿 리터럴 타입과 파싱

템플릿 리터럴 타입은 `infer` 와 결합해 문자열을 타입 수준에서 분해한다. 라우트 파라미터 추출이 대표 사례다.

```ts
type PathParams<T extends string> =
    T extends `${string}:${infer Param}/${infer Rest}`
        ? Param | PathParams<`/${Rest}`>
        : T extends `${string}:${infer Param}`
            ? Param
            : never;

type P = PathParams<"/users/:userId/posts/:postId">;   // "userId" | "postId"
```

템플릿 리터럴도 분배된다. 유니온을 끼우면 곱집합이 생긴다.

```ts
type Size = "sm" | "md" | "lg";
type Color = "red" | "blue";
type Class = `${Color}-${Size}`;     // 6개 조합
```

이 곱집합이 성능 함정이다. 유니온 세 개를 곱하면 멤버 수가 곱으로 늘고, TypeScript 는 유니온 크기 상한(약 10만)을 넘으면 오류를 낸다. Tailwind 클래스 전체를 타입으로 표현하려는 시도가 실패하는 이유다.

`Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize` 는 컴파일러 내장 고유 타입이다. 타입 수준에서 문자열을 변환하되 구현은 네이티브라 비용이 낮다.

```ts
type Getters<T> = {
    [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K]
};

type Q = Getters<{ name: string; age: number }>;
// { getName: () => string; getAge: () => number }
```

`as` 절의 키 재매핑에서 `never` 를 만들면 그 속성이 제거된다. 필터링 패턴의 기반이다.

```ts
type OnlyFunctions<T> = {
    [K in keyof T as T[K] extends Function ? K : never]: T[K]
};
```

## 4. 재귀 조건부 타입과 깊이 제한

재귀 조건부 타입은 TS 4.1 부터 공식 지원된다. 깊이 제한이 있고(인스턴스화 깊이 약 500, 상황에 따라 100 근처에서 먼저 걸린다), 초과하면 `Type instantiation is excessively deep and possibly infinite` 오류가 난다.

꼬리 재귀 형태로 쓰면 컴파일러가 최적화해 훨씬 깊이 들어갈 수 있다(TS 4.5+). 조건이 까다로운데, **조건부 타입의 참/거짓 분기 결과가 곧바로 자기 자신인 경우**여야 한다.

```ts
// 비꼬리 재귀 — 결과를 다시 감싸므로 최적화 안 됨
type ReverseSlow<T extends unknown[]> =
    T extends [infer H, ...infer R] ? [...ReverseSlow<R>, H] : [];

// 꼬리 재귀 — 누산기(accumulator)로 변환
type Reverse<T extends unknown[], Acc extends unknown[] = []> =
    T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;

type R1 = Reverse<[1, 2, 3]>;       // [3, 2, 1]
```

누산기 패턴은 타입 수준 프로그래밍의 기본기다. 길이 계산, 문자열 분할, 숫자 연산 모두 이 형태로 쓴다.

```ts
type Split<S extends string, D extends string, Acc extends string[] = []> =
    S extends `${infer Head}${D}${infer Tail}`
        ? Split<Tail, D, [...Acc, Head]>
        : [...Acc, S];

type R2 = Split<"a.b.c", ".">;      // ["a", "b", "c"]
```

깊이 제한에 걸릴 때의 현실적 대응은 세 가지다. 재귀를 꼬리 형태로 바꾸거나, 입력 크기에 상한을 두거나(경로 깊이 10 이하 등), 타입 수준 계산을 포기하고 런타임 검증으로 옮긴다. 세 번째가 가장 자주 옳다 — 타입 수준 파서는 IDE 반응 속도를 심각하게 떨어뜨린다.

## 5. 변성 애너테이션 — 성능과 명시성

TypeScript 는 구조적 타이핑이라 제네릭의 변성을 **구조에서 추론**한다. 추론 자체가 비용이고, 복잡한 재귀 타입에서는 비싸다. TS 4.7 부터 `in`/`out` 으로 명시할 수 있다.

```ts
interface Producer<out T> {          // 공변: T 를 만들어내기만 함
    get(): T;
}

interface Consumer<in T> {           // 반변: T 를 받기만 함
    set(value: T): void;
}

interface Box<in out T> {            // 불변: 둘 다
    get(): T;
    set(value: T): void;
}
```

효과는 두 가지다.

**성능.** 컴파일러가 구조를 파고들어 변성을 계산하는 대신 애너테이션을 신뢰한다. 깊게 중첩된 제네릭이 많은 코드베이스에서 타입 체크 시간이 줄어든다. `--extendedDiagnostics` 의 `Check time` 으로 전후를 비교해 효과를 확인할 수 있다.

**정확성 검증.** 애너테이션이 실제 사용과 맞지 않으면 컴파일러가 오류를 낸다. 즉 문서화이자 단위 테스트 역할을 한다.

```ts
interface Wrong<out T> {
    set(value: T): void;    // 오류: T 가 반변 위치에 있는데 out 선언
}
```

변성 개념 자체를 정리하면 이렇다. `Dog extends Animal` 일 때,

| 변성 | 관계 | 예 |
|---|---|---|
| 공변(out) | `F<Dog>` 를 `F<Animal>` 에 할당 가능 | `readonly Dog[]` → `readonly Animal[]` |
| 반변(in) | `F<Animal>` 를 `F<Dog>` 에 할당 가능 | `(a: Animal) => void` → `(d: Dog) => void` |
| 불변(in out) | 어느 쪽도 불가 | `Box<Dog>` ↮ `Box<Animal>` |

주의할 점이 있다. TypeScript 는 기본 설정에서 **메서드 파라미터를 이변(bivariant)** 으로 다룬다. 즉 `interface` 의 메서드 문법으로 쓰면 반변 규칙이 느슨해진다. `strictFunctionTypes` 는 함수 타입 속성에만 적용되고 메서드 문법에는 적용되지 않는다.

```ts
interface Handler {
    handle(e: Animal): void;        // 이변 — 느슨
    onEvent: (e: Animal) => void;   // 반변 — strictFunctionTypes 적용
}
```

배열이 `Array<Dog>` → `Array<Animal>` 할당을 허용하는 것도 이 이변성 때문이며, 런타임 안전하지 않다는 것이 알려진 절충이다(`push` 로 `Cat` 을 넣을 수 있다).

## 6. 조건부 타입의 평가 시점

조건부 타입은 **모든 타입 파라미터가 알려질 때까지 지연**된다. 제네릭 함수 본문 안에서 조건부 타입이 즉시 해소되지 않는 이유다.

```ts
function f<T extends string | number>(x: T): T extends string ? string : number {
    if (typeof x === "string") {
        return x;            // 오류: T extends string ? string : number 에 할당 불가
    }
    return 0;                // 같은 오류
}
```

컴파일러는 `T` 가 무엇인지 모르므로 반환 타입을 해소할 수 없고, 어떤 구체 값도 그 미해소 타입에 할당 가능하다고 판단하지 못한다. 실무 해법은 오버로드 시그니처를 노출하고 구현부는 느슨하게 두는 것이다.

```ts
function f(x: string): string;
function f(x: number): number;
function f(x: string | number): string | number {
    return typeof x === "string" ? x : 0;
}
```

TS 5.8 의 `--strictBuiltinIteratorReturn` 과 별개로, 조건부 반환 타입을 좁히는 개선이 여러 버전에 걸쳐 들어왔지만 일반 해법은 아직 없다. 오버로드가 현재의 권장 경로다.

## 7. 디버깅 도구

타입 수준 코드가 복잡해지면 중간 결과를 봐야 한다. 몇 가지 관용구가 있다.

```ts
// 1) 중간 타입 펼쳐 보기
type Expand<T> = T extends infer O ? { [K in keyof O]: O[K] } : never;
type Debug = Expand<SomeComplexType>;     // 에디터 호버에서 평탄하게 표시

// 2) 컴파일 타임 단언
type Assert<T extends true> = T;
type Equals<A, B> =
    (<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false;

type _t1 = Assert<Equals<Reverse<[1, 2]>, [2, 1]>>;   // 틀리면 컴파일 에러
```

`Equals` 구현이 기묘해 보이지만, 지연된 조건부 타입의 **동일성 비교**라는 컴파일러 내부 동작을 이용한 것이다. 단순 상호 `extends` 검사로는 `any` 나 유니온 순서 차이를 구분하지 못하는 반면 이 방법은 구분한다.

성능 진단은 `--extendedDiagnostics` 와 `--generateTrace` 다.

```bash
tsc --noEmit --extendedDiagnostics
tsc --noEmit --generateTrace ./trace
npx @typescript/analyze-trace ./trace
```

`Instantiations` 수치가 수백만을 넘으면 어딘가에서 타입 폭증이 일어난 것이다. analyze-trace 가 원인 파일과 타입을 지목해준다. 조건부 타입을 재작성하기 전에 이 숫자부터 보는 편이 시간을 아낀다.

## 8. 어디까지 타입으로 표현할 것인가

타입 수준 프로그래밍은 매력적이지만 비용이 실재한다. 판단 기준을 정리하면 이렇다.

**할 만한 경우**: 라이브러리 공개 API 의 추론 품질을 높이는 경우, 오타가 잦은 문자열 키(이벤트 이름, 경로, 번역 키)를 좁히는 경우, 잘못된 조합을 컴파일 타임에 막아 런타임 검사를 줄이는 경우.

**피할 경우**: 애플리케이션 코드에서 한두 곳만 쓰는 타입, 런타임 검증(Zod 등)으로 이미 보장되는 것을 타입으로 다시 증명하려는 경우, 타입 정의를 읽는 데 주석이 필요할 만큼 복잡해진 경우.

실용적 신호는 IDE 반응 속도다. 자동완성이 눈에 띄게 느려졌다면 그 타입은 너무 멀리 간 것이다. `Instantiations` 수치와 함께 팀의 개발 경험을 기준으로 선을 그어야 한다.

## 참고

- TypeScript Handbook, Conditional Types — https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript Handbook, Template Literal Types — https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html
- TypeScript 4.7 릴리스 노트, Optional Variance Annotations — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html
- TypeScript Wiki, Performance — https://github.com/microsoft/TypeScript/wiki/Performance
- type-fest 저장소(실전 타입 구현 참고) — https://github.com/sindresorhus/type-fest
- @typescript/analyze-trace — https://github.com/microsoft/typescript-analyze-trace
