Notion 원본: https://www.notion.so/35c5a06fd6d38143afedee39315e43ab

# Java VarHandle과 Memory Order Plain Opaque Acquire Release

> 2026-05-10 신규 주제 · 확장 대상: JAVA

## 학습 목표

- JDK 9 VarHandle 이 sun.misc.Unsafe 와 java.util.concurrent.atomic 을 어떻게 통합하는지, 도입 이유를 이해한다
- VarHandle 이 제공하는 5단계 access mode (Plain, Opaque, Acquire/Release, Volatile, CAS) 가 JMM(Java Memory Model) 의 어느 보장과 매칭되는지 정리한다
- Acquire/Release 와 Volatile 의 차이가 sequential consistency 보장 유무라는 점, 이게 lock-free 큐 구현의 정확성에 어떻게 영향을 주는지 본다
- 잘못된 access mode 선택이 만드는 race 종류 (write tearing, stale read, reordering bug) 와 진단 도구를 익힌다

## 1. VarHandle 등장 배경 — sun.misc.Unsafe 의 결말

JDK 8 까지 lock-free 자료구조의 구현체(ConcurrentHashMap, AtomicReferenceArray, LinkedTransferQueue) 는 거의 모두 sun.misc.Unsafe 에 직접 의존했다. compareAndSwapInt, getObjectVolatile, putOrderedInt 같은 native 인터페이스를 호출했다. 문제:

- 비공개 internal API. JEP 396 (Strongly Encapsulate JDK Internals) 으로 모듈 시스템에서 차단.
- 안전성 0. nullable 안 보장, 메모리 corruption 가능.
- 메모리 모델 보장이 문서화되지 않음. putOrderedInt 가 정확히 어떤 reordering 을 막는지 JLS 에 없었음.

JDK 9 의 VarHandle (JEP 193) 이 위를 모두 해결한다.

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class Counter {
    private long value = 0L;
    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup()
                .findVarHandle(Counter.class, "value", long.class);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public long incrementAndGet() {
        return (long) VALUE.getAndAdd(this, 1L) + 1L;
    }
}
```

private field 에 직접 접근하는 객체. AtomicLong 같은 wrapper 가 필요 없고, AtomicLongFieldUpdater 처럼 reflection 캐싱을 사용자가 안 해도 된다. 성능은 직접 native intrinsic 으로 컴파일되어 AtomicLong 과 동등하거나 약간 빠르다.

## 2. 5단계 access mode

VarHandle 의 메서드는 다음 prefix 로 모드를 표시한다.

| 모드 | 메서드 prefix | JMM 보장 | hardware 비용 |
| --- | --- | --- | --- |
| Plain | get, set | 단일 read/write atomicity 만 | 없음 (그냥 load/store) |
| Opaque | getOpaque, setOpaque | atomicity + happens-before within thread + 코히런스 | x86: 없음, ARM: 약 |
| Acquire/Release | getAcquire, setRelease | happens-before across threads (paired) | x86: 없음, ARM: 약 |
| Volatile | getVolatile, setVolatile | sequential consistency, full fence | x86: store fence, ARM: dmb ish |
| CAS | compareAndSet, weakCompareAndSet | atomic CAS + acquire+release | x86: lock cmpxchg, ARM: ldxr/stxr |

가장 약한 Plain 부터 가장 강한 CAS 까지 *비용이 단조 증가* 한다. 정확성이 허용하는 가장 약한 모드를 쓰는 게 lock-free 자료구조의 핵심 최적화다.

## 3. JMM 모델로 매칭

- *Atomicity*: write tearing 없음. 64-bit primitive (long, double) 의 plain write 는 32-bit JVM 에서 두 word 로 쪼개질 수 있다. Plain mode 에서도 long/double 은 OS/JVM 에 따라 atomic 보장 안 됨. Opaque 부터 보장.
- *Coherence*: 단일 변수에 대한 모든 read/write 가 some total order 에 있다. Opaque 부터 보장.
- *Happens-before within thread*: 같은 스레드 안에서 program order. Plain 도 보장.
- *Happens-before across threads*: Acquire/Release pair 또는 Volatile 부터.
- *Sequential consistency*: 모든 스레드가 보는 *전체 order* 가 같다. Volatile 만.

Acquire/Release 와 Volatile 의 결정적 차이는 SC 보장이다. 다른 변수의 acquire/release 들과는 total order 가 없을 수 있다. 이게 lock-free 자료구조의 미세한 버그(IRIW: Independent Read of Independent Write) 의 원인이 된다.

## 4. Plain — 가장 약한 모드의 위험

```java
class Wrong {
    private boolean flag = false;
    private int data = 0;
    static final VarHandle FLAG = MethodHandles.lookup().findVarHandle(Wrong.class, "flag", boolean.class);

    void writer() {
        data = 42;
        FLAG.set(this, true);  // Plain set
    }
    void reader() {
        while (!(boolean) FLAG.get(this));  // Plain get
        System.out.println(data);  // 0 또는 42 — 보장 없음
    }
}
```

Plain set 은 reordering 을 막지 않는다. JIT 가 `data = 42;` 를 `FLAG.set(true)` 다음으로 옮길 수 있고, 다른 코어가 flag=true 를 보더라도 data 는 아직 0 일 수 있다. Plain 은 *단일 스레드 알고리즘* 이거나 다른 동기화로 둘러싸인 영역에서만 안전하다.

## 5. Opaque — single-variable visibility

Opaque 는 *같은 변수에 대한* 모든 access 가 코히런스를 만족하게 한다. 다만 *다른 변수와의 ordering 보장은 없다*.

```java
class Counter {
    private long count = 0;
    static final VarHandle COUNT = MethodHandles.lookup().findVarHandle(Counter.class, "count", long.class);

    long read() { return (long) COUNT.getOpaque(this); }
}
```

read() 만 사용하는 monitoring 코드라면 Opaque 로 충분하고 비용이 거의 0 이다. 통계 카운터 같은 *대략적인 값* 을 읽는 곳에 적합. 정확한 카운터에는 getAndAdd (CAS 기반) 사용.

## 6. Acquire/Release — paired ordering

가장 자주 쓰이는 lock-free 패턴.

```java
class SafeFlag {
    private int data = 0;
    private boolean published = false;
    static final VarHandle PUBLISHED = MethodHandles.lookup().findVarHandle(SafeFlag.class, "published", boolean.class);

    void publish(int v) {
        data = v;                                  // plain write
        PUBLISHED.setRelease(this, true);          // release
    }
    int consume() {
        while (!(boolean) PUBLISHED.getAcquire(this));  // acquire
        return data;                                    // 정확히 v 가 보임
    }
}
```

release-write 는 *그 이전의 모든 write 가 다른 스레드의 acquire-read 후에 보이도록* 보장한다.

CPU 매핑:
- x86: TSO 라 release/acquire 가 평범한 store/load. extra fence 없음.
- ARM/Power: weakly-ordered. release 는 stlr, acquire 는 ldar. 가벼운 fence.

Volatile 은 같은 의미에 더해 *모든 volatile 변수 사이의 SC* 까지 보장. ARM 에서 추가 dmb ish full fence.

## 7. Volatile vs Acquire/Release — 실제 차이

Volatile 보장: thread3 가 (1, 0) 을 보고 thread4 가 (1, 0) 을 보는 일은 *불가능*. 모두 SC.

같은 코드를 setRelease/getAcquire 로 바꾸면 (1, 0)/(1, 0) 이 *허용된다*. 두 스레드가 같은 변수에 대해 paired publish 하지 않은 *서로 다른 변수*들 사이의 ordering 은 정의되지 않기 때문.

이 차이는 대부분의 비즈니스 코드에서는 무관하다. 두 변수의 publish 순서가 의미 있는 알고리즘(ex: LMAX Disruptor 의 sequence + cursor) 에서만 문제. 그런 곳은 Volatile 로 가야 한다.

## 8. 실전 — lock-free Treiber stack

```java
public class TreiberStack<E> {
    private static class Node<E> {
        final E item;
        Node<E> next;
        Node(E item) { this.item = item; }
    }

    private volatile Node<E> head = null;
    private static final VarHandle HEAD;
    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(TreiberStack.class, "head", Node.class);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public void push(E item) {
        Node<E> node = new Node<>(item);
        Node<E> prev;
        do {
            prev = (Node<E>) HEAD.getAcquire(this);
            node.next = prev;
        } while (!HEAD.compareAndSet(this, prev, node));
    }

    public E pop() {
        Node<E> prev, next;
        do {
            prev = (Node<E>) HEAD.getAcquire(this);
            if (prev == null) return null;
            next = prev.next;
        } while (!HEAD.compareAndSet(this, prev, next));
        return prev.item;
    }
}
```

핵심 결정:
- HEAD getAcquire: read 가 다른 스레드의 마지막 release 까지의 모든 변경을 본다.
- compareAndSet: atomic CAS, 자체에 acquire+release 의미 포함.
- node.next 는 plain write 로 충분.

ABA 문제는 이 구현에 존재한다. 해결은 AtomicStampedReference 또는 versioned pointer (DCAS).

## 9. 진단 도구와 실험

**JCStress** (OpenJDK 산하): JMM 위반을 감지하는 microbenchmark 프레임워크.

**Aleksey Shipilev 의 jmm-cookbook**: VarHandle access mode 별 hardware fence 매핑을 정리한 표.

**JFR (Java Flight Recorder)**: jdk.JavaMonitorWait, jdk.ThreadPark event 로 lock-based 코드의 contention 과 비교 가능.

## 10. 사용 가이드

| 상황 | 권장 mode | 이유 |
| --- | --- | --- |
| 단일 스레드 라이브러리 내부 | Plain | 동기화 비용 0 |
| 통계 카운터 (대략적) | Opaque | atomicity + 코히런스 |
| 진행률 flag (publisher → subscriber) | Acquire/Release | paired publish |
| 두 변수 사이 SC 가 필요 (Disruptor) | Volatile | full fence |
| 정확한 카운터 / lock-free 자료구조 | CAS | atomic RMW |

규칙: 새 코드를 작성할 때는 가장 강한 모드(Volatile / CAS) 부터 시작해 정확성을 확보하고, profiling 이 contention 을 보여주면 약한 모드로 단계적으로 약화한다.

## 참고

- JEP 193 — Variable Handles: https://openjdk.org/jeps/193
- VarHandle Javadoc — Access modes: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html
- Java Memory Model (JLS Chapter 17): https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html
- Aleksey Shipilev — JMM Cookbook: https://shipilev.net/blog/2014/jmm-pragmatics/
- JCStress — Concurrency Stress Testing: https://github.com/openjdk/jcstress
