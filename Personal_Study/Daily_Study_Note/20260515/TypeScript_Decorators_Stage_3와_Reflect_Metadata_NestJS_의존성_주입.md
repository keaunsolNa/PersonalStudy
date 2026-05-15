Notion 원본: https://www.notion.so/3615a06fd6d3810e9aafe70164eb3c7b

# TypeScript Decorators Stage 3와 Reflect Metadata NestJS 의존성 주입

> 2026-05-15 신규 주제 · 확장 대상: TypeScript / NestJS

## 학습 목표

- TC39 Stage 3 데코레이터 사양과 TypeScript 5.0+ 구현의 차이를 구분한다
- `experimentalDecorators` + `emitDecoratorMetadata` 모드와 Stage 3 모드의 호환성 경계를 정리한다
- `reflect-metadata` 폴리필이 제공하는 `design:type`, `design:paramtypes`, `design:returntype` 키 동작을 코드로 검증한다
- NestJS DI 컨테이너가 데코레이터 메타데이터를 어떻게 소비해 생성자 파라미터를 해석하는지 추적한다

## 1. Stage 3 데코레이터 사양과 TypeScript 5.0 구현

TC39 데코레이터 제안은 2022년 12월 Stage 3 에 진입했고, TypeScript 는 5.0 부터 `--experimentalDecorators` 플래그 없이도 Stage 3 데코레이터를 컴파일한다. Stage 3 사양은 이전 Stage 2 (legacy) 제안과 호환되지 않는다. 가장 큰 차이는 데코레이터 함수의 시그니처다.

Stage 3 클래스 메서드 데코레이터:

```ts
type ClassMethodDecorator = (
    value: Function,
    context: {
        kind: 'method'
        name: string | symbol
        access: { get(): unknown }
        static: boolean
        private: boolean
        addInitializer(initializer: () => void): void
    }
) => Function | void
```

Stage 2 (legacy):

```ts
type LegacyMethodDecorator = (
    target: object,
    propertyKey: string | symbol,
    descriptor: TypedPropertyDescriptor<any>
) => TypedPropertyDescriptor<any> | void
```

Stage 3 는 `PropertyDescriptor` 를 직접 받지 않고 `context.addInitializer` 로 인스턴스 생성 직후 초기화 훅을 등록한다. 이 덕분에 같은 데코레이터를 static 과 instance 양쪽에 쓸 수 있고, accessor / field / getter / setter 까지 균일한 시그니처가 된다.

TypeScript 5.0 의 동작:

- `experimentalDecorators: false` (기본) → Stage 3 사양 사용. `emitDecoratorMetadata` 와 함께 쓸 수 없다.
- `experimentalDecorators: true` → Stage 2 legacy. `emitDecoratorMetadata: true` 와 조합 가능, NestJS / TypeORM / Angular(v15 이하) 가 의존하는 모드.

NestJS 11 시점에도 내부 DI 는 여전히 legacy + `emitDecoratorMetadata` 에 묶여 있다. 마이그레이션을 시도하면 `Reflect.getMetadata('design:paramtypes', ...)` 결과가 `undefined` 가 된다.

## 2. emitDecoratorMetadata 가 실제로 무엇을 생성하는가

다음 소스를 보자.

```ts
import 'reflect-metadata'

class Engine { start() {} }
class Wheel { roll() {} }

class Car {
    constructor(private engine: Engine, private wheel: Wheel) {}
}
```

`tsc --experimentalDecorators --emitDecoratorMetadata` 로 컴파일된 결과(요약):

```js
let Car = class Car {
    constructor(engine, wheel) {
        this.engine = engine
        this.wheel = wheel
    }
}
// ↓ 데코레이터가 하나라도 붙으면 이 호출이 삽입된다
Car = __decorate([
    Injectable(),
    __metadata("design:paramtypes", [Engine, Wheel])
], Car)
```

핵심은 `emitDecoratorMetadata` 가 단독으로 동작하지 않는다는 것이다. **클래스나 멤버에 최소 1개의 데코레이터가 붙어야** `__metadata("design:paramtypes", ...)` 가 출력된다. 데코레이터가 없으면 `Reflect.getMetadata('design:paramtypes', Car)` 는 `undefined` 가 된다. NestJS 에서 모든 서비스에 `@Injectable()` 을 강제하는 이유가 여기에 있다.

emit 되는 메타데이터 키 3종:

| 키 | 대상 | 값 |
|---|---|---|
| `design:type` | property / accessor / method | 단일 생성자(런타임 클래스 참조) |
| `design:paramtypes` | constructor / method | 파라미터 타입 생성자 배열 |
| `design:returntype` | method | 반환 타입 생성자 |

값은 **런타임에 존재하는 클래스 참조**여야 emit 된다. interface, type alias, union, literal 등 런타임에 사라지는 타입은 `Object` 로 떨어진다. `Promise<User>` 는 `Promise` 로 잘리고 제네릭 인수는 사라진다.

## 3. reflect-metadata 폴리필 동작 확인

`reflect-metadata` 는 `WeakMap` 기반 메타데이터 저장소를 `Reflect` 네임스페이스에 부착한다. 아래 코드로 동작을 검증할 수 있다.

```ts
import 'reflect-metadata'

const RouteKey = Symbol('route')

function Get(path: string): MethodDecorator {
    return (target, propertyKey) => {
        Reflect.defineMetadata(RouteKey, { method: 'GET', path }, target, propertyKey)
    }
}

class UserController {
    @Get('/users')
    list() { return [] }
}

const proto = UserController.prototype
console.log(Reflect.getMetadata(RouteKey, proto, 'list'))
// → { method: 'GET', path: '/users' }
console.log(Reflect.getMetadata('design:returntype', proto, 'list'))
// → Array (런타임 생성자)
```

`Reflect.getMetadata` 는 prototype chain 을 따라 올라가며 검색한다. 같은 이름 키를 자식 클래스가 덮어쓸 수 있고, 부모 클래스 메타데이터를 그대로 상속받을 수도 있다. 라이브러리에서는 보통 `Reflect.getOwnMetadata` 로 명시적 정의 여부를 가린다.

`tsconfig.json` 최소 설정:

```json
{
    "compilerOptions": {
        "target": "ES2022",
        "module": "commonjs",
        "experimentalDecorators": true,
        "emitDecoratorMetadata": true,
        "strict": true,
        "useDefineForClassFields": false
    }
}
```

`useDefineForClassFields` 가 `true` 면 클래스 필드가 `[[Define]]` semantics 로 초기화되어 부모 데코레이터 동작과 충돌할 수 있다. NestJS 는 명시적으로 `false` 를 권장한다.

## 4. NestJS DI 컨테이너의 메타데이터 소비

NestJS 의 `Injector` 가 `Car` 같은 클래스를 인스턴스화할 때 흐름은 다음과 같다.

```ts
// @nestjs/core/injector/injector.ts (단순화)
async resolveConstructorParams<T>(wrapper: InstanceWrapper<T>) {
    const dependencies = this.reflectConstructorParams(wrapper.metatype)
    const resolved: any[] = []
    for (const dep of dependencies) {
        const instance = await this.resolveDependency(dep, wrapper)
        resolved.push(instance)
    }
    return resolved
}

reflectConstructorParams(type: Type<unknown>): any[] {
    const paramtypes = Reflect.getMetadata('design:paramtypes', type) || []
    const selfParams = this.reflectSelfParams(type)
    selfParams.forEach(({ index, param }) => (paramtypes[index] = param))
    return paramtypes
}
```

`reflectSelfParams` 는 `@Inject(TOKEN)` 처럼 명시적 토큰이 붙은 파라미터를 우선 적용한다. 그 외엔 `design:paramtypes` 의 생성자 참조를 그대로 토큰으로 사용한다. 즉 다음 두 작성은 동치다:

```ts
@Injectable()
class UserService {
    constructor(private repo: UserRepository) {}
}

@Injectable()
class UserService {
    constructor(@Inject(UserRepository) private repo: UserRepository) {}
}
```

interface 토큰은 런타임에 사라지므로 반드시 `@Inject('USER_REPO')` 같은 명시 토큰이 필요하다. NestJS 가 `Nest can't resolve dependencies of the X` 에러를 뱉는 가장 흔한 이유가 이 케이스다.

순환 의존성은 `Reflect.getMetadata` 호출 시점에 한쪽 클래스가 아직 정의 전이라 `undefined` 가 되는 문제로 나타난다. NestJS 는 `forwardRef(() => OtherService)` 로 lazy 평가를 해결한다.

## 5. 커스텀 데코레이터로 메타데이터 파이프라인 만들기

라우트 + 가드 + 응답 변환을 메타데이터로 묶어 처리하는 작은 예제.

```ts
import 'reflect-metadata'

const ROUTE = Symbol('route')
const GUARDS = Symbol('guards')

interface RouteMeta { method: string; path: string }
interface Guard { canActivate(req: any): boolean | Promise<boolean> }

function Route(method: string, path: string): MethodDecorator {
    return (target, key) => Reflect.defineMetadata(ROUTE, { method, path }, target, key)
}

function UseGuards(...guards: Guard[]): MethodDecorator {
    return (target, key) => {
        const existing: Guard[] = Reflect.getMetadata(GUARDS, target, key) ?? []
        Reflect.defineMetadata(GUARDS, [...existing, ...guards], target, key)
    }
}

class AuthGuard implements Guard {
    canActivate(req: any) { return Boolean(req.user) }
}

class OrderController {
    @Route('POST', '/orders')
    @UseGuards(new AuthGuard())
    create(req: any) {
        return { id: 1 }
    }
}

// 디스패처
async function dispatch(controller: any, req: any) {
    const proto = Object.getPrototypeOf(controller)
    for (const key of Object.getOwnPropertyNames(proto)) {
        const route = Reflect.getMetadata(ROUTE, proto, key) as RouteMeta | undefined
        if (!route) continue
        if (route.method !== req.method || route.path !== req.path) continue
        const guards = (Reflect.getMetadata(GUARDS, proto, key) ?? []) as Guard[]
        for (const g of guards) {
            if (!(await g.canActivate(req))) return { status: 403 }
        }
        return { status: 200, body: await controller[key](req) }
    }
    return { status: 404 }
}

dispatch(new OrderController(), { method: 'POST', path: '/orders', user: { id: 7 } })
    .then(console.log) // → { status: 200, body: { id: 1 } }
```

`UseGuards` 가 기존 메타데이터를 읽고 누적 저장한 부분이 포인트다. 데코레이터는 클래스 정의 시점에 한 번 평가되므로 누적 패턴을 쓰면 여러 데코레이터를 합성할 수 있다.

## 6. Stage 3 환경에서 Reflect Metadata 대체 전략

Stage 3 + `emitDecoratorMetadata` 는 공존하지 않는다. Stage 3 로 가려면 메타데이터를 직접 수집해야 한다. 두 가지 패턴이 있다.

**(a) 데코레이터 context.metadata 활용** — Stage 3 데코레이터 context 에는 `metadata` 객체가 들어있고, 같은 클래스의 모든 데코레이터가 이 객체를 공유한다.

```ts
// Stage 3
function Get(path: string) {
    return (_value: Function, context: ClassMethodDecoratorContext) => {
        const routes = (context.metadata.routes ??= []) as Array<{ name: string; path: string }>
        routes.push({ name: context.name as string, path })
    }
}

class Ctrl {
    @Get('/a') a() {}
    @Get('/b') b() {}
}

console.log((Ctrl as any)[Symbol.metadata].routes)
// → [{ name: 'a', path: '/a' }, { name: 'b', path: '/b' }]
```

**(b) Zod / Valibot / TypeBox 같은 schema-first 라이브러리** — 타입 정보를 컴파일 타임에 잃지 않고 런타임 값으로 보존한다. NestJS 도 ValidationPipe 가 class-validator 의 데코레이터를 읽는 경로와 별개로 Zod 통합이 가능하다.

마이그레이션 시점에는 모듈 단위로 `experimentalDecorators` 옵션을 다르게 줄 수 없다. 프로젝트 전체를 한 번에 바꿔야 하므로, NestJS 본체가 Stage 3 를 지원하기 전까지는 legacy 모드 유지가 현실적이다.

## 7. 타입 손실 케이스와 우회

`design:paramtypes` 가 `Object` 로 떨어지는 케이스를 알아두면 디버깅이 빠르다.

| 선언 타입 | 런타임 메타데이터 |
|---|---|
| `string` / `number` / `boolean` | `String` / `Number` / `Boolean` |
| `Date` | `Date` |
| `interface Foo {}` | `Object` |
| `type Foo = ...` | `Object` |
| `'a' \| 'b'` (literal union) | `Object` |
| `Foo \| null` | `Object` (union 이면 Object 로 떨어짐) |
| `Promise<Foo>` | `Promise` (제네릭 인수는 사라짐) |
| `Array<Foo>` / `Foo[]` | `Array` |
| `Map<K, V>` | `Map` |

NestJS swagger 모듈은 이 한계를 보완하려고 `@nestjs/swagger` 의 ts-plugin 으로 컴파일러 hook 을 걸어 추가 메타데이터를 emit 한다. class-validator 도 `@IsString()`, `@IsInt()` 같은 명시 데코레이터를 요구하는 이유가 같다.

## 8. 트레이드오프 정리

- legacy 데코레이터 + `emitDecoratorMetadata` 는 강력하지만 컴파일러에 묶여 있고, 표준화 경로 밖이다. Stage 3 로 옮기면 표준 호환을 얻지만 NestJS / TypeORM 생태계 호환이 깨진다.
- `reflect-metadata` 폴리필은 약 6KB 정도의 번들 비용이 있고 ESM 환경에서 side-effect import 가 필요하다. tree-shaking 이 어렵다.
- 런타임 메타데이터에 의존하는 코드는 타입 시스템 검증과 런타임 검증이 분리된다. Zod 같은 schema-first 접근은 두 검증을 일치시키되 데코레이터가 주는 선언적 API 는 잃는다.
- 마이크로 서비스 단위로는 NestJS 계속, 신규 라이브러리는 Stage 3 + context.metadata 로 가는 하이브리드가 현실적인 선택이다.

## 참고

- TC39 Decorators Proposal (Stage 3): https://github.com/tc39/proposal-decorators
- TypeScript 5.0 Release Notes — Decorators: https://devblogs.microsoft.com/typescript/announcing-typescript-5-0/
- reflect-metadata 사양: https://rbuckton.github.io/reflect-metadata/
- NestJS Custom Decorators: https://docs.nestjs.com/custom-decorators
- NestJS Injector 소스: https://github.com/nestjs/nest/blob/master/packages/core/injector/injector.ts
