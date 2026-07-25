Notion 원본: https://app.notion.com/p/3a85a06fd6d3812f9f40db6ca4824ca5

# gRPC HTTP/2 스트리밍과 흐름 제어 및 데드라인 전파

> 2026-07-25 신규 주제 · 확장 대상: 통신 네트워크(HTTP/2·RPC 프로토콜)

## 학습 목표

- gRPC의 네 가지 RPC 유형이 HTTP/2 스트림 위에 어떻게 매핑되는지 파악한다.
- HTTP/2 흐름 제어(WINDOW_UPDATE)가 스트림별·연결별로 백프레셔를 만드는 메커니즘을 정리한다.
- 데드라인이 클라이언트에서 서버·하위 호출로 전파되는 방식과 취소 신호 처리를 구분한다.
- keepalive·연결 재사용·HoL 블로킹 트레이드오프를 실무 관점에서 설명한다.

## 1. gRPC는 HTTP/2 위의 RPC다

gRPC는 자체 전송 프로토콜을 만들지 않고 HTTP/2를 그대로 사용한다. 각 RPC 호출은 하나의 HTTP/2 **스트림**에 대응한다. 요청 메타데이터는 HTTP/2 HEADERS 프레임으로, 메시지 본문(Protobuf 직렬화)은 DATA 프레임으로, 응답 트레일러(상태 코드 포함)는 다시 HEADERS 프레임으로 전달된다. gRPC의 상태(`grpc-status`)는 HTTP 상태 코드가 아니라 트레일러 헤더에 실려 온다 — HTTP 200이어도 `grpc-status`가 비-0이면 RPC 실패다.

이 설계의 핵심 이득은 HTTP/2의 **스트림 멀티플렉싱**이다. 하나의 TCP 연결에 수백 개의 동시 스트림(RPC)을 실을 수 있어, HTTP/1.1의 연결당 한 요청 제약과 head-of-line 블로킹(HTTP 레벨)을 없앨다. 클라이언트는 연결 하나를 재사용해 여러 RPC를 병렬 진행한다.

## 2. 네 가지 RPC 유형과 스트림 매핑

gRPC는 요청·응답의 스트리밍 여부로 네 유형을 제공한다.

| 유형 | 요청 | 응답 | 스트림 위 흐름 |
|---|---|---|---|
| Unary | 단일 | 단일 | DATA 1개씩 |
| Server streaming | 단일 | 스트림 | 서버가 DATA 다수 |
| Client streaming | 스트림 | 단일 | 클라이언트가 DATA 다수 |
| Bidirectional | 스트림 | 스트림 | 양방향 DATA 교차 |

```protobuf
service ChatService {
  rpc Send(Message) returns (Ack);                       // unary
  rpc Subscribe(Topic) returns (stream Message);         // server streaming
  rpc Upload(stream Chunk) returns (UploadResult);       // client streaming
  rpc Converse(stream Message) returns (stream Message); // bidirectional
}
```

네 유형 모두 **단일 HTTP/2 스트림** 안에서 처리된다. 스트리밍은 새 연결이나 새 스트림을 여는 게 아니라, 같은 스트림에서 DATA 프레임을 여러 번 주고받는 것이다. 양방향 스트리밍은 한 스트림에서 클라이언트와 서버가 독립적으로 DATA를 보내며, 한쪽이 스트림을 반쯤 닫아도(half-close) 반대 방향은 계속 열려 있을 수 있다. 이 반이중 특성 덕분에 요청을 다 보내기 전에 응답을 받기 시작하는 파이프라이닝이 가능하다.

## 3. HTTP/2 흐름 제어와 백프레셔

스트리밍에서 생산자가 소비자보다 빠르면 데이터가 쌓인다. HTTP/2는 이를 **흐름 제어 윈도우**로 막는다. 수신자는 자신이 처리할 수 있는 바이트 수만큼 윈도우를 광고하고, 송신자는 윈도우 잔량 이하만 보낼 수 있다. 수신자가 데이터를 소비하면 `WINDOW_UPDATE` 프레임으로 윈도우를 되돌려주고, 그만큼 송신자가 추가 전송한다.

흐름 제어는 두 계층에서 동작한다. **스트림 레벨** 윈도우는 개별 RPC의 흐름을, **연결 레벨** 윈도우는 그 연결의 모든 스트림 합계를 제한한다. 어느 한쪽이라도 0이 되면 송신이 멈춘다. 이 이중 구조가 "느린 소비자 한 명이 연결 전체를 굶기지 않도록" 격리하면서도 연결 총량을 통제한다.

애플리케이션 관점에서 이것이 곳 gRPC의 자동 백프레셔다. 서버 스트리밍에서 클라이언트가 메시지를 천천히 읽으면, 흐름 제어 윈도우가 차서 서버의 `stream.Send()`가 블록(또는 언어별로 대기)된다. 즉 애플리케이션이 별도 큐잉 없이도 생산 속도가 소비 속도에 맞춰진다. 다만 기본 윈도우 크기(HTTP/2 기본 64KB)가 고대역폭·고지연(BDP가 큰) 경로에서는 처리량 병목이 된다. gRPC 구현은 흐름을 관측해 윈도우를 동적으로 키우는 **BDP 추정 기반 자동 튜닝**을 제공하며, 필요 시 초기 윈도우를 수 MB로 수동 설정할 수 있다.

## 4. 데드라인 전파 — 취소의 사슬

gRPC의 데드라인은 "이 RPC는 시점 T까지 완료돼야 한다"는 절대 시각이다. 클라이언트가 데드라인을 설정하면 `grpc-timeout` 헤더로 서버에 전달되고, 서버는 데드라인 초과 시 자동으로 `DEADLINE_EXCEEDED`를 반환한다. 타임아웃(상대 시간)이 아니라 데드라인(절대 시각)인 이유는 **전파** 때문이다.

서비스 A가 데드라인 100ms로 서비스 B를 부르고, B가 다시 C를 부른다고 하자. B가 C를 호출할 때 새로 100ms를 주면 안 된다 — 이미 A→B에서 시간이 흘렀기 때문이다. 데드라인을 절대 시각으로 전파하면, B는 "남은 시간"만 C에게 넘긴다. 이렇게 데드라인이 호출 사슬을 따라 내려가면서, 최상위 요청이 만료되는 순간 사슬 전체가 동시에 만료된다. 불필요한 하위 작업이 계속되는 낭비를 막는다.

```go
ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
defer cancel()
resp, err := client.Send(ctx, req)
if status.Code(err) == codes.DeadlineExceeded {
    // 데드라인 초과 처리
}

// 서버 — 하위 호출에 같은 ctx 를 넘겨 남은 데드라인 전파
func (s *server) Handle(ctx context.Context, req *Req) (*Resp, error) {
    return s.downstream.Call(ctx, subReq)
}
```

데드라인 초과나 클라이언트 취소는 HTTP/2 `RST_STREAM` 프레임으로 서버에 전달된다. 서버는 `ctx.Done()` 신호를 관측해 진행 중인 작업(DB 쿼리, 파일 I/O)을 중단해야 한다 — context를 하위 호출과 goroutine에 성실히 전파하지 않으면 클라이언트는 떠났는데 서버는 계속 자원을 쓰는 좀비 작업이 생긴다. 데드라인 전파의 실효성은 결국 애플리케이션이 context를 끝까지 전달하는가에 달렸다.

## 5. keepalive와 연결 관리

gRPC는 연결을 오래 재사용하므로 죽은 연결을 감지하는 keepalive가 중요하다. 클라이언트는 주기적으로 HTTP/2 PING 프레임을 보내 연결 생존을 확인한다. 응답이 없으면 연결을 끕고 재수립한다. 특히 장시간 유휴한 서버 스트리밍(구독형)에서 중간 프록시·로드밸런서가 idle 연결을 끕는 문제를 keepalive PING이 방지한다.

주의점은 **keepalive 과다**다. 클라이언트가 너무 잔게 PING을 보내면 서버는 이를 남용으로 보고 `ENHANCE_YOUR_CALM`과 함께 연결을 끕을 수 있다(gRPC의 기본 정책: 유휴 연결에서 최소 간격 미만 PING 거부). 그래서 클라이언트의 `keepalive.time`과 서버의 `MinTime` 정책을 정합시켜야 한다. 실무 기본값은 keepalive 간격을 수십 초~수 분으로 두되 서버 정책과 어깃나지 않게 맞추는 것이다.

## 6. HoL 블로킹과 트레이드오프

HTTP/2 멀티플렉싱은 HTTP 레벨의 head-of-line 블로킹을 없았지만, **TCP 레벨** HoL 블로킹은 남는다. 하나의 TCP 연결에 여러 스트림이 실리므로, 패킷 하나가 유실되면 TCP 재전송이 끝날 때까지 그 연결의 **모든 스트림**이 멈춘다. 손실률이 높은 네트워크에서 이는 gRPC(HTTP/2)의 약점이다. HTTP/3(QUIC)는 스트림별 독립 전송으로 이 문제를 해결하지만, gRPC의 HTTP/3 지원은 아직 성숙 단계가 아니다.

실무 트레이드오프를 정리하면, 단일 연결 재사용은 연결 수립 비용(TCP+TLS 핸드쉐이크)과 연결 관리 부담을 줄이지만 TCP HoL과 단일 연결의 흐름 제어 한계를 안는다. 처리량이 매우 높은 경로에서는 클라이언트가 여러 서브채널로 연결을 분산(connection pooling)해 단일 연결 병목과 HoL 영향을 완화한다. 반대로 마이크로서비스 간 저지연 호출 대부분은 단일 연결 멀티플렉싱으로 충분하며, 오히려 연결을 늘리면 서버 측 소켓·메모리 부담이 커진다.

## 7. 로드밸런싱 — L4의 함정과 L7 해법

gRPC의 연결 재사용은 로드밸런싱에서 예상치 못한 문제를 일으킨다. 전통적 L4(TCP) 로드밸런서는 **연결 단위**로 분배한다. 그런데 gRPC 클라이언트는 하나의 장수명 연결에 모든 RPC를 멀티플렉싱하므로, 그 연결이 배정된 단 하나의 백엔드에만 모든 요청이 몰린다. 백엔드를 늘려도 트래픽이 고르게 퍼지지 않는 것이다.

해결책은 두 갈래다. 첫째, **클라이언트 측 로드밸런싱**이다. gRPC 클라이언트가 서비스 디스커버리(DNS, xDS)로 여러 백엔드 주소를 받아 각각에 서브채널을 열고 `round_robin` 등 정책으로 RPC를 분산한다. 백엔드 목록 변화는 리졸버가 갱신한다.

둘째, **L7(gRPC-aware) 프록시**다. Envoy, gRPC용 인그레스는 HTTP/2 스트림을 이해해 **요청(스트림) 단위**로 분배한다. 서비스 메시(Istio 등)가 사이드카 Envoy로 이 역할을 수행하는 구성이 쿼버네티스 환경의 표준이다. L4로 gRPC를 로드밸런싱하려다 특정 파드만 과부하되는 것은 gRPC 도입 초기의 대표적 함정이므로, 처음부터 L7 또는 클라이언트 LB를 전제해야 한다.

## 8. 에러 모델과 재시도 안전성

gRPC의 상태 코드는 HTTP 상태와 다른 자체 체계(`OK`, `INVALID_ARGUMENT`, `DEADLINE_EXCEEDED`, `UNAVAILABLE`, `RESOURCE_EXHAUSTED` 등)를 쓴다. 재시도 설계에서 코드의 의미 구분이 핵심이다. `UNAVAILABLE`은 일시적 장애라 재시도가 안전하지만, `INVALID_ARGUMENT`는 재시도해도 같은 결과이므로 재시도가 무의미하다. `DEADLINE_EXCEEDED`는 이미 시간이 초과됐으므로 상위 데드라인이 남아 있을 때만 재시도가 의미 있다.

gRPC는 서비스 설정(service config)에 선언적 재시도 정책을 지원한다.

```json
{
  "methodConfig": [{
    "name": [{ "service": "ChatService" }],
    "retryPolicy": {
      "maxAttempts": 4,
      "initialBackoff": "0.1s",
      "maxBackoff": "1s",
      "backoffMultiplier": 2,
      "retryableStatusCodes": ["UNAVAILABLE", "RESOURCE_EXHAUSTED"]
    }
  }]
}
```

재시도의 안전성 전제는 **멱등성**이다. 서버가 요청을 이미 처리한 뒤 응답만 유실됐을 수 있으므로, 비멱등 연산(중복 결제 등)은 재시도 대상에서 빼거나 멱등 키로 중복을 방어해야 한다. 또한 재시도 백오프는 지수적으로 늘려 장애 서버에 재시도 폭풍(retry storm)을 일으키지 않게 한다.

종합하면 gRPC를 프로덕션에서 안정적으로 운용하는 레버는 확장된다. 스트림·흐름 제어의 백프레셔를 이해하고, 데드라인을 절대 시각으로 성실히 전파하며, keepalive를 서버 정책과 정합시키고, 로드밸런싱은 L7/클라이언트 LB로 스트림 단위 분배를 보장하며, 재시도는 상태 코드 의미와 멱등성에 근거해 설계한다. 이 다섯 축을 갖추면 gRPC는 HTTP/1.1 REST 대비 낮은 지연과 높은 처리량을 안정적으로 실현한다.

## 참고

- gRPC Documentation — Core Concepts, Architecture and Lifecycle (https://grpc.io/docs/what-is-grpc/core-concepts/)
- RFC 9113 — HTTP/2, Section 5.2 Flow Control (https://www.rfc-editor.org/rfc/rfc9113.html)
- gRPC Blog — Deadlines and Cancellation Propagation (https://grpc.io/blog/deadlines/)
- gRPC Documentation — Keepalive User Guide (https://github.com/grpc/grpc/blob/master/doc/keepalive.md)
