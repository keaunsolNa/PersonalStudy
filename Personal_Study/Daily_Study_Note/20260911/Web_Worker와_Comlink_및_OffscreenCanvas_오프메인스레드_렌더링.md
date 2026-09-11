Notion 원본: https://app.notion.com/p/3d85a06fd6d381de9b26db8f5e7f4b48?pvs=204

# Web Worker와 Comlink 및 OffscreenCanvas 오프메인스레드 렌더링

> 2026-09-11 신규 주제 · 확장 대상: 브라우저 렌더링 파이프라인

## 학습 목표

- 메인 스레드 점유가 INP 와 Long Task 로 이어지는 경로를 추적하고 워커 분리 지점을 판별한다
- structured clone 과 Transferable 의 비용 차이를 계산해 메시지 경계를 설계한다
- Comlink 와 OffscreenCanvas 로 계산·렌더링을 워커에 옮기는 코드를 작성한다
- 워커 도입이 손해가 되는 구간을 수치로 가려내고 대안을 고른다

## 1. 왜 메인 스레드를 비워야 하는가

브라우저의 메인 스레드는 JVM 의 워커 스레드 풀과 근본적으로 성격이 다르다. Tomcat 이라면 요청 하나가 오래 걸려도 다른 요청은 다른 스레드가 처리하지만, 브라우저 탭의 메인 스레드는 JS 실행, 스타일 재계산, 레이아웃, 페인트 커밋, 그리고 입력 이벤트 디스패치까지 단 하나의 이벤트 루프에서 순차 처리한다. 즉 JS 함수 하나가 200ms 동안 CPU 를 잡으면 그 200ms 동안 클릭 핸들러도, 레이아웃도, 다음 프레임 커밋도 전부 밀린다. 60fps 를 유지하려면 프레임당 예산이 약 16.7ms 이고 그 안에 브라우저 자체 작업도 들어가야 하므로, 실제 JS 에 허용되는 시간은 한 자릿수 ms 수준이다.

Long Task 는 메인 스레드를 50ms 이상 연속 점유한 작업을 뜻하고, 이 정의가 그대로 INP(Interaction to Next Paint) 지표로 이어진다. INP 는 사용자 상호작용이 발생한 시점부터 그 결과가 화면에 페인트될 때까지의 지연을 측정하며, web.dev 기준으로 200ms 이하가 "good" 이다. 긴 태스크 중간에 클릭이 들어오면 입력 지연(input delay)이 그대로 INP 에 가산된다.

메인 스레드 안에서 해결하려는 시도로 태스크 분할이 있다. `scheduler.yield()` 는 현재 태스크를 끕고 제어권을 브라우저에 넘기되 남은 작업의 우선순위를 유지한 채 재개하고, `navigator.scheduling.isInputPending()` 은 대기 중인 입력이 있는지 확인해 그때만 양보하도록 해준다.

```ts
async function processChunks(items: number[]): Promise<void> {
  for (let i = 0; i < items.length; i++) {
    heavyStep(items[i]);
    // 5ms 마다, 또는 입력이 대기 중이면 즉시 양보
    if (i % 256 === 0 && navigator.scheduling?.isInputPending?.()) {
      await scheduler.yield();
    }
  }
}
```

하지만 이 기법은 "작업을 잘게 쪨개 수 있을 때"만 통한다. 단일 호출로 끝나는 `JSON.parse` 4MB, `CryptoJS` 해시 루프, 이미지 필터 컨볼루션처럼 중간에 끕을 수 없는 연산은 양보 지점 자체가 없다. 또한 양보를 넣어도 총 CPU 시간은 줄지 않아 전체 완료 시간은 오히려 길어진다. 이 지점부터가 워커의 영역이다.

## 2. Worker 종류와 격리 모델

워커는 세 종류다. Dedicated Worker 는 자신을 생성한 문서 하나에만 종속되고 그 문서가 사라지면 같이 죽는다. Shared Worker 는 같은 origin 의 여러 탭/문서가 `port` 를 통해 공유하며 마지막 연결이 끊길 때까지 산다. Service Worker 는 네트워크 프록시 역할이 본업이고 이벤트 기반으로 깨어났다 죽기를 반복하므로 계산 오프로드에는 부적합하다.

| 종류 | 공유 범위 | 수명 | 주 용도 |
|---|---|---|---|
| Dedicated | 생성한 문서 1개 | 문서와 함께 종료 | CPU 계산, 렌더링 오프로드 |
| Shared | 동일 origin 의 여러 문서 | 모든 포트 닫힐 때까지 | 탭 간 상태/캐시 공유 |
| Service | origin 의 scope 전체 | 이벤트 단위로 기동·종료 | 오프라인 캐시, 네트워크 가로채기 |

워커 안에는 `window` 도 `document` 도 없다. 전역 객체는 `DedicatedWorkerGlobalScope` 이고 `self` 로 접근한다. `fetch`, `WebSocket`, `IndexedDB`, `crypto.subtle`, `performance` 는 쓸 수 있지만 DOM 조작은 불가능하다. JVM 의 스레드가 같은 힙을 공유하고 `synchronized` 로 조율하는 모델이라면, 워커는 기본이 완전 격리된 별도 JS 힙에 메시지 패싱으로만 통신하는 액터 모델에 가깝다. 이 격리 덕분에 데이터 레이스가 원천적으로 없고, 대신 데이터를 옮길 때마다 복제 비용을 낸다.

모듈 워커는 `type: 'module'` 로 생성하면 워커 안에서 `import` 를 쓸 수 있다. 번들러 환경에서는 경로를 문자열로 넘기면 빌드 시 해석되지 않으므로 `new URL(..., import.meta.url)` 패턴이 사실상 표준이다.

```ts
// Vite / webpack 5 공통으로 인식되는 워커 엔트리 선언
const worker = new Worker(new URL('./heavy.worker.ts', import.meta.url), {
  type: 'module',
  name: 'csv-parser', // DevTools 스레드 목록에 표시되어 디버깅이 쉬워진다
});

worker.postMessage({ cmd: 'parse', payload: rawText });
worker.onmessage = (e: MessageEvent<{ rows: number }>) => console.log(e.data.rows);
worker.onerror = (e) => console.error('worker crashed', e.message, e.filename, e.lineno);
```

```ts
// heavy.worker.ts
/// <reference lib="webworker" />
declare const self: DedicatedWorkerGlobalScope;

self.onmessage = (e: MessageEvent<{ cmd: string; payload: string }>) => {
  if (e.data.cmd === 'parse') {
    const rows = e.data.payload.split('\n').length;
    self.postMessage({ rows });
  }
};
export {};
```

## 3. structured clone 과 전송 비용

`postMessage` 로 넘긴 값은 structured clone 알고리즘으로 깊게 복사된다. `Map`, `Set`, `Date`, `RegExp`, `ArrayBuffer`, `Blob`, `File`, `ImageData`, 순환 참조까지 복제되지만 함수, `Symbol`, DOM 노드는 `DataCloneError` 를 던진다. 더 조용히 문제가 되는 것은 클래스 인스턴스다. 프로토타입이 소실되어 순수 객체로 도착하므로 `user instanceof User` 는 false 가 되고 메서드는 사라진다. `Error` 는 복제되지만 `name`/`message`/`stack` 정도만 보존되고 커스텀 필드는 구현에 따라 유실될 수 있으므로, 워커 에러는 직접 평범한 객체로 직렬화해 넘기는 편이 안전하다.

복제 비용은 데이터 크기에 선형 비례한다. 감각적으로 1MB 정도의 `Float64Array` 를 복제하면 보통 1ms 미만이라 무시할 만하지만, 10MB 를 매 프레임 주고받으면 왕복만으로 프레임 예산을 전부 태운다. 게다가 복제는 보내는 쪽 메인 스레드에서 동기로 일어나므로, 그 순간은 워커를 쓰고 있음에도 메인 스레드가 멈춘다.

Transferable 은 이 문제를 소유권 이전으로 푸다. `ArrayBuffer`, `MessagePort`, `ImageBitmap`, `OffscreenCanvas`, `ReadableStream` 등은 내용을 복사하지 않고 메모리 블록의 소유권만 넘기므로 크기와 무관하게 O(1) 이다.

```ts
const buf = new Float64Array(1_250_000).buffer; // 10MB

// (1) 복제: 10MB 를 통째로 복사한다 — 크기에 비례해 느리다
worker.postMessage({ buf });

// (2) 전송: 포인터만 넘긴다 — 크기와 무관하게 즉시
worker.postMessage({ buf }, [buf]);
console.log(buf.byteLength); // 0 — detach 되어 메인 스레드에서는 더 이상 못 쓴다
```

전송 후 원본이 detach 되어 `byteLength` 가 0 이 되는 것이 대표적인 함정이다. React 상태에 담아둔 버퍼를 전송하면 다음 렌더에서 빈 배열을 읽게 된다. 그래서 실무에서는 워커가 결과를 계산한 뒤 같은 버퍼(또는 결과 버퍼)를 다시 transfer 로 돌려주는 핑퍼 패턴, 즉 버퍼 소유권을 한쪽에서만 유지하는 규약을 세운다. 계속 재사용할 데이터라면 `buf.slice(0)` 로 복사본을 만들어 그쪽을 전송한다.

## 4. SharedArrayBuffer 와 Atomics

메시지 패싱 대신 진짜 공유 메모리를 쓰려면 `SharedArrayBuffer` 가 필요하다. 이건 transfer 가 아니라 postMessage 로 넘겨도 양쪽이 동일한 메모리를 본다. 다만 Spectre 대응으로 브라우저는 이 객체를 cross-origin isolated 문맥에서만 허용한다. 서버가 다음 두 헤더를 응답해야 한다.

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
```

부작용이 작지 않다. COEP require-corp 를 켜면 모든 cross-origin 하위 리소스(이미지, 스크립트, iframe)가 `Cross-Origin-Resource-Policy: cross-origin` 헤더를 주거나 CORS 로 로드돼야 하고, 그러지 못하는 서드파티 광고·분석 위젯·결제 iframe 은 통째로 깨진다. COOP same-origin 은 `window.opener` 관계를 끊어 OAuth 팝업 연동도 영향을 받는다. `self.crossOriginIsolated` 로 런타임에 활성 여부를 확인할 수 있다.

`Atomics` 는 Java 의 `volatile`/`AtomicInteger` 에 해당한다. `Atomics.add`, `Atomics.compareExchange` 가 원자성과 메모리 가시성을 보장하고, `Atomics.wait`/`Atomics.notify` 가 `Object.wait`/`notify` 에 대응한다. 결정적인 차이는 `Atomics.wait` 가 메인 스레드에서 금지된다는 점이다. 메인 스레드가 블로킹되면 이벤트 루프가 멈어 탭 전체가 응답 불가가 되기 때문이다. 메인 스레드에서는 논블로킹인 `Atomics.waitAsync` 를 쓴다.

```ts
// 메인: 공유 버퍼 준비 (crossOriginIsolated 필요)
const sab = new SharedArrayBuffer(8);
const flag = new Int32Array(sab);
worker.postMessage({ sab });

// 워커: 값이 0 인 동안 블로킹 대기 — 워커에서만 허용
const view = new Int32Array(sharedBuffer);
Atomics.wait(view, 0, 0);        // Java 의 wait() 에 해당
const done = Atomics.load(view, 0);

// 메인: 깨우기 (여기서 Atomics.wait 를 호출하면 TypeError)
Atomics.store(flag, 0, 1);
Atomics.notify(flag, 0);
```

## 5. Comlink — postMessage 를 RPC 로

`postMessage` 직접 사용은 금세 지저분해진다. 요청·응답 상관관계를 맞추려면 메시지마다 id 를 붙이고 pending 맵을 관리해야 하고, 커맨드 문자열 스위치가 비대해지며, 타입도 보장되지 않는다. Comlink 는 이 보일러플레이트를 `Proxy` 로 감싼다. 메인 쪽 객체의 프로퍼티 접근·함수 호출을 가로채 메시지로 변환하고, 워커 쪽에서 실제 객체에 적용한 뒤 결과를 되돌려준다. 구조상 gRPC 스텨이나 Java RMI 의 다이나믹 프록시와 같은 발상이다. 차이는 IDL 이 없고 TypeScript 타입만으로 계약을 표현한다는 점이다.

```ts
// math.worker.ts
import * as Comlink from 'comlink';

const api = {
  async fib(n: number): Promise<number> {
    return n < 2 ? n : (await api.fib(n - 1)) + (await api.fib(n - 2));
  },
  async transform(buf: ArrayBuffer, onProgress: (p: number) => void): Promise<ArrayBuffer> {
    const view = new Uint8Array(buf);
    for (let i = 0; i < view.length; i++) {
      view[i] = 255 - view[i];
      if (i % 100_000 === 0) onProgress(i / view.length);
    }
    return Comlink.transfer(buf, [buf]); // 결과도 전송으로 돌려준다
  },
};

export type MathApi = typeof api;
Comlink.expose(api);
```

```ts
// 메인 스레드
import * as Comlink from 'comlink';
import type { MathApi } from './math.worker';

const worker = new Worker(new URL('./math.worker.ts', import.meta.url), { type: 'module' });
const api: Comlink.Remote<MathApi> = Comlink.wrap<MathApi>(worker);

const n = await api.fib(30); // 모든 호출이 Promise 를 반환한다

const out = await api.transform(
  Comlink.transfer(buffer, [buffer]),
  Comlink.proxy((p) => setProgress(p)), // 콜백은 반드시 proxy 로 감싼다
);
```

모든 호출이 Promise 인 이유는 물리적으로 메시지 왕복이기 때문이다. 동기 반환은 불가능하고, 프로퍼티 읽기조차 `await api.count` 형태가 된다. `Remote<T>` 타입은 이 변환을 타입 수준에서 표현해 `T` 의 동기 시그니처를 자동으로 Promise 로 래핑해준다. 다만 클래스 인스턴스를 반환하면 메서드는 프록시 호출로 살아있지만 `instanceof` 는 성립하지 않는다.

콜백에 `Comlink.proxy` 가 필요한 이유는 함수가 structured clone 대상이 아니기 때문이다. Comlink 는 함수 대신 새 `MessageChannel` 의 포트를 넘겨 워커의 호출을 역방향 메시지로 전달한다. 문제는 이 포트가 명시적으로 해제되기 전까지 살아 있어 원본 클로저가 GC 되지 않는다는 점이다. 진행률 콜백을 렌더마다 새로 만들어 넘기면 포트가 계속 쌓인다. 사용이 끝난 프록시는 `proxyObj[Comlink.releaseProxy]()` 로 명시적으로 풀어주고, 워커 자체를 버릴 때는 `worker.terminate()` 를 호출한다.

## 6. OffscreenCanvas

DOM 을 못 만지는 워커가 유일하게 화면에 직접 그릴 수 있는 통로가 `OffscreenCanvas` 다. 메인 스레드에서 `canvas.transferControlToOffscreen()` 을 호출하면 그 canvas 의 렌더링 제어권이 Transferable 객체로 분리되어 워커로 넘어간다. 이후 메인 스레드는 해당 canvas 의 컨텍스트를 얻을 수 없다.

```ts
// 메인
const canvas = document.querySelector('canvas')!;
const offscreen = canvas.transferControlToOffscreen();
worker.postMessage({ canvas: offscreen, dpr: devicePixelRatio }, [offscreen]);
```

```ts
// render.worker.ts
/// <reference lib="webworker" />
let ctx: OffscreenCanvasRenderingContext2D;

self.onmessage = (e: MessageEvent<{ canvas: OffscreenCanvas; dpr: number }>) => {
  const { canvas, dpr } = e.data;
  canvas.width = 800 * dpr;
  canvas.height = 600 * dpr;
  ctx = canvas.getContext('2d')!;

  let t = 0;
  const loop = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#3b82f6';
    ctx.fillRect(200 + Math.sin((t += 0.05)) * 150, 250, 120, 120);
    requestAnimationFrame(loop); // DedicatedWorkerGlobalScope 에서 사용 가능
  };
  loop();
};
export {};
```

워커 전역에도 `requestAnimationFrame` 이 존재해 vsync 에 맞컸 루프를 돌릴 수 있다. 지원이 불확실한 환경이나 화면과 무관한 오프스크린 합성에서는 `setTimeout` 대신 정밀도가 나은 방식으로 자체 타이밍을 잡고 마지막에 `canvas.transferToImageBitmap()` 으로 프레임을 뽑아 메인에 넘기는 대안도 쓴다. 컨텍스트는 `2d` 와 `webgl`/`webgl2`/`webgpu` 모두 지원된다.

이미지 디코딩까지 워커로 옮기려면 `createImageBitmap` 을 쓴다. `Blob` 이나 `ImageData` 에서 GPU 업로드에 바로 쓸 수 있는 `ImageBitmap` 을 만들어주고, 이 객체 역시 Transferable 이므로 워커에서 만들어 메인으로 O(1) 에 넘길 수 있다. `<img>` 태그로 로드할 때 메인 스레드에서 일어나던 디코딩 비용을 통째로 걷어내는 셈이다.

```ts
const blob = await fetch('/large.png').then((r) => r.blob());
const bitmap = await createImageBitmap(blob, { resizeWidth: 512, resizeQuality: 'high' });
self.postMessage({ bitmap }, [bitmap]);
```

가장 큰 이점은 메인 스레드가 Long Task 로 막혀 있어도 워커의 애니메이션은 계속 돌다는 것이다. 대용량 테이블 렌더링이나 라우트 전환 중에도 스피너·차트가 끊기지 않는다. 한계는 명확하다. 입력 이벤트는 여전히 메인 스레드에서만 발생하므로 `canvas.addEventListener('pointermove', ...)` 는 메인에 남고, 좌표를 워커로 postMessage 해야 한다. 즉 메인이 막히면 그림은 돌아도 상호작용 반응은 여전히 늦다.

## 7. React/Next.js 통합 실전

Next.js 에서 첫 걸림돌은 SSR 이다. `Worker` 는 서버에 없으므로 모듈 최상단에서 생성하면 빌드가 깨진다. 생성은 반드시 `useEffect` 안(클라이언트 전용 시점)에서 하고, 워커를 참조하는 컴포넌트는 `dynamic(() => import('./Heavy'), { ssr: false })` 로 감싸는 편이 안전하다.

```ts
'use client';
import { useEffect, useRef, useState, useCallback } from 'react';
import * as Comlink from 'comlink';
import type { CsvApi } from './csv.worker';

export function useCsvWorker() {
  const apiRef = useRef<Comlink.Remote<CsvApi> | null>(null);
  const workerRef = useRef<Worker | null>(null);
  const seqRef = useRef(0);
  const [rows, setRows] = useState<string[][]>([]);

  useEffect(() => {
    const w = new Worker(new URL('./csv.worker.ts', import.meta.url), { type: 'module' });
    workerRef.current = w;
    apiRef.current = Comlink.wrap<CsvApi>(w);
    return () => {
      apiRef.current?.[Comlink.releaseProxy]();
      w.terminate(); // 언마운트 시 반드시 종료
    };
  }, []);

  const parse = useCallback(async (text: string, signal?: AbortSignal) => {
    const seq = ++seqRef.current;
    if (signal?.aborted) return;
    // 취소 시 워커를 죽이는 방식: 진행 중 계산을 실제로 중단할 유일한 수단
    signal?.addEventListener('abort', () => workerRef.current?.terminate(), { once: true });
    const result = await apiRef.current!.parse(text);
    if (seq === seqRef.current) setRows(result); // 늦게 도착한 응답은 폐기
  }, []);

  return { rows, parse };
}
```

두 가지 흔한 버그를 위 코드가 막는다. 첫째는 경쟁 상태다. 사용자가 빠르게 두 번 요청하면 먼저 보낸 것이 나중에 도착할 수 있으므로 시퀀스 번호로 최신 응답만 반영한다. 둘째는 취소다. 워커 안에서 도는 동기 루프는 `AbortSignal` 로 중단되지 않는다. 진짜로 멈추려면 `terminate()` 로 워커를 죽이고 새로 만들거나, 워커 루프 안에서 `SharedArrayBuffer` 플래그를 주기적으로 확인해야 한다.

동시에 여러 작업을 돌릴 때는 워커 풀을 만든다. 워커 수는 `navigator.hardwareConcurrency` 를 상한으로 잡되, 브라우저 자체가 쓸 코어를 남겨 보통 `hardwareConcurrency - 1` 정도로 제한한다. Java 의 `FixedThreadPool` 과 발상이 같다.

```ts
class WorkerPool<T> {
  private idle: Comlink.Remote<T>[] = [];
  private queue: ((api: Comlink.Remote<T>) => void)[] = [];

  constructor(factory: () => Worker, size = Math.max(1, (navigator.hardwareConcurrency ?? 4) - 1)) {
    for (let i = 0; i < size; i++) this.idle.push(Comlink.wrap<T>(factory()));
  }

  async run<R>(task: (api: Comlink.Remote<T>) => Promise<R>): Promise<R> {
    const api = this.idle.pop() ?? (await new Promise<Comlink.Remote<T>>((r) => this.queue.push(r)));
    try {
      return await task(api);
    } finally {
      const next = this.queue.shift();
      next ? next(api) : this.idle.push(api);
    }
  }
}
```

## 8. 언제 워커를 쓰지 말아야 하는가

워커는 공짜가 아니다. 새 워커 기동은 별도 JS 실행 컨텍스트와 힙을 만드는 일이라 보통 수 ms, 모듈 그래프가 크면 수십 ms 가 든다. 여기에 왕복 메시지 지연 최소 1ms 내외와 직렬화 비용이 더해진다. 따라서 10ms 짜리 계산을 워커로 보내면 거의 확실히 손해다. 경험칙으로 메인 스레드에서 50ms 이상 걸리고 데이터가 Transferable 로 넘길 수 있거나 작을 때 이득이 난다. 반대로 "계산은 20ms 인데 입력 객체가 20MB 짜리 JSON" 이면 복제 비용이 계산 이득을 삼킨다.

운영 관점의 비용도 있다. 워커마다 독립 힙이라 메모리 사용량이 늘고, 풀을 8개 띄우면 그만큼 곱해진다. 에러 스택은 워커 파일 기준이라 소스맵 설정이 부실하면 추적이 어렵고, `try/catch` 가 경계를 넘지 않아 `onerror`/`unhandledrejection` 을 워커 안에서 따로 잡아 직렬화해 보내야 한다. React DevTools 나 브라우저 프로파일러도 워커 스레드를 별도로 선택해야 보인다.

| 상황 | 워커보다 나은 선택 | 이유 |
|---|---|---|
| 대량 목록 렌더링이 느림 | 가상 스크롤(react-window 등) | 병목이 계산이 아니라 DOM 노드 수 |
| 알고리즘이 O(n²) | 자료구조·알고리즘 개선 | 코어를 늘려도 복잡도는 그대로 |
| 수치 연산이 극단적으로 무거움 | WASM(+워커 조합) | JS 대비 연산 자체가 빠름, 워커와 병행 가능 |
| 입력 데이터가 원래 서버에 있음 | 서버 오프로드 | 전송·직렬화 없이 결과만 받음 |
| 20ms 내외 단발 계산 | `scheduler.yield` 분할 | 기동·직렬화 오버헤드가 이득보다 큼 |
| 애니메이션이 메인 블로킹에 끊김 | OffscreenCanvas | 렌더 루프만 분리해도 해결 |

결정 순서는 단순하다. 먼저 Performance 패널로 Long Task 의 실제 원인이 JS 계산인지 레이아웃·페인트인지 확인한다. 레이아웃이 원인이면 워커는 아무 도움이 안 된다. JS 계산이 맞다면 입력·출력 데이터 크기를 재고 Transferable 로 옮길 수 있는지 본다. 그 두 조건이 맞을 때 비로소 워커가 정답이고, 이때 Comlink 로 통신 코드를 단순화하고 렌더링까지 얽혀 있다면 OffscreenCanvas 를 함께 쓴다.

## 참고

- MDN, Web Workers API: https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API
- MDN, Using Web Workers: https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers
- MDN, OffscreenCanvas: https://developer.mozilla.org/en-US/docs/Web/API/OffscreenCanvas
- MDN, Transferable objects: https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Transferable_objects
- HTML Living Standard, Web workers: https://html.spec.whatwg.org/multipage/workers.html
- GoogleChromeLabs/comlink: https://github.com/GoogleChromeLabs/comlink
- web.dev, Interaction to Next Paint (INP): https://web.dev/articles/inp
- web.dev, Use web workers to run JavaScript off the browser's main thread: https://web.dev/articles/off-main-thread
