Notion 원본: https://www.notion.so/3b55a06fd6d381a7a809e81dca668080

# TypeScript ts-pattern 패턴 매칭과 P.infer 및 exhaustive 검사
> 2026-08-07 신규 주제 · 확장 대상: TypeScript (판별 유니온·Exhaustiveness Checking 기학습)

## 학습 목표
- match/with/when/otherwise/exhaustive API 를 switch 문과 비교하고 표현식 기반 분기로 재작성한다
- P.select 와 P.infer 의 타입 추론 메커니즘을 추적하고 패턴을 타입 스키마로 활용한다
- .exhaustive() 가 Exclude 기반 누적 추론으로 누락 케이스를 컴파일 에러로 만드는 과정을 분석한다
- 런타임 매칭 비용과 타입 인스턴스화 비용을 계량하여 switch/if 대비 도입 기준을 수립한다

## 1. match API 와 switch 문 대비 표현력·타입 안전성

ts-pattern 은 Gabriel Vergnaud 가 만든 TypeScript 패턴 매칭 라이브러리로, Rust·OCaml 류 함수형 언어의 `match` 표현식을 타입 수준에서 재현한다. 진입점은 `match(value)` 이며 `.with(pattern, handler)` 로 케이스를 쌓고 `.otherwise(fallback)` 또는 `.exhaustive()` 로 닫는다. switch 대비 차이는 세 가지다. 첫째, match 는 **표현식(expression)** 이라 결과를 바로 할당·반환할 수 있다. 둘째, switch 가 단일 값 동등 비교만 하는 반면 match 는 **중첩 객체·배열·튜플·술어 함수까지 하나의 패턴으로 기술**한다. 셋째, switch 의 exhaustiveness 검사는 `default` 에서 `never` 할당(assertNever) 관용구가 필요하지만 `.exhaustive()` 는 이를 일급으로 제공한다.

```typescript
import { match, P } from 'ts-pattern';

type Result =
  | { status: 'idle' }
  | { status: 'loading'; startedAt: number }
  | { status: 'success'; data: string[] }
  | { status: 'error'; error: Error };

// switch: 문(statement), 단일 키만 분기 가능
function renderSwitch(r: Result): string {
  switch (r.status) {
    case 'idle': return 'Idle';
    case 'loading': return `Loading since ${r.startedAt}`;
    case 'success': return r.data.join(', ');
    case 'error': return r.error.message;
    default: {
      const _never: never = r; // 수동 exhaustive 관용구
      return _never;
    }
  }
}

// match: 표현식, 중첩 구조 분기 + 자동 exhaustive
const renderMatch = (r: Result): string =>
  match(r)
    .with({ status: 'idle' }, () => 'Idle')
    .with({ status: 'loading', startedAt: P.number }, ({ startedAt }) => `Loading since ${startedAt}`)
    .with({ status: 'success', data: [] }, () => 'Empty result')     // 중첩: 빈 배열만
    .with({ status: 'success' }, ({ data }) => data.join(', '))
    .with({ status: 'error' }, ({ error }) => error.message)
    .exhaustive();
```

`.when(predicate, handler)` 은 패턴 대신 임의 술어로 분기하고, `.with(pattern, guard, handler)` 3인자 형태는 패턴 매칭 후 추가 가드를 건다. `.otherwise()` 는 남은 케이스 전부를 받는 fallback 으로, 남은 케이스의 좁혀진 타입을 인자로 받는다. trade-off 는 명확하다. 표현력과 안전성을 얻는 대신 외부 의존성, 런타임 패턴 해석 비용, 팀의 DSL 학습 비용이 생긴다. 단순 문자열 enum 분기는 switch 로 충분하고, 중첩 판별 유니온·튜플 동시 분기에서 ts-pattern 의 효용이 급격히 커진다.

## 2. 패턴 DSL: P 네임스페이스의 구성 요소

`P`(구 버전의 `__`·`Pattern`) 네임스페이스가 패턴 조합 DSL 을 제공한다. `P.string`, `P.number`, `P.boolean`, `P.bigint`, `P.nullish` 는 원시 타입 와일드카드, `P._`(`P.any`) 는 전역 와일드카드다. `P.array(pattern)` 은 모든 요소가 패턴을 만족하는 배열, `P.union` 은 OR, `P.intersection` 은 AND, `P.not` 은 부정, `P.optional` 은 객체 패턴에서 해당 키가 없어도 매칭됨을 뜻한다. `P.when(predicate)` 은 술어 함수를 패턴 위치에 삽입하며, 술어가 타입 가드(`v is T`)면 그 좁힘이 핸들러 타입에 반영된다.

```typescript
import { match, P } from 'ts-pattern';

type Input = { kind: string; payload?: unknown };

const describe = (input: unknown) =>
  match(input)
    .with({ kind: 'text', payload: P.string }, ({ payload }) => `text: ${payload}`)
    .with({ kind: 'nums', payload: P.array(P.number) }, ({ payload }) => `sum=${payload.reduce((a, b) => a + b, 0)}`)
    .with({ kind: P.union('a', 'b') }, ({ kind }) => `kind is a or b: ${kind}`)   // kind: 'a' | 'b'
    .with({ kind: P.string.startsWith('sys_') }, () => 'system event')            // v5 문자열 서브패턴
    .with({ payload: P.when((v): v is Date => v instanceof Date) }, ({ payload }) => payload.toISOString())
    .with({ kind: P.not('internal'), payload: P.optional(P.nullish) }, () => 'no payload')
    .otherwise(() => 'unknown');
```

v5 에서는 원시 와일드카드에 체이닝 서브패턴이 추가되어 `P.string.startsWith()`, `P.string.regex()`, `P.number.gte()`, `P.number.int()` 같은 조건을 선언적으로 쓸 수 있고, `P.set`, `P.map`, `P.instanceOf(Class)` 와 tuple + rest 형태의 가변 길이 배열 패턴도 지원한다. DSL 의 trade-off 는 각 패턴이 런타임 검사 함수로 변환된다는 점이다. 리터럴 객체 패턴은 키 접근 + `===` 비교라 저렴하지만 `P.array` 는 전체 요소 순회, `P.intersection` 은 다중 패턴 반복 평가를 유발하므로 대용량 배열을 핫 패스에서 매칭하는 설계는 피해야 한다.

## 3. P.select 로 매칭 부분 캡처와 핸들러 타입 추론

`P.select()` 는 패턴의 특정 위치를 "캡처" 표시하여, 매칭 성공 시 그 위치의 값을 핸들러의 **첫 번째 인자로 직접** 전달한다. 익명 select 가 하나면 그 값 자체가 첫 인자가 되고, `P.select('name')` 처럼 이름을 주면 여러 캡처가 `{ name: ... }` 레코드로 묶여 전달된다. 두 번째 인자는 언제나 매칭으로 좁혀진 입력 전체다.

```typescript
type Event =
  | { type: 'user.created'; user: { id: string; email: string } }
  | { type: 'order.paid'; order: { id: string; amount: number }; coupon?: { code: string } };

const handle = (e: Event) =>
  match(e)
    // 익명 select 1개: user 객체가 곧바로 첫 인자
    .with({ type: 'user.created', user: P.select() }, (user) => user.email)
    // 이름 있는 select 다수: 레코드로 수집
    .with(
      { type: 'order.paid', order: { id: P.select('orderId'), amount: P.select('amount') } },
      ({ orderId, amount }) => `${orderId}: ${amount}`
    )
    .exhaustive();
```

타입 추론 원리는 두 단계다. (1) **좁힘**: 패턴은 `const` 추론으로 리터럴이 보존된 채 캡처되고, `ExtractPreciseValue<Input, InvertPattern<Pattern>>` 류의 조건부 타입이 패턴을 "매칭될 값의 타입" 으로 뒤집어 입력 유니온에서 교차·추출한다. (2) **캡처 수집**: 패턴 트리를 재귀 순회하는 `FindSelected` 계열 타입이 `P.select` 위치의 값 타입을 모아, 익명이면 그 타입 그대로, 이름이 있으면 `{ [name]: type }` 병합으로 핸들러 첫 인자 타입을 합성한다. 런타임에서는 매칭 중 select 노드에서 `(key, value)` 를 기록했다가 핸들러 호출 시 조립한다. trade-off: select 는 깊은 경로 추출에서 구조 분해보다 읽기 좋지만, 캡처가 많아지면 패턴이 스키마인지 추출기인지 모호해지므로 3개 이상이면 이름 있는 select 로 통일하는 편이 낫다.

## 4. P.infer 로 패턴에서 타입 추출 — 패턴을 스키마처럼

`P.infer<typeof pattern>` 은 패턴 정의로부터 "이 패턴이 매칭하는 값의 타입" 을 추출한다. 즉 패턴을 zod 스키마처럼 **단일 진실 공급원(single source of truth)** 으로 삼아, 런타임 검증(`isMatching`)과 정적 타입을 한 정의에서 동시에 얻는다.

```typescript
import { P, isMatching } from 'ts-pattern';

const userPattern = {
  id: P.string,
  name: P.string,
  age: P.number.optional(),
  role: P.union('admin', 'member'),
  tags: P.array(P.string),
} as const;

type User = P.infer<typeof userPattern>;
// { id: string; name: string; age?: number; role: 'admin' | 'member'; tags: string[] }

function parseUser(json: unknown): User {
  if (isMatching(userPattern, json)) return json; // json 이 User 로 좁혀짐
  throw new Error('invalid user payload');
}
```

`isMatching` 은 커링 형태(`isMatching(pattern)` 이 타입 가드 함수 반환)로도 쓸 수 있어 배열 `filter` 에 바로 넣기 좋다. 판별 유니온과 결합하면 variant 패턴을 개별 상수로 정의하고 `P.union(...)` 으로 합성한 뒤 `P.infer` 로 전체 유니온 타입을 뽑을 수 있어, match 케이스와 타입 정의가 같은 패턴 상수를 공유해 드리프트가 없다. 다만 zod 와 달리 **변환·정제·에러 메시지 수집** 기능이 없고 매칭 여부만 반환한다. 외부 입력의 본격적 검증 파이프라인에는 zod/valibot 이 적합하고, P.infer 는 신뢰 경계 안쪽 값의 구조 분기 + 타입 파생이라는 가벼운 용도가 실무적 경계선이다.

## 5. .exhaustive() 의 타입 레벨 구현 원리

`.exhaustive()` 의 핵심은 **매칭된 패턴을 입력 유니온에서 차례로 빼나가는(subtract) 누적 추론**이다. `match(value)` 는 `Match<Input, Output, HandledCases = []>` 형태의 빌더 타입을 만들고, `.with(pattern, handler)` 를 호출할 때마다 패턴이 처리하는 케이스 타입을 계산해 남은 유니온에서 제거한 새 빌더 타입을 반환한다. 개념적으로 다음과 같은 순환이다.

```typescript
// 개념 모델 (실제 구현은 DeepExclude / DistributeMatchingUnions 등으로 정교함)
type Remaining0 = Result;                                  // 초기: 전체 유니온
type Remaining1 = DeepExclude<Remaining0, { status: 'idle' }>;      // idle 제거
type Remaining2 = DeepExclude<Remaining1, { status: 'loading' }>;   // loading 제거
// ... 모든 with 소비 후
// Remaining === never  →  .exhaustive() 시그니처가 유효
// Remaining !== never  →  .exhaustive(): NonExhaustiveError<Remaining> 로 컴파일 에러
```

단순 `Exclude` 는 최상위 유니온 멤버 단위로만 빼므로, ts-pattern 은 중첩 판별자까지 다루는 `DeepExclude` 를 구현한다. 유니온을 매칭 가능한 조합으로 **분배(distribute)** 한 뒤(예: `{ a: 'x' | 'y', b: 1 | 2 }` 를 4개 조합으로 전개), 패턴을 뒤집은 타입(`InvertPattern`)과 서브타입 관계인 조합만 제거하는 방식이다. 남은 유니온이 `never` 가 아니면 `.exhaustive()` 반환 타입 위치에 `NonExhaustiveError<Remaining>` 이 나타나 **어떤 케이스가 누락되었는지** 잔여 타입이 에러 메시지에 그대로 표시된다. 이것이 switch + assertNever 대비 최대의 디버깅 이점이다. v5 부터 `.exhaustive()` 는 fallback 핸들러 인자를 받아, 타입상 불가능하지만 런타임에 유입된 값에 예외 대신 지정 동작을 수행하게 할 수 있다. trade-off: 이 분배 전개는 조합 폭발을 부를 수 있다. 판별자가 여럿이고 리터럴 수가 많으면 조합 수가 곱셈으로 늘어 tsc 검사 시간이 눈에 띄게 증가한다(8절 참조).

## 6. TC39 pattern matching 제안과 ECMAScript 표준화 동향

TC39 의 `proposal-pattern-matching` 은 `match (subject) { when pattern: expr; ... }` 형태의 **언어 내장 match 표현식**을 제안한다. 2018년 제안 이후 오랫동안 Stage 1 에 머물러 있으며, 챔피언 그룹이 문법(구 `when () {}` 블록 문법에서 표현식 지향 문법으로)과 커스텀 매처 프로토콜(`Symbol.customMatcher`) 을 여러 차례 재설계해 왔다. 제안이 다루는 범위는 구조 분해 패턴, 리터럴 패턴, 가드(`if`), 바인딩 캡처, `and`/`or`/`not` 결합자 등으로 ts-pattern 의 DSL 과 개념적으로 상당히 겹친다. ts-pattern README 도 스스로를 이 제안 문법에 대한 사전 실험이자 근접 구현으로 소개한다.

차이점도 분명하다. 언어 제안은 런타임 의미론만 정의하며 **exhaustiveness 검사는 범위 밖**이다(타입 계층인 TypeScript 의 몫). 반면 ts-pattern 은 exhaustive 검사와 캡처 타입 추론까지 제공하는 대신 라이브러리 함수 호출이라는 제약(엔진 최적화 불가, 번들 포함)을 진다. 2026년 초 기준으로도 이 제안은 초기 단계(Stage 1~2 수준 논의)로 보는 것이 안전하며, 프로덕션에서는 당분간 ts-pattern 이 실질적 대안이다. 도입 trade-off 는 "표준을 기다리며 switch 유지" 대 "지금 표현력을 얻고 표준화 시 마이그레이션 감수" 인데, match 는 지역적 표현식이라 케이스별 기계적 치환이 가능해 마이그레이션 리스크는 낮은 편이다.

## 7. 실전 적용: 액션 처리·API 분기·상태 머신·도메인 이벤트

**Redux 스타일 리듀서**에서는 액션 유니온을 `.exhaustive()` 로 닫아 액션 추가 시 리듀서 누락을 컴파일 타임에 강제한다. **API 응답 분기**에서는 HTTP 상태·바디 구조를 튜플로 묶어 한 번에 매칭하면 if 사다리가 사라진다. **상태 머신**에서는 `[state, event]` 튜플 매칭이 전이 테이블을 코드로 직역한 형태가 되어, 허용되지 않는 전이가 자연스럽게 `otherwise` 로 떨어진다.

```typescript
// 상태 머신: [현재 상태, 이벤트] 튜플 매칭
type State = { name: 'idle' } | { name: 'running'; job: string } | { name: 'done' };
type Ev = { type: 'START'; job: string } | { type: 'FINISH' } | { type: 'RESET' };

const transition = (s: State, e: Ev): State =>
  match<[State, Ev]>([s, e])
    .with([{ name: 'idle' }, { type: 'START' }], ([, ev]) => ({ name: 'running', job: ev.job }))
    .with([{ name: 'running' }, { type: 'FINISH' }], () => ({ name: 'done' }))
    .with([P._, { type: 'RESET' }], () => ({ name: 'idle' }))
    .otherwise(([cur]) => cur); // 정의되지 않은 전이는 현 상태 유지
```

NestJS 등 백엔드에서는 **도메인 이벤트 분기**에 잘 맞는다. 클래스별 `@EventsHandler` 를 늘리는 대신 단일 디스패처에서 `match(event).with({ type: 'OrderPlaced' }, ...)` 로 모으면 이벤트 추가 시 `.exhaustive()` 가 라우팅 누락을 잡고, 외부 웹훅 페이로드는 `P.infer` 패턴으로 구조 확인과 타입 부여를 동시에 처리해 `as` 단언을 없앤다. trade-off: 데코레이터 기반 분리는 파일 응집과 DI 를 얻는 대신 누락 검출이 없고, match 디스패처는 그 반대다. 이벤트가 10여 개를 넘으면 도메인별 서브 match 로 계층화하는 절충이 현실적이다.

## 8. 성능·번들 크기·타입 비용, 그리고 한계

런타임 관점에서 switch 는 엔진이 점프 테이블 수준으로 최적화할 수 있는 원시 분기인 반면, match 는 케이스마다 패턴 객체 순회 + 함수 호출을 수행한다. 마이크로벤치마크에서는 대체로 switch 가 수 배 이상 빠르게 측정되는 것으로 알려져 있으나 절대량은 호출당 수십 ns 안팎이어서 I/O·렌더링이 지배하는 일반 경로에서는 사실상 관측되지 않는다. 문제는 초당 수십만 회 호출되는 핫 루프(파서, 게임 틱, 스트림 변환)이며 이런 곳은 switch/if 로 남겨야 한다. 번들은 의존성 없는 단일 패키지로 minify+gzip 기준 약 2~3KB 대로 알려져 있고, ESM 이라 트리셰이킹은 되지만 `match` 와 `P` 가 사실상 한 덩어리여서 절감 폭은 작다. 타입 레벨 비용은 종종 런타임보다 중요하다. 5절의 유니온 분배·DeepExclude 는 타입 인스턴스화를 대량 유발하므로, 큰 유니온 + 깊은 패턴 + 다수의 `.with` 가 결합되면 편집기 반응성과 tsc 시간이 저하될 수 있다. 완화책은 match 지점 분할, `.returnType<T>()` 로 반환 타입 고정, 과도한 튜플 조합 매칭 회피다.

| 관점 | switch / if | ts-pattern match |
|------|-------------|------------------|
| 형태 | 문(statement) | 표현식(expression) |
| 분기 대상 | 단일 값 동등 비교 | 중첩 구조·튜플·술어·부정 |
| exhaustive | assertNever 수동 관용구 | `.exhaustive()` 일급 지원 + 잔여 타입 표시 |
| 런타임 비용 | 최소(엔진 최적화) | 패턴 순회 + 함수 호출 오버헤드 |
| 번들 | 0 | 약 2~3KB(gzip) 안팎 |
| 타입 검사 비용 | 낮음 | 유니온 분배로 증가 가능 |

한계도 명확하다. 첫째, **넓은 타입 입력에는 exhaustive 가 불가능하다.** 입력이 `string`·`number` 처럼 무한한 타입이면 유한 패턴 집합으로 소진할 수 없어 `.exhaustive()` 는 항상 에러이며 `.otherwise()` 로 닫아야 한다. 판별·리터럴 유니온으로 입력을 먼저 좁히는 설계가 전제다. 둘째, `.with()` 는 다수의 오버로드와 깊은 조건부 타입으로 구성되어, 제네릭 함수 안에서 미해결 타입 파라미터를 매칭하거나 패턴을 변수로 빼며 `as const` 를 누락하면 리터럴이 넓혀져(`'a'` → `string`) 추론이 실패하거나 `.exhaustive()` 가 소진을 인식하지 못한다. 셋째, `readonly` 배열·`unknown` 필드·재귀 타입이 얽히면 `ExtractPreciseValue` 가 기대보다 넓은 타입을 돌려주는 경계 사례가 이슈 트래커에 보고되어 있다. 실무 대응은 패턴을 인라인 리터럴로 유지하고, 제네릭 경계는 구체 타입으로 인스턴스화한 뒤 매칭하며, 추론이 무너진 지점에서만 `.returnType` 과 명시적 타입 인자로 고정하는 것이다. 요컨대 ts-pattern 은 "판별 유니온 도메인 모델 + 유한 케이스 분기" 영역에서는 switch 를 압도하지만, 입력 정규화·핫 패스·초대형 유니온에서는 전통적 분기와 스키마 라이브러리에 자리를 내주는 도구다.

## 참고
- ts-pattern GitHub 저장소: https://github.com/gvergnaud/ts-pattern
- ts-pattern README — API 레퍼런스(match, P, isMatching, P.infer): https://github.com/gvergnaud/ts-pattern#readme
- TC39 proposal-pattern-matching: https://github.com/tc39/proposal-pattern-matching
- TypeScript Handbook — Narrowing과 Exhaustiveness checking: https://www.typescriptlang.org/docs/handbook/2/narrowing.html
- Gabriel Vergnaud, "Bringing Pattern Matching to TypeScript": https://dev.to/gvergnaud/bringing-pattern-matching-to-typescript-introducing-ts-pattern-v3-0-o1k
