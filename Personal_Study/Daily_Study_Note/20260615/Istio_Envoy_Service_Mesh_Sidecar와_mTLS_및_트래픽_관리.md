Notion 원본: https://www.notion.so/3805a06fd6d38137abfac49b4555abb4

# Istio/Envoy Service Mesh Sidecar와 mTLS 및 트래픽 관리

> 2026-06-15 신규 주제 · 확장 대상: AWS / Docker&CI / DevOps

## 학습 목표

- 데이터 플레인(Envoy)과 컨트롤 플레인(istiod)의 책임 분리를 설명한다
- iptables 리다이렉트로 사이드카가 트래픽을 가로채는 경로를 추적한다
- SPIFFE/SVID 기반 mTLS 인증서 발급과 PeerAuthentication 모드를 적용한다
- VirtualService·DestinationRule로 카나리·서킷 브레이커·재시도를 구성한다

## 1. 서비스 메시가 풀는 문제

마이크로서비스가 많아지면 재시도·타임아웃·서킷 브레이킹·mTLS·관측성 같은 횡단 관심사를 각 서비스 코드에 중복 구현하는 부담이 커진다. 서비스 메시는 이를 네트워크 계층으로 끕어낸다. Istio는 데이터 플레인(각 파드의 Envoy 사이드카)과 컨트롤 플레인(istiod)으로 구성되며, 핵심 분리는 "정책 결정은 컨트롤 플레인, 집행은 데이터 플레인"이다.

## 2. 사이드카 주입과 트래픽 가로채기

네임스페이스에 `istio-injection=enabled` 라벨을 붙이면 admission webhook이 파드 생성 시 Envoy와 init 컨테이너를 자동 추가한다.

```bash
kubectl label namespace default istio-injection=enabled
kubectl get pod my-app -o jsonpath='{.spec.containers[*].name}'
# my-app istio-proxy
```

`istio-init`이 iptables 규칙을 심어 아웃바운드를 Envoy 15001, 인바운드를 15006으로 리다이렉트한다. 애플리케이션은 가로채진다는 사실을 모른 채 평소처럼 통신하지만 모든 패킷이 사이드카를 통과한다. 최근에는 사이드카 없이 노드 단위 ztunnel을 쓰는 ambient 모드도 도입됐다.

## 3. xDS: 컨트롤 플레인이 Envoy를 설정하는 법

Envoy는 동적 설정 API xDS(LDS·RDS·CDS·EDS)로 런타임 설정을 받는다. istiod는 쿠버네티스 API를 감시하다가 변경 시 영향받는 Envoy에게 gRPC 스트림으로 새 설정을 푸시한다.

```bash
istioctl proxy-config cluster my-app.default
istioctl proxy-status   # SYNCED / STALE
```

`proxy-status`의 SYNCED/STALE은 중요하다. 정책을 바꿨는데 동작이 안 바뀌면 대개 STALE로 푸시가 지연·실패한 것이다.

## 4. mTLS와 SPIFFE 신원

각 워크로드는 `spiffe://<trust-domain>/ns/<ns>/sa/<sa>` 신원을 받아 X.509 SVID에 담기고, istiod가 CA로 인증서를 발급·갱신한다. 적용 강도는 `PeerAuthentication`으로 제어한다. PERMISSIVE(평문+mTLS 수용)·STRICT(mTLS만)·DISABLE.

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

마이그레이션은 PERMISSIVE로 시작해 모든 클라이언트가 mTLS로 전환됐는지 확인 후 STRICT로 조인다. 인가는 `AuthorizationPolicy`로 신원 기반 접근 제어를 더한다.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: allow-frontend, namespace: production }
spec:
  selector: { matchLabels: { app: backend } }
  action: ALLOW
  rules:
  - from:
    - source: { principals: ["cluster.local/ns/production/sa/frontend"] }
    to:
    - operation: { methods: ["GET", "POST"] }
```

IP가 아니라 암호학적 신원에 기반하므로 IP 스푸핑에 견고하다.

## 5. 트래픽 라우팅: VirtualService와 DestinationRule

`DestinationRule`은 subset 정의와 연결 정책을, `VirtualService`는 요청을 어느 subset으로 보낼지를 정한다.

```yaml
kind: DestinationRule
spec:
  host: reviews
  subsets:
  - { name: v1, labels: { version: v1 } }
  - { name: v2, labels: { version: v2 } }
---
kind: VirtualService
spec:
  hosts: [reviews]
  http:
  - route:
    - { destination: { host: reviews, subset: v1 }, weight: 90 }
    - { destination: { host: reviews, subset: v2 }, weight: 10 }
```

가중치 분할로 v2에 10%만 보내고 메트릭이 안정적이면 비율을 올린다. 헤더 기반 라우팅으로 다크 런치·A/B 테스트도 가능하다.

## 6. 복원력: 재시도, 타임아웃, 서킷 브레이킹

```yaml
  http:
  - route: [{ destination: { host: reviews, subset: v1 } }]
    timeout: 2s
    retries: { attempts: 3, perTryTimeout: 500ms, retryOn: 5xx,reset,connect-failure }
```

서킷 브레이킹은 DestinationRule의 `outlierDetection`과 `connectionPool`로 구성한다.

```yaml
  trafficPolicy:
    connectionPool:
      tcp: { maxConnections: 100 }
      http: { http2MaxRequests: 1000, maxRequestsPerConnection: 10 }
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
```

`maxEjectionPercent`가 안전장치다. 일시 장애로 모든 엔드포인트가 동시 제거되면 서비스 전체가 죽으므로 절반까지만 제거한다.

## 7. 관측성: 메트릭·트레이싱·접근 로그

모든 트래픽이 Envoy를 통과하므로 균일한 RED 메트릭과 트레이스 컨텍스트 헤더(B3/W3C traceparent)를 자동 전파한다. 다만 애플리케이션이 인바운드 트레이스 헤더를 아웃바운드로 이어주는 코드는 직접 넣어야 한다.

```bash
kubectl exec my-app -c istio-proxy -- curl -s localhost:15000/stats/prometheus | grep istio_requests_total
```

## 8. 비용과 trade-off, 도입 판단

파드마다 Envoy가 붙어 메모리·CPU가 추가되고 모든 홉에 프록시가 끼어 지연이 소폭 늘며, xDS 동기·iptables 가로채기·시작 순서 경쟁 같은 복잡도가 늘어난다. 따라서 서비스 수가 충분히 많아 횡단 관심사 중복이 실제 부담일 때 정당화된다. 서비스가 몇 개뿐이라면 라이브러리(Resilience4j) 수준으로 충분하다. 도입 시 PERMISSIVE로 점진 마이그레이션하고 `proxy-status`로 동기를 상시 모니터링한다.

## 9. 운영 함정: 시작 순서와 트래픽 캐처 예외

첫째, 앵 컨테이너가 Envoy보다 먼저 떠 외부 호출하면 가로채기 미준비로 연결이 실패한다. 쿠버네티스 1.28+의 native sidecar(`restartPolicy: Always`)가 이를 해결한다. 둘째, 메시 밖 외부 서비스도 기본 가로채지므로 `ServiceEntry`로 등록해야 한다.

```yaml
kind: ServiceEntry
spec:
  hosts: ["api.payment.example.com"]
  ports: [{ number: 443, name: https, protocol: TLS }]
  resolution: DNS
  location: MESH_EXTERNAL
```

두 함정 모두 "투명한 가로채기"의 이면이다. 도입 초기에는 `istioctl proxy-config`로 실제 Envoy 설정을 덤프해 눈으로 확인하는 습관이 디버깅 시간을 크게 줄인다.

## 참고

- Istio Documentation, "Architecture": https://istio.io/latest/docs/ops/deployment/architecture/
- Istio Documentation, "Security / PeerAuthentication": https://istio.io/latest/docs/concepts/security/
- Envoy Proxy, "xDS protocol": https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol
- SPIFFE Specification: https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE-ID.md
