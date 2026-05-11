Notion 원본: https://www.notion.so/35d5a06fd6d38164870ac1f4cd2969aa

# TypeScript Compiler API와 ts-morph AST 조작 코드젠 패턴

> 2026-05-11 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `typescript` 패키지가 노출하는 *Compiler API* 의 4개 핵심 객체(`Program`, `TypeChecker`, `SourceFile`, `Node`) 가 무엇을 책임지는지 분리해서 본다
- `ts.factory` 를 사용한 *raw* AST 생성과, `ts-morph` 가 제공하는 *고수준* mutate API 의 trade-off (안전성·생산성·성능)를 코드로 비교한다
- `transformer` 와 `ts.visitNode`/`ts.visitEachChild` 의 패턴, `printer` 의 옵션(`removeComments`, `newLine`), source-map 보존을 다룬다
- AST 조작이 자주 쓰이는 4가지 실제 use case (코드젠, 마이그레이션, lint-fix, IDE refactor) 별 best practice 와 흔한 함정을 정리한다

## 1. Compiler API 의 4개 객체

`typescript` 를 `npm i typescript` 로 받으면 그 자체가 컴파일러이자 라이브러리다. 핵심은 다음 네 가지다.

| 객체 | 책임 | 만드는 방법 |
|---|---|---|
| `Program` | tsconfig + 파일 그래프 전체. 한 번에 만든다 | `ts.createProgram({ rootNames, options })` |
| `TypeChecker` | 타입 추론·심볼 해석 | `program.getTypeChecker()` |
| `SourceFile` | 한 파일의 AST 루트 | `ts.createSourceFile(...)` 또는 `program.getSourceFile(...)` |
| `Node` | 모든 AST 노드의 공통 인터페이스 | parent → child 탐색 |

```ts
import ts from "typescript";

const program = ts.createProgram({
  rootNames: ["src/index.ts"],
  options: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.NodeNext },
});
const checker = program.getTypeChecker();

for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;
  ts.forEachChild(sf, function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name) {
      const sym = checker.getSymbolAtLocation(node.name);
      console.log(node.name.text, checker.getTypeOfSymbolAtLocation(sym!, node.name).intrinsicName);
    }
    ts.forEachChild(node, visit);
  });
}
```

요점은 `Program` 을 만들지 않고 `createSourceFile` 만 쓰면 *type check 가 불가능* 하다는 것. 단순 텍스트 → AST 만 필요하면 `createSourceFile` 로 충분하지만, 심볼이나 타입을 묻고 싶으면 반드시 `Program` 이 있어야 한다.

## 2. `ts.factory` 로 AST 만들기 (3.0+ 정식 API)

3.0 이전엔 `ts.createIdentifier(...)` 같은 deprecated 함수를 직접 호출했다. 지금은 `ts.factory.*` 가 표준이다. SyntaxKind 가 노출되어 있고, 노드는 *불변* 이다.

장점: 표준 API 라 어떤 TS 버전에서도 동작. 단점: 노드 하나 만드는 데 7~8줄, 가독성이 떨어지고 *유지보수* 가 어렵다. 대규모 코드젠에는 `ts-morph` 가 거의 항상 낫다.

## 3. ts-morph 의 추상화 레벨

`ts-morph` 는 Compiler API 를 OOP wrapper 로 감싼 라이브러리다. 가독성이 압도적이다. 내부적으로는 `ts.factory` 를 호출하면서 *형제·부모 포인터* 를 알아서 관리해 주고, 변경 후에는 자동으로 SourceFile 을 재-파싱한다.

```ts
import { Project } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });
const sf = project.createSourceFile("out.ts", "", { overwrite: true });

sf.addFunction({
  name: "add",
  parameters: [
    { name: "a", type: "number" },
    { name: "b", type: "number" },
  ],
  returnType: "number",
  statements: ["return a + b;"],
});

await sf.save();
```

단점은 다음 두 가지다.

1. **퍼포먼스**: 1만 노드 이상을 mutate 하면 매번 re-parsing 이 일어나 느려진다. `forgetNodesCreatedInBlock` 으로 GC hint 를 줘야 한다.
2. **버전 호환**: ts-morph 의 *TypeScript peer dependency* 가 정해진 범위라, TS upgrade 시 라이브러리 업데이트가 늦으면 신규 문법을 못 다룬다.

## 4. Transformer — 컴파일 시점에 끼어드는 방법

emit 중간에 AST 를 바꾸려면 *transformer* 를 등록한다. 컴파일러 자체의 흐름에 합류하므로 type-checking 후에 호출되어 *심볼 정보* 를 활용할 수 있다는 게 큰 장점.

```ts
const transformer: ts.TransformerFactory<ts.SourceFile> = (context) => {
  return (sf) => {
    const visit: ts.Visitor = (node) => {
      if (
        ts.isCallExpression(node) &&
        ts.isIdentifier(node.expression) &&
        node.expression.text === "$LOG"
      ) {
        return ts.factory.createCallExpression(
          ts.factory.createPropertyAccessExpression(
            ts.factory.createIdentifier("console"),
            "log"
          ),
          undefined,
          node.arguments
        );
      }
      return ts.visitEachChild(node, visit, context);
    };
    return ts.visitNode(sf, visit) as ts.SourceFile;
  };
};
```

이 transformer 를 `tsc` 본체에 끼우는 표준 방법은 없지만, **ttypescript** 또는 **ts-patch** 같은 도구가 `tsconfig.json` 의 `compilerOptions.plugins` 를 hook 해서 등록해 준다.

## 5. Printer — emit 옵션 디테일

`EmitHint` 는 5가지가 있다 — `SourceFile`, `Expression`, `Unspecified`, `IdentifierName`, `MappedTypeParameter`. SourceFile 전체를 출력할 땐 `SourceFile`, 단일 노드만 출력할 땐 `Unspecified`.

주의: `printer.printNode(...)` 는 *문자열* 만 만든다. 파일에 쓰려면 `fs.writeFileSync` 가 필요하다. source-map 보존이 필요한 경우 `ts.createPrinter` 가 아니라 `program.emit(...)` 로 컴파일러 본체 emit 을 거치게 해야 한다.

## 6. 실전 use case 1 — JSON 스키마에서 TS 타입 생성

```ts
import { Project } from "ts-morph";

interface Schema {
  name: string;
  fields: Array<{ name: string; type: "string" | "number" | "boolean" }>;
}

function generateInterface(project: Project, schema: Schema) {
  const sf = project.createSourceFile(
    `generated/${schema.name}.ts`,
    "",
    { overwrite: true }
  );
  sf.addInterface({
    name: schema.name,
    isExported: true,
    properties: schema.fields.map((f) => ({
      name: f.name,
      type: f.type,
      hasQuestionToken: false,
    })),
  });
  return sf;
}
```

이런 코드젠은 OpenAPI / GraphQL schema → TS 변환의 핵심 패턴이다. 코드젠에서 빠지기 쉬운 함정은 import 정리, 포매팅 일관성, 재생성 vs 패치.

## 7. 실전 use case 2 — 코드 마이그레이션 (codemod)

예: 프로젝트 전체에서 `import { useEffect } from "react"` 를 `import { useEffect, useLayoutEffect } from "react"` 로 일괄 변경.

```ts
import { Project, Node } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });

for (const sf of project.getSourceFiles()) {
  const decl = sf.getImportDeclaration((d) => d.getModuleSpecifierValue() === "react");
  if (!decl) continue;
  const named = decl.getNamedImports().map((n) => n.getName());
  if (!named.includes("useEffect")) continue;
  if (named.includes("useLayoutEffect")) continue;
  decl.addNamedImport("useLayoutEffect");
}

await project.save();
```

`jscodeshift` 와 비교했을 때 `ts-morph` 의 강점은 *타입 정보를 동시에 활용* 할 수 있다는 점이다.

대규모 codemod 의 운영 팁: 1000 파일 이상이면 `Project` 를 multiple worker 로 쪼개기, 매 mutate 후 `forgetNodesCreatedInBlock` 으로 메모리 회수, *dry-run* 옵션을 항상 두기.

## 8. 실전 use case 3 — Decorator metadata 추출

```ts
import ts from "typescript";

function readControllerPath(checker: ts.TypeChecker, node: ts.ClassDeclaration): string | null {
  const decorators = ts.getDecorators(node) ?? [];
  for (const d of decorators) {
    if (ts.isCallExpression(d.expression)) {
      const sym = checker.getSymbolAtLocation(d.expression.expression);
      if (sym?.getName() === "Controller") {
        const arg = d.expression.arguments[0];
        if (arg && ts.isStringLiteral(arg)) return arg.text;
      }
    }
  }
  return null;
}
```

`ts.getDecorators` 는 4.8+ 의 stage-3 decorator 와 legacy 모두에 대응한다. 타입 추론으로 *런타임 라이브러리 없이* OpenAPI 를 생성하는 도구들(`nestjs-cli` 의 `--introspect`, `tsoa`) 이 정확히 이 패턴을 쓴다.

## 9. 함정 5가지

1. **Node 객체는 불변** — 직접 `node.text = "..."` 같은 mutation 은 *조용히 무시* 된다. 반드시 `ts.factory.updateXxx` 로 새 노드를 만들어 부모를 갈아 끼워야 한다.
2. **printer 가 trailing trivia 를 보존하지 않는 경우** — 주석이 노드 *밖* 에 있으면 emit 결과에서 사라질 수 있다. `ts.setSyntheticLeadingComments` 로 명시 보존.
3. **`getTypeAtLocation` 의 cost** — type checker 호출은 비싼 lazy 작업. 같은 노드에 반복 호출하지 말고 *한 번 캐싱* 해서 쓰기.
4. **multi-line string literal 처리** — `ts.factory.createStringLiteral` 은 *내부 escape* 를 하지 않는다. 입력에 `"` 가 있으면 직접 escape 하거나 `createNoSubstitutionTemplateLiteral` 사용.
5. **emit 후 mtime** — ts-morph 의 `save()` 는 *변경된 파일만* 디스크에 쓰지만, 빌드 캐시 키로 mtime 을 쓰면 빌드가 매번 깨질 수 있어 *내용 해시* 기반 캐시가 더 안전하다.

## 참고

- TypeScript Compiler API Reference — https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API
- ts-morph 공식 문서 — https://ts-morph.com/
- ts-patch — https://github.com/nonara/ts-patch
- *TypeScript Deep Dive* (Basarat Ali Syed) — Compiler 챕터
- Microsoft Devblog — "Writing a Language Service Plugin"
- Effective TypeScript (Dan Vanderkam) Item 50, 53 — Custom Transformer / Codemod
