Notion 원본: https://www.notion.so/3d75a06fd6d381578e61c980e40e6c85

# TypeScript 네이티브 포팅 tsgo와 병렬 타입 체크 및 컴파일러 아키텍처

> 2026-09-10 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- tsc 파이프라인을 `--extendedDiagnostics` 지표로 분해해 병목을 지목한다.
- `--checkers` / `--builders` / `--singleThreaded` 로 빌드 시간과 메모리를 저울질한다.
- 6.0 과 7.0 을 npm 별칭으로 공존시켜 API 의존 도구를 유지한 채 점진 전환한다.
- `--generateTrace` 와 Project References 로 체크 시간을 줄인다.

## 1. tsc 가 느린 구조적 이유

TypeScript 컴파일러는 2012년 이래 TypeScript 로 작성되어 Node.js 위에서 돌았다. 생태계 통합에는 탁월했지만 성능 천장을 만들었다. JavaScript 는 사실상 단일 스레드이고, Worker threads 는 힙을 공유하지 못해 수백만 노드짜리 AST·심볼 그래프를 넘기려면 직렬화 비용이 이득을 삼킨다. 게다가 모든 AST 노드·심볼·타입이 JS 객체라 히든 클래스와 포인터 간접 참조가 겹치고, 세대별 GC 가 수 GB 힙을 반복 스캔한다.

타입 체크 자체도 본질적으로 무겁다. 구조적 타이핑이라 `A` 가 `B` 에 할당 가능한지 보려면 이름이 아니라 멤버를 재귀 비교하고 그 결과를 assignability / subtype / identity 캐시에 쌓아야 한다. 제네릭은 인스턴스화마다 새 타입을 만들고, 조건부·매핑 타입이 겹치면 인스턴스화가 폭발한다.

```text
$ tsc -p . --noEmit --extendedDiagnostics   # Sentry 레포, TypeScript 5.8
Types:                       999619
Instantiations:             3675199
Assignability cache size:    944737
Check time:                  63.26s
Total time:                  72.81s
```

72초 중 63초가 체크다. 파싱이나 I/O 를 최적화해도 체크가 단일 코어에 묶여 있으면 전체 시간은 거의 줄지 않는다. 이것이 "번들러를 바꿔도 CI 는 안 빨라지는" 현상의 정체다.

## 2. 왜 Go 인가

2025년 3월 네이티브 포팅 발표 당시 첫 질문은 "왜 Rust 가 아닌가"였다. 답은 성능 상한이 아니라 이식 비용이다. 전략이 재작성이 아니라 **1:1 이식(port)** 이었다. 함수 구조, 변수명, 알고리즘, 정보 등록 순서까지 유지한 채 언어만 바꾼다. 그래야 십수 년치 기존 테스트가 그대로 통과하고, 두 구현이 같은 에러를 같은 위치에 같은 메시지로 낸다.

이 전략에는 GC 언어가 필수다. TypeScript 의 심볼·타입 그래프는 순환 참조가 일상이라 Rust 로 옮기려면 `Rc<RefCell<...>>` 나 arena 인덱스로 자료구조를 재설계해야 하고, 그 순간 1:1 이식은 무너진다. Go 는 포인터 그래프를 그대로 옮길 수 있고, goroutine 이라는 경량 동시성 원시와 무엇보다 **공유 메모리 병렬성**을 준다. 워커가 같은 AST 를 포인터로 읽는다는 점이 JS 워커 스레드와의 결정적 차이다. trade-off 는 명확하다. Go 의 GC 는 여전히 GC 이고 Rust 급 튜닝 여지는 없다. 팀은 그 몇 퍼센트 대신 1년 내 이식 완료와 8~12배를 택했다.

## 3. 컴파일러 파이프라인

`tsc` 는 다섯 단계를 거친다.

| 단계 | 하는 일 | `--extendedDiagnostics` 지표 |
| --- | --- | --- |
| Scanner | 소스를 토큰으로 분해 | Parse time 에 포함 |
| Parser | 토큰을 AST 로, 문법 오류 보고 | `Parse time`, `Identifiers` |
| Binder | 스코프·심볼 테이블, 선언 병합, 제어 흐름 그래프 | `Bind time`, `Symbols` |
| Checker | 타입 해석·추론·할당 가능성 검사 | `Check time`, `Types`, `Instantiations`, cache size |
| Transformer/Emitter | 타입 제거, `.js` / `.d.ts` / 소스맵 출력 | `Emit time`, `printTime` |

```bash
npx tsc -p tsconfig.json --noEmit --extendedDiagnostics  # 단계별 분해
npx tsc -p tsconfig.json --noEmit --diagnostics          # 요약만
```

읽는 요령. `Program time` 이 크면 모듈 해석과 I/O 문제이니 `include` 와 `paths` 를 의심한다. `Check time` 이 지배적인데 `Instantiations` 가 수백만이면 제네릭 설계 문제다. `Memory used` 가 힙 한계에 근접하면 GC 가 시간을 먹으므로 Project References 로 쪼갠다. 같은 레포의 네이티브 구현 공식 측정은 `Check time` 63.26초 → 5.882초, 총 72.81초 → 6.761초였다. 흥미롭게도 `Instantiations` 는 3,675,199 → 6,524,885 로 오히려 늘었다. 병렬 체커가 공통 작업을 중복 수행하기 때문이며, 절대 연산량이 늘어도 벽시계 시간은 10배 줄어든다.

## 4. 병렬화 전략

TypeScript 7.0 은 파싱·타입 체크·이미트를 모두 병렬로 수행한다. 파싱과 이미트는 파일 단위로 독립적이라 코어 수에 거의 선형 확장된다. 문제는 체크다. 대부분의 파일이 의존성과 전역 스코프의 같은 타입 정보에 기대므로 완전히 독립적으로 돌리면 낭비가 크다. 게다가 타입 체크는 정보가 등록되는 **상대적 순서**에 결과가 좌우되는 지점이 있어, 재현성을 위해 항상 같은 순서로 같은 파일을 봐야 한다. 해법은 고정 개수의 체커 워커다. 각 워커는 공유 불변 AST 위에 자기 타입 캐시를 두고, 입력 파일을 항상 동일하게 분할한다. 중복 작업은 감수하되 결정성은 보장한다.

```bash
npx tsc -p tsconfig.json --noEmit                  # 기본값: 체커 4개
npx tsc -p tsconfig.json --noEmit --checkers 8     # 코어 많은 개발 머신
npx tsc -p tsconfig.json --noEmit --checkers 1     # 빠듯한 CI 러너, 중복 제거
npx tsc --build --builders 4 --checkers 2          # 모노레포 병렬 빌드
npx tsc -p tsconfig.json --noEmit --singleThreaded # 디버깅·성능 비교
```

2026-07-08 릴리스 포스트의 공식 측정에서 `--checkers 4`(기본)와 `8` 의 차이는 이렇다.

| 레포 | TS 6 | TS 7 (checkers 4) | TS 7 (checkers 8) |
| --- | --- | --- | --- |
| vscode | 125.7s | 10.6s (11.9x) | 7.51s (16.7x) |
| sentry | 139.8s | 15.7s (8.9x) | 12.08s (11.6x) |
| bluesky | 24.3s | 2.8s (8.7x) | 2.01s (12.1x) |
| playwright | 12.8s | 1.47s (8.7x) | 1.16s (11x) |
| tldraw | 11.2s | 1.46s (7.7x) | 1.06s (10.6x) |

메모리는 총량 기준 6~26% 감소했다(vscode 5.2GB → 4.2GB). trade-off 는 셋이다. `--checkers` 를 늘리면 메모리도 오른다. `--builders` 와 곱해져 `--checkers 4 --builders 4` 는 최대 16개 체커를 띄운다. 드물게 체커 수에 따라 순서 의존적 결과가 드러나므로 팀 전체가 값을 고정하는 편이 안전하다. `--builders` 는 결과를 바꾸지 않지만 의존 그래프가 병목이라 무한정 늘려도 소용없다.

## 5. 실제 사용법

2026-09-10 현재 TypeScript 7.0 은 정식 출시(2026-07-08)되어 `typescript` 패키지에서 받는다. `tsgo` 는 프리뷰 시기의 임시 명칭이었고, 7.0 RC 부터 실행 파일 이름이 `tsc` 로 통일되었다.

```bash
npm install -D typescript && npx tsc --version   # 정식 채널
npm install -D typescript@next                   # 나이틀리
npm install -D @typescript/native-preview && npx tsgo -p tsconfig.json  # 구 프리뷰 채널
```

기존 구현과 대조하는 절차다. 에러 텍스트를 정렬해 비교하면 순서 차이에 흔들리지 않는다.

```bash
npx tsc6 -p tsconfig.json --noEmit --pretty false 2>&1 | sort > /tmp/ts6.txt
npx tsc  -p tsconfig.json --noEmit --pretty false 2>&1 | sort > /tmp/ts7.txt
diff -u /tmp/ts6.txt /tmp/ts7.txt && echo "PARITY OK"
```

CI 에서는 한동안 이중 실행을 권한다. `tsc6` 잡을 게이트로 유지해 실패 시 머지를 막고, `tsc` 잡은 `continue-on-error` 로 두어 차이만 관찰한다.

```yaml
      - name: gate (TypeScript 6)
        run: npx tsc6 -p tsconfig.json --noEmit
      - name: shadow (TypeScript 7)
        continue-on-error: true
        run: npx tsc -p tsconfig.json --noEmit --checkers 2 --extendedDiagnostics
```

몇 주간 shadow 가 무결하면 두 잡을 뒤집어 7.0 을 게이트로 승격한다.

## 6. 에디터 경험

에디터 지연의 원인은 빌드와 다르다. VS Code 는 `tsserver` 라는 별도 Node 프로세스와 JSON 프로토콜로 통신해 왔다. LSP 보다 먼저 만들어진 고유 규격이다. 파일을 열면 tsserver 가 `tsconfig.json` 을 찾아 프로젝트를 구성하고, 모든 파일과 `node_modules` 의 `.d.ts` 를 읽어 Program 을 만든 뒤에야 체크를 시작한다. 대형 모노레포에서는 이 초기 로딩만 수십 초이고, 설정이 바뀌면 Program 재생성이 일어난다. 단일 스레드라 자동 완성 요청이 진행 중인 진단 계산 뒤에 줄을 선다.

네이티브 구현은 LSP 로 전환해 표준 경로를 타고, 여러 스레드로 동시 요청을 처리하며, 프로그램 구성 자체가 네이티브 속도로 끝난다. 공식 수치로 VS Code 레포에서 첫 에러가 보이기까지 17.5초가 1.3초 미만이 됐다(13배 이상). Canva 는 약 58초 → 4.8초, Slack 은 CI 타입 체크 7.5분 → 1.25분에 머지 큐 대기 40%가 사라졌다고 보고했다. 실패 명령은 80% 이상, 크래시는 60% 이상 줄었다. `--watch` 는 `@parcel/watcher` 를 Go 로 이식한 새 감시 기반 위에 다시 만들어졌다.

## 7. 마이그레이션 시 확인 사항

7.0 의 가장 큰 제약은 **프로그래밍 API 가 아직 없다**는 점이다. 새 API 는 7.1 목표이며(현재 계획 기준 2026-11월대), `ts-morph`·커스텀 트랜스포머·webpack loader·`typescript-eslint` 처럼 `typescript` 를 직접 import 하는 도구는 당분간 6.0 이 필요하다. 팀은 `tsc6` 실행 파일과 6.0 API 를 함께 제공하는 `@typescript/typescript6` 를 내놓았다. npm 별칭으로 공존시킨다.

```json
{
  "devDependencies": {
    "@typescript/native": "npm:typescript@^7.0.2",
    "typescript": "npm:@typescript/typescript6@^6.0.2"
  }
}
```

설정 측면에서 7.0 은 6.0 의 기본값과 폐기 정책을 계승하되, 폐기 옵션은 경고가 아니라 **하드 에러**다.

| 항목 | 변경 | 대응 |
| --- | --- | --- |
| `strict` | 기본 `true` | 점진 도입이면 명시적으로 `false` 유지 |
| `module` | 기본 `esnext` | 번들러 환경은 `preserve` 검토 |
| `types` | 기본 `[]` | `["node","jest"]` 처럼 명시 |
| `rootDir` | 기본 `./` | `tsconfig` 가 `src` 밖이면 `"rootDir": "./src"` |
| `target: es5`, `downlevelIteration` | 제거 | 다운레벨은 번들러에 위임 |
| `moduleResolution: node/node10/classic` | 제거 | `nodenext` / `bundler` |
| `module: amd/umd/systemjs/none` | 제거 | `esnext` / `preserve` |
| `baseUrl` | 제거 | `paths` 를 루트 기준으로 재작성 |

JS + JSDoc 지원은 이식이 아니라 재작성되었다. Closure 스타일 `function(string): void`, `@enum`, `@class`, 후위 `!`, 단독 `?` 가 더 이상 특별 취급되지 않는다. 템플릿 리터럴 타입의 `infer` 도 UTF-16 코드 유닛이 아니라 코드 포인트 단위로 동작하므로 문자열 `Length` 유틸 같은 트릭은 재검증이 필요하다. Vue·Svelte·Astro·MDX(Volar 계열)와 Angular 템플릿 체크는 아직 6.0 이 필요해, CLI 는 7.0 에디터는 6.0 이라는 절충이 현실적이다.

권장 순서. (1) 6.0 으로 올려 폐기 경고를 해소한다. (2) CI 에 shadow 잡으로 7.0 을 붙여 diff 를 0 으로 만든다. (3) 게이트를 7.0 으로 옮기고 6.0 은 API 소비 도구 전용으로 남긴다.

## 8. 대안과 비교

반드시 분리해서 봐야 한다. **트랜스파일**과 **타입 체크**는 다른 작업이다.

| 도구 | 타입 제거 | 타입 체크 | 비고 |
| --- | --- | --- | --- |
| esbuild | O | X | 파일 단위 변환 |
| swc | O | X | Rust 구현, Next.js/Jest 등에 내장 |
| Babel (`preset-typescript`) | O | X | 파일 독립 변환 전제 |
| Bun / Deno | O | X | 실행 시 타입 무시가 기본 |
| tsc / TypeScript 7 | O | O | 프로그램 전체를 봐야 함 |

esbuild·swc·Babel 이 빠른 근본 이유는 **파일 하나만 보고 타입 주석을 지우기 때문**이다. 타입 체크는 원리상 그럴 수 없다. 어떤 표현식의 타입을 알려면 그 파일이 import 한 모듈, 그 모듈이 import 한 모듈, `lib.d.ts` 전역까지 전부 필요하다. 그래서 이 도구들을 도입해도 "빌드는 빨라졌는데 CI 는 그대로"인 상황이 벌어진다. 병목은 `tsc --noEmit` 잡이었던 것이다. `vue-tsc` 류 래퍼는 처지가 더 곤란하다. tsc 를 감싸 SFC 를 가상 TS 파일로 바꾸는 구조라 컴파일러 API 에 묶여 있어, 7.x 가 API 를 내놓기 전에는 이득이 없다.

결론은 **역할 분리**다. 번들과 개발 서버는 esbuild/swc 에 맡기고, 정확성 게이트는 `tsc --noEmit` 으로 따로 세운다.

## 9. 팀이 지금 할 수 있는 실질적 최적화

네이티브 컴파일러를 기다리지 않아도 얻을 개선이 있다. 효과가 큰 순서다.

**Project References**. Program 하나를 쪼개면 각 프로젝트가 의존 프로젝트의 `.d.ts` 만 읽고, 변경 없는 프로젝트는 통째로 건너뛴다. `isolatedDeclarations` 를 켜면 선언 파일을 체크 없이 문법만으로 생성해 병렬화 여지가 커진다.

```json
{
  "compilerOptions": {
    "composite": true, "incremental": true, "declaration": true,
    "isolatedDeclarations": true, "skipLibCheck": true
  },
  "references": [{ "path": "../core" }, { "path": "../ui" }]
}
```

**`skipLibCheck`**. `node_modules` 안 `.d.ts` 들끼리의 상호 검증을 생략한다. 의존성이 많을수록 체감이 크고, 잃는 것은 남의 타입 정의 속 오류를 발견할 기회뿐이다.

**`incremental`**. `.tsbuildinfo` 를 남겨 두 번째 실행부터 변경 영향 범위만 본다. CI 에서는 이 파일을 캐시해야 의미가 있다.

**인스턴스화 폭발 줄이기**. 중첩 조건부 타입, 재귀 매핑 타입, 거대 유니온의 분배는 인스턴스화 수를 곱셈으로 키운다. 라이브러리 경계 함수에 명시적 반환 타입을 달면 추론 체인이 끊겨 체크 시간이 눈에 띄게 줄기도 한다.

**`--generateTrace` 로 병목 지목**. 추측 대신 측정한다.

```bash
npx tsc -p tsconfig.json --noEmit --generateTrace ./trace
# trace.json 을 chrome://tracing 또는 ui.perfetto.dev 에 올리고, types.json 에서 큰 타입 ID 를 역추적
```

`checkSourceFile` 블록이 유난히 긴 파일, `structuredTypeRelatedTo` 가 반복되는 구간을 찾으면 문제 타입이 드러난다. 여기서 얻은 개선은 6.0 이든 7.0 이든 유효하다. 네이티브 컴파일러는 상수 배수를 줄여 줄 뿐, 알고리즘적으로 나쁜 타입은 10배 빠른 컴파일러에서도 나쁘다.

## 참고

- [A 10x Faster TypeScript — Microsoft TypeScript Blog (2025-03-11)](https://devblogs.microsoft.com/typescript/typescript-native-port/)
- [Announcing TypeScript Native Previews (2025-05-22)](https://devblogs.microsoft.com/typescript/announcing-typescript-native-previews/)
- [Announcing TypeScript 7.0 (2026-07-08)](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/)
- [Announcing TypeScript 6.0 — 폐기 목록과 기본값 변경](https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/)
- [microsoft/typescript-go — 기능별 이식 상태표](https://github.com/microsoft/typescript-go)
- [typescript-go CHANGES.md — 6.0 대비 의도된 동작 차이](https://github.com/microsoft/typescript-go/blob/main/CHANGES.md)
- [TypeScript Wiki — Performance 가이드](https://github.com/microsoft/TypeScript/wiki/Performance)
- [TypeScript Handbook — Project References](https://www.typescriptlang.org/docs/handbook/project-references.html)
- [TypeScript 7.1 Iteration Plan (issue #63703)](https://github.com/microsoft/TypeScript/issues/63703)
