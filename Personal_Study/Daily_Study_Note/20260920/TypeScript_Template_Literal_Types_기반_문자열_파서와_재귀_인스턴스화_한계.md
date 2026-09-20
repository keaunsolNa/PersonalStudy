Notion 원본: https://www.notion.so/3e15a06fd6d38194992ffade7d8b8c5c

# TypeScript Template Literal Types 기반 문자열 파서와 재귀 인스턴스화 한계

> 2026-09-20 신규 주제 · 확장 대상: Javascript, React

## 학습 목표

- 템플릿 리터럴 타입의 유니온 분배 카디널리티를 곱셈으로 계산하고 100,000 유니온 한계까지의 여유를 추정한다
- `infer` 의 leftmost-shortest 매칭 규칙에 맞춰 경로·쿼리스트링 파서의 구분자 배치를 설계한다
- 꼬리 재귀 제거가 적용되는 조건을 판별하고 누산기 패턴으로 재귀 한계를 48회에서 999회로 끌어올린다
- `--extendedDiagnostics` 와 `--generateTrace` 로 instantiation 수를 측정해 타입 레벨 파싱을 포기할 시점을 정한다

## 1. 템플릿 리터럴 타입의 카디널리티는 곱셈이다

TypeScript 4.1(2020-11)이 도입한 템플릿 리터럴 타입은 `` `${A}-${B}` `` 형태로 문자열 리터럴 타입을 조합한다. 여기서 가장 먼저 체감해야 할 규약은 **플레이스홀더에 유니온이 오면 분배(distribute)되고, 결과 크기는 각 유니온 크기의 곱**이라는 점이다. Spring 의 `@RequestMapping` 조합을 타입으로 옮긴다고 생각하면 감이 온다.

```ts
type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';   // 5
type Seg = 'a'|'b'|'c'|'d'|'e'|'f'|'g'|'h'|'i'|'j';          // 10
type Key = `${Method} /${Seg}/${Seg}/${Seg}`;                // 5 * 10^3 = 5,000
```

5,000개는 무해하다. 그러나 곱셈은 금방 벽에 부딪힌다. 10자 유니온 5개를 이어붙인 10^5 = 100,000 은 tsc 5.9.2 에서 곶바로 `error TS2590: Expression produces a union type that is too complex to represent` 로 죽는다. 한 단계 낮춘 9 × 10^4 = 90,000 은 통과하지만 공짜가 아니다. 빈 파일 기준 Types 13,135개가 이 한 줄 때문에 113,152개로 늘고 Check time 이 0.42s → 0.52s 가 된다. 타입 하나가 프로젝트 전체 타입 수를 8배로 부풀린 셈이다.

이 100,000 은 튜닝 옵션이 아니라 `src/compiler/checker.ts` 에 하드코딩된 상수다. 유니온 생성 경로의 `if (size >= 100000)` 검사가 곶바로 `Expression_produces_a_union_type_that_is_too_complex_to_represent` 를 발생시킨다. 서브타입 검사 쪽에도 별도 휴리스틱이 있어, 100,000회 검사 시점에 남은 작업량을 추정해 1,000,000을 넘을 것 같으면 같은 에러를 낸다.

## 2. `intrinsic` — 왜 `Uppercase` 는 라이브러리가 아닌가

`lib.es5.d.ts` 를 열어보면 네 개의 문자열 유틸리티가 이렇게 선언돼 있다.

```ts
type Uppercase<S extends string> = intrinsic;
type Lowercase<S extends string> = intrinsic;
type Capitalize<S extends string> = intrinsic;
type Uncapitalize<S extends string> = intrinsic;
```

`intrinsic` 은 타입이 아니라 "본체는 컴파일러 안에 있다"는 마커 키워드다. checker 에는 `IntrinsicTypeKind` enum 과 이름 → kind 매핑 테이블이 있고, `applyStringMapping` 이 문자열 리터럴을 받아 JS 런타임의 `toUpperCase()` 등을 그대로 호출한다. (TS 5.4 에서 추가된 `NoInfer<T>` 도 같은 `intrinsic` 메커니즘을 쓴다.)

왜 타입 레벨로 구현하지 않았을까. 타입 레벨 `Uppercase` 를 직접 짜려면 26쌍 이상의 문자 매핑 테이블과 문자 단위 재귀가 필요하다. 문자 하나당 conditional type 인스턴스화가 최소 한 번 발생하므로 18자 식별자 하나에 수십 번이 붙고, 유니코드까지 고려하면 테이블이 통제 불능이 된다. 반면 intrinsic 은 문자열 길이와 무관하게 인스턴스화 1회에 네이티브 연산 1회다. **표현 가능한 것과 실용적으로 계산 가능한 것이 다르다**는 사실을 컴파일러 팀이 스스로 인정한 지점이 이 네 줄이다.

## 3. `infer` 의 매칭은 greedy 가 아니라 leftmost-shortest 다

정규식에 익숙하면 `` T extends `${infer Head}.${infer Rest}` `` 를 greedy 로 오해하기 쉽다. 실제는 반대다.

```ts
type Head<T> = T extends `${infer H}.${infer R}` ? [H, R] : never;
type X = Head<'a.b.c'>;   // ["a", "b.c"]  — ["a.b", "c"] 가 아니다
```

근거는 checker 의 `inferFromLiteralPartsToTemplateLiteral` 이다. 이 함수는 먼저 시작 텍스트와 끝 텍스트가 각각 `startsWith` / `endsWith` 로 맞는지 확인한 뒤, 중간 구분자들에 대해 `getSourceText(s).indexOf(delim, p)` 로 **현재 위치 이후 첫 번째 출현 위치**를 찾는다. `indexOf` 이므로 앞쪽 플레이스홀더는 항상 최단으로 끊기고, 남은 전부가 뒤쪽 플레이스홀더로 간다.

파서 설계에는 두 가지 결과가 따라온다. 첫째, 재귀 파서는 자연스럽게 **왼쪽에서 오른쪽으로 한 토큰씩 갉아먹는** 형태가 된다. `[Head, Rest]` 로 쪼개고 `Rest` 를 다시 넣는 구조가 언어 규칙과 정확히 맞물린다. 둘째, 오른쪽 끝부터 자르려면 패턴을 뒤집을 방법이 없어 재귀로 끝까지 밀어 마지막 결과를 취하는 우회가 필요하고, 인스턴스화 비용이 토큰 수만큼 더 든다. 확장자 추출처럼 "마지막 점 뒤" 를 원하는 요구가 은근히 비싼 이유다.

## 4. `infer X extends number` 와 라운드트립 검증

TS 4.7(2022-05)이 `infer` 에 `extends` 제약을 허용했고, TS 4.8(2022-08)이 그 제약을 템플릿 리터럴 안에서 **리터럴 파싱 트리거**로 승격시켰다. 제약이 원시 타입이면 컴파일러가 문자열을 그 원시 타입으로 파싱하려 시도한다.

```ts
type Num<S extends string> = S extends `${infer N extends number}` ? N : never;
type A = Num<'100'>;   // 100   (number 가 아니다)
type B = Num<'true'>;  // never
```

한계는 릴리스 노트가 명시한 **round-trip 검사**다. 문자열을 원시값으로 파싱한 뒤 다시 문자열로 출력했을 때 원본과 일치하지 않으면 리터럴이 아니라 기반 타입으로 폴백한다. tsc 5.9.2 로 직접 확인한 결과는 다음과 같다.

| 입력 문자열 | `infer N extends number` 결과 | 이유 |
| --- | --- | --- |
| `'100'` | `100` | `String(Number('100')) === '100'` |
| `'1.0'` | `number` | 되돌리면 `'1'` 이라 불일치 |
| `'007'` | `number` | 되돌리면 `'7'` |
| `'1e3'` | `number` | 되돌리면 `'1000'` |
| `'+5'` | `number` | 되돌리면 `'5'` |

즉 입력 형식이 정규화돼 있지 않은 곳에서는 이 기법이 조용히 `number` 로 떨어진다. 에러는 나지 않고 "왜 리터럴이 안 잡히지" 만 남는다. 리터럴 정밀도가 계약의 일부라면 파싱 결과가 `number` 인지를 별도 조건 분기로 검증하는 편이 안전하다.

## 5. 실전 파서 세 가지

**(a) 경로 파라미터 추출.** React Router / Express 스타일 경로에서 파라미터 객체를 뽑아난다.

```ts
type Params<S extends string> =
  S extends `${string}:${infer P}/${infer Rest}` ? { [K in P]: string } & Params<`/${Rest}`>
  : S extends `${string}:${infer P}` ? { [K in P]: string }
  : {};

type R = Params<'/users/:id/posts/:postId'>;
// { id: string } & { postId: string }
```

앞의 `${string}` 이 `:` 직전까지를 흡수하고, leftmost-shortest 규칙 덕분에 `infer P` 가 다음 `/` 전까지만 잡는다. 경로 40개를 선언해 측정하면 Instantiations 가 기준선 2,928에서 5,106으로, 즉 경로당 약 54회 증가했다. 실서비스 라우트 테이블에서 충분히 감당 가능한 비용이다.

**(b) 쿼리스트링/CSV 분해.** 구분자로 토큰을 자르는 범용 `Split`.

```ts
type Split<S extends string, D extends string> =
  S extends `${infer H}${D}${infer R}` ? [H, ...Split<R, D>] : [S];

type Q = Split<'a=1&b=2&c=3', '&'>;  // ["a=1", "b=2", "c=3"]
```

이 버전은 6절에서 볼 이유로 **토큰 48개**가 한계다. 49개부터 TS2589 가 터진다.

**(c) 점 표기 딥 경로.** 설정 객체의 중첩 키를 문자열로 안전하게 참조한다.

```ts
type Prim = string | number | boolean | null | undefined | Date;

type Path<T> = T extends Prim ? never
  : { [K in keyof T & string]: T[K] extends Prim ? K : K | `${K}.${Path<T[K]>}` }[keyof T & string];

type Get<T, P extends string> =
  P extends `${infer K}.${infer Rest}` ? K extends keyof T ? Get<T[K], Rest> : never
  : P extends keyof T ? T[P] : never;

type Cfg = { server: { http: { port: number; host: string } }; db: { url: string } };
type Port = Get<Cfg, 'server.http.port'>;   // number
```

중첩 설정 객체 하나에 `Path<Cfg>` 를 적용했을 때 Instantiations 는 2,928 → 3,200 (약 272회)이었다. `Path<T>` 는 매핑 타입 + 유니온 인덱싱이므로 **객체 폭이 넓어질수록 유니온이 곱셈으로 커진다**. 키 20개짜리 객체가 3단 중첩이면 후보 경로 유니온이 수천 개가 된다.

## 6. 꼬리 재귀 제거의 정확한 조건과 TS2589 의 의미

TS 4.5(2021-11)가 conditional type 에 꼬리 재귀 제거를 도입했다(PR #45711). 릴리스 노트의 조건 서술은 명확하다. **conditional type 의 한 분기가 곶바로 또 다른 conditional type 일 때**, 즉 재귀 호출의 결과를 받아 아무 가공도 하지 않고 그대로 반환할 때만 중간 인스턴스화를 생략한다.

```ts
// 꼬리 재귀 O — 결과를 그대로 반환
type TrimLeft<T extends string> = T extends ` ${infer R}` ? TrimLeft<R> : T;

// 꼬리 재귀 X — 결과를 유니온에 합성한다
type GetChars<S> = S extends `${infer C}${infer R}` ? C | GetChars<R> : never;
```

`C | GetChars<R>` 처럼 결과를 유니온·튜플·객체에 **끼워 넣는 순간** 꼬리 위치가 아니게 되고 최적화가 꺼진다. 5절 (b)의 `[H, ...Split<R, D>]` 도 마찬가지다.

최적화가 꺼진 재귀는 `instantiateTypeWithAlias` 의 깊이 가드에 걸린다. checker.ts 5.9.2 의 해당 분기는 `if (instantiationDepth === 100 || instantiationCount >= 5000000)` 이고, 여기서 TS2589 를 내고 `errorType` 을 반환한다. 즉 TS2589 는 "무한 재귀를 증명했다"가 아니라 **"무한일 가능성이 높으니 여기서 포기한다"는 휴리스틱 중단**이다. 타입은 error type 으로 대체되어 그 뒤 검사는 전부 무의미해진다.

꼬리 재귀 경로는 별도 카운터를 쓴다. `getConditionalType` 안의 루프에 `if (tailCount === 1000)` 검사가 있고, 여기 걸리면 같은 TS2589 를 낸다. 같은 에러 코드지만 도달 경로도 한계값도 다르다는 점이 중요하다.

## 7. 누산기 패턴: 48 → 999

실측이 위 구현을 그대로 확인해 준다. 문자 단위 재귀로 동일 로직을 두 버전 작성해 tsc 5.9.2 로 경계를 이분 탐색했다.

| 구현 | 꼬리 위치 | 성공 최대 길이 | 실패 지점 |
| --- | --- | --- | --- |
| `C \| GetChars<R>` (유니온 합성) | X | 48 | 49자에서 TS2589 |
| `GetChars<R, C \| Acc>` (누산기) | O | 999 | 1000자에서 TS2589 |
| `[H, ...Split<R,D>]` (튜플 합성) | X | 48 토큰 | 49 토큰에서 TS2589 |
| `Split<R,D,[...Acc,H]>` (누산기) | O | 1000+ 토큰 | — |

리팩터링은 기계적이다. 결과를 합성하던 자리를 타입 파라미터로 내리고, 종료 분기에서 누산기를 반환한다.

```ts
// before: 48자 한계
type Chars<S> = S extends `${infer C}${infer R}` ? C | Chars<R> : never;

// after: 999자까지
type Chars<S, Acc = never> = S extends `${infer C}${infer R}` ? Chars<R, C | Acc> : Acc;
```

주의할 함정이 있다. **한계가 20배로 늘어난다고 비용이 줄어드는 것은 아니다.** 30개 쿼리스트링을 파싱하는 동일 시나리오에서 naive 는 Instantiations 6,068 / Types 13,928, 누산기 버전은 6,078 / 13,934 로 사실상 동일했다. 누산기는 *천장*을 올릴 뿐 *단가*를 낮추지 않는다. 그리고 천장까지 실제로 올라가면 대가가 크다. 999개 토큰을 누산기 `Split` 로 분해한 파일 하나의 측정값은 Types 518,609개, Memory 264,821K, Check time 0.76s 였다. 소스 코드 두 줄이 프로젝트 타입 수를 50만 개로 만든 것이다.

## 8. 진단 절차와 손절 기준

병목은 감으로 찾지 않는다. 순서는 이렇다.

1. `tsc --noEmit --extendedDiagnostics` 로 `Instantiations`, `Types`, `Check time`, `Memory used` 기준선을 잡는다. 빈 파일 기준선(Types 13,135 / Instantiations 2,928)을 먼저 재고 **델타로 읽어야** 의미가 있다.
2. 의심 타입을 주석 처리하며 델타를 비교한다. 한 줄이 Instantiations 를 수천 단위로 밀어 올리면 그게 범인이다.
3. `tsc --noEmit --generateTrace ./trace-out` 을 실행하면 디렉터리에 `trace.json` 과 `types.json` 이 생성된다(TS 4.1+). `trace.json` 은 Chrome DevTools 의 Performance 탭이나 `chrome://tracing` 에 그대로 로드되며, `checkSourceFile` / `structuredTypeRelatedTo` 이벤트의 duration 으로 어느 선언이 오래 걸리는지 확인할 수 있다. 앞서 본 `instantiateType_DepthLimit` 도 이 트레이스에 instant 이벤트로 기록된다.

여기서 마주하는 트레이드오프가 **"타입이 예쁘다" vs "IDE 가 느리다"** 다. `tsc` 는 전체 파일을 한 번 검사하지만 편집기의 tsserver 는 **키 입력마다** 해당 파일과 의존 파일을 재검사한다. Check time 0.76s 짜리 타입이 파일 상단에 있으면 자동완성 한 번에 그 비용을 다시 낸다. 개인적으로는 Instantiations 델타가 파일당 10,000을 넘거나 hover 응답이 눈에 띄게 늦어지면 설계를 의심한다.

타입 레벨 파싱을 **쓰지 말아야 할** 판단 기준은 다음과 같다.

- **입력이 런타임에 결정되는 경우.** 서버 응답 JSON, 환경변수, 사용자 입력은 애초에 리터럴 타입이 아니다. `string` 이 들어오면 모든 파서가 `string` 또는 `never` 로 무너진다. Zod 같은 런타임 스키마로 검증하고 `z.infer` 로 타입을 역산하는 쪽이 정답이다.
- **문자열 집합이 크고 자주 바뀌는 경우.** OpenAPI 스펙의 수백 개 엔드포인트를 템플릿 리터럴 유니온으로 표현하면 1절의 카디널리티 벽에 바로 닿는다. `openapi-typescript` 류의 **코드 생성**이 낫다. 생성된 `.d.ts` 는 이미 전개된 리터럴이라 인스턴스화 비용이 0이다.
- **반복 깊이가 수백 단위인 경우.** 7절처럼 한계를 999까지 늘릴 수는 있어도 Types 50만 개는 팀 전체의 편집기 경험을 망친다.
- 반대로 **입력이 코드에 리터럴로 고정돼 있고, 반복 깊이가 수십 이하이며, 잘못된 문자열이 컴파일 타임에 막히는 것이 가치 있는 경우**(라우트 경로, i18n 키, 설정 딥 경로)에는 비용 대비 효과가 확실하다. Spring 의 `@Value` 오타를 런타임에야 발견하는 경험과 비교하면, 그 영역이 타입 레벨 파서가 값을 하는 자리다.

## 참고

- TypeScript 4.1 Release Notes — Template Literal Types, Key Remapping: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- TypeScript 4.5 Release Notes — Tail-Recursion Elimination on Conditional Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- TypeScript 4.8 Release Notes — Improved Inference for `infer` Types in Template String Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- Handbook — Template Literal Types: https://www.typescriptlang.org/docs/handbook/2/template-literal-types.html
- microsoft/TypeScript PR #45711 — Tail recursion elimination on conditional types: https://github.com/microsoft/TypeScript/pull/45711
- microsoft/TypeScript PR #48094 — Infer type from string literal in template literal placeholder: https://github.com/microsoft/TypeScript/pull/48094
- type-challenges — 템플릿 리터럴 파서 연습 문제 모음: https://github.com/type-challenges/type-challenges
