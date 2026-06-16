Notion 원본: https://www.notion.so/3815a06fd6d381f8af41fc73bae25659

# Linux cgroup v2와 컨테이너 메모리 제한 OOM Killer 및 CPU cfs_quota Throttling

> 2026-06-16 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- cgroup v2 의 통합 계층 구조와 컨트롤러 위임 모델을 설명한다
- 메모리 컨트롤러의 max/high/low 와 OOM Killer 트리거 조건을 구분한다
- CPU cfs_quota/period 기반 throttling 이 지연(latency)에 미치는 영향을 측정 지표로 진단한다
- 컨테이너 리소스 설정(requests/limits)이 cgroup 파일에 어떻게 매핑되는지 추적한다

## 1. cgroup v1 → v2 — 통합 계층 구조

cgroup v1 은 컨트롤러(cpu, memory, blkio …)마다 별도의 계층 트리를 가졌다. 같은 프로세스가 cpu 트리에서는 A 그룹, memory 트리에서는 B 그룹에 속할 수 있어 정책 조합이 복잡하고 일관성이 깨졌다. cgroup v2 는 **단일 통합 계층(unified hierarchy)** 을 도입했다 — `/sys/fs/cgroup` 하나의 트리에 모든 컨트롤러가 붙고, 프로세스는 정확히 하나의 cgroup 에만 속한다.

```bash
# v2 인지 확인 — 이 파일이 있으면 unified hierarchy
cat /sys/fs/cgroup/cgroup.controllers
# 예: cpuset cpu io memory hugetlb pids rdma

# 현재 프로세스가 속한 cgroup
cat /proc/self/cgroup     # v2 는 "0::/path" 형식 한 줄
```

v2 의 또 다른 핵심은 **no-internal-process 규칙**: 컨트롤러가 활성화된 cgroup 은 자식 그룹에만 프로세스를 둘 수 있고 자신에는 직접 둘 수 없다(루트 제외). 그리고 부모가 `cgroup.subtree_control` 로 자식에게 컨트롤러를 **위임(delegation)** 한다. 컨테이너 런타임(systemd, containerd)이 이 위임 모델 위에서 동작한다.

```bash
# 부모에서 자식들이 cpu/memory 를 쓰도록 위임
echo "+cpu +memory" > /sys/fs/cgroup/myslice/cgroup.subtree_control
```

오늘날 주요 배포판(예: Ubuntu 22.04+, RHEL 9+)과 systemd, 최신 Kubernetes/containerd 는 cgroup v2 를 기본으로 쓴다. 운영 환경이 v1 인지 v2 인지에 따라 파일 경로와 의미가 달라지므로 진단 전 확인이 필요하다.

## 2. 메모리 컨트롤러 — max / high / low / min

v2 메모리 제어는 4개 핵심 인터페이스로 나뉘다. 단순한 하드 리밋 하나였던 v1(`memory.limit_in_bytes`)보다 정교하다.

`memory.max` 는 **하드 리밋** 이다. 이 값을 초과하면 먼저 reclaim(페이지 회수)을 시도하고, 회수해도 못 줄이면 cgroup OOM Killer 가 그 cgroup 내 프로세스를 죽인다. `memory.high` 는 **소프트 throttle 리밋** 이다. 초과하면 죽이지 않고 해당 cgroup 프로세스의 할당을 강하게 조절(throttle)하며 reclaim 을 압박한다 — 즉 "느려지지만 죽지 않는" 완충지대다. `memory.low`/`memory.min` 은 **보호선** 으로, 시스템 전역 메모리 압박 시에도 이 양까지는 회수당하지 않도록 보장한다(min 은 절대 보호, low 는 best-effort).

```bash
cd /sys/fs/cgroup/myapp.scope
echo 512M > memory.max      # 하드 리밋
echo 450M > memory.high     # 이 위로는 throttle + 적극 reclaim
cat memory.current          # 현재 사용량
cat memory.events           # low/high/max/oom/oom_kill 카운터
cat memory.stat             # anon, file, slab, pgfault 등 상세
```

`memory.events` 의 `oom_kill` 증가는 컨테이너가 OOM 으로 재시작됐다는 직접 증거다. Kubernetes 에서 보는 `OOMKilled`(exit code 137)이 바로 이 cgroup OOM 의 결과다.

## 3. OOM Killer — 전역 vs cgroup-local

OOM Killer 에는 두 층위가 있다. **전역 OOM** 은 머신 전체 메모리가 고갈됐을 때 커널이 `oom_score` 가 높은 프로세스를 골라 죽인다. **cgroup OOM** 은 특정 cgroup 이 `memory.max` 를 넘겨 회수 불가일 때 그 cgroup *내부* 에서만 희생자를 고른다. 컨테이너 환경에서 보는 OOM 의 대부분은 후자다 — 호스트는 멀졘한데 컨테이너 하나만 제 리밋에 부딪혀 죽는다.

`memory.oom.group` 을 1 로 설정하면 cgroup OOM 시 그 그룹의 **모든 프로세스를 함께** 죽인다(부분만 죽어 좀비 상태가 되는 것을 방지). 컨테이너의 PID 1 과 자식들이 한 묶음으로 정리되어야 할 때 유용하다.

```bash
echo 1 > /sys/fs/cgroup/myapp.scope/memory.oom.group
```

**중요한 함정**: JVM 같은 런타임은 컨테이너 메모리를 인식해야 한다. 과거 JVM 은 cgroup 리밋을 못 보고 호스트 전체 메모리를 기준으로 힙을 잡아 OOMKilled 가 빈발했다. 현재 JVM(JDK 10+, 특히 15+)은 `-XX:+UseContainerSupport`(기본 켜짐)로 cgroup v1/v2 리밋을 읽어 `MaxRAMPercentage` 기준으로 힙을 산정한다. 그래도 힙 외 영역(메타스페이스, 스레드 스택, 다이렉트 버퍼, 코드 캐시)이 리밋을 밀어올려 OOM 이 나므로, `memory.max` 는 힙 + 비힙 + 버퍼를 합친 값보다 여유 있게 잡아야 한다.

## 4. CPU cfs_quota / period — throttling 의 본질

CPU 제한은 CFS(Completely Fair Scheduler)의 **bandwidth control** 로 구현된다. v2 에서는 `cpu.max` 한 파일에 `"$QUOTA $PERIOD"` 형식으로 들어간다(단위 마이크로초).

```bash
# 매 100ms(period) 중 최대 50ms(quota) CPU 사용 = 0.5 코어
echo "50000 100000" > /sys/fs/cgroup/myapp.scope/cpu.max
echo "max 100000"   > .../cpu.max   # 무제한
```

동작 원리: 각 period(기본 100ms)마다 quota 만큼의 "런타임 예산" 이 충전된다. cgroup 의 태스크들이 그 period 안에 예산을 다 쓰면, **다음 period 까지 강제로 멈춘다(throttled)**. 이게 throttling 이며, 여기서 가장 중요한 운영 함정이 나온다.

**throttling 은 latency 를 망친다.** quota=0.5코어인데 어떤 요청이 짧게 1코어를 풀로 쓰려 하면, 50ms 만에 예산이 소진되고 남은 50ms 를 강제 대기한다 → 그 요청은 최소 50ms 의 인위적 지연을 먹는다. 평균 CPU 사용률은 낮은데 p99 지연이 튀는 전형적 원인이다. 멀티스레드 앱은 더 심하다 — 4스레드가 동시에 돌면 예산을 4배 빨르게(12.5ms 만에) 소진해 더 자주 throttle 된다.

```bash
# throttling 진단 — 핵심 지표
cat /sys/fs/cgroup/myapp.scope/cpu.stat
# nr_periods         : 경과한 period 수
# nr_throttled       : throttle 당한 period 수
# throttled_usec     : 누적 throttle 시간(마이크로초)
```

`nr_throttled / nr_periods` 가 높으면(예: 20% 이상) CPU 리밋이 워크로드의 버스트를 못 받쳐 지연을 유발하고 있다는 신호다.

## 5. requests/limits 가 cgroup 으로 매핑되는 방식

Kubernetes 의 `resources` 설정은 cgroup 파일로 번역된다.

| K8s 설정 | cgroup v2 매핑 | 의미 |
|---|---|---|
| `cpu.requests: 500m` | `cpu.weight`(비례 배분) | 경합 시 점유 비율 |
| `cpu.limits: 1` | `cpu.max = "100000 100000"` | 하드 throttle 상한 |
| `memory.requests` | (스케줄링/`memory.low` 힌트) | 스케줄러 배치 기준 |
| `memory.limits: 512Mi` | `memory.max = 512M` | 초과 시 OOMKilled |

여기서 실무 권고가 갈린다. **메모리 limit 은 설정하되, CPU limit 은 신중히** — CPU limit 을 너무 빡빡하게 걸면 §4 의 throttling 으로 지연이 튀다. 많은 SRE 팀이 "CPU requests 만 두고 limit 은 생략하거나 넓넓히" 두는 이유다. 반대로 메모리는 초과 시 OOMKilled 라는 치명적 결과라서 limit = request 로 두어 예측 가능성을 높이는 경우가 많다.

```yaml
resources:
  requests: { cpu: "500m", memory: "512Mi" }
  limits:   { memory: "512Mi" }   # CPU limit 의도적 생략 — throttle 회피
```

## 6. PSI — 압박을 정량화하기

cgroup v2 의 강력한 기능이 **PSI(Pressure Stall Information)** 다. CPU/메모리/IO 각각에 대해 "리소스 부족으로 태스크가 멈춘 시간 비율" 을 직접 노출한다.

```bash
cat /sys/fs/cgroup/myapp.scope/cpu.pressure
# some avg10=12.34 avg60=8.10 avg300=5.00 total=...
cat .../memory.pressure
cat .../io.pressure
```

`some` 은 일부 태스크가 멈춘 비율, `full` 은 모든 태스크가 동시에 멈춘 비율이다. memory.pressure 의 `full` avg10 이 0 보다 크다면 그 컨테이너는 메모리 부족으로 실질적 작업 정지를 겪고 있다는 뜻 — OOMKilled 직전 신호이거나 과도한 reclaim 의 증거다. PSI 는 "평균 사용률은 괜챮은데 왜 느림?" 를 설명하는 가장 직접적인 지표라서, 사용률 그래프보다 PSI 를 우선 보는 것이 진단 효율이 높다.

## 7. 진단 워크플로 정리

컨테이너가 느리거나 죽을 때 순서는 이렇다. 첫째, `memory.events` 의 `oom_kill` 과 exit code 137 로 OOM 여부 확정. OOM 이면 `memory.max` 상향 또는 앱 메모리 누수 점검(JVM 이면 비힙 영역 합산). 둘째, OOM 이 아닌데 지연이 튀면 `cpu.stat` 의 `nr_throttled` 로 CPU throttling 확인 → CPU limit 완화 또는 스레드 수 조정. 셋째, PSI 의 `memory.pressure`/`io.pressure` 로 reclaim/디스크 압박을 교차 확인. 넷째, `memory.stat` 으로 anon(힙)/file(페이지 캐시)/slab 비중을 보고 누수 위치를 좁힌다. 이 네 파일(`memory.events`, `cpu.stat`, `*.pressure`, `memory.stat`)만으로 컨테이너 리소스 문제의 대부분을 설명할 수 있다.

## 8. cpu.weight vs cpu.max — 비례 배분과 하드 측의 차이

CPU 제어에는 성격이 다른 두 손잡이가 있다. `cpu.max`(§4)는 **절대 상한** 이라 시스템이 한가해도 quota 이상은 못 쓴다. 반면 `cpu.weight`(v1 의 `cpu.shares` 에 대응, 기본 100, 범위 1~10000)는 **경합 시의 비례 배분** 만 정한다 — 두 cgroup 의 weight 가 100:200 이면 CPU 가 부족할 때 1:2 로 나누지만, 한쪽이 놀면 다른 쪽이 코어를 다 써도 된다.

이 차이가 운영 판단을 가른다. weight 만 쓰면 유휴 CPU 를 버스트로 활용해 지연이 좋지만, 한 컨테이너가 "이웃" 의 CPU 를 일시적으로 다 가져갈 수 있어 격리가 약하다(noisy neighbor). cpu.max 는 격리는 강하지만 §4 의 throttling 으로 버스트를 죽인다. Kubernetes 의 `requests` 가 weight 로, `limits` 가 cpu.max 로 번역되는 이유다(§5). 지연이 중요한 서비스에서 CPU limit 을 빼고 request 만 두자는 권고는 "weight 로 공정 배분은 하되 하드 측으로 버스트를 죽이지는 말자" 는 뜻이다.

```bash
# 경합 시 이 그룹이 기본의 2배 CPU 점유 (유휴 시엔 더 써도 됨)
echo 200 > /sys/fs/cgroup/important.scope/cpu.weight
echo max > /sys/fs/cgroup/important.scope/cpu.max   # 하드 측 없음
```

## 9. 실전 진단 — OOMKilled 와 throttling 오인 사례

현장에서 가장 잔 오진은 "느려서 CPU 를 올렸는데 안 나아짐" 이다. 실제로는 메모리 reclaim(페이지 캐시 부족)이나 throttling 이 원인인 경우가 많다. 아래는 한 컨테이너를 빠르게 분류하는 1분 진단 스니펫이다.

```bash
CG=/sys/fs/cgroup/$(awk -F: '{print $3}' /proc/self/cgroup)
echo "== OOM ==";       grep -E 'oom|oom_kill' $CG/memory.events
echo "== mem ==";       echo "current=$(cat $CG/memory.current) max=$(cat $CG/memory.max)"
echo "== throttle ==";  awk '/nr_periods|nr_throttled|throttled_usec/' $CG/cpu.stat
echo "== pressure ==";  head -1 $CG/cpu.pressure; head -1 $CG/memory.pressure; head -1 $CG/io.pressure
```

판독 기준은 명확하다. `oom_kill > 0` 이면 메모리 부족 → limit 상향 또는 누수 점검(JVM 은 힙 외 영역 합산, §3). `nr_throttled/nr_periods` 가 높으면 CPU 측이 버스트를 죽이는 중 → limit 완화 또는 §8 의 weight 기반 전환. `memory.pressure` 의 full 이 0 보다 크면 reclaim 으로 작업이 멈추는 중 → 메모리 증설 또는 페이지 캐시 의존 워크로드 재검토. 세 신호가 모두 깨끗한데 느리면 cgroup 문제가 아니라 애플리케이션/네트워크/다운스트림을 봐야 한다.

마지막 주의: cgroup v1 환경에서는 위 파일 경로와 일부 의미가 다르다(`memory.limit_in_bytes`, `cpu.cfs_quota_us`/`cpu.cfs_period_us`, PSI 미지원). 진단 스크립트를 배포 전 `cat /sys/fs/cgroup/cgroup.controllers` 로 v2 여부를 먼저 분기하는 것이 안전하다.

## 참고

- Linux Kernel Documentation — Control Group v2 (`Documentation/admin-guide/cgroup-v2.rst`)
- Kernel Documentation — PSI (Pressure Stall Information)
- "Throttling: stop or go" — CFS bandwidth control 커널 문서
- Kubernetes 문서 — Resource Management for Pods and Containers
