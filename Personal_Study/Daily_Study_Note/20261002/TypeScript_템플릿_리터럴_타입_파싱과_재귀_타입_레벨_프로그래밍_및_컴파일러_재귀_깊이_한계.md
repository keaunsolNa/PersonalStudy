Notion 원본: https://www.notion.so/3ed5a06fd6d381e494a9ffa995bc9a41

# TypeScript 템플릿 리터럴 타입 파싱과 재귀 타입 레벨 프로그래밍 및 컴파일러 재귀 깊이 한계

> 2026-10-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 템플릿 리터럴 타입의 `infer` 분해 규칙을 적용해 Split, Join, 라우트 파라미터 추출 타입을 구현한다.
- 꼬리 재귀 제거(TS 4.5)가 적용되는 형태와 적용되지 않는 형태를 구분하고 누산기(accumulator) 패턴으로 재작성한다.
- TS2589와 TS2590이 발생하는 조건을 재현하고 우회 설계를 선택한다.
- `Equal`과 `Expect`로 타입 수준 테스트를 작성하고 `@ts-expect-error`로 음성 테스트를 보완한다.

## 1. 템플릿 리터럴 타입에서 문자열을 분해하는 규칙

템플릿 리터럴 타입은 TypeScript 4.1에서 도입되었다. `extends` 오른쪽에 놓으면 문자열 분해(패턴 매칭)에 쓸 수 있다. `${infer A}${Sep}${infer B}` 처럼 구분자가 리터럴이면 컴파일러는 왼쪽부터 훑어 구분자가 처음 나타나는 위치에서 자르고, A는 그 앞부분, B는 나머지 전체를 받는다. 구분자 없이 `${infer H}${infer T}` 로 두면 H가 한 글자, T가 나머지가 되며, 이 한 글자 분해가 재귀 파서의 기본 동작이다.

TypeScript 4.8부터는 `${infer N extends number}` 처럼 `infer` 에 제약을 걸어 숫자 리터럴로 되돌릴 수 있다. 입력이 넓은 `string` 이면 매칭이 성립하지 않으므로 재귀 파서는 보통 맨 앞에서 `string extends S` 로 이를 걸러 낸다. 아래 코드는 이후 모든 예제에서 쓰는 검증 도구(`Equal`, `Expect`, 7절에서 설명)와 기본 분해 동작이다.

```ts
export type Equal<X, Y> =
  (<T>() => T extends X ? 1 : 2) extends (<T>() => T extends Y ? 1 : 2) ? true : false;
export type Expect<T extends true> = T;

type Head<S extends string> = S extends `${infer H}${infer _T}` ? H : never;
type FirstDot = "a.b.c" extends `${infer A}.${infer B}` ? [A, B] : never;
type ParsedNum = "42" extends `${infer N extends number}` ? N : never;
type Wide = string extends `${infer A}.${infer B}` ? [A, B] : "no-match";

type T1 = Expect<Equal<Head<"hello">, "h">>;
type T2 = Expect<Equal<FirstDot, ["a", "b.c"]>>;
type T3 = Expect<Equal<ParsedNum, 42>>;
type T4 = Expect<Equal<Wide, "no-match">>;
```

## 2. Split과 Join: 문자열과 튜플을 오가는 파서

가장 흔한 파서는 구분자로 자르는 `Split` 이다. 직관적인 구현은 첫 토막을 튜플 앞에 붙이고 나머지를 재귀로 처리한다. 읽기 쉽지만 재귀 호출이 스프레드 튜플에 감싸여 꼬리 위치가 아니므로, 토큰이 몇 개뿐인 입력에 적합하다.

```ts
type Split<S extends string, D extends string> =
  string extends S ? string[] :
  S extends "" ? [] :
  S extends `${infer H}${D}${infer T}` ? [H, ...Split<T, D>] : [S];

type SplitAcc<S extends string, D extends string, Acc extends string[] = []> =
  S extends `${infer H}${D}${infer T}` ? SplitAcc<T, D, [...Acc, H]> : [...Acc, S];

type Join<T extends readonly string[], D extends string> =
  T extends readonly [] ? "" :
  T extends readonly [infer F extends string] ? F :
  T extends readonly [infer F extends string, ...infer R extends string[]]
    ? `${F}${D}${Join<R, D>}` : string;

type S1 = Expect<Equal<Split<"a.b.c", ".">, ["a", "b", "c"]>>;
type S2 = Expect<Equal<Split<"", ".">, []>>;
type S3 = Expect<Equal<Split<string, ".">, string[]>>;
type S4 = Expect<Equal<SplitAcc<"2026-10-02", "-">, ["2026", "10", "02"]>>;
type J1 = Expect<Equal<Join<["a", "b", "c"], "-">, "a-b-c">>;
type J2 = Expect<Equal<Join<Split<"x/y/z", "/">, ".">, "x.y.z">>;
```

`SplitAcc` 는 같은 일을 누산기 `Acc` 에 쌓으며 수행하므로 재귀 호출이 꼬리 위치가 되어 4절의 제거 대상이 된다. 대신 빈 문자열 입력에서 `[]` 가 아니라 `[""]` 를 돌려주는 등 경계 동작이 다르다. 공개 유틸리티는 최상단 래퍼에서 입력을 정규화하고 내부에 누산기 버전을 두는 편이 안전하다.

`Join` 은 튜플 쪽 재귀다. `${F}${D}${Join<...>}` 는 템플릿 안에 재귀가 들어가 꼬리가 아니므로, 토큰이 수십 개를 넘을 수 있으면 4절의 틀로 누산기 버전을 만든다.

## 3. 라우트 경로에서 파라미터 추출하기

가장 실용적인 응용은 Express 스타일 경로 `"/users/:id/posts/:postId"` 에서 파라미터 이름을 뽑아 핸들러의 `params` 타입을 만드는 것이다. 핵심은 `${string}:${infer Param}/${infer Rest}` 패턴이다. 앞쪽 `${string}` 은 첫 콜론 이전 부분을 소비하고, Param은 다음 슬래시 직전까지, Rest는 그 이후 전체를 받는다. 남은 부분은 앞에 슬래시를 다시 붙여 재귀하고, 슬래시가 더 없으면 마지막 파라미터가 끝까지 이어진 경우이므로 별도 분기로 처리한다. 결과는 유니온으로 모은다.

```ts
type RouteParams<P extends string> =
  P extends `${string}:${infer Param}/${infer Rest}`
    ? Param | RouteParams<`/${Rest}`>
    : P extends `${string}:${infer Param}` ? Param : never;

type ParamsObject<P extends string> = { [K in RouteParams<P>]: string };

function get<P extends string>(path: P, handler: (params: ParamsObject<P>) => void): P {
  handler({} as ParamsObject<P>);
  return path;
}

get("/users/:id/posts/:postId", (params) => {
  const id: string = params.id;
  const postId: string = params.postId;
  console.log(id, postId);
});

type R1 = Expect<Equal<RouteParams<"/users/:id/posts/:postId">, "id" | "postId">>;
type R2 = Expect<Equal<RouteParams<"/health">, never>>;
type R3 = Expect<Equal<ParamsObject<"/a/:x">, { x: string }>>;
```

핸들러 안에서 `params.userId` 처럼 없는 파라미터를 읽으면 컴파일 오류가 난다. 오타를 런타임이 아니라 편집 단계에서 잡는 것이 이 기법의 가치다.

이 파서는 URL 규격 전체를 다루지 않는다. 퍼센트 인코딩, 선택적 파라미터(`:id?`), 정규식 제약은 타입으로 모사하는 비용이 크므로, 개발자가 직접 쓴 리터럴 경로에만 적용하고 외부 입력은 런타임에서 검증한다.

## 4. 꼬리 재귀 제거와 누산기 패턴

TypeScript 4.5 릴리스 노트는 조건부 타입에 대한 꼬리 재귀 제거를 소개한다. 조건부 타입의 분기 결과가 곧바로 자기 자신(또는 다른 조건부 타입)의 호출일 때, 컴파일러는 새 인스턴스화 스택을 쌓는 대신 반복문처럼 처리한다. 그 결과 일반 재귀의 깊이 제한보다 훨씬 긴 약 1,000회 반복까지 허용된다. 조건은 두 가지다. 재귀 호출이 분기의 최종 결과 위치에 있어야 하고, 그 호출을 템플릿 리터럴, 튜플 스프레드, 유니온, 인덱스 접근 등으로 감싸지 않아야 한다. 감싸는 순간 호출은 "나중에 값을 가공해야 하는" 위치가 되어 스택이 필요해진다.

꼬리 재귀로 바꾸는 방법은 항상 같다. 가공할 중간 결과를 반환값이 아니라 인자(누산기)로 내려보내고, 재귀가 끝나는 분기에서 누산기를 그대로 돌려준다. 문자열 반복, 길이 계산, 뒤집기, 공백 제거, 전체 치환이 모두 이 틀이다.

```ts
type Repeat<S extends string, N extends number, Acc extends string = "", C extends unknown[] = []> =
  C["length"] extends N ? Acc : Repeat<S, N, `${Acc}${S}`, [...C, unknown]>;

type StrLen<S extends string, Acc extends unknown[] = []> =
  S extends `${string}${infer T}` ? StrLen<T, [...Acc, unknown]> : Acc["length"];

type Reverse<S extends string, Acc extends string = ""> =
  S extends `${infer H}${infer T}` ? Reverse<T, `${H}${Acc}`> : Acc;

type Ws = " " | "\n" | "\t";
type TrimLeft<S extends string> = S extends `${Ws}${infer R}` ? TrimLeft<R> : S;
type TrimRight<S extends string> = S extends `${infer R}${Ws}` ? TrimRight<R> : S;
type Trim<S extends string> = TrimLeft<TrimRight<S>>;

type ReplaceAll<S extends string, From extends string, To extends string, Acc extends string = ""> =
  From extends "" ? S :
  S extends `${infer H}${From}${infer T}` ? ReplaceAll<T, From, To, `${Acc}${H}${To}`> : `${Acc}${S}`;

type A1 = Expect<Equal<Repeat<"ab", 3>, "ababab">>;
type A2 = Expect<Equal<StrLen<"typescript">, 10>>;
type A3 = Expect<Equal<Reverse<"abc">, "cba">>;
type A4 = Expect<Equal<Trim<"  \n hi \t">, "hi">>;
type A5 = Expect<Equal<ReplaceAll<"a-b-c", "-", "+">, "a+b+c">>;
type A6 = Expect<Equal<StrLen<Repeat<"a", 900>>, 900>>;
```

`Repeat` 는 튜플 길이로 정수를 흉내 내는 관용구를 쓰며, 900번 반복이 가능한 것은 꼬리 재귀 제거 덕분이다. 누산기 인자가 늘어 시그니처가 길어지므로, 공개 타입에는 인자를 최소로 노출하고 누산기 버전은 내부 타입으로 숨기는 편이 읽기 좋다.

## 5. 인스턴스화 깊이 한계와 TS2589

꼬리 위치가 아닌 재귀는 컴파일러의 인스턴스화 깊이 제한에 걸린다. 이때 보고되는 진단이 TS2589, "Type instantiation is excessively deep and possibly infinite" 이다. 정확한 상수는 구현 세부로 버전마다 바뀔 수 있으므로 "수십 단계 수준에서 멈춘다" 정도로만 기억한다. 반면 꼬리 재귀는 앞 절에서 언급한 것처럼 약 1,000회 반복에서 멈춘다.

아래는 두 한계를 의도적으로 재현한다. `ReverseNT` 는 재귀 결과를 템플릿으로 감싼 비꼬리 버전이라 길이 200에서 실패하고, 꼬리 버전 `Reverse` 는 1,000회를 넘는 길이에서 실패한다. 오류는 정의가 아니라 사용 위치에 보고된다. `@ts-expect-error` 는 해당 줄에 오류가 나야만 통과하므로 한계 재현이 사실인지 검증하는 수단이기도 하다.

```ts
type ReverseNT<S extends string> =
  S extends `${infer H}${infer T}` ? `${ReverseNT<T>}${H}` : "";

type Ok200 = Reverse<Repeat<"a", 200>>;

// @ts-expect-error TS2589: 비꼬리 재귀는 길이 200에서 깊이 한계에 걸린다
type Bad200 = ReverseNT<Repeat<"a", 200>>;

// @ts-expect-error TS2589: 꼬리 재귀도 약 1,000회 반복을 넘으면 실패한다
type Bad1100 = Reverse<Repeat<"a", 1100>>;
```

한계에 걸리면 꼬리 위치인지 먼저 확인하고, 구분자 단위로 소비해 반복 수를 줄이며, 그래도 부족하면 입력 길이를 제한하거나 런타임 파서로 넘어간다. 변화 전후의 비용은 `tsc --extendedDiagnostics` 의 인스턴스화 수로 비교하고, 세부 추적은 `--generateTrace` 를 쓴다.

## 6. 유니온 폭발과 TS2590

TS2589와 이름이 비슷하지만 원인이 다른 오류가 TS2590, "Expression produces a union type that is too complex to represent" 이다. 재귀 깊이가 아니라 유니온 멤버 수가 한계를 넘을 때 발생한다. 템플릿 리터럴에 유니온을 여러 번 넣으면 곱집합이 만들어진다. 한 자리 숫자 유니온 `D` 를 다섯 번 이어 붙이면 10만 개, 여섯 번이면 100만 개가 되어 한계(대략 10만 개 부근, 구현 상수)를 넘는다.

```ts
type D = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;
type Three = `${D}${D}${D}`;

// @ts-expect-error TS2590: 10^6개 멤버의 유니온은 표현할 수 없다
type Six = `${D}${D}${D}${D}${D}${D}`;

type Phone = `${number}-${number}-${number}`;
const ok: Phone = "010-1234-5678";
// @ts-expect-error 패턴 타입은 형태만 검사한다
const bad: Phone = "010-1234";
type U1 = Expect<Equal<Three extends `${number}${number}${number}` ? true : false, true>>;
```

해결 방향은 값을 전부 나열하지 않는 것이다. `${number}` 같은 패턴 템플릿 리터럴 타입은 유니온을 만들지 않고 "이런 모양의 문자열" 이라는 하나의 타입으로 동작한다. 위 `Phone` 이 그 예로, 형식은 검사하지만 자릿수까지는 강제하지 못한다. 분배 조건부 타입이 중간에 끼어 곱집합을 증폭시키는 경우도 있으므로 `[T] extends [...]` 로 분배를 막는 것도 점검한다.

## 7. 타입 수준 테스트: Equal과 Expect

타입 유틸리티는 값이 없어 일반 단위 테스트로 검증하기 어렵다. 관용적인 방법이 1절의 `Equal` 과 `Expect` 다. `Equal<X, Y>` 는 두 제네릭 함수 타입이 서로 같은지로 정의되며, X와 Y가 식별적으로 같아야 true가 되므로 단순한 상호 할당 검사와 달리 `any` 와 다른 타입을 구분한다. `Expect<T extends true>` 는 인자가 `true` 가 아니면 제약 위반(TS2344)을 내는 장치일 뿐이다. 같은 패턴이 type-challenges 저장소와 `expect-type`, `tsd` 라이브러리에도 쓰인다.

```ts
type NotEqual<X, Y> = Equal<X, Y> extends true ? false : true;

type Simplify<T> = { [K in keyof T]: T[K] } & {};
type E2 = Expect<NotEqual<any, 1>>;
type E3 = Expect<NotEqual<{ a: 1 } & { b: 2 }, { a: 1; b: 2 }>>;
type E4 = Expect<Equal<Simplify<{ a: 1 } & { b: 2 }>, { a: 1; b: 2 }>>;
type E5 = Expect<NotEqual<readonly string[], string[]>>;

// @ts-expect-error 음성 테스트: 틀린 기대값은 TS2344를 일으켜야 한다
type Bad = Expect<Equal<Split<"a.b", ".">, ["a"]>>;
```

핵심은 `E3` 처럼 교차 타입과 평탄한 객체가 `Equal` 로는 다르다는 점이며, 그래서 비교 전에 `Simplify` 로 평탄화한다. `readonly` 유무와 `any` 도 구분되므로 기대값은 정확히 같은 모양으로 적어야 한다. `@ts-expect-error` 는 오류가 나야 통과하므로 "이 입력은 거부되어야 한다" 는 음성 테스트와 한계 재현(5, 6절)에 적합하다.

## 8. 설계 트레이드오프와 실무 지침

타입 레벨 프로그래밍은 편집 시점 안전성을 주는 대신 컴파일 시간, 오류 메시지 가독성, 유지보수 비용을 지불한다. 짧고 고정된 리터럴(라우트 경로, 이벤트 이름)은 타입 파서가 이득이 크고, 길이가 가변적이거나 외부에서 오는 문자열은 런타임 검증이 맞다.

| 기법 | 허용 반복 규모 | 컴파일 비용 | 가독성 | 적합한 용도 |
|---|---|---|---|---|
| 비꼬리 재귀 (`[H, ...Rec<T>]`) | 수십 단계 | 낮음 (짧을 때) | 높음 | 토큰 수가 작고 고정된 파서 |
| 꼬리 재귀 + 누산기 | 약 1,000회 | 단계당 소폭 증가 | 중간 (인자 증가) | 문자 단위 처리, 긴 리터럴 |
| 덩어리 단위 소비 | 입력 길이와 무관하게 작음 | 낮음 | 중간 | 구분자 기반 파싱 |
| 패턴 템플릿 (`${number}`) | 해당 없음 | 매우 낮음 | 높음 | 형식만 검사하면 충분한 경우 |
| 유니온 전개 (곱집합) | 약 10만 멤버 부근 | 매우 높음 | 높음 | 작은 열거형 조합만 |

실무 원칙은 다음과 같다. 공개 API에는 단순한 시그니처를 두고 누산기는 내부에 숨긴다. 유틸리티마다 정상, 경계(빈 문자열, 넓은 `string`), 음성 테스트를 `Equal` 과 `@ts-expect-error` 로 둔다. 한계 근처 입력은 문서화하거나 상위에서 막는다. 타입이 복잡해져 팀원이 읽기 어렵다면 그 자체가 비용이므로, 코드 생성이나 런타임 파서가 더 나을 수 있다.

## 참고

- TypeScript Handbook, Template Literal Types: https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html
- TypeScript Handbook, Conditional Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.1 릴리스 노트 (Template Literal Types, Recursive Conditional Types): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- TypeScript 4.5 릴리스 노트 (Tail-Recursion Elimination on Conditional Types): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- TypeScript 4.8 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- TypeScript 컴파일러 옵션 문서 (`--extendedDiagnostics`, `--generateTrace`): https://www.typescriptlang.org/tsconfig
