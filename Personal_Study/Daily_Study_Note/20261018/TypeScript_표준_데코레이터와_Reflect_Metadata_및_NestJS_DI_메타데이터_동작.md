Notion 원본: https://app.notion.com/p/3fd5a06fd6d3817ca93af4880c996faf?pvs=204

# TypeScript 5 표준(Stage 3) 데코레이터와 experimentalDecorators·Reflect Metadata 차이 및 NestJS DI 메타데이터 동작

> 2026-10-18 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 표준 데코레이터와 experimentalDecorators의 시그니처·평가 모델 차이를 코드로 비교한다
- `context.metadata`(Symbol.metadata)와 reflect-metadata의 저장 구조 차이를 추적한다
- `emitDecoratorMetadata`가 만드는 `design:paramtypes`가 NestJS DI의 토큰이 되는 경로를 재현한다
- 표준 데코레이터로 옮길 때 NestJS 계열 DI가 어떻게 깨지는지 실패 출력으로 확인한다

## 1. 두 가지 데코레이터가 공존하는 이유

TypeScript의 데코레이터는 이름이 같은 서로 다른 두 기능이다. 2015년경 Angular 요구로 들어간 `experimentalDecorators`는 당시 TC39 제안 초안을 구현한 것이고, TypeScript 5.0부터 켜지는 기본 데코레이터는 이후 Stage 3에 오른 현재 제안을 구현한 것이다. 둘은 호출 시그니처, 적용 가능한 위치, 메타데이터 방식이 모두 다르다. 같은 `@Foo` 문법이지만 컴파일러 플래그 하나로 완전히 다른 JS가 방출된다.

실무에서 이 구분이 중요한 이유는 NestJS, TypeORM, class-validator, InversifyJS 같은 생태계가 모두 experimental 쪽에 서 있기 때문이다. Spring 애노테이션과 달리 TS 데코레이터는 클래스 정의 시점에 실행되는 함수일 뿐이고, 타입은 컴파일 후 지워진다. DI 컨테이너가 생성자 인자 타입을 아는 것은 `emitDecoratorMetadata`가 타입을 런타임 값으로 바꿔 심어 주기 때문이다. 실험 환경은 TypeScript 6.0.3, Node 22.22, reflect-metadata 0.2.2, @nestjs/core 12.1.3이다.

## 2. 시그니처와 적용 범위 비교

experimental 데코레이터는 위치마다 인자가 다르다. 클래스는 `(constructor)`, 메서드는 `(prototype, key, descriptor)`, 파라미터는 `(prototype, key, index)`를 받고, 반환값으로 디스크립터를 교체한다. 표준 데코레이터는 모든 위치가 `(value, context)` 두 인자로 통일된다. `value`는 꾸밀 대상(클래스, 메서드 함수, 필드는 undefined), `context`는 `kind`, `name`, `static`, `private`, `access`, `addInitializer`, `metadata`를 가진 객체다.

| 항목 | experimentalDecorators | 표준(TS 5.0+) |
| --- | --- | --- |
| 호출 인자 | 위치별로 다름 | `(value, context)` 통일 |
| 파라미터 데코레이터 | 지원 | 없음 (TS1206) |
| 필드 | 프로토타입+키만 받음, 초기값 접근 불가 | 반환한 함수로 초기값 변환 |
| 신규 위치 | 없음 | `accessor` 필드 |
| 메타데이터 | reflect-metadata 외부 폴리필 | `context.metadata` + `Symbol.metadata` |
| 타입 방출 | `emitDecoratorMetadata` | 없음 |
| 사용 조건 | `experimentalDecorators: true` | 플래그 없이 기본 |

가장 큰 단절은 파라미터 데코레이터의 부재다. 아래처럼 표준 모드에서 `@Inject("x")`를 생성자 인자에 쓰면 컴파일 단계에서 막힌다.

```ts
const Inject = (t: string): ParameterDecorator => () => {};
class S {
  constructor(@Inject("x") private r: Repo) {}
}
```

```text
a.ts(4,15): error TS1206: Decorators are not valid here.
```

`emitDecoratorMetadata`를 표준 모드와 같이 켜면 다른 지점에서 거부된다. tsconfig에 `emitDecoratorMetadata`만 두고 `experimentalDecorators`를 빼면 `TS5052: Option 'emitDecoratorMetadata' cannot be specified without specifying option 'experimentalDecorators'`가 출력됐다. 타입 메타데이터 방출은 구조적으로 experimental 전용 기능이라는 뜻이다.

## 3. 표준 데코레이터의 평가 모델과 context

표준 데코레이터는 평가(decorator expression 실행)와 적용(decorator 호출)이 분리돼 있다. 클래스 본문의 멤버는 위에서 아래로 데코레이터가 호출되고, 한 멤버에 여러 개가 있으면 아래에서 위로 적용된다. 클래스 데코레이터는 모든 멤버 데코레이터 뒤에 호출된다. 메서드 데코레이터가 함수를 반환하면 그 함수가 원본을 대체하고, 필드 데코레이터는 초기값을 받아 변환하는 함수를 반환한다. 아래 코드는 메서드 래핑, 필드 등록, 클래스 initializer를 모두 쓰는 형태이며 `tsc` 통과 후 실행했다.

```ts
(Symbol as any).metadata ??= Symbol("Symbol.metadata");

function logged<This, A extends unknown[], R>(
  target: (this: This, ...args: A) => R,
  ctx: ClassMethodDecoratorContext<This, (this: This, ...args: A) => R>,
) {
  return function (this: This, ...args: A): R {
    console.log(`call ${String(ctx.name)}(${args.join(",")})`);
    return target.apply(this, args);
  };
}
function route(path: string) {
  return (_: Function, ctx: ClassMethodDecoratorContext) => {
    const routes = ((ctx.metadata.routes as string[]) ??= []);
    routes.push(`${String(ctx.name)}:${path}`);
  };
}
function Controller(_: Function, ctx: ClassDecoratorContext) {
  ctx.addInitializer(function () { console.log("class defined:", String(ctx.name)); });
}

@Controller
class CatsController {
  @route("/list") @logged list(page: number) { return page * 2; }
  @route("/get") get() { return 1; }
}
console.log((CatsController as any)[(Symbol as any).metadata]);
console.log(new CatsController().list(21));
```

```text
class defined: CatsController
[Object: null prototype] { routes: [ 'list:/list', 'get:/get' ] }
call list(21)
42
```

`logged`는 제네릭으로 `this`와 인자 튜플을 보존해 타입이 정확히 흐르지만 시그니처가 길어진다. tsconfig는 `target: ES2022`, `lib: ["ES2022","ESNext.Decorators"]`로 두었다. 이 lib 항목이 `ClassMethodDecoratorContext` 등 컨텍스트 타입을 제공한다.

## 4. context.metadata와 Symbol.metadata

표준 메타데이터는 TS 5.2에서 추가됐고, 클래스마다 하나의 `null` 프로토타입 객체를 만들어 모든 데코레이터의 `context.metadata`로 공유한다. 클래스 정의가 끝나면 그 객체가 `Class[Symbol.metadata]`에 붙는다. 핵심은 `Symbol.metadata`가 언어 내장 심볼로 아직 모든 런타임에 없다는 점이다. Node 22에서도 폴리필 없이는 `undefined`라서, 위 코드처럼 `Symbol.metadata ??= Symbol(...)`를 모듈 최초 실행 전에 둬야 한다. 폴리필이 없으면 TS가 방출한 코드가 메타데이터 객체를 달 곳이 없어진다. 내장 시점은 런타임 버전에 따라 다르다.

상속은 프로토타입 체인으로 표현된다. 자식 클래스의 메타데이터 객체는 부모 것을 `[[Prototype]]`으로 가진다.

```ts
function tag(v: string) { return (_: unknown, c: ClassDecoratorContext) => { c.metadata.tag = v; }; }
@tag("base") class A {}
@tag("child") class B extends A {}
const mA = (A as any)[META], mB = (B as any)[META];
console.log(mA.tag, mB.tag, Object.getPrototypeOf(mB) === mA, Object.hasOwn(mB, "tag"));
```

```text
base child true true
```

자식이 값을 쓰지 않으면 부모 값이 그대로 읽히고, 쓰면 자식 객체에 own property로 가려진다. 배열처럼 변경형 값을 `ctx.metadata.routes ??= []`로 다룰 때 함정이 있다. 자식에서 `??=`가 부모 배열을 발견해 그대로 `push`하면 부모 메타데이터가 오염된다. 상속 메타데이터에 누적할 때는 `Object.hasOwn` 검사 후 복사해서 써야 한다.

## 5. reflect-metadata의 저장 구조

reflect-metadata는 `Reflect.defineMetadata(key, value, target, propertyKey?)`로 대상 객체와 속성 키마다 `Map`을 보관하는 외부 레지스트리다. 클래스에 프로퍼티를 직접 달지 않고 내부 WeakMap에 (target, propertyKey, key) 단위로 보관한다. 조회는 `getMetadata`(프로토타입 체인 탐색)와 `getOwnMetadata`(자기 것만)로 나뉜다. 이 구분이 표준 `Symbol.metadata`의 프로토타입 상속과 대응되지만, 저장 키가 임의 값이고 속성 단위라는 점이 다르다.

| 구분 | reflect-metadata | Symbol.metadata |
| --- | --- | --- |
| 저장소 | 전역 WeakMap (모듈 import 필요) | 클래스 자체의 심볼 키 프로퍼티 |
| 키 단위 | (target, propertyKey, metadataKey) | 평면 객체, 키 충돌은 사용자 책임 |
| 상속 | `getMetadata`가 체인 탐색 | 프로토타입 체인 자체 |

실무적 차이는 "속성 키 단위 조회"에 있다. `Reflect.getMetadata("design:paramtypes", Svc.prototype, "run")`처럼 메서드별로 읽는 패턴은 표준 쪽에서는 `metadata` 객체 안에 `{ run: ... }` 식으로 직접 모델링해야 한다. 표준 메타데이터는 Reflect API가 아니라 단순 객체라서 구조 설계가 전적으로 라이브러리 몫이다.

## 6. emitDecoratorMetadata가 심는 값

`experimentalDecorators` + `emitDecoratorMetadata`를 켜면 데코레이터가 하나라도 붙은 선언에 대해 컴파일러가 `__metadata("design:type" | "design:paramtypes" | "design:returntype", ...)` 호출을 끼워 넣는다. 값은 타입 이름을 런타임 생성자로 치환한 것이다. 클래스는 그 클래스 값, 원시 타입은 박스 생성자(`String`, `Number`), 배열은 `Array`, 인터페이스·유니온·제네릭 타입 별칭은 `Object`로 지워진다. 아래는 실제 실행 결과다.

```ts
import "reflect-metadata";
class Repo {}
interface Clock { now(): Date }
const Injectable = (): ClassDecorator => () => {};
const Log = (): MethodDecorator => () => {};

@Injectable()
class Svc {
  constructor(private repo: Repo, private clock: Clock, private n: number) {}
  @Log() run(a: string, b: Repo[]): Promise<void> { return Promise.resolve(); }
}
console.log(Reflect.getMetadata("design:paramtypes", Svc));
console.log(Reflect.getMetadata("design:paramtypes", Svc.prototype, "run"));
console.log(Reflect.getMetadata("design:returntype", Svc.prototype, "run"));
```

```text
[ [class Repo], [Function: Object], [Function: Number] ]
[ [Function: String], [Function: Array] ]
[Function: Promise]
```

방출된 JS에는 `__metadata("design:paramtypes", [Repo, Object, Number])`가 클래스 데코레이터 배열에 들어 있다. 인터페이스 `Clock`이 `Object`가 된 점이 중요하다. 인터페이스로는 DI 토큰을 만들 수 없으므로 Spring의 인터페이스 주입과 달리 문자열·Symbol 토큰과 `@Inject(TOKEN)`을 써야 한다. 또 데코레이터가 하나도 없는 클래스에는 `design:paramtypes`가 방출되지 않아, `@Injectable()`을 빼먹은 서비스는 의존성이 비어 버린다. `import type`이나 순환 import로 클래스가 아직 `undefined`인 시점에 배열이 평가되면 `paramtypes` 안에 `undefined`가 들어가는데, 이것이 Nest의 "can't resolve dependencies (?)" 오류 대부분의 원인이다.

## 7. NestJS DI가 메타데이터를 읽는 경로

Nest는 모듈 스캔 시 각 프로바이더 클래스에서 두 키를 읽는다. 하나는 컴파일러가 심은 `design:paramtypes`(암묵적 클래스 토큰), 다른 하나는 `@Inject(token)`이 심는 `self:paramtypes`(인덱스와 토큰 쌍)다. 후자가 같은 인덱스의 전자를 덮어쓴다. 아래 재현에서 `Repo`는 타입으로, `CLOCK`은 명시 토큰으로 주입된다.

```ts
@Injectable() class Repo { hi() { return "repo"; } }
@Injectable() class Svc {
  constructor(private repo: Repo, @Inject("CLOCK") private clock: { now(): number }) {}
  run() { return this.repo.hi() + "@" + this.clock.now(); }
}
@Module({ providers: [Repo, Svc, { provide: "CLOCK", useValue: { now: () => 7 } }] })
class AppModule {}
```

```text
[ [class Repo], [Function: Object] ]
[ { index: 1, param: 'CLOCK' } ]
repo@7
```

두 번째 인자 타입이 `Object`로 나왔지만 `self:paramtypes`의 `{ index: 1, param: 'CLOCK' }`가 우선해 정상 해석된다. 이제 `emitDecoratorMetadata`만 끄면 어떻게 되는지 같은 코드를 다시 컴파일해 봤다.

```text
undefined
[ { index: 1, param: 'CLOCK' } ]
[Nest] ERROR [ExceptionHandler] UndefinedDependencyException [Error]:
  Nest can't resolve dependencies of the Svc (?, CLOCK).
  Please make sure that the argument at index [0] is available in the current module.
```

`design:paramtypes`가 `undefined`가 되면서 index 0이 `?`로 남는다. 명시 토큰(`@Inject`)만 있던 index 1은 살아남았다. 이 대비가 DI 메타데이터의 두 층을 가장 잘 보여 준다. 암묵 토큰은 컴파일러 플래그에 의존하고, 명시 토큰은 reflect-metadata에만 의존한다.

## 8. 표준 모드로 옮기면 생기는 일과 마이그레이션 판단

NestJS를 `experimentalDecorators` 없이 표준 모드로 컴파일하면 어떻게 될까. 타입 검사는 통과하는데(Nest 데코레이터 타입이 느슨해서) 동작이 다르다. 같은 서비스 구조를 `tsconfig`의 `lib: ["ES2022","ESNext.Decorators"]` 표준 설정으로 빌드해 실행한 결과는 아래와 같다.

```text
undefined
TypeError: Cannot read properties of undefined (reading 'hi')
```

첫 줄은 `design:paramtypes`가 없다는 뜻이고, 둘째 줄이 더 위험하다. Nest는 의존성 목록이 비어 있다고 보고 `new Svc()`를 인자 없이 호출하므로 DI 오류가 아니라 호출 시점의 `TypeError`로 늦게 터진다. 즉 "빌드 성공, 부팅 성공, 첫 요청에서 실패"라는 최악의 형태다. 또한 파라미터 데코레이터가 문법 오류이므로 `@Inject`, `@Body()`, `@Param()` 같은 Nest의 핵심 API가 표준 모드에서는 쓸 수 없다(TS1206). 설치한 Nest 12.1.3은 experimental을 전제로 동작했고, 향후 표준 지원은 공식 문서에서 확인해야 한다.

표준 모드로 가는 DI 설계는 타입 방출을 포기하고 명시적 선언을 쓰는 방식이 된다. 아래 미니 컨테이너는 클래스 데코레이터에 의존성 클래스를 직접 넘겨 해석하며, 실행 시 `repo true`(싱글턴 동일성)를 출력했다.

```ts
type Token<T = unknown> = abstract new (...a: any[]) => T;
const registry = new Map<Function, Token[]>();
function Injectable(...deps: Token[]) {
  return (target: Function, _ctx: ClassDecoratorContext) => { registry.set(target, deps); };
}
const cache = new Map<Function, unknown>();
function resolve<T>(t: new (...a: any[]) => T): T {
  if (cache.has(t)) return cache.get(t) as T;
  const inst = new t(...(registry.get(t) ?? []).map((d) => resolve(d as any)));
  cache.set(t, inst);
  return inst;
}
@Injectable() class Repo { hi() { return "repo"; } }
@Injectable(Repo) class Svc { constructor(readonly repo: Repo) {} }
console.log(resolve(Svc).repo.hi(), resolve(Svc) === resolve(Svc));
```

대가는 `deps` 배열과 인자 순서가 어긋나도 컴파일러가 못 잡는다는 점이다. 판단 기준은 이렇다. 기존 Nest·TypeORM·class-validator 코드베이스는 experimental을 유지하는 것이 사실상 유일한 선택이고, 신규 라이브러리나 프레임워크 비종속 코드(로깅·캐시 래퍼 같은 메서드 데코레이터)는 표준으로 작성해도 되지만 같은 프로젝트에서 플래그가 하나이므로 둘을 섞을 수 없다. 혼용이 필요하면 패키지 단위로 `tsconfig`를 분리해야 한다. 빌드 도구를 바꿀 때는 `design:paramtypes` 출력을 위 방식으로 직접 확인하는 편이 안전하다.

## 참고

- TypeScript 5.0 Release Notes: Decorators (typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html)
- TypeScript 5.2 Release Notes: Decorator Metadata (typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html)
- TC39 proposal-decorators, proposal-decorator-metadata (github.com/tc39)
- reflect-metadata (github.com/rbuckton/reflect-metadata)
- NestJS Documentation: Custom providers, Injection scopes (docs.nestjs.com)
