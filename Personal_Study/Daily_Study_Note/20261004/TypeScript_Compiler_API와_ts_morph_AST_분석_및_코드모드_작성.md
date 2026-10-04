Notion 원본: https://www.notion.so/3ef5a06fd6d3813c8f7be9369026e991

# TypeScript Compiler API와 ts-morph를 이용한 AST 분석 및 코드모드 작성 (Program·TypeChecker·Transformer)

> 2026-10-04 신규 주제 · 확장 대상: TypeScript isolatedDeclarations와 단일 파일 트랜스파일 모델, tsc 컴파일러 옵션

## 학습 목표

- `ts.createProgram`으로 Program을 만들고 TypeChecker로 노드의 타입과 심볼을 조회한다.
- `ts.transform`과 `factory` API로 AST를 변환하는 Transformer를 작성해 emit 단계에 끼워 넣는다.
- ts-morph `Project`로 대규모 코드모드를 구성하고 저장 전 검증 절차를 설계한다.
- isolatedDeclarations와 tsc 옵션이 분석 도구의 비용과 정확도에 주는 영향을 비교한다.

## 1. 왜 정규식이나 Babel이 아니라 Compiler API인가

코드 일괄 수정(코드모드)을 처음 시도하면 보통 정규식이나 sed로 시작한다. 문자열 수준의 치환은 `import { foo } from "./a"`처럼 모양이 고정된 경우에는 동작하지만, 주석 안의 같은 문자열, 템플릿 리터럴, 줄바꿈이 낀 import, 같은 이름의 지역 변수 앞에서 금방 깨진다. 그래서 구문 구조를 이해하는 AST 기반 도구로 넘어가게 된다. TypeScript Compiler API가 다른 점은 파서뿐 아니라 바인더와 타입 체커를 같은 프로세스에서 쓸 수 있다는 것이다. "이 `foo`가 `lodash`에서 온 `foo`인가", "이 호출의 인자 타입이 `string | undefined`인가", "이 클래스를 구현하는 모든 타입은 무엇인가" 같은 질문은 문법만으로는 답할 수 없고 심볼 해석과 타입 추론이 필요하다. Compiler API는 이런 의미(semantic) 질문을 `TypeChecker`로 해결해 준다. 반대로 비용도 분명하다. 타입 체크를 하려면 프로젝트 전체를 Program으로 로드해야 하므로 단순 구문 치환보다 시작 비용이 크다. 따라서 선택 기준은 단순하다. 문법만 보면 되는 변환은 Babel/SWC 계열이나 `ts.createSourceFile` 단독 파싱으로, 타입 정보가 필요한 변환은 Program과 TypeChecker 조합으로 구분한다.

## 2. AST의 기본 구조: SourceFile, Node, SyntaxKind

가장 작은 단위부터 보자. `ts.createSourceFile(fileName, sourceText, languageVersion, setParentNodes)`는 파일 하나를 파싱해 `SourceFile` 노드를 돌려준다. 이 호출은 파일 시스템도 tsconfig도 읽지 않으며 타입 정보도 없다. 네 번째 인자 `setParentNodes`를 `true`로 주지 않으면 노드의 `parent` 속성이 채워지지 않아, 위로 거슬러 올라가는 탐색이 필요한 도구에서는 `undefined`를 만나게 된다. 일반적으로 분석 도구에서는 `true`로 두는 편이 편하다.

모든 노드는 `kind` 필드에 `ts.SyntaxKind` 열거값을 가진다. 직접 `node.kind === ts.SyntaxKind.CallExpression`으로 비교할 수도 있지만, `ts.isCallExpression(node)` 같은 타입 가드를 쓰면 이후 블록에서 `node.expression`, `node.arguments`가 타입 안전하게 접근된다. 순회에는 `ts.forEachChild(node, cb)`를 쓴다. 이 함수는 자식 노드를 순서대로 콜백에 넘기며, 콜백이 truthy 값을 반환하면 순회를 즉시 멈추고 그 값을 반환한다. 이 성질을 이용하면 "첫 번째로 발견한 노드를 반환"하는 탐색 함수를 간단히 쓸 수 있다.

```ts
import * as ts from "typescript";

const text = `
import { readFile } from "fs/promises";
export async function load(p: string) {
  return readFile(p, "utf8");
}
`;

const sf = ts.createSourceFile("demo.ts", text, ts.ScriptTarget.ES2022, /* setParentNodes */ true);

function visit(node: ts.Node, depth = 0): void {
  const label = ts.SyntaxKind[node.kind];
  console.log(`${" ".repeat(depth * 2)}${label}`);
  ts.forEachChild(node, (child) => visit(child, depth + 1));
}
visit(sf);
```

## 3. Program과 TypeChecker: 의미 분석의 진입점

타입 정보가 필요하면 `ts.createProgram(rootNames, options)`로 Program을 만든다. Program은 루트 파일에서 출발해 import를 따라가며 모든 SourceFile과 lib.d.ts, `node_modules`의 선언 파일까지 모은 컴파일 단위다. 이 객체에서 `program.getSourceFiles()`로 파일 목록을, `program.getTypeChecker()`로 체커를 얻는다. 체커는 지연 평가(lazy)로 동작하므로 질의하지 않은 노드의 타입은 계산되지 않는다. 대신 처음 질의하는 시점에 필요한 선언들이 연쇄적으로 해석되어 비용이 몰린다.

실무에서는 tsconfig.json을 `ts.readConfigFile`과 `ts.parseJsonConfigFileContent`로 해석해야 `paths`, `moduleResolution`, `strict` 같은 옵션이 빌드와 일치한다.

```ts
import * as ts from "typescript";
import * as path from "node:path";

function loadProgram(tsconfigPath: string): ts.Program {
  const cfg = ts.readConfigFile(tsconfigPath, ts.sys.readFile);
  if (cfg.error) throw new Error(ts.flattenDiagnosticMessageText(cfg.error.messageText, "\n"));
  const parsed = ts.parseJsonConfigFileContent(cfg.config, ts.sys, path.dirname(tsconfigPath));
  return ts.createProgram({ rootNames: parsed.fileNames, options: parsed.options });
}

const program = loadProgram("./tsconfig.json");
const checker = program.getTypeChecker();

for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;           // lib, node_modules 제외
  ts.forEachChild(sf, function walk(node) {
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
      const sym = checker.getSymbolAtLocation(node.expression);
      const decl = sym?.declarations?.[0];
      const fromFile = decl?.getSourceFile().fileName ?? "unknown";
      const retType = checker.typeToString(checker.getTypeAtLocation(node));
      const { line } = sf.getLineAndCharacterOfPosition(node.getStart());
      console.log(`${sf.fileName}:${line + 1} ${node.expression.text}() -> ${retType} (선언: ${fromFile})`);
    }
    ts.forEachChild(node, walk);
  });
}
```

이 예제에서 중요한 호출은 세 가지다. `getSymbolAtLocation`은 식별자가 가리키는 심볼을 돌려주며, import된 이름은 alias 심볼이므로 원본 선언을 따라가려면 `checker.getAliasedSymbol(sym)`을 추가로 호출해야 한다. `getTypeAtLocation`은 노드의 추론 타입을, `typeToString`은 그 타입을 사람이 읽는 문자열로 바꿔 준다. `symbol.declarations`는 배열이므로 함수 오버로드나 인터페이스 병합에서는 선언이 여러 개일 수 있다는 점을 잊지 말아야 한다.

## 4. Transformer 작성: ts.transform, factory, visitEachChild

변환은 `TransformerFactory<SourceFile>`을 작성하는 것으로 시작한다. 이 함수는 `TransformationContext`를 받아 `(sourceFile) => sourceFile` 형태의 transformer를 돌려준다. 내부에서는 `ts.visitEachChild(node, visitor, context)`로 자식 노드를 재귀 방문하고, 바꾸고 싶은 노드에서만 `context.factory`의 메서드로 새 노드를 만들어 반환한다. AST는 불변(immutable)으로 취급해야 하며, 노드를 직접 수정하는 대신 `factory.updateXxx`나 `factory.createXxx`로 새 노드를 만들고, 바뀌지 않은 부분 트리는 그대로 재사용한다.

아래 예제는 `console.log(...)` 호출을 제거하는 transformer다. 제거는 방문 함수가 `undefined`를 반환하면 된다. 단, 부모가 `ExpressionStatement`이므로 문장 단위에서 처리해야 `;`만 남는 일이 없다.

```ts
import * as ts from "typescript";

const stripConsoleLog: ts.TransformerFactory<ts.SourceFile> = (context) => {
  const isConsoleLog = (e: ts.Expression): boolean =>
    ts.isCallExpression(e) &&
    ts.isPropertyAccessExpression(e.expression) &&
    ts.isIdentifier(e.expression.expression) &&
    e.expression.expression.text === "console" &&
    e.expression.name.text === "log";

  const visit: ts.Visitor = (node) => {
    if (ts.isExpressionStatement(node) && isConsoleLog(node.expression)) {
      return undefined;                                  // 문장 통째로 제거
    }
    return ts.visitEachChild(node, visit, context);
  };
  return (sf) => ts.visitNode(sf, visit) as ts.SourceFile;
};

const src = `function f(a: number) { console.log(a); return a + 1; }`;
const sf = ts.createSourceFile("x.ts", src, ts.ScriptTarget.ES2022, true);
const result = ts.transform(sf, [stripConsoleLog]);
const printer = ts.createPrinter({ newLine: ts.NewLineKind.LineFeed });
console.log(printer.printFile(result.transformed[0]));
result.dispose();                                        // 사용 후 반드시 해제
```

`ts.transform(source, transformers, compilerOptions?)`은 `TransformationResult`를 돌려주고, 결과 노드는 `result.transformed` 배열에 있다. 변환이 끝나면 `dispose()`를 호출해 내부 상태를 해제한다. 출력에는 `ts.createPrinter()`의 `printFile`이나 `printNode`를 쓴다. 여기서 알아 둘 trade-off가 있다. Printer는 원본 텍스트의 서식을 보존하지 않고 AST로부터 코드를 다시 생성한다. 따라서 변환하지 않은 부분도 들여쓰기·따옴표·줄바꿈이 달라질 수 있어, 큰 저장소에 적용하면 diff가 커진다. 이 점이 Compiler API 단독 코드모드의 가장 큰 불편이고, ts-morph나 recast 같은 도구가 텍스트 위치 기반으로 편집하는 이유이기도 하다.

같은 transformer는 `program.emit(targetSourceFile, writeFile, cancellationToken, emitOnlyDtsFiles, customTransformers)`의 마지막 인자 `CustomTransformers`(`before`, `after`, `afterDeclarations`)로 빌드에 연결할 수 있다. 타입을 쓰려면 `before` 단계에서 Program을 클로저로 넘겨 `checker.getTypeAtLocation`을 호출한다. 다만 `tsc` CLI는 커스텀 transformer를 직접 지원하지 않으므로 ts-patch 같은 도구나 직접 만든 빌드 스크립트가 필요하다.

## 5. ts-morph로 코드모드 작성하기

ts-morph는 Compiler API를 감싼 래퍼로, `Project`가 Program과 파일 시스템을 함께 관리하고 `SourceFile`과 `Node` 래퍼가 편집 메서드를 제공한다. 편집은 내부적으로 텍스트 위치 기반으로 이뤄져 변경하지 않은 부분의 서식이 유지되므로 diff가 작다. 앞 절의 printer 문제를 피하려는 용도로 가장 흔한 선택이다.

```ts
import { Project, Node, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "./tsconfig.json" });

for (const sf of project.getSourceFiles("src/**/*.ts")) {
  // 1) import 경로 치환
  for (const imp of sf.getImportDeclarations()) {
    if (imp.getModuleSpecifierValue() === "@legacy/utils") {
      imp.setModuleSpecifier("@app/utils");
    }
  }
  // 2) 타입 조건부 변환: Promise를 반환하는 foo() 호출에 await 추가
  sf.forEachDescendant((node) => {
    if (Node.isCallExpression(node) && node.getExpression().getText() === "foo") {
      const t = node.getType();
      if (t.getSymbol()?.getName() === "Promise" && !Node.isAwaitExpression(node.getParent())) {
        node.replaceWithText(`await ${node.getText()}`);
      }
    }
  });
}
const before = project.getProgram().getPreEmitDiagnostics().length;
console.log("저장 전 진단 수:", before);
await project.save();
```

ts-morph를 쓸 때 가장 흔한 함정은 노드 무효화다. `replaceWithText`, `remove`, `insertXxx` 같은 구조 변경 뒤에는 이전에 잡아 둔 하위 노드 래퍼가 forgotten 상태가 되어 접근 시 예외가 난다. `node.wasForgotten()`으로 확인할 수 있고, 안전한 패턴은 변경 대상을 먼저 배열로 수집한 뒤 문서 끝쪽부터 역순으로 수정하는 것이다. `forEachDescendant` 안에서 편집하면서 계속 순회하는 것도 위험하므로 수집과 적용을 분리한다. 테스트에는 `new Project({ useInMemoryFileSystem: true })`를 써서 디스크 없이 입력과 기대 출력을 비교하면 빠르다.

## 6. isolatedDeclarations와 단일 파일 트랜스파일 모델

확장 대상인 두 개념은 "파일 하나만 보고 얼마나 처리할 수 있는가"라는 같은 축 위에 있다. `ts.transpileModule`과 `isolatedModules`는 TypeScript를 JavaScript로 바꾸는 emit이 파일 단위로 가능하도록 제약을 둔다. 타입 정보 없이 변환하므로 `const enum` 인라인이나 타입만 있는 재export(`export { T } from`)처럼 다른 파일의 타입 여부를 알아야 하는 구문이 문제가 되고, `isolatedModules`와 `verbatimModuleSyntax`는 이런 모호한 코드를 오류로 막는다. esbuild, SWC, Babel이 타입 체크 없이 빠르게 변환할 수 있는 이유가 이 모델이다.

TypeScript 5.5의 `isolatedDeclarations`는 같은 발상을 `.d.ts` 생성으로 확장한다. 이 옵션을 켜면 export되는 선언에 타입을 명시하도록 강제한다. 예를 들어 `export function f(a: number) { return a + 1 }`처럼 반환 타입을 추론에 맡긴 export는 오류가 된다. 대가로 선언 파일을 다른 파일의 타입 추론 없이, 그 파일의 구문만으로 생성할 수 있다. 이 옵션은 `declaration` 또는 `composite`가 켜져 있어야 의미가 있다. 그 결과 `.d.ts` 생성을 병렬화하거나 비TS 도구가 처리할 수 있게 된다. 코드모드와의 연결점은 분명하다. 기존 코드베이스에 이 옵션을 도입할 때 수백 개의 export에 타입을 직접 써 넣기는 어렵고, 체커로 추론 타입을 읽어 `typeToString`으로 주석을 채워 주는 코드모드가 정확히 이 작업에 맞다. 다만 추론 결과가 익명 타입이나 import하지 않은 타입이면 그대로 쓸 수 없으므로, 생성된 주석은 반드시 재컴파일해 검증해야 한다.

## 7. tsc 컴파일러 옵션과 분석 비용의 관계

Program 생성 비용은 옵션에 좌우된다. `skipLibCheck`는 `.d.ts`의 타입 체크를 생략해 시간을 줄이지만 선언 파일 속 오류를 놓치고, `noEmit`은 분석 전용 실행에 적합하며, `include`와 `types`로 대상 외 파일(테스트, 스토리북)을 빼면 로드 시간이 줄어든다.

수치는 환경마다 다르므로 직접 재야 한다. 측정 방법은 세 가지다. 첫째 `tsc --noEmit --extendedDiagnostics`는 파일 수, 심볼 수, Parse·Bind·Check 시간을 출력한다. 둘째 `tsc --generateTrace ./trace`는 구간별 trace를 남기며 Chrome의 `chrome://tracing`이나 Perfetto로 열 수 있다. 셋째 코드모드 스크립트 안에서 `performance.now()`로 `createProgram`, 첫 `getTypeAtLocation`, `save` 구간을 각각 감싸 재는 방법이다. 같은 입력으로 옵션만 바꿔 3회 이상 반복 측정하고 중앙값을 비교해야 캐시 효과에 속지 않는다.

## 8. 안전한 코드모드 운영 절차와 trade-off

코드모드는 한 번 돌리고 끝나는 스크립트가 아니라 검증 가능한 파이프라인으로 다뤄야 한다. 권장 절차는 다음과 같다. 먼저 변환 전 `ts.getPreEmitDiagnostics`의 오류 수를 기록하고, 대상 파일 일부(드라이런)에만 적용해 diff를 사람이 읽는다. 전체 적용 후 같은 진단을 다시 실행해 오류가 늘지 않았는지 비교하고 테스트를 통과시킨다. 변환은 멱등이어야 하며, 마지막에 포매터로 printer 기반 서식 차이를 정리한다.

선택 기준은 이렇다. Compiler API 단독은 의존성이 적지만 서식 보존이 약하고, ts-morph는 편집 API가 편하고 diff가 작지만 노드 무효화에 주의해야 하며, 단순 구문 치환은 Babel/SWC 계열이 더 빠르다. 타입 정보가 필요한 순간 Program 로드 비용을 치르므로, 구문만으로 걸러지는 조건을 먼저 적용해 체커 호출을 줄이는 것이 실용적이다.

## 참고

- TypeScript Wiki, Using the Compiler API: https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API
- TypeScript Handbook, Compiler Options (isolatedModules, isolatedDeclarations, skipLibCheck): https://www.typescriptlang.org/tsconfig
- TypeScript 5.5 릴리스 노트, Isolated Declarations: https://devblogs.microsoft.com/typescript/announcing-typescript-5-5/
- TypeScript Wiki, Performance (extendedDiagnostics, generateTrace): https://github.com/microsoft/TypeScript/wiki/Performance
- ts-morph 공식 문서: https://ts-morph.com/
