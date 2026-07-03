Notion 원본: https://www.notion.so/3925a06fd6d38116b64df8872fdab0f1

# TypeScript satisfies 연산자와 const 타입 파라미터 및 as const 추론 제어

> 2026-07-03 신규 주제 · 확장 대상: TypeScript(Conditional Types·infer 학습됨)

## 학습 목표

- `satisfies`가 "값의 넓은 타입 검증"과 "좁은 리터럴 추론 보존"을 동시에 달성하는 원리를 구분한다
- `as const`, 명시적 타입 애노테이션, `satisfies` 세 방식의 추론 결과 차이를 코드로 재현한다
- 제네릭 함수에서 `const` 타입 파라미터가 인자 추론을 어떻게 좁히는지 설명한다
- 설정 객체·라우팅 테이블·상태 매핑에 세 도구를 조합해 타입 안전 API를 설계한다

## 1. 문제: 애노테이션은 추론을 죽이고, 무애노테이션은 검증을 죽인다

TypeScript에서 설정 객체를 만들 때 두 가지를 동시에 원한다. 하나는 "이 객체가 특정 스키마(넓은 타입)를 만족하는지" 컴파일 타임 검증이고, 다른 하나는 "각 필드의 구체적 리터럴 타입"을 나중에 그대로 쓰는 것이다. 전통적인 두 방식은 각각 하나를 희생한다.

```ts
type RGB = [number, number, number];
type Color = RGB | string;
type Palette = Record<string, Color>;

// 방식 A: 명시적 애노테이션 → 검증은 되지만 추론이 넓어짐
const paletteA: Palette = {
  primary: [255, 0, 0],
  border: "#ccc",
};
// paletteA.primary 의 타입은 Color 로 넓어진다. RGB 로 좁혀 쓰려면 캐스팅 필요
paletteA.primary.map((c) => c); // ❌ Color 에는 map 이 없다(string 가능성)

// 방식 B: 무애노테이션 → 추론은 좁지만 스키마 검증이 없음
const paletteB = {
  primary: [255, 0, 0],
  bordr: "#ccc", // ❌ 오타(border→bordr)를 잡지 못한다
};
```

방식 A는 `Palette`로 애노테이트했기 때문에 오타는 잡지만, 각 값의 타입이 선언 타입(`Color`)으로 강제 확장(widening)되어 `primary`가 `RGB`라는 구체 정보를 잃는다. 방식 B는 구체 정보는 살지만 `Palette` 스키마와 대조되지 않아 `bordr` 오타가 통과한다. 두 요구가 충돌한다.

## 2. satisfies의 의미론

`satisfies`는 TypeScript 4.9에서 도입됐다. `expr satisfies T`는 "`expr`의 타입이 `T`에 할당 가능한지 검사하되, 결과 표현식의 타입은 `T`로 넓히지 않고 `expr`의 추론된 타입을 그대로 유지한다"는 연산자다. 즉 검증만 하고 타입은 바꾸지 않는다.

```ts
const palette = {
  primary: [255, 0, 0],
  border: "#ccc",
} satisfies Palette;

palette.primary.map((c) => c * 2); // ✅ primary 는 number[] 로 추론 유지
palette.border.toUpperCase();      // ✅ border 는 string
// @ts-expect-error 오타는 여전히 잡힌다
const bad = { primry: [0, 0, 0] } satisfies Palette;
```

핵심은 `satisfies`가 `as`와 다르다는 점이다. `as T`는 "컴파일러야 믿어라"는 단방향 단언으로, 실제로 맞지 않아도(구조가 어긋나면 일부 제약은 있지만) 강제로 통과시키고 타입을 `T`로 바꾼다. `satisfies`는 반대로 "실제로 `T`를 만족하는지 검사"하며 타입은 원본 유지다. 안전성 측면에서 `satisfies`가 우월하다.

한 가지 미묘한 점: `satisfies`만으로는 배열이 튜플로 좁혀지지 않는다. 위 `primary`는 `number[]`이지 `[number, number, number]`가 아니다. 튜플 고정이 필요하면 `as const`와 조합한다.

## 3. as const와의 상호작용

`as const`는 const 어서션으로, 리터럴을 가능한 한 좁게(넓히지 않고) 추론하고 모든 프로퍼티를 `readonly`로, 배열을 `readonly` 튜플로 만든다. `satisfies`와 결합하면 "튜플로 고정 + 스키마 검증 + 좁은 타입 유지"를 모두 얻는다.

```ts
const routes = {
  home: { path: "/", method: "GET" },
  createUser: { path: "/users", method: "POST" },
} as const satisfies Record<string, { path: string; method: "GET" | "POST" }>;

// routes.createUser.method 는 "POST" 리터럴로 고정
type Methods = (typeof routes)[keyof typeof routes]["method"]; // "GET" | "POST"
```

평가 순서에 주의한다. `as const satisfies T`는 먼저 `as const`로 좁힌 타입을 만든 뒤 그 타입이 `T`를 만족하는지 검사한다. 반대 순서(`satisfies T as const`)는 문법상 어색하고 의도와 다르게 동작하므로 관용적으로 `as const satisfies T`를 쓴다.

세 방식의 추론 결과를 표로 비교한다.

| 작성 방식 | 스키마 검증 | 배열 추론 | readonly | 리터럴 보존 |
|---|---|---|---|---|
| `const x: T = {...}` | O | 선언 타입으로 확장 | 선언에 따름 | X(넓어짐) |
| `const x = {...} as const` | X | `readonly` 튜플 | O | O |
| `const x = {...} satisfies T` | O | `number[]` | X | O(값 단위) |
| `const x = {...} as const satisfies T` | O | `readonly` 튜플 | O | O |

## 4. const 타입 파라미터

TypeScript 5.0의 `const` 타입 파라미터는 제네릭 호출에서 인자를 `as const`를 붙인 것처럼 좁게 추론하게 한다. 라이브러리 작성자가 사용자에게 매번 `as const`를 요구하지 않아도 되게 만드는 장치다.

```ts
// const 없이: names 는 string[] 로 추론된다
function makeEnumLoose<T extends string>(names: T[]): T { return names[0]; }
const a = makeEnumLoose(["up", "down"]); // T = string

// const 파라미터: 호출 지점에서 as const 를 부여한 효과
function makeEnum<const T extends readonly string[]>(names: T): T[number] {
  return names[0];
}
const b = makeEnum(["up", "down"]); // 반환 "up" | "down"
```

`const` 타입 파라미터는 "호출 인자에만" 좁힘을 부여한다. 사용자가 이미 `let` 변수를 넘기는 등 넓은 타입이 확정된 경우엔 좁힐 근거가 없어 효과가 사라진다. 또한 `const`는 추론 힌트일 뿐 `readonly` 제약을 강제하지 않으므로, 실제로 `readonly` 배열을 받고 싶으면 `extends readonly ...`를 함께 명시해야 한다.

## 5. 실전: 타입 안전 이벤트 버스

`satisfies`로 핸들러 맵을 검증하면서 각 이벤트의 페이로드 타입을 그대로 추출하는 패턴이다.

```ts
type EventMap = {
  login: { userId: string };
  purchase: { itemId: string; price: number };
};

const handlers = {
  login: (p) => console.log(p.userId),
  purchase: (p) => console.log(p.itemId, p.price.toFixed(2)),
} satisfies { [K in keyof EventMap]: (payload: EventMap[K]) => void };

function emit<K extends keyof EventMap>(key: K, payload: EventMap[K]) {
  handlers[key](payload as any); // 디스패치 경계에서만 캐스팅
}

emit("purchase", { itemId: "sku1", price: 9.9 }); // ✅
// @ts-expect-error price 누락
emit("purchase", { itemId: "sku1" });
```

`satisfies`가 없으면 `handlers`에 애노테이션을 붙여야 하고, 그러면 각 핸들러의 `p` 파라미터가 매핑 타입을 통해 자동 추론되지 못하거나 반대로 값 타입이 넓어진다. `satisfies`는 애노테이션의 검증력을 유지하면서 값 쪽 추론을 살린다.

## 6. 흔한 함정과 트레이드오프

첫째, `satisfies`는 런타임에 아무 코드도 남기지 않는 순수 타입 연산이다. 방출된 JS에는 `satisfies Palette`가 사라진다. 따라서 런타임 검증(예: 외부 JSON 파싱)에는 Zod 같은 스키마 검증기가 여전히 필요하다. `satisfies`는 "내가 작성한 리터럴이 스키마에 맞는지"를 컴파일 타임에 확인할 뿐이다.

둘째, `satisfies`로도 초과 프로퍼티 검사(excess property check)가 동작한다. 객체 리터럴을 직접 `satisfies`하면 스키마에 없는 키는 오류다. 다만 변수를 거쳐 넘기면 초과 검사가 완화되는 것은 일반 할당과 동일하다.

셋째, `as const satisfies T`에서 `T`가 `readonly`를 요구하지 않으면 문제없지만, `T`가 가변 배열(`number[]`)을 요구하는데 `as const`가 `readonly number[]`를 만들면 "readonly는 mutable에 할당 불가" 오류가 난다. 이 경우 스키마를 `readonly number[]`로 바꾸거나 `as const`를 빼야 한다.

## 7. 컴파일러 관점: 문맥 타입과 추론 우선순위

세 도구의 동작을 컴파일러의 "문맥 타입(contextual type)"과 "넓히기(widening)" 관점에서 보면 언제 무엇을 써야 하는지가 명확해진다. 애노테이션(`const x: T`)은 우변 리터럴에 `T`를 문맥 타입으로 강제로 씌우고, 그 결과 리터럴은 `T`의 관점으로 관찰된다. 그래서 `[255,0,0]`이 `RGB | string`인 `Color`로 관찰되어 배열 메서드를 잃는다. 반면 `satisfies`는 리터럴을 먼저 "그 자체로" 추론(freshly inferred)한 뒤 그 추론 결과가 `T`에 할당 가능한지만 사후 검사한다. 문맥 타입을 씌우지 않으므로 추론이 살아 있다.

이 차이는 함수 반환에서도 드러난다. 반환 타입을 애노테이트하면 반환 표현식이 그 타입으로 관찰되지만, 본문에서 `satisfies`로 검증하고 넓은 반환 타입은 시그니처로 따로 선언하면 "내부는 좁게, 외부는 넓게"를 분리할 수 있다.

```ts
type Config = { retries: number; mode: "fast" | "safe" };

function makeConfig() {
  const cfg = { retries: 3, mode: "fast" } satisfies Config;
  //    ^ 내부에서는 mode: "fast" 리터럴로 사용 가능
  return cfg;
}
const c = makeConfig(); // c.mode 는 "fast" 로 좁게 노출
```

또 하나 실무 팁은 `Record` 스키마와 조합할 때다. `satisfies Record<string, X>`는 키 목록을 제한하지 않으면서 각 값이 `X`를 만족하는지만 검사하고, `keyof typeof obj`로 실제 키 유니온을 추출할 수 있게 한다. 즉 "값 검증은 스키마로, 키 집합은 실제 객체로"라는 두 마리 토끼를 잡는다. 반대로 키 집합까지 고정하고 싶으면 `satisfies Record<"a" | "b", X>`처럼 키 유니온을 명시하면 누락·초과 키가 모두 오류가 된다.

```ts
const icons = {
  save: "M1 1h10",
  load: "M2 2h8",
} satisfies Record<string, string>;

type IconName = keyof typeof icons; // "save" | "load" 자동 추출
function render(name: IconName) { return icons[name]; }
```

마지막으로 `const` 타입 파라미터와 `satisfies`는 역할이 겹치지 않는다. `const` 파라미터는 "라이브러리 함수가 사용자 인자를 좁게 받도록" 하는 생산자 측 장치이고, `satisfies`는 "내가 만든 리터럴을 스키마로 검증하되 좁게 유지"하는 소비자 측 장치다. 둘을 함께 쓰면 API 제공자와 사용자 양쪽에서 좁은 타입이 끝까지 흐른다.

## 참고

- TypeScript Handbook — Release Notes 4.9 (satisfies operator)
- TypeScript Handbook — Release Notes 5.0 (const type parameters)
- TypeScript Handbook — Everyday Types / const assertions
- microsoft/TypeScript GitHub — #47920 (satisfies operator 설계 논의)
