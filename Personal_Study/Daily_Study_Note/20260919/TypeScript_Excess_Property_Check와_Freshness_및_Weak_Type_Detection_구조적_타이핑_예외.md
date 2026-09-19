Notion 원본: https://www.notion.so/3e05a06fd6d381fbb3eaf91961155e42

# TypeScript Excess Property Check와 Freshness 및 Weak Type Detection 구조적 타이핑 예외

> 2026-09-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 구조적 할당 가능성 규칙과 별개로 동작하는 초과 프로퍼티 검사(EPC)의 발동 조건을 정리한다
- freshness(신선도)가 어느 연산에서 소실되는지 코드로 재현한다
- Weak Type Detection과 Common Property Check 두 추가 휴리스틱을 EPC와 구분한다
- 유니온·교차·제네릭 상황에서 EPC가 놓치는 구멍을 알고 대안 패턴을 적용한다

## 1. 구조적 타이핑의 기본 규칙과 그 위의 예외

TypeScript의 할당 가능성은 구조적이다. `S`가 `T`의 모든 멤버를 호환되는 타입으로 가지면 `S`는 `T`에 할당 가능하고, `S`에 추가 멤버가 있어도 상관없다. 이것이 정상이다.

```ts
interface Point { x: number; y: number }

const p3 = { x: 1, y: 2, z: 3 };
const p: Point = p3;   // OK — 변수를 거치면 추가 멤버 z 는 문제가 아니다
```

그런데 같은 값을 리터럴로 직접 쓰면 에러가 난다.

```ts
const p: Point = { x: 1, y: 2, z: 3 };
//                             ~~~~
// Object literal may only specify known properties,
// and 'z' does not exist in type 'Point'. ts(2353)
```

두 코드의 타입 관계는 동일하다. 달라진 것은 **객체 리터럴이 직접 그 자리에 있는가**뿐이다. 이 추가 검사를 초과 프로퍼티 검사(Excess Property Check, EPC)라 부르고, 그 발동 조건을 결정하는 내부 플래그가 **freshness**다.

EPC는 타입 시스템의 건전성(soundness) 규칙이 아니다. 구조적 타이핑을 유지하면서도 "오타와 잘못된 옵션 전달"이라는 가장 흔한 실수를 잡기 위한 실용적 휴리스틱이다. 이 성격을 이해해야 왜 구멍이 있는지도 납득된다.

## 2. Freshness — 리터럴 타입에 붙는 임시 표식

컴파일러는 객체 리터럴에서 추론된 타입에 `ObjectFlags.FreshLiteral` 플래그를 붙인다. 이 플래그가 살아 있는 타입을 대상 타입에 할당할 때만 EPC가 돈다. freshness는 다음 순간에 사라진다.

**(a) 타입 표기가 있는 변수에 담긴 뒤 다시 쓰일 때.**

```ts
const raw = { x: 1, y: 2, z: 3 };   // 추론 타입은 fresh 하지만
const p: Point = raw;               // 식별자 참조이므로 EPC 없음 → OK
```

**(b) 타입 단언을 거칠 때.**

```ts
const p: Point = { x: 1, y: 2, z: 3 } as { x: number; y: number; z: number };  // OK
```

**(c) 대상 타입이 `any`이거나 인덱스 시그니처를 가질 때.**

```ts
interface Loose { x: number; [key: string]: unknown }
const l: Loose = { x: 1, z: 3 };   // OK — 인덱스 시그니처가 z 를 흡수
```

freshness의 수명은 "그 표현식 위치"로 한정된다. 스프레드는 애매한데, 결과가 새 객체 리터럴이므로 여전히 fresh다.

```ts
const extra = { z: 3 };
const p: Point = { x: 1, y: 2, ...extra };   // OK — 스프레드된 멤버는 EPC 대상이 아니다
```

이것이 실무에서 가장 자주 만나는 우회로다. `...extra`로 들어온 `z`는 검사되지 않으므로, 설정 객체를 스프레드로 합성하는 코드는 EPC의 보호를 받지 못한다. 오타를 잡고 싶다면 합성 전에 각 조각을 개별 타입으로 못 박아야 한다.

## 3. 함수 인자와 반환값에서의 EPC

EPC는 변수 초기화뿐 아니라 리터럴이 직접 놓이는 모든 위치에서 작동한다.

```ts
declare function draw(p: Point): void;

draw({ x: 1, y: 2, z: 3 });          // Error ts(2345) — 인자 위치
const f = (): Point => ({ x: 1, y: 2, z: 3 });   // Error — 반환 위치
const arr: Point[] = [{ x: 1, y: 2, z: 3 }];     // Error — 배열 요소도 각각 fresh
```

React 컴포넌트의 props 객체, 옵션 객체를 받는 API에서 EPC가 실질적 방어선이 되는 이유다. 반대로 값을 한 번 변수에 담아 넘기는 헬퍼를 거치면 그 방어선이 사라진다.

```ts
function withDefaults<T>(input: T): T { return input; }
draw(withDefaults({ x: 1, y: 2, z: 3 }));   // OK — 제네릭을 통과하며 freshness 소실
```

제네릭 함수의 반환값은 더 이상 리터럴 표현식이 아니므로 EPC가 없다. 라이브러리에서 `defineConfig(...)` 같은 항등 함수를 제공할 때 이 점이 문제가 된다. 해결은 파라미터 타입을 제네릭이 아니라 구체 타입으로 받는 것이다.

```ts
// 나쁨: T 로 받으면 EPC 소실
export function defineConfigLoose<T extends Config>(c: T): T { return c; }

// 좋음: 파라미터가 Config 이므로 인자 위치에서 EPC 발동
export function defineConfig(c: Config): Config { return c; }
```

제네릭이 꼭 필요하면 `satisfies`를 호출부에서 쓰게 안내하는 편이 낫다.

```ts
const config = {
  port: 8080,
  hostt: 'localhost',   // 오타
} satisfies Config;     // Error — satisfies 는 EPC 를 발동시킨다
```

`satisfies`는 freshness를 유지한 채 대상 타입과의 호환성을 검사하고, 결과 타입은 리터럴 추론을 보존한다. `as`와 결정적으로 다른 지점이다.

## 4. 유니온 대상에서의 EPC — 합집합 규칙과 그 구멍

대상 타입이 유니온이면 EPC는 "유니온 **구성원 전체의 프로퍼티 합집합**에 없는 키"만 잡는다.

```ts
type Circle = { kind: 'circle'; radius: number };
type Square = { kind: 'square'; side: number };
type Shape = Circle | Square;

const s1: Shape = { kind: 'circle', radius: 1, side: 2 };
// OK ?! — side 는 Square 의 프로퍼티이므로 합집합에 존재한다
```

`kind: 'circle'`인데 `side`를 들고 있는 명백히 잘못된 값이 통과한다. TypeScript 3.x 이후 판별 유니온(discriminated union)에 한해 개선되어, 판별자가 확정되면 해당 구성원으로 좁힌 뒤 검사한다.

```ts
const s2: Shape = { kind: 'circle', radius: 1, sidee: 2 };
// Error — sidee 는 어느 구성원에도 없다
```

현재 컴파일러는 판별 프로퍼티가 리터럴로 명시된 경우 해당 구성원만 후보로 두고 EPC를 수행하므로 `s1` 같은 사례도 버전에 따라 잡힌다. 다만 판별자가 없는 유니온에서는 여전히 합집합 규칙이므로, 상호 배타적 옵션을 표현할 때는 **명시적 never 필드**로 방어하는 패턴이 안전하다.

```ts
type WithUrl   = { url: string;  file?: never };
type WithFile  = { file: File;   url?:  never };
type Source = WithUrl | WithFile;

const bad: Source = { url: 'x', file: new File([], 'a') };
// Error — file?: never 와 충돌
```

## 5. Weak Type Detection — 전부 선택적인 타입의 방어

모든 프로퍼티가 선택적인 타입을 "weak type"이라 한다. 구조적 규칙만 따르면 **아무 객체나** 할당 가능해진다. 빈 객체도 호환되기 때문이다.

```ts
interface Options {
  timeout?: number;
  retries?: number;
}

const o: Options = { timeoutMs: 3000 };   // 구조적으로는 OK 여야 하지만…
// Error ts(2559): Type '{ timeoutMs: number; }' has no properties in common with type 'Options'.
```

TypeScript 2.4에 도입된 Weak Type Detection이 이를 막는다. 규칙은 "소스와 타깃이 **공통 프로퍼티를 하나도 공유하지 않으면** 에러"다. EPC와 달리 **freshness와 무관하게** 작동하므로, 변수를 거쳐도 잡힌다.

```ts
const args = { timeoutMs: 3000 };
const o2: Options = args;   // 여전히 Error — weak type 검사는 fresh 여부를 보지 않는다
```

다만 하나라도 겹치면 통과한다.

```ts
const o3: Options = { timeout: 1000, timeoutMs: 3000 };
// Error — 이건 EPC 가 잡는다(fresh 이므로)
const args2 = { timeout: 1000, timeoutMs: 3000 };
const o4: Options = args2;   // OK — 공통 프로퍼티 timeout 존재, fresh 아님 → 둘 다 통과
```

즉 weak type 검사는 "완전히 엉뚱한 객체"만 걸러 주는 최소 방어선이다. 교차 타입에 weak type이 섞이면 검사 대상이 교차 전체로 바뀌므로 더 느슨해진다.

```ts
type A = { a?: string };
type B = { b: number };
const ab: A & B = { b: 1, zzz: 2 };   // EPC 는 잡지만, 변수 경유 시 통과
```

관련된 세 번째 휴리스틱이 **Common Property Check**다. 유니온 대상에 할당할 때, 소스가 유니온의 어느 구성원과도 프로퍼티를 공유하지 않으면 에러를 낸다. weak type 규칙의 유니온 버전이라고 보면 된다.

## 6. 인덱스 시그니처와 `Record`가 검사를 무력화하는 방식

대상 타입에 문자열 인덱스 시그니처가 있으면 EPC는 완전히 꺼진다. 초과 키가 인덱스 시그니처에 흡수되기 때문이다.

```ts
type Headers = { 'content-type'?: string; [k: string]: string | undefined };
const h: Headers = { 'content-typ': 'application/json' };   // OK — 오타 무방비
```

`Record<string, T>`도 같다. 설정 객체를 `Record`로 받는 API는 EPC의 이점을 포기한 것이다. 키 집합이 유한하다면 유니온 키로 좁히는 편이 훨씬 낫다.

```ts
type HeaderName = 'content-type' | 'accept' | 'authorization';
type Headers2 = Partial<Record<HeaderName, string>>;
const h2: Headers2 = { 'content-typ': 'x' };   // Error — 키가 유니온에 없다
```

`Record<HeaderName, string>`은 매핑 타입이며 인덱스 시그니처가 아니므로 EPC가 정상 작동한다. 이 차이는 실무에서 자주 혼동된다: `[k: string]: T`는 인덱스 시그니처, `{ [K in Union]: T }`는 매핑 타입이고 후자만 키를 강제한다.

## 7. 직접 확인하는 방법 — 컴파일러에게 물어보기

EPC가 도는지 아닌지 헷갈릴 때는 의도적으로 잘못된 키를 넣어 보는 것이 가장 빠르다. 좀 더 체계적으로는 타입 수준 테스트로 회귀를 막는다.

```ts
// 헬퍼: 초과 프로퍼티를 컴파일 타임에 금지
type Exact<T, Shape> = T & { [K in Exclude<keyof T, keyof Shape>]: never };

function strictDraw<T extends Point>(p: Exact<T, Point>): void { /* ... */ }

strictDraw({ x: 1, y: 2 });           // OK
strictDraw({ x: 1, y: 2, z: 3 });     // Error — z 가 never 로 요구됨
```

`Exact<T, Shape>`는 초과 키를 `never`로 요구해 어떤 값도 만족하지 못하게 만든다. 제네릭 경유로 freshness가 소실되는 §3의 상황을 보완하는 표준 패턴이다. 단점은 에러 메시지가 난해해지고(`Type 'number' is not assignable to type 'never'`) 추론이 복잡해진다는 것이라, 라이브러리 공개 API에만 선택적으로 쓴다.

컴파일러 내부 동작을 직접 보고 싶다면 진단 플래그가 있다.

```bash
# 어떤 파일이 얼마나 체크되는지 / 타입 인스턴스화 수
tsc --noEmit --extendedDiagnostics

# 특정 표현식의 추론 결과 확인 (에디터 호버가 가장 빠르지만 CI 에서는 이쪽)
tsc --noEmit --listFiles
```

에러 코드로 구분하면 진단이 빨라진다.

| 코드 | 의미 | 발동 조건 |
|---|---|---|
| ts(2353) | Object literal may only specify known properties | EPC — 변수 초기화·반환 위치 |
| ts(2345) | Argument of type ... not assignable | EPC — 인자 위치 |
| ts(2559) | has no properties in common with | Weak Type Detection |
| ts(2322) | Type ... is not assignable to type | 일반 구조적 불일치 (EPC 아님) |

ts(2322)가 나왔다면 EPC가 아니라 진짜 구조 불일치이므로, 접근이 달라야 한다.

## 8. 설계 지침 — 어디에 무엇을 쓸 것인가

정리하면 세 층의 방어선이 있고, 각각 커버 범위가 다르다.

**1층 — EPC(freshness 기반).** 가장 넓게 걸리지만 리터럴이 직접 놓인 위치에서만 작동한다. 공개 API의 파라미터 타입을 제네릭이 아닌 구체 타입으로 선언하면 자동으로 얻는다. 비용 0.

**2층 — `satisfies`.** 호출자가 리터럴을 선언하는 지점에서 EPC를 발동시키면서 좁은 추론을 유지한다. 설정 파일, 상수 테이블에 기본으로 쓴다.

```ts
export const routes = {
  home:    { path: '/',        auth: false },
  profile: { path: '/me',      auth: true  },
} satisfies Record<string, RouteDef>;

type RouteKey = keyof typeof routes;   // 'home' | 'profile' — 리터럴 보존
```

`: Record<string, RouteDef>`로 표기했다면 `RouteKey`는 `string`이 되어 버린다. `satisfies`는 검사와 추론을 분리한다는 점에서 EPC 논의의 실용적 결론이다.

**3층 — `Exact` 유틸리티.** 제네릭 경유가 불가피하고, 초과 프로퍼티가 런타임에 실제 해를 끼치는 경우(예: 그대로 직렬화해 외부 API로 보내는 페이로드)에만 쓴다. 에러 가독성 비용을 지불할 가치가 있을 때로 제한한다.

그리고 반대편 트레이드오프도 분명히 해 두는 편이 낫다. EPC는 건전성 장치가 아니므로 이것에 보안이나 데이터 무결성을 의존해서는 안 된다. 외부에서 들어온 JSON은 어차피 `any`/`unknown`이고 freshness 개념 자체가 없다. 그 경계는 Zod·Valibot 같은 런타임 스키마 검증이 담당해야 하고, EPC는 "내가 쓴 코드의 오타"를 잡는 개발 편의 장치로 위치를 한정하는 것이 정확하다.

```ts
// 경계: 런타임 검증이 타입을 만든다
const ConfigSchema = z.object({ port: z.number(), host: z.string() }).strict();
//                                                                    ^^^^^^^^
// .strict() 가 런타임의 EPC 에 해당한다 — 초과 키를 에러로 처리
type Config = z.infer<typeof ConfigSchema>;
```

`.strict()`를 붙이지 않으면 Zod는 초과 키를 조용히 제거한다(`strip`이 기본). 컴파일 타임 EPC와 런타임 strict를 짝지어 두어야 두 경계에서 같은 규칙이 적용된다.

## 참고

- TypeScript Handbook — "Object Types: Excess Property Checks"
- TypeScript Release Notes 2.4 — "Weak Type Detection"
- TypeScript Release Notes 4.9 — "The satisfies Operator"
- TypeScript Wiki — "FAQ: Why am I getting an error about excess properties?"
- microsoft/TypeScript `src/compiler/checker.ts` — `hasExcessProperties`, `isKnownProperty`, `ObjectFlags.FreshLiteral`
- Zod Documentation — "Objects: .strict / .strip / .passthrough"
