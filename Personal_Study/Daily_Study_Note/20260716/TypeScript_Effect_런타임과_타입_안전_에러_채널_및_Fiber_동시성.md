Notion 원본: https://www.notion.so/39f5a06fd6d381f0942ad87c4d8dc2f5

# TypeScript Effect 런타임과 타입 안전 에러 채널 및 Fiber 동시성

> 2026-07-16 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `Effect<A, E, R>` 세 타입 파라미터가 각각 무엇을 추적하는지 구분한다
- 예외 대신 에러 채널로 실패를 표현했을 때의 타입 이득을 측정한다
- Fiber 기반 구조적 동시성이 Promise 취소 불가 문제를 어떻게 해결하는지 설명한다
- Layer 로 의존성 그래프를 구성하고 Spring DI 와의 차이를 비교한다

## 1. Promise 의 타입 구멍에서 출발

`Promise<T>` 는 성공 타입만 갖는다. 실패는 타입에 나타나지 않는다.

```typescript
async function findUser(id: string): Promise<User> {
	const res = await fetch(`/api/users/${id}`);
	if (!res.ok) throw new NotFoundError(id);
	return res.json();
}
```

시그니처만 보면 이 함수가 던질 수 있는 에러가 무엇인지 알 길이 없다. `NotFoundError` 인가, `fetch` 의 `TypeError` 인가, JSON 파싱 실패인가. 호출부는 `catch (e: unknown)` 을 받아 `instanceof` 로 하나씩 시험하는 수밖에 없고, 새 에러 타입이 추가돼도 컴파일러는 침묵한다.

Java 에는 checked exception 이 있다. `throws NotFoundException` 을 선언하면 호출부가 처리를 강제받는다. 설계상 논란은 있었지만, 최소한 "이 메서드가 어떻게 실패하는가" 가 시그니처에 드러났다. TypeScript 에는 그런 장치가 없다. `throw` 는 타입 시스템 바깥의 사건이다.

두 번째 구멍은 의존성이다. 위 함수는 전역 `fetch` 에 암묵적으로 의존한다. 테스트하려면 전역을 목키패칭해야 하고, 시그니처는 이 의존을 전혀 알려주지 않는다.

세 번째 구멍은 취소다. `Promise` 는 한번 시작하면 멈출 수 없다. `AbortController` 는 `fetch` 같은 특정 API 가 협조할 때만 동작하고, 중첩된 비동기 작업 트리 전체를 일괄 중단하는 표준 수단이 없다.

Effect 라이브러리는 이 셋을 하나의 타입에 담는다.

```typescript
import { Effect } from "effect";

const findUser = (
	id: string
): Effect.Effect<User, NotFoundError | ParseError, HttpClient> => ...;
//                ^성공        ^실패(합집합)              ^요구 의존성
```

`Effect<A, E, R>` 는 "`R` 이 주어지면 `A` 를 만들거나 `E` 로 실패하는 계산 *설명서*" 다. 값이지 실행이 아니다.

## 2. Effect 는 실행되지 않는 값이다

Promise 는 생성 즉시 실행된다(eager). Effect 는 실행되지 않는다(lazy). 이 차이가 모든 것의 출발점이다.

```typescript
// Promise — 이 줄에서 이미 HTTP 요청이 나간다
const p = fetch("/api/users/1");

// Effect — 아무 일도 일어나지 않는다. 설명서를 만들었을 뿐
const e = Effect.tryPromise({
	try: () => fetch("/api/users/1"),
	catch: (cause) => new NetworkError({ cause }),
});

// 실행은 명시적으로
const result = await Effect.runPromise(e);
```

lazy 하기 때문에 다음이 전부 가능해진다.

- **재시도**: 설명서를 다시 실행하면 된다. Promise 는 이미 확정된 결과라 재시도 불가
- **취소**: 실행 중인 지점을 런타임이 알고 있으므로 중단 가능
- **의존성 주입**: 실행 시점에 `R` 을 공급
- **테스트 시간 제어**: 실제 타이머 대신 가상 시계 주입

Effect 값은 사실 인터프리터가 읽는 명령 트리다. `Effect.map`, `Effect.flatMap` 같은 콤비네이터는 트리에 노드를 붙일 뿐이다. `runPromise` 를 호출하면 런타임이 트리를 순회하며 실행한다.

기본 문법은 generator 기반 `Effect.gen` 이며, `async/await` 와 형태가 대응된다.

```typescript
const program = Effect.gen(function* () {
	const user = yield* findUser("1");        // await 대신 yield*
	const orders = yield* findOrders(user.id);
	return { user, orders };
});
// Effect<{user: User, orders: Order[]}, NotFoundError | DbError, HttpClient | Db>
```

`yield*` 마다 에러 타입과 의존성 타입이 **자동으로 합집합**된다. 이 추론이 Effect 의 핵심 가치다. 함수 20개를 조합해도 최종 시그니처에 가능한 실패 전부와 필요한 의존성 전부가 정확히 집계된다.

## 3. 에러 채널 — 세 종류의 실패

Effect 는 실패를 세 범주로 구분한다.

| 범주 | 타입 위치 | 의미 | 예 |
|---|---|---|---|
| Failure (expected) | `E` 채널 | 예상된 도메인 실패 | 사용자 없음, 잔액 부족 |
| Defect (unexpected) | 타입에 없음 | 버그 | null 역참조, 불변식 위반 |
| Interruption | 타입에 없음 | 외부 취소 | 타임아웃, 부모 fiber 중단 |

이 구분이 Java 의 checked/unchecked 구분과 정확히 대응된다. 잔액 부족은 호출부가 처리해야 할 정상적 결과이므로 `E` 에 넣는다. 배열 인덱스 오류는 처리할 방법이 없는 버그이므로 defect 로 두고 최상단에서 로깅한다.

```typescript
import { Data, Effect } from "effect";

class InsufficientBalance extends Data.TaggedError("InsufficientBalance")<{
	readonly required: number;
	readonly available: number;
}> {}

class AccountNotFound extends Data.TaggedError("AccountNotFound")<{
	readonly accountId: string;
}> {}

const withdraw = (
	accountId: string,
	amount: number
): Effect.Effect<Receipt, InsufficientBalance | AccountNotFound, AccountRepo> =>
	Effect.gen(function* () {
		const repo = yield* AccountRepo;
		const account = yield* repo.find(accountId);
		if (account.balance < amount) {
			return yield* Effect.fail(
				new InsufficientBalance({ required: amount, available: account.balance })
			);
		}
		return yield* repo.debit(accountId, amount);
	});
```

`Data.TaggedError` 는 `_tag` 판별자를 자동으로 붙인다. 덕분에 `catchTag` 로 **타입 안전한 선택적 처리**가 된다.

```typescript
const safe = withdraw("acc-1", 5000).pipe(
	Effect.catchTag("InsufficientBalance", (e) =>
		Effect.succeed({ status: "declined", shortfall: e.required - e.available })
	)
);
// Effect<Receipt | {...}, AccountNotFound, AccountRepo>
//                          ^^^^^^^^^^^^^^^ 처리한 것만 정확히 제거됐다
```

`InsufficientBalance` 만 제거되고 `AccountNotFound` 는 남는다. 컴파일러가 "아직 처리 안 한 실패" 를 추적한다. 나중에 새 에러 타입이 추가되면 `catchTags` 의 exhaustive 검사가 즉시 깨진다. Java 의 checked exception 이 하려던 일을 타입 추론으로 자동화한 것이다.

`Cause` 타입은 이 세 범주를 트리로 표현한다.

```typescript
type Cause<E> =
	| { _tag: "Empty" }
	| { _tag: "Fail"; error: E }
	| { _tag: "Die"; defect: unknown }
	| { _tag: "Interrupt"; fiberId: FiberId }
	| { _tag: "Sequential"; left: Cause<E>; right: Cause<E> }
	| { _tag: "Parallel"; left: Cause<E>; right: Cause<E> };
```

`Parallel` 노드가 중요하다. 병렬 작업 3개가 동시에 실패하면 Promise.all 은 **가장 먼저 실패한 것 하나만** 남기고 나머지를 버린다. Effect 는 셋 다 `Cause` 트리에 보존한다. 장애 분석에서 이 차이는 결정적이다. "왜 실패했나" 를 물었을 때 원인 하나가 아니라 동시 발생한 원인 전부를 볼 수 있다.

## 4. Fiber 와 구조적 동시성

Effect 런타임은 실행 단위로 **Fiber** 를 쓴다. OS 스레드가 아니라 런타임이 관리하는 경량 협조적 스케줄링 단위이며, Java 21 의 Virtual Thread 와 개념이 같다. 단 JS 는 싱글 스레드이므로 병렬성이 아니라 **인터리빙**을 제공한다.

Fiber 의 핵심 능력은 **취소 가능성**이다. 런타임은 명령 트리를 해석하며 매 yield 지점에서 인터럽트 플래그를 확인한다.

```typescript
import { Effect, Fiber } from "effect";

const longTask = Effect.gen(function* () {
	yield* Effect.log("시작");
	yield* Effect.sleep("10 seconds");
	yield* Effect.log("완료");  // 취소되면 도달하지 않는다
});

const program = Effect.gen(function* () {
	const fiber = yield* Effect.fork(longTask);
	yield* Effect.sleep("1 second");
	yield* Fiber.interrupt(fiber);  // 실제로 멈춘다
	yield* Effect.log("취소됨");
});
```

**구조적 동시성**은 "자식 fiber 는 부모 fiber 의 스코프를 벗어나 살아남을 수 없다" 는 규칙이다. 부모가 끝나거나 실패하면 자식이 전부 자동 중단된다. 좀비 태스크가 원천적으로 불가능하다.

```typescript
const parent = Effect.gen(function* () {
	yield* Effect.fork(pollForever);     // 자식 1
	yield* Effect.fork(watchChanges);    // 자식 2
	yield* Effect.fail(new SomeError()); // 부모 실패
	// → 자식 1, 2 자동 인터럽트. 명시적 정리 코드 불필요
});
```

Promise 로 같은 것을 하려면 모든 함수에 `AbortSignal` 을 손으로 배관해야 하고, 하나라도 빠뜨리면 누수된다.

리소스 정리는 `acquireRelease` 로 보장된다.

```typescript
const withConnection = Effect.acquireRelease(
	Effect.sync(() => pool.getConnection()),
	(conn) => Effect.sync(() => conn.release())  // 성공·실패·인터럽트 전부에서 실행
);

const query = Effect.gen(function* () {
	const conn = yield* withConnection;
	return yield* Effect.tryPromise(() => conn.query("SELECT 1"));
}).pipe(Effect.scoped);
```

`try/finally` 와의 차이는 인터럽트다. `finally` 는 Promise 가 취소되지 않으므로 취소 시 실행 보장이 없다. `acquireRelease` 의 release 는 인터럽트 불가능 영역에서 실행되므로 반드시 돌다.

병렬 실행 API 는 동시성 수준을 명시한다.

```typescript
// 무제한 병렬 — 하나 실패하면 나머지 즉시 인터럽트
const all = Effect.all([a, b, c], { concurrency: "unbounded" });

// 동시 5개로 제한 — DB 커넥션 풀 크기에 맞추는 실무 패턴
const limited = Effect.forEach(ids, fetchUser, { concurrency: 5 });

// 하나라도 실패해도 전부 수집
const settled = Effect.all([a, b, c], { mode: "either" });
```

`Promise.all` 은 실패해도 나머지가 계속 돌아 리소스를 낭비한다. Effect 는 즉시 중단한다.

## 5. Layer 와 의존성 주입

`R` 채널의 의존성은 `Context.Tag` 로 선언하고 `Layer` 로 공급한다.

```typescript
import { Context, Effect, Layer } from "effect";

class AccountRepo extends Context.Tag("AccountRepo")<
	AccountRepo,
	{
		readonly find: (id: string) => Effect.Effect<Account, AccountNotFound>;
		readonly debit: (id: string, amt: number) => Effect.Effect<Receipt, never>;
	}
>() {}

// 실제 구현 — Db 를 필요로 한다
const AccountRepoLive = Layer.effect(
	AccountRepo,
	Effect.gen(function* () {
		const db = yield* Db;
		return {
			find: (id) => /* ... */,
			debit: (id, amt) => /* ... */,
		};
	})
);
// Layer<AccountRepo, never, Db>
//       ^제공          ^에러  ^요구

// 테스트 구현
const AccountRepoTest = Layer.succeed(AccountRepo, {
	find: () => Effect.succeed({ id: "1", balance: 10_000 }),
	debit: () => Effect.succeed({ ok: true }),
});
```

Layer 를 합성하면 의존성 그래프가 만들어진다.

```typescript
const MainLive = AccountRepoLive.pipe(
	Layer.provide(DbLive),
	Layer.provide(ConfigLive)
);

const runnable = program.pipe(Effect.provide(MainLive));
// Effect<Receipt, InsufficientBalance | AccountNotFound, never>
//                                                        ^^^^^ R이 비었다 = 실행 가능
```

`R` 이 `never` 가 되어야만 `runPromise` 가 호출된다. **의존성 누락이 컴파일 에러다.** Spring 과 비교하면 차이가 선명하다.

| 항목 | Spring DI | Effect Layer |
|---|---|---|
| 해석 시점 | 런타임 (컨텍스트 기동) | 컴파일 타임 |
| 누락 감지 | `NoSuchBeanDefinitionException` | 타입 에러 |
| 순환 참조 | 런타임 감지, `@Lazy` 우회 | 타입 레벨에서 구성 불가 |
| 테스트 교체 | `@MockBean`, 프로파일 | Layer 교체 |
| 스코프 | singleton/prototype/request | `Layer.scoped`, memoization |
| 기동 비용 | 클래스패스 스캔 | 없음 |

Layer 는 기본적으로 memoize 된다. 같은 Layer 를 여러 곳에서 참조해도 인스턴스는 하나다. Spring 의 singleton 과 같은 동작이며, `Layer.fresh` 로 명시적으로 끌 수 있다.

## 6. 재시도, 타임아웃, 스케줄

Effect 는 재시도 정책을 `Schedule` 이라는 합성 가능한 값으로 표현한다.

```typescript
import { Schedule, Duration } from "effect";

const policy = Schedule.exponential(Duration.millis(100), 2.0).pipe(
	Schedule.jittered,                                  // 썬더링 허드 방지
	Schedule.compose(Schedule.recurs(5)),               // 최대 5회
	Schedule.whileInput((e: HttpError) => e.status >= 500) // 5xx만 재시도
);

const resilient = callApi.pipe(
	Effect.retry(policy),
	Effect.timeout("3 seconds"),
	Effect.catchTag("TimeoutException", () => Effect.succeed(cachedValue))
);
```

`Schedule.jittered` 가 중요하다. 지수 백오프만 쓰면 동시에 실패한 클라이언트들이 정확히 같은 시각에 재시도해 뒤쪽 서비스를 다시 무너뜨린다. jitter 로 재시도 시각을 무작위 분산시킨다. Spring Retry 의 `ExponentialBackOffPolicy` + `multiplier` 조합과 목적이 같으나, Schedule 은 값이므로 합성·테스트·재사용이 자유롭다.

`Effect.timeout` 은 실제로 작업을 중단한다. Promise 기반 타임아웃은 `Promise.race` 로 결과만 버리고 원래 작업은 계속 돌다. 커넥션이 계속 점유된다. Fiber 는 인터럽트되므로 `acquireRelease` 의 release 가 돌아 커넥션이 반납된다.

## 7. 도입 비용과 판단 기준

Effect 는 공짜가 아니다.

**학습 곡선이 가파르다.** Effect, Layer, Schedule, Scope, Ref, Stream, STM 등 개념이 많다. 팀 전체가 익히는 데 상당한 시간이 든다. 한 명만 아는 상태로 도입하면 그 사람이 떠날 때 코드베이스가 고아가 된다.

**전염성이 있다.** Effect 를 반환하는 함수를 호출하려면 호출부도 Effect 여야 한다. 코드베이스 일부에만 쓰기 어렵고, 경계에서 `runPromise` 로 탈출해야 하는데 그 지점에서 타입 이득이 사라진다.

**번들 크기.** 코어만 해도 무시할 수 없고, tree-shaking 이 되지만 실제 사용 시 상당량이 포함된다. 프론트엔드 번들에 민감하면 검토가 필요하다.

**스택 트레이스.** 인터프리터를 거치므로 네이티브 스택이 아니다. Effect 는 자체 트레이스를 제공하지만 브라우저 devtools 경험은 다르다.

**도입이 유리한 경우**

- 서버 사이드 Node.js — 번들 크기 무관, 동시성·재시도·리소스 관리가 중요
- 실패 경로가 도메인의 핵심인 시스템(결제, 예약, 트랜잭션 조율)
- 외부 API 여러 개를 오케스트레이션하고 각각의 실패를 다르게 처리해야 하는 경우
- 취소가 실제로 필요한 장기 실행 작업

**도입이 불리한 경우**

- 단순 CRUD — `try/catch` 로 충분한데 개념 부담만 늘어난다
- 팀에 함수형 배경이 없고 학습 시간을 확보할 수 없는 경우
- 번들 크기가 빡빡한 프론트엔드
- 기존 Promise 코드베이스가 크고 마이그레이션 여력이 없는 경우

중간 지점도 있다. `neverthrow` 나 `ts-results` 같은 경량 `Result<T, E>` 라이브러리는 에러 채널의 타입 안전만 취하고 Fiber·Layer 는 포기한다. 학습 비용이 훨씬 낮으므로 "타입 안전한 에러만 필요" 하면 이쪽이 합리적이다.

## 8. 점진적 도입 전략

전면 재작성은 실패한다. 경계에서 시작한다.

```typescript
// 1) 기존 Promise 함수를 Effect로 감싸는 어댑터
const fetchUserEffect = (id: string) =>
	Effect.tryPromise({
		try: () => legacyFetchUser(id),
		catch: (e) => new UserFetchError({ cause: e }),
	});

// 2) 새 로직만 Effect로 작성
const enrichedUser = (id: string) =>
	Effect.gen(function* () {
		const user = yield* fetchUserEffect(id);
		const [orders, prefs] = yield* Effect.all(
			[fetchOrders(user.id), fetchPrefs(user.id)],
			{ concurrency: 2 }
		);
		return { ...user, orders, prefs };
	});

// 3) 기존 Express 핸들러 경계에서 탈출
app.get("/users/:id", async (req, res) => {
	const result = await Effect.runPromiseExit(
		enrichedUser(req.params.id).pipe(Effect.provide(MainLive))
	);
	Exit.match(result, {
		onFailure: (cause) => res.status(500).json({ error: Cause.pretty(cause) }),
		onSuccess: (user) => res.json(user),
	});
});
```

`runPromiseExit` 는 던지지 않고 `Exit<A, E>` 를 반환한다. `Cause.pretty` 는 병렬 실패까지 포함한 전체 원인 트리를 사람이 읽을 수 있게 출력한다. 이 한 지점만 Effect 로 바꿔도 장애 로그의 정보량이 크게 늘어난다.

권장 순서는 다음과 같다. 먼저 새로 작성하는 모듈 하나를 Effect 로 만들어 팀이 실물을 본다. 그다음 재시도·타임아웃이 필요한 외부 API 호출 계층을 옮긴다. 이 계층이 Effect 의 이득이 가장 크고 범위가 명확하다. 마지막으로 도메인 로직을 옮긴다. 컨트롤러·라우터 계층은 마지막까지 Promise 로 두고 경계에서만 변환하는 것이 안전하다.

## 참고

- Effect Documentation (https://effect.website/docs/introduction)
- Effect API Reference — Effect, Layer, Schedule, Fiber 모듈
- ZIO Documentation — Effect 의 설계 원형인 Scala 라이브러리 (https://zio.dev/)
- Structured Concurrency, Nathaniel J. Smith (https://vorpus.org/blog/notes-on-structured-concurrency-or-go-statement-considered-harmful/)
- JEP 444: Virtual Threads — Fiber 개념의 JVM 대응물
- neverthrow (https://github.com/supermacro/neverthrow) — 경량 Result 대안
