Notion 원본: https://www.notion.so/3805a06fd6d38170956fef3d3442be35

# TypeScript Stage 3 데코레이터와 Reflect Metadata 기반 DI 컨테이너

> 2026-06-15 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- Stage 3 데코레이터(ECMAScript 표준)와 레거시 `experimentalDecorators`의 시그니처·동작 차이를 구분한다
- 클래스·메서드·필드·accessor 데코레이터의 실행 시점과 컨텍스트 객체를 활용한다
- `addInitializer`와 데코레이터 메타데이터(`Symbol.metadata`)로 런타임 정보를 축적한다
- Reflect Metadata 기반 생성자 주입 DI 컨테이너의 동작 원리와 한계를 구현한다

## 1. 두 종류의 데코레이터를 먼저 구분한다

레거시 구현은 `experimentalDecorators: true`로 켜며 TC39 stage 1 제안과 `reflect-metadata`에 기반한다. Angular와 NestJS가 이것을 쓴다. Stage 3 데코레이터는 TypeScript 5.0부터 기본 활성화되며 ECMAScript 표준 트랙을 따른다. 두 구현은 시그니처가 완전히 달라 혼용할 수 없다.

핵심 차이: 레거시는 파라미터 데코레이터와 `design:paramtypes` 타입 메타데이터 방출(`emitDecoratorMetadata`)을 지원하지만, Stage 3는 둘 다 없고 대신 컨텍스트 객체를 두 번째 인자로 받아 대상의 정보(이름·종류·static 여부 등)에 접근한다.

```json
// 레거시 (NestJS/Angular)
{ "compilerOptions": { "experimentalDecorators": true, "emitDecoratorMetadata": true } }
// Stage 3 표준 (TS 5.0+ 기본값)
{ "compilerOptions": { "target": "ES2022" } }
```

## 2. Stage 3 데코레이터의 시그니처와 컨텍스트

Stage 3 데코레이터는 `(value, context) => newValue | void` 형태다. `context`가 표준화된 메타정보(kind, name, static, private, access, addInitializer, metadata)를 담는다.

```typescript
function logged<This, Args extends unknown[], Return>(
  target: (this: This, ...args: Args) => Return,
  context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>,
) {
  const name = String(context.name);
  return function (this: This, ...args: Args): Return {
    const start = performance.now();
    const result = target.call(this, ...args);
    console.log(`${name} took ${(performance.now() - start).toFixed(2)}ms`);
    return result;
  };
}

class Service {
  @logged
  compute(n: number): number { return n * 2; }
}
```

`ClassMethodDecoratorContext`는 `This`와 메서드 시그니처를 보존해 타입 안전성을 유지한다.

## 3. 실행 시점과 평가 순서

데코레이터 표현식은 클래스 정의 평가 시 위에서 아래로 평가되지만, 적용은 멤버 종류별로 처리되고 클래스 데코레이터가 가장 마지막이다. 같은 멤버에 여러 데코레이터를 쌓으면 평가는 위→아래, 적용은 아래→위로 일어난다.

```typescript
class Demo {
  @first()
  @second()
  method() {}
}
// first evaluated → second evaluated → second applied → first applied
```

이는 함수 합성 `first(second(x))`와 일치한다. 미들웨어 체인을 데코레이터로 구성할 때 이 순서를 모르면 버그가 생긴다.

## 4. addInitializer로 인스턴스 초기화 가로채기

```typescript
function bound<This, Args extends unknown[], Return>(
  target: (this: This, ...args: Args) => Return,
  context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>,
) {
  const name = context.name;
  context.addInitializer(function (this: This) {
    (this as Record<PropertyKey, unknown>)[name] = (target as Function).bind(this);
  });
}

class Counter {
  count = 0;
  @bound increment() { this.count += 1; }
}
const c = new Counter();
const fn = c.increment;
fn(); // this 유지 → count === 1
```

## 5. 데코레이터 메타데이터 (Symbol.metadata)

같은 클래스의 모든 데코레이터가 동일한 `context.metadata`를 공유하며, `클래스[Symbol.metadata]`로 접근한다.

```typescript
(Symbol as { metadata?: symbol }).metadata ??= Symbol("Symbol.metadata");

function required(_: unknown, context: ClassFieldDecoratorContext) {
  const meta = context.metadata as Record<string, { validators: string[] }>;
  const key = String(context.name);
  (meta[key] ??= { validators: [] }).validators.push("required");
}

class Dto { @required name!: string; }
const collected = Dto[Symbol.metadata]; // { name: { validators: ["required"] } }
```

`reflect-metadata` 없이 클래스 전반의 메타데이터를 수집해, 검증 라이브러리가 표준 기반으로 이동하는 토대가 된다.

## 6. Reflect Metadata 기반 생성자 주입 DI

`emitDecoratorMetadata`가 켜지면 컴파일러가 생성자 파라미터 타입을 `design:paramtypes`로 방출하고, 컨테이너가 이를 읽어 의존성을 재귀 해석한다.

```typescript
import "reflect-metadata";
type Constructor<T = unknown> = new (...args: unknown[]) => T;
const container = new Map<Constructor, unknown>();

function injectable<T extends Constructor>(target: T): T { return target; }

function resolve<T>(target: Constructor<T>): T {
  if (container.has(target)) return container.get(target) as T;
  const paramTypes: Constructor[] = Reflect.getMetadata("design:paramtypes", target) ?? [];
  const deps = paramTypes.map((dep) => resolve(dep));
  const instance = new target(...deps);
  container.set(target, instance);
  return instance;
}

@injectable class Repository { find() { return "data"; } }
@injectable class UserService {
  constructor(private readonly repo: Repository) {}
  load() { return this.repo.find(); }
}
const service = resolve(UserService);
```

`Reflect.getMetadata("design:paramtypes", UserService)`가 `[Repository]`를 반환하므로 컨테이너가 의존성을 먼저 해석해 주입한다. 이것이 NestJS DI의 핵심 원리다.

## 7. emitDecoratorMetadata의 한계와 함정

`design:paramtypes`는 런타임 값이 있는 클래스 참조만 의미가 있다. 인터페이스·타입 별칭·유니온·제네릭은 모두 `Object`로 방출되어 인터페이스를 직접 주입할 수 없고 `@Inject(TOKEN)` 우회가 필요하다. 순환 의존성은 `forwardRef`가 필요하다. 가장 중요한 변화는 표준 Stage 3 데코레이터에는 `emitDecoratorMetadata`가 없어 이 DI 패턴이 그대로 이식되지 않는다는 점이다 — 토큰 명시나 빌드 타임 코드 생성으로 보완해야 한다.

## 8. 어느 쪽을 선택할 것인가

NestJS·Angular·TypeORM·class-validator 현행 버전에 종속된 코드는 여전히 `experimentalDecorators: true`가 필요하다. 신규 라이브러리나 프레임워크 비종속 코드는 Stage 3 표준이 장기적으로 옳다 — 표준이며 런타임 이식성이 높고 `addInitializer`·`Symbol.metadata`를 활용한다. 동작이 모드별로 다르므로 단위 테스트로 검증한다.

```typescript
test("logged decorator preserves return value", () => {
  class S { @logged add(a: number, b: number) { return a + b; } }
  expect(new S().add(2, 3)).toBe(5);
});
```

데코레이터 선택은 취향이 아니라 의존 생태계가 결정한다. 두 모드를 한 컴파일 단위에서 섞을 수 없다.

## 참고

- TC39, "Decorators Proposal (Stage 3)": https://github.com/tc39/proposal-decorators
- TypeScript 5.0 Release Notes, "Decorators": https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TC39, "Decorator Metadata Proposal": https://github.com/tc39/proposal-decorator-metadata
- NestJS Documentation, "Custom Providers / Dependency Injection": https://docs.nestjs.com/fundamentals/custom-providers
