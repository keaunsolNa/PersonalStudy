Notion 원본: https://www.notion.so/3b55a06fd6d381b2a040df15ebd71c14

# TypeScript 컴파일 성능 진단과 generateTrace 및 인스턴스화 폭발 최적화
> 2026-08-07 신규 주제 · 확장 대상: TypeScript (Compiler API·Project References 기학습)

## 학습 목표
- tsc 컴파일 파이프라인의 단계별 비용 구조를 분해하고 Check time 지배 현상의 원인을 규명한다
- --extendedDiagnostics 와 --generateTrace 산출물을 Perfetto·analyze-trace 로 해석해 병목 타입을 특정한다
- 타입 인스턴스화 폭발의 전형 패턴을 식별하고 interface 캐싱·지연 평가·어노테이션 차단 등으로 완화한다
- skipLibCheck·incremental·Project References·네이티브 컴파일러(tsgo) 등 빌드 구성 차원의 최적화 수단을 비교 선택한다

## 1. tsc 컴파일 파이프라인과 비용의 분포

tsc 는 크게 네 단계로 동작한다. 파서(Parser)가 소스 텍스트를 AST 로 변환하고, 바인더(Binder)가 각 선언에 Symbol 을 부여하며 스코프 체인을 구성한다. 이어 체커(Checker)가 타입을 계산·비교하며 진단을 생성하고, 에미터(Emitter)가 JS·d.ts·소스맵을 출력한다. 파서와 바인더는 파일당 한 번씩 입력 크기에 거의 비례해 수행되므로 예측 가능하지만, 체커는 타입 관계 질의에 따라 비용이 비선형적으로 증가할 수 있는 유일한 단계다.

실무 프로젝트에서 `--diagnostics` 를 찍어 보면 대부분 Check time 이 Total time 의 60~80% 수준을 차지한다. 체커의 진입점은 `checkSourceFile` 이며, 이 함수가 파일 내 모든 문장을 순회하면서 `checkExpression`, 제네릭 해석의 핵심인 `instantiateType`, 관계 비교의 핵심인 `checkTypeRelatedTo` 를 재귀 호출한다. 트레이스의 최상위 스팬은 거의 항상 `checkSourceFile` 이고 그 아래 특정 표현식 하나가 수백 ms 를 차지하는 형태가 흔하다. 즉 "느린 파일"이 아니라 "느린 표현식·느린 타입"을 찾는 것이 진단의 본질이다.

체커의 중요한 특성은 지연 평가다. 타입은 참조 전까지 계산되지 않고 결과는 내부 캐시에 저장되므로, 동일 제네릭 조합은 재사용되지만 타입 인자가 조금씩 다른 조합이 대량 생성되면 캐시 미스가 누적되어 인스턴스화 횟수와 메모리가 함께 폭증한다.

```bash
# 단계별 시간 요약
npx tsc --noEmit --diagnostics

# 심층 지표 포함
npx tsc --noEmit --extendedDiagnostics
```

## 2. --extendedDiagnostics 지표 해석법

`--extendedDiagnostics` 는 컴파일 1회의 내부 카운터를 그대로 노출한다. 핵심 지표의 의미와 위험 신호는 다음과 같다.

| 지표 | 의미 | 위험 신호(경험적 기준) |
|---|---|---|
| Files / Lines | 프로그램에 포함된 파일·라인 수 | 예상보다 크면 include 범위·중복 node_modules 의심 |
| Identifiers / Symbols | 파싱된 식별자, 바인더가 만든 심벌 수 | Symbols 가 Identifiers 대비 과도하면 선언 병합·재export 과다 |
| Types | 체커가 생성한 Type 객체 수 | 라인 수 대비 수 배 이상이면 타입 수준 연산 과다 |
| Instantiations | 제네릭 타입 인스턴스화 횟수 | 수백만 단위면 인스턴스화 폭발 가능성 큼 |
| Memory used | V8 힙 사용량 | 2~3GB 접근 시 OOM·GC 스톨 위험 |
| Check time | 체커 소요 시간 | Total 의 80% 초과 시 타입 구조 개선이 최우선 |

특히 Instantiations 는 코드 변경 전후 비교에 가장 민감한 지표다. 대형 유니온을 조건부 타입에 통과시키는 유틸리티 하나를 추가했는데 Instantiations 가 30만에서 500만으로 뛰었다면 그 유틸리티가 분배(distribution)를 유발했다는 강한 증거다. Types 와 Instantiations 는 절대값보다 라인 수 대비 비율과 커밋 전후 증감으로 읽는 것이 실용적이며, CI 에서 기록해 두면 성능 회귀를 리뷰 단계에서 잡을 수 있다.

```bash
# CI 용 성능 회귀 감시 예시 (수치만 추출)
npx tsc --noEmit --extendedDiagnostics | grep -E "Types|Instantiations|Check time|Memory"
```

주의할 점은 이 지표가 전체 합계라는 것이다. 어느 파일·어느 타입이 원인인지는 알려 주지 않으므로, 원인 특정은 다음 절의 트레이스로 넘어가야 한다.

## 3. --generateTrace 와 Perfetto·analyze-trace 분석

TypeScript 4.1 부터 제공되는 `--generateTrace <dir>` 는 체커 내부 이벤트를 Chrome Trace Event Format 으로 기록한다. 지정 디렉터리에 `trace.json`(시간축 이벤트)과 `types.json`(생성된 모든 타입의 메타데이터) 두 파일이 생성된다.

```bash
npx tsc --noEmit --generateTrace ./ts-trace
# ts-trace/trace.json, ts-trace/types.json 생성
```

`trace.json` 은 Perfetto UI(ui.perfetto.dev) 또는 chrome://tracing 에 로드한다. 넓은 `checkSourceFile` 스팬부터 열어 내려가면 `checkExpression` 등 하위 스팬에 소스 경로와 시작 오프셋(pos)이 붙어 있어 어느 표현식이 느린지 라인 단위로 특정할 수 있다. `structuredTypeRelatedTo` 스팬이 넓으면 거대 타입 간 관계 비교가, `instantiateType` 계열이 넓으면 제네릭 전개가 병목이다. `types.json` 은 각 타입의 id·종류·선언 위치를 담아 trace 이벤트의 typeId 를 역추적하는 색인으로 쓴다.

수동 분석이 부담스러우면 공식 보조 도구 `@typescript/analyze-trace` 를 쓴다. 임계값을 넘는 구간을 파일·위치별로 요약해 주고, 중복 로드된 패키지 버전 감지 같은 부가 진단도 수행한다.

```bash
npm i -D @typescript/analyze-trace
npx analyze-trace ./ts-trace
# 출력 예: "Check file /src/api/schema.ts (asdf.ts:120:5) took 4200ms" 류의 핫스팟 목록
```

실측 감각으로는 약 10만 라인 규모 프로젝트에서 trace.json 은 수십 MB, 최상위 핫스팟 하나가 전체 체크 시간의 5% 미만인 것이 보통이다. 특정 표현식 하나가 30~50% 를 차지하면 거의 확실히 다음 절의 폭발 패턴이 존재한다. 트레이싱 자체 오버헤드가 약 10~20% 있으므로 절대 시간보다 상대 비중으로 읽는다.

## 4. 타입 인스턴스화 폭발의 전형 패턴

첫째, 거대 유니온과 분배 조건부 타입의 곱이다. naked type parameter 에 걸린 조건부 타입은 유니온 구성원마다 개별 평가되므로, 구성원 1,000개 유니온이 이런 유틸리티 3개를 연쇄 통과하면 평가 횟수가 곱셈으로 늘어난다.

```typescript
type EventName = `${"user" | "order" | "item"}:${"create" | "update" | "delete"}:${string & {}}`;
// 템플릿 리터럴 교차 곱으로 유니온이 기하급수적으로 커진 상태에서
type PayloadOf<T> = T extends `${infer D}:${infer A}:${string}` ? { domain: D; action: A } : never;
// PayloadOf<EventName> → 각 구성원마다 조건부 평가 + infer 인스턴스화
```

둘째, 재귀 mapped type 이다. `DeepPartial`, `DeepReadonly` 류는 중첩 깊이 × 프로퍼티 수만큼 새 타입을 만들며, 배열·튜플·유니온 분기까지 처리하는 "완전판" 유틸리티는 대형 도메인 모델에서 수십만 인스턴스화를 유발할 수 있다.

```typescript
type DeepPartial<T> = T extends (infer U)[] ? DeepPartial<U>[]
  : T extends object ? { [K in keyof T]?: DeepPartial<T[K]> }
  : T;
```

셋째, 깊은 제네릭 체인이다. 빌더 패턴이나 ORM 쿼리 체인처럼 메서드 호출마다 타입 파라미터가 누적 변형되는 API 는 호출 깊이만큼 인스턴스화가 중첩되며, Zod·tRPC·Prisma 스키마를 수백 개 합성할 때 체크 시간이 급증하는 것이 대표 사례다. 넷째, 함수 오버로드 폭발이다. 오버로드가 수십 개인 함수에 유니온 인자를 넘기면 해석이 시그니처 수 × 유니온 크기로 시도되고 실패 경로에서도 관계 비교 비용이 그대로 발생한다.

컴파일러는 폭주를 상수로 차단한다. 인스턴스화 깊이 한계는 500(초과 시 TS2589 "Type instantiation is excessively deep and possibly infinite"), 단일 검사 내 인스턴스화 횟수 한계는 5,000,000 이며, 유니온 구성원이 100,000개를 넘으면 TS2590 "union type that is too complex to represent" 가 발생한다. 이 오류가 보이면 성능 문제가 임계에 도달했다는 뜻이고, 오류 없이 한계의 수 % 수준인 코드도 에디터 반응성을 체감적으로 망가뜨린다.

## 5. 완화 기법: 캐싱, 축소, 지연, 차단

가장 널리 알려진 처방은 대형 intersection 을 interface extends 로 바꾸는 것이다. interface 는 이름 있는 단일 flat 타입으로 캐싱되어 관계 비교 시 프로퍼티 조회가 한 번에 끝나지만, `A & B & C` intersection 은 비교 시마다 구성 타입을 순회해 매번 재평가에 가까운 비용을 낸다. TypeScript Wiki Performance 문서의 첫 번째 권고이기도 하다.

```typescript
// 느림: 비교마다 구성원 순회
type Props = OwnProps & RouterProps & ThemeProps & I18nProps;

// 빠름: 단일 캐시된 flat 타입
interface Props extends OwnProps, RouterProps, ThemeProps, I18nProps {}
```

둘째, 유니온 크기 자체를 줄인다. 템플릿 리터럴 교차 곱을 피하고, 문자열 리터럴 유니온 대신 브랜드 타입이나 열거형 상수 객체로 표현을 바꾸면 구성원 수가 곱셈에서 덧셈으로 내려간다. 셋째, 조건부 타입의 지연 평가를 활용한다. 조건부 타입은 판정에 필요한 시점까지 분기를 평가하지 않으므로, 무거운 계산을 조건부 분기 안쪽으로 밀어 넣거나 `T extends any ? Heavy<T> : never` 형태로 분배 지점을 통제하면 실제 사용되는 조합만 인스턴스화된다.

넷째, 명시적 타입 어노테이션으로 추론을 차단한다. 거대한 리터럴 객체나 복잡한 함수 반환값에 어노테이션을 붙이면 전체 구조 추론 대신 선언 타입과의 단방향 할당 검사만 수행되고 d.ts 출력 시 타입 재구성 비용도 사라진다. 대형 config 객체 하나에 어노테이션을 추가해 Check time 이 수 초 단위로 줄어드는 사례가 보고된다.

```typescript
// 추론 방치: schema 전체 구조를 체커가 재구성
export const routes = buildRoutes(bigSchema);

// 추론 차단: 비교 방향과 범위가 고정됨
export const routes: RouteTable = buildRoutes(bigSchema);
```

마지막으로 재귀 유틸리티에는 깊이 카운터 튜플로 상한을 두어 TS2589 한계(깊이 500) 훨씬 아래에서 멈추게 설계하는 방어 기법도 쓰인다. trade-off 는 명확하다. interface 화는 선언 병합 가능성이라는 의미 변화를, 어노테이션 차단은 리터럴 좁히기 정보 손실을, 유니온 축소는 자동완성 품질 저하를 동반할 수 있으므로 trace 로 병목을 확인한 뒤 국소 적용해야 한다.

## 6. 빌드 구성 옵션이 Check time 에 주는 영향

`skipLibCheck: true` 는 모든 `.d.ts` 파일 내부의 정합성 검사를 생략한다. 선언 파일이 무거운 프로젝트(RxJS·AWS SDK 등 의존)에서는 전체 시간의 30~50% 가 줄어드는 경우도 있다. 대가는 라이브러리 간 타입 충돌을 놓칠 수 있다는 것으로, 앱에서는 켜고 라이브러리 CI 에서는 끄는 이원화가 실무 표준이다.

`incremental: true` 는 `.tsbuildinfo` 에 파일 해시와 의존 그래프를 저장해 재컴파일 시 영향받은 파일만 다시 체크한다. 콜드 빌드는 오히려 5~10% 느려질 수 있으나 웜 빌드는 수 배 빨라진다. 다만 널리 참조되는 공용 타입을 수정하면 사실상 전체 재체크로 퇴화한다.

`isolatedModules: true` 는 체크 시간을 직접 줄이지 않지만 각 파일을 단독 트랜스파일 가능하게 강제해 swc·esbuild 로의 변환 위임을 안전하게 만든다. TypeScript 5.5의 `isolatedDeclarations: true` 는 export 경계 타입을 어노테이션만으로 결정 가능하게 강제해 체커 없이 d.ts 를 생성하는 병렬 선언 빌드를 가능케 하며, 대가는 public API 전반의 어노테이션 작성 비용이다.

Project References 는 저장소를 하위 프로젝트로 분할해 `tsc --build` 가 변경 프로젝트와 그 하류만 재빌드하게 한다. 프로그램 크기가 작아져 메모리 문제도 완화되지만 `composite: true` 와 declaration 출력 강제, 순환 금지라는 구조적 제약이 따른다.

```jsonc
// tsconfig.json 발췌
{
  "compilerOptions": {
    "skipLibCheck": true,
    "incremental": true,
    "tsBuildInfoFile": "./.cache/tsbuildinfo",
    "isolatedModules": true
  }
}
```

## 7. tsc 대체 도구와 타입체크 분리 전략

swc(Rust)와 esbuild(Go)는 타입을 검사하지 않고 문법만 제거하는 트랜스파일러로, 변환 속도는 tsc 대비 대략 20~70배 수준으로 보고되며 Vite·Next.js·Jest(@swc/jest) 등이 기본 채택하고 있다. 핵심 전략은 "변환과 검사의 분리"다. 개발 서버와 테스트는 swc/esbuild 로 즉시 변환하고 타입 검사는 별도 프로세스(`tsc --noEmit`)를 워치 모드나 CI 로 돌린다. `isolatedModules` 가 전제 조건이며 const enum·namespace 병합 등 단독 파일 변환 불가 문법은 포기해야 한다.

2025년 3월 Microsoft 가 공개한 typescript-go(코드네임 Corsa, CLI 는 `tsgo`)는 컴파일러를 Go 로 포팅한 프로젝트로, 동일한 검사 의미론을 유지하며 네이티브 실행과 병렬화로 약 10배 수준의 향상을 목표로 한다. VS Code 저장소 전체 체크가 수십 초에서 수 초 대로 줄어든 공식 벤치마크가 있고 TypeScript 7 의 기반이 될 예정이다. 그 전까지는 다음과 같은 분리 구성이 실용적 표준이다.

```bash
# 개발: 변환은 esbuild(Vite), 검사는 병렬 프로세스
npm i -D vite-plugin-checker
# vite.config.ts 에서 checker({ typescript: true }) 등록

# CI: 검사와 번들을 독립 잡으로 분리
npx tsc --noEmit --incremental &  # 타입 게이트
npx esbuild src/index.ts --bundle --outdir=dist  # 산출물
```

trade-off 로, 검사 분리는 "타입 오류가 있어도 실행은 된다"는 상태를 허용하므로 CI 게이트를 반드시 유지해야 하고, tsgo 는 아직 tsc 와의 기능 패리티(일부 API·에디터 통합)가 진행 중이므로 프리뷰 채널(@typescript/native-preview)로 검증 후 도입하는 것이 안전하다.

## 8. 에디터(tsserver) 성능과 상호작용 비용

CLI 빌드가 빨라도 에디터가 느리면 개발 경험은 나쁘다. tsserver 는 열린 파일 중심으로 동작하며, 프로젝트 로드 직후에는 부분 의미 모드(partial semantic mode)로 문법·개요 기능만 우선 제공하고 전체 로드가 끝난 뒤 완전한 의미 분석으로 전환한다. 대형 저장소를 열었을 때 초기 몇 초간 자동 import 가 불완전한 것이 이 모드 때문이다.

진단(diagnostics)은 키 입력마다가 아니라 지연(debounce) 후 비동기로 계산되지만, 단일 요청 자체가 무거우면 지연으로도 감출 수 없다. 거대 유니온 타입 위의 hover(quickinfo)는 유니온 전체를 문자열로 직렬화해야 하므로 수백 ms~수 초가 걸릴 수 있고 자동완성도 동일한 타입 전개 비용을 치른다. 4절의 폭발 패턴은 빌드보다 에디터에서 먼저 체감되는 경우가 많다.

진단 도구로는 VS Code 명령 "TypeScript: Open TS Server Log"(`tsserver.log`)와 tsserver 의 `--traceDirectory` 가 있다. 로그에서 `updateGraph`(프로그램 재구성)와 개별 요청의 elapsed 를 보면 어떤 편집이 전체 재계산을 유발하는지 알 수 있다. 완화책은 CLI 와 대체로 같되, References 분할로 tsserver 가 여는 프로그램 크기를 줄이고 `typescript.tsserver.maxTsServerMemory` 를 상향(기본 약 3GB)하며 거대 생성 파일은 exclude 로 관측 범위에서 제외한다. 궁극적으로는 hover 가 느린 타입을 이름 있는 interface 로 바꿔 직렬화 결과를 짧게 만드는 것이 근본 처방이다.

| 증상 | 유력 원인 | 1차 조치 |
|---|---|---|
| 저장 후 오류 갱신 수 초 지연 | 체크 자체가 느림 | trace 로 핫스팟 특정 후 5절 기법 |
| hover 표시가 멈춤 | 거대 유니온·익명 타입 직렬화 | 이름 있는 타입으로 치환 |
| 프로젝트 열기 후 장시간 로딩 | 프로그램 과대 | References 분할, exclude 정리 |
| tsserver OOM 재시작 | 메모리 한계 도달 | maxTsServerMemory 상향 + 타입 축소 |

## 참고
- TypeScript Wiki – Performance: https://github.com/microsoft/TypeScript/wiki/Performance
- TypeScript Wiki – Performance Tracing: https://github.com/microsoft/TypeScript/wiki/Performance-Tracing
- @typescript/analyze-trace: https://github.com/microsoft/typescript-analyze-trace
- typescript-go (네이티브 포트, tsgo): https://github.com/microsoft/typescript-go
- A 10x Faster TypeScript (Microsoft Devblogs): https://devblogs.microsoft.com/typescript/typescript-native-port/
- TSConfig Reference (skipLibCheck·incremental·isolatedDeclarations): https://www.typescriptlang.org/tsconfig/
- Project References Handbook: https://www.typescriptlang.org/docs/handbook/project-references.html
- Perfetto UI: https://ui.perfetto.dev/
