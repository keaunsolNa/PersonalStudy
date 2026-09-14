Notion 원본: https://app.notion.com/p/3db5a06fd6d381beb89fe15b050b0cf5?pvs=204

# TypeScript using 선언과 Explicit Resource Management 및 Symbol.dispose 자원 수명 관리

> 2026-09-14 신규 주제 · 확장 대상: TypeScript 컴파일러 옵션·타입 시스템

## 학습 목표

- `using` / `await using` 선언의 스코프 종료 시점과 LIFO 해제 순서를 코드로 재현한다.
- `Symbol.dispose` / `Symbol.asyncDispose` 프로토콜을 직접 구현하고 `Disposable` / `AsyncDisposable` 로 타입을 노출한다.
- `DisposableStack.move()` 로 소유권을 이전해 부분 생성 실패에 안전한 팩토리를 작성한다.
- `lib` · 폴리필 · `importHelpers` 조합으로 ES2022 이하 타깃에서 다운레벨 컴파일을 구성한다.

## 1. try/finally 가 감당하지 못하는 정리 코드

자원 정리는 백엔드 코드에서 가장 자주 틀리는 부분이다. 파일 핸들, DB 커넥션, 락, 구독 취소, 임시 디렉터리 — 전부 "획득한 쪽이 반드시 반납해야 하는" 계약을 갖는데, JavaScript 는 이 계약을 언어 차원에서 표현할 방법이 없었다. 유일한 도구가 `try`/`finally` 인데, 자원이 하나일 때는 무난하지만 두 개 이상이 되는 순간 구조가 무너진다.

핵심 문제는 세 가지다. 첫째, **중첩 깊이가 자원 개수에 비례**한다. 자원 N 개를 올바르게 정리하려면 `try`/`finally` 를 N 단계 중첩해야 한다. 자원 3 개면 본문 코드가 들여쓰기 6 단계(중괄호 기준) 아래로 밀려 내려간다. 둘째, **에러 마스킹**이 발생한다. 하나의 `finally` 블록에서 두 자원을 연달아 닫을 때 첫 번째 `close()` 가 던지면 두 번째 `close()` 는 아예 실행되지 않고, 그렇다고 각각을 `try`/`catch` 로 감싸면 원래 본문에서 던진 에러가 정리 중 에러에 덮여 사라진다. 셋째, **조건부 획득**이 지저분하다. `let conn: Conn | undefined` 를 밖에 선언하고 `finally` 에서 `conn?.close()` 로 방어해야 하는데, 이 패턴은 타입 좁히기(narrowing)도 방해한다.

```ts
// 자원 3개 — 중첩 깊이가 그대로 3단계
async function reportLegacy(userId: string): Promise<Buffer> {
	const conn = await pool.connect();
	try {
		const tx = await conn.begin();
		try {
			const file = await fs.open("/tmp/report.bin", "w+");
			try {
				const rows = await tx.query("select * from orders where user_id = $1", [userId]);
				await file.write(encode(rows));
				await tx.commit();
				return await file.readFile();
			} finally {
				await file.close();      // (A) 여기서 던지면
			}
		} finally {
			await tx.rollback();         // (B) 이건 실행되지만 (A) 의 에러가 최종 에러
		}
	} finally {
		conn.release();                  // (C) 본문 에러는 (A) 에 의해 완전히 소실
	}
}
```

이 코드에서 본문이 `ErrorB` 를 던지고 `(A)` 가 `ErrorA` 를 던지면, 호출자가 보는 것은 `ErrorA` 뿐이다. 근본 원인인 `ErrorB` 는 스택 트레이스에서 사라진다. 장애 분석 시 "커넥션 닫다가 실패했다"는 로그만 남고 왜 실패했는지는 알 수 없는 상황이 바로 이것이다. TC39 의 Explicit Resource Management 제안(Stage 3, 챔피언 Ron Buckton)은 이 세 문제를 문법과 런타임 프로토콜로 한꺼번에 해결하며, TypeScript 5.2 가 이를 언어 기능으로 구현했다.

## 2. Symbol.dispose 프로토콜과 Disposable 인터페이스

제안의 출발점은 두 개의 well-known symbol 이다. `Symbol.dispose` 는 동기 해제 메서드의 이름이고, `Symbol.asyncDispose` 는 비동기 해제 메서드의 이름이다. 이름을 심볼로 고정했다는 점이 중요하다. 기존 생태계에는 `close()`, `dispose()`, `release()`, `destroy()`, `unsubscribe()`, `end()` 등 제각각인 정리 메서드가 흩어져 있었는데, 심볼이라는 충돌 불가능한 키를 표준으로 정하면 언어가 그 위에 문법을 얹을 수 있다.

TypeScript 는 이 프로토콜을 표현하는 전역 타입 `Disposable` 과 `AsyncDisposable` 을 `lib.esnext.disposable.d.ts` 에 정의해 둔다. 형태는 다음과 같다.

```ts
// lib.esnext.disposable.d.ts 가 제공하는 전역 타입 (개념적 형태)
interface Disposable {
	[Symbol.dispose](): void;
}

interface AsyncDisposable {
	[Symbol.asyncDispose](): PromiseLike<void>;
}
```

기존 클래스에 프로토콜을 붙이는 비용은 거의 없다. 이미 `close()` 가 있다면 심볼 메서드를 얇은 위임으로 추가하면 되고, 기존 호출부는 전혀 바뀌지 않는다. 아래는 커넥션 풀에서 빌려온 커넥션을 `Disposable` 로 노출하는 예다.

```ts
class PooledConnection implements Disposable {
	#released = false;

	constructor(private readonly raw: RawConn, private readonly pool: Pool) {}

	query<T>(sql: string, params: unknown[] = []): Promise<T[]> {
		if (this.#released) {
			throw new Error("released connection reused");
		}
		return this.raw.query<T>(sql, params);
	}

	release(): void {
		if (this.#released) {
			return;           // 해제는 반드시 멱등이어야 한다
		}
		this.#released = true;
		this.pool.give(this.raw);
	}

	[Symbol.dispose](): void {
		this.release();
	}
}
```

해제 메서드를 설계할 때 지켜야 할 규칙이 두 가지 있다. **멱등성**과 **무예외 지향**이다. 멱등성이 필요한 이유는 사용자가 `using` 으로 자동 해제를 걸어둔 상태에서 명시적으로 `release()` 를 한 번 더 호출할 수 있기 때문이다. 위 코드처럼 플래그로 가드하면 이중 반납으로 풀이 오염되는 사고를 막는다. 무예외 지향은, 해제 실패가 본문 에러를 덮어쓰는 사고(1 절의 `ErrorA` 문제)를 줄이기 위해서다. 완전히 무시하라는 뜻은 아니고, "이미 실패한 자원을 닫다가 나는 부수적 에러"는 로깅만 하고 삼키는 편이 대체로 낫다는 의미다.

## 3. using / await using 선언과 LIFO 해제 규칙

`using` 은 `const` 와 비슷한 고정 바인딩을 만들되, **스코프를 벗어나는 모든 경로**에서 `[Symbol.dispose]()` 를 호출한다. 정상 종료, `return`, `break`, `continue`, `throw` 를 전부 포함한다. 해제 순서는 스택과 같은 **선입후출(LIFO)** 이며, 이는 자원 간 의존성을 자연스럽게 보장한다. 트랜잭션이 커넥션 위에 얹혀 있다면 트랜잭션을 나중에 선언했으므로 먼저 해제되고, 커넥션은 그 뒤에 반납된다 — 정확히 우리가 원하는 순서다.

```ts
function loggy(id: string): Disposable {
	console.log(`open ${id}`);
	return {
		[Symbol.dispose]() {
			console.log(`close ${id}`);
		},
	};
}

function run(): void {
	using a = loggy("a");
	using b = loggy("b");
	{
		using c = loggy("c");
	}                      // 여기서 c 해제
	using d = loggy("d");
	return;
	using e = loggy("e");  // 도달 불가 — 생성도 해제도 되지 않는다
}

run();
// open a / open b / open c / close c / open d / close d / close b / close a
```

`await using` 은 `[Symbol.asyncDispose]()` 를 찾아 호출하고 그 결과를 `await` 한다. `Symbol.asyncDispose` 가 없으면 `Symbol.dispose` 로 폴백하므로, 동기 자원과 비동기 자원을 한 블록에서 섞어 써도 된다. 단 `await using` 은 `await` 가 허용되는 문맥(async 함수, async 제너레이터, top-level await 가 가능한 모듈)에서만 쓸 수 있다.

문법상의 제약도 몇 가지 알아둘 필요가 있다. `using` 선언은 초기화식을 반드시 가져야 하고, 구조 분해 패턴(`using { a, b } = ...`)은 허용되지 않는다. 바인딩은 재할당할 수 없다. 초기값이 `null` 또는 `undefined` 인 것은 **허용되며 해제 단계에서 조용히 건너뛴다**. 이 규칙 덕분에 조건부 획득이 아주 깔끔해진다.

```ts
async function fetchWithOptionalCache(key: string, useCache: boolean) {
	// useCache 가 false 면 아무 것도 획득하지 않고, 해제도 시도하지 않는다
	using cache = useCache ? cachePool.acquire() : null;
	const hit = cache?.get(key);
	if (hit !== undefined) {
		return hit;
	}
	const fresh = await origin.load(key);
	cache?.set(key, fresh);
	return fresh;
}
```

`for-of` 루프의 선언부에도 쓸 수 있고, 이때는 **반복 1 회마다** 해제가 일어난다. 순회 방식과 해제 방식의 조합은 제안서에 다음과 같이 정리되어 있다.

| 문법 | 순회에 사용하는 프로토콜 | 해제에 사용하는 프로토콜 |
| --- | --- | --- |
| `for (using x of y)` | `Symbol.iterator` | `Symbol.dispose` |
| `for (await using x of y)` | `Symbol.iterator` | `Symbol.asyncDispose` → `Symbol.dispose` |
| `for await (using x of y)` | `Symbol.asyncIterator` → `Symbol.iterator` | `Symbol.dispose` |
| `for await (await using x of y)` | `Symbol.asyncIterator` → `Symbol.iterator` | `Symbol.asyncDispose` → `Symbol.dispose` |

## 4. 예외 전파와 SuppressedError 체이닝

`using` 은 예외에 대해 두 방향의 보장을 한다. 본문이 던지면 먼저 해제를 수행한 뒤 그 에러를 다시 던지고, 해제가 던지면 그 에러도 그대로 전파된다. 문제는 **둘 다 던지는 경우**인데, 여기서 제안이 도입한 것이 `SuppressedError` 라는 새로운 `Error` 서브타입이다.

`SuppressedError` 는 두 개의 필드를 갖는다. `error` 는 가장 최근에 던져진 에러(= 해제 중 발생한 에러)이고, `suppressed` 는 그것에 의해 억제된 직전 에러(= 본문에서 던진 에러)다. 즉 원인 체인이 소실되지 않는다. 자원이 여러 개라면 `SuppressedError` 가 중첩되어, 가장 바깥이 마지막 해제 에러이고 안쪽으로 갈수록 과거의 에러가 쌓인다.

```ts
class DisposeFailure extends Error { name = "DisposeFailure"; }
class BodyFailure extends Error { name = "BodyFailure"; }

function throwyResource(id: string): Disposable {
	return {
		[Symbol.dispose]() {
			throw new DisposeFailure(`dispose failed: ${id}`);
		},
	};
}

function boom(): void {
	using r = throwyResource("r1");
	throw new BodyFailure("business logic failed");
}

try {
	boom();
} catch (e) {
	const err = e as SuppressedError;
	console.log(err.name);            // "SuppressedError"
	console.log(err.error.name);      // "DisposeFailure"  ← 해제 중 에러
	console.log(err.suppressed.name); // "BodyFailure"     ← 억제된 원래 원인
}
```

운영 관점에서 이 구조는 로깅 코드를 한 번 손봐야 한다는 뜻이다. 대부분의 로거는 `error.cause` 체인만 따라가지 `SuppressedError.suppressed` 는 모른다. 아래처럼 평탄화 헬퍼를 하나 만들어 두면 Sentry·Datadog 같은 도구에 원인 전체를 실어 보낼 수 있다.

```ts
function flattenErrorChain(e: unknown, out: unknown[] = []): unknown[] {
	out.push(e);
	if (e instanceof SuppressedError) {
		flattenErrorChain(e.error, out);
		flattenErrorChain(e.suppressed, out);
	} else if (e instanceof Error && e.cause !== undefined) {
		flattenErrorChain(e.cause, out);
	}
	return out;
}

// 자원 3개가 모두 해제에 실패하면 체인 길이는 본문 에러 1 + 해제 에러 3 = 4
logger.error({ chain: flattenErrorChain(caught).map(describe) }, "request failed");
```

`try`/`finally` 와의 차이를 표로 정리하면 다음과 같다.

| 항목 | try/finally | using 선언 |
| --- | --- | --- |
| 자원 N개일 때 중첩 깊이 | N 단계 | 1 단계(평탄) |
| 해제 호출 지점 | 개발자가 직접 작성 | 스코프 이탈 경로 전부 자동 |
| 본문 에러 + 해제 에러 | 나중 에러가 앞 에러를 덮어씀 | `SuppressedError` 로 양쪽 보존 |
| 조건부 획득 | 외부 `let` + 옵셔널 체이닝 필요 | `using x = cond ? get() : null` |
| 해제 순서 보장 | 작성 순서에 의존(실수 가능) | 선언 역순 LIFO 고정 |
| 런타임 요구사항 | 없음 | 심볼 폴리필 또는 네이티브 지원 |

## 5. DisposableStack 으로 임의 정리 로직 묶기

모든 정리 대상이 클래스일 필요는 없다. 콜백 하나만 나중에 실행하면 되는 경우까지 `Disposable` 클래스를 새로 만드는 것은 과한 추상화다. 이를 위해 제안은 `DisposableStack` 과 `AsyncDisposableStack` 이라는 컨테이너를 제공한다. 이들 자신이 `Disposable` / `AsyncDisposable` 이므로 `using` 변수에 바로 담을 수 있다.

API 는 네 개의 등록 메서드와 한 개의 이전 메서드로 구성된다.

| 메서드 | 용도 | 반환 |
| --- | --- | --- |
| `use(value)` | 이미 `Disposable` 인 값을 스택에 올린다. `null`/`undefined` 는 무시 | 전달한 값 그대로 |
| `adopt(value, onDispose)` | `Disposable` 이 아닌 값 + 정리 콜백을 함께 등록 | 전달한 값 그대로 |
| `defer(onDispose)` | 값 없이 정리 콜백만 등록 | `void` |
| `move()` | 스택의 모든 자원을 **새 스택으로 이전**하고 원본은 비운다 | 새 `DisposableStack` |
| `dispose()` | `[Symbol.dispose]()` 의 별칭 | `void` |

`disposed` 게터로 이미 해제되었는지 확인할 수 있고, 해제 순서는 역시 LIFO 다. 그래서 **자원을 만든 직후 곧바로 등록**하는 것이 규칙이다. 그래야 등록 순서가 생성 순서와 일치하고, 해제가 의존성 역순으로 이뤄진다.

```ts
import { setTimeout as delay } from "node:timers/promises";

async function processBatch(ids: readonly string[]): Promise<Result[]> {
	await using stack = new AsyncDisposableStack();

	const conn = stack.use(await pool.acquire());          // Disposable 이므로 use
	const controller = stack.adopt(new AbortController(),  // 표준 API 는 adopt 로 감싼다
		(c) => c.abort("scope exit"));
	const startedAt = Date.now();
	stack.defer(() => metrics.timing("batch.duration", Date.now() - startedAt));

	const timer = setTimeout(() => controller.abort("timeout"), 30_000);
	stack.defer(() => clearTimeout(timer));

	const results: Result[] = [];
	for (const id of ids) {
		results.push(await handle(conn, id, controller.signal));
		await delay(10);
	}
	return results;
	// 역순 해제: clearTimeout → metrics → abort → pool 반납
}
```

`AbortController` 가 좋은 예다. 표준 `AbortController` 에는 `Symbol.dispose` 가 없기 때문에 `using` 에 직접 담을 수 없지만, `adopt` 로 정리 콜백을 붙이면 스코프 종료 시 자동으로 `abort()` 가 호출된다. 이 패턴은 "요청 취소를 잊어 리스너가 누적되는" 누수를 구조적으로 차단한다. 스택에 등록된 자원 중 여러 개가 해제에 실패하면 앞 절과 동일하게 `SuppressedError` 가 중첩되어 던져진다.

## 6. move() 와 소유권 이전 — 부분 생성 실패에 안전한 팩토리

`move()` 는 처음 보면 용도를 짐작하기 어렵지만, 자원 여러 개를 조합해 하나의 객체를 만드는 팩토리에서 결정적인 역할을 한다. 문제 상황은 이렇다. 커넥션과 파일 핸들과 캐시를 모두 획득해 `Session` 객체를 만든다고 하자. 세 번째 획득이 실패하면 앞의 두 개를 반드시 되돌려야 하지만, 전부 성공하면 **아무것도 해제하면 안 된다**. 반환된 `Session` 이 소유권을 가져가기 때문이다.

`move()` 는 정확히 이 "성공 시 정리 취소(commit)" 를 표현한다. 함수 시작부에서 `using stack` 을 열고 자원을 차례로 등록해 두면 중간에 무슨 일이 생기든 안전하고, 마지막 줄에서 `stack.move()` 를 호출하면 등록분이 새 스택으로 옮겨가므로 원래 스택은 비어 있어 스코프 종료 시 아무 일도 하지 않는다.

```ts
class Session implements Disposable {
	private constructor(
		readonly conn: PooledConnection,
		readonly journal: FileHandleLike,
		private readonly owned: DisposableStack,
	) {}

	static create(pool: Pool, path: string): Session {
		using scratch = new DisposableStack();

		const conn = scratch.use(pool.acquire());
		const journal = scratch.adopt(openJournal(path), (j) => j.closeSync());
		scratch.defer(() => metrics.decrement("session.open"));
		metrics.increment("session.open");

		validate(conn, journal);   // 여기서 던지면 journal → conn 순으로 자동 롤백

		// 전부 성공 — 소유권을 Session 으로 이전하고 scratch 는 빈 껍데기가 된다
		return new Session(conn, journal, scratch.move());
	}

	[Symbol.dispose](): void {
		this.owned.dispose();
	}
}

// 호출부
function useSession(pool: Pool): void {
	using session = Session.create(pool, "/var/log/app.journal");
	session.conn.query("select 1");
}   // session[Symbol.dispose]() → owned 스택 LIFO 해제
```

이 구조의 실질적 이득은 **획득 단계마다 롤백 코드를 중복 작성할 필요가 없다**는 점이다. 자원 N 개를 수동으로 처리하면 실패 지점마다 되돌릴 자원 수가 달라 최악의 경우 N(N−1)/2 줄의 정리 코드가 필요하지만, 스택을 쓰면 등록 N 줄과 `move()` 1 줄로 끝난다. 자원 4 개 기준으로 6 줄 대 1 줄이다.

## 7. 다운레벨 컴파일 — lib, 폴리필, tslib 헬퍼

`using` 은 문법이므로 TypeScript 가 변환할 수 있지만, `Symbol.dispose` 같은 런타임 값은 변환으로 만들어낼 수 없다. 그래서 설정이 두 층으로 나뉜다. **타입 층**은 `lib` 로 해결하고, **런타임 층**은 폴리필로 해결한다.

타입 층부터 보면, `target` 을 `es2022` 이하로 두고 `lib` 에 `"esnext"` 또는 `"esnext.disposable"` 을 추가해야 한다. 이 라이브러리 파일이 `Disposable`, `AsyncDisposable`, `DisposableStack`, `AsyncDisposableStack`, `SuppressedError` 선언과 `SymbolConstructor` 확장을 제공한다.

```json
{
	"compilerOptions": {
		"target": "es2022",
		"lib": ["es2022", "esnext.disposable", "dom"],
		"module": "nodenext",
		"moduleResolution": "nodenext",
		"importHelpers": true,
		"strict": true
	}
}
```

런타임 층은 엔트리 포인트에서 심볼만 채워주면 대부분 동작한다. `using` / `await using` 문법만 쓸 것이라면 아래 두 줄로 충분하고, `DisposableStack` 이나 `SuppressedError` 를 실제로 사용한다면 그것들까지 폴리필해야 한다.

```ts
// polyfill.ts — 앱 진입점에서 가장 먼저 import
(Symbol as any).dispose ??= Symbol("Symbol.dispose");
(Symbol as any).asyncDispose ??= Symbol("Symbol.asyncDispose");
```

컴파일 결과물은 어떤 모습일까. TypeScript 는 각 스코프마다 `{ stack: [], error: void 0, hasError: false }` 형태의 환경 객체를 만들고, 자원 등록은 `__addDisposableResource(env, value, async)` 로, 해제는 `finally` 절의 `__disposeResources(env)` 로 변환한다. tslib 의 선언을 보면 계약이 명확하다.

```ts
// tslib.d.ts 에 선언된 실제 시그니처
export declare function __addDisposableResource<T>(
	env: { stack: { value?: unknown; dispose?: Function; async: boolean }[]; error: unknown; hasError: boolean },
	value: T,
	async: boolean,
): T;

export declare function __disposeResources(
	env: { stack: { value?: unknown; dispose?: Function; async: boolean }[]; error: unknown; hasError: boolean },
): any;
```

`__addDisposableResource` 는 값이 객체가 아니거나 적절한 심볼 메서드가 없으면 `TypeError` 를 던지고, `__disposeResources` 는 해제 중 에러가 이전 에러를 억제하는 상황에서 `SuppressedError` 를 던진다. 비동기 자원이 하나라도 등록되어 있으면 `Promise` 를 반환하고 그렇지 않으면 `void` 를 반환한다 — `await using` 이 `await` 문맥을 요구하는 이유가 여기에 있다.

`importHelpers` 를 켜지 않으면 이 두 헬퍼가 **파일마다 통째로 인라인**된다. 자원 관리를 쓰는 모듈이 50 개면 동일한 헬퍼 구현이 50 벌 번들에 들어간다. `importHelpers: true` 로 두고 `tslib` 를 런타임 의존성에 넣으면 헬퍼는 한 벌만 남고 각 파일에는 import 한 줄만 추가된다. 라이브러리를 배포한다면 `tslib` 를 `dependencies` 에 넣어야 하고, 애플리케이션이라면 번들러의 중복 제거에 맡겨도 무방하다. 반대로 `target` 을 `esnext` 로 두고 네이티브 지원 런타임(최신 V8 계열)만 대상으로 한다면 헬퍼 없이 원문법이 그대로 출력된다.

## 8. 실무 적용 — 트랜잭션, 파일 핸들, 테스트 픽스처

가장 효과가 큰 곳은 트랜잭션 경계다. Spring 의 `@Transactional` 에 익숙하다면 `await using` 은 "프록시 없는 선언적 트랜잭션"에 가깝다. 커밋되지 않은 채 스코프를 벗어나면 무조건 롤백되도록 만들면, 예외 경로든 조기 `return` 이든 누락이 생길 수 없다.

```ts
interface Tx extends AsyncDisposable {
	query<T>(sql: string, params?: unknown[]): Promise<T[]>;
	commit(): Promise<void>;
}

async function beginTx(pool: Pool): Promise<Tx> {
	const conn = await pool.acquire();
	await conn.query("begin");
	let settled = false;
	return {
		query: (sql, params) => conn.query(sql, params),
		async commit() {
			await conn.query("commit");
			settled = true;
		},
		async [Symbol.asyncDispose]() {
			try {
				if (!settled) {
					await conn.query("rollback");   // 커밋 누락 = 자동 롤백
				}
			} finally {
				conn.release();                     // 반납은 반드시 수행
			}
		},
	};
}

export async function transferPoints(from: string, to: string, amount: number): Promise<void> {
	await using tx = await beginTx(pool);
	const [sender] = await tx.query<Account>("select * from account where id = $1 for update", [from]);
	if (sender.points < amount) {
		return;                                     // 롤백 후 커넥션 반납
	}
	await tx.query("update account set points = points - $1 where id = $2", [amount, from]);
	await tx.query("update account set points = points + $1 where id = $2", [amount, to]);
	await tx.commit();
}
```

Node.js 는 이미 표준 API 에 프로토콜을 심어 두었다. `fs.promises.open()` 이 반환하는 `FileHandle` 은 `filehandle[Symbol.asyncDispose]()` 를 가지며, 이는 `filehandle.close()` 를 호출하고 닫힘이 완료되면 이행되는 프로미스를 반환한다. 덕분에 `await using` 만 붙이면 스코프 종료 시 파일이 자동으로 닫힌다. 임시 디렉터리에는 `fsPromises.mkdtempDisposable()` 이 있어, 반환 객체의 `path` 로 경로를 얻고 `Symbol.asyncDispose`(= `remove`)로 디렉터리째 정리된다.

```ts
import { open, mkdtempDisposable } from "node:fs/promises";
import path from "node:path";

export async function exportCsv(rows: readonly Row[]): Promise<Buffer> {
	await using dir = await mkdtempDisposable("export-");   // 스코프 종료 시 디렉터리 삭제
	await using fh = await open(path.join(dir.path, "out.csv"), "w+");

	for (const row of rows) {
		await fh.write(toCsvLine(row));
	}
	return await fh.readFile();
	// 역순: 파일 close → 임시 디렉터리 remove
}
```

테스트 픽스처도 `using` 과 궁합이 좋다. `beforeEach`/`afterEach` 쌍은 등록 순서와 정리 순서가 분리되어 있어, 테스트가 중간에 실패하면 정리가 건너뛰어지거나 순서가 꼬이기 쉽다. 픽스처 생성 함수가 `AsyncDisposable` 을 반환하게 만들면 획득과 반납이 한 줄로 묶이고, 어떤 assertion 에서 실패하더라도 정리는 보장된다.

```ts
async function withTestDb(): Promise<{ url: string } & AsyncDisposable> {
	const container = await startPostgresContainer();
	await migrate(container.url);
	return {
		url: container.url,
		async [Symbol.asyncDispose]() {
			await container.stop();
		},
	};
}

it("주문을 생성하면 재고가 차감된다", async () => {
	await using db = await withTestDb();
	const repo = new OrderRepository(db.url);

	await repo.place({ sku: "A-1", qty: 3 });

	expect(await repo.stockOf("A-1")).toBe(7);   // 실패해도 컨테이너는 정리된다
});
```

## 9. using 이 해결하지 못하는 것

`using` 은 만능이 아니다. 적용 범위가 **렉시컬 블록 스코프**로 못 박혀 있기 때문에, 수명이 블록과 일치하지 않는 자원에는 쓸 수 없다. 대표적인 세 가지 경우가 있다.

첫째, **객체 필드로 보관하는 자원**이다. 서버 기동 시 커넥션 풀을 만들어 애플리케이션 종료까지 유지하는 경우, 자원의 수명은 함수 호출이 아니라 프로세스 수명에 묶인다. 이럴 때는 소유자 객체 자신을 `Disposable` 로 만들고 해제 책임을 상위로 위임하는 수밖에 없다 — 6 절의 `Session` 이 바로 그 패턴이다. 최상단에서는 결국 셧다운 훅이 필요하다.

```ts
class AppContext implements AsyncDisposable {
	private readonly owned = new AsyncDisposableStack();

	constructor(readonly pool: Pool, readonly broker: Broker) {
		this.owned.use(pool);
		this.owned.use(broker);
	}

	async [Symbol.asyncDispose]() {
		await this.owned.disposeAsync();
	}
}

const ctx = new AppContext(await createPool(), await createBroker());
process.once("SIGTERM", () => void ctx[Symbol.asyncDispose]());
// using 으로 감쌀 수 없다 — 수명이 블록이 아니라 프로세스에 묶여 있다
```

둘째, **호출자에게 반환되는 자원**이다. 함수가 자원을 만들어 돌려주는 순간 그 자원은 함수 스코프보다 오래 살아야 하므로 `using` 을 쓸 수 없다. 이때 필요한 것이 `move()` 이며, 소유권이 반환값으로 넘어간다는 사실을 타입으로 드러내려면 반환 타입에 `Disposable` 을 포함시켜 호출자가 `using` 을 붙이도록 유도하는 것이 좋다.

셋째, **GC 기반 정리와의 혼동**이다. `FinalizationRegistry` 는 객체가 수거될 때 콜백을 실행하지만, 명세는 콜백 호출을 **보장하지 않는다**. 프로세스가 종료되거나 GC 가 해당 객체를 끝내 수거하지 않으면 콜백은 영영 실행되지 않고, 실행되더라도 시점을 예측할 수 없다. 따라서 파일 디스크립터나 DB 커넥션처럼 반납이 늦으면 시스템 한계(fd 상한, 풀 고갈)에 부딪히는 자원의 1차 방어선으로 써서는 안 된다. `using` 은 결정론적이고 즉시적이며, `FinalizationRegistry` 는 비결정론적이고 최선 노력(best-effort)이다. 둘의 올바른 조합은 "`using` 으로 정상 경로를 보장하고, `FinalizationRegistry` 는 누수를 **감지해 경고**하는 용도로만 쓰는 것"이다.

```ts
const leakWatch = new FinalizationRegistry<string>((tag) => {
	// 반납되지 않은 채 수거된 핸들 — 정리가 아니라 '탐지'에만 쓴다
	logger.warn({ tag }, "resource was garbage collected without dispose");
});

class TrackedHandle implements Disposable {
	#disposed = false;
	constructor(private readonly fd: number, tag: string) {
		leakWatch.register(this, tag, this);
	}
	[Symbol.dispose](): void {
		if (this.#disposed) return;
		this.#disposed = true;
		leakWatch.unregister(this);   // 정상 해제면 경고 대상에서 제외
		closeSync(this.fd);
	}
}
```

마지막으로 도입 전략을 정리하면, 신규 코드부터 `using` 을 적용하고 기존 클래스에는 `[Symbol.dispose]()` 를 얇은 위임으로 추가하는 방식이 비용 대비 효과가 가장 크다. 기존 `close()` 호출부는 그대로 두어도 되고, 해제가 멱등하다면 두 방식이 공존해도 안전하다. 런타임이 심볼을 네이티브로 지원하지 않는 환경이라면 `lib` 설정과 폴리필 import 를 CI 에서 검증하는 편이 낫다 — 폴리필 누락은 컴파일이 아니라 런타임에 `TypeError` 로 드러나기 때문이다.

## 참고

- [TypeScript 5.2 Release Notes — using Declarations and Explicit Resource Management](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html)
- [TC39 proposal-explicit-resource-management](https://github.com/tc39/proposal-explicit-resource-management)
- [ECMAScript Explicit Resource Management Specification Draft](https://tc39.es/proposal-explicit-resource-management/)
- [microsoft/TypeScript PR #54505 — Explicit Resource Management 구현](https://github.com/microsoft/TypeScript/pull/54505)
- [microsoft/tslib](https://github.com/microsoft/tslib)
- [TSConfig Reference](https://www.typescriptlang.org/tsconfig/)
- [Node.js fs — filehandle[Symbol.asyncDispose]()](https://nodejs.org/api/fs.html#filehandlesymbolasyncdispose)
- [MDN — using declaration](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Statements/using)
- [MDN — DisposableStack](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/DisposableStack)
- [MDN — FinalizationRegistry](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/FinalizationRegistry)
