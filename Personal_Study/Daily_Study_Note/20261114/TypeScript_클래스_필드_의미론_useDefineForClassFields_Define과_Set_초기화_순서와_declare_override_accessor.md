Notion 원본: https://www.notion.so/4185a06fd6d381419d2edc10d30c1641

# TypeScript 클래스 필드 의미론: useDefineForClassFields 의 [[Define]] 과 [[Set]], 초기화 순서와 declare·override·accessor

> 2026-11-14 신규 주제 · 확장 대상: TypeScript(클래스와 tsconfig), JAVA(필드 초기화 순서와 오버라이드)

## 학습 목표

- 같은 클래스 소스가 `target` 과 `useDefineForClassFields` 조합에 따라 어떤 JS 로 emit 되는지 tsc 출력으로 구분한다
- 부모의 setter 나 생성자 대입을 자식 필드 선언이 덮어쓰는 경우를 찾아 `declare` 필드로 고친다
- 필드 초기화식, parameter property, 생성자 본문의 실행 순서를 추적해 TS2729 와 `undefined` 참조를 제거한다
- `override`, `noImplicitOverride`, `accessor`, `!` 를 목적에 맞게 골라 쓴다

## 1. 클래스 필드의 두 가지 의미론: [[Set]] 과 [[Define]]

`class Point { x = 1 }` 이라는 선언을 인스턴스에 반영하는 방법은 두 가지다. 생성자 안에서 `this.x = 1` 로 대입하는 [[Set]] 의미론과, `Object.defineProperty(this, "x", { value: 1, ... })` 로 자체 프로퍼티를 정의하는 [[Define]] 의미론이다. 두 경우에 결과가 갈린다. 프로토타입 체인에 같은 이름의 setter 가 있으면 대입은 그 setter 를 호출하고, 정의는 setter 를 건너뛰고 인스턴스에 데이터 프로퍼티를 만든다. 그리고 초기화식이 없는 필드 `y: number;` 는 대입 방식에서는 아무 코드도 만들지 않지만, 정의 방식에서는 값이 `undefined` 인 프로퍼티를 만든다.

TypeScript 는 [[Set]] 방식으로 필드를 내보내 왔고, ECMAScript 2022 의 클래스 필드는 [[Define]] 방식으로 표준화됐다. 이 차이를 메우려고 TypeScript 3.7 이 `useDefineForClassFields` 옵션과 `declare` 필드를 함께 도입했다(3.7 릴리스 노트). 아래가 기준 예제다.

```ts
class Point {
  x = 1;
  y: number;
  constructor() {
    this.y = 2;
  }
}
console.log(Object.getOwnPropertyNames(new Point())); // [ 'x', 'y' ]
```

`--target ES2015 --useDefineForClassFields true` 로 컴파일하면 [[Define]] 이 무엇인지가 그대로 보인다. `y` 는 초기화식이 없는데도 `void 0` 으로 먼저 정의된다.

```js
class Point {
    constructor() {
        Object.defineProperty(this, "x", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: 1
        });
        Object.defineProperty(this, "y", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: void 0
        });
        this.y = 2;
    }
}
```

## 2. useDefineForClassFields 기본값과 target 별 emit

이 옵션의 기본값은 고정이 아니라 `target` 을 따라간다. `target` 이 ES2022 이상(ESNext 포함)이면 `true`, 그보다 낮으면 `false` 다. `tsc --showConfig` 로 보면 `target: es2021` 에서는 이 옵션이 출력되지 않고, `--target es2022` 를 주면 `"useDefineForClassFields": true` 가 계산된 값으로 나타난다. `target` 을 한 단계 올리는 것만으로 클래스 필드의 런타임 의미가 바뀐다.

1절의 `Point` 를 컴파일한 결과는 다음과 같다.

| target | useDefineForClassFields | 필드 emit 형태 | 의미론 |
| --- | --- | --- | --- |
| ES2015, ES2021 | 미지정(기본 false) | 생성자 안 `this.x = 1` | [[Set]] |
| ES2015 | true | 생성자 안 `Object.defineProperty` | [[Define]] |
| ES2022, ESNext | 미지정(기본 true) | 클래스 본문의 네이티브 필드 `x = 1; y;` | [[Define]] |
| ES2022 | false | 생성자 안 `this.x = 1` | [[Set]] |

`--target ES2022` 기본값의 출력은 소스에서 타입만 지운 모양이고, 초기화식 없는 `y;` 가 클래스 본문에 남는다(6절의 emit 참고). `--target ES2022 --useDefineForClassFields false` 에서는 필드 선언이 사라지고 생성자 안에 `this.x = 1;` 과 `this.y = 2;` 만 남는다. `false` 로 고정하면 기존 동작이 유지되지만 네이티브 필드 문법을 쓰지 못한다. 어느 쪽이든 tsconfig 에 명시해 두면 `target` 변경이 의미론 변경으로 번지지 않는다.

## 3. 부모의 setter 를 가리는 자식 필드 선언

부모의 accessor 와 같은 이름을 자식이 필드로 선언하는 경우다.

```ts
class Base {
  private _name = "";
  get name(): string {
    return this._name;
  }
  set name(v: string) {
    console.log("setter:", v);
    this._name = v.toUpperCase();
  }
}

class Derived extends Base {
  // @ts-expect-error TS2610
  name = "kim";
}

const d = new Derived();
console.log(d.name);
console.log(Object.getOwnPropertyDescriptor(d, "name"));
```

`--target ES2022 --useDefineForClassFields false` 로 실행한 출력이다. 대입이 부모 setter 를 거치고 인스턴스에는 자체 프로퍼티가 없다.

```text
setter: kim
KIM
undefined
```

`--useDefineForClassFields true` 로 실행한 출력이다. setter 는 호출되지 않고 인스턴스의 데이터 프로퍼티가 부모의 accessor 를 가린다.

```text
kim
{ value: 'kim', writable: true, enumerable: true, configurable: true }
```

TypeScript 는 이 패턴을 컴파일 오류로 막는다. 주석을 지우면 `error TS2610: 'name' is defined as an accessor in class 'Base', but is overridden here in 'Derived' as an instance property.` 가 나온다. 옵션 값과 타깃(ES2015, ES2022)에 관계없이 같은 오류가 났고, 4.0 릴리스 노트의 "Properties Overriding Accessors (and vice versa) is an Error" 가 이 검사다. 반대로 부모의 프로퍼티를 자식이 accessor 로 덮으면 TS2611 이 난다.

## 4. 초기화식 없는 재선언과 declare 필드

타입을 좁히려고 필드를 초기화식 없이 다시 선언하는 코드는 [[Set]] 에서는 순수한 타입 선언이었지만 [[Define]] 에서는 `undefined` 를 정의하는 실행 코드다. 부모 생성자가 채운 값이 `super()` 직후에 지워진다.

```ts
type User = { id: number };
type Admin = User & { role: string };

class Repo {
  entity: User;
  constructor(entity: User) {
    this.entity = entity;
  }
}

class AdminRepoBad extends Repo {
  entity!: Admin; // Define 모드에서만 TS2612
}

class AdminRepoGood extends Repo {
  declare entity: Admin;
}

const admin: Admin = { id: 1, role: "root" };
console.log(new AdminRepoBad(admin).entity);
console.log(new AdminRepoGood(admin).entity.role);
```

`--useDefineForClassFields false` 에서는 오류 없이 `{ id: 1, role: 'root' }` 와 `root` 가 출력된다. `true` 에서는 `error TS2612: Property 'entity' will overwrite the base property in 'Repo'. ...`(뒤에 `declare` 를 붙이라는 안내가 이어진다)가 나오고, 그래도 emit 된 JS(`noEmitOnError` 미지정)를 실행하면 첫 줄이 `undefined` 로 바뀐다. emit 에서 `AdminRepoBad` 본문에는 `entity;` 가 남고 `AdminRepoGood` 은 본문이 비어 있다.

`declare` 필드는 emit 에서 사라지므로 초기화식을 가질 수 없다. `declare a: number = 1` 은 TS1039 로 거부된다.

TS2612 는 부모 클래스에 같은 이름의 선언이 있을 때만 나온다. ORM 의 엔티티 매핑이나 DI 의 프로퍼티 주입처럼 데코레이터가 프로토타입에 accessor 를 설치하는 구조는 경고 없이 깨진다. 아래는 최소 재현이다(`--experimentalDecorators` 필요).

```ts
function Tracked(proto: object, key: string): void {
  Object.defineProperty(proto, key, {
    set(v: unknown) {
      console.log(`tracked ${key} =`, v);
    },
    configurable: true,
  });
}

class Order {
  @Tracked status?: string;
}

const o = new Order();
o.status = "PAID";
console.log(Object.getOwnPropertyNames(o));
```

`false` 에서는 `tracked status = PAID` 와 `[]` 가 출력된다. `true` 에서는 컴파일 오류 없이 `[ 'status' ]` 만 출력된다. emit 된 `status;` 가 자체 프로퍼티를 만들어 프로토타입의 accessor 를 가렸기 때문이다. 이런 필드는 `declare status?: string` 으로 바꿔야 한다. 특정 ORM 이나 DI 프레임워크가 실제로 이 방식을 쓰는지는 실행해 확인하지 않았다.

## 5. 초기화 순서와 부모 생성자의 가상 메서드 호출

실행 순서는 부모 필드 초기화, 부모 생성자 본문, 자식 필드 초기화, 자식 생성자 본문이다. 부모 생성자가 오버라이드된 메서드를 호출하면 그 메서드는 초기화 전의 자식 필드를 본다.

```ts
class Parent {
  parentField = (console.log("parent field"), "p");
  constructor() {
    console.log("parent ctor body");
    this.init();
  }
  init(): void {}
}

class Child extends Parent {
  childField = (console.log("child field"), "c");
  constructor() {
    super();
    console.log("child ctor body");
  }
  override init(): void {
    console.log("init sees childField =", this.childField);
  }
}

new Child();
```

출력은 다음과 같고, `--target ES2022` 의 `true`·`false` 와 `--target ES2015` 기본값 세 경우에서 모두 같았다. 순서 자체는 의미론 선택과 무관하다.

```text
parent field
parent ctor body
init sees childField = undefined
child field
child ctor body
```

Java 도 같다. JLS 12.5 는 상위 클래스 생성자 호출이 끝난 뒤에 필드 초기화식을 실행한다고 정한다. 아래 코드를 OpenJDK 21 로 실행하면 `init sees childField = null` 이 출력된다.

```java
class Parent {
    Parent() { init(); }
    void init() {}
}

class Child extends Parent {
    String childField = "c";
    @Override
    void init() { System.out.println("init sees childField = " + childField); }
    public static void main(String[] args) { new Child(); }
}
```

차이는 초기화 전 값이 Java 는 `null`, TypeScript 는 `undefined` 라는 것과, TypeScript 타입 검사기가 `childField` 를 `string` 으로 보아 이 접근을 잡지 못한다는 것이다. 해법도 같아서, 생성자에서 오버라이드 가능한 메서드를 호출하지 않는다.

## 6. parameter property 와 필드 초기화식의 순서

parameter property `constructor(private name: string)` 는 생성자 본문 맨 앞의 `this.name = name` 으로 풀린다. [[Set]] emit 에서는 이 대입이 필드 초기화식보다 앞에 놓여서, 필드 초기화식이 parameter property 를 참조해도 동작했다.

```ts
class Service {
  prefix = this.name + ":";
  constructor(private name: string) {}
}
console.log(new Service("order").prefix);
```

`--target ES2022 --useDefineForClassFields false` 에서는 생성자 안에 `this.name = name;` 다음에 `this.prefix = this.name + ":";` 가 emit 되고 `order:` 가 출력된다.

`--target ES2022` 기본값(또는 ESNext)에서는 `error TS2729: Property 'name' is used before its initialization.` 이 나온다. emit 된 JS 를 실행하면 `undefined:` 가 출력된다. 네이티브 필드가 생성자 본문의 parameter property 대입보다 먼저 실행되기 때문이다.

```js
class Service {
    name;
    prefix = this.name + ":";
    constructor(name) {
        this.name = name;
    }
}
```

`--target ES2021 --useDefineForClassFields true` 에서는 tsc 가 `Object.defineProperty` 호출을 생성자 안에 parameter property, 필드 순으로 배치하므로 TS2729 없이 `order:` 가 출력됐다. 이 오류는 네이티브 필드로 emit 되는 ES2022 이상에서만 드러난다. 고치려면 `prefix: string;` 으로 선언만 하고 생성자 본문에서 `this.prefix = this.name + ":";` 로 대입한다. 이렇게 바꾼 코드는 ES2022 `true` 에서 오류 없이 `order:` 를 출력했다.

## 7. override, noImplicitOverride, 그리고 accessor 키워드

`override` 키워드와 `noImplicitOverride` 옵션은 4.3 에서 들어왔다. Java 의 `@Override` 와 목적이 같지만 강제 수준을 옵션으로 고른다는 점이 다르다.

```ts
class Shape {
  area(): number {
    return 0;
  }
}
class Circle extends Shape {
  area(): number { // noImplicitOverride 에서 TS4114
    return 3;
  }
  override perimeter(): number { // 항상 TS4113
    return 6;
  }
}
```

옵션 없이 컴파일하면 `TS4113: This member cannot have an 'override' modifier because it is not declared in the base class 'Shape'.` 하나만 나온다. `--noImplicitOverride` 를 추가하면 `area` 에 TS4114 가 더해진다. `override` 를 빠뜨린 오버라이드는 부모 메서드 이름이 바뀌어도 오류가 나지 않으므로 `noImplicitOverride` 까지 켜야 의미가 있다.

4.9 의 `accessor` 키워드(auto-accessor)는 필드처럼 선언하지만 getter 와 setter 쌍과 private 저장소로 풀리는 멤버를 만든다.

```ts
class Counter {
  accessor count = 0;
}
const c = new Counter();
c.count++;
console.log(c.count, Object.getOwnPropertyNames(c)); // 1 []
```

`--target ES2022` 의 emit 이다. 값은 private 필드에 있고 `count` 는 프로토타입의 accessor 다.

```js
class Counter {
    #count_accessor_storage = 0;
    get count() { return this.#count_accessor_storage; }
    set count(value) { this.#count_accessor_storage = value; }
}
```

`--target ESNext` 에서는 `accessor count = 0;` 이 그대로 출력되고, `--target ES2015` 에서는 `WeakMap` 기반 헬퍼로 내려간다. `useDefineForClassFields false` 에서도 ES2022 emit 은 같았다. auto-accessor 는 자체 프로퍼티를 만들지 않으므로 4절의 가림 문제가 없고 자식이 getter/setter 로 오버라이드할 수 있다. 대신 `Object.getOwnPropertyNames` 에 잡히지 않고, 자식이 일반 필드로 다시 선언하면 TS2610 이 난다.

## 8. strictPropertyInitialization 과 definite assignment `!`

`strict` 에 포함된 `strictPropertyInitialization` 은 초기화식도 없고 생성자에서 대입되지도 않는 필드를 TS2564 로 막는다. `!` 는 이 검사만 끄고 `?` 는 타입에 `undefined` 를 더할 뿐, emit 은 바꾸지 않는다.

```ts
class Entity {
  id: number; // TS2564
  name!: string;
  title?: string;
}
const e = new Entity();
console.log(Object.getOwnPropertyNames(e), e.name);
```

두 모드 모두 `id` 에 TS2564 를 낸다. emit 된 JS 를 실행하면 `false` 에서는 `[] undefined`, `true` 에서는 `[ 'id', 'name', 'title' ] undefined` 가 출력된다. [[Define]] 에서는 `name!: string` 도 자체 프로퍼티를 만든다. 생성자 이후에 대입으로 채우는 값이면 `!` 로 충분하지만, 부모 생성자나 프로토타입 accessor 가 값을 제공하면 `declare` 가 맞다.

## 9. 마이그레이션 체크리스트: target 을 ES2022 로 올릴 때

1. tsconfig 에 `useDefineForClassFields` 를 명시한다. 먼저 `false` 로 고정해 `target` 변경과 의미론 변경을 분리하고, 별도 작업으로 `true` 로 바꾼다.
2. `true` 로 바꾼 뒤 tsc 를 돌려 TS2612(초기화식 없는 재선언)와 TS2729(초기화 전 사용)를 모두 해소한다.
3. 데코레이터가 붙은 필드를 직접 점검한다. 4절의 `Tracked` 처럼 오류 없이 동작만 바뀐다.
4. 자체 프로퍼티 목록에 의존하는 코드를 점검한다. 8절처럼 초기화식 없는 필드가 `undefined` 값의 자체 프로퍼티로 나타나기 시작한다.
5. `noImplicitOverride` 를 함께 켠다. 초기화식이 있는 필드 재선언에도 TS4114 가 나오므로 상속 계층의 재선언을 찾는 데 도움이 된다.

이 노트의 TS 예제는 TypeScript 5.6.3 의 tsc 로 `--strict --module commonjs --skipLibCheck` 에 `--target`(ES2015, ES2021, ES2022, ESNext)과 `--useDefineForClassFields`(true, false, 미지정)를 바꿔 가며 컴파일했고, 출력이 있는 예제는 Node v22.23.4 로 실행해 결과를 그대로 옮겼다. 본문의 오류 코드(TS1039, TS2564, TS2610, TS2611, TS2612, TS2729, TS4113, TS4114)는 모두 실제 출력에서 확인했다. 4절의 데코레이터 예제에는 `--experimentalDecorators` 를 추가했고, Java 예제는 OpenJDK 21.0.12 로 실행했다. 기능 도입 버전(3.7, 4.0, 4.3, 4.9)은 릴리스 노트를 따랐으며 구버전 컴파일러는 실행하지 않았다.

## 참고

- TSConfig Reference, useDefineForClassFields: https://www.typescriptlang.org/tsconfig/useDefineForClassFields.html
- TypeScript 3.7 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-7.html
- TypeScript 4.0 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-0.html
- TypeScript 4.3 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-3.html
- TypeScript 4.9 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html
- ECMAScript 언어 명세: https://tc39.es/ecma262/
- Java Language Specification SE 21, 12.5 Creation of New Class Instances: https://docs.oracle.com/javase/specs/jls/se21/html/jls-12.html
