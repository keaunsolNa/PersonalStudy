Notion 원본: https://www.notion.so/3765a06fd6d3817e92c4ea8b44271ef4

# Java Virtual Threads 내부구조 — Continuation, Carrier Thread, Pinning

> 2026-06-05 신규 주제 · 확장 대상: JAVA (동시성/스레드 기초 학습됨)

## 학습 목표

- Virtual Thread가 Continuation 위에서 mount/unmount 되는 내부 메커니즘을 추적한다
- Carrier Thread(ForkJoinPool) 스케줄링 구조와 platform thread 와의 차이를 코드로 검증한다
- Pinning이 발생하는 조건(synchronized, native call)을 진단하고 JDK 24의 개선을 비교한다
- ThreadLocal/ScopedValue 선택 기준과 대량 VT 환경의 메모리 특성을 측정한다

## 1. Virtual Thread의 본질 — 스택을 힙으로 옮긴 Continuation

Platform thread는 OS 커널 스레드와 1:1 매핑되고, 생성 시 기본 1MB(리눅스 x86-64, `-Xss` 기본값) 스택을 가상 메모리에 예약한다. 스레드 10만 개를 만들면 스택 예약만 100GB에 달하고, 컨텍스트 스위치마다 커널 모드 전환 비용(~1–2µs)이 든다. Virtual Thread(JEP 444, JDK 21 정식화)는 이 전제를 뒤집는다. VT의 스택은 OS 스택이 아니라 **힙에 저장되는 `StackChunk` 객체**이며, 블로킹 지점에서 스택 프레임을 힙으로 복사해 두고(unmount) 커널 스레드를 반납한다.

핵심 클래스는 `jdk.internal.vm.Continuation`이다. VT는 사실상 다음 구조의 래퍼다.

```java
// JDK 내부 단순화 모델
class VirtualThread extends BaseVirtualThread {
	private final Continuation cont;     // 실행 본체
	private volatile Thread carrierThread; // 현재 mount 된 캐리어
	private volatile int state;          // NEW, STARTED, RUNNING, PARKING, PARKED, PINNED ...

	void run() {
		cont.run();   // 캐리어 스레드 위에서 continuation 재개
	}

	void park() {
		// LockSupport.park() 호출 시 도달
		setState(PARKING);
		Continuation.yield(VTHREAD_SCOPE); // 스택을 힙 StackChunk 로 freeze
	}
}
```

`Continuation.yield()`가 호출되면 JVM은 현재 스택 프레임들을 **freeze** 과정으로 힙의 `StackChunk`에 복사하고, 캐리어 스레드는 스케줄러 큐로 돌아가 다른 VT를 집는다. 재개 시(**thaw**)에는 전체 스택을 복원하지 않고 최상위 몇 프레임만 lazy하게 복원한다(lazy copy). 깊은 콜 스택에서 park/unpark가 반복될 때 복사 비용을 줄이는 최적화로, JDK 21 기준 freeze/thaw는 보통 수백 ns ~ 1µs 수준이다.

## 2. Carrier Thread 스케줄러 — 전용 ForkJoinPool

VT를 실행하는 캐리어는 공용 `ForkJoinPool.commonPool()`이 아니라 **VT 전용 ForkJoinPool**이다. 기본 parallelism은 `Runtime.availableProcessors()`이고 시스템 프로퍼티로 조정한다.

```bash
-Djdk.virtualThreadScheduler.parallelism=16   # 캐리어 수
-Djdk.virtualThreadScheduler.maxPoolSize=256  # pinning 보상용 상한
```

`maxPoolSize`가 parallelism보다 큰 이유가 중요하다. VT가 pinning 상태로 캐리어를 점유하면 스케줄러는 **일시적으로 캐리어를 추가 생성**해 starvation을 완화한다(`ForkJoinPool`의 compensation 메커니즘). 즉 pinning이 빈발하면 커널 스레드 수가 256까지 늘어날 수 있고, 이는 "VT = 가벼움"이라는 가정을 깨는 운영상 함정이다.

스케줄링은 work-stealing FIFO다. 주의할 점은 **time-sharing 선점이 없다**는 것이다. VT가 블로킹 API를 한 번도 호출하지 않고 CPU 루프를 돌면 캐리어를 무한 점유한다. JDK 21~23에는 안전장치가 없으며, CPU-bound 작업은 여전히 platform thread pool에 맡겨야 한다.

```java
// CPU-bound 작업이 VT 스케줄러를 굶기는 안티패턴
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
	for (int i = 0; i < 8; i++) {
		executor.submit(() -> {
			while (true) { /* busy loop — 캐리어 8개 전부 점유 */ }
		});
	}
	executor.submit(() -> System.out.println("나는 영원히 실행되지 못한다"));
}
```

## 3. Mount / Unmount 라이프사이클 추적

블로킹 지점에서 실제로 일어나는 일을 JDK 소스 기준으로 따라가면 다음 순서다.

1. VT 안에서 `Socket.read()` 호출 → NIO 기반으로 재작성된 `sun.nio.ch.NioSocketImpl`이 데이터 없음을 확인
2. `Poller`에 파일 디스크립터 등록 후 `LockSupport.park()` 호출
3. `VirtualThread.park()` → `Continuation.yield()` → 스택 freeze, 상태 `PARKED`
4. 캐리어 스레드는 스케줄러로 복귀, 다른 VT thaw
5. epoll 이벤트 발생 → `Poller` 스레드가 `unpark()` → VT가 스케줄러 큐에 재등록
6. 임의의 캐리어(이전과 다를 수 있음)가 VT를 mount하고 thaw

JDK 21에서 java.base의 거의 모든 블로킹 API(`Socket`, `InputStream`, `Thread.sleep`, `BlockingQueue`, JDBC 드라이버가 쓰는 `socketRead`까지)가 이 경로를 타도록 재작성됐다. 검증은 JFR 이벤트로 한다.

```bash
java -XX:StartFlightRecording=filename=vt.jfr ...
# 관심 이벤트
# jdk.VirtualThreadStart / End
# jdk.VirtualThreadPinned       — pinning 발생 (기본 threshold 20ms)
# jdk.VirtualThreadSubmitFailed — 스케줄러 포화
jfr print --events jdk.VirtualThreadPinned vt.jfr
```

## 4. Pinning — 무엇이 VT를 캐리어에 못 박는가

Pinning은 VT가 unmount 불가능한 상태로 캐리어를 점유하는 현상이다. JDK 21 기준 발생 조건은 두 가지다.

| 조건 | 이유 | 해소 |
|---|---|---|
| `synchronized` 블록/메서드 안에서 블로킹 | monitor가 캐리어 스레드 주소 기준으로 소유 기록됨 | JDK 24(JEP 491)에서 해소 |
| JNI/native 프레임이 스택에 존재 | native 스택은 힙으로 freeze 불가 | 구조적 한계, 우회 필요 |

`synchronized` pinning의 원인은 HotSpot의 monitor 구현이 소유자를 "커널 스레드"로 기록하기 때문이다. VT가 unmount되면 monitor 소유자 식별이 깨지므로 JVM이 yield 자체를 거부한다. 진단 플래그:

```bash
-Djdk.tracePinnedThreads=full   # pinning 시 전체 스택 출력 (JDK 21~23)
```

JDK 24의 JEP 491(Synchronize Virtual Threads without Pinning)은 monitor 소유자를 VT 식별자 기준으로 재구현해 `synchronized` 내부 블로킹에서도 unmount를 허용한다. 이로써 "synchronized를 전부 ReentrantLock으로 바꿔라"라는 마이그레이션 가이드는 JDK 24+에서는 대부분 불필요해졌다. 다만 `Object.wait()`와 native 프레임 pinning은 여전히 남는다.

JDK 21~23 운영 환경이라면 핫패스의 `synchronized`만 선별 교체한다.

```java
// before — 커넥션 풀 내부 등 블로킹 가능 구간
synchronized (lock) {
	conn = waitForConnection(); // pinning + 캐리어 고갈 위험
}

// after
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
	conn = waitForConnection(); // park 시 정상 unmount
} finally {
	lock.unlock();
}
```

실측 사례로, 오래된 MySQL Connector/J(8.0.32 이전)는 내부 `synchronized` 블록에서 socket read를 수행해 VT 환경에서 캐리어 고갈을 일으켰다. 8.0.33+ 또는 r2dbc가 아닌 JDBC를 쓴다면 드라이버 버전 확인이 필수다.

## 5. ThreadLocal의 비용과 ScopedValue

VT는 ThreadLocal을 지원하지만, VT가 수백만 개 생성되는 환경에서는 두 가지 문제가 생긴다. 첫째, ThreadLocal 값이 VT 수에 비례해 힙을 점유한다(스레드 풀 재사용 시엔 수백 개로 캡핑되던 것). 둘째, `inheritableThreadLocal`은 VT 생성 시마다 맵 복사를 유발한다. JEP 487의 `ScopedValue`(JDK 25 정식화 목표)는 불변 바인딩 + 구조적 스코프로 이를 대체한다.

```java
private static final ScopedValue<RequestContext> CTX = ScopedValue.newInstance();

void handle(Request req) {
	ScopedValue.where(CTX, new RequestContext(req.traceId()))
		.run(() -> service.process()); // 하위 콜 스택 + StructuredTaskScope 포크에 전파
}

// 깊은 곳 어디서든
RequestContext ctx = CTX.get(); // O(1), 복사 없음, rebinding 불가
```

마이그레이션 기준: 값이 요청 스코프 동안 불변이면 ScopedValue, mutable 누적(예: 트랜잭션 동기화 자원)이 필요하면 ThreadLocal 유지. Spring 6.1의 `ThreadLocalAccessor`/Micrometer context-propagation은 두 모델을 모두 지원한다.

## 6. 메모리 특성 실측

VT 1개의 고정 비용은 대략 다음과 같다 (JDK 21, G1, 측정 조건: 빈 람다 100만 개 park 상태 유지).

| 항목 | Platform Thread | Virtual Thread |
|---|---|---|
| 스택 | 1MB 가상 예약, 수십 KB 실사용 | StackChunk 힙 객체, 초기 ~수백 B |
| 커널 객체 | task_struct 등 ~16KB | 없음 |
| 100만 개 생성 | OOM/커널 한계로 불가 | 힙 ~1.5–2GB 수준으로 가능 |
| 생성 시간 | ~1ms | ~1µs 미만 |

주의: 깊은 콜 스택(예: Spring MVC + Hibernate 스택 40~60 프레임)을 가진 VT가 park되면 그 스택 전체가 힙에 산다. VT 10만 개 × 프레임당 평균 100B × 50프레임 ≈ 500MB가 순수 StackChunk로 잡힐 수 있다. heap dump에서 `jdk.internal.vm.StackChunk`가 상위에 보이면 이것이다. G1 기준 StackChunk는 일반 객체처럼 young→old 승격되므로 장수 VT가 많으면 old gen 압박과 mixed GC 빈도 증가로 나타난다.

## 7. Spring Boot 3.2+ 적용과 운영 체크리스트

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat 요청 처리 + @Async 등 TaskExecutor 가 VT 로 전환
```

활성화 시 바뀌는 것: Tomcat의 워커가 `VirtualThreadPerTaskExecutor`로 대체되어 `server.tomcat.threads.max`가 무의미해지고, 동시 처리 한계가 "스레드 수"에서 "다운스트림 자원(DB 커넥션, 외부 API)"으로 이동한다. 그래서 반드시 함께 점검해야 하는 항목:

1. **HikariCP `maximumPoolSize`** — 스레드 200개 제한이 사라지므로 동시 요청 5천 개가 커넥션 풀 20개를 두고 경쟁한다. `connectionTimeout` 초과 에러가 VT 도입 직후 급증하는 전형적 패턴. 풀 크기는 여전히 `(core_count * 2) + effective_spindle` 류의 DB 측 공식으로 결정하고, 초과 수요는 세마포어로 흡수한다.

```java
private final Semaphore dbPermit = new Semaphore(50); // 풀보다 약간 크게

Order find(long id) throws InterruptedException {
	dbPermit.acquire();        // VT 는 park — 캐리어 반납하므로 저비용
	try {
		return repository.findById(id);
	} finally {
		dbPermit.release();
	}
}
```

2. **rate-limit이 없는 외부 호출** — 스레드 풀이 곧 백프레셔였던 구조가 사라진다. Resilience4j `Bulkhead`(semaphore 모드)로 명시적 제한을 복원한다.
3. **JFR `jdk.VirtualThreadPinned` 모니터링** — 20ms 이상 pinning을 알림으로 연결.
4. CPU-bound 구간(이미지 인코딩, 압축)은 별도 platform thread pool로 분리 유지.

## 8. Structured Concurrency와의 결합

VT의 진짜 수확은 "스레드가 싸졌으니 fork 자체를 구조화할 수 있다"는 점이다. `StructuredTaskScope`(JEP 480, JDK 23 preview)는 fork된 자식의 생명주기를 부모 스코프에 묶는다.

```java
Response handle() throws Exception {
	try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
		Subtask<User> user = scope.fork(() -> userClient.fetch(id));     // VT 1
		Subtask<List<Order>> orders = scope.fork(() -> orderClient.fetch(id)); // VT 2

		scope.join().throwIfFailed();   // 하나라도 실패하면 나머지 자동 인터럽트

		return new Response(user.get(), orders.get());
	} // 스코프 이탈 = 자식 전원 종료 보장 (스레드 누수 구조적 차단)
}
```

`ShutdownOnFailure`는 첫 실패 시 형제 작업을 취소하고, `ShutdownOnSuccess`는 첫 성공(헤지 요청 패턴)에서 나머지를 취소한다. CompletableFuture 조합 대비 장점은 예외 전파·취소·관측(스레드 덤프에 부모-자식 트리가 보임)이 언어 구조와 일치한다는 것이다. trade-off로는 아직 preview라는 점, 그리고 스코프 경계를 넘는 비동기 핸드오프(콜백 큐 등)에는 부적합하다는 점이 있다.

## 9. Reactive(WebFlux)와의 선택 기준

| 기준 | Virtual Threads | WebFlux/Reactor |
|---|---|---|
| 프로그래밍 모델 | 동기 블로킹 그대로 | 선언형 파이프라인 |
| 디버깅/스택트레이스 | 자연스러움 | assembly trace 필요 |
| 백프레셔 | 세마포어 등 수동 | request(n) 내장 |
| 스트리밍(무한 시퀀스) | 부적합 | 적합 |
| 레거시 JDBC/블로킹 라이브러리 | 즉시 호환 | 별도 스케줄러 격리 필요 |
| 처리량(I/O-bound 요청-응답) | 동급 | 동급 |

요청-응답형 I/O-bound 서비스는 VT가 운영 복잡도 대비 우위이고, 스트리밍·백프레셔가 1급 요구사항인 파이프라인(SSE 팬아웃, Kafka 소비 흐름 제어)은 Reactor가 여전히 적합하다. 이미 WebFlux로 안정 운영 중인 시스템을 VT로 되돌릴 근거는 약하다 — 처리량 이득이 아니라 코드 단순성이 VT의 가치이기 때문이다.

## 참고

- JEP 444: Virtual Threads — https://openjdk.org/jeps/444
- JEP 491: Synchronize Virtual Threads without Pinning — https://openjdk.org/jeps/491
- JEP 480: Structured Concurrency — https://openjdk.org/jeps/480
- JEP 487: Scoped Values — https://openjdk.org/jeps/487
- Ron Pressler, "State of Loom" — https://cr.openjdk.org/~rpressler/loom/loom/sol1_part1.html
- Spring Blog, "Embracing Virtual Threads" — https://spring.io/blog/2022/10/11/embracing-virtual-threads
