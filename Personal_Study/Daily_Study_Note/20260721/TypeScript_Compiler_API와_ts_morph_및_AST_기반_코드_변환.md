Notion 원본: https://app.notion.com/p/3a35a06fd6d381899418e79d8bc7fc96

# TypeScript Compiler API와 ts-morph 및 AST 기반 코드 변환

> 2026-07-21 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript 컴파일러의 파이프라인과 Program·TypeChecker·SourceFile 관계를 설명한다
- Compiler API 로 AST 를 순회하고 타입 정보를 질의하는 방법을 적용한다
- ts-morph 로 고수준 코드 변환(리팩터링·코드젠)을 구현한다
- Transformer 팩토리로 컴파일 파이프라인에 개입하는 원리와 한계를 이해한다

## 1. 컴파일러 파이프라인 개요

tsc 는 Scanner → Parser → Binder → Checker → Transformer → Emitter 단계를 거친다. Compiler API 는 이 내부 단계를 열어 정적 분석, 코드 변환, 코드 생성에 쓴다.

## 2. Program 과 TypeChecker

```typescript
import * as ts from "typescript";
const program = ts.createProgram(["src/index.ts"], {
	target: ts.ScriptTarget.ES2022,
	module: ts.ModuleKind.NodeNext,
	strict: true,
});
const checker = program.getTypeChecker();
for (const sourceFile of program.getSourceFiles()) {
	if (sourceFile.isDeclarationFile) {
		continue;
	}
	ts.forEachChild(sourceFile, (node) => visit(node, checker));
}
```

AST 는 구문, Symbol/Type 은 의미이며 그 다리가 checker 다.

## 3. AST 순회와 노드 판별

```typescript
function visit(node: ts.Node, checker: ts.TypeChecker): void {
	if (ts.isFunctionDeclaration(node) && node.name) {
		const signature = checker.getSignatureFromDeclaration(node);
		if (signature) {
			const returnType = checker.getReturnTypeOfSignature(signature);
			console.log(`${node.name.getText()} -> ${checker.typeToString(returnType)}`);
		}
	}
	ts.forEachChild(node, (child) => visit(child, checker));
}
```

## 4. 실전 분석 — 데코레이터 찾기

```typescript
const decorators = ts.getDecorators(node); // 5.0+ API
const hasInjectable = decorators?.some((d) => {
	const expr = ts.isCallExpression(d.expression) ? d.expression.expression : d.expression;
	return expr.getText() === "Injectable";
});
```

TypeScript 5.0 에서 `node.decorators` 는 deprecated 되고 `ts.getDecorators`/`ts.getModifiers` 로 분리되었다.

## 5. ts-morph — 고수준 래퍼

```typescript
import { Project } from "ts-morph";
const project = new Project({ tsConfigFilePath: "tsconfig.json" });
for (const sourceFile of project.getSourceFiles()) {
	for (const cls of sourceFile.getClasses()) {
		const publicMethods = cls.getMethods().filter((m) => m.getScope() === "public");
	}
}
```

`rename` 은 심볼을 추적해 모든 참조를 함께 바꾸므로 안전한 리팩터링 자동화의 기반이 된다.

## 6. 코드 생성

```typescript
const file = project.createSourceFile("generated/User.ts", "", { overwrite: true });
file.addInterface({
	name: "User",
	isExported: true,
	properties: [{ name: "id", type: "number" }, { name: "name", type: "string" }],
});
project.saveSync();
```

OpenAPI·GraphQL·DB 메타데이터에서 타입·DTO를 뽑아내는 코드젠이 이 패턴을 따른다.

## 7. Transformer 팩토리

```typescript
const logStmt = ts.factory.createExpressionStatement(
	ts.factory.createCallExpression(
		ts.factory.createPropertyAccessExpression(
			ts.factory.createIdentifier("console"), "log"),
		undefined,
		[ts.factory.createStringLiteral(`enter ${node.name?.getText()}`)]));
```

tsc 는 공식적으로 커스텀 트랜스포머를 CLI 로 받지 않으므로 ts-patch 나 ts-loader 로 주입하고, AST 는 불변으로 다뤄 factory.updateXxx 로 교체한다.

## 8. 성능·유지보수 트레이드오프

읽기 전용 분석은 원시 Compiler API, 편집·리팩터링·코드젠은 ts-morph, 산출물 변조는 Transformer 를 쓴다. 큰 프로젝트 createProgram 은 수 초~수십 초가 걸리고, TS 버전 간 API 가 자주 바뀌므로 peerDependency 고정과 회귀 테스트가 필요하다. AST 자동화는 강력한 지렉대지만 버전 취약성과 유지보수 비용을 함께 고려해야 한다.

## 참고

- TypeScript Wiki: Using the Compiler API
- ts-morph 공식 문서 (ts-morph.com)
- TypeScript 5.0 릴리스 노트: Decorators, getDecorators/getModifiers
- ts-patch / ttypescript 프로젝트 문서
