Notion 원본: https://www.notion.so/34a5a06fd6d381d78157d8598672c1b8

# Java Memory Model(JMM)과 happens-before 완전 정복

> 2026-04-22 신규 주제 · 확장 대상: JAVA (문법 중심 → 멀티스레드 기초 보완)

## 학습 목표

- CPU 캐시와 JIT의 Reordering이 어떻게 멀티스레드 버그를 만드는지 하드웨어 수준에서 설명한다
- happens-before(HB)가 **정의하는 것과 강제하지 않는 것**을 구분하고, JLS Chapter 17의 여섯 가지 HB 규칙을 예시로 재현한다
- `volatile`·`synchronized`·`final`이 만들어내는 메모리 배리어를 바이트코드/JIT 레벨로 확인한다
- `jcstress`로 visibility 실패를 재현하고, `AtomicInteger`·`LongAdder`·`VarHandle`의 시맨틱 차이를 벤치마크한다

---

## 1. 왜 JMM이 필요한가 — CPU가 Java만 생각하지 않는다

멀티코어 CPU에서 각 코어는 자기 **L1/L2 캐시**와 **Store Buffer**를 가진다. 코어 1이 쓴 값은 즉시 메인 메모리로 가지 않고 자기 Store Buffer에 머문다. 코어 2가 메인 메모리에서 같은 주소를 읽으면 **옛 값**이 보일 수 있다. 이것이 visibility 문제의 하드웨어 원인이다.

더 나아가 컴파일러(javac)와 JIT(HotSpot C2)는 **프로그램 순서를 바꾼다**. 예를 들어 다음 코드를:

```java
int a = 1;   // (1)
int b = 2;   // (2)
```

CPU 관점에서는 (1)과 (2) 사이에 의존성이 없으므로 실행 순서를 바꿔도 **단일 스레드 관점에서는** 결과가 같다. 이를 Sequentially Consistent 가정하에서의 "as-if-serial"이라고 한다. 문제는 **다른 스레드가 이 두 변수를 관찰하면** 순서가 뒤바뀌어 보인다.

JMM(Java Memory Model, JLS Chapter 17)은 이 두 층(하드웨어와 컴파일러)을 **추상화**해서 개발자가 "어떤 조건에서 어떤 값이 보장되는가"를 프로그래밍 언어 수준으로 규정한다. 핵심 도구가 **happens-before 관계**다.

유명한 Double-checked Locking 버그가 왜 깨지는지 보자.

```java
class Holder {
    private static Singleton instance;
    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Holder.class) {
                if (instance == null) {
                    instance = new Singleton();  // ①
                }
            }
        }
        return instance;
    }
}
```

① 한 줄은 실제로는 세 단계다: (a) 메모리 할당, (b) 생성자 실행, (c) `instance` 필드에 참조 대입. JIT는 (b)와 (c)를 재배치할 수 있다. 그러면 다른 스레드가 `if (instance == null)`에서 **참조는 있지만 생성자가 아직 안 끝난 객체**를 읽어 NPE나 부분 초기화 상태를 만난다. 해결은 `instance`에 `volatile`을 붙이는 것이다. 이유는 다음 섹션에서.

## 2. happens-before의 여섯 가지 규칙

JMM은 다음 규칙들로 HB 관계를 정의한다. 두 연산 A와 B가 A hb B라면 **A의 효과가 B에서 관찰된다**는 뜻이다.

1. **프로그램 순서 규칙**: 한 스레드 내에서 앞선 문장은 뒤에 오는 문장에 대해 HB.
2. **모니터 락 규칙**: `synchronized` 블록의 unlock이 이후 같은 모니터의 lock에 대해 HB.
3. **volatile 변수 규칙**: volatile 필드 write가 이후의 volatile read에 대해 HB.
4. **스레드 시작 규칙**: `Thread.start()` 호출이 시작된 스레드의 모든 동작에 대해 HB.
5. **스레드 종료 규칙**: 스레드의 모든 동작이 `Thread.join()` 반환에 대해 HB.
6. **인터럽트 규칙**: `Thread.interrupt()` 호출이 대상 스레드의 `isInterrupted()` 감지에 대해 HB.

HB는 **추이적(transitive)** 이다. A hb B이고 B hb C이면 A hb C다. 이것 덕분에 volatile 하나만으로도 그 주변 **모든 평범한 변수까지** 순서가 고정된다. 이게 Double-checked Locking을 `volatile`로 고칠 수 있는 이유다.

```java
private static volatile Singleton instance;
```

`instance` 필드에 대한 volatile write는 그 이전의 모든 평범한 write(생성자의 필드 초기화)에 대해 HB 관계를 만든다. 다른 스레드가 volatile read로 null이 아닌 값을 보면, 그 스레드는 생성자가 완료된 상태를 반드시 본다.

## 3. volatile의 실제 구현 — 메모리 배리어

JMM 스펙은 "HB 관계를 만들어라"만 말하지만, JVM은 이를 **메모리 배리어(memory barrier)** 명령으로 구현한다. 배리어는 네 종류다.

- **LoadLoad**: 이 배리어 앞의 read가 뒤의 read보다 먼저 일어나도록 보장
- **StoreStore**: 이 배리어 앞의 write가 뒤의 write보다 먼저 메모리에 반영
- **LoadStore**: read가 뒤의 write보다 먼저
- **StoreLoad**: write가 뒤의 read보다 먼저 (가장 비쌈)

volatile write는 `[StoreStore] write [StoreLoad]`로, volatile read는 `[LoadLoad + LoadStore] read`로 감싸진다. 특히 StoreLoad 배리어는 x86에서 `mfence` 또는 `lock` prefix 명령으로 구현되고, ARM에서는 `dmb ish`로 구현된다. x86은 Total Store Order(TSO) 메모리 모델이라 StoreLoad만 명시적으로 필요하고, ARM은 더 약한 모델이라 모든 배리어가 비용이 크다.

HotSpot에서 `-XX:+PrintAssembly`로 JIT 결과를 뽑으면 volatile write 뒤에 `lock addl $0x0,(%rsp)` 같은 명령이 박힌 걸 실제로 볼 수 있다. 이 한 명령이 약 30~50 clock cycle을 쓴다. 평범한 write보다 10배 정도 느리다.

## 4. synchronized와 Object Monitor

`synchronized`는 `monitorenter`/`monitorexit` 바이트코드로 컴파일되고, 각 객체 헤더의 **mark word**(64-bit 워드)가 락 상태를 저장한다. mark word는 상황에 따라 여러 포맷으로 변신한다.

| 상태 | mark word 내용 |
|---|---|
| Unlocked | `hashcode : age : 01` |
| Biased (JDK 15에서 제거) | `thread_id : epoch : age : 101` |
| Lightweight (Thin) lock | `ptr_to_lock_record : 00` |
| Heavyweight (Fat) lock | `ptr_to_monitor : 10` |
| GC mark | `11` |

경쟁이 없으면 Thin Lock으로 CAS 한 번으로 끝난다. 경쟁이 생기면 OS 커널의 `futex`/Windows CriticalSection으로 승격(Fat Lock)해서 스레드를 blocking한다. Biased Locking은 "한 스레드만 쓰는 경우가 대부분"이라는 가정으로 CAS조차 생략했지만, JDK 15(JEP 374)에서 제거됐다. 현대 워크로드에서 이점이 적고 JVM 내부 복잡도를 높였기 때문.

`ReentrantLock`은 `synchronized`와 달리 **interruptible lock**, **tryLock**, **Condition 여러 개**를 지원한다. 대신 try/finally로 unlock을 명시해야 한다. JDK 9 이후로는 성능 차이가 거의 없으니 기능이 필요하면 `ReentrantLock`, 그렇지 않으면 `synchronized`가 코드가 더 깔끔하다.

## 5. final 필드의 안전 공개(Safe Publication)

JMM은 **final 필드에 특별 대우**를 한다. 생성자에서 final 필드에 값이 대입되고 생성자가 끝난 시점에, 그 참조가 다른 스레드에 공개되면 **그 스레드는 final 필드의 초기화된 값을 반드시 본다**. 이게 불변 객체(`String`, `Integer`, record)가 스레드 안전한 근거다.

```java
class Point {
    final int x;
    final int y;
    Point(int x, int y) { this.x = x; this.y = y; }
}
// 다른 스레드: 참조를 받으면 x, y는 항상 생성자의 값
```

주의할 점은 **final 참조가 가리키는 객체의 내부는 보호되지 않는다는 것**. `final List<String> items = new ArrayList<>();`의 `items` 참조는 final이지만 ArrayList 자체는 스레드 안전하지 않다.

`String`의 `hashCode()`는 이 안전성 위에 **lazy init + non-final cache** 패턴을 쓴다.

```java
public final class String {
    private int hash;  // 처음엔 0, 계산 후 캐시
    public int hashCode() {
        int h = hash;
        if (h == 0 && !hashIsZero && value.length > 0) {
            h = computeHash();
            hash = h;  // race condition 가능, but 결과가 같음
            hashIsZero = (h == 0);
        }
        return h;
    }
}
```

여러 스레드가 동시에 `computeHash`를 중복 계산할 수는 있지만, 결과가 같아서 **race가 무해**(benign race)하다. 이런 최적화는 "계산이 결정적이고 결과 일치"가 보장될 때만 쓸 수 있다.

## 6. Atomic과 CAS

`AtomicInteger.compareAndSet(expected, update)`는 내부적으로 `Unsafe.compareAndSwapInt` → CPU의 `cmpxchg` 명령으로 원자적 교환을 수행한다. CAS는 **full barrier** 시맨틱을 가지므로 volatile write와 동등한 visibility를 제공한다.

한계도 있다. 경쟁이 심한 카운터에서 `AtomicInteger.incrementAndGet()`을 동시에 호출하면 CAS 실패와 재시도가 반복되며 처리량이 급락한다. JDK 8의 `LongAdder`는 이를 해결하려고 **내부적으로 여러 셀(Cell)에 분산 누적**하고 `sum()` 호출 시 합친다. 경쟁이 없을 때는 `AtomicLong`과 비슷하지만 경쟁이 심해지면 5~10배 빠르다.

| 케이스 | AtomicLong | LongAdder |
|---|---|---|
| 단일 스레드 incr | ~20 ns/op | ~25 ns/op |
| 16 threads 경쟁 incr | ~800 ns/op | ~60 ns/op |
| sum() 호출 | O(1) | O(n cells) |

정리: **값을 자주 읽는 카운터는 AtomicLong, 자주 더하고 가끔 읽는 카운터는 LongAdder**.

**ABA 문제**도 알아둬야 한다. 값이 A→B→A로 돌아오면 CAS는 "변하지 않았다"고 판단해버린다. Lock-free 스택/큐 같은 구조에서 문제가 되는데, `AtomicStampedReference`로 버전을 함께 관리하거나 세대 카운터를 붙여 해결한다.

## 7. VarHandle — 세밀한 메모리 시맨틱 제어

JDK 9의 `VarHandle`은 `sun.misc.Unsafe`의 합법적 대체재다. `plain`/`opaque`/`release`/`acquire`/`volatile` 다섯 가지 시맨틱을 필드별로 선택할 수 있다.

```java
class Node {
    volatile Node next;  // 기존 방식

    // VarHandle 방식
    private static final VarHandle NEXT;
    static {
        try {
            NEXT = MethodHandles.lookup().findVarHandle(Node.class, "next", Node.class);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    void setRelease(Node n) { NEXT.setRelease(this, n); }  // StoreStore + StoreLoad 생략
    Node getAcquire()       { return (Node) NEXT.getAcquire(this); }
}
```

`setRelease`는 x86에서 사실상 평범한 write와 같은 비용이지만, 이 write가 **이전의 모든 write보다 먼저 보이지 않도록** 보장한다. volatile write보다 StoreLoad 배리어가 빠져서 가볍다. LMAX Disruptor 같은 고성능 큐는 VarHandle의 release/acquire로 volatile 대비 수 배 처리량을 낸다.

일반적인 비즈니스 코드에서 VarHandle까지 쓸 일은 드물다. 단 **커스텀 lock-free 자료구조**를 만들거나 기존 코드의 volatile 오버헤드를 줄이는 마이크로 최적화를 해야 할 때 필요하다.

## 8. 실전 버그 패턴과 재현

멀티스레드 버그는 재현이 어려워서 디버깅 경험이 쌓이지 않으면 감지하기 힘들다. 대표적인 패턴 세 가지.

**루프 탈출 실패**. 비-volatile 플래그로 종료 조건을 돌리면 JIT가 "이 필드는 변하지 않는다"고 최적화해 무한 루프에 빠진다.

```java
private boolean running = true;  // ❌ volatile 없음
public void stop() { running = false; }
public void run() { while (running) { /* ... */ } }  // 영원히 안 멈춤
```

**HashMap의 put race**. JDK 7까지 HashMap을 멀티스레드에서 put하면 내부 링크드 리스트가 **원형 링크**를 만들어 get이 무한 루프에 빠졌다. JDK 8에서 트리 구조로 바뀌며 무한 루프는 사라졌지만 데이터 손실은 여전히 발생한다. 스레드 공유라면 반드시 `ConcurrentHashMap`.

**Lazy init double-execution**. CDL에 volatile이 빠지면 두 스레드가 각각 다른 인스턴스를 만들 수 있다. `LazyInitializationHolder` 패턴(static inner class)이 가장 안전하고 빠르다.

```java
public class Singleton {
    private Singleton() {}
    private static class Holder { static final Singleton INSTANCE = new Singleton(); }
    public static Singleton get() { return Holder.INSTANCE; }
}
```

JVM의 클래스 로딩 자체가 thread-safe하므로 추가 동기화가 필요 없다.

## 9. jcstress — 동시성 버그 재현 도구

`jcstress`(Java Concurrency Stress tests)는 OpenJDK가 공식 배포하는 harness다. 의심 케이스를 수백만 번 반복 실행해서 발생하는 결과 분포를 통계로 보여준다.

```java
@JCStressTest
@State
@Outcome(id = "1, 1", expect = ACCEPTABLE,            desc = "Both see updates")
@Outcome(id = "0, 1", expect = ACCEPTABLE_INTERESTING, desc = "Re-ordered visible")
@Outcome(id = "1, 0", expect = ACCEPTABLE_INTERESTING, desc = "Re-ordered visible")
@Outcome(id = "0, 0", expect = FORBIDDEN,             desc = "Should never happen")
public class VolatileTest {
    volatile int x, y;
    @Actor public void actor1(IntResult2 r) { x = 1; r.r1 = y; }
    @Actor public void actor2(IntResult2 r) { y = 1; r.r2 = x; }
}
```

x86에서도 `0, 0` 결과가 수천 번에 한 번 나온다(StoreLoad 배리어 없이는 허용됨). volatile로 바꾸면 0, 0이 사라진다. 이론을 **실제로 관찰**하고 나면 volatile 비용이 왜 정당한지 체감된다.

JMH(`org.openjdk.jmh`)로 벤치마크할 때는 `@Fork`, `@Warmup`, `@Measurement`를 꼭 설정하고 **Blackhole.consume()** 으로 DCE(Dead Code Elimination)를 막아야 한다. 그냥 `System.nanoTime()` 차이로 측정하면 숫자가 신뢰할 수 없다.

---

## 참고

- 기학습 연계: [JAVA](./JAVA.md), [JVM 메모리 모델과 GC 튜닝 실전](./JVM_메모리_모델과_GC_튜닝_실전.md)
- [JLS §17 Threads and Locks](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html)
- Brian Goetz, *Java Concurrency in Practice* (JCiP) — 이 책을 안 읽었으면 지금 당장 읽어야 할 책
- Doug Lea, *The JSR-133 Cookbook for Compiler Writers* — 배리어 구현 디테일
- [jcstress samples](https://github.com/openjdk/jcstress)
- [JEP 374 — Disable and Deprecate Biased Locking](https://openjdk.org/jeps/374)
