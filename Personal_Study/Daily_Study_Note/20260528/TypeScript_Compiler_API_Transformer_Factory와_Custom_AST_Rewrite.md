Notion 원본: https://www.notion.so/36e5a06fd6d3813586fcde6ab8bd2df1

# TypeScript Compiler API Transformer Factory와 Custom AST Rewrite

> 2026-05-28 신규 주제 · 확장 대상: TypeScript 컴파일러 API — AST 변환과 emit 파이프라인

## 학습 목표

- `ts.createProgram` → `emit()` 호출 사이에 transformer 가 어느 단계에 끼어드는지 정확히 짚는다.
- `TransformerFactory<T>` 시그니처와 `ts.visitNode`, `ts.visitEachChild` 의 호출 순서를 단계별로 추적한다.
- `before` / `after` / `afterDeclarations` 세 슬롯의 동작 차이를 구분해 사용한다.
- 직접 transformer 를 작성해 메타데이터 주입 / 데코레이터 매크로 변환 / 컴파일 타임 코드 생성을 구현한다.

## 1. 컴파일 파이프라인 개요

Scanner → Parser → Binder/Checker → Transformer/Emitter. transformer 는 emit 직전 *type 정보가 부여된 AST* 를 받아 수정. `before` 는 TS AST→TS AST, `after` 는 emit 후 JS AST→JS AST, `afterDeclarations` 는 .d.ts.

## 2. TransformerFactory 의 시그니처

```ts
type TransformerFactory<T extends ts.Node> = (context: ts.TransformationContext) => Transformer<T>;
type Transformer<T extends ts.Node> = (node: T) => T;

const myTransformer: ts.TransformerFactory<ts.SourceFile> = (context) => {
  return (sourceFile) => {
    const visitor: ts.Visitor = (node) => {
      if (ts.isCallExpression(node) && isMyTarget(node)) {
        return rewriteCall(node, context.factory);
      }
      return ts.visitEachChild(node, visitor, context);
    };
    return ts.visitNode(sourceFile, visitor) as ts.SourceFile;
  };
};
```

## 3. Visitor 패턴의 호출 순서

`visitNode` 는 단일 노드 1회, `visitEachChild` 는 자식들에 visitor 호출. root 에서 `visitNode(sourceFile, visitor)` 부터 시작해야 함.

visitor 반환값: node 그대로(변경 X), 다른 Node(교체), undefined(삭제), 배열(확장).

## 4. Factory 로 새 AST 만들기

```ts
const newClass = context.factory.updateClassDeclaration(
  classNode,
  [decoratorCall, ...(ts.getModifiers(classNode) ?? [])],
  classNode.name,
  classNode.typeParameters,
  classNode.heritageClauses,
  classNode.members
);
```

`updateXxx` 는 변경된 필드만 새 노드. sourcemap 정확성을 위해 중요.

## 5. TypeChecker 와 결합한 메타데이터 주입

JSDoc `@AutoMeta` 가 붙은 클래스마다 type 정보가 담긴 static __meta__ 필드 주입. `checker.typeToString(checker.getTypeAtLocation(p))` 사용.

## 6. Program 에서 Transformer 실행

```ts
const program = ts.createProgram(["src/main.ts"], { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.NodeNext, strict: true });
const emitResult = program.emit(undefined, undefined, undefined, false, { before: [injectMetadata(program)], after: [], afterDeclarations: [] });
```

tsc CLI 는 미지원 — ttypescript, ts-patch, webpack ts-loader, Vite esbuild plugin 으로 주입.

## 7. Trade-off — 직접 작성 vs ts-morph / babel

| 도구 | 장점 | 단점 | 사용처 |
|---|---|---|---|
| Compiler API | TypeChecker 직접 접근 | 학습곡선 가파름 | 메타데이터 주입 |
| ts-morph | OOP wrapper, 간결 | 일부 기능 누락 | 일회성 codemod |
| Babel plugin | 빠름 | type 정보 없음 | syntax 변환 |
| jscodeshift | codemod 표준 | TS 약함 | JS refactor |

## 8. 실측 — Transformer 성능

```
800 파일, 12만 LOC, TS 5.5, Node 20
기본 tsc:                17.2 s
+ noop before:           17.5 s
+ injectMetadata:        21.4 s
+ ts-morph 동일:         29.6 s
```

`ts.factory` 직접 호출이 ts-morph wrapper 보다 3배 빠르다.

## 9. 디버깅 팁

`ts.SyntaxKind` 출력. AST Explorer (astexplorer.net) 사용. `ts.printer.printNode` 로 변환 결과 검증.

## 10. Diagnostic 생성과 lint-as-transformer

Diagnostic 객체 collector 에 모아 후처리 throw — type-aware 규칙 가능. ESLint 와 달리 TypeChecker 접근 가능.

## 11. ts-morph 로의 마이그레이션

100+ LOC transformer 는 ts-morph 로 옮기면 1/4 코드. 단 파일 저장 기반이라 emit 파이프라인 in-memory 와 다름. 일회성 codemod 적합.

## 참고

- TypeScript Compiler API Wiki
- TypeScript AST Viewer
- ts-patch, ts-morph
- NestJS Swagger CLI Plugin
- Basarat Ali Syed 자료
