Notion 원본: https://app.notion.com/p/3db5a06fd6d381459e58e85c98e15ec0?pvs=204

# cgroup v2 통합 계층과 메모리 압박 지표 PSI 및 컨테이너 OOM 진단

> 2026-09-14 신규 주제 · 확장 대상: Docker·Kubernetes 리소스 관리

## 학습 목표

- cgroup v2 통합 계층의 위임 규칙과 제약을 파일시스템 수준에서 해독한다
- 메모리·CPU 인터페이스 파일로 스로틀링과 OOM 의 원인을 구분해 낸다
- PSI 지표를 읽어 사용률만으로는 보이지 않는 포화 상태를 조기에 포착한다
- 137 로 죽은 파드를 정해진 순서로 추적해 근본 원인까지 좁힌다

## 1. v1 의 분리 계층이 남긴 문제

cgroup v1 은 컨트롤러마다 **독립된 계층**을 마운트한다. `/sys/fs/cgroup/memory`, `/sys/fs/cgroup/cpu`, `/sys/fs/cgroup/blkio` 가 각각 자기 트리를 갖고, 같은 프로세스가 트리마다 다른 위치에 속할 수 있다.

```bash
# v1: 컨트롤러별 마운트가 따로 존재
$ mount | grep cgroup
cgroup on /sys/fs/cgroup/memory type cgroup (rw,memory)
cgroup on /sys/fs/cgroup/cpu,cpuacct type cgroup (rw,cpu,cpuacct)
cgroup on /sys/fs/cgroup/blkio type cgroup (rw,blkio)
```

유연해 보이지만 대가가 컸다. 가장 뼈아픈 것은 **메모리와 io 를 함께 볼 수 없다**는 점이다. 페이지 캐시 라이트백은 메모리 컨트롤러가 회수를 유발하고 blkio 가 디스크를 때리는 협업 작업인데, 두 컨트롤러가 서로 다른 트리에 있으면 "이 쓰기가 어느 cgroup 소유인가"를 커널이 판정할 수 없다. 그래서 v1 의 blkio 는 사실상 direct I/O 만 제어했고 버퍼드 쓰기는 제한을 빠져나갔다.

v2 는 계층을 하나로 합쳤다. 프로세스는 트리에서 정확히 한 노드에 속하고, 모든 컨트롤러가 그 한 위치를 공유한다.

```bash
$ mount | grep cgroup2
cgroup2 on /sys/fs/cgroup type cgroup2 (rw,nsdelegate,memory_recursiveprot)

$ cat /sys/fs/cgroup/cgroup.controllers
cpuset cpu io memory hugetlb pids rdma misc
```

`cgroup.controllers` 는 **이 cgroup 에서 쓸 수 있는** 컨트롤러 목록이고, `cgroup.subtree_control` 은 **자식에게 위임한** 컨트롤러 목록이다. 부모가 위임하지 않으면 자식의 `cgroup.controllers` 는 비어 있다. 위임은 한 단계씩 명시적으로 내려간다.

```bash
$ cd /sys/fs/cgroup && mkdir -p app/web
$ echo "+cpu +memory +pids" > cgroup.subtree_control
$ cat app/cgroup.controllers
cpu memory pids
$ echo "+memory" > app/cgroup.subtree_control   # web 에 memory 만 추가 위임
$ cat app/web/cgroup.controllers
memory
```

여기에 v2 의 대표적 제약인 **no internal process** 규칙이 붙는다. 루트를 제외하면, 자식에게 컨트롤러를 위임한 cgroup 은 자기 자신이 프로세스를 직접 가질 수 없다. 자원 분배의 경쟁 단위를 "형제 cgroup 들"로 일관되게 유지하기 위한 규칙이다. 프로세스와 cgroup 이 섞여 경쟁하면 가중치 계산의 의미가 무너지기 때문이다.

```bash
$ echo 4242 > /sys/fs/cgroup/app/cgroup.procs
bash: echo: write error: Device or resource busy   # subtree_control 이 비어 있지 않음
```

실무에서 이 에러는 컨테이너 런타임을 직접 만지거나 systemd 없이 cgroup 을 수동 구성할 때 반드시 한 번은 만난다. 해법은 `app/leaf` 같은 리프 cgroup 을 하나 더 만들어 프로세스를 거기에 넣는 것이다.

## 2. 메모리 컨트롤러의 네 가지 경계선

v2 메모리 컨트롤러는 상한 두 개와 하한 두 개를 제공한다. 이 네 파일의 역할 차이를 구분하는 것이 진단의 출발점이다.

| 파일 | 성격 | 초과 시 동작 | 기본값 |
|---|---|---|---|
| `memory.min` | 하드 보호 | 이 값까지는 회수 대상에서 제외 | 0 |
| `memory.low` | 소프트 보호 | 다른 곳에 여유 없으면 회수 허용 | 0 |
| `memory.high` | 소프트 상한 | 할당 경로에서 강제 회수 + 스로틀 | max |
| `memory.max` | 하드 상한 | 회수 실패 시 cgroup OOM kill | max |

핵심은 `memory.high` 와 `memory.max` 의 차이다. `high` 를 넘으면 프로세스는 **죽지 않고 느려진다**. 커널이 할당 경로에서 직접 회수를 수행하고, 초과량에 비례해 인위적 지연을 삽입한다. 반면 `max` 는 회수로도 못 내리면 OOM 킬러를 호출한다.

```bash
$ CG=/sys/fs/cgroup/app/web
$ echo "1G" > $CG/memory.max
$ echo "768M" > $CG/memory.high      # max 의 75% 지점에서 브레이크
$ echo "256M" > $CG/memory.min       # 이만큼은 어떤 경우에도 보호
$ cat $CG/memory.current
812445696
```

`memory.current` 는 anon + 페이지 캐시 + 커널 메모리를 모두 합친 값이다. 컨테이너가 리밋에 붙어 보여도 대부분이 재사용 가능한 페이지 캐시라면 위험하지 않다. 구성을 보려면 `memory.stat` 을 읽는다.

```bash
$ grep -E '^(anon|file|slab|sock|kernel_stack|file_dirty) ' $CG/memory.stat
anon 654311424        # 회수 불가 — 실제 위험 신호
file 143654912        # 페이지 캐시 — 압박 시 회수됨
kernel_stack 2359296
slab 9871360
sock 1048576
file_dirty 4194304
```

가장 값싼 조기 경보는 `memory.events` 다. 누적 카운터라서 폴링만으로 증가분을 볼 수 있다.

```bash
$ cat $CG/memory.events
low 0
high 2841        # high 초과로 스로틀된 횟수 — 리밋 상향 신호
max 137          # max 에 부딪혀 강제 회수한 횟수
oom 3            # 회수 실패로 OOM 진입
oom_kill 3       # 실제로 죽인 프로세스 수
```

`high` 만 오르고 `oom_kill` 이 0 이면 애플리케이션은 살아 있지만 지연이 커지는 중이다. 이 상태는 `kubectl top` 의 사용률 그래프에 거의 드러나지 않는다. 반대로 `oom_kill` 이 오르면 이미 프로세스가 죽었다.

## 3. CPU 스로틀링과 런타임의 코어 오인

`cpu.max` 는 `"$QUOTA $PERIOD"` 두 값을 마이크로초로 받는다. `100000 100000` 이 1코어다.

```bash
$ echo "150000 100000" > $CG/cpu.max   # 1.5 코어
$ echo "200" > $CG/cpu.weight          # 상대 가중치 (기본 100, 범위 1~10000)
```

`cpu.weight` 는 경쟁이 있을 때만 의미가 있는 비례 배분이고, `cpu.max` 는 유휴 CPU 가 남아 있어도 자르는 절대 상한이다. 후자가 지연 스파이크의 주범이다.

```bash
$ cat $CG/cpu.stat
usage_usec 184023941
nr_periods 41288
nr_throttled 5317        # 41288 주기 중 5317 회 = 12.9% 스로틀
throttled_usec 21774320  # 누적 21.7 초 정지
```

`nr_throttled / nr_periods` 가 **5% 를 넘으면** 꼬리 지연을 의심할 값이다. 100ms 주기에서 quota 를 소진하면 남은 주기 동안 완전히 멈추므로, p99 에 수십 ms 단위 계단이 생긴다. GC 스레드나 비동기 워커가 순간적으로 몰리는 JVM 에서 특히 잘 나타난다.

JVM 은 `UseContainerSupport`(JDK 10+, 8u191+ 기본 활성) 로 `cpu.max` 를 읽어 `Runtime.availableProcessors()` 를 계산한다. quota/period 를 올림한 값이라 `cpu.max = 150000 100000` 이면 2 를 반환한다.

```bash
$ java -XX:+PrintFlagsFinal -version | grep -E 'ActiveProcessorCount|MaxRAMPercentage'
     intx ActiveProcessorCount  = -1
   double MaxRAMPercentage      = 25.000000
```

기본 `MaxRAMPercentage` 가 25% 라는 점이 함정이다. 리밋 1GiB 컨테이너에서 힙은 256MiB 로 잡히고, 나머지 768MiB 를 메타스페이스·코드 캐시·스레드 스택·다이렉트 버퍼가 쓰다가 `memory.max` 에 먼저 부딪힌다. 이 경우 힙 덤프는 깨끗한데 컨테이너는 137 로 죽는다.

| 런타임 | 기본 인식 | 오인 증상 | 대응 |
|---|---|---|---|
| JVM 힙 | 리밋의 25% | 힙 여유, 컨테이너 OOM | `-XX:MaxRAMPercentage=70` |
| JVM 코어 | quota 올림 | quota<1 이면 1 코어 | `-XX:ActiveProcessorCount=N` |
| JVM 네이티브 | 미집계 | RSS 가 힙보다 훨씬 큼 | `-XX:MaxDirectMemorySize`, NMT |
| Node.js 힙 | **호스트 RAM 기준** | 리밋 무시하고 증가 | `--max-old-space-size=<MiB>` |
| Node libuv | 스레드풀 4 고정 | fs/crypto 병목 | `UV_THREADPOOL_SIZE` |
| `os.cpus()` | 호스트 코어 수 | 워커 과다 생성 | 리밋 값을 env 로 주입 |

## 4. io 와 pids 컨트롤러

`io.max` 는 장치 번호와 함께 대역·IOPS 상한을 지정한다. 통합 계층 덕분에 버퍼드 쓰기도 소유 cgroup 으로 귀속된다.

```bash
$ lsblk -d -o NAME,MAJ:MIN nvme0n1
NAME     MAJ:MIN
nvme0n1  259:0
$ echo "259:0 rbps=104857600 wbps=52428800 riops=8000 wiops=4000" > $CG/io.max
```

`io.max` 는 유휴 디스크에서도 자르기 때문에 배치 작업 격리에는 `io.latency` 가 낫다. 목표 지연을 넘기 시작할 때만 **다른 cgroup 을** 조이는 방식이라 평시 처리량을 희생하지 않는다.

```bash
$ echo "259:0 target=10000" > /sys/fs/cgroup/app/critical/io.latency  # 10ms 보장
```

`pids` 컨트롤러는 fork 폭탄과 스레드 누수를 막는다. 리밋 도달 시 `fork()` 가 `EAGAIN` 으로 실패하고, 애플리케이션 로그에는 `OutOfMemoryError: unable to create native thread` 로 나타나 메모리 문제로 오인되기 쉽다.

```bash
$ echo 1024 > $CG/pids.max
$ cat $CG/pids.current $CG/pids.events
1024
max 57
```

## 5. PSI — 사용률이 놓치는 포화

사용률은 "얼마나 썼는가"만 말할 뿐 "얼마나 기다렸는가"를 말하지 않는다. CPU 100% 는 정상 포화일 수도, 대기열이 폭증하는 재앙일 수도 있다. PSI(Pressure Stall Information, 커널 4.20+, `CONFIG_PSI=y`)는 **자원 부족으로 실제 작업이 지연된 시간 비율**을 직접 측정한다.

```bash
$ cat /proc/pressure/memory
some avg10=4.21 avg60=2.88 avg300=1.02 total=8123445
full avg10=1.07 avg60=0.63 avg300=0.21 total=2011983
```

`some` 은 **하나 이상의** 태스크가 해당 자원 때문에 멈춘 시간 비율, `full` 은 **실행 가능한 모든** 태스크가 동시에 멈춰 CPU 가 놀고 있던 시간 비율이다. `full` 은 생산성이 0 이 된 구간이므로 훨씬 심각하다. `total` 은 누적 마이크로초라서 두 시점 차이로 정확한 구간 값을 계산할 수 있다.

cgroup 별로도 같은 형식이 제공된다.

```bash
$ for r in cpu memory io; do printf '%-7s %s\n' "$r" "$(head -1 $CG/$r.pressure)"; done
cpu     some avg10=12.40 avg60=9.11 avg300=6.80
memory  some avg10=31.75 avg60=24.02 avg300=11.43
io      some avg10=18.22 avg60=15.90 avg300=9.66
```

위 예시는 교과서적 패턴이다. 메모리 압박이 커지며 회수가 늘고, 회수가 디스크 리드를 유발해 io 압박이 따라 오르고, 회수 작업 자체가 CPU 를 먹는다. 이 시점 `memory.current` 는 리밋의 85% 정도라 사용률 알람은 울리지 않는다. 하지만 `memory some` 이 30% 라는 것은 이미 작업 시간의 3 분의 1 을 페이지 대기에 쓰고 있다는 뜻이다.

```bash
# 60 초 간격 total 차분으로 실제 정지 시간 산출
$ A=$(awk '/^full/{sub("total=","",$5); print $5}' $CG/memory.pressure); sleep 60
$ B=$(awk '/^full/{sub("total=","",$5); print $5}' $CG/memory.pressure)
$ echo "stall $(( (B-A)/1000 )) ms / 60000 ms"
stall 4820 ms / 60000 ms
```

경보 임계는 `memory full avg60 > 10`, `cpu some avg60 > 20`, `io full avg60 > 5` 정도에서 시작해 워크로드에 맞춰 조정한다. OOM 이 나기 수 분 전부터 압박이 상승하므로, 이 지표는 사후 확인이 아니라 예측에 쓸 수 있다는 점이 사용률 대비 결정적 이점이다.

## 6. OOM 의 두 종류와 종료 코드 137

혼동을 부르는 지점은 OOM 이 서로 다른 두 경로에서 발생한다는 사실이다.

| 구분 | 전역 OOM | cgroup OOM |
|---|---|---|
| 계기 | 호스트 전체 메모리 고갈 | `memory.max` 초과 + 회수 실패 |
| 희생자 선정 | 전 시스템에서 `oom_score` 최대 | 해당 cgroup 내부에서만 |
| dmesg 첫 줄 | `Out of memory: Killed process` | `Memory cgroup out of memory` |
| 파급 | 무관한 파드까지 죽음 | 해당 컨테이너로 한정 |

```bash
$ dmesg -T | grep -i -A2 'out of memory' | tail -6
[Mon Sep 14 03:12:44 2026] Memory cgroup out of memory: Killed process 18324 (java)
  total-vm:4921884kB, anon-rss:1042112kB, file-rss:28160kB, shmem-rss:0kB,
  UID:1000 pgtables:2984kB oom_score_adj:-997
```

`anon-rss` 가 리밋에 근접했는지가 첫 판단 기준이고, `oom_score_adj` 는 어느 QoS 클래스였는지를 역으로 알려 준다. `memory.oom.group` 을 1 로 두면 cgroup 내 한 프로세스가 아니라 **전체 태스크를 한꺼번에** 죽인다. 사이드카가 남아 좀비 파드가 되는 상황을 막을 수 있다.

```bash
$ echo 1 > $CG/memory.oom.group
```

종료 코드 137 은 `128 + 9`, 즉 SIGKILL 로 끝났다는 의미일 뿐이다. **OOM 만이 아니라** `kubectl delete` 의 graceful 타임아웃 초과, liveness 실패 후 강제 종료도 같은 137 을 낸다. 따라서 137 을 본 순간 곧바로 메모리 증설로 가면 안 되고, `reason: OOMKilled` 인지 먼저 확인해야 한다.

## 7. Kubernetes 매핑과 systemd 드라이버

kubelet 은 requests/limits 를 cgroup 파일로 번역한다. QoS 클래스는 이 번역 결과와 회수 우선순위를 결정한다.

| 스펙 | cgroup v2 반영 |
|---|---|
| `limits.memory` | `memory.max` |
| `requests.memory` | `memory.min` (MemoryQoS 게이트 시) |
| `limits.cpu` | `cpu.max` = `quota 100000` |
| `requests.cpu` | `cpu.weight` (shares 를 1~10000 로 환산) |

```yaml
resources:
  requests: { memory: "512Mi", cpu: "500m" }
  limits:   { memory: "1Gi",   cpu: "1500m" }
# → memory.max=1073741824, cpu.max="150000 100000", cpu.weight≈20
```

`requests == limits` 를 CPU·메모리 모두에 지정하면 Guaranteed, 일부만 지정하면 Burstable, 아무것도 없으면 BestEffort 다. kubelet 은 이에 따라 `oom_score_adj` 를 다르게 설정한다. Guaranteed 는 -997 로 사실상 마지막까지 보호되고, BestEffort 는 1000 으로 전역 OOM 시 가장 먼저 죽는다. Burstable 은 요청량 비율로 그 사이 값을 받는다.

systemd 드라이버(`--cgroup-driver=systemd`)를 쓰면 경로가 slice/scope 구조를 따른다.

```bash
$ POD=$(kubectl get pod web-0 -o jsonpath='{.metadata.uid}' | tr - _)
$ find /sys/fs/cgroup/kubepods.slice -name "*${POD}*" -maxdepth 2
/sys/fs/cgroup/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod<uid>.slice

$ systemctl show docker.service -p MemoryMax -p CPUQuotaPerSecUSec
MemoryMax=2147483648
CPUQuotaPerSecUSec=2s
```

cgroupfs 와 systemd 드라이버를 kubelet·컨테이너 런타임에서 **서로 다르게** 설정하면 두 관리자가 같은 트리를 다투게 되고, 재시작 직후 리밋이 사라지거나 파드가 무작위로 죽는 재현 어려운 장애가 된다. 노드 부팅 시 두 설정이 일치하는지 점검하는 것이 기본이다.

## 8. 137 추적 절차

순서를 고정해 두면 원인 구간이 빠르게 좁혀진다.

```bash
# 1) OOMKilled 인지, 단순 SIGKILL 인지
$ kubectl describe pod web-0 | grep -A4 'Last State'
      Reason: OOMKilled
      Exit Code: 137

# 2) 컨테이너 cgroup 에서 카운터 확인
$ kubectl debug node/n1 -it --image=busybox -- \
    grep -E 'oom|high' /host/sys/fs/cgroup/kubepods.slice/.../memory.events
high 0
oom_kill 1

# 3) 전역인지 cgroup 인지 커널 로그로 확정
$ kubectl debug node/n1 -it --image=busybox -- dmesg -T | grep -i 'out of memory'

# 4) 압박 추세로 급증인지 누수인지 판별
$ ... cat .../memory.pressure

# 5) 힙과 RSS 를 분리해 네이티브 누수 확인
$ jcmd 1 VM.native_memory summary | head -12
```

`high 0` 인데 `oom_kill 1` 이면 점진 증가가 아니라 **한 번에 큰 할당**이 리밋을 넘긴 경우다. 대용량 조회 결과를 통째로 메모리에 적재하는 코드가 전형적이다. 반대로 `high` 가 수천 회 누적된 뒤 죽었다면 서서히 차오른 누수이거나 리밋 자체가 작은 것이다.

5 단계에서 힙 사용량은 400MiB 인데 RSS 가 950MiB 라면 범인은 네이티브 영역이다. 스레드 1개당 스택 1MiB 를 쓰므로 스레드 300개면 그것만 300MiB 이고, 여기에 메타스페이스·코드 캐시·Netty 다이렉트 버퍼·glibc malloc 아레나가 더해진다. 컨테이너에서는 `MALLOC_ARENA_MAX=2` 설정이 수백 MiB 를 줄이는 경우가 흔하다.

```bash
$ env MALLOC_ARENA_MAX=2 java -XX:MaxRAMPercentage=70 \
    -XX:MaxDirectMemorySize=128m -XX:NativeMemoryTracking=summary -jar app.jar
```

## 9. 운영 기본값

리밋만 올리는 대응은 문제를 노드 전체로 미룬다. 권장 순서는 `memory.high` 를 `memory.max` 의 70~80% 에 두어 완충 구간을 만들고, PSI 기반 경보로 OOM 이전에 개입하며, JVM·Node 의 인식 값을 명시적으로 고정하는 것이다. Guaranteed QoS 는 노드 압박 시 보호를 받지만 스케줄링 여유가 줄어드는 비용이 있으므로, 지연에 민감한 서비스에만 선택적으로 적용한다.

주의할 점은 Kubernetes 가 파드 스펙으로 `memory.high` 를 직접 노출하지 않는다는 것이다. MemoryQoS 알파 기능 게이트를 켜면 kubelet 이 `requests.memory` 를 `memory.min` 으로, 그리고 `memory.high` 를 `memory.throttlingFactor` 기반으로 계산해 설정한다. 게이트를 켤 수 없는 환경이라면 특권 데몬셋으로 노드의 cgroup 파일을 직접 조정하는 우회가 있지만, kubelet 이 파드를 재동기화할 때 값을 되돌릴 수 있으므로 주기적 재적용이 필요하다. 어느 쪽이든 "커널이 제공하는 기능이 곧 오케스트레이터가 노출하는 기능은 아니다"라는 점을 전제로 설계해야 한다.

```yaml
# kubelet 설정 — MemoryQoS 로 memory.min / memory.high 를 자동 설정
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
featureGates:
  MemoryQoS: true
# memory.high = requests + (limits - requests) * throttlingFactor
memoryThrottlingFactor: 0.8
```

런타임 인식 값을 고정하는 것도 파드 스펙에서 선언적으로 처리하는 편이 안전하다. Downward API 로 리밋을 환경 변수에 주입하면 애플리케이션이 호스트가 아니라 자기 cgroup 기준으로 워커 수를 계산할 수 있다.

```yaml
spec:
  containers:
    - name: api
      image: acme/api:1.42
      resources:
        requests: { memory: "1Gi", cpu: "1" }
        limits:   { memory: "1Gi", cpu: "2" }   # memory 는 동일 → OOM 예측 가능
      env:
        - name: MEM_LIMIT_BYTES
          valueFrom:
            resourceFieldRef: { resource: limits.memory }
        - name: CPU_LIMIT
          valueFrom:
            resourceFieldRef: { resource: limits.cpu, divisor: "1" }
        - name: MALLOC_ARENA_MAX
          value: "2"
        - name: JAVA_TOOL_OPTIONS
          value: >-
            -XX:MaxRAMPercentage=70
            -XX:MaxDirectMemorySize=128m
            -XX:NativeMemoryTracking=summary
            -XX:+ExitOnOutOfMemoryError
```

`-XX:+ExitOnOutOfMemoryError` 는 힙 고갈 시 JVM 을 즉시 종료시켜 쿠버네티스가 재시작하게 만든다. 이 옵션이 없으면 `OutOfMemoryError` 를 스레드 하나가 삼키고 프로세스는 살아남아, liveness 는 통과하는데 요청만 실패하는 최악의 반쯤 죽은 상태가 된다.

메모리 리밋과 리퀘스트를 같게 두는 것은 취향이 아니라 예측 가능성의 문제다. 메모리는 CPU 와 달리 압축 불가능(incompressible)한 자원이라 초과분을 스로틀로 흡수할 수 없고, 리밋이 리퀘스트보다 크면 "스케줄러는 512Mi 기준으로 배치했는데 실제로는 1Gi 를 쓰는" 노드 오버커밋이 생긴다. 반면 CPU 는 리밋을 리퀘스트보다 크게 두어 버스트를 허용하는 편이 낫고, 지연이 극도로 민감한 서비스가 아니라면 `limits.cpu` 를 아예 생략해 `cpu.max = max` 로 두는 선택지도 진지하게 고려할 만하다. `cpu.weight` 만으로도 경쟁 시 배분은 보장되기 때문이다.

마지막으로 관측 항목을 정리한다. cAdvisor 가 노출하는 메트릭 중 `container_memory_working_set_bytes`(= `memory.current` - 비활성 파일 캐시)를 리밋 대비 비율로 보고, `container_cpu_cfs_throttled_periods_total / container_cpu_cfs_periods_total` 로 스로틀 비율을, `kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}` 로 OOM 발생을 감시한다. 여기에 노드 익스포터의 PSI 메트릭을 더하면 "압박 상승 → 스로틀 증가 → OOM" 이라는 인과 사슬을 하나의 대시보드에서 시간축으로 읽을 수 있다.

```promql
# 워킹셋이 리밋의 85% 를 5분 이상 넘는 컨테이너
100 * container_memory_working_set_bytes
  / on(pod, container) kube_pod_container_resource_limits{resource="memory"} > 85

# CPU 스로틀 비율 5% 초과
rate(container_cpu_cfs_throttled_periods_total[5m])
  / rate(container_cpu_cfs_periods_total[5m]) > 0.05
```

## 참고

- [Control Group v2 — Linux kernel documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html)
- [PSI - Pressure Stall Information](https://docs.kernel.org/accounting/psi.html)
- [PSI: Pressure Stall Information overview](https://facebookmicrosites.github.io/psi/docs/overview)
- [About cgroup v2 — Kubernetes](https://kubernetes.io/docs/concepts/architecture/cgroups/)
- [Pod Quality of Service Classes — Kubernetes](https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/)
- [Resource Management for Pods and Containers — Kubernetes](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/)
- [Configuring a cgroup driver — Kubernetes](https://kubernetes.io/docs/tasks/administer-cluster/kubeadm/configure-cgroup-driver/)
- [systemd.resource-control(5)](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html)
- [java Command Reference — Oracle JDK 21](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)
