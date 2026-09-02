Notion 원본: https://www.notion.so/3cf5a06fd6d381899e78e0561cbbdeb7

# TypeScript 명시적 자원 관리 using과 Symbol dispose 및 DisposableStack 설계

> 2026-09-02 신규 주제 · 확장 대상: TypeScript Stage 3 데코레이터, Effect 런타임 리소스 관리

## 학습 목표

- `using` / `await using` 선언이 디슈거링되는 코드와 폐기 순서를 추적한다
- `Disposable` / `AsyncDisposable` 인터페이스를 타입 안전하게 구현한다
- `DisposableStack` 과 `adopt` / `move` 로 부분 초기화 실패를 처리한다
- `SuppressedError` 로 폐기 중 예외와 본문 예외를 함께 보존한다

## 1. try/finally 가 감당하지 못하는 지점

TypeScript 로 서버 코드를 쓰다 보면 자원 해제 코드가 빠르게 지저분해진다. DB 커넥션, 파일 핸들, 락, 임시 디렉터리, 구독 해제, 트레이싱 스팬이 각각 `finally` 를 요구한다.

```typescript
async function migrate(pool: Pool, path: string): Promise<void> {
	const conn = await pool.connect();
	try {
		const handle = await fs.open(path, 'r');
		try {
			const lock = await acquireLock(conn, 'migration');
			try {
				await runStatements(conn, handle);
			} finally {
				await lock.release();
			}
		} finally {
			await handle.close();
		}
	} finally {
		conn.release();
	}
}
```

문제는 중첩만이 아니다. `lock.release()` 가 던지면 `handle.close()` 는 실행되지만 원래 본문 예외가 락 해제 예외로 덮인다. 어떤 예외가 근본 원인인지 로그에서 사라진다. 또 자원을 조건부로 획득하면 `let conn: Connection | undefined` 를 두고 `finally` 에서 `conn?.release()` 를 해야 해서 타입이 옵셔널로 오염된다.

C# 의 `using`, Python 의 `with`, Java 의 try-with-resources 가 해결한 문제다. TypeScript 5.2 부터 ECMAScript Stage 3 "Explicit Resource Management" 제안을 구현해 같은 도구를 제공한다.

## 2. `using` 선언의 디슈거링

```typescript
{
	using res = getResource();
	doWork(res);
}
```

이것은 대략 다음과 같이 변환된다.

```typescript
{
	const res = getResource();
	const dispose = res?.[Symbol.dispose]?.bind(res);
	try {
		doWork(res);
	}
	finally {
		dispose?.();
	}
}
```

정확한 의미론은 몇 가지 규칙을 더 포함한다.

- 폐기는 선언의 **역순** 이다. 스택처럼 나중에 선언한 것부터 해제된다.
- `null` 과 `undefined` 는 허용되고 폐기 시 무시된다. 조건부 자원에 유용하다.
- 그 외의 값에 `[Symbol.dispose]` 가 없으면 **선언 시점에** `TypeError` 를 던진다. 블록 끝까지 가서 실패하지 않는다.
- `using` 은 `const` 처럼 재할당이 금지된다.
- 블록 스코프 전용이다. 최상위 모듈 스코프에서는 쓸 수 없고(모듈은 언제 폐기되는지 정의되지 않음), 클래스 필드나 for-in 에도 쓸 수 없다.

앞의 예제를 다시 쓰면 이렇게 된다.

```typescript
async function migrate(pool: Pool, path: string): Promise<void> {
	await using conn = await connect(pool);
	await using handle = await openFile(path, 'r');
	await using lock = await acquireLock(conn, 'migration');

	await runStatements(conn, handle);
}
```

중첩이 사라지고, 해제 순서는 lock → handle → conn 으로 자동 보장된다.

`await using` 은 `[Symbol.asyncDispose]` 를 찾아 그 결과를 `await` 한다. 폐기 대상에 `Symbol.asyncDispose` 가 없고 `Symbol.dispose` 만 있으면 동기 폐기로 폴백한다. 반대로 `using`(동기)에 `Symbol.asyncDispose` 만 있는 객체를 넣으면 `TypeError` 다 — 비동기 정리를 동기 컨텍스트에서 기다릴 방법이 없기 때문이다.

## 3. 런타임 준비와 폴리필

`Symbol.dispose` 와 `Symbol.asyncDispose` 는 well-known symbol 이지만 런타임 지원이 필요하다. Node.js 는 20 부터 제공한다. 없는 환경에서는 폴리필한다.

```typescript
// polyfill.ts — 엔트리포인트에서 가장 먼저 import
(Symbol as { dispose?: symbol }).dispose ??= Symbol.for('Symbol.dispose');
(Symbol as { asyncDispose?: symbol }).asyncDispose ??= Symbol.for('Symbol.asyncDispose');
```

타입 쪽은 `tsconfig.json` 의 `lib` 설정으로 켜다.

```json
{
	"compilerOptions": {
		"target": "ES2022",
		"lib": ["ES2022", "ESNext.Disposable", "DOM"],
		"strict": true
	}
}
```

`target` 이 `ES2022` 이하면 TypeScript 가 `using` 을 try/finally 로 다운레벨 컴파일한다. `ESNext` 로 두면 네이티브 문법을 그대로 내보내므로 런타임이 지원해야 한다. 라이브러리를 배포한다면 다운레벨 쪽이 안전하다.

`ESNext.Disposable` 을 넣지 않으면 `Symbol.dispose` 타입이 없어 `Disposable` 인터페이스를 쓸 수 없다. 이 lib 에는 `Disposable`, `AsyncDisposable`, `DisposableStack`, `AsyncDisposableStack`, `SuppressedError` 타입이 들어 있다.

## 4. Disposable 구현 — 타입 안전한 래퍼

기존 라이브러리 객체는 `Symbol.dispose` 를 갖고 있지 않다. 얇은 래퍼를 만든다.

```typescript
import type { Pool, PoolClient } from 'pg';

export class ManagedConnection implements AsyncDisposable {
	private constructor(readonly client: PoolClient) {}

	static async acquire(pool: Pool): Promise<ManagedConnection> {
		return new ManagedConnection(await pool.connect());
	}

	async [Symbol.asyncDispose](): Promise<void> {
		this.client.release();
	}
}
```

트랜잭션처럼 "성공하면 커밋, 실패하면 롤백" 이 필요한 자원은 폐기 시점에 상태를 봐야 한다.

```typescript
export class Transaction implements AsyncDisposable {
	private settled = false;

	private constructor(private readonly client: PoolClient) {}

	static async begin(client: PoolClient): Promise<Transaction> {
		await client.query('BEGIN');
		return new Transaction(client);
	}

	async commit(): Promise<void> {
		if (this.settled) {
			throw new Error('transaction already settled');
		}
		this.settled = true;
		await this.client.query('COMMIT');
	}

	async [Symbol.asyncDispose](): Promise<void> {
		if (this.settled) {
			return;
		}
		this.settled = true;
		await this.client.query('ROLLBACK');
	}
}
```

사용부는 다음과 같다.

```typescript
async function transfer(pool: Pool, from: string, to: string, amount: bigint): Promise<void> {
	await using conn = await ManagedConnection.acquire(pool);
	await using tx = await Transaction.begin(conn.client);

	await conn.client.query('UPDATE accounts SET balance = balance - $1 WHERE id = $2', [amount, from]);
	await conn.client.query('UPDATE accounts SET balance = balance + $1 WHERE id = $2', [amount, to]);

	await tx.commit();
}
```

`tx.commit()` 에 도달하지 못하면 — 예외든 조기 `return` 이든 — 폐기가 자동으로 롤백한다. `settled` 플래그로 커밋 후 롤백을 막는다. 이 "커밋하지 않으면 롤백" 관용구는 Rust 의 RAII 가드와 같은 패턴이고, `try/finally` 로 쓰면 항상 플래그 변수와 조건문이 본문에 섞여 들어간다.

간단한 자원은 `Disposable` 팩토리 헬퍼로 줄일 수 있다.

```typescript
export function disposable<T>(value: T, onDispose: (value: T) => void): T & Disposable {
	return Object.assign(value as T & Disposable, {
		[Symbol.dispose]() {
			onDispose(value);
		},
	});
}

// 사용
using span = disposable(tracer.startSpan('migrate'), (s) => s.end());
```

`T & Disposable` 반환 타입 덕분에 호출부에서 원래 메서드가 그대로 보인다. 다만 `Object.assign` 이 원본 객체를 변형하므로, 라이브러리가 반환한 공유 인스턴스에는 쓰지 않는다.

## 5. DisposableStack — 동적 자원과 부분 실패

자원 개수가 컴파일 타임에 고정되지 않으면 `using` 선언만으로는 부족하다. 배열을 순회하며 파일을 여는 경우가 그렇다. `DisposableStack` 이 이 역할을 한다.

```typescript
async function mergeShards(paths: readonly string[]): Promise<Buffer> {
	await using stack = new AsyncDisposableStack();

	const handles: FileHandle[] = [];
	for (const path of paths) {
		const handle = await fs.open(path, 'r');
		stack.defer(() => handle.close());
		handles.push(handle);
	}

	return concat(await Promise.all(handles.map((h) => h.readFile())));
}
```

`stack` 자체가 `AsyncDisposable` 이므로 `await using` 으로 선언하면 블록 끝에서 등록된 모든 정리 함수를 역순으로 실행한다. 세 가지 등록 메서드가 있다.

| 메서드 | 인자 | 반환 | 용도 |
|---|---|---|---|
| `use(value)` | `Disposable` 구현체 | 그 값 | 이미 폐기 가능한 객체 |
| `adopt(value, fn)` | 임의 값 + 정리 함수 | 그 값 | 폐기 프로토콜 없는 외부 객체 |
| `defer(fn)` | 정리 함수만 | `void` | 자원과 무관한 정리 작업 |

`adopt` 는 서드파티 객체를 감쏸 때 래퍼 클래스 없이 쓸 수 있어 편하다.

```typescript
using stack = new DisposableStack();
const watcher = stack.adopt(chokidar.watch(dir), (w) => void w.close());
const timer = stack.adopt(setInterval(tick, 1000), clearInterval);
```

### 부분 초기화 실패 — `move()` 의 존재 이유

가장 중요한 활용은 생성자에서 여러 자원을 잡을 때다. 세 번째 자원 획득이 실패하면 앞의 두 개를 해제해야 하는데, 성공하면 해제하면 안 된다.

```typescript
export class ReplicationSession implements AsyncDisposable {
	#stack: AsyncDisposableStack;

	private constructor(
		stack: AsyncDisposableStack,
		readonly source: ManagedConnection,
		readonly target: ManagedConnection,
		readonly slot: ReplicationSlot,
	) {
		this.#stack = stack;
	}

	static async open(sourcePool: Pool, targetPool: Pool, slotName: string): Promise<ReplicationSession> {
		await using scope = new AsyncDisposableStack();

		const source = scope.use(await ManagedConnection.acquire(sourcePool));
		const target = scope.use(await ManagedConnection.acquire(targetPool));
		const slot = scope.use(await ReplicationSlot.create(source.client, slotName));
		// 여기까지 도중 어디서 던져도 scope 가 획득분만 역순 해제한다

		return new ReplicationSession(scope.move(), source, target, slot);
	}

	async [Symbol.asyncDispose](): Promise<void> {
		await this.#stack.disposeAsync();
	}
}
```

`scope.move()` 는 등록된 항목 전부를 새 스택으로 옮기고 원본을 폐기됨 상태로 만든다. 따라서 `await using scope` 가 블록 끝에서 폐기를 시도해도 아무 일도 일어나지 않는다. 소유권이 반환된 `ReplicationSession` 으로 이전된 것이다. 이 관용구가 없으면 "성공 여부 플래그 + finally 에서 조건부 해제" 를 손으로 써야 하고, 자원이 늘어날수록 실수하기 쉽다.

`move()` 이후 원본 스택에 `use` 를 호출하면 `ReferenceError` 가 난다. 스택은 일회용이다.

## 6. SuppressedError — 예외 중첩 보존

폐기 중 예외가 나면 정보가 사라지는 문제를 이 제안이 명시적으로 해결한다.

```typescript
class Noisy implements Disposable {
	constructor(private readonly name: string) {}
	[Symbol.dispose]() {
		throw new Error(`dispose failed: ${this.name}`);
	}
}

function run() {
	using a = new Noisy('a');
	using b = new Noisy('b');
	throw new Error('body failed');
}
```

`run()` 이 던지는 것은 `SuppressedError` 다. 구조는 다음과 같다.

```
SuppressedError {
  error:      Error("dispose failed: a")     // 나중에 발생한 예외
  suppressed: SuppressedError {
                error:      Error("dispose failed: b")
                suppressed: Error("body failed")   // 먼저 발생한 예외
              }
}
```

규칙은 "새 예외가 `error`, 기존 예외가 `suppressed`" 이고, 폐기가 역순이므로 b → a 순으로 감싸진다. 근본 원인은 가장 깊은 `suppressed` 에 있다.

로깅 시 체인을 펼치는 유틸리티가 필요하다.

```typescript
export function flattenErrors(err: unknown): unknown[] {
	const out: unknown[] = [];
	let cur = err;
	while (cur instanceof SuppressedError) {
		out.push(cur.error);
		cur = cur.suppressed;
	}
	out.push(cur);
	return out;
}

export function rootCause(err: unknown): unknown {
	let cur = err;
	while (cur instanceof SuppressedError) {
		cur = cur.suppressed;
	}
	return cur;
}
```

Sentry 나 Datadog 같은 APM 에 보낼 때 `SuppressedError` 를 그대로 넘기면 최상위 메시지만 그룹화 키로 쓰여 전부 "dispose failed" 로 뭉친다. `rootCause` 를 뽑아 별도 태그로 붙이는 것이 실무상 필요하다.

## 7. 타입 레벨 주의점

`Disposable` 은 구조적 타입이다. 그래서 아래가 통과한다.

```typescript
const fake: Disposable = { [Symbol.dispose]() {} };
```

이 구조적 성질이 문제가 되는 지점은 "폐기해야 하는 값을 폐기하지 않고 흘려보내는" 실수를 타입으로 못 잡는다는 것이다. Rust 의 `#[must_use]` 같은 장치가 없다. `typescript-eslint` 에 해당 규칙이 아직 표준화되어 있지 않으므로, 팩토리 함수 이름 규약(`open*`, `acquire*`)과 코드 리뷰로 보완하는 것이 현실적이다.

제네릭에서 자원 타입을 받을 때는 두 인터페이스를 유니온으로 받는 편이 유연하다.

```typescript
type AnyDisposable = Disposable | AsyncDisposable;

async function withResource<R extends AnyDisposable, T>(
	acquire: () => R | Promise<R>,
	use: (resource: R) => T | Promise<T>,
): Promise<T> {
	await using resource = await acquire();
	return await use(resource);
}
```

`await using` 은 `Disposable` 만 있는 객체도 받으므로 이 시그니처가 안전하다. 반대로 함수를 `using`(동기)으로 쓰려면 `R extends Disposable` 로 좁혀야 한다.

클래스 필드에는 `using` 을 못 쓴다는 제약 때문에, 객체 수명이 블록보다 긴 자원은 5절의 `#stack` 패턴으로 스택을 필드에 보관하고 자신의 `[Symbol.asyncDispose]` 에서 위임하는 형태가 표준 해법이다.

## 8. Effect·fp-ts 계열과의 비교

Effect 는 `Effect.acquireRelease` 와 `Scope` 로 같은 문제를 해결한다.

```typescript
const conn = Effect.acquireRelease(
	Effect.promise(() => pool.connect()),
	(client) => Effect.sync(() => client.release()),
);

const program = Effect.scoped(
	Effect.gen(function* () {
		const client = yield* conn;
		return yield* Effect.promise(() => client.query('SELECT 1'));
	}),
);
```

| 축 | `using` 문법 | Effect Scope |
|---|---|---|
| 도입 비용 | 문법 + lib 설정만 | 런타임 전체 채택 |
| 타입 추적 | 없음(구조적) | `Scope` 가 타입에 나타남 — 미해제가 컴파일 에러 |
| 인터럽션 안전성 | 없음 | 파이버 취소 시에도 해제 보장 |
| 병렬 해제 | 순차만 | `Effect.all` 로 병렬 |
| 기존 코드 통합 | 즉시 | 경계에서 변환 필요 |
| 에러 합성 | `SuppressedError` | `Cause` 트리 |

핵심 차이는 **타입 추적** 이다. Effect 에서 스코프가 필요한 이펙트는 타입의 세 번째 파라미터에 `Scope` 가 남아 있어, `Effect.scoped` 로 닫지 않으면 최종 실행 시 타입 에러가 난다. `using` 은 그런 보장이 없다.

반대로 `using` 은 언어 문법이라 진입 장벽이 사실상 0 이고, 기존 async/await 코드에 한 줄씩 점진 도입할 수 있다. 팀이 Effect 를 쓰지 않는다면 `using` + `DisposableStack` 조합으로 자원 관리의 90% 는 정리된다. 이미 Effect 를 쓰는 코드베이스라면 `Scope` 가 상위 호환이므로 굳이 섞을 이유가 없다 — 다만 Effect 경계 밖(테스트 셋업, CLI 스크립트, 마이그레이션 도구)에서는 `using` 이 여전히 유용하다.

## 참고

- TC39 Proposal — Explicit Resource Management (https://github.com/tc39/proposal-explicit-resource-management)
- TypeScript 5.2 Release Notes — using declarations and Explicit Resource Management
- TypeScript 5.2 Release Notes — Decorator Metadata, DisposableStack, SuppressedError
- MDN — Symbol.dispose, Symbol.asyncDispose, DisposableStack
- Effect Documentation — Scope and Resource Management
