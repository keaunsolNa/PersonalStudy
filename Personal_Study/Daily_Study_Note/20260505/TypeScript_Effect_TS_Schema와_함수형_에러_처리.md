Notion 원본: https://www.notion.so/3575a06fd6d381ddba2ccd7c9951a0a3

# TypeScript Effect-TS — Schema 검증과 Either/Effect 기반 함수형 에러 처리

> 2026-05-05 신규 주제 · 확장 대상: Zod Runtime 검증, Conditional Types

## 학습 목표

- Effect 의 `Effect<R, E, A>` 삼중 타입 파라미터가 의존성·에러·성공값을 어떻게 정적으로 추적하는지 분석한다
- `Schema.Schema<I, A, R>` 의 양방향 변환(decode/encode)과 Zod 의 단방향 parse 를 비교해 설계 차이를 정리한다
- `Effect.Service` / `Layer` 기반 의존성 주입과 NestJS DI 컨테이너의 차이를 코드로 검증한다
- Either-only 코드(fp-ts 잔재) → Effect 마이그레이션 시 발생하는 함정 4가지를 코드로 제시한다

## 1. fp-ts Either 만으로는 부족한 이유

`fp-ts` 의 `Either<E, A>` 는 단일 표현식 안에서 에러를 값으로 다루는 데 충분하다. 하지만 비동기 작업, 의존성 주입, 인터럽트, 리소스 해제 같은 *효과(effect)* 를 모두 표현하려면 `TaskEither<E, A>` + `ReaderTaskEither<R, E, A>` 처럼 타입을 계속 합성해야 했고, 이는 14개에 달하는 모나드 변환자(`fold`, `chainW`, `bindW`, `apS` …) 의 학습 곡선을 만든다. Effect 는 이 모든 것을 단일 데이터 타입 `Effect<A, E, R>` (3.0 부터 인자 순서가 success → error → requirements 로 변경됨) 으로 통합한다.

```ts
import { Effect, Schema, Layer, Context } from "effect";

const program = Effect.gen(function* () {
  const config = yield* Config;        // R 에 Config 요구사항 추가
  const user   = yield* fetchUser(1);  // E 에 NetworkError 추가
  return user.email.toUpperCase();     // A = string
});
//      ^? Effect<string, NetworkError, Config>
```

세 개의 인자 모두 *추론* 된다. `R = Config`, `E = NetworkError`, `A = string` 이고, `Config` 를 제공하지 않은 채 `Effect.runPromise(program)` 을 호출하면 컴파일 에러가 난다. fp-ts 시절엔 런타임 의존성을 함수 인자로 매번 통과시키거나 ReaderTaskEither 로 감싸야 했지만 Effect 는 generator + service 패턴으로 같은 안전성을 훨씬 짧은 문법으로 얻는다.

## 2. Schema 의 양방향 변환 — Zod 와의 핵심 차이

Zod 는 `parse(input: unknown) → T` 단방향이다. 반대로 Effect Schema 는 `Schema<I, A, R>` 즉 입력 표현(`I`), 도메인 표현(`A`), 변환에 필요한 의존성(`R`) 을 모두 추적하면서 *encode* 도 수행한다. ISO 문자열 ↔ `Date` 객체처럼 직렬화 양면이 필요한 도메인에서 차이가 두드러진다.

```ts
import { Schema as S } from "effect";

const ISODate = S.transform(
  S.String,                // I
  S.DateFromSelf,          // A
  {
    decode: (s) => new Date(s),
    encode: (d) => d.toISOString(),
  }
);

const User = S.Struct({
  id: S.Number,
  email: S.String.pipe(S.pattern(/.+@.+/)),
  createdAt: ISODate,
  role: S.Literal("admin", "member", "guest"),
});

type UserI = S.Schema.Encoded<typeof User>;   // { id: number; email: string; createdAt: string; role: ... }
type UserA = S.Schema.Type<typeof User>;      // { id: number; email: string; createdAt: Date;   role: ... }

const decodeUser = S.decode(User);
const encodeUser = S.encode(User);
```

`decodeUser` 의 반환 타입은 `Effect<UserA, ParseError, never>` 다. 즉 검증 실패가 *예외가 아니라 효과의 에러 채널* 로 들어온다. Zod 는 throw 하거나 `.safeParse` 의 discriminated union 을 반환하지만, 두 결과 모두 비동기·의존성·인터럽트와 합성하기 어렵다. Effect Schema 의 결과는 그대로 `Effect.flatMap` / `Effect.catchTag` 로 이어붙는다.

ParseError 는 단일 객체가 아니라 트리 구조(`ParseIssue`)다. `TreeFormatter.formatErrorSync` 또는 `ArrayFormatter.formatErrorSync` 로 사람이 읽을 수 있는 메시지로 환원할 수 있다. 라이브러리 경계에서 유효성 검증 실패를 HTTP 422 로 매핑할 때 이 트리를 *그대로 직렬화* 하면 클라이언트에 위치 기반 에러를 줄 수 있어 form UX 에 유리하다.

## 3. Effect.Service / Layer 와 의존성 합성

Effect 의 의존성 주입은 두 가지 축으로 동작한다. `Context.Tag` 가 *서비스의 식별자*, `Layer<RIn, E, ROut>` 가 *서비스 인스턴스를 만드는 레시피*다. 이는 NestJS 의 모듈 데코레이터·provider 와 비슷하지만, 모든 의존성 누락이 **컴파일 타임에** 감지된다는 점이 결정적이다.

```ts
class HttpClient extends Effect.Service<HttpClient>()("app/HttpClient", {
  effect: Effect.gen(function* () {
    const config = yield* Config;
    return {
      get: (url: string) =>
        Effect.tryPromise({
          try: () => fetch(`${config.baseUrl}${url}`).then((r) => r.json()),
          catch: (cause) => new NetworkError({ cause }),
        }),
    };
  }),
  dependencies: [ConfigLive],
}) {}

class UserRepo extends Effect.Service<UserRepo>()("app/UserRepo", {
  effect: Effect.gen(function* () {
    const http = yield* HttpClient;
    return {
      findById: (id: number) =>
        http.get(`/users/${id}`).pipe(Effect.flatMap(S.decodeUnknown(User))),
    };
  }),
  dependencies: [HttpClient.Default],
}) {}

const App = Layer.mergeAll(UserRepo.Default);

Effect.runPromise(
  Effect.gen(function* () {
    const repo = yield* UserRepo;
    return yield* repo.findById(42);
  }).pipe(Effect.provide(App))
);
```

`Effect.provide(App)` 호출 이후 `R` 가 `never` 로 좁혀지지 않으면 컴파일이 멈춘다. NestJS 는 같은 누락을 부트스트랩 시 런타임 예외(`UnknownDependenciesException`)로 알린다. 빌드 시점 차단이 가져오는 가장 큰 이득은 *마이크로서비스 분리 작업의 안전성* 이다. 어떤 모듈이 어떤 서비스를 요구하는지 타입에 노출되므로, 모듈 추출 시 누락 없이 옮기기 좋다.

## 4. 에러 채널의 트랙 — `Effect.catchTag`

Effect 의 에러 타입 `E` 는 union 으로 누적된다. `Effect.catchTag` 는 클래스 기반 태그(`_tag` 필드)로 union 의 일부만 처리하면서 *남은 에러를 정확히 좁힌다*. 이는 catch-and-rethrow 패턴이 손쉬운 try/catch 와 달리, 어떤 에러를 처리했고 어떤 에러가 남았는지 타입이 강제한다는 뜻이다.

```ts
import { Data } from "effect";

class NetworkError extends Data.TaggedError("NetworkError")<{ cause: unknown }> {}
class ValidationError extends Data.TaggedError("ValidationError")<{ field: string }> {}

const handled = program.pipe(
  Effect.catchTag("NetworkError", (e) =>
    Effect.logWarning(`network down, cause=${String(e.cause)}`).pipe(
      Effect.as("FALLBACK")
    )
  )
);
//          ^? Effect<string, ValidationError, Config>
```

실제 도입 시 함정은 두 가지다. 첫째, 클래스 데코레이터 기반 `@TaggedError` 가 아니라 `Data.TaggedError` 를 쓰는 이유는 *상속 가능한 에러 클래스* 를 만들지 않고 데이터 클래스를 만들기 위해서다. 둘째, `instanceof` 비교 대신 `_tag` 비교를 쓰므로 번들 분리 후에도 비교가 안정적이다.

## 5. Effect.gen 과 control-flow 병행 처리

Effect 는 `Effect.all`, `Effect.forEach`, `Effect.race`, `Effect.timeout`, `Effect.scoped` 등 control-flow 연산자를 generator 와 자연스럽게 합성한다. 다음은 100건의 유저를 *동시성 8* 로 내려받고 60초 타임아웃을 거는 예제다.

```ts
const fetchAll = Effect.gen(function* () {
  const repo = yield* UserRepo;
  return yield* Effect.forEach([1, 2, ..., 100], (id) => repo.findById(id), {
    concurrency: 8,
  });
}).pipe(Effect.timeout("60 seconds"));
```

타임아웃이 발생하면 `TimeoutException` 이 에러 채널에 추가된다. 인터럽트는 Effect 의 *fiber* 가 자동으로 처리하므로, 진행 중이던 fetch 도 중단된다. 이 점이 axios cancel token 이나 AbortController 를 수동으로 흘려보내는 코드와 다르다. fiber 를 명시적으로 `Effect.fork` 하면 각 fiber 의 인터럽트와 자식 자원 해제가 *구조적 동시성(structured concurrency)* 으로 보장된다.

| 패턴 | Promise + AbortSignal | Effect Fiber |
|---|---|---|
| 취소 전파 | 명시적 signal 전달 필요 | 부모 fiber 가 자식까지 자동 |
| 자원 해제 | finally 매크로/try-finally 코드 | `Effect.acquireRelease` 가 보장 |
| 에러 누적 | catch chain 이 단일 | `Cause` 트리에 모두 보존 |
| 병렬성 제어 | Promise.all + 외부 큐 | `concurrency` 옵션 |

## 6. Either-only fp-ts → Effect 마이그레이션의 함정

기존 fp-ts 기반 코드를 Effect 로 옮길 때 자주 마주치는 4가지를 정리한다.

첫째, `Either<E, A>` → `Effect<A, E>` 변환은 `Effect.fromEither` 가 아니라 `Effect.either` 의 *역방향* 임에 주의한다. 같은 함수처럼 보여도 `Effect.either(eff)` 는 효과를 either 로 환원한다.

둘째, `pipe(value, fa, fb)` 스타일이 그대로 컴파일되더라도 *pipeable 메서드* 와 *`.pipe()` 메서드* 가 분리되어 있다. Effect 에서는 `effect.pipe(Effect.map(...))` 처럼 인스턴스 메서드 형태가 권장된다. fp-ts 의 `pipe` 함수를 재사용하면 동작은 하지만 ESLint 규칙이 제대로 잡히지 않는다.

셋째, fp-ts `do-notation` 에서 사용하던 `Do.let("x", ...)` 은 `Effect.gen` 의 `let` 키워드에 해당하지만, generator 안에서는 `let x = yield* …` 로 충분하다. `Do.bindW` 의 width-extension 의미는 union 자동 합산으로 대체된다.

넷째, `TaskEither` 의 인터럽트 부재. axios cancel 을 흘려보내려면 함수마다 signal 인자를 추가해야 했다. Effect 는 fiber 가 자동으로 인터럽트되므로 외부 signal 을 도메인 코드에 노출할 필요가 없다. 라이브러리 경계에서만 `Effect.interruptible` / `Effect.uninterruptible` 로 정책을 명시한다.

## 7. NestJS / tRPC 와의 인터롭

Effect 는 framework agnostic 이지만 실무에서는 기존 IoC 프레임워크와 공존해야 한다. NestJS controller 안에서는 `Effect.runPromise` 로 진입점을 만들고, `@nestjs/common` 의 `HttpException` 으로 매핑하는 *adapter layer* 를 둔다.

```ts
@Controller("users")
class UserController {
  constructor(private readonly app: AppRuntime) {}

  @Get(":id")
  async findOne(@Param("id") id: string): Promise<UserDto> {
    const program = UserRepo.pipe(
      Effect.flatMap((r) => r.findById(Number(id))),
      Effect.catchTag("ParseError", () => Effect.fail(new BadRequestException())),
      Effect.catchTag("NetworkError", () => Effect.fail(new ServiceUnavailableException()))
    );
    return this.app.runPromise(program);
  }
}
```

`AppRuntime` 은 한 번 만든 `ManagedRuntime` 인스턴스를 재사용한다. 매 요청마다 `Effect.runPromise(program.pipe(Effect.provide(App)))` 을 호출하는 것은 layer 초기화 비용이 누적되어 *백프레셔를 망친다*. tRPC 의 경우 procedure 에서 직접 Effect 를 반환하는 게 아니라 마지막에 `runPromise` 로 throw 시키는 방법이 호환성 면에서 안전하다.

## 8. 운영에서 본 trade-off — 도입을 보류할 두 가지 신호

Effect 가 좋은 도구라는 것과 즉시 도입해야 한다는 것은 다른 문제다. 두 가지 신호가 있으면 *도입을 1~2 분기 미룬다*.

첫째, 팀 인원 다수가 RxJS 마저 익숙치 않은 상태. Effect 는 RxJS 보다 더 일관된 모델이지만, generator + 타입 추론을 동시에 다뤄야 하므로 학습 비용이 누적된다. 도입 직후 PR 리뷰 시간이 평균 2배로 늘어나는 사례가 흔하다.

둘째, *런타임 stack trace* 를 디버깅 도구가 아닌 *제품 안정성의 절대 기준* 으로 삼는 조직. Effect 는 fiber 기반이라 V8 stack 이 짧아진다. `Cause` 트리에 더 풍부한 정보가 들어가지만 Sentry 같은 외부 도구가 자동으로 그 트리를 풀어주지 않는다. `Cause.pretty` 를 직접 hook 으로 등록해야 하므로, Sentry SDK 와의 어댑터를 미리 준비하지 않으면 운영 가시성에 손해를 본다.

| 도입 권장도 | 상황 |
|---|---|
| 강력 권장 | 멀티 라이브러리 비동기 합성, 워크플로 엔진, 데이터 파이프라인 |
| 권장 | 신규 백엔드 / monorepo 의 도메인 모듈 |
| 보류 | RxJS 미숙 팀, 강한 Sentry 의존, 12주 이내 출시 압력 |
| 비권장 | 단일 Express handler, 1~2 명의 풀스택 스타트업 초기 |

## 참고

- Effect 공식 문서 — Getting Started, Schema, Layer 가이드
- "Effect: Why it matters" — Michael Arnaldi 2024 keynote (KCDC)
- TC39 Stage 3 Decorators 제안과 `Symbol.metadata` 명세
- fp-ts 3.x 마이그레이션 노트 (effect-ts/effect 리포지토리)