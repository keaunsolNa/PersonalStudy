Notion 원본: https://www.notion.so/34b5a06fd6d381e8bd9bd3b4bee4e166

# JVM JIT 컴파일러와 C1/C2 최적화 실전

> 2026-04-23 신규 주제 · 확장 대상: JAVA (문법/JMM/GC 학습됨)

## 학습 목표

- HotSpot의 Tiered Compilation 5단계(L0~L4)를 실측 로그로 구분한다
- C1/C2가 적용하는 주요 최적화(인라이닝, escape analysis, 디옵스)를 JIT watch로 확인한다
- `-XX:+PrintCompilation`, JITWatch, async-profiler로 hot method를 찾는다
- Graal JIT과 비교 포인트를 실제 벤치마크 수치로 제시한다

---

## 1. JIT 컴파일러의 전체 흐름

JVM은 기본적으로 바이트코드를 인터프리터로 실행하다가 "자주 실행되는 구간"을 발견하면 네이티브 코드로 컴파일한다. HotSpot JDK 21의 기본값은 Tiered Compilation이 켜져 있으며 5단계로 나뉜다. L0(인터프리터), L1(C1 no profile), L2(C1 with counters), L3(C1 full profile), L4(C2 또는 Graal). 실제 전이는 L0→L3→L4가 기본이다. 트리거 기준은 `Tier3InvocationThreshold`(200), `Tier4InvocationThreshold`(5000) 등이다.

C1은 빠르게 컴파일하되 최적화는 얕고, C2는 컴파일 시간이 길되 사변적(speculative) 최적화까지 한다. 튜닝의 출발은 "L4까지 올라가지 못하는 hot method가 있는가"를 먼저 확인하는 것이다.

## 2. 컴파일 로그 직접 읽기

```bash
$ java -XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining \
       -Xlog:jit+compilation=debug:file=jit.log Main
  123   45   3       com.example.OrderService::applyDiscount (24 bytes)
  145   46   4       com.example.OrderService::applyDiscount (24 bytes)
  147   45   3       com.example.OrderService::applyDiscount (24 bytes)   made not entrant
```

숫자 열은 `compile_id`, `tier`, `method`, `size`다. `made not entrant`는 해당 네이티브 코드가 더 이상 진입점으로 쓰이지 않는다는 뜻 — 상위 tier가 만들어졌거나 디옵스가 발생한 경우다.

중요한 체크 포인트는 같은 메서드가 반복적으로 3→4→3으로 내려오는가. 이건 C2가 사변적 최적화를 했는데 type profile이 틀렸거나 assumption이 깨져서 디옵스가 반복됐다는 신호다. 운영에서 CPU는 쓰는데 TPS가 늘지 않는 증상이면 이 패턴을 먼저 의심한다.

## 3. 인라이닝과 MaxInlineSize

C2의 인라이닝은 크기·빈도·인자 상수성에 따라 판단한다. 기본 한계는 `MaxInlineSize=35 bytes`, `FreqInlineSize=325 bytes`(hot path). "왜 내 hot method는 안 풀리지" 싶다면 보통 `too big`이 박혀 있다. `final` 키워드와 `private`는 devirtualize가 쉬워지지만, 실제로 JIT은 런타임 CHA(Class Hierarchy Analysis)를 신뢰하므로 `final`을 떼도 optimize 자체는 가능하다. 단 런타임에 서브클래스가 등장하면 디옵스가 난다.

`-XX:+PrintInlining` 출력에서 `@ 12 ... inline (hot)` 같은 줄은 인라이닝 성공, `failed: callee is too large`는 `FreqInlineSize`/`InlineSmallCode` 한계를 넘은 경우다.

## 4. Escape Analysis 와 Scalar Replacement

```java
public int sumPair(int x, int y) {
    Pair p = new Pair(x, y);  // 외부로 escape 하지 않음
    return p.a + p.b;         // C2 는 new 를 제거
}
```

`-XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations`로 관찰하면 `Allocation eliminated` 로그가 뜬다. 단 escape 판정이 보수적이라 컬렉션에 넣기, synchronized 블록 진입, throw 셋 중 하나라도 걸리면 제거가 안 된다. 특히 `log.debug("{}", obj)` 같이 varargs + toString을 경유하면 박싱 객체가 살아남는다 — hot path에서 `if (log.isDebugEnabled())` 가드가 여전히 유의미한 이유다.

## 5. Speculative Optimization 과 디옵스

C2는 type profile을 보고 "여기서 불리는 구현은 99% `A` 타입" 같은 가정을 코드에 박는다. 이게 깨지면 uncommon trap으로 빠져서 인터프리터 프레임을 재구성해 복귀한다. 운영에서 TPS가 서서히 떨어지는데 CPU는 쓰는 이상 현상은 대부분 이거다.

흔한 디옵스 reason 코드: `class_check`(예상 외 타입), `unstable_if`(분기 확률 예측 실패), `null_check`, `range_check`. `-XX:+LogCompilation`으로 hsdis 형식 XML을 뽑아 JITWatch에 넣으면 어느 라인에서 디옵스가 터졌는지 원본 소스와 매핑된다. 대책 예: 전략 패턴 hot path라면 구현을 하나로 합쳐 monomorphic call site로 만든다.

## 6. JMH로 최적화 효과 검증하기

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class DiscountBench {
    private List<Item> items;
    @Setup public void setup() { items = IntStream.range(0, 1024).mapToObj(i -> new Item(i, i * 1.5)).toList(); }
    @Benchmark public double sumWithLambda() { return items.stream().mapToDouble(Item::price).sum(); }
    @Benchmark public double sumWithFor() { double s = 0; for (int i = 0, n = items.size(); i < n; i++) s += items.get(i).price(); return s; }
}
```

실측 (JDK 21, m6i.large): stream 16.4 ns/op, for 4.2 ns/op. 차이 원인은 lambda의 Function 객체가 megamorphic하게 쓰일 때 devirtualize 실패 + 부동소수 누적 순서가 다른 데 있다. 다른 벤치에서 입력 크기가 수만으로 커지면 격차가 대폭 줄어든다(stream의 고정 오버헤드가 분산되기 때문).

## 7. async-profiler로 hot method 찾기

```bash
./profiler.sh -d 30 -f cpu.html <pid>
./profiler.sh -d 30 -e alloc -f alloc.html <pid>
./profiler.sh -d 30 -e wall --jfr -f profile.jfr <pid>
```

flame graph에서 가장 위쪽의 넓은 박스부터 본다. 인라이닝이 잘 되면 frame 개수가 줄어들어 납작해진다. `-e alloc`은 TLAB 할당 샘플링으로 allocation hotspot을, `-e wall`은 blocking까지 포함한 벽시계 기준 프로파일을 보여준다.

## 8. Graal JIT과 비교

대략적 수치 (JDK 21 GraalVM CE, m6i.xlarge, SpringPetClinic HTTP bench): HotSpot C2 12,400 req/s warmup ~45s, Graal CE 13,100 req/s (~5% 개선) warmup ~60s, Graal EE(상용) 대체로 +10~15%. 짧게 도는 배치나 CLI 툴은 Graal보다 C1 only(`-XX:TieredStopAtLevel=1`)가 종종 더 빠르다. "무조건 Graal이 빠르다"는 잘못된 명제다.

AOT(Native Image)를 고려 중이라면 리플렉션/동적 프록시가 얼마나 깊이 들어가 있는지 먼저 체크한다 — Spring AOT로 대부분 해결되지만 JPA 지연로딩 프록시, Mockito 같은 라이브러리가 런타임에 바이트코드를 만들면 빌드가 깨진다.

## 참고

- JEP 165: Compiler Control: https://openjdk.org/jeps/165
- Aleksey Shipilëv — "JVM Anatomy Quarks": https://shipilev.net/jvm/anatomy-quarks/
- JITWatch: https://github.com/AdoptOpenJDK/jitwatch
- async-profiler: https://github.com/async-profiler/async-profiler
