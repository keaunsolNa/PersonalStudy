Notion 원본: https://www.notion.so/3715a06fd6d381329435db44b0cee5dc

# TypeScript tsconfig strict 9옵션 deep dive와 exactOptionalPropertyTypes / noUncheckedIndexedAccess

> 2026-05-31 신규 주제 · 확장 대상: TypeScript Module Resolution (20260526) / Compiler API (20260528)

## 학습 목표

- `strict: true` 가 묶어주는 9개 세부 옵션이 각각 무엇을 검사하는지 식별하고, 개별로 끄거나 켤 때의 안전성 차이를 평가한다
- `exactOptionalPropertyTypes` 가 `?` optional 과 `| undefined` union 의 의미 차이를 어떻게 강제하는지 코드로 재현한다
- `noUncheckedIndexedAccess` 의 배열·Record 접근 시 추론 결과 변화를 이해하고 운영에서 가드 패턴을 정한다
- 점진적 도입 전략(per-file disable, `// @ts-expect-error`, 마이그레이션 단계)을 수립한다

## 1. strict 가 묶어주는 9개 옵션

| 옵션 | 활성화 시점 | 핵심 검사 |
|---|---|---|
| noImplicitAny | TS 1.0 | 추론 실패 시 any 차단 |
| strictNullChecks | TS 2.0 | null/undefined 분리 |
| strictFunctionTypes | TS 2.6 | 함수 파라미터 반공변 정확 평가 |
| strictBindCallApply | TS 3.2 | bind/call/apply 인자 체크 |
| strictPropertyInitialization | TS 2.7 | 클래스 필드 초기화 확인 |
| noImplicitThis | TS 2.0 | this 추론 실패 시 에러 |
| useUnknownInCatchVariables | TS 4.4 | catch e 를 unknown 으로 |
| alwaysStrict | TS 2.1 | "use strict" 자동 |
| strictBuiltinIteratorReturn | TS 5.6 | IteratorResult narrowing |

별도 옵션 — `exactOptionalPropertyTypes`, `noUncheckedIndexedAccess`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noFallthroughCasesInSwitch`, `noUncheckedSideEffectImports`.

```jsonc
{ "compilerOptions": {
    "strict": true,
    "exactOptionalPropertyTypes": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "noPropertyAccessFromIndexSignature": true,
    "noFallthroughCasesInSwitch": true
}}
```

## 2. noImplicitAny

```ts
function add(a, b) { return a + b }        // error
function add(a: number, b: number) { ... }  // OK
function tmp(x: any) { ... }                // OK (explicit any)
```

## 3. strictNullChecks

```ts
function len(s: string | null) {
  return s.length          // error: Object is possibly null
}
function lenOk(s: string | null) {
  if (s == null) return 0
  return s.length          // narrowed to string
}
```

## 4. exactOptionalPropertyTypes

`?` 는 "키가 없을 수 있다(missing)" 만 의미. `{ x: undefined }` 를 `A` 에 대입 차단.

```ts
interface User { name: string; email?: string }
const u1: User = { name: 'a' }                          // OK (missing)
const u2: User = { name: 'a', email: 'x@y' }            // OK
const u3: User = { name: 'a', email: undefined }        // ERROR
```

## 5. noUncheckedIndexedAccess

```ts
const arr = ['a', 'b', 'c']
const a = arr[0]                  // string | undefined
a.toUpperCase()                   // error: possibly undefined
arr[0]?.toUpperCase()             // OK
```

Record 도 영향. `Record<string, number>['hits']` → `number | undefined`.

## 6. strictPropertyInitialization

```ts
class UserService {
  private repo!: UserRepository           // injected later
  private cache = new Map<string, User>() // initialized inline
  private logger: Logger                  // ERROR if no ctor assign
  constructor(logger: Logger) { this.logger = logger }
}
```

## 7. 보조 옵션

`noImplicitOverride` 가 `override` 키워드 강제, `noPropertyAccessFromIndexSignature` 가 인덱스 시그니처의 점 접근 차단, `noFallthroughCasesInSwitch` 가 break 누락 차단.

```ts
class Sub extends Base {
  override greet() {}     // OK
  override foo() {}       // ERROR if Base.foo 가 없음
}
```

## 8. useUnknownInCatchVariables

```ts
try { await fetchOrder(id) }
catch (e) {
  if (e instanceof ApiError) console.log(e.statusCode)
  else if (e instanceof Error) console.log(e.message)
  else console.log(String(e))
}

function isApi(e: unknown): e is ApiError {
  return typeof e === 'object' && e != null && 'statusCode' in e
}
```

## 9. 마이그레이션 전략

| 단계 | 옵션 | 권장 시점 |
|---|---|---|
| 1 | noImplicitAny, alwaysStrict | 프로젝트 초기 |
| 2 | strictNullChecks | 테스트 커버리지 후 |
| 3 | strictFunctionTypes, strictBindCallApply | 함수형 코드 시 |
| 4 | strictPropertyInitialization | NestJS/Angular |
| 5 | useUnknownInCatchVariables | TS 4.4+ |
| 6 | noUncheckedIndexedAccess | ETL 시 |
| 7 | exactOptionalPropertyTypes | API 경계 명확 후 |
| 8 | noImplicitOverride / noFallthroughCasesInSwitch | 마지막 |

`@ts-expect-error` 는 해당 라인이 더 이상 에러를 내지 않으면 역으로 에러 — 임시 회피 영구화 방지.

CI — `tsc --noEmit --incremental --tsBuildInfoFile .tsbuildinfo` 로 CI 타임 절반 이하 (5분 → 90초 사례).

## 참고

- TypeScript Handbook — Compiler Options https://www.typescriptlang.org/tsconfig
- TypeScript 4.4 Release Notes — useUnknownInCatchVariables / exactOptionalPropertyTypes
- TypeScript 5.6 Release Notes — strictBuiltinIteratorReturn
- Matt Pocock, "Total TypeScript — strict mode migration guide" https://www.totaltypescript.com
