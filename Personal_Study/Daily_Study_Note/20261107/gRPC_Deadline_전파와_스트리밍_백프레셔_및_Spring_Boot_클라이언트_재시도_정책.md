Notion 원본: https://www.notion.so/4115a06fd6d3814fa662c972de1fc8fb

# gRPC Deadline 전파와 스트리밍 백프레셔, Spring Boot 클라이언트 재시도 정책

> 2026-11-07 신규 주제 · 확장 대상: Spring / REST_API / Network

## 학습 목표

- HTTP/2 스트림 위에서 gRPC 호출의 상태 코드·메타데이터·트레일러 구조를 구분한다
- Deadline 을 서비스 체인 전체로 전파하고 취소(Cancellation)를 연쇄시킨다
- 서버/클라이언트 스트리밍에서 `isReady` 와 흐름 제어로 백프레셔를 구현한다
- Service Config 기반 retry / hedging 정책과 멱등성 경계를 설정한다
- Spring Boot(grpc-java) 환경에서 인터셉터로 타임아웃·로깅·인증을 일관되게 적용한다

## 1. 전송 계층: HTTP/2 위의 gRPC

gRPC 호출 하나는 HTTP/2 스트림 하나다. 요청은 `HEADERS`(`:path=/pkg.Service/Method`, `content-type: application/grpc`, `grpc-timeout`, `te: trailers`) 뒤에 길이-접두(length-prefixed) 메시지 프레임(1바이트 압축 플래그 + 4바이트 길이 + 페이로드)이 `DATA` 로 이어진다. 응답은 `HEADERS` → `DATA` → **트레일러 `HEADERS`** (`grpc-status`, `grpc-message`) 로 끝난다. 상태 코드가 HTTP 상태가 아니라 트레일러에 있기 때문에, L7 프록시/로드밸런서는 반드시 HTTP/2 트레일러를 투과해야 한다. nginx 는 `grpc_pass` 로, ALB 는 HTTP/2 target group 으로 이를 지원한다.

연결 수준에서는 `SETTINGS_MAX_CONCURRENT_STREAMS`(보통 100~)와 스트림/커넥션 단위 flow-control window(기본 64KB)가 처리량을 제한한다. 대용량 스트리밍 전송이 느리다면 먼저 `initialFlowControlWindow` 를 의심한다. grpc-java 의 Netty transport 는 기본 1MB 근처로 시작해 BDP 추정으로 동적 조정한다.

## 2. 상태 코드: 재시도 가능 여부의 기준

gRPC 상태 코드는 17개(OK 포함)다. 설계자가 기억할 것은 "재시도해도 안전한가"의 분류다.

| 코드 | 의미 | 재시도 |
|---|---|---|
| UNAVAILABLE | 서버/네트워크 일시 불가 | 가능(핵심 대상) |
| DEADLINE_EXCEEDED | 기한 초과 | 서버 처리 여부 불명 — 멱등일 때만 |
| RESOURCE_EXHAUSTED | 쿼터/레이트 제한 | 백오프 후, 서버가 힌트 제공 시 |
| ABORTED | 동시성 충돌 | 상위 레벨에서 재시도 |
| INVALID_ARGUMENT / NOT_FOUND / PERMISSION_DENIED | 클라이언트 오류 | 불가 |
| INTERNAL / UNKNOWN | 서버 버그 | 보통 불가 |

`DEADLINE_EXCEEDED` 는 "서버가 실행했을 수도 있다"는 뜻이다. 결제 승인 같은 비멱등 RPC 는 Idempotency-Key 를 요청 필드나 메타데이터로 넘겨 서버에서 중복 제거해야 안전하게 재시도할 수 있다.

## 3. Deadline 과 전파

gRPC 는 타임아웃이 아니라 **절대 시각(deadline)** 개념을 쓴다. 클라이언트가 `withDeadlineAfter(500, MILLISECONDS)` 를 설정하면 와이어에는 `grpc-timeout: 500m` 형태로 남은 시간이 실리고, 서버는 수신 시점부터 `Context` 에 deadline 을 만든다. 서버 핸들러가 다른 서비스를 호출할 때 `Context.current()` 의 deadline 이 자동으로 하위 호출에 상속된다(`ClientCalls` 가 `Context.current().getDeadline()` 와 CallOptions 의 deadline 중 더 이른 것을 사용).

```java
// 클라이언트: 호출마다 deadline 지정 (stub 은 불변이므로 withDeadline* 은 새 stub 반환)
OrderReply reply = orderStub
    .withDeadlineAfter(800, TimeUnit.MILLISECONDS)
    .getOrder(GetOrderRequest.newBuilder().setId(id).build());

// 서버 핸들러: 하위 호출은 남은 시간 안에서만
@Override
public void getOrder(GetOrderRequest req, StreamObserver<OrderReply> out) {
    Context ctx = Context.current();
    if (ctx.isCancelled()) return;                 // 클라이언트가 이미 포기
    Deadline d = ctx.getDeadline();                // 남은 시간
    long remainMs = d == null ? Long.MAX_VALUE : d.timeRemaining(TimeUnit.MILLISECONDS);
    if (remainMs < 50) {                           // 남은 시간이 너무 짧으면 빨리 실패
        out.onError(Status.DEADLINE_EXCEEDED.withDescription("insufficient budget").asRuntimeException());
        return;
    }
    PriceReply price = priceStub.getPrice(...);    // deadline 자동 상속
    out.onNext(OrderReply.newBuilder().setPrice(price.getValue()).build());
    out.onCompleted();
}
```

체인 A→B→C 에서 A 가 1초를 주면 B 가 300ms 를 쓰고 C 에는 700ms 이하만 남는다. 이 "예산 감소" 덕분에 상위가 이미 포기한 요청을 하위가 계속 처리하는 낭비(zombie work)가 사라진다. 취소는 `RST_STREAM` 으로 전파되고 서버는 `Context.CancellationListener` 또는 `ServerCallStreamObserver.setOnCancelHandler` 로 알림을 받아 DB 쿼리 등을 중단해야 효과가 있다. 핸들러가 취소를 무시하면 deadline 만 있고 자원은 계속 소모된다.

## 4. 스트리밍과 백프레셔

gRPC 는 네 가지 패턴(unary / server streaming / client streaming / bidi)을 지원한다. `StreamObserver.onNext` 는 기본적으로 **버퍼에 쌓기만 하고 반환**하므로, 느린 클라이언트에게 빠르게 `onNext` 를 호출하면 서버 메모리가 부푼다. 이를 막으려면 `ServerCallStreamObserver` 의 `isReady()` 와 `setOnReadyHandler` 를 쓴다.

```java
@Override
public void exportOrders(ExportRequest req, StreamObserver<OrderRow> out) {
    ServerCallStreamObserver<OrderRow> so = (ServerCallStreamObserver<OrderRow>) out;
    Iterator<OrderRow> it = repository.scan(req);             // 커서 기반 스캔
    so.setOnCancelHandler(() -> repository.close(it));

    Runnable drain = () -> {
        while (so.isReady() && it.hasNext()) {                 // 전송 윈도우에 여유가 있을 때만
            so.onNext(it.next());
        }
        if (!it.hasNext() && so.isReady()) so.onCompleted();
    };
    so.setOnReadyHandler(drain);                               // 윈도우가 열릴 때마다 호출
}
```

클라이언트 쪽 수신 제어는 `ClientResponseObserver.beforeStart` 에서 `disableAutoRequestWithInitial(n)` 으로 자동 요청을 끄고 `request(1)` 로 수동 풀(pull)을 구현한다. 처리 속도가 느린 소비자(예: DB 배치 insert)에서는 이 방식이 서버의 `isReady()` 를 false 로 만들어 체인 전체에 백프레셔를 전달한다. 반대로 `isReady` 를 무시하면 Netty 쓰기 버퍼가 high-water mark 를 넘기고 결국 OOM 위험이 있다. Reactive 스택(reactor-grpc)을 쓰면 `Flux` 의 `request(n)` 이 곧 이 수동 풀로 매핑된다.

## 5. 재시도와 헤징: Service Config

grpc-java 는 name resolver 가 주는 Service Config(JSON) 로 재시도를 선언적으로 설정한다.

```java
Map<String, Object> retryPolicy = Map.of(
    "maxAttempts", 4.0,
    "initialBackoff", "0.1s",
    "maxBackoff", "1s",
    "backoffMultiplier", 2.0,
    "retryableStatusCodes", List.of("UNAVAILABLE"));
Map<String, Object> methodCfg = Map.of(
    "name", List.of(Map.of("service", "order.OrderService", "method", "GetOrder")),
    "retryPolicy", retryPolicy);
Map<String, Object> config = Map.of(
    "methodConfig", List.of(methodCfg),
    "retryThrottling", Map.of("maxTokens", 10.0, "tokenRatio", 0.1));

ManagedChannel channel = ManagedChannelBuilder.forTarget("dns:///order.internal:9090")
    .defaultServiceConfig(config)
    .enableRetry()                    // 1.x 대에서 기본 활성이지만 명시 권장
    .usePlaintext()
    .build();
```

`maxAttempts` 는 서버 선언 상한(5)으로 클램프된다. 재시도는 **응답 헤더를 받기 전**의 실패(투명 재시도 포함)에 한해 안전하게 일어나고, 헤더 수신 후 실패는 재시도되지 않는다(커밋 시점). `retryThrottling` 은 토큰 버킷으로, 실패 시 1 토큰 차감·성공 시 `tokenRatio` 만큼 보충하며 토큰이 `maxTokens/2` 이하이면 재시도를 중단한다. 장애 시 재시도 폭풍(retry storm)을 막는 장치이므로 반드시 설정한다. 재시도 총 시간은 deadline 으로 잘리므로, 재시도 4회에 요청당 deadline 800ms 면 전체 예산이 800ms 임을 인지해야 한다(시도별 타임아웃이 아니다). `hedgingPolicy` 는 응답이 느릴 때 병렬로 추가 요청을 보내 tail latency 를 줄이는 옵션이며 반드시 멱등 RPC 에만 적용한다.

## 6. Spring Boot 통합: 인터셉터로 일관성 확보

Spring Boot 에서는 `grpc-spring-boot-starter` 계열(net.devh 또는 spring-grpc)을 쓰거나 직접 `ManagedChannel` 을 빈으로 등록한다. 공통 정책은 인터셉터에 모은다.

```java
public class DefaultDeadlineInterceptor implements ClientInterceptor {
    private final Duration defaultTimeout;
    public DefaultDeadlineInterceptor(Duration d) { this.defaultTimeout = d; }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions opts, Channel next) {
        if (opts.getDeadline() == null) {                       // 호출 측이 지정하지 않았을 때만
            opts = opts.withDeadlineAfter(defaultTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return next.newCall(method, opts);
    }
}

@Configuration
class GrpcClientConfig {
    @Bean ManagedChannel orderChannel(@Value("${order.target}") String target) {
        return ManagedChannelBuilder.forTarget(target)
            .intercept(new DefaultDeadlineInterceptor(Duration.ofSeconds(2)))
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false)
            .build();
    }
}
```

keepalive 는 서버 정책(`permitKeepAliveTime`)보다 짧게 보내면 `GOAWAY too_many_pings` 로 연결이 끊긴다. 기본 서버 허용 최소값이 5분이므로 클라이언트를 30초로 두려면 서버에서 `permitKeepAliveTime(20, SECONDS)` 를 맞춰야 한다. 서버 쪽에는 `ServerInterceptor` 로 인증 메타데이터 검증과 `MDC` 로의 요청 ID 주입을 넣고, `Metadata.Key<String> REQUEST_ID = Metadata.Key.of("x-request-id", ASCII_STRING_MARSHALLER)` 로 헤더를 전파한다. Spring Security 의 `SecurityContext` 는 ThreadLocal 이라 gRPC executor 스레드 전환 시 사라지므로 `Context` 에 인증 정보를 실어 `Contexts.interceptCall` 로 리스너 실행 구간에 붙인다.

## 7. 로드밸런싱과 운영 이슈

gRPC 는 HTTP/2 장수명 연결 하나에 호출을 다중화하므로 L4 로드밸런서 뒤에서는 **연결 단위로만 분산**되어 특정 서버에 쏠린다. 해법은 클라이언트 측 LB(`round_robin` + DNS 다중 A 레코드 또는 headless Service)이거나 L7 LB(Envoy)의 요청 단위 분산이다. Kubernetes 에서는 ClusterIP 를 쓰면 쏠림이 생기므로 `dns:///svc.ns.svc.cluster.local` + headless Service + `defaultLoadBalancingPolicy("round_robin")` 를 쓰고, 롤링 배포 시에는 서버가 `maxConnectionAge`/`maxConnectionAgeGrace` 로 연결을 주기적으로 `GOAWAY` 시켜 재분산을 유도한다. 관측성은 OpenTelemetry gRPC 계측 + `grpc.server.call.duration` 히스토그램을 status 코드 라벨로 나누어 보고, DEADLINE_EXCEEDED 비율이 오르면 하위 의존성 지연을 의심한다.

## 8. 장애 시나리오 점검표

하위 서비스가 느려졌을 때 상위의 모든 호출이 deadline 까지 대기하면 스레드·연결이 고갈된다. 방어선은 세 겹이다 — 짧은 기본 deadline, 재시도 스로틀링, 서킷브레이커(Resilience4j 를 인터셉터에 결합)다. 롤아웃 중 UNAVAILABLE 급증은 재시도로 흡수되는지 확인하고, 서버 종료 시 `server.shutdown()` 후 `awaitTermination` 으로 진행 중 스트림이 deadline 안에 끝나게 한다. 마지막으로 메시지 크기 제한(`maxInboundMessageSize` 기본 4MB)을 넘는 RPC 는 `RESOURCE_EXHAUSTED` 로 실패하므로 대용량은 스트리밍으로 분할한다.

## 참고

- gRPC 공식 문서 — Deadlines, Cancellation, Retry, Flow Control (grpc.io/docs/guides)
- gRPC Proposal A6: Client Retries / A2: Service Config in DNS
- grpc-java GitHub — examples/retrying, ServerCallStreamObserver Javadoc
- HTTP/2 RFC 9113 — Flow Control
