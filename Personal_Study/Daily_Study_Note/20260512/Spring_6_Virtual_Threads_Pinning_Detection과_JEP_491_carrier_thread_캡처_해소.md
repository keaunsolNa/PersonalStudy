Notion 원본: https://www.notion.so/35e5a06fd6d38174b6cfe88fd4be2f50

# Spring 6 Virtual Threads Pinning Detection과 JEP 491 carrier thread 캡처 해소

> 2026-05-12 신규 주제 · 확장 대상: JAVA / Spring

## 학습 목표

- Virtual Thread 가 carrier thread 에 pinning 되는 정확한 조건과 JDK 21 ~ 24 사이 변화 추적
- JEP 491(Synchronize Virtual Threads without Pinning) 도입 이후 `synchronized` 의미 변화 이해
- `jdk.tracePinnedThreads`, JFR `VirtualThreadPinned` 이벤트로 pinning 핫스팟 식별
- Spring 6.x WebMVC + Tomcat 환경에서 pinning 으로 인한 처리량 손실을 측정·제거

## 1. Pinning 이란 무엇인가

가상 스레드(Virtual Thread, 이하 VT)는 JDK 21 부터 정식으로 도입된 사용자 모드 스레드다. JVM 의 ForkJoinPool 기반 carrier 스레드 위에서 multiplex 되며, blocking I/O 를 만나면 `Continuation.yield()` 로 stack 을 힙에 저장하고 carrier 를 해제한다. 그러나 특정 조건에서는 VT 가 carrier 를 잡고 놓지 않는데 이를 *pinning* 이라 부른다.

JDK 21 ~ 23 의 pinning 트리거:

| 트리거 | 설명 | JEP 491 적용 이후(JDK 24+) |
|---|---|---|
| `synchronized` 블록 안에서의 blocking | monitor 가 stack frame 에 박혀 있어 unmount 불가 | **해소** — monitor 도 unmount 대상 |
| native frame(JNI) 위에서의 blocking | JNI stack 은 heap 으로 옮길 수 없음 | 여전히 pinning |
| Class initializer(`<clinit>`) 내부 blocking | 클래스 초기화 락 보유 | 부분적으로 해소 |
| `Object.wait()` 사용 | JDK 21 에서는 pinning | JDK 24 부터 unmount 가능 |

핵심은 **JEP 491 이전에는 `synchronized` 가 사실상 VT killer** 였다는 점이다. Spring 코드 베이스 또는 그 의존성(Logback, Tomcat, Jackson 등)에 `synchronized` 가 단 한 줄이라도 hot path 에 있으면 처리량이 platform thread 풀과 차이가 없어진다.

## 2. JEP 491 가 바꾼 것

JEP 491 은 JDK 24 에 들어간 변경으로, Object monitor 의 ownership 을 carrier 가 아닌 **Java thread identity** 와 분리해 추적한다. 구현적으로는 monitor 에 들어갈 때 carrier 가 아니라 *Java 레벨의 thread* 를 owner 로 기록하고, unmount 시 carrier 와 monitor 의 연결을 끊는다. remount 될 때 새로운 carrier 가 동일한 Java thread 의 lock 이라고 인식하므로 reentrant 시맨틱이 유지된다.

영향:

1. `ReentrantLock` 으로의 강제 마이그레이션 압력이 사라진다. 라이브러리 작성자는 `synchronized` 를 그대로 유지해도 VT 친화적이다.
2. 다만 *fairness* 가 다르다. ReentrantLock(fair=true) 와 동일한 보장은 여전히 제공되지 않는다.
3. JNI, `<clinit>`, Class loading 락은 여전히 pinning 을 일으킨다. 라이브러리 의존성 검사를 멈출 수 없다.

## 3. Pinning 측정 — 세 가지 도구

### 3.1 JVM flag `-Djdk.tracePinnedThreads=short|full`

가장 손쉽다. pinning 이 발생할 때마다 stdout 에 stack trace 를 찍는다.

```
java -Djdk.tracePinnedThreads=short -jar app.jar
```

`short` 는 pinning 발생 frame 1 개만, `full` 은 전체 frame 을 찍는다. 운영 환경에서는 로그 폭주를 막기 위해 *카나리 인스턴스 1대* 에만 short 옵션을 켜고 5~10분 단위로 sampling 한다.

### 3.2 JFR(Java Flight Recorder) `jdk.VirtualThreadPinned` 이벤트

운영 환경 친화적인 방법. JFR 은 ring buffer 기반이라 오버헤드가 1~2% 수준이다.

```
java -XX:StartFlightRecording=duration=120s,filename=pin.jfr,settings=profile app.jar
```

`jfr print --events jdk.VirtualThreadPinned pin.jfr` 로 핫스팟별 stack 과 누적 시간을 본다. `duration` 필드 임계값을 정해두면(예: 20ms 이상) 정말 문제가 되는 케이스만 골라낼 수 있다.

### 3.3 Micrometer + JFRStreamingMetrics

Spring Boot 3.4+ 의 `actuator` 와 Micrometer 1.13 의 `JfrJvmMetrics` 가 `jvm.threads.pinned` 게이지를 노출한다. Prometheus 에 적재해 dashboard 에서 추세를 본다.

## 4. Spring WebMVC + Tomcat 에서의 실측 패턴

Spring 6.1+ 는 `spring.threads.virtual.enabled=true` 한 줄로 `@RequestMapping` 핸들러를 VT 위에서 실행한다. 내부적으로 `TomcatProtocolHandlerCustomizer` 가 Tomcat 의 `protocol.setExecutor()` 를 `Executors.newVirtualThreadPerTaskExecutor()` 로 교체한다.

이상적인 처리량 곡선은 CPU 코어 N 개에 대해 동시 요청 수 C 가 N×수십~수백 까지 linear scale 된다. 실제 측정에서 곡선이 꺾이면 거의 항상 pinning 또는 connection pool 고갈이다.

### 4.1 흔한 핀 포인트

HikariCP 의 connection borrow: HikariCP 5.0.x 까지의 `ConcurrentBag.borrow()` 는 lock-free 지만 `connectionTimeout` 만료 시 내부적으로 `synchronized` 가 한 곳 있었다. 5.1+ 에서 제거됨.

Logback: 1.5.6 이전의 `ch.qos.logback.core.OutputStreamAppender.doAppend` 가 `synchronized`. 1.5.7 부터 ReentrantLock 로 교체.

Spring Validation: `org.hibernate.validator.internal.engine.ConfigurationImpl` 정적 초기화 일부. 첫 요청에만 발생하므로 워밍업으로 회피.

Jackson: `ObjectMapper.findAndAddModules()` 호출이 SPI 로드를 트리거할 때 ClassLoader 락 → pinning. 부트 시 한 번만 호출하면 영향 없음.

### 4.2 production 코드 점검

자체 코드에서 의심스러운 패턴:

```java
// 안티 패턴: 캐시 갱신 시 synchronized
public class CountryCache {
    private final Map<String, Country> cache = new HashMap<>();

    public synchronized Country get(String code) {
        return cache.computeIfAbsent(code, this::loadFromDb);
    }

    private Country loadFromDb(String code) {
        return jdbcTemplate.queryForObject(...);  // ← VT가 여기서 unmount해야 하는데
                                                  //   synchronized 락을 잡고 있어 pinning
    }
}
```

JDK 21~23 에서는 `loadFromDb` 가 호출되는 동안 carrier 가 잡힌다. 동시에 다른 VT 가 다른 메서드를 실행하려 해도 carrier 가 부족해 대기한다.

해결:

```java
private final ConcurrentHashMap<String, Country> cache = new ConcurrentHashMap<>();
private final StripedLock locks = new StripedLock(64);

public Country get(String code) {
    Country v = cache.get(code);
    if (v != null) return v;
    var lock = locks.forKey(code);
    lock.lock();
    try {
        return cache.computeIfAbsent(code, this::loadFromDb);
    } finally {
        lock.unlock();
    }
}
```

`ReentrantLock` 은 pinning 을 일으키지 않고, Striped 방식으로 hot key 경합도 분산된다.

## 5. carrier pool 사이즈와 동적 조정

기본 carrier pool 은 `Runtime.getRuntime().availableProcessors()` 만큼이다. 시스템 프로퍼티 `jdk.virtualThreadScheduler.parallelism` 으로 override 가능하다.

```
-Djdk.virtualThreadScheduler.parallelism=32
-Djdk.virtualThreadScheduler.maxPoolSize=512
-Djdk.virtualThreadScheduler.minRunnable=2
```

`maxPoolSize` 는 `ForkJoinPool` 이 추가 carrier 를 생성할 수 있는 최대값. pinning 이 종종 발생하는 환경에서는 maxPoolSize 를 코어 수의 4~8배로 잡아 burst 를 흡수한다. 단 메모리 사용량과 GC 압박이 함께 늘어난다.

| 설정 | 작은 값(8) | 큰 값(256) |
|---|---|---|
| Pinning 발생 시 처리량 저하 | 즉시 발생 | 완화 |
| 정상 상태 메모리 | 낮음 | 높음(stack ~512KB × N) |
| Context switch 오버헤드 | 적음 | 많음 |

## 6. ScopedValue 와 ThreadLocal 의 갈등

VT 환경에서 ThreadLocal 은 *의미상* 동작하지만, 수만 개의 VT 가 각자 ThreadLocal 사본을 갖는 비용은 무시할 수 없다. JEP 506(ScopedValue, JDK 24 final)이 대체재로 등장했다.

```java
final static ScopedValue<UserContext> USER_CTX = ScopedValue.newInstance();

ScopedValue.where(USER_CTX, user).run(() -> {
    // 이 블록 안 모든 VT 자식은 USER_CTX 로 user 를 본다
    service.execute();
});
```

Spring Security 의 `SecurityContextHolder` 는 여전히 ThreadLocal 기반이다. 6.4+ 에서 ScopedValue 기반 SecurityContextHolderStrategy 가 실험 도입되었으나 GA 는 7.x 예정이다.

## 7. 측정 사례 — JDK 21 vs JDK 24

내부 벤치마크(8 core, 16GB heap, Spring Boot 3.4, Postgres 13). `/api/orders` 가 DB 조회 1회 + Redis 2회 + 외부 HTTP 1회를 호출한다.

| 설정 | RPS | P99 latency | Pinned event(120s) |
|---|---|---|---|
| Platform thread(200) JDK 21 | 1,420 | 218ms | 0 |
| VT JDK 21, Logback 1.5.6 | 980 | 410ms | 14,300 |
| VT JDK 21, Logback 1.5.7 | 3,150 | 92ms | 380 |
| VT JDK 24(JEP 491), Logback 1.5.6 | 3,080 | 95ms | 12 |
| VT JDK 24(JEP 491), Logback 1.5.7 | 3,210 | 88ms | 8 |

JDK 24 는 의존성이 구버전이어도 *대부분의 pinning 을 자동 해소*한다. 하지만 라이브러리 업그레이드는 여전히 가치가 있다. JEP 491 은 `synchronized` 만 다루고 JNI 와 `<clinit>` 은 그대로기 때문이다.

## 8. 운영 체크리스트

1. JDK 버전 확인: 가능하면 24+ 로 올린다. 21 LTS 에 머무를 거라면 의존성 업그레이드가 더 절실하다.
2. JFR `VirtualThreadPinned` 이벤트를 24시간 sampling 으로 켜둔다(임계값 10ms).
3. `Thread.dumpStack()` 만 신뢰하지 말 것. VT 는 jstack 결과가 carrier 기준이라 헷갈린다. `jcmd <pid> Thread.vthread_dump` 사용.
4. 라이브러리 audit: HikariCP 5.1+, Logback 1.5.7+, Spring 6.1+, Jackson 2.17+, Tomcat 10.1.20+.
5. `synchronized` 자체 코드 검색: `grep -rn "synchronized\s*(" src/main/java` 후 hot path 만 ReentrantLock 으로 옮기거나 JDK 24 로 미룬다.

## 참고

- JEP 491: Synchronize Virtual Threads without Pinning — https://openjdk.org/jeps/491
- JEP 444: Virtual Threads — https://openjdk.org/jeps/444
- JEP 506: Scoped Values — https://openjdk.org/jeps/506
- Spring Framework Reference: Virtual Threads — https://docs.spring.io/spring-framework/reference/integration/scheduling.html#virtual-threads
- Ron Pressler, "State of Loom" — https://cr.openjdk.org/~rpressler/loom/loom/sol1_part1.html
