Notion 원본: https://www.notion.so/4185a06fd6d3812cb65bf970d27003a7

# TypeScript 상관 유니온(Correlated Union) 문제와 분배 객체 타입: Indexed Access 기반 핸들러 맵 설계

> 2026-11-13 신규 주제 · 확장 대상: TypeScript(판별 유니온과 Mapped Types)

## 학습 목표

- 판별 유니온 값으로 핸들러 맵을 조회해 호출할 때 컴파일 오류가 나는 원인을 유니온 호출 해석 규칙으로 설명한다
- 분배 객체 타입과 제네릭 인덱스 접근으로 switch 없이 타입 안전한 dispatch 함수를 작성한다
- 분배 형태와 비분배 형태가 호출부에서 만드는 건전성 차이를 `@ts-expect-error` 로 검증한다
- 입력과 반환 타입이 함께 상관되는 핸들러 맵을 설계한다

## 1. 상관 유니온이라는 문제

이벤트 버스, 리듀서, 메시지 컨슈머처럼 "종류에 따라 다른 페이로드를 받아 종류별 핸들러에 넘기는" 코드는 거의 모든 애플리케이션에 존재한다. TypeScript 에서는 이 구조를 판별 유니온과 핸들러 맵으로 표현하는데, 둘을 연결하는 순간 컴파일러가 뜻밖의 오류를 낸다. 이벤트의 `type` 과 `payload` 는 항상 같은 종류에서 온 짝이라는 사실을 사람은 알고 있지만, 타입 검사기는 두 프로퍼티를 각각 독립된 유니온으로 취급하기 때문이다. 이렇게 "서로 다른 위치에 있는 두 유니온이 실제로는 같은 판별값에 묶여 있다"는 관계를 상관 유니온(correlated union)이라 부르며, TypeScript 이슈 #30581 에서 오랫동안 논의된 주제다.

아래 코드가 문제의 최소 재현이다. `EventMap` 이 단일 진실 원천이고, 핸들러 맵과 이벤트 유니온을 모두 여기에서 유도한다.

```ts
type EventMap = {
  click: { x: number; y: number };
  keydown: { key: string };
  resize: { width: number; height: number };
};

type HandlerMap = { [K in keyof EventMap]: (payload: EventMap[K]) => string };

const handlers: HandlerMap = {
  click: (p) => `${p.x},${p.y}`,
  keydown: (p) => p.key,
  resize: (p) => `${p.width}x${p.height}`,
};

type AppEvent = { [K in keyof EventMap]: { type: K; payload: EventMap[K] } }[keyof EventMap];

function dispatchBad(e: AppEvent): string {
  // @ts-expect-error payload 가 세 페이로드의 교차 타입을 요구받는다
  return handlers[e.type](e.payload);
}
```

`AppEvent` 는 매핑 타입을 만든 뒤 곧바로 `[keyof EventMap]` 으로 인덱싱해서 세 객체 타입의 유니온을 얻는 관용구로 만들었다. 손으로 유니온을 나열하지 않아도 `EventMap` 에 키를 추가하면 이벤트 유니온과 핸들러 맵이 함께 늘어난다는 점이 이 구성의 장점이다.

## 2. 실패 원인: 유니온 함수 호출은 매개변수를 교차시킨다

`dispatchBad` 안에서 `e.type` 의 타입은 `"click" | "keydown" | "resize"` 이고, 따라서 `handlers[e.type]` 의 타입은 세 함수 타입의 유니온이 된다. TypeScript 는 3.3 버전부터 함수 유니온의 호출을 허용하지만, 그때 인자는 유니온의 모든 구성원이 받아들일 수 있어야 한다는 규칙을 적용한다. 어떤 함수가 실제로 선택될지 모르므로 세 매개변수 타입의 교차 타입, 즉 `{ x; y } & { key } & { width; height }` 를 요구하는 것이다. 반면 `e.payload` 는 세 페이로드의 유니온이므로 교차 타입에 대입될 수 없고, 호출은 거부된다.

검사기 입장에서 이 판단은 틀리지 않았다. `handlers[a.type](b.payload)` 처럼 서로 다른 두 이벤트의 프로퍼티를 섞어 쓴 코드와 `handlers[e.type](e.payload)` 는 타입 수준에서 구분되지 않는다. 식별자가 같다는 사실로 두 접근을 묶으려면 표현식 단위의 동일성 추적이 필요한데, TypeScript 의 제어 흐름 분석은 좁히기(narrowing)가 일어난 참조에 대해서만 그런 추적을 한다. 그래서 `as any` 나 `as never` 로 눌러 버리는 코드가 실무에 많이 남아 있고, 그 순간 `EventMap` 을 바꿔도 컴파일러가 아무것도 알려 주지 않는 구멍이 생긴다.

## 3. 첫 번째 우회: switch 로 좁히기와 그 비용

제어 흐름 분석을 이용하면 단언 없이 통과시킬 수 있다. 판별 프로퍼티로 분기하면 각 case 안에서 `e` 가 단일 구성원으로 좁혀지므로 `e.payload` 도 해당 페이로드로 확정된다.

```ts
function dispatchSwitch(e: AppEvent): string {
  switch (e.type) {
    case "click":
      return handlers.click(e.payload);
    case "keydown":
      return handlers.keydown(e.payload);
    case "resize":
      return handlers.resize(e.payload);
  }
}
```

이 방식은 완전히 건전하고, 반환 타입이 `string` 인데 default 분기가 없으므로 `EventMap` 에 키를 추가하면 "모든 경로가 값을 반환하지 않는다"는 오류로 누락을 잡아 준다. 문제는 맵을 만든 이유가 사라진다는 점이다. 핸들러 맵은 분기를 데이터로 바꾸려는 구조인데, dispatch 함수가 다시 종류 수만큼의 case 를 가지게 된다. 종류가 수십 개인 메시지 프로토콜에서는 동일한 모양의 줄이 수십 번 반복되고, 그 반복은 타입 검사를 통과시키기 위한 것일 뿐 런타임 동작에는 아무 기여도 하지 않는다. 번들 크기와 가독성 양쪽에서 손해다.

## 4. 해결: 분배 객체 타입과 제네릭 인덱스 접근

TypeScript 4.6 에 포함된 변경(PR #47109)은 이 문제를 언어 기능 추가 없이 풀 수 있는 패턴을 열어 주었다. 핵심은 이벤트 타입을 제네릭 매개변수 `K` 에 대한 "분배 객체 타입"으로 다시 쓰고, 함수도 `K` 에 대해 제네릭으로 만드는 것이다.

```ts
type EventOf<K extends keyof EventMap = keyof EventMap> = {
  [P in K]: { type: P; payload: EventMap[P] };
}[K];

function dispatch<K extends keyof EventMap>(e: EventOf<K>): string {
  return handlers[e.type](e.payload);
}

function emit<K extends keyof EventMap>(type: K, payload: EventMap[K]): string {
  return handlers[type](payload);
}

const r1 = dispatch({ type: "click", payload: { x: 1, y: 2 } });
const r2 = emit("keydown", { key: "Enter" });
// @ts-expect-error click 의 payload 에 key 는 없다
dispatch({ type: "click", payload: { key: "Enter" } });
```

`EventOf` 는 `K` 의 각 구성원 `P` 마다 객체 타입을 만든 다음 `[K]` 로 인덱싱한다. `K` 가 유니온이면 결과도 유니온으로 분배되고, 기본값 `keyof EventMap` 을 넣으면 앞의 `AppEvent` 와 같은 타입이 된다. 함수 본문에서는 `K` 가 아직 구체화되지 않은 타입 매개변수이므로 `e.type` 은 `K`, `e.payload` 는 `EventMap[K]` 로 남는다. 그리고 `handlers[e.type]` 은 `HandlerMap[K]` 인데, 4.6 이후의 검사기는 "매핑 타입을 그 매핑의 키 타입 매개변수로 인덱싱한 결과"를 템플릿에 `K` 를 대입한 형태, 즉 `(payload: EventMap[K]) => string` 으로 해석한다. 매개변수 타입과 인자 타입이 둘 다 `EventMap[K]` 로 동일하게 표현되므로 호출이 통과한다. 유니온으로 풀어서 비교하는 대신 `K` 라는 하나의 기호를 양쪽에 그대로 흘려보내는 것이 이 패턴의 원리다.

## 5. 패턴이 성립하기 위한 세 가지 조건

이 패턴은 모양이 조금만 어긋나도 2절의 오류로 되돌아가므로 조건을 정확히 기억해 둘 필요가 있다. 첫째, 페이로드와 핸들러 양쪽이 같은 "타입 맵"(여기서는 `EventMap`)에 대한 인덱스 접근으로 표현되어야 한다. 핸들러 맵을 `Record<string, Function>` 이나 손으로 쓴 개별 시그니처로 선언하면 `HandlerMap[K]` 를 `EventMap[K]` 에 대한 함수로 정규화할 근거가 사라진다. 둘째, 핸들러 맵은 `{ [K in keyof EventMap]: ... EventMap[K] ... }` 형태의 매핑 타입이어야 한다. 검사기가 인덱스 접근을 단순화할 때 매핑 타입의 템플릿에 키를 대입하는 경로를 타기 때문이다. 셋째, 함수는 맵의 키로 제약된 타입 매개변수를 가져야 하고, 본문에서 그 매개변수가 유니온으로 풀리지 않은 채 유지되어야 한다. 본문 안에서 `const t: keyof EventMap = e.type` 처럼 넓은 타입으로 옮겨 담으면 그 시점부터 상관관계가 끊긴다.

호출부에서는 제네릭이 부담이 되지 않는다. 리터럴 객체를 넘기면 `K` 가 `"click"` 으로 추론되고, 이미 `AppEvent` 로 넓혀진 값을 넘기면 `K` 가 `keyof EventMap` 전체로 추론되어 그대로 통과한다. 배열에 담긴 이벤트를 순회하며 처리하는 코드도 추가 작업 없이 동작한다.

```ts
declare const anyEvent: AppEvent;
const r3 = dispatch(anyEvent); // K 는 keyof EventMap 으로 추론된다

const queue: AppEvent[] = [
  { type: "click", payload: { x: 1, y: 2 } },
  { type: "resize", payload: { width: 3, height: 4 } },
];
const out = queue.map((e) => dispatch(e));
```

## 6. 반환 타입까지 상관시키기

핸들러의 반환 타입이 종류마다 다르면 결과 타입도 같은 방식으로 맵에서 꺼내면 된다. 결과 타입 맵을 하나 더 두고 핸들러 맵의 템플릿에서 `ResultMap[K]` 를 반환하도록 선언하면, dispatch 함수의 반환 타입을 `ResultMap[K]` 로 적는 것만으로 호출부가 종류별 결과 타입을 받는다.

```ts
type ResultMap = { click: number; keydown: string; resize: boolean };

const computers: { [K in keyof EventMap]: (p: EventMap[K]) => ResultMap[K] } = {
  click: (p) => p.x + p.y,
  keydown: (p) => p.key.toUpperCase(),
  resize: (p) => p.width > p.height,
};

function compute<K extends keyof EventMap>(e: EventOf<K>): ResultMap[K] {
  return computers[e.type](e.payload);
}

const n: number = compute({ type: "click", payload: { x: 1, y: 2 } });
const s: string = compute({ type: "keydown", payload: { key: "a" } });
```

이 구성은 CQRS 의 커맨드 버스나 RPC 클라이언트에서 특히 유용하다. 요청 종류와 응답 타입의 대응표를 타입 맵 두 개로 고정해 두면, 새 커맨드를 추가할 때 수정할 곳이 맵 선언과 핸들러 구현뿐이고 버스 본체는 건드리지 않는다. switch 방식에서는 반환 타입을 종류별로 다르게 주려면 함수 오버로드를 종류 수만큼 써야 하는데, 오버로드 구현부는 선언과의 일치를 검사받지 않으므로 그쪽이 오히려 덜 안전하다.

## 7. 건전성 경계: 분배 형태와 비분배 형태의 차이

제네릭 버전을 처음 쓸 때 흔히 `EventOf` 대신 더 단순한 타입을 선언한다. 본문은 똑같이 통과하지만 호출부의 안전성이 달라진다.

```ts
type LooseEvent<K extends keyof EventMap> = { type: K; payload: EventMap[K] };

function dispatchLoose<K extends keyof EventMap>(e: LooseEvent<K>): string {
  return handlers[e.type](e.payload);
}

// 오류 없이 통과한다: click 핸들러가 keydown 페이로드를 받는다
const mixed = dispatchLoose<"click" | "keydown">({ type: "click", payload: { key: "Enter" } });

// @ts-expect-error 분배 형태는 명시적 유니온 인자에도 짝을 강제한다
dispatch<"click" | "keydown">({ type: "click", payload: { key: "Enter" } });
```

`LooseEvent<"click" | "keydown">` 은 `type` 이 두 리터럴의 유니온이고 `payload` 도 두 페이로드의 유니온인 하나의 객체 타입이다. 짝이 맞지 않는 조합이 이 타입에 속하므로 `mixed` 는 컴파일되고, 실행하면 click 핸들러가 `undefined,undefined` 를 반환한다. 반면 `EventOf<"click" | "keydown">` 은 올바른 짝 두 개의 유니온으로 분배되어 섞인 객체를 거부한다. 타입 인자를 명시하지 않더라도, 이미 넓혀진 변수들로 객체를 조립해 넘기는 코드에서 `K` 가 유니온으로 추론될 수 있으므로 이 차이는 이론적인 문제가 아니다.

같은 이유로 4절의 `emit(type, payload)` 처럼 판별값과 페이로드를 별도 인자로 받는 시그니처는 `emit<"click" | "keydown">("click", { key: "Enter" })` 가 통과한다는 한계를 가진다. 이것은 TypeScript 가 "타입 매개변수가 단일 리터럴로만 구체화된다"는 제약을 표현할 수 없기 때문이며, 검사기가 제네릭 인덱스 접근에 대한 쓰기를 허용하는 대가로 받아들인 알려진 불건전성이다. 공개 API 의 입력은 가능하면 객체 하나로 받고 분배 형태로 타입을 선언하는 쪽이 방어 범위가 넓다.

## 8. 적용 범위와 선택 기준

세 가지 접근을 비교하면 선택 기준이 분명해진다. 타입 단언은 코드가 가장 짧지만 맵과 유니온이 어긋나도 알려 주지 않으므로 선택지에서 제외한다. switch 좁히기는 건전성이 가장 높고 누락 검사까지 얻지만 종류 수에 비례해 코드가 늘어난다. 종류가 서너 개이고 분기마다 처리 방식 자체가 다르다면 이쪽이 읽기 쉽다. 분배 객체 타입 패턴은 dispatch 코드가 종류 수와 무관하게 한 줄로 유지되고, 누락 검사는 핸들러 맵 객체 리터럴이 `HandlerMap` 의 모든 키를 요구하는 것으로 대신 얻는다. 종류가 계속 늘어나는 프로토콜, 플러그인 형태로 핸들러를 등록하는 구조, 요청과 응답 타입이 쌍으로 관리되는 버스에는 이쪽이 맞다.

적용 전에 확인할 것이 두 가지 있다. 하나는 컴파일러 버전으로, 이 동작은 4.6 이상에서만 성립한다. 다른 하나는 런타임 검증과의 경계다. 이 패턴은 컴파일 시점의 짝 맞춤만 보장하므로 네트워크나 메시지 큐에서 들어온 값은 Zod 같은 스키마 검증으로 `AppEvent` 임을 확인한 뒤 dispatch 에 넘겨야 한다. 검증 스키마를 `EventMap` 의 키별로 가진 맵으로 두면 같은 패턴으로 파싱 함수까지 제네릭하게 쓸 수 있다.

## 9. 검증 방법

이 문서의 모든 예제는 하나의 파일로 합쳐 TypeScript 5.6.3 에서 `tsc --strict --noEmit --target es2022` 로 컴파일했고 종료 코드 0 을 확인했다. 실패해야 하는 호출은 `@ts-expect-error` 로 표시했는데, 이 지시문은 다음 줄에 오류가 없으면 그 자체가 오류(TS2578)가 되므로 "거부되어야 할 코드가 실제로 거부된다"는 사실까지 같은 컴파일로 검증된다. 타입 수준의 회귀 테스트가 필요한 프로젝트에서는 이 지시문을 담은 파일을 테스트 디렉터리에 두고 CI 에서 `tsc --noEmit` 을 돌리는 것만으로 별도 도구 없이 건전성 경계를 고정할 수 있다. 7절의 `mixed` 처럼 "통과하지만 위험한" 코드는 지시문으로 잡히지 않으므로, 비분배 형태를 쓰지 않는다는 규칙 자체를 코드 리뷰 항목으로 두는 편이 현실적이다.

## 참고

- TypeScript 4.6 릴리스 노트: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-6.html
- TypeScript PR #47109, Improve checking of indexed access types applied to mapped types: https://github.com/microsoft/TypeScript/pull/47109
- TypeScript Issue #30581, correlated union types 논의: https://github.com/microsoft/TypeScript/issues/30581
- TypeScript 3.3 릴리스 노트, Improved behavior for calling union types: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-3.html
- TypeScript Handbook, Indexed Access Types: https://www.typescriptlang.org/docs/handbook/2/indexed-access-types.html
- TypeScript Handbook, Mapped Types: https://www.typescriptlang.org/docs/handbook/2/mapped-types.html
