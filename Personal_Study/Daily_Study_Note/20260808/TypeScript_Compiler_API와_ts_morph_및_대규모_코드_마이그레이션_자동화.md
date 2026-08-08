Notion 원본: https://www.notion.so/3b65a06fd6d38111ab3dda9ae6ce0e24

# TypeScript Compiler API와 ts-morph 및 대규모 코드 마이그레이션 자동화

> 2026-08-08 신규 주제 · 확장 대상: TypeScript 컴파일러 (tsc 옵션·모듈 해석·컴파일 성능 진단 기학습)

## 학습 목표

- Program → SourceFile → Node로 이어지는 Compiler API의 핵심 객체 모델과 TypeChecker의 역할을 구분한다
- AST 순회(forEachChild vs getChildren)와 TransformerFactory 기반 코드 변환을 구현한다
- ts-morph로 심볼 참조 추적·일괄 리네임·import 재작성 등 마이그레이션 스크립트를 작성한다
- 수천 파일 규모에서 메모리·속도를 제어하는 배치 전략과 안전한 적용 절차를 수립한다

## 1. Compiler API의 계층 구조

TypeScript 컴파일러는 라이브러리(`typescript` 패키지)로도 동작하며, 핵심 객체는 네 개다. **Program**은 컴파일 단위 전체(파일 집합 + compilerOptions)를 대표하고, **SourceFile**은 파일 하나의 AST 루트다. **TypeChecker**는 Program 전체를 보고 심볼 해석·타입 추론을 수행하는 유일한 창구이며, **Node**는 AST의 각 정점이다. 구문(Syntax)만 필요한 작업은 SourceFile만으로 가능하지만, "이 식별자가 어느 선언을 가리키는가" 같은 의미(Semantic) 질의는 반드시 TypeChecker를 거쳐야 한다.

```typescript
import ts from "typescript";

const configPath = ts.findConfigFile("./", ts.sys.fileExists, "tsconfig.json")!;
const configFile = ts.readConfigFile(configPath, ts.sys.readFile);
const parsed = ts.parseJsonConfigFileContent(configFile.config, ts.sys, "./");

const program = ts.createProgram(parsed.fileNames, parsed.options);
const checker = program.getTypeChecker();

for (const sf of program.getSourceFiles()) {
	if (sf.isDeclarationFile) {
		continue;
	}
	console.log(sf.fileName, sf.statements.length);
}
```

주의할 점은 **TypeChecker는 Program에 종속**된다는 것이다. 파일을 수정해 새 Program을 만들면 이전 Checker에서 얻은 Symbol·Type 객체는 무효가 된다. 마이그레이션 스크립트가 "분석 → 수정 → 재분석" 루프를 돌 때 흔히 만나는 함정으로, 수정 전에 필요한 정보를 모두 수집(위치는 파일명+오프셋 같은 원시값으로)한 뒤 일괄 적용하는 구조가 안전하다.

## 2. AST 순회 — forEachChild와 getChildren의 차이

순회 API는 두 계열이 있고 성능 차이가 크다. `ts.forEachChild(node, cb)`는 **의미 있는 자식 노드만** 방문한다(키워드·괄호·세미콜론 등 토큰 제외). 반면 `node.getChildren()`은 모든 토큰을 포함한 전체 구문 트리를 재구성하는데, 내부적으로 SyntaxList 노드를 생성·캐시하므로 메모리와 시간이 수 배 더 든다. 분석 용도라면 거의 항상 `forEachChild`가 정답이고, `getChildren`은 포매터처럼 토큰 단위 위치가 필요한 도구에서만 쓴다.

```typescript
// 재귀 방문자 — 특정 함수 호출(deprecatedFn) 전수 수집
function collectCalls(sf: ts.SourceFile, checker: ts.TypeChecker): ts.CallExpression[] {
	const found: ts.CallExpression[] = [];
	const visit = (node: ts.Node): void => {
		if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
			const symbol = checker.getSymbolAtLocation(node.expression);
			const decl = symbol?.declarations?.[0];
			if (decl && decl.getSourceFile().fileName.endsWith("legacy/api.ts")
					&& symbol!.name === "deprecatedFn") {
				found.push(node);
			}
		}
		ts.forEachChild(node, visit);
	};
	visit(sf);
	return found;
}
```

핵심은 **이름 문자열 매칭이 아니라 심볼 동일성으로 판단**하는 부분이다. 같은 이름의 다른 함수(로컬 셰도잉, 다른 모듈의 동명 export)를 오탐하지 않으려면 `getSymbolAtLocation`으로 선언 위치까지 확인해야 한다. alias import(`import { deprecatedFn as df }`)까지 쪽으려면 `checker.getAliasedSymbol(symbol)`로 원 심볼을 얻는다.

## 3. TransformerFactory 기반 코드 변환

Compiler API의 공식 변환 경로는 `ts.transform`에 TransformerFactory를 넘기는 방식이다. AST는 불변(immutable)이므로 변경은 `ts.factory.update*` 계열로 새 노드를 만들어 반환한다.

```typescript
// console.log(...) 호출을 logger.debug(...)로 치환하는 변환기
const transformer: ts.TransformerFactory<ts.SourceFile> = (context) => {
	return (sourceFile) => {
		const visit: ts.Visitor = (node) => {
			if (ts.isCallExpression(node)
					&& ts.isPropertyAccessExpression(node.expression)
					&& ts.isIdentifier(node.expression.expression)
					&& node.expression.expression.text === "console"
					&& node.expression.name.text === "log") {
				return ts.factory.updateCallExpression(
					node,
					ts.factory.createPropertyAccessExpression(
						ts.factory.createIdentifier("logger"),
						"debug"
					),
					node.typeArguments,
					node.arguments
				);
			}
			return ts.visitEachChild(node, visit, context);
		};
		return ts.visitNode(sourceFile, visit) as ts.SourceFile;
	};
};

const result = ts.transform(sourceFile, [transformer]);
const printer = ts.createPrinter({ removeComments: false });
const newText = printer.printFile(result.transformed[0]);
```

이 경로의 치명적 단점은 **printer가 원본 포매팅을 보존하지 않는다**는 것이다. `createPrinter`는 AST를 자기 규칙대로 다시 찍기 때문에 들여쓰기·개행·주석 위치가 흐트러져, diff가 파일 전체로 번진다. 실무 코드모드에서 이 방식은 신규 파일 생성이나 emit 파이프라인 훅(커스텀 트랜스파일)에 한정하고, **기존 코드 수정은 텍스트 범위 치환(§4~5) 방식**을 쓰는 것이 정석이다. jscodeshift가 recast로 포매팅을 보존하는 것과 같은 이유다.

## 4. ts-morph — 실무 마이그레이션의 사실상 표준

ts-morph는 Compiler API를 감싸 세 가지를 해결한다. 첫째, 노드 조작 시 **원본 텍스트를 범위 기반으로 수정**해 포매팅을 보존한다. 둘째, 수정 후 AST 재바인딩을 자동 처리해 §1의 "낡은 Checker" 문제를 개발자가 신경 쓰지 않게 한다(단, 이전에 잡아둔 노드 참조는 `forgotten node` 오류가 나므로 수정 후 재조회해야 한다). 셋째, `findReferences`·`rename` 같은 language service 기능을 스크립트에서 바로 쓸 수 있다.

```typescript
import { Project, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });

const targetClass = project.getSourceFileOrThrow("src/services/UserService.ts")
	.getClassOrThrow("UserService");

const method = targetClass.getMethodOrThrow("findUser");

// 1) 파라미터 (id: string) → (query: { id: string }) 로 변경
method.getParameters().forEach((p) => p.remove());
method.addParameter({ name: "query", type: "{ id: string }" });
method.setBodyText("return this.repo.findOne({ where: query });");

// 2) 전체 호출부를 language service로 찾아 인자 재작성
for (const ref of method.findReferencesAsNodes()) {
	const call = ref.getFirstAncestorByKind(SyntaxKind.CallExpression);
	if (call === undefined) {
		continue;
	}
	const args = call.getArguments().map((a) => a.getText());
	if (args.length === 1) {
		call.removeArgument(0);
		call.addArgument(`{ id: ${args[0]} }`);
	}
}

project.saveSync();
```

`findReferencesAsNodes`는 language service의 참조 검색을 그대로 쓰므로 문자열 grep으로는 불가능한 정확도(re-export 체인, alias, 상속 오버라이드까지)를 얻는다. 다만 프로젝트 전체 바인딩이 필요해 첫 호출이 비싸다 — 수백 파일 프로젝트에서 수 초, 수천 파일이면 수십 초 단위다.

## 5. 대규모 코드베이스 배치 전략

수천 파일·수십만 LOC 규모에서 ts-morph를 순진하게 쓰면 메모리가 수 GB로 치솟고 GC가 지배한다. 검증된 전략은 다음과 같다.

| 전략 | 내용 | 효과 |
|---|---|---|
| 파일 단위 스트리밍 | 전체 로드 대신 glob 청크로 나눠 처리 후 `sourceFile.forget()` 호출 | 힙 상주 AST 최소화 — 메모리 상한 고정 |
| 구문 전용 프리패스 | 1차로 정규식/구문 스캔으로 후보 파일만 추린 뒤 2차에서 타입 정보 로드 | Checker 바인딩 비용을 후보 파일로 한정 |
| skipLibCheck + lib 제외 | Project 생성 시 `skipAddingFilesFromTsConfig` 후 필요 파일만 add | node_modules .d.ts 파싱 회피 |
| 프로세스 분할 | 디렉터리 샤드별 워커 프로세스 병렬 실행 | V8 힙 한계 우회, 멀티코어 활용 |
| manipulation 최소화 | 다건 수정은 저수준 API로 모아 적용 | 수정마다 일어나는 재파싱 횟수 감소 |

특히 `forget()`은 ts-morph가 내부 캐시한 wrapped node를 해제하는 유일한 수단이므로 스트리밍 처리의 핵심이다. 또 하나의 실무 요령은 **변경 계획(plan)과 적용(apply)의 분리**다. 1차 실행은 "파일 X의 오프셋 [a,b)를 문자열 S로 치환"이라는 JSON 계획만 산출하고, 2차가 계획을 역순 오프셋으로 적용한다. 이렇게 하면 dry-run 리뷰가 가능하고, 실패 시 재시작이 멱등적이며, 적용 단계는 타입 정보가 필요 없어 빠르다.

## 6. Language Service와 codefix 재활용

`ts.createLanguageService`를 직접 띄우면 tsc가 에디터에 제공하는 진단·quick fix를 스크립트에서 재활용할 수 있다. 대표 사례가 "누락 import 일괄 추가"다. 파일을 이동·분할한 뒤 깨진 import를 손으로 고치는 대신, 진단 코드 2304(Cannot find name)에 대한 codefix(`import` fix)를 language service에서 받아 적용한다.

```typescript
const ls = project.getLanguageService(); // ts-morph 래퍼
const sf = project.getSourceFileOrThrow("src/moved/Widget.tsx");

for (const diag of sf.getPreEmitDiagnostics()) {
	if (diag.getCode() !== 2304) {
		continue;
	}
	const fixes = ls.getCodeFixesAtPosition(
		sf.getFilePath(),
		diag.getStart()!,
		diag.getStart()! + diag.getLength()!,
		[2304],
		{},
		{}
	);
	const importFix = fixes.find((f) => f.getFixName() === "import");
	importFix?.getChanges().forEach((change) => change.applyChanges());
}
```

organize imports(`ls.organizeImports`) 역시 마이그레이션 마지막 단계에서 돌려 주면 중복·미사용 import가 정리된다. 이 접근의 장점은 **에디터와 동일한 결과**를 보장한다는 점 — tsserver가 고를 모듈 지정자(상대 경로 vs paths alias)와 동일한 규칙이 적용된다.

## 7. 사례 연구 — 전사 API 클라이언트 교체 시나리오

레거시 `axios` 직접 호출 300여 곳을 사내 typed client로 교체하는 시나리오로 전체 파이프라인을 조립하면 다음 순서가 된다. (1) 구문 프리패스로 `axios.` 텍스트를 포함한 파일 목록 추출. (2) ts-morph Project를 후보 파일 + tsconfig로 생성, `checker`로 axios 모듈에서 온 심볼인지 검증해 오탐 제거. (3) 각 호출의 URL 리터럴·HTTP 메서드·제네릭 인자를 수집해 신규 client 메서드로의 매핑 테이블 생성 — 매핑 불가능 케이스(동적 URL 조립 등)는 `// TODO(migration)` 주석 삽입 후 리포트로 분리. (4) 치환 계획 JSON 산출 → 코드 오너 리뷰 → 적용. (5) `tsc --noEmit`과 전체 테스트로 검증, 실패 파일은 계획에서 제외하고 재적용.

실측 감각치로 이 규모(파일 ~2,500개, 후보 ~180개)에서 프리패스는 수 초, Project 생성+바인딩 ~30초, 전체 파이프라인은 리뷰 제외 5분 내외다. 같은 작업을 수작업으로 하면 이틀 이상 걸리고 오탈자 리스크가 남는다는 점에서, **300곳 이상 반복 수정은 코드모드 작성 비용이 항상 회수된다**는 것이 일반적 경험칙이다.

## 8. 검증과 안전장치

자동 수정의 신뢰도는 검증 체계가 결정한다. 최소 구성은 세 겹이다. 첫째, **컴파일 게이트** — 적용 후 `tsc --noEmit`이 통과해야 하며, 마이그레이션 전 기준(baseline) 오류 목록과 diff해 신규 오류 0건을 확인한다. 둘째, **스냅샷 diff 리뷰** — 계획 단계에서 파일별 통합 diff를 산출해 표본이 아닌 전량을 리뷰 대상으로 남긴다. 셋째, **동작 검증** — 기존 테스트 스위트 통과에 더해, 치환 전후 함수의 런타임 동등성이 의심되는 케이스는 계획 단계에서 별도 태깅해 수동 확인으로 돌린다. 여기에 조직 차원의 안전장치로 코드모드 실행을 CI에서 재현 가능하게 만들어 두면(스크립트+락파일 커밋), 롱런 브랜치에서 발생하는 충돌도 "재실행"으로 해소할 수 있다.

## 9. 도구 선택 기준 — ts-morph vs jscodeshift vs 정규식

정규식 일괄 치환은 심볼 인지가 없어 동명 이인 오탐·문자열 내부 치환 사고가 나므로 import 경로처럼 문맥이 자명한 경우로 한정한다. jscodeshift는 Babel AST 기반이라 JS 중심 코드베이스와 기존 codemod 생태계(react-codemod 등) 재사용에 강하지만, TypeScript 타입 질의(참조 추적, 타입 기반 필터)가 필요한 순간 한계가 온다. ts-morph는 타입 인지 변환의 표준이지만 무겁다 — 단순 구문 치환에까지 쓰면 배보다 배꼽이 크다. 결론은 **문맥 필요도에 따른 3단 선택**이다: 문맥 불필요 → 정규식/sed, 구문 수준 → jscodeshift 또는 ts-morph 구문 API(타입 미로드), 의미 수준(참조·타입·시그니처) → ts-morph + TypeChecker. 어느 도구든 §5의 계획/적용 분리와 §8의 3중 검증은 공통으로 적용하는 것이 안전한 기본값이다.

## 참고

- TypeScript Wiki — Using the Compiler API (github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
- ts-morph 공식 문서 (ts-morph.com) — Manipulation·Navigation·Performance 섹션
- TypeScript AST Viewer (ts-ast-viewer.com) — 노드 구조·factory 코드 확인
- Basarat, TypeScript Deep Dive — Compiler 내부 구조 장
- facebook/jscodeshift README 및 recast 포매팅 보존 설계
