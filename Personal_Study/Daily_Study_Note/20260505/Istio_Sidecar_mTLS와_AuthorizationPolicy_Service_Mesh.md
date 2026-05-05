Notion 원본: https://www.notion.so/3575a06fd6d38132a76add0688235516

# Istio — Sidecar mTLS 와 AuthorizationPolicy 기반 Zero-Trust Service Mesh

> 2026-05-05 신규 주제 · 확장 대상: AWS ECS Service Connect, Kubernetes Operator

## 학습 목표

- Istio 1.22+ 의 ambient mode 와 sidecar mode 가 동일한 보안 모델을 어떻게 다른 데이터 플레인으로 구현하는지 정리한다
- mTLS 의 PERMISSIVE / STRICT / DISABLE 모드가 trust boundary 와 마이그레이션 단계에서 어떻게 활용되는지 코드로 검증한다
- AuthorizationPolicy 의 ALLOW / DENY / CUSTOM 의 우선순위와 deny-by-default 설계를 yaml 로 정리한다
- Sidecar resource(LimitedScope) 와 Telemetry / WasmPlugin 이 운영 비용에 미치는 영향을 정리한다

## 1. Sidecar 모드 vs Ambient 모드 — 동일한 보안 모델, 다른 데이터 플레인

Istio 1.18 에서 *ambient* 모드가 alpha 로 도입됐고 1.22 에서 GA 직전 단계까지 왔다. 두 모드는 *control plane (istiod)* 을 공유하지만 *data plane* 이 다르다. sidecar 모드는 pod 마다 envoy 컨테이너를 주입한다. ambient 모드는 *node 단위 ztunnel* 이 L4(mTLS) 를 처리하고, 필요 시에만 *waypoint proxy* 로 L7 정책을 적용한다.

| 항목 | sidecar | ambient |
|---|---|---|
| pod 자원 오버헤드 | envoy 약 80~150 MB / pod | ztunnel 노드당 1, 메모리 200MB ± |
| L7 처리 | 항상 on | waypoint 사용 시 on |
| 업그레이드 단위 | pod 재시작 | ztunnel daemonset 재시작 |
| 컨테이너 의존 | initContainer 로 traffic redirect | CNI 계층에서 redirect |
| 호환성 | 모든 워크로드 | hostNetwork pod 일부 제약 |

sidecar 모드의 장점은 *명확한 격리* 와 *세밀한 정책 적용*. ambient 의 장점은 *resource 효율과 운영 단순화*. 신규 클러스터라면 ambient 를 우선 검토하되, *모든 워크로드에 L7 정책이 필요* 하다면 sidecar 가 여전히 안전한 선택이다.

## 2. mTLS 모드 — STRICT / PERMISSIVE / DISABLE 의 마이그레이션

`PeerAuthentication` 리소스가 mTLS 의 강제 정도를 정의한다.

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: istio-system
spec:
  mtls:
    mode: PERMISSIVE
```

세 모드는 다음과 같다. `STRICT`: 평문 트래픽 차단, mTLS 없으면 connection reset. `PERMISSIVE`: mTLS 트래픽과 평문 트래픽 모두 수용. `DISABLE`: mTLS 비활성화.

마이그레이션 시 권장 순서는 1단계 PERMISSIVE 로 변경 → metric `istio_request_total{security_policy="mutual_tls"}` 가 100% 에 근접하는지 확인 → 2단계 STRICT 로 전환. 1주~2주 PERMISSIVE 단계를 두는 게 안전하다. `DestinationRule` 의 `trafficPolicy.tls.mode: ISTIO_MUTUAL` 설정도 함께 둬야 클라이언트 측이 mTLS 를 시작한다.

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: default
  namespace: istio-system
spec:
  host: "*.local"
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
```

함정은 *mesh 외부의 클라이언트* 다. mesh 외부에서 들어오는 평문 요청은 ingress gateway 가 처리하므로 STRICT 와 별개다. ingress gateway 의 `Gateway` + `VirtualService` 는 평문/HTTPS 둘 다 가능하며, 내부망 진입 후 *sidecar 단계에서* mTLS 가 강제된다.

## 3. AuthorizationPolicy — ALLOW / DENY / CUSTOM

`AuthorizationPolicy` 는 mesh 안의 *L7 인가 정책* 을 표현한다. 3 가지 action 이 있다.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: orders-allow
  namespace: prod
spec:
  selector:
    matchLabels:
      app: orders
  action: ALLOW
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/prod/sa/checkout"]
      to:
        - operation:
            methods: ["GET", "POST"]
            paths: ["/api/orders/*"]
      when:
        - key: request.headers[x-tenant]
          values: ["tenant-a", "tenant-b"]
```

평가 순서는 *CUSTOM → DENY → ALLOW* 다. CUSTOM 은 외부 authz 서버(예: OPA, Open Policy Agent) 로 위임. DENY 가 ALLOW 보다 먼저 평가되므로, *deny-by-default* 를 만들려면 `action: ALLOW` 정책 *0개* 를 가진 namespace 가 자동으로 모두 거부 상태가 된다. 신규 namespace 는 의도와 달리 *완전 차단* 상태일 수 있어 점검이 필요하다.

자주 쓰는 패턴은 *deny-all + allow-list* 다. namespace 에 deny-all 정책을 두고 service 별로 allow 정책을 추가한다.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: deny-all
  namespace: prod
spec:
  {}      # action 없음 = ALLOW with no rules = deny-all
```

실제로는 *spec: {}* 의 동작이 직관적이지 않아, 명시적으로 `action: ALLOW` 와 `rules: []` 를 선언하기보다 *operation.notMethods: ["*"]* 같은 패턴을 쓰는 운영자도 있다. 동작은 같지만 후자는 selector 가 명시적이라 audit log 의 의도 파악이 쉽다.

| 시나리오 | 권장 정책 |
|---|---|
| 같은 namespace 내 자유 통신 + 외부 namespace 차단 | namespace deny-all + 같은 ns ALLOW |
| 특정 ServiceAccount 만 호출 허용 | principals 기반 ALLOW |
| header 기반 tenant 격리 | when.key=request.headers[x-tenant] |
| OPA 위임 | action=CUSTOM, provider 등록 |

## 4. SPIFFE 와 Workload Identity

Istio 의 mTLS 인증서는 *SPIFFE ID* 형식이다 (`spiffe://cluster.local/ns/<ns>/sa/<sa>`). 클러스터마다 trust domain 이 별도이며, multi-cluster mesh 에서는 *trust domain alias* 또는 *root CA 공유* 가 필요하다.

각 워크로드의 SPIFFE ID 는 *Kubernetes ServiceAccount* 에서 파생된다. 따라서 *ServiceAccount 를 분리하지 않은 워크로드는 인가 정책으로 구분할 수 없다*. 같은 SA 를 쓰는 두 deployment 는 mesh 관점에서 *동일 ID* 다. 보안을 위해 deployment 마다 별도 SA 를 두는 것이 권장된다.

`istioctl x authz check` 또는 `istioctl proxy-config` 로 sidecar 의 RBAC 규칙을 dump 해 디버깅한다. control plane 에서 정책이 *cache 된* 채로 sidecar 에 전달되므로, 정책 변경이 즉시 반영되지 않을 수 있다(보통 ~1초 이내).

## 5. Sidecar(LimitedScope) 리소스 — egress 통제

기본 sidecar 는 *모든 mesh service 로의 envoy config* 를 받는다. 클러스터에 1000 개 service 가 있으면 sidecar 마다 1000 개 cluster 가 생성되어 메모리가 폭증한다. `Sidecar` 리소스로 *해당 워크로드가 호출하는 service 만* 받도록 제한한다.

```yaml
apiVersion: networking.istio.io/v1
kind: Sidecar
metadata:
  name: orders-egress
  namespace: prod
spec:
  workloadSelector:
    labels:
      app: orders
  egress:
    - hosts:
        - "prod/inventory.prod.svc.cluster.local"
        - "prod/payment.prod.svc.cluster.local"
        - "istio-system/*"     # 텔레메트리 / istiod
```

이 정책은 *config 크기* 를 줄여 sidecar 의 메모리·시작 시간·xDS 동기화 시간을 모두 단축한다. 1000 service 클러스터에서 sidecar 메모리가 *250MB → 60MB* 수준으로 감소하는 것이 일반적이다.

함정은 *ad-hoc 새 의존이 추가되었을 때* Sidecar 정책을 함께 업데이트해야 한다는 점이다. 잊으면 connection 이 거부된다. CI 에서 `helm template` 결과를 비교해 새 service 호출이 Sidecar 정책에 등록되었는지 검증하는 자동화가 권장된다.

## 6. Telemetry / WasmPlugin — 운영 비용

Istio 1.13+ 부터 *Telemetry API* 로 metrics/logs/tracing 을 정책 단위로 구성한다. `Telemetry` 리소스로 metric label 을 동적으로 customize 할 수 있다.

```yaml
apiVersion: telemetry.istio.io/v1
kind: Telemetry
metadata:
  name: high-cardinality-trim
  namespace: istio-system
spec:
  metrics:
    - providers:
        - name: prometheus
      overrides:
        - match:
            metric: REQUEST_COUNT
          tagOverrides:
            request_protocol:
              operation: REMOVE
```

`request_protocol` 을 제거하면 시계열 cardinality 가 절반으로 떨어진다. 1.10 이전엔 envoy 의 기본 stat 이 그대로 노출되어 Prometheus 의 disk I/O 가 폭증하는 사고가 잦았다. 1.20+ 의 기본은 *적절한 trim* 이지만 도메인별 추가 trim 은 여전히 필요하다.

WasmPlugin 은 Wasm 모듈을 envoy filter chain 에 주입한다. JWT 검증, custom rate limiting, header injection 등에 쓰이지만 *각 요청마다 wasm runtime 호출* 이 발생해 latency P99 에 1~3ms 추가된다. 도메인 트래픽에서 그 비용이 정당화되는지 측정 후 도입한다.

## 7. 운영에서 본 4가지 함정

첫째, *sidecar 의 startup probe* 와 메인 컨테이너의 startup probe 가 어긋나면 traffic 이 envoy 시작 전에 들어와 5xx 가 발생한다. `holdApplicationUntilProxyStarts: true` 옵션을 mesh 전체에 켜둔다.

둘째, *health check* 가 mTLS 를 못 쓴다. kubelet 이 보내는 readiness/liveness probe 는 mTLS 가 없으므로 STRICT 정책 하에서 거부된다. Istio 가 자동으로 *probe rewrite* 를 하지만 일부 envoy 버전에서 회귀가 있었다(1.18 패치 노트).

셋째, *outbound 차단 정책* 이 mesh 내부에만 적용된다. 외부 API 호출은 `ServiceEntry` 로 등록하지 않으면 자동으로 PASSTHROUGH 된다. 보안상 ServiceEntry 등록을 강제하려면 `meshConfig.outboundTrafficPolicy.mode: REGISTRY_ONLY` 설정.

넷째, *Telemetry 의 access log* 가 STDOUT 으로 흘러 중앙 로그 파이프라인의 비용을 폭발시킨다. 샘플링 또는 *4xx/5xx 만 로깅* 정책으로 줄인다.

## 8. 운영에서 본 도입 결정 기준

| 상황 | 권장 |
|---|---|
| 마이크로서비스 ≤ 10개 | 도입 보류, ALB+IAM 으로 충분 |
| 30~100 서비스, multi-team, 보안 요구사항 | sidecar 모드 도입 |
| 100+ 서비스, 자원 효율 요구 | ambient 모드 검토 |
| edge / hybrid cloud | ambient + multi-mesh |

Istio 는 *학습 곡선* 이 높지만 *기능 폭* 이 매우 넓다. 도입 결정의 핵심은 "*service 간 인가가 IAM/JWT 만으로 부족한가*" 이다. mTLS + AuthorizationPolicy + Telemetry 를 동시에 필요로 하지 않는다면, 가벼운 솔루션(예: Linkerd, AWS VPC Lattice, ECS Service Connect)을 먼저 검토한다. 일단 도입했다면 *ambient 모드 + sidecar 부분 적용* 의 hybrid 가 운영 비용을 가장 잘 통제한다.

## 참고

- Istio Documentation — Security (PeerAuthentication, AuthorizationPolicy)
- "Istio in Action" Manning, 2022 — Chapter 9~11
- SPIFFE/SPIRE Specification — Workload API, Trust Domain
- Istio 1.22 Release Notes — ambient mode GA 준비