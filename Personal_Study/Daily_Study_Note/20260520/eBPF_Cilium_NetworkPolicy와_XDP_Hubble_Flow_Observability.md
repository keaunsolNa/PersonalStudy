Notion 원본: https://www.notion.so/3665a06fd6d381ad83ddca312644531b

# eBPF·Cilium — Identity-aware NetworkPolicy, XDP·tc 가속, Hubble Flow Observability

> 2026-05-20 신규 주제 · 확장 대상: Kubernetes/DevOps

## 학습 목표

- eBPF 프로그램 타입(XDP, tc, sk_msg, cgroup_sock)별 hook 지점과 처리 단계를 식별한다
- Cilium의 Identity-aware 정책이 iptables 기반 kube-proxy 모델과 어떻게 다른지 분석한다
- ClusterMesh와 endpoint 캐시·IPCache·policymap이 dataplane에서 수행하는 lookup 순서를 추적한다
- Hubble Relay·Hubble UI·flow filtering을 운영 관찰 도구로 활용하고 cost를 가늠한다

## 1. eBPF Hook 분류

| Hook | 위치 | 처리 단계 | 용도 |
|---|---|---|---|
| XDP | NIC driver 또는 generic | skb 생성 전 | DDoS drop, L3/L4 LB |
| tc(ingress/egress) | qdisc 단 | skb 생성 후 | 정책 시행, NAT |
| sk_msg | socket layer | 메시지 sendmsg | sockmap short-circuit |
| cgroup_sock_addr | connect/bind | 시스템 콜 전 | egress 라우팅 |

같은 노드 안의 두 pod 간 통신은 XDP까지 가지 않는다. fast-path 덕분에 pod-to-pod latency가 iptables 모델 대비 30~50% 떨어진다.

## 2. Cilium Identity

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: api-allow-from-web
spec:
  endpointSelector:
    matchLabels:
      app: api
  ingress:
    - fromEndpoints:
        - matchLabels:
            app: web
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
          rules:
            http:
              - method: GET
                path: "/v1/.*"
```

규칙 갯수는 *유니크한 identity 조합* 수에 비례한다. 같은 라벨을 가진 1000개 pod는 identity 한 개를 공유한다.

## 3. kube-proxy 대체 — Maglev·DSR·Socket LB

Cilium은 `kube-proxy-replacement=true` 모드에서 kube-proxy를 완전히 대체한다. Service ClusterIP에 대한 destination NAT을 *cgroup_sock_addr* hook으로 syscall 단계에서 처리한다. Maglev는 backend 변경 시 *기존 흐름의 ~1/N만 재해싱*되도록 lookup table을 구성한다.

```bash
helm install cilium cilium/cilium --version 1.15.5 \
  --set kubeProxyReplacement=true \
  --set loadBalancer.mode=dsr \
  --set loadBalancer.algorithm=maglev \
  --set bpf.masquerade=true
```

DSR(Direct Server Return) 모드에서는 응답 패킷이 LB 노드를 거치지 않고 *backend pod가 클라이언트 IP로 직접 송신*한다.

## 4. ClusterMesh

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api
  annotations:
    service.cilium.io/global: "true"
    service.cilium.io/affinity: "local"
spec:
  selector:
    app: api
  ports:
    - port: 8080
```

`global: true` 어노테이션이 붙은 서비스는 같은 이름의 서비스가 있는 모든 클러스터로 로드밸런싱된다.

## 5. Hubble

```bash
hubble observe --pod default/api --since 5m --verdict DROPPED
hubble observe --to-namespace prod --protocol tcp --port 8080 -o jsonpb
```

flow 한 건의 비용은 *수십 bytes*에 불과하다. Hubble Metrics는 Prometheus exporter다.

## 6. XDP·DDoS Drop 가속

XDP는 패킷이 skb로 변환되기 전 가장 빠른 hook이다. 1Gbps NIC에서 line rate에 근접한 패킷 처리 속도를 낸다.

## 7. 정책 디버깅

```bash
hubble observe --verdict DROPPED --since 5m --output table
kubectl exec -n kube-system cilium-xxxxx -- cilium policy trace \
  --src-k8s-pod default/web --dst-k8s-pod default/api --dport 8080
```

`policy trace`가 가장 유용하다. CI에서 정책 변경 PR마다 representative 흐름 세트에 대해 이 trace를 돌리면 regression이 줄어든다.

## 8. 성능 측정값

| 항목 | iptables/kube-proxy | Cilium BPF |
|---|---|---|
| Pod-to-Pod latency (intra-node) | 25~35μs | 12~20μs |
| Pod-to-Pod latency (cross-node) | 80~120μs | 55~85μs |
| Service redirect 오버헤드 | 5~12μs | 1~3μs |
| 정책 변경 적용 시간 (10k pods) | 10~60초 | <1초 |
| 5만 흐름 conntrack 메모리 | ~200MB | ~80MB |

## 9. 운영·Tetragon·마이그레이션

CNI 변경은 *클러스터 전체에 영향*을 주므로 단계적 적용이 필수다. Tetragon은 eBPF로 *프로세스·파일·네트워크 시스템 호출*을 추적한다.

```yaml
apiVersion: cilium.io/v1alpha1
kind: TracingPolicy
metadata:
  name: monitor-credentials
spec:
  kprobes:
    - call: "fd_install"
      selectors:
        - matchArgs:
            - index: 1
              operator: "Equal"
              values:
                - "/etc/shadow"
          matchActions:
            - action: Sigkill
```

## 참고

- Cilium Documentation — Network Policy, Kube-Proxy Replacement, ClusterMesh
- Liz Rice, "Learning eBPF" (O'Reilly, 2023)
- Cilium Blog — "How eBPF will solve Service Mesh"
- KubeCon NA 2023 — "eBPF, Cilium, and the Future of Cloud Native Networking"
- Linux Kernel Documentation — bpf(2), XDP, tc-bpf(8)
