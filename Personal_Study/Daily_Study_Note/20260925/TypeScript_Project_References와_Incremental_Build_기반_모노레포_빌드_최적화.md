Notion 원본: https://app.notion.com/p/3e65a06fd6d38124b009ce859e09c2d5

# TypeScript Project References와 Incremental Build 기반 모노레포 빌드 최적화

> 2026-09-25 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Project References가 패키지 간 타입 경계와 빌드 순서를 어떻게 강제하는지 설명한다
- `.tsbuildinfo`를 이용한 Incremental Build가 어떤 정보를 캐싱해 재컴파일을 건너뛰는지 구분한다
- `composite`, `declarationMap` 등 필수 컴파일러 옵션의 역할과 상호 의존성을 정리한다
- `tsc -b`의 의존 그래프 순회 방식과 순환 참조 시의 실패 양상을 파악한다

## 1. Project References가 해결하는 문제

모노레폰에서 여러 패키지(`packages/core`, `packages/api`, `packages/web`)가 서로 참조하는 구조를 순수 `tsconfig.json`의 `paths` 매핑만으로 구성하면 두 가지 문제가 생긴다. 첫째, `tsc`가 전체 저장소를 하나의 프로그램으로 취급해 매번 전체를 다시 타입체크하므로 저장소가 커질수록 빌드 시간이 선형 이상으로 증가한다. 둘째, `paths`는 타입 해석 경로만 지정할 뿐 "이 패키지가 먼저 컴파일되어야 저 패키지가 컴파일될 수 있다"는 **빌드 순서 의존성**을 강제하지 못해, 순환 참조나 잘못된 참조 방향을 컴파일러가 사전에 걸러내지 못한다.

Project References는 각 패키지를 독립된 TypeScript "프로젝트"로 선언하고, `references` 필드로 명시적인 의존 그래프를 구성한다.

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
  "include": ["src"]
}
```

```jsonc
// packages/api/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "outDir": "./dist",
    "rootDir": "./src"
  },
  "references": [
    { "path": "../core" }
  ],
  "include": ["src"]
}
```

`references`를 선언하면 `packages/api`에서 `packages/core`를 import할 때, TypeScript는 `core`의 소스 코드를 직접 다시 타입체크하지 않고 **이미 컴파일된 `.d.ts` 선언 파일**을 통해서만 타입을 가져온다. 이는 패키지 경계를 컴파일러 레벨에서 물리적으로 강제하는 효과가 있어, "내부적으로만 쓰려던 타입을 다른 패키지가 실수로 깊은 경로(`core/src/internal/foo`)로 import하는" 캐팔수화 위반을 원천 차단한다.

## 2. composite와 declaration의 필수 관계

`references`로 참조되는 프로젝트는 반드시 `composite: true`를 설정해야 한다. `composite`는 다음 세 가지를 자동으로 강제한다: `declaration: true`(선언 파일 생성 필수), `incremental: true`(증분 빌드 정보 저장 필수), 그리고 `rootDir`이 명시적으로 추론 가능해야 한다는 제약(소스 파일이 `include` 범위를 벗어나면 에러). 이 강제 조건들은 "다른 프로젝트가 나를 참조하려면 내가 무엇을 export하는지 `.d.ts`로 명확히 선언해야 한다"는 Project References의 근본 철학을 컴파일러 옵션 레벨에서 보증한다.

`declarationMap: true`를 추가로 켜면 `.d.ts` 파일에서 원본 `.ts` 소스로 되돌아가는 소스맵(`.d.ts.map`)이 생성된다. 이것이 없으면 IDE에서 "정의로 이동(Go to Definition)"을 누렀을 때 컴파일된 `.d.ts` 파일로 이동하지만, 있으면 실제 원본 `.ts` 파일로 바로 이동한다. 모노레폰에서 여러 패키지를 오가며 개발할 때 개발 경험(DX)에 미치는 영향이 크므로 사실상 필수 옵션으로 취급된다.

## 3. tsc -b와 의존 그래프 순회

`tsc -b`(빌드 모드)는 일반 `tsc`와 다르게 동작한다. 지정된 프로젝트의 `references`를 재귀적으로 탐색해 의존 그래프를 구성하고, **위상 정렬(topological sort)** 순서로 각 프로젝트를 순차 컴파일한다. 이때 각 프로젝트는 자신이 의존하는 프로젝트의 출력(`.d.ts`, `.tsbuildinfo`)이 최신 상태인지 먼저 확인하고, 오래된 경우 해당 의존성부터 재컴파일한다.

```bash
# 루트에 solution-style tsconfig를 두고 전체 그래프를 빌드
tsc -b packages/web

# 출력 예시: 의존성 순서대로 컴파일
# Project 'packages/core/tsconfig.json' is out of date because output file 'dist/index.js' does not exist
# Building project 'packages/core/tsconfig.json'...
# Project 'packages/api/tsconfig.json' is up to date with .d.ts files from its dependencies
# Building project 'packages/api/tsconfig.json'...
```

순환 참조(`core`가 `api`를 참조하고 `api`가 다시 `core`를 참조)가 그래프에 존재하면 `tsc -b`는 즉시 에러를 발생시키며 전체 빌드를 중단한다. 이는 런타임에 순환 import로 인한 "일부 export가 undefined로 평가되는" 미묘한 버그를 컴파일 타임에 조기 차단하는 효과가 있다. 순환이 실제로 필요한 설계라면 공통 타입을 별도의 세 번째 패키지(`packages/shared-types`)로 추출해 그래프를 단방향으로 재구성해야 한다.

## 4. .tsbuildinfo와 Incremental Build의 캐싱 단위

`incremental: true`(`composite`에 의해 자동 활성화)가 켜지면 컴파일러는 `.tsbuildinfo` 파일에 다음 정보를 저장한다: 각 소스 파일의 콘텐츠 해시, 파일 간 의존 관계 그래프, 그리고 마지막 컴파일 시점의 컴파일러 옵션 스냅샷. 다음 빌드에서 컴파일러는 파일 해시를 비교해 **변경된 파일과 그 파일에 의존하는 파일들만** 다시 타입체크하고, 변경되지 않은 나머지는 이전 결과를 재사용한다.

```jsonc
{
  "compilerOptions": {
    "composite": true,
    "incremental": true,
    "tsBuildInfoFile": "./dist/.tsbuildinfo"
  }
}
```

주의할 점은 `.tsbuildinfo`가 **파일 단위**로 변경을 추적하지, 함수나 타입 단위로 더 세밀하게 추적하지는 않는다는 것이다. 즉 하나의 파일에 100개의 export가 있고 그중 하나만 수정해도, 해당 파일 전체가 "변경됨"으로 표시되어 재컴파일 대상이 된다. 이 때문에 모노레폰에서는 파일을 지나치게 크게 만들지 않고 기능 단위로 잔게 쪼개는 것이 증분 빌드 효율에 실질적으로 도움이 된다.

## 5. 모노레폰 CI에서의 빌드 캐시 전략

`.tsbuildinfo`는 로컬 개발 환경뿐 아니라 CI 파이프라인에서도 캐싱 대상이 될 수 있다. 다만 CI는 매번 클린 체크아웃에서 시작하므로, `.tsbuildinfo`와 각 패키지의 `dist` 출력을 함께 캐시 키(통상 lockfile 해시 + 소스 트리 해시 조합)로 저장/복원해야 증분 빌드의 이점을 CI에서도 살릴 수 있다.

```yaml
# GitHub Actions 예시
- uses: actions/cache@v4
  with:
    path: |
      **/dist
      **/*.tsbuildinfo
    key: tsc-build-${{ hashFiles('**/pnpm-lock.yaml', 'packages/*/src/**') }}
    restore-keys: |
      tsc-build-
- run: pnpm exec tsc -b packages/web
```

캐시가 정확히 복원되면 `tsc -b`는 각 프로젝트에 대해 "출력이 최신 상태"라고 판단해 대부분의 프로젝트를 건너뛰고, 실제로 변경된 패키지와 그 하위 의존 패키지만 재빌드한다. 대규모 모노레폰에서는 이 차이가 전체 빌드 10분 이상에서 수십 초로 줄어드는 정도로 클 수 있다.

## 6. Nx/Turborepo와의 관계

Nx나 Turborepo 같은 모노레폰 빌드 오케스트레이터는 Project References와 경쟁하는 것이 아니라 **보완하는 관계**다. Project References는 TypeScript 컴파일러 레벨에서 "무엇을 어떤 순서로, 무엇이 변경되었을 때 다시 컴파일할지"를 결정하는 반면, Nx/Turborepo는 그 위에서 패키지별 빌드/테스트/린트 등 **여러 종류의 태스크**를 태스크 그래프로 관리하고, 원격 캐시(remote cache)를 통해 팀원 간 또는 CI 간 빌드 결과를 공유한다. 실무에서는 Turborepo의 `turbo.json`에 각 패키지의 `tsconfig.json` 참조 그래프와 일치하는 `dependsOn: ["^build"]` 설정을 병행해, 두 계층의 의존성 선언이 어긋나지 않도록 유지하는 것이 중요하다. 어긋나면 "Turborepo는 캐시 히트라고 판단했지만 실제로는 타입이 깨진 상태"가 될 수 있다.

## 7. 흔한 실패 패턴과 진단

**(1) `Referenced project must have setting "composite": true`**: 참조되는 프로젝트에 `composite`를 빠뜨렸을 때 발생한다. 새 패키지를 추가할 때 가장 흔히 놓치는 설정이다.

**(2) 출력이 갱신되지 않는데 `tsc -b`가 "up to date"라고 보고**: `.tsbuildinfo`나 `dist`를 수동으로 건드리거나 `git clean` 없이 브랜치를 전환했을 때, 파일 시스템의 mtime과 콘텐츠 해시가 어긋나 발생할 수 있다. `tsc -b --force`로 캐시를 무시하고 강제 재빌드하거나, `tsc -b --clean`으로 출력을 정리한 뒤 다시 빌드하면 해소된다.

```bash
tsc -b --clean packages/web   # 모든 참조 프로젝트의 출력과 .tsbuildinfo 삭제
tsc -b packages/web           # 처음부터 다시 빌드
```

**(3) 편집기에서는 타입 에러가 안 보이는데 CI에서는 실패**: VSCode의 TS 서버가 `references`를 인식하지 못하고 소스를 직접 참조하도록 설정된 경우(`"path"` 대신 잘못된 `paths` 매핑이 우선 적용되는 경우) 발생한다. `tsconfig.json`의 `paths`와 `references`가 같은 대상을 가리키되 서로 충돌하지 않도록, 편집기용 루트 `tsconfig.json`에서는 `references`만으로 그래프를 구성하고 `paths`는 최소화하는 편이 안전하다.

## 8. 실무 도입 체크리스트

기존에 단일 `tsconfig.json`으로 관리되던 모노레폰을 Project References로 전환할 때는, 먼저 패키지 간 실제 import 관계를 정적 분석 도구(`madge`, `dependency-cruiser`)로 추출해 순환 참조가 있는지 사전에 확인한다. 순환이 있다면 공통 타입 패키지로 분리해 그래프를 단방향화한다. 다음으로 각 패키지에 `composite: true`와 `declarationMap: true`를 일괄 적용하고, `references` 필드를 실제 import 관계와 정확히 일치시킨다. 그 다음 CI 캐시 전략에 `.tsbuildinfo`와 `dist`를 포함시켜 증분 빌드 효과를 CI까지 확장한다. 마지막으로 로컬 개발 시 `tsc -b --watch`를 사용해, 파일 저장 시 변경된 패키지와 그 하위 의존 패키지만 재컴파일되는지 실제로 관찰하며 그래프 설정의 정확성을 검증한다.

## 9. Solution-Style tsconfig와 병렬 빌드

패키지 수가 많아지면 루트에 실제 소스를 하나도 포함하지 않고 오직 `references`만 나열하는 "solution-style" `tsconfig.json`을 두는 것이 일반적이다.

```jsonc
// tsconfig.json (루트, solution-style)
{
  "files": [],
  "references": [
    { "path": "packages/core" },
    { "path": "packages/api" },
    { "path": "packages/web" }
  ]
}
```

`files: []`로 두어 루트 자체는 컴파일 대상이 없음을 명시하고, IDE나 `tsc -b .`가 이 파일을 진입점으로 삼아 전체 그래프를 인식하게 한다. `tsc -b`는 기본적으로 의존 관계가 없는 프로젝트들(예: 서로 무관한 `packages/utils-a`, `packages/utils-b`)을 순차적으로 처리하지만, `--verbose`와 함께 실행하면 어떤 프로젝트가 병렬로 처리 가능한지 그래프 상에서 확인할 수 있다. 실무에서 더 적극적인 병렬화가 필요하면 Nx의 `nx run-many --target=build --parallel` 같은 오케스트레이션 레이어에 의존 그래프의 병렬 실행을 위임하는 편이, `tsc -b` 자체의 병렬 스케줄링 기능을 억지로 조정하는 것보다 실용적이다.

## 10. 실측 빌드 시간 개선 경향

중간 규모 모노레폰(패키지 12개, 소스 파일 약 900개) 기준으로 관찰되는 일반적인 경향은 다음과 같다. 단일 `tsconfig.json` + 전체 재컴파일 방식에서는 파일 하나만 수정해도 전체 타입체크가 다시 실행되어 로컬 빌드가 매번 40~60초 수준으로 고정된다. Project References + Incremental Build로 전환하면, 잎(leaf) 노드에 가까운 패키지 하나만 수정했을 때 해당 패키지와 그 상위 의존 패키지 몇 개만 재컴파일되어 전체 시간이 5~10초 수준으로 줄어드는 경향을 보인다. 다만 그래프의 루트에 가까운 공통 패키지(`packages/core`)를 수정하면 그 아래 모든 패키지가 재컴파일 대상이 되므로 개선 폭이 크지 않다. 이 특성은 "자주 바뀌는 코드는 그래프의 잎 쪽에, 안정적인 코드는 루트 쪽에 배치한다"는 모노레폰 패키지 설계 원칙을 뇐받침하는 실증적 근거가 된다.

## 참고

- TypeScript Handbook, "Project References"
- TypeScript Handbook, "Project Configuration" (composite, incremental)
- TypeScript 공식 릴리즈 노트, `tsc -b` 관련 변경 이력
- Turborepo 공식 문서, "Handling TypeScript Monorepos"
