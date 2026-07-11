Notion 원본: https://app.notion.com/p/39a5a06fd6d3813ebbb4ca279c0b5293

# TypeScript Compiler API와 ts-morph AST 변환 및 커스텀 트랜스포머

> 2026-07-11 신규 주제 · 확장 대상: TypeScript (타입 시스템·모듈 해석 학습됨)

## 학습 목표

- TypeScript Compiler API의 Program·TypeChecker·SourceFile 계층을 구분하고 각 단계에서 얻는 정보를 설명한다
- ts-morph로 AST를 순회·수정하고 원본 포맷을 보존한 채 코드를 재출력한다
- `transformerFactory`를 작성해 emit 파이프라인에 커스텀 변환을 주입한다
- Node 재생성 시 발생하는 위치 정보·타입 손실 문제와 대응책을 판단한다

## 1. 컴파일러 파이프라인 개요

TypeScript 컴파일은 다섯 단계로 나뉜다. Scanner가 소스 텍스트를 토큰 스트림으로 바꾸고, Parser가 토큰을 AST(Abstract Syntax Tree)로 만든다. Binder는 심볼(Symbol)을 생성해 선언과 스코프를 연결하고, Checker는 심볼과 노드를 이용해 타입을 계산·검증한다. 마지막으로 Emitter가 변환(transform)을 거친 트리를 JavaScript와 `.d.ts`로 출력한다.

Compiler API를 다룰 때 핵심은 두 개의 관문이다. `SourceFile`(AST)은 "구문(syntax)"의 세계로 위치·텍스트·노드 종류를 안다. `TypeChecker`는 "의미(semantics)"의 세계로 타입·심볼·시그니처를 안다. 대부분의 실수는 이 둘을 혼동하는 데서 나온다. 예를 들어 변수의 실제 타입을 알고 싶으면 AST의 `node.type`을 읽는 게 아니라 Checker에게 물어야 한다. 타입 표기가 생략된 경우 AST에는 타입이 아예 없기 때문이다.

```ts
import ts from "typescript";

const program = ts.createProgram(["src/index.ts"], {
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.NodeNext,
});
const checker = program.getTypeChecker();

for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;
  ts.forEachChild(sf, function visit(node) {
    if (ts.isVariableDeclaration(node) && node.name) {
      const sym = checker.getSymbolAtLocation(node.name);
      if (sym) {
        const t = checker.getTypeOfSymbolAtLocation(sym, node.name);
        console.log(sym.getName(), "=>", checker.typeToString(t));
      }
    }
    ts.forEachChild(node, visit);
  });
}
```

`createProgram`은 전체 프로그램(모든 import 그래프)을 로드하므로 Checker가 크로스 파일 타입 추론을 할 수 있다. 단일 파일만 파싱하려면 `ts.createSourceFile`을 쓸 수 있으나, 이 경우 Checker가 없어 타입 질의가 불가능하다.

## 2. AST 노드 구조와 SyntaxKind

모든 노드는 `kind: ts.SyntaxKind` 값을 가진다. `SyntaxKind`는 300개가 넘는 열거형이고, `ts.isXxx` 형태의 타입 가드가 노드별로 제공된다. 직접 `node.kind === ts.SyntaxKind.CallExpression`을 비교하기보다 `ts.isCallExpression(node)`를 쓰면 타입 좁히기까지 되어 이후 프로퍼티 접근이 안전하다.

노드는 불변(immutable)이며 트리 구조상 부모 참조를 기본적으로 갖지 않는다. `node.parent`는 파싱 시 `setParentNodes`가 true일 때만 채워진다. `createProgram` 경로에서는 채워지지만, `createSourceFile`을 직접 호출하면 세 번째 인자로 `setParentNodes`를 넘겨야 한다.

| 계층 | 대표 노드 | 얻는 정보 |
|------|-----------|-----------|
| Scanner | Token | 텍스트, 트리비아(공백/주석) |
| Parser | SourceFile, Statement, Expression | 구문 구조, 위치(pos/end) |
| Binder | Symbol, FlowNode | 선언 위치, 스코프, 제어 흐름 |
| Checker | Type, Signature | 타입, 할당 가능성, 오버로드 |

위치 정보는 `node.getStart(sf)`와 `node.getEnd()`로 얻는다. `node.pos`는 선행 트리비아를 포함한 시작이라 실제 토큰 시작과 다르다는 점이 자주 혼동된다. 라인·컬럼이 필요하면 `sf.getLineAndCharacterOfPosition(pos)`를 쓴다.

## 3. ts-morph로 다루는 고수준 API

Compiler API는 강력하지만 노드 수정이 번거롭다. ts-morph는 그 위에 래퍼를 씌워 순회·수정·저장을 객체지향적으로 제공하고, 원본 포맷(들여쓰기·따옴표 스타일)을 최대한 보존한다.

```ts
import { Project, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });
const sf = project.getSourceFileOrThrow("src/user.ts");

// 모든 함수 선언에 반환 타입 표기 강제
sf.getFunctions().forEach((fn) => {
  if (!fn.getReturnTypeNode()) {
    const rt = fn.getReturnType();
    fn.setReturnType(rt.getText(fn)); // Checker가 추론한 타입을 명시화
  }
});

// console.log 호출 제거
sf.getDescendantsOfKind(SyntaxKind.CallExpression)
  .filter((c) => c.getExpression().getText() === "console.log")
  .forEach((c) => c.getFirstAncestorByKind(SyntaxKind.ExpressionStatement)?.remove());

await project.save();
```

`rt.getText(fn)`처럼 컨텍스트 노드를 넘기는 것이 중요하다. 타입을 문자열화할 때 import가 필요한 심볼을 어떻게 참조할지가 위치에 따라 달라지기 때문이다. ts-morph는 필요한 경우 import를 자동으로 추가해주는 `getText`의 컨텍스트 인지 동작을 활용한다.

ts-morph의 강점은 "wrapped node" 모델이다. 각 노드가 자바스크립트 객체로 래핑되어 `.rename()`, `.remove()`, `.insertText()` 같은 조작을 제공하며, 변경 후 내부적으로 텍스트를 다시 파싱해 트리 일관성을 유지한다. 대규모 리네이밍처럼 심볼 기반 조작이 필요하면 ts-morph의 `.rename()`이 프로젝트 전역 참조를 함께 갱신해 준다.

## 4. 커스텀 트랜스포머 작성

emit 단계에 개입하려면 `TransformerFactory<T>`를 만든다. 트랜스포머는 노드를 받아 새 노드를 반환하는 순수 함수의 조합이며, `ts.visitEachChild`로 자식을 재귀 방문한다. 아래는 모든 문자열 리터럴을 대문자로 바꾸는 예시다.

```ts
import ts from "typescript";

function upperCaseStrings<T extends ts.Node>(): ts.TransformerFactory<T> {
  return (context) => {
    const visit: ts.Visitor = (node) => {
      if (ts.isStringLiteral(node)) {
        return ts.factory.createStringLiteral(node.text.toUpperCase());
      }
      return ts.visitEachChild(node, visit, context);
    };
    return (node) => ts.visitNode(node, visit) as T;
  };
}

const source = `const msg = "hello world";`;
const result = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext },
  transformers: { before: [upperCaseStrings()] },
});
console.log(result.outputText); // const msg = "HELLO WORLD";
```

`ts.factory`는 노드 생성 팩토리다. 과거의 `ts.createXxx` 전역 함수는 deprecated되었고 4.0부터 `ts.factory.createXxx`로 통일됐다. `before` 트랜스포머는 타입 체크 이후·JS 변환 이전에 실행되고, `after`는 JS로 다운레벨된 뒤 실행된다. 타입 정보를 활용하려면 `before` 단계에서 Checker를 주입받아야 한다. `transpileModule`은 단일 파일·타입 정보 없이 동작하므로 Checker 기반 변환에는 `createProgram` + `program.emit(undefined, writeFile, undefined, false, { before: [...] })` 경로를 써야 한다.

## 5. 타입 정보를 활용하는 트랜스포머

실전 트랜스포머(예: 런타임 타입 검증 코드 생성, 의존성 주입 메타데이터 삽입)는 Checker가 필요하다. 팩토리에 program을 클로저로 넘겨 Checker에 접근한다.

```ts
function stripPrivateFields(program: ts.Program): ts.TransformerFactory<ts.SourceFile> {
  const checker = program.getTypeChecker();
  return (context) => (sf) => {
    const visit: ts.Visitor = (node) => {
      if (ts.isPropertyDeclaration(node)) {
        const sym = checker.getSymbolAtLocation(node.name);
        const isPrivate = node.modifiers?.some(
          (m) => m.kind === ts.SyntaxKind.PrivateKeyword
        );
        if (isPrivate) return undefined; // 노드 삭제
      }
      return ts.visitEachChild(node, visit, context);
    };
    return ts.visitNode(sf, visit) as ts.SourceFile;
  };
}
```

Visitor가 `undefined`를 반환하면 해당 노드는 트리에서 제거된다. 배열 컨텍스트(문장 목록 등)에서는 `ts.visitNodes`가 이를 자연스럽게 처리한다. 여기서 주의할 점은, 커스텀 트랜스포머의 출력은 `tsc`의 일반 emit에만 반영되고 IDE의 타입 체크나 `.d.ts` 생성에는 영향을 주지 않는다는 것이다. 즉 트랜스포머로 API를 바꾸면 타입 세계와 런타임 세계가 어긋날 수 있다.

## 6. 위치 정보와 소스맵 보존

노드를 새로 만들면 원본 위치(pos/end)가 -1로 초기화되어 소스맵이 깨진다. 원본 위치를 잇고 싶으면 `ts.setTextRange(newNode, originalNode)` 또는 `ts.setOriginalNode`로 원본을 연결한다. 후자는 emit 시 주석·소스맵 매핑을 원본 노드에서 상속하게 한다.

```ts
const replaced = ts.factory.createStringLiteral(node.text.toUpperCase());
ts.setOriginalNode(replaced, node);
ts.setTextRange(replaced, node);
return replaced;
```

소스맵을 생성하려면 컴파일러 옵션에 `sourceMap: true`를 켜고, `program.emit`이 `.js.map`을 함께 쓰도록 한다. 트랜스포머가 노드를 대량 재생성하면서 `setOriginalNode`를 빠뜨리면 디버거의 브레이크포인트가 엉뚱한 줄에 잡히는 문제가 생긴다.

## 7. 성능과 트레이드오프

`createProgram`은 전체 타입 그래프를 구축하므로 비용이 크다. 수천 개 파일 프로젝트에서 한 번의 Program 생성이 수 초가 걸릴 수 있다. 반복 분석이라면 `ts.createIncrementalProgram`이나 `.tsbuildinfo`를 활용해 증분 재사용을 노린다. 단순 구문 변환(타입 불필요)이라면 Program 없이 `createSourceFile`만으로 충분하고 훨씬 빠르다.

| 접근 | 타입 정보 | 상대 비용 | 적합한 작업 |
|------|-----------|-----------|-------------|
| createSourceFile | 없음 | 낮음 | lint 규칙, 구문 코드모드 |
| ts-morph | 있음(래핑) | 중간 | 리팩터링, codegen |
| createProgram + transformer | 있음 | 높음 | 타입 기반 emit 변환 |

ts-morph는 편의성이 높지만 내부적으로 텍스트 재파싱을 자주 하므로 초대형 배치 조작에서는 순수 Compiler API보다 느릴 수 있다. 대신 개발 생산성과 포맷 보존이 뛰어나 일회성 마이그레이션 스크립트에 적합하다. CI에 상주하는 lint성 검사라면 `createProgram`을 한 번 만들어 여러 파일을 재사용하는 편이 유리하다.

## 8. 실전 적용 패턴

코드모드(codemod)는 대규모 API 변경을 자동화한다. 예를 들어 사내 라이브러리의 함수 시그니처가 바뀌면 ts-morph로 모든 호출부를 찾아 인자를 재배치한다. 이때 심볼 기반으로 대상을 특정해야 이름만 같은 무관한 함수를 건드리지 않는다. `checker.getSymbolAtLocation`으로 심볼을 얻고, 그 심볼의 `getDeclarations()`가 우리가 바꾸려는 선언과 같은지 대조한다.

또 다른 패턴은 빌드 타임 코드 생성이다. Zod 스키마나 GraphQL 리졸버 타입을 `.d.ts`에서 추출해 런타임 검증 코드로 emit하는 것이다. 이 경우 Checker로 타입을 순회하며 `type.getProperties()`, `checker.getTypeOfSymbolAtLocation`을 재귀적으로 호출해 구조를 재구성한다. 재귀 타입(자기 참조)에서 무한 루프에 빠지지 않도록 방문한 타입의 `type.id`를 캐시에 기록해 순환을 끊어야 한다.

트랜스포머를 프로덕션 빌드에 넣을 때는 `ts-patch`나 `ttypescript` 계열 도구로 `tsc`의 emit에 트랜스포머를 주입하거나, ts-loader/esbuild 플러그인 경로를 쓴다. 순정 `tsc`는 커스텀 트랜스포머를 CLI로 받지 않으므로 프로그램 방식 emit이나 패치 도구가 필요하다는 점을 설계 초기에 확정해야 한다.

## 참고

- TypeScript Wiki, "Using the Compiler API" (github.com/microsoft/TypeScript/wiki)
- TypeScript Compiler Internals, Basarat Ali Syed (basarat.gitbook.io/typescript/overview)
- ts-morph 공식 문서 (ts-morph.com)
- TypeScript AST Viewer (ts-ast-viewer.com)
- microsoft/TypeScript `src/compiler/transformer.ts` 소스
