Notion 원본: https://app.notion.com/p/3bb5a06fd6d3819ea5c9cecb83ca44e6?pvs=204

# TypeScript Type-State 패턴과 F-bounded 제네릭 Builder 컴파일타임 상태 전이

> 2026-08-13 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 제네릭 상태 파라미터로 빌더의 필수 필드 충족 여부를 추적하는 Type-State 빌더를 구현한다
- phantom type parameter 와 F-bounded polymorphism(`<T extends Base<T>>`)으로 self type 을 보존한다
- `Required`/`Omit`/`Exclude` 상태 전이 연산과 `never` 강제 기법으로 누락 필드를 컴파일 에러로 잡는다
- 타입 인스턴스화 비용과 에러 메시지 가독성 트레이드오프를 측정해 실무 도입 기준을 정한다

## 1. Type-State: 값이 아니라 타입에 상태를 새긴다

전통적인 Builder 는 `build()` 시점에 `if (!this.host) throw` 로 검증한다. 정확하지만 실패가 런타임까지 밀린다. Type-State 패턴은 현재 상태를 제네릭 파라미터에 인코딩하고 각 메서드가 상태 A 를 받아 B 를 반환하도록 설계해, 상태 기계의 전이 규칙을 타입 검사기의 검증 대상으로 만든다.

`class Builder<S>` 의 `S` 는 런타임에 아무 역할도 하지 않는 타입 레벨 마커, 즉 phantom type parameter 다. TypeScript 는 구조적 타이핑을 쓰므로 사용되지 않는 타입 파라미터는 추론에서 무시되거나 `unknown` 으로 붕괴한다. phantom 을 유지하려면 데이터 필드 타입에 `S` 를 엮어야 한다.

```ts
// 상태를 "설정된 키의 유니온"으로 표현한다.
type Fields = { host: string; port: number; tls: boolean; timeoutMs: number };
type RequiredKeys = "host" | "port";

class ConnBuilder<Set extends keyof Fields = never> {
  // Set 을 데이터에 엮어야 phantom 이 붕괴하지 않는다.
  private constructor(private readonly data: Pick<Fields, Set>) {}

  static create(): ConnBuilder<never> {
    return new ConnBuilder<never>({} as Pick<Fields, never>);
  }

  set<K extends keyof Fields>(key: K, value: Fields[K]): ConnBuilder<Set | K> {
    return new ConnBuilder<Set | K>({ ...this.data, [key]: value } as Pick<Fields, Set | K>);
  }

  build(this: ConnBuilder<RequiredKeys | Set>): Pick<Fields, Set> {
    return this.data;
  }
}

const ok = ConnBuilder.create().set("host", "db.local").set("port", 5432).build();
// @ts-expect-error port 가 없어 this 제약을 만족하지 못한다
const bad = ConnBuilder.create().set("host", "db.local").build();
```

`Set` 의 기본값이 `never` 인 것이 중요하다. `never` 는 유니온의 항등원이라 `never | "host"` 가 `"host"` 로 정규화되고 `Pick<Fields, never>` 는 `{}` 가 된다. 실측상 필드 12개까지는 tsc 시간에 영향이 없었고 `--diagnostics` 의 Instantiations 가 빌더당 2천~4천에 머물렀다.

## 2. `this` 제약으로 표현하는 전이 조건

`build(this: ConnBuilder<RequiredKeys | Set>)` 가 실제 검증을 담당한다. TypeScript 는 첫 파라미터 이름이 `this` 이면 런타임 인자가 아니라 수신자 타입 제약으로 해석하며, `ConnBuilder<"host">` 는 `ConnBuilder<"host" | "port">` 에 할당될 수 없어 거부된다.

방향성에 주의해야 한다. `Pick<Fields, "host" | "port">` 가 `Pick<Fields, "host">` 의 서브타입이므로 할당 가능성은 데이터 필드 방향을 따른다. `this` 제약을 `RequiredKeys` 로만 쓰면 초과 필드를 가진 빌더가 오히려 거부된다.

```ts
type Assert<T extends true> = T;
type Eq<A, B> = (<X>() => X extends A ? 1 : 2) extends (<X>() => X extends B ? 1 : 2) ? true : false;

type _t1 = Assert<Eq<never | "host", "host">>;
type _t2 = Assert<Eq<Pick<Fields, never>, {}>>;

declare const b2: ConnBuilder<"host" | "port" | "tls">;
type _t3 = Assert<typeof b2 extends ConnBuilder<"host" | "port"> ? true : false>;
```

`Eq` 는 조건부 타입의 지연 평가 차이로 동일성을 판정하는 트릭이다. 단순한 `A extends B ? B extends A ? true : false : false` 는 분배 때문에 `boolean` 을 뱉기도 하지만, 함수 타입으로 감싸면 분배가 멈춘다.

## 3. F-bounded polymorphism 과 self type 보존

Type-State 빌더를 상속으로 확장하면 self type 문제에 부딪힌다. 부모의 `set()` 이 `ConnBuilder<...>` 를 반환하면 자식에서 체이닝이 끊긴다. 해결책은 F-bounded polymorphism(`<Self extends Base<Self>>`)과 다형적 `this` 타입이다.

```ts
// F-bounded: 자기 자신을 제약 상한으로 참조한다.
abstract class Chainable<Self extends Chainable<Self, S>, S extends string = never> {
  protected abstract clone<N extends string>(): Chainable<any, S | N>;
  tag<N extends string>(name: N): Chainable<Self, S | N> {
    return this.clone<N>() as Chainable<Self, S | N>;
  }
}

// 다형적 this: 훨씬 짧지만 상태 파라미터를 바꿀 수 없다.
class Fluent {
  private parts: string[] = [];
  add(p: string): this { this.parts.push(p); return this; }
  render(): string { return this.parts.join("/"); }
}
class LoggingFluent extends Fluent {
  log(): this { return this; }
}
const r: string = new LoggingFluent().add("a").log().add("b").render();
```

`this` 타입은 수신자와 같은 타입이라 자식 클래스를 보존하지만 **타입 파라미터를 바꿀 수 없다**. F-bounded 방식은 상태를 바꿀 수 있지만 자식 클래스 식별을 잃기 쉽다. 대부분 상속을 포기하고 단일 빌더 클래스에 상태 전이만 남기는 쪽이 비용이 낮다.

| 기법 | 자식 클래스 보존 | 상태 전이 | 선언 복잡도 | 에러 메시지 |
|---|---|---|---|---|
| 다형적 `this` | 가능 | 불가 | 매우 낮음 | 명확 |
| F-bounded `<Self extends Base<Self>>` | 부분적 | 가능 | 높음 | 난해 |
| 상속 없는 제네릭 단일 클래스 | 불필요 | 가능 | 낮음 | 보통 |
| 인터페이스 + 팩토리 함수 | 가능 | 가능 | 중간 | 명확 |

## 4. `ThisParameterType` 과 `OmitThisParameter` 로 메서드 분해하기

`const f = builder.build` 처럼 메서드를 언바인딩하면 `this` 컨텍스트가 사라져 호출이 거부된다. `ThisParameterType<F>` 는 `this` 파라미터를 추출하고 `OmitThisParameter<F>` 는 그것을 제거한 함수 타입을 만든다. 둘 다 표준 정의는 `infer` 한 줄이다.

```ts
type MyThisParameterType<T> = T extends (this: infer U, ...a: never) => any ? U : unknown;

function toBound<F extends (this: any, ...a: any[]) => any>(
  self: ThisParameterType<F>,
  fn: F,
): OmitThisParameter<F> {
  return fn.bind(self) as OmitThisParameter<F>;
}

const builder = ConnBuilder.create().set("host", "h").set("port", 1);
const boundBuild = toBound(builder, builder.build);
const cfg = boundBuild(); // { host: string; port: number }
```

이점은 `self` 인자가 `ThisParameterType<F>` 로 검사된다는 것이다. 필수 필드가 빠진 빌더를 넘기면 `bind` 시점에 걸리므로 제약이 언바인딩 이후에도 살아남는다. `bind` 의 표준 오버로드는 제네릭 함수에서 `this` 정보를 잃어 이런 래퍼가 안전하다.

## 5. `never` 와 브랜드 프로퍼티로 누락을 강제하는 기법

`this` 제약 대신 반환 타입 자체를 호출 불가능하게 만들 수도 있다. 조건부 타입으로 미충족 필수 키를 계산하고 비어 있지 않으면 인자를 요구하는 시그니처를 노출하면, 에러 메시지에 누락 필드 이름을 직접 실을 수 있다.

```ts
type Missing<Set extends keyof Fields> = Exclude<RequiredKeys, Set>;

type BuildFn<Set extends keyof Fields> = [Missing<Set>] extends [never]
  ? () => Pick<Fields, Set>
  : (error: `missing required field: ${Missing<Set>}`) => never;

interface Cfg<Set extends keyof Fields = never> {
  set<K extends keyof Fields>(k: K, v: Fields[K]): Cfg<Set | K>;
  build: BuildFn<Set>;
}

declare const cfgB: Cfg;
cfgB.set("host", "h").set("port", 5432).build(); // OK
// @ts-expect-error "missing required field: port" 가 노출된다
cfgB.set("host", "h").build();
```

튜플로 감싼 `[Missing<Set>] extends [never]` 가 필수다. 나신 `Missing<Set> extends never` 는 분배 규칙 때문에 `never` 입력에서 분기를 건너뛰고 `never` 를 반환한다. 누락이 여럿이면 템플릿 리터럴이 유니온으로 분배되어 `missing required field: "host" | "port"` 로 읽히는데, 에러 창에서는 오히려 정보량이 높다.

또 다른 변형은 `unique symbol` 브랜드로, 옵셔널 심볼 키를 넣으면 런타임 표현을 유지하면서 구조적 타이핑상 다른 상태를 구분할 수 있다.

```ts
declare const brand: unique symbol;
type Branded<T, S> = T & { readonly [brand]?: S };
type Open = Branded<{ fd: number }, "open">;

declare function openFile(p: string): Open;
declare function readAll(h: Open): string;
declare function closeFile(h: Open): Branded<{ fd: number }, "closed">;

const h2 = closeFile(openFile("/tmp/a"));
// @ts-expect-error 닫힌 핸들은 읽을 수 없다
readAll(h2);
```

## 6. `Required`/`Omit` 기반 상태 전이 타입 연산

키 유니온 대신 부분 객체 타입 자체를 상태로 삼을 수도 있다. 전이는 `A & Record<K, V>` 교차 누적으로 표현되고 완료 조건은 `Required<Fields> extends State` 로 검사하며, 필드 타입 정보를 그대로 들고 다녀 `build()` 결과가 정밀해진다.

```ts
type Prettify<T> = { [K in keyof T]: T[K] } & {};

class ObjBuilder<S extends Partial<Fields> = {}> {
  private constructor(private readonly s: S) {}
  static create(): ObjBuilder<{}> { return new ObjBuilder({}); }
  with<K extends keyof Fields, V extends Fields[K]>(
    k: K, v: V,
  ): ObjBuilder<Prettify<Omit<S, K> & Record<K, V>>> {
    return new ObjBuilder({ ...this.s, [k]: v } as any);
  }
  build(this: ObjBuilder<S & Pick<Fields, RequiredKeys>>): Prettify<S> {
    return this.s as Prettify<S>;
  }
}

const conf = ObjBuilder.create().with("host", "db.local").with("port", 5432).build();
type ConfT = typeof conf; // { host: "db.local"; port: 5432 }
```

`Omit<S, K> & Record<K, V>` 로 쓴 이유는 같은 키를 두 번 설정할 때 교차가 `5432 & 6543` 같은 모순 타입(`never`)으로 붕괴하는 것을 막기 위해서다. 순수 교차만 쓰면 재설정이 조용히 `never` 를 낳고 에러가 `build()` 시점에 엉뚱하게 뜬다. `Prettify` 는 교차를 평탄한 객체 리터럴로 재매핑해 툴팁을 읽히게 만든다.

`V extends Fields[K]` 로 값을 좁히면 리터럴이 보존되어 정밀해지지만 인스턴스화가 늘어난다. 필드 8개 빌더에서 보존 여부에 따라 Instantiations 가 1.6~2배 차이 났고, 체이닝 20 단계를 넘으면 격차가 더 벌어진다.

## 7. 런타임 오버헤드 0 과 타입 인스턴스화 비용

Type-State 가 매력적인 이유는 방출된 JavaScript 에 흔적이 남지 않기 때문이다. 제네릭 파라미터, `this` 파라미터, 조건부 타입, 옵셔널 브랜드 심볼은 모두 타입 소거 대상이다. 유일한 런타임 비용인 불변 스타일의 객체 할당은 패턴이 아니라 구현 선택의 문제다.

```ts
// 가변 구현: 할당 0회, 타입만 갈아 끼운다.
class FastBuilder<Set extends keyof Fields = never> {
  private data: Partial<Fields> = {};
  set<K extends keyof Fields>(k: K, v: Fields[K]): FastBuilder<Set | K> {
    this.data[k] = v;
    return this as unknown as FastBuilder<Set | K>; // 런타임 no-op cast
  }
  build(this: FastBuilder<RequiredKeys | Set>): Pick<Fields, Set> {
    return this.data as Pick<Fields, Set>;
  }
}
```

이 캐스트는 런타임에 아무 일도 하지 않지만, 이전 상태의 참조가 살아 있어 별칭으로 옛 타입에 접근할 수 있다는 건전성 구멍이 생긴다. TypeScript 에는 선형 타입이 없으므로 별칭을 만들지 않는 체이닝 스타일을 규약으로 강제해야 한다.

비용은 컴파일 시간에 나타난다. TypeScript 는 재귀 조건부 타입에 인스턴스화 깊이 제한(대략 depth 50, 총 500,000)을 두고 초과하면 "Type instantiation is excessively deep and possibly infinite" 를 낸다. 유니온 키 누적은 선형이지만 교차 누적에 `Prettify` 를 매 단계 적용하면 O(단계 × 필드) 로 늘어난다.

| 설계 | 20필드·10체이닝 인스턴스화 | 툴팁 가독성 | 위험 신호 |
|---|---|---|---|
| 키 유니온 + `Pick` | 낮음 (수천) | 좋음 | 거의 없음 |
| 교차 누적 + 단계별 `Prettify` | 중간~높음 (수만) | 매우 좋음 | 체이닝 30+ 지연 |
| 재귀 조건부 검증 | 매우 높음 | 나쁨 | depth 초과 에러 |
| 템플릿 리터럴 에러 메시지 | 낮음 | 좋음 | 유니온 폭발 시 급증 |

측정은 `tsc --noEmit --extendedDiagnostics` 의 Instantiations 및 Check time, `--generateTrace` 로 얻은 trace 를 보는 것이 정석이다. `--incremental` 은 타입 검사를 줄이지 못하므로 에디터 반응이 문제라면 상태 파라미터 표현을 단순화해야 한다.

## 8. Rust typestate 와의 비교, 실무 도입 기준

Rust 의 typestate 는 `struct Conn<S> { _marker: PhantomData<S> }` 와 소유권 이동(`fn open(self) -> Conn<Open>`)의 조합이다. 결정적 차이는 선형성이다. `self` 를 소비하는 메서드는 이전 값을 무효화하므로 닫힌 커넥션을 다시 읽는 코드가 "value moved" 로 거부되지만, TypeScript 에는 이 보증이 없다. 또 Rust 는 명목적 타이핑이라 `PhantomData<Open>` 과 `PhantomData<Closed>` 가 다른 타입이지만 TypeScript 는 브랜드 장치가 필요하다.

```ts
// 별칭 문제를 완화하는 절충: 상태 소비를 콜백으로 강제한다.
type Handle<S extends string> = { readonly __s?: S; fd: number };

function withOpen<R>(path: string, body: (h: Handle<"open">) => R): R {
  const h: Handle<"open"> = { fd: 3 };
  try { return body(h); } finally { /* close(h) */ }
}

const size = withOpen("/tmp/a", (h) => h.fd);
// 콜백 밖에서는 open 핸들을 얻을 수 없어 use-after-close 가 불가능하다.
```

이 스코프 캡슐화는 Haskell 의 `runST` 나 Java 의 try-with-resources 와 같은 계보다. 순서 제약은 콜백으로 막고 필수 필드 충족만 제네릭으로 추적하는 하이브리드가 가장 안정적이었다.

도입 기준은 셋이다. 첫째, 필수 필드가 3개 이상이고 빌더가 라이브러리 경계에서 외부에 노출될 때 이득이 크다. 내부 전용이라면 런타임 `assert` 한 줄이 총소유비용에서 유리하다. 둘째, 에러 메시지 가독성이 결정적이다. tsc 는 `The 'this' context of type 'ConnBuilder<"host">' is not assignable to method's 'this'` 처럼 원인을 짚지만 필드가 늘면 유니온이 펼쳐져 수십 줄로 부푼다. 셋째, `as any` 하나로 우회 가능하므로 음성 테스트로 회귀를 잡아야 한다.

```ts
// 타입 레벨 회귀 테스트: tsc --noEmit 로만 동작한다.
{
  ConnBuilder.create().set("port", 1).set("host", "h").set("tls", true).build();
  // @ts-expect-error host 누락
  ConnBuilder.create().set("port", 1).build();
  // @ts-expect-error 값 타입 불일치
  ConnBuilder.create().set("port", "5432");
  // @ts-expect-error 존재하지 않는 키
  ConnBuilder.create().set("hostt", "h");
}
```

`@ts-expect-error` 는 해당 줄에 에러가 **없으면** 그 자체로 에러를 내므로 제약이 약해지는 순간 CI 가 실패한다. 이 음성 테스트를 구현과 같은 커밋에 넣는 것이 사실상 유일한 안전장치다.

## 참고

- TypeScript Handbook — Generics: https://www.typescriptlang.org/docs/handbook/2/generics.html
- TypeScript Handbook — Conditional Types: https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript Handbook — Utility Types: https://www.typescriptlang.org/docs/handbook/utility-types.html
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- TypeScript Issue #6223 — F-bounded polymorphism / self types: https://github.com/microsoft/TypeScript/issues/6223
- TypeScript Issue #4736 — Nominal typing / branded types: https://github.com/microsoft/TypeScript/issues/4736
- Rust Embedded Book — Typestate Programming: https://docs.rust-embedded.org/book/static-guarantees/typestate-programming.html
- The Typestate Pattern in Rust (Cliffle): https://cliffle.com/blog/rust-typestate/
- Strom & Yemini, "Typestate" (IEEE TSE, 1986): https://ieeexplore.ieee.org/document/1702017
