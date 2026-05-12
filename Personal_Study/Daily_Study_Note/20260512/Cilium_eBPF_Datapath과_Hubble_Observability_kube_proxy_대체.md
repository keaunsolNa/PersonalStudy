Notion 원본: https://www.notion.so/35e5a06fd6d38153849febc834d40907

# Cilium eBPF Datapath과 Hubble Observability kube-proxy 대체

> 2026-05-12 신규 주제 · 확장 대상: Docker / AWS

## 학습 목표

- Cilium 의 eBPF 기반 datapath 가 iptables/IPVS 기반 kube-proxy 를 어떻게 대체하는지 설명
- L7 NetworkPolicy(HTTP/gRPC/Kafka) 의 동작 원리와 Envoy 통합 흐름 추적
- Hubble 의 flow log 모델과 운영 시 적절한 sampling/retention 설정
- ClusterMesh 와 LoadBalancer IPAM 사용 시 트레이드오프 평가

## 1. kube-proxy 가 만들던 문제

Kubernetes 의 Service abstraction 은 ClusterIP 를 Pod IP 로 변환한다. 표준 구현인 kube-proxy 는 두 모드 중 하나로 동작한다.

- **iptables 모드**: 모든 Service 마다 PREROUTING/OUTPUT 체인에 random DNAT 규칙을 채워 넣는다. Service 수 N 에 대해 패킷당 평균 O(N/2) rule 매칭. 5,000 Service · 50,000 Endpoint 규모에서 connection 당 latency 가 수 ms 추가되고, rule 동기화 latency 가 분 단위로 늘어난다.
- **IPVS 모드**: 커널 IPVS 테이블을 사용해 O(1) hash lookup. iptables 보다 빠르지만 connection tracking, NAT, port range 한계가 있고 NetworkPolicy 가 별도다.

두 모드 모두 *L3/L4 까지* 만 다루므로 HTTP path 별 정책, gRPC method 별 인가, Kafka topic 권한 같은 L7 정책을 외부 sidecar(Istio, Linkerd) 없이는 표현할 수 없다.

## 2. Cilium eBPF 의 핵심 아이디어

Cilium 은 두 가지를 바꾼다.

1. Service 매핑을 **eBPF program 의 hash map** 에 저장한다. tc(traffic control) hook 또는 XDP hook 에 attach 된 BPF 프로그램이 패킷 헤더를 보고 즉시 backend pod IP 로 lookup → DNAT. 사용자 공간 동기화도 필요 없다.
2. NetworkPolicy 를 eBPF 의 *policy map* 에 컴파일해 같은 hook 에서 평가. L7 정책이 필요하면 패킷을 in-kernel **Cilium Envoy** 로 redirect 한다.

연결 흐름:

```
Pod A 발신 패킷
   ↓
veth pair → tc-ingress hook (BPF)
   ↓
Service IP 매칭 → backend Pod IP 로 DNAT
   ↓
NetworkPolicy 평가 (identity 기반)
   ↓
(L7 정책 필요 시) Envoy proxy 로 redirect → HTTP 파싱 → 허용/거부
   ↓
물리 NIC 또는 노드 라우팅
```

iptables 가 사라지므로 connection 당 syscall 이 줄고, Service 수와 무관한 O(1) 조회가 보장된다. Cilium 1.13+ benchmark 에서 5,000 Service 환경 P99 latency 가 kube-proxy iptables 대비 30% 이상 단축된 사례가 일반적이다.

## 3. Identity 와 Endpoint

Cilium 의 정책은 IP 주소가 아니라 **identity** 단위다. identity 는 Pod 의 label set 해시이며, 동일 label 의 Pod 는 같은 identity 를 공유한다. cluster 전역 16-bit ID 로 노드 간 전파된다.

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: orders-allow-from-frontend
spec:
  endpointSelector:
    matchLabels:
      app: orders
  ingress:
    - fromEndpoints:
        - matchLabels:
            app: frontend
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
          rules:
            http:
              - method: GET
                path: /api/orders.*
              - method: POST
                path: /api/orders$
```

`fromEndpoints` 는 label selector → identity 변환. Pod 재시작으로 IP 가 바뀌어도 identity 가 같으면 정책은 그대로 적용된다. L7 `http` 규칙이 있으면 해당 endpoint 의 ingress 가 Envoy 로 redirect 된다.

## 4. Datapath 모드 선택

Cilium 은 동일 클러스터에서 세 가지 datapath 모드를 지원한다.

| 모드 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **Tunneling(VXLAN/Geneve)** | 노드 간 트래픽을 overlay 로 캡슐화 | underlay 라우팅 무관, 가장 호환성 좋음 | MTU 50~58 바이트 소비, encap/decap CPU |
| **Native Routing** | Pod CIDR 를 underlay BGP/Static 으로 광고 | 캡슐화 없음, MTU 손실 0 | underlay 가 Pod CIDR 라우팅을 알아야 함 |
| **DSR(Direct Server Return)** | LB → backend, backend → client 직접 응답 | LoadBalancer 외부 트래픽 latency 최소 | client IP 보존 필요, 콜백 라우팅 설계 |

AWS EKS 에서는 VPC CNI 와 함께 *Cilium chaining* 모드를 쓰거나, ENI 모드(`eni: true`)로 Pod 에 ENI IP 를 직접 부여하는 두 가지 패턴이 일반적이다. ENI 모드는 IPv4 주소 소진이 빠르므로 `/16` 이상의 VPC 가 필수다.

## 5. Hubble — flow log 의 구조

Hubble 은 Cilium 의 eBPF perf ring buffer 에서 패킷 메타데이터를 끌어다 gRPC 스트림으로 노출한다. Pod-to-Pod 단위로 모든 connection 의 source/dest identity, verdict(ALLOWED/DENIED), L7 정보를 캡처한다.

flow 예시:

```
Jan 12 14:22:15.124  default/frontend-7f...  ->  default/orders-9a...
  TCP Flags: SYN
  Identity: 12345 -> 67890
  Policy verdict: ALLOWED
  L7: HTTP GET /api/orders/42 -> 200 (12.3ms)
```

CLI:

```
hubble observe --namespace default --to-pod orders --http-method GET --since 5m
hubble observe --verdict DENIED --output json | jq '.flow.l7.http'
```

운영 환경에서는 **flow buffer size, retention, sampling** 을 잘 다뤄야 한다.

| 설정 | 기본 | 권장(중규모) |
|---|---|---|
| `hubble.eventBufferCapacity` | 4,096 | 16,384 |
| `hubble.flowBufferSize` | per-node 200k | 1M(메모리 충분 시) |
| flow export sampling | 100% | DENIED 100%, ALLOWED 10% |
| retention(Hubble Relay) | 메모리만 | Tempo/Loki 로 1~7일 적재 |

100% 적재는 trace 와 비교할 만한 비용이다. denied flow 만 보관하고 allowed 는 sampling 하면 운영 비용이 1/10 수준으로 떨어진다.

## 6. L7 정책의 함정

L7 정책을 켜면 해당 endpoint 의 ingress 가 무조건 Envoy 를 통과한다. 의미:

1. **Latency 증가**: Envoy 한 hop 추가로 P99 가 0.5~2ms 늘어난다. 마이크로서비스 체이닝이 10단계라면 5~20ms.
2. **HTTP/2 trailer, gRPC streaming, WebSocket** 은 잘 처리되지만, HTTP/3(QUIC) 는 Cilium 1.15+ 부터 실험 지원.
3. **상호 TLS** 가 필요하면 별도로 Cilium Mutual Auth(SPIFFE/SPIRE 기반) 또는 외부 Service Mesh 와 결합해야 한다.

L7 정책은 *민감 경계* 에만 적용하는 게 정석이다. 예: 결제 서비스, 관리자 API. 모든 서비스에 L7 을 거는 건 Service Mesh 풀 도입과 다름없다.

## 7. ClusterMesh — 멀티 클러스터 연결

Cilium ClusterMesh 는 두 개 이상의 cluster 를 단일 identity 공간으로 묶는다. 각 cluster 의 etcd 에 identity/endpoint 를 mirror 하고, 글로벌 Service 는 `service.cilium.io/global=true` annotation 으로 다른 cluster 의 endpoint 도 backend 로 포함시킨다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: orders
  annotations:
    service.cilium.io/global: "true"
    service.cilium.io/affinity: "local"  # 로컬 우선, 없으면 원격
spec:
  ports: [...]
  selector:
    app: orders
```

`affinity: local` 은 로컬 cluster 의 healthy endpoint 가 있으면 그것만 사용. 장애 시 자동으로 원격 cluster 의 endpoint 로 fallback. 그러나 cross-cluster latency 가 ms 수십 단위라면 timeout/retry 설정을 함께 검토해야 한다.

## 8. 운영 점검 명령 모음

```bash
# Cilium 노드/Pod 상태
cilium status --verbose

# 특정 Pod 의 identity
kubectl exec -n kube-system ds/cilium -- cilium endpoint list | grep orders

# eBPF map 통계(connection tracking)
kubectl exec -n kube-system ds/cilium -- cilium bpf ct list global | wc -l

# 정책 trace - 두 endpoint 간 패킷이 허용/거부되는지 시뮬레이션
cilium policy trace --src-k8s-pod default/frontend-xxx --dst-k8s-pod default/orders-yyy --dport 8080

# Hubble: 거부된 흐름만 실시간 보기
hubble observe --verdict DENIED --follow
```

## 9. kube-proxy 완전 제거 시 주의

`kubeProxyReplacement: strict` 모드로 배포하면 kube-proxy DaemonSet 을 제거할 수 있다. 단 다음을 확인해야 한다.

1. NodePort / ExternalIPs / HostPort 가 모두 eBPF 로 처리되는지 검증
2. headless Service(`clusterIP: None`) 의 DNS 해석이 정상 작동
3. `hostNetwork: true` Pod 의 트래픽이 Pod CIDR 정책을 우회하지 않도록 PodSelector 명시
4. metric-server, kubelet readiness probe 등 control plane 의존 서비스가 정상 라우팅
5. `cilium connectivity test` 통과 — 70+ 시나리오를 모두 PASS 한 뒤에야 kube-proxy 제거

## 참고

- Cilium Docs — https://docs.cilium.io/en/stable/
- "Replacing kube-proxy with Cilium" — https://docs.cilium.io/en/stable/network/kubernetes/kubeproxy-free/
- Hubble Observability — https://docs.cilium.io/en/stable/observability/hubble/
- Daniel Borkmann 외, "eBPF — Rethinking the Linux Kernel" — https://ebpf.io/summit-2021-slides/eBPF_Summit_2021-Keynote-Daniel_Borkmann-eBPF_The_Future_of_Networking_and_Security.pdf
- "ClusterMesh Use Cases" — https://docs.cilium.io/en/stable/network/clustermesh/
