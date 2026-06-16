Notion 원본: https://www.notion.so/3815a06fd6d381b199ace9368fd50a90

# Java Virtual Threads (Project Loom)과 Carrier Thread Pinning 및 Structured Concurrency

> 2026-06-16 신규 주제 · 확장 대상: JAVA

## 학습 목표

- Virtual Thread 의 마운트/언마운트(continuation) 동작과 Carrier Thread 풀의 관계를 설명한다
- Pinning 이 발생하는 조건(synchronized, native frame)과 JDK 24 이후의 변화를 구분한다
- Structured Concurrency(StructuredTaskScope)로 동시 작업의 수명을 트리로 묶는 방법을 구현한다
- 플랫폼 스레드 풀 기반 코드와 비교해 처리량/메모리 trade-off 를 수치로 판단한다

## 1. Virtual Thread 의 실행 모델 — Continuation 위에 올라간 스레드

Virtual Thread 는 OS 스레드가 아니라 JVM 이 관리하는 사용자 모드 스레드다. 실행에 필요한 것은 `jdk.internal.vm.Continuation` 과 그것을 실제 OS 스레드(= **carrier thread**)에 얹어 돌리는 스케줄러뿐이다. 기본 스케줄러는 `ForkJoinPool` 을 work-stealing 모드로 쓰며, 병렬도는 `jdk.virtualThreadScheduler.parallelism`(기본값 `Runtime.availableProcessors()`)으로 정해진다.

핵심은 **블로킹 호출에서 carrier 를 점유하지 않는다**는 점이다. Virtual Thread 가 `Socket.read()` 같은 JDK 내부의 블로킹 지점에 도달하면, 런타임은 continuation 을 **언마운트(unmount)** 해 힙에 스택을 저장하고 carrier 를 풀어준다. I/O 가 준비되면 다시 임의의 carrier 에 **마운트(mount)** 되어 멈춘 지점부터 재개한다. 따라서 수만 개의 Virtual Thread 가 동시에 블로킹 상태여도 실제 OS 스레드는 CPU 코어 수 정도만 쓴다.

```java
// 1만 개의 동시 블로킹 작업 — 플랫폼 스레드라면 1만 OS 스레드가 필요
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000).forEach(i -> executor.submit(() -> {
        Thread.sleep(Duration.ofSeconds(1)); // 언마운트 발생 — carrier 반환
        return i;
    }));
} // close()가 모든 작업 완료를 기다림 (AutoCloseable)
```

`Thread.currentThread()` 가 Virtual Thread 면 `isVirtual()` 이 `true` 이고, toString 은 `VirtualThread[#34]/runnable@ForkJoinPool-1-worker-3` 처럼 carrier 정보를 함께 보여준다.

## 2. Carrier Thread 풀과 마운트/언마운트의 비용

마운트/언마운트는 continuation 의 스택 프레임을 힙↔carrier 스택 간에 복사하는 작업이다. 스택이 얕으면 수십 ns 수준이고, 깊은 호출 체인일수록 복사량이 늘어난다. 그래서 Virtual Thread 는 "얕은 스택 + 잦은 블로킹" 워크로드(전형적인 요청-응답 서버)에 최적이고, CPU 바운드 연산에는 이점이 없다 — 오히려 컨텍스트 스위치 없이 도는 플랫폼 스레드가 낫다.

생성 비용도 다르다. 플랫폼 스레드는 기본 1MB 스택을 OS 에서 예약하지만, Virtual Thread 의 초기 스택은 힙에 수백 바이트~수 KB 수준으로 잡히고 필요 시 자란다. 그래서 "스레드 풀로 재사용" 한다는 통념이 Virtual Thread 에는 적용되지 않는다 — **풀링하지 말고 작업마다 새로 만든다**. 실제로 `newVirtualThreadPerTaskExecutor()` 가 권장되는 이유다.

```java
// 안티패턴: Virtual Thread 를 고정 풀로 제한하면 동시성 이점이 사라진다
var bad = Executors.newFixedThreadPool(200); // 절대 VT 와 섞지 말 것

// 권장: 작업당 1 VT. 동시성 제어가 필요하면 Semaphore 로 "자원" 을 제한
var limiter = new Semaphore(50); // DB 커넥션 풀 크기에 맞춤
Runnable guarded = () -> {
    limiter.acquire();
    try { callDatabase(); } finally { limiter.release(); }
};
```

## 3. Pinning — Virtual Thread 의 가장 큰 함정

언마운트가 **불가능한** 상황에서 Virtual Thread 가 블로킹되면, carrier 가 그대로 묶여(pinned) 다른 Virtual Thread 를 못 돌린다. JDK 21~23 기준 pinning 의 두 가지 주요 원인은 다음과 같다.

첫째, `synchronized` 블록/메서드 안에서 블로킹하는 경우. 모니터 락이 carrier 스택에 묶여 있어 언마운트가 안 된다. 둘째, native 메서드(JNI) 프레임이 스택에 있을 때.

```java
// JDK 21~23: 이 코드는 carrier 를 pin 한다
private final Object lock = new Object();
void handle() {
    synchronized (lock) {
        jdbcCall(); // 블로킹 — carrier pinned, 동시성 붕괴
    }
}

// 회피: ReentrantLock 으로 교체하면 언마운트 가능
private final ReentrantLock lock = new ReentrantLock();
void handle() {
    lock.lock();
    try { jdbcCall(); } finally { lock.unlock(); }
}
```

**중요한 변화**: JDK 24(JEP 491, 2025년 정식)부터 `synchronized` 로 인한 pinning 이 대부분 제거되었다. 모니터를 객체에 매달아 언마운트가 가능해졌기 때문에, JDK 24+ 환경이라면 `synchronized` → `ReentrantLock` 리팩터링의 긴급성이 크게 줄었다. 다만 native frame 으로 인한 pinning 은 여전히 남는다. 운영 환경 JDK 버전에 따라 대응이 달라지므로 버전 확인이 선행되어야 한다.

pinning 추적은 시스템 프로퍼티로 켠다.

```bash
# JDK 21~23: pinning 발생 시 스택 출력
java -Djdk.tracePinnedThreads=full -jar app.jar
# JDK 24+: JFR 이벤트 jdk.VirtualThreadPinned 로 관측 (위 프로퍼티는 no-op)
```

## 4. Structured Concurrency — 동시 작업을 트리로 묶기

`StructuredTaskScope`(JDK 21~24 preview, JEP 패키지 `java.util.concurrent`)는 "여러 하위 작업을 열었으면 같은 스코프에서 모두 끝내고 닫는다"는 규칙을 강제한다. try-with-resources 블록을 벗어나기 전에 모든 fork 가 완료되거나 취소된다 — 누수된 스레드가 원천적으로 생기지 않는다.

```java
// 두 외부 호출을 병렬로 — 하나라도 실패하면 나머지 자동 취소
Response handle() throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Supplier<User>  user  = scope.fork(() -> findUser(id));
        Supplier<Order> order = scope.fork(() -> fetchOrder(id));

        scope.join();           // 두 작업 완료 대기
        scope.throwIfFailed();  // 하나라도 예외면 전파 (나머지는 interrupt 됨)

        return new Response(user.get(), order.get());
    }
}
```

`ShutdownOnSuccess` 는 반대로 가장 먼저 성공한 결과를 채택하고 나머지를 취소한다(헷지 요청, fastest-wins 패턴). 이 모델의 가치는 **취소 전파와 예외 전파가 호출 트리를 따라 자동**이라는 데 있다. 기존 `ExecutorService` + `Future` 조합은 부모가 죽어도 자식 Future 가 좀비로 남을 수 있었지만, 구조적 동시성에서는 스코프가 닫히는 순간 자식이 정리된다.

## 5. 성능과 메모리 — 언제 이득인가

아래는 단순 I/O 바운드 서버(요청당 100ms 다운스트림 호출)에서의 일반적 경향이다. 절대 수치는 환경마다 다르지만 방향성은 일관된다.

| 구성 | 동시 처리 가능 요청 | 요청당 메모리(개략) | 적합 워크로드 |
|---|---|---|---|
| 플랫폼 스레드 풀(200) | ~200 | ~1MB/스레드 | CPU 바운드, 적은 동시성 |
| Virtual Thread per task | 수만~수십만 | 수 KB/스레드(가변) | I/O 바운드, 높은 동시성 |
| Reactor/WebFlux | 수만 | 낮음 | 백프레셔 필요한 스트리밍 |

Virtual Thread 의 가장 큰 실무적 이점은 **명령형 블로킹 코드를 그대로 쓰면서** Reactive 수준의 동시성을 얻는다는 점이다. WebFlux 의 학습 비용(연산자, 백프레셔, 디버깅 난이도)을 치르지 않고 처리량을 끌어올릴 수 있다. 반대로 스트리밍/백프레셔가 본질인 워크로드(서버-센트-이벤트, 무한 스트림)는 여전히 Reactive 가 적합하다 — Virtual Thread 는 백프레셔 개념이 없기 때문이다.

## 6. Spring Boot 에서의 활성화와 주의점

```properties
# Spring Boot 3.2+ : Tomcat/Jetty 요청 처리를 Virtual Thread 로
spring.threads.virtual.enabled=true
```

이 플래그는 서블릿 요청 처리 스레드, `@Async`, 그리고 일부 스케줄링을 Virtual Thread 로 돌린다. 단, 다음을 점검해야 한다. 첫째, `ThreadLocal` 을 캐시처럼 쓰는 라이브러리는 Virtual Thread 가 매우 많아지면 메모리를 낭비한다(작업마다 새 스레드 = 새 ThreadLocal). `ScopedValue`(JDK 21+ preview)로 옮기는 것이 정석이다. 둘째, DB 커넥션 풀은 여전히 유한하다 — Virtual Thread 가 무한정 늘어도 HikariCP 커넥션은 한정되므로, 풀 크기가 사실상의 동시성 상한이 된다. 셋째, JDK 버전에 따라 §3 의 pinning 위험을 반드시 확인한다.

```java
// ThreadLocal → ScopedValue 전환 예시
private static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

ScopedValue.where(TRACE_ID, requestId).run(() -> {
    // 이 블록과 하위 fork 에서만 TRACE_ID 가 보임 — 불변, 자동 정리
    process();
});
```

## 7. 디버깅과 관측

Virtual Thread 는 스레드 덤프에서 별도 섹션으로 나온다. `jcmd <pid> Thread.dump_to_file -format=json dump.json` 으로 수만 개의 Virtual Thread 스택을 구조화해 받을 수 있다(기존 `jstack` 은 carrier 만 보여줘 부족하다). JFR 에는 `jdk.VirtualThreadStart`, `jdk.VirtualThreadEnd`, `jdk.VirtualThreadPinned`, `jdk.VirtualThreadSubmitFailed` 이벤트가 있어 pinning 빈도와 스케줄러 포화를 모니터링할 수 있다. 운영에서는 pinning 이벤트 카운트가 0 에 수렴하는지, carrier 풀의 큐 길이가 늘지 않는지를 본다.

## 8. 마이그레이션 체크리스트와 흔한 함정

기존 플랫폼 스레드 기반 서비스를 Virtual Thread 로 옮길 때의 점검 순서를 정리한다. 첫째, **운영 JDK 버전 확인** — 21~23 이면 §3 의 `synchronized` pinning 위험이 살아 있으므로 핫패스의 `synchronized` + 블로킹 조합을 `ReentrantLock` 으로 바꾸고, 24+ 면 이 작업은 선택사항이다. 둘째, **스레드 풀 제거** — `Executors.newFixedThreadPool` / `newCachedThreadPool` 같은 풀을 `newVirtualThreadPerTaskExecutor` 로 바꾸되, 동시성 상한이 필요한 자리는 풀이 아니라 `Semaphore` 로 자원을 제한한다(§2). 셋째, **유한 자원의 상한 재확인** — DB 커넥션 풀, 외부 API rate limit 처럼 진짜 유한한 자원은 Virtual Thread 가 늘어도 그대로 유한하므로, 이들이 새로운 병목 지점이 된다.

```java
// 외부 API 가 초당 100 요청만 허용할 때 — VT 가 많아도 호출은 제한
private final Semaphore apiLimiter = new Semaphore(100);

<T> T callExternal(Callable<T> op) throws Exception {
    apiLimiter.acquire();
    try { return op.call(); }   // 블로킹 시 carrier 는 반환됨(pinning 없으면)
    finally { apiLimiter.release(); }
}
```

넷째, **ThreadLocal 누수 점검** — 작업마다 새 Virtual Thread 가 생기므로 ThreadLocal 을 풀 재사용 전제로 캐싱하던 라이브러리는 메모리 사용이 급증할 수 있다. MDC(로깅 컨텍스트) 같은 것은 `ScopedValue`(§6)로 옮기는 것이 정석이다. 다섯째, **CPU 바운드 작업은 그대로 둔다** — 암호화, 압축, 대규모 직렬화 같은 연산은 Virtual Thread 로 옮겨도 이득이 없고, 오히려 마운트/언마운트 오버헤드만 추가된다. 이런 작업은 별도의 고정 플랫폼 스레드 풀(코어 수만큼)에 격리하는 것이 낫다.

가장 흔한 운영 사고는 "Virtual Thread 로 바꿨더니 동시성이 오히려 떨어졌다" 인데, 거의 항상 (1) pinning 으로 carrier 가 묶였거나 (2) 무심코 Virtual Thread 를 고정 풀에 가뒀거나 (3) DB 커넥션 풀이 실제 상한이었던 경우다. 도입 직후에는 JFR 의 `jdk.VirtualThreadPinned` 이벤트와 carrier 풀 큐 길이를 반드시 모니터링해, 이 세 가지를 데이터로 배제한 뒤 효과를 판단한다.

## 참고

- JEP 444: Virtual Threads (Final, JDK 21)
- JEP 491: Synchronize Virtual Threads without Pinning (JDK 24)
- JEP 453/505: Structured Concurrency (Preview)
- JEP 506: Scoped Values
- "Java Concurrency in Practice" 의 스레드 수명/취소 모델과의 대비
