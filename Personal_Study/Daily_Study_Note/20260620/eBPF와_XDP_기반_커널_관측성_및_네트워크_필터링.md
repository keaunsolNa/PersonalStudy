Notion 원본: https://app.notion.com/p/3855a06fd6d381a68826d9494a68ba75

# eBPF와 XDP 기반 커널 관측성 및 네트워크 필터링

> 2026-06-20 신규 주제 · 확장 대상: 리눅스_마스터_2급

## 학습 목표

- eBPF 프로그램이 검증기·JIT 를 거쳐 커널에 안전하게 적재되는 과정을 설명한다
- kprobe·tracepoint·XDP 등 어태치 포인트별 용도와 제약을 구분한다
- BPF 맵으로 커널-유저 공간이 데이터를 공유하는 구조를 이해한다
- XDP 가 네트워크 스택 이전에 패킷을 처리해 얻는 성능 이점을 정량적으로 파악한다

## 1. eBPF 는 커널 안의 안전한 가상머신이다

eBPF(extended Berkeley Packet Filter)는 커널을 다시 컴파일하거나 모듈을 적재하지 않고도, 사용자가 작성한 작은 프로그램을 커널 이벤트에 붙여 실행하는 기술이다. 본래 패킷 필터링용(cBPF)이었으나, 11개의 64비트 레지스터와 자체 명령어 집합을 가진 범용 인-커널 VM 으로 확장됐다. 핵심 가치는 **안전성** 이다. 임의의 커널 코드는 한 줄만 잘못돼도 시스템 전체를 멈추지만, eBPF 프로그램은 적재 시점에 검증기를 통과해야 하므로 무한 루프나 잘못된 메모리 접근을 사전에 차단한다. 이 덕분에 운영 중인 프로덕션 커널에 관측·네트워킹·보안 로직을 동적으로 주입할 수 있다.

## 2. 적재 파이프라인: 검증기와 JIT

eBPF 프로그램의 생애는 다음과 같다.

```
C 소스 → clang -target bpf → eBPF 바이트코드(.o)
      → bpf() 시스템콜로 커널 적재
      → [검증기 Verifier] 정적 분석
      → [JIT 컴파일러] 네이티브 기계어로 변환
      → 어태치 포인트에 부착되어 이벤트마다 실행
```

검증기는 프로그램의 모든 실행 경로를 그래프로 탐색하며 다음을 보장한다. 첫째, **종료성**. 과거에는 백워드 점프(루프)를 금지했고, 5.3+ 부터 경계가 증명되는 bounded loop 만 허용한다. 둘째, **메모리 안전**. 모든 포인터 역참조 전에 경계 검사가 선행했는지 확인한다. 셋째, **명령어 수 한도**. 분석 상태 폭발을 막으려 검사하는 명령어 수에 상한(현재 100만)이 있다. 검증을 통과하면 JIT 가 바이트코드를 호스트 아키텍처 네이티브 코드로 컴파일해 인터프리터 오버헤드 없이 실행한다.

이 모델의 트레이드오프는 표현력 제한이다. 임의 길이 루프, 큰 스택(512바이트 한도), 임의 커널 함수 호출이 안 된다. 대신 커널이 노출한 **헬퍼 함수** 와 BPF 맵으로만 외부와 상호작용한다.

## 3. 어태치 포인트의 종류

eBPF 프로그램은 붙이는 위치(program type)에 따라 할 수 있는 일이 다르다.

| 종류 | 위치 | 용도 | 안정성 |
|---|---|---|---|
| kprobe / kretprobe | 임의 커널 함수 진입·반환 | 동적 추적 | 커널 버전 의존(함수명 변경 위험) |
| tracepoint | 커널이 노출한 정적 추적점 | 안정적 추적 | ABI 안정 |
| uprobe | 유저 공간 함수 | 애플리케이션 추적 | 바이너리 심볼 의존 |
| XDP | NIC 드라이버 수신 경로 | 초고속 패킷 처리 | 드라이버 지원 필요 |
| tc(traffic control) | qdisc 송수신 경로 | 패킷 가공·정책 | 양방향 가능 |

kprobe 는 거의 모든 커널 함수에 붙을 수 있어 강력하지만, 함수 시그니처가 커널 버전마다 바뀌어 이식성이 약하다. 이를 보완하는 것이 **CO-RE(Compile Once, Run Everywhere)** 로, BTF(BPF Type Format) 디버그 정보를 이용해 한 번 컴파일한 바이너리를 여러 커널에서 재배치(relocation)해 실행한다.

## 4. BPF 맵: 커널-유저 공유 메모리

eBPF 프로그램은 상태를 BPF 맵에 저장한다. 맵은 커널과 유저 공간이 함께 접근하는 키-값 자료구조다.

```c
// 함수별 호출 횟수를 세는 kprobe 예시 (libbpf 스타일)
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 1024);
    __type(key, u32);    // PID
    __type(value, u64);  // 호출 횟수
} call_count SEC(".maps");

SEC("kprobe/__x64_sys_openat")
int count_openat(struct pt_regs *ctx) {
    u32 pid = bpf_get_current_pid_tgid() >> 32;
    u64 *cnt = bpf_map_lookup_elem(&call_count, &pid);
    if (cnt) {
        __sync_fetch_and_add(cnt, 1); // 원자적 증가
    } else {
        u64 one = 1;
        bpf_map_update_elem(&call_count, &pid, &one, BPF_ANY);
    }
    return 0;
}
```

맵 종류는 다양하다. HASH/ARRAY 는 일반 저장, PERCPU_* 는 CPU별 분리로 락 경합을 없애고, RINGBUF/PERF_EVENT_ARRAY 는 커널→유저로 이벤트를 스트리밍한다. 대량 이벤트를 유저로 보낼 때는 5.8+ 의 RINGBUF 가 perf buffer 보다 메모리 효율과 순서 보장 면에서 우수하다.

## 5. XDP: 스택 이전의 패킷 처리

XDP(eXpress Data Path)는 NIC 드라이버가 패킷을 받자마자, `sk_buff` 할당 같은 무거운 커널 네트워크 스택 처리 **이전** 에 eBPF 프로그램을 실행한다. 프로그램은 패킷을 보고 다음 동작 코드를 반환한다.

```c
SEC("xdp")
int xdp_drop_port(struct xdp_md *ctx) {
    void *data     = (void *)(long)ctx->data;
    void *data_end = (void *)(long)ctx->data_end;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end) return XDP_PASS; // 경계 검사(검증기 요구)
    if (eth->h_proto != bpf_htons(ETH_P_IP)) return XDP_PASS;

    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > data_end) return XDP_PASS;
    if (ip->protocol != IPPROTO_TCP) return XDP_PASS;

    // 특정 조건의 패킷을 스택에 도달하기 전에 폐기 → DDoS 방어에 효과적
    return XDP_DROP;
}
```

반환 코드는 `XDP_DROP`(폐기), `XDP_PASS`(스택으로 전달), `XDP_TX`(들어온 NIC 로 되돌려보냄), `XDP_REDIRECT`(다른 NIC/CPU 로) 다. 스택 진입 전에 폐기하므로 SYN 플러드 같은 공격 패킷을 **최소 비용** 으로 버린다. 동작 모드는 드라이버에 내장된 native XDP, 하드웨어 NIC 에 오프로드하는 offloaded XDP, 드라이버 미지원 시 스택 초입에서 흉내 내는 generic XDP 가 있고 성능은 이 순서다.

## 6. 성능 모델과 측정값

XDP 가 빠른 이유는 커널 네트워크 스택의 비싼 단계(소켓 버퍼 할당, conntrack, netfilter 훅 순회)를 건너뛰기 때문이다. iptables 기반 필터링은 패킷마다 스택을 통과하고 규칙 체인을 선형 순회하지만, XDP_DROP 은 드라이버 수신 직후 끝난다. 공개 벤치마크에서 단일 코어 XDP 패킷 폐기는 수천만 pps 수준으로, iptables 대비 수배~한 자릿수 빠른 결과가 일반적이다(하드웨어·패킷 크기에 크게 의존하므로 절대값은 환경마다 다르다). 핵심은 절대 수치보다 **"스택을 건너뛴다"** 는 구조적 우위다. 부하 분산에서도 XDP_REDIRECT 기반 L4 로드밸런서(예: Katran 계열)가 커널 IPVS 보다 낮은 지연과 높은 처리량을 보인다.

## 7. 실무 도구 생태계

직접 C 로 BPF 를 쓰는 대신 고수준 도구를 쓰는 경우가 많다. **bcc** 는 파이썬에서 C 스니펫을 런타임 컴파일하고, **bpftrace** 는 awk 풍 DSL 로 한 줄 추적을 가능케 한다.

```bash
# bpftrace: openat 시스템콜을 프로세스별로 집계
bpftrace -e 'tracepoint:syscalls:sys_enter_openat { @[comm] = count(); }'

# 블록 IO 지연 분포를 히스토그램으로
bpftrace -e 'tracepoint:block:block_rq_complete { @us = hist(args.nr_sector); }'
```

프로덕션 관측에는 **libbpf + CO-RE** 로 빌드한 독립 바이너리가 선호된다. bcc 처럼 런타임에 LLVM 을 들고 다닐 필요가 없어 의존성과 시작 지연이 작다. 네트워킹·보안 영역에서는 **Cilium**(쿠버네티스 CNI, eBPF 로 서비스 라우팅·네트워크 정책·관측을 구현)과 **Falco**(런타임 보안 이벤트 탐지)가 대표적 채택 사례다.

## 8. 한계와 주의점

eBPF 는 만능이 아니다. 검증기 제약 때문에 복잡한 로직은 여러 프로그램으로 쪼개 tail call 로 연결해야 하고, 맵 크기는 미리 고정해야 한다. kprobe 기반 도구는 커널 버전 업그레이드 때 함수 변경으로 깨질 수 있어 CO-RE/tracepoint 로 안정성을 확보해야 한다. XDP 는 드라이버 지원이 필요하고, native 모드에서 패킷을 직접 다루므로 잘못 작성하면 정상 트래픽까지 폐기한다. 또한 모든 eBPF 적재에는 `CAP_BPF`(과거 `CAP_SYS_ADMIN`) 권한이 필요하므로, eBPF 자체가 새로운 공격면이 될 수 있어 적재 권한을 엄격히 통제해야 한다.

## 9. tail call 과 프로그램 합성

검증기의 명령어 한도와 스택 제약(512바이트) 때문에 복잡한 로직은 하나의 거대한 eBPF 프로그램으로 표현할 수 없다. 해법은 **tail call** 이다. `BPF_MAP_TYPE_PROG_ARRAY` 맵에 여러 프로그램을 등록하고, 한 프로그램이 다른 프로그램으로 제어를 넘긴다. 함수 호출과 달리 돌아오지 않는 점프라 스택을 공유하지 않아 한도를 우회한다.

```c
struct {
    __uint(type, BPF_MAP_TYPE_PROG_ARRAY);
    __uint(max_entries, 8);
    __type(key, u32);
    __type(value, u32);
} jump_table SEC(".maps");

SEC("xdp")
int parse_eth(struct xdp_md *ctx) {
    // L2 파싱 후 프로토콜별 다음 프로그램으로 점프
    bpf_tail_call(ctx, &jump_table, PROG_PARSE_IP); // 돌아오지 않음
    return XDP_PASS; // tail call 실패 시에만 도달
}
```

tail call 의 깊이도 제한(현재 33단계)되며, 점프하면 호출자 컨텍스트가 사라지므로 상태는 맵으로 넘긴다. 더 최근에는 **BPF-to-BPF 함수 호출** 과 글로벌 함수가 추가되어, 일반 함수처럼 코드를 모듈화하면서 검증기가 각 함수를 개별 검증하도록 발전했다. 이로써 거대한 단일 프로그램 대신 작은 검증 가능한 함수들의 조합으로 복잡한 패킷 처리 파이프라인을 구성한다.

## 10. 관측성 실전: USDT 와 스택 추적

eBPF 관측성의 강점은 애플리케이션 변경 없이 프로덕션에서 동적으로 계측한다는 점이다. uprobe 로 유저 함수에, USDT(User Statically-Defined Tracing) 프로브로 애플리케이션이 미리 심어둔 추적점에 붙는다. 지연 분포를 히스토그램으로 모으거나, 특정 조건에서 커널·유저 스택을 통째 캐처해 "왜 느린가" 를 규명한다.

```c
// 함수 진입 시각을 맵에 저장, 반환 시각과의 차이로 지연 측정
SEC("uprobe/libpq:PQexec")
int trace_query_start(struct pt_regs *ctx) {
    u64 id = bpf_get_current_pid_tgid();
    u64 ts = bpf_ktime_get_ns();
    bpf_map_update_elem(&start_ts, &id, &ts, BPF_ANY);
    return 0;
}

SEC("uretprobe/libpq:PQexec")
int trace_query_end(struct pt_regs *ctx) {
    u64 id = bpf_get_current_pid_tgid();
    u64 *tsp = bpf_map_lookup_elem(&start_ts, &id);
    if (tsp) {
        u64 delta = bpf_ktime_get_ns() - *tsp;
        bpf_map_delete_elem(&start_ts, &id);
        // delta 를 log2 히스토그램 버킷에 누적
    }
    return 0;
}
```

이 방식은 `strace`/`perf` 의 전통적 한계를 넘는다. strace 는 ptrace 로 프로세스를 멈춰 오버헤드가 크지만, eBPF 는 커널 안에서 집계해 유저 공간으로 요약만 전달하므로 상시 프로덕션 관측이 가능하다. 다만 uprobe 는 트랩 기반이라 고빈도 함수에 붙이면 측정 자체가 부하를 더하므로, 샘플링이나 조건부 필터로 대상을 좁힌다.

## 11. 보안과 운영상의 한계

eBPF 의 안전성은 검증기에 의존하므로, 검증기 자체의 버그가 곧 커널 권한 상승 취약점이 된다. 과거 여러 CVE 가 검증기 우회로 보고됐고, 그래서 `kernel.unprivileged_bpf_disabled` 로 비특권 eBPF 적재를 막는 것이 권장된다. 권한 모델도 진화해, 과거의 광범위한 `CAP_SYS_ADMIN` 대신 `CAP_BPF` + `CAP_PERFMON`/`CAP_NET_ADMIN` 으로 세분화됐다. 운영에서는 eBPF 적재 권한을 신뢰된 에이전트(관측·CNI)에만 부여하고, 임의 워크로드가 BPF 를 적재하지 못하게 통제한다. 또 하나의 비용은 관측의 관측성이다. eBPF 프로그램이 잘못 작성되면 조용히 패킷을 떨어뜨리거나 CPU 를 잡아먹는데, 이를 진단하려면 `bpftool prog`/`bpftool map` 으로 적재된 프로그램과 맵 상태를 들여다봐야 한다. 강력한 만큼 관리 책임도 커지는 기술이라는 점을 운영 도입 전에 분명히 해야 한다.

## 참고

- Brendan Gregg, "BPF Performance Tools"
- Linux Kernel Documentation — BPF, XDP (bpf/, networking/af_xdp.rst)
- Cilium & eBPF Documentation (ebpf.io)
- libbpf / bpftrace 공식 저장소 README
