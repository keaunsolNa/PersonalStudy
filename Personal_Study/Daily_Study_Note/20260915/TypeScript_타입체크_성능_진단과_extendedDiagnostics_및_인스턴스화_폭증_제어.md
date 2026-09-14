Notion 원본: https://www.notion.so/3db5a06fd6d3813bb3b3efd61935cd62

# TypeScript 타입체크 성능 진단과 extendedDiagnostics 및 인스턴스화 폭증 제어

> 2026-09-15 신규 주제 · 확장 대상: TypeScript 타입 시스템 심화(Conditional Types, Mapped Types, Variadic Tuple)

## 학습 목표

- `--extendedDiagnostics` 출력 항목을 읽고 체크 시간의 병목 단계를 특정한다
- `--generateTrace` 와 `@typescript/analyze-trace` 로 느린 타입·파일을 정량 지목한다
- 인스턴스화 폭증(instantiation explosion)을 만드는 코드 패턴을 재작성으로 제거한다
- CI 에 타입체크 예산을 걸어 회귀를 빌드 실패로 잡는다

## 1. 타입체크가 느려지는 근본 이유

TypeScript 의 타입 시스템은 구조적(structural)이다. 이름이 아니라 모양으로 호환성을 판단하므로, `A` 가 `B` 에 할당 가능한지 확인하려면 두 타입의 멤버를 재귀적으로 짝지어 비교해야 한다. 명목적 타입 시스템(Java 의 `implements`)은 선언 하나만 확인하면 끝나지만, 구조적 시스템은 매 비교마다 구조를 걷는다.

여기에 제네릭이 붙으면 비용이 곱해진다. `Foo<T>` 를 실제 타입 인자로 사용할 때마다 체커는 새 타입 객체를 만든다. 이것이 **인스턴스화(instantiation)** 다. 제네릭 함수 하나를 호출하면 파라미터·반환·제약 조건이 각각 인스턴스화되고, 그 안에 조건부 타입이 있으면 분기마다 또 인스턴스화된다. 대부분의 "타입스크립트가 느리다"는 사례는 파일 수가 아니라 이 인스턴스화 수가 지배한다.

체커는 무한 재귀를 막기 위해 내부 상한을 둔다. 버전에 따라 상수는 바뀜지만 현재 계열 기준으로 인스턴스화 깊이 100, 누적 인스턴스화 500만 건을 넘으면 `TS2589: Type instantiation is excessively deep and possibly infinite` 를 던진다. 즉 이 에러는 "코드가 틀렸다"가 아니라 "체커 예산을 다 썼다"는 신호이며, 그 직전 구간은 이미 몇 초씩 걸리고 있다고 봐야 한다.

한 가지 더 중요한 성질은 **지연 평가**다. 타입은 실제로 필요해질 때까지 계산되지 않는다. 그래서 `tsc --noEmit` 전체 체크는 느린데 에디터는 빠르거나, 그 반대가 나타난다. 에디터는 열린 파일 주변만 계산하고 CI 는 전부 계산한다. 성능 측정은 반드시 CI 와 동일한 조건(`tsc -p tsconfig.json --noEmit`)에서 해야 한다.

## 2. 1차 측정: --extendedDiagnostics

가장 먼저 할 일은 숫자를 확보하는 것이다.

```bash
npx tsc -p tsconfig.json --noEmit --extendedDiagnostics
```

출력은 대략 이렇게 나온다.

```
Files:                         1843
Lines of TypeScript:          312094
Nodes:                       1402883
Identifiers:                  482011
Symbols:                      690244
Types:                        214877
Instantiations:              9482113
Memory used:                1842301K
Parse time:                     2.31s
Bind time:                      0.94s
Check time:                    41.62s
Emit time:                      0.00s
Total time:                    44.87s
```

읽는 법은 다음과 같다.

| 항목 | 의미 | 이상 징후 판단 |
|---|---|---|
| Files | 프로그램에 포함된 파일 수 | 의도보다 크면 `include`/`types` 범위 오염 |
| Types | 생성된 타입 객체 수 | Instantiations 대비 작으면 정상 |
| Instantiations | 제네릭 인스턴스화 누적 | 500만 초과면 사실상 경보, 1000만 초과면 재작성 대상 |
| Memory used | 힙 사용량 | 3GB 근처면 OOM 위험, `--max-old-space-size` 조정 필요 |
| Parse time | 디스크 I/O + 파싱 | 비중이 크면 파일 수·`skipLibCheck` 문제 |
| Check time | 타입 검사 | 전체의 80% 이상이면 타입 설계 문제 |

경험적 기준선은 **Instantiations / Lines 비율**이다. 평범한 애플리케이션 코드는 라인당 10~30 수준이고, 타입 곡예가 많은 코드베이스는 100을 넘는다. 위 예시는 312,094 라인에 948만 인스턴스화이므로 라인당 30, 경계선이다. 이 비율은 파일을 추가해도 크게 변하지 않아야 정상이고, 특정 PR 이후 두 배가 됐다면 그 PR 이 범인이다.

Parse time 이 유독 크다면 타입 설계가 아니라 파일 범위 문제다. `tsc --listFiles` 로 실제 포함 목록을 뽑아 `node_modules/@types` 전체가 딸려 들어오는지 확인한다.

```bash
npx tsc -p tsconfig.json --noEmit --listFiles | grep node_modules | wc -l
```

`compilerOptions.types` 를 명시하지 않으면 `@types` 아래 모든 패키지가 자동 포함된다. 실제로 쓰는 것만 나열하면 수백 개 `.d.ts` 파싱이 사라진다.

```jsonc
{
  "compilerOptions": {
    "types": ["node", "vitest/globals"],
    "skipLibCheck": true
  }
}
```

`skipLibCheck: true` 는 의존성 `.d.ts` 내부 정합성 검사를 건너뛰다. 남의 타입 버그를 우리 CI 에서 잡을 이유가 없으므로 사실상 기본값으로 켜는 편이 낫다. 대신 우리가 배포하는 `.d.ts` 는 별도 파이프라인에서 검증한다.

## 3. 2차 측정: --generateTrace 와 analyze-trace

`extendedDiagnostics` 는 총량만 알려준다. **어디가** 느린지는 트레이스를 떠야 한다.

```bash
npx tsc -p tsconfig.json --noEmit --generateTrace .trace
npx @typescript/analyze-trace .trace
```

`.trace` 디렉터리에는 `trace.json`(Chrome Trace Event 포맷)과 `types.json`(타입 ID → 구조 매핑)이 생성된다. `trace.json` 은 Perfetto UI 나 `chrome://tracing` 에 드래그해서 플레임 그래프로 볼 수 있고, `analyze-trace` 는 같은 데이터를 텍스트 핫스팟 리포트로 요약한다.

리포트는 이런 모양이다.

```
Hot Spots
└─ Check file /src/api/routes.ts (12.41s)
   └─ Check expression at (88,17) (11.93s)
      └─ Compare types 481203 and 481199 (11.71s)
         └─ Compare types 480118 and 479994 (10.02s)
            └─ Determine variance of type 479812 (9.88s)
```

여기서 숫자는 `types.json` 의 타입 ID 다. 다음처럼 실제 타입을 역추적한다.

```bash
node -e '
const types = require("./.trace/types.json");
const t = types.find(t => t.id === 481203);
console.log(JSON.stringify(t, null, 2));
'
```

실전에서 반복적으로 나타나는 핫스팟 유형은 셋이다.

1. **Compare types** 가 지배 — 거대한 유니온/교차 타입을 할당 검사하고 있다. 유니온 크기 N, M 의 비교는 최악 N×M 이다.
2. **Determine variance** 가 지배 — 재귀적으로 자기를 참조하는 제네릭 인터페이스(예: 트리 노드)의 분산 계산이다.
3. **Structured type check** 가 특정 한 파일에 몰림 — 그 파일 하나가 전체 체크 시간의 대부분이므로 우선 그 파일만 고치면 된다.

트레이스는 파일당 수백 MB 가 되기도 한다. CI 에서 상시로 뜨지 말고, 회귀가 감지됐을 때 로컬에서 재현하는 용도로 쓴다.

## 4. 패턴 1 — 유니온 폭발과 분산 조건부 타입

가장 흔한 폭발원은 분산 조건부 타입(distributive conditional type)이 큰 유니온을 만나는 경우다.

```ts
// 느림: T 가 200개 멤버 유니온이면 200번 분산 인스턴스화된다
type Route<T extends string> = T extends `${infer M} ${infer P}`
  ? { method: M; path: P; params: ExtractParams<P> }
  : never;

type ExtractParams<P extends string> = P extends `${string}:${infer Name}/${infer Rest}`
  ? { [K in Name]: string } & ExtractParams<Rest>
  : P extends `${string}:${infer Name}`
    ? { [K in Name]: string }
    : {};

type AllRoutes = Route<typeof routeStrings[number]>; // 200개 라우트
```

`ExtractParams` 는 재귀이고, 결과가 교차 타입(`&`)으로 누적된다. 교차 타입은 비교 시 양쪽 구성원을 모두 펼쳐야 해서 비용이 크다. 두 가지를 바꾼다.

```ts
// 1) 교차 누적 → 단일 매핑 타입으로 평탄화
type ParamNames<P extends string> = P extends `${string}:${infer N}/${infer R}`
  ? N | ParamNames<R>
  : P extends `${string}:${infer N}`
    ? N
    : never;

type ExtractParams<P extends string> = { [K in ParamNames<P>]: string };

// 2) 분산을 의도적으로 끔 — 유니온 전체를 한 번만 다룬다
type Route<T extends string> = [T] extends [`${infer M} ${infer P}`]
  ? { method: M; path: P }
  : never;
```

핵심은 두 가지다. 첫째, **교차 타입을 누적하지 말고 유니온을 모아 마지막에 한 번 매핑**한다. 유니온 합집합은 정규화가 싸고 교차는 비싸다. 둘째, 분산이 필요 없으면 `[T] extends [U]` 형태로 튜플 래핑해 분산을 끈다. 실측에서 200 라우트 기준 인스턴스화가 180만 → 9만 수준으로 떨어지는 경우가 흔하다.

## 5. 패턴 2 — 재귀 조건부 타입과 꼬리 재귀 최적화

TypeScript 4.5 부터 조건부 타입의 꼬리 재귀가 최적화되어, 결과가 곧바로 다음 조건부 타입인 형태는 스택을 쌓지 않고 최대 1000회까지 반복한다. 반대로 꼬리 위치가 아니면 깊이 50 근처에서 이미 느려지고 100 근처에서 `TS2589` 가 난다.

```ts
// 나쁨: 재귀 결과가 튜플 스프레드 안에 들어가므로 꼬리 위치가 아니다
type Reverse<T extends unknown[]> = T extends [infer H, ...infer R]
  ? [...Reverse<R>, H]
  : [];

// 좋음: 누산기(accumulator)를 넘겨 재귀 호출을 꼬리 위치로 옮긴다
type Reverse<T extends unknown[], Acc extends unknown[] = []> =
  T extends [infer H, ...infer R] ? Reverse<R, [H, ...Acc]> : Acc;
```

누산기 패턴은 타입 수준 재귀의 표준 관용구다. `Join`, `Split`, `Replace`, `Repeat` 계열을 직접 구현했다면 전부 이 형태로 바꿀 수 있다.

그리고 실무적으로 더 중요한 판단은 **재귀 타입을 아예 쓰지 말지 결정하는 것**이다. 문자열 경로에서 파라미터를 뽑는 타입은 멋있지만, 라우트 테이블을 `as const` 객체로 선언하고 파라미터를 명시하면 인스턴스화가 0 에 수렴한다.

```ts
export const routes = {
  getUser: { path: "/users/:id", params: { id: "" } },
  listPosts: { path: "/users/:id/posts", params: { id: "" } },
} as const satisfies Record<string, { path: string; params: Record<string, string> }>;

type Params<K extends keyof typeof routes> = keyof (typeof routes)[K]["params"];
```

타입 수준 파싱으로 얻는 것은 "경로 문자열 하나만 쓰면 된다"는 편의이고, 잃는 것은 전체 팀의 에디터 반응성이다. 대부분의 코드베이스에서 이 교환은 손해다.

## 6. 패턴 3 — 인터페이스와 타입 별칭, 그리고 명시적 반환 타입

체커는 인터페이스 간 할당 검사 결과를 관계 캐시에 저장한다. 이름이 붙은 선언이므로 캐시 키가 안정적이다. 반면 즉석에서 만들어진 교차 타입은 매번 새 구조를 만들어 캐시 적중률이 낮다.

```ts
// 느림
type Props = BaseProps & StyleProps & A11yProps;

// 빠름 — 캐시 가능한 이름 있는 선언
interface Props extends BaseProps, StyleProps, A11yProps {}
```

`interface extends` 는 멤버 충돌 시 컴파일 에러를 내지만 교차 타입은 `never` 멤버를 조용히 만들기 때문에, 성능과 별개로 정확성 면에서도 낫다.

두 번째로 큰 효과는 **내보내는 함수에 반환 타입을 명시**하는 것이다. 반환 타입이 없으면 체커는 함수 본문 전체를 추론해야 하고, 그 추론 결과가 또 다른 추론의 입력이 되면서 연쇄한다. 특히 선언 파일 생성(`declaration: true`)이 켜져 있으면 추론된 거대 타입이 그대로 `.d.ts` 에 직렬화되어 소비 측 체크까지 느려진다.

```ts
// 추론 결과가 수백 줄짜리 익명 타입이 될 수 있다
export function createClient(config: Config) {
  return { ...buildResources(config), ...buildInterceptors(config) };
}

// 명시하면 추론 연쇄가 이 지점에서 끊긴다
export function createClient(config: Config): ApiClient {
  return { ...buildResources(config), ...buildInterceptors(config) };
}
```

TypeScript 5.5 의 `isolatedDeclarations: true` 는 이 규칙을 컴파일러가 강제하게 만든다. 켜면 내보내는 모든 심볼에 명시적 타입 애너테이션을 요구하고, 그 대가로 `.d.ts` 를 타입 체크 없이 파일 단위로 병렬 생성할 수 있다. 라이브러리 패키지라면 도입 가치가 크다.

## 7. 프로젝트 수준 구조 조정

단일 `tsconfig.json` 으로 수십만 라인을 검사하면 캐시 무효화 단위가 전체가 된다. 파일 하나 고쳐도 전부 다시 검사한다.

```jsonc
// tsconfig.json — 루트는 참조만 갖는 솔루션 파일
{
  "files": [],
  "references": [
    { "path": "./packages/core" },
    { "path": "./packages/api" },
    { "path": "./packages/web" }
  ]
}
```

```jsonc
// packages/core/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "incremental": true,
    "tsBuildInfoFile": "./node_modules/.cache/tsc/core.tsbuildinfo",
    "declaration": true,
    "declarationMap": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
```

`composite: true` 는 각 프로젝트가 자기 `.d.ts` 를 산출하게 하고, 상위 프로젝트는 소스 대신 그 `.d.ts` 만 읽는다. `tsc --build` 는 `.tsbuildinfo` 의 파일 해시를 비교해 변경된 프로젝트만 재빌드한다.

추가로 `assumeChangesOnlyAffectDirectDependencies: true` 는 파일 변경의 영향 범위를 직접 의존자까지만으로 제한한다. 정확성을 일부 희생하는 옵션이므로 에디터/로컬 워치에서만 켜고 CI 는 끄는 구성이 안전하다.

| 설정 | 효과 | 대가 |
|---|---|---|
| `skipLibCheck: true` | 의존성 `.d.ts` 검사 생략, Parse/Check 대폭 감소 | 라이브러리 타입 오류를 못 잡음 |
| `composite` + `--build` | 변경 프로젝트만 재검사 | 패키지 경계 설계 필요, 최초 빌드는 더 느림 |
| `isolatedDeclarations` | `.d.ts` 병렬 생성, 추론 연쇄 차단 | 모든 export 에 애너테이션 강제 |
| `assumeChangesOnlyAffectDirectDependencies` | 워치 모드 재검사 범위 축소 | 간접 의존자의 오류를 놓칠 수 있음 |
| `types` 명시 | 불필요한 `@types` 파싱 제거 | 전역 타입 누락 시 수동 추가 |

## 8. CI 에 예산 걸기

측정은 회귀를 막을 때 의미가 있다. `extendedDiagnostics` 출력을 파싱해 임계치를 넘으면 실패시킨다.

```js
// scripts/check-type-budget.mjs
import { execSync } from "node:child_process";

const BUDGET = {
  instantiations: 6_000_000,
  checkTimeSec: 35,
  memoryKB: 2_600_000,
};

const out = execSync("npx tsc -p tsconfig.json --noEmit --extendedDiagnostics", {
  encoding: "utf8",
});

const num = (label) => {
  const m = out.match(new RegExp(`${label}:\\s+([\\d.]+)`));
  if (!m) throw new Error(`metric not found: ${label}`);
  return Number(m[1]);
};

const actual = {
  instantiations: num("Instantiations"),
  checkTimeSec: num("Check time"),
  memoryKB: num("Memory used"),
};

let failed = false;
for (const [key, limit] of Object.entries(BUDGET)) {
  const value = actual[key];
  const over = value > limit;
  if (over) failed = true;
  console.log(`${over ? "FAIL" : "ok  "} ${key}: ${value} (budget ${limit})`);
}

process.exit(failed ? 1 : 0);
```

```yaml
# .github/workflows/typecheck.yml
- name: Type budget
  run: node scripts/check-type-budget.mjs
```

시간 지표는 러너 성능에 흔들리므로 임계치를 넉넉히 잡고, **인스턴스화 수를 주 지표로 쓴다**. 이 값은 하드웨어와 무관하게 결정적(deterministic)이라 회귀 탐지에 적합하다. 예산을 올려야 하는 PR 은 리뷰에서 근거를 요구하면 자연스럽게 방어선이 된다.

## 9. 진단 순서 정리

실전 워크플로는 다음 순서가 효율적이다.

1. `--extendedDiagnostics` 로 총량 확보. Parse 비중이 크면 `types`/`include` 부터 손본다.
2. Check 비중이 크고 Instantiations 가 라인당 50 을 넘으면 `--generateTrace` + `analyze-trace`.
3. 핫스팟 파일이 하나로 몰리면 그 파일의 조건부·교차 타입을 먼저 본다. 여러 파일에 퍼져 있으면 공용 타입 유틸리티가 원인이다.
4. 교차 누적 → 매핑 타입 평탄화, 비꼬리 재귀 → 누산기 패턴, 익명 반환 타입 → 명시적 애너테이션 순으로 고친다.
5. 그래도 부족하면 구조 조정(`composite`, 프로젝트 분할)으로 캐시 무효화 단위를 줄인다.
6. 고친 수치를 CI 예산으로 고정한다.

중요한 판단 기준 하나를 남겨둔다. 타입 수준 프로그래밍으로 얻는 안전성은 런타임 검증(예: 스키마 라이브러리)으로도 상당 부분 얻을 수 있고, 후자는 체크 시간을 소비하지 않는다. 컴파일 타임 정교함과 개발 루프 속도가 충돌하면, 팀 규모가 클수록 속도 쪽이 총비용이 낮은 경우가 많다.

## 참고

- TypeScript Wiki — Performance (github.com/microsoft/TypeScript/wiki/Performance)
- TypeScript Wiki — Performance Tracing (github.com/microsoft/TypeScript/wiki/Performance-Tracing)
- `@typescript/analyze-trace` 패키지 문서 (github.com/microsoft/typescript-analyze-trace)
- TypeScript 4.5 릴리스 노트 — Tail-Recursion Elimination on Conditional Types
- TypeScript 5.5 릴리스 노트 — Isolated Declarations
- TSConfig Reference — `composite`, `incremental`, `skipLibCheck`, `assumeChangesOnlyAffectDirectDependencies`
