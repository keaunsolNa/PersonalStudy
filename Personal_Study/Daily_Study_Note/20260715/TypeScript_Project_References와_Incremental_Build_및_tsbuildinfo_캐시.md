Notion 원본: https://app.notion.com/p/39e5a06fd6d381fe9994ff875a052823

# TypeScript Project References와 Incremental Build 및 tsbuildinfo 캐시

> 2026-07-15 신규 주제 · 확장 대상: TypeScript(모듈 해석 전략 / Compiler API)

## 학습 목표

- `composite` 프로젝트와 `references` 로 모노레포 빌드 그래프를 구성한다
- `tsc --build` 의 up-to-date 판정 알고리즘과 `.tsbuildinfo` 구조를 분석한다
- `declaration` / `declarationMap` 이 프로젝트 경계에서 하는 역할을 구분한다
- 증분 빌드가 무효화되는 실제 원인을 `--diagnostics` 로 측정해 좁힌다

## 1. 단일 tsconfig 의 한계

모노레포에서 패키지 5개를 하나의 `tsconfig.json` 으로 컴파일하면 `tsc` 는 매번 전체 프로그램을 다시 만든다. 파일 하나를 고쳐도 모든 소스의 AST 를 파싱하고, 전체 타입 그래프를 다시 체크한다. 파일 3,000개 규모에서 full check 가 40초라면, 한 줄 수정에도 40초를 낸다.

문제의 본질은 `tsc` 가 "프로그램" 단위로 동작한다는 점이다. 프로그램은 루트 파일 집합에서 import 그래프를 따라 도달 가능한 모든 파일이며, 타입 체커는 이 집합 전체에 대해 하나의 심볼 테이블을 만든다. 경계가 없으니 캐시할 단위도 없다.

Project References 는 이 프로그램을 **여러 개의 독립 프로그램으로 쪼개고**, 프로그램 간 인터페이스를 `.d.ts` 로 고정한다. 하위 프로젝트가 바뀌지 않았다면 상위 프로젝트는 그 `.d.ts` 만 읽으면 되고, 하위의 소스는 파싱조차 하지 않는다.

## 2. composite 프로젝트의 계약

`composite: true` 를 켜면 TypeScript 는 그 프로젝트를 "다른 프로젝트가 참조할 수 있는 단위"로 승격시키고, 대신 몇 가지 제약을 강제한다.

```jsonc
// packages/core/tsconfig.json
{
  "compilerOptions": {
    "composite": true,          // 아래 3개를 강제로 켬
    "declaration": true,        // 강제. 끄면 에러
    "declarationMap": true,     // 권장(강제 아님)
    "incremental": true,        // composite 이면 기본 true
    "rootDir": "src",           // 명시 필요. 없으면 추론되며 경고 대상
    "outDir": "dist",
    "tsBuildInfoFile": "dist/.tsbuildinfo"
  },
  "include": ["src/**/*"]
}
```

강제되는 규칙은 세 가지다.

첫째, **`declaration: true` 필수**. 참조하는 쪽이 소스가 아니라 `.d.ts` 를 소비해야 하므로 산출물이 반드시 있어야 한다. 둘째, **모든 입력 파일이 `include`/`files` 에 나열**되어야 한다. 프로그램 안에서 우연히 import 되어 딸려 들어온 파일은 허용되지 않는다. 이는 빌드 그래프를 정적으로 확정하기 위한 조건이다. 셋째, **`rootDir` 아래에 모든 입력이 있어야** 한다. 출력 경로를 결정적으로 계산하기 위해서다.

참조하는 쪽은 `references` 로 의존을 선언한다.

```jsonc
// packages/api/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "outDir": "dist",
    "rootDir": "src"
  },
  "references": [
    { "path": "../core" }        // 디렉터리 또는 tsconfig 파일 경로
  ],
  "include": ["src/**/*"]
}
```

이 선언의 효과는 두 가지다. `tsc --build packages/api` 는 `../core` 를 먼저 빌드하고, `api` 안에서 `import { X } from "@repo/core"` 를 해석할 때 `core/src/x.ts` 가 아니라 `core/dist/x.d.ts` 로 **리다이렉트**한다. 이 리다이렉션이 핵심이다. 소스로 해석하면 프로그램 경계가 무너지고, 증분 이득이 사라진다.

## 3. paths 와 references 를 함께 쓸 때의 함정

모노레포에서는 보통 `paths` 매핑도 함께 쓴다. 여기서 자주 나오는 실수가 `paths` 를 소스로 겨냥하는 것이다.

```jsonc
// ❌ references 를 무력화하는 설정
{
  "compilerOptions": {
    "paths": { "@repo/core": ["../core/src/index.ts"] }
  },
  "references": [{ "path": "../core" }]
}
```

`paths` 가 소스 파일을 직접 가리키면 `tsc` 는 `core` 의 소스를 `api` 프로그램 안으로 끌어들인다. references 는 빌드 순서만 정하고, 타입 해석은 소스로 가버린다. 결과적으로 `api` 를 체크할 때마다 `core` 전체가 다시 체크된다.

올바른 형태는 산출물을 가리키거나, `paths` 자체를 생략하고 `package.json` 의 `exports` 로 해결하는 것이다.

```jsonc
// ✅ 산출물을 가리킴
{
  "compilerOptions": {
    "paths": { "@repo/core": ["../core/dist/index.d.ts"] }
  },
  "references": [{ "path": "../core" }]
}
```

리다이렉션이 제대로 걸렸는지는 `--traceResolution` 으로 확인한다. 출력에 `Resolving ... to ../core/dist/index.d.ts` 가 보이면 정상이고, `../core/src/index.ts` 가 보이면 경계가 새고 있다.

```bash
tsc -p packages/api --traceResolution | grep -A2 "@repo/core"
```

## 4. tsc --build 의 up-to-date 판정

`tsc --build`(줄여서 `tsc -b`)는 일반 `tsc` 와 다른 프로그램이다. 참조 그래프를 위상 정렬하고, 각 프로젝트에 대해 "다시 빌드해야 하는가"를 판정한다.

판정은 대략 다음 순서다.

1. 출력물(`.js`, `.d.ts`, `.tsbuildinfo`)이 하나라도 없으면 → 빌드
2. 입력 파일 중 하나라도 mtime 이 가장 오래된 출력물보다 새로우면 → 빌드
3. 참조하는 프로젝트의 출력물 mtime 이 내 출력물보다 새로우면 → 빌드
4. `tsbuildinfo` 안의 옵션 해시가 현재 옵션과 다르면 → 빌드
5. 그 외 → up-to-date, 스킵

여기서 3번이 중요하다. `core` 를 재빌드했는데 `.d.ts` **내용이 동일하면**, TypeScript 는 파일을 다시 쓰지 않는다(내용 비교 후 skip). mtime 이 갱신되지 않으므로 `api` 는 스킵된다. 이것이 "구현만 바꾸고 시그니처는 그대로"인 커밋에서 상위 프로젝트 빌드가 통째로 생략되는 이유다.

반대로 `.d.ts` 가 한 글자라도 바뀌면 하위 그래프 전체가 재빌드된다. 그래서 **public 타입 표면을 좁게 유지하는 것**이 빌드 속도에 직접 영향을 준다. 내부 헬퍼를 `export` 하지 않는 것만으로도 `.d.ts` 변동 빈도가 줄어든다.

```bash
tsc -b packages/api --dry          # 무엇을 빌드할지만 출력
tsc -b packages/api --verbose      # up-to-date 판정 이유를 출력
```

`--verbose` 출력 예시:

```
Project 'packages/core/tsconfig.json' is up to date because newest input
  'src/index.ts' is older than output '.tsbuildinfo'
Project 'packages/api/tsconfig.json' is out of date because output
  'dist/index.js' is older than input 'src/route.ts'
```

이 두 줄이 증분 빌드 디버깅의 시작점이다. 예상과 다른 프로젝트가 out of date 로 뜨면 원인이 mtime 인지 옵션 해시인지 바로 드러난다.

## 5. .tsbuildinfo 의 내부 구조

`.tsbuildinfo` 는 JSON 이며(포맷은 비공개·버전 종속) 대략 다음 정보를 담는다.

| 필드 | 의미 |
|---|---|
| `fileNames` | 프로그램의 모든 파일 경로 배열. 이후 필드는 이 배열의 인덱스로 참조 |
| `fileInfos` | 각 파일의 내용 해시(version), `signature`(.d.ts 해시), affectsGlobalScope 여부 |
| `referencedMap` | 파일 → 그 파일이 import 하는 파일 인덱스 목록 |
| `exportedModulesMap` | 파일 → 그 파일의 .d.ts 가 참조하는 파일 목록 |
| `semanticDiagnosticsPerFile` | 파일별 캐시된 의미 진단 |
| `options` | 컴파일 옵션 스냅샷. 하나라도 다르면 전체 무효화 |

증분 체크의 알고리즘은 이렇다. 파일 A 의 `version`(내용 해시)이 바뀌면, A 를 다시 체크해 새 `signature`(A 의 `.d.ts` 해시)를 계산한다. **signature 가 그대로면 거기서 멈춘다.** signature 가 바뀌었으면 `referencedMap` 을 역추적해 A 를 import 하는 파일들을 다시 체크하고, 같은 판정을 재귀적으로 반복한다.

이 때문에 함수 본문만 고친 수정은 signature 가 불변이라 전파가 즉시 멈추고, 리턴 타입을 고친 수정은 의존자 전체로 번진다. `affectsGlobalScope` 가 true 인 파일(전역 `declare` 가 있는 파일)은 **모든 파일을 무효화**한다. 전역 타입 선언을 자주 고치는 프로젝트가 증분 빌드 이득을 못 보는 이유가 이것이다.

```ts
// globals.d.ts — affectsGlobalScope: true
declare global {
  interface Window { __APP__: unknown }
}
export {};
```

이 파일을 건드리면 `.tsbuildinfo` 가 있어도 전체 재체크가 일어난다. 전역 선언은 거의 바뀌지 않는 별도 패키지로 분리하는 것이 정석이다.

## 6. 옵션 해시로 인한 전체 무효화

`options` 필드는 컴파일러 옵션 전체의 스냅샷이다. `strict` 하나만 바뀌어도, `target` 이 ES2020 → ES2022 로 바뀌어도 캐시 전체가 무효화된다. 실무에서 이게 문제를 일으키는 전형적 시나리오는 CI 다.

```bash
# 로컬
tsc -b

# CI — --sourceMap 추가
tsc -b --sourceMap
```

옵션이 다르므로 CI 가 캐시를 복원해도 첫 빌드에서 통째로 무효화된다. CI 캐시 히트를 원한다면 **로컬과 CI 의 옵션을 완전히 일치**시키고, 차이가 필요하면 별도 tsconfig(`tsconfig.ci.json`)와 별도 `tsBuildInfoFile` 을 쓴다.

또 하나의 함정은 경로다. `.tsbuildinfo` 는 상대 경로를 저장하지만, CI 의 체크아웃 경로가 다르면 일부 절대 경로(예: `typeRoots` 로 들어온 `node_modules/@types`)가 어긋난다. Docker 빌드에서 워크스페이스 경로를 고정하는 것이 캐시 히트율에 유의미하게 기여한다.

## 7. declarationMap 과 IDE 경험

`declarationMap: true` 는 `.d.ts` 옆에 `.d.ts.map` 을 생성한다. 이 맵이 없으면 IDE 에서 `core` 의 심볼을 "Go to Definition" 했을 때 `dist/index.d.ts` 로 점프한다 — 읽기 전용 산출물이라 편집이 불가능하다.

맵이 있으면 IDE 가 `.d.ts` → 원본 `src/index.ts` 로 역매핑해 실제 소스로 점프시킨다. Project References 를 도입하면서 개발자 불만이 나오는 1순위가 이 항목이고, 해결책은 `declarationMap` 한 줄이다. Rename Symbol 도 이 맵을 통해 프로젝트 경계를 넘어 동작한다.

```jsonc
{
  "compilerOptions": {
    "composite": true,
    "declaration": true,
    "declarationMap": true,   // 이 줄이 IDE 경험을 결정
    "sourceMap": true
  }
}
```

`sourceMap` 은 런타임 디버깅(`.js` → `.ts`), `declarationMap` 은 타입 탐색(`.d.ts` → `.ts`)이다. 둘은 별개이며 모노레포에서는 둘 다 필요하다.

## 8. 성능 측정과 튜닝

체감이 아니라 숫자로 접근한다. `--diagnostics`(또는 `--extendedDiagnostics`)가 1차 도구다.

```bash
tsc -p packages/api --extendedDiagnostics
```

```
Files:                         412
Lines of Library:            38,742
Lines of TypeScript:         21,338
Identifiers:                 89,201
Symbols:                    142,556
Types:                       31,204
Instantiations:             418,993
Memory used:                412,338K
I/O Read time:                0.14s
Parse time:                   0.62s
Bind time:                    0.28s
Check time:                   4.81s
Emit time:                    0.44s
Total time:                   6.29s
```

읽는 법은 이렇다. **Files 가 예상보다 크면** 프로젝트 경계가 새고 있다(§3 의 `paths` 문제). **Instantiations 가 100만을 넘으면** 타입 수준 재귀나 거대한 conditional type 이 범인이다. **Check time 이 지배적이면** 타입 복잡도 문제이고, **Parse time 이 지배적이면** 파일 수 문제다.

느린 타입을 특정하려면 `--generateTrace` 로 추적 파일을 뽑아 분석한다.

```bash
tsc -p packages/api --generateTrace ./trace
npx @typescript/analyze-trace ./trace
```

`analyze-trace` 는 체크 시간이 오래 걸린 파일과 타입을 내림차순으로 출력한다. 실측상 상위 3개 항목이 전체의 절반 이상을 차지하는 경우가 많고, 대개 라이브러리 경계의 거대한 generic(예: 스키마 추론기, ORM 쿼리 빌더)이다. 해당 지점에 명시적 타입 주석을 넣어 추론을 끊는 것만으로 수 초가 사라진다.

```ts
// 추론이 폭발하는 지점
const q = db.select().from(users).innerJoin(orders, ...).where(...);

// 명시 주석으로 절단 — 하위 추론 체인이 끊김
const q: Query<UserWithOrders> =
  db.select().from(users).innerJoin(orders, ...).where(...);
```

## 9. 빌드 그래프 설계와 트레이드오프

| 구성 | 증분 빌드 이득 | 설정 복잡도 | 순환 의존 위험 |
|---|---|---|---|
| 단일 tsconfig | 없음(전체 재체크) | 최소 | 없음(경계 자체가 없음) |
| composite + references | 큼. 변경 하위 그래프만 | 높음. rootDir/include 강제 | 있음. 참조 순환은 에러 |
| Solution-style tsconfig | 큼 + 단일 진입점 | 중간 | 있음 |
| 번들러 위임(esbuild/swc + tsc --noEmit) | 트랜스파일은 빠름, 체크는 그대로 | 중간 | 없음 |

Solution-style 은 루트에 빈 tsconfig 를 두고 references 만 나열하는 패턴이다.

```jsonc
// tsconfig.json (루트) — 입력 파일 없음
{
  "files": [],
  "references": [
    { "path": "./packages/core" },
    { "path": "./packages/api" },
    { "path": "./packages/web" }
  ]
}
```

`tsc -b` 한 번으로 전체 그래프를 빌드하고, IDE 도 이 파일로 워크스페이스 전체를 인식한다.

마지막 행의 트레이드오프가 실무에서 자주 오해된다. esbuild/swc 는 **타입 체크를 하지 않고** 트랜스파일만 한다. 빌드가 10배 빨라지는 것은 체크를 건너뛰기 때문이지 마법이 아니다. 그래서 `tsc --noEmit` 을 CI 에서 별도로 돌려야 하고, 그 시간은 그대로 남는다. Project References 는 바로 그 **체크 시간**을 줄이는 수단이므로, 번들러 도입과 배타적이지 않고 오히려 상호 보완적이다. 실제 조합은 "emit 은 swc, 체크는 `tsc -b --noEmit` 로 증분" 이 된다.

`composite` 프로젝트에서 `--noEmit` 은 원래 금지였으나 TypeScript 5.5 부터 `--build` 모드에서 허용되어, 체크 전용 증분 빌드가 공식 경로가 되었다.

## 참고

- TypeScript Handbook — Project References: https://www.typescriptlang.org/docs/handbook/project-references.html
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- TypeScript 5.5 Release Notes — `--build` with `--noEmit`: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- @typescript/analyze-trace: https://github.com/microsoft/typescript-analyze-trace
- TSConfig Reference — composite / incremental / tsBuildInfoFile: https://www.typescriptlang.org/tsconfig
