Notion 원본: https://www.notion.so/37a5a06fd6d3818e874be02a3d1434a7

# Java Flight Recorder(JFR)와 async-profiler Wall/CPU 프로파일링 내부구조

> 2026-06-09 신규 주제 · 확장 대상: JAVA

## 학습 목표

- JFR의 이벤트 기반 기록 모델과 낮은 오버헤드의 원리를 설명한다
- safepoint bias가 전통 샘플링 프로파일러를 왜 왜곡하는지 메커니즘을 이해한다
- async-profiler가 AsyncGetCallTrace + perf_events로 bias를 회피하는 방식을 분석한다
- CPU vs Wall-clock 프로파일링을 구분하고 Flame Graph로 병목을 진단한다

## 1. 프로파일링의 두 축: CPU와 Wall-clock

성능 진단은 "CPU를 누가 태우는가"와 "시간이 어디서 흐르는가"를 구분해야 한다. CPU 프로파일링은 실제로 CPU 사이클을 소비하는 코드를, Wall-clock 프로파일링은 대기(I/O, 락, sleep) 포함 실제 경과 시간을 측정한다. CPU 바운드 병목은 CPU 프로파일로, 레이턴시 문제(대기 지배적)는 Wall-clock으로 봐야 한다. 둘을 혼동하면 "CPU는 한가한데 느린" 현상을 영원히 못 잡는다.

## 2. JFR: 이벤트 기반 기록

JFR(JDK Flight Recorder)은 JVM 내부에 내장된 이벤트 레코더다. GC, 스레드 상태, 락 경합, 할당, 메서드 샘플, I/O 등 수백 종의 이벤트를 링 버퍼에 기록하고 주기적으로 파일로 flush 한다. 핵심은 JVM이 이미 내부적으로 아는 정보를 구조화된 바이너리(.jfr)로 흘리므로 오버헤드가 매우 낮다는 점이다(통상 1% 내외).

```bash
# 실행 중 프로세스에 60초 기록 시작 (JDK 11+ jcmd)
jcmd <pid> JFR.start name=prof settings=profile duration=60s filename=app.jfr

# 시작 시점부터 기록
java -XX:StartFlightRecording=duration=120s,filename=startup.jfr -jar app.jar

# 기록 덤프/중지
jcmd <pid> JFR.dump name=prof filename=snapshot.jfr
jcmd <pid> JFR.stop name=prof
```

`settings=profile`은 `default`보다 샘플 주기를 초초히 해 더 자세하지만 오버헤드가 약간 높다. 커스텀 이벤트도 `jdk.jfr.Event`를 상속해 정의할 수 있어 애플리케이션 도메인 메트릭을 같은 타임라인에 합칠 수 있다.

```java
import jdk.jfr.*;

@Name("com.app.OrderProcessed")
@Label("Order Processed")
@Category("Business")
class OrderProcessedEvent extends Event {
    @Label("Order Id") long orderId;
    @Label("Amount") long amount;
}

// 사용
OrderProcessedEvent e = new OrderProcessedEvent();
e.begin();
// ... 처리 ...
e.orderId = 1000; e.amount = 5000;
e.commit(); // 활성 기록이 있을 때만 디스크에 기록
```

## 3. safepoint bias: 전통 샘플러의 함정

JVMTI 기반 전통 프로파일러(VisualVM의 일부 모드 등)는 스택을 얻기 위해 스레드를 safepoint에 멈춘다. 문제는 JIT 컴파일된 코드가 safepoint poll을 메서드 경계·루프 백엣지 등 특정 지점에만 둔다는 것이다. 그 결과 샘플이 "safepoint에 도달하기 쉬운 위치"로 쏠려, 실제 핫스팟이 아니라 safepoint 근처 코드가 과대 표집된다. 이를 safepoint bias라 하며, 인라인된 타이트 루프의 진짜 비용이 호출자 쪽으로 전가되어 보인다.

## 4. async-profiler: AsyncGetCallTrace로 bias 회피

async-profiler는 두 가지 기법으로 safepoint bias를 회피한다. (1) Linux `perf_events`로 커널이 하드웨어/소프트웨어 이벤트(CPU cycle, page fault 등) 기반 인터럽트를 발생시켜 임의 시점에 샘플링한다. (2) 그 시점의 자바 스택은 HotSpot의 비공식 API인 `AsyncGetCallTrace`로 얻는다. 이 API는 safepoint를 요구하지 않고 시그널 핸들러 안에서 호출 가능해, 스레드를 멈추지 않고 임의 명령어 위치의 스택을 떠난다.

```bash
# CPU 프로파일 (perf_events 기반, 기본)
./asprof -d 60 -e cpu -f cpu.html <pid>

# 할당 프로파일 (어디서 메모리를 많이 만드는가)
./asprof -d 60 -e alloc -f alloc.html <pid>

# 락 경합 프로파일
./asprof -d 60 -e lock -f lock.html <pid>
```

`perf_events`는 커널 권한이 필요하므로 컨테이너에서는 `--cap-add SYS_ADMIN` 또는 `perf_event_paranoid` 설정 조정이 필요하다. 권한이 없으면 async-profiler는 `itimer` 모드로 폴백하는데, 이는 perf 대비 정밀도가 낮지만 여전히 safepoint bias는 없다.

## 5. Wall-clock 모드와 대기 시간 진단

CPU 모드는 CPU를 쓰는 스레드만 샘플링하므로, 대기 중(blocked, I/O) 스레드는 보이지 않는다. 레이턴시 문제는 wall 모드로 봐야 한다.

```bash
# Wall-clock: 모든 스레드를 실시간 기준으로 샘플링 (대기 포함)
./asprof -d 60 -e wall -t -f wall.html <pid>
# -t : 스레드별로 분리
```

예를 들어 어떤 API가 느린데 CPU 프로파일은 텅 비어 있다면, wall 프로파일에서 `socketRead`나 `park`(락 대기)가 스택 상단을 차지하는 모습을 보게 된다. 이것이 "DB 응답 대기"인지 "락 경합"인지에 따라 처방이 완전히 달라진다.

| 모드 | 측정 대상 | 적합한 문제 |
|---|---|---|
| cpu | CPU 사이클 소비 | 높은 CPU 사용률, 연산 병목 |
| wall | 실시간 경과(대기 포함) | 레이턴시, I/O·락 대기 |
| alloc | 객체 할당 위치 | GC 압박, 메모리 폭증 |
| lock | 락 경합 시간 | 모니터/락 병목 |

## 6. Flame Graph 읽기

두 도구 모두 Flame Graph를 출력한다. x축은 알파벳 정렬된 스택 폭(샘플 비율, 시간 순서 아님), y축은 스택 깊이다. 넓은 프레임 = 자주 표집된 = 시간을 많이 쓰는 코드다. 진단의 핵심은 "넓은 plateau(고원)"를 찾는 것이다. 잎(leaf) 프레임이 넓으면 그 메서드 자체가 비싸고, 중간 프레임만 넓고 잎이 분산되면 그 메서드의 자식 호출들이 합쳐서 비싼 것이다.

```
# 읽는 순서
1) 가장 넓은 최상위(잎) 프레임 = self time 최대 → 직접 최적화 대상
2) 넓은 중간 프레임 = 누적 비용 큰 호출 경로 → 호출 횟수/구조 점검
3) JIT 인라인으로 사라진 프레임 주의 (async-profiler 는 인라인 표시 옵션 제공)
```

JFR도 JDK Mission Control이나 `jfr print` / 변환 도구로 Flame Graph를 만들 수 있어, 두 도구의 출력은 상호 보완적이다.

## 7. JFR vs async-profiler 선택

JFR은 JVM 내장이라 별도 설치·권한이 거의 필요 없고, GC·할당·락·커스텀 이벤트를 하나의 타임라인에 통합한다. 상시 켜둔(always-on) 운영 프로파일링에 적합하다. async-profiler는 safepoint bias 없는 정밀한 CPU/wall 프로파일과 네이티브 스택(JNI, 커널)까지 보여줘, 특정 병목을 깊게 파고들 때 강하다. 실무에서는 JFR로 상시 관찰 → 이상 구간을 async-profiler로 정밀 분석하는 조합이 흔하다.

```bash
# async-profiler 도 JFR 포맷 출력 가능 → JMC 로 통합 분석
./asprof -d 60 -e cpu,alloc,lock -o jfr -f combined.jfr <pid>
```

## 8. 할당 프로파일링과 TLAB

CPU·wall만큼 중요한 것이 할당 프로파일링이다. GC 압박의 근원은 "어디서 객체를 많이 만드는가"인데, JFR의 `jdk.ObjectAllocationSample`과 async-profiler의 `alloc` 모드가 이를 보여준다. HotSpot은 각 스레드에 TLAB(Thread-Local Allocation Buffer)를 주어 락 없이 포인터 증가만으로 객체를 할당한다. 프로파일러는 TLAB가 새로 채워지는 시점(또는 TLAB 밖 큰 객체 할당)을 샘플링해, 모든 할당을 추적하지 않고도 통계적으로 할당 핫스팟을 잡는다.

```bash
# JFR: 할당 이벤트 샘플 (TLAB 내/외 구분)
jcmd <pid> JFR.start settings=profile filename=alloc.jfr
# jdk.ObjectAllocationSample 이벤트 → JMC 에서 할당 화염 그래프

# async-profiler: 할당 위치별 바이트 수 화염 그래프
./asprof -d 60 -e alloc -f alloc.html <pid>
```

할당 프로파일에서 넓은 프레임은 "그 코드 경로가 GC에 가하는 압력"을 뜻한다. 예를 들어 로그 문자열 연결이나 박싱(`Integer` 오토박싱)이 핫 루프에 있으면 alloc 프로파일에서 즉시 드러난다. 할당을 줄이면(객체 재사용·기본형 사용·스트림 대신 루프) GC 빈도와 일시정지가 함께 감소하므로, CPU 프로파일이 GC 스레드로 가득하다면 alloc 프로파일을 함께 봐야 근본 원인을 짚는다.

## 9. trade-off 정리

프로파일링은 측정 자체가 오버헤드를 부른다. JFR `profile` 설정은 `default`보다 정밀하지만 비용이 높고, async-profiler의 perf 모드는 정밀하지만 커널 권한과 컨테이너 설정을 요구한다. 핵심 원칙은 (1) CPU 문제와 레이턴시 문제를 먼저 구분해 모드를 선택, (2) 상시 관찰은 저비용 JFR, 정밀 분석은 async-profiler, (3) Flame Graph에서 self time과 누적 time을 구분해 진짜 핫스팟을 짚는 것이다. 측정 정밀도와 운영 오버헤드·권한 요구는 trade-off이며, 프로덕션에서는 1% 내외 오버헤드를 유지하는 설정에서 시작해 필요 시 정밀 모드로 단계적으로 좀혀가는 접근이 안전하다.

## 참고

- JDK Flight Recorder 공식 문서 (Oracle JDK / OpenJDK JFR Runtime Guide)
- async-profiler GitHub — README와 wall/cpu/alloc 모드 설명 (https://github.com/async-profiler/async-profiler)
- Nitsan Wakart, "Safepoints: Meaning, Side Effects and Overheads" — safepoint bias 분석
- Brendan Gregg, "Flame Graphs" — 화염 그래프 해석 방법론
