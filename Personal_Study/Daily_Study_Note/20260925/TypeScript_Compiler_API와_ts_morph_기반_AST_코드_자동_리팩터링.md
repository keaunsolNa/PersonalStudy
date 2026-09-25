Notion 원본: https://app.notion.com/p/3e65a06fd6d381ff9bd4c282495a6338

# TypeScript Compiler API와 ts-morph 기반 AST 코드 자동 리팩터링

> 2026-09-25 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript Compiler API의 3단계 파이프라인(Scanner → Parser → Binder/Checker)에서 AST가 만들어지는 과정을 구분한다
- `ts-morph`가 Compiler API를 감싸며 제공하는 변경 추적(manipulation) 모델의 이점을 설명한다
- `Node`, `Symbol`, `Type` 세 가지 표현의 차이를 실제 코드 조작 상황에 맞게 구분해 사용한다
- AST 기반 코드모드(codemod)를 작성해 대규모 저장소의 반복적 리팩터링을 자동화한다

## 1. Compiler API의 처리 파이프라인

TypeScript 소스 하나가 타입체크되기까지 컴파일러 내부에서는 여러 단계가 순차적으로 실행된다. **Scanner**가 소스 텍스트를 토큰 스트림으로 분해하고, **Parser**가 이 토큰들을 재귀 하강 파싱으로 구체 구문 트리(AST, `SourceFile` 노드를 루트로 하는 트리)로 구성한다. 이 시점의 AST는 아직 타입 정보가 없는 순수 구문 트리다. 이후 **Binder**가 AST를 순회하며 각 선언(변수, 함수, 클래스 등)에 대해 `Symbol`(이름과 선언 위치를 연결하는 논리적 개체)을 생성하고 스코프 체인을 구성한다. 마지막으로 **Checker**가 Symbol과 AST 노드를 바탕으로 각 표현식의 `Type`을 추론하고 타입 오류를 검출한다.

```typescript
import ts from "typescript";

const sourceFile = ts.createSourceFile(
  "example.ts",
  "const x: number = 42;",
  ts.ScriptTarget.Latest,
  true
);

function printAst(node: ts.Node, indent = 0) {
  console.log(" ".repeat(indent) + ts.SyntaxKind[node.kind]);
  node.forEachChild(child => printAst(child, indent + 2));
}

printAst(sourceFile);
// SourceFile
//   VariableStatement
//     VariableDeclarationList
//       VariableDeclaration
//         Identifier         <- "x"
//         NumberKeyword      <- ": number"
//         NumericLiteral     <- "42"
```

이 파이프라인을 이해하는 것이 중요한 이유는, "구문적으로만 필요한 조작"(변수명 변경, import 추가)은 Parser 단계의 AST만으로 충분하지만, "타입 정보가 필요한 조작"(특정 타입을 가진 매개변수 찾기, 타입 추론 결과 기반 리팩터링)은 반드시 `Program`을 생성해 Checker까지 실행해야 한다는 실무적 판단 기준이 되기 때문이다.

## 2. Node, Symbol, Type의 구분

Compiler API를 처음 다룰 때 가장 헷갈리는 지점이 `Node`, `Symbol`, `Type` 세 개념의 차이다.

**Node**는 소스 코드의 구문적 위치를 나타낸다. 같은 변수를 참조하는 두 곳의 `Identifier` 노드는 텍스트 위치가 다르므로 서로 다른 Node 객체다.

**Symbol**은 하나의 선언(과 그 선언을 참조하는 모든 위치)을 논리적으로 묶는 개체다. `let x = 1; x = 2;`에서 두 `x` Identifier Node는 다르지만, 둘 다 같은 `Symbol`을 가리킨다.

**Type**은 특정 위치에서 표현식이 가지는 타입 정보다. 같은 `Symbol`이라도 흐름 분석(control flow analysis)에 따라 위치별로 다른 `Type`을 가질 수 있다(타입 내로잉).

```typescript
function example(program: ts.Program, checker: ts.TypeChecker, node: ts.Identifier) {
  const symbol = checker.getSymbolAtLocation(node);       // 이 식별자가 가리키는 선언
  const type = checker.getTypeAtLocation(node);           // 이 위치에서의 타입(내로잉 반영)
  const declaredType = checker.getTypeOfSymbol(symbol!);  // 선언 자체의 타입(내로잉 미반영)

  console.log(checker.typeToString(type));
  console.log(checker.typeToString(declaredType));
}
```

예를 들어 `function f(x: string | number) { if (typeof x === "string") { /* 여기 */ } }`에서 주석 위치의 `x`에 대해 `getTypeAtLocation`은 `string`을 반환하지만(내로잉 반영), `getTypeOfSymbol`은 선언 시점의 `string | number`를 반환한다. 코드 조작 도구를 작성할 때 이 차이를 혼동하면 내로잉된 타입을 기준으로 판단해야 할 곳에서 넓은 타입을 기준으로 판단하는 버그가 생긴다.

## 3. ts-morph가 추상화하는 것

순수 Compiler API로 코드를 **수정**하려면 `ts.factory`로 새 노드를 생성하고 `ts.transform`으로 트랜스포머를 적용한 뒤 `Printer`로 텍스트를 재생성하는, 상당히 저수준의 절차를 거쳐야 한다. `ts-morph`는 이 과정을 감싸 **변경 가능한(mutable) 객체 모델**을 제공한다. Compiler API의 `Node`가 불변 스냅샷인 것과 달리, `ts-morph`의 `Node` 래퍼는 `.rename()`, `.remove()`, `.replaceWithText()` 같은 메서드를 직접 호출하면 즉시 내부적으로 AST를 갱신하고 필요 시 파일을 다시 파싱한다.

```typescript
import { Project, SyntaxKind } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });

// 저장소 전체에서 특정 함수 호출 패턴을 찾아 일괄 변경
project.getSourceFiles("src/**/*.ts").forEach(sourceFile => {
  const calls = sourceFile.getDescendantsOfKind(SyntaxKind.CallExpression);

  for (const call of calls) {
    const expr = call.getExpression();
    if (expr.getText() === "oldApiCall") {
      expr.replaceWithText("newApiCall");
      // 인자 순서가 바뀌는 API라면 인자 노드도 함께 재배치
      const args = call.getArguments();
      if (args.length === 2) {
        const [first, second] = args.map(a => a.getText());
        call.replaceWithText(`newApiCall(${second}, ${first})`);
      }
    }
  }
});

project.saveSync(); // 실제 디스크에 변경사항 기록
```

`ts-morph`는 내부적으로 Compiler API의 `LanguageService`를 활용해 `rename()` 호출 시 해당 심볼을 참조하는 **모든 파일**의 참조를 찾아 함께 변경한다. 이는 단순 문자열 치환(`sed`, 정규식)과 근본적으로 다른데, 문자열 치환은 주석이나 문자열 리터럴 안의 동일한 텍스트까지 실수로 바꿀 위험이 있지만 AST 기반 조작은 실제 구문적 의미가 있는 위치만 정확히 변경한다.

## 4. 실전 코드모드: deprecated API 일괄 치환

레거시 API를 신규 API로 전환하는 대규모 코드모드를 작성하는 경우를 살펴본다. 예를 들어 `moment()` 기반 날짜 처리를 `date-fns`로 마이그레이션한다고 하자.

```typescript
import { Project, SyntaxKind, Node } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });
const checker = project.getTypeChecker();

for (const sourceFile of project.getSourceFiles("src/**/*.ts")) {
  let changed = false;

  sourceFile.forEachDescendant(node => {
    if (Node.isCallExpression(node) && node.getExpression().getText() === "moment") {
      const type = checker.getTypeAtLocation(node);
      // 타입 정보로 실제 moment 라이브러리의 반환 타입인지 검증(오탐 방지)
      if (type.getText().includes("Moment")) {
        node.replaceWithText(`new Date(${node.getArguments().map(a => a.getText()).join(", ")})`);
        changed = true;
      }
    }
  });

  if (changed) {
    // import 문 정리: moment import가 더 이상 쓰이지 않으면 제거
    const momentImport = sourceFile.getImportDeclaration(
      decl => decl.getModuleSpecifierValue() === "moment"
    );
    const stillUsed = sourceFile.getDescendantsOfKind(SyntaxKind.Identifier)
      .some(id => id.getText() === "moment");
    if (momentImport && !stillUsed) {
      momentImport.remove();
    }
    sourceFile.saveSync();
  }
}
```

이 예제에서 핵심은 단순히 `moment(` 텍스트를 찾는 것이 아니라 **타입 체커로 실제 `Moment` 타입을 반환하는 호출인지 검증**하는 부분이다. 만약 `moment`라는 이름의 로컬 변수나 다른 라이브러리의 동명 함수가 있다면, 타입 검증 없이는 잘못된 치환이 발생할 수 있다. 대규모 코드베이스에 자동 리팩터링을 적용할 때는 이런 **오탐 방지 검증**을 반드시 포함해야 하며, 이것이 정규식 기반 치환 대비 Compiler API/ts-morph 기반 접근이 갖는 결정적 우위다.

## 5. LanguageService와 Quick Fix 재사용

`ts-morph`와 Compiler API는 VSCode의 "빠른 수정(Quick Fix)" 기능이 내부적으로 사용하는 `ts.LanguageService`의 `getCodeFixesAtPosition`을 직접 호출할 수 있게 해준다. 이를 활용하면 "미사용 import 제거", "누락된 import 자동 추가" 같은 IDE 수준의 수정을 CLI 스크립트로 저장소 전체에 일괄 적용할 수 있다.

```typescript
import { Project } from "ts-morph";

const project = new Project({ tsConfigFilePath: "tsconfig.json" });
const languageService = project.getLanguageService().compilerObject;

for (const sourceFile of project.getSourceFiles()) {
  const diagnostics = languageService.getSemanticDiagnostics(sourceFile.getFilePath());
  const unusedImportDiagnostics = diagnostics.filter(d => d.code === 6133); // '... is declared but never used'

  for (const diag of unusedImportDiagnostics) {
    const fixes = languageService.getCodeFixesAtPosition(
      sourceFile.getFilePath(),
      diag.start!, diag.start! + diag.length!,
      [diag.code],
      {}, {}
    );
    // fixes[0].changes 를 적용하는 로직 (ts-morph의 applyTextChanges 헬퍼 활용)
  }
}
```

이 접근은 순수 문자열 치환으로는 재현하기 어려운 "컴파일러가 이미 알고 있는 정확한 수정안"을 그대로 재사용할 수 있다는 점에서, 자체적으로 규칙을 다시 구현하는 것보다 훨씬 신뢰도가 높다.

## 6. 성능: Program 재생성 비용

`ts-morph`로 수백~수천 개 파일을 순회하며 조작할 때 흔히 격는 성능 문제는, 파일을 수정할 때마다 내부적으로 해당 파일의 AST가 다시 파싱되고 필요 시 `Program`(전체 타입 정보를 담은 컴파일 단위)이 갱신된다는 점이다. `getTypeChecker()`를 호출할 때마다 갱신 비용이 들 수 있으므로, 대량의 파일을 처리할 때는 타입 정보가 꿅 필요한 검증 단계와 텍스트 치환 단계를 분리해, 타입 조회를 최소 횟수로 배치 처리하는 편이 실행 시간을 크게 줄인다. 실측 기준으로 순수 텍스트 치환만 필요한 코드모드는 수천 파일도 수 초 내에 처리되지만, 매 노드마다 `checker.getTypeAtLocation()`을 호출하는 코드모드는 파일 수에 따라 수 분까지 소요될 수 있다.

## 7. AST 기반 vs 정규식 기반 리팩터링 비교

| 기준 | 정규식/문자열 치환 | AST 기반(Compiler API/ts-morph) |
|---|---|---|
| 문자열/주석 오탐 위험 | 높음(구문 구분 없음) | 없음(구문 단위로 정확히 식별) |
| 타입 기반 조건부 변경 | 불가능 | 가능(Checker 연동) |
| 여러 파일에 걸친 참조 추적(rename) | 수동으로 전체 검색 필요 | LanguageService가 자동 추적 |
| 구현 난이도 | 낮음 | 중간~높음(AST 구조 학습 필요) |
| 실행 속도(대량 파일) | 매우 빠름 | 타입 조회 여부에 따라 가변적 |

간단한 치환(고정 문자열 하나를 다른 문자열로 바꾸는 정도)은 정규식으로 충분하지만, "특정 타입의 값에만", "특정 스코프 안에서만", "실제로 그 심볼을 참조하는 위치만" 같은 조건이 하나라도 붙는 순간 AST 기반 접근이 안전성과 정확도 면에서 압도적으로 유리해진다.

## 8. 실무 적용 가이드

대규모 코드베이스에서 반복적인 리팩터링(라이브러리 마이그레이션, 네이밍 컨벤션 통일, deprecated API 제거)이 필요할 때는, 먼저 변경 대상 패턴을 정확히 식별하는 `SyntaxKind` 필터를 작성하고, 오탐 가능성이 있다면 타입 체커로 2차 검증을 추가한다. 그 다음 실제 파일을 수정하기 전에 **dry-run 모드**로 변경될 위치의 목록만 출력해 사람이 리뷰할 수 있게 하고, 검토가 끝난 뒤에만 `project.save()`를 호출하도록 스크립트를 설계하는 것이 안전하다. 마지막으로 코드모드 실행 후에는 반드시 `tsc --noEmit`으로 전체 타입체크를 다시 실행해, 자동 변경이 새로운 타입 오류를 만들지 않았는지 확인하는 단계를 CI에 포함시켜야 한다.

## 9. jscodeshift와의 관계

Facebook이 만든 `jscodeshift`는 JavaScript/TypeScript 코드모드 도구로 더 널리 알려져 있지만, 내부적으로는 Babel 파서를 사용해 타입 정보 없는 순수 구문 AST만 다루다는 차이가 있다. `ts-morph`/Compiler API 조합은 `jscodeshift`가 할 수 없는 "타입 기반 조건부 변경"(4절의 `Moment` 타입 검증 같은)을 지원하는 대신, TypeScript 프로젝트에 한정된다는 제약이 있다. 순수 JavaScript 저장소나 프레임워크 특화 codemod(예: React Hooks 마이그레이션)처럼 타입 정보가 필요 없는 대규모 구문 변환에는 `jscodeshift`가 여전히 더 가벼고 생태계가 넘다. 두 도구를 팀 상황에 맞게 구분해 선택하되, TypeScript 타입 시스템을 적극 활용하는 코드베이스에서는 `ts-morph` 쪽이 오탐을 줄이는 데 더 유리하다.

## 참고

- TypeScript 공식 Wiki, "Using the Compiler API"
- ts-morph 공식 문서, "Getting Started" / "Manipulation"
- TypeScript 공식 Wiki, "Architectural Overview" (Scanner/Parser/Binder/Checker)
- Microsoft, "TypeScript Compiler Internals" 발표 자료
