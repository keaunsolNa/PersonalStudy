Notion 원본: https://www.notion.so/3635a06fd6d381f39c6bda80d18dd02e

# TypeScript Effect-TS Schema와 Tagged Error Effect Runtime 패턴

> 2026-05-17 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Effect-TS의 핵심 자료형 `Effect<A, E, R>` 가 표현하는 세 축(success, error, requirement)을 type-level 에서 추적한다
- `@effect/schema` 의 ParseError, Transform, Brand 가 만들어 내는 컴파일/런타임 결합 방식을 익힌다
- tagged error(`Data.TaggedError`) 를 활용해 union error type 을 narrowing 하고 catchTag 로 분기 처리하는 패턴을 적용한다
- Effect runtime (`Layer`, `Context`, `Runtime`) 으로 의존성 주입을 정적 타입과 함께 컴파일 시간에 검증한다

## 1. Effect 자료형의 세 축

대부분의 TS 비동기 라이브러리는 `Promise<A>` 한 축으로 표현한다. Effect-TS는 그것을 세 축으로 확장한다.

```ts
type Effect<A, E, R> = ...; // 성공 A, 실패 E, 요구사항 R
```

`A` 는 성공 시 반환 값, `E` 는 실패 시 union 형 에러, `R` 은 실행에 필요한 의존성(Service)의 집합이다. 세 축이 모두 타입 시스템에 있어, "이 함수는 어떤 에러를 던질 수 있고, 어떤 서비스를 필요로 하는가" 가 컴파일 시점에 보인다.

## 2. Promise vs Effect 비교

Promise 가 가지지 못한 가장 큰 차이는 *에러와 의존성이 타입 정보로 노출된다는 점* 이다. 4년차 백엔드 관점에서 Spring 의 `@Transactional`, `@ControllerAdvice` 가 런타임/AOP로 처리하는 영역을 Effect는 type-level 로 가져온다. 에러는 `E` union, 의존성은 `R` type, 취소는 내장 Fiber.interrupt, 동시성은 Effect.all + structured concurrency, 재시도는 Effect.retry(policy), 자원 해제는 Effect.scoped + acquireRelease, 테스트는 Layer 교체로 다루는 게 Promise 대비 일괄된 장점이다.

## 3. @effect/schema 의 정의-검증-변환

`@effect/schema` 는 Zod 와 유사하지만 *변환(transform)* 과 *디코드/인코드 방향성* 을 명시한다.

```ts
import * as S from "@effect/schema/Schema";

const DateFromString = S.transform(
  S.string, S.DateFromSelf,
  { decode: (s) => new Date(s), encode: (d) => d.toISOString() },
);

const User = S.struct({
  id: S.string,
  email: S.string.pipe(S.pattern(/@/)),
  createdAt: DateFromString,
});

type UserI = S.Schema.Type<typeof User>;     // 디코드 후
type UserE = S.Schema.Encoded<typeof User>;  // 인코드 전
```

`Type` 과 `Encoded` 가 따로 있다는 게 핵심이다. JSON wire format → 도메인 객체 변환을 schema 한 정의로 양방향 다룰 수 있다.

```ts
const decode = S.decodeUnknown(User);
const program = Effect.gen(function* () {
  const u = yield* decode({ id: "u1", email: "a@b", createdAt: "2026-05-17" });
  return u; // UserI
});
```

## 4. Tagged Error 와 catchTag

Effect 의 에러는 union 타입이지만, runtime 에서 어떤 종류인지 식별하려면 *판별 필드* 가 필요하다. `Data.TaggedError` 가 그 boilerplate 를 제거한다.

```ts
class NotFound extends Data.TaggedError("NotFound")<{ id: string }> {}
class Forbidden extends Data.TaggedError("Forbidden")<{ reason: string }> {}

const safe = handler("u1").pipe(
  Effect.catchTag("NotFound", (e) => Effect.succeed({ id: e.id, fallback: true } as any)),
  Effect.catchTag("Forbidden", (e) => Effect.fail(new Error(e.reason))),
);
```

`catchTag` 는 `_tag` 필드를 키로 분기. TS narrowing 이 작동해, 각 핸들러 안에서 `e` 는 정확한 타입이다. 처리한 에러는 union 에서 제거되며, `E = never` 가 되면 그 effect 는 "절대 실패하지 않음" 이 타입에 새겨진다.

## 5. Context, Tag, Layer: 정적 DI

Effect 의 의존성 주입은 `Context` 라는 type-level Map 으로 표현되며 각 의존성은 `Context.Tag` 로 식별된다. 실행 전 의존성 모두를 `Layer` 로 제공해야 한다. 테스트에서는 Layer 만 교체하면 되며, Spring 의 `@Profile("test")` 와 비슷한 동기 부여이나 컴파일 시점에 의존성 누락이 잡힌다는 차이가 결정적이다.

## 6. Brand 타입과 도메인 식별자

Effect Schema 는 nominal typing 을 unique symbol 로 구현하며 runtime 코스트가 없다. zod 도 `.brand()` 를 제공하지만, Effect Schema 의 brand 는 decode 와 결합되어 *경계에서 한 번 검증* 모델을 강제한다.

## 7. 동시성: Fiber 와 structured concurrency

Effect 는 자체 경량 스레드(Fiber) 로 동시성을 관리한다. `Effect.all([...], { concurrency: 2 })` 는 최대 2개 동시 실행 후 다음을 시작한다. Promise.all 은 모두 동시에 시작되므로 호출 빈도를 제어할 수 없는데, Effect 는 구조적으로 가능하다. 취소는 부모 fiber 가 자식 fiber 를 함께 끊는 structured cancellation 이다. resource 보유 효과는 `Effect.acquireRelease` 또는 `Effect.scoped` 로 묶어 scope 가 끝나면 close() 가 예외/취소 상황에서도 보장된다.

## 8. 재시도 정책과 Schedule

```ts
import { Schedule } from "effect";
const policy = Schedule.exponential("100 millis").pipe(
  Schedule.intersect(Schedule.recurs(5)),
  Schedule.whileInput((e: HttpError) => e.status >= 500),
);
const safe = getUser("u1").pipe(Effect.retry(policy));
```

지수 백오프 100ms~, 최대 5회, 5xx 에서만 재시도. Spring Retry 의 `@Retryable` 비슷한 선언이나, *정책이 일급 값* 이라 합성/테스트가 쉽다. `Schedule.jittered(policy)` 로 thundering herd 회피용 jitter 도 한 줄로 추가한다.

## 9. 마이그레이션 가이드

Effect 를 한 번에 적용하기보다는 *경계 모듈* 부터 시작하는 게 현실적. 기존 `async function` 을 `Effect.tryPromise` 로 감싸 union 에러로 흡수. 기존 콜백 API는 `Effect.async` 로, 동기 throw 가능 코드는 `Effect.try` 로 감싸 Custom Tagged Error 로 normalize. Schema 로 입력 validation 만 도입해도 Spring DTO 검증과 비슷한 가치가 즉시 나온다. 부분 도입 단계에서 가장 큰 함정은 *Effect 안에서 await 를 쓰면 안 된다* 는 점. `Effect.gen` 안에서는 `yield*` 만 사용. 런타임 비용은 Promise 대비 약 1.5~2배 (단순 micro benchmark 기준). 대부분 워크로드에서 무시 가능한 수준이며, 얻는 type-safety 와 cancellation/retry 의 일급 표현이 ROI가 크다.

## 참고

- Effect 공식 문서: https://effect.website
- @effect/schema 가이드: https://effect.website/docs/schema/introduction
- Data.TaggedError 와 catchTag API: https://effect.website/docs/error-management/expected-errors
- Fiber / Schedule / Layer 문서: https://effect.website/docs/concurrency/fibers
- Effect vs Promise 벤치마크: https://github.com/Effect-TS/effect/tree/main/benchmark
