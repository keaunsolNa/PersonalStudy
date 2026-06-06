Notion 원본: https://www.notion.so/3775a06fd6d38177a6b4ec13a30ce9d3

# Cilium eBPF kube-proxy replacement와 Maglev Consistent Hashing

> 2026-06-06 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- kube-proxy의 iptables/IPVS 데이터 패스 한계와 Cilium eBPF 대체의 동작을 비교한다
- eBPF socket-level load balancing이 서비스 트래픽을 어떻게 가로채는지 설명한다
- Maglev consistent hashing이 백엔드 변동 시 연결 안정성을 어떻게 보장하는지 분석한다
- DSR/XDP 같은 가속 경로의 트레이드오프와 운영 점검 포인트를 정리한다

## 1. kube-proxy가 하던 일과 그 한계

Kubernetes Service(ClusterIP)는 가상 IP다. 실제 트래픽은 그 뒤의 Pod endpoint들로 분산돼야 하는데 전통적으로 kube-proxy가 이 일을 했다. iptables 모드는 Service/Endpoint마다 규칙을 생성하는데 선형 체인으로 평가돼 서비스·엔드포인트 수가 늘면 규칙 수가 O(N)으로 폭증하고 룰 갱신이 느려진다. IPVS 모드는 커널 해시 테이블을 써 룩업이 O(1)에 가깝지만 여전히 conntrack에 의존한다. Cilium의 eBPF kube-proxy replacement는 데이터 패스 자체를 eBPF로 대체해 룩업을 해시 기반 O(1)로 만들고 conntrack 비용을 줄인다.

## 2. eBPF로 서비스 부하분산을 대체

```bash
helm install cilium cilium/cilium --namespace kube-system \
  --set kubeProxyReplacement=true \
  --set k8sServiceHost=<API_SERVER_IP> --set k8sServicePort=6443
cilium service list   # eBPF 서비스 맵 덤프
```

가장 큰 이점은 클러스터 내부(east-west) 트래픽을 소켓 레벨에서 처리하는 것이다. Pod 안 애플리케이션이 ClusterIP로 connect()를 호출하면 BPF_PROG_TYPE_CGROUP_SOCK_ADDR 프로그램이 connect 시스템콜 시점에 목적지를 백엔드 Pod IP로 직접 치환한다. 즉 패킷이 만들어지기 전에 목적지가 실제 endpoint로 바뀌어 per-packet DNAT/conntrack이 불필요해진다.

## 3. Maglev Consistent Hashing

단순 modulo 해시(hash % N)는 N이 바뀌면 거의 모든 매핑이 재배치돼 연결이 끊긴다. Cilium은 Maglev consistent hashing을 eBPF로 구현한다. 고정 크기 lookup table(소수 M, 예 16381)을 백엔드들이 선호 순열에 따라 채우며, 각 백엔드 b는 offset_b = h1(b) % M, skip_b = h2(b) % (M-1) + 1로 자기 순열을 만들고 라운드를 돌며 빈 슬롯을 차지한다.

```python
def populate(backends, M):
    perm = {b: (h1(b) % M, h2(b) % (M-1) + 1) for b in backends}
    table = [-1] * M
    next_idx = {b: 0 for b in backends}
    filled = 0
    while filled < M:
        for b in backends:
            offset, skip = perm[b]
            c = (offset + next_idx[b] * skip) % M
            while table[c] != -1:
                next_idx[b] += 1
                c = (offset + next_idx[b] * skip) % M
            table[c] = b; next_idx[b] += 1; filled += 1
            if filled == M: break
```

핵심 성질은 백엔드 하나가 빠져도 그 백엔드 슬롯만 다른 백엔드로 메워지고 나머지 매핑은 대부분 그대로라는 것(minimal disruption)이다.

| 알고리즘 | 백엔드 변동 시 재배치 | 부하 균형 |
|---|---|---|
| modulo % N | 거의 전부 깨짐 | 좋음 |
| random/rr | 무상태(affinity 없음) | 좋음 |
| Maglev | 빠진 백엔드 슬롯만 | 좋음 (table 크기 의존) |

```bash
helm upgrade cilium cilium/cilium -n kube-system \
  --set loadBalancer.algorithm=maglev --set maglev.tableSize=16381
```

## 4. DSR과 XDP — 가속 경로

DSR(Direct Server Return)을 켜면 백엔드가 클라이언트에게 직접 응답해 진입 노드의 부하와 추가 홉을 없앤다. 단 원본 클라이언트 IP 전달을 위해 캡슐화를 쓰므로 네트워크(MTU)가 이를 허용해야 한다. XDP는 NIC 드라이버 단계에서 eBPF를 실행해 커널 스택을 통째로 건너뛰어 노드당 처리량을 크게 올린다.

```bash
helm upgrade cilium cilium/cilium -n kube-system \
  --set loadBalancer.mode=dsr --set loadBalancer.acceleration=native
```

## 5. 운영 점검 포인트

socket LB 적용 후 노드의 conntrack 엔트리 수가 줄어드는지 확인하고, 서비스/백엔드 수가 많으면 bpf-lb-map-max 등 맵 크기를 늘린다. externalTrafficPolicy: Local은 클라이언트 IP 보존에 쓰되 해당 노드에 백엔드가 없으면 드롭되는 정상 동작을 이해해야 한다. replacement 활성 시 기존 kube-proxy DaemonSet과 룰을 반드시 제거한다.

```bash
cilium status --verbose | grep -A5 "BPF maps"
cilium monitor --type drop
```

## 6. 언제 도입하나

eBPF kube-proxy replacement는 서비스/엔드포인트 수가 많아 iptables 동기화가 느린 대규모 클러스터, east-west 트래픽이 많아 conntrack/NAT 오버헤드가 큰 환경, NodePort/LB 처리량이 병목인 게이트웨이에서 분명한 이득을 준다. 소규모 클러스터에서 IPVS로 충분하다면 굳이 전환할 이유는 작다.

## 7. eBPF가 안전한 이유 — verifier와 maps

eBPF 프로그램은 커널에 로드되기 전 verifier를 통과해야 한다. verifier는 모든 실행 경로를 추적해 무한 루프가 없는지, 메모리 접근이 항상 경계 안인지를 정적으로 증명한다. 통과 못 하면 로드가 거부돼 커널 패닉 없이 데이터 패스를 바꿀 수 있다. 상태는 BPF map에 담기며, cilium-agent가 Kubernetes API를 watch하다가 Service/Endpoint가 바뀌면 해당 map 엔트리만 원자적으로 갱신한다. iptables가 전체 체인을 재구성하는 것과 달리 map 엔트리 단위라 동기화 지연이 서비스 수에 비례해 폭증하지 않는다.

```bash
cilium bpf lb list
cilium bpf ct list global | head
```

## 8. 정리

Cilium의 kube-proxy replacement는 서비스 부하분산을 커널 네트워크 스택 깊숙이에서 eBPF로 처리한다로 요약된다. socket-level LB로 conntrack 비용을 없애고, Maglev consistent hashing으로 백엔드 변동 시 연결을 안정적으로 유지하며, DSR/XDP로 처리량을 끌어올린다. 각 기능은 커널·드라이버 요구사항과 디버깅 복잡성을 동반하므로 부하 테스트로 검증한 뒤 단계적으로 켠다.

## 참고

- Cilium Docs — "Kubernetes Without kube-proxy"
- Eisenbud et al., "Maglev: A Fast and Reliable Software Network Load Balancer" (NSDI 2016)
- Cilium Docs — "Maglev Consistent Hashing", "DSR mode", "XDP acceleration"
- Linux kernel docs — BPF cgroup sockaddr hooks, XDP
- Isovalent Blog — kube-proxy replacement 성능 벤치마크
