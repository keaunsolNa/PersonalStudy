Notion 원본: https://www.notion.so/3825a06fd6d38179a495f89e3abd96d8

# Java G1 GC와 ZGC — Region 동시수집과 Colored Pointer · Load Barrier

> 2026-06-17 신규 주제 · 확장 대상: JAVA

## 학습 목표

- G1의 Region 기반 수집과 Remembered Set / SATB write barrier 동작을 코드와 로그로 추적한다
- ZGC의 Colored Pointer와 Load Barrier가 어떻게 STW 없이 객체를 재배치하는지 설명한다
- Pause time goal, 처리량, 힙 크기에 따른 G1 ↔ ZGC 선택 기준을 실측 관점에서 정리한다
- GC 로그(Unified Logging)와 JFR 이벤트로 두 수집기의 동작을 진단한다

## 1. 왜 Region 기반인가 — CMS의 한계에서 출발

전통적 세대 수집기(ParallelGC, CMS)는 힙을 Young/Old 두 연속 영역으로 나눈다. CMS는 Old 영역을 동시 수집하지만 압축(compaction)을 하지 않아 단편화가 누적되고, 결국 Full GC 시 단일 스레드 압축으로 수백 ms~수 초의 STW를 유발한다. G1(Garbage First)은 힙을 1~32MB 균일 크기의 **Region** 수백~수천 개로 쪼개고, 각 Region을 Eden/Survivor/Old/Humongous로 동적 태깅한다. 핵심 아이디어는 "회수 가치(garbage 비율)가 높은 Region을 우선 수집"하여 정해진 pause goal 안에서 최대 효율을 내는 것이다.

```bash
# G1 활성화 + pause 목표 200ms + Region 크기 명시
java -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=8m \
     -Xms8g -Xmx8g -Xlog:gc*:file=g1.log:tags,uptime,level App
```

`MaxGCPauseMillis`는 약속이 아니라 목표다. G1은 과거 수집 시간 통계를 바탕으로 이번 Mixed GC에서 회수할 Old Region 개수(Collection Set, CSet)를 동적으로 조절해 목표에 수렴시킨다. 목표를 너무 낮게 잡으면 CSet이 작아져 회수가 더뎌지고 결국 힙이 차서 Full GC로 떨어진다.

## 2. G1의 Remembered Set과 SATB Write Barrier

Region 단위로 부분 수집을 하려면 "다른 Region에서 이 Region을 가리키는 참조"를 알아야 한다. 전체 힙을 스캔하면 부분 수집의 의미가 없으므로, 각 Region은 자신을 가리키는 cross-region 참조를 **Remembered Set(RSet)** 에 기록한다. RSet 유지를 위해 객체 참조 필드 갱신마다 **write barrier**가 끼어든다.

G1은 동시 마킹에 **SATB(Snapshot-At-The-Beginning)** 를 쓴다. 마킹 시작 시점의 객체 그래프 스냅샷을 논리적으로 보존해, 마킹 도중 끊긴 참조라도 "그 시점에 살아있었다면" 살아있는 것으로 간주(다음 사이클에 회수)한다. 이를 위해 pre-write barrier가 덮어쓰기 전 옛 참조값을 마킹 큐에 넣는다.

```java
// 개념적 의사코드 — 실제로는 JIT가 인라인한다
void writeFieldWithBarrier(Object owner, long offset, Object newRef) {
	Object oldRef = getReference(owner, offset);
	if (concurrentMarkingActive && oldRef != null) {
		enqueueSatbMarkingQueue(oldRef); // pre-barrier: 옛 값 보존
	}
	putReference(owner, offset, newRef);
	if (isCrossRegion(owner, newRef)) {
		enqueueDirtyCard(owner); // post-barrier: RSet 갱신용 card
	}
}
```

이 barrier 오버헤드가 G1의 처리량을 ParallelGC보다 약 10~15% 낮추는 주범이다. 처리량이 절대적으로 중요한 배치(batch) 작업이라면 ParallelGC가 여전히 유효하다.

## 3. G1 사이클 — Young, Concurrent Mark, Mixed

G1의 수집은 세 모드가 맞물린다. (1) **Young GC**: Eden이 차면 STW로 Eden+Survivor를 evacuation(살아있는 객체를 새 Region으로 복사). (2) **Concurrent Marking**: 힙 점유율이 `InitiatingHeapOccupancyPercent`(기본 45%)를 넘으면 Old 영역 생존 객체를 동시 마킹. (3) **Mixed GC**: 마킹 결과로 garbage 비율 높은 Old Region을 Young과 함께 evacuation.

```
[GC pause (G1 Evacuation Pause) (young)]  ... 12ms
[GC pause (G1 Evacuation Pause) (mixed)]  ... 45ms   ← Old Region 일부 포함
[GC concurrent-mark-start]
[GC concurrent-mark-end, 0.210 secs]
```

Humongous 객체(Region 크기의 50% 초과)는 별도 처리된다. 연속 Region을 통째로 점유하므로 큰 배열·문자열을 자주 만들면 Humongous allocation이 늘고 단편화가 생긴다. `G1HeapRegionSize`를 키우면 Humongous 기준도 올라가 완화된다.

## 4. ZGC — STW를 힙 크기와 분리하다

G1의 STW는 Young evacuation과 RSet 처리에 비례해 힙이 커질수록 늘어난다. ZGC의 설계 목표는 **STW를 힙 크기·살아있는 객체 수와 무관하게 1ms 미만으로 고정**하는 것이다. 비결은 거의 모든 작업(marking, relocation, reference processing)을 애플리케이션 스레드와 동시에 수행하고, STW는 루트 스캔 같은 상수 시간 작업으로만 한정한 것이다.

```bash
# Generational ZGC (JDK 21+ 권장) — 젊은 객체를 분리해 처리량 개선
java -XX:+UseZGC -XX:+ZGenerational -Xms16g -Xmx16g \
     -Xlog:gc*:file=zgc.log App
```

JDK 15에서 production 정식화, JDK 21에서 Generational ZGC가 도입되어 기존 single-gen ZGC의 약점(짧은 수명 객체에 대한 CPU·메모리 오버헤드)을 크게 줄였다. JDK 23부터 non-generational 모드는 deprecated다.

## 5. Colored Pointer — 포인터에 메타데이터를 심다

ZGC의 핵심은 64비트 객체 포인터의 사용하지 않는 상위 비트에 GC 상태를 인코딩하는 **Colored Pointer**다. 주소 자체가 아니라 metadata 비트(Marked0, Marked1, Remapped, Finalizable 등)를 포인터에 박아, 객체를 건드리지 않고도 "이 참조가 현재 GC 사이클 기준 유효한가"를 포인터만 보고 판단한다.

```
63        47 46    44 43                                 0
+----------+--------+------------------------------------+
| unused   | color  | 객체 가상주소 (44bit, ~16TB)        |
+----------+--------+------------------------------------+
            ^ Marked0 / Marked1 / Remapped / Finalizable
```

여러 가상주소를 같은 물리 페이지에 매핑하는 **multi-mapping** 기법으로, 색이 다른 포인터라도 같은 객체에 도달한다. 덕분에 색을 바꾸는 것(마스킹)만으로 상태 전이가 끝나고 실제 메모리는 그대로다.

## 6. Load Barrier — 동시 재배치의 핵심

G1이 write barrier로 마킹을 돕는다면, ZGC는 **Load Barrier**(읽기 장벽)로 재배치를 처리한다. 힙에서 객체 참조를 로드할 때마다 barrier가 끼어들어 포인터 색을 검사한다. 색이 "good"이면 통과, "bad"(아직 remap 안 됨)면 그 자리에서 객체를 새 위치로 옮기고(또는 forwarding table 조회) 포인터를 고친 뒤 반환한다. 이를 **self-healing**이라 한다 — 한 번 고친 참조는 다시 barrier에 걸리지 않는다.

```java
// Load barrier 개념 의사코드
Object loadReferenceWithBarrier(Object obj, long offset) {
	Object ref = rawLoad(obj, offset);
	if (isBadColor(ref)) {
		ref = slowPath(ref); // forwarding table 조회 or relocate
		rawStore(obj, offset, ref); // self-healing: 좋은 색으로 갱신
	}
	return ref;
}
```

이 구조 덕분에 ZGC는 객체를 옮기는 동안에도 애플리케이션을 멈추지 않는다. 대가는 모든 참조 로드에 barrier 비용이 붙는다는 점 — 참조 추적이 많은 워크로드에서 처리량이 ParallelGC 대비 떨어질 수 있다.

## 7. 선택 기준 — 실측 관점 비교

다음은 두 수집기의 특성을 정리한 표다. 절대 수치는 워크로드·JDK 버전에 따라 달라지므로 경향으로 본다.

| 항목 | G1 | ZGC (Generational) |
|---|---|---|
| 일반적 max pause | 수십~수백 ms (힙 크기 비례) | < 1ms (힙과 무관) |
| 권장 힙 범위 | ~수십 GB | 수 GB ~ 16TB |
| barrier 종류 | write (SATB + RSet) | load (relocation) |
| 압축 | Mixed GC 시 evacuation | 항상 동시 압축 |
| 처리량 | ZGC보다 보통 높음 | barrier 비용으로 약간 낮을 수 있음 |
| 메모리 오버헤드 | RSet 등 ~10% | colored pointer/forwarding ~소량, 멀티매핑 가상메모리 |
| 기본 수집기 | JDK 9+ 기본 | 명시적 opt-in |

판단 휴리스틱: pause time이 SLA(예: p99 응답 50ms)를 좌우하는 저지연 서비스라면 ZGC. 처리량이 우선이고 pause 수십 ms를 감내할 수 있는 일반 웹/배치라면 G1로 충분하다. 힙이 32GB를 넘어가면서 G1의 pause가 길어지기 시작하면 ZGC 전환을 검토한다. 단, 32비트 환경이나 매우 작은 힙(수백 MB)에서는 ZGC의 가상메모리 오버헤드가 비효율적이다.

## 8. 진단 — 로그와 JFR

GC 튜닝의 출발점은 추측이 아니라 측정이다. Unified Logging으로 pause 분포를, JFR로 allocation 패턴과 GC 원인을 본다.

```bash
# allocation rate / pause / promotion 을 한 번에
java -XX:+UseZGC -XX:+ZGenerational \
     -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=zgc.log:uptime,level,tags \
     -XX:StartFlightRecording=duration=120s,filename=app.jfr App
```

JFR의 `jdk.GCPhasePause`, `jdk.ZAllocationStall`(ZGC에서 할당이 GC를 못 따라가 멈춘 순간), `jdk.PromotedBytes` 이벤트를 보면 병목이 보인다. ZGC에서 Allocation Stall이 잦다면 힙이 부족하거나 allocation rate가 GC throughput을 초과한 것 — 힙을 키우거나 `-XX:ConcGCThreads`를 늘린다. G1에서 잦은 Full GC + "to-space exhausted" 로그는 evacuation 실패 신호로, 힙 증설이나 `MaxGCPauseMillis` 상향이 필요하다.

## 9. 튜닝 실전 — 흔한 증상과 처방

GC 튜닝의 첫 원칙은 "기본값으로 측정 먼저, 그다음 한 번에 하나씩"이다. 처음부터 플래그를 도배하면 어떤 변경이 효과를 냈는지 알 수 없다. 자주 마주치는 증상별 처방을 정리한다.

**G1에서 잦은 Humongous allocation**: GC 로그에 `(G1 Humongous Allocation)`이 자주 보이면 Region 절반을 넘는 큰 객체가 빈번히 만들어지는 것이다. `G1HeapRegionSize`를 키우면(예: 16m, 32m) Humongous 기준이 올라가 일반 할당 경로로 흡수된다. 근본적으로는 거대한 단일 배열·문자열 생성을 스트리밍/청크 처리로 바꾸는 코드 개선이 낫다.

**G1에서 Mixed GC가 Old를 못 따라감**: Old 점유율이 계속 오르고 결국 Full GC로 떨어진다면 마킹 시작이 늦은 것이다. `-XX:InitiatingHeapOccupancyPercent`(IHOP, 기본 45)를 낮춰 동시 마킹을 일찍 시작하거나, `-XX:G1MixedGCCountTarget`을 키워 Mixed GC가 Old Region을 더 공격적으로 회수하게 한다. JDK 9+의 adaptive IHOP는 이를 자동 조절하지만, 갑작스러운 적재 변화에는 수동 하한이 도움이 된다.

```bash
# G1: 마킹을 일찍, Mixed를 공격적으로
java -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=35 \
     -XX:G1MixedGCCountTarget=16 -XX:G1HeapRegionSize=16m \
     -Xms16g -Xmx16g App
```

**ZGC에서 Allocation Stall**: `jdk.ZAllocationStall` 이벤트나 로그의 `Allocation Stall`은 애플리케이션이 메모리를 GC가 회수하는 속도보다 빨리 소비해 잠시 멈춘 것이다. 처방은 (1) 힙 증설로 여유 확보, (2) `-XX:ConcGCThreads` 상향으로 동시 GC 처리량 증가, (3) allocation rate 자체를 줄이는 코드 개선(객체 재사용, 풀링)이다. ZGC는 STW를 작게 유지하는 대신 동시 작업에 CPU를 쓰므로, CPU가 포화 상태면 GC 스레드와 앱 스레드가 경합한다.

**공통 — Soft/Weak Reference 폭증**: 캐시를 SoftReference로 구현했는데 메모리 압박 시 대량 회수 → 재생성 폭주가 반복되면 GC가 요동친다. 명시적 크기 제한 캐시(Caffeine 등)로 바꾸는 것이 GC 친화적이다.

마지막으로, GC를 바꾸기 전에 "정말 GC가 병목인가"를 확인한다. 애플리케이션 STW의 상당 부분이 GC가 아니라 safepoint 도달 지연(JIT 컴파일, 큰 루프의 safepoint poll 부재)일 수 있다. `-Xlog:safepoint`로 `Reaching safepoint` 시간과 `At safepoint` 시간을 분리해 봐야 진짜 원인이 보인다.

## 참고

- Oracle, "HotSpot Virtual Machine Garbage Collection Tuning Guide" (JDK 21)
- The Garbage First Garbage Collector — Detlefs, Flood, Heller, Printezis (ACM ISMM 2004)
- OpenJDK ZGC Wiki — wiki.openjdk.org/display/zgc
- JEP 439: Generational ZGC
- "The Z Garbage Collector — An Introduction" (Per Liden, FOSDEM)
