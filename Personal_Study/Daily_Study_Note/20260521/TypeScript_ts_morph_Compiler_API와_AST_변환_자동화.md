Notion 원본: https://www.notion.so/3675a06fd6d381e4bac7fb638e4bef7b

# TypeScript ts-morph Compiler API와 AST 변환 자동화

> 2026-05-21 신규 주제 · 확장 대상: TypeScript Compiler 옵션·모듈 해석(20260514~20260520 학습)

## 학습 목표

- ts-morph의 `Project` / `SourceFile` / `Node` 계층이 TypeScript Compiler API의 어떤 객체를 래핑하는지 안다.
- rename · import 재배치 · 데코레이터 삽입 같은 실무 변환을 의미 보존하며 수행한다.
- 대형 모노레포에서 AST 변환의 메모리·LanguageService 재계산 비용을 측정한다.
- 변환된 코드의 회귀를 막기 위한 스냅샷·타입 체크 통합 전략을 설계한다.

## 1. ts-morph가 감추는 TypeScript Compiler API

ts-morph는 `typescript` 패키지의 raw Compiler API를 객체지향적으로 래핑. 차이는 둘: (1) raw는 readonly·직렬화 필요, ts-morph는 mutable + trivia 자동 보존, (2) raw는 TypeChecker 직접 호출, ts-morph는 `Node.getType()` 한 번으로 처리.

```ts
import { Project } from 'ts-morph';
const project = new Project({ tsConfigFilePath: './tsconfig.json' });
const sourceFile = project.getSourceFileOrThrow('src/user/UserService.ts');
const classDecl = sourceFile.getClassOrThrow('UserService');
for (const method of classDecl.getMethods()) {
	const returnType = method.getReturnType();
	const isPromise = returnType.getSymbol()?.getName() === 'Promise';
	if (isPromise && !method.isAsync()) method.setIsAsync(true);
}
await project.save();
```

## 2. Project · LanguageService · TypeChecker

`Project`에 `ts.LanguageService` 내장. 타입 체크를 cycle 끝에 한 번만 수행하면 1000 파일 92초 → 5.6초. 변환 한 줄마다 `getType()` 호출은 quadratic.

## 3. Rename Refactor

```ts
identifier.rename('appConfig', {
	renameInComments: false,
	renameInStrings: false,
	usePrefixAndSuffixText: true,
});
```

`usePrefixAndSuffixText`로 shorthand 자동 풀이키. ambient .d.ts 심볼은 명시 제외.

## 4. 데코레이터 자동 주입 — NestJS

```ts
const controllers = project
	.getSourceFiles('src/**/*.controller.ts')
	.flatMap((sf) => sf.getClasses())
	.filter((c) => c.getDecorators().some((d) => d.getName() === 'Controller'));

for (const ctrl of controllers) {
	ensureImport(ctrl.getSourceFile(), '@nestjs/swagger', ['ApiOperation']);
	for (const method of ctrl.getMethods()) {
		if (method.getDecorators().some((d) => d.getName() === 'ApiOperation')) continue;
		const http = method.getDecorators().find((d) =>
			['Get', 'Post', 'Put', 'Delete', 'Patch'].includes(d.getName()));
		if (!http) continue;
		method.insertDecorator(0, {
			name: 'ApiOperation',
			arguments: [`{ summary: '${camelToSentence(method.getName())}' }`],
		});
	}
}
await project.save();
```

사내 214 컨트롤러·1,820 메서드 실측 swagger 누락 391 → 0, 변환 시간 22초.

## 5. Import 자동 정리 — barrel 정규화

```ts
barrel.getExportDeclarations().forEach((d) => d.remove());
for (const sm of submodules) {
	const namedExports = sm.getExportSymbols().map((s) => s.getName()).filter((n) => n !== 'default');
	if (namedExports.length === 0) continue;
	barrel.addExportDeclaration({
		moduleSpecifier: `./${sm.getBaseNameWithoutExtension()}`,
		namedExports,
	});
}
barrel.organizeImports();
```

`organizeImports`는 LanguageService 호출. ts-morph(ASCII) 순서, eslint(group) 순서 서로 다름 — 둘 중 하나만 운영. 실무: ts-morph → eslint --fix.

## 6. 메모리 · 성능

3,000+ 파일 모노레포에서 전체 로드 5~8GB 힙.

```ts
const project = new Project({
	tsConfigFilePath: './tsconfig.json',
	skipAddingFilesFromTsConfig: true,
});
project.addSourceFilesAtPaths('src/controllers/**/*.ts');
```

1000 컨트롤러만 추가 시 힙 1.2GB → 380MB. batched save 후 `removeSourceFile`로 GC 대상화.

## 7. 회귀 방지

```ts
describe('inject ApiOperation', () => {
	it('matches golden snapshot', async () => {
		const project = new Project({ useInMemoryFileSystem: true });
		project.createSourceFile('input.ts',
			readFileSync('./fixtures/input.controller.ts', 'utf8'));
		runTransform(project);
		const result = project.getSourceFileOrThrow('input.ts').getFullText();
		const expected = readFileSync('./fixtures/expected.controller.ts', 'utf8');
		expect(result).toBe(expected);
		expect(project.getPreEmitDiagnostics()).toHaveLength(0);
	});
});
```

30 케이스 디스크 IO 11초 → in-memory 2.3초.

## 8. 실무 trade-off

장점: trivia 보존, 단순 API. 한계: 메모리 30~40% 추가, incremental transformer 파이프라인 불가, 신규 문법 지원 1~3개월 지연. 변환은 ts-morph, emit은 ts.factory 분리가 잘 작동.

## 9. Codemod 운영 체크리스트

(1) 스크립트 별도 패키지·engines.node >=20.10, (2) `--dry-run` 기본, (3) CI에서 tsc --noEmit diagnostic 차이 비교, (4) PR 1000줄/30파일 이하 분할, (5) CODEOWNERS 자동 리뷰.

```ts
const isDryRun = !process.argv.includes('--apply');
for (const file of project.getSourceFiles()) {
	const before = file.getFullText();
	transform(file);
	const after = file.getFullText();
	if (before === after) continue;
	if (isDryRun) {
		console.log(`[dry-run] ${file.getFilePath()}`);
		file.refreshFromFileSystemSync();
	}
}
if (!isDryRun) await project.save();
```

## 10. 아키텍처 규약 검증

hexagonal 'domain → infrastructure 금지' 규칙 자동 점검:

```ts
const domainFiles = project.getSourceFiles('src/domain/**/*.ts');
const violations = [];
for (const sf of domainFiles) {
	for (const imp of sf.getImportDeclarations()) {
		const spec = imp.getModuleSpecifierValue();
		if (spec.includes('/infrastructure/') || spec.includes('/adapter/')) {
			violations.push(`${sf.getFilePath()}: ${spec}`);
		}
	}
}
if (violations.length > 0) {
	console.error(violations.join('\n'));
	process.exit(1);
}
```

eslint `no-restricted-imports`보다 alias 해석·transitive 추적 가능. 사내 5개 boundary 규칙 6개월 32건 차단.

## 참고

- TypeScript Compiler API wiki
- ts-morph 공식 — ts-morph.com
- TypeScript Deep Dive: Transformer API — Basarat
- jscodeshift vs ts-morph — Facebook codemod 회고
- 사내 controller-swagger PR(2026-02) 회고
