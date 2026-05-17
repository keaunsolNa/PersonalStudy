Notion 원본: https://www.notion.so/3635a06fd6d381918b9cf19e211e19d0

# OpenTelemetry Collector Tail Sampling과 Probabilistic Sampler Trace 샘플링 비교

> 2026-05-17 신규 주제 · 확장 대상: AWS/DevOps 관측성

## 학습 목표

- Head-based, Tail-based, Probabilistic 세 가지 OpenTelemetry trace sampling 전략의 의사 결정 시점과 정보량을 비교한다
- OpenTelemetry Collector 의 `tail_sampling` processor 가 어떻게 trace 를 buffering 하고 policy 평가를 수행하는지 추적한다
- TraceState 와 W3C trace context 의 sampled flag 가 cross-process 전파 시 어떻게 일관성을 보장하는지 이해한다
- 운영에서 latency 기반/error 기반/key 속성 기반 sampling 정책을 조합해 비용을 통제한다

## 1. Trace 샘플링이 필요한 이유

분산 시스템 한 트랜잭션은 5~50개 span 으로 늘어난다. 초당 5000 요청을 처리하는 서비스가 모든 span 을 보관한다면 일일 수십~수백 GB 의 trace 데이터를 만든다. Jaeger/Tempo/Datadog 의 비용은 ingest 량에 비례하므로, *대표성 있는 trace 일부만 저장* 하는 것이 sampling 의 목적이다. 핵심 trade-off 는 *정보 손실 vs 비용*. 1% 만 저장하면 비용은 1/100 이지만 희귀 에러는 99% 확률로 못 본다.

## 2. Head-based Sampling

샘플 결정이 *trace 시작 시점* 에 내려진다. 첫 service 의 SDK 가 새 traceId 를 만들 때 "sampled = true/false" 가 정해지고, trace context 의 `traceparent` 헤더 마지막 byte로 모든 downstream 에 전파된다.

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                                                                ^^ sampled flag
```

장점: 결정이 처음 한 번만 일어나 모든 service 에서 일관됨, downstream service 는 자기 결정 없이 그대로 따르므로 CPU/메모리 이득, network egress 없음. 단점: 시작 시점엔 *trace 가 흥미로운지 모름* — 결과적으로 에러가 날지, 1초 이상 걸릴지 모름.

```java
SdkTracerProvider.builder()
    .setSampler(Sampler.parentBasedOf(Sampler.traceIdRatioBased(0.01)))
    .build();
```

## 3. Probabilistic Sampling (확률 샘플링)

Head-based 의 가장 단순한 구현이 traceId 해시 기반 확률 샘플링. OpenTelemetry Collector 의 `probabilistic_sampler` processor 도 같은 방식.

```yaml
processors:
  probabilistic_sampler:
    sampling_percentage: 5
    hash_seed: 22
```

같은 traceId 는 어느 노드에서 평가해도 같은 결정. 문제는 *분포의 균질성*. 0.01 확률 샘플링이라도, 1초간 100개 trace 중 0~3개가 샘플될 수 있어 *희귀 케이스 보장은 불가*.

## 4. Tail-based Sampling

샘플 결정을 *trace 완료 후* 내린다. Collector 가 모든 span 을 일정 시간 버퍼링했다가, trace 전체가 모이면 policy 평가 후 export/drop. 장점: "100ms 이상 걸린 trace 만" "에러 span 포함된 trace 만" 같은 content-aware 결정 가능, 정보 손실이 적음. 단점: memory (모든 trace 의 모든 span 을 RAM 에 보관), 분산 환경에서 trace affinity 필요, latency.

## 5. OpenTelemetry Collector tail_sampling 설정

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
      - name: slow-policy
        type: latency
        latency: { threshold_ms: 1000 }
      - name: critical-route
        type: string_attribute
        string_attribute:
          key: http.route
          values: ["/payment/*", "/checkout/*"]
      - name: probabilistic-policy
        type: probabilistic
        probabilistic: { sampling_percentage: 1 }
      - name: rate-limited
        type: rate_limiting
        rate_limiting: { spans_per_second: 200 }
```

각 policy 는 OR 결합. ERROR 상태 span 포함 trace 100% export, 1초 이상 걸린 trace 100% export, 그 외는 1% 확률. `num_traces` 50000 × 평균 10 span × 1.5KB = 약 750MB 메모리.

## 6. Trace Affinity 부하 분산

Tail sampling 의 전제는 *같은 traceId 의 모든 span 이 같은 collector 인스턴스로* 들어오는 것.

```yaml
exporters:
  loadbalancing:
    routing_key: traceID
    protocol:
      otlp: { tls: { insecure: true } }
    resolver:
      dns:
        hostname: otel-collector-sampler.otel.svc.cluster.local
        port: 4317
```

운영 토폴로지: SDK → gateway collector (LB exporter) → sampler collectors × N (tail_sampling) → exporter (Tempo/Jaeger). gateway tier 는 stateless, sampler tier 는 stateful.

## 7. Sampling Decision 의 propagation: TraceState

head-based + probabilistic 환경에서, downstream 이 *추가 sampling* 을 적용하면 안 된다. W3C Trace Context 의 `traceparent` 헤더는 sampled flag 만 전달하고, *확률값/sampling 비율* 같은 메타정보는 `tracestate` 헤더에 vendor 별로 인코딩한다.

```
tracestate: ot=p:8;th:c
```

이는 OpenTelemetry 가 정의한 "Adjusted Count" 표현. 이 메커니즘으로 "1% 샘플된 100개 trace 가 사실은 10000개 trace 를 대표한다" 는 가중치 정보를 backend 가 알 수 있고, 메트릭 추정 시 보정 가능.

## 8. 비용/정보량 trade-off 정량화

전형적 운영 시나리오 — 5000 req/s, trace 평균 20 span, span 평균 1.5KB, 30일 보관.

| 전략 | 보관량/일 | 비용 비율 | 에러 trace 보존율 |
|---|---|---|---|
| 100% 보관 | 12.96 TB | 100% | 100% |
| Head 1% | 130 GB | 1% | 1% |
| Tail: 에러 100% + 1% 그 외 | 160 GB | 1.2% | 100% |
| Tail: 에러 100% + slow >1s 100% + 1% | 220 GB | 1.7% | 100% |

추가 1.7% 비용으로 모든 에러와 모든 슬로우 trace 를 보존. tail sampling 의 ROI 가 분명히 높다.

## 9. 실전 함정과 대응

**메모리 폭주** — `num_traces` 한도 초과 burst 시 *결정 전 trace 가 강제 drop*. metric `processor_tail_sampling_traces_evicted_total` 모니터링 필수. **부분 trace** — 한 service 가 30초 이상 hang 시 partial trace. **Cardinality 폭발** — `string_attribute` policy 에 `user.id` 같은 high-cardinality 키 금지. **Stateless/Stateful 혼동** — k8s sampler collector 를 Deployment + HPA scale-out 시 routing key 재해시 → trace 분리. StatefulSet + 고정 replica 권장. **Adaptive Sampling 부재** — 기본 tail_sampling 은 정적 정책만. **Vendor backend 종속** — Datadog/New Relic SaaS 는 자체 ingest-time sampling 이 있어 collector tail sampling 과 이중 적용 가능. 정책 한쪽에 집중. tail sampling 은 *exception path 보존* 에 강하지만 *희귀 정상 케이스 보존* 에는 약하다. 신규 endpoint, A/B test treatment, user cohort 같은 중요 trace 는 `tracestate.ot.priority=1` 마커를 붙여 100% policy 로 매칭.

## 참고

- OpenTelemetry Collector — tail_sampling processor: https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor
- OpenTelemetry Sampling: https://opentelemetry.io/docs/concepts/sampling/
- W3C Trace Context: https://www.w3.org/TR/trace-context/
- OTEP-0235 Probability Sampling: https://github.com/open-telemetry/oteps/blob/main/text/trace/0235-sampling-threshold-in-trace-state.md
- "Tail Sampling at Scale" — Grafana Labs Tempo blog
- Splunk APM Adaptive Sampling 백서
