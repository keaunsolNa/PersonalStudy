Notion 원본: https://www.notion.so/3ed5a06fd6d3814b8b13c520146ee088

# Linux CFS와 EEVDF 스케줄러 동작 원리와 cgroup v2 CPU 제어 및 컨테이너 스로틀링

> 2026-10-02 신규 주제 · 확장 대상: OS

## 학습 목표

- CFS의 vruntime과 EEVDF의 lag, 가상 마감 시각 계산을 비교한다
- cgroup v2의 cpu.weight와 cpu.max가 스케줄러에 반영되는 경로를 추적한다
- 쿠버네티스 requests와 limits가 cgroup 파일로 변환되는 규칙을 검증한다
- JVM의 CPU 개수 인식과 스로틀링 지표를 연결해 지연 급증 원인을 진단한다

## 1. 왜 CPU 스케줄러를 알아야 하는가

Spring 서비스를 쿠버네티스에 올리면 어느 순간 평균 CPU 사용률은 40%인데 p99 응답 시간만 튀는 현상을 만난다. 힙도 여유 있고 DB도 한가한데 요청 하나가 수백 ms 멈춘다. 이때 원인이 애플리케이션 코드가 아니라 커널 스케줄러가 컨테이너의 cgroup을 잠시 멈춰 세운 것(스로틀링)일 때가 많다. 스케줄러는 "어떤 런큐의 어떤 태스크를 다음에 돌릴지"를 정하고, cgroup은 "어떤 그룹이 얼마만큼 돌 수 있는지"를 정한다. 둘의 접점을 모르면 limits를 올려야 할지, requests를 올려야 할지, 스레드 수를 줄여야 할지 근거 없이 찍게 된다.

이 노트는 CFS와 EEVDF, cgroup v2 CPU 컨트롤러, 컨테이너 안의 JVM 순서로 본다. 확인 가능한 사실은 다음과 같다. CFS는 Linux 2.6.23에서 도입되었고, EEVDF는 Linux 6.6에서 CFS의 선택 로직을 대체했다. 두 스케줄러 모두 `kernel/sched/fair.c`에 구현되어 있어 코드 위치는 같다.

## 2. CFS: vruntime과 레드블랙 트리

CFS의 핵심 아이디어는 "이상적인 멀티태스킹 CPU"를 흉내 내는 것이다. 태스크가 N개이면 각자가 정확히 1/N씩 CPU를 동시에 쓰는 CPU를 가정하고, 실제로는 한 번에 하나만 돌 수 있으므로 "지금까지 덜 받은 태스크"를 먼저 돌린다. 이를 수치로 만든 것이 vruntime(virtual runtime)이다. 태스크가 실행된 실제 시간을 가중치로 나눠 정규화한 값이며, 가중치가 큰 태스크일수록 같은 시간을 써도 vruntime이 천천히 늘어난다.

```
vruntime += delta_exec * (NICE_0_WEIGHT / task_weight)
```

nice 0의 가중치는 1024이고, nice가 1 낮아질 때마다 대략 1.25배씩 커지도록 `sched_prio_to_weight` 테이블이 정의되어 있다. nice -1은 1277, nice 1은 820 수준이다. 런큐에는 vruntime을 키로 하는 레드블랙 트리가 있고, 가장 왼쪽 노드(vruntime 최소)가 다음 실행 대상이다. 삽입과 삭제는 O(log n), 최소값 조회는 캐시된 leftmost 포인터로 O(1)이다.

예전 CFS에서는 `sched_latency_ns`(목표 지연)와 `sched_min_granularity_ns`(최소 타임슬라이스)가 타임슬라이스를 결정했다. 대략 "latency 기간 안에 모든 러너블 태스크가 한 번씩 돌게 하되, 태스크가 많아지면 min granularity로 바닥을 깐다"는 구조다. 수치는 CPU 수에 따라 스케일링되어 커널마다 다르므로 `sysctl kernel.sched_latency_ns`처럼 직접 읽는 편이 안전하다(6.6 이전 커널 기준).

CFS의 약점은 지연 시간 제어가 간접적이라는 점이다. 잠들었다 깨어난 태스크는 vruntime을 런큐의 min_vruntime 근처로 보정받는데, 이 휴리스틱(wakeup preemption, sleeper fairness)이 시간이 갈수록 누더기가 되었다.

## 3. EEVDF: 자격과 마감 시각으로 고르기

EEVDF(Earliest Eligible Virtual Deadline First)는 Stoica와 Abdel-Wahab이 1995년에 발표한 논문의 알고리즘이며, Peter Zijlstra가 구현해 Linux 6.6에 들어갔다. CFS의 "vruntime 최소" 대신 두 단계로 고른다. 먼저 자격(eligible)이 있는 태스크만 후보로 삼고, 그중 가상 마감 시각(virtual deadline)이 가장 이른 태스크를 실행한다.

자격은 lag로 판단한다. lag은 "이상적인 공정 스케줄러였다면 받았을 서비스 시간 - 실제로 받은 서비스 시간"이다. lag이 0 이상이면 아직 받을 몫이 남은 것이므로 eligible이고, 음수이면 이미 과하게 써서 잠시 쉬어야 한다. 마감 시각은 태스크가 요청한 슬라이스 크기에서 나온다.

```
virtual_deadline = virtual_eligible_time + slice / weight
```

슬라이스가 짧은 태스크는 마감이 빨리 오므로 자주, 짧게 선택된다. 이것이 CFS와의 결정적 차이다. CPU 몫(weight)과 지연 요구(slice)가 분리된다. 총 사용량은 weight 비율로 보장하면서, 응답성이 중요한 태스크는 짧은 슬라이스로 더 빨리 순서가 돌아오게 만든다. 기본 슬라이스는 `base_slice_ns`이며 수 ms 수준이다. 6.6에서는 debugfs에 노출되고, 이후 버전에서는 `sched_setattr`로 태스크별 슬라이스를 지정하는 경로가 추가되었다(정확한 도입 버전은 사용 커널의 문서로 확인할 것).

| 구분 | CFS | EEVDF |
| --- | --- | --- |
| 선택 기준 | vruntime 최소 | eligible 중 가상 마감 최소 |
| 지연 표현 | 간접(nice, 휴리스틱) | 슬라이스 길이로 직접 |
| 깨어난 태스크 보정 | min_vruntime 기반 휴리스틱 | lag 보존으로 일관되게 처리 |
| 도입 | 2.6.23 | 6.6 |
| 튜닝 노브 | latency, min_granularity 등 | base_slice 중심으로 단순화 |

실무 의미는 이렇다. 6.6 이상 커널 노드에서는 CFS 시절의 `sched_latency_ns` 같은 sysctl 튜닝 경험이 그대로 통하지 않는다. 다만 cgroup 대역폭 제어는 EEVDF로 바뀐 뒤에도 유지된다.

## 4. 그룹 스케줄링: cgroup이 스케줄러에 끼어드는 방식

CFS와 EEVDF는 태스크 단위가 아니라 스케줄링 엔티티(sched_entity) 단위로 동작한다. cgroup의 CPU 컨트롤러를 켜면 각 cgroup이 CPU마다 하나의 그룹 엔티티를 갖고, 이 엔티티가 자기 그룹의 하위 런큐를 대표해 상위 런큐에서 경쟁한다. 즉 스케줄링은 계층적이다. 최상위에서 컨테이너 A와 B가 weight 비율로 경쟁하고, 선택된 컨테이너 안에서 다시 그 컨테이너의 스레드끼리 경쟁한다.

이 구조 때문에 컨테이너 안의 스레드를 아무리 늘려도 컨테이너 몫이 늘지 않는다. 스레드 200개짜리 컨테이너와 10개짜리 컨테이너가 같은 weight이면 경합 시 몫은 똑같다. 스레드가 많은 쪽은 몫을 더 잘게 나눠 쓸 뿐이다. 반대로 경합이 없으면 weight는 아무 효과가 없어서, 한가한 노드에서는 requests가 작아도 CPU를 마음껏 쓴다. weight는 "경합 시의 비율"이지 "보장량"이 아니라는 점을 기억해야 한다. 이 성질은 7절의 requests 해석과 직결된다.

## 5. cgroup v2 CPU 인터페이스: weight, max, stat

cgroup v2의 CPU 컨트롤러는 크게 네 파일로 쓴다. `cpu.weight`는 1~10000 범위이고 기본값은 100이며, 형제 cgroup 간 경합 시 비율을 정한다. `cpu.max`는 "쿼터 주기" 두 값으로, 기본은 `max 100000`(무제한, 주기 100ms)이다. `cpu.max.burst`는 쓰지 않고 모아둔 쿼터를 일시적으로 더 쓰게 허용하는 버스트 한도이며, 5.14 이후 커널에서 쓸 수 있다. `cpu.stat`에는 사용량과 스로틀링 통계가 누적된다.

```bash
cd /sys/fs/cgroup
sudo mkdir demo && cd demo
echo "+cpu" | sudo tee ../cgroup.subtree_control >/dev/null

# 한 코어의 50%만 허용: 100ms 주기마다 50ms 쿼터
echo "50000 100000" | sudo tee cpu.max
echo 200 | sudo tee cpu.weight          # 형제보다 2배 가중치 (경합 시)

# 현재 셸을 이 cgroup에 넣고 CPU 소모 작업 실행
echo $$ | sudo tee cgroup.procs
timeout 10 sh -c 'while :; do :; done' &
sleep 10; cat cpu.stat
```

`cpu.stat`의 주요 필드는 다음과 같다. 숫자 해석은 실행 환경마다 다르므로 비율로 보는 것이 좋다.

| 필드 | 의미 |
| --- | --- |
| usage_usec | 이 cgroup이 사용한 총 CPU 시간(마이크로초) |
| nr_periods | 지나간 쿼터 주기 수 |
| nr_throttled | 쿼터를 소진해 스로틀된 주기 수 |
| throttled_usec | 스로틀되어 멈춰 있던 누적 시간 |

위 예제처럼 50% 쿼터를 주고 한 코어를 100% 쓰려는 루프를 돌리면 매 주기의 절반쯤에서 멈추므로 `nr_throttled / nr_periods`가 1에 가깝게 나온다. 이 비율이 스로틀링의 가장 직관적인 경보 지표다. `cpu.pressure`(PSI)도 함께 보면 "러너블인데 CPU를 못 받은 시간 비율"을 볼 수 있어서 weight 경합과 쿼터 스로틀을 구분하는 데 도움이 된다.

## 6. CFS 대역폭 제어와 스로틀링의 메커니즘

`cpu.max`가 걸린 cgroup은 CFS bandwidth controller의 관리를 받는다. 구조는 글로벌 풀과 CPU별 로컬 풀의 이중 구조다. 주기가 시작될 때마다 글로벌 풀에 쿼터가 채워지고, 각 CPU의 런큐는 필요할 때 글로벌 풀에서 슬라이스 단위(기본 5ms, `sched_cfs_bandwidth_slice_us`)로 시간을 빌려 간다. 로컬 풀이 비고 글로벌 풀도 비면 해당 cgroup의 런큐는 주기 끝까지 throttle되어 어떤 스레드도 돌지 못한다.

여기서 멀티스레드 서버에 불리한 특성이 나온다. 쿼터는 코어 수가 아니라 "시간의 합"이다. 2 CPU limit(200ms/100ms)인 컨테이너에서 스레드 16개가 동시에 돌면 12.5ms 만에 200ms를 다 써 버리고 나머지 87.5ms 동안 전부 멈춘다. 평균 사용률은 낮게 보이지만 요청 하나가 그 멈춤 구간에 걸리면 최대 약 87ms가 지연에 더해진다. 아래 수치는 설명용 산술이며 실측값이 아니다.

```
limit = 2 CPU, period = 100ms  => quota = 200ms/period
동시 실행 스레드 16개  => 200ms / 16 = 12.5ms 에 소진
스로틀 구간 = 100ms - 12.5ms = 87.5ms  (이 동안 p99 지연에 직격)
```

과거에 알려진 문제로, 5.4 이전 커널에는 로컬 슬라이스가 만료되며 쿼터를 다 쓰지도 않았는데 스로틀되는 버그가 있어 "CPU 여유가 있는데 스로틀링" 현상이 널리 보고되었다. 5.4에서 슬라이스 만료 로직을 제거하는 수정이 들어가 상당 부분 완화되었다. 따라서 오래된 커널의 노드에서는 같은 limit이라도 스로틀이 더 심할 수 있다. 완화 수단은 세 가지다. limit을 올리거나 제거하기, 동시 실행 스레드 수를 limit에 맞게 줄이기, `cpu.max.burst`로 짧은 급증 허용하기.

## 7. 쿠버네티스: requests/limits가 cgroup 값이 되는 규칙

kubelet은 컨테이너 런타임을 통해 파드 스펙을 cgroup 파일로 옮긴다. v2 환경에서 `limits.cpu`는 `cpu.max`로, `requests.cpu`는 `cpu.weight`로 변환된다. limits의 주기는 기본 100ms이다. requests는 먼저 v1 시절의 shares(1 CPU = 1024)로 계산한 뒤 1~10000 범위의 weight로 환산하는 공식을 쓰므로, 1 CPU request는 대략 weight 39 안팎이 된다. 정확한 환산 공식은 쿠버네티스와 OCI 런타임 문서를 확인해야 하며, 여기서 중요한 점은 비율만 의미가 있다는 것이다.

```yaml
apiVersion: v1
kind: Pod
metadata: { name: api }
spec:
  containers:
    - name: app
      image: eclipse-temurin:21-jre
      resources:
        requests: { cpu: "1" }     # cpu.weight로 변환: 경합 시 비율
        limits:   { cpu: "2" }     # cpu.max = "200000 100000": 하드 상한
```

```bash
# 파드 안에서 변환 결과 확인 (cgroup v2)
cat /sys/fs/cgroup/cpu.max      # 200000 100000
cat /sys/fs/cgroup/cpu.weight
cat /sys/fs/cgroup/cpu.stat     # nr_throttled 증가 여부 관찰
```

운영 관점의 트레이드오프는 명확하다. limits를 설정하면 이웃 파드 보호와 예측 가능한 비용을 얻지만, 노드가 한가해도 상한에서 멈추는 스로틀링 위험을 진다. limits를 빼면 한가한 노드의 여유 CPU를 쓸 수 있지만, 이웃과의 경합 시 requests 비율로만 보호받는다. 이 선택은 노드 밀도와 SLO에 따라 달라진다. 지연에 민감한 서비스는 requests를 실사용에 맞춰 충분히 잡고 limits는 크게 두거나 생략하는 절충이 흔하다.

## 8. JVM과 Spring 관점: CPU 개수 인식과 진단

JDK 10 이후 HotSpot은 컨테이너의 cgroup 제한을 인식한다. `Runtime.availableProcessors()`는 `cpu.max`의 쿼터를 주기로 나눈 값을 올림해서 돌려준다. 호스트가 64코어여도 limit이 2 CPU이면 2, 1.5 CPU이면 2가 된다. JDK 8은 8u191 이후에야 컨테이너 인식이 들어갔다.

이 값은 JVM 곳곳을 결정한다. GC 스레드 수(ParallelGCThreads, ConcGCThreads), JIT 컴파일러 스레드 수, `ForkJoinPool.commonPool()` 병렬도(보통 availableProcessors - 1), Reactor의 기본 스케줄러 스레드 수가 여기서 나온다. limit이 1 CPU이면 서버급 머신 판정에서 밀려 G1 대신 SerialGC가 선택될 수 있다는 점이 유명한 함정이다(서버급 판정은 2 CPU 이상과 일정 메모리 이상이 조건). 반대로 limit 없이 requests만 있으면 JVM은 호스트 전체 코어를 보고 스레드를 크게 잡아서, 경합 시 불필요한 컨텍스트 스위칭만 늘 수 있다.

```java
public class CpuProbe {
    public static void main(String[] args) {
        System.out.println("availableProcessors = "
            + Runtime.getRuntime().availableProcessors());
        System.out.println("commonPool parallelism = "
            + java.util.concurrent.ForkJoinPool.getCommonPoolParallelism());
    }
}
```

Spring Boot에서 진단하는 순서는 다음처럼 잡을 수 있다. 먼저 p99 급증 시각과 `container_cpu_cfs_throttled_periods_total / container_cpu_cfs_periods_total`(cAdvisor 지표, v2에서도 이름이 유지되는지 사용 중인 버전 확인) 비율을 겹쳐 본다. 스로틀 비율이 높으면 CPU 사용률이 낮아도 의심한다. 다음으로 Tomcat의 `server.tomcat.threads.max`(기본 200)와 커넥션 풀, `@Async` 풀의 총 동시 실행 가능 스레드가 limit 대비 과도한지 본다. CPU 바운드 구간의 동시성이 limit을 훨씬 넘으면 6절의 산술처럼 쿼터가 순식간에 소진된다. 마지막으로 JFR이나 async-profiler로 GC 스레드가 쿼터를 얼마나 잡아먹는지 확인한다. GC 병렬 스레드는 애플리케이션 스레드와 같은 쿼터를 공유하므로 GC 순간에 스로틀이 몰리는 경우가 많다.

## 9. 정리: 판단 기준

핵심 구분은 이렇다. weight(requests)는 경합 시 비율이고, 쿼터(limits)는 경합과 무관한 시간 상한이다. 전자는 한가하면 의미가 없고, 후자는 한가해도 걸린다. CFS에서 EEVDF로의 전환은 선택 알고리즘의 변화이므로 컨테이너 스로틀링의 원리 자체를 바꾸지 않았지만, 지연 요구를 슬라이스로 표현할 길이 열렸다는 점에서 지연 민감 워크로드에는 장기적으로 의미가 있다.

## 참고

- Linux kernel documentation, CFS Scheduler (Documentation/scheduler/sched-design-CFS.rst)
- Linux kernel documentation, EEVDF Scheduler (Documentation/scheduler/sched-eevdf.rst)
- Linux kernel documentation, CFS Bandwidth Control (Documentation/scheduler/sched-bwc.rst)
- Linux kernel documentation, Control Group v2 (Documentation/admin-guide/cgroup-v2.rst)
- I. Stoica, H. Abdel-Wahab, "Earliest Eligible Virtual Deadline First: A Flexible and Accurate Mechanism for Proportional Share Resource Allocation", 1995
- LWN.net, "An EEVDF CPU scheduler for Linux" 및 Linux 6.6 관련 기사
- Kubernetes documentation, "Resource Management for Pods and Containers" 및 "Pod Quality of Service Classes"
- OpenJDK, JEP 및 릴리스 노트의 컨테이너 지원 항목(JDK 10 이후)
- Robert Love, 《Linux Kernel Development》 (스케줄러 장)
