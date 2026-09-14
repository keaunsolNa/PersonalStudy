Notion 원본: https://www.notion.so/3db5a06fd6d3812eb747cb841acedfe3

# Speculation Rules API와 프리렌더링 및 bfcache 복원 조건

> 2026-09-15 신규 주제 · 확장 대상: HTTP 캐시 의미론과 CDN 엣지 캐싱 및 stale-while-revalidate

## 학습 목표

- Speculation Rules 로 prefetch/prerender 를 선언하고 `eagerness` 로 적중률과 낭비를 조절한다
- 프리렌더 중인 문서에서 지연되는 API 와 분석 스크립트 오작동을 처리한다
- bfcache 복원 실패 원인을 `notRestoredReasons` 로 특정해 제거한다
- 투기적 로딩이 서버·측정 파이프라인에 주는 부작용을 설계에 반영한다

## 1. 두 가지 "이미 준비된 페이지"

체감 속도를 극적으로 바꾸는 방법은 결국 하나다. 사용자가 클릭하기 전에 일을 끝내 두는 것. 브라우저에는 이를 위한 서로 다른 두 메커니즘이 있다.

**투기적 로딩(speculative loading)** 은 앞으로 갈 가능성이 있는 페이지를 미리 가져오거나(prefetch) 미리 렌더링한다(prerender). 아직 방문한 적 없는 페이지가 대상이다.

**bfcache(back/forward cache)** 는 이미 떠난 페이지를 메모리에 통째로 얼려 두었다가 뒤로 가기 시 그대로 되살린다. 이미 방문한 페이지가 대상이다.

둘은 보완 관계다. 전진 내비게이션은 투기적 로딩이, 후진 내비게이션은 bfcache 가 맡는다. 두 축을 모두 채워야 세션 전체의 내비게이션 지연이 사라진다.

## 2. Speculation Rules 선언

규칙은 JSON 이며, `type="speculationrules"` 인 스크립트 요소로 문서에 심거나 `Speculation-Rules` 헤더로 외부 JSON 을 가리킬 수 있다.

```json
{
  "prerender": [
    {
      "where": { "href_matches": "/products/*" },
      "eagerness": "moderate"
    }
  ],
  "prefetch": [
    {
      "where": {
        "and": [
          { "href_matches": "/*" },
          { "not": { "href_matches": "/logout*" } },
          { "not": { "selector_matches": ".no-speculate, [download]" } }
        ]
      },
      "eagerness": "conservative"
    }
  ]
}
```

초기 스펙은 URL 을 직접 나열하는 `urls` 리스트만 지원했지만, 이후 **문서 규칙(document rules)** 이 추가되어 `where` 로 현재 문서의 링크를 패턴 매칭할 수 있게 됐다. 링크 목록을 서버에서 계산해 주입할 필요가 없어졌고, SPA 라우팅 이후 동적으로 추가된 링크도 같은 규칙에 잡힌다.

규칙을 동적으로 추가하려면 스크립트 요소를 새로 붙이면 된다. 기존 규칙 요소의 내용을 수정하는 것은 반영되지 않는다.

### eagerness — 적중률과 낭비의 손잡이

| 값 | 트리거 시점 | 성격 |
|---|---|---|
| `immediate` | 규칙이 파싱되는 즉시 | 가장 공격적, 리스트 규칙의 기본값 |
| `eager` | 거의 즉시 | 공격적 |
| `moderate` | 링크에 약 200ms 호버하거나 포인터 다운 | 균형점 |
| `conservative` | 포인터 다운/터치 시작 | 가장 보수적, 문서 규칙의 기본값 |

`conservative` 도 의미가 있다. 포인터 다운과 실제 클릭 사이에는 보통 100~200ms 가 있고, 그 시간에 prefetch 를 시작하면 그만큼을 벌 수 있다. 낭비는 거의 없다.

`moderate` 는 호버 기반이라 데스크톱에서 적중률이 높다. 다만 터치 디바이스에는 호버가 없어 사실상 `conservative` 처럼 동작한다.

`immediate` 는 **다음 목적지가 사실상 확정된 경우**에만 쓴다. 로그인 성공 후 대시보드, 결제 1단계에서 2단계, 페이지네이션의 다음 페이지 같은 경로다. 목록 페이지의 모든 상품 링크에 `immediate` prerender 를 걸면 서버와 사용자 데이터 요금이 함께 타격을 받는다.

브라우저는 동시 투기 개수에 상한을 둔다. 구현마다 다르지만 Chromium 계열은 `immediate`/`eager` prerender 를 10개 내외, `moderate`/`conservative` 를 2개 내외로 제한하고 초과 시 오래된 것부터 버린다.

### prefetch 와 prerender 의 차이

`prefetch` 는 HTML 응답만 미리 받아 둔다. 서브리소스는 가져오지 않고 스크립트도 실행하지 않는다. 비용이 낮고, 낭비되더라도 HTML 한 건이다.

`prerender` 는 숨겨진 렌더러에서 **페이지를 완전히 로드하고 실행**한다. 서브리소스 다운로드, 스크립트 실행, 레이아웃, 페인트까지 끝나 있으므로 활성화 시점의 지연이 사실상 0 이다. 대가는 메모리와 CPU 이며, 저사양 기기에서는 브라우저가 프리렌더 요청을 조용히 거절하기도 한다.

서버 쪽에서는 `Sec-Purpose` 요청 헤더로 구분한다. 값은 `prefetch` 또는 `prefetch;prerender` 다. 이 헤더를 보고 서버는 **부작용이 있는 처리를 건너뛰어야 한다**. 조회수 증가, 세션 갱신, 재고 선점 같은 로직이 투기적 요청에서 실행되면 데이터가 오염된다.

```js
// Express 예시
app.use((req, res, next) => {
  req.isSpeculative = Boolean(req.get("Sec-Purpose"));
  next();
});

app.get("/products/:id", async (req, res) => {
  const product = await loadProduct(req.params.id);
  if (!req.isSpeculative) {
    await incrementViewCount(req.params.id); // 투기 요청에서는 생략
  }
  res.render("product", { product });
});
```

### No-Vary-Search

쿼리 파라미터가 붙은 URL 은 다른 URL 로 취급되어 prefetch 결과가 재사용되지 않는다. 추적 파라미터처럼 응답에 영향을 주지 않는 키는 `No-Vary-Search` 로 무시하게 만들 수 있다.

```
No-Vary-Search: params=("utm_source" "utm_medium" "utm_campaign" "ref")
No-Vary-Search: key-order
```

이 헤더 하나로 prefetch 적중률이 크게 오르는 사이트가 많다. 캐페인 링크가 섞인 랜딩 페이지에서 특히 효과적이다.

### 출처 제약

같은 출처(same-origin) prerender 는 별 제약이 없다. 같은 사이트의 다른 서브도메인(cross-origin same-site)으로 prerender 하려면 대상이 `Supports-Loading-Mode: credentialed-prerender` 로 옵트인해야 한다. 완전히 다른 사이트(cross-site)로의 prerender 는 지원되지 않고, cross-site prefetch 는 대상 사이트에 대한 쿠키가 없는 상태에서만 동작한다. 프라이버시 보호를 위한 설계다.

## 3. 프리렌더된 문서에서의 동작

프리렌더 중인 문서는 살아 있지만 보이지 않는다. 코드가 이를 인지해야 한다.

```js
if (document.prerendering) {
  // 프리렌더 중 — 부작용 있는 작업 보류
} else if (performance.getEntriesByType("navigation")[0]?.activationStart > 0) {
  // 프리렌더된 뒤 활성화된 상태
} else {
  // 일반 내비게이션
}

document.addEventListener("prerenderingchange", () => {
  // 활성화 시점. 여기서 분석 전송·타이머 시작·오디오 재생 등을 수행
  startAnalytics();
}, { once: true });
```

`PerformanceNavigationTiming.activationStart` 는 프리렌더 시작부터 활성화까지의 시간이다. 모든 성능 지표를 이 값 기준으로 보정해야 한다. 보정하지 않으면 LCP 가 "프리렌더 시작 후 2.4초"로 기록되지만 사용자가 실제 경험한 시간은 0.05초다.

```js
new PerformanceObserver((list) => {
  const activationStart =
    performance.getEntriesByType("navigation")[0]?.activationStart ?? 0;
  for (const entry of list.getEntries()) {
    const adjusted = Math.max(entry.startTime - activationStart, 0);
    report("LCP", adjusted);
  }
}).observe({ type: "largest-contentful-paint", buffered: true });
```

web-vitals 라이브러리 같은 도구는 이 보정을 내장하고 있으므로, 직접 구현한 측정 코드만 손보면 되는 경우가 많다.

프리렌더 중에는 사용자 활성화가 필요한 API 들이 **지연되거나 거부**된다. 대표적으로 오디오·비디오 자동 재생, 알림 권한 요청, 지오로케이션, 결제 요청 등이다. 또한 이런 API 호출이 프리렌더를 **취소**시켜 버릴 수도 있다. 페이지 로드 직후 권한을 요청하는 코드가 있다면 `prerenderingchange` 이후로 미뤄야 프리렌더가 유지된다.

분석 도구가 가장 흔한 사고 지점이다. 프리렌더 시점에 페이지뷰를 전송하면, 사용자가 방문하지 않은 페이지가 조회수로 집계된다. 반대로 활성화 이벤트를 처리하지 않으면 실제 방문이 누락된다. 두 경우 모두 지표가 틀리므로, 분석 초기화는 반드시 활성화 이후로 게이팅한다.

## 4. bfcache — 뒤로 가기의 즉시 복원

bfcache 는 문서를 JS 힙, DOM, 스크롤 위치까지 통째로 정지시켜 보관한다. 복원 시 실행되는 것은 `pageshow` 이벤트뿐이고, 스크립트가 처음부터 다시 도는 일은 없다.

```js
window.addEventListener("pageshow", (event) => {
  if (event.persisted) {
    // bfcache 에서 복원됨 — 시간 의존 상태를 갱신한다
    refreshCartBadge();
    restartPollingTimer();
  }
});

window.addEventListener("pagehide", (event) => {
  if (event.persisted) {
    // bfcache 에 들어감 — 연결 정리, 상태 저장
    pausePollingTimer();
  }
});
```

### 복원을 막는 조건들

| 원인 | 설명 | 대응 |
|---|---|---|
| `unload` 이벤트 리스너 | 레거시 정리 코드. 등록만으로 실격 | `pagehide` 로 교체 |
| 메인 문서 `Cache-Control: no-store` | 응답 헤더 하나로 전체 실격 | `no-cache`/`private` 로 조정 |
| 진행 중인 IndexedDB 트랜잭션 | 열린 트랜잭션이 있으면 대기 불가 | `pagehide` 에서 정리 |
| 진행 중인 fetch/XHR | 응답 대기 중 이퀡0 | `AbortController` 로 취소 |
| 브라우저 권한 프롬프트 열림 | 사용자 결정 대기 상태 | 사용자 제스처 기반으로 이동 |
| `window.opener` 연결 | 다른 문서와 상호 참조 | `rel="noopener"` 사용 |

`unload` 와 `Cache-Control: no-store` 두 항목이 실무 사례의 대부분을 차지한다. 특히 `no-store` 는 "민감 페이지는 캐시 금지"라는 관성으로 전역 적용되는 경우가 많은데, bfcache 는 디스크 캐시가 아니라 **같은 사용자의 브라우저 메모리**이므로 일반적인 캐시 우려와 위협 모델이 다르다. 로그아웃 후 뒤로 가기가 걱정된다면 `no-store` 대신 `Clear-Site-Data` 헤더나 `pageshow` 에서의 상태 재검증으로 푸는 편이 낫다.

### 원인 특정 — notRestoredReasons

```js
window.addEventListener("pageshow", () => {
  const nav = performance.getEntriesByType("navigation")[0];
  if (nav?.notRestoredReasons) {
    report("bfcache-blocked", JSON.stringify(nav.notRestoredReasons));
  }
});
```

`notRestoredReasons` 는 최상위 문서와 각 프레임에 대해 실격 사유 목록을 담은 트리를 돌려준다. 서드파티 iframe(광고, 채팅 위젯) 하나 때문에 전체가 실격되는 사례를 여기서 잡을 수 있다. 개발 단계에서는 Chrome DevTools 의 Application → Back/forward cache 패널에서 같은 정보를 대화형으로 확인할 수 있다.

## 5. 실제 배포 전략

**1단계 — bfcache 부터.** 비용이 0 이고 효과가 확실하다. `unload` 리스너 제거와 `no-store` 정리만으로 뒤로 가기 내비게이션 상당수가 즉시 복원으로 바뀜다. 필드 데이터로 실격률을 먼저 측정한다.

**2단계 — conservative prefetch.** 전 링크에 보수적 prefetch 를 걸어도 낭비가 거의 없다. 로그아웃, 삭제, 다운로드 링크는 `selector_matches` 나 `href_matches` 부정 조건으로 제외한다.

**3단계 — moderate prerender 를 좁은 경로에.** 트래픽이 집중되는 전환 경로(목록 → 상세, 장바구니 → 결제)에만 적용한다. 서버 부하 증가분을 측정하면서 범위를 넓힌다.

**4단계 — 측정 파이프라인 정합화.** `activationStart` 보정과 `document.prerendering` 게이팅을 적용하고, `Sec-Purpose` 기반 서버 로직 분기를 확인한다.

효과를 확인할 때는 랩 데이터가 아니라 필드 데이터를 본다. 프리렌더는 성공하면 지표가 0 에 가깝게 찍힐므로, 평균만 보면 착시가 생긴다. **적중률(프리렌더된 페이지 중 실제 방문 비율)**과 **낭비량(프리렌더했으나 방문하지 않은 건의 바이트)**를 함께 추적해야 판단이 가능하다.

## 6. 비용 측면의 정직한 정리

투기적 로딩은 공짜가 아니다. prerender 1건은 실제 페이지뷰 1건과 같은 서버 비용을 발생시킨다. 적중률 30% 라면 서버 요청은 3.3배가 되고 비용도 그만큼 오른다. 모바일 데이터 요금을 쓰는 사용자에게는 보이지 않는 전송량이 추가된다.

그래서 기본 자세는 "가능한 한 보수적으로 시작해 데이터로 넓히기"다. `conservative` 는 거의 순이득이고, `moderate` 는 데스크톱에서 대체로 이득이며, `immediate` 는 다음 목적지가 통계적으로 확정된 소수 경로에만 쓴다. 반면 bfcache 는 추가 네트워크·서버 비용이 전혀 없으므로, 우선순위를 여기에 먼저 두는 것이 합리적이다.

## 참고

- MDN — Speculation Rules API, `Sec-Purpose`, `No-Vary-Search`, `Supports-Loading-Mode`
- WICG — Speculation Rules 명세 (wicg.github.io/nav-speculation)
- web.dev — Prerender pages in Chrome for instant page navigations
- web.dev — Back/forward cache, `notRestoredReasons` API
- MDN — `PerformanceNavigationTiming.activationStart`, `pageshow`/`pagehide`
- MDN — `Clear-Site-Data` 헤더
