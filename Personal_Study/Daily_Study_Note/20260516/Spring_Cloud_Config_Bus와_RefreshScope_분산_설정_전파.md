Notion 원본: https://www.notion.so/3615a06fd6d38120a257cbd256c52577

# Spring Cloud Config Bus와 RefreshScope — Kafka/RabbitMQ 기반 분산 설정 전파 메커니즘

> 2026-05-16 신규 주제 · 확장 대상: Spring (Boot/Cloud)

## 학습 목표

- Spring Cloud Config Server 의 설정 저장소 모델과 `@RefreshScope` 빈 라이프사이클을 설명한다
- Spring Cloud Bus 가 `/busrefresh` 이벤트를 Kafka 또는 RabbitMQ 토픽으로 fan-out 하는 메시지 흐름을 추적한다
- `RefreshScope` 의 lazy proxy + cache eviction 동작과 동시 갱신 시 발생 가능한 race condition 을 코드로 검증한다
- destination service / event id 필터링, audit log, 운영 환경에서의 polling vs push 트레이드오프를 분석한다

## 1. Config Server 와 클라이언트 부트스트랩 구조

Spring Cloud Config Server 는 Git/SVN/Vault/JDBC 백엔드의 설정을 *application name + profile + label* 좌표로 조회 가능한 REST endpoint 로 노출한다.

```
GET /{application}/{profile}[/{label}]
GET /order-service/prod/main
```

응답은 properties 또는 yaml 의 직렬화된 JSON. 클라이언트(Spring Boot 애플리케이션) 는 bootstrap phase 에서 이 endpoint 를 호출해 PropertySource 를 환경(Environment) 에 머지한다. `spring.config.import=configserver:` 를 `application.yml` 에 두는 Boot 2.4+ 방식이 권장된다.

```yaml
spring:
  application:
    name: order-service
  config:
    import: "optional:configserver:http://config-server:8888"
  profiles:
    active: prod
```

설정값이 바뀌면 클라이언트가 *재시작 없이* 새 값을 받아야 한다. 단순 재호출만으로는 부족한데, 이미 빈 그래프에 *주입된 값* 은 객체 안에 박혀 있어서 그대로 둔다. 그래서 `@RefreshScope` 가 필요하다.

## 2. @RefreshScope — Lazy Proxy 와 Cache Eviction

`@RefreshScope` 는 `org.springframework.cloud.context.scope.refresh.RefreshScope` 를 가리키는 스코프 빈이다. 동작 핵심은 두 가지다.

1. **Lazy proxy**: 빈 주입 시점에 실제 인스턴스를 만들지 않고 CGLIB proxy 만 만든다. 메서드 호출 때마다 `RefreshScope.get(name)` 으로 캐시된 실제 인스턴스를 lookup.
2. **Cache eviction**: refresh 이벤트가 오면 캐시된 인스턴스를 *destroy + 재생성*. 다음 호출이 새 환경 값을 읽어 새 빈을 만든다.

```java
@Component
@RefreshScope
public class RateLimitConfig {

	@Value("${ratelimit.requestsPerSecond:10}")
	private int requestsPerSecond;

	public int getLimit() {
		return requestsPerSecond;
	}
}
```

리프레시는 `RefreshEndpoint.refresh()` (Actuator `POST /actuator/refresh`) 또는 프로그래밍적으로 `ContextRefresher.refresh()` 로 트리거한다. 단일 인스턴스에는 효과적이지만, 인스턴스가 10개 이상으로 늘어나면 각각에 PUSH 또는 PULL 을 해야 한다. 이 fan-out 책임을 Bus 가 가져간다.

## 3. Spring Cloud Bus — Broker 기반 이벤트 전파

Bus 는 Kafka 또는 RabbitMQ broker 를 사이에 두고 모든 클라이언트가 동일 topic/exchange 를 구독하는 구조다. 어느 노드든 `POST /actuator/busrefresh` 를 호출하면 broker 에 `RefreshRemoteApplicationEvent` 를 publish 하고, 모든 구독자가 이를 수신해 로컬 RefreshScope 를 갱신한다.

```
Operator → /busrefresh → broker 토픽 → 모든 구독자 수신 → ContextRefresher.refresh() → @RefreshScope evict
```

의존성 (Kafka 예시):

```groovy
implementation 'org.springframework.cloud:spring-cloud-starter-bus-kafka'
implementation 'org.springframework.cloud:spring-cloud-config-client'
```

```yaml
spring:
  cloud:
    bus:
      enabled: true
      destination: springCloudBus
      trace:
        enabled: true
    stream:
      kafka:
        binder:
          brokers: kafka-1:9092,kafka-2:9092
management:
  endpoints:
    web:
      exposure:
        include: busrefresh,bus-env,bus-events,refresh
```

## 4. RefreshRemoteApplicationEvent 페이로드와 필터링

```json
{
  "type": "RefreshRemoteApplicationEvent",
  "timestamp": 1747400000000,
  "originService": "order-service:prod:8081",
  "destinationService": "order-service:**",
  "id": "e0a2..."
}
```

`destinationService` 는 wildcard 매칭이다.

- `**` — 모든 서비스
- `order-service:**` — order-service 의 모든 profile/port
- `order-service:prod:**` — order-service prod 만
- `order-service:**:8081` — 특정 포트(인스턴스 단위)

운영에서 자주 쓰는 패턴은 *특정 인스턴스만 refresh* 시켜 카나리 검증을 하는 것. `id` 는 dedup 용으로 이미 처리한 event id 는 무시한다.

## 5. Config Server Monitor — Webhook 으로 자동화

수동으로 `/busrefresh` 를 호출하는 대신 Git 푸시 시 자동 refresh 가 일반적이다. Spring Cloud Config Server 에는 `spring-cloud-config-monitor` 가 있고, GitHub/GitLab/Bitbucket webhook 을 `/monitor` endpoint 로 받아 변경된 파일에서 영향받는 application name 을 계산해 정확히 그 destination 으로 bus event 를 publish 한다.

```yaml
spring:
  cloud:
    config:
      server:
        monitor:
          enabled: true
```

이렇게 하면 Git push → 30초 이내에 모든 인스턴스가 자동으로 새 설정으로 갱신된다.

## 6. RefreshScope 동작의 race condition 과 thread safety

리프레시는 *유효 인스턴스 교체* 다. 그래서 갱신 중에 다른 스레드가 같은 빈을 사용하면 일관성이 깨질 수 있다. 대응:

- *원자성이 필요한 그룹은 한 객체로 묶기*. 두 값을 동시 읽어야 하면 같은 빈 메서드 내에서 둘 다 읽어 즉시 사용.
- *immutable snapshot 패턴*: 값을 record 로 묶어 한 번에 읽기.

```java
@Component
@RefreshScope
public class FeatureSnapshot {
	private final FeatureValues values;
	public FeatureSnapshot(@Value("${feature.newCheckout:false}") boolean a,
			@Value("${feature.maxRetries:3}") int r) {
		this.values = new FeatureValues(a, r);
	}
	public FeatureValues values() { return values; }
	public record FeatureValues(boolean newCheckout, int maxRetries) {}
}
```

호출 측은 `featureSnapshot.values()` 로 일관된 스냅샷을 받는다. 리프레시가 일어나도 *이미 받은 record* 는 변하지 않는다.

## 7. Bus 메시지 트레이스 — /actuator/bus-events

`spring.cloud.bus.trace.enabled=true` 이면 최근 받은 이벤트가 `/actuator/bus-events` 에 노출된다. 운영 audit 와 디버깅에 유용하다.

```json
{
  "events": [
    {
      "id": "abc-123",
      "type": "RefreshRemoteApplicationEvent",
      "timestamp": 1747400000000,
      "originService": "config-server:prod:8888",
      "destinationService": "order-service:**"
    }
  ]
}
```

## 8. Kafka vs RabbitMQ — Broker 선택과 트레이드오프

| 항목 | Kafka Bus | RabbitMQ Bus |
|---|---|---|
| 메시지 보존 | 토픽 retention 기간 동안 유지 | exchange→queue, 소비 후 삭제 |
| Late join 인스턴스 | retention 내 과거 이벤트 가능 | 가입 이후 이벤트만 |
| 운영 부담 | 클러스터/KRaft 운영 비용 큼 | 비교적 단순 |
| 처리량 | 매우 높음 (필요 이상) | 충분 |
| 메시지 손실 가능성 | min.insync.replicas 로 제어 | mirroring + ack 로 제어 |
| 사용 권장 시점 | 이미 Kafka 인프라 보유 | Bus 단독 용도라면 RabbitMQ |

Bus 이벤트는 손실 허용도가 높다. Bus 단독 목적이라면 RabbitMQ 가 운영비용 낮다는 게 다수 사례.

## 9. 운영 체크리스트와 흔한 사고

체크리스트:

- `@RefreshScope` 가 *Controller* 에 직접 붙어 있지 않은지. 설정 holder 빈으로 분리.
- DB connection pool / Cache manager 같은 *값비싼 resource* 는 `@RefreshScope` 를 함부로 붙이지 않는다.
- secret/password 같은 *민감값*은 vault backend 사용 + access log 마스킹.
- `spring.cloud.bus.id` 를 *unique* 하게 부여. 이벤트 dedup 의 키.
- broker 인증 (Kafka SASL, RabbitMQ TLS) 누락 시 누구나 `/busrefresh` 를 broker 에 직접 publish 가능.
- Webhook URL 에 토큰 검증 추가.

흔한 사고:

1. Config Server 가 Git pull 실패 → `/busrefresh` 이후 클라이언트가 이전 캐시 그대로. Config Server 의 git fetch latency 메트릭 노출.
2. 한 인스턴스가 broker 연결 못 받음 → 인스턴스마다 마지막 리프레시 시각 메트릭 노출해 outlier 감지.
3. RefreshScope 빈이 `@PostConstruct` 에서 무거운 작업 수행 → 리프레시마다 응답 지연.

## 10. 대안 — Kubernetes ConfigMap reload, Vault Dynamic Secrets

Spring Cloud Config + Bus 가 모든 환경의 정답은 아니다. Kubernetes 운영에서는 ConfigMap 을 직접 마운트하고 *Reloader* 같은 컨트롤러로 ConfigMap 변경 시 Deployment 를 rolling restart 하는 패턴도 흔하다.

| 비교 항목 | Spring Cloud Config + Bus | Kubernetes ConfigMap + Reloader |
|---|---|---|
| 무중단 갱신 | RefreshScope 로 in-process | rolling restart 필요 |
| broker 의존성 | Kafka/Rabbit 필요 | 없음 |
| 설정 버전 관리 | Git 백엔드로 자연스러움 | ConfigMap 매니페스트 Git 관리 |
| Secret 처리 | Vault backend 통합 | ESO / Sealed Secrets 별도 |
| 멀티 환경 일관성 | profile/label 좌표 분리 | namespace 단위 분리 |
| 마이그레이션 부담 | 기존 Spring Boot 앱과 잘 맞음 | 클라우드 네이티브 신규 앱 |

Vault Dynamic Secrets 을 쓰면 DB 비밀번호 같은 *시간 만료* 자격 증명을 RefreshScope 와 결합해 자동 갱신 가능. `spring-cloud-vault-config-databases` 가 TTL 추적하고 자동 renew.

운영 권장 — Hybrid 전략: 인프라 endpoint 는 ConfigMap, 비즈니스 feature flag 는 Spring Cloud Config + Bus, secret 은 Vault.

## 참고

- Spring Cloud Config Reference (https://docs.spring.io/spring-cloud-config/reference/)
- Spring Cloud Bus Reference (https://docs.spring.io/spring-cloud-bus/reference/)
- Spring Cloud Commons — RefreshScope 구현 소스 (spring-cloud/spring-cloud-commons github)
- Sébastien Deleuze, "Refresh Scope and Spring Cloud Bus deep dive", SpringOne 발표 자료
- Marcin Grzejszczak Spring Cloud Bus 블로그 시리즈
