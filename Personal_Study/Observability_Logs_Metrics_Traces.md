# 관찰 가능성(Observability) 3종 세트 - Logs/Metrics/Traces

> 2026-04-21 신규 주제 · 확장 대상: Docker & CI → DevOps 관찰성 영역 신규

## 학습 목표

- Observability 3 Pillars (Logs/Metrics/Traces)의 차이와 상호 보완 관계를 설명할 수 있다
- Spring Boot 앱에 Micrometer + Prometheus + Grafana를 구성할 수 있다
- OpenTelemetry로 분산 추적을 구현하고 Tempo/Jaeger에서 확인할 수 있다

## 목차 (PDF 10장 분량)

### 1. Observability vs Monitoring
- Monitoring: 알려진 문제 감지
- Observability: 미지의 문제까지 설명 가능
- Three Pillars: Logs / Metrics / Traces

### 2. Metrics - Prometheus + Grafana
- Pull 기반 모델, `/actuator/prometheus`
- Spring Boot + Micrometer 의존성
- PromQL 기초: `rate()`, `histogram_quantile()`
- Grafana Dashboard Import (ID 4701, 12900)

### 3. Logs - Loki + Promtail
- Grafana Loki: Prometheus-style 로그 수집
- Promtail로 컨테이너 로그 수집
- LogQL: `{app="order"} |= "ERROR"`
- Logback JSON Layout (logstash-logback-encoder)

### 4. Traces - OpenTelemetry + Tempo/Jaeger
- Trace, Span, Context
- W3C Trace Context 헤더 (`traceparent`, `tracestate`) vs B3
- OpenTelemetry Java Agent (auto-instrumentation)
- Manual Instrumentation: `@WithSpan`

### 5. Spring Boot Actuator 심화
- `/actuator/health`, `/actuator/info`, `/actuator/metrics`
- Custom Indicator, Custom Metric
- Security 설정 (운영 환경 주의)

### 6. Micrometer 태그 전략
- 저카디널리티 태그 원칙
- Common Tags (`management.metrics.tags.*`)

### 7. 분산 추적 실전
- Correlation ID 자동 전파 (Feign/WebClient/RestTemplate)
- DB Query 추적, Kafka Producer/Consumer 연동

### 8. Docker Compose로 One-Shot 구성
- Prometheus · Grafana · Loki · Promtail · Tempo · OTel Collector 한 번에
- 샘플 `docker-compose.yml` 제공

### 9. SLO / SLI / Error Budget
- Google SRE 워크북 기반
- Burn Rate Alert

## 참고

- Docker & CI (기학습) 연계
- Spring Cloud (기학습) - Sleuth는 Deprecated → Micrometer Tracing 전환
- OpenTelemetry 공식 문서
- "Observability Engineering" (Charity Majors)

Notion 원본: https://www.notion.so/3485a06fd6d381ad93dad7e110a6663e
