Notion 원본: https://www.notion.so/3775a06fd6d381b181e1e4a415ec31f9

# Java ZGC Generational — Colored Pointers와 Region 기반 GC 심화

> 2026-06-06 신규 주제 · 확장 대상: JAVA

## 학습 목표

- ZGC의 colored pointer와 load barrier가 concurrent relocation을 어떻게 가능하게 하는지 설명한다
- JEP 439(Generational ZGC)가 도입한 young/old generation 분리와 remembered set 설계를 분석한다
- ZGC를 운영 환경에서 튜닝할 때 보는 핵심 지표(allocation stall, GC cycle, heap reserve)를 해석한다
- G1과 비교해 ZGC를 선택해야 하는 워크로드 조건을 판단한다

## 1. ZGC가 풀려는 문제 — pause time을 heap 크기와 분리

전통적 GC(Parallel, G1)는 heap이 커질수록 STW(stop-the-world) 구간이 길어진다. 특히 객체를 이동(relocation/evacuation)하고 그 참조를 갱신하는 작업은 모든 mutator thread를 멈춰야 안전했다. ZGC는 이 전제를 깨고 heap 크기와 무관하게 pause를 sub-millisecond로 유지하는 것을 목표로 설계됐다. JDK 21 기준 ZGC의 STW 구간은 GC root 스캔에 해당하는 짧은 구간들뿐이며, 대부분의 작업(marking, relocation, reference remapping)을 mutator와 동시에 수행한다.

핵심 트릭은 두 가지다. 첫째, colored pointer로 객체 참조 안에 GC 메타데이터를 인코딩한다. 둘째, load barrier로 객체 참조를 읽는 순간 그 포인터가 최신 상태인지 검사하고 필요하면 즉시 고친다. 이 둘이 결합되면 객체를 옮겼지만 아직 모든 참조를 갱신하지 못한 중간 상태를 안전하게 허용할 수 있다.

## 2. Colored Pointers — 64비트 주소에 상태를 새긴다

ZGC는 64비트 포인터의 상위 비트를 메타데이터로 쓴다. 실제 객체 주소는 하위 44비트(최대 16TB)에 담고, 그 위 비트들에 marking/remap 상태를 표현한다. 같은 객체를 가리키는 여러 포인터가 서로 다른 색을 가질 수 있다. GC는 cycle마다 지금 유효한 색을 바꾸고, load barrier가 오래된 색을 만나면 교정한다. 이 방식 덕분에 별도의 forwarding 정보를 객체 헤더에 욱여넣지 않고도, 포인터만 보고 이 참조가 이미 relocate된 영역을 가리키는가를 O(1)에 판단한다. Generational ZGC(JEP 439)에서는 색 비트 레이아웃이 재설계되어 young/old marking을 구분하고 remembered set 처리를 위한 비트가 추가됐다.

## 3. Load Barrier — 참조를 읽을 때 고친다

ZGC의 모든 객체 참조 로드는 컴파일러가 삽입한 load barrier를 거친다.

```c
oop load_barrier(oop* addr) {
    oop ref = *addr;                       // raw load
    if (!is_good_color(ref)) {             // 색이 현재 cycle과 다르면
        ref = slow_path(addr, ref);        // relocate 여부 확인 + self-heal
        *addr = ref;                       // 갱신된 포인터를 다시 기록
    }
    return ref;
}
```

중요한 점은 self-healing이다. barrier가 한 번 느린 경로를 타면 그 슬롯의 포인터를 고쳐 써서, 같은 참조를 다음에 읽을 때는 빠른 경로로 지나간다. 따라서 relocation 비용이 전체 mutator에 amortize되고, 별도의 전체 heap을 멈추고 모든 참조를 갱신하는 단계가 사라진다. G1의 write barrier(쓰기 시점)와 달리 ZGC는 읽기 시점에 동작하므로 fast path를 단일 비트 테스트 수준으로 극한 최적화한다.

## 4. JEP 439 — 왜 generational이 필요했나

비-generational ZGC는 매 cycle 전체 heap을 marking했다. 약한 세대 가설을 활용하지 못해 할당률이 높은 워크로드에서는 GC가 따라가지 못하고 allocation stall이 생기기 쉬웠다. Generational ZGC는 heap을 young/old로 나눠 young을 자주 싸게 수집하고 old는 가끔 수집한다. 같은 CPU 예산으로 훨씬 높은 할당률을 감당한다.

| 항목 | Non-Gen ZGC | Generational ZGC (JEP 439) |
|---|---|---|
| marking 범위 | 매번 전체 heap | 주로 young, old는 가끔 |
| 할당률 내성 | 낮음 | 높음 (수 배) |
| heap overhead | 높음 | 낮음 |
| cross-gen 참조 추적 | 불필요 | remembered set 필요 |
| 활성화 (JDK 21) | `-XX:+UseZGC` | `-XX:+UseZGC -XX:+ZGenerational` |

JDK 23부터는 generational이 기본이 되고 non-generational은 deprecated 경로로 이동했다.

## 5. Remembered Set과 cross-generational 참조

세대를 나누면 old → young 참조를 추적해야 한다. young만 수집할 때 old에서 young 객체를 가리키는 참조를 놓치면 살아있는 객체를 잘못 회수한다. ZGC는 이를 remembered set으로 푼다. old 영역의 field가 young을 가리키게 되면 그 위치를 기록해 두고, young 수집 시 그 위치들을 추가 root로 스캔한다. 운영 관점에서 핵심은 old를 많이 mutate하는 워크로드는 remembered set 유지 비용이 커진다는 점이다.

## 6. 실측 — GC 로그 읽기

```bash
java -XX:+UseZGC -XX:+ZGenerational -Xms8g -Xmx8g \
     -Xlog:gc*,gc+stats=info:file=zgc.log:time,uptime,level,tags -jar app.jar
```

확인할 지표: Pause 구간(Pause Mark Start/End, Pause Relocate Start)은 모두 sub-millisecond여야 정상이고, 1ms를 넘기면 root 수가 많거나 safepoint 비용이 큰 것이다. Allocation Stall이 찍히면 GC가 할당률을 못 따라간다는 신호로 heap을 늘리거나 ConcGCThreads를 올린다. Heap reserve가 max에 근접하며 stall이 보이면 -Xmx를 키운다. 튜닝 1순위는 거의 항상 heap 크기다.

## 7. G1 vs ZGC — 언제 무엇을

| 기준 | G1 | Generational ZGC |
|---|---|---|
| 목표 pause | 수십~수백 ms | < 1ms |
| heap 규모 | ~수십 GB까지 효율적 | 수백 GB~TB까지 선형 |
| throughput | 더 높음 | 약간 낮음 (barrier 비용) |
| barrier | write (SATB+card) | load (+store) |
| 적합 워크로드 | 배치, throughput 중심 | 지연 민감 API, 대용량 heap |

지연이 SLA의 핵심이고 heap이 크면 ZGC가 유리하다. 반대로 throughput이 전부인 배치성 작업이나 heap이 작아 pause가 이미 짧다면 G1이 단순하고 빠르다.

## 8. 적용 시 주의점

ZGC의 load barrier는 모든 참조 로드에 비용을 더하므로 포인터를 매우 많이 따라가는 코드는 약간의 throughput 저하를 본다. colored pointer가 가상 주소 공간을 크게 매핑하므로 모니터링 도구가 RSS를 과대 보고할 수 있어 NMT로 확인하는 것이 정확하다. ZGC는 compaction을 항상 수행해 fragmentation 이슈가 줄지만 relocation 자체가 상시 비용으로 깔린다는 점을 벤치마크로 검증해야 한다.

## 9. 운영 전환 절차 — G1에서 ZGC generational로

실제 서비스를 G1에서 ZGC generational로 옮길 때는 단계적 검증이 안전하다. 카나리 한 인스턴스에만 ZGC를 적용하고 GC 로그와 지연(p50/p99/p99.9)을 동시에 수집하며, throughput 회귀(load barrier 비용으로 CPU 수 % 상승)가 SLA 안에 드는지 확인한다. heap은 G1보다 20~30% 여유를 두고 시작해 stall 로그를 보며 조정한다. ZGC는 지연을 낮추는 대신 약간의 throughput을 내주는 트레이드오프이므로, 지연이 문제가 아닌 배치성 워크로드에서는 오히려 손해다. 전환 결정은 현재 G1의 GC pause가 tail latency SLA를 실제로 위협하는가라는 측정된 근거 위에서 내린다.

## 참고

- JEP 439: Generational ZGC — openjdk.org/jeps/439
- JEP 377: ZGC: A Scalable Low-Latency Garbage Collector
- Oracle, "Getting Started with Generational ZGC" — JDK 21 GC Tuning Guide
- Per Liden, "ZGC — A Scalable Low Latency Garbage Collector" (JVMLS)
- "The Garbage Collection Handbook" 2nd ed.
