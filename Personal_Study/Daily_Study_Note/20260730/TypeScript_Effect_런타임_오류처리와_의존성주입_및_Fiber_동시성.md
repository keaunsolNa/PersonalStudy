Notion 원본: https://app.notion.com/p/3ac5a06fd6d381069682db5d702cbb03

# TypeScript Effect 런타임 오류처리와 의존성 주입 및 Fiber 동시성

> 2026-07-30 신규 주제 · 확장 대상: TypeScript(타입 안전 런타임 라이브러리 심화)

## 학습 목표

- `Effect<A, E, R>` 세 타입 파라미터(성공·오류·요구사항)가 타입 수준에서 무엇을 추적하는지 정리한다.
- 예외(throw) 대신 오류를 타입에 담는 방식이 어떻게 컴파일 타임 오류 처리를 강제하는지 구분한다.
- Context/Layer 로 의존성을 타입 안전하게 주입·조합하는 원리를 파악한다.
- Fiber 기반 구조적 동시성으로 병렬·경쟁·인터럽트를 직접 구현해 검증한다.

## 1. Effect 는 "아직 실행되지 않은 프로그램의 설명"

Promise 는 생성 즉시 실행되고(eager), 오류 타입을 갖지 않으며(`Promise<T>` 뿐, 실패는 `any`), 의존성을 타입으로 표현하지 못한다. Effect 는 이 세 약점을 정면으로 겨눈다. `Effect<A, E, R>` 는 "성공하면 `A`, 실패하면 `E`, 실행에 `R` 이라는 환경이 필요한 **아직 실행 안 된** 계산"을 뜻한다.

```ts
import { Effect } from "effect";

// 성공: number, 오류: 없음(never), 요구사항: 없음(never)
const succeed: Effect.Effect<number> = Effect.succeed(42);

// 성공: never, 오류: Error, 요구사항: 없음
const fail: Effect.Effect<never, Error> = Effect.fail(new Error("boom"));
```

Effect 값은 서술(description)일 뿐이라 만들기만 해선 아무 일도 안 일어난다. `Effect.runPromise(effect)` 같은 실행자를 만나야 비로소 돈다. 이 지연성 덕분에 재시도·타임아웃·인터럽트 같은 제어를 값에 조합으로 얹을 수 있다.

## 2. 오류를 타입에 담으면 컴파일러가 처리를 강제한다

전통적 코드에서 `throw` 된 오류는 타입 시스템에 흔적을 남기지 않는다. 어떤 함수가 어떤 예외를 던지는지 시그니처만 봐선 알 수 없다. Effect 는 실패 가능성을 **두 번째 타입 파라미터** `E` 로 추적한다. 서로 다른 실패를 태그된 클래스로 정의하면, 컴파일러가 유니온을 좁히도록 강제한다.

```ts
import { Effect, Data } from "effect";

class NetworkError extends Data.TaggedError("NetworkError")<{ url: string }> {}
class ParseError   extends Data.TaggedError("ParseError")<{ raw: string }> {}

const fetchUser = (id: number): Effect.Effect<User, NetworkError | ParseError> =>
  Effect.gen(function* () {
    const res = yield* Effect.tryPromise({
      try: () => fetch(`/api/users/${id}`),
      catch: () => new NetworkError({ url: `/api/users/${id}` }),
    });
    const json = yield* Effect.tryPromise({
      try: () => res.json(),
      catch: (e) => new ParseError({ raw: String(e) }),
    });
    return json as User;
  });

// 오류 처리 — 태그로 분기하면 컴파일러가 완전성을 검사
const safe = fetchUser(1).pipe(
  Effect.catchTag("NetworkError", (e) =>
    Effect.succeed({ id: 0, name: `offline(${e.url})` } as User)),
  Effect.catchTag("ParseError", () => Effect.succeed({ id: 0, name: "invalid" } as User)),
);
// safe 의 타입: Effect<User, never>  ← 모든 오류가 처리되어 E 가 never 로 소거
```

`catchTag` 를 하나라도 빠뜨리면 결과 타입의 `E` 에 그 오류가 남아, 최종 실행 지점에서 "처리 안 된 오류가 있다"는 사실이 타입으로 드러난다. 오류 처리 누락이 런타임 크래시가 아니라 컴파일 신호가 된다.

## 3. Context 와 Layer — 타입 안전 의존성 주입

세 번째 파라미터 `R` 은 "이 Effect 를 실행하려면 무엇이 필요한가"다. 서비스를 `Context.Tag` 로 정의하면, 그 서비스를 쓰는 Effect 의 `R` 에 자동으로 요구사항이 누적된다. 실행 전에 그 요구사항이 모두 충족되지 않으면 **컴파일이 안 된다**.

```ts
import { Effect, Context, Layer } from "effect";

class Database extends Context.Tag("Database")<
  Database,
  { readonly query: (sql: string) => Effect.Effect<Row[]> }
>() {}

// 이 Effect 는 R = Database 를 요구한다 (타입에 드러남)
const listUsers: Effect.Effect<Row[], never, Database> =
  Effect.gen(function* () {
    const db = yield* Database;               // 컨텍스트에서 꺼냄
    return yield* db.query("SELECT * FROM users");
  });

// 실제 구현을 Layer 로 제공
const DatabaseLive = Layer.succeed(Database, {
  query: (sql) => Effect.succeed([{ id: 1 }]),  // 실제론 커넥션 풀 사용
});

// 테스트용 대체 구현 — 같은 Tag, 다른 Layer
const DatabaseTest = Layer.succeed(Database, {
  query: () => Effect.succeed([{ id: 999 }]),
});

const program = listUsers.pipe(Effect.provide(DatabaseLive));
// program 의 R 이 never 로 충족됨 → 이제 실행 가능
Effect.runPromise(program);
```

Layer 는 의존성을 가진 서비스도 조합할 수 있다(Layer 자체가 다른 Layer 를 요구). `Layer.provide` 로 레이어들을 위상 정렬하듯 엮으면, 스프링의 DI 컨테이너가 하는 일을 **타입 검사로** 수행한다. 프로덕션은 `DatabaseLive`, 테스트는 `DatabaseTest` 를 provide 하면 되므로, 목킹이 프레임워크 마법 없이 값 교체로 끝난다.

## 4. Fiber — 경량 동시성 단위

Effect 의 동시성은 OS 스레드가 아니라 **Fiber** 라는 경량 협력 단위 위에서 돈다. 하나의 이벤트 루프에서 수만 개 Fiber 를 스케줄링하며, 각 Fiber 는 인터럽트 가능(interruptible)하다. 병렬 실행은 조합자로 표현된다.

```ts
import { Effect } from "effect";

const a = Effect.delay(Effect.succeed("A"), "100 millis");
const b = Effect.delay(Effect.succeed("B"), "200 millis");

// 둘 다 완료될 때까지 병렬 대기
const both = Effect.all([a, b], { concurrency: "unbounded" });
// 결과: ["A", "B"]

// 먼저 끝나는 쪽만 취하고 나머지는 자동 인터럽트 (race)
const winner = Effect.race(a, b);   // "A"
```

`Effect.race` 에서 진 쪽 Fiber 는 자동으로 인터럽트되어 자원을 정리한다. 여기서 중요한 것이 **구조적 동시성** — 부모 Fiber 가 끝나거나 인터럽트되면 그가 낳은 자식 Fiber 도 함께 정리된다. 그래서 "타임아웃으로 요청을 취소했는데 백그라운드 작업이 좀비로 계속 도는" 문제가 구조적으로 방지된다.

## 5. 자원 안전성 — acquireRelease 와 Scope

파일·커넥션·락 같은 자원은 반드시 해제돼야 한다. `try/finally` 는 비동기·인터럽트 상황에서 새기 쉽다. Effect 는 `acquireRelease` 로 획득과 해제를 한 값에 묶고, 인터럽트가 나도 해제가 보장된다.

```ts
const withConn = Effect.acquireRelease(
  Effect.sync(() => openConnection()),         // acquire
  (conn) => Effect.sync(() => conn.close()),   // release — 성공/실패/인터럽트 모두에서 실행
);

const useConn = Effect.gen(function* () {
  const conn = yield* withConn;                // Scope 에 등록
  return yield* Effect.promise(() => conn.query("SELECT 1"));
}).pipe(Effect.scoped);                         // scope 종료 시 release 자동 호출
```

인터럽트가 acquire 와 release 사이에 끼어들어도 release 는 실행된다. 이 보장이 없으면 race·timeout 조합에서 커넥션 누수가 발생한다. Effect 의 자원 모델은 이 창을 원자적으로 다룬다.

## 6. 재시도·타임아웃·스케줄 조합

지연성 덕분에 정책을 값으로 얹는다. `Schedule` 은 재시도·반복 정책을 선언적으로 표현하는 별도 대수(algebra)다.

```ts
import { Effect, Schedule, Duration } from "effect";

const policy = Schedule.exponential(Duration.millis(100))   // 100ms 부터 지수
  .pipe(Schedule.compose(Schedule.recurs(5)),               // 최대 5회
        Schedule.jittered);                                 // 지터 추가

const robust = fetchUser(1).pipe(
  Effect.retry(policy),
  Effect.timeout("3 seconds"),   // 초과 시 인터럽트 → 자원 정리 보장
);
```

`retry` 와 `timeout` 이 각각 독립 조합자라, "지수 백오프로 5번 재시도하되 전체 3초 안에"를 코드 순서가 아니라 조합으로 명확히 표현한다. 타임아웃이 발동하면 진행 중이던 Fiber 는 인터럽트되고 `acquireRelease` 들이 역순으로 정리된다.

## 7. 언제 Effect 를 도입할 것인가

Effect 는 강력하지만 학습 곡선과 생태계 종속이라는 비용이 크다. 단순 스크립트나 얇은 API 핸들러에 도입하면 과하다. 반면 도메인 오류가 다양하고, 자원 정리·재시도·동시성이 얽히며, 대규모 팀이 오류 처리 누락 없이 일관성을 유지해야 하는 백엔드에서는 타입이 주는 안전망이 크다. `E` 채널이 "이 코드가 어떤 실패를 낼 수 있는가"를 문서화하고, `R` 채널이 의존성을 명시하며, Fiber 가 좀비 작업을 막는다. 부분 도입도 가능해서 — Promise 기반 코드를 `Effect.tryPromise` 로 감싸 경계에서만 Effect 로 올리는 점진 전략이 현실적이다. 판단 기준은 "오류·자원·동시성의 복잡도가 타입 안전으로 상쇄될 만큼 높은가"이다.

## 8. 기대 오류와 결함의 구분 — Cause

Effect 는 실패를 두 종류로 명확히 나눈다. `E` 채널에 담기는 **기대 오류(expected/failure)** 는 도메인상 일어날 수 있어 처리를 강제하는 실패다. 반면 예상 못 한 **결함(defect)** — 널 참조, 배열 범위 초과 같은 버그 — 은 `E` 에 넣지 않고 `die` 로 다룬다. 이 둘을 섞으면 "정상적인 실패 처리"와 "복구 불가능한 버그"가 구분되지 않아 오류 처리가 부정확해진다.

```ts
import { Effect, Cause, Exit } from "effect";

const program = Effect.gen(function* () {
  const x: number = yield* Effect.succeed(10);
  if (x > 5) return yield* Effect.fail(new RangeError("too big")); // 기대 오류 → E
  throw new Error("bug!");                                         // 결함 → die
});

const exit = await Effect.runPromiseExit(program);
if (Exit.isFailure(exit)) {
  // Cause 는 실패의 전체 구조(기대 오류·결함·인터럽트·병렬 실패)를 보존
  console.log(Cause.pretty(exit.cause));
}
```

`Cause` 는 단일 오류가 아니라 실패의 **전체 트리** 다. 병렬 실행에서 두 Fiber 가 동시에 실패하면 두 원인이 모두 보존되고(`Cause.Parallel`), 인터럽트도 원인으로 기록된다. Promise 가 첫 rejection 하나만 남기고 나머지를 삼키는 것과 대조적이다. 이 구조 덕분에 사후 디버깅에서 "무엇이 진짜 근본 원인이고 무엇이 그 여파였는지"를 잃지 않는다.

## 9. 배칭·캐싱 — Request 와 RequestResolver

Effect 생태계의 실전 강점 중 하나가 자동 요청 배칭이다. 여러 곳에서 독립적으로 "사용자 N번을 달라"는 Effect 를 만들면, Effect 런타임이 같은 tick 에 모인 요청들을 **하나의 배치 쿼리** 로 묶어 N+1 문제를 구조적으로 제거한다. GraphQL 의 DataLoader 와 같은 목적을, 별도 로더 인스턴스 관리 없이 타입 시스템 위에서 제공한다.

```ts
import { Effect, Request, RequestResolver } from "effect";

interface GetUser extends Request.Request<User, NetworkError> {
  readonly _tag: "GetUser";
  readonly id: number;
}
const GetUser = Request.tagged<GetUser>("GetUser");

// 여러 GetUser 요청을 모아 한 번의 배치 호출로 해결
const resolver = RequestResolver.makeBatched((reqs: GetUser[]) =>
  Effect.gen(function* () {
    const users = yield* fetchUsersBatch(reqs.map((r) => r.id)); // 단일 IN 쿼리
    return reqs.forEach((r, i) => Request.completeEffect(r, Effect.succeed(users[i])));
  }));

const getUser = (id: number) => Effect.request(GetUser({ id }), resolver);
// getUser(1), getUser(2), getUser(3) 을 병렬로 호출해도 fetchUsersBatch([1,2,3]) 한 번
```

동일 요청은 자동 캐시되어 중복 호출도 제거된다. 이처럼 Effect 의 진짜 가치는 오류·의존성 타입 추적을 넘어, 동시성·배칭·자원 관리 같은 횡단 관심사를 일관된 대수로 조합한다는 점에 있다. 개별 기능은 다른 라이브러리에도 있지만, 하나의 타입 안전한 모델 안에서 서로 합성된다는 점이 차별점이다.

## 참고

- Effect Documentation (effect.website) — Effect Data Type, Error Management, Context & Layers
- Effect API Reference — `Effect`, `Layer`, `Fiber`, `Schedule`, `Scope`
- "Structured Concurrency" (Effect docs) — 부모-자식 Fiber 생명주기
- Michael Arnaldi, Effect 컨퍼런스 발표 — 세 타입 파라미터 설계 배경
