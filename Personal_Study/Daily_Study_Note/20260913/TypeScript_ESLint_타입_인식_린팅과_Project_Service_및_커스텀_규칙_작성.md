Notion 원본: https://app.notion.com/p/3da5a06fd6d381558efaf0a6b90a0499?pvs=204

# TypeScript ESLint 타입 인식 린팅과 Project Service 및 커스텀 규칙 작성

> 2026-09-13 신규 주제 · 확장 대상: TypeScript 컴파일러 API / tsserver 아키텍처

## 학습 목표

- 타입 인식 린팅이 구문 기반 린팅과 다른 지점을 컴파일러 동작으로 설명한다
- `projectService` 가 `project` 배열 방식의 어떤 문제를 해결하는지 규명한다
- 타입 정보를 사용하는 커스텀 규칙을 TypeChecker API 로 구현한다
- 대형 저장소에서 린트 실행 시간을 측정하고 줄인다

## 1. 두 가지 린팅 층위

ESLint 의 기본 동작은 AST 순회다. `espree` 가 만든 AST 를 규칙들이 방문하며 패턴을 찾는다. typescript-eslint 는 파서를 `@typescript-eslint/parser` 로 교체해 TypeScript 구문까지 AST 로 만든다. 여기까지는 여전히 **구문 기반**이다.

구문 기반으로 잡을 수 있는 것과 없는 것의 경계는 명확하다.

```typescript
// 구문만으로 판단 가능
if (x = 1) { }              // no-cond-assign
const a = () => { return; } // 도달 불가 코드

// 타입 정보 없이는 불가능
foo();                      // foo 가 Promise 를 반환하는가? (no-floating-promises)
arr.map(cb);                // cb 가 async 인가? (no-misused-promises)
`${value}`;                 // value 가 object 라서 [object Object] 가 되는가?
a === b;                    // 두 유니언 타입에 교집합이 있는가?
```

두 번째 묶음이 실무에서 훨씬 비싼 버그를 만든다. `no-floating-promises` 하나만으로도 "await 을 빠뜨려 트랜잭션이 커밋 전에 응답이 나가는" 종류의 사고가 상당히 걸러진다. Spring 에서 `@Transactional` 메서드의 프록시 자기 호출을 정적으로 잡는 것과 비슷한 위치의 도구다.

타입 인식 규칙이 동작하려면 파서가 **TypeScript Program 을 만들어 TypeChecker 를 규칙에 넘겨야** 한다. 이것이 비용의 원천이다.

## 2. `project` 방식의 구조적 문제

flat config 이전의 전통적 설정은 이랬다.

```javascript
// .eslintrc.js
module.exports = {
	parser: '@typescript-eslint/parser',
	parserOptions: {
		project: ['./tsconfig.json', './packages/*/tsconfig.json'],
		tsconfigRootDir: __dirname,
	},
};
```

파서는 각 tsconfig 마다 `ts.Program` 을 만들고 캐시한다. 파일이 들어오면 어느 Program 에 속하는지 찾아 그 checker 를 쓴다. 문제는 세 가지다.

**첫째, "파일이 어느 프로젝트에도 속하지 않음" 오류.** `tsconfig.json` 의 `include` 에 없는 파일 — 설정 파일, 스크립트, 새로 만든 `*.test-d.ts` — 을 린트하면 파서가 터진다.

```
Parsing error: ESLint was configured to run on `<tsconfigRootDir>/scripts/build.ts`
using `parserOptions.project`. However, that TSConfig does not include this file.
```

이걸 피하려고 `tsconfig.eslint.json` 을 따로 만들어 `include: ["**/*"]` 를 넣는 관행이 생겼다. 그러면 이번엔 Program 이 저장소 전체 파일을 잡아 메모리와 시간이 치솟는다.

**둘째, 메모리 중복.** tsconfig 가 N 개면 Program 이 N 개다. 모노레포에서 패키지가 공유 의존성을 참조하면 같은 `.d.ts` 가 여러 Program 에 중복 적재된다. 패키지 20개짜리 저장소에서 ESLint 가 6GB 를 쓰는 사례가 드물지 않았다.

**셋째, watch 모드 비효율.** `--fix` 나 IDE 연동에서 파일이 바뀔 때마다 Program 을 갱신해야 하는데, 이 경로가 tsserver 만큼 최적화되어 있지 않았다.

## 3. Project Service — tsserver 재사용

typescript-eslint v8 에서 안정화된 `projectService` 는 접근 자체를 바꿨다. Program 을 직접 만드는 대신 **TypeScript 의 ProjectService** — VS Code 의 tsserver 가 쓰는 바로 그 컴포넌트 — 를 사용한다.

```javascript
// eslint.config.js (flat config)
import tseslint from 'typescript-eslint';

export default tseslint.config(
	...tseslint.configs.strictTypeChecked,
	{
		languageOptions: {
			parserOptions: {
				projectService: true,
				tsconfigRootDir: import.meta.dirname,
			},
		},
	},
);
```

동작 차이가 핵심이다. ProjectService 는 파일 경로를 받으면 **상위 디렉터리를 거슬러 올라가 가장 가까운 tsconfig 를 찾아** 그 프로젝트에 파일을 편입시킨다. IDE 에서 아무 `.ts` 파일이나 열어도 타입이 나오는 것과 같은 메커니즘이다.

| 항목 | `project` | `projectService` |
|---|---|---|
| 미포함 파일 | 파싱 에러 | 기본 프로젝트로 폴백 |
| 프로젝트 탐색 | 설정된 배열 전수 검사 | 디렉터리 상향 탐색 |
| 메모리 | tsconfig 당 Program | 공유 DocumentRegistry |
| 증분 갱신 | Program 재생성 | tsserver 증분 경로 |
| `tsconfig.eslint.json` | 사실상 필수 | 불필요 |

미포함 파일에 대한 폴백은 조절할 수 있다.

```javascript
parserOptions: {
	projectService: {
		allowDefaultProject: ['*.js', 'scripts/*.ts'],
		defaultProject: './tsconfig.json',
	},
}
```

`allowDefaultProject` 는 glob 이지만 **상대적으로 적은 수의 파일만** 넣어야 한다. 여기 들어간 파일은 "inferred project" 로 들어가는데, 파일 수가 많아지면 경고가 나오고 성능이 떨어진다. 수십 개를 넣어야 한다면 그건 tsconfig 의 `include` 를 고칠 신호다.

`DocumentRegistry` 공유가 메모리 개선의 핵심이다. 여러 프로젝트가 같은 `node_modules/@types/node/index.d.ts` 를 참조하면 SourceFile 인스턴스 하나를 나눠 쓴다. 앞서 언급한 6GB 사례가 1~2GB 대로 떨어지는 것이 이 때문이다.

## 4. 타입을 쓰는 커스텀 규칙 만들기

사내 규약을 강제할 때 타입 정보가 필요한 경우가 많다. 작은 예로, "`Date` 를 반환하는 함수는 이름이 `...At` 으로 끝나야 한다"는 규칙을 만들어 보자.

```typescript
import { ESLintUtils, TSESTree } from '@typescript-eslint/utils';
import * as ts from 'typescript';

const createRule = ESLintUtils.RuleCreator(
	(name) => `https://internal.docs/eslint/${name}`,
);

export const dateReturnNaming = createRule({
	name: 'date-return-naming',
	meta: {
		type: 'suggestion',
		docs: { description: 'Date 반환 함수는 At 접미사를 쓴다' },
		messages: {
			missingSuffix: "'{{name}}' 은 Date 를 반환하므로 'At' 으로 끝나야 한다",
		},
		schema: [],
	},
	defaultOptions: [],
	create(context) {
		const services = ESLintUtils.getParserServices(context);
		const checker = services.program.getTypeChecker();

		function check(node: TSESTree.FunctionDeclaration | TSESTree.MethodDefinition) {
			const id = node.type === 'MethodDefinition' ? node.key : node.id;
			if (!id || id.type !== 'Identifier') {
				return;
			}

			const tsNode = services.esTreeNodeToTSNodeMap.get(node);
			const signature = checker.getSignatureFromDeclaration(
				tsNode as ts.SignatureDeclaration,
			);
			if (!signature) {
				return;
			}

			const returnType = checker.getReturnTypeOfSignature(signature);
			if (!isDateType(checker, returnType)) {
				return;
			}

			if (!id.name.endsWith('At')) {
				context.report({
					node: id,
					messageId: 'missingSuffix',
					data: { name: id.name },
				});
			}
		}

		return {
			FunctionDeclaration: check,
			MethodDefinition: check,
		};
	},
});

function isDateType(checker: ts.TypeChecker, type: ts.Type): boolean {
	const symbol = type.getSymbol();
	return symbol?.getName() === 'Date';
}
```

핵심은 세 줄이다.

1. `ESLintUtils.getParserServices(context)` — 타입 정보가 없으면 여기서 예외가 난다. 타입 인식 규칙임을 선언하는 지점이다.
2. `services.esTreeNodeToTSNodeMap.get(node)` — ESLint 의 ESTree 노드를 TypeScript 의 AST 노드로 변환한다. 반대 방향은 `tsNodeToESTreeNodeMap` 이다.
3. `checker.getReturnTypeOfSignature(...)` — 여기서부터는 순수 TypeScript Compiler API 다.

`isDateType` 은 의도적으로 단순하게 눴는데, 실전에서는 `Promise<Date>`, `Date | null`, 브랜디드 타입까지 다뤄야 하므로 `@typescript-eslint/type-utils` 의 헬퍼를 쓰는 편이 낫다.

```typescript
import { isTypeFlagSet, unionTypeParts } from '@typescript-eslint/type-utils';

const parts = unionTypeParts(returnType);
const hasDate = parts.some((p) => p.getSymbol()?.getName() === 'Date');
const onlyDateOrNullish = parts.every(
	(p) => p.getSymbol()?.getName() === 'Date'
		|| isTypeFlagSet(p, ts.TypeFlags.Null | ts.TypeFlags.Undefined),
);
```

유니언을 분해해서 다루는 것은 타입 인식 규칙의 기본기다. TypeScript 에서 대부분의 타입은 유니언일 수 있고, 유니언을 통로 비교하면 거의 항상 틀린다.

## 5. 규칙 테스트

`RuleTester` 에 타입 정보를 붙이려면 픽스처 프로젝트가 필요하다.

```typescript
import { RuleTester } from '@typescript-eslint/rule-tester';
import { dateReturnNaming } from '../src/rules/date-return-naming';

const ruleTester = new RuleTester({
	languageOptions: {
		parserOptions: {
			projectService: {
				allowDefaultProject: ['*.ts*'],
			},
			tsconfigRootDir: __dirname + '/fixture',
		},
	},
});

ruleTester.run('date-return-naming', dateReturnNaming, {
	valid: [
		'function createdAt(): Date { return new Date(); }',
		'function name(): string { return ""; }',
		'function maybeAt(): Date | null { return null; }',
	],
	invalid: [
		{
			code: 'function created(): Date { return new Date(); }',
			errors: [{ messageId: 'missingSuffix', data: { name: 'created' } }],
		},
	],
});
```

`valid` 의 세 번째 케이스가 중요하다. 유니언 처리를 빠뜨린 구현은 이걸 통과시키지 못하거나 반대로 잘못 통과시킨다. 규칙 테스트에서는 **경계 타입** — 유니언, `any`, `unknown`, 제네릭 미해결 — 을 의도적으로 배치해야 한다.

## 6. 성능 측정과 통제

타입 인식 린팅은 tsc 를 한 번 도는 것과 비슷하거나 그 이상 걸린다. 측정부터 한다.

```bash
TIMING=1 eslint . --format json > /dev/null
```

`TIMING=1` 은 규칙별 소요 시간 상위 항목을 출력한다. 전형적인 결과는 이렇다.

| Rule | Time (ms) | Relative |
|---|---|---|
| @typescript-eslint/no-misused-promises | 4,182 | 31.2% |
| @typescript-eslint/no-floating-promises | 2,940 | 21.9% |
| @typescript-eslint/no-unnecessary-condition | 2,103 | 15.7% |
| @typescript-eslint/restrict-template-expressions | 981 | 7.3% |

상위 세 규칙이 70% 를 차지하는 것이 흔한 패턴이다. 이들은 모두 **모든 표현식의 타입을 조회**하기 때문이다. 특히 `no-unnecessary-condition` 은 조건문마다 타입 좁히기 결과를 확인하므로 비싸다.

통제 수단은 네 가지다.

**규칙 범위 분리.** 타입 인식 규칙을 `src/**` 에만 적용하고 테스트·스크립트에는 구문 규칙만 적용한다.

```javascript
export default tseslint.config(
	{ files: ['**/*.ts'], extends: [tseslint.configs.recommended] },
	{
		files: ['src/**/*.ts'],
		extends: [tseslint.configs.recommendedTypeChecked],
		languageOptions: { parserOptions: { projectService: true } },
	},
	{
		files: ['**/*.test.ts', 'scripts/**'],
		extends: [tseslint.configs.disableTypeChecked],
	},
);
```

`disableTypeChecked` 는 타입 인식 규칙을 일괄 off 로 만드는 준비된 설정이다. 개별 규칙을 나열하는 것보다 유지보수가 쉽다.

**캐시.** `eslint --cache --cache-location .cache/eslint` 는 변경되지 않은 파일을 건너뛴다. 단, 타입 인식 규칙은 **의존하는 파일이 바뀌면 결과가 달라지는데** ESLint 캐시는 그 파일 자체의 해시만 본다. CI 전체 실행에서는 캐시를 쓰지 않고, 로컬/pre-commit 에서만 쓰는 절충이 안전하다.

**CI 에서는 변경 파일만.** pre-commit 에서 `lint-staged` 로 스테이징된 파일만 린트하면 체감 시간이 수초로 줄어든다. 전체 검사는 CI 의 별도 잡으로 병렬 실행한다.

**tsconfig 슬림화.** `include` 가 넓으면 Program 이 크고, Program 이 크면 checker 의 모든 조회가 느려진다. `projectService` 로 옮기면 이 문제 자체가 대부분 사라진다.

## 7. flat config 로의 이전 시 주의점

ESLint v9 의 flat config 는 `extends` 가 배열 스프레드로 바뀌고, `env`/`parserOptions` 가 `languageOptions` 아래로 들어간다.

```javascript
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
	eslint.configs.recommended,
	...tseslint.configs.strictTypeChecked,
	...tseslint.configs.stylisticTypeChecked,
	{
		languageOptions: {
			parserOptions: {
				projectService: true,
				tsconfigRootDir: import.meta.dirname,
			},
		},
		rules: {
			'@typescript-eslint/no-unnecessary-condition': [
				'error',
				{ allowConstantLoopConditions: true },
			],
		},
	},
	{ ignores: ['dist/**', 'coverage/**'] },
);
```

혼동하기 쉬운 지점 셋.

`ignores` 는 **객체 하나에 단독으로** 들어가야 전역 무시가 된다. 다른 키와 같이 쓰면 그 설정 객체에만 적용되는 파일 필터가 된다.

`.eslintignore` 는 flat config 에서 더 이상 읽히지 않는다. 이전 시 반드시 `ignores` 로 옮겨야 하며, 빠뜨리면 `dist` 를 린트하다 시간이 폭증한다.

설정 순서가 의미를 갖는다. 뒤에 오는 객체가 앞의 규칙을 덮어쓴다. `disableTypeChecked` 는 반드시 타입 인식 설정 **뒤에** 와야 한다.

## 8. 어디까지 강제할 것인가

`strictTypeChecked` 를 기존 저장소에 그대로 켜면 수천 건의 에러가 나온다. 그중 상당수는 `any` 가 섞인 레거시 경계에서 발생하며, 고치려면 타입을 새로 설계해야 한다.

현실적인 도입 순서는 이렇다. 먼저 `recommendedTypeChecked` 만 켜고 `no-floating-promises`, `no-misused-promises`, `await-thenable` 세 개를 error 로 고정한다. 이 셋이 실제 런타임 버그와 직결된다. 나머지는 warn 으로 두고 신규 코드에서만 error 가 되도록 `--max-warnings` 를 점진적으로 낮추는다.

`no-unnecessary-condition` 은 도입 판단이 갈린다. 논리적으로는 "타입상 항상 참인 조건"을 잡아 주지만, 외부 JSON 을 `as` 로 단언해 들여온 값에 대한 방어 코드까지 불필요하다고 지적한다. 타입 단언이 많은 코드베이스에서는 거짓 양성이 진짜 신호를 덮는다. 런타임 검증(Zod 등)으로 경계를 정리한 뒤에 켜는 것이 순서상 맞다.

## 참고

- typescript-eslint — Typed Linting (https://typescript-eslint.io/getting-started/typed-linting/)
- typescript-eslint — Project Service (https://typescript-eslint.io/packages/parser/#projectservice)
- typescript-eslint — Custom Rules (https://typescript-eslint.io/developers/custom-rules/)
- TypeScript Compiler API Wiki (https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
- ESLint — Configuration Files (flat config) (https://eslint.org/docs/latest/use/configure/configuration-files)
