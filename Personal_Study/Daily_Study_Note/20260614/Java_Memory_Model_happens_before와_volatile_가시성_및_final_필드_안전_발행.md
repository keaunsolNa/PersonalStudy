Notion 원본: https://www.notion.so/37f5a06fd6d381678d61f8ca2827cead

# Java Memory Model happens-before와 volatile 가시성 및 final 필드 안전 발행

> 2026-06-14 신규 주제 · 확장 대상: JAVA / OS

## 학습 목표

- happens-before 관계를 program order, monitor, volatile, thread start/join 규칙으로 분해한다
- volatile 의 가시성·재배치 금지 효과와 atomicity 비보장 한계를 구분한다
- final 필드의 안전 발행(safe publication) 보증과 그 한계 조건을 설명한다
- double-checked locking 의 과거 결함과 volatile 로 고치는 원리를 코드로 검증한다

## 1. JMM 이 존재하는 이유

Java Memory Model(JMM, JSR-133)은 멀티스레드 프로그램에서 한 스레드의 쓰기가 다른 스레드에 언제 보이는지를 규정하는 추상 명세다. 실제 하드웨어에서는 CPU 가 store buffer 에 쓰기를 버퍼링하고, 컴파일러(JIT)는 데이터 의존성이 없는 명령을 재배치하며, 캐시 일관성 프로토콜이 동작하더라도 메모리 연산의 순서는 약속되지 않는다. JMM 은 이런 하드웨어·컴파일러의 자유를 인정하면서, 프로그래머가 의존할 수 있는 최소한의 순서 보장을 happens-before 라는 부분 순서(partial order)로 제공한다.

핵심 명제는 이것이다. 한 동작 A 가 다른 동작 B 에 happens-before 하면, A 의 메모리 효과가 B 에게 보이도록 보장된다. happens-before 관계가 없는 두 동작 사이에는 어떤 순서도 보장되지 않으며, 이것이 data race 의 정의가 된다.

## 2. happens-before 규칙들

happens-before 는 몇 가지 기본 규칙과 추이성(transitivity)으로 구성된다. 첫째 program order rule: 한 스레드 안에서 코드 순서상 앞선 동작은 뒤 동작에 happens-before 한다. 둘째 monitor lock rule: 한 모니터의 unlock 은 같은 모니터의 이후 lock 에 happens-before 한다. 셋째 volatile rule: volatile 변수에 대한 쓰기는 이후 그 변수를 읽는 동작에 happens-before 한다. 넷째 thread start rule: `Thread.start()` 는 시작된 스레드의 모든 동작에 happens-before 한다. 다섯째 thread join rule: 스레드의 모든 동작은 다른 스레드가 `join()` 으로부터 반환되는 시점에 happens-before 한다.

```java
class Flag {
    int data = 0;       // 일반 변수
    volatile boolean ready = false;

    void writer() {
        data = 42;       // (1)
        ready = true;    // (2) volatile write
    }

    void reader() {
        if (ready) {     // (3) volatile read
            assert data == 42; // (4) 항상 성립
        }
    }
}
```

(1)→(2) 는 program order, (2)→(3) 은 volatile rule, (3)→(4) 는 program order 다. 추이성에 의해 (1)→(4) 가 성립하므로, reader 가 `ready==true` 를 보면 `data==42` 도 반드시 본다. `ready` 의 volatile 이 일반 변수 `data` 의 가시성까지 끌어오는 이 효과가 piggyback 이다. 만약 `ready` 가 일반 boolean 이라면 (2)→(3) happens-before 가 없어 reader 가 `data==0` 을 볼 수 있다.

## 3. volatile 의 세 가지 효과와 한계

volatile 은 세 가지를 보장한다. 첫째 가시성: 쓴 값이 즉시 다른 스레드에 보인다(store buffer flush, 읽기 측 캐시 무효화에 해당). 둘째 재배치 금지: volatile 쓰기 앞의 일반 메모리 연산이 쓰기 뒤로, volatile 읽기 뒤의 연산이 읽기 앞으로 넘어가지 못한다(acquire/release 의미). 셋째 long/double 의 단일 연산성: 64비트 변수도 찢긴 읽기(word tearing) 없이 원자적으로 읽고 쓴다.

그러나 volatile 은 복합 연산의 원자성을 보장하지 않는다. `count++` 는 읽기-수정-쓰기 세 단계이므로 volatile 만으로는 경쟁 상태가 남는다.

```java
volatile int count = 0;
void increment() { count++; } // 여전히 race: read, +1, write 사이 끼어듦 가능
```

이 경우 `AtomicInteger.incrementAndGet()`(내부적으로 CAS 루프) 이나 `synchronized` 가 필요하다. 정리하면, "한 스레드만 쓰고 여러 스레드가 읽는 플래그/상태값"에는 volatile 이 정확히 맞고, "여러 스레드가 함께 갱신하는 카운터"에는 부족하다.

## 4. final 필드의 안전 발행

JSR-133 은 final 필드에 특별한 보증을 추가했다. 생성자가 정상 종료되기 전에 final 필드에 대입된 값은, 그 객체의 참조를 data race 로 발행하더라도 다른 스레드가 올바르게 본다. 즉 final 필드는 생성자 끝에 freeze 동작이 삽입되어, 객체 참조가 보이는 시점에는 final 필드 초기화도 함께 보이는 것이 보장된다.

```java
public final class ImmutablePoint {
    private final int x;
    private final int y;

    public ImmutablePoint(int x, int y) {
        this.x = x;
        this.y = y;
    } // 생성자 끝에 final freeze: x,y 초기화가 참조 발행에 선행 보장
}
```

여기에는 중요한 단서가 있다. 첫째, final 필드가 가리키는 객체의 내부 상태까지 불변임을 보장하지는 않는다. `final List<T> list` 가 있어도 리스트의 원소를 나중에 바꾸면 그 변경은 별도 동기화가 필요하다. 둘째, 생성자 안에서 `this` 참조가 밖으로 새어 나가면(예: 리스너 등록) freeze 보증이 깨진다. this escape 는 객체가 완전히 구성되기 전에 다른 스레드가 그 참조를 잡을 수 있게 만들기 때문이다.

## 5. double-checked locking 과 volatile

지연 초기화 싱글톤의 고전적 패턴인 double-checked locking 은 JSR-133 이전에 깨져 있었다. 핵심은 객체 생성이 "메모리 할당 → 생성자 실행 → 참조 대입" 세 단계인데, 컴파일러가 이를 "할당 → 참조 대입 → 생성자 실행"으로 재배치할 수 있다는 점이다. 그러면 다른 스레드가 null 이 아닌 참조를 보고 들어왔을 때 아직 생성자가 끝나지 않은 미완성 객체를 만질 수 있다.

```java
public class Holder {
    private static volatile Holder instance; // volatile 이 핵심

    public static Holder getInstance() {
        Holder result = instance;
        if (result == null) {
            synchronized (Holder.class) {
                result = instance;
                if (result == null) {
                    instance = result = new Holder();
                }
            }
        }
        return result;
    }
}
```

`instance` 를 volatile 로 선언하면 release 의미에 의해 생성자 완료가 참조 대입에 happens-before 하고, 읽는 쪽 acquire 의미에 의해 null 이 아닌 참조를 본 스레드는 완성된 객체를 본다. 다만 실무에서는 클래스 초기화의 happens-before 보증을 활용하는 lazy holder 패턴이 더 단순하고 명확하다.

```java
public class Singleton {
    private Singleton() {}
    private static class Holder { // 최초 getInstance 호출 시 클래스 로딩
        static final Singleton INSTANCE = new Singleton();
    }
    public static Singleton getInstance() { return Holder.INSTANCE; }
}
```

JVM 의 클래스 초기화는 락으로 보호되며 정확히 한 번 수행되고, 초기화 완료는 이후 그 클래스 사용에 happens-before 하므로 volatile 없이도 안전하면서 동기화 비용도 최초 1회뿐이다.

## 6. 안전 발행의 네 가지 관용구

객체를 여러 스레드가 공유할 때, 그 객체가 완전히 구성된 상태로 보이게 만드는 것을 안전 발행이라 한다. data race 로 발행하면 다른 스레드가 부분 초기화된 객체를 볼 수 있으므로, 다음 네 가지 중 하나를 써야 한다. 첫째 정적 초기화자에서 발행, 둘째 volatile/AtomicReference 에 저장, 셋째 final 필드로 보관, 넷째 lock 으로 보호되는 자료구조에 넣기다.

```java
private volatile Config config;
public void publish(Config c) { config = c; }   // c 의 모든 필드가 이후 reader 에 보임
public Config get() { return config; }

private final Map<String, Config> registry = new ConcurrentHashMap<>();
public void register(String k, Config c) { registry.put(k, c); }
// put 과 get 사이에 happens-before 가 성립 → c 의 구성 완료가 보장됨
```

`ConcurrentHashMap` 같은 java.util.concurrent 컬렉션은 키/값을 넣는 동작이 그것을 꺼내는 동작에 happens-before 함을 명세로 보장한다. 그래서 가변 객체라도 일단 넣은 뒤 그 안에서 변경하지 않으면, 꺼내는 스레드가 완성된 상태를 본다. 이것이 "효과적으로 불변(effectively immutable)" 객체를 공유하는 표준 방법이다.

## 7. 흔한 오해: synchronized 와 가시성

`synchronized` 가 상호 배제만 제공한다는 오해가 많지만, 실제로는 가시성도 함께 보장한다. monitor unlock 이 이후 같은 monitor 의 lock 에 happens-before 하므로, 임계 구역 안에서 변경한 일반 변수도 다음에 같은 락을 잡은 스레드에 보인다. 따라서 읽기와 쓰기가 모두 같은 락으로 보호되면 volatile 없이도 가시성이 성립한다.

```java
private int counter = 0; // volatile 아님
public synchronized void inc() { counter++; }       // 쓰기도 락
public synchronized int get() { return counter; }   // 읽기도 같은 락 → 가시성 보장
```

주의할 점은 읽기 쪽이 락을 잡지 않으면 가시성이 깨진다는 것이다. 한쪽만 `synchronized` 이고 다른 쪽이 평문 읽기면 happens-before 가 성립하지 않아 stale 값을 볼 수 있다. "쓰기만 동기화하면 충분하다"는 것은 흔한 버그의 원천이다. 락이든 volatile 이든, 읽기와 쓰기 양쪽 모두에 동기화 수단이 걸려 있어야 가시성이 성립한다는 것이 핵심 원칙이다.

## 8. 정리: 무엇을 언제 쓰는가

가시성만 필요하고 한 스레드만 쓰는 상태 플래그는 volatile, 여러 스레드가 함께 갱신하는 단순 수치는 Atomic 류, 복합 불변식을 지켜야 하는 다중 필드 갱신은 lock(synchronized/ReentrantLock), 생성 후 변하지 않는 객체는 final 기반 불변 객체로 발행하는 것이 기본 매핑이다. happens-before 가 없는 곳에 정합성을 기대하면 안 된다는 한 가지 원칙이 모든 판단의 출발점이다.

## 참고

- JSR-133: Java Memory Model and Thread Specification (final 필드, double-checked locking 분석)
- Brian Goetz et al., "Java Concurrency in Practice" — Ch.3 Sharing Objects, Ch.16 The Java Memory Model
- Doug Lea, "The JSR-133 Cookbook for Compiler Writers" (메모리 배리어 매핑)
- Aleksey Shipilëv, "Close Encounters of The Java Memory Model Kind"
