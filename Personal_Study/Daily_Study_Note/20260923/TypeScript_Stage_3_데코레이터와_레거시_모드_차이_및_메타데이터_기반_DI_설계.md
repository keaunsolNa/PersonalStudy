Notion 원본: https://www.notion.so/3e45a06fd6d381f095e2f64fab0b3106

# TypeScript Stage 3 데코레이터와 레거시 모드 차이 및 메타데이터 기반 DI 설계

> 2026-09-23 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Stage 3 데코레이터의 컨텍스트 객체와 적용 시점을 레거시 구현과 대조한다
- `experimentalDecorators` / `emitDecoratorMetadata` 조합별 컴파일 결과를 확인한다
- `Symbol.metadata` 기반 메타데이터 수집으로 DI 컨테이너를 구성한다
- NestJS·TypeORM 등 기존 생태계의 전환 제약을 판단한다

## 1. 두 개의 데코레이터가 공존하는 이유

TypeScript 는 2015년부터 TC39 stage 1 제안을 기반으로 한 데코레이터를 `experimentalDecorators` 플래그 뒤에 제공해왔다. 그 사이 TC39 제안은 여러 차례 전면 재설계됐고, 최종 stage 3 형태는 초기 안과 시그니처·동작이 모두 다르다. TypeScript 5.0 이 stage 3 데코레이터를 플래그 없이 지원하기 시작하면서, 지금은 두 시스템이 공존한다.

선택 규칙은 단순하다. `experimentalDecorators: true` 면 레거시 구현, 없거나 `false` 면 stage 3 구현이 쓰인다. 두 방식을 한 프로젝트에서 섞을 수 없다.

| 항목 | 레거시 (`experimentalDecorators`) | Stage 3 (기본) |
|---|---|---|
| 표준 여부 | TC39 stage 1 기반, 비표준 | TC39 stage 3, 표준 권도 |
| 메서드 데코레이터 인자 | `(target, key, descriptor)` | `(value, context)` |
| 프로퍼티 데코레이터 인자 | `(target, key)` | `(undefined, context)` |
| 파라미터 데코레이터 | 지원 | **미지원** |
| 접근자 분리 | get/set 단일 descriptor | `getter`/`setter` 별도 종류 |
| 메타데이터 | `reflect-metadata` + `emitDecoratorMetadata` | `Symbol.metadata` (stage 3 제안) |
| 초기화 훅 | 없음 | `context.addInitializer()` |
| private 멤버 | 불가 | 가능 |
| `static` 블록 상호작용 | 제한적 | 명세화됨 |

**파라미터 데코레이터 미지원**이 전환의 최대 장벽이다. NestJS 의 `@Inject()`, `@Body()`, `@Param()` 이 전부 파라미터 데코레이터이기 때문이다. TC39 에는 별도 제안이 있지만 아직 stage 3 가 아니다.

## 2. Stage 3 데코레이터의 시그니처

모든 stage 3 데코레이터는 `(value, context)` 두 인자를 받는다. `context` 가 이전의 `target`/`key`/`descriptor` 를 대체하고 훨씬 많은 정보를 담는다.

```ts
type DecoratorContext = {
  kind: 'class' | 'method' | 'getter' | 'setter' | 'field' | 'accessor';
  name: string | symbol;
  static?: boolean;
  private?: boolean;
  access: { has?(o: object): boolean; get?(o: object): unknown; set?(o: object, v: unknown): void };
  addInitializer(initializer: () => void): void;
  metadata: Record<PropertyKey, unknown>;
};
```

메서드 데코레이터의 구체적 형태:

```ts
function logged<This, Args extends unknown[], Return>(
  target: (this: This, ...args: Args) => Return,
  context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>
): (this: This, ...args: Args) => Return {
  const name = String(context.name);

  return function (this: This, ...args: Args): Return {
    const startedAt = performance.now();
    try {
      return target.call(this, ...args);
    } finally {
      const elapsed = performance.now() - startedAt;
      console.debug(`${name} took ${elapsed.toFixed(2)}ms`);
    }
  };
}

class OrderService {
  @logged
  calculateTotal(items: readonly { price: number; qty: number }[]): number {
    return items.reduce((sum, i) => sum + i.price * i.qty, 0);
  }
}
```

레거시와 결정적으로 다른 점은 **반환값의 의미**다. 레거시 메서드 데코레이터는 PropertyDescriptor 를 반환하거나 descriptor 를 변형했다. Stage 3 는 새 함수를 반환하면 그것이 원래 메서드를 대체한다. 더 직관적이고, 반환하지 않으면 원본이 유지된다.

필드 데코레이터는 값이 아니라 **초기화 함수**를 반환한다.

```ts
function defaultTo<T>(fallback: T) {
  return function (
    _target: undefined,
    context: ClassFieldDecoratorContext<unknown, T>
  ): (initial: T) => T {
    return (initial: T): T => (initial ?? fallback);
  };
}

class Config {
  @defaultTo(3000)
  port!: number;

  @defaultTo('info')
  logLevel!: string;
}

new Config().port;  // 3000
```

반환된 함수는 인스턴스 생성 시 필드 초기값을 인자로 받아 최종값을 돌려준다. `this` 가 인스턴스로 바인딩되므로 다른 필드를 참조하는 것도 가능하지만, 필드 선언 순서에 의존하게 되므로 피하는 편이 좋다.

## 3. `addInitializer` 와 auto-accessor

`context.addInitializer()` 는 레거시에 없던 능력이다. 클래스 정의 시점(static)이나 인스턴스 생성 시점에 실행될 코드를 등록한다. 메서드 자동 바인딩이 대표적 활용이다.

```ts
function bound<This, Args extends unknown[], Return>(
  target: (this: This, ...args: Args) => Return,
  context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>
): void {
  if (context.private) {
    throw new Error('@bound cannot be applied to private methods');
  }
  context.addInitializer(function (this: This) {
    // 인스턴스마다 바인딩된 사본을 자기 자신에 설치
    (this as Record<PropertyKey, unknown>)[context.name] = target.bind(this);
  });
}

class Counter {
  #count = 0;

  @bound
  increment(): void {
    this.#count++;
  }

  get value(): number {
    return this.#count;
  }
}

const counter = new Counter();
const detached = counter.increment;
detached();               // this 손실 없이 동작
counter.value;            // 1
```

`accessor` 키워드는 stage 3 와 함께 도입된 새 문법이다. 필드를 getter/setter 쌍과 private 백킹 필드로 펼쳐 데코레이터가 읽기·쓰기를 모두 가로처게 한다.

```ts
function observable<This, T>(
  target: ClassAccessorDecoratorTarget<This, T>,
  context: ClassAccessorDecoratorContext<This, T>
): ClassAccessorDecoratorResult<This, T> {
  const listeners = new WeakMap<object, Set<(v: T) => void>>();

  return {
    get(this: This): T {
      return target.get.call(this);
    },
    set(this: This, value: T): void {
      const previous = target.get.call(this);
      target.set.call(this, value);
      if (previous !== value) {
        listeners.get(this as object)?.forEach((fn) => fn(value));
      }
    },
    init(this: This, initial: T): T {
      listeners.set(this as object, new Set());
      return initial;
    },
  };
}

class Store {
  @observable
  accessor count = 0;
}
```

`init`, `get`, `set` 세 훅을 모두 제공하는 것이 accessor 데코레이터의 특징이다. MobX, Lit 같은 반응형 라이브러리가 stage 3 전환에서 이 형태를 채택했다.

## 4. `Symbol.metadata` 기반 메타데이터

레거시 방식은 `emitDecoratorMetadata: true` 와 `reflect-metadata` 폴리필로 타입 정보를 런타임에 남겼다. 컴파일러가 `design:type`, `design:paramtypes`, `design:returntype` 키로 생성자 파라미터 타입 등을 기록했고, DI 컨테이너가 이를 읽어 의존성을 해석했다.

Stage 3 는 다른 접근을 쓴다. `context.metadata` 객체에 데코레이터가 **직접** 정보를 쓰고, 그 객체는 클래스의 `Symbol.metadata` 속성으로 노출된다.

```ts
// polyfill: Symbol.metadata 가 없는 런타임 대비
(Symbol as { metadata?: symbol }).metadata ??= Symbol.for('Symbol.metadata');

const SERVICE_TOKENS = Symbol('di:tokens');

type Token<T = unknown> = { readonly key: string; readonly _type?: T };

function token<T>(key: string): Token<T> {
  return { key };
}

function injectable(...deps: readonly Token[]) {
  return function <T extends new (...args: never[]) => object>(
    target: T,
    context: ClassDecoratorContext<T>
  ): T {
    context.metadata[SERVICE_TOKENS] = deps;
    return target;
  };
}
```

컨테이너 구현:

```ts
class Container {
  readonly #providers = new Map<string, () => unknown>();
  readonly #singletons = new Map<string, unknown>();

  register<T>(t: Token<T>, factory: () => T): this {
    this.#providers.set(t.key, factory);
    return this;
  }

  registerClass<T extends object>(t: Token<T>, ctor: new (...args: never[]) => T): this {
    this.#providers.set(t.key, () => this.instantiate(ctor));
    return this;
  }

  resolve<T>(t: Token<T>): T {
    if (this.#singletons.has(t.key)) {
      return this.#singletons.get(t.key) as T;
    }
    const factory = this.#providers.get(t.key);
    if (!factory) {
      throw new Error(`No provider registered for token "${t.key}"`);
    }
    const instance = factory();
    this.#singletons.set(t.key, instance);
    return instance as T;
  }

  instantiate<T extends object>(ctor: new (...args: never[]) => T): T {
    const metadata = (ctor as { [Symbol.metadata]?: Record<PropertyKey, unknown> })[Symbol.metadata];
    const deps = (metadata?.[SERVICE_TOKENS] as readonly Token[] | undefined) ?? [];
    const args = deps.map((d) => this.resolve(d));
    return new ctor(...(args as never[]));
  }
}
```

사용:

```ts
interface Clock { now(): number }
interface OrderRepository { findById(id: string): Promise<{ id: string } | null> }

const CLOCK = token<Clock>('Clock');
const ORDER_REPO = token<OrderRepository>('OrderRepository');
const ORDER_SERVICE = token<OrderService>('OrderService');

@injectable(CLOCK, ORDER_REPO)
class OrderService {
  constructor(
    private readonly clock: Clock,
    private readonly repository: OrderRepository
  ) {}

  async describe(id: string): Promise<string> {
    const order = await this.repository.findById(id);
    return order ? `${order.id} @ ${this.clock.now()}` : 'not found';
  }
}
```

```ts
describe('Container', () => {
  it('메타데이터에 선언된 의존성을 순서대로 주입한다', () => {
    const fixedClock: Clock = { now: () => 1_700_000_000_000 };
    const repo: OrderRepository = { findById: async (id) => ({ id }) };

    const c = new Container()
      .register(CLOCK, () => fixedClock)
      .register(ORDER_REPO, () => repo)
      .registerClass(ORDER_SERVICE, OrderService);

    expect(c.resolve(ORDER_SERVICE)).toBeInstanceOf(OrderService);
  });

  it('동일 토큰은 싱글턴으로 재사용된다', () => {
    const c = new Container().register(CLOCK, () => ({ now: () => 1 }));
    expect(c.resolve(CLOCK)).toBe(c.resolve(CLOCK));
  });

  it('등록되지 않은 토큰은 명시적으로 실패한다', () => {
    const c = new Container();
    expect(() => c.resolve(CLOCK)).toThrow(/No provider registered/);
  });

  it('주입 순서가 생성자 파라미터 순서와 일치한다', async () => {
    const c = new Container()
      .register(CLOCK, () => ({ now: () => 42 }))
      .register(ORDER_REPO, () => ({ findById: async (id: string) => ({ id }) }))
      .registerClass(ORDER_SERVICE, OrderService);

    await expect(c.resolve(ORDER_SERVICE).describe('ORD-1')).resolves.toBe('ORD-1 @ 42');
  });
});
```

레거시 대비 트레이드오프가 분명하다. `emitDecoratorMetadata` 는 타입만 쓰면 컴파일러가 알아서 `design:paramtypes` 를 넣어줘 `@injectable()` 에 토큰을 나열할 필요가 없었다. Stage 3 방식은 토큰을 명시해야 해 장황하다. 대신 인터페이스를 토큰으로 쓸 수 있고(레거시는 인터페이스가 런타임에 사라져 불가능했다), 컴파일러 특수 동작에 의존하지 않으며, 번들러에서 타입 정보 누출이 없다.

## 5. 컴파일 결과 비교

같은 코드가 설정에 따라 어떻게 다르게 출력되는지 확인하는 것이 이해에 가장 빠르다.

```json
// 레거시
{ "compilerOptions": {
    "target": "ES2022",
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true
}}

// Stage 3
{ "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true
}}
```

```bash
npx tsc --showConfig                 # 최종 병합된 설정 확인
npx tsc --noEmit --extendedDiagnostics
npx tsc src/service.ts --outFile /tmp/out.js && cat /tmp/out.js
```

레거시 출력은 `__decorate`/`__metadata` 헬퍼를 호출하고 `Reflect.metadata('design:paramtypes', [...])` 를 삽입한다. Stage 3 출력은 `__esDecorate`/`__runInitializers` 계열 헬퍼를 쓰며 메타데이터 삽입이 없다. `target` 이 데코레이터 다운레벨링 여부를 결정하므로, `ES2022` 이상에서도 데코레이터 자체는 아직 런타임 네이티브 지원이 없어 헬퍼로 변환된다.

`useDefineForClassFields` 는 함께 확인해야 할 항목이다. `target: ES2022` 이상이면 기본 `true` 가 되어 클래스 필드가 `Object.defineProperty` 의미로 동작한다. 레거시 데코레이터 기반 코드가 필드 할당(`[[Set]]`) 의미에 의존하고 있었다면 전환 시 동작이 바뀜다 — 부모 클래스의 setter 가 더 이상 호출되지 않는 형태의 버그가 대표적이다.

## 6. 빌드 도구별 지원 현황

| 도구 | 레거시 | Stage 3 | 비고 |
|---|---|---|---|
| tsc | ○ | ○ (5.0+) | 기준 구현 |
| esbuild | ○ (tsconfig 존중) | ○ (0.21+) | 메타데이터는 미지원 |
| SWC | ○ | ○ | `jsc.transform.decoratorVersion` |
| Babel | 플러그인 | `@babel/plugin-proposal-decorators` version: '2023-05' | |
| Vite | esbuild 경유 | ○ | 레거시+메타데이터는 SWC 플러그인 필요 |

esbuild 가 `emitDecoratorMetadata` 를 지원하지 않는 것이 실무에서 가장 자주 부딪히는 벽이다. NestJS 를 Vite/esbuild 로 빌드하려다 DI 가 깨지는 원인이 정확히 이것이다. 해결책은 `@swc/core` 로 트랜스파일하거나 tsc 를 쓰는 것이다.

```json
// .swcrc — 레거시 데코레이터 + 메타데이터
{
  "jsc": {
    "parser": { "syntax": "typescript", "decorators": true },
    "transform": { "legacyDecorator": true, "decoratorMetadata": true },
    "target": "es2022"
  },
  "module": { "type": "es6" }
}
```

```json
// .swcrc — stage 3
{
  "jsc": {
    "parser": { "syntax": "typescript", "decorators": true },
    "transform": { "decoratorVersion": "2022-03" },
    "target": "es2022"
  }
}
```

## 7. 생태계 전환 제약

주요 프레임워크의 상황을 정리하면 전환 가능 여부가 명확해진다.

**NestJS** — 파라미터 데코레이터(`@Inject`, `@Body`, `@Query`)와 `emitDecoratorMetadata` 에 깊이 의존한다. 현재로서는 레거시 모드 유지가 유일한 선택이다. 파라미터 데코레이터 제안이 stage 3 에 도달하기 전까지 전환 논의 자체가 성립하지 않는다.

**TypeORM** — `@Entity`, `@Column` 의 타입 추론이 `design:type` 메타데이터에 기대다. 모든 컬럼에 타입을 명시(`@Column({ type: 'varchar' })`)하면 메타데이터 의존을 줄일 수 있지만, 여전히 레거시 모드가 필요하다. Drizzle·Kysely 처럼 데코레이터를 쓰지 않는 ORM 이 stage 3 전환의 우회로가 된다.

**Angular** — 자체 AOT 컴파일러가 데코레이터를 컴파일 타임에 소비하므로 TypeScript 데코레이터 구현과 독립적이다.

**Lit / MobX** — stage 3 를 지원한다. Lit 3 는 두 모드 모두 동작하고, MobX 6.13+ 는 `accessor` 키워드 기반 stage 3 데코레이터를 제공한다.

따라서 신규 프로젝트의 판단 기준은 이렇게 정리된다. NestJS·TypeORM 을 쓴다면 레거시 모드로 시작하고 전환은 프레임워크 대응을 기다린다. 프레임워크 제약이 없다면 stage 3 를 선택해 표준 권도에 올라타되, DI 가 필요하면 위 4절처럼 토큰 명시 방식으로 설계한다. 데코레이터 자체를 쓰지 않는 선택지도 진지하게 고려할 만하다 — 명시적 팩토리 함수와 고차 함수 조합으로 같은 기능을 달성할 수 있고, 타입 추론이 더 잘 동작하는 경우도 많다.

```ts
// 데코레이터 없는 동일 기능
const withLogging = <T extends (...args: never[]) => unknown>(name: string, fn: T): T =>
  ((...args: Parameters<T>) => {
    const startedAt = performance.now();
    try {
      return fn(...args);
    } finally {
      console.debug(`${name} took ${(performance.now() - startedAt).toFixed(2)}ms`);
    }
  }) as T;
```

## 8. 전환 시 점검 목록

기존 코드베이스를 stage 3 로 옥긴다면 다음 순서로 확인한다.

1. 파라미터 데코레이터 사용처를 전수 조사한다 — 하나라도 있으면 전환 불가
2. `reflect-metadata` import 와 `Reflect.getMetadata` 호출을 찾아 대체 설계를 세운다
3. `useDefineForClassFields` 변경으로 깨지는 필드 초기화 순서를 테스트로 확인한다
4. 메서드 데코레이터의 `descriptor` 조작 코드를 "새 함수 반환" 형태로 재작성한다
5. 프로퍼티 데코레이터 중 값을 반환하던 것을 초기화 함수 반환으로 바꿈다
6. 빌드 파이프라인(esbuild/SWC/Babel)의 데코레이터 버전 설정을 맞춘다
7. `Symbol.metadata` 폴리필을 엔트리포인트 최상단에 둔다

```bash
# 1번 조사용
rg --type ts -n '\(\s*@\w+' src/ | head -50
rg --type ts -n 'constructor\([^)]*@' src/

# 2번 조사용
rg --type ts -n "reflect-metadata|Reflect\.(get|define)Metadata" src/
```

전환은 전부 아니면 전무다. 두 모드를 파일별로 섞을 수 없으므로, 대규모 코드베이스에서는 패키지 경계로 나눠 점진 전환하는 것이 현실적이다. 모노레포에서 새 패키지만 stage 3 로 시작하고, 기존 패키지는 각자의 `tsconfig.json` 에서 레거시를 유지하는 방식이다.

## 참고

- TC39, *Decorators* 제안 (stage 3) 및 *Decorator Metadata* 제안
- TypeScript 5.0 Release Notes, *Decorators* / TypeScript 5.2, *Decorator Metadata*
- TypeScript Handbook, *Decorators* (레거시 문서)
- SWC, *Configuring SWC — jsc.transform.decoratorVersion*
- esbuild, *Content Types — TypeScript decorators* (emitDecoratorMetadata 미지원 근거)
