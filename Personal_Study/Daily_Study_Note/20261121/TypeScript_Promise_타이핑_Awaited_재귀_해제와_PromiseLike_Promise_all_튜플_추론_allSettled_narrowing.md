Notion 원본: https://www.notion.so/41f5a06fd6d3811ca9aed9dc7bb888e9

# TypeScript Promise 타이핑: Awaited 재귀 해제와 PromiseLike, Promise.all 튜플 추론, allSettled 결과 narrowing, 컴파일러가 잡지 못하는 비동기 함정

> 2026-11-21 신규 주제 · 확장 대상: Javascript(Promise·async/await), TypeScript(조건부 타입·튜플)

## 학습 목표

- `Awaited<T>` 가 중첩 Promise 와 thenable 을 푸는 규칙을 타입 단언으로 확인한다
- `async` 함수의 반환 타입이 결정되는 방식과 반환 타입 주석의 제약(TS1064)을 재현한다
- `Promise.all` 이 튜플을 유지하는 경우와 배열로 무너지는 경우를 구분한다
- `Promise.allSettled` 결과를 판별 유니온으로 좁히고, 컴파일러가 통과시키는 비동기 실수 세 가지를 실행 결과로 확인한다

## 1. Promise 는 중첩되지 않는다는 사실을 타입에 반영하기

런타임의 Promise 는 중첩되지 않는다. Promise 를 다른 Promise 로 이행하면 안쪽 값이 나올 때까지 풀린다. `await` 도 같아서 Promise 를 `await` 하면 항상 최종 값이 나온다.

```text
await thenable: 42
nested: 1
```

위 출력은 `{ then(res) { res(42); } }` 라는 평범한 객체와 `Promise.resolve(Promise.resolve(1))` 을 각각 `await` 한 결과다. `then` 메서드를 가진 객체는 Promise 가 아니어도 풀리고, 이중으로 감싼 Promise 도 한 번의 `await` 로 `1` 이 된다. 그런데 타입으로는 `Promise<Promise<number>>` 를 얼마든지 쓸 수 있다. 이 간극을 메우는 것이 TypeScript 4.5 에 추가된 `Awaited<T>` 다. 표준 라이브러리의 정의는 다음과 같다.

```typescript
type Awaited<T> = T extends null | undefined ? T :
	T extends object & { then(onfulfilled: infer F, ...args: infer _): any } ?
		F extends ((value: infer V, ...args: infer _) => any) ? Awaited<V> : never :
	T;
```

`then` 메서드를 가진 객체이면 그 첫 인자인 콜백이 받는 값의 타입 `V` 를 꺼내고, `V` 에 다시 `Awaited` 를 적용한다. `then` 이 없으면 그대로 돌려준다. `Promise` 라는 이름을 검사하지 않고 구조만 본다는 점이 런타임의 thenable 동화 규칙과 맞물린다.

```typescript
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

const a1: Equal<Awaited<Promise<Promise<number>>>, number> = true;
const a2: Equal<Awaited<string | Promise<number>>, string | number> = true;
const a3: Equal<Awaited<null | undefined>, null | undefined> = true;
type Thenable = { then(onfulfilled: (v: Date) => unknown): unknown };
const a4: Equal<Awaited<Thenable>, Date> = true;
type NotThenable = { then: string };
const a5: Equal<Awaited<NotThenable>, NotThenable> = true;
```

다섯 줄 모두 `tsc --noEmit` 을 통과했다. `a2` 는 조건부 타입이 유니온에 분배되어 구성원마다 풀린다는 것을, `a4` 는 `Promise` 가 아닌 구조도 풀린다는 것을, `a5` 는 `then` 이 메서드가 아니면 건드리지 않는다는 것을 보여 준다. `PromiseLike<T>` 는 이 "then 메서드만 가진 최소 구조" 에 이름을 붙인 인터페이스다. 라이브러리의 매개변수를 `Promise<T>` 대신 `PromiseLike<T>` 로 받으면 다른 구현체가 만든 thenable 도 받을 수 있다.

## 2. async 함수의 반환 타입

```typescript
async function f1() { return 1; }
async function f2() { return Promise.resolve("x"); }
async function f3(flag: boolean) { return flag ? 1 : Promise.resolve("x"); }
const b1: Equal<ReturnType<typeof f1>, Promise<number>> = true;
const b2: Equal<ReturnType<typeof f2>, Promise<string>> = true;

async function f4(): number { return 1; }
async function f5(): PromiseLike<number> { return 1; }
```

```text
prom.ts(19,22): error TS1064: The return type of an async function or method must be the global Promise<T> type. Did you mean to write 'Promise<number>'?
prom.ts(20,22): error TS1064: The return type of an async function or method must be the global Promise<T> type. Did you mean to write 'Promise<number>'?
```

`async` 함수는 `return` 한 값의 타입에 `Awaited` 를 적용한 뒤 `Promise` 로 한 번만 감싼다. `f2` 가 `Promise<Promise<string>>` 이 아니라 `Promise<string>` 인 이유다. 반환 타입을 직접 적을 때는 전역 `Promise<T>` 만 허용된다. `number` 는 물론이고 구조상 호환되는 `PromiseLike<number>` 도 TS1064 로 거부된다. `async` 함수가 실제로 돌려주는 것은 언제나 내장 Promise 이므로 그보다 넓은 타입을 적는 것을 막는 규칙이다. 인터페이스에서 메서드를 `PromiseLike<T>` 반환으로 선언하고 구현을 `async` 로 쓰는 것은 문제가 없다. 구현 쪽 주석만 `Promise<T>` 로 적으면 된다.

`f3` 은 예상과 다른 결과가 나왔다. `Promise<string | number>` 로 단언하면 오류가 나서 실제 타입을 출력해 보니 다음과 같았다.

```text
q.ts(2,7): error TS2322: Type '(flag: boolean) => Promise<string | 1>' is not assignable to type 'never'.
```

`f1` 에서는 `1` 이 `number` 로 넓혀졌는데, 다른 타입과 유니온을 이루는 `f3` 에서는 리터럴 타입 `1` 이 그대로 남았다. `if` 로 나눠 `return` 을 두 번 쓴 형태로 바꿔도 같은 타입이었다. 반환 타입을 추론에 맡긴 `async` 함수의 결과를 다른 타입 연산의 입력으로 쓸 때, 리터럴이 남는 경우가 있다는 점을 염두에 둬야 한다. 공개 함수는 반환 타입을 적어 두면 이런 차이가 호출부로 새지 않는다.

## 3. Promise.all 이 튜플을 지키는 조건

```typescript
declare const pn: Promise<number>;
declare const ps: Promise<string>;

async function all() {
	const t = await Promise.all([pn, ps, true]);
	const c1: Equal<typeof t, [number, string, boolean]> = true;

	const arr = [pn, ps];
	const u = await Promise.all(arr);
	const c2: Equal<typeof u, (string | number)[]> = true;

	const tup = [pn, ps] as const;
	const v = await Promise.all(tup);
	const c3: Equal<typeof v, [number, string]> = true;

	const obj = { n: pn, s: ps };
	const w = await Promise.all(Object.values(obj));
	const c4: Equal<typeof w, (string | number)[]> = true;

	const r = await Promise.race([pn, ps]);
	const c5: Equal<typeof r, string | number> = true;

	const e = await Promise.all([]);
	const c6: Equal<typeof e, []> = true;
}
```

여섯 개의 단언이 모두 통과했다. `Promise.all` 의 선언은 다음과 같다.

```typescript
all<T extends readonly unknown[] | []>(values: T): Promise<{ -readonly [P in keyof T]: Awaited<T[P]>; }>;
```

제약에 붙은 `| []` 가 핵심이다. 타입 매개변수의 제약에 튜플 타입이 포함돼 있으면 컴파일러는 배열 리터럴 인자를 배열이 아니라 튜플로 추론한다. 그래서 `Promise.all([pn, ps, true])` 처럼 리터럴을 직접 넘기면 위치별 타입이 유지된다(`c1`). 반환 타입은 튜플에 대한 매핑 타입이라 각 위치에 `Awaited` 가 적용되고, `-readonly` 가 `as const` 로 붙은 읽기 전용을 떼어 낸다(`c3`). Promise 가 아닌 `true` 도 `Awaited<boolean>` 이 `boolean` 이라 그대로 통과한다.

배열을 변수에 먼저 담으면 추론 시점이 달라진다. `const arr = [pn, ps]` 는 선언 시점에 `(Promise<number> | Promise<string>)[]` 로 확정되고, `Promise.all(arr)` 는 그 배열 타입을 받으므로 결과가 `(string | number)[]` 가 된다(`c2`). 구조 분해로 `const [n, s] = await Promise.all(arr)` 를 쓰면 `n` 과 `s` 가 둘 다 `string | number` 다. 객체의 값들을 `Object.values` 로 넘기는 경우(`c4`)도 같다. 키별 타입을 유지하면서 객체 단위로 기다리려면 키 배열과 값 튜플을 따로 다루는 보조 함수를 만들어야 한다.

실행 순서와 결과 순서는 별개다.

```text
all: [ 'a', 'b', 'c' ] 완료 순서: [ 'b', 'a' ]
```

30ms 뒤에 끝나는 `a` 와 10ms 뒤에 끝나는 `b` 를 넘겼을 때 완료는 `b` 가 먼저지만 결과 배열은 입력 순서를 따른다. 튜플 타입의 위치와 런타임 값의 위치가 일치하는 근거다.

## 4. allSettled 결과는 판별 유니온이다

```typescript
async function settled() {
	const rs = await Promise.allSettled([pn, ps]);
	const d1: Equal<typeof rs, [PromiseSettledResult<number>, PromiseSettledResult<string>]> = true;

	const first = rs[0];
	if (first.status === "fulfilled") {
		const n: number = first.value;
	} else {
		const reason: any = first.reason;
		first.value;
	}

	const list: PromiseSettledResult<number>[] = await Promise.allSettled([pn, pn]);
	const ok = list.filter((r) => r.status === "fulfilled");
	const d2: Equal<typeof ok, PromiseFulfilledResult<number>[]> = true;
	const values = ok.map((r) => r.value);
}
```

```text
prom.ts(51,9): error TS2339: Property 'value' does not exist on type 'PromiseRejectedResult'.
```

`PromiseSettledResult<T>` 는 `{ status: "fulfilled"; value: T }` 와 `{ status: "rejected"; reason: any }` 의 유니온이다. `status` 를 비교하면 좁혀지고, `else` 분기에서 `value` 에 접근하면 TS2339 가 난다. `reason` 의 타입이 `any` 라는 점은 주의할 부분이다. `catch` 절 변수는 `useUnknownInCatchVariables` 로 `unknown` 이 되지만 `reason` 은 표준 라이브러리 선언에 `any` 로 박혀 있어 옵션의 영향을 받지 않는다. `reason.message` 를 검사 없이 써도 컴파일러는 막지 않는다.

`d2` 는 TypeScript 5.5 의 타입 서술어 추론 덕분에 통과한다. `(r) => r.status === "fulfilled"` 가 `r is PromiseFulfilledResult<number>` 로 추론되어 `filter` 결과가 좁혀진다. 5.4 이하에서는 같은 코드의 `ok` 가 `PromiseSettledResult<number>[]` 로 남아 `r.value` 에서 오류가 나므로 타입 서술어를 직접 적어야 했다.

```text
[ 'ok:1', 'fail:boom', 'ok:3' ]
AggregateError [ 'e1', 'e2' ]
race([]) 는 영원히 pending: timeout
```

런타임 동작 세 가지를 함께 확인했다. `allSettled` 는 거부가 섞여도 전체 결과를 돌려준다. `Promise.any` 는 전부 거부되면 `AggregateError` 로 거부되고 개별 오류는 `errors` 배열에 담긴다. `Promise.race([])` 는 이행도 거부도 되지 않는다. 마지막 줄은 빈 `race` 를 5ms 타이머와 다시 경쟁시켜 타이머가 이긴 결과다. 타입으로는 `Promise.race([])` 가 문제없이 통과하므로, 동적으로 만든 배열이 비어 있을 가능성이 있으면 호출 전에 길이를 확인해야 한다.

## 5. 컴파일러가 잡는 실수와 잡지 못하는 실수

```typescript
declare function isAdmin(): Promise<boolean>;

async function traps() {
	if (isAdmin()) { }
	[1, 2].forEach(async (n) => { await pn; });
	const handler: () => void = async () => { await pn; };
	const total: number = pn + 1;
	pn.then((v) => v.toFixed());
}
```

```text
prom.ts(62,6): error TS2801: This condition will always return true since this 'Promise<boolean>' is always defined.
prom.ts(65,24): error TS2365: Operator '+' cannot be applied to types 'Promise<number>' and '1'.
```

다섯 줄 가운데 오류는 두 건이다. `await` 를 빠뜨린 조건문(TS2801)과 Promise 를 산술에 쓴 경우(TS2365)는 잡힌다. 나머지 세 줄은 통과한다.

| 코드 | 컴파일 결과 | 실제 동작 |
|---|---|---|
| `if (isAdmin())` | TS2801 | Promise 객체는 항상 참 |
| `pn + 1` | TS2365 | 문자열 연결이 됨 |
| `forEach(async ...)` | 통과 | 콜백의 완료를 기다리지 않음 |
| `() => void` 자리에 `async` 함수 | 통과 | 반환된 Promise 가 버려짐 |
| `pn.then(...)` 만 호출 | 통과 | 거부가 처리되지 않음 |

`() => void` 타입은 "반환값을 쓰지 않겠다" 는 뜻이어서 무엇을 반환하는 함수든 할당할 수 있다. `async` 함수도 예외가 아니다. `forEach` 의 콜백 타입이 `void` 반환이므로 같은 이유로 통과한다.

```text
forEach 직후: []
마이크로태스크 후: [ 1, 2, 3 ]
if (isAdmin()) 는 항상 참
```

`forEach` 에 `async` 콜백을 넘기고 바로 다음 줄에서 결과 배열을 보면 비어 있다. 콜백 세 개가 각각 Promise 를 돌려주지만 `forEach` 는 그것을 버린다. 순차 처리가 필요하면 `for...of` 안에서 `await` 하고, 병렬 처리가 필요하면 `map` 으로 Promise 배열을 만들어 `Promise.all` 에 넘긴다. 세 번째 줄은 `false` 를 이행하는 `isAdmin()` 을 `await` 없이 조건에 쓴 경우로, JavaScript 로 실행하면 분기 안으로 들어간다.

통과하는 세 가지는 타입 정보를 쓰는 린트 규칙의 영역이다. typescript-eslint 의 `no-floating-promises` 가 처리되지 않은 Promise 를, `no-misused-promises` 가 `void` 반환 자리에 넘긴 `async` 함수와 조건문의 Promise 를 보고한다. 두 규칙 모두 타입 검사기를 호출해야 하므로 `parserOptions.projectService` 또는 `project` 설정이 필요하다.

## 6. 실무 판단

- 제네릭 유틸리티에서 "기다린 뒤의 타입" 이 필요하면 `T extends Promise<infer U>` 를 직접 쓰지 말고 `Awaited<T>` 를 쓴다. 직접 쓴 조건부 타입은 한 겹만 풀고 thenable 을 놓친다.
- 입력은 `PromiseLike<T>` 로 넓게 받고, 반환은 `Promise<T>` 로 좁게 준다.
- `Promise.all` 에는 배열 리터럴을 직접 넘기거나 `as const` 를 붙인다. 변수에 담은 배열을 넘기면 위치별 타입이 사라진다.
- `allSettled` 의 `reason` 은 `any` 이므로 `instanceof Error` 로 좁힌 뒤에 쓴다.
- `await` 누락은 컴파일러만으로는 일부만 잡힌다. 타입 인식 린트 규칙 두 개를 CI 에 포함한다.

검증 환경: TypeScript 7.0.2 에서 `strict: true`, `target: es2022`, `lib: ["es2023"]` 로 `tsc --noEmit` 을 실행했다. 타입 코드는 한 파일(`prom.ts`)의 발췌이며, 인용한 오류 다섯 건에 더해 `f3` 을 `Promise<string | number>` 로 단언했던 줄의 TS2322 한 건이 그 파일에서 나온 오류의 전부다. 런타임 출력은 Node.js 22.23.5 에서 ES 모듈 스크립트로 실행한 결과다. 4절의 "5.4 이하" 동작과 5절의 린트 규칙 동작은 릴리스 노트와 규칙 문서에 근거하며 이 환경에서 실행하지 않았다.

## 참고

- TypeScript 4.5 릴리스 노트 (The Awaited Type and Promise Improvements): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- TypeScript 5.5 릴리스 노트 (Inferred Type Predicates): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- TypeScript 소스, `lib.es5.d.ts` (`Awaited`, `PromiseLike` 선언): https://github.com/microsoft/TypeScript/blob/main/src/lib/es5.d.ts
- TypeScript 소스, `lib.es2015.promise.d.ts` (`Promise.all`, `Promise.race` 선언): https://github.com/microsoft/TypeScript/blob/main/src/lib/es2015.promise.d.ts
- TypeScript 소스, `lib.es2020.promise.d.ts` (`PromiseSettledResult` 선언): https://github.com/microsoft/TypeScript/blob/main/src/lib/es2020.promise.d.ts
- ECMAScript 명세, Promise Resolve Functions (thenable 동화): https://tc39.es/ecma262/#sec-promise-resolve-functions
- typescript-eslint, no-floating-promises: https://typescript-eslint.io/rules/no-floating-promises/
- typescript-eslint, no-misused-promises: https://typescript-eslint.io/rules/no-misused-promises/
