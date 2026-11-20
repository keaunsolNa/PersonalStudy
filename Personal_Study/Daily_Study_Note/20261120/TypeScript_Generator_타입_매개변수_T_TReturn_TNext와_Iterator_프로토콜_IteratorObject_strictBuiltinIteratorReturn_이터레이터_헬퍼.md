Notion 원본: https://www.notion.so/41e5a06fd6d381dea670dd165c11ac14

# TypeScript Generator 타입 매개변수 T·TReturn·TNext 와 Iterator 프로토콜: IteratorObject, strictBuiltinIteratorReturn, 이터레이터 헬퍼

> 2026-11-20 신규 주제 · 확장 대상: Javascript(이터러블·제너레이터 문법), TypeScript(제네릭·판별 유니온)

## 학습 목표

- `Generator<T, TReturn, TNext>` 세 매개변수가 `yield`·`return`·`next(arg)` 중 어디에서 추론되는지 컴파일 결과로 확인한다
- `Iterable`·`Iterator`·`IterableIterator`·`IteratorObject` 의 포함 관계를 구분하고 직접 구현한 이터레이터를 `for...of` 에 연결한다
- `strictBuiltinIteratorReturn` 이 `Map.keys().next()` 의 `value` 타입을 어떻게 바꾸는지 옵션을 켜고 끄며 비교한다
- ES2025 이터레이터 헬퍼(`map`·`filter`·`take`)의 지연 평가를 호출 횟수로 측정한다

## 1. 제너레이터 함수의 반환 타입은 세 가지를 따로 기록한다

제너레이터 객체와 주고받는 값은 세 종류다. `yield` 로 내보내는 값, `return` 으로 끝내며 내보내는 값, 호출자가 `next(arg)` 로 넣어 주는 값이다. TypeScript 3.6 부터 이 셋은 `Generator<T, TReturn, TNext>` 의 매개변수로 분리되어 추론된다.

이후 예제는 다음 헬퍼를 `helpers.d.ts` 로 함께 컴파일한다. `Expect<Eq<A, B>>` 는 두 타입이 다르면 TS2344 로 실패한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;
```

```ts
function* countdown(from: number) {
  for (let i = from; i > 0; i--) yield i;
  return "liftoff" as const;
}
const g = countdown(2);
type G = Expect<Eq<typeof g, Generator<number, "liftoff", unknown>>>;
const r1 = g.next();
type R1 = Expect<Eq<typeof r1, IteratorResult<number, "liftoff">>>;
if (r1.done) {
  type D = Expect<Eq<typeof r1.value, "liftoff">>;
} else {
  type V = Expect<Eq<typeof r1.value, number>>;
}
console.log(r1, g.next(), g.next(), g.next());
// { value: 2, done: false } { value: 1, done: false } { value: 'liftoff', done: true } { value: undefined, done: true }
console.log([...countdown(3)]);
// [ 3, 2, 1 ]
for (const v of countdown(2)) console.log(v);
// 2
// 1
export {};
```

`IteratorResult<T, TReturn>` 는 `{ done?: false; value: T } | { done: true; value: TReturn }` 의 판별 유니온이다. `done` 을 검사해야 `value` 가 좁혀지고, 검사하지 않으면 `number | "liftoff"` 로 남는다. 출력에서 확인할 점이 둘 있다. 첫째, `return` 값은 `done: true` 인 결과에 한 번만 실려 나오고 그다음 `next()` 부터는 `undefined` 다. 타입은 네 번째 호출에도 `"liftoff"` 라고 말하지만 런타임 값은 `undefined` 이므로, 종료된 제너레이터의 `next()` 결과를 타입만 믿고 사용하면 안 된다. 둘째, 전개 구문과 `for...of` 는 `done: true` 인 결과의 `value` 를 버린다. `TReturn` 은 `next()` 를 직접 호출하거나 `yield*` 로 위임받는 쪽에서만 관측된다.

## 2. TNext: next 로 넣는 값의 타입

`const x = yield expr` 에서 `x` 의 타입이 `TNext` 다. 반환 타입을 명시하면 `yield` 표현식의 타입과 `next` 의 인수 타입이 함께 고정된다.

```ts
function* accumulator(): Generator<number, string, number> {
  let total = 0;
  while (total < 10) {
    const delta = yield total;
    type Dl = Expect<Eq<typeof delta, number>>;
    total += delta;
  }
  return `done:${total}`;
}
const acc = accumulator();
console.log(acc.next());
// { value: 0, done: false }
console.log(acc.next(4));
// { value: 4, done: false }
console.log(acc.next(7));
// { value: 'done:11', done: true }
function compileOnly() {
  // @ts-expect-error TS2345: '["4"]' 는 '[] | [number]' 에 대입되지 않는다
  acc.next("4");
}
function* first(): Generator<number, void, string> {
  const a = yield 1;
  console.log("received", a);
}
const f = first();
f.next();
f.next("x");
// received x
function* inferred() {
  const x: string = yield 1;
  const y: number = yield 2;
  return x.length + y;
}
const inf = inferred();
type TN = typeof inf extends Generator<any, any, infer N> ? N : never;
type TNc = Expect<Eq<TN, string & number>>;
export {};
```

`next` 의 시그니처는 `next(...[value]: [] | [TNext])` 다. 인수를 생략할 수 있게 열어 둔 이유는 첫 호출 때문이다. 첫 번째 `next()` 는 함수 본문을 첫 `yield` 까지 실행시킬 뿐이고, 그 시점에는 값을 받을 `yield` 표현식이 없어 인수가 버려진다. 그 결과 타입은 `TNext` 가 `number` 라고 말해도 인수 없이 호출된 `next()` 에 대해서는 `yield` 가 `undefined` 로 평가된다. 위 `accumulator` 에서 두 번째 호출을 `acc.next()` 로 바꾸면 컴파일은 통과하고 `total` 은 `NaN` 이 된다. 값을 주입받는 제너레이터는 `TNext` 에 `undefined` 를 포함시키고 본문에서 검사하는 쪽이 타입과 런타임이 일치한다.

반환 타입을 생략하면 `TNext` 는 본문의 모든 `yield` 표현식이 요구하는 타입의 교집합으로 추론된다. `inferred` 는 `string` 과 `number` 를 각각 요구하므로 `string & number`, 즉 `never` 와 같아져 어떤 값으로도 `next(arg)` 를 호출할 수 없다. `yield` 마다 다른 타입을 받아야 하는 프로토콜은 제너레이터 타입으로 표현되지 않는다. redux-saga 가 `yield` 결과에 타입 단언을 요구하는 이유가 이것이다.

## 3. yield* 는 TReturn 을 꺼내는 유일한 문법이다

`yield* inner()` 는 내부 이터러블의 `yield` 값을 그대로 바깥으로 흘려보내고, 내부가 끝나면서 반환한 값을 표현식의 값으로 돌려준다.

```ts
function* inner() {
  yield "a";
  yield "b";
  return 42;
}
function* outer() {
  const result = yield* inner();
  type R = Expect<Eq<typeof result, number>>;
  yield result;
  const fromArr = yield* [true, false];
  type A = Expect<Eq<typeof fromArr, undefined>>;
}
const o = outer();
type O = Expect<Eq<typeof o, Generator<number | "a" | "b" | boolean, void, unknown>>>;
console.log([...outer()]);
// [ 'a', 'b', 42, true, false ]

function* guarded() {
  try {
    yield 1;
    yield 2;
  } finally {
    console.log("cleanup");
  }
}
for (const v of guarded()) { console.log(v); break; }
// 1
// cleanup
const g2 = guarded();
g2.next();
console.log(g2.return(), g2.next());
// cleanup
// { value: undefined, done: true } { value: undefined, done: true }
const g3 = guarded();
console.log(g3.return());
// { value: undefined, done: true }
const g4 = guarded();
g4.next();
try { g4.throw(new Error("boom")); } catch (e) { console.log("caught", (e as Error).message); }
// cleanup
// caught boom
function compileOnly() {
  // @ts-expect-error TS2345: TReturn 이 void 로 추론되어 return 의 인수도 void 여야 한다
  g2.return(99);
}
export {};
```

배열에 대한 `yield*` 의 결과가 `undefined` 타입인 것은 5.6 에서 내장 이터레이터의 `TReturn` 이 `undefined` 로 고정되었기 때문이다(5절).

`for...of` 를 `break` 로 빠져나오면 런타임이 이터레이터의 `return()` 을 호출하고, 제너레이터는 멈춰 있던 `yield` 지점에서 `finally` 블록을 실행한다. 파일 핸들이나 DB 커서를 제너레이터로 감쌀 때 정리가 보장되는 근거다. 단 `g3` 처럼 한 번도 `next()` 를 호출하지 않은 제너레이터는 본문이 시작되지 않았으므로 `return()` 을 호출해도 `finally` 가 실행되지 않는다. 자원 획득을 `try` 안, 첫 `yield` 이전에 두면 "획득했으면 반드시 해제된다" 가 성립한다. `return(value)` 의 인수 타입은 `TReturn` 이어서, `return` 문이 없는 제너레이터에 값을 넘기면 TS2345 가 난다.

## 4. Iterable, Iterator, IterableIterator 의 관계

`Iterable<T>` 는 `[Symbol.iterator]()` 를 가진 객체, `Iterator<T>` 는 `next()` 를 가진 객체다. 둘은 별개의 인터페이스이고, `for...of`·전개·구조 분해는 `Iterable` 만 받는다. `IterableIterator<T>` 는 둘을 모두 만족하면서 `[Symbol.iterator]()` 가 자기 자신을 돌려주는 형태다.

```ts
class Range implements Iterable<number> {
  constructor(private readonly start: number, private readonly end: number) {}
  *[Symbol.iterator](): Generator<number, void, undefined> {
    for (let i = this.start; i < this.end; i++) yield i;
  }
}
const r = new Range(0, 3);
console.log([...r], [...r], Math.max(...r));
// [ 0, 1, 2 ] [ 0, 1, 2 ] 2
const it = r[Symbol.iterator]();
console.log([...it], [...it]);
// [ 0, 1, 2 ] []

class Manual implements Iterator<number, string, undefined> {
  private i = 0;
  next(): IteratorResult<number, string> {
    return this.i < 2 ? { done: false, value: this.i++ } : { done: true, value: "end" };
  }
}
const m = new Manual();
function compileOnly() {
  // @ts-expect-error TS2488: 'Manual' 에는 이터레이터를 반환하는 '[Symbol.iterator]()' 가 없다
  for (const x of m) {}
}
const wrapped = Iterator.from(m);
console.log([...wrapped]);
// [ 0, 1 ]
type X = [
  Expect<Eq<Generator<number> extends IterableIterator<number> ? true : false, true>>,
  Expect<Eq<IterableIterator<number> extends Iterable<number> ? true : false, true>>,
  Expect<Eq<Iterable<number> extends Iterator<number> ? true : false, false>>,
];
export {};
```

`Range` 는 순회할 때마다 새 제너레이터를 만들기 때문에 몇 번이든 다시 순회된다. 반면 `it` 은 이터레이터 자체여서 한 번 소진되면 빈 결과만 낸다. 함수 매개변수를 `Iterable<T>` 로 받으면 호출자가 배열을 넘길지 제너레이터 객체를 넘길지 알 수 없으므로, 본문에서 두 번 순회하는 코드는 제너레이터가 들어왔을 때 두 번째 순회가 조용히 비어 버린다. 두 번 이상 읽어야 하면 `readonly T[]` 로 받거나 진입 시점에 `Array.from` 으로 고정한다.

`next()` 만 구현한 `Manual` 은 `Iterator` 이지만 `Iterable` 이 아니어서 `for...of` 에 쓸 수 없다. ES2025 의 `Iterator.from` 은 이런 객체를 `Iterator.prototype` 을 상속하는 래퍼로 감싸 순회 가능하게 만든다.

## 5. 내장 이터레이터와 strictBuiltinIteratorReturn

5.6 이전에는 `Map.prototype.keys()` 의 반환 타입이 `IterableIterator<K>` 였고 `TReturn` 은 `any` 였다. `done` 을 검사하지 않고 `next().value` 를 꺼내면 `K | any`, 곧 `any` 가 되어 `undefined` 가능성이 타입에서 사라졌다. 5.6 은 내장 이터레이터에 전용 타입(`ArrayIterator`·`MapIterator`·`SetIterator` 등)을 부여하고 `TReturn` 자리에 `BuiltinIteratorReturn` 이라는 내재 타입을 넣었다. 이 타입은 `strictBuiltinIteratorReturn`(`strict` 에 포함)이 켜져 있으면 `undefined`, 꺼져 있으면 `any` 다.

```ts
const map = new Map([["a", 1], ["b", 2]]);
const keys = map.keys();
type K = Expect<Eq<typeof keys, MapIterator<string>>>;
const first = keys.next();
type F = Expect<Eq<typeof first, IteratorResult<string, undefined>>>;
if (first.done) {
  type V = Expect<Eq<typeof first.value, undefined>>;
}
const arrIt = [1, 2][Symbol.iterator]();
type AI = Expect<Eq<typeof arrIt, ArrayIterator<number>>>;
type B = Expect<Eq<BuiltinIteratorReturn, undefined>>;
```

같은 상황에서 `const s: string = map.keys().next().value` 를 컴파일하면 `--strict` 에서는 `TS2322: Type 'string | undefined' is not assignable to type 'string'` 이 나고, `--strict --strictBuiltinIteratorReturn false` 에서는 오류 없이 통과한다. "맵의 첫 번째 키" 를 꺼내는 흔한 코드가 빈 맵에서 `undefined` 를 문자열로 취급하던 문제를 컴파일 단계로 끌어올린 변경이다. 5.6 이상으로 올린 뒤 이 오류가 다수 발생하면 옵션을 끄기보다 `done` 검사나 `??` 기본값을 넣는 쪽이 원래 있던 결함을 고치는 길이다.

## 6. 이터레이터 헬퍼와 IteratorObject

ES2025 는 `Iterator.prototype` 에 `map`·`filter`·`take`·`drop`·`flatMap`·`reduce`·`toArray`·`forEach`·`some`·`every`·`find` 를 추가했다. 제너레이터 객체와 내장 이터레이터는 모두 `Iterator.prototype` 을 상속하므로 배열로 바꾸지 않고 체이닝할 수 있다. TypeScript 는 이 프로토타입을 `IteratorObject<T, TReturn, TNext>` 인터페이스로 모델링하고 `Generator` 와 내장 이터레이터 타입이 이를 확장하게 했다. 구조적 프로토콜인 `Iterator<T>` 에는 헬퍼가 없다. `next()` 만 가진 임의의 객체도 `Iterator<T>` 를 만족하기 때문이다.

```ts
const evens = map.values().filter((v) => v % 2 === 0).map((v) => v * 10);
type E2 = Expect<Eq<typeof evens, IteratorObject<number, undefined, unknown>>>;
console.log(evens.toArray());
// [ 20 ]

function* naturals() { let n = 1; while (true) yield n++; }
console.log(naturals().filter((n) => n % 3 === 0).map((n) => n * n).take(4).toArray());
// [ 9, 36, 81, 144 ]
console.log(naturals().drop(2).take(3).reduce((a, b) => a + b, 0));
// 12
let pulled = 0;
function* traced() { for (let i = 1; ; i++) { pulled++; yield i; } }
const firstBig = traced().map((n) => n * 2).find((n) => n > 6);
console.log(firstBig, pulled);
// 8 4
console.log(Object.getPrototypeOf(Object.getPrototypeOf(naturals().take(1))) === Iterator.prototype, naturals() instanceof Iterator);
// true true
export {};
```

`traced` 는 무한 제너레이터인데 `find` 가 조건을 만족하는 값을 만나자 네 번만 당겨 오고 끝났다. 배열 메서드 체인은 단계마다 전체 배열을 만들지만 헬퍼는 소비자가 요청한 만큼만 원소를 한 개씩 통과시킨다. 중간 배열이 없으므로 메모리 사용량은 입력 크기와 무관하다. 반대로 원소당 `next()` 호출과 결과 객체 생성 비용이 붙으므로, 이미 메모리에 있는 작은 배열을 끝까지 처리할 때는 배열 메서드가 유리하다. 헬퍼가 맞는 자리는 무한·대용량 시퀀스, 조기 종료가 있는 탐색, `Map`·`Set` 의 뷰를 배열 복사 없이 가공하는 경우다.

헬퍼의 타입 선언은 `lib.es2025.iterator.d.ts` 에 있다. `naturals().take(1)` 을 `--target es2022` 와 `es2024` 로 컴파일하면 `TS2339: Property 'take' does not exist on type 'Generator<number, void, unknown>'` 이 나고 `es2025` 와 `esnext` 에서는 통과한다. `target` 을 낮게 유지해야 하면 `lib` 에 `esnext.iterator` 또는 `es2025.iterator` 를 추가한다. `lib` 은 타입만 추가하므로 실행 환경이 헬퍼를 실제로 구현하는지는 별도로 확인해야 한다.

## 7. 제네릭 유틸리티 작성 기준

이터러블을 받아 이터러블을 돌려주는 함수는 입력을 `Iterable<T>` 로, 출력을 구체적인 `Generator<...>` 로 선언한다. 입력을 넓게 받으면 배열·`Set`·제너레이터를 모두 받을 수 있고, 출력을 `Generator` 로 밝히면 호출자가 헬퍼와 `return()` 을 쓸 수 있다.

```ts
function firstOr<T>(src: Iterable<T>, fallback: T): T {
  const it = src[Symbol.iterator]();
  const r = it.next();
  type R = Expect<Eq<typeof r, IteratorResult<T, any>>>;
  return r.done ? fallback : r.value;
}
function* chunk<T>(src: Iterable<T>, size: number): Generator<T[], void, undefined> {
  let buf: T[] = [];
  for (const x of src) {
    buf.push(x);
    if (buf.length === size) { yield buf; buf = []; }
  }
  if (buf.length) yield buf;
}
console.log([...chunk([1, 2, 3, 4, 5], 2)], firstOr(new Set<string>(), "none"));
// [ [ 1, 2 ], [ 3, 4 ], [ 5 ] ] none
function* idGen(): Generator<number, never, undefined> { let i = 0; while (true) yield i++; }
const ids = idGen();
const n = ids.next();
type N = Expect<Eq<typeof n, IteratorResult<number, never>>>;
const v: number = n.value;
type Yields<G> = G extends Generator<infer Y, any, any> ? Y : never;
type Returns<G> = G extends Generator<any, infer R, any> ? R : never;
type T1 = Expect<Eq<Yields<ReturnType<typeof chunk<string>>>, string[]>>;
type T2 = Expect<Eq<Returns<ReturnType<typeof idGen>>, never>>;
console.log(v, ids.next().value);
// 0 1
export {};
```

`Iterable<T>` 의 기본 `TReturn` 은 `any` 다. 임의의 이터러블이 끝날 때 무엇을 반환할지 알 수 없기 때문이며, 그래서 `firstOr` 는 `done` 을 검사해 `value` 를 `T` 로 좁힌 뒤에만 반환한다. 끝나지 않는 제너레이터는 `TReturn` 을 `never` 로 선언한다. `IteratorResult<number, never>` 는 `done: true` 분기의 `value` 가 `never` 이므로 `n.value` 가 검사 없이 `number` 로 읽힌다. ID 발급기나 폴링 루프처럼 종료가 없는 시퀀스에서 불필요한 `done` 검사를 타입 수준에서 없애는 방법이다. `TNext` 를 `undefined` 로 두면 `next(1)` 같은 잘못된 주입이 컴파일 오류가 된다.

## 8. 적용 판단

제너레이터의 장점은 생산자 코드가 평범한 반복문 모양을 유지한 채 지연 평가와 조기 종료, `finally` 정리를 얻는다는 점이다. 페이지네이션된 API 순회, 큰 파일의 행 단위 처리, 트리 순회처럼 "다음 원소를 만드는 데 상태가 필요한" 시퀀스에 맞는다. 약점은 타입 표현력이다. `TNext` 는 하나뿐이어서 `yield` 지점마다 다른 응답 타입을 갖는 프로토콜은 표현할 수 없고, `TReturn` 은 `for...of` 로는 관측되지 않아 일반 소비자에게 전달되지 않는다. 양방향 프로토콜이 필요하면 제너레이터보다 명시적인 상태 머신이나 `async` 함수와 콜백의 조합이 타입 검사를 더 받는다. 일회성이라는 성질도 API 경계에서 드러내야 한다. 다시 순회될 수 있는 값은 `[Symbol.iterator]()` 를 구현한 객체로, 한 번만 흐르는 값은 `Generator` 나 `IteratorObject` 로 반환 타입을 구분해 두면 호출자가 재사용 가능 여부를 타입에서 읽을 수 있다.

검증 환경: 모든 예제는 TypeScript 5.9.3 과 7.0.2 에서 `tsc --strict --target esnext --module nodenext --noEmit helpers.d.ts <파일>` 로 파일별 컴파일했다. 오류 코드는 `@ts-expect-error` 를 제거한 사본의 tsc 7.0.2 출력에서 옮겼고, 출력 주석은 Node v22.23.5 에서 `node --experimental-transform-types` 로 실행한 결과다. 5절과 6절의 코드 블록은 하나의 파일이다. `strictBuiltinIteratorReturn false` 와 `target` 별 비교는 7.0.2 로 따로 컴파일했다.

## 참고

- TypeScript 3.6 릴리스 노트 (Stricter Generators): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-6.html
- TypeScript 5.6 릴리스 노트 (Iterator Helper Methods, Strict Builtin Iterator Checks): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-6.html
- TypeScript Handbook, Iterators and Generators: https://www.typescriptlang.org/docs/handbook/iterators-and-generators.html
- TSConfig Reference, strictBuiltinIteratorReturn: https://www.typescriptlang.org/tsconfig/#strictBuiltinIteratorReturn
- TC39 Iterator Helpers 제안: https://github.com/tc39/proposal-iterator-helpers
- MDN, Iteration protocols: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Iteration_protocols
- MDN, Iterator: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Iterator
