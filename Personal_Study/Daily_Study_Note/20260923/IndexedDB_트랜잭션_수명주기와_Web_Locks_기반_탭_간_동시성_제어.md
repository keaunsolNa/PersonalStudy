Notion 원본: https://www.notion.so/3e45a06fd6d3811d8b73e00654697ca4

# IndexedDB 트랜잭션 수명주기와 Web Locks 기반 탭 간 동시성 제어

> 2026-09-23 신규 주제 · 확장 대상: Javascript

## 학습 목표

- IndexedDB 트랜잭션의 자동 커밋 규칙과 비활성화 시점을 이벤트 루프 기준으로 추적한다
- 스코프·모드 기반 트랜잭션 스케줄링으로 교착과 직렬화 병목을 제어한다
- Web Locks API 로 여러 탭·워커 사이의 임계 구역을 조율한다
- 버전 업그레이드와 blocked 상태를 다중 탭 환경에서 안전하게 처리한다

## 1. 트랜잭션은 생성 즉시 시작되지 않는다

IndexedDB 의 트랜잭션은 `db.transaction(스코프, 모드)` 호출로 객체가 만들어지지만, 실제로 활성화되는 시점은 브라우저가 스코프에 대한 잠금을 확보한 뒤다. 그 전까지 트랜잭션은 대기 상태이고, 요청을 발행하면 큐에 쌓인다.

더 중요한 것은 종료 규칙이다. 명시적 `commit()` 호출 없이도, **모든 요청이 완료되고 제어가 이벤트 루프로 돌아가는 순간** 트랜잭션은 자동 커밋된다. 다시 말해 트랜잭션은 "태스크 경계" 를 넘어 살아남지 못한다. 이 규칙이 IndexedDB 에서 가장 많은 버그를 만든다.

```js
// 동작하지 않는 코드 — await fetch 사이에 트랜잭션이 죽는다
async function brokenSave(db, id) {
  const tx = db.transaction('orders', 'readwrite');
  const store = tx.objectStore('orders');

  const remote = await fetch(`/api/orders/${id}`).then(r => r.json()); // 매크로태스크 경계
  store.put(remote); // TransactionInactiveError
}
```

`await fetch(...)` 는 마이크로태스크가 아니라 네트워크 완료를 기다리는 매크로태스크 경계다. 이 시점에 트랜잭션은 이미 커밋되어 비활성 상태가 된다. 올바른 구조는 I/O 를 트랜잭션 밖으로 빼는 것이다.

```js
async function save(db, id) {
  const remote = await fetch(`/api/orders/${id}`).then(r => r.json());

  return new Promise((resolve, reject) => {
    const tx = db.transaction('orders', 'readwrite');
    tx.objectStore('orders').put(remote);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new DOMException('aborted', 'AbortError'));
  });
}
```

Promise 기반 `await` 를 트랜잭션 안에서 쓸 수 있는 경우도 있다. 마이크로태스크만 소비하는 `await`(이미 resolve 된 Promise) 는 같은 태스크 안에서 처리되므로 트랜잭션이 유지된다. 하지만 이 구분은 미묘하고 브라우저 구현에 따라 경계가 다를 수 있어, 실무에서는 "트랜잭션 안에서는 IndexedDB 요청 외의 await 를 쓰지 않는다" 를 규칙으로 고정하는 편이 안전하다.

## 2. 스코프와 모드가 만드는 스케줄링

트랜잭션 스케줄링 규칙은 단순하다. 같은 오브젝트 스토어를 스코프로 갖는 `readwrite` 트랜잭션은 서로 직렬화된다. `readonly` 끼리는 병렬 수행된다. 스코프가 겹치지 않으면 모드와 무관하게 병렬이다.

| 트랜잭션 A | 트랜잭션 B | 스코프 | 결과 |
|---|---|---|---|
| readonly | readonly | 동일 | 병렬 |
| readonly | readwrite | 동일 | 직렬(생성 순) |
| readwrite | readwrite | 동일 | 직렬(생성 순) |
| readwrite | readwrite | 분리 | 병렬 |

여기서 "생성 순" 이 핵심이다. IndexedDB 는 트랜잭션 생성 순서대로 시작을 보장한다. 따라서 넓은 스코프의 `readwrite` 트랜잭션을 먼저 만들어두면 그 뒤에 생성된 모든 겹치는 트랜잭션이 대기한다. 스코프를 필요 최소로 줄이는 것이 처리량에 직결된다.

```js
// 나쁨: 읽기만 하면서 전체 스토어를 readwrite 로 잠금
const tx = db.transaction(['orders', 'items', 'customers'], 'readwrite');

// 좋음: 실제로 쓰는 스토어만, 읽기는 별도 readonly 트랜잭션으로
const readTx = db.transaction(['customers'], 'readonly');
const writeTx = db.transaction(['orders'], 'readwrite');
```

`durability` 옵션도 성능에 영향을 준다. `'relaxed'` 는 OS 레벨 fsync 를 생략해 쓰기 지연을 크게 줄이는 대신 전원 차단 시 최근 커밋이 유실될 수 있다. 캐시·임시 상태 저장에는 `relaxed`, 사용자 작성 데이터에는 `'strict'` 를 쓴다.

```js
const tx = db.transaction('cache', 'readwrite', { durability: 'relaxed' });
```

## 3. 커서 순회와 인덱스 설계

대량 데이터 처리는 `getAll()` 로 전부 메모리에 올리는 대신 커서로 순회한다. `getAll()` 은 구현상 한 번에 직렬화하므로 수만 건에서 메인 스레드를 수백 ms 멈춘다.

```js
function forEachInRange(db, storeName, indexName, range, visit) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, 'readonly');
    const source = indexName
      ? tx.objectStore(storeName).index(indexName)
      : tx.objectStore(storeName);

    const req = source.openCursor(range);
    req.onsuccess = () => {
      const cursor = req.result;
      if (!cursor) {
        return;
      }
      visit(cursor.value, cursor.primaryKey);
      cursor.continue();
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
```

복합 인덱스는 배열 keyPath 로 만든다. 정렬 순서가 배열 요소 순서를 따르므로, 범위 조회의 선행 컬럼을 앞에 두어야 한다.

```js
function upgrade(db, oldVersion) {
  if (oldVersion < 1) {
    const orders = db.createObjectStore('orders', { keyPath: 'id' });
    orders.createIndex('byStatusCreated', ['status', 'createdAt']);
    orders.createIndex('byCustomer', 'customerId');
  }
}

// 'PAID' 상태의 최근 7일 주문 범위 조회
const since = Date.now() - 7 * 24 * 3600 * 1000;
const range = IDBKeyRange.bound(['PAID', since], ['PAID', Infinity]);
```

`IDBKeyRange` 의 상한에 `Infinity` 를 쓰는 것은 숫자 키에서만 유효하다. 문자열 키의 "이 접두사로 시작하는 전부" 는 유니코드 최대 코드포인트를 붙여 표현한다 — `IDBKeyRange.bound('ORD-', 'ORD-￿')` 형태가 관용적이다.

## 4. 다중 탭에서의 버전 업그레이드

같은 오리진의 여러 탭이 하나의 IndexedDB 를 공유한다. 한 탭이 새 버전으로 열려고 하면, 기존 버전을 열어둔 다른 탭이 닫힐 때까지 업그레이드가 차단된다. 처리하지 않으면 새 탭이 영원히 멈춘 것처럼 보인다.

```js
function openDatabase(name, version) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, version);

    req.onupgradeneeded = (event) => {
      upgrade(req.result, event.oldVersion, req.transaction);
    };

    req.onsuccess = () => {
      const db = req.result;
      // 다른 탭이 더 높은 버전으로 열려고 할 때 자발적으로 닫아준다
      db.onversionchange = () => {
        db.close();
        notifyUser('앱이 업데이트되었습니다. 새로고침해 주세요.');
      };
      resolve(db);
    };

    req.onblocked = () => {
      notifyUser('다른 탭에서 앱이 열려 있어 업데이트할 수 없습니다. 다른 탭을 닫아주세요.');
    };

    req.onerror = () => reject(req.error);
  });
}
```

`onversionchange` 에서 `db.close()` 를 호출하는 것이 핵심이다. 이 핸들러가 없으면 오래된 탭이 계속 연결을 붙들어 새 탭이 `blocked` 에 머문다. 닫은 뒤에는 해당 탭의 DB 접근이 실패하므로, 사용자에게 새로고침을 안내하거나 자동으로 `location.reload()` 를 거는 정책을 정해야 한다.

## 5. Web Locks API — 트랜잭션이 못 하는 조율

IndexedDB 트랜잭션은 단일 DB 안의 원자성만 보장한다. "토큰 갱신을 여러 탭 중 하나만 수행" 같은 조율은 범위 밖이다. Web Locks API 가 이 자리를 채운다. 잠금은 오리진 단위로 공유되며, 탭·Worker·Service Worker 가 모두 같은 잠금 공간을 본다.

```js
async function refreshTokenOnce() {
  return navigator.locks.request('auth-token-refresh', async (lock) => {
    // 잠금을 잡은 뒤 다시 확인 — 대기 중에 다른 탭이 이미 갱신했을 수 있다
    const cached = await readToken();
    if (cached && cached.expiresAt > Date.now() + 30_000) {
      return cached;
    }

    const fresh = await fetch('/api/auth/refresh', { method: 'POST' })
      .then(r => r.json());
    await writeToken(fresh);
    return fresh;
  });
}
```

콜백이 반환하는 Promise 가 settle 될 때까지 잠금이 유지된다. 즉 잠금 해제를 명시적으로 호출할 필요가 없고, 예외가 나도 자동 해제된다. 탭이 크래시하거나 닫혀도 브라우저가 잠금을 회수하므로, 좀비 잠금이 남지 않는다는 점이 애플리케이션 레벨 잠금 대비 결정적 장점이다.

주요 옵션들:

```js
// 1) 비차단 시도 — 이미 잡혀 있으면 즉시 null 로 콜백
await navigator.locks.request('sync-job', { ifAvailable: true }, async (lock) => {
  if (lock === null) {
    return; // 다른 탭이 동기화 중
  }
  await runSync();
});

// 2) 공유 잠금 — 읽기 다수 / 쓰기 단독
await navigator.locks.request('index-rebuild', { mode: 'shared' }, readWork);
await navigator.locks.request('index-rebuild', { mode: 'exclusive' }, rebuildWork);

// 3) 선점 — 대기자를 모두 취소시키고 즉시 획득
await navigator.locks.request('leader', { steal: true }, async () => {
  becomeLeader();
});

// 4) 취소 가능한 대기
const controller = new AbortController();
setTimeout(() => controller.abort(), 3000);
try {
  await navigator.locks.request('slow-job', { signal: controller.signal }, work);
} catch (e) {
  if (e.name === 'AbortError') {
    console.warn('잠금 대기 타임아웃');
  }
}
```

리더 선출 패턴은 `navigator.locks.request` 를 영원히 해제하지 않는 Promise 로 잡는 방식이 관용적이다. 잠금을 잡은 탭이 리더이고, 그 탭이 닫히면 잠금이 해제되어 대기 중이던 다음 탭이 자동으로 리더가 된다.

```js
let resolveRelease;

function becomeLeaderWhenPossible(onBecomeLeader) {
  navigator.locks.request('app-leader', () => {
    onBecomeLeader();
    return new Promise((resolve) => {
      resolveRelease = resolve; // 명시적으로 내려놓기 전까지 리더 유지
    });
  });
}

function resignLeadership() {
  resolveRelease?.();
  resolveRelease = undefined;
}
```

현재 잠금 상태는 `navigator.locks.query()` 로 점검한다. 디버깅과 헬스체크에 유용하다.

```js
const state = await navigator.locks.query();
console.table(state.held);    // [{ name, mode, clientId }]
console.table(state.pending); // 대기 중인 요청
```

## 6. Web Locks 와 IndexedDB 트랜잭션을 함께 쓸 때의 순서

두 메커니즘을 섞을 때 교착을 만들 수 있다. 원칙은 **항상 Web Lock 을 먼저 잡고, 그 안에서 IndexedDB 트랜잭션을 연다**. 반대로 하면 트랜잭션이 잠금 대기 동안 자동 커밋되거나(비활성화), 스코프를 붙든 채 다른 탭의 잠금 획득을 기다리는 형태가 된다.

```js
// 올바른 순서
async function compactStore(db) {
  await navigator.locks.request('orders-compaction', { ifAvailable: true }, async (lock) => {
    if (lock === null) {
      return;
    }
    const stale = await collectStaleKeys(db);          // readonly 트랜잭션, 짧게
    await deleteKeysInBatches(db, stale, 500);         // readwrite 를 배치로 분할
  });
}

function deleteKeysInBatches(db, keys, batchSize) {
  const batches = [];
  for (let i = 0; i < keys.length; i += batchSize) {
    batches.push(keys.slice(i, i + batchSize));
  }
  return batches.reduce(
    (chain, batch) => chain.then(() => deleteBatch(db, batch)),
    Promise.resolve()
  );
}

function deleteBatch(db, keys) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('orders', 'readwrite', { durability: 'relaxed' });
    const store = tx.objectStore('orders');
    keys.forEach((key) => store.delete(key));
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
```

배치로 쪼개는 이유는 단일 거대 트랜잭션이 다른 탭의 읽기를 오래 막기 때문이다. 배치 사이에 이벤트 루프가 돌면서 대기 중이던 `readonly` 트랜잭션들이 끼어들 수 있다. 500 건 단위는 경험적 출발점이고, 레코드 크기에 따라 조정한다.

## 7. 스토리지 할당량과 축출

IndexedDB 는 무제한이 아니다. 오리진별 할당량은 디스크 여유 공간의 비율로 산정되며, 전체 스토리지가 압박받으면 최근 사용하지 않은 오리진부터 통 축출된다.

```js
async function reportQuota() {
  if (!navigator.storage?.estimate) {
    return null;
  }
  const { usage, quota, usageDetails } = await navigator.storage.estimate();
  return {
    usedMB: Math.round(usage / 1024 / 1024),
    quotaMB: Math.round(quota / 1024 / 1024),
    ratio: (usage / quota).toFixed(3),
    detail: usageDetails
  };
}

// 축출 방지 요청 — 사용자 상호작용/참여도에 따라 승인 여부가 갈린다
async function requestDurableStorage() {
  if (!navigator.storage?.persist) {
    return false;
  }
  if (await navigator.storage.persisted()) {
    return true;
  }
  return navigator.storage.persist();
}
```

`persist()` 는 요청이지 보장이 아니다. 브라우저는 북마크 여부, 방문 빈도, 알림 권한 등으로 자동 판단하거나 사용자에게 묻는다. 승인되면 사용자가 명시적으로 삭제하기 전까지 자동 축출 대상에서 제외된다. 오프라인 우선 앱이라면 첫 데이터 저장 전에 호출하고, 실패 시 "데이터가 삭제될 수 있음" 을 전제로 서버 동기화 전략을 설계해야 한다.

`QuotaExceededError` 는 반드시 처리한다. 발생 시 트랜잭션 전체가 abort 되므로, 부분 저장 상태가 남지 않는 것은 다행이지만 사용자 작업은 통 실패한다.

```js
tx.onabort = () => {
  if (tx.error?.name === 'QuotaExceededError') {
    void evictOldestEntries(db, 0.2); // 오래된 20% 정리 후 재시도
  }
};
```

## 8. 실무 래퍼의 최소 요건

라이브러리 없이 직접 감쌀 때 반드시 들어가야 하는 요소는 다음과 같다. 요청 단위 Promise 화, 트랜잭션 완료 대기, 에러 전파, 그리고 연결 재생성이다.

```js
function promisifyRequest(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function txDone(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new DOMException('aborted', 'AbortError'));
  });
}

async function withStore(db, storeName, mode, work) {
  const tx = db.transaction(storeName, mode);
  const result = work(tx.objectStore(storeName));
  await txDone(tx);
  return result;
}
```

`work` 안에서 여러 요청을 발행하고 각각의 Promise 를 따로 await 하는 대신, 요청을 모두 발행한 뒤 `txDone(tx)` 하나만 기다리는 형태가 정석이다. 요청별로 await 하면 각 await 마다 마이크로태스크 경계를 거치며, 구현에 따라 트랜잭션이 조기 종료될 위험이 생긴다.

브라우저 종료·탭 백그라운드 전환으로 연결이 끊기는 경우(`db.onclose`)도 처리한다. 이 이벤트는 강제 종료 시에만 발생하며, 발생하면 해당 `IDBDatabase` 객체는 재사용 불가이므로 재연결이 필요하다.

```js
db.onclose = () => {
  cachedConnection = null;
  void openDatabase('app', CURRENT_VERSION).then((fresh) => {
    cachedConnection = fresh;
  });
};
```

## 참고

- W3C, *Indexed Database API 3.0* (트랜잭션 수명주기 및 스케줄링 규정)
- W3C, *Web Locks API* 명세
- MDN Web Docs, *Using IndexedDB* / *Web Locks API*
- WHATWG Storage Standard, *Storage quota and eviction*
- Chrome for Developers, *Persistent storage and storage eviction*
