Notion 원본: https://app.notion.com/p/39d5a06fd6d381e3bf76df26eef0eac9

# TypeScript Stage 3 데코레이터와 Reflect Metadata 및 NestJS DI 컨테이너

> 2026-07-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TC39 Stage 3 데코레이터와 레거시 `experimentalDecorators`의 시그니처 차이를 코드로 구분한다
- 데코레이터 컨텍스트 객체(`ClassMethodDecoratorContext` 등)와 `addInitializer`의 실행 시점을 추적한다
- `reflect-metadata`가 `design:paramtypes`를 어떻게 방출하며 Stage 3에서 왜 동작하지 않는지 확인한다
- NestJS DI 컨테이너가 메타데이터로 생성자 의존성을 해석하는 경로를 재구성한다

## 1. 두 개의 데코레이터가 존재한다

TypeScript에는 서로 호환되지 않는 두 데코레이터 구현이 공존한다. 하나는 2015년경 도입된 레거시 데코레이터로 `tsconfig`의 `experimentalDecorators: true`로 켠다. 다른 하나는 TypeScript 5.0에서 도입된 TC39 Stage 3 표준 데코레이터로, 아무 플래그 없이 기본 활성화된다. 두 구현은 데코레이터 함수의 인자 형태가 완전히 다르기 때문에, 하나를 위해 작성한 코드는 다른 쪽에서 그대로 깨진다.

레거시 데코레이터는 `(target, propertyKey, descriptor)`를 받는다. Stage 3는 `(value, context)` 두 인자만 받으며, 두 번째 `context`가 모든 메타 정보를 담는 객체다. 핵심 함정은 이것이다. NestJS·TypeORM·Angular 생태계 전부가 아직 레거시 데코레이터 위에 서 있으므로, 이들 프레임워크를 쓰려면 반드시 `experimentalDecorators: true`를 유지해야 한다. Stage 3로 무심코 전환하면 런타임 DI가 조용히 실패한다.

```jsonc
// 레거시(NestJS/TypeORM용) — 반드시 이 조합
{
  "compilerOptions": {
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true,   // design:type 메타데이터 방출
    "target": "ES2021"
  }
}
```

## 2. Stage 3 데코레이터 시그니처와 컨텍스트

Stage 3 메서드 데코레이터는 대상 메서드 함수와 컨텍스트를 받고, 교체할 새 함수를 반환하거나 `undefined`를 반환한다. 컨텍스트에는 `kind`, `name`, `static`, `private`, `access`, 그리고 `addInitializer`가 들어 있다. 아래는 실행 시간을 로깅하는 메서드 데코레이터다.

```typescript
function logged<This, Args extends unknown[], Return>(
  target: (this: This, ...args: Args) => Return,
  context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>
) {
  const name = String(context.name);
  return function (this: This, ...args: Args): Return {
    const start = performance.now();
    try {
      return target.call(this, ...args);
    } finally {
      console.log(`${name} took ${(performance.now() - start).toFixed(2)}ms`);
    }
  };
}

class Repo {
  @logged
  find(id: number) {
    return { id };
  }
}
```

`context.addInitializer`는 인스턴스가 생성될 때(인스턴스 필드/메서드) 또는 클래스 정의가 끝날 때(static) 실행할 콜백을 등록한다. 이것이 레거시에서 `target.prototype`을 직접 조작하던 패턴을 대체한다.

## 3. 데코레이터 평가 순서와 초기화 타이밍

여러 데코레이터가 겹칠 때 평가(evaluation)와 적용(application)은 순서가 다르다. 데코레이터 표현식 자체는 위에서 아래로 평가되지만, 반환된 래퍼의 적용은 아래에서 위로 진행된다.

```typescript
function trace(label: string) {
  console.log(`eval ${label}`);
  return (_: unknown, ctx: ClassMethodDecoratorContext) => {
    console.log(`apply ${label} on ${String(ctx.name)}`);
  };
}

class Svc {
  @trace("A") @trace("B") run() {}
}
// 출력: eval A, eval B, apply B, apply A
```

`eval`은 A→B(위→아래), `apply`는 B→A(아래→위)다. 이 비대칭은 팩토리 부수효과에서 실수를 유발한다.

## 4. reflect-metadata와 emitDecoratorMetadata

`reflect-metadata`는 `Reflect.defineMetadata`/`Reflect.getMetadata` API를 폴리필하는 라이브러리다. `emitDecoratorMetadata: true`를 켜면 레거시 데코레이터가 붙은 선언에 대해 컴파일러가 `design:type`, `design:paramtypes`, `design:returntype`을 자동 방출한다. 이 방출은 타입 정보를 런타임 값으로 굽는 유일한 표준 경로다.

```typescript
import "reflect-metadata";

@Reflect.metadata("role", "service")   // 데코레이터가 최소 1개 있어야 방출됨
class UserService {
  constructor(private repo: UserRepo, private cache: CacheClient) {}
}

const paramTypes = Reflect.getMetadata("design:paramtypes", UserService);
// [ [Function: UserRepo], [Function: CacheClient] ]
```

데코레이터가 하나도 없는 클래스에는 메타데이터가 방출되지 않고, 파라미터 타입이 인터페이스면 런타임에 값이 없으므로 `Object`로 축약된다. DI가 인터페이스 주입을 지원하지 못하고 클래스 토큰을 요구하는 근본 이유다.

## 5. Stage 3에서 reflect-metadata가 죽는 이유

`emitDecoratorMetadata`는 레거시 데코레이터 파이프라인에만 결합돼 있다. Stage 3로 전환하면 `design:paramtypes`가 방출되지 않아 `Reflect.getMetadata`가 `undefined`를 돌려주고 NestJS가 즉시 깨진다.

| 항목 | 레거시(`experimentalDecorators`) | Stage 3(기본) |
|---|---|---|
| 데코레이터 인자 | `(target, key, descriptor)` | `(value, context)` |
| 파라미터 데코레이터 | 지원 | 미지원(제안 단계) |
| `emitDecoratorMetadata` | 동작 | 무시됨 |
| NestJS/TypeORM | 필수 | 미지원 |
| 메타데이터 저장소 | `reflect-metadata` | `context.metadata` |

실무 결론(Yes/No): NestJS/TypeORM/Angular를 쓴다면 Stage 3로 전환할 수 있는가? 아니오. 프레임워크가 레거시 메타데이터에 의존하는 한 `experimentalDecorators`를 유지해야 한다.

## 6. NestJS DI 컨테이너 재구성

`@Injectable()`은 클래스에 최소 한 개의 데코레이터를 붙여 `emitDecoratorMetadata`가 `design:paramtypes`를 방출하도록 트리거하는 마커다. 컨테이너는 프로바이더를 인스턴스화할 때 `design:paramtypes`를 읽어 각 생성자 파라미터 타입(=주입 토큰)을 얻고, 레지스트리에서 재귀적으로 해석해 넘긴다.

```typescript
import "reflect-metadata";

type Ctor<T = any> = new (...args: any[]) => T;
const registry = new Map<Ctor, any>();

function resolve<T>(target: Ctor<T>): T {
  if (registry.has(target)) return registry.get(target);
  const deps: Ctor[] = Reflect.getMetadata("design:paramtypes", target) ?? [];
  const args = deps.map((dep) => resolve(dep));   // 재귀 해석
  const instance = new target(...args);
  registry.set(target, instance);                 // 싱글턴 캐시
  return instance;
}
```

인터페이스 주입이 불가능한 문제는 `@Inject(TOKEN)` 파라미터 데코레이터로 우회하고, 순환 의존성은 `forwardRef`로 지연 해석한다.

## 7. 성능·번들 관점의 실측 감각

`reflect-metadata` 폴리필은 약 50KB를 번들에 더한다. 서버(NestJS)에서는 부팅 시 1회 메타데이터 조회로 그래프를 구성하므로 핫패스 영향이 없다. esbuild는 오랫동안 `emitDecoratorMetadata`를 완전 지원하지 않아 NestJS 빌드에 `tsc`나 SWC를 요구했으므로, 파이프라인을 바꾸려면 메타데이터 방출 지원 여부를 먼저 검증해야 한다.

## 8. 마이그레이션 전략과 체크리스트

프레임워크 DI에 묶이지 않는 새 프로젝트는 Stage 3를 채택하고 메타데이터가 필요하면 `context.metadata`에 명시 기록한다. 기존 NestJS 코드베이스는 레거시를 유지하되 `experimentalDecorators`와 `emitDecoratorMetadata`가 함께 켜져 있는지, `import "reflect-metadata"`가 엔트리포인트 최상단에 단 한 번 로드되는지 확인해야 한다. 파라미터 데코레이터를 쓰는가, `design:paramtypes`에 의존하는가, 서드파티 데코레이터를 소비하는가 중 하나라도 예이면 레거시를 유지한다.

## 참고

- TC39 Decorators Proposal (Stage 3): https://github.com/tc39/proposal-decorators
- TypeScript 5.0 Release Notes — Decorators: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html
- TC39 Decorator Metadata Proposal: https://github.com/tc39/proposal-decorator-metadata
- NestJS Fundamentals — Custom Providers: https://docs.nestjs.com/fundamentals/custom-providers
- reflect-metadata: https://github.com/rbuckton/reflect-metadata
