Notion 원본: https://www.notion.so/3635a06fd6d381f5bcc1f8bf0008b0c4

# gRPC HTTP/2 Stream과 FlowControl Window Bidirectional Streaming Backpressure

> 2026-05-17 신규 주제 · 확장 대상: REST_API/Network

## 학습 목표

- gRPC 의 4가지 RPC 타입(unary, server-streaming, client-streaming, bidi)이 HTTP/2 stream 위에서 어떻게 표현되는지 frame 단위로 추적한다
- HTTP/2 의 connection-level / stream-level flow control window 와 WINDOW_UPDATE frame 이 backpressure 를 만드는 메커니즘을 이해한다
- gRPC bidi streaming 에서 sender/receiver 의 처리 속도 차이를 onReady / isReady listener 로 다루는 패턴을 익힌다
- HOL blocking, max concurrent streams, deadline propagation 등 운영상 핵심 함정과 대응을 정리한다

## 1. gRPC 가 HTTP/2 위에 올라가는 이유

gRPC 는 HTTP/2 의 Multiplexed stream (한 TCP 연결 위 다수 stream 동시 진행), Binary framing (HTTP/2 frame 위에 length-prefixed protobuf payload), Flow control (sender 가 receiver 의 buffer 한도를 알고 자동 대기) 세 특성을 활용. REST/HTTP 1.1 은 chunked transfer encoding 으로 밀어붙이기만 가능했는데, gRPC 는 HTTP/2 flow control 덕분에 양방향으로 자동 backpressure 발생.

## 2. 4가지 RPC 타입의 frame 시퀀스

`Unary`:

```
Client → HEADERS (method=POST, path=/svc.Method, content-type=application/grpc) END_HEADERS
Client → DATA (1 grpc-message) END_STREAM
Server → HEADERS (:status=200, content-type) END_HEADERS
Server → DATA (1 grpc-message)
Server → HEADERS (grpc-status=0) END_HEADERS, END_STREAM
```

응답 trailer에 `grpc-status` 가 담긴다는 점이 HTTP/2 표준 status 와의 차이. `Server streaming`: 클라이언트 첫 DATA + END_STREAM, 서버 여러 DATA + trailer. `Client streaming`: 클라이언트 여러 DATA + END_STREAM. `Bidi`: 양쪽이 자율적으로 DATA frame.

## 3. HTTP/2 Flow Control 의 정확한 의미

HTTP/2 는 두 단계 flow control window: **Stream-level window** (각 stream sender 의 bytes 크레딧, 초기값 65535) + **Connection-level window** (한 연결 전체 합산, 초기값 65535). sender 가 DATA frame 보낼 때마다 두 window 차감. window=0 이면 blocked. receiver 는 application 이 읽은 후 WINDOW_UPDATE 프레임으로 크레딧 복구.

```
Client → Server
window: 65535
DATA (32KB) → window: 33279
DATA (33KB) → window: 279          (다음 frame 송신 불가)
(server가 32KB 읽음)
Server → Client: WINDOW_UPDATE (increment=32768)
window: 33047  (송신 재개)
```

이 메커니즘이 backpressure 의 본질. *receiver 가 처리 못하면 sender 가 멈춘다*. application 코드에 retry/queue 필요 없다.

## 4. gRPC Bidi Streaming 의 backpressure 노출

Java gRPC `StreamObserver` 의 `onNext` 는 동기 호출이지만 내부적으로 buffer 에 enqueue. flow control window 막힌 시 *buffer 무한 증가* → heap OOM. 해결책: `CallStreamObserver` 의 `isReady()` / `setOnReadyHandler`:

```java
ClientCallStreamObserver<Request> req = (ClientCallStreamObserver<Request>) stub.bidi(...);
req.setOnReadyHandler(() -> {
    while (req.isReady() && source.hasNext()) {
        req.onNext(source.next());
    }
});
```

`isReady()` 는 flow control window 가 충분한가의 의미. false 면 송신 중단, 다음 onReady 콜백 대기. 서버 측 ServerCallStreamObserver 도 같은 API.

## 5. Initial Window Size 튜닝

기본 65535 는 1990년대 HTTP/2 표준이 정한 값. RTT 100ms, 100Mbps 회선 시 한 stream 최대 throughput 약 5MB/s. 1Gbps/대량 streaming 에서는 병목.

```java
ManagedChannelBuilder
    .forAddress(host, port)
    .flowControlWindow(8 * 1024 * 1024)
    .build();
```

8MB 가 typical. 너무 크면 receiver buffer 메모리 폭주 위험. receiver 측 max heap 의 1/10 안전 한도. Java gRPC 는 BDP estimation 으로 window 자동 확대.

## 6. MAX_CONCURRENT_STREAMS 와 HOL Blocking

HTTP/2 SETTINGS 의 `MAX_CONCURRENT_STREAMS` 기본 100. gRPC 서버는 보통 무제한 또는 1000+. 문제는 TCP 단 HOL blocking. HTTP/2 multiplexing 은 application layer 이지 transport layer 가 아닌. 한 TCP 패킷 손실 시 OS 가 그 이후 모든 패킷을 재조립 대기 → 같은 연결의 모든 stream 멈춤. QUIC/HTTP/3 가 해결. 운영 대응: **One channel per service** (N 개 channel 풀), **Subchannel pool**, **Stream affinity 회피** (1MB 이상 메시지는 chunking).

## 7. Deadline 과 Cancellation Propagation

gRPC 의 deadline 은 *클라이언트가 명시* + *모든 downstream call 에 전파*. context 가 cancel 되면 in-flight stream 이 즉시 RST_STREAM 으로 종료.

```java
stub.withDeadlineAfter(2, TimeUnit.SECONDS).method(req);
```

서버 측에서 `Context.current().getDeadline()` 으로 남은 시간 조회. 이 propagation 이 마이크로서비스 체인에서 *thundering retry storm* 을 막는다. REST/HTTP 의 timeout 은 각 hop 이 독립적이라 *총 timeout* 이 직관과 다르다. gRPC 의 deadline propagation 이 우월.

## 8. Keepalive 와 Idle Connection

**HTTP/2 PING frame** — 양방향 liveness, 응답 안 오면 connection 종료.

```java
ManagedChannelBuilder
    .keepAliveTime(30, TimeUnit.SECONDS)
    .keepAliveTimeout(10, TimeUnit.SECONDS)
    .keepAliveWithoutCalls(true)
    .build();
```

**Idle timeout** — 일정 시간 RPC 없으면 연결 종료. 운영 함정: AWS NLB/GCP TCP LB 같은 L4 LB 가 idle timeout (보통 350초) 으로 연결 끊으면 클라이언트는 다음 RPC 시 연결이 죽었음을 모르고 송신 → RST 응답. keepalive 를 idle timeout 보다 짧게 설정. 서버 측 PING 폭주 방지 `PERMIT_KEEPALIVE_TIME`.

## 9. 실전 patterns 및 함정

**Streaming + retry 충돌** — gRPC retry 는 unary RPC 위주. streaming 은 idempotency key 로 dedup. **Large message** — protobuf max size 기본 4MB. 100MB+ 는 streaming RPC chunking. **Server load balancing** — gRPC 는 connection 기반 LB. RoundRobinLoadBalancer + name resolver 또는 service mesh. **Browser 클라이언트** — gRPC-Web/Connect-RPC 가 trailer 변환. **Connection storms after deploy** — jitter 분산. **TLS overhead** — connection 재사용이 중요. 한 pod 가 100~1000 stream 을 한 연결에 다중화하는 게 정상. *latency P99 측정* 에서 HTTP/2 flow control 의 backpressure 는 *latency 가 아닌 throughput 으로 표현* 됨에 유의.

## 참고

- HTTP/2 RFC 7540 — Flow Control: https://datatracker.ietf.org/doc/html/rfc7540#section-5.2
- gRPC 공식 문서: https://grpc.io/docs/what-is-grpc/core-concepts/
- gRPC over HTTP/2: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
- grpc-java backpressure: https://grpc.io/docs/languages/java/basics/
- QUIC vs HTTP/2 HOL blocking: https://www.cloudflare.com/learning/performance/what-is-http3/
