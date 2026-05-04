Notion 원본: https://www.notion.so/3565a06fd6d38180a2acc9b3ef0f9de1

# TypeScript Stage 3 Decorators와 Decorator Metadata — 신표준 데코레이터 실전

> 2026-05-04 신규 주제 · 확장 대상: TypeScript Conditional Types, Compiler API

## 학습 목표

- TC39 Stage 3 데코레이터(=ES Decorators)와 TypeScript 의 legacy `experimentalDecorators` 가 시그니처·실행 모델·메타데이터 측면에서 어디서 갈라지는지 구분한다
- Stage 3 데코레이터의 다섯 가지 종류(class · method · getter/setter · accessor · field)에 대해 각각 받는 컨텍스트 객체와 반환값 의미를 코드로 검증한다
- `context.metadata` 와 `Symbol.metadata` 기반의 표준 메타데이터 수집 방식을 reflect-metadata 와 비교하고, 마이그레이션 시 함정을 정리한다
- NestJS · TypeORM · class-validator 가 신표준으로 옮겨가는 시점 전후의 호환 전략(`useDefineForClassFields`, ts-node loader, tsc 플래그)을 의사결정 기준으로 정리한다

## 1. legacy 데코레이터와 Stage 3 데코레이터의 본질적 차이

TypeScript 5.0 이전 10여 년간 코드베이스에 자리 잡은 `experimentalDecorators` 는 사실상 *2016년 TC39 Stage 1 제안* 의 변종이다. 시그니처는 다음과 같다.

```ts
function legacyMethod(
  target: any,
  propertyKey: string | symbol,
  descriptor: PropertyDescriptor,
): PropertyDescriptor | void {}
```

`target` 은 메서드의 경우 prototype, static 메서드의 경우 constructor 그 자체였고 데코레이터는 `PropertyDescriptor` 를 직접 조작하거나 새 descriptor 를 반환해 메서드를 교체했다. 메타데이터는 reflect-metadata 가 추가로 제공하는 `Reflect.defineMetadata("design:type", ...)` 방식이었다.

Stage 3 표준은 시그니처 자체가 다르다.

```ts
type ClassMethodDecorator = (
  value: Function,
  context: {
    kind: "method"
    name: string | symbol
    static: boolean
    private: boolean
    access: { has(obj: object): boolean, get(obj: object): unknown }
    addInitializer(fn: () => void): void
    metadata: DecoratorMetadata
  }
) => Function | void
```

핵심 변화는 세 가지다. 첫째, *target* 이 사라지고 호출 시점에 데코레이터가 받는 첫 인자는 데코레이트되는 값(메서드 함수, 필드의 초기화 함수, 클래스 생성자 등) 그 자체다. 둘째, prototype 직접 변경 대신 *반환값* 으로 대체값을 전달하고 런타임이 적절한 슬롯에 꽂는다. 셋째, 메타데이터는 `context.metadata` 라는 표준 객체를 통해 누적되고 클래스 정의가 끝나면 `Class[Symbol.metadata]` 로 노출된다.

차이의 의미는 단순히 시그니처가 바뀐 게 아니라, *데코레이터가 클래스의 형태를 임의로 변형할 수 없게* 잠긴 점이다. legacy 시절 데코레이터는 prototype 에 새 메서드를 꽂거나 다른 클래스의 descriptor 를 훔칠 수 있었다. Stage 3 에서는 데코레이트 대상이 명시되어 있고 반환값으로만 교체할 수 있어 정적 분석과 트리 셰이킹이 깔끔해진다.

## 2. 다섯 종류 데코레이터 컨텍스트 표

| kind | 받는 value | 반환할 수 있는 값 | 용도 예 |
|---|---|---|---|
| `class` | constructor | 새 constructor (서브클래싱) 또는 void | mixin 추가, 등록(register) |
| `method` | 메서드 함수 | 대체 함수 또는 void | 로깅, 트랜잭션, 캐시 |
| `getter` / `setter` | accessor 함수 | 대체 함수 또는 void | 값 변환, 검증 |
| `accessor` | `{ get, set }` 객체 | `{ get, set, init }` 또는 void | 상태 추적, observable |
| `field` | undefined | 초기화 함수(`(initialValue) => newValue`) 또는 void | 기본값 변환, 의존성 주입 |

`accessor` 키워드 자체가 신표준이다. 다음과 같이 선언하면 컴파일러가 private storage slot 과 getter/setter 한 쌍을 자동 생성한다.

```ts
class User {
  accessor name = ""
}
```

`accessor` 데코레이터는 이 자동 생성된 `{ get, set }` 페어에 끼어들 수 있다. 즉 *프로퍼티 접근* 을 가로채는 가장 깔끔한 표준 메커니즘이다.

## 3. method 데코레이터로 트랜잭션 래핑

표준 데코레이터로 가장 많이 쓰이는 패턴이 메서드 래핑이다. 서비스 레이어의 메서드를 트랜잭션 경계로 감싸는 코드를 표준 시그니처로 작성하면 다음과 같다.

```ts
function transactional<T extends (this: any, ...args: any[]) => Promise<any>>(
  value: T,
  context: ClassMethodDecoratorContext,
): T {
  if (context.kind !== "method") throw new Error("@transactional is method-only")

  const replacement = async function (this: any, ...args: any[]) {
    const tx = await this.txManager.begin()
    try {
      const result = await value.apply(this, args)
      await tx.commit()
      return result
    } catch (e) {
      await tx.rollback()
      throw e
    }
  } as T

  return replacement
}

class OrderService {
  constructor(private txManager: TxManager, private repo: OrderRepo) {}

  @transactional
  async place(orderId: string) {
    await this.repo.insert(orderId)
    await this.repo.markPaid(orderId)
  }
}
```

legacy 와 비교했을 때 두 가지가 확연하다. 첫째, `target.prototype` 을 건드리지 않고 *반환된 함수* 가 메서드 슬롯을 자동으로 차지한다. 둘째, `context` 에 `kind` 와 `name` 이 들어 있어 한 데코레이터를 다른 종류에 잘못 붙였을 때 런타임에 명확한 오류를 던질 수 있다.

주의할 함정은 `this` 타이핑이다. legacy 는 `target: any, propertyKey, descriptor` 였기 때문에 `this` 가 어디서 묶이는지 약속이 모호했지만, Stage 3 에서는 반환 함수를 정의하는 시점에 `this: any` 를 명시해야 인스턴스 문맥을 받을 수 있다. 안 그러면 strict 옵션에서 `this` 가 `void` 로 추론되어 `this.txManager` 접근이 컴파일 오류가 난다.

## 4. addInitializer 와 클래스 등록 패턴

`context.addInitializer(fn)` 는 클래스/메서드/필드 정의가 끝난 직후 런타임이 호출해 주는 콜백이다. legacy 시절 reflect-metadata 와 함께 가장 많이 쓰이던 "데코레이터 시점에 컨테이너에 자기 자신 등록" 패턴이 여기 들어간다.

```ts
const handlers = new Map<string, (req: Request) => Response>()

function route(path: string) {
  return function (value: Function, context: ClassMethodDecoratorContext) {
    if (context.kind !== "method") throw new Error("@route is method-only")
    context.addInitializer(function (this: any) {
      handlers.set(path, value.bind(this))
    })
  }
}

class GreetController {
  @route("/hello")
  hello(_req: Request): Response {
    return new Response("hello")
  }
}
```

`addInitializer` 안의 `this` 는 *static* 데코레이터에서는 클래스 자체, *인스턴스* 데코레이터에서는 인스턴스다. 이 차이 때문에 `static` 메서드 라우팅 등록과 인스턴스 메서드 등록은 구현이 달라진다. legacy 시절에는 이걸 `propertyKey` 와 `target` 의 타입 비교로 분기했다면, 이제는 `context.static` boolean 으로 간단히 구분된다.

## 5. context.metadata 와 Symbol.metadata 표준 메타데이터

reflect-metadata 의 `Reflect.defineMetadata` / `Reflect.getMetadata` 는 polyfill 이 없으면 동작하지 않았고, `design:type` / `design:paramtypes` 같은 키도 TypeScript 컴파일러가 별도 옵션(`emitDecoratorMetadata`)으로 만들어 줄 때만 채워졌다. Stage 3 는 이걸 표준화했다.

```ts
function tagged(tag: string) {
  return function (_value: any, context: ClassFieldDecoratorContext) {
    context.metadata[context.name as string] = tag
  }
}

class Article {
  @tagged("indexable")
  title = ""

  @tagged("internal")
  draft = false
}

const meta = (Article as any)[Symbol.metadata] as Record<string, string>
console.log(meta.title) // "indexable"
console.log(meta.draft) // "internal"
```

표준 모델의 핵심 두 가지: (1) `context.metadata` 는 *현재 클래스의* 메타데이터 객체이고 부모 클래스 메타데이터는 prototype chain 으로 자동 연결된다. (2) 클래스 정의가 끝나면 동일 객체가 `Class[Symbol.metadata]` 로 노출되어 외부에서 일관되게 읽을 수 있다.

reflect-metadata 의 `Reflect.getMetadata("design:paramtypes", target, key)` 처럼 *생성자 파라미터 타입* 을 자동으로 emit 해 주던 기능은 Stage 3 표준 자체에는 들어 있지 않다. NestJS 같이 DI 컨테이너가 파라미터 타입에 의존하는 프레임워크는 한동안 reflect-metadata 와 표준 메타데이터를 *동시에* 사용하는 하이브리드 모드로 운영해야 한다. tsc 5.2+ 에서 `experimentalDecorators: false` + `emitDecoratorMetadata: true` 조합도 가능하지만, 이 경우 `__metadata` helper 가 표준 데코레이터와 별개의 경로로 emit 된다.

## 6. 데코레이터 합성 순서와 평가 시점

데코레이터를 한 메서드에 여러 개 붙였을 때의 평가 순서는 legacy 와 신표준 모두 *위에서 아래로 평가, 안에서 밖으로 적용* 이다. 즉 `@A @B method()` 라면 `A` 와 `B` 의 *데코레이터 함수 자체가 호출되는 순서* 는 위에서 아래(A → B), 그러나 *반환된 함수가 메서드 슬롯에 적용되는 순서* 는 안쪽부터(B 가 먼저 메서드를 감싸고 A 가 그걸 다시 감쌈) 이다.

```ts
function trace(label: string) {
  return function (value: any, context: ClassMethodDecoratorContext) {
    console.log(`[decorator factory] ${label} on ${String(context.name)}`)
    return function (this: any, ...args: any[]) {
      console.log(`[wrapped] ${label} enter`)
      const r = value.apply(this, args)
      console.log(`[wrapped] ${label} exit`)
      return r
    }
  }
}

class S {
  @trace("A")
  @trace("B")
  m() { console.log("[body]") }
}

new S().m()
// 데코레이터 factory 호출 순서 : A, B (위에서 아래)
// 적용 순서                      : B, A (안에서 밖으로)
// 호출 시 콘솔                    : A enter → B enter → body → B exit → A exit
```

표준 모델에서도 이 규칙은 보존됐다. 차이는 `accessor` 와 `field` 데코레이터가 추가되면서 *초기화 시점* 과 *접근 시점* 두 단계가 분리되었다는 점이다. field 데코레이터는 인스턴스 생성 시점에 초기화 함수가 호출되고, method 데코레이터는 클래스 정의 시점에 메서드 슬롯이 결정된다. 두 종류를 같은 클래스에 섞어 쓰는 경우 데코레이터 작성자는 *어떤 시점에 자기 코드가 실행되는지* 를 명시적으로 인지해야 한다.

## 7. tsconfig 옵션 매트릭스와 마이그레이션

| 옵션 | legacy 사용 | Stage 3 사용 | 비고 |
|---|---|---|---|
| `experimentalDecorators` | true | false (또는 미설정) | 두 모드 동시 사용 불가 |
| `emitDecoratorMetadata` | true (필요 시) | true (선택) | reflect-metadata 와의 브릿지 |
| `useDefineForClassFields` | false 권장 | true 필수 | 표준은 `[[Define]]` 시맨틱 |
| `target` | ES2015+ | ES2022+ 권장 | private fields, accessor 키워드 |
| `module` | 자유 | NodeNext / ESNext 권장 | TLA 와 데코레이터 등록 패턴 |

마이그레이션 시 가장 자주 발생하는 충돌은 `useDefineForClassFields` 다. legacy 데코레이터 코드는 필드를 *할당* 으로 처리한다고 가정하고 `target.prototype.x = ...` 같은 prototype 수준 조작에 의존하는 경우가 많다. 표준은 ECMAScript 의 `[[Define]]` 시맨틱을 따르므로 인스턴스 슬롯에 자체 속성으로 정의된다. 이 차이로 reflect-metadata 기반의 ORM(예: TypeORM 0.3 이전 버전) 매핑이 깨지는 사례가 많다.

권장 마이그레이션 순서는: (1) 코드베이스를 `useDefineForClassFields: true` 로 먼저 옮기고 (2) 모든 데코레이터를 한 번에 `experimentalDecorators: false` 로 전환하지 말고 (3) 새로 작성하는 클래스만 표준 데코레이터로 작성, 기존 코드는 reflect-metadata 와 `__decorate` helper 가 함께 emit 되는 하이브리드 모드를 유지한다.

## 8. 런타임 성능과 트리 셰이킹 영향

legacy 데코레이터는 `__decorate(decorators, target, key, desc)` 라는 helper 를 모든 사용 지점에 emit 한다. 이 helper 는 클래스 정의 시점에 *모든 데코레이터를 배열로 모아* 실행하는 구조라, 데코레이터 함수 자체가 dead code 라도 트리 셰이킹이 어려웠다.

표준 데코레이터는 ESM friendly 구조로 emit 된다. tsc 가 만들어 내는 코드는 데코레이터 호출을 인라인하고 `Symbol.metadata` 같은 표준 심볼만 사용한다. esbuild 와 swc 도 동일한 출력을 보장한다. 따라서 사용되지 않는 데코레이터는 정적으로 제거 가능하고, 번들러가 sideEffects: false 패키지를 더 적극적으로 트리 셰이킹할 수 있다.

다만 reflect-metadata 와의 하이브리드 모드를 유지하는 동안에는 `__metadata` helper 와 표준 emit 이 함께 들어가 번들 사이즈가 일시적으로 늘어난다. 마이그레이션을 끝내면 reflect-metadata polyfill(약 60KB)을 통째로 제거할 수 있다.

## 참고

- TC39 Decorators Proposal: https://tc39.es/proposal-decorators/
- TypeScript 5.0 Release Notes (Decorators): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript 5.2 Release Notes (using, accessor decorator): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html
- TC39 Decorator Metadata Proposal: https://github.com/tc39/proposal-decorator-metadata
- Decorators in ECMAScript 2024 (Axel Rauschmayer): https://2ality.com/2022/10/javascript-decorators.html
