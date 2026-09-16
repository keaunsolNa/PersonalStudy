Notion 원본: https://www.notion.so/3dd5a06fd6d381209eddd211a281b7da

# TypeScript Compiler API와 ts-morph 기반 코드모드 및 AST 변환 파이프라인

> 2026-09-16 신규 주제 · 확장 대상: TypeScript 타입 시스템·컴파일러 심화(tsgo, 타입체크 성능 진단)

## 학습 목표

- `ts.createProgram` 과 `LanguageService` 의 비용 차이를 이해하고 코드모드 규모에 맞게 고른다
- 타입 체커를 사용하는 변환과 순수 구문 변환을 구분해 실패 가능 지점을 줄인다
- ts-morph 로 대규모 리네임·임포트 재작성을 안전하게 수행한다
- 코드모드 결과를 diff·타입체크·테스트 3단계로 검증하는 파이프라인을 만든다

## 1. 코드모드가 정규식보다 나은 지점과 그렇지 않은 지점

수백 개 파일에서 `import { foo } from "./legacy"` 를 `import { foo } from "@scope/core"` 로 바꾸는 작업은 `sed` 로도 된다. 문제는 문자열이 코드의 구조를 모른다는 점이다. 주석 안의 `./legacy`, 문자열 리터럴 `"./legacy/docs"`, 타입 전용 임포트, `export ... from` 재수출 구문이 전부 같은 패턴에 걸린다. 반대로 줄바꿈이 들어간 임포트는 걸리지 않는다.

AST 기반 변환은 이 둘을 모두 해결한다. 파서가 이미 "이 노드는 ImportDeclaration 의 moduleSpecifier 다"라고 판정해 두었기 때문에, 주석과 문자열은 애초에 후보에 들어오지 않고 줄바꿈은 토큰화 단계에서 사라진다.

다만 코드모드가 항상 정답은 아니다. 판단 기준은 대략 이렇다.

| 작업 | 권장 도구 | 이유 |
|---|---|---|
| 리터럴 문자열 일괄 치환 | sed/ripgrep | 구조 판단이 불필요 |
| 임포트 경로·심볼 재작성 | ts-morph | 재수출·타입 임포트 구분 필요 |
| 호출 시그니처 변경 (인자 추가/순서) | Compiler API + 체커 | 오버로드 해석이 필요 |
| 데코레이터 → 함수 래핑 전환 | Compiler API transform | 노드 재구성이 필요 |
| 파일 10개 미만 | 손 | 도구 작성 비용이 더 큼 |

경험칙으로 변경 대상 파일이 30개를 넘고, 변환 규칙을 한 문장으로 적을 수 있으면 코드모드가 이긴다.

## 2. 세 가지 진입점: Program, LanguageService, transform

TypeScript 는 성격이 다른 세 개의 API 표면을 제공한다.

**`ts.createProgram`** 은 전체 프로그램을 한 번에 만든다. 모든 파일을 파싱하고 타입 체커를 붙일 수 있다. 일회성 배치 변환에 적합하다. 비용은 전체 파일 파싱 + 필요 시 체크이며, 중간 규모 레포에서 수 초에서 수십 초가 걸린다.

```ts
import * as ts from "typescript";

const configPath = ts.findConfigFile(process.cwd(), ts.sys.fileExists, "tsconfig.json")!;
const config = ts.readConfigFile(configPath, ts.sys.readFile);
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, process.cwd());

const program = ts.createProgram({
	rootNames: parsed.fileNames,
	options: parsed.options,
});
const checker = program.getTypeChecker();
```

**`ts.createLanguageService`** 는 증분 갱신을 전제로 한다. `getScriptVersion` 이 바뀐 파일만 다시 파싱하므로, 변환 → 재검사 → 재변환을 반복하는 도구(에디터 플러그인, watch 모드 코드모드)에 맞는다. 대신 `LanguageServiceHost` 를 직접 구현해야 해서 초기 코드가 길다.

**`ts.transform`** 은 노드 트리를 방문하며 새 트리를 만드는 저수준 API다. 타입 정보가 필요 없고 출력 포맷을 프린터에 맡겨도 되는 경우 가장 빠르다. 단, 프린터는 원본의 주석·공백 배치를 완전히 보존하지 않는다. 이것이 실무에서 `ts.transform` 을 코드모드에 쓰기 어려운 결정적 이유다.

```ts
const transformer: ts.TransformerFactory<ts.SourceFile> = (context) => (sourceFile) => {
	const visit = (node: ts.Node): ts.Node => {
		if (ts.isIdentifier(node) && node.text === "oldName") {
			return context.factory.createIdentifier("newName");
		}
		return ts.visitEachChild(node, visit, context);
	};
	return ts.visitNode(sourceFile, visit) as ts.SourceFile;
};

const result = ts.transform(sourceFile, [transformer]);
const printed = ts.createPrinter().printFile(result.transformed[0]);
```

위 코드는 동작하지만 `printFile` 이 파일 전체를 다시 찍는다. 원본이 Prettier 로 포맷되어 있었다면 그 포맷은 사라지고, diff 는 파일 전체가 바뀐 것처럼 보인다. 리뷰 불가능한 PR 이 만들어진다.

## 3. ts-morph 가 해결하는 것: 텍스트 보존 편집

ts-morph 는 Compiler API 위에 "원본 텍스트를 최대한 건드리지 않는 편집" 레이어를 얹는다. 노드를 교체하면 해당 노드의 문자 범위만 치환하고 나머지 바이트는 그대로 둔다. 결과적으로 diff 가 실제 변경 지점에만 생긴다.

```ts
import { Project, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });

for (const sourceFile of project.getSourceFiles("src/**/*.ts")) {
	for (const decl of sourceFile.getImportDeclarations()) {
		const spec = decl.getModuleSpecifierValue();
		if (spec === "./legacy" || spec.startsWith("./legacy/")) {
			decl.setModuleSpecifier(spec.replace("./legacy", "@scope/core"));
		}
	}
}

await project.save();
```

`getImportDeclarations()` 는 `export ... from` 을 포함하지 않는다. 재수출까지 잡으려면 `getExportDeclarations()` 를 따로 순회해야 한다. 이 구분을 놓치면 배럴 파일만 조용히 남는 흔한 버그가 생긴다.

타입 전용 임포트 처리도 명시적이다.

```ts
for (const decl of sourceFile.getImportDeclarations()) {
	if (decl.isTypeOnly()) {
		continue; // 런타임 의존성 재배치 대상에서 제외
	}
	// ...
}
```

ts-morph 의 `Project` 는 내부적으로 `ts.Program` 을 지연 생성한다. `getTypeChecker()` 를 호출하지 않는 순수 구문 변환이라면 타입 체크 비용이 발생하지 않는다. 반대로 `getType()` 을 한 번이라도 부르면 그 시점에 전체 프로그램 체크가 트리거된다. 1,800 파일 규모에서 이 차이는 4초 대 40초 수준으로 벌어진다. 코드모드를 짤 때 "타입 정보를 정말 써야 하는가"를 먼저 결정해야 하는 이유다.

## 4. 타입 정보가 필요한 변환: 심볼 기준 리네임

같은 이름의 식별자가 여러 스코프에 있을 때, 구문만 보면 어느 것이 우리가 바꾸려는 것인지 알 수 없다. 이때 체커의 심볼 동일성을 기준으로 삼는다.

```ts
const sourceFile = project.getSourceFileOrThrow("src/api/client.ts");
const target = sourceFile.getFunctionOrThrow("fetchUser");

// 선언 기준 전역 리네임 — 모든 참조를 추적해 함께 바꾼다
target.rename("fetchUserById");
await project.save();
```

`rename` 은 내부적으로 LanguageService 의 `findRenameLocations` 를 쓴다. 즉 에디터의 F2 와 동일한 로직이며, 문자열 안의 동명 텍스트나 다른 스코프의 동명 변수를 건드리지 않는다. 주석 안의 이름까지 바꾸려면 `rename(newName, { renameInComments: true })` 를 명시해야 한다. 기본값이 `false` 인 것은 주석 치환이 오탐을 만들기 쉽기 때문이다.

호출부의 인자를 조건부로 바꾸는 변환은 더 까다롭다. 다음은 "`createClient(url)` 호출 중 `url` 이 `string` 타입인 경우에만 옵션 객체로 감싸는" 변환이다.

```ts
for (const call of sourceFile.getDescendantsOfKind(SyntaxKind.CallExpression)) {
	const expr = call.getExpression();
	if (expr.getText() !== "createClient") {
		continue;
	}
	const [first] = call.getArguments();
	if (!first) {
		continue;
	}
	const typeText = first.getType().getText();
	if (typeText !== "string" && !first.getType().isStringLiteral()) {
		continue; // 이미 객체를 넘기는 호출은 건너뛴다
	}
	call.insertArgument(0, `{ url: ${first.getText()} }`);
	call.removeArgument(1);
}
```

여기서 `expr.getText() !== "createClient"` 는 얕은 판정이다. 다른 모듈에서 import 한 동명 함수도 걸린다. 엄밀하게 하려면 심볼을 비교한다.

```ts
const targetSymbol = project
	.getSourceFileOrThrow("src/sdk/index.ts")
	.getFunctionOrThrow("createClient")
	.getSymbol();

const callSymbol = expr.getSymbol()?.getAliasedSymbol() ?? expr.getSymbol();
if (callSymbol !== targetSymbol) {
	continue;
}
```

`getAliasedSymbol()` 이 필요한 이유는 import 된 식별자의 심볼이 "별칭 심볼"이기 때문이다. 이 한 줄을 빠뜨리면 import 를 통한 모든 호출이 누락된다. 코드모드 리뷰에서 가장 자주 발견되는 결함이다.

## 5. 변환 순서와 노드 무효화

ts-morph 에서 노드를 수정하면 그 파일의 AST 가 재파싱된다. 수정 이전에 잡아둔 노드 참조는 "forgotten" 상태가 되어 접근 시 예외를 던진다.

```ts
const calls = sourceFile.getDescendantsOfKind(SyntaxKind.CallExpression);
for (const call of calls) {
	call.replaceWithText("x"); // 두 번째 반복부터 예외 가능
}
```

안전한 패턴은 두 가지다. 첫째, 수집과 변경을 분리하되 변경은 **역순**으로 수행한다. 뒤쪽부터 바꾸면 앞쪽 노드의 문자 위치가 유지된다.

```ts
const calls = sourceFile
	.getDescendantsOfKind(SyntaxKind.CallExpression)
	.reverse();
for (const call of calls) {
	if (call.wasForgotten()) {
		continue;
	}
	// 변경
}
```

둘째, 파일당 한 번만 텍스트를 재구성한다. 변경 목록을 `{ start, end, newText }` 로 모아 두고 마지막에 한 번에 적용하는 방식이다. 변경 수가 수천 건인 경우 재파싱 횟수가 1회로 줄어 실행 시간이 크게 떨어진다.

```ts
type Edit = { start: number; end: number; text: string };
const edits: Edit[] = [];
// ... 수집 ...
edits.sort((a, b) => b.start - a.start);
let text = sourceFile.getFullText();
for (const e of edits) {
	text = text.slice(0, e.start) + e.text + text.slice(e.end);
}
sourceFile.replaceWithText(text);
```

실측 기준으로 1,200 파일·8,000 건 변경 시 노드별 즉시 수정은 3분 이상, 배치 적용은 20초 내외로 끝난다. 차이의 대부분은 재파싱 비용이다.

## 6. 포맷 붕괴를 막는 후처리

ts-morph 도 노드를 새로 만들어 삽입하면 들여쓰기는 추정값으로 들어간다. 팀 포맷과 어긋나는 결과가 섞인다. 해결책은 코드모드가 포맷을 책임지지 않는 것이다.

```bash
node scripts/codemod.mjs
npx prettier --write "src/**/*.ts"
npx tsc -p tsconfig.json --noEmit
npm test
```

순서가 중요하다. Prettier 를 먼저 돌려야 `tsc` 실패가 "코드모드 논리 오류" 때문인지 "포맷 때문인지" 헷갈리지 않는다. 그리고 코드모드 실행 직전 작업 트리는 반드시 깨끗해야 한다. 그래야 `git diff` 가 곧 코드모드의 전체 영향 범위가 된다.

diff 규모를 줄이는 또 하나의 습관은 변환을 **여러 커밋으로 쪼개는** 것이다. 임포트 재작성, 시그니처 변경, 데드 코드 제거를 한 커밋에 넣으면 리뷰어가 검증할 수 없다. 각 단계가 독립적으로 `tsc --noEmit` 를 통과하도록 설계하면 문제가 생겼을 때 이분 탐색이 가능하다.

## 7. 실패 모드와 방어

| 증상 | 원인 | 대응 |
|---|---|---|
| 일부 파일이 변환되지 않음 | tsconfig `include` 밖의 파일 | `project.addSourceFilesAtPaths` 로 명시 추가 |
| `getType()` 이 `any` 반환 | 해당 파일이 프로그램에 없거나 `skipLibCheck` 로 d.ts 누락 | rootNames 확인, 타입 패키지 설치 확인 |
| 변경 후 순환 임포트 발생 | 배럴 파일 경유를 직접 경로로 바꾸며 순서 역전 | `madge --circular` 로 사후 검사 |
| 주석이 다른 노드로 이동 | leading trivia 가 인접 노드에 붙어 있음 | 노드 제거 대신 `replaceWithText` 사용 |
| JSX 속성 변환 누락 | `isJsxAttribute` 분기 미작성 | `.tsx` 전용 케이스 별도 처리 |

특히 마지막 항목은 `.ts` 만 테스트하고 배포한 코드모드에서 자주 터진다. 변환 스크립트 자체에 대한 테스트를 두는 것이 실무적으로 가장 효과가 크다. ts-morph 는 파일 시스템 없이 인메모리 프로젝트를 만들 수 있어 테스트가 간단하다.

```ts
import { Project } from "ts-morph";
import { applyCodemod } from "./codemod";

test("재수출 구문도 함께 재작성한다", () => {
	const project = new Project({ useInMemoryFileSystem: true });
	const file = project.createSourceFile(
		"a.ts",
		`export { foo } from "./legacy";\n`,
	);
	applyCodemod(project);
	expect(file.getFullText()).toContain('from "@scope/core"');
});
```

## 8. 대규모 실행 전략

수천 파일 레포에서는 단일 프로세스 메모리가 병목이 된다. `ts.Program` 은 모든 소스 파일의 AST 를 메모리에 유지하므로, 파일 수에 거의 선형으로 힙이 증가한다. 3,000 파일 규모에서 2GB 를 넘기는 경우가 흔하다.

대응은 세 가지다. 첫째, `--max-old-space-size=8192` 로 힙 상한을 올린다. 둘째, 타입 정보가 필요 없는 변환이라면 `Project` 에 `skipFileDependencyResolution: true` 를 주어 임포트 그래프 해석을 생략한다. 셋째, 디렉터리 단위로 프로세스를 분할한다.

```bash
for dir in src/modules/*/; do
	node --max-old-space-size=4096 scripts/codemod.mjs "$dir"
done
```

분할 실행은 심볼 기준 변환과 충돌한다. 모듈 경계를 넘는 참조를 추적하려면 전체 프로그램이 한 프로세스 안에 있어야 하기 때문이다. 따라서 "구문 변환은 분할, 타입 기반 변환은 단일 프로세스" 라는 규칙이 자연스럽게 따라온다.

마지막으로 코드모드는 일회성 도구라는 점을 기억할 만하다. 완벽하게 일반화하려 들면 비용이 급증한다. 레포 하나, 한 번의 마이그레이션에서만 맞으면 충분하고, 남은 예외 10건은 손으로 고치는 편이 전체적으로 빠른 경우가 대부분이다.

## 참고

- TypeScript Wiki — Using the Compiler API: https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API
- ts-morph Documentation: https://ts-morph.com/
- TypeScript Wiki — Architectural Overview: https://github.com/microsoft/TypeScript/wiki/Architectural-Overview
- TypeScript Wiki — Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- jscodeshift (대안 도구 비교용): https://github.com/facebook/jscodeshift
