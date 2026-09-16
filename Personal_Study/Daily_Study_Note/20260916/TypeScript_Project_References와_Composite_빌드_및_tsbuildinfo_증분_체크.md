Notion 원본: https://www.notion.so/3dd5a06fd6d381fc87f9f14ddf69a11a

# TypeScript Project References와 Composite 빌드 및 tsbuildinfo 증분 체크

> 2026-09-16 신규 주제 · 확장 대상: TypeScript 타입체크 성능 진단(extendedDiagnostics, 인스턴스화 폭증 제어)

## 학습 목표

- `composite`·`declaration`·`references` 세 옵션이 서로를 강제하는 관계를 설명한다
- `.tsbuildinfo` 가 무엇을 저장하고 어떤 조건에서 무효화되는지 파악한다
- 모노레포에서 경로 별칭 대신 참조를 써야 빌드가 정확해지는 이유를 안다
- `tsc -b` 의 재빌드 판단 로직을 읽고 캐시 미스를 진단한다

## 1. 단일 프로그램의 한계

`tsconfig.json` 하나로 모노레포 전체를 체크하면 체커는 모든 파일을 하나의 프로그램으로 본다. 패키지 A 의 한 줄을 고쳐도 패키지 Z 까지 전부 다시 계산된다. 파일 2,000개 규모에서 전체 체크가 90초 걸린다면, 한 글자 수정에도 90초를 낸다.

더 근본적인 문제는 **경계가 없다는 것**이다. 단일 프로그램에서는 패키지 A 가 패키지 B 의 내부 파일을 상대 경로로 직접 import 해도 타입 체크가 통과한다. 런타임 번들에서는 그 경로가 존재하지 않아 깨진다. 타입 레이어에 아키텍처 경계가 없으면 의존 규칙은 문서에만 남는다.

Project References 는 이 두 문제를 동시에 다룬다. 프로젝트를 여러 개의 독립 프로그램으로 쪼개고, 프로젝트 간 참조는 선언 파일(`.d.ts`)을 통해서만 이루어지게 한다.

## 2. 세 옵션의 강제 관계

```jsonc
// packages/core/tsconfig.json
{
	"compilerOptions": {
		"composite": true,
		"declaration": true,
		"declarationMap": true,
		"rootDir": "src",
		"outDir": "dist",
		"tsBuildInfoFile": "dist/.tsbuildinfo"
	},
	"include": ["src/**/*"]
}
```

`composite: true` 는 세 가지를 자동으로 켜거나 요구한다.

1. `declaration: true` 가 강제된다. 다른 프로젝트가 참조할 대상이 `.d.ts` 이기 때문이다.
2. `incremental: true` 가 암묵적으로 켜진다. `.tsbuildinfo` 생성이 전제된다.
3. `rootDir` 이 명시되지 않으면 `tsconfig.json` 위치가 기본값이 된다. `include` 에 들어간 모든 파일이 `rootDir` 하위여야 한다.

`declarationMap: true` 는 필수는 아니지만 사실상 필수다. 이것이 없으면 에디터에서 참조 프로젝트의 심볼로 "Go to Definition" 할 때 `.d.ts` 로 점프한다. 켜 두면 원본 `.ts` 로 간다. 모노레포에서 개발 경험 차이가 크다.

참조하는 쪽은 이렇게 쓴다.

```jsonc
// packages/api/tsconfig.json
{
	"compilerOptions": {
		"composite": true,
		"rootDir": "src",
		"outDir": "dist"
	},
	"include": ["src/**/*"],
	"references": [{ "path": "../core" }]
}
```

`references[].path` 는 `tsconfig.json` 이 있는 디렉터리 또는 파일 경로다. 이 선언의 효과는 두 가지다. 첫째, `tsc -b` 가 빌드 순서를 위상 정렬로 결정한다. 둘째, `@scope/core` 를 import 했을 때 체커가 `../core/dist/index.d.ts` 를 소스로 해석한다. **참조에 없는 프로젝트의 모듈은 import 자체가 에러가 된다.** 아키텍처 경계가 타입 레벨에서 강제되는 지점이다.

## 3. `tsc -b` 의 재빌드 판단

`tsc -b`(build 모드)는 일반 `tsc` 와 다른 프로그램이다. 각 프로젝트에 대해 "출력이 입력보다 최신인가"를 판정하고, 최신이면 건너뛴다.

판정 순서는 대략 이렇다.

1. 프로젝트의 `.tsbuildinfo` 가 있는가? 없으면 빌드.
2. `.tsbuildinfo` 에 기록된 컴파일러 옵션이 현재 옵션과 같은가? 다르면 전체 빌드.
3. 입력 파일 목록이 같은가? 파일이 추가·삭제되었으면 빌드.
4. 각 입력 파일의 버전(내용 해시)이 기록과 같은가? 다르면 해당 파일과 그 영향 범위를 다시 체크.
5. 참조 프로젝트의 출력 `.d.ts` 타임스탬프가 내 출력보다 최신인가? 최신이면 빌드.

여기서 4번의 "영향 범위"가 핵심이다. `.tsbuildinfo` 는 파일별 해시뿐 아니라 **참조 그래프(referencedMap)** 를 저장한다. `a.ts` 가 바뀌면 `a.ts` 를 import 하는 파일들만 다시 체크한다. 무관한 파일은 이전 진단 결과를 재사용한다.

```bash
npx tsc -b --verbose
```

`--verbose` 출력은 이런 식이다.

```
Project 'packages/core/tsconfig.json' is out of date because
  output 'packages/core/dist/.tsbuildinfo' is older than input 'packages/core/src/user.ts'
Building project 'packages/core/tsconfig.json'...
Project 'packages/api/tsconfig.json' is up to date with .d.ts files from its dependencies
```

마지막 줄이 중요하다. `core` 를 다시 빌드했는데도 `api` 는 건너뛰었다. `core` 의 **`.d.ts` 내용이 바뀌지 않았기** 때문이다. 함수 본문만 고치면 선언은 그대로이므로 하위 프로젝트가 영향을 받지 않는다. 이것이 Project References 의 실질적 이득이 나오는 지점이다.

## 4. `.tsbuildinfo` 안에 무엇이 있나

`.tsbuildinfo` 는 JSON 이며, 주요 필드는 다음과 같다.

| 필드 | 내용 |
|---|---|
| `fileNames` | 프로그램 입력 파일 목록 (인덱스로 참조됨) |
| `fileInfos` | 파일별 내용 해시, `affectsGlobalScope` 여부, 시그니처 |
| `referencedMap` | 파일 → 그 파일이 참조하는 파일들의 인덱스 배열 |
| `semanticDiagnosticsPerFile` | 파일별 이전 진단 결과 (에러 재출력용) |
| `options` | 빌드에 사용된 컴파일러 옵션 스냅샷 |
| `version` | TypeScript 버전 문자열 |

`version` 필드 때문에 TypeScript 를 업그레이드하면 모든 `.tsbuildinfo` 가 무효화되고 전체 재빌드가 일어난다. CI 캐시 키에 TypeScript 버전을 반드시 포함해야 하는 이유다.

`fileInfos` 의 **시그니처**는 파일이 만들어내는 선언의 해시다. 함수 본문만 바뀌면 시그니처는 그대로고, 그러면 그 파일을 import 하는 파일들의 재체크가 생략된다. `declaration: true` 인 프로젝트에서만 이 최적화가 정확히 동작한다. `composite` 가 `declaration` 을 강제하는 실질적 이유 중 하나다.

`affectsGlobalScope` 는 `declare global`, 전역 `d.ts`, `/// <reference>` 가 든 파일에 붙는다. 이런 파일이 바뀌면 참조 그래프와 무관하게 **전체 파일**이 재체크된다. 전역 타입 선언을 남발한 레포에서 증분 빌드가 전혀 빨라지지 않는 원인이 대개 여기다.

## 5. paths 별칭과 references 의 충돌

모노레포에서 흔한 설정은 이렇다.

```jsonc
{
	"compilerOptions": {
		"paths": {
			"@scope/core": ["../core/src/index.ts"]
		}
	}
}
```

이 설정은 참조 프로젝트의 **소스**를 직접 가리킨다. 편하지만 Project References 의 이점을 전부 없앤다. `core` 의 모든 소스 파일이 `api` 의 프로그램에 포함되므로, 프로그램이 다시 하나로 합쳐진 셈이 된다. `core` 의 함수 본문만 바뀌어도 `api` 가 재체크된다.

정확한 설정은 빌드 산출물을 가리키는 것이다.

```jsonc
{
	"compilerOptions": {
		"paths": {
			"@scope/core": ["../core/dist/index.d.ts"]
		}
	},
	"references": [{ "path": "../core" }]
}
```

또는 `paths` 를 아예 쓰지 않고 워크스페이스의 `node_modules` 심볼릭 링크와 각 패키지 `package.json` 의 `types` 필드에 맡기는 방법이 더 견고하다. 번들러·Jest·tsc 가 모두 같은 해석 규칙을 쓰게 되어 "타입은 되는데 런타임이 깨지는" 불일치가 사라진다.

```jsonc
// packages/core/package.json
{
	"name": "@scope/core",
	"main": "./dist/index.js",
	"types": "./dist/index.d.ts",
	"exports": {
		".": {
			"types": "./dist/index.d.ts",
			"import": "./dist/index.js"
		}
	}
}
```

`exports` 를 쓸 때는 `moduleResolution` 이 `bundler` 또는 `node16`/`nodenext` 여야 한다. `node10`(구 `node`)은 `exports` 를 읽지 않는다.

## 6. 개발 중 빌드 대기를 없애는 방법

Project References 의 불편한 점은 참조 대상이 **빌드되어 있어야** 에디터가 타입을 안다는 것이다. 클론 직후 `packages/api` 를 열면 온통 빨간 줄이 뜬다.

세 가지 대응이 있다.

**watch 빌드**: `tsc -b --watch` 를 백그라운드로 돌린다. 가장 정확하지만 프로세스가 하나 더 떠 있어야 하고, 대형 레포에서는 메모리를 먹는다.

```bash
npx tsc -b --watch --preserveWatchOutput
```

`--preserveWatchOutput` 이 없으면 매 빌드마다 콘솔이 지워져 로그 확인이 어렵다.

**publishConfig 분기**: 개발 중에는 `types` 가 소스를 가리키고 배포 시에만 `dist` 를 가리키게 한다. 편집기 경험은 좋아지지만 앞서 말한 프로그램 병합 문제가 다시 생긴다.

**사전 빌드 강제**: `postinstall` 에 `tsc -b` 를 걸어 클론 직후 한 번 빌드되게 한다. CI 와 로컬의 시작 상태를 같게 만드는 가장 단순한 방법이고, 실무에서 가장 무난하다.

```jsonc
{
	"scripts": {
		"postinstall": "tsc -b",
		"build": "tsc -b",
		"typecheck": "tsc -b --dry"
	}
}
```

`--dry` 는 실제 출력 없이 "무엇이 빌드될 것인지"만 보고한다. CI 에서 캐시가 제대로 먹었는지 확인할 때 쓴다.

## 7. 캐시 미스 진단

증분 빌드가 기대만큼 빠르지 않을 때 확인할 항목을 순서대로 적으면 이렇다.

```bash
npx tsc -b --verbose 2>&1 | grep -E "out of date|up to date"
```

출력에 나오는 이유 문자열이 곧 진단이다.

| 메시지 | 의미 | 조치 |
|---|---|---|
| `output ... does not exist` | 첫 빌드이거나 dist 삭제됨 | 정상 |
| `older than input ...` | 해당 입력이 실제로 수정됨 | 정상 |
| `oldest output ... is older than newest input` | 타임스탬프 역전 | 체크아웃 후 mtime 문제, `--force` 후 재시도 |
| `buildinfo file ... indicates that program needs to report errors` | 이전 빌드가 에러로 끝남 | 에러부터 해결 |
| `project is out of date because output of its dependency has changed` | 참조의 `.d.ts` 가 실제로 변경됨 | 정상, 다만 잦다면 §8 참고 |

CI 에서 캐시가 매번 미스라면 대개 원인은 셋 중 하나다. 캐시 키에 TypeScript 버전이 빠졌거나, `.tsbuildinfo` 를 캐시 대상에 포함하지 않았거나, 체크아웃이 파일 mtime 을 현재 시각으로 새로 찍기 때문이다. 마지막 항목 때문에 `tsc -b` 는 mtime 뿐 아니라 내용 해시도 함께 본다. 그래도 `.d.ts` 출력 타임스탬프 비교는 남아 있으므로, 캐시 복원 시 `dist` 와 `.tsbuildinfo` 를 함께 복원해야 한다.

```yaml
- uses: actions/cache@v4
  with:
    path: |
      packages/*/dist
      packages/*/dist/.tsbuildinfo
    key: tsbuild-${{ runner.os }}-ts${{ hashFiles('package-lock.json') }}-${{ hashFiles('packages/*/src/**/*.ts') }}
    restore-keys: |
      tsbuild-${{ runner.os }}-ts${{ hashFiles('package-lock.json') }}-
```

`restore-keys` 로 부분 일치 복원을 허용하는 것이 중요하다. 완전 일치만 허용하면 소스가 한 줄이라도 바뀐 순간 캐시가 전혀 쓰이지 않는다.

## 8. `.d.ts` 변동을 줄이는 설계

참조 프로젝트의 `.d.ts` 가 자주 바뀌면 하위 프로젝트가 계속 재빌드된다. 변동을 줄이는 방법은 공개 표면을 좁히는 것이다.

내부 구현 타입이 `.d.ts` 로 새어나오는 전형적 원인은 추론된 반환 타입이다.

```ts
// 나쁨: 내부 헬퍼의 반환 타입이 그대로 노출된다
export function createUser(input: UserInput) {
	return buildEntity(input); // 반환 타입이 추론되어 d.ts 에 인라인됨
}

// 좋음: 명시적 반환 타입으로 표면을 고정한다
export function createUser(input: UserInput): User {
	return buildEntity(input);
}
```

명시적 반환 타입은 `.d.ts` 생성 속도도 올린다. 체커가 본문을 분석해 타입을 합성할 필요가 없기 때문이다. `isolatedDeclarations: true` 를 켜면 이런 명시를 컴파일러가 강제한다. 켜는 순간 수백 개의 에러가 나올 수 있으므로 신규 패키지부터 적용하는 편이 현실적이다.

```jsonc
{
	"compilerOptions": {
		"composite": true,
		"declaration": true,
		"isolatedDeclarations": true
	}
}
```

`isolatedDeclarations` 의 진짜 목적은 별도에 있다. 각 파일의 `.d.ts` 를 타입 체크 없이 구문만으로 생성할 수 있게 만들어, 선언 생성을 병렬화하거나 다른 도구(oxc, swc 계열)에 위임할 수 있게 한다. 대형 모노레포에서 빌드 그래프의 임계 경로를 줄이는 수단이다.

마지막으로, 프로젝트를 너무 잘게 쪼개는 것도 손해다. 프로젝트마다 프로그램 생성·lib.d.ts 로딩 고정 비용이 붙는다. 경험적으로 파일 50개 미만의 프로젝트를 수십 개 만드는 것보다, 응집도 기준으로 10~20개로 묶는 편이 총 빌드 시간이 짧다.

## 참고

- TypeScript Handbook — Project References: https://www.typescriptlang.org/docs/handbook/project-references.html
- TSConfig Reference — composite / incremental / tsBuildInfoFile: https://www.typescriptlang.org/tsconfig
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- TypeScript 5.5 Release Notes — Isolated Declarations: https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html
- Node.js Documentation — Modules: Packages (exports 필드): https://nodejs.org/api/packages.html
