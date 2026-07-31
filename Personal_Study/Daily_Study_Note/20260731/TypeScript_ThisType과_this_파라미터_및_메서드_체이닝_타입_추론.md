Notion 원본: https://www.notion.so/3ae5a06fd6d381b1b4fadb618395ff1f

# TypeScript ThisType과 this 파라미터 및 메서드 체이닝 타입 추론

> 2026-07-31 신규 주제 · 확장 대상: Javascript

## 학습 목표

- this 파라미터로 함수 호출 컨텍스트를 컴파일 타임에 강제하는 방법을 익힌다
- 다형적 this 타입으로 상속 체인에서 메서드 체이닝을 안전하게 유지한다
- ThisType<T> 유틸리티로 객체 리터럴 메서드 내부의 this 를 문맥적으로 지정한다
- noImplicitThis·strictBindCallApply 옵션이 잡아내는 오류 범주를 구분한다

## 1. this 는 매개변수다 — 가짜 첫 파라미터

JavaScript 의 `this` 는 함수가 **어떻게 호출되는가**로 결정되는 동적 바인딩이다. 이 동적성은 런타임 버그의 온상이다. 객체 메서드를 콜백으로 떼어내 넘기면 `this` 가 엉뚚한 값이 된다. TypeScript 는 이를 잡기 위해 함수 시그니처의 **첫 번째 자리에 `this` 파라미터**를 선언하게 한다. 이 `this` 는 실제 인자가 아니라 "이 함수는 이런 this 컨텍스트에서만 호출돼야 한다"는 타입 계약이며, 컴파일 후 코드에서는 완전히 사라진다.

```typescript
interface Button {
  label: string;
  onClick(this: Button): void;   // this 는 반드시 Button 이어야 함
}

const btn: Button = {
  label: 'Save',
  onClick(this: Button) {
    console.log(this.label);     // OK: this 는 Button
  },
};

const fn = btn.onClick;
fn();
// ✗ error TS2684: The 'this' context of type 'void' is not
//   assignable to method's 'this' of type 'Button'.
```

메서드를 분리해 호출하면 `this` 계약이 깨지는 것을 컴파일러가 즉시 잡는다. `noImplicitThis` 옵션은 `this` 타입을 추론할 수 없어 암묵적으로 `any` 가 되는 경우를 오류로 만든다. strict 모드에 포함되어 있어, 일반 함수 안에서 무심코 `this` 를 쓰면 경고를 받는다.

## 2. 다형적 this 타입 — 상속 체인의 체이닝 보존

메서드 체이닝(fluent API)에서 각 메서드가 자기 자신을 반환할 때, 반환 타입을 클래스명으로 고정하면 상속에서 문제가 생긴다. 부모 타입을 반환하는 순간 자식의 메서드에 접근할 수 없어진다. 해결책이 **다형적 `this` 타입**이다. 반환 타입을 `this` 로 두면 "실제 호출된 인스턴스의 타입"으로 좁혀진다.

```typescript
class QueryBuilder {
  protected parts: string[] = [];

  where(cond: string): this {          // 클래스명이 아니라 this 반환
    this.parts.push(`WHERE ${cond}`);
    return this;
  }
  build(): string {
    return this.parts.join(' ');
  }
}

class PagedQueryBuilder extends QueryBuilder {
  limit(n: number): this {
    this.parts.push(`LIMIT ${n}`);
    return this;
  }
}

const q = new PagedQueryBuilder()
  .where('id = 1')   // this 는 PagedQueryBuilder 로 유지
  .limit(10)         // ✓ 부모 메서드 후에도 자식 메서드 접근 가능
  .build();
```

만약 `where` 가 `QueryBuilder` 를 반환했다면 `.where(...).limit(...)` 에서 `limit` 을 찾지 못해 오류가 났을 것이다. `this` 타입은 이 상속 다형성을 자동으로 흡수한다. `this` 는 타입 위치에서 "현재 인스턴스 타입"을 가리키는 특별한 타입이며, 타입 가드에서도 `this is Derived` 형태의 사용자 정의 가드로 활용된다.

## 3. ThisType<T> — 객체 리터럴 메서드의 this 주입

Vue 2 옵션 API 나 믹스인 스타일 객체에서, 메서드 안의 `this` 가 data·methods·computed 를 모두 합친 타입이길 원한다. 그런데 객체 리터럴은 자기 자신을 정의하는 중이라 메서드 안에서 `this` 가 무엇인지 순환적으로 알기 어렵다. `ThisType<T>` 마커 인터페이스가 이를 해결한다. 컨텍스트 타입에 `ThisType<T>` 가 포함되면, **그 객체 리터럴 메서드 내부의 `this` 가 `T` 로 지정**된다. `ThisType` 자체는 아무 멤버도 없는 순수 마커이며 런타임 산출물도 없다.

```typescript
type ObjectDescriptor<D, M> = {
  data: D;
  methods: M & ThisType<D & M>;   // methods 내부 this = D & M
};

function makeObject<D, M>(desc: ObjectDescriptor<D, M>): D & M {
  return { ...desc.data, ...desc.methods } as D & M;
}

const counter = makeObject({
  data: { count: 0 },
  methods: {
    inc() {
      this.count++;          // ✓ this 에 data 의 count 가 보인다
    },
    incBy(n: number) {
      this.count += n;       // ✓ 같은 methods 의 다른 메서드도 this 로 접근
    },
  },
});
counter.inc();
counter.incBy(5);
```

`ThisType<D & M>` 이 없으면 `inc` 안의 `this.count` 는 오류가 난다(`methods` 객체에는 `count` 가 없으므로). 이 유틸리티는 `lib.es5.d.ts` 에 선언되어 있고, `noImplicitThis` 가 켜져 있을 때만 문맥적 `this` 주입이 활성화된다. 프레임워크 없이도 설정 객체·상태 머신 정의 등에서 강력하게 쓰인다.

## 4. bind·call·apply 의 타입 안전성

`Function.prototype.bind/call/apply` 는 `this` 와 인자를 바꿔 호출한다. 과거 TypeScript 는 이들을 느슨한 시그니처(`any` 기반)로 다뤄 타입 검사를 통과시켰다. `strictBindCallApply` 옵션(strict 에 포함)은 이 세 메서드를 **정확한 시그니처로 검사**한다.

```typescript
function greet(this: { name: string }, greeting: string): string {
  return `${greeting}, ${this.name}`;
}

const bound = greet.bind({ name: 'Kim' });
bound('Hello');                      // ✓ 'Hello, Kim'

greet.call({ name: 'Lee' }, 'Hi');   // ✓ this 와 인자 모두 검사됨
greet.call({ name: 'Lee' }, 42);
// ✗ error: Argument of type 'number' is not assignable
//   to parameter of type 'string'.
greet.call({ id: 1 }, 'Hi');
// ✗ error: this 컨텍스트 타입 불일치 ({name} 없음)
```

`strictBindCallApply` 는 인자 개수·타입뿐 아니라 `this` 컨텍스트 타입까지 검증한다. 이 옵션이 꺼져 있으면 위 오류들이 조용히 통과해 런타임에서야 터진다. strict 계열 옵션을 켜는 것이 이런 잠재 버그를 컴파일 단계로 끔어오는 이유다.

## 5. 화살표 함수와 this — 상호작용의 함정

화살표 함수는 자체 `this` 바인딩이 없고 **렉시컬(정의 위치) this** 를 캡처한다. 그래서 콜백에서 `this` 유실을 막는 흔한 관용구다. 하지만 이는 `this` 파라미터 계약과 충돌할 수 있다. 화살표 함수에는 `this` 파라미터를 선언할 수 없다. 정의 시점의 바깥 `this` 를 고정하기 때문이다.

```typescript
class Timer {
  seconds = 0;

  // 화살표 필드: this 가 인스턴스로 고정되어 콜백 전달에 안전
  tick = () => {
    this.seconds++;                  // this 는 항상 Timer
  };

  // 일반 메서드: setInterval 에 그대로 넘기면 this 유실 위험
  tickMethod() {
    this.seconds++;
  }

  start() {
    setInterval(this.tick, 1000);       // ✓ 안전
    setInterval(this.tickMethod, 1000); // ✗ 런타임에서 this 가 Timer 가 아님
  }
}
```

trade-off 가 있다. 화살표 필드는 **인스턴스마다 함수를 새로 만든다**(프로토타입 공유 아님). 인스턴스가 수만 개면 메모리·생성 비용이 는다. 또한 프로토타입에 없으므로 상속·오버라이드·spy 기반 테스트에서 다루기 까다롭다. 그래서 "콜백으로 자주 떼어내는 소수 핸들러"만 화살표 필드로 두고, 일반 로직은 프로토타입 메서드로 두는 절충이 실무 관행이다.

## 6. 종합 — this 타이핑의 설계 판단

`this` 관련 기능을 언제 무엇으로 쓰는지 정리하면 판단이 선명해진다. **호출 컨텍스트를 강제**해야 하면 `this` 파라미터로 계약을 명시한다(이벤트 핸들러, 콜백 API). **상속 체인에서 메서드 체이닝을 보존**해야 하면 반환 타입을 다형적 `this` 로 둔다(빌더, fluent DSL). **객체 리터럴 메서드 내부에서 형제 멤버에 this 로 접근**해야 하면 컨텍스트 타입에 `ThisType<T>` 를 얹는다(옵션 객체, 상태 머신 정의). 그리고 이 모든 검사는 `noImplicitThis` 와 `strictBindCallApply` 가 켜져 있어야 실효를 갖는다.

핵심 통찰은 TypeScript 가 JavaScript 의 가장 미끄러운 부분인 `this` 동적 바인딩을 **타입 계약으로 정적화**한다는 것이다. 런타임에서만 드러나던 "this 유실" 버그를 컴파일 타임으로 앞당기고, 그 대가로 약간의 시그니처 장식을 요구한다. 이 계약을 명시적으로 쓸수록 리팩터링과 콜백 전달이 안전해진다.

## 7. 제네릭 빌더에서 this 와 타입 누적

빌더 패턴을 제네릭과 결합하면 "지금까지 설정된 필드"를 타입 수준에서 누적할 수 있다. 각 메서드가 `this` 대신 자신을 확장한 새 타입을 반환하도록 설계하면, 필수 필드 누락을 컴파일 타임에 잡는 강력한 DSL 이 된다.

```typescript
class FormBuilder<T = {}> {
  private data: Record<string, unknown> = {};

  field<K extends string, V>(
    key: K,
    value: V,
  ): FormBuilder<T & Record<K, V>> {   // 반환 타입에 K:V 누적
    this.data[key] = value;
    return this as unknown as FormBuilder<T & Record<K, V>>;
  }

  build(this: FormBuilder<T>): T {
    return this.data as T;
  }
}

const form = new FormBuilder()
  .field('name', 'Kim')     // FormBuilder<{ name: string }>
  .field('age', 30)         // FormBuilder<{ name: string; age: number }>
  .build();
// form 의 타입: { name: string; age: number }
```

여기서 `this` 파라미터(`build(this: FormBuilder<T>)`)와 다형적 반환이 협력한다. 각 `field` 호출이 누적 타입을 키우고, `build` 는 그 누적된 `T` 를 정확히 반환한다. 순수 `this` 반환 체이닝과 달리, 이 방식은 **호출마다 타입이 진화**하므로 "필수 필드가 다 채워졌을 때만 build 를 허용"하는 제약도 조건부 타입으로 얹을 수 있다. `this` 타입과 제네릭 누적은 이렇게 결합될 때 가장 표현력이 높다.

## 8. React·이벤트 핸들러에서의 실무 주의

브라우저 DOM 이벤트 핸들러는 `this` 가 이벤트 대상 엘리먼트로 바인딩된다. TypeScript 의 `lib.dom.d.ts` 는 이를 반영해 `addEventListener` 콜백의 `this` 를 대상 타입으로 지정해 둔다. 반면 React 는 합성 이벤트를 쓰고 클래스 컴포넌트에서 핸들러의 `this` 유실이 고질적 문제였다. 그래서 화살표 필드나 생성자 바인딩이 관행이었고, 함수형 컴포넌트로 옮겨오면서 `this` 자체가 사라져 이 문제 범주가 대부분 해소되었다.

```typescript
// DOM: this 가 버튼 엘리먼트로 타입 지정됨
button.addEventListener('click', function (this: HTMLButtonElement, ev) {
  this.disabled = true;    // ✓ this 는 HTMLButtonElement
});

// 화살표로 넘기면 this 계약이 사라지므로 대상 접근은 ev.currentTarget 사용
button.addEventListener('click', (ev) => {
  (ev.currentTarget as HTMLButtonElement).disabled = true;
});
```

정리하면 콜백 API 를 설계할 때 "핸들러 안에서 대상에 this 로 접근하게 할지, 인자로 넘길지"를 먼저 정해야 한다. this 로 넘기면 `this` 파라미터로 계약을 걸고 화살표 함수 사용을 막아야 하며, 인자로 넘기면 화살표 함수를 자유롭게 써도 안전하다. 현대 스타일은 후자(명시적 인자 전달)를 선호한다. `this` 의 동적성에 의존하지 않아 리팩터링과 조합이 쉽기 때문이다.

## 참고

- TypeScript Handbook: More on Functions — Declaring this in a Function
- TypeScript Handbook: Classes — this-based type guards / Polymorphic this types
- TypeScript lib.es5.d.ts — ThisType<T> 선언
- TSConfig Reference: noImplicitThis, strictBindCallApply
