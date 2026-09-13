Notion 원본: https://app.notion.com/p/3da5a06fd6d381939d5bc12b9e9011e3?pvs=204

# TypeScript 타입 테스트와 expect-type·tsd 및 타입 회귀 방지 전략

> 2026-09-13 신규 주제 · 확장 대상: TypeScript 타입 시스템 / TDD

## 학습 목표

- 런타임 테스트가 잡지 못하는 타입 회귀의 범주를 구분한다
- `expect-type`, `tsd`, `vitest`의 `expectTypeOf`를 API 설계 목적별로 선택한다
- 조건부 타입의 동등성 판정이 왜 어려운지 `IsEqual` 구현으로 분석한다
- 타입 테스트를 CI 에 배치하고 실행 비용을 통제한다

## 1. 타입 테스트가 필요한 이유

Java 로 라이브러리를 만들면 시그니처가 곧 계약이고, 시그니처를 바꾸면 컴파일이 깨진다. TypeScript 는 다르다. 제네릭 함수의 반환 타입이 **추론으로 결정**되기 때문에, 구현 내부를 고쳤을 뿐인데 소비자 쪽 추론 결과가 조용히 바뀔 수 있다.

구체적인 예를 보자.

```typescript
// v1
export function pick<T, K extends keyof T>(obj: T, keys: K[]) {
	const result = {} as Pick<T, K>;
	for (const key of keys) {
		result[key] = obj[key];
	}
	return result;
}
```

반환 타입 애노테이션이 없으므로 `Pick<T, K>` 가 추론된다. 누군가 리팩터링하면서 `result` 를 `Partial<Pick<T, K>>` 로 선언하면, 런타임 동작은 동일한데 소비자 코드에서는 모든 프로퍼티가 `| undefined` 가 된다. 단위 테스트는 전부 통과한다. 소비자가 `strictNullChecks` 를 켜고 있다면 그쪽 빌드가 깨지고, 꺼져 있다면 아무도 모르는 채로 타입 안전성이 사라진다.

타입 회귀는 대략 네 범주로 나뉜다.

| 범주 | 예시 | 런타임 테스트로 검출 |
|---|---|---|
| 추론 결과 변화 | `Pick<T,K>` → `Partial<Pick<T,K>>` | 불가 |
| 과대 허용(unsound widening) | 반환 타입이 `any` 로 무너짐 | 불가 |
| 과소 허용 | 유효한 인자가 거부됨 | 부분적(컴파일 실패로) |
| 에러 메시지 품질 저하 | 조건부 타입 폭발로 진단이 불가해짐 | 불가 |

두 번째가 가장 위험하다. `any` 로 무너진 타입은 **모든 테스트를 통과시킨다**. 타입 테스트가 없으면 검출 수단 자체가 없다.

## 2. 도구 삼파전 — tsd, expect-type, expectTypeOf

세 도구는 검사 시점과 실패 표현 방식이 다르다.

**tsd** 는 별도 프로세스로 `.test-d.ts` 파일을 컴파일하고, 자체 규칙으로 진단을 수집한다. `expectError` 로 "컴파일 에러가 나야 함"을 단언할 수 있는 것이 강점이다.

```typescript
// index.test-d.ts
import { expectType, expectError, expectAssignable } from 'tsd';
import { pick } from '.';

const src = { id: 1, name: 'a', active: true };

expectType<{ id: number; name: string }>(pick(src, ['id', 'name']));
expectError(pick(src, ['missing']));
expectAssignable<object>(pick(src, ['id']));
```

`package.json` 에 `"types": "index.d.ts"` 가 있어야 동작하고, 기본적으로 패키지 루트의 `index.test-d.ts` 를 본다. 배포되는 `.d.ts` 를 검사하므로 **선언 파일 생성 단계까지 포함한 회귀**를 잡는다. 이것이 tsd 의 본질적 장점이다: 소스가 아니라 배포물을 테스트한다.

**expect-type** 은 런타임 의존성이 없는 순수 타입 레벨 라이브러리다. 체이닝 API 로 표현력이 높다.

```typescript
import { expectTypeOf } from 'expect-type';

expectTypeOf(pick(src, ['id'])).toEqualTypeOf<{ id: number }>();
expectTypeOf(pick).parameter(0).toBeObject();
expectTypeOf(pick).returns.not.toBeAny();
expectTypeOf<Promise<string>>().resolves.toBeString();
```

`not.toBeAny()` 가 특히 중요하다. `any` 무너짐을 직접 겨냥하는 단언이며, 다른 어떤 단언으로도 대체되지 않는다. `toEqualTypeOf<any>()` 는 `any` 가 모든 타입과 양방향 할당 가능하기 때문에 통과해 버린다.

**vitest 의 `expectTypeOf`** 는 expect-type 을 내장한 것이다. `vitest --typecheck` 로 실행하면 `*.test-d.ts` 를 tsc 로 검사하고 결과를 일반 테스트 리포터에 섞어 준다.

```typescript
// pick.test-d.ts
import { expectTypeOf, test } from 'vitest';

test('pick 은 선택한 키만 남긴다', () => {
	expectTypeOf(pick(src, ['id'])).toEqualTypeOf<{ id: number }>();
});
```

```typescript
// vitest.config.ts
export default defineConfig({
	test: {
		typecheck: {
			enabled: true,
			include: ['**/*.test-d.ts'],
			tsconfig: './tsconfig.typecheck.json',
		},
	},
});
```

선택 기준은 단순하다.

| 상황 | 선택 |
|---|---|
| 배포 `.d.ts` 계약 검증 | tsd |
| 애플리케이션 내부 타입 유틸 검증 | expect-type / vitest |
| 기존 테스트 러너와 리포트 통합 | vitest typecheck |
| "에러가 나야 한다" 단언이 핵심 | tsd(`expectError`) 또는 `@ts-expect-error` |

셋 다 쓰는 것도 이상하지 않다. 라이브러리 저장소라면 내부 유틸은 vitest 로, 배포 진입점은 tsd 로 이중 방어하는 구성이 흔하다.

## 3. 타입 동등성 판정의 함정

`toEqualTypeOf` 는 어떻게 두 타입이 같은지 판단하는가? 순진한 구현은 양방향 할당 가능성이다.

```typescript
type NaiveEqual<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
```

이것은 세 군데에서 틀린다.

첫째, `any`. `NaiveEqual<any, string>` 은 `true` 다. `any` 는 양방향 할당 가능하기 때문이다. 타입 테스트의 핵심 목적이 `any` 검출인데 판정기 자체가 `any` 를 못 잡으면 무용지물이다.

둘째, 선택적 프로퍼티와 `| undefined` 의 구분. `{ a?: string }` 과 `{ a: string | undefined }` 는 `exactOptionalPropertyTypes` 가 꺼져 있으면 서로 할당 가능하지만 의미가 다르다.

셋째, `readonly` 수식어. `readonly string[]` 과 `string[]` 은 한 방향만 할당 가능하므로 이건 오히려 잡힌다. 반면 객체의 `readonly` 프로퍼티는 할당 가능성에 영향을 주지 않아 구분되지 않는다.

실무에서 쓰이는 판정기는 조건부 타입의 **지연 평가 동일성**을 이용한다.

```typescript
type IsEqual<A, B> =
	(<T>() => T extends A ? 1 : 2) extends
	(<T>() => T extends B ? 1 : 2) ? true : false;
```

이 트릭은 TypeScript 가 두 제네릭 시그니처의 할당 가능성을 검사할 때 조건부 타입을 **구조적으로 비교**한다는 내부 동작에 기댄다. `A` 와 `B` 가 내부 표현까지 동일할 때만 두 시그니처가 서로 할당 가능해진다. 결과적으로 `IsEqual<any, string>` 은 `false` 가 되고, `IsEqual<{a?: string}, {a: string | undefined}>` 도 `false` 가 된다.

주의할 점은 이것이 **명세된 동작이 아니라 구현 세부에 대한 의존**이라는 것이다. TypeScript 팀은 이 패턴이 널리 쓰인다는 사실을 인지하고 있어 사실상 고정되어 있지만, 컴파일러 메이저 업그레이드 시 타입 테스트가 통로 흔들릴 가능성은 남아 있다. 그래서 expect-type 은 판정기를 직접 구현해 두고, TypeScript 버전 업 때 자기 테스트로 검증한다.

또 하나. `IsEqual` 는 **분배(distributive)되지 않는다**. `A` 가 유니언이어도 조건부 타입의 검사 위치가 아니라 시그니처 내부이므로 유니언이 그대로 유지된다. 이것은 의도된 동작이다 — `IsEqual<string | number, number | string>` 은 `true` 여야 하고, 실제로 그렇다(유니언 순서는 정규화된다).

## 4. 에러를 단언하는 두 방식

"이 호출은 컴파일 에러가 나야 한다"는 단언은 API 계약의 절반이다. 두 가지 방법이 있다.

```typescript
// 방식 1: @ts-expect-error
// @ts-expect-error 존재하지 않는 키는 거부되어야 한다
pick(src, ['missing']);

// 방식 2: tsd expectError
expectError(pick(src, ['missing']));
```

`@ts-expect-error` 는 컴파일러 내장이라 별도 도구가 필요 없다. 다음 줄에 에러가 없으면 그 지시어 자체가 에러가 된다(“Unused '@ts-expect-error' directive”). 즉 양방향으로 동작한다.

한계는 **에러의 종류를 구분하지 못한다**는 것이다. 오타로 함수명을 틀려도 에러가 나므로 지시어는 만족된다. 테스트가 통과하지만 검증하려던 것은 검증되지 않았다.

```typescript
// @ts-expect-error  ← 통과한다. 하지만 'pikc' 오타 때문이지 키 검증 때문이 아니다
pikc(src, ['missing']);
```

방어책은 두 가지다. 하나는 같은 호출의 성공 케이스를 바로 옆에 두어 오타를 드러내는 것이다.

```typescript
pick(src, ['id']);           // 성공해야 함 — 오타면 여기서 터진다
// @ts-expect-error
pick(src, ['missing']);
```

다른 하나는 tsd 의 `expectError` 를 쓰되, tsd 가 에러 코드를 검사하지는 않으므로 여전히 같은 한계가 있다는 점을 인지하는 것이다. 결국 **성공 케이스와 실패 케이스를 쌍으로 작성**하는 규율이 실질적 방어다.

## 5. 타입 테스트 파일의 컴파일 경계 분리

타입 테스트 파일에는 의도적인 에러가 들어 있다. 이 파일이 프로덕션 빌드에 포함되면 빌드가 깨진다. 경계를 나눠야 한다.

```jsonc
// tsconfig.json — 빌드용
{
	"compilerOptions": {
		"strict": true,
		"declaration": true,
		"outDir": "dist"
	},
	"include": ["src"],
	"exclude": ["**/*.test-d.ts", "**/*.test.ts"]
}
```

```jsonc
// tsconfig.typecheck.json — 타입 테스트용
{
	"extends": "./tsconfig.json",
	"compilerOptions": {
		"noEmit": true,
		"exactOptionalPropertyTypes": true
	},
	"include": ["src", "types-test"]
}
```

`exactOptionalPropertyTypes` 를 타입 테스트 쪽에서만 켜는 구성이 유용하다. 이 플래그가 켜져 있으면 `{ a?: string }` 에 `undefined` 를 명시적으로 넣을 수 없으므로, 선택적 프로퍼티와 `| undefined` 의 차이가 단언 수준에서 강제된다.

주의: `skipLibCheck` 는 타입 테스트 tsconfig 에서도 켜 두는 편이 낫다. 끄면 `node_modules` 안의 남의 `.d.ts` 오류까지 잡혀 신호 대 잡음비가 무너진다. 다만 `skipLibCheck: true` 는 자기 패키지의 `.d.ts` 검사도 건너뛰므로, 배포물 검증은 tsd 처럼 별도 경로로 해야 한다.

## 6. 실행 비용과 CI 배치

타입 테스트는 tsc 를 한 번 더 돌리는 일이다. 중형 저장소에서 전체 타입 체크가 30초라면, 타입 테스트를 추가하면 대략 그만큼 더 든다. 세 가지로 통제한다.

**증분 빌드 재사용.** `tsconfig.typecheck.json` 에 `incremental: true` 와 별도 `tsBuildInfoFile` 을 지정하면 두 번째 실행부터 크게 줄어든다. CI 에서는 `.tsbuildinfo` 를 캐시 키에 포함시킨다.

```yaml
- uses: actions/cache@v4
  with:
    path: .cache/tsbuildinfo
    key: tsc-${{ runner.os }}-${{ hashFiles('src/**/*.ts', 'tsconfig*.json') }}
    restore-keys: tsc-${{ runner.os }}-
```

**대상 축소.** 타입 테스트는 공개 API 경계에만 둔다. 내부 헬퍼마다 타입 테스트를 붙이면 리팩터링 저항만 커진다. 판단 기준: "이 타입이 잘못되면 소비자 코드가 조용히 망가지는가?"

**인스턴스화 폭발 감시.** 타입 테스트는 조건부 타입을 대량으로 평가하므로 컴파일 시간의 병목이 되기 쉽다. `tsc --extendedDiagnostics` 로 `Instantiations` 수치를 찍고 임계값을 CI 에서 감시한다.

```bash
tsc -p tsconfig.typecheck.json --extendedDiagnostics | grep Instantiations
# Instantiations:  412,883
```

경험적으로 백만 인스턴스화를 넘기면 IDE 반응성이 눈에 띄게 나빠진다. 타입 테스트가 그 상당 부분을 차지한다면 재귀 유틸을 꼬리 재귀 형태로 바꾸거나 테스트 케이스 수를 줄인다.

## 7. 실전 패턴 — 제네릭 API 의 회귀 방어

실제 쓸 만한 테스트 묶음은 이런 모양이다.

```typescript
import { expectTypeOf, describe, test } from 'vitest';
import { createClient } from '../src/client';

describe('createClient 타입 계약', () => {
	const client = createClient({
		endpoints: {
			getUser: { input: { id: 0 }, output: { id: 0, name: '' } },
		},
	});

	test('입력 타입이 엔드포인트 정의에서 추론된다', () => {
		expectTypeOf(client.getUser).parameter(0).toEqualTypeOf<{ id: number }>();
	});

	test('출력은 Promise 로 감싸진다', () => {
		expectTypeOf(client.getUser).returns
			.resolves.toEqualTypeOf<{ id: number; name: string }>();
	});

	test('any 로 무너지지 않는다', () => {
		expectTypeOf(client.getUser).returns.not.toBeAny();
	});

	test('정의되지 않은 엔드포인트는 존재하지 않는다', () => {
		expectTypeOf(client).not.toHaveProperty('getPost');
	});
});
```

네 번째 단언이 실무적으로 중요하다. 매핑 타입의 키 집합이 넓어지는 회귀 — 예컨대 `[K in keyof T]` 가 `[K in string]` 으로 바뀌는 것 — 는 다른 단언을 전부 통과하면서 자동완성만 망가뜨린다.

`not.toBeAny()` 는 모든 공개 함수에 기계적으로 붙일 가치가 있다. 비용이 거의 없고, 가장 흔하면서 가장 조용한 회귀를 막는다.

## 8. 한계와 오해

타입 테스트는 **타입의 정확성**을 검증하지 않는다. 추론 결과가 작성자의 기대와 일치하는지만 본다. 기대 자체가 틀렸다면 테스트는 틀린 기대를 고정시킨다. 이것은 일반 단위 테스트와 같은 성질의 한계지만, 타입은 "이 정도면 맞겠지"로 작성되는 일이 잦아 더 자주 발생한다.

또한 타입 테스트는 **에러 메시지 품질을 측정하지 못한다**. 조건부 타입이 다섯 겹 중첩된 API 는 잘못 쓰면 40줄짜리 진단을 뱉는다. 타입 테스트는 전부 통과하지만 실사용 경험은 최악이다. 이 부분은 자동화가 어렵고, 의도적으로 잘못된 호출을 작성해 진단을 눈으로 확인하는 수동 검토가 여전히 필요하다.

마지막으로, 타입 테스트는 `.d.ts` 배포 형태의 문제를 전부 잡지 못한다. `exports` 맵이 잘못돼 `moduleResolution: bundler` 환경에서만 타입이 해석되는 경우, 소스 기반 타입 테스트는 통과한다. 이 층위는 `@arethetypeswrong/cli` 같은 별도 도구가 담당하며, 타입 테스트와 상호 보완 관계다.

## 참고

- TypeScript Handbook — Conditional Types (https://www.typescriptlang.org/docs/handbook/2/conditional-types.html)
- tsd 공식 저장소 (https://github.com/tsdjs/tsd)
- expect-type 공식 저장소 (https://github.com/mmkal/expect-type)
- Vitest — Testing Types (https://vitest.dev/guide/testing-types)
- TypeScript Wiki — Performance (https://github.com/microsoft/TypeScript/wiki/Performance)
