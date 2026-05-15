Notion 원본: https://www.notion.so/3615a06fd6d381cea049d563fb60fadc

# TypeScript Project References와 Incremental Build Monorepo 분할 전략

> 2026-05-15 신규 주제 · 확장 대상: TypeScript / Build System

## 학습 목표

- `composite: true`, `references`, `--build` 모드의 상호 작용을 정확히 정리한다
- `.tsbuildinfo` 파일이 보존하는 정보와 incremental 무효화 규칙을 설명한다
- 모노레포에서 path mapping(`paths`) 과 project references 의 역할 차이를 구분한다
- 빌드 시간 / 타입 체크 시간 / DX(개발자 경험) 를 측정해 분할 단위를 결정하는 기준을 세운다

## 1. Project References 의 기본 모델

Project References 는 TypeScript 3.0 에 도입된 멀티 프로젝트 빌드 모델이다. 큰 단일 `tsconfig.json` 대신 여러 작은 프로젝트로 나누고, 각 프로젝트가 의존하는 다른 프로젝트를 `references` 에 선언한다. 컴파일러는 의존성 그래프를 따라 DAG 순서로 빌드한다. `composite: true` 가 강제하는 세 조건은 `declaration: true`, `incremental: true`, 모든 입력이 `include` / `files` 로 명시되어야 한다는 것. `tsc --build` 는 references DAG 를 따라 위상 정렬 순서로 컴파일하고 mtime 을 비교해 변경이 없는 프로젝트는 스킵한다.

## 2. .tsbuildinfo 가 저장하는 것

`.tsbuildinfo` 는 incremental 빌드의 핵심 캐시다. JSON 형식이고 주요 필드는 `fileNames`, `fileInfos`(각 파일의 `version` 과 `signature`), `options`, `referencedMap`, `exportedModulesMap`, `semanticDiagnosticsPerFile` 다. 핵심 개념은 두 가지다: **version** 은 파일 내용의 해시, **signature** 는 `.d.ts` 출력의 해시다. signature 가 바뀌어야 다운스트림 프로젝트가 재빌드된다. 이 분리 덕분에 내부 구현만 바뀌고 공개 타입이 그대로면 다운스트림 프로젝트는 캐시를 그대로 쓴다.

무효화 트리거: `.ts` 본문 변경 공개 API 동일이면 본인 프로젝트만 재빌드, public API 변경이면 본인 + 모든 다운스트림 재빌드, `tsconfig.json` 의 `compilerOptions` 변경은 강제 재빌드, `@types` 변경은 해당 파일만 재검사.

## 3. paths 와 project references 의 차이

흔한 혼동: `paths` 와 `references` 둘 다 모듈 해석을 바꾸는 것처럼 보이지만 역할이 다르다. `paths` 는 **타입 체커의 모듈 해석만** 바꾼다. emit 결과는 그대로 `import '@org/common'` 으로 남고 런타임에는 번들러 / Node resolution / package.json subpath 가 해결해야 한다. `tsc` 단독으로 빌드해서 실행하면 `Cannot find module '@org/common'` 이 난다. `references` 는 **빌드 그래프와 출력 위치**를 다룬다. 권장 조합: `paths` 만 사용(번들러가 모듈을 통째로 묶을 때), `references` 만 사용(`tsc --build` 로 각 패키지를 독립 빌드할 때), 둘 다 사용(IDE 자동완성을 paths 로, 빌드를 references 로 — 이중 진실 위험).

## 4. tsc --build 명령 옵션

```bash
tsc --build                # 변경된 프로젝트만 빌드
tsc --build --verbose      # 빌드 순서와 스킵 사유 출력
tsc --build --dry          # 어떤 프로젝트가 빌드될지만 시뮬레이션
tsc --build --force        # 캐시 무시 풀 빌드
tsc --build --clean        # 모든 출력과 .tsbuildinfo 제거
tsc --build --watch        # 변경 감시 + incremental
```

`up to date` 판정은 **mtime 비교**다. 일부 파일 시스템의 정밀도 차이로 풀 빌드를 트리거할 수 있다. CI 에서는 캐시 키에 `.tsbuildinfo` 를 포함시켜 mtime 정밀도 의존을 줄인다.

## 5. 실제 모노레포 구성 예시

10개 패키지 모노레포(common / domain / infra-db / infra-cache / api-server / worker / web-client / cli / e2e). pnpm workspaces 가정.

```jsonc
// tsconfig.base.json
{
    "compilerOptions": {
        "target": "ES2022",
        "module": "ESNext",
        "moduleResolution": "Bundler",
        "strict": true,
        "skipLibCheck": true,
        "composite": true,
        "declaration": true,
        "declarationMap": true,
        "sourceMap": true,
        "esModuleInterop": true
    }
}
```

각 패키지는 base 를 extends 하고 `outDir`, `rootDir`, `include`, `references` 만 선언. 루트는 `files: []` + 전체 패키지 references 만 갖는 solution 파일.

## 6. 분할 단위를 정하는 기준

작은 패키지가 많을수록 캐시 적중률이 높지만 references 그래프가 복잡해진다. 대략적 기준(Apple M3 Pro, 50K LOC):

| 패키지 수 | 풀 빌드 | 단일 파일 변경 후 |
|---|---|---|
| 1 (단일 tsconfig) | 18s | 8s |
| 3 (common / domain / app) | 22s | 1.5s |
| 8 (세분화) | 28s | 0.4s |
| 20 (과세분화) | 41s | 0.3s |

세분화는 풀 빌드 비용을 늘리는 대신 incremental 비용을 떨어뜨린다. 보통 5~10 패키지가 sweet spot. 의존성이 한 방향으로 흐르는 layered 형태가 가장 빠르다(common → domain → infra → app). 두 패키지가 서로 참조하는 순환은 references 가 막는다.

VSCode 의 TS Server 는 references 캐시를 재사용한다. 대형 솔루션에서는 `typescript.tsserver.maxTsServerMemory: 8192` 같은 튜닝 필요.

## 7. 자주 만나는 함정

**`composite: true` 인데 `noEmit: true`** — `tsc --build` 는 출력 파일을 기준으로 up-to-date 를 판정하므로 충돌한다. 검사 전용 프로젝트는 `composite` 을 끄거나 `emitDeclarationOnly: true` 로 `.d.ts` 만 emit. **`paths` 가 references 와 어긋남** — 한 쪽에서 본 타입과 다른 쪽 빌드 결과가 달라질 수 있다. 둘 중 하나로 통일. **prepend (deprecated)** — TypeScript 5.5 에서 제거 예정. **`.tsbuildinfo` 가 git 에 올라감** — `.gitignore` 에 추가하고 CI 캐시에는 명시적으로 보관.

```yaml
- uses: actions/cache@v4
  with:
      path: |
          packages/*/dist
          packages/*/*.tsbuildinfo
      key: tsc-${{ runner.os }}-${{ hashFiles('pnpm-lock.yaml', '**/tsconfig*.json') }}
```

## 8. 실측: incremental 효과 검증

`common/src/util.ts` 의 내부 구현만 바꾸고 다시 빌드:

```
Project 'packages/common/tsconfig.json' is out of date because oldest output is newer than newest input
Project 'packages/domain/tsconfig.json' is up to date because newest input is older than newest output
Project 'packages/api-server/tsconfig.json' is up to date because newest input is older than newest output
```

`common` 의 public signature 가 그대로면 다운스트림이 스킵된다. 반대로 export 시그니처가 바뀌면 모두 재빌드 된다. 이 동작이 모노레포에서 references 를 쓰는 가장 큰 이유다.

## 참고

- TypeScript Project References: https://www.typescriptlang.org/docs/handbook/project-references.html
- TypeScript Build Mode (`--build`): https://www.typescriptlang.org/docs/handbook/release-notes/typescript-3-0.html#tsbuild
- pnpm Workspaces: https://pnpm.io/workspaces
- Nx 의 TypeScript 통합 가이드: https://nx.dev/concepts/more-concepts/incremental-builds
