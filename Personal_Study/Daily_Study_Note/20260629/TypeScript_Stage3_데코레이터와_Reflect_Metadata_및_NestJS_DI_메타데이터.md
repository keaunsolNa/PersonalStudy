Notion 원본: https://app.notion.com/p/38e5a06fd6d381c78b75fdceb364698c

# TypeScript Stage 3 데코레이터와 Reflect Metadata 및 NestJS DI 메타데이터

> 2026-06-29 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TC39 Stage 3 데코레이터와 레거시 `experimentalDecorators` 의 호출 규약 차이를 구분한다
- 데코레이터 context 객체와 `addInitializer` 로 메서드/필드를 변형한다
- `reflect-metadata` 의 메타데이터 저장 모델과 `emitDecoratorMetadata` 의 한계를 설명한다
- NestJS 가 메타데이터를 통해 DI 토큰을 해석하는 흐름을 추적한다

## 1. 두 가지 데코레이터 — 무엇이 표준인가

TypeScript 의 데코레이터는 역사적으로 두 갈래다. 5.0 이전의 *레거시* 데코레이터는 `tsconfig` 의 `experimentalDecorators: true` 로만 켜지며 오래된 TC39 제안에 기반한다. TypeScript 5.0 이 정식 지원한 *Stage 3* 데코레이터는 플래그 없이 기본 동작하며 표준 진행 단계가 더 앞서 있다. 두 방식은 호출 규약이 완전히 다르므로 섞어 쓸 수 없다.

```jsonc
// 레거시: 이 플래그가 있으면 Stage 3 가 아니라 옛 규약을 쓴다
{ "compilerOptions": { "experimentalDecorators": true } }

// Stage 3: 플래그 없이 TypeScript 5.0+ 에서 기본 동작
{ "compilerOptions": { "target": "ES2022" } }
```

현재 NestJS, TypeORM, Angular 등 대형 프레임워크는 여전히 레거시 데코레이터 + `emitDecoratorMetadata` 에 의존한다. 표준 데코레이터로의 전환이 진행 중이지만, 메타데이터 자동 방출 기능이 표준에는 아직 없어 마이그레이션이 지연되고 있다. 따라서 "어떤 데코레이터를 쓰는가"는 프레임워크 선택에 종속되는 결정이다.

## 2. Stage 3 데코레이터의 호출 규약

표준 데코레이터는 `(value, context)` 두 인자를 받는다. `value` 는 데코레이트 대상(메서드, 접근자 등)이고 `context` 는 종류·이름·정적 여부 등의 메타 정보를 담는다.

```typescript
function logged<This, Args extends unknown[], Return>(
	target: (this: This, ...args: Args) => Return,
	context: ClassMethodDecoratorContext<This, (this: This, ...args: Args) => Return>
) {
	const name = String(context.name);
	return function (this: This, ...args: Args): Return {
		console.log(`[enter] ${name}`);
		const result = target.call(this, ...args);
		console.log(`[exit] ${name}`);
		return result;
	};
}

class Service {
	@logged
	compute(x: number): number {
		return x * 2;
	}
}
```

메서드 데코레이터는 새 함수를 반환해 원본을 교체한다. `context.addInitializer` 를 쓰면 인스턴스 생성 시점에 실행될 로직(예: 메서드 바인딩)을 등록할 수 있다.

```typescript
function bound(target: Function, context: ClassMethodDecoratorContext) {
	context.addInitializer(function (this: any) {
		this[context.name] = this[context.name].bind(this);
	});
}
```

레거시 데코레이터가 `(target, propertyKey, descriptor)` 3 인자에 `PropertyDescriptor` 를 직접 변형했던 것과 비교하면, Stage 3 은 descriptor 를 노출하지 않고 *반환값 교체* 와 *initializer* 라는 더 제한적이고 예측 가능한 인터페이스를 쓴다.

## 3. reflect-metadata 의 저장 모델

`reflect-metadata` 는 객체와 키에 임의의 메타데이터를 연결하는 polyfill 이다. 내부적으로 `WeakMap<target, Map<propertyKey, Map<metadataKey, value>>>` 구조의 전역 저장소를 둔다.

```typescript
import "reflect-metadata";

const FORMAT_KEY = Symbol("format");

function format(fmt: string) {
	return Reflect.metadata(FORMAT_KEY, fmt);
}

class Greeter {
	@format("Hello, %s")
	greeting!: string;
}

const fmt = Reflect.getMetadata(FORMAT_KEY, Greeter.prototype, "greeting");
// "Hello, %s"
```

핵심은 메타데이터가 *대상 객체에 직접 붙는 것이 아니라* 별도 WeakMap 에 저장된다는 점이다. 따라서 대상이 GC 되면 메타데이터도 함께 회수되어 누수가 없다. `getMetadata` 는 프로토타입 체인을 따라 상속된 메타데이터까지 조회하고, `getOwnMetadata` 는 자기 자신에 직접 정의된 것만 본다.

## 4. emitDecoratorMetadata 가 자동으로 심는 것

`emitDecoratorMetadata: true` 를 켜면 컴파일러가 데코레이트된 선언에 세 가지 메타데이터를 자동 방출한다. 이것이 NestJS DI 의 마법처럼 보이는 부분의 실체다.

| 메타데이터 키 | 내용 | 예 |
|---|---|---|
| `design:type` | 프로퍼티/접근자의 타입 | `String`, `Number` |
| `design:paramtypes` | 생성자/메서드 파라미터 타입 배열 | `[Logger, Repository]` |
| `design:returntype` | 메서드 반환 타입 | `Promise` |

```typescript
@Injectable()
class UserService {
	constructor(private repo: UserRepository, private logger: Logger) {}
}

// 컴파일러가 자동 방출:
// Reflect.getMetadata("design:paramtypes", UserService)
//   === [UserRepository, Logger]
```

여기에 결정적 한계가 있다. 방출되는 것은 *런타임에 존재하는 값으로서의 클래스 참조* 뿐이다. 인터페이스, 제네릭 타입 인자, 유니온은 런타임 표현이 없어 모두 `Object` 로 소거된다. 그래서 NestJS 에서 인터페이스를 직접 주입할 수 없고 반드시 `@Inject('TOKEN')` 으로 문자열/심볼 토큰을 명시해야 하는 것이다.

## 5. NestJS DI 메타데이터 해석 흐름

NestJS 컨테이너는 클래스를 인스턴스화할 때 다음 순서로 의존성을 해석한다.

```typescript
// 단순화한 컨테이너 의사 구현
function resolve<T>(token: Type<T>): T {
	const paramTypes: Type[] =
		Reflect.getMetadata("design:paramtypes", token) ?? [];
	const customTokens = Reflect.getMetadata("self:paramtokens", token) ?? {};

	const deps = paramTypes.map((paramType, index) => {
		// @Inject 로 지정된 커스텀 토큰이 우선
		const explicit = customTokens[index];
		return explicit ? resolve(container.get(explicit)) : resolve(paramType);
	});

	return new token(...deps);
}
```

흐름을 정리하면, `@Injectable()` 데코레이터가 클래스를 프로바이더로 표시하고, `emitDecoratorMetadata` 가 `design:paramtypes` 로 생성자 파라미터의 타입 배열을 심는다. 컨테이너는 이 배열을 순회하며 각 타입을 토큰으로 삼아 재귀적으로 인스턴스를 해석한다. `@Inject()` 가 붙은 파라미터는 별도 메타데이터에 인덱스→토큰 매핑이 저장되어 타입 추론을 덮어쓴다. 이 전체가 컴파일 타임 메타데이터 방출과 런타임 리플렉션의 협업으로 성립한다.

## 6. 표준 데코레이터로의 전환에서 생기는 공백

Stage 3 표준에는 `design:paramtypes` 같은 자동 메타데이터 방출 메커니즘이 없다. 표준 데코레이터의 `context.metadata` 객체에 데코레이터가 *명시적으로* 값을 써야 한다. 따라서 프레임워크들은 전환 시 타입 정보를 사람이 직접 토큰으로 적거나, 별도 빌드 플러그인으로 메타데이터를 생성하는 방향을 검토한다.

```typescript
// 표준 데코레이터에서 메타데이터는 context.metadata 에 수동 기록
function entity(value: any, context: ClassDecoratorContext) {
	context.metadata.tableName = context.name;
}

@entity
class Order {}

// Symbol.metadata 로 조회 (ES proposal)
const meta = (Order as any)[Symbol.metadata];
// { tableName: "Order" }
```

실무 판단은 명확하다. 현재 NestJS/TypeORM 스택을 쓴다면 `experimentalDecorators` + `emitDecoratorMetadata` 를 유지하는 것이 안정적이다. 신규 라이브러리를 직접 설계한다면 타입 소거에 의존하지 않는 명시적 토큰 기반 API 로 가야 표준 전환에 강건하다.

## 7. 성능과 안전성 트레이드오프

리플렉션 기반 DI 는 보일러플레이트를 크게 줄이지만 비용이 있다. 메타데이터 조회는 WeakMap 룩업이라 런타임 오버헤드 자체는 작지만, 애플리케이션 부트스트랩 시 모든 프로바이더의 메타데이터를 읽고 의존성 그래프를 구성하는 일회성 비용이 든다. 프로바이더 수백 개 규모에서 부트스트랩이 수백 ms 가 될 수 있어, 콜드 스타트가 중요한 서버리스 환경에서는 컴파일 타임 DI(예: 정적 분석 기반 코드 생성)가 더 유리하다. 또한 타입 소거 때문에 인터페이스 주입이 컴파일 타임에 검증되지 않으므로, 토큰 오타 같은 실수는 런타임 부트스트랩 시점에야 드러난다는 안전성 공백을 인지해야 한다.

## 8. 접근자·필드·클래스 데코레이터의 동작 차이

Stage 3 은 데코레이트 대상별로 받는 `value` 와 반환 의미가 다르다. 이 차이를 모르면 "왜 내 데코레이터가 무시되나"라는 흔한 혼란에 빠진다.

| 대상 | `value` 인자 | 반환값 의미 |
|---|---|---|
| 메서드 | 메서드 함수 | 새 함수면 교체 |
| getter/setter | 해당 접근자 함수 | 새 접근자로 교체 |
| 필드 | `undefined` | 초기화 함수 `(initial) => newValue` |
| 클래스 | 생성자 | 새 클래스로 교체 |
| auto-accessor | `{ get, set }` | `{ get, set, init }` |

필드 데코레이터는 특히 직관에 어긋난다. `value` 가 `undefined` 이고, 반환한 함수가 *초기화 시점에 초깃값을 받아 변환* 한다.

```typescript
function double(_: undefined, context: ClassFieldDecoratorContext) {
	return function (initialValue: number) {
		return initialValue * 2; // 필드 초기화 값을 가로채 변환
	};
}

class Box {
	@double size = 10; // 인스턴스 생성 시 size === 20
}
```

auto-accessor(`accessor` 키워드)는 Stage 3 에서 새로 도입된 대상으로, getter/setter/초기화를 한 번에 다룰 수 있어 옵저버블·검증 로직을 깔끔하게 붙이기 좋다.

```typescript
function tracked<T>(value: ClassAccessorDecoratorTarget<unknown, T>, context: ClassAccessorDecoratorContext) {
	return {
		get(this: any) { return value.get.call(this); },
		set(this: any, v: T) {
			console.log(`${String(context.name)} = ${v}`);
			value.set.call(this, v);
		},
	};
}

class State {
	@tracked accessor count = 0; // set 할 때마다 로그
}
```

## 9. 메타데이터 누수와 상속 함정

리플렉션 메타데이터는 강력하지만 상속과 결합하면 미묘한 버그를 만든다. `getMetadata` 는 프로토타입 체인을 따라가므로, 부모 클래스의 메타데이터를 자식이 *의도치 않게* 상속한다.

```typescript
import "reflect-metadata";

@Reflect.metadata("role", "admin")
class Base {}

class Child extends Base {}

Reflect.getMetadata("role", Child);     // "admin" — 상속됨
Reflect.getOwnMetadata("role", Child);  // undefined — 자기 것만
```

DI 컨테이너나 ORM 이 데코레이터 메타데이터로 동작을 결정할 때, 자식이 부모의 설정을 조용히 물려받아 예상치 못한 동작을 하는 사례가 잦다. 따라서 프레임워크를 직접 만들거나 확장할 때는 *상속 의도가 있는 메타데이터는 `getMetadata`, 없으면 `getOwnMetadata`* 를 명확히 구분해 써야 한다.

또 하나의 함정은 빌드 도구 간 메타데이터 호환성이다. `swc`, `esbuild`, `babel` 의 데코레이터/`emitDecoratorMetadata` 지원 수준이 `tsc` 와 미묘하게 달라, 같은 코드가 빌드 도구에 따라 메타데이터를 다르게 방출하거나 누락한다. NestJS 프로젝트가 빌드 도구를 `tsc` 에서 `swc` 로 바꿨을 때 DI 가 깨지는 전형적 원인이 이것이다. 해결은 `@Inject('TOKEN')` 으로 토큰을 *명시* 해 타입 소거·메타데이터 방출에 의존하지 않는 것이다. 결국 견고한 설계는 "리플렉션에 덜 의존"하는 방향으로 수렴한다.

## 참고

- TC39 Decorators Proposal (Stage 3) (https://github.com/tc39/proposal-decorators)
- TypeScript 5.0 Release Notes — Decorators
- reflect-metadata 명세 (https://rbuckton.github.io/reflect-metadata/)
- NestJS Documentation — Custom Providers / Dependency Injection
