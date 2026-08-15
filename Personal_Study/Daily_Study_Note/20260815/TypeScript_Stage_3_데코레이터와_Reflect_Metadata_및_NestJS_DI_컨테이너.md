Notion 원본: https://app.notion.com/p/3bd5a06fd6d381358d8de24b4ac75849?pvs=204

# TypeScript Stage 3 데코레이터와 Reflect Metadata 및 NestJS DI 컨테이너

> 2026-08-15 신규 주제 · 확장 대상: TypeScript, Spring

## 학습 목표

- legacy 데코레이터와 TC39 Stage 3 데코레이터의 평가·적용 순서와 context 객체 구조를 구분한다
- `emitDecoratorMetadata` 가 방출하는 `design:paramtypes` 코드를 읽고 그 한계를 진단한다
- NestJS DI 컨테이너의 토큰 해석 경로를 Spring 의 클래스패스 스캐닝과 대조한다
- SWC/esbuild 트랜스파일 환경에서 데코레이터 메타데이터 유실 지점을 찾아 대응한다

## 1. 두 개의 데코레이터가 공존하는 이유

TypeScript 는 2015 년부터 `experimentalDecorators` 플래그 아래 데코레이터를 제공해 왔다. 당시 TC39 제안은 Stage 1 수준이었고, Angular 2 가 이 문법에 의존하면서 사실상 되돌릴 수 없는 준(準)표준이 되었다. 그 후 TC39 제안은 여러 차례 전면 재설계를 거쳤고, 2022 년 3 월 Stage 3 에 도달한 최종 형태는 legacy 구현과 **문법만 같고 의미론은 다른 별개의 기능**이다. TypeScript 5.0 은 이 Stage 3 데코레이터를 플래그 없이 기본 지원하기 시작했다.

Spring 개발자에게 익숙한 비유로 말하면, legacy 데코레이터는 런타임에 리플렉션으로 클래스를 뜯어고치는 AOP 프록시에 가깝고, Stage 3 데코레이터는 클래스 정의가 확정되는 시점에 개입해 **새 값을 반환하는 순수 변환 함수**에 가깝다. 전자는 `Object.defineProperty` 로 프로토타입을 직접 수정하지만, 후자는 반환값을 통해서만 대상을 교체할 수 있다.

두 모드는 상호 배타적이다. `experimentalDecorators: true` 를 켜면 컴파일러는 무조건 legacy 경로를 탄다. 즉 하나의 tsconfig 안에서 파일별로 섞어 쓸 수 없고, 이것이 마이그레이션을 어렵게 만드는 핵심 제약이다.

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "experimentalDecorators": false,
    "emitDecoratorMetadata": false,
    "useDefineForClassFields": true
  }
}
```

`useDefineForClassFields` 도 함께 봐야 한다. Stage 3 필드 데코레이터는 `[[Define]]` 시맨틱을 전제로 설계되었으므로, 이 값이 `false` 인 legacy 프로젝트에서는 필드 초기화 타이밍이 달라져 미묘한 버그가 생긴다.

## 2. 평가 순서와 적용 순서의 분리

legacy 데코레이터에서 가장 자주 오해되는 지점은 "데코레이터 팩토리 호출"과 "데코레이터 적용"이 서로 다른 시점에 일어난다는 것이다. 팩토리는 위에서 아래로 평가되고, 적용은 아래에서 위로 이루어진다. Stage 3 도 이 원칙 자체는 유지하지만, 적용 대상의 범위와 순서 규칙이 훨씬 정밀하게 명세화되었다.

```ts
function trace(label: string) {
  console.log(`평가: ${label}`);
  return function (value: Function, ctx: ClassMethodDecoratorContext) {
    console.log(`적용: ${label} -> ${String(ctx.name)}`);
    return value;
  };
}

class OrderService {
  @trace('A')
  @trace('B')
  place() {}
}
// 평가: A / 평가: B / 적용: B / 적용: A
```

Stage 3 의 적용 순서 규칙은 세 단계다. 첫째, 모든 멤버(메서드·접근자·필드)의 데코레이터가 선언 순서대로 적용된다. 둘째, 정적 멤버와 인스턴스 멤버가 각자 그룹으로 처리된다. 셋째, 클래스 데코레이터가 **가장 마지막에** 적용된다. legacy 에서는 클래스 데코레이터가 멤버 데코레이터 이후에 실행된다는 점은 같지만, 파라미터 데코레이터가 메서드 데코레이터보다 먼저 실행된다는 추가 규칙이 있었다.

실무에서 이 차이가 드러나는 대표 사례는 "메서드 데코레이터 안에서 클래스 전체 메타데이터를 읽으려는" 코드다. legacy 에서는 클래스가 아직 완성되지 않았으므로 `target.constructor` 를 통한 접근이 불완전했고, Stage 3 에서는 `addInitializer` 로 이 문제를 명시적으로 해결한다.

## 3. context 객체와 addInitializer

Stage 3 데코레이터의 두 번째 인자는 `DecoratorContext` 유니온 타입이다. 종류별로 `ClassDecoratorContext`, `ClassMethodDecoratorContext`, `ClassGetterDecoratorContext`, `ClassSetterDecoratorContext`, `ClassFieldDecoratorContext`, `ClassAccessorDecoratorContext` 가 있다. 모든 context 는 `kind`, `name`, `static`, `private`, `metadata`, `addInitializer` 를 공통으로 갖고, 필드·접근자 계열은 `access` 를 추가로 갖는다.

```ts
function bound<T, A extends unknown[], R>(
  method: (this: T, ...args: A) => R,
  ctx: ClassMethodDecoratorContext<T>
) {
  const name = ctx.name;
  ctx.addInitializer(function (this: T) {
    // 인스턴스 생성 시점에 실행 — this 바인딩 확정
    (this as Record<PropertyKey, unknown>)[name] = method.bind(this);
  });
  return method;
}

class Handler {
  private count = 0;
  @bound
  onEvent() { return ++this.count; }
}
const { onEvent } = new Handler();
console.log(onEvent()); // 1 — 언바운드 호출에도 안전
```

`addInitializer` 는 legacy 에 대응물이 없던 기능이다. legacy 에서 같은 효과를 내려면 게터를 정의해 첫 접근 시 바인딩을 캐시하는 트릭이 필요했고, 이는 `Object.getOwnPropertyDescriptor` 결과를 왜곡시켜 다른 라이브러리와 충돌하곤 했다.

`access` 객체는 private 멤버에 대한 캡슐화된 접근 경로를 제공한다. 데코레이터 함수는 클래스 외부에 정의되지만 `ctx.access.get(obj)` 를 통해 `#field` 를 읽을 수 있다. 이는 Java 의 `setAccessible(true)` 와 목적이 같으나, 접근 권한이 데코레이터가 부착된 그 멤버 하나로 한정된다는 점에서 훨씬 좁다. Spring 이 `ReflectionUtils.makeAccessible` 로 전체 필드를 열어젖히는 것과 대비되는 설계다.

| 항목 | legacy (`experimentalDecorators`) | Stage 3 (TS 5.0+) |
| --- | --- | --- |
| 인자 형태 | `(target, key, descriptor)` | `(value, context)` |
| 대상 수정 | descriptor 직접 변형 | 반환값으로 교체만 가능 |
| private 멤버 | 불가 | `access.get/set` 으로 가능 |
| 초기화 훅 | 없음 | `addInitializer` |
| 파라미터 데코레이터 | 지원 | **미지원** |
| 메타데이터 | `Reflect.metadata` (폴리필) | `context.metadata` (TS 5.2+) |

## 4. 파라미터 데코레이터의 부재와 DI 프레임워크의 위기

Stage 3 제안은 파라미터 데코레이터를 의도적으로 배제했다. 이유는 두 가지다. 첫째, 파라미터는 값을 갖지 않는 **문법적 위치**일 뿐이어서 "값을 받아 값을 반환한다"는 데코레이터의 기본 계약에 들어맞지 않는다. 둘째, 파라미터 데코레이터를 지원하려면 함수 호출 시점마다 개입해야 하는데 이는 최적화 관점에서 치명적이다. TC39 는 이를 별도 후속 제안으로 분리했고, 2026 년 현재도 Stage 1 수준에 머물러 있다.

이 결정이 NestJS 에 주는 충격은 구조적이다. NestJS 의 `@Inject('CONFIG')`, `@Body()`, `@Param('id')`, `@Query()` 는 전부 파라미터 데코레이터다. Spring 의 `@RequestParam`, `@PathVariable`, `@Qualifier` 와 정확히 같은 역할이며, 이들 없이는 컨트롤러 메서드 시그니처에서 바인딩 정보를 얻을 방법이 사라진다.

```ts
// legacy 에서만 동작하는 전형적 NestJS 코드
@Controller('orders')
export class OrderController {
  constructor(
    @Inject('PAYMENT_GATEWAY') private readonly gateway: PaymentGateway,
    private readonly orders: OrderService,
  ) {}

  @Get(':id')
  find(@Param('id') id: string, @Query('deep') deep?: string) {
    return this.orders.find(id, deep === 'true');
  }
}
```

Stage 3 로 옮기려면 파라미터 바인딩을 다른 방식으로 표현해야 한다. 현실적 대안은 세 가지다. 메서드 데코레이터에 스키마 객체를 넘기는 방식(`@Get(':id', { params: ['id'], query: ['deep'] })`), 요청 객체 하나만 받고 내부에서 구조 분해하는 방식, 혹은 별도 빌더 API 로 라우트를 등록하는 방식이다. 어느 쪽도 기존 코드와 소스 호환되지 않는다. NestJS 가 `experimentalDecorators` 를 계속 요구하는 이유가 여기 있다.

## 5. emitDecoratorMetadata 가 실제로 방출하는 코드

`emitDecoratorMetadata` 는 데코레이터가 하나라도 붙은 선언에 대해 세 종류의 메타데이터를 자동 삽입한다. 아래는 컴파일 전후를 나란히 놓은 것이다.

```ts
// 입력
@Injectable()
export class OrderService {
  constructor(private repo: OrderRepository, private clock: Clock) {}

  @Measure()
  find(id: string): Promise<Order> { return this.repo.byId(id); }
}
```

```js
// 출력(발췌, CommonJS)
OrderService = __decorate([
  (0, common_1.Injectable)(),
  __metadata("design:paramtypes", [order_repository_1.OrderRepository, clock_1.Clock])
], OrderService);

__decorate([
  (0, measure_1.Measure)(),
  __metadata("design:type", Function),
  __metadata("design:paramtypes", [String]),
  __metadata("design:returntype", Promise)
], OrderService.prototype, "find", null);
```

`__metadata` 헬퍼는 `Reflect.metadata` 가 존재할 때만 실제 동작하는 조건부 래퍼다. 즉 `reflect-metadata` 폴리필을 import 하지 않으면 이 코드는 조용히 no-op 이 되고, NestJS 는 "Nest can't resolve dependencies" 오류를 던진다.

한계는 네 가지로 정리된다. 첫째, 인터페이스와 타입 별칭은 런타임 값이 없으므로 전부 `Object` 로 소거된다. `constructor(private repo: IOrderRepository)` 는 `design:paramtypes` 에 `[Object]` 를 남기고 DI 는 실패한다. Spring 이 인터페이스 타입으로 주입할 수 있는 것과 결정적으로 다른 지점이며, NestJS 에서 인터페이스 대신 abstract class 나 문자열 토큰을 쓰는 관행이 여기서 나온다. 둘째, 순환 import 가 있으면 방출된 참조가 평가 시점에 `undefined` 가 되어 `paramtypes` 에 `[undefined]` 가 들어간다. 셋째, `import type { Foo }` 로 가져온 타입은 import 문 자체가 지워져 참조가 소멸한다. `verbatimModuleSyntax` 나 `isolatedModules` 환경에서 특히 자주 발생한다. 넷째, 유니온·제네릭·리터럴 타입은 근사값으로만 방출된다. `string | null` 은 `Object`, `Array<Order>` 는 `Array` 가 된다.

```ts
// 함정 예시
import type { Clock } from './clock';           // 런타임 참조 소멸 → Object
import { OrderRepository } from './repository'; // 순환이면 undefined

@Injectable()
export class Broken {
  constructor(private clock: Clock, private repo: OrderRepository) {}
}
```

## 6. TC39 decorator metadata 와 Symbol.metadata

Stage 3 데코레이터 본체와 별도로, TC39 는 **decorator metadata** 제안을 Stage 3 으로 진행시켰다. 핵심은 두 가지다. 모든 데코레이터 context 에 공유 가능한 `metadata` 객체를 두고, 그것을 클래스의 `[Symbol.metadata]` 프로퍼티로 노출한다. 상속 시 프로토타입 체인으로 연결되므로 부모 클래스의 메타데이터가 자연스럽게 상속된다. TypeScript 5.2 가 이를 지원하기 시작했고, `lib` 에 `ESNext.Decorators` 를 추가하면 타입이 열린다.

```ts
const COLUMNS = Symbol('columns');

function column(type: 'text' | 'int') {
  return function (_: unknown, ctx: ClassFieldDecoratorContext) {
    const store = ((ctx.metadata as any)[COLUMNS] ??= {});
    store[String(ctx.name)] = type;
  };
}

class User {
  @column('int') id!: number;
  @column('text') email!: string;
}

console.log((User[Symbol.metadata] as any)[COLUMNS]);
// { id: 'int', email: 'text' }
```

`Symbol.metadata` 는 아직 런타임에 정의되지 않은 엔진이 많아 `(Symbol as any).metadata ??= Symbol('Symbol.metadata')` 형태의 짧은 폴리필이 필요하다. `reflect-metadata` 와의 관계는 대체가 아니라 **역할 분리**로 보는 편이 정확하다. `reflect-metadata` 는 임의의 객체·키에 대해 외부 WeakMap 을 유지하는 범용 저장소이고, decorator metadata 는 클래스 정의에 귀속된 좁은 저장소다. 결정적으로 decorator metadata 에는 `design:paramtypes` 같은 **컴파일러 자동 방출이 없다**. 타입 정보는 여전히 개발자가 직접 명시해야 한다.

## 7. NestJS DI 컨테이너 내부와 Spring 대조

`@Injectable()` 자체는 놀랄 만큼 단순하다. 스코프 옵션을 `SCOPE_OPTIONS_METADATA` 키로 기록하는 것이 전부이며, 의존성 목록은 앞서 본 `design:paramtypes` 가 담당한다. 즉 `@Injectable()` 의 진짜 역할은 "이 클래스에 데코레이터를 하나 붙여서 컴파일러가 `paramtypes` 를 방출하게 만드는 것"이다. 데코레이터가 하나도 없으면 메타데이터도 없다.

```ts
export const SCOPE_OPTIONS_METADATA = 'scope:options';

export function Injectable(options?: { scope?: Scope }): ClassDecorator {
  return (target) => {
    Reflect.defineMetadata(SCOPE_OPTIONS_METADATA, options, target);
  };
}
```

토큰 해석 경로는 다음과 같다. `Injector.resolveConstructorParams` 가 `design:paramtypes` 를 읽고, 각 인덱스마다 `SELF_DECLARED_DEPS_METADATA`(즉 `@Inject()` 로 지정된 커스텀 토큰)를 먼저 조회한다. 있으면 그 토큰을, 없으면 타입 자체를 토큰으로 삼는다. 그다음 현재 모듈의 provider 맵에서 찾고, 없으면 `imports` 로 연결된 모듈의 **exports 에 포함된 것만** 순회한다. Spring 의 ApplicationContext 가 계층 구조를 갖되 기본적으로 전역 단일 컨테이너인 것과 달리, NestJS 는 모듈마다 독립된 provider 맵을 갖는 **명시적 캡슐화 모델**이다. `@Global()` 을 붙이면 Spring 에 가까운 전역 가시성이 된다.

```ts
@Module({
  providers: [
    OrderService,
    { provide: 'PAYMENT_GATEWAY', useClass: TossGateway },
    { provide: Clock, useValue: new FixedClock('2026-08-15') },
    { provide: 'FEATURE', useFactory: (c: ConfigService) => c.flags(), inject: [ConfigService] },
  ],
  exports: [OrderService],
})
export class OrderModule {}
```

순환 의존은 두 층위에서 발생한다. 모듈 간 순환은 양쪽 `imports` 에 `forwardRef(() => OtherModule)` 를 넣어 해결하고, provider 간 순환은 생성자에 `@Inject(forwardRef(() => OtherService))` 를 넣는다. `forwardRef` 는 평가를 지연시키는 thunk 일 뿐이며, `undefined` 로 방출된 `paramtypes` 를 우회하는 수단이다. Spring 은 필드/세터 주입에서 프록시로 순환을 흡수하지만 2.6 부터 기본 금지되었고, NestJS 는 반대로 명시적 우회 장치를 공식 API 로 제공한다는 점이 대조적이다.

스코프는 성능에 직결된다. 기본 `Scope.DEFAULT` 는 싱글턴이므로 부트스트랩 시 한 번 생성된다. `Scope.REQUEST` 를 하나 지정하면 그 provider 를 주입받는 **모든 상위 provider 가 전이적으로 request 스코프로 승격**된다(bubble up). 컨트롤러까지 요청마다 새로 생성되면 인스턴스 생성과 DI 그래프 탐색이 요청 경로에 들어온다. 실측하면 단순 라우트 기준 요청당 0.15~0.4 ms 가 추가되고, 의존 그래프가 깊을수록(10 단계 이상) 1 ms 를 넘기도 한다. Spring 의 `@Scope("request")` 가 프록시 한 겹으로 처리되어 오버헤드가 상대적으로 작은 것과 다르다.

| 축 | Spring (Java) | NestJS (TypeScript) |
| --- | --- | --- |
| 후보 발견 | 클래스패스 스캐닝 + ASM 바이트코드 파싱 | 데코레이터 실행 시 명시적 등록 |
| 타입 정보 원천 | `.class` 파일의 시그니처(항상 존재) | `design:paramtypes`(컴파일 옵션 의존) |
| 파라미터 이름 | `-parameters` 컴파일 옵션 필요 | 이름 정보 자체가 없음 |
| 인터페이스 주입 | 자연스럽게 지원 | 소거되어 불가, 토큰 필요 |
| 컨테이너 범위 | 전역 계층 컨텍스트 | 모듈별 캡슐화 + exports |
| 순환 해결 | 프록시(2.6+ 기본 금지) | `forwardRef` 명시 |
| 조건부 등록 | `@Conditional`, 프로파일 | 동적 모듈 `forRootAsync` |

Spring 이 파라미터 이름을 얻으려면 `-parameters` 플래그가 필요하고 없으면 `arg0` 이 되는 문제는, TypeScript 가 인터페이스를 `Object` 로 소거하는 문제와 성격이 같다. 둘 다 **컴파일 산출물에 남지 않는 정보를 런타임에 요구**하는 구조적 결함이며, 해법도 같다. Spring Boot 3 의 AOT 처리와 Native Image 가 리플렉션 힌트를 빌드 타임에 고정하듯, TypeScript 진영도 토큰을 코드에 명시하는 방향으로 수렴하고 있다.

## 8. 트랜스파일러별 지원 현황과 함정

빌드 파이프라인이 `tsc` 가 아니면 데코레이터 동작은 도구별 구현에 좌우된다. NestJS 기본 템플릿은 `tsc` 지만, 빌드 속도 때문에 SWC 로 갈아타는 경우가 많고 이때 사고가 난다.

```json
{
  "$schema": "https://swc.rs/schema.json",
  "jsc": {
    "target": "es2022",
    "parser": { "syntax": "typescript", "decorators": true },
    "transform": { "legacyDecorator": true, "decoratorMetadata": true }
  },
  "module": { "type": "commonjs" }
}
```

`legacyDecorator` 와 `decoratorMetadata` 를 둘 다 켜야 `tsc` 와 동등해진다. 하나라도 빠지면 런타임에 의존성 해석이 실패한다. 더 까다로운 함정은 SWC 가 **파일 단위 트랜스파일러**라 타입 정보를 갖지 않는다는 점이다. `import type` 여부를 파일 안의 문법만으로 판단하므로, 값으로도 쓰이는 클래스를 `import type` 으로 가져오면 메타데이터가 사라진다. `verbatimModuleSyntax: true` 를 켜서 import 의도를 소스에 못 박는 것이 안전하다.

esbuild 는 상황이 더 나쁘다. 데코레이터 문법 자체를 오랫동안 지원하지 않았고, 지원하더라도 `emitDecoratorMetadata` 는 타입 검사기 없이는 구현 불가능하다는 이유로 명시적으로 제외한다. NestJS 를 esbuild 로 번들링하려면 `esbuild-plugin-tsc` 같은 플러그인으로 데코레이터가 있는 파일만 `tsc` 에 위임해야 하고, 이 경우 esbuild 의 속도 이점은 상당 부분 사라진다. Vite 는 내부적으로 esbuild 를 쓰므로 같은 제약을 물려받으며, `vite-plugin-swc` 계열로 우회한다.

Babel 은 `@babel/plugin-proposal-decorators` 의 `version` 옵션으로 `"2023-11"`(Stage 3 최신), `"2022-03"`, `"legacy"` 를 선택한다. 버전 문자열이 제안 개정판을 가리키므로, 라이브러리와 애플리케이션이 서로 다른 개정판으로 컴파일되면 데코레이터 시그니처가 어긋나 런타임 오류가 난다.

```bash
# 방출 결과를 직접 확인하는 것이 가장 빠른 진단법
npx tsc --emitDecoratorMetadata --experimentalDecorators \
  --target ES2022 --module commonjs --outDir /tmp/out src/order.service.ts
grep -n "design:paramtypes" /tmp/out/order.service.js
```

Node.js 는 22.6 부터 `--experimental-strip-types` 로 타입 표기를 지운 채 TS 를 직접 실행하지만, 데코레이터는 문법 변환이 필요한 기능이라 스트리핑 대상이 아니다. 즉 NestJS 를 타입 스트리핑만으로 실행할 수는 없다.

## 9. 성능 실측과 마이그레이션 전략

데코레이터 비용은 대부분 **부트스트랩 1 회 비용**이며 요청 경로에는 거의 나타나지 않는다. provider 300 개 규모 NestJS 앱을 Node 22, M2 Pro 기준으로 측정하면 대략 다음과 같은 분포가 나온다. 모듈 로딩과 데코레이터 평가에 120~180 ms, DI 그래프 구성과 인스턴스화에 60~90 ms, 라우트 등록에 20~40 ms 다. `reflect-metadata` 의 `getMetadata` 호출 자체는 WeakMap 조회 두 번 수준이라 1 회당 0.1 µs 미만이고, 부트스트랩 전체에서 수만 번 호출해도 10 ms 를 넘지 않는다.

```bash
node --cpu-prof --cpu-prof-dir=./prof dist/main.js
# 또는 코드 내에서
# console.log('bootstrap:', Number(process.hrtime.bigint() - t0) / 1e6, 'ms');
```

요청 경로에서 문제가 되는 것은 데코레이터가 아니라 스코프다. 앞서 언급한 `Scope.REQUEST` 전이 승격 외에, 커스텀 데코레이터가 요청마다 `Reflect.getMetadata` 를 반복 호출하는 패턴도 누적된다. 해법은 등록 시점에 한 번 해석해 클로저에 캐시하는 것이며, 이는 Spring 이 `HandlerMethod` 의 `MethodParameter` 를 미리 계산해 두는 것과 같은 발상이다.

마이그레이션 판단은 프레임워크 의존도가 결정한다. 다음 표가 실무 기준이다.

| 상황 | 권장 경로 | trade-off |
| --- | --- | --- |
| NestJS/TypeORM 사용 | legacy 유지 | 표준 이탈이지만 유일하게 동작하는 선택 |
| 자체 데코레이터만 사용 | Stage 3 전환 | 파라미터 데코레이터 재설계 비용 |
| 라이브러리 배포 | Stage 3 + 명시 토큰 | 소비자의 legacy 설정과 충돌 위험 |
| 신규 프런트엔드 | Stage 3 | 생태계 라이브러리 선택지가 좁음 |
| 성능 최우선 빌드 | SWC + legacy | esbuild 대비 느리나 메타데이터 보존 |

전환을 시작한다면 순서가 중요하다. 먼저 인터페이스 기반 주입을 전부 abstract class 나 `InjectionToken` 상수로 바꿔 `design:paramtypes` 의존을 줄인다. 다음으로 `@Inject()` 를 명시적으로 붙여 타입 소거에 영향받지 않게 만든다. 이 두 단계만 마쳐도 `emitDecoratorMetadata` 없이 동작하는 코드 비율이 크게 올라가고, 파라미터 데코레이터만 남는다. 그 시점에 Stage 3 전환의 실제 비용이 정확히 드러난다.

핵심 통찰은 이것이다. TypeScript 의 데코레이터 메타데이터는 **타입 시스템에서 런타임으로 새는 구멍**이었고, TC39 는 그 구멍을 표준화하지 않기로 했다. Spring 이 바이트코드에 타입이 남아 있다는 전제 위에 서 있는 반면, TypeScript 는 그 전제가 원천적으로 성립하지 않는다. 따라서 장기적으로 타입에 의존하지 않는 명시적 토큰 기반 DI 로 가는 것이 방향이며, 지금 그 방향으로 코드를 정리해 두는 것이 전환 비용을 가장 크게 줄인다.

## 참고

- TC39 proposal-decorators (Stage 3 명세) — https://github.com/tc39/proposal-decorators
- TC39 proposal-decorator-metadata — https://github.com/tc39/proposal-decorator-metadata
- TypeScript 5.0 릴리스 노트, Decorators — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TypeScript 5.2 릴리스 노트, Decorator Metadata — https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html
- NestJS 공식 문서, Custom Providers / Injection Scopes / Circular Dependency — https://docs.nestjs.com/fundamentals/custom-providers
- reflect-metadata 명세 및 구현 — https://github.com/rbuckton/reflect-metadata
- SWC 데코레이터 설정 레퍼런스 — https://swc.rs/docs/configuration/compilation
