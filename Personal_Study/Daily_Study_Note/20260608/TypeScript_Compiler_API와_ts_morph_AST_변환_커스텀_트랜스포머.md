Notion 원본: https://www.notion.so/3795a06fd6d381509815db7f2790de65

# TypeScript Compiler API와 ts-morph 기반 AST 변환·커스텀 트랜스포머

> 2026-06-08 신규 주제 · 확장 대상: Javascript

## 학습 목표

- TypeScript Compiler API 의 Program·SourceFile·Node·TypeChecker 객체 모델을 구분한다
- AST 를 순회·질의하고 타입 정보를 추출하는 방법을 코드로 익힌다
- Custom Transformer 로 emit 단계의 AST 를 변형하는 파이프라인을 구성한다
- ts-morph 가 raw Compiler API 대비 무엇을 추상화하는지 판단한다

## 1. 컴파일러를 라이브러리로 쓴다는 것

`typescript` 패키지는 `tsc` CLI 만이 아니라 컴파일러 전체를 프로그래밍 API 로 노출한다. 코드 생성기, 린터, 코드모드, 문서 추출기, 타입 기반 검증기는 모두 이 API 위에서 소스를 AST 로 파싱하고 타입을 질의한다. `SourceFile` 은 한 파일의 AST 루트, `Node` 는 모든 노드의 기반(kind 로 식별), `Program` 은 여러 SourceFile + 옵션 + 의존 그래프, `TypeChecker` 는 의미 분석 질의를 담당한다. 파싱(구문)과 타입체킹(의미)이 분리되어 있어, 단순 구문 변형은 SourceFile 만으로 되지만 타입 기반 판단은 반드시 TypeChecker(Program)가 필요하다.

## 2. AST 순회와 타입 질의

```typescript
import ts from "typescript";
const program = ts.createProgram(["src/index.ts"], {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
});
const checker = program.getTypeChecker();
for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;
  ts.forEachChild(sf, function visit(node) {
    if (ts.isFunctionDeclaration(node) && node.name) {
      const symbol = checker.getSymbolAtLocation(node.name);
      if (symbol) {
        const type = checker.getTypeOfSymbolAtLocation(symbol, node);
        console.log(`${symbol.getName()} : ${checker.typeToString(type)}`);
      }
    }
    ts.forEachChild(node, visit);
  });
}
```

`is*` 타입 가드가 SyntaxKind 분기를 안전하게 만든다. `getSymbolAtLocation` → `getTypeOfSymbolAtLocation` 조합이 타입 기반 코드 생성(Zod 스키마, OpenAPI, GraphQL)의 토대다.

## 3. Custom Transformer: emit 을 가로채다

컴파일러는 emit 전 일련의 transformer 를 통과시킨다. AST 는 불변이므로 노드를 직접 수정하지 않고 `ts.factory` 로 새 노드를 만들어 교체한다.

```typescript
function loggerTransformer(): ts.TransformerFactory<ts.SourceFile> {
  return (context) => {
    const visit: ts.Visitor = (node) => {
      if (ts.isCallExpression(node) &&
          ts.isPropertyAccessExpression(node.expression) &&
          ts.isIdentifier(node.expression.expression) &&
          node.expression.expression.text === "console" &&
          node.expression.name.text === "log") {
        return ts.factory.createCallExpression(
          ts.factory.createPropertyAccessExpression(
            ts.factory.createIdentifier("logger"), "debug"),
          undefined, node.arguments);
      }
      return ts.visitEachChild(node, visit, context);
    };
    return (sf) => ts.visitNode(sf, visit) as ts.SourceFile;
  };
}
```

`tsc` 자체는 표준 custom transformer 플러그인 옵션을 제공하지 않으므로 `ts-patch`, `ts-loader` 의 `getCustomTransformers`, esbuild/swc 플러그인으로 연결한다. NestJS 의 `@nestjs/swagger` 플러그인이 이 메커니즘을 쓴다.

## 4. ts-morph: 고수준 래퍼

raw API 는 노드 수정 시 매번 factory 로 트리를 재구성해야 해 장황하다. **ts-morph** 는 `.rename()`, `.addMethod()`, `.remove()` 같은 가변 API 를 제공하고 변경을 추적해 파일로 저장한다.

```typescript
import { Project } from "ts-morph";
const project = new Project({ tsConfigFilePath: "tsconfig.json" });
for (const sf of project.getSourceFiles("src/**/*.ts")) {
  for (const cls of sf.getClasses()) {
    if (!cls.getProperty("createdAt")) {
      cls.addProperty({ name: "createdAt", type: "Date",
        isReadonly: true, initializer: "new Date()" });
    }
  }
}
await project.save();
```

ts-morph 도 내부적으로 같은 Compiler API 와 TypeChecker 를 쓰므로 `getType()` 타입 질의도 그대로 가능하다.

## 5. 무엇을 언제 쓰나

| 작업 | 권장 | 이유 |
|---|---|---|
| emit 시점 변형 | Compiler API transformer | tsc/번들러 emit 에 hook |
| 대규모 codemod | ts-morph | 가변 API·자동 저장 |
| 타입 기반 코드 생성기 | 둘 다(ts-morph 편함) | TypeChecker 질의 + 출력 |
| 최고 성능 필요 | raw Compiler API | 래퍼 오버헤드 없음 |

ts-morph 는 편의 객체 유지 비용이 raw 보다 크다. 수만 파일 일괄 변형이나 emit 핫패스에서는 raw, 한 번 돌리는 마이그레이션은 ts-morph 가 합리적이다.

## 6. 실무 함정

첫째, AST 를 in-place 수정하려는 시도 — 노드는 불변이며 `pos`/`end` 를 들고 있어 직접 바꾸면 printer 가 깨진다. 둘째, transformer 에서 `visitEachChild` 를 빠뜨려 자식이 방문되지 않는 것. 셋째, TypeChecker 없이 타입 판단을 시도하는 것 — 구문만으로는 `as` 단언이나 import 된 타입의 실체를 알 수 없다. ts-morph 에서도 tsConfigFilePath 연결을 빠뜨리면 타입이 비어 보인다.

## 7. 실전 예제: 인터페이스에서 런타임 검증 코드 생성

TypeScript 타입은 emit 시 지워지므로(type erasure) 런타임 검증이 필요하면 빌드 타임에 코드로 박제해야 한다.

```typescript
import { InterfaceDeclaration } from "ts-morph";
function genValidator(iface: InterfaceDeclaration): string {
  const checks = iface.getProperties().map((p) => {
    const name = p.getName(); const t = p.getType();
    if (t.isString())  return `typeof o.${name} === "string"`;
    if (t.isNumber())  return `typeof o.${name} === "number"`;
    if (t.isBoolean()) return `typeof o.${name} === "boolean"`;
    return `o.${name} !== undefined`;
  });
  return `export function is${iface.getName()}(o: any): boolean { return o != null && ${checks.join(" && ")}; }`;
}
```

이 패턴이 typia, typescript-is, io-ts 코드 생성기의 핵심 아이디어다. `getType()` 의 Type 객체로 union·배열·리터럴·옵셔널을 분기하면 중첩 구조까지 재귀적 검증을 생성한다.

## 8. 증분 처리와 성능

가장 큰 비용은 Program 생성과 TypeChecker 초기화다. TypeChecker 는 lazy 하지만 첫 타입 질의가 그 심볼의 의존 그래프를 끌어온다. 수천 파일마다 `getType()` 을 호출하면 폭증한다. 최적화: include 범위 최소화, 구문 필터로 선거른 뒤에만 타입 질의, 반복 조회 캐싱. `--extendedDiagnostics` 나 `--prof` 로 측정 후 최적화한다.

## 9. 워치 모드와 LanguageService

에디터 통합·실시간 도구는 Program 이 아니라 LanguageService 를 쓴다. 파일 버전 기반 증분 재해석을 하며 `getCompletionsAtPosition`, `getDefinitionAtPosition` 등 IDE API 를 노출한다. `createWatchProgram` 은 파일 변경을 감지해 영향 부분만 재컴파일한다. 일회성 변형은 `createProgram`, 지속 감시는 `createWatchProgram`/LanguageService 를 고른다.

## 참고

- TypeScript Wiki: "Using the Compiler API"
- TypeScript Wiki: "Using the Transformation API"
- ts-morph Documentation (ts-morph.com)
- `ts-patch` / `ts-loader` getCustomTransformers 문서
- AST Explorer (astexplorer.net)
