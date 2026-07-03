Notion 원본: https://www.notion.so/3925a06fd6d381549bf3c9f484d035ba

# Java Virtual Threads(Loom)와 Carrier Thread 및 Continuation 스택 관리

> 2026-07-03 신규 주제 · 확장 대상: JAVA(NIO Selector·JIT 학습됨)

## 학습 목표

- 플랫폼 스레드와 가상 스레드의 매핑 모델(M:N)과 캐리어 스레드 개념을 구분한다
- 가상 스레드가 블로킹 지점에서 언마운트/마운트되는 continuation 메커니즘을 설명한다
- pinning(고정)이 발생하는 조건과 회피 방법을 코드로 재현한다
- 구조적 동시성·스레드풀 대체 관점에서 적용 경계를 판단한다

## 1. 왜 가상 스레드인가

전통적 자바 스레드(플랫폼 스레드)는 OS 커널 스레드 1:1 매핑이다. 각 스레드는 기본 1MB 안팛의 스택을 예약하고, 컨텍스트 스위치는 커널을 거친다. 그래서 동시 접속 수만 개를 "요청당 스레드 하나" 모델로 처리하려 하면 수천 지점에서 메모리와 스케줄링 비용이 폭발한다. 이 한계 때문에 그동안 리액티브(WebFlux)나 콜백 기반 비동기로 우회했지만, 그 대가로 코드 가독성과 디버깅·스택트레이스가 나빠졌다.

가상 스레드(Project Loom, JDK 21 정식)는 "블로킹 스타일의 단순한 코드를 유지하면서" 수십만 개를 값싸게 돌리는 것을 목표로 한다. 핵심은 가상 스레드를 OS 스레드에 1:1로 묶지 않고, 소수의 플랫폼 스레드(캐리어) 위에 M:N으로 다중화하는 것이다.

## 2. 캐리어 스레드와 마운트/언마운트

가상 스레드는 실행될 때 캐리어 스레드(carrier thread)에 "마운트"된다. 캐리어는 기본적으로 `ForkJoinPool` 기반의 플랫폼 스레드 풀이고, 크기는 기본으로 가용 CPU 코어 수다. 가상 스레드가 블로킹 연산(예: 소켓 read, `Thread.sleep`, 락 대기)에 진입하면, JVM은 그 가상 스레드를 캐리어에서 **언마운트**하고 스택을 힙에 저장한다. 캐리어는 즉시 다른 대기 가상 스레드를 마운트해 실행한다. 블로킹이 끝나면 스케줄러가 가상 스레드를 아무 캐리어에나 다시 마운트해 이어서 실행한다.

```java
// 가상 스레드는 요청당 하나를 만들어도 부담이 없다
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 100_000).forEach(i ->
        executor.submit(() -> {
            // 이 블로킹 호출 지점에서 가상 스레드가 언마운트된다
            Thread.sleep(Duration.ofSeconds(1));
            return i;
        })
    );
} // try-with-resources 가 모든 작업 완료를 기다린다
```

여기서 결정적 사실은 "블로킹처럼 보이는 코드가 실제로 캐리어를 블로킹하지 않는다"는 점이다. 10만 개 작업이 각자 1초를 자도 캐리어는 CPU 코어 수만큼만 있으면 되고, 언마운트 덕에 캐리어는 놀지 않는다.

## 3. Continuation: 언마운트의 실체

언마운트/마운트를 가능하게 하는 하부 구조가 continuation이다. Continuation은 "일시 중단 가능한 실행 단위"로, 실행 스택 전체를 캐프처했다가 나중에 그 지점부터 재개할 수 있다. 가상 스레드는 내부적으로 `jdk.internal.vm.Continuation` 위에 구현된다.

블로킹 지점에서 JVM은 `Continuation.yield`를 호출해 현재 가상 스레드의 스택 프레임들을 힙 객체(스택 청크)로 복사해 두고 캐리어의 네이티브 스택은 비운다. 재개 시 `Continuation.run`이 그 힙 스택을 다시 캐리어의 네이티브 스택으로 복원한다. 즉 가상 스레드의 스택은 "필요할 때만" 캐리어 스택을 점유하고, 대기 중에는 힙에 압축 저장된다. 이 덕에 스택 메모리가 요청 수에 비례해 미리 예약되지 않는다.

이 모델의 부수 효과로 가상 스레드는 스택트레이스가 온전히 보존된다. 리액티브의 조각난 스택트레이스와 달리, 가상 스레드는 평범한 동기 호출 스택이라 예외 추적과 디버거 스텝이 자연스럽다.

## 4. Pinning: 언마운트가 막히는 경우

언마운트가 항상 되는 것은 아니다. 두 가지 경우 가상 스레드가 캐리어에 **고정(pinned)**되어 언마운트하지 못하고 캐리어를 통째로 블로킹한다.

첫째, `synchronized` 블록/메서드 안에서 블로킹하는 경우(초기 JDK 21~23). 모니터 락이 네이티브 프레임과 얽혀 스택을 힙으로 옷길 수 없기 때문이다. 둘째, 네이티브 메서드(JNI) 호출 스택 안에서 블로킹하는 경우.

```java
private final Object lock = new Object();

void badPinning() {
    synchronized (lock) {           // 이 블록 안에서
        blockingIo();               // 블로킹하면 캐리어가 pin 된다
    }
}

void goodNoPinning() {
    reentrantLock.lock();           // ReentrantLock 은 pin 을 유발하지 않는다
    try {
        blockingIo();               // 언마운트 정상 동작
    } finally {
        reentrantLock.unlock();
    }
}
```

회피 전략은 핫패스의 `synchronized`를 `java.util.concurrent.locks.ReentrantLock`으로 교체하는 것이다. `ReentrantLock`은 순수 자바 구현이라 대기 중 언마운트가 가능하다. 진단은 `-Djdk.tracePinnedThreads=full`(초기 버전) 또는 JFR의 `jdk.VirtualThreadPinned` 이벤트로 pinning 지점을 찾는다. 참고로 JDK 24의 JEP 491에서 `synchronized` 내부 블로킹의 pinning이 대부분 해소되어, 최신 JVM에서는 이 함정이 크게 줄었다. 그래도 레거시/구버전 런타임에서는 여전히 유효한 주의사항이다.

| 항목 | 플랫폼 스레드 | 가상 스레드 |
|---|---|---|
| OS 매핑 | 1:1 커널 스레드 | M:N(캐리어 다중화) |
| 스택 | 고정 예약(~1MB) | 힙 저장·가변, 대기 중 압축 |
| 생성 비용 | 높음(수 KB~수 ms) | 매우 낮음(수십만 개 가능) |
| 블로킹 시 | 커널 스레드 점유 | 언마운트(캐리어 반환) |
| 적합 워크로드 | CPU 바운드 | I/O 바운드 동시성 |

## 5. 실전 적용과 스레드풀 사고 전환

가상 스레드의 지침은 "풀링하지 말고, 요청마다 새로 만들라"이다. 플랫폼 스레드는 비싸서 풀로 재사용했지만, 가상 스레드는 생성이 값싸므로 풀링이 오히려 동시성을 제한하는 안티패턴이다. 동시 실행 개수를 제한하고 싶다면 스레드풀 대신 `Semaphore`로 자원 접근을 제어한다.

```java
// DB 커넥션이 20개뿐이라면 스레드가 아니라 세마포어로 제한한다
Semaphore db = new Semaphore(20);

void handle(Request req) throws InterruptedException {
    db.acquire();
    try {
        queryDatabase(req); // 최대 20개만 동시에 DB 를 친다
    } finally {
        db.release();
    }
}
```

구조적 동시성(`StructuredTaskScope`, JDK 21 프리뷰~)과 결합하면 자식 작업들의 생명주기를 부모 스코프에 묶어, 하나가 실패하면 형제를 취소하고 모두 조인하는 패턴을 안전하게 쓸 수 있다.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user = scope.fork(() -> fetchUser(id));
    var order = scope.fork(() -> fetchOrder(id));
    scope.join().throwIfFailed();          // 하나라도 실패하면 전체 취소
    return new Page(user.get(), order.get());
}
```

## 6. 트레이드오프와 오해

첫째, 가상 스레드는 CPU 바운드 작업을 빨라지게 만들지 않는다. 언마운트는 블로킹 I/O에서만 이득이고, 순수 계산은 여전히 코어 수에 제한된다. CPU 바운드에는 기존 `ForkJoinPool`이 맞다.

둘째, `ThreadLocal`은 가상 스레드에서도 동작하지만, 수십만 개가 각자 큰 `ThreadLocal` 값을 들면 힙을 압박한다. 가상 스레드 시대에는 `ScopedValue`(불변·상속 가능한 스코프 바인딩)가 권장 대안이다.

셋째, 라이브러리가 내부적으로 스레드풀을 캐싱하거나 `synchronized` 핫패스를 많이 쓰면 가상 스레드의 이점이 반감된다. JDBC 드라이버·커넥션풀 등 하위 스택이 pinning-free인지 확인해야 실측 이득이 난다. 실무에서는 "요청당 가상 스레드 + 세마포어 자원 제한 + ReentrantLock"의 조합이 안정적이다.

## 7. 관측과 진단: JFR·스레드 덤프

가상 스레드는 수십만 개가 될 수 있어 기존 스레드 덤프 방식이 그대로 통하지 않는다. 진단 도구도 이에 맞춰 바뀌었다. JDK Flight Recorder(JFR)는 가상 스레드 전용 이벤트를 제공한다. `jdk.VirtualThreadStart`/`jdk.VirtualThreadEnd`로 생성·종료를, `jdk.VirtualThreadPinned`로 pinning 발생을, `jdk.VirtualThreadSubmitFailed`로 스케줄 실패를 관찰한다.

```bash
# 가상 스레드 pinning 을 JFR 로 기록
java -XX:StartFlightRecording=filename=app.jfr,settings=profile -jar app.jar
# 기록 후 pinning 이벤트만 추출
jfr print --events jdk.VirtualThreadPinned app.jfr
```

전체 스레드 덤프도 진화했다. `jcmd <pid> Thread.dump_to_file -format=json dump.json`은 가상 스레드까지 포함해 구조적 동시성 스코프별로 계층화된 덤프를 만든다. 평범한 `jstack`은 플랫폼 스레드(캐리어)만 보여 주므로, 가상 스레드가 대량으로 어디서 대기 중인지 보려면 `jcmd` 경로를 쓴다.

성능 특성을 실측으로 이해하는 것도 중요하다. 가상 스레드의 이득은 "동시 블로킹 I/O 개수"가 클수록 커진다. 예를 들어 각 요청이 100ms 외부 호출을 기다리는 워크로드에서, 고정 크기 플랫폼 스레드풀(예: 200)은 동시 200요청이 상한이지만, 가상 스레드는 캐리어가 CPU 코어 수(예: 8)뿐이어도 수만 요청을 동시에 대기시킨다. 반대로 각 요청이 CPU를 100% 쓰는 계산이라면 두 방식 모두 코어 수에 묶여 처리량 차이가 사라진다. 즉 벤치마크는 반드시 실제 I/O 대기 비율을 반영해야 하며, 순수 CPU 마이크로벤치는 가상 스레드를 과소평가한다.

## 8. 마이그레이션 체크리스트

기존 서블릿/스프링 스택을 가상 스레드로 전환할 때 점검할 항목은 명확하다. 첫째, 요청 처리 스레드풀을 `newVirtualThreadPerTaskExecutor`나 프레임워크 설정(Spring Boot의 `spring.threads.virtual.enabled=true`)으로 전환한다. 둘째, 핫패스의 `synchronized`를 `ReentrantLock`으로 교체해 pinning을 없앱다(JDK 24 미만 런타임에서 특히). 셋째, 커넥션풀은 유지하되 크기를 자원(DB 커넥션) 기준으로 잡고, 애플리케이션 동시성은 세마포로 제어한다. 넷째, `ThreadLocal` 남용을 `ScopedValue`로 점진 대체한다.

```java
// Spring Boot 3.2+ 는 프로퍼티 하나로 요청 처리를 가상 스레드로 전환
// application.yml
// spring:
//   threads:
//     virtual:
//       enabled: true
```

전환 후에는 위 JFR pinning 이벤트가 0에 수렴하는지, 그리고 부하 시 캐리어 풀(`ForkJoinPool`)이 포화되지 않는지 확인한다. pinning이 남아 있으면 캐리어가 블로킹되어 처리량이 오히려 플랫폼 스레드보다 나빠질 수 있다.

## 참고

- JEP 444: Virtual Threads (JDK 21)
- JEP 453/462: Structured Concurrency (Preview)
- JEP 491: Synchronize Virtual Threads without Pinning (JDK 24)
- Oracle — Core Libraries: Virtual Threads 가이드
