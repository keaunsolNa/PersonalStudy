Notion 원본: https://app.notion.com/p/40e5a06fd6d3818b8cf2cb7ac30566d5?pvs=204

# TypeScript 구조적 타입 호환성의 경계: Freshness·Excess Property Check와 unique symbol 기반 명목적(Nominal) 타이핑 및 private 필드 효과

> 2026-11-04 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 객체 리터럴에만 적용되는 Freshness 표식이 사라지는 경로를 tsc 진단 메시지로 추적한다
- 약한 타입(weak type) 검사와 Excess Property Check를 서로 다른 규칙으로 구분한다
- `private`와 `#private` 멤버가 클래스 호환성을 명목적으로 바꾸는 원리를 컴파일 결과와 런타임 출력으로 비교한다
- `unique symbol` 브랜드와 문자열 리터럴 브랜드의 식별 강도를 실험하고 상황별로 선택한다

## 1. 구조적 타입 시스템의 기본선과 실험 환경

TypeScript는 이름이 아니라 모양으로 호환성을 판단한다. S가 T에 대입 가능하려면 T가 요구하는 멤버를 S가 갖고 있으면 충분하고, S가 더 많이 갖고 있어도 상관없다. 덕분에 JavaScript의 덕 타이핑을 타입화할 수 있지만 모양만 같은 `UserId`와 `OrderId`를 섞어 쓰는 사고도 허용된다. 이 문서는 규칙에 뚫린 두 예외(객체 리터럴의 초과 속성 검사, 클래스의 private 멤버)와, 예외가 없는 곧에 예외를 만드는 기법(unique symbol 브랜드)을 한 축으로 묶어 본다.

모든 코드는 아래 환경에서 직접 컴파일했고 에러 메시지는 출력 그대로 옮겼다.

```bash
$ tsc -v && node -v
Version 7.0.2
v22.23.4
```

tsconfig는 strict, target ES2022, noEmit이다.

## 2. Excess Property Check: 리터럴 위치에서만 걸리는 검사

Freshness는 "이 객체 타입이 방금 리터럴 표현식에서 만들어졌다"는 컴파일러 내부 표식이다. 표식이 붙은 타입을 다른 객체 타입에 대입할 때만 대상에 없는 속성이 에러가 된다. 값이 변수에 한 번 담기면 표식이 사라지고 순수한 구조 비교만 남는다.

```ts
interface Point { x: number; y: number }
const p1: Point = { x: 1, y: 2, z: 3 };            // 직접 대입
const tmp = { x: 1, y: 2, z: 3 };
const p2: Point = tmp;                             // 변수 경유
const p3: Point = { x: 1, y: 2, z: 3 } as Point;   // 단언
const p4: Point = ({ x: 1, y: 2, z: 3 });          // 괄호
function f(p: Point) {}
f({ x: 1, y: 2, extra: 1 });                       // 인자
f(tmp);
const arr: Point[] = [{ x: 1, y: 2, w: 0 }];       // 배열 요소
const p5: Point = Math.random() > 0.5
  ? { x: 1, y: 2, q: 1 } : { x: 3, y: 4 };         // 조건식 분기
```

```text
a.ts(2,33): error TS2353: Object literal may only specify known properties, and 'z' does not exist in type 'Point'.
a.ts(6,34): error TS2353: Object literal may only specify known properties, and 'z' does not exist in type 'Point'.
a.ts(8,17): error TS2353: Object literal may only specify known properties, and 'extra' does not exist in type 'Point'.
a.ts(10,37): error TS2353: Object literal may only specify known properties, and 'w' does not exist in type 'Point'.
a.ts(11,55): error TS2353: Object literal may only specify known properties, and 'q' does not exist in type 'Point'.
```

직접 대입, 괄호, 인자, 배열 요소, 조건식 분기는 에러가 났다. 같은 값이라도 `tmp`를 거친 `p2`와 `f(tmp)`는 통과했다. 눈에 띄는 곧은 단언 `as Point`(5번째 줄)가 에러 없이 통과했다는 점이다. 단언은 리터럴을 `Point`로 되돌려 비교하는 위치가 아니라 신선도를 제거하는 위치로 작동하므로, 초과 속성 검사를 피하려는 의도치 않은 우회로가 된다. 반면 괄호는 표식에 영향을 주지 않는다.

판별 유니온 `{ kind: "a"; a: number } | { kind: "b"; b: string }`에 `{ kind: "a", a: 1, b: "x" }`를 대입하면 TS2353 메시지의 대상 타입이 유니온 전체가 아니라 좁혀진 `{ kind: "a"; a: number; }`로 나와, 판별 속성으로 멤버를 좁힌 뒤 초과 속성을 따짐을 알 수 있다. 스프레드는 검사 대상이 아니다. `{ ...tmp }`와 `{ x: 1, y: 2, ...{ z: 3 } }`는 이번 실험에서 에러 없이 통과했다.

## 3. Freshness가 사라지는 경로: 검사가 새는 지점

이 검사는 타입 안전성의 보장이 아니라 오타를 잡는 휴리스틱에 가깝다. 어디서 표식이 사라지는지 알아야 기대해도 되는 구간을 가를 수 있다.

```ts
function make() { return { x: 1, y: 2, z: 3 }; }
const a: Point = make();                                    // 함수 반환
function id<T>(v: T): T { return v; }
const b: Point = id({ x: 1, y: 2, z: 3 });                  // 제네릭 통과
const c: Point = Object.assign({}, { x: 1, y: 2, z: 3 });   // 교차 타입 결과
const fn: () => Point = () => ({ x: 1, y: 2, z: 3 });       // 화살표 반환
function g<T extends Point>(p: T) { return p; }
g({ x: 1, y: 2, z: 3 });                                    // 제약 있는 제네릭
const d: Point & { [k: string]: number } = { x: 1, y: 2, z: 3 }; // 인덱스 시그니처
```

이 블록에서는 TS2353이 한 건도 나오지 않았다. 여섯 경로가 전부 통과한 것이다. 반환 타입이 먼저 추론되거나 타입 인자 T가 리터럴 타입으로 추론되면 `Point`와 비교하는 시점에는 신선하지 않은 타입이 된다. 인덱스 시그니처는 임의 키 자체를 합법으로 만든다.

보강책으로 반환 타입을 명시하면 검사가 되살아난다.

```ts
function make(): Point { return { x: 1, y: 2, z: 3 }; }
const fn: () => Point = (): Point => ({ x: 1, y: 2, z: 3 });
```

```text
e.ts(2,47): error TS2353: Object literal may only specify known properties, and 'z' does not exist in type 'Point'.
e.ts(3,53): error TS2353: Object literal may only specify known properties, and 'z' does not exist in type 'Point'.
```

반환 위치의 리터럴이 `Point`를 문맥 타입으로 직접 받기 때문이다. 정확한 키 집합이 필요한 곳(DB 컨럼 매핑 등)에서는 런타임 스키마 검증을 별도로 두는 편이 안전하다.

## 4. 약한 타입 검사는 별개의 규칙이다

모든 속성이 선택인 타입을 약한 타입이라 한다. 약한 타입으로의 대입은 신선도와 무관하게 공통 속성이 최소 하나 있어야 한다는 규칙을 따로 적용받는다.

```ts
interface Opt { a?: number; b?: string }
const weak = { c: 1 };
const o: Opt = weak;           // 변수 경유
const o2: Opt = { c: 1 };      // 리터럴
```

```text
a.ts(14,7): error TS2559: Type '{ c: number; }' has no properties in common with type 'Opt'.
a.ts(15,19): error TS2353: Object literal may only specify known properties, and 'c' does not exist in type 'Opt'.
```

같은 `{ c: 1 }`이라도 변수 경유는 TS2559, 리터럴은 TS2353으로 코드가 갈린다. 다만 `{ a: 1, c: 1 }`처럼 공통 속성이 하나라도 있으면 변수 경유 시 `c`는 통과하므로 옵션 객체의 오타를 전부 잡기에는 부족하다.

## 5. private과 #private: 구조적 타입 안의 명목적 구멍

클래스에 `private` 또는 `protected` 멤버가 있으면 그 선언에서 유래한 타입끼리만 호환된다.

```ts
class UserId  { private tag = 0; constructor(public v: string) {} }
class OrderId { private tag = 0; constructor(public v: string) {} }
class Plain1  { constructor(public v: string) {} }
class Plain2  { constructor(public v: string) {} }
const pl: Plain1 = new Plain2("x");     // private 없음
const u1: UserId = new OrderId("o");    // private 있음
const u2: UserId = { v: "x" };          // 리터럴
class Admin extends UserId {}
const ok: UserId = new Admin("a");      // 하위 클래스
class A { #x = 1; constructor(public v: string) {} }
class B { #x = 1; constructor(public v: string) {} }
const a: A = new B("b");
```

```text
b.ts(7,7): error TS2322: Type 'OrderId' is not assignable to type 'UserId'.
  Types have separate declarations of a private property 'tag'.
b.ts(8,7): error TS2741: Property 'tag' is missing in type '{ v: string; }' but required in type 'UserId'.
b.ts(15,7): error TS2322: Type 'B' is not assignable to type 'A'.
  Property '#x' in type 'B' refers to a different member that cannot be accessed from within type 'A'.
```

`pl`이 에러 없이 통과한 것이 대조군이다. 객체 리터럴은 private 멤버를 정의할 방법이 없으므로 구조적 위조가 불가능하고, `Admin extends UserId`는 선언을 상속하므로 통과한다. private은 `keyof`와 `implements`에도 영향을 준다.

```text
c.ts(21,7): error TS2322: Type '"secret"' is not assignable to type '"pub"'.
c.ts(25,7): error TS2720: Class 'D' incorrectly implements class 'C'. Did you mean to extend 'C' and inherit its members as a subclass?
  Types have separate declarations of a private property 'secret'.
```

`keyof C`가 `"pub"` 하나라는 점, 별도 클래스가 `implements C`로도 private을 만족시킬 수 없다는 점(TS2720)이 핵심이다.

그러나 TypeScript의 `private`은 컴파일 타임 장치일 뿐이다. 런타임 차이는 다음과 같다.

```ts
class T1 { private a = 1; #b = 2; c = 3; getB() { return this.#b } }
const t = new T1();
console.log(Object.keys(t), JSON.stringify(t), (t as any).a, (t as any).b, t.getB());
```

```text
$ node --experimental-strip-types r.ts
[ 'a', 'c' ] {"a":1,"c":3} 1 undefined 2
```

`private a`는 일반 프로퍼티라서 `Object.keys`와 `JSON.stringify`에 그대로 나오고 `as any`로 읽힌다. `#b`는 어디에도 나타나지 않고 메서드 안에서만 접근된다. 두 문법은 타입 검사 단계의 명목성은 같지만 은닉 강도가 다르다.

## 6. unique symbol 브랜드: 클래스 없이 만드는 명목성

원시 타입 값에는 private 멤버를 달 수 없다. 이때는 교차 타입에 런타임에 존재하지 않는 키를 덧붙이는 브랜드를 쓰고, 키를 `unique symbol`로 선언하면 선언 하나가 곧 하나의 고유 타입이 된다.

```ts
declare const UserBrand: unique symbol;
declare const OrderBrand: unique symbol;
type UserId2  = string & { readonly [UserBrand]: true };
type OrderId2 = string & { readonly [OrderBrand]: true };
const uid = "u1" as UserId2;
const oid: OrderId2 = uid;
const s: string = uid;
const bad: UserId2 = "plain";
```

```text
b.ts(22,7): error TS2322: Type 'UserId2' is not assignable to type 'OrderId2'.
  Type 'UserId2' is not assignable to type '{ readonly [OrderBrand]: true; }'.
    Property '[OrderBrand]' is missing in type 'String & { readonly [UserBrand]: true; }' but required in type '{ readonly [OrderBrand]: true; }'.
b.ts(24,7): error TS2322: Type 'string' is not assignable to type 'UserId2'.
  Type 'string' is not assignable to type '{ readonly [UserBrand]: true; }'.
```

`UserId2`에서 `string`으로의 대입(`s`)은 에러가 없다. 브랜드 타입은 기반 타입의 하위 타입이므로 읽기 방향은 자유롭고 쓰기 방향만 막힌다. `declare const`는 타입 위치에서만 쓰이므로 값이 만들어지지 않아 런타임에 흔적이 없다. 식별력은 선언 단위여서, `Symbol()`을 각각 호출한 두 `unique symbol` 키도 서로 다른 타입이며 한쪽을 다른 쪽에 대입하면 TS2741(`Property '[s2]' is missing in type 'T1'`)이 난다.

심볼 하나를 키로 두고 값 위치에 태그 문자열을 넣으면 범용 헬퍼가 된다.

```ts
declare const brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [brand]: B };
type USD = Brand<number, "USD">;
type KRW = Brand<number, "KRW">;
const usd = (n: number) => n as USD;
const krw: KRW = usd(3);
function addUsd(a: USD, b: USD): USD { return (a + b) as USD; }
addUsd(usd(1), 2);
const sum: USD = usd(1) + usd(2);
```

```text
d.ts(11,7): error TS2322: Type 'USD' is not assignable to type 'KRW'.
  Type 'USD' is not assignable to type '{ readonly [brand]: "KRW"; }'.
    Types of property '[brand]' are incompatible.
      Type '"USD"' is not assignable to type '"KRW"'.
d.ts(13,16): error TS2345: Argument of type 'number' is not assignable to parameter of type 'USD'.
d.ts(14,7): error TS2322: Type 'number' is not assignable to type 'USD'.
```

마지막 줄이 비용이다. 브랜드 숫자끼리 더한 결과는 `number`로 돌아오므로 산술마다 `as USD`로 되돌리는 래퍼가 필요하다.

## 7. 설계 선택: 어떤 경계를 어떤 도구로 닫을 것인가

아래 표의 각 칸은 2~6절의 실험에서 직접 확인한 항목만 적었다.

| 방법 | 런타임 흔적 | 원시 타입 적용 | 단언 없는 위조 | 확인한 한계 |
|---|---|---|---|---|
| Excess Property Check | 없음 | 해당 없음 | 변수 경유로 우회 | 반환·제네릭·콜백·단언에서 미작동 |
| 약한 타입 검사 | 없음 | 해당 없음 | 공통 속성 하나로 우회 | 선택 속성만 있는 타입에서만 작동 |
| `private` 멤버 | 프로퍼티 노출 | 불가 | 불가(TS2741) | `Object.keys`와 JSON에 나타남 |
| `#private` 필드 | 없음(진짜 은닉) | 불가 | 불가 | 직렬화에서 사라짐 |
| `unique symbol` 브랜드 | 없음 | 가능 | 불가(TS2322) | 산술 후 브랜드 소실 |
| 문자열 리터럴 브랜드 | 없음 | 가능 | 같은 태그면 가능 | 태그가 같으면 호환 |

문자열 리터럴 브랜드의 약점도 확인했다. 서로 다른 모듈에서 같은 태그를 쓰면 별개 타입인데도 호환된다.

```ts
type M1 = string & { __brand: "USD" };
type M2 = string & { __brand: "USD" };
const m: M2 = "x" as M1;   // 에러 없이 컴파일됨
```

`__brand: "USD"`라는 구조가 같기 때문이다. 반면 `unique symbol`은 선언이 정체성이라 충돌하지 않는다.

정리하면 은닉이 목적인 클래스 인스턴스는 `#private`, 타입 계층 구분만 필요하면 private 멤버 하나를 둔 클래스, ID·금액·단위 같은 원시 값은 `unique symbol` 브랜드가 맞다. 설정 객체의 오타는 Excess Property Check에 기대되 3절의 새는 경로에는 반환 타입 명시나 스키마 검증을 겹친다.

## 8. 실무 적용 시 주의점

첫째, `as` 단언이 곧 검증 면제이므로 `parseUserId(raw: string): UserId2` 같은 함수 한 곳에서만 승격하고, 린트로 다른 위치의 단언을 막는 편이 안전하다.

둘째, private 멤버를 둔 클래스의 테스트 더블은 `{ v: "x" }`가 TS2741로 거부되므로 `as unknown as UserId`나 하위 클래스를 써야 한다.

셋째, `protected constructor`를 두면 `new Sealed(1)`이 TS2674로 거부되어 생성 지점을 클래스 안으로 제한할 수 있다.

마지막으로, 외부 입력(HTTP, DB, 환경변수)은 경계에서 브랜드로 승격하고 내부 함수는 브랜드 타입만 받게 한다. 초과 속성이 문제되는 구간은 입력 스키마가 담당하고, 컴파일러의 Excess Property Check는 내부 코드 오타 방지용 보조 수단으로 두는 분업이 가장 안정적이다. 번들 크기와 컴파일 시간 영향은 측정하지 않았다.

## 참고

- TypeScript Handbook, Type Compatibility: https://www.typescriptlang.org/docs/handbook/type-compatibility.html
- TypeScript Handbook, Object Types - Excess Property Checks: https://www.typescriptlang.org/docs/handbook/2/objects.html#excess-property-checks
- TypeScript 2.4 Release Notes, Weak Type Detection: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-4.html
- TypeScript 2.7 Release Notes, unique symbol: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-7.html
- MDN Web Docs, Private elements: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Classes/Private_elements
- TypeScript GitHub Issue #202, Nominal types: https://github.com/microsoft/TypeScript/issues/202
