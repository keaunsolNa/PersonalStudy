Notion 원본: https://www.notion.so/3755a06fd6d381a5b8f5d95ce27db0d2

# TypeScript typescript-eslint Type-Aware Linting과 Project Service 아키텍처

> 2026-06-04 신규 주제 · 확장 대상: TypeScript Compiler API / ts-morph AST 조작 (학습됨)

## 학습 목표

- typed linting 이 ESLint 규칙에 TypeScript 타입 체커를 주입하는 메커니즘을 parserServices 수준에서 분석한다
- `parserOptions.project` 방식과 TS 8.x 시대의 `projectService` 방식의 프로그램 생성 전략 차이를 비교한다
- 타입 정보를 사용하는 커스텀 ESLint 규칙을 직접 작성하고 ESTree ↔ TS AST 노드 매핑을 다룬다
- 대형 모노레포에서 typed linting 의 실행 시간을 프로파일링하고 병목별 완화 전략을 적용한다

## 1. typed linting의 구조 — 두 개의 AST와 하나의 체커

ESLint 는 ESTree 형식 AST 위에서 동작하고, TypeScript 체커는 자체 AST(`ts.Node`) 위에서 동작한다. `@typescript-eslint/parser` 는 소스를 한 번 파싱해 **두 AST 를 동시에 만들고 노드 간 양방향 매핑을 유지**한다.

```
source.ts ──parse──▶ ts.SourceFile (TS AST)
                │
                └──convert──▶ TSESTree (ESTree 호환) + node maps
                                  │
                          ESLint rule 실행
                                  │
                  parserServices.getTypeAtLocation(esNode)
                                  │
                  esTreeNodeToTSNodeMap.get(esNode) → ts.Node
                                  │
                          checker.getTypeAtLocation(tsNode)
```

규칙이 타입을 묻는 순간 ESTree 노드 → TS 노드 → 타입 체커 순으로 위임된다. 이 구조 때문에 typed linting 은 "ESLint 실행 + tsc 타입 체크"를 합친 비용이 들고, 비-typed 규칙만 쓸 때보다 3~6배 느려지는 것이 일반적이다.

## 2. parserOptions.project — 고전적 방식과 그 비용

```jsonc
// .eslintrc (legacy) 또는 eslint.config.mjs
{
	"parser": "@typescript-eslint/parser",
	"parserOptions": {
		"project": ["./tsconfig.json", "./packages/*/tsconfig.json"]
	}
}
```

이 방식은 lint 시작 시 명시된 tsconfig 마다 `ts.Program` 을 만든다. 문제가 세 가지 있다.

- **파일→프로그램 매칭 비용**: lint 대상 파일이 어느 tsconfig 의 include 에 속하는지 찾기 위해 glob 매칭을 반복한다. tsconfig 가 수십 개면 이 탐색만으로 수 초가 걸린다.
- **out-of-project 파일 에러**: tsconfig include 에 없는 파일(`*.config.ts`, 스크립트)을 lint 하면 "file was not found in any of the provided project(s)" 에러가 난다. `allowDefaultProject` 같은 우회가 없어서 별도 `tsconfig.eslint.json` 을 만드는 관행이 생겼다.
- **에디터와 CLI 의 프로그램 불일치**: IDE 의 tsserver 와 ESLint 가 각자 프로그램을 만들어 메모리를 이중으로 쓴다. VS Code 에서 ESLint 확장이 1.5GB 를 점유하는 주범이다.

## 3. projectService — tsserver를 그대로 재사용

typescript-eslint v8 에서 기본 권장이 된 `projectService` 는 직접 프로그램을 만들지 않고 **TypeScript 의 tsserver ProjectService API** 를 사용한다. IDE 가 쓰는 것과 동일한 코드 경로다.

```javascript
// eslint.config.mjs (flat config)
import tseslint from "typescript-eslint";

export default tseslint.config(
	...tseslint.configs.recommendedTypeChecked,
	{
		languageOptions: {
			parserOptions: {
				projectService: {
					// tsconfig에 없는 파일도 기본 프로젝트로 lint
					allowDefaultProject: ["*.config.mjs", "scripts/*.ts"]
				},
				tsconfigRootDir: import.meta.dirname
			}
		}
	}
);
```

ProjectService 는 파일을 열 때(`openClientFile`) 가장 가까운 tsconfig 를 자동 탐색해 프로젝트에 배정하고, 프로젝트를 LRU 로 관리하며, 파일 변경 시 영향받는 프로그램만 무효화한다. 이로써:

- tsconfig 글롭 나열이 사라진다 (자동 탐색)
- out-of-project 파일은 `allowDefaultProject` 로 흡수된다
- watch 모드/에디터에서 증분 업데이트가 tsserver 수준으로 정확해진다

트레이드오프도 있다. 첫 lint 의 콜드 스타트는 project 방식과 비슷하거나 약간 느리고(프로젝트 자동 탐색 비용), `allowDefaultProject` 에 큰 글롭을 넣으면 기본 프로젝트가 비대해져 오히려 느려진다. 공식 문서도 8개 이하 파일 패턴 유지를 권고한다.

## 4. 타입 정보 규칙 직접 작성

floating Promise 를 잡는 축소판 규칙으로 전체 흐름을 본다.

```typescript
import { ESLintUtils, TSESTree } from "@typescript-eslint/utils";
import * as ts from "typescript";
import * as tsutils from "ts-api-utils";

const createRule = ESLintUtils.RuleCreator(
	name => `https://example.com/rules/${name}`
);

export const noUnhandledThenable = createRule({
	name: "no-unhandled-thenable",
	meta: {
		type: "problem",
		messages: { floating: "Promise 결과를 처리하지 않았습니다." },
		schema: []
	},
	defaultOptions: [],
	create(context) {
		// 타입 체커 핸들 획득 — projectService/project 어느 쪽이든 동일 API
		const services = ESLintUtils.getParserServices(context);
		const checker = services.program.getTypeChecker();

		return {
			ExpressionStatement(node: TSESTree.ExpressionStatement) {
				if (node.expression.type !== "CallExpression") {
					return;
				}
				// ESTree 노드로 바로 타입 조회 (내부에서 TS 노드 매핑)
				const type = services.getTypeAtLocation(node.expression);
				// union 각 멤버에 then(callable)이 있는지 — Thenable 판정
				const isThenable = tsutils
					.unionTypeParts(type)
					.some(part => {
						const then = part.getProperty("then");
						return then !== undefined
							&& checker
								.getTypeOfSymbolAtLocation(then, services.esTreeNodeToTSNodeMap.get(node))
								.getCallSignatures().length > 0;
					});
				if (isThenable) {
					context.report({ node, messageId: "floating" });
				}
			}
		};
	}
});
```

핵심 API 는 세 가지다. `getParserServices(context)` 는 타입 정보가 없으면 throw 하므로 규칙의 타입 의존을 명시적으로 만든다. `services.getTypeAtLocation(esNode)` 는 노드 매핑 + `checker.getTypeAtLocation` 을 합친 단축이다. `ts-api-utils` 는 union 분해, 수식어 검사 같은 체커 보일러플레이트를 줄여 준다. 규칙 테스트는 `@typescript-eslint/rule-tester` 가 실제 프로그램을 만들어 수행한다.

```typescript
import { RuleTester } from "@typescript-eslint/rule-tester";

const tester = new RuleTester({
	languageOptions: {
		parserOptions: { projectService: { allowDefaultProject: ["*.ts"] }, tsconfigRootDir: __dirname }
	}
});

tester.run("no-unhandled-thenable", noUnhandledThenable, {
	valid: ["await fetch('/api');", "void fetch('/api');"],
	invalid: [{ code: "fetch('/api');", errors: [{ messageId: "floating" }] }]
});
```

## 5. 성능 프로파일링 — 어디가 느린가

typed linting 이 느릴 때 병목은 대체로 세 군데다. 측정부터 한다.

```bash
# 규칙별 시간 분해
TIMING=all npx eslint .

# 파서/프로그램 생성 시간 포함 전체 프로파일
npx eslint . --stats -f json -o stats.json   # ESLint 9 stats
node --cpu-prof node_modules/.bin/eslint .   # V8 프로파일
```

`TIMING=all` 출력에서 상위를 차지하는 전형적 규칙은 `no-misused-promises`, `no-floating-promises`, `naming-convention` 이다. 이들은 노드마다 체커를 호출한다. 실측 예(120k LOC, M1 Pro):

| 구성 | 시간 |
| --- | --- |
| 비-typed 규칙만 | 28s |
| + typed recommended | 96s |
| + typed, projectService, 캠시 워밍 | 71s |
| typed 규칙을 lint-staged에서 제외, CI만 실행 | 로컬 31s / CI 96s |

완화 전략은 우선순위 순으로:

- **이중 실행 분리**: 로컬 pre-commit 은 비-typed 규칙만, CI 에서 typed 전체 실행. `tseslint.configs.recommended` 와 `recommendedTypeChecked` 를 환경 변수로 스위칭한다.
- **`disable-type-checked` 오버라이드**: 테스트 픽스처, 생성 코드 디렉터리는 타입 규칙 제외.

```javascript
{
	files: ["**/__fixtures__/**", "**/generated/**"],
	...tseslint.configs.disableTypeChecked
}
```

- **워커 병렬화**: ESLint 자체는 단일 프로세스다. Nx/Turbo 로 패키지 단위 병렬 실행하거나, eslint-p 같은 병렬 러너를 쓴다. 단 프로그램 생성이 워커마다 중복되므로 패키지 경계 분할이 더 효율적이다.
- **TS 버전 정렬**: typescript-eslint 가 지원 범위 밖 TS 버전을 만나면 경고와 함께 비최적 경로로 동작한다. 메이저 업그레이드 시 함께 올린다.

## 6. Compiler API 관점 — getTypeAtLocation의 비용 모델

규칙 작성자가 알아야 할 비용 특성: 체커는 lazy 다. `getTypeAtLocation` 첫 호출이 해당 노드 주변의 추론을 트리거하고 결과는 내부 캠시에 남는다. 따라서

- 같은 노드를 여러 규칙이 조회해도 두 번째부터는 싸다
- 그러나 **광역 추론을 유발하는 노드**(거대한 객체 리터럴, 깊은 제네릭 체인)는 첫 조회가 수십 ms 까지 걸릴 수 있다
- `checker.typeToString` 은 디버깅엔 좋지만 프로덕션 규칙에서 노드마다 호출하면 직렬화 비용이 크다. 타입 플래그(`type.flags & ts.TypeFlags.StringLike`)나 심볼 검사로 대체한다

```typescript
// 느림 — 문자열화 후 비교
if (checker.typeToString(type) === "Promise<void>") { ... }

// 빠름 — 구조적 검사
const symbol = type.getSymbol();
if (symbol?.getName() === "Promise") { ... }
```

## 7. 모노레포 구성 패턴

패키지 30개 모노레포의 권장 구성은 "루트 flat config 하나 + projectService 자동 탐색"이다. 패키지별 .eslintrc 를 두던 legacy 패턴은 프로그램 중복 생성을 유발한다.

```javascript
// eslint.config.mjs (루트 단일 구성)
export default tseslint.config(
	{ ignores: ["**/dist/**", "**/.next/**"] },
	...tseslint.configs.strictTypeChecked,
	...tseslint.configs.stylisticTypeChecked,
	{
		languageOptions: {
			parserOptions: {
				projectService: true,
				tsconfigRootDir: import.meta.dirname
			}
		}
	},
	// 패키지별 예외는 files 글롭으로 같은 파일 안에서
	{
		files: ["packages/legacy-*/**"],
		rules: { "@typescript-eslint/no-explicit-any": "off" }
	}
);
```

CI 캠시는 `.eslintcache` 를 키에 TS 버전 + eslint config 해시를 포함해 저장한다. typed 규칙은 의존 타입이 바뀌면 결과가 달라질 수 있으므로 `--cache-strategy content` 를 쓰고, 의존 패키지 d.ts 변경 시 캠시를 무효화하는 보수적 정책이 안전하다.

## 8. typed linting의 한계와 대안 흐름

알아둘 경계 조건들:

- **단일 파일 단독 lint 불가**: 타입 정보는 프로그램 전체를 요구하므로 `eslint --stdin` 류의 에디터 통합에서 typed 규칙은 프로그램 로드를 기다린다. projectService 가 이를 tsserver 수준으로 줄였지만 0 은 아니다.
- **Rust 린터와의 관계**: Biome/oxlint 는 타입 정보 없는 규칙에서 ESLint 보다 50~100배 빠르다. 2025년 이후 패턴은 "비-typed 규칙은 oxlint, typed 규칙만 typescript-eslint" 하이브리드다. oxlint 의 `--type-aware` 실험(tsgolint, Go 포팅 tsc 기반)이 진행 중이며 안정화되면 이 구도가 다시 바뀐다.
- **tsgo(TypeScript 7 네이티브 포트) 영향**: 체커 호출 자체가 10배 빨라지면 typed linting 병목 구조가 근본적으로 달라진다. typescript-eslint 팀은 tsgo 의 API 호환 레이어 위에서 동작하는 경로를 추적 중이다.

결론적으로 현재 시점의 합리적 구성은 (1) projectService 채택, (2) 로컬/CI 규칙 분리, (3) 비-typed 규칙의 Rust 린터 이관 검토, (4) 커스텀 typed 규칙은 체커 호출 최소화 원칙으로 작성 — 이 네 가지다.

## 9. 부록 — 노드 매핑의 비대칭과 가짜 노드 문제

규칙 작성 시 부딪히는 미묘한 지점 하나를 기록한다. ESTree 와 TS AST 는 1:1 이 아니다. TS 의 `ts.PropertyAccessExpression` 은 ESTree 의 `MemberExpression` 에 대응하지만, ESTree 에는 TS 에 없는 합성 노드(예: `TSEnumBody`)가 있고 반대로 TS 의 일부 노드(`ts.ParenthesizedExpression`)는 ESTree 변환에서 제거된다. 따라서 `esTreeNodeToTSNodeMap.get()` 이 기대와 다른 노드를 줄 수 있다 — 괄호로 감싼 식의 타입을 물으면 괄호 안쪽 노드 기준으로 동작한다. decorator, optional chaining(`?.`) 체인 분해도 양쪽 표현이 달라, 체인 전체의 타입이 필요하면 ESTree 의 `ChainExpression` 루트에서 조회해야 한다.

또 하나는 **JSDoc 타입의 비가시성**이다. `checker.getTypeAtLocation` 은 JS 파일의 JSDoc `@type` 을 반영하지만, ESTree AST 에는 JSDoc 이 구조화되어 있지 않으므로 "타입 표기가 있는가"를 AST 로 검사하는 규칙(`explicit-function-return-type` 류)은 JS+JSDoc 코드베이스에서 오탐한다. `allowJs` 모노레포에서 typed 규칙을 `**/*.ts` 글롭으로 한정하는 것이 안전한 이유다.

마지막으로 lint 결과의 결정성: typed 규칙의 결과는 **의존 패키지의 d.ts 내용에 의존**한다. CI 에서 lint 를 빌드보다 먼저 돌리면 d.ts 가 stale 하거나 없어서 로컬과 다른 결과가 난다. 파이프라인 순서를 `build(dts) → lint` 로 고정하거나, isolatedDeclarations 기반 빠른 dts 생성을 lint 전 단계에 두는 구성이 결정성을 보장한다 — 같은 날 학습한 isolated declarations 주제와 실무에서 직접 연결되는 지점이다.

```yaml
# CI 순서 보장 예 (GitHub Actions)
- run: pnpm turbo run dts        # oxc 기반 d.ts 선생성 (수 초)
- run: pnpm turbo run lint       # typed lint가 fresh d.ts를 본다
- run: pnpm turbo run typecheck build
```

## 참고

- typescript-eslint 공식 문서 — Typed Linting, Project Service (typescript-eslint.io)
- typescript-eslint v8 발표 블로그 — "Project Service"
- ts-api-utils 문서 (github.com/JoshuaKGoldberg/ts-api-utils)
- TypeScript Wiki — Using the Compiler API
- ESLint 9 Stats / TIMING 프로파일링 문서 (eslint.org)
- oxlint type-aware preview 발표 (oxc.rs/blog)
