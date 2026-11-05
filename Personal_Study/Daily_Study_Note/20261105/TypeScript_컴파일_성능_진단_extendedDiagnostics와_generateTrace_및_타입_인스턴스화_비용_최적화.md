Notion 원본: https://app.notion.com/p/40f5a06fd6d3819fb7e7e36bfa3c344d

# TypeScript 컴파일 성능 진단 extendedDiagnostics와 generateTrace 및 타입 인스턴스화 비용 최적화

> 2026-11-05 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `--extendedDiagnostics` 출력 항목을 읽고 병목 단계(Parse · Bind · Check)를 구분한다
- `--generateTrace`와 analyze-trace로 느린 타입 검사 지점을 특정한다
- 인스턴스화 폭증을 부르는 타입 패턴을 식별해 구조를 바꾼다
- tsconfig 옵션으로 프로그램 크기와 증분 비용을 줄인다

## 1. 컴파일 시간은 어디에 쓰이는가

tsc의 작업은 파일 탐색과 읽기(I/O), 파싱(Parse), 바인딩(Bind), 타입 검사(Check), 출력(Emit)으로 나눈다. 대형 프로젝트에서 대부분의 시간은 Check에 쓰인다. 따라서 성능 최적화는 먼저 어느 단계가 지배적인지 측정하는 일에서 시작한다. 측정 없이 `skipLibCheck`부터 켜는 것은 순서가 거꾸로다.

가장 먼저 쓰는 도구는 `tsc --extendedDiagnostics --noEmit`이다. 출력에는 Files, Lines of Library/Definitions/TypeScript, Nodes, Identifiers, Symbols, Types, Instantiations, Memory used, I/O Read time, Parse time, ResolveModule time, Bind time, Check time, Emit time, Total time이 들어 있다. 프로그램 크기(Files, Lines)가 비정상적으로 크면 포함 범위 문제이고, Types와 Instantiations가 Files 대비 과도하면 타입 레벨 복잡도 문제다.

```bash
tsc -p tsconfig.json --noEmit --extendedDiagnostics
# 확인 포인트
# 1) Files / Lines of Definitions: node_modules/@types 자동 포함 여부
# 2) Instantiations: 제네릭 인스턴스 생성 횟수 (수백만 이상이면 의심)
# 3) Check time vs Total time: 검사 비중
```

## 2. 프로그램 크기부터 줄이기

`--listFiles`로 컴파일에 포함된 파일 전체를, `--explainFiles`로 각 파일이 포함된 이유를 확인한다. 의도하지 않은 `node_modules/@types/*` 전체 포함은 `compilerOptions.types`를 명시해 막을 수 있다(`"types": ["node"]`). `include`가 테스트·빌드 산출물 디렉터리까지 잡고 있다면 `exclude`를 조정한다. 모노레포에서는 Project References로 패키지별 프로그램을 분리해야 전체 재검사를 피할 수 있다.

```bash
tsc --noEmit --explainFiles | head -50
tsc --noEmit --listFiles | wc -l
```

`skipLibCheck: true`는 `.d.ts` 파일의 내부 일관성 검사를 건너뛰어 시간을 줄이지만, 라이브러리 선언의 충돌을 놓칠 수 있다는 trade-off가 있다. 애플리케이션 프로젝트에서는 흔히 켜고, 라이브러리를 배포하는 프로젝트에서는 CI 한 곳에서 꺼서 검증하는 구성이 일반적이다.

## 3. generateTrace로 핫스팟 찾기

`--generateTrace <디렉터리>`는 검사 과정을 trace.json(Chrome 트레이스 이벤트 형식)과 types.json으로 남긴다. trace.json은 Perfetto나 `chrome://tracing`에 열어 파일별 검사 시간을 시각적으로 볼 수 있고, 공식 도구 `@typescript/analyze-trace`는 오래 걸린 파일·표현식 위치를 텍스트로 요약한다.

```bash
rm -rf .trace && tsc -p tsconfig.json --noEmit --generateTrace .trace
npx @typescript/analyze-trace .trace
```

보고서는 "Hot Spots"로 검사 시간이 큰 파일과 노드를 보여주고, 일부는 어떤 타입 인스턴스화가 깊게 재귀되는지 types.json의 id로 가리킨다. types.json에서 해당 id를 찾으면 문제의 타입 alias 이름과 위치가 나온다. 트레이스 생성 자체에 오버헤드가 있으므로 절대 시간보다 상대적 순위를 본다.

## 4. 인스턴스화 폭증 패턴

타입 검사 비용은 대체로 제네릭 인스턴스화 횟수와 구조 비교 깊이에서 나온다. 공식 성능 문서가 권고하는 패턴은 다음과 같다. 첫째, 타입 합성에 intersection(`&`) 대신 interface의 `extends`를 쓴다. interface는 이름으로 캐시되고 속성 충돌을 조기에 검출하지만, intersection은 매번 구조적으로 평가된다. 둘째, 함수의 반환 타입을 명시한다. 추론에 의존하면 선언 파일 생성 시 타입이 인라인으로 펼쳐지고 검사 비용도 커진다. 셋째, 거대한 유니온을 피한다. 유니온 멤버가 n개이면 할당 비교가 대략 n 제곱에 가까워질 수 있다. 넷째, 복잡한 조건부 타입에 이름을 붙여 캐시 가능하게 한다.

```ts
// 나쁜 예: 호출마다 구조 비교가 일어나는 intersection 누적
type Admin = User & Auditable & Timestamped & SoftDeletable;

// 좋은 예: 이름이 붙은 interface 상속은 캐시된다
interface Admin extends User, Auditable, Timestamped, SoftDeletable {}

// 반환 타입 명시
export function buildQuery(opts: QueryOptions): Query {
  return new Query(opts);
}
```

재귀 조건부 타입은 깊이 제한(인스턴스화 깊이 약 100, 꼬리 재귀 최적화 시 1000)에 걸리거나 시간이 급증한다. 템플릿 리터럴 타입으로 큰 문자열 유니온의 카르테시안 곱을 만드는 패턴도 위험하다. 곱 크기가 10만 단위를 넘으면 컴파일러가 `Expression produces a union type that is too complex to represent` 오류를 낸다.

## 5. 증분 빌드와 캐시

`incremental: true`와 `tsBuildInfoFile`은 이전 빌드의 프로그램 구조와 파일 해시를 저장해 변경 파일과 그 의존 파일만 재검사한다. CI에서는 `.tsbuildinfo`를 캐시 대상으로 포함하고 TypeScript 버전을 캐시 키에 넣어야 버전 변경 시 오염을 피한다. 모노레포에서는 `composite: true`와 `tsc -b`로 패키지 단위 증분을 구성한다.

## 6. 에디터와 CLI의 차이

에디터의 언어 서비스는 별도 프로세스(tsserver)로 동작하며 `tsserver.log`에 시간이 기록된다. VS Code에서는 `typescript.tsserver.log` 설정을 켜고 `TypeScript: Open TS Server log`로 열어 느린 요청을 확인한다. CLI 빌드는 빠른데 에디터만 느리다면 대개 열린 파일의 자동 import 후보 계산이나 거대한 선언 파일 하나가 원인이다. `typescript.preferences.includePackageJsonAutoImports` 설정을 `off`로 바꿔 비교해 보는 방법이 있다.

## 7. 측정 기반 개선 절차

개선은 다음 순서로 진행하면 회귀를 막을 수 있다. 베이스라인으로 `--extendedDiagnostics` 결과를 CI 로그에 남긴다. 임계값을 넘으면 `--generateTrace`를 아티팩트로 저장한다. 핫스팟 파일 하나만 수정하고 다시 측정해 Instantiations와 Check time이 줄었는지 확인한다. 한 번에 여러 옵션을 바꾸면 효과의 원인을 알 수 없다. 변경이 의미 있는지는 3회 이상 측정한 중앙값으로 판단한다. 수치는 장비와 TypeScript 버전에 따라 크게 달라지므로 절대값을 외우지 않고 자신의 프로젝트 베이스라인과 비교한다.

## 8. 사례 점검 체크리스트

`types` 미지정으로 불필요한 @types가 수백 개 로드되지 않는가, 모노레포가 단일 프로그램으로 합쳐져 있지 않은가, 공용 유틸 타입에 깊은 재귀가 없는가, ORM이나 스키마 라이브러리의 거대한 유니온 추론이 모든 파일에서 재계산되지 않는가를 확인한다. Zod나 Prisma처럼 타입을 대량 생성하는 라이브러리는 스키마를 별도 파일로 분리하고 `z.infer`로 얻은 타입에 이름을 붙여 재사용하면 호출부 인스턴스화가 줄어든다.

## 참고

- TypeScript Wiki — Performance (microsoft/TypeScript)
- TypeScript 공식 문서 — tsc CLI Options, Compiler Options
- microsoft/typescript-analyze-trace 저장소 README
