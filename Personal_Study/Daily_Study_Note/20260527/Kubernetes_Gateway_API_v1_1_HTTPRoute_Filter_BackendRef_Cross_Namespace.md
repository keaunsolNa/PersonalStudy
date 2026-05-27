Notion 원본: https://www.notion.so/36d5a06fd6d3814898e4d3293b045287

# Kubernetes Gateway API v1.1 HTTPRoute, Filter, BackendRef, Cross-Namespace Routing

> 2026-05-27 신규 주제 · 확장 대상: Kubernetes Ingress / Service Mesh

## 학습 목표

- Gateway API v1.1 의 GatewayClass / Gateway / HTTPRoute 3계층 분리가 Ingress 와 어떻게 다른지 역할 책임으로 설명한다
- HTTPRoute filter (RequestHeaderModifier, URLRewrite, RequestMirror, ExtensionRef) 의 적용 순서를 트레이스한다
- ReferenceGrant 로 cross-namespace BackendRef / SecretRef 를 안전하게 허용하는 정책 모델을 익힌다
- Istio / Cilium / Envoy Gateway 구현체별 conformance 차이와 운영 trade-off 를 비교한다

## 1. 왜 Gateway API 가 Ingress 를 대체하는가

Ingress 는 단순한 host/path 라우팅을 표준화했지만 운영에서 필요한 *헤더 조작, 트래픽 미러, 가중치 분할, mTLS, retry* 같은 기능은 컴트롤러별 비호환적 annotation 으로 구현되어 왔다. Gateway API 는 이 격차를 typed CRD 모델로 메우면서 **역할 분리** 를 도입한다.

| 역할 | 리소스 | 운영 주체 |
|---|---|---|
| 인프라 운영 | GatewayClass | 클러스터 admin |
| 클러스터 운영 | Gateway | 플랫폼 팀 |
| 애플리케이션 운영 | HTTPRoute / TLSRoute / GRPCRoute | 서비스 개발팀 |

## 2. GatewayClass / Gateway / HTTPRoute 연결

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: envoy-gateway
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: public-gw
  namespace: gateway-system
spec:
  gatewayClassName: envoy-gateway
  listeners:
    - name: https
      protocol: HTTPS
      port: 443
      hostname: "*.example.com"
      tls:
        mode: Terminate
        certificateRefs:
          - kind: Secret
            name: wildcard-tls
            namespace: cert-manager
      allowedRoutes:
        namespaces:
          from: Selector
          selector:
            matchLabels:
              gateway-routes: public
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: user-api
  namespace: user
  labels:
    gateway-routes: public
spec:
  parentRefs:
    - name: public-gw
      namespace: gateway-system
  hostnames:
    - api.example.com
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /v1/users
      backendRefs:
        - name: user-service
          port: 8080
          weight: 90
        - name: user-service-canary
          port: 8080
          weight: 10
```

## 3. Filter 종류

| Filter | 역할 |
|---|---|
| `RequestHeaderModifier` | 요청 헤더 add/set/remove |
| `ResponseHeaderModifier` | 응답 헤더 add/set/remove |
| `RequestRedirect` | 3xx 리다이렉트 |
| `URLRewrite` | path/hostname rewrite |
| `RequestMirror` | shadow traffic |
| `ExtensionRef` | 구현체 고유 확장 |

filter 는 *rule 레벨 또는 backendRef 레벨* 에 배치 가능. 적용 순서는 *spec 선언 순서* 그대로 보장.

## 4. RequestMirror — 무중단 점검과 카나리 분석

```yaml
rules:
  - matches:
      - path: { type: PathPrefix, value: /v1/orders }
    filters:
      - type: RequestMirror
        requestMirror:
          backendRef:
            name: orders-canary
            port: 8080
    backendRefs:
      - name: orders-stable
        port: 8080
```

v1.1 부터 mirror 의 `percent` 필드가 추가되어 *전체의 N%만 복제* 가능해졌다.

## 5. Cross-Namespace BackendRef 와 ReferenceGrant

```yaml
# HTTPRoute(namespace=storefront)
backendRefs:
  - name: payment-gateway
    namespace: payments
    port: 8443
---
# 참조받는 payments namespace
apiVersion: gateway.networking.k8s.io/v1beta1
kind: ReferenceGrant
metadata:
  name: allow-storefront
  namespace: payments
spec:
  from:
    - group: gateway.networking.k8s.io
      kind: HTTPRoute
      namespace: storefront
  to:
    - group: ""
      kind: Service
      name: payment-gateway
```

ReferenceGrant 가 없으면 status condition 에 `RefNotPermitted=True` 가 찍히고 트래픽은 흐르지 않는다. SecretRef(TLS)에도 동일 적용.

## 6. 구현체 conformance 비교

| 구현체 | TLS Passthrough | RequestMirror | ExtensionRef WAF | GRPCRoute |
|---|---|---|---|---|
| Envoy Gateway | O | O (percent O) | O (RateLimit/SecurityPolicy CRD) | O |
| Istio | O | O | O (AuthorizationPolicy 연동) | O |
| Cilium | partial | O | partial | O |
| Contour | O | partial | partial | beta |
| Kong | O | O | O (KongPlugin CRD) | O |
| NGINX Gateway Fabric | partial | beta | partial | beta |

## 7. Migration: Ingress → Gateway API 단계적 전환

1. 읽기 전용 환경에 Gateway 컨트롤러 추가 설치 (공존)
2. 내부 API 1개를 HTTPRoute 로 이동 (DNS 는 유지)
3. Synthetic/shadow traffic 으로 신/구 동등성 확인
4. DNS 가중치 split 으로 점진 이동
5. 안정되면 다음 서비스
6. 모든 라우트 이동 후 Ingress 컨트롤러 제거

## 8. 운영 관측

| 리소스 | 주요 condition |
|---|---|
| Gateway | `Accepted`, `Programmed`, `ResolvedRefs` |
| HTTPRoute (parent 별) | `Accepted`, `ResolvedRefs` |
| ReferenceGrant | (없음, presence 자체가 권한) |

`kubectl get httproute user-api -o yaml` 하면 `status.parents[*].conditions` 가 보이고, 각 parent 별로 attach 성공 여부가 기록된다.

## 9. 안티패턴 & 베스트프랙티스

| 안티패턴 | 문제 | 권장 |
|---|---|---|
| 모든 라우트를 단일 HTTPRoute 에 몰기 | 충돌, RBAC 분리 안 됨 | 서비스별 HTTPRoute 분리 |
| Cross-NS 참조를 ReferenceGrant 없이 | 무음 실패 | ReferenceGrant 항상 명시 |
| Gateway 에 너무 많은 listener | reload latency 증가 | 환경별 Gateway 분리 |
| URLRewrite + RequestRedirect 동시 | 의미 충돌 | 둘 중 하나만 |
| weighted backend 의 합이 100 가정 | spec 상 비율로만 의미 | label 로 의도 표기 |

## 10. v1.1 의 주요 변화

- HTTPRoute 의 `timeouts` field stable
- HTTPRoute `RequestMirror` percent 필드 graduated
- `BackendTLSPolicy` GA (backend mTLS)
- `GRPCRoute` GA
- `Gateway.infrastructure` 필드 표준화

## 11. Policy Attachment Model

```yaml
apiVersion: gateway.networking.k8s.io/v1alpha3
kind: BackendTLSPolicy
metadata:
  name: payment-backend-tls
  namespace: payments
spec:
  targetRefs:
    - kind: Service
      name: payment-gateway
  validation:
    caCertificateRefs:
      - name: payment-ca-bundle
        kind: ConfigMap
    hostname: payment-internal.example.com
```

BackendTLSPolicy 는 Gateway 에서 backend 로 가는 *남쪽* mTLS 를 정의한다. RetryPolicy 는 1.1 기준 GEP 단계.

## 12. Argo Rollouts 와 연계

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  strategy:
    canary:
      trafficRouting:
        plugins:
          argoproj-labs/gatewayAPI:
            httpRoute: user-api
            namespace: user
      steps:
        - setWeight: 5
        - pause: { duration: 10m }
        - setWeight: 25
        - pause: { duration: 30m }
        - setWeight: 50
        - setWeight: 100
```

Argo Rollouts plugin 이 HTTPRoute 의 backendRefs weight 를 동적으로 조작, Prometheus 메트릭 기반 자동 rollback 결합.

## 참고

- Gateway API 공식 문서 v1.1 (https://gateway-api.sigs.k8s.io/)
- SIG Network Conformance Reports (https://gateway-api.sigs.k8s.io/implementations/)
- "Production-Ready Gateway API: Migrating from Ingress", KubeCon EU 2024 발표
- Envoy Gateway 공식 문서 — Extension Policies
- Istio Gateway API support matrix
