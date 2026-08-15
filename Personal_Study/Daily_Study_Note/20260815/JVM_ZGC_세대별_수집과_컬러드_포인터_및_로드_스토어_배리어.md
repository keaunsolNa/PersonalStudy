Notion 원본: https://app.notion.com/p/3bd5a06fd6d3816c823ec991e5b69c0c?pvs=204

# JVM ZGC 세대별 수집과 컬러드 포인터 및 로드·스토어 배리어

> 2026-08-15 신규 주제 · 확장 대상: JAVA

## 학습 목표

- 컬러드 포인터의 비트 레이아웃을 해석하고 압축 OOP 비활성화·힙 상한이라는 대가를 계산한다
- 로드 배리어와 스토어 배리어가 삽입하는 기계어와 슬로우패스 진입 조건을 구분한다
- 세대별 ZGC 의 remembered set 유지 방식을 G1 의 카드 테이블·SATB 와 대조해 선택 근거를 세운다
- GC 로그에서 allocation stall 을 식별하고 힙 여유·SoftMaxHeapSize 로 제거한다

## 1. G1 에서 ZGC 로 넘어갈 때 바뀌는 전제

G1 은 "STW 안에서 일을 끝낸다"를 기본 전제로 삼는다. 동시 마킹은 하지만 evacuation(복사)은 반드시 stop-the-world pause 안에서 수행하고, pause 예산(`MaxGCPauseMillis`)에 맞춰 이번에 비울 region 개수를 조절한다. 그래서 G1 의 pause 는 살아있는 객체 양에 비례해 늘어나고, 힙이 커질수록 mixed GC pause 가 수십 ms 에서 수백 ms 로 벌어진다.

ZGC 는 전제를 뒤집는다. 마킹뿐 아니라 **객체 재배치(relocation)까지 애플리케이션 스레드와 동시에** 수행한다. 이게 가능하려면 "이 참조가 가리키는 객체가 방금 옮겨졌는가"를 애플리케이션이 참조를 읽는 순간마다 확인해야 하고, 그 확인을 O(1) 에 하기 위해 참조 자체에 상태를 새겨 넣는 것이 컬러드 포인터다. 즉 ZGC 의 모든 설계는 "mutator 가 참조를 읽을 때 GC 상태를 즉시 판정한다"는 한 문장에서 파생된다.

대가는 처리량이다. 모든 참조 읽기에 배리어가 붙으므로 순수 처리량 벤치에서 G1 대비 5~15% 손해를 본다. 반대로 pause 는 힙 크기와 거의 무관하게 sub-millisecond 로 유지된다. 이 trade-off 를 받아들일 수 있는지가 ZGC 도입의 첫 판단 기준이다.

## 2. 컬러드 포인터의 비트 레이아웃

x86-64 는 물리적으로 48비트 가상 주소만 쓴다(5-level paging 시 57비트). ZGC 는 남는 상위 비트를 GC 메타데이터 저장소로 징발했다. JDK 11~21 의 non-generational ZGC 레이아웃은 다음과 같다.

```text
 6                 4 4 4  4 4                                             0
 3                 8 7 6  3 2                                             0
+-------------------+-+----+-----------------------------------------------+
|00000000 00000000 0|0|1111|11 11111111 11111111 11111111 11111111 11111111|
+-------------------+-+----+-----------------------------------------------+
                    | |    |
                    | |    +-- 42비트 객체 오프셋 (최대 4TB)
                    | +------- 4비트 메타데이터
                    |          bit 42: Finalizable
                    |          bit 43: Remapped
                    |          bit 44: Marked1
                    |          bit 45: Marked0
                    +--------- 미사용 (0)
```

`Marked0`/`Marked1` 이 두 개인 이유는 마킹 사이클을 번갈아 쓰기 위해서다. 이번 사이클에 "좋은 색(good color)"이 Marked0 이면 다음 사이클엔 Marked1 이 된다. 사이클 전환 시 힙 전체를 순회하며 색을 지울 필요 없이, "무엇이 좋은 색인가"라는 전역 변수 하나만 바꾸면 이전 사이클 색을 가진 모든 포인터가 자동으로 "나쁜 색"이 된다. 이 트릭이 ZGC pause 를 힙 크기와 분리하는 핵심이다.

`Finalizable` 은 finalizer 를 통해서만 도달 가능한 객체를 표시한다. 이 색을 가진 참조를 일반 코드가 읽으면 배리어가 객체를 되살려(resurrect) 강한 참조로 승격시킨다.

## 3. 압축 OOP 를 못 쓰는 이유와 힙 상한

압축 OOP(`-XX:+UseCompressedOops`)는 64비트 참조를 32비트로 줄이고 3비트 shift 를 적용해 최대 32GB 힙까지 커버하는 최적화다. 객체 헤더와 참조 필드가 절반이 되므로 힙 사용량이 20~30% 줄고 캐시 적중률이 오른다. 그런데 ZGC 는 42~45번 비트에 색을 넣어야 하므로 참조가 반드시 64비트여야 한다. 두 기능은 원리적으로 공존할 수 없다.

```bash
$ java -XX:+UseZGC -XX:+UseCompressedOops -Xmx8g -version
# 결과: 압축 OOP 가 조용히 비활성화된다
$ java -XX:+UseZGC -Xmx8g -XX:+PrintFlagsFinal -version | grep -i compressedoops
     bool UseCompressedOops = false  {product} {ergonomic}
```

실무적 의미는 명확하다. 힙 8GB 이하 구간에서는 G1(압축 OOP 켜짐)이 ZGC 보다 실제 메모리 footprint 가 15~25% 작다. 참조가 조밀한 도메인 객체 그래프(JPA 엔티티 트리, 큰 `HashMap<String, DTO>`)일수록 격차가 커진다. 반대로 32GB 를 넘어가면 G1 도 압축 OOP 를 잃으므로 이 불리함은 사라진다. **ZGC 는 32GB 이상 힙에서 비로소 메모리 측면의 페널티가 중립이 된다.**

힙 상한은 42비트 오프셋에서 4TB, JDK 15 부터 `ZPlatformAddressOffsetBits` 확장으로 16TB 까지 지원한다. 실무에서 만날 일은 거의 없지만, 컬러드 포인터가 주소 공간을 잘라 쓴다는 구조적 제약의 결과라는 점은 기억할 만하다.

## 4. 다중 매핑 제거와 하위 비트 컬러링으로의 전환

초기 ZGC 는 "다중 매핑(multi-mapping)"을 썼다. 같은 물리 페이지를 Marked0·Marked1·Remapped 세 개의 서로 다른 가상 주소에 매핑해서, 색이 다른 포인터를 그대로 역참조해도 동일한 물리 메모리에 도달하게 만든 것이다. 배리어가 색을 벗겨낼(mask) 필요 없이 바로 로드할 수 있어 빨랐다.

문제는 관측성이었다. `ps`, `top`, cgroup 메모리 회계가 같은 물리 페이지를 세 번 세어 RSS 가 실제의 3배로 보고됐다. 컨테이너 환경에서 "8GB 힙인데 RSS 24GB"로 찍히면 운영팀 입장에서 OOMKill 위험 판단이 불가능하다. JDK 21 에서 다중 매핑을 제거하고 배리어가 명시적으로 색 비트를 마스킹하도록 바꿨다.

JDK 22 이후 세대별 ZGC 는 한 걸음 더 나가 **하위 비트 컬러링**으로 전환했다. 객체 정렬(8바이트) 덕에 놀고 있던 하위 3비트를 포함해 하위 영역에 색을 배치한 것이다. 이유는 세대별 수집에 필요한 상태가 늘어났기 때문이다. young/old 마킹 색, remembered set 상태, remap 상태를 모두 표현하려면 상위 4비트로는 부족했고, 하위 비트는 주소 산술과 겹치지 않으면서 확장 여유가 있었다.

```text
JDK 21 이전 (상위 비트)      : [ color | offset ]  → 역참조 전 shift/mask 필요
JDK 22 이후 (하위 비트)      : [ offset | color ]  → 주소 계산에 color 가 상수로 흡수
```

하위 비트 컬러링의 부수 효과가 **스토어 배리어 도입**이다. 상위 비트 방식에서는 참조를 힙에 쓸 때 색만 맞춰주면 됐지만, 세대별 수집에서는 "old → young 참조가 새로 생겼다"를 기록해야 한다. 이 기록이 store barrier 이고, ZGC 가 로드 배리어 단독 체제에서 로드+스토어 이중 배리어 체제로 넘어간 결정적 계기다.

## 5. 로드 배리어의 실제 동작

Java 코드 `Object o = obj.field;` 한 줄에 대해 C2 가 생성하는 코드는 대략 이렇다.

```java
// 소스
Object o = obj.field;

// 개념적으로 JIT 이 삽입하는 것 (JDK 21 non-generational 기준)
Object o = obj.field;                       // 1. 일반 로드
if ((o & BAD_COLOR_MASK) != 0) {            // 2. 색 검사
    o = slow_path(o, &obj.field);           // 3. 슬로우패스 + self-healing
}
```

x86-64 실제 인스트럭션은 3개다.

```text
mov  0x10(%rsi), %rax          ; 참조 로드
test %rax, 0x20(%r15)          ; 나쁜 색 마스크(TLS 의 bad-mask)와 test
jnz  slow_path                 ; 나쁜 색이면 점프 (거의 항상 not-taken)
```

핵심은 `jnz` 가 압도적으로 not-taken 이라는 점이다. 분기 예측기가 이를 학습하면 파이프라인 비용은 사실상 test 1개 수준이 된다. 실측으로 로드 배리어의 순수 오버헤드는 참조 로드가 많은 워크로드에서 처리량 4~10%, 참조가 적고 primitive 연산 위주면 1% 미만이다.

슬로우패스는 세 가지 경우에 진입한다. 첫째, 마킹 단계에서 아직 마킹되지 않은 객체를 처음 읽을 때 — 배리어가 객체를 마킹 스택에 push 한다. 둘째, 재배치 단계에서 relocation set 에 속한 객체를 읽을 때 — forwarding table 을 조회해 새 주소를 얻거나, 아직 안 옮겨졌으면 **mutator 스레드가 직접 복사한다**. 셋째, remap 단계에서 이전 사이클의 낡은 주소를 읽을 때.

`slow_path` 의 마지막 동작이 **self-healing** 이다. 새 주소와 좋은 색을 계산한 뒤, 참조를 읽어온 원본 필드에 CAS 로 되쓴다. 같은 필드를 다음에 읽으면 이미 좋은 색이므로 슬로우패스에 들어가지 않는다. 그래서 재배치 직후 잠깐 슬로우패스 비율이 치솟고 곧 0 에 수렴하는 곡선이 나온다.

```java
// self-healing 을 확인하는 마이크로 실험
public class HealingProbe {
    static Object[] arr = new Object[10_000_000];
    public static void main(String[] a) throws Exception {
        for (int i = 0; i < arr.length; i++) arr[i] = new byte[16];
        long t0 = System.nanoTime();
        long s = 0;
        for (int i = 0; i < arr.length; i++) s += arr[i].hashCode(); // 1회차: 슬로우패스 다발
        long t1 = System.nanoTime();
        for (int i = 0; i < arr.length; i++) s += arr[i].hashCode(); // 2회차: 힐링 완료
        long t2 = System.nanoTime();
        System.out.printf("1st=%.1fms 2nd=%.1fms sink=%d%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, s);
    }
}
```

GC 사이클 직후 실행하면 1회차가 2회차보다 2~4배 느리게 나온다. 이 격차가 배리어 슬로우패스 + self-healing 의 실체다.

## 6. 세대별 ZGC 와 remembered set

JEP 439(JDK 21) 는 힙을 young/old 두 세대로 나눴다. 근거는 약한 세대 가설이다. 대부분의 객체는 금방 죽으므로, 전체 힙을 매번 마킹하는 대신 young 만 자주 돌면 같은 회수량을 훨씬 적은 CPU 로 얻는다. non-generational ZGC 는 매 사이클 전체 힙을 마킹했기 때문에 할당률이 높은 애플리케이션에서 GC CPU 사용량이 과했다.

young 만 수집하려면 "old 에서 young 을 가리키는 참조"를 알아야 한다. G1 은 이를 **카드 테이블**로 푼다. 힙을 512바이트 카드로 나누고, old 영역에 참조를 쓸 때마다 해당 카드를 dirty 로 표시한 뒤 GC 때 dirty 카드 전체를 스캔한다. 정확도는 카드 단위라서 실제 old→young 참조 하나 때문에 512바이트를 통째로 훑는다.

ZGC 는 **필드 단위 정밀도의 remembered set** 을 store barrier 로 유지한다. old 객체의 참조 필드에 쓰기가 일어나면 배리어가 그 필드 주소에 해당하는 비트맵 비트를 세운다. 카드가 아니라 워드 단위이므로 young 수집 시 스캔 대상이 정확히 필요한 필드로 좁혀진다. 대신 비트맵 메모리(힙의 약 1/64 를 두 벌)와 쓰기 경로 비용을 지불한다.

```text
                 G1                          Generational ZGC
쓰기 기록 단위    512B 카드                    참조 필드(워드) 단위 비트맵
자료구조         card table + RSet(해시)      dual remembered set 비트맵
쓰기 배리어 비용   조건부 store (dirty 마킹)     조건부 store + 색 검사
young 스캔       dirty 카드 전체 재스캔        set 비트 위치만 직접 방문
동시성 모델       SATB (스냅샷 기준 마킹)        증분 업데이트 + 컬러 기반
재배치           STW evacuation               concurrent relocation
```

remembered set 을 두 벌 두는 이유는 이전 사이클 것을 읽으면서 다음 사이클 것을 동시에 쌓기 위해서다. 마킹 색을 두 개 두는 것과 같은 이중 버퍼 발상이다.

스토어 배리어는 remembered set 만 담당하지 않는다. young 객체가 old 로 승격될 때의 상태 전이, 그리고 하위 비트 컬러링 하에서 참조를 힙에 쓸 때 색을 정규화하는 일도 함께 한다. 그래서 세대별 ZGC 로 오면서 쓰기가 많은 워크로드의 처리량은 non-generational 대비 약간 손해를 볼 수 있지만, 읽기 위주 + 높은 할당률 워크로드에서는 GC CPU 절감이 훨씬 커서 순이득이다.

## 7. 사이클 단계와 forwarding table

세대별 ZGC 의 major(old) 사이클은 대략 다음 순서로 진행한다.

```text
[Pause Mark Start]      ← STW, GC root 스캔 시작·색 전환         (수십 µs)
 Concurrent Mark        ← 객체 그래프 순회, 로드 배리어가 보조
[Pause Mark End]        ← STW, 마킹 종료 합의·weak ref 처리 진입  (수십 µs)
 Concurrent Process Non-Strong References
 Concurrent Reset Relocation Set
 Concurrent Select Relocation Set  ← garbage 비율 높은 페이지 선정
[Pause Relocate Start]  ← STW, root 재매핑 시작                  (수십 µs)
 Concurrent Relocate    ← 객체 복사 + forwarding table 기록
```

relocation set 선정은 페이지(ZPage) 단위 live 바이트 기준이다. 각 페이지의 live 비율을 마킹 결과로 알고 있으므로, "적게 복사해서 많이 회수하는" 페이지부터 담는다. 기본적으로 `ZFragmentationLimit`(기본 25%) 이상 조각난 페이지가 후보다.

`ZForwardingTable` 은 relocation set 페이지마다 하나씩 붙는 오픈 어드레싱 해시 테이블이다. 키는 옛 오프셋, 값은 새 오프셋이며, 삽입은 CAS 로 lock-free 하게 수행된다. GC 스레드와 mutator 스레드가 동시에 같은 객체를 옮기려 하면 CAS 승자만 복사본을 확정하고 패자는 자기 복사본을 버린 뒤 승자의 주소를 쓴다. 이 구조 덕에 복사에 락이 필요 없다.

forwarding table 은 다음 사이클의 마킹이 힙 전체를 훑으며 낡은 참조를 갱신할 때까지 살아있다. **ZGC 에 별도의 remap 단계가 없는 이유가 이것이다** — 다음 사이클 마킹이 remap 을 겸한다. 그래서 사이클 하나가 끝나도 forwarding table 메모리는 일정 기간 유지되며, 이것이 ZGC 의 메모리 오버헤드 항목 중 하나다.

## 8. STW 구간이 sub-millisecond 인 이유

남아있는 세 pause 는 모두 **GC root 개수에만 비례**한다. 힙에 객체가 10억 개 있든 100개 있든, pause 안에서 하는 일은 스레드 스택·JNI 핸들·클래스 로더 데이터의 참조를 훑는 것뿐이다. 스레드가 수천 개로 늘어나면 pause 도 늘지만, 힙이 100GB 에서 1TB 로 커져도 pause 는 그대로다.

JDK 16 의 concurrent thread-stack scanning(JEP 376) 이후로는 스택 스캔조차 대부분 concurrent 로 밀려나서, pause 는 safepoint 진입·이탈 오버헤드에 근접했다. 실측으로 100GB 힙·200 스레드 환경에서 pause 는 30~120µs 대다.

```bash
java -XX:+UseZGC -Xmx32g \
  -Xlog:gc,gc+phases:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=50M \
  -jar app.jar
```

```text
[2026-08-15T09:12:03.114+0900] GC(482) Y: Pause Mark Start 0.041ms
[2026-08-15T09:12:03.297+0900] GC(482) Y: Concurrent Mark 182.334ms
[2026-08-15T09:12:03.298+0900] GC(482) Y: Pause Mark End 0.033ms
[2026-08-15T09:12:03.401+0900] GC(482) Y: Pause Relocate Start 0.056ms
[2026-08-15T09:12:03.612+0900] GC(482) Y: Concurrent Relocate 210.881ms
[2026-08-15T09:12:03.613+0900] GC(482) Y: Young Generation 24576M(75%)->3072M(9%) 0.499s
```

Concurrent 구간이 수백 ms 여도 pause 는 0.04ms 다. 다만 **concurrent 구간 동안 GC 스레드가 CPU 를 쓰므로 애플리케이션 응답시간은 pause 만큼만 영향받는 게 아니다.** CPU 여유가 없는 환경에서는 concurrent 구간이 곧 지연으로 나타난다. `ZCollectionInterval`, GC 스레드 수(`-XX:ConcGCThreads`) 조정이 필요한 지점이다.

## 9. 할당 스톨, 실전 튜닝, 그리고 선택 기준

ZGC 를 쓰면서 p99 가 튀는 대부분의 원인은 pause 가 아니라 **allocation stall** 이다. 재배치가 진행 중인데 여유 페이지가 바닥나면, 새 객체를 할당하려는 애플리케이션 스레드가 GC 가 페이지를 반납할 때까지 블록된다. 이건 pause 로 집계되지 않기 때문에 `-Xlog:gc` 만 보면 "GC 는 0.05ms 인데 왜 느리지?"라는 미궁에 빠진다.

```bash
# stall 을 반드시 로그에 남긴다
-Xlog:gc,gc+alloc,gc+heap,safepoint:file=gc.log:time,uptime:filecount=10,filesize=50M
```

```text
[12.884s] Allocation Stall (http-nio-8080-exec-14) 87.412ms
[12.902s] Allocation Stall (http-nio-8080-exec-9) 104.660ms
[12.931s] GC(51) Garbage Collection (Allocation Stall) 30718M(94%)->2044M(6%)
```

`Allocation Stall` 한 줄에 87ms 가 찍히면 그 요청의 p99 는 87ms 를 그대로 먹는다. `Garbage Collection (Allocation Stall)` 이라는 트리거 사유가 보이면 이미 늦은 것이다. 대응은 세 방향이다.

첫째, **힙 여유 확보**가 가장 확실하다. ZGC 는 재배치 대상 객체를 복사할 공간이 필요하므로 여유 없이 돌릴 수 없다. 정상 워크로드에서 힙 사용률이 70% 를 넘어 유지되면 `-Xmx` 를 올린다. 둘째, `-XX:ZAllocationSpikeTolerance`(기본 2.0)를 높여 GC 를 더 일찍 시작하게 한다. 이 값은 "다음 사이클 동안 할당률이 최근 평균의 몇 배까지 튈 수 있다고 가정할 것인가"이며, 배치 잡이나 트래픽 스파이크가 있는 서비스에서 3.0~5.0 이 유효하다. 셋째, `SoftMaxHeapSize` 로 목표 힙을 낮게 잡아 GC 를 자주 돌리되 스파이크 시 `Xmx` 까지 늘어나도록 여지를 남긴다.

```bash
java -XX:+UseZGC \
     -Xms16g -Xmx16g \
     -XX:SoftMaxHeapSize=12g \
     -XX:ZAllocationSpikeTolerance=3.0 \
     -XX:+ZUncommit -XX:ZUncommitDelay=300 \
     -XX:+UseLargePages -XX:+UseNUMA \
     -XX:ConcGCThreads=4 \
     -jar app.jar
```

`-Xms` 를 `-Xmx` 와 같게 두는 것은 컨테이너에서 특히 중요하다. `ZUncommit` 은 유휴 시 OS 에 메모리를 반납해 클라우드 비용을 줄이지만, 반납 직후 트래픽이 오면 재커밋 + page fault 비용이 지연으로 나타난다. 지연이 최우선인 서비스는 `-XX:-ZUncommit` 으로 끄는 편이 낫다. Large Pages 는 TLB 미스를 줄여 배리어 슬로우패스 비용을 실질적으로 낮추며, 큰 힙에서 처리량 3~8% 개선이 흔하다. NUMA 는 다중 소켓 베어메탈에서만 의미가 있고 컨테이너 CPU 제한 하에서는 효과가 미미하다.

컨테이너에서는 힙 외 영역을 반드시 계산에 넣어야 한다. ZGC 는 forwarding table, mark 비트맵, remembered set 비트맵을 힙 밖에 두므로 **힙의 8~15% 를 native 로 추가 소비**한다. 컨테이너 메모리 4GB 에 `-Xmx3g` 를 주면 metaspace·code cache·스레드 스택과 합쳐 OOMKill 이 난다. `MaxRAMPercentage` 는 50~60% 로 잡는 게 안전하다.

세 컬렉터의 실측 경향은 다음과 같다(32GB 힙, 할당률 2GB/s 급 웹 서비스 기준의 대표값이며 워크로드에 따라 달라진다).

| 항목 | G1 | Shenandoah | Generational ZGC |
|---|---|---|---|
| 처리량 (G1=100) | 100 | 88~95 | 90~97 |
| p99 pause | 40~200ms | 1~5ms | 0.05~0.3ms |
| p999 응답 지연 | pause 지배 | 배리어+pause | allocation stall 지배 |
| 힙 외 메타 오버헤드 | 힙의 ~3% (카드+RSet) | 힙의 ~5% (Brooks/LRB) | 힙의 8~15% |
| 압축 OOP | 32GB 미만에서 사용 | 사용 가능 | 사용 불가 |
| 권장 힙 하한 | 제한 없음 | 4GB~ | 8GB~(실질 16GB~) |
| 배리어 | write 만 | load(+write) | load + store |

**ZGC 를 고르지 말아야 하는 경우**가 분명히 있다. 힙이 4GB 이하이면 압축 OOP 손실과 메타 오버헤드가 pause 이득을 압도한다. 배치·ETL·스트림 집계처럼 처리량이 전부인 잡은 G1 이나 Parallel GC 가 낫다 — 총 처리 시간 10% 차이가 pause 100ms 보다 중요하다. CPU 코어가 2개 이하이거나 컨테이너 CPU limit 이 타이트하면 concurrent GC 스레드가 애플리케이션 스레드와 코어를 다투어 오히려 지연이 악화된다. 마지막으로 이미 G1 에서 p99 가 SLA 를 만족하고 있다면 바꿀 이유가 없다. ZGC 는 "pause 가 실제로 문제이고, CPU 와 메모리를 그 대가로 지불할 수 있을 때" 쓰는 도구다.

전환 시 검증 순서는 이렇게 잡는다. 프로덕션과 같은 트래픽 패턴으로 부하를 걸고 `-Xlog:gc,gc+alloc,safepoint` 로 최소 1시간 관측한다. `Allocation Stall` 이 0 이고, GC CPU 사용률이 전체의 15% 미만이며, RSS 가 컨테이너 limit 의 80% 아래에서 안정되면 통과다. 하나라도 어긋나면 힙을 키우거나 G1 으로 돌아가는 것이 정답이다.

## 참고

- JEP 333: ZGC: A Scalable Low-Latency Garbage Collector (Experimental) — https://openjdk.org/jeps/333
- JEP 439: Generational ZGC — https://openjdk.org/jeps/439
- JEP 474: ZGC: Generational Mode by Default (JDK 23) — https://openjdk.org/jeps/474
- JEP 376: ZGC: Concurrent Thread-Stack Processing — https://openjdk.org/jeps/376
- OpenJDK Wiki — Main ZGC Page (설계 문서·배리어 상세) — https://wiki.openjdk.org/display/zgc/Main
- Oracle HotSpot Virtual Machine Garbage Collection Tuning Guide — https://docs.oracle.com/en/java/javase/21/gctuning/
- Yang & Wrigstad, "Deep Dive into ZGC: A Modern Garbage Collector in OpenJDK" (ACM TOPLAS, 2022) — https://dl.acm.org/doi/10.1145/3538532
