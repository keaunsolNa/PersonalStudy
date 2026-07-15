Notion 원본: https://app.notion.com/p/39e5a06fd6d381c4afc0eb2d5aa7f37e

# CPU 캐시 일관성 MESI와 False Sharing 및 메모리 배리어

> 2026-07-15 신규 주제 · 확장 대상: OS / 자료구조&알고리즘 / JAVA(동시성)

## 학습 목표

- 캐시 라인 단위 소유권으로 MESI 상태 전이와 무효화 트래픽을 추적한다
- Store Buffer 와 Invalidate Queue 가 프로그램 순서를 깨뜨리는 과정을 재현한다
- False Sharing 을 패딩·`@Contended` 로 제거하고 효과를 수치로 확인한다
- x86 TSO 와 ARM 약한 순서 모델의 차이를 배리어 명령 수준에서 구분한다

## 1. 캐시 라인이 동시성의 최소 단위다

메모리 접근은 바이트 단위가 아니라 **캐시 라인** 단위다. 현대 x86/ARM 은 64바이트(Apple Silicon 은 128바이트)다. `int` 하나를 읽어도 그 주소를 포함한 64바이트가 통째로 L1 에 올라온다.

```
L1d:  32~48KB,  ~4 cycle   (~1ns)
L2:   1~2MB,    ~14 cycle  (~4ns)
L3:   8~64MB,   ~40 cycle  (~15ns, 코어 간 공유)
DRAM:           ~200 cycle (~70ns)
원격 NUMA 노드:  ~300 cycle (~110ns)
```

L1 과 DRAM 은 50배 차이다. 그래서 성능 문제는 대개 "연산이 느리다" 가 아니라 "캐시를 놓쳤다" 이다.

여기에 멀티코어가 얹힐면 문제가 생긴다. 코어 4개가 각자 L1 을 갖고 같은 주소를 캐싱하면, 하나가 쓰기를 했을 때 나머지 3개의 사본이 낡은 값이 된다. 이 문제를 하드웨어가 자동으로 푸는 프로토콜이 **캐시 일관성(coherence)** 이며, 대표가 MESI 다.

## 2. MESI 상태와 전이

각 캐시 라인은 4개 상태 중 하나다.

| 상태 | 의미 | 다른 코어의 사본 | 메모리와 일치 |
|---|---|---|---|
| **M** (Modified) | 나만 갖고 있고 내가 수정함 | 없음 | 불일치(dirty) |
| **E** (Exclusive) | 나만 갖고 있고 수정 안 함 | 없음 | 일치 |
| **S** (Shared) | 여럿이 읽기용으로 가짐 | 있을 수 있음 | 일치 |
| **I** (Invalid) | 무효. 쓸 수 없음 | — | — |

핵심 규칙은 두 가지다. **쓰기를 하려면 반드시 M 또는 E 여야 한다.** 그리고 **M/E 는 배타적이다** — 다른 어떤 코어도 그 라인의 유효 사본을 가질 수 없다.

시나리오를 따라가 보자. 코어 0 과 1 이 같은 변수 `x` 를 쓴다.

```
1. 코어0: x 읽기 → 아무도 안 가짐 → 메모리에서 로드 → E
2. 코어1: x 읽기 → 코어0 이 E 로 가짐 → 스누프 → 둘 다 S
3. 코어0: x 쓰기 → S 로는 못 씁
        → RFO(Read For Ownership) 브로드캐스트
        → 코어1 이 자기 사본을 I 로 무효화하고 ACK
        → 코어0 이 M 으로 전이 후 쓰기
4. 코어1: x 읽기 → 자기 사본은 I → 스누프
        → 코어0 이 M 라인을 캐시 간 전송(cache-to-cache)
        → 둘 다 S (또는 MESIF/MOESI 에서는 F/O)
```

3번의 **RFO** 가 비용의 핵심이다. 쓰기 하나가 다른 모든 코어에 무효화 메시지를 보내고 ACK 를 기다린다. 코어 수에 비례해 트래픽이 증가하고, 소켓을 넘으면(NUMA) 인터커넥트를 타 지연이 배가된다.

두 코어가 같은 라인에 번갈아 쓰면 라인이 M ↔ I 를 계속 오간다. 이것이 **캐시 라인 핑퍼핑**이고, 실측상 L1 히트(4 cycle) 대비 100~400 cycle 로 뛴다. 100배 느려지는 것이다.

실제 프로토콜은 확장형이다. Intel 은 MESIF(Forward 상태 — S 사본 중 하나만 응답 책임), AMD 는 MOESI(Owned 상태 — dirty 인 채로 공유 가능)를 쓴다. 목적은 동일하게 브로드캐스트 트래픽 절감이다.

## 3. False Sharing — 공유하지 않는데 공유하는 것

MESI 는 **라인 단위**로 동작한다. 논리적으로 무관한 두 변수가 같은 64바이트 라인에 들어 있으면, 하드웨어는 그것을 구분하지 못한다.

```java
// ❌ counterA 와 counterB 가 같은 캐시 라인에 있을 가능성이 높음
class Counters {
    volatile long counterA;   // offset 16
    volatile long counterB;   // offset 24  ← 같은 64B 라인
}
```

코어 0 이 `counterA` 만, 코어 1 이 `counterB` 만 증가시켜도 **서로의 캐시를 계속 무효화한다.** 데이터를 공유하지 않는데 라인을 공유해서 생기는 경합이라 False Sharing 이라 부른다.

측정 가능한 벤치마크:

```java
@State(Scope.Benchmark)
public class FalseSharingBench {

    static class Naive { volatile long a, b; }

    static class Padded {
        volatile long a;
        long p1, p2, p3, p4, p5, p6, p7;   // 56B 패딩 → a 와 b 를 다른 라인으로
        volatile long b;
        long p8, p9, p10, p11, p12, p13, p14;
    }

    final Naive naive = new Naive();
    final Padded padded = new Padded();

    @Benchmark @Group("naive") @GroupThreads(1)
    public void naiveA() { naive.a++; }
    @Benchmark @Group("naive") @GroupThreads(1)
    public void naiveB() { naive.b++; }

    @Benchmark @Group("padded") @GroupThreads(1)
    public void paddedA() { padded.a++; }
    @Benchmark @Group("padded") @GroupThreads(1)
    public void paddedB() { padded.b++; }
}
```

전형적 결과(2코어 기준, 상대값):

```
Benchmark          Mode  Cnt    Score   Units
naive             thrpt   10   38.2 ± 1.1  ops/us
padded            thrpt   10  312.7 ± 8.4  ops/us     ← 약 8배
```

패딩 7개(`7 × 8B = 56B`) + 대상 필드 8B = 64B 로 라인을 채우는 방식이다. 다만 JVM 은 필드 순서를 재배치할 수 있어(`-XX:+CompactFields`, `FieldsAllocationStyle`) 패딩이 의도대로 배치된다는 보장이 없다. 그래서 표준 수단이 따로 있다.

```java
import jdk.internal.vm.annotation.Contended;

class Counters {
    @Contended volatile long counterA;
    @Contended volatile long counterB;
}
```

`@Contended` 는 JVM 이 해당 필드 앞뒤에 자동으로 패딩을 넣게 한다. 패딩 크기는 `-XX:ContendedPaddingWidth`(기본 128 — 인접 라인 프리페치를 고려해 2라인)로 조정한다. JDK 9+ 에서는 `jdk.internal` 이라 모듈 export 가 필요하다.

```bash
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
     -XX:-RestrictContended -jar app.jar
```

JDK 내부에서 실제로 쓰이는 곳을 보면 이해가 빠르다. `LongAdder` 의 `Cell` 이 대표적이다.

```java
// java.util.concurrent.atomic.Striped64
@jdk.internal.vm.annotation.Contended
static final class Cell {
    volatile long value;
    ...
}
```

`AtomicLong` 은 단일 변수를 모든 코어가 CAS 로 두드려 라인 핑퍼핑이 극심하다. `LongAdder` 는 코어별 `Cell` 로 분산하고 각 Cell 을 `@Contended` 로 분리해 경합을 없앱 뒤, `sum()` 시에만 합산한다. 그래서 **경합이 심한 카운터는 `LongAdder`, 정확한 현재값이 매번 필요하면 `AtomicLong`** 이 판단 기준이다.

`ConcurrentHashMap.CounterCell`, `ForkJoinPool.WorkQueue` 도 같은 이유로 `@Contended` 가 붙어 있다.

### 진단

추측하지 말고 측정한다. 리눅스 `perf` 의 `c2c`(cache-to-cache)가 전용 도구다.

```bash
perf c2c record -F 60000 -a -- sleep 10
perf c2c report --stdio
```

```
=================================================
      Shared Data Cache Line Table
=================================================
  Index  Cacheline          Total  %hitm   LclHitm  RmtHitm
      0  0xffff88012a3c0000  4812  62.31%     2998        0
```

`HITM`(Hit Modified — 다른 코어의 M 라인을 가져옴)이 높은 캐시 라인이 범인이다. `perf c2c report` 는 그 라인의 어느 오프셋을 어느 함수가 읽고 썼는지까지 보여주므로, 변수 두 개가 한 라인에 있는 것을 직접 확인할 수 있다.

간이 확인은 이벤트 카운터로도 된다.

```bash
perf stat -e cache-misses,cache-references,LLC-load-misses ./app
```

## 4. 일관성이 있는데 왜 배리어가 필요한가

여기서 흔한 오해가 나온다. "MESI 가 일관성을 보장하면 volatile 이 왜 필요한가?"

MESI 는 **coherence**(하나의 주소에 대한 사본들의 일치)를 보장하지, **consistency**(여러 주소에 대한 연산 순서)를 보장하지 않는다. 그리고 순서를 깨뜨리는 하드웨어 구조가 CPU 안에 있다.

**Store Buffer.** RFO 로 라인 소유권을 얻는 데 수백 사이클이 걸린다. 그동안 코어가 놀 수 없으므로, 쓰기를 Store Buffer 에 넣고 즉시 다음 명령으로 진행한다. 소유권이 오면 나중에 캐시에 반영한다. 결과적으로 **내 쓰기가 다른 코어에 보이는 시점이 지연된다.**

**Invalidate Queue.** 무효화 메시지를 받은 코어도 즉시 처리하지 않는다. 큐에 넣고 ACK 먼저 보낸 뒤 나중에 처리한다. 결과적으로 **다른 코어의 쓰기가 나에게 늦게 보인다.**

이 둘이 만드는 고전적 반례가 Dekker 패턴이다.

```
초기: x = 0, y = 0

코어0:              코어1:
x = 1;              y = 1;
r1 = y;             r2 = x;
```

순차 일관성(SC)이라면 `r1 == 0 && r2 == 0` 은 불가능하다. 하지만 실제 x86 에서 관측된다. `x = 1` 이 코어0 의 Store Buffer 에 머무는 동안 `r1 = y` 가 먼저 실행되기 때문이다. 이것이 **StoreLoad 재배열**이며, x86 조차 허용하는 유일한 재배열이다.

## 5. 메모리 모델 — x86 TSO vs ARM

| 재배열 | x86 (TSO) | ARMv8 / RISC-V (약한 순서) |
|---|---|---|
| LoadLoad | 금지 | **허용** |
| LoadStore | 금지 | **허용** |
| StoreStore | 금지 | **허용** |
| StoreLoad | **허용** | **허용** |

x86 은 Store Buffer 만 있고 Invalidate Queue 를 순서대로 처리하므로 StoreLoad 만 깨진다(Total Store Order). ARM 은 전부 깨진다.

이 차이의 실무적 함의가 크다. **x86 에서 테스트가 통과한 동시성 코드가 ARM(Apple Silicon, AWS Graviton)에서 깨진다.** 배리어를 빠뜨린 코드가 x86 에서는 우연히 동작하기 때문이다. Graviton 마이그레이션에서 재현 불가능한 버그가 나타나는 전형적 원인이 이것이다.

배리어 명령 대응:

| 의미 | x86 | ARMv8 |
|---|---|---|
| 전체 배리어 | `mfence` / `lock` 접두사 | `dmb ish` |
| Acquire (이후 재배열 금지) | 불필요(암묵적) | `ldar` / `dmb ishld` |
| Release (이전 재배열 금지) | 불필요(암묵적) | `stlr` / `dmb ish` |
| Sequential Consistency store | `xchg` 또는 `mov` + `mfence` | `stlr` + `dmb ish` |

x86 에서 acquire/release 가 공짜인 이유는 하드웨어가 이미 그 순서를 지키기 때문이다. 그래서 x86 에서는 `volatile` 읽기가 일반 읽기와 같은 비용이고, 쓰기만 `mfence` 급 비용을 낸다.

## 6. Java Memory Model 에서의 매핑

JMM 은 이 하드웨어 차이를 추상화한다. `volatile` 의 의미는 다음과 같이 정의된다.

```java
class Holder {
    private int data;
    private volatile boolean ready;

    // 쓰기 스레드
    void publish() {
        data = 42;          // 일반 쓰기
        ready = true;       // volatile 쓰기 = release
    }

    // 읽기 스레드
    int consume() {
        if (ready) {        // volatile 읽기 = acquire
            return data;    // 42 가 보장됨
        }
        return -1;
    }
}
```

`ready = true`(volatile write) 이전의 **모든 쓰기**가 `ready` 읽기가 true 를 본 이후의 **모든 읽기**에 보인다. `data` 가 volatile 이 아닌데도 42 가 보장되는 이유다. 이것이 happens-before 의 핵심이고, "volatile 은 그 변수 하나만 보호한다" 는 흔한 오해가 틀린 이유다.

JIT 이 삽입하는 배리어(HotSpot `orderAccess`):

| JMM 연산 | x86 생성 코드 | ARM 생성 코드 |
|---|---|---|
| volatile read | `mov` (배리어 없음) | `ldar` |
| volatile write | `mov` + `lock addl $0,(%rsp)` | `stlr` + `dmb ish` |
| `VarHandle.getAcquire` | `mov` | `ldar` |
| `VarHandle.setRelease` | `mov` | `stlr` |
| `VarHandle.setOpaque` | `mov` | `str` |

x86 이 `mfence` 대신 `lock addl $0,(%rsp)` 를 쓰는 것은 실측상 그쪽이 더 빠르기 때문이다(스택 톱은 항상 L1 에 있고 `lock` 접두사가 full fence 효과를 냄).

`VarHandle` 로 필요한 만큼만 쓰면 volatile 의 전체 배리어 비용을 피할 수 있다.

```java
class RingBuffer {
    private static final VarHandle SEQ;
    static {
        try {
            SEQ = MethodHandles.lookup()
                .findVarHandle(RingBuffer.class, "sequence", long.class);
        } catch (ReflectiveOperationException e) { throw new Error(e); }
    }

    @SuppressWarnings("unused")
    private long sequence;

    void publish(long v) { SEQ.setRelease(this, v); }   // release 만. full fence 불필요
    long read()          { return (long) SEQ.getAcquire(this); }
}
```

생성 코드를 확인하려면:

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly \
     -XX:CompileCommand=print,RingBuffer::publish -jar app.jar
```

## 7. 트레이드오프와 설계 지침

| 기법 | 이득 | 비용 |
|---|---|---|
| 필드 패딩 / `@Contended` | 핑퍼핑 제거. 경합 시 수 배~수십 배 | 객체당 128B+. 객체 수가 많으면 캐시 압박 역효과 |
| `LongAdder` | 경합 카운터 처리량 | `sum()` 이 O(코어수), 정확한 스냅샷 아님 |
| Thread-local 집계 후 병합 | 경합 0 | 집계 지연, 메모리 |
| `VarHandle` acquire/release | ARM 에서 배리어 절감 | 추론 난이도 급증. 버그 시 재현 불가 |
| 불변 객체 + 안전 발행 | 배리어 불필요 | 할당 압력, GC |

패딩의 역효과가 자주 간과된다. `@Contended` 는 객체당 최대 256B(앞뒤 128B)를 추가한다. 인스턴스가 100만 개면 256MB 가 순수 패딩이다. 그 객체들을 순회하는 워크로드에서는 캐시 라인당 유효 데이터 비율이 떨어져 **오히려 느려진다.** 그래서 `@Contended` 는 "적은 수의 객체를 여러 코어가 격렬히 두드리는" 경우에만 쓴다 — `LongAdder.Cell` 이 정확히 그 조건이다.

일반적인 우선순위는 이렇다.

1. **공유하지 않는다.** 스레드별 로컬 집계 후 병합. 경합 자체를 제거하는 것이 항상 최선
2. **불변으로 만든다.** final 필드는 안전 발행이 보장되어 배리어가 불필요
3. **표준 동시 자료구조를 쓴다.** `ConcurrentHashMap`, `LongAdder` 는 이미 이 모든 것이 적용됨
4. **`volatile` / `AtomicX`** 로 명확하게
5. **`VarHandle` 의 약한 모드**는 프로파일링으로 병목이 증명된 후에만
6. **패딩**은 `perf c2c` 로 false sharing 이 확인된 후에만

마지막 두 항목의 순서가 중요하다. 배리어 최적화와 패딩은 코드 이해도를 크게 떨어뜨리고, 잘못하면 x86 에서 통과하고 ARM 에서만 깨지는 — 즉 CI 에서 안 잡히고 운영에서 터지는 — 버그를 만든다. 측정 없이 적용할 대상이 아니다.

## 참고

- Ulrich Drepper, "What Every Programmer Should Know About Memory": https://people.freebsd.org/~lstewart/articles/cpumemory.pdf
- Paul E. McKenney, *Is Parallel Programming Hard, And, If So, What Can You Do About It?*: https://mirrors.edge.kernel.org/pub/linux/kernel/people/paulmck/perfbook/perfbook.html
- Doug Lea, "The JSR-133 Cookbook for Compiler Writers": https://gee.cs.oswego.edu/dl/jmm/cookbook.html
- JEP 193: Variable Handles: https://openjdk.org/jeps/193
- Intel 64 and IA-32 Architectures SDM, Vol.3A Ch.8 — Memory Ordering: https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sdm.html
- ARM Architecture Reference Manual — Memory Ordering: https://developer.arm.com/documentation/den0024/a/Memory-Ordering
- perf c2c man page: https://man7.org/linux/man-pages/man1/perf-c2c.1.html
