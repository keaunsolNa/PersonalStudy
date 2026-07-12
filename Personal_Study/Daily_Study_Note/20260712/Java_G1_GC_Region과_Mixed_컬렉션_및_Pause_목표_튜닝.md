Notion 원본: https://www.notion.so/39b5a06fd6d381218ac5d3ca94adbf25

# Java G1 GC Region과 Mixed 컬렉션 및 Pause 목표 튜닝

> 2026-07-12 신규 주제 · 확장 대상: JAVA

## 학습 목표

- G1 의 Region 기반 힙 레이아웃과 Young/Old/Humongous 구분을 설명한다.
- Remembered Set 과 Write Barrier 가 세대 간 참조를 어떻게 추적하는지 파악한다.
- Young GC / Concurrent Marking / Mixed GC 의 3단계 사이클을 순서대로 이해한다.
- `MaxGCPauseMillis` 목표가 힙 크기·수집 영역 선택에 미치는 영향을 튜닝 관점에서 다룬다.

## 1. Region 기반 힙 — 물리적 세대의 폐지

전통적 Parallel/CMS GC 는 힙을 Young(Eden+Survivor)과 Old 로 물리적으로 연속 분할했다. G1(Garbage-First)은 힙을 균일 크기의 **Region**(기본 힙 크기에 따라 1MB~32MB, 2의 거듭제곱)으로 쪼개다. 각 Region 은 논리적으로 Eden, Survivor, Old, Humongous, Free 중 하나의 역할을 동적으로 부여받는다. 세대는 여전히 존재하지만 물리적으로 흔어진 Region 집합일 뿐이다.

Region 수는 대략 2048 개를 목표로 자동 산정된다(`-XX:G1HeapRegionSize` 로 강제 지정 가능). 이 설계 덕분에 G1 은 "가비지가 가장 많은 Region 부터(Garbage-First) 수집"할 수 있어, 힙 전체가 아니라 회수 효율이 높은 Region 부분집합만 골라 수집한다. 이것이 예측 가능한 일시정지(pause) 시간의 토대다.

Humongous Region 은 Region 크기의 50% 를 초과하는 대형 객체(예: 큰 배열)를 담는다. 이런 객체는 연속된 Region 들을 통째로 차지하며 Old 세대로 취급된다. Humongous 할당이 잦으면 단편화와 조기 수집을 유발하므로, 큰 배열을 남발하면 GC 압력이 커진다.

## 2. Remembered Set 과 Write Barrier

G1 은 Young GC 시 Young Region 만 수집하는데, Old→Young 참조가 있으면 Young 객체가 살아있는지 판단하려 Old 전체를 스캔해야 한다. 이를 피하려고 각 Region 은 **Remembered Set(RSet)** 을 둔다. RSet 은 "이 Region 안의 객체를 가리키는 외부 Region 의 참조 위치"를 기록한다. 덕분에 Young GC 는 Old 전체 대신 RSet 만 보고 루트를 찾는다.

RSet 을 최신 상태로 유지하려면 참조 필드에 쓰기가 일어날 때마다 기록해야 한다. G1 은 **Write Barrier**(참조 필드 대입 시 삽입되는 짧은 코드)로 이를 처리한다. G1 은 post-write barrier 로 카드(card, 512바이트 단위 힙 조각)를 dirty 표시하고, 별도 **Refinement 스레드**가 dirty 카드를 비동기로 처리해 RSet 을 갱신한다. 이 barrier 비용이 G1 의 처리량 오버헤드의 상당 부분을 차지하며, 참조 쓰기가 극도로 많은 워크로드에서 Parallel GC 보다 처리량이 낮아지는 원인이다.

```
# RSet/Refinement 관련 로그 활성화
-Xlog:gc+remset*=debug
```

## 3. 세 가지 수집 유형과 사이클

G1 의 수집은 크게 세 종류다.

**Young GC (Evacuation Pause, STW):** Eden 이 가득 차면 발생. 살아있는 객체를 Survivor 또는 Old Region 으로 복사(evacuate)한다. 전체가 stop-the-world 지만 Young Region 집합만 다루므로 짧다. 여러 번의 Young GC 를 거치며 age 임계치(`MaxTenuringThreshold`, 기본 15)를 넘긴 객체가 Old 로 승격된다.

**Concurrent Marking Cycle:** Old 점유율이 `-XX:InitiatingHeapOccupancyPercent`(IHOP, 기본 45%, 적응형)를 넘으면 시작. SATB(Snapshot-At-The-Beginning) 알고리즘으로 애플리케이션과 **동시에** 살아있는 객체를 표시한다. 짧은 STW 단계(Initial Mark, Remark, Cleanup)와 긴 concurrent 단계가 섞여 있다. Initial Mark 는 Young GC 에 편승(piggyback)해 수행된다.

**Mixed GC (STW):** Concurrent Marking 이 끝나 각 Old Region 의 가비지 비율이 파악되면, 이후 Young GC 들이 Young Region + **가비지가 많은 Old Region 일부**를 함께 수집한다. "Mixed"인 이유다. 한 번에 모든 Old 를 수집하지 않고 여러 Mixed GC 로 나눠 pause 목표를 지킨다. 수집 대상 Old Region 수는 `G1MixedGCLiveThresholdPercent`(라이브 비율이 이보다 낮은 Region 만 후보)와 `G1MixedGCCountTarget`(몇 번에 나눠 수집할지)로 조절된다.

| 단계 | STW 여부 | 수집 대상 |
|---|---|---|
| Young GC | 전체 STW | Eden + Survivor |
| Concurrent Marking | 대부분 동시 | 전 힙 마킹(회수 아님) |
| Mixed GC | 전체 STW | Young + 가비지 많은 Old 일부 |
| Full GC | 전체 STW(느림) | 전 힙(단일 스레드였다가 JDK10+ 병렬화) |

## 4. MaxGCPauseMillis — 예측형 튜닝 모델

G1 의 핵심 튜닝 노브는 `-XX:MaxGCPauseMillis`(기본 200ms)다. 이는 **하드 리밋이 아니라 목표**다. G1 은 과거 수집들의 통계(Region 당 evacuation 비용, RSet 스캔 비용 등)로 다음 수집에서 목표 시간 안에 처리 가능한 Region 수(Collection Set, CSet)를 예측해 선택한다. 목표를 낮추면 한 번에 수집하는 Region 이 줄어 pause 는 짧아지지만, 수집 빈도가 늘고 Old 회수가 지연돼 처리량이 떨어지고 최악의 경우 Full GC 로 이어진다.

```
# 저지연 지향 설정 예시
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:G1HeapRegionSize=8m
-XX:InitiatingHeapOccupancyPercent=40
-Xlog:gc*,gc+heap=info:file=gc.log:time,uptime,level,tags
```

튜닝 원칙은 "먼저 힙을 충분히 주고 pause 목표는 현실적으로"다. pause 목표를 비현실적으로 낮게(예: 20ms) 잡으면 G1 이 매 수집마다 극소량만 회수해 Old 가 계속 차오르고 결국 Full GC 가 터져 오히려 최대 지연이 폭증한다. 실무에서는 먼저 기본값(200ms)으로 로그를 수집해 실제 pause 분포와 승격 실패(to-space exhausted) 여부를 본 뒤 조정한다.

## 5. 위험 신호 — 로그에서 읽어야 할 것

**to-space exhausted / Evacuation Failure:** evacuation 중 복사할 Free Region 이 부족해 실패. 매우 비싼 처리를 유발하며 Full GC 의 전조다. 힙을 키우거나 IHOP 를 낮춰 Marking 을 일찍 시작해야 한다.

**Humongous Allocation 빈발:** `gc+heap` 로그에 humongous 할당이 자주 보이면 Region 크기를 키우거나(`G1HeapRegionSize`), 큰 객체 할당 자체를 줄이는 애플리케이션 수정이 필요하다.

**Full GC 발생:** G1 에서 Full GC 는 실패 신호다. Concurrent Marking 이 할당 속도를 못 따라가 힙이 꽉 찼다는 뜻. IHOP 하향, 힙 확대, `ConcGCThreads` 증대로 대응한다.

```
# 로그에서 위험 신호 grep 예시
grep -E "to-space|Full GC|Humongous" gc.log
```

## 6. JDK 버전에 따른 진화와 대안

G1 은 JDK 9 부터 기본 GC 다. JDK 10 에서 Full GC 가 병렬화됐고, 이후 릴리스마다 RSet 메모리 사용과 pause 예측이 개선됐다. 초저지연이 필요하면 **ZGC**(JDK 15+ 프로덕션, 서브 밀리초 pause, TB 급 힙)나 **Shenandoah** 를 고려한다. 이들은 concurrent evacuation 으로 pause 를 힙 크기와 무관하게 만든다. 다만 concurrent 작업이 늘어 CPU·메모리(로드 배리어) 오버헤드가 G1 보다 크므로, 처리량 우선이면 여전히 Parallel GC 가, 균형이 필요하면 G1 이, 지연 극단 최소화가 목표면 ZGC 가 맞다.

```java
// 애플리케이션 레벨에서 GC 압력을 줄이는 방향이 튜닝보다 먼저다
// 1) 단명 객체 재사용(객체 풀은 신중히), 2) 대형 배열 humongous 회피,
// 3) 캐시 크기 상한으로 Old 승격 억제
int[] reused = threadLocalBuffer.get(); // 매 요청마다 new int[N] 대신 재사용
```

## 7. String Deduplication 과 기타 실무 옵션

G1 은 문자열 중복 제거(`-XX:+UseStringDeduplication`)를 지원한다. 힙에 동일 내용의 `char[]`/`byte[]` 백킹 배열이 다수 존재하면, concurrent marking 중 이를 감지해 하나의 배열로 공유시켜 메모리를 절약한다. 웹 애플리케이션처럼 같은 문자열(헤더명, enum 명, JSON 키)이 대량 중복되는 워크로드에서 힙을 수 퍼센트~수십 퍼센트 줄이기도 한다. 다만 중복 제거 작업 자체의 CPU 비용이 있으므로 문자열 중복이 실제로 많을 때만 켜다.

```
-XX:+UseG1GC -XX:+UseStringDeduplication -Xlog:gc+stringdedup=debug
```

또 하나 중요한 옵션은 `-XX:G1NewSizePercent` / `-XX:G1MaxNewSizePercent` 로 Young 세대 크기의 하한·상한 비율을 조절하는 것이다. G1 은 pause 목표를 맞추려 Young 크기를 동적으로 조정하는데, 할당률이 매우 높은 서비스는 Young 이 너무 작아져 Young GC 가 폭증할 수 있다. 이때 하한을 올려 Young GC 빈도를 낮춘다. 반대로 Young 이 과도하게 커지면 개별 Young GC pause 가 길어지므로 상한으로 억제한다. 이런 세밀 조정은 항상 로그로 실제 Young 크기 변동과 pause 분포를 확인한 뒤 적용해야 한다.

메모리가 넣넨하다면 `-XX:+AlwaysPreTouch` 로 JVM 기동 시 힙 페이지를 미리 터치해 런타임 중 페이지 폴트로 인한 지연 스파이크를 없애는 것도 저지연 서비스의 흔한 선택이다. 컨테이너 환경에서는 `-XX:MaxRAMPercentage` 로 컨테이너 메모리 한도 대비 힙 비율을 지정해 OOMKilled 를 예방한다.

## 8. 실측 감각과 워크플로

튜닝은 항상 측정 우선이다. `-Xlog:gc*` 로그를 GCViewer/GCeasy 같은 도구로 분석해 (1) pause 분포(p99, max), (2) 처리량(GC 시간/전체 시간), (3) 할당·승격 속도를 본다. 목표 SLA(예: p99 pause < 150ms)를 정하고 힙 크기 → pause 목표 → IHOP 순으로 한 번에 하나씩 바꾸며 재측정한다. 여러 파라미터를 동시에 바꾸면 원인 분리가 불가능하다. 대개 가장 효과적인 개입은 GC 파라미터가 아니라 할당률 자체를 낮추는 애플리케이션 수정이라는 점을 기억해야 한다.

## 참고

- Oracle — HotSpot Virtual Machine Garbage Collection Tuning Guide (G1)
- JEP 248: Make G1 the Default Garbage Collector
- "Java Performance" 2nd ed., Scott Oaks (O'Reilly)
- OpenJDK Wiki — G1 GC internals, Remembered Sets
