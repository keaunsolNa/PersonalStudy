Notion 원본: https://www.notion.so/34e5a06fd6d381c6994af69c67ae5aa8

# Istio Service Mesh mTLS와 트래픽 제어

> 2026-04-26 신규 주제 · 확장 대상: AWS (네트워크 학습됨), Docker (Kubernetes HPA 학습됨)

## 학습 목표

- Service Mesh의 sidecar 패턴이 해결하는 운영 문제를 정확히 정의한다
- Istio의 PeerAuthentication / DestinationRule / VirtualService를 mTLS와 트래픽 분할 시나리오로 구현한다
- mTLS STRICT vs PERMISSIVE 정책 전환을 무중단으로 적용한다
- Outlier Detection과 Circuit Breaker로 cascading failure를 차단한다

---

## 1. Service Mesh가 풀려는 문제

마이크로서비스 환경에서 서비스 간 호출이 늘면 다음 운영 부담이 누적된다.

- mTLS / 인증 / 인가를 모든 언어 stack에서 일관되게 구현
- retry, timeout, circuit breaker 정책을 코드 라이브러리로 중복 구현
- canary / blue-green 배포 시 트래픽 비율을 애플리케이션 코드에서 조작
- 분산 trace, access log, metrics를 모든 서비스에 통합

이 모든 책임을 애플리케이션 코드 밖으로 빼낸 것이 Service Mesh다. Istio는 각 Pod에 Envoy proxy sidecar를 주입해 모든 in/out 트래픽을 가로챈다.

```
Pod A
  ├── application container (port 8080)
  └── istio-proxy (Envoy)
       ├── inbound listener  (port 15006)  → application
       └── outbound listener (port 15001)  → 다른 서비스의 sidecar
```

`iptables` 규칙으로 application의 모든 outbound 트래픽이 sidecar로 redirect 되고, inbound도 sidecar를 거친 뒤 application에 도달한다. application 코드는 평문 HTTP를 평소대로 호출하고, mTLS / retry / load balancing은 sidecar가 담당한다.

Istio 1.22 이후로는 sidecar가 없는 ambient mesh 모드도 stable이다. ztunnel(Layer 4)와 waypoint proxy(Layer 7)로 분리되어 sidecar 메모리 비용을 줄인다. 본 노트는 default sidecar 모드를 기준으로 한다.

## 2. PeerAuthentication: mTLS 정책

Pod 간 통신에 mTLS를 강제하는 정책이다.

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: production
spec:
  mtls:
    mode: STRICT
```

`STRICT`는 namespace 안의 모든 Pod 간 통신이 mTLS로만 허용된다. 평문 요청은 sidecar에서 거부된다. `PERMISSIVE`는 mTLS와 평문을 모두 허용하고, `DISABLE`은 mTLS를 끈다.

운영 환경에 STRICT를 한 번에 적용하면 sidecar 미주입 Pod로부터의 요청이 모두 끊긴다. 단계적 도입 절차는 다음과 같다.

```yaml
# 1단계: namespace 전체 PERMISSIVE
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: production
spec:
  mtls:
    mode: PERMISSIVE
```

이 상태에서 모든 서비스가 sidecar를 가지도록 namespace에 `istio-injection=enabled` label을 붙이고 deployment를 rolling update 한다. Kiali 대시보드에서 "non-mTLS traffic" 표시가 사라지면 다음 단계로.

```yaml
# 2단계: STRICT
spec:
  mtls:
    mode: STRICT
```

mTLS 인증서는 Istio CA가 자동 발급하고 24시간 주기로 회전한다. 애플리케이션은 이 과정을 인지하지 않는다.

## 3. DestinationRule: load balancing과 connection pool

`DestinationRule`은 특정 service로 향하는 트래픽의 동작을 정의한다.

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: orders
  namespace: production
spec:
  host: orders.production.svc.cluster.local
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        http2MaxRequests: 1000
        maxRequestsPerConnection: 10
        http1MaxPendingRequests: 50
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 60s
      maxEjectionPercent: 50
    loadBalancer:
      simple: LEAST_REQUEST
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
```

`outlierDetection`은 healthy하지 않은 endpoint를 일시적으로 제거하는 circuit breaker다. 30초 간격으로 평가해 5xx가 5회 연속 발생한 endpoint를 60초 동안 격리한다. `maxEjectionPercent`는 동시에 제거할 수 있는 endpoint 비율 상한이다. 50%를 넘기면 전체 capacity가 절반 이하로 떨어져 또 다른 cascading failure를 만들 수 있다.

`subsets`는 같은 service 안의 여러 deployment 버전을 분류한다. label `version: v1`이 붙은 Pod와 `version: v2`가 붙은 Pod로 그룹을 나눈 뒤, VirtualService에서 트래픽 비율을 정한다.

## 4. VirtualService: 트래픽 분할과 라우팅

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: orders
  namespace: production
spec:
  hosts:
    - orders.production.svc.cluster.local
  http:
    - match:
        - headers:
            x-canary-user:
              exact: "true"
      route:
        - destination:
            host: orders.production.svc.cluster.local
            subset: v2
    - route:
        - destination:
            host: orders.production.svc.cluster.local
            subset: v1
          weight: 90
        - destination:
            host: orders.production.svc.cluster.local
            subset: v2
          weight: 10
```

이 정의는 두 가지 라우팅을 한다. `x-canary-user: true` 헤더가 있는 요청은 항상 v2로, 그 외는 90:10으로 v1과 v2를 분배한다.

내부 테스트 사용자에게 헤더를 주입해 새 버전을 검증하고, 자동화된 부하 테스트가 통과하면 weight를 10 → 25 → 50 → 100으로 점진적으로 올린다. 이 과정에 애플리케이션 코드 수정은 없다.

```yaml
# Blue-Green 배포: v1 100% → v2 100% 즉시 전환
http:
  - route:
      - destination:
          host: orders.production.svc.cluster.local
          subset: v2
        weight: 100
```

VirtualService 변경은 Istio control plane(istiod)이 모든 sidecar에 push 한다. push 지연은 보통 sub-second로 즉시 반영된다.

retry 정책도 VirtualService에서 정의한다.

```yaml
http:
  - route: [ ... ]
    retries:
      attempts: 3
      perTryTimeout: 2s
      retryOn: 5xx,reset,connect-failure
    timeout: 10s
```

`retryOn`은 어떤 응답에서 retry를 시도할지 결정한다. `5xx`는 모든 5xx, `reset`은 connection reset, `connect-failure`는 TCP 연결 실패다. POST 요청에 retry를 무분별하게 걸면 멱등성 문제가 생기므로 method별로 정책을 분리한다.

## 5. Authorization Policy: 인가

mTLS는 인증을 보장하지만 누가 누구에게 접근 가능한지는 별도 정책이다.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: orders-allow-frontend
  namespace: production
spec:
  selector:
    matchLabels:
      app: orders
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - "cluster.local/ns/production/sa/frontend"
              - "cluster.local/ns/production/sa/admin"
      to:
        - operation:
            methods: ["GET", "POST"]
            paths: ["/api/orders/*"]
```

`principals`는 service account를 식별자로 한다. mTLS 인증서의 SAN(Subject Alternative Name)에 SPIFFE ID가 박혀 있어 sidecar가 검증한다. 이 모델은 IP 기반 방화벽보다 강력하다. Pod IP는 재배포로 바뀌지만 service account는 안정적이다.

`action: DENY`로 차단 규칙을 만들 수도 있다. ALLOW와 DENY가 동시에 존재하면 DENY가 우선이다.

## 6. mTLS overhead 측정

Istio 1.22 기준 sidecar 추가의 latency overhead는 다음과 같다.

| 시나리오 | p50 | p99 | CPU per req |
|---|---|---|---|
| 평문 직접 호출 | 1.2ms | 4.5ms | baseline |
| sidecar 우회 (HTTP) | 1.8ms | 5.2ms | +25% |
| sidecar + mTLS | 2.3ms | 6.0ms | +35% |

대부분 워크로드에 받아들일 만한 수준이지만 high-throughput 내부 RPC(예: 마이크로서비스 간 1ms 미만 호출이 100K QPS)에서는 35% overhead가 결정적이다. 이 경우 ambient mesh의 ztunnel 모드 또는 sidecar 없는 영역을 분리하는 결정이 필요하다.

메모리는 sidecar 1개당 50~100MB가 추가된다. Pod 1000개면 50~100GB가 메시 인프라 비용이다.

## 7. 운영 패턴: Circuit Breaker로 cascading failure 차단

서비스 A가 B를 호출하고 B가 C를 호출하는 chain에서, C가 느려지면 B의 worker thread가 모두 대기에 묶여 A로부터의 새 요청도 처리 못 한다. C에서 시작한 장애가 chain 전체로 번진다. 이를 막는 표준은 caller side에서 빠르게 실패하는 것이다.

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: payments
spec:
  host: payments.production.svc.cluster.local
  trafficPolicy:
    connectionPool:
      http:
        http1MaxPendingRequests: 10
        http2MaxRequests: 50
    outlierDetection:
      consecutive5xxErrors: 3
      interval: 10s
      baseEjectionTime: 30s
```

`http1MaxPendingRequests`가 10이라는 의미는, payments에 대한 outbound 요청 중 backend에 보내지 못하고 큐에서 대기 중인 요청이 10개를 넘으면 그 이후 새 요청은 즉시 503을 반환한다. caller 입장에서는 빠르게 실패해 자체 timeout을 길게 끌고 가지 않는다. 이 fast-fail이 cascading failure를 자르는 핵심이다.

`outlierDetection`은 위에서 본 endpoint level eject다. 한 Pod이 죽어가는 중일 때 그 Pod로 가는 트래픽을 자동 격리한다.

## 8. 관측 가능성: 분산 trace와 access log

Istio sidecar는 default로 b3 / w3c traceparent 헤더를 propagate 한다. application 코드가 적절히 헤더를 forward만 하면 분산 trace가 자동으로 만들어진다.

```yaml
apiVersion: telemetry.istio.io/v1
kind: Telemetry
metadata:
  name: default
  namespace: istio-system
spec:
  tracing:
    - providers:
        - name: otel
      randomSamplingPercentage: 1.0
```

production은 100% sampling은 비용이 크다. 1~5% sampling이 일반적이고, 특정 sensitive endpoint(`/api/checkout`)만 100% sampling 하도록 별도 Telemetry를 정의하는 패턴이 있다.

access log는 표준 Envoy 포맷을 그대로 쓰거나 OpenTelemetry collector로 export 한다. mesh 전체 access log를 한 곳에 모으면 troubleshooting 시 grep만으로 cross-service 원인 추적이 가능해진다.

## 9. Trade-off 요약

Service Mesh는 운영 표준화에 강력하지만 도입 비용이 있다. mesh 전체 latency / 메모리 / control plane 운영을 감당할 수 있는 규모가 되어야 한다. 서비스 5개 이하의 시스템은 mesh 없이 application 라이브러리로 충분하다.

mTLS는 default-on으로 두는 것이 안전 기본값이다. STRICT 모드 도입은 PERMISSIVE 단계를 거쳐 검증하는 것을 권장한다. 인증서 회전 자동화는 큰 이점이다.

VirtualService를 코드와 함께 GitOps로 관리하면 트래픽 정책이 코드 리뷰 대상이 된다. 부주의한 weight 변경으로 production 트래픽이 잘못된 버전으로 가는 사고를 막는다.

ambient mesh는 sidecar 비용이 부담스러운 환경에 좋지만, Layer 7 정책이 필요한 모든 서비스는 여전히 waypoint proxy를 거쳐야 한다. 모든 서비스가 Layer 7 정책을 쓴다면 sidecar와 ambient의 메모리 차이는 줄어든다.

## 참고

- Istio Documentation - Security: PeerAuthentication, AuthorizationPolicy
- Istio Documentation - Traffic Management: VirtualService, DestinationRule
- CNCF, "Service Mesh Performance: Istio Latency Benchmarks"
- "Istio in Action", Christian E. Posta and Rinor Maloku, Manning
