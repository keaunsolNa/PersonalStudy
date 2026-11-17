Notion 원본: https://www.notion.so/41b5a06fd6d381279c49d67275ff8587

# TypeScript readonly 의미론: 프로퍼티 할당 호환, ReadonlyArray·readonly 튜플, 가변 배열 공변성의 불건전성과 as const·Object.freeze

> 2026-11-17 신규 주제 · 확장 대상: TypeScript(객체·배열 타입 기초), JAVA(불변 컬렉션과 제네릭 와일드카드)

## 학습 목표

- `readonly` 프로퍼티가 할당 호환성 검사에 참여하지 않는다는 사실을 별칭 대입으로 재현한다
- `readonly T[]` 와 `T[]` 의 대입 방향, 가변 배열 공변성이 만드는 런타임 오류를 실행해 확인한다
- `Readonly<T>`·`as const`·`Object.freeze` 가 각각 어느 깊이까지, 컴파일 시점과 런타임 중 어디서 막는지 구분한다
- 함수 매개변수와 반환 타입에 `readonly` 를 배치하는 기준을 세운다

## 1. readonly 프로퍼티는 대입만 막는다

`readonly` 수식어는 그 프로퍼티 이름을 통한 대입을 컴파일 시점에 금지한다. 그 이상은 하지 않는다. 타입 호환성 검사는 `readonly` 여부를 비교하지 않으므로, 읽기 전용 프로퍼티를 가진 객체를 가변 타입 변수에 그대로 대입할 수 있고 그 별칭으로 값을 바꿀 수 있다.

이후 예제는 다음 헬퍼를 `helpers.d.ts` 로 함께 컴파일한다. `Expect<Eq<A, B>>` 는 두 타입이 다르면 TS2344 로 실패한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;
```

```ts
interface Account { readonly id: string; balance: number }
const acc: Account = { id: "A-1", balance: 100 };
acc.balance = 50;
function compileOnly() {
  // @ts-expect-error TS2540: 읽기 전용 프로퍼티에는 대입할 수 없다
  acc.id = "A-9";
}

interface MutableAccount { id: string; balance: number }
const alias: MutableAccount = acc;      // readonly 는 할당 호환성 검사에 참여하지 않는다
alias.id = "A-2";
console.log(acc.id);
// A-2

function rename(a: { id: string }) { a.id = a.id.toLowerCase(); }
rename(acc);                            // 오류 없음
console.log(acc.id);
// a-2

type Mutual = [
  { readonly id: string } extends { id: string } ? true : false,
  { id: string } extends { readonly id: string } ? true : false,
];
type M = Expect<Eq<Mutual, [true, true]>>;
// @ts-expect-error TS2344: 동일성 검사는 readonly 수식어 차이를 구분한다
type Same = Expect<Eq<{ readonly id: string }, { id: string }>>;
```

두 타입은 서로 대입 가능하지만 동일한 타입은 아니다. 프로퍼티 `readonly` 는 "이 참조로는 쓰지 않겠다" 는 약속이지 "이 객체는 변하지 않는다" 는 보장이 아니다. 이 설계는 2.0 에서 `readonly` 를 도입할 때 기존 코드와의 호환을 위해 선택된 것이고, 엄격한 검사를 추가하자는 제안은 TypeScript 저장소 이슈 13347 에서 논의되어 왔다.

## 2. ReadonlyArray 는 대입 방향이 한쪽으로만 열린다

배열은 다르다. `readonly T[]`(= `ReadonlyArray<T>`)는 `push`·`sort` 같은 변경 메서드가 없는 별도 인터페이스이고, 가변 배열로의 대입이 금지된다. 반대 방향은 허용된다.

```ts
const ro: readonly number[] = [1, 2, 3];
// @ts-expect-error TS2339: push 는 ReadonlyArray 에 없다
ro.push(4);
// @ts-expect-error TS2542: 인덱스 시그니처가 읽기 전용이다
ro[0] = 9;
// @ts-expect-error TS4104: readonly 배열은 가변 배열에 대입되지 않는다
const rw: number[] = ro;
const back: readonly number[] = [4, 5] as number[];   // 가변 -> 읽기 전용은 허용
const copy: number[] = [...ro];                        // 복사하면 가변으로 받을 수 있다
const sorted = ro.toSorted((a, b) => b - a);           // ES2023 비파괴 메서드는 사용 가능
type S = Expect<Eq<typeof sorted, number[]>>;
type A = [
  Expect<Eq<readonly number[], ReadonlyArray<number>>>,
  Expect<Eq<Readonly<number[]>, readonly number[]>>,
  Expect<Eq<Readonly<[a: number, b: string]>, readonly [a: number, b: string]>>,
];
```

프로퍼티 `readonly` 와 달리 여기서는 별칭을 통한 우회가 컴파일 단계에서 막힌다. `Readonly<T>` 는 배열과 튜플에 적용하면 프로퍼티별 매핑이 아니라 `readonly` 배열·튜플로 변환된다. `toSorted`·`toReversed`·`with` 는 `lib` 이 es2023 이상일 때 `ReadonlyArray` 에도 선언되어 있어, 읽기 전용 배열에서도 정렬·역순 결과를 바로 얻을 수 있다.

## 3. 가변 배열 공변성은 불건전하다

`Dog[]` 는 `Animal[]` 에 대입된다. 읽기만 한다면 안전하지만, `Animal[]` 로 받은 쪽이 `Cat` 을 넣으면 원래의 `Dog[]` 에 `Cat` 이 들어간다. 컴파일러는 이를 막지 않는다.

```ts
class Animal { name: string; constructor(name: string) { this.name = name; } }
class Dog extends Animal { bark() { return `${this.name}: woof`; } }
class Cat extends Animal { meow() { return `${this.name}: meow`; } }

function adopt(shelter: Animal[]) { shelter.push(new Cat("nabi")); }
const dogs: Dog[] = [new Dog("bau")];
adopt(dogs);                                   // 컴파일 통과: 가변 배열도 공변으로 취급된다
try {
  for (const d of dogs) console.log(d.bark());
} catch (e) {
  console.log((e as Error).constructor.name, (e as Error).message);
}
// bau: woof
// TypeError d.bark is not a function

function census(shelter: readonly Animal[]) { return shelter.map((a) => a.name); }
console.log(census(dogs));                     // 읽기만 하면 공변이 안전하다
// [ 'bau', 'nabi' ]
```

```ts
class Animal { name = ""; }
class Dog extends Animal { bark() {} }
interface Box<T> { get(): T; set(v: T): void }
interface BoxP<T> { get(): T; set: (v: T) => void }
declare const dm: Box<Dog>;
declare const dp: BoxP<Dog>;
const am: Box<Animal> = dm;                    // 메서드 표기는 매개변수 이변성 때문에 통과
// @ts-expect-error TS2322: 프로퍼티 표기는 strictFunctionTypes 로 반공변 검사된다
const ap: BoxP<Animal> = dp;
```

`strictFunctionTypes` 는 함수 타입 프로퍼티의 매개변수를 반공변으로 검사하지만 메서드 표기로 선언된 멤버는 예외로 둔다. `Array<T>` 의 `push(...items: T[])` 가 메서드 표기라서 `Dog[]` 가 `Animal[]` 에 대입되는 것이다. 매개변수를 `readonly Animal[]` 로 선언하면 함수 본문에서 `push` 자체가 불가능해지므로, 호출자가 넘긴 배열의 요소 타입이 깨질 경로가 사라진다. Java 는 배열 공변성을 런타임 `ArrayStoreException` 으로 막고 제네릭 컬렉션은 불변(`List<Dog>` 은 `List<Animal>` 이 아님)으로 둔 뒤 `List<? extends Animal>` 로 읽기 전용 공변을 표현한다. TypeScript 의 `readonly Animal[]` 이 그 자리에 해당한다.

## 4. readonly 튜플, as const, const 타입 매개변수

`as const` 는 리터럴 타입 유지와 함께 배열을 `readonly` 튜플로 만든다. 그래서 `as const` 값을 가변 배열 매개변수에 넘기면 TS2345 가 난다. 라이브러리 함수의 매개변수가 `T[]` 로 선언되어 있으면 호출자가 `as const` 를 포기하거나 복사해야 하므로, 읽기만 하는 매개변수는 `readonly T[]` 로 받는 것이 호출자에게 유리하다.

```ts
const pair = [1, "a"] as const;
type P = Expect<Eq<typeof pair, readonly [1, "a"]>>;
// @ts-expect-error TS4104: readonly 튜플은 가변 튜플에 대입되지 않는다
const mp: [number, string] = pair;

function sum(xs: readonly number[]) { return xs.reduce((a, b) => a + b, 0); }
function sumM(xs: number[]) { return xs.reduce((a, b) => a + b, 0); }
const lit = [1, 2, 3] as const;
sum(lit);
sum([1, 2, 3]);
// @ts-expect-error TS2345: as const 값은 가변 매개변수에 넘길 수 없다
sumM(lit);

function listOf<const T extends readonly unknown[]>(xs: T): T { return xs; }
const t1 = listOf([1, "a"]);
type T1 = Expect<Eq<typeof t1, readonly [1, "a"]>>;
function listOfM<const T extends unknown[]>(xs: T): T { return xs; }
const t2 = listOfM([1, "a"]);
type T2 = Expect<Eq<typeof t2, [1, "a"]>>;   // 가변 제약이면 가변 튜플로 추론된다
function plain<T extends readonly unknown[]>(xs: T): T { return xs; }
const t3 = plain([1, "a"]);
type T3 = Expect<Eq<typeof t3, (string | number)[]>>;
```

`const` 타입 매개변수(5.0)는 호출부에 `as const` 를 쓰지 않아도 리터럴 추론을 얻게 한다. 제약이 `readonly unknown[]` 이면 결과가 `readonly` 튜플, `unknown[]` 이면 가변 튜플이다. 가변 제약에서의 동작은 버전에 따라 다르다. `listOfM([1, "a"])` 의 추론 결과는 5.0.4 와 5.2.2 에서 `unknown[]`, 5.3.3 부터 `[1, "a"]` 였다. 5.9.3 과 7.0.2 모두 위 결과로 컴파일된다.

## 5. Readonly 는 얕다: 깊은 변환과 Mutable

`Readonly<T>` 는 한 단계만 `readonly` 를 붙이는 동형 매핑 타입이다. 중첩 객체와 배열은 그대로 가변이다. 깊은 변환은 재귀 조건부 타입으로 만들며, 함수와 `Map`·`Set` 을 별도 분기로 처리해야 한다.

```ts
interface Order { id: number; items: { sku: string; qty: number }[]; meta: Map<string, string> }
type RO = Readonly<Order>;
declare const o: RO;
// @ts-expect-error TS2540
o.id = 2;
o.items.push({ sku: "x", qty: 1 });          // 얕다: 중첩 배열은 그대로 가변
o.items[0].qty = 99;

type DeepReadonly<T> =
  T extends (...a: any[]) => unknown ? T :
  T extends ReadonlyMap<infer K, infer V> ? ReadonlyMap<DeepReadonly<K>, DeepReadonly<V>> :
  T extends ReadonlySet<infer U> ? ReadonlySet<DeepReadonly<U>> :
  T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> } : T;
declare const d: DeepReadonly<Order>;
// @ts-expect-error TS2339
d.items.push({ sku: "x", qty: 1 });
// @ts-expect-error TS2540
d.items[0].qty = 99;
// @ts-expect-error TS2339: ReadonlyMap 에는 set 이 없다
d.meta.set("k", "v");
type DI = Expect<Eq<DeepReadonly<Order>["items"], readonly { readonly sku: string; readonly qty: number }[]>>;

type Mutable<T> = { -readonly [K in keyof T]: T[K] };
type M1 = Expect<Eq<Mutable<{ readonly a: 1; b?: 2 }>, { a: 1; b?: 2 }>>;
type M2 = Expect<Eq<Mutable<readonly [1, 2]>, [1, 2]>>;
type M3 = Expect<Eq<Mutable<readonly string[]>, string[]>>;
```

`{ readonly [K in keyof T]: ... }` 가 배열 분기 없이도 `readonly` 배열을 만들어 내는 것은 동형 매핑 타입이 배열·튜플에 적용될 때 요소 타입만 변환하고 배열 구조를 유지하기 때문이다. `-readonly` 는 수식어를 제거하며 선택적 수식어 `?` 는 건드리지 않는다. `DeepReadonly` 는 타입 인스턴스화 비용이 크므로 도메인 모델 전체가 아니라 상태 저장소의 공개 타입처럼 경계에만 적용한다.

## 6. Object.freeze 와 런타임 보호

`readonly` 는 컴파일 결과에 아무 흔적도 남기지 않는다. 런타임 보호가 필요하면 `Object.freeze` 를 쓰는데, 이것도 한 단계만 얼린다.

```ts
const cfg = Object.freeze({ env: "prod", ports: [80, 443], nested: { retry: 3 } });
type C = Expect<Eq<typeof cfg, Readonly<{ env: "prod"; ports: number[]; nested: { retry: number } }>>>;
cfg.ports.push(8080);            // 타입도 런타임도 얕다
cfg.nested.retry = 0;
console.log(cfg.ports, cfg.nested, Object.isFrozen(cfg), Object.isFrozen(cfg.ports));
// [ 80, 443, 8080 ] { retry: 0 } true false

const fz = Object.freeze(["a", "b"]);
type F = Expect<Eq<typeof fz, readonly string[]>>;
try {
  (fz as string[]).push("c");
} catch (e) {
  console.log((e as Error).constructor.name);
}
// TypeError
const ro: readonly string[] = ["a"];
(ro as string[]).push("b");      // readonly 는 런타임 보호가 아니다
console.log(ro);
// [ 'a', 'b' ]
try {
  (cfg as { env: string }).env = "dev";
} catch (e) {
  console.log((e as Error).message);
}
// Cannot assign to read only property 'env' of object '#<Object>'
```

`Object.freeze` 의 반환 타입에서 `env` 가 `string` 이 아니라 `"prod"` 인 점이 눈에 띈다. lib 선언에 원시 값 프로퍼티의 리터럴 타입을 보존하는 오버로드가 있기 때문이다. 얼린 객체에 대한 대입은 엄격 모드(ES 모듈 포함)에서 `TypeError` 를 던지고 비엄격 모드에서는 조용히 무시된다. 설정 객체처럼 프로세스 수명 동안 바뀌면 안 되는 값은 `as const` 로 타입을 고정하고 재귀 `freeze` 로 런타임을 막는 조합을 쓴다.

## 7. 클래스에서의 readonly, getter, #private

클래스 필드의 `readonly` 는 선언부 초기화와 생성자 본문에서만 대입을 허용한다. setter 없는 getter 도 타입 관점에서는 읽기 전용 프로퍼티다.

```ts
class Money {
  readonly currency: string = "KRW";
  static readonly ZERO = new Money(0);
  #cents: number;
  constructor(readonly amount: number, currency?: string) {
    this.#cents = amount * 100;
    if (currency) this.currency = currency;    // 생성자 안에서는 대입할 수 있다
  }
  get cents() { return this.#cents; }
  // @ts-expect-error TS2540: 생성자 밖 메서드에서는 대입할 수 없다
  reset() { this.amount = 0; }
}
const m = new Money(10, "USD");
function compileOnly() {
  // @ts-expect-error TS2540: getter 만 있는 접근자도 읽기 전용이다
  m.cents = 1;
  // @ts-expect-error TS2540
  Money.ZERO = m;
}
(m as { amount: number }).amount = 5;          // 단언으로 우회 가능
console.log(m.amount, Object.getOwnPropertyDescriptor(m, "amount")?.writable);
// 5 true
type K = Expect<Eq<keyof Money, "currency" | "amount" | "cents" | "reset">>;
```

마지막 출력의 `writable: true` 가 핵심이다. `readonly amount` 는 평범한 쓰기 가능 데이터 프로퍼티로 방출되므로 타입 단언 한 번이면 값이 바뀐다. 외부에서 바꿀 수 없어야 하는 상태는 `#private` 필드에 두고 getter 로만 노출한다. 생성자 매개변수 프로퍼티(`constructor(readonly amount: number)`)는 타입 제거만으로는 실행할 수 없는 구문이어서 `erasableSyntaxOnly` 환경에서는 필드 선언과 대입으로 풀어 써야 한다.

## 8. API 경계에서의 배치 기준

읽기만 하는 매개변수는 `readonly` 로 받고, 새로 만든 값을 돌려줄 때는 가변 타입으로 반환한다. 호출자는 가변·읽기 전용 값을 모두 넘길 수 있고, 반환값을 자유롭게 가공할 수 있다. 반대로 내부 상태의 참조를 그대로 노출할 때는 읽기 전용 타입으로 반환해야 호출자가 내부 배열을 직접 수정하는 일을 컴파일 단계에서 막는다.

```ts
interface User { id: number; tags: string[] }
function normalize(users: readonly Readonly<User>[]): User[] {
  return users.map((u) => ({ ...u, tags: u.tags.map((t) => t.trim()) }));
}
function legacySort(xs: number[]) { return xs.sort(); }
const input: readonly number[] = [3, 1, 2];
legacySort([...input]);

const ids = new Set([1, 2]);
const view: ReadonlySet<number> = ids;
function compileOnly() {
  // @ts-expect-error TS2345
  legacySort(input);
  // @ts-expect-error TS2339
  view.add(3);
}
ids.add(3);
console.log(view.has(3));
// true
```

`ReadonlySet` 은 스냅샷이 아니라 읽기 전용 뷰다. 원본을 쥔 쪽이 바꾸면 뷰에서도 보인다. Java 의 `Collections.unmodifiableSet` 과 같은 성격이고, 변경이 전파되지 않아야 하면 `List.copyOf` 처럼 복사본(`new Set(ids)`)을 만들어야 한다. `readonly` 를 매개변수에 적용할 때의 비용은 전파다. 호출 체인 아래의 함수가 `T[]` 를 요구하면 TS4104·TS2345 가 연쇄적으로 나므로, 잎 함수부터 `readonly` 로 바꿔 올라오는 순서가 수정량이 적다.

검증 환경: 모든 예제는 TypeScript 5.9.3 과 7.0.2 에서 `tsc --strict --target es2023 --module nodenext --noEmit helpers.d.ts <파일>` 로 파일별 컴파일했다. 오류 코드는 `@ts-expect-error` 를 제거한 사본의 tsc 출력에서 옮겼고, 출력 주석은 Node v22.23.4 에서 `node --experimental-transform-types` 로 실행한 결과다. `@ts-expect-error` 는 검사만 끄고 문장은 그대로 실행되므로, 실행하는 예제에서는 오류 문장을 호출하지 않는 `compileOnly` 함수 안에 두었다.

## 참고

- TypeScript Handbook, Object Types (readonly Properties, ReadonlyArray): https://www.typescriptlang.org/docs/handbook/2/objects.html#readonly-properties
- TypeScript 3.4 릴리스 노트 (readonly 배열·튜플, const assertions): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-4.html
- TypeScript 5.0 릴리스 노트 (const Type Parameters): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript 5.3 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-3.html
- microsoft/TypeScript 이슈 13347 (readonly 수식어의 할당 호환성): https://github.com/microsoft/TypeScript/issues/13347
- MDN, Object.freeze: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Object/freeze
