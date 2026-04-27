Notion 원본: https://www.notion.so/34f5a06fd6d381e09cdbd2dcffc08d05

# eBPF와 Cilium Hubble 기반 Kubernetes 네트워크 관측성

> 2026-04-27 신규 주제 · 확장 대상: Kubernetes / DevOps / Observability

## 학습 목표

- eBPF 가 Linux 커널에서 어떻게 동작하는지(verifier, JIT, hook point) 구조적으로 이해한다
- Cilium 이 kube-proxy 를 대체하면서 service routing 을 eBPF 로 구현하는 방식을 파악한다
- Hubble 의 flow 수집 모델과 L3/L4/L7 가시성의 범위를 구분한다
- 운영 K8s 에서 Hubble UI/CLI/Prometheus 메트릭으로 트래픽 이슈를 디버깅하는 절차를 정리한다

## 1. eBPF 개관 — 커널 안의 안전한 VM

eBPF(extended Berkeley Packet Filter)는 Linux 커널이 제공하는 in-kernel sandboxed virtual machine 이다. 사용자 공간에서 작성한 작은 프로그램을 커널 내부의 hook point 에 attach 해서 실행할 수 있다. tcpdump 의 BPF 가 패킷 필터링만 했다면, eBPF 는 시스템콜, 네트워크 스택, tracing 같은 거의 모든 커널 이벤트에 후크를 걸 수 있도록 확장됐다.

eBPF 프로그램은 보통 C 로 작성하고 Clang 으로 BPF bytecode 로 컴파일된다. bpf() 시스템콜로 커널에 로드되면 커널의 verifier 가 안전성을 검증한다. verifier 는 다음을 보장한다:

- 프로그램이 종료된다(loop 가 bound 되거나 helper 만 사용)
- 메모리 접근이 valid 한 영역에만 가능
- pointer 산술이 추적 가능

검증 통과 후 커널의 JIT 컴파일러가 해당 아키텍처의 native 코드로 번역한다. x86_64 에서는 거의 native 속도로 실행된다.

hook point 는 종류가 매우 많다. 네트워킹 관련만 봐도 다음과 같다:

- `XDP`(eXpress Data Path): NIC driver 직후, sk_buff 생성 전. 가장 빠르지만 정보가 제한적
- `tc`(traffic control): qdisc ingress/egress, sk_buff 가 만들어진 시점. iptables 보다 위치가 낮다
- `socket filter / cgroup_skb`: socket 단위 filter
- `kprobe / fentry`: 임의 커널 함수 진입/종료 시점
- `tracepoint`: 커널이 안정적으로 export 한 trace point

## 2. Cilium 이 kube-proxy 를 대체하는 방식

전통적인 K8s 의 service 구현은 kube-proxy 가 iptables 규칙을 작성해서 ClusterIP 를 random 한 endpoint pod 로 DNAT 한다. service/endpoint 가 많아지면 iptables 규칙이 수천~수만 줄까지 커지고, 매 패킷마다 linear scan 해서 성능 저하가 발생한다(O(n)). IPVS 모드는 hash table 로 O(1) 이지만 여전히 netfilter 단계를 거친다.

Cilium 은 kube-proxy 를 완전히 대체한다. Service 가 만들어지면 Cilium 이 BPF map 에 service-endpoint 매핑을 기록하고, eBPF 프로그램이 socket 또는 NIC level 에서 destination 을 직접 rewrite 한다. 이는 두 가지 이점이 있다.

첫째, 성능. iptables 의 linear scan 이 없고, conntrack 도 BPF map 으로 대체된다. P99 응답 시간이 large cluster 에서 10~30% 개선되는 사례가 일반적이다.

둘째, **socket-level load balancing**. iptables 모드는 패킷이 떠난 후에 DNAT 하지만, Cilium 의 socket LB 는 connect() 시스템콜 시점에 kernel 에서 destination 을 바꾼다. 이는 같은 노드의 pod 간 통신에서 packet 이 NIC 까지 안 갔다 와도 되므로 latency 가 줄어든다.

```yaml
# Cilium 설치 시 kube-proxy 대체 옵션
apiVersion: helm.cilium.io/v1
kind: HelmRelease
spec:
  values:
    kubeProxyReplacement: "strict"  # kube-proxy 완전 대체
    k8sServiceHost: "control-plane.internal"
    k8sServicePort: 6443
    bpf:
      masquerade: true
      hostLegacyRouting: false
    socketLB:
      enabled: true
      hostNamespaceOnly: false
```

`kubeProxyReplacement: strict` 는 cluster 에 kube-proxy 가 있으면 안 된다. 마이그레이션 시에는 `partial` 로 두고 incremental 하게 옮긴다.

## 3. Hubble 의 flow 모델

Hubble 은 Cilium 의 observability 컴포넌트다. eBPF 가 수집한 packet/connection 단위 이벤트를 모아서 노드 단위 daemon(hubble-agent)이 보관하고, hubble-relay 가 모든 노드의 flow 를 cluster-wide 로 aggregate 한다.

flow 는 다음 정보를 포함한다:

- L3/L4: src/dst IP, port, protocol, TCP flags
- L7(선택): HTTP method/path/status, gRPC service/method, DNS query, Kafka topic
- 메타데이터: src/dst pod identity, namespace, label, service name
- verdict: forwarded / dropped / error

L7 가시성을 켜려면 CiliumNetworkPolicy 에 L7 rule 이 있어야 한다. 단순히 flow 를 보기 위한 것은 L3/L4 만으로 충분하지만, HTTP path 별 분석이 필요하면 L7 정책 또는 visibility annotation 을 단다.

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: orders-api-l7-visibility
spec:
  endpointSelector:
    matchLabels:
      app: orders-api
  ingress:
  - fromEndpoints:
    - matchLabels:
        app: web-bff
    toPorts:
    - ports:
      - port: "8080"
        protocol: TCP
      rules:
        http:
        - method: "GET"
          path: "/orders.*"
        - method: "POST"
          path: "/orders"
```

이 정책이 적용되면 orders-api 에 들어오는 GET /orders\* 와 POST /orders 의 L7 정보가 Hubble flow 에 기록된다. 정책에 매칭되지 않는 요청은 drop 되고 verdict 가 DROPPED 로 남는다.

## 4. Hubble CLI 로 디버깅

운영 중인 cluster 에서 어떤 통신이 일어나고 있는지 가장 빠르게 보는 방법은 hubble CLI 다.

```bash
# 모든 flow 를 실시간으로 follow
hubble observe --follow

# 특정 namespace 의 ingress flow 만
hubble observe --to-namespace orders --type trace --verdict FORWARDED

# 특정 pod 와 통신하는 flow
hubble observe --pod orders-api-7d89f6c9-xkz2p --since 5m

# DROPPED 만 (정책으로 차단된 트래픽)
hubble observe --verdict DROPPED --since 30m

# L7 HTTP 만
hubble observe --type l7 --protocol http
```

운영에서 흔한 시나리오는 "방금 배포한 NetworkPolicy 가 정상 트래픽을 막고 있는가?" 다. 이 때:

```bash
hubble observe --verdict DROPPED --to-namespace orders --since 10m -o json | \
  jq '.flow | {time, source: .source.namespace + "/" + .source.pod_name,
              dest: .destination.namespace + "/" + .destination.pod_name,
              port: .l4.TCP.destination_port,
              drop_reason: .drop_reason_desc}'
```

이 출력으로 어느 source 가 어느 destination 의 어떤 port 를 시도했다가 어떤 이유(POLICY_DENIED, INVALID_SOURCE_IP 등)로 막혔는지 즉시 안다.

## 5. Hubble UI 의 service map

Hubble UI 는 cluster 의 service 간 통신을 그래프로 그린다. 각 노드는 pod 또는 service, edge 는 그 둘 사이의 flow 다. forwarded 는 녹색, dropped 는 빨강이다.

이 시각화의 가치는 두 가지다.

첫째, **새 마이크로서비스가 어디와 통신하는지 자동으로 안다**. 문서가 없거나 outdated 인 경우, Hubble UI 만으로 의존성 그래프를 그릴 수 있다.

둘째, **NetworkPolicy 작성 보조**. 실제 트래픽을 보고 "이 통신은 허용해야 한다" 고 판단해서 policy 를 만든다. Cilium 의 networkpolicy editor 는 Hubble flow 를 기반으로 policy 를 추천하기도 한다.

UI 접근은 일반적으로 다음과 같다.

```bash
# port-forward 로 로컬에서 접근
cilium hubble ui --port-forward 12000

# 브라우저에서 http://localhost:12000
```

## 6. Prometheus 메트릭과 SLO

Hubble 은 flow 단위 raw 데이터를 갖지만, 운영에서는 aggregated metric 이 더 유용하다. Cilium 은 hubble-metrics 를 통해 Prometheus 형태로 export 한다.

```yaml
# helm values
hubble:
  metrics:
    enabled:
    - "dns"
    - "drop"
    - "tcp"
    - "flow"
    - "icmp"
    - "http"
```

특히 http metric 이 강력하다. 다음과 같은 메트릭을 얻는다.

```
hubble_http_requests_total{
  source_workload="web-bff",
  destination_workload="orders-api",
  method="GET",
  status="200"
} 12453

hubble_http_request_duration_seconds_bucket{
  source_workload="web-bff",
  destination_workload="orders-api",
  le="0.05"
} 11200
```

이 메트릭은 sidecar(Envoy) 없이 얻는 골든 시그널이다. service mesh 를 도입하지 않아도 L7 가시성을 확보한다는 것이 Hubble 의 핵심 가치다.

PromQL 로 SLO 를 계산할 수 있다:

```promql
# orders-api 의 5xx 비율 (지난 5분)
sum(rate(hubble_http_requests_total{
  destination_workload="orders-api",
  status=~"5.."
}[5m]))
/
sum(rate(hubble_http_requests_total{
  destination_workload="orders-api"
}[5m]))

# P99 응답 시간 (지난 5분)
histogram_quantile(0.99,
  sum(rate(hubble_http_request_duration_seconds_bucket{
    destination_workload="orders-api"
  }[5m])) by (le)
)
```

## 7. eBPF 의 한계와 주의점

eBPF 가 만능은 아니다. 다음을 고려한다.

첫째, **커널 버전 의존성**. Cilium 의 모든 기능은 5.x 이상 커널에서 동작하고, 일부 고급 기능(BPF lsm, sockmap 등)은 5.10+ 또는 5.15+ 가 필요하다. EKS 의 일부 AMI 가 5.4 인 경우 일부 기능이 비활성화된다. `cilium status` 로 어떤 기능이 enabled 인지 확인한다.

둘째, **encrypted traffic 의 L7 가시성 부재**. mTLS 가 적용된 트래픽은 Hubble 이 L7 정보를 볼 수 없다. service mesh 의 sidecar 가 mTLS 를 풀어준 후 application 으로 보내는 구간은 sidecar 안에서 처리되므로 Hubble 가시성에서 누락된다. 이를 해결하려면 Cilium 의 native mTLS 또는 Envoy embedding 기능을 쓴다.

셋째, **conntrack 메모리**. Cilium 은 BPF conntrack map 을 쓴다. 매우 많은 connection(예: 1M+ concurrent)이 있는 cluster 에서는 BPF map 사이즈를 키워야 한다.

```yaml
# helm values
bpf:
  ctTcpMax: 524288        # TCP conntrack 최대 entry
  ctAnyMax: 262144
```

넷째, **node 단위 로컬리티의 함정**. eBPF 프로그램은 노드 로컬에서만 동작한다. cross-node 트래픽의 양 끝을 보려면 양쪽 노드의 hubble-agent 가 모두 정상이어야 한다. relay 가 다운되면 cluster-wide 쿼리가 안 된다.

## 8. 운영 시나리오 — 트러블슈팅 절차

운영에서 "orders-api 가 가끔 timeout 된다" 는 알람을 받았을 때 Hubble 로 디버깅하는 절차를 본다.

먼저 P99 latency 가 어디서 늘었는지 확인:

```bash
# orders-api 로 향하는 모든 호출의 latency 분포 (지난 30분)
hubble observe --to-pod orders/orders-api --since 30m --type trace -o json | \
  jq '.flow | select(.l4.TCP) | .time' | head -100
```

다음으로 drop 이 있는지:

```bash
hubble observe --to-pod orders/orders-api --verdict DROPPED --since 30m -o compact
```

drop 이 있다면 reason 별로 집계해서 어느 source 가 막혔는지 본다. policy_denied 면 NetworkPolicy 변경 직후일 가능성이 높고, ct_invalid 면 conntrack table 이 가득 찼을 가능성이 있다.

L7 visibility 가 켜져 있다면 어느 endpoint 가 느린지:

```bash
hubble observe --to-pod orders/orders-api --type l7 --protocol http --since 30m -o json | \
  jq '.flow.l7.http | {method, url, code, latency: .response.headers."x-response-time"}'
```

이 정보를 바탕으로 application 의 endpoint 별 핫스팟을 찾고, 필요시 Spring Boot Actuator 의 trace 와 매칭한다.

## 9. ServiceMesh 와의 관계

Cilium 1.12+ 는 자체 service mesh 기능(Cilium Service Mesh)을 제공한다. Envoy 를 sidecar 가 아닌 노드별 daemon 으로 운영하고, eBPF 가 트래픽을 노드의 Envoy 로 라우팅한다. 이는 sidecar 모델에 비해 다음 trade-off 가 있다.

장점: pod 마다 sidecar container 가 없으므로 메모리 절약(pod 당 50~100MB), 네트워크 hop 감소, init container 같은 sidecar 시작 순서 이슈 없음.

단점: 노드 수준 isolation 약화. 한 Envoy 프로세스가 여러 pod 의 트래픽을 다루므로 한 pod 의 메모리 부담이 다른 pod 에 영향을 줄 수 있다. 또한 Istio 의 sidecar 기반 mesh 에 비해 운영 사례가 적다.

언제 어느 것을 쓸지는 상황에 따라 다르다. 이미 Istio 를 운영 중이고 그 mesh 의 기능(VirtualService, DestinationRule 등)을 쓰고 있다면 Cilium 은 underlay CNI 로만 두는 것이 안전하다. 새 cluster 를 시작하면서 service mesh 가 필요하다면 Cilium service mesh 를 한 번 평가할 가치가 있다.

## 참고

- Cilium 공식 문서: https://docs.cilium.io/
- Cilium eBPF Datapath Architecture: https://docs.cilium.io/en/stable/network/ebpf/intro/
- Hubble UI / CLI / Metrics 문서: https://docs.cilium.io/en/stable/observability/hubble/
- "Learning eBPF" by Liz Rice (O'Reilly, 2023)
- Linux kernel BPF documentation: https://www.kernel.org/doc/html/latest/bpf/index.html
