Notion 원본: https://app.notion.com/p/38c5a06fd6d381a7bf9fc71b7762ea23

# TypeScript Effect 런타임과 Layer 의존성 주입 및 Tagged Error 처리

> 2026-06-27 신규 주제 · 확장 대상: TypeScript 런타임 검증·함수형 라이브러리

## 학습 목표

- `Effect<A, E, R>` 세 타입 파라미터(성공·에러·요구사항)의 의미와 추적 방식을 안다.
- try/catch 대신 Tagged Error로 에러를 타입 레벨에서 합산·분기한다.
- `Context.Tag`와 `Layer`로 의존성을 선언·합성·주입한다.
- 동기 throw·Promise 대비 인터럽션·리소스 안전성 차이를 설명한다.

## 1. Effect 타입 — 부수효과를 값으로 기술

Effect는 부수효과를 즉시 실행하지 않고 "어떻게 실행할지"를 기술한 불변 자료구조로 표현한다. `A`는 성공값, `E`는 에러, `R`은 의존성이다.

```typescript
import { Effect } from "effect";
const computed: Effect.Effect<number> = Effect.sync(() => 1 + 2);
const result = Effect.runSync(computed); // 3
```

Promise가 생성 즉시 실행되는 것과 달리 Effect는 명시적으로 실행하기 전까지 아무 일도 일어나지 않는다(지연 평가).

## 2. Tagged Error — 에러를 타입으로 합산

```typescript
import { Effect, Data } from "effect";
class NetworkError extends Data.TaggedError("NetworkError")<{ readonly status: number }> {}
class ParseError extends Data.TaggedError("ParseError")<{ readonly raw: string }> {}

const fetchUser = (id: string): Effect.Effect<{ name: string }, NetworkError | ParseError> =>
  Effect.gen(function* () {
    const res = yield* Effect.tryPromise({ try: () => fetch(`/users/${id}`), catch: () => new NetworkError({ status: 500 }) });
    if (!res.ok) return yield* Effect.fail(new NetworkError({ status: res.status }));
    const text = yield* Effect.promise(() => res.text());
    return yield* Effect.try({ try: () => JSON.parse(text) as { name: string }, catch: () => new ParseError({ raw: text }) });
  });
```

`catchTag`로 처리한 태그는 에러 채널에서 제거된다. "처리하면 타입에서 사라진다"가 핵심이다.

## 3. Context.Tag — 의존성을 인터페이스로 선언

```typescript
import { Effect, Context } from "effect";
class Clock extends Context.Tag("Clock")<Clock, { readonly now: () => Effect.Effect<number> }>() {}
const program = Effect.gen(function* () { const clock = yield* Clock; return yield* clock.now(); });
```

의존성이 `R` 채널에 드러나 숨은 전역 싱글톤이 사라진다.

## 4. Layer — 의존성 그래프 구성

```typescript
import { Layer, Effect } from "effect";
const ClockLive = Layer.succeed(Clock, { now: () => Effect.sync(() => Date.now()) });
const ClockTest = Layer.succeed(Clock, { now: () => Effect.succeed(1_000) });
const runnable = program.pipe(Effect.provide(ClockLive));
```

같은 `Tag`에 다른 `Layer`를 갈아 끼우는 것만으로 구현을 교체한다.

## 5. 인터럽션·리소스 안전성 — Promise 대비

Effect 런타임은 협조적 인터럽션을 지원하고 `acquireRelease`로 인터럽트 시점에도 리소스 해제를 보장한다.

```typescript
const job = Effect.scoped(Effect.gen(function* () {
  const h = yield* Effect.acquireRelease(Effect.sync(() => openHandle()), (h) => Effect.sync(() => h.close()));
  return yield* Effect.promise(() => h.read());
})).pipe(Effect.timeout("2 seconds"));
```

## 6. 트레이드오프와 도입 전략

| 항목 | try/catch+Promise | fp-ts | Effect |
| --- | --- | --- | --- |
| 에러 타입 추적 | 없음 | 있음 | 있음(소거) |
| 의존성 추적 | 없음 | 수동 | R 채널+Layer |
| 인터럽션/리소스 | 수동 | 제한적 | 런타임 보장 |
| 학습 곡선 | 낮음 | 중간 | 높음 |

도메인 서비스 계층만 Effect로 쓰고 가장자리에서 `runPromise`로 접합하는 점진 도입이 현실적이다.

## 7. 동시성·재시도·스케줄

```typescript
import { Effect, Schedule, Duration } from "effect";
const policy = Schedule.exponential(Duration.millis(100)).pipe(Schedule.jittered, Schedule.compose(Schedule.recurs(3)));
const robust = fetchUser("42").pipe(Effect.retry({ schedule: policy, while: (e) => e._tag === "NetworkError" }));
const many = Effect.all(ids.map((id) => fetchUser(id)), { concurrency: 4 });
```

`Schedule`은 합성 가능한 값이라 지수 백오프+지터+최대 횟수를 조각으로 조립한다.

## 8. 테스트 — TestClock과 결정론적 검증

`TestClock`으로 가상 시간을 전진시켜 실제 대기 없이 타임아웃·재시도 로직을 결정론적으로 검증한다. Layer 기반 DI와 결합하면 외부 자원 없이 복잡한 비동기 시나리오를 안정적으로 테스트한다.

## 참고

- Effect 공식 문서 — The Effect Type, Error Management, Requirements Management
- Effect API Reference — Effect.gen, Layer.provide, acquireRelease, Effect.timeout
- Data.TaggedError 및 catchTag/catchTags 가이드
