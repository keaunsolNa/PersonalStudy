Notion 원본: https://www.notion.so/3e05a06fd6d381729055f94a65d5cc14

# TypeScript 7 네이티브 컴파일러와 체커 병렬화 및 6.0 side-by-side 전환 전략

> 2026-09-19 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Go 네이티브 포팅이 왜 8~12배 속도 향상을 내는지 병렬화 구조로 설명한다
- `--checkers` / `--builders` / `--singleThreaded` 세 플래그의 상호작용과 메모리 비용을 계산한다
- 7.0이 API를 제공하지 않는다는 제약을 side-by-side 설치로 우회한다
- 6.0에서 하드 에러가 된 옵션과 JS 지원 변경점을 마이그레이션 체크리스트로 정리한다

## 1. 무엇이 바뀌었나 — 포팅의 범위와 성격

TypeScript 7.0은 2026년 7월 8일 정식 출시됐다. 2025년 3월 11일 Anders Hejlsberg가 네이티브 포팅 착수를 발표한 지 약 16개월 만이다. 핵심은 **컴파일러와 언어 서비스 전체를 Go로 재작성**한 것이고, 의도적으로 "충실한 포팅"을 지향해 원본 코드베이스의 구조와 로직을 유지했다. 타입 체크 결과가 6.0과 동일하게 나와야 하기 때문이다.

성능 수치는 마이크로벤치마크가 아니라 실제 오픈소스 코드베이스 기준이다(기본값 `--checkers 4`).

| 코드베이스 | TypeScript 6 | TypeScript 7 | 배율 |
|---|---|---|---|
| vscode | 125.7s | 10.6s | 11.9x |
| sentry | 139.8s | 15.7s | 8.9x |
| bluesky | 24.3s | 2.8s | 8.7x |
| playwright | 12.8s | 1.47s | 8.7x |
| tldraw | 11.2s | 1.46s | 7.7x |

메모리도 줄었다. vscode 5.2GB → 4.2GB(-18%), bluesky 1.8GB → 1.3GB(-26%). "빠른 대신 메모리를 더 쓴다"는 흔한 트레이드오프가 여기서는 성립하지 않았다는 점이 눈에 띈다.

속도 향상의 출처는 세 갈래다. 첫째, 네이티브 코드 실행 — JIT 워밍업과 JS 엔진 오버헤드 제거. 둘째, **공유 메모리 멀티스레딩** — Node.js의 worker_threads는 구조적 공유가 사실상 불가능하지만 Go의 고루틴은 같은 힙을 공유한다. 이것이 결정적이다. 셋째, 포팅 과정에서 얻은 개별 최적화들.

에디터 체감은 더 극적이다. VS Code 코드베이스에서 "에디터 열고 첫 에러가 보일 때까지" 17.5초 → 1.3초 미만. Canva는 58초 → 4.8초, Slack은 CI 타입체크 7.5분 → 1.25분을 보고했다.

## 2. 체커 병렬화 — 왜 4개가 기본값인가

파싱과 emit은 파일 단위로 거의 독립이라 병렬화가 쉽다. 타입 체크는 다르다. 대부분의 파일이 의존 파일과 전역 스코프의 **같은 타입 정보**에 기대므로, 체커를 완전히 독립 실행하면 계산과 메모리 양쪽에서 낭비가 크다. 더 까다로운 문제는 타입 체크가 때때로 **정보의 상대적 순서**에 의존한다는 점이다. 같은 입력이면 항상 같은 순서로 검사해야 결과가 같다.

TypeScript 7의 해법은 "고정된 수의 체커 워커, 각자 자기 세계관을 가짐"이다. 워커들은 공통 작업을 일부 중복 수행하지만, 같은 입력 파일이 주어지면 **항상 동일하게 분할**해 동일한 결과를 낸다. 결정성을 위해 완전 독립도 완전 공유도 아닌 중간을 택한 셈이다.

```bash
# 기본: 체커 4개
npx tsc --noEmit

# 코어가 많은 개발 머신
npx tsc --noEmit --checkers 8

# CI 러너처럼 코어·메모리가 빠듯한 환경
npx tsc --noEmit --checkers 2
```

`--checkers 8`로 올렸을 때의 수치는 다음과 같다.

| 코드베이스 | TS 6 | TS 7 (`--checkers 8`) | 배율 |
|---|---|---|---|
| vscode | 125.7s | 7.51s | 16.7x |
| sentry | 139.8s | 12.08s | 11.6x |
| bluesky | 24.3s | 2.01s | 12.1x |
| playwright | 12.8s | 1.16s | 11x |
| tldraw | 11.2s | 1.06s | 10.6x |

vscode 기준 10.6초 → 7.51초. 4개를 8개로 두 배 늘려 29% 단축이므로 선형 확장은 아니다. 중복 작업이 늘기 때문이다. 대신 메모리는 워커 수에 비례해 증가한다. `--checkers 1`로 내리면 사실상 단일 스레드 체크가 되어 중복 작업이 사라진다.

주의할 점 하나. 공식 문서는 "드문 경우 `--checkers` 값을 바꾸면 **순서 의존적 결과**가 드러날 수 있다"고 명시한다. 즉 로컬에서 `--checkers 8`, CI에서 기본값 4를 쓰면 이론적으로 에러 개수가 달라질 수 있다. 팀 전체가 같은 값을 `tsconfig.json`이나 스크립트에 고정하는 편이 안전하다.

```json
{
  "scripts": {
    "typecheck": "tsc --noEmit --checkers 4",
    "typecheck:ci": "tsc --noEmit --checkers 4"
  }
}
```

## 3. `--builders` — 모노레포에서의 곱셈 효과

`--build` 모드에서 프로젝트 참조를 여러 개 동시에 빌드하는 병렬도가 `--builders`다. `--checkers`와 **곱셈으로 작용**한다는 점이 함정이다.

```bash
# 최대 4 × 4 = 16개 타입 체커가 동시에 돈다
npx tsc --build --checkers 4 --builders 4
```

16개 체커가 각자 프로그램 상태를 들고 있으면 메모리가 폭발한다. 모노레포에서 CI가 OOM으로 죽는다면 이 곱을 먼저 의심해야 한다. 경험적 출발점은 `checkers × builders ≈ 물리 코어 수`다.

`--checkers`와 달리 `--builders` 값을 바꿔도 결과는 달라지지 않는다. 다만 프로젝트 참조 빌드는 근본적으로 **의존 그래프에 병목**이 걸린다. 직렬 체인이 깊으면 builders를 올려도 효과가 없다. 예외는 `isolatedDeclarations`를 켠 경우로, 선언 파일 emit이 타입 체크 없이 구문만으로 가능해지므로 하위 프로젝트가 상위 체크를 기다리지 않아도 된다.

```json
{
  "compilerOptions": {
    "isolatedDeclarations": true,
    "declaration": true,
    "composite": true
  }
}
```

디버깅이나 6.0과의 성능 비교, 외부에서 병렬 빌드를 오케스트레이션하는 경우에는 `--singleThreaded`로 모든 병렬화를 끈다. 이 플래그는 체커뿐 아니라 파싱·emit까지 단일 스레드로 만든다.

## 4. API 부재 — 7.0의 가장 큰 제약과 우회

7.0은 **프로그래매틱 API를 제공하지 않는다**. 새 API는 7.1 예정이다. 이것이 실무 도입의 실질적 장벽이다. 영향 범위:

- `typescript-eslint` — `typescript`를 직접 import
- Vue(Volar), Svelte, Astro, MDX — TS를 자기 언어 서비스에 임베드
- Angular 템플릿 타입 체크
- webpack의 `ts-loader` 계열

공식 권고는 side-by-side 설치다. 호환 패키지 `@typescript/typescript6`가 `tsc6` 실행파일과 6.0 API를 함께 제공한다. npm alias로 두 버전을 공존시킨다.

```json
{
  "devDependencies": {
    "@typescript/native": "npm:typescript@^7.0.2",
    "typescript": "npm:@typescript/typescript6@^6.0.2"
  }
}
```

이 구성에서 `npx tsc`는 7.0 바이너리를 실행하고, `typescript`를 import하는 도구(typescript-eslint 등)는 6.0 API를 본다. peer dependency로 `typescript`를 요구하는 생태계 도구가 깨지지 않게 하는 것이 alias의 목적이다.

에디터 쪽도 분리 운영이 가능하다. VS Code에는 TypeScript 7 전용 확장이 있고, 명령 팔레트의 "Disable TypeScript 7 Language Server"로 언제든 6.0으로 되돌릴 수 있다. Vue·Svelte·Astro 프로젝트라면 당분간 이 스위치를 끈 상태로 둬야 한다. Angular는 절충이 가능하다 — CLI에서 `tsc`(7.0)로 전체 에러를 빠르게 잡고, 에디터는 6.0을 쓰는 조합.

나이틀리 빌드 경로도 바뀌었다. 기존 `@typescript/native-preview`(주당 850만 다운로드) 대신 표준 패키지의 `next` 태그로 이동한다.

```bash
npm install -D typescript@next
```

## 5. 6.0 기본값 채택 — 마이그레이션에서 실제로 터지는 것들

7.0은 6.0의 타입 체크·CLI 동작과 호환되도록 만들어졌다. `stableTypeOrdering`을 켜고 `ignoreDeprecations` 없이 6.0에서 깨끗하게 컴파일되는 코드는 7.0에서도 동일하게 컴파일된다. 문제는 **6.0 자체가 최근 릴리스**라 대부분의 프로젝트가 아직 그 변경을 흡수하지 못했다는 점이다.

바뀐 기본값:

- `strict`: `true`
- `module`: `esnext`
- `target`: `esnext` 바로 직전의 안정 ECMAScript 버전
- `noUncheckedSideEffectImports`: `true`
- `libReplacement`: `false`
- `stableTypeOrdering`: `true`, 끌 수 없음
- `rootDir`: `./` (내부 소스 디렉터리는 명시 필요)
- `types`: `[]` (기존 동작은 `["*"]`)

공식 안내가 가장 "놀랍다"고 꼽은 둘이 `rootDir`과 `types`다. `tsconfig.json`이 `src` 바깥에 있는 일반적 구조라면 명시가 필요하다.

```json
{
    "compilerOptions": {
        "rootDir": "./src"
    },
    "include": ["./src"]
}
```

`types: []`가 기본이 되면서 전역 선언에 의존하는 프로젝트는 필요한 `@types` 패키지를 나열해야 한다. 이걸 놓치면 `describe`, `it`, `process` 같은 전역이 한꺼번에 사라진다.

```json
{
    "compilerOptions": {
        "types": ["node", "jest"]
    }
}
```

지원이 끊긴 옵션들(하드 에러):

| 제거 | 대체 |
|---|---|
| `target: es5` | 상위 target |
| `downlevelIteration` | 불필요 |
| `moduleResolution: node` / `node10` / `classic` | `nodenext` 또는 `bundler` |
| `module: amd, umd, systemjs, none` | `esnext` 또는 `preserve` |
| `baseUrl` | `paths`를 프로젝트 루트 기준 상대경로로 |
| `esModuleInterop: false` | 설정 불가(항상 true) |
| `alwaysStrict: false` | 설정 불가(항상 true) |
| namespace 선언의 `module` 키워드 | `namespace` |
| import의 `asserts` 키워드 | `with` (ECMAScript import attributes) |

그리고 CLI 동작 변경 하나가 스크립트를 조용히 깨뜨린다: 현재 디렉터리에 `tsconfig.json`이 있으면 명령줄에 파일 경로를 줄 수 없고, 필요하면 `--ignoreConfig`를 명시해야 한다. `tsc src/index.ts` 같은 ad-hoc 호출이 있는 빌드 스크립트를 먼저 점검해야 한다.

## 6. 템플릿 리터럴 타입의 유니코드 처리 변경

타입 레벨 문자열 조작을 하는 코드에 직접 영향을 주는 변경이다. 7.0은 템플릿 리터럴 타입 추론에서 유니코드 **코드 포인트**를 단위로 다룬다.

```ts
type HeadTail<S> = S extends `${infer Head}${infer Tail}` ? [Head, Tail] : never;

type Result = HeadTail<"😀abc">;
// 7.0:   ["😀", "abc"]
// 이전:  ["\ud83d", "\ude00abc"]
```

이전에는 JavaScript의 UTF-16 인덱싱을 그대로 따라 서러게이트 페어를 반으로 쪼갰다. `"😀abc"[0]`과 일치한다는 점에서 기술적으로는 일관됐지만, 짝 없는 서러게이트를 담은 문자열 리터럴 타입은 의미가 없었다. 새 동작은 `for...of` 순회나 `[...str]` 스프레드의 직관과 일치한다.

**이것은 브레이킹 체인지다.** UTF-16 코드 유닛을 의도적으로 모델링한 타입 레벨 `Length` 유틸리티 같은 것은 결과가 달라진다. 자체 구현한 타입 레벨 문자열 라이브러리가 있다면 이모지·한자 확장 영역을 포함한 타입 테스트를 먼저 돌려 봐야 한다.

```ts
import { expectTypeOf } from 'expect-type';

type StrLen<S extends string, Acc extends unknown[] = []> =
  S extends `${string}${infer Rest}` ? StrLen<Rest, [...Acc, unknown]> : Acc['length'];

expectTypeOf<StrLen<'😀ab'>>().toEqualTypeOf<3>();   // 7.0 기준. 6.0 에서는 4
```

## 7. JavaScript 지원 재작성 — JSDoc 코드베이스의 부담

포팅 기회에 JS 파일 지원도 다시 썼다. 기존 구현은 Closure Compiler와 JSDoc 도구가 이해할 법한 패턴을 경험적으로 특수 처리하는 방식이었고, 그 때문에 `.ts` 파일 분석과 여러 지점에서 갈라졌다. 7.0은 이를 `.ts` 분석과 일관되게 정리했다.

주요 차이:

- 타입 자리에 값을 쓸 수 없다 → `typeof someValue`
- `@enum` 특수 처리 없음 → `(typeof YourEnum)[keyof typeof YourEnum]`에 `@typedef`
- 단독 `?`를 타입으로 쓸 수 없다 → `any`
- `@class`가 함수를 생성자로 만들지 않는다 → `class` 선언 사용
- 후위 `!` 미지원 → 그냥 `T`
- 타입 이름은 `@typedef` 태그 안에 정의해야 하며, 식별자 옆에 붙이는 형태는 불가
- Closure 스타일 함수 구문 `function(string): void` 미지원 → `(s: string) => void`

추가로 `this` 별칭이나 함수 `prototype` 전체 재할당 같은 패턴도 더 이상 특별 취급하지 않는다. 상세 차이는 `microsoft/typescript-go` 저장소의 `CHANGES.md`에 계속 갱신된다.

JSDoc 기반 대형 JS 코드베이스를 운영 중이라면 이 항목이 `rootDir`/`types`보다 큰 작업량이 될 수 있다. 마이그레이션 전에 `allowJs: true`인 파일 수부터 세어 볼 것.

```bash
npx tsc --noEmit --listFiles | grep -c '\.js$'
```

## 8. 도입 판단 — 언제 올리고 언제 기다릴 것인가

**지금 올릴 만한 경우.** 순수 TS 프로젝트, 프레임워크 언어 서비스 플러그인 의존 없음, CI 타입체크가 병목. Slack이 머지 큐 시간 40%를 없앴고 Microsoft News Services가 월 400시간을 절약했다는 보고가 나온 영역이 정확히 이쪽이다. 기대 효과가 가장 크고 위험이 가장 작다.

**부분 도입이 합리적인 경우.** Angular 프로젝트 — CLI만 7.0, 에디터는 6.0. 또는 CI 타입체크 잡만 7.0으로 돌리고 빌드 파이프라인은 6.0 유지.

**기다려야 하는 경우.** Vue/Svelte/Astro/MDX. Volar 계열이 7.0 API를 쓸 수 없어 구조적으로 막혀 있다. 7.1의 API를 기다리는 것 외에 방법이 없다. webpack `ts-loader`처럼 컴파일러 API를 직접 부르는 빌드 체인도 마찬가지다.

전환 절차는 단계로 쪼개는 편이 안전하다.

```bash
# 1단계: 6.0 으로 먼저 올리고 deprecation 을 전부 해소한다
npm install -D typescript@^6
npx tsc --noEmit    # 여기서 나오는 경고를 0으로

# 2단계: stableTypeOrdering 을 켠 상태로 6.0 에서 깨끗한지 확인

# 3단계: side-by-side 설치로 7.0 을 CI 에만 투입
npm install -D @typescript/native@npm:typescript@^7

# 4단계: 체커 수를 머신에 맞춰 고정하고 팀 전체 통일
npx tsc --noEmit --checkers 4
```

2단계를 건너뛰고 6.x에서 7.0으로 직행하면 "7.0이 깨졌다"고 오진하기 쉽다. 실제로는 6.0의 기본값 변경이 원인인 경우가 대부분이다. 공식 안내도 6.0을 경유하라고 명시적으로 권한다.

마지막으로 기대 관리. 7.1이 11월경으로 예상되고 이후로는 3~4개월 주기의 기능 릴리스로 돌아간다고 밝혔다. API가 필요한 조직이라면 7.0을 건너뛰고 7.1을 기다리는 것도 합리적 선택이며, 그동안 6.0으로 올려 두는 것만으로도 전환 비용의 절반은 미리 치르는 셈이 된다.

## 참고

- Daniel Rosenwasser, "Announcing TypeScript 7.0", Microsoft DevBlogs (2026-07-08)
- Anders Hejlsberg, "A 10x Faster TypeScript" / TypeScript Native Port 발표 (2025-03-11)
- microsoft/typescript-go — `CHANGES.md` (6.0 ↔ 7.0 JS 지원 차이)
- "Announcing TypeScript 7.0 RC", Microsoft DevBlogs (2026-06-18)
- "Announcing TypeScript 6.0", Microsoft DevBlogs (기본값 변경 및 deprecation 목록)
- VS Code Blog, "Iterating faster with TS 7" (2026-06-26)
- parcel-bundler/watcher — `--watch` 모드의 기반이 된 파일 워처
