Notion 원본: https://app.notion.com/p/3e75a06fd6d3818cb775d9736b00bca6

# JVM JIT 컴파일러 C1/C2 티어링 컴파일과 인라이닝·탈출분석 최적화

> 2026-09-26 신규 주제 · 확장 대상: JAVA · JVM 클래스로더

## 학습 목표

- HotSpot JVM이 인터프리터에서 C1, C2로 실행 계층을 전환하는 티어드 컴파일 정책을 단계별로 구분한다
- 메서드 인라이닝의 후보 선정 기준과 인라이닝 실패 원인을 `-XX:+PrintInlining`으로 직접 관찰한다
- 탈출 분석(Escape Analysis)이 스칼라 대체(Scalar Replacement)와 락 제거(Lock Elision)로 이어지는 과정을 코드로 확인한다
- 역최적화(Deoptimization)가 발생하는 조건과 그것이 애플리케이션 성능에 미치는 실질적 비용을 설명한다

## 1. 인터프리터에서 컴파일 코드로: 왜 즉시 컴파일하지 않는가

HotSpot JVM은 클래스가 로드되자마자 모든 메서드를 네이티브 코드로 컴파일하지 않는다. 대신 처음에는 바이트코드 인터프리터로 실행하면서 각 메서드의 호출 횟수와 루프 백엣지(backedge) 횟수를 카운터로 누적한다. 이는 메서드 대부분이 단 한 번 또는 소수만 호출되고, 실제 실행 시간의 대부분을 차지하는 것은 소수의 "핫(hot)" 메서드라는 경험적 관찰(파레토 법칙에 가까운 분포)에 기반한 설계다. 모든 메서드를 즉시 최적화 컴파일하면 컴파일 자체에 드는 CPU와 메모리 비용이 프로그램 시작 시간을 크게 늘리므로, JIT은 "실제로 뜨거운 코드에만 비싼 최적화를 투자한다"는 원칙을 따른다.

## 2. 티어드 컴파일의 5단계

HotSpot은 C1(클라이언트 컴파일러)과 C2(서버 컴파일러)를 함께 쓰는 티어드 컴파일(Tiered Compilation)을 기본 정책으로 사용한다. 각 메서드는 다음 단계를 거치며 승격된다.

<table header-row="true"><tr><td>티어</td><td>실행 방식</td><td>프로파일링</td><td>특징</td></tr><tr><td>Tier 0</td><td>인터프리터</td><td>호출/백엣지 카운트만</td><td>가장 느림, 즉시 시작</td></tr><tr><td>Tier 1</td><td>C1 컴파일, 프로파일링 없음</td><td>없음</td><td>단순 최적화 후 최고 속도로 실행(간단한 메서드가 여기서 멈추기도 함)</td></tr><tr><td>Tier 2</td><td>C1 컴파일, 제한된 프로파일링</td><td>호출 카운트만</td><td>과도기 단계, 드물게 사용</td></tr><tr><td>Tier 3</td><td>C1 컴파일, 전체 프로파일링</td><td>타입, 분기, 예외 빈도</td><td>C2로 넘어가기 전 데이터 수집 단계</td></tr><tr><td>Tier 4</td><td>C2 컴파일, 최고 수준 최적화</td><td>지속적 재프로파일링</td><td>인라이닝, 탈출분석, 루프 최적화 총동원</td></tr></table>

일반적인 경로는 Tier 0 → Tier 3 → Tier 4다. Tier 3에서 수집한 프로파일 데이터(호출 지점에서 관찰된 실제 타입 분포, 분기 확률 등)가 있어야 C2가 투기적 최적화(speculative optimization)를 안전하게 적용할 수 있기 때문에, Tier 1로 바로 건너뛰는 경로보다 Tier 3를 거치는 경로가 최종 성능이 더 좋은 경우가 많다. 다만 컴파일 큐가 밀려 있거나 C1이 이미 충분히 빠른 단순 메서드(게터/세터 등)는 Tier 1에서 멈추는 것이 오히려 효율적이라 JIT이 상황에 따라 경로를 조정한다.

```bash
java -XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions MyApp
# 출력 예: "  123  456   3       com.example.OrderService::calculate (42 bytes)"
#          시각  ID  티어              메서드명 (바이트코드 크기)
```

## 3. 메서드 인라이닝의 후보 선정과 실패 진단

인라이닝은 호출 지점에 피호출 메서드의 본문을 직접 삽입해 호출 오버헤드를 제거하고, 이후 최적화(상수 전파, 데드 코드 제거)가 호출 경계를 넘어 적용될 수 있게 만드는 핵심 최적화다. C2는 인라이닝 여부를 바이트코드 크기, 호출 빈도, 재귀 깊이 등 여러 휴리스틱으로 판단한다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -jar app.jar
```

```
@ 12   com.example.OrderService::calculate (42 bytes)   inline (hot)
@ 20   com.example.OrderService::validate (312 bytes)   too large
@ 8    com.example.Repository::findById (156 bytes)     not inlineable (virtual/interface call; call site not monomorphic)
```

`too large`는 인라이닝 대상 메서드의 바이트코드 크기가 `-XX:FreqInlineSize`(기본 325바이트, 핫 메서드 기준) 또는 `-XX:MaxInlineSize`(기본 35바이트, 일반 메서드 기준)를 초과했다는 뜻이다. `not inlineable`은 호출 지점이 다형적(polymorphic)이어서 어떤 구현체가 호출될지 확정할 수 없는 경우 흔히 발생한다. C2는 이 문제를 완화하기 위해 클래스 계층 분석(Class Hierarchy Analysis)으로 실제 로드된 서브클래스가 하나뿐이면 단형(monomorphic) 호출로 취급해 인라이닝하되, 나중에 새로운 서브클래스가 로드되면 이 가정을 무효화(deoptimize)하는 투기적 접근을 쓴다. 이 때문에 인터페이스 기반 설계에서 구현체가 하나뿐인 동안에는 매우 빠르다가, 두 번째 구현체가 클래스로더에 의해 로드되는 순간 갑자기 역최적화가 발생해 일시적으로 느려지는 현상을 겪을 수 있다.

## 4. 탈출 분석과 스칼라 대체

탈출 분석은 객체가 생성된 메서드의 스코프를 벗어나 다른 스레드나 힙의 다른 객체에서 참조될 가능성이 있는지를 정적으로 판정하는 최적화다. 만약 객체가 메서드를 벗어나지 않는다는 것이 증명되면(non-escaping), JVM은 그 객체를 힙에 할당하지 않고 개별 필드를 레지스터나 스택에 분산 배치하는 스칼라 대체를 적용할 수 있다.

```java
public long sumPoints(int[] xs, int[] ys) {
    long sum = 0;
    for (int i = 0; i < xs.length; i++) {
        Point p = new Point(xs[i], ys[i]); // non-escaping 후보
        sum += p.x() + p.y();
    }
    return sum;
}

record Point(int x, int y) {}
```

`Point` 인스턴스는 루프 내부에서 생성되어 즉시 사용되고 메서드 밖으로 반환되지도, 다른 객체에 저장되지도 않는다. C2가 탈출 분석으로 이를 확인하면, 실제로는 `Point` 객체를 힙에 할당하지 않고 `x`와 `y` 두 정수를 각각 별도의 레지스터 값처럼 다뤄 GC 압력과 할당 비용을 제거한다. 이 최적화가 성립하려면 인라이닝이 먼저 이루어져 호출자 프레임 안에서 객체의 모든 사용처가 보여야 하므로, 탈출 분석은 인라이닝의 성공 여부에 강하게 종속적이다. 인라이닝이 실패하는 다형 호출 지점 뒤에서 생성된 객체는 탈출 분석 대상에서 제외되는 경우가 많다.

## 5. 락 제거(Lock Elision)

탈출 분석의 또 다른 응용은 동기화 제거다. 객체가 스레드를 벗어나지 않는다는 것이 증명되면, 그 객체에 대한 `synchronized` 블록은 다른 스레드와 경쟁할 가능성이 없으므로 락 획득/해제 연산 자체를 제거할 수 있다.

```java
public String buildKey(String a, String b) {
    StringBuilder sb = new StringBuilder(); // 지역 변수, 탈출하지 않음
    sb.append(a).append(b);
    return sb.toString();
}
```

`StringBuilder`의 내부 구현은 동기화되어 있지 않지만, 만약 `StringBuffer`(동기화된 버전)를 지역 변수로만 사용했다면 C2는 탈출 분석으로 락이 불필요함을 증명하고 락 획득 명령어 자체를 컴파일된 코드에서 생략할 수 있다. 다만 이는 어디까지나 JIT이 워밍업을 거쳐 충분히 최적화된 이후에 나타나는 효과이며, 애플리케이션 설계 단계에서 "JIT이 알아서 제거해주겠지"라고 기대하고 불필요한 동기화를 남발하는 것은 워밍업 이전 구간의 성능과 코드 가독성 모두에 좋지 않다.

## 6. 역최적화(Deoptimization)의 실제 비용

C2의 많은 최적화는 "지금까지 관찰된 프로파일이 앞으로도 유지될 것"이라는 투기적 가정 위에 서 있다. 이 가정이 깨지면(예: 단형 호출로 가정했는데 새 서브클래스가 로드됨, 배열 경계를 벗어나지 않는다고 가정했는데 예외 발생) JVM은 해당 컴파일 코드를 즉시 폐기하고 인터프리터 실행으로 되돌아가는 역최적화를 수행한다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintDeoptimizationDetails -jar app.jar
```

역최적화 자체는 스택 프레임을 인터프리터가 이해할 수 있는 형태로 재구성해야 하므로 마이크로초 단위의 비용이 들지만, 진짜 비용은 그 다음이다. 역최적화된 메서드는 처음부터 다시 Tier 0에서 프로파일링을 시작해야 하며, 만약 같은 가정이 반복적으로 깨지는 상황(예: 한 호출 지점에 번갈아 나타나는 두 개의 구현 클래스)이면 JVM은 해당 최적화를 아예 포기하고 "uncommon trap" 처리로 그 지점을 영구히 컴파일하지 않기로 결정할 수 있다. 이런 패턴이 애플리케이션의 핫 패스에서 반복되면 GC 로그나 CPU 프로파일만으로는 원인을 찾기 어렵고, `-XX:+PrintCompilation`과 `-XX:+TraceDeoptimization`을 함께 켜서 특정 메서드가 반복적으로 컴파일과 역최적화를 오가는지(이를 "flip-flopping"이라 부른다) 확인해야 한다.

## 7. 실전 진단 워크플로우

<table header-row="true"><tr><td>증상</td><td>1차 확인 도구</td><td>대응 방향</td></tr><tr><td>워밍업 이후에도 느림</td><td>PrintInlining으로 인라이닝 실패 지점 확인</td><td>다형 호출 지점 단형화, 메서드 크기 축소</td></tr><tr><td>간헐적 지연 스파이크</td><td>PrintCompilation + 역최적화 로그</td><td>가정이 깨지는 조건 제거, 타입 다양성 축소</td></tr><tr><td>객체 생성이 많은데 GC 압력이 낮음</td><td>정상(탈출 분석 성공 신호)</td><td>추가 조치 불필요</td></tr><tr><td>단순 메서드인데 컴파일이 안 됨</td><td>PrintCompilation의 티어 확인</td><td>호출 빈도 부족, 워밍업 데이터 재확인</td></tr></table>

실무에서는 벤치마크 프레임워크(JMH)로 마이크로벤치마크를 작성할 때 이 워밍업 특성을 무시하면 완전히 잘못된 결론에 도달하기 쉽다. JMH가 기본적으로 워밍업 반복(fork당 여러 번의 워밍업 이터레이션)을 강제하는 이유가 바로 이 티어드 컴파일과 탈출 분석이 안정 상태에 도달할 시간을 벌어주기 위함이며, 워밍업 없이 측정한 벤치마크는 대부분 Tier 0~1 수준의 성능만 반영해 실제 운영 환경의 성능과 크게 어긋난다.

## 8. 루프 최적화와 인트린식(Intrinsic) 치환

C2는 인라이닝과 탈출 분석 외에도 루프에 특화된 최적화 세트를 갖고 있다. 대표적으로 루프 언롤링(loop unrolling)은 루프 반복 조건 검사 횟수를 줄이기 위해 루프 본문을 여러 번 복제하고, 범위 검사 제거(Range Check Elimination)는 배열 인덱스가 루프 전체에서 경계를 벗어나지 않음을 증명해 매 반복마다 수행되던 배열 경계 검사를 루프 진입 전 단 한 번의 검사로 대체한다.

```java
for (int i = 0; i < arr.length; i++) {
    sum += arr[i]; // 매 반복 경계 검사가 필요해 보이지만
}
// C2는 루프 불변 조건 분석으로 i가 항상 [0, arr.length) 범위임을 증명하고
// 개별 경계 검사를 제거한 뒤 루프를 4~8회 단위로 언롤링할 수 있다
```

또한 특정 표준 라이브러리 메서드는 바이트코드를 그대로 컴파일하는 대신 손으로 작성된 고도로 최적화된 네이티브 구현(인트린식)으로 통째로 치환된다. `Math.sqrt`, `System.arraycopy`, `String.equals`, `Arrays.fill` 같은 메서드가 대표적이며, 이런 메서드는 CPU의 SIMD 명령어(SSE/AVX)를 직접 활용하도록 JVM 내부에 미리 구현되어 있어 일반적인 인라이닝 경로로는 도달할 수 없는 성능을 낸다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics -jar app.jar
```

인트린식으로 치환되는 메서드 목록은 JVM 버전과 CPU 아키텍처(AVX2, AVX-512 지원 여부)에 따라 달라지므로, 벡터화에 민감한 수치 연산 코드에서는 실행 환경의 CPU 명령어 집합 지원 여부가 실제 처리량에 직접 영향을 준다. 이 때문에 동일한 JVM 버전이라도 컨테이너 오케스트레이션 환경에서 노드마다 다른 세대의 CPU가 배정되면 동일 코드의 처리량 벤치마크가 유의미하게 갈리는 경우가 있어, 성능에 민감한 배치 작업은 노드 어피니티(affinity)를 CPU 세대 기준으로 고정하는 것이 실전에서 쓰이는 완화책이다.

## 참고

- Oracle, "The Java HotSpot Performance Engine Architecture" 백서
- Cliff Click, "A Brief Overview of the HotSpot JVM" 발표 자료
- OpenJDK Wiki, "PerformanceTechniques" 및 "EscapeAnalysis" 페이지
- Aleksey Shipilëv, JMH 공식 문서 중 워밍업 관련 섹션
