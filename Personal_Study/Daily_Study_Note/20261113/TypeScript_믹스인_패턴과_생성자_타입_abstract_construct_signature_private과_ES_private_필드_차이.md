Notion 원본: https://www.notion.so/4185a06fd6d38172a099d9ce13fef8f4

# TypeScript 믹스인 패턴과 생성자 타입: abstract construct signature, private 과 #private 의 타입·런타임 차이

> 2026-11-13 신규 주제 · 확장 대상: TypeScript(클래스와 제네릭), JAVA(상속과 인터페이스)

## 학습 목표

- 생성자 타입과 `InstanceType` 으로 클래스를 값으로 받아 확장하는 믹스인 함수를 작성한다
- 제약이 있는 믹스인과 추상 믹스인을 `abstract new` 시그니처로 구현한다
- `private` 수식어와 `#private` 필드의 타입 호환성, 런타임 가시성, 다운레벨 출력을 구분한다
- 선언 파일 생성에서 믹스인이 받는 제약(TS4094)을 피하는 구조를 고른다

## 1. 단일 상속 언어에서 믹스인이 필요한 이유

Java 와 마찬가지로 JavaScript 클래스는 `extends` 뒤에 하나의 부모만 둘 수 있다. Java 는 인터페이스의 default 메서드로 구현 재사용을 일부 허용하지만 인터페이스는 인스턴스 상태를 가질 수 없고, TypeScript 의 `interface` 는 런타임에 존재하지 않으므로 구현을 담을 수 없다. "생성 시각을 기록한다", "활성 상태를 가진다" 같은 횡단 기능을 상태와 함께 여러 클래스에 붙이려면 다른 수단이 필요하다. JavaScript 에서는 클래스가 일급 값이라는 점을 이용한다. 클래스를 인자로 받아 그것을 상속한 새 클래스를 반환하는 함수를 만들면, 함수 호출을 중첩하는 것으로 상속 사슬을 조립할 수 있다. 이것이 서브클래스 팩토리 방식의 믹스인이며 TypeScript 2.2 부터 타입 시스템이 정식으로 지원한다.

타입 관점에서 이 패턴이 요구하는 것은 두 가지다. 인자로 들어온 "임의의 클래스"를 표현할 타입, 그리고 반환된 클래스가 원래 클래스의 멤버와 추가된 멤버를 모두 가진다는 사실의 추론이다. 전자는 생성자 타입으로, 후자는 제네릭 타입 매개변수를 `extends` 절에 그대로 쓰는 것으로 해결한다.

## 2. 생성자 타입과 기본 믹스인

`new (...args: any[]) => T` 는 "new 로 호출하면 T 를 만드는 값"의 타입, 즉 construct signature 다. 클래스 선언은 인스턴스 타입과 생성자 값 두 가지를 동시에 만들고, `typeof Entity` 가 이 생성자 쪽 타입이다.

```ts
type Constructor<T = {}> = new (...args: any[]) => T;

function Timestamped<TBase extends Constructor>(Base: TBase) {
  return class Timestamped extends Base {
    createdAt = new Date(0);
    touch(now: Date): this {
      this.createdAt = now;
      return this;
    }
  };
}

class Entity {
  constructor(public id: string) {}
}
```

타입 매개변수를 `Constructor` 자체가 아니라 `TBase extends Constructor` 로 받는 것이 중요하다. 함수의 반환 타입은 "새 멤버를 가진 생성자"와 `TBase` 의 교차로 추론되므로, 원래 클래스의 생성자 인자와 정적 멤버, 인스턴스 멤버가 그대로 보존된다. 매개변수를 `Base: Constructor` 로 선언했다면 반환된 클래스에서 `id` 가 보이지 않는다. 또 한 가지 규칙으로, 믹스인 클래스의 생성자 매개변수는 `any[]` 타입의 rest 하나여야 한다. 믹스인은 부모가 어떤 인자를 받는지 알 수 없으므로 받은 인자를 그대로 `super` 에 넘기는 형태만 허용되고, 이를 어기면 TS2545 오류가 난다. 믹스인이 자체 초기화 인자를 받아야 한다면 생성자가 아니라 별도 메서드나 팩토리 함수의 인자로 받는 식으로 설계한다.

## 3. 제약이 있는 믹스인과 조합

모든 믹스인이 아무 클래스에나 붙을 수 있는 것은 아니다. 베이스가 특정 멤버를 가져야 한다면 `Constructor` 의 타입 인자로 요구 사항을 적는다. 믹스인 본문에서는 그 멤버를 타입 안전하게 사용할 수 있고, 조건을 만족하지 않는 클래스에 적용하면 호출 지점에서 거부된다.

```ts
function Activatable<TBase extends Constructor<{ id: string }>>(Base: TBase) {
  return class Activatable extends Base {
    #active = false;
    activate(): this {
      this.#active = true;
      return this;
    }
    get isActive(): boolean {
      return this.#active;
    }
    describe(): string {
      return `${this.id}:${this.#active}`;
    }
  };
}

const User = Activatable(Timestamped(Entity));
type User = InstanceType<typeof User>;

const u: User = new User("u-1");
u.activate().touch(new Date(1000));
console.log(u.describe(), u.isActive, u.createdAt.getTime()); // u-1:true true 1000

class Plain {}
// @ts-expect-error id 가 없는 베이스에는 적용할 수 없다
Activatable(Plain);
```

`const User` 는 값이고 인스턴스 타입 이름을 자동으로 만들어 주지 않는다. 같은 이름의 타입 별칭을 `InstanceType<typeof User>` 로 선언해 두면 일반 클래스처럼 `User` 를 값과 타입 양쪽으로 쓸 수 있다. 메서드 반환 타입을 `this` 로 선언한 점도 눈여겨볼 만하다. `this` 타입은 호출 시점의 실제 수신자 타입으로 해석되므로 `u.activate()` 의 결과가 `Activatable` 까지만 아는 타입이 아니라 `User` 전체로 유지되고, 그래서 뒤이어 `touch` 를 연결할 수 있다. 반환 타입을 클래스 이름으로 적었다면 체이닝 도중에 다른 믹스인의 멤버가 사라진다. 적용 순서는 상속 순서와 같다. `Activatable(Timestamped(Entity))` 에서 프로토타입 체인은 Activatable, Timestamped, Entity 순이므로 같은 이름의 메서드가 있으면 바깥쪽 믹스인이 안쪽을 가린다.

## 4. 추상 클래스와 abstract construct signature

`Constructor` 타입에는 추상 클래스를 넘길 수 없다. 추상 클래스는 `new` 로 호출할 수 없으므로 일반 construct signature 에 대입되지 않기 때문이다. TypeScript 4.2 는 이를 위해 `abstract new` 시그니처를 추가했다. 이 타입은 "직접 인스턴스화할 수는 없지만 상속할 수는 있는 생성자"를 뜻하고, 일반 클래스도 여기에 대입할 수 있다.

```ts
type AbstractConstructor<T = {}> = abstract new (...args: any[]) => T;

function Serializable<TBase extends AbstractConstructor>(Base: TBase) {
  abstract class Serializable extends Base {
    abstract toJSON(): object;
    serialize(): string {
      return JSON.stringify(this.toJSON());
    }
  }
  return Serializable;
}

abstract class Shape {
  abstract area(): number;
}

class Circle extends Serializable(Shape) {
  constructor(private r: number) {
    super();
  }
  area(): number {
    return Math.PI * this.r ** 2;
  }
  toJSON(): object {
    return { kind: "circle", r: this.r };
  }
}

console.log(new Circle(2).serialize()); // {"kind":"circle","r":2}
// @ts-expect-error 추상 클래스는 인스턴스화할 수 없다
new (Serializable(Shape))();
```

믹스인이 반환하는 클래스 자체를 `abstract` 로 선언하면 믹스인이 하위 클래스에 구현 의무를 부과할 수 있다. `Circle` 은 `Shape` 의 `area` 와 믹스인의 `toJSON` 을 모두 구현해야 컴파일되며, 템플릿 메서드 패턴을 믹스인으로 배포하는 형태가 된다. Java 의 추상 클래스 계층을 TypeScript 로 옮길 때 다중 상속이 필요했던 부분을 이 방식으로 풀 수 있다.

## 5. private 과 #private 의 타입 수준 차이

믹스인은 여러 출처의 멤버를 한 인스턴스에 합치므로 이름 충돌과 캡슐화가 일반 상속보다 민감하다. TypeScript 에는 숨김 수단이 두 가지 있고 성질이 다르다. `private` 수식어는 TypeScript 의 컴파일 시점 검사이고, `#name` 은 ECMAScript 2022 표준의 private 필드다.

타입 호환성에서 `private` 멤버는 구조적 타이핑의 예외를 만든다. 두 클래스가 같은 이름과 타입의 private 멤버를 가지더라도 그 멤버가 같은 선언에서 온 것이 아니면 서로 대입할 수 없다.

```ts
class A { private secret = 1; }
class B { private secret = 1; }
// @ts-expect-error 서로 다른 선언의 private 멤버는 호환되지 않는다
const a: A = new B();
```

이 규칙 때문에 private 또는 protected 멤버를 가진 클래스는 사실상 명목적으로 비교된다. 테스트에서 객체 리터럴로 가짜 구현을 만들어 넣으려 할 때 대입이 거부되는 원인이 대개 이것이다. `#private` 필드도 같은 효과를 내는데, 선언 파일에 필드 이름이 드러나지 않을 뿐 "private 이름을 가진 클래스"라는 표식이 남아 다른 클래스의 인스턴스와 구분된다. 차이는 이름 충돌에서 나타난다. `private secret` 을 가진 믹스인 두 개를 한 클래스에 겹치면, 필드 타입이 서로 달라도 TypeScript 5.6.3 은 오류를 내지 않고 런타임에는 같은 프로퍼티 키를 공유하므로 바깥쪽 믹스인의 초기값이 안쪽 값을 조용히 덮어쓴다. `#secret` 은 선언한 클래스 본문마다 별개의 이름이므로 믹스인 여러 개가 같은 이름을 써도 충돌하지 않는다. 재사용 단위로 배포하는 믹스인의 내부 상태에는 `#private` 이 더 안전한 기본값이다.

## 6. 런타임 가시성과 다운레벨 출력

`private` 은 출력 JavaScript 에서 지워진다. 필드는 평범한 프로퍼티로 남으므로 `as any` 나 대괄호 접근으로 읽을 수 있고, 열거와 직렬화에도 그대로 노출된다. `#private` 은 런타임에서도 접근이 차단되며 프로퍼티 열거에 나타나지 않는다. 아래는 실제 실행 결과다.

```ts
class Soft { private token = "soft"; }
class Hard {
  #token = "hard";
  has(o: object) { return #token in o; }
}

console.log((new Soft() as any).token, Object.keys(new Soft()), Object.keys(new Hard()));
// soft [ 'token' ] []
console.log(new Hard().has(new Hard()), new Hard().has({}));
// true false
console.log(JSON.stringify(new Soft()), JSON.stringify(new Hard()));
// {"token":"soft"} {}
```

`JSON.stringify` 결과가 다르다는 점은 실무에서 사고로 이어지기 쉽다. 엔티티를 그대로 응답으로 직렬화하는 코드에서 `private password` 는 응답 본문에 포함된다. 반대로 `#private` 으로 바꾸면 직렬화에서 빠지므로, 영속화나 캐시 저장에 `JSON.stringify` 를 쓰던 클래스는 `toJSON` 을 명시적으로 구현해야 한다. `#token in o` 는 TypeScript 4.5 부터 지원되는 브랜드 검사로, 객체가 해당 클래스의 생성자를 거쳐 만들어졌는지를 예외 없이 판별하고 타입도 좁혀 준다. `instanceof` 와 달리 프로토타입 조작에 속지 않는다.

출력 대상에 따른 비용도 다르다. `--target es2022` 이상에서는 `#token` 이 그대로 출력된다. `--target es2015` 에서 `class Hard { #token = "hard"; }` 를 컴파일하면 다음 코드가 나온다.

```js
var _Hard_token;
class Hard {
    constructor() {
        _Hard_token.set(this, "hard");
    }
}
_Hard_token = new WeakMap();
```

필드 하나당 모듈 수준 WeakMap 하나가 생기고 모든 읽기와 쓰기가 WeakMap 조회로 바뀐다. 인스턴스를 대량으로 만들고 필드 접근이 빈번한 클래스에서는 네이티브 private 필드보다 느리므로, `#private` 을 적극적으로 쓰려면 target 을 ES2022 이상으로 두는 것이 전제다. `--target es5` 에서는 TS18028 오류로 컴파일 자체가 거부된다. 또 `Proxy` 로 감싼 객체에서 `#private` 에 접근하는 메서드를 호출하면 수신자가 프록시이므로 TypeError 가 발생한다. 프록시 기반 반응성 라이브러리가 관리하는 객체에는 이 점을 고려해야 한다.

## 7. 선언 파일 생성에서의 제약

라이브러리로 배포하거나 프로젝트 참조를 쓰는 모노레포에서는 `declaration: true` 가 켜져 있고, 여기서 믹스인은 추가 제약을 받는다. 믹스인 함수가 반환하는 클래스는 익명 클래스 타입이라 `.d.ts` 에 객체 타입 리터럴로 풀어 써야 하는데, 객체 타입 리터럴에는 private 이나 protected 수식어를 표현할 방법이 없다. 그래서 내보낸 믹스인의 클래스 표현식에 `private` 멤버가 있으면 TS4094 오류가 난다.

```ts
export function WithPrivate<TBase extends Constructor>(Base: TBase) {
  return class extends Base {
    private secret = 1; // error TS4094
    reveal() { return this.secret; }
  };
}
```

같은 코드를 `#secret` 으로 바꾸면 TypeScript 5.6.3 에서 선언 생성이 통과하고, 출력된 `.d.ts` 에는 `"__#1@#secret": number` 라는 합성 프로퍼티가 포함된 타입이 기록된다. 동작은 하지만 내부 필드의 존재와 타입이 공개 선언에 드러나는 셈이다. 공개 API 의 선언을 깔끔하게 유지하려면 믹스인이 추가하는 공개 멤버를 인터페이스로 먼저 선언하고, 함수의 반환 타입을 `TBase & Constructor<공개인터페이스>` 로 명시하는 방법을 쓴다. 이렇게 하면 구현 클래스 안의 private 멤버가 반환 타입에 나타나지 않으므로 오류도 사라지고 선언도 안정된다. 반환 타입을 명시하면 TypeScript 5.5 의 `isolatedDeclarations` 옵션 요구 사항도 함께 충족된다.

## 8. 생성자 인자를 보존하는 팩토리와 선택 기준

믹스인의 생성자 타입은 인자를 `any[]` 로 뭉개지만, 클래스를 받아 감싸는 함수 일반이 그래야 하는 것은 아니다. 상속하지 않고 생성만 대행하는 경우에는 인자 튜플을 타입 매개변수로 받아 그대로 보존할 수 있다.

```ts
type Ctor<A extends unknown[], T> = new (...args: A) => T;

function Counted<A extends unknown[], T extends object>(Base: Ctor<A, T>) {
  let count = 0;
  const factory = (...args: A): T => {
    count++;
    return new Base(...args);
  };
  return Object.assign(factory, { count: () => count });
}

const makeEntity = Counted(Entity);
makeEntity("e-1");
// makeEntity(1) 은 string 자리에 number 를 넘겼으므로 컴파일 오류
```

믹스인, 합성(필드로 협력 객체를 보유), 데코레이터 사이의 선택은 "추가 기능이 인스턴스의 공개 타입을 바꾸는가"로 판단한다. 공개 메서드와 상태를 인스턴스에 더해야 하고 `this` 를 통한 체이닝이나 오버라이드가 필요하면 믹스인이 맞다. 기능이 독립적이고 교체 가능해야 하면 합성이 테스트와 변경에 유리하다. TC39 표준 데코레이터는 클래스 타입을 바꾸지 못하므로 멤버를 추가하는 용도로는 쓸 수 없다. 믹스인의 비용은 상속 깊이다. 믹스인 하나마다 프로토타입 체인이 한 단계 늘고, 중첩 호출이 서너 단계를 넘으면 메서드가 어느 층에서 왔는지 추적하기 어려워진다. 적용 개수를 두세 개 이내로 유지하고 이름 있는 클래스 표현식을 반환해 스택 트레이스에 층 이름이 남도록 하는 것이 유지보수에 도움이 된다.

이 문서의 예제는 TypeScript 5.6.3 에서 `tsc --strict --target es2022` 로 컴파일한 뒤 Node.js 로 실행해 주석에 적은 출력을 확인했고, 선언 생성 관련 내용은 `--declaration --emitDeclarationOnly` 로, 다운레벨 출력은 `--target es2015` 와 `--target es5` 로 각각 확인했다.

## 참고

- TypeScript Handbook, Mixins: https://www.typescriptlang.org/docs/handbook/mixins.html
- TypeScript 2.2 릴리스 노트, Support for Mix-in classes: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-2.html
- TypeScript 4.2 릴리스 노트, abstract Construct Signatures: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-2.html
- TypeScript 3.8 릴리스 노트, ECMAScript Private Fields: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-8.html
- TypeScript 4.5 릴리스 노트, Private Field Presence Checks: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-5.html
- MDN, Private properties: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Classes/Private_properties
- TypeScript Handbook, Classes: https://www.typescriptlang.org/docs/handbook/2/classes.html
