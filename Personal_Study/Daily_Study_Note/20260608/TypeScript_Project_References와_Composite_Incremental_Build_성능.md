Notion 원본: https://www.notion.so/3795a06fd6d38127bd87d34a0b2300b3

# TypeScript Project References와 Composite Incremental Build 성능

> 2026-06-08 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 단일 거대 tsconfig 의 한계와 Project References 가 해결하는 빌드/타입체크 문제를 정의한다
- composite·declaration·.tsbuildinfo 가 증분 빌드를 가능케하는 메커니즘을 추적한다
- tsc --build(빌드 모드)의 의존 그래프 처리와 일반 tsc 의 차이를 구분한다
- 모노레포에서 References 토폴로지·경로 매핑·watch 모드를 구성한다

## 1. 단일 tsconfig 의 한계

모든 소스를 하나의 tsconfig 로 컴파일하면 두 가지가 무너진다. 첫째, **빌드 시간** — 파일 하나만 바꿔도 영향 범위를 몰라 사실상 전체를 재검사한다. 둘째, **경계 강제력** — 한 컴파일 단위 안에 있으면 core 가 web 을 import 하면 안 된다 같은 규칙을 타입 시스템이 막아주지 못한다. Project References 는 프로젝트를 여러 독립 컴파일 단위로 쪼개고 의존을 명시적 DAG 로 선언하게 한다.

## 2. composite 와 선언 파일 생성

```jsonc
// packages/core/tsconfig.json
{
  "compilerOptions": {
    "composite": true,
    "declaration": true,
    "declarationMap": true,
    "outDir": "dist",
    "rootDir": "src"
  },
  "include": ["src/**/*"]
}
```

핵심: web 이 core 를 참조할 때 web 의 타입체크는 core 의 src 를 다시 분석하지 않고 core 가 미리 emit 해둔 `dist/*.d.ts` 를 읽는다. 즉 각 프로젝트의 타입체크 결과가 .d.ts 라는 경계 인터페이스로 캐싱된다. core 가 안 바뀌면 web 은 재검사할 이유가 없다. 이것이 증분성의 근원이다.

## 3. references 선언과 빌드 모드

```jsonc
// packages/web/tsconfig.json
{
  "compilerOptions": { "composite": true, "outDir": "dist" },
  "references": [ { "path": "../core" }, { "path": "../shared" } ],
  "include": ["src/**/*"]
}
```

```bash
tsc -b packages/web            # web 과 의존(core, shared)을 순서대로
tsc -b packages/web --verbose  # up-to-date 여부 출력
tsc -b --clean                 # 출력물·.tsbuildinfo 제거
tsc -b -w                      # watch: 변경된 프로젝트만 재빌드
```

일반 `tsc` 는 references 를 무시하고 단일 프로젝트만 컴파일하지만, `tsc -b` 는 그래프 전체를 인식해 변경 안 된 프로젝트는 건너뛴다. 모노레포에서는 항상 -b 를 써야 한다.

## 4. .tsbuildinfo: 증분 상태 저장

composite(또는 `incremental: true`)는 `.tsbuildinfo` 를 남긴다. 파일별 해시·버전, 의존 그래프, 이전 진단이 들어있다. 다음 빌드에서 컴파일러는 무엇이 바뀌었고 어디까지 전파되는지 계산해 영향받지 않은 파일의 재검사를 생략한다. core 구현만 바뀌고 공개 시그니처(.d.ts)가 동일하면 web 타입체크는 생략될 수 있다.

| 변경 종류 | 재빌드 범위 | 상대 비용 |
|---|---|---|
| leaf 구현만 수정 | 해당 프로젝트만 | 최소 |
| core 구현 수정(.d.ts 불변) | core emit, 상위 체크 생략 | 낮음 |
| core 공개 시그니처 변경 | core + 모든 상위 | 높음 |
| .tsbuildinfo 삭제 | 전체 | 최대(콜드) |

## 5. 모노레포 구성과 경로 매핑

```jsonc
// tsconfig.json (루트 솔루션 파일)
{
  "files": [],
  "references": [
    { "path": "packages/shared" },
    { "path": "packages/core" },
    { "path": "packages/web" }
  ]
}
```

`tsc -b` 한 번이면 전체 그래프가 순서대로 빌드된다. declarationMap 이 켜져 있으면 에디터에서 정의로 이동 시 .d.ts 가 아니라 원본 .ts 로 점프한다. 많은 번들러(esbuild, swc, Vite)는 타입체크를 하지 않고 트랜스파일만 하므로, CI 에서는 `tsc -b --noEmit` 로 타입 검증만 하고 번들은 빠른 트랜스파일러에 맡기는 2-트랙 구성을 쓴다.

## 6. trade-off 와 함정

비용은 구성 복잡도다. composite 누락은 "referenced project must have composite" 에러, 참조 대상 미빌드 상태에서 상위를 열면 .d.ts 를 못 찾아 빨간 줄, declarationMap 없으면 go-to-definition 이 .d.ts 로 빠진다. 수십 개 패키지·콜드 빌드 수 분대 모노레포라면 증분 빌드 이득이 크지만, 패키지 두세 개짜는 단일 tsconfig + `incremental: true` 만으로 충분하다.

## 7. paths 매핑과 References 의 이중화 문제

`paths`(경로 별칭)와 References 는 다른 메커니즘이다. paths 는 모듈 해석, References 는 빌드 순서와 증분성을 다룬다. paths 를 소스(../core/src)로 잡으면 증분 이득이 사라진다. 빌드 산출물(.d.ts)를 가리켜야 정합한다. 현대적 방법은 `customConditions`(TS 5.0+)와 `exports`/`imports` 필드를 활용한 조건부 해석이다 — 개발 시 소스, 배포 시 .d.ts 를 해석한다. pnpm `workspace:*` 와 결합하면 별칭 없이 References 빌드 순서만 활용한다.

## 8. CI 캐싱 전략: .tsbuildinfo 보존

증분 빌드는 .tsbuildinfo 가 보존될 때만 발휘된다. CI 는 매번 깨끗한 환경이므로 .tsbuildinfo 와 dist 의 .d.ts 를 캐싱 키에 포함해 복원한다.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      packages/*/dist
      packages/*/*.tsbuildinfo
    key: tsbuild-${{ hashFiles('packages/**/*.ts', '**/tsconfig*.json') }}
    restore-keys: tsbuild-
```

TypeScript 버전이 올라가면 .tsbuildinfo 포맷이 달라질 수 있으므로 컴파일러 버전도 키에 넣는 것이 안전하다.

## 9. 빌드 도구와의 역할 분담

Nx, Turborepo 같은 오케스트레이터를 얇는 경우 층위가 다르다. `tsc -b` 는 타입 정합성·.d.ts 생성의 증분성을, Turborepo/Nx 는 태스크 그래프 전반(린트·테스트·번들·배포)의 캐싱·병렬을 담당한다. References 토폴로지와 Turborepo `dependsOn` 그래프를 일치시켜야 빌드 순서가 어긋나지 않는다. 도입 판단은 타입체크 외 태스크의 캐싱·병렬화가 병목인가로 내린다.

## 참고

- TypeScript Handbook: "Project References"
- TypeScript Handbook: "tsc --build / Build Mode"
- TypeScript: `--incremental` 와 `.tsbuildinfo` 문서
- TypeScript Wiki: "Performance" — 대형 프로젝트 빌드 최적화
