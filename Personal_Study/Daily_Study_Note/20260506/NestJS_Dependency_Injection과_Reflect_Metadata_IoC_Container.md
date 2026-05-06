Notion 원본: https://www.notion.so/3585a06fd6d381569d08d62008153a06

# NestJS Dependency Injection과 Reflect Metadata 기반 IoC Container 동작

> 2026-05-06 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- NestJS DI 컨테이너가 `reflect-metadata` 와 emitDecoratorMetadata 옵션 위에서 어떻게 타입을 추출하는지 추적한다
- Provider 의 4가지 등록 방식 (useClass, useValue, useFactory, useExisting) 의 차이를 코드와 함께 정리한다
- Module scope, Request scope, Transient scope 의 인스턴스 라이프사이클과 DURABLE provider 를 비교한다
- Circular dependency, forwardRef, OnModuleInit 같은 운영상 문제 케이스의 해결 패턴을 검증한다

## 1. NestJS DI 가 의존하는 두 기둥

NestJS 의 의존성 주입은 두 개의 TypeScript 기능 위에 서 있다. `experimentalDecorators` 와 `emitDecoratorMetadata`. 이 둘이 켜져 있어야 `@Injectable()` 데코레이터와 생성자 파라미터의 타입 정보가 컴파일러에 의해 보존된다.

```jsonc
// tsconfig.json
{
    "compilerOptions": {
        "experimentalDecorators": true,
        "emitDecoratorMetadata": true,
        "target": "ES2021"
    }
}
```

`emitDecoratorMetadata` 가 켜지면 TypeScript 컴파일러가 데코레이터가 붙은 클래스/메서드/프로퍼티에 대해 `design:type`, `design:paramtypes`, `design:returntype` 메타데이터를 자동으로 emit 한다. 이 메타데이터는 `Reflect.metadata("design:paramtypes", [UserService, ConfigService])` 형태로 클래스 객체 위에 박힌다. NestJS 컨테이너는 클래스를 인스턴스화하기 전에 이 메타데이터를 읽어 어떤 타입의 의존성이 필요한지 알아낸다.

```ts
import "reflect-metadata";

@Injectable()
class UserController {
    constructor(
        private readonly userService: UserService,
        private readonly logger: Logger,
    ) {}
}

// 컴파일 후, 런타임에:
const params = Reflect.getMetadata("design:paramtypes", UserController);
// params === [UserService, Logger]
```

이 메타데이터가 없으면 NestJS 는 어떤 의존성을 주입해야 할지 알 수 없다. 그래서 `reflect-metadata` polyfill 을 main.ts 진입점 가장 위에서 import 해야 한다. NestJS 4 부터는 자동 import 되지만 라이브러리 코드에서는 여전히 명시 import 가 필요하다.

## 2. Provider 등록 4 변형

NestJS Module 은 `providers` 배열에 클래스를 직접 넣을 수도 있고, 객체 리터럴로 세부 등록 방식을 지정할 수도 있다.

```ts
@Module({
    providers: [
        // (1) Class provider — shorthand
        UserService,

        // (2) useClass — 인터페이스 토큰에 구현체 매핑
        { provide: "USER_REPOSITORY", useClass: PostgresUserRepository },

        // (3) useValue — 상수/모킹
        { provide: "API_KEY", useValue: process.env.API_KEY },

        // (4) useFactory — 동적 생성
        {
            provide: "DATABASE_CONNECTION",
            useFactory: async (config: ConfigService) => {
                return await createConnection({ url: config.get("DB_URL") });
            },
            inject: [ConfigService],
        },

        // (5) useExisting — alias
        { provide: "ALIAS_LOGGER", useExisting: Logger },
    ],
})
class AppModule {}
```

`useClass` 는 인터페이스(추상화) 와 구현체를 분리하는 핵심이다. TypeScript 인터페이스는 런타임에 사라지므로 토큰을 string 이나 Symbol 로 잡고, 주입 측에서는 `@Inject('USER_REPOSITORY')` 로 명시한다.

```ts
@Injectable()
class UserService {
    constructor(
        @Inject("USER_REPOSITORY") private readonly repo: UserRepository,
    ) {}
}
```

`useFactory` 는 비동기 초기화가 필요한 자원에 쓴다. Nest 컨테이너는 모든 useFactory 의 promise 가 resolve 될 때까지 부트스트랩을 차단한다. 그래서 `await app.init()` 이 끝났을 때는 모든 connection 이 준비된 상태다.

`useExisting` 은 같은 인스턴스를 다른 토큰으로 노출할 때 쓴다. legacy 코드 호환이나 deprecation 경로에서 유용하다.

## 3. Scope — DEFAULT / REQUEST / TRANSIENT

Provider 는 기본적으로 싱글턴(`Scope.DEFAULT`)이다. 한 번 생성된 인스턴스가 모듈 단위로 재사용된다. 하지만 요청 컨텍스트(현재 사용자, trace ID)에 의존하는 provider 는 요청별로 새로 생성되어야 한다.

```ts
@Injectable({ scope: Scope.REQUEST })
class RequestScopedAuditLogger {
    constructor(@Inject(REQUEST) private readonly req: Request) {}

    log(event: string) {
        const userId = this.req.user?.id ?? "anonymous";
        console.log(`[${userId}] ${event}`);
    }
}
```

`Scope.REQUEST` 는 매 HTTP 요청마다 새 인스턴스를 만든다. Nest 는 요청 처리 시 의존성 트리를 따라 올라가며 해당 provider 와 그 위로 의존하는 모든 provider 를 request-scoped 로 격상시킨다. 이를 **scope bubbling** 이라고 부른다. controller 가 request-scoped provider 를 주입하면 controller 자체도 request-scoped 가 된다.

성능 영향은 무시할 수 없다. NestJS 9 기준 단순 echo controller 에서 default scope 은 약 12k req/s, request scope 은 약 8k req/s (벤치 환경: M2 Pro, fastify adapter). 모든 요청마다 인스턴스 생성과 GC 부담이 추가된다.

`Scope.TRANSIENT` 는 매 주입마다 새 인스턴스를 만든다. 의존하는 모듈끼리 별도의 인스턴스를 받는다. 상태를 격리해야 하는 plugin 패턴에 쓴다.

## 4. Durable Provider — request-scope 의 비용 절감

Nest 9 부터 도입된 **durable** 옵션은 request-scoped provider 를 특정 키 단위로 캐싱한다. 멀티테넌트 환경에서 tenant 별로 인스턴스를 분리하되 같은 tenant 의 후속 요청은 인스턴스를 재사용한다.

```ts
@Injectable({ scope: Scope.REQUEST, durable: true })
class TenantConfigService {
    constructor(@Inject(REQUEST) private readonly req: Request) {}
}

class TenantContextStrategy implements ContextIdStrategy {
    attach(contextId: ContextId, request: Request) {
        const tenantId = request.headers["x-tenant-id"] as string;
        return (info: HostComponentInfo) =>
            info.isTreeDurable
                ? ContextIdFactory.getByRequest(request, [tenantId])
                : contextId;
    }
}

ContextIdFactory.apply(new TenantContextStrategy());
```

이렇게 하면 tenant=`acme` 의 N 번째 요청은 같은 `TenantConfigService` 인스턴스를 재사용한다. 1만 tenant × 1000 req/sec 환경에서 수만 인스턴스 생성을 수십~수백으로 줄인다. 캐시 메모리 사용량은 tenant 수 × provider 객체 크기로 예측 가능하다.

## 5. Module 시스템 — Provider 가시성

Module 안의 provider 는 기본적으로 모듈 외부에서 보이지 않는다. 다른 모듈에서 쓰려면 `exports` 에 명시한다.

```ts
@Module({
    providers: [UserService, UserRepository],
    exports: [UserService], // UserRepository 는 외부 비공개
})
class UserModule {}

@Module({
    imports: [UserModule],
    providers: [OrderService],
})
class OrderModule {}
```

`OrderService` 는 `UserService` 만 주입받을 수 있고 `UserRepository` 는 보이지 않는다. 캡슐화가 보장된다.

`@Global()` 로 표시된 모듈은 한 번 import 되면 어느 모듈이든 그 export 를 쓸 수 있다. 가독성을 해치므로 ConfigModule, CacheModule 같은 인프라성에만 사용한다.

```ts
@Global()
@Module({
    providers: [ConfigService],
    exports: [ConfigService],
})
class ConfigModule {}
```

## 6. Circular Dependency 와 forwardRef

A → B → A 형태의 순환 의존이 발생하면 Nest 컨테이너는 의존성 그래프를 토폴로지 정렬할 수 없다. `Nest can't resolve dependencies of A (?)` 에러가 뜬다. 정공법은 **모듈 분리/추출**이지만 빠른 우회로 `forwardRef` 가 있다.

```ts
@Injectable()
class CatService {
    constructor(
        @Inject(forwardRef(() => DogService))
        private readonly dogService: DogService,
    ) {}
}

@Injectable()
class DogService {
    constructor(
        @Inject(forwardRef(() => CatService))
        private readonly catService: CatService,
    ) {}
}
```

`forwardRef` 는 의존 토큰의 평가를 lazy 하게 만든다. 클래스가 정의되기 전에 import 가 시도되면 undefined 가 들어가는 문제를 피한다. 단점은 IDE 의 자동 임포트나 dead-code 분석이 약화된다는 것. 그리고 순환 의존이 코드 냄새라는 사실 자체는 사라지지 않는다.

대안: 두 서비스가 공유하는 로직을 **공통 인터페이스 + 도메인 이벤트**로 분리한다. CatService 가 직접 DogService 를 부르지 않고 EventEmitter 로 이벤트를 발행하면, DogService 는 listener 로 처리한다. 이쪽이 운영상 안전하다.

## 7. Lifecycle Hook

NestJS 는 모듈 부트스트랩과 셧다운 시점에 호출되는 hook 인터페이스를 제공한다.

```ts
@Injectable()
class WarmupCacheService implements OnModuleInit, OnApplicationShutdown {
    constructor(private readonly redis: Redis) {}

    async onModuleInit() {
        const popular = await this.fetchPopularProducts();
        await this.redis.mset(popular);
    }

    async onApplicationShutdown(signal?: string) {
        console.log(`Shutdown signal: ${signal}`);
        await this.redis.quit();
    }
}
```

호출 순서는 `OnModuleInit` → `OnApplicationBootstrap` → (서비스 운영) → `OnModuleDestroy` → `OnApplicationShutdown`. 셧다운 hook 을 받으려면 `app.enableShutdownHooks()` 를 main.ts 에 명시해야 한다. Kubernetes 의 SIGTERM → graceful shutdown 흐름을 만들 때 필수다.

```ts
async function bootstrap() {
    const app = await NestFactory.create(AppModule);
    app.enableShutdownHooks();
    await app.listen(3000);
}
```

## 8. Custom Decorator 로 메타데이터 확장

`reflect-metadata` 는 NestJS 만의 도구가 아니라 사용자가 직접 메타데이터를 박을 수 있는 표준이다. Role 기반 가드를 만들 때 자주 쓴다.

```ts
import { SetMetadata } from "@nestjs/common";

export const ROLES_KEY = "roles";
export const Roles = (...roles: string[]) => SetMetadata(ROLES_KEY, roles);

@Injectable()
class RolesGuard implements CanActivate {
    constructor(private readonly reflector: Reflector) {}

    canActivate(ctx: ExecutionContext): boolean {
        const required = this.reflector.getAllAndOverride<string[]>(ROLES_KEY, [
            ctx.getHandler(),
            ctx.getClass(),
        ]);
        if (!required) return true;
        const { user } = ctx.switchToHttp().getRequest();
        return required.some(r => user?.roles?.includes(r));
    }
}

@Controller("admin")
@UseGuards(RolesGuard)
class AdminController {
    @Get("users")
    @Roles("admin", "superuser")
    listUsers() { /* ... */ }
}
```

`Reflector.getAllAndOverride` 는 메서드 메타가 있으면 메서드 것을 우선하고, 없으면 클래스 것을 가져온다. 컨트롤러 전체에 기본 정책을 두고 메서드별로 override 하는 패턴이다.

## 9. 테스트 — Override 와 Mock

NestJS 의 `Test.createTestingModule` 은 provider 를 override 하는 Builder API 를 제공한다.

```ts
import { Test } from "@nestjs/testing";

describe("UserService", () => {
    let service: UserService;
    let repoMock: jest.Mocked<UserRepository>;

    beforeEach(async () => {
        const moduleRef = await Test.createTestingModule({
            providers: [
                UserService,
                { provide: UserRepository, useValue: { findById: jest.fn() } },
            ],
        }).compile();

        service = moduleRef.get(UserService);
        repoMock = moduleRef.get(UserRepository);
    });

    it("returns user", async () => {
        repoMock.findById.mockResolvedValue({ id: "u-1", email: "a@b.com" });
        const result = await service.getUser("u-1");
        expect(result.email).toBe("a@b.com");
    });
});
```

`useValue` 로 mock 객체를 주입한다. provider 토큰이 클래스든 string 이든 동일하다. 통합 테스트에서는 `.overrideProvider(...).useValue(...)` 체이닝으로 특정 provider 만 모킹하고 나머지는 실제 구현을 사용할 수 있다.

## 참고

- NestJS Docs — Custom Providers: https://docs.nestjs.com/fundamentals/custom-providers
- NestJS Docs — Injection Scopes & Durable: https://docs.nestjs.com/fundamentals/injection-scopes
- TC39 Decorators (Stage 3) Proposal: https://github.com/tc39/proposal-decorators
- reflect-metadata 라이브러리 README: https://github.com/rbuckton/reflect-metadata
- Kamil Mysliwiec, "NestJS — Internals of Dependency Injection" 발표 자료
