Notion 원본: https://www.notion.so/3a25a06fd6d381b884c2d3ff4afe82aa

# Linux CFS 스케줄러와 vruntime 및 cgroup v2 CPU 대역폭 제어

> 2026-07-19 신규 주제 · 확장 대상: OS

## 학습 목표

- CFS 가 vruntime 으로 공정성을 구현하는 원리를 red-black tree 구조로 설명한다.
- nice 값이 가중치를 통해 vruntime 증가 속도를 바꾸는 계산을 추적한다.
- cgroup v2 의 cpu.weight 와 cpu.max 로 상대·절대 제한을 구분한다.
- 컨테이너 CPU throttling 이 왜 발생하고 지표를 어디서 읽는지 안다.

## 1. CFS 가 풀려는 문제

CFS 의 이상은 N 개 태스크가 각자 CPU 의 1/N 을 받는 것이다. "받아 마땅한 시간 대비 실제 받은 시간"을 보정한 vruntime 이 가장 작은 태스크를 다음에 실행한다. 우선순위는 vruntime 이 늘어나는 속도로 표현된다.

## 2. Red-Black Tree

실행 가능 태스크를 vruntime 키 red-black tree 에 담아 leftmost(최소) 를 O(1)로 읽고 삽입·삭제는 O(log n)이다. CPU 코어마다 `cfs_rq` 를 두고 코어 간은 load balancing 이 조정한다.

## 3. nice 값과 가중치

```
vruntime += 실제_실행시간 × (NICE_0_WEIGHT / 태스크_weight)
```

nice 0 가중치는 1024, nice 1 낮아질수록 약 1.25배씩 커진다.

| nice | weight |
|---|---|
| -5 | 3121 |
| 0 | 1024 |
| +5 | 335 |

가중치가 큰 태스크는 vruntime 이 덜 늘어 CPU 를 더 자주 받는다. 배분비는 가중치 비율과 같다.

## 4. sched_latency — 동적 타임 슬라이스

```
태스크_슬라이스 = sched_latency × (태스크_weight / 전체_weight_합)
```

기본 약 6ms. 태스크가 너무 많으면 sched_min_granularity(약 0.75ms)가 하한이다. sched_latency 를 줄이면 응답성↑ 처리량↓. 커널 6.6 부터 기본이 EEVDF 로 바뀜었지만 vruntime·가중치·cgroup 원리는 이어진다.

## 5. cgroup v2 통합 계층

```bash
mkdir /sys/fs/cgroup/myapp
echo "+cpu" > /sys/fs/cgroup/cgroup.subtree_control
echo 12345 > /sys/fs/cgroup/myapp/cgroup.procs
```

vruntime 공정성이 그룹 계층으로 재귀적으로 확장된다(그룹 간 → 그룹 내).

## 6. cpu.weight vs cpu.max

cpu.weight 는 경쟁 시 상대 비율(여유 CPU 초과 사용 허용), cpu.max 는 절대 상한이다.

```bash
echo 200 > /sys/fs/cgroup/myapp/cpu.weight   # 형제 대비 2배
echo "50000 100000" > /sys/fs/cgroup/myapp/cpu.max   # 코어 0.5개
```

| 항목 | cpu.weight | cpu.max |
|---|---|---|
| 성격 | 상대 비율 | 절대 상한 |
| 여유 CPU | 초과 허용 | 불가 |
| 부작용 | 없음 | throttling |

## 7. 컨테이너 CPU throttling

K8s CPU limit 은 cpu.max quota 로 구현된다. quota 를 period 안에서 다 쓰면 다음 period 까지 멈춘다. 멀티스레드가 순간 폭발적으로 일하면 평균 사용률이 낮아도 p99 지연이 튀다.

```bash
cat /sys/fs/cgroup/myapp/cpu.stat   # nr_throttled, throttled_usec
java -XX:ActiveProcessorCount=1 -jar app.jar
```

대응: limit 을 올리거나 없애기, 스레드 풀을 limit 에 맞추기, JVM 컨테이너 인식 옵션.

## 8. 실무 정리

nice 는 상한 없이 우선순위만, cpu.weight 는 경쟁 시 비율, cpu.max 는 강한 격리(throttling 감수). 가장 흔한 실수는 격리를 위해 무조건 limit 을 거는 것이다. request(weight)만으로도 공정 배분이 되므로 불필요한 limit 이 지연의 범인인 경우가 많다.

## 참고

- Linux Kernel Documentation — "CFS Scheduler", "Scheduler Nice Design"
- Linux Kernel Documentation — "Control Group v2"
- Jonathan Corbet (LWN.net) — CFS, EEVDF
- Robert Love, 『Linux Kernel Development』
- Kubernetes 문서 — "CPU limits and throttling"
