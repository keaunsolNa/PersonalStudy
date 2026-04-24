Notion 원본: https://www.notion.so/34c5a06fd6d381e4b52aec55170c5d66

# Virtual Threads(Project Loom)와 Spring Boot 3.2+ 실전

> 2026-04-24 신규 주제 · 확장 대상: JAVA (JMM/JIT 학습됨)

## 학습 목표

- Platform Thread와 Virtual Thread의 스케줄링 차이를 carrier thread 관점에서 설명한다
- Spring Boot 3.2+에서 `spring.threads.virtual.enabled=true`가 실제로 바꾸는 것을 코드로 확인한다
- Synchronized/native 호출로 인한 pinning 문제를 감지하고 ReentrantLock으로 회피한다
- JEP 453 Structured Concurrency로 여러 HTTP 호출을 병렬 처리한다

---

## 1. Virtual Thread의 개념

Virtual Thread는 JDK 21(LTS)에서 stable 된 JEP 444의 결과물이다. 기존 Platform Thread는 OS 스레드와 1:1 매핑되어 생성 비용이 크고(~1MB 스택), 컨텍스트 스위치가 커널 영역에서 일어난다. Virtual Thread는 JVM이 관리하는 사용자 수준 스레드로, 여러 개가 소수의 **carrier thread**(기본적으로 ForkJoinPool.commonPool 크기 = CPU 코어 수) 위에서 swap 된다. 블로킹 호출이 일어나면 JVM이 virtual thread를 carrier에서 분리(park)하고 다른 virtual thread에 carrier를 넘긴다. "스레드가 저렴해졌다"는 체감은 이 parking이 OS 스케줄러를 거치지 않아서다.

벤치마크로 감을 잡아보자. Platform Thread 10,000개 생성 → 힙/스택 메모리 ~8GB, 생성 시간 ~6초. Virtual Thread 1,000,000개 생성 → 힙 사용량 ~800MB, 생성 시간 ~1.2초 (JDK 21, m6i.xlarge). 즉 숫자가 3자리 이상 다르다.

## 2. Platform Thread vs Virtual Thread 비교

| 항목 | Platform Thread | Virtual Thread |
|---|---|---|
| OS 스레드 매핑 | 1:1 | M:N (carrier 경유) |
| 스택 크기 | 고정(기본 1MB) | 힙에 rope 형태, 동적 성장 |
| 생성 비용 | 수 ms | 수 μs |
| 컨텍스트 스위치 | OS 스케줄러 | JVM 스케줄러 (userspace) |
| 적합 워크로드 | CPU-bound | I/O-bound |

CPU-bound 작업에서는 Virtual Thread가 오히려 느리다. 이유는 carrier thread가 결국 CPU 코어 수만큼이고, virtual thread들이 여기서 경쟁하기 때문이다.

## 3. Spring Boot 3.2+ 통합

```properties
# application.properties
spring.threads.virtual.enabled=true
```

이 한 줄로 Tomcat/Jetty의 요청 처리 executor가 `Executors.newVirtualThreadPerTaskExecutor()`로 바뀐다. 내부적으로는 `TomcatProtocolHandlerCustomizer`가 protocolHandler의 executor를 교체한다. WebFlux는 원래 reactive라 영향 없고, 영향 받는 것은 Spring MVC (Tomcat/Jetty) + `@Async` + Scheduled tasks다.

```java
@RestController
public class OrderController {
    private final OrderService service;

    @GetMapping("/orders/{id}/enrich")
    public EnrichedOrder enrich(@PathVariable Long id) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<Order> order = scope.fork(() -> service.fetchOrder(id));
            Subtask<User> user  = scope.fork(() -> service.fetchUser(id));
            Subtask<List<Item>> items = scope.fork(() -> service.fetchItems(id));
            scope.join().throwIfFailed();
            return new EnrichedOrder(order.get(), user.get(), items.get());
        }
    }
}
```

각 HTTP 요청이 virtual thread에서 처리되므로, 외부 API 3개를 병렬 호출해도 carrier thread는 몇 개만 점유한다.

## 4. Pinning 문제

Virtual Thread가 carrier에 "고정(pin)"되어 다른 virtual thread에게 양보하지 못하는 경우가 있다. 두 가지 주범:

1. **`synchronized` 블록 내부의 블로킹**: JDK 21에서는 synchronized 진입 시 monitor 소유자가 carrier에 기록되어, 그 안에서 park 하면 carrier도 같이 park 된다. JDK 24+ (JEP 491)에서 해결될 예정이지만 21에서는 workaround 필요.
2. **Native 메서드 호출 중의 블로킹**: JNI 호출은 carrier를 내려놓을 수 없다.

```java
// 문제 코드
synchronized (cache) {
    value = remoteApi.fetchBlocking(key);   // 여기서 pinning
    cache.put(key, value);
}

// 해결 코드
private final ReentrantLock lock = new ReentrantLock();
public V getOrFetch(K key) {
    lock.lock();
    try {
        V v = cache.get(key);
        if (v != null) return v;
        v = remoteApi.fetchBlocking(key);
        cache.put(key, v);
        return v;
    } finally {
        lock.unlock();
    }
}
```

Pinning 감지는 `-Djdk.tracePinnedThreads=full` 또는 JFR의 `jdk.VirtualThreadPinned` 이벤트로 한다.

## 5. @Async와 executor 설정

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

Spring Boot 3.2에서는 `spring.threads.virtual.enabled=true`가 이 빈을 자동으로 등록해 준다. 직접 오버라이드 하고 싶을 때만 위처럼 작성. 주의점은 `ThreadPoolTaskExecutor`가 아니라 `TaskExecutorAdapter`를 써야 한다는 점. 전자는 platform thread 고정 풀이다.

## 6. 실측 벤치마크

테스트: Spring Boot 3.2 app, 외부 HTTP API(지연 500ms) 호출, wrk로 부하.

| 설정 | throughput | p99 latency | 스레드 수 |
|---|---|---|---|
| Tomcat 기본(max-threads=200) | 380 req/s | 1,800ms | 200 |
| Tomcat max-threads=2000 | 1,950 req/s | 620ms | 2,000 |
| Virtual Threads | 1,980 req/s | 540ms | carrier 16 / virtual ~2000 |

최대 동시성이 같다면 처리량은 비슷하지만, 메모리 풋프린트가 현저히 작다. Virtual Thread 쪽 JVM 메모리는 platform thread 2000개 대비 약 45% 수준.

## 7. Structured Concurrency (JEP 453, preview)

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<String> a = scope.fork(() -> serviceA.call());
    Subtask<String> b = scope.fork(() -> serviceB.call());
    scope.joinUntil(Instant.now().plusSeconds(3));
    scope.throwIfFailed();
    return combine(a.get(), b.get());
}
```

자식 task가 throw하면 나머지도 자동 취소된다. CompletableFuture + allOf 조합보다 오류 전파와 lifecycle 관리가 명확하다. 아직 preview라 `--enable-preview` 필요.

## 8. 마이그레이션 체크리스트

1. JDK 21+ 업그레이드
2. Spring Boot 3.2+ 업그레이드
3. `spring.threads.virtual.enabled=true`
4. 자체 구현한 ThreadPool이 있다면 virtualThreadPerTaskExecutor로 교체
5. `synchronized` 안에서 블로킹 I/O 호출하는 코드 검색 → ReentrantLock으로 교체
6. JFR로 `jdk.VirtualThreadPinned` 이벤트 모니터링
7. ThreadLocal 사용 검토 — 매 요청마다 virtual thread가 새로 생성되므로 캐싱 효과 없음. ScopedValue(JEP 446, preview) 고려

## 참고

- JEP 444: Virtual Threads: https://openjdk.org/jeps/444
- JEP 453: Structured Concurrency (Preview): https://openjdk.org/jeps/453
- Spring Boot 3.2 Release Notes — Virtual Threads: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes
- Ron Pressler "State of Loom" (2023)
