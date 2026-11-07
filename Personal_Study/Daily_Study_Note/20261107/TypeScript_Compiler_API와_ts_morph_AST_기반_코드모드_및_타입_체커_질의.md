Notion 원본: https://www.notion.so/4115a06fd6d381f7bfdbcf8a8974d199

# TypeScript Compiler API와 ts-morph: AST 기반 코드모드 및 타입 체커 질의

> 2026-11-07 신규 주제 · 확장 대상: Javascript / TypeScript 컴파일러 옵션·모듈 해석

## 학습 목표

- `ts.createProgram` 으로 Program / SourceFile / TypeChecker 를 구성하고 AST 를 순회한다
- `ts.transform` 과 `NodeFactory` 로 import 경로를 일괄 치환하는 변환기를 작성한다
- ts-morph 로 선언을 찾고 이름 변경·시그니처 수정·파일 저장까지 수행한다
- TypeChecker 로 심볼·타입·호출 시그니처를 질의해 "사용처 분석" 도구를 만든다
- 대규모 모노레포에서 codemod 의 속도와 정확도를 좌우하는 요소를 측정한다

## 1. 컴파일러 파이프라인과 API 계층

`tsc` 는 Scanner → Parser → Binder → Checker → Emitter 순으로 동작한다. Scanner 가 문자열을 토큰으로 자르고, Parser 가 토큰을 `SyntaxKind` 로 구분된 `Node` 트리(AST)로 만든다. Binder 는 각 선언에 `Symbol` 을 붙이고 스코프(`SymbolTable`)를 구성하며, Checker 가 타입을 계산하고 진단(Diagnostic)을 낸다. Emitter 는 변환기(transformer) 체인을 통과한 AST 를 JS/`.d.ts` 로 출력한다.

공개 API 는 크게 세 층이다. 가장 낮은 층은 `ts.createSourceFile` — 타입 정보 없이 문법만 파싱하므로 빠르다(파일당 수 ms). 중간 층은 `ts.createProgram` — tsconfig 의 `paths`, `moduleResolution`, `references` 를 해석해 전체 그래프와 `TypeChecker` 를 제공한다. 가장 높은 층이 LanguageService(에디터용)이며 증분 업데이트와 rename/refactor 를 제공한다. ts-morph 는 Program 과 LanguageService 를 감싸 "변경 가능한 래퍼 노드"를 제공하는 라이브러리다.

선택 기준은 단순하다. 문법만 필요하면(예: `console.log` 제거) `createSourceFile` 또는 Babel/SWC 가 10배 이상 빠르다. 타입이 필요하면(예: 특정 타입의 메서드 호출만 치환) Program 이 필요하고, 이때 비용은 프로젝트 전체 타입 체크 비용에 수렴한다.

## 2. Program 구성과 AST 순회

```ts
import ts from "typescript";
import path from "node:path";

function loadProgram(tsconfigPath: string): ts.Program {
  const cfg = ts.readConfigFile(tsconfigPath, ts.sys.readFile);
  if (cfg.error) throw new Error(ts.flattenDiagnosticMessageText(cfg.error.messageText, "\n"));
  const parsed = ts.parseJsonConfigFileContent(cfg.config, ts.sys, path.dirname(tsconfigPath));
  return ts.createProgram({ rootNames: parsed.fileNames, options: parsed.options });
}

const program = loadProgram("./tsconfig.json");
const checker = program.getTypeChecker();

for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile || program.isSourceFileFromExternalLibrary(sf)) continue;
  ts.forEachChild(sf, function visit(node) {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
      const name = node.expression.name.text;
      if (name === "findOne") {
        const { line } = sf.getLineAndCharacterOfPosition(node.getStart());
        console.log(`${sf.fileName}:${line + 1} findOne 호출`);
      }
    }
    ts.forEachChild(node, visit);
  });
}
```

`forEachChild` 는 자식 중 하나라도 truthy 를 반환하면 순회를 멈추므로, 방문 함수가 값을 반환하지 않도록 주의한다. `getChildren()` 은 토큰까지 포함해 느리고, `forEachChild` 는 의미 있는 자식만 순회한다. `node.getStart()` 는 선행 trivia(주석·공백)를 제외한 시작 위치이고 `node.pos` 는 trivia 를 포함하므로, 텍스트 치환 시에는 `getStart()` / `getEnd()` 를 쓴다.

## 3. TypeChecker 질의: 문법으로는 알 수 없는 것

`findOne` 이름이 같아도 Mongoose 모델의 것인지 사용자 정의 클래스의 것인지는 타입을 봐야 안다.

```ts
function isFromModule(node: ts.CallExpression, checker: ts.TypeChecker, moduleName: string): boolean {
  const sig = checker.getResolvedSignature(node);
  const decl = sig?.getDeclaration();
  if (!decl) return false;
  return decl.getSourceFile().fileName.includes(`/node_modules/${moduleName}/`);
}

function describeReceiver(expr: ts.PropertyAccessExpression, checker: ts.TypeChecker) {
  const type = checker.getTypeAtLocation(expr.expression);
  return {
    text: checker.typeToString(type),
    props: checker.getPropertiesOfType(type).map(s => s.getName()),
    isUnion: type.isUnion(),
  };
}
```

`getTypeAtLocation` 은 표현식의 "선언 타입"이 아니라 그 위치에서 narrowing 이 반영된 타입을 돌려준다(제어 흐름 분석 결과). `getSymbolAtLocation(identifier)` 는 import alias 를 따라가지 않으므로 원본 선언이 필요하면 `checker.getAliasedSymbol(symbol)` 로 해소해야 한다(`symbol.flags & ts.SymbolFlags.Alias` 확인). 이를 빼먹으면 `import { foo as bar }` 형태의 사용처를 놓친다.

비용은 지연 평가다. Checker 는 요청된 노드의 타입만 계산하므로, 모든 호출식에서 `getResolvedSignature` 를 호출하면 사실상 전체 타입 체크와 비슷해진다. 10만 줄 프로젝트에서 전체 `createProgram` + 체크가 대략 8~20초 범위인 반면 문법 순회만은 1~2초 수준이다(하드웨어·`skipLibCheck` 에 따라 편차가 크므로 `--extendedDiagnostics` 로 직접 측정할 것).

## 4. Transformer 로 import 경로 일괄 치환

`ts.transform` 은 AST 를 불변으로 취급하고 새 노드를 만들어 반환하는 방식이다. 공식 printer 로 출력하므로 원본 포매팅이 일부 사라질 수 있다는 점이 가장 큰 단점이다.

```ts
const rewriteImports = (map: Record<string, string>): ts.TransformerFactory<ts.SourceFile> =>
  (ctx) => {
    const f = ctx.factory;
    const visit: ts.Visitor = (node) => {
      if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
        const to = map[node.moduleSpecifier.text];
        if (to) {
          return f.updateImportDeclaration(
            node, node.modifiers, node.importClause,
            f.createStringLiteral(to), node.attributes);
        }
      }
      return ts.visitEachChild(node, visit, ctx);
    };
    return (sf) => ts.visitNode(sf, visit) as ts.SourceFile;
  };

const result = ts.transform(sourceFile, [rewriteImports({ "@old/lib": "@new/lib" })]);
const out = ts.createPrinter({ newLine: ts.NewLineKind.LineFeed })
  .printFile(result.transformed[0]);
```

`updateImportDeclaration` 은 TS 5.3 부터 `attributes`(import attributes) 인자를 받으며 이전 버전은 `assertClause` 였다. 버전 고정이 안 된 codemod 는 컴파일 에러로 바로 드러나므로 CI 에서 `typescript` 버전을 lock 한다. 포매팅 보존이 중요하면 transformer 로 위치만 수집하고 원문 문자열에 직접 splice 하는 방식(아래 ts-morph 의 `replaceWithText` 가 내부적으로 이 방식)을 쓴다.

## 5. ts-morph: 변경 가능한 AST 와 텍스트 보존

ts-morph 는 노드를 수정하면 해당 범위의 텍스트만 다시 쓰고 나머지는 보존한다. 내부적으로 매 수정 후 SourceFile 을 재파싱하므로 "변경 → 즉시 질의"가 안전하다.

```ts
import { Project, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json", skipAddingFilesFromTsConfig: false });

// (1) 이름 변경: LanguageService 의 rename 이므로 import alias·shorthand 프로퍼티까지 처리
const cls = project.getSourceFileOrThrow("src/user/UserService.ts").getClassOrThrow("UserService");
cls.rename("AccountService");

// (2) 시그니처 수정: 모든 호출처의 인자 갱신은 직접 수행
const fn = project.getSourceFileOrThrow("src/util/date.ts").getFunctionOrThrow("format");
fn.addParameter({ name: "locale", type: "string", hasQuestionToken: true });
for (const ref of fn.findReferencesAsNodes()) {
  const call = ref.getParentIfKind(SyntaxKind.CallExpression);
  if (call && call.getArguments().length === 1) call.addArgument(`"ko-KR"`);
}

project.saveSync();   // 변경된 파일만 디스크에 기록
```

`rename` 은 LanguageService 의 `findRenameLocations` 를 사용하므로 문자열 치환보다 정확하다. 반면 `findReferencesAsNodes` 는 선언 자체와 타입 참조도 포함하므로 `CallExpression` 부모 확인을 반드시 해야 한다. 대량 수정 시 `project.getSourceFiles()` 를 순회하며 `sf.forEachDescendant` 로 노드를 수정하면 순회 중 AST 가 갱신되어 이미 수집한 노드가 "forgotten" 상태가 되어 `InvalidOperationError` 가 난다. 해법은 변경 대상을 먼저 배열로 수집한 뒤 **뒤쪽 위치부터** 수정하거나, 노드 대신 위치 정보를 저장했다가 한 번에 `replaceText` 하는 것이다.

## 6. 성능: 메모리와 증분 처리

ts-morph `Project` 는 Program 을 유지하므로 수천 파일에서 힙이 1~3GB 까지 오를 수 있다. 대응은 다음 세 가지다. 첫째, `Project` 를 패키지 단위(Project References 의 각 tsconfig)로 나눠 생성하고 처리 후 `project.forgetNodesCreatedInBlock` 이나 객체 폐기로 메모리를 반환한다. 둘째, 타입 정보가 필요 없는 규칙은 `useInMemoryFileSystem` 이나 `compilerOptions: { noResolve: true }` 로 모듈 해석 비용을 끊는다. 셋째, 변경 전 `git stash` 가능한 깨끗한 working tree 를 요구하고 변경 후 `tsc --noEmit` 으로 회귀를 확인한다.

| 접근 | 정확도 | 속도 | 포매팅 보존 | 적합한 작업 |
|---|---|---|---|---|
| 정규식 | 낮음 | 매우 빠름 | 완전 | 단순 문자열 치환 |
| createSourceFile + splice | 중간(문법만) | 빠름 | 완전 | import 경로, 데코레이터 |
| ts-morph | 높음(타입) | 느림 | 대부분 | rename, 시그니처 변경 |
| ts.transform + printer | 높음 | 중간 | 일부 손실 | 빌드 파이프라인 변환 |
| jscodeshift/ast-grep | 중간 | 빠름 | 높음 | JS 중심, 다언어 |

## 7. 실전 패턴: 사용처 분석 리포트

"deprecated 된 `legacyFetch` 를 어디서 얼마나 쓰는가"는 codemod 전에 반드시 필요한 분석이다.

```ts
const target = project.getSourceFileOrThrow("src/http/legacy.ts").getFunctionOrThrow("legacyFetch");
const refs = target.findReferences().flatMap(r => r.getReferences())
  .filter(r => !r.isDefinition());

const byDir = new Map<string, number>();
for (const r of refs) {
  const dir = r.getSourceFile().getDirectoryPath().replace(process.cwd(), "");
  byDir.set(dir, (byDir.get(dir) ?? 0) + 1);
}
console.table([...byDir].sort((a, b) => b[1] - a[1]));
```

이 결과를 팀별 소유 디렉터리와 대조하면 "누가 언제까지 마이그레이션"을 정할 수 있고, codemod 는 자동 변환 가능한 패턴(인자 1개 호출)만 처리하고 나머지는 `// TODO(migrate)` 주석을 삽입한다. 자동화 비율을 80% 로 잡고 나머지는 사람이 처리하는 것이 회귀 위험을 낮춘다.

## 8. 테스트와 안전장치

codemod 는 입력/기대 출력 fixture 로 테스트한다. `new Project({ useInMemoryFileSystem: true })` 에 `createSourceFile("a.ts", before)` 후 변환하고 `getFullText()` 를 `after` 와 비교하면 디스크 접근 없이 수 ms 로 돈다. Vitest 로 `fixtures/<case>/{input.ts,output.ts}` 를 순회하게 만들면 케이스 추가가 파일 추가만으로 끝난다. 추가로 멱등성 테스트(변환 결과에 다시 변환을 적용해도 동일)를 넣어 재실행 안전성을 확인한다. 마지막으로 변환 후 `program.getSemanticDiagnostics()` 개수가 변환 전 이하인지 검사하면 타입 회귀를 자동 차단할 수 있다.

## 9. 트레이드오프 정리

타입 체커 기반 codemod 는 정확하지만 느리고 메모리를 많이 쓴다. 문법 기반은 빠르지만 동명이인(同名異人) 오탐이 있다. 현실적인 전략은 2단계 필터다 — 문법 단계에서 후보를 좁히고(이름 일치), 후보에 대해서만 `getResolvedSignature` 로 타입을 확인한다. 이 방식은 전체 타입 질의 대비 보통 5~10배 빠르면서 정확도 손실이 거의 없다. 또한 TypeScript 7(네이티브 포트) 로드맵에서 API 형태가 바뀔 가능성이 있으므로, 장기 보관 도구는 ts-morph 같은 래퍼 뒤에 격리하는 것이 안전하다.

## 참고

- TypeScript Wiki — Using the Compiler API (github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
- ts-morph 공식 문서 (ts-morph.com) — Navigation / Manipulation / Performance
- TypeScript 소스 `src/compiler/factory/nodeFactory.ts`
- ts-ast-viewer.com — AST 시각화
