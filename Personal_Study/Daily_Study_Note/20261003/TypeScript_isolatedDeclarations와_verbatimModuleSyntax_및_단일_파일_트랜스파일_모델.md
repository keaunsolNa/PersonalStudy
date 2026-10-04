Notion 원본: https://www.notion.so/3ef5a06fd6d3812b9c82da61d30fb096

# TypeScript isolatedDeclarations와 verbatimModuleSyntax 및 단일 파일 트랜스파일 모델

> 2026-10-03 신규 주제 · 확장 대상: TypeScript Project References와 Incremental Build, 듀얼 패키지 ESM 전환과 moduleResolution

## 학습 목표

- 파일 하나만 보고 JS와 `.d.ts`를 만드는 "단일 파일 트랜스파일 모델"이 tsc 전체 타입 체크와 어디서 갈라지는지 구분한다.
- `isolatedModules`, `verbatimModuleSyntax`, `isolatedDeclarations`, `erasableSyntaxOnly`의 책임 범위를 컴파일러 에러 코드로 확인한다.
- 모노레포에서 `.d.ts` 생성을 타입 체크와 분리해 빌드 시간을 줄이는 구성을 설계한다.
- esbuild·swc·oxc·Node 타입 스트리핑이 거부하는 문법을 사전에 린트 단계에서 막는다.

## 1. 왜 "단일 파일 모델"이 필요한가

tsc는 원래 프로그램 전체를 한 번에 읽고 타입을 계산한 뒤 JS를 내보낸다. 이 방식은 정확하지만 느리다. 파일 A가 `import { Role } from "./role"`을 할 때, 그 `Role`이 `enum`인지 `type`인지 알려면 role.ts를 열어봐야 한다. `type`이면 import 구문은 emit에서 지워져야 하고, `enum`이면 남아야 한다. 즉 **emit 결과가 다른 파일의 타입 정보에 의존한다.**

esbuild, swc, oxc, Babel 같은 도구는 파일을 병렬로, 서로 독립적으로 변환한다. 이들은 타입 체커가 없으므로 "이 import가 타입인가 값인가"를 알 수 없다. 그래서 TypeScript 팀은 "다른 파일을 보지 않아도 emit이 결정되도록 소스를 제한하는" 컴파일러 옵션을 단계적으로 추가했다. 이 노트가 다루는 네 가지 옵션은 모두 그 제한의 서로 다른 면이다.

| 옵션 | 제한하는 대상 | 도입 시점(대략) |
|---|---|---|
| isolatedModules | 파일 단독 변환 시 emit이 모호해지는 구문 | TS 1.5 |
| verbatimModuleSyntax | import/export의 보존·삭제 규칙을 소스에 명시 | TS 5.0 |
| isolatedDeclarations | 파일 단독으로 `.d.ts`를 생성할 수 있도록 export 타입 명시 | TS 5.5 |
| erasableSyntaxOnly | 타입 소거만으로 JS가 되지 않는 구문 금지 | TS 5.8 |

## 2. isolatedModules: 모호한 구문 차단

`isolatedModules`는 emit을 바꾸지 않는다. 단독 변환기가 잘못 처리할 수 있는 코드를 **에러로 표시**할 뿐이다. 대표적으로 타입만 재수출하는 구문이 그렇다.

```ts
// types.ts
export interface User { id: string }

// index.ts
export { User } from "./types";      // isolatedModules: 에러 — User가 타입인지 값인지 알 수 없다
export type { User } from "./types"; // OK — 소거 대상임이 명시됨
```

단독 변환기는 `export { User } from "./types"`를 만나면 `User`가 값일 수도 있으므로 보존한다. 런타임에 types.js에는 `User`가 없으므로 ESM에서는 링크 에러가 난다. 그래서 `export type`을 요구한다. `const enum`의 ambient 선언을 다른 파일에서 쓰는 경우도 같은 이유로 막힌다. 값이 인라이닝되려면 선언 파일을 읽어야 하기 때문이다.

한계도 분명하다. `isolatedModules`는 "import 구문 자체"는 건드리지 않는다. `import { User } from "./types"`처럼 타입만 가져오는 import는 허용되고, 변환기가 사용 여부를 보고 지울지 판단한다. 이 판단 규칙이 도구마다 달랐고(예: Babel은 사용되지 않은 import를 지우지만 부수효과 import는 남긴다), 그 불일치를 없애려고 다음 옵션이 나왔다.

## 3. verbatimModuleSyntax: 쓴 대로 남긴다

`verbatimModuleSyntax`의 규칙은 한 줄이다. **`type` 한정자가 붙은 import/export만 지워지고, 나머지는 쓴 그대로 출력된다.**

```ts
import { type User, makeId } from "./user.js"; // User만 소거, makeId 보존
import type { Config } from "./config.js";     // 구문 전체 소거
import { Logger } from "./logger.js";          // Logger가 타입이어도 구문이 보존된다 (부수효과 import로 남음)
```

세 번째 줄이 중요하다. `Logger`가 인터페이스뿐이라면 출력 JS에는 `import "./logger.js"` 형태가 남는다. 사용 여부를 추측하지 않기 때문에 모든 도구가 같은 결과를 낸다. 대신 타입 전용 import에 `type`을 빠뜨리면, 런타임에 존재하지 않는 export를 가져오다 실패할 수 있다. 이 실수는 `import type` 사용을 강제하는 ESLint 규칙(`@typescript-eslint/consistent-type-imports`)과 함께 쓰면 잡힌다.

이 옵션은 모듈 시스템과도 연결된다. 출력이 CommonJS인 파일에서 ESM 문법으로 export하면 에러가 난다. 실제로 확인한 메시지는 다음과 같다(TypeScript 5.9.3, package.json에 `"type": "module"`이 없는 상태).

```text
user.ts(1,1): error TS1287: A top-level 'export' modifier cannot be used on value declarations
in a CommonJS module when 'verbatimModuleSyntax' is enabled.
```

원인은 `module: NodeNext`에서 `.ts` 파일의 모듈 형식을 가장 가까운 package.json의 `type`으로 결정하기 때문이다. `"type": "module"`을 추가하면 해결된다. 즉 verbatimModuleSyntax는 "소스의 ESM 문법과 출력 형식이 일치"하도록 강제하며, CJS 출력이 필요하면 `import x = require("x")`와 `export =` 구문을 써야 한다.

`isolatedModules`와의 관계도 정리해 두자. `verbatimModuleSyntax`를 켜면 `isolatedModules`가 요구하던 검사의 상당 부분이 포함되지만, 두 옵션을 함께 두는 구성이 일반적이다. 공식 문서도 `importsNotUsedAsValues`, `preserveValueImports`를 이 옵션이 대체한다고 설명한다. 대체되는 두 옵션은 TS 5.0에서 deprecated 되었다.

## 4. isolatedDeclarations: 파일 단독 `.d.ts` 생성

JS 변환은 단독으로 가능해졌지만 `.d.ts` 생성은 여전히 타입 체커가 필요했다. 함수의 반환 타입이 생략되어 있으면 본문과 호출하는 다른 파일의 타입까지 추론해야 하기 때문이다. `isolatedDeclarations`(TS 5.5)는 **export되는 선언에 타입을 명시하도록 강제**해서, 추론 없이 `.d.ts`를 만들 수 있게 한다. 이 옵션은 `declaration` 또는 `composite`가 켜져 있어야 의미가 있다.

다음 파일로 직접 확인했다.

```ts
// user.ts — 통과
export function makeId(prefix: string, n: number): string {
	return `${prefix}-${n}`;
}

// bad.ts — 실패
import { makeId } from "./user.js";
export function twice(prefix: string) {      // TS9007: 반환 타입 명시 필요
	return makeId(prefix, 2);
}
export const id = makeId("a", 1);            // TS9010: 변수 타입 명시 필요
export const list = [1, 2].map((n) => n * 2); // TS9010
export const cfg = { a: 1, b: "x" };         // 통과 — 리터럴 객체는 추론 가능
```

마지막 줄이 보여주듯, 모든 export에 타입을 쓰라는 뜻은 아니다. `const x = 20`, 단순 리터럴 객체, 템플릿 리터럴이 반환되는 경우처럼 **구문만 보고 타입이 결정되는** 경우는 허용된다. 함수 호출 결과, 배열 메서드 결과, 조건식처럼 다른 심볼의 타입이 필요한 표현식이 막힌다.

효과는 두 가지다. 첫째, 병렬화다. 각 파일의 `.d.ts`를 독립적으로 생성할 수 있으므로 oxc, swc, esbuild 계열 도구가 `.d.ts`까지 낸다(예: `oxc-transform`의 isolatedDeclarations 지원). 둘째, 라이브러리 작성자에게 이득이다. 공개 API의 타입이 소스에 명시되어 실수로 내부 타입이 새어 나가는 일이 줄어든다. 비용은 소스의 장황함이다. 이를 덜기 위해 에디터의 "Add missing return type" 퀵 픽스를 쓴다.

### 클래스와 선언 병합에서의 제약

클래스 필드, 메서드 반환 타입, 파라미터 프로퍼티도 대상이다. 특히 `export default` 표현식과 `unique symbol`, 클래스 expression은 제약이 있어 마이그레이션 시 가장 많은 에러를 낸다. 기존 코드베이스에서 켜면 에러가 수백 건 나올 수 있으므로, 패키지 단위로 점진 적용하는 것이 현실적이다. `// @ts-expect-error`로 숨기기보다, `.d.ts` 산출을 목적으로 하는 패키지(공개 라이브러리)부터 켠다.

## 5. erasableSyntaxOnly와 Node 타입 스트리핑

Node.js는 `--experimental-strip-types`(v22.6, 이후 버전에서 기본 활성화)로 `.ts` 파일의 타입 주석을 공백으로 치환해 직접 실행한다. 이 방식은 **소스맵·변환 없이 타입 구문만 지우는** 것이므로, 지워서 JS가 되지 않는 구문은 실행할 수 없다. 대표적인 것이 `enum`, 런타임 코드를 가진 `namespace`, 생성자의 파라미터 프로퍼티(`constructor(private x: number)`), 그리고 `import x = require()`다.

TS 5.8의 `erasableSyntaxOnly`는 이런 구문을 컴파일 에러로 만든다.

```jsonc
// tsconfig.json
{
	"compilerOptions": {
		"target": "ES2022",
		"module": "NodeNext",
		"moduleResolution": "NodeNext",
		"verbatimModuleSyntax": true,
		"erasableSyntaxOnly": true,
		"rewriteRelativeImportExtensions": true, // TS 5.7: ./a.ts → ./a.js 로 emit
		"noEmit": true
	}
}
```

```ts
enum Level { Low, High }                // 에러: erasableSyntaxOnly
class A { constructor(private x: number) {} } // 에러: 파라미터 프로퍼티
const Level2 = { Low: 0, High: 1 } as const;  // 대안: as const 객체 + 유니온 타입
type Level2 = (typeof Level2)[keyof typeof Level2];
```

`enum`을 `as const` 객체로 바꾸는 패턴은 단순히 규칙을 맞추는 것 이상의 이점이 있다. 트리 셰이킹이 잘 되고, 단독 변환기와 타입 스트리핑에서 동일하게 동작한다. 단점은 reverse mapping이 없다는 것이다.

## 6. 모노레포 빌드 구성 설계

이 옵션들이 가장 크게 영향을 주는 곳은 Project References를 쓰는 모노레포다. 기존 구성은 `tsc -b`가 패키지 순서대로 타입 체크와 `.d.ts` 생성을 모두 수행했다. 의존 패키지의 `.d.ts`가 나와야 하위 패키지를 체크할 수 있었기 때문에 병렬화에 한계가 있었다.

`isolatedDeclarations`를 켜면 구성이 다음처럼 바뀔 수 있다.

1. 모든 패키지에서 변환기(esbuild/swc/oxc)가 JS와 `.d.ts`를 **동시에, 병렬로** 생성한다. 타입 체크가 필요 없다.
2. 타입 체크(`tsc --noEmit`)는 별도 CI 잡에서 돌린다. 빌드 산출물과 무관하다.
3. 개발 서버는 변환기만 사용하므로 저장 즉시 반영된다.

```jsonc
// 루트 package.json 스크립트 예시
{
	"scripts": {
		"build": "turbo run build",         // 각 패키지: 변환기로 JS + d.ts 생성
		"typecheck": "tsc -b --noEmit false --emitDeclarationOnly false"
	}
}
```

주의할 점은 "타입 체크 없이 빌드가 성공한다"는 사실이다. 변환기는 타입 에러가 있어도 JS를 내보낸다. 따라서 **typecheck 잡이 머지 필수 체크**로 걸려 있지 않으면 잘못된 코드가 배포될 수 있다. 이것이 이 구성의 가장 큰 trade-off이며, 속도를 얻는 대신 파이프라인 규율에 의존하게 된다.

또 하나는 `.d.ts` 품질이다. 변환기가 생성한 `.d.ts`와 tsc가 생성한 `.d.ts`는 이론상 동일해야 하지만, 도구별 버전 차이로 미세한 차이가 날 수 있다. 공개 라이브러리라면 릴리스 전에 tsc 산출물과 diff를 한 번 비교하는 검증 단계를 두는 편이 안전하다.

## 7. 마이그레이션 절차

기존 프로젝트에 적용하는 순서를 제안한다. 한 번에 모든 옵션을 켜지 않고, 에러가 가장 적은 것부터 켠다.

첫째, `isolatedModules`를 켜고 `export type` 에러를 고친다. 대부분 기계적으로 해결된다. 둘째, `consistent-type-imports` 린트를 적용해 `import type`을 일괄 변환한 뒤 `verbatimModuleSyntax`를 켠다. 이때 CJS 출력 패키지가 있다면 TS1287을 만나므로, 해당 패키지의 `type` 필드와 `module` 설정을 먼저 정리한다. 셋째, 공개 패키지에서만 `isolatedDeclarations`를 켠다. 넷째, 런타임이 Node 타입 스트리핑이라면 `erasableSyntaxOnly`를 켜고 `enum`과 파라미터 프로퍼티를 치환한다.

각 단계 후에는 `tsc --noEmit`과 기존 테스트를 모두 통과시키고, 번들 결과 크기를 비교한다. `verbatimModuleSyntax` 적용 후 부수효과 import가 남아 번들이 커지는 경우가 있는데, 이는 `import type` 누락의 신호다.

## 8. 검증 테스트

옵션이 실제로 동작하는지는 컴파일러 API로 자동 검증할 수 있다. 아래는 `isolatedDeclarations` 위반을 CI에서 확인하는 Vitest 예시다. (노트 작성 시 동일한 에러 코드를 tsc 5.9.3 CLI로 확인했으며, 아래 테스트 코드 자체는 실행하지 않았다.)

```ts
import ts from "typescript";
import { describe, expect, it } from "vitest";

function diagnose(source: string): number[] {
	const options: ts.CompilerOptions = {
		declaration: true,
		isolatedDeclarations: true,
		target: ts.ScriptTarget.ES2022,
		module: ts.ModuleKind.ESNext,
		noEmit: true,
	};
	const host = ts.createCompilerHost(options);
	const original = host.getSourceFile.bind(host);
	host.getSourceFile = (name, lang) =>
		name === "test.ts" ? ts.createSourceFile(name, source, lang) : original(name, lang);
	const program = ts.createProgram(["test.ts"], options, host);
	return ts.getPreEmitDiagnostics(program).map((d) => d.code);
}

describe("isolatedDeclarations", () => {
	it("반환 타입이 없는 export 함수를 거부한다", () => {
		expect(diagnose("export function f(a: number) { return a + 1; }")).toContain(9007);
	});
	it("명시된 반환 타입은 통과한다", () => {
		expect(diagnose("export function f(a: number): number { return a + 1; }")).toEqual([]);
	});
});
```

## 참고

- TypeScript 5.0 릴리스 노트: `verbatimModuleSyntax`
- TypeScript 5.5 릴리스 노트: Isolated Declarations
- TypeScript 5.8 릴리스 노트: `--erasableSyntaxOnly`
- TypeScript TSConfig Reference: isolatedModules, verbatimModuleSyntax, isolatedDeclarations
- Node.js 문서: Running TypeScript Natively (type stripping)
