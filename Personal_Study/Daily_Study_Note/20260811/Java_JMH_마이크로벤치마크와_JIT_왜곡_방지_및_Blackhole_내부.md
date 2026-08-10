Notion 원본: https://www.notion.so/3b85a06fd6d381e78434d969504a2ca4

# Java JMH 마이크로벤치마크와 JIT 왜곡 방지 및 Blackhole 내부

> 2026-08-11 신규 주제 · 확장 대상: JAVA (JIT 티어드 컴파일 · 탈최적화 진단 기학습)

## 학습 목표

- JIT의 DCE·상수 접기·인라이닝이 벤치마크를 왜곡하는 메커니즘을 재현하고 차단한다
- JMH의 fork/warmup/iteration 모델과 Blackhole·State의 내부 동작을 분석한다
- perfasm·gc 프로파일러로 벤치마크 결과를 어셈블리·할당 수준에서 검증한다
- 신뢰 가능한 벤치마크 작성 체크리스트를 실무 코드 비교(예: Stream vs for)에 적용한다

## 1. 왜 System.nanoTime 루프는 거짓말을 하는가

벤치마크 왜곡의 주범은 측정 코드가 아니라 **JIT 컴파일러의 최적화**다. 기학습한 티어드 컴파일 관점에서 보면, 수동 루프 벤치마크는 다음 함정을 모두 밟는다.

첫째, **Dead Code Elimination(DCE)**. 결과를 사용하지 않는 계산은 C2가 통째로 제거한다. `Math.log(x)`를 백만 번 돌려도 반환값을 아무도 안 읽으면 루프 본문이 빈 껍데기가 되고, "log가 0.3ns"라는 불가능한 수치가 나온다. 둘째, **상수 접기(Constant Folding)**. 입력이 컴파일 타임 상수로 판정되면 계산 자체가 결과 상수로 치환된다. 셋째, **루프 최적화**. 루프 언롤링과 벡터화가 걸리면 "호출당 비용"이라는 개념 자체가 무너진다. 넷째, **프로파일 오염과 탈최적화**. 워밍업 없이 측정하면 인터프리터·C1·C2 실행이 섞인 평균이 나오고, 단형(monomorphic) 호출로 프로파일된 지점에 새 타입이 들어오면 탈최적화가 측정 구간에 끼어든다. 다섯째, **OSR(On-Stack Replacement)**. 긴 루프 안에서 컴파일이 시작되면 OSR 컴파일된 코드는 일반 컴파일과 최적화 품질이 달라, "메서드를 반복 호출"하는 실제 워크로드와 다른 코드를 측정하게 된다.

```java
// 전형적인 잘못된 벤치마크 — 세 가지 왜곡이 동시에 발생
long start = System.nanoTime();
for (int i = 0; i < 1_000_000; i++) {
	Math.log(42);            // 인자 상수 → 접힘, 결과 미사용 → DCE, 루프 → OSR
}
System.out.println((System.nanoTime() - start) / 1_000_000);
```

JMH(Java Microbenchmark Harness)는 OpenJDK가 이 함정들을 체계적으로 봉쇄하려고 만든 하네스로, JDK 자체 성능 작업에 쓰이는 사실상 표준이다.

## 2. JMH 실행 모델 — fork, warmup, iteration의 의미

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Thread)
public class SumBench {

	@Param({"1000", "1000000"})
	int size;

	int[] data;

	@Setup(Level.Trial)
	public void setup() {
		data = new Random(42).ints(size).toArray();
	}

	@Benchmark
	public long forLoop() {
		long sum = 0;
		for (int v : data) {
			sum += v;
		}
		return sum;                       // 반환 → 암묵적 Blackhole 소비
	}

	@Benchmark
	public long stream() {
		return Arrays.stream(data).asLongStream().sum();
	}
}
```

`@Fork`가 가장 중요하다. 각 벤치마크를 **새 JVM 프로세스**에서 돌리는 이유는 JIT 프로파일 오염 때문이다. 같은 JVM에서 A를 먼저 돌리면 공용 코드(예: `Spliterator`)의 타입 프로파일이 A 기준으로 굳어 B의 인라이닝 결정이 달라진다 — 실행 순서에 따라 결과가 바뀌는 "run-to-run 편향"의 원인. fork를 2 이상 주면 JVM 수준의 비결정성(TLAB 배치, ASLR, 코드 캐시 배치)까지 표본에 포함되어 신뢰 구간이 정직해진다.

warmup iteration은 티어드 컴파일이 안정 상태(C2 최종 코드)에 도달할 시간을 준다. JMH는 워밍업 구간의 수치를 버리고 measurement 구간만 집계하며, 결과에는 평균뿐 아니라 **99.9% 신뢰 구간**이 함께 나온다. 신뢰 구간이 넓으면(±20%↑) 결론을 내리면 안 되는 데이터다. `Mode.SampleTime`은 호출당 지연 분포(p50/p99)를, `Mode.SingleShotTime`은 콜드 스타트 비용을 측정할 때 쓴다.

## 3. Blackhole — DCE를 막는 소비자

반환값이 없는 계산이나 루프 내 다중 값 소비에는 `Blackhole`을 명시적으로 쓴다.

```java
@Benchmark
public void multiValues(Blackhole bh) {
	for (int v : data) {
		bh.consume(compute(v));     // 각 결과를 강제 소비
	}
}
```

`Blackhole.consume`의 구현이 흥미로운 지점이다. 전통 구현은 volatile 필드 비교와 "컴파일러가 증명할 수 없는 조건 분기"를 조합해 값이 사용되는 것처럼 보이게 만들었다. JDK 17+에서는 JVM이 `-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=blackhole,...`로 **컴파일러 수준 blackhole**을 지원하고, JMH는 호환 JVM에서 이를 자동 사용한다. 컴파일러 blackhole은 인자를 살아있는 값으로 취급하되 오버헤드는 0에 수렴시키므로, 나노초 단위 벤치마크에서 consume 자체의 비용(구식 구현은 수 ns)이 결과를 오염시키는 문제를 없앨다.

상수 접기 차단은 반대 방향의 문제로, 입력을 `@State` 필드에서 읽게 만들면 된다. JIT는 힙 필드 값을 컴파일 타임 상수로 증명할 수 없으므로 접지 못한다. 흔한 실수는 벤치마크 메서드 안에서 `int x = 42;`를 선언해 쓰는 것 — final 로컬 상수는 그대로 접힌다.

```java
@State(Scope.Thread)
public static class Inputs {
	int x = 42;          // 필드에서 로드 → 접기 차단
}

@Benchmark
public double log(Inputs in) {
	return Math.log(in.x);
}
```

## 4. State와 공유 스코프 — 멀티스레드 벤치마크

`@State(Scope.Thread)`는 스레드마다 인스턴스를 만들고, `Scope.Benchmark`는 모든 스레드가 공유한다. 락·캐시·동시성 자료구조 벤치마크에서 이 구분이 결과를 좌우한다. `ConcurrentHashMap.get`을 Scope.Thread 상태의 맵으로 재면 CPU 캐시에 로컬로 올라앉은 맵을 재는 것이고, Scope.Benchmark로 재면 코어 간 캐시 라인 트래픽이 포함된다 — 기학습한 MESI·False Sharing이 바로 여기서 재현된다. JMH는 `@State` 필드를 캐시 라인 패딩으로 감싸 하네스 자체의 false sharing을 방지한다.

```java
@State(Scope.Benchmark)
public static class SharedMap {
	ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
	@Setup public void fill() { for (int i = 0; i < 10_000; i++) map.put(i, i); }
}

@Benchmark
@Threads(8)                      // 8스레드 경합 측정
public Integer contendedGet(SharedMap s, Inputs in) {
	return s.map.get(in.x);
}
```

`@Group`/`@GroupThreads`를 쓰면 reader 6 : writer 2 같은 비대칭 워크로드를 하나의 벤치마크로 구성할 수 있어, Redisson 락이나 캐시 스탬피드 시나리오 재현에 유용하다.

## 5. 프로파일러 연동 — perfasm과 gc로 결과를 검증한다

JMH 수치는 "무엇이 빠르다"만 말하고 "왜"는 말하지 않는다. 내장 프로파일러로 원인을 확인해야 결과를 신뢰할 수 있다.

```bash
# GC 프로파일러: 할당률과 GC 카운트
java -jar benchmarks.jar SumBench -prof gc

# 결과 예시
# SumBench.stream:·gc.alloc.rate.norm    1000000  368.0 B/op   ← 호출당 368바이트 할당
# SumBench.forLoop:·gc.alloc.rate.norm   1000000    0.001 B/op ← 무할당

# perfasm: 핫 어셈블리 어노테이션 (Linux perf + hsdis 필요)
java -jar benchmarks.jar SumBench -prof perfasm

# perfnorm: 명령어/캐시미스/분기예측실패를 op당 정규화
java -jar benchmarks.jar SumBench -prof perfnorm
```

`gc.alloc.rate.norm`은 Stream 벤치마크의 단골 검증 포인트다 — Stream이 느린 이유가 파이프라인 객체 할당인지 확인해 준다. `perfasm`은 측정 구간의 어셈블리에 사이클 비중을 주석으로 달아 주는데, 여기서 루프가 AVX 벡터 명령(`vpaddq`)으로 벡터화됐는지, 예상한 인라이닝이 실제 일어났는지 눈으로 확인한다. 인라이닝 결정 로그는 `-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"`으로 본다. `@CompilerControl(CompilerControl.Mode.DONT_INLINE)`을 걸면 "인라이닝이 됐을 때/안 됐을 때"를 분리 측정할 수 있다 — 작은 메서드의 비용이 사실상 인라이닝 여부에 지배된다는 것을 정량 확인하는 도구다.

## 6. 실전 사례 — Stream vs for, 그리고 잘못 해석하기 쉬운 결과

위 SumBench를 실행하면 대체로 이런 패턴의 결과가 나온다 (JDK 21, x86-64 기준 전형값):

| 벤치마크 | size=1000 | size=1000000 |
| --- | --- | --- |
| forLoop | ~250 ns/op | ~260 µs/op |
| stream | ~900 ns/op | ~290 µs/op |

작은 배열에서 Stream이 3~4배 느린 것은 파이프라인 셋업 고정비(Spliterator·람다 객체) 때문이고, 큰 배열에서는 고정비가 희석되어 격차가 10% 내로 줄어든다. 여기서 흔한 오해석 두 가지: (1) "Stream은 항상 3배 느리다" — 데이터 크기 축을 무시한 일반화. `@Param`으로 크기 축을 반드시 넣어야 하는 이유다. (2) 이 마이크로벤치 결과를 실서비스에 직결 — 실제 애플리케이션에서는 이 코드가 전체 CPU의 1%도 안 될 수 있고, 프로파일 오염 환경(메가모픽 콜사이트)에서는 람다 인라이닝이 마이크로벤치처럼 잘 되지 않는다. 마이크로벤치마크는 "상한 성능의 비교"이지 "실서비스 기대값"이 아니다.

## 7. 흔한 안티패턴 카탈로그

**루프 인덱스 곱하기 함정**: `@Benchmark` 안에서 자체 루프를 돌리고 총 시간을 나누는 방식은 JIT가 루프를 통째로 최적화(언롤·벡터화·hoisting)해 호출당 비용을 왜곡한다. JMH의 `@OperationsPerInvocation`이 필요한 특수한 경우가 아니면 벤치마크 본문은 "1회 연산"으로 둔다. **setup에서 측정 대상 오염**: `@Setup(Level.Invocation)`은 호출마다 실행되며 그 오버헤드와 캐시 워밍 효과가 측정을 오염시킨다 — 문서 자체가 "정말 필요한 경우만" 쓰라고 경고하는 레벨이다. **String 결합·박싱 무시**: 벤치마크 인자 준비 과정에서 발생하는 할당이 GC를 유발해 측정 구간에 STW가 끼어든다. `-prof gc`로 op당 할당을 항상 확인한다. **결과 재사용**: 이전 iteration의 결과가 다음 입력에 영향을 주는 상태 변이(리스트 append 등)는 iteration마다 작업량이 달라진다. `@Setup(Level.Iteration)`으로 리셋한다. **에너지·클럽 불안정**: 노트북의 터보부스트·서멀 스로틀링은 ±30% 변동을 만든다. 리눅스에서 `cpupower frequency-set -g performance` 고정, 클라우드에서는 전용 인스턴스 사용이 전제 조건이다.

## 8. 벤치마크를 CI에 넣기 — 회귀 감지 설계

JMH 결과를 JSON으로 남기면(`-rf json -rff result.json`) CI에서 기준선 대비 회귀를 판정할 수 있다. 주의점은 공유 러너의 노이즈다. GitHub Actions 공용 러너는 이웃 워크로드에 따라 20% 이상 흔들리므로, 절대값 비교가 아니라 **같은 런에서 baseline 브랜치와 candidate를 모두 실행해 상대 비교**하는 A/B 구조가 필요하다. 판정도 평균이 아닌 신뢰 구간 겹침 여부로 한다:

```bash
# 같은 러너에서 두 버전을 인터리브 실행
java -jar bench-main.jar -rf json -rff main.json
java -jar bench-pr.jar   -rf json -rff pr.json
# 신뢰구간이 겹치지 않고 평균 차 5% 이상일 때만 회귀로 판정하는 스크립트로 비교
```

jmh-gradle-plugin(`me.champeau.jmh`)을 쓰면 `./gradlew jmh`로 통합되고, async-profiler 연동(`-prof async:libPath=...`)으로 플레임그래프까지 아티팩트로 남길 수 있다. 기학습한 JFR 프로파일링과 역할 분담은: JMH는 "격리된 코드 조각의 정밀 비교", JFR/async-profiler는 "실서비스 전체에서 어디가 뜨거운가" — 순서상 후자로 병목을 찾고 전자로 대안을 비교하는 흐름이 정석이다.

## 9. 신뢰 가능한 벤치마크 체크리스트

결과를 보고서에 쓰기 전 최소 확인 목록: fork ≥ 2에서 fork 간 편차가 작은가. warmup 이후 iteration 수치가 안정(추세 없음)한가 — 상승 추세는 GC 누적, 하강 추세는 워밍업 부족 신호다. 신뢰 구간이 비교 대상 간 차이보다 충분히 좁은가. `-prof gc`로 의도치 않은 할당이 없는가. DCE 의심 — 비현실적으로 빠른 수치(<1ns/op)가 없는가. 입력이 상수 접기되지 않는가(@State 필드 경유). 데이터 크기·분포 축을 @Param으로 최소 2점 이상 둬는가. 결과 해석에 "이 조건에서"라는 한정을 명시했는가. 이 목록을 통과하지 못한 수치는 공유하지 않는 것이 원칙이다 — 잘못된 벤치마크는 없는 것보다 해롭다.

## 참고

- OpenJDK JMH 공식 저장소·샘플: https://github.com/openjdk/jmh (jmh-samples 38개 예제)
- Aleksey Shipilëv, "JMH vs Caliper: reference thread" 및 JMH 관련 발표 자료: https://shipilev.net/
- JDK Compiler Blackholes: https://bugs.openjdk.org/browse/JDK-8259316
- jmh-gradle-plugin: https://github.com/melix/jmh-gradle-plugin
- Brian Goetz, "Java theory and practice: Anatomy of a flawed microbenchmark" (IBM developerWorks archive)
