Notion 원본: https://www.notion.so/3915a06fd6d38153b8d6ec329353901b

# TypeScript Project References와 Incremental 빌드 및 tsBuildInfo 증분 컴파일

> 2026-07-02 신규 주제 · 확장 대상: TypeScript(ESM 모듈 해석 학습됨)

## 학습 목표

- 모노레포를 여러 `tsconfig`로 분할하고 `references`로 의존 그래프를 선언한다
- `composite`, `declaration`, `tsBuildInfo`가 증분 빌드에서 하는 역할을 구분한다
- `tsc --build` 모드의 위상 정렬·업투데이트 판정 로직을 읽는다
- 빌드 캐시 무효화 조건을 실측 기준으로 튜닝한다

## 1. 왜 단일 tsconfig가 대규모에서 무너지는가

TypeScript 컴파일러는 하나의 `tsconfig.json`에 대해 프로그램(Program) 객체를 하나 만든다. 이 Program은 `include`/`files`로 지정된 루트 파일에서 시작해 import 그래프를 따라 도달 가능한 모든 소스를 하나의 타입 체크 단위로 묶는다. 파일 500개짜리 패키지 하나만 고쳐도 전체 그래프의 타입 관계를 다시 계산해야 하므로, 모노레포가 수천 파일로 커지면 한 글자 수정에 수십 초가 걸린다. 근본 원인은 "변경 영향 범위"를 컴파일러가 파일 단위 이하로 좁힐 수 없다는 데 있다.

Project References는 이 문제를 컴파일 경계를 물리적으로 쪼개서 해결한다. 각 하위 패키지가 독립 Program이 되고, 상위 패키지는 하위 패키지의 **소스가 아니라 이미 생성된 `.d.ts`** 만 참조한다. 그 결과 하위 패키지가 바뀌지 않았다면 상위 패키지는 그 `.d.ts`를 그대로 신뢰하고 재검사를 건너뛴다.

## 2. references 선언과 의존 그래프

참조는 소비하는 쪽 `tsconfig`의 `references` 배열에 참조 대상 `tsconfig`의 디렉터리(또는 파일) 경로를 적어 선언한다.

```jsonc
// packages/web/tsconfig.json
{
  "compilerOptions": {
    "module": "NodeNext",
    "outDir": "./dist",
    "rootDir": "./src"
  },
  "references": [
    { "path": "../core" },
    { "path": "../ui" }
  ]
}
```

참조 대상은 반드시 `composite: true`여야 한다. `composite`는 세 가지를 강제한다. 첫째 `declaration: true`(참조 측이 읽을 `.d.ts` 생성), 둘째 `rootDir`의 명시적 확정, 셋째 모든 구현 파일이 `include`에 포함되어야 한다는 제약이다. 이 제약들은 "이 패키지의 공개 타입 표면을 파일만 보고 결정 가능하게" 만들기 위한 것이다.

```jsonc
// packages/core/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "declaration": true,
    "declarationMap": true,
    "outDir": "./dist",
    "rootDir": "./src"
  },
  "include": ["src/**/*"]
}
```

`declarationMap: true`를 켜면 상위 패키지에서 `core`의 심볼로 "Go to Definition"을 했을 때 `.d.ts`가 아니라 원본 `.ts`로 점프한다. 모노레포 개발 경험에서 이 옵션은 사실상 필수다.

## 3. tsc --build(빌드 모드)의 동작

일반 `tsc`는 참조를 무시하고 단일 프로젝트만 컴파일한다. 참조 그래프를 따라 빌드하려면 `tsc --build`(약칭 `tsc -b`)를 써야 한다. 빌드 모드는 다음 순서로 동작한다.

1. 진입 `tsconfig`에서 `references`를 재귀적으로 펼쳐 의존 그래프를 만든다.
2. 그래프를 위상 정렬(topological sort)해 리프 패키지부터 빌드 순서를 정한다. 순환 참조가 있으면 에러로 중단한다.
3. 각 프로젝트마다 "업투데이트인가"를 판정한다(§4). 최신이면 건너뛴다.
4. 최신이 아니면 컴파일하고 `.tsbuildinfo`를 갱신한다.

```bash
tsc -b packages/web
tsc -b packages/web --force
tsc -b packages/web --watch
tsc -b packages/web --clean
```

핵심은 상위 패키지의 재빌드 여부가 하위 패키지의 **출력 `.d.ts` 변경 여부**로 결정된다는 점이다. `core`의 구현만 바뀌고 공개 타입이 그대로면, 생성된 `.d.ts`는 동일하고 따라서 `web`은 재검사되지 않는다. 반대로 함수 시그니처를 바꾸면 `.d.ts`가 달라지고 `web`이 재검사 대상이 된다.

## 4. tsBuildInfo와 업투데이트 판정

`composite`나 `incremental`이 켜지면 컴파일러는 `.tsbuildinfo` 파일을 남긴다. 여기에는 각 입력 파일의 버전 해시, 파일 간 참조, 이전 컴파일에서 방출한 시그니처 등이 저장된다. 다음 빌드 때 컴파일러는 소스 파일의 현재 내용 해시를 저장된 해시와 비교해 바뀐 파일만 다시 타입 체크한다.

| 판정 대상 | 최신(스킵) 조건 |
|---|---|
| 출력 존재 | `outDir`에 `.js`/`.d.ts`가 모두 있음 |
| 입력 mtime | 모든 입력의 최신 mtime ≤ 출력의 최소 mtime |
| 의존 프로젝트 | 참조한 프로젝트가 모두 최신이고 그 `.d.ts`가 안 바뀜 |
| 설정 변경 | `tsconfig` 자체가 출력보다 오래됨 |

주의할 함정은 `.tsbuildinfo`가 디스크에 저장되는 파일이라는 점이다. CI에서 캐시를 복원할 때 소스 파일의 mtime이 체크아웃 시각으로 갱신되면, 내용이 같아도 mtime 기준으로 "변경됨"으로 오판될 수 있다. TypeScript는 내용 해시를 우선 보지만, `.tsbuildinfo`와 출력물, 소스가 모두 일관된 시점에 캐시돼야 안전하다. 실무에서는 `.tsbuildinfo`, `dist`, 소스를 한 캐시 키로 묶어 복원하는 편이 안정적이다.

## 5. 증분 빌드 성능 실측 감각

참조 분할의 효과는 "변경이 그래프의 어디서 일어나는가"에 크게 좌우된다.

```
모노레포: core → ui → web, 전체 ~3,000파일, 단일 tsconfig 풀 빌드 ~28s
- web 내부 구현 1파일 수정        →  2~4s (web만 재검사)
- core 구현만 수정(.d.ts 불변)     →  3~5s (core 재컴파일, ui/web 스킵)
- core 공개 시그니처 수정          → 12~18s (core→ui→web 연쇄 재검사)
```

자주 바뀌는 코드는 그래프 상위(리프)에 두어 하위 재검사를 유발하지 않게 하고, 안정적인 공용 타입은 하위 패키지로 내려 공개 표면 변경 빈도를 낮춘다. `core`의 공개 API를 자주 흔들면 참조 분할의 이득이 대부분 사라진다.

## 6. 흔한 함정과 해결

가장 잦은 실패는 참조는 걸었는데 `tsc`(빌드 모드 아님)로 돌려서 `error TS6305: Output file has not been built from source file`을 보는 경우다. 해법은 항상 `tsc -b`를 쓰는 것이다. 두 번째는 `paths` 별칭 충돌로, `tsconfig`의 `paths`는 타입 해석용일 뿐이며 런타임 해석은 번들러/Node가 담당하므로 `outDir` 구조와 일치해야 한다. 세 번째는 `.tsbuildinfo` 위치로, `tsBuildInfoFile`로 명시하거나 `outDir` 하위에 모으는 편이 캐시 관리에 유리하다.

## 7. 번들러 시대의 위치 - 타입 검사와 트랜스파일 분리

Vite, esbuild, swc는 타입을 무시하고 트랜스파일만 빠르게 한다. 이 조합에서 Project References의 역할은 런타임 빌드가 아니라 **타입 검사 오케스트레이션**이다. 트랜스파일은 번들러가, 정합성 검증은 `tsc -b`가 담당하는 역할 분리가 현재 모노레포의 표준 형태다.

## 8. 결론 - 언제 도입할 가치가 있는가

Project References는 공짜가 아니다. 파일 수백 개 이하의 단일 앱이라면 `incremental: true` 하나로 충분하다. 그러나 여러 팀이 공유 패키지를 두고 병렬 개발하는 모노레포, 또는 풀 타입 체크가 20초를 넘겨 개발 루프를 방해하는 규모에서는 변경 영향 범위를 패키지 단위로 격리하는 이 구조가 가장 확실한 해법이다. 판단 기준은 코드 규모가 아니라 타입 체크 지연이 개발 리듬을 깨는가에 둔다.

## 9. 병렬 빌드·watch 내부와 대규모 튜닝

`tsc -b`는 위상 정렬된 그래프를 단일 프로세스에서 순차 빌드하므로 코어가 많은 CI에서 CPU가 논다. 상위 빌드 오케스트레이터(Turborepo, Nx, Bazel)가 패키지별 `tsc -b`를 의존 그래프에 따라 병렬 스케줄링하고 입력 해시를 캐시 키로 삼아 변경되지 않은 패키지는 캐시된 산출물을 복원한다.

```jsonc
{
  "tasks": {
    "build": {
      "dependsOn": ["^build"],
      "inputs": ["src/**", "tsconfig.json"],
      "outputs": ["dist/**", "dist/.tsbuildinfo"]
    }
  }
}
```

`dependsOn: ["^build"]`의 캐럿은 의존 패키지의 build 를 먼저 수행하라는 뜻으로 TypeScript의 `references` 그래프와 같은 순서를 재현한다. 두 그래프가 어긋나면 캐시 미스가 나므로 한 소스에서 생성해 동기화한다. watch 모드는 변경 파일이 속한 프로젝트와 그 `.d.ts`에 의존하는 상위만 재빌드 후보로 올리며, 감시 대상이 많으면 `fs.inotify.max_user_watches` 한도에 걸려 변경을 놓칠 수 있다. `disableReferencedProjectLoad`는 에디터 기동 메모리를, `assumeChangesOnlyAffectDirectDependencies`는 재검사 범위를 줄이지만 정확성과 속도를 맞바꾸므로 `tsc -b --diagnostics`로 검증 후 채택한다.

## 참고

- TypeScript Handbook — Project References (https://www.typescriptlang.org/docs/handbook/project-references.html)
- TypeScript Wiki — Performance (https://github.com/microsoft/TypeScript/wiki/Performance)
- TSConfig Reference — composite, incremental, tsBuildInfoFile (https://www.typescriptlang.org/tsconfig)
