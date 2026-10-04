Notion 원본: https://www.notion.so/3ef5a06fd6d381bfbde8dab2ae90594a

# TypeScript Explicit Resource Management using 선언과 Disposable 스택 및 다운레벨 컴파일

> 2026-10-03 신규 주제 · 확장 대상: TypeScript 제어 흐름 분석과 타입 내로잉, Node.js 비동기 자원 관리

## 학습 목표

- `using`과 `await using`이 `try/finally`로 풀리는 규칙(역순 해제, null 허용, 에러 억제)을 코드로 재현한다.
- `Symbol.dispose`, `Symbol.asyncDispose`를 구현한 클래스를 작성하고 `Disposable`/`AsyncDisposable` 타입으로 제약한다.
- 해제 중 예외와 본문 예외가 겹칠 때 생성되는 `SuppressedError` 구조를 해석한다.
- 다운레벨 emit 결과와 런타임 지원 범위를 확인해 폴리필 필요 여부를 판단한다.

## 1. 해결하려는 문제

DB 커넥션, 파일 핸들, 락, 트랜잭션 같은 자원은 "획득 → 사용 → 반드시 해제" 흐름을 가진다. JS에서는 지금까지 `try/finally`가 유일한 정석이었고, 자원이 둘 이상이면 중첩이 깊어졌다.

```ts
const a = await openA();
try {
	const b = await openB();
	try {
		await work(a, b);
	} finally {
		await b.close();
	}
} finally {
	await a.close();
}
```

중첩만 문제가 아니다. `finally`에서 `close()`가 예외를 던지면 본문에서 발생한 원래 예외가 사라진다. TC39 Explicit Resource Management 제안(TS 5.2에서 지원, ECMAScript 2026 후보 단계로 알려진 기능)은 이 두 문제를 언어 수준에서 해결한다. 정확한 표준 단계는 시점에 따라 달라지므로 TC39 proposals 저장소에서 확인해야 한다.

## 2. using 선언의 의미론

`using x = expr;`는 블록이 끝날 때 `x[Symbol.dispose]()`를 호출한다. `await using`은 `x[Symbol.asyncDispose]()`를 호출하고 `await`한다. 다음 코드를 TypeScript 5.9.3으로 컴파일하고 Node 22에서 실행해 출력을 확인했다.

```ts
class Conn implements Disposable {
	constructor(public name: string, private log: string[]) { log.push(`open ${name}`); }
	[Symbol.dispose](): void { this.log.push(`close ${this.name}`); }
}

export function run(log: string[]): void {
	using a = new Conn("A", log);
	using b = new Conn("B", log);
	log.push(`body ${a.name}${b.name}`);
}
```

실행 결과 로그는 `open A | open B | body AB | close B | close A`였다. 규칙은 다음과 같다.

선언 순서의 **역순**으로 해제된다. `b`가 `a`에 의존할 수 있기 때문이다. 본문이 정상 종료하든, `return`하든, 예외를 던지든 해제는 항상 일어난다. 값이 `null` 또는 `undefined`이면 해제를 건너뛴다. 그래서 `using x = maybeOpen();` 처럼 선택적 자원을 자연스럽게 쓸 수 있다. 반대로 `Symbol.dispose` 메서드가 없는 객체는 선언 시점에 `TypeError`가 난다. `using`은 `const`처럼 재할당할 수 없다.

## 3. 비동기 해제: await using

비동기 해제가 필요한 자원(커넥션 반납, 스트림 flush)은 `Symbol.asyncDispose`를 구현한다.

```ts
class AConn implements AsyncDisposable {
	constructor(public name: string, private log: string[]) { log.push(`aopen ${name}`); }
	async [Symbol.asyncDispose](): Promise<void> {
		await Promise.resolve();
		this.log.push(`aclose ${this.name}`);
	}
}

export async function runAsync(log: string[]): Promise<void> {
	await using a = new AConn("X", log);
	log.push("abody " + a.name);
}
```

주의할 점이 있다. `await using`은 **async 함수 안(또는 모듈 최상위)** 에서만 쓸 수 있고, 블록 종료 시점에 암묵적 `await`가 생긴다. 즉 동기처럼 보이는 `}` 한 줄이 마이크로태스크 경계를 만든다. 성능 민감한 루프 안에서 `await using`을 쓰면 반복마다 대기가 생기므로, 루프 바깥에서 자원을 한 번 획득하는 구조가 낫다.

또 하나, 동기 `Symbol.dispose`만 가진 객체도 `await using`에 쓸 수 있다. 런타임이 `asyncDispose`가 없으면 `dispose`로 대체한다. 반대로 `asyncDispose`만 가진 객체를 `using`에 쓰면 컴파일 에러다.

## 4. 에러 억제: SuppressedError

본문과 해제가 모두 예외를 던지면 어떻게 될까. 다음 코드로 확인했다.

```ts
class Bad implements Disposable {
	[Symbol.dispose](): void { throw new Error("dispose-fail"); }
}
export function suppressed(): unknown {
	try {
		using _b = new Bad();
		throw new Error("body-fail");
	} catch (e) {
		return e;
	}
}
const e = suppressed() as SuppressedError;
console.log(e.name, (e.error as Error).message, (e.suppressed as Error).message);
```

출력은 `SuppressedError dispose-fail body-fail`이었다. `error` 속성에는 **나중에 발생한 예외(해제 중 예외)** 가, `suppressed`에는 **먼저 발생한 예외(본문 예외)** 가 들어간다. 처음 보면 직관과 반대일 수 있다. 정리하면, 마지막으로 던져진 예외가 주(main) 에러이고, 그 때문에 가려진 이전 예외가 `suppressed`에 보존된다. 해제 중 예외가 여러 개면 `SuppressedError`가 중첩된다.

이 구조 덕분에 로깅에서 두 원인을 모두 볼 수 있다. 다만 기존 에러 핸들러가 `err instanceof SomeDomainError`로 분기한다면, 해제 실패 때문에 도메인 에러가 `SuppressedError`로 감싸져 분기가 깨질 수 있다. 해제 메서드는 **가능한 한 던지지 않도록** 작성하고, 실패는 내부에서 로깅하는 것이 권장된다.

## 5. DisposableStack과 AsyncDisposableStack

클래스를 만들 만큼은 아닌 자원이나, 개수가 동적인 자원에는 `DisposableStack`을 쓴다. `use()`로 Disposable을 등록하고, `defer()`로 임의 콜백을, `adopt()`로 값과 해제 함수 쌍을 등록한다. 스택 자체도 `using`으로 선언하면 블록 끝에서 역순으로 모두 해제된다. `move()`는 소유권을 새 스택으로 넘겨, 생성자에서 부분 초기화 실패 시 정리하는 패턴에 유용하다.

```ts
function openAll(paths: string[]) {
	using stack = new DisposableStack();
	const handles = paths.map((p) => stack.adopt(open(p), (h) => h.close()));
	// 여기까지 예외 없이 왔다면 소유권을 호출자에게 이전
	return { handles, cleanup: stack.move() };
}
```

여기서 실제 환경의 함정을 하나 확인했다. **Node 22.22.0에서 `DisposableStack`은 정의되어 있지 않았다.** `new DisposableStack()`이 `ReferenceError: DisposableStack is not defined`로 실패했다. 반면 `Symbol.dispose`/`Symbol.asyncDispose`는 존재해 `using` 자체는 동작했다. TypeScript는 `using`의 다운레벨 변환 헬퍼(`__addDisposableResource`, `__disposeResources`)를 emit에 삽입해 주지만, `DisposableStack` 같은 **런타임 전역은 폴리필하지 않는다.** 따라서 사용하려면 core-js 등의 폴리필, 또는 런타임 버전 확인이 필요하다. 이 노트의 시점 기준으로 각 런타임의 지원 현황은 MDN 호환성 표와 Node 릴리스 노트에서 직접 확인해야 한다.

## 6. 컴파일 설정과 emit 결과

`using`을 쓰려면 타입 정의가 필요하다. `lib`에 `esnext.disposable`을 추가한다.

```jsonc
{
	"compilerOptions": {
		"target": "ES2022",
		"lib": ["ES2022", "esnext.disposable"],
		"module": "ESNext",
		"moduleResolution": "Bundler",
		"strict": true
	}
}
```

`target`이 ES2022 이하이면 tsc는 `using`을 `try/catch/finally`와 헬퍼 함수 호출로 풀어서 내보낸다. 위 예제를 컴파일하면 `__addDisposableResource`가 파일에 6번 등장했고, env 객체(`{ stack: [], error: void 0, hasError: false }`)로 자원을 쌓은 뒤 `finally`에서 `__disposeResources(env)`가 역순으로 호출하는 구조였다. `importHelpers`와 `tslib`을 쓰면 헬퍼가 파일마다 중복 삽입되지 않는다. 라이브러리를 배포한다면 `importHelpers: true`가 번들 크기 면에서 유리하다.

Symbol 자체가 없는 런타임을 지원해야 한다면 앱 진입점에서 다음처럼 보강한다. 아래는 제안 형태이며, 표준 폴리필(core-js 등)을 쓰는 편이 안전하다.

```ts
(Symbol as { dispose?: symbol }).dispose ??= Symbol.for("Symbol.dispose");
(Symbol as { asyncDispose?: symbol }).asyncDispose ??= Symbol.for("Symbol.asyncDispose");
```

## 7. 실무 적용 패턴

**트랜잭션 스코프.** 커밋하지 않고 블록을 벗어나면 롤백되는 트랜잭션 객체를 만든다.

```ts
class Tx implements AsyncDisposable {
	private done = false;
	constructor(private conn: Connection) {}
	async commit(): Promise<void> { await this.conn.query("COMMIT"); this.done = true; }
	async [Symbol.asyncDispose](): Promise<void> {
		if (!this.done) await this.conn.query("ROLLBACK");
	}
}

async function transfer(conn: Connection) {
	await using tx = new Tx(conn);
	await conn.query("UPDATE ...");
	await tx.commit(); // 예외가 나면 commit에 도달하지 못하고 자동 롤백
}
```

**락 가드.** `using lock = await mutex.acquire();` 형태로 획득하고, 블록이 끝나면 해제한다. 해제 누락으로 인한 데드락을 줄인다.

**테스트 정리.** 임시 디렉터리, 모의 서버, 타이머를 `using`으로 묶으면 `afterEach` 정리 코드가 줄어든다. 단, 테스트 러너가 TS 변환을 어떤 도구로 하는지 확인해야 한다. esbuild, swc 등 단독 변환기의 `using` 지원 버전은 각 도구의 릴리스 노트에서 확인한다.

**주의 — 소유권 이전.** `using`으로 선언한 자원을 블록 밖으로 반환하면 반환 직전에 해제되어 닫힌 객체를 돌려주게 된다. 반환해야 한다면 `const`로 받고 호출자가 `using`을 쓰거나, 위의 `stack.move()`로 소유권을 넘긴다.

## 8. 검증 테스트

해제 순서와 에러 억제 규칙을 Vitest로 고정해 두면, 도구 체인 업그레이드 때 회귀를 잡을 수 있다. 아래 테스트의 기대값은 본문에서 실제 실행해 얻은 출력과 같다.

```ts
import { describe, expect, it } from "vitest";
import { run, suppressed, runAsync } from "./res";

describe("using", () => {
	it("선언 역순으로 해제한다", () => {
		const log: string[] = [];
		run(log);
		expect(log).toEqual(["open A", "open B", "body AB", "close B", "close A"]);
	});
	it("본문 예외와 해제 예외를 SuppressedError로 묶는다", () => {
		const e = suppressed() as SuppressedError;
		expect(e.name).toBe("SuppressedError");
		expect((e.error as Error).message).toBe("dispose-fail");
		expect((e.suppressed as Error).message).toBe("body-fail");
	});
	it("await using은 비동기 해제를 기다린다", async () => {
		const log: string[] = [];
		await runAsync(log);
		expect(log.at(-1)).toBe("aclose X");
	});
});
```

## 참고

- TypeScript 5.2 릴리스 노트: `using` Declarations and Explicit Resource Management
- TC39 proposal-explicit-resource-management
- MDN: `Symbol.dispose`, `DisposableStack`, `SuppressedError` 호환성 표
- Node.js 릴리스 노트: `Symbol.dispose` 지원 버전
