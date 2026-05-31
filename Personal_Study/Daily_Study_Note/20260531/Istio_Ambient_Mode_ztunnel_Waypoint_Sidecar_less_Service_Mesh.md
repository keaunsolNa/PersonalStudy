Notion 원본: https://www.notion.so/3715a06fd6d38123a86adc0d130bc4c5

# Istio Ambient Mode — ztunnel/waypoint proxy 기반 Sidecar-less Service Mesh

> 2026-05-31 신규 주제 · 확장 대상: Kubernetes Gateway API (20260527) / Argo Rollouts (20260529)

## 학습 목표

- Istio Ambient 의 두 계층(ztunnel = L4, waypoint = L7) 분리 구조와 sidecar 모델 대비 리소스 trade-off 를 식별한다
- ztunnel 이 사용하는 HBONE(HTTP-Based Overlay Network Encapsulation) over mTLS 와 노드 내 redirect 흐름을 설명한다
- waypoint proxy 가 어떤 서비스 또는 ServiceAccount 에 부착되는지(GAMMA), 트래픽이 어떻게 waypoint 를 거치는지 추적한다
- 운영에서 ztunnel 만 켠 L4-only mode 와 waypoint 추가의 점진적 도입을 비교한다

## 1. Ambient 가 풀려는 문제

sidecar 모델은 강력하지만 운영 비용이 크다 — pod lifecycle 의존, 잉여 메모리, namespace-level injection 의 binary 옵트인. Ambient 는 mesh 기능을 노드급 L4 (ztunnel) + 서비스급 L7 (waypoint) 로 분리해 옵트인을 점진화.

## 2. ztunnel

노드당 한 개 DaemonSet, Rust 작성. SPIFFE identity 부여, HBONE 캡슐화 mTLS, L4 흐름 메트릭.

```
[pod A] →(평문 TCP)→ [veth] →eBPF/iptables→ [ztunnel A]
                                              │ HBONE/mTLS
                                              ▼
                                            [ztunnel B] → [veth] → [pod B]
```

CNI integration 모드(1.22+) 가 conntrack 우회로 성능 우수. L4 정책만 가능.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: l4-only, namespace: prod }
spec:
  selector: { matchLabels: { app: payment } }
  action: ALLOW
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/checkout/sa/checkout-app"]
      to:
        - operation: { ports: ["5432"] }
```

## 3. HBONE

HTTP/2 CONNECT 로 노드간 mTLS 터널 + raw TCP 페이로드.

```
:method: CONNECT
:authority: pod-ip:port
x-istio-peer-identity: spiffe://...
```

|  | sidecar | ztunnel | waypoint |
|---|---|---|---|
| 위치 | per-pod | per-node | per-service/SA |
| 구현 | C++ Envoy | Rust | C++ Envoy |
| 메모리 | 50~100 MB / pod | 50 MB / node | 80 MB / waypoint |
| L7 | O | X | O |

## 4. Waypoint Proxy

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: payment-waypoint
  namespace: prod
  labels:
    istio.io/waypoint-for: service
spec:
  gatewayClassName: istio-waypoint
  listeners:
    - name: mesh
      port: 15008
      protocol: HBONE

---
apiVersion: v1
kind: Service
metadata:
  name: payment
  namespace: prod
  labels:
    istio.io/use-waypoint: payment-waypoint
```

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: pay-l7, namespace: prod }
spec:
  targetRefs:
    - kind: Service
      name: payment
  rules:
    - to:
        - operation: { methods: ["POST"], paths: ["/charge"] }
```

`targetRefs` 가 GAMMA(Gateway API for Mesh). service 단위 정책.

## 5. 트래픽 경로

```
pod A → ztunnel A (SPIFFE id 추출) → HBONE/mTLS → waypoint(있다면) → ztunnel B → pod B
```

## 6. 점진적 도입

```
phase 0: 전체 sidecar (기존)
phase 1: namespace ambient L4-only
        kubectl label ns prod istio.io/dataplane-mode=ambient
phase 2: 핵심 Service 에 waypoint 부착
phase 3: sidecar 제거
```

같은 namespace 의 ambient/sidecar pod 공존 가능 → 카나리.

## 7. 비교

| 항목 | sidecar | ambient L4 | ambient L7 |
|---|---|---|---|
| 추가 메모리/pod | 50~100MB | 0 | 0 |
| pod startup 지연 | sidecar init | 없음 | 없음 |
| mTLS | O | O | O |
| L7 policy | O | X | O |
| retry/timeout | O | X | O |
| traffic mirroring | O | X | O |

## 8. 제약

- TPROXY/IPVS 충돌 가능 (CNI integration 권장)
- hostNetwork=true pod 미적용
- waypoint Deployment SPOF
- DestinationRule L7 항목은 L4-only 에서 동작 안 함
- Envoy access log 1:1 매핑 사라짐

## 9. 실측 (100 node / 5천 pod fleet)

|  | sidecar | ambient L4 | ambient L4+waypoint20 |
|---|---|---|---|
| 총 메모리 | ~400 GB | ~5 GB | ~6.5 GB |
| pod startup 평균 | 8.5 s | 4.1 s | 4.1 s |
| p99 추가 latency | 2.1 ms | 1.4 ms | 2.8 ms |
| upgrade 영향 | pod 전체 | DaemonSet rolling | DaemonSet + Deployment |

waypoint hop 의 latency(~1.4ms)는 sidecar 의 localhost 대비 약간 높음. waypoint scaling 이 fleet capacity 와 직결.

## 참고

- Istio Ambient Mesh Architecture https://istio.io/latest/docs/ambient/architecture/
- HBONE Protocol Spec https://github.com/istio/istio/tree/master/architecture/ambient
- ztunnel Source Repository (Rust) https://github.com/istio/ztunnel
- Gateway API GAMMA Initiative https://gateway-api.sigs.k8s.io/mesh/
- Christian Posta, "Ambient Mesh Performance and Architecture Deep Dive" (Solo.io 2024)
