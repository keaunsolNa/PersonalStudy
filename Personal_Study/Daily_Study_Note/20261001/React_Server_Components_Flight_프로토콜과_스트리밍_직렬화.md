Notion 원본: https://app.notion.com/p/3ec5a06fd6d381b99211f9091c3e3487?pvs=204

# React Server Components Flight 프로토콜과 스트리밍 직렬화 및 클라이언트 경계 설계

> 2026-10-01 신규 주제 · 확장 대상: React, Next.js

## 학습 목표

- Flight 응답의 row 구조를 직접 읽고 참조 태그(`$L`, `$@`, `$S`)의 의미를 해독한다
- Suspense 경계가 스트림의 out-of-order 전송으로 어떻게 대응되는지 추적한다
- props 직렬화 한계를 기준으로 `"use client"` 경계의 위치를 결정한다
- 서버 함수 요청의 역직렬화 위협을 이해하고 방어 계층을 구성한다

## 1. RSC payload는 HTML이 아니다

React Server Components를 처음 접하면 서버가 HTML을 만들어 보내는 기술로 오해하기 쉽다. 실제로 서버 컴포넌트가 만들어내는 1차 산출물은 HTML이 아니라 React 엘리먼트 트리를 직렬화한 데이터이며, React 팀은 이 와이어 포맷을 Flight라고 부른다. Next.js App Router는 문서 요청에는 이 트리를 SSR로 HTML 로 한 번 더 변환해 응답하고, 같은 트리를 `<script>` 안에 `self.__next_f.push(...)` 형태로 끼워 넣어 하이드레이션과 이후 내비게이션에 재사용한다. 클라이언트 측 라우팅 시에는 HTML 없이 Flight 데이터만 `text/x-component` 타입으로 받아 현재 트리에 병합한다.

이 구분이 중요한 이유는 디버깅 대상이 달라지기 때문이다. 화면이 이상할 때 HTML만 보면 서버 컴포넌트 결과 중 클라이언트 컴포넌트 자리표시자가 어떻게 전달됐는지 알 수 없다. Flight를 읽을 수 있으면 "이 props가 왜 클라이언트로 넘어왔는가", "어떤 청크가 지연 로드되는가"를 와이어 레벨에서 확인할 수 있다. 다음 명령은 Next.js 개발 서버에서 Flight 응답만 따로 받아본다. `RSC: 1` 요청 헤더는 Next.js가 내부 내비게이션 요청을 식별하는 데 쓰는 관례이며 프레임워크 버전에 따라 부가 헤더가 필요할 수 있다.

```bash
curl -s -H "RSC: 1" http://localhost:3000/ | head -c 1200
```

Flight 포맷 자체는 공개된 표준 명세가 아니라 React 구현(`react-server` 패키지와 `react-server-dom-*` 번들러 바인딩)의 내부 계약이다. 따라서 이 문서의 예시는 React 19 계열에서 관찰되는 형태이고, 세부 문법은 마이너 버전마다 바뀔 수 있다.

## 2. Flight 와이어 포맷 해부

Flight 스트림은 줄바꿈으로 구분되는 row의 연속이다. 각 row는 `<id>:<payload>` 형태이고 id는 16진수다. id `0`은 보통 루트 모델이다. payload가 `I[`로 시작하면 클라이언트 모듈 참조(import)이고, JSON 배열이면 엘리먼트 또는 값이다. 엘리먼트는 `["$", type, key, props]` 4-튜플로 표현되며 첫 원소 `"$"`가 React 엘리먼트임을 표시한다. 아래는 서버 컴포넌트 하나가 클라이언트 컴포넌트 `Counter`를 포함할 때의 형태를 단순화한 예시다. 실제 번들러 id와 청크 경로는 환경에 따라 다르다.

```text
1:I["app/Counter.js",["static/chunks/app-counter.js"],"default"]
0:["$","main",null,{"children":[["$","h1",null,{"children":"대시보드"}],["$","$L1",null,{"initial":3}]]}]
```

`$L1`의 `$L`은 "lazy reference"로, row 1에 정의된 값을 나중에 해석하라는 뜻이다. 여기서는 row 1이 클라이언트 모듈 참조이므로 `Counter` 코드는 JS 번들에 포함되어 있고, 서버가 보내는 것은 모듈 id·청크 목록·export 이름뿐이다. 서버 컴포넌트의 코드와 의존성(예: DB 드라이버, 마크다운 파서)은 어떤 경우에도 이 스트림에 실리지 않는다. 이것이 RSC가 번들 크기를 줄이는 구조적 이유다.

값 문자열이 `$`로 시작하면 특수 해석 대상이므로 서버는 일반 문자열 `$`를 `$$`로 이스케이프한다. 주요 태그를 정리하면 다음과 같다. 일부 태그는 React 소스(`ReactFlightClient`)에서 확인되는 것이며, 버전에 따라 추가·변경될 수 있다.

| 태그 | 의미 | 비고 |
|---|---|---|
| `$L<id>` | 다른 row를 가리키는 lazy 참조 | 클라이언트 컴포넌트, 지연 도착 청크 |
| `$@<id>` | row를 Promise로 참조 | `use()`로 소비하는 서버발 Promise |
| `$S<name>` | Symbol | `$Sreact.suspense` 등 |
| `$undefined` | undefined 값 | JSON이 표현하지 못하는 값 |
| `$D<iso>` | Date | ISO 문자열로 전송 |
| `$n<digits>` | BigInt | 문자열 숫자 |
| `$Q<id>`, `$W<id>` | Map, Set | row로 분리되어 전송 |
| `$K<id>` | FormData | 서버 함수 인자 |

## 3. 스트리밍과 Suspense 경계의 대응

Flight가 단순 JSON이 아닌 이유는 전체 트리가 완성되기 전에 앞부분부터 흘려보내기 위해서다. 서버 컴포넌트가 `await`로 느린 데이터를 기다리는 동안 React는 해당 서브트리를 건너뛰고 나머지를 먼저 직렬화한다. Suspense 경계가 있으면 경계 안쪽은 fallback으로 대체되어 먼저 나가고, 데이터가 준비되면 같은 id의 row가 뒤늦게 도착해 자리를 채운다. 아래는 단순화한 개념도이다.

```text
0:["$","div",null,{"children":["$","$Sreact.suspense",null,{"fallback":["$","p",null,{"children":"로딩"}],"children":"$L2"}]}]
...(다른 row 전송)...
2:["$","ul",null,{"children":[["$","li",null,{"children":"주문 #1"}]]}]
```

row 2는 처음 스트림에는 없다가 데이터 조회가 끝난 뒤에 도착한다. 클라이언트는 `$L2`를 pending 상태로 두고 Suspense fallback을 보여주다가, row 2가 오면 트리를 갱신한다. 따라서 순서는 "정의 순서"가 아니라 "완료 순서"이며, 이를 out-of-order 스트리밍이라 부른다. 아래 코드는 느린 위젯 하나를 격리해 나머지 UI가 블로킹되지 않게 하는 전형적 구성이다.

```tsx
// app/dashboard/page.tsx (서버 컴포넌트)
import { Suspense } from "react";

async function Orders() {
  const res = await fetch("https://api.example.com/orders", { cache: "no-store" });
  const orders: { id: number; title: string }[] = await res.json();
  return (
    <ul>
      {orders.map((o) => <li key={o.id}>{o.title}</li>)}
    </ul>
  );
}

export default function Page() {
  return (
    <main>
      <h1>대시보드</h1>
      <Suspense fallback={<p>주문 불러오는 중</p>}>
        <Orders />
      </Suspense>
    </main>
  );
}
```

여기에는 trade-off가 있다. 스트리밍은 첫 바이트까지의 시간(TTFB)과 첫 의미 있는 페인트를 앞당기지만, 응답 헤더가 먼저 전송되므로 이후에 발생하는 오류를 HTTP 상태 코드로 표현할 수 없다. Next.js 문서도 스트리밍 응답이 시작된 뒤에는 상태 코드를 바꿀 수 없다는 점을 언급하며, 이 때문에 `notFound()` 처리 같은 상태 코드 의존 로직은 경계 바깥에서 먼저 수행하는 설계가 필요하다. 또한 일부 프록시나 CDN, 압축 설정은 응답을 버퍼링해 스트리밍 효과를 지울 수 있으므로, 배포 환경에서 청크가 실제로 점진 도착하는지 반드시 확인한다. 개선폭은 서버 지연, 네트워크, 캐시 정책에 따라 크게 달라지므로 일반화된 수치는 환경에 따라 다름이다.

## 4. 클라이언트 참조와 번들러 매니페스트

`"use client"` 지시어가 붙은 모듈을 서버 컴포넌트에서 import하면, 서버 번들러는 그 모듈의 실제 코드를 포함하지 않고 "클라이언트 참조" 프록시 객체로 치환한다. 이 프록시가 직렬화될 때 위의 `I` row가 만들어진다. 어떤 모듈 id가 어떤 청크 파일에 대응하는지는 번들러가 생성하는 클라이언트 매니페스트가 담고 있고, Next.js에서는 이 과정이 프레임워크 내부에서 자동 처리된다. 직접 `react-server-dom-webpack`을 쓰는 경우에는 서버 렌더 호출에 매니페스트를 넘겨야 한다. 다음은 공식 패키지 API를 사용하는 최소 골격이며, 매니페스트 생성은 웹팩 플러그인(`react-server-dom-webpack/plugin`)이 맡는다.

```js
// server.js (Node, 개념 골격)
import { renderToPipeableStream } from "react-server-dom-webpack/server.node";

export function handle(req, res, model, clientManifest) {
  res.setHeader("Content-Type", "text/x-component");
  const { pipe } = renderToPipeableStream(model, clientManifest);
  pipe(res);
}
```

```js
// client.js (브라우저)
import { createFromFetch } from "react-server-dom-webpack/client";
import { use } from "react";

const tree = createFromFetch(fetch("/rsc"));
export function Root() {
  return use(tree); // Suspense 경계 안에서 사용
}
```

`createFromFetch`는 응답 본문을 스트림으로 읽어 row가 도착하는 대로 트리를 구성하는 Promise 유사 객체를 돌려준다.

## 5. 직렬화 가능 범위와 props 경계

서버 컴포넌트가 클라이언트 컴포넌트에 넘기는 props는 반드시 Flight가 표현할 수 있어야 한다. 원시값, 배열, 평범한 객체, Date, BigInt, Map, Set, FormData, Promise, 그리고 다른 서버 컴포넌트가 만든 JSX는 가능하다. 일반 함수, 클래스 인스턴스, 심볼 중 등록되지 않은 것은 불가능하며 개발 모드에서 "Functions cannot be passed directly to Client Components" 계열 오류로 드러난다. 예외는 `"use server"`로 표시된 서버 함수이고, 이는 함수 본체가 아니라 참조 id로 전송된다.

경계 설계의 핵심은 직렬화 비용과 노출 범위가 모두 props 크기에 비례한다는 점이다. DB 레코드 전체를 클라이언트 컴포넌트에 넘기면 사용하지 않는 필드까지 Flight와 하이드레이션 데이터에 중복 포함되어 페이지 응답 크기가 커지고, 민감 필드가 브라우저에 노출될 수 있다. 필요한 필드만 뽑는 DTO 변환을 서버에서 수행하는 것이 기본이다.

```tsx
// app/profile/page.tsx (서버)
import { getUser } from "@/lib/db"; // 서버 전용
import { ProfileEditor } from "./ProfileEditor"; // "use client"

export default async function Page() {
  const user = await getUser(); // passwordHash 등 포함
  const dto = { id: user.id, name: user.name, joinedAt: user.joinedAt }; // Date 직렬화 가능
  return <ProfileEditor user={dto} />;
}
```

민감 데이터가 실수로 넘어가는 것을 막기 위해 React는 실험적 Taint API(`experimental_taintObjectReference`, `experimental_taintUniqueValue`)를 제공하고, `server-only` 패키지를 import하면 클라이언트 번들에서 해당 모듈이 포함될 때 빌드 오류를 낼 수 있다. Taint API는 이름대로 실험 단계이므로 방어의 유일 수단으로 삼지 않고 DTO 변환과 병행한다.

## 6. 서버 함수 직렬화와 보안

서버 함수(Server Functions, 흔히 Server Actions)는 클라이언트에서 호출하면 서버로 POST 요청이 가고, 인자는 Flight와 같은 계열의 포맷으로 인코딩되어 서버에서 역직렬화된다. 즉 서버는 공개 엔드포인트에서 외부 입력을 역직렬화한다. 이 지점은 컴포넌트 렌더링과 달리 신뢰할 수 없는 입력이 파서를 통과하는 구간이다. 2025년 12월 React는 `react-server-dom-*` 패키지의 서버 함수 요청 역직렬화 결함(CVE-2025-55182)에 대한 보안 공지를 냈고, 19.0.1, 19.1.2, 19.2.1 등 패치 버전을 배포했다. 세부 영향 범위는 React 공식 블로그의 보안 공지를 직접 확인해야 하며, 교훈은 프레임워크 업데이트를 지연하지 말고 서버 함수 인자를 항상 신뢰할 수 없는 입력으로 취급하라는 것이다.

```ts
// app/actions.ts
"use server";
import { z } from "zod";
import { auth } from "@/lib/auth";

const Input = z.object({ id: z.string().uuid(), title: z.string().max(120) });

export async function updateTitle(raw: unknown) {
  const session = await auth();
  if (!session) throw new Error("unauthorized");   // 인증
  const { id, title } = Input.parse(raw);          // 입력 검증
  // 소유권 검증 후 갱신 (생략)
}
```

서버 함수는 UI에서 버튼을 숨겨도 공개 엔드포인트로 남는다. 각 함수 안에서 인증, 권한, 스키마 검증을 독립적으로 수행한다.

## 7. 경계 배치 패턴

`"use client"`는 컴포넌트 단위가 아니라 모듈 의존 그래프의 경계다. 한 모듈이 클라이언트로 표시되면 그 모듈이 import하는 모든 하위 모듈이 클라이언트 번들에 포함된다. 그러므로 경계는 가능한 한 트리의 잎(leaf) 쪽에 두는 것이 번들 측면에서 유리하다. 상호작용이 필요한 버튼이나 입력만 클라이언트로 분리하고, 데이터 조회와 레이아웃은 서버에 남긴다.

상호작용 래퍼 안에 서버 콘텐츠를 넣어야 할 때는 `children` 합성을 쓴다. 서버 컴포넌트가 만든 JSX를 클라이언트 컴포넌트에 `children` prop으로 넘기면, 서버 결과는 이미 직렬화된 엘리먼트 트리로 전달될 뿐 클라이언트 모듈이 서버 모듈을 import하지 않는다.

```tsx
// Modal.tsx
"use client";
import { useState, type ReactNode } from "react";
export function Modal({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (<>
    <button onClick={() => setOpen(!open)}>토글</button>
    {open && <div role="dialog">{children}</div>}
  </>);
}

// page.tsx (서버)
import { Modal } from "./Modal";
import { HeavyServerReport } from "./HeavyServerReport";
export default function Page() {
  return <Modal><HeavyServerReport /></Modal>;
}
```

## 8. 관찰, 디버깅, 성능 trade-off

Flight 스트림을 눈으로 확인하려면 네트워크 탭에서 `_rsc` 쿼리가 붙은 요청 또는 `text/x-component` 응답을 본다. 아래 스크립트는 저장한 응답에서 row를 분리해 id와 종류를 출력한다. 길이 접두어를 쓰는 텍스트·바이너리 row는 처리하지 않는 단순 파서이므로 진단 용도로만 쓴다.

```js
// parse-flight.mjs : node parse-flight.mjs flight.txt
import { readFileSync } from "node:fs";
const lines = readFileSync(process.argv[2], "utf8").split("\n");
for (const line of lines) {
  const m = /^([0-9a-f]+):(.*)$/.exec(line);
  if (!m) continue;
  const kind = m[2].startsWith("I[") ? "module" : m[2].startsWith("[") ? "element" : "other";
  console.log(m[1].padStart(3), kind.padEnd(8), m[2].length, "bytes");
}
```

성능 측면의 trade-off는 세 가지로 정리된다. 첫째, 서버 컴포넌트는 클라이언트 JS를 줄이지만 내비게이션마다 Flight 응답이 오가므로 서버 왕복이 늘 수 있다. 둘째, 경계를 잘게 쪼개면 번들은 줄지만 청크 요청 수와 직렬화 row가 늘어난다. 셋째, 스트리밍은 체감 속도를 높이지만 상태 코드 제어와 프록시 버퍼링이라는 운영 복잡도를 준다. 이 비용들의 실제 크기는 앱 구조, 네트워크, 캐시, 호스팅 환경에 따라 다름이므로 일반화된 수치를 믿기보다 자신의 라우트에서 번들 분석기와 네트워크 탭, `next build` 출력의 라우트별 First Load JS를 비교해 판단한다.

## 참고

- React 공식 문서: Server Components, `use client`, `use server` 지시어 레퍼런스 (react.dev/reference/rsc)
- React 공식 문서: `use` API, Suspense 레퍼런스 (react.dev/reference)
- React 블로그: React 19 릴리스 노트 및 2025년 12월 서버 함수 보안 공지 (react.dev/blog)
- Next.js 문서: App Router, Server and Client Components, Streaming, Data Security 가이드 (nextjs.org/docs)
- React 소스 저장소 `packages/react-server`, `packages/react-client`, `packages/react-server-dom-webpack` (github.com/facebook/react)
