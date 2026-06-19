Notion 원본: https://www.notion.so/3845a06fd6d38164a0abe7ada6bb1cf5

# TypeScript Compiler API와 ts-morph 기반 AST 변환 및 코드 생성 자동화

> 2026-06-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript Compiler API의 Program / SourceFile / Node 계층 구조를 파악하고 AST를 직접 순회한다
- TypeChecker로 심볼과 타입 정보를 질의해 단순 텍스트 치환으로 불가능한 변환을 구현한다
- ts-morph의 고수준 래퍼로 AST를 안전하게 수정하고 변경분을 디스크에 반영한다
- 코드 생성·마이그레이션·린트 자동화에서 transform과 emit 파이프라인을 설계한다

## 1. Compiler API의 4계층: Program, SourceFile, Node, TypeChecker

TypeScript 컴파일러는 텍스트를 토큰화한 뒤 추상 구문 트리(AST)로 파싱하고, 바인딩 단계에서 심볼 테이블을 구성하며, 타입 체크를 거쳐 JavaScript를 emit한다. Compiler API는 이 파이프라인의 각 단계를 프로그래밍 방식으로 노출한다.

진입점은 `Program`이다. 여러 SourceFile을 묶은 컴파일 단위이며, 모듈 해석 결과와 TypeChecker를 보유한다.

```typescript
import ts from "typescript";

const program = ts.createProgram(["src/index.ts"], {
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.NodeNext,
  strict: true,
});

const checker = program.getTypeChecker();

for (const sourceFile of program.getSourceFiles()) {
  if (sourceFile.isDeclarationFile) continue;
  ts.forEachChild(sourceFile, visit);
}
```

핵심은 `ts.SyntaxKind` enum이다. 모든 노드는 `node.kind`로 종류를 식별하며, `ts.isXxx` 타입 가드가 좁히기를 도와준다. AST 구조 탐색은 TypeScript AST Viewer에 코드를 붙여 노드 계층과 kind를 눈으로 확인하는 것이 가장 빠르다.

## 2. AST 순회: forEachChild vs getChildren

`ts.forEachChild(node, cb)`는 의미 있는(semantic) 자식 노드만 방문하고, 구두점·키워드 같은 토큰 노드는 건너뛴다. 콜백이 truthy를 반환하면 즉시 멈춰 단락 평가가 가능하다. 대부분의 분석·변환은 이쪽을 쓴다.

`node.getChildren()`은 토큰을 포함한 모든 자식을 배열로 반환한다. 세미콜론·콤마·중괄호까지 다뤄야 하는 포매터·트리비아 처리에서만 필요하며 더 느리다.

<table header-row="true">
<tr><td>방법</td><td>방문 대상</td><td>단락 평가</td><td>용도</td></tr>
<tr><td>forEachChild</td><td>semantic 노드만</td><td>가능</td><td>분석·변환 일반</td></tr>
<tr><td>getChildren</td><td>토큰 포함 전부</td><td>불가</td><td>포매터·트리비아</td></tr>
</table>

## 3. TypeChecker: 텍스트로 풀 수 없는 변환

정규식·텍스트 치환으로 불가능한 변환은 거의 항상 타입/심볼 정보를 요구한다. "특정 인터페이스를 구현한 클래스 찾기", "import된 심볼의 실제 선언 추적", "유니온 타입 멤버 열거"는 TypeChecker 없이 신뢰성 있게 못 한다.

```typescript
function getUnionMembers(type: ts.Type, checker: ts.TypeChecker): string[] {
  if (type.isUnion()) return type.types.map((t) => checker.typeToString(t));
  return [checker.typeToString(type)];
}
```

TypeChecker는 Program 전체를 바인딩해야 동작하므로 비용이 크다. 단일 파일 구문만 보면 되는 경우(import 정렬 등)는 생성하지 않는 것이 빠르다.

## 4. Transformer API와 emit 파이프라인

AST는 불변 트리라 "수정"은 곧 새 노드를 만들어 교체하는 것이다. `ts.transform`은 transformer 팩토리를 받아 새 트리를 만들고, 노드 생성은 `ts.factory`를 사용한다.

```typescript
const removeConsoleLog: ts.TransformerFactory<ts.SourceFile> =
  (context) => (sourceFile) => {
    const visitor = (node: ts.Node): ts.Node | undefined => {
      if (ts.isExpressionStatement(node) && ts.isCallExpression(node.expression)) {
        const expr = node.expression.expression;
        if (ts.isPropertyAccessExpression(expr) && ts.isIdentifier(expr.expression) && expr.expression.text === "console") return undefined;
      }
      return ts.visitEachChild(node, visitor, context);
    };
    return ts.visitNode(sourceFile, visitor) as ts.SourceFile;
  };
```

`ts.visitEachChild`는 변경된 노드만 새로 만들고 나머지는 참조를 재사용한다(structural sharing). `ts.factory`는 장황해 실무에서는 ts-morph를 쓴다.

## 5. ts-morph: 고수준 래퍼의 생산성

ts-morph는 Compiler API를 감싸 가변 객체처럼 AST를 다루게 한다. `addMethod`, `rename`, `set...` 메서드로 트리를 조작하고 내부적으로 Compiler API 트리와 동기화한다.

```typescript
import { Project } from "ts-morph";
const project = new Project({ tsConfigFilePath: "tsconfig.json" });
const sourceFile = project.getSourceFileOrThrow("src/user.ts");
const userClass = sourceFile.getClassOrThrow("User");
userClass.addMethod({ name: "toJSON", returnType: "Record<string, unknown>", statements: "return { ...this };" });
sourceFile.getInterfaceOrThrow("Dto").rename("UserDto");
await project.save();
```

`rename`은 언어 서비스의 find-all-references를 사용해 프로젝트 전체 참조를 안전하게 갱신한다. 이것이 sed/정규식 치환과 결정적으로 다른 점이다.

| 작업 | raw Compiler API | ts-morph |
|---|---|---|
| 노드 생성 | ts.factory(장황) | add/insert 메서드 |
| 참조 안전 rename | 직접 구현 | rename() 한 줄 |
| 변경 저장 | printer + fs 수동 | save() 자동 |

## 6. 실전 코드 생성: 인터페이스에서 Zod 스키마 생성

타입 정의를 단일 진실원천(SSOT)으로 삼아 런타임 검증 스키마를 자동 생성한다.

```typescript
function tsTypeToZod(typeText: string): string {
  switch (typeText) {
    case "string": return "z.string()";
    case "number": return "z.number()";
    case "boolean": return "z.boolean()";
    default: return typeText.endsWith("[]") ? `z.array(${tsTypeToZod(typeText.slice(0, -2))})` : "z.unknown()";
  }
}
```

`p.getType().getText()`는 TypeChecker가 계산한 실제 타입 문자열을 돌려준다. 제네릭·중첩 유니온은 `isUnion()`, `getArrayElementType()` 같은 타입 API로 재귀 처리해야 견고하다.

## 7. 성능과 함정

가장 흔한 실수는 변환마다 Project/Program을 새로 만드는 것이다. 한 번 만든 Project를 재사용하고, 타입 정보가 필요 없으면 `useInMemoryFileSystem` 또는 단일 파일 파싱으로 우회한다. 둘째, 텍스트 수정 후 캐시된 노드 위치(pos/end)가 어긋날 수 있다. 셋째, raw printer는 주석을 잃을 수 있어 트리비아 보존이 중요하면 ts-morph를 쓴다.

```typescript
const project = new Project({ useInMemoryFileSystem: true });
const sf = project.createSourceFile("t.ts", "const x: number = 1;");
sf.getVariableDeclarationOrThrow("x").setType("string");
```

마지막으로 변환 전후 `project.getPreEmitDiagnostics()`로 진단 0건을 확인해 깨진 코드 생성을 막는다.

## 8. 커스텀 린트 룰과 코드모드 테스트 전략

AST 도구의 대표 용도는 ESLint로 표현하기 어려운 프로젝트 고유 규칙 강제다. "모든 Controller 메서드는 반환 타입을 명시해야 한다"는 TypeChecker로 명시적 반환 타입 노드 유무를 검사해 구현한다.

```typescript
sf.getClasses().filter((c) => c.getName()?.endsWith("Controller"))
  .forEach((c) => c.getMethods().forEach((m) => {
    if (!m.getReturnTypeNode()) violations.push(`${sf.getBaseName()}::${m.getName()}`);
  }));
```

`getReturnTypeNode()`는 소스에 명시된 타입만, `getReturnType()`은 추론 타입을 돌려준다. 둘을 구분 못 하면 오판한다. 코드모드는 반드시 테스트한다. 메모리 FS에 입력을 만들고 변환 후 출력을 스냅샷 비교하며 진단 0건도 확인한다. CI에서는 dry-run으로 diff를 사람이 검토한 뒤 적용하고, 대규모 리팩터링은 디렉터리·패키지 단위로 쪼개 점진 적용한다.

## 참고

- TypeScript Wiki — Using the Compiler API (microsoft/TypeScript GitHub wiki)
- TypeScript Wiki — Using the Language Service API
- ts-morph 공식 문서 (ts-morph.com)
- TypeScript AST Viewer (ts-ast-viewer.com)
- TypeScript Handbook — Type Manipulation
