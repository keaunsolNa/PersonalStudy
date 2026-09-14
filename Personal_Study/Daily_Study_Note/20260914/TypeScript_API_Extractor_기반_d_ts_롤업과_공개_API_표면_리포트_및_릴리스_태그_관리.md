Notion 원본: https://app.notion.com/p/3db5a06fd6d381e09f9bea29b88c2060?pvs=204

# TypeScript API Extractor 기반 d.ts 롤업과 공개 API 표면 리포트 및 릴리스 태그 관리

> 2026-09-14 신규 주제 · 확장 대상: TypeScript 선언 파일·패키지 배포

## 학습 목표

- `tsc --declaration` 이 만드는 파편화된 `.d.ts` 트리와 내부 타입 누출 경로를 진단한다
- API Extractor 의 entry point → API model → 롤업/리포트/doc model 파이프라인을 설정 파일 수준에서 구성한다
- `@public`·`@beta`·`@alpha`·`@internal` 릴리스 태그로 배포 산출물의 타입 표면을 계층별로 트리밍한다
- `.api.md` 리포트를 git 에 커밋하고 CI 에서 diff 를 검출해 공개 API 변경을 코드 리뷰 대상으로 만든다

## 1. tsc --declaration 이 남기는 것: 파편과 누출

`tsc --declaration` 은 소스 파일 하나당 선언 파일 하나를 그대로 찍어낸다. `src/index.ts`, `src/client.ts`, `src/internal/retry.ts`, `src/internal/backoff.ts` 가 있으면 `dist/` 밑에도 네 개의 `.d.ts` 와 그만큼의 `.d.ts.map` 이 생긴다. 소비자 입장에서 문제가 되는 건 파일 개수 자체가 아니라 **그 파일들이 서로를 상대경로로 참조한다**는 점이다. `dist/index.d.ts` 가 `import { RetryPolicy } from './internal/retry'` 를 포함하는 순간, `internal/retry.d.ts` 는 `package.json` 의 `exports` 로 막아놨든 말든 타입 해석 경로상 반드시 존재해야 하는 공개 파일이 된다. `exports` 맵에 `./internal/*` 를 노출하지 않으면 `moduleResolution: "bundler"` 나 `"node16"` 을 쓰는 소비자 쪽에서 타입 해석이 깨지고, 노출하면 내부 구현이 사실상 공개 API 가 된다.

두 번째 문제는 **구조적 누출**이다. TypeScript 는 추론된 반환 타입에 등장하는 모든 심벌을 선언 파일에 실어 나른다. 아래처럼 내부 타입을 export 하지 않았더라도, 공개 함수의 시그니처에 그 타입이 한 번이라도 등장하면 선언 파일은 그것을 참조해야만 한다.

```ts
// src/internal/retry.ts  — export 할 의도가 전혀 없던 타입
export interface RetryPolicy {
  maxAttempts: number;
  backoffMs: (attempt: number) => number;
  shouldRetry(err: unknown): boolean;
}

// src/client.ts
import type { RetryPolicy } from './internal/retry';

export class HttpClient {
  // 반환 타입을 명시하지 않아도 RetryPolicy 가 추론되어 .d.ts 에 박힌다
  public describeRetry() {
    return this.policy;
  }
  private policy!: RetryPolicy;
}
```

여기서 나온 `dist/client.d.ts` 는 `import('./internal/retry').RetryPolicy` 를 그대로 담는다. 즉 `RetryPolicy` 의 필드 이름 하나만 바꿔도 소비자의 타입 체크가 깨질 수 있고, semver 상 patch 로 올린 릴리스가 실제로는 breaking change 가 된다. 이 사고를 사람이 눈으로 막는 건 불가능에 가깝다 — `.d.ts` 가 20개 파일로 흩어져 있으면 PR diff 에서 "공개 표면이 무엇이 늘고 줄었는지"를 읽어낼 수 없기 때문이다. 롤업(rollup)은 이 두 문제를 한 번에 푼다. 선언을 **엔트리 포인트 하나의 파일로 평탄화**하면 상대경로 참조가 사라지고, 그 단일 파일이 곧 "이 패키지의 타입 계약서"가 되어 diff 로 검토할 수 있는 단위가 된다.

## 2. API Extractor 아키텍처: entry point → API model → 세 갈래 산출물

Microsoft API Extractor(`@microsoft/api-extractor`)는 번들러가 아니라 **선언 파일 분석기**다. 입력은 소스 `.ts` 가 아니라 `tsc` 가 이미 만들어 놓은 `.d.ts` 트리이고, 출력은 그 트리를 해석해 만든 중간 표현과 그로부터 파생된 산출물이다. 파이프라인은 다음 네 단계로 고정돼 있다.

1. **Entry point 로드** — `mainEntryPointFilePath` 가 가리키는 단 하나의 `.d.ts` 에서 시작한다. 이 파일에서 도달 가능한 심벌 그래프 전체가 분석 범위다.
2. **API model 구축** — 각 선언을 `ApiItem` 트리로 정규화한다. 클래스/인터페이스/타입 별칭/함수/변수/열거형에 릴리스 태그, TSDoc 주석, 시그니처 텍스트가 붙는다. 이 모델을 직렬화한 것이 `<package>.api.json` 이다.
3. **정적 검사** — 릴리스 태그 누락, 엔트리 포인트에서 export 되지 않은 참조(forgotten export), 태그 일관성 위반 등을 `ae-*` 메시지로 보고한다.
4. **산출물 생성** — 같은 API model 에서 `dtsRollup`(단일 `.d.ts`), `apiReport`(`.api.md`), `docModel`(`.api.json`) 세 가지를 각각 켜고 끌 수 있다.

중요한 함의는 **entry point 가 정확히 하나**라는 제약이다. `package.json` 의 `exports` 로 서브패스를 여러 개 내보내는 패키지라면, 서브패스마다 별도의 `api-extractor.<name>.json` 설정 파일을 두고 `api-extractor run -c` 로 각각 돌려야 한다. 또한 API Extractor 는 **런타임 코드를 전혀 건드리지 않는다.** JS 번들은 여전히 esbuild/rollup/tsup 이 만들고, API Extractor 는 타입 쪽만 책임진다.

```bash
# 설치와 초기화 — init 은 주석이 빼곡한 템플릿 설정을 만들어 준다
npm i -D @microsoft/api-extractor @microsoft/api-documenter typescript
npx api-extractor init

# 순서가 중요하다: 반드시 tsc 로 .d.ts 를 먼저 만들고 나서 실행
npx tsc -p tsconfig.build.json
npx api-extractor run --local --verbose
```

`--local` 은 "로컬 개발자 모드"로, API 리포트 파일이 없거나 달라졌을 때 **덮어쓰기**를 허용한다. 이 플래그를 빼면 CI 모드로 동작해 리포트가 최신이 아닐 때 0이 아닌 종료 코드로 실패한다. 흔히 `--verify` 라는 플래그가 있다고 오해하는데, `api-extractor run` 의 실제 플래그는 `--local`, `--verbose`, `--diagnostics`, `--config/-c`, `--typescript-compiler-folder` 이며 **검증은 `--local` 을 생략하는 것이 곧 검증 모드**다.

## 3. api-extractor.json 해부

설정 파일은 `<projectFolder>` 토큰을 기준으로 경로를 푼다. 실무에서 손대는 항목은 사실상 다섯 덩어리다.

```jsonc
{
  "$schema": "https://developer.microsoft.com/json-schemas/api-extractor/v7/api-extractor.schema.json",

  // 1) 입력: tsc 가 뱉은 선언 트리의 루트
  "mainEntryPointFilePath": "<projectFolder>/dist/types/index.d.ts",

  // 2) 의존성 타입 인라인: glob 지원 (예: "@acme/*")
  "bundledPackages": ["@acme/shared-types"],

  "compiler": {
    "tsconfigFilePath": "<projectFolder>/tsconfig.build.json"
  },

  // 3) d.ts 롤업 — 태그별로 다른 파일을 뽑는다
  "dtsRollup": {
    "enabled": true,
    "untrimmedFilePath": "<projectFolder>/dist/acme-sdk.untrimmed.d.ts",
    "betaTrimmedFilePath": "<projectFolder>/dist/acme-sdk.beta.d.ts",
    "publicTrimmedFilePath": "<projectFolder>/dist/acme-sdk.d.ts",
    "omitTrimmingComments": false
  },

  // 4) 공개 API 표면 리포트 — git 에 커밋할 파일
  "apiReport": {
    "enabled": true,
    "reportFolder": "<projectFolder>/etc/",
    "reportTempFolder": "<projectFolder>/temp/",
    "includeForgottenExports": false
  },

  // 5) 문서 생성을 위한 API model
  "docModel": {
    "enabled": true,
    "apiJsonFilePath": "<projectFolder>/temp/<unscopedPackageName>.api.json"
  },

  "newlineKind": "lf",
  "enumMemberOrder": "by-name"
}
```

`reportFolder` 와 `reportTempFolder` 가 나뉘어 있는 이유가 이 도구의 핵심 설계다. 실행할 때마다 API Extractor 는 **항상 temp 폴더에 리포트를 새로 쓴 뒤**, `reportFolder` 에 커밋돼 있는 기존 파일과 바이트 단위로 비교한다. 같으면 통과, 다르면 `--local` 여부에 따라 복사하거나 실패한다. 그래서 `temp/` 는 `.gitignore` 에, `etc/` 는 git 추적 대상에 넣는 것이 관례다.

`bundledPackages` 는 지정한 의존성의 선언을 롤업 파일 안으로 **인라인**한다. 모노레포 내부 공용 타입 패키지(`@acme/shared-types`)를 퍼블릭 npm 에 올리고 싶지 않을 때 유용하다. 다만 인라인된 타입은 원본 패키지의 버전과 영구히 분리되므로, 원본이 바뀌면 이 패키지를 다시 빌드·배포하지 않는 한 낡은 복사본이 남는다. 또 서드파티 라이선스 타입을 인라인하면 라이선스 고지 의무가 배포 산출물로 따라온다는 점도 잊기 쉽다.

`enumMemberOrder: "by-name"` 은 리포트 노이즈를 줄이려는 옵션이다. 기본값 `"by-declaration"` 에서는 열거형 멤버를 소스 순서대로 기록하므로 멤버를 중간에 끼워 넣기만 해도 리포트 diff 가 크게 흔들린다. 단, 이름순 정렬은 리뷰 안정성을 얻는 대신 **선언 순서 자체가 의미를 갖는 열거형**(비트 플래그 등)에서는 가독성을 잃는다.

## 4. 릴리스 태그와 트리밍 동작

릴리스 태그는 TSDoc 블록 태그이며, `@public` → `@beta` → `@alpha` → `@internal` 순으로 노출 수준이 낮아진다. 트리밍 규칙은 단순하다: 각 출력 파일은 **자기 수준 이상의 태그만** 남긴다.

```ts
/**
 * HTTP 클라이언트.
 * @public
 */
export declare class HttpClient {
  /** @public */
  get(url: string): Promise<Response>;

  /** 실험적 스트리밍 API. @beta */
  stream(url: string): AsyncIterable<Uint8Array>;

  /** 내부 계측용. @internal */
  _dumpPoolStats(): PoolStats;
}
```

| 출력 파일 | 포함되는 태그 | 용도 |
| --- | --- | --- |
| `untrimmedFilePath` | public + beta + alpha + internal | 사내 디버깅, 테스트 코드에서 내부 API 접근 |
| `alphaTrimmedFilePath` | public + beta + alpha | 얼리 어답터 채널 |
| `betaTrimmedFilePath` | public + beta | 프리릴리스 채널(`npm publish --tag next`) |
| `publicTrimmedFilePath` | public 만 | 정식 배포 `types` 필드가 가리킬 파일 |

`omitTrimmingComments: false` 이면 잘려 나간 자리에 `// Warning: (ae-forgotten-export) ...` 대신 `/* Excluded from this release type: stream */` 형태의 주석이 남아, 소비자가 "이 API 가 없어진 게 아니라 이 릴리스 타입에서 제외됐다"는 사실을 알 수 있다. 진짜로 흔적조차 남기기 싫다면 `true` 로 둔다.

배포 시점에는 `package.json` 이 트리밍된 파일을 가리키게 해야 의미가 있다.

```jsonc
{
  "name": "@acme/sdk",
  "types": "./dist/acme-sdk.d.ts",
  "exports": {
    ".": {
      "types": "./dist/acme-sdk.d.ts",
      "import": "./dist/index.mjs",
      "require": "./dist/index.cjs"
    }
  },
  "files": ["dist"],
  "publishConfig": { "access": "public" }
}
```

`@internal` 태그에는 별도 규약이 하나 더 붙는다. 내부 전용인데 언어 수준에서는 `public` 인 멤버는 이름을 밑줄로 시작하게 하라는 것이고, 어기면 `ae-internal-missing-underscore` 경고가 난다. 위 예제의 `_dumpPoolStats` 가 그 규약을 따른 형태다.

## 5. ae-* 경고 다루기

API Extractor 의 진짜 가치는 롤업보다 **정적 검사**에 있다. 세 가지 메시지가 전체의 90%를 차지한다.

- `ae-missing-release-tag` — 엔트리 포인트에서 export 된 선언에 릴리스 태그가 없음. 태그 없는 심벌은 트리밍 규칙을 적용할 근거가 없으므로 사실상 전부 공개로 취급된다.
- `ae-forgotten-export` — 공개 API 시그니처가 참조하는 타입이 엔트리 포인트에서 export 되지 않음. 1절의 `RetryPolicy` 가 정확히 이 케이스다. 해결은 (a) 엔트리 포인트에서 export 하고 태그를 붙이거나, (b) 시그니처에서 그 타입을 제거하거나, (c) `apiReport.includeForgottenExports: true` 로 리포트에만 기록하는 것 중 하나다.
- `ae-internal-missing-underscore` — `@internal` 인데 이름이 밑줄로 시작하지 않음.

각 메시지는 `messages` 섹션에서 `"error" | "warning" | "none"` 으로 승격·강등할 수 있다. 신규 프로젝트라면 처음부터 error 로 잠그는 편이 낫고, 레거시 패키지를 도입할 때는 `ae-forgotten-export` 만 warning 으로 두고 점진적으로 갚는다.

```jsonc
{
  "messages": {
    "compilerMessageReporting": {
      "default": { "logLevel": "warning" }
    },
    "extractorMessageReporting": {
      "default": { "logLevel": "warning" },
      "ae-missing-release-tag": { "logLevel": "error" },
      "ae-internal-missing-underscore": { "logLevel": "error" },
      "ae-forgotten-export": {
        "logLevel": "warning",
        "addToApiReportFile": true
      }
    },
    "tsdocMessageReporting": {
      "default": { "logLevel": "warning" },
      "tsdoc-undefined-tag": { "logLevel": "error" }
    }
  }
}
```

`addToApiReportFile: true` 는 경고 자체를 `.api.md` 안에 `// Warning: (ae-forgotten-export) The symbol "RetryPolicy" needs to be exported by the entry point index.d.ts` 형태로 기록한다. 이러면 빌드는 통과하되 **누출이 리뷰어 눈에 diff 로 보인다** — 기술 부채를 숨기지 않고 가시화하는 실용적인 절충이다.

## 6. .api.md 를 코드 리뷰 대상으로 만들기

`etc/acme-sdk.api.md` 는 사람이 읽기 좋게 정규화된 공개 표면 스냅샷이다. 실제 파일은 이런 모양이다.

````md
## API Report File for "@acme/sdk"

> Do not edit this file. It is a report generated by [API Extractor](https://api-extractor.com/).

```ts
// @public
export class HttpClient {
    get(url: string): Promise<Response>;
    // @beta
    stream(url: string): AsyncIterable<Uint8Array>;
}

// @public
export interface HttpClientOptions {
    // (undocumented)
    timeoutMs?: number;
}
```
````

이 파일이 git 에 있으면 워크플로우가 바뀐다. 개발자가 `timeoutMs?: number` 를 `timeoutMs: number` 로 바꾸면 소스 diff 한 줄과 **함께 리포트 diff 한 줄**이 PR 에 올라온다. 리뷰어는 소스를 읽지 않고도 "선택 프로퍼티가 필수가 됐다 → breaking" 을 즉시 판정할 수 있다. CODEOWNERS 로 `etc/**` 에 API 오너를 지정해두면 공개 표면 변경 PR 만 자동으로 아키텍트에게 리뷰가 배정된다.

```yaml
# .github/workflows/ci.yml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: 'npm' }
      - run: npm ci
      - run: npx tsc -b
      # --local 을 주지 않으므로 검증 모드: etc/*.api.md 가 낡았으면 실패
      - run: npx api-extractor run --verbose
      - name: 리포트가 낡았을 때 안내
        if: failure()
        run: echo "npm run api:update 를 실행하고 etc/*.api.md 를 커밋하세요" && exit 1
```

```jsonc
// package.json scripts
{
  "scripts": {
    "build:types": "tsc -b",
    "api": "api-extractor run --verbose",
    "api:update": "api-extractor run --local --verbose",
    "docs": "api-documenter markdown -i temp -o docs/api"
  }
}
```

`# .github/CODEOWNERS` 에 `etc/ @acme/api-council` 한 줄을 더하면, 리포트 파일이 곧 승인 게이트가 된다.

## 7. api-documenter 로 마크다운 문서 뽑기

`docModel.enabled: true` 로 만들어진 `.api.json` 이 문서 생성기의 입력이다. `@microsoft/api-documenter` 는 이 모델을 읽어 심벌마다 마크다운 페이지를 생성한다.

```bash
# temp/ 안의 *.api.json 을 모두 읽어 docs/api 아래에 페이지 트리 생성
npx api-documenter markdown --input-folder temp --output-folder docs/api

# DocFX 계열을 쓴다면 YAML 출력
npx api-documenter yaml --input-folder temp --output-folder docs/api-yaml
```

여러 패키지의 `.api.json` 을 한 폴더에 모아 넣으면 **패키지 간 링크가 자동으로 해석된다.** 모노레포에서 각 패키지의 `apiJsonFilePath` 를 저장소 루트의 공용 폴더(`<projectFolder>/../../common/api/`)로 지정해두는 패턴이 여기서 나온다. 반대로 패키지별로 흩어 두면 `{@link @acme/core#HttpClient}` 같은 교차 링크가 `ae-unresolved-link` 로 깨진다.

문서화 관점에서 중요한 TSDoc 태그는 `@remarks`, `@example`, `@param`, `@returns`, `@defaultValue`, `@deprecated`, `@throws` 다. 특히 `@deprecated` 는 리포트 파일에도 그대로 찍히므로, "이번 메이저에서 제거 예정"을 코드·문서·리뷰 세 곳에 동시에 심는 가장 값싼 방법이다.

```ts
/**
 * 요청을 보낸다.
 *
 * @param url - 절대 URL
 * @returns 응답 본문이 담긴 Promise
 * @throws {@link TimeoutError} `timeoutMs` 초과 시
 * @deprecated v4 에서 제거됨. {@link HttpClient.send} 를 사용할 것.
 * @example
 * ```ts
 * const res = await client.get('https://example.com');
 * ```
 * @public
 */
export function get(url: string): Promise<Response>;
```

## 8. 대안 비교: 언제 API Extractor 가 과한가

d.ts 롤업만 필요하다면 더 가벼운 선택지가 여럿 있다. 핵심 차이는 **속도**와 **API 거버넌스 기능의 유무**다.

| 도구 | 동작 방식 | 상대 속도 | 제네릭·조건부 타입 보존 | 선언 병합/`declare global` | 프로젝트 참조(`tsc -b`) | API 리포트 | 문서 생성 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `@microsoft/api-extractor` | `.d.ts` 트리 분석 후 재작성 | 느림 (TS 컴파일러 재로드) | 높음 | 제한적 | 잘 맞음(`.d.ts` 입력) | 있음 (`.api.md`) | 있음 (api-documenter) |
| `rollup-plugin-dts` | Rollup 그래프로 `.d.ts` 번들 | 빠름 | 높음 | 부분 지원 | 별도 설정 필요 | 없음 | 없음 |
| `tsup --dts` | 내부적으로 `rollup-plugin-dts` 사용 (`experimentalDts` 는 API Extractor 사용) | 빠름 | 높음 | 부분 지원 | 약함 | 없음 | 없음 |
| `dts-bundle-generator` | TS 컴파일러 API 로 직접 생성 | 중간 | 중간(옵션 의존) | 부분 지원 | 중간 | 없음 | 없음 |
| `tsc --declaration` + `paths` | 롤업 없음, 파일 그대로 | 가장 빠름 | 완전 | 완전 | 최상 | 없음 | 없음 |

판단 기준은 이렇게 정리된다. **외부에 배포하는 SDK/라이브러리이고 semver 를 지켜야 한다면** API Extractor 다 — 롤업은 부수 효과이고 진짜 값어치는 `.api.md` 와 릴리스 태그 거버넌스에 있다. **사내 애플리케이션 코드이거나 타입 표면이 자주 통째로 바뀌는 초기 단계**라면 `tsup --dts` 나 `rollup-plugin-dts` 로 충분하고, 빌드 시간이 수 배 짧다. 앱 내부용 패키지라면 롤업을 아예 하지 않고 `tsc -b` 산출물을 그대로 쓰는 편이 프로젝트 참조·증분 빌드·에디터 go-to-definition 모두에서 가장 정확하다.

한 가지 실전 팁: 런타임 번들과 타입 롤업을 서로 다른 도구가 맡는 구성이 가장 흔하고 안정적이다.

```jsonc
// tsup.config.ts 대신 스크립트 조합으로 역할을 분리
{
  "scripts": {
    "build": "npm run build:js && npm run build:types && npm run api",
    "build:js": "tsup src/index.ts --format esm,cjs --sourcemap --clean",
    "build:types": "tsc -p tsconfig.build.json --emitDeclarationOnly",
    "api": "api-extractor run --verbose"
  }
}
```

## 9. 모노레포 파이프라인과 남는 한계

모노레포에서는 순서가 전부다. API Extractor 는 `.d.ts` 를 입력으로 받으므로, 의존 패키지의 선언이 먼저 존재해야 한다. `tsc -b` 가 프로젝트 참조 그래프를 위상 정렬해 주므로 이것을 파이프라인의 1단계로 고정한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

# 1) 참조 그래프 순서대로 전 패키지 선언 생성 (증분)
npx tsc -b packages/*/tsconfig.build.json

# 2) 패키지별 롤업 + 리포트 검증 (CI 모드)
for pkg in packages/*/; do
  [ -f "$pkg/api-extractor.json" ] || continue
  (cd "$pkg" && npx api-extractor run --verbose)
done

# 3) 배포 메타데이터 검증
npx publint --strict
npx @arethetypeswrong/cli --pack . --ignore-rules cjs-resolves-to-esm

# 4) 게시
npm publish --provenance --access public
```

`publint` 는 `package.json` 의 `exports`/`main`/`types` 조합과 실제 파일 존재 여부를, `@arethetypeswrong/cli`(attw)는 **소비자의 `moduleResolution` 별로 타입이 올바르게 해석되는지**를 검사한다. API Extractor 가 "내가 만든 타입 파일의 내용"을 보증한다면, attw 는 "그 파일이 소비자에게 실제로 도달하는지"를 보증한다. 둘은 겹치지 않으므로 둘 다 돌려야 한다. 특히 롤업 파일 경로를 바꿔놓고 `exports.types` 를 갱신하지 않는 실수는 API Extractor 가 절대 잡아주지 못하는 종류다.

마지막으로 한계를 분명히 해 둘 것.

첫째, **런타임 코드는 번들되지 않는다.** `dtsRollup` 은 `.d.ts` 만 만든다. JS 를 합치는 일은 별도 번들러의 몫이고, 타입 롤업 파일 경로와 JS 번들 경로가 어긋나면 소비자만 피해를 본다.

둘째, **`declare module` 보강(module augmentation) 처리가 완전하지 않다.** 외부 모듈에 프로퍼티를 덧붙이는 패턴은 롤업 과정에서 원래 모듈 식별자와의 연결이 끊기기 쉽다. 아래처럼 보강 선언을 롤업 대상에서 빼고 별도 파일로 배포한 뒤 `exports` 나 `types` 의 보조 경로로 노출하는 우회가 현실적이다.

```ts
// src/augmentations.d.ts — 롤업에 넣지 않고 그대로 복사해 배포
import 'express';

declare module 'express' {
  interface Request {
    /** 인증 미들웨어가 채우는 필드 */
    acmeUser?: { id: string; scopes: readonly string[] };
  }
}
```

```jsonc
// package.json — 보강 파일을 별도 서브패스로 제공
{
  "exports": {
    ".": { "types": "./dist/acme-sdk.d.ts", "default": "./dist/index.mjs" },
    "./express": { "types": "./dist/augmentations.d.ts" }
  }
}
```

셋째, **`bundledPackages` 인라인의 비용**이다. 인라인된 타입은 (a) 원본 패키지의 라이선스 고지를 내 배포물로 끌고 오고, (b) 원본 버전이 올라가도 자동으로 따라가지 않으며, (c) 같은 타입을 인라인한 두 패키지를 한 프로젝트에서 함께 쓰면 **구조적으로는 같지만 명목상 다른 타입**이 생겨 `declaration merging` 이나 `instanceof` 기반 판별에 혼란을 준다. 내부 공용 타입 패키지에 한정해 쓰고, 서드파티 라이브러리 타입은 그냥 `dependencies` 로 두는 것이 안전하다.

넷째, **빌드 시간**이다. API Extractor 는 매 실행마다 TypeScript 컴파일러 인스턴스를 새로 띄워 선언 트리를 다시 읽는다. 증분 캐시가 없으므로 패키지 수에 선형으로 비례해 시간이 늘어난다. 패키지가 수십 개인 모노레포라면 `api-extractor run` 을 매 커밋마다 전 패키지에 돌리지 말고, `git diff --name-only` 로 변경된 패키지만 골라 돌리거나 Turborepo/Nx 의 태스크 캐시에 `etc/**` 와 `dist/**` 를 출력으로 등록해 캐시 히트를 노리는 편이 낫다.

## 참고

- [API Extractor 공식 사이트](https://api-extractor.com/)
- [api-extractor.json 설정 레퍼런스](https://api-extractor.com/pages/configs/api-extractor_json/)
- [릴리스 태그 개요](https://api-extractor.com/pages/tsdoc/doc_comment_syntax/)
- [API Extractor 메시지 목록](https://api-extractor.com/pages/messages/)
- [API Documenter CLI](https://api-extractor.com/pages/setup/generating_docs/)
- [TSDoc 공식 문서](https://tsdoc.org/)
- [TypeScript Handbook — Declaration Files](https://www.typescriptlang.org/docs/handbook/declaration-files/introduction.html)
- [TypeScript Handbook — Project References](https://www.typescriptlang.org/docs/handbook/project-references.html)
- [npm package.json exports 필드](https://nodejs.org/api/packages.html#exports)
- [publint](https://publint.dev/)
- [Are the Types Wrong?](https://arethetypeswrong.github.io/)
- [rollup-plugin-dts](https://www.npmjs.com/package/rollup-plugin-dts)
- [dts-bundle-generator](https://www.npmjs.com/package/dts-bundle-generator)
- [tsup 문서](https://tsup.egoist.dev/)
