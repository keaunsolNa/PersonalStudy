Notion 원본: https://app.notion.com/p/38e5a06fd6d38187a169ce08dee09a5f

# Istio Envoy 사이드카와 mTLS 및 트래픽 관리 Service Mesh

> 2026-06-29 신규 주제 · 확장 대상: DevOps

## 학습 목표

- 사이드카 프록시가 iptables 리다이렉션으로 트래픽을 가로채는 경로를 설명한다
- Istiod 가 xDS 로 Envoy 설정을 동적 배포하는 컨트롤 플레인 구조를 파악한다
- mTLS 자동 발급과 PERMISSIVE/STRICT 모드의 차이를 적용한다
- VirtualService/DestinationRule 로 카나리·서킷브레이커를 구성하고 비용을 측정한다

## 1. Service Mesh 가 푸는 문제

마이크로서비스가 늘면 서비스 간 통신의 횡단 관심사 — 암호화, 재시도, 타임아웃, 관측성, 트래픽 분할 — 가 각 애플리케이션 코드에 중복 구현된다. Service Mesh 는 이 관심사를 애플리케이션 옆 *사이드카 프록시* 로 옮겨 코드 변경 없이 네트워크 정책을 적용한다. Istio 는 데이터 플레인에 Envoy 프록시를, 컨트롤 플레인에 Istiod 를 둔다.

## 2. 사이드카 주입과 iptables 트래픽 가로채기

Istio 는 파드에 Envoy 컨테이너를 자동 주입한다(네임스페이스에 `istio-injection=enabled` 라벨). 핵심은 애플리케이션이 평소처럼 통신하는데 모든 패킷이 *투명하게* Envoy 를 거치게 만드는 것이다. 이를 init 컨테이너가 설정하는 iptables 규칙이 담당한다.

```bash
# istio-init 이 파드 네트워크 네임스페이스에 심는 규칙(요약)
# 아웃바운드: 앱이 보내는 패킷을 Envoy 의 15001 포트로 리다이렉트
iptables -t nat -A OUTPUT -p tcp -j ISTIO_REDIRECT
# 인바운드: 들어오는 패킷을 Envoy 의 15006 포트로 리다이렉트
iptables -t nat -A PREROUTING -p tcp -j ISTIO_IN_REDIRECT
# Envoy 자신(uid 1337)이 보내는 패킷은 루프 방지를 위해 제외
iptables -t nat -A ISTIO_OUTPUT -m owner --uid-owner 1337 -j RETURN
```

앱은 `http://order-service:8080` 으로 평범하게 호출하지만, 커널의 nat 테이블이 그 패킷을 로컬 Envoy(15001)로 돌린다. Envoy 가 라우팅·암호화·메트릭 수집을 처리한 뒤 실제 목적지로 보낸다. 앱 코드는 이 과정을 전혀 모른다. 최근에는 iptables 대신 노드 단위 Istio CNI 나 ambient 모드(ztunnel)로 사이드카 오버헤드를 줄이는 방향도 쓰인다.

## 3. xDS — 동적 설정 배포

Envoy 는 설정을 파일이 아니라 *xDS API* 로 컨트롤 플레인에서 동적으로 받는다. Istiod 가 Kubernetes 의 Service/Endpoint, 그리고 Istio CRD(VirtualService 등)를 감시하다가 변경이 생기면 해당 Envoy 들에게 새 설정을 push 한다.

| xDS 종류 | 역할 |
|---|---|
| LDS (Listener) | 어떤 포트에서 수신할지 |
| RDS (Route) | HTTP 라우팅 규칙 |
| CDS (Cluster) | 업스트림 서비스 그룹 |
| EDS (Endpoint) | 실제 파드 IP 목록 |

이 동적 구조 덕분에 카나리 비율 변경 같은 정책을 파드 재시작 없이 초 단위로 반영할 수 있다. Istiod 는 변경분만 보내는 증분 xDS(delta)로 대규모 메시에서 push 비용을 줄인다.

## 4. mTLS 자동화 — STRICT vs PERMISSIVE

Istio 의 가장 큰 가치 중 하나는 서비스 간 TLS 를 *자동* 으로 건다는 점이다. Istiod 가 내장 CA 로 각 워크로드에 SPIFFE 형식의 인증서를 발급하고 주기적으로 자동 갱신한다. 애플리케이션은 평문으로 통신하지만 사이드카 사이 구간이 암호화된다.

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: production
spec:
  mtls:
    mode: STRICT   # 이 네임스페이스는 mTLS 만 허용, 평문 거부
```

마이그레이션 안전성을 위해 두 모드가 있다. `PERMISSIVE` 는 mTLS 와 평문을 모두 받아들여, 메시에 아직 편입되지 않은 레거시 서비스와 공존시킨다. 모든 호출자가 사이드카를 갖춘 것을 확인한 뒤 `STRICT` 로 전환하면 평문을 완전히 차단한다. 순서를 거꾸로 해 처음부터 STRICT 를 걸면 사이드카 없는 클라이언트의 호출이 전부 끊기므로, PERMISSIVE → 검증 → STRICT 순서가 표준 절차다.

## 5. 트래픽 관리 — 카나리와 서킷브레이커

VirtualService 는 라우팅 규칙을, DestinationRule 은 대상의 정책(서브셋, 커넥션 풀, 이상 감지)을 정의한다. 가중치 기반 카나리 배포 예시다.

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: reviews
spec:
  hosts: [reviews]
  http:
    - route:
        - destination: { host: reviews, subset: v1 }
          weight: 90
        - destination: { host: reviews, subset: v2 }
          weight: 10   # 10% 트래픽만 신버전으로
---
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: reviews
spec:
  host: reviews
  subsets:
    - { name: v1, labels: { version: v1 } }
    - { name: v2, labels: { version: v2 } }
  trafficPolicy:
    outlierDetection:        # 서킷브레이커
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s  # 5xx 5회면 30초간 풀에서 제외
```

`outlierDetection` 은 연속 오류를 내는 엔드포인트를 자동으로 로드밸런싱 풀에서 빼내(eject) 장애 전파를 막는다. 애플리케이션에 Resilience4j 같은 라이브러리를 넣지 않고도 인프라 레벨에서 서킷브레이킹을 얻는 셋이다.

## 6. 성능 비용 측정

사이드카는 모든 요청에 추가 홉을 더하므로 지연과 자원을 소모한다. 대략적인 실측 범위는 다음과 같다.

| 항목 | 영향 |
|---|---|
| P50 지연 추가 | 요청당 약 0.5~1.5ms (프록시 2회 경유) |
| P99 지연 추가 | 수 ms (혼잡 시 더 커짐) |
| 사이드카 메모리 | 파드당 약 40~100MB |
| 사이드카 CPU | 초당 1000 요청당 약 0.2~0.5 vCPU |

지연 증가는 인바운드·아웃바운드 양쪽에서 Envoy 를 한 번씩 더 통과하기 때문이다. 수천 개 파드 규모에서는 사이드카 자원 총합이 무시할 수 없어, ambient 모드로 L4 처리를 노드 단위 ztunnel 로 옮기고 L7 이 필요한 워크로드만 waypoint 프록시를 두는 절충이 등장했다.

## 7. 도입 트레이드오프

Service Mesh 는 통신 보안·관측성·트래픽 제어를 코드 밖으로 빼내 표준화한다는 분명한 이점이 있다. 그러나 운영 복잡성, 디버깅 난이도(요청 경로에 보이지 않는 홉이 추가됨), 지연·자원 오버헤드를 대가로 치른다. 서비스 수가 적거나(수십 개 이하) 단일 언어 스택이라면 라이브러리 기반 해법(Spring Cloud, gRPC 인터셉터)이 더 가볍다. 메시는 다언어 환경, 강한 zero-trust 보안 요구, 또는 세밀한 트래픽 분할이 빈번한 조직에서 비용 대비 효과가 역전된다. "필요해서 도입"이 아니라 "유행이라 도입"하면 운영 부담만 늘어난다는 점이 가장 흔한 실패 패턴이다.

## 8. 인가 정책과 fault injection

mTLS 가 *누가 호출하는지* 를 증명한다면, AuthorizationPolicy 는 *무엇을 할 수 있는지* 를 통제한다. SPIFFE 신원 기반으로 서비스 간 호출을 화이트리스트한다.

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: orders-allow-frontend
  namespace: production
spec:
  selector:
    matchLabels: { app: orders }
  action: ALLOW
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/production/sa/frontend"]
      to:
        - operation:
            methods: ["GET", "POST"]
            paths: ["/api/orders/*"]
```

이 정책은 `frontend` 서비스 계정만 `orders` 의 특정 경로를 호출하도록 제한한다. 애플리케이션 코드에 인가 로직을 넣지 않고 메시 레벨에서 zero-trust 를 구현하는 것이다. 또한 Istio 는 카오스 엔지니어링을 위한 fault injection 을 선언적으로 제공한다.

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata: { name: ratings }
spec:
  hosts: [ratings]
  http:
    - fault:
        delay: { percentage: { value: 10 }, fixedDelay: 5s } # 10% 요청에 5초 지연
        abort: { percentage: { value: 5 }, httpStatus: 503 }  # 5% 요청에 503
      route:
        - destination: { host: ratings }
```

실제 장애를 코드 변경 없이 주입해, 상위 서비스의 타임아웃·재시도·서킷브레이커가 의도대로 동작하는지 운영 환경에 가까운 조건에서 검증할 수 있다.

## 9. 관측성 — 분산 추적의 한계

사이드카는 모든 요청을 거치므로 메트릭(요청 수, 지연, 오류율)과 트래픽 토폴로지를 애플리케이션 변경 없이 자동 수집한다. 이것이 메시의 큰 매력이다. 그러나 *분산 추적* 에는 결정적 한계가 있다. Envoy 는 트레이스 헤더(`x-request-id`, `traceparent` 등)를 전파만 할 뿐, 한 요청이 서비스 *내부* 에서 다음 호출로 이어질 때 그 헤더를 다음 아웃바운드 호출에 복사하는 일은 애플리케이션이 해야 한다. 서비스 A 가 받은 트레이스 헤더를 B 로의 호출에 propagate 하지 않으면 추적이 A 에서 끊긴다. 즉 "메시를 깔면 분산 추적이 공짜"라는 기대는 절반만 맞다 — 토폴로지와 구간 메트릭은 자동이지만, *연결된 트레이스* 는 애플리케이션의 헤더 전파 협조가 필수다. OpenTelemetry SDK 나 Spring Cloud Sleuth 같은 라이브러리가 이 전파를 자동화하므로, 메시와 앱 레벨 계측을 함께 갖춰야 추적이 완성된다. 이 점을 모르면 "트레이스가 자꾸 끊긴다"는 문제를 메시 설정에서만 찾다가 시간을 낭비한다.

## 참고

- Istio Documentation — Architecture, Traffic Management, Security
- Envoy Proxy Documentation — xDS Protocol
- SPIFFE/SPIRE 명세 — 워크로드 신원과 인증서
- Istio Ambient Mesh 설계 문서 (ztunnel, waypoint)
