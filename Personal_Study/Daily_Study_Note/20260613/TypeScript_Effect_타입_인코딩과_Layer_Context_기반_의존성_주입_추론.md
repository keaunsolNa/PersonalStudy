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

// 성공: number, 에러: never, 요구사항: never
const pure: Effect.Effect<number> = Effect.succeed(42);

// 성공: never, 에러: Error, 요구사항: never
const failed: Effect.Effect<never, Error> = Effect.fail(new Error("boom"));
```

`R`이 `never`라는 것은 "더 이상 외부에서 주입할 의존성이 없다 = 실행 가능"을 의미한다. `Effect.runPromise`는 시그니처상 `R = never`인 Effect만 받으므로, 의존성이 남아 있으면 컴파일 단계에서 실행을 막는다. 이 점이 런타임 DI 컨테이너(Spring 등)와 결정적으로 다른 지점이다 — 누락된 빈은 런타임 예외가 아니라 타입 에러가 된다.

## 2. Context.Tag — 서비스의 타입 수준 식별자

서비스는 `Context.Tag`로 선언한다. Tag는 런타임 키(고유 문자열)와 컴파일 타임 타입(서비스 인터페이스)을 한 객체에 묶은 것이다.

```ts
import { Context, Effect } from "effect";

interface Random {
  readonly next: Effect.Effect<number>;
}

// 첫 번째 제네릭은 self(태그 식별자), 두 번째는 서비스 shape
class RandomTag extends Context.Tag("app/Random")<RandomTag, Random>() {}

const program = Effect.gen(function* () {
  const random = yield* RandomTag; // 여기서 R 채널에 RandomTag가 더해진다
  const n = yield* random.next;
  return n > 0.5 ? "head" : "tail";
});
// program: Effect<string, never, RandomTag>
```

`yield* RandomTag`를 호출하는 순간, 제너레이터의 반환 Effect의 `R` 채널에 `RandomTag`가 누적된다. 두 개 이상의 서비스를 사용하면 `R`은 인터섹션(`RandomTag & LoggerTag`)으로 합쳐진다. 즉 `R`은 "필요한 능력들의 곱"이다.

## 3. 의존성 제공과 R 채널 소거

`Effect.provideService`로 구체 구현을 주입하면 해당 Tag가 `R`에서 제거된다.

```ts
const runnable = program.pipe(
  Effect.provideService(RandomTag, { next: Effect.sync(() => Math.random()) })
);
// runnable: Effect<string, never, never>  ← R이 never로 줄었다
Effect.runPromise(runnable); // OK
```

이 소거는 타입 수준에서 정확히 추적된다. `provideService`의 시그니처는 개념적으로 `Effect<A, E, R> -> Tag<I, S> -> Effect<A, E, Exclude<R, I>>`다. `Exclude`로 제공된 태그만 골라내므로, 여러 서비스 중 일부만 주입하면 나머지는 그대로 `R`에 남아 컴파일러가 "아직 X가 필요하다"고 알려준다. 디버깅 시 `runPromise` 호출부에 빨간 줄이 뜨면, 마우스를 올려 남아 있는 `R` 타입을 읽는 것만으로 누락된 의존성을 식별할 수 있다.

## 4. Layer — 의존성 그래프의 조립

서비스가 다른 서비스에 의존하면 `provideService`로는 부족하다. `Layer<ROut, E, RIn>`는 "RIn을 입력받아 ROut을 만드는 생성 레시피"이며, 이를 합성해 그래프를 세운다.

```ts
import { Context, Effect, Layer } from "effect";

class Config extends Context.Tag("Config")<Config, { readonly url: string }>() {}
class Db extends Context.Tag("Db")<Db, { readonly query: (s: string) => Effect.Effect<string> }>() {}

// Config를 요구하고 Db를 만들어 내는 Layer
const DbLive = Layer.effect(
  Db,
  Effect.gen(function* () {
    const cfg = yield* Config;
    return { query: (s) => Effect.succeed(`${cfg.url}:${s}`) };
  })
); // Layer<Db, never, Config>

const ConfigLive = Layer.succeed(Config, { url: "postgres://x" }); // Layer<Config, never, never>

// Layer.provide로 의존을 채워 RIn을 소거
const AppLive = DbLive.pipe(Layer.provide(ConfigLive)); // Layer<Db, never, never>
```

`Layer.provide(ConfigLive)`는 `DbLive`의 `RIn`에서 `Config`를 제거한다. 최종 `AppLive`는 `RIn = never`이므로 자급자족하는 그래프다. Layer는 메모이제이션되어, 동일 Layer가 그래프에서 여러 번 참조돼도 리소스(커넥션 풀 등)는 한 번만 생성된다 — 이는 `Layer.scoped`와 결합 시 획득/해제가 정확히 한 쌍이 되도록 보장한다.

## 5. 에러 채널과 catchTag 추론

에러를 태그드 유니온으로 모델링하면 `catchTag`가 어떤 에러가 처리됐는지 추론해 `E` 채널을 좁힌다.

```ts
import { Data, Effect } from "effect";

class NotFound extends Data.TaggedError("NotFound")<{ id: string }> {}
class Timeout extends Data.TaggedError("Timeout")<{ ms: number }> {}

const fetchUser = (id: string): Effect.Effect<string, NotFound | Timeout> =>
  id === "1" ? Effect.succeed("Alice") : Effect.fail(new NotFound({ id }));

const recovered = fetchUser("2").pipe(
  Effect.catchTag("NotFound", (e) => Effect.succeed(`guest:${e.id}`))
);
// recovered: Effect<string, Timeout, never>  ← NotFound가 E에서 제거됨
```

`catchTag("NotFound", ...)`는 `E` 유니온에서 `_tag`가 `"NotFound"`인 멤버만 골라 핸들러로 보내고, 남은 `Timeout`만 `E`에 유지한다. 모든 태그를 처리하면 `E`는 `never`가 되어 "무오류" 계산이 됨이 타입으로 증명된다. 이 패턴은 try/catch가 잡은 예외 타입을 `unknown`으로밖에 알 수 없는 한계를 정면으로 해결한다.

## 6. Promise/콜백 통합과 인터럽션

기존 Promise 코드는 `Effect.tryPromise`로 감싸 에러 채널을 명시한다.

```ts
const readFile = (path: string) =>
  Effect.tryPromise({
    try: () => fetch(path).then((r) => r.text()),
    catch: (cause) => new Timeout({ ms: 0 }), // unknown -> 도메인 에러로 매핑
  }); // Effect<string, Timeout>
```

Effect는 협력적 인터럽션을 내장한다. `Effect.race`, `Effect.timeout` 등으로 경쟁시키면 진 쪽은 안전하게 중단되고, `Effect.acquireRelease`로 잡은 리소스의 해제 로직이 인터럽션 시에도 실행된다. Promise에는 표준 취소가 없어 `AbortController`를 수동 배선해야 하는 것과 대비된다.

## 7. 성능과 비용 trade-off

| 항목 | Promise/async | Effect |
|---|---|---|
| 에러 타입 추적 | 불가(`any`/`unknown`) | `E` 채널로 정적 추적 |
| 의존성 주입 | 런타임 컨테이너/수동 | `R` 채널 + Layer, 컴파일 검증 |
| 취소/리소스 안전 | 수동(`AbortController`) | 인터럽션·Scope 내장 |
| 학습 곡선 | 낮음 | 높음(제너레이터·Layer 개념) |
| 런타임 오버헤드 | 거의 없음 | 파이버 스케줄러로 소폭 존재 |
| 타입 추론 부하 | 낮음 | 깊은 제네릭으로 tsserver 부하 증가 |

실측 관점에서 Effect의 런타임 오버헤드는 대부분의 I/O 바운드 워크로드에서 무시할 수준이지만, 마이크로 벤치(순수 CPU 루프)에서는 파이버 스케줄링 비용이 드러난다. 더 체감되는 비용은 **컴파일러 부하**다. Layer 그래프가 커지면 `R` 인터섹션과 `Exclude` 연산이 누적되어 IDE 자동완성 지연이 생길 수 있고, 이때는 그래프를 모듈 경계로 분할하거나 중간 Layer에 명시적 타입 주석을 달아 추론 깊이를 끊는 것이 효과적이다.

## 8. Effect.gen vs pipe 스타일과 추론 차이

Effect 코드는 두 스타일로 쓴다. `pipe`/연산자 체인은 함수형 합성이고, `Effect.gen`은 제너레이터로 명령형처럼 읽힌다. 둘은 동일한 타입을 만들지만 추론 부하와 가독성이 다르다.

```ts
// pipe 스타일 — 합성이 명시적이나 중첩이 깊어지면 읽기 어려움
const a = RandomTag.pipe(
  Effect.flatMap((r) => r.next),
  Effect.map((n) => n * 2),
  Effect.flatMap((n) => Effect.log(`value=${n}`).pipe(Effect.as(n)))
);

// gen 스타일 — async/await처럼 읽히고 R/E 채널은 자동 누적
const b = Effect.gen(function* () {
  const r = yield* RandomTag;
  const n = (yield* r.next) * 2;
  yield* Effect.log(`value=${n}`);
  return n;
});
```

`gen` 스타일은 `yield*` 한 줄마다 `R`·`E` 채널이 자동으로 합쳐져 중간 타입 주석이 거의 필요 없고, 분기·반복이 많은 로직에서 가독성이 압도적이다. 반면 단순한 1~2단계 변환은 `pipe`가 더 간결하다. 실무에서는 도메인 워크플로는 `gen`, 작은 어댑터/유틸은 `pipe`로 섞어 쓰는 것이 일반적이다.

## 9. 테스트 — TestClock과 목 Layer

Effect의 큰 실용적 이점은 결정적 테스트다. 시간 의존 로직(`Effect.sleep`, 타임아웃, 재시도 백오프)은 `TestClock`으로 가상 시간을 수동 전진시켜, 실제로 기다리지 않고 검증한다.

```ts
import { Effect, TestClock, TestContext, Duration, Fiber } from "effect";

const delayed = Effect.sleep(Duration.seconds(60)).pipe(Effect.as("done"));

const test = Effect.gen(function* () {
  const fiber = yield* Effect.fork(delayed);
  yield* TestClock.adjust(Duration.seconds(60)); // 가상 시간 60초 전진
  const result = yield* Fiber.join(fiber);
  return result; // "done" — 실제 대기 없이 즉시
}).pipe(Effect.provide(TestContext.TestContext));
```

서비스 의존성도 `Layer.succeed(Tag, mockImpl)`로 만든 목 Layer를 `provide`하면, 운영 코드와 동일한 프로그램을 외부 I/O 없이 실행할 수 있다. `R` 채널이 컴파일 타임에 "어떤 서비스가 필요한지"를 강제하므로, 테스트에서 목을 빠뜨리면 실행 자체가 타입 에러로 막혀 누락된 스텁을 사전에 잡아 준다.

## 10. 도입 판단 기준

Effect는 "에러와 의존성을 타입으로 강제하고 싶은" 복잡한 백엔드/CLI에 적합하다. 도메인 에러가 많고 복구 분기가 세밀할수록, 또 의존성 그래프가 깊고 테스트에서 구현을 갈아끼워야 할수록 이득이 커진다. 반대로 단순 스크립트나 짧은 수명의 핸들러에서는 학습 비용 대비 효용이 낮다. 점진 도입 시에는 경계(엔드포인트 핸들러 등)에서 `runPromise`로 변환해 기존 Promise 세계와 접합하고, 핵심 도메인 로직부터 Effect로 감싸 나가는 방식이 안전하다. 테스트에서는 운영 Layer 대신 `Layer.succeed`로 만든 목 Layer를 `provide`해 동일 프로그램을 결정적으로 실행할 수 있다.

## 참고

- Effect 공식 문서: https://effect.website/docs/introduction
- Effect Context & Layer 가이드: https://effect.website/docs/requirements-management/layers/
- Effect Error Management: https://effect.website/docs/error-management/expected-errors/
- "Functional effect systems" — ZIO 논문/문서(Effect의 설계 기원)
