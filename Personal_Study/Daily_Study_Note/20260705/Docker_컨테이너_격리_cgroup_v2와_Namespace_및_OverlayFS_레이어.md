Notion 원본: https://app.notion.com/p/3935a06fd6d381b39530c3c64fcbc09d

# Docker 컨테이너 격리 cgroup v2와 Namespace 및 OverlayFS 레이어

> 2026-07-05 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- 컨테이너 격리를 구성하는 리눅스 namespace 7종의 역할을 구분한다
- cgroup v2 통합 계층과 CPU/메모리 컨트롤러의 실제 제한 방식을 파악한다
- OverlayFS 의 lowerdir/upperdir/copy-up 메커니즘으로 이미지 레이어 공유를 설명한다
- 컨테이너 OOM, CPU throttling 을 지표로 진단하고 리소스 요청을 조정한다

## 1. 컨테이너는 격리된 프로세스일 뿐이다

컨테이너는 VM 이 아니라 호스트 커널을 공유하는 일반 리눅스 프로세스다. 격리는 세 기능의 조합이다. **namespace** 는 프로세스가 보는 자원의 범위(무엇을 볼 수 있는가)를, **cgroup** 은 자원의 사용량(얼마나 쓸 수 있는가)을, **capabilities/seccomp/LSM** 은 권한(무엇을 할 수 있는가)을 제한한다.

```bash
docker run -d --name web nginx
PID=$(docker inspect -f '{{.State.Pid}}' web)
sudo ls -l /proc/$PID/ns/   # net, pid, mnt, uts, ipc, user, cgroup
```

## 2. Namespace 7종과 각각의 격리 대상

각 namespace 는 `clone()`/`unshare()` 플래그로 생성된다.

| Namespace | 격리 대상 | 대표 효과 |
|---|---|---|
| PID | 프로세스 ID 트리 | 컨테이너 첫 프로세스가 PID 1 |
| NET | 네트워크 스택 | 독립 인터페이스·라우팅 |
| MNT | 마운트 포인트 | 독립 파일시스템 뷰 |
| UTS | hostname/domain | 컨테이너별 호스트명 |
| IPC | System V IPC | 공유메모리 격리 |
| USER | UID/GID 매핑 | 컨테이너 root ≠ 호스트 root |
| CGROUP | cgroup 루트 뷰 | cgroup 경로 은닉 |

USER namespace 는 컨테이너 root 를 호스트 비특권 UID 로 매핑해 탈출 시 피해를 줄인다. PID 1 은 좀비 reaping 책임이 있어 이를 못 하면 `--init`(tini)로 감싼다.

```bash
docker run --rm --init alpine ps aux
```

## 3. cgroup v2 — 통합 계층과 자원 제한

cgroup v1 은 컨트롤러마다 별도 계층이라 복잡했다. v2 는 **단일 통합 계층**으로 모든 컨트롤러가 한 트리를 공유하고, `cgroup.subtree_control` 로 자식에 위임한다. "no internal process" 규칙으로 자식이 있는 노드에는 프로세스를 리프에만 둔다.

```bash
stat -fc %T /sys/fs/cgroup/   # cgroup2fs
cat /sys/fs/cgroup/system.slice/docker-$CID.scope/cpu.max   # 150000 100000
```

## 4. CPU 제한 — cpu.max 의 quota/period

`--cpus=1.5` 는 `cpu.max = "150000 100000"` 로, period 100ms 마다 quota 150ms 어치 CPU 시간을 허용한다. quota 소진 시 다음 period 까지 throttle 된다. 멀티스레드가 quota 를 몰아 쓰면 꼬리 지연이 튄다.

```bash
cat /sys/fs/cgroup/.../cpu.stat   # nr_periods, nr_throttled, throttled_usec
```

latency 민감 서비스는 하드 quota 대신 `cpu.weight`(상대 배분)로 경합만 제어하는 편이 throttling 스파이크를 줄인다. 배치·크론성은 하드 quota 가 맞다.

## 5. 메모리 제한과 OOM

`--memory=512m` 은 `memory.max` 에 반영된다. 초과 시 reclaim → cgroup OOM killer 가 작동하고 PID 1 이 죽으면 컨테이너가 종료된다. v2 는 `memory.high`(소프트, throttle)와 `memory.max`(하드, kill)를 구분한다. JVM 은 `-XX:+UseContainerSupport`(JDK10+ 기본)로 제한을 인식한다.

```bash
cat /sys/fs/cgroup/.../memory.events   # oom_kill 횟수
docker inspect -f '{{.State.OOMKilled}}' $CID
```

## 6. OverlayFS — 이미지 레이어와 copy-up

overlay2 는 여러 lowerdir(이미지 레이어)를 merged 뷰로 합치고 그 위에 쓰기 가능한 upperdir 를 얹는다. 읽기는 upper→lower 순, 수정은 원본을 upper 로 복사 후 수정하는 copy-up 이다. 삭제는 whiteout 파일로 은닉만 하므로 이미지 레이어의 파일을 지워도 이미지 크기는 안 준다. 같은 베이스를 쓰는 컨테이너 100개가 lower 를 공유하고 각자 작은 upper 만 갖는다.

```bash
docker inspect -f '{{json .GraphDriver.Data}}' $CID   # LowerDir/UpperDir/MergedDir
```

한 RUN 안에서 임시파일을 만들고 지워야 레이어에 흔적이 안 남는다.

## 7. 격리는 완전하지 않다

커널을 공유하므로 escape 취약점에 노출된다. 방어는 capabilities 드롭, seccomp, USER namespace, 필요시 gVisor·Kata 샌드박스 런타임이다.

```bash
docker run --rm --cap-drop=ALL --cap-add=NET_BIND_SERVICE \
  --security-opt=no-new-privileges --read-only --tmpfs /tmp \
  --user 1000:1000 myapp
```

`--privileged` 는 모든 방어를 해제하므로 프로덕션에서 쓰지 않는다. 요약하면 namespace 는 시야를, cgroup 은 자원을, capabilities/seccomp 는 권한을 각각 최소화해야 한다.

## 참고

- Linux kernel documentation — cgroup-v2.rst, namespaces(7)
- Docker docs — Runtime metrics, Storage drivers (overlay2)
- Michael Kerrisk, "The Linux Programming Interface"
