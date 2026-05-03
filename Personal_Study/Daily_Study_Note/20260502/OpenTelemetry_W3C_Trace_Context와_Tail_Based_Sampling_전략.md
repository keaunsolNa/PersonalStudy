Notion 원본: https://www.notion.so/3545a06fd6d3813ca211ee44949a1674

# OpenTelemetry W3C Trace Context와 Tail-Based Sampling 전략

> 2026-05-02 신규 주제 · 확장 대상: 통신_네트워크, 면접을_위한_CS_전공지식_노트

## 학습 목표

- W3C Trace Context (`traceparent`, `tracestate`) 헤더 형식과 propagation 규칙을 안다
- Head-based / Tail-based / Probabilistic / Adaptive 샘플링의 trade-off 를 비교한다
- OpenTelemetry Collector 의 tail_sampling processor 구성을 설계한다
- 분산 trace 의 cardinality 와 storage 비용을 운영 단위로 관리한다

## 1. Trace Context 의 표준화 — 왜 W3C 인가

Zipkin B3, Jaeger uber-trace-id, OpenCensus 가 각각 다른 헤더로 trace 를 전파해 멀티 SDK 환경에서 fragmentation 이 컸다. W3C Trace Context (REC, 2020-02) 는 단일 헤더 `traceparent` 와 vendor 확장용 `tracestate` 를 표준화했다. OpenTelemetry SDK 는 기본 propagator 로 W3C 를 채택하고, 호환을 위해 B3 multi/single 도 함께 켤 수 있다. Spring Cloud Sleuth → Micrometer Tracing 이행, Envoy / Istio sidecar 의 헤더 통일도 모두 같은 방향이다.

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
              ^   ^                                ^                  ^
              |   |                                |                  flags(8bit)
              |   |                                parent-id(64bit)
              |   trace-id(128bit)
              version(8bit)
```

flags 의 LSB 가 1 이면 sampled, 0 이면 dropped 으로 해석된다. version 은 현재 0x00 만 정의되어 있고, 알 수 없는 version 을 받으면 SDK 는 새 trace 를 생성하지 않고 그대로 propagate 만 한다.

## 2. Propagation 규칙과 동기/비동기 경계

OpenTelemetry SDK 는 outgoing HTTP/gRPC/Kafka 의 inject, incoming 의 extract 를 자동으로 수행한다. 명시적으로 dispatcher 를 만드는 코드 (예: ExecutorService 직접 제출) 에서는 context 를 사용자가 전파해야 한다.

```java
import io.opentelemetry.context.Context;

ExecutorService exec = Context.taskWrapping(Executors.newFixedThreadPool(8));
exec.submit(() -> { /* 부모 trace context 자동 전달 */ });
```

Reactor / Project Reactor 의 `Mono`, `Flux` 는 ContextView 로 tracing context 를 운반한다. Micrometer Tracing 이 `ObservationRegistry` 를 통해 자동으로 옮겨준다. 단, raw `CompletableFuture` 와 manual ThreadPool 은 여전히 사용자의 책임이다.

## 3. Head-based Sampling — 단순함과 한계

가장 일반적인 sampling 전략은 trace 의 root span 생성 시점에 sampled / dropped 를 결정하는 head-based 다. SDK 의 `ParentBased(TraceIdRatioBased(0.05))` 가 대표 예로, root 에서 5% 만 표본을 보낸다.

장점은 단순함과 SDK 부담 최소화이고, 단점은 "오류가 발생한 trace" 같은 사후 신호를 반영할 수 없다는 것이다. 운영 상 1% 샘플링을 켜 두면 평소 traffic 은 잘 보이지만, 5xx 가 발생한 trace 99 % 는 이미 drop 된 후라 디버깅이 곤란하다. tail-based 가 필요한 이유다.

## 4. Tail-based Sampling — Collector 가 결정

Tail-based 는 trace 의 모든 span 을 일단 받고, 일정 시간 buffer 한 뒤 trace 단위로 keep / drop 을 결정한다. OpenTelemetry Collector `tail_sampling` processor 가 표준 구현이다.

```yaml
processors:
  tail_sampling:
    decision_wait: 30s
    num_traces: 50000
    expected_new_traces_per_sec: 1000
    policies:
      - name: errors-policy
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: slow-trace
        type: latency
        latency: { threshold_ms: 500 }
      - name: sample-5pct
        type: probabilistic
        probabilistic: { sampling_percentage: 5 }
exporters:
  otlp/jaeger: { endpoint: jaeger:4317, tls: { insecure: true } }
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [tail_sampling]
      exporters: [otlp/jaeger]
```

policy 는 OR 결합이며, 하나라도 sampling 으로 결정하면 trace 가 keep 된다. `decision_wait` 는 trace 의 last span 이 도착하기를 기다리는 시간이다. 너무 짧으면 long-running trace 가 잘리고, 너무 길면 메모리 사용이 증가한다. 30 s 가 일반 시작점이다.

## 5. Adaptive Sampling — 트래픽 변동 대응

피크 시간에 5% 도 너무 많고, 새벽엔 5% 도 부족하다. adaptive sampling 은 RPS 또는 queue 깊이에 따라 비율을 조정한다. OTel Collector 의 `probabilistic_sampler` 단독으로는 정적 비율만 지원하므로, 사이드에서 RPS 를 metric 으로 받아 OPA 또는 custom processor 가 비율을 갱신하는 형태가 보통이다. Honeycomb / Lightstep / Datadog 의 SaaS APM 은 자체 server-side adaptive sampler 를 제공한다.

또 다른 접근은 dynamic head-based 다. 서비스 메시 (Istio, Linkerd) 의 control-plane 이 sampling 비율을 전체 클러스터에 push 하면, 모든 SDK 가 같은 비율로 동작한다. 단, 정책 변경의 일관성 보장이 어렵고, 핵심 서비스의 SLO violation 이 일어나는 trace 만 keep 하는 fine-grain 기능에는 부적합하다.

## 6. Cardinality — 메트릭/태그 폭증의 함정

Tracing 은 span 수, attribute 개수, attribute 값의 unique 수에 따라 storage 와 query 비용이 결정된다. 다음 attribute 는 high-cardinality 를 만들기 쉬우므로 정책적으로 저장 정책을 정한다.

| Attribute | 위험도 | 권장 |
| --- | --- | --- |
| `http.target` (URL with path param) | 높음 | template 으로 정규화 (`/users/{id}`) |
| `db.statement` 원문 | 높음 | sanitize 후 `db.statement.template` |
| `user.id` | 매우 높음 | 보존 기간 짧게 + index 제외 |
| `error.stacktrace` | 매우 높음 | 샘플링 후만 저장 |

OTel SDK 의 SpanProcessor `BatchSpanProcessor` 와 자체 attribute filter 를 두어 보내기 전에 정규화하는 것이 가장 비용 효율적이다.

## 7. Storage — 보존 정책과 hot/cold tiering

Trace storage 는 trace_id 인덱스가 핵심이며, 보통 7~30 일 보존이 일반적이다. Tempo (Grafana) 는 object storage (S3) 에 chunk 를 직접 저장하고 인덱스만 in-memory 또는 boltdb 로 두어 비용이 가장 낮다. Jaeger backend (Cassandra/ES) 는 query 가 더 자유롭지만 저장 비용이 크다. Datadog APM 같은 SaaS 는 데이터 ingest GB 당 과금이라 cardinality 통제가 비용에 직결된다.

운영 권장은 (a) tail-sampling 으로 ingest 를 1~5% 로 줄이되 error/slow trace 100% 보존, (b) hot tier 7 일 + cold tier (S3) 30~90 일, (c) 1년 이상 archive 는 trace 가 아니라 RED metric (Rate / Error / Duration) 의 1m rollup 으로만 보관하는 전략이다.

## 8. 메트릭과 trace exemplar 연결

Prometheus 의 exemplar 는 메트릭 데이터 포인트에 trace_id 를 연결하는 기능이다. OpenMetrics 표준의 일부로 Prometheus 2.30+ 에서 지원되며, Grafana 에서 그래프 spike 를 클릭하면 해당 trace 가 바로 열린다.

```text
# HELP http_request_duration_seconds Request duration
# TYPE http_request_duration_seconds histogram
http_request_duration_seconds_bucket{le="0.5"} 1027 # {trace_id="4bf92f3577b34da6a3ce929d0e0e4736"} 0.42
```

Micrometer 의 `@Timed` 와 OTel tracer 가 함께 켜져 있으면 자동으로 exemplar 가 발행된다. Sentry / Honeycomb 도 비슷한 cross-link 를 지원한다. Trace 만 본다고 운영 디버깅이 끝나지 않으니, metric → trace → log 의 3-pillar 가 한 클릭으로 이어지는 환경이 이상적이다.

## 9. 함정과 운영 가이드

`tracestate` 는 vendor 별 데이터를 운반하지만 길이 제한 (HTTP 헤더 8KB 일반) 으로 인해 oversize 시 일부 vendor entry 가 잘릴 수 있다. 프록시 (Cloudflare, ALB) 가 헤더를 strip 하는지 사전 확인한다. 일부 ALB 설정은 `traceparent` 를 forward 하지 않는 경우가 있어, 클러스터 ingress 는 명시적으로 allow-list 한다.

또한 sampling 결정의 일관성이 중요하다. service A 는 sampled, service B 는 dropped 가 되면 trace 가 partial 로 남는다. OTel SDK 의 `ParentBased` sampler 는 incoming sampled bit 를 존중하므로, root sampler 만 잘 설계하면 일관성이 유지된다. 모든 서비스가 동일 SDK 버전과 sampler config 를 쓰는지 CI 에서 점검한다.

마지막으로 PII 누설 방지 — `db.statement` 원문에 사용자 데이터가 그대로 들어갈 수 있다. SDK 의 `SpanExporter` 직전에 attribute redact processor 를 두고, 정기적으로 sample trace 를 검토해 신규 PII 가 등장하지 않는지 확인한다.

## 참고

- W3C Trace Context Recommendation <https://www.w3.org/TR/trace-context/>
- OpenTelemetry Collector — tail_sampling processor <https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor>
- Grafana Tempo Architecture <https://grafana.com/docs/tempo/latest/operations/architecture/>
- Honeycomb — Tail-based Sampling Patterns <https://www.honeycomb.io/blog/tail-based-sampling>
- Prometheus OpenMetrics Exemplars <https://prometheus.io/docs/concepts/exemplars/>
