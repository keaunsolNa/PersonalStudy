Notion 원본: https://www.notion.so/34f5a06fd6d3816da786fef512cec68e

# Lock-Free 알고리즘과 CAS, ABA 문제, Hazard Pointer

> 2026-04-27 신규 주제 · 확장 대상: 자료구조&알고리즘 / JAVA(JMM)

## 학습 목표

- CAS(Compare-And-Swap) 의 하드웨어 보장과 ABA 문제 발생 조건을 정확히 이해한다
- Treiber stack, Michael-Scott queue 같은 대표 lock-free 자료구조의 동작을 직접 따라간다
- ABA 를 회피하는 두 패턴(태그 포인터, Hazard Pointer)을 비교하고 trade-off 를 정리한다
- Java 의 AtomicReference, AtomicStampedReference 가 어디까지 책임지는지 구분한다

## 1. Lock-Free 의 정의를 명확히

"lock-free" 는 종종 "wait-free" 와 혼용되지만 엄밀히 다른 진행성(progress) 보장이다.

- **wait-free**: 모든 스레드가 다른 스레드의 진행과 무관하게 bounded step 안에 자기 작업을 끝낸다. 가장 강한 보장
- **lock-free**: 어떤 스레드들의 무리가 동시에 실행되어도 그 중 적어도 하나는 진행한다. 한 스레드가 starve 될 수는 있지만 system-wide 진행이 보장된다
- **obstruction-free**: 한 스레드가 혼자 실행되면 끝낸다. 충돌 시 빙빙 돌 수 있다

대부분의 "lock-free 자료구조" 는 lock-free 보장이고, wait-free 는 구현이 복잡하고 성능이 떨어져 실무에서 드물다.

lock-free 의 가치는 다음과 같다. 첫째, 한 스레드의 실패(crash, suspend, GC pause)가 다른 스레드를 막지 않는다. 둘째, OS 의 mutex 보다 contention 시 latency 분포가 좁다. 셋째, priority inversion 같은 mutex 특유의 문제를 피한다.

대가는 코드 복잡도와 ABA 같은 미묘한 버그 가능성이다.

## 2. CAS 의 하드웨어 보장

CAS 는 다음 의사코드를 atomic 하게 수행한다:

```
boolean CAS(addr, expected, new):
    if *addr == expected:
        *addr = new
        return true
    else:
        return false
```

x86 의 `LOCK CMPXCHG`, ARM 의 `LDREX/STREX` pair 가 이 연산을 제공한다. CAS 가 atomic 하다는 것은 다음을 의미한다:

- 다른 코어가 같은 주소를 동시에 쓰는 것을 차단(cache coherency 가 보장)
- memory barrier 의 역할까지 한다(x86 의 LOCK prefix 는 full fence 효과)

C++ std::atomic, Java AtomicReference, Rust std::sync::atomic 의 compare_exchange 는 모두 이 명령을 추상화한다.

```java
AtomicReference<Node> head = new AtomicReference<>(null);

// CAS 사용 예
Node oldHead = head.get();
Node newHead = new Node(value, oldHead);
boolean success = head.compareAndSet(oldHead, newHead);
```

CAS 의 핵심은 "expected 와 메모리 값이 같다는 것 = 그 사이에 아무 변경이 없었다" 는 가정이다. 이 가정이 깨지는 것이 ABA 다.

## 3. ABA 문제 — 정확히 어디서 발생하는가

ABA 는 다음 시나리오다:

```
T1: head = A
T1: oldHead = head; (oldHead = A)
T1: ... preempted ...

T2: head 에서 A 를 pop, 다른 것 push, A 가 free 되었다가 메모리 재할당으로 다시 A 주소로 새 노드가 생성됨
T2: head = A (다른 의미의 A)

T1: resume. CAS(head, A, ...) 성공!
    → 그러나 A 의 next 가 이전 T1 시점과 다름 → 자료구조 깨짐
```

이 문제는 "포인터 동등성 = 값 동등성" 의 가정이 깨질 때 발생한다. JVM 의 garbage collector 환경에서는 같은 객체 주소가 reclaim 되어 재사용되는 경우가 드물어 ABA 발생 빈도가 낮지만(살아있는 참조가 있으면 GC 가 회수 안 함), C/C++ 의 raw pointer 환경에서는 흔하다.

JVM 에서도 ABA 가 발생하는 경우가 있다.

첫째, 카운터 값이 ABA: 정수 값을 CAS 로 update 하는데 A→B→A 로 돌아오면 의미가 달라진 경우. 예를 들어 "마지막으로 본 version 이 7 이었는데 5번 변경 후 다시 7" 인 시나리오.

둘째, 객체 풀링: pool 에서 reuse 되는 객체 인스턴스를 CAS 로 다루면, 같은 인스턴스가 다른 의미로 다시 등장할 수 있다.

## 4. Treiber Stack — 단순한 lock-free 구현

가장 간단한 lock-free 자료구조는 Treiber stack 이다. push 와 pop 모두 head 만 CAS 한다.

```java
public class TreiberStack<T> {
    private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

    private static class Node<T> {
        final T value;
        final Node<T> next;
        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    public void push(T value) {
        Node<T> oldHead, newHead;
        do {
            oldHead = head.get();
            newHead = new Node<>(value, oldHead);
        } while (!head.compareAndSet(oldHead, newHead));
    }

    public T pop() {
        Node<T> oldHead, newHead;
        do {
            oldHead = head.get();
            if (oldHead == null) return null;
            newHead = oldHead.next;
        } while (!head.compareAndSet(oldHead, newHead));
        return oldHead.value;
    }
}
```

push 는 ABA 의 영향을 거의 받지 않는다. 새로 만든 Node 가 expected 와 일치할 일이 없기 때문이다. pop 은 ABA 의 위험이 있다 — pop 후 다시 같은 Node 인스턴스가 재사용되면 문제가 된다. JVM 환경에서 oldHead 가 살아있으면 GC 가 회수하지 않으므로 같은 Node 가 다시 push 되는 일은 없다(개발자가 직접 다시 push 하지 않는 한). 따라서 GC 환경의 Treiber stack 은 일반적으로 ABA-safe.

C++ 환경에서는 raw pointer 를 다시 deallocate / reallocate 하면 같은 주소가 재등장할 수 있어 ABA 가 실제 문제가 된다.

## 5. Michael-Scott Queue — head/tail 두 포인터

FIFO 큐의 lock-free 구현인 Michael-Scott queue 는 head 와 tail 두 포인터를 별도로 관리한다.

```java
public class MichaelScottQueue<T> {
    private final AtomicReference<Node<T>> head;
    private final AtomicReference<Node<T>> tail;

    private static class Node<T> {
        final T value;
        final AtomicReference<Node<T>> next = new AtomicReference<>(null);
        Node(T value) { this.value = value; }
    }

    public MichaelScottQueue() {
        Node<T> dummy = new Node<>(null);
        head = new AtomicReference<>(dummy);
        tail = new AtomicReference<>(dummy);
    }

    public void enqueue(T value) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> last = tail.get();
            Node<T> next = last.next.get();
            if (last == tail.get()) {                          // 일관성 재확인
                if (next == null) {
                    if (last.next.compareAndSet(null, newNode)) {
                        tail.compareAndSet(last, newNode);
                        return;
                    }
                } else {
                    tail.compareAndSet(last, next);            // 누가 진행 못한 tail 을 도와주기
                }
            }
        }
    }

    public T dequeue() {
        while (true) {
            Node<T> first = head.get();
            Node<T> last = tail.get();
            Node<T> next = first.next.get();
            if (first == head.get()) {
                if (first == last) {
                    if (next == null) return null;             // 빈 큐
                    tail.compareAndSet(last, next);            // 도움 단계
                } else {
                    T value = next.value;
                    if (head.compareAndSet(first, next)) {
                        return value;
                    }
                }
            }
        }
    }
}
```

이 구현의 핵심 idea 두 가지:

첫째, **dummy node**. head 와 tail 이 같은 dummy 를 가리킨다. 빈 큐도 항상 head/tail 이 valid 한 노드를 가리킨다.

둘째, **helping**. enqueue 가 last.next 만 set 하고 tail update 전에 죽으면 다음 enqueue 가 그 작업을 마무리해 준다. lock-free 보장은 이런 helping 으로 만들어진다.

dequeue 에서도 dummy node 이전 위치를 head 가 가리키고 있어, dequeue 시 head 를 next 로 옮기고 새 head 가 가리키는 노드의 value 를 반환한다.

ABA 위험은 enqueue 의 `last.next.compareAndSet(null, newNode)` 에서 last 가 free 됐다가 같은 주소로 재할당된 경우다. JVM 에서는 GC 덕분에 reclaim 이 늦어 거의 발생하지 않지만, C/C++ 에서는 명시적 해제 시 ABA 보호 메커니즘이 필요하다.

## 6. ABA 회피 — 태그 포인터 패턴

ABA 를 회피하는 가장 단순한 방법은 포인터에 단조 증가 카운터를 붙여서 비교하는 것이다.

```java
public class TaggedStack<T> {
    private final AtomicStampedReference<Node<T>> head =
        new AtomicStampedReference<>(null, 0);

    public void push(T value) {
        int[] stampHolder = new int[1];
        Node<T> oldHead, newHead;
        int oldStamp;
        do {
            oldHead = head.get(stampHolder);
            oldStamp = stampHolder[0];
            newHead = new Node<>(value, oldHead);
        } while (!head.compareAndSet(oldHead, newHead, oldStamp, oldStamp + 1));
    }
    // ...
}
```

`AtomicStampedReference` 는 (reference, stamp) pair 를 atomic 하게 다룬다. CAS 시 reference 와 stamp 를 모두 비교한다. A→B→A 로 돌아와도 stamp 가 증가했으므로 CAS 가 실패한다.

이 방식의 단점은 stamp 가 32-bit 정수일 때 overflow 가 발생할 수 있다는 점이다. 이론적으로는 wraparound 후 같은 stamp 가 다시 등장하면 ABA 가 재발할 수 있다. 32-bit 면 1초당 1억 번 update 해도 약 43초 만에 wraparound 한다. 64-bit pointer + 64-bit counter = 128-bit DWCAS(double-width CAS)가 안전하지만 모든 아키텍처가 지원하지는 않는다.

## 7. Hazard Pointer 패턴

Hazard Pointer 는 lock-free 자료구조의 메모리 reclamation 문제를 해결하는 정교한 패턴이다. C/C++ 에서 GC 가 없을 때 lock-free 자료구조의 unlink 된 노드를 안전하게 free 하기 위해 만들어졌다.

핵심 아이디어:

1. 각 스레드는 N 개의 hazard pointer slot 을 갖는다(보통 2개)
2. 스레드가 어떤 노드 P 에 접근하려면 먼저 자기 hazard pointer 에 P 를 publish 한다
3. 다른 스레드가 P 를 free 하려면, 모든 스레드의 hazard pointer 를 검사해서 누구도 P 를 가리키지 않는지 확인한 후 free
4. 가리키는 자가 있으면 retire list 에 보류했다가 나중에 재시도

이는 epoch-based reclamation 이나 RCU 와 함께 lock-free 자료구조의 핵심 인프라다.

```cpp
// 의사코드
void* HazardPointer::protect(std::atomic<void*>& src) {
    void* p;
    do {
        p = src.load();
        slot.store(p);                    // hazard pointer publish
    } while (p != src.load());            // 그 사이 변경이 있었는지 재검증
    return p;
}

void retire(void* p) {
    retire_list.push(p);
    if (retire_list.size() > threshold) {
        scan_and_free();                  // 모든 hazard pointer 와 비교해 free
    }
}
```

JVM 환경에서는 GC 가 이 역할을 대신한다. 살아있는 참조가 있으면 회수 안 한다는 GC 의 보장이 hazard pointer 와 동등한 효과를 만든다. 그래서 Java 의 lock-free 자료구조는 일반적으로 hazard pointer 를 명시적으로 구현하지 않는다.

C++20 이후 표준 라이브러리 제안에 hazard_pointer 가 포함됐다. Folly, JUnit C++ 같은 라이브러리도 자체 구현을 제공한다.

## 8. Java 의 atomic 클래스 — 어디까지 보장하는가

Java 의 java.util.concurrent.atomic 패키지는 다음을 제공한다:

| 클래스 | 보장 |
| --- | --- |
| AtomicReference<V> | 단일 reference 의 CAS |
| AtomicStampedReference<V> | (reference, int stamp) pair CAS — ABA 회피 |
| AtomicMarkableReference<V> | (reference, boolean mark) pair CAS — link-mark |
| AtomicIntegerFieldUpdater | volatile int 필드를 외부에서 CAS |
| LongAdder, LongAccumulator | striping 으로 contention 분산. 절대값 일관성 약함 |
| VarHandle | low-level memory ordering(acquire/release/relaxed) |

JDK 9+ 의 VarHandle 은 C++ 의 std::atomic 처럼 memory order 를 명시적으로 제어할 수 있다. acquire/release semantic 으로 full fence 보다 비용이 낮은 ordering 을 선택할 수 있다.

```java
public class Counter {
    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup()
                .findVarHandle(Counter.class, "value", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile long value;

    public long incrementAcquireRelease() {
        long current, next;
        do {
            current = (long) VALUE.getAcquire(this);
            next = current + 1;
        } while (!VALUE.compareAndSet(this, current, next));
        return next;
    }
}
```

acquire/release 는 sequential consistency 보다 약하지만 happens-before 관계를 만들기에는 충분하다. x86 에서는 acquire/release 가 거의 무료에 가깝지만, ARM 에서는 명시적 명령(LDAR/STLR)으로 mapping 되어 약간의 비용이 있다.

## 9. 실무에서의 선택 기준

lock-free 는 항상 옳지 않다. 다음을 고려해서 선택한다.

**lock 이 적합한 경우**:
- 임계 구역이 길거나 복잡한 작업(여러 데이터 구조 동시 변경)
- contention 이 낮음(같은 lock 을 동시에 노리는 스레드가 거의 없음)
- 코드 명확성이 우선

**lock-free 가 적합한 경우**:
- 임계 구역이 매우 짧음(몇 줄)
- contention 이 높음
- latency 분포의 tail 을 줄이는 것이 중요함
- priority inversion / GC pause 같은 lock 의 부작용을 피해야 함

벤치마크 예시(8-core, 4 스레드 push/pop):

| 자료구조 | 평균 throughput | P99 latency |
| --- | --- | --- |
| ConcurrentLinkedQueue (lock-free MS queue) | 12.4M ops/s | 110 ns |
| LinkedBlockingQueue (lock-based) | 4.1M ops/s | 380 ns |
| ArrayBlockingQueue (lock-based) | 6.8M ops/s | 220 ns |
| ConcurrentLinkedDeque (lock-free) | 9.7M ops/s | 140 ns |

contention 이 낮을 때(스레드 1개)는 ArrayBlockingQueue 가 cache locality 로 더 빠를 수도 있다. 항상 측정 후 선택한다.

## 참고

- M. Herlihy, N. Shavit, "The Art of Multiprocessor Programming" 2판 — lock-free 자료구조 정석
- M. Michael, "Hazard Pointers: Safe Memory Reclamation for Lock-Free Objects": https://www.research.ibm.com/people/m/michael/ieeetpds-2004.pdf
- Brian Goetz, "Java Concurrency in Practice" 15장 — Atomic Variables and Nonblocking Synchronization
- Doug Lea, "java.util.concurrent.atomic" 설계 노트: https://gee.cs.oswego.edu/dl/papers/aer.pdf
- C++ memory_order 공식 cppreference: https://en.cppreference.com/w/cpp/atomic/memory_order
