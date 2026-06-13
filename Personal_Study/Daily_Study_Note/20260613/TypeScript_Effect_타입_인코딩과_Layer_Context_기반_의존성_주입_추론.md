Notion 원본: https://www.notion.so/37e5a06fd6d3810287b3d99655e72359

# TypeScript Effect 타입 인코딩과 Layer · Context 기반 의존성 주입 추론

> 2026-06-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `Effect<A, E, R>`의 세 타입 채널(성공·에러·요구사항)이 무엇을 인코딩하는지 구분해 사용한다
- `Context.Tag`로 서비스를 식별하고 `R` 채널에 의존성이 누적·소거되는 과정을 추론한다
- `Layer<ROut, E, RIn>`로 의존성 그래프를 조립하고 `Effect.provide`가 `R`을 `never`로 줄이는 메커니즘을 설명한다
- 에러 채널을 태그드 유니온으로 모델링하고 `catchTag` 추론과 결합해 부분 복구를 구현한다

## 1. 세 개의 타입 채널

Effect의 핵심 타입은 `Effect<A, E, R>` 단 하나다. `A`는 성공 값, `E`는 실패 시 발생 가능한 에러의 유니온, `R`은 이 계산을 실행하기 위해 환경(Context)에서 충족되어야 하는 의존성의 교집합이다. Promise가 성공 타입 하나만 정적으로 추적하고 에러는 `any`로 흘려보내는 것과 달리, Effect는 에러와 의존성까지 타입으로 끌어올린다.

```ts
import { Effect } from "effect";

const pure: Effect.Effect<number> = Effect.succeed(42);
const failed: Effect.Effect<never, Error> = Effect.fail(new Error("boom"));
```

`R`이 `never`라는 것은 더 이상 외부에서 주입할 의존성이 없다(= 실행 가능)를 의미한다. `Effect.runPromise`는 `R = never`인 Effect만 받으므로, 의존성이 남아 있으면 컴파일 단계에서 실행을 막는다. 이 점이 런타임 DI 컨테이너와 결정적으로 다른 지점이다 — 누락된 빈은 런타임 예외가 아니라 타입 에러가 된다.

## 2. Context.Tag — 서비스의 타입 수준 식별자

서비스는 `Context.Tag`로 선언한다. Tag는 런타임 키와 컴파일 타임 타입을 한 객체에 묶은 것이다.

```ts
import { Context, Effect } from "effect";

interface Random { readonly next: Effect.Effect<number>; }
class RandomTag extends Context.Tag("app/Random")<RandomTag, Random>() {}

const program = Effect.gen(function* () {
  const random = yield* RandomTag; // R 채널에 RandomTag가 더해진다
  const n = yield* random.next;
  return n > 0.5 ? "head" : "tail";
});
```

`yield* RandomTag`를 호출하는 순간 R 채널에 `RandomTag`가 누적된다. 두 개 이상 서비스를 쓰면 R은 인터섹션으로 합쳐진다. 즉 R은 필요한 능력들의 곱이다.

## 3. 의존성 제공과 R 채널 소거

`Effect.provideService`로 구체 구현을 주입하면 해당 Tag가 R에서 제거된다.

```ts
const runnable = program.pipe(
  Effect.provideService(RandomTag, { next: Effect.sync(() => Math.random()) })
);
// runnable: Effect<string, never, never>  ← R이 never로 줄었다
Effect.runPromise(runnable);
```

시그니처는 개념적으로 `Effect<A,E,R> -> Tag<I,S> -> Effect<A,E,Exclude<R,I>>`다. 일부만 주입하면 나머지가 R에 남아 컴파일러가 누락을 알려준다.

## 4. Layer — 의존성 그래프의 조립

`Layer<ROut, E, RIn>`는 RIn을 입력받아 ROut을 만드는 생성 레시피다.

```ts
import { Context, Effect, Layer } from "effect";

class Config extends Context.Tag("Config")<Config, { readonly url: string }>() {}
class Db extends Context.Tag("Db")<Db, { readonly query: (s: string) => Effect.Effect<string> }>() {}

const DbLive = Layer.effect(Db, Effect.gen(function* () {
  const cfg = yield* Config;
  return { query: (s) => Effect.succeed(`${cfg.url}:${s}`) };
})); // Layer<Db, never, Config>

const ConfigLive = Layer.succeed(Config, { url: "postgres://x" });
const AppLive = DbLive.pipe(Layer.provide(ConfigLive)); // Layer<Db, never, never>
```

`Layer.provide(ConfigLive)`는 DbLive의 RIn에서 Config를 제거한다. 최종 AppLive는 자급자족 그래프다. Layer는 메모이제이션되어 여러 번 참조돼도 리소스는 한 번만 생성된다.

## 5. 에러 채널과 catchTag 추론

```ts
import { Data, Effect } from "effect";

class NotFound extends Data.TaggedError("NotFound")<{ id: string }> {}
class Timeout extends Data.TaggedError("Timeout")<{ ms: number }> {}

const fetchUser = (id: string): Effect.Effect<string, NotFound | Timeout> =>
  id === "1" ? Effect.succeed("Alice") : Effect.fail(new NotFound({ id }));

const recovered = fetchUser("2").pipe(
  Effect.catchTag("NotFound", (e) => Effect.succeed(`guest:${e.id}`))
); // Effect<string, Timeout, never>
```

`catchTag`는 E 유니온에서 해당 태그만 골라 처리하고 나머지는 유지한다. 모든 태그를 처리하면 E는 never가 되어 무오류임이 타입으로 증명된다. try/catch가 예외 타입을 `unknown`으로밖에 모르는 한계를 해결한다.

## 6. Promise/콜백 통합과 인터럽션

```ts
const readFile = (path: string) =>
  Effect.tryPromise({
    try: () => fetch(path).then((r) => r.text()),
    catch: (cause) => new Timeout({ ms: 0 }),
  });
```

Effect는 협력적 인터럽션을 내장한다. `Effect.race`/`timeout`으로 경쟁시키면 진 쪽은 안전하게 중단되고 `acquireRelease`로 잡은 리소스는 인터럽션 시에도 해제된다.

## 7. 성능과 비용 trade-off

| 항목 | Promise/async | Effect |
|---|---|---|
| 에러 타입 추적 | 불가(any/unknown) | E 채널로 정적 추적 |
| 의존성 주입 | 런타임 컨테이너/수동 | R 채널 + Layer, 컴파일 검증 |
| 취소/리소스 안전 | 수동(AbortController) | 인터럽션·Scope 내장 |
| 학습 곡선 | 낮음 | 높음 |
| 런타임 오버헤드 | 거의 없음 | 파이버 스케줄러로 소폭 |
| 타입 추론 부하 | 낮음 | 깊은 제네릭으로 tsserver 부하 증가 |

Effect의 런타임 오버헤드는 대부분의 I/O 바운드에서 무시할 수준이나 마이크로 벤치에서는 파이버 스케줄링 비용이 드러난다. 더 체감되는 비용은 컴파일러 부하다. Layer 그래프가 커지면 R 인터섹션과 Exclude가 누적되어 IDE 지연이 생길 수 있고, 모듈 분할이나 중간 Layer 타입 주석으로 추론 깊이를 끊는다.

## 8. Effect.gen vs pipe 스타일과 추론 차이

```ts
const b = Effect.gen(function* () {
  const r = yield* RandomTag;
  const n = (yield* r.next) * 2;
  yield* Effect.log(`value=${n}`);
  return n;
});
```

`gen` 스타일은 yield* 한 줄마다 R·E 채널이 자동 합쳐져 중간 주석이 거의 불필요하고 분기·반복이 많은 로직에서 가독성이 압도적이다. 단순 변환은 `pipe`가 간결하다. 보통 도메인 워크플로는 gen, 작은 어댑터는 pipe로 섞어 쓴다.

## 9. 테스트 — TestClock과 목 Layer

```ts
import { Effect, TestClock, TestContext, Duration, Fiber } from "effect";

const delayed = Effect.sleep(Duration.seconds(60)).pipe(Effect.as("done"));
const test = Effect.gen(function* () {
  const fiber = yield* Effect.fork(delayed);
  yield* TestClock.adjust(Duration.seconds(60)); // 가상 시간 전진
  return yield* Fiber.join(fiber); // "done" — 실제 대기 없이 즉시
}).pipe(Effect.provide(TestContext.TestContext));
```

시간 의존 로직은 TestClock으로 가상 시간을 전진시켜 즉시 검증한다. 서비스 의존성도 `Layer.succeed`로 만든 목 Layer를 provide하면 외부 I/O 없이 동일 프로그램을 실행할 수 있고, R 채널이 누락된 목을 컴파일 타임에 잡아준다.

## 10. 도입 판단 기준

Effect는 에러와 의존성을 타입으로 강제하고 싶은 복잡한 백엔드/CLI에 적합하다. 도메인 에러가 많고 복구 분기가 세밀할수록, 의존성 그래프가 깊고 테스트에서 구현을 갈아끼워야 할수록 이득이 크다. 단순 스크립트나 짧은 핸들러는 학습 비용 대비 효용이 낮다. 점진 도입 시 경계에서 runPromise로 기존 Promise 세계와 접합하고 핵심 도메인부터 Effect로 감싼다.

## 참고

- Effect 공식 문서: https://effect.website/docs/introduction
- Effect Context & Layer: https://effect.website/docs/requirements-management/layers/
- Effect Error Management: https://effect.website/docs/error-management/expected-errors/
- ZIO 논문/문서 (Effect 설계 기원)
