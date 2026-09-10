Notion 원본: https://www.notion.so/3d75a06fd6d381a7b34cdeb5dfa740be

# Playwright 아키텍처와 자동 대기 및 트레이스 기반 디버깅

> 2026-09-10 신규 주제 · 확장 대상: TDD

## 학습 목표

- 브라우저 서버 · 컨텍스트 · 페이지 계층과 worker 병렬 모델 파악
- actionability 항목별로 어떤 flaky 실패가 사라지는지 구분
- 로케이터 · 웹-퍼스트 어서션 · 네트워크 모킹으로 결정론 확보
- 트레이스와 샤딩을 CI에 배치해 실패 분석과 실행 시간 통제

## 1. E2E 테스트가 깨지는 이유

E2E 실패 대부분은 제품 버그가 아니라 타이밍 문제다. `waitForTimeout(2000)` 같은 명시적 sleep은 느린 CI에서 부족하고, "클릭하면 모달이 떠 있을 것"이라는 암묵적 가정은 애니메이션·청크 로딩·React 렌더 스케줄링에 따라 깨진다. 셀렉터도 마찬가지여서 `.css-1x2y3z > div:nth-child(3)`는 해시가 바뀌면 무너진다. 구현 세부에 묶인 테스트는 TDD가 기대하는 안전망이 아니라 변경 비용이 된다.

Selenium/WebDriver는 명령마다 HTTP 왕복이 발생하고 각 명령이 독립적이라, 요소를 찾은 뒤 클릭 전에 DOM이 바뀌면 stale element 에러가 난다. Playwright는 WebSocket 연결을 유지하고 Chromium에서는 CDP로 이벤트를 푸시받으며, 이것이 자동 대기와 트레이스의 기반이다.

| 항목 | Selenium/WebDriver | Playwright |
| --- | --- | --- |
| 전송 방식 | 명령마다 HTTP 왕복 | 상시 WebSocket 연결 |
| 이벤트 수신 | 폴링 위주 | 브라우저가 푸시 |
| 요소 참조 | 조회 시점 고정(stale 위험) | 로케이터는 지연 평가 |
| 대기 | wait를 직접 구성 | actionability 기본 내장 |

## 2. 프로세스 구조

실행 계층은 테스트 러너 → 브라우저 서버 → 브라우저 컨텍스트 → 페이지다. `BrowserContext`는 쿠키·localStorage·권한·지역 설정을 독립적으로 갖는 "새 시크릿 프로필"이지만 프로세스를 새로 띄우지 않아 생성 비용이 밀리초 단위다. 테스트마다 새 컨텍스트를 쓰면 앞 테스트의 쿠키·캐시가 새지 않아 순서 의존성이 사라지며, 기본 `page` fixture가 이미 그렇게 동작한다. 병렬 단위인 worker는 독립 Node 프로세스로 각자 브라우저를 띄우므로, 브라우저는 worker 단위 재사용이고 컨텍스트는 테스트 단위 폐기다.

```ts
// playwright.config.ts — defineConfig, devices 는 @playwright/test 에서 import
export default defineConfig({
  fullyParallel: true,
  workers: process.env.CI ? 4 : undefined,
  retries: process.env.CI ? 2 : 0,
  use: { baseURL: 'http://localhost:3000', trace: 'on-first-retry' },
});
```

trade-off: worker를 늘리면 벽시계 시간은 줄지만 브라우저 인스턴스가 그만큼 메모리를 먹는다. CI 컨테이너 메모리가 모자라면 OOM이 flaky처럼 보이므로, worker 수는 코어 수보다 가용 메모리 기준으로 잡는다.

## 3. 자동 대기와 actionability

액션은 즉시 실행되지 않는다. 대상이 조작 가능해질 때까지 조건을 반복 검사하고 모두 만족한 순간 이벤트를 보내는데, 이 조건 집합이 actionability다.

| 조건 | 의미 | 막아주는 실패 |
| --- | --- | --- |
| attached | DOM에 붙어 있음 | 렌더 전 클릭, stale 참조 |
| visible | 빈 박스가 아니고 `visibility: hidden`이 아님 | 숨겨진 모달 조작 |
| stable | 최소 두 프레임 동안 바운딩 박스 고정 | 애니메이션 중 좌표 어긋남 |
| enabled | `disabled` 아님 | 제출 중 비활성 버튼 클릭 |
| receives events | 해당 지점 hit target이 자기 자신 | 오버레이에 가려진 클릭 |

`click`은 다섯 가지를 모두 보고 `fill`은 editable 여부를 더 보며, `textContent` 같은 조회는 대기하지 않는다. 마지막 검사가 특히 값진데, 반투명 오버레이에 흡수된 클릭은 아무 일도 일으키지 않고 뒤이은 어서션에서 실패하기 때문이다. 타임아웃은 계층이다. `actionTimeout`은 액션 재시도 한도, `expect.timeout`은 어서션 폴링 한도, `timeout`은 테스트 본문 전체, `globalTimeout`은 전체 실행 상한이며 안쪽 값이 바깥쪽보다 크면 의미가 없다.

```ts
export default defineConfig({
  timeout: 30_000,
  globalTimeout: 30 * 60_000,
  expect: { timeout: 7_000 },
  use: { actionTimeout: 10_000, navigationTimeout: 20_000 },
});
```

trade-off: 타임아웃을 넉넉히 잡으면 통과율은 오르지만 회귀 확인이 늦어진다. 특정 화면만 느리다면 전역 값 대신 `test.setTimeout()`이나 개별 어서션의 `{ timeout }`으로 국소화한다.

## 4. 로케이터와 웹-퍼스트 어서션

로케이터는 요소 참조가 아니라 "찾는 방법"이라 지연 평가되고, 그래서 React 리렌더로 노드가 교체돼도 stale이 되지 않는다. `getByRole`이 1순위인 것은 접근성 트리 기준이기 때문인데, 이 트리는 `div`를 `section`으로 바꾸거나 클래스명을 갈아엎어도 잘 바뀌지 않아 리팩터링에 강하다. 다음이 `getByLabel`, `getByPlaceholder`, `getByText`이고, 사용자 대상 속성으로 잡히지 않을 때만 `getByTestId`를 쓴다.

```ts
test('주문서를 제출하면 확인 배너가 뜬다', async ({ page }) => {
  await page.goto('/checkout');
  await page.getByLabel('수령인').fill('나근솔');
  await page.getByRole('button', { name: '결제하기' }).click();

  // 자동 재시도: 조건 만족까지 expect.timeout 동안 폴링
  await expect(page.getByRole('status')).toHaveText(/주문이 접수되었습니다/);
  await expect(page).toHaveURL(/\/orders\/\d+/);
});
```

핵심은 재시도 여부다. `expect(locator).toBeVisible()`은 조건 만족까지 폴링하지만, `expect(await locator.isVisible()).toBe(true)`는 `await`가 그 순간의 boolean을 뽑아 버려 재시도할 대상이 없고 렌더가 1ms만 늦어도 실패한다. strict mode도 설계를 강제해, 두 개 이상에 매칭되면 첫 번째를 고르는 대신 에러를 던진다.

```ts
await page.getByRole('button', { name: '삭제' }).click();
// → Error: strict mode violation: resolved to 3 elements

await page.getByRole('row', { name: '2026-09-10 리포트' })
  .getByRole('button', { name: '삭제' }).click();
```

trade-off: 접근성 이름은 텍스트에서 오므로 다국어 UI에서는 문구 변경에 민감하다. i18n 키가 자주 바뀌면 `getByTestId`가 안정적이지만, 남발하면 테스트가 사용자 관점에서 멀어진다.

## 5. 네트워크 제어

불안정의 두 번째 축은 백엔드다. 시드 데이터가 다른 테스트에 의해 바뀌거나 외부 API가 간헐적으로 죽으면 프런트엔드는 멀쩡한데 테스트가 빨갛게 된다. `page.route`로 요청을 가로채면 이 의존을 끊고 재현하기 어려운 분기까지 검증할 수 있다.

```ts
test('결제 서버 5xx 시 재시도 안내를 노출한다', async ({ page }) => {
  await page.route('**/api/payments', (route) =>
    route.fulfill({ status: 503, body: JSON.stringify({ message: 'unavailable' }) }),
  );

  await page.goto('/checkout');
  await page.getByRole('button', { name: '결제하기' }).click();
  await expect(page.getByRole('alert')).toContainText('잠시 후 다시');
});
```

응답을 기다릴 때는 `waitForResponse`를 액션보다 **먼저** 걸어 둔다. 나중에 걸면 그 사이 응답이 도착해 영영 기다리는 경합이 생긴다. 전 구간 모킹이 부담스러우면 HAR로 기록해 두고 재생한다.

```ts
const res = page.waitForResponse((r) => r.url().includes('/api/orders'));
await page.getByRole('button', { name: '결제하기' }).click();
expect((await (await res).json()).status).toBe('CONFIRMED');

// update: true 로 한 번 기록한 뒤 false 로 재생
await page.routeFromHAR('e2e/har/orders.har', { url: '**/api/**', update: false });
```

trade-off: 모킹은 결정론을 주지만 "프런트와 백엔드가 실제로 맞물리는가"라는 E2E 본연의 질문을 회피한다. 스키마가 바뀌어도 모킹된 테스트는 초록색이므로, 핵심 해피 패스는 실제 백엔드로 돌려 계약을 검증하고 에러·타임아웃 분기만 모킹한다.

## 6. 인증 상태 재사용

로그인을 테스트마다 반복하면 시나리오 수만큼 시간이 낭비되고, 로그인 화면이 바뀌면 전 스위트가 함께 깨진다. `storageState`는 쿠키와 origin별 localStorage를 JSON으로 저장·주입해 이 단계를 건너뛴다. 셋업은 프로젝트 의존성으로 분리한다. `globalSetup`과 달리 셋업이 테스트로 실행돼 실패 시 트레이스가 남는다.

```ts
// e2e/auth.setup.ts — test as setup, expect 를 @playwright/test 에서 import
setup('관리자 로그인', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('아이디').fill(process.env.ADMIN_ID!);
  await page.getByLabel('비밀번호').fill(process.env.ADMIN_PW!);
  await page.getByRole('button', { name: '로그인' }).click();
  await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();
  await page.context().storageState({ path: 'e2e/.auth/admin.json' });
});
```

```ts
// playwright.config.ts — 역할마다 setup 테스트와 상태 파일을 분리한다
projects: [
  { name: 'setup', testMatch: /.*\.setup\.ts/ },
  { name: 'admin', testMatch: /.*\.admin\.spec\.ts/,
    use: { ...devices['Desktop Chrome'], storageState: 'e2e/.auth/admin.json' },
    dependencies: ['setup'] },
  // member 프로젝트도 같은 형태로 storageState 만 바꿔 추가한다
],
```

두 역할이 한 테스트에 동시에 필요하면 `browser.newContext({ storageState })`로 컨텍스트를 두 개 만든다.

trade-off: 상태 파일은 토큰을 담으므로 `.gitignore`에 넣고 CI에서는 매 실행 새로 만든다. 토큰 만료가 있으면 오래된 파일이 401을 유발하므로 셋업을 캐시하지 않는다. 모두가 상태를 재사용하면 로그인이 깨져도 모르니 로그인 플로우 테스트는 하나 남긴다.

## 7. 트레이스와 디버깅

`trace: 'on-first-retry'`는 첫 실행은 트레이스 없이 돌리고 실패해 재시도할 때만 기록한다. `'on'`은 용량 부담이 크고 `'retain-on-failure'`도 모든 실행을 기록해 오버헤드가 든다. 트레이스에는 액션 타임라인, 액션 전후의 DOM 스냅샷, 네트워크, 콘솔, 소스 위치가 담긴다. 스냅샷이 실제 DOM이라 뷰어에서 구조를 뒤져볼 수 있어 CI에서만 재현되는 실패를 로컬에서 사후 분석할 수 있다. `await page.pause()`로 Inspector를 열어 멈출 수 있지만, 커밋 전 제거를 잊으면 CI가 타임아웃까지 매달린다.

```bash
npx playwright show-trace test-results/checkout/trace.zip   # 트레이스 뷰어
npx playwright test checkout.spec.ts --debug                # Inspector
```

```yaml
# .github/workflows/e2e.yml
name: E2E
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm }
      - run: npm ci
      - run: npx playwright install --with-deps
      - run: npx playwright test
      - uses: actions/upload-artifact@v4
        if: ${{ !cancelled() }}
        with:
          name: playwright-report
          path: playwright-report/
```

trade-off: 업로드 스텝은 `if: ${{ !cancelled() }}`로 걸어야 한다. 기본 `success()` 조건이면 정작 실패했을 때 아티팩트가 안 올라간다. 반대로 트레이스를 항상 남기면 용량과 업로드 시간이 쌓인다.

## 8. 시각 회귀와 컴포넌트 테스트

`toHaveScreenshot`은 첫 실행에서 기준 이미지를 만들고 이후 픽셀을 비교하며, 차이는 diff 이미지로 남는다. `mask`는 매번 달라지는 영역을 단색으로 덮고, `maxDiffPixelRatio`는 허용 오차, `threshold`는 픽셀 색상 민감도, `animations: 'disabled'`는 애니메이션을 종료 상태로 고정한다.

```ts
await expect(page).toHaveScreenshot('dashboard.png', {
  mask: [page.getByTestId('server-time')],
  maxDiffPixelRatio: 0.01,
  animations: 'disabled',
  fullPage: true,
});
```

가장 큰 함정은 플랫폼별 렌더링 차이다. 폰트 힌팅과 안티에일리어싱이 macOS와 Linux에서 다르므로 로컬에서 만든 기준 이미지는 Ubuntu 러너에서 깨진다. 공식 도커 이미지를 태그까지 못 박아 쓰고 기준 갱신도 그 컨테이너 안에서 해야 한다.

```bash
docker run --rm -v "$(pwd)":/work -w /work \
  mcr.microsoft.com/playwright:v1.47.0-jammy \
  npx playwright test --update-snapshots
```

컴포넌트 테스트(`@playwright/experimental-ct-react`)는 컴포넌트를 실제 브라우저에 마운트해 레이아웃·CSS·이벤트가 진짜지만, 실험적이라 필수 게이트보다는 특정 영역에 한정해 쓴다. 비중은 "이 실패를 잡는 가장 싼 계층"을 기준으로 정한다. 순수 로직과 상태 전이는 단위 테스트, 상호작용과 렌더 결과는 컴포넌트 테스트, 여러 화면과 실제 서버를 가로지르는 흐름은 E2E다.

trade-off: 시각 회귀는 커버리지가 넓지만 false positive가 많다. 습관적으로 `--update-snapshots`를 돌리면 진짜 UI 깨짐도 함께 승인되므로, 전체 페이지보다 컴포넌트 단위로 좁게 찍는다.

## 9. 안정성과 속도 운영

팀이 빨간 결과를 무시하기 시작하면 스위트 전체가 신뢰를 잃으므로 flaky는 방치하면 안 된다. `retries`를 켜면 재시도 후 통과한 테스트가 flaky로 분류되므로 이 목록을 주기적으로 확인하고, 의심 케이스는 `--repeat-each`로 재현율을 측정한다. 실행 시간은 샤딩으로 줄이는데, `blob` 리포터로 각 샤드 결과를 남긴 뒤 별도 job에서 `merge-reports`로 합치면 단일 HTML 리포트가 나온다.

```yaml
strategy:
  fail-fast: false
  matrix:
    shard: [1, 2, 3, 4]
steps:
  - run: npx playwright test --shard=${{ matrix.shard }}/4 --reporter=blob
  - uses: actions/upload-artifact@v4
    if: ${{ !cancelled() }}
    with: { name: blob-report-${{ matrix.shard }}, path: blob-report/ }
```

테스트 데이터 격리는 병렬화의 전제 조건이다. 공유 계정으로 여러 worker가 같은 레코드를 동시에 수정하면 실패가 비결정적이 된다. 대응은 worker별 전용 계정을 인덱스로 매핑하거나, 테스트마다 API로 데이터를 만들고 정리하거나, 테넌트를 분리하는 것이다.

```ts
export const test = base.extend<{}, { account: { id: string } }>({
  account: [async ({}, use, w) => {
    const id = `e2e-user-${w.workerIndex}`;
    await createAccount(id);
    await use({ id });
    await deleteAccount(id);
  }, { scope: 'worker' }],
});
```

trade-off: `retries`는 CI 노이즈를 줄이지만 근본 원인을 가리고, 샤딩은 머신 수만큼 세팅 비용이 곱해져 캐시가 없으면 이득이 상쇄된다. 스위트가 비대해지면 중복 E2E는 하나만 남기고 하위 계층에서 잡을 수 있는 검증은 단위 테스트로 내린다. 판단 기준은 "이 테스트가 지난 1년간 무엇을 막았는가"다.

## 참고

- [Playwright Docs — Getting Started](https://playwright.dev/docs/intro)
- [Auto-waiting and Actionability](https://playwright.dev/docs/actionability)
- [Locators](https://playwright.dev/docs/locators)
- [Assertions](https://playwright.dev/docs/test-assertions)
- [Network Mocking and HAR](https://playwright.dev/docs/mock)
- [Authentication](https://playwright.dev/docs/auth)
- [Trace Viewer](https://playwright.dev/docs/trace-viewer)
- [Visual Comparisons](https://playwright.dev/docs/test-snapshots)
- [Sharding](https://playwright.dev/docs/test-sharding)
