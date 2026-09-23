Notion 원본: https://www.notion.so/3e45a06fd6d381f8a39debf383f48fac

# JVM G1 GC Region 수집 사이클과 Humongous 할당 및 Mixed GC 튜닝

> 2026-09-23 신규 주제 · 확장 대상: JAVA

## 학습 목표

- G1의 Region 분할·RSet·SATB 구조로 Young/Concurrent/Mixed 사이클의 동작을 추적한다
- Humongous 할당이 Region 낭비와 Full GC를 유발하는 경로를 계산으로 확인한다
- `-XX:MaxGCPauseMillis` 가 Eden 크기와 CSet 선택에 미치는 되먹임을 조정한다
- GC 로그(`-Xlog:gc*`)에서 Evacuation Failure·to-space exhausted 를 진단한다

## 1. Region 기반 힙 레이아웃

G1은 힙 전체를 연속 세대로 나누지 않고 동일 크기 Region 으로 쪼갠다. Region 크기는 `-XX:G1HeapRegionSize` 로 지정하지 않으면 초기 힙 기준으로 자동 결정되며, 규칙은 "Region 개수가 2048개에 가깝도록, 1MB~32MB 사이의 2의 거듭제곱" 이다. 8GB 힙이면 8GB/2048 = 4MB 가 선택된다. 32GB 힙이면 16MB, 64GB 이상이면 상한인 32MB 로 고정되고 Region 개수가 2048을 넘어간다.

각 Region 은 시점마다 Eden / Survivor / Old / Humongous / Free 중 하나의 역할을 가진다. 역할이 고정 주소 범위에 묶이지 않기 때문에, Young 영역이 커졌다 작아졌다 하는 것은 단지 Eden 태그가 붙은 Region 수가 변하는 것일 뿐이다. 이 유연성이 G1의 핵심이고, 동시에 "Young 크기를 고정하는 순간 G1의 목표 시간 제어가 꺼진다" 는 부작용의 원인이기도 하다.

```bash
# 현재 JVM이 고른 Region 크기와 개수 확인
java -XX:+PrintFlagsFinal -Xmx8g -version | grep -E "G1HeapRegionSize|MaxGCPauseMillis|G1NewSizePercent"
#   size_t G1HeapRegionSize = 4194304    {product} {ergonomic}
#   uintx  MaxGCPauseMillis = 200        {product} {default}
```

Region 크기는 런타임 성능에 두 가지로 영향을 준다. 첫째, Region 이 작을수록 CSet(Collection Set) 선택 입자가 고와져 목표 pause 를 맞추기 쉽다. 둘째, Region 이 작을수록 Humongous 판정 임계값(Region 크기의 50%)이 낮아져 거대 객체가 늘어난다. 두 효과는 반대 방향이라, 큰 배열을 자주 쓰는 애플리케이션에서 Region 크기를 키우는 튜닝이 유효한 이유가 여기 있다.

## 2. Remembered Set 과 Card Table

Region 단위로 부분 수집을 하려면 "수집하지 않는 Region 에서 수집 대상 Region 을 가리키는 참조" 를 알아야 한다. G1은 이를 Region 마다 RSet(Remembered Set)으로 유지한다. RSet 은 "나를 가리키는 카드들" 의 집합, 즉 inverse 방향 기록이다.

쓰기 배리어는 참조 필드 저장 시 카드(512바이트 단위)를 dirty 로 표시하고, dirty card queue 에 넣는다. Refinement 스레드(`-XX:G1ConcRefinementThreads`)가 이 큐를 비우며 해당 카드를 스캔해 대상 Region 의 RSet 에 등록한다. 큐가 밀리면 mutator 스레드가 직접 refinement 를 수행하는데, 이때 애플리케이션 스레드의 처리량이 눈에 띄게 떨어진다.

RSet 은 밀도에 따라 세 가지 표현을 전환한다. 참조가 적으면 sparse PRT(카드 인덱스 배열), 늘어나면 fine-grained PRT(Region 별 카드 비트맵), 더 늘어나면 coarse 로 승격되어 "이 Region 전체를 스캔하라" 로 퇴화한다. coarse 승격이 많아지면 evacuation 단계의 스캔 비용이 급증한다. `-Xlog:gc+remset+stats=trace` 로 coarse 엔트리 수를 확인할 수 있다.

```
# GC 로그에서 RSet 비용이 지배적인지 확인
[12.345s][info][gc,phases] GC(42)   Scan Heap Roots (ms): Min: 0.1, Avg: 0.8, Max: 12.4
[12.345s][info][gc,phases] GC(42)   Code Root Scan (ms):  Min: 0.0, Avg: 0.1, Max: 0.4
```

`Scan Heap Roots` 의 Max 가 Avg 의 10배 이상이면 특정 워커가 coarse RSet 을 만난 것이다. 이 경우 `-XX:G1RSetRegionEntries` 를 올려 coarse 승격을 늦추거나, 애초에 Old→Young 참조를 만드는 캐시 구조를 손보는 쪽이 근본 해결이다.

## 3. Young 수집과 pause 목표의 되먹임

Young 수집은 Eden + Survivor 전체를 CSet 으로 잡는 STW 복사 수집이다. G1은 이전 수집들의 "Region 당 복사 비용" 통계를 유지하고, `MaxGCPauseMillis` 안에 들어갈 Eden Region 수를 역산해 다음 Young 크기를 정한다. 즉 pause 목표는 힌트가 아니라 Eden 사이징 제어 변수다.

이 되먹임에서 자주 발생하는 실수가 `-Xmn` 이나 `-XX:NewRatio` 를 함께 지정하는 것이다. Young 크기를 고정하면 G1은 pause 목표를 맞출 수단을 잃고, 목표를 초과해도 Eden 을 줄이지 못한다. HotSpot 은 이 조합에서 경고 없이 적응 사이징만 비활성화한다.

```bash
# 안티패턴: Young 고정 + pause 목표 동시 지정
java -Xms8g -Xmx8g -Xmn3g -XX:MaxGCPauseMillis=100 -jar app.jar   # 적응 사이징 무력화

# 권장: 범위만 제한하고 나머지는 G1에 위임
java -Xms8g -Xmx8g \
     -XX:MaxGCPauseMillis=100 \
     -XX:G1NewSizePercent=10 \
     -XX:G1MaxNewSizePercent=60 \
     -Xlog:gc*,gc+heap=debug,gc+ergo=trace:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20M \
     -jar app.jar
```

`gc+ergo=trace` 는 G1이 왜 그 크기를 골랐는지를 남긴다. "predicted base time", "predicted pause time", "target pause time" 세 값을 비교하면, base time(고정 오버헤드: RSet 업데이트, 루트 스캔)이 목표의 절반을 이미 먹고 있는지 알 수 있다. base time 이 목표에 근접하면 Eden 을 최소로 줄여도 목표를 못 맞추므로, pause 목표를 낮추는 대신 RSet 비용을 줄여야 한다.

실측 감각: 8 vCPU, 8GB 힙, 초당 400MB 할당률인 일반적인 Spring Boot API 서버에서 `MaxGCPauseMillis=200` 이면 Eden 이 힙의 40~50% 로 커지고 Young GC 가 4~6초에 한 번, pause 60~120ms 로 수렴한다. 같은 조건에서 목표를 50ms 로 낮추면 Eden 이 10% 근처로 줄고 GC 빈도가 1초에 2~3회로 올라가 총 GC 오버헤드는 오히려 2~3배가 된다. 목표 pause 를 낮추는 것은 공짜가 아니라 처리량과의 교환이다.

## 4. Humongous 할당의 실제 비용

Region 크기의 50% 이상인 객체는 Humongous 로 분류되어 Old 영역의 연속 Region 들에 직접 할당된다. Eden 을 거치지 않는다. 핵심 함정은 세 가지다.

첫째, 연속 Region 이 필요하다. 4MB Region 에서 9MB 배열을 할당하면 연속된 3개 Region(12MB)을 차지하고 마지막 Region 의 3MB 는 낭비된다. 단편화가 심해 연속 3개를 못 찾으면 G1은 Full GC 를 유발할 수 있다.

둘째, Humongous Region 은 오랫동안 Mixed GC 에서만 회수됐다. 현재는 Young 수집 시점에 "아무도 참조하지 않는 Humongous" 를 eager reclaim 으로 즉시 회수하지만(`G1EagerReclaimHumongousObjects`, 기본 활성), 대상은 typeArray(참조를 포함하지 않는 원시 배열) 중심이다. 참조를 담은 `Object[]` 가 Humongous 로 가면 eager reclaim 혜택을 받기 어렵다.

셋째, 임계값이 "초과" 가 아니라 "이상" 이다. 4MB Region 에서 정확히 2MB 인 `byte[2097152]` 는 객체 헤더(16바이트) 때문에 2MB 를 넘어 Humongous 가 된다. 버퍼 크기를 2의 거듭제곱으로 딱 맞춰 잡는 흔한 코드가 의도치 않게 Humongous 를 양산하는 전형적 경로다.

```java
public final class BufferSizePlanner {

	private static final int OBJECT_HEADER_BYTES = 16;

	private BufferSizePlanner() {
	}

	/**
	 * Region 크기 대비 Humongous 임계를 넘지 않는 최대 byte[] 길이를 반환한다.
	 */
	public static int maxNonHumongousLength(int regionSizeBytes) {
		return (regionSizeBytes / 2) - OBJECT_HEADER_BYTES;
	}
}
```

```java
class BufferSizePlannerTest {

	@Test
	void 리전_4MB_에서_임계_길이를_계산한다() {
		assertThat(BufferSizePlanner.maxNonHumongousLength(4 * 1024 * 1024))
				.isEqualTo(2_097_136);
	}

	@Test
	void 정확히_2MB_배열은_임계를_초과한다() {
		int limit = BufferSizePlanner.maxNonHumongousLength(4 * 1024 * 1024);
		assertThat(2 * 1024 * 1024).isGreaterThan(limit);
	}
}
```

| 상황 | Region 4MB | Region 16MB |
|---|---|---|
| Humongous 임계 | 2MB | 8MB |
| 4MB 배열 | Humongous, 2 Region(8MB) 점유 | 일반 Old 할당 |
| 낭비 메모리 | 약 4MB | 0 |
| eager reclaim | typeArray 만 | typeArray 만 |

파일 업로드 버퍼, 이미지 리사이징, Protobuf 직렬화 버퍼처럼 수 MB 배열을 다루는 서비스에서는 `-XX:G1HeapRegionSize=16m` 로 올려 Humongous 를 일반 할당으로 되돌리는 것이 가장 효과가 확실한 한 줄 튜닝이다. 단, Region 을 키우면 CSet 입자가 굵어져 pause 편차가 커지므로 pause 목표를 함께 재검증해야 한다.

## 5. Concurrent Marking 과 SATB

Old 점유율이 `-XX:InitiatingHeapOccupancyPercent`(IHOP, 기본 45%, 적응형) 를 넘으면 Concurrent Marking Cycle 이 시작된다. 단계는 Initial Mark(Young GC 에 piggyback, STW) → Concurrent Root Region Scan → Concurrent Mark → Remark(STW) → Cleanup(STW, 짧음) 순이다.

G1의 동시 마킹은 SATB(Snapshot-At-The-Beginning) 를 쓴다. 마킹 시작 시점의 객체 그래프 스냅샷을 논리적으로 고정하고, 그 이후 참조가 끊기더라도 스냅샷에 살아 있던 객체는 이번 사이클에서 살아 있다고 본다. 즉 SATB 는 floating garbage 를 허용하는 대신 마킹 정확성을 보장한다. 쓰기 배리어는 참조를 덮어쓰기 **전**의 옛 값을 SATB 큐에 기록한다(pre-write barrier).

SATB 의 실무적 함의는 두 가지다. 하나, 마킹 중에 죽은 객체는 이번 사이클에서 회수되지 않고 다음 사이클로 넘어간다 — 할당률이 매우 높은 구간에서 힙 사용량이 실제 라이브 셋보다 부풀어 보이는 이유다. 둘, Remark 단계에서 SATB 큐를 전부 소진해야 하므로, 큐가 길면 Remark pause 가 길어진다. `Ref Proc`(참조 처리)와 함께 Remark 가 100ms 를 넘으면 `-XX:+ParallelRefProcEnabled` 를 먼저 확인한다.

IHOP 이 너무 높으면 마킹이 늦게 시작되어 마킹이 끝나기 전에 Old 가 가득 차고, 그 결과가 Full GC 다. 적응형 IHOP 은 "마킹에 걸리는 시간 × 할당률" 만큼의 여유를 두고 시작 지점을 조정하지만, 할당률이 스파이크성이면 예측이 빗나간다. 배치 잡처럼 순간 할당률이 10배로 뛰는 워크로드는 `-XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=35` 로 고정하는 편이 안전하다.

## 6. Mixed GC 와 CSet 선택 정책

마킹이 끝나면 각 Old Region 의 라이브 비율을 알게 되고, G1은 "쓰레기가 많은 Region" 부터 Young 수집에 끼워 함께 수집한다. 이것이 Mixed GC 다. 관련 플래그는 다음과 같이 맞물린다.

| 플래그 | 기본값 | 역할 | 올리면 |
|---|---|---|---|
| `G1MixedGCLiveThresholdPercent` | 85 | 이 비율 이상 살아있는 Region 은 후보 제외 | 회수량↑, 복사 비용↑ |
| `G1HeapWastePercent` | 5 | 남은 쓰레기가 이 비율 이하면 Mixed 중단 | Mixed 횟수↓, 잔여 쓰레기↑ |
| `G1MixedGCCountTarget` | 8 | 후보를 몇 번에 나눠 수집할지 | pause↓, 회수 완료 지연↑ |
| `G1OldCSetRegionThresholdPercent` | 10 | 한 번에 넣을 Old Region 상한(힙 대비) | pause↑, 회수 속도↑ |

전형적 증상과 처방: Mixed GC 가 돌아도 Old 점유가 계속 우상향한다면 회수 속도가 할당 속도를 못 따라가는 것이다. `G1MixedGCCountTarget` 을 8 → 4 로 낮추고 `G1OldCSetRegionThresholdPercent` 를 10 → 20 으로 올려 회차당 회수량을 키운다. 반대로 Mixed GC pause 만 유독 길다면 `G1MixedGCLiveThresholdPercent` 를 85 → 65 로 낮춰 복사량이 많은 Region 을 후보에서 뺀다.

```
# Mixed 사이클 진행을 로그에서 추적
[301.2s][info][gc] GC(88) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 5120M->3970M(8192M) 78.432ms
[303.9s][info][gc] GC(89) Pause Young (Mixed) (G1 Evacuation Pause) 4480M->3120M(8192M) 141.204ms
[306.5s][info][gc] GC(90) Pause Young (Mixed) (G1 Evacuation Pause) 3620M->2740M(8192M) 118.900ms
```

`Prepare Mixed` 가 보이면 마킹이 정상 종료된 것이고, 이어지는 `(Mixed)` 개수가 `G1MixedGCCountTarget` 보다 현저히 적으면 `G1HeapWastePercent` 조건으로 조기 중단된 것이다.

## 7. Evacuation Failure 진단

복사 수집은 대상 Region(to-space)이 있어야 성립한다. Free Region 이 고갈되면 G1은 객체를 복사하지 못하고 제자리에 표시만 하는 Evacuation Failure 로 빠진다. 이 경로는 매우 비싸며, 로그에 `To-space Exhausted` 또는 `Evacuation Failure` 로 남는다.

```
[512.7s][info][gc] GC(140) To-space exhausted
[512.7s][info][gc] GC(140) Pause Young (Normal) (G1 Evacuation Pause) 7900M->7850M(8192M) 892.1ms
```

원인은 대개 셋 중 하나다. (1) Survivor 공간이 부족해 승격 대상이 몰림 — `-XX:G1ReservePercent`(기본 10) 를 15~20 으로 올려 예비 공간을 확보한다. (2) Humongous 단편화로 연속 Free Region 확보 실패 — Region 크기 조정으로 해결한다. (3) 애초에 라이브 셋이 힙에 비해 큼 — 튜닝이 아니라 증설 또는 메모리 누수 조사 대상이다.

세 번째를 판별하려면 Full GC 직후의 힙 사용량을 본다. Full GC 후에도 사용량이 힙의 70% 이상이면 튜닝으로 해결되지 않는다. `-XX:+HeapDumpBeforeFullGC` 로 덤프를 남기고 지배 트리(dominator tree)를 확인하는 것이 순서다.

## 8. 운영 체크리스트와 계측

프로덕션에 적용할 기본 옵션 세트는 다음과 같다. JDK 17+ 의 통합 로깅 형식을 사용한다.

```bash
java -XX:+UseG1GC \
     -Xms8g -Xmx8g \
     -XX:MaxGCPauseMillis=150 \
     -XX:G1HeapRegionSize=16m \
     -XX:G1ReservePercent=15 \
     -XX:InitiatingHeapOccupancyPercent=40 \
     -XX:-G1UseAdaptiveIHOP \
     -XX:+ParallelRefProcEnabled \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/log/app/heapdump \
     -Xlog:gc*,gc+ergo*=debug,gc+heap=debug:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=20M \
     -jar app.jar
```

`-Xms` 와 `-Xmx` 를 같게 두는 것은 컨테이너 환경에서 특히 중요하다. 힙 확장 과정에서 발생하는 페이지 폴트와 Region 재구성이 pause 편차의 숨은 원인이 되기 때문이다. 같은 이유로 `-XX:+AlwaysPreTouch` 는 기동 시간을 수 초 늘리는 대신 런타임 지연 스파이크를 줄인다 — 오토스케일링으로 잦게 뜨고 지는 파드에서는 기동 시간 손해가 더 클 수 있어 트레이드오프를 측정해야 한다.

애플리케이션 레벨 계측은 Micrometer 의 `JvmGcMetrics` 로 `jvm.gc.pause`, `jvm.gc.memory.promoted`, `jvm.gc.memory.allocated` 를 수집한다. 승격률(promoted/초)이 할당률의 5% 를 넘으면 Survivor 를 빠져나가는 객체가 많다는 뜻이고, 이는 `-XX:MaxTenuringThreshold` 조정이나 요청 스코프 객체 수명 검토의 신호다.

```java
@Configuration
public class GcMetricsConfig {

	@Bean
	public MeterBinder jvmGcMetrics() {
		return new JvmGcMetrics();
	}
}
```

## 9. ZGC·Shenandoah 와의 선택 기준

G1은 "예측 가능한 중간 수준 pause + 좋은 처리량" 의 균형점이다. 실측 기준으로 힙 8~32GB, 목표 pause 100~200ms 구간에서는 G1이 가장 무난하다. 목표 pause 가 10ms 미만이고 힙이 수십~수백 GB 라면 ZGC 가 맞는다 — ZGC 는 동시 이동(concurrent relocation)과 load barrier 로 pause 를 힙 크기와 무관하게 유지하지만, load barrier 비용으로 처리량이 G1 대비 통상 5~15% 낮다.

정리하면 선택 기준은 "pause 상한이 SLA 에 직접 걸리는가" 다. p99 응답시간 SLA 가 300ms 인 API 에서 GC pause 150ms 는 다른 지연과 합쳐져 위험하지만, 배치 처리라면 500ms pause 가 문제되지 않고 처리량이 전부다. GC 선택을 바꾸기 전에, 위 6~7절의 CSet·Humongous·Evacuation Failure 를 먼저 제거했는지 확인하는 편이 비용 대비 효과가 크다.

## 참고

- Oracle, *HotSpot Virtual Machine Garbage Collection Tuning Guide — Garbage-First (G1) Garbage Collector* (JDK 21)
- Detlefs, Flood, Heller, Printezis, "Garbage-First Garbage Collection", ISMM 2004
- OpenJDK, *JEP 307: Parallel Full GC for G1*
- OpenJDK Wiki, *G1GC Logging and Ergonomics*
- Micrometer Reference, *JVM Metrics — JvmGcMetrics*
