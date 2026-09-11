Notion 원본: https://app.notion.com/p/3d85a06fd6d3815398e4e2d1a3026358?pvs=204

# TypeScript 비동기 이터레이터 타이핑과 ReadableStream 제네릭 및 스트림 파이프라인 설계

> 2026-09-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `Iterator`/`AsyncIterator` 3-파라미터 제네릭과 TS 5.6 의 `IteratorObject` 계층을 구분해 시그니처를 읽는다
- `async function*` 의 yield·return·next 타입 추론 규칙을 근거로 API 시그니처를 직접 설계한다
- Web Streams 제네릭과 `@types/node`/DOM lib 충돌을 진단하고 런타임 중립 어댑터를 작성한다
- Variadic Tuple Types 로 타입이 전파되는 스트림 파이프라인을 합성하고 백프레셔·자원 정리를 타이핑한다

## 1. Iterator / AsyncIterator 타입 계층

TypeScript 의 이터레이터 타입은 `lib.es2015.iterable.d.ts` 와 `lib.es2018.asynciterable.d.ts` 에 정의된 3-파라미터 제네릭이다. `Iterator<T, TReturn, TNext>` 에서 `T` 는 `yield` 로 흘러나오는 값, `TReturn` 은 `done: true` 일 때 실리는 값, `TNext` 는 호출자가 `next(v)` 로 **밀어 넣는** 값이다. Java 의 `Iterator<E>` 가 단일 타입 파라미터만 갖는 것과 대비하면, JS 이터레이터는 사실상 코루틴 프로토콜이라 양방향 채널의 타입을 모두 표현해야 한다는 점이 다르다.

```ts
interface IteratorYieldResult<TYield> { done?: false; value: TYield }
interface IteratorReturnResult<TReturn> { done: true; value: TReturn }

interface Iterator<T, TReturn = any, TNext = any> {
  next(...args: [] | [TNext]): IteratorResult<T, TReturn>;
  return?(value?: TReturn): IteratorResult<T, TReturn>;
  throw?(e?: any): IteratorResult<T, TReturn>;
}

interface AsyncIterator<T, TReturn = any, TNext = any> {
  next(...args: [] | [TNext]): Promise<IteratorResult<T, TReturn>>;
  return?(value?: TReturn | PromiseLike<TReturn>): Promise<IteratorResult<T, TReturn>>;
  throw?(e?: any): Promise<IteratorResult<T, TReturn>>;
}

interface AsyncIterable<T> { [Symbol.asyncIterator](): AsyncIterator<T> }
interface AsyncIterableIterator<T> extends AsyncIterator<T> {
  [Symbol.asyncIterator](): AsyncIterableIterator<T>;
}
```

`next(...args: [] | [TNext])` 라는 가변 튜플 시그니처가 핵심이다. 인자를 아예 안 주는 호출과 `TNext` 하나를 주는 호출을 모두 허용하기 위한 트릭인데, 덕분에 `TNext = undefined` 여도 `it.next()` 가 컴파일된다. `AsyncIterable<T>` 는 `TReturn`/`TNext` 를 노출하지 않기 때문에, 함수 파라미터를 `AsyncIterable<T>` 로 받으면 반환값·주입값 타입은 통째로 소실된다. 이것이 실무에서 제네레이터의 `return` 값을 잃어버리는 가장 흔한 원인이다.

TypeScript 5.6 은 여기에 두 계층을 추가했다. `IteratorObject<T, TReturn, TNext>` / `AsyncIteratorObject<T, TReturn, TNext>` 는 **`Iterator.prototype` 을 상속한 실제 객체**를 가리키고, `BuiltinIterator`(그리고 `BuiltinAsyncIterator`)는 `Map.prototype.keys()` 처럼 런타임이 제공하는 내장 이터레이터의 반환 타입 별칭이다. 구분의 목적은 명확하다 — TC39 Iterator Helpers 의 `map`/`filter` 는 `Iterator.prototype` 에 올라간 메서드라, 직접 손으로 만든 `{ next() {...} }` 리터럴에는 존재하지 않는다. 그래서 헬퍼 메서드는 `IteratorObject` 쪽에만 선언되고, 순수 프로토콜 구현체는 여전히 `Iterator` 로 남는다.

```ts
// lib.esnext.iterator.d.ts 의 형태 (요약)
interface IteratorObject<T, TReturn = unknown, TNext = unknown>
  extends Iterator<T, TReturn, TNext> {
  map<U>(cb: (value: T, index: number) => U): IteratorObject<U, undefined, unknown>;
  filter<S extends T>(p: (v: T, i: number) => v is S): IteratorObject<S, undefined, unknown>;
  take(limit: number): IteratorObject<T, undefined, unknown>;
  drop(count: number): IteratorObject<T, undefined, unknown>;
  flatMap<U>(cb: (v: T, i: number) => Iterator<U> | Iterable<U>): IteratorObject<U, undefined, unknown>;
  toArray(): T[];
  [Symbol.iterator](): IteratorObject<T, TReturn, TNext>;
}
```

헬퍼의 반환 타입에서 `TReturn` 이 항상 `undefined` 로 고정되는 점을 눈여겨봐야 한다. 헬퍼를 한 번 통과하면 원본 제네레이터의 `return` 값은 스펙상 버려지기 때문이다. 실무에서 "집계 결과를 return 으로 돌려주는 제네레이터"를 설계했다면 `.map()` 을 끼우는 순간 그 값이 사라지고, 타입도 정확히 그렇게 말해준다.

| 타입 | 제네릭 파라미터 | 헬퍼 메서드 | 대표 출처 |
| --- | --- | --- | --- |
| `Iterator<T, TReturn, TNext>` | 3개 | 없음 | 직접 구현한 객체 리터럴 |
| `IterableIterator<T>` | 1개 (5.6+ 에선 3개) | 없음 | 레거시 제네레이터 반환 타입 |
| `IteratorObject<T, TReturn, TNext>` | 3개 | 있음 | `Iterator.from()`, 헬퍼 결과 |
| `BuiltinIterator<T, TReturn>` | 2개 | 있음 | `Map#keys()`, `Array#values()` |
| `AsyncGenerator<T, TReturn, TNext>` | 3개 | 없음(제안 단계) | `async function*` |

## 2. `async function*` 의 반환 타입 추론 규칙

`async function*` 의 추론 결과는 `AsyncGenerator<TYield, TReturn, TNext>` 이며, 세 파라미터가 각각 다른 근거로 결정된다. `TYield` 는 함수 본문의 모든 `yield` 표현식 피연산자의 union 을 `Awaited<>` 로 감싼 값이다 — 비동기 제네레이터에서 `yield Promise.resolve(1)` 은 `number` 로 평탄화된다(동기 제네레이터라면 `Promise<number>` 가 그대로 남는다). `TReturn` 은 `return` 문들의 union 이고, 명시적 `return` 이 없으면 `void` 다. 그런데 `TNext` 는 **본문에서 역추론되지 않는다.** `const v = yield x` 의 `v` 타입은 추론 대상이 아니라 문맥 타입에서 내려오는 값이라, 명시적 타입 어노테이션이 없으면 컴파일러는 `unknown`(엄밀히는 `any`/`unknown`, 5.x 기준 `unknown`)을 넣는다. 실무에서 대부분 `undefined` 나 `unknown` 으로 보이는 이유가 이것이다.

```ts
// 추론: AsyncGenerator<number, string, unknown>
async function* counter(limit: number) {
  for (let i = 0; i < limit; i++) {
    await new Promise((r) => setTimeout(r, 1));
    yield i;
  }
  return "done";
}

// TNext 를 쓰려면 명시적 어노테이션이 필수
async function* controllable(): AsyncGenerator<number, void, "stop" | "go"> {
  let i = 0;
  while (true) {
    const cmd = yield i++;   // cmd: "stop" | "go"
    if (cmd === "stop") return;
  }
}

const g = controllable();
await g.next("go");   // 첫 next 의 인자는 스펙상 버려진다
await g.next("stop"); // OK
```

여기에 두 가지 함정이 있다. 첫째, **첫 `next()` 의 인자는 런타임에서 무시된다.** 제네레이터가 아직 첫 `yield` 에 도달하지 않았으므로 받을 자리가 없는데, 타입 시스템은 이를 구분하지 못해 `next("go")` 를 그냥 통과시킨다. 둘째, `for await...of` 는 **항상 `next()` 를 인자 없이 호출한다.** 즉 `TNext` 가 `"stop" | "go"` 처럼 `undefined` 를 포함하지 않는 타입이어도 for-await-of 는 조용히 `undefined` 를 주입한다. 타입상 `cmd` 는 `"stop" | "go"` 인데 런타임 값은 `undefined` 인 불건전성(unsoundness)이 생긴다. 결론적으로 **양방향 제어가 필요한 제네레이터는 for-await-of 가 아니라 수동 `next()` 루프로 소비해야** 하며, 시그니처에 `TNext` 를 넣었다면 `undefined` 를 union 에 포함시켜 방어하는 편이 안전하다.

## 3. `--target` / `--lib` / `downlevelIteration` 상호작용

`for...of`, 배열 spread, 구조 분해는 ES2015 부터 이터레이터 프로토콜 기반이다. `--target ES5` 에서 TypeScript 는 기본적으로 이들을 **인덱스 기반 for 루프**로 다운레벨한다. 배열과 문자열에는 맞지만 `Set`, `Map`, 제네레이터에는 틀린 변환이다. `--downlevelIteration` 을 켜면 컴파일러가 `__values`/`__read`/`__spreadArray` 헬퍼를 방출해 `Symbol.iterator` 를 실제로 호출하는 정확한 코드로 바꾼다. 대신 산출물 크기와 호출 오버헤드가 늘어난다 — 배열만 도는 루프에서도 이터레이터 객체를 매번 할당하므로, 마이크로벤치에서 수천만 회 반복 시 인덱스 루프 대비 수 배 느려지는 것이 일반적이다. `--importHelpers` + `tslib` 로 헬퍼 중복만큼은 제거할 수 있다.

```jsonc
{
  "compilerOptions": {
    "target": "ES2018",          // for await...of 가 네이티브로 방출되는 최소 타깃
    "lib": ["ES2022", "DOM", "ESNext.Iterator"],
    "downlevelIteration": true,  // target < ES2015 일 때만 의미 있음
    "importHelpers": true
  }
}
```

`for await...of` 는 ES2018 문법이다. `--target ES2017` 이하로 내리면 TypeScript 는 `__asyncValues` / `__await` 헬퍼로 다운레벨하는데, 이때 `Symbol.asyncIterator` 가 런타임에 존재해야 한다. Node 10+ 와 모든 현행 브라우저는 네이티브로 갖고 있지만, 아주 오래된 환경을 타깃한다면 진입점에서 `(Symbol as any).asyncIterator ??= Symbol.for("Symbol.asyncIterator")` 같은 폴리필을 깔아야 한다. 한편 `--lib` 는 **타입만** 제어한다. `ESNext.Iterator` 를 lib 에 넣어도 `.map()`/`.take()` 는 Node 22+ 나 Chrome 122+ 같은 런타임에서만 실제로 동작하고, 그 이하에서는 컴파일은 통과하지만 `TypeError: x.map is not a function` 이 난다.

혼동이 잦은 지점 하나를 못 박아 두면, `esModuleInterop` 은 이터레이션과 **아무 관련이 없다.** 그 옵션은 CommonJS 모듈의 default import 상호운용(`__importDefault`, `__importStar`)만 다룬다. "for-of 가 이상하게 컴파일된다"는 문제에서 `esModuleInterop` 을 켜고 끄는 것은 무의미하며, 봐야 할 것은 `target`·`downlevelIteration`·`lib` 세 개뿐이다.

| target | for-of on Set | for await-of | 필요한 조치 |
| --- | --- | --- | --- |
| ES5 | 인덱스 루프로 오변환 | `__asyncValues` 헬퍼 | `downlevelIteration` + Symbol 폴리필 |
| ES2015 | 네이티브 | `__asyncValues` 헬퍼 | `Symbol.asyncIterator` 존재 확인 |
| ES2018+ | 네이티브 | 네이티브 | 없음 |

## 4. Web Streams 의 제네릭 설계와 타입 충돌

WHATWG Streams 는 세 개의 제네릭 클래스로 구성된다. `ReadableStream<R = any>` 의 `R` 은 청크 타입, `WritableStream<W = any>` 의 `W` 는 쓰기 청크 타입, `TransformStream<I = any, O = any>` 는 `readable: ReadableStream<O>` 와 `writable: WritableStream<I>` 를 필드로 갖는 **한 쌍의 묶음**이다. 기본값이 `any` 인 탓에 `new ReadableStream({...})` 을 어노테이션 없이 쓰면 청크가 전부 `any` 로 새어 나간다는 점이 첫 번째 실무 리스크다.

```ts
function fromArray<T>(items: readonly T[]): ReadableStream<T> {
  let i = 0;
  return new ReadableStream<T>({
    pull(controller) {              // controller: ReadableStreamDefaultController<T>
      if (i >= items.length) { controller.close(); return; }
      controller.enqueue(items[i++]);
    },
    cancel(reason) { console.warn("cancelled", reason); },
  });
}

function mapStream<I, O>(fn: (chunk: I) => O | Promise<O>): TransformStream<I, O> {
  return new TransformStream<I, O>({
    async transform(chunk, controller) { controller.enqueue(await fn(chunk)); },
  });
}
```

두 번째 리스크는 **타입 선언 중복**이다. `lib.dom.d.ts` 와 `@types/node` 의 `stream/web` 이 각각 전역 `ReadableStream` 을 선언하는데, 둘은 구조적으로 미묘하게 다르다(대표적으로 Node 쪽에는 `[Symbol.asyncIterator]()` 와 `values()` 가 있고 DOM lib 에는 오래 없었다). `"lib": ["DOM"]` 와 `@types/node` 를 동시에 켜면 "Subsequent property declarations must have the same type" 류의 충돌이나, 함수 인자로 넘길 때 대입 불가 오류가 난다. 해결책은 세 가지다 — (a) 프론트/백 tsconfig 를 분리해 한쪽만 lib 에 올린다, (b) `"types": ["node"]` 로 전역 타입 주입 범위를 좁힌다, (c) Node 코드에서는 전역 대신 `import type { ReadableStream } from "node:stream/web"` 으로 명시 import 한다. Node 20 이후로 `@types/node` 가 DOM 정의와 충돌하지 않도록 조건부 선언을 다듬어 상황이 나아졌지만, 모노레포에서 같은 이름의 서로 다른 두 구조체를 넘겨받는 순간은 여전히 생긴다.

세 번째는 **`ReadableStream` 이 `AsyncIterable` 인가** 하는 문제다. 스펙에는 async iteration 이 명시돼 있고 Node.js 는 16.5 무렵부터 `ReadableStream` 에 `[Symbol.asyncIterator]` 를 제공한다. 반면 브라우저 구현은 오래 지연됐다 — Firefox 는 비교적 이르게 지원했지만 Chromium 은 한참 미구현 상태였고, 그래서 `for await (const chunk of response.body)` 가 Node 에서는 되고 Chrome 에서는 `TypeError` 가 나는 상황이 수년간 존재했다. 런타임 중립 코드를 쓰려면 reader 기반 어댑터를 직접 두는 편이 안전하다.

```ts
async function* streamToAsyncIterable<T>(stream: ReadableStream<T>): AsyncGenerator<T, void, undefined> {
  const anyStream = stream as ReadableStream<T> & Partial<AsyncIterable<T>>;
  if (typeof anyStream[Symbol.asyncIterator] === "function") {
    yield* anyStream as AsyncIterable<T>;
    return;
  }
  const reader = stream.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) return;
      yield value;
    }
  } finally {
    reader.releaseLock();   // 중도 이탈 시에도 락 해제 보장
  }
}
```

역방향 어댑터도 필요하다. 제네레이터를 `ReadableStream` 으로 바꿀 때는 `pull` 안에서 `next()` 를 호출해 백프레셔를 그대로 전달하는 것이 핵심이다.

```ts
function fromAsyncIterable<T>(src: AsyncIterable<T>): ReadableStream<T> {
  let it: AsyncIterator<T> | undefined;
  return new ReadableStream<T>({
    start() { it = src[Symbol.asyncIterator](); },
    async pull(controller) {
      const { done, value } = await it!.next();
      if (done) controller.close(); else controller.enqueue(value);
    },
    async cancel(reason) { await it?.return?.(reason as never); },
  });
}
```

## 5. 타입 안전 파이프라인 합성

`TransformStream<I, O>` 체인은 개념적으로 `I → O` 함수 합성이다. TypeScript 4.0 의 Variadic Tuple Types 를 쓰면 가변 개수 변환기의 입출력 타입 전파를 컴파일 타임에 검증할 수 있다. 핵심 아이디어는 "튜플의 각 원소 타입이 이전 원소의 출력과 맞물리도록 제약하는" 매핑 타입을 만드는 것이다.

```ts
type Chain<In, Ts extends readonly TransformStream<any, any>[]> =
  Ts extends readonly [TransformStream<infer I, infer O>, ...infer Rest]
    ? [In] extends [I]
      ? Rest extends readonly TransformStream<any, any>[] ? Chain<O, Rest> : never
      : never                     // 타입 불일치 지점에서 never 로 붕괴
    : In;                         // 빈 튜플이면 최종 출력 = In

function pipe<In, const Ts extends readonly TransformStream<any, any>[]>(
  source: ReadableStream<In>,
  ...transforms: Ts
): ReadableStream<Chain<In, Ts>> {
  return transforms.reduce<ReadableStream<any>>(
    (acc, t) => acc.pipeThrough(t),
    source,
  );
}

// 사용 예: ReadableStream<number> → string → { len: number }
const out = pipe(
  fromArray([1, 2, 3]),
  mapStream((n: number) => `#${n}`),
  mapStream((s: string) => ({ len: s.length })),
);   // out: ReadableStream<{ len: number }>
```

실무에서 이 패턴은 두 군데서 깨진다. 첫째, `mapStream((n) => ...)` 처럼 콜백 파라미터를 생략하면 TypeScript 가 `mapStream` 의 `I` 를 추론할 근거를 잃고 `unknown` 으로 떨어진다. 가변 인자 위치에서는 **좌→우 문맥 전파가 일어나지 않기** 때문이다. 위 예제처럼 `(n: number)` 를 명시하거나, 오버로드를 2~4개 인자까지 손으로 나열하는(RxJS `pipe` 가 택한) 방식으로 우회한다. 둘째, `Chain` 이 `never` 로 붕괴하면 "어디서 어긋났는지"가 에러 메시지에 드러나지 않는다. 이를 개선하려면 `never` 대신 브랜드된 에러 타입을 끼워 넣어 진단을 남기는 편이 낫다.

```ts
type TypeError_<Msg extends string, Actual, Expected> =
  { __error: Msg; actual: Actual; expected: Expected };
// Chain 의 실패 분기를 TypeError_<"stage input mismatch", In, I> 로 교체
```

Java 개발자 시각에서 보면 `Chain` 은 `Function<A,B>.andThen(Function<B,C>)` 의 타입 수준 시뮬레이션이다. Java 는 메서드 체이닝으로 각 단계마다 컴파일러가 타입을 잇지만, 가변 인자 배열에는 이 연결이 없다. TypeScript 는 조건부 타입 재귀로 그 연결을 흉내 내되, 재귀 인스턴스화 한도(기본 50회 수준)에 걸리지 않도록 단계 수를 현실적인 범위로 유지해야 한다. 오버로드 나열은 못생겼지만 추론 품질과 에러 메시지 면에서는 여전히 우위다.

## 6. 백프레셔와 타입의 관계

백프레셔는 소비자가 감당 가능한 속도로 생산자를 늦추는 메커니즘이다. Web Streams 는 이를 **큐 + desiredSize** 로 모델링한다. `ReadableStreamDefaultController.desiredSize` 는 `highWaterMark - 현재 큐 크기` 이며, 0 이하가 되면 스트림은 `pull` 호출을 멈춘다. `pull` 이 Promise 를 반환하면 그것이 resolve 될 때까지 다음 `pull` 도 호출되지 않는다. 즉 백프레셔 신호는 **숫자와 Promise** 라는 런타임 값으로 표현되며, 타입 시스템은 "이 스트림이 백프레셔를 지키는가"를 전혀 검증하지 못한다. `controller.enqueue()` 를 `desiredSize` 무시하고 루프에서 난사하면 타입 오류 없이 메모리가 터진다.

```ts
function fromPagedApi<T>(fetchPage: (cursor?: string) => Promise<{ items: T[]; next?: string }>) {
  let cursor: string | undefined;
  let done = false;
  return new ReadableStream<T>({
    async pull(controller) {
      if (done) { controller.close(); return; }
      const page = await fetchPage(cursor);
      for (const item of page.items) controller.enqueue(item);
      cursor = page.next;
      if (!cursor) done = true;
      // desiredSize 가 0 이하로 내려가면 소비자가 읽을 때까지 pull 이 다시 안 불린다
    },
  }, new CountQueuingStrategy({ highWaterMark: 64 }));
}
```

AsyncGenerator 는 이와 다르게 **암묵적·완전 백프레셔**를 갖는다. 제네레이터 본문은 `yield` 에서 완전히 정지하고, 소비자가 `next()` 를 호출할 때만 재개된다. 큐도 없고 highWaterMark 도 없다. 버퍼링이 전혀 없으므로 메모리 측면에서는 가장 안전하지만, 생산자와 소비자가 절대 겹쳐 실행되지 않아 I/O 대기 시간이 직렬로 누적된다. 항목당 네트워크 10ms + 처리 5ms 인 작업 1,000건이면 제네레이터는 약 15초가 걸리는 반면, highWaterMark 를 준 ReadableStream 은 prefetch 로 겹쳐져 10초대로 줄어든다. 처리량이 중요하면 스트림, 메모리 상한과 단순함이 중요하면 제네레이터다.

Java 진영과 비교하면 차이가 선명하다. Reactive Streams 의 `Subscription.request(n)` 은 소비자가 **정수 요청량을 명시적으로 올려보내는** pull-push 하이브리드고, RxJS 의 `Observable` 은 기본적으로 백프레셔가 없는 push 모델이라 `bufferCount`/`throttle` 같은 연산자로 별도 제어해야 한다. Web Streams 는 요청량을 노출하지 않고 큐 여유분만 내부적으로 보는 pull 모델에 가깝다. 타입으로 보면 Reactive Streams 만이 `request(long n)` 이라는 시그니처로 백프레셔를 API 표면에 드러내고, 나머지 둘은 타입에 흔적이 없다.

| 모델 | 신호 방식 | 버퍼 | 타입에 드러나는가 |
| --- | --- | --- | --- |
| AsyncGenerator | `next()` 호출 자체 | 없음(1건) | 아니오 |
| Web Streams | `desiredSize`, `pull` Promise | highWaterMark | 아니오 |
| Reactive Streams (Reactor) | `request(n)` | 연산자별 | 예 (`Subscription`) |
| RxJS Observable | 없음(push) | 수동 연산자 | 아니오 |

## 7. 에러 전파와 자원 정리

`return()`/`throw()` 는 이터레이터의 조기 종료 프로토콜이다. `AsyncGenerator<T, TReturn, TNext>` 에서 `return(value: TReturn | PromiseLike<TReturn>)` 은 `Promise<IteratorResult<T, TReturn>>` 을 돌려주며, 호출되면 제네레이터 본문의 현재 `yield` 지점에서 `return` 문이 실행된 것처럼 동작한다. 따라서 `try/finally` 의 `finally` 블록이 실행된다. `for await...of` 는 break·throw·early return 으로 루프를 벗어날 때 **자동으로 `return()` 을 호출하도록 스펙에 규정**돼 있으므로, `finally` 안의 정리 코드는 정상 종료든 중단이든 실행이 보장된다. 이는 Java 의 try-with-resources 와 같은 급의 보장이며, 스트림에서 `reader.releaseLock()`/`stream.cancel()` 을 거기에 넣어야 하는 이유다.

```ts
async function* withCleanup<T>(src: AsyncIterable<T>, label: string): AsyncGenerator<T, void, undefined> {
  try {
    for await (const v of src) yield v;
  } finally {
    console.log(`[${label}] cleanup`);   // break/throw 에도 반드시 실행
  }
}
```

`AbortSignal` 결합은 타이핑 관점에서 한 번 짚어둘 만하다. `AbortSignal.reason` 은 `any` 이고 `throwIfAborted(): void` 는 abort 여부를 타입으로 좁혀주지 않으므로, 취소 사유를 도메인 타입으로 다루고 싶다면 별도 타입 가드를 두는 편이 낫다. `Promise.race` 로 취소를 얹을 때는 pending Promise 가 남지 않도록 리스너 해제까지 `finally` 에 넣어야 한다.

```ts
class Cancelled extends Error { constructor(readonly why: string) { super(why); } }

async function* takeUntilAborted<T>(src: AsyncIterable<T>, signal: AbortSignal): AsyncGenerator<T, void, undefined> {
  const it = src[Symbol.asyncIterator]();
  try {
    while (true) {
      if (signal.aborted) throw new Cancelled(String(signal.reason ?? "aborted"));
      const aborted = new Promise<never>((_, rej) => {
        signal.addEventListener("abort", () => rej(new Cancelled(String(signal.reason))), { once: true });
      });
      const r = await Promise.race([it.next(), aborted]);
      if (r.done) return;
      yield r.value;
    }
  } finally {
    await it.return?.(undefined as never);   // 상류 정리 전파
  }
}
```

TypeScript 5.2 의 `using`/`await using`(Explicit Resource Management)은 이 패턴을 선언적으로 바꾼다. `Symbol.asyncDispose` 를 구현한 객체를 `await using` 으로 바인딩하면 블록 이탈 시 `[Symbol.asyncDispose]()` 가 호출된다. 컴파일하려면 `lib` 에 `ESNext.Disposable` 이 포함돼야 하고, 런타임에는 `Symbol.asyncDispose` 폴리필(`(Symbol as any).asyncDispose ??= Symbol("Symbol.asyncDispose")`)이 필요하다 — Node 20 이상은 내장한다.

```ts
function readerOf<T>(s: ReadableStream<T>) {
  const reader = s.getReader();
  return {
    reader,
    async [Symbol.asyncDispose]() { reader.releaseLock(); await s.cancel("scope exit"); },
  };
}

async function head<T>(s: ReadableStream<T>, n: number): Promise<T[]> {
  await using res = readerOf(s);             // 블록 이탈 시 자동 취소
  const out: T[] = [];
  while (out.length < n) {
    const { done, value } = await res.reader.read();
    if (done) break;
    out.push(value);
  }
  return out;
}
```

## 8. 실전 패턴 — NDJSON·SSE·대용량 파일

세 패턴 모두 "바이트 청크를 경계 기준으로 재조립"하는 같은 뼈대를 공유한다. 결정적으로 중요한 사실은 **네트워크 청크 경계와 줄 경계가 일치하지 않는다**는 것이다. 그래서 캐리 버퍼를 유지하고, `TextDecoder` 는 반드시 `{ stream: true }` 로 호출해 멀티바이트 UTF-8(한글은 3바이트)이 청크 경계에서 깨지지 않게 해야 한다. 이 옵션을 빼면 한글 데이터에서 간헐적으로 U+FFFD 치환 문자가 섞여 들어가고, 재현이 어려워 디버깅 비용이 크다.

```ts
function lineSplitter(): TransformStream<Uint8Array, string> {
  const decoder = new TextDecoder("utf-8");
  let carry = "";
  return new TransformStream<Uint8Array, string>({
    transform(chunk, controller) {
      carry += decoder.decode(chunk, { stream: true });   // 경계 보존 필수
      const parts = carry.split("\n");
      carry = parts.pop() ?? "";
      for (const line of parts) if (line.trim()) controller.enqueue(line);
    },
    flush(controller) {
      carry += decoder.decode();
      if (carry.trim()) controller.enqueue(carry);
    },
  });
}

function ndjson<T>(guard?: (v: unknown) => v is T): TransformStream<string, T> {
  return new TransformStream<string, T>({
    transform(line, controller) {
      const parsed: unknown = JSON.parse(line);
      if (guard && !guard(parsed)) throw new TypeError(`schema mismatch: ${line.slice(0, 80)}`);
      controller.enqueue(parsed as T);
    },
  });
}

interface User { id: number; name: string }
const isUser = (v: unknown): v is User =>
  typeof v === "object" && v !== null && typeof (v as User).id === "number";

const res = await fetch("/api/users.ndjson");
const users = pipe(res.body!, lineSplitter(), ndjson(isUser));
for await (const u of streamToAsyncIterable(users)) console.log(u.name);
```

SSE 는 줄 단위 위에 한 겹 더 얹는다. 이벤트는 빈 줄로 구분되고 각 줄은 `field: value` 형태이며, `data:` 가 여러 줄이면 `\n` 으로 이어 붙인다. `EventSource` 를 쓸 수 없는 상황(POST 요청, 커스텀 헤더 필요)에서 `fetch` 로 직접 파싱하는 구현이 실무에서 자주 필요하다.

```ts
interface SseEvent { event: string; data: string; id?: string }

async function* parseSse(lines: AsyncIterable<string>): AsyncGenerator<SseEvent, void, undefined> {
  let ev = "message", data: string[] = [], id: string | undefined;
  for await (const raw of lines) {
    if (raw === "") {
      if (data.length) yield { event: ev, data: data.join("\n"), id };
      ev = "message"; data = []; id = undefined;
      continue;
    }
    if (raw.startsWith(":")) continue;                 // 주석(keep-alive)
    const idx = raw.indexOf(":");
    const field = idx < 0 ? raw : raw.slice(0, idx);
    const value = idx < 0 ? "" : raw.slice(idx + 1).replace(/^ /, "");
    if (field === "event") ev = value;
    else if (field === "data") data.push(value);
    else if (field === "id") id = value;
  }
}
```

주의할 점은 위 `lineSplitter` 가 빈 줄을 버린다는 것이다. SSE 용으로는 `if (line.trim())` 필터를 제거한 변형을 따로 써야 이벤트 경계가 살아남는다. 그리고 SSE 는 `\r\n`·`\r`·`\n` 을 모두 줄바꿈으로 인정하므로 `split(/\r\n|\r|\n/)` 로 바꾸는 편이 스펙에 충실하다.

대용량 파일 라인 처리에서 선택지는 세 갈래다. 아래 수치는 방식별 **메모리 상한의 성격**을 비교한 것으로, 절대 수치는 하드웨어·청크 크기에 따라 달라진다.

| 방식 | 메모리 상한 | 처리량 | 적합한 상황 |
| --- | --- | --- | --- |
| `fs.readFileSync` + `split("\n")` | 파일 크기 × 약 2 (버퍼 + 문자열) | 최고 | 수 MB 이하 설정 파일 |
| `readline` / `node:readline` | 최장 줄 길이 수준 | 높음 | Node 전용 로그 처리 |
| Web Streams 파이프라인 | highWaterMark × 청크 크기 | 중간~높음 | 런타임 중립, 브라우저 공용 |
| AsyncGenerator 직렬 | 1줄 | 가장 낮음 | 항목당 무거운 I/O, 엄격한 메모리 제약 |

1 GB NDJSON 을 `readFileSync` 로 읽으면 Node 기본 힙(64비트에서 약 4 GB 상한)을 압박해 `RangeError: Invalid string length`(V8 문자열 최대 길이 제한, 현행 V8 기준 약 5억 자) 를 만나기 쉽다. 스트림 파이프라인은 highWaterMark 를 64 KB 급 청크 16개로 잡으면 상주 메모리가 수 MB 수준에서 평탄하게 유지된다. 반대로 항목마다 외부 API 를 호출하는 경우라면 스트림의 prefetch 가 오히려 상대 서버에 동시 부하를 만들 수 있어, AsyncGenerator 직렬 처리 + 명시적 동시성 제한(세마포어)이 더 안전한 선택이다.

마지막으로 타입 설계 원칙 하나. 파이프라인 내부는 `Uint8Array → string → unknown → T` 로 좁혀가되, **`unknown` 에서 `T` 로 넘어가는 지점에 반드시 런타임 타입 가드를 둔다.** `JSON.parse` 는 `any` 를 반환하므로 그대로 `as T` 하면 타입 시스템 전체가 그 지점에서 무력화된다. Zod 같은 스키마 라이브러리의 `parse` 를 `TransformStream<string, T>` 안에 넣으면 타입 경계와 검증 경계를 한 곳에 모을 수 있고, 실패한 줄을 버릴지 스트림 전체를 error 시킬지(`controller.error()`)를 그 자리에서 정책으로 결정할 수 있다.

## 참고

- TypeScript 5.6 Release Notes — Iterator Helper Methods, `IteratorObject`/`BuiltinIterator`: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-6.html
- TypeScript 5.2 Release Notes — `using` 선언과 Explicit Resource Management: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html
- TypeScript 4.0 Release Notes — Variadic Tuple Types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-0.html
- TSConfig Reference — `downlevelIteration`, `target`, `lib`: https://www.typescriptlang.org/tsconfig/#downlevelIteration
- TC39 Iterator Helpers Proposal: https://github.com/tc39/proposal-iterator-helpers
- TC39 Explicit Resource Management Proposal (`Symbol.asyncDispose`): https://github.com/tc39/proposal-explicit-resource-management
- WHATWG Streams Standard — 백프레셔·큐잉 전략·async iteration: https://streams.spec.whatwg.org/
- MDN — `ReadableStream`: https://developer.mozilla.org/en-US/docs/Web/API/ReadableStream
- Node.js Web Streams API (`node:stream/web`): https://nodejs.org/api/webstreams.html
- WHATWG HTML Standard — Server-sent events 파싱 규칙: https://html.spec.whatwg.org/multipage/server-sent-events.html
