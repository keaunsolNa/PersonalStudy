Notion 원본: https://www.notion.so/3755a06fd6d381eeb7cccf810e033700

# TypeScript Isolated Declarations와 Declaration Emit 병렬화 빌드 성능

> 2026-06-04 신규 주제 · 확장 대상: TypeScript Project References / Incremental Build (모노레포 빌드 학습됨)

## 학습 목표

- `isolatedDeclarations` 옵션이 declaration emit을 타입 체커 없이 수행 가능하게 만드는 원리를 컴파일러 파이프라인 수준에서 분석한다
- 기존 `tsc --declaration` 의 병목(타입 추론 의존)과 isolated declarations 가 요구하는 명시적 타입 표기 규칙을 코드로 구분한다
- oxc / swc / ts-blank-space 등 외부 트랜스파일러와 결합한 병렬 d.ts 생성 파이프라인을 구성한다
- 모노레포에서 Project References + isolatedDeclarations 조합의 빌드 시간 개선 폭과 마이그레이션 비용을 측정한다

## 1. Declaration Emit이 느린 이유 — 타입 체커 의존성

`tsc` 가 `.d.ts` 를 생성할 때 단순히 구현부를 지우는 것이 아니다. 명시적 타입 표기가 없는 export 의 타입을 **추론**해야 하고, 이 추론은 타입 체커 전체를 요구한다.

```typescript
// 이 함수의 d.ts 시그니처를 만들려면
export function createClient(config: Config) {
	return new HttpClient(config).withRetry(3);
}
// tsc는 HttpClient 생성자 → withRetry 반환 타입 → 제네릭 인스턴스화까지
// 전부 체크해야 반환 타입을 알 수 있다
```

문제는 추론 체인이 모듈 경계를 넘는다는 점이다. `withRetry` 가 다른 패키지의 타입을 반환하면 그 패키지의 타입 정보까지 로드해야 한다. 결과적으로 declaration emit 은 **전체 타입 체크와 동일한 비용**이 되고, 모노레포에서 패키지 A 의 d.ts 가 나와야 패키지 B 를 컴파일할 수 있는 직렬 의존이 생긴다. Project References 의 `tsc --build` 가 위상 정렬 순서로 패키지를 순차 빌드하는 이유가 이것이다.

실측 기준으로 중형 모노레포(40개 패키지, 총 30만 LOC)에서 `tsc --build` 전체 시간의 55~70%가 declaration emit 경로의 타입 추론에 소비된다는 보고가 TypeScript 5.5 설계 문서(#58201)에 인용되어 있다.

## 2. isolatedDeclarations의 핵심 계약

TypeScript 5.5에서 도입된 `isolatedDeclarations: true` 는 다음 계약을 강제한다: **모든 export 선언은 파일 단독으로(타입 체커 없이) d.ts 를 만들 수 있을 만큼 명시적이어야 한다.**

```jsonc
// tsconfig.json
{
	"compilerOptions": {
		"declaration": true,
		"isolatedDeclarations": true,
		"composite": true
	}
}
```

계약을 위반하면 컴파일 에러가 난다.

```typescript
// TS9007: Function must have an explicit return type annotation
// with --isolatedDeclarations
export function createClient(config: Config) {	// 에러
	return new HttpClient(config).withRetry(3);
}

// 수정: 반환 타입 명시
export function createClient(config: Config): RetryClient {
	return new HttpClient(config).withRetry(3);
}
```

`isolatedModules` 가 "파일 단독 **JS** 트랜스파일 가능"을 보장해 Babel/swc 가 타입 체크 없이 JS 를 뽑을 수 있게 했던 것과 정확히 대칭이다. `isolatedDeclarations` 는 "파일 단독 **d.ts** 생성 가능"을 보장한다.

명시가 필요한 대표 케이스는 다음과 같다.

```typescript
// 1. 함수 반환 타입 — 추론 불가, 명시 필수
export function parse(input: string): ParseResult { ... }

// 2. 변수 — 초기화 식이 자명하지 않으면 타입 명시
export const registry: Map<string, Handler> = new Map();

// 3. 클래스 멤버 — 표현식으로 초기화되는 public 필드
export class Service {
	timeout: number = DEFAULT_TIMEOUT;	// 리터럴이 아니면 타입 필요
}

// 4. 자명한 리터럴은 허용 (체커 없이 타입 결정 가능)
export const VERSION = "1.2.3";		// OK — "1.2.3" 리터럴 타입... 단 string으로 넓혀짐
export const MAX = 100;			// OK
```

허용/거부의 경계는 "syntactic하게 타입을 결정할 수 있는가"다. 리터럴, `as const`, satisfies 가 붙은 자명한 식은 통과하고, 함수 호출 결과·조건식·제네릭 인스턴스화가 필요한 식은 거부된다.

## 3. 컴파일러 내부 — transpileDeclaration API

TS 5.5는 `ts.transpileModule` 의 d.ts 버전인 `ts.transpileDeclaration` 을 공개했다. 단일 파일 텍스트만으로 d.ts 를 만든다.

```typescript
import * as ts from "typescript";

const source = `
export function add(a: number, b: number): number {
	return a + b;
}
const secret = 42;
export const config: { retries: number } = { retries: 3 };
`;

const result = ts.transpileDeclaration(source, {
	compilerOptions: { isolatedDeclarations: true },
	fileName: "math.ts"
});

console.log(result.outputText);
// declare function add(a: number, b: number): number;
// declare const config: { retries: number };
// export { add, config };
```

내부적으로는 declaration transformer 가 AST 를 순회하며 (1) 구현부 제거, (2) 비공개 심볼 제거, (3) 타입 표기를 그대로 복사한다. 타입 체커가 끼어드는 지점은 기존 emit 에서 `getTypeOfSymbol` → `typeToTypeNode` 로 추론 타입을 직렬화하던 부분인데, isolatedDeclarations 모드에서는 이 호출이 일어나지 않고, 표기가 없으면 즉시 에러를 낸다. 추론 호출이 사라졌으므로 **메모리에 프로그램 전체를 올릴 필요가 없고**, 파일 단위 병렬화가 가능해진다.

## 4. 외부 도구로 d.ts 병렬 생성

계약이 syntactic 하므로 TS 컴파일러가 아닌 도구도 d.ts 를 만들 수 있다. 현재 프로덕션에서 쓰이는 구현은 세 가지다.

| 도구 | 언어 | 방식 | 비고 |
| --- | --- | --- | --- |
| oxc (oxc-transform) | Rust | AST 변환으로 d.ts 직접 생성 | Vue/Nuxt 빌드 체인에서 사용 |
| swc dts emit | Rust | 실험적 | @swc/core 플러그인 경로 |
| ts-blank-space + tsc | TS | 타입만 공백 치환(JS), d.ts는 tsc | Bloomberg, JS emit 전용 |

oxc 기반 파이프라인 예시:

```javascript
// build-dts.mjs — 워커 풀에서 파일별 병렬 실행
import { isolatedDeclaration } from "oxc-transform";
import { readFile, writeFile } from "node:fs/promises";

export async function emitDts(file) {
	const code = await readFile(file, "utf8");
	const { code: dts, errors } = isolatedDeclaration(file, code);
	if (errors.length > 0) {
		throw new AggregateError(errors, `DTS emit failed: ${file}`);
	}
	await writeFile(file.replace(/\.ts$/, ".d.ts"), dts);
}
```

Rust 구현은 단일 파일 처리에 타입 체커 로드가 없으므로 파일당 수 ms 수준이다. Nuxt 팀이 공개한 수치로는 `vue-tsc` 기반 d.ts 생성 대비 **약 20배** 빠른 결과(분 단위 → 초 단위)를 보고했다. 핵심은 절대 속도보다 **의존성 그래프에서 d.ts 생성이 임계 경로에서 제거**된다는 점이다. 모든 패키지의 d.ts 를 먼저 병렬로 만들어 두면, 이후 각 패키지의 타입 체크도 서로 독립이 되어 패키지 수준 병렬화가 가능하다.

## 5. 모노레포 빌드 그래프 재설계

기존 Project References 빌드:

```
A(체크+emit) → B(체크+emit) → C(체크+emit)   // 직렬, 총 시간 = 합
```

isolatedDeclarations 도입 후:

```
1단계: A.d.ts, B.d.ts, C.d.ts 병렬 생성 (oxc, 수 초)
2단계: A 체크 ∥ B 체크 ∥ C 체크 (tsc, 패키지별 병렬)
```

Turborepo/Nx 설정으로 표현하면:

```jsonc
// turbo.json
{
	"tasks": {
		"dts": {
			"outputs": ["dist/**/*.d.ts"]
		},
		"typecheck": {
			"dependsOn": ["^dts"]	// 자신의 dts가 아니라 의존 패키지 dts에만 의존
		},
		"build": {
			"dependsOn": ["dts", "^build"]
		}
	}
}
```

`typecheck` 가 `^build` 가 아니라 `^dts` 에 의존하게 바뀌는 것이 그래프 상 가장 큰 변화다. 빌드 시간은 "가장 느린 패키지 체인의 합"에서 "dts 전체(짧음) + 가장 느린 단일 패키지 체크"로 줄어든다. 16코어 CI 러너 기준 40패키지 모노레포에서 풀 빌드 6~8분 → 1.5~2분 수준의 개선이 일반적으로 보고된다(워크로드 의존, oxc 채택 + 패키지 병렬도 8 가정).

## 6. 마이그레이션 — 비용과 자동화

명시적 반환 타입 강제는 코드베이스 전반에 수정을 요구한다. 수동으로는 불가능한 규모이므로 두 가지 자동화 경로를 쓴다.

```bash
# 1. TS 공식 codemod — 추론된 타입을 표기로 박아넣음
npx @typescript/auto-annotate --project tsconfig.json

# 2. typescript-eslint 규칙으로 점진 강제
```

```jsonc
// .eslintrc — 신규 코드부터 강제
{
	"rules": {
		"@typescript-eslint/explicit-module-boundary-types": "error"
	}
}
```

주의할 트레이드오프:

- **추론 타입이 표기보다 정밀한 경우가 있다.** codemod 가 `Readonly<{...}>` 같은 장황한 타입을 박아 가독성이 떨어질 수 있고, 조건부 타입 반환 함수는 자동 변환이 불가능해 수동 개입이 필요하다.
- **내부 전용 패키지는 강제 불필요.** isolatedDeclarations 는 d.ts 를 배포하는 라이브러리 패키지에만 켜고, 앱 패키지(d.ts 불필요)는 끄는 식의 선택적 적용이 합리적이다.
- **API 표면이 명시적이 된다는 부수 효과**는 장기적으로 이득이다. 반환 타입이 표기되어 있으면 구현 변경이 의도치 않게 공개 API 를 바꾸는 사고(추론 타입 드리프트)가 컴파일 에러로 잡힌다.

## 7. declarationMap과 디버깅 경로 유지

병렬 emit 도구를 쓰면 `declarationMap` (d.ts.map) 생성이 누락되기 쉽다. d.ts.map 이 없으면 모노레포에서 "Go to Definition" 이 d.ts 로 점프해 버린다. oxc 는 0.30 이후 sourcemap 생성을 지원하므로 반드시 켠다.

```javascript
const { code, map } = isolatedDeclaration(file, source, { sourcemap: true });
await writeFile(dtsPath, code + `\n//# sourceMappingURL=${basename(mapPath)}`);
await writeFile(mapPath, JSON.stringify(map));
```

IDE 경험 측면에서는 TS 5.6+ 의 `typescript.preferences.includePackageJsonAutoImports` 와 결합해, d.ts 가 아닌 소스로의 점프를 유지하면서도 빌드는 d.ts 기반으로 분리하는 구성이 표준이 되고 있다.

## 8. 적용 판단 기준

isolatedDeclarations 가 이득인 조건과 아닌 조건을 구분한다.

| 조건 | 권장 |
| --- | --- |
| 모노레포 10+ 패키지, d.ts 배포 라이브러리 다수 | 강력 권장 — 빌드 그래프 병렬화 이득 큼 |
| 단일 패키지 앱 | 불필요 — d.ts 자체가 필요 없음 |
| 조건부 타입/제네릭 추론을 공개 API로 노출하는 라이브러리 | 부분 적용 — 해당 파일만 제외하거나 오버로드로 재설계 |
| tsup/rollup-plugin-dts 등 번들형 d.ts 생성 사용 중 | 보완 관계 — 입력 d.ts 생성을 빨리 한 뒤 번들링 |

마이그레이션의 실질 비용은 "명시적 반환 타입 추가"이며, 이는 코드 리뷰 문화에 따라 이미 하고 있는 팀이라면 거의 0이다. 반대로 추론 의존이 심한 코드베이스(tRPC 라우터, Zod 스키마에서 추론된 타입을 그대로 export)는 `export type` 별칭을 만들어 명시하는 리팩터링이 선행되어야 한다.

```typescript
// 추론 의존 export — isolatedDeclarations 위반
export const appRouter = t.router({ ... });

// 우회: 타입을 별도 파일로 분리하고 명시적 별칭 export
const appRouter = t.router({ ... });
export type AppRouter = typeof appRouter;	// 에러 — typeof는 체커 필요
// 실전에서는 라우터 패키지만 isolatedDeclarations 제외가 현실적
```

이처럼 **타입 추론 자체가 제품인 패키지**(tRPC, Drizzle 스키마)는 예외로 두는 하이브리드 구성이 현재 커뮤니티의 합의점이다.

## 9. 검증 — 빌드 파이프라인 회귀 테스트

병렬 emit 도입 후 tsc 가 만들던 d.ts 와 동일한지 검증하는 회귀 장치를 둔다.

```bash
# CI에서 양쪽 emit 비교 (도입 초기 한정)
tsc -p tsconfig.json --emitDeclarationOnly --outDir .dts-tsc
node build-dts.mjs --outDir .dts-oxc
diff -r .dts-tsc .dts-oxc && echo "DTS parity OK"
```

공백/주석 차이는 정규화 후 비교하고, parity 가 수 주간 유지되면 tsc 경로를 제거한다. 또한 `attw`(arethetypeswrong)로 패키지 배포 형태의 d.ts 해석을 검증하면 exports map 과 d.ts 불일치 사고를 차단할 수 있다.

```bash
npx @arethetypeswrong/cli --pack ./packages/core
```

## 참고

- TypeScript 5.5 Release Notes — Isolated Declarations (devblogs.microsoft.com/typescript)
- TypeScript Design Proposal #47947 — isolatedDeclarations
- oxc-transform isolatedDeclaration API 문서 (oxc.rs)
- Bloomberg ts-blank-space 기술 블로그 (bloomberg.github.io/ts-blank-space)
- Nuxt 팀 "20x faster d.ts generation" 블로그 포스트
- Are the Types Wrong? CLI 문서 (arethetypeswrong.github.io)
