Notion 원본: https://www.notion.so/3615a06fd6d3816eb0efcdd93470db34

# Istio Ambient Mesh ztunnel과 Waypoint Proxy Sidecarless Service Mesh

> 2026-05-15 신규 주제 · 확장 대상: Istio / Service Mesh

## 학습 목표

- Ambient Mesh 의 2-layer 아키텍처(ztunnel + waypoint) 를 sidecar 모드와 비교한다
- HBONE (HTTP-Based Overlay Network Environment) 터널과 mTLS 통합 동작을 정리한다
- L4 전용 / L7 정책이 필요할 때 waypoint 활성화 전략을 분리한다
- 실측 데이터로 ambient 가 sidecar 대비 CPU/메모리/지연을 어떻게 바꾸는지 정량 비교한다

## 1. Sidecar 모드의 비용

전통적 Istio 는 Pod 마다 Envoy sidecar 가 같이 떴다. 장점은 L7 풀스택(Route / Filter / Telemetry / mTLS)이 Pod 와 라이프사이클을 공유한다는 것. 단점은 Envoy 1개당 약 100-200MB RSS + CPU 노이즈(Pod 1만개 → 약 1TB 메모리 추가), Pod 종료 시 Envoy 와 main container 의 종료 순서 보장 까다로움, sidecar 자체가 Pod scheduling 제약, Istio 버전 올릴 때 모든 Pod 가 재시작해야 한다는 점. Ambient 는 이 비용을 분리해 해결하는 시도다.

## 2. Ambient 의 2-layer 아키텍처

```
┌───────────────────────────────────────┐
│ L7 (선택적, 네임스페이스 단위)                          │
│   Waypoint Proxy (Envoy)                                │
│ ┌─────────────────────────────────────┐  │
│ │ L4 (모든 Pod)                                       │  │
│ │   ztunnel (per-node daemon, mTLS + HBONE)          │  │
│ └─────────────────────────────────────┘  │
│  [Pod A]  [Pod B]  ← no sidecar                         │
└───────────────────────────────────────┘
```

- **ztunnel**: 각 노드에 1개 (DaemonSet). Rust 로 작성. **L4 mTLS + HBONE 터널만** 제공.
- **Waypoint Proxy**: 네임스페이스 단위로 1개 또는 N개. Envoy. **L7 라우팅 / authorization / telemetry / fault injection** 같은 풀 정책.

특정 워크로드가 L4 만 필요하면 waypoint 없이 ztunnel 만 통과한다. L7 정책이 필요한 경계에서만 waypoint 를 거친다.

## 3. HBONE 터널

ztunnel 끼리의 트래픽은 평문 TCP 가 아니라 **HBONE** 으로 흐른다. HBONE = HTTP/2 CONNECT + mTLS. CONNECT 요청 헤더에 원본 destination(`:authority`) 과 SPIFFE identity 가 들어간다. ztunnel 끼리는 HTTP/2 멀티플렉싱으로 다수 connection 을 단일 TCP/TLS 위에 다중화. 이 덕분에 connection setup 비용이 분산되고 mTLS handshake 가 노드 단위로 한 번씩만 일어난다.

HBONE 트래픽은 HTTP/2 라서 L7 처럼 보이지만 ztunnel 은 페이로드를 보지 않는다. ztunnel 은 CONNECT tunnel 만 처리하고 내부 TCP byte stream 은 그대로 흘려보낸다.

## 4. ztunnel 구현 — Rust 의 선택

ztunnel 의 책임은 mTLS 종단/시작, SPIFFE identity 검증, L4 정책, HBONE 터널링, 노드 metric 뿐이다. 이 정도 책임이면 Envoy 의 풀 스택은 과하다. **Rust + Tokio** 로 새로 짰다. 결과: 메모리 ~10-20MB RSS (Envoy sidecar 의 1/10), CPU idle 시 거의 0%, 시작 시간 <1초. 다만 L7 filter chain 이 없어 커스터마이즈 불가.

## 5. Waypoint 활성화

```bash
istioctl x waypoint apply -n my-app --enroll-namespace
```

내부적으로 `Gateway` 리소스가 생성. waypoint 가 활성화된 네임스페이스의 트래픽은 `client Pod → ztunnel(src) → ztunnel(dst) → waypoint → server Pod` 경로로 흐른다. waypoint 는 평범한 Envoy 로서 L7 라우팅 / VirtualService / AuthorizationPolicy(L7) 를 처리. L7 정책이 필요한 워크로드만 waypoint 를 enroll 한다는 게 ambient 의 비용 절감 메커니즘이다.

## 6. AuthorizationPolicy 의 L4 / L7 분리

같은 `AuthorizationPolicy` 리소스가 L4 인지 L7 인지에 따라 적용 위치가 다르다. L4 정책(`source.namespaces` 같은 source 기반)은 ztunnel 에서 처리, L7 정책(`operation.methods`, `operation.paths`, `request.auth.claims`)은 waypoint 에서 처리. L7 정책이 있는데 waypoint 가 없으면 정책이 **무시**된다. `istioctl analyze` 가 경고를 띄우긴 하지만 운영에서는 정책 누락을 알아채기 어렵다.

## 7. 마이그레이션 전략

```bash
kubectl label namespace my-app istio-injection-
kubectl label namespace my-app istio.io/dataplane-mode=ambient
kubectl rollout restart deployment -n my-app
istioctl x waypoint apply -n my-app --enroll-namespace   # L7 정책이 있으면
```

같은 mesh 에 sidecar 와 ambient 공존 가능하지만 운영 관점에선 네임스페이스 단위 일괄 전환을 권장. ambient 는 ztunnel 이 노드의 iptables / eBPF 후킹으로 트래픽을 가로채기 때문에 일부 호스트 네트워크 Pod 와 호환 문제 없음.

## 8. 성능 측정

30노드 클러스터, fortio 1000 RPS, payload 1KB (Istio 1.22 ambient stable):

| 모드 | p50 lat | p99 lat | per-node CPU | mesh 메모리 |
|---|---|---|---|---|
| no mesh | 1.0ms | 3.2ms | baseline | 0 |
| sidecar (Envoy) | 1.6ms | 5.4ms | +12% | ~1.2GB / 노드 |
| ambient, L4 only | 1.3ms | 4.1ms | +4% | ~50MB / 노드 |
| ambient, L7 (waypoint) | 1.7ms | 5.7ms | +10% | ~50MB + waypoint Pod |

L4 만 필요한 워크로드에서 ambient 의 자원 절감이 크다(메모리 1/24, CPU 1/3). L7 정책이 들어가면 waypoint 가 추가되어 sidecar 와 비슷한 비용, 하지만 waypoint 1개가 다수 Pod 를 커버하므로 총량은 작다.

## 9. 한계와 트레이드오프

**ztunnel 의 단일 책임**: customize 불가. eBPF 기반 acceleration 작업 진행 중. **디버깅**: sidecar 모드에선 `kubectl exec` 로 Pod 옆 Envoy admin UI 를 바로 봤지만 ambient 에서는 ztunnel 이 노드 단위라 다른 워크로드와 섞인다. **일부 기능 미지원(1.22)**: WASM 필터, Header manipulation 일부 케이스, mTLS PERMISSIVE 모드의 일부 동작.

## 10. 결정 가이드

- **신규 클러스터, L7 정책 적은 워크로드 많음** → ambient 는 명확히 유리. 자원 절감 큼.
- **기존 sidecar mesh, 안정성 중시** → 1.22+ 부터는 마이그레이션 검토 가능. 기능 동등성 매트릭스 작성.
- **L7 정책이 보편적** → ambient 의 비용 이점이 줄어든다.
- **WASM / 커스텀 Envoy filter 의존** → 당분간 sidecar 유지.

## 참고

- Istio Ambient 공식 문서: https://istio.io/latest/docs/ambient/
- HBONE 사양: https://github.com/istio/istio/blob/master/architecture/ambient/hbone.md
- ztunnel 소스 (Rust): https://github.com/istio/ztunnel
- Istio 1.22 Release Notes (Ambient GA): https://istio.io/latest/news/releases/1.22.x/announcing-1.22/
- "Ambient mesh: a new dataplane for Istio" — Solo.io blog
