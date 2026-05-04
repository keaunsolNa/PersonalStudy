Notion 원본: https://www.notion.so/3565a06fd6d3812dbfcfdbfeeade9c6a

# AWS ECS Fargate Service Connect — ECS Native Service Mesh 동작 원리

> 2026-05-04 신규 주제 · 확장 대상: Kubernetes Operator, Istio Service Mesh

## 학습 목표

- ECS Service Connect 가 Envoy 사이드카를 어떻게 자동으로 주입하고 ECS Service Discovery / App Mesh 와 어떤 차이를 가지는지 정리한다
- 클라이언트→서버 트래픽이 Service Connect 의 *대체 DNS 이름* 으로 흐를 때 Envoy → 대상 task 까지의 호 흐름을 단계별로 추적한다
- Outlier Detection · Retry · 헬스 체크가 Envoy config 어디에 매핑되는지, 그리고 사용자가 어디까지 제어 가능한지 식별한다
- Service Connect, ECS Service Discovery(Cloud Map A 레코드), ALB 기반 라우팅 세 가지의 비교표를 만들어 *언제 무엇을 쓸지* 결정 기준을 만든다

## 1. ECS 네트워킹의 4단 변천사

ECS 의 서비스 간 통신은 시기마다 다음과 같이 바뀌어 왔다.

| 세대 | 메커니즘 | 한계 |
|---|---|---|
| 1세대 | EC2 ECS + bridged networking + Docker links | 호스트 종속, 포트 충돌 |
| 2세대 | awsvpc + ALB/NLB | LB 비용, 서비스마다 LB |
| 3세대 | Cloud Map (Service Discovery) DNS | 헬스 체크 단순, 클라이언트 사이드 LB 없음 |
| 4세대 | Service Connect (Envoy 사이드카) | 4세대가 본 글 주제 |

3세대의 Cloud Map 기반 Service Discovery 는 *DNS A 레코드* 로 task 의 IP 목록을 알려 준다. 클라이언트는 그냥 `orders.local` 로 DNS 질의를 하고 IP 들 중 하나에 직접 연결한다. 단순하지만 *클라이언트 사이드 부하 분산이 약하고*, 헬스 체크가 느리게(60초 단위) 반영되며, 재시도/타임아웃을 클라이언트 코드가 책임져야 했다.

Service Connect 는 *Envoy 사이드카* 를 task 별로 자동 주입해 *클라이언트 사이드 LB · 헬스 체크 · 재시도 · mTLS* 를 사용자 코드 변경 없이 제공한다. ECS 가 직접 Envoy 를 관리하므로 App Mesh 처럼 별도 control plane 을 운영할 필요가 없다.

## 2. Service Connect 의 task 토폴로지

Service Connect 가 활성화된 ECS Service 의 task 안 구조는 다음과 같다.

```
ECS Task (awsvpc, IP=10.0.1.4)
├─ container "app"        : 사용자 컨테이너 (port 3000)
└─ container "ecs-service-connect-agent"
    └─ Envoy proxy
       ├─ inbound listener  : 0.0.0.0:8080 → 127.0.0.1:3000
       └─ outbound listener : 127.0.0.1:15001 (애플리케이션 가상 IP 캡처)
```

ECS 에이전트가 task 시작 시 *Envoy 컨테이너를 자동으로 추가* 하고, 사용자 컨테이너의 `/etc/hosts` 와 iptables 가 같이 갱신된다. 사용자 코드는 `http://orders/api/v1/orders` 같은 *short DNS 이름* 으로 호출하면 되고, OS 수준에서 그 이름이 127.0.0.x 의 가상 IP 로 매핑된다. 가상 IP 트래픽이 Envoy outbound listener 로 들어오고, Envoy 가 실제 대상 task 의 IP:port 로 forward 한다.

이 구조 덕분에 *Envoy 가 모든 outbound 호출을 가로채서 LB · 헬스 체크 · 메트릭 수집을 적용* 할 수 있다. 사용자 코드는 일반 HTTP 클라이언트만 쓰면 된다.

## 3. ServiceConnectConfiguration 정의 예시

ECS Service 정의에 다음 블록을 추가하면 Service Connect 가 활성화된다.

```json
{
  "serviceName": "orders",
  "taskDefinition": "orders:42",
  "desiredCount": 5,
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-aaa", "subnet-bbb"],
      "securityGroups": ["sg-app"],
      "assignPublicIp": "DISABLED"
    }
  },
  "serviceConnectConfiguration": {
    "enabled": true,
    "namespace": "prod.local",
    "services": [
      {
        "portName": "http",
        "discoveryName": "orders",
        "clientAliases": [
          { "port": 80, "dnsName": "orders" }
        ]
      }
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/service-connect/orders",
        "awslogs-region": "ap-northeast-2",
        "awslogs-stream-prefix": "envoy"
      }
    }
  }
}
```

- `namespace`: Cloud Map HTTP namespace. Service Connect 는 기존 DNS namespace 가 아니라 *HTTP namespace* 를 사용해 동적 endpoint 를 관리한다.
- `clientAliases.dnsName`: 클라이언트 task 가 사용할 short DNS 이름. namespace 안에서 유일해야 한다.
- `clientAliases.port`: 클라이언트가 사용하는 가상 포트. 실제 백엔드 포트와 달라도 된다.

Service Connect 를 사용하는 클라이언트 서비스는 *동일 namespace* 를 자기 ServiceConnectConfiguration 에 선언만 해 두면, 별도 service entry 없이 outbound 트래픽을 자동으로 잡아낸다.

## 4. Envoy 가 가져오는 dynamic config

Envoy 는 ECS 에이전트로부터 *xDS-like* 동적 설정을 받는다. AWS 가 자체 관리하므로 사용자가 직접 Envoy yaml 을 작성할 필요는 없지만 어떤 항목이 매핑되는지 파악해 두면 디버깅이 쉽다.

| 사용자 노출 옵션 | Envoy 내부 매핑 |
|---|---|
| `clientAliases.port` | LDS (Listener) on `127.0.0.x:port` |
| 헬스 체크 grace period | EDS endpoint health |
| Outlier Detection (베타) | CDS Cluster outlier_detection |
| Retry policy | RDS RouteConfig retry_policy |
| Idle timeout | LDS listener filter http_connection_manager |
| TLS Policy | CDS transport_socket TLS context |

Outlier Detection 은 *연속 실패한 endpoint 를 일시적으로 LB pool 에서 제외* 한다. Service Connect 의 기본 정책은 5xx 5회 연속 / 30초 ejection 이지만 console 또는 API 로 조정 가능하다. *transient 장애를 빠르게 흡수* 하지만 너무 공격적이면 *전체 endpoint 가 ejection 되어 NoHealthyUpstream* 으로 trip 한다.

Retry 는 기본 비활성화다. 활성화 시 *idempotent 메서드 (GET, HEAD, OPTIONS)* 에만 적용을 권장한다. POST 에 retry 를 켜면 중복 결제 같은 사고가 난다.

## 5. CloudWatch 메트릭과 트레이싱

Service Connect 는 task 별 / 서비스 별로 다음 표준 메트릭을 emit 한다.

| 메트릭 | 차원 |
|---|---|
| `ServiceConnect.RequestCountPerTarget` | DiscoveryName, TargetGroup |
| `ServiceConnect.RequestLatency` (p50/p99) | DiscoveryName, ResponseCode |
| `ServiceConnect.HttpCode_Target_5XX_Count` | DiscoveryName |
| `ServiceConnect.ProcessedBytes` | DiscoveryName |
| `ServiceConnect.ConnectionAttempts` | DiscoveryName |

ALB 의 메트릭과 유사한 구조라 기존 대시보드를 그대로 옮겨오기 쉽다. 모든 메트릭이 *클라이언트 측 Envoy 에서 emit* 되므로 LB 보다 *진짜 클라이언트가 본 latency* 에 가깝다.

분산 트레이싱은 X-Ray native 통합이 가장 단순하다. Service Connect 의 Envoy 는 X-Amzn-Trace-Id 헤더를 자동으로 propagate 하고, X-Ray daemon 이 task 안에서 같이 돌면 segment 가 자동 연결된다. OpenTelemetry / W3C Trace Context 로 옮기려면 Envoy 의 tracing config 를 사용자가 명시적으로 추가해야 한다(현재는 console 노출이 제한적이라 raw API 로 설정).

## 6. Service Connect vs ECS Service Discovery vs ALB

세 메커니즘을 한 표로 비교해 두면 결정 기준이 명확하다.

| 항목 | Service Connect | ECS Service Discovery (Cloud Map A) | ALB |
|---|---|---|---|
| 클라이언트 사이드 LB | O (Envoy) | X (DNS RR) | N/A (서버 사이드) |
| 헬스 체크 반영 시간 | 수 초 | 수십 초 | 수 초 |
| 재시도 / 회로 차단 | O (제한적) | X | X |
| mTLS / 암호화 | O | 별도 | TLS termination 위치에 따름 |
| Layer 7 라우팅 | 단순 path/host | X | 풍부 (Path, Host, Header, Method) |
| 추가 비용 | 사이드카 CPU/메모리 | 거의 무료 | LB 시간당 + LCU |
| Public 노출 | X (내부 통신용) | X | O |
| 주된 용도 | 서비스 간 동서 트래픽 | 단순 내부 lookup | 외부 HTTP 진입점 |

*외부 트래픽 진입* 은 ALB, *내부 동서 트래픽* 은 Service Connect, *Lambda 등 서버리스가 직접 DNS 로 호출* 해야 하는 단순 경우는 Service Discovery 만 두는 조합이 일반적이다. 동일 클러스터 안에 세 가지가 공존해도 무방하다.

## 7. 무중단 배포와 트래픽 이동

ECS 서비스의 *rolling update* 와 Service Connect 의 동작을 함께 이해해야 한다.

배포 시 흐름:

1. ECS 가 새 task 정의로 task N+1 을 띄움
2. 새 task 안 Envoy 가 namespace 의 endpoint 정보를 받음
3. 새 task 가 Service Connect inbound listener 로 *자기 자신을 endpoint 로 등록*
4. 기존 클라이언트 task 들의 Envoy 가 *new endpoint 를 발견* 하고 LB pool 에 추가
5. 헬스 체크 통과 후 트래픽이 자동 분산
6. 기존 task 가 `ECS task draining` 진입, Envoy 가 자기 inbound listener 를 graceful shutdown
7. 클라이언트 측 Envoy 가 *removal* 을 감지해 LB pool 에서 제외

`task draining` 이 충분히 긴 grace period 를 가져야 in-flight 요청이 끊기지 않는다. ECS 의 `stopTimeout` 은 기본 30초인데, 응답 시간이 긴 API 서비스라면 이 값을 늘려야 한다.

CodeDeploy Blue/Green 배포와도 호환된다. 같은 namespace 안에 같은 `discoveryName` 을 사용하는 *두 서비스 (blue, green)* 가 동시에 존재하면 LB pool 에 둘 다 들어간다. 트래픽 비중 조절은 ECS 서비스의 `desiredCount` 비율로 거칠게, 또는 deploymentConfiguration 의 *traffic shift* 로 세밀하게 가능하다.

## 8. 한계와 우회

Service Connect 가 모든 케이스를 커버하지는 않는다. 자주 부딪히는 한계 세 가지.

첫째, *gRPC bidirectional streaming* 의 retry 가 제한적이다. 현재 정책은 단방향 unary 호출에 최적화되어 있고 long-lived stream 은 사용자 코드가 reconnect 를 책임져야 한다.

둘째, *Lambda → ECS Service Connect* 호출이 직접적으로 안 된다. Lambda 는 사이드카가 없으므로 namespace 의 short DNS 이름을 해석할 수 없다. 우회는 (1) ALB 를 앞에 두거나 (2) Cloud Map 의 DNS namespace 를 추가로 만들어 Lambda 가 DNS 로만 lookup 하게 하는 것이다.

셋째, *세밀한 L7 라우팅* (예: 헤더 기반 카나리)이 부족하다. 이런 시나리오는 ALB Listener Rule 또는 App Mesh / Istio 같은 본격 service mesh 가 더 잘 맞는다.

대체로 *내부 동서 트래픽의 80% 시나리오* 를 가장 적은 운영 부담으로 커버하려는 도구다. 아주 복잡한 라우팅이 필요한 일부 서비스만 ALB / App Mesh 로 분리하는 하이브리드 전략이 현실적이다.

## 참고

- AWS Docs — Amazon ECS Service Connect: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect.html
- AWS Docs — Service Connect parameters: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect-parameters.html
- AWS Containers Blog — Introducing ECS Service Connect: https://aws.amazon.com/blogs/containers/
- Envoy proxy documentation — Listeners & Clusters: https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/intro/intro
- AWS Cloud Map — Namespaces: https://docs.aws.amazon.com/cloud-map/latest/dg/working-with-namespaces.html
