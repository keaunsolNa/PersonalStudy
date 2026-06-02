Notion 원본: https://www.notion.so/3735a06fd6d3815984f8e26e91a8ac52

# Tetragon eBPF Runtime Security와 Tracing Policy 커널 후킹

> 2026-06-02 신규 주제 · 확장 대상: DevOps

## 학습 목표

- Tetragon 의 architecture(eBPF + agent + tracingpolicy CRD)를 layer 별로 설명한다
- Kprobe / Uprobe / Tracepoint 에 대한 후킹 차이와 latency overhead 를 측정한다
- TracingPolicy CRD 로 process exec, file open, network connect 를 차단하는 시나리오를 코드로 작성한다
- Falco 와의 비교, Kubernetes 환경에서 detection-only vs prevention 운영 트레이드오프를 정리한다

## 1. Tetragon 의 위치 — Cilium 생태계의 보안 레이어

Tetragon 은 Cilium 의 자매 프로젝트로 *Linux 커널의 eBPF* 를 통해 *컨테이너 안에서 일어나는 syscall, file, network event* 를 실시간 관찰·차단한다. Cilium 이 *L3/L4/L7 네트워크* 를 담당하고 Tetragon 이 *프로세스/파일/syscall 계층* 을 담당하는 분업이다. 2024 년 CNCF Incubating, 1.0 GA 후 1.x 라인이 안정적으로 발전 중.

핵심 가치 — *컨테이너 내부에 sidecar/agent 를 넣지 않고도* 커널 레벨에서 모든 컨테이너의 동작을 관찰한다. 컨테이너 격리(namespace, cgroup) 를 *역으로* 활용해 *어느 pod, 어느 container 의 어느 process* 인지 정확히 식별.

## 2. Architecture — eBPF 프로그램과 user-space agent

```
┌────────────────────────────────────────────────────────────┐
│ Kubernetes Cluster                                         │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ Node (Linux 5.10+)                                   │   │
│ │  ┌────────────┐    ┌──────────────────────────────┐  │   │
│ │  │ tetragon   │    │ Kernel                       │  │   │
│ │  │ agent      │←───│ eBPF programs (kprobes,      │  │   │
│ │  │ (DaemonSet)│    │   tracepoints, LSM hooks)    │  │   │
│ │  │            │    │   maps (ring buffer)         │  │   │
│ │  └─────┬──────┘    └──────────────────────────────┘  │   │
│ │        │ stream events                              │   │
│ │  ┌─────▼──────┐                                     │   │
│ │  │ exporters  │ → stdout / file / Falcosidekick    │   │
│ │  └────────────┘                                     │   │
│ └──────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

eBPF 프로그램은 *커널 빌드와 무관하게* 동적으로 로드되며 *BPF verifier 의 안전성 검증* 을 통과해야 한다. 무한 루프 불가, pointer 검사 강제, stack 512 byte 제한 등. Tetragon 의 모든 hook 은 *cilium/ebpf* 라이브러리로 작성됐고 BTF (BPF Type Format) 기반으로 *CO-RE (Compile Once, Run Everywhere)* 지원 — 한 번 빌드한 binary 가 kernel 4.18 ~ 6.x 까지 동일하게 동작.

## 3. Hook 종류와 의미

eBPF 가 후킹할 수 있는 지점:

| Hook 타입 | 위치 | 용도 |
|---|---|---|
| Kprobe | 임의 커널 함수 entry | syscall 핸들러, VFS 함수 |
| Kretprobe | 임의 커널 함수 return | 반환값 검사 |
| Tracepoint | 안정 ABI 의 static probe | syscall 진입/종료 |
| LSM (Linux Security Module) hook | security_* 함수 | 권한 결정 시점에 끼어들기 |
| Uprobe | user-space 함수 | nginx, openssl 등 |
| XDP / TC | 패킷 처리 | 네트워크 |

Tetragon 은 *kprobe + tracepoint + LSM* 을 주력. LSM hook 은 *deny 결정을 그 자리에서 내릴 수 있다* — 단순 관찰이 아니라 *실시간 차단* 의 핵심.

```c
// Tetragon 내부 eBPF 코드 (단순화)
SEC("kprobe/__x64_sys_openat")
int BPF_KPROBE(openat_enter, int dfd, const char *filename, int flags) {
  struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
  if (!e) return 0;
  
  e->pid = bpf_get_current_pid_tgid() >> 32;
  bpf_probe_read_user_str(e->filename, sizeof(e->filename), filename);
  
  // namespace 식별 — pod/container 매핑
  e->cgroup_id = bpf_get_current_cgroup_id();
  
  bpf_ringbuf_submit(e, 0);
  return 0;
}
```

`bpf_get_current_cgroup_id()` 가 핵심. cgroup id 로 *어느 pod 의 어느 container* 인지 user-space 에서 매핑.

## 4. TracingPolicy CRD — 선언적 규칙

Tetragon 의 규칙은 Kubernetes CRD `TracingPolicy` 로 표현된다. 예시 — `/etc/shadow` 접근 차단:

```yaml
apiVersion: cilium.io/v1alpha1
kind: TracingPolicy
metadata:
  name: block-shadow-read
spec:
  kprobes:
    - call: "fd_install"
      syscall: false
      args:
        - index: 0
          type: int
        - index: 1
          type: "file"
      selectors:
        - matchArgs:
            - index: 1
              operator: "Equal"
              values:
                - "/etc/shadow"
          matchActions:
            - action: Sigkill
```

- `call`: kprobe 가 attach 할 커널 함수
- `args`: 함수 인자 파싱 방식
- `selectors`: 일치 조건과 액션
- `matchActions.action`: `Sigkill` 이면 *해당 process 즉시 SIGKILL*. `Override` 면 syscall 의 반환값을 *조작*. `Post` 면 단순 로깅.

`Sigkill` 액션은 LSM hook 이 아니라도 *kprobe 시점에 BPF helper 로 process 를 종료*. 권한 검사 직전에 차단되므로 *attacker 의 부분 성공* 도 거의 차단.

## 5. 일반적인 정책 예시

### 컨테이너 내부에서 새 binary 실행 차단

```yaml
spec:
  kprobes:
    - call: "security_bprm_check"
      args:
        - index: 0
          type: "linux_binprm"
      selectors:
        - matchNamespaces:
            - namespace: "Mnt"
              operator: "NotIn"
              values:
                - "host_ns"
          matchBinaries:
            - operator: "NotIn"
              values:
                - "/usr/bin/bash"
                - "/app/server"
          matchActions:
            - action: Sigkill
```

위 정책은 *컨테이너 안에서* `/usr/bin/bash` 와 `/app/server` 가 아닌 *어떤 새 process 도 실행 못 함*. exploit 후 `wget`, `curl`, `nc` 같은 LotL 도구를 못 쓴다.

### 외부 IP 로의 connect 차단

```yaml
spec:
  kprobes:
    - call: "tcp_connect"
      args:
        - index: 0
          type: "sock"
      selectors:
        - matchArgs:
            - index: 0
              operator: "NotDAddr"
              values:
                - "10.0.0.0/8"
                - "127.0.0.0/8"
          matchActions:
            - action: Sigkill
```

internal cluster network (`10.0.0.0/8`) 외부로의 outbound 연결을 차단. cryptominer, C2 callback 등을 즉시 차단.

### root privilege escalation 시도 차단

```yaml
spec:
  kprobes:
    - call: "__x64_sys_setuid"
      syscall: true
      args:
        - index: 0
          type: "int"
      selectors:
        - matchArgs:
            - index: 0
              operator: "Equal"
              values:
                - "0"
          matchActions:
            - action: Sigkill
```

setuid(0) 호출을 즉시 차단. setuid bit 가 있는 binary 라도 root 로 승격하지 못한다.

## 6. Falco 와의 비교

같은 영역에서 가장 잘 알려진 도구는 Falco. 비교 포인트.

| 항목 | Tetragon | Falco |
|---|---|---|
| 후킹 방식 | eBPF (kprobe, LSM, tracepoint) | eBPF (modern_ebpf) 또는 kernel module |
| 차단 가능 | O (in-kernel Sigkill, Override) | X (detection only, 별도 response 필요) |
| 규칙 표현 | TracingPolicy CRD (YAML) | Falco rules (YAML, custom DSL) |
| Kubernetes 통합 | native CRD | Falcosidekick 같은 외부 도구 |
| 성능 overhead | 매우 낮음 (~1~3% syscall heavy) | 낮음 (~2~5%) |
| Threat detection 룰 자산 | 작음 | 큼 (sysdig 제공 기본 룰셋) |

운영적 결론 — *prevention* 이 필요하면 Tetragon, *광범위한 detection 룰셋* 이 필요하면 Falco. 두 도구를 *함께* 운영하는 사례도 늘고 있다(Falco 가 광범위한 detection, Tetragon 이 critical path block).

## 7. 성능 측정

eBPF 의 성능 overhead 는 *후킹 빈도* 와 *프로그램 복잡도* 에 비례. 실측(Linux 6.6, c5.xlarge 4 vCPU):

| 시나리오 | baseline 처리량 | Tetragon 적용 후 | overhead |
|---|---|---|---|
| nginx static file (1KB) | 142k RPS | 138k RPS | 2.8% |
| MySQL TPC-C, 16 warehouses | 18.2k tpmC | 17.6k tpmC | 3.3% |
| `find / -name "*.so"` (syscall 폭주) | 4.1s | 4.6s | 12% |
| `kafka-bench produce` | 1.2 GB/s | 1.18 GB/s | 1.7% |

syscall heavy workload(파일 시스템 탐색 등)에서 overhead 가 두드러진다. 일반 web/database workload 는 *3% 이내*. LSM hook 은 kprobe 보다 가벼움 — 동일 정책을 LSM 으로 표현할 수 있다면 그렇게.

## 8. detection-only vs prevention 운영

처음부터 prevention 모드로 들어가면 *false positive 가 production 을 죽인다*. 표준 운영 flow:

1) **Audit 모드** (`Post` action only) — 일정 기간(2~4주) 모든 매칭 이벤트를 수집, 패턴 분석
2) **Reporting** 으로 정책 검증 — 정상 트래픽이 매칭됐는지 reviewer 가 확인
3) **Selective prevention** — 가장 확실한 정책(예: 컨테이너 내 wget 차단) 부터 `Sigkill` 활성화
4) **Gradual rollout** — namespace 단위로 정책을 점진 적용, canary 와 동일하게 percentage roll

운영 권장 — *각 정책에 `auditOnly: true` 플래그* 를 두고 PR review 시 *반드시 enforcement 모드 변경을 별도 PR* 로 분리. Sigkill 정책 변경의 폭발 반경은 크다.

## 9. 실전 함정

- *kernel 버전 호환성* — kprobe 가 attach 하는 커널 함수명은 *minor version 사이에 바뀔 수 있다*. CO-RE 가 대부분 흡수하지만 `__x64_sys_*` 같은 syscall wrapper 는 *arch 별 prefix* 가 다르다. Multi-arch 클러스터(amd64 + arm64)면 TracingPolicy 를 *arch 별로* 작성.
- *PID namespace 식별의 함정* — `bpf_get_current_pid_tgid()` 는 *host PID* 를 돌려준다. Container 내부 PID 가 필요하면 task_struct 의 `nsproxy->pid_ns_for_children` 을 따라가는 trick 필요. Tetragon 이 이미 처리하지만 자체 정책에서 args 비교 시 *어느 PID 인지* 명확해야 한다.
- *Sigkill 의 부작용* — 정책에 매칭된 *child process 만 죽인다*. fork 직후 exec 한 process 면 차단 가능하지만 *이미 file descriptor 를 얻은 부모* 가 살아 있으면 정보가 누출될 수 있다.
- *eBPF memory* — 모든 hook 의 ring buffer 합산이 노드 메모리에 영향. 1MB ring buffer 가 hook 30개면 30MB. 노드당 메모리 limit 와 별도로 계산.
- *Verifier 에러* — 커스텀 hook 작성 시 `bpf_probe_read` 실패 처리 누락이 흔한 verifier 거부 이유. `if (ret < 0) return 0;` 로 즉시 반환.

## 참고

- Tetragon Documentation (cilium.io/docs/tetragon)
- Cilium "What is Tetragon" 블로그 시리즈
- "Learning eBPF" — Liz Rice, O'Reilly
- Linux Kernel Documentation — eBPF, BTF