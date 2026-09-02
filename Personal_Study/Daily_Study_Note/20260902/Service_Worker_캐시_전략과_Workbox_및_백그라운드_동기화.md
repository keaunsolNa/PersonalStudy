Notion 원본: https://www.notion.so/3cf5a06fd6d3812980f1f8c19bd84806

# Service Worker 캐시 전략과 Workbox 및 백그라운드 동기화

> 2026-09-02 신규 주제 · 확장 대상: 브라우저 렌더링 파이프라인·INP 최적화, CDN 캐시 계층

## 학습 목표

- Service Worker 생명주기(install → waiting → activate)와 업데이트 함정을 추적한다
- 자원 유형별 캐시 전략을 선택하고 Cache Storage 와 HTTP 캐시의 관계를 구분한다
- Workbox 로 프리캐시 매니페스트와 런타임 라우팅을 구성한다
- Background Sync 와 IndexedDB 큐로 오프라인 쓰기를 안전하게 재시도한다

## 1. CDN 캐시가 닿지 않는 곳

CDN 은 서버와 사용자 사이의 지연을 줄인다. 그러나 네트워크가 아예 없거나(지하철, 엘리베이터), 있어도 첫 바이트까지 수백 ms 걸리는 모바일 환경에서는 CDN 도 무력하다. Service Worker 는 브라우저와 네트워크 사이에 프로그래머블 프록시를 넣어 이 마지막 구간을 통제한다.

핵심 특징 세 가지가 설계를 규정한다.

- **별도 워커 스레드**에서 돌고 DOM 에 접근할 수 없다. 페이지와는 `postMessage` 로만 통신한다.
- **이벤트 기반이며 언제든 종료된다.** 브라우저가 유휴 워커를 수십 초 만에 죽인다. 전역 변수에 상태를 두면 사라지므로 영속 상태는 Cache Storage 나 IndexedDB 에 둔다.
- **HTTPS 전용**이다(localhost 예외). 중간자가 SW 를 심으면 사이트를 영구 장악할 수 있기 때문이다.

## 2. 생명주기 — 업데이트가 안 먹는 이유

등록부터 활성화까지의 흐름은 다음과 같다.

```
register() → 스크립트 다운로드 → install 이벤트
                                     ↓
                          (기존 SW 가 제어 중이면) waiting
                                     ↓ 모든 탭이 닫히거나 skipWaiting()
                                  activate 이벤트
                                     ↓
                                  controlling
```

가장 흔한 혼란은 "배포했는데 사용자가 옛 버전을 계속 본다" 이다. 원인은 `waiting` 단계다. 새 SW 는 설치까지 끝내고 대기하지만, 기존 SW 가 제어하는 탭이 하나라도 열려 있으면 활성화되지 않는다. 사용자가 새로고침만 해서는 탭이 닫히지 않으므로 영원히 대기한다.

```javascript
// sw.js
self.addEventListener('install', (event) => {
	event.waitUntil(precache());
	// self.skipWaiting();   // 즉시 활성화 — 주의 필요
});

self.addEventListener('activate', (event) => {
	event.waitUntil((async () => {
		const keys = await caches.keys();
		await Promise.all(
			keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)),
		);
		await self.clients.claim();
	})());
});
```

`skipWaiting()` 을 무조건 켜는 것은 위험하다. 이미 열려 있는 탭이 구 버전 JS 청크를 지연 로딩하려는 순간 새 SW 가 그 청크를 지워버리면 `Failed to fetch dynamically imported module` 이 난다. 코드 분할을 쓰는 SPA 에서 자주 겪는 증상이다.

안전한 패턴은 사용자에게 알리고 동의를 받는 것이다.

```javascript
// 페이지 쪽
const registration = await navigator.serviceWorker.register('/sw.js');

registration.addEventListener('updatefound', () => {
	const installing = registration.installing;
	installing?.addEventListener('statechange', () => {
		if (installing.state === 'installed' && navigator.serviceWorker.controller) {
			showToast('새 버전이 준비되었습니다', {
				action: '새로고침',
				onAction: () => installing.postMessage({ type: 'SKIP_WAITING' }),
			});
		}
	});
});

let reloading = false;
navigator.serviceWorker.addEventListener('controllerchange', () => {
	if (reloading) return;
	reloading = true;
	window.location.reload();
});
```

```javascript
// sw.js
self.addEventListener('message', (event) => {
	if (event.data?.type === 'SKIP_WAITING') {
		self.skipWaiting();
	}
});
```

`reloading` 가드가 없으면 `controllerchange` 가 여러 번 발화해 무한 새로고침에 빠질 수 있다.

또 하나의 함정은 SW 스크립트 자체의 캐싱이다. 브라우저는 `sw.js` 를 HTTP 캐시에서 가져올 수 있고, 그러면 새 버전을 영영 못 본다. 명세상 SW 스크립트는 최대 24시간까지만 캐시되지만, 배포 서버에서 명시적으로 막는 것이 안전하다.

```
Cache-Control: no-cache, max-age=0
```

`no-store` 가 아니라 `no-cache` 다. `no-store` 는 일부 브라우저에서 등록 자체를 방해한 이력이 있다.

## 3. Cache Storage 와 HTTP 캐시의 관계

`caches` API 는 HTTP 캐시와 완전히 별개의 저장소다. 스크립트가 명시적으로 넣고 빼며, `Cache-Control` 헤더를 해석하지 않는다. 즉 `caches.match()` 가 반환한 응답은 서버가 뭐라고 했든 만료되지 않는다. 만료 관리는 개발자 책임이다.

`fetch()` 를 SW 안에서 호출하면 그건 HTTP 캐시를 거친다. 따라서 두 계층이 겹친다.

```
페이지 요청 → SW fetch 이벤트 → caches.match()  (Cache Storage)
                                → fetch()        → HTTP 캐시 → 네트워크
```

이중 캐싱을 피하려면 SW 에서 프리캐시 대상을 받을 때 `cache: 'reload'` 를 줘 HTTP 캐시를 우회한다.

```javascript
await cache.addAll(urls.map((u) => new Request(u, { cache: 'reload' })));
```

용량은 Storage Quota 로 관리된다. 오리진당 대략 디스크 여유의 일정 비율(Chrome 은 60% 수준)이 상한이고, 넘으면 `QuotaExceededError` 가 난다. 실제 사용량은 다음으로 확인한다.

```javascript
const { usage, quota } = await navigator.storage.estimate();
console.log(`${(usage / quota * 100).toFixed(1)}% used`);
```

브라우저는 디스크 압박 시 오리진 데이터를 통째로 축출할 수 있다. 사용자에게 중요한 데이터라면 `navigator.storage.persist()` 로 영속 권한을 요청한다(사용자 참여도가 높은 사이트에만 자동 승인된다).

## 4. 자원 유형별 캐시 전략

다섯 가지 전략이 사실상 표준이다.

| 전략 | 동작 | 적합한 자원 |
|---|---|---|
| Cache First | 캐시 있으면 반환, 없으면 네트워크 후 저장 | 해시 파일명 JS/CSS, 폰트, 이미지 |
| Network First | 네트워크 시도, 실패 시 캐시 | HTML 문서, 자주 바뀌는 API |
| Stale-While-Revalidate | 캐시 즉시 반환 + 백그라운드 갱신 | 아바타, 목록 API, 로고 |
| Network Only | 항상 네트워크 | 인증, 결제, POST |
| Cache Only | 항상 캐시 | 프리캐시된 앱 셸 |

Cache First 를 해시 없는 URL 에 걸면 영구히 낡은 파일을 서빙하게 된다. 반드시 빌드 도구가 `main.a1b2c3.js` 형태로 지문을 넣는 자원에만 쓴다.

직접 구현하면 이렇다.

```javascript
const RUNTIME = 'runtime-v3';

async function staleWhileRevalidate(request) {
	const cache = await caches.open(RUNTIME);
	const cached = await cache.match(request);

	const network = fetch(request)
		.then((response) => {
			if (response.ok && response.type !== 'opaque') {
				cache.put(request, response.clone());
			}
			return response;
		})
		.catch(() => undefined);

	return cached ?? (await network) ?? Response.error();
}

self.addEventListener('fetch', (event) => {
	const { request } = event;
	if (request.method !== 'GET') return;

	const url = new URL(request.url);
	if (url.origin !== self.location.origin) return;

	if (request.mode === 'navigate') {
		event.respondWith(networkFirst(request));
	} else if (/\.(js|css|woff2)$/.test(url.pathname)) {
		event.respondWith(cacheFirst(request));
	} else if (url.pathname.startsWith('/api/')) {
		event.respondWith(staleWhileRevalidate(request));
	}
});
```

`response.clone()` 이 필수인 이유는 Response 본문이 일회용 스트림이기 때문이다. 캐시에 넣으면서 동시에 반환하려면 복제해야 한다.

`response.type !== 'opaque'` 검사도 중요하다. `no-cors` 로 가져온 교차 출처 응답은 상태 코드가 0 이고 본문을 읽을 수 없는 opaque 응답인데, 이것을 캐시에 넣으면 브라우저가 실제 크기와 무관하게 **7MB 정도의 패딩**을 쿼터에 계산한다. 폰트나 서드파티 이미지를 무심코 캐시하면 쿼터가 순식간에 찬다.

`fetch` 이벤트에서 `event.respondWith()` 를 호출하지 않으면 브라우저 기본 동작으로 넘어간다. 위 코드처럼 조건에 안 걸리면 early return 하는 편이, 모든 요청을 SW 가 프록시하며 생기는 오버헤드(요청당 수 ms)를 줄인다.

## 5. Workbox 로 정리하기

직접 짠 SW 는 프리캐시 매니페스트 관리, 만료 정책, 라우팅 우선순위에서 금방 복잡해진다. Workbox 는 이를 선언적으로 만든다.

```javascript
// src/sw.js
import { precacheAndRoute, cleanupOutdatedCaches, createHandlerBoundToURL } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
import { CacheFirst, NetworkFirst, NetworkOnly, StaleWhileRevalidate } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';
import { CacheableResponsePlugin } from 'workbox-cacheable-response';
import { BackgroundSyncPlugin } from 'workbox-background-sync';

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

registerRoute(
	new NavigationRoute(createHandlerBoundToURL('/index.html'), {
		denylist: [/^\/api\//, /^\/admin\//],
	}),
);

registerRoute(
	({ request }) => request.destination === 'image',
	new CacheFirst({
		cacheName: 'images',
		plugins: [
			new CacheableResponsePlugin({ statuses: [0, 200] }),
			new ExpirationPlugin({
				maxEntries: 120,
				maxAgeSeconds: 30 * 24 * 60 * 60,
				purgeOnQuotaError: true,
			}),
		],
	}),
);

registerRoute(
	({ url }) => url.pathname.startsWith('/api/catalog'),
	new StaleWhileRevalidate({
		cacheName: 'api-catalog',
		plugins: [
			new ExpirationPlugin({ maxEntries: 60, maxAgeSeconds: 5 * 60 }),
		],
	}),
);

registerRoute(
	({ url, request }) => url.pathname.startsWith('/api/orders') && request.method === 'POST',
	new NetworkOnly({
		plugins: [
			new BackgroundSyncPlugin('order-queue', { maxRetentionTime: 24 * 60 }),
		],
	}),
	'POST',
);
```

`self.__WB_MANIFEST` 는 빌드 시 주입되는 자리표시자다. Vite 라면 `vite-plugin-pwa`, webpack 이면 `workbox-webpack-plugin` 의 `InjectManifest` 가 채운다.

```typescript
// vite.config.ts
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
	plugins: [
		VitePWA({
			strategies: 'injectManifest',
			srcDir: 'src',
			filename: 'sw.js',
			injectManifest: {
				globPatterns: ['**/*.{js,css,html,woff2,svg}'],
				maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
			},
			devOptions: { enabled: true, type: 'module' },
		}),
	],
});
```

매니페스트 항목은 `{ url, revision }` 쌍이다. 해시가 파일명에 있으면 `revision: null` 이 되어 URL 자체가 버전이고, 없으면 내용 해시가 revision 이 된다. Workbox 는 revision 이 바뀐 항목만 다시 받으므로 배포 시 델타 업데이트가 된다.

`NavigationRoute` + `createHandlerBoundToURL('/index.html')` 은 SPA 의 App Shell 패턴이다. 어떤 경로로 진입해도 프리캐시된 셸을 즉시 띄우고 라우터가 나머지를 처리한다. `denylist` 로 API 와 서버 렌더링 경로를 빼야 한다 — 빼지 않으면 `/api/...` 로의 내비게이션이 index.html 을 받는다.

## 6. Background Sync — 오프라인 쓰기

읽기는 캐시로 해결되지만 쓰기는 다르다. 오프라인에서 사용자가 주문을 넣으면 그 요청을 보관했다가 온라인이 되면 보내야 한다. Background Sync API 가 이를 브라우저 수준에서 지원한다.

```javascript
// 페이지 쪽
async function submitOrder(payload) {
	try {
		const res = await fetch('/api/orders', {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify(payload),
		});
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		return await res.json();
	}
	catch {
		await enqueue(payload);
		const reg = await navigator.serviceWorker.ready;
		await reg.sync.register('order-queue');
		return { queued: true };
	}
}
```

```javascript
// sw.js — 직접 구현 버전
import { openDB } from 'idb';

const db = openDB('outbox', 1, {
	upgrade(d) {
		d.createObjectStore('orders', { keyPath: 'id', autoIncrement: true });
	},
});

self.addEventListener('sync', (event) => {
	if (event.tag === 'order-queue') {
		event.waitUntil(drainQueue());
	}
});

async function drainQueue() {
	const conn = await db;
	const items = await conn.getAll('orders');

	for (const item of items) {
		const res = await fetch('/api/orders', {
			method: 'POST',
			headers: {
				'content-type': 'application/json',
				'idempotency-key': item.idempotencyKey,
			},
			body: JSON.stringify(item.payload),
		});

		if (res.ok || (res.status >= 400 && res.status < 500)) {
			await conn.delete('orders', item.id);   // 성공 또는 영구 실패
		} else {
			throw new Error('retry');   // 던지면 브라우저가 sync 를 재스케줄
		}
	}

	const clients = await self.clients.matchAll();
	clients.forEach((c) => c.postMessage({ type: 'QUEUE_DRAINED' }));
}
```

설계상 반드시 지켜야 할 점이 세 가지다.

**멱등성 키.** `sync` 핸들러에서 예외를 던지면 브라우저가 재시도하는데, 서버가 이미 요청을 처리했지만 응답이 유실된 경우 중복 생성이 된다. `Idempotency-Key` 헤더를 클라이언트가 생성해 보내고 서버가 중복을 흡수해야 한다.

**4xx 와 5xx 구분.** 400 Bad Request 를 재시도하면 영원히 큐가 안 빠진다. 4xx 는 큐에서 제거하고 사용자에게 알린다.

**지수 백오프는 브라우저가 한다.** `sync` 이벤트에서 던지면 브라우저가 자체 백오프로 재시도하되, 시도 횟수에 상한이 있다(Chrome 은 약 3회, 총 몇 시간). 무한 재시도를 기대하면 안 되고, 앱이 다시 열렸을 때 큐를 검사하는 폴백이 필요하다.

지원 범위도 확인해야 한다. Background Sync 는 Chromium 계열에서만 안정적이고 Safari 와 Firefox 는 미지원이다. 따라서 큐 자체는 IndexedDB 에 두되, 드레인 트리거를 `sync` 이벤트 + `online` 이벤트 + 앱 시작 시점 세 곳에 거는 것이 실용적이다.

```javascript
window.addEventListener('online', () => {
	navigator.serviceWorker.controller?.postMessage({ type: 'DRAIN_QUEUE' });
});
```

## 7. 디버깅과 테스트

SW 는 상태가 브라우저에 남아 디버깅이 까다롭다. 도구는 다음과 같다.

- **DevTools → Application → Service Workers**: `Update on reload` 체크박스를 개발 중 켜두면 매 새로고침마다 새 SW 를 설치·활성화한다. `Bypass for network` 는 SW 를 완전히 우회한다.
- **Application → Cache Storage**: 실제 캐시 내용과 키를 확인한다. 예상과 다른 URL 이 들어 있으면 라우팅 조건이 틀린 것이다.
- **Application → Storage → Clear site data**: 상태를 완전히 초기화한다. 재현 안 되는 버그의 절반은 낡은 SW 때문이다.
- **`chrome://serviceworker-internals`**: 등록 상태와 마지막 에러를 원시 수준으로 본다.

자동화 테스트는 Playwright 로 가능하다.

```typescript
test('offline navigation serves app shell', async ({ page, context }) => {
	await page.goto('/');
	await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

	await context.setOffline(true);
	await page.goto('/orders/42');

	await expect(page.locator('[data-testid="app-shell"]')).toBeVisible();
	await expect(page.locator('[data-testid="offline-banner"]')).toBeVisible();
});

test('queued order is submitted after reconnect', async ({ page, context }) => {
	await page.goto('/');
	await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

	await context.setOffline(true);
	await page.getByRole('button', { name: '주문' }).click();
	await expect(page.getByText('오프라인 — 전송 대기 중')).toBeVisible();

	const requestPromise = page.waitForRequest((r) =>
		r.url().includes('/api/orders') && r.method() === 'POST');
	await context.setOffline(false);

	const request = await requestPromise;
	expect(request.headers()['idempotency-key']).toBeTruthy();
});
```

`waitForFunction(() => navigator.serviceWorker.controller !== null)` 은 필수다. 첫 방문에서는 SW 가 아직 페이지를 제어하지 않으므로, 이를 기다리지 않으면 테스트가 SW 를 거치지 않고 통과해버린다.

## 8. 도입 시 주의점과 회수 경로

Service Worker 는 배포하면 되돌리기 어렵다. 사용자 브라우저에 코드가 남아 서버가 무엇을 보내든 가로챌 수 있기 때문이다. 잘못된 SW 를 배포하면 사이트 전체가 죽고, 사용자가 캐시를 지우기 전까지 복구되지 않는다.

그래서 **처음부터 kill switch 를 심어둔다**.

```javascript
self.addEventListener('install', (event) => {
	event.waitUntil((async () => {
		const res = await fetch('/sw-kill-switch.json', { cache: 'no-store' }).catch(() => null);
		if (res?.ok) {
			const { disabled } = await res.json();
			if (disabled) {
				await self.registration.unregister();
				const clients = await self.clients.matchAll();
				clients.forEach((c) => c.navigate(c.url));
				return;
			}
		}
		await precache();
	})());
});
```

이 파일 하나를 `{"disabled": true}` 로 바꾸면 다음 SW 갱신 검사 때 전 사용자가 SW 를 해제한다. 검사는 내비게이션마다 또는 최대 24시간 주기로 일어난다.

도입 판단은 다음과 같이 정리할 수 있다.

- **명확히 이득**: 모바일 비중이 높고 재방문율이 높은 서비스, 네트워크 불안정 환경(현장 작업 앱, 물류), 오프라인 열람 요구가 있는 콘텐츠 앱, 설치형 PWA.
- **이득이 작음**: 데스크톱 위주 사내 도구(이미 안정적 네트워크), 일회성 방문이 대부분인 랜딩 페이지, 이미 CDN + 강한 HTTP 캐싱이 잘 걸린 정적 사이트.
- **주의 필요**: 인증·결제 흐름이 있는 화면. 캐시된 페이지가 로그아웃 상태와 어긋나 개인정보가 노출될 수 있다. 인증 관련 요청과 응답은 예외 없이 Network Only 로 두고, 로그아웃 시 관련 캐시를 명시적으로 비운다.

마지막으로, SW 는 성능 도구이지 아키텍처 문제의 해결책이 아니다. 초기 로딩이 느린 근본 원인이 3MB 짜리 번들이라면 SW 는 두 번째 방문부터만 도움이 되고 첫 방문은 오히려 SW 등록 비용만큼 느려진다. 번들 크기, 코드 분할, 서버 응답 시간을 먼저 손보고 그 위에 얹는 것이 순서다.

## 참고

- MDN — Service Worker API, Cache API, Background Sync API
- W3C — Service Workers Specification (https://w3c.github.io/ServiceWorker/)
- Workbox Documentation — Strategies, Precaching, Background Sync (https://developer.chrome.com/docs/workbox)
- web.dev — Storage for the web, Quota management and eviction
- Jake Archibald, *The Service Worker Lifecycle* (developer.chrome.com)
