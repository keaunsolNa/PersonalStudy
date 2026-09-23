Notion 원본: https://www.notion.so/3e45a06fd6d3818c8956ccdac0cdc621

# Envoy xDS 증분 구성 전달과 아웃라이어 감지 및 재시도 예산 설계

> 2026-09-23 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- xDS 프로토콜의 SotW 와 Delta 변형을 구분하고 ADS 단일 스트림의 순서 보장을 적용한다
- 아웃라이어 감지 파라미터로 느린 인스턴스를 자동 격리하는 임계를 설계한다
- 재시도 예산과 서킷브레이커로 재시도 폭주를 차단한다
- Envoy 통계에서 구성 전파 실패와 이상 이젝션을 식별한다

## 1. xDS 는 무엇을 전달하는가

Envoy 의 구성은 네 개의 계층으로 나뉜다. LDS(Listener)가 수신 포트와 필터 체인을, RDS(Route)가 HTTP 라우팅 규칙을, CDS(Cluster)가 업스트림 클러스터 정의를, EDS(Endpoint)가 실제 인스턴스 주소 목록을 담는다. 이 넷을 통칭 xDS 라 부르고, gRPC 양방향 스트림으로 제어 평면(Istiod, go-control-plane 등)에서 푸시된다.

정적 YAML 대신 xDS 를 쓰는 이유는 단순히 동적이기 때문이 아니라, **구성 변경이 커넥션을 끊지 않기 때문이다**. 엔드포인트 목록이 바뀌어도 기존 리스너와 활성 커넥션은 유지되고, 새 요청만 새 목록으로 라우팅된다. 파드가 롤링 업데이트될 때 사용자 요청이 끊기지 않는 근본 메커니즘이 여기에 있다.

```yaml
# bootstrap: ADS(Aggregated Discovery Service) 단일 스트림 구성
node:
  id: sidecar~10.4.2.17~orders-7d9f-x2k~default.svc.cluster.local
  cluster: orders

dynamic_resources:
  ads_config:
    api_type: GRPC
    transport_api_version: V3
    grpc_services:
      - envoy_grpc: { cluster_name: xds_cluster }
  lds_config: { ads: {}, resource_api_version: V3 }
  cds_config: { ads: {}, resource_api_version: V3 }

static_resources:
  clusters:
    - name: xds_cluster
      type: STRICT_DNS
      typed_extension_protocol_options:
        envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
          "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
          explicit_http_config:
            http2_protocol_options:
              connection_keepalive:
                interval: 30s
                timeout: 5s
      load_assignment:
        cluster_name: xds_cluster
        endpoints:
          - lb_endpoints:
              - endpoint:
                  address:
                    socket_address: { address: istiod.istio-system.svc, port_value: 15012 }
```

## 2. SotW 와 Delta, 그리고 ADS 의 순서 문제

원래 xDS 는 SotW(State of the World) 방식이다. 변경이 있을 때마다 해당 타입의 **전체 리소스 목록**을 다시 보낸다. 엔드포인트가 5,000개인 클러스터에서 파드 하나가 바뀌어도 5,000개를 다시 직렬화해 전송한다. 대규모 메시에서 제어 평면 CPU 와 네트워크가 터지는 원인이 이것이다.

Delta xDS(증분)는 추가·수정된 리소스와 삭제된 리소스 이름만 보낸다. 리소스 버전은 전역 하나가 아니라 리소스별로 관리된다.

| 항목 | SotW | Delta |
|---|---|---|
| 응답 크기 | 타입 전체 | 변경분만 |
| 버전 관리 | 응답 단위 version_info | 리소스별 version |
| 구독 변경 | 전체 목록 재전송 | subscribe/unsubscribe 명시 |
| 재연결 비용 | 전체 재전송 | initial_resource_versions 로 차분 |
| 제어 평면 구현 난도 | 낮음 | 높음(상태 추적 필요) |

Delta 의 재연결 처리가 특히 중요하다. 스트림이 끊겼다 붙으면 Envoy 는 `initial_resource_versions` 에 자신이 가진 리소스별 버전을 담아 보내고, 제어 평면은 그 차이만 보낸다. 수천 개 사이드카가 제어 평면 재시작 시 동시에 재연결하는 상황에서 전체 재전송을 피하는 것은 가용성 문제 그 자체다.

ADS 는 별개 축의 개념이다. LDS/RDS/CDS/EDS 를 **하나의 gRPC 스트림**으로 다중화한다. 목적은 대역폭 절약이 아니라 순서 보장이다. 클러스터가 아직 없는데 그 클러스터를 참조하는 라우트가 먼저 도착하면 Envoy 는 해당 라우트를 거부하고, 그 사이 요청은 503 NR(No Route) 로 떨어진다. ADS 는 단일 스트림이라 "CDS → EDS → LDS → RDS" 순서를 제어 평면이 강제할 수 있다. 프로덕션에서 ADS 를 쓰지 않을 이유는 사실상 없다.

## 3. 구성 전파를 실제로 확인하는 법

전파 실패는 조용히 일어난다. 반드시 통계로 확인한다.

```bash
# 사이드카의 구성 동기화 상태
istioctl proxy-status
# NAME                          CDS      LDS      EDS      RDS      ISTIOD
# orders-7d9f-x2k.default       SYNCED   SYNCED   SYNCED   STALE    istiod-5c9

# Envoy 관리 포트에서 직접 통계 조회
curl -s localhost:15000/stats | grep -E "^cluster_manager|^listener_manager|update_(rejected|failure)"
# cluster_manager.cds.update_rejected: 3
# listener_manager.lds.update_rejected: 0
```

`update_rejected` 가 0이 아니면 제어 평면이 보낸 구성을 Envoy 가 거부한 것이다. 원인은 로그에 남는다.

```bash
kubectl logs orders-7d9f-x2k -c istio-proxy | grep -i "rejected\|NACK"
# gRPC config for type.googleapis.com/envoy.config.cluster.v3.Cluster rejected:
#   Error adding/updating cluster(s) outbound|443||api.ext.com: malformed IP address
```

NACK 이 발생하면 Envoy 는 **직전의 정상 구성을 계속 사용한다**. 즉 장애가 즉시 드러나지 않고, 나중에 파드가 재시작되는 순간 구성이 비어 있어 대규모 실패로 번진다. `update_rejected` 를 Prometheus 알람 대상으로 반드시 올려야 하는 이유다.

```yaml
# PrometheusRule 예시
- alert: EnvoyConfigRejected
  expr: increase(envoy_cluster_manager_cds_update_rejected[5m]) > 0
  for: 2m
  labels: { severity: critical }
  annotations:
    summary: "{{ $labels.pod }} 가 CDS 구성을 거부함 — 구 구성으로 동작 중"
```

## 4. 아웃라이어 감지 — 느린 인스턴스 자동 격리

로드밸런싱만으로는 "살아 있지만 느린" 인스턴스를 걸러내지 못한다. 헬스체크는 통과하는데 GC 가 길거나 디스크가 느려 p99 가 10배인 파드가 대표적이다. 아웃라이어 감지는 실제 요청 결과를 관찰해 그런 인스턴스를 일시적으로 풀에서 빼낸다(ejection).

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: payments
spec:
  host: payments.default.svc.cluster.local
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 200
        connectTimeout: 3s
      http:
        http2MaxRequests: 500
        maxRequestsPerConnection: 0
        maxRetries: 3
    outlierDetection:
      consecutive5xxErrors: 5
      consecutiveGatewayErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 30
      minHealthPercent: 50
      splitExternalLocalOriginErrors: true
      consecutiveLocalOriginFailures: 3
```

파라미터의 상호작용을 정확히 이해해야 한다. `interval` 은 이젝션 판정 주기이고, `baseEjectionTime` 은 격리 기간의 기준값이다. 실제 격리 시간은 `baseEjectionTime × 연속 이젝션 횟수` 로 증가한다. 같은 파드가 3번째 이젝션되면 90초간 빠진다. 지수적 백오프와 같은 효과로, 만성적으로 불량한 인스턴스를 점점 오래 배제한다.

`maxEjectionPercent` 는 안전장치다. 업스트림 전체가 동시에 느려지는 상황(예: 공통 DB 장애)에서 이 값이 없으면 모든 인스턴스가 이젝션되어 요청이 갈 곳을 잃는다. 30% 는 "최소 70%는 남긴다" 는 보수적 선택이고, 인스턴스가 3개뿐인 서비스라면 33% 도 1개 격리를 의미하므로 인스턴스 수를 고려해 정해야 한다.

`splitExternalLocalOriginErrors: true` 는 중요한 구분이다. 업스트림이 돌려준 5xx(external origin)와 Envoy 자체의 커넥션 실패·타임아웃(local origin)을 나눠 센다. 이 구분이 없으면 네트워크 순단으로 발생한 커넥션 오류가 애플리케이션 오류로 집계되어 과도한 이젝션을 유발한다.

이젝션 현황 확인:

```bash
curl -s localhost:15000/stats | grep outlier_detection
# cluster.outbound|8080||payments...outlier_detection.ejections_active: 1
# cluster.outbound|8080||payments...outlier_detection.ejections_enforced_total: 7
# cluster.outbound|8080||payments...outlier_detection.ejections_overflow: 2

curl -s localhost:15000/clusters | grep -E "payments.*(health_flags|cx_active)"
# ...::10.4.3.22:8080::health_flags::/failed_outlier_check
```

`ejections_overflow` 가 증가한다면 `maxEjectionPercent` 상한에 걸려 이젝션하지 못한 경우다. 업스트림 전체가 나쁘다는 신호이므로, 사이드카 설정이 아니라 업스트림 자체를 봐야 한다.

## 5. 재시도 예산 — 재시도가 장애를 키우는 경로

재시도는 개별 요청의 성공률을 올리지만, 시스템 전체로는 위험한 증폭기다. 업스트림이 과부하로 느려지면 타임아웃이 늘고, 타임아웃마다 재시도가 붙어 부하가 2~3배가 되며, 그 결과 더 느려진다. 메타스테이블 장애(metastable failure)의 교과서적 경로다.

재시도 예산(retry budget)은 "전체 활성 요청 대비 재시도 비율" 에 상한을 두어 이 되먹임을 끊는다.

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: payments
spec:
  hosts: [ payments.default.svc.cluster.local ]
  http:
    - timeout: 3s
      retries:
        attempts: 2
        perTryTimeout: 1s
        retryOn: connect-failure,refused-stream,gateway-error,reset
        retryRemoteLocalities: false
      route:
        - destination: { host: payments.default.svc.cluster.local }
```

`timeout: 3s`, `perTryTimeout: 1s`, `attempts: 2` 의 관계를 계산으로 확인해야 한다. 최악의 경우 1s(1차) + 백오프 + 1s(2차) 로 전체 타임아웃 3s 안에 들어간다. `perTryTimeout × attempts` 가 전체 `timeout` 을 넘으면 마지막 시도가 잘려 무의미해진다.

`retryOn` 목록에서 `5xx` 를 통째로 넣는 것은 피한다. 500 은 애플리케이션 버그일 가능성이 높고 재시도해도 같은 결과가 나오며 부하만 2배가 된다. `connect-failure`(커넥션 자체 실패), `refused-stream`(HTTP/2 GOAWAY 후 거절), `reset`(RST) 처럼 **요청이 처리되지 않았음이 확실한** 경우로 한정하는 것이 원칙이다. 비멱등 연산(POST 결제)에는 `retryOn` 을 `connect-failure` 만 남기거나 아예 재시도를 끄고, 애플리케이션 레벨 멱등키로 처리한다.

Envoy 네이티브 재시도 예산은 서킷브레이커 스레셔드에 포함된다.

```yaml
# EnvoyFilter 로 retry_budget 주입 (Istio CRD 미노출 항목)
circuit_breakers:
  thresholds:
    - priority: DEFAULT
      max_connections: 200
      max_pending_requests: 100
      max_requests: 500
      max_retries: 3
      retry_budget:
        budget_percent: { value: 20.0 }
        min_retry_concurrency: 3
```

`budget_percent: 20` 은 활성 요청의 20% 까지만 재시도로 채울 수 있다는 뜻이다. 활성 요청 500건이면 재시도 동시성 상한은 100건이다. `max_retries` 고정값과 달리 트래픽에 비례하므로, 저부하에서는 관대하고 고부하에서는 자동으로 조여든다. 예산 초과는 통계로 드러난다.

```bash
curl -s localhost:15000/stats | grep -E "upstream_rq_retry|circuit_breakers"
# cluster.outbound|8080||payments...upstream_rq_retry: 1420
# cluster.outbound|8080||payments...upstream_rq_retry_limit_exceeded: 87
# cluster.outbound|8080||payments...upstream_rq_retry_overflow: 312
# cluster.outbound|8080||payments...circuit_breakers.default.rq_retry_open: 1
```

`upstream_rq_retry_overflow` 가 크면 예산이 실제로 재시도를 막고 있는 것이고, 이는 정상 동작이다. 동시에 업스트림이 문제 상태라는 신호이므로 알람의 근거로 쓴다.

## 6. 서킷브레이커 스레셔드 설계

Envoy 서킷브레이커는 Hystrix 식 "상태 머신" 이 아니라 **동시성 상한**이다. 임계를 넘으면 즉시 503 으로 거절하고, 넘지 않으면 통과시킨다. 상태 전이나 half-open 이 없어 동작이 단순하고 예측 가능하다.

| 항목 | 의미 | 산정 기준 |
|---|---|---|
| `max_connections` | 업스트림 TCP 커넥션 상한 | HTTP/1.1 에서만 의미 큼 |
| `max_pending_requests` | 커넥션 할당 대기 큐 | HTTP/1.1 에서 핵심 |
| `max_requests` | 동시 활성 요청 | HTTP/2 에서 핵심 |
| `max_retries` | 동시 재시도 | 예산과 병행 |

HTTP/2 업스트림에서는 커넥션 하나에 다중 스트림이 붙으므로 `max_connections` 는 거의 의미가 없고 `max_requests` 가 실질 제어 지점이다. 반대로 HTTP/1.1 에서는 `max_connections` 가 곧 동시성이고 초과분은 `max_pending_requests` 큐에 쌓인다. 프로토콜을 확인하지 않고 값을 복사하는 것이 가장 흔한 실수다.

값 산정은 리틀의 법칙으로 한다. 목표 처리량 500 rps, 평균 응답 40ms 라면 필요한 동시성은 500 × 0.04 = 20 이다. 여기에 지연 변동을 감안해 3~5배 여유를 두면 `max_requests` 60~100 이 합리적이다. 1000 같은 값을 넣으면 서킷브레이커는 사실상 비활성이고, 과부하가 그대로 업스트림으로 전달된다.

## 7. 로케일리티 인지 라우팅과 장애 도메인

멀티 AZ 환경에서 같은 AZ 내 엔드포인트를 우선하면 지연과 네트워크 비용이 함께 줄어든다. 문제는 로컬 AZ 가 죽었을 때의 전환이다.

```yaml
trafficPolicy:
  loadBalancer:
    localityLbSetting:
      enabled: true
      failover:
        - from: ap-northeast-2a
          to: ap-northeast-2c
    simple: LEAST_REQUEST
  outlierDetection:
    consecutive5xxErrors: 5
    interval: 10s
    baseEjectionTime: 30s
```

로케일리티 페일오버는 **아웃라이어 감지가 켜져 있어야 동작한다**. Envoy 는 로컬 로케일리티의 건강한 엔드포인트 비율로 오버프로비저닝 계수(기본 1.4)를 곱해 전환 여부를 판단하는데, 건강 여부 판정 자체가 아웃라이어 감지 또는 액티브 헬스체크에서 나오기 때문이다. `outlierDetection` 없이 `localityLbSetting` 만 켜면 로컬 AZ 가 완전히 죽어도 전환되지 않는다.

`LEAST_REQUEST` 는 기본 `ROUND_ROBIN` 보다 응답 시간 편차가 큰 백엔드에서 유리하다. Envoy 의 구현은 전수 비교가 아니라 P2C(power of two choices) — 무작위 2개를 뽑아 활성 요청이 적은 쪽을 고른다. O(1) 비용으로 거의 최적에 가까운 분산을 얻는 표준 기법이다.

## 8. 점진 적용 절차

기존 서비스에 위 설정을 한 번에 넣으면 트래픽이 끊길 수 있다. 다음 순서를 권한다.

1. **관측만 먼저**: 서킷브레이커·아웃라이어를 매우 느슨한 값으로 넣고 통계만 수집한다. 2주간 `upstream_rq_pending_overflow`, `outlier_detection.ejections_enforced_total` 의 기저선을 확보한다.
2. **아웃라이어 감지 적용**: `maxEjectionPercent: 10` 처럼 보수적으로 시작한다. 이젝션이 정상 인스턴스를 치고 있지 않은지 파드별로 확인한다.
3. **재시도 정책 정비**: `retryOn` 을 멱등 가능한 조건으로 좁히고 `perTryTimeout` 을 명시한다. 비멱등 엔드포인트는 별도 VirtualService 로 분리한다.
4. **서킷브레이커 조이기**: 리틀의 법칙 산정값의 5배 → 3배 순으로 단계적으로 낮추며 `pending_overflow` 를 감시한다.
5. **부하 시험으로 검증**: 업스트림에 인위적 지연을 주입(Istio fault injection)하고, 재시도 폭주 없이 빠르게 실패하는지 확인한다.

```yaml
# 검증용 결함 주입
http:
  - fault:
      delay: { percentage: { value: 20 }, fixedDelay: 5s }
      abort: { percentage: { value: 5 }, httpStatus: 503 }
    route:
      - destination: { host: payments.default.svc.cluster.local }
```

주입 상태에서 클라이언트 측 p99 가 전체 `timeout` 값 근처에서 잘리고, `upstream_rq_retry` 가 예산 상한에서 멈추며, 이젝션이 결함 인스턴스에만 적용되면 설계가 의도대로 동작하는 것이다.

## 참고

- Envoy Proxy, *xDS REST and gRPC protocol* 및 *Incremental xDS* 문서
- Envoy Proxy, *Outlier detection* / *Circuit breaking* 아키텍처 문서
- Istio, *Destination Rule* / *Virtual Service* API 레퍼런스
- Bronson, Aghayev, Abd-El-Malek, Zhu, "Metastable Failures in Distributed Systems", HotOS 2021
- Mitzenmacher, "The Power of Two Choices in Randomized Load Balancing", IEEE TPDS 2001
