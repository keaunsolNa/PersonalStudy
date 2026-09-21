Notion 원본: https://www.notion.so/3e25a06fd6d38196ab15ed107f4d017f

# OpenTelemetry Collector 파이프라인과 테일 샘플링 및 스팬 메트릭 생성

> 2026-09-21 신규 주제 · 확장 대상: AWS, Docker&CI

## 학습 목표

- receiver·processor·exporter 파이프라인에서 프로세서 순서가 결과를 바꾸는 지점을 식별한다
- 헤드 샘플링과 테일 샘플링을 비용·완전성 기준으로 조합하고 로드밸런싱 계층을 설계한다
- spanmetrics 로 RED 지표를 생성할 때 카디널리티 폭발을 차단한다
- 배치·큐·재시도 설정을 메모리 한계와 백프레셔 관점에서 튜닝한다

## 1. Collector 가 필요한 이유

애플리케이션 SDK 가 백엔드로 직접 보내면 될 것 같지만, 실제로는 중간 계층이 거의 항상 필요하다. 이유는 네 가지다.

**자격증명 분리.** 수백 개 서비스에 관측 백엔드 API 키를 배포하는 대신 Collector 하나만 보유한다. 백엔드 교체 시 애플리케이션 재배포도 없다.

**재시도와 버퍼링.** SDK 의 내보내기 큐는 작고, 애플리케이션 프로세스와 생명주기를 공유한다. 파드가 재시작하면 큐의 데이터가 사라진다.

**변환과 보강.** k8s 메타데이터 부착, PII 마스킹, 속성 정규화는 애플리케이션이 아니라 인프라가 할 일이다.

**테일 샘플링.** 트레이스 전체를 본 뒤 유지 여부를 결정하려면 한 트레이스의 모든 스팬이 한 곳에 모여야 한다. SDK 단독으로는 불가능하다.

배포 형태는 두 가지를 섞는다. **에이전트(DaemonSet/사이드카)** 는 노드 로컬에서 수집·보강하고, **게이트웨이(Deployment)** 는 중앙에서 샘플링·집계·전송한다.

```
[App SDK] ─OTLP─▶ [Agent: k8sattributes, memory_limiter, batch]
                        └─OTLP─▶ [Gateway: tailsampling, spanmetrics, export]
```

## 2. 파이프라인 구조와 프로세서 순서

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317, max_recv_msg_size_mib: 16 }
      http: { endpoint: 0.0.0.0:4318 }

processors:
  memory_limiter:
    check_interval: 1s
    limit_percentage: 75
    spike_limit_percentage: 20
  k8sattributes:
    extract:
      metadata: [k8s.namespace.name, k8s.pod.name, k8s.deployment.name, k8s.node.name]
  resourcedetection:
    detectors: [env, system, eks]
  transform:
    error_mode: ignore
    trace_statements:
      - context: span
        statements:
          - delete_key(attributes, "http.request.header.authorization")
          - replace_pattern(attributes["http.url"], "token=[^&]*", "token=REDACTED")
  batch:
    send_batch_size: 8192
    send_batch_max_size: 16384
    timeout: 5s

exporters:
  otlp/backend:
    endpoint: collector.vendor.io:4317
    sending_queue: { enabled: true, num_consumers: 10, queue_size: 5000 }
    retry_on_failure: { enabled: true, initial_interval: 5s, max_elapsed_time: 300s }

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, k8sattributes, resourcedetection, transform, batch]
      exporters: [otlp/backend]
  telemetry:
    metrics: { level: detailed }
```

**순서가 의미를 갖는 규칙 세 가지**가 있다.

첫째, `memory_limiter` 는 **반드시 첫 번째**다. 이 프로세서는 힙 사용량이 임계를 넘으면 데이터를 거부해 리시버가 백프레셔를 발생시키도록 한다. 뒤에 두면 이미 메모리를 쓴 뒤라 보호 효과가 없다.

둘째, `batch` 는 **반드시 마지막**이다. 배치 이후에 필터링·변환을 하면 배치 크기 계산이 무너지고, 샘플링 프로세서가 배치 뒤에 오면 배치가 쪼개져 효율이 떨어진다.

셋째, **필터링은 가능한 한 앞으로** 보낸다. 버릴 데이터에 k8s 메타데이터를 붙이고 변환하는 것은 낭비다. 단 테일 샘플링은 예외로, 전체 트레이스가 모여야 하므로 구조상 뒤쪽 게이트웨이에 놓인다.

`memory_limiter` 의 `limit_percentage` 는 컨테이너 메모리 limit 대비 비율이다. GOMEMLIMIT 과 함께 설정하면 GC 압박을 더 정확히 제어할 수 있다. 실전에서는 컨테이너 limit 의 75~80% 를 limiter 로, GOMEMLIMIT 을 limit 의 90% 로 두는 조합이 안정적이다.

## 3. 헤드 샘플링 vs 테일 샘플링

| 구분 | 헤드 | 테일 |
| --- | --- | --- |
| 결정 시점 | 트레이스 시작 | 트레이스 종료 후 |
| 결정 주체 | SDK | Collector |
| 에러 트레이스 보존 | 확률적 | 100% 가능 |
| 애플리케이션 비용 | 낮음(스팬 미생성) | 높음(전부 생성·전송) |
| Collector 메모리 | 낮음 | 높음(윈도 동안 보관) |
| 상태 | 무상태 | 상태 있음 → 라우팅 필요 |

헤드 샘플링의 치명적 약점은 "에러가 난 트레이스일수록 보고 싶은데, 에러가 날지 미리 알 수 없다"는 것이다. 1% 헤드 샘플링이면 에러 트레이스도 1%만 남는다.

테일 샘플링은 이를 해결하지만 **한 트레이스의 모든 스팬이 같은 Collector 인스턴스에 도달해야 한다**. 게이트웨이가 여러 대이고 로드밸런서가 라운드로빈이면 스팬이 흩어져 판정이 불가능하다. 해법이 `loadbalancing` 익스포터다.

```yaml
# Layer 1: trace ID 해시로 라우팅만
exporters:
  loadbalancing:
    routing_key: traceID
    protocol:
      otlp: { timeout: 10s, tls: { insecure: true } }
    resolver:
      k8s: { service: otel-tailsampling.observability, ports: [4317] }

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [loadbalancing]
```

```yaml
# Layer 2: 실제 테일 샘플링
processors:
  tail_sampling:
    decision_wait: 30s
    num_traces: 100000
    expected_new_traces_per_sec: 2000
    policies:
      - name: errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: slow
        type: latency
        latency: { threshold_ms: 1000 }
      - name: critical-route
        type: and
        and:
          and_sub_policy:
            - name: route
              type: string_attribute
              string_attribute: { key: http.route, values: ["/api/payments.*"], enabled_regex_matching: true }
            - name: rate
              type: probabilistic
              probabilistic: { sampling_percentage: 50 }
      - name: baseline
        type: probabilistic
        probabilistic: { sampling_percentage: 2 }
```

정책은 **OR 로 평가**된다. 하나라도 샘플링을 지시하면 유지된다. 따라서 "에러는 전부 + 느린 것 전부 + 나머지 2%" 가 위 설정의 의미다.

`decision_wait` 이 가장 중요한 파라미터다. 이 시간 동안 스팬을 메모리에 보관하므로 메모리 사용량은 대략 `decision_wait × 초당 스팬 수 × 스팬 크기` 에 비례한다. 너무 짧으면 느린 트레이스의 마지막 스팬이 도착하기 전에 판정이 끝나 트레이스가 잘린다. 애플리케이션의 p99 지연보다 여유 있게 잡되, 배치 타임아웃과 네트워크 지연도 더해야 한다. 실무에서는 30초가 흔한 출발점이다.

`num_traces` 는 동시에 추적하는 트레이스 수의 상한이다. 초과하면 오래된 것부터 버려지고 판정 품질이 조용히 떨어지므로 알람을 걸어 둔다.

## 4. spanmetrics — 트레이스에서 RED 지표 생성

별도 메트릭 계측 없이 스팬에서 요청 수·에러율·지연 분포를 만든다.

```yaml
connectors:
  spanmetrics:
    histogram:
      explicit:
        buckets: [2ms, 5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s]
    dimensions:
      - name: http.route
      - name: http.request.method
      - name: http.response.status_code
      - name: service.namespace
    exclude_dimensions: [span.kind]
    dimensions_cache_size: 10000
    metrics_flush_interval: 30s
    events: { enabled: false }

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [spanmetrics, otlp/backend]
    metrics/spanmetrics:
      receivers: [spanmetrics]
      processors: [batch]
      exporters: [prometheusremotewrite]
```

`spanmetrics` 는 connector 이므로 트레이스 파이프라인의 익스포터이면서 메트릭 파이프라인의 리시버로 동시에 등장한다.

**카디널리티가 이 기능의 유일한 진짜 위험**이다. 생성되는 시계열 수는 대략 다음과 같다.

```
series ≈ Π(각 dimension 의 고유값 수) × (버킷 수 + 2) × 서비스 수
```

`http.url` 을 차원으로 넣으면 경로 파라미터마다 시계열이 생겨 순식간에 수백만 개가 된다. 반드시 `http.route`(템플릿화된 경로)를 쓴다. 방어책은 두 단계다.

```yaml
processors:
  transform/normalize:
    trace_statements:
      - context: span
        statements:
          # 경로 파라미터 정규화
          - replace_pattern(attributes["http.route"], "/[0-9]+", "/{id}")
          - replace_pattern(attributes["http.route"],
              "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/{uuid}")
```

그리고 Prometheus 쪽에서 상한을 건다.

```yaml
exporters:
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write
    resource_to_telemetry_conversion: { enabled: false }   # 리소스 속성 자동 승격 차단
```

`resource_to_telemetry_conversion` 을 켜면 모든 리소스 속성(파드 이름 포함)이 레이블이 되어 카디널리티가 폭발한다. **기본값 false 를 그대로 두는 것이 정답**인 몇 안 되는 설정 중 하나다.

히스토그램 버킷 수도 직접 비용이다. 12개 버킷이면 차원 조합당 14개 시계열이다. 지연 SLO 경계 근처만 초초하게 두고 나머지는 성기게 잡는다. 또는 exponential histogram 으로 바꿔 버킷 수를 고정하면서 해상도를 확보한다.

## 5. 배치·큐·재시도의 상호작용

세 설정이 함께 백프레셔 경로를 만든다.

```
receiver → [memory_limiter] → ... → [batch] → exporter[sending_queue] → 네트워크
              ↑ 거부 시 gRPC RESOURCE_EXHAUSTED         ↑ 가득 차면 드롭
```

`sending_queue.queue_size` 는 **배치 단위**의 개수다. 스팬 개수가 아니다. `send_batch_size: 8192`, `queue_size: 5000` 이면 최대 4천만 스팬을 메모리에 들고 있으려 한다는 뜻이고, 이는 거의 확실히 OOM 이다. 둘을 함께 계산해야 한다.

실무 기준선은 이렇다. 배치 크기 8192 스팬(약 4~8MB), 큐 1000~5000, consumer 10~20. 백엔드가 5분간 죽어도 버티려면 `queue_size ≥ 초당 배치 수 × 300` 이어야 하는데, 그만한 메모리가 없다면 `file_storage` 확장으로 디스크 큐를 쓴다.

```yaml
extensions:
  file_storage/queue:
    directory: /var/lib/otelcol/queue
    timeout: 10s

exporters:
  otlp/backend:
    sending_queue:
      enabled: true
      storage: file_storage/queue   # 재시작에도 큐 유지
      queue_size: 20000
```

디스크 큐는 파드 재시작 시 데이터를 보존하지만, 쓰기 지연이 붙고 PVC 가 필요하다. 트레이스는 손실 허용도가 높으니 메모리 큐, 감사 로그나 결제 관련 이벤트는 디스크 큐라는 구분이 합리적이다.

`retry_on_failure.max_elapsed_time` 을 0(무한)으로 두면 영구 실패하는 데이터가 큐를 영원히 점유한다. 반드시 유한한 값(5~15분)을 둔다.

## 6. Collector 자체 관측

Collector 가 조용히 데이터를 버리는 것이 가장 위험한 실패 모드다. 자기 지표를 반드시 수집한다.

| 지표 | 의미 | 알람 조건 |
| --- | --- | --- |
| `otelcol_receiver_refused_spans` | memory_limiter 거부 | > 0 지속 |
| `otelcol_processor_dropped_spans` | 프로세서 드롭 | > 0 |
| `otelcol_exporter_send_failed_spans` | 전송 실패 | 비율 > 1% |
| `otelcol_exporter_queue_size` / `_capacity` | 큐 사용률 | > 80% |
| `otelcol_processor_batch_batch_send_size` | 실제 배치 크기 | 설정값 대비 과소 |
| `otelcol_process_runtime_heap_alloc_bytes` | 힙 | limit 대비 추세 |
| `..._sampling_trace_dropped_too_early` | 판정 전 만료 | > 0 |

마지막 항목이 테일 샘플링 품질의 직접 지표다. 0 이 아니면 `decision_wait` 이 짧거나 `num_traces` 가 부족한 것이다.

배치 크기가 설정값보다 훨씬 작게 나온다면 `timeout` 이 먼저 만료되고 있다는 뜻이다. 트래픽이 적은 환경에서는 정상이지만, 트래픽이 있는데도 작다면 상류에서 너무 자주 flush 하고 있는지 확인한다.

## 7. 데이터 품질 — 시맨틱 컨벤션 정렬

여러 언어 SDK 와 버전이 섞이면 같은 의미의 속성이 다른 키로 들어온다. HTTP 시맨틱 컨벤션이 안정화되면서 `http.method` → `http.request.method`, `http.status_code` → `http.response.status_code` 로 바뀐 것이 대표적이다. Collector 에서 정규화하면 백엔드 쿼리를 하나로 통일할 수 있다.

```yaml
processors:
  transform/semconv:
    error_mode: ignore
    trace_statements:
      - context: span
        statements:
          - set(attributes["http.request.method"], attributes["http.method"])
              where attributes["http.request.method"] == nil and attributes["http.method"] != nil
          - delete_key(attributes, "http.method")
          - set(attributes["http.response.status_code"], attributes["http.status_code"])
              where attributes["http.response.status_code"] == nil
          - delete_key(attributes, "http.status_code")
```

`error_mode: ignore` 를 쓰면 변환 실패 시 해당 문장만 건너뛴다. `propagate` 로 두면 파이프라인 전체가 실패할 수 있으므로, 변환 프로세서에서는 `ignore` 가 기본 선택이다. 다만 실패가 조용해지므로 오류 카운터를 함께 본다.

PII 제거도 같은 프로세서에서 처리한다. 쿼리 스트링의 토큰, 헤더의 인증 정보, 사용자 식별자는 백엔드에 도달하기 전에 지운다. 트레이스 백엔드는 대개 장기 보관되므로, 한 번 들어간 개인정보는 회수가 어렵다.

## 8. 도입 순서와 트레이드오프 정리

단계적 도입 경로를 권한다.

**1단계** — 에이전트만 배포하고 패스스루로 전달. 자격증명 분리와 k8s 메타데이터 보강만 얻는다. 리스크가 거의 없다.

**2단계** — 게이트웨이 추가, 배치·큐·재시도 설정. 백엔드 장애 내성을 확보한다.

**3단계** — spanmetrics 도입. 이 시점에 카디널리티 정책(경로 정규화, 차원 화이트리스트)을 먼저 정한다.

**4단계** — 테일 샘플링. loadbalancing 계층이 추가되므로 운영 복잡도가 한 단계 오른다. 트레이스 저장 비용이 실제 문제가 된 뒤에 도입한다.

| 결정 | 얻는 것 | 잃는 것 |
| --- | --- | --- |
| 에이전트 + 게이트웨이 | 장애 내성, 중앙 정책 | 홉 1개, 운영 컴포넌트 |
| 테일 샘플링 | 에러 100% 보존, 저장 비용↓ | 메모리, 라우팅 복잡도 |
| spanmetrics | 계측 없이 RED | 카디널리티 리스크 |
| 디스크 큐 | 재시작 내성 | 지연, PVC 의존 |
| 100% 수집 | 완전한 데이터 | 비용 선형 증가 |

핵심 원칙 하나만 남긴다면 이것이다. **Collector 는 관측 시스템의 단일 장애점이 될 수 있으므로, Collector 자신의 지표를 별도 경로로 내보낸다.** Collector 가 죽었는데 그 사실을 Collector 를 통해 알려고 하면 알 수 없다. 자기 지표만큼은 Prometheus 가 직접 스크레이프하도록 구성하는 것이 안전하다.

## 참고

- OpenTelemetry Collector Documentation — Architecture / Configuration
- opentelemetry-collector-contrib — tailsamplingprocessor README
- opentelemetry-collector-contrib — spanmetricsconnector README
- OpenTelemetry Transformation Language (OTTL) Specification
- OpenTelemetry Semantic Conventions — HTTP (stable)
- OpenTelemetry Collector — Scaling the Collector
