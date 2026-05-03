Notion 원본: https://www.notion.so/3545a06fd6d381d3861ceac23e9a3ae2

# TypeScript Compiler API와 ts-morph 기반 코드 변환 자동화

> 2026-05-02 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript Compiler API의 SourceFile, Program, TypeChecker 역할을 구분한다
- AST node 의 SyntaxKind 를 이용해 타입 단언, import, JSX 요소를 정확히 식별한다
- ts-morph 의 high-level API 로 대규모 codemod 를 안전하게 작성한다
- Transformer factory 와 emit 파이프라인을 직접 호출해 .d.ts 를 가공한다

## 1. 왜 codemod 인가 — 변경의 원자성과 검증

대규모 모노레포에서 라이브러리 v1 → v2 마이그레이션, deprecated API 제거, import 경로 일괄 정정은 grep + sed 로는 안전성을 담보하기 어렵다. 동일 식별자라도 scope 가 다르면 의미가 다르고, JSX 안의 식별자, type-only import, 재내보내기(re-export) 까지 흩어져 있다. AST 기반 변환은 컴파일러가 인식하는 의미 단위로 정확히 한정된 변경을 보장하며, 실패 시 원자적 롤백이 가능하다. Facebook jscodeshift, Vercel codemod, Sentry 의 migration scripts, NestJS schematics 가 모두 이 방식이다.

## 2. Compiler API 핵심 객체 4 가지

`typescript` 패키지가 노출하는 API 중 codemod 에서 쓰는 핵심은 4개다.

| 객체 | 역할 | 흔한 호출 |
| --- | --- | --- |
| `Program` | tsconfig 기반 루트 프로젝트 단위, 의미 분석 컨텍스트 보유 | `ts.createProgram(rootNames, options)` |
| `SourceFile` | 한 파일의 AST 루트 | `program.getSourceFile(path)` |
| `TypeChecker` | 심볼·타입 해석 엔진 | `program.getTypeChecker()` |
| `Printer` | 변환된 AST 를 텍스트로 직렬화 | `ts.createPrinter()` |

Program 은 모든 SourceFile 의 의존 그래프를 함께 들고 있어, 타입 정보가 필요한 변환에 필수다. SourceFile 만으로는 식별자가 어떤 모듈에서 왔는지를 알 수 없다.

```ts
import * as ts from 'typescript'

const program = ts.createProgram(['src/index.ts'], {
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.NodeNext,
  moduleResolution: ts.ModuleResolutionKind.NodeNext,
  strict: true
})
const checker = program.getTypeChecker()
const sf = program.getSourceFile('src/index.ts')!

ts.forEachChild(sf, function visit(node) {
  if (ts.isCallExpression(node)) {
    const sym = checker.getSymbolAtLocation(node.expression)
    console.log(sym?.getName())
  }
  ts.forEachChild(node, visit)
})
```

`forEachChild` 와 `ts.visitEachChild` 의 차이는 전자가 단순 순회이고 후자는 transformation 컨텍스트에서 새 노드 트리를 빌드한다는 점이다. 변환에는 후자를 써야 한다.

## 3. SyntaxKind 기반 노드 식별 — type guard 패턴

TS 의 `ts.is*` 함수는 SyntaxKind 와 인터페이스를 동시에 좁혀 주는 type guard 다. 자주 쓰이는 가드는 다음과 같다.

| Guard | 예시 | 좁혀지는 타입 |
| --- | --- | --- |
| `ts.isImportDeclaration` | `import x from 'y'` | `ImportDeclaration` |
| `ts.isCallExpression` | `foo()` | `CallExpression` |
| `ts.isAsExpression` / `ts.isTypeAssertionExpression` | `x as T`, `<T>x` | 각 단언 노드 |
| `ts.isJsxElement` / `ts.isJsxSelfClosingElement` | `<Foo/>` | JSX 노드 |
| `ts.isPropertyAccessExpression` | `a.b.c` | `PropertyAccessExpression` |

여러 변환 도구가 type assertion 을 일괄로 잡아낼 때 `isAsExpression` 만 보고 끝내는 실수를 한다. legacy `<T>x` 문법(`TypeAssertionExpression`)도 함께 처리해야 미스가 안 생긴다.

## 4. ts-morph — 고수준 wrapper 의 가독성 이득

ts-morph 는 Compiler API 를 OOP 스타일로 감싼 라이브러리로, file·class·method·import 단위의 자주 쓰는 변환을 한 줄로 표현한다. 동일한 동작을 vanilla 로 작성하면 5~10 배 길어진다.

```ts
import { Project } from 'ts-morph'

const project = new Project({ tsConfigFilePath: 'tsconfig.json' })

for (const sf of project.getSourceFiles('src/**/*.ts')) {
  for (const id of sf.getDescendantsOfKind(ts.SyntaxKind.Identifier)) {
    if (id.getText() === 'oldName') id.replaceWithText('newName')
  }
  // import path 변경
  const imp = sf.getImportDeclaration('legacy-pkg')
  if (imp) imp.setModuleSpecifier('@scope/legacy-pkg')
}

await project.save()
```

내부적으로 ts-morph 는 변경된 텍스트를 LSP-style edit 으로 모아 두었다가 `save()` 시점에 파일 단위로 한 번에 쓰기 때문에 동시성 문제가 없다. 단, 한 SourceFile 안에서 다수의 변환을 연속 적용할 때는 텍스트 위치가 즉시 갱신되므로 forEach 와 미리 모아둔 노드 리스트가 stale 해질 수 있다 — 변환은 traverse 한 뒤 원자적으로 일괄 적용한다.

## 5. 실전 codemod — `as any` 제거와 Optional Chaining 도입

다음 두 변환을 동일 프로젝트에 적용하는 예다. 하나는 의미를 바꾸는 변환(`as any` → 정확한 타입 추론으로 대체), 다른 하나는 의미가 동등한 형태로 표현만 바꾸는 변환(`a && a.b` → `a?.b`).

```ts
import { Project, SyntaxKind } from 'ts-morph'

const project = new Project({ tsConfigFilePath: 'tsconfig.json' })

for (const sf of project.getSourceFiles('src/**/*.ts')) {
  // 1) as any 표시
  for (const expr of sf.getDescendantsOfKind(SyntaxKind.AsExpression)) {
    if (expr.getTypeNode()?.getText() === 'any') {
      expr.replaceWithText(`/* TODO(any): */ ${expr.getExpression().getText()}`)
    }
  }
  // 2) `a && a.b` → `a?.b`
  for (const bin of sf.getDescendantsOfKind(SyntaxKind.BinaryExpression)) {
    if (bin.getOperatorToken().getText() !== '&&') continue
    const left = bin.getLeft().getText()
    const right = bin.getRight()
    if (right.getKind() === SyntaxKind.PropertyAccessExpression) {
      const obj = (right as any).getExpression().getText()
      if (obj === left) {
        bin.replaceWithText(`${left}?.${(right as any).getName()}`)
      }
    }
  }
}

await project.save()
```

이런 패턴 변환은 short-circuit semantics 가 동일한지 직접 단위 테스트로 검증해야 한다. `0 && 0.toString()` 처럼 falsy primitive 는 의미가 미묘하게 갈린다.

## 6. Transformer Factory 와 emit 파이프라인

build 시점에 .d.ts 또는 .js 결과물을 가공해야 하면 transformer factory 를 사용한다. `ts-patch` 또는 `tsx` 의 transformer hook 으로 등록하며, decorator emit, path alias rewriting 같은 공식 옵션이 부족한 영역에서 흔히 쓴다.

```ts
import * as ts from 'typescript'

export default function transformer(_program: ts.Program): ts.TransformerFactory<ts.SourceFile> {
  return ctx => sf => {
    const visit: ts.Visitor = node => {
      if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
        const mod = node.moduleSpecifier.text
        if (mod.startsWith('@app/')) {
          return ts.factory.updateImportDeclaration(
            node,
            node.modifiers,
            node.importClause,
            ts.factory.createStringLiteral(mod.replace('@app/', '../')),
            node.assertClause
          )
        }
      }
      return ts.visitEachChild(node, visit, ctx)
    }
    return ts.visitNode(sf, visit) as ts.SourceFile
  }
}
```

Transformer 는 emit 단계에서만 적용되므로 IDE 자동완성에는 영향이 없다. 따라서 emitted 결과만 변경하고 type 정보는 유지하는 데 적합하다.

## 7. 성능 — Program 재생성 비용과 incremental 모드

대규모 monorepo 에서 codemod 를 매 PR 에 적용하기 위해서는 Program 생성 비용을 줄여야 한다. 4,000 파일 기준 풀 Program 생성은 5~12 초 소요되는 반면 `createIncrementalProgram` 으로 build info 를 재사용하면 0.6~1.5 초로 떨어진다. 단 incremental 모드는 transformer 적용 시 cache 무효화 규칙이 까다로워, type 변경이 있는 변환에는 권장되지 않는다.

ts-morph 는 자체적으로 file watcher 가 없으므로 nodemon 또는 chokidar 로 감싸 변경 파일만 다시 파싱하는 경로가 일반적이다. `Project.addSourceFilesAtPaths` 와 `removeSourceFile` 을 명시적으로 호출해 SourceFile lifecycle 을 관리한다.

## 8. 검증 전략 — golden file 과 컴파일 통과

codemod 의 가장 신뢰 가능한 검증은 (a) before/after 를 묶은 golden test 와 (b) 변환 후 `tsc --noEmit` 가 통과하는지 확인이다. golden test 는 `__fixtures__/in/*.ts` 와 `__fixtures__/out/*.ts` 를 두고 변환 결과를 diff 로 비교한다. AST diff 는 print formatting 이 살짝 달라지면 깨지므로 `prettier` 를 양쪽에 똑같이 한 번씩 통과시킨 뒤 비교한다.

```ts
import * as fs from 'fs'
import { format } from 'prettier'
import { transform } from './codemod'

test('removes any-asserts', async () => {
  const input = fs.readFileSync('__fixtures__/in/case1.ts', 'utf8')
  const expected = fs.readFileSync('__fixtures__/out/case1.ts', 'utf8')
  const actual = await format(transform(input), { parser: 'typescript' })
  expect(actual).toBe(await format(expected, { parser: 'typescript' }))
})
```

이런 fixture 기반 테스트가 200 케이스를 넘어가면, 변환 alias 마다 case folder 를 구분하고 lint 처럼 PR 에 자동 add 하는 방식이 안정적이다.

## 9. 한계와 대안

Compiler API 가 제공하지 않는 일부는 직접 다뤄야 한다 — 예를 들어 trivia(주석, 공백) 보존이 어렵다. ts-morph 는 leading trivia 보존을 신경 쓰지만, 노드를 통째로 교체하는 경우 detached comment 가 사라진다. 가능하면 텍스트 단위 replace (`replaceWithText`) 보다 노드 mutation 으로 처리하고, 주석 유지가 중요한 변환은 `ts.setSyntheticLeadingComments` 로 명시적으로 옮긴다.

JSX 의 attribute 순서 정합성, decorator emit 순서 변화 같은 경우는 spec 보다 구현 detail 의 영향이 크므로, 변환 후 svelte/Astro 같은 외부 transformer 와 함께 쓸 때는 회귀 테스트가 필수다.

## 참고

- TypeScript Compiler API Wiki <https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API>
- ts-morph 공식 문서 <https://ts-morph.com/>
- Vercel codemod CLI <https://github.com/vercel/codemod>
- jscodeshift README — recipe 모음 <https://github.com/facebook/jscodeshift>
- TS performance tracing 가이드 <https://github.com/microsoft/TypeScript/wiki/Performance>
