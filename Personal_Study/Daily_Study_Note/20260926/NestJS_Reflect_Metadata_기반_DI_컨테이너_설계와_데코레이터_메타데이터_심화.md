Notion 원본: https://app.notion.com/p/3e75a06fd6d38140b976e3cda03edb13

# NestJS Reflect Metadata 기반 DI 컨테이너 설계와 데코레이터 메타데이터 심화

> 2026-09-26 신규 주제 · 확장 대상: TypeScript 데코레이터 · Spring 기반 DI 이해

## 학습 목표

- `reflect-metadata`가 데코레이터 실행 시점에 타입 정보를 어떻게 저장·조회하는지 추적한다
- NestJS의 `@Injectable`, `@Inject`, `@Module`이 메타데이터를 통해 의존성 그래프를 구성하는 과정을 재현한다
- Spring의 `ApplicationContext` 빈 등록/주입 모델과 NestJS의 `ModuleRef` 모델을 대응시켜 설계 차이를 구분한다
- 순환 의존성과 스코프(Singleton/Request/Transient) 문제를 데코레이터 메타데이터 관점에서 진단한다

## 1. Reflect Metadata가 채우는 빈틈

TypeScript는 컴파일 시 대부분의 타입 정보를 제거(erasure)한다. 그런데 NestJS처럼 생성자 매개변수 타입만 보고 자동으로 의존성을 주입하려면 런타임에 "이 클래스의 생성자 세 번째 매개변수 타입은 `UserRepository`다"라는 정보가 남아 있어야 한다. 이 문제를 해결하는 것이 `reflect-metadata` 폴리필과 TypeScript 컴파일러의 `emitDecoratorMetadata` 옵션이다.

```json
{
  "compilerOptions": {
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true
  }
}
```

`emitDecoratorMetadata`가 켜져 있으면, 데코레이터가 하나라도 붙은 클래스/메서드/프로퍼티에 대해 컴파일러가 `design:type`, `design:paramtypes`, `design:returntype`이라는 세 가지 메타데이터 키를 자동으로 주입하는 코드를 생성한다.

```typescript
@Injectable()
class OrderService {
  constructor(private readonly userRepo: UserRepository) {}
}

// 컴파일 결과(개념적으로)
Reflect.defineMetadata("design:paramtypes", [UserRepository], OrderService);
```

이 메타데이터는 클래스 자체가 아니라 `Reflect`가 관리하는 별도의 WeakMap 구조에 "대상 객체 + 키"를 조합한 키로 저장된다. 즉 클래스에 프로퍼티를 추가하는 것이 아니라, 클래스를 키로 하는 전역(정확히는 모듈 스코프) 저장소에 메타데이터를 얹는 방식이다.

## 2. design:paramtypes의 한계와 인터페이스 소거 문제

`design:paramtypes`는 TypeScript의 정적 타입 정보를 런타임 값(생성자 함수 참조)으로 변환하는 방식이라서, 값으로 존재하지 않는 타입은 담을 수 없다. 인터페이스나 타입 별칭이 대표적이다.

```typescript
interface Logger { log(msg: string): void; }

@Injectable()
class OrderService {
  constructor(private readonly logger: Logger) {} // 런타임에 Logger는 존재하지 않음
}
```

이 경우 컴파일러는 `design:paramtypes`에 `Object`를 채워 넣는다. 인터페이스는 런타임에 소거되므로 어떤 구현체를 주입해야 할지 프레임워크가 알 방법이 없기 때문이다. NestJS는 이 문제를 커스텀 토큰과 `@Inject()` 데코레이터로 해결한다.

```typescript
const LOGGER_TOKEN = Symbol("LOGGER_TOKEN");

@Injectable()
class OrderService {
  constructor(@Inject(LOGGER_TOKEN) private readonly logger: Logger) {}
}

@Module({
  providers: [{ provide: LOGGER_TOKEN, useClass: ConsoleLogger }],
})
class AppModule {}
```

`@Inject(LOGGER_TOKEN)`이 실행되면 별도의 메타데이터 키(`self:paramtypes` 계열)에 "이 파라미터 위치는 이 토큰을 써서 해석하라"는 오버라이드 정보가 기록되고, DI 컨테이너는 파라미터 해석 시 `design:paramtypes`보다 이 오버라이드를 우선한다. Spring에서 인터페이스 기반 자동 와이어링이 가능한 이유는 JVM 바이트코드가 인터페이스 타입 정보를 런타임까지 유지하기 때문이며, 이는 TypeScript의 구조적 타입 소거 모델과 근본적으로 다른 지점이다.

## 3. NestJS 의존성 그래프 구성 과정

NestJS 부트스트랩 과정을 단계별로 보면 Spring의 컴포넌트 스캔 및 빈 등록과 유사하지만 세부 구현이 다르다.

<table header-row="true"><tr><td>단계</td><td>NestJS</td><td>Spring</td></tr><tr><td>탐색</td><td>@Module 데코레이터의 providers/imports 배열을 재귀 순회</td><td>클래스패스 스캔(@ComponentScan) 또는 명시적 @Bean</td></tr><tr><td>메타데이터 저장</td><td>reflect-metadata WeakMap</td><td>BeanDefinition 객체(리플렉션 + ASM 바이트코드 분석)</td></tr><tr><td>인스턴스화 순서</td><td>모듈 그래프 위상 정렬 후 지연 인스턴스화</td><td>BeanFactory의 3단계 캐시로 순환 참조 허용</td></tr><tr><td>스코프 기본값</td><td>Singleton</td><td>Singleton</td></tr></table>

NestJS는 `@Module` 데코레이터가 실행될 때 `Reflect.defineMetadata`로 모듈 메타데이터(providers, controllers, imports, exports)를 클래스에 붙인다. `NestFactory.create(AppModule)`이 호출되면 `DependenciesScanner`가 이 메타데이터를 재귀적으로 읽어 `Module` 인스턴스 그래프를 구성하고, `InstanceLoader`가 실제 클래스 인스턴스를 생성한다. 인스턴스 생성 순서는 의존성이 없는 리프 노드부터 위상 정렬(topological sort)로 결정되며, 이는 Spring의 `BeanFactory`가 생성자 주입 시 의존 빈을 먼저 만드는 것과 목적은 같지만, Spring은 3단계 캐시(싱글톤 캐시, 조기 노출 캐시, 팩토리 캐시)로 일부 순환 참조를 허용하는 반면 NestJS는 기본적으로 순환 의존성을 오류로 처리하고 `forwardRef()`로 명시적 해결을 요구한다는 점이 다르다.

```typescript
@Injectable()
class CatsService {
  constructor(@Inject(forwardRef(() => DogsService)) private dogsService: DogsService) {}
}
```

`forwardRef`는 즉시 클래스 참조를 평가하지 않고 지연 평가용 래퍼 함수를 반환해, 두 프로바이더가 서로를 참조하는 시점에 아직 정의되지 않은 클래스를 참조하는 문제(TDZ, Temporal Dead Zone과 유사한 문제)를 우회한다.

## 4. 커스텀 데코레이터와 메타데이터 합성

NestJS의 `@SetMetadata`와 `Reflector`는 프레임워크 내장 DI 메커니즘과 별개로, 애플리케이션 레벨의 메타데이터(예: 역할 기반 접근 제어)를 데코레이터에 실어 나르는 범용 패턴을 제공한다.

```typescript
export const Roles = (...roles: string[]) => SetMetadata("roles", roles);

@Controller("orders")
class OrderController {
  @Roles("admin")
  @Delete(":id")
  remove(@Param("id") id: string) { /* ... */ }
}

@Injectable()
class RolesGuard implements CanActivate {
  constructor(private reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const roles = this.reflector.getAllAndOverride<string[]>("roles", [
      context.getHandler(),
      context.getClass(),
    ]);
    if (!roles) return true;
    const user = context.switchToHttp().getRequest().user;
    return roles.includes(user.role);
  }
}
```

`getAllAndOverride`는 메서드 레벨 메타데이터를 우선하고 없으면 클래스 레벨 메타데이터로 폴백하는 조회 순서를 캡슐화한 헬퍼다. 이 패턴은 Spring의 `@PreAuthorize`가 AOP 프록시와 SpEL(Spring Expression Language) 평가로 접근 제어를 구현하는 것과 목적은 같지만, NestJS는 AOP 프록시 대신 실행 컨텍스트(`ExecutionContext`)를 직접 인터셉터/가드 체인에 전달하는 명시적 파이프라인 방식을 쓴다는 차이가 있다.

## 5. 데코레이터 팩토리와 매개변수 데코레이터의 메타데이터 병합

여러 매개변수 데코레이터가 같은 메서드에 누적 적용될 때, NestJS는 각 데코레이터 호출마다 메타데이터를 완전히 새로 쓰는 대신 기존 메타데이터를 읽어 병합하는 방식을 쓴다. 이는 데코레이터가 아래에서 위로(bottom-up) 실행되는 자바스크립트 데코레이터 평가 순서와 맞물려야 하기 때문이다.

```typescript
function createParamDecorator(key: string, extractor: (ctx: ExecutionContext) => any) {
  return (target: object, propertyKey: string | symbol, index: number) => {
    const existing: RouteParamMetadata[] =
      Reflect.getMetadata(ROUTE_ARGS_METADATA, target.constructor, propertyKey) ?? [];
    existing[index] = { key, extractor };
    Reflect.defineMetadata(ROUTE_ARGS_METADATA, existing, target.constructor, propertyKey);
  };
}
```

매개변수 데코레이터는 클래스가 정의되는 시점에 매개변수 인덱스와 함께 즉시 호출되므로, `existing[index]`처럼 배열의 특정 위치에 정보를 채워 넣는 방식으로 여러 매개변수 데코레이터(`@Param`, `@Query`, `@Body` 등)가 하나의 메서드 시그니처에 공존할 수 있게 된다. 이 메타데이터는 요청이 들어올 때 `RouterExecutionContext`가 다시 읽어 각 인덱스에 맞는 값을 실제 인자로 조립하는 데 쓰인다.

## 6. Stage 3 데코레이터 표준과의 공존 문제

TypeScript 5.0 이후 표준화가 진행 중인 Stage 3 데코레이터는 `experimentalDecorators` 기반 레거시 데코레이터와 메타데이터 접근 방식이 다르다. Stage 3 데코레이터는 `context.metadata` 객체를 통해 데코레이터 간에 메타데이터를 공유하도록 설계되어 있어, `reflect-metadata` 전역 폴리필에 의존하지 않는다. 그러나 NestJS의 핵심 DI 메커니즘은 여전히 `design:paramtypes`에 의존하는 레거시 데코레이터 모델 위에 구축되어 있어, Stage 3 데코레이터로 전면 전환하려면 `design:paramtypes`를 대체할 별도의 타입 정보 전달 경로(예: 명시적 토큰 배열)가 필요하다. 이 때문에 NestJS 생태계는 당분간 `experimentalDecorators: true`를 유지하는 프로젝트가 대다수이며, Stage 3 데코레이터로의 마이그레이션은 프레임워크 차원의 메타데이터 계층 재설계를 동반하는 큰 작업으로 남아 있다.

## 7. 순환 의존성 진단 실전 절차

순환 의존성이 발생하면 NestJS는 "Nest can't resolve dependencies of the X"류의 오류를 던지는데, 메시지만으로는 어느 지점에서 순환이 발생했는지 파악하기 어려운 경우가 많다. 실전에서 사용하는 진단 절차는 다음과 같다.

1. `NEST_DEBUG=true` 환경 변수로 부트스트랩 로그를 상세화해 인스턴스화 순서를 확인한다.
2. `DependenciesScanner`가 만든 모듈 그래프를 `app.get(ModulesContainer)`로 직접 조회해 프로바이더 간 참조 관계를 덤프한다.
3. 순환의 두 지점 중 하나에 `forwardRef()`를 적용하되, 가능하면 순환 자체를 제거하는 방향(공통 로직을 별도 모듈로 추출)을 우선 검토한다.

Spring에서는 세터 주입이나 `@Lazy`로 순환을 완화할 수 있지만, 필드/세터 주입이 테스트 용이성과 불변성을 해친다는 이유로 생성자 주입이 권장되는 것처럼, NestJS에서도 `forwardRef`는 임시방편이며 구조적으로 모듈 경계를 다시 그리는 것이 더 근본적인 해법으로 취급된다.

## 8. 프로바이더 스코프와 메타데이터의 인스턴스 캐싱 전략

NestJS 프로바이더는 `Scope.DEFAULT`(싱글톤), `Scope.REQUEST`, `Scope.TRANSIENT` 세 가지 스코프를 가지며, 이 선택은 단순한 설정이 아니라 DI 컨테이너의 인스턴스 캐싱 전략 자체를 바꾼다. 싱글톤은 모듈 그래프 구성 시 한 번만 인스턴스화되어 애플리케이션 전체 수명 동안 재사용되지만, `Scope.REQUEST`로 지정된 프로바이더는 매 HTTP 요청마다 새 인스턴스가 생성된다.

```typescript
@Injectable({ scope: Scope.REQUEST })
class RequestContextService {
  constructor(@Inject(REQUEST) private readonly request: Request) {}
}
```

문제는 요청 스코프 프로바이더를 의존하는 다른 프로바이더도 연쇄적으로 요청 스코프가 되어야 한다는 점이다. NestJS는 이를 "스코프 버블링(bubbling up)"이라 부르며, 싱글톤 컨트롤러가 요청 스코프 프로바이더를 주입받으면 해당 컨트롤러의 인스턴스화 방식 자체가 요청 단위 동적 생성으로 바뀐다. 이는 Spring의 `@RequestScope` 빈이 프록시 모드(`ScopedProxyMode.TARGET_CLASS`)를 통해 싱글톤 컨텍스트 안에서도 실제 호출 시점에 요청 빈으로 위임되는 방식과 대비된다. Spring은 프록시 한 겹을 두어 스코프 전파를 격리하는 반면, NestJS는 의존성 그래프 자체를 요청마다 재구성하는 방식이라 요청량이 많은 서비스에서는 이 재구성 비용이 무시할 수 없는 수준이 될 수 있다.

<table header-row="true"><tr><td>스코프</td><td>인스턴스화 시점</td><td>요청당 비용</td><td>대표 사용처</td></tr><tr><td>DEFAULT(싱글톤)</td><td>부트스트랩 1회</td><td>없음</td><td>대부분의 서비스, 리포지토리</td></tr><tr><td>REQUEST</td><td>요청마다</td><td>DI 그래프 재구성 비용</td><td>요청 컨텍스트, 멀티테넌시 식별자</td></tr><tr><td>TRANSIENT</td><td>주입될 때마다</td><td>주입 지점마다 재생성</td><td>상태를 갖는 헬퍼, 로거 인스턴스</td></tr></table>

실무에서 관찰되는 일반적인 패턴은, 요청 스코프가 정말 필요한 것은 멀티테넌시 식별자나 요청별 트레이스 컨텍스트 정도이고, 이를 컨트롤러 계층까지 전파시키지 않도록 요청 스코프 프로바이더를 최대한 얕은 계층(인터셉터나 미들웨어에 가까운 곳)에 격리하는 것이다. 요청 스코프가 깊은 의존성 체인 전체에 전파되면, 트래픽이 증가할 때 CPU 프로파일에서 DI 컨테이너의 인스턴스 생성 오버헤드가 눈에 띄게 잡히는 경우가 많아, 대안으로 `AsyncLocalStorage`를 이용해 요청 컨텍스트를 스코프 밖에서 전역적으로(그러나 요청별로 격리되게) 전달하는 방식을 채택하기도 한다.

```typescript
export const requestContext = new AsyncLocalStorage<{ tenantId: string }>();

// 미들웨어에서 컨텍스트 설정
app.use((req, res, next) => {
  requestContext.run({ tenantId: req.headers["x-tenant-id"] as string }, next);
});

// 싱글톤 서비스에서 스코프 없이 컨텍스트 조회
@Injectable()
class TenantAwareRepository {
  findAll() {
    const { tenantId } = requestContext.getStore()!;
    return this.db.query("SELECT * FROM items WHERE tenant_id = ?", [tenantId]);
  }
}
```

이 방식은 DI 그래프 재구성 없이 싱글톤 인스턴스를 유지하면서도 요청별 컨텍스트 격리를 달성하므로, 요청 스코프의 정확한 시맨틱이 필요하지 않은 대부분의 멀티테넌시 시나리오에서 더 나은 성능 트레이드오프를 제공한다.

## 참고

- NestJS 공식 문서, "Custom decorators" 및 "Circular dependency" 섹션
- TC39 Decorators Proposal (Stage 3) 명세
- reflect-metadata (rbuckton/reflect-metadata) GitHub 저장소 README
- Spring Framework 공식 문서, "BeanFactory and ApplicationContext" 챕터
