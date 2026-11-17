Notion 원본: https://www.notion.so/41b5a06fd6d381b3a1e9c83a2dbb5664

# TypeScript 인덱스 시그니처와 keyof: Record 와의 차이, 템플릿 패턴 키, noUncheckedIndexedAccess·noPropertyAccessFromIndexSignature

> 2026-11-17 신규 주제 · 확장 대상: TypeScript(객체 타입 기초), Javascript(객체 프로퍼티 키와 프로토타입)

## 학습 목표

- 인덱스 시그니처가 선언된 프로퍼티에 거는 제약과 `keyof` 결과를 컴파일러로 확인한다
- `Record<string, V>`·매핑 타입·인터페이스가 인덱스 시그니처에 대입되는 조건을 구분한다
- `noUncheckedIndexedAccess`·`noPropertyAccessFromIndexSignature` 를 켰을 때 달라지는 읽기·쓰기 타입을 재현한다
- 사전(dictionary) 용도의 객체와 `Map` 중 무엇을 쓸지 런타임 동작을 근거로 고른다

## 1. 인덱스 시그니처가 프로퍼티에 거는 제약

인덱스 시그니처 `[k: string]: V` 는 "어떤 문자열 키로 읽어도 값은 V" 라는 선언이다. 그래서 같은 타입에 이름을 적어 선언한 프로퍼티도 모두 V 에 대입 가능해야 한다. 키 타입으로는 `string`·`number`·`symbol`·템플릿 리터럴 패턴만 허용되고, 리터럴 유니온은 매핑 타입으로 써야 한다.

이후 예제는 타입이 기대와 같은지를 컴파일러가 판정하도록 다음 헬퍼를 `helpers.d.ts` 로 함께 컴파일한다. `Expect<Eq<A, B>>` 는 두 타입이 다르면 TS2344 로 실패한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;
```

```ts
interface Headers1 {
  [name: string]: string;
  host: string;
  // @ts-expect-error TS2411: number 는 인덱스 시그니처의 값 타입 string 에 대입되지 않는다
  contentLength: number;
}
interface Headers2 {
  [name: string]: string | number;
  contentLength: number;          // 값 타입을 넓히면 통과한다
}
type Bad = {
  // @ts-expect-error TS1337: 리터럴 유니온은 인덱스 시그니처의 키 타입이 될 수 없다
  [k: "a" | "b"]: number;
};
type Good = { [K in "a" | "b"]: number };   // 매핑 타입은 프로퍼티 두 개를 만든다
type G = Expect<Eq<Good, { a: number; b: number }>>;
```

`Headers2` 처럼 값 타입을 유니온으로 넓히면 선언은 통과하지만, 임의 키로 읽은 값이 `string | number` 가 되어 사용처마다 좁히기가 필요해진다. 알려진 키와 임의 키의 값 타입이 다르면 임의 키 부분을 별도 프로퍼티(`extra: Record<string, string>`)로 분리하는 쪽이 사용하기 쉽다.

## 2. keyof 가 돌려주는 타입

`string` 인덱스 시그니처를 가진 타입의 `keyof` 는 `string` 이 아니라 `string | number` 다. 자바스크립트에서 `obj[1]` 은 `obj["1"]` 과 같은 프로퍼티를 가리키므로, 숫자로 접근하는 코드도 타입이 허용해야 하기 때문이다. `number` 인덱스 시그니처만 있으면 `keyof` 는 `number` 다.

```ts
type Dict = { [k: string]: boolean };
type List = { [i: number]: boolean };
type K = [
  Expect<Eq<keyof Dict, string | number>>,
  Expect<Eq<keyof List, number>>,
  Expect<Eq<keyof Record<string, boolean>, string>>,
  Expect<Eq<keyof boolean[], number | keyof []>>,
];
const d: Dict = {};
d[1] = true;                      // 숫자 키는 문자열 "1" 로 저장된다
console.log(Object.keys(d), d["1"]);
// [ '1' ] true
```

세 번째 줄이 눈에 띈다. `Record<string, boolean>` 은 `Dict` 와 구조가 같은데도 `keyof` 가 `string` 으로 나온다. `Record<K, V>` 는 `{ [P in K]: V }` 라는 매핑 타입이고, 매핑 타입에 `keyof` 를 적용하면 컴파일러가 제약 타입 K 를 그대로 돌려주기 때문이다. 제네릭 코드에서 `keyof T` 를 `string` 으로 가정하면 깨지므로, 문자열 키만 필요하면 `Extract<keyof T, string>` 또는 `keyof T & string` 으로 받는다.

## 3. number 인덱스와 string 인덱스의 공존 규칙

두 인덱스 시그니처를 함께 선언할 수 있지만, `number` 쪽 값 타입이 `string` 쪽 값 타입에 대입 가능해야 한다. 숫자 키도 결국 문자열 키이므로, 숫자로 읽은 값은 문자열 인덱스의 약속도 지켜야 한다.

```ts
class Animal { name = ""; }
class Dog extends Animal { bark() {} }
interface Ok {
  [i: number]: Dog;
  [k: string]: Animal;
}
interface Ng {
  // @ts-expect-error TS2413: number 인덱스 타입 Animal 이 string 인덱스 타입 Dog 에 대입되지 않는다
  [i: number]: Animal;
  [k: string]: Dog;
}
declare const ok: Ok;
type R = [
  Expect<Eq<(typeof ok)[0], Dog>>,
  Expect<Eq<(typeof ok)["0"], Dog>>,     // 숫자 모양의 문자열 리터럴 키는 number 인덱스로 해석된다
  Expect<Eq<(typeof ok)["x"], Animal>>,
];
```

두 번째 줄은 직접 확인하기 전까지 `Animal` 로 예상했던 부분이다. 컴파일러는 `"0"` 처럼 숫자를 정규 표기한 문자열 리터럴을 숫자 키로 취급한다. 배열 타입에서 `arr["0"]` 이 요소 타입으로 나오는 것도 같은 규칙이다.

## 4. 템플릿 리터럴 패턴 키와 symbol 키

TypeScript 4.4 부터 인덱스 시그니처의 키 타입으로 `symbol`, 템플릿 리터럴 패턴, 그리고 이들의 유니온을 쓸 수 있다. `data-*` 속성이나 `x-` 접두 헤더처럼 "모양은 정해져 있고 이름은 열려 있는" 키를 표현할 때 쓴다.

```ts
interface DataAttrs {
  [k: `data-${string}`]: string;
  [s: symbol]: unknown;
  id: number;
}
const el: DataAttrs = { id: 1, "data-role": "tab", [Symbol.iterator]: null };
// @ts-expect-error TS2353: 패턴에 맞지 않는 키는 잉여 프로퍼티다
const bad: DataAttrs = { id: 1, role: "tab" };
// @ts-expect-error TS2322: 패턴에 맞는 키의 값은 string 이어야 한다
const bad2: DataAttrs = { id: 1, "data-n": 1 };
type K = Expect<Eq<keyof DataAttrs, `data-${string}` | symbol | "id">>;
type U = { [k: string | symbol]: number };   // 키 타입의 유니온도 허용
type KU = Expect<Eq<keyof U, string | number | symbol>>;
```

패턴 키는 `string` 인덱스와 달리 선언된 프로퍼티 `id: number` 에 제약을 걸지 않는다. `"id"` 가 `data-${string}` 패턴에 속하지 않기 때문이다. 알려진 키와 임의 키의 값 타입이 다를 때 1절의 유니온 확장 대신 쓸 수 있는 방법이다.

## 5. Record, 타입 별칭, 인터페이스의 대입 차이

`Record<string, V>` 는 `{ [x: string]: V }` 와 같은 타입으로 평가되고, 키가 리터럴 유니온이면 필수 프로퍼티 집합이 된다. 차이는 대입할 때 드러난다. 객체 리터럴 타입(타입 별칭)에는 암묵적 인덱스 시그니처가 인정되지만 인터페이스에는 인정되지 않는다.

```ts
type R1 = Expect<Eq<Record<string, number>, { [x: string]: number }>>;
type R2 = Expect<Eq<Record<"a" | "b", number>, { a: number; b: number }>>;
type R3 = Expect<Eq<Record<`on${string}`, number>, { [x: `on${string}`]: number }>>;

type PointT = { x: number; y: number };
interface PointI { x: number; y: number }
declare const pt: PointT;
declare const pi: PointI;
const d1: Record<string, number> = pt;    // 타입 별칭은 암묵적 인덱스 시그니처를 가진다
// @ts-expect-error TS2322: 인터페이스에는 암묵적 인덱스 시그니처가 없다
const d2: Record<string, number> = pi;
const d3: Record<string, number> = { ...pi };   // 전개하면 객체 리터럴 타입이 된다
const d4: { [k: string]: unknown } = pt;
// @ts-expect-error TS2322
const d5: { [k: string]: unknown } = pi;
const d6: Record<string, any> = pi;       // 값 타입이 any 이면 예외로 허용된다
const d7: object = pi;
```

인터페이스가 거부되는 이유는 선언 병합이다. 인터페이스는 다른 파일에서 프로퍼티가 추가될 수 있으므로, 지금 보이는 프로퍼티가 모두 `number` 라는 사실만으로 "모든 키의 값이 number" 를 보장할 수 없다. 오류 메시지는 `Index signature for type 'string' is missing in type 'PointI'` 다. "아무 객체나 받는다" 는 뜻의 매개변수라면 `Record<string, unknown>` 대신 `object` 를 쓰거나, 호출부에서 전개해 넘긴다.

## 6. noUncheckedIndexedAccess

기본 설정에서 인덱스 시그니처로 읽은 값은 V 로 추론된다. 없는 키를 읽으면 런타임에는 `undefined` 인데 타입은 그렇지 않다. `noUncheckedIndexedAccess` 는 읽기 결과에 `undefined` 를 더한다. `strict` 에 포함되지 않으므로 따로 켜야 한다.

```ts
// tsc --noUncheckedIndexedAccess
const env: Record<string, string> = { HOME: "/root" };
const home = env.HOME;
const arr = [1, 2, 3];
const first = arr[0];
const tup: [number, number] = [1, 2];
const t0 = tup[0];
type R = [
  Expect<Eq<typeof home, string | undefined>>,
  Expect<Eq<typeof first, number | undefined>>,
  Expect<Eq<typeof t0, number>>,              // 튜플의 알려진 위치는 영향을 받지 않는다
];
for (let i = 0; i < arr.length; i++) {
  const v = arr[i];                           // 길이 검사는 좁히기에 반영되지 않는다
  type V = Expect<Eq<typeof v, number | undefined>>;
}
for (const v of arr) {
  type V = Expect<Eq<typeof v, number>>;      // 이터레이션은 영향을 받지 않는다
}
const [h, ...rest] = arr;
type D = [Expect<Eq<typeof h, number | undefined>>, Expect<Eq<typeof rest, number[]>>];
if (env.HOME !== undefined) {
  const s: string = env.HOME;                 // 같은 리터럴 키의 재접근은 좁혀진다
}
const key: string = "HOME";
if (env[key] !== undefined) {
  const s: string = env[key];                 // const 변수 키도 좁혀진다
}
if ("HOME" in env) {
  const s: string = env.HOME;                 // 리터럴 키의 in 검사도 좁힌다
}
declare const dyn: string;
if (dyn in env) {
  // @ts-expect-error TS2322: 리터럴이 아닌 키의 in 검사는 좁히지 않는다
  const s: string = env[dyn];
}
env.NEW = "x";                                // 쓰기에는 undefined 가 붙지 않는다
// @ts-expect-error TS2322
env.NEW2 = undefined;
```

읽기에만 `undefined` 가 붙고 쓰기 타입은 그대로다. 값에 `undefined` 를 저장할 수 있게 하려면 `Record<string, string | undefined>` 로 선언해야 하고, 이 둘은 다른 타입이다. 인덱스 기반 `for` 루프에서 매번 `undefined` 검사가 필요해지는 것이 가장 큰 비용이므로, 이 옵션을 켠 코드베이스는 `for...of`·`entries()`·`at()` 과 구조 분해를 쓰는 쪽으로 정리된다.

## 7. noPropertyAccessFromIndexSignature

인덱스 시그니처가 있으면 어떤 이름의 점 접근도 통과한다. 알려진 프로퍼티의 오타가 인덱스 시그니처의 값 타입으로 조용히 추론되는 문제가 여기서 나온다. `noPropertyAccessFromIndexSignature` 는 인덱스 시그니처에서 온 키를 대괄호로만 접근하게 강제해 선언된 키와 임의 키를 구문으로 구별한다.

```ts
// tsc --noPropertyAccessFromIndexSignature
interface Options {
  mode: "dev" | "prod";
  [extra: string]: string;
}
declare const o: Options;
o.mode;
o["verbose"];
// @ts-expect-error TS4111: 인덱스 시그니처에서 온 프로퍼티는 대괄호로 접근해야 한다
o.verbose;
// @ts-expect-error TS4111
o.mdoe;   // 오타가 string 으로 통과하던 문제를 드러낸다
```

`process.env.NODE_ENV` 같은 관용 표기도 TS4111 대상이 된다. `@types/node` 의 `ProcessEnv` 가 인덱스 시그니처로 선언되어 있기 때문이다. 이 경우 선언 병합으로 `ProcessEnv` 에 알려진 키를 추가하면 점 접근이 다시 허용되고, 6절의 옵션과 함께 쓸 때도 그 키는 `undefined` 검사 대상에서 빠진다.

## 8. 인덱스 시그니처를 걷어내고 알려진 키만 얻기

인덱스 시그니처가 섞인 타입에 `keyof` 를 쓰면 리터럴 키가 `string` 에 흡수된다. 키 리매핑에서 "넓은 키 타입" 을 `never` 로 걸러내면 알려진 키만 남는다.

```ts
interface Options {
  mode: "dev" | "prod";
  port: number;
  [extra: string]: string | number;
  [idx: number]: number;
}
type KnownKeys<T> = keyof {
  [K in keyof T as string extends K ? never : number extends K ? never : symbol extends K ? never : K]: T[K];
};
type OmitIndex<T> = { [K in KnownKeys<T> & keyof T]: T[K] };
type T1 = Expect<Eq<KnownKeys<Options>, "mode" | "port">>;
type T2 = Expect<Eq<OmitIndex<Options>, { mode: "dev" | "prod"; port: number }>>;
type T3 = Expect<Eq<Pick<Options, "mode">, { mode: "dev" | "prod" }>>;
// Omit 은 keyof 가 string | number 로 뭉개져 알려진 키가 사라진다
type T4 = Expect<Eq<Omit<Options, "port">, { [x: string]: string | number; [x: number]: number }>>;
```

`string extends K` 는 K 가 `string` 자체일 때만 참이고 `"mode"` 같은 리터럴에서는 거짓이다. 마지막 줄은 실무에서 자주 만나는 함정이다. `Omit<T, K>` 는 `Pick<T, Exclude<keyof T, K>>` 로 정의되는데 `Exclude<string | number, "port">` 가 `string | number` 그대로라서, 인덱스 시그니처가 있는 타입에 `Omit` 을 쓰면 `mode` 의 리터럴 타입 정보까지 사라진다. 서드파티 props 타입을 `Omit` 으로 다듬었더니 자동완성이 사라졌다면 이 경우다.

## 9. 사전 객체와 Map 의 런타임 차이

타입이 같아 보여도 일반 객체를 사전으로 쓰면 프로토타입 체인이 끼어든다. 아래 실행 결과는 Node v22.23.4 에서 얻었다.

```ts
const counts: Record<string, number> = {};
for (const w of ["a", "constructor", "a"]) {
  counts[w] = (counts[w] ?? 0) + 1;
}
console.log(counts);
// { a: 2, constructor: 'function Object() { [native code] }1' }

const dict: Record<string, number> = {};
console.log(typeof dict["constructor"], "toString" in dict, Object.hasOwn(dict, "toString"));
// function true false

const safe: Record<string, number> = Object.create(null);
console.log(safe["constructor"], "toString" in safe);
// undefined false

const m = new Map<string, number>();
for (const w of ["a", "constructor", "a"]) m.set(w, (m.get(w) ?? 0) + 1);
console.log([...m]);
// [ [ 'a', 2 ], [ 'constructor', 1 ] ]
const got = m.get("zzz");
type G = Expect<Eq<typeof got, number | undefined>>;

const user = { id: 1, name: "kim" };
const ks = Object.keys(user);
type KS = Expect<Eq<typeof ks, string[]>>;
const o: Record<string, unknown> = JSON.parse('{"__proto__": {"admin": true}}');
console.log(Object.keys(o), ({} as any).admin);
// [ '__proto__' ] undefined
```

첫 출력에서 단어 `constructor` 의 개수가 문자열로 망가졌다. `counts["constructor"]` 가 `Object.prototype.constructor` 함수를 돌려주어 `??` 의 기본값이 쓰이지 않았고, 함수에 1 을 더한 결과가 문자열 연결이 된 것이다. 타입은 `number` 라고 말하지만 컴파일러는 프로토타입에서 올라오는 값을 모델링하지 않는다. `Map.get` 은 옵션과 무관하게 `V | undefined` 를 돌려주고 프로토타입의 영향을 받지 않는다. `JSON.parse` 는 `__proto__` 를 자체 프로퍼티로 만들 뿐 전역 프로토타입을 바꾸지 않지만, 이런 객체를 재귀 병합하는 코드가 오염 경로가 된다.

정리하면 키 집합이 컴파일 시점에 정해져 있으면 리터럴 유니온 키의 `Record` 나 인터페이스를, 사용자 입력처럼 키가 열려 있으면 `Map` 을, JSON 으로 직렬화해야 해서 객체가 필요하면 `Object.create(null)` 또는 `Object.hasOwn` 검사를 쓴다. `Object.keys` 가 `string[]` 을 돌려주는 것도 같은 맥락이다. 구조적 타이핑에서는 선언보다 프로퍼티가 많은 객체가 대입될 수 있어 `(keyof T)[]` 를 보장할 수 없다.

검증 환경: 모든 예제는 TypeScript 5.9.3 과 7.0.2 에서 `tsc --strict --target es2022 --module nodenext --noEmit helpers.d.ts <파일>` 로 파일별 컴파일했고, 6절과 7절은 주석에 적은 옵션을 추가했다. 오류 코드는 `@ts-expect-error` 를 제거한 사본의 tsc 출력에서 옮겼다.

## 참고

- TypeScript Handbook, Object Types (Index Signatures): https://www.typescriptlang.org/docs/handbook/2/objects.html#index-signatures
- TypeScript 4.4 릴리스 노트 (Symbol and Template String Pattern Index Signatures): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-4.html
- TSConfig noUncheckedIndexedAccess: https://www.typescriptlang.org/tsconfig/#noUncheckedIndexedAccess
- TSConfig noPropertyAccessFromIndexSignature: https://www.typescriptlang.org/tsconfig/#noPropertyAccessFromIndexSignature
- TypeScript 4.1 릴리스 노트 (Key Remapping in Mapped Types): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-1.html
- MDN, Object.hasOwn: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Object/hasOwn
