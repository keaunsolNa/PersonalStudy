Notion 원본: https://www.notion.so/37a5a06fd6d38167a9ffd32ec6b96ab7

# CPU 캐시 일관성 MESI 프로토콜과 False Sharing 그리고 @Contended 패딩

> 2026-06-09 신규 주제 · 확장 대상: 면접을 위한 CS 전공지식 노트

## 학습 목표

- 캐시 라인과 MESI 4상태 전이로 멀티코어 캐시 일관성이 유지되는 과정을 설명한다
- False Sharing이 성능을 떨어뜨리는 메커니즘을 캐시 라인 관점에서 분석한다
- Java `@Contended`와 패딩으로 False Sharing을 제거하는 방법을 구현한다
- store buffer·invalidate queue가 메모리 가시성·재정렬에 미치는 영향을 이해한다

## 1. 캐시 계층과 캐시 라인

CPU는 메인 메모리(수십~수백 ns)와의 속도 격차를 메우기 위해 L1/L2/L3 캐시를 둔다. 캐시는 바이트 단위가 아니라 캐시 라인(대부분 64바이트) 단위로 메모리를 적재한다. 즉 `long`(8바이트) 하나를 읽어도 그를 포함한 64바이트 블록 전체가 캐시에 올라온다. 이 "라인 단위 적재"가 공간 지역성 이점을 주는 동시에, 뒤에서 볼 False Sharing의 근본 원인이 된다.

## 2. MESI 프로토콜: 4상태 일관성

멀티코어에서 같은 메모리 블록이 여러 코어 캐시에 복제되면, 한 코어의 쓰기가 다른 코어에 보이도록 일관성을 유지해야 한다. MESI는 각 캐시 라인을 네 상태 중 하나로 관리한다.

| 상태 | 의미 | 다른 코어 사본 | 메모리와 일치 |
|---|---|---|---|
| Modified | 이 코어만 수정본 보유(dirty) | 없음 | 불일치 |
| Exclusive | 이 코어만 보유(clean) | 없음 | 일치 |
| Shared | 여러 코어가 공유(clean) | 있을 수 있음 | 일치 |
| Invalid | 무효, 사용 불가 | - | - |

코어가 Shared 라인에 쓰려면 먼저 다른 코어들에게 Invalidate 메시지를 보내 그들의 사본을 Invalid로 만들고, 자신은 Modified로 전이한다. 이 과정을 캐시 코히런시 버스(혹은 디렉터리 기반 프로토콜)가 중재한다. 한 코어가 Modified인 라인을 다른 코어가 읽으려 하면, 메모리가 아니라 Modified 보유 코어가 데이터를 넘겨주고(Forwarding) 양쪽이 Shared가 된다.

## 3. False Sharing: 논리적으로 무관한데 물리적으로 충돌

서로 다른 스레드가 서로 다른 변수를 수정하는데, 그 변수들이 우연히 같은 캐시 라인(64바이트)에 들어 있으면 문제가 생긴다. 코어 A가 자기 변수를 쓰면 라인 전체가 무효화되어, 같은 라인에 있는 코어 B의 변수도 강제로 다시 읽혀야 한다. 데이터상 공유가 없는데도 캐시 라인 공유 때문에 일관성 트래픽이 폭발하는 이것이 False Sharing이다.

```java
// False Sharing 발생: 두 long 이 같은 64바이트 라인에 인접 배치될 가능성 높음
class Counters {
    volatile long a; // 스레드1이 갱신
    volatile long b; // 스레드2가 갱신
}
// 스레드1의 a 쓰기 → 라인 무효화 → 스레드2의 b 캐시도 무효 → 재적재 반복
// 두 변수가 독립적인데도 코어 간 ping-pong 으로 성능 급락
```

증상은 "스레드를 늘렸는데 오히려 느려지거나 확장이 멈추는" 현상이다. 단일 스레드보다 멀티 스레드가 느리면 False Sharing을 의심해야 한다.

## 4. 패딩으로 라인 분리

해결책은 충돌하는 변수들을 각각 다른 캐시 라인으로 밀어내는 것이다. 변수 사이에 더미 필드를 채워 64바이트 이상 간격을 둔다.

```java
// 수동 패딩: a 와 b 가 서로 다른 캐시 라인에 놓이도록 56바이트 채움
class PaddedCounters {
    volatile long a;
    long p1, p2, p3, p4, p5, p6, p7; // 7 * 8 = 56바이트 패딩
    volatile long b;
}
```

`a`(8B) + 패딩(56B) = 64B로 한 라인을 가득 채워 `b`는 다음 라인으로 넘어간다. 다만 JIT/JVM의 필드 재정렬과 객체 헤더 때문에 수동 패딩은 깨지기 쉬워, 표준 애너테이션이 권장된다.

## 5. Java @Contended

JDK 8부터 `jdk.internal.vm.annotation.Contended`(과거 `sun.misc.Contended`)가 캐시 라인 패딩을 자동화한다. JVM이 해당 필드 앞뒤로 충분한 패딩을 삽입한다. 다만 내부 API라서 사용하려면 JVM 플래그가 필요하다.

```java
import jdk.internal.vm.annotation.Contended;

class Counters {
    @Contended volatile long a; // JVM 이 라인 단위로 격리
    @Contended volatile long b;
}
```

```bash
# @Contended 활성화 (기본은 무시됨)
java -XX:-RestrictContended --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -jar app.jar
```

`-XX:ContendedPaddingWidth`(기본 128바이트)로 패딩 폭을 조정한다. 128을 쓰는 이유는 일부 CPU가 인접 두 라인을 함께 prefetch(adjacent line prefetch)해 64바이트만으로는 충돌이 남을 수 있기 때문이다. JDK의 `LongAdder`, `ForkJoinPool`, `ConcurrentHashMap`의 카운터 셀이 이 기법으로 코어별 셀을 분리해 경합을 흥뿌린다.

## 6. Store Buffer와 가시성

쓰기 성능을 위해 CPU는 store buffer를 둔다. 쓰기를 즉시 캐시에 반영하지 않고 버퍼에 쌓아 비동기로 flush 한다. 이 때문에 한 코어의 쓰기가 다른 코어에 즉시 보이지 않을 수 있고, 같은 코어 내에서도 후속 읽기가 store buffer를 먼저 보는 store forwarding이 일어난다. 또 invalidate 메시지를 즉시 처리하지 않고 invalidate queue에 미루면, 무효화된 데이터를 잠깐 더 읽을 수 있다. 이 두 구조가 메모리 재정렬과 가시성 문제의 하드웨어적 근원이다.

```java
// 메모리 배리어가 필요한 이유 — store buffer/invalidate queue flush 강제
volatile boolean ready = false; // volatile 쓰기 = store 배리어, 읽기 = load 배리어
// JMM 의 happens-before 는 결국 이 하드웨어 배리어로 구현됨
```

`volatile`, `synchronized`, `VarHandle`의 acquire/release는 적절한 메모리 배리어를 삽입해 store buffer를 flush하고 invalidate queue를 비워 가시성을 보장한다. MESI가 "최종적으로는" 일관성을 맞추지만, store buffer 때문에 "즉시" 일관적이지는 않다는 점이 핵심이다.

## 7. 진단과 측정

False Sharing은 코드만 봐선 알기 어렵고 측정이 필요하다. `perf c2c`(cache-to-cache)는 어떤 캐시 라인이 코어 간에 ping-pong 되는지 HITM(modified line 적중) 이벤트로 직접 보여준다.

```bash
# 코어 간 캐시 라인 경합 핫스팟 탐지
perf c2c record -- ./your_app
perf c2c report     # HITM 많은 캐시 라인과 그 안의 오프셋(변수) 식별

# JMH 로 마이크로벤치마크 (패딩 전후 처리량 비교)
# @State 객체에 @Contended 적용 전/후 ops/s 차이로 검증
```

JMH 벤치마크로 패딩 적용 전후 처리량을 비교하면 효과가 정량화된다. 통상 고경합 카운터에서 패딩만으로 수 배 처리량 향상이 관측된다.

## 8. LongAdder가 AtomicLong을 이기는 이유

False Sharing과 캐시 일관성 비용을 이해하면 JDK의 설계 선택이 보인다. `AtomicLong.incrementAndGet()`은 단일 변수에 CAS를 걸는데, 코어가 많아질수로 그 한 캐시 라인을 두고 모든 코어가 경합해 MESI invalidate가 폭주한다. 이를 cache line contention이라 한다. `LongAdder`는 값을 여러 `Cell`로 쯪고 각 Cell을 `@Contended`로 캐시 라인 격리해, 스레드들이 서로 다른 Cell에 더한 뒤 읽을 때만 합산한다.

```java
// 고경합 카운터: AtomicLong → LongAdder 로 교체 시 처리량 급증
LongAdder counter = new LongAdder();
counter.increment();        // 스레드별로 분산된 Cell 에 가산 (경합 분산)
long total = counter.sum(); // 읽을 때만 모든 Cell 합산

// 내부 Cell 은 대략 다음과 같이 패딩되어 있음
// @jdk.internal.vm.annotation.Contended static final class Cell { volatile long value; ... }
```

핵심 통찰은 "쓰기 경합을 공간적으로 분산(striping)하면 일관성 트래픽이 줄어든다"는 것이다. 단, `sum()`은 원자적 스냅샷이 아니라 근사값일 수 있어 통계 카운터에는 적합하지만 "정확한 순간값"이 필요한 곳에는 부적합하다. 정확성과 확장성의 trade-off가 또 한 번 나타난다. 같은 원리로 `ConcurrentHashMap`도 카운터 셀을 분산해 size 집계 경합을 줄인다.

## 9. trade-off 정리

캐시 라인 패딩은 False Sharing을 제거해 멀티코어 확장성을 회복시키지만, 변수마다 라인 하나(64~128바이트)를 통째 쓰므로 메모리 사용량과 캐시 점유가 늘어난다. 따라서 (1) 실제로 여러 스레드가 고빈도로 독립 갱신하는 핫 필드에만 선택 적용하고, (2) 단일 스레드 또는 저경합 데이터에는 패딩이 캐시 낭비일 뿐이며, (3) 적용 전후를 `perf c2c`·JMH로 반드시 측정하는 것이 원칙이다. 확장성과 메모리 효율은 trade-off이며, 측정 없이 모든 필드에 `@Contended`를 붙이면 캐시 적중률을 떨어뜨려 오히려 손해다.

## 참고

- "What Every Programmer Should Know About Memory" — Ulrich Drepper (캐시·MESI 상세)
- OpenJDK — `jdk.internal.vm.annotation.Contended` 와 `-XX:ContendedPaddingWidth`
- Linux perf — `perf c2c` cache-to-cache 분석 문서
- Martin Thompson, "Mechanical Sympathy" 블로그 — False Sharing 사례 분석
