Notion 원본: https://www.notion.so/39b5a06fd6d3810da63efed84786783a

# TypeScript Template Literal Types와 재귀 추론 및 타입레벨 파싱

> 2026-07-12 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Template Literal Types 가 문자열 리터럴 유니온을 어떻게 조합·분배하는지 설명한다.
- `infer` 를 템플릿 패턴 안에 배치해 문자열을 분해·추출한다.
- 재귀 조건부 타입으로 경로(path)·쿼리스트링을 타입 레벨에서 파싱한다.
- 재귀 깊이 한계와 성능 함정을 이해하고 실무에서 안전하게 쓴다.

## 1. Template Literal Types 의 기본과 분배

TypeScript 4.1 부터 문자열 리터럴을 템플릿처럼 조립하는 타입이 가능하다. 백틱 안에 다른 타입을 `${}` 로 끓워 넣으면 새 문자열 리터럴 타입이 만들어진다. 끓워 넣은 타입이 유니온이면 **교차 조합(cross product)** 으로 분배된다.

```ts
type Lang = "ko" | "en";
type Kind = "page" | "post";
type Key = `${Lang}_${Kind}`;
// "ko_page" | "ko_post" | "en_page" | "en_post"  (2 x 2 = 4)
```

이 분배 규칙 덕분에 CSS-in-JS 의 속성명, i18n 키, 이벤트명(`on${Capitalize<E>}`) 같은 조합형 문자열을 타입으로 정확히 표현할 수 있다. 내장 유틸리티 `Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize` 는 컴파일러 내장(intrinsic) 문자열 조작자로, 템플릿과 결합해 케이스 변환을 타입 레벨에서 수행한다.

```ts
type Getter<K extends string> = `get${Capitalize<K>}`;
type G = Getter<"name">; // "getName"
```

## 2. infer 로 문자열 분해

조건부 타입의 `infer` 를 템플릿 패턴 안에 두면 문자열의 일부를 캐프처할 수 있다. 정규식의 캐프처 그룹과 유사하다.

```ts
type Split2<S extends string> =
    S extends `${infer Head}_${infer Tail}` ? [Head, Tail] : [S];

type A = Split2<"ko_page">; // ["ko", "page"]
```

`infer Head` 는 첫 `_` 이전을, `infer Tail` 은 이후(첫 매치 기준, non-greedy)를 잡는다. 이 패턴 매칭이 타입 레벨 파싱의 기본 도구다. 예를 들어 라우트 파라미터 추출을 보자.

```ts
type PathParams<S extends string> =
    S extends `${infer _Start}:${infer Param}/${infer Rest}`
        ? Param | PathParams<`/${Rest}`>
        : S extends `${infer _Start}:${infer Param}`
            ? Param
            : never;

type P = PathParams<"/users/:userId/posts/:postId">;
// "userId" | "postId"
```

이렇게 추출한 유니온으로 `Record<PathParams<T>, string>` 를 만들면, `buildPath("/users/:userId", { userId: "1" })` 같은 함수에서 누락된 파라미터를 컴파일 타임에 잡을 수 있다.

## 3. 재귀 조건부 타입으로 완전 분해

두 조각이 아니라 임의 개수로 나누려면 재귀한다. 조건부 타입은 자기 자신을 참조할 수 있다.

```ts
type Split<S extends string, D extends string> =
    S extends `${infer Head}${D}${infer Tail}`
        ? [Head, ...Split<Tail, D>]  // 튜플 스프레드로 누적
        : [S];

type Parts = Split<"a.b.c.d", ".">; // ["a", "b", "c", "d"]
```

핵심은 `[Head, ...Split<Tail, D>]` 의 가변 튜플 스프레드다. 매 재귀 단계가 남은 문자열을 다시 쪼개 결과 앞에 붙인다. 종료 조건은 더 이상 구분자가 없어 `[S]` 로 떨어지는 시점이다.

이를 응용해 중첩 객체의 도트 경로 접근 타입을 만들 수 있다. 이는 lodash 의 `get(obj, "a.b.c")` 를 타입 안전하게 만드는 실전 패턴이다.

```ts
type DeepGet<T, Path extends string> =
    Path extends `${infer K}.${infer Rest}`
        ? K extends keyof T
            ? DeepGet<T[K], Rest>
            : never
        : Path extends keyof T
            ? T[Path]
            : never;

interface Config {
    server: { port: number; host: string };
}
type Port = DeepGet<Config, "server.port">; // number
type Bad = DeepGet<Config, "server.ssl">;   // never (오타를 타입에서 검출)
```

## 4. 쿼리스트링 파서 — 타입 레벨 파싱 종합

여러 기법을 합쳤 쿼리스트링을 객체 타입으로 파싱해 본다. `Split` 으로 `&` 를 나누고, 각 쌍을 `=` 로 다시 나눠 키/값 매핑을 만든다.

```ts
type ParsePair<S extends string> =
    S extends `${infer K}=${infer V}` ? { [P in K]: V } : {};

type Merge<T> = { [K in keyof T]: T[K] }; // 교차 타입 평탄화

type ParseQuery<S extends string> =
    S extends `${infer Pair}&${infer Rest}`
        ? Merge<ParsePair<Pair> & ParseQuery<Rest>>
        : ParsePair<S>;

type Q = ParseQuery<"page=1&size=20&sort=desc">;
// { page: "1"; size: "20"; sort: "desc" }
```

값이 항상 `string` 이라는 한계는 있지만, 여기에 조건부 타입을 더해 숫자처럼 보이는 값을 `number` 로 좁히는 확장도 가능하다. 이 정도면 컴파일 타임에 API 계약을 문자열 리터럴로 검증하는 tRPC·라우터 라이브러리의 핵심 메커니즘을 재현한 것이다.

## 5. 재귀 깊이 한계와 tail-recursion

TypeScript 컴파일러는 무한 재귀를 막으려 재귀 인스턴스화 깊이를 제한한다. 조건부 타입은 대략 50 단계, 특정 tail-recursive 형태는 4.5+ 에서 최적화돼 1000 단계까지 허용된다. 한계를 넘으면 `Type instantiation is excessively deep and possibly infinite (2589)` 에러가 난다.

tail-recursion 최적화를 받으려면 재귀 호출이 조건부 타입의 **최종 결과 위치**에 있어야 한다. 누적자(accumulator)를 파라미터로 넘기는 형태가 유리하다.

```ts
// 누적자 방식 — tail 위치 재귀로 깊은 문자열도 처리
type SplitAcc<S extends string, D extends string, Acc extends string[] = []> =
    S extends `${infer Head}${D}${infer Tail}`
        ? SplitAcc<Tail, D, [...Acc, Head]>  // 결과를 Acc 에 누적하며 리 재귀
        : [...Acc, S];

type Deep = SplitAcc<"a.b.c.d.e.f", ".">; // ["a","b","c","d","e","f"]
```

`[...Acc, Head]` 로 앞에서부터 누적하고 재귀가 tail 위치에 있으므로, 프론트에서 스프레드하는 `[Head, ...Split<...>]` 형태보다 훨씬 깊은 입력을 견딘다.

## 6. 성능·유지보수 함정

타입 레벨 파싱은 강력하지만 남용하면 컴파일러가 느려지고 IDE 반응성이 떨어진다. 다음을 유의한다.

첫째, 복잡한 재귀 타입은 에러 메시지를 난해하게 만든다. `never` 가 여러 단계를 거쳐 전파되면 어디서 매칭이 깨졌는지 추적이 어렵다. 중간 타입에 이름을 붙이고 `type _Debug = ...` 로 단계별 확인하는 습관이 필요하다.

둘째, 입력 문자열이 리터럴이 아니라 넓은 `string` 이면 모든 패턴 매칭이 실패해 대개 `never` 나 fallback 으로 떨어진다. `as const` 나 제네릭 제약으로 리터럴성을 유지해야 한다.

```ts
function route<T extends string>(path: T): PathParams<T>[] { /* ... */ return []; }
route("/users/:id"); // OK: 리터럴 유지
const p: string = "/users/:id";
route(p);            // PathParams<string> => never[]
```

셋째, 타입 검사는 런타임 검증이 아니다. 외부에서 들어오는 실제 문자열은 Zod 등으로 런타임 파싱하고, Template Literal Types 는 어디까지나 개발 시점의 자동완성·오타 방지 용도로 쓴다. 두 층을 혼동하면 "타입은 통과했는데 런타임에 깨지는" 사고가 난다.

## 7. 실무 적용 지점

Template Literal Types 의 실전 가치는 (1) 라우터 경로 파라미터 타입 안전, (2) i18n/이벤트/CSS 키의 자동완성, (3) SQL/GraphQL 쿼리 문자열의 컴파일 타임 검증, (4) 객체 도트 경로 접근에 있다. 공통점은 "문자열이 사실은 구조를 가진 DSL"이라는 것이다. 그 구조를 타입으로 표현하면 IDE 가 오타와 계약 위반을 즉시 알려 준다. 단, 복잡도가 팀의 이해 수준을 넘으면 유지보수 부채가 되므로, 재귀 3~4 단계를 넘는 타입 마법은 라이브러리 경계 안에 캐슸화하고 애플리케이션 코드에서는 그 결과 타입만 소비하는 것이 건강한 경계다.

## 8. 매핑 타입 키 리매핑과의 결합

Template Literal Types 는 매핑 타입의 `as` 절과 결합할 때 진가를 발휘한다. 4.1 의 키 리매핑(`as`)으로 기존 객체의 키를 템플릿으로 변형한 새 타입을 만들 수 있다. 대표 예가 이벤트 핸들러 자동 생성이다.

```ts
type Events = { click: MouseEvent; focus: FocusEvent; keydown: KeyboardEvent };

type Handlers<T> = {
    [K in keyof T as `on${Capitalize<string & K>}`]: (e: T[K]) => void;
};

type H = Handlers<Events>;
// { onClick: (e: MouseEvent) => void;
//   onFocus: (e: FocusEvent) => void;
//   onKeydown: (e: KeyboardEvent) => void }
```

`string & K` 로 감싼 이유는 `keyof T` 가 `string | number | symbol` 일 수 있어 `Capitalize` 가 요구하는 `string` 으로 좁히기 위해서다. 이 패턴은 React 컴포넌트의 prop 타입, ORM 의 컬럼→게터 매핑, 상태 관리 라이브러리의 액션 생성자 타입을 자동 파생하는 데 광범위하게 쓰인다.

리매핑에서 특정 키를 제외할 때는 `never` 로 매핑하면 그 키가 결과에서 사라진다. 조건부 타입과 결합해 "문자열 키만 골라 게터로 변환"하는 식의 정교한 필터링도 가능하다.

```ts
type Getters<T> = {
    [K in keyof T as K extends string ? `get${Capitalize<K>}` : never]: () => T[K];
};
```

## 9. 문자열 검증 타입 — Brand 와의 조합

Template Literal Types 로 문자열의 **형식**을 타입 수준에서 제약할 수 있다. 예를 들어 hex 색상, 이메일 유사 패턴, 버전 문자열을 조건부로 검사해 부적합하면 `never` 로 떨구는 검증 타입을 만든다.

```ts
type HexColor<S extends string> =
    S extends `#${infer R}` ? (R extends `${string}` ? S : never) : never;

type SemVer<S extends string> =
    S extends `${infer _A}.${infer _B}.${infer _C}` ? S : never;

function setVersion<S extends string>(v: SemVer<S> extends never ? never : S) { /* ... */ }
setVersion("1.2.3"); // OK
// setVersion("1.2"); // Error: never 로 떨어져 인자 불가
```

다만 이 방식은 "구조"는 검사해도 "값의 범위"(예: 0~255)는 못 잡는다. 완전한 검증이 필요하면 런타임 파서(Zod 등)와 병행하고, Template Literal Types 는 자동완성과 명백한 오타 차단이라는 개발 경험(DX) 향상에 초점을 둔다. 두 층의 책임을 명확히 나누는 것이 유지보수의 핵심이다.

## 참고

- TypeScript Handbook — Template Literal Types
- TypeScript 4.1 / 4.5 Release Notes (tail-recursion 최적화)
- Microsoft/TypeScript 이슈 트래커 — recursive conditional types
- "Type-Level TypeScript" (type-level-typescript.com)
