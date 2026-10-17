Notion 원본: https://app.notion.com/p/3fc5a06fd6d38132b1e6e4956e584b76?pvs=204

# Resilience4j CircuitBreaker 슬라이딩 윈도우와 Bulkhead·RateLimiter 조합 및 Spring Boot 장애 격리 설계

> 2026-10-17 신규 주제 · 확장 대상: Spring

## 학습 목표

- CircuitBreaker의 COUNT_BASED·TIME_BASED 슬라이딩 윈도우가 실패율을 계산하는 방식을 구분한다.
- 서킷 상태 전이(CLOSED, OPEN, HALF_OPEN)에 영향을 주는 설정 키를 용도별로 튜닝한다.
- Bulkhead와 RateLimiter를 CircuitBreaker와 함께 배치해 자원 고갈 경로를 차단한다.
- Spring Boot에서 어노테이션 적용 순서, fallback, Actuator 메트릭으로 장애 격리 구조를 검증한다.

## 1. 장애 격리가 필요한 이유와 세 가지 패턴의 역할 분담

마이크로서비스에서 하위 의존성 하나가 느려지면 호출한 쪽의 스레드가 응답을 기다리며 쌓이고, 결국 서블릿 컨테이너의 요청 스레드가 모두 묶여 무관한 API까지 멈추는 연쇄 장애가 생긴다. Tomcat의 기본 최대 요청 스레드가 200개이고 HikariCP 기본 풀 크기가 10개라는 점을 생각하면, 응답 시간이 정상 50ms에서 5초로 100배 늘어나는 순간 같은 처리량을 유지하려면 동시 실행 수가 100배 필요해지고 풀은 몇 초 안에 바닥난다. 이 현상은 Little의 법칙(동시 실행 수 = 도착률 x 응답 시간)으로 바로 계산된다. 초당 100건이 들어오는 API에서 응답 시간이 50ms이면 평균 동시 실행 수는 5, 5초이면 500이므로 스레드 200개로는 버티지 못한다.

Resilience4j는 이 문제를 서로 다른 축에서 막는 모듈을 제공한다. CircuitBreaker는 실패율과 느린 호출 비율을 보고 호출 자체를 끉는다. Bulkhead는 동시에 진행 중인 호출 수를 제한해 한 의존성이 자원을 독점하지 못하게 한다. RateLimiter는 단위 시간당 호출 수를 제한해 내가 하위 시스템을 과부하로 몰지 않게 하거나 외부 호출량 쿼터를 지킨다. 세 가지는 대체 관계가 아니라 보완 관계이며, 어떤 지표를 기준으로 거부하는지가 다르다.

| 패턴 | 제한 기준 | 거부 시 예외 | 주된 방어 대상 |
|---|---|---|---|
| CircuitBreaker | 최근 호출의 실패율, 느린 호출 비율 | CallNotPermittedException | 이미 고장 난 의존성 호출 낙비 |
| Bulkhead | 동시 실행 호출 수 | BulkheadFullException | 스레드·커넥션 풀 독점 |
| RateLimiter | 주기당 허용 호출 수 | RequestNotPermitted | 처리량 폭주, 외부 API 쿼터 초과 |

이 표에서 중요한 것은 실패를 관측해야 동작하는 패턴(CircuitBreaker)과 관측 없이 사전에 상한을 걸는 패턴(Bulkhead, RateLimiter)의 차이다. 앞의 것은 반응형, 뒤의 것은 예방형이므로 둘을 함께 써야 장애 초기의 느린 응답 구간(아직 실패율은 낮지만 동시 실행 수가 폭증하는 구간)을 Bulkhead가 먼저 막아 준다.

## 2. 의존성과 Spring Boot 기본 설정

Spring Boot 3 계열에서는 resilience4j-spring-boot3 스타터를 사용한다. 어노테이션 기반 적용은 AOP에 의존하므로 spring-boot-starter-aop가 필요하고, 메트릭과 헬스 노출을 위해 actuator를 추가한다. Boot 2 계열은 resilience4j-spring-boot2 아티팩트를 쓴다.

```groovy
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

설정은 application.yml의 resilience4j 접두어 아래에 모듈별로 configs(재사용 가능한 기본 템플릿)와 instances(실제 이름 붙은 인스턴스)로 나눈다. instances 항목에서 baseConfig로 템플릿을 상속하고 필요한 값만 덮어쓰는 방식이 인스턴스가 많을 때 중복을 줄여 준다.

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20
        minimumNumberOfCalls: 10
        failureRateThreshold: 50
        slowCallDurationThreshold: 2s
        slowCallRateThreshold: 80
        waitDurationInOpenState: 20s
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
    instances:
      paymentApi:
        baseConfig: default
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,circuitbreakers,circuitbreakerevents
  health:
    circuitbreakers:
      enabled: true
```

기본값을 알고 있어야 튀닝 방향이 잡힌다. 공식 문서 기준으로 slidingWindowSize와 minimumNumberOfCalls의 기본값은 모두 100, failureRateThreshold는 50, slowCallRateThreshold는 100, slowCallDurationThreshold는 60초, waitDurationInOpenState는 60초, permittedNumberOfCallsInHalfOpenState는 10이며 automaticTransitionFromOpenToHalfOpenEnabled는 false다. 즉 기본값 그대로면 100건이 쌓이기 전에는 서킷이 열리지 않으므로, 트래픽이 낮은 서비스에서는 서킷이 사실상 동작하지 않는 함정이 있다.

## 3. 슬라이딩 윈도우: COUNT_BASED와 TIME_BASED

CircuitBreaker는 호출 결과를 슬라이딩 윈도우에 기록하고 그 안의 실패율과 느린 호출 비율로 상태를 전이한다. COUNT_BASED는 최근 N건의 호출을 크기 N의 원형 배열에 저장한다. 호출이 끝날 때마다 가장 오래된 측정값이 밀려나고 집계가 갱신되므로 호출량과 무관하게 항상 최근 N건만 본다. 메모리는 N에 비례한다. TIME_BASED는 최근 N초를 보며, 1초 단위 부분 집계(partial aggregation) N개를 원형 배열로 유지한다. 각 버킷에는 해당 초의 총 호출 수, 실패 수, 느린 호출 수, 총 소요 시간이 합산되어 있으므로 호출 하나가 들어올 때 윈도우 전체를 다시 훑지 않고 상수 시간에 갱신된다.

두 방식은 같은 실패율 공식을 쓰지만 트래픽 모양에 따라 민감도가 달라진다. 초당 1000건이 들어오는 서비스에서 COUNT_BASED 크기 20은 사실상 0.02초 분량만 보므로 순간적인 잡음에 서킷이 열릴 수 있고, 초당 1건 이하인 서비스에서 TIME_BASED 60초는 몇 건 안 되는 표본으로 실패율을 계산하게 된다. 그래서 고트래픽 경로에는 TIME_BASED, 저트래픽 경로에는 COUNT_BASED가 일반적으로 잘 맞는다.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      searchApi:            # 고트래픽: 최근 10초를 본다
        slidingWindowType: TIME_BASED
        slidingWindowSize: 10
        minimumNumberOfCalls: 50
        failureRateThreshold: 40
      batchApi:             # 저트래픽: 최근 10건을 본다
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
```

minimumNumberOfCalls는 표본이 이 값에 도달하기 전에는 실패율을 계산하지 않게 하는 안전장치다. COUNT_BASED에서 크기 10, 최소 5건, 임계 50이면 처음 5건 중 3건이 실패(60%)한 시점에 곳바로 OPEN이 된다. 반대로 최소 호출 수를 윈도우 크기와 같게 두면 윈도우가 가득 찬 뒤에야 판단하므로 오탐은 줄지만 반응이 늦어진다. TIME_BASED에서는 minimumNumberOfCalls가 윈도우의 총 호출 수와 비교된다는 점도 기억해야 한다. 아래 표는 선택 기준을 정리한 것이다.

| 구분 | COUNT_BASED | TIME_BASED |
|---|---|---|
| 윈도우 단위 | 최근 N건 | 최근 N초 |
| 내부 구조 | 호출 N개 원형 배열 | 1초 버킷 N개 원형 배열 |
| 유리한 트래픽 | 저빈도, 호출 간격 불규칙 | 고빈도, 시간 기준 SLO |
| 약점 | 고트래픽에서 윈도우가 너무 짧아짐 | 저트래픽에서 표본 부족 |

## 4. 상태 전이와 느린 호출 처리

서킷은 CLOSED에서 시작해 실패율이나 느린 호출 비율이 임계값 이상이면 OPEN으로 간다. OPEN에서는 호출이 CallNotPermittedException으로 즉시 거부된다. waitDurationInOpenState가 지나면 HALF_OPEN으로 넘어가는데, automaticTransitionFromOpenToHalfOpenEnabled가 false(기본)이면 대기 시간이 지난 뒤 첫 호출이 들어올 때 전이하고, true이면 내부 스케줄러가 별도 스레드로 시간 경과만으로 전이시킨다. HALF_OPEN에서는 permittedNumberOfCallsInHalfOpenState만큼의 시험 호출을 허용하고 그 결과의 실패율이 임계값 미만이면 CLOSED로 복귀하며 아니면 다시 OPEN이 된다. HALF_OPEN에서 시험 호출 수만큼 결과가 모이지 않고 멈추는 경우를 막으려면 maxWaitDurationInHalfOpenState를 지정할 수 있고, 기본값 0은 시험 호출이 모두 끝날 때까지 무한정 기다린다는 뜻이다.

느린 호출은 에러가 아니어도 자원을 오래 잡으므로 slowCallDurationThreshold를 넘는 호출을 느린 것으로 집계하고 그 비율이 slowCallRateThreshold 이상이면 OPEN으로 간다. 다만 이것은 호출이 끝난 뒤에 소요 시간으로 판정하는 것이지 호출을 중단시키지는 않는다. 호출을 끊으려면 HTTP 클라이언트의 타임아웃이나 TimeLimiter가 별도로 필요하다. 이 점을 놓치면 서킷은 열렸는데 이미 진행 중이던 느린 호출이 계속 스레드를 점유한다.

어떤 예외를 실패로 볼지도 중요하다. 클라이언트 입력 오류(HTTP 4xx)까지 실패로 집계하면 사용자 실수만으로 서킷이 열리므로 recordExceptions와 ignoreExceptions로 구분한다. ignoreExceptions에 해당하는 예외는 성공도 실패도 아닌 것으로 취급되어 윈도우 집계에서 빠진다.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.HttpServerErrorException
        ignoreExceptions:
          - com.example.payment.InvalidOrderException
```

## 5. Bulkhead: 세마포어 방식과 스레드풀 방식

Bulkhead는 선박의 격벽처럼 자원을 구획으로 나눠 한 구획의 침수가 전체로 번지지 않게 한다. Resilience4j에는 두 구현이 있다. SemaphoreBulkhead는 호출 스레드 위에서 세마포어로 동시 실행 수만 센다. 설정은 maxConcurrentCalls(기본 25)와 maxWaitDuration(기본 0, 즉 대기 없이 즉시 거부)이다. ThreadPoolBulkhead는 별도의 스레드풀과 큐로 호출을 넘기며 maxThreadPoolSize, coreThreadPoolSize, queueCapacity(기본 100), keepAliveDuration 등을 쓰고 CompletableFuture를 반환하는 메서드에만 적용된다. 전용 스레드풀은 호출 스레드와 완전히 분리되는 대신 컨텍스트 전환 비용이 있고 ThreadLocal 기반 정보(보안 컨텍스트, MDC)가 전달되지 않는 점을 신경 써야 한다.

```yaml
resilience4j:
  bulkhead:
    instances:
      paymentApi:
        maxConcurrentCalls: 15
        maxWaitDuration: 0ms
  thread-pool-bulkhead:
    instances:
      reportApi:
        maxThreadPoolSize: 8
        coreThreadPoolSize: 4
        queueCapacity: 20
```

용량 산정은 Little의 법칙으로 시작한다. 결제 API의 정상 p99가 200ms이고 목표 처리량이 초당 50건이면 필요한 동시 실행 수는 약 10이므로 여유를 두어 maxConcurrentCalls를 15 안팔으로 잡는다. 전체 요청 스레드가 200개일 때 이 의존성에 15개만 허용하면 결제 서버가 완전히 멈추더라도 나머지 185개 스레드는 다른 API를 처리할 수 있다. maxWaitDuration을 길게 주면 순간 폭주를 흡수하지만 대기하는 스레드도 요청 스레드이므로 격리 효과가 약해진다. 요청 스레드를 보호하는 목적이라면 0 또는 수십 ms가 낫다.

## 6. RateLimiter: 주기 기반 허용량 제어

RateLimiter의 핵심 설정은 limitForPeriod(주기당 허용 호출 수, 기본 50), limitRefreshPeriod(주기 길이, 기본 500ns), timeoutDuration(허용량이 없을 때 대기하는 최대 시간, 기본 5초)이다. 기본 주기가 매우 짧아 그대로 쓰면 의도와 다르게 동작하므로 실무에서는 주기를 1초 등으로 명시한다. 허용량이 소진되면 timeoutDuration 동안 스레드가 대기하다 얻지 못하면 RequestNotPermitted가 발생한다. 대기 중에도 스레드를 잡고 있으므로 요청 스레드를 보호해야 하는 경로에서는 timeoutDuration을 0으로 두고 즉시 거부하는 편이 안전하다.

```yaml
resilience4j:
  ratelimiter:
    instances:
      externalSms:
        limitForPeriod: 10
        limitRefreshPeriod: 1s
        timeoutDuration: 0
```

```java
@Service
public class SmsClient {

    @RateLimiter(name = "externalSms", fallbackMethod = "rejected")
    public SmsResult send(String phone, String text) {
        return smsGateway.post(phone, text);
    }

    private SmsResult rejected(String phone, String text, RequestNotPermitted e) {
        return SmsResult.queuedForRetry(phone);
    }
}
```

RateLimiter는 호출 측에서 외부 쿼터(예: 초당 10건)를 지키는 용도와, 내 서비스가 받는 인입 트래픽을 제한하는 용도로 모두 쓰이지만 서버 인입 제한은 게이트웨이나 필터에서 사용자·키 단위로 하는 편이 정확하다. 인스턴스 안의 한도는 JVM 단위이므로 파드가 여러 개이면 전체 한도는 파드 수 곱하기 limitForPeriod가 된다는 점을 계산에 넣어야 한다. 분산 환경의 전역 제한이 필요하면 Redis 기반 구현 등 별도 수단을 써야 한다.

## 7. 세 패턴의 조합과 Spring 어노테이션 적용 순서

Spring Boot 스타터는 어노테이션별 AOP 어드밴스의 기본 적용 순서를 정해 두었다. 공식 문서에 따르면 바깥에서 안쪽 순으로 Retry, CircuitBreaker, RateLimiter, TimeLimiter, Bulkhead가 호출을 감싼다. 즉 Retry가 가장 바깥, Bulkhead가 실제 메서드에 가장 가깝다. 이 순서에서는 Bulkhead가 거부한 호출도 CircuitBreaker 입장에서는 예외로 보일 수 있으므로 BulkheadFullException을 실패로 집계할지 ignoreExceptions에 넣을지 의도적으로 결정해야 한다. 같은 원리로 CallNotPermittedException을 Retry가 재시도 대상으로 삼으면 열린 서킷에 불필요한 재시도가 몰리므로 retry의 ignoreExceptions에 넣는다. 순서를 바꾸려면 각 모듈의 aspect order 속성(예: resilience4j.circuitbreaker.circuitBreakerAspectOrder, resilience4j.bulkhead.bulkheadAspectOrder)을 조정한다. 값이 클수록 안쪽에 위치한다.

```java
@Service
@RequiredArgsConstructor
public class PaymentClient {

    private final RestClient restClient;

    @CircuitBreaker(name = "paymentApi", fallbackMethod = "fallback")
    @Bulkhead(name = "paymentApi")
    @RateLimiter(name = "paymentApi")
    public PaymentResult pay(PaymentRequest request) {
        return restClient.post()
            .uri("/v1/payments")
            .body(request)
            .retrieve()
            .body(PaymentResult.class);
    }

    private PaymentResult fallback(PaymentRequest request, CallNotPermittedException e) {
        return PaymentResult.deferred(request.orderId(), "CIRCUIT_OPEN");
    }

    private PaymentResult fallback(PaymentRequest request, BulkheadFullException e) {
        return PaymentResult.deferred(request.orderId(), "BULKHEAD_FULL");
    }

    private PaymentResult fallback(PaymentRequest request, Throwable t) {
        return PaymentResult.deferred(request.orderId(), "UNAVAILABLE");
    }
}
```

fallback 메서드는 원본 메서드와 같은 반환 타입과 같은 파라미터 뒤에 예외 파라미터 하나를 받는 시그니처여야 하며, 여러 개를 오버로딩하면 가장 구체적인 예외 타입과 매칭되는 쪽이 선택된다. 같은 클래스 안에서 this로 호출하면 프록시를 거치지 않아 어노테이션이 적용되지 않으므로 다른 빈에서 호출해야 한다. 여러 모듈에 같은 name을 쓰는 것은 문제가 없는데, 각 모듈의 레지스트리가 서로 독립이기 때문이다.

조합 설계에서 실무적으로 가장 중요한 것은 임계값의 계층이다. 안쪽 방어선(Bulkhead 15, 클라이언트 읽기 타임아웃 2초)이 먼저 작동하고, 그 결과 쌓이는 실패와 느린 호출을 CircuitBreaker가 집계해 OPEN으로 만드는 순서가 되도록 한다. 반대로 CircuitBreaker의 slowCallDurationThreshold를 HTTP 타임아웃보다 길게 잡으면 느린 호출이 타임아웃으로 먼저 잘려 실패로 기록되므로 느린 호출 지표가 의미를 잃는다. 예를 들어 읽기 타임아웃 3초, slowCallDurationThreshold 1초, Bulkhead 15, 서킷 failureRateThreshold 50, slowCallRateThreshold 80으로 맞추면 1~3초 사이의 성능 저하는 느린 호출로, 3초 초과는 타임아웃 실패로 각각 집계된다.

## 8. 관측과 검증: Actuator, 메트릭, 테스트

설정이 의도대로 작동하는지는 관측으로 확인한다. 위 설정처럼 circuitbreakers 엔드포인트를 노출하면 /actuator/circuitbreakers에서 인스턴스별 상태와 실패율을, /actuator/circuitbreakerevents에서 최근 이벤트(상태 전이, 허용되지 않은 호출 등)를 볼 수 있다. Micrometer를 쓰면 Prometheus에서 다음 메트릭 이름으로 수집된다: resilience4j_circuitbreaker_state, resilience4j_circuitbreaker_failure_rate, resilience4j_circuitbreaker_slow_call_rate, resilience4j_circuitbreaker_calls_seconds_count, resilience4j_bulkhead_available_concurrent_calls, resilience4j_ratelimiter_available_permissions. 경보는 서킷 OPEN 상태 지속 시간, Bulkhead 가용량이 0에 붙는 비율, RateLimiter 거부 증가율을 기준으로 건다.

```java
@SpringBootTest
class PaymentClientCircuitTest {

    @Autowired CircuitBreakerRegistry registry;
    @Autowired PaymentClient client;

    @Test
    void opensAfterFailureRateExceeded() {
        CircuitBreaker cb = registry.circuitBreaker("paymentApi");
        cb.reset();

        for (int i = 0; i < 10; i++) {
            cb.onError(0, TimeUnit.MILLISECONDS, new IOException("boom"));
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(client.pay(sample()).status()).isEqualTo("DEFERRED");
    }
}
```

CircuitBreaker의 onError, onSuccess를 직접 호출하면 외부 서버 없이 상태 전이를 결정적으로 검증할 수 있고, 윈도우 크기와 최소 호출 수를 바꾸어 가리며 몇 번째 호출에서 열리는지 경계값을 확인하면 설정 오류를 배포 전에 걸러 낸다. 마지막으로 운영 튜닝 순서를 제안하면, 먼저 하위 시스템의 정상 p99와 목표 처리량으로 Bulkhead 크기를 정하고, 클라이언트 타임아웃을 p99의 3~5배 안에서 정하며, 그 값보다 작은 slowCallDurationThreshold를 두고, 마지막으로 트래픽 모양에 맞는 슬라이딩 윈도우 유형과 minimumNumberOfCalls를 정한다. 이후 장애 주입(하위 서비스 지연 2초, 오류율 60%)을 해서 서킷이 열리는 시점, fallback 응답 비율, 다른 API의 지연 변화를 메트릭으로 확인하고 값을 조정한다. 서킷이 지나치게 민감하면 정상 서비스를 차단하고 지나치게 둔하면 보호 효과가 없으므로, 이 값들은 한 번 정하고 끝나는 상수가 아니라 장애 훈련 결과로 계속 보정해야 하는 운영 파라미터다.

## 참고

- Resilience4j 공식 문서 CircuitBreaker: https://resilience4j.readme.io/docs/circuitbreaker
- Resilience4j 공식 문서 Bulkhead: https://resilience4j.readme.io/docs/bulkhead
- Resilience4j 공식 문서 RateLimiter: https://resilience4j.readme.io/docs/ratelimiter
- Resilience4j Spring Boot 3 가이드: https://resilience4j.readme.io/docs/getting-started-3
- Resilience4j GitHub 저장소: https://github.com/resilience4j/resilience4j
- Michael Nygard, Release It! 2nd Edition (Pragmatic Bookshelf)
