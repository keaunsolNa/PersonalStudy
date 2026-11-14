Notion 원본: https://www.notion.so/4185a06fd6d381af8b7cd1fb7c5209ba

# TypeScript 타입 격자와 할당 가능성: any·unknown·never·void, {} 와 object, strictNullChecks 의 경계

> 2026-11-14 신규 주제 · 확장 대상: TypeScript(타입 시스템 기초), JAVA(Object 와 제네릭 와일드카드)

## 학습 목표

- `unknown`·`never`·`any` 의 할당 방향을 `@ts-expect-error` 로 직접 확인한다
- `() => void` 문맥 타입과 `: void` 선언의 차이를 구분해 콜백 시그니처를 설계한다
- `{}`·`object`·`Object`·`Record<string, never>` 중 의도에 맞는 타입을 고른다
- `strictNullChecks`·`useUnknownInCatchVariables` 에 따른 할당 가능성 변화를 옵션을 바꿔 재현한다

## 1. 격자의 꼭대기와 바닥: unknown 과 never

타입을 값의 집합으로 보면 할당 가능성은 부분집합 관계에 가깝고 `|` 는 합집합, `&` 는 교집합이다. 이 격자의 꼭대기가 `unknown`(모든 값), 바닥이 `never`(빈 집합)다. 그래서 모든 타입은 `unknown` 에 대입되고, `never` 는 모든 타입에 대입된다. 반대 방향은 막히며, 아래 예제의 두 오류는 모두 TS2322 다.

이후 예제는 타입이 기대와 같은지를 컴파일러가 판정하도록 다음 헬퍼를 전역 선언 파일(`helpers.d.ts`)로 함께 컴파일한다. `Expect<Eq<A, B>>` 는 두 타입이 다르면 TS2344 로 실패한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;
```

```ts
declare const u: unknown;
declare const nv: never;
declare const an: any;

const top1: unknown = null;       // 모든 값은 unknown 에 대입된다
// @ts-expect-error unknown 은 unknown, any 외의 타입에 대입되지 않는다
const num: number = u;
const bot1: { id: number } = nv;  // never 는 모든 타입에 대입된다
// @ts-expect-error never 에는 any 조차 대입되지 않는다
const bot2: never = an;

type L1 = Expect<Eq<string | never, string>>;    // 합집합의 항등원
type L2 = Expect<Eq<string & unknown, string>>;  // 교집합의 항등원
type L3 = Expect<Eq<string | unknown, unknown>>; // 합집합의 흡수원
type L4 = Expect<Eq<string & never, never>>;     // 교집합의 흡수원
```

`unknown` 과 `never` 는 연산의 항등원이자 흡수원이다. 꼭대기 타입은 받기 쉽고 쓰기 어려우며, 바닥 타입은 그 반대다.

## 2. any: 격자 밖의 양방향 할당과 전염

`any` 는 격자의 한 점이 아니라 검사를 끄는 스위치다. 모든 타입이 `any` 에 대입되고, `any` 도 `never` 를 제외한 모든 타입에 대입된다. 더 큰 문제는 전염이다. `any` 가 섞인 표현식은 대부분 다시 `any` 로 추론된다.

```ts
declare const x: any;
const n1: number = x;             // any -> number
const a1: any = "s";              // string -> any

const r1 = x + 1;
const r2 = x.a.b();
const r3 = [1, x];
type R = [
  Expect<Eq<typeof r1, any>>,
  Expect<Eq<typeof r2, any>>,
  Expect<Eq<typeof r3, any[]>>,
  Expect<Eq<any | string, any>>,
  Expect<Eq<any & string, any>>,
  Expect<Eq<any & never, never>>,                 // 예외: never 가 이긴다
  Expect<Eq<any extends string ? 1 : 2, 1 | 2>>,  // 조건부 타입은 두 분기를 모두 취한다
];
type IsAny<T> = 0 extends 1 & T ? true : false;
type A1 = Expect<Eq<[IsAny<any>, IsAny<unknown>], [true, false]>>;
```

조건부 타입의 검사 대상이 `any` 면 두 분기의 합집합이 나오므로, 제네릭 유틸리티에 `any` 가 흘러들면 결과가 조용히 넓어진다. `IsAny` 는 `1 & any` 가 `any` 라는 성질을 이용한 판별식이다. `any` 는 마이그레이션 중 탈출구로는 값싸지만, 한 번 들어오면 접근·호출 결과까지 번져 오류 지점을 런타임으로 옮긴다.

## 3. unknown 을 좁히는 방법

`unknown` 은 `any` 처럼 무엇이든 받지만 좁히기 전에는 아무것도 할 수 없다. 프로퍼티 접근은 TS18046(`'v' is of type 'unknown'`)으로 막힌다. 좁히는 도구는 `typeof`, `instanceof`, `in`, 사용자 정의 타입 가드(`v is T`)다.

```ts
interface User { id: number; nick: string }

function isUser(v: unknown): v is User {
  return typeof v === "object" && v !== null
    && "id" in v && typeof v.id === "number"
    && "nick" in v && typeof v.nick === "string";
}
function rejected(v: unknown) {
  // @ts-expect-error 좁히기 전에는 프로퍼티 접근이 금지된다
  return v.id;
}
function describe(v: unknown): string {
  if (typeof v === "string") return `string(${v.length})`;
  if (v instanceof Date) return `date(${v.getFullYear()})`;
  if (isUser(v)) return `user(${v.nick})`;
  return "unknown";
}
console.log(describe("abc"), describe(new Date(2026, 10, 14)));   // string(3) date(2026)
console.log(describe({ id: 1, nick: "kim" }), describe(null));    // user(kim) unknown
```

`typeof v === "object"` 는 `object | null` 까지만 좁히므로 `v !== null` 을 더해야 한다. 그 뒤 `"id" in v` 가 프로퍼티 존재를 타입에 반영해 `v.id` 를 단언 없이 읽을 수 있다. 타입 가드는 본문이 실제로 그 타입을 보장하는지 컴파일러가 검증하지 않는다. 정확성은 테스트나 스키마 검증으로 따로 보장해야 한다.

## 4. never 가 생기는 네 지점

`never` 는 직접 쓰는 일보다 컴파일러가 만들어 내는 일이 많다. 아래 네 지점이 대표적이다.

```ts
// (1) 불가능한 교차, (2) 유니온에서의 소거
type X1 = Expect<Eq<string & number, never>>;
type X2 = Expect<Eq<{ kind: "a" } & { kind: "b" }, never>>;
type X3 = Expect<Eq<"a" | never, "a">>;
// (3) 조건부 타입 분배에서의 빈 유니온
type ToArray<T> = T extends unknown ? T[] : "no";
type X5 = Expect<Eq<ToArray<never>, never>>;       // never[] 도 "no" 도 아니다
type IsNever<T> = [T] extends [never] ? true : false;   // 튜플로 감싸 분배를 막는다
type X6 = Expect<Eq<IsNever<never>, true>>;

// (4) exhaustive 분기
type Shape = { kind: "circle"; r: number } | { kind: "rect"; w: number; h: number };
function assertNever(v: never): never {
  throw new Error(`unreachable: ${JSON.stringify(v)}`);
}
function area(s: Shape): number {
  switch (s.kind) {
    case "circle": return Math.PI * s.r ** 2;
    case "rect": return s.w * s.h;
    default: return assertNever(s);   // 여기서 s 는 never
  }
}
console.log(area({ kind: "rect", w: 2, h: 3 }));   // 6
try { area({ kind: "tri" } as unknown as Shape); }
catch (e) { console.log((e as Error).message); }   // unreachable: {"kind":"tri"}
```

`ToArray<never>` 가 `never[]` 가 아닌 이유는 `never` 가 원소 0개짜리 유니온이어서 분배할 대상이 없기 때문이다. `assertNever` 패턴에서는 `Shape` 에 `{ kind: "tri" }` 를 추가하고 `case` 를 빠뜨리면 `default` 줄이 TS2345 로 실패하는 것을 확인했다. 다만 외부 입력이 타입을 벗어나면 예제처럼 런타임에 `default` 로 들어오므로 `assertNever` 는 예외를 던지게 둔다.

## 5. void 반환 타입의 특수 할당 규칙

함수 타입에서 `void` 는 "반환값이 없다"가 아니라 "반환값을 쓰지 않겠다"는 뜻이다. 반환 자리가 `void` 면 어떤 값을 반환하는 함수든 대입된다. `forEach` 의 콜백 타입이 `=> void` 인데도 `out.push(v)` 를 그대로 넘길 수 있는 이유다. 반면 함수 선언이나 화살표 함수에 `: void` 를 직접 쓰면 값을 반환하는 순간 TS2322 가 난다.

```ts
const out: number[] = [];
[1, 2, 3].forEach(v => out.push(v));   // push 는 number 를 반환하지만 허용된다
console.log(out);                      // [ 1, 2, 3 ]

type VoidFn = () => void;
const f: VoidFn = () => 42;            // 반환값이 있는 함수를 대입할 수 있다
const r = f();
type V1 = Expect<Eq<typeof r, void>>;
console.log(r);                        // 42
// @ts-expect-error void 값은 참/거짓 판정에 쓸 수 없다
if (r) { }

// @ts-expect-error 선언에 직접 쓴 void 는 엄격하다
function g(): void { return 42; }
// @ts-expect-error 화살표 함수에 직접 표기해도 같다
const h = (): void => 42;

[1, 2].forEach(async id => { await Promise.resolve(id); });   // Promise 반환도 통과한다
```

호출 결과 `r` 의 타입은 `void` 지만 런타임 값은 42 가 그대로 남는다. 그래서 `void` 값은 조건식에 쓸 수 없다(TS1345). 이 규칙의 대가는 마지막 줄이다. `async` 콜백도 `() => void` 자리에 통과하므로 `forEach` 는 Promise 를 기다리지 않고, 타입 검사만으로는 이 실수가 드러나지 않는다.

## 6. {} · object · Object 와 NonNullable

`{}` 는 프로퍼티 요구가 없는 타입이라 원시값까지 받으며, 결과적으로 `null`·`undefined` 를 제외한 모든 값이다. `object` 는 원시값이 아닌 값만 받는다. `Object` 는 원시값도 받지만 `toString` 같은 멤버의 시그니처 호환까지 검사한다. 아래 표는 `strict` 에서 직접 대입해 확인한 결과다.

| 대입하는 값 | `{}` | `object` | `Object` | `unknown` |
| --- | --- | --- | --- | --- |
| `1`, `"s"` | 가능 | 오류 | 가능 | 가능 |
| `{ a: 1 }`, `[]` | 가능 | 가능 | 가능 | 가능 |
| `null`, `undefined` | 오류 | 오류 | 오류 | 가능 |

```ts
const e1: {} = 1;
// @ts-expect-error
const e3: {} = null;
// @ts-expect-error object 는 원시값을 받지 않는다
const o1: object = 1;
const O1: Object = 1;                  // Object 인터페이스는 원시값도 받는다
// @ts-expect-error Object 는 멤버 시그니처 호환까지 검사한다
const O2: Object = { toString() { return 1; } };
const e4: {} = { toString() { return 1; } };

function narrow(u: unknown) {
  if (u != null) {
    type N1 = Expect<Eq<typeof u, {}>>;   // unknown 에서 null, undefined 를 빼면 {}
  }
}
type N2 = Expect<Eq<NonNullable<unknown>, {}>>;

type Empty = Record<string, never>;
const m1: Empty = {};
// @ts-expect-error 어떤 키든 값 타입이 never 라서 넣을 수 없다
const m2: Empty = { a: 1 };
```

`unknown` 은 `{} | null | undefined` 처럼 동작한다. `u != null` 로 좁히면 `{}` 가 남고, 5.6.3 의 `lib.es5.d.ts` 에서 `NonNullable<T>` 는 `T & {}` 로 정의되어 있다. 따라서 `{}` 는 "빈 객체"가 아니다. 프로퍼티가 없는 객체를 뜻하려면 `Record<string, never>` 를 쓴다.

## 7. strictNullChecks 와 null·undefined 의 위치

`strictNullChecks` 가 꺼져 있으면 `null` 과 `undefined` 는 `never` 를 제외한 모든 타입에 대입된다. 유니온에 적은 `null` 도 타입에서 사라져 `string | null` 이 `string` 과 같아진다. 다음 파일은 `--strict --strictNullChecks false` 로 오류 없이 컴파일된다.

```ts
const s1: string = null;
const s2: string = undefined;
const e1: {} = null;
const o1: object = null;
// @ts-expect-error off 에서도 never 에는 null 을 넣을 수 없다
const n1: never = null;
type S1 = Expect<Eq<string | null, string>>;   // 유니온의 null 이 사라진다
function len(s: string | null) {
  return s.length;                             // 검사 없이 통과
}
try { len(null); } catch (e) { console.log((e as Error).message); }
// Cannot read properties of null (reading 'length')
```

같은 파일을 `--strict` 만으로 컴파일하면 1~4 행이 TS2322, `S1` 이 TS2344, `s.length` 가 TS18047(`'s' is possibly 'null'`)로 실패한다. 옵션을 켜면 `null`·`undefined` 는 독립된 타입이 되어, 받으려는 자리마다 유니온으로 명시해야 한다. 옵션을 켜는 비용은 기존 코드의 수정량이지만, 끈 채로는 6번의 `{}`·`NonNullable` 구분이 모두 무의미해진다.

## 8. 제네릭 제약과 catch 절 변수

제약 없는 타입 매개변수 `T` 는 `T extends unknown` 과 같아서 `null`·`undefined` 가 될 수 있다. 그래서 `T` 는 `{}` 에 대입되지 않는다(TS2322). 이 대입은 4.7.4 에서는 통과하고 4.8.4 부터 오류다. 반대로 `u != null` 로 좁힌 `unknown` 을 `{}` 에 대입하는 코드는 4.7.4 에서 오류, 4.8.4 부터 통과다. null 을 배제하려면 `T extends {}` 로 제약하거나 본문에서 좁혀 `T & {}` 를 얻는다.

```ts
function f1<T>(x: T) {
  const u: unknown = x;
  // @ts-expect-error 제약 없는 T 는 null, undefined 일 수 있다
  const o: {} = x;
  if (x != null) {
    const ok: {} = x;                  // 여기서 x 는 T & {}
  }
}
function f2<T extends unknown>(x: T) {
  // @ts-expect-error extends unknown 은 제약이 없는 것과 같다
  const o: {} = x;
}
function f3<T extends {}>(x: T) {
  const o: {} = x;
  return typeof x;
}
f3(1);
// @ts-expect-error
f3(null);
```

`catch` 절 변수도 같은 문제다. JavaScript 는 무엇이든 던질 수 있으므로 `strict` 에 포함된 `useUnknownInCatchVariables` 는 변수 타입을 `unknown` 으로 만든다. 옵션을 `false` 로 주면 아래의 `e.message` 줄은 오류 없이 통과한다(`any`).

```ts
function parsePort(input: string): number {
  try {
    const n = Number(input);
    if (!Number.isInteger(n)) throw `not an integer: ${input}`;   // Error 가 아닌 값
    return n;
  } catch (e) {
    // @ts-expect-error e 는 unknown
    e.message;
    if (e instanceof Error) return -1;
    console.log(typeof e, e);              // string not an integer: 8.5
    return -2;
  }
}
console.log(parsePort("80"), parsePort("8.5"));   // 80 -2
```

## 9. Java 와의 대응, 그리고 실무 선택 기준

Java 의 `Object` 는 참조 타입의 꼭대기일 뿐 원시 타입은 박싱을 거쳐야 들어가고, `null` 은 모든 참조 타입에 대입된다. 즉 Java 의 기본 모델은 `strictNullChecks` 를 끈 TypeScript 에 가깝다. `List<?>` 는 읽을 때는 `Object`(`unknown` 에 대응), 쓸 때는 `null` 외에 아무것도 받지 않는다(`never` 에 대응). 아래 코드는 OpenJDK 21 로 컴파일·실행했다.

```java
import java.util.ArrayList;
import java.util.List;

public class Lattice {
    static Object first(List<?> xs) {
        // xs.add("a");   // 컴파일 오류: String cannot be converted to CAP#1
        xs.add(null);     // null 만 넣을 수 있다
        return xs.get(0); // 읽은 값은 Object
    }

    public static void main(String[] args) {
        Object o = 1;     // 원시값은 Integer 로 박싱되어 들어간다
        String s = null;  // null 은 모든 참조 타입에 대입된다
        System.out.println(o.getClass().getSimpleName() + " " + s);   // Integer null
        System.out.println(first(new ArrayList<>(List.of("a"))));     // a
    }
}
```

차이도 있다. Java 는 명목적 타입이라 `Object` 로 받은 값은 캐스팅이나 `instanceof` 로만 내려가지만, TypeScript 는 구조적이라 프로퍼티 검사로도 좁혀진다. Java 에는 프로그래머가 이름으로 쓸 수 있는 바닥 타입도 없다(JLS 4.1 의 null 타입은 이름이 없다).

선택 기준은 이렇다. HTTP 응답·`JSON.parse` 결과 같은 경계 입력은 `unknown` 으로 받고 타입 가드나 스키마 검증으로 좁힌다. `any` 는 typescript-eslint 의 `no-explicit-any` 로, `{}` 는 `no-empty-object-type` 으로, `void` 자리에 넘긴 Promise 는 `no-misused-promises` 로 막는다. 린트 규칙은 문서의 존재만 확인했고 실행하지 않았다. 콜백 매개변수의 반환 타입은 `void` 로 열어 두고 구현 함수에는 실제 반환 타입을 적는다.

검증 환경: 모든 TypeScript 예제는 TypeScript 5.6.3 과 @types/node 26.6.6 에서 `npx tsc --strict --target es2022 --module nodenext --noEmit helpers.d.ts <파일>` 로 파일별로 컴파일했다. 7번 예제에는 `--strictNullChecks false` 를 추가했고, `catch` 변수의 `any` 동작은 `--useUnknownInCatchVariables false` 로 확인했다. 본문의 오류 코드는 `@ts-expect-error` 를 제거한 사본의 tsc 출력에서 옮겼고, 출력 주석은 Node v22.23.4 실행 결과다. 4.8 전후 비교는 4.7.4 와 4.8.4 를 따로 설치해 확인했다.

## 참고

- TypeScript Handbook, More on Functions (Return type void): https://www.typescriptlang.org/docs/handbook/2/functions.html
- TypeScript 4.4 릴리스 노트 (useUnknownInCatchVariables): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-4.html
- TypeScript 4.8 릴리스 노트 (`{}` 와 unknown 좁히기): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-8.html
- Java Language Specification SE 21, Chapter 4: https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html
- typescript-eslint no-explicit-any: https://typescript-eslint.io/rules/no-explicit-any/
- typescript-eslint no-empty-object-type: https://typescript-eslint.io/rules/no-empty-object-type/
