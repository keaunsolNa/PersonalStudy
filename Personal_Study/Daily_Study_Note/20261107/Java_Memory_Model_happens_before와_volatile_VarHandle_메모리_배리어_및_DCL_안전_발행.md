Notion 원본: https://www.notion.so/4115a06fd6d3814d8027f4aecb572e30

# Java Memory Model: happens-before, volatile·VarHandle 메모리 배리어, DCL 안전 발행

> 2026-11-07 신규 주제 · 확장 대상: JAVA (동시성 · JIT)

## 학습 목표

- 재배치와 가시성 문제가 발생하는 하드웨어·컴파일러 원인을 구분한다
- happens-before 규칙 8가지를 코드에 적용해 데이터 레이스 여부를 판정한다
- `volatile` 의 보장 범위와 한계(복합 연산 비원자성)를 구분한다
- `VarHandle` 의 plain / opaque / acquire-release / volatile 접근 모드를 선택한다
- Double-Checked Locking, final 필드 안전 발행, 불변 객체 패턴을 올바르게 구현한다

## 1. 왜 메모리 모델이 필요한가

멀티스레드 코드가 직관과 다르게 동작하는 이유는 세 곳에서 순서가 바뀌기 때문이다. 컴파일러/JIT 는 단일 스레드 의미를 보존하는 한 명령을 재배치하고 값을 레지스터에 캐싱한다. CPU 는 아웃오브오더 실행과 스토어 버퍼로 쓰기 순서를 바꾼다(x86 은 TSO 로 비교적 강하지만 Store→Load 재배치를 허용하고, ARM/POWER 는 훨씬 약하다). 캐시 계층은 코어 간 가시화 시점이 다르다. Java Memory Model(JSR-133, Java 5)은 이 모든 하드웨어 차이를 **happens-before 관계**라는 추상으로 감춰, "올바르게 동기화된 프로그램"이면 어떤 플랫폼에서도 순차 일관성처럼 동작함을 보장한다.

```java
class Flag {
    boolean ready;      // volatile 아님
    int value;
    void writer() { value = 42; ready = true; }
    void reader() {
        while (!ready) { }               // JIT 가 ready 를 루프 밖으로 hoist 하면 무한 루프
        System.out.println(value);       // 0 이 출력될 수도 있음
    }
}
```

위 코드는 데이터 레이스다. `ready` 의 쓰기와 읽기 사이에 happens-before 가 없으므로 reader 는 `ready` 를 영원히 못 볼 수도, `ready == true` 이면서 `value == 0` 을 볼 수도 있다.

## 2. happens-before 규칙

A happens-before B 이면 A 의 모든 쓰기 결과가 B 에서 보인다. 규칙은 다음과 같다.

| 규칙 | 의미 |
|---|---|
| 프로그램 순서 | 한 스레드 내에서 앞선 동작이 뒤 동작보다 먼저 |
| 모니터 락 | `unlock` 이 같은 모니터의 이후 `lock` 보다 먼저 |
| volatile | volatile 쓰기가 같은 변수의 이후 읽기보다 먼저 |
| 스레드 시작 | `Thread.start()` 호출 이전 동작이 새 스레드의 모든 동작보다 먼저 |
| 스레드 종료 | 스레드의 모든 동작이 `join()` 반환·`isAlive()==false` 확인보다 먼저 |
| 인터럽트 | `interrupt()` 호출이 인터럽트 감지보다 먼저 |
| 생성자 완료 | 객체 생성자 종료가 `finalize()` 시작보다 먼저 |
| 전이성 | A→B, B→C 이면 A→C |

전이성이 실용의 열쇠다. 위 Flag 예제는 `ready` 를 `volatile` 로 선언하면 `value = 42`(프로그램 순서) → `ready = true`(volatile 쓰기) → `ready` 읽기 → `println(value)`(프로그램 순서) 로 이어져 `value` 가 반드시 42 로 보인다. 이를 "volatile 피기백킹"이라 부르며 `ConcurrentHashMap` 같은 java.util.concurrent 컬렉션의 "put 이전 동작은 get 이후에 보인다" 보장도 이 원리다.

## 3. volatile 의 보장과 한계

`volatile` 은 ① 가시성(쓰기가 즉시 다른 스레드에 보임) ② 해당 변수 접근 주변의 재배치 금지 ③ `long`/`double` 의 원자적 읽기·쓰기를 제공한다. JIT 는 volatile 쓰기 뒤에 StoreLoad 배리어를 삽입한다(x86 에서 `lock addl $0,(%rsp)` 같은 명령). 읽기는 x86 에서 사실상 일반 `mov` 이다. 따라서 volatile 읽기는 저렴하고 쓰기는 상대적으로 비싸다(수~수십 ns).

그러나 **복합 연산은 원자적이지 않다.** `count++` 는 읽기-수정-쓰기 3단계이므로 volatile 이어도 갱신을 잃는다.

```java
class Counter {
    volatile int n;
    void inc() { n++; }                    // 잘못됨: 레이스
}
// 올바른 대안
class Counter2 {
    private final AtomicInteger n = new AtomicInteger();
    void inc() { n.incrementAndGet(); }    // CAS 루프 (LOCK XADD)
    // 경합이 심하면 LongAdder: 셀 분산으로 쓰기 처리량 수 배 향상, 읽기는 합산 비용
}
```

## 4. VarHandle: 접근 모드로 비용 조절

Java 9 의 `VarHandle` 은 `Unsafe` 를 대체하는 공식 저수준 API 이며 접근 강도를 선택할 수 있다.

| 모드 | 메서드 | 보장 |
|---|---|---|
| Plain | `get/set` | 일반 변수와 동일, 보장 없음 |
| Opaque | `getOpaque/setOpaque` | 해당 변수 접근의 일관성(결국 보임, 컴파일러 hoist 금지)만 보장, 타 변수와의 순서 보장 없음 |
| Acquire/Release | `getAcquire/setRelease` | release 쓰기 이전의 모든 동작이 이를 acquire 로 읽은 스레드에 보임 |
| Volatile | `getVolatile/setVolatile` | 순차 일관성 |

```java
class SpscSlot<T> {
    private T item;
    private static final VarHandle ITEM;
    static {
        try { ITEM = MethodHandles.lookup().findVarHandle(SpscSlot.class, "item", Object.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    boolean offer(T t) {                       // 단일 생산자
        if (ITEM.getOpaque(this) != null) return false;
        ITEM.setRelease(this, t);              // t 의 필드 초기화가 소비자에게 먼저 보임
        return true;
    }
    T poll() {                                 // 단일 소비자
        T t = (T) ITEM.getAcquire(this);
        if (t != null) ITEM.setRelease(this, null);
        return t;
    }
}
```

단일 생산자/단일 소비자 구조에서는 release/acquire 만으로 충분해 StoreLoad 배리어 비용을 피한다. 이것이 Disruptor 류 링버퍼의 핵심 아이디어다. 그러나 여러 생산자·소비자나 Dekker 류 상호 배제처럼 StoreLoad 순서가 필요한 알고리즘에서는 acquire/release 로는 부족하고 volatile 이 필요하다. 약한 모드는 x86 에서 volatile 과 거의 차이가 없고 ARM 에서 차이가 크므로, 최적화 근거 없이 쓰면 이식성 위험만 생긴다. JMH 로 대상 하드웨어에서 측정하기 전에는 volatile 을 기본값으로 둔다.

## 5. Double-Checked Locking

```java
// 잘못된 DCL (Java 5 이전, 또는 volatile 누락 시)
class Lazy {
    private static Lazy inst;
    static Lazy get() {
        if (inst == null) {
            synchronized (Lazy.class) {
                if (inst == null) inst = new Lazy();   // 할당 → 필드 초기화 → 참조 대입이 재배치되면
            }                                          // 다른 스레드가 반쯤 초기화된 객체를 봄
        }
        return inst;
    }
}

// 올바른 DCL
class Lazy2 {
    private static volatile Lazy2 inst;
    static Lazy2 get() {
        Lazy2 r = inst;                 // volatile 읽기를 1회로 줄이는 지역 변수 트릭
        if (r == null) {
            synchronized (Lazy2.class) {
                r = inst;
                if (r == null) inst = r = new Lazy2();
            }
        }
        return r;
    }
}

// 더 간단한 대안: 홀더 클래스 (클래스 초기화는 JVM 이 락과 happens-before 를 보장)
class Lazy3 {
    private Lazy3() {}
    private static class Holder { static final Lazy3 INSTANCE = new Lazy3(); }
    static Lazy3 get() { return Holder.INSTANCE; }
}
```

`volatile` 이 없으면 생성자 안의 쓰기와 `inst` 대입이 재배치될 수 있다. 홀더 관용구는 클래스 초기화 규칙(JLS 12.4.2)이 초기화 락을 통해 happens-before 를 만들어 주므로 가장 단순하고 안전하다. enum 싱글턴도 동일 원리다.

## 6. final 필드와 안전 발행

JMM 은 `final` 필드에 특별 보장을 준다. 생성자가 끝나는 시점에 **freeze** 동작이 일어나, 생성자 안에서 `this` 가 유출되지 않았다면 다른 스레드가 레이스로 객체 참조를 얻더라도 final 필드와 그 필드가 가리키는 객체의 초기 상태(생성자 종료 시점)는 올바르게 보인다.

```java
final class Money {
    private final long amount;
    private final String currency;
    private final List<String> tags;      // final 이어도 내용 변경 가능 → 방어적 복사 필요
    Money(long amount, String currency, List<String> tags) {
        this.amount = amount;
        this.currency = currency;
        this.tags = List.copyOf(tags);    // 불변 복사
        // Registry.register(this);       // 금지: 생성자에서 this 유출 시 freeze 보장 깨짐
    }
}
```

안전 발행 방법은 ① static 초기화 ② volatile 필드·`AtomicReference` 에 저장 ③ 락으로 보호된 필드에 저장 ④ `ConcurrentHashMap` 등 스레드 안전 컬렉션에 저장 ⑤ final 필드(불변 객체)다. 이 중 하나도 아닌 일반 필드 대입은 안전하지 않은 발행이다. Spring 빈은 컨테이너가 초기화 후 안전 발행 경로(싱글턴 레지스트리 락)를 거치므로 불변 설정 객체는 안전하다. `@Async` 나 `CompletableFuture` 의 결과 전달에도 happens-before 가 있다(`complete()` 이전 동작은 `get()` 이후에 보임).

## 7. JIT 와의 상호작용과 진단

JIT 는 락 제거(escape analysis 로 스레드 로컬임을 증명한 `synchronized` 제거), 락 조대화(인접한 락 통합), 루프 불변식 호이스팅을 수행한다. 이 때문에 volatile 없는 플래그 폴링 루프는 인터프리터에서는 동작하지만 C2 컴파일 후 멈추는 "워밍업 후에만 재현되는 버그"가 생긴다. 검증 도구로는 **jcstress**(OpenJDK 동시성 스트레스 프레임워크)를 쓴다. 허용되는 결과 집합을 정의하고 수백만 번 반복 실행해 금지된 결과(예: `ready=true, value=0`)가 관측되는지 확인한다.

```java
@JCStressTest
@Outcome(id = "1, 42", expect = ACCEPTABLE)
@Outcome(id = "1, 0",  expect = FORBIDDEN, desc = "volatile 누락 시 발생")
@State
public class PublishTest {
    int value; volatile boolean ready;
    @Actor void writer() { value = 42; ready = true; }
    @Actor void reader(II_Result r) { r.r1 = ready ? 1 : 0; r.r2 = value; }
}
```

(위 `@Outcome` 은 `ready` 를 읽은 경우를 중심으로 설명용 단순화한 것이며, 실제 테스트는 `ready=false` 결과 `0, x` 도 ACCEPTABLE 로 기술해야 한다.) 분석 시 `-XX:+PrintAssembly`(hsdis 필요)로 volatile 쓰기 뒤 `lock` 접두 명령을 확인하면 배리어 삽입을 눈으로 검증할 수 있다.

## 8. 실무 지침

가변 공유 상태는 우선 피하고, 필요하면 `java.util.concurrent` 의 상위 추상(`ConcurrentHashMap`, `BlockingQueue`, `CompletableFuture`)을 쓴다. 상태 플래그에는 `volatile`, 카운터에는 `AtomicLong`/`LongAdder`, 복합 불변식에는 `synchronized`/`ReentrantLock` 을 쓴다. 불변 객체와 안전 발행이 가장 적은 비용으로 가장 많은 버그를 없앤다. `VarHandle` 약한 모드는 프로파일링으로 병목이 증명되고 jcstress 로 정당성을 검증한 뒤에만 도입한다. 코드 리뷰 체크리스트: ① 공유 가변 필드의 모든 접근에 동일한 동기화 수단이 있는가 ② check-then-act 가 원자적인가 ③ 생성자에서 `this` 가 유출되지 않는가 ④ 컬렉션 요소의 가변 상태 변경이 안전 발행되는가.

## 참고

- JLS 17 — Threads and Locks (docs.oracle.com/javase/specs)
- JSR-133 Cookbook for Compiler Writers (Doug Lea)
- JEP 193 — Variable Handles
- OpenJDK jcstress (github.com/openjdk/jcstress)
- 『Java Concurrency in Practice』 3장 — 객체 공유
