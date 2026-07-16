Notion 원본: https://www.notion.so/39f5a06fd6d38147b250ea410f4aa374

# TypeScript 구조적 타이핑과 Assignability 알고리즘 및 Excess Property Check

> 2026-07-16 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 컴파일러의 `isTypeAssignableTo` 관계 판정 경로를 추적한다
- Fresh Literal Type 과 Excess Property Check 의 발동 조건을 구분한다
- 재귀 타입 비교에서 발생하는 무한 확장을 Depth Limiter 관점에서 진단한다
- Branded Type 으로 구조적 타이핑의 안전 구멍을 봉쇄한다

## 1. 구조적 타이핑이란 무엇을 비교하는가

Java 는 명목적(nominal) 타입 시스템이다. `class Won { long amount; }` 와 `class Dollar { long amount; }` 는 필드가 완전히 동일해도 서로 대입할 수 없다. 이름이 다르기 때문이다. TypeScript 는 정반대다. 타입의 이름은 진단 메시지에 쓰이는 라벨일 뿐, 대입 가능성 판정에는 참여하지 않는다. 컴파일러가 보는 것은 오직 "이 타입이 가진 멤버의 집합과 각 멤버의 타입" 이다.

```typescript
interface Won { amount: number; }
interface Dollar { amount: number; }

const won: Won = { amount: 10_000 };
const dollar: Dollar = won; // OK — 구조가 같으므로 통과
```

이것은 버그가 아니라 설계 의도다. TypeScript 는 이미 존재하는 JavaScript 코드에 타입을 사후에 붙이는 것을 목표로 만들어졌고, JavaScript 에는 클래스 상속 계층을 선언하지 않고도 "오리처럼 걷고 오리처럼 울면 오리" 로 취급하는 duck typing 관행이 깊이 뿌리내려 있다. 명목적 타입을 강제했다면 기존 라이브러리에 타입을 붙이는 순간 모든 호출부가 깨졌을 것이다.

문제는 도메인 모델링에서 드러난다. 위 코드에서 원화를 달러 변수에 넣어도 컴파일러가 아무 말도 하지 않는다. Spring 백엔드에서 `WonAmount` 와 `DollarAmount` 를 별도 VO 로 나눠 컴파일 타임에 사고를 막던 습관이 TypeScript 에서는 그대로 통하지 않는다. 이 간극을 메우는 것이 §7 의 Branded Type 이다.

## 2. Assignability 판정 알고리즘의 실제 경로

컴파일러 내부에서 `S`(source) 가 `T`(target) 에 대입 가능한지 묻는 함수는 `checker.ts` 의 `isTypeRelatedTo(source, target, assignableRelation)` 이다. 이 함수는 여러 관계(relation)를 공유하는 일반화된 구조를 갖는다. 관계는 네 가지다.

| 관계 | 상수 | 용도 | 엄격도 |
|---|---|---|---|
| identity | `identityRelation` | 두 타입이 동일한가 | 가장 엄격 |
| subtype | `subtypeRelation` | 진짜 부분 타입인가 | 엄격 |
| strictSubtype | `strictSubtypeRelation` | union 축소용 | 엄격 |
| assignable | `assignableRelation` | 대입 가능한가 | 느슨 |
| comparable | `comparableRelation` | `as` 캐스팅 허용 여부 | 가장 느슨 |

`assignableRelation` 이 `subtypeRelation` 보다 느슨한 이유는 `any` 와 `enum` 때문이다. `any` 는 어디에나 대입 가능하지만 부분 타입은 아니다. 이 차이는 union 타입을 축소(reduce)할 때 드러난다. `string | any` 는 `any` 로 줄어들지만, 이는 subtype 관계로 판정한 결과다.

판정은 대략 다음 순서로 진행된다.

1. **빠른 경로**: `S === T` 면 즉시 true. 원시 타입 플래그 비트 비교로 끝나는 경우가 대부분이다.
2. **관계 캐시 조회**: `relation.get(source.id + "," + target.id)` 로 이전 판정 결과를 찾는다. 대형 프로젝트에서 체크 시간의 상당 부분이 이 캐시 히트로 절약된다.
3. **union/intersection 분해**: `S` 가 union 이면 모든 구성원이 `T` 에 대입 가능해야 한다(∀). `T` 가 union 이면 어느 하나에만 대입 가능하면 된다(∃). 이 비대칭이 union 이 많아질수록 체크가 조합 폭발하는 이유다.
4. **구조 비교(structuredTypeRelatedTo)**: 위 단계에서 결론이 나지 않으면 프로퍼티별 재귀 비교로 들어간다.

구조 비교는 다시 세 갈래다.

```typescript
// (a) 프로퍼티 비교 — T의 모든 프로퍼티가 S에 존재하고 대입 가능한가
// (b) 시그니처 비교 — 호출/생성 시그니처 (§4에서 상술)
// (c) 인덱스 시그니처 비교 — [key: string]: V
```

프로퍼티 비교의 방향에 주목해야 한다. **타깃의 프로퍼티를 기준으로 순회**한다. 소스에 여분의 프로퍼티가 있어도 상관없다. 이것이 구조적 타이핑의 "너비 부분 타입(width subtyping)" 이다.

```typescript
interface Point2D { x: number; y: number; }
const p3d = { x: 1, y: 2, z: 3 };
const p: Point2D = p3d; // OK — z는 검사 대상이 아니다
```

`optional` 프로퍼티는 예외 처리가 붙는다. 타깃 프로퍼티가 optional 이고 소스에 없으면 통과한다. 단 `exactOptionalPropertyTypes: true` 를 켜면 `{ x?: number }` 에 `{ x: undefined }` 를 넣는 것이 거부된다. "없는 것" 과 "undefined 인 것" 을 구분하기 시작한다.

## 3. Excess Property Check — 구조적 타이핑의 예외 조항

§2 의 규칙대로라면 다음 코드가 통과해야 한다. 그런데 에러가 난다.

```typescript
interface Config { host: string; port?: number; }

const c: Config = { host: "localhost", prot: 8080 };
//                                     ~~~~
// Object literal may only specify known properties,
// and 'prot' does not exist in type 'Config'.
```

앞 절에서는 여분 프로퍼티가 허용된다고 했는데 왜 여기서는 막힐까. **Excess Property Check(EPC)** 라는 별도 규칙이 대입 가능성 판정과 *독립적으로* 돌기 때문이다. EPC 는 타입 이론에서 파생된 규칙이 아니라 순수하게 실용적인 오타 방지 장치다.

발동 조건은 하나다. 소스 타입이 **fresh object literal type** 이어야 한다. "fresh" 는 객체 리터럴 표현식에서 방금 추론된 타입에 붙는 내부 플래그(`ObjectFlags.FreshLiteral`)다. 이 플래그는 타입이 변수에 한 번 대입되어 "widening" 을 거치면 사라진다.

```typescript
const raw = { host: "localhost", prot: 8080 }; // freshness 소멸
const c: Config = raw; // OK — EPC가 발동하지 않는다
```

이 동작이 EPC 의 성격을 정확히 보여준다. EPC 는 "당신이 방금 그 자리에서 손으로 쓴 리터럴이 어디에도 쓰이지 않는 필드를 갖고 있다면 오타일 가능성이 높다" 는 휴리스틱이다. 이미 변수에 담긴 값은 다른 곳에서도 쓰일 수 있으므로 참견하지 않는다.

union 타깃에서는 규칙이 더 미묘하다.

```typescript
type Shape =
	| { kind: "circle"; radius: number }
	| { kind: "square"; side: number };

const s: Shape = { kind: "circle", radius: 1, side: 2 };
// Error: 'side' does not exist in type '{ kind: "circle"; radius: number; }'
```

union 에 대해서는 각 구성원의 프로퍼티 이름을 **합집합**으로 모아 비교하는 것이 아니라, 먼저 discriminant 로 후보를 좁힌 뒤 그 후보 하나에 대해 EPC 를 돌린다. discriminant 가 없으면 모든 구성원 프로퍼티의 합집합으로 비교하는 완화된 경로를 탄다.

실무 우회 수단은 세 가지이며 안전도가 다르다.

```typescript
// 1) 변수 경유 — freshness 소멸. 안전하나 의도가 드러나지 않는다
const tmp = { host: "h", extra: 1 };
const a: Config = tmp;

// 2) as 단언 — comparableRelation으로 완화. 오타를 그대로 통과시킨다
const b = { host: "h", prot: 1 } as Config; // 위험

// 3) 인덱스 시그니처 추가 — 명시적 설계
interface OpenConfig extends Config { [key: string]: unknown; }
const c2: OpenConfig = { host: "h", anything: 1 }; // OK
```

2번은 피해야 한다. `as` 는 EPC 만 끄는 것이 아니라 관계 판정 자체를 `comparableRelation` 으로 낮춘다. 오타가 있는 채로 런타임에 도달한다.

## 4. 함수 시그니처의 반공변성과 메서드 bivariance 구멍

프로퍼티 타입이 함수일 때 비교는 파라미터와 반환값을 나눠 진행한다. 이론적으로 함수 타입 `(a: A) => R` 은 파라미터에 대해 **반공변(contravariant)**, 반환값에 대해 **공변(covariant)** 이어야 한다.

```typescript
type Animal = { name: string };
type Dog = Animal & { bark(): void };

declare let f: (a: Animal) => void;
declare let g: (d: Dog) => void;

f = g; // 반공변이면 에러여야 한다
g = f; // 반공변이면 OK
```

`strictFunctionTypes: true` 를 켜면 `f = g` 가 정확히 거부된다. `f` 를 호출하는 쪽은 임의의 `Animal` 을 넘길 수 있는데, 실제 구현인 `g` 는 `bark` 를 기대하므로 런타임 오류가 난다.

그런데 함정이 있다. **메서드 문법으로 선언된 함수는 `strictFunctionTypes` 를 켜도 여전히 bivariant 하게 비교된다.**

```typescript
interface WithMethod { handle(a: Animal): void; }   // 메서드 문법 — bivariant
interface WithProp { handle: (a: Animal) => void; } // 프로퍼티 문법 — contravariant

declare let m: WithMethod;
declare let dogM: { handle(d: Dog): void };
m = dogM; // OK — strictFunctionTypes를 켜도 통과한다 (구멍)

declare let p: WithProp;
declare let dogP: { handle: (d: Dog) => void };
p = dogP; // Error — 정상 거부
```

이 예외는 의도적이다. `Array<T>` 의 `push`, `Promise<T>` 의 `then` 등 표준 라이브러리 다수가 메서드 문법으로 선언돼 있고, 엄격한 반공변을 적용하면 `Array<Dog>` 를 `Array<Animal>` 로 넘기는 극히 흔한 패턴이 전부 깨진다. 배열은 원래 mutable 이므로 공변이 unsound 하지만, TypeScript 는 실용성을 택했다.

**따라서 콜백 타입은 프로퍼티 문법으로 선언하는 것이 안전하다.** 이벤트 핸들러나 전략 인터페이스처럼 대입이 실제로 일어나는 자리에서는 이 차이가 버그로 이어진다.

파라미터 개수도 비대칭이다. 소스가 타깃보다 파라미터가 **적으면** 통과한다.

```typescript
declare function each(cb: (v: number, i: number) => void): void;
each((v) => console.log(v)); // OK — 인자를 무시하는 것은 안전
```

`Array.prototype.forEach(x => ...)` 가 동작하는 근거가 이것이다.

## 5. 재귀 타입과 Depth Limiter

구조 비교는 재귀다. 재귀 타입에서는 무한 확장 위험이 있다.

```typescript
interface Node<T> { value: T; next: Node<T> | null; }
interface Item<T> { value: T; next: Item<T> | null; }

declare let n: Node<string>;
const i: Item<string> = n; // 어떻게 종료되는가?
```

`Node.next` 를 비교하려면 다시 `Node` 대 `Item` 비교가 필요하고, 이는 원래 질문이다. 컴파일러는 두 가지 장치로 종료를 보장한다.

**첫째, in-progress 스택.** `maybeKeys` 라는 배열에 현재 비교 중인 `(sourceId, targetId)` 쌍을 쌓아둔다. 재귀 도중 같은 쌍이 다시 등장하면 `Ternary.Maybe` 를 반환하고 낙관적으로 진행한다. 모든 하위 비교가 실패하지 않으면 최종적으로 true 로 확정한다. 이것이 coinductive 판정이며, 위 예시가 통과하는 이유다.

**둘째, depth limiter.** 제네릭 인스턴스화가 중첩되면 매번 *새로운* 타입 ID 가 생기므로 in-progress 검사에 걸리지 않는다.

```typescript
type Deep<T> = { value: T; child: Deep<{ wrapped: T }> };
```

`Deep<A>` → `Deep<{wrapped: A}>` → `Deep<{wrapped: {wrapped: A}}>` … 로 무한 생성된다. 컴파일러는 동일 제네릭 심볼의 인스턴스화 깊이를 세다가 **5** 를 넘으면 `Ternary.Maybe` 로 끊는다. 타입 인스턴스화 총 횟수 한도는 별도로 **500,000** 이며 초과하면 다음 에러가 난다.

```
Type instantiation is excessively deep and possibly infinite. ts(2589)
```

재귀 조건부 타입의 tail-recursion 깊이 한도는 **1000** 이다(TS 4.5 이상, tail 위치일 때). tail 이 아니면 **50** 에서 끊긴다. 타입 레벨 파서를 짤 때 이 숫자가 실질적 상한이 된다.

진단 도구는 `tsc --extendedDiagnostics` 와 `--generateTrace` 다.

```bash
tsc --noEmit --extendedDiagnostics
# Instantiations:   1_842_331   ← 100만 넘어가면 설계를 의심
# Check time:       12.4s
# Total time:       18.9s

tsc --noEmit --generateTrace ./trace
# trace/trace.json 을 chrome://tracing 또는 Perfetto 에 로드
# structuredTypeRelatedTo 프레임이 긴 구간이 병목
```

경험적으로 Instantiations 가 100만을 넘고 Check time 이 10초를 넘으면 어딘가에 조합 폭발하는 conditional type 이나 거대 union 이 있다. `--generateTrace` 결과에서 가장 넓은 `checkExpression` 프레임의 파일·라인을 보면 대체로 범인이 특정된다.

## 6. 인덱스 시그니처와 weak type detection

인덱스 시그니처 비교에는 비직관적 규칙이 있다.

```typescript
interface Dict { [key: string]: number; }
interface Named { name: number; age: number; }

declare let named: Named;
const d: Dict = named; // Error!
// Index signature for type 'string' is missing in type 'Named'.
```

`Named` 의 모든 프로퍼티가 `number` 인데도 거부된다. 인터페이스는 **선언 병합(declaration merging)** 으로 나중에 프로퍼티가 추가될 수 있으므로 컴파일러가 "모든 프로퍼티가 number 임" 을 보장할 수 없다. 반면 type alias 는 병합이 불가능하므로 암묵적 인덱스 시그니처가 부여된다.

```typescript
type NamedAlias = { name: number; age: number };
declare let na: NamedAlias;
const d2: Dict = na; // OK — type alias는 통과
```

`interface` 와 `type` 중 무엇을 쓸지의 실질적 판단 기준 하나가 여기에 있다. 외부에 확장 지점을 열어야 하면 `interface`, 값 객체처럼 닫힌 형태면 `type` 이 유리하다.

**weak type detection** 은 EPC 와 짝을 이루는 별도 규칙이다. 타깃의 모든 프로퍼티가 optional 이면 그 타입을 "weak type" 으로 보고, 소스와 공통 프로퍼티가 **하나도 없으면** 거부한다.

```typescript
interface Options { timeout?: number; retries?: number; }

const o: Options = { timeuot: 100 }; // Error — 겹치는 프로퍼티 0개
const o2: Options = {};              // OK — 빈 객체는 허용
```

`{}` 는 허용된다는 점이 중요하다. weak type detection 은 "옵션 객체를 넘겼는데 전부 오타여서 아무 옵션도 안 먹는" 사고를 막는 장치이고, 의도적 빈 객체는 막을 이유가 없다.

## 7. Branded Type — 구멍 봉쇄

§1 의 `Won`/`Dollar` 문제로 돌아간다. 구조적 타이핑을 명목적으로 만드는 표준 기법이 branding 이다.

```typescript
declare const brand: unique symbol;

type Brand<T, B extends string> = T & { readonly [brand]: B };

type Won = Brand<number, "Won">;
type Dollar = Brand<number, "Dollar">;

function toWon(n: number): Won { return n as Won; }
function toDollar(n: number): Dollar { return n as Dollar; }

const w = toWon(10_000);
const d: Dollar = w; // Error — brand가 다르므로 거부
// Type 'Won' is not assignable to type 'Dollar'.
//   Types of property '[brand]' are incompatible.
//     Type '"Won"' is not assignable to type '"Dollar"'.

const sum = w + toWon(500); // OK — number 연산은 그대로 동작
```

`unique symbol` 을 쓰는 이유는 필드 이름 충돌을 원천 차단하기 위해서다. 문자열 키(`__brand`)를 쓰면 다른 라이브러리의 브랜드와 겹칠 수 있고, `Object.keys` 같은 런타임 순회 코드에도 노출된다. 심볼은 `declare const` 로 선언만 하고 값을 만들지 않으므로 런타임에 아무 흔적도 남지 않는다.

trade-off 는 명확하다.

| 항목 | 영향 |
|---|---|
| 런타임 비용 | 0 — 타입 레벨에서만 존재 |
| 생성자 강제 | `as` 캐스팅이 필요하므로 팩토리 함수로 봉인 필요 |
| 진단 메시지 | brand 필드 불일치로 표시돼 다소 장황 |
| 외부 API 경계 | JSON 파싱 결과는 brand 가 없으므로 변환 계층 필수 |

Zod 와 결합하면 파싱 시점에 brand 를 붙여 경계를 깔끔히 만들 수 있다.

```typescript
import { z } from "zod";

const WonSchema = z.number().int().nonnegative().brand<"Won">();
type WonZ = z.infer<typeof WonSchema>; // number & z.BRAND<"Won">

const parsed = WonSchema.parse(req.body.amount); // 검증 통과 시에만 Won
```

Spring 에서 `@Valid` 로 DTO 경계를 방어하던 것과 같은 역할이다. 다만 TypeScript 는 런타임 타입 정보가 없으므로 검증 라이브러리가 그 자리를 대신한다.

## 8. 성능 관점의 실무 지침

대형 프로젝트에서 구조적 타이핑은 컴파일 시간의 주된 소비처다. 다음이 실측 기준의 개선 순서다.

**첫째, 거대 union 을 피한다.** union 대 union 비교는 최악의 경우 `|S| × |T|` 회의 관계 판정을 유발한다. 200개 문자열 리터럴 union 두 개를 비교하면 4만 회다. discriminated union 으로 바꾸면 컴파일러가 discriminant 로 후보를 1개로 좁힌 뒤 한 번만 비교한다.

```typescript
// 나쁨 — 판별자 없음, 모든 구성원 시도
type Event = { click: MouseEvent } | { key: KeyboardEvent } | ... ;

// 좋음 — kind로 O(1) 축소
type Event =
	| { kind: "click"; payload: MouseEvent }
	| { kind: "key"; payload: KeyboardEvent };
```

**둘째, 반환 타입을 명시한다.** export 되는 함수의 반환 타입을 생략하면 컴파일러가 매 호출부에서 추론 결과를 재사용하기 위해 타입을 구조화해야 한다. 명시하면 `.d.ts` 생성도 빨라지고 관계 캐시 히트율이 오른다. 특히 라이브러리 코드에서 효과가 크다.

**셋째, `interface` 를 선호한다.** 컴파일러는 interface 에 대해 "이름이 같으면 같은 타입" 이라는 빠른 경로를 갖는다. 반면 intersection 으로 조합된 type alias 는 매번 구조를 펼쳐 비교한다. `type A = B & C` 를 `interface A extends B, C` 로 바꾸는 것만으로 대형 코드베이스 체크 시간이 유의미하게 줄어든 사례가 다수 보고돼 있다.

**넷째, `skipLibCheck: true` 를 켠다.** `node_modules/**/*.d.ts` 전체를 서로 비교하는 비용을 제거한다. 부작용은 라이브러리 타입 정의 간 충돌을 놓치는 것인데, 대부분의 프로젝트에서 이 위험보다 시간 절감이 크다.

**다섯째, Project References 로 체크 단위를 쪼갠다.** 관계 캐시는 프로그램 인스턴스 단위이므로, 모놀리식 tsconfig 는 캐시가 매번 처음부터 채워진다. 참조로 분할하면 `.tsbuildinfo` 를 통해 변경된 프로젝트만 재검사한다.

## 9. 정리 — 언제 무엇을 의심할 것인가

증상별 원인 매핑이다.

| 증상 | 1차 의심 | 확인 방법 |
|---|---|---|
| 리터럴만 에러, 변수는 통과 | EPC / freshness | 변수로 뽑아 재현되는지 |
| 옵션 객체가 통째로 거부 | weak type detection | 공통 프로퍼티가 0개인지 |
| 인터페이스가 Dict 에 안 들어감 | 암묵적 인덱스 시그니처 부재 | `type` 으로 바꿔 통과하는지 |
| 콜백 대입이 이상하게 통과 | 메서드 문법 bivariance | 프로퍼티 문법으로 재선언 |
| ts(2589) | 인스턴스화 깊이 초과 | `--generateTrace` |
| 체크 시간 급증 | 거대 union / intersection | `--extendedDiagnostics` |
| 도메인 값 혼용 | 구조적 타이핑 본질 | Branded Type 도입 |

구조적 타이핑은 JavaScript 생태계와의 호환을 위해 선택된 설계이고, EPC·weak type detection·strictFunctionTypes 는 그 선택이 만든 안전 구멍을 사후에 메우는 실용적 패치다. 각 규칙이 "이론적 정합성" 이 아니라 "실제로 발생하는 버그 패턴" 을 겨냥하고 있다는 점을 이해하면, 왜 예외가 이렇게 많은지가 납득된다.

## 참고

- TypeScript Handbook — Type Compatibility (https://www.typescriptlang.org/docs/handbook/type-compatibility.html)
- TypeScript Wiki — Performance (https://github.com/microsoft/TypeScript/wiki/Performance)
- TypeScript Compiler Source — `src/compiler/checker.ts`, `isTypeRelatedTo` / `structuredTypeRelatedTo`
- TypeScript 2.4 Release Notes — Strict contravariance for callback parameters
- Zod Documentation — Branded types (https://zod.dev/)
- Effective TypeScript, Dan Vanderkam — Item 4: Get Comfortable with Structural Typing
