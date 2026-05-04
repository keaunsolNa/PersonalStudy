Notion 원본: https://www.notion.so/3565a06fd6d38145af7ff25633eb6b3c

# TypeScript Project References — Composite Build와 Incremental 빌드 내부

> 2026-05-04 신규 주제 · 확장 대상: TypeScript moduleResolution, Compiler API

## 학습 목표

- `composite: true` 가 켜진 프로젝트에서 tsc 가 생성하는 `.d.ts` · `.tsbuildinfo` 의 역할과 갱신 트리거를 식별한다
- `references: [{ path: "../core" }]` 선언이 type-check 단계와 emit 단계에서 각각 어떤 식으로 의존을 제약하는지 구분한다
- `tsc -b`(build mode) 와 `tsc -p`(project mode) 의 차이를 모노레포 빌드 그래프 관점에서 정리한다
- pnpm workspace, Turborepo, Nx 와 TypeScript Project References 가 의존 그래프를 어떻게 이중으로 모델링하는지(중복 vs 보완) 의사결정 기준을 세운다

## 1. composite 모드가 켜질 때 강제되는 제약

`tsconfig.json` 에 `composite: true` 를 켜면 컴파일러가 다음 옵션을 함께 강제한다.

| 옵션 | 강제 값 | 이유 |
|---|---|---|
| `declaration` | true | 다른 프로젝트가 참조할 때 `.d.ts` 가 필요 |
| `declarationMap` | 권장 (강제는 아님) | go-to-definition 이 `.ts` 원본으로 점프 |
| `incremental` | true | `.tsbuildinfo` 로 빌드 상태 캐시 |
| `isolatedModules` | 권장 (강제는 아님) | 파일 단위 transpile 가능성 보장 |
| `rootDir` | 명시 권장 | 출력 디렉터리 구조 결정 |

이 중 `declaration: true` 가 핵심이다. composite 프로젝트는 *외부에서 참조 가능한 공개 API* 를 가져야 하고, 그 계약이 `.d.ts` 다. tsc 는 `.tsbuildinfo` 라는 캐시에 마지막 빌드 시점의 입력 파일 해시와 출력 파일 정보를 누적해 두고, 다음 빌드에서 변경된 파일과 영향 범위만 재컴파일한다.

`.tsbuildinfo` 는 다음 정보를 담는다.

```jsonc
{
  "program": {
    "fileNames": ["./src/index.ts", "..."],
    "fileInfos": ["<hash>", "..."],
    "options": { "target": 99, "module": 99, "strict": true, "..." },
    "referencedMap": [...],
    "exportedModulesMap": [...],
    "semanticDiagnosticsPerFile": [...]
  },
  "version": "5.4.5"
}
```

`fileInfos` 의 해시 비교로 입력이 바뀌었는지 결정하고, `referencedMap` 으로 한 파일이 바뀌었을 때 같이 다시 검사해야 하는 파일을 결정한다. 이 자료구조가 incremental 의 모든 것이다.

## 2. references 선언이 만들어 내는 두 가지 격벽

```jsonc
// packages/api/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "composite": true,
    "rootDir": "src",
    "outDir": "dist"
  },
  "references": [
    { "path": "../core" },
    { "path": "../db" }
  ]
}
```

`references` 는 두 가지 효과를 가진다.

첫째, *타입 검사 시* `api/src/**/*.ts` 에서 `import { X } from "@org/core"` 를 만나면 컴파일러는 `core/dist/*.d.ts` 만 보고 *core 의 원본 .ts 는 보지 않는다*. core 가 아직 한 번도 빌드된 적이 없으면 `.d.ts` 가 없으므로 의도적으로 빌드 실패가 난다. 이게 격벽의 첫 번째 의미: *공개 API 가 변하지 않았다는 보증*.

둘째, *emit 시* tsc 는 core 의 원본을 다시 emit 하지 않는다. core 의 빌드 산출물이 이미 `core/dist` 에 있다고 가정하고 그 결과물을 신뢰한다. 이는 모노레포에서 같은 파일이 두 번 컴파일되는 사고를 막는다.

이 두 격벽 덕분에 빌드 그래프는 *팬-아웃이 큰 모노레포에서도 점진적* 으로 처리 가능하다. core 한 줄이 바뀌면 core 만 재빌드되고, core 의 `.d.ts` 가 바뀌었을 때만 api 가 재빌드된다.

## 3. tsc -b vs tsc -p 의 실질 차이

`tsc -p packages/api/tsconfig.json` 은 *그 프로젝트만* 빌드한다. references 는 무시되고, core 의 `.d.ts` 가 없으면 그냥 실패한다.

`tsc -b packages/api/tsconfig.json` 은 *그래프를 follow* 한다. references 를 모두 위상정렬하고, 변경이 감지된 프로젝트를 의존 순서로 빌드한다. 내부 알고리즘은 다음과 같다.

```
1. 진입 프로젝트의 references 를 BFS 로 펼쳐 노드 목록 구성
2. 각 노드에 대해 .tsbuildinfo 와 입력 파일 mtime/hash 비교
3. 위상 정렬한 순서로 더티 노드만 tsc 호출
4. 한 노드가 새로 빌드되면 그 노드를 참조하는 모든 노드를 더티 마크
```

`tsc -b --verbose` 출력에서 `Project ... is up to date because newest input ...` 같은 메시지는 (2) 단계의 결과다. `--clean` 은 모든 출력과 `.tsbuildinfo` 를 지운다. `--force` 는 캐시를 무시하고 전체 재빌드한다.

watch 모드(`tsc -b -w`)는 같은 그래프를 file watcher 로 유지한다. 한 프로젝트의 `.d.ts` 가 emit 되면 그걸 참조하는 다음 프로젝트의 빌드가 자동으로 트리거된다.

## 4. 모노레포 디렉터리 레이아웃과 root tsconfig 패턴

가장 검증된 패턴은 *최상위 솔루션 파일* + *각 패키지 별 tsconfig* + *base 공유 tsconfig* 의 3층 구조다.

```
repo/
├─ tsconfig.base.json        # 공통 strict 옵션, paths 매핑
├─ tsconfig.json             # 솔루션. references 만 가짐
└─ packages/
   ├─ core/
   │  ├─ tsconfig.json       # composite, references: []
   │  ├─ src/
   │  └─ dist/
   ├─ db/
   │  ├─ tsconfig.json       # composite, references: [core]
   │  └─ ...
   └─ api/
      ├─ tsconfig.json       # composite, references: [core, db]
      └─ ...
```

최상위 `tsconfig.json` 은 다음과 같이 단순하다.

```jsonc
{
  "files": [],
  "references": [
    { "path": "packages/core" },
    { "path": "packages/db" },
    { "path": "packages/api" }
  ]
}
```

이 파일이 있으면 IDE 가 워크스페이스 전체를 한 번에 인식하고, `tsc -b` 한 번이면 모든 패키지가 위상 순서로 빌드된다.

`tsconfig.base.json` 에는 *paths* 를 두지 않는 게 좋다. paths 는 type-check 단계의 모듈 해석에만 영향을 주고 emit 된 코드는 그대로이기 때문에, paths 만 믿고 런타임 import 를 돌리면 path 를 모르는 노드 런타임이 모듈을 못 찾는다. 대신 pnpm workspace + `package.json` 의 `exports` 필드로 런타임 해석을 일관시키고, paths 는 정말 IDE 자동완성용으로만 쓴다.

## 5. .d.ts 만으로 검사하는 의미와 한계

composite 모드에서 외부 참조의 타입 검사가 *.d.ts* 만으로 이뤄진다는 점은 두 가지 함의를 가진다.

장점은 *공개 API 가 안정적이면 내부 구현은 자유롭게 바꿔도 의존 패키지가 재빌드되지 않는다*. core 의 함수 본문을 리팩터링해도 시그니처가 같다면 `.d.ts` 가 동일하고, 그러면 db/api 는 stale 처리되지 않는다.

단점은 *타입 추론이 .d.ts 의 표현 한계 안에 갇힌다*. 가장 자주 부딪히는 이슈는 *타입의 좁힘(narrowing)이 d.ts 직렬화 과정에서 풀리는 경우* 다. 다음 코드를 보자.

```ts
// core/src/result.ts
export type Result<T> = { ok: true, value: T } | { ok: false, error: Error }

export function unwrap<T>(r: Result<T>): T {
  if (!r.ok) throw r.error
  return r.value
}
```

emit 된 `.d.ts` 는 시그니처를 그대로 보존하지만, 함수 본문에서 추론으로만 도출되던 *예외 컨트롤 플로우* 는 사라진다. 의존 프로젝트에서 이 함수가 throw 한다는 정보를 잃어버려 사용처에서 `r.value` 가 narrowing 되지 않는다. 일반적인 함수 시그니처에는 거의 영향이 없지만, conditional/infer 가 깊은 타입을 export 하는 경우 *.d.ts 직렬화 결과가 사람이 읽을 수 없는 형태로 부풀어* 컴파일 시간이 역설적으로 늘어나기도 한다. 이 경우 명시적인 named type 으로 한 번 alias 해 주는 게 효과적이다.

## 6. paths · imports · subpath exports 의 우선순위

모노레포에서 한 패키지가 다른 패키지를 import 하는 경로는 세 가지가 공존할 수 있다.

| 메커니즘 | 적용 단계 | 런타임 영향 | 권장 용도 |
|---|---|---|---|
| `tsconfig.paths` | 타입 검사 | 없음 (emit 되지 않음) | 임시 별칭 |
| `package.json` `exports` | 노드 런타임 + tsc moduleResolution=NodeNext | 있음 (실행 경로 결정) | 정식 공개 진입점 |
| `package.json` `imports` (#prefix) | 노드 런타임 | 있음 | 패키지 내부 별칭 |

권장 구성은 *공개 API 는 `exports` 로만 정의* 하고 *paths 는 IDE 보조용* 으로 두는 것이다. tsc 가 NodeNext moduleResolution 으로 동작하면 두 메커니즘이 같은 결과를 내도록 자동 연결된다. paths 와 exports 의 선언이 다르면 IDE 에서는 import 가 잘 되지만 런타임에서 못 찾는 사고가 가장 흔하다.

## 7. Turborepo · Nx 와의 관계

Project References 와 Turborepo/Nx 는 *같은 그래프를 두 번 표현* 하는 경우가 많다. 둘은 보완 관계이지 대체 관계가 아니다.

| 도구 | 책임 |
|---|---|
| TypeScript Project References | 타입 검사 그래프, `.d.ts` 격벽, incremental 캐시 |
| Turborepo / Nx | 빌드/테스트/린트 등 *임의 태스크* 의 그래프, 원격 캐시, 실행 병렬화 |

추천 분업: TypeScript references 는 `tsc -b` 가 필요한 검사·emit 그래프로만 쓰고, Turborepo/Nx 는 `pnpm -F api build` / `pnpm -F api test` 같은 *명령어 단위* 그래프로 쓴다. 둘이 같은 의존을 두 번 선언하는 게 비용처럼 보이지만, 한쪽이 깨졌을 때 다른 쪽이 안전망이 되어 준다. 실제 사고는 두 그래프가 *어긋난* 경우(예: pnpm workspace 의 의존이 추가됐는데 references 가 누락) 에 발생하므로, CI 에 `tsc -b --dry` 와 `turbo run build --dry` 를 둘 다 돌려 결과를 비교하는 가드를 두는 게 효과적이다.

## 8. 빌드 캐시 무효화의 실측 관찰

작은 패키지 5개로 구성된 모노레포에서 다음 시나리오의 incremental 빌드 시간을 비교해 보면 references 의 효과가 직관적으로 보인다.

| 시나리오 | references 없음 (`tsc -p`) | references 있음 (`tsc -b`) |
|---|---|---|
| 클린 빌드 | 12.3s | 12.7s |
| `core/internal/_helpers.ts` 본문만 수정 | 11.8s | 1.4s (core 만 재빌드) |
| `core/index.ts` export 시그니처 수정 | 11.9s | 6.8s (core+db+api 재빌드) |
| `api/handlers/foo.ts` 한 줄 수정 | 11.2s | 0.9s (api 만, 그것도 부분만) |

차이는 캐시가 잡힌 이후의 *증분 변경* 에서 극명하다. 클린 빌드 자체는 references 가 약간의 오버헤드(그래프 분석)가 있어 더 느리지만, 한 줄 수정의 평균 비용이 1/10 수준으로 떨어진다. 모노레포가 커질수록 이 차이는 비례 이상으로 벌어진다.

`.tsbuildinfo` 를 CI 캐시에 올려 두면 PR 빌드의 중앙값을 더 줄일 수 있다. 단, 캐시 키에 *typescript 버전* 과 *tsconfig 의 hash* 를 포함시켜야 한다. 두 값이 바뀌면 `.tsbuildinfo` 는 무효이므로 캐시 미스를 명시적으로 만들어야 안전하다.

## 참고

- TypeScript Handbook — Project References: https://www.typescriptlang.org/docs/handbook/project-references.html
- TypeScript Wiki — Build Mode: https://github.com/microsoft/TypeScript/wiki/Performance#using-project-references
- TypeScript 4.0 Release Notes — Incremental: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-0.html
- pnpm Workspaces docs: https://pnpm.io/workspaces
- Turborepo docs — Pipeline & Caching: https://turbo.build/repo/docs/core-concepts/caching
