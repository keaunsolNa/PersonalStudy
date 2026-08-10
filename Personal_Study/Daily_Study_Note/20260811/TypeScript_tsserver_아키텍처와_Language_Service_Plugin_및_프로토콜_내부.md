Notion 원본: https://www.notion.so/3b85a06fd6d381ce97f5cdd62137e3e0

# TypeScript tsserver 아키텍처와 Language Service Plugin 및 프로토콜 내부

> 2026-08-11 신규 주제 · 확장 대상: TypeScript (Compiler API · 컴파일 성능 진단 기학습)

## 학습 목표

- tsserver 프로세스 모델과 editor ↔ server JSON 프로토콜의 요청/응답 흐름을 추적한다
- Project System(Configured/Inferred/External Project)의 로딩 규칙과 메모리 구조를 분석한다
- Language Service Plugin을 작성해 진단·자동완성을 커스터마이징한다
- 대형 모노레포에서 tsserver 메모리·응답 지연을 진단하고 튜닝한다

## 1. tsserver는 무엇이 다른가 — tsc와의 구조적 차이

`tsc`는 배치 컴파일러다. 전체 프로그램을 한 번 로드해 타입 검사 후 emit하고 종료한다. 반면 `tsserver`는 **장수명(long-lived) 프로세스**로, 에디터가 켜져 있는 동안 계속 살아 있으면서 파일 편집마다 증분으로 프로그램 상태를 갱신한다. VS Code에서 TypeScript IntelliSense가 동작할 때 실제로 일하는 프로세스가 이것이며, `node .../typescript/lib/tsserver.js` 형태로 확인할 수 있다.

핵심 차이는 세 가지다. 첫째, tsserver는 **LanguageService** 계층을 사이에 두고 Program을 재사용한다. 편집이 발생하면 전체 재파싱이 아니라 해당 SourceFile의 텍스트 스냅샷만 교체하고, 의존 그래프상 영향을 받는 파일의 타입 검사 결과만 무효화한다. 둘째, tsserver의 응답 단위는 "파일 전체 진단"이 아니라 "요청 위치 기준 정보"다. `quickinfo`(hover), `completions`, `definition` 같은 요청은 커서 위치의 노드만 바인딩·검사해 응답하므로 전체 검사보다 훨씬 싸다. 셋째, 진단은 **우선순위가 나뉜 비동기 이벤트**로 push된다. `syntacticDiag`(파싱 에러) → `semanticDiag`(타입 에러) → `suggestionDiag`(미사용 변수 등) 순서로, 편집이 계속되면 이전 요청을 취소한다.

```bash
# tsserver를 직접 띄워 프로토콜을 관찰
node ./node_modules/typescript/lib/tsserver.js

# stdin으로 요청 (Content-Length 헤더 없이 줄 단위 JSON)
{"seq":1,"type":"request","command":"open","arguments":{"file":"/proj/src/index.ts"}}
{"seq":2,"type":"request","command":"quickinfo","arguments":{"file":"/proj/src/index.ts","line":10,"offset":5}}
```

응답은 `{"seq":0,"type":"response","command":"quickinfo","request_seq":2,"success":true,"body":{...}}` 형태다. LSP(Language Server Protocol)와 다른 **TypeScript 고유 프로토콜**이라는 점이 중요하다. VS Code는 이 네이티브 프로토콜을 직접 쓰고, 다른 에디터(Neovim 등)는 `typescript-language-server`라는 LSP 어댑터를 중간에 끼운다. 어댑터 계층이 있으면 요청 변환 비용과 기능 공백(예: 일부 refactor)이 생긴다.

## 2. 프로세스 모델 — syntax server / semantic server / partial semantic

VS Code는 기본적으로 tsserver를 **두 개** 띄운다. `syntax server`는 파싱 수준 기능(문법 하이라이트 보조, outline, 괄호 매칭)만 담당해 즉각 응답하고, `semantic server`는 타입 검사가 필요한 무거운 요청을 담당한다. 대형 프로젝트에서 semantic server가 프로젝트 로딩에 수십 초 걸리는 동안에도 syntax server 덕분에 편집 자체는 끊기지 않는다.

`--partialSemantic` 모드는 원격 저장소 브라우징(github.dev) 같은 환경에서 쓰이는 제3의 모드로, import 해석 없이 열린 파일만으로 제한된 IntelliSense를 제공한다. 프로세스 기동 옵션은 VS Code 설정으로 제어한다:

```jsonc
// settings.json
{
  "typescript.tsserver.maxTsServerMemory": 8192,       // 기본 3072MB, 모노레포는 상향
  "typescript.tsserver.useSyntaxServer": "auto",       // never로 끄면 단일 서버
  "typescript.tsserver.log": "verbose",                // tsserver.log 파일 생성
  "typescript.tsserver.enableRegionDiagnostics": true  // 5.6+: 보이는 영역 우선 진단
}
```

`maxTsServerMemory`는 Node의 `--max-old-space-size`로 전달된다. 8GB로 올려도 해결이 안 되면 메모리 사용 자체(Project 수, 파일 수)를 줄여야 한다는 신호다. 5.6에 들어온 region-prioritized diagnostics는 에디터 뷰포트에 보이는 코드 영역의 진단을 먼저 계산해 체감 지연을 줄인다. 파일 전체 검사가 3초 걸리는 파일에서 보이는 영역만 먼저 0.3초에 표시하는 식이다.

## 3. Project System — Configured / Inferred / External Project

tsserver의 메모리 구조를 이해하려면 "Project"가 무엇인지 정확히 알아야 한다. 파일이 `open` 요청으로 열리면 tsserver는 그 파일이 속할 프로젝트를 찾는다.

**ConfiguredProject**는 파일에서 상위 디렉터리로 올라가며 찾은 `tsconfig.json`(또는 `jsconfig.json`) 기반 프로젝트다. tsconfig의 `files`/`include`가 가리키는 **모든 파일**이 프로젝트에 로드된다 — 열린 파일 하나 때문에 그 tsconfig가 커버하는 3,000개 파일이 메모리에 올라간다는 뜻이다. **InferredProject**는 tsconfig가 없는 고아 파일용으로, 열린 파일과 그 import 클로저만 담는다. **ExternalProject**는 VS 같은 IDE가 프로젝트 구조를 직접 밀어넣는 형태로 VS Code에서는 쓰이지 않는다.

모노레포에서 문제가 되는 지점이 여기다. `packages/a/src/x.ts`를 열었는데 이 파일이 `packages/a/tsconfig.json`에도 속하고, 루트 `tsconfig.json`(전체 include)에도 속하면 **두 프로젝트가 모두 로드**될 수 있다. Project References를 쓰면 `disableSourceOfProjectReferenceRedirect`, `disableSolutionSearching` 옵션으로 "참조 프로젝트의 소스로 점프할 때만 해당 프로젝트를 로드"하도록 제어할 수 있다.

```jsonc
// 루트 tsconfig.json — solution 스타일 (파일을 직접 include하지 않음)
{
  "files": [],
  "references": [
    { "path": "./packages/a" },
    { "path": "./packages/b" }
  ]
}
```

solution 스타일 루트는 자체적으로 파일을 갖지 않으므로 루트가 거대 프로젝트로 로드되는 사고를 막는다. tsserver 로그에서 `Creating configuredProject: ...`, `Project '...' (Configured) projectProgramVersion` 라인을 세어 보면 실제 몇 개의 프로젝트가 떠 있는지 확인할 수 있다.

## 4. 문서 동기화와 ScriptInfo — 편집이 반영되는 경로

에디터의 키 입력은 `change` 요청으로 tsserver에 전달된다. tsserver는 파일마다 `ScriptInfo` 객체를 유지하는데, 여기에는 텍스트의 **버전화된 스냅샷**과 이 파일을 포함하는 프로젝트 목록이 들어 있다. 편집이 오면 스냅샷 버전이 올라가고, 소속 프로젝트들의 `projectProgramVersion`이 dirty로 표시된다. 다음 요청이 들어올 때 비로소 `updateGraph()`가 실행되어 Program이 재구성된다 — **지연 평가**라서 연속 타이핑 중에는 그래프 갱신이 일어나지 않는다.

Program 재구성은 전체 재파싱이 아니다. `SourceFile`은 immutable이므로 변경된 파일만 새 SourceFile로 교체되고, 나머지는 이전 Program에서 재사용된다. 다만 **TypeChecker는 Program마다 새로 만들어진다**. 타입 캐시(노드 → 타입 매핑)가 checker에 붙어 있으므로, 파일 하나를 고쳤도 다른 파일의 타입을 다시 물어보면 재계산이 필요하다. 대형 파일에서 hover가 느린 근본 원인이 이것이다. 순환 import가 많거나 거대한 조건부 타입이 있으면 checker 재생성 비용이 그대로 응답 지연으로 나타난다.

파일 워칭도 tsserver 응답성에 영향을 준다. `node_modules` 변경(패키지 설치)이 감지되면 모듈 해석 캐시가 무효화되고 사실상 프로젝트 전체 재로드가 일어난다. 워처 구현은 `watchOptions`로 제어한다:

```jsonc
{
  "watchOptions": {
    "watchFile": "useFsEvents",
    "watchDirectory": "useFsEvents",
    "excludeDirectories": ["**/node_modules/.cache", "**/dist"]
  }
}
```

## 5. Language Service Plugin — 진단·완성 커스터마이징

tsserver는 플러그인 훅을 공식 지원한다. 플러그인은 `LanguageService` 인터페이스를 **프록시로 감싸** 특정 메서드만 오버라이드하는 구조다. GraphQL 태그드 템플릿 내부 자동완성(`ts-graphql-plugin`), styled-components CSS 검사, Angular Language Service가 모두 이 방식이다.

```ts
// my-ts-plugin/index.ts
import type ts from "typescript/lib/tsserverlibrary";

function init(modules: { typescript: typeof ts }) {
	function create(info: ts.server.PluginCreateInfo) {
		const proxy: ts.LanguageService = Object.create(null);
		const oldLS = info.languageService;

		for (const k of Object.keys(oldLS) as (keyof ts.LanguageService)[]) {
			const x = oldLS[k]!;
			// @ts-expect-error 프록시 패스스루
			proxy[k] = (...args: unknown[]) => x.apply(oldLS, args);
		}

		// 시맨틱 진단에 커스텀 룰 추가: TODO 주석이 달린 export 금지
		proxy.getSemanticDiagnostics = (fileName) => {
			const prior = oldLS.getSemanticDiagnostics(fileName);
			const source = info.languageService.getProgram()?.getSourceFile(fileName);
			if (!source) {
				return prior;
			}
			const extra: ts.Diagnostic[] = [];
			source.forEachChild(function walk(node) {
				const text = node.getFullText(source);
				if (ts.isExportAssignment(node) && text.includes("// TODO")) {
					extra.push({
						file: source,
						start: node.getStart(),
						length: node.getWidth(),
						messageText: "TODO가 남은 export는 머지 금지",
						category: modules.typescript.DiagnosticCategory.Warning,
						code: 90001
					});
				}
				node.forEachChild(walk);
			});
			return [...prior, ...extra];
		};

		return proxy;
	}
	return { create };
}

export = init;
```

```jsonc
// tsconfig.json — 플러그인 등록
{
  "compilerOptions": {
    "plugins": [{ "name": "my-ts-plugin" }]
  }
}
```

주의할 제약이 두 가지 있다. 첫째, **플러그인은 에디터 경험에만 영향**을 준다. `tsc` 배치 빌드는 플러그인을 로드하지 않으므로 플러그인이 추가한 진단은 CI에서 잡히지 않는다. CI 강제까지 필요하면 같은 룰을 typescript-eslint 커스텀 룰로 이중 구현해야 한다. 둘째, 플러그인은 tsserver 프로세스 안에서 동기 실행되므로 느린 플러그인은 모든 IntelliSense를 함께 느리게 만든다. `getSemanticDiagnostics` 오버라이드에서 파일당 수 ms 이내로 끝나도록 AST 순회 범위를 제한해야 한다.

## 6. 프로토콜 주요 커맨드와 취소 메커니즘

에디터 기능과 프로토콜 커맨드의 매핑을 알면 tsserver 로그를 읽을 수 있다. hover는 `quickinfo`, 자동완성은 `completionInfo` + 상세 요청 `completionEntryDetails`, 정의 이동은 `definitionAndBoundSpan`, 참조 찾기는 `references`, 리네임은 `rename`, 저장 시 자동 import 정리는 `organizeImports`, 리팩터링 목록은 `getApplicableRefactors`다. 진단은 요청-응답이 아니라 `geterr` 요청 후 `syntaxDiag`/`semanticDiag`/`suggestionDiag` 이벤트로 흘러온다.

취소는 두 층위로 동작한다. `geterr`는 `delay` 파라미터를 갖고 있어 타이핑이 이어지면 서버가 스스로 이전 진단 계산을 버린다. 더 무거운 요청은 **cancellation pipe**(파일 기반 세마포어)로 중단한다. tsserver 기동 시 `--cancellationPipeName` 경로를 받고, 에디터가 취소 파일을 생성하면 checker 내부 루프가 주기적으로 이를 폴링해 `OperationCanceledException`을 던진다. 타입 검사가 원자적으로 오래 걸리는 단일 표현식(거대 조건부 타입 인스턴스화)은 폴링 지점이 없어 취소가 안 먹는다 — "한 줄 고쳤는데 에디터가 5초 얼어붙는" 사례의 전형적 원인이다.

| 에디터 동작 | 프로토콜 커맨드 | 비용 |
| --- | --- | --- |
| hover | quickinfo | 낮음 (위치 기준) |
| 자동완성 목록 | completionInfo | 중간 (스코프 심볼 수집) |
| 완성 항목 상세 | completionEntryDetails | 항목당 타입 문자열화 |
| 정의 이동 | definitionAndBoundSpan | 낮음 |
| 전체 진단 | geterr → *Diag 이벤트 | 높음 (파일 전체 검사) |
| 참조 찾기 | references | 매우 높음 (프로젝트 전역) |

## 7. 성능 진단 — tsserver 로그와 서버 이벤트 읽기

체감 지연을 계측하는 1차 도구는 tsserver 로그다. `"typescript.tsserver.log": "verbose"` 설정 후 커맨드 팔레트에서 "TypeScript: Open TS Server log"로 연다. 로그에서 봐야 할 패턴은 다음과 같다.

```
Perf 2841 [12:03:11.045] 312::completionInfo: elapsed time (in milliseconds) 1843.2011
Event 2903 [12:03:14.101] event: {"event":"projectsUpdatedInBackground",...}
Loading configured project /repo/tsconfig.json ... elapsed 41235ms
```

`elapsed time` 이 100ms를 넘는 요청이 반복되면 어떤 커맨드가 병목인지 특정할 수 있다. `completionInfo`가 느리면 대부분 거대한 유니온/제네릭 완성 목록 생성이 원인이고, `updateGraph`가 느리면 프로젝트 파일 수 자체가 문제다. 프로젝트 로딩 시간이 수십 초라면 `include` 범위와 `types` 배열(기본값이 `node_modules/@types` 전부 로드)을 좁힌다:

```jsonc
{
  "compilerOptions": {
    "types": ["node"],          // @types 자동 로드를 명시 목록으로 제한
    "skipLibCheck": true
  },
  "include": ["src"],           // 테스트 픽스처·생성물 제외
  "exclude": ["**/__generated__"]
}
```

타입 수준 병목은 tsserver 로그만으로 부족하고, 기학습한 `tsc --generateTrace`로 인스턴스화 폭발 지점을 찾은 뒤 해당 타입을 단순화하는 흐름으로 이어진다. 실무 감각으로는 (1) 프로젝트 로드 30초+ → tsconfig 구조 문제, (2) 모든 요청이 균일하게 느림 → 메모리 부족으로 GC 스래싱, (3) 특정 파일에서만 느림 → 그 파일이 import하는 타입 복잡도 문제로 삼분해서 접근한다.

## 8. tsserver의 미래 — 네이티브 포팅(tsgo)과 LSP 전환

Microsoft는 TypeScript 컴파일러·서버를 Go로 포팅하는 작업(코드네임 Corsa, `typescript-go`)을 진행해 왔고, 네이티브 구현에서는 프로세스 아키텍처가 **LSP 표준 기반**으로 재설계된다. JS 구현의 tsserver 고유 프로토콜이 LSP로 수렴하면 typescript-language-server 같은 어댑터 계층이 사라지고, 에디터 간 기능 격차도 줄어든다. 네이티브 포팅은 공유 메모리 병렬 파싱·검사로 프로젝트 로드 시간을 자릿수 단위로 줄이는 것이 목표라는 점에서, 위 §7의 튜닝 상당수는 "그때까지의 생존 기술"에 해당한다.

다만 Language Service Plugin처럼 JS 런타임 안에서 동기 프록시로 동작하던 확장 모델은 네이티브 서버에서 동일하게 재현되기 어렵다. 플러그인에 깊이 의존하는 도구 체인(GraphQL 플러그인, styled-components 등)은 LSP 미들웨어나 별도 language server로의 이행을 준비해야 한다. 신규 도구를 설계한다면 처음부터 tsserver 플러그인보다 **독립 LSP 서버 + tsserver 병행** 구조를 선택하는 편이 이식성이 높다.

## 9. 정리 — 실무 체크리스트

대형 코드베이스에서 IntelliSense가 느릴 때의 진단 순서를 정리한다. 먼저 tsserver 로그를 verbose로 켜고 어떤 커맨드의 elapsed time이 큰지 확인한다. 프로젝트 로드가 느리면 solution 스타일 루트 tsconfig와 Project References로 로드 단위를 쪼개고, `types`·`include`를 좁힌다. 응답이 전반적으로 느리면 `maxTsServerMemory`를 올리되, 근본적으로는 열린 파일이 유발하는 ConfiguredProject 수를 로그로 세어 본다. 특정 파일만 느리면 generateTrace로 타입 인스턴스화 병목을 찾는다. 팀 고유 규칙을 에디터에서 즉시 보여주고 싶다면 Language Service Plugin을 작성하되, CI 강제는 별도 lint 룰로 이중화한다는 원칙을 지킨다.

## 참고

- TypeScript Wiki — Standalone Server (tsserver): https://github.com/microsoft/TypeScript/wiki/Standalone-Server-%28tsserver%29
- TypeScript Wiki — Writing a Language Service Plugin: https://github.com/microsoft/TypeScript/wiki/Writing-a-Language-Service-Plugin
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- microsoft/typescript-go (네이티브 포팅): https://github.com/microsoft/typescript-go
- VS Code Docs — TypeScript performance: https://code.visualstudio.com/docs/typescript/typescript-compiling
