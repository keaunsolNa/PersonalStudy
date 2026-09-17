Notion 원본: https://www.notion.so/3de5a06fd6d3815087abc44cc2411df0

# eBPF 검증기와 CO-RE 및 커널 관측성 계측 오버헤드 설계

> 2026-09-17 신규 주제 · 확장 대상: Docker&CI 컨테이너 운영 → 커널 레벨 관측성과 네트워크 데이터패스

## 학습 목표

- 검증기가 프로그램을 거부하는 대표 원인 네 가지를 구분하고 우회 방법을 안다
- CO-RE 와 BTF 가 "한 번 컴파일해 어디서나 실행"을 성립시키는 메커니즘을 설명한다
- kprobe·tracepoint·fentry 의 오버헤드 차이를 근거로 계측 지점을 고른다
- 사이드카 없는 관측성이 무엇을 볼 수 있고 무엇을 못 보는지 판정한다

## 1. eBPF 가 푸는 문제

커널 동작을 관찰하거나 바꾸려면 전통적으로 두 선택지밖에 없었다. 커널 모듈을 짜거나(잘못하면 커널 패닉, 배포마다 재컴파일), 커널에 패치를 올려 몇 년을 기다리거나.

eBPF 는 세 번째 길이다. 제한된 명령어 집합의 바이트코드를 커널에 올리고, **검증기가 안전성을 정적으로 증명한 뒤에만** JIT 컴파일해 실행한다. 안전성의 정의가 명확하다: 프로그램은 반드시 종료하고, 유효하지 않은 메모리에 접근하지 않으며, 커널 자료구조를 임의로 바꾸지 않는다.

그 대가로 제약이 붙는다. 무한 루프 금지(유한 반복만 허용), 스택 512바이트, 명령어 수 상한(현재 100만, 검증 상태 수 기준), 호출 가능한 커널 함수는 헬퍼 함수 화이트리스트로 한정. 이 제약 안에서 관측성·네트워킹·보안 도구를 짜는 것이 eBPF 프로그래밍이다.

## 2. 검증기가 거부하는 네 가지 패턴

검증기 오류는 처음 보면 해독이 어렵다. 실제로 마주치는 유형은 대체로 넷이다.

**포인터 산술 후 범위 미증명.** 검증기는 모든 메모리 접근에 대해 "이 오프셋이 유효 범위 안"임을 증명하려 한다. XDP 프로그램에서 패킷 파싱이 대표적이다.

```c
SEC("xdp")
int parse(struct xdp_md *ctx) {
    void *data     = (void *)(long)ctx->data;
    void *data_end = (void *)(long)ctx->data_end;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end)      // 이 검사가 없으면 즉시 거부
        return XDP_PASS;

    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > data_end)       // 각 헤더마다 반복해야 한다
        return XDP_PASS;

    return ip->protocol == IPPROTO_TCP ? XDP_DROP : XDP_PASS;
}
```

검사를 한 번 했다고 이후 접근이 자유로워지지 않는다. 포인터를 다시 계산할 때마다 다시 증명해야 하며, 중간에 헬퍼 함수를 호출하면 검증기가 포인터 범위 정보를 잃는 경우도 있다.

**가변 길이 반복.** `bpf_loop()` 헬퍼(커널 5.17+)가 나오기 전에는 `#pragma unroll` 로 펼치는 것이 유일한 방법이었고, 반복 횟수가 커지면 명령어 수 상한에 걸렸다. 현재는 `bpf_loop(nr_loops, callback, ctx, 0)` 로 런타임 반복이 가능하며, 검증기는 콜백을 한 번만 검증한다.

**맵 조회 결과 NULL 미검사.** `bpf_map_lookup_elem` 반환값은 NULL 일 수 있고, 검사 없이 역참조하면 거부된다. 이건 단순 규칙이라 금방 익숙해진다.

**상태 폭증.** 분기가 많으면 검증기가 탐색해야 할 경로가 지수적으로 늘어 `BPF program is too large` 또는 `too many states` 로 실패한다. 해결책은 프로그램을 쯪서 tail call 이나 BPF-to-BPF 함수 호출로 연결하는 것이다. `__always_inline` 을 남발하면 이 문제가 심해진다.

검증기 로그는 `bpftool prog load` 나 libbpf 의 `libbpf_set_print` 으로 상세히 볼 수 있다. 로그 마지막 몇 줄이 실패 지점이고, 그 위에 각 레지스터의 추론된 타입·범위가 찍힌다. 이 범위 정보를 읽는 것이 디버깅의 전부라 해도 과언이 아니다.

## 3. CO-RE — 커널 구조체 오프셋 문제

커널 구조체의 필드 오프셋은 버전과 빌드 설정(`CONFIG_*`)에 따라 다르다. 예전 BCC 는 실행 시점에 대상 머신에서 LLVM 으로 컴파일해 이 문제를 피했는데, 프로덕션 노드마다 컴파일러와 커널 헤더를 깔아야 했고 시작 시간이 수백 밀리초씩 걸렸다.

CO-RE(Compile Once - Run Everywhere)는 이를 재배치(relocation)로 푼다. 컴파일 시 필드 접근을 `__builtin_preserve_access_index` 로 감싸면, 오프셋을 하드코딩하는 대신 "구조체 X 의 필드 Y" 라는 심볼 정보를 ELF 에 남긴다. 로더(libbpf)가 실행 시점에 커널의 BTF 를 읽어 실제 오프셋으로 패치한다.

```c
#include <vmlinux.h>          // 커널 BTF 에서 생성된 전체 타입 정의
#include <bpf/bpf_core_read.h>

SEC("kprobe/do_unlinkat")
int BPF_KPROBE(trace_unlink, int dfd, struct filename *name) {
    const char *fn = BPF_CORE_READ(name, name);   // CO-RE 재배치 적용
    char buf[64];
    bpf_probe_read_kernel_str(buf, sizeof(buf), fn);
    bpf_printk("unlink: %s", buf);
    return 0;
}
```

`vmlinux.h` 는 `bpftool btf dump file /sys/kernel/btf/vmlinux format c > vmlinux.h` 로 만든다. 커널 헤더 패키지가 필요 없고, 생성한 헤더는 다른 커널 버전에서도 쓸 수 있다 — 오프셋은 어차피 로드 시점에 패치되기 때문이다.

전제는 대상 커널이 `CONFIG_DEBUG_INFO_BTF=y` 로 빌드도 `/sys/kernel/btf/vmlinux` 를 노출하는 것이다. 최근 배포판 커널은 대부분 켜져 있지만, 커스텀 커널이나 오래된 LTS 에서는 없을 수 있다. 이 경우 BTFHub 의 사전 생성 BTF 를 함께 배포하는 방식으로 우회한다.

필드가 아예 없는 커널도 있다. `bpf_core_field_exists()` 로 분기하면 하나의 바이너리가 여러 커널 세대를 커버한다.

```c
if (bpf_core_field_exists(task->__state))
    state = BPF_CORE_READ(task, __state);        // 5.14+
else
    state = BPF_CORE_READ(task, state);          // 그 이전
```

## 4. 부착 지점별 오버헤드

계측 지점 선택이 오버헤드를 좌우한다. 실측 경향은 다음과 같다.

| 부착 방식 | 진입 비용 | 안정성 | 특징 |
|---|---|---|---|
| tracepoint | 낮음 | 높음(ABI 준수) | 커널이 공식 제공, 필드 고정 |
| fentry/fexit | 가장 낮음 | 중간 | BPF trampoline, 5.5+ x86_64 |
| kprobe | 중간 | 낮음(함수명 변경 가능) | int3 트랩 또는 ftrace 기반 |
| kretprobe | 높음 | 낮음 | 반환 주소 교체 + 인스턴스 풀 |
| uprobe | 매우 높음 | 중간 | 사용자 공간 트랩, 컨텍스트 전환 |

`fentry` 가 빠른 이유는 BPF trampoline 때문이다. ftrace 의 범용 경로를 거치지 않고 대상 함수 진입부에서 곧바로 BPF 프로그램으로 점프하는 코드를 생성한다. kretprobe 와 달리 `fexit` 는 반환값과 **진입 인자를 동시에** 볼 수 있어 코드도 간결해진다.

```c
SEC("fexit/vfs_read")
int BPF_PROG(read_exit, struct file *file, char *buf, size_t count,
             loff_t *pos, ssize_t ret) {
    // 인자와 반환값을 한 자리에서 — kretprobe 는 맵에 인자를 저장했다 꺼내야 한다
    if (ret < 0) {
        bpf_printk("read failed: %ld", ret);
    }
    return 0;
}
```

kretprobe 는 진입 시 인자를 해시맵에 저장하고 반환 시 꺼내는 패턴이 필수라, 맵 조회 두 번이 추가된다. 고빈도 함수(`vfs_read`, `tcp_sendmsg`)에 걸면 이 비용이 누적된다.

uprobe 는 특히 비싸다. 사용자 공간 명령어를 `int3` 로 바꾸고 트랩할 때마다 커널 진입이 일어난다. 초당 수십만 회 호출되는 함수에 걸면 애플리케이션이 눈에 띄게 느려진다. Go 런타임 함수나 SSL 라이브러리 계측 시 이 점을 반드시 감안해야 한다.

## 5. 맵 선택과 데이터 전달 비용

커널→사용자 공간 데이터 전달 방식이 전체 오버헤드의 큰 부분을 차지한다.

- **BPF_MAP_TYPE_HASH / PERCPU_HASH**: 집계용. PERCPU 는 락 경합이 없어 고빈도 카운터에 적합하지만, 읽을 때 CPU 수만큼 합산해야 한다.
- **BPF_MAP_TYPE_PERF_EVENT_ARRAY**: 이벤트 스트리밍의 구식 방법. CPU 마다 링 버퍼가 따로라 메모리를 CPU 수만큼 쓰고, 이벤트 순서가 CPU 간에 보장되지 않는다.
- **BPF_MAP_TYPE_RINGBUF**(커널 5.8+): 전역 단일 링 버퍼. 메모리 효율이 좋고 순서가 보장되며, `bpf_ringbuf_reserve`/`submit` 으로 복사 없이 직접 쓸 수 있다.

```c
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 256 * 1024);
} events SEC(".maps");

SEC("tp/sched/sched_process_exec")
int handle_exec(struct trace_event_raw_sched_process_exec *ctx) {
    struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (!e) return 0;                         // 가득 차면 버린다 — 손실 허용

    e->pid = bpf_get_current_pid_tgid() >> 32;
    bpf_get_current_comm(&e->comm, sizeof(e->comm));
    bpf_ringbuf_submit(e, 0);                 // 예약한 자리에 직접 기록 — 복사 없음
    return 0;
}
```

`reserve` 후 `submit` 또는 `discard` 를 반드시 호출해야 한다. 빠뜨리면 검증기가 거부한다 — 이것도 검증기가 잡아주는 안전성 중 하나다.

고빈도 이벤트를 전부 사용자 공간으로 보내는 설계는 대개 실패한다. 커널에서 집계하고 주기적으로 요약만 내보내는 편이 수십 배 싸다. 히스토그램이 좋은 예로, `log2` 버킷 인덱스를 커널에서 계산해 PERCPU_ARRAY 에 누적하면 사용자 공간은 초당 한 번만 읽으면 된다.

## 6. 네트워크 데이터패스 — XDP 와 TC

패킷 처리 훅은 위치에 따라 할 수 있는 일이 다르다.

**XDP** 는 드라이버 수준, `sk_buff` 할당 전이다. 그래서 가장 빠르고(DDoS 필터링에서 코어당 수천만 pps 규모가 보고된다) 가장 제한적이다. 반환값은 `XDP_PASS`, `XDP_DROP`, `XDP_TX`, `XDP_REDIRECT`, `XDP_ABORTED` 다. 네이티브 모드는 드라이버 지원이 필요하고, 미지원 시 `generic` 모드로 떨어지는데 이때는 `sk_buff` 할당 후라 이점이 거의 없다. 모드 확인은 `ip link show` 의 `xdp/xdpgeneric` 표시로 한다.

**TC(traffic control) BPF** 는 `sk_buff` 가 만들어진 뒤라 메타데이터가 풍부하고 egress 도 처리할 수 있다. Cilium 이 서비스 로드밸런싱과 네트워크 정책에 주로 쓰는 지점이다.

**socket 계열 훅**(`sockops`, `sk_msg`, `cgroup/connect4`)은 더 위쪽이다. 같은 노드 안의 두 파드가 통신할 때 `sk_msg` 로 소켓끼리 직접 연결하면 TCP/IP 스택을 통째로 건너뛴다. 사이드카 프록시 없이 L7 정책 일부를 처리하는 아키텍처의 근거가 여기다.

여기서 현실적인 한계를 짚어야 한다. eBPF 는 TLS 로 암호화된 페이로드를 그대로 볼 수 없다. HTTP 메서드·경로 기반 정책을 하려면 (1) uprobe 로 SSL 라이브러리의 평문 버퍼를 읽거나, (2) 트래픽을 사용자 공간 프록시로 우회시켜야 한다. "사이드카 없는 서비스 메시"라는 표현은 L3/L4 와 일부 L7 에 해당하며, 완전한 L7 처리는 여전히 프록시를 거친다. 도구 선택 시 이 경계를 확인하는 것이 중요하다.

## 7. 운영 진단 명령

```bash
# 로드된 프로그램과 실행 통계
sysctl -w kernel.bpf_stats_enabled=1        # 오버헤드 있으니 진단 시에만
bpftool prog show                            # run_time_ns, run_cnt 표시
bpftool prog profile id 42 duration 10 cycles instructions

# 맵 내용 확인
bpftool map show
bpftool map dump id 17

# 어디에 붙어 있는지
bpftool net show
bpftool cgroup tree

# 프로그램 → 소스 라인 매핑 (BTF 있을 때)
bpftool prog dump xlated id 42 linum
```

`run_time_ns / run_cnt` 가 프로그램 1회 실행의 평균 나노초다. 이 값이 패킷당 처리 예산(10Gbps 에서 64바이트 패킷 기준 약 67ns)을 넘으면 데이터패스 프로그램으로는 쓸 수 없다는 뜻이다. 관측용이라면 호출 빈도와 곱해 전체 CPU 점유를 계산한다 — 초당 100만 회 × 500ns = 0.5 코어다.

## 8. 도입 판단

eBPF 관측성 도구(Cilium, Pixie, Parca, bpftrace)의 공통 이점은 **애플리케이션 수정 없이** 커널 경계에서 데이터를 얻는다는 것이다. 언어·프레임워크에 무관하고 재배포가 필요 없다. 사이드카 방식 대비 메모리와 지연 오버헤드도 작다.

한계도 분명하다. 커널 버전 요구사항이 있고(실용적으로 5.8 이상, CO-RE 와 ringbuf 를 쓰려면), 관리형 쿠버네티스에서 노드 커널을 통제하지 못하면 기능 가용성이 클러스터마다 달라진다. 특권 컨테이너나 `CAP_BPF`+`CAP_PERFMON` 이 필요해 보안 정책과 충돌할 수 있다. 그리고 디버깅 난이도가 높다 — 검증기 오류 메시지는 커널 내부 지식을 요구한다.

직접 eBPF 프로그램을 짜기 전에 기성 도구로 충분한지 먼저 확인하는 편이 낫다. `bpftrace` 한 줄이면 되는 일이 많다.

```bash
# 느린 블록 I/O 추적
bpftrace -e 'tracepoint:block:block_rq_complete /args->error != 0/
             { printf("%s err=%d\n", ksym(arg0), args->error); }'

# 프로세스별 syscall 횟수
bpftrace -e 'tracepoint:raw_syscalls:sys_enter { @[comm] = count(); }'
```

이 수준으로 원인이 좁혀지면 그때 전용 프로그램을 작성한다. 처음부터 libbpf 로 들어가면 검증기와 씩름하느라 정작 문제를 못 본다.

## 참고

- eBPF 공식 문서 — https://ebpf.io/what-is-ebpf/
- Linux 커널 문서, BPF — https://docs.kernel.org/bpf/
- Andrii Nakryiko, "BPF CO-RE Reference Guide" — https://nakryiko.com/posts/bpf-core-reference-guide/
- libbpf-bootstrap 예제 저장소 — https://github.com/libbpf/libbpf-bootstrap
- Brendan Gregg, *BPF Performance Tools*, Addison-Wesley
- Cilium 문서, eBPF datapath — https://docs.cilium.io/en/stable/network/ebpf/
