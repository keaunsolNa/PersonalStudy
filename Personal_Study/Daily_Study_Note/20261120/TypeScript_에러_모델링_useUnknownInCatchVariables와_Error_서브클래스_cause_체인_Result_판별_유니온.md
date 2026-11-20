Notion 원본: https://www.notion.so/41e5a06fd6d38180b9abf6f0e8f2ba04

# TypeScript 에러 모델링: useUnknownInCatchVariables, Error 서브클래스와 instanceof, cause 체인, Result 판별 유니온

> 2026-11-20 신규 주제 · 확장 대상: TypeScript(내로잉·판별 유니온), JAVA(checked/unchecked 예외 계층)

## 학습 목표

- `catch` 변수가 `unknown` 인 이유를 `Error` 가 아닌 값의 `throw` 로 재현하고 정규화 함수를 작성한다
- `Error` 서브클래스의 `name`·`instanceof`·직렬화 동작을 실행해 확인하고 ES5 타깃에서 깨지는 조건을 재현한다
- `cause` 로 하위 계층 오류를 연결하고 체인을 끝까지 따라가는 함수를 구현한다
- 예외와 `Result` 판별 유니온 중 어느 쪽을 쓸지 실패의 성격에 따라 결정한다

## 1. catch 변수는 unknown 이다

JavaScript 의 `throw` 는 어떤 값이든 던질 수 있다. 그래서 `catch (e)` 의 `e` 에 대해 컴파일러가 보장할 수 있는 타입은 없다. 4.4 에서 도입된 `useUnknownInCatchVariables`(`strict` 에 포함)는 `e` 를 `any` 대신 `unknown` 으로 만들어, 좁히기 전에는 프로퍼티 접근이 불가능하게 한다.

이후 예제는 다음 헬퍼를 `helpers.d.ts` 로 함께 컴파일한다. `Expect<Eq<A, B>>` 는 두 타입이 다르면 TS2344 로 실패한다.

```ts
type Eq<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Expect<T extends true> = T;
```

```ts
function parse(json: string): unknown {
  try {
    return JSON.parse(json);
  } catch (e) {
    type E1 = Expect<Eq<typeof e, unknown>>;
    if (e instanceof SyntaxError) {
      type E2 = Expect<Eq<typeof e, SyntaxError>>;
      console.log(e.name, e.message);
    }
    return undefined;
  }
}
parse("{oops");
// SyntaxError Expected property name or '}' in JSON at position 1 (line 1 column 2)
function compileOnly() {
  try {
    parse("1");
  } catch (e) {
    // @ts-expect-error TS18046: 'e' is of type 'unknown'
    console.log(e.message);
  }
}
try { throw "plain string"; } catch (e) { console.log(typeof e, e instanceof Error); }
// string false
try { (undefined as any).x; } catch (e: any) { console.log(e.constructor.name); }
// TypeError
function toError(e: unknown): Error {
  if (e instanceof Error) return e;
  const message = typeof e === "string" ? e : JSON.stringify(e) ?? String(e);
  return new Error(message, { cause: e });
}
console.log([toError("x").message, toError({ code: 7 }).message, toError(undefined).message, toError(() => 1).message]);
// [ 'x', '{"code":7}', 'undefined', '()=>1' ]
Promise.reject(new RangeError("r")).catch((reason) => {
  type R = Expect<Eq<typeof reason, any>>;
  console.log(reason.name);
});
// RangeError
export {};
```

`catch` 절의 타입 표기는 `any` 와 `unknown` 만 허용된다. `catch (e: Error)` 는 문법 오류다. Java 의 `catch (IOException e)` 처럼 타입으로 분기하는 구문이 없으므로 분기는 본문의 `instanceof` 로 직접 작성해야 한다. `catch (e: any)` 는 옵션을 파일 단위로 끄지 않고 그 지점만 예전 동작으로 되돌리는 탈출구다.

`toError` 는 경계에서 한 번 호출해 이후 코드가 `Error` 만 다루게 하는 정규화 함수다. `JSON.stringify` 는 `undefined` 와 함수에 대해 문자열이 아닌 `undefined` 를 반환하므로 `?? String(e)` 가 없으면 메시지가 빈 문자열이 된다. 원래 값은 `cause` 에 보존한다.

`Promise` 쪽은 사정이 다르다. `Promise<T>` 에는 실패 타입 매개변수가 없고, `.catch` 콜백의 `reason` 은 lib 선언상 `any` 다. `useUnknownInCatchVariables` 는 `catch` 절에만 적용되므로 `.catch((reason) => ...)` 와 `Promise.allSettled` 의 `reason` 은 `strict` 에서도 `any` 로 남는다. 콜백 매개변수에 직접 `(reason: unknown)` 을 적어야 같은 수준의 검사를 받는다.

## 2. Error 서브클래스: name 과 instanceof

도메인 오류는 `Error` 를 상속한 클래스로 만든다. `instanceof` 로 좁힐 수 있고, 추가 필드를 타입과 함께 실어 보낼 수 있다.

```ts
class AppError extends Error {
  override readonly name: string = "AppError";
  constructor(message: string, readonly code: string, options?: ErrorOptions) {
    super(message, options);
  }
}
class NotFoundError extends AppError {
  override readonly name = "NotFoundError";
  constructor(readonly resource: string, readonly id: number, options?: ErrorOptions) {
    super(`${resource} ${id} not found`, "E404", options);
  }
}
const e = new NotFoundError("order", 7);
console.log(e instanceof NotFoundError, e instanceof AppError, e instanceof Error);
// true true true
console.log(e.name, e.code, e.message, Object.prototype.toString.call(e));
// NotFoundError E404 order 7 not found [object Error]
console.log(String(e), e.stack?.split("\n")[0]);
// NotFoundError: order 7 not found NotFoundError: order 7 not found
console.log(JSON.stringify(e), Object.keys(e));
// {"code":"E404","name":"NotFoundError","resource":"order","id":7} [ 'code', 'name', 'resource', 'id' ]
console.log(JSON.stringify({ name: e.name, message: e.message, code: e.code }));
// {"name":"NotFoundError","message":"order 7 not found","code":"E404"}
const cloned = structuredClone(e);
console.log(cloned instanceof NotFoundError, cloned instanceof Error, cloned.name, (cloned as any).code);
// false true Error undefined
export {};
```

`name` 을 지정하지 않으면 서브클래스 인스턴스도 `Error.prototype.name` 인 `"Error"` 를 물려받아 로그에 `Error: ...` 로만 찍힌다. `this.constructor.name` 으로 자동 설정하는 방식은 번들러가 클래스 이름을 축약하면 `"t"` 같은 값이 되므로 문자열 리터럴로 고정하는 편이 안전하다. `override` 는 `noImplicitOverride` 가 켜져 있을 때 필수이고, 상위 선언과 이름이 어긋나면 컴파일 오류로 알려 준다.

직렬화 결과는 두 가지를 보여 준다. `message` 와 `stack` 은 열거 불가능한 자체 프로퍼티여서 `JSON.stringify(e)` 에 포함되지 않는다. 클래스 필드로 선언한 `name`·`code`·`resource`·`id` 만 나온다. 로그나 HTTP 응답으로 내보낼 때는 필요한 필드를 명시적으로 뽑아야 한다. 그리고 `structuredClone` 은 `Error` 계열을 복제하지만 프로토타입은 표준 오류 타입으로만 복원하고 사용자 정의 필드는 버린다. `postMessage` 로 워커 경계를 넘긴 오류에 `instanceof NotFoundError` 를 쓰면 항상 `false` 다. 프로세스·스레드 경계를 넘는 오류는 `code` 같은 문자열 판별자를 가진 평범한 객체로 변환해 보내고 받는 쪽에서 판별자로 분기한다.

## 3. ES5 타깃에서 instanceof 가 깨지는 조건

`target` 이 ES5 이면 클래스가 생성자 함수로 변환되는데, `Error` 를 함수로 호출하면 `this` 를 무시하고 새 객체를 반환한다. 그 결과 서브클래스 생성자가 돌려주는 객체의 프로토타입이 `Error.prototype` 이 되어 서브클래스 메서드와 `instanceof` 가 사라진다.

```ts
class LegacyError extends Error {
  code = "E1";
  constructor(message: string) { super(message); }
  describe() { return this.code + ":" + this.message; }
}
class FixedError extends Error {
  code = "E2";
  constructor(message: string) {
    super(message);
    Object.setPrototypeOf(this, new.target.prototype);
  }
  describe() { return this.code + ":" + this.message; }
}
var a = new LegacyError("x");
console.log(a instanceof LegacyError, a instanceof Error, typeof (a as any).describe, a.code);
var b = new FixedError("y");
console.log(b instanceof FixedError, b.describe());
```

이 파일을 TypeScript 5.9.3 으로 `--target es5 --lib es2015` 컴파일해 실행한 출력과 `--target es2015` 로 컴파일해 실행한 출력은 다음과 같다.

```text
# target es5
false true undefined E1
true E2:y
# target es2015
true true function E1
true E2:y
```

ES5 출력에서 `LegacyError` 인스턴스는 필드 `code` 는 가지고 있지만 `describe` 메서드가 없고 `instanceof LegacyError` 가 `false` 다. `Object.setPrototypeOf(this, new.target.prototype)` 한 줄이 프로토타입 체인을 복구한다. ES2015 이상 타깃은 네이티브 `class` 를 그대로 방출하므로 이 문제가 없다. TypeScript 7.0.2 에 `--target es5` 를 주면 `TS5108: Option 'target=ES5' has been removed` 로 거부된다. 지금 이 보정 코드를 만나는 곳은 오래된 라이브러리의 소스이거나, Babel 등 다른 도구로 ES5 까지 내리는 빌드 파이프라인이다. 그런 파이프라인이 없으면 새 코드에 넣을 필요가 없다.

## 4. cause 로 계층 간 오류를 연결한다

하위 계층의 오류를 그대로 올리면 상위 계층이 구현 세부(드라이버 오류 클래스)에 의존하게 되고, 새 오류로 바꿔 던지면 원인이 사라진다. ES2022 의 `cause` 옵션은 둘을 함께 해결한다. Java 의 `new ServiceException("...", e)` 와 같은 역할이다.

```ts
class RepositoryError extends Error { override name = "RepositoryError"; }
function query(): never {
  throw new TypeError("connection reset");
}
function findUser(id: number) {
  try {
    query();
  } catch (cause) {
    throw new RepositoryError(`findUser(${id}) failed`, { cause });
  }
}
try {
  findUser(1);
} catch (e) {
  if (e instanceof RepositoryError) {
    type C = Expect<Eq<typeof e.cause, unknown>>;
    console.log(e.message, "<-", e.cause instanceof TypeError && e.cause.message);
  }
}
// findUser(1) failed <- connection reset
function rootCause(e: unknown): unknown {
  let cur = e;
  const seen = new Set<unknown>();
  while (cur instanceof Error && cur.cause !== undefined && !seen.has(cur)) {
    seen.add(cur);
    cur = cur.cause;
  }
  return cur;
}
const chain = new Error("a", { cause: new Error("b", { cause: "c" }) });
console.log(rootCause(chain), Object.hasOwn(chain, "cause"), Object.hasOwn(new Error("n"), "cause"));
// c true false
const agg = new AggregateError([new Error("x"), "y"], "multi");
type AE = Expect<Eq<typeof agg.errors, any[]>>;
console.log(agg.errors.length, agg.message);
// 2 multi
Promise.any([Promise.reject(new Error("p1")), Promise.reject(new Error("p2"))]).catch((e2: unknown) => {
  if (e2 instanceof AggregateError) console.log(e2.errors.map((x) => x.message));
});
// [ 'p1', 'p2' ]
export {};
```

`cause` 의 타입은 `unknown` 이다. `throw` 와 마찬가지로 아무 값이나 넣을 수 있기 때문이며, 꺼내 쓸 때 다시 좁혀야 한다. `rootCause` 가 `seen` 집합을 두는 것은 `cause` 가 순환하도록 조작된 객체에서 무한 루프를 피하기 위해서다. `cause` 프로퍼티는 옵션으로 넘겼을 때만 자체 프로퍼티로 생기므로 `"cause" in err` 로 유무를 구분할 수 있다. `cause` 를 쓰려면 `lib` 이 es2022 이상이어야 하고, 그보다 낮으면 `new Error(msg, { cause })` 의 두 번째 인수가 TS2554 로 거부된다. `AggregateError.errors` 는 `any[]` 로 선언되어 있어 원소 접근에 검사가 걸리지 않는다. `Promise.any` 가 전부 실패했을 때의 원인 목록을 다룰 때는 원소마다 `toError` 를 거치는 것이 안전하다.

## 5. Result 판별 유니온: 실패를 반환 타입에 적는다

TypeScript 에는 Java 의 `throws` 절이 없다. 함수 시그니처만 봐서는 무엇이 던져지는지 알 수 없고, 호출자가 처리를 빠뜨려도 컴파일러가 알려 주지 않는다. 호출자가 반드시 처리해야 하는 실패는 반환 타입에 넣는 방법이 있다.

```ts
type Result<T, E> = { ok: true; value: T } | { ok: false; error: E };
const ok = <T,>(value: T): Result<T, never> => ({ ok: true, value });
const err = <const E,>(error: E): Result<never, E> => ({ ok: false, error });

type ParseError = { kind: "empty" } | { kind: "nan"; raw: string } | { kind: "range"; min: number; max: number };
function parsePort(raw: string): Result<number, ParseError> {
  if (raw === "") return err({ kind: "empty" });
  const n = Number(raw);
  if (!Number.isInteger(n)) return err({ kind: "nan", raw });
  if (n < 1 || n > 65535) return err({ kind: "range", min: 1, max: 65535 });
  return ok(n);
}
function describe(e: ParseError): string {
  switch (e.kind) {
    case "empty": return "empty";
    case "nan": return `not a number: ${e.raw}`;
    case "range": return `out of ${e.min}..${e.max}`;
    default: { const _never: never = e; return _never; }
  }
}
for (const raw of ["8080", "", "abc", "70000"]) {
  const r = parsePort(raw);
  console.log(r.ok ? r.value : describe(r.error));
}
// 8080
// empty
// not a number: abc
// out of 1..65535
function map<T, U, E>(r: Result<T, E>, f: (t: T) => U): Result<U, E> {
  return r.ok ? ok(f(r.value)) : r;
}
function andThen<T, U, E, F>(r: Result<T, E>, f: (t: T) => Result<U, F>): Result<U, E | F> {
  return r.ok ? f(r.value) : r;
}
const chained = andThen(parsePort("443"), (p) => (p < 1024 ? err("privileged") : ok(p)));
type Ch = Expect<Eq<typeof chained, Result<number, ParseError | "privileged">>>;
console.log(chained, map(parsePort("80"), (p) => p + 1));
// { ok: false, error: 'privileged' } { ok: true, value: 81 }
function tryCatch<T>(f: () => T): Result<T, Error> {
  try { return ok(f()); } catch (e) { return err(e instanceof Error ? e : new Error(String(e), { cause: e })); }
}
const j = tryCatch(() => JSON.parse("{"));
console.log(j.ok, !j.ok && j.error.name);
// false SyntaxError
export {};
```

`ok` 가 `Result<T, never>`, `err` 가 `Result<never, E>` 를 돌려주는 것이 핵심이다. `never` 는 모든 타입에 대입되므로 두 값 모두 `Result<number, ParseError>` 자리에 그대로 들어간다. `err` 의 `const` 타입 매개변수는 `"privileged"` 를 `string` 으로 넓히지 않고 리터럴로 유지해, `andThen` 의 결과에서 실패 타입이 `ParseError | "privileged"` 로 누적되는 것을 볼 수 있게 한다. `describe` 의 `default` 분기는 `ParseError` 에 새 변형이 추가되면 `never` 대입에서 컴파일 오류를 낸다. 예외로는 얻을 수 없는 성질이다.

비용도 있다. `Result` 는 호출 체인의 모든 함수 시그니처에 실패 타입이 전파되고, `await`·`?.` 같은 언어 지원이 없어 `map`·`andThen` 호출이 늘어난다. 스택 트레이스도 자동으로 붙지 않는다. `tryCatch` 처럼 예외를 던지는 외부 API 와의 경계에서 변환 함수가 필요하다.

## 6. never 반환 함수와 단언 함수로 흐름 분석 돕기

던지기만 하는 함수의 반환 타입을 `never` 로 적으면 호출 이후 코드가 도달 불가능하다는 사실을 제어 흐름 분석이 이용한다. 단언 함수(`asserts e is Error`)는 통과한 뒤의 변수 타입을 좁힌다.

```ts
async function load(id: number): Promise<string> {
  if (id < 0) throw new RangeError("negative id");
  return `user-${id}`;
}
type L = Expect<Eq<ReturnType<typeof load>, Promise<string>>>;
const settled = await Promise.allSettled([load(1), load(-1)]);
type S = Expect<Eq<typeof settled, [PromiseSettledResult<string>, PromiseSettledResult<string>]>>;
for (const s of settled) {
  if (s.status === "fulfilled") console.log(s.value);
  else {
    type Rn = Expect<Eq<typeof s.reason, any>>;
    console.log(s.reason instanceof RangeError, s.reason.message);
  }
}
// user-1
// true negative id
function fail(message: string): never { throw new Error(message); }
function assertIsError(e: unknown): asserts e is Error {
  if (!(e instanceof Error)) throw new TypeError("non-Error thrown", { cause: e });
}
function port(env: Record<string, string | undefined>): number {
  const raw = env.PORT ?? fail("PORT missing");
  type Raw = Expect<Eq<typeof raw, string>>;
  return Number(raw);
}
try { port({}); } catch (e) { assertIsError(e); type A = Expect<Eq<typeof e, Error>>; console.log(e.message); }
// PORT missing
function isErrnoException(e: unknown): e is NodeJS.ErrnoException {
  return e instanceof Error && "code" in e && typeof e.code === "string";
}
import { readFile } from "node:fs/promises";
try { await readFile("/no/such/file"); } catch (e) { if (isErrnoException(e)) console.log(e.code, e.syscall); }
// ENOENT open
export {};
```

`env.PORT ?? fail("...")` 에서 우변이 `never` 이므로 전체 타입이 `string | never`, 즉 `string` 이 된다. `async` 함수의 반환 타입은 `Promise<string>` 뿐이고 `RangeError` 는 어디에도 나타나지 않는다. `PromiseSettledResult` 의 `rejected` 분기에서도 `reason` 은 `any` 다.

Node.js 의 시스템 오류는 클래스가 아니라 `code` 문자열로 구분한다. `ENOENT` 와 `EACCES` 모두 평범한 `Error` 인스턴스여서 `instanceof` 로는 나눌 수 없고, 위와 같은 타입 가드로 `NodeJS.ErrnoException` 으로 좁힌 뒤 `code` 를 비교한다. 이 파일은 `@types/node` 가 필요하다. TypeScript 7.0.2 에서는 `--types node` 를 명시해야 컴파일되었고, 생략하면 `TS2503: Cannot find namespace 'NodeJS'` 와 TS2591 이 났다. 5.9.3 은 같은 명령에서 `node_modules/@types` 를 자동으로 포함해 옵션 없이 통과했다.

## 7. 예외와 Result 의 선택 기준

두 방식은 대체재가 아니라 실패의 성격에 따라 나눠 쓰는 도구다. 입력 검증 실패, 조회 결과 없음, 잔액 부족처럼 정상 업무 흐름의 한 갈래이고 호출자가 그 자리에서 분기해야 하는 실패는 `Result` 나 판별 유니온으로 반환한다. 컴파일러가 처리 누락을 잡아 주고, 변형이 추가될 때 수정할 곳을 알려 준다. 네트워크 단절, 불변식 위반, 프로그래밍 오류처럼 대부분의 호출자가 복구할 수 없고 최상위 핸들러까지 올라가야 하는 실패는 예외로 던진다. 중간 계층이 모두 `Result` 를 전달만 하게 만드는 것은 시그니처 소음만 늘린다.

예외를 쓸 때의 규칙은 세 가지로 정리된다. 항상 `Error` 인스턴스를 던진다. 문자열이나 객체 리터럴을 던지면 스택 트레이스가 없고 `instanceof Error` 분기를 통과하지 못한다. 계층을 넘길 때는 `cause` 에 원인을 담아 새 오류로 감싼다. 그리고 `catch` 한 값은 `unknown` 으로 받아 경계에서 한 번 정규화한다. Spring 의 `@ExceptionHandler` 계층에 해당하는 최상위 핸들러에서는 `instanceof AppError` 이면 `code` 로 응답을 만들고, 그 외는 500 으로 처리하며 `rootCause` 를 로그에 남기는 구성이 일반적이다.

검증 환경: 모든 예제는 TypeScript 5.9.3 과 7.0.2 에서 `tsc --strict --target esnext --module nodenext --noEmit helpers.d.ts <파일>` 로 파일별 컴파일했다(6절 파일은 `--types node` 추가, `package.json` 의 `"type": "module"`). 오류 코드는 `@ts-expect-error` 를 제거한 사본의 tsc 7.0.2 출력에서 옮겼고, 출력 주석은 Node v22.23.5 에서 `node --experimental-transform-types` 로 실행한 결과다. 3절은 TypeScript 5.9.3 으로 방출한 JavaScript 를 같은 Node 로 실행했다.

## 참고

- TypeScript 4.4 릴리스 노트 (useUnknownInCatchVariables): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-4.html
- TSConfig Reference, useUnknownInCatchVariables: https://www.typescriptlang.org/tsconfig/#useUnknownInCatchVariables
- TypeScript Breaking Changes 2.1 (Extending built-ins like Error, Array, and Map may no longer work): https://github.com/microsoft/TypeScript-wiki/blob/main/Breaking-Changes.md
- TypeScript 3.7 릴리스 노트 (Assertion Functions): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-7.html
- MDN, Error: cause: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Error/cause
- MDN, AggregateError: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/AggregateError
- MDN, The structured clone algorithm: https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm
- Node.js 문서, Errors (System errors, error.code): https://nodejs.org/api/errors.html
