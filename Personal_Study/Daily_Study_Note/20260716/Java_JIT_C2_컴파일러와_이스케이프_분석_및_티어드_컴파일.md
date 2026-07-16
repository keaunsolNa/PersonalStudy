Notion 원본: https://www.notion.so/39f5a06fd6d381c68531fe91893ac090

# Java JIT C2 컴파일러와 이스케이프 분석 및 티어드 컴파일

> 2026-07-16 신규 주제 · 확장 대상: JAVA

## 학습 목표

- 인터프리터부터 C2 까지 5단계 티어의 전이 조건을 추적한다
- Sea of Nodes IR 이 최적화에 유리한 이유를 설명한다
- 이스케이프 분석이 스칼라 치환과 락 제거로 이어지는 경로를 확인한다
- 역최적화 로그를 읽고 성능 이상을 진단한다

## 1. HotSpot 은 왜 컴파일러를 두 개 갖는가

JVM 은 바이트코드를 인터프리터로 실행하다가, 자주 실행되는 코드를 기계어로 컴파일한다. HotSpot 이라는 이름 자체가 "뜨거운 지점(hot spot)" 을 찾아 컴파일한다는 뜻이다.

문제는 컴파일러 설계의 근본적 상충이다. 컴파일을 빨리 하려면 최적화를 덜 해야 하고, 최적화를 많이 하려면 컴파일이 느려진다. 애플리케이션 기동 직후에는 빠른 컴파일이 필요하고, 정상 운영 중에는 최고 품질 코드가 필요하다.

HotSpot 은 컴파일러를 두 개 두어 이 상충을 해결한다.

| 항목 | C1 (Client) | C2 (Server) |
|---|---|---|
| 컴파일 속도 | 빠름 | 느림 (C1 대비 수십 배) |
| 코드 품질 | 보통 | 최고 |
| IR | HIR/LIR (CFG 기반) | Sea of Nodes |
| 주 최적화 | 인라이닝, 상수 폴딩, 널 체크 제거 | + 이스케이프 분석, 루프 언롤링, 벡터화 |
| 프로파일 수집 | 가능 (Tier 3) | 소비만 |
| 역할 | 기동 구간 담당 | 정상 운영 담당 |

**Tiered Compilation** 은 둘을 조합해 "처음엄 C1 으로 빠르게, 나중엔 C2 로 최고 품질" 을 실현한다. JDK 8 부터 기본 활성이다.

## 2. 5단계 티어와 전이 조건

티어는 0~4 다. Tier 2 와 3 이 둘 다 C1 인 것이 헷갈리는 지점이다.

| Tier | 실행 주체 | 프로파일링 | 언제 |
|---|---|---|---|
| 0 | 인터프리터 | 전체 (호출/백엣지 카운터) | 최초 |
| 1 | C1 | 없음 | 사소한 메서드(getter 등), C2 큐가 비었을 때 |
| 2 | C1 | 제한적 (호출/백엣지만) | C2 큐가 길 때 임시 |
| 3 | C1 | 전체 (타입 프로파일 포함) | 표준 경로 |
| 4 | C2 | 없음 (소비) | 최종 |

표준 경로는 **0 → 3 → 4** 다.

Tier 3 는 프로파일 수집 코드가 삽입되어 Tier 1 보다 **20~30% 느리다**. 그래도 이 단계를 거치는 이유는 C2 가 필요로 하는 정보 때문이다. C2 는 "이 호출 지점에서 실제로 어떤 구현체가 불렸는가", "이 분기가 얼마나 자주 참인가", "이 필드가 null 인 적이 있는가" 를 알아야 공격적 최적화를 할 수 있다. Tier 3 가 그 데이터를 모은다.

Tier 1 은 최적화가 끝난 사소한 메서드에 쓴다. `getName()` 같은 getter 는 프로파일을 모아봐야 얻을 것이 없으므로 C1 로 컴파일하고 끝낸다.

Tier 2 는 부하 조절 장치다. C2 컴파일 큐가 길어지면 Tier 3 의 느린 프로파일링 코드를 계속 도는 것이 손해이므로, 프로파일링을 줄인 Tier 2 로 임시 대피시킨다.

**전이 임계값**은 다음 공식으로 판정한다.

```
i = 메서드 호출 횟수 (invocation counter)
b = 루프 백엣지 횟수 (backedge counter)
s = 해당 티어의 컴파일 큐 길이

Tier 0 → 3:  i > TierXInvocationThreshold * s
             또는 i > TierXMinInvocationThreshold * s && i + b > TierXCompileThreshold * s
Tier 3 → 4:  i > Tier4InvocationThreshold * s
             또는 i > Tier4MinInvocationThreshold * s && i + b > Tier4CompileThreshold * s
```

큐 길이 `s` 가 곱해지는 것이 핵심이다. 큐가 길수록 임계값이 올라가 컴파일 요청이 줄어든다. 자기 조절(self-throttling) 메커니즘이다.

기본값은 다음과 같다.

```bash
java -XX:+PrintFlagsFinal -version | grep -i tier

# Tier3InvocationThreshold  = 200
# Tier3CompileThreshold     = 2000
# Tier4InvocationThreshold  = 5000
# Tier4CompileThreshold     = 15000
```

`-XX:-TieredCompilation` 으로 끄면 C2 만 쓰며 `CompileThreshold=10000` 단일 임계값이 적용된다. 기동은 느려지지만 워밍업 후 프로파일 오염이 없어 최종 코드 품질이 미세하게 나은 경우가 있다. 벤치마킹 외에는 권장되지 않는다.

**OSR(On-Stack Replacement)** 은 특수 경우다.

```java
public static void main(String[] args) {
	long sum = 0;
	for (int i = 0; i < 1_000_000_000; i++) {  // main 은 한 번만 호출됨
		sum += compute(i);
	}
}
```

`main` 은 호출 카운터가 1 이므로 영원히 컴파일되지 않는다. 백엣지 카운터가 이 문제를 푸는다. 루프가 임계값을 넘으면 **실행 중인 프레임을 컴파일된 코드로 교체**한다. 인터프리터의 로컬 변수 상태를 컴파일된 코드의 레지스터로 옜기는 작업이 필요해 복잡하지만, JIT 벤치마크 코드가 동작하는 근거가 이것이다.

## 3. Sea of Nodes — C2 의 IR

C2 의 중간 표현은 **Sea of Nodes** 다. 전통적 컴파일러의 CFG(제어 흐름 그래프) + 기본 블록 구조와 다르다.

전통적 IR 에서 명령어는 기본 블록 안에 순서대로 놓인다. 순서를 바꾸려면 블록 내 재배치라는 별도 패스가 필요하다. Sea of Nodes 는 **명령어를 블록에 배치하지 않는다.** 노드는 데이터 의존성과 제어 의존성만 가진 채 "바다에 떠 있고", 실제 순서는 컴파일 마지막에 스케줄링 단계에서 결정된다.

```java
int foo(int a, int b) {
	int c = a + b;
	if (a > 0) {
		return c * 2;
	}
	return c;
}
```

```
Parm(a)  Parm(b)
   \       /
    AddI(c)          ← 어느 블록에도 속하지 않는다
      |    \
      |     MulI(×2)
      |        |
   Region ← If(a>0)
      |
    Return(Phi)
```

`AddI` 노드는 제어 의존성이 없다. `a + b` 는 부수효과가 없고 예외도 던지지 않으므로 언제 계산해도 된다. 스케줄러는 나중에 "이 값이 실제로 쓰이는 가장 늦은 지점" 에 배치한다. `if` 가 거짓이면 `c * 2` 는 계산되지 않는다. 별도 최적화 패스 없이 IR 구조 자체가 이 이득을 만든다.

노드 종류는 세 가지다.

- **Data 노드**: `AddI`, `LoadField` 등 값 생산. 제어 흐름과 무관
- **Control 노드**: `If`, `Region`, `Return` 등 흐름 제어
- **Memory 노드**: `Phi`, `MergeMem` 등 메모리 상태 추적

메모리를 별도 노드로 모델링하는 것이 중요하다. `LoadField` 는 메모리 상태에 의존하므로 임의로 옛길 수 없지만, 서로 다른 필드에 접근하는 로드들은 독립적임을 그래프가 명시적으로 표현한다. C2 는 필드별로 메모리 슬라이스를 나눠 추적한다.

**장점**은 최적화 알고리즘이 단순해진다는 것이다. 공통 부분식 제거(GVN)는 "같은 연산·같은 입력 노드가 있으면 하나로 합치기" 로 끝난다. 별도 데이터 흐름 분석이 필요 없다.

**단점**은 컴파일 시간과 디버깅 난이도다. 노드가 수만 개로 불어나면 메모리와 시간이 급증한다. 스케줄링이 예상 밖 결과를 내면 원인 추적이 어렵다. Graal 은 Sea of Nodes 를 계승했지만, 최근 컴파일러 설계 논의에서는 CFG 기반으로 회귀하는 흐름도 있다.

## 4. 인라이닝 — 모든 최적화의 관문

인라이닝은 그 자체로도 호출 오버헤드를 없애지만, 진짜 가치는 **다른 최적화를 가능하게 하는 것**이다. 메서드 경계가 사라져야 상수 전파, 이스케이프 분석, 널 체크 제거가 호출자와 피호출자에 걸쳐 동작한다.

C2 의 인라이닝 결정 기준이다.

| 플래그 | 기본값 | 의미 |
|---|---|---|
| `MaxInlineSize` | 35 bytes | 호출 빈도 무관 무조건 인라인 |
| `FreqInlineSize` | 325 bytes | 자주 불리는(hot) 메서드 인라인 한도 |
| `MaxInlineLevel` | 15 | 중첩 깊이 |
| `InlineSmallCode` | 2500 bytes | 컴파일된 코드 크기 한도 |

**35 바이트**는 getter/setter 를 잡는 크기다. 필드 하나 읽고 반환하는 코드는 5~10 바이트다. **325 바이트**는 그보다 큰 메서드도 자주 불리면 인라인한다는 뜻이다. 메서드를 작게 쓰라는 통념의 정량적 근거가 이 숫자들이다.

**다형성 처리**가 인라이닝의 핵심 난제다. 인터페이스 호출은 대상이 정해지지 않는다.

```java
interface Handler { void handle(Request r); }

for (Handler h : handlers) {
	h.handle(req);  // 어느 구현체?
}
```

C2 는 Tier 3 프로파일을 보고 세 갈래로 대응한다.

**Monomorphic (구현체 1종)** — 가장 좋다. 프로파일이 "여기서는 항상 `JsonHandler` 였다" 를 보고하면, C2 는 타입 가드를 넣고 직접 인라인한다.

```
if (h.getClass() != JsonHandler.class) deoptimize();
// JsonHandler.handle 본문을 그대로 삽입
```

**Bimorphic (2종)** — 가드 두 개를 넣고 둘 다 인라인한다.

**Megamorphic (3종 이상)** — 포기하고 vtable/itable 호출로 둔다. 인라인이 없으므로 후속 최적화도 전부 막힌다.

이것이 "구현체가 많은 인터페이스는 느리다" 의 실체다. 다만 **호출 지점(call site)별로 판정**한다는 점이 중요하다. 인터페이스 전체 구현체가 10개여도, 특정 루프에서 항상 한 종류만 흐르면 그 지점은 monomorphic 이다.

Class Hierarchy Analysis(CHA)는 다른 경로다. 로드된 클래스 전체를 보고 "이 인터페이스의 구현체가 현재 하나뿐" 이면 프로파일 없이도 직접 호출로 바꿔다. 나중에 두 번째 구현체가 로드되면 컴파일된 코드를 무효화한다. `Dependencies` 메커니즘이 이를 추적한다.

인라이닝 진단은 다음으로 한다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -XX:+PrintCompilation -jar app.jar
```

```
@ 12   com.example.Service::process (45 bytes)   inline (hot)
  @ 5    com.example.Repo::find (12 bytes)       inline (hot)
  @ 21   com.example.Mapper::map (380 bytes)     too big
  @ 33   com.example.Handler::handle (28 bytes)  failed to inline: virtual call
```

`too big` 은 `FreqInlineSize` 초과, `virtual call` 은 megamorphic 이다. 성능 문제가 있는 핫 패스에서 이 로그를 보면 대체로 원인이 특정된다.

## 5. 이스케이프 분석 — 세 등급의 결과

**이스케이프 분석(EA)** 은 "이 객체 참조가 생성 메서드/스레드를 벗어나는가" 를 판정한다. JDK 6u23 부터 기본 활성(`-XX:+DoEscapeAnalysis`)이다.

세 등급이 있다.

| 등급 | 의미 | 가능한 최적화 |
|---|---|---|
| NoEscape | 메서드를 벗어나지 않음 | 스칼라 치환, 락 제거, 스택 할당 |
| ArgEscape | 인자로 전달되나 스레드는 안 벗어남 | 락 제거 |
| GlobalEscape | 필드/반환/스레드 공유 | 없음 |

**결정적 전제 조건**: EA 는 **인라이닝 이후**에 동작한다. 메서드 경계가 남아 있으면 C2 는 피호출자가 참조를 어디에 저장하는지 알 수 없어 보수적으로 GlobalEscape 로 판정한다. 인라이닝이 실패하면 EA 도 실패한다. §4 와 §5 가 연결되는 지점이다.

**스칼라 치환(Scalar Replacement)** 이 EA 의 주 산출물이다. 흔히 "스택 할당" 으로 설명되지만 정확하지 않다. HotSpot 은 객체를 스택에 통째로 올리지 않는다. **객체를 해체해 필드를 개별 지역 변수(레지스터)로 만든다.**

```java
public double distance(double x1, double y1, double x2, double y2) {
	Point p1 = new Point(x1, y1);   // NoEscape
	Point p2 = new Point(x2, y2);   // NoEscape
	return p1.distanceTo(p2);
}
```

인라이닝 후 C2 가 보는 것은 이렇다.

```java
// Point 객체가 아예 사라진다
double dx = x1 - x2;
double dy = y1 - y2;
return Math.sqrt(dx * dx + dy * dy);
```

`new Point` 가 두 번 사라졌다. 힙 할당 없음, GC 압력 없음, 필드 접근 없음. 전부 레지스터 연산이다.

**락 제거(Lock Elision)** 는 두 번째 산출물이다.

```java
public String join(List<String> items) {
	StringBuffer sb = new StringBuffer();   // synchronized 메서드를 가진 레거시 클래스
	for (String s : items) {
		sb.append(s);                        // synchronized
	}
	return sb.toString();
}
```

`sb` 는 NoEscape 다. 다른 스레드가 접근할 수 없으므로 모든 `synchronized` 가 제거된다. `StringBuffer` 와 `StringBuilder` 의 성능 차이가 마이크로벤치마크에서 사라지는 이유가 이것이다. 다만 `sb` 가 필드에 저장되거나 반환되면 GlobalEscape 가 되어 락이 살아난다.

**EA 가 실패하는 조건**을 아는 것이 실무적으로 더 중요하다.

```java
// 1) 인라이닝 실패 — 가장 흔한 원인
Point p = new Point(x, y);
megamorphicCall(p);   // 인라인 안 됨 → ArgEscape 이상

// 2) 반환 — GlobalEscape
Point create() { return new Point(1, 2); }

// 3) 필드 저장 — GlobalEscape
this.cache = new Point(1, 2);

// 4) 배열 인덱스가 상수가 아님 — 스칼라 치환 불가
int[] arr = new int[10];
arr[i] = 1;   // i 가 컴파일 타임 상수여야 치환 가능

// 5) 크기가 큰 객체 — EliminateAllocationArraySizeLimit(기본 64) 초과
int[] big = new int[1000];   // 치환 안 됨
```

4번이 특히 함정이다. 지역 배열이 스칼라 치환되려면 크기가 상수여야 하고 인덱스도 상수로 접힐 수 있어야 한다.

진단은 다음으로 한다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations -jar app.jar

# 대조 실험 — 끄고 돌려서 차이를 본다
java -XX:-DoEscapeAnalysis -jar app.jar
java -XX:-EliminateAllocations -jar app.jar
```

`-XX:-DoEscapeAnalysis` 를 끄고 처리량이 눈에 띄게 떨어지면 EA 가 실제로 기여하고 있다는 증거다. 변화가 없으면 EA 가 작동하지 않고 있으므로 인라이닝부터 확인한다.

가장 정확한 확인은 할당 프로파일러다.

```bash
# async-profiler
./profiler.sh -e alloc -d 30 -f alloc.html <pid>
```

소스에 `new` 가 있는데 alloc 프로파일에 안 나타나면 스칼라 치환이 성공한 것이다.

## 6. 역최적화 — 공격적 최적화의 안전망

C2 의 최적화 상당수는 **가정에 기반한 투기(speculation)** 다. 가정이 깨지면 되돌려야 한다. 이것이 **역최적화(Deoptimization)** 다.

```java
public int process(Shape s) {
	return s.area();   // 프로파일: 지금까지 항상 Circle
}
```

C2 는 `Circle.area()` 를 인라인하고 앞에 가드를 넣는다.

```
if (s.getClass() != Circle.class) {
	deoptimize(reason = class_check, action = reinterpret);
}
// Circle.area 본문
```

`Square` 가 처음 들어오면 가드가 걸려 역최적화된다. 컴파일된 프레임을 인터프리터 프레임으로 되돌리고(uncommon trap), 인터프리터가 이어서 실행하며, 프로파일이 갱신되고 재컴파일이 예약된다.

주요 역최적화 사유다.

| Reason | 원인 |
|---|---|
| `class_check` | 타입 가드 실패 (다형성 가정 깨짐) |
| `null_check` | 널이 아니라 가정했는데 널 |
| `unstable_if` | 절대 안 간다고 본 분기로 진입 |
| `bimorphic` | 2종 가정 깨짐 |
| `unloaded` | 미로드 클래스 참조 도달 |
| `unreached` | 도달 불가로 본 코드에 도달 |

Action 은 세 가지다.

- `reinterpret` — 인터프리터로 복귀, 재컴파일 예약
- `make_not_entrant` — 기존 코드 진입 금지, 재컴파일
- `make_not_compilable` — 컴파일 포기 (반복 실패 시)

**역최적화 폭풍**이 실무 문제다. 같은 지점에서 역최적화 → 재컴파일 → 다시 역최적화가 반복되면 처리량이 무너진다. `PerMethodRecompilationCutoff`(기본 400)를 넘으면 그 메서드는 영구 인터프리터 실행이 된다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=compile.log -jar app.jar
grep "uncommon_trap" compile.log | head -50
```

```xml
<uncommon_trap thread='23' reason='class_check' action='maybe_recompile'
               debug_id='0' compile_id='1234' compiler='c2' level='4'
               stamp='45.123' method='com/example/Service process (12 bytes)'/>
```

JFR 로 보는 것이 더 편하다.

```bash
java -XX:StartFlightRecording=duration=60s,filename=rec.jfr -jar app.jar
jfr summary rec.jfr | grep -i deopt
jfr print --events jdk.Deoptimization rec.jfr | head -40
```

**전형적 시나리오**: 애플리케이션이 기동 후 워밍업을 마치고 안정적으로 돌다가, 특정 API 가 처음 호출되면서 새 구현체가 로드된다. 그 순간 monomorphic 가정이 깨져 대량 역최적화가 발생하고 latency 스파이크가 찍힌다. 부하 테스트에서 초반 스파이크 후 안정화되는 곡선이 이 현상이다. 대응은 워밍업 시나리오에 모든 코드 경로를 포함시키는 것이다.

## 7. Code Cache — 놓치기 쉬운 한계

컴파일된 기계어는 **Code Cache** 라는 별도 네이티브 메모리 영역에 저장된다. 힙이 아니다.

```bash
java -XX:+PrintCodeCache -XX:ReservedCodeCacheSize=256m -jar app.jar
```

기본값은 티어드 컴파일 활성 시 **240MB** 다. 세 세그먼트로 나뉘다(`SegmentedCodeCache`, JDK 9+).

| 세그먼트 | 내용 | 특성 |
|---|---|---|
| non-nmethods | 인터프리터, 어댑터, 스텁 | 절대 비워지지 않음 |
| profiled nmethods | Tier 2/3 코드 | 수명 짧음 |
| non-profiled nmethods | Tier 1/4 코드 | 수명 김 |

분리 이유는 단편화 방지와 스캔 효율이다. 수명이 다른 코드가 섞이면 Tier 3 코드가 비워질 때 구멍이 뚫려 큰 C2 코드가 들어갈 자리를 못 찾는다.

**Code Cache 가 가득 차면 JIT 컴파일이 영구 중단된다.**

```
CodeCache is full. Compiler has been disabled.
Try increasing the code cache size using -XX:ReservedCodeCacheSize=
```

이 로그가 뜨면 애플리케이션 성능이 인터프리터 수준으로 떨어진다. 대형 모놀리식 Spring 애플리케이션, 동적 프록시를 많이 만드는 시스템, 코드 생성 프레임워크를 쓰는 경우에 발생한다.

모니터링은 다음으로 한다.

```java
// Actuator 로 노출되는 메모리 풀
ManagementFactory.getMemoryPoolMXBeans().stream()
		.filter(pool -> pool.getName().contains("CodeCache")
				|| pool.getName().contains("CodeHeap"))
		.forEach(pool -> System.out.printf("%s: %d / %d MB%n",
				pool.getName(),
				pool.getUsage().getUsed() / 1024 / 1024,
				pool.getUsage().getMax() / 1024 / 1024));
```

Micrometer 는 `jvm.memory.used{area="nonheap",id="CodeHeap 'non-nmethods'"}` 등으로 자동 수집한다. **사용률 90% 알람**을 걸어두는 것이 실무 권장이다.

## 8. 실무 정리 — 무엇을 하고 무엇을 하지 말 것인가

**할 것**

메서드를 작게 유지한다. 325 바이트라는 구체적 기준이 있다. 핫 패스의 메서드가 이를 넘으면 인라이닝이 막히고 EA 도 함께 막힌다.

호출 지점의 다형성을 낮춘다. 인터페이스 구현체 수가 아니라 특정 호출 지점을 흐르는 타입 수가 문제다. 핫 루프에서 여러 타입이 섞이면 그 지점만 별도 처리를 고려한다.

워밍업을 실측 기반으로 설계한다. `-XX:+PrintCompilation` 출력이 잔로들 때까지가 워밍업이다. 부하 테스트 결과를 이 시점 이후로만 집계한다.

Code Cache 사용률을 모니터링한다. 90% 알람을 건다.

역최적화를 JFR 로 주기적으로 확인한다. 특정 메서드가 반복 등장하면 설계 문제다.

**하지 말 것**

`-XX:CompileThreshold` 같은 임계값을 근거 없이 조정하지 않는다. 티어드 컴파일에서는 큐 길이가 곱해지므로 단순 조정이 예상과 다르게 작동한다.

`-XX:-TieredCompilation` 을 운영에 쓰지 않는다. 기동이 크게 느려진다.

마이크로벤치마크를 직접 짜지 않는다. JIT 가 죽은 코드를 제거하거나 상수 폴딩해 버려 측정이 무의미해진다. **JMH** 를 쓴다.

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = "-XX:+UnlockDiagnosticVMOptions")
public void measureDistance(Blackhole bh) {
	bh.consume(distance(1.0, 2.0, 3.0, 4.0));  // Blackhole 로 DCE 방지
}
```

`Blackhole` 이 없으면 C2 가 결과를 안 쓴다고 판단해 계산 전체를 제거한다. `@Fork` 로 JVM 을 새로 띄우는 것도 필수다. 같은 JVM 에서 여러 벤치마크를 돌리면 프로파일이 오염되어 앞 벤치마크가 뒤 벤치마크의 인라이닝 결정을 망친다.

**측정 없는 최적화는 하지 않는다.** JIT 는 대부분의 경우 사람이 손으로 하는 것보다 잘한다. 손대야 할 지점은 프로파일러가 지목한 곳뿐이다.

## 참고

- OpenJDK Wiki — HotSpot Compiler (https://wiki.openjdk.org/display/HotSpot/Compiler)
- Aleksey Shipilёv — JVM Anatomy Quarks (https://shipilev.net/jvm/anatomy-quarks/)
- Cliff Click — A Simple Graph-Based Intermediate Representation (Sea of Nodes 원논문)
- Choi et al. — Escape Analysis for Java (OOPSLA '99)
- JEP 165: Compiler Control
- JMH — Java Microbenchmark Harness (https://github.com/openjdk/jmh)
- async-profiler (https://github.com/async-profiler/async-profiler)
